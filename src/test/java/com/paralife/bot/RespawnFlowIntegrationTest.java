package com.paralife.bot;

import com.paralife.engine.BotRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Respawn flow smoke test. A bot connects, registers, and stays alive across
 * multiple tick cycles. The plan's primary invariant is that the session
 * remains open — specifically that the BotClient does NOT close the session
 * on decoding a {@code vD} event (SCHEMA §8.4 Died) but instead waits a
 * randomised cooldown and re-registers.
 *
 * <p>We don't force-kill the bot here (that would require reaching into
 * server internals); instead we verify the FSM glue — connection, register,
 * sustained perception/action flow over a ~2s window. The D-33 gate already
 * guards the upgrade side; this test guards the happy-path wire dance with
 * a real codec and real server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=50",
        "paralife.tick.auto-start=true",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.simulation.enabled=true"
})
class RespawnFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    BotRegistry botRegistry;

    @Test
    void botConnectsRegistersAndStaysAliveAcrossTicks() throws Exception {
        BotClient bot = new BotClient("ws://localhost:" + port + "/ws/world",
                'S', new HeuristicBrain(70), 100L, 50L);
        try {
            bot.connect();
            assertTrue(bot.awaitConnected(5000),
                    "Bot must connect within 5s");
            assertTrue(bot.awaitRegistered(5000),
                    "Bot must receive S frame (sync) within 5s of connect");

            // Let it run for 2s (~40 ticks at 50ms interval).
            Thread.sleep(2000);

            assertTrue(bot.isConnected(),
                    "Bot must remain connected across tick cycles (respawn FSM keeps session open)");
            assertTrue(bot.getPerceptionCount() > 0,
                    "Bot must receive T frames (perceptions) during the window");
        } finally {
            bot.disconnect();
        }
    }

    /**
     * Phase 15.2 — Phase 15 UAT Test 7 gate. Drives the full own-death →
     * respawn loop end-to-end:
     *
     * <ol>
     *   <li>Bot connects and receives initial S frame.</li>
     *   <li>Test forces a death via {@link BotRegistry#unregisterByEntity} —
     *       the exact path {@code DeathFinalizer} / {@code
     *       SimulationEngine.cleanupCompositeMemberCellViaFinalizer} use.</li>
     *   <li>Next tick {@code TickBroadcaster} drains the DeathNotice queue and
     *       emits a terminal {@code vD} frame.</li>
     *   <li>{@link BotClient#handleDeath} clears entityId, schedules respawn
     *       register after cooldown+jitter.</li>
     *   <li>Server resolves with a fresh {@code S|<newEntityId>}, bot's
     *       respawnCount increments.</li>
     * </ol>
     */
    @Test
    void botReceivesVDAndRespawnsAfterForcedDeath() throws Exception {
        BotClient bot = new BotClient("ws://localhost:" + port + "/ws/world",
                'S', new HeuristicBrain(70), 100L, 50L);
        try {
            bot.connect();
            assertTrue(bot.awaitConnected(5000), "Bot must connect within 5s");
            assertTrue(bot.awaitRegistered(5000),
                    "Bot must receive initial S frame within 5s");

            String firstEntityId = bot.getEntityId();
            assertThat(firstEntityId).isNotNull();

            // Force a death via the exact same path the engine uses. Queues a
            // DeathNotice; next tick broadcast ships vD to the session.
            botRegistry.unregisterByEntity(firstEntityId);

            // Wait up to 5s for the respawn loop to complete.
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && bot.getRespawnCount() == 0) {
                Thread.sleep(50);
            }

            assertTrue(bot.isConnected(),
                    "Session must remain open across the respawn (FSM keeps session)");
            assertThat(bot.getRespawnCount())
                    .as("Bot must receive at least one respawn S frame after vD")
                    .isGreaterThanOrEqualTo(1);
            assertThat(bot.getEntityId())
                    .as("New entityId after respawn — different from pre-death id")
                    .isNotEqualTo(firstEntityId);
        } finally {
            bot.disconnect();
        }
    }
}
