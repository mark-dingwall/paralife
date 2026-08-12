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
 * E-2a/b — mutagen radius cap (EARS-3) and cross-outbreak gossip-source filter
 * (EARS-4). Split out of {@code MutagenTest} because {@code max-radius=3} and
 * {@code zone-decay-ticks=500} cannot coexist with that class's property set
 * (see task-2 brief).
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
        "paralife.simulation.events.mutagen.max-radius=3",
        "paralife.bonding.bonding-probability=0.0",
        "paralife.composite.dissolution-chance=0.0"
})
class MutagenRadiusTest {

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

    // ── EARS-3: radius cap ──────────────────────────────────────────

    @Test
    void gossipStaysWithinChebyshevRadiusOfOrigin() {
        env.forceSpawnMutagenForTest(10L, new Position(8, 8), 100, 300);

        // Advance enough ticks that an uncapped front would pass distance 3
        // (>=5 ticks on a 16x16 world reaches distance 8 unbounded).
        for (long t = 11L; t <= 15L; t++) {
            env.advanceMutagenForTest(t);
        }

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                if (env.mutagenStrainAtForTest(new Position(x, y)) == 0) continue;
                int dx = Math.min(Math.abs(x - 8), 16 - Math.abs(x - 8));
                int dy = Math.min(Math.abs(y - 8), 16 - Math.abs(y - 8));
                int chebyshev = Math.max(dx, dy);
                assertThat(chebyshev)
                        .as("cell (%d,%d) chebyshev distance from origin", x, y)
                        .isLessThanOrEqualTo(3);
            }
        }

        // Positive control: diagonal corner (11,11) is Chebyshev 3 but Euclidean
        // 4.24. A Euclidean cap would leave it uncolonized — the diagonal is the
        // only fixture that discriminates the two metrics.
        assertThat(env.mutagenStrainAtForTest(new Position(11, 11)))
                .as("diagonal corner at chebyshev 3 (euclidean 4.24)")
                .isGreaterThan(0);
    }

    @Test
    void gossipRadiusWrapsToroidally() {
        env.forceSpawnMutagenForTest(10L, new Position(0, 0), 100, 300);

        for (long t = 11L; t <= 15L; t++) {
            env.advanceMutagenForTest(t);
        }

        // (15,15) is toroidal Chebyshev 1 from (0,0) on a 16x16 world — must be
        // colonized. A non-toroidal (absolute-difference) cap would leave it clean.
        assertThat(env.mutagenStrainAtForTest(new Position(15, 15)))
                .as("toroidal neighbor (15,15), chebyshev 1")
                .isGreaterThan(0);

        // (4,0) is Chebyshev 4 from (0,0) — outside the radius-3 cap.
        assertThat(env.mutagenStrainAtForTest(new Position(4, 0)))
                .as("(4,0), chebyshev 4 — outside cap")
                .isEqualTo(0);
    }

    // ── EARS-4: no cross-outbreak ratchet ───────────────────────────

    @Test
    void gossipDoesNotSourceFromEarlierOutbreakCells() {
        env.forceSpawnMutagenForTest(10L, new Position(8, 8), 100, 300);

        Position legacy = new Position(11, 8); // chebyshev 3 from origin — inside cap
        env.stampMutagenForTest(legacy, 100);
        env.setMutagenLastReinforcedTickForTest(legacy, 9L); // before spawnTick=10

        env.advanceMutagenForTest(11L); // exactly one tick

        // Legacy cell's neighbors, all within the radius cap: must stay clean.
        // A broken source filter would colonize these from the legacy cell.
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
