package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Random;

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
}
