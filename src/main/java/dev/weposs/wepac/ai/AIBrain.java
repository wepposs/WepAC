package dev.weposs.wepac.ai;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The self-learning core.
 *
 * Data flow:
 *  1. FeatureExtractor (buildFeatures) turns rolling player telemetry into a raw vector.
 *  2. A population-level normaliser (running mean/stddev over every observed player)
 *     standardises the vector, so the network is scale-free and adapts to the server.
 *  3. A small MLP predicts P(cheater). The probability is stored on PlayerData and is
 *     used for AI alerts and adaptive per-player check thresholds.
 *  4. Training labels arrive from: confirmed punishments (auto), long clean play (auto),
 *     and staff commands (/ac label). Samples go into a replay buffer (persisted on disk).
 *  5. An async task runs mini-batch SGD every train-interval seconds; the model and the
 *     buffer are saved periodically and on shutdown - learning survives restarts.
 */
public final class AIBrain {

    private static final int MAGIC = 0x57455041;  // "WEPA" v1 (no sample owners)
    private static final int MAGIC2 = 0x57455042; // v2: samples carry owner UUID
    private static final int BUFFER_CAP = 5000;

    private final WepAC plugin;
    private NeuralNetwork network;
    private final Random random = new Random();

    // population normaliser stats
    private final double[] normMean = new double[FeatureVector.SIZE];
    private final double[] normStd = new double[FeatureVector.SIZE];
    private long normCount = 0;

    private final ArrayDeque<TrainingSample> buffer = new ArrayDeque<>();
    private long samplesPositive = 0;
    private long samplesNegative = 0;
    private long trainRounds = 0;
    private double lastAvgError = -1;
    private long lastSave = 0;

    public AIBrain(WepAC plugin) {
        this.plugin = plugin;
        this.network = newNetwork();
    }

    private NeuralNetwork newNetwork() {
        NeuralNetwork net = new NeuralNetwork(FeatureVector.SIZE, 16, 8, 1);
        net.setLearningRate(plugin.config().aiLearningRate);
        return net;
    }

    // ------------------------------------------------------------------
    // Feature extraction
    // ------------------------------------------------------------------

    public double[] buildRawFeatures(Player player, PlayerData d) {
        double[] f = new double[FeatureVector.SIZE];
        long now = System.currentTimeMillis();
        long window = now - 3000L;

        // clicks
        List<Long> clicks = new ArrayList<>(d.clickTimes);
        clicks.removeIf(t -> t < window);
        f[0] = clicks.size() / 3.0;
        double[] intervals = intervals(clicks);
        f[1] = PlayerData.stddev(intervals);
        f[2] = intervals.length == 0 ? 0 : min(intervals);

        // movement
        double[] speeds = toArray(d.speedSamples);
        f[3] = PlayerData.avg(speeds);
        f[4] = speeds.length == 0 ? 0 : max(speeds);
        f[5] = ratioTrue(d.airborneSamples);
        f[6] = ratioTrue(d.risingSamples);

        // combat
        List<Long> attacks = new ArrayList<>(d.attackTimes);
        attacks.removeIf(t -> t < window);
        f[7] = attacks.size() / 3.0;
        f[8] = PlayerData.avg(toArray(d.reachSamples));

        // blocks
        f[9] = avgIntervalMs(d.breakTimes) / 1000.0;
        f[10] = avgIntervalMs(d.placeTimes) / 1000.0;

        // rotation
        f[11] = PlayerData.avg(toArray(d.yawDeltas));
        f[12] = PlayerData.avg(toArray(d.pitchDeltas));

        // network
        f[13] = player.getPing() / 100.0;

        // aim precision at hits (aim-gated auras glue the crosshair to the victim;
        // neutral human-like default when the player has not attacked anyone)
        double[] hitAngles = toArray(d.hitAngles);
        f[14] = hitAngles.length == 0 ? 4.0 : PlayerData.avg(hitAngles);

        // 1.9+ combat discipline: cooldown charge at hits + airborne (crit) ratio
        double[] charges = toArray(d.attackCharges);
        f[15] = charges.length == 0 ? 0.85 : PlayerData.avg(charges);
        f[16] = ratioTrue(d.attackAirborne);

        // silent rotations: large pitch reversals per second (5s window)
        long cutoff = System.currentTimeMillis() - 5000L;
        int reversals = 0;
        for (Long t : d.pitchReversals) {
            if (t >= cutoff) reversals++;
        }
        f[17] = reversals / 5.0;
        return f;
    }

    private static double[] intervals(List<Long> times) {
        if (times.size() < 2) return new double[0];
        double[] out = new double[times.size() - 1];
        for (int i = 1; i < times.size(); i++) {
            out[i - 1] = times.get(i) - times.get(i - 1);
        }
        return out;
    }

    private static double avgIntervalMs(ArrayDeque<Long> times) {
        double[] iv = intervals(new ArrayList<>(times));
        return PlayerData.avg(iv);
    }

    private static double[] toArray(ArrayDeque<Double> deque) {
        double[] out = new double[deque.size()];
        int i = 0;
        for (double v : deque) out[i++] = v;
        return out;
    }

