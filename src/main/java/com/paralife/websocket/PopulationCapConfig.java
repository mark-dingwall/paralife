package com.paralife.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Temporary world-level registration back-pressure for
 * {@link WorldWebSocketHandler}.
 *
 * <p>The cap counts live non-rock / non-nutrient occupants on the grid. When a
 * client sends {@code r|} and the count is already at or above the cap, the
 * server refuses the registration with {@code E|429|population cap exceeded}.
 * This gates external load injection (initial register or respawn) without
 * interfering with in-simulation reproduction, which continues to be governed
 * by world rules.
 *
 * <p>Bound from {@code application.yml} under
 * {@code paralife.websocket.max-active-entities}. The production default is a
 * conservative temporary guardrail while the longer-term policy is revisited.
 *
 * @param maxActiveEntities maximum allowed live non-terrain occupants before
 *                          register / respawn requests are denied (must be
 *                          {@code > 0}).
 */
@ConfigurationProperties(prefix = "paralife.websocket")
public record PopulationCapConfig(int maxActiveEntities) {

    /**
     * Conservative temporary default: 2.5x the validated 100-bot operator
     * envelope, leaving headroom for offspring / bonded / composite occupants
     * while still bounding external load injection.
     */
    public static final int DEFAULT_MAX_ACTIVE_ENTITIES = 256;

    @ConstructorBinding
    public PopulationCapConfig {
        if (maxActiveEntities <= 0) {
            throw new IllegalArgumentException(
                    "paralife.websocket.max-active-entities must be > 0 (got "
                            + maxActiveEntities + ")");
        }
    }

    /** Convenience for tests that instantiate the handler without Spring. */
    public static PopulationCapConfig defaults() {
        return new PopulationCapConfig(DEFAULT_MAX_ACTIVE_ENTITIES);
    }
}
