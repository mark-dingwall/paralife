# Phase 19: High-Density Placement & Partition-Aware World Execution — Research

**Researched:** 2026-04-30
**Domain:** 2D game-simulation placement algorithms + entity-list iteration patterns (Java 21 / Spring Boot)
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Placement (SCALE-06)**

- D-01: Free-cell index — eligible-cell set maintained server-side; O(1) uniform sample; no retry storms.
- D-02: Scope = `r|` ingress path only (bot register + respawn in `WorldWebSocketHandler.handleRegister`).
- D-03: Eligibility constraints in priority order:
  1. Cell has no occupant (rocks, nutrients, particles, composite members excluded).
  2. Cell not flagged OVERCROWDED (`Cell.flags & FLAG_OVERCROWDED == 0`).
  3. Placing here would NOT cause any adjacent occupied cell to flip from non-overcrowded → overcrowded (no occupied Moore neighbour with `neighborCount == OVERCROWDED_THRESHOLD - 1`).
- D-04: Index maintenance is incremental, dirty-bbox (5×5 around place/clear/death events). Hooks at `WorldGrid.trySetEntity / setEntity / clearEntity`. O(25) per event, sub-ms.
- D-05: Failure mode on empty eligible set = `E|503|GRID_FULL` (existing `RejectionToken.GRID_FULL` wire shape; no constraint relaxation; no queue).
- D-06: Bit-exact determinism under `paralife.simulation.spawn.seed`. Promoted to a tested contract. Mirrors Phase 15 D-35 `RockGenerator` pattern.

**World Execution (SCALE-07)**

- D-07: Entity-list iteration replaces O(grid-cells) scans in tick handlers. Candidate handlers: `SimulationEngine`, `EnvironmentEngine`, `PerceptionBroadcaster`, `TickBroadcaster` (exact set determined in planning).
- D-08: Single-threaded mutation core preserved throughout Phase 19.
- D-09: Live-entity registry is the source of iteration. Data structure (concrete type), deterministic iteration order, O(1) add/remove, composite entity support, snapshot-stable iteration are the binding constraints. Concrete design is Claude's Discretion.
- D-10: Semantic equivalence is a verification gate — byte-identical observable output vs pre-refactor baseline at existing milestone workloads.
- D-11: Phase 19 ships entity-list refactor only — no new parallelism inside tick handlers.
- D-12: Phase 19.1 (follow-up) ships parallel read-only sub-steps (PerceptionBroadcaster + TickBroadcaster encode); deferred out of Phase 19 scope.

### Claude's Discretion

- Concrete name and package of the free-cell index component.
- Exact data structure for the eligible set.
- Concrete name and shape of the live-entity registry abstraction.
- Dirty-bbox radius adjustment if 5×5 proves wrong.
- Golden-trace test format (raw frame log, hashed digest, or both).
- Final list of tick handlers refactored to entity-list iteration.
- Overcrowding constraint threshold value (read from existing config).

### Deferred Ideas (OUT OF SCOPE)

- Parallel `PerceptionBroadcaster` — Phase 19.1.
- Parallel `TickBroadcaster` encode — Phase 19.1.
- Conflict-graph parallel dispatch — backlog 999.x.
- Spatial tiling with halo, striped locks, full tile decomposition — backlog 999.x.
- GPU offload for env diffusion — backlog 999.x.
- Work-stealing per @Order phase — backlog 999.x.
- Initial rock placement — Phase 15-locked, do not touch.
- Reproduce offspring placement — out of scope.
- Nutrient spawn — out of scope.
- Connection multiplexing / runtime tuning — Phase 20.
- Benchmark gate + scale reports — Phase 21.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| SCALE-06 | High-density runs use placement behaviour that avoids pathological collisions with rocks and already-occupied cells. | Free-cell index (D-01 through D-06): dense-array + HashMap structure, O(1) sample, dirty-bbox maintenance, deterministic seed contract. |
| SCALE-07 | World execution gains a partition-aware or equivalently decomposed scale path that can grow without changing observed simulation semantics. | Entity-list iteration (D-07 through D-11): `LiveEntityRegistry` backed by a dense array with swap-remove, replaces O(65,536) grid scans with O(N) entity walks. |
</phase_requirements>

---

## Summary

Phase 19 has two independent but related concerns. The CONTEXT.md decisions have already resolved the architectural choices; this research validates those choices and provides prescriptive implementation detail.

**Concern 1 — High-density placement (SCALE-06).** The current 50-retry random scan in `WorldWebSocketHandler` (lines 450–467) degrades pathologically above ~50% grid occupancy: expected retry count grows as O(1/(1-density)), hitting O(50) collisions at 98% density. The locked decision (D-01) is a free-cell index — a dense array of eligible cell indices with a parallel hash map for O(1) removal and O(1) uniform random sample. This is the exact data structure known as the "O(1) insert/delete/getRandom" structure from competitive programming and ECS literature: dense array + map of value→index, with swap-and-pop deletion. The additional eligibility constraints (D-03) piggyback the per-tick `EnvironmentEngine.buildStatusCaches()` pass that already computes OVERCROWDED bits — constraint-3 evaluation adds one predicate per cell to a walk that already runs each tick.

**Concern 2 — Entity-list iteration (SCALE-07).** At 256×256 = 65,536 cells and 256 entities (current admission cap), every tick handler that does a full grid scan wastes 65,280 iterations on empty or rock cells. `SimulationEngine` alone has 9 distinct double-nested grid loops (confirmed by source). The fix is a live-entity registry backed by the same dense-array + position-map structure used in ECS literature — O(1) add/remove, O(N) iteration, deterministic ordering. The spatial diffusion loops in `EnvironmentEngine` (toxin/mutagen CA diffusion) must remain grid-based because they operate on every cell regardless of occupancy; only the per-entity logic segments move to entity-list iteration.

