package dev.weposs.wepac.checks.block;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Locale;

/** Minimum interval between block placements. */
public final class FastPlaceCheck extends Check {

    public FastPlaceCheck(WepAC plugin) {
        super(plugin, CheckType.FASTPLACE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        PlayerData data = plugin.data().get(player);
        long now = System.currentTimeMillis();
        long minInterval = plugin.config().optLongOr(CheckType.FASTPLACE, "min-interval-ms", 70L);

        Long last = data.placeTimes.peekLast();
        PlayerData.pushTime(data.placeTimes, now, PlayerData.BLOCK_CAP);
        if (last == null) return;

        long interval = now - last;
        if (interval < minInterval) {
            flag(player, data, String.format(Locale.ROOT,
                    "%s placed %dms after previous (min %dms)",
                    event.getBlockPlaced().getType().name(), interval, minInterval));
        }
    }
}
