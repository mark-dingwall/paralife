package com.paralife.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operator-visibility metrics bean for Phase 17 admission control (D-17 / D-18).
 *
 * <h2>Counter</h2>
 * <ul>
 *   <li>{@link #M_REJECTED} — tagged counter with {@code reason=<token>} per D-17.
 *       Single counter name; tags distinguish reasons. Call {@link #incRejected(String)}.</li>
 *   <li>{@link #M_INGRESS_OVERWRITES} — aggregate counter for last-write-wins collapses
 *       in {@code ActionResolver} (D-09). Call {@link #incIngressOverwrite()}.</li>
 * </ul>
 *
 * <h2>Gauges (D-18)</h2>
 * <ul>
 *   <li>{@link #M_ACTIVE_ENTITIES} — cap-relevant live occupants. Caller: {@link #setActiveEntities(int)}.</li>
 *   <li>{@link #M_MAINTENANCE} — 0/1 mirror of {@code AdmissionConfig.maintenance()}. Caller: {@link #setMaintenance(boolean)}.</li>
 *   <li>{@link #M_TICK_WORK_MS} — most-recently-completed tick wall-clock work time in ms. Caller: {@link #setLastTickWorkMs(long)}.</li>
 *   <li>{@link #M_STALLED_SESSIONS} — sessions currently in STALLED grace window (STALLED entries only, ACTIVE armed tokens excluded per codex/opencode HIGH review). Caller: {@link #setStalledSessions(int)}.</li>
 * </ul>
 *
 * <h2>DistributionSummary</h2>
 * <ul>
 *   <li>{@link #M_FRAME_SIZE} — outbound encoded frame size in bytes. Recorded by
 *       {@code OutboundSender.drainLoop} (Plan 06) after encode. Restored here to address
 *       codex MEDIUM operational-regression review. Call {@link #recordFrameSize(int)}.</li>
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
    public static final String M_REBOUND            = "paralife.backpressure.rebound";
    public static final String M_TERMINAL_DROPOUT   = "paralife.backpressure.terminal.dropouts";

    private final MeterRegistry registry;
    private final Counter ingressOverwrites;
    private final Counter rebound;
    private final Counter terminalDropouts;
    private final DistributionSummary frameSize;

    private final AtomicInteger activeEntities  = new AtomicInteger();
    private final AtomicInteger maintenance     = new AtomicInteger();
    private final AtomicLong    lastTickWorkMs  = new AtomicLong();
    private final AtomicInteger stalledSessions = new AtomicInteger();

    public AdmissionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressOverwrites = Counter.builder(M_INGRESS_OVERWRITES)
                .description("Action-frame ingress overwrites (last-write-wins collapse per D-09)")
                .register(registry);
        this.rebound = Counter.builder(M_REBOUND)
                .description("STALLED sessions that successfully reconnected with their resume token within the grace window. "
                        + "Operator SLI: rebound / (rebound + terminal_dropouts) is the recovery rate.")
                .register(registry);
        this.terminalDropouts = Counter.builder(M_TERMINAL_DROPOUT)
                .description("STALLED sessions whose resume token expired before reconnect; entity reaped by ResumeTokenRegistry sweep. "
                        + "Operator SLI: rising terminal dropouts indicate either widespread slow-consumer conditions or grace-window mis-tuning.")
                .register(registry);
        this.frameSize = DistributionSummary.builder(M_FRAME_SIZE)
                .description("Encoded outbound frame size in bytes; recorded by OutboundSender drain loop (Plan 06). "
                        + "Restored to address codex MEDIUM operational regression — TickBroadcaster no longer records frame size directly (Plan 08).")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(M_ACTIVE_ENTITIES, activeEntities, AtomicInteger::get)
                .description("Live cap-relevant occupants (D-18)")
                .register(registry);
        Gauge.builder(M_MAINTENANCE, maintenance, AtomicInteger::get)
                .description("Maintenance flag mirror: 0=off, 1=on (D-18)")
                .register(registry);
        Gauge.builder(M_TICK_WORK_MS, lastTickWorkMs, AtomicLong::get)
                .description("Most-recently-completed tick wall-clock work time in ms (D-18); lags by 1 tick — see 17-ADMISSION.md §5 operator caveat")
                .register(registry);
        Gauge.builder(M_STALLED_SESSIONS, stalledSessions, AtomicInteger::get)
                .description("Sessions currently in STALLED grace window; ACTIVE armed tokens excluded (D-18, codex/opencode HIGH fix)")
                .register(registry);
    }

    /**
     * Increment the tagged rejection counter. Single counter name; {@code reason} tag
     * distinguishes the cause (D-17 design: one counter per taxonomy, not one per reason).
     *
     * @param reason Token from {@link RejectionToken}.
     */
    public void incRejected(String reason) {
        Counter.builder(M_REJECTED)
                .tag("reason", reason)
                .description("Admission rejections by reason token (Phase 17 D-17)")
                .register(registry)
                .increment();
    }

    /** Increment the aggregate ingress-overwrite counter (D-09 last-write-wins). */
    public void incIngressOverwrite() {
        ingressOverwrites.increment();
    }

    /** Increment when a STALLED session reconnects and rebinds its entity within the grace window. */
    public void incRebound() {
        rebound.increment();
    }

    /** Increment when a STALLED session's grace window expires before reconnect — entity is reaped. */
    public void incTerminalDropout() {
        terminalDropouts.increment();
    }

    /**
     * Record an outbound encoded frame size in bytes.
     * Called by {@code OutboundSender.drainLoop} in Plan 06 after encode.
     */
    public void recordFrameSize(int bytes) {
        frameSize.record(bytes);
    }

    /** Set the cap-relevant live occupant count (D-18). */
    public void setActiveEntities(int n)   { activeEntities.set(n); }

    /** Mirror the maintenance flag as 0 (off) or 1 (on) in the gauge (D-18). */
    public void setMaintenance(boolean on) { maintenance.set(on ? 1 : 0); }

    /** Set the most-recently-completed tick work time in ms (D-18). Lags by 1 tick relative to dispatching TickEvent. */
    public void setLastTickWorkMs(long ms) { lastTickWorkMs.set(ms); }

    /** Set the count of sessions in STALLED grace window (STALLED entries only, per D-18). */
    public void setStalledSessions(int n)  { stalledSessions.set(n); }
}
