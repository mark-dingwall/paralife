package com.paralife.engine;

import com.paralife.codec.Frame;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Rock;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19 SCALE-07 invariant integration tests — LiveEntityRegistry positions
 * match non-rock/non-nutrient occupied grid cells after every lifecycle event.
 *
 * <p>REVIEWS MEDIUM-4: post-bond-formation and post-composite-formation scenarios
 * are MANDATORY. No @Disabled escape.
 *
 * <p>REVIEWS CONSENSUS-H1 OPTION B (USER-LOCKED): TickBroadcaster is NOT migrated
 * in Phase 19. The {@code sessionIdAgreesWithBotRegistry} assertion is DROPPED —
 * TickBroadcaster does not consume sessionId in Phase 19.
 *
 * <p>Invariant checked by {@link #assertRegistryMatchesGrid}: for every position
 * P occupied by a non-Rock/non-Nutrient entity, LiveEntityRegistry contains exactly
 * one entry at P; and the registry contains no entries at positions that are empty,
 * Rock, or Nutrient on the grid.
 *
 * <p>The four lifecycle scenarios tested:
 * <ol>
 *   <li>{@link #registryMatchesGridOccupantsAtRest} — seeded particles manually
 *       registered; invariant holds before any tick.</li>
 *   <li>{@link #registryMatchesGridOccupantsAfterDeath} — energy-decay kills a
 *       low-energy particle; death hooks fire; invariant holds after 1 tick.</li>
 *   <li>{@link #registryMatchesGridOccupantsAfterBondFormation} — adjacent
 *       predator+prey particles form a BondedPair; hooks unregister both particles
 *       and register the pair; invariant holds after 1 tick. (REVIEWS MEDIUM-4)</li>
 *   <li>{@link #registryMatchesGridOccupantsAfterCompositeFormation} — adjacent
 *       BondedPairs merge into a Composite; hooks fire; invariant holds after 1 tick.
 *       (REVIEWS MEDIUM-4)</li>
 * </ol>
 *
 * <p><b>REVIEWS CONSENSUS-H5:</b> {@link TickEvent} is in {@code com.paralife.engine}
 * (verified). Convenience ctor {@code new TickEvent(1L)} is valid (REVIEWS R2-15).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        // No decay — tests control energy explicitly.
        "paralife.simulation.energy-decay-per-tick=0",
        "paralife.simulation.types.catalyst.decay-per-tick=0",
        "paralife.simulation.types.membrane.decay-per-tick=0",
        "paralife.simulation.types.spore.decay-per-tick=0",
        // No nutrient spawning — prevents surprise occupants appearing.
        "paralife.simulation.nutrient-spawn-probability=0.0",
        // No overcrowding penalty so particles survive freely.
        "paralife.simulation.overcrowding-threshold=8",
        // Bond with 100% probability on any eligible adjacent pair (threshold 0).
        "paralife.bonding.bond-energy-threshold=0",
        "paralife.bonding.bonding-probability=1.0",
        // Composites enabled; formation is deterministic when two adjacent BondedPairs exist.
        "paralife.composite.can-form-composites=true",
        // Disable env events that could alter the grid unexpectedly.
        "paralife.simulation.events.enabled=false",
})
class LiveEntityRegistryInvariantTest {

    @Autowired
    private WorldGrid worldGrid;

    @Autowired
    private GridConfig gridConfig;

    @Autowired
    private LiveEntityRegistry liveEntityRegistry;

    @Autowired
    private BotRegistry botRegistry;

    @Autowired
    private CompositeRegistry compositeRegistry;

    @Autowired
    private ActionResolver actionResolver;

    @Autowired
    private ApplicationEventPublisher publisher;

    @BeforeEach
    void resetAll() {
        worldGrid.clear();
        liveEntityRegistry.clearForTest();
        botRegistry.clear();
        compositeRegistry.clear();
        // Phase 19.1 D-12: clear actionResolver state between tests to avoid
        // lastReproducedTick cooldown leaking into subsequent reproduction tests.
        actionResolver.clearStateForTest();
    }

    // ── Scenario 1: AT-REST ──────────────────────────────────────────────────

    /**
     * Seed 4 particles, register them manually, assert registry == grid occupants.
     * No tick fired. Validates that the invariant helper itself works and that
     * manual registration flows match what handleRegister would produce.
     */
    @Test
    void registryMatchesGridOccupantsAtRest() {
        // Seed 4 catalyst particles at known positions.
        var p1 = new Particle("p1", ParticleType.CATALYST, 80);
        var p2 = new Particle("p2", ParticleType.MEMBRANE, 80);
        var p3 = new Particle("p3", ParticleType.SPORE, 80);
        var p4 = new Particle("p4", ParticleType.CATALYST, 80);

        worldGrid.setEntity(0, 0, p1);
        worldGrid.setEntity(2, 3, p2);
        worldGrid.setEntity(5, 5, p3);
        worldGrid.setEntity(7, 1, p4);

        // Register them (simulating what WorldWebSocketHandler.handleRegister does).
        liveEntityRegistry.register("p1", new Position(0, 0));
        liveEntityRegistry.register("p2", new Position(2, 3));
        liveEntityRegistry.register("p3", new Position(5, 5));
        liveEntityRegistry.register("p4", new Position(7, 1));

        assertRegistryMatchesGrid("AT-REST with 4 particles");
    }

    // ── Scenario 2: POST-DEATH ───────────────────────────────────────────────

    /**
     * A particle with energy=2 is placed and registered. After 1 tick with
     * decay=2 applied by SimulationEngine, it dies and the death hook fires
     * liveEntityRegistry.unregister. The registry must then match the (now empty) grid.
     *
     * Uses decay=2 on a particle with energy=2 so exactly one tick kills it.
     * We temporarily override properties via direct energy manipulation — the
     * SimulationEngine uses per-type decay from MetabolicProfile so we give the
     * particle energy=1 (below decay floor) and rely on the fact that SimulationEngine
     * removes entities with energy <= 0.
     */
    @Test
    void registryMatchesGridOccupantsAfterDeath() {
        // Catalyst with energy=1; SimulationEngine will apply decay-per-tick=0 here
        // (we suppressed decay globally in @TestPropertySource).
        // To force death, use energy=0 directly — the engine removes energy<=0 entities
        // at the START of tick processing before decay.
        var dying = new Particle("dying-1", ParticleType.CATALYST, 0);
        worldGrid.setEntity(3, 3, dying);
        liveEntityRegistry.register("dying-1", new Position(3, 3));

        assertThat(liveEntityRegistry.size()).isEqualTo(1);

        // Fire 1 tick. SimulationEngine.@Order(10) processes deaths of energy<=0 entities.
        publisher.publishEvent(new TickEvent(1L));

        // Energy-0 entity should have been removed from grid.
        assertThat(worldGrid.getCell(3, 3).isEmpty())
                .as("Energy-0 particle should be removed after tick")
                .isTrue();

        // Registry must now also be empty.
        assertRegistryMatchesGrid("POST-DEATH after energy-0 particle removed");
    }

    // ── Scenario 3: POST-BOND-FORMATION ─────────────────────────────────────

    /**
     * REVIEWS MEDIUM-4: bond-formation scenario is MANDATORY.
     *
     * Seed a CATALYST particle (predator) adjacent to a SPORE particle (prey).
     * Both have high energy so they meet the bond threshold. With bonding-probability=1.0
     * and bond-energy-threshold=0, they will bond on the first tick.
     *
     * The bond-formation hook must:
     *   - unregister predator + prey from LiveEntityRegistry
     *   - register the resulting BondedPair at primaryPos
     *
     * After the tick: only 1 entry in registry at primaryPos (BondedPair); secondaryPos is empty.
     */
    @Test
    void registryMatchesGridOccupantsAfterBondFormation() {
        // CATALYST beats SPORE in RPS combat; they form a bond when adjacent.
        var predator = new Particle("pred-1", ParticleType.CATALYST, 80);
        var prey = new Particle("prey-1", ParticleType.SPORE, 80);

        Position predPos = new Position(3, 3);
        Position preyPos = new Position(3, 4); // adjacent (toroidal: dy=1)

        worldGrid.setEntity(predPos.x(), predPos.y(), predator);
        worldGrid.setEntity(preyPos.x(), preyPos.y(), prey);

        // Register both particles before the tick fires.
        liveEntityRegistry.register("pred-1", predPos);
        liveEntityRegistry.register("prey-1", preyPos);

        assertThat(liveEntityRegistry.size()).isEqualTo(2);

        // Fire 1 tick — bonding happens in SimulationEngine @Order(10).
        publisher.publishEvent(new TickEvent(1L));

        // One of the two positions is now empty (secondary pos absorbed into bond).
        // The other position holds the BondedPair.
        boolean predPosHasBondedPair = worldGrid.getCell(predPos.x(), predPos.y()).occupant() instanceof BondedPair;
        boolean preyPosHasBondedPair = worldGrid.getCell(preyPos.x(), preyPos.y()).occupant() instanceof BondedPair;

        assertThat(predPosHasBondedPair || preyPosHasBondedPair)
                .as("After bond formation, one of the two positions should hold a BondedPair")
                .isTrue();

        // Exactly one live entity remains (the BondedPair grid-occupant).
        assertThat(liveEntityRegistry.size())
                .as("Registry should contain exactly 1 entry after bond formation")
                .isEqualTo(1);

        // The invariant: registry positions == non-rock/non-nutrient grid occupants.
        assertRegistryMatchesGrid("POST-BOND-FORMATION");
    }

    // ── Scenario 4: POST-COMPOSITE-FORMATION ────────────────────────────────

    /**
     * REVIEWS MEDIUM-4: composite-formation scenario is MANDATORY.
     *
     * Seed two adjacent BondedPairs. With canFormComposites=true, SimulationEngine
     * will merge them into a Composite on the first tick (D-01). The formation hook must:
     *   - unregister bp1 + bp2
     *   - register member1 (at pos1) + member2 (at pos2) with Optional.empty()
     *
     * After the tick: 2 entries in registry (one per CompositeMember); both positions
     * hold CompositeMember entities.
     */
    @Test
    void registryMatchesGridOccupantsAfterCompositeFormation() {
        var bp1 = new BondedPair("bp1", ParticleType.CATALYST, ParticleType.SPORE,
                80, 100, "bp1-primary", "bp1-secondary");
        var bp2 = new BondedPair("bp2", ParticleType.MEMBRANE, ParticleType.CATALYST,
                80, 100, "bp2-primary", "bp2-secondary");

        Position pos1 = new Position(3, 3);
        Position pos2 = new Position(3, 4); // adjacent

        worldGrid.setEntity(pos1.x(), pos1.y(), bp1);
        worldGrid.setEntity(pos2.x(), pos2.y(), bp2);

        // Register both BondedPairs before the tick fires.
        liveEntityRegistry.register("bp1", pos1);
        liveEntityRegistry.register("bp2", pos2);

        assertThat(liveEntityRegistry.size()).isEqualTo(2);

        // Fire 1 tick — composite formation happens in SimulationEngine @Order(10).
        publisher.publishEvent(new TickEvent(1L));

        // Both positions should now hold CompositeMember entities.
        assertThat(worldGrid.getCell(pos1.x(), pos1.y()).occupant())
                .as("pos1 should hold a CompositeMember after composite formation")
                .isInstanceOf(CompositeMember.class);
        assertThat(worldGrid.getCell(pos2.x(), pos2.y()).occupant())
                .as("pos2 should hold a CompositeMember after composite formation")
                .isInstanceOf(CompositeMember.class);

        // Registry should have exactly 2 entries (one per CompositeMember grid-occupant).
        assertThat(liveEntityRegistry.size())
                .as("Registry should contain exactly 2 entries after composite formation (one per member)")
                .isEqualTo(2);

        // Registry positions match the members' grid positions.
        assertRegistryMatchesGrid("POST-COMPOSITE-FORMATION");

        // Phase 19.5 M6: EntityEntry.sessionId field deleted; per-session attribution
        // lives in BotRegistry exclusively. No registry-level assertion remains.
    }

    // ── Scenario 5: POST-MOVEMENT ────────────────────────────────────────────

    /**
     * Phase 19.1 D-12 / LM — registry matches grid after movement action.
     *
     * Seed 2 particles at non-overlapping cells; queue an M action (numpad '6' = East)
     * for one of them; drive 1 tick; assert registry == grid occupants.
     *
     * B5.1: queueAction is sessionId-keyed (ActionResolver.java:351), not entityId.
     * C5.3: tick driving via publisher.publishEvent; tickEngine.tickOnce() does not exist.
     */
    @Test
    @DisplayName("Phase 19.1 D-12 / LM — registry matches grid after movement action")
    void registryMatchesGridOccupantsAfterMovement() {
        // Seed p1 at (3,3) and p2 at (5,5) — non-adjacent so p1's move won't collide.
        var p1 = new Particle("p1-move", ParticleType.CATALYST, 80);
        var p2 = new Particle("p2-move", ParticleType.MEMBRANE, 80);

        worldGrid.setEntity(3, 3, p1);
        worldGrid.setEntity(5, 5, p2);
        liveEntityRegistry.register("p1-move", new Position(3, 3));
        liveEntityRegistry.register("p2-move", new Position(5, 5));

        // B5.1: queueAction is sessionId-keyed. Register p1 in botRegistry with a fake session.
        botRegistry.register("sess-p1-move", "p1-move", new Position(3, 3));
        // Queue M action with direction '6' (East) — target cell (4,3) is empty.
        actionResolver.queueAction("sess-p1-move",
                new Frame.ActionFrame('M', Optional.of("6")));

        // C5.3: publish TickEvent; tickEngine.tickOnce() does not exist.
        publisher.publishEvent(new TickEvent(1L));

        assertRegistryMatchesGrid("POST-MOVEMENT after M action");
    }

    // ── Scenario 6: POST-REPRODUCTION ────────────────────────────────────────

    /**
     * Phase 19.1 D-12 / LM — registry matches grid after reproduction action.
     *
     * Seed 1 high-energy CATALYST parent; queue R action with numpad '6' (East);
     * drive 1 tick; assert child particle is in registry AND parent remains.
     *
     * D3-T5: reproduce takes a numpad direction (target cell relative to parent),
     * NOT a mate id. CATALYST reproduce-energy-cost=40; parent needs energy >= 48
     * (cost + starvation floor). Energy=80 satisfies this.
     * B5.1: queueAction is sessionId-keyed.
     * C5.3: tick driving via publisher.publishEvent.
     */
    @Test
    @DisplayName("Phase 19.1 D-12 / LM — registry matches grid after reproduction action")
    void registryMatchesGridOccupantsAfterReproduction() {
        // CATALYST: max-energy=80, reproduce-energy-cost=40, starvation-threshold=30%,
        // starvation-floor=10% (8 energy). Parent energy=80 >= cost(40) + floor(8) = 48. OK.
        var parent = new Particle("p1-repro", ParticleType.CATALYST, 80);

        worldGrid.setEntity(3, 3, parent);
        liveEntityRegistry.register("p1-repro", new Position(3, 3));

        // Target cell (4,3) is East='6'; must be empty for reproduction to succeed.
        // No other entity is placed there.
        botRegistry.register("sess-p1-repro", "p1-repro", new Position(3, 3));
        actionResolver.queueAction("sess-p1-repro",
                new Frame.ActionFrame('R', Optional.of("6")));

        // C5.3: publish TickEvent.
        publisher.publishEvent(new TickEvent(1L));

        // Registry must match grid: parent at (3,3) + child at (4,3).
        assertRegistryMatchesGrid("POST-REPRODUCTION after R action");
        assertThat(liveEntityRegistry.snapshot().size())
                .as("Registry must have >= 2 entries after reproduction (parent + child)")
                .isGreaterThanOrEqualTo(2);
    }

    // ── Invariant helper ─────────────────────────────────────────────────────

    /**
     * Assert that the registry's snapshot positions EXACTLY match the set of
     * non-Rock/non-Nutrient occupied positions on the grid.
     *
     * <p>Two-way check:
     * <ol>
     *   <li>Every position in the registry corresponds to a non-Rock/non-Nutrient
     *       grid occupant (no stale entries).</li>
     *   <li>Every non-Rock/non-Nutrient occupied grid cell has a registry entry
     *       (no missing entries).</li>
     * </ol>
     */
    private void assertRegistryMatchesGrid(String scenario) {
        // Phase 19.5 C1: compare position → entityId maps. The prior Set<Position>
        // comparison only caught extra/missing positions; identity drift (registry
        // entry exists at the right position but with the wrong entityId — the
        // exact failure mode of H-A pre-fix) was invisible. Now any mismatch
        // between grid occupant id and registry entityId at the same position
        // surfaces as a map inequality.
        Map<Position, String> gridOccupantIds = new HashMap<>();
        for (int x = 0; x < gridConfig.width(); x++) {
            for (int y = 0; y < gridConfig.height(); y++) {
                Cell cell = worldGrid.getCell(x, y);
                Entity occ = cell.occupant();
                if (occ != null && !(occ instanceof Rock) && !(occ instanceof Nutrient)) {
                    gridOccupantIds.put(new Position(x, y), occ.id());
                }
            }
        }

        Map<Position, String> registryByPosition = new HashMap<>();
        for (LiveEntityRegistry.EntityEntry entry : liveEntityRegistry.snapshot()) {
            registryByPosition.put(entry.position(), entry.entityId());
        }

        assertThat(registryByPosition)
                .as("[%s] Registry position→entityId must match grid occupant id at the same position. "
                        + "Extra in registry (stale): %s. Missing from registry: %s. "
                        + "Identity drift (same position, different id): %s",
                        scenario,
                        differenceKeys(registryByPosition, gridOccupantIds),
                        differenceKeys(gridOccupantIds, registryByPosition),
                        idDrift(registryByPosition, gridOccupantIds))
                .isEqualTo(gridOccupantIds);
    }

    private static Set<Position> differenceKeys(Map<Position, String> a, Map<Position, String> b) {
        Set<Position> diff = new HashSet<>(a.keySet());
        diff.removeAll(b.keySet());
        return diff;
    }

    private static Map<Position, String> idDrift(Map<Position, String> registry,
                                                  Map<Position, String> grid) {
        Map<Position, String> drift = new HashMap<>();
        for (Map.Entry<Position, String> e : registry.entrySet()) {
            String gridId = grid.get(e.getKey());
            if (gridId != null && !gridId.equals(e.getValue())) {
                drift.put(e.getKey(), "registry=" + e.getValue() + " grid=" + gridId);
            }
        }
        return drift;
    }
}
