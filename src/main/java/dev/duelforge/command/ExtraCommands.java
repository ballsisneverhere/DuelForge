package dev.duelforge.command;

import dev.duelforge.DuelForge;
import dev.duelforge.kit.Kit;
import dev.duelforge.party.PartyManager.Party;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /queue, /party and /friend. */
public final class ExtraCommands implements CommandExecutor, TabCompleter {
    private final DuelForge plugin;

    public ExtraCommands(DuelForge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!p.hasPermission("duelforge.use")) {
            plugin.messages().send(p, "no-permission");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "queue" -> queue(p, args);
            case "party" -> party(p, args);
            default -> friend(p, args);
        }
        return true;
    }

    // =====================================================================

    /** /queue [1v1|2v2|3v3] [kit] [ranked] [bo1|bo3|bo5]  |  /queue leave */
    public void queue(Player p, String[] args) {
        var ms = plugin.messages();
        var q = plugin.queue();
        if (args.length == 0) {
            plugin.gui().queue(p);
            return;
        }
        if (args[0].equalsIgnoreCase("leave") || args[0].equalsIgnoreCase("cancel")) {
            if (!q.leave(p.getUniqueId())) ms.send(p, "queue.not-in");
            return;
        }
        Party party = plugin.parties().of(p.getUniqueId());
        int teamSize = party != null && party.members.size() > 1 ? Math.min(3, party.members.size()) : 1;
        boolean ranked = false;
        int bestOf = Math.max(1, plugin.getConfig().getInt("duel.best-of", 3));
        Kit kit = null;
        for (String raw : args) {
            String t = raw.toLowerCase(Locale.ROOT);
            if (t.matches("[1-3]v[1-3]") && t.charAt(0) == t.charAt(2)) teamSize = t.charAt(0) - '0';
            else if (t.equals("ranked")) ranked = true;
            else if (t.equals("unranked")) ranked = false;
            else if (t.matches("bo[135]")) bestOf = t.charAt(2) - '0';
            else {
                kit = plugin.kits().get(t);
                if (kit == null) {
                    ms.send(p, "kit-not-found");
                    return;
                }
            }
        }
        if (kit == null) kit = plugin.kits().defaultKit();
        q.join(p, teamSize, kit, ranked, bestOf);
    }

    public void party(Player p, String[] args) {
        var pm = plugin.parties();
        if (args.length == 0) {
            plugin.gui().party(p);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite", "add" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) plugin.messages().send(p, "player-not-found");
                else pm.invite(p, t);
            }
            case "accept" -> pm.accept(p, args.length > 1 ? args[1] : null);
            case "deny" -> pm.deny(p, args.length > 1 ? args[1] : null);
            case "leave" -> pm.leave(p, false);
            case "kick" -> {
                if (args.length > 1) pm.kick(p, args[1]);
                else plugin.messages().send(p, "party.usage");
            }
            case "disband" -> pm.disband(p);
            case "list" -> pm.list(p);
            default -> {
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null) plugin.messages().send(p, "party.usage");
                else pm.invite(p, t);
            }
        }
    }

    public void friend(Player p, String[] args) {
        var fm = plugin.friends();
        var ms = plugin.messages();
        if (args.length == 0) {
            plugin.gui().friends(p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) ms.send(p, "player-not-found");
                else fm.request(p, t);
            }
            case "accept" -> fm.accept(p, args.length > 1 ? args[1] : null);
            case "deny" -> fm.deny(p, args.length > 1 ? args[1] : null);
            case "remove" -> {
                if (args.length < 2) {
                    ms.send(p, "friend.usage");
                    return;
                }
                for (UUID u : fm.list(p.getUniqueId())) {
                    String n = Bukkit.getOfflinePlayer(u).getName();
                    if (n != null && n.equalsIgnoreCase(args[1])) {
                        fm.remove(p.getUniqueId(), u);
                        ms.send(p, "friend.removed");
                        return;
                    }
                }
                ms.send(p, "player-not-found");
            }
            case "list" -> {
                List<String> names = new ArrayList<>();
                for (UUID u : fm.list(p.getUniqueId())) {
                    String n = Bukkit.getOfflinePlayer(u).getName();
                    names.add((Bukkit.getPlayer(u) != null ? "&a" : "&7") + (n == null ? "?" : n));
                }
                ms.send(p, "friend.list", "friends", names.isEmpty() ? "&7none" : String.join("&8, ", names));
            }
            default -> ms.send(p, "friend.usage");
        }
    }

    // =====================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        switch (name) {
            case "queue" -> {
                out.addAll(List.of("1v1", "2v2", "3v3", "ranked", "unranked", "bo1", "bo3", "bo5", "leave"));
                for (Kit k : plugin.kits().enabled()) out.add(k.id);
            }
            case "party" -> {
                if (args.length == 1) out.addAll(List.of("invite", "accept", "deny", "leave", "kick", "disband", "list"));
                else for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
            default -> {
                if (args.length == 1) out.addAll(List.of("add", "remove", "accept", "deny", "list"));
                else for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
