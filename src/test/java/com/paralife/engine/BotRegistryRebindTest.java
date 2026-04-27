package com.paralife.engine;

import com.paralife.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link BotRegistry#rebindSession} (Phase 17 D-13).
 *
 * Verifies the session-swap semantics required by the STALLED FSM:
 * - New session gets the entity; old session is gone.
 * - Unknown entity returns false (no-op).
 * - Collision with a different entity throws IllegalStateException.
 * - Self-rebind (same session) is a consistent no-op.
 */
class BotRegistryRebindTest {

    private BotRegistry registry;

    @BeforeEach
    void setup() {
        registry = new BotRegistry();
    }

    private void registerEntity(String sessionId, String entityId) {
        registry.register(sessionId, entityId, new Position(1, 1));
    }

    @Test
    void rebindSwapsSessionIdPreservingEntity() {
        registerEntity("s-old", "e-1");
        boolean ok = registry.rebindSession("s-new", "e-1");

        assertThat(ok).isTrue();
        assertThat(registry.getBySession("s-new")).isPresent();
        assertThat(registry.getBySession("s-new").get().entityId()).isEqualTo("e-1");
        assertThat(registry.getBySession("s-old")).isEmpty();
    }

    @Test
    void rebindUpdatesReverseEntityToSessionMapping() {
        registerEntity("s-old", "e-1");
        registry.rebindSession("s-new", "e-1");

        assertThat(registry.getSessionForEntity("e-1")).contains("s-new");
    }

    @Test
    void rebindUnknownEntityReturnsFalse() {
        boolean ok = registry.rebindSession("s-new", "unknown-entity");
        assertThat(ok).isFalse();
    }

    @Test
    void rebindRefusesCollisionWithDifferentEntity() {
        registerEntity("s-1", "e-1");
        registerEntity("s-2", "e-2");

        // s-2 is bound to e-2; trying to rebind e-1 to s-2 is a collision
        assertThatThrownBy(() -> registry.rebindSession("s-2", "e-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound to entity=e-2");
    }

    @Test
    void rebindIsIdempotentForSameSessionAndEntity() {
        registerEntity("s-1", "e-1");

        // Re-bind to the SAME session id — consistent state preserved
        boolean ok = registry.rebindSession("s-1", "e-1");
        assertThat(ok).isTrue();
        assertThat(registry.getBySession("s-1")).isPresent();
        assertThat(registry.getBySession("s-1").get().entityId()).isEqualTo("e-1");
        assertThat(registry.getSessionForEntity("e-1")).contains("s-1");
    }

    @Test
    void rebindPreservesEntityPosition() {
        registry.register("s-old", "e-1", new Position(7, 3));
        registry.rebindSession("s-new", "e-1");

        assertThat(registry.getBySession("s-new").get().position()).isEqualTo(new Position(7, 3));
    }

    @Test
    void rebindDoesNotAffectOtherRegisteredEntities() {
        registerEntity("s-1", "e-1");
        registerEntity("s-2", "e-2");

        registry.rebindSession("s-3", "e-1");

        // e-2 / s-2 untouched
        assertThat(registry.getBySession("s-2")).isPresent();
        assertThat(registry.getBySession("s-2").get().entityId()).isEqualTo("e-2");
        assertThat(registry.getSessionForEntity("e-2")).contains("s-2");
    }
}
