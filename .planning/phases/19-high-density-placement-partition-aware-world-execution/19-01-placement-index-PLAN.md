---
phase: 19
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/main/java/com/paralife/engine/EligibleCellIndex.java
  - src/main/java/com/paralife/engine/EnvironmentEngine.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/test/java/com/paralife/engine/EligibleCellIndexTest.java
  - src/test/java/com/paralife/engine/PlacementDeterminismTest.java
  - src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
autonomous: true
requirements:
  - SCALE-06
tags: [placement, sparse-set, determinism, websocket, java, spring-boot]

must_haves:
  truths:
    - "Bot register/respawn placement returns a uniformly random cell from the eligible set in O(1) — no retry storm at any density."
    - "An empty eligible set yields E|503|GRID_FULL with the existing RejectionToken.GRID_FULL wire shape — no constraint relaxation, no queue."
    - "Eligibility excludes occupied cells (constraint 1), OVERCROWDED cells (constraint 2), and cells whose placement would push an adjacent occupied cell from non-overcrowded → overcrowded (constraint 3)."
    - "Same `paralife.simulation.spawn.seed` + same registration arrival order → byte-identical (x,y) placements across two runs."
    - "MAX_PLACEMENT_ATTEMPTS constant is removed from WorldWebSocketHandler — the retry loop is gone."
  artifacts:
    - path: src/main/java/com/paralife/engine/EligibleCellIndex.java
      provides: "Sparse-set eligible-cell index: O(1) add/remove/sample; @PostConstruct walks grid post-rock-init; notifyChanged(x,y) recomputes 5×5 dirty bbox using EnvironmentEngine.cellStatusCacheView() + neighbour-count walk."
      min_lines: 120
    - path: src/test/java/com/paralife/engine/EligibleCellIndexTest.java
      provides: "Unit tests for add/remove/sample/empty + constraint 3 rejection + dual-run seed equality on a small (8×8) grid."
      min_lines: 80
    - path: src/test/java/com/paralife/engine/PlacementDeterminismTest.java
      provides: "@SpringBootTest with paralife.simulation.spawn.seed=42 — drives N registrations twice and asserts byte-equality of placement positions (D-06)."
      min_lines: 80
    - path: src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
      provides: "@SpringBootTest(webEnvironment=RANDOM_PORT) — fills grid to >50% via WS register frames, asserts no retry storm, asserts (N+1)th register receives E|503|GRID_FULL when eligible set empty."
      min_lines: 100
  key_links:
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected field; handleRegister calls eligibleCellIndex.sample(spawnRng) instead of the 50-retry loop"
      pattern: "eligibleCellIndex\\.sample\\(spawnRng\\)"
    - from: src/main/java/com/paralife/engine/EligibleCellIndex.java
      to: src/main/java/com/paralife/engine/EnvironmentEngine.java
      via: "cellStatusCacheView() read for constraint-2; neighbour-count walk for constraint-3"
      pattern: "cellStatusCacheView\\(\\)"
    - from: src/main/java/com/paralife/engine/EligibleCellIndex.java
      to: src/main/java/com/paralife/world/WorldGrid.java
      via: "notifyChanged(x,y) called from WorldWebSocketHandler after trySetEntity returns true; @PostConstruct walks worldGrid.getCell(x,y) once at startup"
      pattern: "worldGrid\\.getCell"
---

<objective>
Replace the 50-retry random-scan placement of bot register/respawn (lines 450–467 of `WorldWebSocketHandler`) with an O(1) sparse-set free-cell index that enforces three eligibility constraints (no occupant; not OVERCROWDED; placement here would not push an adjacent occupied cell over the overcrowding threshold). Promote `paralife.simulation.spawn.seed` to a tested bit-exact placement contract. Closes SCALE-06.

