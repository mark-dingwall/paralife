package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.engine.BuffRegistry.ActiveBuff;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.WorldGrid;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Plan 14-06 Task 3b (cycle-9 action D — Codex HIGH from cycle 8): the
 * ROADMAP-LITERAL phase-gate deliverable.
 *
 * <p>ROADMAP.md Phase 14 14-06 success criterion:
 * "Integration test: 300-tick seeded full-stack validation of all four effects +
 *  buff grants."
 *
 * <p>This test:
 * <ol>
 *   <li>Loads the FULL production pipeline via {@code @SpringBootTest} —
 *       SimulationEngine @Order(10), EnvironmentEngine @Order(14),
 *       CompositeEnergyDistributor @Order(15), ActionResolver @Order(20),
 *       EnvPostActionReconciler @Order(25), TickBroadcaster @Order(50).</li>
 *   <li>Seeds a realistic starting population of ~30 Particles + 5 BondedPairs.</li>
 *   <li>Drives 300 ticks via {@link ApplicationEventPublisher#publishEvent} —
 *       no {@code Thread.sleep}.</li>
 *   <li>Asserts ALL four env-effect counters fired (toxin, mutagen, lightning, compost).</li>
 *   <li>Asserts &ge;1 buff was granted at some point during the run.</li>
 * </ol>
 *
 * <p><b>Why {@code @Tag("slow")} (opt-in, not default-gated):</b> "all four
 * effects fire + a buff is granted in a stochastic full-stack run" is emergence,
 * not mechanism. Effect firing rides on seasonal Poisson rolls; mutagen infection
 * and the survivor-buff chain additionally need an entity to be standing on the
 * bloom and to survive a cure — all seed- and layout-sensitive. No lambda makes
 * that deterministic (verified: distinct seeds still miss at elevated lambdas).
 * Pinning a seed that happens to fire would be the anti-pattern the firewall bans
 * from the default gate, so this run lives in the opt-in suite instead. Each
 * effect's MECHANISM is default-gated deterministically elsewhere
 * ({@code ToxinTest}, {@code MutagenTest}, {@code LightningTest},
 * {@code CompostTest}); {@link EnvironmentDeterminismTest} covers env-engine
 * determinism. Seed 42 pins one representative firing run for this opt-in check.
 */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Env-engine RNG seed — pins one representative firing run for this opt-in
        // (@slow) emergence check. See the class javadoc for why firing can't be
        // made seed-independent here (so this is not a default-gated test).
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.enabled=true",
        // Full-year cycle within 300 ticks — all four seasons engaged.
        "paralife.simulation.seasons.year-length-ticks=300",
        // NOT production values (0.04/0.03/0.02). Elevated ~8-15x so the pinned
        // seed fires all four within 300 ticks; the population band these once had
        // to stay survivable for is gone, so 'apocalyptic' lambdas are fine here.
        "paralife.simulation.events.lightning.peak-lambda=0.30",
        "paralife.simulation.events.toxin.peak-lambda=0.30",
        "paralife.simulation.events.mutagen.peak-lambda=0.30",
        "paralife.tick.auto-start=false"
})
class EnvironmentPhaseGateIntegrationTest {

    private static final int TICKS = 300;

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired BuffRegistry buffRegistry;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired DeathFinalizer deathFinalizer;
    @Autowired EnvCleanupHooksBean envCleanupHooksBean;
    @Autowired ApplicationEventPublisher publisher;

