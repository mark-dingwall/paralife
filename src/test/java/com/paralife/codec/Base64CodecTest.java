package com.paralife.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decode-semantic unit tests for {@link Base64Codec} — pins SCHEMA.md §0 R1
 * (the single shared 64-char alphabet) in the DECODE direction, independent of
 * the round-trip oracle ({@link PerceptionCodecRoundTripTest}). Round-trip has a
 * symmetric blind spot: a digit that mis-decodes AND mis-encodes identically
 * survives it. These assertions read the alphabet from a test-owned literal —
 * never from {@code Base64Codec.ALPHABET} — so a reorder of the production
 * alphabet goes red here.
 */
class Base64CodecTest {

    /** Independent copy of the SCHEMA §1 alphabet — deliberately NOT Base64Codec.ALPHABET. */
    private static final String EXPECTED_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-";

    @Test
    @DisplayName("decodeDigit maps each alphabet char to its index (R1)")
    void decodeDigitMapsEachCharToIndex() {
        for (int i = 0; i < 64; i++) {
            char c = EXPECTED_ALPHABET.charAt(i);
            assertEquals(i, Base64Codec.decodeDigit(c), "char '" + c + "'");
        }
    }

    @Test
    @DisplayName("decodeDigit boundary chars (R1)")
    void decodeDigitBoundaries() {
        assertEquals(0, Base64Codec.decodeDigit('0'));
        assertEquals(9, Base64Codec.decodeDigit('9'));
        assertEquals(10, Base64Codec.decodeDigit('A'));
        assertEquals(35, Base64Codec.decodeDigit('Z'));
        assertEquals(36, Base64Codec.decodeDigit('a'));
        assertEquals(61, Base64Codec.decodeDigit('z'));
        assertEquals(62, Base64Codec.decodeDigit('_'));
        assertEquals(63, Base64Codec.decodeDigit('-'));
    }

    @Test
    @DisplayName("encodeDigit maps each index to its alphabet char (R1)")
    void encodeDigitMapsEachIndexToChar() {
        for (int i = 0; i < 64; i++) {
            assertEquals(EXPECTED_ALPHABET.charAt(i), Base64Codec.encodeDigit(i), "index " + i);
        }
    }

    @Test
    @DisplayName("decodeDigit rejects out-of-alphabet chars (R1)")
    void decodeDigitRejectsInvalidChars() {
        assertThrows(CodecException.class, () -> Base64Codec.decodeDigit('|'));
        assertThrows(CodecException.class, () -> Base64Codec.decodeDigit(' '));
        assertThrows(CodecException.class, () -> Base64Codec.decodeDigit('~'));
        assertThrows(CodecException.class, () -> Base64Codec.decodeDigit((char) 200)); // > 127
    }

    @Test
    @DisplayName("encodeDigit rejects out-of-range values (R1)")
    void encodeDigitRejectsOutOfRange() {
        assertThrows(CodecException.class, () -> Base64Codec.encodeDigit(-1));
        assertThrows(CodecException.class, () -> Base64Codec.encodeDigit(64));
    }
}
