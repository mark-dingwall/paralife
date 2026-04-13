package com.paralife.world;

import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Rock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTest {

    // ── ParticleType RPS ───────────────────────────────────────────

    @Test
    void catalystBeatsSporeLosesToMembrane() {
        assertThat(ParticleType.CATALYST.prey()).isEqualTo(ParticleType.SPORE);
        assertThat(ParticleType.CATALYST.predator()).isEqualTo(ParticleType.MEMBRANE);
    }

    @Test
    void sporeBeatsMembraneLosesToCatalyst() {
        assertThat(ParticleType.SPORE.prey()).isEqualTo(ParticleType.MEMBRANE);
        assertThat(ParticleType.SPORE.predator()).isEqualTo(ParticleType.CATALYST);
    }

    @Test
    void membraneBeatssCatalystLosesToSpore() {
        assertThat(ParticleType.MEMBRANE.prey()).isEqualTo(ParticleType.CATALYST);
        assertThat(ParticleType.MEMBRANE.predator()).isEqualTo(ParticleType.SPORE);
    }

    // ── Particle ───────────────────────────────────────────────────

    @Test
    void spawnCreatesWithDefaults() {
        Particle p = Particle.spawn("p1", ParticleType.CATALYST);
        assertThat(p.id()).isEqualTo("p1");
        assertThat(p.type()).isEqualTo(ParticleType.CATALYST);
        assertThat(p.energy()).isEqualTo(Particle.DEFAULT_START_ENERGY);
        assertThat(p.maxEnergy()).isEqualTo(Particle.DEFAULT_MAX_ENERGY);
        assertThat(p.isAlive()).isTrue();
    }

    @Test
    void withEnergyClampsToRange() {
        Particle p = Particle.spawn("p1", ParticleType.SPORE);
        assertThat(p.withEnergy(200).energy()).isEqualTo(100); // clamped to max
        assertThat(p.withEnergy(-5).energy()).isEqualTo(0);    // clamped to 0
    }

    @Test
    void deadParticleHasZeroEnergy() {
        Particle p = Particle.spawn("p1", ParticleType.MEMBRANE).withEnergy(0);
        assertThat(p.isAlive()).isFalse();
    }

    @Test
    void beatsFollowsRPS() {
        Particle catalyst = Particle.spawn("c", ParticleType.CATALYST);
        Particle spore = Particle.spawn("s", ParticleType.SPORE);
        Particle membrane = Particle.spawn("m", ParticleType.MEMBRANE);

        assertThat(catalyst.beats(spore)).isTrue();
        assertThat(catalyst.beats(membrane)).isFalse();
        assertThat(spore.beats(membrane)).isTrue();
        assertThat(membrane.beats(catalyst)).isTrue();
    }

    @Test
    void negativeEnergyRejected() {
        assertThatThrownBy(() -> new Particle("p", ParticleType.CATALYST, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroMaxEnergyRejected() {
        assertThatThrownBy(() -> new Particle("p", ParticleType.CATALYST, 50, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Rock ───────────────────────────────────────────────────────

    @Test
    void rockHasId() {
        Rock r = new Rock("rock-1");
        assertThat(r.id()).isEqualTo("rock-1");
    }

    // ── Nutrient ───────────────────────────────────────────────────

    @Test
    void nutrientSpawnDefaults() {
        Nutrient n = Nutrient.spawn("n1");
        assertThat(n.level()).isEqualTo(Nutrient.DEFAULT_LEVEL);
        assertThat(n.isDepleted()).isFalse();
    }

    @Test
    void nutrientConsumed() {
        Nutrient n = Nutrient.spawn("n1");
        Nutrient consumed = n.consumed(3);
        assertThat(consumed.level()).isEqualTo(7);
        assertThat(consumed.isDepleted()).isFalse();
    }

    @Test
    void nutrientDepletedAtZero() {
        Nutrient n = new Nutrient("n1", 2);
        assertThat(n.consumed(5).isDepleted()).isTrue();
        assertThat(n.consumed(5).level()).isEqualTo(0);
    }

    @Test
    void negativeNutrientLevelRejected() {
        assertThatThrownBy(() -> new Nutrient("n", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Sealed interface ───────────────────────────────────────────

    @Test
    void patternMatchingWorks() {
        Entity entity = Particle.spawn("p1", ParticleType.CATALYST);
        String result = switch (entity) {
            case Particle p -> "particle:" + p.type();
            case Rock r -> "rock";
            case Nutrient n -> "nutrient:" + n.level();
            case Entity.BondedPair bp -> "bonded:" + bp.primaryType() + "+" + bp.secondaryType();
        };
        assertThat(result).isEqualTo("particle:CATALYST");
    }

    // ── BondedPair ────────────────────────────────────────────────

    @Test
    void bondedPairConstruction() {
        var bp = new Entity.BondedPair("cat+spo", ParticleType.CATALYST, ParticleType.SPORE, 100, 200);
        assertThat(bp.id()).isEqualTo("cat+spo");
        assertThat(bp.primaryType()).isEqualTo(ParticleType.CATALYST);
        assertThat(bp.secondaryType()).isEqualTo(ParticleType.SPORE);
        assertThat(bp.energy()).isEqualTo(100);
        assertThat(bp.maxEnergy()).isEqualTo(200);
        assertThat(bp.isAlive()).isTrue();
    }

    @Test
    void bondedPairWithEnergyClampsToRange() {
        var bp = new Entity.BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE, 100, 200);
        assertThat(bp.withEnergy(300).energy()).isEqualTo(200); // clamped to max
        assertThat(bp.withEnergy(-5).energy()).isEqualTo(0);    // clamped to 0
    }

    @Test
    void bondedPairDeadAtZeroEnergy() {
        var bp = new Entity.BondedPair("bp", ParticleType.MEMBRANE, ParticleType.CATALYST, 50, 100);
        assertThat(bp.withEnergy(0).isAlive()).isFalse();
    }

    @Test
    void bondedPairNegativeEnergyRejected() {
        assertThatThrownBy(() -> new Entity.BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE, -1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bondedPairZeroMaxEnergyRejected() {
        assertThatThrownBy(() -> new Entity.BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE, 50, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bondedPairPatternMatching() {
        Entity entity = new Entity.BondedPair("bp", ParticleType.SPORE, ParticleType.MEMBRANE, 80, 200);
        String result = switch (entity) {
            case Particle p -> "particle";
            case Rock r -> "rock";
            case Nutrient n -> "nutrient";
            case Entity.BondedPair bp -> "bonded:" + bp.primaryType() + "+" + bp.secondaryType();
        };
        assertThat(result).isEqualTo("bonded:SPORE+MEMBRANE");
    }
}
