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
  - src/main/java/com/paralife/engine/DeathFinalizer.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
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
    - "EligibleCellIndex is notified at every grid mutation site — register, death (DeathFinalizer.finalize* AND SimulationEngine direct unregister sites), move (ActionResolver.resolveMove old + new pos), reproduce (resolveReproduce target), bonding (processInteractions setEntity/clearEntity), composite formation (lines 651–652), composite collapse (collapseToMember), dissolve (dissolveToParticles), revert (revertToBondedPair). Stale eligibility under churn is the single most-cited reviewer concern; this plan closes it."
    - "EligibleCellIndex @PostConstruct runs strictly after RockGenerator @PostConstruct via @DependsOn(\"rockGenerator\") — rocks are not seeded into the eligible set."
  artifacts:
    - path: src/main/java/com/paralife/engine/EligibleCellIndex.java
      provides: "Sparse-set eligible-cell index: O(1) add/remove/sample; @DependsOn(\"rockGenerator\") guarantees @PostConstruct order; notifyChanged(x,y) recomputes 5×5 dirty bbox using EnvironmentEngine.cellStatusCacheView() (hoisted once per call) + neighbour-count walk."
      min_lines: 140
    - path: src/test/java/com/paralife/engine/EligibleCellIndexTest.java
      provides: "Unit tests for add/remove/sample/empty + constraint 3 rejection + dual-run seed equality on a small (8×8) grid."
      min_lines: 80
    - path: src/test/java/com/paralife/engine/PlacementDeterminismTest.java
      provides: "@SpringBootTest with paralife.simulation.spawn.seed=42 — drives N registrations through handler.handleRegisterForTest(...) (a package-private seam) twice and asserts byte-equality of placement positions (D-06). Drives the real handleRegister code path, NOT a direct EligibleCellIndex sample call (closes review M2)."
      min_lines: 80
    - path: src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
      provides: "@SpringBootTest(webEnvironment=RANDOM_PORT) — fills grid to >50% via WS register frames, asserts no retry storm, asserts (N+1)th register receives E|503|GRID_FULL when eligible set empty."
      min_lines: 100
  key_links:
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected field; handleRegister calls eligibleCellIndex.sample(spawnRng) instead of the 50-retry loop; eligibleCellIndex.notifyChanged after successful trySetEntity"
      pattern: "eligibleCellIndex\\.sample\\(spawnRng\\)"
    - from: src/main/java/com/paralife/engine/DeathFinalizer.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected; eligibleCellIndex.notifyChanged(x,y) called immediately after every worldGrid.clearEntity(x,y) in finalize*Death methods"
      pattern: "eligibleCellIndex\\.notifyChanged"
    - from: src/main/java/com/paralife/engine/ActionResolver.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected; eligibleCellIndex.notifyChanged(oldPos.x, oldPos.y) AND notifyChanged(newPos.x, newPos.y) on every successful move; notifyChanged(target.x, target.y) on resolveReproduce child placement"
      pattern: "eligibleCellIndex\\.notifyChanged"
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected; eligibleCellIndex.notifyChanged after each worldGrid.setEntity / clearEntity in processInteractions (bonding lines 589–590), composite-formation (lines 651–652), collapseToMember (lines 695–701, 977), dissolveToParticles (line 1098), revertToBondedPair (line 1051), member-death (line 1139)"
      pattern: "eligibleCellIndex\\.notifyChanged"
    - from: src/main/java/com/paralife/engine/EligibleCellIndex.java
      to: src/main/java/com/paralife/engine/EnvironmentEngine.java
      via: "cellStatusCacheView() read once per notifyChanged invocation (HOISTED outside the 5×5 inner loop per review O3); neighbour-count walk for constraint-3"
      pattern: "cellStatusCacheView\\(\\)"
    - from: src/main/java/com/paralife/engine/EligibleCellIndex.java
      to: src/main/java/com/paralife/world/WorldGrid.java
      via: "@PostConstruct walks worldGrid.getCell(x,y) once at startup AFTER RockGenerator's @PostConstruct (locked via @DependsOn)"
      pattern: "worldGrid\\.getCell"
---

<objective>
Replace the 50-retry random-scan placement of bot register/respawn (lines 450–467 of `WorldWebSocketHandler`) with an O(1) sparse-set free-cell index that enforces three eligibility constraints (no occupant; not OVERCROWDED; placement here would not push an adjacent occupied cell over the overcrowding threshold). Promote `paralife.simulation.spawn.seed` to a tested bit-exact placement contract. Closes SCALE-06.

