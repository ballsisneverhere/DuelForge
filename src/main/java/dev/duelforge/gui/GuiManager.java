package dev.duelforge.gui;

import dev.duelforge.DuelForge;
import dev.duelforge.kit.Kit;
import dev.duelforge.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Opens menus (always on the next tick) and routes inventory events to them. */
public final class GuiManager implements Listener {
    private final DuelForge plugin;
    private final Map<UUID, Setup> setups = new HashMap<>();

    public GuiManager(DuelForge plugin) {
        this.plugin = plugin;
    }

    private void open(Menu m) {
        Bukkit.getScheduler().runTask(plugin, m::open);
    }

    private boolean busy(Player p) {
        if (plugin.matches().inMatch(p.getUniqueId())) {
            plugin.messages().send(p, "already-in-match");
            return true;
        }
        return false;
    }

    private boolean perm(Player p, String node) {
        if (p.hasPermission(node)) return true;
        plugin.messages().send(p, "no-permission");
        return false;
    }

    public void hub(Player p) {
        if (busy(p)) return;
        open(new Menus.Hub(plugin, p));
    }

    public void players(Player p) {
        if (busy(p) || !perm(p, "duelforge.duel")) return;
        open(new Menus.PlayerPick(plugin, p));
    }

    public void duelSetup(Player p, Player target) {
        if (busy(p) || !perm(p, "duelforge.duel")) return;
        Kit def = plugin.kits().defaultKit();
        if (def == null) {
            plugin.messages().send(p, "kit-not-found");
            return;
        }
        Setup s = new Setup();
        s.target = target.getUniqueId();
        s.kit = def;
        setups.put(p.getUniqueId(), s);
        setupMenu(p, s);
    }

    public void ffaSetup(Player p) {
        if (busy(p) || !perm(p, "duelforge.ffa")) return;
        if (!plugin.getConfig().getBoolean("ffa.enabled", true)) {
            plugin.messages().send(p, "ffa-disabled");
            return;
        }
        Kit def = plugin.kits().defaultKit();
        if (def == null) return;
        Setup s = new Setup();
        s.ffa = true;
        s.kit = def;
        setups.put(p.getUniqueId(), s);
        setupMenu(p, s);
    }

    public void setupMenu(Player p, Setup s) {
        open(new Menus.SetupMenu(plugin, p, s));
    }

    public void kitList(Player p, String title, Consumer<Kit> select, Runnable back) {
        open(new Menus.KitList(plugin, p, title, select, back, () -> kitList(p, title, select, back)));
    }

    /** Browse kits (preview only). */
    public void kitBrowser(Player p) {
        kitList(p, plugin.messages().get("gui.kits.title"), null, () -> hub(p));
    }

    public void preview(Player p, Kit kit, Consumer<Kit> select, Runnable back) {
        open(new Menus.KitPreview(plugin, p, kit, select, back));
    }

    public void arenas(Player p, Setup s) {
        open(new Menus.ArenaList(plugin, p, s));
    }

    public void invites(Player p) {
        if (busy(p)) return;
        open(new Menus.Invites(plugin, p));
    }

    public void stats(Player p, OfflinePlayer target) {
        if (!perm(p, "duelforge.stats")) return;
        open(new Menus.StatsMenu(plugin, p, target));
    }

    public void lobbies(Player p) {
        if (busy(p) || !perm(p, "duelforge.ffa")) return;
        open(new Menus.Lobbies(plugin, p));
    }

    public void spectate(Player p) {
        if (busy(p) || !perm(p, "duelforge.spectate")) return;
        open(new Menus.SpectateList(plugin, p));
    }

    /** Kit chooser followed by the layout editor. */
    public void editor(Player p) {
        if (busy(p) || !perm(p, "duelforge.editkit")) return;
        kitList(p, plugin.messages().get("gui.kits.editor-title"), kit -> {
            plugin.messages().send(p, "editor.opened");
            open(new Menus.KitEditor(plugin, p, kit));
        }, () -> hub(p));
    }

    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getWhoClicked() instanceof Player p) Fx.sound(p, plugin.getConfig().getString("sounds.gui-click", ""), 0.5f, 1f);
        menu.onClick(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        menu.onClose(e);
        // A menu must never hand items to the player: wipe whatever is left on the cursor.
        if (e.getPlayer() instanceof Player p) p.setItemOnCursor(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        setups.remove(e.getPlayer().getUniqueId());
    }
}
