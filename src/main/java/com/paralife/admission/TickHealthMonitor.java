package com.paralife.admission;

import com.paralife.engine.TickConfig;
import com.paralife.engine.TickEngine;
import com.paralife.engine.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rolling-window hysteresis gate (Phase 17 D-14, D-15).
 *
 * <p>Samples {@link TickEngine#getLastTickWorkMs()} once per tick at {@code @Order(Integer.MAX_VALUE)}
 * (after all other listeners including {@code TickBroadcaster @Order(50)}) into a ring buffer of
 * size {@code windowTicks}. Computes the rolling mean. Opens the gate when mean &gt;
 * {@code highWaterPct} of {@link TickConfig#intervalMs}; clears when mean &lt;
 * {@code lowWaterPct}. Hysteresis (gap between watermarks) prevents flapping.
 *
 * <p><b>Window-fill guard (claude MEDIUM review):</b> the gate evaluates ONLY once the window is
 * fully populated ({@code filled == window.length}). During warm-up, {@link #isOverloaded()} stays
 * false regardless of sample magnitude — a single cold-start spike cannot trip overload.
 *
 * <p><b>Sampling-tick semantics (codex MEDIUM):</b> because {@code TickEngine.lastTickWorkMs} is
 * set AFTER {@code publishEvent} returns, this listener at {@code @Order(Integer.MAX_VALUE)} reads
 * the value from tick N-1 during tick N's dispatch. The gauge therefore lags by 1 tick relative to
 * the dispatching {@code TickEvent}. This is acceptable for hysteresis correctness — the rolling
 * mean is still computed over a contiguous window — but operators reading the gauge live will see
 * N-1 latency compared to the tick that triggered a gate transition.
 */
@Component
public class TickHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(TickHealthMonitor.class);

    private final TickEngine tickEngine;
    private final AdmissionConfig admissionConfig;
    private final TickConfig tickConfig;
    private final AdmissionMetrics admissionMetrics;

    private final long[] window;
    private int head = 0;
    private long sum = 0;
    private int filled = 0;
    private volatile boolean overloaded = false;

    public TickHealthMonitor(TickEngine tickEngine,
                             AdmissionConfig admissionConfig,
                             TickConfig tickConfig,
                             AdmissionMetrics admissionMetrics) {
        this.tickEngine = tickEngine;
        this.admissionConfig = admissionConfig;
        this.tickConfig = tickConfig;
        this.admissionMetrics = admissionMetrics;
        this.window = new long[admissionConfig.tickOverload().windowTicks()];
    }

    @EventListener
    @Order(Integer.MAX_VALUE)
    public void onTick(TickEvent event) {
        long sample = tickEngine.getLastTickWorkMs();
        admissionMetrics.setLastTickWorkMs(sample);

        // Push into ring buffer.
        sum -= window[head];
        window[head] = sample;
        sum += sample;
        head = (head + 1) % window.length;
        if (filled < window.length) filled++;

        // WINDOW-FILL GUARD: defer gate evaluation until the window is fully populated.
        // Until filled == window.length, a single cold-start spike cannot trip overload.
        if (filled < window.length) return;

        double mean = (double) sum / filled;
        long budget = tickConfig.intervalMs();
        int highPct = admissionConfig.tickOverload().highWaterPct();
        int lowPct = admissionConfig.tickOverload().lowWaterPct();

        double highThreshold = budget * (highPct / 100.0);
        double lowThreshold  = budget * (lowPct / 100.0);

        if (!overloaded && mean > highThreshold) {
            overloaded = true;
            log.info("TICK-HEALTH degraded tick={} work-ms={} high-water-pct={}",
                    event.tickNumber(), (long) mean, highPct);
        } else if (overloaded && mean < lowThreshold) {
            overloaded = false;
            log.info("TICK-HEALTH recovered tick={} work-ms={} low-water-pct={}",
                    event.tickNumber(), (long) mean, lowPct);
        }
    }

    /** Returns true when the rolling mean tick-work-time exceeds the high-water mark. */
    public boolean isOverloaded() { return overloaded; }

    /** Test-only: current rolling mean (ms). Returns 0 during warm-up. */
    public double currentMeanMs() { return filled == 0 ? 0.0 : (double) sum / filled; }

    /** Test-only: number of samples accumulated (capped at window.length). */
    int filledCount() { return filled; }
}
