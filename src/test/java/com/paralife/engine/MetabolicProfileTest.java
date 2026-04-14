package com.paralife.engine;

import com.paralife.engine.MetabolicProfile.TypeProfile;
import com.paralife.world.Entity.ParticleType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MetabolicProfile} per-type configuration record.
 *
 * <p>Covers:
 * <ul>
 *   <li>Default archetype values (D-03) per particle type</li>
 *   <li>{@code forType()} lookup for each {@link ParticleType}</li>
 *   <li>{@code childStartEnergy()} derivation (maxEnergy / 2)</li>
 *   <li>Compact-constructor validation (ranges, cross-field constraints)</li>
 * </ul>
 */
class MetabolicProfileTest {

    @Nested
    class Defaults {

        @Test
        void catalystMatchesArchetype() {
            TypeProfile p = MetabolicProfile.defaults().forType(ParticleType.CATALYST);

            assertThat(p.maxEnergy()).isEqualTo(80);
            assertThat(p.decayPerTick()).isEqualTo(3);
            assertThat(p.combatEnergyTransfer()).isEqualTo(15);
            assertThat(p.attackPower()).isEqualTo(15);
            assertThat(p.nutrientConsumeEnergy()).isEqualTo(3);
            assertThat(p.reproduceEnergyCost()).isEqualTo(40);
            assertThat(p.reproduceCooldown()).isEqualTo(10);
            assertThat(p.bonusOffspringChance()).isEqualTo(0.0);
            assertThat(p.reproduceRange()).isEqualTo(1);
            assertThat(p.starvationThreshold()).isEqualTo(30);
            assertThat(p.starvationFloor()).isEqualTo(10);
        }

        @Test
        void membraneMatchesArchetype() {
            TypeProfile p = MetabolicProfile.defaults().forType(ParticleType.MEMBRANE);

            assertThat(p.maxEnergy()).isEqualTo(120);
            assertThat(p.decayPerTick()).isEqualTo(1);
            assertThat(p.combatEnergyTransfer()).isEqualTo(5);
            assertThat(p.attackPower()).isEqualTo(5);
            assertThat(p.nutrientConsumeEnergy()).isEqualTo(8);
            assertThat(p.reproduceEnergyCost()).isEqualTo(35);
            assertThat(p.reproduceCooldown()).isEqualTo(8);
            assertThat(p.bonusOffspringChance()).isEqualTo(0.0);
            assertThat(p.reproduceRange()).isEqualTo(1);
            assertThat(p.starvationThreshold()).isEqualTo(25);
            assertThat(p.starvationFloor()).isEqualTo(8);
        }

        @Test
        void sporeMatchesArchetype() {
            TypeProfile p = MetabolicProfile.defaults().forType(ParticleType.SPORE);

            assertThat(p.maxEnergy()).isEqualTo(60);
            assertThat(p.decayPerTick()).isEqualTo(2);
            assertThat(p.combatEnergyTransfer()).isEqualTo(8);
            assertThat(p.attackPower()).isEqualTo(8);
            assertThat(p.nutrientConsumeEnergy()).isEqualTo(5);
            assertThat(p.reproduceEnergyCost()).isEqualTo(20);
            assertThat(p.reproduceCooldown()).isEqualTo(5);
            assertThat(p.bonusOffspringChance()).isEqualTo(0.25);
            assertThat(p.reproduceRange()).isEqualTo(2);
            assertThat(p.starvationThreshold()).isEqualTo(35);
            assertThat(p.starvationFloor()).isEqualTo(12);
        }

        @Test
        void archetypeOrderingMatchesD03() {
            // D-03: CATALYST burns fastest, MEMBRANE is most efficient, SPORE middle
            MetabolicProfile mp = MetabolicProfile.defaults();
            assertThat(mp.forType(ParticleType.CATALYST).decayPerTick())
                    .isGreaterThan(mp.forType(ParticleType.SPORE).decayPerTick());
            assertThat(mp.forType(ParticleType.SPORE).decayPerTick())
                    .isGreaterThan(mp.forType(ParticleType.MEMBRANE).decayPerTick());

            // D-03: SPORE reproduces cheapest
            assertThat(mp.forType(ParticleType.SPORE).reproduceEnergyCost())
                    .isLessThan(mp.forType(ParticleType.MEMBRANE).reproduceEnergyCost());
            assertThat(mp.forType(ParticleType.MEMBRANE).reproduceEnergyCost())
                    .isLessThan(mp.forType(ParticleType.CATALYST).reproduceEnergyCost());
        }
    }

