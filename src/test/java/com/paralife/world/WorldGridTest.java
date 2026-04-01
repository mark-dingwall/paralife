package com.paralife.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorldGridTest {

    private static final int WIDTH = 10;
    private static final int HEIGHT = 10;
    private WorldGrid grid;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
    }

    @Test
    void dimensionsMatchConfig() {
        assertThat(grid.getWidth()).isEqualTo(WIDTH);
        assertThat(grid.getHeight()).isEqualTo(HEIGHT);
    }

    @Test
    void emptyCellReturnsEmpty() {
        assertThat(grid.getCell(0, 0)).isEmpty();
    }

    @Test
    void setCellAndRetrieve() {
        grid.setCell(3, 4, "entity-1");
        assertThat(grid.getCell(3, 4)).contains("entity-1");
    }

    @Test
    void clearCellWithNull() {
        grid.setCell(3, 4, "entity-1");
        grid.setCell(3, 4, null);
        assertThat(grid.getCell(3, 4)).isEmpty();
    }

    @Test
    void getCellWrapsCoordinates() {
        grid.setCell(0, 0, "wrap-test");
        // (10, 10) wraps to (0, 0) on a 10x10 grid
        assertThat(grid.getCell(10, 10)).contains("wrap-test");
        // (-10, -10) also wraps to (0, 0)
        assertThat(grid.getCell(-10, -10)).contains("wrap-test");
    }

    @Test
    void setCellWrapsCoordinates() {
        grid.setCell(-1, -1, "negative-wrap");
        // -1 mod 10 = 9
        assertThat(grid.getCell(9, 9)).contains("negative-wrap");
    }

    @Test
    void getNeighborsReturns8() {
        List<Position> neighbors = grid.getNeighbors(5, 5);
        assertThat(neighbors).hasSize(8);
    }

    @Test
    void getNeighborsWrapsAtEdges() {
        List<Position> neighbors = grid.getNeighbors(0, 0);
        assertThat(neighbors).hasSize(8);
        assertThat(neighbors).contains(new Position(9, 9)); // wraps both axes
    }

    @Test
    void snapshotIsDeepCopy() {
        grid.setCell(1, 1, "before-snapshot");
        WorldGrid.GridSnapshot snap = grid.snapshot();

        // Mutate the live grid after snapshot
        grid.setCell(1, 1, "after-snapshot");
        grid.setCell(2, 2, "new-entity");

        // Snapshot should reflect state at time of capture
        assertThat(snap.getCell(1, 1)).contains("before-snapshot");
        assertThat(snap.getCell(2, 2)).isEmpty();
    }

    @Test
    void snapshotEntityCount() {
        grid.setCell(0, 0, "a");
        grid.setCell(1, 1, "b");
        grid.setCell(2, 2, "c");
        WorldGrid.GridSnapshot snap = grid.snapshot();
        assertThat(snap.entityCount()).isEqualTo(3);
    }

    @Test
    void emptyGridSnapshotHasZeroEntities() {
        WorldGrid.GridSnapshot snap = grid.snapshot();
        assertThat(snap.entityCount()).isEqualTo(0);
    }

    @Test
    void snapshotDimensionsMatchGrid() {
        WorldGrid.GridSnapshot snap = grid.snapshot();
        assertThat(snap.width()).isEqualTo(WIDTH);
        assertThat(snap.height()).isEqualTo(HEIGHT);
    }

    @Test
    void concurrentReadsDontBlock() throws Exception {
        grid.setCell(5, 5, "concurrent");

        // Launch multiple virtual threads reading simultaneously
        var threads = new Thread[100];
        @SuppressWarnings("unchecked")
        Optional<String>[] results = new Optional[100];
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            threads[i] = Thread.startVirtualThread(() -> {
                results[idx] = grid.getCell(5, 5);
            });
        }
        for (Thread t : threads) {
            t.join();
        }

        // All reads should succeed
        for (Optional<String> r : results) {
            assertThat(r).contains("concurrent");
        }
    }

    @Test
    void largeGridWorks() {
        WorldGrid large = new WorldGrid(new GridConfig(256, 256));
        large.setCell(255, 255, "corner");
        assertThat(large.getCell(255, 255)).contains("corner");
        assertThat(large.getNeighbors(255, 255)).hasSize(8);
        // Neighbor wrapping: (256,256) → (0,0)
        assertThat(large.getNeighbors(255, 255)).contains(new Position(0, 0));
    }
}
