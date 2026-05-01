package com.paralife.engine;

import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19 SCALE-06 (D-06): Bit-exact placement determinism test.
 *
 * <p>Two runs with the same {@code paralife.simulation.spawn.seed} and the same
 * registration order must produce byte-identical placement sequences. This test
 * drives N registrations through {@link WorldWebSocketHandler#attemptPlacementForTest}
 * (the public test seam defined in REVIEWS CONSENSUS-H6) twice — resetting the seed
 * between runs — and asserts the resulting {@link Position} lists are equal element-by-element.
 *
 * <p>The test relies on the O(1) sparse-set index introduced by SCALE-06. The legacy
 * 50-retry random scan was NOT seeded consistently across runs (different iteration
 * orders due to concurrent occupation changes), so byte-exact reproducibility was
 * impossible before this plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.simulation.spawn.seed=42"
})
@DirtiesContext
class PlacementDeterminismTest {

    private static final int PLACEMENT_COUNT = 20;
    private static final Entity.ParticleType TYPE = Entity.ParticleType.CATALYST;
    private static final int INITIAL_ENERGY = 50;

    @Autowired
    private WorldWebSocketHandler handler;

    @Autowired
    private EligibleCellIndex eligibleCellIndex;

    @Autowired
    private WorldGrid worldGrid;

    /**
     * Two seeded runs with the same insertion order must produce identical positions.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Run-1: reset seed, place N entities, record positions.</li>
     *   <li>Clear grid cells directly via the injected WorldGrid bean.</li>
     *   <li>Rebuild EligibleCellIndex from the cleared grid.</li>
     *   <li>Run-2: reset seed to same value, place N entities with new IDs, record positions.</li>
     *   <li>Assert both position lists are element-by-element equal (D-06 bit-exact contract).</li>
     * </ol>
     */
    @Test
    void sameSpawnSeedProducesBitIdenticalPlacements() {
        // Run 1: start from a known-seeded state.
        handler.resetSeed();
        List<Position> run1 = placeBatch("r1-");

        // Restore to clean grid: clear only the cells placed by run1 (preserves rocks),
        // rebuild index from the restored state, reset RNG.
        for (Position pos : run1) {
            worldGrid.clearEntity(pos.x(), pos.y());
        }
        eligibleCellIndex.rebuildForTest();
        handler.resetSeed();

        // Run 2: same seed, same grid state (rocks preserved), same index state.
        List<Position> run2 = placeBatch("r2-");

        assertThat(run2)
                .as("Bit-exact placement determinism: same seed + same order must yield same positions")
                .isEqualTo(run1);
    }

    private List<Position> placeBatch(String idPrefix) {
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < PLACEMENT_COUNT; i++) {
            Optional<Position> placed = handler.attemptPlacementForTest(
                    idPrefix + i, TYPE, INITIAL_ENERGY);
            assertThat(placed)
                    .as("Placement %d should succeed on a 16×16 grid (only %d entities placed)",
                            i, i)
                    .isPresent();
            positions.add(placed.get());
        }
        return positions;
    }
}
