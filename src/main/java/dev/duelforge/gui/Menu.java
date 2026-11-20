package dev.duelforge.gui;

import dev.duelforge.DuelForge;
import dev.duelforge.util.Fmt;
import dev.duelforge.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Base class for all DuelForge GUIs. Clicks are cancelled by default; menus opt in to moving items. */
public abstract class Menu implements InventoryHolder {
    protected final DuelForge plugin;
    protected final Player viewer;
    protected final Inventory inv;

    protected Menu(DuelForge plugin, Player viewer, int rows, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, rows * 9, Fmt.color(title));
    }

    /** Fills the inventory. Called on open and on refresh. */
    public abstract void build();

    /** Called for clicks inside this menu's inventory. The event is already cancelled. */
    public abstract void onClick(InventoryClickEvent e);

    public void onClose(InventoryCloseEvent e) {}

    public void open() {
        build();
        viewer.openInventory(inv);
    }

    public void refresh() {
        build();
    }

    protected void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
    }

    protected void fill(Material pane) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, Items.pane(pane));
    }

    protected void border(Material pane) {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, Items.pane(pane));
        }
    }

    protected String t(String key, Object... kv) {
        return plugin.messages().get(key, kv);
    }

    /** Menu item whose name and lore come from messages.yml (base + ".name" / ".lore"). */
    protected ItemStack icon(Material m, String base, Object... kv) {
        return Items.make(m, plugin.messages().get(base + ".name", kv), plugin.messages().list(base + ".lore", kv));
    }

    protected void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