Purpose: At >50% grid occupancy, the existing 50-retry loop hits O(1/(1−density)) collisions and degrades pathologically. Phase 21 benchmark targets (1000+ bots) are unreachable without this fix.
Output: New `EligibleCellIndex` bean; refactored `handleRegister` placement; three new test files; `MAX_PLACEMENT_ATTEMPTS` constant deleted; `EnvironmentEngine` exposes `cellStatusCacheView()` (already exists at line 1399).
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@CLAUDE.md
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-VALIDATION.md
@src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
@src/main/java/com/paralife/engine/EnvironmentEngine.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/SpawnConfig.java
@src/main/java/com/paralife/engine/SimulationConfig.java
@src/main/java/com/paralife/world/WorldGrid.java
@src/main/java/com/paralife/world/RockGenerator.java
@src/main/java/com/paralife/world/Cell.java
@src/main/java/com/paralife/world/Position.java
@src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java
@src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java

<interfaces>
<!-- Key types and contracts the executor needs. Use these directly — no codebase exploration needed. -->

From src/main/java/com/paralife/world/WorldGrid.java:
```java
public boolean trySetEntity(int x, int y, Entity entity);   // line 60 — write-lock + occupant check
public Cell getCell(int x, int y);                          // toroidal-wrapped read
public int getWidth();
public int getHeight();
public List<Position> getNeighbors(int x, int y);           // 8 Moore neighbours, toroidal
```

From src/main/java/com/paralife/world/Cell.java:
```java
public boolean hasOccupant();
public Entity occupant();
public byte flags();
public static final byte FLAG_OVERCROWDED = 0x01; // bit 0
```

From src/main/java/com/paralife/world/Position.java:
```java
public record Position(int x, int y) {
    public static Position wrap(int x, int y, int width, int height);
}
```

From src/main/java/com/paralife/engine/SpawnConfig.java:
```java
@ConfigurationProperties(prefix = "paralife.simulation.spawn")
public record SpawnConfig(Long seed) { /* null = unseeded */ }
```

From src/main/java/com/paralife/engine/SimulationConfig.java:
```java
public record SimulationConfig(..., int overcrowdingThreshold, ...) { }
```

From src/main/java/com/paralife/engine/EnvironmentEngine.java:
```java
Map<Position, Byte> cellStatusCacheView();   // line 1399 — Collections.unmodifiableMap of cellStatusCache
                                              // bit 0 (0x01) = OVERCROWDED, bit 1 (0x02) = TOXIN, bit 2 (0x04) = MUTAGEN
void buildStatusCaches();                    // line 875 — runs at @Order(14); rebuild every tick
void buildStatusCachesForTest();             // line 1492 — public test seam
```

From src/main/java/com/paralife/engine/BotRegistry.java:
```java
public void register(String sessionId, String entityId, Position position);  // line 62
public Collection<BotState> getAllBots();                                     // line 195
public void clear();                                                           // existing test helper
```