Purpose: At >50% grid occupancy, the existing 50-retry loop hits O(1/(1−density)) collisions and degrades pathologically. Phase 21 benchmark targets (1000+ bots) are unreachable without this fix.
Output: New `EligibleCellIndex` bean (with `@DependsOn("rockGenerator")` per review H2); refactored `handleRegister` placement; full lifecycle hook coverage at DeathFinalizer + ActionResolver + SimulationEngine grid-mutation sites (per review H1); three new test files; `MAX_PLACEMENT_ATTEMPTS` constant deleted; `EnvironmentEngine.cellStatusCacheView()` (already exists at line 1399) read once per `notifyChanged` (per review O3).

**REVIEWS revisions applied:**
- H1: notifyChanged hooks added at every grid-mutation site — DeathFinalizer.finalize*, ActionResolver.resolveMove (old + new pos) + resolveReproduce, SimulationEngine.processInteractions/collapseToMember/dissolveToParticles/revertToBondedPair/handleMemberDeath, processOvercrowding death (line 977).
- H2: `@DependsOn("rockGenerator")` on EligibleCellIndex locks @PostConstruct order.
- M1: import paths corrected to `Entity.Particle` / `Entity.BondedPair` (sealed-inner types per `src/main/java/com/paralife/world/Entity.java` line 18).
- M2: PlacementDeterminismTest drives via `WorldWebSocketHandler.handleRegisterForTest(...)` package-private seam, not direct `eligibleCellIndex.sample(...)`.
- O3 (opencode MEDIUM): `cellStatusCacheView()` reference hoisted above the 5×5 loop in `notifyChanged`.
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
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md
@src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
@src/main/java/com/paralife/engine/EnvironmentEngine.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/SpawnConfig.java
@src/main/java/com/paralife/engine/SimulationConfig.java
@src/main/java/com/paralife/engine/DeathFinalizer.java
@src/main/java/com/paralife/engine/ActionResolver.java
@src/main/java/com/paralife/engine/SimulationEngine.java
@src/main/java/com/paralife/world/WorldGrid.java
@src/main/java/com/paralife/world/RockGenerator.java
@src/main/java/com/paralife/world/Cell.java
@src/main/java/com/paralife/world/Entity.java
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

From src/main/java/com/paralife/world/Entity.java (CONFIRMED via file inspection — line 18):
```java
public sealed interface Entity permits Entity.Particle, Entity.Rock, Entity.Nutrient, Entity.BondedPair, Entity.CompositeMember { ... }
// Subtypes are SEALED INNER TYPES — qualified access is `Entity.Particle`, `Entity.BondedPair`, etc.
// (Review M1: planner samples that imported `com.paralife.world.Particle` are wrong.
//  The qualified form `Entity.Particle` is the correct one — match what `SimulationEngine`
//  and `DeathFinalizer` already use.)
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
```

From src/main/java/com/paralife/world/RockGenerator.java:
```java
@Component
public class RockGenerator {
    @PostConstruct  // line 49 — populates rocks BEFORE EligibleCellIndex must run
    public void initialize() { ... }
}
```

