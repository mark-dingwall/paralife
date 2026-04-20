package com.paralife.codec;

/**
 * Shared encode/decode per 15-SCHEMA.md. Pure static; no hidden state (D-41).
 *
 * <p>STUB: implementation lands in plan 15-05. The 13 round-trip vectors in
 * SCHEMA §10 are the acceptance oracle; PerceptionCodecRoundTripTest must go
 * RED initially and GREEN after plan 15-05.
 *
 * <h2>DoS bounds (15-SCHEMA.md §12)</h2>
 * {@link #MAX_S_ENTRIES} and {@link #MAX_V_ENTRIES} bound the decoder's
 * per-frame list allocation. Exceeding either during decode is a wire-protocol
 * violation and throws {@link CodecException} (mapped by the handler to E|400).
 * These bounds are public so tests and static analysis can reference them.
 */
public final class PerceptionCodec {

    /**
     * Maximum cell entries per `s` block. Structurally-valid frames with more
     * than this many entries are rejected on decode. 256 comfortably covers
     * a 7×7 sensor radius (49 cells) plus env supplements with generous slack.
     */
    public static final int MAX_S_ENTRIES = 256;

    /**
     * Maximum event entries per `v` block. 32 covers the worst-case LOCOMOTOR
     * tick (multi-member alarms + own events) with slack for future events.
     */
    public static final int MAX_V_ENTRIES = 32;

    private PerceptionCodec() {
        // utility — not instantiable
    }

    public static String encode(Frame f) {
        throw new UnsupportedOperationException("PerceptionCodec.encode pending plan 15-05");
    }

    public static Frame decode(String s) {
        throw new UnsupportedOperationException("PerceptionCodec.decode pending plan 15-05");
    }
}
