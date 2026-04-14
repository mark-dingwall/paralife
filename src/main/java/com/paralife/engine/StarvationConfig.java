package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global starvation multipliers (D-11). Per-type starvation thresholds live on
 * {@link MetabolicProfile.TypeProfile}; these multipliers are applied
 * identically to all starving entities, scaled by their computed intensity.
 *
 * <p>Bound from application.yml under {@code paralife.simulation.starvation}.
 *
 * @param maxAttackBoost           max attack multiplier at starvation floor (e.g. 0.5 → +50%)
 * @param maxNutrientBoost         max nutrient-gain multiplier at starvation floor
 * @param maxDamageVulnerability   max incoming-damage multiplier at starvation floor
 */
@ConfigurationProperties(prefix = "paralife.simulation.starvation")
public record StarvationConfig(
        double maxAttackBoost,
        double maxNutrientBoost,
        double maxDamageVulnerability
) {
    public StarvationConfig {
        if (maxAttackBoost < 0)
            throw new IllegalArgumentException("maxAttackBoost must be >= 0: " + maxAttackBoost);
        if (maxNutrientBoost < 0)
            throw new IllegalArgumentException("maxNutrientBoost must be >= 0: " + maxNutrientBoost);
        if (maxDamageVulnerability < 0)
            throw new IllegalArgumentException(
                    "maxDamageVulnerability must be >= 0: " + maxDamageVulnerability);
    }

    public static StarvationConfig defaults() {
        return new StarvationConfig(0.5, 0.5, 0.5);
    }
}
