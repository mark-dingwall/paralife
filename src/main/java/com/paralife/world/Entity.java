package com.paralife.world;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Sealed entity hierarchy for all things that occupy grid cells.
 *
 * <ul>
 *   <li>{@link Particle} — active agent with type (RPS dynamic), energy, and an owner ID</li>
 *   <li>{@link Rock} — impassable terrain</li>
 *   <li>{@link Nutrient} — passive energy source consumed by particles</li>
 * </ul>
 *
 * Entities are immutable records — mutation produces a new instance.
 * Null occupant in a {@link Cell} means the cell is empty.
 */
public sealed interface Entity permits Entity.Particle, Entity.Rock, Entity.Nutrient, Entity.BondedPair, Entity.CompositeMember {

    /** Unique identifier for this entity instance. */
    String id();

    // ── Roles (siphonophore zooid specialization) ───────────────────

    /**
     * Specialization roles for composite organism members (D-06).
     * Each member of a composite fills exactly one role.
     */
    enum Role { LOCOMOTOR, FEEDER, ATTACKER, DEFENDER, REPRODUCER, SENSOR }

    // ── Active agents ──────────────────────────────────────────────

    /**
     * Rock-paper-scissors types for combat:
     * <pre>
     *   CATALYST  beats SPORE
     *   SPORE     beats MEMBRANE
     *   MEMBRANE  beats CATALYST
     * </pre>
     */
    enum ParticleType {
        CATALYST, MEMBRANE, SPORE;

        /** Returns the type this one defeats. */
        public ParticleType prey() {
            return switch (this) {
                case CATALYST -> SPORE;
                case SPORE -> MEMBRANE;
                case MEMBRANE -> CATALYST;
            };
        }

        /** Returns the type that defeats this one. */
        public ParticleType predator() {
            return switch (this) {
                case CATALYST -> MEMBRANE;
                case SPORE -> CATALYST;
                case MEMBRANE -> SPORE;
            };
        }
    }

    /**
     * An active entity controlled by a bot client.
     *
     * @param id          unique entity identifier
     * @param type        RPS particle type
     * @param energy      current energy (0 = dead, removed next tick)
     * @param maxEnergy   energy cap for this particle
     */
    record Particle(String id, ParticleType type, int energy, int maxEnergy) implements Entity {

        public static final int DEFAULT_MAX_ENERGY = 100;
        public static final int DEFAULT_START_ENERGY = 50;

        public Particle {
            if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
            if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
        }

        /** Convenience constructor with default max energy. */
        public Particle(String id, ParticleType type, int energy) {
            this(id, type, energy, DEFAULT_MAX_ENERGY);
        }

        /** Create a fresh particle with default starting energy. */
        public static Particle spawn(String id, ParticleType type) {
            return new Particle(id, type, DEFAULT_START_ENERGY, DEFAULT_MAX_ENERGY);
        }

        /**
         * Create a fresh particle with per-type max energy (Phase 13). Starting
         * energy is half of max, matching {@link com.paralife.engine.MetabolicProfile.TypeProfile#childStartEnergy()}.
         */
        public static Particle spawn(String id, ParticleType type, int maxEnergy) {
            return new Particle(id, type, maxEnergy / 2, maxEnergy);
        }

        /** Return a copy with adjusted energy, clamped to [0, maxEnergy]. */
        public Particle withEnergy(int newEnergy) {
            return new Particle(id, type, Math.clamp(newEnergy, 0, maxEnergy), maxEnergy);
        }

        public boolean isAlive() {
            return energy > 0;
        }

        /** Does this particle beat the other in RPS combat? */
        public boolean beats(Particle other) {
            return this.type.prey() == other.type;
        }
    }

    // ── Passive terrain ────────────────────────────────────────────

    /**
     * Impassable terrain. Cannot be moved through or destroyed.
     */
    record Rock(String id) implements Entity {}

    /**
     * Passive energy source. Particles consume nutrients to gain energy.
     *
     * @param id    unique identifier
     * @param level current nutrient level (consumed → decremented, 0 → removed)
     */
    record Nutrient(String id, int level) implements Entity {

        public static final int DEFAULT_LEVEL = 10;

        public Nutrient {
            if (level < 0) throw new IllegalArgumentException("Nutrient level cannot be negative: " + level);
        }

        public static Nutrient spawn(String id) {
            return new Nutrient(id, DEFAULT_LEVEL);
        }

        /** Return a copy with decremented level. */
        public Nutrient consumed(int amount) {
            return new Nutrient(id, Math.max(0, level - amount));
        }

