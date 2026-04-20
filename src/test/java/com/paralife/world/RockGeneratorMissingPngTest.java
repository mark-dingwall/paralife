package com.paralife.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup fails fast when any configured rock texture is missing from the
 * classpath. Prevents the silent "empty rock map" regression that a runtime-
 * deferred check would allow.
 *
 * <p>Review recommendation (Codex LOW #4 + Claude LOW): make missing-PNG
 * behaviour explicit via a dedicated test. {@link RockGenerator#verifyTextures()}
 * runs at {@code @PostConstruct} before any placement work.
 */
class RockGeneratorMissingPngTest {

    @Test
    void missingPngFailsFastAtStartup() {
        WorldGrid grid = new WorldGrid(new GridConfig(16, 16));
        // Four real paths + one that does not exist. The generator must detect
        // the missing one and throw BEFORE any placement work begins.
        RockConfig cfg = new RockConfig(42L, 128, List.of(
                "/rocks/perlin-01.png",
                "/rocks/perlin-02.png",
                "/rocks/perlin-03.png",
                "/rocks/DOES-NOT-EXIST.png",
                "/rocks/perlin-05.png"));
        RockGenerator gen = new RockGenerator(grid, cfg);

        IllegalStateException ex = assertThrows(IllegalStateException.class, gen::initialize);
        assertTrue(ex.getMessage().contains("DOES-NOT-EXIST.png"),
                "Exception message must name the missing resource: " + ex.getMessage());
    }

    @Test
    void allPresentTexturesInitializeCleanly() {
        WorldGrid grid = new WorldGrid(new GridConfig(16, 16));
        RockGenerator gen = new RockGenerator(grid, RockConfig.defaults());
        // Should not throw. The random seed is auto (0) — placement count is
        // not asserted here; Task 2's RockGeneratorTest covers determinism.
        gen.initialize();
    }
}
