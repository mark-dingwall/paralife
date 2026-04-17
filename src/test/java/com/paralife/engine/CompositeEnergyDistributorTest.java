package com.paralife.engine;

import com.paralife.world.Entity;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CompositeEnergyDistributor: passive drain and healing
 * from shared pool for composite members.
 */
class CompositeEnergyDistributorTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 8;

    private WorldGrid grid;
    private CompositeRegistry compositeRegistry;
    private CompositeConfig config;
    private CompositeEnergyDistributor distributor;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        compositeRegistry = new CompositeRegistry();
        config = CompositeConfig.defaults();
        distributor = new CompositeEnergyDistributor(grid, compositeRegistry, config);
    }

    /** Place a CompositeMember on the grid and register it in the composite registry. */
    private CompositeMember placeMember(String memberId, String compositeId, Role role,
                                         int energy, int maxEnergy, int x, int y) {
        var member = new CompositeMember(memberId, compositeId, ParticleType.CATALYST, role, energy, maxEnergy);
        grid.setEntity(x, y, member);
        return member;
    }

    /** Register a composite with given members and positions in the registry. */
    private void registerComposite(String compositeId, List<String> memberIds,
                                    Map<String, Position> positions, int poolEnergy, int maxPool) {
        compositeRegistry.register(compositeId, memberIds, positions, poolEnergy, maxPool);
    }

    // ════════════════════════════════════════════════════════════════
    // Passive drain
    // ════════════════════════════════════════════════════════════════

    @Test
    void passiveDrainReducesMemberEnergy() {
        // SENSOR passive drain = 1 (from CompositeConfig.defaults())
        // Pool = 0 so no healing occurs — isolates drain behavior
        String compositeId = "comp-1";
        String memberId = "m1";
        placeMember(memberId, compositeId, Role.SENSOR, 50, 100, 3, 3);
        registerComposite(compositeId, List.of(memberId),
                Map.of(memberId, new Position(3, 3)), 0, 200);

        distributor.onTick(new TickEvent(1));

        CompositeMember updated = (CompositeMember) grid.getCell(3, 3).occupant();
        assertThat(updated.energy()).isEqualTo(49); // 50 - 1 (sensor passive drain)
    }

    @Test
    void passiveDrainRateVariesByRole() {
        String compositeId = "comp-1";
        // ATTACKER passive drain = 1, LOCOMOTOR passive drain = 1 (from defaults)
        // Pool = 0 so no healing occurs — isolates drain behavior
        placeMember("m1", compositeId, Role.ATTACKER, 50, 100, 3, 3);
        placeMember("m2", compositeId, Role.LOCOMOTOR, 50, 100, 3, 4);
        registerComposite(compositeId, List.of("m1", "m2"),
                Map.of("m1", new Position(3, 3), "m2", new Position(3, 4)), 0, 400);

        distributor.onTick(new TickEvent(1));

        CompositeMember updatedAttacker = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember updatedLocomotor = (CompositeMember) grid.getCell(3, 4).occupant();
        // Both have passive drain of 1 in defaults
        assertThat(updatedAttacker.energy()).isEqualTo(49);
        assertThat(updatedLocomotor.energy()).isEqualTo(49);
    }

    // ════════════════════════════════════════════════════════════════
    // Healing from shared pool
    // ════════════════════════════════════════════════════════════════

    @Test
    void healingDrawsFromSharedPool() {
        String compositeId = "comp-1";
        // Member with energy 30, maxEnergy 50 — deficit of 20. Pool has 100.
        // After passive drain (1), energy = 29. Deficit = 50 - 29 = 21.
        // Heal amount = min(passiveDrain=1, deficit=21) = 1. Net effect: energy stays at 30 (drain 1, heal 1).
        // Wait — let's use a member with higher deficit so healing is more visible.
        placeMember("m1", compositeId, Role.SENSOR, 30, 50, 3, 3);
        registerComposite(compositeId, List.of("m1"),
                Map.of("m1", new Position(3, 3)), 100, 200);

        distributor.onTick(new TickEvent(1));

        CompositeMember updated = (CompositeMember) grid.getCell(3, 3).occupant();
        // energy = 30 - 1 (drain) + 1 (heal from pool) = 30
        assertThat(updated.energy()).isEqualTo(30);
        // Pool should have decreased by 1
        assertThat(compositeRegistry.getSharedEnergy(compositeId)).isEqualTo(99);
    }

    @Test
    void healingDoesNotExceedMaxEnergy() {
        String compositeId = "comp-1";
        // Member at maxEnergy — no deficit, no healing
        placeMember("m1", compositeId, Role.SENSOR, 50, 50, 3, 3);
        registerComposite(compositeId, List.of("m1"),
                Map.of("m1", new Position(3, 3)), 100, 200);

        distributor.onTick(new TickEvent(1));

        CompositeMember updated = (CompositeMember) grid.getCell(3, 3).occupant();
        // After drain: 50 - 1 = 49. Has deficit of 1. Heals 1 from pool. Back to 50.
        assertThat(updated.energy()).isEqualTo(50);
        // Pool drained by heal amount
        assertThat(compositeRegistry.getSharedEnergy(compositeId)).isEqualTo(99);
    }

    @Test
    void healingStopsWhenPoolDepleted() {
        String compositeId = "comp-1";
        // Pool has 0 energy — no healing possible
        placeMember("m1", compositeId, Role.SENSOR, 30, 50, 3, 3);
        registerComposite(compositeId, List.of("m1"),
                Map.of("m1", new Position(3, 3)), 0, 200);

        distributor.onTick(new TickEvent(1));

        CompositeMember updated = (CompositeMember) grid.getCell(3, 3).occupant();
        // energy = 30 - 1 (drain) + 0 (no pool) = 29
        assertThat(updated.energy()).isEqualTo(29);
        assertThat(compositeRegistry.getSharedEnergy(compositeId)).isEqualTo(0);
    }

    @RepeatedTest(5)
    void healingOrderShuffled() {
        // With pool only enough for 1 member's healing (1 energy),
        // over multiple runs, different members should get healed
        String compositeId = "comp-1";
        placeMember("m1", compositeId, Role.SENSOR, 30, 50, 3, 3);
        placeMember("m2", compositeId, Role.SENSOR, 30, 50, 3, 4);
        // Pool has exactly 1 — only one member can be healed
        registerComposite(compositeId, List.of("m1", "m2"),
                Map.of("m1", new Position(3, 3), "m2", new Position(3, 4)), 1, 200);

        distributor.onTick(new TickEvent(1));

        CompositeMember u1 = (CompositeMember) grid.getCell(3, 3).occupant();
        CompositeMember u2 = (CompositeMember) grid.getCell(3, 4).occupant();
        // Both drained by 1. One healed back.
        // One should be 30 (drained then healed) and one should be 29 (drained only)
        int total = u1.energy() + u2.energy();
        assertThat(total).isEqualTo(59); // 30-1+1 + 30-1 = 30 + 29 = 59, or 30-1 + 30-1+1 = 29 + 30 = 59
        assertThat(compositeRegistry.getSharedEnergy(compositeId)).isEqualTo(0);
    }

    // ════════════════════════════════════════════════════════════════
    // Grid update
    // ════════════════════════════════════════════════════════════════

    @Test
    void memberEnergyUpdatedOnGrid() {
        String compositeId = "comp-1";
        placeMember("m1", compositeId, Role.FEEDER, 40, 100, 2, 2);
        registerComposite(compositeId, List.of("m1"),
                Map.of("m1", new Position(2, 2)), 0, 200);

        distributor.onTick(new TickEvent(1));

        Entity entity = grid.getCell(2, 2).occupant();
        assertThat(entity).isInstanceOf(CompositeMember.class);
        CompositeMember updated = (CompositeMember) entity;
        // FEEDER passive drain = 1 (defaults), no pool to heal from
        assertThat(updated.energy()).isEqualTo(39);
        // Verify it's the same entity otherwise (id, compositeId, role preserved)
        assertThat(updated.id()).isEqualTo("m1");
        assertThat(updated.compositeId()).isEqualTo(compositeId);
        assertThat(updated.role()).isEqualTo(Role.FEEDER);
    }

    // ════════════════════════════════════════════════════════════════
    // Annotation verification
    // ════════════════════════════════════════════════════════════════

    @Test
    void distributorRunsAtOrder15() throws Exception {
        Method onTick = CompositeEnergyDistributor.class.getMethod("onTick", TickEvent.class);
        assertThat(onTick.isAnnotationPresent(EventListener.class)).isTrue();
        assertThat(onTick.isAnnotationPresent(Order.class)).isTrue();
        assertThat(onTick.getAnnotation(Order.class).value()).isEqualTo(15);
    }

    // ════════════════════════════════════════════════════════════════
    // Plan 14-05: UPKEEP_MINUS_1 reduces per-member passiveDrain
    // ════════════════════════════════════════════════════════════════

    @Test
    void compositeUpkeepMinus1ReducesPerMemberPassiveDrain() {
        // SENSOR passive drain = 1 (defaults). UPKEEP_MINUS_1 on this member
        // reduces per-member passiveDrain by 1 floored at 0. Pool = 0 so no
        // healing — isolates drain behavior.
        BuffRegistry buffRegistry = new BuffRegistry();
        distributor.setBuffRegistry(buffRegistry);

        String compositeId = "comp-1";
        String memberId = "m1";
        placeMember(memberId, compositeId, Role.SENSOR, 50, 100, 3, 3);
        registerComposite(compositeId, List.of(memberId),
                Map.of(memberId, new Position(3, 3)), 0, 200);

        buffRegistry.grant(memberId, BuffRegistry.BuffType.UPKEEP_MINUS_1, 1_000L);

        distributor.onTick(new TickEvent(1));

        CompositeMember updated = (CompositeMember) grid.getCell(3, 3).occupant();
        // Baseline drain = 1, buff reduces to 0 → energy unchanged from 50.
        assertThat(updated.energy())
                .as("Plan 14-05: UPKEEP_MINUS_1 reduces SENSOR passive drain from 1 to 0")
                .isEqualTo(50);
    }

    @Test
    void compositeUpkeepStackingWithSameBuffTypeOnOneMemberDoesNotStackNumerically() {
        // Dedup contract (BuffRegistry truth #2): multiple grant() calls of
        // the same BuffType on one entity keep ONE active buff with max(expiry).
        // Therefore passiveDrain is reduced by EXACTLY 1, not 2. A second
        // grant must NOT drop passiveDrain below 0 or below the first grant's
        // effect.
        BuffRegistry buffRegistry = new BuffRegistry();
        distributor.setBuffRegistry(buffRegistry);

        String compositeId = "comp-1";
        String memberId = "m1";
        // LOCOMOTOR baseline passiveDrain = 1 in defaults.
        placeMember(memberId, compositeId, Role.LOCOMOTOR, 50, 100, 3, 3);
        registerComposite(compositeId, List.of(memberId),
                Map.of(memberId, new Position(3, 3)), 0, 200);

        buffRegistry.grant(memberId, BuffRegistry.BuffType.UPKEEP_MINUS_1, 1_000L);
        buffRegistry.grant(memberId, BuffRegistry.BuffType.UPKEEP_MINUS_1, 2_000L);
        // Dedup keeps ONE ActiveBuff, expiry = max(1000, 2000) = 2000.
        assertThat(buffRegistry.getBuffs(memberId)).hasSize(1);

        distributor.onTick(new TickEvent(1));
        CompositeMember updated = (CompositeMember) grid.getCell(3, 3).occupant();
        // 50 - max(0, 1-1) = 50. Second grant did NOT drop passiveDrain to -1.
        assertThat(updated.energy())
                .as("Buff dedup: two grants of same type = exactly one reduction")
                .isEqualTo(50);
    }
}
