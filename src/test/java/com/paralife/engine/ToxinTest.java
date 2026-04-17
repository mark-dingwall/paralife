package com.paralife.engine;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 14-02 toxin-spread verification.
 *
 * <p>Covers: spawn (max-1), damage across Particle + BondedPair (MAX rule) +
 * CompositeMember, normalised intensity/255 * resistance formula, splash
 * damage, same-tick env death, nonZero counter fast-path, low-intensity ×
 * high-resistance floor (cycle-9 action G).
 *
 * <p>All tests live in {@code com.paralife.engine} per cycle-4 action item #10.
 *
 * <p>Splash-wiring integration tests across the three attack families are in
 * Task 4 (added in a follow-up commit that wires SplashDelta + ActionResolver).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.tick.auto-start=false",
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.toxin.base-damage=10",
        "paralife.simulation.events.toxin.intensity-threshold=20",
        "paralife.simulation.events.toxin.splash-damage-fraction=0.2",
        "paralife.simulation.events.toxin.diffusion-rate=0.5",
        "paralife.simulation.events.toxin.diffusion-radius=1"
})
class ToxinTest {

    @Autowired WorldGrid worldGrid;
    @Autowired EnvironmentEngine env;
    @Autowired EnvironmentConfig cfg;

    @BeforeEach
    void reset() {
        worldGrid.clear();
        env.resetToxinStateForTest();
    }

    // ── Single-cell damage coverage ────────────────────────────────

    @Test
    void particleOnToxicCellTakesNormalisedDamage() {
        Particle catalyst = new Particle("c1", ParticleType.CATALYST, 100, 100);
        worldGrid.setEntity(5, 5, catalyst);
        env.stampToxinIntensityForTest(new Position(5, 5), 255);  // full intensity

        env.resolveToxinCollisionsForTest(0L);

        // damage = 10 * (255/255) * 1.0 (catalyst) = 10
        Particle after = (Particle) worldGrid.getCell(5, 5).occupant();
        assertThat(after.energy()).isEqualTo(90);
    }

    @Test
    void compositeMemberOnToxicCellTakesNormalisedDamage() {
        CompositeMember cm = new CompositeMember("cm1", "cid", ParticleType.MEMBRANE,
                Role.ATTACKER, 50, 100);
        worldGrid.setEntity(7, 7, cm);
        env.stampToxinIntensityForTest(new Position(7, 7), 255);

        env.resolveToxinCollisionsForTest(0L);

        // damage = 10 * 1.0 * 0.7 (membrane) = 7
        CompositeMember after = (CompositeMember) worldGrid.getCell(7, 7).occupant();
        assertThat(after.energy()).isEqualTo(43);
    }

    @Test
    void bondedPairToxinResistanceIsMaxOfMemberTypeMultipliers() {
        // MEMBRANE resistance = 0.7 (low), SPORE resistance = 1.3 (high).
        // MAX rule: worst-case resistance (1.3) drives damage, NOT 0.7.
        BondedPair bp = new BondedPair("bp1", ParticleType.MEMBRANE, ParticleType.SPORE,
                100, 200);
        worldGrid.setEntity(3, 3, bp);
        env.stampToxinIntensityForTest(new Position(3, 3), 255);

        env.resolveToxinCollisionsForTest(0L);

        // damage = 10 * 1.0 * max(0.7, 1.3) = 13
        BondedPair after = (BondedPair) worldGrid.getCell(3, 3).occupant();
        assertThat(after.energy()).isEqualTo(87);
    }

    // ── Normalised formula ─────────────────────────────────────────

