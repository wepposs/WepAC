package dev.weposs.wepac.ai;

/**
 * Fixed-size feature vector describing recent player behaviour.
 * Raw units; normalisation happens in {@link AIBrain}.
 */
public final class FeatureVector {

    public static final int SIZE = 18;

    public static final String[] NAMES = {
            "cps",                 // 0  clicks per second (rolling 3s)
            "clickStd",            // 1  stddev of click intervals (ms)
            "clickMin",            // 2  min click interval (ms)
            "speedAvg",            // 3  avg horizontal speed (blocks/tick)
            "speedMax",            // 4  max horizontal speed
            "airRatio",            // 5  fraction of samples airborne
            "riseRatio",           // 6  fraction of airborne samples rising
            "aps",                 // 7  attacks per second (rolling 3s)
            "reachAvg",            // 8  avg attack reach (blocks)
            "breakMs",             // 9  avg block-break interval (ms, /1000)
            "placeMs",             // 10 avg block-place interval (ms, /1000)
            "yawRate",             // 11 avg |yaw delta| per move (deg)
            "pitchRate",           // 12 avg |pitch delta| per move (deg)
            "ping",                // 13 ping (ms, /100)
            "hitAngleAvg",         // 14 avg aim-to-victim angle at hits (deg)
            "chargeAvg",           // 15 avg 1.9 attack-cooldown charge at hits (0..1)
            "airAttackRatio",      // 16 fraction of PvP hits landed while airborne (crits)
            "pitchRevRate"         // 17 large pitch reversals/sec (silent rotations)
    };

    private final double[] values = new double[SIZE];

    public void set(int i, double v) {
        values[i] = Double.isFinite(v) ? v : 0.0;
    }

    public double get(int i) {
        return values[i];
    }

    public double[] toArray() {
        return values.clone();
    }
}
