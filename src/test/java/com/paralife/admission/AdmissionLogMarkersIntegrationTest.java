package com.paralife.admission;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.TickEngine;
import com.paralife.engine.TickEvent;
import com.paralife.websocket.WorldWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 Plan 11 — Phase 17 D-19 log-marker emission tests.
 *
 * <p>Each {@code @Test} drives one log marker and asserts via SUBSTRING matchers (claude LOW
 * review fix — fragile exact-string regex would break on harmless message tweaks). The
 * D-19 operator contract is preserved at the prefix + key-field level.
 *
 * <p>Markers covered:
 * <ul>
 *   <li>{@code ADMISSION rejected reason=world-full active=N/cap}</li>
 *   <li>{@code ADMISSION rejected reason=tick-overload}</li>
 *   <li>{@code ADMISSION maintenance state=on} — covered in nested {@link MaintenanceStartup}.</li>
 *   <li>{@code BACKPRESSURE stalled session=... queue-depth=... limit=...}</li>
 *   <li>{@code BACKPRESSURE expired entity=... session=...}</li>
 *   <li>{@code BACKPRESSURE resumed session=... entity=... respawnCountRestored=...}</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.tick.interval-ms=100",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.world.rock.density-threshold=255",
        "paralife.admission.cap=1",   // allow world-full assertion with a single registered entity
        "paralife.admission.backpressure.outbound-queue-size=1",
        "paralife.admission.backpressure.grace-window-ticks=2",
        "paralife.admission.tick-overload.high-water-pct=50",
        "paralife.admission.tick-overload.low-water-pct=30",
        "paralife.admission.tick-overload.window-ticks=5"
})
@Tag("slow")
class AdmissionLogMarkersIntegrationTest {

    @Autowired AdmissionGate admissionGate;
    @Autowired ResumeTokenRegistry resumeTokenRegistry;
    @Autowired OutboundSender outboundSender;
    @Autowired WorldWebSocketHandler handler;
    @Autowired TickHealthMonitor tickHealthMonitor;
    @Autowired TickEngine tickEngine;
    @Autowired AdmissionMetrics admissionMetrics;

    private ListAppender<ILoggingEvent> gateAppender;
    private ListAppender<ILoggingEvent> handlerAppender;
    private ListAppender<ILoggingEvent> registryAppender;

    @BeforeEach
    void attachAppenders() {
        gateAppender = attach((Logger) LoggerFactory.getLogger(AdmissionGate.class));
        handlerAppender = attach((Logger) LoggerFactory.getLogger(WorldWebSocketHandler.class));
        registryAppender = attach((Logger) LoggerFactory.getLogger(ResumeTokenRegistry.class));

        // Reset shared bean state across tests (single Spring context).
        long[] window = (long[]) ReflectionTestUtils.getField(tickHealthMonitor, "window");
        if (window != null) for (int i = 0; i < window.length; i++) window[i] = 0L;
        ReflectionTestUtils.setField(tickHealthMonitor, "head", 0);
        ReflectionTestUtils.setField(tickHealthMonitor, "sum", 0L);
        ReflectionTestUtils.setField(tickHealthMonitor, "filled", 0);
        ReflectionTestUtils.setField(tickHealthMonitor, "overloaded", false);
    }

    @AfterEach
    void detachAppenders() {
        detach((Logger) LoggerFactory.getLogger(AdmissionGate.class), gateAppender);
        detach((Logger) LoggerFactory.getLogger(WorldWebSocketHandler.class), handlerAppender);
        detach((Logger) LoggerFactory.getLogger(ResumeTokenRegistry.class), registryAppender);
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> a = new ListAppender<>();
        a.setContext(logger.getLoggerContext());
        a.start();
        logger.addAppender(a);
        return a;
    }