    @Test
    void intensityScalesDamageLinearlyVia255Divisor() {
        // Intensity=127 (≈50%) with catalyst (1.0) and base=10 → 10 * 127/255 * 1.0 ≈ 4.98 → 4
        Particle p = new Particle("p2", ParticleType.CATALYST, 100, 100);
        worldGrid.setEntity(2, 2, p);
        env.stampToxinIntensityForTest(new Position(2, 2), 127);

        env.resolveToxinCollisionsForTest(0L);

        Particle after = (Particle) worldGrid.getCell(2, 2).occupant();
        // int(10 * 127 / 255) = int(4.98) = 4 (Java int cast truncates)
        assertThat(after.energy()).isEqualTo(96);
    }

    @Test
    void lowIntensityHighResistanceRoundsToZeroDamage() {
        // cycle-9 action G: toxin damage formula is baseDamage * (intensity/255.0) * resistance.
        // With baseDamage=10 (this test's TestPropertySource), intensity=1, SPORE (1.3):
        //   10 * (1/255.0) * 1.3 = 0.051 → int cast to 0 → NO damage.
        // This test pins the floor behavior so future resistance-formula churn cannot
        // silently change low-intensity tolerance.
        Particle spore = new Particle("s1", ParticleType.SPORE, 100, 100);
        worldGrid.setEntity(6, 6, spore);
        env.stampToxinIntensityForTest(new Position(6, 6), 1);  // intensity 1

        env.resolveToxinCollisionsForTest(0L);

        Particle after = (Particle) worldGrid.getCell(6, 6).occupant();
        assertThat(after.energy())
                .as("cycle-9 action G: low intensity × high resistance rounds damage to 0")
                .isEqualTo(100);
    }

    // ── Same-tick finalization + markEnvDamageApplied ──────────────

    @Test
    void zeroEnergyParticleFinalizesSameTickViaMarkEnvDamageApplied() {
        // Particle with 5 energy on max-intensity toxic cell should die from a
        // single 10-damage hit; processEnvDeaths clears it same tick.
        Particle catalyst = new Particle("c2", ParticleType.CATALYST, 5, 100);
        worldGrid.setEntity(4, 4, catalyst);
        env.stampToxinIntensityForTest(new Position(4, 4), 255);

        env.resolveToxinCollisionsForTest(0L);
        assertThat(env.envDamageAppliedThisTickForTest()).isTrue();

        env.processEnvDeathsForTest();
        assertThat(worldGrid.getCell(4, 4).hasOccupant()).isFalse();
    }

    @Test
    void resolveToxinCollisionsDoesNotClearEntity() {
        // Contract from must-haves: "Toxin collisions do NOT clear zero-energy
        // entities themselves — they set withEnergy(max(0, ...)) AND call
        // markEnvDamageApplied()". processEnvDeaths is the clearer.
        Particle p = new Particle("p3", ParticleType.CATALYST, 5, 100);
        worldGrid.setEntity(8, 8, p);
        env.stampToxinIntensityForTest(new Position(8, 8), 255);

        env.resolveToxinCollisionsForTest(0L);

        // Before processEnvDeaths the occupant is still present but at energy 0.
        Particle after = (Particle) worldGrid.getCell(8, 8).occupant();
        assertThat(after).isNotNull();
        assertThat(after.energy()).isEqualTo(0);
    }

    // ── Max-1 event + persistence ──────────────────────────────────

    @Test
    void activeToxinRespectsMaxOneEvent() {
        env.forceSpawnToxinForTest(0L);
        ToxinEvent first = env.activeToxinEvent();
        assertThat(first).isNotNull();

        // Attempt to spawn again — Poisson rolls are skipped when activeToxin != null.
        env.spawnToxin(1L);
        assertThat(env.activeToxinEvent()).isSameAs(first);
    }

    @Test
    void toxicCellsPersistAcrossTicks() {
        // Stamp a single cell, run advanceToxin — the grid retains non-zero
        // intensity in the diffusion neighbourhood rather than going immediately to 0.
        env.stampToxinIntensityForTest(new Position(10, 10), 255);
        env.advanceToxinForTest(1L);
        assertThat(env.nonZeroToxinCellCountForTest()).isGreaterThan(0);
    }

