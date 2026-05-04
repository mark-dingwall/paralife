package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plan 14-04 lightning-strike verification (cycle-6 MEDIUM/LOW patches).
 *
 * <p>Two-tier structure:
 * <ol>
 *   <li>Plain JUnit unit tests (mocked CompositeRegistry + SimulationEngine) —
 *       fine for arithmetic, radii, counter semantics because these tests
 *       never exercise composite-member cleanup.</li>
 *   <li>Nested {@code @SpringBootTest} inner class (cycle-4 MEDIUM fix) wires
 *       the REAL SimulationEngine + DeathFinalizer + EnvironmentEngine +
 *       CompositeRegistry so the {@code DeathFinalizer →
 *       SimulationEngine.handleMemberDeath → CompositeRegistry.removeMember}
 *       delegation chain actually runs.</li>
 * </ol>
 *
 * <p>Unit-test setup passes {@code null} for the (now-ignored) ToxinPathGenerator
 * parameter — Phase 19.1 C2.1: ToxinPathGenerator is fully-static, no instance needed.
 *
 * <p>Composite integration test scope (cycle-6 LOW): pinned at
 * {@code dissolution-chance=0.0} — proves graceful-degradation only.
 * Shatter branch owned by {@code EnvDeathSweepTest_Shatter} in Plan 14-01.
 */
class LightningTest {

    // ======================================================================
    // Unit tests (mocked collaborators) — arithmetic, radii, counter.
    // ======================================================================

    private WorldGrid grid;
    private EnvironmentEngine env;
    private EnvironmentConfig cfg;
    private FertilityConfig fertilityCfg;

    @BeforeEach
    void setup() {
        grid = new WorldGrid(new GridConfig(32, 32));
        cfg = EnvironmentConfig.defaults();
        fertilityCfg = FertilityConfig.defaults();
        EnvCleanupHooksBean hooksBean = new EnvCleanupHooksBean();
        BotRegistry bots = new BotRegistry();
        BuffRegistry buffs = new BuffRegistry();
        CompositeRegistry composites = mock(CompositeRegistry.class);
        SimulationEngine sim = mock(SimulationEngine.class);
        DeathFinalizer finalizer = new DeathFinalizer(grid, bots, buffs, composites, hooksBean, sim);
        // Phase 19.1 C2.1: ToxinPathGenerator is now fully-static; null is passed for the
        // back-compat ignored parameter. The constructor shim accepts ToxinPathGenerator but
        // does not use it.
        env = new EnvironmentEngine(grid,
                new SeasonTracker(new SeasonsConfig(200, 0.5)),
                cfg, buffs, fertilityCfg, finalizer, hooksBean,
                (ToxinPathGenerator) null, new Random(42L));
        hooksBean.registerCompostSink(env::applyCompost);
    }

    @Test
    void innerRadiusDamagesParticle() {
        grid.setEntity(10, 10, new Particle("p1", ParticleType.CATALYST, 100, 100));
        env.applyLightningAtForTest(10, 10);
        Particle after = (Particle) grid.getCell(10, 10).occupant();
        assertThat(after.energy()).isEqualTo(100 - cfg.lightning().damage());
    }

    @Test
    void innerRadiusDamagesBondedPair() {
        grid.setEntity(10, 10, new BondedPair("bp1", ParticleType.CATALYST, ParticleType.MEMBRANE,
                100, 100, "a", "b"));
        env.applyLightningAtForTest(10, 10);
        BondedPair after = (BondedPair) grid.getCell(10, 10).occupant();
        assertThat(after.energy()).isEqualTo(100 - cfg.lightning().damage());
    }

    @Test
    void innerRadiusDamagesCompositeMember() {
        grid.setEntity(10, 10, new CompositeMember("cm1", "comp", ParticleType.CATALYST,
                Role.LOCOMOTOR, 100, 100));
        env.applyLightningAtForTest(10, 10);
        CompositeMember after = (CompositeMember) grid.getCell(10, 10).occupant();
        assertThat(after.energy()).isEqualTo(100 - cfg.lightning().damage());
    }

