package com.paralife.engine;

import com.paralife.engine.EnvCleanupHooksBean.PendingGrant;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 14-06 Task 2: engine-only deterministic harness. Runs a 300-tick seeded
 * env-only simulation TWICE within the same JVM, resetting all stateful
 * collaborators between runs. Asserts every observable is equal across runs —
 * including grid-scale nutrient totals (cycle-4 action item #9 Codex MEDIUM).
 *
 * <p><b>cycle-6 HIGH #4 PARTICLE-ONLY CONSTRAINT:</b> seed registers Particle
 * entities only. {@link #driveRun} asserts
 * {@code compositeRegistry.getAll().isEmpty()} at start. If a future maintainer
 * adds composites to seed, the guard fails loudly — preventing silent
 * non-deterministic regression via
 * {@code SimulationEngine.handleMemberDeath → ThreadLocalRandom}.
 *
 * <p><b>Scope:</b> this is SUPPLEMENTAL to the roadmap-literal
 * {@link EnvironmentPhaseGateIntegrationTest}. This harness guards env-engine
 * determinism at the {@link EnvironmentEngine#onTickEnvOnlyForTest} boundary;
 * it does NOT drive {@code SimulationEngine.processInteractions}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.seasons.year-length-ticks=100",
        "paralife.simulation.events.lightning.peak-lambda=0.06",
        "paralife.tick.auto-start=false"
})
class EnvironmentDeterminismTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired EnvironmentConfig environmentConfig;
    @Autowired BuffRegistry buffRegistry;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired DeathFinalizer deathFinalizer;
    @Autowired EnvCleanupHooksBean envCleanupHooksBean;

    private static final class RunObservables {
        int toxinEvents;
        int mutagenEvents;
        long lightningStrikes;
        long deathEvents;
        int infectionsAtTick150;
        boolean everAnyStatus;
        /** cycle-4 action item #9 (Codex MEDIUM): fertility invariant. */
        int totalNutrients;
    }

    @Test
    void envOnlyObservablesFireDuringSingleRun() {
        resetAll();
        RunObservables obs = driveRun();
        assertThat(obs.toxinEvents)
                .as("at least one toxin event in 300 seeded env ticks")
                .isGreaterThanOrEqualTo(1);
        assertThat(obs.mutagenEvents)
                .as("at least one mutagen event")
                .isGreaterThanOrEqualTo(1);
        assertThat(obs.lightningStrikes)
                .as("at least one lightning strike")
                .isGreaterThanOrEqualTo(1L);
        assertThat(obs.everAnyStatus)
                .as("env-only path populated at least one status cache byte")
                .isTrue();
        assertThat(obs.totalNutrients)
                .as("cycle-4 action item #9: compost + lightning mutated nutrient levels — "
                        + "otherwise the invariant is unmeasurable")
                .isGreaterThan(0);
    }

    @Test
    void envOnlyRunsAreDeterministicAcrossTwoInvocations() {
        resetAll();
        RunObservables a = driveRun();

        resetAll();
        RunObservables b = driveRun();

        assertThat(b.toxinEvents).as("toxinEvents deterministic").isEqualTo(a.toxinEvents);
        assertThat(b.mutagenEvents).as("mutagenEvents deterministic").isEqualTo(a.mutagenEvents);
        assertThat(b.lightningStrikes).as("lightningStrikes deterministic").isEqualTo(a.lightningStrikes);
        assertThat(b.deathEvents).as("deathEvents deterministic").isEqualTo(a.deathEvents);
        assertThat(b.infectionsAtTick150).as("infections snapshot deterministic")
                .isEqualTo(a.infectionsAtTick150);
        assertThat(b.totalNutrients)
                .as("cycle-4 action item #9 (Codex MEDIUM): totalNutrients MUST be equal across runs — "
                        + "guards compost + lightning fertility drift")
                .isEqualTo(a.totalNutrients);
    }

    private void resetAll() {
        // cycle-4 action item #1 + cycle-6 MEDIUM: use worldGrid.clear() (full wipe)
        // NOT worldGrid.clearEntity(...) — the latter preserves nutrients + flags
        // which would leak compost + lightning fertility state across runs.
        worldGrid.clear();
        environmentEngine.resetForTest();
        buffRegistry.clear();
        compositeRegistry.clear();
        botRegistry.clear();
        deathFinalizer.resetCountForTest();
    }

    private RunObservables driveRun() {
        // cycle-6 HIGH #4 GUARD: this harness is particle-only. Fail loudly if
        // composites have been seeded — SimulationEngine.handleMemberDeath uses
        // ThreadLocalRandom and would break determinism.
        assertThat(compositeRegistry.getAll())
                .as("cycle-6 HIGH #4: EnvironmentDeterminismTest harness is PARTICLE-ONLY. "
                        + "Composites routed through SimulationEngine.handleMemberDeath which uses "
                        + "ThreadLocalRandom — seeding composites breaks determinism. Remove composites "
                        + "from seedInitialPopulation() or inject deterministic Random under a test flag.")
                .isEmpty();

        RunObservables obs = new RunObservables();
        seedInitialPopulation(60);   // Particle-only — cycle-6 HIGH #4
        long lightningBaseline = environmentEngine.lightningStrikeCount();
        long deathBaseline = deathFinalizer.getDeathEventCount();

        boolean toxinPrev = false, mutagenPrev = false;
        for (long tick = 1; tick <= 300; tick++) {
            environmentEngine.onTickEnvOnlyForTest(tick);

            boolean toxinActive = environmentEngine.activeToxinEvent() != null;
            boolean mutagenActive = environmentEngine.activeMutagenEvent() != null;
            if (toxinActive && !toxinPrev) obs.toxinEvents++;
            if (mutagenActive && !mutagenPrev) obs.mutagenEvents++;
            toxinPrev = toxinActive;
            mutagenPrev = mutagenActive;

            if (!obs.everAnyStatus && anyNonZeroStatus()) obs.everAnyStatus = true;

            if (tick == 150) {
                obs.infectionsAtTick150 = infectionsSnapshotCount();
            }
        }

        obs.lightningStrikes = environmentEngine.lightningStrikeCount() - lightningBaseline;
        obs.deathEvents = deathFinalizer.getDeathEventCount() - deathBaseline;
        obs.totalNutrients = environmentEngine.totalNutrients();
        return obs;
    }

    /**
     * cycle-6 HIGH #4: seed Particle entities only. No BondedPair (may consume
     * via bonding). No CompositeMember. If this constraint is violated, the
     * guard assertion in {@link #driveRun()} fails loudly.
     *
     * <p>Uses a test-local Random(42L) — same seed as env engine — so the
     * layout is reproducible across reset + driveRun invocations.
     */
    private void seedInitialPopulation(int count) {
        Random layoutRng = new Random(42L);
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        ParticleType[] types = ParticleType.values();
        int placed = 0;
        int attempts = 0;
        int maxAttempts = count * 10;
        while (placed < count && attempts < maxAttempts) {
            attempts++;
            int x = layoutRng.nextInt(w);
            int y = layoutRng.nextInt(h);
            if (!worldGrid.getCell(x, y).isEmpty()) continue;
            ParticleType type = types[placed % types.length];
            Particle p = new Particle("seed-p-" + placed, type, 80, 100);
            worldGrid.setEntity(x, y, p);
            placed++;
        }
    }

    private int infectionsSnapshotCount() {
        return envCleanupHooksBean.getInfections().size();
    }

    /**
     * True if either status cache has any non-zero byte. Uses the package-private
     * views on EnvironmentEngine.
     */
    private boolean anyNonZeroStatus() {
        for (Byte b : environmentEngine.cellStatusCacheView().values()) {
            if (b != null && b != 0) return true;
        }
        for (Byte b : environmentEngine.entityStatusCacheView().values()) {
            if (b != null && b != 0) return true;
        }
        return false;
    }

    // ── Phase 19.1 D-04 multi-cure determinism fixture ──────────────

    /**
     * Phase 19.1 D-04: same-tick multi-cure buff outcomes must be byte-exact
     * across two same-seed runs. Before the fix, CHM iteration order in
     * {@code processPendingGrants} produced non-deterministic RNG consumption.
     */
    @Test
    @DisplayName("F4 — same-tick multi-cure produces byte-exact buff state across same-seed runs (D-04)")
    void multiCureSameSeedDeterminism() {
        Map<String, List<BuffRegistry.ActiveBuff>> run1 = driveMultiCureScenario();
        Map<String, List<BuffRegistry.ActiveBuff>> run2 = driveMultiCureScenario();
        assertThat(run2)
                .as("Phase 19.1 D-04 — pendingGrants must be sorted before randomBuff(). "
                        + "If this fails, processPendingGrants is consuming RNG in CHM order.")
                .isEqualTo(run1);
    }

    /**
     * Drives the multi-cure scenario: place 5 Particle entities, enqueue one
     * PendingGrant for each in the same tick, run 300 ticks, return the sorted
     * buff snapshot.
     *
     * <p>resetAll() brings EnvironmentEngine.rng back to seed 42, so two consecutive
     * calls produce identical Poisson roll sequences — the ONLY variable is whether
     * processPendingGrants iterates in a stable sorted order.
     */
    private Map<String, List<BuffRegistry.ActiveBuff>> driveMultiCureScenario() {
        resetAll();

        // Place 5 particles at distinct positions
        String[] ids = {"mc-e1", "mc-e2", "mc-e3", "mc-e4", "mc-e5"};
        Position[] positions = {
                new Position(1, 1), new Position(2, 3), new Position(5, 7),
                new Position(10, 12), new Position(20, 20)
        };
        for (int i = 0; i < ids.length; i++) {
            worldGrid.setEntity(positions[i].x(), positions[i].y(),
                    new Particle(ids[i], ParticleType.CATALYST, 80, 100));
        }

        // Enqueue one PendingGrant per entity directly into envCleanupHooksBean
        // (the same path tickBuffsAndInfections uses). initialTicks=10 for all
        // so the comparator's entityId key differentiates them.
        for (int i = 0; i < ids.length; i++) {
            Entity occ = worldGrid.getCell(positions[i].x(), positions[i].y()).occupant();
            envCleanupHooksBean.addPendingGrant(
                    new PendingGrant(ids[i], 10, occ, positions[i]));
        }

        // Tick 1 will drain pendingGrants and call randomBuff() for each entity
        for (long tick = 1; tick <= 300; tick++) {
            environmentEngine.onTickEnvOnlyForTest(tick);
        }

        // Capture sorted buff snapshot: entityId -> sorted list of (buffType, expiryTick)
        Map<String, List<BuffRegistry.ActiveBuff>> snapshot = new TreeMap<>();
        for (String id : ids) {
            List<BuffRegistry.ActiveBuff> buffs = buffRegistry.getBuffs(id).stream()
                    .sorted(Comparator.comparing(b -> b.type().name()))
                    .collect(Collectors.toList());
            snapshot.put(id, buffs);
        }
        return snapshot;
    }
}
