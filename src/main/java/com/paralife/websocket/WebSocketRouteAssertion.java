package com.paralife.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.WebSocketHandler;

/**
 * Startup-time invariant check: exactly one handler is bound to the world
 * WebSocket path ({@code /ws/world}).
 *
 * <p>Cross-AI review (2026-04-20, HIGH #3 consensus) flagged that earlier
 * Phase 15 sketches registered the same path both via Spring's
 * {@code WebSocketHandlerRegistry} AND via a native Jetty {@code addMapping}
 * call. The Task 2 replan dropped the native path, but the invariant needs
 * runtime evidence — that is this bean's job. Any future plan that introduces
 * a duplicate registration will fail startup with a fast, loud
 * {@link IllegalStateException} instead of producing the "last writer wins"
 * silent misbehaviour the reviewers warned about.
 *
 * <p>The behavioural half of the invariant (a text frame actually reaches
 * {@link WorldWebSocketHandler#handleTextMessage}) is covered by
 * {@code WebSocketRouteAssertionTest}, which drives a real upgrade and probes
 * the path end-to-end.
 */
@Component
public class WebSocketRouteAssertion {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRouteAssertion.class);
    static final String PATH = "/ws/world";

    private final ApplicationContext ctx;

    public WebSocketRouteAssertion(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        List<String> registrations = collectRegistrationsForPath();

        Map<String, WebSocketHandler> wsBeans = ctx.getBeansOfType(WebSocketHandler.class);
        String handlerSummary = new TreeMap<>(wsBeans).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().getClass().getSimpleName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");

        if (registrations.isEmpty()) {
            throw new IllegalStateException(
                    "No handler registered for " + PATH
                            + " — Phase 15 wiring invariant broken. "
                            + "WebSocketHandler beans: " + handlerSummary);
        }
        if (registrations.size() > 1) {
            throw new IllegalStateException(
                    "Multiple handlers registered for " + PATH + " (expected 1, found "
                            + registrations.size() + "): " + registrations
                            + ". WebSocketHandler beans: " + handlerSummary);
        }
        log.info("[ws-route-assertion] Confirmed single handler path {} — source={} beans={}",
                PATH, registrations.get(0), handlerSummary);
    }

    /**
     * Enumerates every {@link SimpleUrlHandlerMapping} bean and returns a descriptor
     * string for each one whose URL map contains {@link #PATH}. The descriptor is
     * {@code "<beanName>:<handlerClass>"} so the caller can report both the source
     * mapping and the handler class if duplicates are detected.
     */
    private List<String> collectRegistrationsForPath() {
        List<String> hits = new ArrayList<>();
        Map<String, HandlerMapping> mappings = ctx.getBeansOfType(HandlerMapping.class);
        for (Map.Entry<String, HandlerMapping> entry : mappings.entrySet()) {
            HandlerMapping mapping = entry.getValue();
            if (!(mapping instanceof SimpleUrlHandlerMapping simple)) {
                continue;
            }
            Map<String, ?> urlMap = simple.getUrlMap();
            if (urlMap == null) {
                continue;
            }
            Object handler = urlMap.get(PATH);
            if (handler != null) {
                hits.add(entry.getKey() + ":" + handler.getClass().getSimpleName());
            }
        }
        return hits;
    }
}
