package com.paralife.websocket;

import com.paralife.admission.ResumeTokenRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 19.1 F3 regression test — {@code WorldWebSocketHandler.markDead} must
 * clean up the resume token (call {@code ResumeTokenRegistry.clearActive} and
 * remove {@code ATTR_RESUME_TOKEN}) to prevent token leak on solo death.
 *
 * <p>F3 bug: the pre-fix {@code markDead} only removed {@code ATTR_ENTITY_ID}:
 * <pre>{@code
 *     public void markDead(WebSocketSession session) {
 *         if (session == null) return;
 *         session.getAttributes().remove(ATTR_ENTITY_ID);
 *         // BUG: no resumeTokenRegistry.clearActive, no ATTR_RESUME_TOKEN removal
 *     }
 * }</pre>
 *
 * <p>Fix: mirror {@code cleanupBot:847-858} — read entityId before remove, also
 * remove {@code ATTR_RESUME_TOKEN}, and call {@code resumeTokenRegistry.clearActive(entityId)}.
 *
 * <p>Test is a pure unit test — no Spring scaffolding. A Mockito mock for
 * {@code ResumeTokenRegistry} allows verification of {@code clearActive} calls.
 * A Mockito mock for {@code WebSocketSession} returns a real {@link HashMap}
 * for attribute inspection.
 *
 * <p>Analog: {@code BotRegistryRebindTest} for the registry-assertion shape;
 * {@code WorldWebSocketHandlerTest} for the session-stub pattern.
 */
class WorldWebSocketHandlerMarkDeadTest {

    // Attribute key constants — mirror the private constants in WorldWebSocketHandler.
    private static final String ATTR_ENTITY_ID    = "entityId";
    private static final String ATTR_RESUME_TOKEN = "resumeToken";

    private ResumeTokenRegistry resumeTokenRegistry;
    private WorldWebSocketHandler handler;
    private Map<String, Object> attrs;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        resumeTokenRegistry = mock(ResumeTokenRegistry.class);

        // Use the 7-arg back-compat ctor (no Spring) to get a minimal handler;
        // then inject resumeTokenRegistry via the field accessible in this package.
        // Since that ctor passes null for resumeTokenRegistry, we construct a
        // real handler via a minimal test subclass or reflective field injection.
        // Instead: use the @SpringBootTest-independent approach — construct via
        // the existing minimal-dependency utility available in this package's
        // package-access test infrastructure.
        //
        // Practical approach: @SpringBootTest(webEnvironment=NONE) is avoided to
        // keep this test free of Spring overhead. We use Mockito to supply all
        // required constructor args as mocks (the markDead code path only touches
        // session attributes and resumeTokenRegistry).
        handler = buildMinimalHandler(resumeTokenRegistry);

        attrs = new HashMap<>();
        session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("test-session-id");
    }

    @Test
    @DisplayName("F3 — markDead calls clearActive when ATTR_ENTITY_ID is set (Phase 19.1 D-03)")
    void markDeadCallsClearActiveOnResumeTokenRegistry() {
        attrs.put(ATTR_ENTITY_ID, "entity-1");
        attrs.put(ATTR_RESUME_TOKEN, "r:0000000000000001");

        handler.markDead(session);

        // F3 fix: clearActive must be called with the entity id.
        verify(resumeTokenRegistry).clearActive(eq("entity-1"));
    }

    @Test
    @DisplayName("F3 — markDead removes ATTR_RESUME_TOKEN from session attributes (Phase 19.1 D-03)")
    void markDeadRemovesResumeTokenAttribute() {
        attrs.put(ATTR_ENTITY_ID, "entity-2");
        attrs.put(ATTR_RESUME_TOKEN, "r:0000000000000002");

        handler.markDead(session);

        // F3 fix: ATTR_RESUME_TOKEN must be removed from session attributes.
        assertThat(attrs).doesNotContainKey(ATTR_RESUME_TOKEN);
    }

    @Test
    @DisplayName("F3 — markDead removes ATTR_ENTITY_ID from session attributes (existing behaviour)")
    void markDeadRemovesEntityIdAttribute() {
        attrs.put(ATTR_ENTITY_ID, "entity-3");

        handler.markDead(session);

        // Existing behaviour preserved.
        assertThat(attrs).doesNotContainKey(ATTR_ENTITY_ID);
    }

    @Test
    @DisplayName("F3 — markDead(null) is a no-op (no NPE)")
    void markDeadNullSessionIsNoOp() {
        assertThatNoException()
                .as("Phase 19.1 D-03 — markDead(null) must not throw")
                .isThrownBy(() -> handler.markDead(null));
    }

    @Test
    @DisplayName("F3 — markDead does not call clearActive when no entityId is set")
    void markDeadDoesNotCallClearActiveWhenNoEntityId() {
        // No ATTR_ENTITY_ID in attrs — clearActive must not be called.
        attrs.put(ATTR_RESUME_TOKEN, "r:0000000000000003");

        handler.markDead(session);

        verify(resumeTokenRegistry, never()).clearActive(Mockito.any());
        assertThat(attrs).doesNotContainKey(ATTR_RESUME_TOKEN);
    }

    /**
     * Construct a minimal {@link WorldWebSocketHandler} with only {@code resumeTokenRegistry}
     * wired. All other dependencies are Mockito mocks. The {@code markDead} code path
     * only reads {@code session.getAttributes()} and calls {@code resumeTokenRegistry.clearActive} —
     * so only these two matter; all other field accesses would throw NPE if exercised (they are not).
     *
     * <p>Uses the existing 15-arg primary constructor, passing Mockito mocks for the
     * required non-null args and {@code null} for optional ones. The {@code resumeTokenRegistry}
     * argument uses our Mockito mock so we can verify calls to it.
     */
    private static WorldWebSocketHandler buildMinimalHandler(ResumeTokenRegistry registry) {
        // Import all required types.
        var sessionRegistry    = mock(com.paralife.websocket.SessionRegistry.class);
        var worldGrid          = mock(com.paralife.world.WorldGrid.class);
        var tickEngine         = mock(com.paralife.engine.TickEngine.class);
        var botRegistry        = mock(com.paralife.engine.BotRegistry.class);
        var actionResolver     = mock(com.paralife.engine.ActionResolver.class);
        var metabolicProfile   = mock(com.paralife.engine.MetabolicProfile.class);
        var spawnConfig        = com.paralife.engine.SpawnConfig.defaults();
        var respawnConfig      = com.paralife.websocket.RespawnConfig.defaults();
        var admissionGate      = mock(com.paralife.admission.AdmissionGate.class);
        var outboundSender     = mock(com.paralife.admission.OutboundSender.class);
        var admissionConfig    = com.paralife.admission.AdmissionConfig.defaults();
        var admissionMetrics   = mock(com.paralife.admission.AdmissionMetrics.class);
        var eligibleCellIndex  = mock(com.paralife.engine.EligibleCellIndex.class);
        var liveEntityRegistry = mock(com.paralife.engine.LiveEntityRegistry.class);

        // WorldGrid.getDimension() may be called during construction (buildRng via SpawnConfig).
        // SpawnConfig.defaults() uses null seed, so no dimension needed.
        // RespawnConfig.defaults() is safe.

        return new WorldWebSocketHandler(
                sessionRegistry, worldGrid, tickEngine, botRegistry, actionResolver,
                metabolicProfile, spawnConfig, respawnConfig,
                admissionGate, outboundSender,
                registry,                 // ← our mock — the one field markDead uses
                admissionConfig, admissionMetrics,
                eligibleCellIndex, liveEntityRegistry);
    }
}
