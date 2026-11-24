package dev.weposs.wepac.packet;

/**
 * Balance-based Timer detection (pure, unit-tested).
 *
 * Every client tick produces at most ONE flying packet, and a client tick is
 * 50ms of client time. So each packet "spends" 50ms and real time "earns" it
 * back: {@code balance += 50ms per packet - elapsed real time}. A vanilla
 * client can never stay ahead of real time; a timer client gains
 * (speed - 1) * 1000 ms per second - a 1.05x timer gains 50ms/s, invisible to
 * a packets-per-second threshold but unbounded here.
 *
 * Why it is safe:
 *  - network jitter only delays packets: a burst after a delay pays back the
 *    deficit the delay created, it never pushes the balance above ~jitter;
 *  - idle clients send fewer packets (negative balance). The negative side is
 *    CLAMPED so idling cannot bank credit for a later timer burst, and the
 *    clamp exceeds what the client's own lag catch-up (max 10 ticks = 500ms)
 *    could legitimately produce only by {@code 500 - |clamp|} ms, which stays
 *    well under the flag threshold;
 *  - teleports/respawns/joins reset it (the server's teleport makes the
 *    client send an extra confirmation move).
 */
public final class TimerBalance {

    public static final long TICK_NANOS = 50_000_000L;

    private static final long IDLE_HEARTBEAT_NANOS = 1_100_000_000L;
    private static final long MAX_GAP_CREDIT_NANOS = 10_000_000_000L;

    private long gapCreditNanos = 0L;
    private long gapCreditUntil = 0L;
    private long lastNanos = 0L;
    private long balanceNanos = 0L;
    private volatile long peakNanos = 0L;

    /** Netty thread: one flying packet arrived at {@code nowNanos}. */
    public synchronized void onPacket(long nowNanos, long clampNanos) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - lastNanos;
        lastNanos = nowNanos;
        balanceNanos += TICK_NANOS - elapsed;
        // A single silence longer than the vanilla idle heartbeat (1 status
        // packet per second) is a network/client stall: the burst that follows
        // will repay exactly that silence, so for the next 5s the floor is
        // lowered to the full gap instead of the idle clamp. (A deliberate
        // silence to bank credit is capped by the Blink check's timeout.)
        if (elapsed > IDLE_HEARTBEAT_NANOS) {
            gapCreditNanos = Math.max(gapCreditNanos, Math.min(elapsed, MAX_GAP_CREDIT_NANOS));
            gapCreditUntil = nowNanos + 5_000_000_000L;
        }
        if (nowNanos > gapCreditUntil) gapCreditNanos = 0L;
        long floor = -Math.max(clampNanos, gapCreditNanos);
        if (balanceNanos < floor) balanceNanos = floor;
        if (balanceNanos > peakNanos) peakNanos = balanceNanos;
    }

    /** Highest balance (ms) since the last reset/consume. */
    public long peakMs() {
        return peakNanos / 1_000_000L;
    }

    /** After a flag: forgive the gained time so one violation flags once. */
    public synchronized void consume() {
        balanceNanos = 0L;
        peakNanos = 0L;
    }

    /** Teleport / respawn / join: the stream legitimately restarts. */
    public synchronized void reset() {
        lastNanos = 0L;
        balanceNanos = 0L;
        peakNanos = 0L;
        gapCreditNanos = 0L;
        gapCreditUntil = 0L;
    }

    public synchronized long balanceMs() {
        return balanceNanos / 1_000_000L;
    }
}
