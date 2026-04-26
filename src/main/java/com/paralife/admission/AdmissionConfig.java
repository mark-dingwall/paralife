package com.paralife.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Durable admission policy config (Phase 17, replaces {@code PopulationCapConfig}).
 *
 * <p>Bound to {@code paralife.admission.*} in {@code application.yml}.
 *
 * <p>Decisions:
 * <ul>
 *   <li>D-01: single global cap counts cap-relevant occupants</li>
 *   <li>D-15: tick-overload watermarks live in config, not constants</li>
 *   <li>D-16: maintenance is a static flag (restart required to flip)</li>
 * </ul>
 *
 * <p>See {@code .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md}
 * for the full admission specification.
 */
@ConfigurationProperties(prefix = "paralife.admission")
public record AdmissionConfig(
        @DefaultValue("256") int cap,
        @DefaultValue("false") boolean maintenance,
        @DefaultValue TickOverloadConfig tickOverload,
        @DefaultValue BackpressureConfig backpressure) {

    /**
     * Conservative default: 2.5x the validated 100-bot operator envelope,
     * leaving headroom for offspring / bonded / composite occupants while
     * still bounding external load injection.
     */
    public static final int DEFAULT_CAP = 256;

    @ConstructorBinding
    public AdmissionConfig {
        if (cap <= 0) {
            throw new IllegalArgumentException(
                    "paralife.admission.cap must be > 0 (got " + cap + ")");
        }
        if (tickOverload == null) tickOverload = TickOverloadConfig.defaults();
        if (backpressure == null) backpressure = BackpressureConfig.defaults();
    }

    /** Convenience for tests that instantiate without Spring. */
    public static AdmissionConfig defaults() {
        return new AdmissionConfig(DEFAULT_CAP, false,
                TickOverloadConfig.defaults(), BackpressureConfig.defaults());
    }

    /**
     * Tick-health hysteresis gate configuration (D-14 / D-15).
     *
     * <p>The gate opens (overloaded=true) when the rolling mean of tick-work-time
     * over {@code windowTicks} exceeds {@code highWaterPct}% of the tick-interval
     * budget; it clears when the mean drops below {@code lowWaterPct}%. Hysteresis
     * prevents flapping on single-tick GC spikes.
     */
    public record TickOverloadConfig(
            @DefaultValue("80") int highWaterPct,
            @DefaultValue("60") int lowWaterPct,
            @DefaultValue("10") int windowTicks) {

        @ConstructorBinding
        public TickOverloadConfig {
            if (highWaterPct < 1 || highWaterPct > 100) {
                throw new IllegalArgumentException(
                        "paralife.admission.tick-overload.high-water-pct must be 1..100 (got "
                                + highWaterPct + ")");
            }
            if (lowWaterPct < 1 || lowWaterPct > 100) {
                throw new IllegalArgumentException(
                        "paralife.admission.tick-overload.low-water-pct must be 1..100 (got "
                                + lowWaterPct + ")");
            }
            if (highWaterPct <= lowWaterPct) {
                throw new IllegalArgumentException(
                        "paralife.admission.tick-overload.high-water-pct (" + highWaterPct
                                + ") must be > low-water-pct (" + lowWaterPct + ")");
            }
            if (windowTicks < 1) {
                throw new IllegalArgumentException(
                        "paralife.admission.tick-overload.window-ticks must be >= 1 (got "
                                + windowTicks + ")");
            }
        }

        /**
         * Defaults: high=80%, low=60%, window=10 ticks.
         *
         * <p>Reasoning: at a 100ms tick budget the gate opens at 80ms mean work
         * (20% headroom before actual drift) and clears at 60ms (20-point hysteresis
         * band). A 10-tick window smooths single-tick GC spikes without being too
         * slow to respond.
         */
        public static TickOverloadConfig defaults() {
            return new TickOverloadConfig(80, 60, 10);
        }
    }

    /**
     * Outbound backpressure configuration (D-09 / D-10 / D-11 / D-12).
     *
     * <p>Each connected session is paired with one virtual thread that drains an
     * {@code ArrayBlockingQueue<Frame>(outboundQueueSize)}. When the queue is full,
     * the session transitions to STALLED; the entity is held on the grid for
     * {@code graceWindowTicks} to allow reconnection with the resume token.
     */
    public record BackpressureConfig(
            @DefaultValue("16") int outboundQueueSize,
            @DefaultValue("10") int graceWindowTicks) {

        @ConstructorBinding
        public BackpressureConfig {
            if (outboundQueueSize < 1) {
                throw new IllegalArgumentException(
                        "paralife.admission.backpressure.outbound-queue-size must be >= 1 (got "
                                + outboundQueueSize + ")");
            }
            if (graceWindowTicks < 1) {
                throw new IllegalArgumentException(
                        "paralife.admission.backpressure.grace-window-ticks must be >= 1 (got "
                                + graceWindowTicks + ")");
            }
        }

        /**
         * Defaults: 16-frame queue, 10-tick grace window.
         *
         * <p>16 frames: at 10Hz gives ~1.6s of frames buffered per session — enough
         * to survive a brief network hiccup without triggering stall. 10-tick grace
         * balances "tolerate a tab switch" against "don't hoard reaper slots".
         */
        public static BackpressureConfig defaults() {
            return new BackpressureConfig(16, 10);
        }
    }
}
