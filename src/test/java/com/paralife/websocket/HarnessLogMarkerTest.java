package com.paralife.websocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.admission.AttributionTagger;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 18-02 Task 1 — verifies {@code HARNESS connected} and {@code HARNESS disconnected}
 * log markers are emitted by {@link WorldWebSocketHandler} on every connection lifecycle.
 *
 * <p>Uses a {@code ListAppender} on {@link WorldWebSocketHandler}'s logger. Each test connects
 * a Jetty client, verifies the HARNESS connected marker, then closes and verifies the
 * HARNESS disconnected marker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.world.rock.density-threshold=255",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false"
})
class HarnessLogMarkerTest {

    @LocalServerPort
    private int port;

    private ListAppender<ILoggingEvent> appender;
    private Logger handlerLogger;
    private WebSocketClient client;
    private Session jettySession;

    @BeforeEach
    void attachAppender() {
        handlerLogger = (Logger) LoggerFactory.getLogger(WorldWebSocketHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        handlerLogger.detachAppender(appender);
        if (jettySession != null && jettySession.isOpen()) {
            jettySession.close(1000, "test done", Callback.NOOP);
        }
        if (client != null) {
            client.stop();
        }
    }

    @Test
    void harnessConnection_emitsConnectedAndDisconnectedMarkers() throws Exception {
        connect("harness", "harness-A");

        // Wait briefly for afterConnectionEstablished
        Thread.sleep(100);

        // Assert HARNESS connected marker
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(
                        "HARNESS connected tick=\\d+ session=\\S+ harness=harness-A source=harness active=\\d+"));

        // Close the connection and wait for afterConnectionClosed
        jettySession.close(1000, "test done", Callback.NOOP);
        Thread.sleep(300);

        // Assert HARNESS disconnected marker
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(
                        "HARNESS disconnected tick=\\d+ session=\\S+ harness=\\S+ source=\\S+ reason=(token|graceful|stalled-held)"));
    }

    @Test
    void operatorConnection_emitsConnectedMarkerWithDashForHarness() throws Exception {
        connect("operator", null);
        Thread.sleep(100);

        // harness=- for non-harness sources
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(
                        "HARNESS connected tick=\\d+ session=\\S+ harness=- source=operator active=\\d+"));
    }

    @Test
    void unknownConnection_emitsConnectedMarkerWithUnknownSource() throws Exception {
        connect(null, null);
        Thread.sleep(100);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(
                        "HARNESS connected tick=\\d+ session=\\S+ harness=- source=unknown active=\\d+"));
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private void connect(String sourceHeader, String harnessHeader) throws Exception {
        CountDownLatch openLatch = new CountDownLatch(1);

        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");
        if (sourceHeader != null) req.setHeader("X-Paralife-Source", sourceHeader);
        if (harnessHeader != null) req.setHeader("X-Paralife-Harness", harnessHeader);

        jettySession = client.connect(new SimpleOpenEndpoint(openLatch),
                URI.create("ws://localhost:" + port + "/ws/world"), req)
                .get(5, TimeUnit.SECONDS);
        openLatch.await(5, TimeUnit.SECONDS);
    }

    @WebSocket
    public static class SimpleOpenEndpoint {
        private final CountDownLatch latch;

        SimpleOpenEndpoint(CountDownLatch latch) {
            this.latch = latch;
        }

        @OnWebSocketOpen
        public void onOpen(Session s) {
            latch.countDown();
        }
    }
}
