package dev.duelforge.kit;

import dev.duelforge.DuelForge;
import dev.duelforge.util.Fmt;
import dev.duelforge.util.Persist;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads kits.yml and stores per-player kit layouts (layouts.yml). */
public final class KitManager {
    private final DuelForge plugin;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Map<Integer, ItemStack>>> layouts = new HashMap<>();
    private final File layoutFile;

    public KitManager(DuelForge plugin) {
        this.plugin = plugin;
        this.layoutFile = new File(plugin.getDataFolder(), "layouts.yml");
    }

    public static PotionEffectType effectType(String name) {
        if (name == null) return null;
        return PotionEffectType.getByKey(NamespacedKey.minecraft(name.toLowerCase()));
    }

    public static Enchantment enchant(String name) {
        if (name == null) return null;
        return Enchantment.getByKey(NamespacedKey.minecraft(name.toLowerCase()));
    }

    public void load() {
        kits.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "kits.yml"));
        boolean unbreakable = cfg.getBoolean("unbreakable-items", true);
        ConfigurationSection root = cfg.getConfigurationSection("kits");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    kits.put(id.toLowerCase(), parseKit(id.toLowerCase(), s, unbreakable));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to load kit '" + id + "': " + ex.getMessage());
                }
            }
        }
        loadCustom();
        plugin.getLogger().info("Loaded " + kits.size() + " kits.");
        loadLayouts();
    }

    private Kit parseKit(String id, ConfigurationSection s, boolean unbreakable) {
        Kit k = new Kit(id);
        k.displayName = s.getString("display-name", "&f" + id);
        Material icon = Material.matchMaterial(s.getString("icon", "IRON_SWORD"));
        k.icon = icon != null ? icon : Material.IRON_SWORD;
        k.description = s.getStringList("description");
        k.enabled = s.getBoolean("enabled", true);
        k.permission = s.getString("permission", "");
        k.themes = s.getStringList("themes");
        ConfigurationSection st = s.getConfigurationSection("settings");
        if (st != null) {
            k.naturalRegen = st.getBoolean("natural-regen", true);
            k.hunger = st.getBoolean("hunger", false);
            k.fallDamage = st.getBoolean("fall-damage", true);
            k.enderPearlCooldown = st.getInt("ender-pearl-cooldown", 15);
        }
        for (Map<?, ?> m : s.getMapList("items")) {
            ItemStack it = parseItem(m, unbreakable);
            if (it == null) continue;
            if (m.get("ability") != null) k.abilities = true;
            Object slot = m.get("slot");
            if (slot instanceof Number n) {
                int i = n.intValue();
                if (i >= 0 && i < 36) k.inventory.put(i, it);
            } else if (slot != null) {
                k.equipment.put(slot.toString().toLowerCase(), it);
            }
        }
        for (Map<?, ?> m : s.getMapList("effects")) {
            PotionEffectType type = effectType(String.valueOf(m.get("type")));
            if (type == null) continue;
            int level = m.get("level") instanceof Number n ? n.intValue() : 1;
            k.effects.add(new PotionEffect(type, 1_000_000, Math.max(0, level - 1), false, false, true));
        }
        return k;
    }

    private ItemStack parseItem(Map<?, ?> raw, boolean defaultUnbreakable) {
        if (raw.get("ability") != null && !plugin.abilities().enabled()) return null;
        Material mat = Material.matchMaterial(String.valueOf(raw.get("item")));
        if (mat == null) {
            plugin.getLogger().warning("Unknown material in kits.yml: " + raw.get("item"));
            return null;
        }
        int amount = raw.get("amount") instanceof Number n ? n.intValue() : 1;
        ItemStack it = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        String ability = raw.get("ability") != null ? raw.get("ability").toString() : null;
        if (raw.get("name") != null) meta.setDisplayName(Fmt.color(raw.get("name").toString()));
        List<String> lore = new ArrayList<>();
        if (raw.get("lore") instanceof List<?> l) for (Object o : l) lore.add(Fmt.color(String.valueOf(o)));

        if (raw.get("enchants") instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> e : em.entrySet()) {
                Enchantment ench = enchant(String.valueOf(e.getKey()));
                if (ench == null) {
                    plugin.getLogger().warning("Unknown enchantment in kits.yml: " + e.getKey());
                    continue;
                }
                int lvl = e.getValue() instanceof Number n ? n.intValue() : 1;
                meta.addEnchant(ench, lvl, true);
            }
        }
        boolean unb = raw.get("unbreakable") instanceof Boolean b ? b : defaultUnbreakable;
        if (unb && mat.getMaxDurability() > 0) meta.setUnbreakable(true);

        if (meta instanceof LeatherArmorMeta lm && raw.get("color") != null) {
            lm.setColor(dev.duelforge.util.Fx.hex(raw.get("color").toString(), Color.WHITE));
        }
        if (meta instanceof PotionMeta pm && raw.get("potion") instanceof Map<?, ?> pmap) {
            PotionEffectType type = effectType(String.valueOf(pmap.get("effect")));
            if (type != null) {
                int sec = pmap.get("seconds") instanceof Number n ? n.intValue() : 30;
                int lvl = pmap.get("level") instanceof Number n ? n.intValue() : 1;
                pm.addCustomEffect(new PotionEffect(type, Math.max(1, sec * 20), Math.max(0, lvl - 1)), true);
                pm.setColor(Color.fromRGB(0xCC3366));
            }
        }
        if (ability != null) {
            meta.getPersistentDataContainer().set(plugin.abilityKey, PersistentDataType.STRING, ability);
            if (raw.get("name") == null) meta.setDisplayName(Fmt.color(plugin.abilities().name(ability)));
            lore.add(Fmt.color(plugin.abilities().description(ability)));
            lore.add(Fmt.color("&7Right-click to use &8| &7Cooldown: &f" + plugin.abilities().cooldownText(ability) + "s"));
        }
        if (!lore.isEmpty()) meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    public Kit get(String id) {
        return id == null ? null : kits.get(id.toLowerCase());
    }

    public Collection<Kit> all() {
        return kits.values();
    }

    public List<Kit> enabled() {
        List<Kit> out = new ArrayList<>();
        for (Kit k : kits.values()) if (k.enabled) out.add(k);
        return out;
    }

    public Kit defaultKit() {
        List<Kit> e = enabled();
        return e.isEmpty() ? null : e.get(0);
    }

    public boolean canUse(Player p, Kit k) {
        return k.permission == null || k.permission.isEmpty() || p.hasPermission(k.permission);
    }

    // ---------- admin-made kits (custom-kits.yml) ----------

    private File customFile() {
        return new File(plugin.getDataFolder(), "custom-kits.yml");
    }

    private void loadCustom() {
        ConfigurationSection root = YamlConfiguration.loadConfiguration(customFile()).getConfigurationSection("kits");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null || kits.containsKey(id.toLowerCase())) continue;
            Kit k = new Kit(id.toLowerCase());
            k.custom = true;
            k.displayName = s.getString("display-name", "&f" + id);
            Material icon = Material.matchMaterial(s.getString("icon", "IRON_SWORD"));
            k.icon = icon != null ? icon : Material.IRON_SWORD;
            k.description = s.getStringList("description");
            k.enabled = s.getBoolean("enabled", true);
            k.naturalRegen = s.getBoolean("settings.natural-regen", true);
            k.hunger = s.getBoolean("settings.hunger", false);
            k.fallDamage = s.getBoolean("settings.fall-damage", true);
            k.enderPearlCooldown = s.getInt("settings.ender-pearl-cooldown", 15);
            ConfigurationSection items = s.getConfigurationSection("items");
            if (items != null) {
                for (String slot : items.getKeys(false)) {
                    ItemStack it = items.getItemStack(slot);
                    try {
                        int i = Integer.parseInt(slot);
                        if (it != null && i >= 0 && i < 36) k.inventory.put(i, it);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            ConfigurationSection eq = s.getConfigurationSection("equipment");
            if (eq != null) {
                for (String name : eq.getKeys(false)) {
                    ItemStack it = eq.getItemStack(name);
                    if (it != null) k.equipment.put(name, it);
                }
            }
            for (Map<?, ?> m : s.getMapList("effects")) {
                PotionEffectType type = effectType(String.valueOf(m.get("type")));
                if (type == null) continue;
                int level = m.get("level") instanceof Number n ? n.intValue() : 1;
                k.effects.add(new PotionEffect(type, 1_000_000, Math.max(0, level - 1), false, false, true));
            }
            kits.put(k.id, k);
        }
    }

    public void saveCustom() {
        YamlConfiguration c = new YamlConfiguration();
        c.createSection("kits");
        for (Kit k : kits.values()) {
            if (!k.custom) continue;
            String p = "kits." + k.id + ".";
            c.set(p + "display-name", k.displayName);
            c.set(p + "icon", k.icon.name());
            c.set(p + "description", k.description);
            c.set(p + "enabled", k.enabled);
            c.set(p + "settings.natural-regen", k.naturalRegen);
            c.set(p + "settings.hunger", k.hunger);
            c.set(p + "settings.fall-damage", k.fallDamage);
            c.set(p + "settings.ender-pearl-cooldown", k.enderPearlCooldown);
            for (Map.Entry<Integer, ItemStack> e : k.inventory.entrySet()) c.set(p + "items." + e.getKey(), e.getValue());
            for (Map.Entry<String, ItemStack> e : k.equipment.entrySet()) c.set(p + "equipment." + e.getKey(), e.getValue());
            List<Map<String, Object>> eff = new ArrayList<>();
            for (PotionEffect e : k.effects) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", e.getType().getKey().getKey());
                m.put("level", e.getAmplifier() + 1);
                eff.add(m);
            }
            c.set(p + "effects", eff);
        }
        Persist.save(plugin, customFile(), c, true);
    }

    private boolean isHubItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(plugin.hubKey, PersistentDataType.STRING);
    }

    /** Creates a kit from the admin's current inventory, armor and off-hand. */
    public Kit createCustom(String id, Player p) {
        Kit k = new Kit(id.toLowerCase());
        k.custom = true;
        k.displayName = "&f&l" + Fmt.pretty(id);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack it = p.getInventory().getItem(slot);
            if (it != null && it.getType() != Material.AIR && !isHubItem(it)) k.inventory.put(slot, it.clone());
        }
        ItemStack[] armor = {p.getInventory().getHelmet(), p.getInventory().getChestplate(),
                p.getInventory().getLeggings(), p.getInventory().getBoots()};
        String[] names = {"helmet", "chestplate", "leggings", "boots"};
        for (int i = 0; i < 4; i++) if (armor[i] != null && armor[i].getType() != Material.AIR) k.equipment.put(names[i], armor[i].clone());
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getType() != Material.AIR) k.equipment.put("offhand", off.clone());
        ItemStack hand = p.getInventory().getItemInMainHand();
        k.icon = hand.getType() != Material.AIR ? hand.getType() : (k.inventory.isEmpty() ? Material.IRON_SWORD : k.inventory.values().iterator().next().getType());
        kits.put(k.id, k);
        saveCustom();
        return k;
    }

    public boolean deleteCustom(String id) {
        Kit k = kits.get(id.toLowerCase());
        if (k == null || !k.custom) return false;
        kits.remove(k.id);
        saveCustom();
        return true;
    }

    // ---------- layouts ----------

    private void loadLayouts() {
        layouts.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(layoutFile);
        ConfigurationSection players = c.getConfigurationSection("players");
        if (players == null) return;
        for (String uid : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(uid);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection ps = players.getConfigurationSection(uid);
            if (ps == null) continue;
            Map<String, Map<Integer, ItemStack>> perKit = new HashMap<>();
            for (String kitId : ps.getKeys(false)) {
                ConfigurationSection ks = ps.getConfigurationSection(kitId);
                if (ks == null) continue;
                Map<Integer, ItemStack> map = new LinkedHashMap<>();
                for (String slot : ks.getKeys(false)) {
                    ItemStack it = ks.getItemStack(slot);
                    try {
                        if (it != null) map.put(Integer.parseInt(slot), it);
                    } catch (NumberFormatException ignored) {
                    }
                }
                perKit.put(kitId, map);
            }
            layouts.put(id, perKit);
        }
    }

    /** The player's saved layout for the kit, or null when none / no longer valid. */
    public Map<Integer, ItemStack> layout(UUID id, String kitId) {
        Map<String, Map<Integer, ItemStack>> m = layouts.get(id);
        Kit kit = get(kitId);
        if (m == null || kit == null) return null;
        Map<Integer, ItemStack> l = m.get(kitId);
        return l != null && kit.validLayout(l) ? l : null;
    }

    public Map<Integer, ItemStack> layoutOrDefault(UUID id, Kit kit) {
        Map<Integer, ItemStack> l = layout(id, kit.id);
        return new LinkedHashMap<>(l != null ? l : kit.inventory);
    }

    public void setLayout(UUID id, String kitId, Map<Integer, ItemStack> layout) {
        layouts.computeIfAbsent(id, k -> new HashMap<>()).put(kitId, new LinkedHashMap<>(layout));
        saveLayouts(true);
    }

    public void resetLayout(UUID id, String kitId) {
        Map<String, Map<Integer, ItemStack>> m = layouts.get(id);
        if (m != null) m.remove(kitId);
        saveLayouts(true);
    }

    public void saveLayouts(boolean async) {
        YamlConfiguration c = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Map<Integer, ItemStack>>> pe : layouts.entrySet()) {
            for (Map.Entry<String, Map<Integer, ItemStack>> ke : pe.getValue().entrySet()) {
                for (Map.Entry<Integer, ItemStack> se : ke.getValue().entrySet()) {
                    c.set("players." + pe.getKey() + "." + ke.getKey() + "." + se.getKey(), se.getValue());
                }
            }
        }
        Persist.save(plugin, layoutFile, c, async);
    }
}
