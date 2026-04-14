package com.paralife.engine;

import com.paralife.world.Entity;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-type metabolic profiles (D-02, D-03).
 *
 * <p>Bound from application.yml under {@code paralife.simulation.types}. Each
 * {@link Entity.ParticleType} has its own {@link TypeProfile} with 11 knobs
 * governing energy dynamics, combat, reproduction, and starvation response.
 *
 * <p>Using the {@code paralife.simulation.types} sub-prefix (rather than the
 * flat {@code paralife.simulation} prefix) avoids collision with
 * {@link SimulationConfig} which binds different fields under the same root.
 *
 * <p>Archetypes (D-03):
 * <ul>
 *   <li><b>CATALYST</b> — fast hungry predator (high decay, high attack, costly reproduction)</li>
 *   <li><b>MEMBRANE</b> — efficient defensive grazer (low decay, high nutrient gain, durable)</li>
 *   <li><b>SPORE</b> — r-strategist breeder (cheap + short cooldown + bonus offspring + range 2)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "paralife.simulation.types")
public record MetabolicProfile(
        TypeProfile catalyst,
        TypeProfile membrane,
        TypeProfile spore
) {

    /**
     * Per-type metabolic profile (D-02). 11 knobs governing how a particle
     * burns, fights, feeds, breeds, and starves.
     *
     * @param maxEnergy              energy capacity
     * @param decayPerTick           base metabolic burn rate
     * @param combatEnergyTransfer   energy gained per combat win
     * @param attackPower            damage dealt in combat
     * @param nutrientConsumeEnergy  energy gained per nutrient consumed
     * @param reproduceEnergyCost    energy cost to reproduce
     * @param reproduceCooldown      ticks between reproductions
     * @param bonusOffspringChance   probability of free extra child (r-strategist)
     * @param reproduceRange         distance (in direction steps) for offspring spawn
     * @param starvationThreshold    % of max energy at which starvation begins
     * @param starvationFloor        % of max energy at which starvation effects peak
     */
    public record TypeProfile(
            int maxEnergy,
            int decayPerTick,
            int combatEnergyTransfer,
            int attackPower,
            int nutrientConsumeEnergy,
            int reproduceEnergyCost,
            int reproduceCooldown,
            double bonusOffspringChance,
            int reproduceRange,
            int starvationThreshold,
            int starvationFloor
    ) {
        public TypeProfile {
            if (maxEnergy <= 0)
                throw new IllegalArgumentException("maxEnergy must be > 0: " + maxEnergy);
            if (decayPerTick < 0)
                throw new IllegalArgumentException("decayPerTick must be >= 0: " + decayPerTick);
            if (combatEnergyTransfer < 0)
                throw new IllegalArgumentException("combatEnergyTransfer must be >= 0: " + combatEnergyTransfer);
            if (attackPower < 0)
                throw new IllegalArgumentException("attackPower must be >= 0: " + attackPower);
            if (nutrientConsumeEnergy < 0)
                throw new IllegalArgumentException("nutrientConsumeEnergy must be >= 0: " + nutrientConsumeEnergy);
            if (reproduceEnergyCost < 0)
                throw new IllegalArgumentException("reproduceEnergyCost must be >= 0: " + reproduceEnergyCost);
            if (reproduceCooldown < 0)
                throw new IllegalArgumentException("reproduceCooldown must be >= 0: " + reproduceCooldown);
            if (reproduceRange < 1)
                throw new IllegalArgumentException("reproduceRange must be >= 1: " + reproduceRange);
            if (bonusOffspringChance < 0.0 || bonusOffspringChance > 1.0)
                throw new IllegalArgumentException(
                        "bonusOffspringChance must be in [0.0, 1.0]: " + bonusOffspringChance);
            if (starvationThreshold < 0 || starvationThreshold > 100)
                throw new IllegalArgumentException(
                        "starvationThreshold must be in [0, 100]: " + starvationThreshold);
            if (starvationFloor < 0 || starvationFloor > starvationThreshold)
                throw new IllegalArgumentException(
                        "starvationFloor must be in [0, starvationThreshold]: floor="
                                + starvationFloor + " threshold=" + starvationThreshold);
        }

        /**
         * Per-type child start energy: half of maxEnergy. Replaces the legacy
         * static {@code CHILD_START_ENERGY = 20} so children scale with parent type.
         */
        public int childStartEnergy() {
            return maxEnergy / 2;
        }
    }

    public MetabolicProfile {
        if (catalyst == null) throw new IllegalArgumentException("catalyst profile missing");
        if (membrane == null) throw new IllegalArgumentException("membrane profile missing");
        if (spore == null) throw new IllegalArgumentException("spore profile missing");
    }

    /**
     * Look up the {@link TypeProfile} for a {@link Entity.ParticleType}.
     */
    public TypeProfile forType(Entity.ParticleType type) {
        return switch (type) {
            case CATALYST -> catalyst;
            case MEMBRANE -> membrane;
            case SPORE -> spore;
        };
    }

    /**
     * Default profiles matching the type archetypes (D-03). Used by tests and
     * as a reference for {@code application.yml} values.
     */
    public static MetabolicProfile defaults() {
        return new MetabolicProfile(
                // CATALYST — fast hungry predator
                new TypeProfile(80, 3, 15, 15, 3, 40, 10, 0.0, 1, 30, 10),
                // MEMBRANE — efficient defensive grazer
                new TypeProfile(120, 1, 5, 5, 8, 35, 8, 0.0, 1, 25, 8),
                // SPORE — r-strategist breeder
                new TypeProfile(60, 2, 8, 8, 5, 20, 5, 0.25, 2, 35, 12)
        );
    }
}
