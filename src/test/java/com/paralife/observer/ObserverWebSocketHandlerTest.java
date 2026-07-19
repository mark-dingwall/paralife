package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/**
 * O10 (final-review finding): if the bootstrap {@code sendMessage} throws before
 * {@code broadcaster.register}, the attach→bootstrap→register sequence must self-heal —
 * releasing the gate permit and detaching the drain VT via the same idempotent
 * {@code cleanup(session)} used by the close/error callbacks — because container
 * behaviour for a throw out of {@code afterConnectionEstablished} is not guaranteed to
 * invoke those callbacks.
 */
class ObserverWebSocketHandlerTest {

    @Test
    void bootstrapSendFailure_releasesPermitAndDetachesDrainVt() throws Exception {
        WorldGrid grid = new WorldGrid(new GridConfig(16, 16));
        EnvironmentEngine env = mock(EnvironmentEngine.class);
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of()));
        ObserverFrameBuilder builder = new ObserverFrameBuilder();
        ObserverOutboundSender sender = new ObserverOutboundSender();
        ObserverBroadcaster broadcaster = new ObserverBroadcaster(builder, grid, env,
                new BotRegistry(), new SpeciesSpawnCounter(), sender);
        ObserverSessionGate gate = new ObserverSessionGate(new ObserverConfig(true, 4));
        ObserverWebSocketHandler handler =
                new ObserverWebSocketHandler(broadcaster, sender, gate, builder, grid);

        // Replicate the real handshake precondition: beforeHandshake acquires a permit and
        // stamps the ATTR_PERMIT marker into what becomes the session's attribute map.
        Map<String, Object> handshakeAttrs = new HashMap<>();
        boolean admitted = gate.beforeHandshake(mock(ServerHttpRequest.class),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), handshakeAttrs);
        assertThat(admitted).as("precondition: handshake acquired a permit").isTrue();
        assertThat(gate.availablePermits()).as("precondition: one permit now held").isEqualTo(3);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("obs-fail");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(handshakeAttrs));
        doThrow(new IOException("boom: freshly-upgraded socket reset"))
                .when(session).sendMessage(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> handler.afterConnectionEstablished(session))
                .as("failure must propagate so the container sees the upgrade as failed")
                .isInstanceOf(IOException.class);

        assertThat(sender.attachedCount())
                .as("drain VT detached after the failed establish").isZero();
        assertThat(gate.availablePermits())
                .as("permit returned after the failed establish — no leak").isEqualTo(4);
        assertThat(broadcaster.observerCount())
                .as("never registered, so unregister is a no-op").isZero();
    }
}
