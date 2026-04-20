package com.paralife.websocket;

import com.paralife.metrics.WebSocketMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of active WebSocket sessions.
 * Maps session ID → WebSocketSession for broadcast and lookup.
 *
 * <p>Plan 15-10: every register/unregister republishes the session count to
 * {@link WebSocketMetrics#setActiveSessions(int)} so the
 * {@code paralife.ws.active.sessions} gauge reflects live state.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final WebSocketMetrics metrics;

    public SessionRegistry(WebSocketMetrics metrics) {
        this.metrics = metrics;
    }

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        metrics.setActiveSessions(sessions.size());
        log.info("Session registered: {} (total: {})", session.getId(), sessions.size());
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
        metrics.setActiveSessions(sessions.size());
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
