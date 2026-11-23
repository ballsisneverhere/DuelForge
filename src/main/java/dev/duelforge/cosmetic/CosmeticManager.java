package dev.duelforge.cosmetic;

import dev.duelforge.DuelForge;
import dev.duelforge.stats.StatsManager.PlayerStats;
import dev.duelforge.util.Fx;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Player-selectable kill effects, victory fireworks and hit particles (defined in config.yml). */
public final class CosmeticManager {
    public static final List<String> TYPES = List.of("kill", "victory", "hit");

    private final DuelForge plugin;

    public CosmeticManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    public static String defaultId(String type) {
        return type.equals("hit") ? "crit" : "default";
    }

    private ConfigurationSection root(String type) {
        return plugin.getConfig().getConfigurationSection("cosmetics." + type);
    }

    public List<String> ids(String type) {
        ConfigurationSection r = root(type);
        return r == null ? new ArrayList<>() : new ArrayList<>(r.getKeys(false));
    }

    public ConfigurationSection option(String type, String id) {
        ConfigurationSection r = root(type);
        return r == null ? null : r.getConfigurationSection(id);
    }

    public boolean allowed(Player p, String type, String id) {
        ConfigurationSection s = option(type, id);
        if (s == null) return false;
        String perm = s.getString("permission", "");
        return perm.isEmpty() || p.hasPermission(perm);
    }

    public String selected(PlayerStats st, String type) {
        return switch (type) {
            case "kill" -> st.killEffect;
            case "victory" -> st.victoryEffect;
            default -> st.hitEffect;
        };
    }

    public void select(PlayerStats st, String type, String id) {
        switch (type) {
            case "kill" -> st.killEffect = id;
            case "victory" -> st.victoryEffect = id;
            default -> st.hitEffect = id;
        }
        plugin.stats().markDirty();
    }

    /** The section to use for this player: their pick if they may use it, otherwise the default. */
    private ConfigurationSection effective(Player p, String type) {
        if (p != null) {
            PlayerStats st = plugin.stats().get(p.getUniqueId(), p.getName());
            String id = selected(st, type);
            if (allowed(p, type, id)) return option(type, id);
        }
        return option(type, defaultId(type));
    }

    public void kill(Player killer, Location loc) {
        ConfigurationSection s = effective(killer, "kill");
        String particle = s == null ? "TOTEM_OF_UNDYING" : s.getString("particle", "TOTEM_OF_UNDYING");
        int count = s == null ? 35 : s.getInt("count", 35);
        Fx.particle(loc, particle, count, 0.5);
        if (s != null && s.getBoolean("lightning", false)) loc.getWorld().strikeLightningEffect(loc);
        if (s != null) Fx.sound(loc, s.getString("sound", ""), 1f, 1f);
    }

    public void hit(Player attacker, Location loc) {
        ConfigurationSection s = effective(attacker, "hit");
        if (s == null) return;
        Fx.particle(loc, s.getString("particle", "CRIT"), s.getInt("count", 6), 0.3);
    }

    public int victoryCount(Player winner) {
        ConfigurationSection s = effective(winner, "victory");
        return s == null ? 6 : s.getInt("fireworks", 6);
    }

    public List<String> victoryColors(Player winner) {
        ConfigurationSection s = effective(winner, "victory");
        return s == null ? List.of("#FFD700", "#FF4500") : s.getStringList("colors");
    }
}
