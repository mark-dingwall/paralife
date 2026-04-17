// Bot package lives in src/main intentionally: bots will become standalone clients
// connecting over the network. Keeping them here during development avoids a premature
// module split while the protocol is still evolving.
package com.paralife.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bot client that connects to the paralife server via WebSocket,
 * receives perception messages, and submits actions using a {@link HeuristicBrain}.
 */
public class BotClient {

    private static final Logger log = LoggerFactory.getLogger(BotClient.class);

    private final String serverUri;
    private final String entityType;
    private final HeuristicBrain brain;
    private final ObjectMapper objectMapper;

    private volatile WebSocketSession session;
    private volatile String entityId;
    private volatile boolean registered = false;
    private final AtomicInteger actionCount = new AtomicInteger(0);
    private final AtomicInteger perceptionCount = new AtomicInteger(0);
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch registeredLatch = new CountDownLatch(1);

    public BotClient(String serverUri, String entityType) {
        this.serverUri = serverUri;
        this.entityType = entityType;
        this.brain = new HeuristicBrain();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Connect to the server and start the bot loop.
     * Returns immediately — perception handling runs asynchronously.
     */
    public void connect() throws Exception {
        var client = new StandardWebSocketClient();
        var handler = new BotWebSocketHandler();

        session = client.execute(handler, new WebSocketHttpHeaders(),
                URI.create(serverUri)).get(10, TimeUnit.SECONDS);

        connectedLatch.countDown();
        log.info("Bot connected: type={} uri={}", entityType, serverUri);
    }

    /**
     * Wait until the bot has registered with the server.
     */
    public boolean waitForRegistered(long timeout, TimeUnit unit) throws InterruptedException {
        return registeredLatch.await(timeout, unit);
    }

    /**
     * Disconnect the bot from the server.
     */
    public void disconnect() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
                log.info("Bot disconnected: entity={}", entityId);
            } catch (Exception e) {
                log.warn("Error disconnecting bot: {}", e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getEntityId() {
        return entityId;
    }

    public int getActionCount() {
        return actionCount.get();
    }

    public int getPerceptionCount() {
        return perceptionCount.get();
    }

    /**
     * Internal WebSocket handler for the bot.
     */
    private class BotWebSocketHandler extends TextWebSocketHandler {

        @Override
        protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
            try {
                JsonNode node = objectMapper.readTree(message.getPayload());
                String type = node.get("type").asText();

                switch (type) {
                    case "welcome" -> handleWelcome(wsSession, node);
                    case "registered" -> handleRegistered(node);
                    case "perception" -> handlePerception(wsSession, node);
                    case "action_result" -> handleActionResult(node);
                    case "tick" -> {} // Ignore tick broadcasts
                    case "error" -> log.warn("Bot {} error: {}", entityId, node.get("message").asText());
                    default -> log.debug("Bot {} unknown message: {}", entityId, type);
                }
            } catch (Exception e) {
                log.warn("Bot {} message error: {}", entityId, e.getMessage());
            }
        }

        private void handleWelcome(WebSocketSession wsSession, JsonNode node) throws Exception {
            // Send register message using the session from the callback
            var registerMap = new java.util.LinkedHashMap<String, Object>();
            registerMap.put("type", "register");
            registerMap.put("entityType", entityType);
            wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerMap)));
            log.debug("Bot sent register: type={}", entityType);
        }

        private void handleRegistered(JsonNode node) {
            entityId = node.get("entityId").asText();
            registered = true;
            registeredLatch.countDown();
            log.info("Bot registered: entity={} at ({},{})", entityId,
                    node.get("x").asInt(), node.get("y").asInt());
        }

        private void handlePerception(WebSocketSession wsSession, JsonNode node) {
            perceptionCount.incrementAndGet();
            try {
                // Phase 14 (cycle-4 action item #11): CellView status fields
                // (cellStatus, entityStatus) are deserialised natively by
                // Jackson from the expanded 6-field record. No manual parsing
                // needed here. Any residual raw-map / JsonNode path is
                // pre-existing Phase 09 tech debt and is NOT modified by this
                // phase.
                Messages.Perception perception = objectMapper.treeToValue(node, Messages.Perception.class);

                // Let brain decide
                HeuristicBrain.Decision decision = brain.decide(perception);

                // Send action using the session from the callback
                var actionMap = new java.util.LinkedHashMap<String, Object>();
                actionMap.put("type", "action");
                actionMap.put("actionType", decision.actionType());
                if (decision.direction() != null) {
                    actionMap.put("direction", decision.direction());
                }
                wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(actionMap)));
                actionCount.incrementAndGet();

                if (log.isDebugEnabled()) {
                    log.debug("Bot {} tick {} → {} {}",
                            entityId, perception.tickNumber(),
                            decision.actionType(), decision.direction() != null ? decision.direction() : "");
                }
            } catch (Exception e) {
                log.warn("Bot {} failed to process perception: {}", entityId, e.getMessage());
            }
        }

        private void handleActionResult(JsonNode node) {
            if (log.isTraceEnabled()) {
                log.trace("Bot {} action result: success={} type={} reason={}",
                        entityId, node.get("success").asBoolean(),
                        node.get("actionType").asText(), node.get("reason").asText());
            }
        }
    }
}
