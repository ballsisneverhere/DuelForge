package dev.duelforge.arena;

import dev.duelforge.DuelForge;
import dev.duelforge.match.Match;
import dev.duelforge.util.Persist;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Loads/saves arenas and restores them after matches. */
public final class ArenaManager {
    private final DuelForge plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final File file;

    public ArenaManager(DuelForge plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        arenas.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = c.getConfigurationSection("arenas");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection a = sec.getConfigurationSection(key);
            if (a == null) continue;
            try {
                Arena ar = new Arena(key.toLowerCase());
                ar.displayName = a.getString("display-name", key);
                Material icon = Material.matchMaterial(a.getString("icon", "GRASS_BLOCK"));
                ar.icon = icon != null ? icon : Material.GRASS_BLOCK;
                ar.worldName = a.getString("world", "world");
                List<Integer> min = a.getIntegerList("min"), max = a.getIntegerList("max");
                if (min.size() == 3 && max.size() == 3) {
                    ar.p1 = new int[]{min.get(0), min.get(1), min.get(2)};
                    ar.p2 = new int[]{max.get(0), max.get(1), max.get(2)};
                    ar.recalc();
                }
                for (String s : a.getStringList("spawns")) ar.spawns.add(Arena.Pos.parse(s));
                String spec = a.getString("spectator");
                if (spec != null && !spec.isEmpty()) ar.spectator = Arena.Pos.parse(spec);
                ar.enabled = a.getBoolean("enabled", true);
                ar.allowBuilding = a.getBoolean("allow-building", true);
                arenas.put(ar.name, ar);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load arena '" + key + "': " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + arenas.size() + " arenas.");
    }

    public void save(boolean async) {
        YamlConfiguration c = new YamlConfiguration();
        c.createSection("arenas");
        for (Arena a : arenas.values()) {
            String p = "arenas." + a.name + ".";
            c.set(p + "display-name", a.displayName);
            c.set(p + "icon", a.icon.name());
            c.set(p + "world", a.worldName);
            if (a.boundsSet) {
                c.set(p + "min", List.of(a.minX, a.minY, a.minZ));
                c.set(p + "max", List.of(a.maxX, a.maxY, a.maxZ));
            }
            List<String> sp = new ArrayList<>();
            for (Arena.Pos s : a.spawns) sp.add(s.toString());
            c.set(p + "spawns", sp);
            if (a.spectator != null) c.set(p + "spectator", a.spectator.toString());
            c.set(p + "enabled", a.enabled);
            c.set(p + "allow-building", a.allowBuilding);
        }
        Persist.save(plugin, file, c, async);
    }

    public Arena get(String name) {
        return name == null ? null : arenas.get(name.toLowerCase());
    }

    public Collection<Arena> all() {
        return arenas.values();
    }

    public List<Arena> ready() {
        List<Arena> out = new ArrayList<>();
        for (Arena a : arenas.values()) if (a.ready()) out.add(a);
        return out;
    }

    public Arena create(String name, World world) {
        Arena a = new Arena(name.toLowerCase());
        a.worldName = world.getName();
        arenas.put(a.name, a);
        return a;
    }

    public void delete(String name) {
        arenas.remove(name.toLowerCase());
    }

    public Arena randomFree() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : arenas.values()) if (a.free()) free.add(a);
        return free.isEmpty() ? null : free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    // ---------- restoration ----------

    public void clean(Arena a) {
        World w = a.world();
        if (w == null || !a.boundsSet) return;
        for (Entity e : w.getNearbyEntities(a.box())) {
            if (e instanceof Item || e instanceof Projectile || e instanceof ExperienceOrb
                    || e instanceof AreaEffectCloud || e instanceof TNTPrimed || e instanceof FallingBlock) {
                e.remove();
            }
        }
    }

    /** Restores all changed blocks in small batches, then frees the arena. */
    public void restore(Match m) {
        Arena a = m.arena;
        clean(a);
        List<BlockState> list = new ArrayList<>(m.changed.values());
        m.changed.clear();
        if (list.isEmpty()) {
            a.current = null;
            return;
        }
        final int perTick = Math.max(50, plugin.getConfig().getInt("arena.restore-blocks-per-tick", 400));
        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                int n = 0;
                while (idx < list.size() && n < perTick) {
                    list.get(idx++).update(true, false);
                    n++;
                }
                if (idx >= list.size()) {
                    clean(a);
                    a.current = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Puts every changed block back right now (between rounds). The arena stays reserved. */
    public void restoreBlocksNow(Match m) {
        clean(m.arena);
        for (BlockState s : m.changed.values()) s.update(true, false);
        m.changed.clear();
    }

    /** Synchronous restore, used on shutdown. */
    public void restoreNow(Match m) {
        restoreBlocksNow(m);
        m.arena.current = null;
    }
}