From src/main/java/com/paralife/world/Entity.java (sealed):
```java
public sealed interface Entity permits Particle, BondedPair, CompositeMember, Rock, Nutrient { }
// Constraint-1 already covers all subtypes via hasOccupant().
// Constraint-3 occupied-neighbour check should match SimulationEngine.processOvercrowding (line 876–882):
// neighbour counts iff (occupant instanceof Particle || occupant instanceof BondedPair).
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:
```java
private static final int MAX_PLACEMENT_ATTEMPTS = 50;        // line 88 — DELETE
private Random spawnRng;                                       // line 116 — keep
public void resetSeed();                                       // line 187 — test-only; keep
@Autowired ctor signature line 118 — add EligibleCellIndex parameter
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create EligibleCellIndex sparse-set + Wave 0 unit tests</name>
  <files>src/main/java/com/paralife/engine/EligibleCellIndex.java, src/test/java/com/paralife/engine/EligibleCellIndexTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (target — confirm absent before creating)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 1 — full sparse-set sketch lines 446–495; §Pitfall 5 — 5×5 radius rationale)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 28–86 — class shell + neighbour-count walk excerpt)
    - src/main/java/com/paralife/world/RockGenerator.java (seeded init / @PostConstruct pattern)
    - src/main/java/com/paralife/engine/BotRegistry.java (lines 1–30 — @Component class shell + Logger pattern)
    - src/main/java/com/paralife/world/WorldGrid.java (lines 50–115 — trySetEntity, setEntity, clearEntity, getCell, getNeighbors)
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (lines 870–940 — buildStatusCaches; line 1399 — cellStatusCacheView; lines 875–895 — neighbour-count walk shape)
    - src/main/java/com/paralife/engine/SimulationConfig.java (line 28 — overcrowdingThreshold)
    - src/main/java/com/paralife/world/Cell.java (FLAG_OVERCROWDED constant)
    - src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java (pure-JUnit unit-test analog)
  </read_first>
  <behavior>
    - addThenSampleReturnsAddedCell: add (3,4) on a fresh 8×8 index → sample(rng) returns Position(3,4); eligibleCount() == 1.
    - removeIsO1AndDoesNotShift: add three cells; remove the first; eligibleCount() == 2; remaining cells still samplable.
    - addIsIdempotent: add (3,4) twice; eligibleCount() == 1.
    - removeIsIdempotent: remove a non-present cell; eligibleCount() unchanged; no exception.
    - sampleEmptyReturnsNull: fresh index → sample(new Random(0)) == null (triggers GRID_FULL path).
    - seededSampleIsBitExact: build two identical indices, populate with same cells in same order, sample with `new Random(42L)` → both return same Position.
    - notifyChangedRespects5x5Bbox: place an entity at centre of an 8×8 grid and call notifyChanged(cx,cy); cells at distance ≤2 (toroidally) re-evaluated, cells at distance >2 untouched. Verify by pre-poisoning the index with a deliberately-wrong entry at distance 3 and confirming notifyChanged does not fix it (proves bbox is bounded — radius=2 boundary).
    - constraint1RejectsOccupiedCells: place an entity at (1,1) via worldGrid.trySetEntity; notifyChanged(1,1); index does not contain (1,1).
    - constraint2RejectsOvercrowdedCells: build a fake cellStatusCache where (2,2) has bit 0 set (FLAG_OVERCROWDED); notifyChanged(2,2); index does not contain (2,2).
    - constraint3RejectsCellsThatWouldOvercrowdNeighbour: with overcrowdingThreshold=4, surround cell (4,4) with exactly 3 occupied Moore neighbours so (4,4) itself is NOT yet overcrowded but any candidate cell (cx,cy) that is a Moore-neighbour of (4,4) AND empty would, if placed, push (4,4) to count==4. After notifyChanged in the bbox, those candidate cells must be excluded from the eligible set.
  </behavior>
  <action>
1. Create `src/main/java/com/paralife/engine/EligibleCellIndex.java`:

