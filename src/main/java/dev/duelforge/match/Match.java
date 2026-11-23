package dev.duelforge.match;

import dev.duelforge.arena.Arena;
import dev.duelforge.kit.Kit;
import dev.duelforge.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Runtime state of one duel (1v1 / team) or free-for-all match. */
public final class Match {
    /** BUILDING = the map is being built; BETWEEN = short pause between rounds of a best-of series. */
    public enum State { LOBBY, BUILDING, COUNTDOWN, ACTIVE, BETWEEN, ENDING }

    public final int id;
    /** Null until the map exists (auto-built maps are created after the match is set up). */
    public Arena arena;
    public final Kit kit;
    public final boolean ranked;
    public final boolean ffa;
    public final UUID host;
    public State state = State.LOBBY;

    public int maxPlayers, minPlayers, lives, countdown, endTimer, timeLimit;
    public int lobbyTimer = -1;
    public long startMillis;
    public int teamSize = 1, teamCount = 0, bestOf = 1, round = 1;
    public boolean cancelled;

    /** Everyone who ever took part (never shrinks after the lobby phase). */
    public final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    public final Set<UUID> winners = new HashSet<>();
    public final Set<UUID> eliminated = new HashSet<>();
    public final Set<UUID> left = new HashSet<>();
    public final Set<UUID> spectators = new HashSet<>();
    public final Set<UUID> dying = new HashSet<>();

    public final Map<UUID, Integer> team = new HashMap<>();
    public final Map<Integer, Integer> score = new HashMap<>();
    public final Map<UUID, Integer> spawnSlot = new HashMap<>();
    public final Map<UUID, Integer> kills = new HashMap<>();
    public final Map<UUID, Integer> deaths = new HashMap<>();
    public final Map<UUID, Integer> livesLeft = new HashMap<>();
    public final Map<UUID, UUID> lastDamager = new HashMap<>();
    public final Map<UUID, Long> lastDamageTime = new HashMap<>();
    public final Map<UUID, Long> protectedUntil = new HashMap<>();
    public final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    public final Map<Long, BlockState> changed = new HashMap<>();

    public Match(int id, Kit kit, boolean ranked, boolean ffa, UUID host) {
        this.id = id;
        this.kit = kit;
        this.ranked = ranked;
        this.ffa = ffa;
        this.host = host;
    }

    /** True once players have been teleported into the arena. */
    public boolean inArena() {
        return state == State.COUNTDOWN || state == State.ACTIVE || state == State.BETWEEN || state == State.ENDING;
    }

    public String arenaName() {
        return arena == null ? "Random" : Fmt.color(arena.displayName);
    }

    public boolean isParticipant(UUID u) {
        return players.contains(u);
    }

    public boolean isAlive(UUID u) {
        return players.contains(u) && !eliminated.contains(u);
    }

    public boolean isProtected(UUID u) {
        Long t = protectedUntil.get(u);
        return t != null && t > System.currentTimeMillis();
    }

    public int kills(UUID u) {
        return kills.getOrDefault(u, 0);
    }

    public int deaths(UUID u) {
        return deaths.getOrDefault(u, 0);
    }

    // ---- teams ----

    public int teamOf(UUID u) {
        return team.getOrDefault(u, -1);
    }

    public boolean sameTeam(UUID a, UUID b) {
        return !ffa && team.containsKey(a) && Objects.equals(team.get(a), team.get(b));
    }

    public List<UUID> members(int t) {
        List<UUID> out = new ArrayList<>();
        for (UUID u : players) if (teamOf(u) == t) out.add(u);
        return out;
    }

    public int teamAlive(int t) {
        int n = 0;
        for (UUID u : members(t)) if (isAlive(u)) n++;
        return n;
    }

    public Set<Integer> aliveTeams() {
        Set<Integer> out = new TreeSet<>();
        for (UUID u : players) if (isAlive(u) && team.containsKey(u)) out.add(team.get(u));
        return out;
    }

    // ---- players ----

    public List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID u : players) {
            if (eliminated.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    public List<Player> aliveOthers(Player self) {
        List<Player> out = alivePlayers();
        out.remove(self);
        return out;
    }

    /** Living opponents of the player (everyone else in FFA, the other team(s) otherwise). */
    public List<Player> enemiesOf(Player self) {
        List<Player> out = new ArrayList<>();
        for (Player p : alivePlayers()) {
            if (p.equals(self) || sameTeam(self.getUniqueId(), p.getUniqueId())) continue;
            out.add(p);
        }
        return out;
    }

    /** Everyone who should see match messages: online participants plus spectators. */
    public List<Player> viewers() {
        List<Player> out = new ArrayList<>();
        for (UUID u : players) {
            if (left.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        for (UUID u : spectators) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    public void broadcast(String msg) {
        for (Player p : viewers()) p.sendMessage(msg);
    }

    // ---- blocks ----

    public boolean canBuild(UUID u, Block b) {
        return state == State.ACTIVE && isAlive(u) && arena != null && arena.allowBuilding && arena.containsBlock(b);
    }

    public void record(BlockState s) {
        changed.putIfAbsent(key(s.getX(), s.getY(), s.getZ()), s);
    }

    private static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }
}