**Primary recommendation:** Implement `EligibleCellIndex` in `com.paralife.world` (dense `int[]` of linearised cell indices + `int[] indexByCell` back-map) and `LiveEntityRegistry` in `com.paralife.engine` (dense `List<BotRegistry.BotState>` or equivalent + position-to-index map for O(1) removal). Both structures reuse patterns already proven in the existing codebase (sparse-set style from RockGenerator's deterministic seeded contract; BotRegistry's ConcurrentHashMap discipline).

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Free-cell index maintenance | World / Engine boundary | — | Index must be notified at every `WorldGrid.trySetEntity / clearEntity` call site. Lives in `com.paralife.world` or `com.paralife.engine`; leaning `com.paralife.engine` to keep WorldGrid focused on grid ops. |
| Eligibility evaluation (constraints 1–3) | Engine (`EnvironmentEngine`) | World (`WorldGrid`) | Constraint-2/3 uses `cellStatusCache` which lives in `EnvironmentEngine`; constraint-1 uses `WorldGrid.getCell`. Piggyback logic in `buildStatusCaches()` rebuild pass. |
| Placement sampling (O(1) random draw) | WebSocket (`WorldWebSocketHandler`) | — | Placement is triggered by `r|` inbound frame; handler calls `EligibleCellIndex.sample(rng)`. |
| Live-entity registry | Engine (`BotRegistry` extension or new `LiveEntityRegistry`) | — | Must coexist with `BotRegistry` session↔entity↔position tracking; natural extension point. |
| Tick handler entity iteration | Engine (per-handler: `SimulationEngine`, `EnvironmentEngine` per-entity segments, `PerceptionBroadcaster`, `TickBroadcaster`) | — | Each handler owns its own iteration loop; all consume the same `LiveEntityRegistry`. |
| Environmental diffusion (CA) | Engine (`EnvironmentEngine`) | — | Inherently spatial — stays O(grid cells); cannot be entity-list iterated. |
| Determinism contract + golden-trace test | Test layer | — | Separate `*DeterminismTest` / `*GoldenTraceTest` classes following existing `EnvironmentDeterminismTest` pattern. |

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `java.util.Random` | JDK 21 (built-in) | Seeded deterministic RNG for placement sampling | Algorithm is fixed and documented (LCG, `seed = seed * 0x5DEECE66DL + 0xBL`). Cross-JVM stable: JDK 17 and JDK 11 produce identical sequences from same seed — algorithm has not changed since Java 1.0. `[VERIFIED: JDK 21 docs + JDK source]` |
| `java.util.ArrayList` | JDK 21 (built-in) | Dense array backing for eligible-cell index and live-entity registry | Contiguous memory, O(1) indexed access for swap-remove, best cache locality for sequential iteration. `[VERIFIED: Java Collections docs]` |
| `java.util.HashMap` | JDK 21 (built-in) | Back-map for O(1) removal in dense-array structures | Pairs with ArrayList to form the O(1) insert/delete/getRandom structure. `[VERIFIED: GeeksforGeeks design problem / sparse-set literature]` |
| Spring `@EventListener @Order` | Spring Framework 6.x (via Spring Boot 3.4.4) | Tick pipeline ordering — entity-list refactor must preserve `@Order` numbering | Existing architecture; not changing. `[VERIFIED: CLAUDE.md]` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.util.BitSet` | JDK 21 (built-in) | Optional alternative for the eligibility set | Compact memory (65,536 bits = 8 KB), O(1) set/clear, but O(cardinality) sampling (must walk to nth set bit). Worse than dense-array for sampling; better for membership queries if sampling is rare. Not recommended given D-01's O(1) sample requirement. `[ASSUMED]` |
| `java.util.LinkedHashSet` | JDK 21 (built-in) | Alternative for live-entity registry if insertion-order iteration is required | Provides insertion-order determinism automatically; O(1) contains/add/remove; iteration is O(n+capacity). Lacks O(1) indexed access for uniform sampling — does not apply to `LiveEntityRegistry` (no sampling needed there). `[VERIFIED: Java Collections docs]` |

### Alternatives Considered (and Rejected by CONTEXT.md Decisions)

| Instead of | Could Use | Tradeoff | Decision |
|------------|-----------|----------|---------|
| Dense-array + HashMap index | `java.util.BitSet` | BitSet: 8 KB, O(cardinality) to find nth bit; dense-array: O(1) sample. At >1000 eligible cells, nth-bit walk is non-trivial. | Dense-array chosen per D-01. |
| Dense-array + HashMap index | Segment tree over cell ranks | O(log N) sample with non-uniform weight support; required for weighted placement. Overkill — uniform distribution is specified. | Dense-array simpler, O(1). |
| Entity-list iteration | Conflict-graph parallel dispatch | Bigger win possible; ~3–4× complexity; deferred to backlog 999.x per CONTEXT.md D-07/D-11. | Entity-list only. |
| Entity-list iteration | Spatial tile decomposition with halo | Future-parallelizable but large blast radius; deferred. | Entity-list only. |

**Installation:** No new dependencies. Everything required is in JDK 21 + existing Spring Boot 3.4.4 classpath.

---

## Architecture Patterns

### System Architecture Diagram

```
PLACEMENT PATH (r| inbound frame)
─────────────────────────────────
WS inbound frame
  └─→ WorldWebSocketHandler.handleRegister(session, register)
        │
        ├─→ EligibleCellIndex.sample(spawnRng)          ← O(1) uniform draw from dense array
        │     │  (empty eligible set)
        │     └─→ E|503|GRID_FULL                        ← existing RejectionToken.GRID_FULL
        │
        ├─→ WorldGrid.trySetEntity(x, y, particle)      ← write lock; returns true
        │     └─→ EligibleCellIndex.notifyOccupied(x,y) ← dirty 5×5 bbox, remove from eligible set
        │
        └─→ BotRegistry.register(session, entity, pos)

