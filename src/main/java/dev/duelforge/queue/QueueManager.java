package dev.duelforge.queue;

import dev.duelforge.DuelForge;
import dev.duelforge.kit.Kit;
import dev.duelforge.party.PartyManager.Party;
import dev.duelforge.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Matchmaking queue for 1v1 (ranked / unranked), 2v2 and 3v3. Matches get an auto-built map. */
public final class QueueManager {

    public static final class Entry {
        public final List<UUID> members;
        public final int teamSize;
        public final Kit kit;
        public final boolean ranked;
        public final int bestOf;
        public final long since = System.currentTimeMillis();
        public final int elo;

        Entry(List<UUID> members, int teamSize, Kit kit, boolean ranked, int bestOf, int elo) {
            this.members = members;
            this.teamSize = teamSize;
            this.kit = kit;
            this.ranked = ranked;
            this.bestOf = bestOf;
            this.elo = elo;
        }
    }

    private final DuelForge plugin;
    private final Map<UUID, Entry> byPlayer = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    public QueueManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    public Entry get(UUID id) {
        return byPlayer.get(id);
    }

    public int size() {
        return entries.size();
    }

    public static String modeName(int teamSize) {
        return teamSize + "v" + teamSize;
    }

    public void join(Player p, int teamSize, Kit kit, boolean ranked, int bestOf) {
        var ms = plugin.messages();
        UUID id = p.getUniqueId();
        if (!p.hasPermission("duelforge.duel")) {
            ms.send(p, "no-permission");
            return;
        }
        if (plugin.matches().inMatch(id)) {
            ms.send(p, "already-in-match");
            return;
        }
        if (byPlayer.containsKey(id)) {
            ms.send(p, "queue.already");
            return;
        }
        if (kit == null || !kit.enabled) {
            ms.send(p, "kit-not-found");
            return;
        }
        if (!plugin.kits().canUse(p, kit)) {
            ms.send(p, "kit-locked");
            return;
        }
        teamSize = Math.max(1, Math.min(3, teamSize));
        if (ranked && (teamSize != 1 || !plugin.getConfig().getBoolean("duel.ranked-enabled", true) || !p.hasPermission("duelforge.ranked"))) {
            ms.send(p, teamSize != 1 ? "queue.ranked-1v1-only" : "ranked-disabled");
            return;
        }
        if (!plugin.instances().enabled() && plugin.arenas().ready().isEmpty()) {
            ms.send(p, "no-arenas-configured");
            return;
        }
        List<UUID> members = new ArrayList<>();
        Party party = plugin.parties().of(id);
        if (party != null && party.members.size() > 1) {
            if (!party.leader.equals(id)) {
                ms.send(p, "party.leaders-only");
                return;
            }
            if (ranked) {
                ms.send(p, "party.no-ranked");
                return;
            }
            if (party.members.size() > teamSize) {
                ms.send(p, "queue.party-too-big", "size", party.members.size(), "mode", modeName(teamSize));
                return;
            }
            for (UUID u : party.members) {
                if (Bukkit.getPlayer(u) == null || plugin.matches().inMatch(u) || byPlayer.containsKey(u)) {
                    ms.send(p, "queue.member-busy");
                    return;
                }
            }
            members.addAll(party.members);
        } else {
            members.add(id);
        }
        int elo = plugin.stats().get(id, p.getName()).elo;
        Entry e = new Entry(members, teamSize, kit, ranked, Math.max(1, bestOf), elo);
        entries.add(e);
        for (UUID u : members) {
            byPlayer.put(u, e);
            Player m = Bukkit.getPlayer(u);
            if (m != null) ms.send(m, "queue.joined", "mode", modeName(teamSize), "kit", Fmt.color(kit.displayName),
                    "ranked", plugin.matches().modeName(ranked));
        }
    }

    /** Removes the whole group the player queued with. Returns false if they were not queued. */
    public boolean leave(UUID id) {
        Entry e = byPlayer.get(id);
        if (e == null) return false;
        drop(e);
        for (UUID u : e.members) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) plugin.messages().send(m, "queue.left");
        }
        return true;
    }

    /** Silent removal (used when a match starts or a player quits). */
    public void remove(UUID id) {
        Entry e = byPlayer.get(id);
        if (e != null) drop(e);
    }

    private void drop(Entry e) {
        entries.remove(e);
        for (UUID u : e.members) byPlayer.remove(u, e);
    }

    public void tick() {
        if (entries.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (Entry e : new ArrayList<>(entries)) {
            boolean ok = true;
            for (UUID u : e.members) if (Bukkit.getPlayer(u) == null || plugin.matches().inMatch(u)) ok = false;
            if (ok && e.members.size() > 1) {
                Party party = plugin.parties().of(e.members.get(0));
                if (party == null || party.members.size() != e.members.size()) ok = false;
            }
            if (!ok) {
                drop(e);
                for (UUID u : e.members) {
                    Player m = Bukkit.getPlayer(u);
                    if (m != null && !plugin.matches().inMatch(u)) plugin.messages().send(m, "queue.cancelled");
                }
            }
        }
        for (Entry e : entries) {
            String line = plugin.messages().get("queue.searching", "mode", modeName(e.teamSize),
                    "kit", Fmt.color(e.kit.displayName), "time", Fmt.time((now - e.since) / 1000));
            for (UUID u : e.members) {
                Player m = Bukkit.getPlayer(u);
                if (m != null) plugin.messages().actionBar(m, line);
            }
        }

        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries) {
            groups.computeIfAbsent(e.teamSize + "|" + e.kit.id + "|" + e.ranked + "|" + e.bestOf, k -> new ArrayList<>()).add(e);
        }
        for (List<Entry> g : groups.values()) {
            while (g.size() >= 2) {
                List<List<Entry>> found = find(g, now);
                if (found == null) break;
                List<UUID> a = new ArrayList<>(), b = new ArrayList<>();
                for (Entry e : found.get(0)) a.addAll(e.members);
                for (Entry e : found.get(1)) b.addAll(e.members);
                Entry first = found.get(0).get(0);
                for (List<Entry> side : found) for (Entry e : side) {
                    drop(e);
                    g.remove(e);
                }
                plugin.matches().createMatch(List.of(a, b), first.kit, null, first.ranked, first.bestOf);
            }
        }
    }

    private List<List<Entry>> find(List<Entry> g, long now) {
        int n = g.get(0).teamSize;
        if (n == 1) {
            for (int i = 0; i < g.size(); i++) {
                for (int j = i + 1; j < g.size(); j++) {
                    Entry a = g.get(i), b = g.get(j);
                    if (a.ranked) {
                        long wait = (now - Math.min(a.since, b.since)) / 1000;
                        int window = 100 + (int) (wait * 25);
                        if (Math.abs(a.elo - b.elo) > window) continue;
                    }
                    return List.of(List.of(a), List.of(b));
                }
            }
            return null;
        }
        List<Entry> pool = new ArrayList<>(g);
        List<Entry> a = fill(pool, n);
        if (a == null) return null;
        pool.removeAll(a);
        List<Entry> b = fill(pool, n);
        if (b == null) return null;
        return List.of(a, b);
    }

    /** Picks entries (largest parties first) that add up to exactly n players. */
    private List<Entry> fill(List<Entry> pool, int n) {
        List<Entry> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingInt((Entry e) -> e.members.size()).reversed());
        List<Entry> out = new ArrayList<>();
        int sum = 0;
        for (Entry e : sorted) {
            if (sum + e.members.size() <= n) {
                out.add(e);
                sum += e.members.size();
                if (sum == n) return out;
            }
        }
        return null;
    }
}
