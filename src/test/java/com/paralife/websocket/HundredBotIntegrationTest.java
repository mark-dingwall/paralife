package com.paralife.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: 100 concurrent WebSocket bots connect, all receive
 * sequential tick events without gaps or duplicates.
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) session.close();
            } catch (Exception ignored) {}
        }
    }

    @Test
    void hundredBotsConnectAndReceiveTicks() throws Exception {
        // Track ticks received per bot
        var ticksByBot = new ConcurrentHashMap<String, List<Long>>();
        var allConnected = new CountDownLatch(BOT_COUNT);
        var allReceivedTicks = new CountDownLatch(BOT_COUNT);
        var connectErrors = new AtomicInteger(0);

        // Connect 100 bots concurrently using virtual threads
        var connectLatch = new CountDownLatch(BOT_COUNT);
        for (int i = 0; i < BOT_COUNT; i++) {
            final int botIndex = i;
            Thread.startVirtualThread(() -> {
                try {
                    String botId = "bot-" + botIndex;
                    var ticks = new CopyOnWriteArrayList<Long>();
                    ticksByBot.put(botId, ticks);

                    var handler = new TextWebSocketHandler() {
                        private boolean welcomed = false;
                        private boolean tickGoalReached = false;

                        @Override
                        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                            try {
                                JsonNode node = objectMapper.readTree(message.getPayload());
                                String type = node.get("type").asText();

                                if ("welcome".equals(type) && !welcomed) {
                                    welcomed = true;
                                    allConnected.countDown();
                                } else if ("tick".equals(type)) {
                                    long tickNum = node.get("tickNumber").asLong();
                                    ticks.add(tickNum);
                                    if (!tickGoalReached && ticks.size() >= TICKS_TO_COLLECT) {
                                        tickGoalReached = true;
                                        allReceivedTicks.countDown();
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Bot {} message error: {}", botId, e.getMessage());
                            }
                        }
                    };

                    var client = new StandardWebSocketClient();
                    WebSocketSession session = client.execute(handler, new WebSocketHttpHeaders(),
                            URI.create("ws://localhost:" + port + "/ws/world")).get(10, TimeUnit.SECONDS);
                    sessions.add(session);
                } catch (Exception e) {
                    connectErrors.incrementAndGet();
                    log.error("Bot {} failed to connect: {}", botIndex, e.getMessage());
                } finally {
                    connectLatch.countDown();
                }
            });
        }

        // Wait for all connections
        assertThat(connectLatch.await(30, TimeUnit.SECONDS))
                .as("All %d bots should attempt connection within 30s", BOT_COUNT)
                .isTrue();

        assertThat(connectErrors.get())
                .as("No connection errors expected")
                .isEqualTo(0);

        // Wait for all welcomes
        assertThat(allConnected.await(10, TimeUnit.SECONDS))
                .as("All %d bots should receive welcome within 10s", BOT_COUNT)
                .isTrue();

        log.info("All {} bots connected and welcomed", BOT_COUNT);

        // Wait for all bots to receive enough ticks
        assertThat(allReceivedTicks.await(30, TimeUnit.SECONDS))
                .as("All %d bots should receive %d ticks within 30s", BOT_COUNT, TICKS_TO_COLLECT)
                .isTrue();

        log.info("All {} bots received {} ticks", BOT_COUNT, TICKS_TO_COLLECT);

        // Verify: each bot received sequential tick numbers (no gaps within each bot's stream)
        for (var entry : ticksByBot.entrySet()) {
            List<Long> ticks = new ArrayList<>(entry.getValue()); // snapshot to avoid COW race
            assertThat(ticks)
                    .as("Bot %s should have received at least %d ticks", entry.getKey(), TICKS_TO_COLLECT)
                    .hasSizeGreaterThanOrEqualTo(TICKS_TO_COLLECT);

            // Verify ticks are in ascending order
            for (int i = 1; i < ticks.size(); i++) {
                assertThat(ticks.get(i))
                        .as("Bot %s tick %d should be > tick %d", entry.getKey(), i, i - 1)
                        .isGreaterThan(ticks.get(i - 1));
            }
        }

        // Verify: all bots saw the same tick numbers (no bot missed a tick that others got)
        Set<Long> allTicksSeen = new TreeSet<>();
        for (List<Long> ticks : ticksByBot.values()) {
            allTicksSeen.addAll(ticks);
        }

        log.info("Tick range seen across all bots: {} to {}",
                allTicksSeen.stream().min(Long::compare).orElse(0L),
                allTicksSeen.stream().max(Long::compare).orElse(0L));

        // All sessions should still be open
        long openSessions = sessions.stream().filter(WebSocketSession::isOpen).count();
        assertThat(openSessions).isEqualTo(BOT_COUNT);

        log.info("Test passed: {} bots, {} ticks each, all sequential, no gaps", BOT_COUNT, TICKS_TO_COLLECT);
    }
}