INDEX MAINTENANCE LOOP (per placement/death event)
──────────────────────────────────────────────────
Event: place / clear / death at position (px, py)

  for each (cx, cy) in 5×5 Moore neighbourhood of (px, py):  [toroidal wrap]
    recompute eligibility of cell (cx, cy):
      1. cell.hasOccupant()? → ineligible
      2. cellStatusCache.overcrowded(cx,cy)? → ineligible
      3. any occupied Moore neighbour at neighborCount == THRESHOLD-1? → ineligible
    if now eligible and not in index → index.add(cx,cy)
    if now ineligible and in index  → index.remove(cx,cy)   ← swap-and-pop, O(1)

INDEX REBUILD PIGGYBACK (per tick, in EnvironmentEngine.buildStatusCaches())
─────────────────────────────────────────────────────────────────────────────
buildStatusCaches() already walks all cells to compute OVERCROWDED bit.
  → Constraint-2/3 evaluation for dirty-bbox cells runs here (one extra predicate).
  → Full rebuild: O(65,536); incremental bbox-only on event: O(25).

ENTITY-LIST TICK PATH (per tick)
─────────────────────────────────
TickEvent fires
  │
  @Order(10)  SimulationEngine.onTick()
  │             └─→ LiveEntityRegistry.snapshot()          ← O(N) copy for stable iteration
  │                   forEach entity:
  │                     processInteraction(entity)          ← replaces O(65,536) grid scan
  │
  @Order(14)  EnvironmentEngine.onTick()
  │             ├─→ diffusion loops: still O(65,536)       ← grid-based CA, cannot entity-list
  │             └─→ per-entity env effects:
  │                   LiveEntityRegistry.snapshot()
  │                   forEach entity: applyToxin/mutagen()
  │
  @Order(20)  ActionResolver.onTick()
  │             └─→ (no grid scan today; already entity-keyed)
  │
  @Order(50)  PerceptionBroadcaster.onTick()
  │             └─→ LiveEntityRegistry.snapshot()
  │                   forEach entity: buildPerception(entity)  ← replaces O(65,536) per-bot
  │
  @Order(100) TickBroadcaster.onTick()
               └─→ LiveEntityRegistry.snapshot()
                     encodeSnapshot + broadcast             ← tick frame still uses worldGrid read
```

### Recommended Project Structure

```
src/main/java/com/paralife/
├── world/
│   └── (WorldGrid, Cell, Entity, Position — unchanged)
├── engine/
│   ├── EligibleCellIndex.java        # NEW — free-cell index for placement
│   ├── LiveEntityRegistry.java       # NEW — dense entity list for tick iteration
│   ├── BotRegistry.java              # EXISTING — extend or integrate with LiveEntityRegistry
│   ├── SimulationEngine.java         # MODIFIED — entity-list iteration in grid-scan methods
│   ├── EnvironmentEngine.java        # MODIFIED — per-entity env effects use entity-list; diffusion stays grid
│   └── (other existing engine beans)
└── websocket/
    ├── WorldWebSocketHandler.java    # MODIFIED — placement via EligibleCellIndex.sample()
    ├── TickBroadcaster.java          # MODIFIED — entity-list for broadcast; tick snapshot still grid-read
    └── (other existing WS beans)

src/test/java/com/paralife/
├── engine/
│   ├── EligibleCellIndexTest.java          # NEW — unit tests for index ops + determinism contract
│   ├── PlacementDeterminismTest.java        # NEW — bit-exact placement regression (D-06 contract)
│   └── GoldenTraceEquivalenceTest.java     # NEW — semantic equivalence gate (D-10)
└── websocket/
    └── PlacementDensityIntegrationTest.java # NEW — GRID_FULL at high density, no retry storms
```

### Pattern 1: Dense-Array + HashMap (O(1) Insert / Remove / Sample)

**What:** Maintain a `int[] dense` array (packed cell indices, size = eligible count) and `int[] sparse` back-map indexed by linearised cell position. Removal is swap-and-pop — no shifting. Sampling is `dense[rng.nextInt(size)]`.

**When to use:** Any time you need O(1) uniform random selection from a changing set — used here for `EligibleCellIndex`.

```java
// Source: [VERIFIED: GeeksforGeeks "Design a data structure that supports insert,
//          delete, search and getRandom in constant time" + Sparse Set ECS literature]
// Linearise cell coordinate to single int index
private int toIndex(int x, int y) { return x * height + y; }

// dense[0..size-1] = eligible cell linear indices (packed)
// posInDense[linearIndex] = position of that index in dense[], or -1 if absent
private int[] dense;
private int[] posInDense;
private int size; // current eligible count

public void add(int x, int y) {
    int idx = toIndex(x, y);
    if (posInDense[idx] >= 0) return;         // already present
    dense[size] = idx;
    posInDense[idx] = size;
    size++;
}

public void remove(int x, int y) {
    int idx = toIndex(x, y);
    int pos = posInDense[idx];
    if (pos < 0) return;                       // not present
    // Swap-and-pop: move last element into this slot
    int last = dense[size - 1];
    dense[pos] = last;
    posInDense[last] = pos;
    posInDense[idx] = -1;
    size--;
}

