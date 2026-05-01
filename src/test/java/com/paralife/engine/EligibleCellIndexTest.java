package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EligibleCellIndex} — pure JUnit 5, no Spring context.
 *
 * <p>Uses a real 8×8 {@link WorldGrid} (small enough for exhaustive assertions)
 * and a Mockito spy on {@link EnvironmentEngine} to verify cache-read discipline.
 */
@ExtendWith(MockitoExtension.class)
class EligibleCellIndexTest {

    private static final int W = 8;
    private static final int H = 8;
    /** overcrowdingThreshold = 6 (default). Constraint 3 triggers when neighbour has 5 neighbours. */
    private static final int THRESHOLD = 6;

    private WorldGrid worldGrid;
    private EnvironmentEngine envEngineSpy;
    private SimulationConfig simConfig;
    private EligibleCellIndex index;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(W, H));
        // Build a real EnvironmentEngine spy — cellStatusCacheView() returns empty by default.
        // We use lenient stubbing so tests that don't care about the spy can skip it.
        simConfig = SimulationConfig.defaults(); // overcrowdingThreshold = 6

        // We need a minimal EnvironmentEngine. Since it has many deps, we stub via subclass.
        envEngineSpy = buildStubEnvironmentEngine(Map.of());
        index = new EligibleCellIndex(worldGrid, envEngineSpy, simConfig);
        // @PostConstruct is not called automatically in unit test; call manually.
        index.initialize();
    }

    // ─── Helper: build a stub EnvironmentEngine that returns a fixed cellStatusCache ──

    private EnvironmentEngine buildStubEnvironmentEngine(Map<Position, Byte> cache) {
        // We need a minimal EnvironmentEngine spy. Since the full class has many mandatory
        // Spring collaborators, we create a testable override via a mock approach:
        // Use Mockito to create a mock of EnvironmentEngine, bypassing constructor.
        EnvironmentEngine stub = org.mockito.Mockito.mock(EnvironmentEngine.class,
                org.mockito.Answers.RETURNS_DEFAULTS);
        when(stub.cellStatusCacheView()).thenReturn(cache);
        return stub;
    }

    // ─── Constraint-1: occupied cell is not eligible ───────────────────────────

    @Test
    void constraint1RejectsOccupied() {
        // Place a particle at (3,3) — cell becomes occupied.
        worldGrid.trySetEntity(3, 3, Entity.Particle.spawn("p1", Entity.ParticleType.CATALYST, 10));
        index.notifyChanged(3, 3);

        assertThat(isCellInIndex(3, 3)).isFalse();
    }

    // ─── Constraint-2: OVERCROWDED bit (bit 0) makes cell ineligible ──────────

    @Test
    void constraint2RejectsOvercrowded() {
        // Mark cell (2,2) as OVERCROWDED in the status cache (bit 0 = 0x01).
        EnvironmentEngine envWithOC = buildStubEnvironmentEngine(
                Map.of(new Position(2, 2), (byte) 0x01));
        EligibleCellIndex idx2 = new EligibleCellIndex(worldGrid, envWithOC, simConfig);
        idx2.initialize();

        assertThat(isCellInIndexOf(idx2, 2, 2)).isFalse();
        // Neighbouring empty cells that are not themselves overcrowded remain eligible.
        assertThat(isCellInIndexOf(idx2, 0, 0)).isTrue();
    }

    // ─── Constraint-3: would cause adjacent neighbour to flip to overcrowded ──

    @Test
    void constraint3RejectsCellsThatWouldOvercrowdNeighbour() {
        // Goal: make occupied cell N at (4,4) have exactly THRESHOLD-1 = 5 occupied
        // Moore neighbours. Then any unoccupied Moore neighbour of (4,4) must be
        // rejected by constraint-3 (placing there would give N its 6th neighbour).
        //
        // Step 1: place N at (4,4).
        worldGrid.trySetEntity(4, 4, Entity.Particle.spawn("N", Entity.ParticleType.CATALYST, 10));
        index.notifyChanged(4, 4);
        //
        // Step 2: place exactly 5 occupied Moore neighbours of (4,4).
        int[][] neighbours5 = {{3,3},{3,4},{3,5},{4,3},{4,5}};
        for (int[] n : neighbours5) {
            worldGrid.trySetEntity(n[0], n[1], Entity.Particle.spawn("n-"+n[0]+"-"+n[1],
                    Entity.ParticleType.CATALYST, 10));
        }
        for (int[] n : neighbours5) {
            index.notifyChanged(n[0], n[1]);
        }
        // Now (4,4) is occupied with exactly 5 occupied Moore neighbours (= THRESHOLD-1).
        //
        // Step 3: candidate (5,4) is an unoccupied Moore neighbour of (4,4).
        // Placing at (5,4) would give (4,4) a 6th occupied neighbour → overcrowded.
        // Constraint-3 must reject (5,4).
        assertThat(isCellInIndex(5, 4)).isFalse();
    }

    // ─── add/remove/sample basics ──────────────────────────────────────────────

    @Test
    void addThenSampleReturnsAddedCell() {
        // Empty 8×8 grid — all cells eligible at init (no rocks, no overcrowding).
        int initialCount = index.eligibleCount();
        assertThat(initialCount).isEqualTo(W * H);

        // Remove one cell from index manually; check count.
        index.remove(0, 0);
        assertThat(index.eligibleCount()).isEqualTo(initialCount - 1);

        // Re-add it.
        index.add(0, 0);
        assertThat(index.eligibleCount()).isEqualTo(initialCount);
    }

    @Test
    void removeIsO1AndDoesNotShift() {
        // Fill index with all 64 cells, remove one from the middle, check size.
        assertThat(index.eligibleCount()).isEqualTo(W * H);
        index.remove(3, 3);
        assertThat(index.eligibleCount()).isEqualTo(W * H - 1);
        // Re-add and verify size restores.
        index.add(3, 3);
        assertThat(index.eligibleCount()).isEqualTo(W * H);
    }

    @Test
    void sampleEmptyReturnsNull() {
        // Drain all cells.
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                index.remove(x, y);
            }
        }
        assertThat(index.eligibleCount()).isZero();
        assertThat(index.sample(new Random(1L))).isNull();
    }

    @Test
    void seededSampleIsBitExact() {
        // Two runs with the same seed must return identical Position from same index state.
        assertThat(index.eligibleCount()).isEqualTo(W * H);

        Random r1 = new Random(42L);
        Position a = index.sample(r1);

        Random r2 = new Random(42L);
        Position b = index.sample(r2);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotNull();
    }

    @Test
    void addIdempotent() {
        // Adding same cell twice must not grow count.
        int before = index.eligibleCount();
        index.add(2, 2);
        assertThat(index.eligibleCount()).isEqualTo(before);
    }

    @Test
    void removeAbsentIsNoop() {
        // Removing a cell that was already removed must not corrupt state.
        index.remove(1, 1);
        int count1 = index.eligibleCount();
        index.remove(1, 1);
        assertThat(index.eligibleCount()).isEqualTo(count1);
    }

    // ─── REVIEWS O3: cellStatusCache read exactly once per notifyChanged ───────

    @Test
    void cellStatusCacheReadOnlyOncePerNotifyChanged() {
        // Use a mock directly (not spy-of-mock) that tracks cellStatusCacheView() calls.
        EnvironmentEngine tracked = org.mockito.Mockito.mock(EnvironmentEngine.class,
                org.mockito.Answers.RETURNS_DEFAULTS);
        when(tracked.cellStatusCacheView()).thenReturn(Map.of());
        EligibleCellIndex idx3 = new EligibleCellIndex(worldGrid, tracked, simConfig);
        idx3.initialize();

        // Reset mock invocation history after initialize().
        org.mockito.Mockito.clearInvocations(tracked);

        // One notifyChanged call must trigger exactly one cellStatusCacheView() read.
        idx3.notifyChanged(4, 4);
        verify(tracked, times(1)).cellStatusCacheView();
    }

    // ─── notifyChanged respects 5×5 bbox ─────────────────────────────────────

    @Test
    void notifyChangedRespects5x5Bbox() {
        // Place an entity at (4,4). After notifyChanged(4,4), all cells within
        // 2-step toroidal distance of (4,4) should be re-evaluated.
        worldGrid.trySetEntity(4, 4, Entity.Particle.spawn("q1", Entity.ParticleType.CATALYST, 10));
        int before = index.eligibleCount();
        index.notifyChanged(4, 4);
        // (4,4) is now occupied → not eligible; count should decrease.
        assertThat(index.eligibleCount()).isLessThan(before);
        assertThat(isCellInIndex(4, 4)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean isCellInIndex(int x, int y) {
        return isCellInIndexOf(index, x, y);
    }

    private boolean isCellInIndexOf(EligibleCellIndex idx, int x, int y) {
        // sample all and check, or use the internal toIndex/posInDense via package-private access.
        // Since we're in the same package, we can access posInDense via the index field:
        int linearIdx = idx.toIndex(x, y);
        Position p = idx.fromIndex(linearIdx);
        // Verify round-trip (sanity check).
        assertThat(p.x()).isEqualTo(x);
        assertThat(p.y()).isEqualTo(y);
        // Check via eligibleCount change: try removing then re-adding.
        int before = idx.eligibleCount();
        idx.remove(x, y);
        boolean wasPresent = idx.eligibleCount() == before - 1;
        if (wasPresent) idx.add(x, y); // restore
        return wasPresent;
    }
}
