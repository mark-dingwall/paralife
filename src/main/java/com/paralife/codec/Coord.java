package com.paralife.codec;

/**
 * Three coordinate forms per SCHEMA.md §2. Parser disambiguates by first-char class.
 * There is NO 6-char "extended relative" form — all relative coords are exactly 4 chars.
 */
public sealed interface Coord permits Coord.Numpad, Coord.Relative, Coord.Absolute {

    /** Single digit '1'-'9', numpad layout (§2). '5' = self. */
    record Numpad(char digit) implements Coord {
        public Numpad {
            if (digit < '1' || digit > '9') {
                throw new IllegalArgumentException("Numpad digit must be 1-9: " + digit);
            }
        }
    }

    /** Signed ±63 per axis; 4-char wire form [+-]X[+-]Y. */
    record Relative(int dx, int dy) implements Coord {
        public Relative {
            if (dx < -63 || dx > 63) throw new IllegalArgumentException("dx out of range: " + dx);
            if (dy < -63 || dy > 63) throw new IllegalArgumentException("dy out of range: " + dy);
        }
    }

    /** Unsigned 0..4095 per axis; 4-char wire form XXYY (2 chars per axis). */
    record Absolute(int x, int y) implements Coord {
        public Absolute {
            if (x < 0 || x > 4095) throw new IllegalArgumentException("x out of range: " + x);
            if (y < 0 || y > 4095) throw new IllegalArgumentException("y out of range: " + y);
        }
    }
}