```java
package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/**
 * Phase 19 SCALE-06 (D-01..D-06): O(1) sparse-set of cells eligible for bot
 * register/respawn placement. Replaces the 50-retry random-scan in
 * {@code WorldWebSocketHandler}. Eligibility = (no occupant) AND
 * (cell not FLAG_OVERCROWDED) AND (placing here would NOT push any adjacent
 * occupied Moore neighbour over {@code overcrowdingThreshold}). Maintained
 * incrementally via {@link #notifyChanged(int, int)} on a 5×5 dirty bbox.
 *
 * <p>Caller supplies the seeded {@link Random} (i.e. {@code spawnRng}); this
 * bean owns no RNG state. {@code sample} consumes exactly one
 * {@code rng.nextInt(size)} call per draw — see RESEARCH.md Pitfall 3.
 *
 * <p>Constraint-2 staleness: per RESEARCH.md Pitfall 1, the
 * {@code cellStatusCache} read can be 1 tick stale at registration mid-tick.
 * The atomic gate is still {@code worldGrid.trySetEntity}; the index is a
 * fast-path heuristic.
 */
@Component
public class EligibleCellIndex {
    private static final Logger log = LoggerFactory.getLogger(EligibleCellIndex.class);
    private static final int DIRTY_BBOX_RADIUS = 2; // 5×5 — RESEARCH §Pitfall 5

    private final WorldGrid worldGrid;
    private final EnvironmentEngine environmentEngine;
    private final SimulationConfig simulationConfig;

    private final int width;
    private final int height;
    private final int[] dense;
    private final int[] posInDense;
    private int size = 0;

    public EligibleCellIndex(WorldGrid worldGrid,
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

    /**
     * Walk the grid once after rocks are placed (RockGenerator runs at @PostConstruct;
     * Spring guarantees component init order via dependency graph — WorldGrid is
     * already populated). Add every currently-eligible cell.
     */
    @PostConstruct
    public void initialize() {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (evaluateEligibility(x, y)) addInternal(x, y);
            }
        }
        log.info("EligibleCellIndex initialised: {} eligible cells of {} total", size, width * height);
    }

    private int toIndex(int x, int y) { return x * height + y; }

    /** Add cell (x,y) to the eligible set. Idempotent. */
    public synchronized void add(int x, int y) { addInternal(x, y); }

    private void addInternal(int x, int y) {
        int idx = toIndex(x, y);
        if (posInDense[idx] >= 0) return;
        dense[size] = idx;
        posInDense[idx] = size;
        size++;
    }

    /** Remove cell (x,y). Swap-and-pop O(1). Idempotent. */
    public synchronized void remove(int x, int y) {
        int idx = toIndex(x, y);
        int pos = posInDense[idx];
        if (pos < 0) return;
        int last = dense[size - 1];
        dense[pos] = last;
        posInDense[last] = pos;
        posInDense[idx] = -1;
        size--;
    }

    /**
     * O(1) uniform sample. Consumes exactly one {@code rng.nextInt(size)} call.
     * Returns null iff the eligible set is empty — caller produces E|503|GRID_FULL.
     */
    public synchronized Position sample(Random rng) {
        if (size == 0) return null;
        int idx = dense[rng.nextInt(size)];
        return new Position(idx / height, idx % height);
    }

    public synchronized int eligibleCount() { return size; }

    /**
     * Re-evaluate eligibility for the 5×5 Moore bbox around (px, py).
     * Called from WorldWebSocketHandler after every successful trySetEntity
     * and from DeathFinalizer after every death. Toroidal wrap via Math.floorMod.
     */
    public synchronized void notifyChanged(int px, int py) {
        for (int dy = -DIRTY_BBOX_RADIUS; dy <= DIRTY_BBOX_RADIUS; dy++) {
            for (int dx = -DIRTY_BBOX_RADIUS; dx <= DIRTY_BBOX_RADIUS; dx++) {
                int cx = Math.floorMod(px + dx, width);
                int cy = Math.floorMod(py + dy, height);
                if (evaluateEligibility(cx, cy)) addInternal(cx, cy);
                else removeInternal(cx, cy);
            }
        }
    }

    private void removeInternal(int x, int y) {
        int idx = toIndex(x, y);
        int pos = posInDense[idx];
        if (pos < 0) return;
        int last = dense[size - 1];
        dense[pos] = last;
        posInDense[last] = pos;
        posInDense[idx] = -1;
        size--;
    }

    private boolean evaluateEligibility(int x, int y) {
        // Constraint 1: no occupant.
        Cell cell = worldGrid.getCell(x, y);
        if (cell.hasOccupant()) return false;
        // Constraint 2: not OVERCROWDED (read cellStatusCache bit 0).
        Map<Position, Byte> cache = environmentEngine.cellStatusCacheView();
        Byte status = cache.get(new Position(x, y));
        if (status != null && (status & 0x01) != 0) return false;
        // Constraint 3: placing here would not push any adjacent occupied
        // Moore neighbour from non-overcrowded → overcrowded.
        int threshold = simulationConfig.overcrowdingThreshold();
        for (Position nPos : worldGrid.getNeighbors(x, y)) {
            Cell nCell = worldGrid.getCell(nPos.x(), nPos.y());
            Entity occ = nCell.occupant();
            if (!(occ instanceof Particle) && !(occ instanceof Entity.BondedPair)) continue;
            int neighborCount = countOccupiedMooreNeighbours(nPos.x(), nPos.y());
            if (neighborCount == threshold - 1) return false;
        }
        return true;
    }

    private int countOccupiedMooreNeighbours(int x, int y) {
        int count = 0;
        for (Position nPos : worldGrid.getNeighbors(x, y)) {
            Entity occ = worldGrid.getCell(nPos.x(), nPos.y()).occupant();
            if (occ instanceof Particle || occ instanceof Entity.BondedPair) count++;
        }
        return count;
    }

    /** Test seam — re-run @PostConstruct logic after a worldGrid.clear(). */
    public synchronized void rebuildForTest() {
        Arrays.fill(posInDense, -1);
        size = 0;
        initialize();
    }
}
```