public Position sample(Random rng) {
    if (size == 0) return null;                // triggers GRID_FULL path
    int idx = dense[rng.nextInt(size)];
    int x = idx / height;
    int y = idx % height;
    return new Position(x, y);
}
```

**Memory:** `dense[]` = 65,536 ints = 256 KB; `posInDense[]` = 65,536 ints = 256 KB. Total ~512 KB — acceptable heap cost. Pre-allocate at construction; no resizing needed.

**Initialisation:** On application startup (after rock map is final), walk all cells and add each eligible cell to the index. Subsequent maintenance is event-driven.

### Pattern 2: Live-Entity Registry (Dense Entity List for Tick Iteration)

**What:** A `List<T>` (where T carries entity identity + position) maintained in sync with grid mutations. Provides O(N) sequential iteration over live entities without an O(grid) scan. Iteration order must be deterministic per tick.

**When to use:** Any tick handler that currently does a full grid scan to find entities — replace the grid scan with `liveEntityRegistry.snapshot()`.

```java
// Source: [ASSUMED — pattern derived from ECS dense-array literature; no specific
//          Java game library implements this identically for Spring Boot]
//
// Implementation options (in preference order for this codebase):

// Option A: Extend BotRegistry with a deterministically-ordered view
// BotRegistry already has bySession (ConcurrentHashMap). Add:
private final List<BotState> orderedBots = Collections.synchronizedList(new ArrayList<>());

// register() → orderedBots.add(state)
// unregisterByEntity() → swap-and-remove by scanning for entityId
//   OR maintain a parallel index: entityId → index in orderedBots

// Option B: New LiveEntityRegistry bean
// Simpler blast radius — separate from BotRegistry session-tracking responsibility.
// Iteration: orderedBots is a per-tick snapshot (shallow copy) so handlers iterate
// a stable list even if deaths occur mid-handler.

// Per-tick snapshot pattern (snapshot-stable iteration, D-09 constraint):
public List<BotState> snapshot() {
    synchronized (this) {
        return new ArrayList<>(orderedBots);  // O(N) copy; N ≤ 256 at current cap
    }
}
```

**Determinism requirement (D-09):** Iteration order must be stable per tick across two runs with the same seed. Option: sort by entityId (lexicographic) at snapshot time. Cost: O(N log N) at N=256 = trivial. Alternative: insertion order (append-only); entities are always registered through the same serialised WS inbound path — insertion order is deterministic given same seed and same registration-arrival order (D-06 confirms this).

**Composite entity support (D-09):** `BondedPair` and `CompositeMember` are `Entity` subtypes. The registry stores by `entityId`, not by `Entity` type. Composite members have their own entityId and position. The existing `BotRegistry` already tracks them via `remapEntity`. Extending or delegating from `BotRegistry` naturally handles this.

### Pattern 3: Dirty-Bbox Index Maintenance

**What:** On any grid event (place, clear, death), recompute eligibility for the 5×5 Moore neighbourhood of the affected cell. Constraint-3 evaluation uses neighbour-count data already available in `EnvironmentEngine.buildStatusCaches()`.

**When to use:** At every `WorldGrid.trySetEntity`, `setEntity`, `clearEntity` call site. These are the natural notification points.

```java
// Source: [ASSUMED — dirty-bbox pattern from game simulation literature;
//          D-04 locks this approach in CONTEXT.md]
// Call from WorldGrid write sites (or from WorldWebSocketHandler after successful trySetEntity):
eligibleCellIndex.notifyChanged(x, y);

