package com.paralife.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: launch bots, verify they connect, register, and make decisions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",
        "paralife.tick.auto-start=true",
        "paralife.world.width=32",
        "paralife.world.height=32",
        "paralife.simulation.enabled=true"
})
class BotClientIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BotClientIntegrationTest.class);

    @LocalServerPort
    private int port;

    private final BotLauncher launcher = new BotLauncher();

    @AfterEach
    void tearDown() {
        launcher.shutdown();
    }

    @Test
    void nineBotsConnectAndMakeDecisions() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";
        List<BotClient> bots = launcher.launch(uri, 9);

        assertThat(bots).hasSize(9);

        // Verify all registered
        for (BotClient bot : bots) {
            assertThat(bot.isRegistered()).isTrue();
            assertThat(bot.getEntityId()).isNotNull();
        }

        // Let them run for ~2 seconds (about 20 ticks at 100ms)
        Thread.sleep(2000);

        // Verify all bots have received perceptions and submitted actions
        int totalPerceptions = 0;
        int totalActions = 0;
        for (BotClient bot : bots) {
            assertThat(bot.isConnected())
                    .as("Bot %s should still be connected", bot.getEntityId())
                    .isTrue();
            assertThat(bot.getPerceptionCount())
                    .as("Bot %s should have received perceptions", bot.getEntityId())
                    .isGreaterThan(0);
            assertThat(bot.getActionCount())
                    .as("Bot %s should have submitted actions", bot.getEntityId())
                    .isGreaterThan(0);

            totalPerceptions += bot.getPerceptionCount();
            totalActions += bot.getActionCount();
        }

        log.info("9 bots ran for ~20 ticks: {} total perceptions, {} total actions",
                totalPerceptions, totalActions);

        // Sanity: should have at least 5 perceptions per bot on average
        assertThat(totalPerceptions).isGreaterThan(9 * 5);
        assertThat(totalActions).isGreaterThan(9 * 5);
    }

    @Test
    void botsDisconnectCleanly() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";
        List<BotClient> bots = launcher.launch(uri, 3);

        // Wait for some ticks
        Thread.sleep(500);

        // Disconnect all
        launcher.shutdown();

        for (BotClient bot : bots) {
            assertThat(bot.isConnected()).isFalse();
        }
    }
}
