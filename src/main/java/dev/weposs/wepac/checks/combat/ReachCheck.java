package dev.weposs.wepac.checks.combat;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Lag-compensated Reach (v2).
 *
 * The attacker's client sees the victim where it was up to (ping) milliseconds ago.
 * A 20Hz position history is kept for every player; on each hit we measure the
 * eye-to-hitbox distance against the victim's positions inside the window the
 * attacker could legitimately have seen, and take the MINIMUM. Only if the hit
 * exceeds max range against every candidate position is it a violation.
 * This removes the classic high-ping false positives and still catches real
 * reach, because a reach hack extends distance at every point in time.
 */
public final class ReachCheck extends Check {

    public ReachCheck(WepAC plugin) {
        super(plugin, CheckType.REACH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!enabled() || bypass(attacker)) return;

        Entity victim = event.getEntity();
        PlayerData data = plugin.data().get(attacker);

        double maxReach = plugin.config().optDouble(CheckType.REACH, "max-reach", 3.05);
        double pingComp = plugin.config().optDouble(CheckType.REACH, "ping-compensation", 0.35);
        double maxComp = plugin.config().optDouble(CheckType.REACH, "max-ping-compensation", 0.6);
        double allowed = maxReach
                + Math.min(maxComp, attacker.getPing() / 1000.0 * pingComp)
                + (attacker.getVelocity().length() > 0.1 ? 0.35 : 0.0);

        Location eye = attacker.getEyeLocation();
        Vector eyeVec = eye.toVector();
        Vector dir = eye.getDirection();

        // distance against the victim's CURRENT position
        double minDistance = rayOrBox(eyeVec, dir, victim.getBoundingBox());

        // lag compensation: the attacker saw the victim in the recent past
        int vPing = victim instanceof Player ? ((Player) victim).getPing() : 0;
        long windowStart = System.currentTimeMillis()
                - Math.min(600L, attacker.getPing() + vPing / 2L + 150L);
        PlayerData victimData = plugin.data().getIfLoaded(victim.getUniqueId());
        if (victimData != null) {
            double w = victim.getWidth();
            double h = victim.getHeight();
            for (double[] pos : victimData.posHistory) {
                if (pos[0] < windowStart) continue;
                BoundingBox box = BoundingBox.of(
                        new Vector(pos[1], pos[2] + h / 2.0, pos[3]), w, h, w);
                minDistance = Math.min(minDistance, rayOrBox(eyeVec, dir, box));
            }
        }

        PlayerData.push(data.reachSamples, Math.min(minDistance, 8.0), PlayerData.REACH_CAP);

        if (minDistance > allowed) {
            flag(attacker, data, String.format(Locale.ROOT,
                    "%.2f > %.2f blocks (lag-compensated)", minDistance, allowed), 2);
        }
    }

    private static double rayOrBox(Vector eye, Vector dir, BoundingBox box) {
        RayTraceResult hit = box.rayTrace(eye, dir, 8.0);
        return hit != null ? hit.getHitPosition().distance(eye) : distanceToBox(eye, box);
    }

    private static double distanceToBox(Vector point, BoundingBox box) {
        double dx = Math.max(box.getMinX() - point.getX(),
                Math.max(0, point.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - point.getY(),
                Math.max(0, point.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - point.getZ(),
                Math.max(0, point.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
