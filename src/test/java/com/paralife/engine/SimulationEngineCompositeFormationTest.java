package com.paralife.engine;

import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 19.1 F1 regression test — composite formation must use
 * {@code BotRegistry.remapEntity} instead of the buggy
 * {@code unregisterByEntity + register} sequence that enqueued a spurious
 * {@link BotRegistry.DeathNotice}.
 *
 * <p>The buggy path at {@code SimulationEngine.updateBotRegistryForFormation}
 * called {@code botRegistry.unregisterByEntity(bp.id())} which always queues a
 * DeathNotice via {@code BotRegistry:113}. Fix: replace with
 * {@code botRegistry.remapEntity(sessionId, newMemberId, pos)}, mirroring the
 * composite-revert site at {@code SimulationEngine.java:1259}.
 *
 * <p>Test drive: {@code @SpyBean BotRegistry} lets us verify method calls on the REAL
 * bean. {@code verify(botRegistry, never()).unregisterByEntity(any())} is RED with the
 * bug (unregisterByEntity IS called) and GREEN after the fix.
 *
 * <p>Verified: no custom {@code ApplicationEventMulticaster} bean with a
 * {@code TaskExecutor} is wired — dispatch is synchronous (default Spring
 * {@code SimpleApplicationEventMulticaster}).
 *
 * <p>Phase 19.1 D-10 (E4.1 amendment): two additional tests assert FLEEING transfer
 * at bp→cm composite formation and cm→particle dissolve identity transitions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=8",
        "paralife.world.height=8",
        "paralife.simulation.energy-decay-per-tick=0",
        "paralife.simulation.types.catalyst.decay-per-tick=0",
        "paralife.simulation.types.membrane.decay-per-tick=0",
        "paralife.simulation.types.spore.decay-per-tick=0",
        "paralife.simulation.nutrient-spawn-probability=0.0",
        "paralife.simulation.overcrowding-threshold=8",
        "paralife.bonding.bond-energy-threshold=0",
        "paralife.bonding.bonding-probability=1.0",
        "paralife.composite.can-form-composites=true",
        "paralife.simulation.events.enabled=false",
})
class SimulationEngineCompositeFormationTest {

    @Autowired
    private WorldGrid worldGrid;

    @SpyBean
    private BotRegistry botRegistry;

    @Autowired
    private LiveEntityRegistry liveEntityRegistry;

    @Autowired
    private CompositeRegistry compositeRegistry;

    @Autowired
    private EnvironmentEngine environmentEngine;

    @Autowired
    private SimulationEngine simulationEngine;

    @Autowired
    private ApplicationEventPublisher publisher;

    private static final String SESSION_1 = "sess-bp1";
    private static final String SESSION_2 = "sess-bp2";
    private static final String BP1_ID = "bp1";
    private static final String BP2_ID = "bp2";
    private static final Position POS1 = new Position(3, 3);
    private static final Position POS2 = new Position(3, 4);

    @BeforeEach
    void setUp() {
        worldGrid.clear();
        liveEntityRegistry.clearForTest();
        botRegistry.clear();
        compositeRegistry.clear();
        Mockito.clearInvocations(botRegistry);

        // Seed two adjacent BondedPairs — composite formation requires two adjacent BondedPairs.
        var bp1 = new BondedPair(BP1_ID, ParticleType.CATALYST, ParticleType.SPORE,
                80, 100, "bp1-primary", "bp1-secondary");
        var bp2 = new BondedPair(BP2_ID, ParticleType.MEMBRANE, ParticleType.CATALYST,
                80, 100, "bp2-primary", "bp2-secondary");

        worldGrid.setEntity(POS1.x(), POS1.y(), bp1);
        worldGrid.setEntity(POS2.x(), POS2.y(), bp2);

        // Register with LiveEntityRegistry (mirrors production flow).
        liveEntityRegistry.register(BP1_ID, POS1);
        liveEntityRegistry.register(BP2_ID, POS2);

        // Register bot sessions to both BondedPairs so updateBotRegistryForFormation
        // has an active session to remap. Without sessions, the ifPresent block is a no-op.
        botRegistry.register(SESSION_1, BP1_ID, POS1);
        botRegistry.register(SESSION_2, BP2_ID, POS2);

        // Clear invocations after setUp so the test only sees formation-tick calls.
        Mockito.clearInvocations(botRegistry);
    }

    @Test
    @DisplayName("F1 — drainDeaths() is empty after composite formation (Phase 19.1 D-02)")
    void drainDeathsIsEmptyAfterCompositeFormation() {
        // Fire 1 tick — composite formation happens in SimulationEngine @Order(10).
        // Synchronous dispatch: all @EventListener @Order slots run before publishEvent returns.
        publisher.publishEvent(new TickEvent(1L));

        // F1 bug: updateBotRegistryForFormation called unregisterByEntity which queued DeathNotices.
        // After the fix (remapEntity), unregisterByEntity must NOT be called during formation.
        // Note: unregisterByEntity may be called for other reasons (energy-0 deaths, etc.) —
        // but in this test, all entities have energy=80 and decay=0, so only formation calls it.
        verify(botRegistry, never()).unregisterByEntity(BP1_ID);
        verify(botRegistry, never()).unregisterByEntity(BP2_ID);
    }