    // ── nonZero counter fast-path ──────────────────────────────────

    @Test
    void nonZeroToxinCellCounterEnablesIdleTickFastPath() {
        // Idle state: activeToxin == null && nonZeroToxinCellCount == 0.
        // advanceToxin must be an O(1) no-op (no scan, counter unchanged).
        assertThat(env.activeToxinEvent()).isNull();
        assertThat(env.nonZeroToxinCellCountForTest()).isEqualTo(0);

        env.advanceToxinForTest(1L);

        // Counter still zero (no diffusion fired).
        assertThat(env.nonZeroToxinCellCountForTest()).isEqualTo(0);
    }

    @Test
    void stampToxinIntensityUpdatesNonZeroCounter() {
        assertThat(env.nonZeroToxinCellCountForTest()).isEqualTo(0);
        env.stampToxinIntensityForTest(new Position(1, 1), 100);
        assertThat(env.nonZeroToxinCellCountForTest()).isEqualTo(1);
        env.stampToxinIntensityForTest(new Position(1, 1), 0);
        assertThat(env.nonZeroToxinCellCountForTest()).isEqualTo(0);
    }

    // ── Status caches ──────────────────────────────────────────────

    @Test
    void buildStatusCachesSetsToxinPresentBitAboveThresholdOnly() {
        // threshold=20. Cell A with intensity=50 → TOXIN_PRESENT set. Cell B with
        // intensity=10 → below threshold → no cellStatus bit (but entity TOXIC bit
        // still fires if an entity is present).
        env.stampToxinIntensityForTest(new Position(12, 12), 50);
        env.stampToxinIntensityForTest(new Position(13, 13), 10);

        env.buildStatusCachesForTest();

        assertThat(env.getCellStatus(new Position(12, 12))
                & EnvironmentEngine.CELL_STATUS_TOXIN_PRESENT).isNotZero();
        assertThat(env.getCellStatus(new Position(13, 13))
                & EnvironmentEngine.CELL_STATUS_TOXIN_PRESENT).isZero();
    }

    @Test
    void buildStatusCachesSetsEntityToxicBitForAnyPositiveIntensity() {
        // Entity-level TOXIC bit uses > 0 (separate threshold from cell bit).
        Particle p = new Particle("p-toxic", ParticleType.CATALYST, 100, 100);
        worldGrid.setEntity(0, 0, p);
        env.stampToxinIntensityForTest(new Position(0, 0), 1);  // below cell threshold

        env.buildStatusCachesForTest();

        assertThat(env.getEntityStatus("p-toxic")
                & EnvironmentEngine.ENTITY_STATUS_TOXIC).isNotZero();
        // cellStatus for that position remains zero because 1 < threshold(20).
        assertThat(env.getCellStatus(new Position(0, 0))
                & EnvironmentEngine.CELL_STATUS_TOXIN_PRESENT).isZero();
    }

    // ── Splash damage fraction ─────────────────────────────────────

    @Test
    void computeSplashDamageReturnsConfiguredFractionOfBaseDamage() {
        env.stampToxinIntensityForTest(new Position(9, 9), 255);

        // splash = round(baseDamage * (intensity/255) * splashDamageFraction)
        //        = round(10 * 1.0 * 0.2) = 2
        int splash = env.computeSplashDamage(new Position(9, 9));
        assertThat(splash).isEqualTo(2);
    }

    @Test
    void computeSplashDamageReturnsZeroForNonToxicCell() {
        int splash = env.computeSplashDamage(new Position(0, 15));
        assertThat(splash).isEqualTo(0);
    }

    @Test
    void toxinIntensityAtReadsUnsignedByteCorrectly() {
        // 200 signed-byte-cast is still 200 unsigned.
        env.stampToxinIntensityForTest(new Position(5, 5), 200);
        assertThat(env.toxinIntensityAt(new Position(5, 5))).isEqualTo(200);
    }
}
