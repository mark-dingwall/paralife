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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
