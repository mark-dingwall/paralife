package com.paralife.engine;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonTrackerTest {

    private SeasonTracker tracker(int yearLength, double amplitude) {
        return new SeasonTracker(new SeasonsConfig(yearLength, amplitude));
    }

    // ── Multiplier math (sin, tick 0 = mid-SPRING, L/4 = SUMMER peak) ─────

    @Test
    void tickZeroIsMidSpringMultiplierOne() {
        // sin(0) = 0 → multiplier = 1.0
        double m = tracker(200, 0.5).getSeasonalMultiplier(0);
        assertThat(m).isCloseTo(1.0, Offset.offset(1e-9));
    }

    @Test
    void quarterYearIsSummerPeak() {
        // sin(PI/2) = 1 → multiplier = 1 + amplitude
        double m = tracker(200, 0.5).getSeasonalMultiplier(50);
        assertThat(m).isCloseTo(1.5, Offset.offset(1e-9));
    }

    @Test
    void halfYearIsMidAutumnMultiplierOne() {
        double m = tracker(200, 0.5).getSeasonalMultiplier(100);
        assertThat(m).isCloseTo(1.0, Offset.offset(1e-9));
    }

    @Test
    void threeQuarterYearIsWinterTrough() {
        double m = tracker(200, 0.5).getSeasonalMultiplier(150);
        assertThat(m).isCloseTo(0.5, Offset.offset(1e-9));
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
        assertThat(t.getSeasonalMultiplier(200))
                .isCloseTo(t.getSeasonalMultiplier(0), Offset.offset(1e-9));
    }

    @Test
    void zeroAmplitudeMeansNoSwing() {
        SeasonTracker t = tracker(200, 0.0);
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

    // ── Season enum cycling (seasons centered on landmarks) ──────────────

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
        assertThat(tracker(200, 0.5).getSeason(200)).isEqualTo(SeasonTracker.Season.SPRING);
        assertThat(tracker(200, 0.5).getSeason(250)).isEqualTo(SeasonTracker.Season.SUMMER);
    }

    @Test
    void seasonBoundariesCenteredOnLandmarks() {
        // Seasons span ±L/8 around each landmark. For L=200, L/8=25.
        SeasonTracker t = tracker(200, 0.5);
        // SPRING: [-25, 25) wrapped → [175, 200) ∪ [0, 25)
        assertThat(t.getSeason(175)).isEqualTo(SeasonTracker.Season.SPRING);
        assertThat(t.getSeason(24)).isEqualTo(SeasonTracker.Season.SPRING);
        // SUMMER: [25, 75)
        assertThat(t.getSeason(25)).isEqualTo(SeasonTracker.Season.SUMMER);
        assertThat(t.getSeason(74)).isEqualTo(SeasonTracker.Season.SUMMER);
        // AUTUMN: [75, 125)
        assertThat(t.getSeason(75)).isEqualTo(SeasonTracker.Season.AUTUMN);
        assertThat(t.getSeason(124)).isEqualTo(SeasonTracker.Season.AUTUMN);
        // WINTER: [125, 175)
        assertThat(t.getSeason(125)).isEqualTo(SeasonTracker.Season.WINTER);
        assertThat(t.getSeason(174)).isEqualTo(SeasonTracker.Season.WINTER);
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
    void seasonsConfigRejectsYearLengthBelowMinimum() {
        // Minimum is 8 so L/8 >= 1 — prevents divide-by-zero in season index math.
        for (int invalid : new int[] {1, 2, 3, 7}) {
            try {
                new SeasonsConfig(invalid, 0.5);
                org.junit.jupiter.api.Assertions.fail("expected IAE for " + invalid);
            } catch (IllegalArgumentException expected) {
                assertThat(expected.getMessage()).contains("yearLengthTicks");
            }
        }
    }

    @Test
    void seasonsConfigAcceptsMinimumYearLength() {
        // yearLengthTicks=8 must be usable. Exercises getSeason across all quarters.
        SeasonTracker t = tracker(8, 0.5);
        assertThat(t.getSeason(0)).isEqualTo(SeasonTracker.Season.SPRING);
        assertThat(t.getSeason(2)).isEqualTo(SeasonTracker.Season.SUMMER);
        assertThat(t.getSeason(4)).isEqualTo(SeasonTracker.Season.AUTUMN);
        assertThat(t.getSeason(6)).isEqualTo(SeasonTracker.Season.WINTER);
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
