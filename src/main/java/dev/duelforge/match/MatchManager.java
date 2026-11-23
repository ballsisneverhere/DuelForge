package dev.duelforge.match;

import dev.duelforge.DuelForge;
import dev.duelforge.Messages;
import dev.duelforge.arena.Arena;
import dev.duelforge.arena.ArenaBuilder;
import dev.duelforge.kit.Kit;
import dev.duelforge.match.Match.State;
import dev.duelforge.party.PartyManager.Party;
import dev.duelforge.stats.StatsManager.PlayerStats;
import dev.duelforge.util.Fmt;
import dev.duelforge.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Owns every match, duel request and the main 1-second game loop. */
public final class MatchManager {

    public static final class DuelRequest {
        public final UUID from, to;
        public final Kit kit;
        public final Arena arena; // null = auto-built random map
        public final boolean ranked;
        public final int bestOf, teamSize;
        public final long expires;

        DuelRequest(UUID from, UUID to, Kit kit, Arena arena, boolean ranked, int bestOf, int teamSize, long expires) {
            this.from = from;
            this.to = to;
            this.kit = kit;
            this.arena = arena;
            this.ranked = ranked;
            this.bestOf = bestOf;
            this.teamSize = teamSize;
            this.expires = expires;
        }
    }

    private static final class LastMatch {
        final UUID opponent;
        final String kit, arena;
        final boolean ranked;
        final int bestOf;
        final long expires;

        LastMatch(UUID opponent, String kit, String arena, boolean ranked, int bestOf, long expires) {
            this.opponent = opponent;
            this.kit = kit;
            this.arena = arena;
            this.ranked = ranked;
            this.bestOf = bestOf;
            this.expires = expires;
        }
    }

    private final DuelForge plugin;
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    private final Map<Integer, Match> matches = new LinkedHashMap<>();
    private final Map<UUID, List<DuelRequest>> requests = new HashMap<>();
    private final Map<UUID, LastMatch> lastMatches = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private final Random random = new Random();
    private int nextId = 1;
    private BukkitTask task;

    public MatchManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private Messages msg() {
        return plugin.messages();
    }

