package com.paralife.admission;

/**
 * Stable machine-readable rejection-token vocabulary emitted in
 * {@code Frame.ErrorFrame} messages per Phase 17 D-07.
 *
 * <p>See {@code docs/ADMISSION.md}
 * for the full taxonomy, HTTP-style code mapping, and on-wire shape.
 */
public final class RejectionToken {

    private RejectionToken() {}

    /** 400 — Codec / parse failure. */
    public static final String MALFORMED           = "malformed";

    /** 404 — Action frame on Unregistered session. */
    public static final String NO_ACTIVE_ENTITY    = "no-active-entity";

    /** 408 — Session was STALLED; client must drop and reconnect. */
    public static final String RECONNECT_REQUIRED  = "reconnect-required";

    /** 409 — Second {@code r|} frame while session is Alive. */
    public static final String ALREADY_REGISTERED  = "already-registered";

    /** 429 — Global admission cap reached (D-01). */
    public static final String WORLD_FULL          = "world-full";

    /** 429 — Per-session respawn cap reached. */
    public static final String RESPAWN_CAP         = "respawn-cap";

    /** 429 — Tick-health admission gate firing (D-14). */
    public static final String TICK_OVERLOAD       = "tick-overload";

    /** 429 — Operator maintenance flag set (D-16). */
    public static final String MAINTENANCE         = "maintenance";

    /** 503 — Placement RNG exhausted {@code MAX_PLACEMENT_ATTEMPTS}. */
    public static final String GRID_FULL           = "grid-full";
}
