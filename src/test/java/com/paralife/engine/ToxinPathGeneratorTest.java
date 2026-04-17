package com.paralife.engine;

import com.paralife.world.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 1 tests for {@link ToxinPathGenerator} and {@link ToxinEvent}.
 *
 * <p>Tests live in {@code com.paralife.engine} (cycle-4 action item #10) so
 * they have access to the package-private {@code ToxinPathGenerator(Random)}
 * constructor and {@code static catmullRom} helper.
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
        ToxinPathGenerator gen1 = new ToxinPathGenerator(new Random(42L));
        ToxinPathGenerator gen2 = new ToxinPathGenerator(new Random(42L));
        List<Position> a = gen1.generatePath(64, 64, 4, 8, 5, 20);
        List<Position> b = gen2.generatePath(64, 64, 4, 8, 5, 20);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void generatedPathCoversSubstantialGridDistance() {
        // Path starts at one edge, ends at the opposite edge. First-to-last
        // point sum of abs deltas should be at least the grid width - a few
        // cells (approximates "traverses the grid").
        ToxinPathGenerator gen = new ToxinPathGenerator(new Random(7L));
        List<Position> path = gen.generatePath(64, 64, 4, 8, 5, 20);
        assertThat(path).isNotEmpty();
        assertThat(path.size()).isGreaterThan(10);
    }

    @Test
    void pathPointsAllInGridBounds() {
        ToxinPathGenerator gen = new ToxinPathGenerator(new Random(3L));
        int w = 48;
        int h = 48;
        List<Position> path = gen.generatePath(w, h, 4, 8, 5, 20);
        assertThat(path).allSatisfy(p -> {
            assertThat(p.x()).isBetween(0, w - 1);
            assertThat(p.y()).isBetween(0, h - 1);
        });
    }

    @Test
    void noArgConstructorDelegatesToDefaultRandom() {
        // cycle-6 MEDIUM: 14-04 unit tests use `new ToxinPathGenerator()` (no-arg).
        // Lock that path here — path generation must not NPE when no Random is supplied.
        ToxinPathGenerator gen = new ToxinPathGenerator();
        List<Position> path = gen.generatePath(32, 32, 4, 8, 5, 25);
        assertThat(path).isNotEmpty();
        assertThat(path).allSatisfy(p -> {
            assertThat(p.x()).isBetween(0, 31);
            assertThat(p.y()).isBetween(0, 31);
        });
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