From src/main/java/com/paralife/engine/BotRegistry.java:
```java
public void register(String sessionId, String entityId, Position position);  // line 62
public Collection<BotState> getAllBots();                                     // line 195
public void clear();                                                           // existing test helper
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:
```java
private static final int MAX_PLACEMENT_ATTEMPTS = 50;        // line 88 — DELETE
private Random spawnRng;                                       // line 116 — keep
public void resetSeed();                                       // line 187 — test-only; keep
@Autowired ctor signature line 118 — add EligibleCellIndex parameter
```

Mutation sites that MUST notify EligibleCellIndex (per review H1 — full audit):

`src/main/java/com/paralife/engine/DeathFinalizer.java`:
- line 88  — `worldGrid.clearEntity(x, y)` in `finalizeParticleDeath`
- line 113 — `worldGrid.clearEntity(x, y)` in `finalizeBondedPairDeath`
- (re-grep `clearEntity` in DeathFinalizer for any others; current file shows two)

`src/main/java/com/paralife/engine/ActionResolver.java`:
- line 483 — `clearEntity(oldPos)` in successful move
- line 497 — `setEntity(target)` in successful move
- line 530, 572 — `setEntity` after consume / reproduce
- line 569, 582, 753 — `setEntity(target, child)` reproduce children + bonus children
- line 962 — `clearEntity(pos)` (composite/dissolve path)
- line 968 — `setEntity(target, member)` (composite path)

`src/main/java/com/paralife/engine/SimulationEngine.java`:
- line 589 — `setEntity(primary, bondedPair)` bonding
- line 590 — `clearEntity(secondary)` bonding (the absorbed Particle)
- line 651–652 — `setEntity(member1)`, `setEntity(member2)` composite formation
- line 695, 698, 701 — `setEntity(...)` in collapseToMember (3 placement variants)
- line 756, 783 — `setEntity(updated)` in energy decay (occupant identity unchanged → notify is harmless idempotent; SAFE TO INCLUDE)
- line 886, 888 — `setEntity` overcrowding penalty (occupant identity unchanged → idempotent)
- line 977 — `clearEntity(pos)` shared cleanup (line ~973 also has `botRegistry.unregisterByEntity`)
- line 1051 — `setEntity(pos, bondedPair)` revertToBondedPair
- line 1098 — `setEntity(pos, particle)` dissolveToParticles
- line 1139 — `clearEntity(pos)` member-death cleanup
- line 1185 — `setEntity(x, y, Nutrient.spawn(id))` nutrient spawn — this affects constraint-1 eligibility; INCLUDE notify

WorldWebSocketHandler placement path:
- After successful `worldGrid.trySetEntity` in `handleRegister`, call `eligibleCellIndex.notifyChanged(x, y)`.

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
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (review H2 — @DependsOn(\"rockGenerator\"); review O3 — hoist cellStatusCacheView)
    - src/main/java/com/paralife/world/Entity.java (CONFIRM sealed inner types `Entity.Particle`, `Entity.BondedPair`)
    - src/main/java/com/paralife/world/RockGenerator.java (confirm `@Component` + `@PostConstruct` + bean name `rockGenerator` for @DependsOn)
    - src/main/java/com/paralife/engine/BotRegistry.java (lines 1–30 — @Component class shell + Logger pattern)
    - src/main/java/com/paralife/world/WorldGrid.java (lines 50–115 — trySetEntity, setEntity, clearEntity, getCell, getNeighbors)
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (line 1399 — cellStatusCacheView; lines 875–895 — neighbour-count walk shape)
    - src/main/java/com/paralife/engine/SimulationConfig.java (overcrowdingThreshold accessor)
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
    - cellStatusCacheReadOnlyOnceInLoop: invoke notifyChanged with a spy on EnvironmentEngine; assert cellStatusCacheView() called exactly 1 time per notifyChanged invocation (review O3 — no per-cell allocation churn).
  </behavior>
  <action>
1. Create `src/main/java/com/paralife/engine/EligibleCellIndex.java`:

```java
package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
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
 * <p>Lifecycle hooks (REVIEWS.md H1) — every grid-mutation site MUST call
 * {@link #notifyChanged(int, int)}: WorldWebSocketHandler.handleRegister,
 * DeathFinalizer.finalize*Death, ActionResolver.resolveMove (old+new pos),
 * ActionResolver.resolveReproduce (target), SimulationEngine.processInteractions
 * bonding (lines 589–590), composite-formation (lines 651–652),
 * collapseToMember (lines 695–701, 977), dissolveToParticles (line 1098),
 * revertToBondedPair (line 1051), handleMemberDeath (line 1139),
 * processNutrientSpawning setEntity (line 1185).
 *
 * <p>Caller supplies the seeded {@link Random} (i.e. {@code spawnRng}); this
 * bean owns no RNG state. {@code sample} consumes exactly one
 * {@code rng.nextInt(size)} call per draw — see RESEARCH.md Pitfall 3.
 *
 * <p>Constraint-2 staleness: per RESEARCH.md Pitfall 1, the
 * {@code cellStatusCache} read can be 1 tick stale at registration mid-tick.
 * The atomic gate is still {@code worldGrid.trySetEntity}; the index is a
 * fast-path heuristic.
 *
 * <p>Lock ordering invariant (review O6): WS inbound thread acquires this
 * bean's monitor inside {@link #notifyChanged} and then calls into
 * {@code WorldGrid} (which has its own RRWL). Tick threads must NOT call
 * into this index while holding the {@code WorldGrid} write lock — the
 * established direction is index-monitor → grid-read-lock.
 */
@Component
@DependsOn("rockGenerator")  // REVIEWS.md H2 — guarantee @PostConstruct order
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
     * Walk the grid once after rocks are placed. {@code @DependsOn("rockGenerator")}
     * forces this @PostConstruct to run AFTER RockGenerator.@PostConstruct so the
     * rock map is final and rock cells are correctly excluded by constraint 1.
     */
    @PostConstruct
    public void initialize() {
        // cellStatusCache may be empty at startup (no tick has run); constraint-2
        // therefore evaluates as "not overcrowded" by default — correct.
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (evaluateEligibility(x, y, /*hoistedCache*/ Map.of())) addInternal(x, y);
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
        removeInternal(x, y);
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
     * Re-evaluate eligibility for the 5×5 Moore bbox around (px, py). Called
     * from every grid-mutation site (see class Javadoc). Toroidal wrap via
     * {@code Math.floorMod}. The {@code cellStatusCacheView()} reference is
     * hoisted ONCE per invocation (review O3) — no per-cell allocation churn.
     */
    public synchronized void notifyChanged(int px, int py) {
        // REVIEWS O3: hoist cache view above the 5×5 loop. One Map view per
        // call, not 25.
        Map<Position, Byte> hoistedCache = environmentEngine.cellStatusCacheView();
        for (int dy = -DIRTY_BBOX_RADIUS; dy <= DIRTY_BBOX_RADIUS; dy++) {
            for (int dx = -DIRTY_BBOX_RADIUS; dx <= DIRTY_BBOX_RADIUS; dx++) {
                int cx = Math.floorMod(px + dx, width);
                int cy = Math.floorMod(py + dy, height);
                if (evaluateEligibility(cx, cy, hoistedCache)) addInternal(cx, cy);
                else removeInternal(cx, cy);
            }
        }
    }

    /**
     * Eligibility predicate. Pass-in {@code cellStatusCache} avoids the per-cell
     * {@code unmodifiableMap} allocation flagged in review O3.
     */
    private boolean evaluateEligibility(int x, int y, Map<Position, Byte> cellStatusCache) {
        // Constraint 1: no occupant (excludes Rock, Particle, BondedPair, CompositeMember, Nutrient).
        Cell cell = worldGrid.getCell(x, y);
        if (cell.hasOccupant()) return false;
        // Constraint 2: not OVERCROWDED (read cellStatusCache bit 0).
        Byte status = cellStatusCache.get(new Position(x, y));
        if (status != null && (status & 0x01) != 0) return false;
        // Constraint 3: placing here would not push any adjacent occupied
        // Moore neighbour from non-overcrowded → overcrowded.
        int threshold = simulationConfig.overcrowdingThreshold();
        for (Position nPos : worldGrid.getNeighbors(x, y)) {
            Cell nCell = worldGrid.getCell(nPos.x(), nPos.y());
            Entity occ = nCell.occupant();
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

    /** Test seam — re-run @PostConstruct logic after a worldGrid.clear(). */
    public synchronized void rebuildForTest() {
        Arrays.fill(posInDense, -1);
        size = 0;
        initialize();
    }
}
```

Notes:
- `synchronized(this)` on every public method touching dense/posInDense/size — concurrent `notifyChanged` calls can come from any thread that mutates the grid (WS inbound + tick handlers). Sub-microsecond critical sections; no contention concern.
- `Entity.Particle` and `Entity.BondedPair` — confirmed sealed-inner-type qualified names per `src/main/java/com/paralife/world/Entity.java` line 18 (REVIEWS M1 fix).
- Do NOT introduce `parallelStream()` anywhere (D-08, D-11).
- `@DependsOn("rockGenerator")` — guarantees `RockGenerator.@PostConstruct` runs first (REVIEWS H2 fix).
- `cellStatusCacheView()` is read EXACTLY ONCE per `notifyChanged` invocation (REVIEWS O3 fix). Constraint-2 reads from the hoisted map.

2. Create `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` as a pure-JUnit unit test (no @SpringBootTest). Build a small `WorldGrid` (8×8) with stub `EnvironmentEngine` and `SimulationConfig` instances. Cover the 10 behaviour bullets above (including the new `cellStatusCacheReadOnlyOnceInLoop` from review O3). For constraint-3, configure `overcrowdingThreshold=4` and use `WorldGrid.trySetEntity` directly to set up the 3-neighbour scenario, then call `eligibleCellIndex.notifyChanged(...)` and assert the bbox cells around the to-be-overcrowded neighbour are excluded.

For `cellStatusCacheReadOnlyOnceInLoop`, use Mockito spy on the stub `EnvironmentEngine` and `verify(envEngine, times(1)).cellStatusCacheView()` after a single `notifyChanged` call.

For the stub `EnvironmentEngine`, you can either use a spy that returns a fixed `Map<Position,Byte>` from `cellStatusCacheView()`, or instantiate a real EnvironmentEngine with a minimal config. Spy-with-Mockito is simpler and matches existing unit-test patterns in the codebase (see `BuffRegistryTest`).

Rebuild after every test mutation via `eligibleCellIndex.rebuildForTest()` to keep tests independent.

3. Run the gate command listed in `<verify>`. The unit test must pass before the wave can advance.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/main/java/com/paralife/engine/EligibleCellIndex.java` exists and contains `@Component` on the class declaration.
    - `grep -c "@DependsOn(\"rockGenerator\")" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1 (REVIEWS H2)
    - `grep -c "Entity.Particle\\|Entity.BondedPair" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 2 (REVIEWS M1 — qualified sealed-inner-type names)
    - `grep -c "import com.paralife.world.Particle\\|import com.paralife.world.ParticleType" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 0 (REVIEWS M1 — wrong imports must be absent)
    - `grep -c "public.*Position sample(Random" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1
    - `grep -c "public.*void notifyChanged(int" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1
    - `grep -c "rng.nextInt(size)" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1 (Pitfall 3 — exactly one RNG draw per sample)
    - `grep -c "Math.floorMod" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 2 (toroidal wrap on cx and cy)
    - `grep -c "@PostConstruct" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1
    - `grep -c "DIRTY_BBOX_RADIUS = 2" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 0 (D-08/D-11 invariant)
    - cellStatusCacheView is hoisted once (REVIEWS O3): `grep -cE "cellStatusCacheView\\(\\)" src/main/java/com/paralife/engine/EligibleCellIndex.java` <= 1 within the `notifyChanged` method body. Verify by reading the method.
    - `./gradlew compileJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"` exits 0
    - File `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` contains tests `addThenSampleReturnsAddedCell`, `removeIsO1AndDoesNotShift`, `sampleEmptyReturnsNull`, `seededSampleIsBitExact`, `constraint3RejectsCellsThatWouldOvercrowdNeighbour`, `cellStatusCacheReadOnlyOnceInLoop` (verifiable via `grep`).
  </acceptance_criteria>
  <done>EligibleCellIndex bean exists with @DependsOn("rockGenerator"); @PostConstruct populates the dense set after rocks are seeded; notifyChanged maintains the 5×5 bbox with cellStatusCacheView() hoisted outside the inner loop; sample consumes exactly one RNG call; sealed-inner-type imports correct; unit-test class passes 10+ tests including review-O3 single-cache-read assertion.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor WorldWebSocketHandler placement; wire notifyChanged at all grid-mutation sites; delete MAX_PLACEMENT_ATTEMPTS; add PlacementDeterminismTest + PlacementDensityIntegrationTest</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/ActionResolver.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/test/java/com/paralife/engine/PlacementDeterminismTest.java, src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (lines 85–195 — fields, constructors, buildRng, resetSeed; lines 440–495 — placement loop region to replace)
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (the bean from Task 1; confirm `sample(Random)` and `notifyChanged(int,int)` signatures)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (entire file — every `worldGrid.clearEntity` site)
    - src/main/java/com/paralife/engine/ActionResolver.java (entire file — every `worldGrid.setEntity` / `clearEntity` site)
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 580–600 bonding; 645–660 composite formation; 690–710 collapseToMember; 970–985 shared cleanup; 1045–1060 revert; 1095–1105 dissolve; 1130–1145 member-death cleanup; 1180–1195 nutrient spawn)
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template for dual-run determinism @SpringBootTest)
    - src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java (high-density WS load harness analog — multi-client BlockingWebSocketClient pattern)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 137–186 — WorldWebSocketHandler refactor block)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (review H1 — full lifecycle audit; review M2 — PlacementDeterminismTest must drive handleRegister, not bypass it)
    - src/main/java/com/paralife/websocket/Frame.java (RegisterFrame, ErrorFrame shape — for the 503 response)
    - src/main/java/com/paralife/websocket/RejectionToken.java (GRID_FULL constant — preserved)
  </read_first>
  <behavior>
    - placementUsesIndexNotRetryLoop: handleRegister calls `eligibleCellIndex.sample(spawnRng)` once; never enters a retry loop.
    - sampleNullEmitsGridFull: when index returns null, handler sends `E|503|GRID_FULL` and (for fresh registrations) calls `admissionGate.releaseSlot()`.
    - successfulPlaceTriggersIndexNotify: after `worldGrid.trySetEntity` returns true, handler calls `eligibleCellIndex.notifyChanged(x,y)` exactly once.
    - lostRaceFallsBackToGridFull: in the (extremely rare) case where `trySetEntity` returns false despite the index reporting the cell eligible (mid-tick race with another writer), handler treats the placement as failed → emits `E|503|GRID_FULL`, releases the slot, returns. No retry, no second sample.
    - deathTriggersIndexNotify: a Particle dying (DeathFinalizer.finalizeParticleDeath path) → after `worldGrid.clearEntity(x,y)`, eligibleCellIndex.notifyChanged(x,y) is called with the same (x,y). Verified by spying on EligibleCellIndex in an integration test.
    - moveTriggersIndexNotifyOnBothPositions: ActionResolver.resolveMove successful move → notifyChanged(oldPos.x, oldPos.y) AND notifyChanged(newPos.x, newPos.y). Old-cell may now be eligible; new-cell becomes ineligible.
    - placementsAreBitExactAcrossTwoRuns (PlacementDeterminismTest, D-06): same `paralife.simulation.spawn.seed=42` + same registration sequence DRIVEN VIA `handler.handleRegisterForTest(sessionId, registerFrame)` (NOT via direct `eligibleCellIndex.sample`) → identical placement positions across two runs. (REVIEWS M2.)
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

