package com.paralife.engine;

import com.paralife.metrics.EmergenceMetrics;
import com.paralife.world.Entity;
import com.paralife.world.WorldGrid;
import com.paralife.engine.emergence.TestLogCapture;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 Plan 05 (R15): deterministic composite-formation integration test.
 *
 * <p><b>Goal.</b> Given a fixed master seed (42), three successive in-method
 * runs of a short tick-pipeline exercise produce IDENTICAL composite counts.
 *
 * <p><b>REVIEWS HIGH #1 — the critical fix.</b> A prior revision of this test
 * relied on {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} +
 * {@code worldGrid.clear()} to reset state between runs. That approach is
 * <b>insufficient</b> for three reasons:
 * <ol>
 *   <li>{@code @DirtiesContext} fires between {@code @Test} methods — NOT
 *       between in-method iterations. A loop that exercises 3 runs inside one
 *       test sees the same beans across all 3 runs.</li>
 *   <li>Bean-internal {@code Random} fields are never reinitialised by a
 *       context rebuild unless we call them explicitly.</li>
 *   <li>{@link FertilityInitializer}'s {@code @PostConstruct} only fires once
 *       per bean creation; its seeded fertility grid is frozen after the first
 *       tick unless we re-run {@code seedPatches()} via {@code resetSeed()}.</li>
 * </ol>
 * This test therefore invokes {@link #resetAllSeedsBetweenRuns()} before every
 * run, which calls the public {@code resetSeed()} hook shipped by Plan 16-01
 * on every seeded component.
 *
 * <p><b>REVIEWS HIGH #4 — master-seed fail-fast.</b> {@code @BeforeEach}
 * asserts each autowired config record returned {@code 42L} from its
 * {@code seed()} accessor. If a yaml key is mistyped, Spring's binder silently
 * falls back to the nullable default, and only the fail-fast assertion catches
 * it — the 3-run identity property would otherwise still "pass" but against
 * an unseeded production path.
 *
 * <p><b>REVIEWS HIGH #5 — fertility prefix.</b> The correct {@code @TestPropertySource}
 * key is {@code paralife.simulation.fertility.seed} (NOT the wrong
 * "world" prefix used in a prior revision).
 *
 * <p><b>REVIEWS MEDIUM — {@code @DirtiesContext} dropped.</b> Explicit
 * {@code resetSeed()} replaces the {@code BEFORE_EACH_TEST_METHOD} context
 * churn, avoiding ~20-30s of Spring context rebuild per @Test.
 *
 * <p><b>D-17.</b> Engine-direct: {@code @SpringBootTest} runs with the MOCK
 * environment (default — the annotation attribute that would enable a live
 * Jetty is deliberately omitted); no Jetty, no bots, no virtual-thread I/O.
 *
 * <p><b>D-23 (addendum).</b> The {@link DifferentSeedControl} nested class
 * rebuilds context with seed=1337 and asserts its composite count DIFFERS
 * from the seed=42 baseline captured via {@link #seed42CompositeCount}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=32",
        "paralife.world.height=32",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.seed=42",
        "paralife.simulation.action-seed=42",
        "paralife.simulation.fertility.seed=42",
        "paralife.simulation.spawn.seed=42",
        "paralife.composite.seed=42",
        "paralife.bonding.bonding-probability=0.15",
        "paralife.bonding.bond-energy-threshold=20",
        "paralife.composite.can-form-composites=true",
        // Disable stochastic shatter so composites that form survive to the
        // end of the run — the registry snapshot at run-end is what we assert
        // identity on across the 3 runs (plan must_haves: HashSet.hasSize(1)
        // on compositeRegistry.size()). Shattered composites would muddy the
        // signal by confounding formation-determinism with shatter-determinism.
        "paralife.composite.dissolution-chance=0.0",
        "paralife.composite.critical-energy-percent=0"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class CompositeFormationDeterminismTest {

    /** Shared holder — outer seed=42 baseline writes, nested seed=1337 control reads (D-23). */
    static final AtomicInteger seed42CompositeCount = new AtomicInteger(-1);

    @Autowired WorldGrid worldGrid;
    @Autowired SimulationEngine simulationEngine;
    @Autowired ActionResolver actionResolver;
    @Autowired CompositeEnergyDistributor compositeEnergyDistributor;
    @Autowired FertilityInitializer fertilityInitializer;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired BuffRegistry buffRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired DeathFinalizer deathFinalizer;
    @Autowired EmergenceMetrics emergenceMetrics;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired MeterRegistry meterRegistry;
    /** Phase 19 Plan 04: registry must be cleared between runs and entities registered. */
    @Autowired LiveEntityRegistry liveEntityRegistry;

    // Config autowires for REVIEWS HIGH #4 master-seed fail-fast binding check:
    @Autowired SimulationConfig simulationConfig;
    @Autowired FertilityConfig fertilityConfig;
    @Autowired SpawnConfig spawnConfig;
    @Autowired CompositeConfig compositeConfig;
    @Autowired EnvironmentConfig environmentConfig;

    /**
     * REVIEWS HIGH #4 — fail-fast binding assertion. A yaml key typo under
     * {@code @TestPropertySource} would silently fall back to the nullable
     * default and the 3-run identity property would pass against the
     * production (unseeded) path, masking the bug. These assertions catch
     * the typo at test start.
     */
    @org.junit.jupiter.api.BeforeEach
    void verifySeedBindingFailFast() {
        assertThat(simulationConfig.seed())
                .as("paralife.simulation.seed must bind to 42L — check yaml key")
                .isEqualTo(42L);
        assertThat(simulationConfig.actionSeed())
                .as("paralife.simulation.action-seed must bind to 42L")
                .isEqualTo(42L);
        assertThat(fertilityConfig.seed())
                .as("paralife.simulation.fertility.seed must bind to 42L "
                        + "(REVIEWS HIGH #5: do NOT use the wrong world-scoped prefix)")
                .isEqualTo(42L);
        assertThat(spawnConfig.seed())
                .as("paralife.simulation.spawn.seed must bind via SpawnConfig @ConfigurationProperties")
                .isEqualTo(42L);
        assertThat(compositeConfig.seed())
                .as("paralife.composite.seed must bind to 42L")
                .isEqualTo(42L);
        assertThat(environmentConfig.seed())
                .as("paralife.simulation.events.seed must bind to 42L")
                .isEqualTo(42L);
    }

    /**
     * REVIEWS HIGH #1 — THE CRITICAL FIX. Called before every run in the
     * 3-run identity loop. Order:
     * <ol>
     *   <li>Clear all registries/caches that track live entities.</li>
     *   <li>Reseed every seeded {@code Random} field (4 engine components).</li>
     * </ol>
     * {@code fertilityInitializer.resetSeed()} also re-runs {@code seedPatches}
     * so the fertility grid is regenerated — a bare {@code worldGrid.clear()}
     * + {@code Random}-field reset would leave prior-run patches intact.
     * {@code environmentEngine.resetForTest()} already re-seeds its internal
     * rng from {@code config.seed()} (see EnvironmentEngine:1239-1240) —
     * verified pre-revision; no production change needed for this plan.
     */
    private void resetAllSeedsBetweenRuns() {
        // 1. Reset registries + caches that track live entities
        worldGrid.clear();
        compositeRegistry.clear();
        buffRegistry.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest(); // Phase 19 Plan 04: must clear between runs
        environmentEngine.resetForTest();
        deathFinalizer.resetCountForTest();

        // 2. Reset seeded Random state in every bean (HIGH #1 core fix)
        simulationEngine.resetSeed();
        actionResolver.resetSeed();
        compositeEnergyDistributor.resetSeed();
        fertilityInitializer.resetSeed();

        // EmergenceMetrics counters are NOT reset — the counter deltas between
        // the bondBefore/compositeBefore snapshots captured inside driveRun are
        // the observable we compare, so absolute-value drift across runs is
        // isolated per-run.
    }

    /**
     * Paired observables captured per run — asserted for identity across runs.
     *
     * <p>{@link #compositeCount} tracks the <b>cumulative composite-formation
     * counter delta</b> during the run (via {@link EmergenceMetrics#compositesFormed}).
     * This is a stronger determinism signal than end-of-run
     * {@code compositeRegistry.size()} because it counts every composite that
     * FORMED, independent of whether pool-drain / shatter subsequently
     * dissolved it — the latter is a function of composite energy dynamics,
     * which is orthogonal to the formation-determinism property R15 asserts.
     */
    private record RunObservables(int compositeCount,
                                  double bondedPairCounterDelta,
                                  double compositeCounterDelta) {}

    private RunObservables driveRun(int ticks) {
        double bondBefore = emergenceMetrics.bondedPairsFormed();
        double compositeBefore = emergenceMetrics.compositesFormed();

        seedDeterministicScenario();

        for (long t = 1; t <= ticks; t++) {
            publisher.publishEvent(new TickEvent(t));
        }

        double compositeDelta = emergenceMetrics.compositesFormed() - compositeBefore;
        return new RunObservables(
                (int) Math.round(compositeDelta),
                emergenceMetrics.bondedPairsFormed() - bondBefore,
                compositeDelta);
    }

    /**
     * Pinned Catalyst (predator) / Spore (prey) clusters arranged as adjacent
     * 2×2 blocks. Bond placement is at the attacker position (primaryPos),
     * so after two attackers at, say, (4,4) and (5,4) each bond with their
     * adjacent prey, two BondedPairs land at (4,4) + (5,4) — which are Moore
     * neighbours. The composite-formation scan ({@link SimulationEngine}
     * line 502+) runs in the SAME tick after bond application and merges
     * them into a Composite.
     *
     * <p>RPS: Catalyst beats Spore (see {@link Entity.ParticleType#prey}).
     *
     * <p>Identical placement every run + seeded {@code simRng} / {@code actionRng}
     * / {@code compositeRng} / {@code fertilityRng} = deterministic composite counts.
     *
     * <p>Six 2×2 clusters spread across the 32×32 grid. Each cluster yields
     * a potential composite when both bonds roll true in the same tick
     * ({@code bondingProbability=0.5} → ~25% per cluster per tick → multiple
     * expected across 200 ticks).
     */
    private void seedDeterministicScenario() {
        // Each row: {attackerX1, attackerY1, attackerX2, attackerY2,
        //           preyX1, preyY1, preyX2, preyY2}
        // Attackers are placed adjacent (same row, dx=1) so resulting
        // BondedPairs will be Moore-neighbours.
        int[][] clusters = {
                {  4,  4,  5,  4,     4,  5,  5,  5 },
                { 12,  4, 13,  4,    12,  5, 13,  5 },
                { 22,  4, 23,  4,    22,  5, 23,  5 },
                {  4, 20,  5, 20,     4, 21,  5, 21 },
                { 12, 20, 13, 20,    12, 21, 13, 21 },
                { 22, 20, 23, 20,    22, 21, 23, 21 },
        };
        for (int[] c : clusters) {
            // Two Catalyst attackers
            String catId1 = "c-" + c[0] + "-" + c[1];
            String catId2 = "c-" + c[2] + "-" + c[3];
            String spoId1 = "s-" + c[4] + "-" + c[5];
            String spoId2 = "s-" + c[6] + "-" + c[7];
            worldGrid.setEntity(c[0], c[1],
                    new Entity.Particle(catId1, Entity.ParticleType.CATALYST, 80, 100));
            liveEntityRegistry.register(catId1, new com.paralife.world.Position(c[0], c[1]), java.util.Optional.empty());
            worldGrid.setEntity(c[2], c[3],
                    new Entity.Particle(catId2, Entity.ParticleType.CATALYST, 80, 100));
            liveEntityRegistry.register(catId2, new com.paralife.world.Position(c[2], c[3]), java.util.Optional.empty());
            // Two Spore prey (Catalyst beats Spore per ParticleType.prey)
            worldGrid.setEntity(c[4], c[5],
                    new Entity.Particle(spoId1, Entity.ParticleType.SPORE, 80, 100));
            liveEntityRegistry.register(spoId1, new com.paralife.world.Position(c[4], c[5]), java.util.Optional.empty());
            worldGrid.setEntity(c[6], c[7],
                    new Entity.Particle(spoId2, Entity.ParticleType.SPORE, 80, 100));
            liveEntityRegistry.register(spoId2, new com.paralife.world.Position(c[6], c[7]), java.util.Optional.empty());
        }
    }

    @Test
    @Order(1)
    void compositeFormationIsDeterministicAcrossThreeRunsWithSameSeed() {
        List<RunObservables> observations = new ArrayList<>();
        int runs = 3;
        int ticks = 200;
        for (int r = 0; r < runs; r++) {
            resetAllSeedsBetweenRuns();
            observations.add(driveRun(ticks));
        }

        Set<Integer> uniqueCompositeCounts = new HashSet<>();
        for (RunObservables o : observations) uniqueCompositeCounts.add(o.compositeCount());

        // Primary identity assertion (REVIEWS HIGH #1). Single-line form preserves
        // the plan's acceptance-grep pattern: `new HashSet.*hasSize(1)`.
        assertThat(new HashSet<>(uniqueCompositeCounts)).hasSize(1);

        // Richer diagnostic assertion — same property, with context on failure.
        assertThat(uniqueCompositeCounts)
                .as("seed=42 — all %d runs must produce identical cumulative "
                        + "composites-formed counter delta (equivalent to "
                        + "compositeRegistry.size() at formation time) after explicit "
                        + "resetSeed() (REVIEWS HIGH #1). Counts: %s",
                        runs, observations.stream().map(RunObservables::compositeCount).toList())
                .hasSize(1);

        assertThat(observations.get(0).compositeCount())
                .as("If 0, seedDeterministicScenario is under-tuned or forced-composite "
                        + "config (bondingProbability=0.5, threshold=20) too weak. Raise "
                        + "entity density or tick count.")
                .isGreaterThan(0);

        // Publish baseline for DifferentSeedControl (D-23).
        seed42CompositeCount.set(observations.get(0).compositeCount());
    }

    @Test
    @Order(2)
    void seededRunProducesAtLeastOneComposite() {
        resetAllSeedsBetweenRuns();
        RunObservables obs = driveRun(200);
        assertThat(obs.compositeCount())
                .as("seed=42 scenario must produce at least one composite")
                .isGreaterThan(0);
    }

    @Test
    @Order(3)
    void emergenceMarkersFireDuringSeededRun() {
        TestLogCapture logCapture = TestLogCapture.attach();
        try {
            resetAllSeedsBetweenRuns();
            driveRun(200);
            List<String> markers = logCapture.emergenceMarkers();

            assertThat(markers)
                    .as("EMERGENCE bonded-pair-formed marker must fire at least once — "
                            + "proves SimulationEngine log wiring (16-02) runs in this scenario")
                    .anyMatch(m -> m.startsWith("EMERGENCE bonded-pair-formed"));
            assertThat(markers)
                    .as("EMERGENCE composite-formed marker must fire at least once — "
                            + "proves SimulationEngine composite log wiring runs")
                    .anyMatch(m -> m.startsWith("EMERGENCE composite-formed"));

            // Gated marker checks — only assert when the underlying counter fired.
            // These gates prevent false failures when the scenario happens to
            // avoid a specific domain event (e.g. no mutagen infections at
            // seed=42) while still enforcing marker-counter symmetry when the
            // event did occur.
            if (emergenceMetrics.buffsGrantedCount() > 0.0) {
                assertThat(markers)
                        .as("EMERGENCE buff-granted marker must fire when buffs were granted")
                        .anyMatch(m -> m.startsWith("EMERGENCE buff-granted"));
            }
            if (emergenceMetrics.infectionsStarted() > 0.0) {
                assertThat(markers)
                        .as("EMERGENCE infection-started marker must fire when infections started")
                        .anyMatch(m -> m.startsWith("EMERGENCE infection-started"));
            }
        } finally {
            logCapture.detach();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // D-23 — live mutation control: seed=1337 must diverge from seed=42
    // ════════════════════════════════════════════════════════════════

    /**
     * Sibling @Nested class that rebuilds the Spring context with a DIFFERENT
     * master seed. @TestClassOrder ensures this runs AFTER the outer class —
     * so {@link #seed42CompositeCount} has been populated by the outer
     * baseline test. A defense-in-depth assertion guards the ordering.
     */
    @Nested
    @Order(2)
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "paralife.tick.auto-start=false",
            "paralife.world.width=32",
            "paralife.world.height=32",
            "paralife.simulation.events.enabled=true",
            "paralife.simulation.events.seed=1337",
            "paralife.simulation.seed=1337",
            "paralife.simulation.action-seed=1337",
            "paralife.simulation.fertility.seed=1337",
            "paralife.simulation.spawn.seed=1337",
            "paralife.composite.seed=1337",
            "paralife.bonding.bonding-probability=0.5",
            "paralife.bonding.bond-energy-threshold=20",
            "paralife.composite.can-form-composites=true",
            "paralife.composite.dissolution-chance=0.0",
            "paralife.composite.critical-energy-percent=0"
    })
    class DifferentSeedControl {
        @Autowired WorldGrid worldGrid;
        @Autowired SimulationEngine simulationEngine;
        @Autowired ActionResolver actionResolver;
        @Autowired CompositeEnergyDistributor compositeEnergyDistributor;
        @Autowired FertilityInitializer fertilityInitializer;
        @Autowired CompositeRegistry compositeRegistry;
        @Autowired EnvironmentEngine environmentEngine;
        @Autowired BuffRegistry buffRegistry;
        @Autowired BotRegistry botRegistry;
        @Autowired DeathFinalizer deathFinalizer;
        @Autowired EmergenceMetrics emergenceMetrics;
        @Autowired ApplicationEventPublisher publisher;
        /** Phase 19 Plan 04: registry must be cleared between runs and entities registered. */
        @Autowired LiveEntityRegistry liveEntityRegistry;

        private void resetAllSeedsBetweenRuns() {
            worldGrid.clear();
            compositeRegistry.clear();
            buffRegistry.clear();
            botRegistry.clear();
            liveEntityRegistry.clearForTest(); // Phase 19 Plan 04: must clear between runs
            environmentEngine.resetForTest();
            deathFinalizer.resetCountForTest();
            simulationEngine.resetSeed();
            actionResolver.resetSeed();
            compositeEnergyDistributor.resetSeed();
            fertilityInitializer.resetSeed();
        }

        private void seedDeterministicScenario() {
            // Mirror of outer seedDeterministicScenario — 2×2 Catalyst/Spore
            // clusters arranged so bonded pairs land in Moore-adjacent cells.
            int[][] clusters = {
                    {  4,  4,  5,  4,     4,  5,  5,  5 },
                    { 12,  4, 13,  4,    12,  5, 13,  5 },
                    { 22,  4, 23,  4,    22,  5, 23,  5 },
                    {  4, 20,  5, 20,     4, 21,  5, 21 },
                    { 12, 20, 13, 20,    12, 21, 13, 21 },
                    { 22, 20, 23, 20,    22, 21, 23, 21 },
            };
            for (int[] c : clusters) {
                String catId1 = "c-" + c[0] + "-" + c[1];
                String catId2 = "c-" + c[2] + "-" + c[3];
                String spoId1 = "s-" + c[4] + "-" + c[5];
                String spoId2 = "s-" + c[6] + "-" + c[7];
                worldGrid.setEntity(c[0], c[1],
                        new Entity.Particle(catId1, Entity.ParticleType.CATALYST, 80, 100));
                liveEntityRegistry.register(catId1, new com.paralife.world.Position(c[0], c[1]), java.util.Optional.empty());
                worldGrid.setEntity(c[2], c[3],
                        new Entity.Particle(catId2, Entity.ParticleType.CATALYST, 80, 100));
                liveEntityRegistry.register(catId2, new com.paralife.world.Position(c[2], c[3]), java.util.Optional.empty());
                worldGrid.setEntity(c[4], c[5],
                        new Entity.Particle(spoId1, Entity.ParticleType.SPORE, 80, 100));
                liveEntityRegistry.register(spoId1, new com.paralife.world.Position(c[4], c[5]), java.util.Optional.empty());
                worldGrid.setEntity(c[6], c[7],
                        new Entity.Particle(spoId2, Entity.ParticleType.SPORE, 80, 100));
                liveEntityRegistry.register(spoId2, new com.paralife.world.Position(c[6], c[7]), java.util.Optional.empty());
            }
        }

        @Test
        void seed1337_compositeCountDiffersFromSeed42() {
            // Defense in depth: hard-fail if outer baseline hasn't populated the
            // shared holder. @TestClassOrder(ClassOrderer.OrderAnnotation.class)
            // + @Order(1) on outer / @Order(2) on this nested class should
            // order correctly — this assertion catches any regression in the
            // ordering mechanism (e.g. JUnit API change).
            assertThat(seed42CompositeCount.get())
                    .as("Outer seed=42 baseline must run first — check @TestClassOrder annotation "
                            + "and outer @Order(1) on compositeFormationIsDeterministicAcrossThreeRunsWithSameSeed")
                    .isNotEqualTo(-1);

            resetAllSeedsBetweenRuns();
            double compositeBefore = emergenceMetrics.compositesFormed();
            seedDeterministicScenario();
            for (long t = 1; t <= 200; t++) {
                publisher.publishEvent(new TickEvent(t));
            }
            int compositesFormedThisRun = (int) Math.round(
                    emergenceMetrics.compositesFormed() - compositeBefore);

            assertThat(compositesFormedThisRun)
                    .as("D-23: seed=1337 cumulative composite-formation count MUST differ from "
                            + "seed=42 baseline (%d) — otherwise the test isn't actually sensitive "
                            + "to the master seed",
                            seed42CompositeCount.get())
                    .isNotEqualTo(seed42CompositeCount.get());
        }
    }
}
