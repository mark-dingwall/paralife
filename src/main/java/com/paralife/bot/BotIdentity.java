package com.paralife.bot;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Identity carried in the WebSocket handshake (Phase 18 D-06 / D-07 / D-08 / D-09 / D-11 / D-20).
 *
 * <p>Source values are constrained to the bounded taxonomy {@link #SOURCE_TAXONOMY};
 * harness ids are truncated to 32 chars and may not contain ASCII control chars including
 * CR/LF (header-injection guard, RESEARCH.md Pitfall 4 / threat T-18-01).
 *
 * <p><b>Invariants enforced in the compact constructor:</b>
 * <ul>
 *   <li>{@code source=harness} IFF {@code harnessId.isPresent()} — symmetric.</li>
 *   <li>Normalization (trim + 32-char truncate + control-char rejection) runs in the
 *       compact ctor itself, not only the {@link #harness(String)} factory.</li>
 * </ul>
 *
 * <p><b>Round 2 LOW note:</b> Plan 02 (Wave 3) extracts a shared
 * {@code AttributionSanitizer.sanitizeHarnessId(String)} helper used by both this class
 * and the server-side handshake-header path. After Plan 02 lands, the {@link #harness(String)}
 * factory may delegate to the sanitizer; the externally-observable invariants enforced
 * here are unchanged by that refactor.
 */
public record BotIdentity(String source, Optional<String> harnessId) {

    /**
     * Full source taxonomy — includes server-only reserved values.
     * Server code (e.g. attribution tagger, future D-20 producer) may set any of these.
     */
    public static final Set<String> SOURCE_TAXONOMY =
            Set.of("operator", "harness", "unknown", "overflow", "offspring");

    /**
     * Subset of {@link #SOURCE_TAXONOMY} accepted from client-supplied handshake headers.
     * {@code overflow} is a server-side cardinality fold result; {@code offspring} is reserved
     * for D-20. Allowing clients to send these would let them spoof the server-side cardinality
     * fold or pre-empt the future offspring producer.
     */
    public static final Set<String> CLIENT_ALLOWED_SOURCES =
            Set.of("operator", "harness", "unknown");
    public static final int MAX_HARNESS_ID_LENGTH = 32;

    public BotIdentity {
        Objects.requireNonNull(source, "source must not be null");
        if (!SOURCE_TAXONOMY.contains(source)) {
            throw new IllegalArgumentException(
                    "source must be in " + SOURCE_TAXONOMY + " (got '" + source + "')");
        }
        Objects.requireNonNull(harnessId, "harnessId Optional must not be null");

        // Symmetric invariant: source=harness IFF harnessId.isPresent().
        boolean hasId = harnessId.isPresent();
        boolean isHarnessSource = "harness".equals(source);
        if (isHarnessSource != hasId) {
            throw new IllegalArgumentException(
                    "source=harness IFF harnessId.isPresent(); got source='" + source
                            + "', harnessId=" + (hasId ? "present" : "empty"));
        }

        // Normalize on every construction path. Delegates to AttributionSanitizer so client-side
        // and server-side use the same regex (^[A-Za-z0-9-]{1,32}$).
        if (hasId) {
            String raw = harnessId.get();
            harnessId = com.paralife.admission.AttributionSanitizer.sanitizeHarnessId(raw)
                    .map(Optional::of)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "harnessId must match ^[A-Za-z0-9-]{1,32}$ (got '" + raw + "')"));
        }
    }

    public static BotIdentity operator() {
        return new BotIdentity("operator", Optional.empty());
    }

    public static BotIdentity harness(String harnessId) {
        if (harnessId == null || harnessId.isBlank()) {
            throw new IllegalArgumentException("harnessId required for source=harness");
        }
        return new BotIdentity("harness", Optional.of(harnessId));
    }

    public static BotIdentity unknown() {
        return new BotIdentity("unknown", Optional.empty());
    }
}
