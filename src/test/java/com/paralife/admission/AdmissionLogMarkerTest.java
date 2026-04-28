package com.paralife.admission;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.admission.AttributionTagger;
import com.paralife.websocket.WorldWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 18-02 Task 2 — verifies that the {@code ADMISSION rejected} log marker carries
 * {@code source=<v>[ harness=<id>]} fields when AdmissionGate rejects a request from a
 * session that has attribution attributes set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.world.rock.density-threshold=255",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false",
        "paralife.admission.cap=100",
        "paralife.admission.maintenance=true"   // maintenance=true → every request rejected
})
class AdmissionLogMarkerTest {

    @Autowired
    private AdmissionGate admissionGate;

    private ListAppender<ILoggingEvent> appender;
    private Logger gateLogger;

    @BeforeEach
    void attachAppender() {
        gateLogger = (Logger) LoggerFactory.getLogger(AdmissionGate.class);
        appender = new ListAppender<>();
        appender.start();
        gateLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        gateLogger.detachAppender(appender);
        appender.list.clear();
    }

    @Test
    void rejectionFromHarnessSession_logLineCarriesSourceAndHarness() {
        WebSocketSession session = fakeSession("harness", "harness-A");

        AdmissionGate.AdmissionRequest req = new AdmissionGate.AdmissionRequest(
                session.getId(), 42L, false, false, 0, java.util.Optional.empty());

        admissionGate.evaluate(req, session);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(
                        "ADMISSION rejected tick=\\d+ session=\\S+ reason=\\S+ source=harness harness=harness-A active=\\d+/\\d+"));
    }

    @Test
    void rejectionFromUnknownSession_logLineCarriesSourceOnly() {
        WebSocketSession session = fakeSession("unknown", null);

        AdmissionGate.AdmissionRequest req = new AdmissionGate.AdmissionRequest(
                session.getId(), 43L, false, false, 0, java.util.Optional.empty());

        admissionGate.evaluate(req, session);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(
                        "ADMISSION rejected tick=\\d+ session=\\S+ reason=\\S+ source=unknown active=\\d+/\\d+")
                        && !m.contains("harness="));
    }

    // ── Fake session builder ────────────────────────────────────────────────────

    private static WebSocketSession fakeSession(String source, String harnessId) {
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        if (source != null) {
            attrs.put(AttributionTagger.ATTR_SOURCE, source);
        }
        if (harnessId != null) {
            attrs.put(AttributionTagger.ATTR_HARNESS, harnessId);
        }
        return new StubWebSocketSession("session-" + System.nanoTime(), attrs);
    }

    /**
     * Minimal stub implementing just enough of {@link WebSocketSession} for attribution tests.
     */
    private record StubWebSocketSession(String id, Map<String, Object> attrs)
            implements WebSocketSession {

        @Override public String getId() { return id; }
        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public URI getUri() { return URI.create("ws://localhost/ws/world"); }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public boolean isOpen() { return true; }
        @Override public void sendMessage(WebSocketMessage<?> message) {}
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
        @Override public String getAcceptedProtocol() { return null; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 65536; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 65536; }
    }
}