(e) Verify `spawnRng` and `resetSeed()` (line 187) are not touched — D-06 contract relies on them.

(f) **Add a package-private test seam** for PlacementDeterminismTest (REVIEWS M2):

```java
// in WorldWebSocketHandler.java, near resetSeed() at line 187
/**
 * Test-only seam: drive the placement step of handleRegister with a stub session.
 * REVIEWS M2 — PlacementDeterminismTest must exercise this real code path,
 * not bypass it via direct EligibleCellIndex.sample calls. Returns the placed
 * Position, or empty if rejected (eligible set empty / lost race).
 *
 * Implementation: extract the `sample → trySetEntity → notifyChanged` sub-step
 * of handleRegister into a private helper `attemptPlacement(Particle particle)`
 * returning Optional<Position>; expose a package-private wrapper here that
 * accepts a synthetic entityId and invokes the same helper.
 */
Optional<Position> attemptPlacementForTest(String entityId, Entity.ParticleType type, int initialEnergy) {
    Particle particle = Particle.spawn(entityId, type, initialEnergy);
    return attemptPlacement(particle);
}

Random spawnRngForTest() { return spawnRng; }   // package-private; matches resetSeed() style
```

The `attemptPlacement(Particle)` helper is a **refactor extraction** of the placement sub-step — the production `handleRegister` should call it too, ensuring the test exercises the same code surface.

