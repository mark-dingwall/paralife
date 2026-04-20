package com.paralife.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip acceptance oracle for PerceptionCodec.
 * Vectors lifted verbatim from 15-SCHEMA.md §10. Every vector MUST satisfy:
 *   PerceptionCodec.encode(PerceptionCodec.decode(vector)) == vector
 * byte-for-byte.
 *
 * This test is RED until plan 15-05 (codec impl) lands; then it must stay GREEN forever.
 * Adding a vector here REQUIRES adding a row in 15-SCHEMA.md §10.
 */
class PerceptionCodecRoundTripTest {

    @ParameterizedTest(name = "[V{index}] {0}")
    @MethodSource("vectors")
    @DisplayName("Vector round-trips byte-for-byte")
    void roundTripsExactly(String wireFrame) {
        Frame decoded = PerceptionCodec.decode(wireFrame);
        String reEncoded = PerceptionCodec.encode(decoded);
        assertEquals(wireFrame, reEncoded,
                "Round-trip byte mismatch for: " + wireFrame);
    }

    static Stream<String> vectors() {
        return Stream.of(
                // V1 — empty tick (solo bot, quiet)
                "T|001|0A1B|15/80|2",
                // V2 — adjacent nutrient (numpad coord)
                "T|001|0A1B|15/80|2|s61F",
                // V3 — rock RLE run (relative anchor + numpad RLE dir)
                "T|001|0A1B|15/80|2|s+4-21R62",
                // V4 — mixed-status cell (entity + env states)
                "T|001|0A1B|15/80|2|s+1+13M32",
                // V5 — state-change + event (bonded CAT; reproduced success)
                "T|001|0A1B|15/80|2|cC:7A|vS",
                // V6 — LOCOMOTOR full frame (pool + roster + vision + alarm + dmg + FLEEING)
                "T|004|0A1B|15/80|2|s61R,91F,43C1,+3-21R62,+3+33M32|fF:2E:0F03|v6H3,6N,T3|p120/200|g62,93,+0+21",
                // V7 — authority-lite FEEDER (radius-1 vision)
                "T|004|0C1E|20/60|1|s21F",
                // V8 — passive member DEFENDER minimal frame
                "T|004|0D2F|18/50|v6H3",
                // V9 — FLEEING active (effect carries abs strike; event carries rel lightning-hit)
                //      Relative coord +F-3 parses as (+15, -3): sign + base64 mag F(15), sign - base64 mag 3(3).
                //      This is the standard §2 4-char relative form — no 6-char extended coords exist.
                "T|001|0A1B|15/80|2|fF:2E:0F03|v+F-3L5",
                // V10 — resync (Sync with two active effects, no `f` prefix per SCHEMA §6.2)
                "S|7A|S:1Fg8,I:1Ef0",
                // V11 — multi-member alarm (LOCO sees two alarms). Canonical block order per §6.3.1: v before g.
                "T|005|0A1B|30/100|2|v6N,9N|g62,93,+0+21",
                // V12 — env-only cell (empty cell with toxin hazard, relative anchor)
                "T|001|0A1B|15/80|2|s+2+022",
                // V13 — RLE with per-cell env supplements (rock column of 3 south, all MUTAGEN_ZONE)
                "T|001|0A1B|15/80|2|s43R824,124,-1-124"
        );
    }
}
