package dev.duelforge.stats;

import dev.duelforge.DuelForge;
import dev.duelforge.util.Persist;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent player statistics (stats.yml), saved atomically and asynchronously. */
public final class StatsManager {

    public static final class PlayerStats {
        public final UUID id;
        public String name;
        public int wins, losses, kills, deaths, killStreak, bestKillStreak, winStreak, bestWinStreak;
        public int elo, rankedWins, rankedLosses, matches;

        PlayerStats(UUID id, String name, int elo) {
            this.id = id;
            this.name = name;
            this.elo = elo;
        }

        public double kd() {
            return deaths == 0 ? kills : Math.round(kills * 100.0 / deaths) / 100.0;
        }

        public double wl() {
            return losses == 0 ? wins : Math.round(wins * 100.0 / losses) / 100.0;
        }
    }

    private final DuelForge plugin;
    private final Map<UUID, PlayerStats> map = new HashMap<>();
    private final File file;
    private boolean dirty;
    private BukkitTask autosave;

    public StatsManager(DuelForge plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    private int startElo() {
        return plugin.getConfig().getInt("elo.starting", 1000);
    }

    public void load() {
        map.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = c.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID id = UUID.fromString(key);
                PlayerStats st = new PlayerStats(id, s.getString("name", "Unknown"), s.getInt("elo", startElo()));
                st.wins = s.getInt("wins");
                st.losses = s.getInt("losses");
                st.kills = s.getInt("kills");
                st.deaths = s.getInt("deaths");
                st.killStreak = s.getInt("kill-streak");
                st.bestKillStreak = s.getInt("best-kill-streak");
                st.winStreak = s.getInt("win-streak");
                st.bestWinStreak = s.getInt("best-win-streak");
                st.rankedWins = s.getInt("ranked-wins");
                st.rankedLosses = s.getInt("ranked-losses");
                st.matches = s.getInt("matches");
                map.put(id, st);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void startAutosave() {
        autosave = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) save(true);
        }, 20L * 300, 20L * 300);
    }

    public void stop() {
        if (autosave != null) autosave.cancel();
        save(false);
    }

    public PlayerStats get(UUID id, String name) {
        PlayerStats s = map.computeIfAbsent(id, k -> new PlayerStats(k, name == null ? "Unknown" : name, startElo()));
        if (name != null && !name.equals(s.name)) s.name = name;
        return s;
    }

    public PlayerStats find(String name) {
        for (PlayerStats s : map.values()) if (s.name.equalsIgnoreCase(name)) return s;
        return null;
    }

    public void reset(UUID id) {
        map.remove(id);
        dirty = true;
        save(true);
    }

    public void markDirty() {
        dirty = true;
    }

    public List<PlayerStats> top(String metric, int n) {
        Comparator<PlayerStats> cmp = switch (metric) {
            case "kills" -> Comparator.comparingInt(s -> s.kills);
            case "elo" -> Comparator.comparingInt(s -> s.elo);
            default -> Comparator.comparingInt(s -> s.wins);
        };
        List<PlayerStats> all = new ArrayList<>(map.values());
        all.removeIf(s -> s.matches == 0);
        all.sort(cmp.reversed());
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    public void save(boolean async) {
        YamlConfiguration c = new YamlConfiguration();
        for (PlayerStats s : map.values()) {
            String p = "players." + s.id + ".";
            c.set(p + "name", s.name);
            c.set(p + "wins", s.wins);
            c.set(p + "losses", s.losses);
            c.set(p + "kills", s.kills);
            c.set(p + "deaths", s.deaths);
            c.set(p + "kill-streak", s.killStreak);
            c.set(p + "best-kill-streak", s.bestKillStreak);
            c.set(p + "win-streak", s.winStreak);
            c.set(p + "best-win-streak", s.bestWinStreak);
            c.set(p + "elo", s.elo);
            c.set(p + "ranked-wins", s.rankedWins);
            c.set(p + "ranked-losses", s.rankedLosses);
            c.set(p + "matches", s.matches);
        }
        dirty = false;
        Persist.save(plugin, file, c, async);
    }
}
