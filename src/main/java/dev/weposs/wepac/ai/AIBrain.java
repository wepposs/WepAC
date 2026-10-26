package dev.weposs.wepac.ai;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.data.PlayerData;
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
    /**
     * Population stats freeze. After this many observations the normaliser stops
     * updating: the statistics have converged, and freezing prevents slow drift
     * (e.g. a long cheater-heavy period permanently shifting what counts as
     * "normal" and masking the next wave). Saved/loaded with the model.
     */
    private static final long NORM_MAX_OBSERVATIONS = 250_000L;
    /** Per-feature stddev floor: near-zero population variance must not turn
     *  tiny deviations into saturated (clipped) z-scores. */
    private static final double NORM_MIN_STD = 1e-3;

    // Locking discipline (deadlock-free by construction):
    //   monitors: `this` (population stats), `buffer` (replay buffer), `netLock` (network).
    //   NO method ever holds two of them at once - each is acquired, used and
    //   released sequentially, so no lock-order inversion is possible. The old
    //   scheme nested this->network in save() while score() nested network->this,
    //   an ABBA deadlock between the main thread and the async save task.
    //   File IO in save() happens with NO locks held (everything is snapshotted
    //   into memory first), so a slow disk can never stall the main thread.
    private final WepAC plugin;
    private final Object netLock = new Object();
    private volatile NeuralNetwork network;
    private final Random random = new Random();

    // population normaliser stats
    private final double[] normMean = new double[FeatureVector.SIZE];
    private final double[] normStd = new double[FeatureVector.SIZE];
    private long normCount = 0;

    private final ArrayDeque<TrainingSample> buffer = new ArrayDeque<>();
    private volatile long samplesPositive = 0;
    private volatile long samplesNegative = 0;
    private volatile long trainRounds = 0;
    private volatile double lastAvgError = -1;
    private volatile long lastSave = 0;

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
        // reach/hit-angle/charge samples are count-capped but NOT time-capped:
        // without a recency gate a builder's last hit from an hour ago would
        // still poison their combat features
        boolean recentCombat = d.lastAttackAt != 0L && now - d.lastAttackAt < 15_000L;
        f[8] = recentCombat ? PlayerData.avg(toArray(d.reachSamples)) : 0.0;

        // blocks
        f[9] = avgIntervalMs(d.breakTimes) / 1000.0;
        f[10] = avgIntervalMs(d.placeTimes) / 1000.0;

        // rotation
        f[11] = PlayerData.avg(toArray(d.yawDeltas));
        f[12] = PlayerData.avg(toArray(d.pitchDeltas));

        // network
        f[13] = player.getPing() / 100.0;

        // aim precision at hits (aim-gated auras glue the crosshair to the victim;
        // neutral human-like default when the player has not attacked recently)
        double[] hitAngles = recentCombat ? toArray(d.hitAngles) : new double[0];
        f[14] = hitAngles.length == 0 ? 4.0 : PlayerData.avg(hitAngles);

        // 1.9+ combat discipline: cooldown charge at hits + airborne (crit) ratio.
        // E2: 1.8-protocol players have no cooldown - their charge readings would
        // look anomalous against the 1.9+ population, so stay neutral for them.
        double[] charges = recentCombat && !d.legacy18() ? toArray(d.attackCharges) : new double[0];
        f[15] = charges.length == 0 ? 0.85 : PlayerData.avg(charges);
        f[16] = recentCombat ? ratioTrue(d.attackAirborne) : 0.0;

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
        if (normCount >= NORM_MAX_OBSERVATIONS) return; // frozen: stats converged
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
            double sd = Math.max(normStd[i], NORM_MIN_STD);
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
        // C2: population stats must describe the HEALTHY population. Flagged or
        // already-suspect players would drag "normal" towards cheating (each new
        // wave looks cleaner), and AFK zero-vectors would drag it towards zero.
        boolean active = raw[0] > 0.0 || raw[7] > 0.0 || raw[3] > 0.02
                || raw[9] > 0.0 || raw[10] > 0.0;
        if (active && data.totalVl() == 0 && data.aiProbability < 0.5) {
            observe(raw);
        }
        data.profile.update(raw);
        double[] input = normalize(raw); // `this` monitor, released before netLock
        double prob;
        synchronized (netLock) {
            prob = network.predict(input);
        }
        data.aiProbability = prob;
        // EMA for threshold adaptation: a single noisy scoring pass must not
        // instantly tighten every check for the player (feedback-loop damping)
        data.aiProbEma += (prob - data.aiProbEma) * 0.35;
        return prob;
    }

    /** Threshold multiplier applied to a player's check thresholds (lower = stricter). */
    public double adaptiveMultiplier(PlayerData data) {
        if (!plugin.config().aiAdaptiveThresholds) return 1.0;
        // not enough observations yet -> stay neutral, never punish on noise
        if (data.profile.count() < 20) return 1.0;
        double lo = plugin.config().aiAdaptiveMin;
        double hi = plugin.config().aiAdaptiveMax;
        double m = hi - (hi - lo) * data.aiProbEma;
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
            // increment under the same lock so counts never drift from the buffer
            if (label > 0.5) samplesPositive++;
            else samplesNegative++;
        }
        plugin.debug("AI sample (" + source + ") label=" + label);
    }

    /**
     * Anti-poisoning: remove every LEGIT sample donated by this player.
     * Called when a player is punished - if they once passed the "legit PvP"
     * gate, those donations were lies and must not keep training the model.
     * Cheater-labelled samples of the same owner are kept: they are the
     * punishment signal itself (and are removed only by staff rehabilitation).
     */
    public int purgeSamplesFrom(java.util.UUID owner) {
        return purge(owner, false);
    }

    /**
     * Rehabilitation: remove every CHEATER sample attributed to this player.
     * Triggered by {@code /ac label <player> legit} - the undo path for a
     * false-positive kick, whose telemetry would otherwise keep training the
     * model that "this playstyle = cheater" with no way to revoke it.
     */
    public int purgeCheaterSamplesFrom(java.util.UUID owner) {
        return purge(owner, true);
    }

    private int purge(java.util.UUID owner, boolean cheaterSamples) {
        if (owner == null) return 0;
        List<TrainingSample> removed = new ArrayList<>();
        synchronized (buffer) {
            buffer.removeIf(s -> {
                if (owner.equals(s.owner) && (s.label > 0.5) == cheaterSamples) {
                    removed.add(s);
                    return true;
                }
                return false;
            });
            // counters move under the SAME lock so they can never drift from the buffer
            for (TrainingSample s : removed) {
                if (s.label > 0.5) samplesPositive--;
                else samplesNegative--;
            }
        }
        if (!removed.isEmpty()) {
            plugin.debug("Purged " + removed.size() + " AI samples ("
                    + (cheaterSamples ? "cheater" : "legit") + ") of player " + owner);
        }
        return removed.size();
    }

    /** Called when a player is confirmed cheating via punishment. Main thread. */
    public void labelFromPunishment(Player player) {
        if (!plugin.config().aiLabelFromPunishments) return;
        PlayerData data = plugin.data().get(player);
        // owner is recorded so a later staff "legit" label can REVOKE these
        // samples - an FP kick must not poison the model permanently
        addSample(buildRawFeatures(player, data), 1.0,
                "punishment:" + player.getName(), player.getUniqueId());
    }

    /** Staff label: /ac label <player> <cheater|legit>. Main thread. */
    public void labelByStaff(Player player, boolean cheater) {
        PlayerData data = plugin.data().get(player);
        if (!cheater) {
            // rehabilitation: wipe cheater samples previously attributed to this
            // player (e.g. by a false-positive kick) before adding the legit one
            purgeCheaterSamplesFrom(player.getUniqueId());
        }
        addSample(buildRawFeatures(player, data), cheater ? 1.0 : 0.0,
                "staff:" + player.getName(), player.getUniqueId());
    }

    /** Periodic clean-play donation. Main thread, called from scoring task. */
    public void checkCleanPlay(Player player, PlayerData data) {
        if (!plugin.config().aiLabelFromCleanPlay) return;
        if (data.totalVl() > 0) return;
        // a player the model already suspects cannot donate "legit" samples:
        // subtle cheats that stay under every heuristic threshold would
        // otherwise poison the buffer with self-labelled clean data
        if (data.aiProbability >= plugin.config().pvpMaxAiProbability) return;
        // C3: AFK is not honest PLAY. Near-zero vectors teach the model that
        // "absence of activity = legit" and contradict real honest telemetry;
        // require actual recent movement or interaction before donating.
        boolean active = data.windowDistance > 20.0 || !data.clickTimes.isEmpty()
                || !data.attackTimes.isEmpty() || !data.breakTimes.isEmpty();
        if (!active) return;
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
            // class-balanced sampling: legit samples outnumber cheater samples
            // by orders of magnitude on a healthy server, and a uniform draw
            // would almost never show the network a positive example
            List<TrainingSample> pos = new ArrayList<>();
            List<TrainingSample> neg = new ArrayList<>();
            for (TrainingSample s : buffer) {
                (s.label > 0.5 ? pos : neg).add(s);
            }
            if (pos.isEmpty() || neg.isEmpty()) {
                // C1: a single-class round drags EVERY probability towards that
                // class (after a raid the buffer can be cheater-heavy) - skip
                // until both classes are present
                return;
            }
            java.util.Collections.shuffle(pos, random);
            java.util.Collections.shuffle(neg, random);
            int nPos = Math.min(pos.size(), Math.max(1, n / 2));
            int nNeg = Math.min(neg.size(), n - nPos);
            if (nPos + nNeg < n) nPos = Math.min(pos.size(), n - nNeg);
            batch = new ArrayList<>(nPos + nNeg);
            batch.addAll(pos.subList(0, nPos));
            batch.addAll(neg.subList(0, nNeg));
        }
        // normalise the whole batch under the stats monitor, released before netLock
        double[][] inputs = new double[batch.size()][];
        synchronized (this) {
            for (int i = 0; i < batch.size(); i++) {
                inputs[i] = normalize(batch.get(i).features);
            }
        }
        double errSum = 0;
        synchronized (netLock) {
            network.setLearningRate(plugin.config().aiLearningRate);
            for (int i = 0; i < inputs.length; i++) {
                errSum += network.train(inputs[i], batch.get(i).label);
            }
        }
        trainRounds++;
        lastAvgError = errSum / inputs.length;
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

            // 1) snapshot everything in memory under short sequential locks
            java.io.ByteArrayOutputStream netBytes = new java.io.ByteArrayOutputStream();
            synchronized (netLock) {
                try (DataOutputStream tmp = new DataOutputStream(netBytes)) {
                    network.save(tmp);
                }
            }
            long countSnap;
            double[] meanSnap, stdSnap;
            synchronized (this) {
                countSnap = normCount;
                meanSnap = normMean.clone();
                stdSnap = normStd.clone();
            }
            List<TrainingSample> snapshot;
            synchronized (buffer) {
                snapshot = new ArrayList<>(buffer);
            }

            // 2) write a temp file with NO locks held, then atomically replace:
            //    a crash or full disk mid-write must not corrupt weeks of training
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(MAGIC2);
                out.write(netBytes.toByteArray());
                out.writeLong(countSnap);
                for (double v : meanSnap) out.writeDouble(v);
                for (double v : stdSnap) out.writeDouble(v);
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
            atomicReplace(tmp, file);
            lastSave = System.currentTimeMillis();
            plugin.debug("AI model saved (" + snapshot.size() + " samples)");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save AI model: " + e.getMessage());
        }
    }

    /**
     * Replace target with the fully-written temp file. Atomic on filesystems
     * that support it (a crash mid-write leaves the OLD file intact);
     * best-effort plain move otherwise.
     */
    public static void atomicReplace(File tmp, File target) throws IOException {
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            synchronized (netLock) {
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
        synchronized (netLock) {
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
        synchronized (netLock) {
            return network.trainSteps();
        }
    }

    public String status() {
        return String.format(Locale.ROOT,
                "samples=%d (cheaters=%d, legit=%d), rounds=%d, steps=%d, lastErr=%.4f (batch TRAIN error, not validation), savedAgo=%ds",
                snapshotSize(), samplesPositive, samplesNegative, trainRounds, trainSteps(),
                lastAvgError, lastSave == 0 ? -1 : (System.currentTimeMillis() - lastSave) / 1000);
    }
}
