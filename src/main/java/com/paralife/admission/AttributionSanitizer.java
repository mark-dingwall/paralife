package com.paralife.admission;

import java.util.Optional;

/**
 * Single source of truth for harness-id normalization (Phase 18 Round 2 Codex HIGH).
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link com.paralife.bot.BotIdentity#harness(String)} — client-side validation</li>
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

    private AttributionSanitizer() {}

    /**
     * Sanitize a raw harness-id value from an untrusted source (e.g. WS upgrade header).
     *
     * <p>Rules applied in order:
     * <ol>
     *   <li>Null input → {@link Optional#empty()}.</li>
     *   <li>Trim whitespace. Blank after trim → {@link Optional#empty()}.</li>
     *   <li>Any ASCII control character (0x00-0x1F, 0x7F) present → {@link Optional#empty()}.
     *       This covers CR, LF, tab, NUL, DEL — the full header-injection guard surface.</li>
     *   <li>Truncate to {@link #MAX_HARNESS_ID_LENGTH} characters.</li>
     * </ol>
     *
     * @param raw the untrusted handshake header value (or client-supplied id)
     * @return {@link Optional#empty()} for null, blank, or control-char-containing input;
     *         {@link Optional#of(Object)} with the sanitized (trimmed, truncated) value otherwise.
     */
    public static Optional<String> sanitizeHarnessId(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        // Reject ANY ASCII control char (broader than CR/LF only — a header-injection guard).
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return Optional.empty();
            }
        }
        String truncated = trimmed.length() > MAX_HARNESS_ID_LENGTH
                ? trimmed.substring(0, MAX_HARNESS_ID_LENGTH)
                : trimmed;
        return Optional.of(truncated);
    }
}
