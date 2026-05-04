package com.paralife.engine;

import com.paralife.world.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Catmull-Rom waypoint-based path generator for toxin spline events (D-05).
 *
 * <p><b>Toroidal wrapping contract:</b> waypoints and intermediate samples
 * are produced in UN-WRAPPED (continuous double) coordinates. {@code Math.floorMod}
 * is applied ONLY at the final cell-conversion step to map a sampled continuous
 * point to the integer grid — see {@link #generatePath}. This matches
 * RESEARCH.md Pitfall 8 and the microbes.js:207-223 reference.
 *
 * <p><b>Phase 19.1 Option A — stateless static (C2.1 pin):</b>
 * All constructors removed. {@link #generatePath} is {@code public static};
 * callers supply a {@code long seed} per call and a local {@code Random(seed)}
 * is constructed inside the method. No per-instance RNG state. No resetSeed.
 * This makes path generation byte-exact for a given seed regardless of call
 * order, fixing the RNG-determinism HIGH finding (D-06).
 */
public final class ToxinPathGenerator {

    private ToxinPathGenerator() {
        // Utility class — no instances.
    }

    /**
     * Produce a dense arc-length-sampled path in grid coordinates. The path
     * starts on one random edge and ends on the opposite edge. Intermediate
     * waypoints are generated in un-wrapped coordinates (continuous doubles)
     * with perpendicular offset; Catmull-Rom interpolation yields a smooth
     * spline which is then sampled at approximately one cell per step.
     * {@code Math.floorMod} is applied ONLY at the final cell-materialisation
     * step per the Toroidal wrapping contract.
     *
     * <p>Phase 19.1 D-06: callers supply a {@code seed} per invocation.
     * A local {@code Random(seed)} is constructed inside this method — no
     * shared mutable RNG state. Two calls with the same {@code seed} and grid
     * dimensions always return an equal {@code List<Position>}.
     *
     * @param width            grid width
     * @param height           grid height
     * @param pathPointsMin    minimum number of waypoints (> 1)
     * @param pathPointsMax    maximum number of waypoints (>= pathPointsMin)
     * @param pathOffsetMin    minimum perpendicular waypoint offset
     * @param pathOffsetMax    maximum perpendicular waypoint offset
     * @param seed             per-call RNG seed; caller is responsible for
     *                         supplying a stable seed (e.g. the seed already
     *                         drawn by {@code EnvironmentEngine.spawnToxin})
     * @return ordered list of grid-materialised {@link Position}s
     */
    public static List<Position> generatePath(int width, int height,
                                               int pathPointsMin, int pathPointsMax,
                                               int pathOffsetMin, int pathOffsetMax,
                                               long seed) {
        Random localRng = new Random(seed);

        // ── Step 1: pick entry/exit edge pair ──────────────────────────
        // 0=N edge, 1=E, 2=S, 3=W. Exit is the opposite edge so paths traverse
        // the grid rather than skirting one side.
        int entryEdge = localRng.nextInt(4);
        int exitEdge = (entryEdge + 2) % 4;
        double[] entry = pickEdgePoint(entryEdge, width, height, localRng);
        double[] exit = pickEdgePoint(exitEdge, width, height, localRng);

        // ── Step 2: waypoint generation in UN-WRAPPED coords ──────────
        int waypointCount = (pathPointsMax > pathPointsMin)
                ? pathPointsMin + localRng.nextInt(pathPointsMax - pathPointsMin + 1)
                : pathPointsMin;
        List<double[]> waypoints = new ArrayList<>(waypointCount);
        waypoints.add(entry);
        // Interior points: evenly-spaced fractions along the straight line from
        // entry to exit, then nudged perpendicular by a random offset.
        double dx = exit[0] - entry[0];
        double dy = exit[1] - entry[1];
        double len = Math.hypot(dx, dy);
        // Perpendicular unit vector (rotate tangent by 90°).
        double px = len > 1e-9 ? -dy / len : 0.0;
        double py = len > 1e-9 ? dx / len : 0.0;
        int interior = waypointCount - 2;
        for (int i = 1; i <= interior; i++) {
            double t = i / (double) (interior + 1);
            double cx = entry[0] + t * dx;
            double cy = entry[1] + t * dy;
            int range = pathOffsetMax - pathOffsetMin + 1;
            double mag = (range > 0 ? pathOffsetMin + localRng.nextInt(range) : pathOffsetMin)
                    * (localRng.nextBoolean() ? 1.0 : -1.0);
            waypoints.add(new double[] { cx + px * mag, cy + py * mag });
        }
        waypoints.add(exit);

        // ── Step 3: Catmull-Rom sampling between consecutive waypoints ──
        // Sample each segment at fine resolution, dedup consecutive cells, then
        // materialise to Position via Math.floorMod (toroidal wrap at the END).
        List<Position> path = new ArrayList<>();
        int lastGx = Integer.MIN_VALUE;
        int lastGy = Integer.MIN_VALUE;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            // Neighbour anchors p0 / p3 for the segment p1→p2. At boundaries
            // we duplicate the endpoint so the spline still has 4 control
            // points (standard Catmull-Rom trick).
            double[] p0 = i == 0 ? waypoints.get(0) : waypoints.get(i - 1);
            double[] p1 = waypoints.get(i);
            double[] p2 = waypoints.get(i + 1);
            double[] p3 = (i + 2 < waypoints.size()) ? waypoints.get(i + 2) : p2;

            // Sample density: step by ~1 cell along the segment. Use the
            // straight-line distance between p1 and p2 as a cheap upper bound.
            double segLen = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int samples = Math.max(2, (int) Math.ceil(segLen));
            for (int s = 0; s <= samples; s++) {
                double t = s / (double) samples;
                double sx = catmullRom(p0[0], p1[0], p2[0], p3[0], t);
                double sy = catmullRom(p0[1], p1[1], p2[1], p3[1], t);
                // Toroidal wrap at materialisation (UN-wrapped math above).
                int gx = Math.floorMod((int) Math.round(sx), width);
                int gy = Math.floorMod((int) Math.round(sy), height);
                if (gx != lastGx || gy != lastGy) {
                    path.add(new Position(gx, gy));
                    lastGx = gx;
                    lastGy = gy;
                }
            }
        }
        return path;
    }

    /**
     * Pick a point on the given edge. {@code edge}: 0=N (y=0), 1=E (x=w-1),
     * 2=S (y=h-1), 3=W (x=0). Returns un-wrapped double coords.
     */
    private static double[] pickEdgePoint(int edge, int width, int height, Random rng) {
        return switch (edge) {
            case 0 -> new double[] { rng.nextInt(width), 0.0 };
            case 1 -> new double[] { width - 1.0, rng.nextInt(height) };
            case 2 -> new double[] { rng.nextInt(width), height - 1.0 };
            case 3 -> new double[] { 0.0, rng.nextInt(height) };
            default -> throw new IllegalStateException("Invalid edge: " + edge);
        };
    }

    /**
     * Uniform Catmull-Rom interpolation (tau=0.5). Returns a point between
     * {@code p1} and {@code p2} — p0 / p3 are used only for tangent shaping.
     *
     * <p>Package-private for direct testing — matches the pattern of
     * {@link FertilityInitializer#generatePatch}.
     */
    static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (
                (2.0 * p1)
                        + (-p0 + p2) * t
                        + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                        + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        );
    }
}
