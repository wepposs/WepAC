package dev.weposs.wepac.packet;

import dev.weposs.wepac.WepAC;
import dev.weposs.wepac.check.Check;
import dev.weposs.wepac.check.CheckType;
import dev.weposs.wepac.data.PlayerData;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Transaction layer (B1.4 / Grim-style ping-pong synchronisation).
 *
 * Every server tick, after recording the tick's position snapshot, the server
 * writes a {@code ClientboundPingPacket(id)} into each player's channel. TCP
 * keeps it ordered behind everything sent before it, and the vanilla client
 * answers with {@code ServerboundPongPacket(id)} only after it has processed
 * every earlier packet. So when the pong for tick N comes back we KNOW the
 * client has seen the world state of tick N - not a guess from
 * {@code Player#getPing()} (which keep-alive delay cheats spoof freely).
 *
 * Consumers:
 *  - Reach: lag compensation window = [ackTick - interpolation, currentTick]
 *    in exact ticks, instead of a millisecond heuristic;
 *  - Velocity: the knockback measurement window opens only once the client
 *    has acknowledged the tick the knockback was sent in;
 *  - BadPackets: answering pongs out of order is impossible for a vanilla
 *    client; withholding them while movement keeps streaming is ping spoofing.
 *
 * IDs are negative so they never collide with ids used by the server itself
 * (vanilla does not ping in play, other plugins conventionally use positives).
 * Our own pongs are swallowed before they reach the server's packet listener.
 *
 * If the ping packet class cannot be resolved on this build the layer logs
 * once and every consumer falls back to its previous heuristic.
 */
public final class TransactionManager {

    private static final String[] PING_CLASSES = {
            "net.minecraft.network.protocol.common.ClientboundPingPacket",  // 1.20.2+
            "net.minecraft.network.protocol.game.ClientboundPingPacket",    // 1.17-1.20.1
            "net.minecraft.network.protocol.game.PacketPlayOutPing",
    };

    /** Pure, thread-safe per-player transaction queue (unit-tested in SelfTest). */
    public static final class TxQueue {
        private final ArrayDeque<long[]> pending = new ArrayDeque<>(); // {id, tick, sentMs}
        private int nextId = -1;
        private volatile long ackedTick = -1L;
        private volatile long rttMs = -1L;
        private volatile long outOfOrder = 0L;

        /** Registers a ping for {@code tick}; returns the id to send. */
        public synchronized int send(long tick, long nowMs) {
            int id = nextId;
            nextId = nextId <= -30000 ? -1 : nextId - 1;
            pending.addLast(new long[]{id, tick, nowMs});
            // a client that never answers must not grow this without bound
            while (pending.size() > 600) pending.pollFirst();
            return id;
        }

        /**
         * Handles an incoming pong.
         * @return true when the id was ours (caller swallows the packet)
         */
        public synchronized boolean onPong(int id, long nowMs) {
            if (id >= 0) return false;
            long[] head = pending.peekFirst();
            if (head == null) return false;
            if (head[0] == id) {
                pending.pollFirst();
                ackedTick = head[1];
                rttMs = nowMs - head[2];
                return true;
            }
            // not the head: either an out-of-order answer (impossible in
            // vanilla) or a stale id after the overflow trim
            for (java.util.Iterator<long[]> it = pending.iterator(); it.hasNext(); ) {
                long[] e = it.next();
                if (e[0] == id) {
                    outOfOrder++;
                    // everything before it is considered skipped
                    while (pending.peekFirst() != e) pending.pollFirst();
                    pending.pollFirst();
                    ackedTick = e[1];
                    rttMs = nowMs - e[2];
                    return true;
                }
            }
            return true; // negative id we no longer track: still ours, swallow
        }

        public long ackedTick() {
            return ackedTick;
        }

        public long rttMs() {
            return rttMs;
        }

        /** Age (ms) of the oldest unanswered ping, 0 when none. */
        public synchronized long oldestPendingAge(long nowMs) {
            long[] head = pending.peekFirst();
            return head == null ? 0L : nowMs - head[2];
        }

        public long consumeOutOfOrder() {
            long v = outOfOrder;
            outOfOrder = 0L;
            return v;
        }

        public synchronized void clear() {
            pending.clear();
            ackedTick = -1L;
            rttMs = -1L;
        }
    }

    private final WepAC plugin;
    private final PacketSniffer sniffer;
    private Constructor<?> pingCtor;
    private boolean active;
    private volatile long tick = 0L;

    public TransactionManager(WepAC plugin, PacketSniffer sniffer) {
        this.plugin = plugin;
        this.sniffer = sniffer;
        this.pingCtor = resolvePing();
        this.active = pingCtor != null;
        if (active) {
            plugin.getLogger().info("Transaction layer ACTIVE (" + pingCtor.getDeclaringClass().getName()
                    + ") - exact tick-level lag compensation for Reach/Velocity.");
        } else {
            plugin.getLogger().warning("Transaction layer INACTIVE: ping packet class not found on this "
                    + "server build - Reach/Velocity fall back to ping-time heuristics.");
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::evaluate, 40L, 20L);
    }

    public boolean active() {
        return active;
    }

    /** Monotonic server tick counter, advanced by {@link #onTick()}. */
    public long tick() {
        return tick;
    }

    private static Constructor<?> resolvePing() {
        for (String name : PING_CLASSES) {
            try {
                Class<?> c = Class.forName(name);
                Constructor<?> ctor = c.getDeclaredConstructor(int.class);
                ctor.setAccessible(true);
                return ctor;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Called once per tick from the 20Hz task AFTER the tick's position
     * snapshot was recorded, so pong(N) == "client has seen snapshot N".
     */
    public void onTick() {
        tick++;
        if (!active) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
            if (d == null) continue;
            Channel ch = sniffer.channel(p.getUniqueId());
            if (ch == null || !ch.isActive()) continue;
            int id = d.tx.send(tick, now);
            try {
                ch.writeAndFlush(pingCtor.newInstance(id));
            } catch (Throwable t) {
                active = false;
                plugin.getLogger().warning("Transaction layer disabled: cannot send ping (" + t + ")");
                return;
            }
        }
    }

    /** 1Hz: out-of-order pongs and pong withholding (ping spoof / backtrack). */
    private void evaluate() {
        if (!active) return;
        long now = System.currentTimeMillis();
        long withholdMs = plugin.config().optLongOr(CheckType.BADPACKETS, "transaction-timeout-ms", 15000L);
        Check bad = plugin.check(CheckType.BADPACKETS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
            if (d == null || bad == null || !bad.enabled() || p.hasPermission("wepac.bypass")) continue;
            long ooo = d.tx.consumeOutOfOrder();
            if (ooo > 0) {
                bad.flag(p, d, "transactions answered out of order (" + ooo + "x) - impossible for vanilla", 5);
            }
            long age = d.tx.oldestPendingAge(now);
            // movement keeps streaming in, pongs do not: the client is holding
            // them back on purpose (ping spoof / fake lag / backtrack)
            if (age > withholdMs && now - d.lastFlyingPacket < 1000L
                    && now - d.joinTime > 10000L) {
                bad.flag(p, d, String.format(Locale.ROOT,
                        "withholding transactions for %.1fs while still moving (ping spoof)", age / 1000.0), 4);
                d.tx.clear();
            }
        }
    }
}
