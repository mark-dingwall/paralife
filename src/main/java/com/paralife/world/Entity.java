package com.paralife.world;

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
     * @param id               composite identifier (predatorId+preyId)
     * @param primaryType      RPS type of the predator (dominant member)
     * @param secondaryType    RPS type of the prey (symbiont member)
     * @param energy           shared energy pool (sum of both members at formation)
     * @param maxEnergy        energy cap (sum of both members' maxEnergy)
     * @param primaryEntityId  original entity ID of the predator (for bot cleanup)
     * @param secondaryEntityId original entity ID of the prey (for bot cleanup)
     */
    record BondedPair(
            String id,
            ParticleType primaryType,
            ParticleType secondaryType,
            int energy,
            int maxEnergy,
            String primaryEntityId,
            String secondaryEntityId
    ) implements Entity {

        public BondedPair {
            if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
            if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
        }

        /**
         * Convenience constructor without explicit entity IDs (for tests and legacy usage).
         * Derives constituent IDs by splitting the composite id on "+".
         */
        public BondedPair(String id, ParticleType primaryType, ParticleType secondaryType,
                          int energy, int maxEnergy) {
            this(id, primaryType, secondaryType, energy, maxEnergy,
                    id.contains("+") ? id.split("\\+", 2)[0] : id,
                    id.contains("+") ? id.split("\\+", 2)[1] : id);
        }

        /** Return a copy with adjusted energy, clamped to [0, maxEnergy]. */
        public BondedPair withEnergy(int newEnergy) {
            return new BondedPair(id, primaryType, secondaryType,
                    Math.clamp(newEnergy, 0, maxEnergy), maxEnergy,
                    primaryEntityId, secondaryEntityId);
        }

        public boolean isAlive() {
            return energy > 0;
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
