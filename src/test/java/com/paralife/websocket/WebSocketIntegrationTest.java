package com.paralife.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",  // Fast ticks for testing
        "paralife.tick.auto-start=true",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocketSession clientSession;

    @AfterEach
    void tearDown() throws Exception {
        if (clientSession != null && clientSession.isOpen()) {
            clientSession.close();
        }
    }

    @Test
    void connectAndReceiveWelcome() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var welcomeLatch = new CountDownLatch(1);

        clientSession = connectClient(messages, welcomeLatch);

        assertThat(welcomeLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive welcome message")
                .isTrue();

        // Parse welcome message
        JsonNode welcome = objectMapper.readTree(messages.get(0));
        assertThat(welcome.get("type").asText()).isEqualTo("welcome");
        assertThat(welcome.get("worldWidth").asInt()).isEqualTo(16);
        assertThat(welcome.get("worldHeight").asInt()).isEqualTo(16);
        assertThat(welcome.has("sessionId")).isTrue();
        assertThat(welcome.has("currentTick")).isTrue();
    }

    @Test
    void receivesTickBroadcasts() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var tickLatch = new CountDownLatch(3); // Wait for 3 tick messages

        var handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                messages.add(payload);
                if (payload.contains("\"type\":\"tick\"")) {
                    tickLatch.countDown();
                }
            }
        };

        var client = new StandardWebSocketClient();
        clientSession = client.execute(handler, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);

        assertThat(tickLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive at least 3 tick messages")
                .isTrue();

        // Verify tick messages have expected structure
        var tickMessages = messages.stream()
                .filter(m -> m.contains("\"type\":\"tick\""))
                .map(m -> {
                    try { return objectMapper.readTree(m); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toList();

        assertThat(tickMessages).hasSizeGreaterThanOrEqualTo(3);
        for (JsonNode tick : tickMessages) {
            assertThat(tick.get("type").asText()).isEqualTo("tick");
            assertThat(tick.has("tickNumber")).isTrue();
            assertThat(tick.has("timestamp")).isTrue();
            assertThat(tick.has("entityCount")).isTrue();
        }
    }

    @Test
    void registerEntityAndReceiveConfirmation() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var registeredLatch = new CountDownLatch(1);

        var handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                messages.add(payload);
                if (payload.contains("\"type\":\"registered\"")) {
                    registeredLatch.countDown();
                }
            }
        };

        var client = new StandardWebSocketClient();
        clientSession = client.execute(handler, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);

        // Wait for welcome first
        Thread.sleep(200);

        // Send register message
        String registerJson = objectMapper.writeValueAsString(new Messages.Register("cell"));
        clientSession.sendMessage(new TextMessage(registerJson));

        assertThat(registeredLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive registered confirmation")
                .isTrue();

        // Parse registered response
        var registeredMsg = messages.stream()
                .filter(m -> m.contains("\"type\":\"registered\""))
                .findFirst()
                .orElseThrow();

        JsonNode registered = objectMapper.readTree(registeredMsg);
        assertThat(registered.get("type").asText()).isEqualTo("registered");
        assertThat(registered.has("entityId")).isTrue();
        assertThat(registered.get("x").asInt()).isBetween(0, 15);
        assertThat(registered.get("y").asInt()).isBetween(0, 15);
    }

    @Test
    void invalidMessageReturnsError() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var errorLatch = new CountDownLatch(1);

        var handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                messages.add(payload);
                if (payload.contains("\"type\":\"error\"")) {
                    errorLatch.countDown();
                }
            }
        };

        var client = new StandardWebSocketClient();
        clientSession = client.execute(handler, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        // Send garbage
        clientSession.sendMessage(new TextMessage("{\"not\":\"valid\"}"));

        assertThat(errorLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive error for invalid message")
                .isTrue();
    }

    private WebSocketSession connectClient(CopyOnWriteArrayList<String> messages, CountDownLatch latch) throws Exception {
        var handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                messages.add(message.getPayload());
                latch.countDown();
            }
        };

        var client = new StandardWebSocketClient();
        return client.execute(handler, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);
    }
}
