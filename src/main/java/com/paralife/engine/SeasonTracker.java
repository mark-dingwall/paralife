package com.paralife.engine;

import org.springframework.stereotype.Component;

/**
 * Stateless seasonal multiplier computation (Phase 13 D-14, D-15).
 *
 * <p>Uses {@link Math#sin} so season landmarks land on the expected phase points:
 * <ul>
 *   <li>{@code tick 0} — sin=0 ascending, mid-SPRING (multiplier 1.0)</li>
 *   <li>{@code L/4} — sin=+1, mid-SUMMER peak (multiplier 1 + amplitude)</li>
 *   <li>{@code L/2} — sin=0 descending, mid-AUTUMN (multiplier 1.0)</li>
 *   <li>{@code 3L/4} — sin=-1, mid-WINTER trough (multiplier 1 - amplitude)</li>
 * </ul>
 *
 * <p>Formula:
 * <pre>
 *   multiplier = 1 + amplitude * sin(2 * PI * tick / yearLength)
 * </pre>
 *
 * <p>Season enum indexing uses an {@code L/8} shift so each season is
 * centered on its landmark rather than starting at it.
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
                * Math.sin(2.0 * Math.PI * tick / config.yearLengthTicks());
    }

    /**
     * Current season enum for the given tick. Seasons span a quarter of the
     * year centered on their landmarks, produced by the {@code +L/8} shift.
     */
    public Season getSeason(long tick) {
        int yearLength = config.yearLengthTicks();
        long position = Math.floorMod(tick, (long) yearLength);
        long shifted = position + yearLength / 8L;
        int quarter = (int) Math.floorMod(shifted / (yearLength / 4L), 4L);
        return Season.values()[quarter];
    }

    public SeasonsConfig getConfig() {
        return config;
    }
}
