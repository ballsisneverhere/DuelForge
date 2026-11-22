package dev.duelforge.listener;

import dev.duelforge.DuelForge;
import dev.duelforge.gui.Menu;
import dev.duelforge.match.Match;
import dev.duelforge.match.Match.State;
import dev.duelforge.match.MatchManager;
import dev.duelforge.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Combat rules, arena boundaries, anti-exploit protection and block tracking. */
public final class MatchListener implements Listener {
    private static final Set<Material> BLOCKED_BLOCKS = Set.of(
            Material.CRAFTING_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.ENDER_CHEST, Material.GRINDSTONE, Material.SMITHING_TABLE,
            Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.STONECUTTER, Material.BEACON);

    private final DuelForge plugin;
    private final MatchManager mm;
    private final Map<UUID, Long> warned = new HashMap<>();

    public MatchListener(DuelForge plugin) {
        this.plugin = plugin;
        this.mm = plugin.matches();
    }

    private Match fighting(Player p) {
        Match m = mm.get(p);
        return m != null && m.inArena() ? m : null;
    }

    private Player attackerOf(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // =====================================================================
    // damage / death
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Player attacker = e instanceof EntityDamageByEntityEvent be ? attackerOf(be) : null;
        Match am = attacker != null ? fighting(attacker) : null;

        if (!(e.getEntity() instanceof Player victim)) {
            if (am != null) e.setCancelled(true); // fighters can't hurt outsiders / mobs
            return;
        }
        Match vm = fighting(victim);
        if (vm == null) {
            if (am != null) e.setCancelled(true); // can't hit people outside the match
            else if (attacker != null && attacker != victim && plugin.getConfig().getBoolean("world-protection.disable-pvp", true)) {
                e.setCancelled(true); // no PvP outside matches
            }
            return;
        }
        UUID vid = victim.getUniqueId();
        if (vm.state != State.ACTIVE || !vm.isAlive(vid) || vm.isProtected(vid) || vm.dying.contains(vid)) {
            e.setCancelled(true);
            return;
        }
        if (attacker != null && attacker != victim && (am != vm || !vm.isAlive(attacker.getUniqueId()))) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && !vm.kit.fallDamage) {
            e.setCancelled(true);
            return;
        }
        if (e.isCancelled()) return;

        Player realAttacker = attacker != null && attacker != victim ? attacker : null;
        if (realAttacker != null) {
            vm.lastDamager.put(vid, realAttacker.getUniqueId());
            vm.lastDamageTime.put(vid, System.currentTimeMillis());
            Fx.sound(realAttacker, plugin.getConfig().getString("sounds.hit", ""), 0.6f, 1.4f);
            Fx.particle(victim.getLocation().add(0, 1, 0), plugin.getConfig().getString("effects.hit.particle", "CRIT"),
                    plugin.getConfig().getInt("effects.hit.count", 6), 0.3);
        }

