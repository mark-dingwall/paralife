package com.paralife.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RockConfigTest {

    @Test
    void defaultsProvidesFivePerlinTextures() {
        RockConfig cfg = RockConfig.defaults();
        assertEquals(5, cfg.textures().size());
        assertEquals(0L, cfg.seed());
        assertEquals(128, cfg.densityThreshold());
    }

    @Test
    void densityThresholdBelowZeroRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RockConfig(0L, -1, List.of("/rocks/a.png")));
    }

    @Test
    void densityThresholdAboveMaxRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RockConfig(0L, 256, List.of("/rocks/a.png")));
    }

    @Test
    void emptyTexturesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RockConfig(0L, 128, List.of()));
    }

    @Test
    void nullTexturesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RockConfig(0L, 128, null));
    }

    @Test
    void negativeSeedAccepted() {
        RockConfig cfg = new RockConfig(-42L, 128, List.of("/rocks/a.png"));
        assertEquals(-42L, cfg.seed());
    }
}
