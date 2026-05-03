package com.paralife.engine;

import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for composite formation: two adjacent BondedPairs merge
 * into a Composite with CompositeMember entities on the grid.
 */
class CompositeFormationTest {

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
        return engine(CompositeConfig.defaults());
    }

    private SimulationEngine engine(CompositeConfig compositeConfig) {
        var sc = simConfig();
        return new SimulationEngine(grid, sc, botRegistry, noBonding(),
                compositeRegistry, compositeConfig,
                MetabolicProfile.defaults(), StarvationConfig.defaults(),
                new SeasonTracker(new SeasonsConfig(200, 0.0)));
    }

    /** Config with no decay, no combat, no spawning, no overcrowding. */
    private SimulationConfig simConfig() {
        return new SimulationConfig(0, 0, 0.0, 0, true, 8, 0);
    }

    /** BondingConfig that prevents new bonds from forming. */
    private BondingConfig noBonding() {
        return new BondingConfig(Integer.MAX_VALUE, 0.0, 0.0);
    }

    /** Create a BondedPair with known entity IDs for bot tracking. */
    private BondedPair makeBondedPair(String id, int energy) {
        return new BondedPair(id, ParticleType.CATALYST, ParticleType.SPORE,
                energy, 100, id + "-primary", id + "-secondary");
    }

    // ════════════════════════════════════════════════════════════════
    // Formation basics
    // ════════════════════════════════════════════════════════════════

    @Test
    void adjacentBondedPairsFormComposite() {
        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2); // adjacent

        engine().processTick(1);

        // Both positions should now contain CompositeMember entities with same compositeId
        Entity occ1 = grid.getCell(3, 3).occupant();
        Entity occ2 = grid.getCell(3, 4).occupant();
        assertThat(occ1).isInstanceOf(CompositeMember.class);
        assertThat(occ2).isInstanceOf(CompositeMember.class);

        CompositeMember cm1 = (CompositeMember) occ1;
        CompositeMember cm2 = (CompositeMember) occ2;
        assertThat(cm1.compositeId()).isEqualTo(cm2.compositeId());

        // CompositeRegistry should have 1 composite with 2 members
        assertThat(compositeRegistry.size()).isEqualTo(1);
        var composite = compositeRegistry.getComposite(cm1.compositeId());
        assertThat(composite).isPresent();
        assertThat(composite.get().getMemberCount()).isEqualTo(2);
    }

    @Test
    void nonAdjacentBondedPairsDoNotForm() {
        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(0, 0, bp1);
        grid.setEntity(4, 4, bp2); // not adjacent

        engine().processTick(1);

        // Both should still be BondedPairs
        assertThat(grid.getCell(0, 0).occupant()).isInstanceOf(BondedPair.class);
        assertThat(grid.getCell(4, 4).occupant()).isInstanceOf(BondedPair.class);
        assertThat(compositeRegistry.size()).isEqualTo(0);
    }

    @Test
    void canFormCompositesDisabledPreventsFormation() {
        CompositeConfig disabled = new CompositeConfig(0.03, 12, 1.0,
                1, 3, 1, 2, 1, 4, 1, 1, 2, 1, false);

        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        engine(disabled).processTick(1);

        assertThat(grid.getCell(3, 3).occupant()).isInstanceOf(BondedPair.class);
        assertThat(grid.getCell(3, 4).occupant()).isInstanceOf(BondedPair.class);
        assertThat(compositeRegistry.size()).isEqualTo(0);
    }

    @Test
    void formationCreatesTwoCompositeMembersOnGrid() {
        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        engine().processTick(1);

        assertThat(grid.getCell(3, 3).occupant()).isInstanceOf(CompositeMember.class);
        assertThat(grid.getCell(3, 4).occupant()).isInstanceOf(CompositeMember.class);
    }

    // ════════════════════════════════════════════════════════════════
    // Energy accounting
    // ════════════════════════════════════════════════════════════════

    @Test
    void formationSharedPoolEnergyIsSumOfBondedPairs() {
        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        engine().processTick(1);

        CompositeMember cm = (CompositeMember) grid.getCell(3, 3).occupant();
        var composite = compositeRegistry.getComposite(cm.compositeId());
        assertThat(composite).isPresent();
        assertThat(composite.get().getSharedPoolEnergy()).isEqualTo(70); // remainder: (80-40) + (60-30)
    }

    @Test
    void formationMemberEnergyIsSplitFromBondedPairEnergy() {
        BondedPair bp1 = new BondedPair("bp1", ParticleType.CATALYST, ParticleType.SPORE,
                80, 100, "bp1-primary", "bp1-secondary");
        BondedPair bp2 = new BondedPair("bp2", ParticleType.MEMBRANE, ParticleType.CATALYST,
                60, 100, "bp2-primary", "bp2-secondary");
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        engine().processTick(1);

        CompositeMember cm1 = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember cm2 = (CompositeMember) grid.getCell(3, 4).occupant();
        // Each member's energy = half of source BondedPair energy
        assertThat(cm1.energy()).isEqualTo(40); // 80 / 2
        assertThat(cm2.energy()).isEqualTo(30); // 60 / 2
    }

    // ════════════════════════════════════════════════════════════════
    // Role assignment
    // ════════════════════════════════════════════════════════════════

    @Test
    void formationAssignsRoles() {
        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        engine().processTick(1);

        CompositeMember cm1 = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember cm2 = (CompositeMember) grid.getCell(3, 4).occupant();

        // One should be FEEDER, the other LOCOMOTOR
        assertThat(cm1.role()).isNotEqualTo(cm2.role());
        assertThat(cm1.role()).isIn(Entity.Role.FEEDER, Entity.Role.LOCOMOTOR);
        assertThat(cm2.role()).isIn(Entity.Role.FEEDER, Entity.Role.LOCOMOTOR);
    }

    // ════════════════════════════════════════════════════════════════
    // BotRegistry update
    // ════════════════════════════════════════════════════════════════

    @Test
    void formationUpdatesBotRegistry() {
        BondedPair bp1 = makeBondedPair("bp1", 80);
        BondedPair bp2 = makeBondedPair("bp2", 60);
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        // Phase 19.5 H-A: production BotRegistry holds session→bp.id() after the
        // H2 (d509cff) bond-formation remap; composite formation looks up by
        // bp.id() and remaps to cm.id(). Prey sessions are unregistered at bond
        // formation so they never reach the composite-formation site.
        botRegistry.register("session-1", "bp1", new Position(3, 3));
        botRegistry.register("session-2", "bp2", new Position(3, 4));

        engine().processTick(1);

        CompositeMember cm1 = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember cm2 = (CompositeMember) grid.getCell(3, 4).occupant();

        // BondedPair-id entries cleared by the composite-formation remap.
        assertThat(botRegistry.getSessionForEntity("bp1")).isEmpty();
        assertThat(botRegistry.getSessionForEntity("bp2")).isEmpty();

        // New CompositeMember IDs should be registered
        assertThat(botRegistry.getSessionForEntity(cm1.id())).isPresent();
        assertThat(botRegistry.getSessionForEntity(cm2.id())).isPresent();
    }

    // ════════════════════════════════════════════════════════════════
    // Double formation prevention
    // ════════════════════════════════════════════════════════════════

    @Test
    void doubleFormationPreventedSameTick() {
        // Three adjacent BondedPairs in a line: A-B-C
        BondedPair bpA = makeBondedPair("bpA", 80);
        BondedPair bpB = makeBondedPair("bpB", 60);
        BondedPair bpC = makeBondedPair("bpC", 70);
        grid.setEntity(3, 3, bpA);
        grid.setEntity(3, 4, bpB); // neighbor to both A and C
        grid.setEntity(3, 5, bpC);

        engine().processTick(1);

        // Only one composite should form (A-B or B-C, not both using B)
        assertThat(compositeRegistry.size()).isEqualTo(1);

        // Count CompositeMember entities on grid
        int memberCount = 0;
        int bondedPairCount = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                Entity occ = grid.getCell(x, y).occupant();
                if (occ instanceof CompositeMember) memberCount++;
                if (occ instanceof BondedPair) bondedPairCount++;
            }
        }
        assertThat(memberCount).isEqualTo(2); // one composite = 2 members
        assertThat(bondedPairCount).isEqualTo(1); // one BondedPair left unclaimed
    }

    @Test
    void formationPreservesMemberParticleType() {
        BondedPair bp1 = new BondedPair("bp1", ParticleType.CATALYST, ParticleType.SPORE,
                80, 100, "bp1-primary", "bp1-secondary");
        BondedPair bp2 = new BondedPair("bp2", ParticleType.MEMBRANE, ParticleType.CATALYST,
                60, 100, "bp2-primary", "bp2-secondary");
        grid.setEntity(3, 3, bp1);
        grid.setEntity(3, 4, bp2);

        engine().processTick(1);

        CompositeMember cm1 = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember cm2 = (CompositeMember) grid.getCell(3, 4).occupant();

        // Each member should retain the primaryType of its source BondedPair
        assertThat(cm1.type()).isEqualTo(ParticleType.CATALYST);
        assertThat(cm2.type()).isEqualTo(ParticleType.MEMBRANE);
    }
}
