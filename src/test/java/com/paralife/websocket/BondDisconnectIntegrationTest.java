package com.paralife.websocket;

import com.paralife.admission.OutboundSender;
import com.paralife.admission.ResumeTokenRegistry;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.DeathFinalizer;
import com.paralife.engine.LiveEntityRegistry;
import com.paralife.engine.TickEvent;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 19.5 H2 regression test — bond-formation must remap {@link BotRegistry}
 * AND the predator session's {@code ATTR_ENTITY_ID} attribute so a
 * disconnect-before-death cleanly removes the {@link BondedPair} entry from
 * {@link LiveEntityRegistry}.
 *
 * <p><b>Bug</b>: prior to H2 fix, bond-formation only updated
 * {@link LiveEntityRegistry} (predator+prey unregistered, BondedPair registered).
 * Neither {@link BotRegistry} nor the WS session's {@code ATTR_ENTITY_ID} were
 * touched — both still pointed at the predator's particle id. When the
 * predator's session disconnected before the BondedPair died, {@code cleanupBot}
 * called {@code liveEntityRegistry.unregister(predator.id())} — a no-op (the
 * registry holds {@code bondedPair.id()}). The BondedPair entry leaked until
 * eventual death. Violates SCALE-07 invariant.
 *
 * <p>WS:entity 1:1 (CLAUDE.md Phase 18 D-05/D-21): the BondedPair is one entity
 * controlled by the predator's surviving session. Prey's session is unregistered
 * at bond formation.
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
        "paralife.composite.can-form-composites=false",
        "paralife.simulation.events.enabled=false",
})
class BondDisconnectIntegrationTest {

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired DeathFinalizer deathFinalizer;
    @Autowired ResumeTokenRegistry resumeTokenRegistry;

    @BeforeEach
    void resetAll() {
        worldGrid.clear();
        liveEntityRegistry.clearForTest();
        botRegistry.clear();
    }

    @Test
    void bondFormation_thenPredatorDisconnect_leavesNoStaleRegistryEntry() {
        // ── Arrange: place predator + prey adjacent and wire BotRegistry as if both
        // had registered through handleRegister. Sessions are mocked because
        // handleRegister's random placement can't guarantee adjacency.
        String predSessionId = "pred-session";
        String preySessionId = "prey-session";
        String predEntityId  = "pred-1";
        String preyEntityId  = "prey-1";

        WebSocketSession predSession = mockSession(predSessionId);
        WebSocketSession preySession = mockSession(preySessionId);
        sessionRegistry.register(predSession);
        sessionRegistry.register(preySession);

        Position predPos = new Position(3, 3);
        Position preyPos = new Position(3, 4);

        // CATALYST beats SPORE in RPS — bond when adjacent at bonding-probability=1.0.
        worldGrid.setEntity(predPos.x(), predPos.y(),
                new Particle(predEntityId, ParticleType.CATALYST, 80));
        worldGrid.setEntity(preyPos.x(), preyPos.y(),
                new Particle(preyEntityId, ParticleType.SPORE, 80));

        // Mirror handleRegister's three-line bookkeeping for both bots.
        botRegistry.register(predSessionId, predEntityId, predPos);
        botRegistry.register(preySessionId, preyEntityId, preyPos);
        liveEntityRegistry.register(predEntityId, predPos);
        liveEntityRegistry.register(preyEntityId, preyPos);
        predSession.getAttributes().put("entityId", predEntityId);
        predSession.getAttributes().put("entityType", "C");
        preySession.getAttributes().put("entityId", preyEntityId);
        preySession.getAttributes().put("entityType", "S");

        assertThat(liveEntityRegistry.size()).isEqualTo(2);

        // ── Act: fire a tick — SimulationEngine forms the bond and (post-H2) remaps
        // BotRegistry + ATTR_ENTITY_ID for the predator's surviving session.
        publisher.publishEvent(new TickEvent(1L));

        // Sanity: a BondedPair now occupies one of the two adjacent cells.
        boolean bondAtPredPos = worldGrid.getCell(predPos.x(), predPos.y()).occupant() instanceof BondedPair;
        boolean bondAtPreyPos = worldGrid.getCell(preyPos.x(), preyPos.y()).occupant() instanceof BondedPair;
        assertThat(bondAtPredPos || bondAtPreyPos).isTrue();
        assertThat(liveEntityRegistry.size()).isEqualTo(1);

        // H2 invariant #1: BotRegistry now maps predator's session → bondedPair.id().
        var bondedPairId = liveEntityRegistry.snapshot().get(0).entityId();
        assertThat(botRegistry.getBySession(predSessionId))
                .as("Predator's session should now control the BondedPair")
                .isPresent()
                .get()
                .extracting("entityId")
                .isEqualTo(bondedPairId);

        // H2 invariant #2: prey's session no longer maps to anything (1:1 invariant).
        assertThat(botRegistry.getBySession(preySessionId))
                .as("Prey's session must be unregistered at bond formation (WS:entity 1:1)")
                .isEmpty();

        // H2 invariant #3: ATTR_ENTITY_ID on predator's session points at the BondedPair.
        assertThat(predSession.getAttributes().get("entityId"))
                .as("Predator session ATTR_ENTITY_ID must reflect the BondedPair id")
                .isEqualTo(bondedPairId);

        // ── Act 2: predator disconnects BEFORE the BondedPair dies.
        handler.cleanupBot(predSession);

        // ── Assert: no stale registry entry remains.
        assertThat(liveEntityRegistry.size())
                .as("BondedPair entry must be removed by cleanupBot via the remapped entityId")
                .isZero();
        assertThat(botRegistry.getBySession(predSessionId))
                .as("Predator's BotRegistry entry must be gone after cleanupBot")
                .isEmpty();
    }

