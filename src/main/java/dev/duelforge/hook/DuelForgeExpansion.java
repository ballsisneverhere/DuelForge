package dev.duelforge.hook;

import dev.duelforge.DuelForge;
import dev.duelforge.stats.StatsManager.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * %duelforge_wins% %duelforge_losses% %duelforge_kills% %duelforge_deaths% %duelforge_kd% %duelforge_wl%
 * %duelforge_elo% %duelforge_rank% %duelforge_streak% %duelforge_best_streak% %duelforge_win_streak%
 * %duelforge_matches% %duelforge_season% %duelforge_season_wins% %duelforge_season_kills%
 * %duelforge_kit_wins_&lt;kit&gt;% %duelforge_in_match% %duelforge_queued% %duelforge_party_size%
 * %duelforge_top_&lt;wins|kills|elo|season_wins|season_kills&gt;_&lt;1-10&gt;_&lt;name|value&gt;%
 */
public final class DuelForgeExpansion extends PlaceholderExpansion {
    private final DuelForge plugin;

    public DuelForgeExpansion(DuelForge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "duelforge";
    }

    @Override
    public String getAuthor() {
        return "DuelForge";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("season")) return String.valueOf(plugin.stats().season());

        if (p.startsWith("top_")) {
            String rest = p.substring(4);
            int last = rest.lastIndexOf('_');
            if (last < 0) return "";
            String what = rest.substring(last + 1);
            String rest2 = rest.substring(0, last);
            int pos = rest2.lastIndexOf('_');
            if (pos < 0) return "";
            int n;
            try {
                n = Integer.parseInt(rest2.substring(pos + 1));
            } catch (NumberFormatException e) {
                return "";
            }
            String metric = rest2.substring(0, pos);
            List<PlayerStats> top = plugin.stats().top(metric, Math.max(1, n));
            if (n < 1 || top.size() < n) return what.equals("name") ? "-" : "0";
            PlayerStats s = top.get(n - 1);
            return what.equals("name") ? s.name : String.valueOf(plugin.stats().value(s, metric));
        }
        if (player == null) return "";
        var id = player.getUniqueId();
        if (p.equals("in_match")) return String.valueOf(plugin.matches().inMatch(id));
        if (p.equals("queued")) return String.valueOf(plugin.queue().get(id) != null);
        if (p.equals("party_size")) {
            var party = plugin.parties().of(id);
            return String.valueOf(party == null ? 1 : party.members.size());
        }
        PlayerStats s = plugin.stats().get(id, player.getName());
        if (p.startsWith("kit_wins_")) return String.valueOf(s.kitWins.getOrDefault(p.substring(9), 0));
        if (p.startsWith("kit_kills_")) return String.valueOf(s.kitKills.getOrDefault(p.substring(10), 0));
        return switch (p) {
            case "wins" -> String.valueOf(s.wins);
            case "losses" -> String.valueOf(s.losses);
            case "kills" -> String.valueOf(s.kills);
            case "deaths" -> String.valueOf(s.deaths);
            case "kd" -> String.valueOf(s.kd());
            case "wl" -> String.valueOf(s.wl());
            case "elo" -> String.valueOf(s.elo);
            case "rank" -> String.valueOf(plugin.stats().rank(id, "elo"));
            case "streak" -> String.valueOf(s.killStreak);
            case "best_streak" -> String.valueOf(s.bestKillStreak);
            case "win_streak" -> String.valueOf(s.winStreak);
            case "matches" -> String.valueOf(s.matches);
            case "season_wins" -> String.valueOf(s.seasonWins);
            case "season_kills" -> String.valueOf(s.seasonKills);
            default -> null;
        };
    }
}
