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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
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
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of(), Set.of(), Set.of()));
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

    @Test
    void afterConnectionClosedReleasesLeaseDetachesAndUnregisters() throws Exception {
        Fixture f = fixture();
        WebSocketSession s = establishedObserver(f, "obs-close");
        assertThat(f.gate.availablePermits()).as("precondition: permit held").isEqualTo(3);
        assertThat(f.sender.attachedCount()).as("precondition: draining").isEqualTo(1);
        assertThat(f.broadcaster.observerCount()).as("precondition: registered").isEqualTo(1);

        f.handler.afterConnectionClosed(s, CloseStatus.NORMAL);

        assertThat(f.gate.availablePermits()).as("close releases the permit").isEqualTo(4);
        assertThat(f.sender.attachedCount()).as("close detaches the drain").isZero();
        assertThat(f.broadcaster.observerCount()).as("close unregisters the observer").isZero();
    }

    @Test
    void handleTransportErrorReleasesLeaseDetachesAndUnregisters() throws Exception {
        Fixture f = fixture();
        WebSocketSession s = establishedObserver(f, "obs-error");
        assertThat(f.gate.availablePermits()).isEqualTo(3);

        f.handler.handleTransportError(s, new IOException("reset"));

        assertThat(f.gate.availablePermits()).as("transport error releases the permit").isEqualTo(4);
        assertThat(f.sender.attachedCount()).as("transport error detaches the drain").isZero();
        assertThat(f.broadcaster.observerCount()).as("transport error unregisters the observer").isZero();
    }

    @Test
    void drainTerminalSendFailureRunsProductionCleanupEvenWhenCloseFails() throws Exception {
        // R2a-2: pins the PRODUCTION wiring `sender.attach(session, () -> cleanup(session))`
        // end-to-end — not merely "some callback runs". Establish a real observer, then make the
        // drain's world-frame send AND close() both fail (the exact path where a Jetty close callback
        // may never fire). The drain-OWNED cleanup must still return sender + broadcaster + permit to
        // baseline, and a later container close must not double-release. (RED-test by rewiring the
        // handler's attach callback to `() -> {}`.)
        Fixture f = fixture();
        WebSocketSession s = establishedObserver(f, "obs-drain-fail");
        assertThat(f.sender.attachedCount()).as("precondition: draining").isEqualTo(1);
        assertThat(f.broadcaster.observerCount()).as("precondition: registered").isEqualTo(1);
        assertThat(f.gate.availablePermits()).as("precondition: permit held").isEqualTo(3);

        // after a successful bootstrap, both the world-frame send and close now fail
        doThrow(new IOException("socket reset")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        doThrow(new RuntimeException("close failed")).when(s).close(org.mockito.ArgumentMatchers.any());

        f.sender.offer("obs-drain-fail", "{\"type\":\"world\"}"); // drain wakes → send throws → cleanup

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (f.gate.availablePermits() != 4 && System.nanoTime() < deadline) {
            Thread.sleep(20); // releaseIfHeld is cleanup's last step → gate the whole teardown on it
        }

        assertThat(f.sender.attachedCount()).as("drain cleanup detached the sender").isZero();
        assertThat(f.broadcaster.observerCount()).as("drain cleanup unregistered the observer").isZero();
        assertThat(f.gate.availablePermits()).as("drain cleanup released the permit").isEqualTo(4);

        f.handler.afterConnectionClosed(s, CloseStatus.NORMAL);
        assertThat(f.gate.availablePermits())
                .as("no double-release when close fires after drain-owned cleanup").isEqualTo(4);
    }

    private record Fixture(ObserverWebSocketHandler handler, ObserverSessionGate gate,
                           ObserverOutboundSender sender, ObserverBroadcaster broadcaster) {}

    private static Fixture fixture() {
        WorldGrid grid = new WorldGrid(new GridConfig(16, 16));
        EnvironmentEngine env = mock(EnvironmentEngine.class);
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of(), Set.of(), Set.of()));
        ObserverFrameBuilder builder = new ObserverFrameBuilder();
        ObserverOutboundSender sender = new ObserverOutboundSender();
        ObserverBroadcaster broadcaster = new ObserverBroadcaster(builder, grid, env,
                new BotRegistry(), new SpeciesSpawnCounter(), sender);
        ObserverSessionGate gate = new ObserverSessionGate(new ObserverConfig(true, 4));
        return new Fixture(new ObserverWebSocketHandler(broadcaster, sender, gate, builder, grid),
                gate, sender, broadcaster);
    }

    /** Drive a session through a real successful establish (attach + bootstrap send + register). */
    private static WebSocketSession establishedObserver(Fixture f, String id) throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        f.gate.beforeHandshake(mock(ServerHttpRequest.class), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attrs);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(attrs));
        f.handler.afterConnectionEstablished(session); // bootstrap send (unstubbed mock) succeeds
        return session;
    }
}
