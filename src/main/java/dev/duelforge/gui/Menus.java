package dev.duelforge.gui;

import dev.duelforge.DuelForge;
import dev.duelforge.arena.Arena;
import dev.duelforge.kit.Kit;
import dev.duelforge.match.Match;
import dev.duelforge.match.MatchManager.DuelRequest;
import dev.duelforge.queue.QueueManager;
import dev.duelforge.stats.StatsManager.PlayerStats;
import dev.duelforge.util.Fmt;
import dev.duelforge.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** All concrete DuelForge menus. */
public final class Menus {
    private Menus() {}

    private static List<String> kitInfo(DuelForge pl, Kit k) {
        var ms = pl.messages();
        List<String> lore = new ArrayList<>(Fmt.color(k.description));
        lore.add("");
        lore.add(ms.get("gui.kits.regen", "value", ms.get(k.naturalRegen ? "gui.kits.yes" : "gui.kits.no")));
        lore.add(ms.get("gui.kits.hunger", "value", ms.get(k.hunger ? "gui.kits.yes" : "gui.kits.no")));
        lore.add(ms.get("gui.kits.fall", "value", ms.get(k.fallDamage ? "gui.kits.yes" : "gui.kits.no")));
        for (PotionEffect e : k.effects) {
            lore.add(ms.get("gui.kits.effect", "effect", Fmt.pretty(e.getType().getKey().getKey()), "level", e.getAmplifier() + 1));
        }
        return lore;
    }

    // =====================================================================
    public static final class Hub extends Menu {
        public Hub(DuelForge pl, Player v) {
            super(pl, v, 5, pl.messages().get("gui.hub.title"));
        }

        @Override
        public void build() {
            inv.clear();
            border(Material.BLACK_STAINED_GLASS_PANE);
            set(10, icon(Material.DIAMOND_SWORD, "gui.hub.duel"));
            set(12, icon(Material.NETHER_STAR, "gui.hub.queue"));
            set(14, icon(Material.NETHERITE_AXE, "gui.hub.ffa"));
            set(16, icon(Material.ENDER_CHEST, "gui.hub.kits"));
            set(19, icon(Material.ANVIL, "gui.hub.editor"));
            set(21, icon(Material.FIREWORK_ROCKET, "gui.hub.cosmetics"));
            set(23, icon(Material.PAPER, "gui.hub.invites", "count", plugin.matches().incoming(viewer.getUniqueId()).size()));
            set(25, icon(Material.ENDER_EYE, "gui.hub.spectate"));
            set(28, Items.head(viewer, t("gui.hub.stats.name"), plugin.messages().list("gui.hub.stats.lore")));
            set(30, icon(Material.PLAYER_HEAD, "gui.hub.friends"));
            set(32, icon(Material.NAME_TAG, "gui.hub.party"));
            set(40, Items.make(Material.BARRIER, t("gui.close")));
        }

        @Override
        public void onClick(InventoryClickEvent e) {
            var g = plugin.gui();
            switch (e.getRawSlot()) {
                case 10 -> later(() -> g.players(viewer));
                case 12 -> later(() -> g.queue(viewer));
                case 14 -> later(() -> g.lobbies(viewer));
                case 16 -> later(() -> g.kitBrowser(viewer));
                case 19 -> later(() -> g.editor(viewer));
                case 21 -> later(() -> g.cosmetics(viewer));
                case 23 -> later(() -> g.invites(viewer));
                case 25 -> later(() -> g.spectate(viewer));
                case 28 -> later(() -> g.stats(viewer, viewer));
                case 30 -> later(() -> g.friends(viewer));
                case 32 -> later(() -> g.party(viewer));
                case 40 -> later(viewer::closeInventory);
                default -> { }
            }
        }
    }

    // =====================================================================
    public static final class PlayerPick extends PagedMenu<Player> {
        private final Consumer<Player> onPick;