2. Modify `src/main/java/com/paralife/engine/DeathFinalizer.java` (REVIEWS H1):

(a) Add field: `private final EligibleCellIndex eligibleCellIndex;`
(b) Add `EligibleCellIndex eligibleCellIndex` parameter to the constructor. Assign in body. Note: this constructor signature change must be propagated (see step 5(d) below) to any back-compat ctor in SimulationEngine that constructs DeathFinalizer.
(c) After `worldGrid.clearEntity(x, y);` at line 88 (`finalizeParticleDeath`), insert:
```java
eligibleCellIndex.notifyChanged(x, y);
```
(d) After `worldGrid.clearEntity(x, y);` at line 113 (`finalizeBondedPairDeath`), insert the same call. Use the same (x,y) — that's the bonded-pair primary cell. (BondedPair occupies one cell at the primary position; the secondary was already cleared at bonding time.)
(e) `finalizeCompositeMemberDeath` — re-grep `worldGrid.clearEntity` in that file to be sure no further site is missed; if any additional clearEntity exists, hook notifyChanged there too.

3. Modify `src/main/java/com/paralife/engine/ActionResolver.java` (REVIEWS H1):

(a) Add field: `private final EligibleCellIndex eligibleCellIndex;`
(b) Add `EligibleCellIndex eligibleCellIndex` parameter to the @Autowired constructor. Assign in body.
(c) For every `worldGrid.setEntity` / `worldGrid.clearEntity` call, add a `notifyChanged` immediately after. Specific sites (line numbers may shift after edits — re-grep first):

