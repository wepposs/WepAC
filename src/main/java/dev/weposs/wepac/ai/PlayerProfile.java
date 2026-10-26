package dev.weposs.wepac.ai;

/**
 * Per-player behavioural baseline learned online with Welford's algorithm.
 * Used for z-scores in staff info and as an adaptive sanity reference.
 * Session-scoped by design: profiles are not persisted (see README roadmap).
 */
public final class PlayerProfile {

    private final int dims;
    private final double[] mean;
    private final double[] m2;
    private long count;

    public PlayerProfile(int dims) {
        this.dims = dims;
        this.mean = new double[dims];
        this.m2 = new double[dims];
    }

    public void update(double[] features) {
        int n = Math.min(dims, features.length);
        count++;
        for (int i = 0; i < n; i++) {
            double v = Double.isFinite(features[i]) ? features[i] : 0.0;
            double delta = v - mean[i];
            mean[i] += delta / count;
            m2[i] += delta * (v - mean[i]);
        }
    }

    public long count() {
        return count;
    }

    public double mean(int i) {
        return mean[i];
    }

    public double stddev(int i) {
        return count < 2 ? 0.0 : Math.sqrt(m2[i] / (count - 1));
    }

    public double zscore(int i, double value) {
        double sd = stddev(i);
        return sd < 1e-9 ? 0.0 : (value - mean[i]) / sd;
    }
}
