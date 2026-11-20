package dev.duelforge;

import dev.duelforge.arena.ArenaManager;
import dev.duelforge.command.DuelCommand;
import dev.duelforge.gui.GuiManager;
import dev.duelforge.kit.AbilityManager;
import dev.duelforge.kit.KitManager;
import dev.duelforge.listener.MatchListener;
import dev.duelforge.match.MatchManager;
import dev.duelforge.stats.StatsManager;
import dev.duelforge.ui.ScoreboardService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.UUID;

/** DuelForge: kit-based PvP duels, FFA, ranked, spectating and a full GUI. */
public final class DuelForge extends JavaPlugin {
    public NamespacedKey abilityKey;

    private Messages messages;
    private AbilityManager abilities;
    private KitManager kits;
    private ArenaManager arenas;
    private StatsManager stats;
    private ScoreboardService boards;
    private MatchManager matches;
    private GuiManager gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String f : new String[]{"kits.yml", "arenas.yml", "messages.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        abilityKey = new NamespacedKey(this, "ability");

        messages = new Messages(this);
        messages.load();
        abilities = new AbilityManager(this);
        kits = new KitManager(this);
        kits.load();
        arenas = new ArenaManager(this);
        arenas.load();
        stats = new StatsManager(this);
        stats.load();
        boards = new ScoreboardService(this);
        matches = new MatchManager(this);
        gui = new GuiManager(this);

        Bukkit.getPluginManager().registerEvents(new MatchListener(this), this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        DuelCommand dc = new DuelCommand(this);
        for (String n : new String[]{"duel", "accept", "deny", "duelstats"}) {
            PluginCommand pc = getCommand(n);
            if (pc != null) {
                pc.setExecutor(dc);
                pc.setTabCompleter(dc);
            }
        }

        matches.start();
        stats.startAutosave();
        // /reload safety: recover anyone whose match snapshot is still on disk
        for (Player p : Bukkit.getOnlinePlayers()) matches.restorePending(p);
        getLogger().info("DuelForge enabled.");
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
        if (stats != null) stats.stop();
        if (kits != null) kits.saveLayouts(false);
        if (arenas != null) arenas.save(false);
    }

    public void reload() {
        reloadConfig();
        messages.load();
        kits.load();
        if (matches.all().isEmpty()) arenas.load();
    }

    public File snapshotFile(UUID id) {
        return new File(getDataFolder(), "snapshots" + File.separator + id + ".yml");
    }

    // ---- lobby location (stored in lobby.yml so config.yml keeps its comments) ----

    public Location lobby() {
        File f = new File(getDataFolder(), "lobby.yml");
        if (!f.exists()) return null;
        String s = YamlConfiguration.loadConfiguration(f).getString("location", "");
        try {
            String[] a = s.split(",");
            var w = Bukkit.getWorld(a[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Float.parseFloat(a[4]), Float.parseFloat(a[5]));
        } catch (Exception e) {
            return null;
        }
    }

    public void setLobby(Location l) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("location", l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch());
        dev.duelforge.util.Persist.save(this, new File(getDataFolder(), "lobby.yml"), c, false);
    }

    public Messages messages() { return messages; }
    public AbilityManager abilities() { return abilities; }
    public KitManager kits() { return kits; }
    public ArenaManager arenas() { return arenas; }
    public StatsManager stats() { return stats; }
    public ScoreboardService boards() { return boards; }
    public MatchManager matches() { return matches; }
    public GuiManager gui() { return gui; }
}
