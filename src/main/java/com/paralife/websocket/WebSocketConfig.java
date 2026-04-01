package com.paralife.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the world WebSocket handler at /ws/world.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorldWebSocketHandler worldWebSocketHandler;

    public WebSocketConfig(WorldWebSocketHandler worldWebSocketHandler) {
        this.worldWebSocketHandler = worldWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(worldWebSocketHandler, "/ws/world")
                .setAllowedOrigins("*"); // Allow all origins for bot connections
    }
}
