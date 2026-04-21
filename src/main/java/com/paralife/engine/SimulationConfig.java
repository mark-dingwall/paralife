package com.paralife.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the simulation physics engine.
 * Bound from application.yml under "paralife.simulation".
 *
 * <p>Phase 16 Plan 01: added nullable {@code seed} + {@code actionSeed} fields
 * for deterministic-RNG injection. Both nullable — {@code null} = unseeded
 * (production default). Tests bind explicit values via {@code @TestPropertySource}
 * or {@code @DynamicPropertySource}.
 */
@ConfigurationProperties(prefix = "paralife.simulation")
public record SimulationConfig(
        /** Energy lost per particle per tick. */
        int energyDecayPerTick,
        /** Energy transferred from prey to predator on combat. */
        int combatEnergyTransfer,
        /** Probability (0.0–1.0) that an empty cell spawns a nutrient each tick. */
        double nutrientSpawnProbability,
        /** Energy gained when a particle consumes a nutrient. */
        int nutrientConsumeEnergy,
        /** Whether the simulation engine processes ticks. */
        boolean enabled,
        /** Number of neighboring particles to trigger overcrowding penalty. */
        int overcrowdingThreshold,
        /** Energy lost per tick when a particle is overcrowded. */
        int overcrowdingEnergyPenalty,
        /** Master seed for SimulationEngine RNG. null = unseeded (production). */
        Long seed,
        /** Master seed for ActionResolver RNG. null = unseeded (production). */
        Long actionSeed
) {
    public SimulationConfig {
        if (energyDecayPerTick < 0) throw new IllegalArgumentException("energyDecayPerTick must be >= 0: " + energyDecayPerTick);
        if (combatEnergyTransfer < 0) throw new IllegalArgumentException("combatEnergyTransfer must be >= 0: " + combatEnergyTransfer);
        if (nutrientSpawnProbability < 0 || nutrientSpawnProbability > 1) throw new IllegalArgumentException("nutrientSpawnProbability must be in [0,1]: " + nutrientSpawnProbability);
        if (nutrientConsumeEnergy < 0) throw new IllegalArgumentException("nutrientConsumeEnergy must be >= 0: " + nutrientConsumeEnergy);
        if (overcrowdingThreshold < 0 || overcrowdingThreshold > 8) throw new IllegalArgumentException("overcrowdingThreshold must be in [0,8]: " + overcrowdingThreshold);
        if (overcrowdingEnergyPenalty < 0) throw new IllegalArgumentException("overcrowdingEnergyPenalty must be >= 0: " + overcrowdingEnergyPenalty);
        // seed / actionSeed may be null — that is valid (production path).
    }

    /**
     * Back-compat 7-arg convenience constructor — preserves pre-Phase-16 direct
     * instantiation in unit tests. Defaults the new seed fields to {@code null}.
     */
    public SimulationConfig(int energyDecayPerTick, int combatEnergyTransfer,
                            double nutrientSpawnProbability, int nutrientConsumeEnergy,
                            boolean enabled, int overcrowdingThreshold,
                            int overcrowdingEnergyPenalty) {
        this(energyDecayPerTick, combatEnergyTransfer, nutrientSpawnProbability,
                nutrientConsumeEnergy, enabled, overcrowdingThreshold,
                overcrowdingEnergyPenalty, null, null);
    }

    public static SimulationConfig defaults() {
        return new SimulationConfig(1, 10, 0.001, 5, true, 6, 2, null, null);
    }
}
