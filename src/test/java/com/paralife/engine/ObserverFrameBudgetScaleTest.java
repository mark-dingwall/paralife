package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.observer.ObserverFrameBuilder;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GO-on-scale gate (spec Assumption 1). Measures the FULL worst-case ON-THREAD path a real tick
 * runs — WorldGrid.snapshot() + EnvironmentEngine.snapshot() (both-layers-saturated full-grid scan
 * + ~131 072 EnvCell allocations) + buildWorld + Jackson serialize — on a 256x256 grid densely
 * occupied and both env layers saturated. Lives in com.paralife.engine to reach the package-private
 * stamp helpers; calling the REAL env.snapshot() inside the timed loop is deliberate — an
 * EnvironmentSnapshot pre-built outside the loop would exclude the production scan+alloc the spec
 * counts (Assumption 1). NOT default-suite (@Tag("slow"), run via -PincludeLong=true) —
 * machine-sensitive. The logged best-of-5 is the artifact; the assertion is a soft ceiling forcing
 * the decision: under 400 ms → on-thread encode ships; over → implement the capacity-1 encoder-VT
 * fallback (Assumption 1) before scale-readiness.
 */
@Tag("slow")
class ObserverFrameBudgetScaleTest {

    private static final int DIM = 256;
    private static final long WATERMARK_MS = 400;

    /** Real EnvironmentEngine with BOTH shadow grids saturated (mirrors the Task 4 wiring). */
    private static EnvironmentEngine saturatedEngine(WorldGrid grid) {
        EnvironmentConfig d = EnvironmentConfig.defaults();
        EnvironmentConfig cfg = new EnvironmentConfig(false, 42L, d.lightning(), d.toxin(), d.mutagen(), d.compost());
        BuffRegistry buffs = new BuffRegistry();
        EnvCleanupHooksBean hooks = new EnvCleanupHooksBean();
        DeathFinalizer finalizer = new DeathFinalizer(
                grid, new BotRegistry(), buffs, mock(CompositeRegistry.class), hooks, mock(SimulationEngine.class));
        EnvironmentEngine env = new EnvironmentEngine(grid,
                new SeasonTracker(new SeasonsConfig(200, 0.5)),
                cfg, buffs, FertilityConfig.defaults(), finalizer, hooks,
                (ToxinPathGenerator) null, new Random(42L));
        hooks.registerCompostSink(env::applyCompost);
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                env.stampToxinIntensityForTest(new Position(x, y), 200); // saturate both layers
                env.stampMutagenForTest(new Position(x, y), 7);
            }
        }
        return env;
    }

    @Test
    void worstCaseCaptureAndEncodeUnderTickWatermark() throws Exception {
        WorldGrid grid = new WorldGrid(new GridConfig(DIM, DIM));
        int i = 0;
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                grid.setEntity(x, y,
                        new Particle("p" + (i++), ParticleType.values()[(x + y) % 3], 50, 100));
            }
        }
        EnvironmentEngine env = saturatedEngine(grid);
        ObserverFrameBuilder builder = new ObserverFrameBuilder();
        ObjectMapper mapper = new ObjectMapper();

        for (int w = 0; w < 3; w++) encodeOnce(builder, mapper, grid, env); // JIT warmup
        long bestNs = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) bestNs = Math.min(bestNs, encodeOnce(builder, mapper, grid, env));
        long ms = bestNs / 1_000_000;
        System.out.println("[scale-gate] worst-case snapshot+capture+encode best-of-5 = " + ms
                + " ms (watermark " + WATERMARK_MS + " ms)");

        assertThat(ms)
                .as("worst-case capture+encode must stay under the 400ms tick watermark, else "
                        + "implement the capacity-1 encoder-VT fallback (spec Assumption 1)")
                .isLessThan(WATERMARK_MS);
    }

    /** Times the ENTIRE on-thread path: BOTH snapshots (incl. the real env scan) + build + serialize. */
    private static long encodeOnce(ObserverFrameBuilder builder, ObjectMapper mapper,
                                   WorldGrid grid, EnvironmentEngine env) throws Exception {
        long t0 = System.nanoTime();
        var frame = builder.buildWorld(1L, grid.snapshot(), env.snapshot(), Set.of(), new long[] {0, 0, 0});
        mapper.writeValueAsString(frame);
        return System.nanoTime() - t0;
    }
}
