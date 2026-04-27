package com.paralife.admission;

import com.paralife.codec.Frame;
import com.paralife.engine.TickEvent;
import com.paralife.websocket.WorldWebSocketHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 Plan 11 — edge-case integration coverage for SCALE-02 robustness (opencode LOW).
 *
 * <p>Five behaviours:
 * <ol>
 *   <li><b>convertToStalledIsIdempotent</b> — second call is a no-op; gauge not double-counted.</li>
 *   <li><b>concurrentRebindOneSucceeds</b> — under concurrent rebind on the same STALLED token,
 *       exactly one thread receives a present Optional, the other empty.</li>
 *   <li><b>offerAfterDetachReturnsFalse</b> — after {@code detachSession}, {@code offer} returns
 *       false without throwing.</li>
 *   <li><b>sweepRemovesEntryEvenIfCallbackThrows</b> — sweep removes the entry and decrements the
 *       gauge even when the cleanup callback throws.</li>
 *   <li><b>markStalledIsIdempotentOnSecondCall</b> — handler.markStalled second call is a no-op
 *       (Plan 07 ATTR_STALL_TICK guard).</li>
 * </ol>
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
        "paralife.admission.cap=10",
        "paralife.admission.backpressure.outbound-queue-size=2",
        "paralife.admission.backpressure.grace-window-ticks=5"
})
@Tag("slow")
class EdgeCasesIntegrationTest {

    @Autowired ResumeTokenRegistry resumeTokenRegistry;
    @Autowired OutboundSender outboundSender;
    @Autowired AdmissionMetrics admissionMetrics;
    @Autowired WorldWebSocketHandler handler;
    @Autowired MeterRegistry meterRegistry;

    private double stalledGauge() {
        return meterRegistry.get(AdmissionMetrics.M_STALLED_SESSIONS).gauge().value();
    }

    @Test
    void convertToStalledIsIdempotent() {
        double before = stalledGauge();
        String token = resumeTokenRegistry.issueActive("e-idem", "s-idem");
        resumeTokenRegistry.convertToStalled(token, 100L);
        double afterFirst = stalledGauge();
        assertThat(afterFirst - before).isEqualTo(1.0);

        // Second call must NOT double-count or change expiry.
        resumeTokenRegistry.convertToStalled(token, 200L);
        double afterSecond = stalledGauge();
        assertThat(afterSecond).isEqualTo(afterFirst);

        // Cleanup so subsequent tests start clean: rebind consumes the entry.
        resumeTokenRegistry.tryRebind(token, "s-idem-rebind", 101L);
        assertThat(stalledGauge()).isEqualTo(before);
    }