    /**
     * Phase 19.5 H-B (C2 regression): when a BondedPair dies, BotRegistry must
     * be cleared via bp.id() and a DeathNotice queued for the predator session
     * so TickBroadcaster emits the v|D respawn signal.
     */
    @Test
    void bondedPairDeath_clearsBotRegistryAndQueuesDeathNotice() {
        // Reuse the same arrange/bond-formation flow as the H2 test above.
        String predSessionId = "pred-session";
        String preySessionId = "prey-session";
        WebSocketSession predSession = mockSession(predSessionId);
        WebSocketSession preySession = mockSession(preySessionId);
        sessionRegistry.register(predSession);
        sessionRegistry.register(preySession);

        Position predPos = new Position(3, 3);
        Position preyPos = new Position(3, 4);
        worldGrid.setEntity(predPos.x(), predPos.y(),
                new Particle("pred-1", ParticleType.CATALYST, 80));
        worldGrid.setEntity(preyPos.x(), preyPos.y(),
                new Particle("prey-1", ParticleType.SPORE, 80));
        botRegistry.register(predSessionId, "pred-1", predPos);
        botRegistry.register(preySessionId, "prey-1", preyPos);
        liveEntityRegistry.register("pred-1", predPos);
        liveEntityRegistry.register("prey-1", preyPos);
        predSession.getAttributes().put("entityId", "pred-1");
        preySession.getAttributes().put("entityId", "prey-1");

        publisher.publishEvent(new TickEvent(1L));

        // Drain the bond-formation absorbed/death notices so we only see
        // the BondedPair-death notice produced by the finalize call below.
        botRegistry.drainDeaths();

        var bpEntry = liveEntityRegistry.snapshot().get(0);
        String bpId = bpEntry.entityId();
        Position bpPos = bpEntry.position();
        BondedPair bp = (BondedPair) worldGrid.getCell(bpPos.x(), bpPos.y()).occupant();

        // ── Act: kill the BondedPair via DeathFinalizer (the production path).
        deathFinalizer.finalizeBondedPairDeath(bpPos.x(), bpPos.y(), bp);

        // ── Assert H-B invariants:
        // (1) BotRegistry no longer holds the predator's session.
        assertThat(botRegistry.getBySession(predSessionId))
                .as("Predator session must be unregistered when BondedPair dies (H-B)")
                .isEmpty();
        // (2) Death notice was queued for the predator session keyed by bp.id().
        var deaths = botRegistry.drainDeaths();
        assertThat(deaths)
                .as("DeathNotice must include the predator session keyed by bp.id() (H-B)")
                .anyMatch(dn -> dn.sessionId().equals(predSessionId)
                        && dn.entityId().equals(bpId));
        // (3) LiveEntityRegistry empty (cell cleared + bp.id() unregistered).
        assertThat(liveEntityRegistry.size()).isZero();
    }