| Line | Call | notifyChanged target |
|------|------|----------------------|
| 483 | `clearEntity(ra.bot.position().x(), ra.bot.position().y())` move-from | (ra.bot.position().x(), ra.bot.position().y()) |
| 497 | `setEntity(target.x(), target.y(), placed)` move-to | (target.x(), target.y()) |
| 530 | `setEntity(pos.x(), pos.y(), updated)` consume self-update — occupant identity unchanged | (pos.x(), pos.y()) — idempotent, safe |
| 534, 536 | `clearEntity(nutrientPos)` / `setEntity(nutrientPos, depleted)` — Nutrient still occupies | (nutrientPos.x(), nutrientPos.y()) |
| 569 | `setEntity(target, child)` reproduce child | (target.x(), target.y()) |
| 572 | `setEntity(ra.bot.position(), updatedParent)` parent-update | (ra.bot.position().x(), ra.bot.position().y()) |
| 582 | `setEntity(bonusTarget, bonusChild)` bonus child | (bonusTarget.x(), bonusTarget.y()) |
| 634, 636 | `clearEntity(nutrientPos)` / `setEntity(nutrientPos, depleted)` (same shape as 534/536) | (nutrientPos.x(), nutrientPos.y()) |
| 675, 679, 682 | `setEntity(targetPos, damaged)` combat damage | (targetPos.x(), targetPos.y()) |
| 694 | `setEntity(pos, ...)` per-bot result | (pos.x(), pos.y()) |
| 753 | `setEntity(target, child)` (likely composite reproduce / variant) | (target.x(), target.y()) |
| 962 | `clearEntity(pos)` (composite/dissolve cleanup variant) | (pos.x(), pos.y()) |
| 968 | `setEntity(target, member)` composite placement | (target.x(), target.y()) |

