package com.paralife.world;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A 2D toroidal grid that stores entity IDs (or null for empty cells).
 * Thread-safe via read-write lock — many readers, exclusive writer.
 */
@Component
public class WorldGrid {

    private final int width;
    private final int height;
    private final String[][] cells; // entity ID or null
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public WorldGrid(GridConfig config) {
        this.width = config.width();
        this.height = config.height();
        this.cells = new String[width][height];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Get the entity ID at a position. Returns empty if cell is unoccupied.
     * Coordinates are wrapped toroidally.
     */
    public Optional<String> getCell(int x, int y) {
        Position pos = Position.wrap(x, y, width, height);
        lock.readLock().lock();
        try {
            return Optional.ofNullable(cells[pos.x()][pos.y()]);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Place an entity at a position. Pass null to clear.
     * Coordinates are wrapped toroidally.
     */
    public void setCell(int x, int y, String entityId) {
        Position pos = Position.wrap(x, y, width, height);
        lock.writeLock().lock();
        try {
            cells[pos.x()][pos.y()] = entityId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the 8 Moore neighbors of a position on the toroidal grid.
     */
    public List<Position> getNeighbors(int x, int y) {
        Position pos = Position.wrap(x, y, width, height);
        return pos.neighbors(width, height);
    }

    /**
     * Returns an immutable snapshot of the entire grid state.
     * The snapshot is a deep copy — modifications to the grid after snapshot creation
     * do not affect the snapshot.
     */
    public GridSnapshot snapshot() {
        lock.readLock().lock();
        try {
            String[][] copy = new String[width][height];
            for (int i = 0; i < width; i++) {
                copy[i] = Arrays.copyOf(cells[i], height);
            }
            return new GridSnapshot(width, height, copy);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Immutable snapshot of the grid at a point in time.
     */
    public record GridSnapshot(int width, int height, String[][] cells) {

        public Optional<String> getCell(int x, int y) {
            Position pos = Position.wrap(x, y, width, height);
            return Optional.ofNullable(cells[pos.x()][pos.y()]);
        }

        /**
         * Count of occupied cells.
         */
        public int entityCount() {
            int count = 0;
            for (String[] col : cells) {
                for (String cell : col) {
                    if (cell != null) count++;
                }
            }
            return count;
        }
    }
}
