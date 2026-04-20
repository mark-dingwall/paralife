package com.paralife.world;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for PNG-based rock generation (Phase 15 D-34/D-35).
 *
 * <p>{@code seed}: {@code 0} selects {@link java.util.concurrent.ThreadLocalRandom}-
 * derived randomness; any other value (positive or negative) seeds deterministically
 * via {@code new Random(seed)}. Deterministic placement is required for reproducibility
 * tests (Phase 16).
 *
 * <p>{@code densityThreshold}: luminance 0..255; pixels whose luminance
 * {@code >=} threshold become rocks. 128 is the half-brightness default.
 *
 * <p>{@code textures}: classpath resource paths (e.g. {@code /rocks/perlin-01.png}).
 * Loaded once at {@code @PostConstruct} — a missing entry fails startup.
 *
 * <p>Bound from {@code application.yml} under {@code paralife.world.rock}. The
 * parent prefix {@code paralife.world} is already used by
 * {@link GridConfig}; Spring's relaxed binding tolerates the nested subkey
 * because {@code GridConfig}'s record components don't include a {@code rock}
 * field.
 */
@ConfigurationProperties(prefix = "paralife.world.rock")
public record RockConfig(long seed, int densityThreshold, List<String> textures) {

    public RockConfig {
        if (densityThreshold < 0 || densityThreshold > 255) {
            throw new IllegalArgumentException(
                    "densityThreshold must be 0..255: " + densityThreshold);
        }
        if (textures == null || textures.isEmpty()) {
            throw new IllegalArgumentException("textures must not be empty");
        }
        textures = List.copyOf(textures);
    }

    public static RockConfig defaults() {
        return new RockConfig(0L, 128, List.of(
                "/rocks/perlin-01.png",
                "/rocks/perlin-02.png",
                "/rocks/perlin-03.png",
                "/rocks/perlin-04.png",
                "/rocks/perlin-05.png"));
    }
}
