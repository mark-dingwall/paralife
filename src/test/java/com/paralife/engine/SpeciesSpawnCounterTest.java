package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.world.Entity.ParticleType;
import org.junit.jupiter.api.Test;

class SpeciesSpawnCounterTest {

    @Test
    void incrementRaisesOnlyTheTargetSpeciesByExactlyOne() {
        SpeciesSpawnCounter counter = new SpeciesSpawnCounter();
        long before = counter.get(ParticleType.CATALYST);

        counter.increment(ParticleType.CATALYST);

        assertThat(counter.get(ParticleType.CATALYST) - before)
                .as("committed spawn is an exact +1 delta on its species").isEqualTo(1L);
        assertThat(counter.get(ParticleType.MEMBRANE))
                .as("control: other species untouched").isZero();
        assertThat(counter.get(ParticleType.SPORE))
                .as("control: other species untouched").isZero();
    }

    @Test
    void snapshotDeltaIsPlusOneOnlyForTheIncrementedOrdinal() {
        // Firewall (O4): assert a before/after DELTA around one increment — never an
        // accumulated total. This still pins the ordinal MAPPING (which slot each species
        // lands in) without asserting any cumulative magnitude.
        SpeciesSpawnCounter counter = new SpeciesSpawnCounter();
        long[] before = counter.snapshot();

        counter.increment(ParticleType.SPORE);
        long[] after = counter.snapshot();

        assertThat(after[ParticleType.SPORE.ordinal()] - before[ParticleType.SPORE.ordinal()])
                .as("increment lands on the SPORE ordinal, delta exactly +1").isEqualTo(1L);
        assertThat(after[ParticleType.CATALYST.ordinal()] - before[ParticleType.CATALYST.ordinal()])
                .as("control: other ordinals unchanged").isZero();
        assertThat(after[ParticleType.MEMBRANE.ordinal()] - before[ParticleType.MEMBRANE.ordinal()])
                .as("control: other ordinals unchanged").isZero();
    }
}
