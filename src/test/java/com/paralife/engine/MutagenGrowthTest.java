package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Mutagen bloom bounds. The radius cap (old EARS-3) was dropped in favour of a
 * grow-window: the bloom gossips outward until {@code growTicks} elapse, then the
 * front freezes — that time bound is now the natural size cap. Also pins the
 * cross-outbreak gossip-source filter (EARS-4). {@code gossip-probability=1.0} and
 * {@code zone-decay-ticks=500} make growth deterministic and decay-free here.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=true",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.mutagen.damage-per-tick=2",
        "paralife.simulation.events.mutagen.infection-duration-min=10",
        "paralife.simulation.events.mutagen.infection-duration-max=10",
        "paralife.simulation.events.mutagen.buff-duration-multiplier=5",
        "paralife.simulation.events.mutagen.cure-ticks=3",
        "paralife.simulation.events.mutagen.attack-cure-reduction=3",
        "paralife.simulation.events.mutagen.gossip-probability=1.0",
        "paralife.simulation.events.mutagen.strain-mutation-chance=0.0",
        "paralife.simulation.events.mutagen.zone-decay-ticks=500",
        "paralife.simulation.events.mutagen.outbreak-lifetime-ticks=20",
        "paralife.bonding.bonding-probability=0.0",
        "paralife.composite.dissolution-chance=0.0"
})
class MutagenGrowthTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine env;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired BuffRegistry buffRegistry;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        compositeRegistry.clear();
        buffRegistry.clear();
        env.resetMutagenStateForTest();
    }

    // ── grow window: gossip freezes once growTicks elapse ───────────────

    @Test
    void gossipStopsColonizingOnceGrowTicksElapse() {
        // growTicks=3 → isGrowing is true for ticks 1,2 and false from tick 3 on.
        env.forceSpawnMutagenForTest(0L, new Position(8, 8), 100, 100, 3);

        env.advanceMutagenForTest(1L);
        int afterTick1 = env.snapshot().mutagen().size();
        env.advanceMutagenForTest(2L);
        int afterTick2 = env.snapshot().mutagen().size();

        // Ticks 3..6 are past the grow window — gossip must not add cells.
        for (long t = 3L; t <= 6L; t++) {
            env.advanceMutagenForTest(t);
        }
        int afterTick6 = env.snapshot().mutagen().size();

        // Positive control: the bloom was genuinely growing while in the window.
        assertThat(afterTick2)
                .as("bloom grows while within the grow window")
                .isGreaterThan(afterTick1);
        // The gate under test: no colonization after growTicks (zone-decay=500 so
        // the count can't move for any other reason within this span).
        assertThat(afterTick6)
                .as("front freezes once growTicks elapse — no new colonization")
                .isEqualTo(afterTick2);
    }

    // ── EARS-4: no cross-outbreak ratchet ───────────────────────────

    @Test
    void gossipDoesNotSourceFromEarlierOutbreakCells() {
        env.forceSpawnMutagenForTest(10L, new Position(8, 8), 100, 300);

        Position legacy = new Position(11, 8); // a surviving cell from an earlier outbreak
        env.stampMutagenForTest(legacy, 100);
        env.setMutagenLastReinforcedTickForTest(legacy, 9L); // before spawnTick=10

        env.advanceMutagenForTest(11L); // exactly one tick

        // Legacy cell's neighbors (Chebyshev 2-3 from origin, so out of the origin's
        // own one-tick reach) must stay clean: a broken source filter would colonize
        // them FROM the legacy cell.
        assertThat(env.mutagenStrainAtForTest(new Position(10, 7))).isEqualTo(0);
        assertThat(env.mutagenStrainAtForTest(new Position(10, 8))).isEqualTo(0);
        assertThat(env.mutagenStrainAtForTest(new Position(10, 9))).isEqualTo(0);
        assertThat(env.mutagenStrainAtForTest(new Position(11, 7))).isEqualTo(0);
        assertThat(env.mutagenStrainAtForTest(new Position(11, 9))).isEqualTo(0);

        // Positive control: the origin's own 8 neighbors ARE colonized after one
        // tick — this also pins the filter as >= spawnTick, not >: the origin's
        // own timestamp equals spawnTick, so a strict > filter would kill the
        // whole bloom.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                assertThat(env.mutagenStrainAtForTest(new Position(8 + dx, 8 + dy)))
                        .as("origin neighbor (%d,%d)", 8 + dx, 8 + dy)
                        .isGreaterThan(0);
            }
        }
    }
}
