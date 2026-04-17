package com.paralife.engine;

import com.paralife.engine.EnvironmentConfig.Toxin;
import com.paralife.engine.SeasonTracker.Season;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Phase 14 environmental-rules tick-pipeline component (D-45, D-46).
 *
 * <p>Runs between {@link SimulationEngine}{@code @Order(10)} and
 * {@link CompositeEnergyDistributor}{@code @Order(15)} — env damage resolves
 * before the composite pool drains so composite members see post-env energy
 * when their shared pool is updated (14-PATTERNS.md "@Order slot" note;
 * {@code @Order(15)} from CONTEXT.md D-45 is stale — that slot is already
 * occupied).
 *
 * <p>Plan 14-02 fills the toxin effect body: Poisson spawn on AUTUMN peak,
 * Catmull-Rom spline advance via pre-sampled path, per-tick CA diffusion with
 * configurable Moore-neighbourhood radius + diffusionRate, per-cell entity
 * damage with normalised {@code intensity/255.0} fraction and per-type
 * resistance. BondedPair uses MAX of member-type multipliers.
 *
 * <p>Plans 03/04/05 will fill mutagen / lightning / perception bodies.
 *
 * <p><b>cycle-6 HIGH #1 — seeded RNG.</b> Production constructor branches on
 * {@code config.seed() == null}; production yaml omits the key so production
 * runs unseeded. Tests bind {@code paralife.simulation.events.seed=42} via
 * {@code @TestPropertySource} for deterministic runs.
 *
 * <p><b>cycle-4 action item #1 — bean-cycle break.</b> This engine delegates
 * the {@link DeathCleanupHooks} surface to an injected
 * {@link EnvCleanupHooksBean} third bean. Registers itself as the
 * {@link EnvCleanupHooksBean.CompostSink} in {@code @PostConstruct} so
 * compost nutrient bumps flow through the engine without introducing a
 * construction cycle with {@link DeathFinalizer}.
 */
