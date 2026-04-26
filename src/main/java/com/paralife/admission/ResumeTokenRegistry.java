package com.paralife.admission;

import java.util.Optional;

/**
 * Token registry for stalled-session recovery (Phase 17 D-12 / D-13).
 *
 * <p>Two-state lifecycle (codex/opencode HIGH review):
 * <ul>
 *   <li>ACTIVE — issued on successful registration; no expiry; not counted in stalled gauge.</li>
 *   <li>STALLED — transitioned on stall event; expires at {@code currentTick + graceWindowTicks};
 *       counted in stalled-sessions gauge.</li>
 * </ul>
 *
 * <p>Full implementation in Plan 05. This stub satisfies the compile-time dependency
 * for Plan 03 ({@link AdmissionGate}); Plan 05 replaces it with the full
 * {@code @Component} + {@code @EventListener @Order(1)} implementation.
 *
 * <p>Token format: {@code r:%016x} (18 chars) — locked by Plan 02 codec disambiguator.
 *
 * @see AdmissionGate
 */
public class ResumeTokenRegistry {

    /**
     * Attempt to rebind a STALLED token.
     *
     * <p>If the token is present and not yet expired ({@code currentTick <= expiresAtTick}):
     * consumes the entry, mints a fresh ACTIVE token, and returns the outcome.
     * If missing or expired: returns empty (caller falls through to fresh registration).
     *
     * @param token        The opaque resume token from the {@code r|<type>|<token>} frame.
     * @param newSessionId The incoming session's ID.
     * @param currentTick  Current simulation tick number.
     * @return Present if rebind succeeded; empty if token unknown or expired.
     */
    public Optional<RebindOutcome> tryRebind(String token, String newSessionId, long currentTick) {
        return Optional.empty();
    }

    /**
     * Outcome of a successful rebind.
     *
     * @param entityId         The existing entity ID rebound to the new session.
     * @param freshResumeToken A newly minted ACTIVE token for the rebound session.
     */
    public record RebindOutcome(String entityId, String freshResumeToken) {}
}
