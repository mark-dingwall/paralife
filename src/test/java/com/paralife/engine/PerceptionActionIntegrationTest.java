package com.paralife.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.world.Entity;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.paralife.world.Position;

import java.net.URI;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: full perception→action→perception round trip over WebSocket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=200",
        "paralife.tick.auto-start=true",
        "paralife.world.width=32",
        "paralife.world.height=32",
        "paralife.simulation.enabled=false"  // Disable physics so tests control state precisely
})
class PerceptionActionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PerceptionActionIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private WorldGrid worldGrid;

    @Autowired
    private BotRegistry botRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        worldGrid.clear();
        botRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isOpen()) {
            try { session.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Connect a bot and return a handler that captures messages by type.
     */
    private record BotConnection(WebSocketSession session, MessageCapture capture) {}

    private static class MessageCapture extends TextWebSocketHandler {
        private final ObjectMapper objectMapper = new ObjectMapper();
        final BlockingQueue<JsonNode> welcomes = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> registered = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> perceptions = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> actionResults = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> ticks = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> errors = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("type").asText();
            switch (type) {
                case "welcome" -> welcomes.add(node);
                case "registered" -> registered.add(node);
                case "perception" -> perceptions.add(node);
                case "action_result" -> actionResults.add(node);
                case "tick" -> ticks.add(node);
                case "error" -> errors.add(node);
                default -> log.warn("Unknown message type: {}", type);
            }
        }
    }

    private BotConnection connectBot(String entityType) throws Exception {
        var capture = new MessageCapture();
        var client = new StandardWebSocketClient();
        session = client.execute(capture, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);

        // Wait for welcome
        JsonNode welcome = capture.welcomes.poll(5, TimeUnit.SECONDS);
        assertThat(welcome).as("Should receive welcome").isNotNull();

        // Register
        String registerJson = objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>() {{
                    put("type", "register");
                    put("entityType", entityType);
                }});
        session.sendMessage(new TextMessage(registerJson));

        // Wait for registered
        JsonNode reg = capture.registered.poll(5, TimeUnit.SECONDS);
        assertThat(reg).as("Should receive registered").isNotNull();

        return new BotConnection(session, capture);
    }

    private void sendAction(WebSocketSession session, String actionType, String direction) throws Exception {
        var actionMap = new java.util.LinkedHashMap<String, Object>();
        actionMap.put("type", "action");
        actionMap.put("actionType", actionType);
        if (direction != null) actionMap.put("direction", direction);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(actionMap)));
    }

    // ── Tests ─────────────────────────────────────────────────────

    @Test
    void botReceivesPerceptionAfterRegister() throws Exception {
        var bot = connectBot("CATALYST");

        // Should receive a perception on the next tick
        JsonNode perception = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(perception).as("Should receive perception").isNotNull();
        assertThat(perception.get("type").asText()).isEqualTo("perception");
        assertThat(perception.has("self")).isTrue();
        assertThat(perception.get("self").get("particleType").asText()).isEqualTo("CATALYST");
        assertThat(perception.has("neighbourhood")).isTrue();
        assertThat(perception.get("radius").asInt()).isEqualTo(2);

        // Neighbourhood should be 5x5
        JsonNode neighbourhood = perception.get("neighbourhood");
        assertThat(neighbourhood.size()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            assertThat(neighbourhood.get(i).size()).isEqualTo(5);
        }

        log.info("Perception received: self={}", perception.get("self"));
    }

    @Test
    void moveActionChangesPositionInNextPerception() throws Exception {
        var bot = connectBot("CATALYST");

        // Wait for first perception
        JsonNode p1 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p1).isNotNull();
        int startX = p1.get("self").get("x").asInt();
        int startY = p1.get("self").get("y").asInt();

        // Submit move east
        sendAction(bot.session, "move", "E");

        // Wait for action result
        JsonNode result = bot.capture.actionResults.poll(5, TimeUnit.SECONDS);
        assertThat(result).as("Should receive action result").isNotNull();
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("actionType").asText()).isEqualTo("move");

        // Wait for next perception — position should have changed
        JsonNode p2 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p2).isNotNull();
        int newX = p2.get("self").get("x").asInt();
        int newY = p2.get("self").get("y").asInt();

        // x should be one east (with toroidal wrapping)
        int expectedX = (startX + 1) % worldGrid.getWidth();
        assertThat(newX).isEqualTo(expectedX);
        assertThat(newY).isEqualTo(startY);

        log.info("Move verified: ({},{}) → ({},{})", startX, startY, newX, newY);
    }

    @Test
    void consumeActionGainsEnergy() throws Exception {
        var bot = connectBot("CATALYST");

        // Wait for first perception to know position
        JsonNode p1 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p1).isNotNull();
        int x = p1.get("self").get("x").asInt();
        int y = p1.get("self").get("y").asInt();
        int startEnergy = p1.get("self").get("energy").asInt();

        // Place a nutrient adjacent (east)
        int nx = (x + 1) % worldGrid.getWidth();
        worldGrid.setEntity(nx, y, Entity.Nutrient.spawn("test-nutrient"));

        // Submit consume
        sendAction(bot.session, "consume", null);

        // Wait for action result
        JsonNode result = bot.capture.actionResults.poll(5, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("actionType").asText()).isEqualTo("consume");

        // Next perception should show increased energy
        JsonNode p2 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p2).isNotNull();
        int newEnergy = p2.get("self").get("energy").asInt();
        assertThat(newEnergy).isGreaterThan(startEnergy);

        log.info("Consume verified: energy {} → {}", startEnergy, newEnergy);
    }

    @Test
    void moveIntoRockFails() throws Exception {
        var bot = connectBot("CATALYST");

        // Wait for first perception
        JsonNode p1 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p1).isNotNull();
        int x = p1.get("self").get("x").asInt();
        int y = p1.get("self").get("y").asInt();

        // Place rock to the east
        int rx = (x + 1) % worldGrid.getWidth();
        worldGrid.setEntity(rx, y, new Entity.Rock("test-rock"));

        // Try to move east
        sendAction(bot.session, "move", "E");

        // Wait for action result — should fail
        JsonNode result = bot.capture.actionResults.poll(5, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.get("success").asBoolean()).isFalse();
        assertThat(result.get("reason").asText()).contains("rock");

        // Position should not have changed
        JsonNode p2 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p2).isNotNull();
        assertThat(p2.get("self").get("x").asInt()).isEqualTo(x);
        assertThat(p2.get("self").get("y").asInt()).isEqualTo(y);

        log.info("Move into rock correctly rejected");
    }

    @Test
    void twoBotsConflictOnSameCell() throws Exception {
        // Clear grid and set up two bots manually for precise positioning
        worldGrid.clear();
        botRegistry.clear();

        var bot1 = connectBot("CATALYST");
        JsonNode p1 = bot1.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p1).isNotNull();
        int x1 = p1.get("self").get("x").asInt();
        int y1 = p1.get("self").get("y").asInt();

        // Connect second bot
        var capture2 = new MessageCapture();
        var client2 = new StandardWebSocketClient();
        var session2 = client2.execute(capture2, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/world")).get(5, TimeUnit.SECONDS);

        JsonNode w2 = capture2.welcomes.poll(5, TimeUnit.SECONDS);
        assertThat(w2).isNotNull();

        // Register second bot
        session2.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>() {{
                    put("type", "register");
                    put("entityType", "MEMBRANE");
                }})));
        JsonNode r2 = capture2.registered.poll(5, TimeUnit.SECONDS);
        assertThat(r2).isNotNull();
        String bot2EntityId = r2.get("entityId").asText();

        // Wait for bot2 perception
        JsonNode p2 = capture2.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p2).isNotNull();

        // Reposition bot2 two cells east of bot1 so both target the same cell:
        // bot1 at (x1, y1) moves E → target (x1+1, y1)
        // bot2 at (x1+2, y1) moves W → target (x1+1, y1)
        int bot2X = (x1 + 2) % worldGrid.getWidth();
        String bot2SessionId = botRegistry.getSessionForEntity(bot2EntityId).orElseThrow();
        Position bot2OldPos = botRegistry.getBySession(bot2SessionId).orElseThrow().position();
        worldGrid.setEntity(bot2OldPos.x(), bot2OldPos.y(), null); // clear old position
        Entity.Particle bot2Entity = Entity.Particle.spawn(bot2EntityId, Entity.ParticleType.MEMBRANE);
        worldGrid.setEntity(bot2X, y1, bot2Entity);
        botRegistry.updatePosition(bot2SessionId, new Position(bot2X, y1));

        // Both bots move toward the same target cell
        sendAction(bot1.session, "move", "E");
        sendAction(session2, "move", "W");

        // Both should get results — exactly one succeeds, one fails
        JsonNode result1 = bot1.capture.actionResults.poll(5, TimeUnit.SECONDS);
        JsonNode result2 = capture2.actionResults.poll(5, TimeUnit.SECONDS);
        assertThat(result1).as("Bot1 should receive action result").isNotNull();
        assertThat(result2).as("Bot2 should receive action result").isNotNull();

        boolean bot1Won = result1.get("success").asBoolean();
        boolean bot2Won = result2.get("success").asBoolean();
        assertThat(bot1Won ^ bot2Won)
                .as("Exactly one bot should succeed when both target the same cell (bot1=%s, bot2=%s)",
                        bot1Won, bot2Won)
                .isTrue();

        log.info("Conflict test: bot1={} bot2={} — winner: {}",
                bot1Won, bot2Won, bot1Won ? "bot1" : "bot2");

        try { session2.close(); } catch (Exception ignored) {}
    }

    @Test
    void restActionKeepsPosition() throws Exception {
        var bot = connectBot("SPORE");

        JsonNode p1 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p1).isNotNull();
        int x = p1.get("self").get("x").asInt();
        int y = p1.get("self").get("y").asInt();

        sendAction(bot.session, "rest", null);

        JsonNode result = bot.capture.actionResults.poll(5, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.get("success").asBoolean()).isTrue();

        JsonNode p2 = bot.capture.perceptions.poll(5, TimeUnit.SECONDS);
        assertThat(p2).isNotNull();
        assertThat(p2.get("self").get("x").asInt()).isEqualTo(x);
        assertThat(p2.get("self").get("y").asInt()).isEqualTo(y);
    }
}
