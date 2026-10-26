package dev.weposs.wepac.checks.combat;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Smart KillAura suite. Instead of one naive rule, an ensemble of independent
 * signals is evaluated, each with its own confirmation buffer, so a single
 * lag spike never flags:
 *
 *  A. Rate      - too many attacks per rolling second
 *  B. Angle     - hitting a PLAYER far outside the field of view
 *  C. Snap-aim  - whole rotation onto the victim inside one burst of packets
 *  D. Mouse GCD - real mice quantise pitch deltas to a sensitivity constant;
 *                 packet-set rotations produce arbitrary doubles (GCD ~ 0)
 *  E. Pacing    - metronome-regular attack intervals (stddev below human jitter)
 *  F. NoSwing   - melee damage without an arm swing nearby in time
 *  G. Aim-lock  - crosshair glued to the victim (tiny angle AND tiny variance)
 *  H. Multi     - 4+ distinct victims in 1.5s, each close to the look direction
 */
public final class KillAuraCheck extends Check {

    private static final int ROT_SAMPLES = 40;
    // 40 (not 25): the GCD test needs enough multiples of the sensitivity step
    // for the Euclid to converge; measured FP rate 0.17% vs 99% cheat detection
    private static final int PITCH_SAMPLES = 40;
    /** A14 signal voting window: independent detectors must agree within this. */
    private static final long VOTE_WINDOW_MS = 5000L;

    /**
     * A14 signal voting. Every detector confirmation casts a vote into a short
     * window; a FULL-severity flag requires 2+ independent detectors agreeing.
     * A lone signal - elite PvP players can episodically produce aim-lock or
     * perfect-hits patterns - only yields an informational severity-1 VL, so
     * single FP episodes no longer sum up towards the kick threshold.
     */
    private void suspect(Player player, PlayerData data, String detector,
                         String info, int severity) {
        long now = System.currentTimeMillis();
        data.auraVotes.put(detector, now);
        data.auraVotes.values().removeIf(t -> now - t > VOTE_WINDOW_MS);
        int distinct = data.auraVotes.size();
        if (distinct >= 2) {
            data.auraVotes.clear();
            flag(player, data, info + " | +" + (distinct - 1)
                    + " independent signal(s) in 5s", Math.max(3, severity));
        } else {
            flag(player, data, info + " [single signal]", 1);
        }
    }

    public KillAuraCheck(WepAC plugin) {
        super(plugin, CheckType.KILLAURA);
    }

