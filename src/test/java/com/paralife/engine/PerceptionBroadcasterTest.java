package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.Messages.CellView;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PerceptionBroadcasterTest {

    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private WorldGrid worldGrid;
    private ObjectMapper objectMapper;
    private CompositeRegistry compositeRegistry;
    private PerceptionBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry();
        objectMapper = new ObjectMapper();
        compositeRegistry = new CompositeRegistry();
        broadcaster = new PerceptionBroadcaster(botRegistry, sessionRegistry, worldGrid, objectMapper, compositeRegistry);
    }

    @Test
    void buildPerceptionContainsEntityState() {
        Particle particle = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        var perception = broadcaster.buildPerception(42, bot);

        assertThat(perception.tickNumber()).isEqualTo(42);
        assertThat(perception.self().entityId()).isEqualTo("e1");
        assertThat(perception.self().particleType()).isEqualTo("CATALYST");
        assertThat(perception.self().energy()).isEqualTo(Particle.DEFAULT_START_ENERGY);
        assertThat(perception.self().x()).isEqualTo(5);
        assertThat(perception.self().y()).isEqualTo(5);
        assertThat(perception.radius()).isEqualTo(PerceptionBroadcaster.PERCEPTION_RADIUS);
    }

    @Test
    void buildPerceptionNeighbourhoodSize() {
        Particle particle = Particle.spawn("e1", ParticleType.SPORE);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        var perception = broadcaster.buildPerception(1, bot);

        int expectedSize = PerceptionBroadcaster.PERCEPTION_RADIUS * 2 + 1;
        assertThat(perception.neighbourhood()).hasSize(expectedSize);
        for (var row : perception.neighbourhood()) {
            assertThat(row).hasSize(expectedSize);
        }
    }

    @Test
    void buildPerceptionShowsNearbyEntities() {
        // Place bot at (5,5)
        Particle self = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, self);
        botRegistry.register("s1", "e1", new Position(5, 5));

        // Place a neighbor at (6,5) — one cell east
        Particle neighbor = Particle.spawn("e2", ParticleType.MEMBRANE);
        worldGrid.setEntity(6, 5, neighbor);

        // Place a rock at (4,4) — one cell NW
        worldGrid.setEntity(4, 4, new Entity.Rock("rock1"));

        var bot = botRegistry.getBySession("s1").orElseThrow();
        var perception = broadcaster.buildPerception(1, bot);

        // Centre of 5x5 grid is at index [2][2]
        int center = PerceptionBroadcaster.PERCEPTION_RADIUS;
        CellView selfView = perception.neighbourhood().get(center).get(center);
        assertThat(selfView.occupantType()).isEqualTo("CATALYST");

        // East neighbour: same row, one column right → [2][3]
        CellView eastView = perception.neighbourhood().get(center).get(center + 1);
        assertThat(eastView.occupantType()).isEqualTo("MEMBRANE");
        assertThat(eastView.occupantId()).isEqualTo("e2");

        // NW: one row up, one column left → [1][1]
        CellView nwView = perception.neighbourhood().get(center - 1).get(center - 1);
        assertThat(nwView.occupantType()).isEqualTo("ROCK");
    }

    @Test
    void buildPerceptionWrapsToroidally() {
        // Place bot at (0,0) — neighbours should wrap around the grid edges
        Particle self = Particle.spawn("e1", ParticleType.SPORE);
        worldGrid.setEntity(0, 0, self);
        botRegistry.register("s1", "e1", new Position(0, 0));

        // Place entity at (15, 15) — wrapped to be NW of (0,0)
        Particle wrapped = Particle.spawn("e2", ParticleType.CATALYST);
        worldGrid.setEntity(15, 15, wrapped);

        var bot = botRegistry.getBySession("s1").orElseThrow();
        var perception = broadcaster.buildPerception(1, bot);

        // NW corner of the neighbourhood should show the wrapped entity
        // NW = row 0, col 0 (top-left of the 5x5 grid, which is offset -2,-2 from centre)
        // (0-2, 0-2) wraps to (14, 14) — but we placed at (15,15) which is offset -1,-1
        // So look at row 1, col 1 (offset -1, -1)
        CellView nwView = perception.neighbourhood().get(1).get(1);
        assertThat(nwView.occupantType()).isEqualTo("CATALYST");
        assertThat(nwView.occupantId()).isEqualTo("e2");
    }

    @Test
    void cellToViewEmpty() {
        CellView view = PerceptionBroadcaster.cellToView(Cell.EMPTY);
        assertThat(view.occupantType()).isNull();
        assertThat(view.occupantId()).isNull();
        assertThat(view.nutrientLevel()).isZero();
        assertThat(view.flags()).isZero();
    }

    // ── Phase 13 Plan 02: FLAG_STARVING visible in perception ──

    @Test
    void cellToViewIncludesFlags() {
        // Cell with both FLAG_OVERCROWDED (1) and FLAG_STARVING (2) → flags=3
        Cell cell = Cell.EMPTY
                .withOccupant(Particle.spawn("p1", ParticleType.CATALYST))
                .withAddedFlag(Cell.FLAG_OVERCROWDED)
                .withAddedFlag(Cell.FLAG_STARVING);
        CellView view = PerceptionBroadcaster.cellToView(cell);
        assertThat(view.flags()).isEqualTo(Cell.FLAG_OVERCROWDED | Cell.FLAG_STARVING);
    }

    @Test
    void cellToViewFlagStarvingVisible() {
        Cell cell = Cell.EMPTY
                .withOccupant(Particle.spawn("p1", ParticleType.SPORE))
                .withAddedFlag(Cell.FLAG_STARVING);
        CellView view = PerceptionBroadcaster.cellToView(cell);
        assertThat((view.flags() & Cell.FLAG_STARVING) != 0).isTrue();
    }

    @Test
    void cellToViewParticle() {
        Cell cell = Cell.EMPTY.withOccupant(Particle.spawn("p1", ParticleType.MEMBRANE));
        CellView view = PerceptionBroadcaster.cellToView(cell);
        assertThat(view.occupantType()).isEqualTo("MEMBRANE");
        assertThat(view.occupantId()).isEqualTo("p1");
    }

    @Test
    void cellToViewRock() {
        Cell cell = Cell.EMPTY.withOccupant(new Entity.Rock("r1"));
        CellView view = PerceptionBroadcaster.cellToView(cell);
        assertThat(view.occupantType()).isEqualTo("ROCK");
        assertThat(view.occupantId()).isEqualTo("r1");
    }

    @Test
    void cellToViewNutrient() {
        Cell cell = Cell.EMPTY.withOccupant(Entity.Nutrient.spawn("n1"));
        CellView view = PerceptionBroadcaster.cellToView(cell);
        assertThat(view.occupantType()).isEqualTo("NUTRIENT");
        assertThat(view.occupantId()).isEqualTo("n1");
    }

    @Test
    void cellToViewBondedPair() {
        Entity.BondedPair bp = new Entity.BondedPair(
                "bond-1", ParticleType.CATALYST, ParticleType.SPORE, 80, 200);
        Cell cell = Cell.EMPTY.withOccupant(bp);

        CellView view = PerceptionBroadcaster.cellToView(cell);

        assertThat(view.occupantType()).isEqualTo("BONDED_CATALYST_SPORE");
        assertThat(view.occupantId()).isEqualTo("bond-1");
    }

    @Test
    void cellToViewBondedPairReflectsTypes() {
        // Verify different type combinations produce correct strings
        Entity.BondedPair bp = new Entity.BondedPair(
                "bond-2", ParticleType.MEMBRANE, ParticleType.CATALYST, 60, 200);
        Cell cell = Cell.EMPTY.withOccupant(bp);

        CellView view = PerceptionBroadcaster.cellToView(cell);

        assertThat(view.occupantType()).isEqualTo("BONDED_MEMBRANE_CATALYST");
        assertThat(view.occupantId()).isEqualTo("bond-2");
    }

    @Test
    void onTickSendsPerceptionToRegisteredBots() throws Exception {
        // Set up a mock session
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        Particle particle = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        // Fire tick event
        broadcaster.onTick(new TickEvent(1));

        // Verify a message was sent
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void onTickSkipsClosedSessions() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(false);
        sessionRegistry.register(session);

        botRegistry.register("s1", "e1", new Position(5, 5));

        broadcaster.onTick(new TickEvent(1));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void onTickNoBots() {
        // Should not throw when no bots registered
        broadcaster.onTick(new TickEvent(1));
    }

    @Test
    void compositeMemberGetsCompositePerception() throws Exception {
        // Place a CompositeMember bot — should receive CompositePerception, not regular Perception
        var sensor = new CompositeMember("m1", "c1", ParticleType.CATALYST, Role.SENSOR, 50, 100);
        worldGrid.setEntity(5, 5, sensor);

        compositeRegistry.register("c1", List.of("m1"),
                Map.of("m1", new Position(5, 5)), 100, 200);

        botRegistry.register("s1", "m1", new Position(5, 5));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        var msg = objectMapper.readValue(captor.getValue().getPayload(), Messages.class);
        assertThat(msg).isInstanceOf(Messages.CompositePerception.class);
    }

    @Test
    void regularParticleBotGetsRegularPerception() throws Exception {
        // Particle bot should still get regular Perception (not CompositePerception)
        Particle particle = Particle.spawn("e1", ParticleType.CATALYST);
        worldGrid.setEntity(5, 5, particle);
        botRegistry.register("s1", "e1", new Position(5, 5));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);

        broadcaster.onTick(new TickEvent(1));

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        var msg = objectMapper.readValue(captor.getValue().getPayload(), Messages.class);
        assertThat(msg).isInstanceOf(Messages.Perception.class);
    }
}
