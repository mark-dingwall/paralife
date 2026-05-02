package com.paralife.metrics;

import com.paralife.engine.BondingConfig;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.BuffRegistry.BuffType;
import com.paralife.engine.EnvCleanupHooksBean;
import com.paralife.engine.EnvironmentConfig;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.engine.SimulationEngine;
import com.paralife.engine.TickEvent;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 16-02 Task 3: end-to-end wiring test for {@link EmergenceMetrics}. Drives
 * every counter through its REAL production call path — never calls
 * {@code metrics.inc*()} directly.
 *
 * <p>Addresses PATTERNS.md line 612 anti-pattern (bean-priming tests look wired
 * but prove nothing) AND REVIEWS HIGH #3 regression — validates that
 * {@link BuffRegistry#transferBuffs(String, String)} does NOT bump
 * {@link EmergenceMetrics#bondedPairsFormed()} or
 * {@link EmergenceMetrics#buffsGrantedCount()} even though it internally calls
 * {@link BuffRegistry#grant(String, BuffType, long)}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        // Force bonding — probability=1.0 and threshold=0 guarantee every
        // predator-prey adjacency pair becomes a BondedPair on a single tick.
        "paralife.bonding.bonding-probability=1.0",
        "paralife.bonding.bond-energy-threshold=0",
        // Silence noisy tick-pipeline components — only env + interactions matter.
        "paralife.simulation.enabled=true",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.mutagen.damage-per-tick=0",
        "paralife.simulation.events.mutagen.infection-duration-min=3",
        "paralife.simulation.events.mutagen.infection-duration-max=3",
        "paralife.simulation.events.mutagen.buff-duration-multiplier=5",
        "paralife.simulation.events.mutagen.cure-ticks=3",
        "paralife.simulation.events.mutagen.gossip-probability=0.0",
        "paralife.simulation.events.mutagen.strain-mutation-chance=0.0",
        // Don't spawn random toxin/mutagen/lightning events — we stamp manually.
        "paralife.simulation.events.toxin.peak-lambda=0.0",
        "paralife.simulation.events.toxin.off-season-lambda=0.0",
        "paralife.simulation.events.mutagen.peak-lambda=0.0",
        "paralife.simulation.events.mutagen.off-season-lambda=0.0",
        "paralife.simulation.events.lightning.peak-lambda=0.0",
        "paralife.simulation.events.lightning.off-season-lambda=0.0"
})
class EmergenceMetricsWiringTest {

    @Autowired WorldGrid worldGrid;
    @Autowired BuffRegistry buffRegistry;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired EnvCleanupHooksBean envCleanupHooksBean;
    @Autowired SimulationEngine simulationEngine;
    @Autowired EmergenceMetrics metrics;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired BondingConfig bondingConfig;
    @Autowired EnvironmentConfig environmentConfig;
    /** Phase 19 Plan 04: must be cleared between tests so entitySnapshot fallback is consistent. */
    @Autowired LiveEntityRegistry liveEntityRegistry;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        buffRegistry.clear();
        liveEntityRegistry.clearForTest();
        environmentEngine.resetForTest();
    }

    @AfterEach
    void tearDown() {
        worldGrid.clear();
        buffRegistry.clear();
        liveEntityRegistry.clearForTest();
        environmentEngine.resetForTest();
    }

    @Test
    void bondedPairCounterIncrementsOnRealBondFormation() {
        // Fail-fast config binding assertion: kebab-case properties MUST bind
        // to the record's camelCase accessors. A silent bind failure here would
        // make every subsequent bonding assertion vacuously true (TLR default).
        assertThat(bondingConfig.bondingProbability())
                .as("bond-probability binding failed — check @TestPropertySource key matches kebab-case binder")
                .isEqualTo(1.0);
        assertThat(bondingConfig.bondEnergyThreshold())
                .as("bond-energy-threshold binding failed")
                .isZero();

        Counter counter = meterRegistry.find(EmergenceMetrics.M_BONDED_PAIRS).counter();
        assertThat(counter).as("bonded pairs counter registered").isNotNull();
        double before = counter.count();

        // CATALYST beats SPORE (RPS). Place adjacent — single tick, predator
        // attacks prey, bonding-probability=1.0 forces BondFormation outcome.
        Particle cat = new Particle("wiring-cat", ParticleType.CATALYST, 80, 100);
        Particle spo = new Particle("wiring-spo", ParticleType.SPORE, 80, 100);
        worldGrid.setEntity(3, 3, cat);
        liveEntityRegistry.register("wiring-cat", new Position(3, 3));
        worldGrid.setEntity(3, 4, spo);
        liveEntityRegistry.register("wiring-spo", new Position(3, 4));

        publisher.publishEvent(new TickEvent(1L));

        assertThat(counter.count())
                .as("bonded-pair counter must be non-vacuous (> 0)")
                .isGreaterThan(before);
    }

    @Test
    void compositeCounterIncrementsOnRealCompositeFormation() {
        Counter counter = meterRegistry.find(EmergenceMetrics.M_COMPOSITES).counter();
        assertThat(counter).as("composites counter registered").isNotNull();
        double before = counter.count();

        // Two adjacent BondedPairs → composite formation (mirrors
        // CompositeFormationTest.adjacentBondedPairsFormComposite).
        BondedPair bp1 = new BondedPair("wiring-bp1",
                ParticleType.CATALYST, ParticleType.SPORE, 80, 200, "c1", "s1");
        BondedPair bp2 = new BondedPair("wiring-bp2",
                ParticleType.CATALYST, ParticleType.SPORE, 80, 200, "c2", "s2");
        worldGrid.setEntity(5, 5, bp1);
        liveEntityRegistry.register("wiring-bp1", new Position(5, 5));
        worldGrid.setEntity(5, 6, bp2);
        liveEntityRegistry.register("wiring-bp2", new Position(5, 6));

        publisher.publishEvent(new TickEvent(2L));

        assertThat(counter.count())
                .as("composite counter must be non-vacuous (> 0)")
                .isGreaterThan(before);
    }

    @Test
    void buffCounterIncrementsOnlyOnNewBuffViaGrantSurvivorBuffs() {
        // CRITICAL — this test validates REVIEWS HIGH #3 fix: counter lives in
        // EnvironmentEngine.grantSurvivorBuffs via size-diff detection, NOT in
        // BuffRegistry.grant (which is also called by transferBuffs → would
        // over-count identity transfers as emergence).
        Counter counter = meterRegistry.find(EmergenceMetrics.M_BUFFS_GRANTED).counter();
        assertThat(counter).as("buffs granted counter registered").isNotNull();

        // Drive the full mutagen infection → cure → buff-grant pipeline by
        // stamping a mutagen zone, placing an entity on it, and cycling
        // resolveMutagenCollisions + tickBuffsAndInfections for enough ticks to
        // expire the infection (duration=3) and trigger grantSurvivorBuffs.
        Particle p = new Particle("wiring-entity-1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(7, 7, p);
        liveEntityRegistry.register("wiring-entity-1", new Position(7, 7));
        environmentEngine.stampMutagenAtForTestPublic(new Position(7, 7), 100);

        double beforeInfection = counter.count();
        environmentEngine.resolveMutagenCollisionsForTestPublic(0L);
        // Infection runs for 3 ticks (min=max=3). Cycle tickBuffsAndInfections
        // until it expires and processPendingGrants fires grantSurvivorBuffs.
        for (int t = 1; t <= 4; t++) {
            environmentEngine.tickBuffsAndInfectionsForTestPublic(t);
        }

        double afterFirstGrant = counter.count();
        assertThat(afterFirstGrant)
                .as("buff-granted counter must bump on first grantSurvivorBuffs (non-vacuous)")
                .isGreaterThan(beforeInfection);

        // REVIEWS HIGH #3 regression — transferBuffs reaches BuffRegistry.grant
        // internally, but placement in grantSurvivorBuffs means the counter
        // stays flat.
        double beforeTransfer = counter.count();
        buffRegistry.transferBuffs("wiring-entity-1", "wiring-entity-2");
        assertThat(counter.count())
                .as("REVIEWS HIGH #3: BuffRegistry.transferBuffs must NOT count as emergence")
                .isEqualTo(beforeTransfer);

        // Refresh branch — granting the same buff type on the same entity
        // should also not bump (BuffRegistry.grant takes the list.set path,
        // size stays the same).
        double beforeRefresh = counter.count();
        // Pick any existing buff type on the transferred-to entity.
        if (!buffRegistry.getBuffs("wiring-entity-2").isEmpty()) {
            BuffType existing = buffRegistry.getBuffs("wiring-entity-2").get(0).type();
            buffRegistry.grant("wiring-entity-2", existing, 10_000L);
        }
        assertThat(counter.count())
                .as("refresh branch (list.set) must NOT count as new emergence")
                .isEqualTo(beforeRefresh);
    }

    @Test
    void infectionCounterIncrementsOnRealInfectionStart() {
        Counter counter = meterRegistry.find(EmergenceMetrics.M_INFECTIONS).counter();
        assertThat(counter).as("infections counter registered").isNotNull();
        double before = counter.count();

        // Stamp mutagen directly at the entity cell then drive
        // resolveMutagenCollisions (the real production code path — Env's
        // @EventListener also calls this).
        Particle p = new Particle("wiring-infectee", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(9, 9, p);
        liveEntityRegistry.register("wiring-infectee", new Position(9, 9));
        environmentEngine.stampMutagenAtForTestPublic(new Position(9, 9), 100);

        environmentEngine.resolveMutagenCollisionsForTestPublic(0L);

        assertThat(counter.count())
                .as("infection counter must be non-vacuous (> 0)")
                .isGreaterThan(before);
    }
}
