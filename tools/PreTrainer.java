import dev.weposs.wepac.ai.NeuralNetwork;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Offline pre-trainer for the WepAC AI, v2.
 *
 * Archetypes are derived from REAL cheat client parameters (vesence: cps 15,
 * attackRange 3.0-6.0, Shard settle gate 8.5 deg, GCD-snapped rotations,
 * Neuro/Spooky/Snap rotation modes, 1.8-CPS tick-quantised pacing) plus the
 * generic cheat and honest-player archetypes.
 *
 * Feature order (FeatureVector v15):
 *  0 cps, 1 clickStd(ms), 2 clickMin(ms), 3 speedAvg(b/t), 4 speedMax(b/t),
 *  5 airRatio, 6 riseRatio, 7 aps, 8 reachAvg, 9 breakSec, 10 placeSec,
 *  11 yawRate(deg), 12 pitchRate(deg), 13 ping/100, 14 hitAngleAvg(deg)
 *
 * Run:
 *   javac -cp target/classes -d tools tools/PreTrainer.java
 *   java  -cp "target/classes;tools" PreTrainer
 *   -> pretrained/model.bin  (copy to plugins/WepAC/ai/model.bin)
 */
public class PreTrainer {

    static final int DIMS = 18;
    static final int MAGIC = 0x57455041; // must match AIBrain
    static final Random R = new Random(1337);

    record Archetype(String name, boolean cheat, double[] mean, double[] sd) {}

    public static void main(String[] args) throws Exception {
        List<Archetype> archetypes = buildArchetypes();
        List<double[]> features = new ArrayList<>();
        List<Double> labels = new ArrayList<>();
        List<String> origins = new ArrayList<>();

        for (Archetype a : archetypes) {
            int n = a.cheat() ? 220 : 500;
            for (int i = 0; i < n; i++) {
                features.add(sample(a));
                labels.add(a.cheat() ? 1.0 : 0.0);
                origins.add(a.name());
            }
        }
        System.out.printf("Generated %d samples (%d archetypes)%n", features.size(), archetypes.size());

        // population normaliser (identical Welford update to AIBrain.observe)
        double[] mean = new double[DIMS];
        double[] std = new double[DIMS];
        long normCount = 0;
        for (double[] f : features) {
            normCount++;
            for (int j = 0; j < DIMS; j++) {
                double d = f[j] - mean[j];
                mean[j] += d / normCount;
                double d2 = f[j] - mean[j];
                std[j] = normCount < 2 ? 1.0
                        : Math.sqrt((std[j] * std[j] * (normCount - 2) + d * d2) / (normCount - 1));
            }
        }

        // shuffle + 85/15 split
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < features.size(); i++) order.add(i);
        Collections.shuffle(order, R);
        int split = (int) (order.size() * 0.85);
        List<Integer> trainIdx = order.subList(0, split);
        List<Integer> testIdx = order.subList(split, order.size());

        NeuralNetwork net = new NeuralNetwork(DIMS, 32, 16, 1);
        net.setLearningRate(0.02);

        int rounds = 18000;
        for (int round = 1; round <= rounds; round++) {
            for (int b = 0; b < 32; b++) {
                int idx = trainIdx.get(R.nextInt(trainIdx.size()));
                net.train(normalize(features.get(idx), mean, std), labels.get(idx));
            }
            if (round == 4000) net.setLearningRate(0.008);
            if (round == 7500) net.setLearningRate(0.003);
            if (round == 14000) net.setLearningRate(0.0012);
            if (round % 1000 == 0) {
                double acc = accuracy(net, features, labels, testIdx, mean, std);
                System.out.printf(Locale.ROOT, "round %4d: test accuracy %.2f%%%n", round, acc * 100);
            }
        }

        report(net, features, labels, origins, testIdx, mean, std);

