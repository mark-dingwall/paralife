package com.paralife.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

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
}
