package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: seeds the grid with mixed particles, runs simulation ticks,
 * and verifies population dynamics are actually happening — entity counts change,
 * deaths occur, nutrients spawn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=50",
        "paralife.tick.auto-start=false",      // We'll drive ticks manually
        "paralife.world.width=32",
        "paralife.world.height=32",
        "paralife.simulation.energy-decay-per-tick=2",
        "paralife.simulation.combat-energy-transfer=15",
        "paralife.simulation.nutrient-spawn-probability=0.01",
        "paralife.simulation.nutrient-consume-energy=5",
        "paralife.simulation.enabled=true",
        "paralife.simulation.overcrowding-threshold=6",
        "paralife.simulation.overcrowding-energy-penalty=2"
})
class SimulationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SimulationIntegrationTest.class);

    @Autowired
    private WorldGrid worldGrid;

    @Autowired
    private SimulationEngine simulationEngine;

    @BeforeEach
    void resetGrid() {
        worldGrid.clear();
    }

    @Test
    void populationChangesOverTime() {
        // Seed grid with particles in clusters
        int particlesPerType = 30;
        seedParticles(particlesPerType);

        Map<ParticleType, Integer> initialCounts = countParticlesByType();
        int initialTotal = initialCounts.values().stream().mapToInt(i -> i).sum();
        assertThat(initialTotal).isEqualTo(particlesPerType * 3);

        log.info("Initial population: {}", initialCounts);

        // Run 50 ticks
        for (int tick = 1; tick <= 50; tick++) {
            simulationEngine.processTick(tick);

            if (tick % 10 == 0) {
                Map<ParticleType, Integer> counts = countParticlesByType();
                int nutrients = countNutrients();
                log.info("Tick {}: particles={}, nutrients={}", tick, counts, nutrients);
            }
        }

        // After 50 ticks with decay=2, some particles should have died
        Map<ParticleType, Integer> finalCounts = countParticlesByType();
        int finalTotal = finalCounts.values().stream().mapToInt(i -> i).sum();

        log.info("Final population after 50 ticks: {} (started at {})", finalCounts, initialCounts);

        // Entity count should have changed (deaths from decay + combat)
        assertThat(finalTotal).isLessThan(initialTotal);

        // Nutrients should have spawned on the 32x32 grid
        assertThat(countNutrients()).isGreaterThan(0);
    }

    @Test
    void deadEntitiesActuallyRemoved() {
        // Place a single particle with very low energy
        Particle dying = new Particle("dying-1", ParticleType.CATALYST, 3);
        worldGrid.setEntity(15, 15, dying);

        // After 2 ticks at decay=2: energy 3→1→0 → dead → removed
        simulationEngine.processTick(1);
        assertThat(worldGrid.getCell(15, 15).hasOccupant()).isTrue();

        simulationEngine.processTick(2);
        assertThat(worldGrid.getCell(15, 15).isEmpty())
                .as("Particle with energy 0 should be removed from grid")
                .isTrue();
    }

    @Test
    void nutrientsAppearOnEmptyCells() {
        // Grid is empty, run a few ticks — nutrients should appear
        // At 0.01 probability on 32*32=1024 cells, expect ~10 per tick
        for (int tick = 1; tick <= 10; tick++) {
            simulationEngine.processTick(tick);
        }

        int nutrients = countNutrients();
        assertThat(nutrients)
                .as("After 10 ticks, some nutrients should have spawned on 32x32 grid")
                .isGreaterThan(0);
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void seedParticles(int perType) {
        int id = 0;
        // Place each type in a different region to create interesting dynamics
        for (ParticleType type : ParticleType.values()) {
            int baseX = switch (type) {
                case CATALYST -> 5;
                case SPORE -> 15;
                case MEMBRANE -> 25;
            };
            for (int i = 0; i < perType; i++) {
                int x = (baseX + (i % 6)) % worldGrid.getWidth();
                int y = (5 + (i / 6)) % worldGrid.getHeight();
                // Only place if cell is empty
                if (worldGrid.getCell(x, y).isEmpty()) {
                    worldGrid.setEntity(x, y, Particle.spawn("p-" + (id++), type));
                }
            }
        }
    }

    private Map<ParticleType, Integer> countParticlesByType() {
        Map<ParticleType, Integer> counts = new EnumMap<>(ParticleType.class);
        for (ParticleType type : ParticleType.values()) {
            counts.put(type, 0);
        }
        for (int x = 0; x < worldGrid.getWidth(); x++) {
            for (int y = 0; y < worldGrid.getHeight(); y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Particle p) {
                    counts.merge(p.type(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private int countNutrients() {
        int count = 0;
        for (int x = 0; x < worldGrid.getWidth(); x++) {
            for (int y = 0; y < worldGrid.getHeight(); y++) {
                if (worldGrid.getCell(x, y).occupant() instanceof Nutrient) {
                    count++;
                }
            }
        }
        return count;
    }
}
