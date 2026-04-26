package com.paralife.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operator-visibility metrics for Phase 17 durable admission control (D-17 / D-18).
 *
 * <p>Exposes:
 * <ul>
 *   <li>Tagged rejection counter: {@code paralife.admission.rejected{reason=<token>}}</li>
 *   <li>Ingress-overwrite counter: {@code paralife.admission.ingress.overwrites}</li>
 *   <li>Outbound frame-size distribution summary: {@code paralife.outbound.frame.size.bytes}</li>
 *   <li>4 D-18 gauges: active entities, maintenance flag, last-tick work ms, stalled sessions</li>
 * </ul>
 *
 * <p>This bean is authored in Plan 05 (wave 2) so that {@link ResumeTokenRegistry} can call
 * {@link #setStalledSessions(int)} at compile time. Plan 03 (AdmissionGate) introduces no
 * file-overlap; wave-2 merge ordering is handled by the orchestrator.
 */
@Component
public class AdmissionMetrics {

    public static final String M_REJECTED           = "paralife.admission.rejected";
    public static final String M_INGRESS_OVERWRITES = "paralife.admission.ingress.overwrites";
    public static final String M_ACTIVE_ENTITIES    = "paralife.admission.active.entities";
    public static final String M_MAINTENANCE        = "paralife.admission.maintenance";
    public static final String M_TICK_WORK_MS       = "paralife.tick.health.work-time-ms";
    public static final String M_STALLED_SESSIONS   = "paralife.backpressure.stalled.sessions";
    public static final String M_FRAME_SIZE         = "paralife.outbound.frame.size.bytes";

    private final MeterRegistry registry;
    private final Counter ingressOverwrites;
    private final io.micrometer.core.instrument.DistributionSummary frameSize;

    private final AtomicInteger activeEntities  = new AtomicInteger();
    private final AtomicInteger maintenance     = new AtomicInteger();
    private final AtomicLong    lastTickWorkMs  = new AtomicLong();
    private final AtomicInteger stalledSessions = new AtomicInteger();

    public AdmissionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressOverwrites = Counter.builder(M_INGRESS_OVERWRITES)
                .description("Action-frame ingress overwrites (last-write-wins collapse)")
                .register(registry);
        this.frameSize = io.micrometer.core.instrument.DistributionSummary.builder(M_FRAME_SIZE)
                .description("Encoded outbound frame size in bytes")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(M_ACTIVE_ENTITIES, activeEntities, AtomicInteger::get)
                .description("Live cap-relevant occupants")
                .register(registry);
        Gauge.builder(M_MAINTENANCE, maintenance, AtomicInteger::get)
                .description("Maintenance flag mirror (0/1)")
                .register(registry);
        Gauge.builder(M_TICK_WORK_MS, lastTickWorkMs, AtomicLong::get)
                .description("Last-tick wall-clock work time in ms")
                .register(registry);
        Gauge.builder(M_STALLED_SESSIONS, stalledSessions, AtomicInteger::get)
                .description("Sessions currently in STALLED grace window (excludes ACTIVE armed tokens)")
                .register(registry);
    }

    /** Increment the tagged rejection counter for the given reason token (D-17). */
    public void incRejected(String reason) {
        Counter.builder(M_REJECTED)
                .tag("reason", reason)
                .description("Admission rejections by reason token (Phase 17 D-17)")
                .register(registry)
                .increment();
    }

    /** Increment the last-write-wins ingress-overwrite aggregate counter (D-09). */
    public void incIngressOverwrite() { ingressOverwrites.increment(); }

    /** Record encoded outbound frame size (bytes) in the distribution summary. */
    public void recordFrameSize(int bytes) { frameSize.record(bytes); }

    // --- D-18 gauge setters ---

    /** Set the count of live cap-relevant occupants. */
    public void setActiveEntities(int n)   { activeEntities.set(n); }

    /** Mirror the maintenance flag as 0 (off) or 1 (on). */
    public void setMaintenance(boolean on) { maintenance.set(on ? 1 : 0); }

    /** Record the most recently completed tick's wall-clock work time (ms). */
    public void setLastTickWorkMs(long ms) { lastTickWorkMs.set(ms); }

    /**
     * Set the count of sessions currently in the STALLED grace window.
     * Called by {@link ResumeTokenRegistry} whenever the STALLED count changes.
     * ACTIVE armed tokens are NOT counted here — fixes the gauge over-reporting bug
     * identified in codex/opencode HIGH reviews.
     */
    public void setStalledSessions(int n)  { stalledSessions.set(n); }
}
