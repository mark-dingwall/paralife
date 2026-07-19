package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O3/O3b/O7/O7b — the frame CONTRACT. Seeded engine-direct fixture, ZERO ticks
 * advanced; expected values enumerated solely from the test-owned fixture (never
 * from a production census function). Permitted mechanism per CLAUDE.md:73/:77.
 */
class ObserverFrameBuilderTest {

    private final ObserverFrameBuilder builder = new ObserverFrameBuilder();

    private static WorldGrid grid16() {
        return new WorldGrid(new GridConfig(16, 16));
    }

    private static EnvironmentSnapshot emptyEnv() {
        return new EnvironmentSnapshot(List.of(), List.of(), List.of());
    }

    private static long[] noSpawns() {
        return new long[] {0, 0, 0};
    }

    @Test
    void worldFrameCarriesEveryOccupantWithKindAndCoordinates() {
        WorldGrid grid = grid16();
        grid.setEntity(1, 1, new Particle("p1", ParticleType.CATALYST, 45, 100));
        grid.setEntity(2, 2, new Nutrient("n1", 20));
        grid.setEntity(3, 3, new Entity.Rock("r1")); // rock excluded from world frame

        ObserverFrame.WorldFrame f = builder.buildWorld(
                12L, grid.snapshot(), emptyEnv(), Set.of(), noSpawns());

        assertThat(f.type()).isEqualTo("world");
        assertThat(f.schemaVersion()).isEqualTo(ObserverFrameBuilder.SCHEMA_VERSION);
        assertThat(f.tick()).isEqualTo(12L);
        assertThat(f.entities())
                .as("particle + nutrient present at their coords; rock excluded")
                .anySatisfy(e -> {
                    assertThat(e.kind()).isEqualTo("particle");
                    assertThat(e.x()).isEqualTo(1);
                    assertThat(e.y()).isEqualTo(1);
                    assertThat(e.species()).isEqualTo("CATALYST");
                    assertThat(e.energy()).isEqualTo(45);
                })
                .anySatisfy(e -> assertThat(e.kind()).isEqualTo("nutrient"))
                .noneSatisfy(e -> assertThat(e.kind()).isEqualTo("rock"));
    }

    /** Select the DTO at exact grid coordinates (entities carry no id on the wire). */
    private static ObserverFrame.EntityDto dtoAt(ObserverFrame.WorldFrame f, int x, int y) {
        return f.entities().stream().filter(e -> e.x() == x && e.y() == y)
                .findFirst().orElseThrow(() -> new AssertionError("no entity at " + x + "," + y));
    }

    @Test
    void brainedTrueForOwnedFalseForWild_particlesAndStructures() {
        WorldGrid grid = grid16();
        grid.setEntity(1, 1, new Particle("ownedP", ParticleType.CATALYST, 50, 100));
        grid.setEntity(2, 2, new Particle("wildP", ParticleType.SPORE, 50, 100));
        grid.setEntity(6, 6, new BondedPair("ownedBp", ParticleType.CATALYST, ParticleType.SPORE,
                60, 200, "pe", "se", 1, 1, 1));
        grid.setEntity(7, 7, new BondedPair("wildBp", ParticleType.MEMBRANE, ParticleType.SPORE,
                60, 200, "pe2", "se2", 1, 1, 1));

        // ownership set contains the particle AND the structure ids (remapEntity keeps the
        // controlling session across bond formation → structures are frequently brained).
        ObserverFrame.WorldFrame f = builder.buildWorld(
                1L, grid.snapshot(), emptyEnv(), Set.of("ownedP", "ownedBp"), noSpawns());

        assertThat(dtoAt(f, 1, 1).brained()).as("owned particle → true").isTrue();
        assertThat(dtoAt(f, 2, 2).brained()).as("wild particle → false (control)").isFalse();
        assertThat(dtoAt(f, 6, 6).brained()).as("owned bondedPair → true (O3 structures)").isTrue();
        assertThat(dtoAt(f, 7, 7).brained()).as("wild bondedPair → false (control)").isFalse();
    }

