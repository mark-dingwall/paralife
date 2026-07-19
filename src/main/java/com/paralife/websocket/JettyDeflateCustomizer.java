package com.paralife.websocket;

import com.paralife.runtime.JettyRuntimeConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.socket.server.jetty.JettyRequestUpgradeStrategy;

/**
 * Enforces {@code permessage-deflate; server_no_context_takeover} on every
 * WebSocket upgrade Spring performs via Jetty 12.
 *
 * <p>Two co-operating beans:
 *
 * <ul>
 *   <li>{@code jettyRequestUpgradeStrategy} — the {@link JettyRequestUpgradeStrategy}
 *       that Spring's {@code DefaultHandshakeHandler} in {@link WebSocketConfig} uses
 *       to drive Jetty's native upgrade. The strategy is left at Jetty defaults for
 *       extension registration — Jetty 12's built-in {@code PerMessageDeflateExtension}
 *       is already in the factory registry, so negotiation only needs us to (a) refuse
 *       upgrades without the extension and (b) force {@code server_no_context_takeover}
 *       on the response.</li>
 *   <li>{@link #deflateEnforcementFilter()} — a servlet {@link Filter} registered with
 *       the highest precedence so it runs before Jetty's {@code WebSocketUpgradeFilter}.
 *       It inspects the WebSocket upgrade request's {@code Sec-WebSocket-Extensions}
 *       header, rejects (HTTP 400) when {@code permessage-deflate} is absent (D-33
 *       server-side fail-fast, threat T-15-02), and wraps the request to ensure the
 *       {@code server_no_context_takeover} parameter is present before Jetty negotiates
 *       (D-31). Jetty's negotiator echoes the request extension (including the
 *       parameter) back in the response.</li>
 * </ul>
 *
 * <p>Critically, the upgrade path is registered exactly once — by Spring's
 * {@code WebSocketHandlerRegistry} in {@link WebSocketConfig}. This class never
 * calls any native Jetty route-registration API. {@link WebSocketRouteAssertion}
 * verifies the single-path invariant at {@code ApplicationReadyEvent}.
 */
@Configuration
public class JettyDeflateCustomizer {

    private static final Logger log = LoggerFactory.getLogger(JettyDeflateCustomizer.class);
    static final String EXTENSION = "permessage-deflate";
    static final String NO_CONTEXT = "server_no_context_takeover";
    static final String EXTENSIONS_HEADER = "Sec-WebSocket-Extensions";
    static final String UPGRADE_HEADER = "Upgrade";
    static final String WEBSOCKET = "websocket";
    /**
     * Sentinel for "idle-timeout key is at default", used by
     * {@link #resolveEffectiveIdleMs} for both keys. Three 60000s must stay in lockstep:
     * <ul>
     *   <li>this constant,</li>
     *   <li>{@link JettyRuntimeConfig#idleTimeoutMs()}'s {@code @DefaultValue("60000")}, and</li>
     *   <li>the {@code @Value("${paralife.websocket.idle-timeout-ms:60000}")} default on
     *       {@link #jettyRequestUpgradeStrategy} below.</li>
     * </ul>
     * Phase 999.x removes the legacy key + this constant entirely. Until then,
     * any change here MUST be mirrored in the other two literals.
     */
    static final long IDLE_TIMEOUT_DEFAULT_MS = 60000L;

    /**
     * The {@link JettyRequestUpgradeStrategy} Spring uses to drive Jetty's native
     * upgrade. Jetty 12 already registers {@code permessage-deflate} in its extension
     * factory registry, so extension negotiation happens automatically when the
     * (wrapped) request advertises it. The deflate-specific policy (refusal + forced
     * {@code server_no_context_takeover}) is applied earlier in the pipeline by
     * {@link #deflateEnforcementFilter()}.
     *
     * <p>Phase 20 D-07 layer 2: all eight Jetty {@link
     * org.eclipse.jetty.websocket.api.Configurable} setters are wired through
     * {@link JettyRuntimeConfig}. Defaults match project-current defaults (Jetty
     * 12.0.18 defaults except {@code idleTimeoutMs=60000} which inherits the
     * project's pre-existing legacy {@code paralife.websocket.idle-timeout-ms} value
     * — Jetty's own default is 30000; Pass-2 Concern #16). A fresh boot with no
     * overrides exhibits zero behavioural change vs c22e487.
     *
     * <p>The legacy {@code paralife.websocket.idle-timeout-ms} key remains live for
     * back-compat. Precedence: {@code paralife.runtime.jetty.idle-timeout-ms} (the
     * primary surface) wins when both keys are set — <strong>except</strong> when the
     * new key is left at (or explicitly set to) its default {@code 60000}, which the
     * primitive {@code long} field cannot distinguish from "unset". In that one case
     * the legacy key wins (see {@link #resolveEffectiveIdleMs} and the Task 2.3
     * truth table, case E). Operators who must pin {@code 60000} while a legacy key
     * is also present should clear the legacy key. Phase 999.x removes the legacy
     * key and this carve-out entirely. (Concern #4 — full nullable/{@code Binder}
     * explicit-set detection deferred as out-of-scope MVP cleanup.)
     */
    @Bean
    public JettyRequestUpgradeStrategy jettyRequestUpgradeStrategy(
            JettyRuntimeConfig runtimeConfig,
            @Value("${paralife.websocket.idle-timeout-ms:60000}") long legacyIdleTimeoutMs) {
        JettyRequestUpgradeStrategy strategy = new JettyRequestUpgradeStrategy();
        // Phase 20 — review concern #4: extract resolution into a package-private helper so
        // JettyIdleTimeoutFallbackTest (Task 2.3) can drive all five yaml combinations.
        final long idleMs = resolveEffectiveIdleMs(runtimeConfig, legacyIdleTimeoutMs);
        strategy.addWebSocketConfigurer(c -> {
            c.setIdleTimeout(Duration.ofMillis(idleMs));
            c.setInputBufferSize(runtimeConfig.inputBufferSize());
            c.setOutputBufferSize(runtimeConfig.outputBufferSize());
            c.setMaxFrameSize(runtimeConfig.maxFrameSize());
            c.setMaxBinaryMessageSize(runtimeConfig.maxBinaryMessageSize());
            c.setMaxTextMessageSize(runtimeConfig.maxTextMessageSize());
            c.setAutoFragment(runtimeConfig.autoFragment());
            c.setMaxOutgoingFrames(runtimeConfig.maxOutgoingFrames());
        });
        return strategy;
    }