Notes:
- Use `synchronized(this)` on every public method touching dense/posInDense/size — concurrent `notifyChanged` calls can come from any thread that mutates the grid (WS inbound + tick handlers). Sub-microsecond critical sections; no contention concern.
- `Entity.BondedPair` is the actual sealed-interface subtype name used at SimulationEngine.processOvercrowding (line 876–882). If grep shows it's just `BondedPair`, drop the `Entity.` qualifier — match the existing import style of `BotRegistry.java`.
- Do NOT introduce `parallelStream()` anywhere (D-08, D-11).
- Do NOT call `cellStatusCache.clear()` here — caller (EnvironmentEngine) owns that.

2. Create `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` as a pure-JUnit unit test (no @SpringBootTest). Build a small `WorldGrid` (8×8) with stub `EnvironmentEngine` and `SimulationConfig` instances. Cover the 9 behaviour bullets above. For constraint-3, configure `overcrowdingThreshold=4` and use `WorldGrid.trySetEntity` directly to set up the 3-neighbour scenario, then call `eligibleCellIndex.notifyChanged(...)` and assert the bbox cells around the to-be-overcrowded neighbour are excluded.

Rebuild after every test mutation via `eligibleCellIndex.rebuildForTest()` to keep tests independent.

For the stub `EnvironmentEngine`, you can either use a spy that returns a fixed `Map<Position,Byte>` from `cellStatusCacheView()`, or instantiate a real EnvironmentEngine with a minimal config. Spy-with-Mockito is simpler and matches existing unit-test patterns in the codebase (see `BuffRegistryTest`).

