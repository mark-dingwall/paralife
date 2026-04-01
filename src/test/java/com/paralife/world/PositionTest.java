package com.paralife.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PositionTest {

    private static final int W = 10;
    private static final int H = 10;

    @Test
    void wrapPositiveCoordinates() {
        Position p = Position.wrap(3, 7, W, H);
        assertThat(p.x()).isEqualTo(3);
        assertThat(p.y()).isEqualTo(7);
    }

    @Test
    void wrapNegativeX() {
        Position p = Position.wrap(-1, 5, W, H);
        assertThat(p.x()).isEqualTo(9); // -1 mod 10 = 9
        assertThat(p.y()).isEqualTo(5);
    }

    @Test
    void wrapNegativeY() {
        Position p = Position.wrap(5, -1, W, H);
        assertThat(p.x()).isEqualTo(5);
        assertThat(p.y()).isEqualTo(9);
    }

    @Test
    void wrapOverflowX() {
        Position p = Position.wrap(10, 5, W, H);
        assertThat(p.x()).isEqualTo(0);
    }

    @Test
    void wrapOverflowY() {
        Position p = Position.wrap(5, 10, W, H);
        assertThat(p.y()).isEqualTo(0);
    }

    @Test
    void wrapLargeNegative() {
        Position p = Position.wrap(-25, -33, W, H);
        assertThat(p.x()).isEqualTo(5);  // -25 mod 10 = 5
        assertThat(p.y()).isEqualTo(7);  // -33 mod 10 = 7
    }

    @Test
    void neighborsAtCenter() {
        Position p = new Position(5, 5);
        List<Position> neighbors = p.neighbors(W, H);
        assertThat(neighbors).hasSize(8);
        assertThat(neighbors).containsExactlyInAnyOrder(
                new Position(4, 4), new Position(5, 4), new Position(6, 4),
                new Position(4, 5),                      new Position(6, 5),
                new Position(4, 6), new Position(5, 6), new Position(6, 6)
        );
    }

    @Test
    void neighborsAtTopLeftCorner() {
        Position p = new Position(0, 0);
        List<Position> neighbors = p.neighbors(W, H);
        assertThat(neighbors).hasSize(8);
        // Should wrap: (-1,-1) → (9,9), (-1,0) → (9,0), etc.
        assertThat(neighbors).contains(
                new Position(9, 9), // top-left wraps both
                new Position(0, 9), // top wraps y
                new Position(1, 9), // top-right wraps y
                new Position(9, 0), // left wraps x
                new Position(1, 0), // right
                new Position(9, 1), // bottom-left wraps x
                new Position(0, 1), // bottom
                new Position(1, 1)  // bottom-right
        );
    }

    @Test
    void neighborsAtBottomRightCorner() {
        Position p = new Position(9, 9);
        List<Position> neighbors = p.neighbors(W, H);
        assertThat(neighbors).hasSize(8);
        assertThat(neighbors).contains(
                new Position(0, 0), // wraps both
                new Position(9, 0), // wraps y
                new Position(0, 9)  // wraps x
        );
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 5, 5, 10, 10, 10",  // direct distance
            "0, 0, 9, 9, 10, 10, 2",   // wrapping is shorter
            "0, 0, 5, 0, 10, 10, 5",   // equidistant on x
            "0, 0, 0, 0, 10, 10, 0",   // same position
    })
    void toroidalDistance(int x1, int y1, int x2, int y2, int w, int h, int expected) {
        Position p1 = new Position(x1, y1);
        Position p2 = new Position(x2, y2);
        assertThat(p1.toroidalDistance(p2, w, h)).isEqualTo(expected);
    }

    @Test
    void toroidalDistanceIsSymmetric() {
        Position a = new Position(2, 3);
        Position b = new Position(8, 7);
        assertThat(a.toroidalDistance(b, W, H)).isEqualTo(b.toroidalDistance(a, W, H));
    }
}
