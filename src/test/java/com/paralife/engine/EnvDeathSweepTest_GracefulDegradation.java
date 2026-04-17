package com.paralife.engine;

import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same-tick env-death sweep — graceful-degradation branch.
 *
 * <p>Class-level {@code @TestPropertySource} pins
 * {@code paralife.composite.dissolution-chance=0.0} so the 97/3 roll in
 * {@link SimulationEngine#handleMemberDeath} ALWAYS takes the graceful path:
 * composite survives with remaining members, registry updated SAME TICK.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=true",
        "paralife.composite.dissolution-chance=0.0"
})
class EnvDeathSweepTest_GracefulDegradation {

    @Autowired WorldGrid worldGrid;
    @Autowired BotRegistry botRegistry;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired EnvironmentEngine environmentEngine;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        botRegistry.clear();
        compositeRegistry.clear();
    }

    @Test
    void envKilledCompositeMemberTriggersGracefulDegradationSameTick() {
        // 3-member composite at (1,1), (2,1), (3,1). dissolution-chance=0.0
        // so any env-kill MUST produce graceful degradation (composite shrinks).
        String compositeId = "composite-gd";
        String m1 = "cm-1", m2 = "cm-2", m3 = "cm-3";
        CompositeMember cm1 = new CompositeMember(m1, compositeId, ParticleType.CATALYST, Role.LOCOMOTOR, 10, 50);
        CompositeMember cm2 = new CompositeMember(m2, compositeId, ParticleType.MEMBRANE, Role.FEEDER, 10, 50);
        CompositeMember cm3 = new CompositeMember(m3, compositeId, ParticleType.SPORE, Role.SENSOR, 10, 50);
        worldGrid.setEntity(1, 1, cm1);
        worldGrid.setEntity(2, 1, cm2);
        worldGrid.setEntity(3, 1, cm3);

        // Register via the real CompositeRegistry API (cycle-4 action item #4).
        compositeRegistry.register(compositeId, List.of(m1, m2, m3),
                Map.of(m1, new Position(1, 1), m2, new Position(2, 1), m3, new Position(3, 1)),
                30, 100);

        // Kill m2 via env damage.
        environmentEngine.killCompositeMemberAtForTest(2, 1);
        environmentEngine.processEnvDeathsForTest();

        // Composite survives with 2 members (graceful degradation path).
        var composite = compositeRegistry.getComposite(compositeId);
        assertThat(composite).as("composite survives same tick").isPresent();
        assertThat(composite.get().getMemberCount())
                .as("one member env-killed; two survive")
                .isEqualTo(2);

        // Grid cell cleared for the dead member.
        assertThat(worldGrid.getCell(2, 1).hasOccupant()).isFalse();
        // Surviving members still on grid.
        assertThat(worldGrid.getCell(1, 1).hasOccupant()).isTrue();
        assertThat(worldGrid.getCell(3, 1).hasOccupant()).isTrue();
    }
}
