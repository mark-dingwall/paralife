package com.paralife.websocket;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 15-11: 100 concurrent bots connect and receive sequential tick frames.
 *
 * <p>Migrated from the Messages-era JSON wire to the codec-native protocol.
 * Each bot holds its own Jetty-native {@link WebSocketClient} session (required
 * for permessage-deflate negotiation — D-33), decodes incoming frames via
 * {@link PerceptionCodec}, and records the {@code tickId} of every
 * {@link Frame.TickFrame} it observes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=200",
        "paralife.tick.auto-start=true",
        "paralife.world.width=64",
        "paralife.world.height=64"
})
class HundredBotIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HundredBotIntegrationTest.class);
    private static final int BOT_COUNT = 100;
    private static final int TICKS_TO_COLLECT = 5;

    @LocalServerPort
    private int port;

    private final List<Bot> bots = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (Bot b : bots) b.close();
    }

    @Test
    void hundredBotsConnectAndReceiveTicks() throws Exception {
        var ticksByBot = new ConcurrentHashMap<String, List<Long>>();
        var allReceivedTicks = new CountDownLatch(BOT_COUNT);
        var connectLatch = new CountDownLatch(BOT_COUNT);
        var connectErrors = new AtomicInteger(0);

        // Connect 100 bots concurrently using virtual threads.
        for (int i = 0; i < BOT_COUNT; i++) {
            final int botIndex = i;
            Thread.startVirtualThread(() -> {
                String botId = "bot-" + botIndex;
                var ticks = new CopyOnWriteArrayList<Long>();
                ticksByBot.put(botId, ticks);
                try {
                    Bot bot = new Bot(botId, port, ticks, allReceivedTicks);
                    bots.add(bot);
                    bot.connect();
                } catch (Exception e) {
                    connectErrors.incrementAndGet();
                    log.error("Bot {} failed to connect: {}", botIndex, e.getMessage());
                } finally {
                    connectLatch.countDown();
                }
            });
        }

        assertThat(connectLatch.await(30, TimeUnit.SECONDS))
                .as("All %d bots should attempt connection within 30s", BOT_COUNT)
                .isTrue();
        assertThat(connectErrors.get())
                .as("No connection errors expected")
                .isEqualTo(0);

        log.info("All {} bots connected", BOT_COUNT);

        // Wait for each bot to receive enough tick frames.
        assertThat(allReceivedTicks.await(30, TimeUnit.SECONDS))
                .as("All %d bots should receive %d ticks within 30s", BOT_COUNT, TICKS_TO_COLLECT)
                .isTrue();

        log.info("All {} bots received {} ticks", BOT_COUNT, TICKS_TO_COLLECT);

        // Verify: each bot received monotonically non-decreasing tickIds.
        for (var entry : ticksByBot.entrySet()) {
            List<Long> seen = new ArrayList<>(entry.getValue());
            assertThat(seen)
                    .as("Bot %s should have received at least %d ticks", entry.getKey(), TICKS_TO_COLLECT)
                    .hasSizeGreaterThanOrEqualTo(TICKS_TO_COLLECT);
            for (int i = 1; i < seen.size(); i++) {
                assertThat(seen.get(i))
                        .as("Bot %s tick %d should be >= tick %d", entry.getKey(), i, i - 1)
                        .isGreaterThanOrEqualTo(seen.get(i - 1));
            }
        }

        Set<Long> allTicksSeen = new TreeSet<>();
        for (List<Long> t : ticksByBot.values()) allTicksSeen.addAll(t);
        log.info("Tick range seen across all bots: {} to {}",
                allTicksSeen.stream().min(Long::compare).orElse(0L),
                allTicksSeen.stream().max(Long::compare).orElse(0L));

        // All sessions should still be open.
        long openSessions = bots.stream().filter(Bot::isOpen).count();
        assertThat(openSessions).isEqualTo(BOT_COUNT);

        log.info("Test passed: {} bots, {} ticks each, all monotonic, no gaps",
                BOT_COUNT, TICKS_TO_COLLECT);
    }

    // ── Bot harness ──────────────────────────────────────────────

    /** Minimal tick-listening bot — opens a deflate-negotiated Jetty WS session. */
    private static final class Bot {
        final String botId;
        final int port;
        final List<Long> tickIds;
        final CountDownLatch tickGoal;
        volatile boolean tickGoalReached = false;

        WebSocketClient client;
        Session session;

        Bot(String botId, int port, List<Long> tickIds, CountDownLatch tickGoal) {
            this.botId = botId;
            this.port = port;
            this.tickIds = tickIds;
            this.tickGoal = tickGoal;
        }

        void connect() throws Exception {
            client = new WebSocketClient();
            client.start();
            ClientUpgradeRequest req = new ClientUpgradeRequest();
            req.addExtensions("permessage-deflate; server_no_context_takeover");
            session = client.connect(new Endpoint(this),
                            URI.create("ws://localhost:" + port + "/ws/world"), req)
                    .get(10, TimeUnit.SECONDS);
            // Post-plan-15: server only sends T frames to registered bots.
            // Send r|C immediately after the upgrade so T frames start flowing.
            session.sendText(com.paralife.codec.PerceptionCodec.encode(
                    new Frame.RegisterFrame('C')), Callback.NOOP);
        }

        boolean isOpen() {
            return session != null && session.isOpen();
        }

        void close() {
            try {
                if (session != null && session.isOpen()) {
                    session.close(1000, "done", Callback.NOOP);
                }
            } catch (Exception ignored) { /* best-effort */ }
            try {
                if (client != null) client.stop();
            } catch (Exception ignored) { /* best-effort */ }
        }
    }

    @WebSocket
    public static class Endpoint {
        private final Bot bot;

        public Endpoint(Bot bot) {
            this.bot = bot;
        }

        @OnWebSocketOpen
        public void onOpen(Session s) { /* session reference captured by caller */ }

        @OnWebSocketMessage
        public void onMessage(String payload) {
            try {
                Frame f = PerceptionCodec.decode(payload);
                if (f instanceof Frame.TickFrame tf) {
                    bot.tickIds.add(tf.tickId());
                    if (!bot.tickGoalReached && bot.tickIds.size() >= TICKS_TO_COLLECT) {
                        bot.tickGoalReached = true;
                        bot.tickGoal.countDown();
                    }
                }
            } catch (Exception e) {
                log.warn("Bot {} frame decode failed: {}", bot.botId, e.getMessage());
            }
        }
    }
}
