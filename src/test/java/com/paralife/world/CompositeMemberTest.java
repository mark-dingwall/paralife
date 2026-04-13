package com.paralife.world;

import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeMemberTest {

    // ── Construction ──────────────────────────────────────────────

    @Test
    void validConstruction() {
        var cm = new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        assertThat(cm.id()).isEqualTo("cm-1");
        assertThat(cm.compositeId()).isEqualTo("comp-1");
        assertThat(cm.type()).isEqualTo(ParticleType.CATALYST);
        assertThat(cm.role()).isEqualTo(Role.FEEDER);
        assertThat(cm.energy()).isEqualTo(50);
        assertThat(cm.maxEnergy()).isEqualTo(100);
    }

    @Test
    void negativeEnergyRejected() {
        assertThatThrownBy(() -> new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.FEEDER, -1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroMaxEnergyRejected() {
        assertThatThrownBy(() -> new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.FEEDER, 50, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── withEnergy ────────────────────────────────────────────────

    @Test
    void withEnergyReturnsNewInstance() {
        var cm = new CompositeMember("cm-1", "comp-1", ParticleType.SPORE, Role.ATTACKER, 50, 100);
        var updated = cm.withEnergy(75);
        assertThat(updated.energy()).isEqualTo(75);
        assertThat(updated).isNotSameAs(cm);
        assertThat(updated.id()).isEqualTo("cm-1");
        assertThat(updated.compositeId()).isEqualTo("comp-1");
        assertThat(updated.role()).isEqualTo(Role.ATTACKER);
    }

    @Test
    void withEnergyClampsAboveMax() {
        var cm = new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        assertThat(cm.withEnergy(200).energy()).isEqualTo(100);
    }

    @Test
    void withEnergyClampsToZero() {
        var cm = new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        assertThat(cm.withEnergy(-10).energy()).isEqualTo(0);
    }

    // ── isAlive ───────────────────────────────────────────────────

    @Test
    void isAliveWhenEnergyPositive() {
        var cm = new CompositeMember("cm-1", "comp-1", ParticleType.MEMBRANE, Role.DEFENDER, 1, 100);
        assertThat(cm.isAlive()).isTrue();
    }

    @Test
    void notAliveWhenEnergyZero() {
        var cm = new CompositeMember("cm-1", "comp-1", ParticleType.MEMBRANE, Role.DEFENDER, 0, 100);
        assertThat(cm.isAlive()).isFalse();
    }

    // ── Role enum ─────────────────────────────────────────────────

    @Test
    void roleEnumHasSixValues() {
        assertThat(Role.values()).hasSize(6);
    }

    @Test
    void roleEnumContainsAllExpectedValues() {
        assertThat(Role.values()).containsExactlyInAnyOrder(
                Role.LOCOMOTOR, Role.FEEDER, Role.ATTACKER,
                Role.DEFENDER, Role.REPRODUCER, Role.SENSOR
        );
    }

    // ── Sealed interface participation ────────────────────────────

    @Test
    void compositeMemberIsEntity() {
        Entity entity = new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        assertThat(entity).isInstanceOf(Entity.class);
    }

    @Test
    void patternMatchingWorks() {
        Entity entity = new CompositeMember("cm-1", "comp-1", ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        String result = switch (entity) {
            case Entity.Particle p -> "particle";
            case Entity.Rock r -> "rock";
            case Entity.Nutrient n -> "nutrient";
            case Entity.BondedPair bp -> "bonded";
            case CompositeMember cm -> "composite:" + cm.role();
        };
        assertThat(result).isEqualTo("composite:LOCOMOTOR");
    }
}
