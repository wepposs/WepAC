package dev.weposs.wepac.checks.player;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Locale;

/**
 * ChestStealer: emptying containers faster than humanly possible.
 * Legit fast looting (shift-click spam) tops out around 8-10 clicks/sec;
 * stealer modules run at 10-20/sec on a timer. Threshold 14/sec over a
 * 1.2s window with a confirmation buffer keeps honest players safe.
 */
public final class ChestStealerCheck extends Check {

    public ChestStealerCheck(WepAC plugin) {
        super(plugin, CheckType.CHESTSTEALER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!enabled() || bypass(player)) return;

        // Only count clicks while a real container GUI is open. Clicking around your
        // own inventory (no container open) is normal and must not feed the rate.
        // Uses event-tracked GUI state, not player.getOpenInventory() - InventoryView
        // became an interface on Paper 1.21+ and would crash a 1.20-compiled plugin.
        PlayerData data = plugin.data().get(player);
        if (!data.guiOpen) return;

        long now = System.currentTimeMillis();
        long windowMs = plugin.config().optLongOr(CheckType.CHESTSTEALER, "window-ms", 1200L);

        PlayerData.pushTime(data.invClickTimes, now, 60);
        PlayerData.prune(data.invClickTimes, now - windowMs);

        int clicks = data.invClickTimes.size();
        double seconds = windowMs / 1000.0;
        double rate = clicks / seconds;
        double maxRate = plugin.config().optDouble(CheckType.CHESTSTEALER, "max-clicks-per-second", 14.0);

        if (rate > maxRate) {
            data.chestStealBuffer++;
            int needed = plugin.config().optInt(CheckType.CHESTSTEALER, "buffer", 2);
            if (data.chestStealBuffer >= needed) {
                flag(player, data, String.format(Locale.ROOT,
                        "%.1f container clicks/sec over %.1fs (human max ~10)", rate, seconds), 3);
                data.chestStealBuffer = 0;
                data.invClickTimes.clear();
            }
        } else {
            data.chestStealBuffer = Math.max(0, data.chestStealBuffer - 1);
        }
    }
}
