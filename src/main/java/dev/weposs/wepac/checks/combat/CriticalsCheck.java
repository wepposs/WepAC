package dev.weposs.wepac.checks.combat;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.util.GroundUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.9+ Criticals detection, two independent signals:
 *
 *  1. Packet GroundSpoof crits: a critical requires being airborne; crit hacks
 *     send onGround=true inside flying packets while falling to force crits on
 *     every hit. The packet claim is compared against server-side physics.
 *
 *  2. Crit rhythm: jumping before every single attack IS legitimate 1.9 play,
 *     but a bot does it perfectly - 95%+ of hits airborne at a metronomic
 *     interval. Humans in a real fight lose the rhythm (strafing, getting hit,
 *     terrain, panic swings), so their airborne ratio and interval spread vary.
 */
public final class CriticalsCheck extends Check {

    public CriticalsCheck(WepAC plugin) {
        super(plugin, CheckType.CRITICALS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!enabled() || bypass(attacker)) return;

        PlayerData data = plugin.data().get(attacker);
        long now = System.currentTimeMillis();

        if (data.velocityExempt()) {
            data.critsBuffer = Math.max(0, data.critsBuffer - 1);
            return;
        }

        // server truth: airborne for at least ~0.25s, but the client claims ground
        boolean serverAirborne = !data.serverOnGround && data.airTicks >= 5;
        if (serverAirborne && data.packetSeen && data.lastPacketGround) {
            data.critsBuffer++;
            int needed = plugin.config().optInt(CheckType.CRITICALS, "buffer", 3);
            if (data.critsBuffer >= needed) {
                flag(attacker, data, String.format(Locale.ROOT,
                        "packet onGround=true while airborne %d ticks (forced crits)",
                        data.airTicks), 3);
                data.critsBuffer = 0;
            }
        } else {
            data.critsBuffer = Math.max(0, data.critsBuffer - 1);
        }

        // crit rhythm tracking (PvP hits only)
        if (event.getEntity() instanceof Player) {
            boolean airborneAtHit = !GroundUtil.isOnGround(attacker);
            PlayerData.push(data.attackAirborne, airborneAtHit, PlayerData.HIT_CAP);
            checkCritRhythm(attacker, data, now);
        }
    }

    private void checkCritRhythm(Player player, PlayerData data, long now) {
        if (!plugin.config().optBool(CheckType.CRITICALS, "crit-rhythm", true)) return;
        int minAttacks = plugin.config().optInt(CheckType.CRITICALS, "rhythm-min-attacks", 15);
        double minAirRatio = plugin.config().optDouble(CheckType.CRITICALS, "rhythm-air-ratio", 0.95);
        double maxIntervalStd = plugin.config().optDouble(CheckType.CRITICALS, "rhythm-max-interval-stddev", 60.0);
        if (data.attackAirborne.size() < minAttacks) return;

        List<Boolean> air = new ArrayList<>(data.attackAirborne);
        List<Boolean> win = air.subList(air.size() - minAttacks, air.size());
        int airborne = 0;
        for (boolean b : win) if (b) airborne++;
        double ratio = (double) airborne / minAttacks;

        List<Long> attacks = new ArrayList<>(data.attackTimes);
        if (attacks.size() < minAttacks) return;
        List<Long> recent = attacks.subList(attacks.size() - minAttacks, attacks.size());
        double[] intervals = new double[recent.size() - 1];
        for (int i = 1; i < recent.size(); i++) {
            intervals[i - 1] = recent.get(i) - recent.get(i - 1);
        }
        double intervalStd = PlayerData.stddev(intervals);

        if (ratio >= minAirRatio && intervalStd <= maxIntervalStd) {
            data.critRhythmBuffer++;
            if (data.critRhythmBuffer >= 2) {
                flag(player, data, String.format(Locale.ROOT,
                        "robotic crit rhythm: %d/%d hits airborne, interval stddev %.0fms",
                        airborne, minAttacks, intervalStd), 3);
                data.critRhythmBuffer = 0;
                data.attackAirborne.clear();
            }
        } else {
            data.critRhythmBuffer = Math.max(0, data.critRhythmBuffer - 1);
        }
    }
}
