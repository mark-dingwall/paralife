package com.paralife.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decode-semantic unit tests for coordinate parsing — pins SCHEMA.md §0 R2
 * (first-char-class disambiguation) in the DECODE direction, independent of the
 * round-trip oracle (which cannot catch a symmetric mis-parse/mis-encode bug).
 *
 * <p>The parse primitives ({@code parseCoordFirst} / {@code parseRelative}) are
 * private, so each coord is fed through {@link PerceptionCodec#decode} of a
 * minimal hand-authored {@code T} frame and read back off the s-block
 * {@link CellEntry}. The wire strings are independent literals built straight
 * from the SCHEMA §2/§6.3.1 grammar — NOT produced by the encoder.
 */
class CoordTest {

    /**
     * Wrap a coord token in a minimal full {@code T} frame carrying one s-cell
     * (presence=1 → entity only, kind {@code C}, no state) and return its coord.
     * Frame shape: {@code T|<tick:3>|<curXY:2+2>|<energy>/<max>|<sensorRadius:1>|s<coord>1C}.
     */
    private static Coord decodeCoord(String coordToken) {
        Frame f = PerceptionCodec.decode("T|000|0000|1/2|1|s" + coordToken + "1C");
        return ((Frame.TickFrame) f).cells().get(0).coord();
    }

    @Test
    @DisplayName("first char 1-9 → Numpad (R2)")
    void numpadDispatch() {
        assertEquals(new Coord.Numpad('6'), decodeCoord("6"));
    }

    @Test
    @DisplayName("first char +/- → Relative, sign applied per axis (R2)")
    void relativeDispatch() {
        assertEquals(new Coord.Relative(4, -2), decodeCoord("+4-2"));
        assertEquals(new Coord.Relative(-4, 2), decodeCoord("-4+2"));
    }

    @Test
    @DisplayName("relative magnitude parse is positional: '-' in the magnitude slot is base64 digit 63, not a sign (R2)")
    void relativeMagnitudeIsPositional() {
        // sx='+', mx=decode('-')=63, sy='+', my=decode('5')=5
        assertEquals(new Coord.Relative(63, 5), decodeCoord("+-+5"));
    }

    @Test
    @DisplayName("absolute coords are positional only — not first-char dispatched (R2)")
    void absoluteIsPositionalOnly() {
        // curX='0A'=10, curY='0B'=11 in the fixed 2+2 header slot.
        Frame.TickFrame f = (Frame.TickFrame) PerceptionCodec.decode("T|000|0A0B|1/2|1|s61C");
        assertEquals(10, f.curX());
        assertEquals(11, f.curY());
    }

    @Test
    @DisplayName("coord first char outside the numpad/relative classes is rejected (R2 negative; numpad+relative above are the positive controls)")
    void invalidFirstCharRejected() {
        // '0' is not a numpad digit (1-9) and not a sign → no coord form matches.
        assertThrows(CodecException.class, () -> decodeCoord("0"));
        // '*' is in neither class either.
        assertThrows(CodecException.class, () -> decodeCoord("*"));
    }
}
