package com.paralife.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 15 D-38 metrics surface. Names follow Micrometer + Prometheus dot-separated
 * lowercase convention (RESEARCH §Pitfall 7 — hyphens cause backend name coercion).
 *
 * <h2>Live meters</h2>
 * <ul>
 *   <li>{@link #M_ACTIVE_SESSIONS} — Gauge over SessionRegistry size.</li>
 *   <li>{@link #M_TICK_FRAME_BYTES} — DistributionSummary of raw (pre-deflate)
 *       per-frame payload bytes. Recorded via {@link #recordFrameSize(int)}.</li>
 * </ul>
 *
 * <h2>Deferred</h2>
 * A third meter for post-deflate bytes-saved is NOT implemented this phase.
 * Jetty 12 does not cleanly expose exact per-frame post-deflate byte length
 * without reaching into extension internals. Previous drafts shipped a
 * fabricated coefficient-based estimate which misleads downstream dashboards.
 * Deferred to a follow-up plan once Jetty exposes a stable hook — see
 * 15-SCHEMA.md §13.
 */
@Component
public class WebSocketMetrics {

    public static final String M_ACTIVE_SESSIONS   = "paralife.ws.active.sessions";
    public static final String M_TICK_FRAME_BYTES  = "paralife.ws.tick.frame.bytes";

    private final DistributionSummary tickFrameBytes;
    private final AtomicInteger activeSessionCount = new AtomicInteger();
    private final Gauge activeSessions;

    public WebSocketMetrics(MeterRegistry registry) {
        this.tickFrameBytes = DistributionSummary.builder(M_TICK_FRAME_BYTES)
                .description("Per-tick outbound frame payload size (raw, pre-deflate)")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.activeSessions = Gauge.builder(M_ACTIVE_SESSIONS, activeSessionCount, AtomicInteger::get)
                .description("Current active WebSocket sessions")
                .register(registry);
    }

    /** Called by TickBroadcaster after every successful send. */
    public void recordFrameSize(int rawBytes) {
        tickFrameBytes.record(rawBytes);
    }

    /** Called by SessionRegistry when a session opens/closes. */
    public void setActiveSessions(int count) {
        activeSessionCount.set(count);
    }
}
