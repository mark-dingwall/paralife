package com.paralife.engine;

import com.paralife.world.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 1 tests for {@link ToxinPathGenerator} and {@link ToxinEvent}.
 *
 * <p>Phase 19.1 C2.1: {@link ToxinPathGenerator} is now a static-method holder
 * (no constructors, no per-instance RNG). Tests use the static
 * {@code generatePath(..., long seed)} API directly.
 */
class ToxinPathGeneratorTest {

    @Test
    void catmullRomEndpointsLandOnAnchors() {
        // Uniform tau=0.5: at t=0 the curve passes through p1, at t=1 through p2.
        assertThat(ToxinPathGenerator.catmullRom(0.0, 10.0, 20.0, 30.0, 0.0))
                .isCloseTo(10.0, within(1e-9));
        assertThat(ToxinPathGenerator.catmullRom(0.0, 10.0, 20.0, 30.0, 1.0))
                .isCloseTo(20.0, within(1e-9));
    }

    @Test
    void catmullRomInterpolatesLinearlyForColinearPoints() {
        // For a line through p0=0, p1=1, p2=2, p3=3 the curve must interpolate
        // linearly between p1 and p2 — spline math degenerates to linear for
        // equally-spaced colinear anchors.
        double mid = ToxinPathGenerator.catmullRom(0.0, 1.0, 2.0, 3.0, 0.5);
        assertThat(mid).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void generatePathIsDeterministicForSameSeed() {
        // Phase 19.1 D-06: static API — same seed, same path every time.
        List<Position> a = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, 42L);
        List<Position> b = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, 42L);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void generatedPathCoversSubstantialGridDistance() {
        // Path starts at one edge, ends at the opposite edge. First-to-last
        // point sum of abs deltas should be at least the grid width - a few
        // cells (approximates "traverses the grid").
        List<Position> path = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, 7L);
        assertThat(path).isNotEmpty();
        assertThat(path.size()).isGreaterThan(10);
    }

    @Test
    void pathPointsAllInGridBounds() {
        int w = 48;
        int h = 48;
        List<Position> path = ToxinPathGenerator.generatePath(w, h, 4, 8, 5, 20, 3L);
        assertThat(path).allSatisfy(p -> {
            assertThat(p.x()).isBetween(0, w - 1);
            assertThat(p.y()).isBetween(0, h - 1);
        });
    }

    @Test
    void differentSeedsProduceDifferentPaths() {
        // Phase 19.1 D-06 falsifier check: different seeds must (overwhelmingly) differ.
        List<Position> a = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, 0xDEADBEEFL);
        List<Position> b = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, 0xCAFEBABEL);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void multiToxinSeedDeterminism() {
        // Phase 19.1 D-06: sequential calls with distinct seeds are each individually
        // reproducible — verifies no shared mutable state leaks between calls.
        long[] seeds = { 0xDEADBEEFL, 100L, 999999L };
        for (long seed : seeds) {
            List<Position> first = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, seed);
            List<Position> second = ToxinPathGenerator.generatePath(64, 64, 4, 8, 5, 20, seed);
            assertThat(first)
                    .as("Same seed %d must always produce same path", seed)
                    .isEqualTo(second);
        }
    }

    // ── ToxinEvent record tests ────────────────────────────────────

    @Test
    void toxinEventWithHeadIdxReturnsCopyWithNewIndex() {
        ToxinEvent ev = new ToxinEvent(0L, 80L, List.of(new Position(1, 1), new Position(2, 2)), 0, 123L);
        ToxinEvent advanced = ev.withHeadIdx(1);
        assertThat(advanced.headIdx()).isEqualTo(1);
        // Other fields preserved.
        assertThat(advanced.spawnTick()).isEqualTo(0L);
        assertThat(advanced.lifetimeTicks()).isEqualTo(80L);
        assertThat(advanced.prePath()).isEqualTo(ev.prePath());
        assertThat(advanced.seed()).isEqualTo(123L);
    }

    @Test
    void toxinEventHasReachedEndWhenHeadIdxAtOrPastLastPoint() {
        List<Position> path = List.of(new Position(0, 0), new Position(1, 1), new Position(2, 2));
        ToxinEvent a = new ToxinEvent(0L, 80L, path, 2, 0L);
        ToxinEvent b = new ToxinEvent(0L, 80L, path, 1, 0L);
        assertThat(a.hasReachedEnd()).isTrue();
        assertThat(b.hasReachedEnd()).isFalse();
    }

    @Test
    void toxinEventIsExpiredWhenTickAfterSpawnPlusLifetime() {
        List<Position> path = List.of(new Position(0, 0));
        ToxinEvent ev = new ToxinEvent(10L, 20L, path, 0, 0L);
        assertThat(ev.isExpired(29L)).isFalse();
        assertThat(ev.isExpired(30L)).isTrue();
        assertThat(ev.isExpired(31L)).isTrue();
    }
}
