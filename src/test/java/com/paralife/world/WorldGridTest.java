package com.paralife.world;

import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Rock;
import com.paralife.world.Entity.Nutrient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        Cell cell = grid.getCell(0, 0);
        assertThat(cell.isEmpty()).isTrue();
    }

    @Test
    void setEntityAndRetrieve() {
        Particle p = Particle.spawn("p1", ParticleType.CATALYST);
        grid.setEntity(3, 4, p);
        Cell cell = grid.getCell(3, 4);
        assertThat(cell.hasOccupant()).isTrue();
        assertThat(cell.occupant()).isEqualTo(p);
    }

    @Test
    void clearEntity() {
        grid.setEntity(3, 4, Particle.spawn("p1", ParticleType.SPORE));
        grid.clearEntity(3, 4);
        assertThat(grid.getCell(3, 4).isEmpty()).isTrue();
    }

    @Test
    void setCellReplacesEntireCell() {
        Cell cell = new Cell(new Rock("r1"), 0, 5);
        grid.setCell(2, 2, cell);
        Cell retrieved = grid.getCell(2, 2);
        assertThat(retrieved.occupant()).isInstanceOf(Rock.class);
        assertThat(retrieved.nutrientLevel()).isEqualTo(5);
    }

    @Test
    void setEntityPreservesEnvironment() {
        // Set a cell with nutrient level
        grid.setCell(1, 1, Cell.EMPTY.withNutrientLevel(7));
        // Place an entity on it
        grid.setEntity(1, 1, Particle.spawn("p1", ParticleType.MEMBRANE));
        Cell cell = grid.getCell(1, 1);
        assertThat(cell.hasOccupant()).isTrue();
        assertThat(cell.nutrientLevel()).isEqualTo(7); // preserved
    }

    @Test
    void getCellWrapsCoordinates() {
        grid.setEntity(0, 0, new Rock("wrap-test"));
        // (10, 10) wraps to (0, 0) on a 10x10 grid
        assertThat(grid.getCell(10, 10).occupant()).isInstanceOf(Rock.class);
        assertThat(((Rock) grid.getCell(10, 10).occupant()).id()).isEqualTo("wrap-test");
        // (-10, -10) also wraps to (0, 0)
        assertThat(grid.getCell(-10, -10).occupant()).isNotNull();
    }

    @Test
    void setEntityWrapsCoordinates() {
        grid.setEntity(-1, -1, new Rock("negative-wrap"));
        // -1 mod 10 = 9
        Cell cell = grid.getCell(9, 9);
        assertThat(cell.hasOccupant()).isTrue();
        assertThat(cell.occupant().id()).isEqualTo("negative-wrap");
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
        Particle before = Particle.spawn("before", ParticleType.CATALYST);
        grid.setEntity(1, 1, before);
        WorldGrid.GridSnapshot snap = grid.snapshot();

        // Mutate the live grid after snapshot
        grid.setEntity(1, 1, Particle.spawn("after", ParticleType.SPORE));
        grid.setEntity(2, 2, new Rock("new-entity"));

        // Snapshot should reflect state at time of capture
        assertThat(snap.getCell(1, 1).occupant()).isEqualTo(before);
        assertThat(snap.getCell(2, 2).isEmpty()).isTrue();
    }

    @Test
    void snapshotEntityCount() {
        grid.setEntity(0, 0, Particle.spawn("a", ParticleType.CATALYST));
        grid.setEntity(1, 1, Particle.spawn("b", ParticleType.SPORE));
        grid.setEntity(2, 2, new Rock("c"));
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
        grid.setEntity(5, 5, Particle.spawn("concurrent", ParticleType.MEMBRANE));

        // Launch multiple virtual threads reading simultaneously
        var threads = new Thread[100];
        Cell[] results = new Cell[100];
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
        for (Cell r : results) {
            assertThat(r.hasOccupant()).isTrue();
            assertThat(r.occupant().id()).isEqualTo("concurrent");
        }
    }

    @Test
    void largeGridWorks() {
        WorldGrid large = new WorldGrid(new GridConfig(256, 256));
        large.setEntity(255, 255, new Rock("corner"));
        assertThat(large.getCell(255, 255).occupant()).isInstanceOf(Rock.class);
        assertThat(large.getNeighbors(255, 255)).hasSize(8);
        // Neighbor wrapping: (256,256) → (0,0)
        assertThat(large.getNeighbors(255, 255)).contains(new Position(0, 0));
    }

    @Test
    void differentEntityTypesCoexist() {
        grid.setEntity(0, 0, Particle.spawn("p1", ParticleType.CATALYST));
        grid.setEntity(1, 0, new Rock("r1"));
        grid.setEntity(2, 0, Nutrient.spawn("n1"));

        assertThat(grid.getCell(0, 0).occupant()).isInstanceOf(Particle.class);
        assertThat(grid.getCell(1, 0).occupant()).isInstanceOf(Rock.class);
        assertThat(grid.getCell(2, 0).occupant()).isInstanceOf(Nutrient.class);
    }
}