`notifyChanged` is idempotent for occupant-identity-unchanged updates (constraint-1 evaluates identically; constraint-2/3 may flip if FLAG_OVERCROWDED transitioned this tick — calling it is harmless and correct).

4. Modify `src/main/java/com/paralife/engine/SimulationEngine.java` (REVIEWS H1):

(a) Add field: `private final EligibleCellIndex eligibleCellIndex;`
(b) Add `EligibleCellIndex eligibleCellIndex` parameter to the constructor. Assign in body.
(c) Hook `notifyChanged` after EVERY `worldGrid.setEntity` / `clearEntity` site — re-run `grep -n "setEntity\|clearEntity" src/main/java/com/paralife/engine/SimulationEngine.java` to enumerate. Sites confirmed in `<interfaces>` above (lines 589, 590, 651, 652, 695, 698, 701, 756, 783, 886, 888, 977, 1051, 1098, 1139, 1185).
(d) **Back-compat constructor (REVIEWS M6):** SimulationEngine has a 9-arg / 13-arg back-compat constructor (lines 172–202) that internally constructs `new DeathFinalizer(...)`. After step 2(b) added `EligibleCellIndex` to DeathFinalizer's ctor, that internal construction call must also pass `eligibleCellIndex`. Update it: `new DeathFinalizer(worldGrid, botRegistry, this.buffRegistry, compositeRegistry, this.hooks, this, eligibleCellIndex)` (or whatever the actual back-compat signature is — re-read SimulationEngine lines 172–202 before patching). Failing to update this triggers a compile error across all back-compat-using tests.

5. Modify `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` constructor: confirmed that EligibleCellIndex is constructor-injected (already done in step 1(b)/(c)). No further edits.

6. Create `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` (REVIEWS M2 — drives handleRegister, NOT direct sample):

