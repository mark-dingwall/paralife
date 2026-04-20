package com.paralife.websocket;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 UAT Test 7 follow-up: server→client PING frames keep otherwise-idle
 * bot sessions alive past Jetty's read-idle timeout.
 *
 * <p>Runs with fast ticks ({@code 50ms}) and a short keepalive cadence
 * ({@code 3} ticks → ping every 150ms) so the test can observe multiple pings
 * inside a couple of seconds. Idle-timeout is bumped low enough that the
 * timer would fire during the test window if pings weren't flowing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=50",
        "paralife.tick.auto-start=true",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.websocket.keepalive-ticks=3",
        "paralife.websocket.idle-timeout-ms=2000"
})
class WebSocketKeepaliveIntegrationTest {

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
    void serverSendsPingFramesOnKeepaliveCadence() throws Exception {
        PingCaptureEndpoint endpoint = new PingCaptureEndpoint();
        session = openConnection(endpoint);
        session.sendText(PerceptionCodec.encode(new Frame.RegisterFrame('C')), Callback.NOOP);

        assertThat(endpoint.pingLatch.await(3, TimeUnit.SECONDS))
                .as("Expected ≥2 PING frames within 3s at 150ms cadence")
                .isTrue();
        assertThat(endpoint.pingCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void idleBotSurvivesPastServerIdleTimeout() throws Exception {
        PingCaptureEndpoint endpoint = new PingCaptureEndpoint();
        session = openConnection(endpoint);
        session.sendText(PerceptionCodec.encode(new Frame.RegisterFrame('C')), Callback.NOOP);

        // Wait longer than idle-timeout-ms (2000) without sending anything.
        // If pings weren't flowing, Jetty would close the session before this point.
        Thread.sleep(2500);

        assertThat(session.isOpen())
                .as("Session should still be open after idle window thanks to server pings")
                .isTrue();
        assertThat(endpoint.pingCount.get())
                .as("Should have received multiple pings during the idle window")
                .isGreaterThanOrEqualTo(3);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Session openConnection(Object endpoint) throws Exception {
        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");
        return client.connect(endpoint, URI.create("ws://localhost:" + port + "/ws/world"), req)
                .get(5, TimeUnit.SECONDS);
    }

    @WebSocket
    public static class PingCaptureEndpoint {
        final AtomicInteger pingCount = new AtomicInteger();
        final CountDownLatch pingLatch = new CountDownLatch(2);

        @OnWebSocketOpen
        public void onOpen(Session s) {
            // no-op
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            // Drain text frames (S/T/E) without inspecting — ping arrival is the contract.
        }

        @OnWebSocketFrame
        public void onFrame(org.eclipse.jetty.websocket.api.Frame frame, Callback callback) {
            try {
                if (frame.getType() == org.eclipse.jetty.websocket.api.Frame.Type.PING) {
                    pingCount.incrementAndGet();
                    pingLatch.countDown();
                }
            } finally {
                callback.succeed();
            }
        }
    }
}
