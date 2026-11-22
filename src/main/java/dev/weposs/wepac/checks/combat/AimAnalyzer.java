package dev.weposs.wepac.checks.combat;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.packet.TransactionManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Aim analysis on top of {@link KillAuraCheck} (flags as KILLAURA):
 *
 *  K. Hitbox     - DETERMINISTIC: the attack ray (rotation before AND after
 *                  the attack, plus the frames in between) misses the victim's
 *                  hitbox at EVERY lag-compensated position the attacker could
 *                  have seen. Rotation-less / silent / "legit-rotation" auras
 *                  that hit before the crosshair is on the body fail this.
 *  L. Triggerbot - reaction time from "target entered the crosshair" to the
 *                  attack, measured in the attacker's own (latency-shifted)
 *                  frame. Humans: 150-300ms; triggerbots: 0-2 ticks, always.
 *  M. AimAssist  - smooth aim / aim assist pulls the crosshair by a constant
 *                  FRACTION of the remaining error every tick (delta = k*err):
 *                  the ratio is near-constant across many ticks. Human
 *                  tracking is ballistic with overshoot - the ratio scatters.
 *
 * L and M are statistical and only VOTE (KillAura A14 voting): a lone signal
 * is informational, two independent detectors within 5s give a full flag.
 */
public final class AimAnalyzer implements Listener {

    private final WepAC plugin;

