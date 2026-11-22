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
        // 1.20.5+: entity_interaction_range attribute is the real vanilla limit
        // (plugins/attribute modifiers change it); 1.21.11 spears extend it
        // through their attack_range component (jab reaches up to 4.5)
        double attrRange = dev.weposs.wepac.util.Compat.attribute(attacker, "entity_interaction_range", 3.0);
        maxReach = Math.max(maxReach, attrRange + (maxReach - 3.0));
        boolean spear = dev.weposs.wepac.util.Compat.isSpear(attacker.getInventory().getItemInMainHand());
        if (spear) {
            maxReach = Math.max(maxReach,
                    plugin.config().optDouble(CheckType.REACH, "spear-max-reach", 4.55));
        }
        double pingComp = plugin.config().optDouble(CheckType.REACH, "ping-compensation", 0.35);
        double maxComp = plugin.config().optDouble(CheckType.REACH, "max-ping-compensation", 0.6);
        // transaction RTT when available: a faked keep-alive ping can no
        // longer buy extra reach allowance
        int latency = dev.weposs.wepac.packet.TransactionManager.latency(attacker, data);
        double allowed = maxReach
                + Math.min(maxComp, latency / 1000.0 * pingComp)
                + (attacker.getVelocity().length() > 0.1 ? 0.35 : 0.0);

        Location eye = attacker.getEyeLocation();
        Vector eyeVec = eye.toVector();
        Vector dir = eye.getDirection();

        // distance against the victim's CURRENT position
        double minDistance = rayOrBox(eyeVec, dir, victim.getBoundingBox());

        // lag compensation: the attacker saw the victim in the recent past
        int vPing = victim instanceof Player ? ((Player) victim).getPing() : 0;
        long nowMs = System.currentTimeMillis();
        long windowStart = nowMs - Math.min(600L, latency + vPing / 2L + 150L);
        var tx = data.transactions;
        if (tx.active && tx.lastAckedSendMs != 0L && nowMs - tx.lastAckArrivalMs < 1000L) {
            // transaction anchor: the client PROVABLY had every position sent
            // up to the last acked ping; entity interpolation trails by ~3 ticks
            windowStart = Math.max(nowMs - 1000L, tx.lastAckedSendMs - 150L - vPing / 2L);
        }
        PlayerData victimData = plugin.data().getIfLoaded(victim.getUniqueId());
        if (victimData != null) {
            double w = victim.getWidth();
            double h = victim.getHeight();
            for (double[] pos : victimData.posHistory) {
                if (pos[0] < windowStart) continue;
                // BoundingBox.of(center, x, y, z) takes HALF-extents: passing the
                // full width/height doubled every historical hitbox (free reach)
                BoundingBox box = BoundingBox.of(
                        new Vector(pos[1], pos[2] + h / 2.0, pos[3]), w / 2.0, h / 2.0, w / 2.0);
                minDistance = Math.min(minDistance, rayOrBox(eyeVec, dir, box));
            }
        }

        PlayerData.push(data.reachSamples, Math.min(minDistance, 8.0), PlayerData.REACH_CAP);

        if (minDistance > allowed) {
            flag(attacker, data, String.format(Locale.ROOT,
                    "%.2f > %.2f blocks (lag-compensated)", minDistance, allowed), 2);
        }

        // statistical layer: "smart" reach (3.1-3.3) hides under the per-hit
        // allowance (ping comp + movement slack) but shifts the whole lag-
        // compensated distribution. Honest players average well under 3.0
        // because the MINIMUM over the compensation window is taken per hit.
        if (spear) {
            // spear hits sit at 3-4.5 by design: they must not shift the
            // sword-reach average either way
        } else if (data.reachAvgCooldown > 0) {
            data.reachAvgCooldown--;
        } else if (latency < plugin.config().optInt(CheckType.REACH, "avg-max-ping", 250)) {
            int n = plugin.config().optInt(CheckType.REACH, "avg-samples", 12);
            double avgMax = plugin.config().optDouble(CheckType.REACH, "avg-max-reach", 2.95);
            if (data.reachSamples.size() >= n) {
                double sum = 0;
                int i = 0;
                for (java.util.Iterator<Double> it = data.reachSamples.descendingIterator();
                     it.hasNext() && i < n; i++) {
                    sum += it.next();
                }
                double avg = sum / n;
                if (avg > avgMax) {
                    flag(attacker, data, String.format(Locale.ROOT,
                            "sustained reach: avg %.2f > %.2f over %d hits", avg, avgMax, n), 2);
                    data.reachAvgCooldown = n;
                }
            }
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
