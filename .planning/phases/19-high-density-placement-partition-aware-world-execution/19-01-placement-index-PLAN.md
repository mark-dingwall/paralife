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
  - src/main/java/com/paralife/websocket/AdmissionMetrics.java
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
    - "EligibleCellIndex @PostConstruct runs strictly after RockGenerator @PostConstruct via @DependsOn(\"rockGenerator\") — rocks are not seeded into the eligible set. RockGenerator.initialize() is verified synchronous (no @Async, no executor submit) — REVIEWS L2."
    - "Lost-race fallback (sample returned a position but trySetEntity disagreed) increments `paralife.placement.lost-race.total` for Phase 21 benchmark observation (REVIEWS L3)."
    - "Every manual `new SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler` site in src/test/** is enumerated and forwarded with the new EligibleCellIndex parameter — `./gradlew compileTestJava` exits 0 (REVIEWS MED-2)."
  artifacts:
    - path: src/main/java/com/paralife/engine/EligibleCellIndex.java
      provides: "Sparse-set eligible-cell index: O(1) add/remove/sample; @DependsOn(\"rockGenerator\") guarantees @PostConstruct order; notifyChanged(x,y) recomputes 5×5 dirty bbox using EnvironmentEngine.cellStatusCacheView() (hoisted once per call) + neighbour-count walk. Lock-order invariant documented in Javadoc (REVIEWS L1): index-monitor → grid-read-lock; tick threads must NOT invert."
      min_lines: 140
    - path: src/test/java/com/paralife/engine/EligibleCellIndexTest.java
      provides: "Unit tests for add/remove/sample/empty + constraint 3 rejection + dual-run seed equality on a small (8×8) grid."
      min_lines: 80
    - path: src/test/java/com/paralife/engine/PlacementDeterminismTest.java
      provides: "@SpringBootTest with paralife.simulation.spawn.seed=42 — drives N registrations through handler.attemptPlacementForTest(...) (a package-private seam) twice and asserts byte-equality of placement positions (D-06). Drives the real handleRegister code path."
      min_lines: 80
    - path: src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
      provides: "@SpringBootTest(webEnvironment=RANDOM_PORT) — fills grid to >50% via WS register frames, asserts no retry storm, asserts (N+1)th register receives E|503|GRID_FULL when eligible set empty."
      min_lines: 100
  key_links:
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected field; handleRegister calls eligibleCellIndex.sample(spawnRng) instead of the 50-retry loop; eligibleCellIndex.notifyChanged after successful trySetEntity; lost-race branch increments paralife.placement.lost-race.total"
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
      via: "@PostConstruct walks worldGrid.getCell(x,y) once at startup AFTER RockGenerator's @PostConstruct (locked via @DependsOn; REVIEWS L2 — synchronous verified)"
      pattern: "worldGrid\\.getCell"
---

<objective>
Replace the 50-retry random-scan placement of bot register/respawn (lines 450–467 of `WorldWebSocketHandler`) with an O(1) sparse-set free-cell index that enforces three eligibility constraints (no occupant; not OVERCROWDED; placement here would not push an adjacent occupied cell over the overcrowding threshold). Promote `paralife.simulation.spawn.seed` to a tested bit-exact placement contract. Closes SCALE-06.

Purpose: At >50% grid occupancy, the existing 50-retry loop hits O(1/(1−density)) collisions and degrades pathologically. Phase 21 benchmark targets (1000+ bots) are unreachable without this fix.
Output: New `EligibleCellIndex` bean (with `@DependsOn("rockGenerator")` per review H2); refactored `handleRegister` placement; full lifecycle hook coverage at DeathFinalizer + ActionResolver + SimulationEngine grid-mutation sites (per review H1); three new test files; `MAX_PLACEMENT_ATTEMPTS` constant deleted; `EnvironmentEngine.cellStatusCacheView()` (already exists at line 1399) read once per `notifyChanged` (per review O3); lost-race fallback metric (REVIEWS L3); ctor cascade enumeration (REVIEWS MED-2); RockGenerator sync verification (REVIEWS L2); lock-order Javadoc (REVIEWS L1).

