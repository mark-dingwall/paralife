package com.paralife.observer;

import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Pre-upgrade enablement + concurrency-cap gate for {@code /ws/observer}. Runs as a
 * {@link HandshakeInterceptor} so it can refuse the HTTP handshake (afterConnectionEstablished
 * is post-upgrade and cannot). A {@link Semaphore} makes the cap race-free under
 * concurrent handshakes (a plain size()&lt;max check is check-then-act).
 *
 * <p>Release-once lease (O9): {@code handleTransportError} AND {@code afterConnectionClosed}
 * both fire for one failed connection, so release is guarded by a remove-once marker
 * (the {@code observerPermit} session attribute) — mirroring the bot cleanup's
 * {@code attrs.remove(ATTR_ENTITY_TYPE) != null} gate. This prevents double-release
 * inflating the semaphore above maxSessions.
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
        if (!permits.tryAcquire()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        attributes.put(ATTR_PERMIT, Boolean.TRUE); // becomes a session attribute (remove-once marker)
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // afterHandshake runs only when OUR beforeHandshake returned true (permit held). A session
        // — and therefore releaseIfHeld — exists ONLY if the upgrade actually switched protocols.
        // Spring rejects a malformed upgrade by returning false from doHandshake WITHOUT throwing,
        // so exception==null does NOT imply success. Release for every non-established handshake, or
        // maxSessions bad requests wedge the cap permanently. (Success defers to releaseIfHeld — no
        // double-release, since a non-101 status has no session to call it.)
        if (!isUpgraded(response, exception)) {
            permits.release();
        }
    }

    private static boolean isUpgraded(ServerHttpResponse response, Exception exception) {
        if (exception != null) {
            return false;
        }
        // Spring always passes a ServletServerHttpResponse here; doHandshake sets the status to
        // 101 on a real upgrade and 4xx on rejection (both via servletResponse.setStatus).
        if (response instanceof ServletServerHttpResponse servlet) {
            return servlet.getServletResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS.value();
        }
        return true; // unknown response type (not reachable under the servlet container): assume a
                     // session was established so releaseIfHeld owns the release, never double-free.
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
