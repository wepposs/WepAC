package dev.weposs.wepac.checks.combat;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Click statistics: max CPS over a rolling second, plus "robotic consistency"
 * (suspiciously low standard deviation of click intervals over many samples).
 */
public final class AutoClickerCheck extends Check {

    public AutoClickerCheck(WepAC plugin) {
        super(plugin, CheckType.AUTOCLICKER);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        long now = System.currentTimeMillis();
        PlayerData data = plugin.data().get(player);
        // one physical click can produce both BLOCK and AIR events; dedupe
        if (!data.clickTimes.isEmpty() && now - data.clickTimes.peekLast() < 25L) return;

        PlayerData.pushTime(data.clickTimes, now, PlayerData.CLICK_CAP);

        // Keep a 3-second window so the consistency check can accumulate enough
        // samples. (A 1s window caps at ~40 clicks after dedupe and could never
        // reach min-samples=45, which made the robotic-click detector dead.)
        PlayerData.prune(data.clickTimes, now - 3000L);

        // CPS over the last 1 second (a sub-window of the 3s buffer).
        // 1.9+ note: raw CPS is a WEAK combat signal now - damage is gated by the
        // attack cooldown, and legit butterfly/drag clickers hit 20+ CPS. The main
        // autoclicker signal is robotic interval consistency (below). The CPS cap
        // only catches physically impossible spam.
        int cps = 0;
        for (Long t : data.clickTimes) {
            if (now - t <= 1000L) cps++;
        }
        int maxCps = plugin.config().optInt(CheckType.AUTOCLICKER, "max-cps", 28);
        if (cps > maxCps) {
            // confirmation buffer: hardcore drag/butterfly clickers legitimately
            // burst 25-30 CPS for short stretches - one burst must not VL
            data.cpsBuffer++;
            if (data.cpsBuffer >= plugin.config().optInt(CheckType.AUTOCLICKER, "cps-buffer", 2)) {
                data.cpsBuffer = 0;
                flag(player, data, cps + " cps > " + maxCps, 3);
            }
        } else {
            data.cpsBuffer = 0;
        }

        // consistency check over the 3s buffer
        int minSamples = plugin.config().optInt(CheckType.AUTOCLICKER, "min-samples", 45);
        double minStd = plugin.config().optDouble(CheckType.AUTOCLICKER, "min-interval-stddev", 12.0);
        List<Long> recent = new ArrayList<>(data.clickTimes);
        if (recent.size() >= minSamples) {
            double[] intervals = new double[recent.size() - 1];
            for (int i = 1; i < recent.size(); i++) {
                intervals[i - 1] = recent.get(i) - recent.get(i - 1);
            }
            double std = PlayerData.stddev(intervals);
            double avg = PlayerData.avg(intervals);
            // humans clicking steadily: avg 80-400ms with wide spread;
            // machines: tight spread around the interval
            if (std < minStd && avg < 400 && avg > 20) {
                flag(player, data, String.format(Locale.ROOT,
                        "robotic clicking: stddev %.1fms over %d clicks", std, recent.size()), 4);
                data.clickTimes.clear();
            }
        }
    }
}
