package dev.weposs.wepac.data;

import dev.weposs.wepac.ai.PlayerProfile;
import dev.weposs.wepac.check.CheckType;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Rolling telemetry + violation state for one player. All access on main thread. */
public final class PlayerData {

    public static final int CLICK_CAP = 120;
    public static final int ATTACK_CAP = 60;
    public static final int REACH_CAP = 20;
    public static final int SPEED_CAP = 60;
    public static final int AIR_CAP = 100;
    public static final int ROT_CAP = 60;
    public static final int BLOCK_CAP = 40;
    public static final int HIT_CAP = 20;

    public final UUID uuid;
    public String name;
    public long joinTime = System.currentTimeMillis();

    // ---- client detection ----
    public String clientBrand = "?";
    public String clientVerdict = "?";   // CHEAT / SUSPICIOUS / KNOWN / UNKNOWN
    public String clientName = "";       // pretty display name for known clients

    // ---- movement ----
    public boolean serverOnGround = true;
    /** per-tick server ground truth (incl. entity support), written by the 20Hz task */
    public volatile boolean groundTruth = true;
    public volatile long groundTruthAt = 0L;
    /** last time the server truth said "on ground" - bhop contact windows */
    public volatile long lastGroundedMs = 0L;
    public double airTicks = 0;
    public double hoverTicks = 0;
    public double jesusTicks = 0;
    /** wall-clock of the last MovementMonitor sample, for real-elapsed normalisation */
    public long lastMonitorTime = 0L;
    /** distance travelled in the current 5-minute window - the clean-play
     *  activity gate (AFK must not donate "legit" samples) */
    public double windowDistance = 0;
    public long distanceWindowStart = 0L;
    public long velocityExemptUntil = 0L;
    public long lastTeleport = 0L;
    public long lastRespawn = 0L;
    public Location lastLocation;
    public Location lastGroundLocation;
    public boolean prevAirborne = false;
    public double phaseTicks = 0;
    public int airJumpBuffer = 0;
    public double lastHorizontalSpeed = 0.0;
    public double lastVerticalSpeed = 0.0;
    public final ArrayDeque<Double> speedSamples = new ArrayDeque<>();
    public final ArrayDeque<Boolean> airborneSamples = new ArrayDeque<>();
    public final ArrayDeque<Boolean> risingSamples = new ArrayDeque<>();

    // ---- combat ----
    public final ArrayDeque<Long> clickTimes = new ArrayDeque<>();
    public final ArrayDeque<Long> attackTimes = new ArrayDeque<>();
    /** attacks with a meaningful cooldown charge - the honest rate signal (A7):
     *  1.9+ damage events also fire on weak hits, so raw event rate punishes
     *  legit jitter/butterfly clicking */
    public final ArrayDeque<Long> rateAttackTimes = new ArrayDeque<>();
    /** KillAura detector votes: detectorName -> lastSignalTime (A14 voting) */
    public final Map<String, Long> auraVotes = new HashMap<>();
    public final ArrayDeque<Double> reachSamples = new ArrayDeque<>();
    public final ArrayDeque<Double> yawDeltas = new ArrayDeque<>();
    public final ArrayDeque<Double> pitchDeltas = new ArrayDeque<>();
    /** aim-to-victim angle (deg) at the moment of each hit, all victims */
    public final ArrayDeque<Double> hitAngles = new ArrayDeque<>();
    /** same, but PvP hits only (perfect-hits detector) */
    public final ArrayDeque<Double> hitAnglesPvp = new ArrayDeque<>();
    /** 1.9+ attack cooldown charge (0..1) at the moment of each PvP hit */
    public final ArrayDeque<Double> attackCharges = new ArrayDeque<>();
    /** was the attacker airborne (server truth) at each PvP hit - crit rhythm */
    public final ArrayDeque<Boolean> attackAirborne = new ArrayDeque<>();

    // ---- killaura telemetry ----
    public double prevYaw = 0.0;
    public double prevPitch = 0.0;
    public long lastRotationChange = 0L;
    public long lastArmSwing = 0L;
    /** {timestampMs, yaw, pitch} samples for aim analysis */
    public final ArrayDeque<double[]> rotationSamples = new ArrayDeque<>();
    /** |pitch deltas| of recent moves, for the mouse-GCD test */
    public final ArrayDeque<Double> pitchDeltaSamples = new ArrayDeque<>();
    /** recently damaged entities: id -> {timeMs, angleAtHit} */
    public final Map<UUID, double[]> recentTargets = new HashMap<>();
    public int auraSnapBuffer = 0;
    public int auraGcdBuffer = 0;
    public int auraPacingBuffer = 0;
    public int auraSwingBuffer = 0;
    public int auraLockBuffer = 0;
    public int auraMultiBuffer = 0;
    public int auraAngleBuffer = 0;
    public int auraRateBuffer = 0;
    public int auraPerfectBuffer = 0;
    public int cooldownLockBuffer = 0;
    public int critRhythmBuffer = 0;
    public long lastAttackAt = 0L;
    /** own decay clock for crits buffers (isolated events must not "ripen" across a fight) */
    public long lastCritAttackAt = 0L;

