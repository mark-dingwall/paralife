package com.paralife.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.runtime.JettyRuntimeConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase 20 — cross-AI review concern #4: legacy {@code paralife.websocket.idle-timeout-ms}
 * fallback resolution across the five yaml combinations (case E pins the
 * explicit-new-key-equals-default footgun surfaced in the second-round review).
 *
 * <p>Pure unit test of the static helper — no Spring context (cross-AI review #9).
 * Property-binding round-trip is proven separately by {@link
 * com.paralife.runtime.JettyRuntimeConfigTest.BindingRoundTripTest}; this isolates
 * the resolution logic.
 *
 * <p>See: {@code 20-REVIEW-DISPOSITIONS.md} Concern #4 + {@code
 * JettyDeflateCustomizer#resolveEffectiveIdleMs}.
 */
class JettyIdleTimeoutFallbackTest {

    @Nested
    class CaseA_neitherSet {
        @Test
        void bothAtDefault_usesNewKeyDefault() {
            JettyRuntimeConfig cfg = JettyRuntimeConfig.defaults(); // idleTimeoutMs=60000
            long legacy = JettyDeflateCustomizer.IDLE_TIMEOUT_DEFAULT_MS; // legacy @Value default
            assertThat(JettyDeflateCustomizer.resolveEffectiveIdleMs(cfg, legacy)).isEqualTo(60000L);
        }
    }

    @Nested
    class CaseB_newOnlySet {
        @Test
        void newKeyOverridesDefault_legacyAtDefault() {
            JettyRuntimeConfig cfg = new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 30000L, true, -1);
            long legacy = JettyDeflateCustomizer.IDLE_TIMEOUT_DEFAULT_MS; // unset by operator
            assertThat(JettyDeflateCustomizer.resolveEffectiveIdleMs(cfg, legacy)).isEqualTo(30000L);
        }
    }

    @Nested
    class CaseC_legacyOnlySet {
        @Test
        void legacyKeyHonoured_newKeyAtDefault() {
            JettyRuntimeConfig cfg = JettyRuntimeConfig.defaults(); // idleTimeoutMs=60000 (default)
            long legacy = 45000L; // legacy explicitly set by operator
            assertThat(JettyDeflateCustomizer.resolveEffectiveIdleMs(cfg, legacy)).isEqualTo(45000L);
        }
    }

    @Nested
    class CaseD_bothSet {
        @Test
        void newKeyWinsWhenBothSet() {
            JettyRuntimeConfig cfg = new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 30000L, true, -1);
            long legacy = 45000L;
            assertThat(JettyDeflateCustomizer.resolveEffectiveIdleMs(cfg, legacy)).isEqualTo(30000L);
        }
    }

    @Nested
    class CaseE_bothSetNewAtDefault {
        @Test
        void legacyWinsWhenNewKeyExplicitlyEqualsDefault() {
            // Footgun (cross-AI review #4): operator explicitly pins the new key to 60000
            // AND sets the legacy key to 45000. The primitive field cannot tell explicit-60000
            // from unset, so the proxy treats the new key as "unset" → legacy wins (45000).
            // Pinned so it cannot silently regress; documented in JettyDeflateCustomizer javadoc.
            JettyRuntimeConfig cfg = new JettyRuntimeConfig(4096, 4096, 65536L, 65536L, 65536L, 60000L, true, -1);
            long legacy = 45000L;
            assertThat(JettyDeflateCustomizer.resolveEffectiveIdleMs(cfg, legacy)).isEqualTo(45000L);
        }
    }
}