    @Test
    void damageClampsAtZero() {
        // Start energy well below damage — clamp MUST hold at 0, not go negative.
        grid.setEntity(10, 10, new Particle("p1", ParticleType.CATALYST, 10, 100));
        env.applyLightningAtForTest(10, 10);
        Particle after = (Particle) grid.getCell(10, 10).occupant();
        assertThat(after.energy()).isZero();
    }

    @Test
    void outerRingBoostsFertility() {
        int centerX = 10, centerY = 10;
        // Pick a cell in the outer ring: inner=2, outer=4 — (10, 13) is distance 3.
        int rx = 10, ry = 13;
        Cell before = grid.getCell(rx, ry);
        int baselineLevel = before.nutrientLevel();

        env.applyLightningAtForTest(centerX, centerY);

        Cell after = grid.getCell(rx, ry);
        assertThat(after.nutrientLevel())
                .as("outer ring (dist=3) receives fertility boost")
                .isEqualTo(Math.min(baselineLevel + cfg.lightning().fertilityBoost(),
                        fertilityCfg.maxLevel()));
    }

    @Test
    void fertilityBoostClampsToMaxLevel() {
        int rx = 10, ry = 13;
        // Pre-set nutrient level at max to prove clamp holds.
        Cell start = grid.getCell(rx, ry);
        grid.setCell(rx, ry, start.withNutrientLevel(fertilityCfg.maxLevel()));

        env.applyLightningAtForTest(10, 10);

        Cell after = grid.getCell(rx, ry);
        assertThat(after.nutrientLevel())
                .as("fertility-boost must clamp at FertilityConfig.maxLevel")
                .isEqualTo(fertilityCfg.maxLevel());
    }

    @Test
    void noEffectOutsideOuterRadius() {
        // Cell at distance 5 from center with outer=4 — must NOT be touched.
        int rx = 10, ry = 15; // dist = 5
        int baselineLevel = grid.getCell(rx, ry).nutrientLevel();
        // Also set a particle — must also be undamaged.
        grid.setEntity(rx, ry, new Particle("p-out", ParticleType.CATALYST, 100, 100));

        env.applyLightningAtForTest(10, 10);

        Cell after = grid.getCell(rx, ry);
        assertThat(after.nutrientLevel()).isEqualTo(baselineLevel);
        Particle p = (Particle) after.occupant();
        assertThat(p.energy()).as("outside outer radius — no damage").isEqualTo(100);
    }

    @Test
    void innerRadiusCellsDoNotReceiveFertilityBoost() {
        // Inner ring cell at center — the center itself is dist=0 (inner).
        // Inner radius cells ONLY take damage — never receive fertility.
        int cx = 10, cy = 10;
        int baselineLevel = grid.getCell(cx, cy).nutrientLevel();

        env.applyLightningAtForTest(cx, cy);

        Cell after = grid.getCell(cx, cy);
        assertThat(after.nutrientLevel())
                .as("inner-radius cells receive no fertility boost")
                .isEqualTo(baselineLevel);
    }

    @Test
    void lightningWorksAcrossToroidalSeam() {
        // Center at (0, 0) — the outer ring wraps across x=width-1 / y=height-1.
        int rx = 31, ry = 0; // dx = -1 (wraps), dy = 0 — dist = 1 — inner
        grid.setEntity(rx, ry, new Particle("p-wrap", ParticleType.CATALYST, 100, 100));
        env.applyLightningAtForTest(0, 0);
        Particle after = (Particle) grid.getCell(rx, ry).occupant();
        assertThat(after.energy())
                .as("toroidal-wrap inner-radius damage must apply")
                .isEqualTo(100 - cfg.lightning().damage());
    }

