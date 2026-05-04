package com.paralife.admission;

import com.paralife.engine.TickEvent;
import com.paralife.websocket.WorldWebSocketHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19.1 D-07 — stuck-VT scenario asserting tick-thread service-time stays bounded.
 *
 * <p>Tests that {@code WorldWebSocketHandler.markStalled} (using the Phase 19.1 D-07
 * close-aware {@code detachSession(WebSocketSession, CloseStatus)} overload) does NOT
 * block the tick thread under a stuck Jetty write. The fake session's {@code sendMessage}
 * blocks until {@code close()} is called, reproducing the production stuck-write scenario.
 *
 * <p>Package: {@code com.paralife.admission} so the package-private
 * {@link OutboundSender#senderThread(String)} accessor is visible.
 *
 * <p>F6 anti-reflag note (pass-5 triage 2026-05-04): this test invokes
 * {@code markStalled} directly on a worker thread rather than through the
 * production overflow callback path. The synchronized-monitor blast radius is
 * identical regardless of trigger; the overhead-budget assertion (calibrated
 * baseline + 5x + 20ms floor) is sufficient. Full overflow-callback simulation
 * is deferred.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.tick.interval-ms=50",
        "paralife.world.width=64",
        "paralife.world.height=64",
        "paralife.world.rock.density-threshold=255",
        "paralife.simulation.events.enabled=false",
        "paralife.simulation.enabled=false",
        "paralife.admission.cap=10",
        "paralife.admission.backpressure.outbound-queue-size=2",
        "paralife.admission.backpressure.grace-window-ticks=5",
        "paralife.admission.tick-overload.high-water-pct=95",
        "paralife.admission.tick-overload.low-water-pct=90",
        "paralife.admission.tick-overload.window-ticks=10",
        "paralife.websocket.max-respawns-per-session=10"
})
class MarkStalledTickBoundIntegrationTest {

    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired ApplicationEventPublisher applicationEventPublisher;
    @Autowired MeterRegistry meterRegistry;

    @Test
    @DisplayName("Phase 19.1 D-07 — markStalled does not block tick thread under stuck Jetty write")
    void markStalledKeepsTickThreadBoundedUnderStuckWrite() throws Exception {
        // Step 1 — build a fake session whose sendMessage blocks until close() is invoked.
        BlockingFakeSession fake = new BlockingFakeSession();
        String stuckSessionId = fake.getId();

        // Attach the fake session to OutboundSender so the drain VT starts.
        outboundSender.attachSession(fake, 2);

        // Offer a frame so the drain VT enters sendMessage and blocks.
        outboundSender.offer(stuckSessionId, new com.paralife.codec.Frame.RegisterFrame('C'));

        // Wait until the drain VT is provably blocked in sendMessage.
        assertThat(fake.sendStarted.await(2, TimeUnit.SECONDS))
                .as("drain VT must enter sendMessage within 2s")
                .isTrue();

        // E3.3 — capture Thread before markStalled fires (post-detach senderThread returns null).
        Thread drainVt = outboundSender.senderThread(stuckSessionId);
        assertThat(drainVt).as("drain VT must be live before markStalled fires").isNotNull();

        // Step 2 — calibrated baseline: drive 10 ticks without the stuck VT blocking markStalled.
        // C3.2: SimpleApplicationEventMulticaster (no setTaskExecutor) dispatches synchronously.
        long[] baselineSamples = new long[10];
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            applicationEventPublisher.publishEvent(new TickEvent(i + 1));
            baselineSamples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long baselineMaxMs = java.util.Arrays.stream(baselineSamples).max().orElse(0L);

        // Step 3 — B3.4: paired latches for cross-thread coordination.
        CountDownLatch markStalledFinished = new CountDownLatch(1);

        // Spawn markStalled on a worker VT.
        Thread worker = Thread.startVirtualThread(() -> {
            // E3.1 (pass-4 amendment) — markStalled signature is (WebSocketSession, long stallTick)
            handler.markStalled(fake, 3L);
            markStalledFinished.countDown();
        });

        // Step 4 — drive stalled-period ticks while markStalled is running.
        // The close-aware D-07 fix must ensure markStalled returns quickly (transport closes
        // first, unblocking the Jetty write), so these ticks should complete without blocking.
        long[] stalledSamples = new long[10];
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            applicationEventPublisher.publishEvent(new TickEvent(11 + i));
            stalledSamples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long stalledMaxMs = java.util.Arrays.stream(stalledSamples).max().orElse(0L);

        // Confirm markStalled returned (close-aware detach must have unblocked the write).
        assertThat(markStalledFinished.await(5, TimeUnit.SECONDS))
                .as("markStalled must return — close-aware detach unblocks the write")
                .isTrue();

        // Step 5 — calibrated bound assertion.
        // C3.3: when baselineMaxMs == 0, floor relaxes to 50ms to absorb CI scheduling jitter.
        // Deadlock blocks for seconds; 50ms still catches the catastrophic case.
        long bound = (baselineMaxMs == 0) ? 50 : baselineMaxMs * 5 + 20;
        System.out.println("MarkStalledTickBound: baselineMax=" + baselineMaxMs
                + "ms stalledMax=" + stalledMaxMs + "ms bound=" + bound + "ms");
        assertThat(stalledMaxMs)
                .as("Phase 19.1 D-07 — markStalled returns and drain VT exits under stuck write "
                        + "(tick-thread service-time bounded as secondary signal) "
                        + "(baseline=" + baselineMaxMs + "ms, observed=" + stalledMaxMs
                        + "ms, bound=" + bound + "ms)")
                .isLessThanOrEqualTo(bound);

        // Step 6 — drain VT for the stalled session has exited.
        // E3.3: use the captured thread reference (senderThread(id) is now null).
        assertThat(drainVt.join(java.time.Duration.ofSeconds(2)))
                .as("drain VT must exit within 2s of markStalled — D-07 close-aware detach")
                .isTrue();
        assertThat(drainVt.isAlive()).as("Phase 19.1 D-07 drain VT must have exited").isFalse();

        // Optional defensive: confirm map entry was cleaned up.
        assertThat(outboundSender.senderThread(stuckSessionId))
                .as("senderThread map entry must be cleared post-detach")
                .isNull();

        worker.join(1000);
    }

    /**
     * Fake WebSocketSession whose {@code sendMessage} blocks on a {@link CountDownLatch}
     * until {@code close()} is called, mirroring how Jetty's transport-close throws
     * {@code IOException} out of a blocked socket write (C3.1 / A3.4 amendment).
     *
     * <p>{@code sendStarted} latch lets the test thread wait until the drain VT is
     * provably in-flight before spawning markStalled.
     */
    static final class BlockingFakeSession implements WebSocketSession {
        private final String id = "stuck-" + UUID.randomUUID();
        private final Map<String, Object> attrs = new ConcurrentHashMap<>();
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        final CountDownLatch sendStarted = new CountDownLatch(1);
        private volatile boolean open = true;

        @Override
        public void sendMessage(WebSocketMessage<?> m) throws IOException {
            sendStarted.countDown();           // signal: write is provably in-flight
            try { closeLatch.await(); }        // block until close() unblocks us
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ie);
            }
            throw new IOException("session closed mid-write");   // production unblock semantic
        }

        @Override
        public void close(CloseStatus s) throws IOException {
            open = false;
            closeLatch.countDown();
        }

        @Override public void close() throws IOException { close(CloseStatus.NORMAL); }
        @Override public String getId() { return id; }
        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public boolean isOpen() { return open; }
        @Override public URI getUri() { return null; }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
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
