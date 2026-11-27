package dev.duelforge.kit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A PvP kit: equipment, inventory layout, passive effects and rule settings. */
public final class Kit {
    public final String id;
    public String displayName;
    public Material icon = Material.IRON_SWORD;
    public List<String> description = new ArrayList<>();
    public boolean enabled = true;
    /** Created in-game with /duel admin kit create (stored in custom-kits.yml). */
    public boolean custom;
    /** True if the kit contains at least one ability item. */
    public boolean abilities;
    /** If not empty, auto-built maps for this kit are picked from these themes only. */
    public List<String> themes = new ArrayList<>();
    public String permission = "";

    public final Map<Integer, ItemStack> inventory = new LinkedHashMap<>();
    public final Map<String, ItemStack> equipment = new HashMap<>();
    public final List<PotionEffect> effects = new ArrayList<>();

    public boolean naturalRegen = true;
    public boolean hunger = false;
    public boolean fallDamage = true;
    public int enderPearlCooldown = 15;

    public Kit(String id) {
        this.id = id;
        this.displayName = "&f" + id;
    }

    private static ItemStack copy(ItemStack i) {
        return i == null ? null : i.clone();
    }

    /** Clears the player and gives this kit (using a custom hotbar/inventory layout when supplied). */
    public void apply(Player p, Map<Integer, ItemStack> layout) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        Map<Integer, ItemStack> src = layout != null ? layout : inventory;
        for (Map.Entry<Integer, ItemStack> e : src.entrySet()) {
            int slot = e.getKey();
            if (slot >= 0 && slot < 36 && e.getValue() != null) inv.setItem(slot, e.getValue().clone());
        }
        inv.setHelmet(copy(equipment.get("helmet")));
        inv.setChestplate(copy(equipment.get("chestplate")));
        inv.setLeggings(copy(equipment.get("leggings")));
        inv.setBoots(copy(equipment.get("boots")));
        inv.setItemInOffHand(copy(equipment.get("offhand")));
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        for (PotionEffect e : effects) p.addPotionEffect(e);
        p.updateInventory();
    }

    /** A layout is valid only if it contains exactly the same items as the default inventory. */
    public boolean validLayout(Map<Integer, ItemStack> layout) {
        for (int slot : layout.keySet()) if (slot < 0 || slot > 35) return false;
        return signature(layout.values()).equals(signature(inventory.values()));
    }

    private static Map<String, Integer> signature(Iterable<ItemStack> items) {
        Map<String, Integer> m = new HashMap<>();
        for (ItemStack it : items) {
            if (it == null || it.getType() == Material.AIR) continue;
            ItemStack c = it.clone();
            c.setAmount(1);
            m.merge(c.serialize().toString(), it.getAmount(), Integer::sum);
        }
        return m;
    }
}
