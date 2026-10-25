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

        data.packetCounter.increment();
        data.lastFlyingPacket = System.currentTimeMillis();

        Boolean ground = readBooleanField(msg, "onGround");
        if (ground != null) {
            data.lastPacketGround = ground;
            data.packetSeen = true;
        }
        Float pitch = readFloatField(msg, "xRot", "pitch");
        if (pitch != null && Math.abs(pitch) > 90.5f) {
            data.lastBadPitch = System.currentTimeMillis();
        }

        // position jump: a single move packet claiming a teleport-sized delta
        Double x = readDoubleField(msg, "x");
        Double y = readDoubleField(msg, "y");
        Double z = readDoubleField(msg, "z");
        if (x != null && y != null && z != null) {
            double px = data.lastPacketX, py = data.lastPacketY, pz = data.lastPacketZ;
            data.lastPacketX = x;
            data.lastPacketY = y;
            data.lastPacketZ = z;
            if (!Double.isNaN(px)) {
                double dx = x - px, dy = y - py, dz = z - pz;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                long now = System.currentTimeMillis();
                boolean grace = now - data.lastTeleport < 5000L
                        || now - data.lastRespawn < 5000L
                        || now - data.joinTime < 5000L;
                if (dist > 16.0 && !grace) {
                    data.lastPosJumpDist = dist;
                    data.lastPosJump = now;
                }
            }
        }
    }

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
        int maxRate = plugin.config().timerMaxRate;
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

            // TIMER (skip very high ping - their packets arrive in bursts)
            Check timer = plugin.check(CheckType.TIMER);
            if (timer != null && timer.enabled()
                    && now - data.joinTime > 5000L
                    && now - data.lastTeleport > 2000L
                    && player.getPing() <= lagPing) {
                if (rate > maxRate) {
                    if (data.timerHighRateSince == 0L) {
                        data.timerHighRateSince = now;
                    } else if (now - data.timerHighRateSince >= sustainMs) {
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

            // POSITION JUMP (single-packet teleport hacks)
            if (blink != null && blink.enabled() && data.lastPosJump != 0L
                    && now - data.lastPosJump < 1500L) {
                blink.flag(player, data, String.format(Locale.ROOT,
                        "position jumped %.1f blocks in a single packet", data.lastPosJumpDist), 4);
                data.lastPosJump = 0L;
            }

            // BADPACKETS
            Check bad = plugin.check(CheckType.BADPACKETS);
            if (bad != null && bad.enabled() && data.lastBadPitch != 0L) {
                bad.flag(player, data, "impossible pitch value in rotation packet", 5);
                data.lastBadPitch = 0L;
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