3. Run the gate command listed in `<verify>`. The unit test must pass before the wave can advance.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/main/java/com/paralife/engine/EligibleCellIndex.java` exists and contains `@Component` on the class declaration.
    - `grep -c "public.*Position sample(Random" src/main/java/com/paralife/engine/EligibleCellIndex.java` ≥ 1
    - `grep -c "public.*void notifyChanged(int" src/main/java/com/paralife/engine/EligibleCellIndex.java` ≥ 1
    - `grep -c "rng.nextInt(size)" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1 (Pitfall 3 — exactly one RNG draw per sample)
    - `grep -c "Math.floorMod" src/main/java/com/paralife/engine/EligibleCellIndex.java` ≥ 2 (toroidal wrap on cx and cy)
    - `grep -c "@PostConstruct" src/main/java/com/paralife/engine/EligibleCellIndex.java` ≥ 1
    - `grep -c "DIRTY_BBOX_RADIUS = 2" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 0 (D-08/D-11 invariant)
    - `./gradlew compileJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"` exits 0
    - File `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` contains tests `addThenSampleReturnsAddedCell`, `removeIsO1AndDoesNotShift`, `sampleEmptyReturnsNull`, `seededSampleIsBitExact`, `constraint3RejectsCellsThatWouldOvercrowdNeighbour` (verifiable via `grep`).
  </acceptance_criteria>
  <done>EligibleCellIndex bean exists; @PostConstruct populates the dense set; notifyChanged maintains the 5×5 bbox; sample consumes exactly one RNG call; unit-test class passes 9+ tests including constraint-3 rejection and seeded determinism.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor WorldWebSocketHandler placement; delete MAX_PLACEMENT_ATTEMPTS; add PlacementDeterminismTest + PlacementDensityIntegrationTest</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/test/java/com/paralife/engine/PlacementDeterminismTest.java, src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (lines 85–195 — fields, constructors, buildRng, resetSeed; lines 440–495 — placement loop region to replace)
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (the bean from Task 1; confirm `sample(Random)` and `notifyChanged(int,int)` signatures)
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template for dual-run determinism @SpringBootTest)
    - src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java (high-density WS load harness analog — multi-client BlockingWebSocketClient pattern)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 137–186 — WorldWebSocketHandler refactor block)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 3 — RNG call count drops 2→1 per placement; Open Question 3 — golden-trace digest separately covered by Plan 04)
    - src/main/java/com/paralife/websocket/Frame.java (RegisterFrame, ErrorFrame shape — for the 503 response)
    - src/main/java/com/paralife/websocket/RejectionToken.java (GRID_FULL constant — preserved)
  </read_first>
  <behavior>
    - placementUsesIndexNotRetryLoop: handleRegister calls `eligibleCellIndex.sample(spawnRng)` once; never enters a retry loop.
    - sampleNullEmitsGridFull: when index returns null, handler sends `E|503|GRID_FULL` and (for fresh registrations) calls `admissionGate.releaseSlot()`.
    - successfulPlaceTriggersIndexNotify: after `worldGrid.trySetEntity` returns true, handler calls `eligibleCellIndex.notifyChanged(x,y)` exactly once.
    - traceLostRaceFallsBackToGridFull: in the (extremely rare) case where `trySetEntity` returns false despite the index reporting the cell eligible (mid-tick race with another writer), handler treats the placement as failed → emits `E|503|GRID_FULL`, releases the slot, returns. No retry, no second sample.
    - placementsAreBitExactAcrossTwoRuns (PlacementDeterminismTest, D-06): same `paralife.simulation.spawn.seed=42` + same registration sequence (via direct call to `handleRegister` or via WS round-trip) → identical placement positions across two runs in the same JVM.
    - densityFillReturnsGridFullWithoutRetryStorm (PlacementDensityIntegrationTest): register N bots until eligible set is exhausted; (N+1)th `r|` frame returns `E|503|GRID_FULL`; the placement path completed in O(N) not O(N × MAX_PLACEMENT_ATTEMPTS).
  </behavior>
  <action>
1. Modify `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:

(a) **Delete** line 88: `private static final int MAX_PLACEMENT_ATTEMPTS = 50;`

(b) **Add** field beside line 116: `private final EligibleCellIndex eligibleCellIndex;`

(c) **Modify** the @Autowired constructor at line 118. Add `EligibleCellIndex eligibleCellIndex` as the last parameter (after `AdmissionMetrics admissionMetrics`). Assign `this.eligibleCellIndex = eligibleCellIndex;` Update both alternate constructors (lines 153 and 170) to forward `null` for the new parameter so existing test wiring still compiles. (Alternative: provide a single shared constructor and have the alt-ctors delegate; whichever pattern matches existing style.)

(d) **Replace** lines 450–467 (the retry loop and GRID_FULL block) with:

```java
Position pos = eligibleCellIndex.sample(spawnRng);
if (pos == null) {
    if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
    if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
    sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
    return;
}
int x = pos.x();
int y = pos.y();
boolean placed = worldGrid.trySetEntity(x, y, particle);
if (!placed) {
    // D-05: lost-race fallback. The index said eligible; trySetEntity disagreed
    // (concurrent writer, e.g. mid-tick mutation). Treat as GRID_FULL — no retry.
    if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
    if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
    sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
    return;
}
eligibleCellIndex.notifyChanged(x, y);
```

The downstream code (line 469 onwards: `botRegistry.register(...)`, resume token, S-frame, log) is **unchanged**.

(e) Verify `spawnRng` and `resetSeed()` (line 187) are not touched — D-06 contract relies on them. The only RNG-call-count change is the placement path: was 2 × `nextInt` per attempt × up-to-50 attempts; now exactly 1 × `nextInt(size)` per registration.

2. Create `src/test/java/com/paralife/engine/PlacementDeterminismTest.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false"
})
class PlacementDeterminismTest {
    @Autowired WorldGrid worldGrid;
    @Autowired EligibleCellIndex eligibleCellIndex;
    @Autowired WorldWebSocketHandler handler;
    @Autowired BotRegistry botRegistry;

