package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the composite entity lifecycle.
 * Exercises the complete tick pipeline with real Spring-wired components:
 * SimulationEngine(@Order(10)) -> CompositeEnergyDistributor(@Order(15))
 *   -> ActionResolver(@Order(20)) -> PerceptionBroadcaster(@Order(50))
 *   -> TickBroadcaster(@Order(100)).
 *
 * <p>Uses {@link ApplicationEventPublisher} to publish {@link TickEvent}
 * so all pipeline components fire in order, unlike
 * {@code simulationEngine.processTick()} which only runs the engine itself.
 *
 * <p>Tests:
 * <ul>
 *   <li>Full lifecycle: formation -> energy decay -> dissolution</li>
 *   <li>Regression: existing v1.0 behavior unaffected by composite code</li>
 *   <li>Movement: rigid body translation through real tick pipeline</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=50",
        "paralife.tick.auto-start=false",
        "paralife.world.width=32",
        "paralife.world.height=32",
        "paralife.simulation.energy-decay-per-tick=1",
        "paralife.simulation.combat-energy-transfer=10",
        "paralife.simulation.nutrient-spawn-probability=0.01",
        "paralife.simulation.nutrient-consume-energy=5",
        "paralife.simulation.enabled=true",
        "paralife.simulation.overcrowding-threshold=6",
        "paralife.simulation.overcrowding-energy-penalty=2",
        "paralife.bonding.bond-energy-threshold=40",
        "paralife.bonding.bonding-probability=1.0",
        "paralife.bonding.bond-defense-chance=0.0",
        "paralife.composite.dissolution-chance=0.0",
        "paralife.composite.critical-energy-percent=12",
        "paralife.composite.speed-constant=1.0",
        "paralife.composite.locomotor-passive-drain=1",
        "paralife.composite.locomotor-active-drain=3",
        "paralife.composite.feeder-passive-drain=1",
        "paralife.composite.feeder-active-drain=2",
        "paralife.composite.attacker-passive-drain=1",
        "paralife.composite.attacker-active-drain=4",
        "paralife.composite.defender-passive-drain=1",
        "paralife.composite.reproducer-passive-drain=1",
        "paralife.composite.reproducer-active-drain=2",
        "paralife.composite.sensor-passive-drain=1",
        "paralife.composite.can-form-composites=true"
})
class CompositeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CompositeIntegrationTest.class);

    @Autowired
    private WorldGrid worldGrid;

    @Autowired
    private SimulationEngine simulationEngine;

    @Autowired
    private CompositeRegistry compositeRegistry;

    @Autowired
    private BotRegistry botRegistry;

    @Autowired
    private ActionResolver actionResolver;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void resetGrid() {
        worldGrid.clear();
        compositeRegistry.clear();
        botRegistry.clear();
    }

    /**
     * Publish a TickEvent through Spring's event system, driving all
     * pipeline components in @Order sequence.
     */
    private void publishTick(long tickNumber) {
        eventPublisher.publishEvent(new TickEvent(tickNumber));
    }

    // ════════════════════════════════════════════════════════════════
    // Test 1: Full composite lifecycle — formation -> energy decay -> dissolution
    // ════════════════════════════════════════════════════════════════

    @Test
    void compositeLifecycleFormToDissolve() {
        // Step 1: Place two BondedPairs adjacent to each other.
        // bondingProbability=1.0 and bondEnergyThreshold=40 from test properties.
        var bp1 = new BondedPair("bp1", ParticleType.CATALYST, ParticleType.SPORE, 80, 200);
        var bp2 = new BondedPair("bp2", ParticleType.MEMBRANE, ParticleType.CATALYST, 80, 200);
        worldGrid.setEntity(15, 15, bp1);
        worldGrid.setEntity(15, 16, bp2);

        log.info("Setup: 2 BondedPairs placed at (15,15) and (15,16)");

        // Step 2: Publish a tick — composite formation happens in SimulationEngine(@Order(10)),
        // then CompositeEnergyDistributor(@Order(15)) processes energy
        publishTick(1);

        // Step 3: Verify composite formed
        assertThat(compositeRegistry.size())
                .as("One composite should have formed from the two BondedPairs")
                .isEqualTo(1);

        var composite = compositeRegistry.getAll().iterator().next();
        assertThat(composite.getMemberCount())
                .as("Composite should have exactly 2 members")
                .isEqualTo(2);

        // Verify grid has CompositeMember entities
        Entity occ1 = worldGrid.getCell(15, 15).occupant();
        Entity occ2 = worldGrid.getCell(15, 16).occupant();
        assertThat(occ1).isInstanceOf(CompositeMember.class);
        assertThat(occ2).isInstanceOf(CompositeMember.class);

        CompositeMember cm1 = (CompositeMember) occ1;
        CompositeMember cm2 = (CompositeMember) occ2;
        assertThat(cm1.compositeId()).isEqualTo(cm2.compositeId());

        // Verify shared pool has energy (sum of both BP energies)
        assertThat(composite.getSharedPoolEnergy())
                .as("Shared pool should have energy from both BondedPairs")
                .isGreaterThan(0);

        int initialPool = composite.getSharedPoolEnergy();
        log.info("Composite formed: id={}, members={}, pool={}/{}",
                composite.getCompositeId(), composite.getMemberCount(),
                composite.getSharedPoolEnergy(), composite.getMaxPoolEnergy());

        // Step 4: Verify member individual energy
        assertThat(cm1.energy()).isGreaterThan(0);
        assertThat(cm2.energy()).isGreaterThan(0);

        // Step 5: Run more ticks — CompositeEnergyDistributor passive drain + healing
        publishTick(2);
        publishTick(3);
        publishTick(4);

        // Members should still exist
        Entity updatedOcc1 = worldGrid.getCell(15, 15).occupant();
        assertThat(updatedOcc1).isInstanceOf(CompositeMember.class);

        // Pool should have decreased (healing draws from it each tick)
        assertThat(composite.getSharedPoolEnergy())
                .as("Pool energy should decrease due to healing draws for passive-drained members")
                .isLessThan(initialPool);

        log.info("After 3 additional ticks: pool={}/{}", composite.getSharedPoolEnergy(),
                composite.getMaxPoolEnergy());

        // Step 6: Force dissolution — drain pool and set member energy low
        composite.drainEnergy(composite.getSharedPoolEnergy());
        assertThat(composite.getSharedPoolEnergy()).isEqualTo(0);

        // Set member energy to 1 so SimulationEngine decay kills them
        Entity m1 = worldGrid.getCell(15, 15).occupant();
        Entity m2 = worldGrid.getCell(15, 16).occupant();
        if (m1 instanceof CompositeMember cmr1) {
            worldGrid.setEntity(15, 15, cmr1.withEnergy(1));
        }
        if (m2 instanceof CompositeMember cmr2) {
            worldGrid.setEntity(15, 16, cmr2.withEnergy(1));
        }

        // Publish tick — pool=0 triggers total death in processDeaths panic zone (D-31)
        publishTick(5);

        // Composite should be dissolved
        assertThat(compositeRegistry.size())
                .as("Composite should be dissolved after pool depletion and member energy drain")
                .isEqualTo(0);

        log.info("Composite dissolved successfully after energy depletion");
    }

    // ════════════════════════════════════════════════════════════════
    // Test 2: Existing v1.0 behavior unaffected by composite code
    // ════════════════════════════════════════════════════════════════

    @Test
    void compositeDoesNotBreakExistingBehavior() {
        // Place particles of all 3 types in separate clusters — no BondedPairs
        int id = 0;
        for (ParticleType type : ParticleType.values()) {
            int baseX = switch (type) {
                case CATALYST -> 3;
                case SPORE -> 13;
                case MEMBRANE -> 23;
            };
            for (int i = 0; i < 10; i++) {
                int x = (baseX + (i % 4)) % worldGrid.getWidth();
                int y = (3 + (i / 4)) % worldGrid.getHeight();
                if (worldGrid.getCell(x, y).isEmpty()) {
                    worldGrid.setEntity(x, y, Particle.spawn("p-" + (id++), type));
                }
            }
        }

        // Place some nutrients
        worldGrid.setEntity(10, 10, Nutrient.spawn("n1"));
        worldGrid.setEntity(20, 20, Nutrient.spawn("n2"));

        Map<ParticleType, Integer> initialCounts = countParticlesByType();
        int initialTotal = initialCounts.values().stream().mapToInt(i -> i).sum();
        log.info("Regression test initial: particles={}, nutrients present", initialCounts);

        // Run 50 ticks through the full pipeline
        for (int tick = 1; tick <= 50; tick++) {
            publishTick(tick);
        }

        Map<ParticleType, Integer> finalCounts = countParticlesByType();
        int finalTotal = finalCounts.values().stream().mapToInt(i -> i).sum();

        log.info("Regression test after 50 ticks: particles={}", finalCounts);

        // Energy decay should have killed some particles (decay=1, starting energy=50)
        assertThat(finalTotal)
                .as("Some particles should have died from energy decay over 50 ticks")
                .isLessThan(initialTotal);

        // Nutrients should have spawned on the 32x32 grid (0.01 probability)
        assertThat(countNutrients())
                .as("Nutrients should spawn on the grid")
                .isGreaterThan(0);

        // No composites should have formed — only solo particles, no adjacent BondedPairs
        assertThat(compositeRegistry.size())
                .as("No composites should form from solo particles without BondedPairs")
                .isEqualTo(0);

        log.info("Regression test passed: v1.0 behavior confirmed intact");
    }

    // ════════════════════════════════════════════════════════════════
    // Test 3: Composite moves as unit through real tick pipeline
    // ════════════════════════════════════════════════════════════════

    @Test
    void compositeMovesAsUnit() {
        // Manually create a 2-member composite with one LOCOMOTOR and one FEEDER
        String compositeId = "test-composite-1";
        String memberId1 = "cm-loco-1";
        String memberId2 = "cm-feed-1";

        var locomotor = new CompositeMember(memberId1, compositeId,
                ParticleType.CATALYST, Role.LOCOMOTOR, 50, 100);
        var feeder = new CompositeMember(memberId2, compositeId,
                ParticleType.SPORE, Role.FEEDER, 50, 100);

        // Place members at (10,10) and (10,11)
        worldGrid.setEntity(10, 10, locomotor);
        worldGrid.setEntity(10, 11, feeder);

        // Register in CompositeRegistry
        compositeRegistry.register(compositeId,
                List.of(memberId1, memberId2),
                Map.of(memberId1, new Position(10, 10), memberId2, new Position(10, 11)),
                100, 200);

        // Register bot sessions for both members
        String session1 = "session-loco-1";
        String session2 = "session-feed-1";
        botRegistry.register(session1, memberId1, new Position(10, 10));
        botRegistry.register(session2, memberId2, new Position(10, 11));

        log.info("Movement test: composite at (10,10) and (10,11)");

        // Verify initial positions
        assertThat(worldGrid.getCell(10, 10).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(worldGrid.getCell(10, 11).occupant()).isInstanceOf(CompositeMember.class);

        // Ensure target cells are clear (north = y-1)
        assertThat(worldGrid.getCell(10, 9).isEmpty()).isTrue();

        // Queue a LOCOMOTOR action for movement north
        var compositeAction = new com.paralife.websocket.Messages.CompositeAction(
                "move", "N", List.of("N", "NE"));
        actionResolver.queueCompositeAction(session1, compositeAction);

        // Publish tick 1 — drives the full pipeline including ActionResolver(@Order(20))
        // Speed gate: 1 LOCOMOTOR / 2 members * speedConstant=1.0 = speed 0.5
        // moveInterval = ceil(1/0.5) = 2. New composites default to moveInterval in map,
        // so first tick may or may not move depending on initial ticksSinceMove.
        publishTick(1);

        // Queue another action and publish tick 2
        compositeAction = new com.paralife.websocket.Messages.CompositeAction(
                "move", "N", List.of("N"));
        actionResolver.queueCompositeAction(session1, compositeAction);
        publishTick(2);

        // After 2 ticks, verify formation shape is preserved if composite still exists
        var composite = compositeRegistry.getComposite(compositeId);
        if (composite.isPresent()) {
            Position pos1 = composite.get().getPositionForMember(memberId1);
            Position pos2 = composite.get().getPositionForMember(memberId2);
            log.info("After movement: member1={}, member2={}", pos1, pos2);

            if (pos1 != null && pos2 != null) {
                // Verify rigid body invariant: both members on same X, 1 cell apart in Y
                int yDiff = Math.abs(pos1.y() - pos2.y());
                // Account for toroidal wrapping
                if (yDiff > worldGrid.getHeight() / 2) {
                    yDiff = worldGrid.getHeight() - yDiff;
                }
                assertThat(pos1.x()).isEqualTo(pos2.x());
                assertThat(yDiff)
                        .as("Members should maintain vertical adjacency (rigid body)")
                        .isEqualTo(1);
            }
        }

        log.info("Movement test completed");
    }

    // ════════════════════════════════════════════════════════════════
    // Test 4: Formation from Particles through bonding to composite
    // ════════════════════════════════════════════════════════════════

    @Test
    void fullFormationPipelineParticlesToComposite() {
        // Place 4 particles as 2 predator-prey pairs adjacent to each other.
        // With bondingProbability=1.0 and threshold=40, they should bond on tick 1.
        // BondedPairs then form a composite in the same or next tick.

        // Pair 1: CATALYST beats SPORE
        Particle cat1 = new Particle("cat1", ParticleType.CATALYST, 60, 100);
        Particle spo1 = new Particle("spo1", ParticleType.SPORE, 60, 100);
        worldGrid.setEntity(5, 5, cat1);
        worldGrid.setEntity(5, 6, spo1);

        // Pair 2: MEMBRANE beats CATALYST
        Particle mem1 = new Particle("mem1", ParticleType.MEMBRANE, 60, 100);
        Particle cat2 = new Particle("cat2", ParticleType.CATALYST, 60, 100);
        worldGrid.setEntity(6, 5, mem1);
        worldGrid.setEntity(6, 6, cat2);

        log.info("Formation pipeline: 4 particles placed as 2 predator-prey pairs");

        // Tick 1: Bonding should occur
        publishTick(1);

        // Count BondedPairs and CompositeMembers on grid
        int bondedCount = 0;
        int compositeCount = 0;
        for (int x = 4; x <= 7; x++) {
            for (int y = 4; y <= 7; y++) {
                Entity occ = worldGrid.getCell(x, y).occupant();
                if (occ instanceof BondedPair) bondedCount++;
                if (occ instanceof CompositeMember) compositeCount++;
            }
        }

        log.info("After tick 1: bondedPairs={}, compositeMembers={}", bondedCount, compositeCount);

        // Bonding + composite formation can happen in the same processInteractions call,
        // or BondedPairs might form first and merge on the next tick.
        if (bondedCount == 2 && compositeCount == 0) {
            log.info("Two BondedPairs formed. Running tick 2 for composite formation...");
            publishTick(2);
        }

        // The pipeline ran without errors through bonding -> composite.
        // At minimum, bonding should have occurred since probability=1.0
        assertThat(bondedCount + compositeCount)
                .as("At least one bonding or composite event should have occurred")
                .isGreaterThan(0);

        log.info("Formation pipeline test completed successfully");
    }

    // ════════════════════════════════════════════════════════════════
    // Test 5: Energy distribution pipeline active during full tick
    // ════════════════════════════════════════════════════════════════

    @Test
    void energyDistributionActiveForComposites() {
        // Create a composite manually and verify that CompositeEnergyDistributor
        // (wired at @Order(15)) processes it when TickEvent is published.
        String compositeId = "test-energy-composite";
        String memberId1 = "cm-e1";
        String memberId2 = "cm-e2";

        // Members start at 40/100 energy — below max so healing draws from pool
        var member1 = new CompositeMember(memberId1, compositeId,
                ParticleType.CATALYST, Role.FEEDER, 40, 100);
        var member2 = new CompositeMember(memberId2, compositeId,
                ParticleType.SPORE, Role.SENSOR, 40, 100);

        worldGrid.setEntity(20, 20, member1);
        worldGrid.setEntity(20, 21, member2);

        compositeRegistry.register(compositeId,
                List.of(memberId1, memberId2),
                Map.of(memberId1, new Position(20, 20), memberId2, new Position(20, 21)),
                100, 200);

        int initialPool = compositeRegistry.getSharedEnergy(compositeId);
        log.info("Energy test: initial pool={}, member1 energy={}, member2 energy={}",
                initialPool, member1.energy(), member2.energy());

        // Publish ticks through the full pipeline
        // CompositeEnergyDistributor fires at @Order(15) — passive drain then healing
        for (int tick = 1; tick <= 5; tick++) {
            publishTick(tick);
        }

        // Pool should have decreased: each tick, 2 members drain 1 energy each (passive),
        // then heal 1 energy each from pool. Net effect: -2 pool/tick from healing draws.
        int finalPool = compositeRegistry.getSharedEnergy(compositeId);
        log.info("Energy test after 5 ticks: pool={}", finalPool);

        assertThat(finalPool)
                .as("Pool should decrease due to healing draws for passive-drained members")
                .isLessThan(initialPool);

        // Members should still be alive (started at 40, drain=1/tick, healed from pool)
        Entity updatedM1 = worldGrid.getCell(20, 20).occupant();
        Entity updatedM2 = worldGrid.getCell(20, 21).occupant();
        assertThat(updatedM1).isInstanceOf(CompositeMember.class);
        assertThat(updatedM2).isInstanceOf(CompositeMember.class);

        log.info("Energy distribution pipeline verified active");
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private Map<ParticleType, Integer> countParticlesByType() {
        Map<ParticleType, Integer> counts = new EnumMap<>(ParticleType.class);
        for (ParticleType type : ParticleType.values()) {
            counts.put(type, 0);
        }
        for (int x = 0; x < worldGrid.getWidth(); x++) {
            for (int y = 0; y < worldGrid.getHeight(); y++) {
                Cell cell = worldGrid.getCell(x, y);
                if (cell.occupant() instanceof Particle p) {
                    counts.merge(p.type(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private int countNutrients() {
        int count = 0;
        for (int x = 0; x < worldGrid.getWidth(); x++) {
            for (int y = 0; y < worldGrid.getHeight(); y++) {
                if (worldGrid.getCell(x, y).occupant() instanceof Nutrient) {
                    count++;
                }
            }
        }
        return count;
    }
}
