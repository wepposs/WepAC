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
        double maxPitch = plugin.config().optDouble(CheckType.SCAFFOLD, "max-pitch", 25.0);
        int bufferNeeded = plugin.config().optInt(CheckType.SCAFFOLD, "buffer", 4);

        if (belowPlayer && loc.getPitch() < maxPitch && data.lastHorizontalSpeed > 0.08) {
            data.scaffoldBuffer++;
            if (data.scaffoldBuffer >= bufferNeeded) {
                flag(player, data, String.format(Locale.ROOT,
                        "block below feet while pitch %.0f, speed %.2f b/t",
                        loc.getPitch(), data.lastHorizontalSpeed), 2);
                data.scaffoldBuffer = 0;
            }
        } else {
            data.scaffoldBuffer = Math.max(0, data.scaffoldBuffer - 1);
        }
    }
}
