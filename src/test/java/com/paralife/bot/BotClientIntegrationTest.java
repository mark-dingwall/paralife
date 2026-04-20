package com.paralife.bot;

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
 * Plan 15-11: bot lifecycle over the codec-native protocol.
 *
 * <p>Migrated from the Jackson-JSON era — all wire I/O now flows through
 * {@link com.paralife.codec.PerceptionCodec}. The pre-Phase-15 JSON-parse
 * assertions have been removed; this test focuses on the BotClient lifecycle
 * (connect → register → receive perception → submit action) using
 * {@link BotLauncher}. Wire-protocol correctness is covered by
 * {@code WebSocketIntegrationTest} and {@code RespawnFlowIntegrationTest}.
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

        // Verify all registered (each received an S|<entityId> sync frame).
        for (BotClient bot : bots) {
            assertThat(bot.isRegistered())
                    .as("Bot should be registered after launch")
                    .isTrue();
            assertThat(bot.getEntityId())
                    .as("Registered bot must have an entityId from the sync frame")
                    .isNotNull();
        }

        // Let them run for ~2 seconds (about 20 ticks at 100ms).
        Thread.sleep(2000);

        // Each bot should have processed perception frames and submitted action frames.
        int totalPerceptions = 0;
        int totalActions = 0;
        for (BotClient bot : bots) {
            assertThat(bot.isConnected())
                    .as("Bot %s should still be connected", bot.getEntityId())
                    .isTrue();
            assertThat(bot.getPerceptionCount())
                    .as("Bot %s should have received tick frames", bot.getEntityId())
                    .isGreaterThan(0);
            assertThat(bot.getActionCount())
                    .as("Bot %s should have submitted action frames", bot.getEntityId())
                    .isGreaterThan(0);

            totalPerceptions += bot.getPerceptionCount();
            totalActions += bot.getActionCount();
        }

        log.info("9 bots ran for ~20 ticks: {} tick frames, {} action frames",
                totalPerceptions, totalActions);

        // Sanity: should have at least 5 tick frames per bot on average.
        assertThat(totalPerceptions).isGreaterThan(9 * 5);
        assertThat(totalActions).isGreaterThan(9 * 5);
    }

    @Test
    void botsDisconnectCleanly() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";
        List<BotClient> bots = launcher.launch(uri, 3);

        Thread.sleep(500);

        launcher.shutdown();

        for (BotClient bot : bots) {
            assertThat(bot.isConnected()).isFalse();
        }
    }
}