    @Test
    void concurrentRebindOneSucceeds() throws Exception {
        String token = resumeTokenRegistry.issueActive("e-concur", "s-concur");
        resumeTokenRegistry.convertToStalled(token, 100L);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Optional<ResumeTokenRegistry.RebindOutcome>> r1 = new AtomicReference<>();
        AtomicReference<Optional<ResumeTokenRegistry.RebindOutcome>> r2 = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(2);

        Runnable task1 = () -> {
            try {
                barrier.await();
                r1.set(resumeTokenRegistry.tryRebind(token, "s-rebind-A", 101L));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        };
        Runnable task2 = () -> {
            try {
                barrier.await();
                r2.set(resumeTokenRegistry.tryRebind(token, "s-rebind-B", 101L));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        };

        Thread.ofVirtual().start(task1);
        Thread.ofVirtual().start(task2);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        // Exactly one present, exactly one empty.
        long present = (r1.get().isPresent() ? 1 : 0) + (r2.get().isPresent() ? 1 : 0);
        assertThat(present).isEqualTo(1L);
    }

    @Test
    void offerAfterDetachReturnsFalse() {
        FakeSession s = new FakeSession("s-detach");
        outboundSender.attachSession(s, 4);
        outboundSender.detachSession(s.getId());
        boolean accepted = outboundSender.offer(s.getId(), new Frame.RegisterFrame('C'));
        assertThat(accepted).isFalse();
    }

    @Test
    void sweepRemovesEntryEvenIfCallbackThrows() {
        // Save and restore the registry's existing cleanup callback (the handler-wired one).
        @SuppressWarnings("unchecked")
        Consumer<String> prior = (Consumer<String>) ReflectionTestUtils.getField(resumeTokenRegistry, "cleanupCallback");

        // Pre-sweep any leftover STALLED entries from prior tests so this test can isolate
        // the callback-call count to its own entry (singleton registry across @Tests).
        resumeTokenRegistry.setCleanupCallback(eid -> {});
        resumeTokenRegistry.onTick(new TickEvent(Long.MAX_VALUE / 2));

        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicInteger seenEntities = new AtomicInteger();
        AtomicReference<String> entityIdSeen = new AtomicReference<>();
        resumeTokenRegistry.setCleanupCallback(eid -> {
            callbackCalls.incrementAndGet();
            entityIdSeen.set(eid);
            seenEntities.incrementAndGet();
            throw new RuntimeException("boom");
        });

        double before = stalledGauge();
        String token = resumeTokenRegistry.issueActive("e-sweep-throw", "s-sweep-throw");
        resumeTokenRegistry.convertToStalled(token, 100L);
        assertThat(stalledGauge()).isEqualTo(before + 1.0);

        // Sweep at tick well past expiry.
        resumeTokenRegistry.onTick(new TickEvent(110L));

        // Callback was invoked for OUR entity AND threw, yet the entry was still removed and the
        // gauge decremented. Use >=1 since other tests' lingering entries may also reach this sweep.
        assertThat(callbackCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(entityIdSeen.get()).isEqualTo("e-sweep-throw");
        assertThat(stalledGauge()).isEqualTo(before);

        // Restore prior callback so other tests aren't affected.
        resumeTokenRegistry.setCleanupCallback(prior);
    }

    @Test
    void markStalledIsIdempotentOnSecondCall() {
        // Build a fake session with the right attrs, mark it stalled twice, assert the
        // registry's stalled gauge increments only once and ATTR_STALL_TICK retains tick=100.
        FakeSession fake = new FakeSession("s-mark-idem");
        var sessionRegistry = (com.paralife.websocket.SessionRegistry)
                ReflectionTestUtils.getField(handler, "sessionRegistry");
        sessionRegistry.register(fake);

        // Pre-populate ATTR_ENTITY_ID + ATTR_RESUME_TOKEN so markStalled has something to convert.
        String token = resumeTokenRegistry.issueActive("e-mark-idem", fake.getId());
        fake.getAttributes().put("entityId", "e-mark-idem");
        fake.getAttributes().put("resumeToken", token);

        outboundSender.attachSession(fake, 4);
        double before = stalledGauge();

        handler.markStalled(fake, 100L);
        double afterFirst = stalledGauge();
        assertThat(afterFirst - before).isEqualTo(1.0);
        assertThat(fake.getAttributes().get("stallTick")).isEqualTo(100L);

        // Second call: ATTR_STALL_TICK is already set, so markStalled is a no-op.
        handler.markStalled(fake, 200L);
        double afterSecond = stalledGauge();
        assertThat(afterSecond).isEqualTo(afterFirst);
        // Stall tick value preserved from first call (NOT overwritten by 200).
        assertThat(fake.getAttributes().get("stallTick")).isEqualTo(100L);

        // Cleanup so other tests are unaffected.
        outboundSender.detachSession(fake.getId());
        sessionRegistry.unregister(fake.getId());
    }

    // ---- FakeSession (mirrors AdmissionLogMarkersIntegrationTest) -----------------------

    static class FakeSession implements WebSocketSession {
        final String id;
        final List<String> captured = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> attrs = new ConcurrentHashMap<>();

        FakeSession(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public boolean isOpen() { return true; }
        @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
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
