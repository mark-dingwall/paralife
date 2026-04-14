package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seasonal cycle config (Phase 13 D-14).
 *
 * <p>Bound from application.yml under {@code paralife.simulation.seasons}.
 * Drives the global nutrient spawn multiplier via a sine wave so seasons
 * are centered on their natural landmarks:
 * <ul>
 *   <li>{@code tick 0} — mid-SPRING (rising toward abundance)</li>
 *   <li>{@code L/4} — mid-SUMMER (peak)</li>
 *   <li>{@code L/2} — mid-AUTUMN (falling)</li>
 *   <li>{@code 3L/4} — mid-WINTER (trough)</li>
 * </ul>
 *
 * @param yearLengthTicks number of ticks per full seasonal cycle (>= 8 so
 *                        {@code L/8} season-centering shift is a usable offset)
 * @param amplitude       amplitude of the sine swing (in [0, 1])
 */
@ConfigurationProperties(prefix = "paralife.simulation.seasons")
public record SeasonsConfig(
        int yearLengthTicks,
        double amplitude
) {
    /** Minimum so the {@code L/8} season-centering shift yields a non-zero integer offset. */
    public static final int MIN_YEAR_LENGTH_TICKS = 8;

    public SeasonsConfig {
        if (yearLengthTicks < MIN_YEAR_LENGTH_TICKS)
            throw new IllegalArgumentException(
                    "yearLengthTicks must be >= " + MIN_YEAR_LENGTH_TICKS + ": " + yearLengthTicks);
        if (amplitude < 0.0 || amplitude > 1.0)
            throw new IllegalArgumentException("amplitude must be in [0, 1]: " + amplitude);
    }

    public static SeasonsConfig defaults() {
        return new SeasonsConfig(200, 0.5);
    }
}
