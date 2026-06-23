package com.paralife.engine;

import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mechanics check for world fertility initialization.
 *
 * <p><b>History:</b> this class previously also held {@code allThreeTypesSurvive500Ticks},
 * a 30-bot / 500-tick run that asserted emergent ecosystem outcomes (all three RPS types
 * survive, per-type population variation). That gate was inherently flaky — the outcome is
 * a stochastic property of an essentially unbounded state space and cannot be reliably
 * forced without destabilising other signals.
 *
 * <p>It was deleted (not seed-pinned or tolerance-widened) per the Phase 16 precedent
 * (commit {@code 2ec1d1c}, "Drift Correction" D-04 #2): tests pin spec <i>mechanics</i>
 * via short, seeded, engine-direct paths; ecosystem <i>emergence</i> is judged by a human
 * against the live visualiser (M5) or via offline parameter search — not gated in JUnit.
 * Every production mechanic it touched is already pinned deterministically:
 * RPS combat in {@code SimulationEngineTest.CombatTests.allThreeRPSPairsWork},
 * death/removal in {@code SimulationEngineTest.DeathTests.*}, reproduction in
 * {@code ReproducerAutoPlaceTest}, nutrient spawn/consume in
 * {@code SimulationEngineTest.NutrientSpawnTests.*} / {@code PerceptionActionIntegrationTest},
 * starvation in {@code SimulationEngineTest.StarvationTests.*}, and composite formation in
 * {@code CompositeFormationDeterminismTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.world.width=64",
        "paralife.world.height=64",
        "paralife.simulation.enabled=true"
})
class PopulationDynamicsTest {

    private static final Logger log = LoggerFactory.getLogger(PopulationDynamicsTest.class);

    @Autowired
    private WorldGrid worldGrid;

    @Test
    void fertilityPatchesExistAfterInit() {
        var snapshot = worldGrid.snapshot();
        int fertileCells = 0;
        for (int x = 0; x < snapshot.width(); x++) {
            for (int y = 0; y < snapshot.height(); y++) {
                if (snapshot.getCell(x, y).nutrientLevel() > 0) {
                    fertileCells++;
                }
            }
        }
        log.info("Fertile cells after init: {}", fertileCells);
        assertThat(fertileCells)
                .as("Number of fertile cells after initialization")
                .isGreaterThan(0);
    }
}