    @Nested
    class ChildStartEnergy {

        @Test
        void catalystChildGetsHalfMaxEnergy() {
            TypeProfile p = MetabolicProfile.defaults().forType(ParticleType.CATALYST);
            assertThat(p.childStartEnergy()).isEqualTo(40); // 80 / 2
        }

        @Test
        void membraneChildGetsHalfMaxEnergy() {
            TypeProfile p = MetabolicProfile.defaults().forType(ParticleType.MEMBRANE);
            assertThat(p.childStartEnergy()).isEqualTo(60); // 120 / 2
        }

        @Test
        void sporeChildGetsHalfMaxEnergy() {
            TypeProfile p = MetabolicProfile.defaults().forType(ParticleType.SPORE);
            assertThat(p.childStartEnergy()).isEqualTo(30); // 60 / 2
        }

        @Test
        void childStartEnergyIsMaxEnergyDividedByTwo() {
            TypeProfile p = new TypeProfile(100, 1, 5, 5, 5, 30, 0, 0.0, 1, 30, 10);
            assertThat(p.childStartEnergy()).isEqualTo(50);
        }
    }

    @Nested
    class ForTypeLookup {

        @Test
        void returnsExactInstancePerType() {
            MetabolicProfile mp = MetabolicProfile.defaults();
            assertThat(mp.forType(ParticleType.CATALYST)).isSameAs(mp.catalyst());
            assertThat(mp.forType(ParticleType.MEMBRANE)).isSameAs(mp.membrane());
            assertThat(mp.forType(ParticleType.SPORE)).isSameAs(mp.spore());
        }
    }

    @Nested
    class Validation {

        private TypeProfile baseline() {
            return new TypeProfile(100, 1, 5, 5, 5, 30, 5, 0.0, 1, 30, 10);
        }

        @Test
        void rejectsZeroMaxEnergy() {
            assertThatThrownBy(() -> new TypeProfile(0, 1, 5, 5, 5, 30, 5, 0.0, 1, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxEnergy");
        }

        @Test
        void rejectsNegativeMaxEnergy() {
            assertThatThrownBy(() -> new TypeProfile(-1, 1, 5, 5, 5, 30, 5, 0.0, 1, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeDecayPerTick() {
            assertThatThrownBy(() -> new TypeProfile(100, -1, 5, 5, 5, 30, 5, 0.0, 1, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("decayPerTick");
        }

        @Test
        void rejectsNegativeAttackPower() {
            assertThatThrownBy(() -> new TypeProfile(100, 1, 5, -1, 5, 30, 5, 0.0, 1, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attackPower");
        }

        @Test
        void rejectsReproduceRangeBelowOne() {
            assertThatThrownBy(() -> new TypeProfile(100, 1, 5, 5, 5, 30, 5, 0.0, 0, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reproduceRange");
        }

        @Test
        void rejectsBonusOffspringChanceBelowZero() {
            assertThatThrownBy(() -> new TypeProfile(100, 1, 5, 5, 5, 30, 5, -0.01, 1, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bonusOffspringChance");
        }

        @Test
        void rejectsBonusOffspringChanceAboveOne() {
            assertThatThrownBy(() -> new TypeProfile(100, 1, 5, 5, 5, 30, 5, 1.01, 1, 30, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bonusOffspringChance");
        }

        @Test
        void rejectsStarvationFloorAboveThreshold() {
            assertThatThrownBy(() -> new TypeProfile(100, 1, 5, 5, 5, 30, 5, 0.0, 1, 20, 25))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("starvationFloor");
        }

        @Test
        void rejectsStarvationThresholdAbove100() {
            assertThatThrownBy(() -> new TypeProfile(100, 1, 5, 5, 5, 30, 5, 0.0, 1, 101, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("starvationThreshold");
        }

        @Test
        void acceptsStarvationFloorEqualToThreshold() {
            assertThat(new TypeProfile(100, 1, 5, 5, 5, 30, 5, 0.0, 1, 30, 30))
                    .isNotNull();
        }

        @Test
        void rejectsMissingTypeProfile() {
            assertThatThrownBy(() -> new MetabolicProfile(null, baseline(), baseline()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MetabolicProfile(baseline(), null, baseline()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MetabolicProfile(baseline(), baseline(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
