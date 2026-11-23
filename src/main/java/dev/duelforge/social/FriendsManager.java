package dev.duelforge.social;

import dev.duelforge.DuelForge;
import dev.duelforge.util.Persist;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Friends list (friends.yml). Friend requests must be accepted. */
public final class FriendsManager {
    private final DuelForge plugin;
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> pending = new HashMap<>();
    private final File file;

    public FriendsManager(DuelForge plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "friends.yml");
    }

    public void load() {
        friends.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("friends");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Set<UUID> set = new HashSet<>();
                for (String s : root.getStringList(key)) set.add(UUID.fromString(s));
                friends.put(id, set);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save(boolean async) {
        YamlConfiguration c = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> e : friends.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            c.set("friends." + e.getKey(), l);
        }
        Persist.save(plugin, file, c, async);
    }

    public boolean isFriend(UUID a, UUID b) {
        return friends.getOrDefault(a, Set.of()).contains(b);
    }

    /** Online friends first, then alphabetical. */
    public List<UUID> list(UUID id) {
        List<UUID> out = new ArrayList<>(friends.getOrDefault(id, Set.of()));
        out.sort((a, b) -> {
            boolean oa = Bukkit.getPlayer(a) != null, ob = Bukkit.getPlayer(b) != null;
            if (oa != ob) return oa ? -1 : 1;
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });
        return out;
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    public void request(Player from, Player to) {
        var ms = plugin.messages();
        if (from.equals(to)) {
            ms.send(from, "friend.self");
            return;
        }
        if (isFriend(from.getUniqueId(), to.getUniqueId())) {
            ms.send(from, "friend.already", "player", to.getName());
            return;
        }
        Map<UUID, Long> mine = pending.get(from.getUniqueId());
        if (mine != null && mine.containsKey(to.getUniqueId()) && mine.get(to.getUniqueId()) > System.currentTimeMillis()) {
            mine.remove(to.getUniqueId());
            makeFriends(from, to);
            return;
        }
        pending.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>()).put(from.getUniqueId(), System.currentTimeMillis() + 120_000L);
        ms.send(from, "friend.sent", "player", to.getName());
        ms.sendButtons(to, ms.prefixed("friend.request-received", "player", from.getName()),
                new String[]{ms.get("friend.accept-button"), "/friend accept " + from.getName(), ms.get("friend.accept-hover")},
                new String[]{ms.get("friend.deny-button"), "/friend deny " + from.getName(), ms.get("friend.deny-hover")});
    }

    private UUID pick(Player p, String name) {
        Map<UUID, Long> map = pending.get(p.getUniqueId());
        if (map == null) return null;
        long now = System.currentTimeMillis();
        map.values().removeIf(t -> t < now);
        UUID best = null;
        for (UUID u : map.keySet()) {
            Player o = Bukkit.getPlayer(u);
            if (o != null && (name == null || o.getName().equalsIgnoreCase(name))) best = u;
        }
        return best;
    }

    public void accept(Player p, String name) {
        UUID u = pick(p, name);
        Player o = u == null ? null : Bukkit.getPlayer(u);
        if (o == null) {
            plugin.messages().send(p, "friend.no-request");
            return;
        }
        pending.get(p.getUniqueId()).remove(u);
        makeFriends(p, o);
    }

    public void deny(Player p, String name) {
        UUID u = pick(p, name);
        if (u == null) {
            plugin.messages().send(p, "friend.no-request");
            return;
        }
        pending.get(p.getUniqueId()).remove(u);
        plugin.messages().send(p, "friend.denied");
    }

    private void makeFriends(Player a, Player b) {
        friends.computeIfAbsent(a.getUniqueId(), k -> new HashSet<>()).add(b.getUniqueId());
        friends.computeIfAbsent(b.getUniqueId(), k -> new HashSet<>()).add(a.getUniqueId());
        save(true);
        plugin.messages().send(a, "friend.added", "player", b.getName());
        plugin.messages().send(b, "friend.added", "player", a.getName());
    }

    public boolean remove(UUID a, UUID b) {
        boolean had = friends.getOrDefault(a, new HashSet<>()).remove(b);
        friends.getOrDefault(b, new HashSet<>()).remove(a);
        if (had) save(true);
        return had;
    }
}
