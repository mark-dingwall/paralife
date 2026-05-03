package com.paralife.engine;

/**
 * Phase 19.5 H-A — entity-id remap callback hook for {@link SimulationEngine}.
 *
 * <p>Fires whenever an entity changes identity on the grid while keeping the
 * same controlling session — bond formation, composite formation, composite
 * revert (last member → BondedPair), and composite dissolution (member →
 * Particle). Decouples the engine from {@code WorldWebSocketHandler} and
 * {@code ResumeTokenRegistry}: the engine performs the {@link BotRegistry}
 * remap, then fires this single callback so the WS layer updates
 * {@code ATTR_ENTITY_ID} and the resume registry rewrites entries keyed by
 * {@code oldEntityId} for any STALLED grace token.
 *
 * <p><b>WS:entity 1:1 invariant (CLAUDE.md Phase 18 D-05/D-21):</b> at most one
 * session controls an entity. Remap events transfer that control across
 * identity changes; no session-multiplexing implied.
 *
 * <p>Setter-injected on {@code SimulationEngine}; pre-Phase-19.5 tests that
 * construct the engine directly see {@code null} and the engine no-ops.
 */
public interface EntityLifecycleListener {

    /**
     * Invoked synchronously inside the tick thread, immediately after
     * {@code SimulationEngine} has remapped {@link BotRegistry} from
     * {@code oldEntityId} to {@code newEntityId} for {@code sessionId}.
     *
     * <p>Implementations should:
     * <ol>
     *   <li>Locate the session by {@code sessionId} and update its
     *       {@code ATTR_ENTITY_ID} attribute to {@code newEntityId}.</li>
     *   <li>Rewrite any STALLED/ACTIVE resume-token entries keyed by
     *       {@code oldEntityId} so reconnect flows resolve to the new id.</li>
     * </ol>
     *
     * <p>If the session is no longer present (already disconnected before the
     * remap fired), implementations should treat this as a no-op — the
     * registry remap is still correct.
     */
    void onEntityRemapped(String sessionId, String oldEntityId, String newEntityId);
}
