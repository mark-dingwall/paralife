package com.paralife.observer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the read-only observer visualiser endpoint ({@code /ws/observer}).
 *
 * <p>Ships {@code enabled=false} — an operator opts in. {@code maxSessions} caps
 * concurrent observers (enforced atomically in {@link ObserverSessionGate}). Real
 * auth / origin policy / rate-limiting is a named later hardening slice (BACKLOG).
 */
@ConfigurationProperties(prefix = "paralife.observer")
public record ObserverConfig(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("4") int maxSessions) {

    @ConstructorBinding
    public ObserverConfig {
        if (maxSessions <= 0) {
            throw new IllegalArgumentException(
                    "paralife.observer.max-sessions must be > 0 (got " + maxSessions + ")");
        }
    }

    /** Convenience for unit tests that instantiate components without Spring. */
    public static ObserverConfig defaults() {
        return new ObserverConfig(false, 4);
    }
}