**REVIEWS revisions applied (this revision):**
- H1: notifyChanged hooks added at every grid-mutation site — DeathFinalizer.finalize*, ActionResolver.resolveMove (old + new pos) + resolveReproduce, SimulationEngine.processInteractions/collapseToMember/dissolveToParticles/revertToBondedPair/handleMemberDeath, processOvercrowding death (line 977).
- H2: `@DependsOn("rockGenerator")` on EligibleCellIndex locks @PostConstruct order.
- M1: import paths corrected to `Entity.Particle` / `Entity.BondedPair` (sealed-inner types).
- M2: PlacementDeterminismTest drives via `WorldWebSocketHandler.attemptPlacementForTest(...)` package-private seam, not direct `eligibleCellIndex.sample(...)`.
- O3: `cellStatusCacheView()` reference hoisted above the 5×5 loop in `notifyChanged`.
- **MED-2 (gemini/opencode — ctor cascade fragility):** Task 2 enumerates every existing manual constructor invocation site of `SimulationEngine` / `ActionResolver` / `DeathFinalizer` / `WorldWebSocketHandler` in `src/test/**/*.java` via a deterministic grep, and prescribes the explicit forwarding update for each. Acceptance gate: `./gradlew compileTestJava` exits 0.
- **L1 (gemini/codex — lock ordering):** EligibleCellIndex Javadoc documents the lock-order invariant explicitly: index-monitor → grid-read-lock. Tick threads must NOT acquire index-monitor while holding worldGrid write lock.
- **L2 (opencode — RockGenerator sync verification):** Task 1 includes a grep step confirming `RockGenerator.initialize()` is synchronous (no `@Async`, no executor submit). `@DependsOn("rockGenerator")` only enforces `@PostConstruct` order; if RockGenerator deferred its work via async, the rock map would not be final when EligibleCellIndex.@PostConstruct fires.
- **L3 (claude — lost-race metric):** Step 1(d)'s lost-race branch (sample returned a position but trySetEntity disagreed) increments `paralife.placement.lost-race.total` Counter on `AdmissionMetrics`. Lets Phase 21 benchmark observe race incidence.
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
@src/main/java/com/paralife/websocket/AdmissionMetrics.java
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
    public void initialize() { ... }   // REVIEWS L2 — verified synchronous (no @Async, no executor submit)
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

From src/main/java/com/paralife/websocket/AdmissionMetrics.java (REVIEWS L3 — new counter to add):
```java
// Existing: incRejected(token, session)
// NEW: incLostRace() — micrometer Counter "paralife.placement.lost-race.total"
public void incLostRace();
```

Mutation sites that MUST notify EligibleCellIndex (per review H1 — full audit):

`src/main/java/com/paralife/engine/DeathFinalizer.java`:
- line 88  — `worldGrid.clearEntity(x, y)` in `finalizeParticleDeath`
- line 113 — `worldGrid.clearEntity(x, y)` in `finalizeBondedPairDeath`

`src/main/java/com/paralife/engine/ActionResolver.java`:
- line 483 — `clearEntity(oldPos)` in successful move
- line 497 — `setEntity(target)` in successful move
- line 530, 572 — `setEntity` after consume / reproduce
- line 569, 582, 753 — `setEntity(target, child)` reproduce children + bonus children
- line 962 — `clearEntity(pos)` (composite/dissolve path)
- line 968 — `setEntity(target, member)` (composite path)

`src/main/java/com/paralife/engine/SimulationEngine.java`:
- line 589 — `setEntity(primary, bondedPair)` bonding
- line 590 — `clearEntity(secondary)` bonding
- line 651–652 — composite formation
- line 695, 698, 701 — collapseToMember
- line 756, 783 — energy decay (idempotent)
- line 886, 888 — overcrowding penalty (idempotent)
- line 977 — shared cleanup
- line 1051 — revertToBondedPair
- line 1098 — dissolveToParticles
- line 1139 — member-death cleanup
- line 1185 — nutrient spawn

