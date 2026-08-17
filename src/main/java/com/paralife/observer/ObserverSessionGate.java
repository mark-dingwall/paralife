package com.paralife.observer;

import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Enablement + concurrency-cap gate for {@code /ws/observer}.
 *
 * <p>Runs as a {@link HandshakeInterceptor} so it can refuse a disabled endpoint (and
 * fast-reject at the cap) during the HTTP handshake. The AUTHORITATIVE, race-free permit
 * is acquired later — at {@code afterConnectionEstablished} via {@link #acquireForSession}
 * — because a permit taken in {@code beforeHandshake} cannot be freed on the
 * "upgrade committed (HTTP 101) but the session never opens" path: a container fires no
 * session lifecycle callback (onClose only follows onOpen), so {@link #releaseIfHeld}
 * would never run and the permit would leak until {@code maxSessions} orphans wedge the
 * cap. Tying acquisition to the session lifecycle (acquire on establish, release on
 * close) makes every acquired permit reclaimable.
 *
 * <p>Release-once lease (O9): {@code handleTransportError} AND {@code afterConnectionClosed}
 * both fire for one failed connection, so release is guarded by a remove-once marker (the
 * {@code observerPermit} session attribute) — preventing double-release above maxSessions.
 */
@Component
public class ObserverSessionGate implements HandshakeInterceptor {

    static final String ATTR_PERMIT = "observerPermit";

    private final ObserverConfig config;
    private final Semaphore permits;

    public ObserverSessionGate(ObserverConfig config) {
        this.config = config;
        this.permits = new Semaphore(config.maxSessions());
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!config.enabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        // Best-effort fast-reject at the cap: refuse the HTTP upgrade cleanly instead of
        // upgrading a connection that acquireForSession would immediately close. NOT
        // authoritative (availablePermits() is check-then-act) — the race-free cap is
        // enforced at acquireForSession; this only spares a full observer the round-trip.
        if (permits.availablePermits() == 0) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No permit is held before the session opens — nothing to release here.
    }

    /**
     * Authoritative, race-free permit acquire — called from
     * {@code afterConnectionEstablished}, i.e. once a real session exists whose
     * {@code afterConnectionClosed} is guaranteed to fire and free the permit via
     * {@link #releaseIfHeld}. The {@link Semaphore} makes concurrent establishes race-free
     * (exactly maxSessions win); the loser is closed by the caller.
     *
     * @return true if a permit was granted (and marked on the session); false at cap.
     */
    public boolean acquireForSession(WebSocketSession session) {
        if (!permits.tryAcquire()) {
            return false;
        }
        session.getAttributes().put(ATTR_PERMIT, Boolean.TRUE); // remove-once release marker
        return true;
    }

    /** Release the session's permit exactly once (idempotent across error+close). */
    public void releaseIfHeld(WebSocketSession session) {
        if (session.getAttributes().remove(ATTR_PERMIT) != null) {
            permits.release();
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