// In EligibleCellIndex.notifyChanged(int px, int py):
int radius = DIRTY_BBOX_RADIUS; // = 2, giving 5×5
for (int dy = -radius; dy <= radius; dy++) {
    for (int dx = -radius; dx <= radius; dx++) {
        int cx = Math.floorMod(px + dx, width);  // toroidal wrap
        int cy = Math.floorMod(py + dy, height);
        boolean eligible = evaluateEligibility(cx, cy);
        if (eligible) add(cx, cy); else remove(cx, cy);
    }
}
```

**Toroidal wrap:** `Math.floorMod` handles negative coordinates correctly — existing `Position.wrap` uses the same idiom. No special boundary case needed.

**Constraint-3 evaluation detail:** For a candidate cell (cx, cy), check each of the 8 Moore neighbours. If a neighbour is occupied AND its current neighbour count equals `OVERCROWDED_THRESHOLD - 1`, placing at (cx, cy) would push it over the threshold — cell is ineligible. Neighbour count is already computed per cell in `EnvironmentEngine.buildStatusCaches()` (it writes FLAG_OVERCROWDED when count ≥ threshold). You need the raw count, not just the flag, to evaluate constraint-3. Options:
  1. Store the per-cell neighbour count as a separate `byte[]` in `EnvironmentEngine` during the rebuild pass (add one `byte[256][256]` array = 64 KB, fully worth it).
  2. Re-compute the neighbour count inline in `notifyChanged`. Cost: O(8) per affected cell, O(200) total per event. Acceptable.

Option 2 is simpler (no new stored field). Option 1 avoids redundant grid reads during high-event ticks. Either is fine at current scale.

### Anti-Patterns to Avoid

- **Full index rebuild every tick:** O(65,536) rebuild when only a few events occurred. The incremental dirty-bbox approach (D-04) avoids this. If full rebuild is used as a correctness fallback during initial implementation, mark it as a TODO and replace before Phase 21.
- **Iterating `bySession.values()` inside a tick handler with modification possible:** `BotRegistry.getAllBots()` returns `bySession.values()` which is a live view of the ConcurrentHashMap. Iterating a live view during a tick where deaths can fire (SimulationEngine @Order(10) removes entities) is safe due to ConcurrentHashMap's weakly consistent iterator, but the entity count may be stale mid-iteration. Use `snapshot()` for any handler that cares about consistency.
- **Using `ThreadLocalRandom` in the eligible-cell index:** Non-reproducible; breaks D-06. Must use the seeded `spawnRng` from `SpawnConfig`.
- **Mixing entity-list and grid-scan within the same handler method:** A partial refactor that walks the entity list but then does a grid read inside the loop to re-check cell state is fine, but walking the grid to build a position list (the current pattern) should be fully replaced, not partially.
- **Removing entities from the live-entity registry inside the iteration loop:** Use the existing deferred-delta / death-queue pattern from `SimulationEngine` — accumulate deaths, apply after the loop. `BotRegistry.deathsThisTick` already does this.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| O(1) uniform random sample from a changing set | Custom skip-list or reservoir sampling | Dense-array + index back-map (swap-and-pop removal) | Well-understood O(1) structure; used in ECS (EnTT, Bevy), documented in competitive programming literature. |
| Deterministic seeded RNG | `SecureRandom`, `SplittableRandom`, or custom LCG | `java.util.Random(seed)` | Algorithm is documented and stable across JDK versions (LCG, unchanged since Java 1.0). `SplittableRandom` does NOT guarantee same sequence across JVMs. `SecureRandom` is not seeded deterministically. `[VERIFIED: JDK 21 docs + JDK source history]` |
| Toroidal modular arithmetic | Custom bounds-clamp | `Math.floorMod(coord, dimension)` | Already used in `WorldGrid.getCell` and `Position.wrap`; handles negative inputs correctly. Java `%` operator does NOT wrap negatives correctly. |
| Snapshot-stable entity iteration | `synchronized(list) { ... iterate ... }` held for full tick | `new ArrayList<>(list)` shallow copy | O(N) copy at N≤256 costs ~2µs; avoids holding the lock during the full tick handler execution. |
| Overcrowding neighbour-count recomputation | Storing a separate spatial index of occupied positions | Walk the 8 Moore neighbours from `WorldGrid.getNeighbors` | Already done in `SimulationEngine.processOvercrowding` and `EnvironmentEngine.buildStatusCaches()`. Reuse existing call pattern. |

**Key insight:** Both the free-cell index and the live-entity registry are instances of the same underlying pattern: a dense array of active items + a back-map for O(1) removal. Building anything more complex (segment trees, quad trees, spatial hash grids) is overkill at 256×256 grid size with ≤256 entities.

---

## Common Pitfalls

### Pitfall 1: Constraint-3 Reads Stale Neighbour Counts

**What goes wrong:** The dirty-bbox update fires when an entity is placed or dies. At that moment, `cellStatusCache` may not yet be rebuilt for the new tick — the constraint-3 check reads last-tick's overcrowding state.

**Why it happens:** `EnvironmentEngine.buildStatusCaches()` runs at @Order(14) — after `SimulationEngine` @Order(10) but before `ActionResolver` @Order(20). Bot registration arrives via WebSocket at any time, including mid-tick. If registration fires between @Order(10) and @Order(14), the cache is one tick stale.

**How to avoid:** For the purposes of constraint-3 evaluation in the *placement* path, the 1-tick staleness is acceptable by design (the eligible set is a best-effort heuristic; the real constraint-1 check `WorldGrid.trySetEntity` is the atomic gate). Document this staleness explicitly in `EligibleCellIndex` Javadoc. For the *index maintenance* triggered by deaths inside tick handlers, the cache is always current for that tick's @Order slot.

**Warning signs:** Placement succeeding into cells that are flagged OVERCROWDED on the subsequent tick — occasional, not persistent. Persistent overcrowded placement indicates the constraint-2 check is broken.

### Pitfall 2: Linearised Index Overflow for Non-Square Grids

**What goes wrong:** Using `x * height + y` assumes width and height are both known and constant. If grid dimensions change (future feature) or if width != height, the linearisation must be consistent.

**Why it happens:** `dense[]` and `posInDense[]` are allocated at construction with size `width * height`. If the linearisation formula is inconsistent between add/remove/sample, posInDense can get corrupted silently.

**How to avoid:** Inject both `width` and `height` into `EligibleCellIndex` at construction. Use a single `private int toIndex(int x, int y)` method throughout — never inline the formula.

### Pitfall 3: java.util.Random Sequence Divergence After Refactor

**What goes wrong:** The placement refactor changes the order of `spawnRng.nextInt()` calls. If `spawnRng` is shared between placement and other uses, the golden-trace test will fail even though both paths are correct independently.

**Why it happens:** `WorldWebSocketHandler.spawnRng` is currently used only for placement. If any other code path calls `spawnRng` in a changed order, the sequence diverges.

**How to avoid:** `spawnRng` must remain a single-purpose seed for placement in `WorldWebSocketHandler`. The refactor changes *what* the sample draws from (dense array index vs raw x,y), but the number of `rng.nextInt()` calls per placement must remain exactly 1 (previously 2 — one for x, one for y). Verify: new implementation uses `rng.nextInt(size)` = 1 call, down from 2. This is a behaviour change in call count — the golden-trace test will catch divergence if the seed contract is broken.

**Warning signs:** `PlacementDeterminismTest` fails with a wrong cell position despite correct algorithm logic.

### Pitfall 4: Entity-List Stale After Death Within Same Tick

**What goes wrong:** `SimulationEngine` @Order(10) kills entities. `PerceptionBroadcaster` @Order(50) iterates the entity list. If the list is not updated between these two handlers, PerceptionBroadcaster sends perception frames to dead entities.

**Why it happens:** Dead entities are currently removed via `BotRegistry.unregisterByEntity()`, which is called from `SimulationEngine`'s death-removal phase. The `LiveEntityRegistry` must be notified at the same time.

**How to avoid:** Hook `LiveEntityRegistry.remove(entityId)` at the same sites where `BotRegistry.unregisterByEntity(entityId)` is called — likely `DeathFinalizer` or wherever `BotRegistry.unregisterByEntity` is currently invoked. Then `PerceptionBroadcaster`'s snapshot, taken at @Order(50), sees the post-death list. The `BotRegistry.drainDeaths()` pattern (for sending the terminal `vD` frame) is unrelated and must be preserved.

**Warning signs:** `PerceptionBroadcaster` NullPointerException on entity lookup, or perception frames sent after entity death.

### Pitfall 5: Dirty-Bbox Radius Too Small for Constraint-3

**What goes wrong:** A placement at (px, py) affects the overcrowding count of cells up to 1 step away (Moore neighbours of (px, py) are at distance 1). Constraint-3 evaluates whether placing at (cx, cy) would push a Moore neighbour of (cx, cy) over threshold. That neighbour can be at distance up to 1 from (cx, cy) and 2 from (px, py). So constraint-3 eligibility of cells up to 2 steps from the event position can change — a 5×5 bbox (radius=2) is exactly correct.

**Why it happens:** Under-estimating the cascade radius leads to stale eligibility data in the index for cells at distance 2.

**How to avoid:** The 5×5 radius (D-04) is correct for constraint-3 given the Moore neighbourhood definition and the 1-step overcrowding coupling. Do not reduce it to 3×3.

### Pitfall 6: Golden-Trace Test Fragility Due to Non-Deterministic Ordering

**What goes wrong:** The golden-trace test records frames from one run and compares against a second run. If any frame ordering is non-deterministic (e.g., entity order in tick broadcast is HashMap iteration order), the test fails spuriously.

**Why it happens:** `BotRegistry.getAllBots()` returns `bySession.values()` from a `ConcurrentHashMap` — iteration order is not guaranteed stable across runs.

**How to avoid:** The `LiveEntityRegistry` snapshot must produce a deterministically ordered list (e.g., sorted by entityId at snapshot time, or maintained in insertion order where insertion order is itself deterministic from the seed). Assert insertion order is deterministic in `PlacementDeterminismTest` before running the golden-trace.

---

## Code Examples

Verified patterns from official sources or existing codebase:

### Toroidal Coordinate Wrap (existing pattern)

```java
// Source: [VERIFIED: WorldGrid.java + Position.wrap() in codebase]
// Math.floorMod handles negative dx/dy correctly; Java % does not.
int cx = Math.floorMod(px + dx, width);
int cy = Math.floorMod(py + dy, height);
```

### Seeded java.util.Random — stable LCG contract

```java
// Source: [VERIFIED: JDK 21 Random docs — algorithm specified and mandatory]
// "If two instances of Random are created with the same seed, and the same sequence
//  of method calls is made for each, they will generate and return identical sequences."
// Algorithm: seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1)
// This has NOT changed across JDK versions (legacy LCG group, stable since Java 1.0).
Random rng = new Random(spawnConfig.seed()); // null-seed → unseeded production default
```

### EligibleCellIndex — full swap-and-pop sketch

```java
// Source: [VERIFIED: "Design a data structure that supports insert, delete, search
//          and getRandom in constant time" — GeeksforGeeks + Sparse Set ECS literature]
public final class EligibleCellIndex {
    private final int width;
    private final int height;
    private final int[] dense;       // linearised cell indices, packed [0..size-1]
    private final int[] posInDense;  // posInDense[linearIdx] = position in dense, or -1
    private int size = 0;

