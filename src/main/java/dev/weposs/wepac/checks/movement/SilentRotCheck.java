package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Locale;

/**
 * SilentRot: the server-visible signature of "silent" rotations (silent scaffold,
 * silent aura, NoServerRotate companions). The cheat snaps the server-side pitch
 * (or yaw) to the block/target for one action, then snaps back to the camera
 * angle - producing large, fast REVERSALS that human tracking never sustains.
 *
 * A15 hardening: reversals are counted only when ACTION-CORRELATED - within
 * ~250ms after an attack/place/interact (tracked by TelemetryCollector). That is
 * exactly the silent-rot signature (snap -> act -> snap back), while honest
 * combat flicks (ground target -> air target) reverse WITHOUT the action
 * coupling and no longer count. Yaw reversals are covered with the same gate
 * (horizontal-only silent auras were previously invisible).
 *
 * Reversal COLLECTION lives in TelemetryCollector (always on), so the AI
 * pitchRevRate feature no longer depends on this check being enabled.
 */
public final class SilentRotCheck extends Check {

    public SilentRotCheck(WepAC plugin) {
        super(plugin, CheckType.SILENTROT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        PlayerData data = plugin.data().get(player);
        long now = System.currentTimeMillis();
        long window = plugin.config().optLongOr(CheckType.SILENTROT, "window-ms", 5000L);
        prune(data.actionPitchReversals, now, window);
        prune(data.actionYawReversals, now, window);

        int reversals = data.actionPitchReversals.size() + data.actionYawReversals.size();
        int minReversals = plugin.config().optInt(CheckType.SILENTROT, "min-reversals", 5);
        if (reversals >= minReversals) {
            data.silentRotBuffer++;
            int needed = plugin.config().optInt(CheckType.SILENTROT, "buffer", 2);
            if (data.silentRotBuffer >= needed) {
                double minSwing = plugin.config().optDouble(CheckType.SILENTROT, "min-swing-deg", 30.0);
                flag(player, data, String.format(Locale.ROOT,
                        "%d action-correlated reversals (pitch>=%.0f deg / yaw>=%.0f deg) in %.1fs - silent rotations",
                        reversals, minSwing,
                        plugin.config().optDouble(CheckType.SILENTROT, "yaw-min-swing-deg", 60.0),
                        window / 1000.0), 3);
                data.silentRotBuffer = 0;
                data.actionPitchReversals.clear();
                data.actionYawReversals.clear();
            }
        } else {
            data.silentRotBuffer = Math.max(0, data.silentRotBuffer - 1);
        }
    }

    private static void prune(java.util.ArrayDeque<Long> deque, long now, long windowMs) {
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
            deque.pollFirst();
        }
    }
}
