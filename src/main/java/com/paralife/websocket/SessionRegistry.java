package com.paralife.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of active WebSocket sessions.
 * Maps session ID → WebSocketSession for broadcast and lookup.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("Session registered: {} (total: {})", session.getId(), sessions.size());
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
        log.info("Session unregistered: {} (total: {})", sessionId, sessions.size());
    }

    public Collection<WebSocketSession> getActiveSessions() {
        return sessions.values();
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
}
