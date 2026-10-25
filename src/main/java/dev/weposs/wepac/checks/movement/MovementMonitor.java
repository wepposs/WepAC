package dev.weposs.wepac.checks.movement;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import dev.weposs.wepac.util.Compat;
import dev.weposs.wepac.util.GroundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * Runs 4 times per second over every online player and produces:
 *  - server-authoritative movement telemetry for the AI feature vector,
 *  - hover/fly detection (server ground truth, immune to ground spoofing),
 *  - speed detection, water/lava-walk (Jesus) detection, fast climb,
 *  - wall climb (Spider) detection, air jump (double jump) detection,
 *  - phase/no-clip detection (player inside solid cubes),
 *  - server-side fall damage expectation (NoFall).
 *
 * False-positive guards baked in here:
 *  - /fly (allowFlight) players are exempt from all flight-family checks,
 *  - lag-guard doubles confirmation buffers for high-ping players,
 *  - knockback/teleport/respawn grace periods,
 *  - rollback returns confirmed cheaters to their last legal position.
 */
public final class MovementMonitor implements Listener {

    private static final int INTERVAL_TICKS = 5;

    private final WepAC plugin;

    public MovementMonitor(WepAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                monitor(player);
            } catch (Throwable t) {
                plugin.debug("MovementMonitor error for " + player.getName() + ": " + t);
            }
        }
    }

    private void monitor(Player player) {
        if (player.hasPermission("wepac.bypass")) return;
        if (player.isInsideVehicle() || player.isDead()) return;

        PlayerData data = plugin.data().get(player);
        Location now = player.getLocation();
        Location last = data.lastLocation;
        data.lastLocation = now.clone();

        boolean grounded = GroundUtil.isOnGround(player);
        data.serverOnGround = grounded;
        if (grounded) {
            data.lastGroundLocation = now.clone();
        }

        // Server-granted flight (creative, /fly, plot plugins) - exempt from
        // the whole flight family. Honest players with /fly are never touched.
        boolean mayFly = player.getAllowFlight() || player.isFlying();

        boolean gliding = player.isGliding() || player.isRiptiding();
        boolean climbing = GroundUtil.onClimbable(player);
        boolean liquid = GroundUtil.inLiquid(player);
        boolean levitating = Compat.hasEffect(player, Compat.LEVITATION)
                || Compat.hasEffect(player, Compat.SLOW_FALLING);
        boolean exempt = data.velocityExempt()
                || System.currentTimeMillis() - data.lastTeleport < 1500L
                || System.currentTimeMillis() - data.lastRespawn < 3000L;

        // lag guard: noisy telemetry for high-ping players needs more confirmations
        int lagMul = plugin.config().lagGuard && player.getPing() > plugin.config().lagPingThreshold
                ? 2 : 1;

        boolean airborne = !grounded && !gliding && !climbing && !liquid && !levitating;

        double dx = 0, dy = 0, dz = 0;
        boolean fresh = last == null || !last.getWorld().equals(now.getWorld())
                || last.distanceSquared(now) > 400;
        if (!fresh) {
            dx = now.getX() - last.getX();
            dy = now.getY() - last.getY();
            dz = now.getZ() - last.getZ();
        }
        double hSpeed = Math.sqrt(dx * dx + dz * dz) / INTERVAL_TICKS; // blocks/tick
        double vSpeed = dy / INTERVAL_TICKS;                           // blocks/tick (+ = rising)
        double prevVSpeed = data.lastVerticalSpeed;

        if (!fresh) {
            double yawDelta = Math.abs(wrapDegrees(now.getYaw() - last.getYaw()));
            double pitchDelta = Math.abs(now.getPitch() - last.getPitch());
            PlayerData.push(data.yawDeltas, yawDelta, PlayerData.ROT_CAP);
            PlayerData.push(data.pitchDeltas, pitchDelta, PlayerData.ROT_CAP);

            if (hSpeed > 0.01) {
                PlayerData.push(data.speedSamples, hSpeed, PlayerData.SPEED_CAP);
            }
            data.lastHorizontalSpeed = hSpeed;
            data.lastVerticalSpeed = vSpeed;
        }

        if (airborne && !fresh) {
            PlayerData.push(data.airborneSamples, Boolean.TRUE, PlayerData.AIR_CAP);
            PlayerData.push(data.risingSamples, vSpeed > 0.0, PlayerData.AIR_CAP);
            data.airTicks += INTERVAL_TICKS;
        } else {
            PlayerData.push(data.airborneSamples, Boolean.FALSE, PlayerData.AIR_CAP);
            data.airTicks = 0;
        }

        if (mayFly || gliding || liquid || climbing || levitating || exempt || grounded) {
            data.hoverTicks = 0;
        } else if (!fresh) {
            boolean nearSlime = nearSlimeBelow(player);
            if (vSpeed > -0.04 && !nearSlime) {
                data.hoverTicks += INTERVAL_TICKS;
            } else {
                data.hoverTicks = Math.max(0, data.hoverTicks - INTERVAL_TICKS);
            }
        }

        checkFly(player, data, mayFly, lagMul);
        checkPhysicsDivergence(player, data, dy, vSpeed, airborne, grounded, climbing,
                liquid, gliding, levitating, mayFly, exempt, fresh, lagMul);
        checkSpeed(player, data, hSpeed, grounded, mayFly, exempt, fresh, lagMul);
        checkJesus(player, data, grounded, mayFly, exempt, fresh, vSpeed, lagMul);
        checkFastClimb(player, data, vSpeed, climbing, exempt, fresh);
        checkWallClimb(player, data, vSpeed, grounded, climbing, liquid, gliding,
                levitating, mayFly, exempt, fresh, lagMul);
        checkAirJump(player, data, vSpeed, prevVSpeed, airborne, grounded, climbing,
                liquid, gliding, levitating, mayFly, exempt, fresh, lagMul);
        checkPhase(player, data, mayFly, exempt, fresh, gliding);
        checkStep(player, data, vSpeed, grounded, mayFly, exempt, fresh, lagMul);
        checkLongJump(player, data, hSpeed, airborne, gliding, liquid, mayFly, exempt, fresh, lagMul);
        checkWaterWalk(player, data, hSpeed, liquid, grounded, mayFly, exempt, fresh, lagMul);
        checkElytraFly(player, data, vSpeed, hSpeed, gliding, mayFly, exempt, fresh, lagMul);
        checkAirStuck(player, data, hSpeed, vSpeed, airborne, mayFly, exempt, fresh, lagMul);
        checkNoSlow(player, data, hSpeed, grounded, mayFly, exempt, fresh, lagMul);
        checkNoWeb(player, data, hSpeed, mayFly, exempt, fresh, lagMul);
        checkGroundSpoof(player, data, grounded, climbing, liquid, gliding, mayFly, exempt, fresh, lagMul);
        checkVelocityAbsorption(player, data, hSpeed * INTERVAL_TICKS, liquid, exempt);
        trackFall(player, data, grounded, dy, mayFly, exempt, fresh);

        data.prevAirborne = airborne;
    }

    /**
     * Anti-KB detector ("Velocity" modules - CatLean's flagship absorbs 85-90%
     * of knockback): after a significant hit, the server knows exactly what
     * velocity it applied. We integrate the player's real horizontal movement
     * over the 700ms knockback window and compare it against the expected
     * travel distance. Honest players always travel most of it; an anti-KB
     * client barely moves.
     */
    private void checkVelocityAbsorption(Player player, PlayerData data, double hDist,
                                         boolean liquid, boolean exempt) {
        Check kb = plugin.check(CheckType.VELOCITY);
        if (kb == null || !plugin.config().isEnabled(CheckType.VELOCITY)) return;
        if (data.pendingKbTime == 0L) return;

        long now = System.currentTimeMillis();
        if (now - data.pendingKbTime <= 700L) {
            data.kbActual += hDist;
            return;
        }

        double expected = data.pendingKbLen * (data.serverOnGround ? 1.6 : 2.2);
        double actual = data.kbActual;
        data.pendingKbTime = 0L;
        data.pendingKbLen = 0;
        data.kbActual = 0;

        // shields legitimately eat knockback; water drag and grace periods too
        if (liquid || exempt || player.isBlocking() || expected < 0.15) return;

        double ratio = plugin.config().optDouble(CheckType.VELOCITY, "absorb-ratio", 0.35);
        if (actual < expected * ratio) {
            data.antiKbBuffer++;
            if (data.antiKbBuffer >= plugin.config().optInt(CheckType.VELOCITY, "buffer", 2)) {
                kb.flag(player, data, String.format(Locale.ROOT,
                        "absorbed knockback: moved %.2f of expected %.2f blocks (%.0f%%)",
                        actual, expected, 100.0 * actual / expected), 3);
                data.antiKbBuffer = 0;
            }
        } else {
            data.antiKbBuffer = Math.max(0, data.antiKbBuffer - 1);
        }
    }

    // ------------------------------------------------------------------
    // Grim-lite physics simulation
    // ------------------------------------------------------------------

    /**
     * While airborne, vanilla vertical velocity follows vy = (vy - 0.08) * 0.98
     * per tick. We integrate the expected displacement over every 5-tick sampling
     * window and accumulate the divergence between observed and simulated Y
     * movement. This catches glide/slow-fall flies and gradual climb flies that
     * individually stay under any per-sample threshold - physics cannot be lied to.
     */
    private void checkPhysicsDivergence(Player player, PlayerData data, double dy, double vSpeed,
                                        boolean airborne, boolean grounded, boolean climbing,
                                        boolean liquid, boolean gliding, boolean levitating,
                                        boolean mayFly, boolean exempt, boolean fresh, int lagMul) {
        Check fly = plugin.check(CheckType.FLY);
        if (fly == null || !plugin.config().isEnabled(CheckType.FLY) || mayFly) {
            data.flyDivergence = 0;
            data.expectedVy = 0;
            return;
        }
        if (exempt || fresh || grounded || gliding || liquid || climbing || levitating
                || !airborne || nearSlimeBelow(player) || GroundUtil.softLanding(player)) {
            data.flyDivergence = 0;
            data.expectedVy = 0;
            return;
        }

        if (!data.prevAirborne) {
            // just left the ground: seed the simulation from the launch type
            double seed = 0.0;
            if (vSpeed > 0.15) {
                seed = 0.42; // jump
                int jumpLevel = Compat.effectAmplifier(player, Compat.JUMP);
                if (jumpLevel > 0) {
                    seed += 0.1 * jumpLevel;
                }
            }
            data.expectedVy = seed;
        }

        double expectedDy = 0;
        double vy = data.expectedVy;
        for (int i = 0; i < INTERVAL_TICKS; i++) {
            vy = (vy - 0.08) * 0.98;
            expectedDy += vy;
        }
        data.expectedVy = vy;
        data.flyDivergence += dy - expectedDy;

        if (data.flyDivergence < -2.0) {
            data.flyDivergence = 0; // knocked down / desynced downward - resync silently
            return;
        }
        double limit = plugin.config().optDouble(CheckType.FLY, "physics-divergence", 0.75) * lagMul;
        if (data.flyDivergence > limit) {
            fly.flag(player, data, String.format(Locale.ROOT,
                    "physics divergence +%.2f blocks above gravity simulation", data.flyDivergence), 3);
            data.flyDivergence = 0;
            rollback(player, data);
        }
    }

    /** Step hacks: rising faster than the 0.6 block auto-step while grounded. */
    private void checkStep(Player player, PlayerData data, double vSpeed, boolean grounded,
                           boolean mayFly, boolean exempt, boolean fresh, int lagMul) {
        Check step = plugin.check(CheckType.STEP);
        if (step == null || !plugin.config().isEnabled(CheckType.STEP)) return;
        if (!grounded || mayFly || exempt || fresh) {
            data.stepBuffer = Math.max(0, data.stepBuffer - 1);
            return;
        }
        double maxRise = plugin.config().optDouble(CheckType.STEP, "max-rise", 0.7);
        if (vSpeed > maxRise) {
            data.stepBuffer++;
            if (data.stepBuffer >= plugin.config().optInt(CheckType.STEP, "buffer", 3) * lagMul) {
                step.flag(player, data, String.format(Locale.ROOT,
                        "rose %.2f b/t while grounded (auto-step max 0.6)", vSpeed), 2);
                data.stepBuffer = 0;
            }
        } else {
            data.stepBuffer = Math.max(0, data.stepBuffer - 1);
        }
    }

    /** LongJump/hop hacks: horizontal bursts mid-air beyond sprint-jump physics. */
    private void checkLongJump(Player player, PlayerData data, double hSpeed, boolean airborne,
                               boolean gliding, boolean liquid, boolean mayFly,
                               boolean exempt, boolean fresh, int lagMul) {
        Check lj = plugin.check(CheckType.LONGJUMP);
        if (lj == null || !plugin.config().isEnabled(CheckType.LONGJUMP)) return;
        if (!airborne || gliding || liquid || mayFly || exempt || fresh
                || nearSlimeBelow(player) || GroundUtil.softLanding(player)) {
            data.longJumpBuffer = Math.max(0, data.longJumpBuffer - 1);
            return;
        }
        double maxAir = plugin.config().optDouble(CheckType.LONGJUMP, "max-air-speed", 0.55);
        int speedLvl = Compat.effectAmplifier(player, Compat.SPEED);
        if (speedLvl > 0) {
            maxAir += 0.06 * speedLvl;
        }
        if (hSpeed > maxAir) {
            data.longJumpBuffer++;
            if (data.longJumpBuffer >= plugin.config().optInt(CheckType.LONGJUMP, "buffer", 2) * lagMul) {
                lj.flag(player, data, String.format(Locale.ROOT,
                        "air speed %.2f b/t > %.2f (sprint-jump physics ~0.35)", hSpeed, maxAir), 2);
                data.longJumpBuffer = 0;
            }
        } else {
            data.longJumpBuffer = Math.max(0, data.longJumpBuffer - 1);
        }
    }

    /** WaterSpeed hacks: swimming faster than vanilla water physics allows. */
    private void checkWaterWalk(Player player, PlayerData data, double hSpeed, boolean liquid,
                                boolean grounded, boolean mayFly, boolean exempt,
                                boolean fresh, int lagMul) {
        Check ww = plugin.check(CheckType.WATERWALK);
        if (ww == null || !plugin.config().isEnabled(CheckType.WATERWALK)) return;
        if (!liquid || grounded || mayFly || exempt || fresh
                || Compat.hasEffect(player, Compat.DOLPHINS)) {
            data.waterSpeedBuffer = Math.max(0, data.waterSpeedBuffer - 1);
            return;
        }
        int depthStrider = Compat.enchantLevel(player.getInventory().getBoots(), Compat.DEPTH_STRIDER);
        double limit = plugin.config().optDouble(CheckType.WATERWALK, "max-speed", 0.32)
                + 0.07 * depthStrider;
        if (hSpeed > limit) {
            data.waterSpeedBuffer++;
            if (data.waterSpeedBuffer >= plugin.config().optInt(CheckType.WATERWALK, "buffer", 6) * lagMul) {
                ww.flag(player, data, String.format(Locale.ROOT,
                        "water speed %.2f b/t > %.2f (Depth Strider %d)", hSpeed, limit, depthStrider), 2);
                data.waterSpeedBuffer = 0;
            }
        } else {
            data.waterSpeedBuffer = Math.max(0, data.waterSpeedBuffer - 1);
        }
    }

    /** ElytraFly: climbing or over-speeding while gliding without fireworks/riptide/velocity. */
    private void checkElytraFly(Player player, PlayerData data, double vSpeed, double hSpeed,
                                boolean gliding, boolean mayFly, boolean exempt,
                                boolean fresh, int lagMul) {
        Check ef = plugin.check(CheckType.ELYTRAFLY);
        if (ef == null || !plugin.config().isEnabled(CheckType.ELYTRAFLY)) return;
        boolean boosted = System.currentTimeMillis() - data.lastFireworkUse < 3500L
                || data.velocityExempt() || player.isRiptiding();
        if (!gliding || mayFly || exempt || fresh || boosted) {
            data.elytraRiseSamples = 0;
            data.elytraSpeedBuffer = Math.max(0, data.elytraSpeedBuffer - 1);
            return;
        }
        double maxRise = plugin.config().optDouble(CheckType.ELYTRAFLY, "max-rise", 0.08);
        if (vSpeed > maxRise) {
            data.elytraRiseSamples++;
            if (data.elytraRiseSamples >= plugin.config().optInt(CheckType.ELYTRAFLY, "samples", 5) * lagMul) {
                ef.flag(player, data, String.format(Locale.ROOT,
                        "elytra climbing %.2f b/t for %d samples without boost",
                        vSpeed, data.elytraRiseSamples), 3);
                data.elytraRiseSamples = 0;
            }
        } else {
            data.elytraRiseSamples = Math.max(0, data.elytraRiseSamples - 1);
        }

        // terminal velocity of an unboosted elytra dive is ~2.5-2.7 b/t;
        // sustained flight beyond that without rockets is ElytraMotion/ElytraSpeed
        double maxSpeed = plugin.config().optDouble(CheckType.ELYTRAFLY, "max-speed", 3.2);
        if (hSpeed > maxSpeed) {
            data.elytraSpeedBuffer++;
            if (data.elytraSpeedBuffer >= 4 * lagMul) {
                ef.flag(player, data, String.format(Locale.ROOT,
                        "elytra speed %.2f b/t sustained without rockets (terminal ~2.7)", hSpeed), 3);
                data.elytraSpeedBuffer = 0;
            }
        } else {
            data.elytraSpeedBuffer = Math.max(0, data.elytraSpeedBuffer - 1);
        }
    }

    /**
     * NoWeb / NoBerry (CatLean WebBlockMixin & SweetBerryBushBlockMixin):
     * cobweb and berry bushes slow vanilla movement to a crawl (~0.05 b/t).
     * Moving through them at walking speed means the client cancelled the
     * collision slowdown.
     */
    private void checkNoWeb(Player player, PlayerData data, double hSpeed,
                            boolean mayFly, boolean exempt, boolean fresh, int lagMul) {
        Check nw = plugin.check(CheckType.NOWEB);
        if (nw == null || !plugin.config().isEnabled(CheckType.NOWEB)) {
            data.nowebBuffer = 0;
            return;
        }
        if (mayFly || exempt || fresh) {
            data.nowebBuffer = Math.max(0, data.nowebBuffer - 1);
            return;
        }
        Material at = player.getLocation().getBlock().getType();
        boolean inWeb = at == Material.COBWEB;
        boolean inBerry = at == Material.SWEET_BERRY_BUSH;
        if (!inWeb && !inBerry) {
            data.nowebBuffer = Math.max(0, data.nowebBuffer - 1);
            return;
        }
        double cap = inWeb
                ? plugin.config().optDouble(CheckType.NOWEB, "web-max-speed", 0.20)
                : plugin.config().optDouble(CheckType.NOWEB, "berry-max-speed", 0.25);
        if (hSpeed > cap) {
            data.nowebBuffer++;
            if (data.nowebBuffer >= plugin.config().optInt(CheckType.NOWEB, "buffer", 4) * lagMul) {
                nw.flag(player, data, String.format(Locale.ROOT,
                        "%.2f b/t inside %s (vanilla crawls at ~0.05)", hSpeed, at.name()), 2);
                data.nowebBuffer = 0;
            }
        } else {
            data.nowebBuffer = Math.max(0, data.nowebBuffer - 1);
        }
    }

    /** AirStuck: frozen mid-air (the "AirStuck" module found in most RU clients). */
    private void checkAirStuck(Player player, PlayerData data, double hSpeed, double vSpeed,
                               boolean airborne, boolean mayFly, boolean exempt,
                               boolean fresh, int lagMul) {
        Check stuck = plugin.check(CheckType.AIRSTUCK);
        if (stuck == null || !plugin.config().isEnabled(CheckType.AIRSTUCK)) return;
        if (!airborne || mayFly || exempt || fresh) {
            data.airStuckTicks = 0;
            return;
        }
        if (hSpeed < 0.005 && Math.abs(vSpeed) < 0.02) {
            data.airStuckTicks += INTERVAL_TICKS;
            int maxTicks = plugin.config().optInt(CheckType.AIRSTUCK, "max-ticks", 60) * lagMul;
            if (data.airStuckTicks > maxTicks) {
                stuck.flag(player, data, "frozen mid-air " + data.airStuckTicks + " ticks", 3);
                data.airStuckTicks = 0;
                rollback(player, data);
            }
        } else {
            data.airStuckTicks = 0;
        }
    }

    /** NoSlow: moving at full speed while using an item (vanilla slows to ~20%). */
    private void checkNoSlow(Player player, PlayerData data, double hSpeed, boolean grounded,
                             boolean mayFly, boolean exempt, boolean fresh, int lagMul) {
        Check ns = plugin.check(CheckType.NOSLOW);
        if (ns == null || !plugin.config().isEnabled(CheckType.NOSLOW)) return;
        boolean using = player.isBlocking() || isHandRaised(player);
        if (!using || !grounded || mayFly || exempt || fresh) {
            data.noSlowBuffer = Math.max(0, data.noSlowBuffer - 1);
            return;
        }
        double limit = plugin.config().optDouble(CheckType.NOSLOW, "max-speed", 0.12);
        if (hSpeed > limit) {
            data.noSlowBuffer++;
            if (data.noSlowBuffer >= plugin.config().optInt(CheckType.NOSLOW, "buffer", 6) * lagMul) {
                ns.flag(player, data, String.format(Locale.ROOT,
                        "%.2f b/t while using item (vanilla slows to ~0.05)", hSpeed), 2);
                data.noSlowBuffer = 0;
            }
        } else {
            data.noSlowBuffer = Math.max(0, data.noSlowBuffer - 1);
        }
    }

    private static boolean isHandRaised(Player player) {
        try {
            return player.isHandRaised();
        } catch (Throwable t) {
            return false; // older API: fall back to isBlocking only
        }
    }

    /**
     * Packet ground spoof: the client claims onGround=true in flying packets while
     * server-side physics says airborne. Root signal of packet Criticals,
     * NoFall bypasses and part of Fly. Requires the netty sniffer.
     */
    private void checkGroundSpoof(Player player, PlayerData data, boolean grounded,
                                  boolean climbing, boolean liquid, boolean gliding,
                                  boolean mayFly, boolean exempt, boolean fresh, int lagMul) {
        Check spoof = plugin.check(CheckType.GROUND_SPOOF);
        if (spoof == null || !plugin.config().isEnabled(CheckType.GROUND_SPOOF)) return;
        if (!grounded && !climbing && !liquid && !gliding && !mayFly && !exempt && !fresh
                && data.packetSeen && data.lastPacketGround && data.airTicks >= 5) {
            data.groundSpoofStreak++;
            if (data.groundSpoofStreak >= 3 * lagMul) {
                spoof.flag(player, data, String.format(Locale.ROOT,
                        "packet onGround=true while airborne %d ticks", data.airTicks), 2);
                data.groundSpoofStreak = 0;
            }
        } else {
            data.groundSpoofStreak = 0;
        }
    }

    // ------------------------------------------------------------------

    private void checkFly(Player player, PlayerData data, boolean mayFly, int lagMul) {
        Check fly = plugin.check(CheckType.FLY);
        if (fly == null || !plugin.config().isEnabled(CheckType.FLY)) return;
        if (mayFly) {
            data.hoverTicks = 0;
            return;
        }
        int maxHover = plugin.config().optInt(CheckType.FLY, "max-hover-ticks", 8) * lagMul;
        if (data.hoverTicks > maxHover * 2) { // hoverTicks already counts game ticks
            fly.flag(player, data, String.format(Locale.ROOT,
                    "hover %d ticks (y=%.1f)", data.hoverTicks, player.getLocation().getY()), 2);
            data.hoverTicks = 0;
            rollback(player, data);
        }
    }

    private void checkSpeed(Player player, PlayerData data, double hSpeed,
                            boolean grounded, boolean mayFly, boolean exempt,
                            boolean fresh, int lagMul) {
        Check speed = plugin.check(CheckType.SPEED);
        if (speed == null || !plugin.config().isEnabled(CheckType.SPEED)) return;
        if (mayFly || !grounded || exempt || fresh) {
            data.speedBuffer = Math.max(0, data.speedBuffer - 1);
            return;
        }
        if (GroundUtil.onSlimeOrIce(player)) return;
        // Soul Speed boots on soul sand/soil are legitimately much faster
        ItemStack bootsForSoul = player.getInventory().getBoots();
        if (Compat.hasEnchant(bootsForSoul, Compat.SOUL_SPEED)) {
            Material belowMat = player.getLocation().getBlock().getRelative(0, -1, 0).getType();
            if (belowMat == Material.SOUL_SAND || belowMat == Material.SOUL_SOIL) return;
        }

        double multiplier = plugin.config().optDouble(CheckType.SPEED, "multiplier", 1.45);
        double limit = 0.29 * multiplier * (player.getWalkSpeed() / 0.2f);
        if (player.isSprinting()) limit *= 1.05;
        // vanilla sneak speed is ~0.13 b/tick; the old "*2.0" made the sneak cap
        // ~0.40 b/t, letting a sneak-speedhack pass. Scale by walk speed like above.
        if (player.isSneaking()) limit = 0.13 * multiplier * (player.getWalkSpeed() / 0.2f);
        int speedLevel = Compat.effectAmplifier(player, Compat.SPEED);
        if (speedLevel > 0) {
            limit *= 1.0 + 0.22 * speedLevel;
        }

        int bufferNeeded = plugin.config().optInt(CheckType.SPEED, "buffer", 8) * lagMul;
        if (hSpeed > limit) {
            data.speedBuffer++;
            if (data.speedBuffer >= bufferNeeded) {
                speed.flag(player, data, String.format(Locale.ROOT,
                        "%.2f b/t > %.2f (buf %d)", hSpeed, limit, data.speedBuffer));
                data.speedBuffer = 0;
            }
        } else {
            data.speedBuffer = Math.max(0, data.speedBuffer - 2);
        }
    }

    private void checkJesus(Player player, PlayerData data, boolean grounded, boolean mayFly,
                            boolean exempt, boolean fresh, double vSpeed, int lagMul) {
        Check jesus = plugin.check(CheckType.JESUS);
        if (jesus == null || !plugin.config().isEnabled(CheckType.JESUS)) return;
        if (Compat.hasEffect(player, Compat.DOLPHINS) || mayFly) {
            data.jesusTicks = 0;
            return;
        }
        // Depth Strider boosts UNDERWATER speed (that belongs to the WaterWalk check,
        // which already scales its limit by the enchant level). It does NOT let a player
        // stand ON the surface, so it must not fully exempt Jesus - otherwise DS boots
        // become a free water-walk. Instead require a much longer sustained surface-stand
        // before flagging, which protects swimmers who momentarily sit at the surface.
        boolean depthStrider = Compat.hasEnchant(player.getInventory().getBoots(), Compat.DEPTH_STRIDER);
        int maxTicks = plugin.config().optInt(CheckType.JESUS, "max-ticks", 12) * lagMul
                * (depthStrider ? 3 : 1);
        if (!fresh && !exempt && !grounded && GroundUtil.onLiquidSurface(player)
                && vSpeed > -0.05) {
            data.jesusTicks += INTERVAL_TICKS;
            if (data.jesusTicks > maxTicks) {
                jesus.flag(player, data, "walking on liquid " + data.jesusTicks + " ticks");
                data.jesusTicks = 0;
            }
        } else {
            data.jesusTicks = 0;
        }
    }

    private void checkFastClimb(Player player, PlayerData data, double vSpeed,
                                boolean climbing, boolean exempt, boolean fresh) {
        Check climb = plugin.check(CheckType.FASTCLIMB);
        if (climb == null || !plugin.config().isEnabled(CheckType.FASTCLIMB)) return;
        if (!climbing || exempt || fresh) return;
        double mult = plugin.config().optDouble(CheckType.FASTCLIMB, "multiplier", 1.6);
        double limit = 0.117 * mult;
        if (vSpeed > limit) {
            climb.flag(player, data, String.format(Locale.ROOT,
                    "climb %.2f b/t > %.2f", vSpeed, limit), 2);
        }
    }

    /**
     * Spider / WallClimb: the player moves UP along a solid wall without any
     * climbable blocks. Vanilla physics allows at most a jump's worth of rise
     * (~1.25 blocks) while pressed against a wall; anything above that is a cheat.
     * The held item is irrelevant to detection - only positions and velocities
     * are used - but it is included in the alert for staff context.
     */
    private void checkWallClimb(Player player, PlayerData data, double vSpeed,
                                boolean grounded, boolean climbing, boolean liquid,
                                boolean gliding, boolean levitating, boolean mayFly,
                                boolean exempt, boolean fresh, int lagMul) {
        Check wallClimb = plugin.check(CheckType.WALLCLIMB);
        if (wallClimb == null || !plugin.config().isEnabled(CheckType.WALLCLIMB)) return;

        if (grounded || climbing || liquid || gliding || levitating || mayFly || exempt || fresh) {
            data.wallClimbBuffer = 0;
            return;
        }
        double minRise = plugin.config().optDouble(CheckType.WALLCLIMB, "min-rise", 0.03);
        if (vSpeed <= minRise) {
            data.wallClimbBuffer = 0;
            return;
        }
        Block wall = GroundUtil.adjacentWall(player);
        if (wall == null) {
            data.wallClimbBuffer = 0;
            return;
        }
        if (data.wallClimbBuffer == 0) {
            data.wallClimbStartY = player.getLocation().getY();
        }
        data.wallClimbBuffer++;

        double minHeight = plugin.config().optDouble(CheckType.WALLCLIMB, "min-climb-height", 1.3);
        double climbed = player.getLocation().getY() - data.wallClimbStartY;
        if (data.wallClimbBuffer >= 2 * lagMul && climbed > minHeight) {
            Material held = player.getInventory().getItemInMainHand().getType();
            String heldInfo = held.isAir() ? "empty hand"
                    : held.name() + (held.isBlock() ? " (block)" : " (NOT a block)");
            wallClimb.flag(player, data, String.format(Locale.ROOT,
                    "crawling UP %s wall: +%.2f blocks (vy=%+.2f b/t, holding %s)",
                    wall.getType().name(), climbed, vSpeed, heldInfo), 3);
            data.wallClimbBuffer = 0;
            rollback(player, data);
        }
    }

    /**
     * AirJump / double jump: a sudden upward velocity spike while already
     * airborne. A legitimate jump always starts from a grounded sample;
     * gravity makes airborne vy monotonically decrease (except bounces,
     * which are excluded via slime/soft landing and velocity grace).
     */
    private void checkAirJump(Player player, PlayerData data, double vSpeed, double prevVSpeed,
                              boolean airborne, boolean grounded, boolean climbing, boolean liquid,
                              boolean gliding, boolean levitating, boolean mayFly,
                              boolean exempt, boolean fresh, int lagMul) {
        Check airJump = plugin.check(CheckType.AIRJUMP);
        if (airJump == null || !plugin.config().isEnabled(CheckType.AIRJUMP)) return;

        boolean prevAir = data.prevAirborne;
        if (mayFly || exempt || fresh || grounded || climbing || liquid || gliding
                || levitating || !airborne || !prevAir) {
            data.airJumpBuffer = Math.max(0, data.airJumpBuffer - 1);
            return;
        }
        if (vSpeed > 0.25 && vSpeed > prevVSpeed + 0.10
                && !nearSlimeBelow(player) && !GroundUtil.softLanding(player)) {
            data.airJumpBuffer++;
            int needed = plugin.config().optInt(CheckType.AIRJUMP, "buffer", 2) * lagMul;
            if (data.airJumpBuffer >= needed) {
                airJump.flag(player, data, String.format(Locale.ROOT,
                        "air jump: vy=%+.2f after %+.2f b/t while airborne", vSpeed, prevVSpeed), 3);
                data.airJumpBuffer = 0;
                rollback(player, data);
            }
        } else {
            data.airJumpBuffer = Math.max(0, data.airJumpBuffer - 1);
        }
    }

    /**
     * Phase / NoClip: the player's body is fully inside stacked occluding
     * cubes (a real wall, not leaves/glass/slabs) for min-ticks in a row.
     * Pistons grant velocity grace, so legit pushes are not counted.
     */
    private void checkPhase(Player player, PlayerData data, boolean mayFly,
                            boolean exempt, boolean fresh, boolean gliding) {
        Check phase = plugin.check(CheckType.PHASE);
        if (phase == null || !plugin.config().isEnabled(CheckType.PHASE)) {
            data.phaseTicks = 0;
            return;
        }
        if (mayFly || exempt || fresh || gliding || player.isSwimming() || player.isRiptiding()) {
            data.phaseTicks = 0;
            return;
        }
        if (insideFullSolid(player)) {
            data.phaseTicks += INTERVAL_TICKS;
            int minTicks = plugin.config().optInt(CheckType.PHASE, "min-ticks", 30);
            if (data.phaseTicks >= minTicks) {
                Location loc = player.getLocation();
                phase.flag(player, data, String.format(Locale.ROOT,
                        "inside solid for %d ticks at %.1f %.1f %.1f",
                        data.phaseTicks, loc.getX(), loc.getY(), loc.getZ()), 3);
                data.phaseTicks = 0;
                rollback(player, data);
            }
        } else {
            data.phaseTicks = Math.max(0, data.phaseTicks - INTERVAL_TICKS);
        }
    }

    private static boolean insideFullSolid(Player player) {
        BoundingBox box = player.getBoundingBox();
        World w = player.getWorld();
        int x = (int) Math.floor((box.getMinX() + box.getMaxX()) / 2);
        int z = (int) Math.floor((box.getMinZ() + box.getMaxZ()) / 2);
        int yFeet = (int) Math.floor(box.getMinY() + 0.2);
        int yHead = (int) Math.floor(box.getMaxY() - 0.2);
        if (yHead <= yFeet) return false;
        return w.getBlockAt(x, yFeet, z).getType().isOccluding()
                && w.getBlockAt(x, yHead, z).getType().isOccluding();
    }

    private void trackFall(Player player, PlayerData data, boolean grounded,
                           double dy, boolean mayFly, boolean exempt, boolean fresh) {
        if (exempt || fresh || mayFly) {
            data.pendingFallDamage = false;
            data.pendingFallDistance = 0;
            return;
        }
        Check nofall = plugin.check(CheckType.NOFALL);
        double minDist = plugin.config().optDouble(CheckType.NOFALL, "min-fall-distance", 4.0);

        if (grounded) {
            if (data.pendingFallDamage && System.currentTimeMillis() > data.pendingFallDeadline) {
                // landed from a big fall, no fall damage event ever arrived
                if (nofall != null && plugin.config().isEnabled(CheckType.NOFALL)) {
                    nofall.flag(player, data, String.format(Locale.ROOT,
                            "landed from %.1f blocks without damage", data.pendingFallDistance), 3);
                }
                data.pendingFallDamage = false;
            }
            data.pendingFallDistance = 0;
            return;
        }
        if (dy < 0) {
            data.pendingFallDistance += -dy;
            if (data.pendingFallDistance >= minDist && !data.pendingFallDamage
                    && !GroundUtil.softLanding(player)) {
                data.pendingFallDamage = true;
                data.pendingFallDeadline = System.currentTimeMillis() + 2000L;
            }
        }
    }

    /**
     * Returns a confirmed cheater to their last legal ground position so the
     * cheat grants no advantage. Only called after a check's buffer confirmed,
     * honest players are never rubber-banded by this.
     */
    private void rollback(Player player, PlayerData data) {
        if (!plugin.config().rollbackEnabled) return;
        Location safe = data.lastGroundLocation;
        if (safe == null || !safe.getWorld().equals(player.getWorld())) return;
        if (safe.distanceSquared(player.getLocation()) < 0.25) return; // nothing to undo
        player.teleport(safe);
        data.lastTeleport = System.currentTimeMillis();
        data.lastLocation = safe.clone();
        data.hoverTicks = 0;
        data.airTicks = 0;
        data.jesusTicks = 0;
        data.wallClimbBuffer = 0;
        data.phaseTicks = 0;
        data.airJumpBuffer = 0;
        data.exemptVelocity(1500L);
    }

    private boolean nearSlimeBelow(Player player) {
        Location loc = player.getLocation();
        for (int y = -1; y >= -3; y--) {
            if (loc.clone().add(0, y, 0).getBlock().getType() == Material.SLIME_BLOCK) {
                return true;
            }
        }
        return false;
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}
