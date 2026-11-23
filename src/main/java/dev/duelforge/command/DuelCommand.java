package dev.duelforge.command;

import dev.duelforge.DuelForge;
import dev.duelforge.arena.Arena;
import dev.duelforge.arena.ArenaBuilder;
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
            "spectate", "ffa", "join", "start", "rematch", "invites", "menu", "lobby", "queue", "party", "friends",
            "cosmetics", "help", "admin");
    private static final List<String> ADMIN = List.of("reload", "arena", "setlobby", "build", "world", "autosetup", "kit", "board", "season", "end", "resetstats");
    private static final List<String> WORLD = List.of("create", "tp", "list");
    private static final List<String> ARENA = List.of("create", "quick", "delete", "pos1", "pos2", "addspawn", "clearspawns",
            "spectator", "toggle", "building", "icon", "list", "info", "tp", "generate");

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
                if (plugin.queue().get(p.getUniqueId()) != null) plugin.queue().leave(p.getUniqueId());
                else if (!mm.leave(p, false)) plugin.messages().send(p, "not-in-match");
            }
            case "queue", "q" -> plugin.extra().queue(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "party" -> plugin.extra().party(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "friends", "friend" -> plugin.extra().friend(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "cosmetics", "cosmetic" -> gui.cosmetics(p);
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
            case "lobby", "spawn", "hub" -> {
                if (mm.inMatch(p.getUniqueId())) {
                    plugin.messages().send(p, "already-in-match");
                } else if (plugin.lobby() == null) {
                    plugin.messages().send(p, "lobby-not-set");
                } else p.teleport(plugin.lobby());
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
            case "build" -> {
                if (s instanceof Player p) {
                    boolean on = plugin.toggleBuilder(p.getUniqueId());
                    plugin.messages().send(s, on ? "admin.build-on" : "admin.build-off");
                    if (!on) plugin.hubItems().give(p);
                } else plugin.messages().send(s, "player-only");
            }
            case "world" -> adminWorld(s, a);
            case "autosetup" -> autosetup(s, a);
            case "kit" -> adminKit(s, a);
            case "board" -> adminBoard(s, a);
            case "season" -> adminSeason(s, a);
            default -> plugin.messages().send(s, "admin.usage");
        }
    }

    /** /duel admin kit <create|delete|icon|name|setting|list> ... - kits made in-game (custom-kits.yml). */
    private void adminKit(CommandSender s, String[] a) {
        var ms = plugin.messages();
        var km = plugin.kits();
        if (a.length < 3) {
            ms.send(s, "admin.kit-usage");
            return;
        }
        String sub = a[2].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            for (Kit k : km.all()) s.sendMessage(ms.get("admin.kit-list-entry", "id", k.id, "type", k.custom ? "&acustom" : "&7kits.yml"));
            return;
        }
        if (a.length < 4) {
            ms.send(s, "admin.kit-usage");
            return;
        }
        String id = a[3].toLowerCase(Locale.ROOT);
        Kit k = km.get(id);
        if (sub.equals("create")) {
            if (!(s instanceof Player p)) {
                ms.send(s, "player-only");
                return;
            }
            if (!id.matches("[a-z0-9_\\-]{1,24}")) {
                ms.send(s, "admin.kit-usage");
                return;
            }
            if (k != null) {
                ms.send(s, "admin.kit-exists");
                return;
            }
            Kit created = km.createCustom(id, p);
            ms.send(s, "admin.kit-created", "name", id, "items", created.inventory.size() + created.equipment.size());
            return;
        }
        if (k == null) {
            ms.send(s, "kit-not-found");
            return;
        }
        if (sub.equals("delete")) {
            ms.send(s, km.deleteCustom(id) ? "admin.kit-deleted" : "admin.kit-not-custom");
            return;
        }
        if (!k.custom) {
            ms.send(s, "admin.kit-not-custom");
            return;
        }
        switch (sub) {
            case "icon" -> {
                if (!(s instanceof Player p) || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    ms.send(s, "admin.kit-hold-item");
                    return;
                }
                k.icon = p.getInventory().getItemInMainHand().getType();
            }
            case "name" -> {
                if (a.length < 5) {
                    ms.send(s, "admin.kit-usage");
                    return;
                }
                k.displayName = String.join(" ", java.util.Arrays.copyOfRange(a, 4, a.length));
            }
            case "desc" -> {
                if (a.length < 5) {
                    ms.send(s, "admin.kit-usage");
                    return;
                }
                k.description = new ArrayList<>(List.of(String.join(" ", java.util.Arrays.copyOfRange(a, 4, a.length))));
            }
            case "setting" -> {
                if (a.length < 6) {
                    ms.send(s, "admin.kit-usage");
                    return;
                }
                boolean v = a[5].equalsIgnoreCase("true");
                switch (a[4].toLowerCase(Locale.ROOT)) {
                    case "regen" -> k.naturalRegen = v;
                    case "hunger" -> k.hunger = v;
                    case "fall" -> k.fallDamage = v;
                    default -> {
                        ms.send(s, "admin.kit-usage");
                        return;
                    }
                }
            }
            default -> {
                ms.send(s, "admin.kit-usage");
                return;
            }
        }
        km.saveCustom();
        ms.send(s, "admin.kit-updated");
    }

    /** /duel admin board <create|remove|refresh> [metric] - leaderboard holograms. */
    private void adminBoard(CommandSender s, String[] a) {
        var ms = plugin.messages();
        var lb = plugin.leaderboards();
        if (a.length < 3) {
            ms.send(s, "admin.board-usage");
            return;
        }
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(s instanceof Player p)) {
                    ms.send(s, "player-only");
                    return;
                }
                String metric = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "wins";
                if (!lb.validMetric(metric)) {
                    ms.send(s, "admin.board-usage");
                    return;
                }
                lb.create(p, metric);
                ms.send(s, "admin.board-created");
            }
            case "remove" -> {
                if (!(s instanceof Player p)) {
                    ms.send(s, "player-only");
                    return;
                }
                ms.send(s, lb.removeNearest(p, 6) ? "admin.board-removed" : "admin.board-none-near");
            }
            case "refresh" -> {
                lb.refreshAll();
                ms.send(s, "admin.board-refreshed");
            }
            default -> ms.send(s, "admin.board-usage");
        }
    }

    /** /duel admin season new confirm - archives the season and resets season counters. */
    private void adminSeason(CommandSender s, String[] a) {
        var ms = plugin.messages();
        if (a.length < 3 || !a[2].equalsIgnoreCase("new")) {
            ms.send(s, "admin.season-usage", "season", plugin.stats().season());
            return;
        }
        if (a.length < 4 || !a[3].equalsIgnoreCase("confirm")) {
            ms.send(s, "admin.season-confirm");
            return;
        }
        var podium = plugin.stats().newSeason();
        Bukkit.broadcastMessage(ms.get("admin.season-started", "season", plugin.stats().season()));
        for (int i = 0; i < podium.size(); i++) {
            Bukkit.broadcastMessage(ms.get("admin.season-podium", "rank", i + 1, "player", podium.get(i).name, "wins", podium.get(i).seasonWins));
        }
        plugin.leaderboards().refreshAll();
    }

    /** /duel admin arena generate <theme> [name] - builds a finished map at your feet. */
    private void generate(CommandSender s, String[] a) {
        var ms = plugin.messages();
        if (!(s instanceof Player p)) {
            ms.send(s, "player-only");
            return;
        }
        ArenaBuilder.Theme theme = a.length > 3 ? ArenaBuilder.Theme.parse(a[3]) : null;
        if (theme == null) {
            ms.send(s, "admin.generate-usage");
            return;
        }
        String name = (a.length > 4 ? a[4] : a[3]).toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_\\-]{1,24}")) {
            ms.send(s, "arena-not-found");
            return;
        }
        Arena existing = plugin.arenas().get(name);
        if (existing != null && existing.current != null) {
            ms.send(s, "admin.arena-in-use");
            return;
        }
        var w = p.getWorld();
        boolean clear = !(w.getGenerator() instanceof DuelForge.VoidGenerator);
        Location l = p.getLocation();
        ArenaBuilder.Plan plan = ArenaBuilder.plan(theme, l.getBlockX(), l.getBlockY() - 1, l.getBlockZ(), clear);
        ArenaBuilder.register(plugin.arenas(), name, w, plan);
        ms.send(s, "admin.generate-start", "theme", theme.name().toLowerCase(Locale.ROOT), "name", name, "blocks", plan.ops.size());
        ArenaBuilder.apply(plugin, w, plan.ops, () -> {
            plugin.arenas().save(true);
            ms.send(p, "admin.generate-done", "name", name);
        });
    }

    /** /duel admin autosetup [world] - void world + hub + 4 arenas + bridges, all in one go. */
    private void autosetup(CommandSender s, String[] a) {
        var ms = plugin.messages();
        if (!(s instanceof Player p)) {
            ms.send(s, "player-only");
            return;
        }
        String worldName = (a.length > 2 ? a[2] : "pvp").toLowerCase(Locale.ROOT);
        if (!worldName.matches("[a-z0-9_\\-]{1,24}")) {
            ms.send(s, "admin.world-bad-name");
            return;
        }
        if (plugin.worldNameTaken(worldName)) {
            ms.send(s, "admin.world-exists");
            return;
        }
        for (ArenaBuilder.Theme t : ArenaBuilder.Theme.values()) {
            Arena ex = plugin.arenas().get(t.name().toLowerCase(Locale.ROOT));
            if (ex != null && ex.current != null) {
                ms.send(s, "admin.arena-in-use");
                return;
            }
        }
        ms.send(s, "admin.autosetup-start");
        Location spawn = plugin.createWorld(worldName, "void"); // void world with a platform at y=64
        var w = spawn.getWorld();
        final int y0 = 64, d = ArenaBuilder.ARENA_DISTANCE;
        ArenaBuilder.Plan hub = ArenaBuilder.hub(0, y0, 0, false);
        List<ArenaBuilder.Op> ops = new ArrayList<>(hub.ops);
        Object[][] layout = {
                {ArenaBuilder.Theme.COLOSSEUM, d, 0},
                {ArenaBuilder.Theme.SKY, 0, -d},
                {ArenaBuilder.Theme.CASTLE, -d, 0},
                {ArenaBuilder.Theme.WASTELAND, 0, d}};
        for (Object[] e : layout) {
            ArenaBuilder.Theme t = (ArenaBuilder.Theme) e[0];
            ArenaBuilder.Plan plan = ArenaBuilder.plan(t, (Integer) e[1], y0, (Integer) e[2], false);
            ArenaBuilder.register(plugin.arenas(), t.name().toLowerCase(Locale.ROOT), w, plan);
            ops.addAll(plan.ops);
        }
        ops.addAll(ArenaBuilder.bridges(y0));
        Location lobby = new Location(w, 0.5, y0 + 1, 0.5, 0f, 0f);
        plugin.setLobby(lobby);
        p.teleport(lobby);
        ArenaBuilder.apply(plugin, w, ops, () -> {
            plugin.arenas().save(true);
            ms.send(p, "admin.autosetup-done");
        });
    }

    private void adminWorld(CommandSender s, String[] a) {
        var ms = plugin.messages();
        if (a.length < 3) {
            ms.send(s, "admin.world-usage");
            return;
        }
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                for (var e : plugin.customWorlds().entrySet()) {
                    s.sendMessage(ms.get("admin.world-list-entry", "name", e.getKey(), "type", e.getValue()));
                }
            }
            case "tp" -> {
                if (!(s instanceof Player p)) {
                    ms.send(s, "player-only");
                    return;
                }
                var w = a.length > 3 ? Bukkit.getWorld(a[3]) : null;
                if (w == null) {
                    ms.send(s, "admin.world-not-found");
                    return;
                }
                p.teleport(w.getSpawnLocation());
                ms.send(s, "admin.world-teleported", "name", w.getName());
            }
            case "create" -> {
                if (!(s instanceof Player p)) {
                    ms.send(s, "player-only");
                    return;
                }
                if (a.length < 4 || !a[3].matches("[a-z0-9_\\-]{1,24}")) {
                    ms.send(s, "admin.world-bad-name");
                    return;
                }
                String name = a[3];
                String type = a.length > 4 && a[4].equalsIgnoreCase("flat") ? "flat" : "void";
                if (plugin.worldNameTaken(name)) {
                    ms.send(s, "admin.world-exists");
                    return;
                }
                Location spawn = plugin.createWorld(name, type);
                p.teleport(spawn);
                plugin.setLobby(spawn);
                if (!plugin.isBuilder(p.getUniqueId())) plugin.toggleBuilder(p.getUniqueId());
                ms.send(s, "admin.world-created", "name", name, "type", type);
            }
            default -> ms.send(s, "admin.world-usage");
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
        if (sub.equals("generate")) {
            generate(s, a);
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
        else if (args.length == 3 && args[1].equalsIgnoreCase("world")) out.addAll(WORLD);
        else if (args.length == 3 && args[1].equalsIgnoreCase("kit")) out.addAll(List.of("create", "delete", "icon", "name", "desc", "setting", "list"));
        else if (args.length == 4 && args[1].equalsIgnoreCase("kit")) for (Kit k : plugin.kits().all()) out.add(k.id);
        else if (args.length == 3 && args[1].equalsIgnoreCase("board")) out.addAll(List.of("create", "remove", "refresh"));
        else if (args.length == 4 && args[1].equalsIgnoreCase("board") && args[2].equalsIgnoreCase("create")) {
            out.addAll(List.of("wins", "kills", "elo", "season_wins", "season_kills"));
            for (Kit k : plugin.kits().enabled()) out.add("kit:" + k.id);
        }
        else if (args.length == 3 && args[1].equalsIgnoreCase("season")) out.add("new");
        else if (args.length == 5 && args[1].equalsIgnoreCase("kit") && args[2].equalsIgnoreCase("setting")) out.addAll(List.of("regen", "hunger", "fall"));
        else if (args.length == 4 && args[1].equalsIgnoreCase("world") && args[2].equalsIgnoreCase("create")) out.addAll(List.of("void", "flat"));
        else if (args.length == 4 && args[1].equalsIgnoreCase("arena") && args[2].equalsIgnoreCase("generate")) {
            for (ArenaBuilder.Theme t : ArenaBuilder.Theme.values()) out.add(t.name().toLowerCase(Locale.ROOT));
        }
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
