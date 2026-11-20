package dev.duelforge.match;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything about a player that must be restored after a match. Persisted to disk while the match runs so a
 * crash can never cost a player their items.
 */
public final class PlayerSnapshot {
    private ItemStack[] contents;
    private Location loc;
    private GameMode gameMode;
    private double health;
    private int food, level, fireTicks;
    private float saturation, exp;
    private List<PotionEffect> effects = new ArrayList<>();
    private boolean allowFlight, flying;

    private PlayerSnapshot() {}

    public static PlayerSnapshot capture(Player p) {
        PlayerSnapshot s = new PlayerSnapshot();
        ItemStack[] src = p.getInventory().getContents();
        s.contents = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) s.contents[i] = src[i] == null ? null : src[i].clone();
        s.loc = p.getLocation().clone();
        s.gameMode = p.getGameMode();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.saturation = p.getSaturation();
        s.exp = p.getExp();
        s.level = p.getLevel();
        s.fireTicks = p.getFireTicks();
        s.effects = new ArrayList<>(p.getActivePotionEffects());
        s.allowFlight = p.getAllowFlight();
        s.flying = p.isFlying();
        return s;
    }

    public Location location() {
        return loc;
    }

    @SuppressWarnings("deprecation")
    public void restore(Player p, Location dest, boolean heal) {
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setAbsorptionAmount(0);
        p.setGameMode(gameMode);
        p.getInventory().clear();
        p.getInventory().setContents(contents);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        for (PotionEffect e : effects) p.addPotionEffect(e);
        p.setAllowFlight(allowFlight);
        if (allowFlight) p.setFlying(flying);
        p.setLevel(level);
        p.setExp(exp);
        double max = p.getMaxHealth();
        p.setHealth(heal ? max : Math.min(max, Math.max(1.0, health)));
        p.setFoodLevel(heal ? 20 : food);
        p.setSaturation(heal ? 20f : saturation);
        p.setFireTicks(heal ? 0 : fireTicks);
        p.setVelocity(new org.bukkit.util.Vector());
        if (dest != null) p.teleport(dest);
        p.updateInventory();
    }

    public void save(File f) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("contents", Arrays.asList(contents));
        c.set("loc", loc);
        c.set("gamemode", gameMode.name());
        c.set("health", health);
        c.set("food", food);
        c.set("saturation", (double) saturation);
        c.set("exp", (double) exp);
        c.set("level", level);
        c.set("fire", fireTicks);
        c.set("effects", effects);
        c.set("allow-flight", allowFlight);
        c.set("flying", flying);
        try {
            f.getParentFile().mkdirs();
            c.save(f);
        } catch (Exception ignored) {
        }
    }

    public static PlayerSnapshot load(File f) {
        try {
            YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
            PlayerSnapshot s = new PlayerSnapshot();
            List<?> raw = c.getList("contents");
            s.contents = new ItemStack[raw == null ? 0 : raw.size()];
            for (int i = 0; raw != null && i < raw.size(); i++) s.contents[i] = raw.get(i) instanceof ItemStack it ? it : null;
            s.loc = c.getLocation("loc");
            s.gameMode = GameMode.valueOf(c.getString("gamemode", "SURVIVAL"));
            s.health = c.getDouble("health", 20);
            s.food = c.getInt("food", 20);
            s.saturation = (float) c.getDouble("saturation", 5);
            s.exp = (float) c.getDouble("exp", 0);
            s.level = c.getInt("level", 0);
            s.fireTicks = c.getInt("fire", 0);
            for (Object o : c.getList("effects", new ArrayList<>())) if (o instanceof PotionEffect pe) s.effects.add(pe);
            s.allowFlight = c.getBoolean("allow-flight");
            s.flying = c.getBoolean("flying");
            return s;
        } catch (Exception ex) {
            return null;
        }
    }
}
