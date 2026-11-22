package dev.weposs.wepac.packet;

/**
 * Per-packet boat physics (pure math, unit-tested). Fed by every
 * ServerboundMoveVehiclePacket the driver sends (20 Hz).
 *
 * Vanilla AbstractBoat#floatBoat / controlBoat while IN_AIR, per tick:
 *   vy += -0.04                     (gravity, no vertical drag in air)
 *   vh  = vh * 0.9 + paddle         (paddle input adds at most 0.04 forward)
 *
 * So once a boat leaves water/ground, every packet's dy must follow the
 * parabola and horizontal speed can only decay towards the 0.4 b/t paddle
 * equilibrium. BoatFly modules (hover, glide, climb, "side=0.6 up=0.4"
 * velocity modes, slow-fall) all add height or speed the equation does not
 * allow; both excesses accumulate into divergences that never decay while
 * airborne, so a glide that cheats only 0.01 b/t is caught within seconds.
 */
public final class VehicleSim {

    public static final double GRAVITY = 0.04;
    public static final double AIR_FRICTION = 0.9;
    public static final double PADDLE = 0.04;
    public static final double H_EPS = 0.02;
    public static final double NEG_CLAMP = -0.5;

    public double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    public long lastTime = 0L;
    public double lastDy = Double.NaN, lastH = Double.NaN;
    public double vDiv = 0.0, hDiv = 0.0;
    /** airborne packets since leaving support (for context in alerts) */
    public int airPackets = 0;
    public static final double MAX_TAKEOFF = 0.8;
    private double lastSupportDy = 0.0;
    private boolean takeoff = false;

    public void reset() {
        lastX = lastY = lastZ = Double.NaN;
        lastTime = 0L;
        lastDy = lastH = Double.NaN;
        vDiv = hDiv = 0.0;
        airPackets = 0;
        takeoff = false;
        lastSupportDy = 0.0;
    }

    /** Result of one packet: 0 = fine, 1 = vertical overflow, 2 = horizontal overflow. */
    public int onPacket(double x, double y, double z, long t, boolean airborne,
                        double vLimit, double hLimit) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            reset();
            return 0;
        }
        if (Double.isNaN(lastX)) {
            lastX = x; lastY = y; lastZ = z; lastTime = t;
            return 0;
        }
        double dy = y - lastY;
        double h = Math.hypot(x - lastX, z - lastZ);
        long dt = t - lastTime;
        lastX = x; lastY = y; lastZ = z; lastTime = t;

        if (!airborne) {
            // water, bubble column, ground, ice road: legal support - re-anchor;
            // the takeoff packet is adopted, not charged (bounded below)
            lastSupportDy = dy;
            lastDy = Double.NaN;
            lastH = Double.NaN;
            takeoff = true;
            vDiv = 0.0;
            hDiv = 0.0;
            airPackets = 0;
            return 0;
        }
        if (dt < 0L || dt > PacketPhysics.Sim.MAX_GAP_MS) {
            lastDy = Double.NaN;
            lastH = Double.NaN;
            return 0;
        }
        if (dt > PacketPhysics.Sim.REANCHOR_GAP_MS || dt < 25L) {
            // pulse / burst: tick count unknown - re-anchor, KEEP divergence
            lastDy = Double.NaN;
            lastH = Double.NaN;
            return 0;
        }
        airPackets++;
        if (Double.isNaN(lastDy)) {
            if (takeoff) {
                takeoff = false;
                // an upward bubble column launches boats at ~0.7, a slime/bed
                // bounce reflects the previous fall speed - anything above is a
                // launch no vanilla support produces
                double maxTakeoff = Math.max(MAX_TAKEOFF, -lastSupportDy + 0.1);
                if (dy > maxTakeoff) vDiv += dy - maxTakeoff;
            }
            lastDy = Math.min(dy, Math.max(MAX_TAKEOFF, -lastSupportDy + 0.1));
            lastH = h;
            if (vDiv > vLimit) {
                vDiv = 0.0;
                return 1;
            }
            return 0;
        }
        int steps = (int) Math.min(5, Math.max(1, Math.round(dt / 50.0)));
        // vertical: v_{n} = v_{n-1} - 0.04 each tick
        double v = lastDy, expY = 0.0;
        for (int i = 0; i < steps; i++) {
            v -= GRAVITY;
            expY += v;
        }
        double errY = dy - expY;
        vDiv += errY;
        if (vDiv < NEG_CLAMP) vDiv = NEG_CLAMP;
        // horizontal: vh_n <= vh_{n-1} * 0.9 + 0.04
        double vh = lastH, maxH = 0.0;
        for (int i = 0; i < steps; i++) {
            vh = vh * AIR_FRICTION + PADDLE;
            maxH += vh;
        }
        double errH = h - (maxH + H_EPS * steps);
        if (errH > 0) hDiv += errH;
        // the bound, not the observation, carries forward (no banking speed)
        lastDy = Math.min(dy / steps, v);
        lastH = Math.min(h / steps, vh);

        if (vDiv > vLimit) {
            vDiv = 0.0;
            return 1;
        }
        if (hDiv > hLimit) {
            hDiv = 0.0;
            return 2;
        }
        return 0;
    }
}