        double hp = victim.getHealth() + victim.getAbsorptionAmount();
        if (e.getFinalDamage() >= hp) {
            e.setCancelled(true);
            vm.dying.add(vid);
            Player killer = realAttacker != null ? realAttacker : mm.recentDamager(vm, vid);
            String cause = causeName(e.getCause());
            Bukkit.getScheduler().runTask(plugin, () -> mm.handleDeath(vm, victim, killer, cause));
        }
    }

    private String causeName(EntityDamageEvent.DamageCause c) {
        return dev.duelforge.util.Fmt.pretty(c.name());
    }

    /** Fallback for deaths that bypass damage events (e.g. /kill): never drop items, never show the death screen. */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Match m = fighting(p);
        if (m == null) return;
        e.getDrops().clear();
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.setDeathMessage(null);
        Player killer = mm.recentDamager(m, p.getUniqueId());
        m.dying.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isDead()) p.spigot().respawn();
            mm.handleDeath(m, p, killer, "Unknown");
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Match m = fighting(e.getPlayer());
        if (m == null) {
            Location lobby = plugin.lobby();
            if (lobby != null && plugin.getConfig().getBoolean("lobby.respawn-at-lobby", true)
                    && !e.isBedSpawn() && !e.isAnchorSpawn()) {
                e.setRespawnLocation(lobby);
            }
            return;
        }
        e.setRespawnLocation(m.isAlive(e.getPlayer().getUniqueId()) ? m.arena.spawn(0) : m.arena.spectatorSpawn());
    }

    @EventHandler
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = fighting(p);
        if (m == null) return;
        EntityRegainHealthEvent.RegainReason r = e.getRegainReason();
        if (!m.kit.naturalRegen && (r == EntityRegainHealthEvent.RegainReason.SATIATED || r == EntityRegainHealthEvent.RegainReason.REGEN)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = fighting(p);
        if (m == null) {
            if (plugin.getConfig().getBoolean("lobby.no-hunger", true)) e.setCancelled(true);
            return;
        }
        if (!m.kit.hunger || m.state != State.ACTIVE) e.setCancelled(true);
    }

    // =====================================================================
    // movement / boundaries
    // =====================================================================

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Match m = fighting(p);
        if (m == null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        UUID id = p.getUniqueId();

        if (m.state == State.COUNTDOWN && m.isAlive(id)) {
            if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
                Location l = from.clone();
                l.setYaw(to.getYaw());
                l.setPitch(to.getPitch());
                e.setTo(l);
            }
            return;
        }
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        if (m.arena.contains(to)) return;

        boolean alive = m.isAlive(id);
        if (alive && m.state == State.ACTIVE && to.getY() < m.arena.minY
                && plugin.getConfig().getBoolean("combat.void-kills", true)) {
            if (m.dying.add(id)) {
                Player killer = mm.recentDamager(m, id);
                Bukkit.getScheduler().runTask(plugin, () -> mm.handleDeath(m, p, killer, "The Void"));
            }
            return;
        }
        Location back;
        if (m.arena.contains(from)) {
            back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
        } else {
            back = alive ? m.arena.spawn(0) : m.arena.spectatorSpawn();
        }
        e.setTo(back);
        p.setVelocity(new Vector(0, 0, 0));
        long now = System.currentTimeMillis();
        Long last = warned.get(id);
        if (last == null || now - last > 1500) {
            warned.put(id, now);
            plugin.messages().actionBar(p, plugin.messages().get("out-of-bounds"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Match m = fighting(p);
        if (m == null || mm.isTeleporting(p.getUniqueId())) return;
        Location to = e.getTo();
        if (to == null || !m.arena.contains(to)) {
            e.setCancelled(true);
            plugin.messages().actionBar(p, plugin.messages().get("out-of-bounds"));
        }
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent e) {
        if (fighting(e.getPlayer()) != null) e.setCancelled(true);
    }

    // =====================================================================
    // items / inventory exploits
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Match m = fighting(e.getPlayer());
        if (m != null) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && fighting(p) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onPickupArrow(PlayerPickupArrowEvent e) {
        if (fighting(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Match m = fighting(p);
        if (m == null) return;
        if (e.getInventory().getHolder() instanceof Menu) return;
        if (!m.isAlive(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        var type = e.getSlotType();
        if (type == org.bukkit.event.inventory.InventoryType.SlotType.CRAFTING
                || type == org.bukkit.event.inventory.InventoryType.SlotType.RESULT
                || type == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR
                || type == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE) {
            e.setCancelled(true);
            return;
        }
        switch (e.getClick()) {
            case DROP, CONTROL_DROP, CREATIVE -> e.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || fighting(p) == null) return;
        if (e.getInventory().getHolder() instanceof Menu) return;
        if (e.getInventory().getType() == InventoryType.CRAFTING) {
            for (int slot : e.getRawSlots()) {
                if (slot < 9) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (fighting(p) == null || p.hasPermission("duelforge.bypass.commands")) return;
        if (!plugin.getConfig().getBoolean("combat.block-commands", true)) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        for (String allowed : plugin.getConfig().getStringList("combat.allowed-commands")) {
            if (allowed.equalsIgnoreCase(cmd)) return;
        }
        e.setCancelled(true);
        plugin.messages().send(p, "blocked-command");
    }

    // =====================================================================
    // interaction / abilities
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Match m = fighting(p);
        if (m == null) return;
        UUID id = p.getUniqueId();
        if (m.state != State.ACTIVE || !m.isAlive(id)) {
            e.setCancelled(true);
            return;
        }
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Block b = e.getClickedBlock();
            if (BLOCKED_BLOCKS.contains(b.getType()) || b.getType().name().endsWith("_BED")
                    || b.getState() instanceof InventoryHolder) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String ability = it.getItemMeta().getPersistentDataContainer().get(plugin.abilityKey, PersistentDataType.STRING);
        if (ability != null) {
            e.setCancelled(true);
            plugin.abilities().use(p, m, ability, it);
        }
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player p) {
            Match m = fighting(p);
            if (m != null && m.kit.enderPearlCooldown > 0) p.setCooldown(Material.ENDER_PEARL, m.kit.enderPearlCooldown * 20);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!plugin.getConfig().getBoolean("combat.remove-arrows", true)) return;
        if (e.getEntity() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player p && fighting(p) != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (arrow.isValid()) arrow.remove();
            });
        }
    }

    // =====================================================================
    // block tracking (so every arena can be restored)
    // =====================================================================

    private void handleBlock(Player p, Block b, BlockState before, Cancellable c) {
        Match m = mm.get(p);
        if (m == null || !m.inArena()) {
            if (mm.matchAt(b) != null) {
                c.setCancelled(true); // someone else's match is using this arena
                return;
            }
            if (worldProtected(p)) {
                c.setCancelled(true);
                plugin.messages().actionBar(p, plugin.messages().get("protection.no-build"));
            }
            return;
        }
        int max = plugin.getConfig().getInt("arena.max-tracked-blocks", 30000);
        if (!m.canBuild(p.getUniqueId(), b) || m.changed.size() >= max) {
            c.setCancelled(true);
            return;
        }
        m.record(before);
    }

    /** Outside of a running duel, blocks are protected unless the player is in admin build mode. */
    private boolean worldProtected(Player p) {
        return plugin.getConfig().getBoolean("world-protection.enabled", true) && !plugin.isBuilder(p.getUniqueId());
    }

    private boolean explosionsProtected() {
        return plugin.getConfig().getBoolean("world-protection.enabled", true)
                && plugin.getConfig().getBoolean("world-protection.explosions", true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        handleBlock(e.getPlayer(), e.getBlock(), e.getBlockReplacedState(), e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        handleBlock(e.getPlayer(), e.getBlock(), e.getBlock().getState(), e);
        if (!e.isCancelled() && fighting(e.getPlayer()) != null) {
            e.setDropItems(false);
            e.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        handleBlock(e.getPlayer(), e.getBlock(), e.getBlock().getState(), e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent e) {
        handleBlock(e.getPlayer(), e.getBlock(), e.getBlock().getState(), e);
    }

    @EventHandler
    public void onFlow(BlockFromToEvent e) {
        Match m = mm.matchAt(e.getToBlock());
        if (m == null) return;
        if (m.changed.size() >= plugin.getConfig().getInt("arena.max-tracked-blocks", 30000)) {
            e.setCancelled(true);
            return;
        }
        m.record(e.getToBlock().getState());
    }

    @EventHandler
    public void onBurn(BlockBurnEvent e) {
        Match m = mm.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock().getState());
    }

    @EventHandler
    public void onIgnite(BlockIgniteEvent e) {
        Match m = mm.matchAt(e.getBlock());
        if (m == null) return;
        if (!m.arena.allowBuilding) e.setCancelled(true);
        else m.record(e.getBlock().getState());
    }

    @EventHandler
    public void onSpread(BlockSpreadEvent e) {
        Match m = mm.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock().getState());
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        Match m = mm.matchAt(e.getLocation());
        if (m == null) {
            if (explosionsProtected()) e.blockList().clear();
            return;
        }
        filterExplosion(m, e.blockList());
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        Match m = mm.matchAt(e.getBlock());
        if (m == null) {
            if (explosionsProtected()) e.blockList().clear();
            return;
        }
        filterExplosion(m, e.blockList());
    }

    private void filterExplosion(Match m, java.util.List<Block> blocks) {
        if (!m.arena.allowBuilding) {
            blocks.clear();
            return;
        }
        int max = plugin.getConfig().getInt("arena.max-tracked-blocks", 30000);
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block b = it.next();
            if (!m.arena.containsBlock(b) || m.changed.size() >= max) it.remove();
            else m.record(b.getState());
        }
    }

    // =====================================================================
    // join / quit
    // =====================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            mm.restorePending(p);
            Location lobby = plugin.lobby();
            if (lobby != null && plugin.getConfig().getBoolean("lobby.teleport-on-join", true) && !mm.inMatch(p.getUniqueId())) {
                p.teleport(lobby);
            }
        }, 3L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        warned.remove(e.getPlayer().getUniqueId());
        plugin.removeBuilder(e.getPlayer().getUniqueId());
        mm.onQuit(e.getPlayer());
    }
}
