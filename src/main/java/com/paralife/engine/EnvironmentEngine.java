package com.paralife.engine;

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
 * <p>Responsibilities (plan 01 scaffolding — effect bodies filled in plans
 * 02/03/04):
 * <ul>
 *   <li>Roll seasonal Poisson for each event type</li>
 *   <li>Spawn + advance active events (toxin path, mutagen gossip, lightning, compost)</li>
 *   <li>Resolve entity–environment collisions</li>
 *   <li>Apply corpse compost on entity death via {@link EnvCleanupHooksBean.CompostSink}</li>
 *   <li>Build per-tick {@code cellStatusCache} + {@code entityStatusCache} for
 *       PerceptionBroadcaster (D-41)</li>
 *   <li>Update {@link BuffRegistry} (expire survivor buffs, grant on cure)</li>
 *   <li>Sweep env-damaged entities at end of tick via {@link DeathFinalizer}
 *       (same-tick death model — cycle-5 contract)</li>
 * </ul>
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

    private final WorldGrid worldGrid;
    private final SeasonTracker seasonTracker;
    private final EnvironmentConfig config;
    private final BuffRegistry buffRegistry;
    private final FertilityConfig fertilityConfig;
    private final DeathFinalizer deathFinalizer;
    private final EnvCleanupHooksBean envCleanupHooksBean;
    private Random rng;

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

            // TODO(plans 02/03/04): roll Poisson, advance events, resolve collisions,
            //                       populate status caches, drain post-action grants.

            // Final step: env-death sweep — short-circuited when no env damage applied.
            processEnvDeaths();
        } catch (RuntimeException ex) {
            log.error("EnvironmentEngine.onTick failed at tick {} — continuing pipeline",
                    event.tickNumber(), ex);
        } finally {
            envDamageAppliedThisTick = false;
        }
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
