package dev.duelforge.kit;

import dev.duelforge.DuelForge;
import dev.duelforge.match.Match;
import dev.duelforge.util.Fx;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes kit abilities (right-click items) with configurable cooldowns and values. */
public final class AbilityManager {
    private final DuelForge plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public AbilityManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    public String name(String id) {
        return plugin.getConfig().getString("abilities." + id + ".name", id);
    }

    public String description(String id) {
        return plugin.getConfig().getString("abilities." + id + ".description", "");
    }

    public int cooldown(String id) {
        return plugin.getConfig().getInt("abilities." + id + ".cooldown", 10);
    }

    public void clear(UUID id) {
        cooldowns.remove(id);
    }

    /** Returns true if the item click was handled (so the caller should cancel the interact). */
    public boolean use(Player p, Match m, String id, ItemStack item) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("abilities." + id);
        if (s == null) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> map = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        Long until = map.get(id);
        if (until != null && until > now) {
            long secs = (until - now + 999) / 1000;
            plugin.messages().actionBar(p, plugin.messages().get("ability.cooldown", "ability", name(id), "seconds", secs));
            return true;
        }
        boolean ok = switch (id) {
            case "dash" -> dash(p, s);
            case "smoke_bomb" -> smoke(p, m, s);
            case "slam" -> slam(p, m, s);
            case "leap" -> leap(p, s);
            case "berserk" -> berserk(p, s);
            case "thunder" -> thunder(p, m, s);
            case "golden_head" -> goldenHead(p, s);
            default -> false;
        };
        if (!ok) return true;
        int cd = s.getInt("cooldown", 10);
        if (cd > 0) {
            map.put(id, now + cd * 1000L);
            p.setCooldown(item.getType(), cd * 20);
        }
        if (s.getBoolean("consume", false)) {
            int left = item.getAmount() - 1;
            if (left <= 0) p.getInventory().setItemInMainHand(null);
            else {
                ItemStack c = item.clone();
                c.setAmount(left);
                p.getInventory().setItemInMainHand(c);
            }
        }
        return true;
    }

    private void effect(Player p, String type, int seconds, int level) {
        PotionEffectType t = KitManager.effectType(type);
        if (t != null) p.addPotionEffect(new PotionEffect(t, Math.max(1, seconds * 20), Math.max(0, level - 1), false, true, true));
    }

    private boolean dash(Player p, ConfigurationSection s) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(s.getDouble("power", 1.9)).setY(s.getDouble("lift", 0.25));
        p.setVelocity(dir);
        Fx.particle(p.getLocation().add(0, 0.5, 0), "CLOUD", 25, 0.3);
        Fx.sound(p.getLocation(), s.getString("sound", ""), 1f, 1.2f);
        return true;
    }

    private boolean smoke(Player p, Match m, ConfigurationSection s) {
        double r = s.getDouble("radius", 5);
        for (Player e : m.aliveOthers(p)) {
            if (e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                effect(e, "blindness", s.getInt("blind-seconds", 3), 1);
            }
        }
        effect(p, "invisibility", s.getInt("invisible-seconds", 4), 1);
        effect(p, "speed", s.getInt("speed-seconds", 5), 3);
        Fx.particle(p.getLocation().add(0, 1, 0), "LARGE_SMOKE", 60, 1.2);
        Fx.sound(p.getLocation(), "entity.generic.extinguish_fire", 1f, 0.8f);
        return true;
    }

    private boolean slam(Player p, Match m, ConfigurationSection s) {
        double r = s.getDouble("radius", 5);
        for (Player e : m.aliveOthers(p)) {
            if (!e.getWorld().equals(p.getWorld()) || e.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            Vector push = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (push.lengthSquared() < 1.0E-4) push = new Vector(1, 0, 0);
            push.normalize().multiply(s.getDouble("knockback", 1.1)).setY(s.getDouble("lift", 0.6));
            e.setVelocity(push);
            effect(e, "slowness", s.getInt("slow-seconds", 2), 2);
            e.damage(s.getDouble("damage", 4), p);
        }
        Fx.particle(p.getLocation().add(0, 0.2, 0), "EXPLOSION", 3, 1.5);
        Fx.particle(p.getLocation().add(0, 0.2, 0), "CLOUD", 40, 1.5);
        Fx.sound(p.getLocation(), "block.anvil.land", 1f, 0.8f);
        return true;
    }

    private boolean leap(Player p, ConfigurationSection s) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(-s.getDouble("power", 1.3)).setY(s.getDouble("lift", 0.6));
        p.setVelocity(dir);
        Fx.particle(p.getLocation().add(0, 0.3, 0), "CLOUD", 20, 0.3);
        Fx.sound(p.getLocation(), "entity.rabbit.jump", 1f, 1f);
        return true;
    }

    private boolean berserk(Player p, ConfigurationSection s) {
        int sec = s.getInt("seconds", 6);
        effect(p, "strength", sec, s.getInt("strength-level", 2));
        effect(p, "speed", sec, s.getInt("speed-level", 1));
        Fx.particle(p.getLocation().add(0, 1, 0), "ANGRY_VILLAGER", 8, 0.5);
        Fx.sound(p.getLocation(), "entity.ravager.roar", 0.8f, 1.3f);
        return true;
    }

    private boolean thunder(Player p, Match m, ConfigurationSection s) {
        double range = s.getDouble("range", 20);
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        Location eye = p.getEyeLocation();
        for (Player e : m.aliveOthers(p)) {
            if (!e.getWorld().equals(p.getWorld())) continue;
            Vector to = e.getEyeLocation().toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.5) continue;
            if (to.normalize().dot(eye.getDirection()) > 0.96 && dist < bestDist && p.hasLineOfSight(e)) {
                best = e;
                bestDist = dist;
            }
        }
        if (best == null) {
            plugin.messages().actionBar(p, plugin.messages().get("title.no-target"));
            return false;
        }
        best.getWorld().strikeLightningEffect(best.getLocation());
        best.damage(s.getDouble("damage", 6), p);
        Fx.sound(p.getLocation(), "entity.lightning_bolt.thunder", 0.6f, 1.2f);
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean goldenHead(Player p, ConfigurationSection s) {
        double max = p.getMaxHealth();
        p.setHealth(Math.min(max, p.getHealth() + s.getDouble("heal", 8)));
        effect(p, "absorption", s.getInt("absorption-seconds", 60), s.getInt("absorption-level", 2));
        effect(p, "regeneration", s.getInt("regeneration-seconds", 5), 2);
        Fx.particle(p.getLocation().add(0, 1, 0), "HEART", 6, 0.5);
        Fx.sound(p.getLocation(), "entity.generic.eat", 1f, 1f);
        return true;
    }

    /** Placeholder used by tab completion / docs. */
    public List<String> ids() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("abilities");
        return s == null ? List.of() : List.copyOf(s.getKeys(false));
    }
}
