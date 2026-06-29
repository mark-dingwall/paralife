package com.paralife.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Round-trip acceptance oracle for PerceptionCodec.
 * Vectors lifted verbatim from SCHEMA.md §10. Every vector MUST satisfy:
 *   PerceptionCodec.encode(PerceptionCodec.decode(vector)) == vector
 * byte-for-byte.
 *
 * This test is RED until plan 15-05 (codec impl) lands; then it must stay GREEN forever.
 * Adding a vector here REQUIRES adding a row in SCHEMA.md §10.
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

    /**
     * Phase 19.1 D-15 — every code declared in {@link Event#ALL_CODES} must
     * round-trip through {@link PerceptionCodec} byte-for-byte.
     *
     * <p>Structural defence: driven from {@code Event.ALL_CODES} (not a @ValueSource
     * copy) — adding a new code to {@code ALL_CODES} automatically adds a test case.
     * If the codec switch ({@code validateEventCode}) is not updated in lockstep,
     * this test fails. The dual-list maintenance trap that caused F2 is physically
     * impossible.
     *
     * <p>Pinned reference vectors used by {@link #buildMinimalEventFrame(char)}:
     * <ul>
     *   <li>Magnitude path (E,A,H,T,M,R,L): {@code V8 = "T|004|0D2F|18/50|v6H3"}
     *       — coord=6 (numpad), code=H, magnitude=3. Substitute code char.</li>
     *   <li>No-magnitude path (N,S,D,B): {@code "T|004|0D2F|18/50|vS"} — no coord,
     *       no magnitude. Substitute code char. (B mirrors D — no coord, no magnitude
     *       per Phase 19.5 E1 and Phase 19.1 D-01 confirmation.)</li>
     * </ul>
     */
    @ParameterizedTest(name = "[CODE {0}] event code round-trips")
    @MethodSource("allEventCodes")
    @DisplayName("Phase 19.1 D-15 — every Event.ALL_CODES code round-trips through PerceptionCodec")
    void everyEventCodeRoundTrips(char code) {
        String wireFrame = buildMinimalEventFrame(code);
        Frame decoded = PerceptionCodec.decode(wireFrame);
        String reEncoded = PerceptionCodec.encode(decoded);
        assertEquals(wireFrame, reEncoded,
                "Phase 19.1 D-15 — codec must round-trip every code declared in Event.ALL_CODES");
    }

    /**
     * Provider for {@link #everyEventCodeRoundTrips} — iterates {@code Event.ALL_CODES}.
     * Adding a code to {@code ALL_CODES} automatically adds a test case.
     */
    static Stream<Character> allEventCodes() {
        List<Character> list = new ArrayList<>();
        for (char c : Event.ALL_CODES.toCharArray()) list.add(c);
        return list.stream();
    }

    /**
     * C1.1 — minimal viable wire frame containing exactly one event of the given code.
     *
     * <p>Two-branch helper verified against {@code PerceptionCodec.eventHasMagnitude}:
     * <ul>
     *   <li>Magnitude codes (E,A,H,T,M,R,L): use V8-shape {@code "T|004|0D2F|18/50|v6Xn"}
     *       where X is the code, n=3 is the magnitude digit.</li>
     *   <li>No-magnitude codes (N,S,D,B): use {@code "T|004|0D2F|18/50|vX"}
     *       where X is the code.</li>
     * </ul>
     */
    private static String buildMinimalEventFrame(char code) {
        // D3-M4: B is no-magnitude (confirmed by Phase 19.1 D-01 fix to eventHasMagnitude).
        // Phase 19.1 follow-up — driven from Event.MAG_CODES so adding a MAG code there
        // automatically routes the round-trip through the magnitude-frame branch.
        boolean needsMagnitude = Event.MAG_CODES.indexOf(code) >= 0;
        if (needsMagnitude) {
            // Magnitude path: pinned from V8 = "T|004|0D2F|18/50|v6H3"
            // Substitute code char at position of 'H'; coord=6, magnitude=3 constant.
            return "T|004|0D2F|18/50|v6" + code + "3";
        } else {
            // No-magnitude path: no coord, no magnitude — simplest valid event form.
            return "T|004|0D2F|18/50|v" + code;
        }
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