    @Test
    void phaseGate300TickSeededFullStackValidation() {
        // ===== PREP =====
        resetAll();

        int startingPopulation = seedInitialPopulation();
        assertThat(startingPopulation)
                .as("cycle-9 action D: must seed a realistic starting population")
                .isGreaterThan(20);

        int maxBuffsObserved = 0;

        // ===== DRIVE =====
        for (long tick = 1; tick <= TICKS; tick++) {
            publisher.publishEvent(new TickEvent(tick));

            // Sample buff-count observation once per tick. Some ticks have 0
            // buffs (pre-infection); some have many (post-cure-wave). Track
            // max observed.
            int buffCountAtTick = countActiveBuffs();
            if (buffCountAtTick > maxBuffsObserved) {
                maxBuffsObserved = buffCountAtTick;
            }
        }

        // ===== ASSERT =====

        // (i) All four env effects fired.
        assertThat(environmentEngine.getToxinEventCount())
                .as("cycle-9 action D: at least one toxin event in %d ticks", TICKS)
                .isGreaterThan(0);
        assertThat(environmentEngine.getMutagenInfectionEventCount())
                .as("cycle-9 action D: at least one mutagen infection event in %d ticks", TICKS)
                .isGreaterThan(0);
        assertThat(environmentEngine.getLightningStrikeEventCount())
                .as("cycle-9 action D: at least one lightning strike in %d ticks", TICKS)
                .isGreaterThan(0);
        assertThat(environmentEngine.getCompostEventCount())
                .as("cycle-9 action D: at least one compost event in %d ticks (death-sourced)", TICKS)
                .isGreaterThan(0);

        // (ii) At least one buff was granted.
        assertThat(maxBuffsObserved)
                .as("cycle-9 action D: at least one buff granted during %d-tick run "
                        + "(mutagen survivor path)", TICKS)
                .isGreaterThan(0);

    }

    private void resetAll() {
        worldGrid.clear();
        environmentEngine.resetForTest();
        buffRegistry.clear();
        compositeRegistry.clear();
        botRegistry.clear();
        deathFinalizer.resetCountForTest();
    }

    /**
     * Seeds ~30 Particles + 5 BondedPairs. Returns total starting population.
     *
     * <p>Particles are mixed across CATALYST / MEMBRANE / SPORE for RPS dynamics.
     * BondedPairs are seeded via direct grid placement (no randomized formation
     * path) to ensure a controlled starting state. Uses a fresh Random(42L) for
     * position selection so the initial layout is reproducible even though
     * subsequent ThreadLocalRandom-driven behavior varies.
     */
    private int seedInitialPopulation() {
        Random layoutRng = new Random(42L);
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        int count = 0;

        // 30 Particles spread roughly evenly. Starting energy + max-energy set
        // high enough to survive 300 ticks of decay (max observed decay rate is
        // 3/tick for CATALYST, so 1000 energy = 333 ticks of pure decay). Bots
        // are NOT connected in this test, so entities cannot consume nutrients
        // to replenish — high starting energy keeps metabolic starvation from
        // obscuring the environment-effect exercise.
        ParticleType[] types = ParticleType.values();
        int particleTarget = 30;
        int attempts = 0;
        int maxAttempts = particleTarget * 20;
        while (count < particleTarget && attempts < maxAttempts) {
            attempts++;
            int x = layoutRng.nextInt(w);
            int y = layoutRng.nextInt(h);
            if (!worldGrid.getCell(x, y).isEmpty()) continue;
            ParticleType type = types[count % types.length];
            Particle p = new Particle("seed-p-" + count, type, 1000, 1000);
            worldGrid.setEntity(x, y, p);
            count++;
        }

        // 5 BondedPairs — place directly (bypass randomized formation path for
        // test determinism). Same high-energy rationale as Particles.
        int bpCount = 0;
        attempts = 0;
        while (bpCount < 5 && attempts < 100) {
            attempts++;
            int x = layoutRng.nextInt(w);
            int y = layoutRng.nextInt(h);
            if (!worldGrid.getCell(x, y).isEmpty()) continue;
            BondedPair bp = new BondedPair("seed-bp-" + bpCount,
                    ParticleType.CATALYST, ParticleType.MEMBRANE,
                    1500, 1500,
                    "seed-bp-" + bpCount + "-a", "seed-bp-" + bpCount + "-b");
            worldGrid.setEntity(x, y, bp);
            bpCount++;
            count++;
        }

        return count;
    }

    /** Total active buff count across all entities in the registry. */
    private int countActiveBuffs() {
        int total = 0;
        for (String entityId : buffRegistry.getRegisteredEntityIds()) {
            List<ActiveBuff> buffs = buffRegistry.getBuffs(entityId);
            if (buffs != null) total += buffs.size();
        }
        return total;
    }

}
