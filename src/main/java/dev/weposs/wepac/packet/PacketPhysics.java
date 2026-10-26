package dev.weposs.wepac.packet;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.util.Compat;
import dev.weposs.wepac.util.GroundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-packet physics layer ("Grim-lite v2"), fed by {@link PacketSniffer}.
 *
 * MovementMonitor samples positions at 4 Hz; a subtle glide/hover tuned to sit
 * under per-sample thresholds can survive there. This layer instead consumes
 * EVERY client move packet (20 Hz) and integrates the vanilla vertical
 * equation {@code vy = (vy - 0.08) * 0.98} packet-by-packet:
 *
 *  - grounded (server truth): simulation is idle - walking/stepping is legal,
 *  - takeoff packet: the first airborne dy is compared against the vanilla
 *    jump impulse (0.42 + 0.1 per Jump Boost level),
 *  - airborne: each packet's dy must match the displacement physics predicts
 *    from the previous packet; the mismatch accumulates into a divergence,
 *  - divergence above the limit = confirmed extra height no vanilla movement
 *    can produce (glide, slow-fall, hover, air-jump bursts, gradual climb).
 *
 * False-positive guards:
 *  - only vertical motion is judged (horizontal has friction/collision noise),
 *  - a single-packet mismatch above {@link Sim#IMPULSE} is an external impulse
 *    (explosion, slime bounce, piston): re-anchor WITHOUT clearing the charge,
 *  - bounce context (slime/honey below, liquid, climbable, soft landing)
 *    cancels a pending confirmation,
 *  - network burst flushes (several packets, dt ~ 0, full-size dy) re-anchor,
 *  - all standard exemptions (velocity/teleport/respawn/join grace, /fly,
 *    elytra, riptide, vehicle, dead) resync instead of charge,
 *  - flags need {@code buffer} overflow events inside a 3s window.
 *
 * The pure math lives in {@link Sim} and has zero Bukkit references, so it is
 * unit-testable (see tools/SelfTest.java).
 */
public final class PacketPhysics {

    public static final double GRAVITY = 0.08;
    public static final double DRAG = 0.98;

    /** Max packets drained per player per tick (a stalled main thread must not OOM-flag). */
    private static final int MAX_DRAIN = 40;

    private final WepAC plugin;

    public PacketPhysics(WepAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------
    // Pure simulation core (no Bukkit; unit-tested in tools/SelfTest.java)
    // ------------------------------------------------------------------

    public static final class Sim {
        /** Divergence below this (negative) is clamped: falling faster than physics
         *  is legal-ish (knockdowns, drop shafts) and must not bank "free height". */
        public static final double NEG_CLAMP = -0.5;
        /** Single-packet excess above this = external impulse, re-anchor, don't charge. */
        public static final double IMPULSE = 1.0;
        /**
         * Packet gap above this (ms): the per-tick step count is unreliable, so the
         * velocity baseline re-anchors - but accumulated divergence is KEPT.
         * Otherwise a glide cheat could blink packets every ~0.6s and live in the
         * blind spot between this resync and the Blink check (2s).
         */
        public static final long REANCHOR_GAP_MS = 250L;
        /** Gap above this (ms) = genuine stream stall (freeze/world load): full resync. */
        public static final long MAX_GAP_MS = 5000L;

        public double lastY = Double.NaN;
        public double lastDy = Double.NaN;
        public long lastTime = 0L;
        public double divergence = 0.0;
        public boolean wasGrounded = true;
        /** consecutive single-packet impulses without a landing in between */
        public int consecutiveImpulses = 0;

        public void reset() {
            lastY = Double.NaN;
            lastDy = Double.NaN;
            lastTime = 0L;
            divergence = 0.0;
            wasGrounded = true;
            consecutiveImpulses = 0;
        }

        public void resync(double y, long timeMs) {
            lastY = y;
            lastDy = Double.NaN;
            lastTime = timeMs;
            divergence = 0.0;
            consecutiveImpulses = 0;
        }

        /**
         * Feed one position packet.
         *
         * @param y          claimed feet Y
         * @param timeMs     arrival time (main-thread clock)
         * @param grounded   SERVER-truth ground state (never the client claim -
         *                   a spoofer must keep charging, GroundSpoof flags him too)
         * @param jumpVy     vanilla jump impulse for this player (0.42 + 0.1*boost level)
         * @param limit      divergence limit in blocks
         * @return the divergence overflow (&gt; 0) when the limit was exceeded, else 0
         */
        public double onPacket(double y, long timeMs, boolean grounded, double jumpVy, double limit) {
            if (!Double.isFinite(y)) {
                reset();
                return 0.0;
            }
            if (Double.isNaN(lastY)) {
                lastY = y;
                lastTime = timeMs;
                wasGrounded = grounded;
                return 0.0;
            }
            double dy = y - lastY;
            long dt = timeMs - lastTime;
            lastY = y;
            lastTime = timeMs;

            if (dt < 0L || dt > MAX_GAP_MS) {
                // genuine stall (freeze, world load): predictions are meaningless
                lastDy = Double.NaN;
                divergence = 0.0;
                wasGrounded = grounded;
                return 0.0;
            }
            if (dt > REANCHOR_GAP_MS) {
                // blink-style pulse or lag burst: we cannot know how many physics
                // ticks elapsed, so skip charging this packet and re-anchor the
                // velocity - but the charge accumulated BEFORE the gap survives,
                // so pulsing cannot launder a glide (covered by SelfTest).
                lastDy = Double.NaN;
                return 0.0;
            }
            if (grounded) {
                wasGrounded = true;
                lastDy = Double.NaN;
                divergence = 0.0;
                consecutiveImpulses = 0;
                return 0.0;
            }
            if (wasGrounded) {
                // takeoff: a jump's first dy IS the impulse - charge any excess;
                // a walk-off ledge anchors to the observation (no charge)
                wasGrounded = false;
                if (dy > 0.2) {
                    divergence += dy - jumpVy;
                }
                lastDy = dy;
                return overflow(limit);
            }
            if (Double.isNaN(lastDy)) {
                lastDy = dy; // re-anchored earlier: adopt observation, no charge
                return 0.0;
            }
            int steps = dt < 25L ? 0 : (int) Math.min(10, Math.max(1, Math.round(dt / 50.0)));
            if (steps == 0) {
                if (Math.abs(dy) > 0.05) {
                    // network burst flush: full-size moves arriving back-to-back.
                    // Charging them would false-flag every lag spike - re-anchor.
                    lastDy = dy;
                    return 0.0;
                }
                // duplicate/near-idle packet inside one tick: physics has not
                // stepped yet, so any movement is extra
                divergence += dy;
                if (divergence < NEG_CLAMP) divergence = NEG_CLAMP;
                return overflow(limit);
            }
            double expected = expectedDisplacement(lastDy, steps);
            double err = dy - expected;
            if (err > IMPULSE) {
                // external impulse (explosion, slime/bed bounce, piston launch):
                // re-anchor to the observation but KEEP the accumulated charge,
                // so a cheater cannot launder divergence with one big packet.
                lastDy = dy;
                consecutiveImpulses++;
                if (consecutiveImpulses == 1) {
                    return 0.0;
                }
                // CONSECUTIVE "impulses" without any landing between them are not
                // explosions (real launches are covered by the upstream velocity
                // grace): that is a climb cheat rising ~1+ block per packet, each
                // step masquerading as a bounce. Charge it in full.
                divergence += err;
                return overflow(limit);
            }
            consecutiveImpulses = 0;
            divergence += err;
            if (divergence < NEG_CLAMP) divergence = NEG_CLAMP;
            lastDy = dy;
            return overflow(limit);
        }

        private double overflow(double limit) {
            if (divergence > limit) {
                double over = divergence;
                divergence = 0.0;
                return over;
            }
            return 0.0;
        }

        /**
         * Total vertical displacement vanilla physics produces over {@code n} ticks,
         * starting from a tick whose displacement was {@code v}.
         * Each tick: velocity is gravity-dragged, then position advances by it.
         */
        public static double expectedDisplacement(double v, int n) {
            double total = 0.0;
            for (int i = 0; i < n; i++) {
                v = (v - GRAVITY) * DRAG;
                total += v;
            }
            return total;
        }
    }

    // ------------------------------------------------------------------
    // Main-thread evaluation
    // ------------------------------------------------------------------

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                process(player);
            } catch (Throwable t) {
                plugin.debug("PacketPhysics error for " + player.getName() + ": " + t);
            }
        }
    }

    private void process(Player player) {
        PlayerData data = plugin.data().getIfLoaded(player.getUniqueId());
        if (data == null) return;
        Sim sim = data.physicsSim;
        if (data.packetMoves.isEmpty()) return;

        if (player.hasPermission("wepac.bypass")) {
            data.packetMoves.clear();
            sim.reset();
            return;
        }
        Check check = plugin.check(CheckType.PACKETFLY);
        if (check == null || !check.enabled()) {
            data.packetMoves.clear();
            return;
        }

        long now = System.currentTimeMillis();
        List<double[]> drained = new ArrayList<>(MAX_DRAIN);
        data.packetMoves.drainTo(drained, MAX_DRAIN);
        if (drained.isEmpty()) return;

        boolean exempt = player.isDead() || player.isInsideVehicle()
                || player.getAllowFlight() || player.isFlying()
                || player.isGliding() || player.isRiptiding()
                || data.velocityExempt()
                || now - data.lastTeleport < 1500L
                || now - data.lastRespawn < 3000L
                || now - data.joinTime < 3000L;
        if (exempt) {
            sim.resync(drained.get(drained.size() - 1)[2], now);
            return;
        }

        double limit = plugin.config().optDouble(CheckType.PACKETFLY, "divergence-limit", 0.35);
        int jumpLevel = Math.max(0, Compat.effectAmplifier(player, Compat.JUMP));
        double jumpVy = 0.42 + 0.1 * jumpLevel;
        // per-tick ground truth from the shared 20Hz cache (entity support included)
        boolean serverGrounded = data.groundTruthAt != 0L && now - data.groundTruthAt <= 200L
                ? data.groundTruth : GroundUtil.isOnGround(player);

        // Landing/takeoff transition inside a lag-spiked batch: one ground-truth
        // value cannot be right for every packet in it, so instead of charging on
        // a guess, resync the whole batch (misses at most one window of a cheat;
        // never fabricates a violation - the per-tick engine is B1 roadmap)
        long gta = data.groundTruthAt;
        if (gta > (long) drained.get(0)[0] && gta < (long) drained.get(drained.size() - 1)[0]) {
            sim.resync(drained.get(drained.size() - 1)[2], now);
            return;
        }

        for (double[] mv : drained) {
            double over = sim.onPacket(mv[2], (long) mv[0], serverGrounded, jumpVy, limit);
            if (over > 0.0) {
                confirm(player, data, sim, over);
            }
        }
        if (serverGrounded && data.packetFlyBuffer > 0) {
            data.packetFlyBuffer = 0; // standing on ground: pending overflows were noise
        }
    }

    private void confirm(Player player, PlayerData data, Sim sim, double over) {
        // standing on ground RIGHT NOW: any overflow produced mid-batch at a
        // landing transition is stale - drop it instead of confirming
        if (data.groundTruthAt != 0L
                && System.currentTimeMillis() - data.groundTruthAt <= 200L
                && data.groundTruth) {
            sim.divergence = 0.0;
            data.packetFlyBuffer = 0;
            return;
        }
        // bounce/soft context explains physics-defying height legitimately
        if (bounceContext(player)) {
            sim.divergence = 0.0;
            data.packetFlyBuffer = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastPacketFlyAt > 3000L) {
            data.packetFlyBuffer = 0;
        }
        data.lastPacketFlyAt = now;
        data.packetFlyBuffer++;
        int needed = plugin.config().optInt(CheckType.PACKETFLY, "buffer", 2);
        if (data.packetFlyBuffer < needed) return;

        data.packetFlyBuffer = 0;
        Check check = plugin.check(CheckType.PACKETFLY);
        check.flag(player, data, String.format(Locale.ROOT,
                "packet physics: +%.2f blocks above per-packet gravity simulation", over), 3);
    }

    /**
     * Slime/honey below, liquid, climbables, a soft landing block at the body,
     * or a slow-sinking medium (cobweb sinks at ~0.026 b/t, powder snow and
     * berry bushes alike) - all legitimately defy gravity-based expectations.
     */
    private static boolean bounceContext(Player player) {
        if (GroundUtil.inLiquid(player) || GroundUtil.onClimbable(player)
                || GroundUtil.softLanding(player)) {
            return true;
        }
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        if (at == Material.COBWEB || at == Material.POWDER_SNOW
                || at == Material.SWEET_BERRY_BUSH) {
            return true;
        }
        org.bukkit.World w = loc.getWorld();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dy = -1; dy >= -3; dy--) {
            Material m = w.getBlockAt(bx, by + dy, bz).getType();
            if (m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK
                    || m == Material.POWDER_SNOW) return true;
        }
        return false;
    }
}
