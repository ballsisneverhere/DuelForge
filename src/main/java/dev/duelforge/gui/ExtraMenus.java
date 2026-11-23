package dev.duelforge.gui;

import dev.duelforge.DuelForge;
import dev.duelforge.party.PartyManager.Party;
import dev.duelforge.stats.StatsManager.PlayerStats;
import dev.duelforge.util.Fmt;
import dev.duelforge.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Friends, party and cosmetics menus. */
public final class ExtraMenus {
    private ExtraMenus() {}

    // =====================================================================
    public static final class FriendsMenu extends PagedMenu<UUID> {
        public FriendsMenu(DuelForge pl, Player v) {
            super(pl, v, pl.messages().get("gui.friends.title"), () -> pl.gui().hub(v));
        }

        @Override
        protected List<UUID> load() {
            return plugin.friends().list(viewer.getUniqueId());
        }

        @Override
        protected ItemStack entryIcon(UUID u) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            boolean online = Bukkit.getPlayer(u) != null;
            String name = op.getName() == null ? "?" : op.getName();
            return Items.head(op, (online ? "&a" : "&7") + name,
                    plugin.messages().list(online ? "gui.friends.lore-online" : "gui.friends.lore-offline"));
        }

        @Override
        protected void select(UUID u, InventoryClickEvent e) {
            Player target = Bukkit.getPlayer(u);
            if (e.isShiftClick()) {
                plugin.friends().remove(viewer.getUniqueId(), u);
                plugin.messages().send(viewer, "friend.removed");
                later(this::refresh);
            } else if (target == null) {
                plugin.messages().send(viewer, "player-not-found");
            } else if (e.isRightClick()) {
                later(() -> plugin.parties().invite(viewer, target));
            } else {
                later(() -> plugin.gui().duelSetup(viewer, target));
            }
        }

        @Override
        protected ItemStack extra() {
            return Items.make(Material.BOOK, t("gui.friends.add-hint.name"), plugin.messages().list("gui.friends.add-hint.lore"));
        }
    }

    // =====================================================================
    public static final class PartyMenu extends Menu {
        public PartyMenu(DuelForge pl, Player v) {
            super(pl, v, 3, pl.messages().get("gui.party.title"));
        }

        @Override
        public void build() {
            inv.clear();
            fill(Material.BLACK_STAINED_GLASS_PANE);
            Party party = plugin.parties().of(viewer.getUniqueId());
            boolean inParty = party != null && party.members.size() > 1;
            boolean leader = party == null || party.leader.equals(viewer.getUniqueId());
            if (party != null) {
                for (int i = 0; i < party.members.size() && i < 7; i++) {
                    UUID u = party.members.get(i);
                    OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                    String name = (u.equals(party.leader) ? "&6\u2605 &f" : "&f") + (op.getName() == null ? "?" : op.getName());
                    set(10 + i, Items.head(op, name, List.of()));
                }
            } else {
                set(13, icon(Material.NAME_TAG, "gui.party.none"));
            }
            if (leader) set(20, icon(Material.EMERALD, "gui.party.invite"));
            if (inParty) set(22, leader ? icon(Material.TNT, "gui.party.disband") : icon(Material.BARRIER, "gui.party.leave"));
            set(24, icon(Material.NETHER_STAR, "gui.party.queue"));
            set(18, Items.make(Material.ARROW, t("gui.back")));
        }

        @Override
        public void onClick(InventoryClickEvent e) {
            var g = plugin.gui();
            switch (e.getRawSlot()) {
                case 20 -> later(() -> g.partyInvitePicker(viewer));
                case 22 -> later(() -> {
                    Party party = plugin.parties().of(viewer.getUniqueId());
                    if (party == null) return;
                    if (party.leader.equals(viewer.getUniqueId())) plugin.parties().disband(viewer);
                    else plugin.parties().leave(viewer, false);
                    g.party(viewer);
                });
                case 24 -> later(() -> g.queue(viewer));
                case 18 -> later(() -> g.hub(viewer));
                default -> { }
            }
        }
    }

    // =====================================================================
    public static final class CosmeticsMenu extends Menu {
        private static final String[] TYPES = {"kill", "victory", "hit"};
        private static final int[] BASE = {10, 19, 28};
        private static final Material[] LABEL = {Material.TOTEM_OF_UNDYING, Material.FIREWORK_ROCKET, Material.IRON_SWORD};

        public CosmeticsMenu(DuelForge pl, Player v) {
            super(pl, v, 5, pl.messages().get("gui.cosmetics.title"));
        }

        @Override
        public void build() {
            inv.clear();
            border(Material.BLACK_STAINED_GLASS_PANE);
            PlayerStats st = plugin.stats().get(viewer.getUniqueId(), viewer.getName());
            for (int ti = 0; ti < TYPES.length; ti++) {
                String type = TYPES[ti];
                set(BASE[ti] - 1, icon(LABEL[ti], "gui.cosmetics." + type));
                List<String> ids = plugin.cosmetics().ids(type);
                String sel = plugin.cosmetics().selected(st, type);
                for (int j = 0; j < Math.min(7, ids.size()); j++) {
                    String id = ids.get(j);
                    ConfigurationSection o = plugin.cosmetics().option(type, id);
                    if (o == null) continue;
                    Material mat = Material.matchMaterial(o.getString("icon", "STONE"));
                    boolean chosen = id.equals(sel);
                    boolean allowed = plugin.cosmetics().allowed(viewer, type, id);
                    List<String> lore = new ArrayList<>();
                    lore.add(t(chosen ? "gui.cosmetics.selected" : (allowed ? "gui.cosmetics.click" : "gui.cosmetics.locked")));
                    set(BASE[ti] + j, Items.make(mat == null ? Material.STONE : mat, (chosen ? "&a\u2714 " : "") + o.getString("name", id), lore));
                }
            }
            set(36, Items.make(Material.ARROW, t("gui.back")));
        }

        @Override
        public void onClick(InventoryClickEvent e) {
            int slot = e.getRawSlot();
            if (slot == 36) {
                later(() -> plugin.gui().hub(viewer));
                return;
            }
            for (int ti = 0; ti < TYPES.length; ti++) {
                if (slot < BASE[ti] || slot >= BASE[ti] + 7) continue;
                List<String> ids = plugin.cosmetics().ids(TYPES[ti]);
                int idx = slot - BASE[ti];
                if (idx >= ids.size()) return;
                String id = ids.get(idx);
                if (!plugin.cosmetics().allowed(viewer, TYPES[ti], id)) {
                    plugin.messages().send(viewer, "cosmetics.locked");
                    return;
                }
                plugin.cosmetics().select(plugin.stats().get(viewer.getUniqueId(), viewer.getName()), TYPES[ti], id);
                ConfigurationSection o = plugin.cosmetics().option(TYPES[ti], id);
                plugin.messages().send(viewer, "cosmetics.selected", "name", Fmt.color(o == null ? id : o.getString("name", id)));
                refresh();
                return;
            }
        }
    }
}
