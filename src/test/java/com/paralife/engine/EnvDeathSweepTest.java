package com.paralife.engine;

import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same-tick env-death sweep verification (cycle-5 contract).
 *
 * <p>Particle scenario: an entity damaged to energy=0 by the env phase must be
 * absent from {@link WorldGrid} AND unregistered from {@link BotRegistry}
 * before {@code ActionResolver(@Order 20)} runs. {@link EnvironmentEngine}'s
 * {@code processEnvDeaths} must pick up the zero-energy occupant when the
 * {@code envDamageAppliedThisTick} short-circuit flag is true.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=true"
})
class EnvDeathSweepTest {

    @Autowired WorldGrid worldGrid;
    @Autowired BotRegistry botRegistry;
    @Autowired EnvironmentEngine environmentEngine;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        botRegistry.clear();
    }

    @Test
    void particleEnvDeathIsSweptSameTickAndBotRegistryCleaned() {
        // Place a registered particle at (3, 4).
        Particle p = new Particle("p-1", ParticleType.CATALYST, 10, 100);
        worldGrid.setEntity(3, 4, p);
        botRegistry.register("session-A", "p-1", new Position(3, 4));
        assertThat(botRegistry.getSessionForEntity("p-1")).isPresent();

        // Env damage lethals the particle — mimic a plan 02/03/04 env damage site.
        environmentEngine.killParticleAtForTest(3, 4);

        // Sweep.
        environmentEngine.processEnvDeathsForTest();

        // Grid cleared.
        assertThat(worldGrid.getCell(3, 4).hasOccupant()).isFalse();
        // Bot registry cleaned.
        assertThat(botRegistry.getSessionForEntity("p-1")).isEmpty();
    }
}
