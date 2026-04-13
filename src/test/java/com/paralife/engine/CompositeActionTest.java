package com.paralife.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.websocket.Messages;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeActionTest {

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
        worldGrid = new WorldGrid(new GridConfig(8, 8));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry();
        compositeRegistry = new CompositeRegistry();
        compositeConfig = CompositeConfig.defaults();
        config = SimulationConfig.defaults();
        objectMapper = new ObjectMapper();
        resolver = new ActionResolver(worldGrid, botRegistry, sessionRegistry, config,
                objectMapper, compositeRegistry, compositeConfig);
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

    private void registerComposite(String compositeId, String... memberIds) {
        var memberIdList = java.util.List.of(memberIds);
        var positions = new java.util.HashMap<String, Position>();
        for (String mid : memberIds) {
            var bot = botRegistry.getBySession(getSessionForEntity(mid));
            if (bot.isPresent()) {
                positions.put(mid, bot.get().position());
            }
        }
        compositeRegistry.register(compositeId, memberIdList, positions, 100, 200);
    }

    private String getSessionForEntity(String entityId) {
        return botRegistry.getSessionForEntity(entityId).orElse(null);
    }

    // ── FEEDER tests ─────────────────────────────────────────────

    @Test
    void feederAutoConsumesAdjacentNutrient() {
        mockSession("s-feeder");
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(3, 3), 50);
        worldGrid.setEntity(3, 4, Nutrient.spawn("n1"));
        registerComposite("comp1", "cm-feeder");

        resolver.resolveActions(1, Map.of("s-feeder", new Messages.Action("consume", null)));

        // Nutrient should be consumed
        Cell nutrientCell = worldGrid.getCell(3, 4);
        if (nutrientCell.hasOccupant() && nutrientCell.occupant() instanceof Nutrient n) {
            assertThat(n.level()).isLessThan(Nutrient.DEFAULT_LEVEL);
        }
        // else fully depleted — also valid
    }

    @Test
    void feederEnergyGoesToSharedPool() {
        mockSession("s-feeder");
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(3, 3), 50);
        worldGrid.setEntity(3, 4, Nutrient.spawn("n1"));
        registerComposite("comp1", "cm-feeder");

        int poolBefore = compositeRegistry.getSharedEnergy("comp1");

        resolver.resolveActions(1, Map.of("s-feeder", new Messages.Action("consume", null)));

        // Shared pool should have increased
        int poolAfter = compositeRegistry.getSharedEnergy("comp1");
        assertThat(poolAfter).isGreaterThan(poolBefore);

        // Member individual energy should NOT have changed on the grid entity
        // (energy goes to shared pool per D-15)
        Cell cell = worldGrid.getCell(3, 3);
        assertThat(cell.occupant()).isInstanceOf(CompositeMember.class);
        CompositeMember member = (CompositeMember) cell.occupant();
        assertThat(member.energy()).isEqualTo(50);
    }

    // ── ATTACKER tests ───────────────────────────────────────────

    @Test
    void attackerAutoAttacksAdjacentEnemy() {
        mockSession("s-attacker");
        mockSession("s-target");
        placeCompositeMember("s-attacker", "cm-attacker", "comp1", ParticleType.CATALYST,
                Role.ATTACKER, new Position(3, 3), 50);
        // Target is a solo Particle (enemy)
        Particle target = new Particle("e-target", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(3, 4, target);
        botRegistry.register("s-target", "e-target", new Position(3, 4));
        registerComposite("comp1", "cm-attacker");

        resolver.resolveActions(1, Map.of("s-attacker", new Messages.Action("attack", "S")));

        // Target should have taken damage
        Cell targetCell = worldGrid.getCell(3, 4);
        assertThat(targetCell.occupant()).isInstanceOf(Particle.class);
        Particle damaged = (Particle) targetCell.occupant();
        assertThat(damaged.energy()).isLessThan(50);
    }

    @Test
    void attackerDealsTrueDamage() {
        // ATTACKER should attack regardless of RPS type (D-10: true damage, type-agnostic)
        mockSession("s-attacker");
        mockSession("s-target");
        // CATALYST attacking MEMBRANE — membrane beats catalyst normally,
        // but ATTACKER deals true damage regardless
        placeCompositeMember("s-attacker", "cm-attacker", "comp1", ParticleType.CATALYST,
                Role.ATTACKER, new Position(3, 3), 50);
        Particle target = new Particle("e-target", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(3, 4, target);
        botRegistry.register("s-target", "e-target", new Position(3, 4));
        registerComposite("comp1", "cm-attacker");

        resolver.resolveActions(1, Map.of("s-attacker", new Messages.Action("attack", "S")));

        // Target should take damage regardless of type matchup
        Cell targetCell = worldGrid.getCell(3, 4);
        Particle damaged = (Particle) targetCell.occupant();
        assertThat(damaged.energy()).isEqualTo(50 - config.combatEnergyTransfer());
    }

    // ── REPRODUCER tests ─────────────────────────────────────────

    @Test
    void reproducerBudsParticle() {
        mockSession("s-reproducer");
        placeCompositeMember("s-reproducer", "cm-reproducer", "comp1", ParticleType.SPORE,
                Role.REPRODUCER, new Position(3, 3), 50);
        registerComposite("comp1", "cm-reproducer");
        // Set shared pool to enough for reproduction
        compositeRegistry.addEnergy("comp1", ActionResolver.REPRODUCE_ENERGY_COST);

        int poolBefore = compositeRegistry.getSharedEnergy("comp1");

        resolver.resolveActions(1, Map.of("s-reproducer", new Messages.Action("reproduce", "E")));

        // New Particle should be spawned at (4, 3)
        Cell targetCell = worldGrid.getCell(4, 3);
        assertThat(targetCell.occupant()).isInstanceOf(Particle.class);
        Particle child = (Particle) targetCell.occupant();
        assertThat(child.type()).isEqualTo(ParticleType.SPORE);
        assertThat(child.energy()).isEqualTo(ActionResolver.CHILD_START_ENERGY);

        // Shared pool should have decreased
        int poolAfter = compositeRegistry.getSharedEnergy("comp1");
        assertThat(poolAfter).isLessThan(poolBefore);
    }

    @Test
    void reproducerFailsWhenPoolInsufficient() {
        mockSession("s-reproducer");
        placeCompositeMember("s-reproducer", "cm-reproducer", "comp1", ParticleType.SPORE,
                Role.REPRODUCER, new Position(3, 3), 50);
        // Register with very low pool energy
        var memberIds = java.util.List.of("cm-reproducer");
        var positions = Map.of("cm-reproducer", new Position(3, 3));
        compositeRegistry.register("comp1", memberIds, positions, 5, 200);

        resolver.resolveActions(1, Map.of("s-reproducer", new Messages.Action("reproduce", "E")));

        // No child spawned
        Cell targetCell = worldGrid.getCell(4, 3);
        assertThat(targetCell.isEmpty()).isTrue();

        // Pool unchanged
        assertThat(compositeRegistry.getSharedEnergy("comp1")).isEqualTo(5);
    }

    // ── Passive role tests ───────────────────────────────────────

    @Test
    void sensorDoesNotAct() {
        mockSession("s-sensor");
        placeCompositeMember("s-sensor", "cm-sensor", "comp1", ParticleType.CATALYST,
                Role.SENSOR, new Position(3, 3), 50);
        registerComposite("comp1", "cm-sensor");

        // Place a nutrient nearby — SENSOR should NOT consume it
        worldGrid.setEntity(3, 4, Nutrient.spawn("n1"));

        resolver.resolveActions(1, Map.of("s-sensor", new Messages.Action("consume", null)));

        // Nutrient should still be there (SENSOR is passive)
        Cell nutrientCell = worldGrid.getCell(3, 4);
        assertThat(nutrientCell.occupant()).isInstanceOf(Nutrient.class);
    }

    @Test
    void defenderDoesNotAutoAct() {
        mockSession("s-defender");
        placeCompositeMember("s-defender", "cm-defender", "comp1", ParticleType.CATALYST,
                Role.DEFENDER, new Position(3, 3), 50);
        registerComposite("comp1", "cm-defender");

        // Place a target nearby — DEFENDER should NOT attack
        Particle target = new Particle("e-target", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(3, 4, target);

        resolver.resolveActions(1, Map.of("s-defender", new Messages.Action("attack", "S")));

        // Target should be untouched
        Cell targetCell = worldGrid.getCell(3, 4);
        Particle p = (Particle) targetCell.occupant();
        assertThat(p.energy()).isEqualTo(50);
    }

    @Test
    void compositeMemberActionDoesNotAlterIndividualEnergy() {
        mockSession("s-feeder");
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(3, 3), 50);
        worldGrid.setEntity(3, 4, Nutrient.spawn("n1"));
        registerComposite("comp1", "cm-feeder");

        resolver.resolveActions(1, Map.of("s-feeder", new Messages.Action("consume", null)));

        // Member's individual energy should remain unchanged (D-15: energy to shared pool)
        Cell cell = worldGrid.getCell(3, 3);
        CompositeMember member = (CompositeMember) cell.occupant();
        assertThat(member.energy()).isEqualTo(50);
    }
}
