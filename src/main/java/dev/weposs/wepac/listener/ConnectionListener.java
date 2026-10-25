package dev.weposs.wepac.listener;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
        plugin.data().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = plugin.data().get(event.getPlayer());
        data.lastTeleport = System.currentTimeMillis();
        data.lastLocation = event.getTo() != null ? event.getTo().clone() : null;
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
        data.exemptVelocity(3000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        plugin.data().get(event.getPlayer()).exemptVelocity(2000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        plugin.data().get(event.getPlayer()).exemptVelocity(1200L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        PlayerData data = plugin.data().get(player);
        // knockback from any damage source grants movement grace
        data.exemptVelocity(1500L);

        // capture the server-applied knockback next tick for the anti-KB check:
        // "Velocity" modules (CatLean's flagship) absorb 85-90% of it
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isBlocking()) return;
            if (player.hasPermission("wepac.bypass")) return;
            org.bukkit.util.Vector v = player.getVelocity();
            double h = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
            if (h > 0.15) {
                PlayerData d = plugin.data().get(player);
                d.pendingKbLen = h;
                d.pendingKbTime = System.currentTimeMillis();
                d.kbActual = 0;
            }
        });
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