WorldWebSocketHandler placement path:
- After successful `worldGrid.trySetEntity` in `handleRegister`, call `eligibleCellIndex.notifyChanged(x, y)`.
- Lost-race branch (`!placed`): call `admissionMetrics.incLostRace()` before sending GRID_FULL (REVIEWS L3).

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create EligibleCellIndex sparse-set + Wave 0 unit tests + RockGenerator sync verification (REVIEWS L2) + lock-order Javadoc (REVIEWS L1)</name>
  <files>src/main/java/com/paralife/engine/EligibleCellIndex.java, src/test/java/com/paralife/engine/EligibleCellIndexTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (target — confirm absent before creating)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 1 — full sparse-set sketch lines 446–495; §Pitfall 5 — 5×5 radius rationale)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 28–86 — class shell + neighbour-count walk excerpt)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H2, O3, L1 — lock ordering documentation, L2 — RockGenerator sync verify)
    - src/main/java/com/paralife/world/Entity.java (CONFIRM sealed inner types `Entity.Particle`, `Entity.BondedPair`)
    - src/main/java/com/paralife/world/RockGenerator.java (entire file — REVIEWS L2 — confirm initialize() runs synchronously: no `@Async` annotation, no `executor.submit` / `executor.execute` / `CompletableFuture.runAsync` / `Thread.start` calls; the @PostConstruct body must complete its rock writes before returning)
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
    - addIsIdempotent / removeIsIdempotent: as expected.
    - sampleEmptyReturnsNull: fresh index → sample(new Random(0)) == null (triggers GRID_FULL path).
    - seededSampleIsBitExact: build two identical indices, populate with same cells in same order, sample with `new Random(42L)` → both return same Position.
    - notifyChangedRespects5x5Bbox: place an entity at centre of an 8×8 grid and call notifyChanged(cx,cy); cells at distance ≤2 (toroidally) re-evaluated; cells at distance >2 untouched.
    - constraint1RejectsOccupiedCells: place an entity at (1,1) via worldGrid.trySetEntity; notifyChanged(1,1); index does not contain (1,1).
    - constraint2RejectsOvercrowdedCells: build a fake cellStatusCache where (2,2) has bit 0 set; notifyChanged(2,2); index does not contain (2,2).
    - constraint3RejectsCellsThatWouldOvercrowdNeighbour: with overcrowdingThreshold=4, surround cell (4,4) with exactly 3 occupied Moore neighbours so any candidate cell that is a Moore-neighbour of (4,4) must be rejected.
    - cellStatusCacheReadOnlyOnceInLoop: invoke notifyChanged with a spy on EnvironmentEngine; assert cellStatusCacheView() called exactly 1 time per notifyChanged invocation (REVIEWS O3).
  </behavior>
  <action>

**STEP 0 — REVIEWS L2 RockGenerator synchronous verification (pre-flight):**

Run this check before creating EligibleCellIndex. The output must show NO matches:
```bash
grep -nE "@Async|executor\\.submit|executor\\.execute|CompletableFuture\\.runAsync|Thread\\.start|new Thread\\(" src/main/java/com/paralife/world/RockGenerator.java
```

