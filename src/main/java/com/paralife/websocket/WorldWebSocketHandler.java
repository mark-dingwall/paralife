package com.paralife.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.ActionResolver;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.TickEngine;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Handles WebSocket connections for the paralife world.
 * On connect: sends Welcome message with world info.
 * On message: handles Register and Heartbeat from clients.
 * On close: cleans up session.
 */
@Component
public class WorldWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorldWebSocketHandler.class);

    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final TickEngine tickEngine;
    private final BotRegistry botRegistry;
    private final ActionResolver actionResolver;
    private final ObjectMapper objectMapper;

    public WorldWebSocketHandler(SessionRegistry sessionRegistry, WorldGrid worldGrid,
                                  TickEngine tickEngine, BotRegistry botRegistry,
                                  ActionResolver actionResolver, ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.tickEngine = tickEngine;
        this.botRegistry = botRegistry;
        this.actionResolver = actionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessionRegistry.register(session);

        var welcome = new Messages.Welcome(
                session.getId(),
                worldGrid.getWidth(),
                worldGrid.getHeight(),
                tickEngine.getCurrentTick()
        );

        sendMessage(session, welcome);
        log.info("Client connected: {} (total: {})", session.getId(), sessionRegistry.getSessionCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Messages msg = objectMapper.readValue(message.getPayload(), Messages.class);

            switch (msg) {
                case Messages.Register register -> handleRegister(session, register);
                case Messages.Heartbeat heartbeat -> handleHeartbeat(session);
                case Messages.Action action -> handleAction(session, action);
                default -> {
                    log.warn("Unexpected message type from {}: {}", session.getId(), msg.getClass().getSimpleName());
                    sendMessage(session, new Messages.Error("UNKNOWN_MESSAGE", "Unhandled message type"));
                }
            }
        } catch (Exception e) {
            log.warn("Invalid message from {}: {}", session.getId(), e.getMessage());
            sendMessage(session, new Messages.Error("INVALID_MESSAGE", e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanupBot(session.getId());
        sessionRegistry.unregister(session.getId());
        log.info("Client disconnected: {} (status: {}, total: {})",
                session.getId(), status, sessionRegistry.getSessionCount());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanupBot(session.getId());
        sessionRegistry.unregister(session.getId());
    }

    /** Remove bot's entity from the grid, then unregister from BotRegistry. */
    private void cleanupBot(String sessionId) {
        botRegistry.getBySession(sessionId).ifPresent(state -> {
            var pos = state.position();
            worldGrid.clearEntity(pos.x(), pos.y());
        });
        botRegistry.unregisterBySession(sessionId);
    }

    private static final int MAX_PLACEMENT_ATTEMPTS = 50;

    private void handleRegister(WebSocketSession session, Messages.Register register) throws Exception {
        String entityId = "entity-" + session.getId();

        // Parse particle type from register message, default to CATALYST
        ParticleType particleType;
        try {
            particleType = ParticleType.valueOf(register.entityType().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            particleType = ParticleType.CATALYST;
        }

        Particle particle = Particle.spawn(entityId, particleType);

        // Try random positions until we find an empty cell
        var rng = ThreadLocalRandom.current();
        int x = -1, y = -1;
        boolean placed = false;
        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            x = rng.nextInt(worldGrid.getWidth());
            y = rng.nextInt(worldGrid.getHeight());
            if (worldGrid.trySetEntity(x, y, particle)) {
                placed = true;
                break;
            }
        }

        if (!placed) {
            sendMessage(session, new Messages.Error("GRID_FULL", "No empty cell found after "
                    + MAX_PLACEMENT_ATTEMPTS + " attempts"));
            return;
        }

        // Register in bot registry for perception/action tracking
        botRegistry.register(session.getId(), entityId, new Position(x, y));

        sendMessage(session, new Messages.Registered(entityId, x, y));
        log.info("Entity registered: {} at ({},{}) type={}", entityId, x, y, particleType);
    }

    private void handleAction(WebSocketSession session, Messages.Action action) {
        actionResolver.queueAction(session.getId(), action);
        log.debug("Action queued from {}: type={} dir={}", session.getId(),
                action.actionType(), action.direction());
    }

    private void handleHeartbeat(WebSocketSession session) {
        // Heartbeat acknowledged — no response needed
        log.debug("Heartbeat from {}", session.getId());
    }

    private void sendMessage(WebSocketSession session, Messages message) throws Exception {
        String json = objectMapper.writeValueAsString(message);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }
}
