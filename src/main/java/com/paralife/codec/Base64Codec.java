package com.paralife.codec;

import java.util.Arrays;

/**
 * Shared 64-char alphabet lookup tables for the Phase 15 compact wire protocol.
 * Authoritative alphabet from SCHEMA.md §1 — do not reorder.
 */
public final class Base64Codec {

    public static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-";

    public static final char[] INT_TO_CHAR = new char[64];
    public static final int[] CHAR_TO_INT = new int[128];

    static {
        Arrays.fill(CHAR_TO_INT, -1);
        for (int i = 0; i < 64; i++) {
            char c = ALPHABET.charAt(i);
            INT_TO_CHAR[i] = c;
            CHAR_TO_INT[c] = i;
        }
    }

    private Base64Codec() {
        // utility — not instantiable
    }

    public static char encodeDigit(int value) {
        if (value < 0 || value > 63) {
            throw new CodecException("Base64 digit out of range: " + value);
        }
        return INT_TO_CHAR[value];
    }

    public static int decodeDigit(char c) {
        int v = c < 128 ? CHAR_TO_INT[c] : -1;
        if (v < 0) {
            throw new CodecException("Invalid base64 char: " + c);
        }
        return v;
    }
}