    /**
     * Phase 19.5 H-C (C2 regression): a STALLED resume token issued before
     * bond formation must resolve to the BondedPair id (not the pre-bond
     * particle id) when the predator reconnects. ResumeTokenRegistry.remapEntity
     * is fired by EntityLifecycleListener.onEntityRemapped at bond formation.
     */
    @Test
    void stalledPredator_thenBond_thenReconnect_rebindsToBondedPair() {
        String predSessionId = "pred-session";
        String preySessionId = "prey-session";
        WebSocketSession predSession = mockSession(predSessionId);
        WebSocketSession preySession = mockSession(preySessionId);
        sessionRegistry.register(predSession);
        sessionRegistry.register(preySession);

        Position predPos = new Position(3, 3);
        Position preyPos = new Position(3, 4);
        worldGrid.setEntity(predPos.x(), predPos.y(),
                new Particle("pred-1", ParticleType.CATALYST, 80));
        worldGrid.setEntity(preyPos.x(), preyPos.y(),
                new Particle("prey-1", ParticleType.SPORE, 80));
        botRegistry.register(predSessionId, "pred-1", predPos);
        botRegistry.register(preySessionId, "prey-1", preyPos);
        liveEntityRegistry.register("pred-1", predPos);
        liveEntityRegistry.register("prey-1", preyPos);
        predSession.getAttributes().put("entityId", "pred-1");
        preySession.getAttributes().put("entityId", "prey-1");

        // Issue an ACTIVE resume token for the predator entity BEFORE bond
        // formation — this is the token the H-C remap must rewrite.
        String token = resumeTokenRegistry.issueActive("pred-1", predSessionId);
        predSession.getAttributes().put("resumeToken", token);

        // Bond formation tick — H-C fires onEntityRemapped(predSessionId, "pred-1", bp.id()).
        publisher.publishEvent(new TickEvent(1L));

        String bpId = liveEntityRegistry.snapshot().get(0).entityId();

        // STALLED-then-rebind round-trip is the H-C contract: the resume token
        // must resolve to the post-bond BondedPair id, not the pre-bond
        // particle id. Pre-fix this returned "pred-1".
        resumeTokenRegistry.convertToStalled(token, 1L);
        var rebind = resumeTokenRegistry.tryRebind(token, "new-pred-session", 2L).orElse(null);
        assertThat(rebind)
                .as("Reconnect must resolve to the post-bond BondedPair (H-C)")
                .isNotNull();
        assertThat(rebind.entityId()).isEqualTo(bpId);
    }

    /**
     * Phase 19.5 E5: bond formation must enqueue a vB absorbed-notice for the
     * prey session so {@code TickBroadcaster.drainAndBroadcastAbsorptions}
     * delivers a terminal {@code v|B} frame (E1 schema) instead of silently
     * dropping the prey's binding.
     */
    @Test
    void bondFormation_emitsAbsorbedNoticeForPreySession() {
        String predSessionId = "pred-session";
        String preySessionId = "prey-session";
        WebSocketSession predSession = mockSession(predSessionId);
        WebSocketSession preySession = mockSession(preySessionId);
        sessionRegistry.register(predSession);
        sessionRegistry.register(preySession);

        Position predPos = new Position(3, 3);
        Position preyPos = new Position(3, 4);
        worldGrid.setEntity(predPos.x(), predPos.y(),
                new Particle("pred-1", ParticleType.CATALYST, 80));
        worldGrid.setEntity(preyPos.x(), preyPos.y(),
                new Particle("prey-1", ParticleType.SPORE, 80));
        botRegistry.register(predSessionId, "pred-1", predPos);
        botRegistry.register(preySessionId, "prey-1", preyPos);
        liveEntityRegistry.register("pred-1", predPos);
        liveEntityRegistry.register("prey-1", preyPos);
        predSession.getAttributes().put("entityId", "pred-1");
        preySession.getAttributes().put("entityId", "prey-1");

        publisher.publishEvent(new TickEvent(1L));

        // After the tick, TickBroadcaster.drainAndBroadcastAbsorptions has
        // delivered the v|B frame and called handler.markDead on the prey
        // session — which clears ATTR_ENTITY_ID. Pre-fix: prey session was
        // unregisterBySession'd silently and ATTR_ENTITY_ID stayed pinned
        // to "prey-1", so a follow-up r| from the prey session was rejected
        // as already-registered.
        assertThat(preySession.getAttributes().get("entityId"))
                .as("prey ATTR_ENTITY_ID must be cleared by absorbed-frame markDead (E1)")
                .isNull();
        // The notice list is drained by TickBroadcaster — empty here is correct.
        assertThat(botRegistry.drainAbsorptions()).isEmpty();
        // BotRegistry binding is gone (matches pre-E1 behavior — only the
        // signal mechanism changed).
        assertThat(botRegistry.getBySession(preySessionId)).isEmpty();
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(s.getAttributes()).thenReturn(attrs);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        return s;
    }
}
