package com.paralife.world;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A 2D toroidal grid of {@link Cell}s.
 * Thread-safe via read-write lock — many readers, exclusive writer.
 */
@Component
public class WorldGrid {

    private final int width;
    private final int height;
    private final Cell[][] cells;
    // Non-fair RRWL: per-cell locking means ~262K acquisitions/tick at 256×256, but these
    // are uncontended reads on a single-threaded tick pipeline — sub-ms in practice.
    // Coarser locking (per-phase or striped) is a future optimization for 1000+ bots.
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public WorldGrid(GridConfig config) {
        this.width = config.width();
        this.height = config.height();
        this.cells = new Cell[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                cells[x][y] = Cell.EMPTY;
            }
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Get the cell at a position. Coordinates are wrapped toroidally.
     */
    public Cell getCell(int x, int y) {
        Position pos = Position.wrap(x, y, width, height);
        lock.readLock().lock();
        try {
            return cells[pos.x()][pos.y()];
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Attempt to place an entity at a position only if the cell is currently empty.
     * Returns true if placed, false if the cell was occupied.
     */
    public boolean trySetEntity(int x, int y, Entity entity) {
        Position pos = Position.wrap(x, y, width, height);
        lock.writeLock().lock();
        try {
            if (cells[pos.x()][pos.y()].hasOccupant()) {
                return false;
            }
            cells[pos.x()][pos.y()] = cells[pos.x()][pos.y()].withOccupant(entity);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Place an entity at a position, replacing any existing occupant.
     * Preserves the cell's environmental state (flags, nutrient level).
     * Coordinates are wrapped toroidally.
     */
    public void setEntity(int x, int y, Entity entity) {
        Position pos = Position.wrap(x, y, width, height);
        lock.writeLock().lock();
        try {
            cells[pos.x()][pos.y()] = cells[pos.x()][pos.y()].withOccupant(entity);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Set the entire cell at a position (occupant + environment).
     * Coordinates are wrapped toroidally.
     */
    public void setCell(int x, int y, Cell cell) {
        Position pos = Position.wrap(x, y, width, height);
        lock.writeLock().lock();
        try {
            cells[pos.x()][pos.y()] = cell;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clear the occupant at a position, preserving environmental state.
     */
    public void clearEntity(int x, int y) {
        Position pos = Position.wrap(x, y, width, height);
        lock.writeLock().lock();
        try {
            cells[pos.x()][pos.y()] = cells[pos.x()][pos.y()].cleared();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reset the entire grid to empty cells — wipes both occupants and
     * environmental state (nutrientLevel, flags). Use {@link #clearOccupants()}
     * if you want to reset entities but keep soil fertility / flags intact.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    cells[x][y] = Cell.EMPTY;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Remove every occupant but preserve environmental state
     * ({@code nutrientLevel}, {@code flags}) on each cell. Counterpart to
     * {@link #clear()} for tests that reset entities between iterations
     * without disturbing soil fertility.
     */
    public void clearOccupants() {
        lock.writeLock().lock();
        try {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    cells[x][y] = cells[x][y].cleared();
                }
            }
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
     * The snapshot is a deep copy — modifications after creation don't affect it.
     */
    public GridSnapshot snapshot() {
        lock.readLock().lock();
        try {
            Cell[][] copy = new Cell[width][height];
            for (int x = 0; x < width; x++) {
                // Cell is a record (immutable) so shallow copy of the array is sufficient
                System.arraycopy(cells[x], 0, copy[x], 0, height);
            }
            return new GridSnapshot(width, height, copy);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Count live non-terrain occupants on the grid.
     *
     * <p>Used by WebSocket registration back-pressure. Rocks and nutrients do
     * not count; zero-energy occupants that are awaiting cleanup do not count.
     */
    public int livingEntityCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (Cell[] col : cells) {
                for (Cell cell : col) {
                    Entity occupant = cell.occupant();
                    if (occupant instanceof Entity.Particle particle && particle.isAlive()) {
                        count++;
                    } else if (occupant instanceof Entity.BondedPair pair && pair.energy() > 0) {
                        count++;
                    } else if (occupant instanceof Entity.CompositeMember member && member.isAlive()) {
                        count++;
                    }
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Immutable snapshot of the grid at a point in time.
     */
    public record GridSnapshot(int width, int height, Cell[][] cells) {

        public Cell getCell(int x, int y) {
            Position pos = Position.wrap(x, y, width, height);
            return cells[pos.x()][pos.y()];
        }

        /** Count of occupied cells (cells with a non-null occupant). */
        public int entityCount() {
            int count = 0;
            for (Cell[] col : cells) {
                for (Cell cell : col) {
                    if (cell.hasOccupant()) count++;
                }
            }
            return count;
        }
    }
}