@Component
public class EnvironmentEngine implements EnvCleanupHooksBean.CompostSink {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentEngine.class);

    /**
     * Tick-pipeline order slot. Chosen {@code 14} — one before
     * {@link CompositeEnergyDistributor}{@code @Order(15)} — so env damage
     * resolves before the composite pool drains (14-PATTERNS.md, cycle-9
     * design_note_cycle_9).
     */
    public static final int TICK_ORDER = 14;

    // ── D-38 / D-39 status bit constants ──────────────────────────────
    /** Cell-status bit: toxin intensity above {@link Toxin#intensityThreshold()}. */
    public static final byte CELL_STATUS_TOXIN_PRESENT = 0x01;
    /** Entity-status bit: entity currently standing on a toxic cell (intensity > 0). */
    public static final byte ENTITY_STATUS_TOXIC = 0x01;

    private final WorldGrid worldGrid;
    private final SeasonTracker seasonTracker;
    private final EnvironmentConfig config;
    private final BuffRegistry buffRegistry;
    private final FertilityConfig fertilityConfig;
    private final DeathFinalizer deathFinalizer;
    private final EnvCleanupHooksBean envCleanupHooksBean;
    private Random rng;
    private final ToxinPathGenerator toxinPathGenerator;

    /**
     * Toxin shadow grids — double-buffered (Pitfall 2). {@link #toxinGrid} is the
     * authoritative state read by damage + perception; {@link #toxinGridNext} is
     * the CA write target that is swapped in after each {@link CellularAutomaton#diffuseStep}.
     */
    private final byte[][] toxinGrid;
    private final byte[][] toxinGridNext;

    /** Single active toxin event (D-03 — max 1). Null when no event active. */
    private ToxinEvent activeToxin;

    /**
     * Number of non-zero cells in {@link #toxinGrid}. Updated on every diffuse
     * step via the return value of {@link CellularAutomaton#diffuseStep} AND
     * on direct writes via {@link #stampToxinIntensityForTest}. Enables an O(1)
     * idle-tick fast-path in {@link #advanceToxin}.
     */
    private int nonZeroToxinCellCount = 0;

    /** D-41: per-tick status caches — derived read-only projections from shadow grids. */
    private final Map<Position, Byte> cellStatusCache = new HashMap<>();
    private final Map<String, Byte> entityStatusCache = new HashMap<>();

    /**
     * Short-circuit flag (cycle-5 contract). Env damage sites in plans 02/03/04
     * call {@link #markEnvDamageApplied()} when a write may have reached zero
     * energy. {@link #processEnvDeaths()} skips the full-grid scan when this
     * flag is false.
     */
    private volatile boolean envDamageAppliedThisTick = false;

    @org.springframework.beans.factory.annotation.Autowired
    public EnvironmentEngine(WorldGrid worldGrid, SeasonTracker seasonTracker,
                              EnvironmentConfig config, BuffRegistry buffRegistry,
                              FertilityConfig fertilityConfig, DeathFinalizer deathFinalizer,
                              EnvCleanupHooksBean envCleanupHooksBean) {
        this(worldGrid, seasonTracker, config, buffRegistry, fertilityConfig, deathFinalizer,
                envCleanupHooksBean,
                config.seed() == null ? new Random() : new Random(config.seed()));
    }

    /** Package-private test constructor for deterministic Random injection. */
    EnvironmentEngine(WorldGrid worldGrid, SeasonTracker seasonTracker,
                      EnvironmentConfig config, BuffRegistry buffRegistry,
                      FertilityConfig fertilityConfig, DeathFinalizer deathFinalizer,
                      EnvCleanupHooksBean envCleanupHooksBean, Random rng) {
        this.worldGrid = worldGrid;
        this.seasonTracker = seasonTracker;
        this.config = config;
        this.buffRegistry = buffRegistry;
        this.fertilityConfig = fertilityConfig;
        this.deathFinalizer = deathFinalizer;
        this.envCleanupHooksBean = envCleanupHooksBean;
        this.rng = rng;
        this.toxinPathGenerator = new ToxinPathGenerator();
        this.toxinGrid = new byte[worldGrid.getWidth()][worldGrid.getHeight()];
        this.toxinGridNext = new byte[worldGrid.getWidth()][worldGrid.getHeight()];
    }

    /**
     * Register ourselves as the compost sink on the shared hooks bean so
     * {@link DeathFinalizer}'s death-cleanup pipeline can flow compost events
     * back through this engine (D-24/D-25). cycle-4 action item #1 — setter
     * wired post-construction to avoid a construction cycle.
     */
    @PostConstruct
    void registerAsCompostSink() {
        envCleanupHooksBean.registerCompostSink(this);
        log.debug("EnvironmentEngine registered as CompostSink on EnvCleanupHooksBean");
    }

    @EventListener
    @Order(TICK_ORDER)
    public void onTick(TickEvent event) {
        try {
            // Always rebuild caches and expire buffs, even when config.enabled() is false,
            // so PerceptionBroadcaster reads a consistent empty surface (Pitfall 7).
            cellStatusCache.clear();
            entityStatusCache.clear();
            buffRegistry.expireBuffs(event.tickNumber());

            if (!config.enabled()) {
                envDamageAppliedThisTick = false;
                return;
            }

            // Plan 14-02 toxin pipeline:
            spawnToxin(event.tickNumber());
            advanceToxin(event.tickNumber());
            resolveToxinCollisions(event.tickNumber());
            buildStatusCaches();

            // Final step: env-death sweep — short-circuited when no env damage applied.
            processEnvDeaths();
        } catch (RuntimeException ex) {
            log.error("EnvironmentEngine.onTick failed at tick {} — continuing pipeline",
                    event.tickNumber(), ex);
        } finally {
            envDamageAppliedThisTick = false;
        }
    }

    // ── Toxin pipeline (Plan 14-02) ──────────────────────────────────

    /**
     * Roll seasonal Poisson for a new toxin event. Max 1 active (D-03) — skip
     * if {@link #activeToxin} is non-null. WINTER gate: no events fire during
     * the off-season when lambda is floored by {@code config.toxin().offSeasonLambda()}.
     */
    void spawnToxin(long tickNumber) {
        if (activeToxin != null) return;
        double lambda = seasonalToxinLambda(tickNumber);
        if (rng.nextDouble() >= lambda) return;

        Toxin tx = config.toxin();
        long seed = rng.nextLong();
        List<Position> path = toxinPathGenerator.generatePath(
                worldGrid.getWidth(), worldGrid.getHeight(),
                tx.pathPointsMin(), tx.pathPointsMax(),
                tx.pathOffsetMin(), tx.pathOffsetMax());
        activeToxin = new ToxinEvent(tickNumber, tx.lifetimeTicks(), path, 0, seed);
        log.debug("Toxin spawned: tick={} pathLen={} seed={}", tickNumber, path.size(), seed);
    }

    /**
     * Peak-season sine-scaled Poisson lambda (D-27). Off-season uses the flat
     * {@code offSeasonLambda}. Peak season is {@link Toxin#peakSeason()}.
     */
    double seasonalToxinLambda(long tickNumber) {
        Toxin tx = config.toxin();
        Season current = seasonTracker.getSeason(tickNumber);
        if (current != tx.peakSeason()) {
            return tx.offSeasonLambda();
        }
        // Sine scale 0..1..0 across the peak season window. Use the seasonal
        // multiplier (1 ± amplitude) renormalised to a 0..1 fraction.
        double mult = seasonTracker.getSeasonalMultiplier(tickNumber);
        double amp = seasonTracker.getConfig().amplitude();
        double frac = amp > 0.0 ? Math.clamp((mult - (1.0 - amp)) / (2.0 * amp), 0.0, 1.0) : 1.0;
        return tx.offSeasonLambda() + (tx.peakLambda() - tx.offSeasonLambda()) * frac;
    }

    /**
     * Advance the active toxin event along its pre-sampled path and diffuse
     * the shadow grid. Fast-path: when {@code activeToxin == null &&
     * nonZeroToxinCellCount == 0} do nothing — no scans.
     */
    void advanceToxin(long tickNumber) {
        // O(1) idle-tick fast-path (T-14-02-07).
        if (activeToxin == null && nonZeroToxinCellCount == 0) {
            return;
        }

        Toxin tx = config.toxin();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        // ── Step 1: advance the head (if event active) ─────────────
        if (activeToxin != null) {
            if (activeToxin.isExpired(tickNumber)) {
                activeToxin = null;
            } else {
                int newHead = activeToxin.headIdx();
                for (int s = 0; s < tx.speed() && newHead < activeToxin.prePath().size(); s++) {
                    Position pos = activeToxin.prePath().get(newHead);
                    // Stamp at full intensity on every cell the head visits.
                    int before = toxinGrid[pos.x()][pos.y()] & 0xFF;
                    toxinGrid[pos.x()][pos.y()] = (byte) 255;
                    if (before == 0) nonZeroToxinCellCount++;
                    newHead++;
                }
                if (newHead >= activeToxin.prePath().size()) {
                    activeToxin = null;
                } else {
                    activeToxin = activeToxin.withHeadIdx(newHead);
                }
            }
        }

        // ── Step 2: CA diffusion (double-buffered) ────────────────
        // diffusionRate + decayRate + diffusionRadius read from config.toxin()
        // (cycle-2 addition; no longer hardcoded).
        int nz = CellularAutomaton.diffuseStep(toxinGrid, toxinGridNext, w, h,
                config.toxin().diffusionRate(), tx.decayRate(), 1, tx.diffusionRadius());
        // Swap src/next by copying dst back to src. We could flip pointers,
        // but that would require mutable field references; the shadow grids are
        // final byte[][] so copy is simpler + safer.
        for (int x = 0; x < w; x++) {
            System.arraycopy(toxinGridNext[x], 0, toxinGrid[x], 0, h);
        }
        nonZeroToxinCellCount = nz;
    }

    /**
     * Apply toxin damage to occupants of cells with positive intensity. Damage
     * formula per D-08/D-09: {@code baseDamage * (intensity/255.0) * typeResistance}.
     * Covers Particle, BondedPair, AND CompositeMember (occupant-type-exhaustive
     * per must-haves).
     *
     * <p><b>BondedPair rule:</b> resistance is the MAX of per-type multipliers
     * for {@code primaryType} and {@code secondaryType} — worst-case resistance
     * drives damage. Documented inline.
     */
    void resolveToxinCollisions(long tickNumber) {
        Toxin tx = config.toxin();
        int baseDamage = tx.baseDamage();
        if (baseDamage <= 0) return;

        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int intensity = toxinGrid[x][y] & 0xFF;
                if (intensity <= 0) continue;
                Cell cell = worldGrid.getCell(x, y);
                Entity occ = cell.occupant();
                if (occ == null) continue;

                double fraction = intensity / 255.0;
                if (occ instanceof Particle p) {
                    double resistance = tx.resistance().forType(p.type());
                    int damage = (int) (baseDamage * fraction * resistance);
                    if (damage <= 0) continue;
                    worldGrid.setEntity(x, y, p.withEnergy(Math.max(0, p.energy() - damage)));
                    markEnvDamageApplied();
                } else if (occ instanceof BondedPair bp) {
                    // BondedPair MAX rule: worst-case resistance between primary+secondary.
                    double resistance = Math.max(
                            tx.resistance().forType(bp.primaryType()),
                            tx.resistance().forType(bp.secondaryType()));
                    int damage = (int) (baseDamage * fraction * resistance);
                    if (damage <= 0) continue;
                    worldGrid.setEntity(x, y, bp.withEnergy(Math.max(0, bp.energy() - damage)));
                    markEnvDamageApplied();
                } else if (occ instanceof CompositeMember cm) {
                    double resistance = tx.resistance().forType(cm.type());
                    int damage = (int) (baseDamage * fraction * resistance);
                    if (damage <= 0) continue;
                    worldGrid.setEntity(x, y, cm.withEnergy(Math.max(0, cm.energy() - damage)));
                    markEnvDamageApplied();
                }
            }
        }
    }

    /**
     * Populate {@link #cellStatusCache} and {@link #entityStatusCache} for
     * PerceptionBroadcaster (Plan 05). Cell-visibility threshold is
     * {@link Toxin#intensityThreshold()}; entity-level TOXIC bit fires for
     * ANY positive intensity — separate rules, separate thresholds (must-haves).
     */
    void buildStatusCaches() {
        if (nonZeroToxinCellCount == 0 && activeToxin == null) return;
        Toxin tx = config.toxin();
        int threshold = tx.intensityThreshold();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int intensity = toxinGrid[x][y] & 0xFF;
                if (intensity <= 0) continue;

                Position pos = new Position(x, y);
                if (intensity >= threshold) {
                    Byte prior = cellStatusCache.get(pos);
                    byte merged = (byte) ((prior == null ? 0 : prior) | CELL_STATUS_TOXIN_PRESENT);
                    cellStatusCache.put(pos, merged);
                }
                // Entity-level TOXIC: fire on ANY positive intensity.
                Cell cell = worldGrid.getCell(x, y);
                String id = EntityIds.entityIdOf(cell.occupant());
                if (id != null) {
                    Byte prior = entityStatusCache.get(id);
                    byte merged = (byte) ((prior == null ? 0 : prior) | ENTITY_STATUS_TOXIC);
                    entityStatusCache.put(id, merged);
                }
            }
        }
    }

    /**
     * Read the current toxin intensity at {@code pos}. Package-visible for
     * SimulationEngine + ActionResolver splash-damage lookup (Task 4).
     */
    public int toxinIntensityAt(Position pos) {
        int x = Math.floorMod(pos.x(), worldGrid.getWidth());
        int y = Math.floorMod(pos.y(), worldGrid.getHeight());
        return toxinGrid[x][y] & 0xFF;
    }

    /**
     * Compute splash damage for an attacker whose defender stands on a toxic
     * cell. Returns 0 for cells with no toxin. Formula per D-10:
     * {@code round(baseDamage * (intensity/255.0) * splashDamageFraction)}.
     */
    public int computeSplashDamage(Position defenderPos) {
        Toxin tx = config.toxin();
        int intensity = toxinIntensityAt(defenderPos);
        if (intensity <= 0) return 0;
        double fraction = intensity / 255.0;
        return (int) Math.round(tx.baseDamage() * fraction * tx.splashDamageFraction());
    }

    // ── Corpse compost (D-24, D-25) ───────────────────────────────

    /**
     * Apply corpse compost per D-24/D-25 — full-strength bump at {@code deathPos}
     * and half-strength bump at each of the 8 Moore neighbors. Clamped to
     * {@link FertilityConfig#maxLevel()}.
     *
     * <p>Invoked by {@link DeathFinalizer} (via {@link EnvCleanupHooksBean}'s
     * CompostSink) for every solo/bonded/composite-member death.
     */
    @Override
    public void applyCompost(Position deathPos) {
        if (!config.enabled()) {
            return;
        }
        int max = fertilityConfig.maxLevel();
        int full = config.compost().fullStrength();
        int half = config.compost().halfStrength();

        // Death cell — full strength.
        Cell deathCell = worldGrid.getCell(deathPos.x(), deathPos.y());
        int bumped = Math.min(deathCell.nutrientLevel() + full, max);
        worldGrid.setCell(deathPos.x(), deathPos.y(), deathCell.withNutrientLevel(bumped));

        // 8 Moore neighbors — half strength.
        for (Position n : worldGrid.getNeighbors(deathPos.x(), deathPos.y())) {
            Cell nc = worldGrid.getCell(n.x(), n.y());
            int nBumped = Math.min(nc.nutrientLevel() + half, max);
            worldGrid.setCell(n.x(), n.y(), nc.withNutrientLevel(nBumped));
        }

        log.debug("Compost applied: deathPos={} full={} half={} max={}",
                deathPos, full, half, max);
    }

    // ── Same-tick env-death sweep (cycle-5 contract) ─────────────

    /**
     * Mark that an env damage write may have reached zero energy this tick.
     * Damage sites in plans 02/03/04 call this when a write is lethal-possible.
     * Package-private — only called from within this package.
     */
    void markEnvDamageApplied() {
        envDamageAppliedThisTick = true;
    }

    /**
     * Sweep the grid for env-damaged zero-energy occupants and route each
     * through {@link DeathFinalizer}. Short-circuited when no env damage
     * applied this tick.
     *
     * <p>Called as the LAST step of {@link #onTick} (regular env-phase path)
     * AND re-invoked by {@link EnvPostActionReconciler} @Order(25) (composite
     * attack-path splash — cycle-4 action item #2, T-14-17).
     */
    public void processEnvDeaths() {
        if (!envDamageAppliedThisTick) return;

        int width = worldGrid.getWidth();
        int height = worldGrid.getHeight();
        Set<String> processedComposites = new HashSet<>();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Cell cell = worldGrid.getCell(x, y);
                Entity occupant = cell.occupant();
                if (occupant == null) continue;
                if (occupant instanceof Particle p && !p.isAlive()) {
                    deathFinalizer.finalizeParticleDeath(x, y, p);
                } else if (occupant instanceof BondedPair bp && !bp.isAlive()) {
                    deathFinalizer.finalizeBondedPairDeath(x, y, bp);
                } else if (occupant instanceof CompositeMember cm && !cm.isAlive()) {
                    deathFinalizer.finalizeCompositeMemberDeath(x, y, cm, processedComposites);
                }
            }
        }
    }

    /**
     * Drain post-action buff grants enqueued by composite-attack-path cures
     * (plan 14-03). Plan 01 ships an empty no-op body; plan 14-03 Task 2
     * extends the signature to {@code drainPostActionGrants(long tickNumber)}.
     */
    public void drainPostActionGrants() {
        // Plan 14-03 fills this method body.
    }

    // ── Test-only helpers (package-private) ──────────────────────

    /** Expose {@link #markEnvDamageApplied()} to tests. */
    void markEnvDamageAppliedForTest() {
        markEnvDamageApplied();
    }

    /** Run {@link #processEnvDeaths()} from tests. */
    void processEnvDeathsForTest() {
        processEnvDeaths();
    }

    /**
     * Test helper — lethal a {@link Particle} at the given position by writing
     * energy=0, then mark env damage so {@link #processEnvDeaths()} picks it up.
     */
    void killParticleAtForTest(int x, int y) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof Particle p) {
            worldGrid.setEntity(x, y, p.withEnergy(0));
            markEnvDamageApplied();
        }
    }

    /**
     * Test helper — lethal a {@link CompositeMember} at the given position by
     * writing energy=0, then mark env damage so {@link #processEnvDeaths()}
     * picks it up.
     */
    void killCompositeMemberAtForTest(int x, int y) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof CompositeMember cm) {
            worldGrid.setEntity(x, y, cm.withEnergy(0));
            markEnvDamageApplied();
        }
    }

    /** Test helper — read-only snapshot of the current tick's cell status cache. */
    Map<Position, Byte> cellStatusCacheView() {
        return Collections.unmodifiableMap(cellStatusCache);
    }

    /** Test helper — read-only snapshot of the current tick's entity status cache. */
    Map<String, Byte> entityStatusCacheView() {
        return Collections.unmodifiableMap(entityStatusCache);
    }

    /** Test helper — read the short-circuit flag. */
    boolean envDamageAppliedThisTickForTest() {
        return envDamageAppliedThisTick;
    }

    /** Test helper — stamp an exact intensity on a grid cell, updating the counter. */
    void stampToxinIntensityForTest(Position pos, int intensity) {
        int x = Math.floorMod(pos.x(), worldGrid.getWidth());
        int y = Math.floorMod(pos.y(), worldGrid.getHeight());
        int before = toxinGrid[x][y] & 0xFF;
        int clamped = Math.clamp(intensity, 0, 255);
        toxinGrid[x][y] = (byte) clamped;
        if (before == 0 && clamped > 0) nonZeroToxinCellCount++;
        else if (before > 0 && clamped == 0) nonZeroToxinCellCount--;
    }

    /** Test helper — synchronous resolveToxinCollisions for a single tick. */
    void resolveToxinCollisionsForTest(long tickNumber) {
        resolveToxinCollisions(tickNumber);
    }

    /** Test helper — run advanceToxin once (diffusion step). */
    void advanceToxinForTest(long tickNumber) {
        advanceToxin(tickNumber);
    }

    /** Test helper — force an active toxin event (skips Poisson roll). */
    void forceSpawnToxinForTest(long tickNumber) {
        Toxin tx = config.toxin();
        long seed = rng.nextLong();
        List<Position> path = toxinPathGenerator.generatePath(
                worldGrid.getWidth(), worldGrid.getHeight(),
                tx.pathPointsMin(), tx.pathPointsMax(),
                tx.pathOffsetMin(), tx.pathOffsetMax());
        activeToxin = new ToxinEvent(tickNumber, tx.lifetimeTicks(), path, 0, seed);
    }

    /** Test helper — expose active toxin event. */
    ToxinEvent activeToxinEvent() {
        return activeToxin;
    }

    /** Test helper — expose non-zero toxin cell counter. */
    int nonZeroToxinCellCountForTest() {
        return nonZeroToxinCellCount;
    }

    /** Test helper — call buildStatusCaches directly. */
    void buildStatusCachesForTest() {
        buildStatusCaches();
    }

    /**
     * Test helper — wipe toxin grid + event state between tests sharing a
     * Spring context. Zeroes {@code toxinGrid} + {@code toxinGridNext}, clears
     * {@code activeToxin}, resets {@code nonZeroToxinCellCount} and the
     * {@code envDamageAppliedThisTick} flag.
     */
    void resetToxinStateForTest() {
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                toxinGrid[x][y] = 0;
                toxinGridNext[x][y] = 0;
            }
        }
        activeToxin = null;
        nonZeroToxinCellCount = 0;
        envDamageAppliedThisTick = false;
        cellStatusCache.clear();
        entityStatusCache.clear();
    }

    /**
     * Projected cell-status byte for a position (D-38). Zero by default.
     * PerceptionBroadcaster will call this in plan 05 via a narrow accessor.
     */
    public byte getCellStatus(Position pos) {
        Byte b = cellStatusCache.get(pos);
        return b == null ? (byte) 0 : b;
    }

    /**
     * Projected entity-status byte for an entity id (D-39). Zero by default.
     */
    public byte getEntityStatus(String entityId) {
        Byte b = entityStatusCache.get(entityId);
        return b == null ? (byte) 0 : b;
    }
}
