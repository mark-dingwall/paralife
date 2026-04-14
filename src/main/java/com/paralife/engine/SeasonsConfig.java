package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seasonal cycle config (Phase 13 D-14).
 *
 * <p>Bound from application.yml under {@code paralife.simulation.seasons}.
 * Drives the global nutrient spawn multiplier via a cosine wave so that
 * {@code tick 0} lands on the spring peak (cosine, not sine — see review
 * changes in 13-03-PLAN).
 *
 * @param yearLengthTicks number of ticks per full seasonal cycle (> 0)
 * @param amplitude       amplitude of the cosine swing (in [0, 1])
 */
@ConfigurationProperties(prefix = "paralife.simulation.seasons")
public record SeasonsConfig(
        int yearLengthTicks,
        double amplitude
) {
    public SeasonsConfig {
        if (yearLengthTicks <= 0)
            throw new IllegalArgumentException("yearLengthTicks must be > 0: " + yearLengthTicks);
        if (amplitude < 0.0 || amplitude > 1.0)
            throw new IllegalArgumentException("amplitude must be in [0, 1]: " + amplitude);
    }

    public static SeasonsConfig defaults() {
        return new SeasonsConfig(200, 0.5);
    }
}
