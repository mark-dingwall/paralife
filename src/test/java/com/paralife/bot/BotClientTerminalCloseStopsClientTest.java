package com.paralife.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.1 regression: a <b>failed reconnect</b> must stop the bot's underlying Jetty
 * {@link org.eclipse.jetty.websocket.client.WebSocketClient}, releasing its
 * scheduler/selector/executor pools.
 *
 * <p>Before the fix, {@code reconnect()} swallowed the failure and left the client
 * running. This is the dominant source of the lingering {@code HttpClient@…-scheduler}
 * threads the Phase 22.1 leak probe found (19 in a hung shared-JVM fleet teardown): a
 * tokened bot reaped by server idle-timeout takes the {@code @OnWebSocketClose} reconnect
 * branch, but at teardown the server is gone, so re-bind fails — and the client was never
 * released. {@code reconnect()} now calls {@code stopClientAsync()} on failure.
 *
 * <p>Driven directly (no server) via the package-private {@code reconnect()} seam against a
 * dead port, so the failure is deterministic: {@code connect()} lazily starts the Jetty
 * client, then the upgrade is refused.
 */
class BotClientTerminalCloseStopsClientTest {

    @Test
    void failedReconnectStopsStartedClient() throws Exception {
        // ws://localhost:1 — nothing listening; the upgrade is refused promptly.
        BotClient bot = new BotClient("ws://localhost:1/ws/world", 'C', new HeuristicBrain(3));
        assertThat(bot.isClientStopped())
                .as("client has not been created/stopped yet")
                .isFalse();

        // Drives connect() → start client → upgrade fails → catch → stopClientAsync().
        bot.reconnect();

        // stopClientAsync() releases the client off-thread; await up to 5s.
        long deadline = System.currentTimeMillis() + 5000;
        while (!bot.isClientStopped() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(bot.isClientStopped())
                .as("a failed reconnect must stop the client it started")
                .isTrue();
    }
}
