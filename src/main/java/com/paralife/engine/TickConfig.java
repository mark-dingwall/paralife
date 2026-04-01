package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the tick engine.
 * Bound from application.yml under "paralife.tick".
 */
@ConfigurationProperties(prefix = "paralife.tick")
public record TickConfig(
        /** Milliseconds between ticks. */
        long intervalMs,
        /** Whether the tick engine starts automatically on boot. */
        boolean autoStart
) {
    public TickConfig {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Tick interval must be positive: " + intervalMs);
        }
    }

    public static TickConfig defaults() {
        return new TickConfig(500, true);
    }
}
