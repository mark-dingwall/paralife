package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompositePerceptionTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;
    private ObjectMapper objectMapper;
    private PerceptionBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry();
        compositeRegistry = new CompositeRegistry();
        objectMapper = new ObjectMapper();
        broadcaster = new PerceptionBroadcaster(botRegistry, sessionRegistry,
                worldGrid, objectMapper, compositeRegistry);
    }

    @Test
    void sensorMemberProduces5x5Circle() {
        // Place a SENSOR at (5,5)
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);

        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);

        // Build stitched coverage
        var coverage = broadcaster.stitchSensorCoverage(
                compositeRegistry.getComposite("c1").orElseThrow());

        // Should contain all 25 cells in 5x5 around (5,5)
        assertThat(coverage).hasSize(25);
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                assertThat(coverage).contains(
                        Position.wrap(5 + dx, 5 + dy, 16, 16));
            }
        }
    }

    @Test
    void twoSensorsProduceUnionCoverage() {
        // Two SENSOR members at (5,5) and (8,5) -- 3 apart, overlapping coverage
        var s1 = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        var s2 = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, s1);
        worldGrid.setEntity(8, 5, s2);

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(8, 5)), 100, 200);

        var coverage = broadcaster.stitchSensorCoverage(
                compositeRegistry.getComposite("c1").orElseThrow());

        // Union of two 5x5 grids. (5,5) covers x=3..7, y=3..7. (8,5) covers x=6..10, y=3..7.
        // Overlap at x=6,7. Union covers x=3..10, y=3..7 = 8 * 5 = 40 unique positions
        assertThat(coverage).hasSize(40);

        // Check both centers are covered
        assertThat(coverage).contains(new Position(5, 5));
        assertThat(coverage).contains(new Position(8, 5));

        // Check an overlap position
        assertThat(coverage).contains(new Position(7, 5));
    }

    @Test
    void nonSensorMemberAddsNoVision() {
        // Composite: 1 FEEDER at (5,5) and 1 SENSOR at (8,5)
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        var sensor = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, feeder);
        worldGrid.setEntity(8, 5, sensor);

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(8, 5)), 100, 200);

        var coverage = broadcaster.stitchSensorCoverage(
                compositeRegistry.getComposite("c1").orElseThrow());

        // Only SENSOR at (8,5) contributes: 25 cells
        assertThat(coverage).hasSize(25);
        // SENSOR's coverage center
        assertThat(coverage).contains(new Position(8, 5));
        // FEEDER's position (5,5) is NOT in SENSOR's range (distance 3, radius 2)
        assertThat(coverage).doesNotContain(new Position(5, 5));
    }

    @Test
    void noSensorMeansBlindComposite() {
        // Composite with only FEEDER and LOCOMOTOR (no SENSOR)
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        var loco = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, feeder);
        worldGrid.setEntity(6, 5, loco);

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(6, 5)), 100, 200);

        var coverage = broadcaster.stitchSensorCoverage(
                compositeRegistry.getComposite("c1").orElseThrow());

        assertThat(coverage).isEmpty();
    }

    @Test
    void allMembersReceiveSamePerception() throws Exception {
        // Composite: 1 SENSOR at (5,5), 1 LOCOMOTOR at (6,5)
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        var loco = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);
        worldGrid.setEntity(6, 5, loco);

        compositeRegistry.register("c1", List.of("m1", "m2"),
                Map.of("m1", new Position(5, 5), "m2", new Position(6, 5)), 100, 200);

        // Register both as bots with mock sessions
        botRegistry.register("s1", "m1", new Position(5, 5));
        botRegistry.register("s2", "m2", new Position(6, 5));

        WebSocketSession session1 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("s1");
        when(session1.isOpen()).thenReturn(true);
        sessionRegistry.register(session1);

        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session2.getId()).thenReturn("s2");
        when(session2.isOpen()).thenReturn(true);
        sessionRegistry.register(session2);

        broadcaster.onTick(new TickEvent(1));

        // Both sessions should have received a message
        var captor1 = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        var captor2 = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session1).sendMessage(captor1.capture());
        verify(session2).sendMessage(captor2.capture());

        // Parse both messages
        var msg1 = objectMapper.readValue(captor1.getValue().getPayload(), Messages.class);
        var msg2 = objectMapper.readValue(captor2.getValue().getPayload(), Messages.class);

        assertThat(msg1).isInstanceOf(Messages.CompositePerception.class);
        assertThat(msg2).isInstanceOf(Messages.CompositePerception.class);

        var cp1 = (Messages.CompositePerception) msg1;
        var cp2 = (Messages.CompositePerception) msg2;

        // Same stitched neighbourhood
        assertThat(cp1.stitchedNeighbourhood()).isEqualTo(cp2.stitchedNeighbourhood());
        // Same composite metadata
        assertThat(cp1.compositeSize()).isEqualTo(cp2.compositeSize());
        assertThat(cp1.sharedPoolEnergy()).isEqualTo(cp2.sharedPoolEnergy());
        assertThat(cp1.maxPoolEnergy()).isEqualTo(cp2.maxPoolEnergy());

        // Different self state (each member has their own entity state)
        assertThat(cp1.self().entityId()).isEqualTo("m1");
        assertThat(cp2.self().entityId()).isEqualTo("m2");
        // Different roles
        assertThat(cp1.role()).isEqualTo("SENSOR");
        assertThat(cp2.role()).isEqualTo("LOCOMOTOR");
    }

    @Test
    void perceptionIncludesCompositeMetadata() throws Exception {
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 45, 100);
        worldGrid.setEntity(5, 5, sensor);

        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 80, 200);

        botRegistry.register("s1", "m1", new Position(5, 5));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(42));

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        var msg = objectMapper.readValue(captor.getValue().getPayload(), Messages.class);
        assertThat(msg).isInstanceOf(Messages.CompositePerception.class);

        var cp = (Messages.CompositePerception) msg;
        assertThat(cp.tickNumber()).isEqualTo(42);
        assertThat(cp.compositeSize()).isEqualTo(1);
        assertThat(cp.sharedPoolEnergy()).isEqualTo(80);
        assertThat(cp.maxPoolEnergy()).isEqualTo(200);
        assertThat(cp.role()).isEqualTo("SENSOR");
        assertThat(cp.self().entityId()).isEqualTo("m1");
        assertThat(cp.self().energy()).isEqualTo(45);
        assertThat(cp.self().maxEnergy()).isEqualTo(100);
    }

    @Test
    void perceptionMemoizedPerComposite() throws Exception {
        // 3 members in same composite — perception should be built once (same neighbourhood object)
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        var feeder = new CompositeMember("m2", "c1", ParticleType.MEMBRANE, Role.FEEDER, 50, 100);
        var loco = new CompositeMember("m3", "c1", ParticleType.SPORE, Role.LOCOMOTOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);
        worldGrid.setEntity(6, 5, feeder);
        worldGrid.setEntity(7, 5, loco);

        compositeRegistry.register("c1", List.of("m1", "m2", "m3"),
                Map.of("m1", new Position(5, 5), "m2", new Position(6, 5),
                        "m3", new Position(7, 5)), 100, 300);

        botRegistry.register("s1", "m1", new Position(5, 5));
        botRegistry.register("s2", "m2", new Position(6, 5));
        botRegistry.register("s3", "m3", new Position(7, 5));

        List<WebSocketSession> sessions = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            WebSocketSession s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn("s" + i);
            when(s.isOpen()).thenReturn(true);
            sessionRegistry.register(s);
            sessions.add(s);
        }

        broadcaster.onTick(new TickEvent(1));

        // All 3 should receive messages
        List<Messages.CompositePerception> perceptions = new ArrayList<>();
        for (var s : sessions) {
            var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
            verify(s).sendMessage(captor.capture());
            var msg = objectMapper.readValue(captor.getValue().getPayload(), Messages.class);
            assertThat(msg).isInstanceOf(Messages.CompositePerception.class);
            perceptions.add((Messages.CompositePerception) msg);
        }

        // All should have the same stitched neighbourhood (memoized)
        assertThat(perceptions.get(0).stitchedNeighbourhood())
                .isEqualTo(perceptions.get(1).stitchedNeighbourhood())
                .isEqualTo(perceptions.get(2).stitchedNeighbourhood());
    }

    @Test
    void toroidalWrappingSensorCoverage() {
        // SENSOR at (0,0) on 16x16 grid — coverage should wrap
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        worldGrid.setEntity(0, 0, sensor);

        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(0, 0)), 100, 200);

        var coverage = broadcaster.stitchSensorCoverage(
                compositeRegistry.getComposite("c1").orElseThrow());

        assertThat(coverage).hasSize(25);

        // Should wrap: (0-2) mod 16 = 14
        assertThat(coverage).contains(new Position(14, 14)); // NW wrap
        assertThat(coverage).contains(new Position(15, 15)); // NW wrap
        assertThat(coverage).contains(new Position(0, 0));    // center
        assertThat(coverage).contains(new Position(2, 2));    // SE
    }

    @Test
    void blindCompositeGetsNoPerception() throws Exception {
        // Composite with no SENSOR -- should NOT send any perception
        var feeder = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.FEEDER, 50, 100);
        worldGrid.setEntity(5, 5, feeder);

        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);

        botRegistry.register("s1", "m1", new Position(5, 5));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        // No message should be sent (blind composite)
        verify(session, never()).sendMessage(any());
    }
}