If any match appears, STOP — `@DependsOn("rockGenerator")` is insufficient because the rock writes may not be complete when the dependent bean's `@PostConstruct` fires. Escalate to operator. If empty (expected), proceed.

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
 * <p><b>Lock ordering invariant (REVIEWS L1 — gemini/codex):</b>
 * {@code notifyChanged} acquires this bean's monitor (synchronized) and then
 * calls into {@link WorldGrid#getCell} which takes the WorldGrid read lock.
 * The established direction is therefore <b>index-monitor → grid-read-lock</b>.
 * Tick handlers that hold the WorldGrid <b>write</b> lock (i.e. inside a
 * {@code worldGrid.setEntity} / {@code clearEntity} call) MUST NOT call
 * {@link #notifyChanged} from within that critical section — the wiring at
 * every call site invokes {@code notifyChanged} <i>after</i> the WorldGrid
 * mutation returns, never inside it. Inverting this order can deadlock the
 * tick pipeline against a concurrent WS-thread placement.
 *
 * <p>Initialisation order (REVIEWS H2 + L2): {@code @DependsOn("rockGenerator")}
 * forces this bean's {@code @PostConstruct} to run after RockGenerator's.
 * RockGenerator.initialize() is synchronous (no @Async, no executor submit
 * — verified at plan-execution time per REVIEWS L2), so rocks are guaranteed
 * present in WorldGrid by the time we walk it.
 */
@Component
@DependsOn("rockGenerator")
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

    @PostConstruct
    public void initialize() {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (evaluateEligibility(x, y, /*hoistedCache*/ Map.of())) addInternal(x, y);
            }
        }
        log.info("EligibleCellIndex initialised: {} eligible cells of {} total", size, width * height);
    }

    private int toIndex(int x, int y) { return x * height + y; }

    public synchronized void add(int x, int y) { addInternal(x, y); }

    private void addInternal(int x, int y) {
        int idx = toIndex(x, y);
        if (posInDense[idx] >= 0) return;
        dense[size] = idx;
        posInDense[idx] = size;
        size++;
    }

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

    public synchronized Position sample(Random rng) {
        if (size == 0) return null;
        int idx = dense[rng.nextInt(size)];
        return new Position(idx / height, idx % height);
    }

    public synchronized int eligibleCount() { return size; }

    /**
     * Re-evaluate eligibility for the 5×5 Moore bbox around (px, py). Toroidal
     * wrap via {@code Math.floorMod}. The {@code cellStatusCacheView()}
     * reference is hoisted ONCE per invocation (REVIEWS O3).
     *
     * <p>Lock-order: this method holds {@code synchronized(this)} (the index
     * monitor) and then calls into {@code worldGrid.getCell(...)} (which
     * acquires the grid read lock). See class Javadoc — never invert.
     */
    public synchronized void notifyChanged(int px, int py) {
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

    private boolean evaluateEligibility(int x, int y, Map<Position, Byte> cellStatusCache) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.hasOccupant()) return false;
        Byte status = cellStatusCache.get(new Position(x, y));
        if (status != null && (status & 0x01) != 0) return false;
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

    public synchronized void rebuildForTest() {
        Arrays.fill(posInDense, -1);
        size = 0;
        initialize();
    }
}
```

2. Create `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` as a pure-JUnit unit test (no @SpringBootTest). Build a small `WorldGrid` (8×8) with stub `EnvironmentEngine` and `SimulationConfig` instances. Cover the 10 behaviour bullets above (including `cellStatusCacheReadOnlyOnceInLoop` from review O3). For constraint-3, configure `overcrowdingThreshold=4`. For `cellStatusCacheReadOnlyOnceInLoop`, use Mockito spy on the stub `EnvironmentEngine` and `verify(envEngine, times(1)).cellStatusCacheView()` after a single `notifyChanged` call.

3. Run the gate command listed in `<verify>`. The unit test must pass before the wave can advance.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"</automated>
  </verify>
  <acceptance_criteria>
    - **REVIEWS L2 RockGenerator sync verification:** `grep -cE "@Async|executor\\.submit|executor\\.execute|CompletableFuture\\.runAsync|new Thread\\(|Thread\\.start" src/main/java/com/paralife/world/RockGenerator.java` == 0 (RockGenerator.initialize is synchronous; @DependsOn is sufficient)
    - File `src/main/java/com/paralife/engine/EligibleCellIndex.java` exists and contains `@Component`.
    - `grep -c "@DependsOn(\"rockGenerator\")" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -cE "Lock ordering invariant|index-monitor.*grid-read-lock|index-monitor → grid-read-lock" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1 (REVIEWS L1 — lock-order Javadoc)
    - `grep -c "Entity.Particle\\|Entity.BondedPair" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 2
    - `grep -c "import com.paralife.world.Particle\\|import com.paralife.world.ParticleType" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 0
    - `grep -c "public.*Position sample(Random" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1
    - `grep -c "public.*void notifyChanged(int" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1
    - `grep -c "rng.nextInt(size)" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -c "Math.floorMod" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 2
    - `grep -c "@PostConstruct" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1
    - `grep -c "DIRTY_BBOX_RADIUS = 2" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 0
    - cellStatusCacheView is hoisted once: `grep -cE "cellStatusCacheView\\(\\)" src/main/java/com/paralife/engine/EligibleCellIndex.java` <= 1 within the `notifyChanged` method body.
    - `./gradlew compileJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"` exits 0
    - File `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` contains tests `addThenSampleReturnsAddedCell`, `removeIsO1AndDoesNotShift`, `sampleEmptyReturnsNull`, `seededSampleIsBitExact`, `constraint3RejectsCellsThatWouldOvercrowdNeighbour`, `cellStatusCacheReadOnlyOnceInLoop` (verifiable via grep).
  </acceptance_criteria>
  <done>EligibleCellIndex bean exists with @DependsOn("rockGenerator") (REVIEWS H2); RockGenerator confirmed synchronous (REVIEWS L2); lock-order invariant documented in Javadoc (REVIEWS L1); @PostConstruct populates the dense set after rocks are seeded; notifyChanged maintains the 5×5 bbox with cellStatusCacheView() hoisted (REVIEWS O3); sample consumes exactly one RNG call; sealed-inner-type imports correct (REVIEWS M1); unit-test class passes 10+ tests including the single-cache-read assertion.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor WorldWebSocketHandler placement; wire notifyChanged at all grid-mutation sites; add lost-race metric (REVIEWS L3); enumerate and fix manual ctor sites in tests (REVIEWS MED-2); delete MAX_PLACEMENT_ATTEMPTS; add PlacementDeterminismTest + PlacementDensityIntegrationTest</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/ActionResolver.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/websocket/AdmissionMetrics.java, src/test/java/com/paralife/engine/PlacementDeterminismTest.java, src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (lines 85–195 — fields, constructors, buildRng, resetSeed; lines 440–495 — placement loop region to replace)
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (the bean from Task 1; confirm `sample(Random)` and `notifyChanged(int,int)` signatures)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (entire file — every `worldGrid.clearEntity` site)
    - src/main/java/com/paralife/engine/ActionResolver.java (entire file — every `worldGrid.setEntity` / `clearEntity` site)
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 580–600 bonding; 645–660 composite formation; 690–710 collapseToMember; 970–985 shared cleanup; 1045–1060 revert; 1095–1105 dissolve; 1130–1145 member-death cleanup; 1180–1195 nutrient spawn)
    - src/main/java/com/paralife/websocket/AdmissionMetrics.java (existing Counter pattern — to add `incLostRace()` per REVIEWS L3)
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template for dual-run determinism @SpringBootTest)
    - src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java (high-density WS load harness analog)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 137–186 — WorldWebSocketHandler refactor block)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H1 — full lifecycle audit; M2 — PlacementDeterminismTest must drive handleRegister; MED-2 — ctor cascade; L3 — lost-race metric)
    - src/main/java/com/paralife/websocket/Frame.java (RegisterFrame, ErrorFrame shape — for the 503 response)
    - src/main/java/com/paralife/websocket/RejectionToken.java (GRID_FULL constant — preserved)
  </read_first>
  <behavior>
    - placementUsesIndexNotRetryLoop: handleRegister calls `eligibleCellIndex.sample(spawnRng)` once; never enters a retry loop.
    - sampleNullEmitsGridFull: when index returns null, handler sends `E|503|GRID_FULL` and (for fresh registrations) calls `admissionGate.releaseSlot()`.
    - successfulPlaceTriggersIndexNotify: after `worldGrid.trySetEntity` returns true, handler calls `eligibleCellIndex.notifyChanged(x,y)` exactly once.
    - lostRaceFallsBackToGridFullAndCountsMetric: in the rare case where `trySetEntity` returns false despite the index reporting the cell eligible, handler increments `paralife.placement.lost-race.total` AND emits `E|503|GRID_FULL` AND releases the slot. No retry, no second sample. (REVIEWS L3.)
    - deathTriggersIndexNotify: a Particle dying → after `worldGrid.clearEntity`, `eligibleCellIndex.notifyChanged` is called.
    - moveTriggersIndexNotifyOnBothPositions: ActionResolver.resolveMove successful move → notifyChanged on both old and new pos.
    - placementsAreBitExactAcrossTwoRuns (PlacementDeterminismTest, D-06): same `paralife.simulation.spawn.seed=42` + same registration sequence DRIVEN VIA `handler.attemptPlacementForTest` → identical placement positions across two runs. (REVIEWS M2.)
    - densityFillReturnsGridFullWithoutRetryStorm (PlacementDensityIntegrationTest): saturation→GRID_FULL completes in O(N).
  </behavior>
  <action>

