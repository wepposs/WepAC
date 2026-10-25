package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * SilentRot: the server-visible signature of "silent" rotations (silent scaffold,
 * silent aura, NoServerRotate companions). The cheat snaps the server-side pitch
 * down to the block/target for one action, then snaps back to the camera angle -
 * producing large, fast pitch REVERSALS that human tracking never sustains.
 *
 * Detection: a reversal = two consecutive pitch deltas of at least {min-swing-deg}
 * in opposite directions within {flip-window-ms}. {min-reversals} of them inside
 * {window-ms} (with a confirmation buffer) is mechanically impossible for a human
 * in normal play.
 *
 * The reversal timestamps also feed the AI (pitchRevRate feature).
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
        double dPitch = event.getTo().getPitch() - event.getFrom().getPitch();
        long now = System.currentTimeMillis();

        double minSwing = plugin.config().optDouble(CheckType.SILENTROT, "min-swing-deg", 30.0);
        long flipWindow = plugin.config().optLongOr(CheckType.SILENTROT, "flip-window-ms", 500L);

        if (Math.abs(dPitch) >= minSwing) {
            int dir = dPitch > 0 ? 1 : -1;
            if (data.lastPitchDir != 0 && dir != data.lastPitchDir
                    && now - data.lastReversalTime <= flipWindow) {
                data.pitchReversals.addLast(now);
                data.lastReversalTime = now;
            } else if (data.lastPitchDir != 0 && dir != data.lastPitchDir) {
                data.lastReversalTime = now;
            }
            data.lastPitchDir = dir;
        }

        long window = plugin.config().optLongOr(CheckType.SILENTROT, "window-ms", 5000L);
        while (!data.pitchReversals.isEmpty() && now - data.pitchReversals.peekFirst() > window) {
            data.pitchReversals.pollFirst();
        }

        int minReversals = plugin.config().optInt(CheckType.SILENTROT, "min-reversals", 5);
        if (data.pitchReversals.size() >= minReversals) {
            data.silentRotBuffer++;
            int needed = plugin.config().optInt(CheckType.SILENTROT, "buffer", 2);
            if (data.silentRotBuffer >= needed) {
                flag(player, data, String.format(Locale.ROOT,
                        "%d pitch reversals >=%.0f deg in %.1fs (silent rotations)",
                        data.pitchReversals.size(), minSwing, window / 1000.0), 3);
                data.silentRotBuffer = 0;
                data.pitchReversals.clear();
            }
        } else {
            data.silentRotBuffer = Math.max(0, data.silentRotBuffer - 1);
        }
    }

    /** Reversals per second over the rolling window - AI feature 17. */
    public static double reversalRate(PlayerData data, long windowMs) {
        ArrayDeque<Long> r = data.pitchReversals;
        if (r.isEmpty()) return 0.0;
        return r.size() / (windowMs / 1000.0);
    }
}
