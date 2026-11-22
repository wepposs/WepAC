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
        try {
            boats(player, data);
        } catch (Throwable t) {
            plugin.debug("VehicleSim error for " + player.getName() + ": " + t);
        }
        Sim sim = data.physicsSim;
        if (data.packetMoves.isEmpty()) return;

        if (player.hasPermission("wepac.bypass")) {
            data.packetMoves.clear();
            sim.reset();
            return;
        }
        long now = System.currentTimeMillis();
        List<double[]> drained = new ArrayList<>(MAX_DRAIN);
        data.packetMoves.drainTo(drained, MAX_DRAIN);
        if (drained.isEmpty()) return;

        try {
            horizontal(player, data, drained, now);
        } catch (Throwable t) {
            plugin.debug("HorizontalSim error for " + player.getName() + ": " + t);
        }

        Check check = plugin.check(CheckType.PACKETFLY);
        if (check == null || !check.enabled()) return;

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

    // ------------------------------------------------------------------
    // Horizontal per-packet prediction (flags as SPEED)
    // ------------------------------------------------------------------

    private void horizontal(Player player, PlayerData data, List<double[]> drained, long now) {
        HorizontalSim hs = data.horizontalSim;
        Check speed = plugin.check(CheckType.SPEED);
        double[] last = drained.get(drained.size() - 1);
        if (speed == null || !speed.enabled()
                || !plugin.config().optBool(CheckType.SPEED, "packet-sim", true)) {
            hs.resync(last[1], last[3], (long) last[0]);
            return;
        }
        txKnockback(player, data, drained);
        Material at = player.getLocation().getBlock().getType();
        boolean exempt = player.isDead() || player.isInsideVehicle()
                || player.getAllowFlight() || player.isFlying()
                || player.isGliding() || player.isRiptiding()
                || data.velocityExempt()
                || lungeContext(player, data, now)
                || GroundUtil.inLiquid(player) || GroundUtil.onClimbable(player)
                || at == Material.COBWEB || at == Material.BUBBLE_COLUMN
                || now - data.lastTeleport < 1500L
                || now - data.lastRespawn < 3000L
                || now - data.joinTime < 3000L;
        long gta = data.groundTruthAt;
        if (exempt || (gta > (long) drained.get(0)[0] && gta < (long) last[0])) {
            hs.resync(last[1], last[3], (long) last[0]);
            data.hPrevY = last[2];
            data.hPrevGround = last[4] > 0.5;
            return;
        }
        boolean serverGrounded = data.groundTruthAt != 0L && now - data.groundTruthAt <= 200L
                ? data.groundTruth : GroundUtil.isOnGround(player);
        int speedLvl = Math.max(0, Compat.effectAmplifier(player, Compat.SPEED) + 1);
        double mult = plugin.config().optDouble(CheckType.SPEED, "packet-sim-multiplier", 1.0);
        // sprint always assumed (server sprint state lags the client by a tick).
        // The movement_speed attribute covers item/plugin modifiers on 1.21.x;
        // the effect formula is the floor when the attribute lags the effect.
        double attr = Compat.attribute(player, "movement_speed", 0.1);
        double attrSprint = player.isSprinting() ? attr : attr * 1.3;
        double moveSpeed = Math.max(0.1 * 1.3 * (1.0 + 0.2 * speedLvl), attrSprint) * mult;
        double slip = slipperiness(player);
        double limit = plugin.config().optDouble(CheckType.SPEED, "packet-sim-limit", 0.25);

        // NoSlow: item use known for 250ms+ (server learns about use/release in
        // packet order, the delay only guards the use-start edge)
        boolean using = player.isHandRaised() && !player.isRiptiding()
                && plugin.config().optBool(CheckType.NOSLOW, "packet-sim", true)
                && plugin.check(CheckType.NOSLOW) != null && plugin.check(CheckType.NOSLOW).enabled();
        if (using) {
            if (data.useSince == 0L) data.useSince = now;
        } else {
            data.useSince = 0L;
        }
        boolean slowed = using && now - data.useSince > 250L;
        hs.inputScale = slowed ? 0.2 : 1.0;
        for (double[] mv : drained) {
            boolean clientGround = mv[4] > 0.5;
            boolean prevGround = data.hPrevGround;
            double dy = Double.isNaN(data.hPrevY) ? 0.0 : mv[2] - data.hPrevY;
            boolean jumped = prevGround && dy > 0.2;
            // ground tick = BOTH sides agree the tick STARTED on ground; any
            // disagreement uses air physics (whose bound converges to the same
            // ~0.29 b/t equilibrium, so a spoofed flag buys nothing)
            boolean ground = prevGround && serverGrounded;
            double over = hs.onPacket(mv[1], mv[3], (long) mv[0], moveSpeed,
                    ground ? slip : 0.6, ground, jumped, limit);
            data.hPrevY = mv[2];
            data.hPrevGround = clientGround;
            if (over > 0.0) {
                if (now - data.lastHSpeedAt > 3000L) data.hSpeedBuffer = 0;
                data.lastHSpeedAt = now;
                if (++data.hSpeedBuffer >= plugin.config().optInt(CheckType.SPEED, "packet-sim-buffer", 2)) {
                    data.hSpeedBuffer = 0;
                    if (slowed) {
                        plugin.check(CheckType.NOSLOW).flag(player, data, String.format(Locale.ROOT,
                                "NoSlow: +%.2f blocks beyond 0.2x input while using %s",
                                over, player.getInventory().getItemInMainHand().getType().name()), 3);
                    } else
                    speed.flag(player, data, String.format(Locale.ROOT,
                            "packet physics: +%.2f blocks beyond max vanilla horizontal acceleration", over), 3);
                    setback(player, data);
                    hs.reset();
                    return;
                }
            }
        }
    }

    /**
     * Transaction-confirmed anti-KB: the move entry carries the knockback the
     * client PROVABLY had when it produced this packet. The first post-ack
     * tick must travel at least kb - (max counter-input) horizontally; anti-KB
     * modules that cancel/scale the velocity packet cannot hide behind lag.
     */
    private void txKnockback(Player player, PlayerData data, List<double[]> drained) {
        Check kb = plugin.check(CheckType.VELOCITY);
        if (kb == null || !kb.enabled()) return;
        HorizontalSim hs = data.horizontalSim;
        double[] prev = Double.isNaN(hs.lastX) ? null
                : new double[]{hs.lastTime, hs.lastX, 0, hs.lastZ, 0};
        for (double[] mv : drained) {
            if (mv.length > 5 && !Double.isNaN(mv[5]) && prev != null && mv[5] > 0.25
                    && mv[0] - prev[0] >= 25 && mv[0] - prev[0] <= 150) {
                double h = Math.hypot(mv[1] - prev[1], mv[3] - prev[3]);
                // worst-case vanilla: full counter-input (0.13) plus ground friction
                // on the same tick; walls eat knockback (raycast guard below)
                double minH = mv[5] * plugin.config().optDouble(CheckType.VELOCITY, "tx-ratio", 0.5) - 0.13;
                boolean wall = player.getWorld().rayTraceBlocks(player.getLocation().add(0, 0.1, 0),
                        player.getVelocity().setY(0).lengthSquared() > 1e-6
                                ? player.getVelocity().setY(0).normalize()
                                : new org.bukkit.util.Vector(1, 0, 0),
                        1.0, org.bukkit.FluidCollisionMode.NEVER, true) != null;
                if (minH > 0.05 && h < minH && !player.isBlocking() && !wall
                        && !GroundUtil.inLiquid(player)) {
                    if (++data.txKbBuffer >= plugin.config().optInt(CheckType.VELOCITY, "tx-buffer", 2)) {
                        data.txKbBuffer = 0;
                        kb.flag(player, data, String.format(Locale.ROOT,
                                "transaction-confirmed knockback %.2f b/t, moved %.2f (min %.2f)",
                                mv[5], h, minH), 3);
                    }
                } else if (h >= minH) {
                    data.txKbBuffer = Math.max(0, data.txKbBuffer - 1);
                }
            }
            prev = mv;
        }
    }

    /** Return the player to the last legal ground position (rollback config gate). */
    private void setback(Player player, PlayerData data) {
        if (!plugin.config().rollbackEnabled) return;
        Location safe = data.lastGroundLocation;
        if (safe == null || !safe.getWorld().equals(player.getWorld())) return;
        if (safe.distanceSquared(player.getLocation()) < 0.25) return;
        Location to = safe.clone();
        to.setYaw(player.getLocation().getYaw());
        to.setPitch(player.getLocation().getPitch());
        player.teleport(to);
        data.lastTeleport = System.currentTimeMillis();
        data.lastLocation = to.clone();
        data.exemptVelocity(1000L);
    }

    /**
     * 1.21.11 Lunge: a spear attack with the Lunge enchantment launches the
     * player forward (client-side impulse, no velocity packet).
     */
    private static boolean lungeContext(Player player, PlayerData data, long now) {
        if (now - data.lastAttackAt > 1500L && !player.isHandRaised()) return false;
        org.bukkit.inventory.ItemStack main = player.getInventory().getItemInMainHand();
        return Compat.isSpear(main) && Compat.hasEnchant(main, Compat.LUNGE);
    }

    // ------------------------------------------------------------------
    // Boat per-packet physics (flags as BOATFLY)
    // ------------------------------------------------------------------

    private void boats(Player player, PlayerData data) {
        VehicleSim vs = data.vehicleSim;
        if (data.vehicleMoves.isEmpty()) return;
        List<double[]> moves = new ArrayList<>(MAX_DRAIN);
        data.vehicleMoves.drainTo(moves, MAX_DRAIN);
        Check check = plugin.check(CheckType.BOATFLY);
        org.bukkit.entity.Entity vehicle = player.getVehicle();
        long now = System.currentTimeMillis();
        boolean judge = check != null && check.enabled()
                && plugin.config().optBool(CheckType.BOATFLY, "packet-sim", true)
                && vehicle instanceof org.bukkit.entity.Boat
                && !player.hasPermission("wepac.bypass")
                && now - data.lastTeleport > 1500L && now - data.joinTime > 3000L
                && singleRider(vehicle);
        if (!judge) {
            vs.reset();
            return;
        }
        double vLimit = plugin.config().optDouble(CheckType.BOATFLY, "sim-vertical-limit", 0.5);
        double hLimit = plugin.config().optDouble(CheckType.BOATFLY, "sim-horizontal-limit", 0.8);
        org.bukkit.World w = vehicle.getWorld();
        for (double[] mv : moves) {
            boolean airborne = boatAirborne(w, mv[1], mv[2], mv[3], vehicle);
            if (!airborne) {
                data.lastBoatSupport = new Location(w, mv[1], mv[2], mv[3]);
            }
            int r = vs.onPacket(mv[1], mv[2], mv[3], (long) mv[0], airborne, vLimit, hLimit);
            if (r == 0) continue;
            if (now - data.lastBoatSimAt > 3000L) data.boatSimBuffer = 0;
            data.lastBoatSimAt = now;
            if (++data.boatSimBuffer < plugin.config().optInt(CheckType.BOATFLY, "sim-buffer", 2)) continue;
            data.boatSimBuffer = 0;
            check.flag(player, data, String.format(Locale.ROOT,
                    r == 1 ? "boat physics: rose above gravity (-0.04/tick) for %d airborne packets"
                           : "boat physics: airborne speed beyond 0.9 drag + 0.04 paddle for %d packets",
                    vs.airPackets), 5);
            boatSetback(player, vehicle, data);
            vs.reset();
            return;
        }
    }

    private static boolean singleRider(org.bukkit.entity.Entity vehicle) {
        int riders = 0;
        for (org.bukkit.entity.Entity e : vehicle.getPassengers()) {
            if (e instanceof Player) riders++;
        }
        return riders == 1;
    }

    /**
     * No support for a boat at (x,y,z): every block overlapping its box
     * (1.375 wide) from 0.25 below to its top is passable, dry and not a
     * bubble column, and no other entity sits beneath it.
     */
    private static boolean boatAirborne(org.bukkit.World w, double x, double y, double z,
                                        org.bukkit.entity.Entity self) {
        int minX = (int) Math.floor(x - 0.7), maxX = (int) Math.floor(x + 0.7);
        int minZ = (int) Math.floor(z - 0.7), maxZ = (int) Math.floor(z + 0.7);
        int minY = (int) Math.floor(y - 0.25), maxY = (int) Math.floor(y + 0.6);
        if (!w.isChunkLoaded(minX >> 4, minZ >> 4) || !w.isChunkLoaded(maxX >> 4, maxZ >> 4)) return false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    org.bukkit.block.Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (!b.isPassable() || b.isLiquid() || m == Material.BUBBLE_COLUMN
                            || m == Material.COBWEB || m == Material.SCAFFOLDING) return false;
                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl
                            && wl.isWaterlogged()) return false;
                }
            }
        }
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(new Location(w, x, y - 0.5, z), 1.2, 0.8, 1.2)) {
            if (e != self && !self.getPassengers().contains(e)) return false;
        }
        return true;
    }

    private void boatSetback(Player player, org.bukkit.entity.Entity vehicle, PlayerData data) {
        if (!plugin.config().optBool(CheckType.BOATFLY, "setback", true)) return;
        Location safe = data.lastBoatSupport;
        if (safe == null || !safe.getWorld().equals(vehicle.getWorld())) return;
        player.leaveVehicle();
        vehicle.teleport(safe);
        Location p = safe.clone().add(0, 0.6, 0);
        p.setYaw(player.getLocation().getYaw());
        p.setPitch(player.getLocation().getPitch());
        player.teleport(p);
        data.lastTeleport = System.currentTimeMillis();
        data.exemptVelocity(1500L);
    }

    /** Max slipperiness of the blocks that can affect this player's movement (permissive). */
    private static double slipperiness(Player player) {
        Location loc = player.getLocation();
        // vanilla: getBlockPosBelowThatAffectsMyMovement = floor(y - 0.5000001)
        Material m = loc.clone().subtract(0, 0.5000001, 0).getBlock().getType();
        return switch (m) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> 0.6;
        };
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
