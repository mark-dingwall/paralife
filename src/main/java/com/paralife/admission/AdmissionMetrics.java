package com.paralife.admission;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer gauges and counters for the Phase 17 admission / tick-health surface (D-17, D-18).
 *
 * <h2>Gauges</h2>
 * <ul>
 *   <li>{@link #M_TICK_WORK_MS} — wall-clock work time of the most recently completed tick (ms).
 *       Fed by {@link TickHealthMonitor} each tick.</li>
 * </ul>
 *
 * <p>Additional counters (rejected, ingress.overwrites) and gauges (active.entities,
 * maintenance, stalled.sessions) are registered by the components that own their
 * increment/read sites (AdmissionGate, ActionResolver, ResumeTokenRegistry).
 */
@Component
public class AdmissionMetrics {

    /** Gauge name: last tick work time in ms (D-18). */
    public static final String M_TICK_WORK_MS = "paralife.tick.health.work-time-ms";

    private final AtomicLong lastTickWorkMs = new AtomicLong(0);

    public AdmissionMetrics(MeterRegistry registry) {
        Gauge.builder(M_TICK_WORK_MS, lastTickWorkMs, AtomicLong::doubleValue)
                .description("Wall-clock work time of the most recently completed tick (ms). " +
                             "Reflects tick N-1 during tick N dispatch — see TickEngine.getLastTickWorkMs() Javadoc.")
                .baseUnit("ms")
                .register(registry);
    }

    /**
     * Called by {@link TickHealthMonitor#onTick} each tick to update the gauge.
     * Thread-safe — {@code AtomicLong} write, single writer in practice (tick VT).
     */
    public void setLastTickWorkMs(long ms) {
        lastTickWorkMs.set(ms);
    }
}
