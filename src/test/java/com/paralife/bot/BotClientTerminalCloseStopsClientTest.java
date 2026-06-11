package com.paralife.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * PR#3 review H1: a reconnect that fires <em>after</em> teardown has begun must NOT stand
     * up a fresh Jetty client. The lifetime {@code clientStopped} CAS is one-shot; once
     * consumed, a newly-created client can never be released — so {@code reconnect()} must bail
     * on {@code shutdown} rather than re-enter {@code connect()}.
     *
     * <p>Reproduces the fleet-teardown interleave deterministically:
     * <ol>
     *   <li>A first failed reconnect creates + starts a client, fails the upgrade, releases it,
     *       and consumes the one-shot CAS (models a prior stop).</li>
     *   <li>{@code disconnect()} flips {@code shutdown} (client already null — CAS stays consumed).</li>
     *   <li>A previously-scheduled {@code reconnect()} fires. Pre-fix it stood up a fresh,
     *       unreleasable {@code WebSocketClient} (the leak). Post-fix the shutdown guard bails.</li>
     * </ol>
     */
    @Test
    void reconnectAfterShutdownDoesNotLeakFreshClient() throws Exception {
        BotClient bot = new BotClient("ws://localhost:1/ws/world", 'C', new HeuristicBrain(3));

        // 1. First failed reconnect: creates+starts a client, upgrade refused, releases it
        //    and consumes the one-shot clientStopped CAS.
        bot.reconnect();
        assertThat(bot.isClientStopped())
                .as("first failed reconnect consumed the one-shot stop CAS")
                .isTrue();
        assertThat(bot.hasLiveClient())
                .as("first client was released, none held")
                .isFalse();

        // 2. Teardown begins.
        bot.disconnect();

        // 3. The straggler reconnect fires during teardown.
        bot.reconnect();

        assertThat(bot.hasLiveClient())
                .as("reconnect after shutdown must not stand up a fresh, unreleasable Jetty client")
                .isFalse();
    }

    /**
     * PR#3 review round 2 (codex/gemini HIGH): the per-instance stop invariant that closes the
     * residual hairline. The lifetime {@code clientStopped} CAS is re-armed whenever
     * {@code connect()} stands up a FRESH client, so a second failed reconnect must stop its OWN
     * newly-created client — even though the CAS was already consumed stopping the first one.
     *
     * <p>This is exactly the dead-server fleet-teardown case (the dominant real scenario) made
     * deterministic without needing a concurrent interleave: pre-fix the second client leaked
     * because the one-shot CAS was spent; post-fix the re-arm lets {@code stopClientAsync()} win
     * for the second client too.
     */
    @Test
    void secondFailedReconnectStopsItsFreshClient() throws Exception {
        BotClient bot = new BotClient("ws://localhost:1/ws/world", 'C', new HeuristicBrain(3));

        bot.reconnect(); // creates client #1, upgrade refused, stops it, consumes the CAS
        assertThat(bot.isClientStopped()).isTrue();
        assertThat(bot.hasLiveClient()).as("client #1 released").isFalse();

        bot.reconnect(); // creates client #2 — re-arm must let it be stopped despite the spent CAS

        assertThat(bot.hasLiveClient())
                .as("a fresh client from a later reconnect must also be stopped (per-instance CAS re-arm)")
                .isFalse();
    }

    /**
     * PR#3 review round 5 (claude MEDIUM test-gap): pin the round-4 catch-all release on the
     * PUBLIC startup {@code connect()} path. The other tests drive the failure through
     * {@code reconnect()}, which has its own {@code stopClientAsync()} catch — so they pass even
     * if {@code connect()}'s own catch is reverted. This calls {@code connect()} directly (the
     * {@code BotFleet} launch path that only logs on failure): the upgrade to a dead port throws,
     * and {@code connect()}'s own catch must release the started client. Fails iff that catch is removed.
     */
    @Test
    void startupConnectStopsClientOnUpgradeFailure() throws Exception {
        BotClient bot = new BotClient("ws://localhost:1/ws/world", 'C', new HeuristicBrain(3));

        assertThatThrownBy(bot::connect)
                .as("upgrade to a dead port must fail")
                .isInstanceOf(Exception.class);

        long deadline = System.currentTimeMillis() + 5000;
        while (!bot.isClientStopped() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(bot.isClientStopped())
                .as("startup connect() must release the client it started when the upgrade fails")
                .isTrue();
        assertThat(bot.hasLiveClient())
                .as("no running client retained after a failed startup connect()")
                .isFalse();
    }
}
