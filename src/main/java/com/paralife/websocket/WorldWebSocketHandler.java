package com.paralife.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.TickEngine;
import com.paralife.world.WorldGrid;
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
    private final ObjectMapper objectMapper;

    public WorldWebSocketHandler(SessionRegistry sessionRegistry, WorldGrid worldGrid,
                                  TickEngine tickEngine, ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.tickEngine = tickEngine;
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
        sessionRegistry.unregister(session.getId());
        log.info("Client disconnected: {} (status: {}, total: {})",
                session.getId(), status, sessionRegistry.getSessionCount());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        sessionRegistry.unregister(session.getId());
    }

    private void handleRegister(WebSocketSession session, Messages.Register register) throws Exception {
        // For now, place entity at random position
        int x = (int) (Math.random() * worldGrid.getWidth());
        int y = (int) (Math.random() * worldGrid.getHeight());
        String entityId = "entity-" + session.getId();

        worldGrid.setCell(x, y, entityId);

        sendMessage(session, new Messages.Registered(entityId, x, y));
        log.info("Entity registered: {} at ({},{}) type={}", entityId, x, y, register.entityType());
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
