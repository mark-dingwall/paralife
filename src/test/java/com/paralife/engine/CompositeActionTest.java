package com.paralife.engine;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.*;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 15-11 (Task 2): composite reactive-role verb dispatch.
 *
 * <p>Migrated from {@code Messages.Action} → {@link Frame.ActionFrame}. Verbs
 * use SCHEMA §8.6 letter codes and numpad direction args:
 * <ul>
 *   <li>{@code E} — consume (no arg)</li>
 *   <li>{@code A|<numpad>} — attack (2 = S, 6 = E, etc.)</li>
 *   <li>{@code R|<numpad>} — reproduce</li>
 *   <li>{@code L} — alarm (no arg, LOCOMOTOR only)</li>
 * </ul>
 */
class CompositeActionTest {

    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;
    private CompositeConfig compositeConfig;
    private SimulationConfig config;
    private ActionResolver resolver;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(8, 8));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        compositeRegistry = new CompositeRegistry();
        compositeConfig = CompositeConfig.defaults();
        config = SimulationConfig.defaults();
        resolver = new ActionResolver(worldGrid, botRegistry, sessionRegistry, config,
                compositeRegistry, compositeConfig, legacyProfile());
    }

    /**
     * Legacy-flat {@link MetabolicProfile} matching the pre-Phase-13
     * {@link SimulationConfig} defaults. Inlined here (instead of shared with
     * the excluded {@code ActionResolverTest}) so plan 15-11 can re-enable
     * CompositeActionTest without forcing a parallel ActionResolverTest
     * migration.
     */
    static MetabolicProfile legacyProfile() {
        MetabolicProfile.TypeProfile p = new MetabolicProfile.TypeProfile(
                /* maxEnergy */ 40,
                /* decayPerTick */ 1,
                /* combatEnergyTransfer */ 10,
                /* attackPower */ 10,
                /* nutrientConsumeEnergy */ 5,
                /* reproduceEnergyCost */ ActionResolver.REPRODUCE_ENERGY_COST, // 30
                /* reproduceCooldown */ 0,
                /* bonusOffspringChance */ 0.0,
                /* reproduceRange */ 1,
                /* starvationThreshold */ 0,
                /* starvationFloor */ 0);
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

    private void registerComposite(String compositeId, String... memberIds) {
        var memberIdList = java.util.List.of(memberIds);
        var positions = new java.util.HashMap<String, Position>();
        for (String mid : memberIds) {
            var bot = botRegistry.getBySession(getSessionForEntity(mid));
            if (bot.isPresent()) positions.put(mid, bot.get().position());
        }
        compositeRegistry.register(compositeId, memberIdList, positions, 100, 200);
    }

    private String getSessionForEntity(String entityId) {
        return botRegistry.getSessionForEntity(entityId).orElse(null);
    }

    // ── Frame.ActionFrame convenience builders ────────────────────────

    /** Consume — verb E, no arg (per SCHEMA §8.6). */
    private static Frame.ActionFrame consume() {
        return new Frame.ActionFrame('E', Optional.of("5"));
    }

    /** Attack toward numpad direction. */
    private static Frame.ActionFrame attack(char numpad) {
        return new Frame.ActionFrame('A', Optional.of(String.valueOf(numpad)));
    }

    /** Reproduce toward numpad direction. */
    private static Frame.ActionFrame reproduce(char numpad) {
        return new Frame.ActionFrame('R', Optional.of(String.valueOf(numpad)));
    }

    // ── FEEDER tests ─────────────────────────────────────────────

    @Test
    void feederAutoConsumesAdjacentNutrient() {
        mockSession("s-feeder");
        placeCompositeMember("s-feeder", "cm-feeder", "comp1", ParticleType.CATALYST,
                Role.FEEDER, new Position(3, 3), 50);
        worldGrid.setEntity(3, 4, Nutrient.spawn("n1"));
        registerComposite("comp1", "cm-feeder");

        resolver.resolveActions(1, Map.of("s-feeder", consume()));

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

        resolver.resolveActions(1, Map.of("s-feeder", consume()));

        int poolAfter = compositeRegistry.getSharedEnergy("comp1");
        assertThat(poolAfter).isGreaterThan(poolBefore);

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
        Particle target = new Particle("e-target", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(3, 4, target);
        botRegistry.register("s-target", "e-target", new Position(3, 4));
        registerComposite("comp1", "cm-attacker");

        resolver.resolveActions(1, Map.of("s-attacker", attack('2'))); // S (numpad 2)

        Cell targetCell = worldGrid.getCell(3, 4);
        assertThat(targetCell.occupant()).isInstanceOf(Particle.class);
        Particle damaged = (Particle) targetCell.occupant();
        assertThat(damaged.energy()).isLessThan(50);
    }

    @Test
    void attackerDealsTrueDamage() {
        // ATTACKER deals true damage regardless of RPS matchup (D-10).
        mockSession("s-attacker");
        mockSession("s-target");
        placeCompositeMember("s-attacker", "cm-attacker", "comp1", ParticleType.CATALYST,
                Role.ATTACKER, new Position(3, 3), 50);
        Particle target = new Particle("e-target", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(3, 4, target);
        botRegistry.register("s-target", "e-target", new Position(3, 4));
        registerComposite("comp1", "cm-attacker");

        resolver.resolveActions(1, Map.of("s-attacker", attack('2'))); // S

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
        compositeRegistry.addEnergy("comp1", ActionResolver.REPRODUCE_ENERGY_COST);

        int poolBefore = compositeRegistry.getSharedEnergy("comp1");

        resolver.resolveActions(1, Map.of("s-reproducer", reproduce('6'))); // E (numpad 6)

        Cell targetCell = worldGrid.getCell(4, 3);
        assertThat(targetCell.occupant()).isInstanceOf(Particle.class);
        Particle child = (Particle) targetCell.occupant();
        assertThat(child.type()).isEqualTo(ParticleType.SPORE);
        assertThat(child.energy()).isEqualTo(ActionResolver.CHILD_START_ENERGY);

        int poolAfter = compositeRegistry.getSharedEnergy("comp1");
        assertThat(poolAfter).isLessThan(poolBefore);
    }

    @Test
    void reproducerFailsWhenPoolInsufficient() {
        mockSession("s-reproducer");
        placeCompositeMember("s-reproducer", "cm-reproducer", "comp1", ParticleType.SPORE,
                Role.REPRODUCER, new Position(3, 3), 50);
        var memberIds = java.util.List.of("cm-reproducer");
        var positions = Map.of("cm-reproducer", new Position(3, 3));
        compositeRegistry.register("comp1", memberIds, positions, 5, 200);

        resolver.resolveActions(1, Map.of("s-reproducer", reproduce('6')));

        Cell targetCell = worldGrid.getCell(4, 3);
        assertThat(targetCell.isEmpty()).isTrue();
        assertThat(compositeRegistry.getSharedEnergy("comp1")).isEqualTo(5);
    }

    // ── Passive role tests ───────────────────────────────────────

    @Test
    void sensorDoesNotAct() {
        mockSession("s-sensor");
        placeCompositeMember("s-sensor", "cm-sensor", "comp1", ParticleType.CATALYST,
                Role.SENSOR, new Position(3, 3), 50);
        registerComposite("comp1", "cm-sensor");

        worldGrid.setEntity(3, 4, Nutrient.spawn("n1"));

        resolver.resolveActions(1, Map.of("s-sensor", consume()));

        // Nutrient unchanged — SENSOR is passive.
        Cell nutrientCell = worldGrid.getCell(3, 4);
        assertThat(nutrientCell.occupant()).isInstanceOf(Nutrient.class);
    }

    @Test
    void defenderDoesNotAutoAct() {
        mockSession("s-defender");
        placeCompositeMember("s-defender", "cm-defender", "comp1", ParticleType.CATALYST,
                Role.DEFENDER, new Position(3, 3), 50);
        registerComposite("comp1", "cm-defender");

        Particle target = new Particle("e-target", ParticleType.MEMBRANE, 50, 100);
        worldGrid.setEntity(3, 4, target);

        resolver.resolveActions(1, Map.of("s-defender", attack('2')));

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

        resolver.resolveActions(1, Map.of("s-feeder", consume()));

        Cell cell = worldGrid.getCell(3, 3);
        CompositeMember member = (CompositeMember) cell.occupant();
        assertThat(member.energy()).isEqualTo(50);
    }
}
