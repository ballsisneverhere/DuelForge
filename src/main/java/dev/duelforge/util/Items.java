package dev.duelforge.util;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/** Small item builder helpers for GUIs. */
public final class Items {
    private Items() {}

    public static ItemStack make(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(Fmt.color(name));
            if (lore != null && !lore.isEmpty()) meta.setLore(Fmt.color(lore));
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack make(Material m, String name, String... lore) {
        return make(m, name, List.of(lore));
    }

    public static ItemStack head(OfflinePlayer p, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta sm) {
            sm.setOwningPlayer(p);
            if (name != null) sm.setDisplayName(Fmt.color(name));
            if (lore != null && !lore.isEmpty()) sm.setLore(Fmt.color(lore));
            it.setItemMeta(sm);
        }
        return it;
    }

    public static ItemStack pane(Material m) {
        return make(m, " ");
    }
}