**STEP 0 — REVIEWS MED-2 ctor cascade pre-flight:**

Run this command and save the output:
```bash
grep -nE "new (SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler)\\(" src/test/java -r
```

Each `file:line` in the output points to a manual instantiation that will need updating after step 1's production-side ctor changes. After step 1 lands, visit each site and forward the new `EligibleCellIndex` parameter (insert in the same positional slot as in the production constructor signature). For tests that use `@SpringBootTest` with `@Autowired`, no edit is needed — Spring autowires the new bean.

Acceptance gate: `./gradlew compileTestJava` exits 0.

1. Modify `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:

(a) **Delete** line 88: `private static final int MAX_PLACEMENT_ATTEMPTS = 50;`

(b) **Add** field beside line 116: `private final EligibleCellIndex eligibleCellIndex;`

(c) **Modify** the @Autowired constructor at line 118. Add `EligibleCellIndex eligibleCellIndex` as the last parameter. Update both alternate constructors (lines 153 and 170) to forward `null` for the new parameter.

(d) **Replace** lines 450–467 with:

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
    // D-05 lost-race fallback (REVIEWS L3): the index said eligible; trySetEntity
    // disagreed (concurrent writer, e.g. mid-tick mutation). Treat as GRID_FULL —
    // no retry. Emit metric so Phase 21 benchmark can observe race incidence.
    if (admissionMetrics != null) admissionMetrics.incLostRace();
    if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
    if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
    sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
    return;
}
eligibleCellIndex.notifyChanged(x, y);
```

