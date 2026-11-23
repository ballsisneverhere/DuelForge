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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player sidebar showing kills, deaths, lives, round score and players remaining. */
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

    private Scoreboard create() {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("duelforge", "dummy", plugin.messages().get("scoreboard.title"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < MAX_LINES; i++) sb.registerNewTeam("l" + i).addEntry(entry(i));
        return sb;
    }

    private String timeOf(Match m) {
        return switch (m.state) {
            case ACTIVE -> Fmt.time((System.currentTimeMillis() - m.startMillis) / 1000);
            case COUNTDOWN -> "Starting";
            case BETWEEN -> "Next round";
            case ENDING -> "Ended";
            case BUILDING -> "Building";
            default -> "Waiting";
        };
    }

    public void update(Player p, Match m) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;
        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(), k -> create());
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        Objective o = sb.getObjective("duelforge");
        if (o == null) return;

        UUID id = p.getUniqueId();
        boolean spectating = m.spectators.contains(id);
        String type = spectating ? "spectator" : (m.ffa ? "ffa" : (m.teamSize > 1 ? "team" : "duel"));
        List<String> template = plugin.messages().rawList("scoreboard.lines." + type);
        Messages ms = plugin.messages();

        String opponent = "-";
        int allies = 0, enemies = 0, total = 0;
        for (UUID u : m.players) {
            if (!m.left.contains(u)) total++;
            if (u.equals(id)) continue;
            if (!m.ffa && m.isAlive(u)) {
                if (m.sameTeam(id, u)) allies++;
                else enemies++;
            }
            if (!m.ffa && !m.sameTeam(id, u) && m.teamSize == 1) {
                Player op = Bukkit.getPlayer(u);
                opponent = op != null ? op.getName() : "?";
            }
        }
        String mode = m.ffa ? "FFA" : (m.teamSize > 1 ? m.teamSize + "v" + m.teamSize : ms.get(m.ranked ? "request.ranked" : "request.unranked"));
        List<String> scores = new ArrayList<>();
        for (int t = 0; t < m.teamCount; t++) scores.add(String.valueOf(m.score.getOrDefault(t, 0)));

        Map<String, String> ph = new HashMap<>();
        ph.put("kit", Fmt.color(m.kit.displayName));
        ph.put("arena", m.arenaName());
        ph.put("mode", mode);
        ph.put("time", timeOf(m));
        ph.put("opponent", opponent);
        ph.put("kills", String.valueOf(m.kills(id)));
        ph.put("deaths", String.valueOf(m.deaths(id)));
        ph.put("lives", String.valueOf(Math.max(0, m.livesLeft.getOrDefault(id, 0))));
        ph.put("alive", String.valueOf(m.alivePlayers().size()));
        ph.put("total", String.valueOf(total));
        ph.put("allies", String.valueOf(allies));
        ph.put("enemies", String.valueOf(enemies));
        ph.put("round", String.valueOf(m.round));
        ph.put("bo", String.valueOf(m.bestOf));
        ph.put("score", String.join("-", scores));
        ph.put("round_line", m.bestOf > 1 && !m.ffa ? Messages.apply(ms.raw("scoreboard.round-line"), ph) : "");

        List<String> lines = new ArrayList<>();
        for (String t : template) {
            String text = Fmt.color(Messages.apply(t, ph));
            if (text.isEmpty()) continue;
            lines.add(text.length() > 64 ? text.substring(0, 64) : text);
            if (lines.size() >= MAX_LINES) break;
        }
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entry(i);
            Team t = sb.getTeam("l" + i);
            if (t == null) continue;
            if (i < lines.size()) {
                t.setPrefix(lines.get(i));
                o.getScore(entry).setScore(lines.size() - i);
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