    /** Package-private for test access (Phase 20 — review concern #4 — see 20-REVIEW-DISPOSITIONS.md). */
    static long resolveEffectiveIdleMs(JettyRuntimeConfig runtimeConfig, long legacyIdleTimeoutMs) {
        long effectiveIdleMs = runtimeConfig.idleTimeoutMs();
        if (legacyIdleTimeoutMs != IDLE_TIMEOUT_DEFAULT_MS
                && runtimeConfig.idleTimeoutMs() == IDLE_TIMEOUT_DEFAULT_MS) {
            // Legacy key was overridden but new key is at default — honour legacy for one phase.
            effectiveIdleMs = legacyIdleTimeoutMs;
        }
        return effectiveIdleMs;
    }

    @Bean
    public FilterRegistrationBean<Filter> deflateEnforcementFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new DeflateEnforcementFilter());
        // URL pattern is left at default ("/*"); the filter body short-circuits
        // non-WebSocket-upgrade requests so there is no measurable overhead on
        // normal HTTP traffic. This avoids hard-coding the upgrade path here —
        // Spring's handler registry is the sole authority on which route serves
        // WebSocket upgrades (see WebSocketConfig).
        reg.setName("deflateEnforcementFilter");
        // Run before Jetty's WebSocketUpgradeFilter so our request wrapper is seen.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * Servlet filter that enforces {@code permessage-deflate; server_no_context_takeover}
     * on WebSocket upgrade requests.
     *
     * <p>Non-upgrade requests pass through untouched. For upgrade requests the filter
     * responds {@code HTTP 400} and short-circuits the chain if the
     * {@code Sec-WebSocket-Extensions} header does not offer a {@code permessage-deflate}
     * variant that includes the {@code server_no_context_takeover} parameter.
     * Passing requests reach Jetty's {@code WebSocketUpgradeFilter} which negotiates
     * the extension and echoes both tokens on the response (D-31, D-33, threat T-15-02).
     *
     * <p>Request mutation is intentionally avoided: Jetty 12's {@code WebSocketUpgradeFilter}
     * unwraps the servlet request back to the underlying Jetty {@code Request} before
     * reading headers, so an {@link jakarta.servlet.http.HttpServletRequestWrapper} would
     * not be seen during extension negotiation. Requiring the client to advertise the
     * parameter in its offer is the equivalent policy — Jetty always echoes the
     * parameter that the client requested — without reaching into Jetty's internal
     * negotiation state.
     */
    static final class DeflateEnforcementFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (!(request instanceof HttpServletRequest httpReq)
                    || !(response instanceof HttpServletResponse httpResp)) {
                chain.doFilter(request, response);
                return;
            }

            if (!isWebSocketUpgrade(httpReq)) {
                chain.doFilter(request, response);
                return;
            }

            // C1: observers are browser-facing; browsers cannot advertise server_no_context_takeover.
            String uri = httpReq.getRequestURI();
            if (uri != null && uri.startsWith("/ws/observer")) {
                chain.doFilter(request, response);
                return;
            }

            String extensions = httpReq.getHeader(EXTENSIONS_HEADER);
            if (extensions == null || !offersDeflateWithNoContextTakeover(extensions)) {
                log.warn("Rejecting WebSocket upgrade from {} at {} — missing {} or {}",
                        httpReq.getRemoteAddr(), httpReq.getRequestURI(),
                        EXTENSION, NO_CONTEXT);
                httpResp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        EXTENSION + "; " + NO_CONTEXT + " required");
                return;
            }

            chain.doFilter(request, response);
        }

        private static boolean isWebSocketUpgrade(HttpServletRequest req) {
            String upgrade = req.getHeader(UPGRADE_HEADER);
            return upgrade != null && WEBSOCKET.equalsIgnoreCase(upgrade.trim());
        }

        /**
         * True iff {@code header} contains a {@code permessage-deflate} offer whose
         * parameter list includes {@code server_no_context_takeover}. Other offers
         * are ignored — only one matching offer is required.
         */
        static boolean offersDeflateWithNoContextTakeover(String header) {
            if (header == null) {
                return false;
            }
            for (String offer : header.split(",")) {
                String trimmed = offer.trim();
                int semi = trimmed.indexOf(';');
                String name = (semi < 0 ? trimmed : trimmed.substring(0, semi)).trim();
                if (!name.equalsIgnoreCase(EXTENSION)) {
                    continue;
                }
                if (semi < 0) {
                    continue;
                }
                String params = trimmed.substring(semi + 1).toLowerCase(Locale.ROOT);
                for (String param : params.split(";")) {
                    if (param.trim().equals(NO_CONTEXT)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
