package com.paralife.engine;

import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashSet;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for {@link DeathFinalizer}. Plain JUnit 5 + Mockito
 * only — no Spring context is loaded (cycle-6 LOW clarification: the finalizer's
 * contract is a pure function of its injected collaborators, so every collaborator
 * is mocked and the class is constructed directly).
 */
class DeathFinalizerTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private BuffRegistry buffRegistry;
    private CompositeRegistry compositeRegistry;
    private DeathCleanupHooks hooks;
    private SimulationEngine simulationEngine;
    private DeathFinalizer finalizer;

    @BeforeEach
    void setUp() {
        worldGrid = mock(WorldGrid.class);
        botRegistry = mock(BotRegistry.class);
        buffRegistry = mock(BuffRegistry.class);
        compositeRegistry = mock(CompositeRegistry.class);
        hooks = mock(DeathCleanupHooks.class);
        simulationEngine = mock(SimulationEngine.class);
        finalizer = new DeathFinalizer(worldGrid, botRegistry, buffRegistry,
                compositeRegistry, hooks, simulationEngine);
    }

    @Test
    void particleDeathInvokesAllCleanupSteps() {
        Particle p = new Particle("p-1", ParticleType.CATALYST, 0, 100);

        finalizer.finalizeParticleDeath(7, 3, p);

        InOrder order = inOrder(botRegistry, buffRegistry, hooks, worldGrid);
        order.verify(botRegistry).unregisterByEntity("p-1");
        order.verify(buffRegistry).unregisterEntity("p-1");
        order.verify(hooks).clearInfectionOnDeath("p-1");
        order.verify(hooks).applyCompost(new Position(7, 3));
        order.verify(worldGrid).clearEntity(7, 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void bondedPairDeathCleansBothMemberIdsAndBpId() {
        // cycle-4 action item #6 — Gemini MEDIUM BondedPair cleanup:
        // finalizeBondedPairDeath MUST clear infection for primary id,
        // secondary id, AND bp.id() itself (Plan 14-03 keys BondedPair
        // infections by bp.id()).
        BondedPair bp = new BondedPair("bp1", ParticleType.CATALYST, ParticleType.MEMBRANE,
                0, 100, "p-a", "p-b");

        finalizer.finalizeBondedPairDeath(5, 5, bp);

        verify(botRegistry).unregisterByEntity("p-a");
        verify(botRegistry).unregisterByEntity("p-b");
        verify(buffRegistry).unregisterEntity("p-a");
        verify(buffRegistry).unregisterEntity("p-b");
        verify(hooks).clearInfectionOnDeath("p-a");
        verify(hooks).clearInfectionOnDeath("p-b");
        verify(hooks).clearInfectionOnDeath("bp1");   // cycle-4 action item #6
        verify(hooks).applyCompost(new Position(5, 5));
        verify(worldGrid).clearEntity(5, 5);
    }

    @Test
    void compositeMemberDeathDelegatesToSimulationEngineHandleMemberDeath() {
        CompositeMember cm = new CompositeMember("cm-1", "composite-X",
                ParticleType.SPORE, Role.LOCOMOTOR, 0, 50);
        Set<String> processed = new HashSet<>();

        finalizer.finalizeCompositeMemberDeath(4, 9, cm, processed);

        ArgumentCaptor<Position> posCaptor = ArgumentCaptor.forClass(Position.class);
        verify(simulationEngine).handleMemberDeath(
                org.mockito.ArgumentMatchers.eq(cm),
                posCaptor.capture(),
                org.mockito.ArgumentMatchers.same(processed));
        assertThat(posCaptor.getValue()).isEqualTo(new Position(4, 9));
        // DeathFinalizer MUST NOT bypass the 97/3 roll — composite-member
        // cleanup routes through handleMemberDeath.
    }

    @Test
    void compositeMemberConvenienceOverloadCreatesFreshProcessedSet() {
        CompositeMember cm = new CompositeMember("cm-2", "composite-Y",
                ParticleType.MEMBRANE, Role.FEEDER, 0, 40);

        finalizer.finalizeCompositeMemberDeath(1, 1, cm);

        ArgumentCaptor<Set<String>> setCaptor = ArgumentCaptor.forClass(Set.class);
        verify(simulationEngine).handleMemberDeath(
                org.mockito.ArgumentMatchers.eq(cm),
                org.mockito.ArgumentMatchers.eq(new Position(1, 1)),
                setCaptor.capture());
        assertThat(setCaptor.getValue()).as("convenience overload must pass a fresh empty set").isEmpty();
    }

    // ── Plan 14-06 Task 1: deathEventCount counter ────────────────────────

    @Test
    void deathEventCountIncrementsOnEveryFinalize() {
        // Plan 14-06 Task 1: DeathFinalizer exposes a monotonic counter so the
        // EnvironmentPhaseGateIntegrationTest can assert compost events fired.
        assertThat(finalizer.getDeathEventCount()).isEqualTo(0L);

        Particle p = new Particle("p-count", ParticleType.CATALYST, 0, 100);
        finalizer.finalizeParticleDeath(0, 0, p);
        assertThat(finalizer.getDeathEventCount()).isEqualTo(1L);

        BondedPair bp = new BondedPair("bp-count", ParticleType.CATALYST, ParticleType.MEMBRANE,
                0, 100, "a", "b");
        finalizer.finalizeBondedPairDeath(1, 1, bp);
        assertThat(finalizer.getDeathEventCount()).isEqualTo(2L);

        CompositeMember cm = new CompositeMember("cm-count", "composite-Z",
                ParticleType.SPORE, Role.LOCOMOTOR, 0, 50);
        finalizer.finalizeCompositeMemberDeath(2, 2, cm);
        assertThat(finalizer.getDeathEventCount()).isEqualTo(3L);
    }

    @Test
    void deathEventCountResetForTestZeroesCounter() {
        Particle p = new Particle("p-reset", ParticleType.CATALYST, 0, 100);
        finalizer.finalizeParticleDeath(0, 0, p);
        assertThat(finalizer.getDeathEventCount()).isEqualTo(1L);

        finalizer.resetCountForTest();
        assertThat(finalizer.getDeathEventCount()).isEqualTo(0L);
    }
}
