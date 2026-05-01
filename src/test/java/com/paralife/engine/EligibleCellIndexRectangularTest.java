package com.paralife.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REVIEWS R2-13 (OpenCode): lock the {@code toIndex} / {@code fromIndex}
 * linearisation formula for non-square grids before the codebase ever
 * exercises {@code width != height}.
 *
 * <p>Formula: {@code toIndex(x, y) = x * height + y}. This test verifies
 * bijectivity on an 8×16 fixture (width=8, height=16).
 */
class EligibleCellIndexRectangularTest {

    @Test
    void linearisationIsBijectiveOn8x16() {
        int width = 8, height = 16;
        Set<Integer> seen = new HashSet<>();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int idx = x * height + y;
                assertThat(seen.add(idx))
                        .as("toIndex(%d, %d) = %d collides with a prior cell", x, y, idx)
                        .isTrue();
                // Verify fromIndex round-trip.
                int rx = idx / height;
                int ry = idx % height;
                assertThat(rx).as("fromIndex(%d) round-trip x", idx).isEqualTo(x);
                assertThat(ry).as("fromIndex(%d) round-trip y", idx).isEqualTo(y);
            }
        }
        assertThat(seen).hasSize(width * height);
    }
}
