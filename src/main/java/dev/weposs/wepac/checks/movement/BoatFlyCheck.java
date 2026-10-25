package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BoatFly (CatLean BoatFly.kt, KeyzDLC BoatFly side=0.6 up=0.4, Zenith/Winlend/
 * Xrose boat modes): boats obey gravity and float on water - they cannot hover,
 * climb, or exceed rowing speed. Physics is evaluated on every VehicleMoveEvent
 * with per-signal streak buffers.
 *
 * False-positive guards: bubble columns and waterfalls (in-water exempt),
 * launches/explosions (velocity grace), ballistic flight (falling = not hovering),
 * ice boat roads (separate generous cap).
 *
 * Punishment: threshold 3 + severity 5 per confirmed flag + the BOATFLY stage
 * override means an instant tempban on the first confirmed violation - the
 * signal is deterministic, a boat in the sky for 0.3s has no honest explanation.
 */
public final class BoatFlyCheck extends Check {

    // boat uuid -> [hoverStreak, riseStreak, speedStreak]
    private final Map<UUID, int[]> streaks = new HashMap<>();

    public BoatFlyCheck(WepAC plugin) {
        super(plugin, CheckType.BOATFLY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!enabled()) return;
        Entity vehicle = event.getVehicle();
        if (!(vehicle instanceof Boat)) return;
        if (vehicle.isEmpty()) return;
        if (!(vehicle.getPassengers().get(0) instanceof Player player)) return;
        if (player.hasPermission("wepac.bypass")) return;

        PlayerData data = plugin.data().get(player);
        Location from = event.getFrom();
        Location to = event.getTo();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        int[] st = streaks.computeIfAbsent(vehicle.getUniqueId(), k -> new int[3]);
        if (streaks.size() > 1000) streaks.clear(); // safety valve

        // just launched (explosion, collision, drop) - grace
        if (vehicle.getVelocity().lengthSquared() > 0.12) {
            st[0] = 0;
            st[1] = 0;
            st[2] = 0;
            return;
        }

        Block at = to.getBlock();
        Block below = to.clone().add(0, -0.3, 0).getBlock();
        Material atType = at.getType();
        Material belowType = below.getType();

        boolean inWater = atType == Material.WATER || belowType == Material.WATER;
        boolean bubble = atType == Material.BUBBLE_COLUMN || belowType == Material.BUBBLE_COLUMN;
        boolean onIce = belowType.name().contains("ICE");
        boolean onGround = belowType.isSolid() && !inWater && !bubble;
        boolean airborne = !inWater && !bubble && !onGround;

        // A) hovering / flying: airborne and NOT falling (gravity always wins)
        if (airborne && dy > -0.04) {
            st[0]++;
            if (st[0] >= plugin.config().optInt(CheckType.BOATFLY, "hover-events", 6)) {
                flag(player, data, String.format(Locale.ROOT,
                        "boat hovering %d events at y=%.1f (boats obey gravity)", st[0], to.getY()), 5);
                st[0] = 0;
            }
        } else {
            st[0] = Math.max(0, st[0] - 1);
        }

        // B) powered climb outside water (bubble columns legitimately lift boats)
        if (!inWater && !bubble && dy > 0.05) {
            st[1]++;
            if (st[1] >= plugin.config().optInt(CheckType.BOATFLY, "rise-events", 4)) {
                flag(player, data, String.format(Locale.ROOT,
                        "boat climbing +%.2f/event out of water", dy), 5);
                st[1] = 0;
            }
        } else {
            st[1] = Math.max(0, st[1] - 1);
        }

        // C) speed caps per surface (rowing ~0.4 b/t, ice roads up to ~1.6)
        double cap;
        String surface;
        if (onIce) {
            cap = plugin.config().optDouble(CheckType.BOATFLY, "ice-speed-cap", 1.8);
            surface = "ice";
        } else if (inWater) {
            cap = plugin.config().optDouble(CheckType.BOATFLY, "water-speed-cap", 0.55);
            surface = "water";
        } else {
            cap = plugin.config().optDouble(CheckType.BOATFLY, "land-speed-cap", 0.35);
            surface = "land";
        }
        if (hDist > cap) {
            st[2]++;
            if (st[2] >= plugin.config().optInt(CheckType.BOATFLY, "speed-events", 8)) {
                flag(player, data, String.format(Locale.ROOT,
                        "boat speed %.2f b/t > %.2f on %s", hDist, cap, surface), 5);
                st[2] = 0;
            }
        } else {
            st[2] = Math.max(0, st[2] - 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExit(VehicleExitEvent event) {
        streaks.remove(event.getVehicle().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDestroy(VehicleDestroyEvent event) {
        streaks.remove(event.getVehicle().getUniqueId());
    }
}
