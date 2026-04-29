package com.paralife.admission;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Single source of truth for harness-id normalization (Phase 18 Round 2 Codex HIGH).
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link com.paralife.bot.BotIdentity} — client-side validation in compact ctor</li>
 *   <li>{@link com.paralife.websocket.WorldWebSocketHandler#afterConnectionEstablished(org.springframework.web.socket.WebSocketSession)}
 *       — server-side defense-in-depth against untrusted handshake input (T-18-01)</li>
 * </ul>
 *
 * <p>Without this shared helper, an ad-hoc client could send a CR-injected or oversize
 * harness id that bypasses BotIdentity's compact-ctor validation, polluting server-side
 * logs and metric tags. The server cannot trust the client.
 */
public final class AttributionSanitizer {

    /** Maximum length for a harness id (matches {@link com.paralife.bot.BotIdentity#MAX_HARNESS_ID_LENGTH}). */
    public static final int MAX_HARNESS_ID_LENGTH = 32;

    /**
     * Allowlist regex per {@code 18-HARNESS.md §2}: {@code ^[A-Za-z0-9-]{1,32}$}.
     * Spaces, {@code =}, {@code /}, {@code _}, non-ASCII chars are rejected — a tight
     * superset of the original control-char-only guard (P18-Chunk-A remediation MEDIUM).
     */
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private AttributionSanitizer() {}

    /**
     * Sanitize a raw harness-id value from an untrusted source (e.g. WS upgrade header).
     *
     * <p>Rules applied in order:
     * <ol>
     *   <li>Null input → {@link Optional#empty()}.</li>
     *   <li>Trim whitespace. Blank after trim → {@link Optional#empty()}.</li>
     *   <li>Trimmed value must match {@code ^[A-Za-z0-9-]{1,32}$} — anything else
     *       (spaces, {@code =}, {@code /}, {@code _}, non-ASCII, control chars including
     *       CR/LF, oversize) → {@link Optional#empty()}.</li>
     * </ol>
     *
     * @param raw the untrusted handshake header value (or client-supplied id)
     * @return {@link Optional#empty()} for null/blank/non-conformant input;
     *         {@link Optional#of(Object)} with the trimmed value otherwise.
     */
    public static Optional<String> sanitizeHarnessId(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        if (!ALLOWED.matcher(trimmed).matches()) return Optional.empty();
        return Optional.of(trimmed);
    }
}
