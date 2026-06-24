package com.paralife.engine.emergence;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.BuffRegistry;
import com.paralife.engine.CompositeRegistry;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the population-census counting rule in {@link PopulationHistory#sample}:
 * a {@link BondedPair} contributes +1 to BOTH its primary and secondary type,
 * and a {@link CompositeMember} contributes +1 to its own type. This is the
 * named purpose of the (now deleted) {@code MetabolismIntegrationTest} census —
 * the review concern was that BondedPair/CompositeMember occupants were once
 * invisible to population counts. {@code PopulationHistory} is a test fixture,
 * but its counts gate the (opt-in) {@code EmergenceStabilityLoadTest} extinction
 * checks, so a regression here would silently corrupt that gate.
 *
 * <p>Pure function over a hand-built grid — no tick loop, no RNG.
 */
class PopulationHistoryTest {

    @Test
    void countsBondedPairAsBothTypes_andCompositeMemberByType() {
        WorldGrid grid = new WorldGrid(new GridConfig(8, 8));
        grid.setEntity(0, 0, new Particle("p", ParticleType.CATALYST, 50, 100));
        // BondedPair (CATALYST + SPORE): +1 CATALYST, +1 SPORE.
        grid.setEntity(2, 2, new BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE,
                100, 200, "p-a", "p-b", 0, 10, 10));
        // CompositeMember (MEMBRANE): +1 MEMBRANE.
        grid.setEntity(4, 4, new CompositeMember("cm", "comp", ParticleType.MEMBRANE, Role.FEEDER, 50, 100));

        PopulationHistory history = new PopulationHistory();
        history.sample(grid, new CompositeRegistry(), new BuffRegistry(), new BotRegistry(),
                new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry())), 1L);

        assertThat(history.typeSeries("CATALYST")).as("solo CATALYST + bonded primary").containsExactly(2);
        assertThat(history.typeSeries("SPORE")).as("bonded secondary").containsExactly(1);
        assertThat(history.typeSeries("MEMBRANE")).as("composite member").containsExactly(1);
    }
}
