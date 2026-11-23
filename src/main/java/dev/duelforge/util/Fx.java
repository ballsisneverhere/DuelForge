package dev.duelforge.util;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Sounds, particles and fireworks. Names are resolved at runtime so one jar works across versions. */
public final class Fx {
    private static final Map<String, Particle> CACHE = new HashMap<>();
    private static final String[][] ALIASES = {
            {"TOTEM_OF_UNDYING", "TOTEM"}, {"SMOKE", "SMOKE_NORMAL"}, {"LARGE_SMOKE", "SMOKE_LARGE"},
            {"FIREWORK", "FIREWORKS_SPARK"}, {"HAPPY_VILLAGER", "VILLAGER_HAPPY"},
            {"ANGRY_VILLAGER", "VILLAGER_ANGRY"}, {"ENCHANTED_HIT", "CRIT_MAGIC"}, {"ELECTRIC_SPARK", "CRIT_MAGIC"}, {"FLAME", "FLAME"}, {"EXPLOSION", "EXPLOSION_LARGE"}, {"POOF", "EXPLOSION_NORMAL"}
    };

    private Fx() {}

    public static Particle particle(String name) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase("none")) return null;
        String key = name.toUpperCase();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Particle found = null;
        List<String> tries = new ArrayList<>();
        tries.add(key);
        for (String[] a : ALIASES) {
            if (a[0].equals(key)) tries.add(a[1]);
            if (a[1].equals(key)) tries.add(a[0]);
        }
        for (String t : tries) {
            try {
                found = Particle.valueOf(t);
                break;
            } catch (IllegalArgumentException ignored) {
            }
        }
        CACHE.put(key, found);
        return found;
    }

    public static void particle(Location loc, String name, int count, double spread) {
        Particle p = particle(name);
        if (p == null || loc == null || loc.getWorld() == null) return;
        try {
            loc.getWorld().spawnParticle(p, loc, count, spread, spread, spread, 0.02);
        } catch (IllegalArgumentException ignored) {
            // particle requires extra data; skip
        }
    }

    public static void sound(Player p, String key, float volume, float pitch) {
        if (p == null || key == null || key.isEmpty()) return;
        p.playSound(p.getLocation(), key, volume, pitch);
    }

    public static void sound(Location loc, String key, float volume, float pitch) {
        if (loc == null || loc.getWorld() == null || key == null || key.isEmpty()) return;
        loc.getWorld().playSound(loc, key, volume, pitch);
    }

    public static Color hex(String s, Color def) {
        try {
            String h = s.startsWith("#") ? s.substring(1) : s;
            return Color.fromRGB(Integer.parseInt(h, 16));
        } catch (Exception e) {
            return def;
        }
    }

    public static void firework(Location loc, List<String> colors) {
        if (loc == null || loc.getWorld() == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Color c1 = Color.YELLOW, c2 = Color.RED;
        if (colors != null && !colors.isEmpty()) {
            c1 = hex(colors.get(r.nextInt(colors.size())), c1);
            c2 = hex(colors.get(r.nextInt(colors.size())), c2);
        }
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta fm = fw.getFireworkMeta();
        fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                .withColor(c1).withFade(c2).flicker(true).trail(true).build());
        fm.setPower(0);
        fw.setFireworkMeta(fm);
        fw.setSilent(true);
        fw.detonate();
    }
}
