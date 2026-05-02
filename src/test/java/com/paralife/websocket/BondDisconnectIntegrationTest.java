package com.paralife.websocket;

import com.paralife.admission.OutboundSender;
import com.paralife.engine.BotRegistry;
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
