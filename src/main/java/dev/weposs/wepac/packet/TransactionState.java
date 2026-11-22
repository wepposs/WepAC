package dev.weposs.wepac.packet;

import java.util.ArrayDeque;

/**
 * Per-player transaction bookkeeping (pure logic, no Bukkit - unit-tested).
 *
 * The server sends a play-phase Ping packet with a unique negative id every
 * tick; the client MUST answer each with a Pong in the exact order received,
 * after it has processed every packet sent before it. An acknowledged ping
 * therefore proves "the client has seen the world as of send time T" - a
 * lag-compensation anchor that, unlike the keep-alive ping, cannot be spoofed
 * without the delay being visible here.
 *
 * Thread model: {@link #onSend} on the main thread / netty write, {@link #onPong}
 * on the netty thread; all methods synchronize on this (cheap, uncontended).
 */
public final class TransactionState {

    /** Results of {@link #onPong}. */
    public enum PongResult { OK, UNKNOWN_ID, OUT_OF_ORDER, STALE }

    /** Max in-flight pings tracked; older ones are dropped (client is way behind). */
    public static final int MAX_PENDING = 400;

    private final ArrayDeque<long[]> pending = new ArrayDeque<>(); // {id, sendMs}
    private int nextId = -1;
    /** Most recent id evicted by the MAX_PENDING cap (0 = none): late pongs for it are STALE, not fabricated. */
    private int droppedNewest = 0;

    /** Server time (ms) at which the newest ACKED ping was sent. 0 = none yet. */
    public volatile long lastAckedSendMs = 0L;
    /** Wall time (ms) when the newest ack arrived. */
    public volatile long lastAckArrivalMs = 0L;
    /** Smoothed transaction round-trip time (ms). -1 = unknown. */
    public volatile double rttMs = -1.0;
    /** Bad-order / unknown-id events since the last evaluation (main thread consumes). */
    public volatile int badPongs = 0;
    public volatile String lastBadPong = "";
    /** True once the first pong ever arrived (client supports & answers transactions). */
    public volatile boolean active = false;

    public synchronized int onSend(long nowMs) {
        int id = nextId;
        nextId = nextId == Integer.MIN_VALUE + 1 ? -1 : nextId - 1;
        pending.addLast(new long[]{id, nowMs});
        while (pending.size() > MAX_PENDING) {
            long[] d = pending.pollFirst();
            if (d != null) droppedNewest = (int) d[0];
        }
        if (id == -1) droppedNewest = 0; // wrapped around: old history is meaningless
        return id;
    }

    /** The id the NEXT {@link #onSend} will use (anything written before that send is covered by its ack). */
    public synchronized int peekNextId() {
        return nextId;
    }

    /** Whether this id was produced by us (pongs with positive ids belong to the server/other plugins). */
    public static boolean ours(int id) {
        return id < 0;
    }

    public synchronized PongResult onPong(int id, long nowMs) {
        if (droppedNewest != 0 && id >= droppedNewest && id < 0) {
            return PongResult.STALE; // answer for a ping we stopped tracking (huge lag)
        }
        long[] head = pending.peekFirst();
        if (head == null) {
            return PongResult.UNKNOWN_ID;
        }
        if (head[0] == id) {
            pending.pollFirst();
            ack(head, nowMs);
            return PongResult.OK;
        }
        // not the head: either skipped ids (client answered out of order /
        // dropped some = disabler) or a fabricated id
        boolean found = false;
        for (long[] p : pending) {
            if (p[0] == id) {
                found = true;
                break;
            }
        }
        if (!found) return PongResult.UNKNOWN_ID;
        long[] p;
        while ((p = pending.pollFirst()) != null) {
            if (p[0] == id) {
                ack(p, nowMs);
                break;
            }
        }
        return PongResult.OUT_OF_ORDER;
    }

    private void ack(long[] p, long nowMs) {
        active = true;
        lastAckedSendMs = p[1];
        lastAckArrivalMs = nowMs;
        double sample = Math.max(0, nowMs - p[1]);
        rttMs = rttMs < 0 ? sample : rttMs * 0.8 + sample * 0.2;
    }

    /** Age (ms) of the oldest unanswered ping, 0 if none. */
    public synchronized long oldestPendingAge(long nowMs) {
        long[] head = pending.peekFirst();
        return head == null ? 0L : nowMs - head[1];
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized void reset() {
        pending.clear();
        lastAckedSendMs = 0L;
        lastAckArrivalMs = 0L;
        rttMs = -1.0;
        badPongs = 0;
        active = false;
    }
}
