package com.paralife.world;

import java.util.List;

/**
 * Immutable position on a toroidal 2D grid.
 * Coordinates automatically wrap using modular arithmetic.
 */
public record Position(int x, int y) {

    /**
     * Creates a position that wraps coordinates into [0, width) x [0, height).
     */
    public static Position wrap(int x, int y, int width, int height) {
        return new Position(Math.floorMod(x, width), Math.floorMod(y, height));
    }

    /**
     * Returns the 8 Moore neighbors (including diagonals) on a toroidal grid.
     */
    public List<Position> neighbors(int width, int height) {
        return List.of(
                wrap(x - 1, y - 1, width, height),
                wrap(x, y - 1, width, height),
                wrap(x + 1, y - 1, width, height),
                wrap(x - 1, y, width, height),
                wrap(x + 1, y, width, height),
                wrap(x - 1, y + 1, width, height),
                wrap(x, y + 1, width, height),
                wrap(x + 1, y + 1, width, height)
        );
    }

    /**
     * Manhattan distance on a toroidal grid.
     */
    public int toroidalDistance(Position other, int width, int height) {
        int dx = Math.abs(this.x - other.x);
        int dy = Math.abs(this.y - other.y);
        dx = Math.min(dx, width - dx);
        dy = Math.min(dy, height - dy);
        return dx + dy;
    }
}
