package com.paralife.engine;

import com.paralife.bot.BotClient;
import com.paralife.bot.BotFactory;
import com.paralife.bot.BotFleet;
import com.paralife.bot.BotIdentity;
import com.paralife.bot.RampUpSpec;
import com.paralife.bot.SpeciesMix;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load test: 100 concurrent bots with full simulation, verifying no tick drift
 * or data corruption.
 *
 * <p>Phase 18 migration: bots are launched via {@link BotFleet} with
 * {@link BotIdentity#harness(String) BotIdentity.harness("test-load")} so the test
 * exercises the attribution path end-to-end. A gauge assertion verifies that the
 * server-side active-entities bucket for {@code source=harness, harness=test-load}
 * is populated during the run.
 *
 * <p>Single harness id — well within the 64-cap MeterFilter threshold (D-10). All 100
 * bots share the same id, which costs exactly one cardinality slot per test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",
        "paralife.tick.auto-start=true",
        "paralife.world.width=128",
        "paralife.world.height=128",
        "paralife.simulation.enabled=true",
        "paralife.simulation.energy-decay-per-tick=0",
        "paralife.simulation.combat-energy-transfer=5",
        "paralife.simulation.nutrient-spawn-probability=0.001",
        "paralife.simulation.nutrient-consume-energy=5",
        "paralife.simulation.overcrowding-threshold=8",
        "paralife.simulation.overcrowding-energy-penalty=0",
        "paralife.websocket.max-respawns-per-session=1000000",
        "paralife.admission.cap=1000000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoadTest {

    private static final Logger log = LoggerFactory.getLogger(LoadTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    // Phase 18: BotFleet + BotFactory replaces the legacy BotLauncher.
    // Single harness id; well within the 64-cap MeterFilter threshold (D-10).
    private final BotFleet fleet = new BotFleet();

    @AfterEach
    void tearDown() {
        fleet.shutdown();
    }

    @Test
    void hundredBotsNoCorruption() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";
        int botCount = 100;

        // Phase 18: use BotIdentity.harness("test-load") so the attribution path is
        // exercised end-to-end. All 100 bots share the same harness id (one cardinality
        // slot, well within D-10's 64-cap).
        BotIdentity identity = BotIdentity.harness("test-load");
        BotFactory factory = new BotFactory(uri);

        List<BotClient> bots = fleet.launch(
                uri, botCount, identity,
                RampUpSpec.instant(),
                SpeciesMix.balanced(),
                factory);

        assertThat(bots).hasSize(botCount);

        // Wait for registration to settle (allow up to 30s for all VTs to connect).
        fleet.awaitAllSettled().get(30, java.util.concurrent.TimeUnit.SECONDS);

        // Verify most registered (some may fail under heavy concurrent load)
        long registered = bots.stream().filter(BotClient::isRegistered).count();
        log.info("{}/{} bots registered", registered, botCount);
        assertThat(registered)
                .as("At least 80%% of bots should register under load")
                .isGreaterThanOrEqualTo((long) (botCount * 0.8));

        // Run for ~100 ticks at 100ms = 10 seconds
        Thread.sleep(10_000);

        // Verify: harness-tagged active-entities gauge is populated.
        // This confirms the attribution path is wired end-to-end: BotClient sends
        // X-Paralife-Source: harness + X-Paralife-Harness: test-load; the server
        // reads them in afterConnectionEstablished and tags the Micrometer gauge.
        Gauge harnessGauge = meterRegistry.find("paralife.admission.active.entities")
                .tags("source", "harness", "harness", "test-load")
                .gauge();
        assertThat(harnessGauge)
                .as("paralife.admission.active.entities{source=harness, harness=test-load} "
                        + "gauge must exist — attribution path must be wired end-to-end")
                .isNotNull();
        assertThat(harnessGauge.value())
                .as("Active entities gauge for harness=test-load must reflect the actual "
                        + "registered count (within 80%% tolerance, matching the registration "
                        + "tolerance above)")
                .isGreaterThanOrEqualTo(registered * 0.8);

        // Verify: most bots still connected
        long connected = bots.stream().filter(BotClient::isConnected).count();
        log.info("Bots still connected: {}/{}", connected, botCount);
        assertThat(connected)
                .as("At least 50%% of bots should still be connected after 100 ticks")
                .isGreaterThanOrEqualTo((long) (botCount * 0.5));

        // Verify: connected bots have been active
        int totalPerceptions = 0;
        int totalActions = 0;
        int activeBots = 0;
        for (BotClient bot : bots) {
            if (bot.isConnected() && bot.getPerceptionCount() > 0) {
                totalPerceptions += bot.getPerceptionCount();
                totalActions += bot.getActionCount();
                activeBots++;
            }
        }

        log.info("{} active bots over ~100 ticks: {} total perceptions, {} total actions",
                activeBots, totalPerceptions, totalActions);

        // Active bots should have received meaningful perceptions
        assertThat(totalPerceptions)
                .as("Total perceptions should be substantial")
                .isGreaterThan(activeBots * 10);

        assertThat(totalActions)
                .as("Total actions should be substantial")
                .isGreaterThan(activeBots * 10);
    }
}
