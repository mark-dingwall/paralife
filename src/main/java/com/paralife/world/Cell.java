package com.paralife.world;

/**
 * A single cell in the world grid.
 *
 * Separates positional/environmental state from occupant state:
 * <ul>
 *   <li>{@code occupant} — the Entity in this cell, or null if empty</li>
 *   <li>{@code flags} — bitfield for environmental effects (fire, disease, etc.)</li>
 *   <li>{@code nutrientLevel} — ambient nutrient level of the terrain itself</li>
 * </ul>
 *
 * Cells are immutable — mutation produces a new Cell via {@code with*} methods.
 */
public record Cell(Entity occupant, int flags, int nutrientLevel) {

    // ── Flag constants (bitfield) ──────────────────────────────────
    public static final int FLAG_NONE = 0;
    public static final int FLAG_OVERCROWDED = 1;
    public static final int FLAG_STARVING = 2;

    /** An empty cell with no flags or nutrients. */
    public static final Cell EMPTY = new Cell(null, FLAG_NONE, 0);

    public Cell {
        if (nutrientLevel < 0) throw new IllegalArgumentException("Nutrient level cannot be negative: " + nutrientLevel);
    }

    // ── Queries ────────────────────────────────────────────────────

    public boolean isEmpty() {
        return occupant == null;
    }

    public boolean hasOccupant() {
        return occupant != null;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    // ── Immutable mutation ─────────────────────────────────────────

    public Cell withOccupant(Entity entity) {
        return new Cell(entity, flags, nutrientLevel);
    }

    public Cell cleared() {
        return new Cell(null, flags, nutrientLevel);
    }

    public Cell withFlags(int newFlags) {
        return new Cell(occupant, newFlags, nutrientLevel);
    }

    public Cell withAddedFlag(int flag) {
        return new Cell(occupant, flags | flag, nutrientLevel);
    }

    public Cell withRemovedFlag(int flag) {
        return new Cell(occupant, flags & ~flag, nutrientLevel);
    }

    public Cell withNutrientLevel(int level) {
        return new Cell(occupant, flags, level);
    }
}
