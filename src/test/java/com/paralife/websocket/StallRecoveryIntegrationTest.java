package com.paralife.websocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paralife.admission.AdmissionMetrics;
import com.paralife.admission.OutboundSender;
import com.paralife.admission.ResumeTokenRegistry;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.TickEngine;
import com.paralife.engine.TickEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 Plan 11 — end-to-end STALLED-pivot integration test (uses BlockingWebSocketClient).
 *
 * <p>Drives queue overflow by holding inbound on a real WS client; the server's per-session VT
 * stops draining (TCP backpressure), the bounded queue fills, the overflow callback fires once,
 * and {@code WorldWebSocketHandler.markStalled} pivots the session. Exercises:
 * <ul>
 *   <li>Token-based rebind preserves entityId within grace window.</li>
 *   <li>Grace expiry reaps the entity; expired token is treated as fresh registration.</li>
 *   <li>Inbound on a STALLED session yields {@code E|408|reconnect-required} (delivered before close).</li>
 *   <li>Respawn count snapshotted at stall time and restored on rebind (T-17-stallbypass).</li>
 *   <li>Idempotent {@code markStalled}: callback fires exactly once even with many overflow attempts.</li>
 * </ul>
 *
 * <p><b>Tagged @slow:</b> requires a real Spring context + WebSocket loopback round-trip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.tick.interval-ms=50",
        "paralife.world.width=64",
        "paralife.world.height=64",
        "paralife.world.rock.density-threshold=255",     // suppress rocks (clean grid)
        "paralife.simulation.events.enabled=false",       // no lightning/toxin churn during overflow drive
        "paralife.simulation.enabled=false",              // no death/decay during stall drive
        "paralife.admission.cap=10",
        "paralife.admission.backpressure.outbound-queue-size=2",
        "paralife.admission.backpressure.grace-window-ticks=5",
        "paralife.admission.tick-overload.high-water-pct=95",
        "paralife.admission.tick-overload.low-water-pct=90",
        "paralife.admission.tick-overload.window-ticks=10",
        "paralife.websocket.max-respawns-per-session=10"
})
@Tag("slow")
class StallRecoveryIntegrationTest {

    @LocalServerPort int port;
    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired ResumeTokenRegistry resumeTokenRegistry;
    @Autowired TickEngine tickEngine;
    @Autowired BotRegistry botRegistry;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired MeterRegistry meterRegistry;
    @Autowired AdmissionMetrics admissionMetrics;

    private ListAppender<ILoggingEvent> handlerAppender;
    private ListAppender<ILoggingEvent> registryAppender;
    private final List<BlockingWebSocketClient> clients = new ArrayList<>();

    @BeforeEach
    void attachAppenders() {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(WorldWebSocketHandler.class);
        Logger registryLogger = (Logger) LoggerFactory.getLogger(ResumeTokenRegistry.class);
        handlerAppender = new ListAppender<>();
        registryAppender = new ListAppender<>();
        handlerAppender.setContext(handlerLogger.getLoggerContext());
        registryAppender.setContext(registryLogger.getLoggerContext());
        handlerAppender.start();
        registryAppender.start();
        handlerLogger.addAppender(handlerAppender);
        registryLogger.addAppender(registryAppender);
    }

    @AfterEach
    void teardown() {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(WorldWebSocketHandler.class);
        Logger registryLogger = (Logger) LoggerFactory.getLogger(ResumeTokenRegistry.class);
        handlerLogger.detachAppender(handlerAppender);
        registryLogger.detachAppender(registryAppender);
        handlerAppender.stop();
        registryAppender.stop();
        for (BlockingWebSocketClient c : clients) {
            try { c.close(); } catch (RuntimeException ignored) {}
        }
        clients.clear();
        botRegistry.clear();
    }

    private BlockingWebSocketClient newClient() throws Exception {
        BlockingWebSocketClient c = new BlockingWebSocketClient();
        c.connect(URI.create("ws://localhost:" + port + "/ws/world"), Duration.ofSeconds(5));
        clients.add(c);
        return c;
    }

