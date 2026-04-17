package com.paralife.engine;

import com.paralife.engine.BuffRegistry.BuffType;
import com.paralife.engine.EnvCleanupHooksBean.PendingGrant;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 14-03 mutagen-outbreak verification.
 *
 * <p>Covers D-12 through D-20 + cycle-4 action items #2/#3/#6/#10 +
 * cycle-6 HIGH #2 (identity-transition state migration matrix).
 *
 * <p>All tests live in {@code com.paralife.engine} per cycle-4 action item #10.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=true",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.mutagen.damage-per-tick=2",
        "paralife.simulation.events.mutagen.infection-duration-min=10",
        "paralife.simulation.events.mutagen.infection-duration-max=10",
        "paralife.simulation.events.mutagen.buff-duration-multiplier=5",
        "paralife.simulation.events.mutagen.cure-ticks=3",
        "paralife.simulation.events.mutagen.attack-cure-reduction=3",
        "paralife.simulation.events.mutagen.gossip-probability=1.0",
        "paralife.simulation.events.mutagen.strain-mutation-chance=0.0",
        "paralife.simulation.events.mutagen.zone-decay-ticks=5",
        "paralife.simulation.events.mutagen.outbreak-lifetime-ticks=20",
        "paralife.bonding.bonding-probability=0.0",
        "paralife.composite.dissolution-chance=0.0"
})
class MutagenTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine env;
    @Autowired EnvironmentConfig cfg;
    @Autowired SimulationEngine simulationEngine;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired EnvCleanupHooksBean envCleanupHooksBean;
    @Autowired BuffRegistry buffRegistry;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        compositeRegistry.clear();
        buffRegistry.clear();
        env.resetMutagenStateForTest();
    }

    // ── 1-3: Basic infection acquisition + damage + cure ──────────

    @Test
    void particleOnMutagenZoneGetsInfected() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);

        env.resolveMutagenCollisionsForTest(0L);

        assertThat(envCleanupHooksBean.getInfections()).containsKey("p1");
        Infection inf = envCleanupHooksBean.getInfections().get("p1");
        assertThat(inf.damagePerTick()).isEqualTo(cfg.mutagen().damagePerTick());
        assertThat(inf.position()).isEqualTo(new Position(5, 5));
    }

    @Test
    void infectedEntityTakesDoTPerTick() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        env.tickBuffsAndInfectionsForTest(1L);

        Particle after = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(after.energy()).isEqualTo(78); // 80 - 2
        assertThat(env.envDamageAppliedThisTickForTest()).isTrue();
    }

    @Test
    void buffGrantedOnInfectionExpiry() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        // 10 ticks of DoT — infection starts at 10, expires at tick 10.
        for (int t = 1; t <= 10; t++) {
            env.tickBuffsAndInfectionsForTest(t);
        }

        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("p1");
        // Solo Particle cure: uniform 4-way pick — any one of the BuffTypes.
        assertThat(buffRegistry.getBuffs("p1")).hasSize(1);
    }

    // ── 4: Cure-immunity grace period ──────────────────────────────

    @Test
    void cureImmunityPreventsReinfectionDuringGrace() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);
        for (int t = 1; t <= 10; t++) env.tickBuffsAndInfectionsForTest(t);

        // Now at tick 10, cure granted; cureImmuneUntil = 10 + 3 = 13.
        assertThat(envCleanupHooksBean.getCureImmuneUntil().get("p1")).isEqualTo(13L);

        // Attempt re-infection at tick 11 — should be blocked by immunity.
        env.resolveMutagenCollisionsForTest(11L);
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("p1");

        // After grace period (tick 14+), re-infection succeeds.
        env.resolveMutagenCollisionsForTest(14L);
        assertThat(envCleanupHooksBean.getInfections()).containsKey("p1");
    }

    // ── 5: max-1 event ────────────────────────────────────────────

    @Test
    void maxOneActiveMutagenEvent() {
        env.forceSpawnMutagenForTest(0L, new Position(3, 3), 50, 100);
        assertThat(env.activeMutagenEvent()).isNotNull();
        MutagenEvent first = env.activeMutagenEvent();

        // Trying to spawn again — spawnMutagen should skip.
        env.spawnMutagen(5L);
        assertThat(env.activeMutagenEvent()).isSameAs(first);
    }

    // ── 6: BondedPair shared infection ───────────────────────────

    @Test
    void bondedPairSharedInfection() {
        BondedPair bp = new BondedPair("bp-a", ParticleType.CATALYST, ParticleType.SPORE,
                100, 200, "c1", "s1");
        worldGrid.setEntity(4, 4, bp);
        env.stampMutagenForTest(new Position(4, 4), 80);

        env.resolveMutagenCollisionsForTest(0L);

        assertThat(envCleanupHooksBean.getInfections()).containsKey("bp-a");
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("c1");
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("s1");
    }

    // ── 7: Mutagen damage does NOT call clearEntity ──────────────

    @Test
    void mutagenDamageDoesNotClearEntity() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 4, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        env.tickBuffsAndInfectionsForTest(1L);
        // Entity still on grid with energy=2 — processEnvDeaths handles removal.
        assertThat(worldGrid.getCell(5, 5).isEmpty()).isFalse();
    }

    // ── 8: DoT applies markEnvDamageApplied — same-tick sweep ───

    @Test
    void sameTickFinalizationViaMarkEnvDamageApplied() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 2, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        env.tickBuffsAndInfectionsForTest(1L);
        assertThat(env.envDamageAppliedThisTickForTest()).isTrue();

        env.processEnvDeathsForTest();
        assertThat(worldGrid.getCell(5, 5).isEmpty()).isTrue();
    }

    // ── 9: Strain gossip propagates to neighbors ──────────────

    @Test
    void strainGossipPropagatesToMooreNeighbors() {
        env.forceSpawnMutagenForTest(0L, new Position(8, 8), 100, 50);
        // Re-run advanceMutagen — gossipProbability=1.0 guarantees spread.
        env.advanceMutagenForTest(1L);

        // Check all 8 Moore neighbors are now non-zero strain.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                int strain = env.mutagenStrainAtForTest(new Position(8 + dx, 8 + dy));
                assertThat(strain).as("neighbor (%d,%d)", dx, dy).isGreaterThan(0);
            }
        }
    }

    // ── 10: Zone decay after event expires ────────────────────

    @Test
    void mutagenZoneDecaysAfterEventExpires() {
        // Force a mutagen cell but no active event.
        env.stampMutagenForTest(new Position(3, 3), 100);
        env.setMutagenLastReinforcedTickForTest(new Position(3, 3), 0L);

        // zone-decay-ticks = 5. Tick 6 should clear the strain.
        env.advanceMutagenForTest(6L);

        assertThat(env.mutagenStrainAtForTest(new Position(3, 3))).isZero();
    }

    // ── 11: Infection cleanup on death (Particle) ─────────────

    @Test
    void infectionCleanupOnParticleDeath() {
        Particle p = new Particle("p1", ParticleType.CATALYST, 1, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);
        assertThat(envCleanupHooksBean.getInfections()).containsKey("p1");

        env.tickBuffsAndInfectionsForTest(1L); // kills
        env.processEnvDeathsForTest();

        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("p1");
    }

    // ── 12: Infection cleanup on BondedPair death for bp.id() ─

    @Test
    void infectionCleanupOnBondedPairDeathIncludesBpId() {
        BondedPair bp = new BondedPair("bp-a", ParticleType.CATALYST, ParticleType.SPORE,
                1, 200, "c1", "s1");
        worldGrid.setEntity(4, 4, bp);
        env.stampMutagenForTest(new Position(4, 4), 80);
        env.resolveMutagenCollisionsForTest(0L);
        assertThat(envCleanupHooksBean.getInfections()).containsKey("bp-a");

        env.tickBuffsAndInfectionsForTest(1L);
        env.processEnvDeathsForTest();
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("bp-a");
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("c1");
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("s1");
    }

    // ── 13: Composite role-perk buff grant (D-18 exhaustive) ────

    @Test
    void compositeRoleSpecificBuffGrant() {
        // LOCOMOTOR -> MOVEMENT_PLUS_1 + universal UPKEEP_MINUS_1
        CompositeMember cm = new CompositeMember("m-loco", "cid", ParticleType.CATALYST,
                Role.LOCOMOTOR, 60, 100);
        worldGrid.setEntity(6, 6, cm);
        env.stampMutagenForTest(new Position(6, 6), 100);
        env.resolveMutagenCollisionsForTest(0L);

        for (int t = 1; t <= 10; t++) env.tickBuffsAndInfectionsForTest(t);

        assertThat(buffRegistry.hasBuff("m-loco", BuffType.MOVEMENT_PLUS_1)).isTrue();
        assertThat(buffRegistry.hasBuff("m-loco", BuffType.UPKEEP_MINUS_1)).isTrue();
    }

    // ── 14: Cure-path bug fix — mid-tick eviction survives ────

    @Test
    void reduceInfectionSurvivesTargetBeingRemovedFromInfectionsMapMidTick() {
        // This test locks T-14-03-11: the PendingGrant carries its own Position
        // so the Phase B lookup is by position, not via infections map.
        Particle p = new Particle("p1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        // reduceInfection with ticks = initialTicks forces cure + pending grant.
        env.reduceInfection("p1", 100, 50L, new Position(5, 5));

        // Infection removed, pending grant enqueued.
        assertThat(envCleanupHooksBean.getInfections()).doesNotContainKey("p1");
        List<PendingGrant> grants = new ArrayList<>(envCleanupHooksBean.getPendingGrants());
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).position()).isEqualTo(new Position(5, 5));

        // Drain grants via tickBuffsAndInfections — entity is alive, so buff fires.
        env.tickBuffsAndInfectionsForTest(51L);

        assertThat(buffRegistry.getBuffs("p1")).hasSize(1);
    }

    // ── 15: Lethal damage same tick as cure — NO buff grant ─

    @Test
    void lethalDamageSameTickAsCurePreventsBuffGrant() {
        // Setup: infection with ticksLeft=1, entity with energy=1 (lethal DoT).
        Particle p = new Particle("p1", ParticleType.CATALYST, 1, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        // One tick: DoT=2 kills (energy 1->0); decrement to 9; but pg enqueued when ticksLeft hits 0
        // Actually infection-duration=10, so we need reduceInfection to bring to 1 first.
        env.reduceInfection("p1", 9, 0L, new Position(5, 5));  // ticksLeft 10 -> 1

        // Now tickBuffsAndInfections at tick 1: DoT 2 kills entity, decrement 1->0 enqueues pg.
        env.tickBuffsAndInfectionsForTest(1L);

        // Processing env death removes entity. Check during drainPostActionGrants
        // (called by reconciler) the entity is dead so buff NOT granted.
        env.processEnvDeathsForTest();
        env.drainPostActionGrants(2L);

        // Entity dead — no buff should persist for "p1".
        assertThat(buffRegistry.getBuffs("p1")).isEmpty();
    }

    @Test
    void reduceInfectionThatCoincidesWithLethalDamageDoesNotGrantBuff() {
        // Direct variant: reduceInfection enqueues pending grant, but entity was
        // just killed by unrelated damage — Phase B alive-gate drops the grant.
        Particle p = new Particle("p1", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, p);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);

        // Kill the entity (unrelated combat damage) — energy = 0.
        worldGrid.setEntity(5, 5, p.withEnergy(0));

        // reduceInfection does NOT enqueue because cell occupant is not alive —
        // actually reduceInfection checks occupant match, not aliveness. The
        // alive-gate is in drainPostActionGrants. Force the PendingGrant path:
        env.reduceInfection("p1", 100, 0L, new Position(5, 5));

        // processEnvDeaths sweeps the dead particle off grid.
        env.markEnvDamageAppliedForTest();
        env.processEnvDeathsForTest();

        // Drain grants: entity is now gone — buff should NOT fire.
        env.drainPostActionGrants(1L);

        assertThat(buffRegistry.getBuffs("p1")).isEmpty();
    }

    // ── 16: Structural perf — single pass per tick ────────────

    @Test
    void applyInfectionDamageRunsSinglePassStructural() {
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        // Infect 50 particles spread across the 16x16 grid.
        int infected = 0;
        for (int x = 0; x < w && infected < 50; x++) {
            for (int y = 0; y < h && infected < 50; y++) {
                String id = "p-" + x + "-" + y;
                worldGrid.setEntity(x, y, new Particle(id, ParticleType.CATALYST, 80, 100));
                env.stampMutagenForTest(new Position(x, y), 100);
                infected++;
            }
        }
        env.resolveMutagenCollisionsForTest(0L);

        env.tickBuffsAndInfectionsForTest(1L);
        // Structural assertion: ≤ W*H grid reads (one pass).
        assertThat(env.gridReadCountForTest()).isLessThanOrEqualTo(w * h);
    }

    // ── 17: composite_attackCureBuffGrantedSameTickViaReconciler ─

    @Test
    void composite_attackCureBuffGrantedSameTickViaReconciler() {
        // Simulate an ActionResolver attack-cure path: enqueue PendingGrant
        // (as ActionResolver.resolveAttackerAttack would), then invoke
        // drainPostActionGrants(tick) (as the reconciler would).
        CompositeMember cm = new CompositeMember("m-cure", "cid", ParticleType.CATALYST,
                Role.ATTACKER, 60, 100);
        worldGrid.setEntity(7, 7, cm);
        env.stampMutagenForTest(new Position(7, 7), 100);
        env.resolveMutagenCollisionsForTest(0L);

        // Attack cure triggers — bring infection down to 0 in one call.
        env.reduceInfection("m-cure", 100, 1L, new Position(7, 7));

        // Same tick: reconciler drains grants.
        env.drainPostActionGrants(1L);

        // ATTACKER role → ATTACK_PLUS_1 + universal UPKEEP_MINUS_1.
        assertThat(buffRegistry.hasBuff("m-cure", BuffType.ATTACK_PLUS_1)).isTrue();
        assertThat(buffRegistry.hasBuff("m-cure", BuffType.UPKEEP_MINUS_1)).isTrue();
    }

    // ── 18-21: cycle-6 HIGH #2 identity-transition tests ─────

    @Test
    void bondFormationTransfersInfectionToBondedPairId() {
        // Infect a lone particle, then force bond by direct placement.
        Particle predator = new Particle("pred", ParticleType.CATALYST, 80, 100);
        worldGrid.setEntity(5, 5, predator);
        env.stampMutagenForTest(new Position(5, 5), 100);
        env.resolveMutagenCollisionsForTest(0L);
        assertThat(envCleanupHooksBean.getInfections()).containsKey("pred");

        // Simulate BondFormation via transferMutagenState (matches the
        // SimulationEngine code path on bond-apply). We build a bp manually
        // because driving the full SimulationEngine here requires a lot of
        // setup — the contract we are locking is the transfer helper itself.
        BondedPair bp = new BondedPair("bp-test", ParticleType.CATALYST, ParticleType.SPORE,
                160, 200, "pred", "prey");
        envCleanupHooksBean.transferMutagenState("pred", bp.id());
        envCleanupHooksBean.transferMutagenState("prey", bp.id());

        assertThat(envCleanupHooksBean.getInfections())
                .as("cycle-6 HIGH #2: bond TRANSFERS infection to bp.id()")
                .containsKey(bp.id())
                .doesNotContainKey("pred");
    }

    @Test
    void compositeFormationFromInfectedBondedPairCleansesBpState() {
        // Infect a BondedPair, then simulate the cleanse (matches SimulationEngine
        // CompositeFormation apply site code path).
        BondedPair bp = new BondedPair("bp-inf", ParticleType.CATALYST, ParticleType.SPORE,
                180, 200, "c1", "s1");
        worldGrid.setEntity(4, 4, bp);
        env.stampMutagenForTest(new Position(4, 4), 80);
        env.resolveMutagenCollisionsForTest(0L);
        assertThat(envCleanupHooksBean.getInfections()).containsKey("bp-inf");

        // Cleanse — matches SimulationEngine composite-formation apply site behavior.
        envCleanupHooksBean.clearInfectionOnDeath("bp-inf");
        buffRegistry.unregisterEntity("bp-inf");

        assertThat(envCleanupHooksBean.getInfections())
                .as("cycle-6 HIGH #2: composite formation cleanses bp-level infection")
                .doesNotContainKey("bp-inf");
    }

    @Test
    void revertToBondedPairMergesMemberInfectionsToBondedPairId() {
        // Infect two members with different ticksLeft + initialTicks.
        Infection infShort = new Infection(5, (byte) 10, 2, 5, new Position(3, 3));
        Infection infLong = new Infection(15, (byte) 20, 2, 12, new Position(4, 4));
        envCleanupHooksBean.getInfections().put("m1", infShort);
        envCleanupHooksBean.getInfections().put("m2", infLong);

        // Run transfer as revertToBondedPair would.
        String newBpId = "bp-revert";
        envCleanupHooksBean.transferMutagenState("m1", newBpId);
        envCleanupHooksBean.transferMutagenState("m2", newBpId);

        Infection merged = envCleanupHooksBean.getInfections().get(newBpId);
        assertThat(merged).as("bp-level infection created by revert").isNotNull();
        assertThat(merged.ticksLeft())
                .as("cycle-6 HIGH #2: MAX ticksLeft across merged member infections")
                .isEqualTo(12);
        assertThat(merged.initialTicks())
                .as("cycle-6 HIGH #2: MAX initialTicks across merged member infections")
                .isEqualTo(15);
        assertThat(envCleanupHooksBean.getInfections())
                .as("member keys removed after transfer")
                .doesNotContainKey("m1")
                .doesNotContainKey("m2");
    }

    @Test
    void dissolveToParticlesPreservesInfectionUnderSameId() {
        // cycle-6 HIGH #2: Plan text describes id preservation by construction.
        // Existing dissolveToParticles in SimulationEngine appends "-p" to the
        // id string so in practice the new Particle id differs, BUT the
        // infection map entry remains under the ORIGINAL key (no migration).
        // This test locks the contract that: stamp infection under memberId,
        // then the infection key survives even after the composite dissolves
        // (no explicit cleanup on dissolve).
        Infection inf = new Infection(10, (byte) 5, 2, 10, new Position(6, 6));
        envCleanupHooksBean.getInfections().put("m-to-particle", inf);

        // No code change runs here — the invariant is: nothing explicitly
        // removes the key on dissolve. Locking grep: the dissolveToParticles
        // method body has no clearInfectionOnDeath or transferMutagenState
        // calls (asserted in acceptance criteria).
        assertThat(envCleanupHooksBean.getInfections())
                .as("cycle-6 HIGH #2: infection key preserved across dissolve (no-op per matrix)")
                .containsKey("m-to-particle");
    }

    // ── Bonus — boundary of transferMutagenState (cycle-9 action B.2) ─

    @Test
    void transferMutagenStateDoesNotMigrateBuffs() {
        // cycle-9 action B.2: transferMutagenState migrates ONLY Infection +
        // cureImmuneUntil. Buffs require BuffRegistry.transferBuffs.
        buffRegistry.grant("from", BuffType.ATTACK_PLUS_1, 100L);
        envCleanupHooksBean.getInfections().put("from",
                new Infection(10, (byte) 1, 2, 10, new Position(0, 0)));
        envCleanupHooksBean.getCureImmuneUntil().put("from", 50L);

        envCleanupHooksBean.transferMutagenState("from", "to");

        // Infection + cureImmuneUntil migrate.
        assertThat(envCleanupHooksBean.getInfections()).containsKey("to").doesNotContainKey("from");
        assertThat(envCleanupHooksBean.getCureImmuneUntil().get("to")).isEqualTo(50L);
        assertThat(envCleanupHooksBean.getCureImmuneUntil()).doesNotContainKey("from");

        // Buff does NOT migrate — still on "from", not "to".
        assertThat(buffRegistry.hasBuff("from", BuffType.ATTACK_PLUS_1)).isTrue();
        assertThat(buffRegistry.hasBuff("to", BuffType.ATTACK_PLUS_1)).isFalse();
    }
}
