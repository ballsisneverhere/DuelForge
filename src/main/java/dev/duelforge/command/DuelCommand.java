package dev.duelforge.command;

import dev.duelforge.DuelForge;
import dev.duelforge.arena.Arena;
import dev.duelforge.kit.Kit;
import dev.duelforge.match.Match;
import dev.duelforge.stats.StatsManager.PlayerStats;
import dev.duelforge.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Handles /duel, /accept, /deny and /duelstats. */
public final class DuelCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("accept", "deny", "leave", "stats", "top", "kits", "kit", "editkit",
            "spectate", "ffa", "join", "start", "rematch", "invites", "menu", "help", "admin");
    private static final List<String> ADMIN = List.of("reload", "arena", "setlobby", "end", "resetstats");
    private static final List<String> ARENA = List.of("create", "quick", "delete", "pos1", "pos2", "addspawn", "clearspawns",
            "spectator", "toggle", "building", "icon", "list", "info", "tp");

    private final DuelForge plugin;

    public DuelCommand(DuelForge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "accept" -> {
                Player p = needPlayer(sender);
                if (p != null) plugin.matches().accept(p, args.length > 0 ? args[0] : null);
            }
            case "deny" -> {
                Player p = needPlayer(sender);
                if (p != null) plugin.matches().deny(p, args.length > 0 ? args[0] : null);
            }
            case "duelstats" -> stats(sender, args);
            default -> duel(sender, args);
        }
        return true;
    }

    private Player needPlayer(CommandSender s) {
        if (s instanceof Player p) {
            if (!p.hasPermission("duelforge.use")) {
                plugin.messages().send(p, "no-permission");
                return null;
            }
            return p;
        }
        plugin.messages().send(s, "player-only");
        return null;
    }

    // =====================================================================

    private void duel(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            admin(sender, args);
            return;
        }
        Player p = needPlayer(sender);
        if (p == null) return;
        var mm = plugin.matches();
        var gui = plugin.gui();
        if (args.length == 0) {
            gui.hub(p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> mm.accept(p, args.length > 1 ? args[1] : null);
            case "deny" -> mm.deny(p, args.length > 1 ? args[1] : null);
            case "leave" -> {
                if (!mm.leave(p, false)) plugin.messages().send(p, "not-in-match");
            }
            case "stats" -> stats(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "top" -> top(p, args.length > 1 ? args[1] : "wins");
            case "kits" -> gui.kitBrowser(p);
            case "kit" -> {
                Kit k = args.length > 1 ? plugin.kits().get(args[1]) : null;
                if (k == null) plugin.messages().send(p, "kit-not-found");
                else gui.preview(p, k, null, () -> gui.kitBrowser(p));
            }
            case "editkit", "editor" -> gui.editor(p);
            case "spectate", "watch" -> {
                if (!p.hasPermission("duelforge.spectate")) {
                    plugin.messages().send(p, "no-permission");
                } else if (args.length > 1) {
                    Match m = mm.matchOfPlayer(args[1]);
                    if (m == null) plugin.messages().send(p, "spectate.not-found");
                    else mm.spectate(p, m);
                } else gui.spectate(p);
            }
            case "ffa" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("create")) gui.ffaSetup(p);
                else gui.lobbies(p);
            }
            case "join" -> {
                if (!p.hasPermission("duelforge.ffa")) {
                    plugin.messages().send(p, "no-permission");
                } else if (args.length < 2) {
                    gui.lobbies(p);
                } else mm.joinLobby(p, mm.lobbyOf(args[1]));
            }
            case "start" -> {
                Match m = mm.get(p);
                if (m == null || !m.host.equals(p.getUniqueId()) || m.state != Match.State.LOBBY) plugin.messages().send(p, "ffa.no-lobby");
                else mm.startLobby(p, m);
            }
            case "rematch" -> {
                if (p.hasPermission("duelforge.duel")) mm.rematch(p);
                else plugin.messages().send(p, "no-permission");
            }
            case "invites" -> gui.invites(p);
            case "menu" -> gui.hub(p);
            case "help" -> {
                for (String line : plugin.messages().list("help")) p.sendMessage(line);
            }
            default -> challenge(p, args);
        }
    }

    private void challenge(Player p, String[] args) {
        if (!p.hasPermission("duelforge.duel")) {
            plugin.messages().send(p, "no-permission");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.messages().send(p, "player-not-found");
            return;
        }
        if (args.length == 1) {
            plugin.gui().duelSetup(p, target);
            return;
        }
        Kit kit = plugin.kits().get(args[1]);
        if (kit == null) {
            plugin.messages().send(p, "kit-not-found");
            return;
        }
        Arena arena = null;
        boolean ranked = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("ranked")) ranked = true;
            else if (!args[i].equalsIgnoreCase("random") && !args[i].equalsIgnoreCase("unranked")) {
                arena = plugin.arenas().get(args[i]);
                if (arena == null) {
                    plugin.messages().send(p, "arena-not-found");
                    return;
                }
            }
        }
        plugin.matches().sendRequest(p, target, kit, arena, ranked);
    }

    // =====================================================================
    // stats
    // =====================================================================

    private void stats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("duelforge.stats")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            top(sender, args.length > 1 ? args[1] : "wins");
            return;
        }
        OfflinePlayer target;
        if (args.length > 0) {
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) target = online;
            else {
                PlayerStats found = plugin.stats().find(args[0]);
                if (found == null) {
                    plugin.messages().send(sender, "player-not-found");
                    return;
                }
                target = Bukkit.getOfflinePlayer(found.id);
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (sender instanceof Player p) {
            plugin.gui().stats(p, target);
        } else {
            PlayerStats st = plugin.stats().find(target.getName() == null ? "" : target.getName());
            if (st == null) {
                plugin.messages().send(sender, "stats.none");
                return;
            }
            sender.sendMessage(plugin.messages().get("stats.header", "player", st.name));
            sender.sendMessage(plugin.messages().get("stats.line", "wins", st.wins, "losses", st.losses, "kills", st.kills, "deaths", st.deaths));
            sender.sendMessage(plugin.messages().get("stats.line2", "kd", st.kd(), "streak", st.killStreak, "best", st.bestKillStreak, "elo", st.elo));
        }
    }

    private void top(CommandSender sender, String metric) {
        metric = metric.toLowerCase(Locale.ROOT);
        if (!List.of("wins", "kills", "elo").contains(metric)) metric = "wins";
        List<PlayerStats> list = plugin.stats().top(metric, 10);
        sender.sendMessage(plugin.messages().get("stats.top-header", "metric", metric));
        if (list.isEmpty()) sender.sendMessage(plugin.messages().get("stats.none"));
        for (int i = 0; i < list.size(); i++) {
            PlayerStats s = list.get(i);
            int value = switch (metric) {
                case "kills" -> s.kills;
                case "elo" -> s.elo;
                default -> s.wins;
            };
            sender.sendMessage(plugin.messages().get("stats.top-line", "rank", i + 1, "player", s.name, "value", value));
        }
    }

    // =====================================================================
    // admin
    // =====================================================================

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("duelforge.admin")) {
            plugin.messages().send(s, "no-permission");
            return;
        }
        if (a.length < 2) {
            plugin.messages().send(s, "admin.usage");
            return;
        }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                plugin.messages().send(s, "reloaded");
            }
            case "setlobby" -> {
                if (s instanceof Player p) {
                    plugin.setLobby(p.getLocation());
                    plugin.messages().send(s, "admin.lobby-set");
                } else plugin.messages().send(s, "player-only");
            }
            case "end" -> {
                Match m = a.length > 2 ? plugin.matches().matchOfPlayer(a[2]) : (s instanceof Player p ? plugin.matches().get(p) : null);
                if (m == null) plugin.messages().send(s, "not-in-match");
                else {
                    plugin.matches().forceEnd(m);
                    plugin.messages().send(s, "admin.ended");
                }
            }
            case "resetstats" -> {
                if (a.length < 3) {
                    plugin.messages().send(s, "admin.usage");
                    return;
                }
                PlayerStats st = plugin.stats().find(a[2]);
                if (st == null) {
                    plugin.messages().send(s, "player-not-found");
                    return;
                }
                plugin.stats().reset(st.id);
                plugin.messages().send(s, "stats.reset", "player", st.name);
            }
            case "arena" -> adminArena(s, a);
            default -> plugin.messages().send(s, "admin.usage");
        }
    }

    private void adminArena(CommandSender s, String[] a) {
        var ms = plugin.messages();
        var am = plugin.arenas();
        if (a.length < 3) {
            ms.send(s, "admin.arena-usage");
            return;
        }
        String sub = a[2].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            for (Arena ar : am.all()) {
                s.sendMessage(ms.get("admin.arena-list-entry", "name", ar.name,
                        "state", ar.ready() ? (ar.current == null ? "&aready" : "&ein use") : "&cnot ready"));
            }
            return;
        }
        if (a.length < 4) {
            ms.send(s, "admin.arena-usage");
            return;
        }
        String name = a[3].toLowerCase(Locale.ROOT);
        Arena ar = am.get(name);
        Player p = s instanceof Player pl ? pl : null;

        if (sub.equals("create") || sub.equals("quick")) {
            if (p == null) {
                ms.send(s, "player-only");
                return;
            }
            if (ar != null) {
                ms.send(s, "admin.arena-exists");
                return;
            }
            if (!name.matches("[a-z0-9_\\-]{1,24}")) {
                ms.send(s, "arena-not-found");
                return;
            }
            Arena created = am.create(name, p.getWorld());
            if (sub.equals("quick")) quick(p, created, a.length > 4 ? parseInt(a[4], 15) : 15);
            else ms.send(s, "admin.arena-created", "name", name);
            am.save(true);
            return;
        }
        if (ar == null) {
            ms.send(s, "arena-not-found");
            return;
        }
        if (ar.current != null && !sub.equals("info") && !sub.equals("tp")) {
            ms.send(s, "admin.arena-in-use");
            return;
        }
        switch (sub) {
            case "delete" -> {
                am.delete(name);
                ms.send(s, "admin.arena-deleted");
            }
            case "pos1", "pos2" -> {
                if (p == null) {
                    ms.send(s, "player-only");
                    return;
                }
                Location l = p.getLocation();
                if (!l.getWorld().getName().equals(ar.worldName)) {
                    ar.worldName = l.getWorld().getName();
                    ar.spawns.clear();
                    ar.spectator = null;
                    ar.p1 = null;
                    ar.p2 = null;
                    ar.boundsSet = false;
                }
                int[] pos = {l.getBlockX(), l.getBlockY(), l.getBlockZ()};
                if (sub.equals("pos1")) ar.p1 = pos;
                else ar.p2 = pos;
                ar.recalc();
                ms.send(s, "admin.arena-saved");
            }
            case "addspawn" -> {
                if (p == null) {
                    ms.send(s, "player-only");
                    return;
                }
                ar.spawns.add(Arena.Pos.of(p.getLocation()));
                ms.send(s, "admin.arena-saved");
            }
            case "clearspawns" -> {
                ar.spawns.clear();
                ms.send(s, "admin.arena-saved");
            }
            case "spectator" -> {
                if (p == null) {
                    ms.send(s, "player-only");
                    return;
                }
                ar.spectator = Arena.Pos.of(p.getLocation());
                ms.send(s, "admin.arena-saved");
            }
            case "toggle" -> {
                ar.enabled = !ar.enabled;
                ms.send(s, "admin.arena-saved");
            }
            case "building" -> {
                ar.allowBuilding = a.length > 4 && a[4].equalsIgnoreCase("true");
                ms.send(s, "admin.arena-saved");
            }
            case "icon" -> {
                Material m = a.length > 4 ? Material.matchMaterial(a[4]) : null;
                if (m == null || !m.isItem()) {
                    ms.send(s, "admin.arena-usage");
                    return;
                }
                ar.icon = m;
                ms.send(s, "admin.arena-saved");
            }
            case "info" -> {
                s.sendMessage(ms.get("admin.arena-info", "name", ar.name, "world", ar.worldName,
                        "bounds", ar.boundsSet ? ar.minX + "," + ar.minY + "," + ar.minZ + " -> " + ar.maxX + "," + ar.maxY + "," + ar.maxZ : "unset",
                        "spawns", ar.spawns.size(), "ready", ar.ready(), "building", ar.allowBuilding));
                return;
            }
            case "tp" -> {
                if (p != null && ar.ready()) plugin.matches().tp(p, ar.spawn(0));
                return;
            }
            default -> {
                ms.send(s, "admin.arena-usage");
                return;
            }
        }
        if (!sub.equals("delete") && !ar.ready()) ms.send(s, "admin.arena-not-ready", "name", ar.name);
        am.save(true);
    }

    private int parseInt(String s, int def) {
        try {
            return Math.max(8, Math.min(60, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Instant arena: a cube around the player with four spawns facing the centre. */
    private void quick(Player p, Arena ar, int r) {
        Location l = p.getLocation();
        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
        ar.worldName = p.getWorld().getName();
        ar.p1 = new int[]{bx - r, by - 4, bz - r};
        ar.p2 = new int[]{bx + r, by + r, bz + r};
        ar.recalc();
        double cx = bx + 0.5, cz = bz + 0.5, y = l.getY();
        int d = r - 3;
        ar.spawns.clear();
        ar.spawns.add(new Arena.Pos(cx + d, y, cz, 90f, 0f));
        ar.spawns.add(new Arena.Pos(cx - d, y, cz, -90f, 0f));
        ar.spawns.add(new Arena.Pos(cx, y, cz + d, 180f, 0f));
        ar.spawns.add(new Arena.Pos(cx, y, cz - d, 0f, 0f));
        ar.spectator = new Arena.Pos(cx, y + 8, cz, 0f, 45f);
        plugin.messages().send(p, "admin.arena-quick", "name", ar.name, "radius", r, "spawns", ar.spawns.size());
    }

    // =====================================================================
    // tab completion
    // =====================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("accept") || name.equals("deny") || name.equals("duelstats")) {
            if (args.length == 1) {
                players(out, args[0]);
                if (name.equals("duelstats")) out.add("top");
            }
            return filter(out, args[args.length - 1]);
        }
        if (args.length == 1) {
            out.addAll(SUBS);
            players(out, args[0]);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "accept", "deny", "stats", "spectate", "watch", "join" -> {
                    if (args.length == 2) players(out, args[1]);
                }
                case "kit" -> {
                    if (args.length == 2) for (Kit k : plugin.kits().enabled()) out.add(k.id);
                }
                case "top" -> {
                    if (args.length == 2) out.addAll(List.of("wins", "kills", "elo"));
                }
                case "admin" -> adminTab(args, out);
                default -> {
                    if (args.length == 2) for (Kit k : plugin.kits().enabled()) out.add(k.id);
                    else {
                        out.add("ranked");
                        out.add("random");
                        for (Arena a : plugin.arenas().all()) out.add(a.name);
                    }
                }
            }
        }
        return filter(out, args[args.length - 1]);
    }

    private void adminTab(String[] args, List<String> out) {
        if (args.length == 2) out.addAll(ADMIN);
        else if (args.length == 3 && args[1].equalsIgnoreCase("arena")) out.addAll(ARENA);
        else if (args.length == 4 && args[1].equalsIgnoreCase("arena")) for (Arena a : plugin.arenas().all()) out.add(a.name);
        else if (args.length == 3 && (args[1].equalsIgnoreCase("end") || args[1].equalsIgnoreCase("resetstats"))) players(out, args[2]);
    }

    private void players(List<String> out, String prefix) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }

    private List<String> filter(List<String> in, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : in) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
