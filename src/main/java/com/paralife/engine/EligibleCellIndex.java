package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase 19 SCALE-06 (D-01..D-06): O(1) sparse-set of cells eligible for bot
 * register/respawn placement. Replaces the 50-retry random-scan in
 * {@code WorldWebSocketHandler}.
 *
 * <p>Eligibility = (no occupant) AND (cell not FLAG_OVERCROWDED per
 * {@code cellStatusCache} bit 0) AND (placing here would NOT push any adjacent
 * occupied Moore neighbour over {@code overcrowdingThreshold}).
 * Maintained incrementally via {@link #notifyChanged(int, int)} on a 5×5
 * dirty bbox.
 *
 * <p><b>Lifecycle hook discipline (REVIEWS MEDIUM-1):</b> only STRUCTURAL grid
 * mutations call notifyChanged. Energy-only writes (applyDeltaToOccupant,
 * processEnergyDecay, processOvercrowding penalty, combat damage withEnergy,
 * toxin/mutagen damage) MUST NOT — they don't change occupancy.
 *
 * <p><b>Lock-order invariant (REVIEWS L1):</b> index-lock → grid-read-lock.
 * The index lock (a {@link ReentrantLock}) is acquired before
 * {@link WorldGrid#getCell} (which acquires the grid read lock). Tick handlers
 * must call {@code notifyChanged} AFTER the WorldGrid mutation returns, never
 * inside the write lock.
 *
 * <p><b>Why {@link ReentrantLock}, not {@code synchronized} (backlog 999.6):</b>
 * {@code notifyChanged} holds the index lock and then blocks on the grid
 * read lock. Under an intrinsic {@code synchronized} monitor, a virtual thread
 * that blocks while inside the monitor <em>pins its carrier</em> — so a burst of
 * concurrent server-side {@code cleanupBot → notifyChanged} calls (e.g. a mass
 * idle-timeout cascade at fleet teardown) starves the VT carrier pool and
 * deadlocks: the lock holder can never be rescheduled to finish, and every other
 * thread waiting on the index lock blocks forever. A {@code ReentrantLock} does
 * NOT pin — a VT blocked on {@code lock.lock()} or on the inner grid lock unmounts
 * cleanly — which lets the holder make progress and clears the cascade. This was
 * the 2026-05-03 teardown-hang class reproduced by the Phase 22.1 leak probe.
 *
 * <p><b>Init order (REVIEWS H2 + L2):</b> {@code @DependsOn("rockGenerator")}
 * forces this bean's @PostConstruct to run after RockGenerator's;
 * RockGenerator.initialize() is synchronous (verified pre-flight per L2).
 *
 * <p><b>Cell-status read (REVIEWS CONSENSUS-H4):</b> reads
 * {@link EnvironmentEngine#cellStatusCacheView()}, which returns a volatile
 * immutable {@code Map.copyOf} snapshot. Safe for concurrent WS-thread reads
 * with no risk of ConcurrentModificationException.
 *
 * <p>// PERF: REVIEWS MEDIUM-9 — {@code cache.get(new Position(x, y))} allocates a
 * Position per cell × 25 per notifyChanged call. Acceptable at current scale;
 * Phase 21 benchmark may revisit (option: packed-int cache key).
 */
@Component
@DependsOn("rockGenerator")
public class EligibleCellIndex {

    private static final Logger log = LoggerFactory.getLogger(EligibleCellIndex.class);
    private static final int DIRTY_BBOX_RADIUS = 2;

    private final WorldGrid worldGrid;
    /**
     * Setter-injected to break the construction cycle:
     * {@code EligibleCellIndex} → {@code EnvironmentEngine} → {@code DeathFinalizer}
     * → {@code setEligibleCellIndex} → {@code EligibleCellIndex} (already in creation).
     * Tests that construct {@code EligibleCellIndex} directly pass {@code environmentEngine}
     * via the package-private 3-arg constructor — Spring uses the 2-arg {@code @Autowired}
     * constructor plus this setter.
     */
    private EnvironmentEngine environmentEngine;
    private final SimulationConfig simulationConfig;

    private final int width;
    private final int height;
    /** Dense array: eligible cell linear indices, packed [0..size-1]. */
    private final int[] dense;
    /** Back-map: posInDense[linearIdx] = position of that index in dense[], or -1 if absent. */
    private final int[] posInDense;
    private int size = 0;

    /**
     * Index lock — replaces the former {@code synchronized(this)} intrinsic monitor.
     * A {@link ReentrantLock} so virtual threads blocking while holding it (on the
     * inner grid read lock) unmount rather than pin their carrier (backlog 999.6;
     * see class javadoc). Reentrant so {@link #rebuildForTest()} → {@link #initialize()}
     * nests without self-deadlock, exactly as the old monitor allowed.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Spring-used constructor. {@link EnvironmentEngine} is injected via
     * {@link #setEnvironmentEngine} after construction to break the circular dependency.
     */
    @Autowired
    public EligibleCellIndex(WorldGrid worldGrid, SimulationConfig simulationConfig) {
        this.worldGrid = worldGrid;
        this.environmentEngine = null; // filled by setter before @PostConstruct
        this.simulationConfig = simulationConfig;
        this.width = worldGrid.getWidth();
        this.height = worldGrid.getHeight();
        int total = width * height;
        this.dense = new int[total];
        this.posInDense = new int[total];
        Arrays.fill(posInDense, -1);
    }

    /**
     * Package-private constructor for unit tests that supply the environment engine
     * directly (avoids Spring context; tests in {@code com.paralife.engine} package).
     */
    EligibleCellIndex(WorldGrid worldGrid,
                      EnvironmentEngine environmentEngine,
                      SimulationConfig simulationConfig) {
        this.worldGrid = worldGrid;
        this.environmentEngine = environmentEngine;
        this.simulationConfig = simulationConfig;
        this.width = worldGrid.getWidth();
        this.height = worldGrid.getHeight();
        int total = width * height;
        this.dense = new int[total];
        this.posInDense = new int[total];
        Arrays.fill(posInDense, -1);
    }

    @Autowired
    public void setEnvironmentEngine(@Lazy EnvironmentEngine environmentEngine) {
        this.environmentEngine = environmentEngine;
    }

    @PostConstruct
    public void initialize() {
        // Phase 19.5 M4: clear posInDense + size at the top so re-init starts
        // clean rather than double-counting cells. rebuildForTest() does this
        // already; making initialize() self-contained removes the latent ordering
        // requirement and lets any caller (Spring @PostConstruct or test) invoke
        // it safely. dense[] does not need explicit clearing — addInternal
        // overwrites entries [0..size-1] starting from size=0.
        // Locked: M4 hardens against test-misuse footguns where any future
        // call path invokes initialize() outside the index lock concurrent
        // with reads. @PostConstruct + lock is honoured by Spring;
        // rebuildForTest already holds the lock (re-entrant — free).
        lock.lock();
        try {
            Arrays.fill(posInDense, -1);
            size = 0;
            Map<Position, Byte> snap = environmentEngine != null
                    ? environmentEngine.cellStatusCacheView() : Map.of();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (evaluateEligibility(x, y, snap)) addInternal(x, y);
                }
            }
            log.info("EligibleCellIndex initialised: {} eligible cells of {} total", size, width * height);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Linearisation: {@code x * height + y}.
     * REVIEWS R2-13 — see EligibleCellIndexRectangularTest for parity proof on non-square grids.
     */
    int toIndex(int x, int y) {
        return x * height + y;
    }

    Position fromIndex(int idx) {
        return new Position(idx / height, idx % height);
    }

    /** Add cell (x,y) to the eligible set. No-op if already present. Thread-safe. */
    public void add(int x, int y) {
        lock.lock();
        try {
            addInternal(x, y);
        } finally {
            lock.unlock();
        }
    }

    private void addInternal(int x, int y) {
        int idx = toIndex(x, y);
        if (posInDense[idx] >= 0) return; // already present
        dense[size] = idx;
        posInDense[idx] = size;
        size++;
    }

    /** Remove cell (x,y) from the eligible set. No-op if absent. Thread-safe. */
    public void remove(int x, int y) {
        lock.lock();
        try {
            removeInternal(x, y);
        } finally {
            lock.unlock();
        }
    }

    private void removeInternal(int x, int y) {
        int idx = toIndex(x, y);
        int pos = posInDense[idx];
        if (pos < 0) return; // not present
        // Swap-and-pop: move last element into this slot
        int last = dense[size - 1];
        dense[pos] = last;
        posInDense[last] = pos;
        posInDense[idx] = -1;
        size--;
    }

    /**
     * O(1) uniform sample. Returns {@code null} iff eligible set is empty (→ GRID_FULL).
     * Uses exactly 1 call to {@code rng.nextInt(size)} per successful sample.
     */
    public Position sample(Random rng) {
        lock.lock();
        try {
            if (size == 0) return null;
            int idx = dense[rng.nextInt(size)];
            return fromIndex(idx);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the number of cells currently in the eligible set. */
    public int eligibleCount() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Re-evaluate eligibility for the 5×5 Moore bbox around (px, py). Toroidal
     * wrap via {@code Math.floorMod}. Cell-status cache is read exactly once per
     * call and hoisted — REVIEWS MEDIUM-1 / O3 (single cellStatusCacheView() per notifyChanged).
     *
     * <p>Lock order: acquires the index lock ({@link ReentrantLock}) then
     * internally calls {@code worldGrid.getCell} (grid read lock). This order
     * is index-lock → grid-read-lock and must not be inverted.
     */
    public void notifyChanged(int px, int py) {
        lock.lock();
        try {
            Map<Position, Byte> hoistedCache = environmentEngine != null
                    ? environmentEngine.cellStatusCacheView() : Map.of();
            for (int dy = -DIRTY_BBOX_RADIUS; dy <= DIRTY_BBOX_RADIUS; dy++) {
                for (int dx = -DIRTY_BBOX_RADIUS; dx <= DIRTY_BBOX_RADIUS; dx++) {
                    int cx = Math.floorMod(px + dx, width);
                    int cy = Math.floorMod(py + dy, height);
                    if (evaluateEligibility(cx, cy, hoistedCache)) {
                        addInternal(cx, cy);
                    } else {
                        removeInternal(cx, cy);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // Phase 19.5 multi-review L-1: cellStatusCache parameter is intentionally unused
    // in the current predicate body (constraints 1-3 read Cell.flags directly per
    // REVIEWS H1; bit 0 of cellStatusCache is per-bot redacted, see CLAUDE.md D-40).
    // The parameter is retained — not deleted — because callers already construct it
    // and a future env-effect constraint (e.g. "no placement in active mutagen zone")
    // will need it. Removing then re-adding the param is the kind of churn this
    // comment exists to prevent. Reviewed and dismissed in P19.5 multi-review pass 2.
    @SuppressWarnings("unused")
    private boolean evaluateEligibility(int x, int y, Map<Position, Byte> cellStatusCache) {
        // Constraint 1: cell must be unoccupied.
        Cell cell = worldGrid.getCell(x, y);
        if (cell.hasOccupant()) return false;

        // Constraint 2: cell must not be flagged OVERCROWDED.
        // REVIEWS H1 (Phase 19.5): read Cell.flags directly — bit 0 of cellStatusCache
        // is deliberately redacted (per CLAUDE.md D-40, recomputed per-bot in
        // TickBroadcaster.cellToView). Cell.FLAG_OVERCROWDED is the authoritative
        // server-global per-cell flag set by SimulationEngine.processOvercrowding.
        if (cell.hasFlag(Cell.FLAG_OVERCROWDED)) return false;

        // Constraint 3: placing here must not push any adjacent occupied cell over threshold.
        int threshold = simulationConfig.overcrowdingThreshold();
        for (Position nPos : worldGrid.getNeighbors(x, y)) {
            Cell nCell = worldGrid.getCell(nPos.x(), nPos.y());
            Entity occ = nCell.occupant();
            // Only Particle and BondedPair count as live occupants for overcrowding.
            if (!(occ instanceof Entity.Particle) && !(occ instanceof Entity.BondedPair)) continue;
            int neighborCount = countOccupiedMooreNeighbours(nPos.x(), nPos.y());
            if (neighborCount == threshold - 1) return false;
        }
        return true;
    }

    private int countOccupiedMooreNeighbours(int x, int y) {
        int count = 0;
        for (Position nPos : worldGrid.getNeighbors(x, y)) {
            Entity occ = worldGrid.getCell(nPos.x(), nPos.y()).occupant();
            if (occ instanceof Entity.Particle || occ instanceof Entity.BondedPair) count++;
        }
        return count;
    }

    /**
     * Test helper — wipe the index and rebuild from current grid state.
     * Mirrors the @PostConstruct init; call after {@code worldGrid.clear()} in tests.
     */
    public void rebuildForTest() {
        // Phase 19.5 M4: initialize() now self-clears posInDense + size at the top,
        // so this is a thin alias. Kept as a public test seam for clarity at call sites.
        // (initialize() takes the reentrant index lock itself; nesting is free.)
        initialize();
    }
}
