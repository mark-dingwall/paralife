package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Env snapshot contract: sparse non-zero toxin intensity + mutagen strain (seeded via the
 * engine's own package-private test-stamp helpers), and this-tick lightning coordinates that
 * clear on the next onTick. Engine-direct and DETERMINISTIC: a test-owned config with
 * enabled=false + a seeded RNG means onTick injects NO random toxin/mutagen/lightning, so the
 * "cleared → empty" assertion cannot be masked by a freshly-spawned strike. (The
 * lightningStrikesThisTick.clear() runs at the TOP of onTick, before the enabled gate, so the
 * clear still fires with events disabled.) Helpers verified package-private:
 * stampToxinIntensityForTest / stampMutagenForTest / applyLightningAtForTest (EnvironmentEngine
 * :1569 / :1598 / :1550) — test stays in package com.paralife.engine.
 */
class EnvironmentSnapshotTest {

    /** Real EnvironmentEngine with a deterministic, event-disabled config (mirrors LightningTest wiring). */
    private static EnvironmentEngine newEngine(int dim) {
        WorldGrid grid = new WorldGrid(new GridConfig(dim, dim));
        EnvironmentConfig d = EnvironmentConfig.defaults();
        // enabled=false so onTick spawns nothing; reuse the default sub-configs via accessors.
        EnvironmentConfig cfg = new EnvironmentConfig(
                false, 42L, d.lightning(), d.toxin(), d.mutagen(), d.compost());
        BuffRegistry buffs = new BuffRegistry();
        EnvCleanupHooksBean hooks = new EnvCleanupHooksBean();
        DeathFinalizer finalizer = new DeathFinalizer(
                grid, new BotRegistry(), buffs, mock(CompositeRegistry.class), hooks,
                mock(SimulationEngine.class));
        EnvironmentEngine env = new EnvironmentEngine(grid,
                new SeasonTracker(new SeasonsConfig(200, 0.5)),
                cfg, buffs, FertilityConfig.defaults(), finalizer, hooks,
                (ToxinPathGenerator) null, new Random(42L));
        hooks.registerCompostSink(env::applyCompost);
        return env;
    }

    @Test
    void snapshotListsOnlyNonZeroCellsWithCorrectValues() {
        EnvironmentEngine env = newEngine(16);
        env.stampToxinIntensityForTest(new Position(1, 2), 180); // intensity magnitude
        env.stampMutagenForTest(new Position(3, 4), 42);         // strain id (NOT a magnitude)

        EnvironmentSnapshot snap = env.snapshot();

        assertThat(snap.toxin())
                .as("only the seeded non-zero toxin cell, carrying its intensity")
                .containsExactly(new EnvironmentSnapshot.EnvCell(1, 2, 180));
        assertThat(snap.mutagen())
                .as("only the seeded non-zero mutagen cell, carrying its strain id")
                .containsExactly(new EnvironmentSnapshot.EnvCell(3, 4, 42));
    }

    @Test
    void snapshotExcludesZeroCells() {
        // control: nothing seeded → both layers empty. Arms "only non-zero" — an impl that
        // listed every cell (or a default value on clean cells) would fail here.
        EnvironmentSnapshot snap = newEngine(16).snapshot();
        assertThat(snap.toxin()).isEmpty();
        assertThat(snap.mutagen()).isEmpty();
    }

    @Test
    void appliedLightningPresentThisTick_multipleCoords_noDuplicates_clearsNext() {
        EnvironmentEngine env = newEngine(16);
        env.applyLightningAtForTest(7, 8);
        env.applyLightningAtForTest(9, 10);

        assertThat(env.snapshot().lightning())
                .as("each applied strike CENTER appears exactly once (append-once, not per-affected-cell)")
                .containsExactly(new Position(7, 8), new Position(9, 10));

        env.onTick(new TickEvent(1)); // clears the per-tick list at onTick start (before the enabled gate)

        assertThat(env.snapshot().lightning())
                .as("lightning list is transient — cleared by the next onTick").isEmpty();
    }
}