    public EligibleCellIndex(int width, int height) {
        this.width = width;
        this.height = height;
        int total = width * height;
        this.dense = new int[total];
        this.posInDense = new int[total];
        Arrays.fill(posInDense, -1);
    }

    private int toIndex(int x, int y) { return x * height + y; }

    public void add(int x, int y) {
        int idx = toIndex(x, y);
        if (posInDense[idx] >= 0) return;
        dense[size] = idx;
        posInDense[idx] = size++;
    }

    public void remove(int x, int y) {
        int idx = toIndex(x, y);
        int pos = posInDense[idx];
        if (pos < 0) return;
        int last = dense[size - 1];
        dense[pos] = last;
        posInDense[last] = pos;
        posInDense[idx] = -1;
        size--;
    }

    /** O(1) uniform sample. Returns null iff eligible set is empty (→ GRID_FULL). */
    public Position sample(Random rng) {
        if (size == 0) return null;
        int idx = dense[rng.nextInt(size)];
        return new Position(idx / height, idx % height);
    }

    public int eligibleCount() { return size; }
}
```

### SimulationEngine — replacing grid scan with entity-list iteration

```java
// Source: [ASSUMED — derived from existing SimulationEngine.processInteractions()
//          structure at lines 292–305 of SimulationEngine.java]
// BEFORE: O(65,536) nested grid scan to build particle position list
List<Position> particlePositions = new ArrayList<>();
for (int x = 0; x < width; x++) {
    for (int y = 0; y < height; y++) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof Particle) {
            particlePositions.add(new Position(x, y));
        }
    }
}

// AFTER: O(N) entity-list snapshot — N ≤ 256 at current admission cap
List<LiveEntityRegistry.EntityEntry> entities = liveEntityRegistry.snapshot();
List<Position> particlePositions = entities.stream()
    .filter(e -> e.entity() instanceof Particle)
    .map(LiveEntityRegistry.EntityEntry::position)
    .collect(Collectors.toCollection(ArrayList::new));
