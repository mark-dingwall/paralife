package com.paralife.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.jetty.JettyRequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Spring owns the only WebSocket registration for the world upgrade path.
 * Extension negotiation ({@code permessage-deflate; server_no_context_takeover})
 * is driven by {@link JettyDeflateCustomizer}: the {@link JettyRequestUpgradeStrategy}
 * bean it produces is wired through {@link DefaultHandshakeHandler} into this
 * registration, and its companion servlet filter intercepts upgrade requests to
 * enforce refusal + {@code server_no_context_takeover}.
 *
 * <p>This is the ONLY wiring path for the world upgrade route — there is no native
 * Jetty mapping call for the same route. See {@link WebSocketRouteAssertion} for
 * the runtime invariant check.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorldWebSocketHandler worldWebSocketHandler;
    private final JettyRequestUpgradeStrategy upgradeStrategy;

    public WebSocketConfig(WorldWebSocketHandler worldWebSocketHandler,
                           JettyRequestUpgradeStrategy upgradeStrategy) {
        this.worldWebSocketHandler = worldWebSocketHandler;
        this.upgradeStrategy = upgradeStrategy;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        DefaultHandshakeHandler handshake = new DefaultHandshakeHandler(upgradeStrategy);
        registry.addHandler(worldWebSocketHandler, "/ws/world")
                .setHandshakeHandler(handshake)
                .setAllowedOrigins("*"); // Allow all origins for bot connections
    }
}
