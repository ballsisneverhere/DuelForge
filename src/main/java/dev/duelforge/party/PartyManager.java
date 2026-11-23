package dev.duelforge.party;

import dev.duelforge.DuelForge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Simple parties used for 2v2 / 3v3 duels and team queues. */
public final class PartyManager {

    public static final class Party {
        public UUID leader;
        public final List<UUID> members = new ArrayList<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    private final DuelForge plugin;
    private final Map<UUID, Party> byPlayer = new HashMap<>();
    /** target -> (leader -> expiry) */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();

    public PartyManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    public Party of(UUID id) {
        return byPlayer.get(id);
    }

    public int maxSize() {
        return Math.max(2, plugin.getConfig().getInt("party.max-size", 3));
    }

    private void tell(Party party, String key, Object... kv) {
        for (UUID u : party.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) plugin.messages().send(p, key, kv);
        }
    }

    public void invite(Player leader, Player target) {
        var ms = plugin.messages();
        if (leader.equals(target)) {
            ms.send(leader, "party.self");
            return;
        }
        if (plugin.matches().inMatch(leader.getUniqueId()) || plugin.matches().inMatch(target.getUniqueId())) {
            ms.send(leader, "already-in-match");
            return;
        }
        Party party = byPlayer.get(leader.getUniqueId());
        if (party != null && !party.leader.equals(leader.getUniqueId())) {
            ms.send(leader, "party.leaders-only");
            return;
        }
        if (byPlayer.containsKey(target.getUniqueId())) {
            ms.send(leader, "party.target-in-party", "player", target.getName());
            return;
        }
        if (party != null && party.members.size() >= maxSize()) {
            ms.send(leader, "party.full");
            return;
        }
        if (party == null) {
            party = new Party(leader.getUniqueId());
            byPlayer.put(leader.getUniqueId(), party);
        }
        long expiry = System.currentTimeMillis() + 60_000L;
        invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).put(leader.getUniqueId(), expiry);
        ms.send(leader, "party.invited", "player", target.getName());
        ms.sendButtons(target, ms.prefixed("party.invite-received", "player", leader.getName()),
                new String[]{ms.get("party.accept-button"), "/party accept " + leader.getName(), ms.get("party.accept-hover")},
                new String[]{ms.get("party.deny-button"), "/party deny " + leader.getName(), ms.get("party.deny-hover")});
    }

    private UUID pickInvite(Player p, String leaderName) {
        Map<UUID, Long> map = invites.get(p.getUniqueId());
        if (map == null) return null;
        long now = System.currentTimeMillis();
        map.values().removeIf(t -> t < now);
        UUID best = null;
        for (UUID l : map.keySet()) {
            Player lp = Bukkit.getPlayer(l);
            if (lp == null) continue;
            if (leaderName == null || lp.getName().equalsIgnoreCase(leaderName)) best = l;
        }
        return best;
    }

    public void accept(Player p, String leaderName) {
        var ms = plugin.messages();
        UUID l = pickInvite(p, leaderName);
        if (l == null) {
            ms.send(p, "party.no-invite");
            return;
        }
        invites.get(p.getUniqueId()).remove(l);
        Party party = byPlayer.get(l);
        if (party == null || !party.leader.equals(l)) {
            ms.send(p, "party.no-invite");
            return;
        }
        if (byPlayer.containsKey(p.getUniqueId())) {
            ms.send(p, "party.already");
            return;
        }
        if (party.members.size() >= maxSize()) {
            ms.send(p, "party.full");
            return;
        }
        party.members.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), party);
        tell(party, "party.joined", "player", p.getName());
    }

    public void deny(Player p, String leaderName) {
        UUID l = pickInvite(p, leaderName);
        if (l == null) {
            plugin.messages().send(p, "party.no-invite");
            return;
        }
        invites.get(p.getUniqueId()).remove(l);
        Player lp = Bukkit.getPlayer(l);
        if (lp != null) plugin.messages().send(lp, "party.denied", "player", p.getName());
        plugin.messages().send(p, "party.you-denied");
    }

    public void leave(Player p, boolean quit) {
        Party party = byPlayer.remove(p.getUniqueId());
        invites.remove(p.getUniqueId());
        if (party == null) {
            if (!quit) plugin.messages().send(p, "party.none");
            return;
        }
        party.members.remove(p.getUniqueId());
        if (!quit) plugin.messages().send(p, "party.left");
        if (party.members.size() <= 1) {
            disbandInternal(party);
            return;
        }
        tell(party, "party.member-left", "player", p.getName());
        if (party.leader.equals(p.getUniqueId())) {
            party.leader = party.members.get(0);
            tell(party, "party.new-leader", "player", nameOf(party.leader));
        }
    }

    public void kick(Player leader, String name) {
        Party party = byPlayer.get(leader.getUniqueId());
        if (party == null || !party.leader.equals(leader.getUniqueId())) {
            plugin.messages().send(leader, "party.leaders-only");
            return;
        }
        for (UUID u : new ArrayList<>(party.members)) {
            if (u.equals(leader.getUniqueId())) continue;
            if (nameOf(u).equalsIgnoreCase(name)) {
                party.members.remove(u);
                byPlayer.remove(u);
                Player k = Bukkit.getPlayer(u);
                if (k != null) plugin.messages().send(k, "party.kicked");
                tell(party, "party.member-left", "player", nameOf(u));
                if (party.members.size() <= 1) disbandInternal(party);
                return;
            }
        }
        plugin.messages().send(leader, "player-not-found");
    }

    public void disband(Player leader) {
        Party party = byPlayer.get(leader.getUniqueId());
        if (party == null || !party.leader.equals(leader.getUniqueId())) {
            plugin.messages().send(leader, "party.leaders-only");
            return;
        }
        disbandInternal(party);
    }

    private void disbandInternal(Party party) {
        tell(party, "party.disbanded");
        for (UUID u : party.members) byPlayer.remove(u);
        party.members.clear();
    }

    public void list(Player p) {
        Party party = byPlayer.get(p.getUniqueId());
        if (party == null) {
            plugin.messages().send(p, "party.none");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (UUID u : party.members) {
            if (sb.length() > 0) sb.append("&7, ");
            sb.append(u.equals(party.leader) ? "&6\u2605 &f" : "&f").append(nameOf(u));
        }
        plugin.messages().send(p, "party.list", "members", sb.toString());
    }

    public void onQuit(Player p) {
        leave(p, true);
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }
}