    private static double ratioTrue(ArrayDeque<Boolean> deque) {
        if (deque.isEmpty()) return 0.0;
        int t = 0;
        for (boolean b : deque) if (b) t++;
        return (double) t / deque.size();
    }

    private static double min(double[] v) {
        double m = Double.MAX_VALUE;
        for (double x : v) m = Math.min(m, x);
        return m;
    }

    private static double max(double[] v) {
        double m = -Double.MAX_VALUE;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    // ------------------------------------------------------------------
    // Normalisation (population statistics, learned online)
    // ------------------------------------------------------------------

    private synchronized void observe(double[] raw) {
        normCount++;
        for (int i = 0; i < raw.length; i++) {
            double delta = raw[i] - normMean[i];
            normMean[i] += delta / normCount;
            double delta2 = raw[i] - normMean[i];
            // running variance via Welford; store std directly
            normStd[i] = normCount < 2 ? 1.0
                    : Math.sqrt(((normStd[i] * normStd[i]) * (normCount - 2) + delta * delta2) / (normCount - 1));
        }
    }

    public synchronized double[] normalize(double[] raw) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double sd = Math.max(normStd[i], 1e-6);
            out[i] = Math.max(-5.0, Math.min(5.0, (raw[i] - normMean[i]) / sd));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    /** Main thread. Updates profile + probability, returns P(cheater). */
    public double score(Player player, PlayerData data) {
        double[] raw = buildRawFeatures(player, data);
        observe(raw);
        data.profile.update(raw);
        double prob;
        synchronized (network) {
            prob = network.predict(normalize(raw));
        }
        data.aiProbability = prob;
        return prob;
    }

    /** Threshold multiplier applied to a player's check thresholds (lower = stricter). */
    public double adaptiveMultiplier(PlayerData data) {
        if (!plugin.config().aiAdaptiveThresholds) return 1.0;
        // not enough observations yet -> stay neutral, never punish on noise
        if (data.profile.count() < 20) return 1.0;
        double lo = plugin.config().aiAdaptiveMin;
        double hi = plugin.config().aiAdaptiveMax;
        double m = hi - (hi - lo) * data.aiProbability;
        return Math.max(lo, Math.min(hi, m));
    }

    // ------------------------------------------------------------------
    // Training data
    // ------------------------------------------------------------------

    public void addSample(double[] rawFeatures, double label, String source) {
        addSample(rawFeatures, label, source, null);
    }

    public void addSample(double[] rawFeatures, double label, String source, java.util.UUID owner) {
        synchronized (buffer) {
            buffer.addLast(new TrainingSample(rawFeatures, label, owner));
            while (buffer.size() > BUFFER_CAP) {
                TrainingSample dropped = buffer.pollFirst();
                if (dropped != null) {
                    if (dropped.label > 0.5) samplesPositive--;
                    else samplesNegative--;
                }
            }
        }
        if (label > 0.5) samplesPositive++;
        else samplesNegative++;
        plugin.debug("AI sample (" + source + ") label=" + label);
    }

    /**
     * Anti-poisoning: remove every sample donated by this player.
     * Called when a player is punished - if they once passed the "legit PvP"
     * gate, those donations were lies and must not keep training the model.
     */
    public int purgeSamplesFrom(java.util.UUID owner) {
        if (owner == null) return 0;
        List<TrainingSample> removed = new ArrayList<>();
        synchronized (buffer) {
            buffer.removeIf(s -> {
                if (owner.equals(s.owner)) {
                    removed.add(s);
                    return true;
                }
                return false;
            });
        }
        for (TrainingSample s : removed) {
            if (s.label > 0.5) samplesPositive--;
            else samplesNegative--;
        }
        if (!removed.isEmpty()) {
            plugin.debug("Purged " + removed.size() + " AI samples from punished player " + owner);
        }
        return removed.size();
    }

    /** Called when a player is confirmed cheating via punishment. Main thread. */
    public void labelFromPunishment(Player player) {
        if (!plugin.config().aiLabelFromPunishments) return;
        PlayerData data = plugin.data().get(player);
        addSample(buildRawFeatures(player, data), 1.0, "punishment:" + player.getName());
    }

    /** Staff label: /ac label <player> <cheater|legit>. Main thread. */
    public void labelByStaff(Player player, boolean cheater) {
        PlayerData data = plugin.data().get(player);
        addSample(buildRawFeatures(player, data), cheater ? 1.0 : 0.0, "staff:" + player.getName());
    }

    /** Periodic clean-play donation. Main thread, called from scoring task. */
    public void checkCleanPlay(Player player, PlayerData data) {
        if (!plugin.config().aiLabelFromCleanPlay) return;
        if (data.totalVl() > 0) return;
        if (data.cleanSamplesDonated >= plugin.config().aiMaxCleanSamples) return;
        long minutes = (System.currentTimeMillis() - data.joinTime) / 60000L;
        if (minutes < plugin.config().aiCleanPlayMinutes) return;
        data.cleanSamplesDonated++;
        addSample(buildRawFeatures(player, data), 0.0, "clean:" + player.getName());
    }

    // ------------------------------------------------------------------
    // Online training (async)
    // ------------------------------------------------------------------

    public void trainRound() {
        List<TrainingSample> batch;
        synchronized (buffer) {
            if (buffer.isEmpty()) return;
            int n = Math.min(plugin.config().aiBatchSize, buffer.size());
            batch = new ArrayList<>(n);
            List<TrainingSample> pool = new ArrayList<>(buffer);
            for (int i = 0; i < n; i++) {
                batch.add(pool.remove(random.nextInt(pool.size())));
            }
        }
        double errSum = 0;
        synchronized (network) {
            network.setLearningRate(plugin.config().aiLearningRate);
            for (TrainingSample s : batch) {
                errSum += network.train(normalize(s.features), s.label);
            }
        }
        trainRounds++;
        lastAvgError = errSum / batch.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private File modelFile() {
        return new File(plugin.getDataFolder(), plugin.config().aiModelFile);
    }

    public void save() {
        File file = modelFile();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(file)))) {
                out.writeInt(MAGIC2);
                synchronized (network) {
                    network.save(out);
                }
                synchronized (this) {
                    out.writeLong(normCount);
                    for (double v : normMean) out.writeDouble(v);
                    for (double v : normStd) out.writeDouble(v);
                }
                List<TrainingSample> snapshot;
                synchronized (buffer) {
                    snapshot = new ArrayList<>(buffer);
                }
                out.writeInt(snapshot.size());
                for (TrainingSample s : snapshot) {
                    for (int i = 0; i < FeatureVector.SIZE; i++) {
                        out.writeDouble(i < s.features.length ? s.features[i] : 0.0);
                    }
                    out.writeDouble(s.label);
                    out.writeLong(s.createdAt);
                    out.writeBoolean(s.owner != null);
                    if (s.owner != null) {
                        out.writeLong(s.owner.getMostSignificantBits());
                        out.writeLong(s.owner.getLeastSignificantBits());
                    }
                }
            }
            lastSave = System.currentTimeMillis();
            plugin.debug("AI model saved (" + snapshotSize() + " samples)");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save AI model: " + e.getMessage());
        }
    }

    public void load() {
        File file = modelFile();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != MAGIC2) {
                plugin.getLogger().warning("AI model file has wrong magic; starting fresh.");
                return;
            }
            boolean withOwners = magic == MAGIC2;
            NeuralNetwork loaded = NeuralNetwork.load(in);
            if (loaded.inputSize() != FeatureVector.SIZE) {
                plugin.getLogger().warning("AI model was trained on an older feature set; starting fresh.");
                return;
            }
            synchronized (network) {
                this.network = loaded;
            }
            synchronized (this) {
                normCount = in.readLong();
                for (int i = 0; i < FeatureVector.SIZE; i++) normMean[i] = in.readDouble();
                for (int i = 0; i < FeatureVector.SIZE; i++) normStd[i] = in.readDouble();
            }
            int count = in.readInt();
            synchronized (buffer) {
                buffer.clear();
                samplesPositive = 0;
                samplesNegative = 0;
                for (int n = 0; n < count; n++) {
                    double[] f = new double[FeatureVector.SIZE];
                    for (int i = 0; i < FeatureVector.SIZE; i++) f[i] = in.readDouble();
                    double label = in.readDouble();
                    long createdAt = in.readLong();
                    java.util.UUID owner = null;
                    if (withOwners && in.readBoolean()) {
                        owner = new java.util.UUID(in.readLong(), in.readLong());
                    }
                    buffer.addLast(new TrainingSample(f, label, owner, createdAt));
                    if (label > 0.5) samplesPositive++;
                    else samplesNegative++;
                }
            }
            plugin.getLogger().info("AI model loaded: " + count + " training samples, "
                    + loaded.trainSteps() + " train steps.");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load AI model, starting fresh: " + e.getMessage());
        }
    }

    public void resetModel() {
        synchronized (network) {
            network = newNetwork();
        }
        synchronized (this) {
            normCount = 0;
            java.util.Arrays.fill(normMean, 0);
            java.util.Arrays.fill(normStd, 0);
        }
        synchronized (buffer) {
            buffer.clear();
        }
        samplesPositive = 0;
        samplesNegative = 0;
        trainRounds = 0;
        lastAvgError = -1;
        File f = modelFile();
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Could not delete model file " + f);
        }
    }

    public int snapshotSize() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    public long trainSteps() {
        synchronized (network) {
            return network.trainSteps();
        }
    }

    public String status() {
        return String.format(Locale.ROOT,
                "samples=%d (cheaters=%d, legit=%d), rounds=%d, steps=%d, lastErr=%.4f, savedAgo=%ds",
                snapshotSize(), samplesPositive, samplesNegative, trainRounds, trainSteps(),
                lastAvgError, lastSave == 0 ? -1 : (System.currentTimeMillis() - lastSave) / 1000);
    }

    /** Async safety: run a task on the main thread. */
    public void sync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