    // ---- anti-KB ("Velocity" modules) ----
    public double pendingKbLen = 0;
    public long pendingKbTime = 0L;
    public double kbActual = 0;
    public int antiKbBuffer = 0;
    /** unit direction of the applied knockback + its origin, for projection
     *  and wall-occlusion checks (A16): total distance moved is the wrong
     *  measure - a sprint-retreat pads it, a wall legitimately eats it */
    public double pendingKbDirX = 0;
    public double pendingKbDirZ = 0;
    public double pendingKbStartX, pendingKbStartY, pendingKbStartZ;

    // ---- misc module coverage ----
    public int nowebBuffer = 0;
    public int elytraSpeedBuffer = 0;
    public int invMoveBuffer = 0;
    public int nukerBuffer = 0;
    public int fastClimbBuffer = 0;
    public int cpsBuffer = 0;
    /** last time the player clicked their OWN inventory (E) - no InventoryOpenEvent fires for it */
    public long selfInvOpenUntil = 0L;
    public int chestStealBuffer = 0;
    public final ArrayDeque<Long> invClickTimes = new ArrayDeque<>();
    // GUI state tracked via InventoryOpen/CloseEvent (avoids version-fragile InventoryView)
    public boolean guiOpen = false;
    public String guiTypeName = "";

    // ---- silent rotations (oscillating pitch/yaw) ----
    public final ArrayDeque<Long> pitchReversals = new ArrayDeque<>();
    public int lastPitchDir = 0;
    public long lastReversalTime = 0L;
    public int silentRotBuffer = 0;
    /** action-correlated reversals (A15): within ~250ms after attack/place/use */
    public final ArrayDeque<Long> actionPitchReversals = new ArrayDeque<>();
    public final ArrayDeque<Long> actionYawReversals = new ArrayDeque<>();
    public int lastYawDir = 0;
    public long lastYawReversalTime = 0L;
    public long lastActionAt = 0L;

    // ---- xray statistics (per session) ----
    public long minedStone = 0;
    public long minedValuable = 0;
    public long xrayFlagCount = 0;
    public long xrayWindowStart = 0L;

    // ---- gui attacks ----
    public int guiAttackBuffer = 0;

    // ---- packet position jumps (netty thread writes) ----
    public volatile double lastPacketX = Double.NaN;
    public volatile double lastPacketY = Double.NaN;
    public volatile double lastPacketZ = Double.NaN;
    public volatile long lastPosJump = 0L;
    public volatile double lastPosJumpDist = 0;
    public volatile long lastBadPosition = 0L;
    // ---- micro-blink resume (netty thread writes, main thread consumes) ----
    public volatile long lastBlinkResume = 0L;
    public volatile long lastBlinkResumeGap = 0L;
    public volatile double lastBlinkResumeDist = 0;
    public int blinkResumeBuffer = 0;
    public long lastBlinkResumeAt = 0L;

    // ---- per-packet physics: netty thread offers {timeMs, x, y, z, ground},
    //      the main-thread PacketPhysics task drains and simulates. Bounded so a
    //      frozen main thread can never grow the queue without limit. ----
    public final java.util.concurrent.LinkedBlockingQueue<double[]> packetMoves =
            new java.util.concurrent.LinkedBlockingQueue<>(128);
    public final dev.weposs.wepac.packet.PacketPhysics.Sim physicsSim =
            new dev.weposs.wepac.packet.PacketPhysics.Sim();
    public int packetFlyBuffer = 0;
    public long lastPacketFlyAt = 0L;

    // ---- yaw gcd ----
    public final ArrayDeque<Double> yawDeltaSamples = new ArrayDeque<>();
    public int auraGcdYawBuffer = 0;

    // ---- blocks ----
    public final ArrayDeque<Long> breakTimes = new ArrayDeque<>();
    public final ArrayDeque<Long> placeTimes = new ArrayDeque<>();
    public long lastRegenTime = 0L;
    public long consumeStart = 0L;
    public long bowDrawTime = 0L;
    public int speedBuffer = 0;
    public int scaffoldBuffer = 0;
    public int wallClimbBuffer = 0;
    public double wallClimbStartY = 0;

    // ---- nofall ----
    public boolean pendingFallDamage = false;
    public double pendingFallDistance = 0.0;
    public long pendingFallDeadline = 0L;

    // ---- physics simulation (Grim-lite) ----
    public double expectedVy = 0.0;
    public double flyDivergence = 0.0;
    public int stepBuffer = 0;
    public int longJumpBuffer = 0;
    public int waterSpeedBuffer = 0;
    public int elytraRiseSamples = 0;
    public double airStuckTicks = 0;
    /** grounded contact with slime/ice - launches a legitimate fast bounce arc */
    public long lastSlimeOrIceContact = 0L;
    public int noSlowBuffer = 0;
    public int groundSpoofStreak = 0;
    public int critsBuffer = 0;
    public long lastFireworkUse = 0L;

