package dev.duelforge.ui;

import dev.duelforge.DuelForge;
import dev.duelforge.Messages;
import dev.duelforge.match.Match;
import dev.duelforge.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player sidebar showing kills, deaths, lives and remaining players. */
public final class ScoreboardService {
    private static final int MAX_LINES = 15;

    private final DuelForge plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public ScoreboardService(DuelForge plugin) {
        this.plugin = plugin;
    }

    private static String entry(int i) {
        return "\u00a7" + Integer.toHexString(i) + "\u00a7r";
    }

    private Scoreboard create(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("duelforge", "dummy", plugin.messages().get("scoreboard.title"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < MAX_LINES; i++) {
            Team t = sb.registerNewTeam("l" + i);
            t.addEntry(entry(i));
        }
        return sb;
    }

    public void update(Player p, Match m) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;
        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(), k -> create(p));
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        Objective o = sb.getObjective("duelforge");
        if (o == null) return;

        UUID id = p.getUniqueId();
        boolean spectating = m.spectators.contains(id);
        String type = spectating ? "spectator" : (m.ffa ? "ffa" : "duel");
        List<String> lines = plugin.messages().rawList("scoreboard.lines." + type);

        Messages ms = plugin.messages();
        String opponent = "-";
        if (!m.ffa) {
            for (UUID u : m.players) {
                if (!u.equals(id)) {
                    Player op = Bukkit.getPlayer(u);
                    opponent = op != null ? op.getName() : "?";
                }
            }
        }
        String time = switch (m.state) {
            case ACTIVE -> Fmt.time((System.currentTimeMillis() - m.startMillis) / 1000);
            case COUNTDOWN -> "Starting";
            case ENDING -> "Ended";
            default -> "Waiting";
        };
        int total = 0;
        for (UUID u : m.players) if (!m.left.contains(u)) total++;
        Map<String, String> ph = new HashMap<>();
        ph.put("kit", Fmt.color(m.kit.displayName));
        ph.put("arena", Fmt.color(m.arena.displayName));
        ph.put("mode", m.ffa ? "FFA" : ms.get(m.ranked ? "request.ranked" : "request.unranked"));
        ph.put("time", time);
        ph.put("opponent", opponent);
        ph.put("kills", String.valueOf(m.kills(id)));
        ph.put("deaths", String.valueOf(m.deaths(id)));
        ph.put("lives", String.valueOf(Math.max(0, m.livesLeft.getOrDefault(id, 0))));
        ph.put("alive", String.valueOf(m.alivePlayers().size()));
        ph.put("total", String.valueOf(total));

        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entry(i);
            Team t = sb.getTeam("l" + i);
            if (t == null) continue;
            if (i < count) {
                String text = Fmt.color(Messages.apply(lines.get(i), ph));
                if (text.length() > 64) text = text.substring(0, 64);
                t.setPrefix(text);
                o.getScore(entry).setScore(count - i);
            } else {
                sb.resetScores(entry);
            }
        }
    }

    public void remove(Player p) {
        if (boards.remove(p.getUniqueId()) != null && Bukkit.getScoreboardManager() != null) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }
}
