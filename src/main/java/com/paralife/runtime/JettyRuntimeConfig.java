package com.paralife.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Jetty WebSocket runtime tuning surface (Phase 20 D-07 layer 2, D-09).
 *
 * <p>Bound to {@code paralife.runtime.jetty.*} in {@code application.yml}.
 * Wired into {@link org.springframework.web.socket.server.jetty.JettyRequestUpgradeStrategy}
 * by {@code JettyDeflateCustomizer.jettyRequestUpgradeStrategy} via
 * {@code addWebSocketConfigurer(Configurable)}.
 *
 * <p>Defaults match <strong>project-current defaults</strong> — Jetty 12.0.18
 * defaults except {@code idleTimeoutMs=60000} which inherits the project's
 * pre-existing legacy {@code paralife.websocket.idle-timeout-ms} value (Jetty's
 * own default is 30000). A fresh boot with no overrides exhibits zero behavioural
 * change vs the c22e487 baseline. Tuning is JFR-driven per D-10/D-13 — see
 * {@code .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md} §3.
 *
 * <p>(Pass-2 Concern #16: do NOT "fix" {@code idleTimeoutMs=60000} to Jetty's bare
 * 30000 default — the 60000 inherits the project's pre-existing operator behaviour,
 * codified in Phase 17 17-ADMISSION.md as the keepalive-service braces' belt. If you
 * want to tighten it for a specific tier, that's a per-recipe override in
 * {@code 20-RUNTIME.md} §3, not a default change.)
 *
 * <p>All eight setters are <strong>launch-only</strong> per Jetty 12's
 * {@code Configurable} contract: applied at WS upgrade per session; no live
 * mutation API. {@code @RefreshScope} hook seams therefore do not apply here.
 *
 * <p>Decisions:
 * <ul>
 *   <li>D-07: layer 2 of the four-layer tuning surface</li>
 *   <li>D-09: @ConfigurationProperties record mirrors the project pattern (AdmissionConfig)</li>
 *   <li>D-15: tuning lives in config (Phase 17 precedent)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "paralife.runtime.jetty")
public record JettyRuntimeConfig(
        /** [launch-only] Jetty input buffer size (bytes). Jetty default: 4096. */
        @DefaultValue("4096") int inputBufferSize,
        /** [launch-only] Jetty output buffer size (bytes). Jetty default: 4096. */
        @DefaultValue("4096") int outputBufferSize,
        /** [launch-only] Max single WebSocket frame (bytes). Jetty default: 65536. Preserves Jetty's existing cap (T-20-DOS-1); launch-only operator knob, not attacker-controllable. */
        @DefaultValue("65536") long maxFrameSize,
        /** [launch-only] Max accumulated binary message (bytes). Jetty default: 65536. */
        @DefaultValue("65536") long maxBinaryMessageSize,
        /** [launch-only] Max accumulated text message (bytes). Jetty default: 65536. */
        @DefaultValue("65536") long maxTextMessageSize,
        /** [launch-only] Server-side idle close timeout (ms). Jetty default: 30000; project: 60000. */
        @DefaultValue("60000") long idleTimeoutMs,
        /** [launch-only] Auto-fragment outgoing frames > maxFrameSize. Jetty default: true. */
        @DefaultValue("true") boolean autoFragment,
        /**
         * [launch-only] Max WebSocket frames queued for outgoing write before sends fail
         * (Jetty {@code WritePendingException}). Jetty default: -1 (unlimited). Secondary to
         * the Phase 17 D-10 {@code OutboundSender} bounded queue, which remains the primary
         * outbound backpressure signal; {@code -1} = delegate entirely to that queue (zero
         * behavioural change vs c22e487). Validation carve-out: {@code -1} (unlimited) or a
         * positive cap {@code >= 1}.
         */
        @DefaultValue("-1") int maxOutgoingFrames) {

    @ConstructorBinding
    public JettyRuntimeConfig {
        if (inputBufferSize < 256) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.input-buffer-size must be >= 256 (got " + inputBufferSize + ")");
        }
        if (outputBufferSize < 256) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.output-buffer-size must be >= 256 (got " + outputBufferSize + ")");
        }
        if (maxFrameSize < 1024) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.max-frame-size must be >= 1024 (got " + maxFrameSize + ")");
        }
        if (maxBinaryMessageSize < 1024) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.max-binary-message-size must be >= 1024 (got " + maxBinaryMessageSize + ")");
        }
        if (maxTextMessageSize < 1024) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.max-text-message-size must be >= 1024 (got " + maxTextMessageSize + ")");
        }
        if (idleTimeoutMs < 1000) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.idle-timeout-ms must be >= 1000 (got " + idleTimeoutMs + ")");
        }
        // Carve-out: -1 (Jetty's "unlimited" sentinel) is valid; any other value must be a positive cap >= 1.
        if (maxOutgoingFrames != -1 && maxOutgoingFrames < 1) {
            throw new IllegalArgumentException(
                    "paralife.runtime.jetty.max-outgoing-frames must be -1 (unlimited) or >= 1 (got " + maxOutgoingFrames + ")");
        }
    }

    /**
     * Convenience factory for tests / programmatic construction. Mirrors project-current
     * defaults (Jetty 12.0.18 defaults except idleTimeoutMs=60000 — Pass-2 Concern #16).
     */
    public static JettyRuntimeConfig defaults() {
        return new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 60000L, true, -1);
    }
}
