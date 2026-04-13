package com.paralife.engine;

import com.paralife.world.Entity;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for composite combat mechanics in SimulationEngine.processInteractions.
 * Covers: Particle-vs-CompositeMember, ATTACKER role true damage, DEFENDER absorption,
 * same-composite no-fight, and individual-vs-shared-pool energy targeting.
 */
class CompositeCombatTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 8;

    private WorldGrid grid;
    private BotRegistry botRegistry;
    private CompositeRegistry compositeRegistry;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        botRegistry = new BotRegistry();
        compositeRegistry = new CompositeRegistry();
    }

    private SimulationEngine engine() {
        return engine(CompositeConfig.defaults(), BondingConfig.defaults());
    }

    private SimulationEngine engine(CompositeConfig compositeConfig, BondingConfig bondingConfig) {
        // No decay, no spawning, no overcrowding — combat only
        var simConfig = new SimulationConfig(0, 10, 0.0, 0, true, 8, 0);
        return new SimulationEngine(grid, simConfig, botRegistry, bondingConfig,
                compositeRegistry, compositeConfig);
    }

    /** Create a CompositeMember on the grid and register in CompositeRegistry. */
    private CompositeMember placeCompositeMember(String memberId, String compositeId,
                                                  ParticleType type, Entity.Role role,
                                                  int energy, int maxEnergy,
                                                  int x, int y) {
        var cm = new CompositeMember(memberId, compositeId, type, role, energy, maxEnergy);
        grid.setEntity(x, y, cm);
        return cm;
    }

    /** Register a composite with the given members and positions in the registry. */
    private void registerComposite(String compositeId, List<String> memberIds,
                                    Map<String, Position> positions, int poolEnergy) {
        compositeRegistry.register(compositeId, memberIds, positions, poolEnergy, 200);
    }

    // ════════════════════════════════════════════════════════════════
    // Particle vs CompositeMember
    // ════════════════════════════════════════════════════════════════

    @Test
    void particleAttacksCompositeMemberDamagesIndividualEnergy() {
        // CATALYST beats SPORE — Particle (CATALYST) attacks CompositeMember (SPORE)
        Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
        grid.setEntity(3, 3, catalyst);

        placeCompositeMember("cm1", "comp-1", ParticleType.SPORE, Entity.Role.FEEDER, 50, 100, 3, 4);
        placeCompositeMember("cm2", "comp-1", ParticleType.CATALYST, Entity.Role.LOCOMOTOR, 50, 100, 4, 4);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 4), "cm2", new Position(4, 4)), 100);

        engine().processTick(1);

        // CompositeMember individual energy should be reduced
        CompositeMember updated = (CompositeMember) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isLessThan(50);

        // Shared pool should be unchanged
        assertThat(compositeRegistry.getSharedEnergy("comp-1")).isEqualTo(100);
    }

    @Test
    void particleDoesNotAttackPredatorCompositeMember() {
        // SPORE cannot attack CATALYST (CATALYST is predator of SPORE)
        Particle spore = new Particle("spo", ParticleType.SPORE, 50);
        grid.setEntity(3, 3, spore);

        placeCompositeMember("cm1", "comp-1", ParticleType.CATALYST, Entity.Role.FEEDER, 50, 100, 3, 4);
        placeCompositeMember("cm2", "comp-1", ParticleType.SPORE, Entity.Role.LOCOMOTOR, 50, 100, 4, 4);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 4), "cm2", new Position(4, 4)), 100);

        engine().processTick(1);

        // No damage — SPORE cannot attack CATALYST
        CompositeMember updated = (CompositeMember) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isEqualTo(50);
    }

    // ════════════════════════════════════════════════════════════════
    // ATTACKER role: true damage
    // ════════════════════════════════════════════════════════════════

    @Test
    void attackerRoleDealsTrueDamage() {
        // ATTACKER CompositeMember (SPORE) attacks adjacent Particle (SPORE — same type!)
        // Same type means no RPS interaction from the Particle scan.
        // ATTACKER bypasses RPS (D-10), deals true damage regardless of type.
        // Place cm2 far away so it can't interfere.
        placeCompositeMember("cm1", "comp-1", ParticleType.SPORE, Entity.Role.ATTACKER, 50, 100, 3, 3);
        placeCompositeMember("cm2", "comp-1", ParticleType.SPORE, Entity.Role.LOCOMOTOR, 50, 100, 3, 1);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 3), "cm2", new Position(3, 1)), 100);

        // Target Particle same type as attacker — normally no RPS interaction,
        // but ATTACKER deals true damage
        Particle target = new Particle("target", ParticleType.SPORE, 50);
        grid.setEntity(3, 4, target);

        engine().processTick(1);

        // Target Particle should have taken damage (true damage, type-agnostic)
        Particle updated = (Particle) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isLessThan(50);
    }

    @Test
    void nonAttackerMemberUsesRPS() {
        // FEEDER CompositeMember (CATALYST) adjacent to Particle (SPORE, prey of CATALYST)
        // RPS combat should apply
        placeCompositeMember("cm1", "comp-1", ParticleType.CATALYST, Entity.Role.FEEDER, 50, 100, 3, 3);
        placeCompositeMember("cm2", "comp-1", ParticleType.SPORE, Entity.Role.LOCOMOTOR, 50, 100, 2, 3);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 3), "cm2", new Position(2, 3)), 100);

        Particle prey = new Particle("prey", ParticleType.SPORE, 50);
        grid.setEntity(3, 4, prey);

        engine().processTick(1);

        // Prey Particle should have taken RPS damage
        Particle updated = (Particle) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isLessThan(50);
    }

    @Test
    void nonAttackerMemberCannotAttackPredator() {
        // FEEDER CompositeMember (CATALYST) adjacent to Particle (MEMBRANE)
        // MEMBRANE beats CATALYST — the FEEDER should NOT attack its predator
        placeCompositeMember("cm1", "comp-1", ParticleType.CATALYST, Entity.Role.FEEDER, 50, 100, 3, 3);
        placeCompositeMember("cm2", "comp-1", ParticleType.SPORE, Entity.Role.LOCOMOTOR, 50, 100, 2, 3);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 3), "cm2", new Position(2, 3)), 100);

        Particle predator = new Particle("pred", ParticleType.MEMBRANE, 50);
        grid.setEntity(3, 4, predator);

        engine().processTick(1);

        // MEMBRANE is predator of CATALYST — the Particle (MEMBRANE) may attack the CM,
        // but the CM (CATALYST, non-ATTACKER) should NOT attack MEMBRANE.
        // The Particle attack is Particle-vs-CompositeMember (handled separately),
        // but the CompositeMember scan should skip this neighbor.
        // We just verify the Particle's energy wasn't reduced by the CM's attack scan
        // (only the Particle->CM path may apply, not CM->Particle for non-ATTACKER+predator)
        Particle updatedPredator = (Particle) grid.getCell(3, 4).occupant();
        // The particle may have gained energy from attacking the CM, but should not have lost any
        assertThat(updatedPredator.energy()).isGreaterThanOrEqualTo(50);
    }

    // ════════════════════════════════════════════════════════════════
    // DEFENDER role: damage absorption
    // ════════════════════════════════════════════════════════════════

    @Test
    void defenderAbsorbsDamage() {
        // DEFENDER CompositeMember (SPORE) attacked by Particle (CATALYST)
        // bondDefenseChance=1.0 → always deflects
        Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
        grid.setEntity(3, 3, catalyst);

        placeCompositeMember("cm1", "comp-1", ParticleType.SPORE, Entity.Role.DEFENDER, 50, 100, 3, 4);
        placeCompositeMember("cm2", "comp-1", ParticleType.CATALYST, Entity.Role.LOCOMOTOR, 50, 100, 4, 4);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 4), "cm2", new Position(4, 4)), 100);

        // bondDefenseChance=1.0 for DEFENDER absorption
        var bondConfig = new BondingConfig(Integer.MAX_VALUE, 0.0, 1.0);
        engine(CompositeConfig.defaults(), bondConfig).processTick(1);

        // DEFENDER deflected — no energy lost
        CompositeMember updated = (CompositeMember) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isEqualTo(50);
        // Attacker should not gain energy either
        Particle updatedCat = (Particle) grid.getCell(3, 3).occupant();
        assertThat(updatedCat.energy()).isEqualTo(50);
    }

    // ════════════════════════════════════════════════════════════════
    // Same-composite no-fight
    // ════════════════════════════════════════════════════════════════

    @Test
    void compositeMembersOfSameCompositeDoNotFight() {
        // Two members of same composite adjacent — no combat
        placeCompositeMember("cm1", "comp-1", ParticleType.CATALYST, Entity.Role.ATTACKER, 50, 100, 3, 3);
        placeCompositeMember("cm2", "comp-1", ParticleType.SPORE, Entity.Role.FEEDER, 50, 100, 3, 4);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 3), "cm2", new Position(3, 4)), 100);

        engine().processTick(1);

        // No energy change for either member
        CompositeMember updated1 = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember updated2 = (CompositeMember) grid.getCell(3, 4).occupant();
        assertThat(updated1.energy()).isEqualTo(50);
        assertThat(updated2.energy()).isEqualTo(50);
    }

    @Test
    void compositeMembersOfDifferentCompositesCanFight() {
        // ATTACKER from comp-1 adjacent to member of comp-2
        placeCompositeMember("cm1", "comp-1", ParticleType.CATALYST, Entity.Role.ATTACKER, 50, 100, 3, 3);
        placeCompositeMember("cm1b", "comp-1", ParticleType.SPORE, Entity.Role.LOCOMOTOR, 50, 100, 2, 3);
        registerComposite("comp-1", List.of("cm1", "cm1b"),
                Map.of("cm1", new Position(3, 3), "cm1b", new Position(2, 3)), 100);

        placeCompositeMember("cm2", "comp-2", ParticleType.SPORE, Entity.Role.FEEDER, 50, 100, 3, 4);
        placeCompositeMember("cm2b", "comp-2", ParticleType.MEMBRANE, Entity.Role.LOCOMOTOR, 50, 100, 4, 4);
        registerComposite("comp-2", List.of("cm2", "cm2b"),
                Map.of("cm2", new Position(3, 4), "cm2b", new Position(4, 4)), 100);

        engine().processTick(1);

        // ATTACKER deals true damage to comp-2 member
        CompositeMember updated = (CompositeMember) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isLessThan(50);
    }

    // ════════════════════════════════════════════════════════════════
    // Individual vs shared pool energy targeting
    // ════════════════════════════════════════════════════════════════

    @Test
    void combatDamageHitsIndividualNotSharedPool() {
        // Particle attacks CompositeMember — only individual energy changes
        Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
        grid.setEntity(3, 3, catalyst);

        placeCompositeMember("cm1", "comp-1", ParticleType.SPORE, Entity.Role.FEEDER, 50, 100, 3, 4);
        placeCompositeMember("cm2", "comp-1", ParticleType.CATALYST, Entity.Role.LOCOMOTOR, 50, 100, 4, 4);
        registerComposite("comp-1", List.of("cm1", "cm2"),
                Map.of("cm1", new Position(3, 4), "cm2", new Position(4, 4)), 100);

        int poolBefore = compositeRegistry.getSharedEnergy("comp-1");

        engine().processTick(1);

        // Individual energy changed
        CompositeMember updated = (CompositeMember) grid.getCell(3, 4).occupant();
        assertThat(updated.energy()).isLessThan(50);

        // Shared pool unchanged
        assertThat(compositeRegistry.getSharedEnergy("comp-1")).isEqualTo(poolBefore);
    }
}