// Then shuffle + process as before.
```

### Golden-Trace Test Pattern (determinism gate)

```java
// Source: [VERIFIED: EnvironmentDeterminismTest.java in codebase for inspiration;
//          Golden Master technique — https://stevenschwenke.de/whatIsTheGoldenMasterTechnique]
// Shape: run seeded scenario twice in same JVM; compare recorded output.
// Two implementation options:

// Option A: SHA-256 digest of all outbound frame bytes per tick (compact, fast)
MessageDigest digest = MessageDigest.getInstance("SHA-256");
// For each tick, before and after refactor: digest.update(frameBytes)
byte[] preRefactorHash = digest.digest();
// ... run again post-refactor ...
assertArrayEquals(preRefactorHash, postRefactorHash);

// Option B: Record raw frame log to a temp file; diff on second run (debuggable)
// Prefer Option A for CI speed; add Option B as diagnostic fallback.
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| 50-retry random scan | Free-cell index O(1) sample | Phase 19 | Eliminates O(1/(1-density)) retry explosion at high density |
| O(grid-cells) tick scan | O(entities) entity-list iteration | Phase 19 | ~50× iteration reduction at 256 entities / 65,536 cells |
| Single seeded RNG per concern | Same — contract promoted to tested | Phase 19 | `PlacementDeterminismTest` locks bit-exact contract |

**Deprecated/outdated approaches (not applicable to Phase 19, informational only):**
- Spatial partitioning with halo cells: standard for multi-threaded ECS tick loops; overkill here because Phase 19 keeps single-threaded mutation core. Deferred to backlog 999.x per D-11.
- Poisson-disk sampling: produces blue-noise spatial distribution (no two placements within radius r of each other). Useful for aesthetics or fairness of initial density. Not applicable here — constraint-3 already prevents cascade overcrowding; uniform random from eligible set is specified.
- Low-discrepancy sequences (Halton, quasi-random): deterministic without a seed; produce better spatial coverage than LCG. Not applicable — D-06 specifies seed-based determinism; LCG via `java.util.Random` is the locked contract.

---

## Honest Assessment: Is Entity-List Iteration Necessary at This Scale?

**Short answer:** Yes, it is the right call, but the performance win is modest at 256 bots and becomes significant at 1000+.

At the current admission cap of 256 entities on a 256×256 grid:

- Each grid scan: 65,536 iterations × ~10ns/iteration ≈ 0.65 ms
- Per tick, SimulationEngine has 9 distinct double-nested loops (confirmed by source grep). Total scan cost: ~6 ms/tick of pure loop overhead, before any logic.
- Tick interval: 500 ms. So scan overhead is ~1.2% of tick budget.
- At 1000 entities (Phase 21 benchmark target), scan overhead scales with grid (fixed at 65,536) while entity-list overhead scales with entities. No win on scan cost; win is on: (a) number of useless cell reads, (b) cleaner path to Phase 19.1 parallelism.

**The real motivation for D-07 is architectural, not purely performance:**

1. Entity-list iteration is the prerequisite for Phase 19.1's parallel read-only sub-steps (PerceptionBroadcaster, TickBroadcaster). You cannot parallel-stream a grid scan that builds a position list — you first need the entity list.
2. As the admission cap grows (Phase 21 may raise it), grid scan cost stays fixed but entity-list cost grows proportionally — entity-list is the right scaling direction.
3. The existing code's 9 grid scans per tick is O(9 × 65,536) = 589,824 cell reads per tick regardless of entity count. At 0 entities, this is pure waste. Entity-list eliminates that floor.

`[ASSUMED — performance number estimates based on typical JVM loop costs; no benchmark run in this session. Phase 21 benchmark will provide real data.]`

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | O(65,536) grid scan costs ~0.65 ms at 10 ns/iteration | Honest Assessment | Impact on Phase 21 benchmark targets; no plan change required |
| A2 | SimulationEngine has 9 distinct double-nested grid loops | Standard Stack / Architecture Patterns | Verified by grep (lines 295, 421, 505, 735, 870, 909, 924, 1172 + one more); count is accurate |
| A3 | Option 2 for constraint-3 (re-compute neighbour count inline) is acceptable at current scale | Architecture Patterns | If event rate > ~10,000/tick, O(200) per event could add measurable cost. Unlikely at 256 cap. |
| A4 | `java.util.Random` LCG algorithm is stable across JDK minor versions | Standard Stack / Code Examples | Verified via JDK source history: algorithm documented as mandatory; unchanged since Java 1.0. Risk: extremely low. |

**A2 is tagged VERIFIED by source-code grep during this session.**

---

## Open Questions

1. **Where exactly does `LiveEntityRegistry` get notified of entity deaths?**
   - What we know: `BotRegistry.unregisterByEntity()` is called on death. `DeathFinalizer` or `SimulationEngine.processDeaths()` drives this.
   - What's unclear: Whether death cleanup goes through a single choke point (easy to hook) or multiple sites.
   - Recommendation: Planner reads `DeathFinalizer.java` to identify all `botRegistry.unregisterByEntity()` call sites before writing the task.

