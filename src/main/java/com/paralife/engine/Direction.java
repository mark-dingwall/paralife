package com.paralife.engine;

import com.paralife.world.Position;

/**
 * The 8 compass directions for movement and reproduction on the grid.
 * Each direction maps to a (dx, dy) offset.
 */
public enum Direction {
    N(0, -1),
    NE(1, -1),
    E(1, 0),
    SE(1, 1),
    S(0, 1),
    SW(-1, 1),
    W(-1, 0),
    NW(-1, -1);

    private final int dx;
    private final int dy;

    Direction(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public int dx() { return dx; }
    public int dy() { return dy; }

    /**
     * Apply this direction to a position, with toroidal wrapping.
     */
    public Position apply(Position from, int width, int height) {
        return Position.wrap(from.x() + dx, from.y() + dy, width, height);
    }

    /**
     * Parse a direction string (case-insensitive), returning null if invalid.
     */
    public static Direction fromString(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Map a numpad digit {@code '1'..'9'} to a compass direction per SCHEMA
     * §2 (numpad convention). {@code '5'} is the self/rest cell and returns
     * null. Any other character is a grammar violation and throws.
     *
     * <p>Plan 15-06 Task 2: used by {@link ActionResolver}'s verb-M/E/A/R/V
     * dispatch and IRV tie-breaking.
     */
    public static Direction fromNumpad(char digit) {
        return switch (digit) {
            case '7' -> NW;
            case '8' -> N;
            case '9' -> NE;
            case '4' -> W;
            case '5' -> null;          // self — no direction
            case '6' -> E;
            case '1' -> SW;
            case '2' -> S;
            case '3' -> SE;
            default -> throw new IllegalArgumentException(
                    "Numpad digit must be '1'..'9': " + digit);
        };
    }

    /** Inverse of {@link #fromNumpad}. {@code '5'} is never produced. */
    public static char numpadOf(Direction d) {
        return switch (d) {
            case NW -> '7';
            case N  -> '8';
            case NE -> '9';
            case W  -> '4';
            case E  -> '6';
            case SW -> '1';
            case S  -> '2';
            case SE -> '3';
        };
    }
}
