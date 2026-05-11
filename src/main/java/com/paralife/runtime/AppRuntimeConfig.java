package com.paralife.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application-level runtime tuning surface (Phase 20 D-07 layer 3, D-09).
 *
 * <p>Bound to {@code paralife.runtime.app.*} in {@code application.yml}.
 *
 * <p><strong>D-20: This record does NOT contain {@code outbound-queue-size}.</strong>
 * The outbound queue size lives in {@link
 * com.paralife.admission.AdmissionConfig.BackpressureConfig#outboundQueueSize()}
 * — that key is kept in place for Phase 20 (CLAUDE.md / 17-ADMISSION.md /
 * AdmissionConfig.java cross-references stay intact). Phase 999.4 owns the
 * eventual namespace consolidation. {@code AppRuntimeConfig.OutboundConfig}
 * carries <em>sibling</em> outbound knobs only.
 *
 * <p><strong>Pass-2 Concern #7 (gemini + codex):</strong> All fields in this
 * record are tagged {@code [reserved — no effect in Phase 20]}. Plan 5's
 * no-public-API-change rule on {@code PerceptionCodec.encode(Frame)} makes
 * {@code frameSizeBudgetBytes} structurally unconsumable in this phase (the
 * codec allocates its own {@code StringBuilder} internally and takes no
 * capacity argument). Other reserved fields await M5 admin UI / Phase 19.1
 * follow-up consumers. The record exists to stand up the binding surface;
 * tuning operators get the framework, not yet the levers.
 *
 * <p>Decisions:
 * <ul>
 *   <li>D-07: layer 3 of the four-layer tuning surface</li>
 *   <li>D-09: @ConfigurationProperties record mirrors AdmissionConfig + nested-record style</li>
 *   <li>D-20: layer alongside paralife.admission.backpressure.outbound-queue-size, do NOT move</li>
 * </ul>
 *
 * <p>See {@code .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md} §2.
 */
@ConfigurationProperties(prefix = "paralife.runtime.app")
public record AppRuntimeConfig(
        @DefaultValue OutboundConfig outbound,
        @DefaultValue EncodeConfig encode) {

    @ConstructorBinding
    public AppRuntimeConfig {
        if (outbound == null) outbound = OutboundConfig.defaults();
        if (encode == null) encode = EncodeConfig.defaults();
    }

    /** Convenience for tests + programmatic construction. */
    public static AppRuntimeConfig defaults() {
        return new AppRuntimeConfig(OutboundConfig.defaults(), EncodeConfig.defaults());
    }

    /**
     * Sibling outbound knobs (D-20 — does NOT include outbound-queue-size; that
     * lives in {@code paralife.admission.backpressure}).
     *
     * <p>Bound to {@code paralife.runtime.app.outbound.*}.
     */
    public record OutboundConfig(
            /** [reserved — no effect in Phase 20] When the outbound queue is at this %, emit a slow-client warning. Consumer wiring deferred (M5 admin UI / future plan). Range 1..100. */
            @DefaultValue("80") int queueWatermarkPct,
            /**
             * [reserved — no effect in Phase 20] Per-frame size budget hint for codec sizing decisions (bytes).
             * Pass-2 Concern #7: PerceptionCodec.encode(Frame) takes no capacity argument and allocates its
             * own StringBuilder internally; consuming this would require a public-API change forbidden by
             * Plan 5. Reserved for future codec opts that pass capacity through (Phase 999.4 or later).
             * Min 64.
             */
            @DefaultValue("1024") int frameSizeBudgetBytes) {

        @ConstructorBinding
        public OutboundConfig {
            if (queueWatermarkPct < 1 || queueWatermarkPct > 100) {
                throw new IllegalArgumentException(
                        "paralife.runtime.app.outbound.queue-watermark-pct must be 1..100 (got "
                                + queueWatermarkPct + ")");
            }
            if (frameSizeBudgetBytes < 64) {
                throw new IllegalArgumentException(
                        "paralife.runtime.app.outbound.frame-size-budget-bytes must be >= 64 (got "
                                + frameSizeBudgetBytes + ")");
            }
        }

        public static OutboundConfig defaults() {
            return new OutboundConfig(80, 1024);
        }
    }

    /**
     * Encode-pipeline knobs.
     *
     * <p>Bound to {@code paralife.runtime.app.encode.*}.
     *
     * <p>{@code parallelEncodeThreshold} is RESERVED for the Phase 19.1 follow-up
     * (parallel {@code PerceptionBroadcaster}). Sentinel value {@code -1} means
     * "disabled" — Phase 20 ships with the field defined and bindable but no
     * consumer reads it yet.
     */
    public record EncodeConfig(
            /**
             * [reserved — no effect in Phase 20] Phase 19.1 reservation: when active session count exceeds this,
             * encode in parallel. -1 = disabled (Phase 20 default — no consumer wired).
             */
            @DefaultValue("-1") int parallelEncodeThreshold,
            /** [reserved — no effect in Phase 20] Hint for batch sizing within a single tick's broadcast. Min 1. */
            @DefaultValue("8") int encodeBatchHint) {

        @ConstructorBinding
        public EncodeConfig {
            if (parallelEncodeThreshold < -1) {
                throw new IllegalArgumentException(
                        "paralife.runtime.app.encode.parallel-encode-threshold must be >= -1 (got "
                                + parallelEncodeThreshold + ")");
            }
            if (encodeBatchHint < 1) {
                throw new IllegalArgumentException(
                        "paralife.runtime.app.encode.encode-batch-hint must be >= 1 (got "
                                + encodeBatchHint + ")");
            }
        }

        public static EncodeConfig defaults() {
            return new EncodeConfig(-1, 8);
        }
    }
}