    @Test
    void placementsAreBitExactAcrossTwoRuns() {
        List<Position> a = driveRegistrations(50);
        resetAll();
        List<Position> b = driveRegistrations(50);
        assertThat(b).as("D-06 bit-exact contract").isEqualTo(a);
    }

    private List<Position> driveRegistrations(int n) {
        // Use a test seam on WorldWebSocketHandler that performs the placement
        // step without the full WS handshake. If no such seam exists, drive via
        // direct calls to handler.handleRegister(stubSession, RegisterFrame)
        // through a package-private method; or expose handler.placeForTest(rng).
        List<Position> placed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Position p = eligibleCellIndex.sample(handler.spawnRngForTest());
            assertThat(p).isNotNull();
            worldGrid.trySetEntity(p.x(), p.y(), com.paralife.world.Particle.spawn(
                "test-" + i, com.paralife.world.ParticleType.CATALYST, 100));
            eligibleCellIndex.notifyChanged(p.x(), p.y());
            placed.add(p);
        }
        return placed;
    }

    private void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        handler.resetSeed();
        eligibleCellIndex.rebuildForTest();
    }
}
```

If `spawnRngForTest()` does not already exist on `WorldWebSocketHandler`, add a package-private accessor:

```java
// in WorldWebSocketHandler.java, near resetSeed()
Random spawnRngForTest() { return spawnRng; }
```

Mark with package visibility (no modifier) so only same-package tests see it. This is consistent with the existing `resetSeed()` test seam.

3. Create `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` modelled on `HundredBotIntegrationTest.java`:

```java
package com.paralife.websocket;

import com.paralife.engine.EligibleCellIndex;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false",
    "paralife.world.width=16",
    "paralife.world.height=16"   // tiny grid → fast saturation
})
class PlacementDensityIntegrationTest {
    @LocalServerPort int port;
    @Autowired WorldGrid worldGrid;
    @Autowired EligibleCellIndex eligibleCellIndex;

    @Test
    void densityFillReturnsGridFullWithoutRetryStorm() throws Exception {
        // Connect WS clients and send r|<species> frames until the index empties.
        // Pattern: copy connection setup from HundredBotIntegrationTest.
        // Assert: when eligibleCount() reaches 0, the next r| frame returns
        // E|503|GRID_FULL within (say) 200ms. Pre-refactor would have spun the
        // 50-retry loop on every register attempt; index path makes the empty
        // case O(1).
        // Implementation detail: track frames received via a CountDownLatch +
        // BlockingWebSocketClient (existing test infrastructure).
        long start = System.nanoTime();
        int registered = registerUntilGridFull();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(registered).isGreaterThan(0);
        assertThat(eligibleCellIndex.eligibleCount()).isZero();
        // Sanity: empty-set rejection completes quickly. Pre-refactor: 50 RNG
        // draws + 50 lock acquires per rejection. Post-refactor: 1 sample call.
        assertThat(elapsedMs).as("no retry storm — saturation loop completes quickly").isLessThan(5_000);
    }

