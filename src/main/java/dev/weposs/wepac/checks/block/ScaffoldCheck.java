package dev.weposs.wepac.checks.block;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Locale;

/**
 * Scaffold: placing a block directly under your feet while looking FORWARD.
 * Legit bridging always involves looking down/back (high pitch) or sneaking.
 */
public final class ScaffoldCheck extends Check {

    public ScaffoldCheck(WepAC plugin) {
        super(plugin, CheckType.SCAFFOLD);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        Block placed = event.getBlockPlaced();
        Location loc = player.getLocation();
        Block belowFeet = loc.clone().add(0, -1, 0).getBlock();

        boolean belowPlayer = placed.getX() == belowFeet.getX()
                && placed.getY() == belowFeet.getY()
                && placed.getZ() == belowFeet.getZ();

        PlayerData data = plugin.data().get(player);
        checkImpossibleFace(player, data, event);
        double maxPitch = plugin.config().optDouble(CheckType.SCAFFOLD, "max-pitch", 20.0);
        int bufferNeeded = plugin.config().optInt(CheckType.SCAFFOLD, "buffer", 4);

        if (belowPlayer && loc.getPitch() < maxPitch && data.lastHorizontalSpeed > 0.08) {
            data.scaffoldBuffer++;
            if (data.scaffoldBuffer >= bufferNeeded) {
                // severity 1 (informational): god-bridge/neo-bridge are LEGIT
                // vanilla techniques with exactly this signature. A scaffold
                // tower still escalates via sustained VL and cross-check
                // confirmation, but one long bridge session must not walk an
                // honest builder into the kick stage.
                flag(player, data, String.format(Locale.ROOT,
                        "block below feet while pitch %.0f, speed %.2f b/t (or god-bridge)",
                        loc.getPitch(), data.lastHorizontalSpeed), 1);
                data.scaffoldBuffer = 0;
            }
        } else {
            data.scaffoldBuffer = Math.max(0, data.scaffoldBuffer - 1);
        }
    }

    /**
     * Impossible face: vanilla can only click a block face whose outward
     * normal points towards the player's eye. Scaffold/airplace modules that
     * pick the "best" face without line-of-sight place against faces that are
     * geometrically behind the face plane relative to the eye.
     */
    private void checkImpossibleFace(Player player, PlayerData data, BlockPlaceEvent event) {
        if (!plugin.config().optBool(CheckType.SCAFFOLD, "face-check", true)) return;
        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();
        org.bukkit.block.BlockFace face = against.getFace(placed);
        if (face == null || face == org.bukkit.block.BlockFace.SELF) return;
        // replaceable targets (grass, snow layer, water) report odd faces
        if (!against.getType().isSolid()) return;
        Location eye = player.getEyeLocation();
        double tol = plugin.config().optDouble(CheckType.SCAFFOLD, "face-tolerance", 0.35);
        double planeX = against.getX() + 0.5 + face.getModX() * 0.5;
        double planeY = against.getY() + 0.5 + face.getModY() * 0.5;
        double planeZ = against.getZ() + 0.5 + face.getModZ() * 0.5;
        double side = (eye.getX() - planeX) * face.getModX()
                + (eye.getY() - planeY) * face.getModY()
                + (eye.getZ() - planeZ) * face.getModZ();
        if (side < -tol) {
            data.scaffoldFaceBuffer++;
            if (data.scaffoldFaceBuffer >= plugin.config().optInt(CheckType.SCAFFOLD, "face-buffer", 2)) {
                data.scaffoldFaceBuffer = 0;
                flag(player, data, String.format(Locale.ROOT,
                        "placed on %s face from behind its plane (%.2f blocks)", face, -side), 3);
            }
        } else {
            data.scaffoldFaceBuffer = Math.max(0, data.scaffoldFaceBuffer - 1);
        }
    }
}
