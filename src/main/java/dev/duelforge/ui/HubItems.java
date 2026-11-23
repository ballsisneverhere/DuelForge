package dev.duelforge.ui;

import dev.duelforge.DuelForge;
import dev.duelforge.util.Fmt;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The hotbar items players get in the lobby (compass = menu, sword = FFA, book = stats ...). */
public final class HubItems {
    private final DuelForge plugin;

    public HubItems(DuelForge plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("hub-items.enabled", true);
    }

    public void give(Player p) {
        if (!enabled() || plugin.isBuilder(p.getUniqueId()) || p.getGameMode() == GameMode.CREATIVE
                || plugin.matches().inMatch(p.getUniqueId())) return;
        PlayerInventory inv = p.getInventory();
        if (plugin.getConfig().getBoolean("hub-items.clear-inventory", true)) inv.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("hub-items.items")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
            Object slot = m.get("slot");
            Object action = m.get("action");
            if (mat == null || !(slot instanceof Number) || action == null) continue;
            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            if (meta == null) continue;
            if (m.get("name") != null) meta.setDisplayName(Fmt.color(String.valueOf(m.get("name"))));
            if (m.get("lore") instanceof List<?> l) {
                List<String> lore = new ArrayList<>();
                for (Object o : l) lore.add(Fmt.color(String.valueOf(o)));
                meta.setLore(lore);
            }
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(plugin.hubKey, PersistentDataType.STRING, String.valueOf(action));
            it.setItemMeta(meta);
            int s = ((Number) slot).intValue();
            if (s >= 0 && s < 36) inv.setItem(s, it);
        }
        inv.setHeldItemSlot(0);
        p.updateInventory();
    }

    public String actionOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(plugin.hubKey, PersistentDataType.STRING);
    }

    public void run(Player p, String action) {
        var g = plugin.gui();
        switch (action) {
            case "menu" -> g.hub(p);
            case "queue" -> g.queue(p);
            case "ffa" -> g.lobbies(p);
            case "stats" -> g.stats(p, p);
            case "cosmetics" -> g.cosmetics(p);
            case "friends" -> g.friends(p);
            case "party" -> g.party(p);
            case "kits" -> g.kitBrowser(p);
            case "editor" -> g.editor(p);
            case "spectate" -> g.spectate(p);
            case "duel" -> g.players(p);
            default -> { }
        }
    }
}
