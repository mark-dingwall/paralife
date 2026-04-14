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

    /**
     * Compute starvation intensity (D-09). Returns 0.0 if not starving, rising
     * linearly from the threshold toward the floor where it reaches 1.0.
     *
     * <pre>
     * starvationIntensity = (threshold - currentPercent) / (threshold - floorPercent)
     * </pre>
     * clamped to {@code [0.0, 1.0]}.
     *
     * <p><b>Why a static helper on StarvationConfig?</b> Plan 02 computes the
     * intensity directly from current energy at the point of application
     * (combat damage, nutrient boost) rather than reading Cell.FLAG_STARVING.
     * This avoids the "stale flag" problem where an entity recovers energy
     * between flag-set (end of previous decay) and combat resolution. The flag
     * remains for observability (cell display + perception) but is not used to
     * drive gameplay modifiers.
     */
    public static double computeIntensity(int energy, int maxEnergy,
                                          int thresholdPercent, int floorPercent) {
        if (maxEnergy <= 0) return 0.0;
        double currentPercent = (double) energy / maxEnergy * 100.0;
        if (currentPercent >= thresholdPercent) return 0.0;
        if (thresholdPercent == floorPercent) return 1.0; // avoid division by zero
        double intensity = (thresholdPercent - currentPercent)
                / (double) (thresholdPercent - floorPercent);
        return Math.clamp(intensity, 0.0, 1.0);
    }
}
