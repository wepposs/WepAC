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
 * override means an instant KICK on the first confirmed violation (ban follows
 * via the record system on recidivism) - the signal is near-deterministic, but
 * ballistic arcs off ramps are hard to model perfectly and a first-offense ban
 * is unrecoverable if one slips through.
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
        // snapshot the passenger list once: a passenger could exit between the
        // isEmpty() check and get(0), which would throw IndexOutOfBoundsException
        java.util.List<Entity> passengers = vehicle.getPassengers();
        if (passengers.isEmpty()) return;
        if (!(passengers.get(0) instanceof Player player)) return;
        if (player.hasPermission("wepac.bypass")) return;
        // attribution: with two riders we cannot know who controls (and who
        // cheats) - only judge single-player boats
        int riders = 0;
        for (Entity e : passengers) {
            if (e instanceof Player) riders++;
        }
        if (riders != 1) return;

        PlayerData data = plugin.data().get(player);
        Location from = event.getFrom();
        Location to = event.getTo();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        int[] st = streaks.computeIfAbsent(vehicle.getUniqueId(), k -> new int[3]);
        if (streaks.size() > 1000) streaks.clear(); // safety valve

        // Launch grace. The old threshold (|v|^2 > 0.12, i.e. |v| > 0.35 b/t)
        // matched NORMAL rowing (~0.3-0.45) and ice roads (1.5+), so the speed
        // cap was never evaluated exactly when it mattered, and a velocity-driven
        // boat-fly (KeyzDLC up=0.4) lived inside the grace forever. Ballistic
        // hover/rise needs |v| > 1.0; only a real launch (|v| > 2.0: explosion,
        // cannon) excuses the speed caps.
        double vLen2 = vehicle.getVelocity().lengthSquared();
        if (vLen2 > 4.0) {
            st[0] = 0;
            st[1] = 0;
            st[2] = 0;
            return;
        }
        if (vLen2 > 1.0) {
            st[0] = 0;
            st[1] = 0;
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

        // A) hovering / flying: airborne, NOT falling (gravity always wins) and
        // nearly stationary horizontally - the apex of a small ballistic arc
        // still carries horizontal speed, a hover module does not
        if (airborne && dy > -0.04 && hDist < 0.5) {
            st[0]++;
            if (st[0] >= plugin.config().optInt(CheckType.BOATFLY, "hover-events", 6)) {
                flag(player, data, String.format(Locale.ROOT,
                        "boat hovering %d events at y=%.1f (boats obey gravity)", st[0], to.getY()), 5);
                st[0] = 0;
            }
        } else {
            st[0] = Math.max(0, st[0] - 1);
        }

        // B) powered climb outside water (bubble columns legitimately lift boats).
        // A9: require LOW horizontal speed too - the apex of a ballistic arc off
        // an ice ramp has dy > 0.05 for 4+ events at high speed and is a legit
        // stunt; a hover-climb module rises near-vertically in place.
        if (!inWater && !bubble && dy > 0.05 && hDist < 0.6) {
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
