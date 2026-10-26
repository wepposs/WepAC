package dev.weposs.wepac.listener;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

/**
 * Session lifecycle + velocity/teleport exemptions.
 * Any knockback or server-side movement grants a short grace period
 * so movement checks do not false-positive.
 */
public final class ConnectionListener implements Listener {

    private final WepAC plugin;

    public ConnectionListener(WepAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PlayerData data = plugin.data().get(event.getPlayer());
        data.name = event.getPlayer().getName();
        data.joinTime = System.currentTimeMillis();
        data.lastLocation = event.getPlayer().getLocation().clone();
        data.lastTeleport = System.currentTimeMillis();
        data.exemptVelocity(3000L);
        // a relog must not reset progress towards the kick stage
        plugin.vlStore().restore(event.getPlayer(), data);
        // hook the packet sniffer into this player's connection
        if (plugin.packets() != null) {
            plugin.packets().inject(event.getPlayer());
        }
        // "police record": announce watched/wanted players to staff
        plugin.records().announceJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.packets() != null) {
            plugin.packets().eject(event.getPlayer());
        }
        PlayerData data = plugin.data().getIfLoaded(event.getPlayer().getUniqueId());
        if (data != null) {
            plugin.vlStore().snapshot(event.getPlayer(), data);
        }
        plugin.alerts().forget(event.getPlayer().getUniqueId());
        plugin.data().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = plugin.data().get(event.getPlayer());
        data.lastTeleport = System.currentTimeMillis();
        data.lastLocation = event.getTo() != null ? event.getTo().clone() : null;
        // the rollback anchor must move WITH the player: a stale ground position
        // from before /tp would teleport a flagged cheater across the map (A19)
        data.lastGroundLocation = event.getTo() != null ? event.getTo().clone() : null;
        data.airTicks = 0;
        data.hoverTicks = 0;
        data.jesusTicks = 0;
        data.pendingFallDamage = false;
        data.pendingFallDistance = 0;
        data.exemptVelocity(1500L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.data().get(event.getPlayer());
        data.lastRespawn = System.currentTimeMillis();
        data.lastLocation = event.getRespawnLocation().clone();
        data.lastGroundLocation = event.getRespawnLocation().clone(); // rollback anchor (A19)
        data.exemptVelocity(3000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        plugin.data().get(event.getPlayer()).exemptVelocity(2000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        // 800ms covers melee knockback settling (~0.5-0.7s) without blinding
        // the combat checks for longer than the physics requires
        plugin.data().get(event.getPlayer()).exemptVelocity(800L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        PlayerData data = plugin.data().get(player);

        // Movement grace ONLY for causes that actually apply knockback.
        // FIRE_TICK/POISON/WITHER/... do not move the player: granting grace for
        // them was a free bypass - stand in fire and every movement check goes
        // blind (the fire tick renews the immunity every second).
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean knockback = switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, THORNS,
                 BLOCK_EXPLOSION, ENTITY_EXPLOSION, SONIC_BOOM -> true;
            default -> false;
        };
        if (!knockback) return;

        // melee knockback settles within ~0.5-0.7s; explosions launch further.
        // Keep the grace as short as the physics needs - every extra millisecond
        // blinds GroundSpoof/Criticals/PacketFly during PvP, exactly when they matter.
        boolean bigLaunch = cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.SONIC_BOOM
                || cause == EntityDamageEvent.DamageCause.PROJECTILE;
        data.exemptVelocity(bigLaunch ? 1200L : 700L);

        // capture the server-applied knockback next tick for the anti-KB check:
        // "Velocity" modules (CatLean's flagship) absorb 85-90% of it
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isBlocking()) return;
            if (player.hasPermission("wepac.bypass")) return;
            org.bukkit.util.Vector v = player.getVelocity();
            double h = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
            if (h > 0.15) {
                PlayerData d = plugin.data().get(player);
                d.pendingKbLen = h;
                d.pendingKbDirX = v.getX() / h;
                d.pendingKbDirZ = v.getZ() / h;
                org.bukkit.Location at = player.getLocation();
                d.pendingKbStartX = at.getX();
                d.pendingKbStartY = at.getY() + 0.5;
                d.pendingKbStartZ = at.getZ();
                d.pendingKbTime = System.currentTimeMillis();
                d.kbActual = 0;
            }
        });
    }

    /**
     * 1.21 mechanics that launch players WITHOUT a reliable PlayerVelocityEvent:
     * the mace smash-attack bounces the ATTACKER upward (Wind Burst launches him
     * even higher), and pistons push without any velocity packet. Without these
     * timers a legit mace fight trips AirJump/hover/LongJump.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.getInventory().getItemInMainHand().getType() == org.bukkit.Material.MACE) {
            plugin.data().get(attacker).exemptVelocity(1000L);
            plugin.data().get(victim).exemptVelocity(1000L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (event.getEntityType() != org.bukkit.entity.EntityType.WIND_CHARGE) return;
        org.bukkit.Location hit = event.getEntity().getLocation();
        for (org.bukkit.entity.Entity e : hit.getWorld().getNearbyEntities(hit, 4.0, 4.0, 4.0)) {
            if (e instanceof Player p) {
                plugin.data().get(p).exemptVelocity(800L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        exemptNearPiston(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        exemptNearPiston(event.getBlocks());
    }

    private void exemptNearPiston(java.util.List<org.bukkit.block.Block> blocks) {
        for (org.bukkit.block.Block b : blocks) {
            org.bukkit.Location at = b.getLocation();
            for (org.bukkit.entity.Entity e : b.getWorld().getNearbyEntities(at, 3.0, 3.0, 3.0)) {
                if (e instanceof Player p) {
                    plugin.data().get(p).exemptVelocity(600L);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        org.bukkit.event.block.Action action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getItem() != null
                && event.getItem().getType() == org.bukkit.Material.FIREWORK_ROCKET) {
            // legit elytra boost - exempt ElytraFly climb detection briefly
            plugin.data().get(event.getPlayer()).lastFireworkUse = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        PlayerData data = plugin.data().get(event.getPlayer());
        // mining statistics are per-world; don't carry luck/ore-rates across worlds
        data.minedStone = 0;
        data.minedValuable = 0;
        data.xrayWindowStart = 0L;
        // position history is world-specific: stale coords from the old world
        // would corrupt lag-compensated Reach right after the switch
        data.posHistory.clear();
        // movement state is invalid across a world switch
        data.lastLocation = event.getPlayer().getLocation().clone();
        data.lastGroundLocation = null;
        data.lastTeleport = System.currentTimeMillis();
        data.pendingFallDamage = false;
        data.pendingFallDistance = 0;
        data.hoverTicks = 0;
        data.airTicks = 0;
        data.exemptVelocity(1500L);
    }

    // GUI open/close tracking via events (Inventory#getType is version-stable,
    // unlike InventoryView which became an interface on Paper 1.21+).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = plugin.data().get(player);
        data.guiOpen = true;
        try {
            data.guiTypeName = event.getInventory().getType().name();
        } catch (Throwable t) {
            data.guiTypeName = "CONTAINER";
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        plugin.data().get(player).guiOpen = false;
    }
}