    public AimAnalyzer(WepAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private KillAuraCheck aura() {
        var c = plugin.check(CheckType.KILLAURA);
        return c instanceof KillAuraCheck k && k.enabled() ? k : null;
    }

    private static boolean skip(Player p) {
        return p.hasPermission("wepac.bypass") || p.isDead() || p.isInsideVehicle()
                || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE;
    }

    // ------------------------------------------------------------------
    // K. Hitbox (deferred to the rotation packet AFTER the attack)
    // ------------------------------------------------------------------

    /**
     * After a confirmed through-walls flag: for 3s, attacks whose straight
     * eye-to-victim line is blocked by a solid block are cancelled outright.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void cancelWallHits(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        PlayerData data = plugin.data().getIfLoaded(attacker.getUniqueId());
        if (data == null || System.currentTimeMillis() > data.wallsCancelUntil) return;
        Location eye = attacker.getEyeLocation();
        Entity v = event.getEntity();
        Vector to = v.getBoundingBox().getCenter().subtract(eye.toVector());
        double d = to.length();
        if (d < 0.5) return;
        var block = attacker.getWorld().rayTraceBlocks(eye, to.normalize(), d,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (block != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        PlayerData d = plugin.data().getIfLoaded(event.getPlayer().getUniqueId());
        if (d != null) d.lastBlockBreakAt = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (aura() == null || skip(attacker)) return;
        if (event.getCause() != EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK) return;
        if (dev.weposs.wepac.util.Compat.holdingSpear(attacker)) return; // piercing/kinetic
        if (dev.weposs.wepac.util.Bedrock.isBedrock(attacker.getUniqueId())) return;
        PlayerData data = plugin.data().get(attacker);
        long now = System.currentTimeMillis();
        Location eye = attacker.getEyeLocation();
        data.pendingHit = new double[]{now, eye.getX(), eye.getY(), eye.getZ(),
                eye.getYaw(), eye.getPitch()};
        data.pendingHitVictim = victim.getUniqueId();
        // triggerbot sample: was the target entered just now?
        triggerSample(attacker, data, victim, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        PlayerData data = plugin.data().getIfLoaded(p.getUniqueId());
        if (data == null || data.pendingHit == null) return;
        Location to = event.getTo();
        evaluateHitbox(p, data, to.getYaw(), to.getPitch());
    }

    private void evaluateHitbox(Player attacker, PlayerData data, float newYaw, float newPitch) {
        double[] h = data.pendingHit;
        data.pendingHit = null;
        KillAuraCheck aura = aura();
        if (h == null || aura == null) return;
        Player victim = Bukkit.getPlayer(data.pendingHitVictim);
        if (victim == null || !victim.getWorld().equals(attacker.getWorld())) return;
        PlayerData vd = plugin.data().getIfLoaded(victim.getUniqueId());
        if (vd == null) return;
        long now = System.currentTimeMillis();
        if (now - data.lastTeleport < 1000L || now - vd.lastTeleport < 1000L) return;

        Vector eye = new Vector(h[1], h[2], h[3]);
        // also the eye one sneak-pose away (pose changes lag a tick)
        Vector[] eyes = {eye, eye.clone().add(new Vector(0, -0.35, 0)), eye.clone().add(new Vector(0, 0.35, 0))};
        double w = victim.getWidth() / 2.0, ht = victim.getHeight();
        double expand = plugin.config().optDouble(CheckType.KILLAURA, "hitbox-expand", 0.1);
        double range = 6.0;

        int latency = TransactionManager.latency(attacker, data);
        long windowStart = (long) h[0] - Math.min(1000L, latency + 250L);
        var tx = data.transactions;
        if (tx.active && tx.lastAckedSendMs != 0L) {
            windowStart = Math.min(windowStart, tx.lastAckedSendMs - 200L);
        }

        // candidate boxes: every history sample in the window + midpoints + now
        java.util.List<BoundingBox> boxes = new java.util.ArrayList<>();
        boxes.add(victim.getBoundingBox().clone().expand(expand));
        double[] prev = null;
        for (double[] pos : vd.posHistory) {
            if (pos[0] < windowStart) {
                prev = pos;
                continue;
            }
            boxes.add(box(pos[1], pos[2], pos[3], w, ht, expand));
            if (prev != null) {
                boxes.add(box((pos[1] + prev[1]) / 2, (pos[2] + prev[2]) / 2, (pos[3] + prev[3]) / 2,
                        w, ht, expand));
            }
            prev = pos;
        }

        float oy = (float) h[4], op = (float) h[5];
        float dyaw = wrap(newYaw - oy), dp = newPitch - op;
        org.bukkit.World world = attacker.getWorld();
        boolean walled = false;
        Vector wallAt = null;
        for (int i = 0; i <= 6; i++) {
            double f = i / 6.0;
            Vector dir = direction(oy + (float) (dyaw * f), op + (float) (dp * f));
            for (Vector e : eyes) {
                for (BoundingBox b : boxes) {
                    if (b.contains(e)) {
                        data.hitboxBuffer = Math.max(0, data.hitboxBuffer - 1);
                        data.wallHitBuffer = Math.max(0, data.wallHitBuffer - 1);
                        return;
                    }
                    var hit = b.rayTrace(e, dir, range);
                    if (hit == null) continue;
                    // N. Through-walls: vanilla picking stops at the first block
                    // outline, so the entity hit must come BEFORE any block hit
                    double entDist = hit.getHitPosition().distance(e);
                    var block = world.rayTraceBlocks(e.toLocation(world), dir, entDist,
                            org.bukkit.FluidCollisionMode.NEVER, true);
                    if (block == null || block.getHitPosition().distance(e) >= entDist - 0.05) {
                        data.hitboxBuffer = Math.max(0, data.hitboxBuffer - 1);
                        data.wallHitBuffer = Math.max(0, data.wallHitBuffer - 1);
                        return;
                    }
                    walled = true;
                    wallAt = block.getHitBlock() != null
                            ? block.getHitBlock().getLocation().toVector() : block.getHitPosition();
                }
            }
        }
        if (walled) {
            // ghost-block guard: a block the attacker just placed/broke may not
            // exist (yet / anymore) on his client
            Long lastPlace = data.placeTimes.peekLast();
            if (lastPlace != null && now - lastPlace < 1000L) return;
            if (now - data.lastBlockBreakAt < 1000L) return;
            data.wallHitBuffer += 3;
            if (data.wallHitBuffer >= plugin.config().optInt(CheckType.KILLAURA, "walls-buffer", 6)) {
                data.wallHitBuffer = 0;
                aura.flag(attacker, data, String.format(Locale.ROOT,
                        "HitThroughWalls: hit %s through block at %d %d %d",
                        victim.getName(), wallAt.getBlockX(), wallAt.getBlockY(), wallAt.getBlockZ()), 4);
                if (plugin.config().optBool(CheckType.KILLAURA, "walls-cancel", true)) {
                    data.wallsCancelUntil = now + 3000L;
                }
            }
            return;
        }
        // missed every candidate - buffered: 3 misses in the last ~10 hits
        data.hitboxBuffer += 3;
        if (data.hitboxBuffer >= plugin.config().optInt(CheckType.KILLAURA, "hitbox-buffer", 8)) {
            data.hitboxBuffer = 0;
            aura.flag(attacker, data, String.format(Locale.ROOT,
                    "Hitbox: attack ray missed %s at all %d lag-compensated positions (rot %.1f/%.1f -> %.1f/%.1f)",
                    victim.getName(), boxes.size(), oy, op, newYaw, newPitch), 4);
        }
    }

    private static BoundingBox box(double x, double y, double z, double hw, double h, double ex) {
        return new BoundingBox(x - hw - ex, y - ex, z - hw - ex, x + hw + ex, y + h + ex, z + hw + ex);
    }

    // ------------------------------------------------------------------
    // L/M: per-tick crosshair tracking in the attacker's frame
    // ------------------------------------------------------------------

    private void tick() {
        if (aura() == null) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                PlayerData data = plugin.data().getIfLoaded(p.getUniqueId());
                if (data == null || skip(p)) continue;
                if (data.pendingHit != null && now - (long) data.pendingHit[0] > 150L) {
                    // no rotation packet followed: judge with the pre-attack rotation only
                    Location l = p.getLocation();
                    evaluateHitbox(p, data, l.getYaw(), l.getPitch());
                }
                track(p, data, now);
            } catch (Throwable t) {
                plugin.debug("AimAnalyzer error for " + p.getName() + ": " + t);
            }
        }
    }

    /** Victim position the attacker's client was displaying at server time {@code now}. */
    private Vector seenCenter(Player victim, PlayerData vd, long targetTime) {
        double[] best = null;
        for (double[] pos : vd.posHistory) {
            if (best == null || Math.abs(pos[0] - targetTime) < Math.abs(best[0] - targetTime)) best = pos;
        }
        if (best == null) {
            Location l = victim.getLocation();
            return new Vector(l.getX(), l.getY(), l.getZ());
        }
        return new Vector(best[1], best[2], best[3]);
    }

    private void track(Player p, PlayerData data, long now) {
        Location eyeLoc = p.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        Vector dir = eyeLoc.getDirection();
        int latency = TransactionManager.latency(p, data);
        long seenAt = now - latency - 100L; // one RTT + entity interpolation

        Player best = null;
        double bestErr = Double.MAX_VALUE;
        boolean onTarget = false;
        Vector bestCenter = null;
        for (Entity e : p.getNearbyEntities(6, 4, 6)) {
            if (!(e instanceof Player v) || v.getGameMode() == GameMode.SPECTATOR) continue;
            PlayerData vd = plugin.data().getIfLoaded(v.getUniqueId());
            if (vd == null) continue;
            Vector c = seenCenter(v, vd, seenAt);
            double hw = v.getWidth() / 2.0, h = v.getHeight();
            BoundingBox b = box(c.getX(), c.getY(), c.getZ(), hw, h, 0.0);
            if (b.rayTrace(eye, dir, 6.0) != null) onTarget = true;
            Vector to = c.clone().add(new Vector(0, h * 0.6, 0)).subtract(eye);
            double err = Math.toDegrees(dir.angle(to));
            if (err < bestErr) {
                bestErr = err;
                best = v;
                bestCenter = c;
            }
        }

        // L. crosshair state transitions (for the triggerbot reaction sample)
        if (onTarget && !data.crossOn) {
            data.crossOn = true;
            data.crossOnSince = now;
            data.crossOnCharge = p.getAttackCooldown();
            data.crossWasOffFor = now - data.crossOffSince;
        } else if (!onTarget && data.crossOn) {
            data.crossOn = false;
            data.crossOffSince = now;
        }

        // M. aim-assist ratio on yaw (signed error toward the target)
        float yaw = eyeLoc.getYaw();
        float dYaw = Float.isNaN(data.aimPrevYaw) ? 0f : wrap(yaw - data.aimPrevYaw);
        data.aimPrevYaw = yaw;
        if (best == null || bestErr > 45.0) {
            data.aimRatios.clear();
            data.aimToward = 0;
            data.aimMoves = 0;
            return;
        }
        Vector to = bestCenter.clone().add(new Vector(0, best.getHeight() * 0.6, 0)).subtract(eye);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        // error BEFORE this tick's turn: assist moves by k * previous error
        float yawErr = wrap(targetYaw - (yaw - dYaw));
        if (Math.abs(dYaw) > 0.1f && Math.abs(yawErr) > 2.0f) {
            data.aimMoves++;
            if (Math.signum(dYaw) == Math.signum(yawErr)) {
                data.aimToward++;
                data.aimRatios.addLast((double) Math.abs(dYaw) / Math.abs(yawErr));
                while (data.aimRatios.size() > 40) data.aimRatios.pollFirst();
            }
        }
        if (data.aimRatios.size() >= 40 && data.aimMoves >= 40) {
            double[] r = data.aimRatios.stream().mapToDouble(Double::doubleValue).toArray();
            double mean = PlayerData.avg(r), sd = PlayerData.stddev(r);
            double cv = mean > 1e-6 ? sd / mean : 1.0;
            double toward = data.aimToward / (double) data.aimMoves;
            double maxCv = plugin.config().optDouble(CheckType.KILLAURA, "aim-assist-max-cv", 0.18);
            if (cv < maxCv && mean > 0.04 && mean < 0.85 && toward > 0.85) {
                KillAuraCheck aura = aura();
                if (aura != null && plugin.config().optBool(CheckType.KILLAURA, "aim-assist", true)) {
                    aura.suspect(p, data, "aimassist", String.format(Locale.ROOT,
                            "AimAssist: crosshair pulled by constant %.0f%% of error (cv %.2f, %.0f%% toward) over %d ticks",
                            mean * 100, cv, toward * 100, r.length), 3);
                }
            }
            data.aimRatios.clear();
            data.aimToward = 0;
            data.aimMoves = 0;
        }
    }

    private void triggerSample(Player attacker, PlayerData data, Player victim, long now) {
        if (!plugin.config().optBool(CheckType.KILLAURA, "triggerbot", true)) return;
        if (!data.crossOn) return;
        long reaction = now - data.crossOnSince;
        // only "fresh entries" while the weapon was already charged and the
        // crosshair had been OFF the target for a while: the player was waiting
        if (reaction > 600L || data.crossOnCharge < 0.9 || data.crossWasOffFor < 200L) return;
        data.crossOn = false; // one sample per entry
        boolean instant = reaction <= plugin.config().optLongOr(CheckType.KILLAURA, "triggerbot-max-ms", 75L);
        data.triggerSamples.addLast(instant ? 1 : 0);
        while (data.triggerSamples.size() > 12) data.triggerSamples.pollFirst();
        if (data.triggerSamples.size() < 12) return;
        int inst = 0;
        for (int s : data.triggerSamples) inst += s;
        double ratio = plugin.config().optDouble(CheckType.KILLAURA, "triggerbot-ratio", 0.75);
        if (inst >= 12 * ratio) {
            KillAuraCheck aura = aura();
            if (aura != null) {
                aura.suspect(attacker, data, "triggerbot", String.format(Locale.ROOT,
                        "Triggerbot: %d/12 crosshair entries answered within 1-2 ticks", inst), 3);
            }
            data.triggerSamples.clear();
        }
    }

    // ------------------------------------------------------------------

    private static Vector direction(float yaw, float pitch) {
        double pr = Math.toRadians(pitch), yr = Math.toRadians(yaw);
        double xz = Math.cos(pr);
        return new Vector(-xz * Math.sin(yr), -Math.sin(pr), xz * Math.cos(yr));
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
