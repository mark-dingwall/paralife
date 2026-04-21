package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fertility patch generation config (Phase 13 D-13).
 *
 * <p>Bound from application.yml under {@code paralife.simulation.fertility}.
 * Drives the initial world-init distribution of {@link com.paralife.world.Cell#nutrientLevel()}
 * patches that modulate per-cell nutrient spawn rates (D-12).
 *
 * @param patchCount      number of patches to generate at world init
 * @param patchMinRadius  minimum patch radius (inclusive, > 0)
 * @param patchMaxRadius  maximum patch radius (inclusive, >= patchMinRadius)
 * @param maxLevel        maximum nutrient level at the center of a patch
 */
@ConfigurationProperties(prefix = "paralife.simulation.fertility")
public record FertilityConfig(
        int patchCount,
        int patchMinRadius,
        int patchMaxRadius,
        int maxLevel,
        Long seed
) {
    public FertilityConfig {
        if (patchCount < 0)
            throw new IllegalArgumentException("patchCount must be >= 0: " + patchCount);
        if (patchMinRadius <= 0)
            throw new IllegalArgumentException("patchMinRadius must be > 0: " + patchMinRadius);
        if (patchMaxRadius < patchMinRadius)
            throw new IllegalArgumentException(
                    "patchMaxRadius must be >= patchMinRadius: min=" + patchMinRadius
                            + " max=" + patchMaxRadius);
        if (maxLevel <= 0)
            throw new IllegalArgumentException("maxLevel must be > 0: " + maxLevel);
        // seed may be null — that is valid (production path).
    }

    /**
     * Back-compat 4-arg convenience constructor — preserves pre-Phase-16 direct
     * instantiation in unit tests. Defaults seed to {@code null} (unseeded).
     */
    public FertilityConfig(int patchCount, int patchMinRadius, int patchMaxRadius, int maxLevel) {
        this(patchCount, patchMinRadius, patchMaxRadius, maxLevel, null);
    }

    public static FertilityConfig defaults() {
        return new FertilityConfig(20, 3, 8, 100, null);
    }
}
