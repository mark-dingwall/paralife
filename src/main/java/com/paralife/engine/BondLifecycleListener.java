package com.paralife.engine;

/**
 * Phase 19.5 H2 — callback hook for {@link SimulationEngine} bond-formation events.
 *
 * <p>Decouples the engine from {@code WorldWebSocketHandler}: when two particles
 * fuse into a {@code BondedPair}, the engine needs to update the predator's
 * session-attribute {@code ATTR_ENTITY_ID} so subsequent
 * {@code cleanupBot(session)} reaches the right registry entry. SimulationEngine
 * itself MUST NOT know about {@code WebSocketSession} types — instead it fires
 * this single callback, which the WS layer registers as the implementer.
 *
 * <p><b>WS:entity 1:1 invariant (CLAUDE.md Phase 18 D-05/D-21):</b> the BondedPair
 * is one entity controlled by exactly one session — the predator's surviving
 * session. The prey's session is unregistered/cleaned at bond formation.
 *
 * <p>Setter-injected on {@code SimulationEngine}; pre-Phase-19.5 tests that
 * construct the engine directly see {@code null} and the engine no-ops.
 */
public interface BondLifecycleListener {

    /**
     * Invoked synchronously inside the tick thread, immediately after
     * {@code SimulationEngine} has remapped {@link BotRegistry} for the
     * predator's session (predator entityId → bondedPair.id()). Implementations
     * should locate the session by {@code predatorSessionId} and update its
     * {@code ATTR_ENTITY_ID} attribute to {@code bondedPairId}.
     *
     * <p>If the predator's session is no longer present (already disconnected
     * before the bond formed), implementations should treat this as a no-op —
     * the registry remap is still correct.
     */
    void onBondFormed(String predatorSessionId, String bondedPairId);
}