    @Test
    void strikeCounterUsesAttemptedStrikeSemantics() {
        assertThat(env.lightningStrikeCount()).isZero();
        env.applyLightningAtForTest(5, 5);
        assertThat(env.lightningStrikeCount()).isEqualTo(1L);
        env.applyLightningAtForTest(20, 20);
        assertThat(env.lightningStrikeCount()).isEqualTo(2L);
    }

    /**
     * Unit-scope same-tick Particle kill via processEnvDeaths — uses REAL
     * DeathFinalizer (composite-member cleanup is NOT exercised here, so
     * mocked SimulationEngine is fine).
     */
    @Test
    void innerRadiusLethalParticleKillIsRemovedSameTickViaProcessEnvDeaths() {
        grid.setEntity(10, 10, new Particle("p-dies", ParticleType.CATALYST, 1, 100));
        env.applyLightningAtForTest(10, 10);
        env.processEnvDeathsForTest();
        assertThat(grid.getCell(10, 10).isEmpty())
                .as("lethal inner-radius Particle hit is removed same tick via processEnvDeaths")
                .isTrue();
    }

    // ======================================================================
    // @SpringBootTest (cycle-4 MEDIUM fix) — same-tick composite-registry
    // update requires a REAL SimulationEngine to run the full delegation chain.
    // ======================================================================
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "paralife.world.width=32",
            "paralife.world.height=32",
            "paralife.tick.auto-start=false",
            "paralife.simulation.enabled=true",
            "paralife.simulation.events.enabled=true",
            "paralife.simulation.events.seed=42",
            // Deterministic graceful-degradation — shatter branch owned by
            // EnvDeathSweepTest_Shatter in Plan 14-01 (cycle-6 LOW scope note).
            "paralife.composite.dissolution-chance=0.0",
            "paralife.bonding.bonding-probability=0.0"
    })
    class CompositeCleanupIntegration {

        @Autowired WorldGrid grid;
        @Autowired EnvironmentEngine env;
        @Autowired CompositeRegistry compositeRegistry;

        @Test
        void innerRadiusCompositeMemberKillUpdatesCompositeRegistrySameTick() {
            grid.clearOccupants();
            compositeRegistry.clear();

            // cycle-4 action item #4: use the REAL CompositeRegistry.register
            // signature — (String, List, Map, int sharedPoolEnergy, int maxPoolEnergy).
            String compositeId = "comp-L";
            List<String> memberIds = new ArrayList<>(List.of("cm-dies", "cm-alive-1", "cm-alive-2"));
            Map<String, Position> positions = new HashMap<>();
            positions.put("cm-dies", new Position(10, 10));
            positions.put("cm-alive-1", new Position(10, 11));
            positions.put("cm-alive-2", new Position(10, 12));
            grid.setEntity(10, 10, new CompositeMember("cm-dies", compositeId,
                    ParticleType.CATALYST, Role.LOCOMOTOR, 1, 100));
            grid.setEntity(10, 11, new CompositeMember("cm-alive-1", compositeId,
                    ParticleType.CATALYST, Role.FEEDER, 80, 100));
            grid.setEntity(10, 12, new CompositeMember("cm-alive-2", compositeId,
                    ParticleType.CATALYST, Role.ATTACKER, 80, 100));
            compositeRegistry.register(compositeId, memberIds, positions, 60, 300);

            env.applyLightningAtForTest(10, 10);
            env.processEnvDeathsForTest();

            // Full delegation chain ran: DeathFinalizer →
            // SimulationEngine.handleMemberDeath → CompositeRegistry.removeMember,
            // all same tick.
            assertThat(grid.getCell(10, 10).isEmpty())
                    .as("composite member cell cleared same tick")
                    .isTrue();
            var stateOpt = compositeRegistry.getComposite(compositeId);
            assertThat(stateOpt)
                    .as("composite still registered (graceful-degradation branch)")
                    .isPresent();
            assertThat(stateOpt.get().getMemberCount())
                    .as("cycle-4 MEDIUM fix: removeMember fired via REAL SimulationEngine; memberCount dropped from 3 to 2 same tick")
                    .isEqualTo(2);
        }
    }
}
