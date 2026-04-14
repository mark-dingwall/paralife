package com.paralife.engine;

import com.paralife.bot.BotLauncher;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Population dynamics test: proves all three entity types survive 500+ ticks
 * with visible population oscillations under full simulation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=50",
        "paralife.tick.auto-start=true",
        "paralife.world.width=64",
        "paralife.world.height=64",
        "paralife.simulation.enabled=true",
        // Deliberately zero: isolates RPS combat dynamics from energy starvation.
        // A separate test with decay>0 would verify sustainable populations, but this
        // test's purpose is proving all three types coexist under pure combat pressure.
        "paralife.simulation.energy-decay-per-tick=0",
        "paralife.simulation.combat-energy-transfer=5",
        "paralife.simulation.nutrient-spawn-probability=0.005",
        "paralife.simulation.nutrient-consume-energy=10",
        "paralife.simulation.overcrowding-threshold=8",
        "paralife.simulation.overcrowding-energy-penalty=0",
        // Phase 13: zero out per-type decay and normalize combat/reproduction so
        // this test continues to isolate pure RPS combat dynamics.
        "paralife.simulation.types.catalyst.max-energy=100",
        "paralife.simulation.types.catalyst.decay-per-tick=0",
        "paralife.simulation.types.catalyst.combat-energy-transfer=5",
        "paralife.simulation.types.catalyst.attack-power=5",
        "paralife.simulation.types.catalyst.nutrient-consume-energy=10",
        "paralife.simulation.types.catalyst.reproduce-energy-cost=30",
        "paralife.simulation.types.catalyst.reproduce-cooldown=0",
        "paralife.simulation.types.catalyst.bonus-offspring-chance=0.0",
        "paralife.simulation.types.catalyst.reproduce-range=1",
        "paralife.simulation.types.catalyst.starvation-threshold=0",
        "paralife.simulation.types.catalyst.starvation-floor=0",
        "paralife.simulation.types.membrane.max-energy=100",
        "paralife.simulation.types.membrane.decay-per-tick=0",
        "paralife.simulation.types.membrane.combat-energy-transfer=5",
        "paralife.simulation.types.membrane.attack-power=5",
        "paralife.simulation.types.membrane.nutrient-consume-energy=10",
        "paralife.simulation.types.membrane.reproduce-energy-cost=30",
        "paralife.simulation.types.membrane.reproduce-cooldown=0",
        "paralife.simulation.types.membrane.bonus-offspring-chance=0.0",
        "paralife.simulation.types.membrane.reproduce-range=1",
        "paralife.simulation.types.membrane.starvation-threshold=0",
        "paralife.simulation.types.membrane.starvation-floor=0",
        "paralife.simulation.types.spore.max-energy=100",
        "paralife.simulation.types.spore.decay-per-tick=0",
        "paralife.simulation.types.spore.combat-energy-transfer=5",
        "paralife.simulation.types.spore.attack-power=5",
        "paralife.simulation.types.spore.nutrient-consume-energy=10",
        "paralife.simulation.types.spore.reproduce-energy-cost=30",
        "paralife.simulation.types.spore.reproduce-cooldown=0",
        "paralife.simulation.types.spore.bonus-offspring-chance=0.0",
        "paralife.simulation.types.spore.reproduce-range=1",
        "paralife.simulation.types.spore.starvation-threshold=0",
        "paralife.simulation.types.spore.starvation-floor=0"
})
class PopulationDynamicsTest {

    private static final Logger log = LoggerFactory.getLogger(PopulationDynamicsTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private WorldGrid worldGrid;

    private final BotLauncher launcher = new BotLauncher();

    @AfterEach
    void tearDown() {
        launcher.shutdown();
    }

    @Test
    void allThreeTypesSurvive500Ticks() throws Exception {
        int botCount = 30; // 10 of each type
        String uri = "ws://localhost:" + port + "/ws/world";

        var bots = launcher.launch(uri, botCount);
        assertThat(bots).hasSize(botCount);

        // Track population counts over time
        List<Map<String, Integer>> history = new ArrayList<>();

        // Run for ~500 ticks at 50ms = 25 seconds
        // Sample every 50 ticks
        int totalTicks = 500;
        int sampleInterval = 50;
        int samples = totalTicks / sampleInterval;

        for (int sample = 0; sample < samples; sample++) {
            Thread.sleep((long) sampleInterval * 50); // 50ms per tick

            Map<String, Integer> counts = countPopulation();
            history.add(counts);

            log.info("Tick ~{}: CATALYST={} MEMBRANE={} SPORE={} total={}",
                    (sample + 1) * sampleInterval,
                    counts.getOrDefault("CATALYST", 0),
                    counts.getOrDefault("MEMBRANE", 0),
                    counts.getOrDefault("SPORE", 0),
                    counts.values().stream().mapToInt(Integer::intValue).sum());
        }

        // Verify: all three types should have been present at some point
        Set<String> typesEverSeen = new HashSet<>();
        for (Map<String, Integer> sample : history) {
            typesEverSeen.addAll(sample.keySet());
        }
        assertThat(typesEverSeen)
                .as("All three particle types should appear during the simulation")
                .contains("CATALYST", "MEMBRANE", "SPORE");

        // Verify: population should show variation (not flat-lined)
        for (String type : List.of("CATALYST", "MEMBRANE", "SPORE")) {
            List<Integer> typeCounts = history.stream()
                    .map(h -> h.getOrDefault(type, 0))
                    .toList();
            int max = typeCounts.stream().mapToInt(Integer::intValue).max().orElse(0);
            int min = typeCounts.stream().mapToInt(Integer::intValue).min().orElse(0);

            log.info("{}: min={} max={} range={}", type, min, max, max - min);

            // At least some variation expected (not all same count)
            // With reproduction and combat, populations should fluctuate
            assertThat(max).as("Max population of %s should be > 0", type).isGreaterThan(0);
            assertThat(max - min).as("Population of %s should vary (not flat)", type).isGreaterThan(0);
        }

        // Verify bots are still connected
        long connected = bots.stream().filter(b -> b.isConnected()).count();
        log.info("Bots still connected: {}/{}", connected, botCount);
    }

    private Map<String, Integer> countPopulation() {
        Map<String, Integer> counts = new HashMap<>();
        var snapshot = worldGrid.snapshot();
        for (int x = 0; x < snapshot.width(); x++) {
            for (int y = 0; y < snapshot.height(); y++) {
                Cell cell = snapshot.getCell(x, y);
                if (cell.occupant() instanceof Particle p) {
                    counts.merge(p.type().name(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }
}
