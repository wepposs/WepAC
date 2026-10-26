package dev.weposs.wepac.packet;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet layer WITHOUT external dependencies: a ChannelDuplexHandler is injected
 * directly into each player's netty pipeline (the same technique protocol
 * libraries use). It observes the client's flying packets and provides the data
 * for the packet-grade checks that event-based anti-cheats cannot do:
 *
 *  - TIMER:       vanilla sends exactly ~20 flying packets/sec; timer hacks 25-40.
 *  - BLINK:       packet stream stops while the player keeps moving.
 *  - GROUND_SPOOF: client claims onGround=true while server-side physics says airborne
 *                 (the root signal of packet Criticals and part of Fly).
 *  - BADPACKETS:  impossible pitch values (|pitch| > 90).
 *
 * Netty-thread safety: the handler only writes volatile fields / a LongAdder on
 * PlayerData; all flagging is evaluated on the main thread (1Hz task here +
 * MovementMonitor).
 */
public final class PacketSniffer {

    private static final String HANDLER_NAME = "wepac_sniffer";

    private final WepAC plugin;
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private boolean available = true;

    public PacketSniffer(WepAC plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            inject(p);
        }
        // 1Hz evaluation of timer / blink / badpackets
        Bukkit.getScheduler().runTaskTimer(plugin, this::evaluate, 20L, 20L);
        plugin.getLogger().info("Packet sniffer active (direct netty hook).");
    }

    public void stop() {
        for (UUID id : channels.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) eject(p);
        }
        channels.clear();
    }

    public void inject(Player player) {
        if (!available) return;
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Channel channel = findChannel(handle, 0);
            if (channel == null) {
                warnOnce("Could not locate netty channel for " + player.getName()
                        + "; packet checks inactive for them.");
                return;
            }
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channels.put(player.getUniqueId(), channel);
                return;
            }
            final UUID uuid = player.getUniqueId();
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME,
                    new ChannelDuplexHandler() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            try {
                                onPacket(uuid, msg);
                            } catch (Throwable ignored) {
                                // never break the connection
                            }
                            super.channelRead(ctx, msg);
                        }
                    });
            channels.put(player.getUniqueId(), channel);
            // a successful injection re-arms warnings for any future failure
            warned = false;
        } catch (Throwable t) {
            // a single player's failure must not disable packet checks for everyone;
            // log once and keep trying for other players / future joins
            warnOnce("Packet injection failed for " + player.getName() + ": " + t);
        }
    }

    private volatile boolean warned = false;
    private volatile boolean posFieldsWarned = false;
    private volatile boolean pitchWarned = false;
    private volatile boolean capabilityLogged = false;

    private void warnOnce(String msg) {
        if (!warned) {
            warned = true;
            plugin.getLogger().warning(msg + " (further injection failures will be silent)");
        }
    }

    public void eject(Player player) {
        Channel channel = channels.remove(player.getUniqueId());
        if (channel == null) return;
        try {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Netty thread: record only, never flag
    // ------------------------------------------------------------------

    /**
     * Whether a flying packet CARRIES a position. Since 1.21.2 the x/y/z fields
     * live on the PARENT ServerboundMovePlayerPacket, so a field read on a
     * Rot/StatusOnly packet returns 0.0 (not null!) - consuming those as
     * positions produced "teleport to world origin" garbage (mass pos-jump FPs)
     * and fed y=0 into the physics sim. Class-name gate: Mojang $Pos/$PosRot,
     * Spigot PacketPlayInPosition(Look); on Paper the hasPos flag must
     * additionally be TRUE when that field exists.
     */
    public static boolean positionBearing(String cls, Boolean hasPos) {
        boolean byName = cls.contains("Pos") || cls.contains("Position");
        return byName && (hasPos == null || hasPos);
    }

    private void onPacket(UUID uuid, Object msg) {
        // IMPORTANT: movement packets are NESTED classes
        // (ServerboundMovePlayerPacket$Pos/$Rot/$PosRot/$Status on Mojang mappings,
        // PacketPlayInFlying$PacketPlayInPosition/... on Spigot mappings).
        // Class#getSimpleName() of a nested class drops the outer name ("Pos",
        // "PacketPlayInPosition"), so we MUST match on the full binary name.
        String cls = msg.getClass().getName();
        boolean flying = cls.contains("PacketPlayInFlying")
                || cls.contains("ServerboundMovePlayerPacket");
        if (!flying) return;

        PlayerData data = plugin.data().getIfLoaded(uuid);
        if (data == null) return;

        long prevFlying = data.lastFlyingPacket;
        data.packetCounter.increment();
        long now = System.currentTimeMillis();
        data.lastFlyingPacket = now;

        Boolean ground = readBooleanField(msg, "onGround");
        if (ground != null) {
            data.lastPacketGround = ground;
            data.packetSeen = true;
        }
        // strict (named-only) lookup: on pre-1.21.2 builds "hasPos" does not
        // exist and the unique-boolean fallback would wrongly return onGround
        Boolean hasPos = readStrictBoolean(msg, "hasPos");
        Float pitch = readFloatField(msg, "xRot", "pitch");
        if (pitch != null && (!Float.isFinite(pitch) || Math.abs(pitch) > 90.5f)) {
            data.lastBadPitch = now;
        }

        // position fields exist on the PARENT class since 1.21.2 - reading them
        // from a Rot/Status packet yields 0.0, so consumption is gated by
        // positionBearing() and only real position packets update the state
        Double x = readDoubleField(msg, "x");
        Double y = readDoubleField(msg, "y");
        Double z = readDoubleField(msg, "z");
        boolean posPacket = cls.contains("Pos") || cls.contains("Position");
        if (x == null && posPacket && !posFieldsWarned) {
            // silent death of PacketFly/pos-jump on an unknown mapping is worse
            // than a noisy log: say once that this build is not supported
            posFieldsWarned = true;
            plugin.getLogger().warning("Could not resolve position fields on move packet "
                    + cls + " - PacketFly and position-jump checks are INACTIVE on this "
                    + "server build. Please report this mapping.");
        }
        if (pitch == null && !pitchWarned
                && (cls.contains("Rot") || cls.contains("Look"))) {
            pitchWarned = true;
            plugin.getLogger().warning("Could not resolve rotation fields on move packet "
                    + cls + " - BadPackets pitch checks INACTIVE on this server build.");
        }
        if (!capabilityLogged && x != null) {
            // A3 capability matrix: log once which packet fields this server
            // build exposes, so admins can see exactly what is active
            capabilityLogged = true;
            plugin.getLogger().info("Packet layer probe (" + cls + "): position=true"
                    + " rotation=" + (pitch != null) + " onGround=" + (ground != null)
                    + ((pitch != null && ground != null)
                            ? " - all packet checks ACTIVE"
                            : " - SOME PACKET CHECKS INACTIVE on this build"));
        }
        if (!positionBearing(cls, hasPos) || x == null || y == null || z == null) {
            return; // Rot/StatusOnly: parent-field zeros must never be consumed
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            // NaN/Inf coordinates: impossible in vanilla, crash other plugins
            data.lastBadPosition = now;
            return;
        }
        // feed the per-packet physics simulation (bounded queue; when the main
        // thread stalls the offer silently drops - Sim resyncs on time gaps)
        data.packetMoves.offer(new double[]{now, x, y, z,
                Boolean.TRUE.equals(ground) ? 1.0 : 0.0});
        double px = data.lastPacketX, py = data.lastPacketY, pz = data.lastPacketZ;
        data.lastPacketX = x;
        data.lastPacketY = y;
        data.lastPacketZ = z;
        if (!Double.isNaN(px)) {
            double dx = x - px, dy = y - py, dz = z - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean grace = now - data.lastTeleport < 5000L
                    || now - data.lastRespawn < 5000L
                    || now - data.joinTime < 5000L;
            if (!grace) {
                if (dist > 16.0) {
                    data.lastPosJumpDist = dist;
                    data.lastPosJump = now;
                }
                // micro-blink (B6): the stream paused for 0.5-2s and the first
                // packet on resume sits further away than any vanilla speed
                // could have travelled during the silence
                long gap = prevFlying == 0L ? 0L : now - prevFlying;
                if (gap > 500L && gap < plugin.config().blinkTimeoutMs
                        && dist > gap / 50.0 * 0.6 + 0.5) {
                    data.lastBlinkResume = now;
                    data.lastBlinkResumeGap = gap;
                    data.lastBlinkResumeDist = dist;
                }
            }
        }
    }

    /**
     * Reflection caches. resolveField runs for EVERY move packet (20/s per player);
     * without the named cache each miss walks the class hierarchy throwing
     * NoSuchFieldException (stack-fill cost) on Spigot-mapped servers.
     */
    private static final Map<String, java.util.Optional<Field>> NAMED_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private static Boolean readBooleanField(Object packet, String... names) {
        Field f = resolveField(packet.getClass(), Boolean.TYPE, names);
        if (f == null) return null;
        try {
            return f.getBoolean(packet);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Named-only boolean lookup (NO unique-fallback): used for "hasPos", where a
     * pre-1.21.2 packet lacks the field and the fallback would wrongly return
     * onGround instead. null = field absent on this mapping.
     */
    private static Boolean readStrictBoolean(Object packet, String name) {
        String key = packet.getClass().getName() + "|strict|boolean|" + name;
        java.util.Optional<Field> hit = NAMED_FIELD_CACHE.computeIfAbsent(key,
                k -> java.util.Optional.ofNullable(
                        findNamedField(packet.getClass(), Boolean.TYPE, name)));
        if (hit.isEmpty()) return null;
        try {
            return hit.get().getBoolean(packet);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Float readFloatField(Object packet, String... names) {
        Field f = resolveField(packet.getClass(), Float.TYPE, names);
        if (f == null) return null;
        try {
            return f.getFloat(packet);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Double readDoubleField(Object packet, String... names) {
        Field f = resolveField(packet.getClass(), Double.TYPE, names);
        if (f == null) return null;
        try {
            return f.getDouble(packet);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** Field by name first (walking superclasses); fallback: unique primitive of that type. */
    private static Field resolveField(Class<?> clazz, Class<?> type, String... names) {
        String key = clazz.getName() + '|' + type.getName() + '|' + String.join(",", names);
        java.util.Optional<Field> hit = NAMED_FIELD_CACHE.computeIfAbsent(key,
                k -> java.util.Optional.ofNullable(findNamedField(clazz, type, names)));
        return hit.orElseGet(() -> resolveUniqueFallback(clazz, type));
    }

    private static Field findNamedField(Class<?> clazz, Class<?> type, String... names) {
        for (String name : names) {
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == type) {
                        f.setAccessible(true);
                        return f;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Field resolveUniqueFallback(Class<?> clazz, Class<?> type) {
        Field cached = FIELD_CACHE.get(clazz);
        if (cached != null && cached.getType() == type) return cached;
        Field unique = null;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    if (unique != null) return null; // ambiguous - refuse to guess
                    unique = f;
                }
            }
            c = c.getSuperclass();
        }
        if (unique != null) {
            unique.setAccessible(true);
            FIELD_CACHE.put(clazz, unique);
            return unique;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Main thread: evaluation
    // ------------------------------------------------------------------

    private long lastEvaluate = System.currentTimeMillis();

    private void evaluate() {
        long now = System.currentTimeMillis();
        // Normalise by the REAL elapsed wall time: under server lag this task can
        // run later than 1s apart, which would otherwise inflate packets/sec and
        // false-flag Timer. Guard against absurd gaps (freeze) too.
        long elapsed = now - lastEvaluate;
        lastEvaluate = now;
        if (elapsed < 200L || elapsed > 5000L) {
            // too short to measure, or the server froze - drop this window's counts
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
                if (d != null) d.packetCounter.sumThenReset();
            }
            return;
        }
        double maxRate = plugin.config().timerMaxRate;
        long sustainMs = plugin.config().timerSustainMs;
        long blinkTimeout = plugin.config().blinkTimeoutMs;
        int lagPing = plugin.config().lagPingThreshold;

        for (Player player : Bukkit.getOnlinePlayers()) {
          try {
            PlayerData data = plugin.data().getIfLoaded(player.getUniqueId());
            if (data == null) continue;
            if (player.hasPermission("wepac.bypass")) continue;

            long count = data.packetCounter.sumThenReset();
            double rate = count * 1000.0 / elapsed; // packets per second, lag-normalised

            // TIMER. High ping is no longer a full exclusion (a timer on a bad
            // connection used to be invisible): burst arrivals self-reset the
            // streak anyway, and for high-ping players the rate must simply
            // stay above the cap for longer (5s) before a flag.
            Check timer = plugin.check(CheckType.TIMER);
            if (timer != null && timer.enabled()
                    && now - data.joinTime > 5000L
                    && now - data.lastTeleport > 2000L) {
                long sustainNeeded = player.getPing() > lagPing
                        ? Math.max(sustainMs, 5000L) : sustainMs;
                if (rate > maxRate) {
                    if (data.timerHighRateSince == 0L) {
                        data.timerHighRateSince = now;
                    } else if (now - data.timerHighRateSince >= sustainNeeded) {
                        timer.flag(player, data, String.format(Locale.ROOT,
                                "%.1f move-packets/sec (vanilla: 20)", rate), 4);
                        data.timerHighRateSince = 0L;
                    }
                } else {
                    data.timerHighRateSince = 0L;
                }
            }

            // BLINK (riders send vehicle-move packets, not player position packets;
            // dead players send none - never false-flag either)
            boolean sendsPositionPackets = !player.isDead() && !player.isInsideVehicle();
            Check blink = plugin.check(CheckType.BLINK);
            if (blink != null && blink.enabled() && sendsPositionPackets
                    && data.lastFlyingPacket != 0L
                    && now - data.lastFlyingPacket > blinkTimeout
                    && now - data.joinTime > 5000L) {
                blink.flag(player, data, String.format(Locale.ROOT,
                        "no move packets for %dms", now - data.lastFlyingPacket), 3);
                data.lastFlyingPacket = now; // avoid alert spam
            }

            // MICRO-BLINK (B6): the stream paused for 0.5s..blink-timeout and the
            // first packet on resume sits further away than any vanilla speed
            // could have moved during the silence - the classic short-blink that
            // the fixed 2s timeout could never see
            if (blink != null && blink.enabled() && data.lastBlinkResume != 0L) {
                if (now - data.lastBlinkResume <= 1500L
                        && !player.isGliding() && !player.isRiptiding()
                        && !player.isInsideVehicle() && !player.isDead()
                        && now - data.lastTeleport > 3000L
                        && now - data.lastRespawn > 3000L
                        && now - data.joinTime > 5000L) {
                    // buffer of 2 events inside 4s: one-off lag spikes (e.g. a
                    // frozen tick while landing off an elytra glide) must not
                    // flag, a pulsing blink cheat re-triggers immediately
                    if (now - data.lastBlinkResumeAt > 4000L) data.blinkResumeBuffer = 0;
                    data.lastBlinkResumeAt = now;
                    data.blinkResumeBuffer++;
                    if (data.blinkResumeBuffer >= 2) {
                        data.blinkResumeBuffer = 0;
                        blink.flag(player, data, String.format(Locale.ROOT,
                                "blink: stream paused %dms, resumed %.1f blocks away (vanilla max ~%.1f)",
                                data.lastBlinkResumeGap, data.lastBlinkResumeDist,
                                data.lastBlinkResumeGap / 50.0 * 0.6 + 0.5), 3);
                    }
                }
                data.lastBlinkResume = 0L; // consume regardless of verdict
            }

            // POSITION JUMP (single-packet teleport hacks). Semantically this is
            // an impossible VALUE, not a stream interruption - flagged as BadPackets
            Check bad = plugin.check(CheckType.BADPACKETS);
            if (bad != null && bad.enabled() && data.lastPosJump != 0L
                    && now - data.lastPosJump < 1500L) {
                bad.flag(player, data, String.format(Locale.ROOT,
                        "position jumped %.1f blocks in a single packet", data.lastPosJumpDist), 4);
                data.lastPosJump = 0L;
            }

            // BADPACKETS
            if (bad != null && bad.enabled() && data.lastBadPitch != 0L) {
                bad.flag(player, data, "impossible pitch value in rotation packet", 5);
                data.lastBadPitch = 0L;
            }
            if (bad != null && bad.enabled() && data.lastBadPosition != 0L) {
                bad.flag(player, data, "non-finite (NaN/Inf) coordinates in move packet", 5);
                data.lastBadPosition = 0L;
            }
          } catch (Throwable t) {
              plugin.debug("PacketSniffer evaluate error for " + player.getName() + ": " + t);
          }
        }
    }

    // ------------------------------------------------------------------
    // Reflection helper: find the netty Channel below the NMS entity handle
    // ------------------------------------------------------------------

    private static Channel findChannel(Object obj, int depth) {
        if (obj == null || depth > 4) return null;
        if (obj instanceof Channel) return (Channel) obj;
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (Channel.class.isAssignableFrom(ft)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof Channel) return (Channel) v;
                    } catch (Throwable ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        // second pass: descend into non-primitive complex fields (connection objects)
        clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.getName().startsWith("java.")
                        || ft.getName().startsWith("io.netty.") || ft.isArray()
                        || ft.getName().startsWith("it.unimi.")
                        || ft.getName().startsWith("com.mojang.")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    Channel c = findChannel(v, depth + 1);
                    if (c != null) return c;
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
