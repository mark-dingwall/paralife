package com.paralife.engine;

import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;

/**
 * Phase 19 Plan 04 — structural assertions that {@link SimulationEngine} and
 * {@link EnvironmentEngine} consume {@link LiveEntityRegistry#snapshot()} for
 * per-entity iteration rather than full grid scans.
 *
 * <p>RED gate tests: fail before the Plan 04 refactor (registry.snapshot() not yet
 * called in iteration loops) and must turn green after the refactor (D-10 SCALE-07).
 */
class EntityListIterationTest {

    private static final int W = 8;
    private static final int H = 8;

    private WorldGrid grid;
    private BotRegistry botRegistry;
    private LiveEntityRegistry registry;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(W, H));
        botRegistry = mock(BotRegistry.class);
        registry = spy(new LiveEntityRegistry(new GridConfig(W, H)));
    }

    // ── SimulationEngine ─────────────────────────────────────────────

    /**
     * Verify that {@code SimulationEngine.processTick} calls
     * {@code liveEntityRegistry.snapshot()} when an entity is present.
     * RED before Plan 04 refactor; GREEN after.
     */
    @Test
    void simulationEngine_processTick_callsSnapshotForEntityIteration() {
        registry.register("cat1", new Position(3, 3));
        grid.setEntity(3, 3, new Particle("cat1", ParticleType.CATALYST, 50));

        SimulationConfig cfg = new SimulationConfig(1, 0, 0.0, 0, true, 8, 0);
        SimulationEngine engine = new SimulationEngine(grid, cfg, botRegistry, noBonding(),
                new CompositeRegistry(), CompositeConfig.defaults(),
                uniformProfile(cfg), StarvationConfig.defaults(),
                defaultSeasonTracker());
        engine.setLiveEntityRegistry(registry);

        engine.processTick(1L);

        // snapshot() must be called at least once per in-scope grid-scan replacement.
        verify(registry, atLeastOnce()).snapshot();
    }

    // ── EnvironmentEngine ────────────────────────────────────────────

    /**
     * Verify that {@code EnvironmentEngine.onTickEnvOnlyForTest} calls
     * {@code liveEntityRegistry.snapshot()} for per-entity segments.
     * RED before Plan 04 refactor; GREEN after.
     */
    @Test
    void environmentEngine_onTick_callsSnapshotForPerEntitySegments() {
        // Place + register a particle so the BUFFED scan has something to iterate.
        registry.register("env1", new Position(2, 2));
        grid.setEntity(2, 2, new Particle("env1", ParticleType.CATALYST, 50));

        EnvCleanupHooksBean hooksBean = new EnvCleanupHooksBean();
        BuffRegistry buffRegistry = new BuffRegistry();
        CompositeRegistry composites = mock(CompositeRegistry.class);
        DeathFinalizer finalizer = new DeathFinalizer(grid, botRegistry, buffRegistry,
                composites, hooksBean, null);

        EnvironmentEngine env = new EnvironmentEngine(grid,
                new SeasonTracker(new SeasonsConfig(200, 0.0)),
                EnvironmentConfig.defaults(), buffRegistry,
                FertilityConfig.defaults(), finalizer, hooksBean,
                new java.util.Random(42L));
        hooksBean.registerCompostSink(env::applyCompost);
        env.setLiveEntityRegistry(registry);

        env.onTickEnvOnlyForTest(1L);

        // After Plan 04 refactor, snapshot() is called at least once.
        verify(registry, atLeastOnce()).snapshot();
    }

    // ── helpers ─────────────────────────────────────────────────────

    private BondingConfig noBonding() {
        return new BondingConfig(Integer.MAX_VALUE, 0.0, 0.0);
    }

    private SeasonTracker defaultSeasonTracker() {
        return new SeasonTracker(new SeasonsConfig(200, 0.0));
    }

    private MetabolicProfile uniformProfile(SimulationConfig cfg) {
        int decay = cfg.energyDecayPerTick();
        int combat = cfg.combatEnergyTransfer();
        int nutrient = cfg.nutrientConsumeEnergy();
        MetabolicProfile.TypeProfile p = new MetabolicProfile.TypeProfile(
                100, decay, combat, combat, nutrient, 30, 0, 0.0, 1, 30, 10);
        return new MetabolicProfile(p, p, p);
    }
}
