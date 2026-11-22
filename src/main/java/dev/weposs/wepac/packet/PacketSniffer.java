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

    private TransactionManager transactions;

    public void transactions(TransactionManager tm) {
        this.transactions = tm;
    }

    /** Netty channel of a player (null when not injected). */
    public Channel channel(UUID uuid) {
        return channels.get(uuid);
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
        if (transactions != null && (cls.contains("ServerboundPongPacket")
                || cls.contains("PacketPlayInPong"))) {
            PlayerData pd = plugin.data().getIfLoaded(uuid);
            Integer id = readIntField(msg, "id", "a");
            if (pd != null && id != null) transactions.onPong(pd, id);
            return;
        }
        if (cls.contains("ServerboundMoveVehiclePacket") || cls.contains("PacketPlayInVehicleMove")) {
            PlayerData vd = plugin.data().getIfLoaded(uuid);
            double[] xyz = readVehiclePos(msg);
            if (vd != null && xyz != null) {
                vd.vehicleMoves.offer(new double[]{System.currentTimeMillis(), xyz[0], xyz[1], xyz[2]});
            } else if (xyz == null && !vehicleWarned) {
                vehicleWarned = true;
                plugin.getLogger().warning("Could not read vehicle move packet " + cls
                        + " - packet BoatFly simulation INACTIVE on this build.");
            }
            return;
        }
        if (packetOrder(uuid, cls, msg)) return;
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
        double kbH = data.kbArmedH, kbY = 0.0;
        if (!Double.isNaN(kbH)) {
            kbY = data.kbArmedY;
            data.kbArmedH = Double.NaN; // consumed by the first post-ack move
        }
        data.packetMoves.offer(new double[]{now, x, y, z,
                Boolean.TRUE.equals(ground) ? 1.0 : 0.0, kbH, kbY});
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
     * Packet-order family. Tick boundary = ServerboundClientTickEndPacket
     * (1.21.2+) when the client sends it, else any flying packet.
     * @return true when the packet was fully handled here
     */
    private boolean packetOrder(UUID uuid, String cls, Object msg) {
        boolean tickEnd = cls.contains("ClientTickEnd");
        boolean flying = cls.contains("PacketPlayInFlying") || cls.contains("ServerboundMovePlayerPacket");
        boolean interact = cls.contains("ServerboundInteractPacket") || cls.contains("PacketPlayInUseEntity");
        boolean swing = cls.contains("ServerboundSwingPacket") || cls.contains("PacketPlayInArmAnimation");
        boolean slot = cls.contains("ServerboundSetCarriedItemPacket") || cls.contains("PacketPlayInHeldItemSlot");
        if (!(tickEnd || flying || interact || swing || slot)) return false;
        PlayerData d = plugin.data().getIfLoaded(uuid);
        if (d == null) return !flying;
        if (tickEnd) {
            d.tickEndSeen = true;
            d.lastTickEndAt = System.currentTimeMillis();
        }
        // timer balance: one unit per CLIENT TICK (tick-end packet on 1.21.2+,
        // else the per-tick flying packet of older protocols)
        if (tickEnd || (flying && !d.tickEndSeen)) timerTick(d);
        if (tickEnd || (flying && !d.tickEndSeen)) {
            if (d.attackAwaitSwing) d.noSwingEvents++;
            d.attackAwaitSwing = false;
            d.lastAttackTarget = Integer.MIN_VALUE;
            return tickEnd;
        }
        if (flying) return false;
        if (swing) {
            d.attackAwaitSwing = false;
            return true;
        }
        if (interact) {
            if (isAttack(msg)) {
                Integer target = readIntField(msg, "entityId", "a");
                if (target != null) {
                    int prev = d.lastAttackTarget;
                    if (prev != Integer.MIN_VALUE && prev != target) d.multiTargetEvents++;
                    d.lastAttackTarget = target;
                }
                d.attackAwaitSwing = true;
            }
            return true;
        }
        Integer s = readIntField(msg, "slot", "a");
        if (s != null) {
            if (s == d.lastHeldSlot) d.duplicateSlotEvents++;
            d.lastHeldSlot = s;
        }
        return true;
    }

    private volatile boolean vehicleWarned = false;
    private static final Map<Class<?>, Field[]> VEC_FIELDS = new ConcurrentHashMap<>();

    /**
     * Vehicle position: pre-1.21.2 three doubles x/y/z on the packet; 1.21.2+
     * a record holding a Vec3 position. Both resolved mapping-agnostically
     * (first three non-static doubles in declaration order).
     */
    private static double[] readVehiclePos(Object packet) {
        try {
            Field[] direct = doubles(packet.getClass());
            if (direct != null) {
                return new double[]{direct[0].getDouble(packet), direct[1].getDouble(packet),
                        direct[2].getDouble(packet)};
            }
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String tn = f.getType().getName();
                if (tn.endsWith("Vec3") || tn.endsWith("Vec3D")) {
                    f.setAccessible(true);
                    Object vec = f.get(packet);
                    Field[] vf = vec == null ? null : doubles(vec.getClass());
                    if (vf == null) return null;
                    return new double[]{vf[0].getDouble(vec), vf[1].getDouble(vec), vf[2].getDouble(vec)};
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Field[] doubles(Class<?> c) {
        Field[] cached = VEC_FIELDS.get(c);
        if (cached != null) return cached.length == 3 ? cached : null;
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == Double.TYPE) {
                f.setAccessible(true);
                out.add(f);
                if (out.size() == 3) break;
            }
        }
        Field[] arr = out.size() == 3 ? out.toArray(new Field[0]) : new Field[0];
        VEC_FIELDS.put(c, arr);
        return arr.length == 3 ? arr : null;
    }

    /** Lower clamp: lag may be caught up (burst after a stall), not banked for later. */
    private static final long TIMER_MIN_BALANCE = -1500L;

    private void timerTick(PlayerData d) {
        long now = System.currentTimeMillis();
        long last = d.timerLastTick;
        d.timerLastTick = now;
        if (last == 0L || now - d.joinTime < 5000L || now - d.lastTeleport < 1000L) {
            d.timerBalance = 0L;
            return;
        }
        long bal = d.timerBalance + 50L - (now - last);
        if (bal < TIMER_MIN_BALANCE) bal = TIMER_MIN_BALANCE;
        long limit = plugin.config().optLongOr(CheckType.TIMER, "balance-limit-ms", 300L);
        if (bal > limit) {
            d.timerFlagBalance = bal;
            bal = 0L;
        }
        d.timerBalance = bal;
    }

    private static final Map<Class<?>, Boolean> ATTACK_ACTION_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.util.Optional<Field>> ACTION_FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * The attack action is the only interact action WITHOUT instance fields
     * (interact / interact_at carry a hand and a location) - mapping-agnostic.
     */
    private static boolean isAttack(Object packet) {
        try {
            java.util.Optional<Field> af = ACTION_FIELD_CACHE.computeIfAbsent(packet.getClass(), c -> {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (!t.isPrimitive() && t.getName().startsWith("net.minecraft") && !t.isEnum()) {
                        f.setAccessible(true);
                        return java.util.Optional.of(f);
                    }
                }
                return java.util.Optional.empty();
            });
            if (af.isEmpty()) return false;
            Object action = af.get().get(packet);
            if (action == null) return false;
            return ATTACK_ACTION_CACHE.computeIfAbsent(action.getClass(), k -> {
                for (Field f : k.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) return false;
                }
                return true;
            });
        } catch (Throwable t) {
            return false;
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

    private static Integer readIntField(Object packet, String... names) {
        Field f = resolveField(packet.getClass(), Integer.TYPE, names);
        if (f == null) return null;
        try {
            return f.getInt(packet);
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
