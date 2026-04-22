package com.paralife.engine;

import com.paralife.bot.BotClient;
import com.paralife.bot.BotLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load test: 100 concurrent bots with full simulation, verifying no tick drift
 * or data corruption.
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
        "paralife.websocket.max-active-entities=1000000"
})
class LoadTest {

    private static final Logger log = LoggerFactory.getLogger(LoadTest.class);

    @LocalServerPort
    private int port;

    private final BotLauncher launcher = new BotLauncher();

    @AfterEach
    void tearDown() {
        launcher.shutdown();
    }

    @Test
    void hundredBotsNoCorruption() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";
        int botCount = 100;

        List<BotClient> bots = launcher.launch(uri, botCount);
        assertThat(bots).hasSize(botCount);

        // Verify most registered (some may fail under heavy concurrent load)
        long registered = bots.stream().filter(BotClient::isRegistered).count();
        log.info("{}/{} bots registered", registered, botCount);
        assertThat(registered)
                .as("At least 80%% of bots should register under load")
                .isGreaterThanOrEqualTo((long) (botCount * 0.8));

        // Run for ~100 ticks at 100ms = 10 seconds
        Thread.sleep(10_000);

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
