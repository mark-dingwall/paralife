package com.paralife.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonTrackerTest {

    private SeasonTracker tracker(int yearLength, double amplitude) {
        return new SeasonTracker(new SeasonsConfig(yearLength, amplitude));
    }

    // ── Multiplier math (cosine, tick 0 = spring peak) ────────────

    @Test
    void tickZeroIsSpringPeak() {
        // cos(0) = 1 → multiplier = 1 + 0.5 = 1.5
        double m = tracker(200, 0.5).getSeasonalMultiplier(0);
        assertThat(m).isEqualTo(1.5);
    }

    @Test
    void halfYearIsAutumnTrough() {
        // tick = yearLength/2 → cos(PI) = -1 → multiplier = 1 - 0.5 = 0.5
        double m = tracker(200, 0.5).getSeasonalMultiplier(100);
        assertThat(m).isEqualTo(0.5);
    }

    @Test
    void quarterYearIsSummerEquinox() {
        // tick = yearLength/4 → cos(PI/2) = 0 → multiplier = 1.0
        double m = tracker(200, 0.5).getSeasonalMultiplier(50);
        assertThat(m).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void threeQuarterYearIsWinterEquinox() {
        double m = tracker(200, 0.5).getSeasonalMultiplier(150);
        assertThat(m).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void multiplierAlwaysWithinAmplitudeBand() {
        SeasonTracker t = tracker(200, 0.5);
        for (int tick = 0; tick < 200; tick++) {
            double m = t.getSeasonalMultiplier(tick);
            assertThat(m).isBetween(0.5, 1.5);
        }
    }

    @Test
    void yearWrapsAround() {
        SeasonTracker t = tracker(200, 0.5);
        // tick=200 is identical to tick=0
        assertThat(t.getSeasonalMultiplier(200)).isEqualTo(t.getSeasonalMultiplier(0));
    }

    @Test
    void zeroAmplitudeMeansNoSwing() {
        SeasonTracker t = tracker(200, 0.0);
        // cos(x)*0 = 0 → multiplier = 1.0 always
        assertThat(t.getSeasonalMultiplier(0)).isEqualTo(1.0);
        assertThat(t.getSeasonalMultiplier(50)).isEqualTo(1.0);
        assertThat(t.getSeasonalMultiplier(100)).isEqualTo(1.0);
    }

    @Test
    void statelessReturnsSameResultForSameTick() {
        SeasonTracker t = tracker(200, 0.5);
        double a = t.getSeasonalMultiplier(73);
        double b = t.getSeasonalMultiplier(73);
        double c = t.getSeasonalMultiplier(73);
        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    // ── Season enum cycling ────────────────────────────────────────

    @Test
    void tickZeroIsSpring() {
        assertThat(tracker(200, 0.5).getSeason(0)).isEqualTo(SeasonTracker.Season.SPRING);
    }

    @Test
    void quarterYearIsSummer() {
        assertThat(tracker(200, 0.5).getSeason(50)).isEqualTo(SeasonTracker.Season.SUMMER);
    }

    @Test
    void halfYearIsAutumn() {
        assertThat(tracker(200, 0.5).getSeason(100)).isEqualTo(SeasonTracker.Season.AUTUMN);
    }

    @Test
    void threeQuarterYearIsWinter() {
        assertThat(tracker(200, 0.5).getSeason(150)).isEqualTo(SeasonTracker.Season.WINTER);
    }

    @Test
    void seasonWrapsAtYearBoundary() {
        // tick=200 should cycle back to SPRING
        assertThat(tracker(200, 0.5).getSeason(200)).isEqualTo(SeasonTracker.Season.SPRING);
        assertThat(tracker(200, 0.5).getSeason(250)).isEqualTo(SeasonTracker.Season.SUMMER);
    }

    @Test
    void edgeOfSpring() {
        SeasonTracker t = tracker(200, 0.5);
        assertThat(t.getSeason(49)).isEqualTo(SeasonTracker.Season.SPRING);
        assertThat(t.getSeason(99)).isEqualTo(SeasonTracker.Season.SUMMER);
        assertThat(t.getSeason(149)).isEqualTo(SeasonTracker.Season.AUTUMN);
        assertThat(t.getSeason(199)).isEqualTo(SeasonTracker.Season.WINTER);
    }

    // ── Config validation ──────────────────────────────────────────

    @Test
    void seasonsConfigRejectsZeroYearLength() {
        try {
            new SeasonsConfig(0, 0.5);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("yearLengthTicks");
        }
    }

    @Test
    void seasonsConfigRejectsNegativeAmplitude() {
        try {
            new SeasonsConfig(200, -0.1);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("amplitude");
        }
    }

    @Test
    void seasonsConfigRejectsAmplitudeAboveOne() {
        try {
            new SeasonsConfig(200, 1.1);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("amplitude");
        }
    }
}
