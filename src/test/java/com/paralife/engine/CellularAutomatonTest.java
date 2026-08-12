package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CellularAutomaton#diffuseStep}.
 *
 * <p>Lives in {@code com.paralife.engine} (cycle-4 action item #10) for
 * package-private helper visibility.
 */
class CellularAutomatonTest {

    @Test
    void diffuseStepRadius1SpreadsToImmediateNeighbors() {
        int w = 10, h = 10;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[5][5] = (byte) 255;
        int nonZero = CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 1);
        // 9 cells in the 3x3 neighbourhood should be non-zero (self + 8 Moore).
        assertThat(nonZero).isEqualTo(9);
        assertThat(dst[4][4] & 0xFF).isGreaterThan(0);
        assertThat(dst[6][6] & 0xFF).isGreaterThan(0);
    }

    @Test
    void diffuseStepRadius2ReachesTwoCellsAway() {
        int w = 10, h = 10;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[5][5] = (byte) 255;
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 2);
        // At radius 2, the diffusion reaches 2 cells away on the first step.
        assertThat(dst[3][5] & 0xFF).as("radius=2 reaches two cells away").isGreaterThan(0);
        assertThat(dst[7][5] & 0xFF).as("radius=2 reaches two cells away (other side)").isGreaterThan(0);
    }

    @Test
    void diffuseStepRadius1DoesNotReachTwoCellsAway() {
        int w = 10, h = 10;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[5][5] = (byte) 255;
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 1);
        assertThat(dst[3][5] & 0xFF).as("radius=1 must NOT reach two cells").isEqualTo(0);
        assertThat(dst[7][5] & 0xFF).as("radius=1 must NOT reach two cells").isEqualTo(0);
    }

    @Test
    void diffuseStepDiffusionRateZeroPreservesSelfAndLeavesNeighborsEmpty() {
        int w = 5, h = 5;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[2][2] = (byte) 100;
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.0, 0.0, 1, 1);
        assertThat(dst[2][2] & 0xFF).as("self preserved when diffusionRate=0").isEqualTo(100);
        // Neighbours: (1-diffusionRate)*self + diffusionRate*neighborAvg = 1*0 + 0*... = 0
        assertThat(dst[1][2] & 0xFF).isEqualTo(0);
    }

    @Test
    void diffuseStepAppliesDecayRate() {
        int w = 5, h = 5;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[2][2] = (byte) 100;
        // diffusionRate=0 means value stays at self; decayRate=0.5 halves it.
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.0, 0.5, 0, 1);
        assertThat(dst[2][2] & 0xFF).as("decay 0.5 halves 100").isEqualTo(50);
    }

    @Test
    void diffuseStepThresholdClearZeroesBelowThreshold() {
        int w = 5, h = 5;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        // Low signal: after a 50% diffuse + avg neighbours (all 0 nearby except self)
        // the signal at self would be 2, below threshold=5.
        src[2][2] = (byte) 4;
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 5, 1);
        assertThat(dst[2][2] & 0xFF).isEqualTo(0);
    }

    @Test
    void diffuseStepUnsignedByteReadHandlesFullIntensity() {
        int w = 3, h = 3;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        // -1 signed == 255 unsigned.
        src[1][1] = (byte) 255;
        int nonZero = CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 1);
        // Self must retain substantial intensity (not wrapped to 0 by a missing & 0xFF).
        assertThat(dst[1][1] & 0xFF).isGreaterThan(100);
        assertThat(nonZero).isGreaterThan(1);
    }

    @Test
    void diffuseStepClampsAtMax255() {
        int w = 3, h = 3;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        // Fill a 3x3 patch fully — resulting writeback must still fit in a byte.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                src[x][y] = (byte) 255;
            }
        }
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 1);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                assertThat(dst[x][y] & 0xFF).isLessThanOrEqualTo(255);
            }
        }
    }

    @Test
    void diffuseStepWrapsToroidallyAroundEdges() {
        int w = 5, h = 5;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[0][0] = (byte) 255;
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 1);
        // (0,0)'s Moore neighbours wrap to include (4,4), (4,0), (0,4) etc.
        assertThat(dst[4][4] & 0xFF).as("toroidal wrap SW corner").isGreaterThan(0);
        assertThat(dst[4][0] & 0xFF).as("toroidal wrap W edge").isGreaterThan(0);
        assertThat(dst[0][4] & 0xFF).as("toroidal wrap S edge").isGreaterThan(0);
    }

    @Test
    void diffuseStepReturnsCountOfNonZeroDestinationCells() {
        int w = 4, h = 4;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[1][1] = (byte) 255;
        int returned = CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 1, 1);

        // Independently count non-zero destination cells and compare.
        int observed = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if ((dst[x][y] & 0xFF) != 0) observed++;
            }
        }
        assertThat(returned).as("diffuseStep return value matches observed non-zero count").isEqualTo(observed);
    }

    // ── EARS-1: strict descent of the grid maximum whenever decayRate > 0 ──

    @Test
    void diffuseStepStrictlyDecaysUniformPlateauUnderDecayRate() {
        // Fixture: uniform 5x5 plateau at intensity 3 — the exact band Math.round pins
        // (round(0.9*v) == v for v in 1..5). diffusionRate 0.5 keeps it locally uniform
        // on a torus, so diffusion cannot mask the decay by importing higher neighbours.
        // Hand-computed: uniform plateau on a torus => neighbourAvg == 3 => mixed == 3
        //   => round(2.7) == 3 (RED) vs floor(2.7) == 2 (GREEN).
        int w = 5, h = 5;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                src[x][y] = (byte) 3;
            }
        }
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.1, 0, 1);
        int max = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                max = Math.max(max, dst[x][y] & 0xFF);
            }
        }
        assertThat(max).as("uniform plateau at 3 must strictly descend under decayRate 0.1").isLessThan(3);
    }

    @Test
    void diffuseStepStrictlyDecaysSingleCellAtIntensityOne() {
        // Sharpest single-cell case: v = 1, where floor(0.9) == 0 but round(0.9) == 1.
        int w = 3, h = 3;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        src[1][1] = (byte) 1;
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.0, 0.1, 0, 1);
        assertThat(dst[1][1] & 0xFF).as("intensity 1 must decay to 0 under decayRate 0.1, self-only").isEqualTo(0);
    }

    @Test
    void diffuseStepPositiveControlNoDecayHoldsPlateauAtSource() {
        // Positive control: the SAME fixture with decayRate 0.0 must hold at 3 —
        // otherwise a change that simply erodes every grid would pass vacuously.
        int w = 5, h = 5;
        byte[][] src = new byte[w][h];
        byte[][] dst = new byte[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                src[x][y] = (byte) 3;
            }
        }
        CellularAutomaton.diffuseStep(src, dst, w, h, 0.5, 0.0, 0, 1);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                assertThat(dst[x][y] & 0xFF).as("decayRate 0.0 must not erode the plateau").isEqualTo(3);
            }
        }
    }

    @Test
    @Disabled("perf-only — not part of CI. Enable locally to sanity-check CA diffusion cost on full-size grid.")
    void diffusionCostOn256x256For60TicksWithinLooseBound() {
        // cycle-6 LOW: 60 CA steps on 256x256 at default radius should complete
        // well under 500ms on a dev box. Loose bound — only fires if someone adds
        // accidental quadratic-per-cell work to the hot path.
        byte[][] src = new byte[256][256];
        byte[][] dst = new byte[256][256];
        src[128][128] = (byte) 255;
        long t0 = System.nanoTime();
        byte[][] a = src, b = dst;
        for (int t = 0; t < 60; t++) {
            CellularAutomaton.diffuseStep(a, b, 256, 256, 0.5, 0.1, 5, 3);
            byte[][] swap = a; a = b; b = swap;
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(elapsedMs).as("60 CA steps on 256x256 within 500ms").isLessThan(500L);
    }
}
