package com.paralife.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Encode-isolating contract pins for SCHEMA §0 R4/R5/R6 — the emission-side clauses
 * ("WHEN emitting … THE SYSTEM SHALL …").
 *
 * <p>{@link PerceptionCodecRoundTripTest#roundTripsExactly} is a strong <em>joint</em>
 * gate but not clause-isolating: it only exercises frames obtained by <em>decoding</em>
 * a wire vector, so it never sees the encode of a server-built frame shape absent from
 * the vector set. Each test here builds a {@link Frame} directly from independent
 * literals, encodes it, and asserts the ONE structural property its clause names — so a
 * failure names the clause, not "some byte differs". {@code roundTripsExactly} stays the
 * joint backstop.
 *
 * <p>RED-tested 2026-07-07: each assertion was shown to fire on a targeted encoder
 * perturbation (';' entry separator; s-block code-first; permuted block order). See the
 * slice PR scope-diff for which of these round-trip also caught vs. caught here alone.
 */
class PerceptionCodecEncodeContractTest {

    /** A CellEntry with a numpad coord and a simple kind code — the minimal s-block entry. */
    private static CellEntry cell(char coord, char kind) {
        return new CellEntry(new Coord.Numpad(coord), 1,
                Optional.of(new KindData.Simple(kind)), OptionalInt.empty(), OptionalInt.empty());
    }

    /** A FLEEING effect carrying an abs strike coord in the trailing ctx slot — a type-block entry. */
    private static ActiveEffect fleeingEffect() {
        return new ActiveEffect('F', 46L, Optional.of(new int[] {15, 3}));
    }

    /**
     * The wire block segment whose first char is {@code prefix}. Splits on {@code |} and
     * skips the five positional header segments (T, tickId, xy, energy/max, radius), so a
     * base64 header token can never masquerade as a block.
     */
    private static String blockSegment(String wire, char prefix) {
        String[] seg = wire.split("\\|");
        for (int i = 5; i < seg.length; i++) {
            if (!seg[i].isEmpty() && seg[i].charAt(0) == prefix) return seg[i];
        }
        throw new AssertionError("no block segment '" + prefix + "' in: " + wire);
    }

    @Test
    @DisplayName("R4 — encode separates entries with ',' and intra-entry structure with ':' and never emits ';' (§3)")
    void encodeUsesCommaBetweenEntriesColonIntraEntryNeverSemicolon() {
        // Two s-cells force an entry separator; the f-effect forces intra-entry ':'.
        Frame.TickFrame f = new Frame.TickFrame(1L, 10, 11, 21, 128, 2,
                List.of(cell('6', 'F'), cell('7', 'M')),
                Optional.empty(),
                List.of(fleeingEffect()),
                List.of(), Optional.empty(), List.of());

        String wire = PerceptionCodec.encode(f);

        assertThat(blockSegment(wire, 's')).as("',' between s entries").contains(",");
        assertThat(blockSegment(wire, 'f')).as("':' intra-entry in f effect").contains(":");
        assertThat(wire).as("';' never appears anywhere (§3)").doesNotContain(";");
    }

    @Test
    @DisplayName("R5 — encode puts coord first in a spatial block (s) and code first in a type block (f) (§4)")
    void encodePlacesCoordFirstInSpatialBlockCodeFirstInTypeBlock() {
        Frame.TickFrame f = new Frame.TickFrame(1L, 10, 11, 21, 128, 2,
                List.of(cell('6', 'F')),              // spatial: coord-first
                Optional.empty(),
                List.of(fleeingEffect()),             // type: code-first, ctx coord trailing
                List.of(), Optional.empty(), List.of());

        String wire = PerceptionCodec.encode(f);
        String sEntry = blockSegment(wire, 's').substring(1);   // drop 's' prefix → "61F"
        String fEntry = blockSegment(wire, 'f').substring(1);   // drop 'f' prefix → "F:2E:0F03"

        // Spatial (§4): the coord leads the entry; the kind code follows it.
        assertThat(sEntry.charAt(0)).as("s entry leads with the coord token").isEqualTo('6');
        assertThat(sEntry.indexOf('F')).as("kind code follows the coord").isGreaterThan(0);

        // Type (§4): the code leads the entry; the trailing ctx coord comes after it.
        assertThat(fEntry.charAt(0)).as("f entry leads with the effect code").isEqualTo('F');
        assertThat(fEntry.indexOf("0F03")).as("trailing ctx coord comes after the code").isGreaterThan(0);
    }

    @Test
    @DisplayName("R6 — encode emits present optional blocks in canonical order s,c,f,v,p,g (§6.3.1)")
    void encodeEmitsBlocksInCanonicalOrder() {
        Frame.TickFrame f = new Frame.TickFrame(4L, 10, 27, 21, 128, 2,
                List.of(cell('6', 'F')),
                Optional.of(new StateChange('C', Optional.of("7A"))),
                List.of(fleeingEffect()),
                List.of(new Event('H', Optional.of(new Coord.Numpad('6')), OptionalInt.of(3))),
                Optional.of(new PoolSnapshot(288, 512)),
                List.of(new RosterMember(new Coord.Numpad('6'), '2')));

        String wire = PerceptionCodec.encode(f);
        String[] seg = wire.split("\\|");
        List<Character> blockPrefixes = new ArrayList<>();
        for (int i = 5; i < seg.length; i++) blockPrefixes.add(seg[i].charAt(0));

        assertThat(blockPrefixes).containsExactly('s', 'c', 'f', 'v', 'p', 'g');
    }
}
