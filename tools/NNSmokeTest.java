import dev.weposs.wepac.ai.NeuralNetwork;

import java.util.Random;

/**
 * Standalone smoke test: can the WepAC network actually learn to separate
 * cheater-like telemetry from legit telemetry? Run:
 *   javac -cp target/classes -d tools tools/NNSmokeTest.java
 *   java  -cp target/classes;tools NNSmokeTest
 */
public class NNSmokeTest {

    static final int DIMS = 14;
    static final Random R = new Random(7);

    // population "normaliser" like AIBrain uses
    static final double[] mean = new double[DIMS];
    static final double[] std = new double[DIMS];

    public static void main(String[] args) {
        double[][] train = new double[400][];
        double[] labels = new double[400];
        for (int i = 0; i < 400; i++) {
            boolean cheater = i % 2 == 0;
            train[i] = cheater ? cheaterSample() : legitSample();
            labels[i] = cheater ? 1.0 : 0.0;
        }

        // learn population stats
        long n = 0;
        for (double[] f : train) {
            n++;
            for (int j = 0; j < DIMS; j++) {
                double d = f[j] - mean[j];
                mean[j] += d / n;
                double d2 = f[j] - mean[j];
                std[j] = n < 2 ? 1 : Math.sqrt((std[j] * std[j] * (n - 2) + d * d2) / (n - 1));
            }
        }

        NeuralNetwork net = new NeuralNetwork(DIMS, 16, 8, 1);
        net.setLearningRate(0.02);

        // online mini-batch training like AIBrain.trainRound()
        for (int round = 0; round < 300; round++) {
            for (int b = 0; b < 32; b++) {
                int idx = R.nextInt(train.length);
                net.train(normalize(train[idx]), labels[idx]);
            }
        }

        // evaluate on fresh unseen samples
        int correct = 0, total = 200;
        double errSum = 0;
        for (int i = 0; i < total; i++) {
            boolean cheater = i % 2 == 0;
            double[] f = cheater ? cheaterSample() : legitSample();
            double p = net.predict(normalize(f));
            errSum += Math.abs(p - (cheater ? 1.0 : 0.0));
            if ((p > 0.5) == cheater) correct++;
        }
        System.out.printf("accuracy=%.1f%%  avgErr=%.3f  trainSteps=%d%n",
                100.0 * correct / total, errSum / total, net.trainSteps());
        if (correct < total * 0.9) {
            System.out.println("FAIL: network did not learn separation");
            System.exit(1);
        }
        System.out.println("OK: network learned to separate cheater/legit telemetry");
    }

    static double[] normalize(double[] raw) {
        double[] out = new double[DIMS];
        for (int i = 0; i < DIMS; i++) {
            out[i] = Math.max(-5, Math.min(5, (raw[i] - mean[i]) / Math.max(std[i], 1e-6)));
        }
        return out;
    }

    // feature order: cps, clickStd, clickMin, speedAvg, speedMax, airRatio,
    //               riseRatio, aps, reachAvg, breakMs, placeMs, yawRate, pitchRate, ping
    static double[] legitSample() {
        return new double[]{
                4 + R.nextGaussian() * 1.5,      // cps
                60 + R.nextGaussian() * 25,      // click stddev ms
                55 + R.nextGaussian() * 20,      // min interval
                0.15 + R.nextGaussian() * 0.03,  // speed avg
                0.28 + R.nextGaussian() * 0.05,  // speed max
                0.25 + R.nextGaussian() * 0.08,  // air ratio
                0.35 + R.nextGaussian() * 0.15,  // rise ratio
                0.8 + R.nextGaussian() * 0.3,    // aps
                2.1 + R.nextGaussian() * 0.3,    // reach avg
                0.35 + R.nextGaussian() * 0.1,   // break s
                0.30 + R.nextGaussian() * 0.1,   // place s
                8 + R.nextGaussian() * 4,        // yaw rate
                5 + R.nextGaussian() * 3,        // pitch rate
                0.6 + R.nextGaussian() * 0.2     // ping/100
        };
    }

    static double[] cheaterSample() {
        return new double[]{
                16 + R.nextGaussian() * 1.0,     // cps
                8 + R.nextGaussian() * 2,        // robotic consistency
                45 + R.nextGaussian() * 3,
                0.34 + R.nextGaussian() * 0.02,  // speed hacks
                0.60 + R.nextGaussian() * 0.05,
                0.75 + R.nextGaussian() * 0.1,   // fly: mostly airborne
                0.70 + R.nextGaussian() * 0.1,   // rising/hovering
                10 + R.nextGaussian() * 1.5,     // aura: attacks/sec
                4.3 + R.nextGaussian() * 0.4,    // reach
                0.06 + R.nextGaussian() * 0.01,  // fastbreak
                0.05 + R.nextGaussian() * 0.01,  // fastplace
                45 + R.nextGaussian() * 15,      // snappy rotations
                30 + R.nextGaussian() * 10,
                0.6 + R.nextGaussian() * 0.2
        };
    }
}