The downstream code (line 469 onwards: `botRegistry.register(...)`, resume token, S-frame, log) is **unchanged**.

(e) Verify `spawnRng` and `resetSeed()` (line 187) are not touched — D-06 contract relies on them.

(f) **Add a package-private test seam** (REVIEWS M2):

```java
Optional<Position> attemptPlacementForTest(String entityId, Entity.ParticleType type, int initialEnergy) {
    Particle particle = Particle.spawn(entityId, type, initialEnergy);
    return attemptPlacement(particle);
}
```

The `attemptPlacement(Particle)` helper is a **refactor extraction** of the placement sub-step — the production `handleRegister` calls it too.

2. Modify `src/main/java/com/paralife/websocket/AdmissionMetrics.java` (REVIEWS L3 — new counter):

(a) Add a `Counter lostRace` field and registration code (mirror the existing `incRejected` Counter pattern):

```java
this.lostRace = Counter.builder("paralife.placement.lost-race.total")
    .description("Placement: sampled cell lost the trySetEntity race (D-05 lost-race fallback)")
    .register(meterRegistry);
```

(b) Add a method:

```java
public void incLostRace() {
    lostRace.increment();
}
```

If `AdmissionMetrics` doesn't exist or doesn't carry a `MeterRegistry`, route the counter through the existing observability bean — re-grep for `Counter.builder(\"paralife` to find the exact pattern.

3. Modify `src/main/java/com/paralife/engine/DeathFinalizer.java` (REVIEWS H1):

(a) Add field: `private final EligibleCellIndex eligibleCellIndex;`
(b) Add `EligibleCellIndex eligibleCellIndex` parameter to the constructor.
(c) After `worldGrid.clearEntity(x, y);` at line 88, insert:
```java
eligibleCellIndex.notifyChanged(x, y);
```
(d) After `worldGrid.clearEntity(x, y);` at line 113, insert the same call.
(e) Re-grep `worldGrid.clearEntity` in DeathFinalizer to be sure no further site is missed.

4. Modify `src/main/java/com/paralife/engine/ActionResolver.java` (REVIEWS H1):

(a) Add field: `private final EligibleCellIndex eligibleCellIndex;`
(b) Add `EligibleCellIndex eligibleCellIndex` parameter to the @Autowired constructor.
(c) For every `worldGrid.setEntity` / `worldGrid.clearEntity` call, add a `notifyChanged` immediately after. Specific sites:

| Line | Call | notifyChanged target |
|------|------|----------------------|
| 483 | `clearEntity(oldPos)` | (oldPos.x, oldPos.y) |
| 497 | `setEntity(target, placed)` | (target.x, target.y) |
| 530 | `setEntity(pos, updated)` consume | (pos.x, pos.y) |
| 534, 536 | nutrient consume/depleted | (nutrientPos.x, nutrientPos.y) |
| 569 | reproduce primary child | (target.x, target.y) |
| 572 | parent-update | (ra.bot.position().x, .y) |
| 582 | bonus child | (bonusTarget.x, bonusTarget.y) |
| 634, 636 | nutrient (variant) | (nutrientPos.x, nutrientPos.y) |
| 675, 679, 682 | combat damage | (targetPos.x, targetPos.y) |
| 694 | per-bot result | (pos.x, pos.y) |
| 753 | reproduce variant | (target.x, target.y) |
| 962 | clearEntity composite | (pos.x, pos.y) |
| 968 | composite placement | (target.x, target.y) |

