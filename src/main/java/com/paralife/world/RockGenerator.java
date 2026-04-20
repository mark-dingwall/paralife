package com.paralife.world;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PNG-based procedural rock initializer (Phase 15 D-34/D-35).
 *
 * <p>Pipeline per tick: random texture -> random rotation (0/90/180/270) ->
 * random flip (none/H/V) -> luminance threshold -> place as {@link Entity.Rock}
 * via {@link WorldGrid#trySetEntity(int, int, Entity)} (existing occupants win).
 *
 * <p>Runs once at Spring bean init via {@link #initialize()}; no per-tick cost.
 *
 * <p><b>Determinism (D-35):</b> when {@code config.seed() != 0}, the entire
 * pipeline (texture choice + rotation + flip + placement) derives from a seeded
 * {@link Random} — two runs with the same seed produce byte-identical rock
 * placement. {@code seed == 0} uses {@link ThreadLocalRandom} for the seed source.
 *
 * <p><b>Fail-fast on missing textures (D-34):</b> {@link #verifyTextures()}
 * runs at the top of {@link #initialize()} before any placement work. If ANY
 * configured texture is absent from the classpath, startup fails with a clear
 * {@link IllegalStateException} — no silent fallback to an empty rock map.
 */
@Component
public class RockGenerator {

    private static final Logger log = LoggerFactory.getLogger(RockGenerator.class);

    private final WorldGrid worldGrid;
    private final RockConfig config;

    public RockGenerator(WorldGrid worldGrid, RockConfig config) {
        this.worldGrid = worldGrid;
        this.config = config;
    }

    @PostConstruct
    public void initialize() {
        verifyTextures();
        int placed = apply(buildRandom());
        log.info("Rock init placed {} rocks (seed={}, threshold={})",
                placed, config.seed(), config.densityThreshold());
    }

    /**
     * Loads every configured texture eagerly so a missing resource surfaces as
     * a startup failure rather than a silent empty rock map at runtime. Throws
     * {@link IllegalStateException} with the offending resource path.
     */
    void verifyTextures() {
        for (String resource : config.textures()) {
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "Rock texture resource missing from classpath: " + resource
                                    + " (configured in paralife.world.rock.textures)");
                }
                BufferedImage img = ImageIO.read(in);
                if (img == null) {
                    throw new IllegalStateException(
                            "Rock texture present but could not be decoded as PNG: " + resource);
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Rock texture IO failure: " + resource, e);
            }
        }
    }

    /**
     * Core placement routine — picks a texture, randomises orientation, then
     * walks the toroidal grid thresholding luminance. Package-private so tests
     * can drive the pipeline with a fixed {@link Random} and assert
     * byte-for-byte determinism.
     *
     * @return number of rocks actually placed (cells that were empty at the
     *         time of {@code trySetEntity})
     */
    int apply(Random rng) {
        String resource = config.textures().get(rng.nextInt(config.textures().size()));
        BufferedImage img = loadTexture(resource);
        img = rotate(img, rng.nextInt(4) * 90);
        int flip = rng.nextInt(3); // 0=none, 1=H, 2=V
        if (flip != 0) {
            img = flip(img, flip);
        }

        int placed = 0;
        int tileW = img.getWidth();
        int tileH = img.getHeight();
        int width = worldGrid.getWidth();
        int height = worldGrid.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int lum = luminance(img.getRGB(Math.floorMod(x, tileW), Math.floorMod(y, tileH)));
                if (lum >= config.densityThreshold()) {
                    if (worldGrid.trySetEntity(x, y, new Entity.Rock("rock-" + x + "-" + y))) {
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    private Random buildRandom() {
        return config.seed() == 0L
                ? new Random(ThreadLocalRandom.current().nextLong())
                : new Random(config.seed());
    }

    private BufferedImage loadTexture(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Rock texture resource not found: " + resource);
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IllegalStateException("Rock texture could not be decoded: " + resource);
            }
            return img;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load rock texture: " + resource, e);
        }
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    private static BufferedImage rotate(BufferedImage src, int degrees) {
        if (degrees == 0) return src;
        AffineTransform tx = new AffineTransform();
        tx.rotate(Math.toRadians(degrees), src.getWidth() / 2.0, src.getHeight() / 2.0);
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(src, null);
    }

    private static BufferedImage flip(BufferedImage src, int mode) {
        AffineTransform tx = new AffineTransform();
        if (mode == 1) {
            tx.scale(-1, 1);
            tx.translate(-src.getWidth(), 0);
        } else {
            tx.scale(1, -1);
            tx.translate(0, -src.getHeight());
        }
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(src, null);
    }
}
