package dev.duelforge.listener;

import dev.duelforge.DuelForge;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Makes the lobby hotbar items clickable and impossible to drop, move or duplicate. */
public final class HubListener implements Listener {
    private final DuelForge plugin;

    public HubListener(DuelForge plugin) {
        this.plugin = plugin;
    }

    private boolean isHub(ItemStack it) {
        return plugin.hubItems().actionOf(it) != null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        String action = plugin.hubItems().actionOf(e.getItem());
        if (action == null) return;
        e.setCancelled(true);
        if (plugin.matches().inMatch(e.getPlayer().getUniqueId())) return;
        plugin.hubItems().run(e.getPlayer(), action);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || plugin.isBuilder(p.getUniqueId())) return;
        boolean hub = isHub(e.getCurrentItem()) || isHub(e.getCursor());
        if (!hub && e.getHotbarButton() >= 0) hub = isHub(p.getInventory().getItem(e.getHotbarButton()));
        if (!hub && e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) hub = isHub(p.getInventory().getItemInOffHand());
        if (hub) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && !plugin.isBuilder(p.getUniqueId()) && isHub(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (!plugin.isBuilder(e.getPlayer().getUniqueId()) && isHub(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!plugin.isBuilder(e.getPlayer().getUniqueId()) && (isHub(e.getMainHandItem()) || isHub(e.getOffHandItem()))) e.setCancelled(true);
    }
}