5. Modify `src/main/java/com/paralife/engine/SimulationEngine.java` (REVIEWS H1):

(a) Add field: `private final EligibleCellIndex eligibleCellIndex;`
(b) Add `EligibleCellIndex eligibleCellIndex` parameter to the constructor.
(c) Hook `notifyChanged` after EVERY `worldGrid.setEntity` / `clearEntity` site at lines 589, 590, 651, 652, 695, 698, 701, 756, 783, 886, 888, 977, 1051, 1098, 1139, 1185.
(d) **Back-compat constructor (REVIEWS M6):** SimulationEngine has back-compat ctors (lines 172–202) that internally construct `new DeathFinalizer(...)`. Update the internal call to pass `eligibleCellIndex`.

6. **REVIEWS MED-2 — visit every site from STEP 0's grep output** in src/test/** and forward the new `EligibleCellIndex` parameter to the manual `new SimulationEngine(...)` / `new ActionResolver(...)` / `new DeathFinalizer(...)` / `new WorldWebSocketHandler(...)` calls. Tests using `@Autowired` need no change.

7. Create `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` (REVIEWS M2):

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
        resetAll();
        List<Position> a = driveRegistrations(50);
        resetAll();
        List<Position> b = driveRegistrations(50);
        assertThat(b).as("D-06 bit-exact contract").isEqualTo(a);
    }

    private List<Position> driveRegistrations(int n) {
        List<Position> placed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String entityId = "test-" + i;
            Optional<Position> p = handler.attemptPlacementForTest(
                entityId,
                Entity.ParticleType.values()[i % 3],
                100);
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

8. Create `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` modelled on `HundredBotIntegrationTest.java`. Spin up WS clients, register all, count `S|` vs `E|503` frames, assert saturation→GRID_FULL completes quickly. Implement `registerUntilGridFull` using BlockingWebSocketClient + frame parsing patterns from the analog test.

9. Run `./gradlew compileTestJava` first as the REVIEWS MED-2 fast gate. Then run `./gradlew test`.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "MAX_PLACEMENT_ATTEMPTS" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0
    - `grep -c "for (int attempt = 0" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0
    - `grep -c "eligibleCellIndex.sample(spawnRng)" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "admissionMetrics.incLostRace" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1 (REVIEWS L3 — lost-race counter incremented exactly once in the lost-race branch)
    - `grep -c "incLostRace\\|paralife.placement.lost-race.total" src/main/java/com/paralife/websocket/AdmissionMetrics.java` >= 2 (REVIEWS L3 — Counter registration + method)
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/engine/DeathFinalizer.java` >= 2
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/engine/ActionResolver.java` >= 8
    - `grep -c "eligibleCellIndex.notifyChanged" src/main/java/com/paralife/engine/SimulationEngine.java` >= 12
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -c "RejectionToken.GRID_FULL" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 2
    - `grep -c "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1
    - `grep -c "attemptPlacementForTest" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - **REVIEWS MED-2 ctor cascade gate:** `./gradlew compileTestJava` exits 0 (every manual `new SimulationEngine|new ActionResolver|new DeathFinalizer|new WorldWebSocketHandler` site forwards the new EligibleCellIndex parameter)
    - File `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` exists.
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` >= 1
    - `grep -c "placementsAreBitExactAcrossTwoRuns" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` == 1
    - `grep -c "attemptPlacementForTest" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` >= 1
    - `grep -c "eligibleCellIndex.sample" src/test/java/com/paralife/engine/PlacementDeterminismTest.java` == 0
    - File `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` exists.
    - `grep -c "GRID_FULL" src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` >= 1
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>WorldWebSocketHandler placement uses EligibleCellIndex; MAX_PLACEMENT_ATTEMPTS deleted; retry loop deleted; GRID_FULL preserved on both empty-index and lost-race paths; lost-race metric `paralife.placement.lost-race.total` exposed (REVIEWS L3); lifecycle hooks wired at every grid-mutation site (REVIEWS H1); back-compat ctor updated (REVIEWS M6); every manual ctor site in src/test/** forwarded (REVIEWS MED-2 closed via `./gradlew compileTestJava` green); PlacementDeterminismTest passes via real handleRegister (REVIEWS M2); PlacementDensityIntegrationTest passes; full suite green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → WS handler | `r|<species>` inbound frame. Already validated by Phase 17 admission gate. |
| handler → grid mutation | `worldGrid.trySetEntity` is the atomic gate; index is a fast-path heuristic. |
| index monitor → grid read lock | Documented invariant (REVIEWS L1): `synchronized(EligibleCellIndex)` is acquired before WorldGrid read lock. Tick handlers must not invert. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-01 | Information disclosure | `EligibleCellIndex` server-internal state | accept | Index is never echoed to clients. |
| T-19-02 | Denial of service | `notifyChanged` on every grid mutation | mitigate | 5×5 bbox cap = O(25) per event; sub-microsecond critical section; `cellStatusCacheView()` hoisted (REVIEWS O3). |
| T-19-03 | Tampering | Determinism contract under `paralife.simulation.spawn.seed` | accept | Server-side config; no client influence. |
| T-19-04 | Repudiation | Placement determinism for benchmark replay | mitigate | `PlacementDeterminismTest` (REVIEWS M2). |
| T-19-04a | Tampering | Stale eligibility set under entity churn | mitigate | REVIEWS H1 — notifyChanged hooks at EVERY grid-mutation site. |
| T-19-04b | DoS | Lock ordering inversion under tick-thread + WS-thread contention | mitigate | REVIEWS L1 — Javadoc documents `index-monitor → grid-read-lock`. Tick handlers call notifyChanged AFTER worldGrid mutations return, never inside the WorldGrid write lock. |
| T-19-04c | Tampering | RockGenerator async writes leave grid empty when EligibleCellIndex.@PostConstruct walks | mitigate | REVIEWS L2 — pre-flight grep gate confirms RockGenerator.initialize() is fully synchronous. |
| T-19-04d | Repudiation | Lost-race incidence invisible to operators | mitigate | REVIEWS L3 — `paralife.placement.lost-race.total` Counter exposed for Phase 21 benchmark observation. |
| T-19-04e | Compile-error regression | Manual ctor sites in src/test/** miss the new EligibleCellIndex parameter | mitigate | REVIEWS MED-2 — pre-flight grep enumerates every site; `./gradlew compileTestJava` gate catches misses. |
</threat_model>

<verification>
- `./gradlew test` — full suite green.
- `./gradlew compileTestJava` — REVIEWS MED-2 ctor cascade gate green.
- `./gradlew test --tests "com.paralife.engine.EligibleCellIndex*" --tests "com.paralife.engine.PlacementDeterminism*" --tests "com.paralife.websocket.PlacementDensityIntegrationTest"` — Wave 0 tests for SCALE-06 all green.
- `MAX_PLACEMENT_ATTEMPTS` constant absent.
- All grid-mutation sites notify the index.
- RockGenerator confirmed synchronous (REVIEWS L2).
- Lost-race metric `paralife.placement.lost-race.total` exposed (REVIEWS L3).
- Lock-order invariant documented in Javadoc (REVIEWS L1).
</verification>

<success_criteria>
- High-density runs avoid pathological spawn collision patterns: `PlacementDensityIntegrationTest` proves saturation→GRID_FULL completes in O(N).
- Deterministic placement: `PlacementDeterminismTest` asserts byte-identical placement across two runs at fixed seed via the real handleRegister sub-step (REVIEWS M2).
- Three eligibility constraints actively enforced: covered by `EligibleCellIndexTest`.
- `RejectionToken.GRID_FULL` wire shape preserved.
- No stale-eligibility under churn (REVIEWS H1).
- @PostConstruct ordering locked (REVIEWS H2 + L2 — RockGenerator confirmed sync).
- Lock-order invariant documented (REVIEWS L1).
- Lost-race metric exposed (REVIEWS L3).
- Manual test ctor sites enumerated and forwarded (REVIEWS MED-2 — `./gradlew compileTestJava` green).
- Full regression suite remains green plus 3 new Wave 0 tests.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-01-SUMMARY.md`.
</output>
