package dev.duelforge;

import dev.duelforge.arena.ArenaManager;
import dev.duelforge.arena.InstanceManager;
import dev.duelforge.command.DuelCommand;
import dev.duelforge.command.ExtraCommands;
import dev.duelforge.cosmetic.CosmeticManager;
import dev.duelforge.gui.GuiManager;
import dev.duelforge.hook.PapiHook;
import dev.duelforge.kit.AbilityManager;
import dev.duelforge.kit.KitManager;
import dev.duelforge.listener.HubListener;
import dev.duelforge.listener.MatchListener;
import dev.duelforge.match.MatchManager;
import dev.duelforge.party.PartyManager;
import dev.duelforge.queue.QueueManager;
import dev.duelforge.social.FriendsManager;
import dev.duelforge.stats.StatsManager;
import dev.duelforge.ui.HubItems;
import dev.duelforge.ui.LeaderboardManager;
import dev.duelforge.ui.ScoreboardService;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** DuelForge: kit-based PvP duels, FFA, ranked, spectating and a full GUI. */
public final class DuelForge extends JavaPlugin {
    public NamespacedKey abilityKey;
    public NamespacedKey hubKey;

    private Messages messages;
    private AbilityManager abilities;
    private KitManager kits;
    private ArenaManager arenas;
    private StatsManager stats;
    private ScoreboardService boards;
    private MatchManager matches;
    private GuiManager gui;
    private PartyManager parties;
    private FriendsManager friends;
    private CosmeticManager cosmetics;
    private QueueManager queue;
    private InstanceManager instances;
    private HubItems hubItems;
    private LeaderboardManager leaderboards;
    private ExtraCommands extra;
    private final Set<UUID> builders = new HashSet<>();
    private final Map<String, String> customWorlds = new LinkedHashMap<>();

    /** Generates a completely empty (void) world. */
    public static final class VoidGenerator extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, 65, 0.5);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String f : new String[]{"kits.yml", "arenas.yml", "messages.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        abilityKey = new NamespacedKey(this, "ability");
        hubKey = new NamespacedKey(this, "hubaction");
        loadCustomWorlds();

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
        parties = new PartyManager(this);
        friends = new FriendsManager(this);
        friends.load();
        cosmetics = new CosmeticManager(this);
        queue = new QueueManager(this);
        instances = new InstanceManager(this);
        hubItems = new HubItems(this);
        leaderboards = new LeaderboardManager(this);
        leaderboards.load();
        extra = new ExtraCommands(this);

        Bukkit.getPluginManager().registerEvents(new MatchListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HubListener(this), this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        DuelCommand dc = new DuelCommand(this);
        for (String n : new String[]{"duel", "accept", "deny", "duelstats"}) {
            PluginCommand pc = getCommand(n);
            if (pc != null) {
                pc.setExecutor(dc);
                pc.setTabCompleter(dc);
            }
        }

        for (String n : new String[]{"queue", "party", "friend"}) {
            PluginCommand pc = getCommand(n);
            if (pc != null) {
                pc.setExecutor(extra);
                pc.setTabCompleter(extra);
            }
        }

        matches.start();
        stats.startAutosave();
        leaderboards.start();
        Bukkit.getScheduler().runTask(this, instances::ensureWorld); // pre-create the match world
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            PapiHook.register(this);
            getLogger().info("PlaceholderAPI expansion registered (%duelforge_...%).");
        }
        // /reload safety: recover anyone whose match snapshot is still on disk
        for (Player p : Bukkit.getOnlinePlayers()) matches.restorePending(p);
        getLogger().info("DuelForge enabled.");
    }

    @Override
    public void onDisable() {
        if (leaderboards != null) leaderboards.stop();
        if (friends != null) friends.save(false);
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
        l.getWorld().setSpawnLocation(l);
    }

    // ---- build mode (lets admins edit the protected world) ----

    public boolean isBuilder(UUID id) {
        return builders.contains(id);
    }

    /** Toggles build mode; returns the new state. */
    public boolean toggleBuilder(UUID id) {
        if (builders.remove(id)) return false;
        builders.add(id);
        return true;
    }

    public void removeBuilder(UUID id) {
        builders.remove(id);
    }

    // ---- custom worlds (void / flat) created with /duel admin world create ----

    private File worldsFile() {
        return new File(getDataFolder(), "worlds.yml");
    }

    private void loadCustomWorlds() {
        ConfigurationSection s = YamlConfiguration.loadConfiguration(worldsFile()).getConfigurationSection("worlds");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            String type = s.getString(name, "void");
            customWorlds.put(name, type);
            try {
                buildWorld(name, type);
            } catch (Exception ex) {
                getLogger().warning("Could not load world '" + name + "': " + ex.getMessage());
            }
        }
    }

    public World buildWorld(String name, String type) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;
        WorldCreator wc = new WorldCreator(name).generateStructures(false);
        if (type.equals("flat")) wc.type(WorldType.FLAT);
        else wc.generator(new VoidGenerator());
        World w = wc.createWorld();
        if (w == null) throw new IllegalStateException("world creation failed");
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setTime(6000);
        w.setStorm(false);
        return w;
    }

    public Map<String, String> customWorlds() {
        return customWorlds;
    }

    public boolean worldNameTaken(String name) {
        return Bukkit.getWorld(name) != null || new File(Bukkit.getWorldContainer(), name).exists();
    }

    /** Creates a new build world and returns its spawn point. */
    public Location createWorld(String name, String type) {
        World w = buildWorld(name, type);
        customWorlds.put(name, type);
        YamlConfiguration c = new YamlConfiguration();
        for (Map.Entry<String, String> e : customWorlds.entrySet()) c.set("worlds." + e.getKey(), e.getValue());
        dev.duelforge.util.Persist.save(this, worldsFile(), c, false);
        if (type.equals("flat")) return w.getSpawnLocation().add(0.5, 0, 0.5);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) w.getBlockAt(x, 64, z).setType(Material.SMOOTH_STONE);
        return new Location(w, 0.5, 65, 0.5);
    }

    public Messages messages() { return messages; }
    public AbilityManager abilities() { return abilities; }
    public KitManager kits() { return kits; }
    public ArenaManager arenas() { return arenas; }
    public StatsManager stats() { return stats; }
    public ScoreboardService boards() { return boards; }
    public MatchManager matches() { return matches; }
    public GuiManager gui() { return gui; }
    public PartyManager parties() { return parties; }
    public FriendsManager friends() { return friends; }
    public CosmeticManager cosmetics() { return cosmetics; }
    public QueueManager queue() { return queue; }
    public InstanceManager instances() { return instances; }
    public HubItems hubItems() { return hubItems; }
    public LeaderboardManager leaderboards() { return leaderboards; }
    public ExtraCommands extra() { return extra; }
}