2. **Should `EligibleCellIndex` live in `com.paralife.world` or `com.paralife.engine`?**
   - What we know: It depends on `WorldGrid` (via event hooks at `trySetEntity`/`clearEntity`) but also on `EnvironmentEngine.cellStatusCache` (for constraint-2/3 evaluation).
   - What's unclear: Whether the index should be a `@Component` Spring bean or a plain collaborator injected into `WorldWebSocketHandler`.
   - Recommendation: `com.paralife.engine` as a `@Component`. `WorldGrid` should call a notification method on `EligibleCellIndex` after each mutation (or `WorldWebSocketHandler` calls it after `trySetEntity` succeeds). Either is fine; keeping `WorldGrid` pure (not Spring-aware) slightly favours the second option.

3. **Golden-trace test scope: what frames to capture?**
   - What we know: D-10 says "byte-identical observable output (tick frames, perception frames, action results, metric counters)".
   - What's unclear: Capturing all frame bytes requires a test WebSocket client. Metric counters require Actuator scrapes. This is significant test infrastructure.
   - Recommendation: Start with SHA-256 digest of all `sendMessage` payloads captured via a mock `OutboundSender`; defer metric counter comparison to Phase 21 benchmark gate. Flag in the plan that full equivalence is aspirational; perception-frame digest is the minimum viable contract.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 19 is purely Java code/config changes. No external tools, databases, CLIs, or services beyond the existing Spring Boot JVM are required.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) via Spring Boot Test |
| Config file | `src/test/resources/` (application.yml overridden by `@TestPropertySource`) |
| Quick run command | `./gradlew test --tests "com.paralife.engine.EligibleCellIndex*" --tests "com.paralife.engine.PlacementDeterminism*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SCALE-06 | Free-cell index returns eligible cells only (constraint 1, 2, 3) | unit | `./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"` | ❌ Wave 0 |
| SCALE-06 | Same seed + same registration order → identical placements | unit | `./gradlew test --tests "com.paralife.engine.PlacementDeterminismTest"` | ❌ Wave 0 |
| SCALE-06 | Dense grid (>50% occupancy) → placement still succeeds or returns GRID_FULL without retry storm | integration | `./gradlew test --tests "com.paralife.websocket.PlacementDensityIntegrationTest"` | ❌ Wave 0 |
| SCALE-07 | Tick handler output byte-identical before/after entity-list refactor (seeded scenario) | integration | `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` | ❌ Wave 0 |
| SCALE-07 | `./gradlew test` (all 166 existing tests green after refactor) | regression | `./gradlew test` | ✅ (existing suite) |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "com.paralife.engine.EligibleCellIndex*" --tests "com.paralife.engine.Placement*"` for placement tasks; `./gradlew test --tests "com.paralife.engine.GoldenTrace*"` for entity-list tasks.
- **Per wave merge:** `./gradlew test` (full suite, must be green).
- **Phase gate:** Full suite green before `/gsd-verify-work`.

### Wave 0 Gaps

- [ ] `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` — covers SCALE-06 constraint evaluation + O(1) ops
- [ ] `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` — covers SCALE-06 bit-exact seed contract (D-06)
- [ ] `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` — covers SCALE-06 high-density GRID_FULL path
- [ ] `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` — covers SCALE-07 semantic equivalence gate (D-10)

---

## Security Domain

`security_enforcement` not explicitly set to false in config.json — section included.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | No auth surface touched |
| V3 Session Management | No | Session lifecycle unchanged |
| V4 Access Control | No | No access control surface |
| V5 Input Validation | Yes (low) | `EligibleCellIndex.sample()` returns null on empty set → GRID_FULL rejection already validated by wire protocol |
| V6 Cryptography | No | `java.util.Random` is explicitly not a CSPRNG; it is not used for security, only for deterministic placement in tests |

**No new attack surface introduced.** The free-cell index is server-internal. The placement result (x,y) is never echoed back to the client as a trust boundary. RNG seeding is for test determinism only; production runs unseeded.

---

## Sources

### Primary (HIGH confidence)

- JDK 21 `java.util.Random` documentation — algorithm stability guarantee, LCG specification `[VERIFIED]`
- Codebase grep + file reads — grid dimensions (256×256), grid scan loop count (9 in SimulationEngine), BotRegistry structure, WorldWebSocketHandler placement loop, existing seed pattern `[VERIFIED]`
- `EnvironmentDeterminismTest.java` — existing golden-trace / dual-run determinism test pattern `[VERIFIED]`
- CONTEXT.md decisions D-01 through D-12 — all architectural decisions `[VERIFIED]`

### Secondary (MEDIUM confidence)

- EnTT sparse-set ECS documentation (skypjack.github.io/2020-08-02-ecs-baf-part-9/) — dense-array + swap-pop pattern `[CITED: skypjack.github.io]`
- GeeksforGeeks "Design a data structure that supports insert, delete, search and getRandom in constant time" — swap-and-pop algorithm `[CITED: geeksforgeeks.org]`
- JDK source history: `java.util.Random` LCG unchanged since Java 1.0, confirmed not changed in JDK 17+ `[CITED: openjdk/jdk master]`
- Golden Master testing technique — stevenschwenke.de `[CITED: stevenschwenke.de]`

### Tertiary (LOW confidence)

- Performance estimate: ~0.65 ms per O(65,536) grid scan at 10 ns/iteration — from general JVM loop cost knowledge `[ASSUMED]`

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — JDK 21 built-ins, no new dependencies, algorithm documented and stable.
- Architecture: HIGH — patterns verified from codebase structure + EnTT literature + CONTEXT.md decisions.
- Pitfalls: HIGH — derived from direct code inspection of placement loop, entity death path, and existing determinism tests.
- Performance estimates: LOW — no benchmark run in session; Phase 21 benchmark gate owns real data.

**Research date:** 2026-04-30
**Valid until:** 2026-05-30 (stable domain — JDK 21 LCG, Java collections, Spring Boot 3.4.4 all stable)
