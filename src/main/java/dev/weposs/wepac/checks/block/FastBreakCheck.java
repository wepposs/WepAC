package dev.weposs.wepac.checks.block;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.util.Compat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * FastBreak / Nuker:
 *  - minimum interval between breaks for blocks that are not instant-break,
 *  - break radius: blocks further than ~4.75 (+ping compensation) from the
 *    eyes cannot be reached (Nuker breaks everything around).
 *
 * Honest-player guards: soft blocks (hardness &lt; 1.0) are ignored, Haste is
 * ignored, and high Efficiency tools insta-mining soft stone is ignored.
 */
public final class FastBreakCheck extends Check {

    public FastBreakCheck(WepAC plugin) {
        super(plugin, CheckType.FASTBREAK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        Material type = event.getBlock().getType();
        PlayerData data = plugin.data().get(player);
        long now = System.currentTimeMillis();

        // Nuker radius check (applies to every block, including soft ones)
        double dist = player.getEyeLocation().toVector()
                .distance(event.getBlock().getBoundingBox().getCenter());
        double maxDist = 4.75 + Math.min(0.75, player.getPing() / 1000.0);
        if (dist > maxDist) {
            flag(player, data, String.format(Locale.ROOT,
                    "broke %s at %.2f blocks (max %.2f)", type.name(), dist, maxDist), 3);
        }

        // instant-break blocks (plants, torches, glass...) and soft blocks are ignored
        if (type.getHardness() < 1.0f) {
            PlayerData.pushTime(data.breakTimes, now, PlayerData.BLOCK_CAP);
            return;
        }
        if (Compat.hasEffect(player, Compat.HASTE)) {
            PlayerData.pushTime(data.breakTimes, now, PlayerData.BLOCK_CAP);
            return;
        }
        // Efficiency V on a netherite/diamond tool insta-mines stone-grade blocks
        ItemStack tool = player.getInventory().getItemInMainHand();
        int efficiency = Compat.enchantLevel(tool, Compat.EFFICIENCY);
        if (efficiency >= 5 && type.getHardness() < 3.0f) {
            PlayerData.pushTime(data.breakTimes, now, PlayerData.BLOCK_CAP);
            return;
        }
        if (efficiency >= 1 && type.getHardness() < 1.5f) {
            PlayerData.pushTime(data.breakTimes, now, PlayerData.BLOCK_CAP);
            return;
        }

        long minInterval = plugin.config().optLongOr(CheckType.FASTBREAK, "min-interval-ms", 90L);
        Long last = data.breakTimes.peekLast();
        PlayerData.pushTime(data.breakTimes, now, PlayerData.BLOCK_CAP);
        if (last == null) return;

        long interval = now - last;
        if (interval < minInterval) {
            flag(player, data, String.format(Locale.ROOT,
                    "%s in %dms (min %dms)", type.name(), interval, minInterval));
        }
    }
}