    @Test
    @DisplayName("F1 — session is rebound to new composite member id after formation (Phase 19.1 D-02)")
    void sessionIsReboundToNewCompositeMemberAfterFormation() {
        publisher.publishEvent(new TickEvent(1L));

        // Composite formation must have occurred.
        assertThat(worldGrid.getCell(POS1.x(), POS1.y()).occupant())
                .as("pos1 should hold CompositeMember after formation")
                .isInstanceOf(CompositeMember.class);

        String cm1Id = ((CompositeMember) worldGrid.getCell(POS1.x(), POS1.y()).occupant()).id();
        String cm2Id = ((CompositeMember) worldGrid.getCell(POS2.x(), POS2.y()).occupant()).id();

        // The sessions must now resolve to the new composite member ids.
        assertThat(botRegistry.getSessionForEntity(cm1Id))
                .as("Session-1 should be rebound to new composite member at pos1")
                .isPresent();
        assertThat(botRegistry.getSessionForEntity(cm2Id))
                .as("Session-2 should be rebound to new composite member at pos2")
                .isPresent();
    }

    // ── Phase 19.1 D-10 (E4.1 amendment) — FLEEING transfer behavioural tests ──

    @Test
    @DisplayName("D-10 (E4.1) — FLEEING transfers from bp1 to cm member on composite formation")
    void fleeingTransfersOnCompositeFormation() {
        // Grant FLEEING to BP1 before the formation tick.
        // Expiry far in the future so it does not expire during the tick.
        environmentEngine.grantFleeingForTest(BP1_ID, 9999L, 3, 3);

        assertThat(environmentEngine.getFleeing(BP1_ID))
                .as("BP1 must have FLEEING before formation")
                .isNotNull();

        // Fire 1 tick — composite formation replaces BP1 with cm member at POS1.
        publisher.publishEvent(new TickEvent(1L));

        // Verify formation actually happened.
        assertThat(worldGrid.getCell(POS1.x(), POS1.y()).occupant())
                .as("POS1 must hold CompositeMember after formation tick")
                .isInstanceOf(CompositeMember.class);

        String cm1Id = ((CompositeMember) worldGrid.getCell(POS1.x(), POS1.y()).occupant()).id();

        // D-10 assertion: FLEEING must have moved from bp1.id() → cm member id.
        assertThat(environmentEngine.getFleeing(BP1_ID))
                .as("BP1 FLEEING must be removed after transfer to composite member")
                .isNull();
        assertThat(environmentEngine.getFleeing(cm1Id))
                .as("composite member must inherit FLEEING from bp1 after formation")
                .isNotNull();
    }

    @Test
    @DisplayName("D-10 (E4.1) — FLEEING transfers from composite member to particle on dissolve")
    void fleeingTransfersOnCompositeDissolve() {
        // First, form the composite by firing tick 1.
        publisher.publishEvent(new TickEvent(1L));

        assertThat(worldGrid.getCell(POS1.x(), POS1.y()).occupant())
                .as("POS1 must hold CompositeMember after formation")
                .isInstanceOf(CompositeMember.class);

        String cm1Id = ((CompositeMember) worldGrid.getCell(POS1.x(), POS1.y()).occupant()).id();
        String compositeId = ((CompositeMember) worldGrid.getCell(POS1.x(), POS1.y()).occupant()).compositeId();

        // Grant FLEEING to the composite member after formation.
        environmentEngine.grantFleeingForTest(cm1Id, 9999L, 4, 4);

        assertThat(environmentEngine.getFleeing(cm1Id))
                .as("composite member must have FLEEING before dissolve")
                .isNotNull();

        // Invoke dissolveToParticles directly (package-private test seam — D-10 E4.1 amendment).
        // This avoids the probabilistic shatter die roll in checkPanicZone.
        compositeRegistry.getComposite(compositeId).ifPresent(state ->
                simulationEngine.dissolveToParticles(state, new HashSet<>()));

        // After dissolve, cm1Id maps to a particle with id = cm1Id + "-p"
        // (see SimulationEngine.dissolveToParticles — particle = new Particle(cm.id() + "-p", ...)).
        String particleId = cm1Id + "-p";

        // D-10 assertion: FLEEING must have moved from cm.id() → particle.id().
        assertThat(environmentEngine.getFleeing(cm1Id))
                .as("composite member FLEEING must be removed after transfer to particle")
                .isNull();
        assertThat(environmentEngine.getFleeing(particleId))
                .as("particle must inherit FLEEING from composite member after dissolve")
                .isNotNull();
    }
}
