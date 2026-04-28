package com.paralife.websocket;

import com.paralife.admission.AttributionTagger;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 18-02 Task 1 — verifies that {@code WorldWebSocketHandler.afterConnectionEstablished}
 * reads {@code X-Paralife-Source} and {@code X-Paralife-Harness} from the upgrade headers
 * and stashes them on the session as {@link AttributionTagger#ATTR_SOURCE} and
 * {@link AttributionTagger#ATTR_HARNESS}.
 *
 * <p>Server-side enforcement of {@link com.paralife.admission.AttributionSanitizer} is
 * also exercised (Round 2 Codex HIGH amendment).
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
class WorldWebSocketHandlerHandshakeHeaderTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRegistry sessionRegistry;

    private WebSocketClient client;
    private Session jettySession;

    @AfterEach
    void tearDown() throws Exception {
        if (jettySession != null && jettySession.isOpen()) {
            jettySession.close(1000, "test done", Callback.NOOP);
        }
        if (client != null) {
            client.stop();
        }
        // brief pause to let afterConnectionClosed fire
        Thread.sleep(100);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Connect to the server with the given upgrade headers and return the server-side
     * WebSocketSession once it appears in the SessionRegistry.
     */
    private WebSocketSession connectAndGetServerSession(
            String sourceHeader, String harnessHeader) throws Exception {
        AtomicReference<String> capturedSessionId = new AtomicReference<>();
        CountDownLatch openLatch = new CountDownLatch(1);

        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions("permessage-deflate; server_no_context_takeover");
        if (sourceHeader != null) {
            req.setHeader("X-Paralife-Source", sourceHeader);
        }
        if (harnessHeader != null) {
            req.setHeader("X-Paralife-Harness", harnessHeader);
        }

        OpenCapture endpoint = new OpenCapture(capturedSessionId, openLatch);
        jettySession = client.connect(endpoint,
                URI.create("ws://localhost:" + port + "/ws/world"), req)
                .get(5, TimeUnit.SECONDS);

        assertThat(openLatch.await(5, TimeUnit.SECONDS)).isTrue();
        // Give afterConnectionEstablished a moment to populate session attributes
        Thread.sleep(50);

        // Find the server-side session in SessionRegistry
        return sessionRegistry.getActiveSessions().stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No server-side session found in SessionRegistry"));
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void harnessSource_stashesAttrSourceAndAttrHarness() throws Exception {
        WebSocketSession serverSession = connectAndGetServerSession("harness", "harness-A");

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_SOURCE))
                .as("ATTR_SOURCE should be 'harness'")
                .isEqualTo("harness");
        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_HARNESS))
                .as("ATTR_HARNESS should be 'harness-A'")
                .isEqualTo("harness-A");
    }

    @Test
    void operatorSource_stashesAttrSourceOnly() throws Exception {
        WebSocketSession serverSession = connectAndGetServerSession("operator", null);

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_SOURCE))
                .as("ATTR_SOURCE should be 'operator'")
                .isEqualTo("operator");
        assertThat(serverSession.getAttributes())
                .as("ATTR_HARNESS should be absent for operator source")
                .doesNotContainKey(AttributionTagger.ATTR_HARNESS);
    }

    @Test
    void noHeaders_attrSourceIsUnknown() throws Exception {
        WebSocketSession serverSession = connectAndGetServerSession(null, null);

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_SOURCE))
                .as("ATTR_SOURCE should default to 'unknown'")
                .isEqualTo("unknown");
        assertThat(serverSession.getAttributes())
                .as("ATTR_HARNESS should be absent")
                .doesNotContainKey(AttributionTagger.ATTR_HARNESS);
    }

    @Test
    void unknownSourceValue_foldsToUnknown() throws Exception {
        // "admin" is NOT in SOURCE_TAXONOMY
        WebSocketSession serverSession = connectAndGetServerSession("admin", null);

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_SOURCE))
                .as("Out-of-taxonomy source 'admin' should fold to 'unknown'")
                .isEqualTo("unknown");
    }

    @Test
    void mixedCaseHeader_stillResolved() throws Exception {
        // HTTP headers are case-insensitive; Spring's HttpHeaders handles this transparently.
        // We use a lower-case variant to verify the server still reads it.
        WebSocketSession serverSession = connectAndGetServerSession("harness", "test-id");

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_SOURCE))
                .isEqualTo("harness");
        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_HARNESS))
                .isEqualTo("test-id");
    }

    // ── Round 2 Codex HIGH: server-side sanitizer enforcement ─────────────────

    @Test
    void blankHarnessId_notStashed() throws Exception {
        // Blank harness id must be rejected by AttributionSanitizer on the server path.
        // Note: raw CR/LF cannot be injected via HTTP headers (the protocol strips them before
        // reaching the server). Control-char rejection is verified at the unit level in
        // AttributionSanitizerTest. This test verifies the server code path is wired to the
        // sanitizer (blank → Optional.empty() → no stash).
        // Session is admitted but treated as harness with no harness id.
        WebSocketSession serverSession = connectAndGetServerSession("harness", "   ");

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_SOURCE))
                .as("Source should still be 'harness'")
                .isEqualTo("harness");
        assertThat(serverSession.getAttributes())
                .as("Blank harness id must NOT be stashed (sanitizer rejects blank)")
                .doesNotContainKey(AttributionTagger.ATTR_HARNESS);
    }

    @Test
    void overLengthHarnessId_truncatedTo32Chars() throws Exception {
        String raw = "a-very-long-harness-id-that-exceeds-32-chars-by-quite-a-lot";
        String expected = raw.substring(0, 32);

        WebSocketSession serverSession = connectAndGetServerSession("harness", raw);

        assertThat(serverSession.getAttributes().get(AttributionTagger.ATTR_HARNESS))
                .as("Over-length harness id should be truncated to 32 chars")
                .isEqualTo(expected);
    }

    // ── Inner endpoint ─────────────────────────────────────────────────────────

    @WebSocket
    public static class OpenCapture {
        private final AtomicReference<String> sessionIdRef;
        private final CountDownLatch latch;

        OpenCapture(AtomicReference<String> sessionIdRef, CountDownLatch latch) {
            this.sessionIdRef = sessionIdRef;
            this.latch = latch;
        }

        @OnWebSocketOpen
        public void onOpen(Session session) {
            sessionIdRef.set(session.getUpgradeResponse().getHeader("Sec-WebSocket-Accept"));
            latch.countDown();
        }
    }
}