```java
package com.paralife.engine;

import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        // Run 1
        resetAll();
        List<Position> a = driveRegistrations(50);
        // Run 2
        resetAll();
        List<Position> b = driveRegistrations(50);
        // D-06 contract: same seed + same arrival order → byte-identical placements.
        assertThat(b).as("D-06 bit-exact contract").isEqualTo(a);
    }

    private List<Position> driveRegistrations(int n) {
        // REVIEWS M2: drive via handler.attemptPlacementForTest — exercises the
        // real handleRegister placement sub-step (sample → trySetEntity →
        // notifyChanged), NOT a direct EligibleCellIndex.sample call.
        List<Position> placed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String entityId = "test-" + i;
            Optional<Position> p = handler.attemptPlacementForTest(
                entityId,
                Entity.ParticleType.values()[i % 3],
                /*initialEnergy*/ 100);
            assertThat(p).isPresent();
            placed.add(p.get());
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

If `Entity.ParticleType` is the actual sealed-inner-enum name (or if it's a top-level `ParticleType` in `com.paralife.world`), match the existing import style in the codebase — re-grep `ParticleType.values()` before writing. The point is the test drives `handler.attemptPlacementForTest`, not `eligibleCellIndex.sample`.

7. Create `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` modelled on `HundredBotIntegrationTest.java`:

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

8. Run the gate command from `<verify>`. Both new tests must pass + the full suite must stay green.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "MAX_PLACEMENT_ATTEMPTS" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0 (constant deleted)
    - `grep -c "for (int attempt = 0" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0 (retry loop gone)
    - `grep -c "eligibleCellIndex.sample(spawnRng)" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/engine/DeathFinalizer.java` >= 2 (REVIEWS H1)
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/engine/ActionResolver.java` >= 8 (REVIEWS H1 — cover all setEntity/clearEntity sites)
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/engine/SimulationEngine.java` >= 12 (REVIEWS H1 — cover all setEntity/clearEntity sites including 1185 nutrient spawn)
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -c "RejectionToken.GRID_FULL" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 2 (one for empty-index path, one for lost-race path)
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1
    - `grep -c "attemptPlacementForTest" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1 (REVIEWS M2 test seam)
    - File `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` exists.
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` >= 1
    - `grep -c "placementsAreBitExactAcrossTwoRuns" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` == 1
    - `grep -c "attemptPlacementForTest" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` >= 1 (REVIEWS M2 — test exercises real handleRegister sub-step)
    - `grep -c "eligibleCellIndex.sample" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` == 0 (REVIEWS M2 — test must NOT bypass via direct sample)
    - File `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` exists.
    - `grep -c "GRID_FULL" src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` >= 1
    - `./gradlew test` exits 0 (full regression — all 166+ existing tests remain green plus 3 new SCALE-06 tests)
  </acceptance_criteria>
  <done>WorldWebSocketHandler placement path uses EligibleCellIndex; MAX_PLACEMENT_ATTEMPTS deleted; retry loop deleted; GRID_FULL wire shape preserved on both empty-index and lost-race paths; lifecycle hooks wired at every grid-mutation site (DeathFinalizer + ActionResolver + SimulationEngine — REVIEWS H1 closed); back-compat ctor updated for new DeathFinalizer signature (REVIEWS M6); PlacementDeterminismTest passes via the real handleRegister code path (REVIEWS M2); PlacementDensityIntegrationTest passes (no retry storm at saturation); full suite remains green.</done>
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
| T-19-02 | Denial of service | `notifyChanged` on every grid mutation | mitigate | 5×5 bbox cap = O(25) per event; synchronized on a single bean — sub-microsecond critical section. cellStatusCacheView() hoisted (REVIEWS O3) so per-call allocation is single-Map-view. |
| T-19-03 | Tampering | Determinism contract under `paralife.simulation.spawn.seed` | accept | Seed is server-side config; no client influence. RNG is `java.util.Random` (not a CSPRNG by design — see RESEARCH §Security Domain). |
| T-19-04 | Repudiation | Placement determinism for benchmark replay | mitigate | `PlacementDeterminismTest` locks bit-exact contract (driving the real `handleRegister` sub-step per REVIEWS M2); failure = visible regression in CI. |
| T-19-04a | Tampering | Stale eligibility set under entity churn | mitigate | REVIEWS H1 — notifyChanged hooks at EVERY grid-mutation site (register, death, move, reproduce, bonding, composite formation/collapse/dissolve/revert, member-death, nutrient spawn). Verified by grep counters in acceptance criteria. |
</threat_model>

<verification>
- `./gradlew test` — full suite green (regression).
- `./gradlew test --tests "com.paralife.engine.EligibleCellIndex*" --tests "com.paralife.engine.PlacementDeterminism*" --tests "com.paralife.websocket.PlacementDensityIntegrationTest"` — Wave 0 tests for SCALE-06 all green.
- Manual grep gates listed in each task's `<acceptance_criteria>`.
- `MAX_PLACEMENT_ATTEMPTS` constant absent from any source file.
- All grid-mutation sites notify the index — no stale-eligibility regression possible (REVIEWS H1 closed).
</verification>

<success_criteria>
- High-density runs avoid pathological spawn collision patterns: `PlacementDensityIntegrationTest` proves saturation→GRID_FULL completes in O(N) not O(N × 50).
- Deterministic placement: `PlacementDeterminismTest` asserts byte-identical placement across two runs at fixed seed via the real handleRegister sub-step (REVIEWS M2).
- Three eligibility constraints actively enforced: covered by `EligibleCellIndexTest.constraint{1,2,3}*` cases.
- `RejectionToken.GRID_FULL` wire shape preserved (no new error codes; no constraint relaxation; no queue).
- No stale-eligibility under churn (REVIEWS H1 closed) — every grid mutation re-evaluates the 5×5 bbox.
- @PostConstruct ordering locked (REVIEWS H2 closed) — rocks never seeded into the eligible set.
- Full regression suite (166+ tests) remains green plus the 3 new Wave 0 tests.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-01-SUMMARY.md`.
</output>
</content>
