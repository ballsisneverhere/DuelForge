package dev.duelforge.arena;

import dev.duelforge.DuelForge;
import dev.duelforge.match.Match;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds a fresh map for every match in a dedicated void world and wipes it afterwards.
 * The world is deliberately NOT registered for auto-loading: its folder is deleted on the next start,
 * so nothing can ever be left over from a crash.
 */
public final class InstanceManager {
    private static final class Inst {
        final int slot;
        final List<ArenaBuilder.Op> ops;

        Inst(int slot, List<ArenaBuilder.Op> ops) {
            this.slot = slot;
            this.ops = ops;
        }
    }

    private final DuelForge plugin;
    private World world;
    private final Map<Arena, Inst> active = new HashMap<>();
    private final Set<Integer> used = new HashSet<>();

    public InstanceManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("matches.auto-build", true);
    }

    private String worldName() {
        return plugin.getConfig().getString("matches.world", "duelforge_matches");
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    public World world() {
        if (world != null && Bukkit.getWorld(world.getName()) != null) return world;
        String name = worldName();
        World w = Bukkit.getWorld(name);
        if (w == null) {
            File folder = new File(Bukkit.getWorldContainer(), name);
            if (folder.exists()) delete(folder); // leftovers from the last run
            try {
                w = plugin.buildWorld(name, "void");
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not create the match world: " + ex.getMessage());
                return null;
            }
        }
        world = w;
        return w;
    }

    public void ensureWorld() {
        if (enabled()) world();
    }

    /** Reserves a slot, builds the map and calls {@code ready} when it is finished (or {@code fail}). */
    public void acquire(ArenaBuilder.Theme theme, Consumer<Arena> ready, Runnable fail) {
        World w = world();
        if (w == null) {
            fail.run();
            return;
        }
        int max = Math.max(1, plugin.getConfig().getInt("matches.max-instances", 16));
        int slot = -1;
        for (int i = 0; i < max; i++) {
            if (!used.contains(i)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            fail.run();
            return;
        }
        used.add(slot);
        int spacing = Math.max(100, plugin.getConfig().getInt("matches.slot-spacing", 160));
        int cx = (slot % 8) * spacing, cz = (slot / 8) * spacing;
        ArenaBuilder.Plan plan = ArenaBuilder.plan(theme, cx, 64, cz, false);
        Arena a = ArenaBuilder.instantiate("match-" + slot, w, plan);
        active.put(a, new Inst(slot, plan.ops));
        ArenaBuilder.apply(plugin, w, plan.ops, () -> ready.accept(a));
    }

    /** Wipes the map (and anything players built in it) and frees the slot. */
    public void release(Arena a, Match m) {
        Inst inst = active.remove(a);
        if (inst == null) return;
        World w = a.world();
        if (w == null) {
            used.remove(inst.slot);
            return;
        }
        plugin.arenas().clean(a);
        List<ArenaBuilder.Op> wipe = new ArrayList<>(inst.ops.size());
        for (ArenaBuilder.Op o : inst.ops) wipe.add(new ArenaBuilder.Op(o.x(), o.y(), o.z(), Material.AIR));
        if (m != null) {
            for (BlockState s : m.changed.values()) wipe.add(new ArenaBuilder.Op(s.getX(), s.getY(), s.getZ(), Material.AIR));
            m.changed.clear();
        }
        ArenaBuilder.apply(plugin, w, wipe, () -> used.remove(inst.slot));
    }
}
