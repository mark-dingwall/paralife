package com.paralife.websocket;

import com.paralife.engine.TickEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Sends RFC 6455 PING control frames to every active WebSocket session every
 * {@code keepaliveTicks} ticks, so server-side Jetty read-idle timers never fire
 * while a bot has nothing to say. The bot (Jetty client) auto-PONGs per spec;
 * no bot-side code is required.
 *
 * <p>Phase 15 UAT Test 7 follow-up: idle bots used to be dropped ~30s after the
 * last outbound action frame. Application-level keepalive is intentionally
 * absent from SCHEMA §5 — keepalive is a transport concern, not a protocol
 * verb — so we handle it at the WebSocket layer.
 *
 * <p>Paired with {@link JettyDeflateCustomizer}'s bumped idle timeout as the
 * defensive fallback: ping cadence stays well under the idle cap so a single
 * dropped ping never evicts an otherwise-live session.
 */
@Component
public class WebSocketKeepaliveService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketKeepaliveService.class);
    private static final ByteBuffer EMPTY_PING_PAYLOAD = ByteBuffer.allocate(0);

    private final SessionRegistry sessionRegistry;
    private final long keepaliveTicks;
    private long pingsSent;

    public WebSocketKeepaliveService(SessionRegistry sessionRegistry,
                                     @Value("${paralife.websocket.keepalive-ticks:30}") long keepaliveTicks) {
        this.sessionRegistry = sessionRegistry;
        this.keepaliveTicks = Math.max(1, keepaliveTicks);
    }

    /**
     * Run after {@link TickBroadcaster} (@Order 50) and its ilk so keepalives
     * are the last outbound traffic on the tick. Tick 0 is the first fire —
     * that's fine, sessions registered before tick 0 still benefit.
     */
    @EventListener
    @Order(200)
    public void onTick(TickEvent event) {
        if (event.tickNumber() % keepaliveTicks != 0) return;

        for (WebSocketSession session : sessionRegistry.getActiveSessions()) {
            if (!session.isOpen()) continue;
            try {
                synchronized (session) {
                    session.sendMessage(new PingMessage(EMPTY_PING_PAYLOAD));
                }
                pingsSent++;
            } catch (IOException | RuntimeException e) {
                log.warn("Keepalive ping failed for session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    long getPingsSent() {
        return pingsSent;
    }
}
