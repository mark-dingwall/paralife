package com.paralife.websocket;

import com.paralife.observer.ObserverSessionGate;
import com.paralife.observer.ObserverWebSocketHandler;
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
 *
 * <p>{@code /ws/observer} is the read-only observer route (Task 9): the same Jetty
 * upgrade strategy is reused, gated by {@link ObserverSessionGate} (enablement +
 * concurrency-cap {@link org.springframework.web.socket.server.HandshakeInterceptor}),
 * and exempt from the deflate-enforcement filter's {@code server_no_context_takeover}
 * requirement (C1 — browsers cannot advertise that param; see {@link JettyDeflateCustomizer}).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorldWebSocketHandler worldWebSocketHandler;
    private final ObserverWebSocketHandler observerWebSocketHandler;
    private final ObserverSessionGate observerSessionGate;
    private final JettyRequestUpgradeStrategy upgradeStrategy;

    public WebSocketConfig(WorldWebSocketHandler worldWebSocketHandler,
                           ObserverWebSocketHandler observerWebSocketHandler,
                           ObserverSessionGate observerSessionGate,
                           JettyRequestUpgradeStrategy upgradeStrategy) {
        this.worldWebSocketHandler = worldWebSocketHandler;
        this.observerWebSocketHandler = observerWebSocketHandler;
        this.observerSessionGate = observerSessionGate;
        this.upgradeStrategy = upgradeStrategy;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(worldWebSocketHandler, "/ws/world")
                .setHandshakeHandler(new DefaultHandshakeHandler(upgradeStrategy))
                .setAllowedOrigins("*"); // bots
        registry.addHandler(observerWebSocketHandler, "/ws/observer")
                .setHandshakeHandler(new DefaultHandshakeHandler(upgradeStrategy))
                .addInterceptors(observerSessionGate)
                .setAllowedOrigins("*"); // browser observers (read-only; enablement/cap gate applies)
    }
}
