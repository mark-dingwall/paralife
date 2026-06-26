package com.paralife.engine;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, engine-direct tests for the SOLO-particle reproduce path
 * {@link ActionResolver#resolveReproduce} — the surplus gate, the starvation-floor
 * gate, the cooldown gate, the energy debit, and child placement.
 *
 * <p>Part of the TD-22-A decomposition: these replace the implicit reproduce
 * coverage of the deleted {@code MetabolismIntegrationTest.allTypesSurviveWithMetabolism}
 * with assertions pinned to config values (not stochastic survival outcomes).
 * The composite REPRODUCER pool path ({@code resolveReproducerBud}) is covered
 * separately by {@code ReproducerAutoPlaceTest}; this exercises the distinct
 * solo path reached when the bot entity is a plain {@link Particle}.
 *
 * <p><b>Harness contract</b> (why this is not vacuous): {@code resolveActions}
 * only dispatches an action when a REAL {@link BotRegistry} returns a registered
 * {@link BotState} whose entityId equals the grid occupant's id. So every fixture
 * registers the bot and places a Particle with the same id; every negative test
 * is paired with a positive control that DOES reproduce, proving the action fired.
 */
class ActionResolverReproduceTest {

    private static final int DIM = 16;

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(DIM, DIM));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        compositeRegistry = new CompositeRegistry();
    }

    /** Fresh resolver; {@code childIdCounter}/{@code lastReproducedTick} are instance state. */
    private ActionResolver resolverWith(MetabolicProfile profile) {
        return new ActionResolver(worldGrid, botRegistry, sessionRegistry,
                SimulationConfig.defaults(), compositeRegistry, CompositeConfig.defaults(), profile);
    }

    /** All three types share one profile so the CATALYST bot's knobs are explicit. */
    private static MetabolicProfile profile(int cost, int cooldown, int starvThreshold) {
        var p = new MetabolicProfile.TypeProfile(
                100, 1, 10, 10, 5, cost, cooldown, 0.0, 1, starvThreshold, 0);
        return new MetabolicProfile(p, p, p);
    }

    /** Place a solo Particle as a registered bot entity (id == entityId == occupant id). */
    private void placeBot(String sessionId, String entityId, Position pos, int energy) {
        worldGrid.setEntity(pos.x(), pos.y(), new Particle(entityId, ParticleType.CATALYST, energy, 100));
        botRegistry.register(sessionId, entityId, pos);
    }

    private static Frame.ActionFrame reproduce(char numpad) {
        return new Frame.ActionFrame('R', Optional.of(String.valueOf(numpad)));
    }

    private Particle particleAt(int x, int y) {
        return (Particle) worldGrid.getCell(x, y).occupant();
    }

    // ── Insufficient-energy gate (cost + starvation-floor, jointly) ──
    // NOTE: with floor=0 here, sub-cost energy is blocked by BOTH the surplus
    // gate AND the floor gate (energyAfterCost = 29 - 30 = -1 < floor 0). Because
    // the floor is non-negative, it always subsumes the cost gate for sub-cost
    // energy, so this test cannot ISOLATE the cost gate — it pins the observable
    // behaviour (sub-cost ⇒ no child, no debit). The floor gate is isolated on
    // its own below (cost passes, floor blocks).

    @Test
    void belowCostBlocksReproduce_parentNotDebited() {
        var resolver = resolverWith(profile(30, 0, 0));
        placeBot("s", "e", new Position(5, 5), 29); // energy < cost 30

        resolver.resolveActions(1, Map.of("s", reproduce('6'))); // '6' = East

        assertThat(worldGrid.getCell(6, 5).hasOccupant())
                .as("no child placed when energy below reproduce cost").isFalse();
        assertThat(particleAt(5, 5).energy())
                .as("parent not debited when reproduce is rejected").isEqualTo(29);
    }

    @Test
    void aboveCostReproduces_childGetsStartEnergy_parentDebited() {
        var profile = profile(30, 0, 0);
        var catalyst = profile.forType(ParticleType.CATALYST);
        var resolver = resolverWith(profile);
        placeBot("s", "e", new Position(5, 5), 70);

        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        Particle child = particleAt(6, 5);
        assertThat(child).as("child placed at East target").isNotNull();
        assertThat(child.energy())
                .as("child starts at childStartEnergy (maxEnergy/2)")
                .isEqualTo(catalyst.childStartEnergy());
        assertThat(particleAt(5, 5).energy())
                .as("parent debited exactly reproduceEnergyCost")
                .isEqualTo(70 - catalyst.reproduceEnergyCost());
    }

    // ── Cooldown gate ─────────────────────────────────────────────
    // Reproduce EAST at tick 1 (child fills (6,5)), then WEST at tick 2 into the
    // EMPTY (4,5). Because the tick-2 target is empty, the ONLY thing that can
    // suppress it is the cooldown — isolating the gate the deleted test never did.

    @Test
    void cooldownSuppressesSecondReproduce_parentDebitedOnce() {
        var resolver = resolverWith(profile(30, 5, 0)); // cooldown 5
        placeBot("s", "e", new Position(5, 5), 100);

        resolver.resolveActions(1, Map.of("s", reproduce('6')));   // East child
        assertThat(particleAt(6, 5)).as("tick-1 reproduce succeeds").isNotNull();
        int afterFirst = particleAt(5, 5).energy();

        resolver.resolveActions(2, Map.of("s", reproduce('4')));   // West, empty target

        assertThat(worldGrid.getCell(4, 5).hasOccupant())
                .as("cooldown (not an occupied target) blocks the second reproduce").isFalse();
        assertThat(particleAt(5, 5).energy())
                .as("parent debited only once").isEqualTo(afterFirst);
    }

    @Test
    void noCooldownAllowsBackToBackReproduce() { // positive control for the cooldown test
        var resolver = resolverWith(profile(30, 0, 0));
        placeBot("s", "e", new Position(5, 5), 100);

        resolver.resolveActions(1, Map.of("s", reproduce('6')));
        resolver.resolveActions(2, Map.of("s", reproduce('4')));

        assertThat(worldGrid.getCell(6, 5).hasOccupant()).as("East child (tick 1)").isTrue();
        assertThat(worldGrid.getCell(4, 5).hasOccupant()).as("West child (tick 2, no cooldown)").isTrue();
    }

    // ── Starvation-floor gate ─────────────────────────────────────
    // Floor = (int)(starvationThreshold/100 * maxEnergy) = 40% of 100 = 40.

    @Test
    void starvationFloorBlocksReproduce_parentNotDebited() {
        var resolver = resolverWith(profile(30, 0, 40));
        placeBot("s", "e", new Position(5, 5), 50); // energyAfterCost = 20 < floor 40

        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        assertThat(worldGrid.getCell(6, 5).hasOccupant())
                .as("reproduce blocked when it would drop below the starvation floor").isFalse();
        assertThat(particleAt(5, 5).energy()).as("parent not debited").isEqualTo(50);
    }

    @Test
    void aboveFloorAllowsReproduce() { // positive control for the floor test
        var resolver = resolverWith(profile(30, 0, 40));
        placeBot("s", "e", new Position(5, 5), 80); // energyAfterCost = 50 >= floor 40

        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        assertThat(worldGrid.getCell(6, 5).hasOccupant())
                .as("reproduce allowed when energyAfterCost stays at/above the floor").isTrue();
    }
}
