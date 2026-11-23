package dev.duelforge.ui;

import dev.duelforge.DuelForge;
import dev.duelforge.kit.Kit;
import dev.duelforge.stats.StatsManager.PlayerStats;
import dev.duelforge.util.Fmt;
import dev.duelforge.util.Persist;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Floating leaderboard holograms (TextDisplay) for wins, kills, Elo, season stats and per-kit wins. */
public final class LeaderboardManager {

    private static final class Board {
        final String id;
        final String world;
        final double x, y, z;
        final String metric;
        UUID entity;

        Board(String id, String world, double x, double y, double z, String metric) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.metric = metric;
        }
    }

    private final DuelForge plugin;
    private final List<Board> boards = new ArrayList<>();
    private final NamespacedKey key;
    private final File file;
    private BukkitTask task;

    public LeaderboardManager(DuelForge plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "board");
        this.file = new File(plugin.getDataFolder(), "boards.yml");
    }

    public void load() {
        boards.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("boards");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Board b = new Board(id, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), s.getString("metric", "wins"));
            String e = s.getString("entity", "");
            if (!e.isEmpty()) {
                try {
                    b.entity = UUID.fromString(e);
                } catch (IllegalArgumentException ignored) {
                }
            }
            boards.add(b);
        }
    }

    private void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (Board b : boards) {
            String p = "boards." + b.id + ".";
            c.set(p + "world", b.world);
            c.set(p + "x", b.x);
            c.set(p + "y", b.y);
            c.set(p + "z", b.z);
            c.set(p + "metric", b.metric);
            c.set(p + "entity", b.entity == null ? "" : b.entity.toString());
        }
        Persist.save(plugin, file, c, true);
    }

    public void start() {
        long every = 20L * Math.max(10, plugin.getConfig().getInt("leaderboards.refresh-seconds", 30));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 60L, every);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public boolean validMetric(String metric) {
        if (metric.startsWith("kit:")) return plugin.kits().get(metric.substring(4)) != null;
        return List.of("wins", "kills", "elo", "season_wins", "season_kills").contains(metric);
    }

    public int count() {
        return boards.size();
    }

    public void create(Player p, String metric) {
        Location l = p.getLocation().add(0, 2.2, 0);
        Board b = new Board("b" + (System.currentTimeMillis() % 1_000_000L), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), metric);
        boards.add(b);
        refresh(b);
        save();
    }

    public boolean removeNearest(Player p, double radius) {
        Board best = null;
        double bd = radius * radius;
        for (Board b : boards) {
            if (!b.world.equals(p.getWorld().getName())) continue;
            double d = p.getLocation().distanceSquared(new Location(p.getWorld(), b.x, b.y, b.z));
            if (d <= bd) {
                bd = d;
                best = b;
            }
        }
        if (best == null) return false;
        World w = Bukkit.getWorld(best.world);
        TextDisplay td = w == null ? null : find(best, w, new Location(w, best.x, best.y, best.z));
        if (td != null) td.remove();
        boards.remove(best);
        save();
        return true;
    }

    public void refreshAll() {
        for (Iterator<Board> it = boards.iterator(); it.hasNext(); ) {
            Board b = it.next();
            try {
                refresh(b);
            } catch (Exception ex) {
                plugin.getLogger().warning("Leaderboard refresh failed: " + ex.getMessage());
            }
        }
    }

    private TextDisplay find(Board b, World w, Location loc) {
        if (b.entity != null) {
            Entity e = Bukkit.getEntity(b.entity);
            if (e instanceof TextDisplay t && t.isValid()) return t;
        }
        for (Entity e : w.getNearbyEntities(loc, 1.5, 1.5, 1.5)) {
            if (e instanceof TextDisplay t && b.id.equals(t.getPersistentDataContainer().get(key, PersistentDataType.STRING))) {
                b.entity = t.getUniqueId();
                return t;
            }
        }
        return null;
    }

    private void refresh(Board b) {
        World w = Bukkit.getWorld(b.world);
        if (w == null) return;
        Location loc = new Location(w, b.x, b.y, b.z);
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
        TextDisplay td = find(b, w, loc);
        if (td == null) {
            td = w.spawn(loc, TextDisplay.class);
            td.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.id);
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            b.entity = td.getUniqueId();
            save();
        }
        td.setText(text(b.metric));
    }

    private String title(String metric) {
        if (metric.startsWith("kit:")) {
            Kit k = plugin.kits().get(metric.substring(4));
            return "&6&lTop Wins &8- " + (k == null ? metric : k.displayName);
        }
        return switch (metric) {
            case "kills" -> "&6&lTop Kills";
            case "elo" -> "&6&lTop Elo";
            case "season_wins" -> "&6&lSeason " + plugin.stats().season() + " &8- &6&lWins";
            case "season_kills" -> "&6&lSeason " + plugin.stats().season() + " &8- &6&lKills";
            default -> "&6&lTop Wins";
        };
    }

    private String text(String metric) {
        StringBuilder sb = new StringBuilder(Fmt.color(title(metric)));
        List<PlayerStats> top = plugin.stats().top(metric, 10);
        if (top.isEmpty()) sb.append("\n").append(Fmt.color("&7No data yet"));
        for (int i = 0; i < top.size(); i++) {
            PlayerStats s = top.get(i);
            sb.append("\n").append(Fmt.color("&e#" + (i + 1) + " &f" + s.name + " &8- &6" + plugin.stats().value(s, metric)));
        }
        return sb.toString();
    }
}