    @Test
    void envLayersProjectIntensityStrainAndLightningCoords() {
        WorldGrid grid = grid16();
        EnvironmentSnapshot env = new EnvironmentSnapshot(
                List.of(new EnvironmentSnapshot.EnvCell(1, 2, 180)), // toxin intensity magnitude
                List.of(new EnvironmentSnapshot.EnvCell(3, 4, 42)),  // mutagen strain id
                List.of(new Position(5, 6), new Position(7, 8)));    // this-tick lightning

        ObserverFrame.WorldFrame f = builder.buildWorld(9L, grid.snapshot(), env, Set.of(), noSpawns());

        assertThat(f.env().toxin())
                .containsExactly(new ObserverFrame.ToxinCell(1, 2, 180));
        assertThat(f.env().mutagen())
                .as("mutagen DTO carries strain id, not intensity")
                .containsExactly(new ObserverFrame.MutagenCell(3, 4, 42));
        assertThat(f.env().lightning())
                .containsExactly(new ObserverFrame.Coord(5, 6), new ObserverFrame.Coord(7, 8));
    }

    @Test
    void subtypeFieldsEmittedForBondedPairAndCompositeMember() {
        WorldGrid grid = grid16();
        grid.setEntity(4, 4, new BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE,
                60, 200, "pe", "se", 1, 1, 1));
        grid.setEntity(5, 5, new CompositeMember("cm", "c-7", ParticleType.MEMBRANE, Role.FEEDER, 33, 100));

        ObserverFrame.WorldFrame f = builder.buildWorld(
                1L, grid.snapshot(), emptyEnv(), Set.of(), noSpawns());

        assertThat(f.entities()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo("bondedPair");
            assertThat(e.primarySpecies()).isEqualTo("CATALYST");
            assertThat(e.secondarySpecies()).isEqualTo("SPORE");
        });
        assertThat(f.entities()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo("compositeMember");
            assertThat(e.species()).isEqualTo("MEMBRANE");
            assertThat(e.compositeId()).isEqualTo("c-7");
            assertThat(e.role()).isEqualTo("FEEDER");
        });
    }

    @Test
    void populationsCensusCountsBothSpeciesOfAPairAndIncludesZeroEnergyMember() {
        WorldGrid grid = grid16();
        grid.setEntity(1, 1, new Particle("p", ParticleType.CATALYST, 50, 100));
        grid.setEntity(2, 2, new BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE,
                60, 200, "pe", "se", 1, 1, 1));
        grid.setEntity(3, 3, new CompositeMember("cm", "c-1", ParticleType.MEMBRANE, Role.FEEDER, 0, 100)); // zero energy
        grid.setEntity(4, 4, new Nutrient("n", 10)); // excluded

        ObserverFrame.WorldFrame f = builder.buildWorld(
                1L, grid.snapshot(), emptyEnv(), Set.of(), noSpawns());

        // O7: the zero-energy member is PRESENT in entities (occupancy, not just counted)
        assertThat(dtoAt(f, 3, 3).kind()).isEqualTo("compositeMember");
        assertThat(dtoAt(f, 3, 3).energy()).as("zero-energy member still emitted, energy 0").isEqualTo(0);

        // particle CATALYST(+1); pair CATALYST(+1) & SPORE(+1); member MEMBRANE(+1); nutrient excluded
        assertThat(f.populations().get("CATALYST")).as("particle + pair-primary").isEqualTo(2);
        assertThat(f.populations().get("SPORE")).as("pair-secondary").isEqualTo(1);
        assertThat(f.populations().get("MEMBRANE"))
                .as("zero-energy composite member still counts — occupancy census, no liveness filter")
                .isEqualTo(1);
    }

    @Test
    void bootstrapCarriesRocksAndGridDimsOnly() {
        WorldGrid grid = grid16();
        grid.setEntity(3, 3, new Entity.Rock("r1"));
        grid.setEntity(1, 1, new Particle("p", ParticleType.CATALYST, 50, 100)); // NOT in bootstrap

        ObserverFrame.BootstrapFrame b = builder.buildBootstrap(grid.snapshot());

        assertThat(b.type()).isEqualTo("bootstrap");
        assertThat(b.schemaVersion()).isEqualTo(ObserverFrameBuilder.SCHEMA_VERSION);
        assertThat(b.grid().width()).isEqualTo(16);
        assertThat(b.grid().height()).isEqualTo(16);
        assertThat(b.rocks()).containsExactly(new ObserverFrame.RockDto(3, 3));
    }
}
