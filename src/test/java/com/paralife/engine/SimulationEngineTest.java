package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Rock;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

class SimulationEngineTest {

    private static final int WIDTH = 10;
    private static final int HEIGHT = 10;

    private WorldGrid grid;
    private BotRegistry botRegistry;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        botRegistry = mock(BotRegistry.class);
    }

    private SimulationEngine engineWith(SimulationConfig config) {
        return new SimulationEngine(grid, config, botRegistry, noBonding(),
                new CompositeRegistry(), CompositeConfig.defaults(),
                uniformProfile(config), StarvationConfig.defaults(),
                defaultSeasonTracker());
    }

    private SimulationEngine engineWith(SimulationConfig config, BondingConfig bondingConfig) {
        return new SimulationEngine(grid, config, botRegistry, bondingConfig,
                new CompositeRegistry(), CompositeConfig.defaults(),
                uniformProfile(config), StarvationConfig.defaults(),
                defaultSeasonTracker());
    }

    /**
     * Default SeasonTracker for legacy tests — year=200, amplitude=0 so the
     * seasonal multiplier is always 1.0. This preserves pre-Phase-13 tests
     * that assert on flat nutrient-spawn probability.
     */
    private SeasonTracker defaultSeasonTracker() {
        return new SeasonTracker(new SeasonsConfig(200, 0.0));
    }

    /**
     * Build a MetabolicProfile where all three types share the legacy flat rates
     * from SimulationConfig. This preserves Phase 11/12 test semantics: tests that
     * passed {@code decayOnly(N)} still observe every particle decaying by N, and
     * combat tests that assumed a flat combatEnergyTransfer keep working.
     *
     * <p>Production code uses {@link MetabolicProfile#defaults()} which gives each
     * type its own archetype (Phase 13 D-03).
     */
    private MetabolicProfile uniformProfile(SimulationConfig cfg) {
        int decay = cfg.energyDecayPerTick();
        int combat = cfg.combatEnergyTransfer();
        int nutrient = cfg.nutrientConsumeEnergy();
        // attackPower = combat so attacker gain == defender loss, matching pre-Phase-13 behavior
        MetabolicProfile.TypeProfile p = new MetabolicProfile.TypeProfile(
                100,       // maxEnergy (legacy default)
                decay,     // decayPerTick
                combat,    // combatEnergyTransfer
                combat,    // attackPower
                nutrient,  // nutrientConsumeEnergy
                30,        // reproduceEnergyCost (legacy)
                0,         // reproduceCooldown (no cooldown in legacy tests)
                0.0,       // bonusOffspringChance
                1,         // reproduceRange
                30, 10     // starvationThreshold/floor (unused at engine level)
        );
        return new MetabolicProfile(p, p, p);
    }

    private BondingConfig bondingConfig(int threshold, double probability, double defense) {
        return new BondingConfig(threshold, probability, defense);
    }

    private BondingConfig noBonding() {
        return new BondingConfig(Integer.MAX_VALUE, 0.0, 0.0);
    }

    /** Config with only combat enabled (no decay, no spawning, no overcrowding). */
    private SimulationConfig combatOnly() {
        return new SimulationConfig(0, 10, 0.0, 5, true, 8, 0);
    }

    /** Config with only energy decay enabled. */
    private SimulationConfig decayOnly(int decayPerTick) {
        return new SimulationConfig(decayPerTick, 0, 0.0, 0, true, 8, 0);
    }

    /** Config with only nutrient spawning at 100% probability. */
    private SimulationConfig nutrientSpawnAlways() {
        return new SimulationConfig(0, 0, 1.0, 5, true, 8, 0);
    }

    /** Config with everything disabled. */
    private SimulationConfig disabled() {
        return new SimulationConfig(0, 0, 0.0, 0, false, 8, 0);
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 1: Combat
    // ════════════════════════════════════════════════════════════════

    @Nested
    class CombatTests {

        @Test
        void predatorAdjacentToPreyTransfersEnergy() {
            // CATALYST beats SPORE
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
            Particle spore = new Particle("spo", ParticleType.SPORE, 50);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore); // adjacent

            engineWith(combatOnly()).processTick(1);

            Particle updatedCatalyst = (Particle) grid.getCell(5, 5).occupant();
            Particle updatedSpore = (Particle) grid.getCell(5, 6).occupant();

            // Catalyst gains energy, spore loses
            assertThat(updatedCatalyst.energy()).isGreaterThan(50);
            assertThat(updatedSpore.energy()).isLessThan(50);
        }

        @Test
        void sameTypeNoInteraction() {
            Particle c1 = new Particle("c1", ParticleType.CATALYST, 50);
            Particle c2 = new Particle("c2", ParticleType.CATALYST, 50);

            grid.setEntity(5, 5, c1);
            grid.setEntity(5, 6, c2);

            engineWith(combatOnly()).processTick(1);

            // No energy change for same type
            assertThat(((Particle) grid.getCell(5, 5).occupant()).energy()).isEqualTo(50);
            assertThat(((Particle) grid.getCell(5, 6).occupant()).energy()).isEqualTo(50);
        }

        @Test
        void nonAdjacentParticlesNoInteraction() {
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
            Particle spore = new Particle("spo", ParticleType.SPORE, 50);

            grid.setEntity(0, 0, catalyst);
            grid.setEntity(5, 5, spore); // far apart

            engineWith(combatOnly()).processTick(1);

            assertThat(((Particle) grid.getCell(0, 0).occupant()).energy()).isEqualTo(50);
            assertThat(((Particle) grid.getCell(5, 5).occupant()).energy()).isEqualTo(50);
        }

        @Test
        void allThreeRPSPairsWork() {
            // CATALYST beats SPORE
            grid.setEntity(0, 0, new Particle("cat", ParticleType.CATALYST, 50));
            grid.setEntity(0, 1, new Particle("spo", ParticleType.SPORE, 50));

            // SPORE beats MEMBRANE
            grid.setEntity(3, 0, new Particle("spo2", ParticleType.SPORE, 50));
            grid.setEntity(3, 1, new Particle("mem", ParticleType.MEMBRANE, 50));

            // MEMBRANE beats CATALYST
            grid.setEntity(6, 0, new Particle("mem2", ParticleType.MEMBRANE, 50));
            grid.setEntity(6, 1, new Particle("cat2", ParticleType.CATALYST, 50));

            engineWith(combatOnly()).processTick(1);

            // Winners gained energy
            assertThat(((Particle) grid.getCell(0, 0).occupant()).energy()).isGreaterThan(50);
            assertThat(((Particle) grid.getCell(3, 0).occupant()).energy()).isGreaterThan(50);
            assertThat(((Particle) grid.getCell(6, 0).occupant()).energy()).isGreaterThan(50);

            // Losers lost energy
            assertThat(((Particle) grid.getCell(0, 1).occupant()).energy()).isLessThan(50);
            assertThat(((Particle) grid.getCell(3, 1).occupant()).energy()).isLessThan(50);
            assertThat(((Particle) grid.getCell(6, 1).occupant()).energy()).isLessThan(50);
        }

        @Test
        void combatIgnoresRocksAndNutrients() {
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, new Rock("rock"));
            grid.setEntity(6, 5, Nutrient.spawn("n1"));

            engineWith(combatOnly()).processTick(1);

            // No energy change
            assertThat(((Particle) grid.getCell(5, 5).occupant()).energy()).isEqualTo(50);
        }

        @Test
        void combatEnergyClampsToMax() {
            // Particle near max energy fights prey
            Particle strong = new Particle("strong", ParticleType.CATALYST, 95);
            Particle weak = new Particle("weak", ParticleType.SPORE, 50);

            grid.setEntity(5, 5, strong);
            grid.setEntity(5, 6, weak);

            engineWith(combatOnly()).processTick(1);

            Particle updated = (Particle) grid.getCell(5, 5).occupant();
            assertThat(updated.energy()).isLessThanOrEqualTo(updated.maxEnergy());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 2: Energy Decay
    // ════════════════════════════════════════════════════════════════

    @Nested
    class EnergyDecayTests {

        @Test
        void allParticlesLoseEnergyPerTick() {
            grid.setEntity(0, 0, new Particle("p1", ParticleType.CATALYST, 50));
            grid.setEntity(1, 1, new Particle("p2", ParticleType.SPORE, 30));
            grid.setEntity(2, 2, new Particle("p3", ParticleType.MEMBRANE, 80));

            engineWith(decayOnly(3)).processTick(1);

            assertThat(((Particle) grid.getCell(0, 0).occupant()).energy()).isEqualTo(47);
            assertThat(((Particle) grid.getCell(1, 1).occupant()).energy()).isEqualTo(27);
            assertThat(((Particle) grid.getCell(2, 2).occupant()).energy()).isEqualTo(77);
        }

        @Test
        void decayDoesNotGoBelowZero() {
            // Energy 2, decay 5 → clamped to 0 → removed by death phase
            grid.setEntity(0, 0, new Particle("p1", ParticleType.CATALYST, 2));

            engineWith(decayOnly(5)).processTick(1);

            // Particle decayed to 0 and was removed by death phase
            assertThat(grid.getCell(0, 0).isEmpty()).isTrue();
        }

        @Test
        void rocksAndNutrientsUnaffectedByDecay() {
            grid.setEntity(0, 0, new Rock("r1"));
            grid.setEntity(1, 1, Nutrient.spawn("n1"));

            engineWith(decayOnly(5)).processTick(1);

            assertThat(grid.getCell(0, 0).occupant()).isInstanceOf(Rock.class);
            assertThat(grid.getCell(1, 1).occupant()).isInstanceOf(Nutrient.class);
        }

        @Test
        void zeroDecayConfigNoChange() {
            grid.setEntity(0, 0, new Particle("p1", ParticleType.SPORE, 50));

            engineWith(decayOnly(0)).processTick(1);

            assertThat(((Particle) grid.getCell(0, 0).occupant()).energy()).isEqualTo(50);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 3: Death Removal
    // ════════════════════════════════════════════════════════════════

    @Nested
    class DeathTests {

        @Test
        void zeroEnergyParticleRemoved() {
            grid.setEntity(5, 5, new Particle("dead", ParticleType.CATALYST, 1));

            // Decay by 1 → energy = 0 → removed
            engineWith(decayOnly(1)).processTick(1);

            assertThat(grid.getCell(5, 5).isEmpty()).isTrue();
        }

        @Test
        void aliveParticleNotRemoved() {
            grid.setEntity(5, 5, new Particle("alive", ParticleType.MEMBRANE, 50));

            engineWith(decayOnly(1)).processTick(1);

            assertThat(grid.getCell(5, 5).hasOccupant()).isTrue();
        }

        @Test
        void multipleDeathsInOneTick() {
            grid.setEntity(0, 0, new Particle("d1", ParticleType.CATALYST, 1));
            grid.setEntity(1, 1, new Particle("d2", ParticleType.SPORE, 1));
            grid.setEntity(2, 2, new Particle("d3", ParticleType.MEMBRANE, 1));
            grid.setEntity(3, 3, new Particle("alive", ParticleType.CATALYST, 50));

            engineWith(decayOnly(1)).processTick(1);

            assertThat(grid.getCell(0, 0).isEmpty()).isTrue();
            assertThat(grid.getCell(1, 1).isEmpty()).isTrue();
            assertThat(grid.getCell(2, 2).isEmpty()).isTrue();
            assertThat(grid.getCell(3, 3).hasOccupant()).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 4: Nutrient Spawning
    // ════════════════════════════════════════════════════════════════

    @Nested
    class NutrientSpawnTests {

        @Test
        void emptyCellsGetNutrientsAtFullProbability() {
            // 10x10 = 100 cells, all empty, spawn probability = 1.0
            engineWith(nutrientSpawnAlways()).processTick(1);

            int nutrientCount = 0;
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Nutrient) {
                        nutrientCount++;
                    }
                }
            }
            assertThat(nutrientCount).isEqualTo(100); // Every empty cell filled
        }

        @Test
        void occupiedCellsSkipped() {
            grid.setEntity(0, 0, new Particle("p1", ParticleType.CATALYST, 50));
            grid.setEntity(1, 1, new Rock("r1"));

            engineWith(nutrientSpawnAlways()).processTick(1);

            // Occupied cells unchanged
            assertThat(grid.getCell(0, 0).occupant()).isInstanceOf(Particle.class);
            assertThat(grid.getCell(1, 1).occupant()).isInstanceOf(Rock.class);
        }

        @Test
        void zeroProbabilityNoSpawning() {
            var config = new SimulationConfig(0, 0, 0.0, 5, true, 8, 0);
            engineWith(config).processTick(1);

            int nutrientCount = 0;
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Nutrient) {
                        nutrientCount++;
                    }
                }
            }
            assertThat(nutrientCount).isEqualTo(0);
        }

        @RepeatedTest(3) // Run multiple times since probabilistic
        void partialProbabilitySpawnsSome() {
            // 100 cells, 50% spawn rate — expect roughly 50 but not 0 or 100
            var config = new SimulationConfig(0, 0, 0.5, 5, true, 8, 0);
            engineWith(config).processTick(1);

            int nutrientCount = 0;
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Nutrient) {
                        nutrientCount++;
                    }
                }
            }
            assertThat(nutrientCount).isBetween(10, 90); // Wide range for probabilistic test
        }

        // ── Phase 13 Plan 03: fertility + seasonal modulation ─────

        private SimulationEngine engineWithSeasons(SimulationConfig cfg, SeasonTracker seasons) {
            return new SimulationEngine(grid, cfg, botRegistry, noBonding(),
                    new CompositeRegistry(), CompositeConfig.defaults(),
                    uniformProfile(cfg), StarvationConfig.defaults(), seasons);
        }

        @Test
        void fertilityClampsAtFullProbability() {
            // baseRate=1.0, nutrientLevel=100 → fertilityMult=2.0 → effective 2.0
            // Clamped to 1.0 → every empty cell still gets exactly one nutrient.
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    grid.setCell(x, y, grid.getCell(x, y).withNutrientLevel(100));
                }
            }
            engineWith(nutrientSpawnAlways()).processTick(1);

            int nutrientCount = 0;
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Nutrient) nutrientCount++;
                }
            }
            assertThat(nutrientCount).isEqualTo(100);
        }

        @Test
        void winterTroughSpawnsNothingWithLowBaseRate() {
            // Base rate small enough that seasonal multiplier 0.0 pushes effective to 0.
            // amplitude=1.0 at yearLength=200, tick=150 (sin(3PI/2)=-1) → multiplier = 0.
            var seasons = new SeasonTracker(new SeasonsConfig(200, 1.0));
            var cfg = new SimulationConfig(0, 0, 0.5, 0, true, 8, 0);

            engineWithSeasons(cfg, seasons).processTick(150);

            int nutrientCount = 0;
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Nutrient) nutrientCount++;
                }
            }
            assertThat(nutrientCount).isZero();
        }

        @RepeatedTest(3)
        void summerPeakOutSpawnsWinterTrough() {
            // Probabilistic: more nutrients spawn at summer peak than at winter trough.
            var seasons = new SeasonTracker(new SeasonsConfig(200, 0.5));
            // Half-probability base so seasonal multipliers can push around it.
            var cfg = new SimulationConfig(0, 0, 0.5, 0, true, 8, 0);

            // Count summer (tick=50, multiplier=1.5) spawns
            int summerCount = countSpawnsOnFreshGrid(cfg, seasons, 50);
            // Count winter (tick=150, multiplier=0.5) spawns on a fresh grid
            int winterCount = countSpawnsOnFreshGrid(cfg, seasons, 150);

            // Rough band with wide tolerance — summer should spawn noticeably more.
            assertThat(summerCount).isGreaterThan(winterCount);
        }

        private int countSpawnsOnFreshGrid(SimulationConfig cfg, SeasonTracker seasons, long tick) {
            grid.clear();
            engineWithSeasons(cfg, seasons).processTick(tick);
            int count = 0;
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Nutrient) count++;
                }
            }
            return count;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Multi-phase integration
    // ════════════════════════════════════════════════════════════════

    @Nested
    class MultiPhaseTests {

        @Test
        void combatThenDecayThenDeath() {
            // Spore with low energy adjacent to catalyst → combat damage + decay → death
            var config = new SimulationConfig(1, 10, 0.0, 0, true, 8, 0);
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
            Particle weakSpore = new Particle("spo", ParticleType.SPORE, 5);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, weakSpore);

            engineWith(config).processTick(1);

            // Spore took combat damage (-10) + decay (-1) = max(0, 5-10-1) = 0 → dead
            assertThat(grid.getCell(5, 6).isEmpty()).isTrue();

            // Catalyst gained combat energy (+10) - decay (-1) = 59
            Particle updatedCatalyst = (Particle) grid.getCell(5, 5).occupant();
            assertThat(updatedCatalyst.energy()).isEqualTo(59);
        }

        @Test
        void deathFreesSpaceForNutrients() {
            // Particle dies → cell becomes empty → nutrient can spawn
            var config = new SimulationConfig(5, 0, 1.0, 5, true, 8, 0);
            grid.setEntity(5, 5, new Particle("dying", ParticleType.MEMBRANE, 3));

            engineWith(config).processTick(1);

            // Particle decayed (3-5=0) → died → nutrient spawned in freed cell
            assertThat(grid.getCell(5, 5).occupant()).isInstanceOf(Nutrient.class);
        }

        @Test
        void disabledConfigNoProcessing() {
            grid.setEntity(5, 5, new Particle("p1", ParticleType.CATALYST, 50));

            engineWith(disabled()).processTick(1);

            // Nothing changed
            assertThat(((Particle) grid.getCell(5, 5).occupant()).energy()).isEqualTo(50);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // BotRegistry Death Cleanup
    // ════════════════════════════════════════════════════════════════

    @Nested
    class BotRegistryDeathCleanupTests {

        @Test
        void deathTriggersUnregisterByEntity() {
            grid.setEntity(5, 5, new Particle("bot-1", ParticleType.CATALYST, 1));

            engineWith(decayOnly(1)).processTick(1);

            // Particle died → BotRegistry cleanup called
            assertThat(grid.getCell(5, 5).isEmpty()).isTrue();
            verify(botRegistry).unregisterByEntity("bot-1");
        }

        @Test
        void aliveParticleNotUnregistered() {
            grid.setEntity(5, 5, new Particle("bot-2", ParticleType.CATALYST, 50));

            engineWith(decayOnly(1)).processTick(1);

            // Particle alive → no unregister call
            assertThat(grid.getCell(5, 5).hasOccupant()).isTrue();
            verify(botRegistry, never()).unregisterByEntity(anyString());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 2.5: Overcrowding
    // ════════════════════════════════════════════════════════════════

    @Nested
    class OvercrowdingTests {

        /** Config with overcrowding at threshold 7, penalty 3, no decay/combat. */
        private SimulationConfig overcrowdingConfig(int threshold, int penalty) {
            return new SimulationConfig(0, 0, 0.0, 0, true, threshold, penalty);
        }

        @Test
        void overcrowdedParticleLosesEnergy() {
            // Place center particle surrounded by 7 neighbors (threshold=7)
            grid.setEntity(5, 5, new Particle("center", ParticleType.CATALYST, 50));
            for (int i = 0; i < 7; i++) {
                var pos = grid.getNeighbors(5, 5).get(i);
                grid.setEntity(pos.x(), pos.y(), new Particle("n" + i, ParticleType.SPORE, 50));
            }

            engineWith(overcrowdingConfig(7, 3)).processTick(1);

            Particle center = (Particle) grid.getCell(5, 5).occupant();
            assertThat(center.energy()).isEqualTo(47); // 50 - 3
        }

        @Test
        void notOvercrowdedBelowThreshold() {
            // Place center with only 3 neighbors (threshold=7)
            grid.setEntity(5, 5, new Particle("center", ParticleType.CATALYST, 50));
            var neighbors = grid.getNeighbors(5, 5);
            for (int i = 0; i < 3; i++) {
                var pos = neighbors.get(i);
                grid.setEntity(pos.x(), pos.y(), new Particle("n" + i, ParticleType.SPORE, 50));
            }

            engineWith(overcrowdingConfig(7, 3)).processTick(1);

            Particle center = (Particle) grid.getCell(5, 5).occupant();
            assertThat(center.energy()).isEqualTo(50); // No penalty
        }

        @Test
        void overcrowdedFlagSetAndCleared() {
            grid.setEntity(5, 5, new Particle("center", ParticleType.CATALYST, 50));
            var neighbors = grid.getNeighbors(5, 5);
            // Fill all 8 neighbors
            for (int i = 0; i < 8; i++) {
                var pos = neighbors.get(i);
                grid.setEntity(pos.x(), pos.y(), new Particle("n" + i, ParticleType.SPORE, 50));
            }

            var engine = engineWith(overcrowdingConfig(6, 2));
            engine.processTick(1);

            // Flag should be set
            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_OVERCROWDED)).isTrue();

            // Remove neighbors to go below threshold
            for (int i = 0; i < 8; i++) {
                var pos = neighbors.get(i);
                grid.clearEntity(pos.x(), pos.y());
            }

            engine.processTick(2);

            // Flag should be cleared
            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_OVERCROWDED)).isFalse();
        }

        @Test
        void overcrowdingCanCauseDeath() {
            // Particle with low energy, surrounded by many neighbors
            grid.setEntity(5, 5, new Particle("dying", ParticleType.MEMBRANE, 2));
            var neighbors = grid.getNeighbors(5, 5);
            for (int i = 0; i < 7; i++) {
                var pos = neighbors.get(i);
                grid.setEntity(pos.x(), pos.y(), new Particle("n" + i, ParticleType.CATALYST, 50));
            }

            // Penalty of 3 on energy 2 → 0 or below → death phase removes it
            engineWith(overcrowdingConfig(6, 3)).processTick(1);

            assertThat(grid.getCell(5, 5).isEmpty()).isTrue();
            verify(botRegistry).unregisterByEntity("dying");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 1 extension: Bonding (endosymbiosis)
    // ════════════════════════════════════════════════════════════════

    @Nested
    class BondingTests {

        @Test
        void eligibleEncounterFormsBond() {
            // CATALYST beats SPORE — both above threshold, probability=1.0
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 60);
            Particle spore = new Particle("spo", ParticleType.SPORE, 60);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore);

            // threshold=50, probability=1.0, defense=0.0
            engineWith(combatOnly(), bondingConfig(50, 1.0, 0.0)).processTick(1);

            // Primary cell should contain BondedPair
            assertThat(grid.getCell(5, 5).occupant()).isInstanceOf(Entity.BondedPair.class);
            // One of the two cells should be empty (the secondary)
            // Due to shuffled iteration, either cell could be primary
            Entity occ1 = grid.getCell(5, 5).occupant();
            Entity occ2 = grid.getCell(5, 6).occupant();
            boolean hasBond = occ1 instanceof Entity.BondedPair || occ2 instanceof Entity.BondedPair;
            boolean hasEmpty = occ1 == null || occ2 == null;
            assertThat(hasBond).isTrue();
            assertThat(hasEmpty).isTrue();
        }

        @Test
        void belowEnergyThresholdFallsThroughToCombat() {
            // threshold=60, particles at 50 — not eligible for bonding
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
            Particle spore = new Particle("spo", ParticleType.SPORE, 50);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore);

            engineWith(combatOnly(), bondingConfig(60, 1.0, 0.0)).processTick(1);

            // No BondedPair — combat should have occurred
            assertThat(grid.getCell(5, 5).occupant()).isInstanceOf(Particle.class);
            assertThat(grid.getCell(5, 6).occupant()).isInstanceOf(Particle.class);

            // Energy should have changed (combat occurred)
            Particle updatedCat = (Particle) grid.getCell(5, 5).occupant();
            Particle updatedSpo = (Particle) grid.getCell(5, 6).occupant();
            assertThat(updatedCat.energy()).isNotEqualTo(50);
            assertThat(updatedSpo.energy()).isNotEqualTo(50);
        }

        @Test
        void zeroProbabilityNeverBonds() {
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 60);
            Particle spore = new Particle("spo", ParticleType.SPORE, 60);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore);

            // probability=0.0 — bonding never triggers
            engineWith(combatOnly(), bondingConfig(50, 0.0, 0.0)).processTick(1);

            // Both should still be Particles (combat occurred instead)
            assertThat(grid.getCell(5, 5).occupant()).isInstanceOf(Particle.class);
            assertThat(grid.getCell(5, 6).occupant()).isInstanceOf(Particle.class);
        }

        @Test
        void bondedPairEnergyIsSumOfMembers() {
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 60, 100);
            Particle spore = new Particle("spo", ParticleType.SPORE, 70, 100);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore);

            engineWith(combatOnly(), bondingConfig(50, 1.0, 0.0)).processTick(1);

            // Find the BondedPair
            Entity.BondedPair bp = findBondedPair(5, 5, 5, 6);
            assertThat(bp).isNotNull();
            assertThat(bp.energy()).isEqualTo(130); // 60 + 70
            assertThat(bp.maxEnergy()).isEqualTo(200); // 100 + 100
        }

        @Test
        void bondedPairPrimaryIsAttackerSecondaryIsPrey() {
            // CATALYST beats SPORE → primary=CATALYST, secondary=SPORE
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 60);
            Particle spore = new Particle("spo", ParticleType.SPORE, 60);

            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore);

            engineWith(combatOnly(), bondingConfig(50, 1.0, 0.0)).processTick(1);

            Entity.BondedPair bp = findBondedPair(5, 5, 5, 6);
            assertThat(bp).isNotNull();
            assertThat(bp.primaryType()).isEqualTo(ParticleType.CATALYST);
            assertThat(bp.secondaryType()).isEqualTo(ParticleType.SPORE);
        }

        @Test
        void bondedPairDiesWhenEnergyReachesZero() {
            // Phase 13 Plan 02: BondedPair decay uses cached effectiveDecayRate, not the
            // flat config. Construct the pair with effectiveDecayRate=1 so energy 1→0.
            Entity.BondedPair bp = new Entity.BondedPair(
                    "bp1", ParticleType.CATALYST, ParticleType.SPORE, 1, 200,
                    "cat", "spo", 1, 10, 10);
            grid.setEntity(5, 5, bp);

            // Decay by 1 → energy=0 → death removes it
            engineWith(decayOnly(1)).processTick(1);

            assertThat(grid.getCell(5, 5).isEmpty()).isTrue();
        }

        @Test
        void bondedPairDecaysPerTick() {
            // Phase 13 Plan 02: BondedPair decay is the cached effectiveDecayRate, not the
            // flat SimulationConfig.energyDecayPerTick. Construct the pair with an explicit
            // effective rate = 3 to match the pre-Plan-02 assertion.
            Entity.BondedPair bp = new Entity.BondedPair(
                    "bp1", ParticleType.CATALYST, ParticleType.SPORE, 100, 200,
                    "cat", "spo", 3, 10, 10);
            grid.setEntity(5, 5, bp);

            engineWith(decayOnly(3)).processTick(1);

            Entity.BondedPair updated = (Entity.BondedPair) grid.getCell(5, 5).occupant();
            assertThat(updated.energy()).isEqualTo(97); // 100 - 3
        }

        @Test
        void bondedPairFormationCachesHybridVigorRates() {
            // Phase 13 Plan 02 D-05/D-06: formation must populate the three effective-* fields.
            // With uniformProfile(decay=3, combat=10, attack=10) and default ranges [0.6, 0.9],
            // effectiveDecay ∈ [3, 5] (sum*factor = 6 * 0.6..0.9 = 3.6..5.4 → int truncation 3..5)
            // and effectiveCombat/effectiveAttack ∈ [10, 10] (avg==max==10).
            Particle catalyst = new Particle("cat", ParticleType.CATALYST, 60, 100);
            Particle spore = new Particle("spo", ParticleType.SPORE, 60, 100);
            grid.setEntity(5, 5, catalyst);
            grid.setEntity(5, 6, spore);

            SimulationConfig cfg = new SimulationConfig(3, 10, 0.0, 5, true, 8, 0);
            engineWith(cfg, bondingConfig(50, 1.0, 0.0)).processTick(1);

            Entity.BondedPair bp = findBondedPair(5, 5, 5, 6);
            assertThat(bp).isNotNull();
            // Hybrid vigor rates cached — within expected bounds.
            assertThat(bp.effectiveDecayRate()).isBetween(3, 5);
            assertThat(bp.effectiveCombatTransfer()).isEqualTo(10); // avg==max for uniform profile
            assertThat(bp.effectiveAttackPower()).isEqualTo(10);
        }

        @Test
        void bondedPairWithEnergyPreservesHybridVigor() {
            // withEnergy must not lose cached rates.
            Entity.BondedPair bp = new Entity.BondedPair(
                    "bp1", ParticleType.CATALYST, ParticleType.SPORE, 100, 200,
                    "cat", "spo", 4, 12, 14);
            Entity.BondedPair after = bp.withEnergy(50);
            assertThat(after.energy()).isEqualTo(50);
            assertThat(after.effectiveDecayRate()).isEqualTo(4);
            assertThat(after.effectiveCombatTransfer()).isEqualTo(12);
            assertThat(after.effectiveAttackPower()).isEqualTo(14);
        }

        @Test
        void bondedPairDefenseDeflectsAttack() {
            // BondedPair primary=CATALYST, attacker=MEMBRANE (MEMBRANE beats CATALYST)
            // defense=1.0 → always deflects
            Entity.BondedPair bp = new Entity.BondedPair("bp1", ParticleType.CATALYST, ParticleType.SPORE, 100, 200);
            Particle attacker = new Particle("mem", ParticleType.MEMBRANE, 50);

            grid.setEntity(5, 5, bp);
            grid.setEntity(5, 6, attacker);

            engineWith(combatOnly(), bondingConfig(50, 0.0, 1.0)).processTick(1);

            // Defense deflected — BondedPair energy unchanged
            Entity.BondedPair updated = (Entity.BondedPair) grid.getCell(5, 5).occupant();
            assertThat(updated.energy()).isEqualTo(100);
            // Attacker energy unchanged too (no transfer happened)
            Particle updatedAttacker = (Particle) grid.getCell(5, 6).occupant();
            assertThat(updatedAttacker.energy()).isEqualTo(50);
        }

        @Test
        void bondedPairDefenseFailsAllowsDamage() {
            // defense=0.0 → never deflects
            Entity.BondedPair bp = new Entity.BondedPair("bp1", ParticleType.CATALYST, ParticleType.SPORE, 100, 200);
            Particle attacker = new Particle("mem", ParticleType.MEMBRANE, 50);

            grid.setEntity(5, 5, bp);
            grid.setEntity(5, 6, attacker);

            engineWith(combatOnly(), bondingConfig(50, 0.0, 0.0)).processTick(1);

            // Damage applied — BondedPair lost energy, attacker gained
            Entity.BondedPair updated = (Entity.BondedPair) grid.getCell(5, 5).occupant();
            assertThat(updated.energy()).isLessThan(100);
            Particle updatedAttacker = (Particle) grid.getCell(5, 6).occupant();
            assertThat(updatedAttacker.energy()).isGreaterThan(50);
        }

        @Test
        void doubleBondingSamePreyPrevented() {
            // Two catalysts adjacent to same spore, probability=1.0
            Particle cat1 = new Particle("cat1", ParticleType.CATALYST, 60);
            Particle cat2 = new Particle("cat2", ParticleType.CATALYST, 60);
            Particle spore = new Particle("spo", ParticleType.SPORE, 60);

            grid.setEntity(5, 5, spore);    // prey in center
            grid.setEntity(5, 4, cat1);      // predator above
            grid.setEntity(5, 6, cat2);      // predator below

            engineWith(combatOnly(), bondingConfig(50, 1.0, 0.0)).processTick(1);

            // Count BondedPairs — should be exactly 1
            int bondCount = 0;
            for (int x = 4; x <= 6; x++) {
                for (int y = 3; y <= 7; y++) {
                    if (grid.getCell(x, y).occupant() instanceof Entity.BondedPair) {
                        bondCount++;
                    }
                }
            }
            assertThat(bondCount).isEqualTo(1);
        }

        /** Helper to find a BondedPair in either of two positions. */
        private Entity.BondedPair findBondedPair(int x1, int y1, int x2, int y2) {
            Entity occ1 = grid.getCell(x1, y1).occupant();
            Entity occ2 = grid.getCell(x2, y2).occupant();
            if (occ1 instanceof Entity.BondedPair bp) return bp;
            if (occ2 instanceof Entity.BondedPair bp) return bp;
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 13 Plan 02: Starvation mechanic (D-09, D-10, D-11)
    // ════════════════════════════════════════════════════════════════

    @Nested
    class StarvationTests {

        /** Profile with explicit starvation thresholds — all three types share values. */
        private MetabolicProfile profile(int maxEnergy, int decay, int combat, int attack,
                                         int threshold, int floor) {
            MetabolicProfile.TypeProfile p = new MetabolicProfile.TypeProfile(
                    maxEnergy, decay, combat, attack, /*nutrient*/5,
                    /*reproduceCost*/30, /*cooldown*/0, /*bonusOffspring*/0.0, /*range*/1,
                    threshold, floor);
            return new MetabolicProfile(p, p, p);
        }

        private SimulationEngine engineWith(SimulationConfig config, MetabolicProfile profile,
                                             StarvationConfig starv) {
            return new SimulationEngine(grid, config, botRegistry,
                    new BondingConfig(Integer.MAX_VALUE, 0.0, 0.0, 0.1, 0.5, 0.6, 0.9),
                    new CompositeRegistry(), CompositeConfig.defaults(),
                    profile, starv,
                    new SeasonTracker(new SeasonsConfig(200, 0.0)));
        }

        // ── computeIntensity math ─────────────────────────────────

        @Test
        void computeIntensityZeroWhenAboveThreshold() {
            // energy=50% of max, threshold=30% → 50 > 30 → not starving
            assertThat(StarvationConfig.computeIntensity(50, 100, 30, 10)).isZero();
        }

        @Test
        void computeIntensityHalfAtMidpoint() {
            // energy=20% of max, threshold=30%, floor=10%
            // → intensity = (30-20) / (30-10) = 0.5
            assertThat(StarvationConfig.computeIntensity(20, 100, 30, 10)).isEqualTo(0.5);
        }

        @Test
        void computeIntensityClampedAtOne() {
            // energy=5% of max, threshold=30%, floor=10% → (30-5)/(30-10)=1.25 → clamped 1.0
            assertThat(StarvationConfig.computeIntensity(5, 100, 30, 10)).isEqualTo(1.0);
        }

        @Test
        void computeIntensityThresholdEqualsFloorReturnsOne() {
            // threshold==floor → binary: either not starving or fully starving (avoid div by zero)
            assertThat(StarvationConfig.computeIntensity(20, 100, 30, 30)).isEqualTo(1.0);
            assertThat(StarvationConfig.computeIntensity(40, 100, 30, 30)).isZero();
        }

        // ── FLAG_STARVING lifecycle (observability) ───────────────

        @Test
        void starvingParticleGetsFlagStarving() {
            // maxEnergy=100, threshold=30%, floor=10%, particle at 20 → starving
            Particle p = new Particle("e1", ParticleType.CATALYST, 20, 100);
            grid.setEntity(5, 5, p);

            engineWith(decayOnly(0), profile(100, 0, 10, 10, 30, 10), StarvationConfig.defaults())
                    .processTick(1);

            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_STARVING)).isTrue();
        }

        @Test
        void nonStarvingParticleHasNoFlag() {
            Particle p = new Particle("e1", ParticleType.CATALYST, 80, 100);
            grid.setEntity(5, 5, p);

            engineWith(decayOnly(0), profile(100, 0, 10, 10, 30, 10), StarvationConfig.defaults())
                    .processTick(1);

            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_STARVING)).isFalse();
        }

        @Test
        void flagStarvingClearedWhenParticleRecovers() {
            // Seed a starving particle and set the flag, then replace with a healthy one.
            Particle starving = new Particle("e1", ParticleType.CATALYST, 20, 100);
            grid.setEntity(5, 5, starving);
            grid.setCell(5, 5, grid.getCell(5, 5).withAddedFlag(Cell.FLAG_STARVING));
            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_STARVING)).isTrue();

            // Replace with healthy particle (preserve flags since setEntity keeps flags)
            Particle healthy = new Particle("e1", ParticleType.CATALYST, 80, 100);
            grid.setEntity(5, 5, healthy);
            // confirm flag still set after setEntity
            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_STARVING)).isTrue();

            engineWith(decayOnly(0), profile(100, 0, 10, 10, 30, 10), StarvationConfig.defaults())
                    .processTick(1);

            // Engine should have cleared the flag this tick
            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_STARVING)).isFalse();
        }

        @Test
        void bondedPairStarvationUsesWeightedThresholdFloor() {
            // primary CATALYST (max=80, thr=30, floor=10), secondary MEMBRANE (max=120, thr=60, floor=20)
            // weighted threshold = (30*80 + 60*120)/(80+120) = (2400+7200)/200 = 48
            // weighted floor     = (10*80 + 20*120)/(80+120) = (800+2400)/200 = 16
            // pair energy = 40, max = 200 → 20% < 48 → starving
            MetabolicProfile p = new MetabolicProfile(
                    new MetabolicProfile.TypeProfile(80, 0, 10, 10, 5, 30, 0, 0.0, 1, 30, 10),
                    new MetabolicProfile.TypeProfile(120, 0, 10, 10, 5, 30, 0, 0.0, 1, 60, 20),
                    new MetabolicProfile.TypeProfile(100, 0, 10, 10, 5, 30, 0, 0.0, 1, 30, 10));
            Entity.BondedPair bp = new Entity.BondedPair(
                    "bp1", ParticleType.CATALYST, ParticleType.MEMBRANE, 40, 200,
                    "cat", "mem", 0, 10, 10);
            grid.setEntity(5, 5, bp);

            engineWith(decayOnly(0), p, StarvationConfig.defaults()).processTick(1);

            assertThat(grid.getCell(5, 5).hasFlag(Cell.FLAG_STARVING)).isTrue();
        }

        // ── Combat modifiers computed from CURRENT energy (no stale flag) ──

        @Test
        void starvingAttackerDealsMoreDamageAndGainsMoreEnergy() {
            // CATALYST beats SPORE. With threshold=30, floor=10, attacker at energy=20 → intensity=0.5
            // baseAttack=10 → boosted = 10 * (1 + 0.5*0.5) = 12 (maxAttackBoost=0.5)
            // baseCombat=10 → boosted = 12
            Particle cat = new Particle("cat", ParticleType.CATALYST, 20, 100);
            Particle spo = new Particle("spo", ParticleType.SPORE, 60, 100);
            grid.setEntity(5, 5, cat);
            grid.setEntity(5, 6, spo);

            MetabolicProfile mp = profile(100, 0, 10, 10, 30, 10);
            engineWith(combatOnly(), mp, StarvationConfig.defaults()).processTick(1);

            Particle updatedCat = (Particle) grid.getCell(5, 5).occupant();
            Particle updatedSpo = (Particle) grid.getCell(5, 6).occupant();
            // Attacker gained 12 (boosted from 10)
            assertThat(updatedCat.energy()).isEqualTo(32);
            // Defender lost 12 (boosted damage)
            assertThat(updatedSpo.energy()).isEqualTo(48);
        }

        @Test
        void starvingDefenderTakesMoreDamage() {
            // Healthy attacker (no attack boost), starving defender (damage vulnerability)
            // attacker energy=80 (>threshold, no intensity), defender energy=20 (intensity=0.5)
            // baseDamage=10, defenderVulnerability = 10 * (1 + 0.5*0.5) = 12
            Particle cat = new Particle("cat", ParticleType.CATALYST, 80, 100);
            Particle spo = new Particle("spo", ParticleType.SPORE, 20, 100);
            grid.setEntity(5, 5, cat);
            grid.setEntity(5, 6, spo);

            MetabolicProfile mp = profile(100, 0, 10, 10, 30, 10);
            engineWith(combatOnly(), mp, StarvationConfig.defaults()).processTick(1);

            Particle updatedCat = (Particle) grid.getCell(5, 5).occupant();
            Particle updatedSpo = (Particle) grid.getCell(5, 6).occupant();
            // Attacker gained base 10 (no boost since not starving)
            assertThat(updatedCat.energy()).isEqualTo(90);
            // Defender lost boosted damage 12
            assertThat(updatedSpo.energy()).isEqualTo(8);
        }

        @Test
        void recoveredEntityHasNoStaleFlagModifier() {
            // Plan 02 explicit: modifiers are computed from CURRENT energy, not from FLAG_STARVING.
            // Seed a cell with FLAG_STARVING set, then place a healthy attacker there. No boost.
            grid.setCell(5, 5, grid.getCell(5, 5).withAddedFlag(Cell.FLAG_STARVING));
            Particle cat = new Particle("cat", ParticleType.CATALYST, 80, 100);
            Particle spo = new Particle("spo", ParticleType.SPORE, 60, 100);
            grid.setEntity(5, 5, cat);
            grid.setEntity(5, 6, spo);

            MetabolicProfile mp = profile(100, 0, 10, 10, 30, 10);
            engineWith(combatOnly(), mp, StarvationConfig.defaults()).processTick(1);

            Particle updatedCat = (Particle) grid.getCell(5, 5).occupant();
            Particle updatedSpo = (Particle) grid.getCell(5, 6).occupant();
            // No stale-flag modifier — attacker gained exactly 10, defender lost exactly 10.
            assertThat(updatedCat.energy()).isEqualTo(90);
            assertThat(updatedSpo.energy()).isEqualTo(50);
        }
    }
}
