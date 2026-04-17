package com.paralife.engine;

import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Plan 14-02 seasonal Poisson trigger tests (D-27). The toxin lambda sine-scales
 * across the AUTUMN peak season and flattens to {@code offSeasonLambda} elsewhere.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        // Lock the seasonal landmarks for deterministic tick-number ↔ season math.
        "paralife.simulation.seasons.year-length-ticks=200",
        "paralife.simulation.seasons.amplitude=0.5",
        "paralife.simulation.events.toxin.peak-season=AUTUMN",
        "paralife.simulation.events.toxin.peak-lambda=0.03",
        "paralife.simulation.events.toxin.off-season-lambda=0.005"
})
class SeasonalPoissonTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine env;
    @Autowired SeasonTracker seasons;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        env.resetToxinStateForTest();
    }

    @Test
    void lambdaFlattensToOffSeasonInWinter() {
        // With year-length 200 and SeasonTracker's L/8 shift, mid-WINTER lands at
        // tick 3*200/4 = 150. Verify it's WINTER before asserting lambda.
        long winterTick = 150L;
        assertThat(seasons.getSeason(winterTick))
                .as("sanity: tick 150 resolves to WINTER")
                .isEqualTo(SeasonTracker.Season.WINTER);

        double lambda = env.seasonalToxinLambda(winterTick);
        assertThat(lambda).isCloseTo(0.005, within(1e-9));
    }

    @Test
    void lambdaPeaksAtMidAutumnAtPeakLambda() {
        // Mid-AUTUMN lands at tick L/2 = 100 (sin=0 descending) per SeasonTracker
        // documentation, but with the +L/8 shift the season enum maps that tick to
        // AUTUMN. The seasonal multiplier is 1.0 at tick 100 (sin(PI)=0), so the
        // fraction (mult - (1-amp)) / (2*amp) = (1 - 0.5) / 1 = 0.5. Lambda sits
        // halfway between off and peak: 0.005 + 0.5 * (0.03 - 0.005) = 0.0175.
        long autumnMid = 100L;
        assertThat(seasons.getSeason(autumnMid))
                .as("sanity: tick 100 resolves to AUTUMN")
                .isEqualTo(SeasonTracker.Season.AUTUMN);

        double lambda = env.seasonalToxinLambda(autumnMid);
        assertThat(lambda).isCloseTo(0.0175, within(1e-6));
    }

    @Test
    void lambdaIsStrictlyWithinPeakAndOffBoundsDuringAutumn() {
        // At tick 75 — the SeasonTracker's +L/8 shift places this early-AUTUMN;
        // lambda should be in [offSeasonLambda, peakLambda].
        long tick = 75L;
        double lambda = env.seasonalToxinLambda(tick);
        assertThat(lambda).isBetween(0.005, 0.03);
    }
}
