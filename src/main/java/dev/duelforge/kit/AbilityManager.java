package dev.duelforge.kit;

import dev.duelforge.DuelForge;
import dev.duelforge.match.Match;
import dev.duelforge.util.Fx;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Executes kit abilities (right-click items) with configurable cooldowns and values. */
public final class AbilityManager {
    private final DuelForge plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public AbilityManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    /** Global switch: with abilities-enabled: false every ability item is removed from kits. */
    public boolean enabled() {
        return plugin.getConfig().getBoolean("abilities-enabled", true);
    }

    public String name(String id) {
        return plugin.getConfig().getString("abilities." + id + ".name", id);
    }

    public String description(String id) {
        return plugin.getConfig().getString("abilities." + id + ".description", "");
    }

    public double cooldown(String id) {
        return plugin.getConfig().getDouble("abilities." + id + ".cooldown", 10);
    }

    public String cooldownText(String id) {
        double c = cooldown(id);
        return c == Math.floor(c) ? String.valueOf((int) c) : String.valueOf(c);
    }

    public void clear(UUID id) {
        cooldowns.remove(id);
    }

    /** Returns true if the item click was handled (so the caller should cancel the interact). */
    public boolean use(Player p, Match m, String id, ItemStack item) {
        if (!enabled()) return true;
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
            case "soup" -> soup(p, s);
            case "grapple" -> grapple(p, m, s);
            case "frost" -> frost(p, m, s);
            case "blink" -> blink(p, m, s);
            case "adrenaline" -> adrenaline(p, s);
            case "shadowstep" -> shadowstep(p, m, s);
            case "firebolt" -> firebolt(p, m, s);
            case "launch" -> launch(p, s);
            case "barrier" -> barrier(p, s);
            default -> false;
        };
        if (!ok) return true;
        double cd = s.getDouble("cooldown", 10);
        if (cd > 0) {
            map.put(id, now + (long) (cd * 1000));
            p.setCooldown(item.getType(), (int) Math.ceil(cd * 20));
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

    // ---------------------------------------------------------------- helpers

    private void effect(Player p, String type, double seconds, int level) {
        PotionEffectType t = KitManager.effectType(type);
        if (t == null || seconds <= 0) return;
        int ticks = Math.max(1, (int) Math.round(seconds * 20));
        p.addPotionEffect(new PotionEffect(t, ticks, Math.max(0, level - 1), false, true, true));
    }

    @SuppressWarnings("deprecation")
    private void heal(Player p, double amount) {
        p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + amount));
    }

    /** Closest living enemy inside a narrow cone in front of the player (with line of sight). */
    private Player lookTarget(Player p, Match m, double range, double minDot) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        Location eye = p.getEyeLocation();
        for (Player e : m.enemiesOf(p)) {
            if (!e.getWorld().equals(p.getWorld())) continue;
            Vector to = e.getEyeLocation().toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.5) continue;
            if (to.normalize().dot(eye.getDirection()) > minDot && dist < bestDist && p.hasLineOfSight(e)) {
                best = e;
                bestDist = dist;
            }
        }
        if (best == null) plugin.messages().actionBar(p, plugin.messages().get("title.no-target"));
        return best;
    }

    // ---------------------------------------------------------------- original abilities (nerfed)

    private boolean dash(Player p, ConfigurationSection s) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(s.getDouble("power", 1.0)).setY(s.getDouble("lift", 0.2));
        p.setVelocity(dir);
        Fx.particle(p.getLocation().add(0, 0.5, 0), "CLOUD", 25, 0.3);
        Fx.sound(p.getLocation(), s.getString("sound", ""), 1f, 1.2f);
        return true;
    }

    private boolean smoke(Player p, Match m, ConfigurationSection s) {
        double r = s.getDouble("radius", 3);
        for (Player e : m.enemiesOf(p)) {
            if (e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                effect(e, "blindness", s.getDouble("blind-seconds", 1.5), 1);
            }
        }
        effect(p, "invisibility", s.getDouble("invisible-seconds", 2), 1);
        effect(p, "speed", s.getDouble("speed-seconds", 2.5), 2);
        Fx.particle(p.getLocation().add(0, 1, 0), "LARGE_SMOKE", 60, 1.0);
        Fx.sound(p.getLocation(), "entity.generic.extinguish_fire", 1f, 0.8f);
        return true;
    }

    private boolean slam(Player p, Match m, ConfigurationSection s) {
        double r = s.getDouble("radius", 3.5);
        for (Player e : m.enemiesOf(p)) {
            if (!e.getWorld().equals(p.getWorld()) || e.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            Vector push = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (push.lengthSquared() < 1.0E-4) push = new Vector(1, 0, 0);
            push.normalize().multiply(s.getDouble("knockback", 0.6)).setY(s.getDouble("lift", 0.35));
            e.setVelocity(push);
            effect(e, "slowness", s.getDouble("slow-seconds", 1), 2);
            e.damage(s.getDouble("damage", 2), p);
        }
        Fx.particle(p.getLocation().add(0, 0.2, 0), "EXPLOSION", 3, 1.2);
        Fx.particle(p.getLocation().add(0, 0.2, 0), "CLOUD", 40, 1.2);
        Fx.sound(p.getLocation(), "block.anvil.land", 1f, 0.8f);
        return true;
    }

    private boolean leap(Player p, ConfigurationSection s) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(-s.getDouble("power", 0.7)).setY(s.getDouble("lift", 0.4));
        p.setVelocity(dir);
        Fx.particle(p.getLocation().add(0, 0.3, 0), "CLOUD", 20, 0.3);
        Fx.sound(p.getLocation(), "entity.rabbit.jump", 1f, 1f);
        return true;
    }

    private boolean berserk(Player p, ConfigurationSection s) {
        double sec = s.getDouble("seconds", 3);
        effect(p, "strength", sec, s.getInt("strength-level", 1));
        effect(p, "speed", sec, s.getInt("speed-level", 1));
        Fx.particle(p.getLocation().add(0, 1, 0), "ANGRY_VILLAGER", 8, 0.5);
        Fx.sound(p.getLocation(), "entity.ravager.roar", 0.8f, 1.3f);
        return true;
    }

    private boolean thunder(Player p, Match m, ConfigurationSection s) {
        Player t = lookTarget(p, m, s.getDouble("range", 12), 0.96);
        if (t == null) return false;
        t.getWorld().strikeLightningEffect(t.getLocation());
        t.damage(s.getDouble("damage", 3), p);
        Fx.sound(p.getLocation(), "entity.lightning_bolt.thunder", 0.6f, 1.2f);
        return true;
    }

    private boolean goldenHead(Player p, ConfigurationSection s) {
        heal(p, s.getDouble("heal", 4));
        effect(p, "absorption", s.getDouble("absorption-seconds", 30), s.getInt("absorption-level", 1));
        effect(p, "regeneration", s.getDouble("regeneration-seconds", 3), 2);
        Fx.particle(p.getLocation().add(0, 1, 0), "HEART", 6, 0.5);
        Fx.sound(p.getLocation(), "entity.generic.eat", 1f, 1f);
        return true;
    }

    // ---------------------------------------------------------------- new abilities

    private boolean soup(Player p, ConfigurationSection s) {
        if (p.getHealth() >= p.getMaxHealth() - 0.01) return false; // don't waste a bowl at full health
        heal(p, s.getDouble("heal", 3.5));
        Fx.particle(p.getLocation().add(0, 1, 0), "HEART", 3, 0.4);
        Fx.sound(p.getLocation(), "entity.generic.drink", 1f, 1f);
        return true;
    }

    private boolean grapple(Player p, Match m, ConfigurationSection s) {
        Player t = lookTarget(p, m, s.getDouble("range", 10), 0.93);
        if (t == null) return false;
        Vector pull = p.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
        if (pull.lengthSquared() < 1.0E-4) return false;
        pull.normalize().multiply(s.getDouble("pull", 0.8)).setY(s.getDouble("lift", 0.25));
        t.setVelocity(pull);
        Fx.sound(p.getLocation(), "entity.fishing_bobber.retrieve", 1f, 0.9f);
        return true;
    }

    private boolean frost(Player p, Match m, ConfigurationSection s) {
        double r = s.getDouble("radius", 3);
        for (Player e : m.enemiesOf(p)) {
            if (e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                effect(e, "slowness", s.getDouble("slow-seconds", 2), s.getInt("slow-level", 2));
            }
        }
        Fx.particle(p.getLocation().add(0, 1, 0), "SNOWFLAKE", 50, 1.2);
        Fx.sound(p.getLocation(), "block.glass.break", 1f, 1.4f);
        return true;
    }

    private boolean blink(Player p, Match m, ConfigurationSection s) {
        double range = s.getDouble("range", 5);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double dist = r == null ? range : Math.max(0, r.getHitPosition().distance(eye.toVector()) - 0.8);
        Location start = p.getLocation();
        for (double d = dist; d >= 1.5; d -= 0.5) {
            Location dest = start.clone().add(dir.clone().multiply(d));
            if (dest.getBlock().isPassable() && dest.clone().add(0, 1, 0).getBlock().isPassable()
                    && (m.arena == null || m.arena.contains(dest))) {
                Fx.particle(start.clone().add(0, 1, 0), "PORTAL", 30, 0.4);
                plugin.matches().tp(p, dest);
                Fx.particle(dest.clone().add(0, 1, 0), "PORTAL", 30, 0.4);
                Fx.sound(dest, "entity.enderman.teleport", 0.8f, 1.2f);
                return true;
            }
        }
        plugin.messages().actionBar(p, plugin.messages().get("ability.blocked"));
        return false;
    }

    private boolean adrenaline(Player p, ConfigurationSection s) {
        effect(p, "speed", s.getDouble("speed-seconds", 3), s.getInt("speed-level", 1));
        effect(p, "regeneration", s.getDouble("regen-seconds", 2), 1);
        Fx.particle(p.getLocation().add(0, 1, 0), "HAPPY_VILLAGER", 8, 0.5);
        Fx.sound(p.getLocation(), "entity.player.breath", 1f, 1.2f);
        return true;
    }

    private boolean shadowstep(Player p, Match m, ConfigurationSection s) {
        Player t = lookTarget(p, m, s.getDouble("range", 8), 0.6);
        if (t == null) return false;
        Vector facing = t.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) facing = new Vector(0, 0, 1);
        facing.normalize();
        Location dest = t.getLocation().clone().subtract(facing.clone().multiply(s.getDouble("behind", 1.5)));
        dest.setDirection(facing);
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) dest = t.getLocation().clone();
        if (m.arena != null && !m.arena.contains(dest)) return false;
        Fx.particle(p.getLocation().add(0, 1, 0), "LARGE_SMOKE", 25, 0.4);
        plugin.matches().tp(p, dest);
        Fx.particle(dest.clone().add(0, 1, 0), "LARGE_SMOKE", 25, 0.4);
        Fx.sound(dest, "entity.enderman.teleport", 0.8f, 0.8f);
        return true;
    }

    private boolean firebolt(Player p, Match m, ConfigurationSection s) {
        Player t = lookTarget(p, m, s.getDouble("range", 10), 0.96);
        if (t == null) return false;
        Location from = p.getEyeLocation();
        Vector step = t.getEyeLocation().toVector().subtract(from.toVector());
        double len = step.length();
        step.normalize();
        for (double d = 0.5; d < len; d += 0.6) Fx.particle(from.clone().add(step.clone().multiply(d)), "FLAME", 1, 0.03);
        t.damage(s.getDouble("damage", 2), p);
        t.setFireTicks((int) Math.round(s.getDouble("fire-seconds", 2) * 20));
        Fx.sound(p.getLocation(), "entity.blaze.shoot", 1f, 1f);
        return true;
    }

    private boolean launch(Player p, ConfigurationSection s) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 0);
        else dir.normalize().multiply(s.getDouble("forward", 0.35));
        dir.setY(s.getDouble("up", 0.9));
        p.setVelocity(dir);
        effect(p, "slow_falling", s.getDouble("slow-fall-seconds", 3), 1);
        Fx.particle(p.getLocation(), "CLOUD", 30, 0.3);
        Fx.sound(p.getLocation(), "entity.firework_rocket.launch", 1f, 1f);
        return true;
    }

    private boolean barrier(Player p, ConfigurationSection s) {
        effect(p, "resistance", s.getDouble("resistance-seconds", 3), s.getInt("resistance-level", 1));
        effect(p, "absorption", s.getDouble("absorption-seconds", 6), s.getInt("absorption-level", 1));
        Fx.particle(p.getLocation().add(0, 1, 0), "CRIT", 20, 0.6);
        Fx.sound(p.getLocation(), "item.shield.block", 1f, 0.9f);
        return true;
    }
}
