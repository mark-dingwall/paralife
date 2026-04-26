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

    @BeforeEach
    void setup() {
        meterReg = new SimpleMeterRegistry();
        metrics = new AdmissionMetrics(meterReg);
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
}
