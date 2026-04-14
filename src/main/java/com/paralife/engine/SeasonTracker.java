package com.paralife.engine;

import org.springframework.stereotype.Component;

/**
 * Stateless seasonal multiplier computation (Phase 13 D-14, D-15).
 *
 * <p>Uses {@link Math#cos} (not sin — see review changes in 13-03-PLAN) so
 * {@code tick 0} coincides with the spring peak where the multiplier equals
 * {@code 1 + amplitude}. CONTEXT.md D-14 describes the shape as a sine wave;
 * cosine is the phase-corrected version for intuitive season labels.
 *
 * <p>Formula:
 * <pre>
 *   multiplier = 1 + amplitude * cos(2 * PI * tick / yearLength)
 * </pre>
 *
 * <p>Reference values at yearLength=200, amplitude=0.5:
 * <table>
 *   <tr><th>tick</th><th>cos</th><th>multiplier</th><th>season</th></tr>
 *   <tr><td>0</td><td>1</td><td>1.5</td><td>SPRING peak</td></tr>
 *   <tr><td>50</td><td>0</td><td>1.0</td><td>SUMMER equinox</td></tr>
 *   <tr><td>100</td><td>-1</td><td>0.5</td><td>AUTUMN trough</td></tr>
 *   <tr><td>150</td><td>0</td><td>1.0</td><td>WINTER equinox</td></tr>
 * </table>
 */
@Component
public class SeasonTracker {

    /** Four seasons, one per quarter of the year cycle (D-15). */
    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    private final SeasonsConfig config;

    public SeasonTracker(SeasonsConfig config) {
        this.config = config;
    }

    /**
     * Global nutrient-spawn multiplier for the given tick. Range:
     * {@code [1 - amplitude, 1 + amplitude]}.
     */
    public double getSeasonalMultiplier(long tick) {
        return 1.0 + config.amplitude()
                * Math.cos(2.0 * Math.PI * tick / config.yearLengthTicks());
    }

    /**
     * Current season enum for the given tick. Year is divided into four
     * equal quarters: SPRING at cosine peak, AUTUMN at cosine trough.
     */
    public Season getSeason(long tick) {
        int yearLength = config.yearLengthTicks();
        long position = Math.floorMod(tick, (long) yearLength);
        int quarter = (int) (position / (yearLength / 4));
        // Clamp for edge case when yearLength isn't divisible by 4 and position
        // exceeds the last quarter boundary — still WINTER.
        return Season.values()[Math.min(quarter, 3)];
    }

    public SeasonsConfig getConfig() {
        return config;
    }
}