    // =====================================================================
    // lifecycle
    // =====================================================================

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Ends every match immediately: restores players and static arenas synchronously. */
    public void shutdown() {
        if (task != null) task.cancel();
        for (Match m : new ArrayList<>(matches.values())) {
            for (UUID id : new ArrayList<>(byPlayer.keySet())) {
                if (byPlayer.get(id) != m) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) restore(p, m);
                else byPlayer.remove(id);
            }
            if (m.arena != null && !m.arena.temporary) plugin.arenas().restoreNow(m);
        }
        matches.clear();
        byPlayer.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, List<DuelRequest>>> it = requests.entrySet().iterator(); it.hasNext(); ) {
            List<DuelRequest> list = it.next().getValue();
            list.removeIf(r -> {
                if (r.expires > now) return false;
                Player s = Bukkit.getPlayer(r.from);
                if (s != null) msg().send(s, "request.expired", "player", nameOf(r.to));
                return true;
            });
            if (list.isEmpty()) it.remove();
        }
        lastMatches.values().removeIf(l -> l.expires < now);
        try {
            plugin.queue().tick();
        } catch (Exception ex) {
            plugin.getLogger().warning("Queue error: " + ex);
        }
        for (Match m : new ArrayList<>(matches.values())) {
            try {
                tickMatch(m, now);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error ticking match " + m.id + ": " + ex);
            }
        }
    }

    private void tickMatch(Match m, long now) {
        switch (m.state) {
            case LOBBY -> tickLobby(m);
            case BUILDING -> { }
            case COUNTDOWN -> {
                if (m.countdown > 0) {
                    for (Player p : m.viewers()) {
                        String sub = m.bestOf > 1 ? msg().get("title.round", "round", m.round) : "";
                        p.sendTitle(msg().get("title.countdown", "seconds", m.countdown), sub, 0, 25, 5);
                        Fx.sound(p, cfg().getString("sounds.countdown", ""), 1f, 1f);
                    }
                    m.countdown--;
                } else {
                    m.state = State.ACTIVE;
                    m.startMillis = now;
                    for (Player p : m.viewers()) {
                        p.sendTitle(msg().get("title.fight"), "", 0, 25, 8);
                        Fx.sound(p, cfg().getString("sounds.fight", ""), 0.6f, 1.2f);
                    }
                }
            }
            case ACTIVE -> {
                if (m.timeLimit > 0 && (now - m.startMillis) / 1000 >= m.timeLimit) timeout(m);
            }
            case BETWEEN -> {
                m.endTimer--;
                if (m.endTimer <= 0) startRound(m);
            }
            case ENDING -> {
                m.endTimer--;
                if (m.endTimer <= 0) finish(m);
            }
        }
        if (matches.containsKey(m.id)) for (Player p : m.viewers()) plugin.boards().update(p, m);
    }

    private void tickLobby(Match m) {
        if (m.players.size() >= m.maxPlayers) {
            startMatch(m);
            return;
        }
        if (m.players.size() >= m.minPlayers) {
            if (m.lobbyTimer < 0) {
                m.lobbyTimer = cfg().getInt("ffa.auto-start-seconds", 30);
            } else {
                m.lobbyTimer--;
                if (m.lobbyTimer <= 0) {
                    startMatch(m);
                } else if (m.lobbyTimer <= 5 || m.lobbyTimer == 10 || m.lobbyTimer == 20) {
                    for (UUID u : m.players) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) msg().send(p, "ffa.starting-in", "seconds", m.lobbyTimer);
                    }
                }
            }
        } else {
            m.lobbyTimer = -1;
        }
    }

    private Set<UUID> topKillers(Match m) {
        UUID best = null;
        int bestKills = -1;
        boolean tie = false;
        for (Player p : m.alivePlayers()) {
            int k = m.kills(p.getUniqueId());
            if (k > bestKills) {
                best = p.getUniqueId();
                bestKills = k;
                tie = false;
            } else if (k == bestKills) {
                tie = true;
            }
        }
        Set<UUID> out = new HashSet<>();
        if (best != null && !tie) out.add(best);
        return out;
    }

    // =====================================================================
    // queries
    // =====================================================================

    public Match get(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public Match get(UUID id) {
        return byPlayer.get(id);
    }

    public boolean inMatch(UUID id) {
        return byPlayer.containsKey(id);
    }

    public Collection<Match> all() {
        return matches.values();
    }

    public List<Match> lobbies() {
        List<Match> out = new ArrayList<>();
        for (Match m : matches.values()) if (m.ffa && m.state == State.LOBBY) out.add(m);
        return out;
    }

    public List<Match> spectatable() {
        List<Match> out = new ArrayList<>();
        for (Match m : matches.values()) if (m.inArena()) out.add(m);
        return out;
    }

    public Match matchAt(Block b) {
        if (matches.isEmpty()) return null;
        for (Match m : matches.values()) if (m.inArena() && m.arena.containsBlock(b)) return m;
        return null;
    }

    public Match matchAt(Location l) {
        if (matches.isEmpty() || l == null) return null;
        for (Match m : matches.values()) if (m.inArena() && m.arena.contains(l)) return m;
        return null;
    }

    public boolean isTeleporting(UUID id) {
        return teleporting.contains(id);
    }

    public void tp(Player p, Location l) {
        UUID id = p.getUniqueId();
        boolean added = teleporting.add(id);
        try {
            p.teleport(l);
        } finally {
            if (added) teleporting.remove(id);
        }
    }

    public List<DuelRequest> incoming(UUID target) {
        List<DuelRequest> l = requests.get(target);
        if (l == null) return new ArrayList<>();
        long now = System.currentTimeMillis();
        l.removeIf(r -> r.expires <= now);
        return new ArrayList<>(l);
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "Unknown" : n;
    }

    private String names(Collection<UUID> ids) {
        List<String> out = new ArrayList<>();
        for (UUID u : ids) out.add(nameOf(u));
        return String.join(", ", out);
    }

    public String modeName(boolean ranked) {
        return msg().get(ranked ? "request.ranked" : "request.unranked");
    }

    private String describe(int teamSize, boolean ranked, int bestOf) {
        String s = (teamSize > 1 ? teamSize + "v" + teamSize + " " : "") + modeName(ranked);
        if (bestOf > 1) s += " &8| &7Bo" + bestOf;
        return Fmt.color(s);
    }

    public String describe(DuelRequest r) {
        return describe(r.teamSize, r.ranked, r.bestOf);
    }

    private ArenaBuilder.Theme pickTheme() {
        List<ArenaBuilder.Theme> pool = new ArrayList<>();
        for (String s : cfg().getStringList("matches.themes")) {
            ArenaBuilder.Theme t = ArenaBuilder.Theme.parse(s);
            if (t != null) pool.add(t);
        }
        if (pool.isEmpty()) Collections.addAll(pool, ArenaBuilder.Theme.values());
        return pool.get(random.nextInt(pool.size()));
    }

    // =====================================================================
    // duel requests
    // =====================================================================

    public void sendRequest(Player from, Player to, Kit kit, Arena arena, boolean ranked) {
        sendRequest(from, to, kit, arena, ranked, Math.max(1, cfg().getInt("duel.best-of", 3)));
    }

    public void sendRequest(Player from, Player to, Kit kit, Arena arena, boolean ranked, int bestOf) {
        if (from.equals(to)) {
            msg().send(from, "cannot-duel-self");
            return;
        }
        if (inMatch(from.getUniqueId())) {
            msg().send(from, "already-in-match");
            return;
        }
        if (inMatch(to.getUniqueId())) {
            msg().send(from, "target-in-match", "player", to.getName());
            return;
        }
        if (kit == null || !kit.enabled) {
            msg().send(from, "kit-not-found");
            return;
        }
        if (!plugin.kits().canUse(from, kit)) {
            msg().send(from, "kit-locked");
            return;
        }
        if (ranked && (!cfg().getBoolean("duel.ranked-enabled", true) || !from.hasPermission("duelforge.ranked"))) {
            msg().send(from, "ranked-disabled");
            return;
        }
        if (arena == null && !plugin.instances().enabled() && plugin.arenas().ready().isEmpty()) {
            msg().send(from, "no-arenas-configured");
            return;
        }
        if (arena != null && !arena.free()) {
            msg().send(from, "arena-busy");
            return;
        }
        // parties: 2v2 / 3v3 between two party leaders
        int teamSize = 1;
        Party fp = plugin.parties().of(from.getUniqueId());
        Party tp = plugin.parties().of(to.getUniqueId());
        boolean fromTeam = fp != null && fp.members.size() > 1;
        boolean toTeam = tp != null && tp.members.size() > 1;
        if (fromTeam || toTeam) {
            if (!fromTeam || !toTeam) {
                msg().send(from, "party.need-both");
                return;
            }
            if (!fp.leader.equals(from.getUniqueId()) || !tp.leader.equals(to.getUniqueId())) {
                msg().send(from, "party.leaders-only");
                return;
            }
            if (fp == tp) {
                msg().send(from, "cannot-duel-self");
                return;
            }
            if (fp.members.size() != tp.members.size()) {
                msg().send(from, "party.size-mismatch");
                return;
            }
            if (ranked) {
                msg().send(from, "party.no-ranked");
                return;
            }
            teamSize = fp.members.size();
        }
        // If the target already challenged us, accept their request instead.
        DuelRequest rev = find(from.getUniqueId(), to.getUniqueId());
        if (rev != null) {
            accept(from, to.getName());
            return;
        }
        List<DuelRequest> list = requests.computeIfAbsent(to.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(r -> r.from.equals(from.getUniqueId()));
        long timeout = cfg().getLong("duel.request-timeout", 30) * 1000L;
        DuelRequest req = new DuelRequest(from.getUniqueId(), to.getUniqueId(), kit, arena, ranked, bestOf, teamSize, System.currentTimeMillis() + timeout);
        list.add(req);

        String mode = describe(req);
        msg().send(from, "request.sent", "player", to.getName(), "kit", Fmt.color(kit.displayName), "mode", mode);
        String arenaName = arena == null ? msg().get("request.random-arena") : Fmt.color(arena.displayName);
        msg().sendButtons(to,
                msg().prefixed("request.received", "player", from.getName(), "kit", Fmt.color(kit.displayName), "mode", mode, "arena", arenaName),
                new String[]{msg().get("request.accept-button"), "/duel accept " + from.getName(), msg().get("request.accept-hover")},
                new String[]{msg().get("request.deny-button"), "/duel deny " + from.getName(), msg().get("request.deny-hover")});
        Fx.sound(to, cfg().getString("sounds.invite", ""), 1f, 1f);
    }

    private DuelRequest find(UUID target, UUID sender) {
        List<DuelRequest> l = requests.get(target);
        if (l == null) return null;
        long now = System.currentTimeMillis();
        for (DuelRequest r : l) if (r.from.equals(sender) && r.expires > now) return r;
        return null;
    }

    private DuelRequest pick(Player target, String senderName) {
        List<DuelRequest> list = incoming(target.getUniqueId());
        if (list.isEmpty()) return null;
        if (senderName == null) return list.get(list.size() - 1);
        for (DuelRequest r : list) if (nameOf(r.from).equalsIgnoreCase(senderName)) return r;
        return null;
    }

    public void accept(Player target, String senderName) {
        DuelRequest req = pick(target, senderName);
        if (req == null) {
            msg().send(target, "request.none");
            return;
        }
        List<DuelRequest> l = requests.get(target.getUniqueId());
        if (l != null) l.remove(req);
        Player sender = Bukkit.getPlayer(req.from);
        if (sender == null) {
            msg().send(target, "request.player-offline");
            return;
        }
        List<UUID> a = new ArrayList<>(), b = new ArrayList<>();
        if (req.teamSize > 1) {
            Party fp = plugin.parties().of(req.from), tp = plugin.parties().of(req.to);
            if (fp == null || tp == null || fp.members.size() != req.teamSize || tp.members.size() != req.teamSize) {
                msg().send(target, "party.size-mismatch");
                return;
            }
            a.addAll(fp.members);
            b.addAll(tp.members);
        } else {
            a.add(req.from);
            b.add(req.to);
        }
        createMatch(List.of(a, b), req.kit, req.arena, req.ranked, req.bestOf);
    }

    public void deny(Player target, String senderName) {
        DuelRequest req = pick(target, senderName);
        if (req == null) {
            msg().send(target, "request.none");
            return;
        }
        List<DuelRequest> l = requests.get(target.getUniqueId());
        if (l != null) l.remove(req);
        msg().send(target, "request.you-denied", "player", nameOf(req.from));
        Player s = Bukkit.getPlayer(req.from);
        if (s != null) msg().send(s, "request.denied", "player", target.getName());
    }

    public void rematch(Player p) {
        LastMatch lm = lastMatches.get(p.getUniqueId());
        if (lm == null || lm.expires < System.currentTimeMillis()) {
            msg().send(p, "rematch.none");
            return;
        }
        Player opp = Bukkit.getPlayer(lm.opponent);
        if (opp == null) {
            msg().send(p, "request.player-offline");
            return;
        }
        Arena arena = plugin.arenas().get(lm.arena);
        if (arena != null && !arena.free()) arena = null;
        sendRequest(p, opp, plugin.kits().get(lm.kit), arena, lm.ranked, lm.bestOf);
    }

    // =====================================================================
    // creating matches (duels, queue matches, party duels)
    // =====================================================================

    /** Sets up a team match. Each inner list is one team. The map is built automatically when {@code arena} is null. */
    public boolean createMatch(List<List<UUID>> teams, Kit kit, Arena arena, boolean ranked, int bestOf) {
        List<UUID> all = new ArrayList<>();
        for (List<UUID> t : teams) all.addAll(t);
        for (UUID u : all) {
            if (Bukkit.getPlayer(u) == null || inMatch(u)) {
                for (UUID o : all) {
                    Player op = Bukkit.getPlayer(o);
                    if (op != null && !inMatch(o)) msg().send(op, "match.cancelled");
                }
                return false;
            }
        }
        boolean auto = arena == null && plugin.instances().enabled();
        if (arena != null && !arena.free()) {
            for (UUID o : all) msg().send(Bukkit.getPlayer(o), "arena-busy");
            return false;
        }
        if (arena == null && !auto) {
            arena = plugin.arenas().randomFree();
            if (arena == null) {
                for (UUID o : all) msg().send(Bukkit.getPlayer(o), "no-arena");
                return false;
            }
        }
        for (UUID u : all) plugin.queue().remove(u);

        Match m = new Match(nextId++, kit, ranked, false, all.get(0));
        m.teamSize = teams.get(0).size();
        m.teamCount = teams.size();
        m.maxPlayers = all.size();
        m.minPlayers = all.size();
        m.bestOf = bestOf % 2 == 0 ? bestOf + 1 : Math.max(1, bestOf);
        m.lives = Math.max(1, cfg().getInt("duel.lives", 1));
        m.timeLimit = cfg().getInt("duel.time-limit", 600);
        for (int t = 0; t < teams.size(); t++) {
            for (UUID u : teams.get(t)) {
                m.players.add(u);
                m.team.put(u, t);
                byPlayer.put(u, m);
            }
        }
        matches.put(m.id, m);
        if (arena != null) {
            m.arena = arena;
            arena.current = m;
        }
        startMatch(m);
        return true;
    }

    /** Either begins right away (map exists) or builds a fresh map first. */
    private void startMatch(Match m) {
        if (m.arena != null) {
            begin(m);
            return;
        }
        m.state = State.BUILDING;
        for (Player p : m.viewers()) msg().send(p, "match.building");
        plugin.instances().acquire(pickTheme(), arena -> {
            if (!matches.containsKey(m.id) || m.cancelled) {
                plugin.instances().release(arena, null);
                return;
            }
            m.arena = arena;
            arena.current = m;
            begin(m);
        }, () -> {
            if (!matches.containsKey(m.id) || m.cancelled) return;
            Arena fb = plugin.arenas().randomFree();
            if (fb != null) {
                m.arena = fb;
                fb.current = m;
                begin(m);
            } else {
                for (Player p : m.viewers()) msg().send(p, "no-arena");
                dismantle(m);
            }
        });
    }

    /** Removes a match that never started, without touching anyone's inventory. */
    private void dismantle(Match m) {
        for (UUID u : new ArrayList<>(m.players)) byPlayer.remove(u);
        matches.remove(m.id);
        m.cancelled = true;
        if (m.arena != null && !m.arena.temporary) m.arena.current = null;
    }

    // =====================================================================
    // free-for-all lobbies
    // =====================================================================

    public void createLobby(Player host, Kit kit, Arena arena) {
        if (!cfg().getBoolean("ffa.enabled", true)) {
            msg().send(host, "ffa-disabled");
            return;
        }
        if (inMatch(host.getUniqueId())) {
            msg().send(host, "already-in-match");
            return;
        }
        if (kit == null || !plugin.kits().canUse(host, kit)) {
            msg().send(host, "kit-locked");
            return;
        }
        boolean auto = arena == null && plugin.instances().enabled();
        if (!auto) {
            if (plugin.arenas().ready().isEmpty()) {
                msg().send(host, "no-arenas-configured");
                return;
            }
            if (arena == null) arena = plugin.arenas().randomFree();
            if (arena == null || !arena.free()) {
                msg().send(host, arena == null ? "no-arena" : "arena-busy");
                return;
            }
        } else if (plugin.parties().of(host.getUniqueId()) != null && plugin.parties().of(host.getUniqueId()).members.size() > 1) {
            msg().send(host, "party.ffa-solo");
            return;
        }
        Match m = new Match(nextId++, kit, false, true, host.getUniqueId());
        int spawns = arena != null ? arena.spawns.size() : 12;
        m.maxPlayers = Math.max(2, Math.min(cfg().getInt("ffa.max-players", 16), spawns));
        m.minPlayers = Math.max(2, cfg().getInt("ffa.min-players", 2));
        m.lives = Math.max(1, cfg().getInt("ffa.lives", 3));
        m.timeLimit = cfg().getInt("ffa.time-limit", 900);
        if (arena != null) {
            m.arena = arena;
            arena.current = m;
        }
        matches.put(m.id, m);
        m.players.add(host.getUniqueId());
        byPlayer.put(host.getUniqueId(), m);
        plugin.queue().remove(host.getUniqueId());
        msg().send(host, "ffa.created");
        if (cfg().getBoolean("ffa.broadcast-lobby", true)) {
            String line = msg().prefixed("ffa.announce", "player", host.getName(), "kit", Fmt.color(kit.displayName), "arena", m.arenaName());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(host) || inMatch(p.getUniqueId())) continue;
                msg().sendButtons(p, line, new String[]{msg().get("ffa.join-button"), "/duel join " + host.getName(), msg().get("ffa.join-hover")});
            }
        }
    }

    public void joinLobby(Player p, Match m) {
        if (m == null || m.state != State.LOBBY || !matches.containsKey(m.id)) {
            msg().send(p, "ffa.not-open");
            return;
        }
        if (inMatch(p.getUniqueId())) {
            msg().send(p, "already-in-match");
            return;
        }
        if (m.players.size() >= m.maxPlayers) {
            msg().send(p, "ffa.full");
            return;
        }
        if (!plugin.kits().canUse(p, m.kit)) {
            msg().send(p, "kit-locked");
            return;
        }
        plugin.queue().remove(p.getUniqueId());
        m.players.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), m);
        msg().send(p, "ffa.joined", "host", nameOf(m.host), "count", m.players.size(), "max", m.maxPlayers);
        for (UUID u : m.players) {
            Player o = Bukkit.getPlayer(u);
            if (o != null && !o.equals(p)) msg().send(o, "ffa.player-joined", "player", p.getName(), "count", m.players.size(), "max", m.maxPlayers);
        }
    }

    public Match lobbyOf(String hostName) {
        for (Match m : lobbies()) if (nameOf(m.host).equalsIgnoreCase(hostName)) return m;
        return null;
    }

    public void startLobby(Player by, Match m) {
        if (m == null || m.state != State.LOBBY) {
            msg().send(by, "ffa.no-lobby");
            return;
        }
        if (m.players.size() < m.minPlayers) {
            msg().send(by, "ffa.not-enough", "min", m.minPlayers);
            return;
        }
        startMatch(m);
    }

    private void cancelLobby(Match m) {
        for (UUID u : new ArrayList<>(m.players)) {
            byPlayer.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null) msg().send(p, "ffa.cancelled");
        }
        matches.remove(m.id);
        if (m.arena != null && !m.arena.temporary) m.arena.current = null;
    }

    // =====================================================================
    // starting
    // =====================================================================

    private void begin(Match m) {
        m.state = State.COUNTDOWN;
        m.countdown = Math.max(1, cfg().getInt(m.ffa ? "ffa.countdown" : "duel.countdown", 5));
        m.round = 1;
        List<UUID> order = new ArrayList<>(m.players);
        if (m.ffa) {
            Collections.shuffle(order, random);
            int i = 0;
            for (UUID id : order) m.spawnSlot.put(id, i++);
        } else {
            for (int t = 0; t < m.teamCount; t++) {
                int k = 0;
                for (UUID u : m.members(t)) {
                    m.spawnSlot.put(u, 2 * k + t);
                    k++;
                }
            }
        }
        for (UUID id : order) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                m.players.remove(id);
                m.team.remove(id);
                byPlayer.remove(id);
                continue;
            }
            PlayerSnapshot snap = PlayerSnapshot.capture(p);
            m.snapshots.put(id, snap);
            snap.save(plugin.snapshotFile(id));
            m.livesLeft.put(id, m.lives);
            m.kills.put(id, 0);
            m.deaths.put(id, 0);
            plugin.abilities().clear(id);
            plugin.queue().remove(id);
            p.closeInventory();
            prepare(p, m);
            tp(p, m.arena.spawn(m.spawnSlot.getOrDefault(id, 0)));
            p.sendTitle(msg().get("title.get-ready"), msg().get("title.get-ready-sub", "kit", Fmt.color(m.kit.displayName)), 5, 30, 5);
            msg().send(p, "match.starting");
            plugin.boards().update(p, m);
        }
        if (m.players.size() < m.minPlayers) abort(m);
    }

    /** Heal, clear and give the kit. */
    @SuppressWarnings("deprecation")
    private void prepare(Player p, Match m) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setAbsorptionAmount(0);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExp(0);
        p.setLevel(0);
        p.setVelocity(new Vector());
        m.kit.apply(p, plugin.kits().layout(p.getUniqueId(), m.kit.id));
    }

    // =====================================================================
    // leaving / spectating
    // =====================================================================

    private boolean teamGone(Match m) {
        if (m.ffa) return false;
        for (int t = 0; t < m.teamCount; t++) {
            boolean allLeft = true;
            for (UUID u : m.members(t)) if (!m.left.contains(u)) allLeft = false;
            if (allLeft) return true;
        }
        return false;
    }

    /** A whole team walked out: the other side wins the match outright. */
    private boolean forfeitEnd(Match m) {
        if (m.ffa) return false;
        for (int t = 0; t < m.teamCount; t++) {
            boolean allLeft = true;
            for (UUID u : m.members(t)) if (!m.left.contains(u)) allLeft = false;
            if (!allLeft) continue;
            Set<UUID> winners = new HashSet<>();
            for (int o = 0; o < m.teamCount; o++) {
                if (o == t) continue;
                for (UUID u : m.members(o)) if (!m.left.contains(u)) winners.add(u);
            }
            end(m, winners, true);
            return true;
        }
        return false;
    }

    public boolean leave(Player p, boolean quit) {
        UUID id = p.getUniqueId();
        Match m = byPlayer.get(id);
        if (m == null) return false;

        if (m.state == State.LOBBY) {
            m.players.remove(id);
            byPlayer.remove(id);
            if (id.equals(m.host)) {
                cancelLobby(m);
            } else {
                for (UUID u : m.players) {
                    Player o = Bukkit.getPlayer(u);
                    if (o != null) msg().send(o, "ffa.player-left", "player", p.getName(), "count", m.players.size(), "max", m.maxPlayers);
                }
            }
            if (!quit) msg().send(p, "match.you-left");
            return true;
        }
        if (m.state == State.BUILDING) {
            m.players.remove(id);
            byPlayer.remove(id);
            if (!quit) msg().send(p, "match.you-left");
            if (!m.ffa || m.players.size() < m.minPlayers) {
                for (Player o : m.viewers()) msg().send(o, "match.aborted");
                dismantle(m);
            }
            return true;
        }
        if (m.spectators.contains(id)) {
            m.spectators.remove(id);
            restore(p, m);
            if (!quit) msg().send(p, "spectate.stopped");
            return true;
        }
        boolean wasAlive = m.isAlive(id);
        m.left.add(id);
        m.eliminated.add(id);
        if (wasAlive && m.state != State.ENDING) m.broadcast(msg().prefixed("match.forfeit", "player", p.getName()));
        restore(p, m);
        if (!quit) msg().send(p, "match.you-left");
        switch (m.state) {
            case COUNTDOWN -> {
                if (m.ffa ? m.alivePlayers().size() < 2 : teamGone(m)) abort(m);
            }
            case ACTIVE, BETWEEN -> {
                if (!forfeitEnd(m) && m.state == State.ACTIVE) checkEnd(m);
            }
            default -> { }
        }
        return true;
    }

    public void spectate(Player p, Match m) {
        if (!cfg().getBoolean("spectator.enabled", true)) {
            msg().send(p, "spectate-disabled");
            return;
        }
        if (inMatch(p.getUniqueId())) {
            msg().send(p, "already-in-match");
            return;
        }
        if (m == null || !m.inArena() || !matches.containsKey(m.id)) {
            msg().send(p, "spectate.no-matches");
            return;
        }
        UUID id = p.getUniqueId();
        PlayerSnapshot snap = PlayerSnapshot.capture(p);
        m.snapshots.put(id, snap);
        snap.save(plugin.snapshotFile(id));
        m.spectators.add(id);
        byPlayer.put(id, m);
        plugin.queue().remove(id);
        p.closeInventory();
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        tp(p, m.arena.spectatorSpawn());
        msg().send(p, "spectate.started");
        plugin.boards().update(p, m);
    }

    public Match matchOfPlayer(String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) return null;
        Match m = byPlayer.get(t.getUniqueId());
        return m != null && m.inArena() ? m : null;
    }

    private boolean toLobby() {
        return cfg().getBoolean("lobby.return-to-lobby", true) && plugin.lobby() != null;
    }

    private Location returnLocation(PlayerSnapshot s) {
        return toLobby() ? plugin.lobby() : s.location();
    }

    public Location lobbyLocation() {
        return plugin.lobby();
    }

    /** Puts a player back to how they were before the match and removes all match tracking. */
    private void restore(Player p, Match m) {
        UUID id = p.getUniqueId();
        byPlayer.remove(id);
        m.dying.remove(id);
        plugin.boards().remove(p);
        plugin.abilities().clear(id);
        PlayerSnapshot s = m.snapshots.remove(id);
        if (s != null) {
            s.restore(p, returnLocation(s), cfg().getBoolean("restore.heal-on-return", true));
            plugin.snapshotFile(id).delete();
        }
        if (toLobby()) plugin.hubItems().give(p);
    }

    /** Called on join: recovers a player whose match was interrupted by a crash or restart. */
    public void restorePending(Player p) {
        File f = plugin.snapshotFile(p.getUniqueId());
        if (!f.exists() || inMatch(p.getUniqueId())) return;
        PlayerSnapshot s = PlayerSnapshot.load(f);
        if (s != null) s.restore(p, toLobby() ? plugin.lobby() : s.location(), true);
        f.delete();
    }

    public void onQuit(Player p) {
        leave(p, true);
        plugin.queue().remove(p.getUniqueId());
        plugin.parties().onQuit(p);
        requests.remove(p.getUniqueId());
        for (List<DuelRequest> l : requests.values()) l.removeIf(r -> r.from.equals(p.getUniqueId()));
    }

    // =====================================================================
    // combat resolution
    // =====================================================================

    public Player recentDamager(Match m, UUID victim) {
        UUID a = m.lastDamager.get(victim);
        Long t = m.lastDamageTime.get(victim);
        if (a == null || t == null || System.currentTimeMillis() - t > 10_000) return null;
        Player p = Bukkit.getPlayer(a);
        return p != null && m.isAlive(a) ? p : null;
    }

    private String weaponName(Player killer) {
        ItemStack it = killer.getInventory().getItemInMainHand();
        if (it.getType().isAir()) return "Fist";
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return org.bukkit.ChatColor.stripColor(it.getItemMeta().getDisplayName());
        return Fmt.pretty(it.getType().name());
    }

    @SuppressWarnings("deprecation")
    public void handleDeath(Match m, Player victim, Player killer, String cause) {
        UUID v = victim.getUniqueId();
        m.dying.remove(v);
        if (m.state != State.ACTIVE || !m.isAlive(v) || !victim.isOnline()) return;
        if (killer != null && (killer.equals(victim) || !m.isAlive(killer.getUniqueId()) || m.sameTeam(killer.getUniqueId(), v))) killer = null;

        m.deaths.merge(v, 1, Integer::sum);
        PlayerStats vs = plugin.stats().get(v, victim.getName());
        vs.deaths++;
        vs.killStreak = 0;

        String feed;
        if (killer != null) {
            UUID k = killer.getUniqueId();
            m.kills.merge(k, 1, Integer::sum);
            PlayerStats ks = plugin.stats().get(k, killer.getName());
            ks.kills++;
            ks.seasonKills++;
            ks.kitKills.merge(m.kit.id, 1, Integer::sum);
            ks.killStreak++;
            ks.bestKillStreak = Math.max(ks.bestKillStreak, ks.killStreak);
            double heal = cfg().getDouble("combat.heal-on-kill", 0);
            if (heal > 0) killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + heal));
            if (cfg().getIntegerList("combat.killstreak-announce").contains(ks.killStreak)) {
                m.broadcast(msg().prefixed("match.killstreak", "player", killer.getName(), "streak", ks.killStreak));
            }
            feed = msg().get("killfeed.player", "killer", killer.getName(), "victim", victim.getName(), "weapon", weaponName(killer));
            Fx.sound(killer, cfg().getString("sounds.kill", ""), 1f, 1.2f);
        } else {
            feed = msg().get("killfeed.environment", "victim", victim.getName(), "cause", cause);
        }
        plugin.stats().markDirty();
        m.broadcast(feed);
        if (cfg().getBoolean("combat.broadcast-killfeed", false)) Bukkit.broadcastMessage(feed);

        Location vl = victim.getLocation().add(0, 1, 0);
        plugin.cosmetics().kill(killer, vl);
        Fx.sound(vl, cfg().getString("sounds.death", ""), 1f, 1f);

        int left = m.livesLeft.merge(v, -1, Integer::sum);
        if (left > 0) respawn(m, victim, left);
        else eliminate(m, victim);
        checkEnd(m);
    }

    private void respawn(Match m, Player p, int livesLeft) {
        prepare(p, m);
        tp(p, bestSpawn(m, p));
        m.protectedUntil.put(p.getUniqueId(), System.currentTimeMillis() + cfg().getLong("ffa.respawn-protection", 3) * 1000L);
        p.sendTitle(msg().get("title.died"), msg().get("title.died-sub", "lives", livesLeft), 0, 25, 8);
        msg().send(p, "match.respawn", "lives", livesLeft);
    }

    private Location bestSpawn(Match m, Player self) {
        Arena a = m.arena;
        List<Player> others = m.enemiesOf(self);
        Location best = a.spawn(0);
        double bestMin = -1;
        int start = random.nextInt(a.spawns.size());
        for (int n = 0; n < a.spawns.size(); n++) {
            Location s = a.spawn(start + n);
            double min = Double.MAX_VALUE;
            for (Player o : others) if (o.getWorld().equals(s.getWorld())) min = Math.min(min, o.getLocation().distanceSquared(s));
            if (min > bestMin) {
                bestMin = min;
                best = s;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private void eliminate(Match m, Player p) {
        m.eliminated.add(p.getUniqueId());
        p.getInventory().clear();
        for (var e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setHealth(p.getMaxHealth());
        p.setGameMode(GameMode.SPECTATOR);
        tp(p, m.arena.spectatorSpawn());
        p.sendTitle(msg().get("title.eliminated"), "", 0, 40, 10);
        msg().send(p, "match.eliminated");
    }

    // =====================================================================
    // rounds
    // =====================================================================

    private void checkEnd(Match m) {
        if (m.state != State.ACTIVE) return;
        if (m.ffa) {
            List<Player> alive = m.alivePlayers();
            if (alive.size() <= 1) {
                Set<UUID> w = new HashSet<>();
                if (!alive.isEmpty()) w.add(alive.get(0).getUniqueId());
                end(m, w, true);
            }
            return;
        }
        Set<Integer> alive = m.aliveTeams();
        if (alive.size() > 1) return;
        roundEnd(m, alive.isEmpty() ? -1 : alive.iterator().next());
    }

    private void timeout(Match m) {
        m.broadcast(msg().prefixed("match.timeout"));
        if (m.ffa) {
            end(m, topKillers(m), true);
            return;
        }
        int best = -1, bestCount = -1;
        boolean tie = false;
        for (int t = 0; t < m.teamCount; t++) {
            int c = m.teamAlive(t);
            if (c > bestCount) {
                best = t;
                bestCount = c;
                tie = false;
            } else if (c == bestCount) {
                tie = true;
            }
        }
        roundEnd(m, tie ? -1 : best);
    }

    private String teamLabel(Match m, int t) {
        if (m.teamSize == 1) return names(m.members(t));
        return msg().get("team." + t);
    }

    private String scoreString(Match m) {
        List<String> parts = new ArrayList<>();
        for (int t = 0; t < m.teamCount; t++) parts.add(String.valueOf(m.score.getOrDefault(t, 0)));
        return String.join(" - ", parts);
    }

    private void roundEnd(Match m, int winTeam) {
        if (winTeam >= 0) m.score.merge(winTeam, 1, Integer::sum);
        int needed = m.bestOf / 2 + 1;
        if (winTeam >= 0 && m.score.getOrDefault(winTeam, 0) >= needed) {
            end(m, new HashSet<>(m.members(winTeam)), true);
            return;
        }
        if (m.bestOf <= 1) {
            end(m, winTeam >= 0 ? new HashSet<>(m.members(winTeam)) : new HashSet<>(), true);
            return;
        }
        if (m.round >= m.bestOf + 2) { // too many drawn rounds: decide on points
            int best = -1, bc = -1;
            boolean tie = false;
            for (int t = 0; t < m.teamCount; t++) {
                int s = m.score.getOrDefault(t, 0);
                if (s > bc) {
                    best = t;
                    bc = s;
                    tie = false;
                } else if (s == bc) {
                    tie = true;
                }
            }
            end(m, tie ? new HashSet<>() : new HashSet<>(m.members(best)), true);
            return;
        }
        m.state = State.BETWEEN;
        m.endTimer = 3;
        String who = winTeam >= 0 ? teamLabel(m, winTeam) : msg().get("match.round-draw");
        m.broadcast(msg().prefixed("match.round-end", "round", m.round, "winner", who, "score", scoreString(m)));
        for (Player p : m.viewers()) Fx.sound(p, cfg().getString("sounds.kill", ""), 1f, 0.8f);
    }

    private void startRound(Match m) {
        m.round++;
        plugin.arenas().restoreBlocksNow(m);
        for (UUID id : m.players) {
            if (m.left.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            m.eliminated.remove(id);
            m.livesLeft.put(id, m.lives);
            m.dying.remove(id);
            m.protectedUntil.remove(id);
            m.lastDamager.remove(id);
            plugin.abilities().clear(id);
            prepare(p, m);
            tp(p, m.arena.spawn(m.spawnSlot.getOrDefault(id, 0)));
        }
        m.state = State.COUNTDOWN;
        m.countdown = Math.max(1, cfg().getInt("duel.round-countdown", 3));
    }

    // =====================================================================
    // ending
    // =====================================================================

    /** Cancels a match that cannot proceed. No stats are recorded. */
    private void abort(Match m) {
        for (Player p : m.viewers()) msg().send(p, "match.aborted");
        end(m, new HashSet<>(), false);
    }

    private void end(Match m, Set<UUID> winners, boolean countStats) {
        if (m.state == State.ENDING) return;
        m.state = State.ENDING;
        m.endTimer = Math.max(1, cfg().getInt("duel.end-delay", 6));
        m.winners.clear();
        m.winners.addAll(winners);
        m.dying.clear();
        List<UUID> all = new ArrayList<>(m.players);

        if (countStats) {
            for (UUID id : all) {
                PlayerStats st = plugin.stats().get(id, nameOf(id));
                st.matches++;
                if (winners.contains(id)) {
                    st.wins++;
                    st.seasonWins++;
                    st.kitWins.merge(m.kit.id, 1, Integer::sum);
                    st.winStreak++;
                    st.bestWinStreak = Math.max(st.bestWinStreak, st.winStreak);
                } else if (!winners.isEmpty()) {
                    st.losses++;
                    st.winStreak = 0;
                }
            }
            if (!winners.isEmpty()) {
                m.broadcast(msg().prefixed("match.end.winner", "winner", names(winners)));
                if (m.bestOf > 1 && !m.ffa) m.broadcast(msg().prefixed("match.end.score", "score", scoreString(m)));
            } else {
                m.broadcast(msg().prefixed("match.end.draw"));
            }
            for (UUID id : all) {
                m.broadcast(msg().get("match.end.player-line", "player", nameOf(id), "kills", m.kills(id), "deaths", m.deaths(id)));
            }
            if (!m.ffa && m.ranked && m.teamSize == 1 && winners.size() == 1 && all.size() == 2) applyElo(m, winners.iterator().next(), all);
            plugin.stats().markDirty();
            plugin.stats().save(true);
        }

        for (Player p : m.viewers()) {
            UUID id = p.getUniqueId();
            if (!m.isParticipant(id)) continue;
            if (winners.isEmpty()) {
                p.sendTitle(msg().get("title.draw"), "", 5, 50, 10);
            } else if (winners.contains(id)) {
                p.sendTitle(msg().get("title.victory"), msg().get("title.victory-sub"), 5, 60, 15);
                Fx.sound(p, cfg().getString("sounds.victory", ""), 1f, 1f);
            } else {
                p.sendTitle(msg().get("title.defeat"), msg().get("title.defeat-sub"), 5, 50, 10);
                Fx.sound(p, cfg().getString("sounds.defeat", ""), 1f, 1f);
            }
        }
        if (!winners.isEmpty()) victoryEffects(m, winners);
    }

    private void applyElo(Match m, UUID winner, List<UUID> all) {
        UUID loser = all.get(0).equals(winner) ? all.get(1) : all.get(0);
        PlayerStats w = plugin.stats().get(winner, nameOf(winner));
        PlayerStats l = plugin.stats().get(loser, nameOf(loser));
        double expected = 1.0 / (1.0 + Math.pow(10, (l.elo - w.elo) / 400.0));
        int k = cfg().getInt("elo.k-factor", 32);
        int delta = (int) Math.max(1, Math.round(k * (1 - expected)));
        int min = cfg().getInt("elo.minimum", 100);
        int lossDelta = Math.min(delta, Math.max(0, l.elo - min));
        w.elo += delta;
        l.elo -= lossDelta;
        w.rankedWins++;
        l.rankedLosses++;
        m.broadcast(msg().get("match.end.elo", "player", w.name, "elo", w.elo, "change", "&a+" + delta));
        m.broadcast(msg().get("match.end.elo", "player", l.name, "elo", l.elo, "change", "&c-" + lossDelta));
    }

    private void victoryEffects(Match m, Set<UUID> winners) {
        int max = 0;
        for (UUID w : winners) {
            Player p = Bukkit.getPlayer(w);
            if (p != null) max = Math.max(max, plugin.cosmetics().victoryCount(p));
        }
        if (max <= 0) return;
        final int total = max;
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (m.state != State.ENDING || n >= total) {
                    cancel();
                    return;
                }
                for (UUID w : winners) {
                    Player p = Bukkit.getPlayer(w);
                    if (p == null || n >= plugin.cosmetics().victoryCount(p)) continue;
                    Location l = p.getLocation().add(random.nextInt(5) - 2, 2, random.nextInt(5) - 2);
                    Fx.firework(l, plugin.cosmetics().victoryColors(p));
                }
                n++;
            }
        }.runTaskTimer(plugin, 5L, 10L);
    }

    private void finish(Match m) {
        matches.remove(m.id);
        List<UUID> ids = new ArrayList<>(m.players);
        ids.addAll(m.spectators);
        List<Player> restored = new ArrayList<>();
        for (UUID id : ids) {
            if (byPlayer.get(id) != m) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                restore(p, m);
                restored.add(p);
            } else {
                byPlayer.remove(id);
            }
        }
        if (m.arena != null) {
            if (m.arena.temporary) plugin.instances().release(m.arena, m);
            else plugin.arenas().restore(m);
        }

        if (!m.ffa && m.teamSize == 1 && m.teamCount == 2 && !m.winners.isEmpty() && m.players.size() == 2) {
            long exp = System.currentTimeMillis() + 60_000L;
            List<UUID> pl = new ArrayList<>(m.players);
            String arenaName = m.arena != null && !m.arena.temporary ? m.arena.name : null;
            lastMatches.put(pl.get(0), new LastMatch(pl.get(1), m.kit.id, arenaName, m.ranked, m.bestOf, exp));
            lastMatches.put(pl.get(1), new LastMatch(pl.get(0), m.kit.id, arenaName, m.ranked, m.bestOf, exp));
            for (Player p : restored) {
                if (!m.isParticipant(p.getUniqueId())) continue;
                UUID other = pl.get(0).equals(p.getUniqueId()) ? pl.get(1) : pl.get(0);
                msg().sendButtons(p, msg().prefixed("rematch.prompt"),
                        new String[]{msg().get("rematch.button"), "/duel rematch", msg().get("rematch.hover", "player", nameOf(other))});
            }
        }
    }

    /** Admin: end the match a player is in right now (no winner). */
    public boolean forceEnd(Match m) {
        if (m.state == State.LOBBY) {
            cancelLobby(m);
            return true;
        }
        if (m.state == State.BUILDING) {
            for (Player o : m.viewers()) msg().send(o, "match.aborted");
            dismantle(m);
            return true;
        }
        if (m.state == State.ENDING) return true;
        end(m, new HashSet<>(), false);
        return true;
    }
}
