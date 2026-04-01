package com.paralife.world;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the world grid dimensions.
 * Bound from application.yml under "paralife.world".
 */
@ConfigurationProperties(prefix = "paralife.world")
public record GridConfig(int width, int height) {

    public GridConfig {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Grid dimensions must be positive: width=%d, height=%d".formatted(width, height));
        }
    }

    /**
     * Default grid size if not configured.
     */
    public static GridConfig defaults() {
        return new GridConfig(256, 256);
    }
}
