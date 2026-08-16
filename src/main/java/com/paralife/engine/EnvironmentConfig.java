package com.paralife.engine;

import com.paralife.engine.SeasonTracker.Season;
import com.paralife.world.Entity.ParticleType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 14 environmental-rules config (D-48).
 *
 * <p><b>cycle-6 HIGH #1 — {@code seed} field:</b> nullable Long. Production yaml
 * omits the key so {@link #seed()} returns null and
 * {@code EnvironmentEngine}'s production constructor uses
 * {@code new Random()}. Tests bind via
 * {@code @TestPropertySource(paralife.simulation.events.seed=42)} so seeded
 * runs are reproducible. Do NOT make this a {@code long} primitive — the
 * nullable-Long semantics are load-bearing for the "production RNG is
 * unseeded, test RNG is seeded" distinction.
 */
@ConfigurationProperties(prefix = "paralife.simulation.events")
public record EnvironmentConfig(
        boolean enabled,
        Long seed,
        Lightning lightning,
        Toxin toxin,
        Mutagen mutagen,
        Compost compost
) {

    public EnvironmentConfig {
        if (lightning == null) throw new IllegalStateException("lightning config required");
        if (toxin == null) throw new IllegalStateException("toxin config required");
        if (mutagen == null) throw new IllegalStateException("mutagen config required");
        if (compost == null) throw new IllegalStateException("compost config required");
        // seed may be null — that is valid (production path).
    }

    public static EnvironmentConfig defaults() {
        return new EnvironmentConfig(
                true,                          // enabled
                null,                          // seed — cycle-6 HIGH #1: null = production (unseeded Random)
                Lightning.defaults(),
                Toxin.defaults(),
                Mutagen.defaults(),
                Compost.defaults());
    }

    // ── Nested records ─────────────────────────────────────────────

    /**
     * Lightning strike config (D-21, D-22, D-48). Summer-peak Poisson event
     * with dual-radius effect: inner-radius damage, outer-radius fertility boost.
     */
    public record Lightning(
            Season peakSeason,
            double peakLambda,
            double offSeasonLambda,
            int innerRadius,
            int outerRadius,
            int damage,
            int fertilityBoost,
            int fleeingTicks
    ) {
        /**
         * Plan 15-08 Task 2 (SCHEMA §9 D-50 #9): default FLEEING duration.
         * Survivors in the outer damage radius flee for this many ticks after
         * the strike. Bots consume the f{@code F:<expiry>:<XXYY>} effect via
         * the codec and steer away from the stored strike coord.
         */
        public static final int DEFAULT_FLEEING_TICKS = 8;

        public Lightning {
            if (peakSeason == null)
                throw new IllegalArgumentException("lightning.peakSeason required");
            if (peakLambda < 0.0 || peakLambda > 1.0)
                throw new IllegalArgumentException("lightning.peakLambda must be in [0, 1]: " + peakLambda);
            if (offSeasonLambda < 0.0 || offSeasonLambda > 1.0)
                throw new IllegalArgumentException("lightning.offSeasonLambda must be in [0, 1]: " + offSeasonLambda);
            if (innerRadius < 0)
                throw new IllegalArgumentException("lightning.innerRadius must be >= 0: " + innerRadius);
            if (outerRadius < innerRadius)
                throw new IllegalArgumentException(
                        "lightning.outerRadius must be >= innerRadius: inner="
                                + innerRadius + " outer=" + outerRadius);
            if (damage < 0)
                throw new IllegalArgumentException("lightning.damage must be >= 0: " + damage);
            if (fertilityBoost < 0)
                throw new IllegalArgumentException("lightning.fertilityBoost must be >= 0: " + fertilityBoost);
            if (fleeingTicks < 0)
                throw new IllegalArgumentException("lightning.fleeingTicks must be >= 0: " + fleeingTicks);
        }

        public static Lightning defaults() {
            return new Lightning(Season.SUMMER, 0.04, 0.005, 2, 4, 40, 25, DEFAULT_FLEEING_TICKS);
        }
    }

    /**
     * Toxin spread config (D-04 through D-11, D-48). Autumn-peak Poisson
     * spline-path event with CA diffusion + decay. Plan 02 extends with
     * {@code diffusionRate}.
     */
    public record Toxin(
            Season peakSeason,
            double peakLambda,
            double offSeasonLambda,
            int pathPointsMin,
            int pathPointsMax,
            int pathOffsetMin,
            int pathOffsetMax,
            int speed,
            int lifetimeTicks,
            int diffusionRadius,
            double diffusionRate,
            double decayRate,
            double splashDamageFraction,
            int baseDamage,
            int intensityThreshold,
            Resistance resistance
    ) {
        public Toxin {
            if (peakSeason == null)
                throw new IllegalArgumentException("toxin.peakSeason required");
            if (peakLambda < 0.0 || peakLambda > 1.0)
                throw new IllegalArgumentException("toxin.peakLambda must be in [0, 1]: " + peakLambda);
            if (offSeasonLambda < 0.0 || offSeasonLambda > 1.0)
                throw new IllegalArgumentException("toxin.offSeasonLambda must be in [0, 1]: " + offSeasonLambda);
            if (pathPointsMin <= 1)
                throw new IllegalArgumentException("toxin.pathPointsMin must be > 1: " + pathPointsMin);
            if (pathPointsMax < pathPointsMin)
                throw new IllegalArgumentException(
                        "toxin.pathPointsMax must be >= pathPointsMin: min="
                                + pathPointsMin + " max=" + pathPointsMax);
            if (pathOffsetMin < 0)
                throw new IllegalArgumentException("toxin.pathOffsetMin must be >= 0: " + pathOffsetMin);
            if (pathOffsetMax < pathOffsetMin)
                throw new IllegalArgumentException(
                        "toxin.pathOffsetMax must be >= pathOffsetMin: min="
                                + pathOffsetMin + " max=" + pathOffsetMax);
            if (speed <= 0)
                throw new IllegalArgumentException("toxin.speed must be > 0: " + speed);
            if (lifetimeTicks <= 0)
                throw new IllegalArgumentException("toxin.lifetimeTicks must be > 0: " + lifetimeTicks);
            if (diffusionRadius < 0)
                throw new IllegalArgumentException("toxin.diffusionRadius must be >= 0: " + diffusionRadius);
            if (diffusionRate < 0.0 || diffusionRate > 1.0)
                throw new IllegalArgumentException(
                        "toxin.diffusionRate must be in [0, 1]: " + diffusionRate);
            if (decayRate < 0.0 || decayRate > 1.0)
                throw new IllegalArgumentException("toxin.decayRate must be in [0, 1]: " + decayRate);
            if (splashDamageFraction < 0.0 || splashDamageFraction > 1.0)
                throw new IllegalArgumentException(
                        "toxin.splashDamageFraction must be in [0, 1]: " + splashDamageFraction);
            if (baseDamage < 0)
                throw new IllegalArgumentException("toxin.baseDamage must be >= 0: " + baseDamage);
            if (intensityThreshold < 0 || intensityThreshold > 255)
                throw new IllegalArgumentException(
                        "toxin.intensityThreshold must be in [0, 255]: " + intensityThreshold);
            if (resistance == null)
                throw new IllegalArgumentException("toxin.resistance required");
        }

        public static Toxin defaults() {
            return new Toxin(Season.AUTUMN, 0.03, 0.005,
                    4, 8, 5, 25, 3, 80, 3, 0.5, 0.1, 0.2, 10, 20,
                    Resistance.defaults());
        }

        /** Per-type toxin resistance coefficients (D-09). */
        public record Resistance(double catalyst, double membrane, double spore) {
            public Resistance {
                if (catalyst < 0.0)
                    throw new IllegalArgumentException("toxin.resistance.catalyst must be >= 0: " + catalyst);
                if (membrane < 0.0)
                    throw new IllegalArgumentException("toxin.resistance.membrane must be >= 0: " + membrane);
                if (spore < 0.0)
                    throw new IllegalArgumentException("toxin.resistance.spore must be >= 0: " + spore);
            }

            public static Resistance defaults() {
                return new Resistance(1.0, 0.7, 1.3);
            }

            public double forType(ParticleType type) {
                return switch (type) {
                    case CATALYST -> catalyst;
                    case MEMBRANE -> membrane;
                    case SPORE -> spore;
                };
            }
        }
    }

    /**
     * Mutagen outbreak config (D-12 through D-20, D-48). Spring-peak Poisson
     * strain-gossip event with damage-over-time and survivor buffs.
     *
     * <p>{@code zoneDecayTicks} is pre-wired for Plan 03 (default 50).
     * Plan 03 Task 1 extends with {@code outbreakLifetimeTicks}.
     */
    public record Mutagen(
            Season peakSeason,
            double peakLambda,
            double offSeasonLambda,
            int infectionDurationMin,
            int infectionDurationMax,
            int buffDurationMultiplier,
            int cureTicks,
            int attackCureReduction,
            int damagePerTick,
            double strainMutationChance,
            double gossipProbability,
            int zoneDecayTicks,
            int outbreakLifetimeTicks,
            int maxRadius
    ) {
        public Mutagen {
            if (peakSeason == null)
                throw new IllegalArgumentException("mutagen.peakSeason required");
            if (peakLambda < 0.0 || peakLambda > 1.0)
                throw new IllegalArgumentException("mutagen.peakLambda must be in [0, 1]: " + peakLambda);
            if (offSeasonLambda < 0.0 || offSeasonLambda > 1.0)
                throw new IllegalArgumentException("mutagen.offSeasonLambda must be in [0, 1]: " + offSeasonLambda);
            if (infectionDurationMin <= 0)
                throw new IllegalArgumentException(
                        "mutagen.infectionDurationMin must be > 0: " + infectionDurationMin);
            if (infectionDurationMax < infectionDurationMin)
                throw new IllegalArgumentException(
                        "mutagen.infectionDurationMax must be >= infectionDurationMin: min="
                                + infectionDurationMin + " max=" + infectionDurationMax);
            if (buffDurationMultiplier <= 0)
                throw new IllegalArgumentException(
                        "mutagen.buffDurationMultiplier must be > 0: " + buffDurationMultiplier);
            if (cureTicks < 0)
                throw new IllegalArgumentException("mutagen.cureTicks must be >= 0: " + cureTicks);
            if (attackCureReduction < 0)
                throw new IllegalArgumentException(
                        "mutagen.attackCureReduction must be >= 0: " + attackCureReduction);
            if (damagePerTick < 0)
                throw new IllegalArgumentException(
                        "mutagen.damagePerTick must be >= 0: " + damagePerTick);
            if (strainMutationChance < 0.0 || strainMutationChance > 1.0)
                throw new IllegalArgumentException(
                        "mutagen.strainMutationChance must be in [0, 1]: " + strainMutationChance);
            if (gossipProbability < 0.0 || gossipProbability > 1.0)
                throw new IllegalArgumentException(
                        "mutagen.gossipProbability must be in [0, 1]: " + gossipProbability);
            if (zoneDecayTicks <= 0)
                throw new IllegalArgumentException(
                        "mutagen.zoneDecayTicks must be > 0: " + zoneDecayTicks);
            if (outbreakLifetimeTicks <= 0)
                throw new IllegalArgumentException(
                        "mutagen.outbreakLifetimeTicks must be > 0: " + outbreakLifetimeTicks);
            if (maxRadius <= 0)
                throw new IllegalArgumentException("mutagen.maxRadius must be > 0: " + maxRadius);
        }

        public static Mutagen defaults() {
            return new Mutagen(Season.SPRING, 0.02, 0.0025,
                    20, 30, 10, 5, 3, 2,
                    0.1, 0.3, 50, 300, 20);
        }
    }

    /**
     * Corpse compost config (D-24, D-25, D-48). On entity death: full strength
     * at death cell, half strength at each of the 8 Moore neighbors.
     */
    public record Compost(int fullStrength, int halfStrength) {
        public Compost {
            if (fullStrength < 0)
                throw new IllegalArgumentException("compost.fullStrength must be >= 0: " + fullStrength);
            if (halfStrength < 0)
                throw new IllegalArgumentException("compost.halfStrength must be >= 0: " + halfStrength);
        }

        public static Compost defaults() {
            return new Compost(30, 15);
        }
    }
}
