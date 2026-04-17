package com.paralife.engine;

import com.paralife.engine.BuffRegistry.BuffType;
import com.paralife.engine.EnvCleanupHooksBean.PendingGrant;
import com.paralife.engine.EnvironmentConfig.Mutagen;
import com.paralife.engine.EnvironmentConfig.Toxin;
import com.paralife.engine.SeasonTracker.Season;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.Role;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * <p>Plan 14-02 filled the toxin effect body. Plan 14-03 fills the mutagen
 * outbreak body: Poisson spawn on SPRING peak, strain gossip propagation with
 * ±1 strain mutation, per-entity Infection records with damage-over-time,
 * post-damage-alive-gated survivor buff grants (D-15, D-18 role-specific for
 * CompositeMember), attack-cure-reduction hook (D-20), cure immunity grace
 * period (D-17), and zone-decay phase after the event expires.
 *
 * <p><b>cycle-6 HIGH #1 — seeded RNG.</b> Production constructor branches on
 * {@code config.seed() == null}; production yaml omits the key so production
 * runs unseeded. Tests bind {@code paralife.simulation.events.seed=42} via
 * {@code @TestPropertySource} for deterministic runs.
 *
 * <p><b>cycle-4 action item #1 — bean-cycle break.</b> This engine delegates
 * the {@link DeathCleanupHooks} surface to an injected
 * {@link EnvCleanupHooksBean} third bean which owns the canonical infection /
 * cureImmuneUntil / pendingBuffGrants maps. This engine reads/writes through
 * the bean via its public accessors (no duplicate storage).
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
    /** Cell-status bit 1: toxin intensity above {@link Toxin#intensityThreshold()}. */
    public static final byte CELL_STATUS_TOXIN_PRESENT = 0x01;
    /** Cell-status bit 2: mutagen strain cell (D-38). */
    public static final byte CELL_STATUS_MUTAGEN_ZONE = 0x04;
    /** Entity-status bit 1: entity currently standing on a toxic cell (intensity > 0). */
    public static final byte ENTITY_STATUS_TOXIC = 0x01;
    /** Entity-status bit 2: entity has an active {@link Infection} (D-39). */
    public static final byte ENTITY_STATUS_MUTATING = 0x04;
    /** Entity-status bit 3: entity has any active survivor buff (D-39). */
    public static final byte ENTITY_STATUS_BUFFED = 0x08;

    private final WorldGrid worldGrid;
    private final SeasonTracker seasonTracker;
    private final EnvironmentConfig config;
    private final BuffRegistry buffRegistry;
    private final FertilityConfig fertilityConfig;
    private final DeathFinalizer deathFinalizer;
    private final EnvCleanupHooksBean envCleanupHooksBean;
    // Plan 14-06 Task 1: rng is a MUTABLE field (NOT final) so resetForTest can
    // reassign it from config.seed to produce deterministic cross-run replay.
    private Random rng;
    private final ToxinPathGenerator toxinPathGenerator;

    /**
     * Plan 14-06 Task 3b: monotonic rising-edge count of toxin events spawned.
     * Incremented by {@link #spawnToxin} whenever {@code activeToxin} is newly
     * installed. The phase-gate integration test asserts this is > 0 after
     * 300 ticks.
     */
    private long toxinEventCount = 0L;

    /**
     * Plan 14-06 Task 3b: monotonic count of mutagen infections initiated.
     * Incremented in {@link #resolveMutagenCollisions} each time a new
     * {@link Infection} record is inserted into the bean's map. The phase-gate
     * integration test asserts this is > 0 after 300 ticks.
     */
    private long mutagenInfectionEventCount = 0L;

    /**
     * Monotonic count of lightning strikes FIRED. <b>"Attempted-strike"
     * semantics:</b> incremented exactly once per successful Poisson roll in
     * {@link #spawnLightning}, BEFORE {@link #applyLightningAt} runs. An
     * exception inside {@code applyLightningAt} still leaves the counter
     * incremented — a strike was attempted; its side-effects may be partial.
     * The {@link #applyLightningAtForTest} helper preserves this ordering
     * (increment before apply) so test semantics match production semantics
     * exactly.
     */
    private long lightningStrikeCount = 0L;

    /**
     * Toxin shadow grids — double-buffered (Pitfall 2).
     */
    private final byte[][] toxinGrid;
    private final byte[][] toxinGridNext;

    /** Single active toxin event (D-03 — max 1). Null when no event active. */
    private ToxinEvent activeToxin;

    /** Non-zero cell counter for toxin grid (O(1) idle-tick fast-path). */
    private int nonZeroToxinCellCount = 0;

    /**
     * Mutagen shadow grids — double-buffered (Pitfall 2). Each cell stores the
     * strain byte (0 = clean, 1-255 = strain id). See D-13.
     */
    private final byte[][] mutagenGrid;
    private final byte[][] mutagenGridNext;

    /**
     * Per-cell last-reinforced tick for mutagen zone decay. A cell that has
     * not been reinforced (or never gossip-infected) for longer than
     * {@link Mutagen#zoneDecayTicks()} is cleared after the active event
     * expires.
     */
    private final long[][] mutagenLastReinforcedTick;

    /** Single active mutagen event (D-03 — max 1). Null when no event active. */
    private MutagenEvent activeMutagen;

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

    /**
     * Plan 14-03 perf counter: number of {@code worldGrid.getCell} calls made
     * inside {@link #tickBuffsAndInfections}. Structural perf assertion uses
     * this instead of wall-clock (T-14-03-05).
     */
    private int gridReadCountForTest = 0;

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
        this(worldGrid, seasonTracker, config, buffRegistry, fertilityConfig, deathFinalizer,
                envCleanupHooksBean, new ToxinPathGenerator(), rng);
    }

    /**
     * Package-private test constructor for deterministic Random + explicit
     * {@link ToxinPathGenerator} injection. Exposes the pinned no-arg
     * {@code new ToxinPathGenerator()} construction surface to tests
     * (cycle-6 MEDIUM — 14-02 Task 1 is authoritative).
     */
    EnvironmentEngine(WorldGrid worldGrid, SeasonTracker seasonTracker,
                      EnvironmentConfig config, BuffRegistry buffRegistry,
                      FertilityConfig fertilityConfig, DeathFinalizer deathFinalizer,
                      EnvCleanupHooksBean envCleanupHooksBean,
                      ToxinPathGenerator toxinPathGenerator, Random rng) {
        this.worldGrid = worldGrid;
        this.seasonTracker = seasonTracker;
        this.config = config;
        this.buffRegistry = buffRegistry;
        this.fertilityConfig = fertilityConfig;
        this.deathFinalizer = deathFinalizer;
        this.envCleanupHooksBean = envCleanupHooksBean;
        this.rng = rng;
        this.toxinPathGenerator = toxinPathGenerator;
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        this.toxinGrid = new byte[w][h];
        this.toxinGridNext = new byte[w][h];
        this.mutagenGrid = new byte[w][h];
        this.mutagenGridNext = new byte[w][h];
        this.mutagenLastReinforcedTick = new long[w][h];
    }

    /**
     * Register ourselves as the compost sink on the shared hooks bean so
     * {@link DeathFinalizer}'s death-cleanup pipeline can flow compost events
     * back through this engine (D-24/D-25).
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

            // Plan 14-03 mutagen pipeline:
            spawnMutagen(event.tickNumber());
            advanceMutagen(event.tickNumber());
            resolveMutagenCollisions(event.tickNumber());
            tickBuffsAndInfections(event.tickNumber());

            // Plan 14-04 lightning pipeline (seasonal Poisson, single-tick dual-radius):
            spawnLightning(event.tickNumber());

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
        toxinEventCount++; // Plan 14-06 Task 3b: rising-edge counter
        log.debug("Toxin spawned: tick={} pathLen={} seed={}", tickNumber, path.size(), seed);
    }

    double seasonalToxinLambda(long tickNumber) {
        Toxin tx = config.toxin();
        Season current = seasonTracker.getSeason(tickNumber);
        if (current != tx.peakSeason()) {
            return tx.offSeasonLambda();
        }
        double mult = seasonTracker.getSeasonalMultiplier(tickNumber);
        double amp = seasonTracker.getConfig().amplitude();
        double frac = amp > 0.0 ? Math.clamp((mult - (1.0 - amp)) / (2.0 * amp), 0.0, 1.0) : 1.0;
        return tx.offSeasonLambda() + (tx.peakLambda() - tx.offSeasonLambda()) * frac;
    }

    void advanceToxin(long tickNumber) {
        if (activeToxin == null && nonZeroToxinCellCount == 0) {
            return;
        }

        Toxin tx = config.toxin();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        if (activeToxin != null) {
            if (activeToxin.isExpired(tickNumber)) {
                activeToxin = null;
            } else {
                int newHead = activeToxin.headIdx();
                for (int s = 0; s < tx.speed() && newHead < activeToxin.prePath().size(); s++) {
                    Position pos = activeToxin.prePath().get(newHead);
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

        int nz = CellularAutomaton.diffuseStep(toxinGrid, toxinGridNext, w, h,
                config.toxin().diffusionRate(), tx.decayRate(), 1, tx.diffusionRadius());
        for (int x = 0; x < w; x++) {
            System.arraycopy(toxinGridNext[x], 0, toxinGrid[x], 0, h);
        }
        nonZeroToxinCellCount = nz;
    }

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

    // ── Mutagen pipeline (Plan 14-03) ──────────────────────────────

    /**
     * Roll seasonal Poisson for a new mutagen outbreak. Max 1 active (D-03).
     * WINTER gate: no events fire during off-season.
     */
    void spawnMutagen(long tickNumber) {
        if (activeMutagen != null) return;
        double lambda = seasonalMutagenLambda(tickNumber);
        if (rng.nextDouble() >= lambda) return;

        Mutagen cfg = config.mutagen();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        int ox = rng.nextInt(w);
        int oy = rng.nextInt(h);

        // Stamp a non-zero strain byte at origin cell — 1..255 uniform.
        int strain = 1 + rng.nextInt(255);
        mutagenGrid[ox][oy] = (byte) strain;
        mutagenLastReinforcedTick[ox][oy] = tickNumber;

        activeMutagen = new MutagenEvent(tickNumber, new Position(ox, oy),
                cfg.outbreakLifetimeTicks());
        log.debug("Mutagen spawned: tick={} origin=({},{}) strain={} lifetime={}",
                tickNumber, ox, oy, strain, cfg.outbreakLifetimeTicks());
    }

    /**
     * Peak-season sine-scaled Poisson lambda for mutagen (D-27). Off-season
     * uses the flat {@code offSeasonLambda}.
     */
    double seasonalMutagenLambda(long tickNumber) {
        Mutagen cfg = config.mutagen();
        Season current = seasonTracker.getSeason(tickNumber);
        if (current != cfg.peakSeason()) {
            return cfg.offSeasonLambda();
        }
        double mult = seasonTracker.getSeasonalMultiplier(tickNumber);
        double amp = seasonTracker.getConfig().amplitude();
        double frac = amp > 0.0 ? Math.clamp((mult - (1.0 - amp)) / (2.0 * amp), 0.0, 1.0) : 1.0;
        return cfg.offSeasonLambda() + (cfg.peakLambda() - cfg.offSeasonLambda()) * frac;
    }

    /**
     * Advance mutagen: double-buffered gossip while active; zone decay
     * (cell-level aging) while null.
     */
    void advanceMutagen(long tickNumber) {
        Mutagen cfg = config.mutagen();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        // Handle expiry first — the active event may have expired this tick.
        if (activeMutagen != null && activeMutagen.isExpired(tickNumber)) {
            activeMutagen = null;
        }

        if (activeMutagen != null) {
            // Gossip propagation — double-buffered CA-like step.
            // Copy current grid into next buffer, then layer gossip additions on top.
            for (int x = 0; x < w; x++) {
                System.arraycopy(mutagenGrid[x], 0, mutagenGridNext[x], 0, h);
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int strain = mutagenGrid[x][y] & 0xFF;
                    if (strain == 0) continue;
                    // Gossip to 8 Moore neighbors per-neighbor with configured probability.
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = Math.floorMod(x + dx, w);
                            int ny = Math.floorMod(y + dy, h);
                            int existingStrain = mutagenGridNext[nx][ny] & 0xFF;
                            if (existingStrain != 0) continue; // neighbor already infected
                            if (rng.nextDouble() >= cfg.gossipProbability()) continue;
                            int mutated = strain;
                            if (rng.nextDouble() < cfg.strainMutationChance()) {
                                // ±1 drift (unsigned byte wrap OK — 0 sentinel preserved by clamp below)
                                int delta = rng.nextBoolean() ? 1 : -1;
                                mutated = strain + delta;
                                if (mutated <= 0) mutated = 1;
                                if (mutated > 255) mutated = 255;
                            }
                            mutagenGridNext[nx][ny] = (byte) mutated;
                            mutagenLastReinforcedTick[nx][ny] = tickNumber;
                        }
                    }
                }
            }
            // Swap next → current.
            for (int x = 0; x < w; x++) {
                System.arraycopy(mutagenGridNext[x], 0, mutagenGrid[x], 0, h);
            }
        } else {
            // No active event — zone decay phase. Clear any strain cell that has
            // not been reinforced within zoneDecayTicks.
            int decayTicks = cfg.zoneDecayTicks();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int strain = mutagenGrid[x][y] & 0xFF;
                    if (strain == 0) continue;
                    long lastReinforced = mutagenLastReinforcedTick[x][y];
                    if (tickNumber - lastReinforced >= decayTicks) {
                        mutagenGrid[x][y] = 0;
                    }
                }
            }
        }
    }

    /**
     * Walk every occupant on a non-zero-strain cell. Infect under
     * cureImmuneUntil gate. BondedPair infected once per bp.id() (shared
     * infection semantics per must-haves). Infection record captures the
     * entity's current Position (T-14-03-11 cure-path fix).
     */
    void resolveMutagenCollisions(long tickNumber) {
        Mutagen cfg = config.mutagen();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        Map<String, Infection> infections = envCleanupHooksBean.getInfections();
        Map<String, Long> cureImmuneUntil = envCleanupHooksBean.getCureImmuneUntil();

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int strain = mutagenGrid[x][y] & 0xFF;
                if (strain == 0) continue;
                Cell cell = worldGrid.getCell(x, y);
                Entity occ = cell.occupant();
                if (occ == null) continue;
                String id = EntityIds.entityIdOf(occ);
                if (id == null) continue;
                if (infections.containsKey(id)) continue; // already infected

                // cure-immunity gate
                Long immuneUntil = cureImmuneUntil.get(id);
                if (immuneUntil != null && tickNumber < immuneUntil) continue;

                int minDur = cfg.infectionDurationMin();
                int maxDur = cfg.infectionDurationMax();
                int dur = maxDur > minDur
                        ? minDur + rng.nextInt(maxDur - minDur + 1)
                        : minDur;
                Infection infection = new Infection(dur, (byte) strain,
                        cfg.damagePerTick(), dur, new Position(x, y));
                infections.put(id, infection);
                mutagenInfectionEventCount++; // Plan 14-06 Task 3b counter
            }
        }
    }

    /**
     * Apply DoT + advance infection counter. Phase A scans the grid once,
     * building a per-entity Position index. Phase B iterates pending grants
     * and grants survivor buffs to entities that are still alive.
     */
    void tickBuffsAndInfections(long tickNumber) {
        Mutagen cfg = config.mutagen();
        Map<String, Infection> infections = envCleanupHooksBean.getInfections();
        Map<String, Long> cureImmuneUntil = envCleanupHooksBean.getCureImmuneUntil();
        gridReadCountForTest = 0;

        if (infections.isEmpty()) {
            // Still drain any pending grants from prior tick's reduceInfection.
            processPendingGrants(tickNumber, cfg);
            return;
        }

        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        // Phase A — single grid pass building entityId → (Position, Entity) index.
        Map<String, Position> entityPositions = new HashMap<>();
        Map<String, Entity> entitySnapshot = new HashMap<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                gridReadCountForTest++;
                Cell cell = worldGrid.getCell(x, y);
                Entity occ = cell.occupant();
                String id = EntityIds.entityIdOf(occ);
                if (id != null) {
                    entityPositions.put(id, new Position(x, y));
                    entitySnapshot.put(id, occ);
                }
            }
        }

        // Apply DoT + decrement ticksLeft.
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Infection> e : new ArrayList<>(infections.entrySet())) {
            String id = e.getKey();
            Infection inf = e.getValue();
            Position p = entityPositions.get(id);
            Entity occ = entitySnapshot.get(id);
            if (p == null || occ == null) {
                // Occupant gone (already died) — drop the infection.
                toRemove.add(id);
                continue;
            }
            // Apply DoT damage.
            if (inf.damagePerTick() > 0) {
                applyDoTDamage(p, occ, inf.damagePerTick());
                markEnvDamageApplied();
            }
            Infection advanced = inf.decrement();
            if (advanced.isExpired()) {
                // Enqueue pending grant — carries the Position captured now
                // (T-14-03-11 cure-path fix) AND the snapshot occupant for
                // role lookup at grant time.
                envCleanupHooksBean.addPendingGrant(
                        new PendingGrant(id, inf.initialTicks(), occ, p));
                toRemove.add(id);
            } else {
                infections.put(id, advanced);
            }
        }
        for (String id : toRemove) infections.remove(id);

        // Phase B — drain pending grants (post-damage-alive-gated).
        processPendingGrants(tickNumber, cfg);
    }

    /**
     * Phase B buff-grant drainer shared between {@link #tickBuffsAndInfections}
     * and the post-action drain called by {@link EnvPostActionReconciler}.
     * Looks up occupants at {@code pg.position()} — NOT via the infections map
     * (T-14-03-11).
     */
    private void processPendingGrants(long tickNumber, Mutagen cfg) {
        var grants = envCleanupHooksBean.getPendingGrants();
        if (grants.isEmpty()) return;
        List<PendingGrant> snapshot;
        synchronized (grants) {
            snapshot = new ArrayList<>(grants);
            grants.clear();
        }
        for (var pg : snapshot) {
            Position p = pg.position();
            Cell cell = worldGrid.getCell(p.x(), p.y());
            if (cell.isEmpty()) continue;
            Entity postOcc = cell.occupant();
            if (!isAliveEntity(postOcc)) continue;
            if (!pg.entityId().equals(EntityIds.entityIdOf(postOcc))) continue;
            grantSurvivorBuffs(pg.entityId(), tickNumber, cfg, pg.initialTicks(), postOcc);
            envCleanupHooksBean.getCureImmuneUntil().put(pg.entityId(),
                    tickNumber + cfg.cureTicks());
        }
    }

    /**
     * Apply DoT damage to occupant at position, routed through withEnergy so
     * energy clamps to [0, maxEnergy].
     */
    private void applyDoTDamage(Position p, Entity occ, int damage) {
        if (occ instanceof Particle part) {
            worldGrid.setEntity(p.x(), p.y(),
                    part.withEnergy(Math.max(0, part.energy() - damage)));
        } else if (occ instanceof BondedPair bp) {
            worldGrid.setEntity(p.x(), p.y(),
                    bp.withEnergy(Math.max(0, bp.energy() - damage)));
        } else if (occ instanceof CompositeMember cm) {
            worldGrid.setEntity(p.x(), p.y(),
                    cm.withEnergy(Math.max(0, cm.energy() - damage)));
        }
    }

    /**
     * Grant survivor buffs on cure (D-15, D-18).
     *
     * <p>Solo Particle / BondedPair: uniform 4-way pick from {@link BuffType}.
     * CompositeMember: role-specific buff (D-18 exhaustive mapping — no
     * fallback) PLUS universal UPKEEP_MINUS_1.
     */
    private void grantSurvivorBuffs(String entityId, long tickNumber,
                                     Mutagen cfg, int initialTicks, Entity postOcc) {
        long expiry = tickNumber + (long) initialTicks * cfg.buffDurationMultiplier();
        if (postOcc instanceof CompositeMember cm) {
            BuffType perk = roleSpecificBuff(cm.role());
            buffRegistry.grant(entityId, perk, expiry);
            buffRegistry.grant(entityId, BuffType.UPKEEP_MINUS_1, expiry);
        } else {
            BuffType pick = randomBuff();
            buffRegistry.grant(entityId, pick, expiry);
        }
        log.debug("Mutagen buff granted: entity={} tick={} expiry={}", entityId, tickNumber, expiry);
    }

    /**
     * D-18 role → buff mapping (LOCKED, exhaustive — NO fallback):
     * <pre>
     *   LOCOMOTOR  -> MOVEMENT_PLUS_1
     *   ATTACKER   -> ATTACK_PLUS_1
     *   SENSOR     -> SENSOR_PLUS_1
     *   FEEDER     -> SENSOR_PLUS_1
     *   DEFENDER   -> UPKEEP_MINUS_1
     *   REPRODUCER -> ATTACK_PLUS_1
     * </pre>
     */
    BuffType roleSpecificBuff(Role role) {
        return switch (role) {
            case LOCOMOTOR -> BuffType.MOVEMENT_PLUS_1;
            case ATTACKER -> BuffType.ATTACK_PLUS_1;
            case SENSOR -> BuffType.SENSOR_PLUS_1;
            case FEEDER -> BuffType.SENSOR_PLUS_1;
            case DEFENDER -> BuffType.UPKEEP_MINUS_1;
            case REPRODUCER -> BuffType.ATTACK_PLUS_1;
        };
    }

    private BuffType randomBuff() {
        BuffType[] all = BuffType.values();
        return all[rng.nextInt(all.length)];
    }

    /**
     * True if {@code occ} is a live entity (non-null, non-terrain, energy > 0).
     */
    private boolean isAliveEntity(Entity occ) {
        if (occ == null) return false;
        return switch (occ) {
            case Particle p -> p.energy() > 0;
            case BondedPair bp -> bp.energy() > 0;
            case CompositeMember cm -> cm.energy() > 0;
            case Entity.Rock r -> false;
            case Entity.Nutrient n -> false;
        };
    }

    /**
     * Attack-cure hook (D-20). Reduces infection ticksLeft by {@code ticks}.
     * If the infection reaches 0 in the same call, enqueues a PendingGrant
     * (post-damage-alive-gated) carrying the passed-in {@code position} so
     * the caller's in-scope defender position is used rather than a full-grid
     * id-scan (T-14-03-12).
     */
    public void reduceInfection(String entityId, int ticks, long currentTick, Position position) {
        if (entityId == null || position == null) return;
        if (ticks <= 0) return;
        Map<String, Infection> infections = envCleanupHooksBean.getInfections();
        Infection inf = infections.get(entityId);
        if (inf == null) return;
        Infection reduced = inf.reduceBy(ticks);
        if (reduced.isExpired()) {
            // Cure triggered. Enqueue pending grant at caller's Position.
            Cell cell = worldGrid.getCell(position.x(), position.y());
            if (!cell.isEmpty()) {
                Entity occ = cell.occupant();
                if (entityId.equals(EntityIds.entityIdOf(occ))) {
                    envCleanupHooksBean.addPendingGrant(
                            new PendingGrant(entityId, inf.initialTicks(), occ, position));
                }
            }
            infections.remove(entityId);
        } else {
            infections.put(entityId, reduced);
        }
    }

    /** True if {@code entityId} has an active infection. */
    public boolean isInfected(String entityId) {
        return envCleanupHooksBean.getInfections().containsKey(entityId);
    }

    /** Current attack-cure-reduction tick count (D-20). */
    public int getAttackCureReduction() {
        return config.mutagen().attackCureReduction();
    }

    /**
     * Populate {@link #cellStatusCache} and {@link #entityStatusCache} for
     * PerceptionBroadcaster (Plan 05).
     */
    void buildStatusCaches() {
        Toxin tx = config.toxin();
        int toxinThreshold = tx.intensityThreshold();
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();

        // Toxin bits (from Plan 02).
        if (nonZeroToxinCellCount > 0 || activeToxin != null) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int intensity = toxinGrid[x][y] & 0xFF;
                    if (intensity <= 0) continue;

                    Position pos = new Position(x, y);
                    if (intensity >= toxinThreshold) {
                        Byte prior = cellStatusCache.get(pos);
                        byte merged = (byte) ((prior == null ? 0 : prior) | CELL_STATUS_TOXIN_PRESENT);
                        cellStatusCache.put(pos, merged);
                    }
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

        // Mutagen MUTAGEN_ZONE cell bits.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int strain = mutagenGrid[x][y] & 0xFF;
                if (strain == 0) continue;
                Position pos = new Position(x, y);
                Byte prior = cellStatusCache.get(pos);
                byte merged = (byte) ((prior == null ? 0 : prior) | CELL_STATUS_MUTAGEN_ZONE);
                cellStatusCache.put(pos, merged);
            }
        }

        // Entity MUTATING bits for infected entities.
        for (String id : envCleanupHooksBean.getInfections().keySet()) {
            Byte prior = entityStatusCache.get(id);
            byte merged = (byte) ((prior == null ? 0 : prior) | ENTITY_STATUS_MUTATING);
            entityStatusCache.put(id, merged);
        }

        // Entity BUFFED bits for any entity with active buffs. Build by scanning
        // a grid pass — BuffRegistry lookups are O(1) per id.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Cell cell = worldGrid.getCell(x, y);
                String id = EntityIds.entityIdOf(cell.occupant());
                if (id == null) continue;
                if (buffRegistry.getBuffs(id).isEmpty()) continue;
                Byte prior = entityStatusCache.get(id);
                byte merged = (byte) ((prior == null ? 0 : prior) | ENTITY_STATUS_BUFFED);
                entityStatusCache.put(id, merged);
            }
        }
    }

    public int toxinIntensityAt(Position pos) {
        int x = Math.floorMod(pos.x(), worldGrid.getWidth());
        int y = Math.floorMod(pos.y(), worldGrid.getHeight());
        return toxinGrid[x][y] & 0xFF;
    }

    public int computeSplashDamage(Position defenderPos) {
        Toxin tx = config.toxin();
        int intensity = toxinIntensityAt(defenderPos);
        if (intensity <= 0) return 0;
        double fraction = intensity / 255.0;
        return (int) Math.round(tx.baseDamage() * fraction * tx.splashDamageFraction());
    }

    // ── Lightning pipeline (Plan 14-04) ──────────────────────────

    /**
     * Roll seasonal Poisson for a new lightning strike (D-21, D-22). Single
     * tick effect — inner-radius damage + outer-ring fertility boost. Uses
     * sine-scaled lambda during peak season ({@link EnvironmentConfig.Lightning#peakSeason()}
     * SUMMER by default) and flat {@code offSeasonLambda} elsewhere (same
     * formula as toxin/mutagen, reused for consistency).
     *
     * <p><b>"Attempted-strike" semantics (cycle-6 truth #8).</b> The
     * {@link #lightningStrikeCount} counter increments EXACTLY once per
     * successful Poisson roll, BEFORE {@link #applyLightningAt} runs. If
     * {@code applyLightningAt} throws the counter still reflects the attempt.
     * This matches {@link #applyLightningAtForTest} so unit-test observations
     * agree with production behaviour.
     */
    void spawnLightning(long tickNumber) {
        double lambda = seasonalLightningLambda(tickNumber);
        if (rng.nextDouble() >= lambda) return;

        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        int cx = rng.nextInt(w);
        int cy = rng.nextInt(h);

        // Increment BEFORE apply (attempted-strike semantics).
        lightningStrikeCount++;
        applyLightningAt(cx, cy, config.lightning());
        log.info("Lightning strike: tick={} center=({},{}) inner={} outer={} damage={} fertility={}",
                tickNumber, cx, cy,
                config.lightning().innerRadius(), config.lightning().outerRadius(),
                config.lightning().damage(), config.lightning().fertilityBoost());
    }

    /**
     * Peak-season sine-scaled Poisson lambda for lightning (D-27). Off-season
     * uses the flat {@code offSeasonLambda}. Mirrors {@link #seasonalToxinLambda}
     * + {@link #seasonalMutagenLambda} — shared sine formula.
     */
    double seasonalLightningLambda(long tickNumber) {
        EnvironmentConfig.Lightning cfg = config.lightning();
        Season current = seasonTracker.getSeason(tickNumber);
        if (current != cfg.peakSeason()) {
            return cfg.offSeasonLambda();
        }
        double mult = seasonTracker.getSeasonalMultiplier(tickNumber);
        double amp = seasonTracker.getConfig().amplitude();
        double frac = amp > 0.0 ? Math.clamp((mult - (1.0 - amp)) / (2.0 * amp), 0.0, 1.0) : 1.0;
        return cfg.offSeasonLambda() + (cfg.peakLambda() - cfg.offSeasonLambda()) * frac;
    }

    /**
     * Apply a single lightning strike centred at {@code (cx, cy)}.
     *
     * <ul>
     *   <li>{@code dist <= innerRadius}: damage occupant via
     *       {@link #damageEntityAt} (Particle / BondedPair / CompositeMember
     *       each via their own {@code withEnergy(max(0, energy - damage))}).</li>
     *   <li>{@code innerRadius < dist <= outerRadius}: fertility boost —
     *       {@code cell.withNutrientLevel(min(existing + fertilityBoost, maxLevel))}.</li>
     * </ul>
     *
     * <p>If ANY damage was applied, {@link #markEnvDamageApplied()} is invoked
     * once at the end so {@link #processEnvDeaths()} sweeps lethal hits same
     * tick (Plan 14-01 contract). The write path uses {@link WorldGrid#setEntity}
     * — no {@code clearEntity} — zero-energy occupants are reaped by
     * {@link DeathFinalizer} via the env-death sweep.
     *
     * <p>Toroidal-wrap: both x and y offsets pass through {@link Math#floorMod}
     * before addressing the grid.
     */
    void applyLightningAt(int cx, int cy, EnvironmentConfig.Lightning cfg) {
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        int inner = cfg.innerRadius();
        int outer = cfg.outerRadius();
        int damage = cfg.damage();
        int boost = cfg.fertilityBoost();
        int maxNutrient = fertilityConfig.maxLevel();
        boolean anyDamage = false;

        for (int dx = -outer; dx <= outer; dx++) {
            for (int dy = -outer; dy <= outer; dy++) {
                double dist = Math.sqrt((double) (dx * dx + dy * dy));
                if (dist > outer) continue;
                int x = Math.floorMod(cx + dx, w);
                int y = Math.floorMod(cy + dy, h);
                Cell cell = worldGrid.getCell(x, y);

                if (dist <= inner) {
                    if (damageEntityAt(x, y, cell, damage)) {
                        anyDamage = true;
                    }
                } else {
                    // Outer ring (inner < dist <= outer): fertility boost.
                    int bumped = Math.min(cell.nutrientLevel() + boost, maxNutrient);
                    worldGrid.setCell(x, y, cell.withNutrientLevel(bumped));
                }
            }
        }

        if (anyDamage) {
            markEnvDamageApplied();
        }
    }

    /**
     * Damage the occupant at {@code (x, y)} via its typed
     * {@code withEnergy(max(0, energy - damage))} — no {@code clearEntity}.
     * Zero-energy writes are reaped by {@link #processEnvDeaths()}.
     *
     * @return {@code true} if damage was applied (Particle / BondedPair /
     *         CompositeMember), {@code false} for Rock / Nutrient / empty.
     */
    boolean damageEntityAt(int x, int y, Cell cell, int damage) {
        Entity occ = cell.occupant();
        if (occ == null) return false;
        if (occ instanceof Entity.Particle p) {
            worldGrid.setEntity(x, y, p.withEnergy(Math.max(0, p.energy() - damage)));
            return true;
        }
        if (occ instanceof Entity.BondedPair bp) {
            worldGrid.setEntity(x, y, bp.withEnergy(Math.max(0, bp.energy() - damage)));
            return true;
        }
        if (occ instanceof Entity.CompositeMember cm) {
            worldGrid.setEntity(x, y, cm.withEnergy(Math.max(0, cm.energy() - damage)));
            return true;
        }
        // Rock / Nutrient: no lightning damage.
        return false;
    }

    /** Monotonic count of lightning strikes attempted (fired). */
    public long lightningStrikeCount() {
        return lightningStrikeCount;
    }

    // ── Corpse compost (D-24, D-25) ───────────────────────────────

    @Override
    public void applyCompost(Position deathPos) {
        if (!config.enabled()) {
            return;
        }
        int max = fertilityConfig.maxLevel();
        int full = config.compost().fullStrength();
        int half = config.compost().halfStrength();

        Cell deathCell = worldGrid.getCell(deathPos.x(), deathPos.y());
        int bumped = Math.min(deathCell.nutrientLevel() + full, max);
        worldGrid.setCell(deathPos.x(), deathPos.y(), deathCell.withNutrientLevel(bumped));

        for (Position n : worldGrid.getNeighbors(deathPos.x(), deathPos.y())) {
            Cell nc = worldGrid.getCell(n.x(), n.y());
            int nBumped = Math.min(nc.nutrientLevel() + half, max);
            worldGrid.setCell(n.x(), n.y(), nc.withNutrientLevel(nBumped));
        }

        log.debug("Compost applied: deathPos={} full={} half={} max={}",
                deathPos, full, half, max);
    }

    // ── Same-tick env-death sweep ─────────────

    void markEnvDamageApplied() {
        envDamageAppliedThisTick = true;
    }

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
     * Plan 14-03 cycle-6 HIGH #5a: drain composite-attack-path post-action
     * grants from ActionResolver.resolveAttackerAttack — called by
     * {@link EnvPostActionReconciler} @Order(25) with {@code event.tickNumber()}
     * so composite attack-cures receive their buffs SAME TICK.
     *
     * <p>Alive-gate + cell-occupant match rules are shared with
     * {@link #processPendingGrants}.
     */
    public void drainPostActionGrants(long tickNumber) {
        Mutagen cfg = config.mutagen();
        processPendingGrants(tickNumber, cfg);
    }

    // ── Plan 14-06 Task 1: test harness hooks ──────────────────────

    /**
     * Plan 14-06 Task 1: full env-state reset + RNG reseed for deterministic
     * cross-run replay. Clears engine-local grids AND the shared
     * {@link EnvCleanupHooksBean} maps (cycle-4 action item #1 — state lives on
     * the bean now).
     *
     * <p>Also reseeds {@link #rng} from {@code config.seed()} (null → 0L) so
     * two successive runs produce byte-identical Poisson roll sequences.
     */
    public void resetForTest() {
        int w = worldGrid.getWidth(), h = worldGrid.getHeight();
        for (int x = 0; x < w; x++) {
            java.util.Arrays.fill(toxinGrid[x], (byte) 0);
            java.util.Arrays.fill(toxinGridNext[x], (byte) 0);
            java.util.Arrays.fill(mutagenGrid[x], (byte) 0);
            java.util.Arrays.fill(mutagenGridNext[x], (byte) 0);
            java.util.Arrays.fill(mutagenLastReinforcedTick[x], 0L);
        }
        nonZeroToxinCellCount = 0;
        activeToxin = null;
        activeMutagen = null;
        // cycle-4 action item #1: shared state lives on EnvCleanupHooksBean now.
        envCleanupHooksBean.getInfections().clear();
        envCleanupHooksBean.getCureImmuneUntil().clear();
        synchronized (envCleanupHooksBean.getPendingGrants()) {
            envCleanupHooksBean.getPendingGrants().clear();
        }
        cellStatusCache.clear();
        entityStatusCache.clear();
        envDamageAppliedThisTick = false;
        lightningStrikeCount = 0L;
        toxinEventCount = 0L;
        mutagenInfectionEventCount = 0L;
        gridReadCountForTest = 0;
        long seed = config.seed() == null ? 0L : config.seed();
        this.rng = new Random(seed);
    }

    /**
     * Plan 14-06 Task 1: pass-through accessor — every finalize* call produces
     * exactly one compost event via {@link DeathCleanupHooks#applyCompost}, so
     * the death count IS the compost event count.
     */
    public long getCompostEventCount() {
        return deathFinalizer.getDeathEventCount();
    }

    /**
     * Plan 14-06 Task 3b: rising-edge count of toxin events spawned since bean
     * creation (or last {@link #resetForTest}).
     */
    public long getToxinEventCount() {
        return toxinEventCount;
    }

    /**
     * Plan 14-06 Task 3b: monotonic count of mutagen infections initiated
     * since bean creation (or last {@link #resetForTest}).
     */
    public long getMutagenInfectionEventCount() {
        return mutagenInfectionEventCount;
    }

    /**
     * Plan 14-06 Task 3b: alias for {@link #lightningStrikeCount()} exposed
     * under the canonical {@code getXxxEventCount()} naming convention so all
     * four env-effect counters share a consistent accessor shape on the
     * phase-gate test.
     */
    public long getLightningStrikeEventCount() {
        return lightningStrikeCount;
    }

    /**
     * Run ONLY the env-owned tick phases for deterministic testing.
     *
     * <p><b>cycle-6 HIGH #4 CONSTRAINT:</b> this method calls
     * {@link #processEnvDeaths()} which routes CompositeMember env-deaths
     * through {@code SimulationEngine.handleMemberDeath()}
     * (SimulationEngine.java:665-703) which uses {@code ThreadLocalRandom}.
     * For deterministic runs, the caller MUST guarantee no composites are
     * registered during the run.
     * {@link com.paralife.engine.EnvironmentDeterminismTest} asserts
     * {@code compositeRegistry.getAll().isEmpty()} at driveRun start to enforce
     * this.
     *
     * <p>This is the honest boundary between "deterministic env engine" and
     * "the whole sim uses ThreadLocalRandom."
     */
    public void onTickEnvOnlyForTest(long tickNumber) {
        try {
            cellStatusCache.clear();
            entityStatusCache.clear();
            buffRegistry.expireBuffs(tickNumber);

            if (!config.enabled()) {
                envDamageAppliedThisTick = false;
                return;
            }

            // Toxin pipeline (Plan 14-02):
            spawnToxin(tickNumber);
            advanceToxin(tickNumber);
            resolveToxinCollisions(tickNumber);

            // Mutagen pipeline (Plan 14-03):
            spawnMutagen(tickNumber);
            advanceMutagen(tickNumber);
            resolveMutagenCollisions(tickNumber);
            tickBuffsAndInfections(tickNumber);

            // Lightning (Plan 14-04):
            spawnLightning(tickNumber);

            buildStatusCaches();

            // Env-death sweep — short-circuited when no env damage applied.
            processEnvDeaths();
        } catch (RuntimeException ex) {
            log.error("onTickEnvOnlyForTest failed at tick {}", tickNumber, ex);
        } finally {
            envDamageAppliedThisTick = false;
        }
    }

    /**
     * Grid-scale nutrient total. Sums {@link Cell#nutrientLevel} across every
     * cell.
     *
     * <p>cycle-4 action item #9 (Codex MEDIUM): compost (D-24/D-25 bumps on
     * death cell + 8 neighbors) and lightning (outer ring fertility boost)
     * both mutate nutrient levels. Fertility drift across supposedly-identical
     * deterministic runs is the PRIMARY invariant this harness exists to
     * protect.
     *
     * <p>cycle-6 LOW: uses a SINGLE {@link WorldGrid#snapshot()} call +
     * iteration over {@link WorldGrid.GridSnapshot} instead of 65k individual
     * {@code getCell} calls (each of which acquires a read lock). Cheap
     * end-of-run polish for the primary perf hot spot in the determinism
     * harness.
     *
     * @return sum of Cell.nutrientLevel across the whole grid (always &gt;= 0)
     */
    public int totalNutrients() {
        WorldGrid.GridSnapshot snap = worldGrid.snapshot();
        Cell[][] cells = snap.cells();
        int total = 0;
        for (int x = 0; x < snap.width(); x++) {
            for (int y = 0; y < snap.height(); y++) {
                total += cells[x][y].nutrientLevel();
            }
        }
        return total;
    }

    // ── Test-only helpers (package-private) ──────────────────────

    void markEnvDamageAppliedForTest() {
        markEnvDamageApplied();
    }

    void processEnvDeathsForTest() {
        processEnvDeaths();
    }

    void killParticleAtForTest(int x, int y) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof Particle p) {
            worldGrid.setEntity(x, y, p.withEnergy(0));
            markEnvDamageApplied();
        }
    }

    void killCompositeMemberAtForTest(int x, int y) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof CompositeMember cm) {
            worldGrid.setEntity(x, y, cm.withEnergy(0));
            markEnvDamageApplied();
        }
    }

    /**
     * Test helper — fire a single lightning strike at {@code (cx, cy)} while
     * bypassing the seasonal Poisson roll. Preserves the production
     * "attempted-strike" counter ordering — {@link #lightningStrikeCount} is
     * incremented BEFORE {@link #applyLightningAt} runs, matching
     * {@link #spawnLightning} exactly.
     */
    void applyLightningAtForTest(int cx, int cy) {
        lightningStrikeCount++;
        applyLightningAt(cx, cy, config.lightning());
    }

    Map<Position, Byte> cellStatusCacheView() {
        return Collections.unmodifiableMap(cellStatusCache);
    }

    Map<String, Byte> entityStatusCacheView() {
        return Collections.unmodifiableMap(entityStatusCache);
    }

    boolean envDamageAppliedThisTickForTest() {
        return envDamageAppliedThisTick;
    }

    void stampToxinIntensityForTest(Position pos, int intensity) {
        int x = Math.floorMod(pos.x(), worldGrid.getWidth());
        int y = Math.floorMod(pos.y(), worldGrid.getHeight());
        int before = toxinGrid[x][y] & 0xFF;
        int clamped = Math.clamp(intensity, 0, 255);
        toxinGrid[x][y] = (byte) clamped;
        if (before == 0 && clamped > 0) nonZeroToxinCellCount++;
        else if (before > 0 && clamped == 0) nonZeroToxinCellCount--;
    }

    /** Test helper — stamp mutagen strain at a cell (and reset the reinforcement tick). */
    void stampMutagenForTest(Position pos, int strain) {
        int x = Math.floorMod(pos.x(), worldGrid.getWidth());
        int y = Math.floorMod(pos.y(), worldGrid.getHeight());
        mutagenGrid[x][y] = (byte) Math.clamp(strain, 0, 255);
        mutagenLastReinforcedTick[x][y] = 0L;
    }

    /** Test helper — run resolveMutagenCollisions directly. */
    void resolveMutagenCollisionsForTest(long tickNumber) {
        resolveMutagenCollisions(tickNumber);
    }

    /** Test helper — run tickBuffsAndInfections directly. */
    void tickBuffsAndInfectionsForTest(long tickNumber) {
        tickBuffsAndInfections(tickNumber);
    }

    /** Test helper — force a new mutagen outbreak starting at the given origin. */
    void forceSpawnMutagenForTest(long tickNumber, Position origin, int strain, int lifetime) {
        int x = origin.x();
        int y = origin.y();
        mutagenGrid[x][y] = (byte) Math.clamp(strain, 1, 255);
        mutagenLastReinforcedTick[x][y] = tickNumber;
        activeMutagen = new MutagenEvent(tickNumber, origin, lifetime);
    }

    /** Test helper — expose active mutagen event. */
    MutagenEvent activeMutagenEvent() {
        return activeMutagen;
    }

    /** Test helper — expose the strain byte at a cell (unsigned). */
    int mutagenStrainAtForTest(Position pos) {
        return mutagenGrid[pos.x()][pos.y()] & 0xFF;
    }

    /** Test helper — expose last-reinforcement tick for zone-decay tests. */
    long mutagenLastReinforcedTickForTest(Position pos) {
        return mutagenLastReinforcedTick[pos.x()][pos.y()];
    }

    /** Test helper — manual set of last-reinforced tick (for zone-decay tests). */
    void setMutagenLastReinforcedTickForTest(Position pos, long tick) {
        mutagenLastReinforcedTick[pos.x()][pos.y()] = tick;
    }

    /** Test helper — perf counter exposed for structural assertion. */
    int gridReadCountForTest() {
        return gridReadCountForTest;
    }

    /** Test helper — run buildStatusCaches directly. */
    void buildStatusCachesForTest() {
        buildStatusCaches();
    }

    /** Test helper — synchronous resolveToxinCollisions for a single tick. */
    void resolveToxinCollisionsForTest(long tickNumber) {
        resolveToxinCollisions(tickNumber);
    }

    /** Test helper — run advanceToxin once. */
    void advanceToxinForTest(long tickNumber) {
        advanceToxin(tickNumber);
    }

    /** Test helper — run advanceMutagen once. */
    void advanceMutagenForTest(long tickNumber) {
        advanceMutagen(tickNumber);
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

    ToxinEvent activeToxinEvent() {
        return activeToxin;
    }

    int nonZeroToxinCellCountForTest() {
        return nonZeroToxinCellCount;
    }

    /**
     * Test helper — wipe toxin + mutagen grid + event state between tests
     * sharing a Spring context.
     */
    void resetToxinStateForTest() {
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                toxinGrid[x][y] = 0;
                toxinGridNext[x][y] = 0;
                mutagenGrid[x][y] = 0;
                mutagenGridNext[x][y] = 0;
                mutagenLastReinforcedTick[x][y] = 0L;
            }
        }
        activeToxin = null;
        activeMutagen = null;
        nonZeroToxinCellCount = 0;
        envDamageAppliedThisTick = false;
        cellStatusCache.clear();
        entityStatusCache.clear();
    }

    /** Test helper — wipe just mutagen state (use between tests within same context). */
    void resetMutagenStateForTest() {
        int w = worldGrid.getWidth();
        int h = worldGrid.getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                mutagenGrid[x][y] = 0;
                mutagenGridNext[x][y] = 0;
                mutagenLastReinforcedTick[x][y] = 0L;
            }
        }
        activeMutagen = null;
        envCleanupHooksBean.getInfections().clear();
        envCleanupHooksBean.getCureImmuneUntil().clear();
        synchronized (envCleanupHooksBean.getPendingGrants()) {
            envCleanupHooksBean.getPendingGrants().clear();
        }
    }

    public byte getCellStatus(Position pos) {
        Byte b = cellStatusCache.get(pos);
        return b == null ? (byte) 0 : b;
    }

    public byte getEntityStatus(String entityId) {
        Byte b = entityStatusCache.get(entityId);
        return b == null ? (byte) 0 : b;
    }
}
