package com.paralife.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.TickEvent;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * Listens for TickEvents and broadcasts tick messages to all connected clients.
 * Uses virtual threads for non-blocking broadcast to many sessions.
 */
@Component
public class TickBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);

    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final ObjectMapper objectMapper;

    public TickBroadcaster(SessionRegistry sessionRegistry, WorldGrid worldGrid, ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.worldGrid = worldGrid;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onTick(TickEvent event) {
        var sessions = sessionRegistry.getActiveSessions();
        if (sessions.isEmpty()) {
            return;
        }

        var snapshot = worldGrid.snapshot();
        var tickMessage = new Messages.Tick(
                event.tickNumber(),
                event.timestamp().toEpochMilli(),
                snapshot.entityCount()
        );

        try {
            String json = objectMapper.writeValueAsString(tickMessage);
            var textMessage = new TextMessage(json);

            int sent = 0;
            int failed = 0;

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(textMessage);
                        }
                        sent++;
                    } catch (IOException e) {
                        failed++;
                        log.warn("Failed to send tick {} to session {}: {}",
                                event.tickNumber(), session.getId(), e.getMessage());
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("Tick {} broadcast: sent={}, failed={}, total={}",
                        event.tickNumber(), sent, failed, sessions.size());
            }
        } catch (Exception e) {
            log.error("Failed to serialize tick message: {}", e.getMessage(), e);
        }
    }
}
