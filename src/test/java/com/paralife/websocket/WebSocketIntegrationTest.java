package com.paralife.websocket;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 15-11: codec-native lifecycle tests.
 *
 * <p>Exercises the post-plan-15-06 wire protocol:
 * <ul>
 *   <li>Connection establishment with {@code permessage-deflate;
 *       server_no_context_takeover} negotiation (D-33).</li>
 *   <li>No Welcome frame — the server stays quiet until it sees an {@code r|}
 *       frame (collapsed into {@code S|} sync per SCHEMA §5).</li>
 *   <li>Register → {@link Frame.SyncFrame} response carrying {@code entityId}.</li>
 *   <li>Tick broadcast → stream of {@link Frame.TickFrame}s with monotonic
 *       {@code tickId}.</li>
 *   <li>Malformed frame → {@link Frame.ErrorFrame} with code 400.</li>
 * </ul>
 *
 * <p>Uses Jetty's native {@link WebSocketClient} (added in plan 15-09) because
 * Spring's {@link org.springframework.web.socket.client.standard.StandardWebSocketClient}
 * cannot advertise {@code Sec-WebSocket-Extensions} through its public API, and
 * the server-side {@code DeflateEnforcementFilter} rejects upgrades without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",  // Fast ticks for testing
        "paralife.tick.auto-start=true",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private WebSocketClient client;
    private Session session;

    @AfterEach
    void tearDown() throws Exception {
        if (session != null && session.isOpen()) {
            session.close(1000, "test done", Callback.NOOP);
        }
        if (client != null) {
            client.stop();
        }
    }

    @Test
    void tickFramesBroadcastAfterRegister() throws Exception {
        // Post-plan-15 the server only sends T frames to registered bots.
        // An unregistered session stays silent — pre-plan-15 "welcome/tick"
        // broadcast semantics are gone.
        var frames = new CopyOnWriteArrayList<Frame>();
        var tickLatch = new CountDownLatch(3);

        CaptureEndpoint cap = new CaptureEndpoint(frames, frame -> {
            if (frame instanceof Frame.TickFrame) tickLatch.countDown();
        });
        session = openConnection(cap);

        // Register first — otherwise no T frames will flow.
        sendEncoded(session, new Frame.RegisterFrame('C'));

        assertThat(tickLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive at least 3 tick frames after register")
                .isTrue();

        long prevTickId = -1L;
        int tickCount = 0;
        for (Frame f : frames) {
            if (f instanceof Frame.TickFrame t) {
                assertThat(t.tickId()).isGreaterThanOrEqualTo(0L);
                if (prevTickId >= 0) {
                    assertThat(t.tickId()).isGreaterThanOrEqualTo(prevTickId);
                }
                prevTickId = t.tickId();
                tickCount++;
            }
        }
        assertThat(tickCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    void registerReceivesSyncFrame() throws Exception {
        var frames = new CopyOnWriteArrayList<Frame>();
        var syncLatch = new CountDownLatch(1);

        CaptureEndpoint cap = new CaptureEndpoint(frames, frame -> {
            if (frame instanceof Frame.SyncFrame) syncLatch.countDown();
        });
        session = openConnection(cap);

        // Plan 15-11: no welcome frame — client sends r| first. Server responds S|<id>.
        sendEncoded(session, new Frame.RegisterFrame('C'));

        assertThat(syncLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive S (sync) frame in response to r")
                .isTrue();

        Frame.SyncFrame sync = frames.stream()
                .filter(f -> f instanceof Frame.SyncFrame)
                .map(f -> (Frame.SyncFrame) f)
                .findFirst()
                .orElseThrow();
        assertThat(sync.entityId()).isNotBlank();
    }

    @Test
    void invalidMessageReturnsErrorFrame() throws Exception {
        var frames = new CopyOnWriteArrayList<Frame>();
        var errorLatch = new CountDownLatch(1);

        CaptureEndpoint cap = new CaptureEndpoint(frames, frame -> {
            if (frame instanceof Frame.ErrorFrame) errorLatch.countDown();
        });
        session = openConnection(cap);

        // Malformed — not a valid single-letter frame type per SCHEMA §5/§6.
        session.sendText("{\"not\":\"valid\"}", Callback.NOOP);

        assertThat(errorLatch.await(5, TimeUnit.SECONDS))
                .as("Should receive E| error frame for malformed input")
                .isTrue();

        Frame.ErrorFrame err = frames.stream()
                .filter(f -> f instanceof Frame.ErrorFrame)
                .map(f -> (Frame.ErrorFrame) f)
                .findFirst()
                .orElseThrow();
        assertThat(err.code()).isEqualTo(400);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Session openConnection(CaptureEndpoint endpoint) throws Exception {
        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");
        return client.connect(endpoint, URI.create("ws://localhost:" + port + "/ws/world"), req)
                .get(5, TimeUnit.SECONDS);
    }

    private static void sendEncoded(Session session, Frame frame) {
        session.sendText(PerceptionCodec.encode(frame), Callback.NOOP);
    }

    @WebSocket
    public static class CaptureEndpoint {
        private final CopyOnWriteArrayList<Frame> sink;
        private final java.util.function.Consumer<Frame> onFrame;

        CaptureEndpoint(CopyOnWriteArrayList<Frame> sink, java.util.function.Consumer<Frame> onFrame) {
            this.sink = sink;
            this.onFrame = onFrame;
        }

        @OnWebSocketOpen
        public void onOpen(Session s) {
            // no-op — session reference retained by caller via connect().get()
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            try {
                Frame f = PerceptionCodec.decode(message);
                sink.add(f);
                onFrame.accept(f);
            } catch (Exception e) {
                // decode failures are their own signal; rethrow would close the session.
                sink.add(new Frame.ErrorFrame(999, Optional.of("decode-fail: " + e.getMessage())));
            }
        }
    }
}