    private static void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<String> messages(ListAppender<ILoggingEvent> a) {
        synchronized (a.list) {
            List<ILoggingEvent> snap = new ArrayList<>(a.list);
            return snap.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    @Test
    void admissionRejectedWorldFull() {
        // cap=1; first request fills the cap, second is rejected.
        // worldGrid.livingEntityCount returns 0 by default; we mock the count by registering
        // an actual particle. Simpler: ask AdmissionGate to evaluate twice with alreadyAlive=false
        // BUT cap counts livingEntityCount which is 0 unless we add an entity. To trigger
        // world-full deterministically, place a particle on the grid via WorldGrid.
        var worldGrid = (com.paralife.world.WorldGrid) ReflectionTestUtils.getField(admissionGate, "worldGrid");
        var profile = new com.paralife.world.Entity.Particle(
                "log-marker-test-1", com.paralife.world.Entity.ParticleType.CATALYST, 50, 50);
        worldGrid.trySetEntity(0, 0, profile);

        AdmissionGate.AdmissionRequest req = new AdmissionGate.AdmissionRequest(
                "session-X", 100L, false, false, 0, Optional.empty());
        AdmissionResult r = admissionGate.evaluate(req);
        assertThat(r).isInstanceOf(AdmissionResult.Reject.class);
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.WORLD_FULL);

        // Substring matcher (NOT exact regex) per claude LOW review fix.
        assertThat(messages(gateAppender)).anyMatch(m ->
                m.contains("ADMISSION rejected") &&
                m.contains("reason=world-full") &&
                m.contains("active=") &&
                m.contains("/"));

        // Cleanup so other tests don't see this lingering entity.
        worldGrid.clearEntity(0, 0);
    }

    @Test
    void admissionRejectedTickOverload() {
        // Drive overload by writing samples directly to the monitor's window.
        for (int i = 1; i <= 5; i++) {
            ReflectionTestUtils.setField(tickEngine, "lastTickWorkMs", 80L);
            tickHealthMonitor.onTick(new TickEvent(i));
        }
        assertThat(tickHealthMonitor.isOverloaded()).isTrue();

        AdmissionResult r = admissionGate.evaluate(new AdmissionGate.AdmissionRequest(
                "session-X", 100L, false, false, 0, Optional.empty()));
        assertThat(r).isInstanceOf(AdmissionResult.Reject.class);
        assertThat(((AdmissionResult.Reject) r).token()).isEqualTo(RejectionToken.TICK_OVERLOAD);

        assertThat(messages(gateAppender)).anyMatch(m ->
                m.contains("ADMISSION rejected") &&
                m.contains("reason=tick-overload"));
    }

    @Test
    void backpressureStalled() {
        // Use a fake WebSocketSession + the real WorldWebSocketHandler @PostConstruct overflow
        // callback (wired during context startup). Fire offer overflow on the OutboundSender;
        // the wired callback calls markStalled which logs BACKPRESSURE stalled.

        // First, register a fake session in SessionRegistry so the overflow callback's
        // sessionRegistry.getSession(sessionId) lookup succeeds.
        var sessionRegistry = (com.paralife.websocket.SessionRegistry)
                ReflectionTestUtils.getField(handler, "sessionRegistry");

        FakeSession fake = new FakeSession("log-marker-stall", true);
        sessionRegistry.register(fake);
        fake.getAttributes().put("entityId", "log-marker-entity-stall");
        fake.getAttributes().put("resumeToken", resumeTokenRegistry.issueActive(
                "log-marker-entity-stall", fake.getId()));

        outboundSender.attachSession(fake, 1);
        fake.holdNextSend(true);
        // Fill: in-flight + queued + overflow.
        outboundSender.offer(fake.getId(), new Frame.RegisterFrame('C'));
        outboundSender.offer(fake.getId(), new Frame.RegisterFrame('M'));
        outboundSender.offer(fake.getId(), new Frame.RegisterFrame('S'));   // overflow → callback → markStalled

        // Wait briefly for the overflow callback to run on the OutboundSender VT.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        boolean fired = false;
        while (System.nanoTime() < deadline) {
            fired = messages(handlerAppender).stream()
                    .anyMatch(m -> m.contains("BACKPRESSURE stalled"));
            if (fired) break;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(fired).isTrue();

        assertThat(messages(handlerAppender)).anyMatch(m ->
                m.contains("BACKPRESSURE stalled") &&
                m.contains("session=") &&
                m.contains("queue-depth=") &&
                m.contains("limit="));

        fake.releaseAll();
        outboundSender.detachSession(fake.getId());
        sessionRegistry.unregister(fake.getId());
    }

    @Test
    void backpressureExpired() {
        // Wire a no-op cleanup callback so no NPE when sweep fires.
        // (The handler bean already wired its own callback; we leave it alone.)
        String token = resumeTokenRegistry.issueActive("log-marker-entity-expired", "log-marker-session-expired");
        resumeTokenRegistry.convertToStalled(token, 100L);

        // Publish a TickEvent past expiry so the @EventListener sweeps. Grace window=2 in
        // this test class (see @TestPropertySource), so expiry is 100+2=102; sweep at tick 110.
        resumeTokenRegistry.onTick(new TickEvent(110));

        assertThat(messages(registryAppender)).anyMatch(m ->
                m.contains("BACKPRESSURE expired") &&
                m.contains("entity=") &&
                m.contains("session="));
    }

    @Test
    void backpressureResumed() {
        // Issue + stall a token, then drive a rebind via the handler's handleTextMessage.
        String token = resumeTokenRegistry.issueActive("log-marker-entity-resumed", "log-marker-session-resumed");
        resumeTokenRegistry.convertToStalled(token, 0L);

        // Build a fresh fake session and rebind via r|C|<token> through the handler.
        FakeSession fake = new FakeSession("log-marker-resumed-new", true);
        var sessionRegistry = (com.paralife.websocket.SessionRegistry)
                ReflectionTestUtils.getField(handler, "sessionRegistry");
        sessionRegistry.register(fake);
        outboundSender.attachSession(fake, 8);

        TextMessage msg = new TextMessage(PerceptionCodec.encode(new Frame.RegisterFrame('C', Optional.of(token))));
        ReflectionTestUtils.invokeMethod(handler, "handleTextMessage", fake, msg);

        assertThat(messages(handlerAppender)).anyMatch(m ->
                m.contains("BACKPRESSURE resumed") &&
                m.contains("session=") &&
                m.contains("entity="));

        outboundSender.detachSession(fake.getId());
        sessionRegistry.unregister(fake.getId());
    }

    /**
     * Exact match per plan: maintenance startup line has no variable fields, so substring
     * matchers and exact-string comparison are equivalent. Nested context with maintenance=on.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
            "paralife.tick.auto-start=false",
            "paralife.simulation.enabled=false",
            "paralife.simulation.events.enabled=false",
            "paralife.world.width=8",
            "paralife.world.height=8",
            "paralife.world.rock.density-threshold=255",
            "paralife.admission.cap=2",
            "paralife.admission.maintenance=true"
    })
    @Tag("slow")
    class MaintenanceStartup {

        @Autowired WorldWebSocketHandler localHandler;

        @Test
        void admissionMaintenanceStartup() {
            // The handler's @PostConstruct fires on context init when maintenance=true.
            // Capture the message via the static logger context: the line was emitted before
            // this test method started, so we attach a fresh appender and invoke a no-op then
            // re-run the wireCrossBeanCallbacks check via reflection — alternative: assert that
            // the @PostConstruct line was emitted by querying the Spring context's startup logs
            // via a captured appender that wraps WorldWebSocketHandler logger from the parent
            // ApplicationContext startup cache.
            //
            // Simplest: attach an appender, then reflectively re-run wireCrossBeanCallbacks to
            // re-emit the line (idempotent log emission is fine for D-19 verification).
            Logger handlerLogger = (Logger) LoggerFactory.getLogger(WorldWebSocketHandler.class);
            ListAppender<ILoggingEvent> a = attach(handlerLogger);
            try {
                ReflectionTestUtils.invokeMethod(localHandler, "wireCrossBeanCallbacks");
                assertThat(messages(a)).anyMatch(m -> m.contains("ADMISSION maintenance state=on"));
            } finally {
                detach(handlerLogger, a);
            }
        }
    }

    // ---- FakeSession (mirrors OutboundSenderTest) ----------------------------------

    static class FakeSession implements WebSocketSession {
        final String id;
        final boolean open;
        final List<String> captured = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> attrs = new ConcurrentHashMap<>();
        private volatile java.util.concurrent.CountDownLatch holdLatch;

        FakeSession(String id, boolean open) { this.id = id; this.open = open; }

        void holdNextSend(boolean hold) {
            this.holdLatch = hold ? new java.util.concurrent.CountDownLatch(1) : null;
        }

        void releaseAll() {
            java.util.concurrent.CountDownLatch l = this.holdLatch;
            this.holdLatch = null;
            if (l != null) l.countDown();
        }

        @Override public String getId() { return id; }
        @Override public boolean isOpen() { return open; }
        @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
            java.util.concurrent.CountDownLatch l = this.holdLatch;
            if (l != null) {
                try {
                    l.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // CRITICAL: clear the latch so subsequent sendMessage calls (e.g. sendOutOfBand
                    // invoked by markStalled after detach) do not deadlock waiting on the same latch.
                    this.holdLatch = null;
                    throw new IOException("interrupted");
                }
            }
            captured.add(message.getPayload().toString());
        }
        @Override public URI getUri() { return null; }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