        public boolean isDepleted() {
            return level <= 0;
        }
    }

    // ── Bonded pairs (endosymbiosis) ──────────────────────────────

    /**
     * A bonded pair formed when a predator and prey fuse via endosymbiosis.
     * Flat fields only — no nested member state (per D-05).
     * primaryType is the predator's type, secondaryType is the prey's type (per D-07).
     *
     * <p>Phase 13 Plan 02 extends the record with three cached hybrid vigor /
     * bond decay cost fields (D-05, D-06). These are computed once at formation
     * time via {@link #formBond} and stored permanently on the pair. Per-tick
     * randomness is deliberately avoided for stability (review concern #5).
     *
     * @param id                      composite identifier (predatorId+preyId)
     * @param primaryType             RPS type of the predator (dominant member)
     * @param secondaryType           RPS type of the prey (symbiont member)
     * @param energy                  shared energy pool (sum of both members at formation)
     * @param maxEnergy               energy cap (sum of both members' maxEnergy)
     * @param primaryEntityId         original entity ID of the predator (for bot cleanup)
     * @param secondaryEntityId       original entity ID of the prey (for bot cleanup)
     * @param effectiveDecayRate      D-06: cached bond decay cost (sum*factor), used per-tick
     * @param effectiveCombatTransfer D-05: cached hybrid combat transfer rate
     * @param effectiveAttackPower    D-05: cached hybrid attack power
     */
    record BondedPair(
            String id,
            ParticleType primaryType,
            ParticleType secondaryType,
            int energy,
            int maxEnergy,
            String primaryEntityId,
            String secondaryEntityId,
            int effectiveDecayRate,
            int effectiveCombatTransfer,
            int effectiveAttackPower
    ) implements Entity {

        /**
         * Default effective decay rate used by legacy 5/7-arg constructors.
         * Set to 0 so pre-Phase-13 tests constructed via legacy factories continue
         * to observe BondedPair decay == SimulationConfig.energyDecayPerTick(0) in
         * combatOnly-style configurations. Tests that explicitly need decay should
         * use the full 10-arg constructor and supply an effectiveDecayRate.
         */
        public static final int DEFAULT_EFFECTIVE_DECAY_RATE = 0;
        /** Default effective combat transfer used by legacy constructors (matches legacy flat value). */
        public static final int DEFAULT_EFFECTIVE_COMBAT_TRANSFER = 10;
        /** Default effective attack power used by legacy constructors (matches legacy flat value). */
        public static final int DEFAULT_EFFECTIVE_ATTACK_POWER = 10;

        public BondedPair {
            if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
            if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
        }

        /**
         * 7-arg constructor used by pre-Phase-13 code (e.g. SimulationEngine
         * revertToBondedPair and other callers). Fills in the three cached
         * hybrid-vigor fields with sensible defaults so existing behavior
         * (legacy decay/combat rates) is preserved.
         */
        public BondedPair(String id, ParticleType primaryType, ParticleType secondaryType,
                          int energy, int maxEnergy,
                          String primaryEntityId, String secondaryEntityId) {
            this(id, primaryType, secondaryType, energy, maxEnergy,
                    primaryEntityId, secondaryEntityId,
                    DEFAULT_EFFECTIVE_DECAY_RATE,
                    DEFAULT_EFFECTIVE_COMBAT_TRANSFER,
                    DEFAULT_EFFECTIVE_ATTACK_POWER);
        }

        /**
         * Convenience constructor without explicit entity IDs (for tests and legacy usage).
         * Derives constituent IDs by splitting the composite id on "+".
         */
        public BondedPair(String id, ParticleType primaryType, ParticleType secondaryType,
                          int energy, int maxEnergy) {
            this(id, primaryType, secondaryType, energy, maxEnergy,
                    id.contains("+") ? id.split("\\+", 2)[0] : id,
                    id.contains("+") ? id.split("\\+", 2)[1] : id,
                    DEFAULT_EFFECTIVE_DECAY_RATE,
                    DEFAULT_EFFECTIVE_COMBAT_TRANSFER,
                    DEFAULT_EFFECTIVE_ATTACK_POWER);
        }

        /** Return a copy with adjusted energy, clamped to [0, maxEnergy]. */
        public BondedPair withEnergy(int newEnergy) {
            return new BondedPair(id, primaryType, secondaryType,
                    Math.clamp(newEnergy, 0, maxEnergy), maxEnergy,
                    primaryEntityId, secondaryEntityId,
                    effectiveDecayRate, effectiveCombatTransfer, effectiveAttackPower);
        }

        public boolean isAlive() {
            return energy > 0;
        }

        // ── Phase 13 Plan 02: formation-time hybrid vigor (D-05, D-06) ──

        /**
         * Create a BondedPair with cached hybrid vigor rates (D-05, D-06).
         * Rates computed once at formation and stored — no per-tick randomness.
         *
         * <p>The decay rate (D-06) uses {@code sum * random(costMin, costMax)}.
         * Combat transfer and attack power (D-05) use
         * {@code avg + (max - avg) * random(bonusMin, bonusMax)}.
         *
         * <p>Phase 16 Plan 01: last parameter widened from {@link ThreadLocalRandom}
         * to {@link RandomGenerator} so callers can inject a seeded RNG for
         * determinism. Package-independent signature (takes primitive ints /
         * doubles + a JDK-standard RandomGenerator) so this can be called without
         * pulling MetabolicProfile / BondingConfig into the world package.
         */
        public static BondedPair formBond(
                String id,
                Particle primary, Particle secondary,
                int primaryDecay, int primaryCombatTransfer, int primaryAttackPower, int primaryMaxEnergy,
                int secondaryDecay, int secondaryCombatTransfer, int secondaryAttackPower, int secondaryMaxEnergy,
                double bondRateBonusMin, double bondRateBonusMax,
                double bondDecayCostMin, double bondDecayCostMax,
                RandomGenerator rng) {

            int maxEnergy = primaryMaxEnergy + secondaryMaxEnergy; // D-07
            int combinedEnergy = primary.energy() + secondary.energy();

            int effectiveDecay = bondDecayCost(primaryDecay, secondaryDecay,
                    bondDecayCostMin, bondDecayCostMax, rng);
            int effectiveCombat = hybridRate(primaryCombatTransfer, secondaryCombatTransfer,
                    bondRateBonusMin, bondRateBonusMax, rng);
            int effectiveAttack = hybridRate(primaryAttackPower, secondaryAttackPower,
                    bondRateBonusMin, bondRateBonusMax, rng);

            return new BondedPair(
                    id,
                    primary.type(), secondary.type(),
                    combinedEnergy, maxEnergy,
                    primary.id(), secondary.id(),
                    effectiveDecay, effectiveCombat, effectiveAttack);
        }

        /** Hybrid vigor (D-05): rate = avg + (max-avg) * random(bonusMin, bonusMax). */
        static int hybridRate(int rateA, int rateB, double bonusMin, double bonusMax, RandomGenerator rng) {
            int avg = (rateA + rateB) / 2;
            int max = Math.max(rateA, rateB);
            // Guard: RandomGenerator.nextDouble(origin,bound) requires origin<bound; use min when equal.
            double bonus = (bonusMin == bonusMax) ? bonusMin : rng.nextDouble(bonusMin, bonusMax);
            return avg + (int) ((max - avg) * bonus);
        }

        /** Bond decay cost (D-06): bondedDecay = sum(A,B) * random(costMin, costMax). */
        static int bondDecayCost(int decayA, int decayB, double costMin, double costMax, RandomGenerator rng) {
            double factor = (costMin == costMax) ? costMin : rng.nextDouble(costMin, costMax);
            int raw = (int) ((decayA + decayB) * factor);
            // Ensure at least 1 unit of decay when the constituents had any decay at all.
            return (decayA + decayB) > 0 ? Math.max(1, raw) : 0;
        }
    }

    // ── Composite members (siphonophore model) ───────────────────

    /**
     * A member of a composite organism. Each member occupies its own grid cell
     * and shares a compositeId with other members of the same composite.
     * Shared state (energy pool, member list) lives in CompositeRegistry.
     *
     * @param id          unique entity identifier for this member
     * @param compositeId identifier of the composite this member belongs to
     * @param type        original RPS particle type
     * @param role        specialization role within the composite (D-06)
     * @param energy      individual energy (combat damage hits this directly, D-15)
     * @param maxEnergy   energy cap for this member
     */
    record CompositeMember(
            String id,
            String compositeId,
            ParticleType type,
            Role role,
            int energy,
            int maxEnergy
    ) implements Entity {

        public CompositeMember {
            if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
            if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
        }

        /** Return a copy with adjusted energy, clamped to [0, maxEnergy]. */
        public CompositeMember withEnergy(int newEnergy) {
            return new CompositeMember(id, compositeId, type, role,
                    Math.clamp(newEnergy, 0, maxEnergy), maxEnergy);
        }

        public boolean isAlive() {
            return energy > 0;
        }
    }
}
