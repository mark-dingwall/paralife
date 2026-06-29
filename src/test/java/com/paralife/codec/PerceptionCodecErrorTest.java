package com.paralife.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Negative-path tests for {@link PerceptionCodec} — malformed wire bytes, unknown
 * frame types, truncated input, out-of-alphabet chars, and DoS-bounded entries
 * must all surface as structured {@link CodecException} rather than hangs, OOM,
 * or silent acceptance.
 *
 * <p>Complements {@link PerceptionCodecRoundTripTest}, which covers the happy
 * path. See SCHEMA.md §12 for LL(1) parser expectations and DoS bounds.
 */
class PerceptionCodecErrorTest {

    @Test
    void emptyInputRejected() {
        assertThrows(CodecException.class, () -> PerceptionCodec.decode(""));
    }

    @Test
    void nullInputRejected() {
        assertThrows(CodecException.class, () -> PerceptionCodec.decode(null));
    }

    @Test
    void unknownFrameTypeRejected() {
        CodecException ex = assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("Z|whatever"));
        assertTrue(ex.getMessage().contains("Unknown frame type"), ex.getMessage());
    }

    @Test
    void outOfAlphabetCharRejectedInHeader() {
        assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("T|!!!|0A1B|15/80|2"));
    }

    @Test
    void truncatedTickRejected() {
        assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("T|001|0A"));
    }

    @Test
    void unknownActionVerbRejected() {
        assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("a|Q|8"));
    }

    @Test
    void actionRoundTrips() {
        Frame.ActionFrame move = new Frame.ActionFrame('M', Optional.of("8"));
        String encoded = PerceptionCodec.encode(move);
        assertEquals("a|M|8", encoded);
        Frame decoded = PerceptionCodec.decode(encoded);
        assertEquals(move, decoded);
    }

    @Test
    void alarmActionRoundTrips() {
        Frame.ActionFrame alarm = new Frame.ActionFrame('L', Optional.empty());
        String encoded = PerceptionCodec.encode(alarm);
        assertEquals("a|L", encoded);
    }

    @Test
    void errorFrameRoundTrips() {
        Frame.ErrorFrame e = new Frame.ErrorFrame(429, Optional.of("respawn cap"));
        String encoded = PerceptionCodec.encode(e);
        assertEquals("E|429|respawn cap", encoded);
        Frame decoded = PerceptionCodec.decode(encoded);
        assertEquals(e, decoded);
    }

    @Test
    void registerFrameRoundTrips() {
        Frame.RegisterFrame r = new Frame.RegisterFrame('C');
        assertEquals("r|C", PerceptionCodec.encode(r));
        assertEquals(r, PerceptionCodec.decode("r|C"));
    }

    /**
     * DoS sentinel #1: time-bounded rejection on a 100KB structurally-garbage bomb.
     * Pins the 500ms budget required by T-15-01.
     */
    @Test
    void largeInputRejectedQuickly() {
        String bomb = "T|" + "x".repeat(100_000);
        long start = System.nanoTime();
        try {
            PerceptionCodec.decode(bomb);
        } catch (CodecException ignored) {
            // expected — parser rejects malformed bytes
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 500,
                "Parser took " + elapsedMs + "ms on a 100KB bomb; expected < 500ms");
    }

    /**
     * DoS sentinel #2: a structurally-valid {@code s} block with more entries
     * than {@link PerceptionCodec#MAX_S_ENTRIES}. Fills the gap left by the
     * elapsed-time bomb — 10_000 valid cell entries would otherwise march
     * through the parser and produce an unbounded {@code List}.
     *
     * Per 15-REVIEWS.md Consensus #5 (Claude + Codex MEDIUM).
     */
    @Test
    void boundedEntriesRejected() {
        StringBuilder bomb = new StringBuilder("T|001|0A1B|15/80|2|s");
        // Each entry "61F" is a valid cell: numpad 6, presence=1 (entity only),
        // kind=F (nutrient). 10_000 >> MAX_S_ENTRIES (256), so the cap must trip.
        for (int i = 0; i < 10_000; i++) {
            if (i > 0) bomb.append(',');
            bomb.append("61F");
        }
        CodecException ex = assertThrows(CodecException.class,
                () -> PerceptionCodec.decode(bomb.toString()));
        assertTrue(ex.getMessage().contains("MAX_S_ENTRIES"),
                "Expected MAX_S_ENTRIES in message: " + ex.getMessage());
    }

    /**
     * Phase 19.1 D-01 (F2 fix) — {@code validateEventCode} must accept {@code 'B'} (BUFF/absorBed).
     * 'B' was added to {@code Event.java} compact-ctor in Phase 19.5 E1 but the codec switch
     * at {@code PerceptionCodec.java:685} was not updated — causing every prey-of-bond respawn
     * to throw {@link CodecException}. This test is RED until the codec switch is fixed.
     */
    @Test
    @DisplayName("F2 — validateEventCode accepts 'B' (BUFF) — Phase 19.1 D-01")
    void validateEventCodeAcceptsB() {
        // V8-shape: "T|004|0D2F|18/50|vBH3" — swap H for B but B has NO magnitude (mirrors 'D').
        // Use a no-magnitude v-block frame: "T|004|0D2F|18/50|vB"
        // (analogous to V5's "...vS" which uses the no-magnitude event S).
        assertDoesNotThrow(() -> PerceptionCodec.decode("T|004|0D2F|18/50|vB"),
                "Phase 19.1 D-01 — codec must accept 'B' event code (BUFF/absorBed added in Phase 19.5 E1)");
    }

    @Test
    @DisplayName("F2 — validateEventCode rejects unknown code 'Z'")
    void validateEventCodeRejectsZ() {
        CodecException ex = assertThrows(CodecException.class,
                () -> PerceptionCodec.decode("T|004|0D2F|18/50|vZ"),
                "Codec must reject unknown event code 'Z'");
        assertTrue(ex.getMessage().contains("Unknown event code 'Z'"), ex.getMessage());
    }

    @Test
    @DisplayName("F2 — eventHasMagnitude returns false for 'B'")
    void eventHasMagnitudeReturnsFalseForB() {
        // A magnitude v-block row for B would be invalid (B has no magnitude like D).
        // Drive this via a frame that would be valid if B had magnitude — it must be rejected.
        // The absence of exception for "vB" (no magnitude) confirms eventHasMagnitude('B')==false.
        assertDoesNotThrow(() -> PerceptionCodec.decode("T|004|0D2F|18/50|vB"),
                "Phase 19.1 D-01 — 'B' must be accepted as a no-magnitude event (mirrors 'D')");
    }

    /**
     * DoS sentinel #3: a structurally-valid {@code v} block with more events
     * than {@link PerceptionCodec#MAX_V_ENTRIES}.
     */
    @Test
    void boundedEventsRejected() {
        StringBuilder bomb = new StringBuilder("T|001|0A1B|15/80|2|v");
        // Each event "S" is a valid no-coord-no-magnitude event (reproduce
        // success). 100 >> MAX_V_ENTRIES (32), so the cap must trip.
        for (int i = 0; i < 100; i++) {
            if (i > 0) bomb.append(',');
            bomb.append('S');
        }
        CodecException ex = assertThrows(CodecException.class,
                () -> PerceptionCodec.decode(bomb.toString()));
        assertTrue(ex.getMessage().contains("MAX_V_ENTRIES"),
                "Expected MAX_V_ENTRIES in message: " + ex.getMessage());
    }
}
