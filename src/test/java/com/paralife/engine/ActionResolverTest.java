package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Rock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionResolverTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private SimulationConfig config;
    private ObjectMapper objectMapper;
    private ActionResolver resolver;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry();
        config = SimulationConfig.defaults();
        objectMapper = new ObjectMapper();
        resolver = new ActionResolver(worldGrid, botRegistry, sessionRegistry, config, objectMapper,
                new CompositeRegistry(), CompositeConfig.defaults());
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);
        return session;
    }

    private void placeBot(String sessionId, String entityId, ParticleType type, Position pos) {
        Particle p = Particle.spawn(entityId, type);
        worldGrid.setEntity(pos.x(), pos.y(), p);
        botRegistry.register(sessionId, entityId, pos);
    }

    private void placeBot(String sessionId, String entityId, ParticleType type, Position pos, int energy) {
        Particle p = new Particle(entityId, type, energy);
        worldGrid.setEntity(pos.x(), pos.y(), p);
        botRegistry.register(sessionId, entityId, pos);
    }

    // ── Move tests ────────────────────────────────────────────────

    @Test
    void moveToEmptyCell() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "E")));

        // Entity should have moved east
        assertThat(worldGrid.getCell(5, 5).isEmpty()).isTrue();
        assertThat(worldGrid.getCell(6, 5).occupant()).isInstanceOf(Particle.class);
        assertThat(((Particle) worldGrid.getCell(6, 5).occupant()).id()).isEqualTo("e1");

        // BotRegistry should be updated
        assertThat(botRegistry.getBySession("s1").get().position()).isEqualTo(new Position(6, 5));
    }

    @Test
    void moveWrapsToroidally() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(15, 0));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "E")));

        // Should wrap to x=0
        assertThat(worldGrid.getCell(15, 0).isEmpty()).isTrue();
        assertThat(worldGrid.getCell(0, 0).occupant()).isInstanceOf(Particle.class);
        assertThat(botRegistry.getBySession("s1").get().position()).isEqualTo(new Position(0, 0));
    }

    @Test
    void moveIntoRockFails() throws Exception {
        WebSocketSession session = mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));
        worldGrid.setEntity(6, 5, new Rock("rock1"));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "E")));

        // Entity should not have moved
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(Particle.class);
        assertThat(((Particle) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("e1");

        // Should have sent failure result
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void moveIntoOccupiedCellFails() throws Exception {
        mockSession("s1");
        mockSession("s2");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));
        placeBot("s2", "e2", ParticleType.MEMBRANE, new Position(6, 5));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "E")));

        // Entity should not have moved
        assertThat(((Particle) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("e1");
        assertThat(((Particle) worldGrid.getCell(6, 5).occupant()).id()).isEqualTo("e2");
    }

    @Test
    void moveWithInvalidDirection() throws Exception {
        WebSocketSession session = mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "INVALID")));

        // Entity should not have moved
        assertThat(((Particle) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("e1");
        verify(session).sendMessage(any(TextMessage.class));
    }

    // ── Consume tests ─────────────────────────────────────────────

    @Test
    void consumeAdjacentNutrient() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));
        worldGrid.setEntity(6, 5, Nutrient.spawn("n1"));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("consume", null)));

        // Energy should have increased
        Particle p = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(p.energy()).isEqualTo(Particle.DEFAULT_START_ENERGY + config.nutrientConsumeEnergy());

        // Nutrient should be depleted (or reduced)
        Cell nutrientCell = worldGrid.getCell(6, 5);
        if (nutrientCell.hasOccupant()) {
            Nutrient n = (Nutrient) nutrientCell.occupant();
            assertThat(n.level()).isEqualTo(Nutrient.DEFAULT_LEVEL - config.nutrientConsumeEnergy());
        }
    }

    @Test
    void consumeWithNoNutrientFails() throws Exception {
        WebSocketSession session = mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("consume", null)));

        // Energy unchanged
        Particle p = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(p.energy()).isEqualTo(Particle.DEFAULT_START_ENERGY);

        verify(session).sendMessage(any(TextMessage.class));
    }

    // ── Reproduce tests ───────────────────────────────────────────

    @Test
    void reproduceIntoEmptyCell() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5), 60);

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("reproduce", "E")));

        // Parent should have lost energy
        Particle parent = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(parent.energy()).isEqualTo(60 - ActionResolver.REPRODUCE_ENERGY_COST);

        // Child should exist east
        assertThat(worldGrid.getCell(6, 5).occupant()).isInstanceOf(Particle.class);
        Particle child = (Particle) worldGrid.getCell(6, 5).occupant();
        assertThat(child.type()).isEqualTo(ParticleType.CATALYST);
        assertThat(child.energy()).isEqualTo(ActionResolver.CHILD_START_ENERGY);
    }

    @Test
    void reproduceWithInsufficientEnergy() throws Exception {
        WebSocketSession session = mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5), 10);

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("reproduce", "E")));

        // No child spawned
        assertThat(worldGrid.getCell(6, 5).isEmpty()).isTrue();
        // Parent energy unchanged
        Particle parent = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(parent.energy()).isEqualTo(10);
    }

    @Test
    void reproduceIntoOccupiedCellFails() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5), 60);
        worldGrid.setEntity(6, 5, new Rock("rock1"));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("reproduce", "E")));

        // Parent energy unchanged
        Particle parent = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(parent.energy()).isEqualTo(60);
    }

    // ── Rest tests ────────────────────────────────────────────────

    @Test
    void restIsNoOp() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("rest", null)));

        // Entity should not have moved
        assertThat(((Particle) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("e1");
    }

    // ── Conflict resolution ───────────────────────────────────────

    @Test
    void twoBotsMovingToSameCellOneWinsOneConflicts() throws Exception {
        mockSession("s1");
        mockSession("s2");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));
        placeBot("s2", "e2", ParticleType.MEMBRANE, new Position(7, 5));

        // Both try to move to (6,5) — s1 moves E, s2 moves W
        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s1", new Messages.Action("move", "E"));
        actions.put("s2", new Messages.Action("move", "W"));

        resolver.resolveActions(1, actions);

        // Exactly one should have moved to (6,5)
        Cell target = worldGrid.getCell(6, 5);
        assertThat(target.occupant()).isInstanceOf(Particle.class);
        String winnerId = ((Particle) target.occupant()).id();
        assertThat(winnerId).isIn("e1", "e2");

        // The other should be in their original position
        if ("e1".equals(winnerId)) {
            assertThat(worldGrid.getCell(5, 5).isEmpty()).isTrue();
            assertThat(worldGrid.getCell(7, 5).occupant()).isInstanceOf(Particle.class);
            assertThat(((Particle) worldGrid.getCell(7, 5).occupant()).id()).isEqualTo("e2");
        } else {
            assertThat(worldGrid.getCell(7, 5).isEmpty()).isTrue();
            assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(Particle.class);
            assertThat(((Particle) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("e1");
        }
    }

    // ── Edge cases ────────────────────────────────────────────────

    @Test
    void unregisteredSessionActionIgnored() throws Exception {
        WebSocketSession session = mockSession("s1");
        // Don't register in bot registry

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "E")));

        // Should send failure result
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void deadEntityActionIgnored() throws Exception {
        WebSocketSession session = mockSession("s1");
        botRegistry.register("s1", "e1", new Position(5, 5));
        // Don't place entity on grid (simulating death)

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("move", "E")));

        // Should unregister the bot
        assertThat(botRegistry.getBySession("s1")).isEmpty();
    }

    @Test
    void queueActionAndResolveOnTick() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));

        resolver.queueAction("s1", new Messages.Action("move", "E"));
        resolver.onTick(new TickEvent(1));

        // Entity should have moved
        assertThat(worldGrid.getCell(6, 5).occupant()).isInstanceOf(Particle.class);
    }

    @Test
    void unknownActionTypeTreatedAsRest() throws Exception {
        mockSession("s1");
        placeBot("s1", "e1", ParticleType.CATALYST, new Position(5, 5));

        resolver.resolveActions(1, Map.of("s1", new Messages.Action("dance", null)));

        // Entity should not have moved
        assertThat(((Particle) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("e1");
    }

    @Test
    void emptyActionsIsNoOp() {
        resolver.resolveActions(1, Map.of());
        // Should not throw
    }
}
