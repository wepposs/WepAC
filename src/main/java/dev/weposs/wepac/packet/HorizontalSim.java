package dev.weposs.wepac.packet;

/**
 * Per-packet horizontal movement bound (pure math, unit-tested).
 *
 * Vanilla LivingEntity#travel per tick (horizontal):
 *   v += input accel      (ground: speed * 0.21600002 / slip^3 ; air: 0.02 / 0.026 sprinting)
 *   move(v)               (collisions can only SHRINK the displacement)
 *   v *= slip * 0.91      (air: 0.91)
 * plus a one-off +0.2 on a sprint-jump tick.
 *
 * We do not know the player's inputs, so we take the MAXIMUM the vanilla
 * equation allows (full input aligned with the current velocity). Any real
 * displacement above that bound is extra speed that no key combination can
 * produce - Speed/Strafe/BHop/Timer-in-place/LongJump horizontal components.
 * Excess accumulates into a divergence with a slow decay, so one noisy packet
 * never flags but a cheat that adds even ~3% per tick does.
 */
public final class HorizontalSim {

    public static final double JUMP_BOOST = 0.2;
    /** Per-tick slack: entity push (0.05 / colliding player), float noise, 1.21 attribute rounding. */
    public static final double EPS = 0.03;
    /** Divergence decays by this per legit packet (forgives sustained rounding). */
    public static final double DECAY = 0.005;

    public double lastX = Double.NaN, lastZ = Double.NaN;
    public long lastTime = 0L;
    /** Velocity carried into the next tick (after friction), as bounded by the sim. */
    public double carried = Double.NaN;
    public double divergence = 0.0;
    /**
     * Movement-input multiplier for the NEXT packets: 1.0 normally, 0.2 while
     * using an item (eating, drinking, bow, shield, spyglass...) - vanilla
     * scales the input vector by 0.2 and cannot sprint, so no sprint-jump boost.
     */
    public double inputScale = 1.0;

    public void reset() {
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastTime = 0L;
        carried = Double.NaN;
        divergence = 0.0;
    }

    /** Re-anchor at a position without judging (teleport, exemption, velocity). */
    public void resync(double x, double z, long timeMs) {
        lastX = x;
        lastZ = z;
        lastTime = timeMs;
        carried = Double.NaN;
        divergence = 0.0;
    }

    /** Ground acceleration for a movement speed attribute value (already sprint-scaled) and slipperiness. */
    public static double groundAccel(double speed, double slip) {
        return speed * (0.21600002 / (slip * slip * slip));
    }

    /**
     * @param speed  movement speed attribute incl. sprint/effects (e.g. 0.13 sprinting)
     * @param slip   block slipperiness under the player (0.6 default, ice 0.98)
     * @param ground whether this tick starts on ground (permissive: client OR server)
     * @param jumped whether a jump started this tick
     * @param limit  divergence limit (blocks)
     * @return overflow (&gt; 0) when the accumulated excess exceeds the limit
     */
    public double onPacket(double x, double z, long timeMs, double speed, double slip,
                           boolean ground, boolean jumped, double limit) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            reset();
            return 0.0;
        }
        if (Double.isNaN(lastX)) {
            resync(x, z, timeMs);
            return 0.0;
        }
        double dx = x - lastX, dz = z - lastZ;
        long dt = timeMs - lastTime;
        lastX = x;
        lastZ = z;
        lastTime = timeMs;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dt < 0L || dt > PacketPhysics.Sim.REANCHOR_GAP_MS) {
            // unknown number of ticks elapsed: adopt observation, keep charge
            carried = Double.NaN;
            return 0.0;
        }
        int steps = dt < 25L ? 0 : (int) Math.min(5, Math.max(1, Math.round(dt / 50.0)));
        if (steps == 0) {
            // burst flush (lag): several real ticks arriving back-to-back
            carried = Double.NaN;
            return 0.0;
        }
        double friction = ground ? slip * 0.91 : 0.91;
        double accel = (ground ? groundAccel(speed, slip) : (speed > 0.11 ? 0.026 : 0.02)) * inputScale;
        boolean canSprintJump = inputScale >= 0.999;
        double v = Double.isNaN(carried) ? dist / steps : carried;
        if (Double.isNaN(carried)) {
            // re-anchored: adopt the observation as the baseline, judge from next packet
            carried = (dist / steps) * friction;
            return 0.0;
        }
        double bound = 0.0;
        for (int i = 0; i < steps; i++) {
            v = v + accel + (i == 0 && jumped && canSprintJump ? JUMP_BOOST : 0.0);
            bound += v;
            v *= friction;
        }
        bound += EPS * steps;
        double excess = dist - bound;
        // carry the smaller of observation/bound: a cheater must not bank speed
        double observedPerTick = dist / steps;
        double lastTickV = Math.min(observedPerTick, v / friction);
        carried = lastTickV * friction;
        if (excess > 0) {
            divergence += excess;
        } else {
            divergence = Math.max(0.0, divergence - DECAY);
        }
        if (divergence > limit) {
            double over = divergence;
            divergence = 0.0;
            return over;
        }
        return 0.0;
    }
}
