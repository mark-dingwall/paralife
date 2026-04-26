package com.paralife.admission;

/**
 * Outcome of {@link AdmissionGate#evaluate} (Phase 17).
 *
 * <p>Three permits:
 * <ul>
 *   <li>{@link Allow} — admission granted; proceed with fresh registration.</li>
 *   <li>{@link Reject} — admission denied; send {@code E|<code>|<token>} to client.</li>
 *   <li>{@link Rebind} — valid resume token; rebind existing entity, issue fresh token.</li>
 * </ul>
 */
public sealed interface AdmissionResult permits AdmissionResult.Allow,
                                                AdmissionResult.Reject,
                                                AdmissionResult.Rebind {

    /**
     * Admission granted. Singleton to avoid allocation on the hot path.
     */
    final class Allow implements AdmissionResult {
        public static final Allow INSTANCE = new Allow();
        private Allow() {}
    }

    /**
     * Admission denied. Send {@code E|<code>|<token>} to the client.
     *
     * @param code  HTTP-style status code (100–999).
     * @param token Machine-readable rejection token from {@link RejectionToken}.
     */
    record Reject(int code, String token) implements AdmissionResult {
        public Reject {
            if (code < 100 || code > 999) throw new IllegalArgumentException("code 100..999, got " + code);
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token required");
        }
    }

    /**
     * Valid resume token found — rebind existing entity to the new session.
     * Send {@code S|<entityId>|<freshResumeToken>} to the client.
     *
     * @param entityId        The existing entity's ID to rebind.
     * @param freshResumeToken A newly minted ACTIVE token for the rebound session.
     */
    record Rebind(String entityId, String freshResumeToken) implements AdmissionResult {
        public Rebind {
            if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("entityId required");
            if (freshResumeToken == null || freshResumeToken.isBlank()) throw new IllegalArgumentException("token required");
        }
    }
}
