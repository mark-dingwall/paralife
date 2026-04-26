package com.paralife.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 17 D-17/D-18 admission metrics surface.
 *
 * <p>Counters:
 * <ul>
 *   <li>{@link #M_REJECTED} — tagged by rejection token (D-17); single tagged counter, not one per reason.</li>
 *   <li>{@link #M_INGRESS_OVERWRITES} — aggregate inbound last-write-wins collapse counter (D-09).</li>
 *   <li>{@link #M_FRAME_SIZE} — DistributionSummary of encoded outbound frame bytes, recorded by
 *       {@code OutboundSender.drainLoop} after encode (codex MEDIUM — replaces any prior
 *       {@code TickBroadcaster} emission point).</li>
 * </ul>
 *
 * <p>Gauges (D-18):
 * <ul>
 *   <li>{@link #M_ACTIVE_ENTITIES} — live cap-relevant occupants (set by admission/session code).</li>
 *   <li>{@link #M_MAINTENANCE} — 0/1 mirror of {@code AdmissionConfig.maintenance()}.</li>
 *   <li>{@link #M_TICK_WORK_MS} — last-tick wall-clock work time in ms (drives D-14 gate).</li>
 *   <li>{@link #M_STALLED_SESSIONS} — sessions currently in STALLED grace window.</li>
 * </ul>
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
    private final DistributionSummary frameSize;

    private final AtomicInteger activeEntities  = new AtomicInteger();
    private final AtomicInteger maintenance     = new AtomicInteger();
    private final AtomicLong    lastTickWorkMs  = new AtomicLong();
    private final AtomicInteger stalledSessions = new AtomicInteger();

    public AdmissionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressOverwrites = Counter.builder(M_INGRESS_OVERWRITES)
                .description("Action-frame ingress overwrites (last-write-wins collapse, D-09)")
                .register(registry);
        this.frameSize = DistributionSummary.builder(M_FRAME_SIZE)
                .description("Encoded outbound frame size in bytes (OutboundSender drain loop; "
                        + "codex MEDIUM — replaces TickBroadcaster emission)")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(M_ACTIVE_ENTITIES, activeEntities, AtomicInteger::get)
                .description("Live cap-relevant occupants (D-18)")
                .register(registry);
        Gauge.builder(M_MAINTENANCE, maintenance, AtomicInteger::get)
                .description("Maintenance flag mirror 0/1 (D-18)")
                .register(registry);
        Gauge.builder(M_TICK_WORK_MS, lastTickWorkMs, AtomicLong::get)
                .description("Last-tick wall-clock work time in ms (drives D-14 gate, D-18)")
                .register(registry);
        Gauge.builder(M_STALLED_SESSIONS, stalledSessions, AtomicInteger::get)
                .description("Sessions currently in STALLED grace window (D-18)")
                .register(registry);
    }

    /** Increments the tagged rejection counter for {@code reason} (D-17). */
    public void incRejected(String reason) {
        Counter.builder(M_REJECTED)
                .tag("reason", reason)
                .description("Admission rejections by reason token (Phase 17 D-17)")
                .register(registry)
                .increment();
    }

    /** Increments the aggregate ingress-overwrite counter (D-09). */
    public void incIngressOverwrite() {
        ingressOverwrites.increment();
    }

    /**
     * Records the byte length of an encoded outbound frame.
     * Called by {@code OutboundSender.drainLoop} after encoding — single measurement point
     * so callers such as {@code TickBroadcaster} must NOT add a duplicate call (codex MEDIUM).
     */
    public void recordFrameSize(int bytes) {
        frameSize.record(bytes);
    }

    public void setActiveEntities(int n)    { activeEntities.set(n); }
    public void setMaintenance(boolean on)  { maintenance.set(on ? 1 : 0); }
    public void setLastTickWorkMs(long ms)  { lastTickWorkMs.set(ms); }
    public void setStalledSessions(int n)   { stalledSessions.set(n); }
}
