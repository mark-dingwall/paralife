package com.paralife.engine;

/**
 * Double-buffered Moore-neighbourhood diffusion helper for byte-intensity
 * shadow grids (toxin, mutagen).
 *
 * <p>Every step reads {@code src}, writes {@code dst}; the caller swaps or
 * copies the buffers outside this helper (Pitfall 2). All byte reads are
 * masked with {@code & 0xFF} so intensity values up to 255 are handled as
 * unsigned (Pitfall 1). Wrap is toroidal via {@link Math#floorMod}.
 *
 * <p>No in-codebase analog — follows RESEARCH.md Pattern 3 and the
 * formula <code>next = (1 - diffusionRate) * self + diffusionRate * neighbourAvg</code>
 * then <code>next = floor(next * (1 - decayRate))</code>, with a post-decay threshold
 * clear that zeroes any destination cell below {@code threshold} to prevent
 * long-tail spread. Flooring (rather than rounding) the decay step guarantees the
 * grid maximum strictly descends every tick whenever {@code decayRate > 0}; rounding
 * has integer fixed points at low intensities (e.g. {@code round(0.9 * v) == v} for
 * {@code v} in 1..5), which stalls decay and leaves a permanent low-intensity stain.
 */
public final class CellularAutomaton {

    private CellularAutomaton() {
        // utility — not instantiable
    }

    /**
     * Execute one diffusion step from {@code src} to {@code dst}.
     *
     * @param src           source grid (read-only)
     * @param dst           destination grid (overwritten per cell)
     * @param width         grid width
     * @param height        grid height
     * @param diffusionRate fraction in [0, 1] — 0 preserves self, 1 replaces with neighbour average
     * @param decayRate     fraction in [0, 1] — applied multiplicatively after diffusion
     * @param threshold     destination cells with value {@code < threshold} are zeroed
     * @param radius        Moore-neighbourhood half-width (radius=1 → 3×3, radius=2 → 5×5)
     * @return count of non-zero destination cells after the step (for callers
     *         that track a non-zero cell counter — O(1) check in idle ticks)
     */
    public static int diffuseStep(byte[][] src, byte[][] dst,
                                   int width, int height,
                                   double diffusionRate, double decayRate,
                                   int threshold, int radius) {
        int nonZero = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int self = src[x][y] & 0xFF;
                int neighbourSum = 0;
                int neighbourCount = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = Math.floorMod(x + dx, width);
                        int ny = Math.floorMod(y + dy, height);
                        neighbourSum += src[nx][ny] & 0xFF;
                        neighbourCount++;
                    }
                }
                double neighbourAvg = neighbourCount > 0
                        ? neighbourSum / (double) neighbourCount
                        : 0.0;
                double mixed = (1.0 - diffusionRate) * self + diffusionRate * neighbourAvg;
                int after = (int) Math.floor(mixed * (1.0 - decayRate));
                if (after < threshold) after = 0;
                if (after > 255) after = 255;
                if (after < 0) after = 0;
                dst[x][y] = (byte) after;
                if (after != 0) nonZero++;
            }
        }
        return nonZero;
    }
}
