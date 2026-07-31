package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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

    /** Engine plus the canonical hooks bean that owns the live infection map. */
    private record Rig(EnvironmentEngine engine, EnvCleanupHooksBean hooks) {}

    private static EnvironmentEngine newEngine(int dim) {
        return newRig(dim).engine();
    }

    /** Real EnvironmentEngine with a deterministic, event-disabled config (mirrors LightningTest wiring). */
    private static Rig newRig(int dim) {
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
        return new Rig(env, hooks);
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

    /**
     * R1 — the record itself owns immutability. Constructed from mutable inputs, then every
     * input is mutated; all four components must be unaffected. Record-direct (no engine) so
     * the invariant is pinned at the constructor, not at one cooperating producer.
     */
    @Test
    void recordDetachesEveryCollectionFromItsMutableConstructorInput() {
        List<EnvironmentSnapshot.EnvCell> toxin =
                new ArrayList<>(List.of(new EnvironmentSnapshot.EnvCell(1, 1, 10)));
        List<EnvironmentSnapshot.EnvCell> mutagen =
                new ArrayList<>(List.of(new EnvironmentSnapshot.EnvCell(2, 2, 20)));
        List<Position> lightning = new ArrayList<>(List.of(new Position(3, 3)));
        Set<String> infected = new HashSet<>(Set.of("e1"));

        EnvironmentSnapshot snap = new EnvironmentSnapshot(toxin, mutagen, lightning, infected);

        toxin.add(new EnvironmentSnapshot.EnvCell(9, 9, 99));
        mutagen.add(new EnvironmentSnapshot.EnvCell(9, 9, 99));
        lightning.add(new Position(9, 9));
        infected.add("intruder");

        assertThat(snap.toxin()).containsExactly(new EnvironmentSnapshot.EnvCell(1, 1, 10));
        assertThat(snap.mutagen()).containsExactly(new EnvironmentSnapshot.EnvCell(2, 2, 20));
        assertThat(snap.lightning()).containsExactly(new Position(3, 3));
        assertThat(snap.infectedIds()).containsExactly("e1");
    }

    /**
     * R1 (production seam) — the engine actually supplies the active infection ids. The clean
     * control proves an always-populated set cannot pass.
     */
    @Test
    void snapshotCarriesActiveInfectionIdsFromTheHooksBean() {
        Rig rig = newRig(16);

        assertThat(rig.engine().snapshot().infectedIds())
                .as("control: no infection seeded → empty").isEmpty();

        rig.hooks().getInfections().put("infected-1",
                new Infection(5, (byte) 3, 1, 5, new Position(4, 4)));

        assertThat(rig.engine().snapshot().infectedIds())
                .as("the engine seam projects the live infection key set")
                .containsExactly("infected-1");
    }
}