    // ------------------------------------------------------------------
    // Rotation telemetry
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || bypass(player)) return;

        PlayerData data = plugin.data().get(player);
        Location from = event.getFrom();
        Location to = event.getTo();
        float yaw = to.getYaw();
        float pitch = to.getPitch();
        double dYaw = Math.abs(wrapDegrees(yaw - from.getYaw()));
        double dPitch = Math.abs(pitch - from.getPitch());
        long now = System.currentTimeMillis();

        if (dYaw > 0.001 || dPitch > 0.001) {
            data.lastRotationChange = now;
        }
        data.rotationSamples.addLast(new double[]{now, yaw, pitch});
        while (data.rotationSamples.size() > ROT_SAMPLES) {
            data.rotationSamples.pollFirst();
        }
        if (dPitch > 0.01 && dPitch < 20.0) {
            data.pitchDeltaSamples.addLast(dPitch);
            while (data.pitchDeltaSamples.size() > PITCH_SAMPLES) {
                data.pitchDeltaSamples.pollFirst();
            }
        }
        if (dYaw > 0.05 && dYaw < 50.0) {
            data.yawDeltaSamples.addLast(dYaw);
            while (data.yawDeltaSamples.size() > PITCH_SAMPLES) {
                data.yawDeltaSamples.pollFirst();
            }
        }
        data.prevYaw = from.getYaw();
        data.prevPitch = from.getPitch();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        plugin.data().get(event.getPlayer()).lastArmSwing = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Attack evaluation
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!enabled() || bypass(attacker)) return;

        PlayerData data = plugin.data().get(attacker);
        long now = System.currentTimeMillis();
        double charge = attacker.getAttackCooldown();
        PlayerData.pushTime(data.attackTimes, now, PlayerData.ATTACK_CAP);
        // A7: only cooldown-meaningful attacks count towards the RATE check.
        // On 1.9+ the damage event also fires for weak pre-cooldown hits, so the
        // raw event rate punishes legit jitter/butterfly clicking (15-20 CPS).
        // Sustained charge>=0.4 attacks above 14/s are physically impossible
        // (a sword's 0.625s cooldown caps the honest full-charge rate ~12.5/s).
        // E2: on the 1.8 protocol the attack cooldown does not exist (charge is
        // always 0) - count every attack there, 1.8 CPS semantics apply
        if (data.legacy18()
                || charge >= plugin.config().optDouble(CheckType.KILLAURA, "rate-min-charge", 0.4)) {
            PlayerData.pushTime(data.rateAttackTimes, now, PlayerData.ATTACK_CAP);
        }
        decayBuffers(data, now);

        checkRate(attacker, data, now);

        Entity victim = event.getEntity();
        Location eye = attacker.getEyeLocation();
        Vector targetPoint = victim.getLocation().toVector().add(new Vector(0, victim.getHeight() * 0.55, 0));
        Vector toVictim = targetPoint.clone().subtract(eye.toVector());
        double distance = toVictim.length();
        double angle = toVictim.lengthSquared() > 1e-4
                ? Math.toDegrees(angleBetween(eye.getDirection().normalize(), toVictim.normalize()))
                : 0.0;

        checkAngle(attacker, data, victim, angle);
        checkSnapAim(attacker, data, victim, angle, now);
        checkMouseGcd(attacker, data);
        checkMouseGcdYaw(attacker, data);
        checkGuiAttack(attacker, data, victim);
        checkPacing(attacker, data, now);
        checkNoSwing(attacker, data, now);
        checkAimLock(attacker, data, victim, angle, now);
        checkMultiTarget(attacker, data, victim, angle, distance, now);

        PlayerData.push(data.hitAngles, angle, PlayerData.HIT_CAP);
        if (victim instanceof Player) {
            // A8: charge/cooldown discipline is analysed on PvP hits ONLY.
            // Mob farming with full-charge waits is the legitimate optimal DPS
            // pattern and has the exact "cooldown-locked" signature - flagging
            // it kicked grinders. A PvE-only aura harms nobody; if it also
            // targets players, the PvP-side detectors see it.
            PlayerData.push(data.attackCharges, charge, PlayerData.HIT_CAP);
            checkCooldownLock(attacker, data, now);
            PlayerData.push(data.hitAnglesPvp, angle, PlayerData.HIT_CAP);
            checkPerfectHits(attacker, data);
        }
    }

    /**
     * J. CooldownLock (1.9+): a cooldown-synced aura fires the instant the weapon
     * reaches full charge - so across many hits the charge value is pinned at
     * ~1.0 with near-zero variance AND the intervals are metronomic. A human
     * fighting for their life swings early, late, and misses the rhythm.
     */
    private void checkCooldownLock(Player player, PlayerData data, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "cooldown-lock", true)) return;
        if (data.bedrock()) return;  // A13: touch/controller timing is not mouse timing
        if (data.legacy18()) return; // E2: the 1.8 protocol has no attack cooldown
        int minAttacks = plugin.config().optInt(CheckType.KILLAURA, "lock-min-attacks", 15);
        double minCharge = plugin.config().optDouble(CheckType.KILLAURA, "lock-min-charge", 0.98);
        double maxChargeStd = plugin.config().optDouble(CheckType.KILLAURA, "lock-max-charge-stddev", 0.012);
        double maxIntervalStd = plugin.config().optDouble(CheckType.KILLAURA, "lock-max-interval-stddev", 80.0);
        if (data.attackCharges.size() < minAttacks) return;

        List<Double> charges = new ArrayList<>(data.attackCharges);
        List<Double> win = charges.subList(charges.size() - minAttacks, charges.size());
        double[] arr = new double[minAttacks];
        for (int i = 0; i < minAttacks; i++) arr[i] = win.get(i);
        double mean = PlayerData.avg(arr);
        double chargeStd = PlayerData.stddev(arr);

        List<Long> attacks = new ArrayList<>(data.attackTimes);
        if (attacks.size() < minAttacks) return;
        List<Long> recent = attacks.subList(attacks.size() - minAttacks, attacks.size());
        double[] intervals = new double[recent.size() - 1];
        for (int i = 1; i < recent.size(); i++) {
            intervals[i - 1] = recent.get(i) - recent.get(i - 1);
        }
        double intervalStd = PlayerData.stddev(intervals);

        if (mean >= minCharge && chargeStd <= maxChargeStd && intervalStd <= maxIntervalStd) {
            data.cooldownLockBuffer++;
            if (data.cooldownLockBuffer >= 2) {
                suspect(player, data, "cooldown", String.format(Locale.ROOT,
                        "cooldown-locked: %d attacks at charge %.3f (stddev %.4f), intervals stddev %.0fms",
                        minAttacks, mean, chargeStd, intervalStd), 4);
                data.cooldownLockBuffer = 0;
                data.attackCharges.clear();
            }
        } else {
            data.cooldownLockBuffer = Math.max(0, data.cooldownLockBuffer - 1);
        }
    }

    /**
     * I. Perfect hits - the signature of aim-gated auras (Shard-like "attack only
     * when the crosshair settled on the target" gates, raycast-gated attacks).
     * Humans time their clicks, so their hit angles spread widely even at high
     * skill; an aura's hits cluster near zero almost every time.
     * PvP-only: mob farming while staring at a slow target is legitimate.
     */
    private void checkPerfectHits(Player player, PlayerData data) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "perfect-hits", true)) return;
        int window = plugin.config().optInt(CheckType.KILLAURA, "perfect-window", 12);
        double maxAngle = plugin.config().optDouble(CheckType.KILLAURA, "perfect-angle", 2.5);
        double minRatio = plugin.config().optDouble(CheckType.KILLAURA, "perfect-ratio", 0.8);
        if (data.hitAnglesPvp.size() < window) return;

        List<Double> all = new ArrayList<>(data.hitAnglesPvp);
        List<Double> win = all.subList(all.size() - window, all.size());
        int perfect = 0;
        for (double a : win) {
            if (a < maxAngle) perfect++;
        }
        double ratio = (double) perfect / window;
        if (ratio >= minRatio) {
            data.auraPerfectBuffer++;
            if (data.auraPerfectBuffer >= 2) {
                suspect(player, data, "perfect", String.format(Locale.ROOT,
                        "robotic hit precision: %d/%d PvP hits within %.1f deg of crosshair",
                        perfect, window, maxAngle), 4);
                data.auraPerfectBuffer = 0;
                data.hitAnglesPvp.clear();
            }
        } else {
            data.auraPerfectBuffer = Math.max(0, data.auraPerfectBuffer - 1);
        }
    }

    /**
     * Time-based buffer decay: isolated suspicious events that are far apart must
     * NOT slowly accumulate into a flag over minutes. If the previous attack was
     * more than {gap}ms ago, this is a separate burst - reset the per-detector
     * confirmation counters so only sustained patterns confirm.
     */
    private void decayBuffers(PlayerData data, long now) {
        long gap = plugin.config().optLongOr(CheckType.KILLAURA, "buffer-reset-gap-ms", 4000L);
        if (data.lastAttackAt != 0L && now - data.lastAttackAt > gap) {
            data.auraSnapBuffer = 0;
            data.auraGcdBuffer = 0;
            data.auraGcdYawBuffer = 0;
            data.auraPacingBuffer = 0;
            data.auraSwingBuffer = 0;
            data.auraLockBuffer = 0;
            data.auraMultiBuffer = 0;
            data.auraPerfectBuffer = 0;
            data.auraAngleBuffer = 0;
            data.auraRateBuffer = 0;
            data.cooldownLockBuffer = 0;
        }
        data.lastAttackAt = now;
    }

    // A. attack rate -----------------------------------------------------

    private void checkRate(Player player, PlayerData data, long now) {
        PlayerData.prune(data.rateAttackTimes, now - 1000L);
        int aps = data.rateAttackTimes.size();
        int maxAps = plugin.config().optInt(CheckType.KILLAURA, "max-attacks-per-second", 14);
        if (aps > maxAps) {
            data.auraRateBuffer++;
            if (data.auraRateBuffer >= 2) {
                suspect(player, data, "rate",
                        aps + " charged attacks/sec > " + maxAps, 3);
                data.auraRateBuffer = 0;
            }
        } else {
            data.auraRateBuffer = 0;
        }
    }

    // B. field-of-view angle ----------------------------------------------

    private void checkAngle(Player player, PlayerData data, Entity victim, double angle) {
        // players only: sweeping edge legitimately hits nearby mobs off-view
        if (!(victim instanceof Player)) return;
        double maxAngle = plugin.config().optDouble(CheckType.KILLAURA, "max-rotation-angle", 75.0);
        if (angle > maxAngle) {
            data.auraAngleBuffer++;
            if (data.auraAngleBuffer >= 2) {
                suspect(player, data, "angle", String.format(Locale.ROOT,
                        "hit %.0f deg off look direction", angle), 2);
                data.auraAngleBuffer = 0;
            }
        } else {
            data.auraAngleBuffer = 0;
        }
    }

    // C. snap-aim ----------------------------------------------------------

    private void checkSnapAim(Player player, PlayerData data, Entity victim, double newAngle, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "snap-aim", true)) return;
        double maxNew = plugin.config().optDouble(CheckType.KILLAURA, "snap-new-angle", 10.0);
        double minOld = plugin.config().optDouble(CheckType.KILLAURA, "snap-old-angle", 32.0);
        long snapWindowMs = plugin.config().optLongOr(CheckType.KILLAURA, "snap-window-ms", 200L);

        if (newAngle > maxNew) return; // aim not on target - nothing to snap about

        // previous rotation -> angle to the same victim
        Location eye = player.getEyeLocation().clone();
        eye.setYaw((float) data.prevYaw);
        eye.setPitch((float) data.prevPitch);
        Vector toVictim = victim.getLocation().toVector()
                .add(new Vector(0, victim.getHeight() * 0.55, 0))
                .subtract(eye.toVector());
        if (toVictim.lengthSquared() < 1e-4) return;
        double oldAngle = Math.toDegrees(angleBetween(eye.getDirection().normalize(), toVictim.normalize()));

        long sinceRotation = now - data.lastRotationChange;
        if (oldAngle >= minOld && sinceRotation <= snapWindowMs) {
            data.auraSnapBuffer++;
            if (data.auraSnapBuffer >= 2) {
                suspect(player, data, "snap", String.format(Locale.ROOT,
                        "snap-aim: %.0f deg -> %.0f deg in %dms", oldAngle, newAngle, sinceRotation), 3);
                data.auraSnapBuffer = 0;
            }
        } else {
            data.auraSnapBuffer = Math.max(0, data.auraSnapBuffer - 1);
        }
    }

    // D. mouse GCD ----------------------------------------------------------

    private void checkMouseGcd(Player player, PlayerData data) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "gcd-rotations", true)) return;
        if (data.bedrock()) return; // A13: touch/controller input has no mouse quantisation
        if (data.pitchDeltaSamples.size() < 12) return;

        double gcd = 0;
        for (double d : data.pitchDeltaSamples) {
            gcd = gcd == 0 ? d : gcd(gcd, d);
            if (gcd < 1e-6) break;
        }
        double minGcd = plugin.config().optDouble(CheckType.KILLAURA, "gcd-min", 0.005);
        if (gcd < minGcd) {
            data.auraGcdBuffer++;
            if (data.auraGcdBuffer >= 4) {
                suspect(player, data, "gcd-pitch", String.format(Locale.ROOT,
                        "rotation gcd %.6f (real mice quantise pitch steps)", gcd), 4);
                data.auraGcdBuffer = 0;
                data.pitchDeltaSamples.clear();
            }
        } else {
            data.auraGcdBuffer = Math.max(0, data.auraGcdBuffer - 1);
        }
    }

    // D2. mouse GCD on yaw (auras that only rotate horizontally evade the pitch GCD)
    private void checkMouseGcdYaw(Player player, PlayerData data) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "gcd-yaw", true)) return;
        if (data.bedrock()) return; // A13: touch/controller input has no mouse quantisation
        if (data.yawDeltaSamples.size() < 12) return;

        double gcd = 0;
        for (double d : data.yawDeltaSamples) {
            gcd = gcd == 0 ? d : gcd(gcd, d);
            if (gcd < 1e-6) break;
        }
        double minGcd = plugin.config().optDouble(CheckType.KILLAURA, "gcd-min", 0.005);
        if (gcd < minGcd) {
            data.auraGcdYawBuffer++;
            if (data.auraGcdYawBuffer >= 4) {
                suspect(player, data, "gcd-yaw", String.format(Locale.ROOT,
                        "yaw rotation gcd %.6f (real mice quantise yaw steps too)", gcd), 4);
                data.auraGcdYawBuffer = 0;
                data.yawDeltaSamples.clear();
            }
        } else {
            data.auraGcdYawBuffer = Math.max(0, data.auraGcdYawBuffer - 1);
        }
    }

    /**
     * K. GUI attack: in vanilla the mouse is captured by the open inventory -
     * attacking with a GUI open is impossible without mods. Catches auras used
     * while looting/shopping. Buffer guards against exotic plugin GUIs.
     */
    /** Vanilla screen types that capture the mouse - a full GUI-attack signal
     *  requires one of these AND a player victim. */
    private static final java.util.Set<String> VANILLA_CONTAINERS = java.util.Set.of(
            "CHEST", "ENDER_CHEST", "BARREL", "HOPPER", "DISPENSER", "DROPPER",
            "SHULKER_BOX", "CRAFTING", "WORKBENCH", "ANVIL", "FURNACE",
            "BLAST_FURNACE", "SMOKER", "ENCHANTING", "ENCHANTING_TABLE",
            "BREWING", "BEACON", "LOOM", "CARTOGRAPHY_TABLE", "CARTOGRAPHY",
            "GRINDSTONE", "STONECUTTER", "SMITHING", "SMITHING_TABLE");

    private void checkGuiAttack(Player player, PlayerData data, Entity victim) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "gui-attacks", true)) return;
        // event-tracked GUI state (InventoryView is version-fragile on Paper 1.21+)
        if (!data.guiOpen) {
            data.guiAttackBuffer = 0;
            return;
        }
        data.guiAttackBuffer++;
        if (data.guiAttackBuffer >= 2) {
            data.guiAttackBuffer = 0;
            String where = data.guiTypeName.isEmpty() ? "a GUI" : data.guiTypeName;
            // Plugin menus (shops, quests, "hit the mob with a menu open" event
            // minigames) do not always capture the mouse, and a PvE victim makes
            // the signal ambiguous - those cases must not cast a vote in the
            // ensemble, only inform the staff
            boolean full = victim instanceof Player
                    && VANILLA_CONTAINERS.contains(where.toUpperCase(Locale.ROOT));
            if (full) {
                suspect(player, data, "gui", "attacked a PLAYER with " + where
                        + " open (impossible in vanilla)", 4);
            } else {
                flag(player, data, "attacked with " + where
                        + " open [plugin GUI or PvE victim - informational]", 1);
            }
        }
    }

    // E. attack pacing -------------------------------------------------------

    private void checkPacing(Player player, PlayerData data, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "attack-pacing", true)) return;
        if (data.bedrock()) return; // A13: controller auto-attack can be metronomic
        List<Long> attacks = new ArrayList<>(data.attackTimes);
        attacks.removeIf(t -> t < now - 3000L);
        if (attacks.size() < 8) return;

        double[] intervals = new double[attacks.size() - 1];
        for (int i = 1; i < attacks.size(); i++) {
            intervals[i - 1] = attacks.get(i) - attacks.get(i - 1);
        }
        double std = PlayerData.stddev(intervals);
        double avg = PlayerData.avg(intervals);
        double minStd = plugin.config().optDouble(CheckType.KILLAURA, "pacing-min-stddev", 25.0);
        // metronome attacks at combat-relevant speed
        if (std < minStd && avg > 40 && avg < 800) {
            data.auraPacingBuffer++;
            if (data.auraPacingBuffer >= 3) {
                suspect(player, data, "pacing", String.format(Locale.ROOT,
                        "metronome attacks: interval stddev %.1fms over %d hits", std, attacks.size()), 4);
                data.auraPacingBuffer = 0;
                data.attackTimes.clear();
            }
        } else {
            data.auraPacingBuffer = Math.max(0, data.auraPacingBuffer - 1);
        }
    }

    // F. no-swing -------------------------------------------------------------

    private void checkNoSwing(Player player, PlayerData data, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "no-swing", true)) return;
        long window = plugin.config().optLongOr(CheckType.KILLAURA, "swing-window-ms", 400L);
        if (data.lastArmSwing != 0L && Math.abs(now - data.lastArmSwing) <= window) {
            data.auraSwingBuffer = 0;
            return;
        }
        data.auraSwingBuffer++;
        if (data.auraSwingBuffer >= 2) {
            suspect(player, data, "noswing", "melee damage without arm swing", 3);
            data.auraSwingBuffer = 0;
        }
    }

    // G. aim-lock ---------------------------------------------------------------

    private void checkAimLock(Player player, PlayerData data, Entity victim, double currentAngle, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "aim-lock", true)) return;
        if (currentAngle > 5.0) {
            data.auraLockBuffer = 0;
            return;
        }
        Vector targetPoint = victim.getLocation().toVector().add(new Vector(0, victim.getHeight() * 0.55, 0));
        List<Double> angles = new ArrayList<>();
        for (double[] s : data.rotationSamples) {
            if (now - s[0] > 300L) continue;
            Vector dir = directionFrom((float) s[1], (float) s[2]);
            Vector to = targetPoint.clone().subtract(player.getEyeLocation().toVector());
            if (to.lengthSquared() < 1e-4) continue;
            angles.add(Math.toDegrees(angleBetween(dir, to.normalize())));
        }
        if (angles.size() < 4) return;

        double[] arr = new double[angles.size()];
        for (int i = 0; i < angles.size(); i++) arr[i] = angles.get(i);
        double std = PlayerData.stddev(arr);
        double maxSeen = 0;
        for (double a : arr) maxSeen = Math.max(maxSeen, a);

        double lockStd = plugin.config().optDouble(CheckType.KILLAURA, "lock-max-stddev", 0.5);
        // crosshair glued to the victim: small angle, near-zero variance
        if (std < lockStd && maxSeen < 6.0) {
            data.auraLockBuffer++;
            if (data.auraLockBuffer >= 3) {
                suspect(player, data, "aimlock", String.format(Locale.ROOT,
                        "pixel-perfect aim lock: angle stddev %.2f deg over %d samples", std, arr.length), 3);
                data.auraLockBuffer = 0;
            }
        } else {
            data.auraLockBuffer = Math.max(0, data.auraLockBuffer - 1);
        }
    }

    // H. multi-target ---------------------------------------------------------------

    private void checkMultiTarget(Player player, PlayerData data, Entity victim,
                                  double angle, double distance, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "multi-target", true)) return;
        // A12: PLAYER victims only. Sweeping Edge legitimately hits 4+ mobs per
        // swing on farms - the malicious case (multi-target aura) is anti-player
        if (!(victim instanceof Player)) return;
        long window = plugin.config().optLongOr(CheckType.KILLAURA, "multi-window-ms", 1500L);
        int maxTargets = plugin.config().optInt(CheckType.KILLAURA, "multi-max-targets", 4);
        double maxAngle = plugin.config().optDouble(CheckType.KILLAURA, "multi-max-angle", 40.0);

        UUID id = victim.getUniqueId();
        data.recentTargets.put(id, new double[]{now, angle});
        data.recentTargets.values().removeIf(v -> now - v[0] > window);

        // only count victims that were close to the look direction:
        // sweeping edge hits mobs off-view and must not count
        int onView = 0;
        for (double[] v : data.recentTargets.values()) {
            if (v[1] <= maxAngle) onView++;
        }
        if (onView >= maxTargets) {
            data.auraMultiBuffer++;
            if (data.auraMultiBuffer >= 2) {
                suspect(player, data, "multi", String.format(Locale.ROOT,
                        "%d distinct PLAYER victims in %.1fs (reach up to %.1f)",
                        onView, window / 1000.0, distance), 3);
                data.auraMultiBuffer = 0;
                data.recentTargets.clear();
            }
        } else {
            data.auraMultiBuffer = Math.max(0, data.auraMultiBuffer - 1);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Float-safe GCD. Client rotations are FLOATS accumulated over time: honest
     * deltas are multiples of the sensitivity constant but carry ~1e-5..1e-4 of
     * rounding noise, and modulo steps AMPLIFY that noise (q*eps). A naive
     * Euclid with an absolute stop (b > 1e-7) therefore converges to the noise
     * floor for real mice too - empirically it flagged ~200/200 simulated honest
     * sessions, indistinguishable from raw-double rotations.
     *
     * Stopping when the remainder drops below {@link #GCD_REL_TOL} of the current
     * value makes the tolerance scale with the sensitivity constant itself:
     * honest samples converge to the true step (>= gcd-min), raw doubles keep
     * cascading to sub-mouse garbage (< gcd-min). The 7%/40-sample combination
     * was tuned empirically (see SelfTest gcd cases): 2/1200 honest FP,
     * 297/300 raw-double detections.
     */
    static final double GCD_REL_TOL = 0.07;

    public static double gcd(double a, double b) {
        a = Math.abs(a);
        b = Math.abs(b);
        if (a < b) {
            double t = a; a = b; b = t;
        }
        while (b > a * GCD_REL_TOL && b > 1e-9) {
            double t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static Vector directionFrom(float yaw, float pitch) {
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(yaw);
        double xz = Math.cos(pitchRad);
        return new Vector(-xz * Math.sin(yawRad), -Math.sin(pitchRad), xz * Math.cos(yawRad)).normalize();
    }

    private static double angleBetween(Vector a, Vector b) {
        double dot = Math.max(-1.0, Math.min(1.0, a.dot(b)));
        return Math.acos(dot);
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}
