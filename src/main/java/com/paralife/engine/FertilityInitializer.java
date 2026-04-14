package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates fertility patches on the {@link WorldGrid} at world init (Phase 13 D-13).
 *
 * <p>Patches are placed at random positions with random radii in
 * {@code [patchMinRadius, patchMaxRadius]}. Each patch has a radial linear
 * falloff from {@code maxLevel} at the center to {@code 0} at the edge.
 * Overlapping patches use a max-merge so a cell's nutrient level is the
 * highest of any patch that covers it. Toroidal wrapping via
 * {@link Math#floorMod}.
 *
 * <p>Runs as a {@code @PostConstruct} hook. {@link WorldGrid} has no
 * {@code @PostConstruct} of its own, so dependency injection alone is
 * sufficient to guarantee ordering (the grid is fully constructed by the
 * time this bean's constructor resolves).
 */
@Component
public class FertilityInitializer {

    private static final Logger log = LoggerFactory.getLogger(FertilityInitializer.class);

    private final WorldGrid worldGrid;
    private final FertilityConfig config;

    public FertilityInitializer(WorldGrid worldGrid, FertilityConfig config) {
        this.worldGrid = worldGrid;
        this.config = config;
    }

    @PostConstruct
    public void initializeFertility() {
        if (config.patchCount() == 0) {
            log.debug("Fertility patchCount=0, skipping initialization");
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int width = worldGrid.getWidth();
        int height = worldGrid.getHeight();

        for (int i = 0; i < config.patchCount(); i++) {
            int cx = rng.nextInt(width);
            int cy = rng.nextInt(height);
            int radius = (config.patchMinRadius() == config.patchMaxRadius())
                    ? config.patchMinRadius()
                    : rng.nextInt(config.patchMinRadius(), config.patchMaxRadius() + 1);
            generatePatch(cx, cy, radius, width, height);
        }

        if (log.isDebugEnabled()) {
            log.debug("Fertility initialization complete: {} patches on {}x{} grid",
                    config.patchCount(), width, height);
        }
    }

    /**
     * Generate one fertility patch centered at {@code (cx, cy)} with radial
     * linear falloff out to {@code radius}. Uses max-merge so overlapping
     * patches keep the higher level per cell. Toroidal via {@link Math#floorMod}.
     *
     * <p>Package-private for direct testing.
     */
    void generatePatch(int cx, int cy, int radius, int width, int height) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > radius) continue;
                double falloff = 1.0 - (dist / radius);
                int level = (int) (config.maxLevel() * falloff);
                if (level <= 0) continue;
                int x = Math.floorMod(cx + dx, width);
                int y = Math.floorMod(cy + dy, height);
                Cell cell = worldGrid.getCell(x, y);
                int merged = Math.max(cell.nutrientLevel(), level);
                worldGrid.setCell(x, y, cell.withNutrientLevel(merged));
            }
        }
    }
}
