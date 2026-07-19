package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O4 at the reproduce paths: a committed offspring increments spawns[parentSpecies]
 * by exactly 1; a rejected reproduce commits nothing. Engine-direct, zero ticks
 * advanced beyond the single resolveActions call. Fixture-owned profile
 * (bonusOffspringChance = 0.0) so exactly one child is placed.
 */
class ActionResolverSpawnCounterTest {

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

    private ActionResolver resolverWith(MetabolicProfile profile, SpeciesSpawnCounter counter) {
        ActionResolver r = new ActionResolver(worldGrid, botRegistry, sessionRegistry,
                SimulationConfig.defaults(), compositeRegistry, CompositeConfig.defaults(), profile);
        r.setSpawnCounter(counter);
        return r;
    }

    /** Fixture-owned profile: cost 30, no cooldown, no starvation floor, bonusChance 0.0. */
    private static MetabolicProfile profile() {
        var p = new MetabolicProfile.TypeProfile(100, 1, 10, 10, 5, 30, 0, 0.0, 1, 0, 0);
        return new MetabolicProfile(p, p, p);
    }

    /** Same, but bonusOffspringChance = 1.0 → the bonus-offspring site always fires. */
    private static MetabolicProfile profileWithBonus() {
        var p = new MetabolicProfile.TypeProfile(100, 1, 10, 10, 5, 30, 0, 1.0, 1, 0, 0);
        return new MetabolicProfile(p, p, p);
    }

    private static Frame.ActionFrame reproduce(char numpad) {
        return new Frame.ActionFrame('R', Optional.of(String.valueOf(numpad)));
    }

    private void placeBot(String sessionId, String entityId, Position pos, int energy) {
        worldGrid.setEntity(pos.x(), pos.y(), new Particle(entityId, ParticleType.CATALYST, energy, 100));
        botRegistry.register(sessionId, entityId, pos);
    }

    @Test
    void committedReproduceIncrementsParentSpeciesByExactlyOne() {
        var counter = new SpeciesSpawnCounter();
        var resolver = resolverWith(profile(), counter);
        placeBot("s", "e", new Position(5, 5), 70); // CATALYST, above cost

        long before = counter.get(ParticleType.CATALYST);
        resolver.resolveActions(1, Map.of("s", reproduce('6'))); // East → (6,5) empty

        assertThat(counter.get(ParticleType.CATALYST) - before)
                .as("one committed child → spawns[CATALYST] += 1").isEqualTo(1L);
        assertThat(counter.get(ParticleType.MEMBRANE))
                .as("control: unrelated species unchanged").isZero();
    }

    @Test
    void bonusOffspringSiteAlsoIncrements_deltaTwoForParentSpecies() {
        // bonusOffspringChance=1.0 → the SEPARATE bonus-offspring creation site (ActionResolver
        // ~:692) fires alongside the primary (~:674): two committed children, both parent-species.
        var counter = new SpeciesSpawnCounter();
        var resolver = resolverWith(profileWithBonus(), counter);
        placeBot("s", "e", new Position(5, 5), 90); // ample energy; open neighbourhood for the bonus

        long before = counter.get(ParticleType.CATALYST);
        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        assertThat(counter.get(ParticleType.CATALYST) - before)
                .as("primary + forced bonus offspring → exactly +2").isEqualTo(2L);
    }

    @Test
    void rejectedReproduceIncrementsNothing() {
        var counter = new SpeciesSpawnCounter();
        var resolver = resolverWith(profile(), counter);
        placeBot("s", "e", new Position(5, 5), 29); // below cost 30 → no child

        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        assertThat(counter.get(ParticleType.CATALYST))
                .as("rejected reproduce commits no birth (failed-path control)").isZero();
    }
}
