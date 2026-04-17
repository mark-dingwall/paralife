package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corpse-composting (D-24, D-25) verification.
 *
 * <p>Asserts EnvironmentEngine's {@link EnvironmentEngine#applyCompost} bumps
 * Cell.nutrientLevel by {@code compost.full-strength} at the death cell and
 * by {@code compost.half-strength} at each of the 8 Moore neighbors, clamped
 * to {@link FertilityConfig#maxLevel()}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.compost.full-strength=30",
        "paralife.simulation.events.compost.half-strength=15",
        "paralife.simulation.fertility.max-level=100"
})
class CompostTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired FertilityConfig fertilityConfig;

    @BeforeEach
    void reset() {
        worldGrid.clear();
    }

    @Test
    void applyCompostBumpsCenterAndNeighbors() {
        Position death = new Position(5, 5);

        environmentEngine.applyCompost(death);

        // Center — full strength (30).
        assertThat(worldGrid.getCell(5, 5).nutrientLevel()).isEqualTo(30);
        // 8 Moore neighbors — half strength (15).
        for (Position n : worldGrid.getNeighbors(5, 5)) {
            assertThat(worldGrid.getCell(n.x(), n.y()).nutrientLevel())
                    .as("neighbor %s should receive half strength", n)
                    .isEqualTo(15);
        }
    }

    @Test
    void applyCompostClampsAtFertilityMaxLevel() {
        // Pre-seed with high nutrient to force clamp.
        int max = fertilityConfig.maxLevel();
        worldGrid.setCell(4, 4, new Cell(null, 0, max - 5));
        Position death = new Position(4, 4);

        environmentEngine.applyCompost(death);

        assertThat(worldGrid.getCell(4, 4).nutrientLevel())
                .as("clamped at maxLevel")
                .isEqualTo(max);
    }
}