    private int registerUntilGridFull() throws Exception {
        // implementation: open WS clients, send register frames, count S| vs E|503 frames.
        // Re-use BlockingWebSocketClient + frame parsing from HundredBotIntegrationTest.
        // Return number of successful registrations.
        return /* see analog test */ 0;
    }
}
```

The `registerUntilGridFull` helper should follow the **exact** connection-and-frame-counting pattern in `HundredBotIntegrationTest` — read that test first, copy the WS client + frame-receiver scaffolding, then loop until you see `E|503|GRID_FULL`.

4. Run the gate command from `<verify>`. Both new tests must pass + the full suite must stay green.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.PlacementDeterminismTest" --tests "com.paralife.websocket.PlacementDensityIntegrationTest" --tests "com.paralife.websocket.WorldWebSocketHandlerTest" --tests "com.paralife.engine.EligibleCellIndexTest"</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "MAX_PLACEMENT_ATTEMPTS" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0 (constant deleted)
    - `grep -c "for (int attempt = 0" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0 (retry loop gone)
    - `grep -c "eligibleCellIndex.sample(spawnRng)" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "RejectionToken.GRID_FULL" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 2 (one for empty-index path, one for lost-race path; existing wire shape preserved)
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1
    - File `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` exists.
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` >= 1
    - `grep -c "placementsAreBitExactAcrossTwoRuns" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` == 1
    - File `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` exists.
    - `grep -c "GRID_FULL" src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` >= 1
    - `./gradlew test --tests "com.paralife.engine.PlacementDeterminismTest"` exits 0
    - `./gradlew test --tests "com.paralife.websocket.PlacementDensityIntegrationTest"` exits 0
    - `./gradlew test` exits 0 (full regression — all 166+ existing tests remain green)
  </acceptance_criteria>
  <done>WorldWebSocketHandler placement path uses EligibleCellIndex; MAX_PLACEMENT_ATTEMPTS deleted; retry loop deleted; GRID_FULL wire shape preserved on both empty-index and lost-race paths; PlacementDeterminismTest passes (D-06 contract); PlacementDensityIntegrationTest passes (no retry storm at saturation); full suite remains green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → WS handler | `r|<species>` inbound frame. Already validated by Phase 17 admission gate. |
| handler → grid mutation | `worldGrid.trySetEntity` is the atomic gate; index is a fast-path heuristic. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-01 | Information disclosure | `EligibleCellIndex` server-internal state | accept | Index is never echoed to clients; placement (x,y) is delivered in `S|<entityId>` per existing wire grammar — same disclosure surface as today. |
| T-19-02 | Denial of service | `notifyChanged` on every grid mutation | mitigate | 5×5 bbox cap = O(25) per event; synchronized on a single bean — sub-microsecond critical section. Per-event cost is documented; not unbounded. |
| T-19-03 | Tampering | Determinism contract under `paralife.simulation.spawn.seed` | accept | Seed is server-side config; no client influence. RNG is `java.util.Random` (not a CSPRNG by design — see RESEARCH §Security Domain). |
| T-19-04 | Repudiation | Placement determinism for benchmark replay | mitigate | `PlacementDeterminismTest` locks bit-exact contract; failure = visible regression in CI. |
</threat_model>

<verification>
- `./gradlew test` — full suite green (regression).
- `./gradlew test --tests "com.paralife.engine.EligibleCellIndex*" --tests "com.paralife.engine.PlacementDeterminism*" --tests "com.paralife.websocket.PlacementDensityIntegrationTest"` — Wave 0 tests for SCALE-06 all green.
- Manual grep gates listed in each task's `<acceptance_criteria>`.
- `MAX_PLACEMENT_ATTEMPTS` constant absent from any source file.
</verification>

<success_criteria>
- High-density runs avoid pathological spawn collision patterns: `PlacementDensityIntegrationTest` proves saturation→GRID_FULL completes in O(N) not O(N × 50).
- Deterministic placement: `PlacementDeterminismTest` asserts byte-identical placement across two runs at fixed seed.
- Three eligibility constraints actively enforced: covered by `EligibleCellIndexTest.constraint{1,2,3}*` cases.
- `RejectionToken.GRID_FULL` wire shape preserved (no new error codes; no constraint relaxation; no queue).
- Full regression suite (166+ tests) remains green.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-01-SUMMARY.md`.
</output>
