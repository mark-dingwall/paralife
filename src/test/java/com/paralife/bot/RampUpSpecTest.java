package com.paralife.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RampUpSpec}.
 */
class RampUpSpecTest {

    @Test
    void instant_noSleep_forAnyIndex() {
        RampUpSpec spec = RampUpSpec.instant();
        // Just verify it completes immediately for any index.
        long start = System.nanoTime();
        spec.awaitNext(0);
        spec.awaitNext(1);
        spec.awaitNext(100);
        long elapsed = System.nanoTime() - start;
        // Should complete in well under 1ms.
        assertThat(elapsed).isLessThan(1_000_000L);
    }

    @Test
    void rate2000_doesNotCollapseToZero() {
        // Round 2 Codex MEDIUM: 1000/perSecond truncates to 0 above 1000/s.
        // Nanosecond-precision fix: 1_000_000_000L / 2000 = 500_000ns per bot.
        RampUpSpec spec = RampUpSpec.rate(2000);
        spec.awaitNext(0); // index 0 is no-op

        long start = System.nanoTime();
        spec.awaitNext(1);
        long elapsed = System.nanoTime() - start;

        // At 2000/s nominal interval is 500_000ns. Allow 80% tolerance for VT scheduling.
        assertThat(elapsed).as("rate(2000).awaitNext(1) must sleep at least 400_000ns")
                .isGreaterThanOrEqualTo(400_000L);
    }

    @Test
    void rate_indexZero_isNoOp() {
        RampUpSpec spec = RampUpSpec.rate(1);
        long start = System.nanoTime();
        spec.awaitNext(0);
        long elapsed = System.nanoTime() - start;
        // Index 0 should not sleep at all.
        assertThat(elapsed).isLessThan(1_000_000L);
    }

    @Test
    void wave_indexZero_isNoOp() {
        RampUpSpec spec = RampUpSpec.wave(5, 500L);
        long start = System.nanoTime();
        spec.awaitNext(0);
        long elapsed = System.nanoTime() - start;
        assertThat(elapsed).isLessThan(1_000_000L);
    }

    @Test
    void wave_atWaveBoundary_sleeps() {
        // Wave boundary at index 5 with count=5 and 200ms sleep.
        RampUpSpec spec = RampUpSpec.wave(5, 200L);
        long start = System.currentTimeMillis();
        spec.awaitNext(5); // 5 % 5 == 0 → sleep
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(200L);
    }
}