    // ---- packet layer (written from the netty thread: volatile / LongAdder) ----
    public volatile long lastFlyingPacket = 0L;
    // default FALSE: until the first flying packet is actually seen we must not
    // assume the client claims ground (would false-flag jumps on join / failed inject)
    public volatile boolean lastPacketGround = false;
    public volatile boolean packetSeen = false;
    public volatile long lastBadPitch = 0L;
    public final java.util.concurrent.atomic.LongAdder packetCounter =
            new java.util.concurrent.atomic.LongAdder();
    public long timerHighRateSince = 0L;

    // ---- lag-compensated reach: position history {timeMs, x, y, z}, 20Hz ----
    public final ArrayDeque<double[]> posHistory = new ArrayDeque<>();

    // ---- violations / AI ----
    public final Map<CheckType, Integer> violations = new EnumMap<>(CheckType.class);
    /** punishment stages already fired: "CHECKTYPE:at" */
    public final java.util.Set<String> firedStages = new java.util.HashSet<>();
    public long lastViolationTime = 0L;
    public double aiProbability = 0.0;
    /** EMA-smoothed probability used for adaptive thresholds (damps oscillation) */
    public double aiProbEma = 0.0;
    public long lastAiAlert = 0L;
    // dims MUST match the feature vector: an 8-dim profile silently dropped
    // features 8..17 and made /ac info throw ArrayIndexOutOfBoundsException
    public PlayerProfile profile = new PlayerProfile(
            dev.weposs.wepac.ai.FeatureVector.SIZE);
    public int cleanSamplesDonated = 0;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ---- Bedrock/Geyser (A13): touch and controller input is NOT quantised
    //      like a mouse, so GCD/pacing/rhythm detectors must not judge it ----
    private Boolean bedrock;

    public boolean bedrock() {
        if (bedrock == null) {
            bedrock = dev.weposs.wepac.util.Bedrock.isBedrock(uuid);
        }
        return bedrock;
    }

    // ---- 1.8 legacy protocol (E2): no attack cooldown exists there ----
    private Integer protocol;

    public boolean legacy18() {
        if (protocol == null) {
            protocol = dev.weposs.wepac.util.LegacyProtocol.playerVersion(uuid);
        }
        return protocol > 0 && protocol <= dev.weposs.wepac.util.LegacyProtocol.PROTOCOL_1_8;
    }

    public boolean velocityExempt() {
        return System.currentTimeMillis() < velocityExemptUntil;
    }

    public void exemptVelocity(long ms) {
        velocityExemptUntil = Math.max(velocityExemptUntil, System.currentTimeMillis() + ms);
    }

    public int vl(CheckType type) {
        return violations.getOrDefault(type, 0);
    }

    public void addVl(CheckType type, int amount) {
        violations.merge(type, amount, Integer::sum);
        lastViolationTime = System.currentTimeMillis();
    }

    public void decayVl(int amount) {
        violations.replaceAll((type, vl) -> Math.max(0, vl - amount));
        violations.values().removeIf(v -> v <= 0);
        // let punishment stages fire again if VL decayed below them
        firedStages.removeIf(key -> {
            int idx = key.indexOf(':');
            if (idx < 0) return true;
            int at = Integer.parseInt(key.substring(idx + 1));
            try {
                CheckType type = CheckType.valueOf(key.substring(0, idx));
                return vl(type) < at;
            } catch (IllegalArgumentException e) {
                return true;
            }
        });
    }

    public int totalVl() {
        int sum = 0;
        for (int v : violations.values()) sum += v;
        return sum;
    }

    // ---- buffer helpers ----

    public static void push(ArrayDeque<?> deque, Object value, int cap) {
        @SuppressWarnings("unchecked")
        ArrayDeque<Object> d = (ArrayDeque<Object>) deque;
        d.addLast(value);
        while (d.size() > cap) d.pollFirst();
    }

    public static void pushTime(ArrayDeque<Long> deque, long time, int cap) {
        deque.addLast(time);
        while (deque.size() > cap) deque.pollFirst();
    }

    public static void prune(ArrayDeque<Long> times, long olderThan) {
        while (!times.isEmpty() && times.peekFirst() < olderThan) times.pollFirst();
    }

    public static double stddev(double[] values) {
        if (values.length < 2) return 0.0;
        double mean = 0.0;
        for (double v : values) mean += v;
        mean /= values.length;
        double acc = 0.0;
        for (double v : values) acc += (v - mean) * (v - mean);
        return Math.sqrt(acc / (values.length - 1));
    }

    public static double avg(double[] values) {
        if (values.length == 0) return 0.0;
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }
}
