package com.paralife.admission;

import com.paralife.codec.Frame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundSenderTest {

    private SimpleMeterRegistry meterReg;
    private AdmissionMetrics metrics;
    private OutboundSender sender;

    // G4 (pass-6 triage 2026-05-04) — `never` promoted to test-class field so
    // @AfterEach can release the parked drain VT regardless of test outcome.
    // L5 pass-7 triage — explicit null initialiser.
    private CountDownLatch never = null;
    // M3 pass-7 triage — captured drain Thread reference so @AfterEach can join + assert exit.
    private Thread drainThread = null;

    @BeforeEach
    void setup() {
        meterReg = new SimpleMeterRegistry();
        AdmissionConfig admissionConfig = AdmissionConfig.defaults();
        com.paralife.engine.TickEngine mockTickEngine = org.mockito.Mockito.mock(com.paralife.engine.TickEngine.class);
        org.mockito.Mockito.when(mockTickEngine.currentTick()).thenReturn(0L);
        AttributionTagger tagger = new AttributionTagger(64, mockTickEngine);
        metrics = new AdmissionMetrics(meterReg, admissionConfig, mockTickEngine, tagger);
        sender = new OutboundSender(metrics);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        if (!cond.getAsBoolean()) throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }

    @Test
    void attachAndOfferEnqueuesAndDelivers() throws Exception {
        FakeSession s = new FakeSession("session-1");
        sender.attachSession(s, 4);
        boolean ok = sender.offer("session-1", new Frame.RegisterFrame('C'));
        assertThat(ok).isTrue();
        awaitUntil(() -> s.captured.size() >= 1, 2000);
        assertThat(s.captured.get(0)).startsWith("r|C");
        sender.detachSession("session-1");
    }

    @Test
    void offerToUnknownSessionReturnsFalse() {
        assertThat(sender.offer("nope", new Frame.RegisterFrame('C'))).isFalse();
    }

    @Test
    void offerReturnsFalseWhenQueueFull() throws Exception {
        // Use capacity=1 so the pattern is deterministic:
        //   offer #1 -- VT takes it immediately, blocks on sendMessage latch (in-flight)
        //   offer #2 -- queued (1/1 -- queue is now full)
        //   offer #3 -- queue full -> returns false
        FakeSession blocking = new FakeSession("session-blocking");
        sender.attachSession(blocking, 1);
        blocking.holdNextSend(true);

        sender.offer("session-blocking", new Frame.RegisterFrame('C'));   // dispatched, in-flight
        // Wait briefly for the VT to take the frame so the queue is clear before offer #2
        awaitUntil(() -> blocking.sendPending, 2000);
        boolean a = sender.offer("session-blocking", new Frame.RegisterFrame('M'));   // queued (1/1)
        boolean b = sender.offer("session-blocking", new Frame.RegisterFrame('S'));   // queue full -> false

        assertThat(a).isTrue();
        assertThat(b).isFalse();

        blocking.releaseAll();
        sender.detachSession("session-blocking");
    }

    @Test
    void overflowCallbackFiresExactlyOncePerAttach() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        sender.setOverflowCallback((sid, depth) -> callbackCount.incrementAndGet());

        FakeSession blocking = new FakeSession("session-cb");
        sender.attachSession(blocking, 1);
        blocking.holdNextSend(true);

        sender.offer("session-cb", new Frame.RegisterFrame('C'));   // in-flight
        sender.offer("session-cb", new Frame.RegisterFrame('M'));   // queued (1/1)
        sender.offer("session-cb", new Frame.RegisterFrame('S'));   // overflow -> callback
        sender.offer("session-cb", new Frame.RegisterFrame('C'));   // overflow again -- must NOT re-fire
        sender.offer("session-cb", new Frame.RegisterFrame('M'));   // overflow again -- must NOT re-fire

        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(sender.hasOverflowFired("session-cb")).isTrue();

        blocking.releaseAll();
        sender.detachSession("session-cb");
    }

    @Test
    void reattachResetsOverflowFiredFlag() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        sender.setOverflowCallback((sid, depth) -> callbackCount.incrementAndGet());

        FakeSession blocking1 = new FakeSession("session-r");
        sender.attachSession(blocking1, 1);
        blocking1.holdNextSend(true);
        sender.offer("session-r", new Frame.RegisterFrame('C'));
        sender.offer("session-r", new Frame.RegisterFrame('M'));
        sender.offer("session-r", new Frame.RegisterFrame('S'));   // first overflow -> fire
        assertThat(callbackCount.get()).isEqualTo(1);

        blocking1.releaseAll();
        sender.detachSession("session-r");

        // Re-attach with the same session id; flag should reset.
        FakeSession blocking2 = new FakeSession("session-r");
        sender.attachSession(blocking2, 1);
        blocking2.holdNextSend(true);
        sender.offer("session-r", new Frame.RegisterFrame('C'));
        sender.offer("session-r", new Frame.RegisterFrame('M'));
        sender.offer("session-r", new Frame.RegisterFrame('S'));   // second overflow -> fire (post re-attach)
        assertThat(callbackCount.get()).isEqualTo(2);

        blocking2.releaseAll();
        sender.detachSession("session-r");
    }

    @Test
    void detachJoinsVTWithinTimeout() throws Exception {
        FakeSession s = new FakeSession("session-d");
        sender.attachSession(s, 4);
        long start = System.nanoTime();
        sender.detachSession("session-d");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(200);   // detach should return quickly (VT exits on interrupt)
        assertThat(sender.queueDepth("session-d")).isEqualTo(-1);
    }

    /**
     * Phase 22 (TD-19.5-A) regression: when detaching a session whose drain VT is mid-flight
     * inside {@code sendMessage} (the realistic shutdown / graceful-disconnect race), the
     * session-aware {@link OutboundSender#detachSession(WebSocketSession)} closes the transport
     * first so Jetty unblocks the write — drain VT then honours the interrupt and exits well
     * within the 100ms join, no "did not exit" WARN.
     *
     * <p>Modelled with a {@link CloseAwareSession} whose blocking {@code sendMessage} returns
     * only when {@code close} flips its open-state flag.
     */
    @Test
    void detachSessionWithSessionRefUnblocksInFlightSend() throws Exception {
        CloseAwareSession s = new CloseAwareSession("session-sr");
        sender.attachSession(s, 1);
        sender.offer("session-sr", new Frame.RegisterFrame('C'));   // VT enters sendMessage, blocks
        awaitUntil(() -> s.sendInFlight, 2000);

        // B3.3 — capture thread BEFORE detach: post-detach senderThread(id) returns null
        // because detachSession removes the map entry first.
        Thread t = sender.senderThread("session-sr");
        assertThat(t).as("drain VT must be live before detach").isNotNull();

        long start = System.nanoTime();
        sender.detachSession(s);   // closes transport → unblocks send → VT exits via interrupt
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(s.closed).isTrue();
        assertThat(elapsedMs).isLessThan(200);
        assertThat(sender.queueDepth("session-sr")).isEqualTo(-1);

        // Phase 19.1 D-13 / EL — drain VT must exit after detach
        boolean joined = t.join(java.time.Duration.ofSeconds(5));
        assertThat(joined).as("Phase 19.1 D-13 / EL — drain VT must exit after detach").isTrue();
        assertThat(t.isAlive()).as("Phase 19.1 D-13").isFalse();
    }

    @Test
    @DisplayName("Phase 19.1 D-14 / E3.2 — detachSession(String) join-timeout increments paralife.outbound.detach.timeout counter")
    void detachTimeoutIncrementsCounter() throws Exception {
        // Setup: register a fake session whose sendMessage blocks on a latch that
        // is NEVER counted down DURING the test. The drain VT enters
        // take()→sendMessage→awaitLatch. detachSession(String) interrupts the VT;
        // the fake's sendMessage swallows the interrupt and keeps blocking past the
        // join timeout, so the counter increments.
        // @AfterEach releaseStuckVT() counts the latch down so the drain VT unwinds.
        never = new CountDownLatch(1);
        FakeSession fake = new FakeSession("session-dt") {
            @Override
            public void sendMessage(WebSocketMessage<?> message) throws java.io.IOException {
                // Swallow interrupt — block indefinitely until `never` is counted down.
                try { never.await(); } catch (InterruptedException ie) {
                    // Re-park after interrupt — do NOT honour it, so the join times out.
                    try { never.await(); } catch (InterruptedException ie2) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        sender.attachSession(fake, 2);
        sender.offer(fake.getId(), new Frame.RegisterFrame('C'));   // wedges the drain VT in sendMessage

        // Wait until the drain VT is provably blocked in sendMessage before calling detachSession.
        // Give it time to take the frame and enter sendMessage.
        Thread.sleep(50);

        // M3 pass-7 triage — capture the drain Thread so @AfterEach can join + assert exit.
        this.drainThread = sender.senderThread(fake.getId());

        double before = meterReg.counter(AdmissionMetrics.M_DETACH_TIMEOUT).count();
        sender.detachSession(fake.getId());   // String overload — Step 3 increment site
        double after  = meterReg.counter(AdmissionMetrics.M_DETACH_TIMEOUT).count();

        assertThat(after - before)
            .as("Phase 19.1 D-14 / E3.2 — detachSession(String) join-timeout must increment counter")
            .isEqualTo(1.0d);
    }

    @AfterEach
    void releaseStuckVT() throws InterruptedException {
        // G4 (pass-6 triage 2026-05-04) — unblock the drain VT so the test class
        // does not leak a parked VT across forkEvery=1 runs. Count down `never`
        // so the fake sendMessage returns, then interrupt the thread so the drain
        // loop exits if it re-enters queue.take().
        if (never != null) {
            never.countDown();
        }
        // M3 pass-7 triage — interrupt + join the captured drain Thread.
        if (drainThread != null) {
            drainThread.interrupt();   // unblocks any subsequent queue.take() after sendMessage returns
            drainThread.join(500);
            assertThat(drainThread.isAlive())
                    .as("drain VT must exit within 500ms of latch release + interrupt")
                    .isFalse();
        }
    }

    @Test
    void detachSessionWithNullIsNoOp() {
        sender.detachSession((WebSocketSession) null);   // must not throw
    }

    @Test
    void detachSessionWithAlreadyClosedSessionStillCleansUp() throws Exception {
        CloseAwareSession s = new CloseAwareSession("session-pre");
        s.close();   // already closed before attach-detach
        sender.attachSession(s, 4);
        long start = System.nanoTime();
        sender.detachSession(s);   // skips the close, falls through to the id-keyed path
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(200);
        assertThat(sender.queueDepth("session-pre")).isEqualTo(-1);
    }

    @Test
    void drainLoopSurvivesIOException() throws Exception {
        FakeSession s = new FakeSession("session-err");
        s.failNextSend = true;
        sender.attachSession(s, 4);
        sender.offer("session-err", new Frame.RegisterFrame('C'));   // throws IOException internally
        sender.offer("session-err", new Frame.RegisterFrame('M'));   // succeeds
        awaitUntil(() -> s.captured.size() >= 1, 2000);
        sender.detachSession("session-err");
    }

    @Test
    void frameSizeMetricRecordedAfterEncode() throws Exception {
        FakeSession s = new FakeSession("session-fs");
        sender.attachSession(s, 4);
        sender.offer("session-fs", new Frame.RegisterFrame('C'));
        sender.offer("session-fs", new Frame.RegisterFrame('M'));
        awaitUntil(() -> s.captured.size() >= 2, 2000);
        var summary = meterReg.get(AdmissionMetrics.M_FRAME_SIZE).summary();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isGreaterThan(0.0);
        sender.detachSession("session-fs");
    }

    @Test
    void drainAndExternalSyncWriterSerialize() throws Exception {
        // F11 regression for A1/A2: drain VT and external "keepalive" writer must
        // serialize via synchronized(session). Simultaneous unsynchronized sends
        // would interleave; with the contract all sends complete cleanly.
        FakeSession s = new FakeSession("session-stress");
        sender.attachSession(s, 64);

        int producerCount = 1000;
        int keepaliveCount = 200;
        CountDownLatch producerDone = new CountDownLatch(1);
        CountDownLatch keepaliveDone = new CountDownLatch(1);
        AtomicInteger producerAccepted = new AtomicInteger();
        AtomicInteger keepaliveSent = new AtomicInteger();
        AtomicInteger keepaliveErrors = new AtomicInteger();

        Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < producerCount; i++) {
                    if (sender.offer("session-stress", new Frame.RegisterFrame('C'))) {
                        producerAccepted.incrementAndGet();
                    } else {
                        Thread.sleep(1);
                        i--;   // retry until accepted; capacity 64 + draining handles this
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producerDone.countDown();
            }
        });

        Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < keepaliveCount; i++) {
                    try {
                        synchronized (s) {
                            s.sendMessage(new org.springframework.web.socket.TextMessage("k|ping"));
                        }
                        keepaliveSent.incrementAndGet();
                    } catch (Exception ex) {
                        keepaliveErrors.incrementAndGet();
                    }
                }
            } finally {
                keepaliveDone.countDown();
            }
        });

        assertThat(producerDone.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(keepaliveDone.await(30, TimeUnit.SECONDS)).isTrue();

        awaitUntil(() -> s.captured.size() >= producerAccepted.get() + keepaliveSent.get(), 10_000);

        assertThat(keepaliveErrors.get()).isZero();
        assertThat(s.captured.size())
                .isEqualTo(producerAccepted.get() + keepaliveSent.get());
        sender.detachSession("session-stress");
    }

    @Test
    void reattachAfterDetachIsIdempotent() throws Exception {
        FakeSession s1 = new FakeSession("session-x");
        sender.attachSession(s1, 4);
        sender.detachSession("session-x");
        FakeSession s2 = new FakeSession("session-x");
        sender.attachSession(s2, 4);
        sender.offer("session-x", new Frame.RegisterFrame('C'));
        awaitUntil(() -> s2.captured.size() >= 1, 2000);
        sender.detachSession("session-x");
    }

    /** Minimal FakeSession capturing sendMessage calls. */
    static class FakeSession implements WebSocketSession {
        final String id;
        final List<String> captured = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failNextSend = false;
        /** Set to true the moment sendMessage is entered (before waiting on holdLatch). */
        volatile boolean sendPending = false;
        private volatile CountDownLatch holdLatch;

        FakeSession(String id) { this.id = id; }

        void holdNextSend(boolean hold) { this.holdLatch = hold ? new CountDownLatch(1) : null; }

        void releaseAll() {
            CountDownLatch l = this.holdLatch;
            this.holdLatch = null;
            if (l != null) l.countDown();
        }

        @Override public String getId() { return id; }
        @Override public boolean isOpen() { return true; }
        @Override public void sendMessage(WebSocketMessage<?> message) throws java.io.IOException {
            sendPending = true;
            CountDownLatch l = this.holdLatch;
            if (l != null) {
                try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("interrupted"); }
            }
            sendPending = false;
            if (failNextSend) {
                failNextSend = false;
                throw new java.io.IOException("simulated send failure");
            }
            captured.add(message.getPayload().toString());
        }
        @Override public URI getUri() { return null; }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return new ConcurrentHashMap<>(); }
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

    /**
     * Phase 22 (TD-19.5-A) test fixture: simulates a Jetty session whose blocking
     * {@code sendMessage} returns only after {@code close} flips the open flag, mirroring
     * how Jetty unblocks an in-flight socket write when the transport is torn down.
     */
    static class CloseAwareSession implements WebSocketSession {
        final String id;
        final List<String> captured = Collections.synchronizedList(new ArrayList<>());
        volatile boolean open = true;
        volatile boolean closed = false;
        volatile boolean sendInFlight = false;

        CloseAwareSession(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public boolean isOpen() { return open; }
        @Override public void sendMessage(WebSocketMessage<?> message) throws java.io.IOException {
            sendInFlight = true;
            try {
                while (open) {
                    try { Thread.sleep(5); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("interrupted");
                    }
                }
                throw new java.io.IOException("session closed mid-send");
            } finally {
                sendInFlight = false;
            }
        }
        @Override public void close() { open = false; closed = true; }
        @Override public void close(CloseStatus status) { close(); }
        @Override public URI getUri() { return null; }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return new ConcurrentHashMap<>(); }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
    }
}
