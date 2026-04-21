package com.paralife.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Per-session respawn cap for {@link WorldWebSocketHandler}. Bounds the
 * respawn-storm DoS vector (T-15-04): a misbehaving client that dies-and-respawns
 * in a tight loop would otherwise chew server CPU on repeated placement
 * attempts. First {@code r|} on a session is registration (not counted);
 * subsequent {@code r|} frames after a {@code markDead} transition each
 * increment a per-session counter. When the counter reaches the cap, the
 * server emits {@code E|429|respawn cap exceeded} and refuses the respawn.
 *
 * <p>Bound from {@code application.yml} under
 * {@code paralife.websocket.max-respawns-per-session}. Production default is
 * {@code 5} (tuned in Phase 15 to match typical bot-lifetime expectations
 * while still curbing runaway sessions). Phase 16 Plan 06 exposed the value
 * as a config property (was a hardcoded constant) so long-run emergence tests
 * can raise the ceiling for 1000-tick exercises without relaxing the
 * production invariant — tests set the property to a large value via
 * {@code @TestPropertySource}.
 *
 * <p>Auto-discovered by the existing
 * {@link org.springframework.boot.context.properties.ConfigurationPropertiesScan}
 * on {@code ParalifeApplication} — no manual registration required.
 *
 * @param maxRespawnsPerSession per-session respawn cap (must be &gt; 0).
 *                              Production default is 5.
 */
@ConfigurationProperties(prefix = "paralife.websocket")
public record RespawnConfig(int maxRespawnsPerSession) {

    /** Production default — matches the prior hardcoded {@code MAX_RESPAWNS_PER_SESSION}. */
    public static final int DEFAULT_MAX_RESPAWNS_PER_SESSION = 5;

    @ConstructorBinding
    public RespawnConfig {
        if (maxRespawnsPerSession <= 0) {
            throw new IllegalArgumentException(
                    "paralife.websocket.max-respawns-per-session must be > 0 (got "
                            + maxRespawnsPerSession + ")");
        }
    }

    /** Convenience for unit tests that instantiate the handler without Spring. */
    public static RespawnConfig defaults() {
        return new RespawnConfig(DEFAULT_MAX_RESPAWNS_PER_SESSION);
    }
}
