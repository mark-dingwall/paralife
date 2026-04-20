package com.paralife.engine;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 15-11 (Task 2): LOCOMOTOR IRV voting + rigid-body composite movement.
 *
 * <p>Migrated from the Messages-era {@code Messages.Action} / {@code Map<String, List<String>>}
 * ranked-preference API to SCHEMA §8.6 verbs + the 3-char numpad IRV ballot
 * carried on {@code ActionFrame.arg()}. Per-direction numpad digits used here:
 * {@code 8=N, 6=E, 2=S, 4=W}.
 */
class CompositeMovementTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;
    private CompositeConfig compositeConfig;
    private SimulationConfig config;
    private ActionResolver resolver;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        compositeRegistry = new CompositeRegistry();
        compositeConfig = CompositeConfig.defaults();
        config = SimulationConfig.defaults();
        resolver = new ActionResolver(worldGrid, botRegistry, sessionRegistry, config,
                compositeRegistry, compositeConfig, legacyProfile());
    }

    /**
     * Legacy-flat {@link MetabolicProfile} for pre-Phase-13 assertion parity.
     * Inlined to avoid a cross-dependency on the excluded
     * {@code ActionResolverTest} class.
     */
    static MetabolicProfile legacyProfile() {
        MetabolicProfile.TypeProfile p = new MetabolicProfile.TypeProfile(
                40, 1, 10, 10, 5,
                ActionResolver.REPRODUCE_ENERGY_COST,
                0, 0.0, 1, 0, 0);
        return new MetabolicProfile(p, p, p);
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

    // ── Frame.ActionFrame convenience builders ────────────────────────

    /** Vote with a single-direction ranking — fills the 3-char IRV ballot with the direction. */
    private static Frame.ActionFrame voteSingle(char numpad) {
        String arg = "" + numpad + numpad + numpad;  // ballot must be 3 chars
        return new Frame.ActionFrame('V', Optional.of(arg));
    }

    /** Full 3-char numpad IRV ballot. */
    private static Frame.ActionFrame vote(String threeCharBallot) {
        if (threeCharBallot.length() != 3) {
            throw new IllegalArgumentException("IRV ballot must be 3 chars");
        }
        return new Frame.ActionFrame('V', Optional.of(threeCharBallot));
    }

    /** Rest — LOCOMOTOR solo-particle rest. Returns a vote with no valid direction. */
    private static Frame.ActionFrame rest() {
        // Per SCHEMA there is no explicit rest verb; for non-LOCOMOTOR members we
        // skip submitting; tests use V with self/self/self (numpad 5 forbidden on wire,
        // so we use a non-participating numpad 5? Actually schema forbids 5 in ballot
        // — easiest is to omit the action altogether rather than submit noise).
        return null;
    }

    // ── STV Voting ───────────────────────────────────────────────

    @Test
    void locomotorVotesMoveComposite() {
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 6), 50);
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        // Single LOCOMOTOR votes N (numpad 8).
        Map<String, Frame.ActionFrame> actions = new HashMap<>();
        actions.put("s-loco", voteSingle('8'));
        // s-feeder: no action submitted.

        Map<String, String> ballots = new HashMap<>();
        ballots.put("s-loco", "888");

        resolver.resolveActions(1, actions, ballots);

        // Both members should have shifted 1 cell north.
        assertThat(worldGrid.getCell(5, 4).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 4).occupant()).id()).isEqualTo("cm-loco");
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-feeder");

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

        Map<String, Frame.ActionFrame> actions = new HashMap<>();
        actions.put("s-loco1", voteSingle('8'));
        actions.put("s-loco2", voteSingle('8'));
        actions.put("s-loco3", voteSingle('6')); // E

        Map<String, String> ballots = new HashMap<>();
        ballots.put("s-loco1", "888");
        ballots.put("s-loco2", "888");
        ballots.put("s-loco3", "666");

        resolver.resolveActions(1, actions, ballots);

        // Majority voted N → all should have shifted north.
        assertThat(worldGrid.getCell(5, 4).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 4).occupant()).id()).isEqualTo("cm-loco1");
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco2");
        assertThat(worldGrid.getCell(5, 6).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 6).occupant()).id()).isEqualTo("cm-loco3");
    }

    @Test
    void tiedVoteResolvesToSomeDirection() {
        // 2 LOCOMOTORs vote N and S — tie broken by the resolver (randomly).
        mockSession("s-loco1");
        mockSession("s-loco2");
        placeCompositeMember("s-loco1", "cm-loco1", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(8, 8), 50);
        placeCompositeMember("s-loco2", "cm-loco2", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(8, 9), 50);
        registerComposite("comp1", 100, "cm-loco1", "cm-loco2");

        Map<String, Frame.ActionFrame> actions = new HashMap<>();
        actions.put("s-loco1", voteSingle('8')); // N
        actions.put("s-loco2", voteSingle('2')); // S

        Map<String, String> ballots = new HashMap<>();
        ballots.put("s-loco1", "888");
        ballots.put("s-loco2", "222");

        resolver.resolveActions(1, actions, ballots);

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
        // Composite without any LOCOMOTOR member — no vote, no movement.
        mockSession("s-feeder");
        mockSession("s-defender");
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 5), 50);
        placeCompositeMember("s-defender", "cm-defender", "comp1", ParticleType.CATALYST,
                Role.DEFENDER, new Position(5, 6), 50);
        registerComposite("comp1", 100, "cm-feeder", "cm-defender");

        // No V actions — passive + authority-lite don't vote.
        Map<String, Frame.ActionFrame> actions = new HashMap<>();

        resolver.resolveActions(1, actions, Map.of());

        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-feeder");
        assertThat(worldGrid.getCell(5, 6).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(5, 6).occupant()).id()).isEqualTo("cm-defender");
    }

    @Test
    void movementBlockedByOccupiedTargetCell() {
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(5, 6), 50);
        // Rock at (5,4) — the N target for cm-loco.
        worldGrid.setEntity(5, 4, new Entity.Rock("rock1"));
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        Map<String, Frame.ActionFrame> actions = Map.of("s-loco", voteSingle('8'));
        resolver.resolveActions(1, actions, Map.of("s-loco", "888"));

        assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco");
        assertThat(((CompositeMember) worldGrid.getCell(5, 6).occupant()).id()).isEqualTo("cm-feeder");
    }

    @Test
    void movementBlockedByClaimedCell() {
        mockSession("s-loco1");
        mockSession("s-loco2");
        placeCompositeMember("s-loco1", "cm-loco1", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        placeCompositeMember("s-loco2", "cm-loco2", "comp2", ParticleType.MEMBRANE,
                Role.LOCOMOTOR, new Position(5, 3), 50);
        registerComposite("comp1", 100, "cm-loco1");
        registerComposite("comp2", 100, "cm-loco2");

        Map<String, Frame.ActionFrame> actions = Map.of(
                "s-loco1", voteSingle('8'),
                "s-loco2", voteSingle('2'));
        Map<String, String> ballots = Map.of(
                "s-loco1", "888",
                "s-loco2", "222");

        resolver.resolveActions(1, actions, ballots);

        Cell target = worldGrid.getCell(5, 4);
        assertThat(target.occupant()).isInstanceOf(CompositeMember.class);
        CompositeMember winner = (CompositeMember) target.occupant();

        if (winner.id().equals("cm-loco1")) {
            assertThat(((CompositeMember) worldGrid.getCell(5, 3).occupant()).id()).isEqualTo("cm-loco2");
        } else {
            assertThat(((CompositeMember) worldGrid.getCell(5, 5).occupant()).id()).isEqualTo("cm-loco1");
        }
    }

    @Test
    void rigidBodyPreservesFormation() {
        mockSession("s-loco");
        mockSession("s-feeder");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(3, 3), 50);
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(3, 4), 50);
        registerComposite("comp1", 100, "cm-loco", "cm-feeder");

        Map<String, Frame.ActionFrame> actions = Map.of("s-loco", voteSingle('6')); // E
        resolver.resolveActions(1, actions, Map.of("s-loco", "666"));

        assertThat(worldGrid.getCell(4, 3).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(4, 3).occupant()).id()).isEqualTo("cm-loco");
        assertThat(worldGrid.getCell(4, 4).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(((CompositeMember) worldGrid.getCell(4, 4).occupant()).id()).isEqualTo("cm-feeder");

        assertThat(worldGrid.getCell(3, 3).isEmpty()).isTrue();
        assertThat(worldGrid.getCell(3, 4).isEmpty()).isTrue();
    }

    @Test
    void movementSpeedGate() {
        // 1 LOCOMOTOR among 4 members → speed = 1/4 * 1.0 = 0.25 → interval = 4.
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

        Map<String, Frame.ActionFrame> actions = Map.of("s-loco", voteSingle('8'));
        Map<String, String> ballots = Map.of("s-loco", "888");

        resolver.resolveActions(1, actions, ballots);
        Position afterFirst = botRegistry.getBySession("s-loco").get().position();
        assertThat(afterFirst).isEqualTo(new Position(8, 7));

        // Next 3 ticks — speed gate blocks at least 2.
        int blockedCount = 0;
        for (int tick = 2; tick <= 4; tick++) {
            resolver.resolveActions(tick, actions, ballots);
            Position current = botRegistry.getBySession("s-loco").get().position();
            if (current.equals(afterFirst)) blockedCount++;
        }

        assertThat(blockedCount).isGreaterThanOrEqualTo(2);
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

        Map<String, Frame.ActionFrame> actions = Map.of("s-loco", voteSingle('6')); // E
        resolver.resolveActions(1, actions, Map.of("s-loco", "666"));

        assertThat(botRegistry.getBySession("s-loco").get().position()).isEqualTo(new Position(6, 5));
        assertThat(botRegistry.getBySession("s-feeder").get().position()).isEqualTo(new Position(6, 6));
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

        Map<String, Frame.ActionFrame> actions = Map.of("s-loco", voteSingle('8')); // N
        resolver.resolveActions(1, actions, Map.of("s-loco", "888"));

        int poolAfter = compositeRegistry.getSharedEnergy("comp1");
        assertThat(poolAfter).isEqualTo(poolBefore - compositeConfig.locomotorActiveDrain());
    }

    @Test
    void irvBallotWithNonLeadingPreferencesFallsBack() {
        // LOCOMOTOR IRV ballot "864" = N, E, W — first-choice N should win.
        mockSession("s-loco");
        placeCompositeMember("s-loco", "cm-loco", "comp1", ParticleType.CATALYST,
                Role.LOCOMOTOR, new Position(5, 5), 50);
        registerComposite("comp1", 100, "cm-loco");

        Map<String, Frame.ActionFrame> actions = Map.of("s-loco", vote("864"));
        Map<String, String> ballots = Map.of("s-loco", "864");
        resolver.resolveActions(1, actions, ballots);

        // Should have moved N (first preference).
        assertThat(worldGrid.getCell(5, 4).occupant()).isInstanceOf(CompositeMember.class);
    }
}
