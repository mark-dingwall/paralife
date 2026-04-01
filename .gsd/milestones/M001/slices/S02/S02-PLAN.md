# S02: World Grid Model

**Goal:** Toroidal 2D grid with configurable dimensions, neighbor queries, and snapshot capability.
**Demo:** Unit tests prove wrapping arithmetic, neighbor queries at edges, and grid snapshot serialization.

## Must-Haves
- `WorldGrid` created from configurable width/height (default 256x256)
- `Position` record with toroidal arithmetic (wraps at edges)
- `getNeighbors(x, y)` returns exactly 8 neighbors (Moore neighborhood), correct at edges/corners
- `getCell(x, y)` / `setCell(x, y, entity)` for cell access
- `snapshot()` returns immutable view of entire grid state
- All edge cases tested: corners, edges, center, wrapping in both directions

## Tasks

- [ ] **T01: Position record and grid core**
  Create Position value record with toroidal math, WorldGrid with cell access and neighbor queries.

- [ ] **T02: Grid configuration and snapshot**
  Add Spring ConfigurationProperties for grid dimensions, snapshot method, comprehensive edge-case tests.

## Files Likely Touched
- `src/main/java/com/paralife/world/Position.java`
- `src/main/java/com/paralife/world/WorldGrid.java`
- `src/main/java/com/paralife/world/GridConfig.java`
- `src/test/java/com/paralife/world/PositionTest.java`
- `src/test/java/com/paralife/world/WorldGridTest.java`
