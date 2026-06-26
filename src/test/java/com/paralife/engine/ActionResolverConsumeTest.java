package com.paralife.engine;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Entity;
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
 * Deterministic, engine-direct tests for the solo-particle consume path
 * {@link ActionResolver#resolveConsume} — exact energy gain, nutrient
 * partial-decrement vs full-deplete, and the starvation nutrient boost.
 *
 * <p>Part of the TD-22-A decomposition. The consume path scans the 8 Moore
 * neighbours and takes the first {@link Entity.Nutrient}; the direction arg is
 * ignored. Each fixture places the nutrient as the SOLE non-empty neighbour so
 * the scanned cell is unambiguous, and asserts on that exact cell.
 */
class ActionResolverConsumeTest {

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

    private ActionResolver resolverWith(MetabolicProfile profile, StarvationConfig starv) {
        return new ActionResolver(worldGrid, botRegistry, sessionRegistry,
                SimulationConfig.defaults(), compositeRegistry, CompositeConfig.defaults(), profile, starv);
    }

    private static MetabolicProfile profile(int starvThreshold, int starvFloor, int nutrientConsume) {
        var p = new MetabolicProfile.TypeProfile(
                100, 1, 10, 10, nutrientConsume, 30, 0, 0.0, 1, starvThreshold, starvFloor);
        return new MetabolicProfile(p, p, p);
    }

    private void placeBot(String sessionId, String entityId, Position pos, int energy) {
        worldGrid.setEntity(pos.x(), pos.y(), new Particle(entityId, ParticleType.CATALYST, energy, 100));
        botRegistry.register(sessionId, entityId, pos);
    }

    private static Frame.ActionFrame consume() {
        return new Frame.ActionFrame('E', Optional.empty()); // direction ignored on consume path
    }

    private Particle particleAt(int x, int y) {
        return (Particle) worldGrid.getCell(x, y).occupant();
    }

    @Test
    void consumeGainsExactNutrientEnergy_partialDepletion() {
        var resolver = resolverWith(profile(0, 0, 5), StarvationConfig.defaults()); // threshold 0 → no boost
        placeBot("s", "e", new Position(5, 5), 50);
        worldGrid.setEntity(6, 5, new Entity.Nutrient("n", 10)); // sole non-empty neighbour

        resolver.resolveActions(1, Map.of("s", consume()));

        assertThat(particleAt(5, 5).energy())
                .as("particle gains exactly nutrientConsumeEnergy").isEqualTo(50 + 5);
        var occ = worldGrid.getCell(6, 5).occupant();
        assertThat(occ).as("nutrient remains (10 - 5 > 0)").isInstanceOf(Entity.Nutrient.class);
        assertThat(((Entity.Nutrient) occ).level()).isEqualTo(10 - 5);
    }

    @Test
    void consumeFullyDepletesAndClearsLowNutrient() {
        var resolver = resolverWith(profile(0, 0, 5), StarvationConfig.defaults());
        placeBot("s", "e", new Position(5, 5), 50);
        worldGrid.setEntity(6, 5, new Entity.Nutrient("n", 5)); // level == gain → depletes to 0

        resolver.resolveActions(1, Map.of("s", consume()));

        assertThat(particleAt(5, 5).energy())
                .as("particle still gains full nutrientConsumeEnergy").isEqualTo(50 + 5);
        assertThat(worldGrid.getCell(6, 5).isEmpty())
                .as("depleted nutrient cell is cleared").isTrue();
    }

    @Test
    void consumeGainBoostedWhenStarving() {
        var starv = new StarvationConfig(1.0, 1.0, 1.0); // large boost so truncation can't collapse it
        var profile = profile(30, 10, 5);                // threshold 30%, floor 10%
        var resolver = resolverWith(profile, starv);
        placeBot("s", "e", new Position(5, 5), 10);       // 10% ≤ floor → intensity 1.0
        worldGrid.setEntity(6, 5, new Entity.Nutrient("n", 20));

        resolver.resolveActions(1, Map.of("s", consume()));

        // Expected anchored to a hand-computed literal, NOT to computeIntensity
        // (the function under test): at energy 10 / maxEnergy 100, currentPercent
        // (10%) ≤ floor (10%) so starvation intensity is 1.0 by construction;
        // gain = (int)(5 * (1 + maxNutrientBoost(1.0) * 1.0)) = 10 (double the
        // healthy gain of 5). A sign/formula regression in computeIntensity would
        // shift actual but not this literal, so it stays detectable.
        assertThat(particleAt(5, 5).energy())
                .as("starving particle gains the boosted amount (10), not the healthy 5")
                .isEqualTo(10 + 10);
    }
}
