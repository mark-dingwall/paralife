package com.paralife.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.websocket.JettyDeflateCustomizer.DeflateEnforcementFilter;
import org.junit.jupiter.api.Test;

/**
 * C1 deflate exemption must match {@code /ws/observer} EXACTLY (context-path-relative), not by
 * prefix. Pins that the bot route stays enforced and that neither a context-path nor a sibling
 * {@code /ws/observer*} route breaks the exemption.
 */
class ObserverRouteExemptionTest {

    @Test
    void exemptsObserverAtRoot() {
        assertThat(DeflateEnforcementFilter.isObserverRoute("", "/ws/observer")).isTrue();
    }

    @Test
    void exemptsObserverUnderAContextPath() {
        // startsWith("/ws/observer") would MISS this and reject the browser handshake.
        assertThat(DeflateEnforcementFilter.isObserverRoute("/paralife", "/paralife/ws/observer"))
                .as("context-path-relative exact match").isTrue();
    }

    @Test
    void doesNotExemptTheBotRoute() {
        // load-bearing: /ws/world must stay under server_no_context_takeover enforcement.
        assertThat(DeflateEnforcementFilter.isObserverRoute("", "/ws/world")).isFalse();
    }

    @Test
    void doesNotExemptASiblingObserverPrefixedRoute() {
        // startsWith would WRONGLY exempt this; exact match must not.
        assertThat(DeflateEnforcementFilter.isObserverRoute("", "/ws/observer-admin")).isFalse();
    }

    @Test
    void nullUriIsNotExempt() {
        assertThat(DeflateEnforcementFilter.isObserverRoute("", null)).isFalse();
    }
}
