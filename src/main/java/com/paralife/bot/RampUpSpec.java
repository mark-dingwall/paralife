package com.paralife.bot;

import java.util.concurrent.locks.LockSupport;

/**
 * Controls the ramp-up pattern when launching bots via {@link BotFleet}.
 *
 * <p>Three modes:
 * <ul>
 *   <li>{@link Instant} — all bots start in a tight loop (matches old {@code BotLauncher} behaviour)</li>
 *   <li>{@link Rate} — N bots per second; nanosecond-precision sleep prevents truncation above 1000/s</li>
 *   <li>{@link Wave} — bursts of {@code count} bots followed by {@code sleepMs} pauses</li>
 * </ul>
 *
 * <p>See {@code 18-CONTEXT.md D-03} for ramp-up rationale.
 */
public sealed interface RampUpSpec permits RampUpSpec.Instant, RampUpSpec.Rate, RampUpSpec.Wave {

    /** Called before launching bot at {@code botIndex}. May block the caller thread. */
    void awaitNext(int botIndex);

    record Instant() implements RampUpSpec {
        public void awaitNext(int botIndex) { /* no-op — all bots fire immediately */ }
    }

    /**
     * Rate-limited ramp: N bot starts per second.
     *
     * <p><b>Round 2 Codex MEDIUM amendment — nanosecond precision.</b>
     * The previous shape {@code 1000L / perSecond} integer-truncated to 0 above 1000/s,
     * so all delays became zero-sleep at high rates. The fix uses
     * {@code 1_000_000_000L / perSecond} nanoseconds explicitly, which gives
     * 500_000ns at 2000/s, 100_000ns at 10_000/s, etc.
     * {@link LockSupport#parkNanos(long)} handles sub-millisecond sleeps correctly
     * on virtual threads (unlike {@code Thread.sleep(0)}).
     */
    record Rate(int perSecond) implements RampUpSpec {
        public Rate {
            if (perSecond < 1) throw new IllegalArgumentException("perSecond must be >= 1");
        }

        public void awaitNext(int botIndex) {
            if (botIndex == 0) return; // First bot launches immediately.
            long nanos = 1_000_000_000L / perSecond;
            LockSupport.parkNanos(nanos);
        }
    }

    /**
     * Wave-based ramp: bursts of {@code count} bots followed by {@code sleepMs} pause.
     * Useful for synthetic bursty traffic shapes.
     */
    record Wave(int count, long sleepMs) implements RampUpSpec {
        public Wave {
            if (count < 1) throw new IllegalArgumentException("wave count must be >= 1");
            if (sleepMs < 0) throw new IllegalArgumentException("sleepMs must be >= 0");
        }

        public void awaitNext(int botIndex) {
            if (botIndex == 0) return; // First bot in the first wave launches immediately.
            if (botIndex % count == 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static RampUpSpec instant() { return new Instant(); }
    static RampUpSpec rate(int perSecond) { return new Rate(perSecond); }
    static RampUpSpec wave(int count, long sleepMs) { return new Wave(count, sleepMs); }
}