        // save model.bin in the exact AIBrain format
        File out = new File("pretrained/model.bin");
        out.getParentFile().mkdirs();
        List<Integer> shuffledAll = new ArrayList<>(order);
        Collections.shuffle(shuffledAll, R);
        int bufCap = Math.min(5000, shuffledAll.size());
        long now = System.currentTimeMillis();
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            dos.writeInt(MAGIC);
            net.save(dos);
            dos.writeLong(normCount);
            for (double v : mean) dos.writeDouble(v);
            for (double v : std) dos.writeDouble(v);
            dos.writeInt(bufCap);
            for (int i = 0; i < bufCap; i++) {
                int idx = shuffledAll.get(i);
                double[] f = features.get(idx);
                for (int j = 0; j < DIMS; j++) dos.writeDouble(f[j]);
                dos.writeDouble(labels.get(idx));
                dos.writeLong(now - R.nextInt(86_400_000));
            }
        }
        System.out.printf("Saved %s (%.1f KB, buffer %d samples, trainSteps %d)%n",
                out.getPath(), out.length() / 1024.0, bufCap, net.trainSteps());
    }

    static double[] normalize(double[] raw, double[] mean, double[] std) {
        double[] out = new double[DIMS];
        for (int i = 0; i < DIMS; i++) {
            out[i] = Math.max(-5, Math.min(5, (raw[i] - mean[i]) / Math.max(std[i], 1e-6)));
        }
        return out;
    }

    static double[] sample(Archetype a) {
        double[] f = new double[DIMS];
        for (int i = 0; i < DIMS; i++) {
            f[i] = Math.max(0, a.mean()[i] + R.nextGaussian() * a.sd()[i]);
        }
        for (int i : new int[]{5, 6}) f[i] = Math.min(1.0, f[i]);
        f[2] = Math.max(5, f[2]);
        return f;
    }

    static double accuracy(NeuralNetwork net, List<double[]> f, List<Double> l,
                           List<Integer> idx, double[] mean, double[] std) {
        int ok = 0;
        for (int i : idx) {
            double p = net.predict(normalize(f.get(i), mean, std));
            if ((p > 0.5) == (l.get(i) > 0.5)) ok++;
        }
        return (double) ok / idx.size();
    }

    static void report(NeuralNetwork net, List<double[]> features, List<Double> labels,
                       List<String> origins, List<Integer> testIdx, double[] mean, double[] std) {
        List<String> names = new ArrayList<>();
        for (String o : origins) if (!names.contains(o)) names.add(o);

        System.out.println("\n=== per-archetype results (test set) ===");
        double worstCheat = 1.0, worstLegit = 1.0;
        for (String name : names) {
            int total = 0, correct = 0;
            double sum = 0;
            boolean cheat = false;
            for (int i : testIdx) {
                if (!origins.get(i).equals(name)) continue;
                total++;
                double p = net.predict(normalize(features.get(i), mean, std));
                cheat = labels.get(i) > 0.5;
                sum += p;
                if ((p > 0.5) == cheat) correct++;
            }
            if (total == 0) continue;
            double rate = (double) correct / total;
            if (cheat) worstCheat = Math.min(worstCheat, rate);
            else worstLegit = Math.min(worstLegit, rate);
            System.out.printf(Locale.ROOT, "%-22s %-7s %5.1f%%  avgP=%.3f%n",
                    name, cheat ? "CHEAT" : "legit", rate * 100, sum / total);
        }
        System.out.printf(Locale.ROOT, "%nWORST cheat detection: %.1f%% | WORST legit protection: %.1f%%%n",
                worstCheat * 100, worstLegit * 100);
    }

    static List<Archetype> buildArchetypes() {
        List<Archetype> list = new ArrayList<>();

        // ---------------- vesence-derived archetypes (real client defaults) -------------
        // Spooky (default rotation), 1.8-CPS 15, attackRange ~3.0, GCD-snapped rotations
        list.add(new Archetype("vesence-spooky", true,
                new double[]{15, 10, 50, .22, .34, .30, .38, 12, 3.1, .40, .35, 32, 18, .5, 0.9},
                new double[]{2, 4, 8, .04, .05, .10, .15, 3, .25, .15, .15, 12, 8, .25, .6}));
        // Neuro mode: humanised rotations trained on samples, legit cooldown
        list.add(new Archetype("vesence-neuro", true,
                new double[]{10, 35, 55, .21, .32, .30, .38, 7, 3.0, .40, .35, 22, 12, .55, 0.6},
                new double[]{2, 12, 15, .04, .05, .10, .15, 1.5, .20, .15, .15, 8, 5, .3, .5}));
        // Snap mode (Fast/Smooth/Random), FOV 40
        list.add(new Archetype("vesence-snap", true,
                new double[]{14, 12, 50, .22, .34, .30, .38, 10, 3.1, .40, .35, 85, 45, .5, 0.4},
                new double[]{2, 5, 10, .04, .05, .10, .15, 3, .25, .15, .15, 30, 20, .25, .3}));
        // Shard mode: attack only when aim settled (residual <= 8.5 deg)
        list.add(new Archetype("vesence-shard", true,
                new double[]{12, 18, 52, .22, .33, .30, .38, 9, 3.0, .40, .35, 28, 16, .5, 1.6},
                new double[]{3, 6, 12, .04, .05, .10, .15, 2, .20, .15, .15, 10, 6, .25, .9}));
        // vesence Speed / Jesus / LongJump movement modes
        list.add(new Archetype("vesence-speed", true,
                new double[]{9, 40, 50, .50, .85, .40, .40, 2, 2.8, .40, .35, 18, 8, .5, 3.0},
                new double[]{2, 15, 15, .08, .15, .10, .12, 1, .40, .15, .15, 8, 4, .25, 1.5}));
        list.add(new Archetype("vesence-jesus", true,
                new double[]{6, 60, 55, .24, .36, .72, .13, .8, 2.3, .50, .45, 12, 15, .6, 3.5},
                new double[]{2, 25, 20, .05, .06, .10, .06, .4, .40, .20, .20, 6, 6, .3, 1.5}));
        list.add(new Archetype("vesence-longjump", true,
                new double[]{7, 50, 50, .55, 1.00, .55, .45, 1, 2.5, .60, .55, 15, 10, .5, 4.0},
                new double[]{2, 20, 15, .15, .25, .12, .15, .5, .40, .25, .25, 8, 5, .25, 2.0}));

        // ---------------- archetypes from the source-code collection -------------------
        // Crystall: CPS default 30 (5-60), snap speed 50-180 deg
        list.add(new Archetype("crystall-aura", true,
                new double[]{30, 6, 25, .22, .34, .28, .36, 14, 3.4, .40, .35, 90, 50, .5, 0.8},
                new double[]{3, 2, 5, .04, .05, .10, .15, 3, .30, .15, .15, 25, 15, .25, .5}));
        // DELTA: instant 180 deg/tick rotations with optional smoothing
        list.add(new Archetype("delta-aura", true,
                new double[]{12, 15, 50, .22, .33, .30, .38, 8, 3.2, .40, .35, 120, 60, .5, 0.5},
                new double[]{2, 5, 12, .04, .05, .10, .15, 2, .25, .15, .15, 40, 20, .25, .3}));
        // Arbuz: stepped rotation 90 yaw / 6-9 pitch, raytrace-gated hits
        list.add(new Archetype("arbuz-aura", true,
                new double[]{10, 30, 50, .21, .32, .30, .38, 7, 3.1, .40, .35, 55, 10, .5, 1.0},
                new double[]{2, 10, 12, .04, .05, .10, .15, 2, .25, .15, .15, 20, 4, .25, .6}));
        // Cometa: FOV-90 "legit" style rotations
        list.add(new Archetype("cometa-aura", true,
                new double[]{11, 25, 50, .21, .32, .30, .38, 8, 3.15, .40, .35, 30, 15, .5, 1.2},
                new double[]{2, 8, 12, .04, .05, .10, .15, 2, .25, .15, .15, 12, 6, .25, .7}));
        // Wild: snap rotations, FOV 90
        list.add(new Archetype("wild-aura", true,
                new double[]{13, 14, 48, .22, .33, .30, .38, 10, 3.2, .40, .35, 70, 35, .5, 0.7},
                new double[]{2, 5, 10, .04, .05, .10, .15, 3, .25, .15, .15, 25, 12, .25, .4}));
        // Meow: neuro controller + Polar-bypass aim (humanised but aim-gated)
        list.add(new Archetype("meow-neuro", true,
                new double[]{10, 38, 55, .21, .32, .30, .38, 7, 3.0, .40, .35, 20, 11, .55, 0.5},
                new double[]{2, 12, 15, .04, .05, .10, .15, 1.5, .20, .15, .15, 8, 5, .3, .4}));
        // Minced: Grim-bypass mode, conservative timings, still precise hits
        list.add(new Archetype("minced-grim", true,
                new double[]{9, 40, 55, .20, .31, .30, .38, 6.5, 3.0, .40, .35, 24, 13, .5, 0.9},
                new double[]{2, 12, 15, .04, .05, .10, .15, 1.5, .20, .15, .15, 9, 5, .3, .6}));
        // Essence: classic fast snap aura
        list.add(new Archetype("essence-aura", true,
                new double[]{14, 12, 48, .22, .34, .30, .38, 11, 3.2, .40, .35, 40, 22, .5, 1.1},
                new double[]{2, 4, 10, .04, .05, .10, .15, 2.5, .25, .15, .15, 15, 8, .25, .6}));
        // Euphoria: high-CPS aggressive
        list.add(new Archetype("euphoria-aura", true,
                new double[]{16, 9, 45, .22, .34, .30, .38, 12, 3.2, .40, .35, 48, 26, .5, 0.8},
                new double[]{2, 3, 9, .04, .05, .10, .15, 3, .25, .15, .15, 16, 9, .25, .5}));
        // Exploit: mid-tempo aim-gated aura
        list.add(new Archetype("exploit-aura", true,
                new double[]{12, 20, 50, .21, .32, .30, .38, 8, 3.3, .40, .35, 35, 18, .5, 1.3},
                new double[]{3, 7, 12, .04, .05, .10, .15, 2, .30, .15, .15, 13, 7, .25, .7}));

        // ------- batch 2: 10-agent sweep of the full source collection -------
        // Rockstar: cps 8-12, rot 180deg/tick, GCD-snap, raytrace gate
        list.add(new Archetype("rockstar-aura", true,
                new double[]{10, 25, 50, .22, .33, .30, .38, 8, 3.1, .40, .35, 90, 50, .5, 0.8},
                new double[]{2, 8, 10, .04, .05, .10, .15, 2, .25, .15, .15, 30, 15, .25, .5}));
        // Zenith: cooldown-gated, easing+GCD rotations, raycast gate
        list.add(new Archetype("zenith-aura", true,
                new double[]{8, 40, 55, .21, .32, .30, .38, 7, 3.0, .40, .35, 60, 35, .5, 0.7},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 25, 12, .25, .4}));
        // Neverlose (brand "copyright"): 25ms strike gate, GCD+noise, raytrace+isLooking
        list.add(new Archetype("neverlose-aura", true,
                new double[]{9, 35, 50, .22, .33, .30, .38, 12, 3.2, .40, .35, 70, 40, .5, 0.6},
                new double[]{2, 10, 12, .04, .05, .10, .15, 3, .25, .15, .15, 25, 15, .25, .4}));
        // Nursultan: mode-based timing, fov 180, NO aim gate (wide hit angles)
        list.add(new Archetype("nursultan-aura", true,
                new double[]{8, 45, 55, .21, .32, .30, .38, 5, 3.0, .40, .35, 35, 20, .5, 6.0},
                new double[]{2, 15, 15, .04, .05, .10, .15, 1.5, .25, .15, .15, 12, 8, .25, 3.0}));
        // Stalin/Nexis: Neuro steps yaw 22deg pitch 12deg, crosshair gate
        list.add(new Archetype("stalin-neuro", true,
                new double[]{9, 30, 50, .21, .32, .30, .38, 6, 3.0, .40, .35, 22, 12, .5, 0.9},
                new double[]{2, 8, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 6, 4, .25, .6}));
        // Monoton/Nuclear: ~2.2 cps, sine jitter, NO aim gate
        list.add(new Archetype("monoton-aura", true,
                new double[]{5, 60, 55, .20, .31, .30, .38, 2.2, 3.0, .40, .35, 40, 20, .5, 5.0},
                new double[]{1.5, 20, 15, .04, .05, .10, .15, .6, .20, .15, .15, 15, 8, .25, 2.5}));
        // EvaWare: 20deg/tick smooth budget, GRU neuro, raytrace gate ON
        list.add(new Archetype("evaware-smooth", true,
                new double[]{5, 55, 55, .21, .32, .30, .38, 2, 3.0, .40, .35, 20, 10, .5, 1.0},
                new double[]{1.5, 18, 15, .04, .05, .10, .15, .5, .20, .15, .15, 5, 3, .25, .6}));
        // Toshiba: NoRotation silent aura, through walls, range up to 6
        list.add(new Archetype("toshiba-silent", true,
                new double[]{8, 40, 50, .21, .32, .30, .38, 6, 4.5, .40, .35, 4, 3, .5, 25},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .8, .15, .15, 3, 2, .25, 12}));
        // Minced Builder: ultra-slow 6deg/tick rotations, gate OFF by default
        list.add(new Archetype("minced-builder", true,
                new double[]{8, 42, 52, .20, .31, .30, .38, 7, 3.0, .40, .35, 9, 5, .5, 4.5},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 3, 2, .25, 2.2}));
        // Wild (W1ld): AI/Neuro rotations, canSee gate, 7 BPS speed
        list.add(new Archetype("wild-ai", true,
                new double[]{8, 40, 52, .21, .33, .30, .38, 7, 3.0, .40, .35, 25, 14, .5, 1.2},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 8, 5, .25, .7}));
        // Velka: ONNX aim model, 500ms + miss-every-4th, NO raytrace, fov 90
        list.add(new Archetype("velka-ml", true,
                new double[]{4, 70, 60, .21, .32, .30, .38, 1.8, 3.4, .40, .35, 18, 10, .5, 8},
                new double[]{1.5, 20, 15, .04, .05, .10, .15, .5, .4, .15, .15, 6, 4, .25, 4}));
        // ExploitCrack: snap 360-625deg/tick, mandatory raycast gate
        list.add(new Archetype("exploit-snap", true,
                new double[]{5, 55, 52, .21, .32, .30, .38, 2, 3.0, .40, .35, 120, 40, .5, 0.5},
                new double[]{1.5, 15, 12, .04, .05, .10, .15, .5, .20, .15, .15, 60, 20, .25, .3}));
        // Sadnes: 360deg/tick subtle snap, through walls, NO gate
        list.add(new Archetype("sadnes-snap", true,
                new double[]{8, 45, 50, .21, .32, .30, .38, 6, 3.0, .40, .35, 150, 10, .5, 15},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 80, 6, .25, 8}));
        // WexSide: cps 15, range 3+2=5 blocks, humanised overshoot
        list.add(new Archetype("wexside-hvh", true,
                new double[]{15, 12, 45, .22, .34, .30, .38, 12, 5.0, .40, .35, 60, 30, .5, 1.0},
                new double[]{2, 4, 10, .04, .05, .10, .15, 3, .9, .15, .15, 25, 10, .25, .6}));
        // KeyzDLC: aim-assist range 4.5 fov 360 + triggerbot raycast
        list.add(new Archetype("keyz-aimassist", true,
                new double[]{6, 50, 50, .20, .31, .30, .38, 3, 4.5, .40, .35, 30, 15, .5, 1.0},
                new double[]{2, 15, 15, .04, .05, .10, .15, 1, .7, .15, .15, 10, 6, .25, .7}));
        // HealthClient: ONNX rotation head + Kalman, reaction 150-280ms, no gate
        list.add(new Archetype("health-ml", true,
                new double[]{7, 50, 48, .21, .32, .30, .38, 5, 3.0, .40, .35, 20, 12, .5, 2.5},
                new double[]{2, 15, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 7, 5, .25, 1.5}));
        // Skycore: turnSpeed 180-360 snap, raycast gate default true
        list.add(new Archetype("skycore-snap", true,
                new double[]{8, 42, 50, .21, .32, .30, .38, 8, 3.0, .40, .35, 150, 60, .5, 0.8},
                new double[]{2, 12, 12, .04, .05, .10, .15, 2, .20, .15, .15, 60, 25, .25, .5}));
        // Wave: dynamic 80+16*dist deg/tick, gcd 0.15deg, raytrace gate
        list.add(new Archetype("wave-aura", true,
                new double[]{5, 60, 55, .21, .32, .30, .38, 2.1, 3.2, .40, .35, 110, 50, .5, 0.8},
                new double[]{1.5, 20, 15, .04, .05, .10, .15, .6, .25, .15, .15, 30, 15, .25, .5}));
        // Xrose: GCD + gaussian noise 0.15s, raytrace gate
        list.add(new Archetype("xrose-aura", true,
                new double[]{5, 58, 55, .21, .31, .30, .38, 2, 3.0, .40, .35, 30, 16, .5, 0.9},
                new double[]{1.5, 18, 15, .04, .05, .10, .15, .5, .20, .15, .15, 10, 6, .25, .6}));
        // 3ssenc/Essence: exotic aim-path shapes (orbit/zigzag/pulse), NO gate
        list.add(new Archetype("essence-pattern", true,
                new double[]{7, 45, 50, .21, .32, .30, .38, 5, 3.0, .40, .35, 45, 25, .5, 6.0},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 20, 10, .25, 3}));
        // Timoha: slow 0.2 rot, fov 45, rayTrace+canHit gates
        list.add(new Archetype("timoha-aura", true,
                new double[]{6, 50, 52, .21, .32, .30, .38, 3, 3.0, .40, .35, 10, 6, .5, 1.2},
                new double[]{2, 15, 12, .04, .05, .10, .15, 1, .20, .15, .15, 4, 3, .25, .8}));
        // Obsidian/Punch (shared lineage): 467ms, GCD-snap 8deg/6deg caps, no gate
        list.add(new Archetype("obsidian-punch", true,
                new double[]{5, 58, 55, .20, .31, .30, .38, 2.1, 3.0, .40, .35, 15, 8, .5, 4.0},
                new double[]{1.5, 18, 15, .04, .05, .10, .15, .6, .20, .15, .15, 5, 3, .25, 2}));
        // Phobia: yaw clamp 12-55deg/tick, GCD+randomLerp, rayTrace gate
        list.add(new Archetype("phobia-smooth", true,
                new double[]{5, 58, 55, .21, .32, .30, .38, 2.2, 3.0, .40, .35, 35, 15, .5, 1.0},
                new double[]{1.5, 18, 15, .04, .05, .10, .15, .6, .20, .15, .15, 15, 7, .25, .7}));
        // Winlend/Wonderful: spline+GCD, raycast+canSee, timer 1.7 speed
        list.add(new Archetype("winlend-wonderful", true,
                new double[]{8, 42, 52, .28, .40, .30, .38, 6, 3.2, .40, .35, 30, 16, .5, 1.1},
                new double[]{2, 12, 12, .05, .07, .10, .15, 1.5, .25, .15, .15, 10, 6, .25, .7}));
        // ZOV: bezier rotations, GCD, rayTrace gate, Grim collision speed
        list.add(new Archetype("zov-bezier", true,
                new double[]{5, 60, 55, .21, .32, .30, .38, 2, 3.0, .40, .35, 25, 14, .5, 0.9},
                new double[]{1.5, 20, 15, .04, .05, .10, .15, .5, .20, .15, .15, 10, 6, .25, .6}));
        // Arbuz 1.21.4: ONNX/neuro aim, smoothing 0.3-0.55, raycast
        list.add(new Archetype("arbuz-onnx", true,
                new double[]{8, 40, 50, .21, .32, .30, .38, 7, 3.0, .40, .35, 20, 11, .5, 1.0},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 7, 4, .25, .6}));
        // Grim-timer speedhack (timer 1.5-1.7: Monoton/Phobia/Wonderful/EvaWare/Velka/Wave)
        list.add(new Archetype("grim-timer-speed", true,
                new double[]{7, 50, 55, .38, .55, .25, .35, 2, 2.8, .45, .40, 15, 10, .5, 4.0},
                new double[]{2, 18, 15, .06, .08, .10, .12, .8, .35, .15, .15, 6, 4, .25, 2}));
        // Dragon/Castle fly (Zenith CastleFly, Wild/Health DragonFly, fly 1.5-3.0)
        list.add(new Archetype("dragon-fly", true,
                new double[]{6, 55, 55, .35, .60, .98, .50, 1, 2.5, .8, .7, 12, 10, .5, 4.0},
                new double[]{2, 20, 18, .08, .12, .02, .12, .5, .4, .3, .3, 6, 5, .25, 2}));
        // Boat/Shulker/Slime longjump (Neverlose/Winlend/Wonderful/Xrose HighJump)
        list.add(new Archetype("boat-longjump", true,
                new double[]{6, 55, 55, .45, 1.10, .50, .45, 1, 2.5, .7, .6, 15, 12, .5, 4.0},
                new double[]{2, 20, 18, .12, .25, .12, .15, .5, .4, .25, .25, 8, 6, .25, 2}));

        // ---------------- generic CHEAT archetypes ----------------
        list.add(new Archetype("aura-aggressive", true,
                new double[]{14, 9, 42, .21, .35, .32, .40, 9, 3.6, .40, .35, 45, 28, .6, 1.0},
                new double[]{2, 3, 8, .04, .06, .10, .15, 2, .30, .15, .15, 15, 10, .3, .8}));
        list.add(new Archetype("aura-smooth", true,
                new double[]{8, 28, 55, .20, .32, .30, .38, 4, 3.15, .40, .35, 22, 13, .6, 1.5},
                new double[]{1.5, 6, 15, .04, .05, .10, .15, 1, .15, .15, .15, 8, 5, .3, 1.0}));
        list.add(new Archetype("reach", true,
                new double[]{7, 55, 50, .19, .30, .28, .36, 2.5, 4.6, .40, .35, 15, 9, .6, 2.2},
                new double[]{2, 20, 15, .04, .05, .10, .15, 1, .50, .15, .15, 6, 4, .3, 1.2}));
        list.add(new Archetype("autoclicker", true,
                new double[]{21, 6, 38, .17, .28, .20, .35, 5, 2.5, .35, .30, 12, 8, .5, 2.0},
                new double[]{2, 2, 6, .04, .05, .08, .15, 1.5, .30, .15, .12, 5, 4, .2, 1.2}));
        list.add(new Archetype("fly", true,
                new double[]{6, 60, 55, .28, .50, .95, .55, 1, 2.4, 1.2, 1.0, 10, 8, .6, 4.0},
                new double[]{2, 25, 20, .08, .12, .03, .15, .5, .40, .50, .40, 5, 4, .3, 2.0}));
        list.add(new Archetype("speed-generic", true,
                new double[]{9, 40, 50, .44, .72, .40, .40, 2, 2.8, .40, .35, 18, 8, .5, 3.0},
                new double[]{2, 15, 15, .07, .15, .10, .12, 1, .40, .15, .15, 8, 4, .25, 1.5}));
        list.add(new Archetype("jesus-generic", true,
                new double[]{6, 60, 55, .23, .35, .70, .15, .8, 2.3, .50, .45, 12, 15, .6, 3.5},
                new double[]{2, 25, 20, .05, .06, .10, .08, .4, .40, .20, .20, 6, 6, .3, 1.5}));
        list.add(new Archetype("spider", true,
                new double[]{5, 60, 55, .09, .18, .85, .78, .6, 2.2, .60, .55, 5, 25, .6, 4.5},
                new double[]{2, 25, 20, .04, .06, .08, .10, .3, .40, .25, .25, 3, 10, .3, 2.0}));
        list.add(new Archetype("nuker", true,
                new double[]{10, 30, 45, .16, .28, .15, .30, 1, 3.2, .05, .30, 75, 45, .5, 3.0},
                new double[]{3, 10, 12, .05, .06, .08, .12, .5, .50, .02, .10, 20, 15, .25, 1.5}));
        list.add(new Archetype("scaffold", true,
                new double[]{12, 25, 45, .26, .36, .35, .30, .5, 2.4, .50, .06, 8, 35, .55, 4.0},
                new double[]{2, 8, 12, .05, .06, .10, .12, .3, .40, .20, .02, 4, 12, .3, 2.0}));
        list.add(new Archetype("hvh-mix", true,
                new double[]{15, 8, 40, .38, .60, .80, .60, 10, 3.8, .80, .60, 55, 35, .4, 0.7},
                new double[]{2, 3, 8, .08, .12, .10, .15, 2, .40, .40, .30, 18, 12, .2, .5}));

        // ---------------- LEGIT archetypes ----------------
        list.add(new Archetype("casual", false,
                new double[]{5, 75, 60, .14, .26, .15, .35, .7, 2.0, .45, .40, 8, 5, .5, 5.5},
                new double[]{2, 30, 30, .04, .05, .08, .15, .4, .35, .20, .20, 4, 3, .3, 2.5}));
        list.add(new Archetype("pvper", false,
                new double[]{9, 45, 50, .24, .34, .35, .42, 2.5, 2.6, .50, .40, 25, 14, .5, 3.2},
                new double[]{3, 20, 20, .04, .05, .10, .12, 1, .30, .20, .20, 10, 6, .3, 1.8}));
        list.add(new Archetype("builder", false,
                new double[]{6, 65, 55, .18, .30, .18, .35, .5, 2.2, .50, .14, 20, 30, .45, 6.0},
                new double[]{2, 25, 20, .04, .05, .08, .15, .3, .30, .20, .05, 8, 12, .25, 3.0}));
        list.add(new Archetype("miner", false,
                new double[]{6, 70, 60, .15, .27, .10, .30, .3, 2.3, .22, .50, 12, 25, .5, 6.0},
                new double[]{2, 25, 25, .04, .05, .06, .15, .2, .30, .10, .20, 6, 12, .3, 3.0}));
        list.add(new Archetype("parkour", false,
                new double[]{6, 70, 60, .26, .36, .62, .48, .3, 2.0, .80, .70, 22, 18, .45, 5.0},
                new double[]{2, 25, 25, .05, .05, .12, .12, .2, .30, .30, .30, 10, 8, .25, 2.5}));
        list.add(new Archetype("laggy", false,
                new double[]{7, 80, 60, .18, .42, .30, .40, 1.5, 2.8, .50, .50, 15, 10, 5.5, 6.5},
                new double[]{3, 35, 25, .08, .15, .15, .20, 1, .60, .30, .30, 10, 8, 2.5, 3.0}));
        list.add(new Archetype("elytra", false,
                new double[]{5, 80, 65, .90, 1.70, .10, .10, .3, 2.2, .90, .80, 14, 20, .5, 6.0},
                new double[]{2, 30, 25, .30, .50, .08, .08, .2, .40, .30, .30, 6, 10, .3, 3.0}));
        list.add(new Archetype("jitter-kid", false,
                new double[]{14, 38, 45, .17, .29, .25, .38, 1.8, 2.4, .40, .40, 30, 20, .5, 4.5},
                new double[]{3, 12, 15, .05, .06, .10, .15, 1, .40, .20, .20, 15, 10, .3, 2.5}));

        // ------- CatLean 1.21.11 (ZKM-protected jar; constants extracted from bytecode) -------
        // Aura: range 3.0 default (max 6.0), 1.21 cooldown-synced combat
        list.add(new Archetype("catlean-aura", true,
                new double[]{8, 42, 50, .21, .32, .30, .38, 6.5, 3.0, .40, .35, 28, 15, .5, 0.9},
                new double[]{2, 12, 12, .04, .05, .10, .15, 1.5, .20, .15, .15, 10, 6, .25, .6}));
        // Reach module: range up to 10, typical 4.5
        list.add(new Archetype("catlean-reach", true,
                new double[]{7, 48, 50, .21, .32, .30, .38, 4, 4.5, .40, .35, 20, 10, .5, 1.5},
                new double[]{2, 15, 12, .04, .05, .10, .15, 1, .50, .15, .15, 8, 5, .25, 1.0}));
        // Fly: constant 0.4 b/t motion - unnaturally flat speed signature
        list.add(new Archetype("catlean-fly", true,
                new double[]{6, 55, 55, .40, .45, .97, .50, 1, 2.5, .80, .70, 12, 10, .5, 4.0},
                new double[]{2, 20, 18, .02, .04, .02, .12, .5, .40, .30, .30, 6, 5, .25, 2.0}));
        // Timer 1.088x: everything ~9% faster - subtle but persistent
        list.add(new Archetype("catlean-timer", true,
                new double[]{8, 45, 50, .27, .40, .28, .36, 3, 2.8, .35, .30, 18, 9, .5, 3.0},
                new double[]{2, 15, 12, .03, .05, .10, .12, 1, .30, .12, .12, 7, 4, .25, 1.5}));
        // Scaffold with tower-jump (0.42 constants in bytecode)
        list.add(new Archetype("catlean-scaffold", true,
                new double[]{10, 30, 48, .25, .35, .40, .35, .5, 2.5, .50, .07, 10, 30, .5, 4.0},
                new double[]{2, 8, 12, .05, .06, .10, .12, .3, .40, .20, .02, 4, 12, .3, 2.0}));
        // Speed module (burst modes, 0.5/2.0 constants)
        list.add(new Archetype("catlean-speed", true,
                new double[]{9, 40, 50, .45, .75, .38, .40, 2, 2.8, .40, .35, 18, 8, .5, 3.0},
                new double[]{2, 12, 15, .07, .12, .10, .12, 1, .35, .15, .15, 8, 4, .25, 1.5}));
        // TriggerBot (KeyzDLC/Obsidian/NewCode): human clicks and movement, but
        // attacks fire only with crosshair exactly on target (hitAngle ~0)
        list.add(new Archetype("triggerbot", true,
                new double[]{6, 55, 55, .21, .32, .30, .38, 2.5, 2.9, .40, .35, 18, 10, .5, 0.4},
                new double[]{2, 18, 15, .04, .05, .10, .15, .8, .25, .15, .15, 7, 4, .25, .3}));

        // ------- System DLC / "System Ready" 1.21.11 (decompiled crack: AimAssist
        // Legit/Rage, TriggerBot 100% charge, TapeMouse 20-tick metronome, Backtrack 200ms;
        // rotations are raw doubles - no GCD snap, no randomize by default) -------
        // 17-dim archetypes (chargeAvg + airAttackRatio given explicitly)
        list.add(new Archetype("system-legitaim", true,
                new double[]{7, 50, 55, .21, .32, .32, .38, 3.5, 2.9, .40, .35, 22, 12, .5, 1.2, .95, .45},
                new double[]{2, 18, 15, .04, .05, .10, .15, 1.2, .25, .15, .15, 8, 5, .25, .8, .04, .20}));
        list.add(new Archetype("system-rageaim", true,
                new double[]{12, 15, 45, .22, .33, .30, .38, 6, 3.0, .40, .35, 75, 35, .5, 0.3, .98, .50},
                new double[]{3, 6, 12, .04, .05, .10, .15, 2, .20, .15, .15, 30, 15, .25, .25, .02, .20}));
        list.add(new Archetype("system-triggerbot", true,
                new double[]{8, 45, 50, .21, .32, .30, .38, 2.2, 2.8, .40, .35, 15, 9, .5, 0.4, .995, .40},
                new double[]{2.5, 18, 15, .04, .05, .10, .15, .8, .25, .15, .15, 7, 4, .25, .3, .005, .18}));
        list.add(new Archetype("system-tapemouse", true,
                new double[]{6, 55, 55, .20, .31, .30, .38, 1.0, 2.7, .40, .35, 14, 8, .5, 1.0, .99, .42},
                new double[]{2, 20, 15, .04, .05, .10, .15, .05, .25, .15, .15, 6, 4, .25, .7, .01, .15}));
        list.add(new Archetype("system-backtrack", true,
                new double[]{8, 48, 50, .22, .33, .32, .40, 4, 3.7, .40, .35, 25, 14, .5, 2.2, .96, .45},
                new double[]{2, 15, 15, .04, .05, .10, .15, 1.2, .50, .15, .15, 10, 6, .25, 1.3, .03, .20}));

        return extendCombat(list);
    }

    // ---------------- 1.9+ combat features (15: chargeAvg, 16: airAttackRatio) -------------

    static final java.util.Set<String> CRIT_AURAS = java.util.Set.of(
            "toshiba-silent", "minced-builder", "zenith-aura");

    static final java.util.Set<String> CD_AURAS = java.util.Set.of(
            "vesence-neuro", "vesence-shard", "stalin-neuro", "evaware-smooth", "wild-ai",
            "meow-neuro", "arbuz-onnx", "health-ml", "keyz-aimassist", "xrose-aura",
            "zov-bezier", "timoha-aura", "phobia-smooth", "winlend-wonderful",
            "exploit-aura", "cometa-aura", "aura-smooth", "velka-ml", "obsidian-punch",
            "monoton-aura", "nursultan-aura", "wild-aura", "minced-grim",
            "catlean-aura", "catlean-reach", "reach", "triggerbot");

    static final java.util.Set<String> SPAM_AURAS = java.util.Set.of(
            "vesence-spooky", "vesence-snap", "crystall-aura", "delta-aura", "essence-aura",
            "euphoria-aura", "wave-aura", "sadnes-snap", "skycore-snap", "wexside-hvh",
            "arbuz-aura", "aura-aggressive", "autoclicker", "hvh-mix", "exploit-snap",
            "essence-pattern");

    static final java.util.Map<String, double[]> LEGIT_COMBAT = java.util.Map.of(
            "pvper", new double[]{.88, .10, .50, .20},
            "jitter-kid", new double[]{.50, .20, .30, .15},
            "casual", new double[]{.65, .20, .15, .10},
            "builder", new double[]{.60, .20, .10, .10},
            "miner", new double[]{.60, .20, .10, .10},
            "parkour", new double[]{.70, .15, .35, .15},
            "laggy", new double[]{.60, .20, .30, .15},
            "elytra", new double[]{.60, .20, .20, .15});

    // server-visible silent rotation oscillators (pitch snap down/up per action)
    static final java.util.Set<String> SILENT_ROT = java.util.Set.of(
            "scaffold", "catlean-scaffold");
    static final java.util.Set<String> SILENT_SOME = java.util.Set.of(
            "vesence-spooky", "minced-builder", "health-ml", "arbuz-onnx",
            "wild-ai", "winlend-wonderful");

    /** Pads archetypes to 18 dims (15/16: combat discipline, 17: silent rotations). */
    static List<Archetype> extendCombat(List<Archetype> base) {
        List<Archetype> out = new ArrayList<>();
        for (Archetype a : base) {
            if (a.mean().length >= DIMS) {
                out.add(a);
                continue;
            }
            double[] m = java.util.Arrays.copyOf(a.mean(), DIMS);
            double[] s = java.util.Arrays.copyOf(a.sd(), DIMS);
            String n = a.name();

            // features 15/16 - honour explicit values if the archetype has them
            if (a.mean().length <= 15) {
                double[] legit = LEGIT_COMBAT.get(n);
                if (legit != null) {
                    m[15] = legit[0]; s[15] = legit[1];
                    m[16] = legit[2]; s[16] = legit[3];
                } else if (CRIT_AURAS.contains(n)) {
                    m[15] = .99; s[15] = .01;
                    m[16] = .96; s[16] = .03;
                } else if (CD_AURAS.contains(n)) {
                    m[15] = .98; s[15] = .02;
                    m[16] = .48; s[16] = .20;
                } else if (SPAM_AURAS.contains(n)) {
                    m[15] = .40; s[15] = .15;
                    m[16] = .35; s[16] = .15;
                } else if (a.cheat()) {
                    m[15] = .85; s[15] = .10;
                    m[16] = .30; s[16] = .15;
                } else {
                    m[15] = .75; s[15] = .15;
                    m[16] = .20; s[16] = .12;
                }
            }

            // feature 17 - pitch reversal rate (silent rotations)
            if (SILENT_ROT.contains(n)) {
                m[17] = 3.0; s[17] = 1.0;
            } else if (SILENT_SOME.contains(n)) {
                m[17] = 1.5; s[17] = 0.8;
            } else if (a.cheat()) {
                m[17] = 0.5; s[17] = 0.4;
            } else if (n.equals("pvper") || n.equals("jitter-kid")) {
                m[17] = 0.5; s[17] = 0.35;
            } else {
                m[17] = 0.2; s[17] = 0.15;
            }

            out.add(new Archetype(n, a.cheat(), m, s));
        }
        return out;
    }
}
