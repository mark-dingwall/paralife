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
}
