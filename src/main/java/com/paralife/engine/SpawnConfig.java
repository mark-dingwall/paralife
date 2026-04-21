package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed for {@link com.paralife.websocket.WorldWebSocketHandler} initial-entity
 * placement RNG. Bound from application.yml under
 * {@code paralife.simulation.spawn}. {@code null} = unseeded (production
 * default).
 *
 * <p>Phase 16 Plan 01: new {@code @ConfigurationProperties} record. Replaces
 * the {@code @Value} pattern used in the prior planning round — matches the
 * project convention that typed config lives in {@code @ConfigurationProperties}
 * records (CLAUDE.md §Spring patterns). Auto-discovered by the existing
 * {@link org.springframework.boot.context.properties.ConfigurationPropertiesScan}
 * on {@code ParalifeApplication} — no manual registration required.
 *
 * @param seed master seed for spawn placement RNG. null = unseeded (production).
 */
@ConfigurationProperties(prefix = "paralife.simulation.spawn")
public record SpawnConfig(Long seed) {
    public static SpawnConfig defaults() {
        return new SpawnConfig(null);
    }
}
