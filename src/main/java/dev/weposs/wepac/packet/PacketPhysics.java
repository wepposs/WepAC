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

    /**
     * Horizontal air simulation (B1.1, pure, unit-tested).
     *
     * Vanilla airborne horizontal motion per tick is
     * {@code disp(t+1) = disp(t) * 0.91 + accel}, where the input acceleration
     * is the player's flying speed: 0.02, or 0.026 while sprinting - it is NOT
     * affected by Speed potions, attributes or the yaw it is applied in. So in
     * air the deviation {@code |disp(t+1) - 0.91 * disp(t)|} can never exceed
     * 0.026, whatever the player presses. Air-strafe, air-speed, "bhop speed"
     * and instant direction changes all break this bound by an order of
     * magnitude, while the limit is independent of any tuned constant.
     *
     * Validity conditions (otherwise the pair is skipped, never charged):
     *  - three CONSECUTIVE flying packets (seq numbers adjacent), so each
     *    displacement is exactly one client tick;
     *  - the two earlier packets both claimed airborne: then the friction of the
     *    middle tick was pure air (0.91) and its acceleration was air control.
     *    The jump tick itself (ground friction + sprint-jump boost) is excluded;
     *  - an axis that moved LESS than predicted in the same direction is never
     *    charged: wall collisions, attack slowdown and braking only remove speed.
     */
    public static final class AirSim {
        public static final double AIR_FRICTION = 0.91;
        public static final double MAX_ACCEL = 0.026;

        private double px = Double.NaN, pz = Double.NaN;
        private double vx = Double.NaN, vz = Double.NaN;
        private boolean air1 = false, air0 = false;
        private long prevSeq = -1L;

        public void reset() {
            px = pz = vx = vz = Double.NaN;
            air1 = air0 = false;
            prevSeq = -1L;
        }

        /** @return how far the acceleration exceeded the vanilla bound (0 = legal / not judged) */
        public double onPacket(double x, double z, boolean clientGround, long seq, double tolerance) {
            boolean chain = prevSeq >= 0L && seq == prevSeq + 1L && !Double.isNaN(px);
            double nvx = chain ? x - px : Double.NaN;
            double nvz = chain ? z - pz : Double.NaN;
            double excess = 0.0;
            if (chain && !Double.isNaN(vx) && air0 && air1) {
                double rx = residual(nvx, AIR_FRICTION * vx);
                double rz = residual(nvz, AIR_FRICTION * vz);
                double r = Math.sqrt(rx * rx + rz * rz);
                excess = Math.max(0.0, r - MAX_ACCEL - tolerance);
            }
            px = x;
            pz = z;
            vx = nvx;
            vz = nvz;
            air0 = air1;
            air1 = !clientGround;
            prevSeq = seq;
            return excess;
        }

        /** Per-axis deviation; a same-direction slowdown is explained by collision. */
        static double residual(double actual, double expected) {
            if (Math.abs(actual) <= Math.abs(expected) && actual * expected >= 0.0) {
                return 0.0;
            }
            return actual - expected;
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
            data.airSim.reset();
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

        processAir(player, data, drained, exempt, now);

        Check check = plugin.check(CheckType.PACKETFLY);
        if (check == null || !check.enabled()) {
            return;
        }
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

    /** Horizontal air simulation over this tick's packet batch (flags SPEED). */
    private void processAir(Player player, PlayerData data, List<double[]> drained,
                            boolean exempt, long now) {
        AirSim air = data.airSim;
        Check speed = plugin.check(CheckType.SPEED);
        if (speed == null || !speed.enabled()
                || !plugin.config().optBool(CheckType.SPEED, "air-simulation", true)) {
            return;
        }
        // contexts whose horizontal physics is not plain air: resync, never charge
        if (exempt || data.bedrock() || GroundUtil.inLiquid(player)
                || GroundUtil.onClimbable(player) || bounceContext(player)
                || Compat.effectAmplifier(player, Compat.LEVITATION) >= 0) {
            air.reset();
            return;
        }
        double tolerance = plugin.config().optDouble(CheckType.SPEED, "air-accel-tolerance", 0.01);
        double worst = 0.0;
        for (double[] mv : drained) {
            if (mv.length < 6) continue;
            double over = air.onPacket(mv[1], mv[3], mv[4] > 0.5, (long) mv[5], tolerance);
            worst = Math.max(worst, over);
        }
        if (worst <= 0.0) return;
        // entity pushing (crowds, mobs) adds real horizontal velocity: the
        // scan is only paid for when the bound was actually broken
        if (!player.getNearbyEntities(1.2, 1.5, 1.2).isEmpty()) return;
        if (now - data.lastAirStrafeAt > 2000L) data.airStrafeBuffer = 0;
        data.lastAirStrafeAt = now;
        data.airStrafeBuffer++;
        if (data.airStrafeBuffer < plugin.config().optInt(CheckType.SPEED, "air-buffer", 3)) return;
        data.airStrafeBuffer = 0;
        speed.flag(player, data, String.format(Locale.ROOT,
                "air simulation: horizontal acceleration %.3f above vanilla air control (0.026/tick)",
                worst), 2);
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