        public PlayerPick(DuelForge pl, Player v, String title, Consumer<Player> onPick) {
            super(pl, v, title, () -> pl.gui().hub(v));
            this.onPick = onPick;
        }

        @Override
        protected List<Player> load() {
            List<Player> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(viewer) && !plugin.matches().inMatch(p.getUniqueId())) out.add(p);
            }
            return out;
        }

        @Override
        protected ItemStack entryIcon(Player p) {
            return Items.head(p, "&e" + p.getName(), plugin.messages().list("gui.players.lore"));
        }

        @Override
        protected void select(Player p, InventoryClickEvent e) {
            later(() -> onPick.accept(p));
        }
    }

    // =====================================================================
    public static final class SetupMenu extends Menu {
        private final Setup s;

        public SetupMenu(DuelForge pl, Player v, Setup s) {
            super(pl, v, 3, pl.messages().get("gui.setup.title"));
            this.s = s;
        }

        private int partySize() {
            var party = plugin.parties().of(viewer.getUniqueId());
            return party == null ? 1 : Math.max(1, party.members.size());
        }

        @Override
        public void build() {
            inv.clear();
            fill(Material.BLACK_STAINED_GLASS_PANE);
            Kit k = s.kit;
            set(10, Items.make(k.icon, t("gui.setup.kit.name", "kit", Fmt.color(k.displayName)), plugin.messages().list("gui.setup.kit.lore")));
            if (s.type == Setup.Type.QUEUE) {
                set(12, icon(Material.NETHER_STAR, "gui.setup.queue-mode", "mode", QueueManager.modeName(s.teamSize)));
            } else {
                String arenaName = s.arena == null ? t("request.random-arena") : Fmt.color(s.arena.displayName);
                set(12, icon(s.arena == null ? Material.COMPASS : s.arena.icon, "gui.setup.arena", "arena", arenaName));
            }
            if (s.type == Setup.Type.FFA) set(14, icon(Material.IRON_SWORD, "gui.setup.ffa-mode"));
            else set(14, icon(s.ranked ? Material.GOLD_INGOT : Material.IRON_INGOT, "gui.setup.ranked", "mode", plugin.matches().modeName(s.ranked)));
            set(16, icon(Material.BOOK, "gui.setup.preview"));
            if (s.type != Setup.Type.FFA) set(24, icon(Material.CLOCK, "gui.setup.rounds", "rounds", s.bestOf));
            switch (s.type) {
                case FFA -> set(22, icon(Material.EMERALD_BLOCK, "gui.setup.create"));
                case QUEUE -> {
                    if (plugin.queue().get(viewer.getUniqueId()) != null) set(22, icon(Material.REDSTONE_BLOCK, "gui.setup.queue-leave"));
                    else set(22, icon(Material.EMERALD_BLOCK, "gui.setup.queue-join", "mode", QueueManager.modeName(s.teamSize)));
                }
                default -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(s.target);
                    set(22, icon(Material.EMERALD_BLOCK, "gui.setup.send", "target", target.getName() == null ? "?" : target.getName()));
                }
            }
            set(18, icon(Material.REDSTONE_BLOCK, "gui.setup.cancel"));
        }

        @Override
        public void onClick(InventoryClickEvent e) {
            var g = plugin.gui();
            switch (e.getRawSlot()) {
                case 10 -> later(() -> g.kitList(viewer, plugin.messages().get("gui.kits.select-title"), kit -> {
                    s.kit = kit;
                    g.setupMenu(viewer, s);
                }, () -> g.setupMenu(viewer, s)));
                case 12 -> {
                    if (s.type == Setup.Type.QUEUE) {
                        int min = Math.min(3, partySize());
                        do {
                            s.teamSize = s.teamSize % 3 + 1;
                        } while (s.teamSize < min);
                        if (s.teamSize > 1) s.ranked = false;
                        refresh();
                    } else {
                        later(() -> g.arenas(viewer, s));
                    }
                }
                case 14 -> {
                    boolean canRank = s.type != Setup.Type.FFA && s.teamSize == 1 && plugin.getConfig().getBoolean("duel.ranked-enabled", true);
                    if (canRank) {
                        s.ranked = !s.ranked;
                        refresh();
                    }
                }
                case 16 -> later(() -> g.preview(viewer, s.kit, null, () -> g.setupMenu(viewer, s)));
                case 24 -> {
                    if (s.type != Setup.Type.FFA) {
                        s.bestOf = s.bestOf == 1 ? 3 : (s.bestOf == 3 ? 5 : 1);
                        refresh();
                    }
                }
                case 22 -> later(() -> {
                    viewer.closeInventory();
                    switch (s.type) {
                        case FFA -> plugin.matches().createLobby(viewer, s.kit, s.arena);
                        case QUEUE -> {
                            if (plugin.queue().get(viewer.getUniqueId()) != null) plugin.queue().leave(viewer.getUniqueId());
                            else plugin.queue().join(viewer, s.teamSize, s.kit, s.ranked, s.bestOf);
                        }
                        default -> {
                            Player target = s.target == null ? null : Bukkit.getPlayer(s.target);
                            if (target == null) plugin.messages().send(viewer, "player-not-found");
                            else plugin.matches().sendRequest(viewer, target, s.kit, s.arena, s.ranked, s.bestOf);
                        }
                    }
                });
                case 18 -> later(viewer::closeInventory);
                default -> { }
            }
        }
    }

    // =====================================================================
    public static final class KitList extends PagedMenu<Kit> {
        private final Consumer<Kit> onSelect;
        private final Runnable reopen;

        public KitList(DuelForge pl, Player v, String title, Consumer<Kit> onSelect, Runnable back, Runnable reopen) {
            super(pl, v, title, back);
            this.onSelect = onSelect;
            this.reopen = reopen;
        }

        @Override
        protected List<Kit> load() {
            return plugin.kits().enabled();
        }

        @Override
        protected ItemStack entryIcon(Kit k) {
            List<String> lore = kitInfo(plugin, k);
            lore.add("");
            boolean locked = !plugin.kits().canUse(viewer, k);
            if (locked) lore.add(t("gui.kits.locked"));
            else if (onSelect != null) {
                lore.add(t("gui.kits.lore-select"));
                lore.add(t("gui.kits.lore-preview"));
            } else lore.add(t("gui.kits.lore-preview-only"));
            ItemStack it = Items.make(k.icon, k.displayName, lore);
            return it;
        }

        @Override
        protected void select(Kit k, InventoryClickEvent e) {
            if (onSelect != null && e.isLeftClick()) {
                if (!plugin.kits().canUse(viewer, k)) {
                    plugin.messages().send(viewer, "kit-locked");
                    return;
                }
                later(() -> onSelect.accept(k));
            } else {
                later(() -> plugin.gui().preview(viewer, k, onSelect, reopen));
            }
        }
    }

    // =====================================================================
    public static final class KitPreview extends Menu {
        private final Kit kit;
        private final Consumer<Kit> select;
        private final Runnable back;

        public KitPreview(DuelForge pl, Player v, Kit kit, Consumer<Kit> select, Runnable back) {
            super(pl, v, 6, pl.messages().get("gui.preview.title", "kit", Fmt.color(kit.displayName)));
            this.kit = kit;
            this.select = select;
            this.back = back;
        }

        private ItemStack show(ItemStack it) {
            ItemStack c = it.clone();
            ItemMeta meta = c.getItemMeta();
            if (meta != null) {
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                c.setItemMeta(meta);
            }
            return c;
        }

        @Override
        public void build() {
            inv.clear();
            fill(Material.GRAY_STAINED_GLASS_PANE);
            for (int i = 0; i < 9; i++) set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
            for (int i = 45; i < 54; i++) set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
            set(4, Items.make(kit.icon, kit.displayName, kitInfo(plugin, kit)));
            // main inventory (slots 9-35) then hotbar (0-8), laid out like the player's real inventory
            for (int s = 9; s < 36; s++) set(s, Items.pane(Material.GRAY_STAINED_GLASS_PANE));
            for (Map.Entry<Integer, ItemStack> e : kit.inventory.entrySet()) {
                int slot = e.getKey();
                set(slot >= 9 ? slot : 36 + slot, show(e.getValue()));
            }
            String[] order = {"helmet", "chestplate", "leggings", "boots", "offhand"};
            for (int i = 0; i < order.length; i++) {
                ItemStack it = kit.equipment.get(order[i]);
                if (it != null) set(47 + i, show(it));
            }
            set(BACK_SLOT, Items.make(Material.ARROW, t("gui.back")));
            if (select != null) set(53, Items.make(Material.LIME_DYE, t("gui.preview.select")));
        }

        private static final int BACK_SLOT = 45;

        @Override
        public void onClick(InventoryClickEvent e) {
            int slot = e.getRawSlot();
            if (slot == BACK_SLOT && back != null) later(back);
            else if (slot == 53 && select != null) {
                if (!plugin.kits().canUse(viewer, kit)) {
                    plugin.messages().send(viewer, "kit-locked");
                    return;
                }
                later(() -> select.accept(kit));
            }
        }
    }

    // =====================================================================
    public static final class ArenaList extends PagedMenu<Arena> {
        private final Setup s;

        public ArenaList(DuelForge pl, Player v, Setup s) {
            super(pl, v, pl.messages().get("gui.arenas.title"), () -> pl.gui().setupMenu(v, s));
            this.s = s;
        }

        @Override
        protected List<Arena> load() {
            return plugin.arenas().ready();
        }

        @Override
        protected ItemStack entryIcon(Arena a) {
            List<String> lore = new ArrayList<>();
            lore.add(t(a.free() ? "gui.arenas.free" : "gui.arenas.busy"));
            lore.add(t("gui.arenas.click"));
            return Items.make(a.icon, a.displayName, lore);
        }

        @Override
        protected void select(Arena a, InventoryClickEvent e) {
            s.arena = a;
            later(() -> plugin.gui().setupMenu(viewer, s));
        }

        @Override
        protected ItemStack extra() {
            return icon(Material.COMPASS, "gui.arenas.random");
        }

        @Override
        protected void extraClick(InventoryClickEvent e) {
            s.arena = null;
            later(() -> plugin.gui().setupMenu(viewer, s));
        }
    }

    // =====================================================================
    public static final class Invites extends PagedMenu<DuelRequest> {
        public Invites(DuelForge pl, Player v) {
            super(pl, v, pl.messages().get("gui.invites.title"), () -> pl.gui().hub(v));
        }

        @Override
        protected List<DuelRequest> load() {
            return plugin.matches().incoming(viewer.getUniqueId());
        }

        @Override
        protected ItemStack entryIcon(DuelRequest r) {
            OfflinePlayer from = Bukkit.getOfflinePlayer(r.from);
            String arena = r.arena == null ? t("request.random-arena") : Fmt.color(r.arena.displayName);
            long secs = Math.max(0, (r.expires - System.currentTimeMillis()) / 1000);
            return Items.head(from, "&e" + (from.getName() == null ? "?" : from.getName()),
                    plugin.messages().list("gui.invites.lore", "kit", Fmt.color(r.kit.displayName), "arena", arena,
                            "mode", plugin.matches().describe(r), "seconds", secs));
        }

        @Override
        protected void select(DuelRequest r, InventoryClickEvent e) {
            String name = Bukkit.getOfflinePlayer(r.from).getName();
            boolean accept = e.isLeftClick();
            later(() -> {
                if (accept) {
                    viewer.closeInventory();
                    plugin.matches().accept(viewer, name);
                } else {
                    plugin.matches().deny(viewer, name);
                    refresh();
                }
            });
        }
    }

    // =====================================================================
    public static final class StatsMenu extends Menu {
        private final OfflinePlayer target;

        public StatsMenu(DuelForge pl, Player v, OfflinePlayer target) {
            super(pl, v, 5, pl.messages().get("gui.stats.title", "player", target.getName() == null ? "?" : target.getName()));
            this.target = target;
        }

        @Override
        public void build() {
            inv.clear();
            border(Material.BLACK_STAINED_GLASS_PANE);
            String name = target.getName() == null ? "?" : target.getName();
            PlayerStats st = plugin.stats().get(target.getUniqueId(), name);
            set(13, Items.head(target, t("gui.stats.head", "player", name), List.of(
                    t("stats.line", "wins", st.wins, "losses", st.losses, "kills", st.kills, "deaths", st.deaths),
                    t("stats.line2", "kd", st.kd(), "streak", st.killStreak, "best", st.bestKillStreak, "elo", st.elo))));
            set(19, Items.make(Material.DIAMOND, t("gui.stats.wins", "value", st.wins)));
            set(20, Items.make(Material.COAL, t("gui.stats.losses", "value", st.losses)));
            set(21, Items.make(Material.GOLD_INGOT, t("gui.stats.wl", "value", st.wl())));
            set(22, Items.make(Material.IRON_SWORD, t("gui.stats.kills", "value", st.kills)));
            set(23, Items.make(Material.SKELETON_SKULL, t("gui.stats.deaths", "value", st.deaths)));
            set(24, Items.make(Material.GOLDEN_SWORD, t("gui.stats.kd", "value", st.kd())));
            set(25, Items.make(Material.BLAZE_POWDER, t("gui.stats.streak", "value", st.killStreak, "best", st.bestKillStreak)));
            set(31, Items.make(Material.NETHER_STAR, t("gui.stats.elo", "value", st.elo)));
            set(36, Items.make(Material.ARROW, t("gui.back")));
            set(37, Items.make(Material.GOLD_BLOCK, t("gui.stats.top")));
            List<PlayerStats> top = plugin.stats().top("wins", 5);
            for (int i = 0; i < top.size(); i++) {
                PlayerStats s = top.get(i);
                set(39 + i, Items.head(Bukkit.getOfflinePlayer(s.id), t("gui.stats.top-entry", "rank", i + 1, "player", s.name),
                        plugin.messages().list("gui.stats.top-lore", "wins", s.wins, "kills", s.kills, "elo", s.elo)));
            }
        }

        @Override
        public void onClick(InventoryClickEvent e) {
            if (e.getRawSlot() == 36) later(() -> plugin.gui().hub(viewer));
        }
    }

    // =====================================================================
    public static final class Lobbies extends PagedMenu<Match> {
        public Lobbies(DuelForge pl, Player v) {
            super(pl, v, pl.messages().get("gui.lobbies.title"), () -> pl.gui().hub(v));
        }

        @Override
        protected List<Match> load() {
            return plugin.matches().lobbies();
        }

        @Override
        protected ItemStack entryIcon(Match m) {
            String host = Bukkit.getOfflinePlayer(m.host).getName();
            boolean mine = m.host.equals(viewer.getUniqueId());
            return Items.make(m.kit.icon, t("gui.lobbies.name", "host", host == null ? "?" : host),
                    plugin.messages().list(mine ? "gui.lobbies.host-lore" : "gui.lobbies.lore", "kit", Fmt.color(m.kit.displayName),
                            "arena", m.arenaName(), "count", m.players.size(), "max", m.maxPlayers));
        }

        @Override
        protected void select(Match m, InventoryClickEvent e) {
            later(() -> {
                viewer.closeInventory();
                if (m.host.equals(viewer.getUniqueId())) plugin.matches().startLobby(viewer, m);
                else plugin.matches().joinLobby(viewer, m);
            });
        }

        @Override
        protected ItemStack extra() {
            return icon(Material.EMERALD, "gui.lobbies.create");
        }

        @Override
        protected void extraClick(InventoryClickEvent e) {
            later(() -> plugin.gui().ffaSetup(viewer));
        }
    }

    // =====================================================================
    public static final class SpectateList extends PagedMenu<Match> {
        public SpectateList(DuelForge pl, Player v) {
            super(pl, v, pl.messages().get("gui.spectate.title"), () -> pl.gui().hub(v));
        }

        @Override
        protected List<Match> load() {
            return plugin.matches().spectatable();
        }

        @Override
        protected ItemStack entryIcon(Match m) {
            List<String> names = new ArrayList<>();
            for (UUID u : m.players) {
                String n = Bukkit.getOfflinePlayer(u).getName();
                if (n != null) names.add(n);
            }
            return Items.make(m.kit.icon, t("gui.spectate.name", "kit", Fmt.color(m.kit.displayName)),
                    plugin.messages().list("gui.spectate.lore", "arena", m.arenaName(),
                            "players", String.join(", ", names), "state", Fmt.pretty(m.state.name())));
        }

        @Override
        protected void select(Match m, InventoryClickEvent e) {
            later(() -> {
                viewer.closeInventory();
                plugin.matches().spectate(viewer, m);
            });
        }
    }

    // =====================================================================
    /** Lets a player rearrange their kit inventory. Only moving items inside this GUI is allowed. */
    public static final class KitEditor extends Menu {
        private final Kit kit;

        public KitEditor(DuelForge pl, Player v, Kit kit) {
            super(pl, v, 6, pl.messages().get("gui.editor.title", "kit", Fmt.color(kit.displayName)));
            this.kit = kit;
        }

        private static int guiIndex(int slot) {
            return slot >= 9 ? slot - 9 : 27 + slot;
        }

        private static int slotFor(int gui) {
            return gui < 27 ? gui + 9 : gui - 27;
        }

        @Override
        public void build() {
            for (int i = 0; i < 36; i++) inv.setItem(i, null);
            for (int i = 36; i < 54; i++) inv.setItem(i, Items.pane(Material.GRAY_STAINED_GLASS_PANE));
            for (Map.Entry<Integer, ItemStack> e : plugin.kits().layoutOrDefault(viewer.getUniqueId(), kit).entrySet()) {
                inv.setItem(guiIndex(e.getKey()), e.getValue().clone());
            }
            set(40, icon(Material.BOOK, "gui.editor.info"));
            set(45, Items.make(Material.ARROW, t("gui.back")));
            set(48, icon(Material.YELLOW_DYE, "gui.editor.reset"));
            set(50, icon(Material.LIME_DYE, "gui.editor.save"));
        }

        @Override
        public void onClick(InventoryClickEvent e) {
            int raw = e.getRawSlot();
            if (raw < 36 && e.getClickedInventory() == inv) {
                ClickType ct = e.getClick();
                if (ct == ClickType.LEFT || ct == ClickType.RIGHT) e.setCancelled(false);
                return;
            }
            switch (raw) {
                case 45 -> later(() -> plugin.gui().editor(viewer));
                case 48 -> {
                    plugin.kits().resetLayout(viewer.getUniqueId(), kit.id);
                    plugin.messages().send(viewer, "editor.reset");
                    refresh();
                }
                case 50 -> save();
                default -> { }
            }
        }

        private void save() {
            if (viewer.getItemOnCursor().getType() != Material.AIR) {
                plugin.messages().send(viewer, "editor.invalid");
                return;
            }
            Map<Integer, ItemStack> layout = new LinkedHashMap<>();
            for (int i = 0; i < 36; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() != Material.AIR) layout.put(slotFor(i), it.clone());
            }
            if (!kit.validLayout(layout)) {
                plugin.messages().send(viewer, "editor.invalid");
                return;
            }
            plugin.kits().setLayout(viewer.getUniqueId(), kit.id, layout);
            plugin.messages().send(viewer, "editor.saved", "kit", Fmt.color(kit.displayName));
            later(viewer::closeInventory);
        }
    }
}
