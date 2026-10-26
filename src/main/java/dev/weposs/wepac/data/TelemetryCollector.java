package dev.weposs.wepac.data;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Always-on telemetry collectors, deliberately decoupled from the checks.
 *
 * The AI feature vector consumes attackAirborne (f16) and pitchReversals
 * (f17); previously those were filled INSIDE CriticalsCheck/SilentRotCheck,
 * so disabling a check silently changed the model's input distribution
 * against a frozen normaliser - corrupting predictions. Collection lives
 * here now and runs regardless of any check's enabled state.
 *
 * Also tracks:
 *  - lastActionAt (attack / block place / interact): the SilentRot check
 *    correlates pitch/yaw reversals with actions - a silent rotation snaps
 *    to the target right around the action packet, a human flick does not
 *    reverse within ~250ms of acting (A15);
 *  - yaw reversals with the same action gate (silent auras that only rotate
 *    horizontally were previously invisible).
 */
public final class TelemetryCollector implements Listener {

    private final WepAC plugin;

    public TelemetryCollector(WepAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.data().get(player);
        long now = System.currentTimeMillis();

        double minSwing = plugin.config().optDouble(CheckType.SILENTROT, "min-swing-deg", 30.0);
        long flipWindow = plugin.config().optLongOr(CheckType.SILENTROT, "flip-window-ms", 500L);
        double yawSwing = plugin.config().optDouble(CheckType.SILENTROT, "yaw-min-swing-deg", 60.0);

        // ---- pitch reversals ----
        double dPitch = event.getTo().getPitch() - event.getFrom().getPitch();
        if (Math.abs(dPitch) >= minSwing) {
            int dir = dPitch > 0 ? 1 : -1;
            if (data.lastPitchDir != 0 && dir != data.lastPitchDir
                    && now - data.lastReversalTime <= flipWindow) {
                data.pitchReversals.addLast(now);           // ungated: AI feature f17
                if (now - data.lastActionAt <= 250L) {
                    data.actionPitchReversals.addLast(now); // action-correlated: check signal
                }
                data.lastReversalTime = now;
            } else if (data.lastPitchDir != 0 && dir != data.lastPitchDir) {
                data.lastReversalTime = now;
            }
            data.lastPitchDir = dir;
        }

        // ---- yaw reversals (silent horizontal rotations) ----
        double dYaw = wrapDegrees(event.getTo().getYaw() - event.getFrom().getYaw());
        if (Math.abs(dYaw) >= yawSwing) {
            int dir = dYaw > 0 ? 1 : -1;
            if (data.lastYawDir != 0 && dir != data.lastYawDir
                    && now - data.lastYawReversalTime <= flipWindow
                    && now - data.lastActionAt <= 250L) {
                data.actionYawReversals.addLast(now);
                data.lastYawReversalTime = now;
            } else if (data.lastYawDir != 0 && dir != data.lastYawDir) {
                data.lastYawReversalTime = now;
            }
            data.lastYawDir = dir;
        }

        // hard memory bound (the checks prune their own windows; ALL deques must
        // be bounded here because SilentRotCheck early-returns when disabled or
        // bypassed and would never prune them)
        while (!data.pitchReversals.isEmpty() && now - data.pitchReversals.peekFirst() > 10_000L) {
            data.pitchReversals.pollFirst();
        }
        while (!data.actionPitchReversals.isEmpty()
                && now - data.actionPitchReversals.peekFirst() > 10_000L) {
            data.actionPitchReversals.pollFirst();
        }
        while (!data.actionYawReversals.isEmpty()
                && now - data.actionYawReversals.peekFirst() > 10_000L) {
            data.actionYawReversals.pollFirst();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        PlayerData data = plugin.data().get(attacker);
        data.lastActionAt = System.currentTimeMillis();
        // airborne ratio of PvP hits (AI feature f16, crit rhythm) - collected
        // here so it survives CriticalsCheck being disabled. Ground state comes
        // from the per-tick truth cache, fresher than any 4Hz sample.
        if (event.getEntity() instanceof Player) {
            PlayerData.push(data.attackAirborne, !data.serverOnGround, PlayerData.HIT_CAP);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        plugin.data().get(event.getPlayer()).lastActionAt = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        plugin.data().get(event.getPlayer()).lastActionAt = System.currentTimeMillis();
    }

    private static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }
}
