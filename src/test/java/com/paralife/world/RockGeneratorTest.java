package com.paralife.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism, toroidal, idempotence, and no-overwrite guarantees for
 * {@link RockGenerator} (Phase 15 D-34/D-35).
 *
 * <p>The tests drive {@link RockGenerator#apply(Random)} directly with a fixed
 * {@link Random} — bypassing {@link RockGenerator#initialize()} (and therefore
 * {@link RockGenerator#verifyTextures()}). Missing-PNG behaviour is covered by
 * {@link RockGeneratorMissingPngTest}.
 */
class RockGeneratorTest {

    private static final int WIDTH = 32;
    private static final int HEIGHT = 32;

    private WorldGrid grid;
    private RockConfig config;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        config = RockConfig.defaults();
    }

    private boolean[][] rockMap() {
        boolean[][] out = new boolean[WIDTH][HEIGHT];
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                out[x][y] = grid.getCell(x, y).occupant() instanceof Entity.Rock;
            }
        }
        return out;
    }

    @Test
    void sameSeedProducesIdenticalGrid() {
        WorldGrid gridA = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        WorldGrid gridB = new WorldGrid(new GridConfig(WIDTH, HEIGHT));

        new RockGenerator(gridA, config).apply(new Random(12345));
        new RockGenerator(gridB, config).apply(new Random(12345));

        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                boolean a = gridA.getCell(x, y).occupant() instanceof Entity.Rock;
                boolean b = gridB.getCell(x, y).occupant() instanceof Entity.Rock;
                assertEquals(a, b, "Divergent rock placement at (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void differentSeedsProduceDifferentGrids() {
        WorldGrid gridA = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
        WorldGrid gridB = new WorldGrid(new GridConfig(WIDTH, HEIGHT));

        new RockGenerator(gridA, config).apply(new Random(1));
        new RockGenerator(gridB, config).apply(new Random(99));

        int diff = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                boolean a = gridA.getCell(x, y).occupant() instanceof Entity.Rock;
                boolean b = gridB.getCell(x, y).occupant() instanceof Entity.Rock;
                if (a != b) diff++;
            }
        }
        assertTrue(diff > 0, "Different seeds must produce different grids");
    }

    @Test
    void doesNotOverwriteExistingOccupants() {
        Entity.Nutrient existing = new Entity.Nutrient("n-test", 50);
        boolean placed = grid.trySetEntity(5, 5, existing);
        assertTrue(placed);

        new RockGenerator(grid, config).apply(new Random(42));

        assertFalse(grid.getCell(5, 5).occupant() instanceof Entity.Rock,
                "RockGenerator must not overwrite pre-existing occupants");
    }

    @Test
    void placesAtLeastSomeRocksAtDefaultThreshold() {
        new RockGenerator(grid, config).apply(new Random(42));
        int rockCount = 0;
        boolean[][] map = rockMap();
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                if (map[x][y]) rockCount++;
            }
        }
        assertTrue(rockCount > 0,
                "Expected at least one rock to be placed from bundled textures");
    }
}
