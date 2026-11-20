package dev.duelforge.match;

import dev.duelforge.arena.Arena;
import dev.duelforge.kit.Kit;
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
import java.util.Set;
import java.util.UUID;

/** Runtime state of one duel or free-for-all match. */
public final class Match {
    public enum State { LOBBY, COUNTDOWN, ACTIVE, ENDING }

    public final int id;
    public final Arena arena;
    public final Kit kit;
    public final boolean ranked;
    public final boolean ffa;
    public final UUID host;
    public State state = State.LOBBY;

    public int maxPlayers, minPlayers, lives, countdown, endTimer, timeLimit;
    public int lobbyTimer = -1;
    public long startMillis;
    public UUID winner;

    /** Everyone who ever took part (never shrinks after the lobby phase). */
    public final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    public final Set<UUID> eliminated = new HashSet<>();
    public final Set<UUID> left = new HashSet<>();
    public final Set<UUID> spectators = new HashSet<>();
    public final Set<UUID> dying = new HashSet<>();

    public final Map<UUID, Integer> kills = new HashMap<>();
    public final Map<UUID, Integer> deaths = new HashMap<>();
    public final Map<UUID, Integer> livesLeft = new HashMap<>();
    public final Map<UUID, UUID> lastDamager = new HashMap<>();
    public final Map<UUID, Long> lastDamageTime = new HashMap<>();
    public final Map<UUID, Long> protectedUntil = new HashMap<>();
    public final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    public final Map<Long, BlockState> changed = new HashMap<>();

    public Match(int id, Arena arena, Kit kit, boolean ranked, boolean ffa, UUID host) {
        this.id = id;
        this.arena = arena;
        this.kit = kit;
        this.ranked = ranked;
        this.ffa = ffa;
        this.host = host;
    }

    /** True once players have been teleported into the arena. */
    public boolean inArena() {
        return state != State.LOBBY;
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

    public boolean canBuild(UUID u, Block b) {
        return state == State.ACTIVE && isAlive(u) && arena.allowBuilding && arena.containsBlock(b);
    }

    public void record(BlockState s) {
        changed.putIfAbsent(key(s.getX(), s.getY(), s.getZ()), s);
    }

    private static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }
}
