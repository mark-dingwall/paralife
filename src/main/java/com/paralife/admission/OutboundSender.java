package com.paralife.admission;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Per-session virtual-thread outbound sender (Phase 17 D-10, D-11).
 *
 * <p>Each open WebSocket session is paired with one VT that loops:
 * {@code queue.take(); synchronized(session) { session.sendMessage(...) }}.
 *
 * <p><b>Synchronized-session-monitor contract (Phase 17 hardening):</b> Spring's
 * {@code WebSocketSession.sendMessage} is NOT thread-safe; concurrent calls may interleave or
 * throw. Every writer to a session — drain VT, keepalive PING (Plan 09), and out-of-band stall/
 * error frames built on the inbound Jetty thread — MUST hold {@code synchronized(session)} for
 * the actual {@code sendMessage} invocation. Encoding and metric recording stay outside the
 * monitor (they don't touch the session).
 *
 * <p><b>Outbound concurrency rationale</b> (D-10; mirrored in {@code CLAUDE.md} "Outbound concurrency"):
 * <ul>
 *   <li>Matches Paralife's stated philosophy: simple blocking code, virtual threads do concurrency
 *       ({@code spring.threads.virtual.enabled: true}).</li>
 *   <li>Per-session isolation is structural — one slow socket cannot block the tick thread
 *       or any other session.</li>
 *   <li>{@code queue.size()} is the explicit backpressure signal — trivially observable as a gauge.</li>
 *   <li>Java 21 VTs scheduled on shared carriers; per-VT cost is a few KB heap.
 *       1000+ VTs is acceptable.</li>
 *   <li>Considered alternative — Jetty native async write + write callbacks — was rejected because
 *       slow-client detection becomes implicit (write-Future latency / Jetty internals) and the API
 *       surface differs across Jetty 12 minor versions.</li>
 * </ul>
 *
 * <p><b>Overflow-fire-once guard:</b> a saturated queue calls the overflow callback exactly once
 * per attach lifecycle (per-session {@code AtomicBoolean overflowFired}). Plan 07's
 * {@code markStalled} is therefore invoked at most once per stall transition, eliminating the
 * duplicate-token / log-spam / repeated-FSM-transition bug flagged by codex HIGH review.
 *
 * <p><b>Frame-size metric:</b> {@link AdmissionMetrics#recordFrameSize(int)} is called in the
 * drain loop after encode — single measurement point. Callers such as {@code TickBroadcaster}
 * must NOT add a duplicate {@code recordFrameSize} call (codex MEDIUM).
 *
 * <p><b>Test seam (Phase 19 D-10):</b> {@link #setFrameEmitListener} registers a callback
 * invoked after each {@code sendMessage}; null in production. See {@link FrameEmitListener}.
 */
@Component
public class OutboundSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundSender.class);
    private static final long DETACH_JOIN_TIMEOUT_MS = 100L;

    private final AdmissionMetrics metrics;
    private final ConcurrentHashMap<String, ArrayBlockingQueue<Frame>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> senderThreads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> overflowFiredFlags = new ConcurrentHashMap<>();

    /**
     * Phase 19 SCALE-07 D-10: test-only seam for capturing outbound frame bytes.
     * Invoked AFTER {@code session.sendMessage} succeeds, still inside {@code synchronized(session)},
     * so the post-drain {@code synchronized(session)} barrier in tests covers in-flight callbacks.
     * Production listener is always {@code null}.
     *
     * <p>Catch in the drain loop is {@code Exception} (NOT {@code Throwable}) per REVIEWS LOW-10:
     * {@code OutOfMemoryError} / {@code StackOverflowError} still propagate per JVM contract.
     */
    @FunctionalInterface
    public interface FrameEmitListener {
        void onEmit(String sessionId, byte[] frameBytes);
    }

    /** Test-only. Null in production. Volatile so the drain-VT sees assignments from the test thread. */
    private volatile FrameEmitListener frameEmitListener;

    /**
     * Registers a {@link FrameEmitListener} invoked after each successful {@code sendMessage}.
     * Pass {@code null} to clear. Test-only — production code must not call this.
     */
    public void setFrameEmitListener(FrameEmitListener listener) {
        this.frameEmitListener = listener;
    }

    /** Set by Plan 07 at startup. Receives (sessionId, queueCapacity) on overflow — fired ONCE per attach. */
    private volatile BiConsumer<String, Integer> overflowCallback;

    public OutboundSender(AdmissionMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Registers the overflow callback invoked when {@link #offer} encounters a full queue.
     * Called at most once per attach lifecycle due to the fire-once guard.
     * Wired by Plan 07 at startup.
     */
    public void setOverflowCallback(BiConsumer<String, Integer> callback) {
        this.overflowCallback = callback;
    }

    /**
     * Spawns a named VT to drain frames for {@code session}. Idempotent — re-attach detaches
     * any existing sender first, resetting the overflow-fire-once flag.
     *
     * @param session       the WebSocket session to pair with this sender
     * @param queueCapacity bounded queue depth (must be &gt;= 1)
     */
    public void attachSession(WebSocketSession session, int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        String id = session.getId();
        // Detach any existing sender for this id first (idempotent re-attach).
        detachSession(id);
        ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(queueCapacity);
        queues.put(id, queue);
        overflowFiredFlags.put(id, new AtomicBoolean(false));
        Thread t = Thread.ofVirtual()
                .name("ws-sender-" + id)
                .start(() -> drainLoop(session, queue));
        senderThreads.put(id, t);
    }

    /**
     * Interrupts the sender VT and joins it for up to {@value #DETACH_JOIN_TIMEOUT_MS}ms.
     * Mutual exclusion of writers is guaranteed by the synchronized-session-monitor contract;
     * the join keeps detach bounded so callers don't block indefinitely on a misbehaving VT.
     */
    public void detachSession(String sessionId) {
        queues.remove(sessionId);
        overflowFiredFlags.remove(sessionId);
        Thread t = senderThreads.remove(sessionId);
        if (t == null) return;
        t.interrupt();
        try {
            boolean exited = t.join(java.time.Duration.ofMillis(DETACH_JOIN_TIMEOUT_MS));
            if (!exited) {
                log.warn("Sender VT for session={} did not exit within {}ms after interrupt",
                        sessionId, DETACH_JOIN_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Non-blocking enqueue. Returns {@code false} if no queue exists for {@code sessionId}
     * OR the queue is full. On overflow, the registered callback is invoked AT MOST ONCE
     * per attach lifecycle (overflow-fire-once guard via per-session {@code AtomicBoolean}).
     *
     * @param sessionId the attached session id
     * @param frame     the frame to enqueue
     * @return {@code true} if accepted; {@code false} on no-such-session or queue-full
     */
    public boolean offer(String sessionId, Frame frame) {
        ArrayBlockingQueue<Frame> queue = queues.get(sessionId);
        if (queue == null) return false;
        boolean accepted = queue.offer(frame);
        if (!accepted) {
            AtomicBoolean fired = overflowFiredFlags.get(sessionId);
            if (fired != null && fired.compareAndSet(false, true)) {
                BiConsumer<String, Integer> cb = this.overflowCallback;
                if (cb != null) {
                    try {
                        cb.accept(sessionId, queue.size());
                    } catch (RuntimeException ex) {
                        log.warn("Overflow callback failed for {}: {}", sessionId, ex.getMessage());
                    }
                }
            }
        }
        return accepted;
    }

    /**
     * Returns the current queue depth for {@code sessionId}, or -1 if no queue is attached.
     * Useful for backpressure gauges and diagnostics.
     */
    public int queueDepth(String sessionId) {
        ArrayBlockingQueue<Frame> q = queues.get(sessionId);
        return q == null ? -1 : q.size();
    }

    /** Returns the number of sessions currently attached. */
    public int attachedCount() {
        return queues.size();
    }

    /**
     * Test-only: returns {@code true} if the overflow callback has fired for this session.
     * Package-private to keep it accessible within the same package's tests.
     */
    boolean hasOverflowFired(String sessionId) {
        AtomicBoolean fired = overflowFiredFlags.get(sessionId);
        return fired != null && fired.get();
    }

    private void drainLoop(WebSocketSession session, ArrayBlockingQueue<Frame> queue) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Frame frame = queue.take();
                if (!session.isOpen()) continue;
                try {
                    String encoded = PerceptionCodec.encode(frame);
                    byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8);
                    metrics.recordFrameSize(encodedBytes.length);
                    synchronized (session) {
                        session.sendMessage(new TextMessage(encoded));
                        // Phase 19 SCALE-07 D-10: invoke test seam INSIDE the monitor so
                        // the post-drain synchronized(session) barrier in tests covers it.
                        // REVIEWS LOW-10: catch Exception, NOT Throwable — OOM/SOE propagate.
                        FrameEmitListener emitListener = frameEmitListener;
                        if (emitListener != null) {
                            try {
                                emitListener.onEmit(session.getId(), encodedBytes);
                            } catch (Exception listenerEx) {
                                log.warn("FrameEmitListener threw for session={}: {}",
                                        session.getId(), listenerEx.toString());
                            }
                        }
                    }
                } catch (IOException e) {
                    log.warn("Send failed for session={}: {}", session.getId(), e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("Send error for session={}: {}", session.getId(), e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
