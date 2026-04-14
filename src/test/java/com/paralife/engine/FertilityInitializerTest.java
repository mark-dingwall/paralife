package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FertilityInitializerTest {

    private static final int WIDTH = 20;
    private static final int HEIGHT = 20;

    private WorldGrid grid;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
    }

    private FertilityInitializer init(FertilityConfig cfg) {
        return new FertilityInitializer(grid, cfg);
    }

    private int countNonZeroCells() {
        int count = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                if (grid.getCell(x, y).nutrientLevel() > 0) count++;
            }
        }
        return count;
    }

    // ── FertilityConfig validation ─────────────────────────────────

    @Test
    void fertilityConfigRejectsNegativePatchCount() {
        try {
            new FertilityConfig(-1, 3, 8, 100);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("patchCount");
        }
    }

    @Test
    void fertilityConfigRejectsZeroMinRadius() {
        try {
            new FertilityConfig(5, 0, 8, 100);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("patchMinRadius");
        }
    }

    @Test
    void fertilityConfigRejectsMaxLessThanMin() {
        try {
            new FertilityConfig(5, 8, 3, 100);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("patchMaxRadius");
        }
    }

    @Test
    void fertilityConfigRejectsZeroMaxLevel() {
        try {
            new FertilityConfig(5, 3, 8, 0);
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("maxLevel");
        }
    }

    // ── Patch generation behavior ──────────────────────────────────

    @Test
    void initializerProducesNonZeroCells() {
        FertilityInitializer fi = init(new FertilityConfig(5, 3, 5, 100));
        fi.initializeFertility();
        assertThat(countNonZeroCells()).isGreaterThan(0);
    }

    @Test
    void patchCountZeroSkipsInitialization() {
        FertilityInitializer fi = init(new FertilityConfig(0, 3, 5, 100));
        fi.initializeFertility();
        assertThat(countNonZeroCells()).isZero();
    }

    @Test
    void generatePatchCenterHasMaxLevel() {
        FertilityInitializer fi = init(new FertilityConfig(1, 3, 3, 100));
        // Directly invoke generatePatch at a known center with a known radius.
        fi.generatePatch(10, 10, 4, WIDTH, HEIGHT);
        // radius=4 → falloff at center (0,0): 1.0 → level = 100
        assertThat(grid.getCell(10, 10).nutrientLevel()).isEqualTo(100);
    }

    @Test
    void generatePatchFallsOffFromCenter() {
        FertilityInitializer fi = init(new FertilityConfig(1, 4, 4, 100));
        fi.generatePatch(10, 10, 4, WIDTH, HEIGHT);
        int center = grid.getCell(10, 10).nutrientLevel();
        int edge = grid.getCell(13, 10).nutrientLevel(); // dist=3, falloff=0.25
        assertThat(center).isGreaterThan(edge);
        assertThat(edge).isGreaterThan(0);
    }

    @Test
    void generatePatchPreservesHigherExistingLevel() {
        // Pre-set a cell to 80 (higher than some falloff edge levels)
        grid.setCell(10, 10, Cell.EMPTY.withNutrientLevel(80));
        FertilityInitializer fi = init(new FertilityConfig(1, 4, 4, 100));
        // Generate patch at (10, 10) with max=100 — center overwrites 80 with 100 (max wins)
        fi.generatePatch(10, 10, 4, WIDTH, HEIGHT);
        assertThat(grid.getCell(10, 10).nutrientLevel()).isEqualTo(100);
    }

    @Test
    void generatePatchDoesNotLowerExistingLevel() {
        // Pre-set a cell to 90. A patch edge would have a lower falloff value;
        // max-merge must keep the pre-existing 90.
        grid.setCell(13, 10, Cell.EMPTY.withNutrientLevel(90));
        FertilityInitializer fi = init(new FertilityConfig(1, 4, 4, 100));
        // Patch at center (10, 10) radius 4 — cell (13, 10) is dist=3, falloff=0.25 → level=25
        fi.generatePatch(10, 10, 4, WIDTH, HEIGHT);
        assertThat(grid.getCell(13, 10).nutrientLevel()).isEqualTo(90);
    }

    @Test
    void generatePatchWrapsToroidally() {
        // Center at (0, 0) with radius 3 — cells near (WIDTH-1, 0) should receive nutrients
        FertilityInitializer fi = init(new FertilityConfig(1, 3, 3, 100));
        fi.generatePatch(0, 0, 3, WIDTH, HEIGHT);
        // (WIDTH-1, 0) is distance 1 from center on a toroidal grid
        assertThat(grid.getCell(WIDTH - 1, 0).nutrientLevel()).isGreaterThan(0);
        // (0, HEIGHT-1) is distance 1 as well
        assertThat(grid.getCell(0, HEIGHT - 1).nutrientLevel()).isGreaterThan(0);
    }

    @Test
    void initializeFertilityDoesNotClobberHighValuesAcrossPatches() {
        // Deterministic test: set a known high value, then run initializeFertility,
        // and check nothing reduced it below the pre-set value.
        grid.setCell(5, 5, Cell.EMPTY.withNutrientLevel(100));
        FertilityInitializer fi = init(new FertilityConfig(10, 3, 5, 80));
        fi.initializeFertility();
        // Cell preset at 100 is higher than any patch's maxLevel=80, so must survive
        assertThat(grid.getCell(5, 5).nutrientLevel()).isEqualTo(100);
    }
}