    /** Read one S frame from the client; return parsed (entityId, resumeToken). */
    private Frame.SyncFrame awaitSync(BlockingWebSocketClient c) {
        long deadlineNs = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNs) {
            for (String raw : c.received()) {
                try {
                    Frame f = PerceptionCodec.decode(raw);
                    if (f instanceof Frame.SyncFrame sf) return sf;
                } catch (Exception ignored) {}
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError("S frame not observed within 5s; received=" + c.received());
    }

    /**
     * Drive overflow deterministically by directly offering frames to the OutboundSender for
     * {@code serverSessionId}. The client is expected to be holding receive (TCP backpressure
     * eventually blocks the server-side sendMessage, the VT can't drain, the bounded queue
     * fills, and the overflow callback fires). Caps at {@code maxOffers} to bound the worst
     * case if loopback throughput is unexpectedly high.
     */
    private void driveOverflow(String serverSessionId, int maxOffers) {
        // Use a large action arg payload to consume socket buffer faster (action frames are
        // permitted from server to client at the codec level — receiver is the test client which
        // doesn't validate the inbound direction).
        Frame frame = new Frame.RegisterFrame('C');
        for (int i = 0; i < maxOffers; i++) {
            outboundSender.offer(serverSessionId, frame);
            if (anyHandlerLog("BACKPRESSURE stalled")) return;
            if (i % 200 == 199) {
                // brief pause so the appender's writer thread has a chance to commit.
                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private List<String> handlerMessages() {
        synchronized (handlerAppender.list) {
            List<ILoggingEvent> snap = new ArrayList<>(handlerAppender.list);
            return snap.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    private List<String> registryMessages() {
        synchronized (registryAppender.list) {
            List<ILoggingEvent> snap = new ArrayList<>(registryAppender.list);
            return snap.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    private boolean anyHandlerLog(String substring) {
        return handlerMessages().stream().anyMatch(m -> m != null && m.contains(substring));
    }

    private boolean anyRegistryLog(String substring) {
        return registryMessages().stream().anyMatch(m -> m != null && m.contains(substring));
    }

    private boolean awaitHandlerLog(String substring, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (anyHandlerLog(substring)) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return anyHandlerLog(substring);
    }

    @Test
    void stallRecoveryRebindsEntityIdWithinGraceWindow() throws Exception {
        BlockingWebSocketClient bot1 = newClient();
        bot1.send(PerceptionCodec.encode(new Frame.RegisterFrame('C')));
        Frame.SyncFrame initialSync = awaitSync(bot1);
        String originalEntityId = initialSync.entityId();
        String originalToken = initialSync.resumeToken().orElseThrow();
        assertThat(originalToken).startsWith("r:").hasSize(18);

        // Stall: hold receive, then drive overflow via direct OutboundSender offers.
        String serverSessionId = botRegistry.getSessionForEntity(originalEntityId).orElseThrow();
        bot1.holdReceive(true);
        driveOverflow(serverSessionId, 5000);

        // Assert BACKPRESSURE stalled marker (substring matchers — claude LOW review).
        assertThat(awaitHandlerLog("BACKPRESSURE stalled", Duration.ofSeconds(5))).isTrue();
        List<String> stalledLines = handlerMessages().stream()
                .filter(m -> m.contains("BACKPRESSURE stalled"))
                .toList();
        assertThat(stalledLines).anyMatch(m -> m.contains("session=") && m.contains("queue-depth="));

        // Release client so it can drain the 408 + close events.
        bot1.releaseReceive();
        assertThat(bot1.awaitClose(Duration.ofSeconds(5))).isTrue();

        // Connect a fresh client, send r|C|<token> within grace window — entityId preserved.
        BlockingWebSocketClient bot2 = newClient();
        bot2.send(PerceptionCodec.encode(new Frame.RegisterFrame('C', Optional.of(originalToken))));
        Frame.SyncFrame rebindSync = awaitSync(bot2);
        assertThat(rebindSync.entityId()).isEqualTo(originalEntityId);
        assertThat(rebindSync.resumeToken()).isPresent();
        assertThat(rebindSync.resumeToken().get()).isNotEqualTo(originalToken);
        assertThat(rebindSync.resumeToken().get()).startsWith("r:").hasSize(18);

        // BACKPRESSURE resumed marker fired.
        assertThat(awaitHandlerLog("BACKPRESSURE resumed", Duration.ofSeconds(2))).isTrue();
    }

    @Test
    void stallExpiryReapsEntityAndForcesFreshRegistration() throws Exception {
        BlockingWebSocketClient bot1 = newClient();
        bot1.send(PerceptionCodec.encode(new Frame.RegisterFrame('M')));
        Frame.SyncFrame initialSync = awaitSync(bot1);
        String originalEntityId = initialSync.entityId();
        String originalToken = initialSync.resumeToken().orElseThrow();

        String serverSessionId = botRegistry.getSessionForEntity(originalEntityId).orElseThrow();
        bot1.holdReceive(true);
        driveOverflow(serverSessionId, 5000);
        assertThat(awaitHandlerLog("BACKPRESSURE stalled", Duration.ofSeconds(5))).isTrue();
        bot1.releaseReceive();
        bot1.awaitClose(Duration.ofSeconds(5));

        // Advance well past grace window (5 ticks). The stall happened at currentTick=0
        // (auto-start=false); publish events with tick numbers >= 6 so the sweep reaps.
        for (int i = 1; i <= 20; i++) {
            eventPublisher.publishEvent(new TickEvent(i));
        }
        // Wait for sweep to land — registry log "BACKPRESSURE expired".
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (anyRegistryLog("BACKPRESSURE expired")) break;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(anyRegistryLog("BACKPRESSURE expired")).isTrue();
        // Stalled-sessions gauge dropped to 0 after sweep reaped the entry.
        double stalledGauge = meterRegistry.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value();
        assertThat(stalledGauge).isEqualTo(0.0);

        // Reconnect with the (now-expired) token — server falls through to fresh registration.
        BlockingWebSocketClient bot2 = newClient();
        bot2.send(PerceptionCodec.encode(new Frame.RegisterFrame('M', Optional.of(originalToken))));
        Frame.SyncFrame freshSync = awaitSync(bot2);
        assertThat(freshSync.entityId()).isNotEqualTo(originalEntityId);
        assertThat(freshSync.resumeToken()).isPresent();
        assertThat(freshSync.resumeToken().get()).isNotEqualTo(originalToken);
    }

    @Test
    void stalledSessionInboundIsRejectedWith408AndClosed() throws Exception {
        BlockingWebSocketClient bot1 = newClient();
        bot1.send(PerceptionCodec.encode(new Frame.RegisterFrame('S')));
        Frame.SyncFrame s = awaitSync(bot1);

        String serverSessionId = botRegistry.getSessionForEntity(s.entityId()).orElseThrow();
        bot1.holdReceive(true);
        driveOverflow(serverSessionId, 5000);
        assertThat(awaitHandlerLog("BACKPRESSURE stalled", Duration.ofSeconds(5))).isTrue();
        bot1.releaseReceive();

        // Server closed the WS via SERVICE_RESTARTED after sending E|408|reconnect-required.
        // Verify both: close fired, and an E|408|reconnect-required was delivered before close.
        assertThat(bot1.awaitClose(Duration.ofSeconds(5))).isTrue();
        boolean got408 = bot1.received().stream().anyMatch(raw -> {
            try {
                Frame f = PerceptionCodec.decode(raw);
                return f instanceof Frame.ErrorFrame e
                        && e.code() == 408
                        && e.message().orElse("").contains("reconnect-required");
            } catch (Exception ignored) {
                return false;
            }
        });
        assertThat(got408)
                .as("Stalled session must receive E|408|reconnect-required before close. Frames=" + bot1.received())
                .isTrue();
    }

    @Test
    void respawnCountRestoredAcrossRebind() throws Exception {
        BlockingWebSocketClient bot1 = newClient();
        bot1.send(PerceptionCodec.encode(new Frame.RegisterFrame('C')));
        Frame.SyncFrame initialSync = awaitSync(bot1);
        String entityId = initialSync.entityId();

        // Simulate a death-pivot respawn cycle: resolve the server-side session, mark dead
        // via the public handler API, then send a respawn r|C so the respawn counter ticks to 1.
        String serverSessionId = botRegistry.getSessionForEntity(entityId).orElseThrow();
        var serverSession = resolveSession(serverSessionId);
        handler.markDead(serverSession);

        // Issue a respawn r|C — server treats this as respawn (ATTR_ENTITY_TYPE retained).
        bot1.send(PerceptionCodec.encode(new Frame.RegisterFrame('C')));
        Frame.SyncFrame respawnSync = awaitSecondSync(bot1, initialSync.entityId());
        String token = respawnSync.resumeToken().orElseThrow();
        String respawnedEntityId = respawnSync.entityId();
        assertThat(respawnedEntityId).isNotEqualTo(entityId);

        // Now stall the session (overflow). Resolve the new session id post-respawn.
        String respawnedSessionId = botRegistry.getSessionForEntity(respawnedEntityId).orElseThrow();
        bot1.holdReceive(true);
        driveOverflow(respawnedSessionId, 5000);
        assertThat(awaitHandlerLog("BACKPRESSURE stalled", Duration.ofSeconds(5))).isTrue();
        bot1.releaseReceive();
        bot1.awaitClose(Duration.ofSeconds(5));

        // Reconnect with token — handler should restore respawnCount on the new session
        // (claude MEDIUM respawn-cap-bypass fix). The BACKPRESSURE resumed log line
        // explicitly records respawnCountRestored=1.
        BlockingWebSocketClient bot2 = newClient();
        bot2.send(PerceptionCodec.encode(new Frame.RegisterFrame('C', Optional.of(token))));
        Frame.SyncFrame rebindSync = awaitSync(bot2);
        assertThat(rebindSync.entityId()).isEqualTo(respawnedEntityId);

        // Find the resumed log line carrying respawnCountRestored.
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        boolean restored = false;
        while (System.nanoTime() < deadline) {
            restored = handlerMessages().stream()
                    .anyMatch(m -> m.contains("BACKPRESSURE resumed") && m.contains("respawnCountRestored=1"));
            if (restored) break;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(restored)
                .as("Rebind must record respawnCountRestored=1 (claude MEDIUM fix). lines=" + handlerMessages())
                .isTrue();
    }

    @Test
    void idempotentMarkStalledFiresOnce() throws Exception {
        BlockingWebSocketClient bot1 = newClient();
        bot1.send(PerceptionCodec.encode(new Frame.RegisterFrame('C')));
        Frame.SyncFrame s = awaitSync(bot1);

        String serverSessionId = botRegistry.getSessionForEntity(s.entityId()).orElseThrow();
        bot1.holdReceive(true);
        // Drive far more offers than queue capacity — overflow should fire once due to
        // the per-session AtomicBoolean guard (Plan 06) plus the ATTR_STALL_TICK guard
        // in markStalled (Plan 07).
        driveOverflow(serverSessionId, 5000);
        assertThat(awaitHandlerLog("BACKPRESSURE stalled", Duration.ofSeconds(5))).isTrue();
        bot1.releaseReceive();
        bot1.awaitClose(Duration.ofSeconds(5));

        long stalledCount = handlerMessages().stream()
                .filter(m -> m.contains("BACKPRESSURE stalled"))
                .count();
        assertThat(stalledCount)
                .as("BACKPRESSURE stalled must log exactly once per stall transition. lines=" + handlerMessages())
                .isEqualTo(1L);
    }

    /** Convenience: resolve the live WebSocketSession for a given session id via SessionRegistry. */
    private org.springframework.web.socket.WebSocketSession resolveSession(String sessionId) {
        return ((com.paralife.websocket.SessionRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(handler, "sessionRegistry"))
                .getSession(sessionId);
    }

    /** Wait for an S frame with entityId != excludeId (used after respawn to skip the prior S). */
    private Frame.SyncFrame awaitSecondSync(BlockingWebSocketClient c, String excludeId) {
        long deadlineNs = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNs) {
            for (String raw : c.received()) {
                try {
                    Frame f = PerceptionCodec.decode(raw);
                    if (f instanceof Frame.SyncFrame sf && !sf.entityId().equals(excludeId)) return sf;
                } catch (Exception ignored) {}
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError("Second S frame (entityId != " + excludeId + ") not observed within 5s; received=" + c.received());
    }
}
