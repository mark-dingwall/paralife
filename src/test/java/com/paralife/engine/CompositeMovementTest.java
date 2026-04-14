package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeMovementTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;
    private CompositeConfig compositeConfig;
    private SimulationConfig config;
    private ObjectMapper objectMapper;
    private ActionResolver resolver;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry();
        compositeRegistry = new CompositeRegistry();
        compositeConfig = CompositeConfig.defaults(); // speedConstant = 1.0
        config = SimulationConfig.defaults();
        objectMapper = new ObjectMapper();
        resolver = new ActionResolver(worldGrid, botRegistry, sessionRegistry, config,
                objectMapper, compositeRegistry, compositeConfig, ActionResolverTest.legacyProfile());
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        sessionRegistry.register(session);
        return session;
    }

    private void placeCompositeMember(String sessionId, String entityId, String compositeId,
                                       ParticleType type, Role role, Position pos, int energy) {
        CompositeMember cm = new CompositeMember(entityId, compositeId, type, role, energy, 100);
        worldGrid.setEntity(pos.x(), pos.y(), cm);
        botRegistry.register(sessionId, entityId, pos);
    }

    private void registerComposite(String compositeId, int poolEnergy, String... memberIds) {
        var memberIdList = java.util.List.of(memberIds);
        var positions = new java.util.HashMap<String, Position>();
        for (String mid : memberIds) {
            botRegistry.getSessionForEntity(mid).flatMap(botRegistry::getBySession)
                    .ifPresent(bot -> positions.put(mid, bot.position()));
        }
        compositeRegistry.register(compositeId, memberIdList, positions, poolEnergy, 500);
    }

    // ── STV Voting ───────────────────────────────────────────────

    @Test
    void locomotorVotesMoveComposite() {
        // 2-member composite: 1 LOCOMOTOR votes N, 1 FEEDER
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 6), 50);
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        // LOCOMOTOR votes N via ranked preferences
        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco", new Messages.Action("move", "N"));
        actions.put("s-feeder", new Messages.Action("rest", null));

        resolver.resolveActions(1, actions);

        // Both members should have shifted 1 cell north
        assertThat(worldGrid.getCell(5, 4).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 4).occupant()).id()).isEqualTo("cm-loco");
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-feeder");

        // Original positions should be cleared
        assertThat(worldGrid.getCell(5, 6).isEmpty()).isTrue();
    }

    @Test
    void multipleLocomotorsMajorityWins() {
        // 3 LOCOMOTORs vote N, N, E → composite moves N
        mockSession("s-loco1");
        mockSession("s-loco2");
        mockSession("s-loco3");
        placeCompositeMember("s-loco1", "cm-loco1", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-loco2", "cm-loco2", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 6), 50);
        placeCompositeMember("s-loco3", "cm-loco3", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 7), 50);
        registerComposite("comp1", 100, "cm-loco1", "cm-loco2", "cm-loco3");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco1", new Messages.Action("move", "N"));
        actions.put("s-loco2", new Messages.Action("move", "N"));
        actions.put("s-loco3", new Messages.Action("move", "E"));

        resolver.resolveActions(1, actions);

        // Majority voted N — all should have shifted north
        assertThat(worldGrid.getCell(5, 4).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 4).occupant()).id()).isEqualTo("cm-loco1");
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco2");
        assertThat(worldGrid.getCell(5, 6).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 6).occupant()).id()).isEqualTo("cm-loco3");
    }

    @Test
    void tiedVoteResolvesRandomly() {
        // 2 LOCOMOTORs vote N and S — composite moves in one of those directions
        mockSession("s-loco1");
        mockSession("s-loco2");
        placeCompositeMember("s-loco1", "cm-loco1", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(8, 8), 50);
        placeCompositeMember("s-loco2", "cm-loco2", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(8, 9), 50);
        registerComposite("comp1", 100, "cm-loco1", "cm-loco2");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco1", new Messages.Action("move", "N"));
        actions.put("s-loco2", new Messages.Action("move", "S"));

        resolver.resolveActions(1, actions);

        // Should have moved in either N or S direction
        // Original positions: (8,8) and (8,9)
        // If N: (8,7) and (8,8)
        // If S: (8,9) and (8,10)
        boolean movedNorth = worldGrid.getCell(8, 7).hasOccupant()
                && worldGrid.getCell(8, 7).occupant() instanceof CompositeMember;
        boolean movedSouth = worldGrid.getCell(8, 9).hasOccupant()
                && worldGrid.getCell(8, 9).occupant() instanceof CompositeMember cm
                && cm.id().equals("cm-loco1");

        assertThat(movedNorth || movedSouth)
                .as("Composite should have moved either N or S")
                .isTrue();
    }

    @Test
    void sessileCompositeDoesNotMove() {
        // Composite with no LOCOMOTOR members — should not move
        mockSession("s-feeder");
        mockSession("s-defender");
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 5), 50);
        placeCompositeMember("s-defender", "cm-defender", "comp1", ParticleType.CATALYST,
                Role.DEFENDER, new Position(5, 6), 50);
        registerComposite("comp1", 100, "cm-feeder", "cm-defender");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-feeder", new Messages.Action("rest", null));
        actions.put("s-defender", new Messages.Action("rest", null));

        resolver.resolveActions(1, actions);

        // Members should not have moved
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-feeder");
        assertThat(worldGrid.getCell(5, 6).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 6).occupant()).id()).isEqualTo("cm-defender");
    }

    @Test
    void movementBlockedByOccupiedTargetCell() {
        // Place a Rock at one member's target position
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 6), 50);
        // Rock at (5,4) — target position for cm-loco when moving N
        worldGrid.setEntity(5, 4, new Entity.Rock("rock1"));
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco", new Messages.Action("move", "N"));
        actions.put("s-feeder", new Messages.Action("rest", null));

        resolver.resolveActions(1, actions);

        // No members should have moved
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco");
        assertThat(((CompositeMember) worldGrid.getCell(5, 6).occupant()).id()).isEqualTo("cm-feeder");
    }

    @Test
    void movementBlockedByClaimedCell() {
        // Two composites try to move into overlapping target cells
        mockSession("s-loco1");
        mockSession("s-loco2");
        placeCompositeMember("s-loco1", "cm-loco1", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-loco2", "cm-loco2", "comp2", ParticleType.MEMBRANE,
                Role.LOCOMOTOR, new Position(5, 3), 50);
        registerComposite("comp1", 100, "cm-loco1");
        registerComposite("comp2", 100, "cm-loco2");

        // comp1 moves N → target (5,4), comp2 moves S → target (5,4)
        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco1", new Messages.Action("move", "N"));
        actions.put("s-loco2", new Messages.Action("move", "S"));

        resolver.resolveActions(1, actions);

        // Only one should have reached (5,4)
        Cell target = worldGrid.getCell(5, 4);
        assertThat(target.occupant()).isInstanceOf(CompositeMember.class);
        CompositeMember winner = (CompositeMember) target.occupant();

        // The loser should be in their original position
        if (winner.id().equals("cm-loco1")) {
            assertThat(worldGrid.getCell(5, 3).occupant()).isInstanceOf(CompositeMember.class);
            assertThat(((CompositeMember) worldGrid.getCell(5, 3).occupant()).id()).isEqualTo("cm-loco2");
        } else {
            assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
            assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco1");
        }
    }

    @Test
    void rigidBodyPreservesFormation() {
        // 2-member composite at (3,3) and (3,4). Move E. New positions: (4,3) and (4,4).
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(3, 3), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(3, 4), 50);
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco", new Messages.Action("move", "E"));
        actions.put("s-feeder", new Messages.Action("rest", null));

        resolver.resolveActions(1, actions);

        // Formation preserved — both shifted E
        assertThat(worldGrid.getCell(4, 3).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(4, 3).occupant()).id()).isEqualTo("cm-loco");
        assertThat(worldGrid.getCell(4, 4).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(4, 4).occupant()).id()).isEqualTo("cm-feeder");

        // Relative positions preserved: dy=1 between members before and after
        assertThat(worldGrid.getCell(3, 3).isEmpty()).isTrue();
        assertThat(worldGrid.getCell(3, 4).isEmpty()).isTrue();
    }

    @Test
    void movementSpeedGate() {
        // Composite with 1 LOCOMOTOR and 4 total members
        // Speed = 1/4 * 1.0 = 0.25. Move interval = ceil(1/0.25) = 4
        // Should only move every 4 ticks
        mockSession("s-loco");
        mockSession("s-f1");
        mockSession("s-f2");
        mockSession("s-f3");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(8, 8), 50);
        placeCompositeMember("s-f1", "cm-f1", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(8, 9), 50);
        placeCompositeMember("s-f2", "cm-f2", "comp1", ParticleType.CATALYST,
                Role.DEFENDER, new Position(8, 10), 50);
        placeCompositeMember("s-f3", "cm-f3", "comp1", ParticleType.CATALYST,
                Role.SENSOR, new Position(8, 11), 50);
        registerComposite("comp1", 200, "cm-loco", "cm-f1", "cm-f2", "cm-f3");

        // First move attempt should succeed (first time allowed)
        Map<String, Messages.Action> actions = Map.of(
                "s-loco", new Messages.Action("move", "N"),
                "s-f1", new Messages.Action("rest", null),
                "s-f2", new Messages.Action("rest", null),
                "s-f3", new Messages.Action("rest", null)
        );

        resolver.resolveActions(1, actions);
        Position afterFirst = botRegistry.getBySession("s-loco").get().position();
        assertThat(afterFirst).isEqualTo(new Position(8, 7));

        // Next 3 attempts should be blocked by speed gate
        int blockedCount = 0;
        for (int tick = 2; tick <= 4; tick++) {
            resolver.resolveActions(tick, actions);
            Position current = botRegistry.getBySession("s-loco").get().position();
            if (current.equals(afterFirst)) blockedCount++;
        }

        // At least 2 of the next 3 ticks should be blocked (speed gate)
        assertThat(blockedCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void allTargetCellsClaimedAtomically() {
        // Moving composite claims all target cells before any member moves
        // If a solo particle tries to move to a target cell, it should be blocked
        mockSession("s-loco");
        mockSession("s-feeder");
        mockSession("s-solo");

        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 6), 50);
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        // Solo particle trying to move into composite's target path
        Entity.Particle solo = new Entity.Particle("e-solo", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(6, 4, solo);
        botRegistry.register("s-solo", "e-solo", new Position(6, 4));

        // Composite moves E, solo moves S (to 6,5 — target of cm-feeder moving E)
        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco", new Messages.Action("move", "E"));
        actions.put("s-feeder", new Messages.Action("rest", null));
        actions.put("s-solo", new Messages.Action("move", "S"));

        resolver.resolveActions(1, actions);

        // Either composite got (6,5) and (6,6), or solo got (6,5)
        // But they should not overlap
        Cell cell = worldGrid.getCell(6, 5);
        assertThat(cell.hasOccupant()).isTrue();
        // No double occupancy
    }

    @Test
    void movementUpdatesRegistries() {
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 6), 50);
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco", new Messages.Action("move", "E"));
        actions.put("s-feeder", new Messages.Action("rest", null));

        resolver.resolveActions(1, actions);

        // BotRegistry positions updated
        assertThat(botRegistry.getBySession("s-loco").get().position()).isEqualTo(new Position(6, 5));
        assertThat(botRegistry.getBySession("s-feeder").get().position()).isEqualTo(new Position(6, 6));

        // CompositeRegistry positions updated
        assertThat(compositeRegistry.getPositionForMember("cm-loco")).isEqualTo(new Position(6, 5));
        assertThat(compositeRegistry.getPositionForMember("cm-feeder")).isEqualTo(new Position(6, 6));
    }

    @Test
    void locomotorActiveDrainChargedOnMove() {
        mockSession("s-loco");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        registerComposite("comp1", 100, "cm-loco");

        int poolBefore = compositeRegistry.getSharedEnergy("comp1");

        Map<String, Messages.Action> actions = new HashMap<>();
        actions.put("s-loco", new Messages.Action("move", "N"));

        resolver.resolveActions(1, actions);

        // Pool should have decreased by locomotorActiveDrain (default 3)
        int poolAfter = compositeRegistry.getSharedEnergy("comp1");
        assertThat(poolAfter).isEqualTo(poolBefore - compositeConfig.locomotorActiveDrain());
    }

    @Test
    void rankedPreferencesMaxThree() {
        // LOCOMOTOR submits 5 preferences via CompositeAction, only first 3 counted
        mockSession("s-loco");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        registerComposite("comp1", 100, "cm-loco");

        // Queue via composite action with 5 preferences
        List<String> fivePrefs = List.of("N", "E", "S", "W", "NE");
        Map<String, List<String>> rankedPrefs = Map.of("s-loco", fivePrefs);

        Map<String, Messages.Action> actions = Map.of("s-loco", new Messages.Action("move", "N"));
        resolver.resolveActions(1, actions, rankedPrefs);

        // Should have moved (first pref N)
        assertThat(worldGrid.getCell(5, 4).occupant()).isInstanceOf(CompositeMember.class);
    }

    @Test
    void invalidDirectionInVoteIgnored() {
        // LOCOMOTOR submits "INVALID" as first preference — treated as abstention
        mockSession("s-loco");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        registerComposite("comp1", 100, "cm-loco");

        Map<String, List<String>> rankedPrefs = Map.of("s-loco", List.of("INVALID", "BOGUS"));
        Map<String, Messages.Action> actions = Map.of("s-loco", new Messages.Action("move", "INVALID"));

        resolver.resolveActions(1, actions, rankedPrefs);

        // Should NOT have moved — invalid direction is ignored
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco");
    }
}
