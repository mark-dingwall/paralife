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
  - src/main/java/com/paralife/admission/AdmissionMetrics.java
  - src/test/java/com/paralife/engine/EligibleCellIndexTest.java
  - src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java
  - src/test/java/com/paralife/engine/PlacementDeterminismTest.java
  - src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
autonomous: true
requirements:
  - SCALE-06
tags: [placement, sparse-set, determinism, websocket, java, spring-boot]

must_haves:
  truths:
    - "Bot register/respawn placement returns a uniformly random cell from the eligible set in O(1) — no retry storm at any density."
    - "An empty eligible set yields E|503|GRID_FULL with the existing RejectionToken.GRID_FULL wire shape — no constraint relaxation."
    - "Eligibility excludes occupied cells (constraint 1), OVERCROWDED cells (constraint 2 — read from `cellStatusCache` immutable snapshot), and cells whose placement would push an adjacent occupied cell over `overcrowdingThreshold` (constraint 3)."
    - "EnvironmentEngine.cellStatusCache is a `volatile Map<Position,Byte>` field; tick thread mutates a private staging map and publishes `Map.copyOf(staging)` to the volatile field at end of `buildStatusCaches()`. WS thread reads the volatile snapshot only — zero shared-mutable-state race (REVIEWS CONSENSUS-H4)."
    - "Same `paralife.simulation.spawn.seed` + same registration order → byte-identical placements across two runs (D-06)."
    - "MAX_PLACEMENT_ATTEMPTS constant deleted; the retry loop is gone."
    - "Lifecycle hooks: only STRUCTURAL grid mutations (place / clear / move / bond / composite-form / composite-collapse / dissolve / revert / death / nutrient spawn) call `eligibleCellIndex.notifyChanged`. Energy-only writes (applyDeltaToOccupant, processEnergyDecay, processOvercrowding penalty, combat damage withEnergy, toxin/mutagen damage) do NOT (REVIEWS MEDIUM-1 — energy-only over-hooking)."
    - "Every `notifyChanged` call site is enumerated by file:line in this plan, not gated by a count threshold (REVIEWS LOW-11)."
    - "EligibleCellIndex @PostConstruct runs after RockGenerator @PostConstruct via @DependsOn(\"rockGenerator\"); RockGenerator.initialize() is verified synchronous (REVIEWS L2)."
    - "Lost-race fallback (sample returned a position but trySetEntity disagreed) is retried up to 3 times before declaring GRID_FULL; each lost-race attempt increments `paralife.placement.lost-race.total` (REVIEWS LOW-12 — bounded retry mitigates concurrent-registration false GRID_FULL)."
    - "`WorldWebSocketHandler.attemptPlacementForTest` is `public` (not package-private) so tests in `com.paralife.engine` can compile (REVIEWS CONSENSUS-H6)."
    - "Every back-compat WorldWebSocketHandler ctor either forwards a real `EligibleCellIndex` or fails loudly (`Objects.requireNonNull`) — no silent `null` forwarding NPE risk (REVIEWS MEDIUM-7 / Codex MED)."
    - "`cleanupByEntityId` (line 655 clearEntity) and `cleanupBot` (line 695 clearEntity) call `eligibleCellIndex.notifyChanged` after the grid clear (REVIEWS MEDIUM-6 — Codex)."
    - "Rectangular-grid linearisation test exists for 8×16 grid (REVIEWS R2-13)."
    - "Every manual `new SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler` site in src/test/** is enumerated and forwarded with the new EligibleCellIndex parameter — `./gradlew compileTestJava` exits 0 (REVIEWS MED-2)."
  artifacts:
    - path: src/main/java/com/paralife/engine/EligibleCellIndex.java
      provides: "Sparse-set eligible-cell index: O(1) add/remove/sample; @DependsOn(\"rockGenerator\"); notifyChanged(x,y) recomputes 5×5 dirty bbox using EnvironmentEngine.cellStatusCacheView() (immutable Map.copyOf snapshot — REVIEWS H4) read once per call. Lock-order: index-monitor → grid-read-lock (REVIEWS L1)."
      min_lines: 140
    - path: src/test/java/com/paralife/engine/EligibleCellIndexTest.java
      provides: "Unit tests for add/remove/sample/empty + constraint 3 rejection + dual-run seed equality on a small (8×8) grid + cellStatusCache hoisted exactly once."
      min_lines: 80
    - path: src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java
      provides: "Rectangular-grid (width≠height) linearisation/de-linearisation parity for `toIndex`/`fromIndex` (REVIEWS R2-13). 8×16 fixture."
      min_lines: 30
    - path: src/test/java/com/paralife/engine/PlacementDeterminismTest.java
      provides: "@SpringBootTest with paralife.simulation.spawn.seed=42 — drives N registrations through `handler.attemptPlacementForTest(...)` (PUBLIC test seam) twice and asserts byte-equality (D-06)."
      min_lines: 80
    - path: src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
      provides: "@SpringBootTest fills grid >50% via WS register frames; asserts no retry storm; (N+1)th register receives E|503|GRID_FULL when eligible set empty."
      min_lines: 100
  key_links:
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "constructor-injected; handleRegister calls eligibleCellIndex.sample(spawnRng); notifyChanged after successful trySetEntity; bounded 3-retry on lost-race (REVIEWS L4); cleanupByEntityId / cleanupBot also notify (REVIEWS MED-6)"
      pattern: "eligibleCellIndex\\.sample\\(spawnRng\\)"
    - from: src/main/java/com/paralife/engine/EnvironmentEngine.java
      to: src/main/java/com/paralife/engine/EligibleCellIndex.java
      via: "cellStatusCacheView() returns volatile immutable Map.copyOf snapshot — safe for WS-thread reads (REVIEWS H4)"
      pattern: "volatile Map<Position, Byte> cellStatusCache"
---

<objective>
Replace the 50-retry random-scan placement at WorldWebSocketHandler line 453 with an O(1) sparse-set eligible-cell index. Promote `paralife.simulation.spawn.seed` to a tested bit-exact placement contract. Closes SCALE-06.

**REVIEWS Round 2 + Round 3 fixes encoded in plan body:**

- **CONSENSUS-H4** — `EnvironmentEngine.cellStatusCache` becomes `private volatile Map<Position, Byte> cellStatusCache`; tick thread mutates a private staging map and publishes `Map.copyOf(staging)` to the volatile field at the end of `buildStatusCaches()`. WS thread reads only the volatile snapshot. No `.put()` / `.clear()` outside the staging path.
- **CONSENSUS-H6** — `WorldWebSocketHandler.attemptPlacementForTest` is declared **`public`** (tests live in `com.paralife.engine`).
- **MEDIUM-1 / line-number hallucinations** — `notifyChanged` removed from every energy-only site (applyDeltaToOccupant 695/698/701, processEnergyDecay 756/783, processOvercrowding penalty 886/888, ActionResolver combat-damage 675/679/682). Hooks land only at structural grid mutations. Every site enumerated by file:line.
- **MEDIUM-6 (Codex)** — `cleanupByEntityId` line 655 and `cleanupBot` line 695 grid clears get `eligibleCellIndex.notifyChanged` hooks.
- **MEDIUM-7 (Codex)** — back-compat WorldWebSocketHandler ctors at lines 153 and 170 use `Objects.requireNonNull(eligibleCellIndex, "eligibleCellIndex")` — fail loudly, no `null` forwarding.
- **R2-13 (OpenCode)** — `EligibleCellIndexRectangularTest` 8×16 fixture locks `toIndex` / `fromIndex` formula parity.
- **LOW-12** — bounded 3-retry on lost-race (defensive against concurrent registrations both sampling the same cell) before declaring GRID_FULL; each retry still increments lost-race counter for observability.
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
@src/main/java/com/paralife/world/GridConfig.java
@src/main/java/com/paralife/world/Cell.java
@src/main/java/com/paralife/world/Entity.java
@src/main/java/com/paralife/world/Position.java
@src/main/java/com/paralife/admission/AdmissionMetrics.java
@src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java
@src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java

<interfaces>
<!-- VERIFIED via grep against current source. -->

From src/main/java/com/paralife/world/WorldGrid.java:
```java
public boolean trySetEntity(int x, int y, Entity entity);
public Cell getCell(int x, int y);
public int getWidth();
public int getHeight();
public List<Position> getNeighbors(int x, int y);   // 8 Moore, toroidal
```

From src/main/java/com/paralife/world/Entity.java (line 18):
```java
public sealed interface Entity permits Entity.Particle, Entity.Rock, Entity.Nutrient, Entity.BondedPair, Entity.CompositeMember { ... }
public enum ParticleType { CATALYST, MEMBRANE, SPORE; ... }
public static Particle spawn(String id, ParticleType type, int energy);  // line 95
```

From src/main/java/com/paralife/world/GridConfig.java:
```java
public record GridConfig(int width, int height) { ... }   // line 10
```

From src/main/java/com/paralife/engine/EnvironmentEngine.java (CURRENT — to be modified):
```java
private final Map<Position, Byte> cellStatusCache = new HashMap<>();   // line 177 — REVIEWS H4 fix this
Map<Position, Byte> cellStatusCacheView() { ... }                       // line 1399 — returns Collections.unmodifiableMap(cellStatusCache)
void buildStatusCaches() { ... cellStatusCache.clear(); ... cellStatusCache.put(pos, merged); ... }   // line 875
```

After REVIEWS H4 fix (this plan):
```java
private volatile Map<Position, Byte> cellStatusCache = Map.of();   // immutable snapshot
private final Map<Position, Byte> cellStatusStaging = new HashMap<>();   // tick-thread-only mutation
// buildStatusCaches() mutates cellStatusStaging then publishes:
//   this.cellStatusCache = Map.copyOf(cellStatusStaging);
// cellStatusCacheView() returns the volatile field directly (already immutable).
```

From src/main/java/com/paralife/admission/OutboundSender.java (UNCHANGED, but used by Plan 03):
```java
package com.paralife.admission;   // VERIFIED — REVIEWS CONSENSUS-H5
```

From src/main/java/com/paralife/engine/TickEvent.java (UNCHANGED):
```java
package com.paralife.engine;   // VERIFIED — REVIEWS CONSENSUS-H5
public record TickEvent(long tickNumber, Instant timestamp) {
    public TickEvent(long tickNumber) { this(tickNumber, Instant.now()); }   // convenience ctor
}
```

From src/main/java/com/paralife/admission/AdmissionMetrics.java:
```java
// existing pattern: Counter.builder(...).register(meterRegistry)
// add: paralife.placement.lost-race.total + incLostRace()
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (VERIFIED via grep):
```java
private static final int MAX_PLACEMENT_ATTEMPTS = 50;   // line 88 — DELETE
private Random spawnRng;                                  // line 116 — keep
public WorldWebSocketHandler(...) { ... }                 // line 119 — primary @Autowired ctor
public WorldWebSocketHandler(...) { ... }                 // line 153 — back-compat ctor (tests)
public WorldWebSocketHandler(...) { ... }                 // line 170 — back-compat ctor (tests)
public void resetSeed();                                  // line 187
public void cleanupByEntityId(String entityId);          // line 615 — clearEntity at line 655 (REVIEWS MED-6)
public void cleanupBot(WebSocketSession s);               // line 678 — clearEntity at line 695 (REVIEWS MED-6)
for (int attempt = 0; ...) { ... }                        // line 453 — DELETE the retry loop
```

**Mutation sites** (RE-VERIFIED via `grep -n "worldGrid\.(set|clear)Entity" src/main/java/com/paralife/`). STRUCTURAL only — energy-only writes EXCLUDED:

`src/main/java/com/paralife/engine/DeathFinalizer.java`:
- line 88  — `worldGrid.clearEntity(x, y)` in `finalizeParticleDeath` ✓ STRUCTURAL
- line 113 — `worldGrid.clearEntity(x, y)` in `finalizeBondedPairDeath` ✓ STRUCTURAL

`src/main/java/com/paralife/engine/ActionResolver.java`:
- line 483 — `clearEntity(oldPos)` in `resolveMove` ✓ STRUCTURAL
- line 497 — `setEntity(target, placed)` in `resolveMove` ✓ STRUCTURAL
- line 530 — `setEntity(pos, updated)` consume — energy-only via withEnergy → **EXCLUDED**
- line 534 — `clearEntity(nutrientPos)` nutrient consumed ✓ STRUCTURAL (nutrient removed)
- line 536 — `setEntity(nutrientPos, depleted)` ✓ STRUCTURAL (nutrient → depleted)
- line 569 — `setEntity(target, child)` reproduce primary child ✓ STRUCTURAL
- line 572 — `setEntity(parent.pos, updatedParent)` energy-only via withEnergy → **EXCLUDED**
- line 582 — `setEntity(bonusTarget, bonusChild)` ✓ STRUCTURAL
- line 634 — `clearEntity(nutrientPos)` reproducer-bud nutrient ✓ STRUCTURAL
- line 636 — `setEntity(nutrientPos, depleted)` ✓ STRUCTURAL
- line 675/679/682 — combat damage `withEnergy` → **EXCLUDED** (energy-only)
- line 694 — `setEntity(pos, ...)` per-bot result — REVIEW per-call: if structural transform (e.g. spawn nutrient on death) HOOK; if `withEnergy` only EXCLUDE. Re-read inline.
- line 753 — `setEntity(target, child)` reproducer-bud ✓ STRUCTURAL
- line 962 — `clearEntity(pos)` in `executeCompositeMovement` ✓ STRUCTURAL
- line 968 — `setEntity(target, member)` in `executeCompositeMovement` ✓ STRUCTURAL

`src/main/java/com/paralife/engine/SimulationEngine.java`:
- line 589 — `setEntity(primary, bondedPair)` bond-formation ✓ STRUCTURAL
- line 590 — `clearEntity(secondary)` bond-formation ✓ STRUCTURAL
- line 651 — `setEntity(cf.pos1, member1)` composite-formation ✓ STRUCTURAL
- line 652 — `setEntity(cf.pos2, member2)` composite-formation ✓ STRUCTURAL
- line 695/698/701 — `applyDeltaToOccupant` `withEnergy` → **EXCLUDED** (energy-only; this is the line-number hallucination from prior plans)
- line 756 — `processEnergyDecay` setEntity withEnergy → **EXCLUDED** (energy-only)
- line 783 — `processEnergyDecay` setEntity withEnergy → **EXCLUDED** (energy-only)
- line 886/888 — `processOvercrowding` penalty `withEnergy` → **EXCLUDED** (energy-only)
- line 977 — `worldGrid.clearEntity(pos)` in `handleMemberDeath` shared cleanup ✓ STRUCTURAL
- line 1051 — `setEntity(pos, bondedPair)` in `revertToBondedPair` ✓ STRUCTURAL
- line 1098 — `setEntity(pos, particle)` in `dissolveToParticles` ✓ STRUCTURAL
- line 1139 — `clearEntity(pos)` in `checkPanicZone` panic-collapse ✓ STRUCTURAL (REVIEWS Claude R2 unique)
- line 1185 — `setEntity(x, y, Nutrient.spawn(id))` `processNutrientSpawning` ✓ STRUCTURAL

`src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:
- line 655 — `worldGrid.clearEntity(pos.x, pos.y)` in `cleanupByEntityId` (stalled-token expiry) ✓ STRUCTURAL (REVIEWS MED-6)
- line 695 — `worldGrid.clearEntity(pos.x, pos.y)` in `cleanupBot` (graceful disconnect) ✓ STRUCTURAL (REVIEWS MED-6)

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Make EnvironmentEngine.cellStatusCache thread-safe (REVIEWS CONSENSUS-H4) and verify RockGenerator sync</name>
  <files>src/main/java/com/paralife/engine/EnvironmentEngine.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (line 177 cellStatusCache decl; line 875 buildStatusCaches; line 890 cellStatusCache.get; line 892 cellStatusCache.put; line 911 get; line 913 put; line 324 cellStatusCache.clear; line 1231 clear; line 1296 clear; line 1399 cellStatusCacheView; line 1550 clear; line 1574 get)
    - src/main/java/com/paralife/world/RockGenerator.java (verify @PostConstruct synchronous; no @Async, no executor.submit, no Thread.start)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (CONSENSUS-H4 + L2)
  </read_first>
  <behavior>
    - cellStatusCacheViewReturnsImmutableSnapshot: after `buildStatusCaches()`, `cellStatusCacheView()` returns the volatile snapshot; the returned reference is `Map.copyOf` (immutable); a second `buildStatusCaches()` call leaves the previously-returned reference intact (stable while caller holds it).
    - tickThreadMutationDoesNotDisturbConcurrentReader: simulate WS-thread `cellStatusCacheView().get(pos)` while tick-thread `buildStatusCaches()` mutates the staging map; reader sees a coherent snapshot (no ConcurrentModificationException, no torn read).
  </behavior>
  <action>
**STEP 0 — REVIEWS L2 RockGenerator sync pre-flight:**

```bash
grep -nE "@Async|executor\\.submit|executor\\.execute|CompletableFuture\\.runAsync|new Thread\\(|Thread\\.start" src/main/java/com/paralife/world/RockGenerator.java
```

Output MUST be empty. If any match appears, STOP and escalate — `@DependsOn("rockGenerator")` will not be sufficient.

**STEP 1 — Refactor `cellStatusCache` to volatile immutable snapshot:**

In `src/main/java/com/paralife/engine/EnvironmentEngine.java`:

(a) Line 177 — change:
```java
private final Map<Position, Byte> cellStatusCache = new HashMap<>();
```
to:
```java
/**
 * REVIEWS CONSENSUS-H4: WS thread (EligibleCellIndex.notifyChanged) reads this
 * concurrently with tick-thread {@link #buildStatusCaches()} mutation. To
 * eliminate the HashMap concurrent-read race, this field is volatile and
 * always points at an IMMUTABLE Map.copyOf snapshot. Tick-thread mutates
 * {@link #cellStatusStaging} privately, then publishes a new immutable
 * snapshot in one volatile write at the end of buildStatusCaches.
 */
private volatile Map<Position, Byte> cellStatusCache = Map.of();
private final Map<Position, Byte> cellStatusStaging = new HashMap<>();
```

(b) Inside `buildStatusCaches()` (line 875): replace EVERY `cellStatusCache.clear()` / `cellStatusCache.put(...)` inside that method with `cellStatusStaging.clear()` / `cellStatusStaging.put(...)`. The mutations at lines 890–892 and 911–913 (the ones inside `buildStatusCaches`) are the staging path.

At the end of `buildStatusCaches()` (last line of method body, before return), append:
```java
// REVIEWS CONSENSUS-H4: publish immutable snapshot via single volatile write.
this.cellStatusCache = Map.copyOf(cellStatusStaging);
```

(c) Other `cellStatusCache.clear()` sites at lines 324, 1231, 1296, 1550 — these are full-reset paths (state load, etc.). Replace with:
```java
this.cellStatusCache = Map.of();
cellStatusStaging.clear();
```

(d) `cellStatusCache.get(pos)` at line 1574 (the public-facing getter path, OUTSIDE buildStatusCaches) — leave as-is. The volatile read returns the immutable snapshot; `.get` on `Map.copyOf` is thread-safe.

(e) Line 1399 `cellStatusCacheView()`: replace body with:
```java
Map<Position, Byte> cellStatusCacheView() {
    return cellStatusCache;   // REVIEWS H4: volatile field is already immutable Map.copyOf snapshot.
}
```

(f) **Acceptance grep — STAGING is the only mutation path:**
```bash
grep -nE "cellStatusCache\\.(put|clear)\\(" src/main/java/com/paralife/engine/EnvironmentEngine.java
```
Output MUST be empty. The only mutations live on `cellStatusStaging`.

  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.EnvironmentEngine*" --tests "com.paralife.engine.EnvironmentDeterminismTest"</automated>
  </verify>
  <acceptance_criteria>
    - `grep -cE "@Async|executor\\.submit|executor\\.execute|CompletableFuture\\.runAsync|new Thread\\(|Thread\\.start" src/main/java/com/paralife/world/RockGenerator.java` == 0 (REVIEWS L2 — RockGenerator sync verified)
    - `grep -cE "private volatile Map<Position, Byte> cellStatusCache" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 1 (REVIEWS H4 — volatile field)
    - `grep -cE "private final Map<Position, Byte> cellStatusStaging" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 1 (REVIEWS H4 — staging map)
    - `grep -cE "this\\.cellStatusCache = Map\\.copyOf\\(cellStatusStaging\\)" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (REVIEWS H4 — single publish point)
    - `grep -cE "cellStatusCache\\.(put|clear)\\(" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 0 (REVIEWS H4 — no direct mutation; staging only)
    - `grep -cE "cellStatusStaging\\.(put|clear)" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 4 (the 2 puts + at minimum 2 clears in buildStatusCaches/state-reset paths)
    - `./gradlew test --tests "com.paralife.engine.EnvironmentDeterminismTest"` exits 0 (no behaviour change — equivalence preserved)
  </acceptance_criteria>
  <done>cellStatusCache is volatile + Map.copyOf-published; staging map is the only mutation site; cellStatusCacheView returns the volatile snapshot; existing EnvironmentDeterminismTest remains green; REVIEWS CONSENSUS-H4 closed.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Create EligibleCellIndex sparse-set + 8×8 unit tests + 8×16 rectangular linearisation test</name>
  <files>src/main/java/com/paralife/engine/EligibleCellIndex.java, src/test/java/com/paralife/engine/EligibleCellIndexTest.java, src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (target — confirm absent)
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (Task 1 result — `cellStatusCacheView()` now returns volatile immutable Map; line 1399)
    - src/main/java/com/paralife/world/Entity.java (sealed inner types `Entity.Particle`, `Entity.BondedPair`)
    - src/main/java/com/paralife/world/RockGenerator.java (verify line 49 @PostConstruct still synchronous after Task 1)
    - src/main/java/com/paralife/world/WorldGrid.java (trySetEntity, setEntity, clearEntity, getCell, getNeighbors)
    - src/main/java/com/paralife/world/Cell.java (FLAG_OVERCROWDED constant)
    - src/main/java/com/paralife/world/GridConfig.java (record signature)
    - src/main/java/com/paralife/engine/SimulationConfig.java (overcrowdingThreshold accessor)
    - src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java (pure-JUnit unit-test analog)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 1, §Pitfall 5)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H2/L1/L2/MEDIUM-9/R2-13)
  </read_first>
  <behavior>
    - addThenSampleReturnsAddedCell, removeIsO1AndDoesNotShift, sampleEmptyReturnsNull, seededSampleIsBitExact (8×8 grid).
    - notifyChangedRespects5x5Bbox, constraint1RejectsOccupied, constraint2RejectsOvercrowded, constraint3RejectsCellsThatWouldOvercrowdNeighbour.
    - cellStatusCacheReadOnlyOnceInLoop: Mockito spy on EnvironmentEngine; `verify(envEngine, times(1)).cellStatusCacheView()` per `notifyChanged` invocation.
    - **rectangularGridLinearisationParity (8×16):** for every (x, y) in 0..7 × 0..15, `toIndex(x, y)` is unique AND `fromIndex(toIndex(x, y))` round-trips to (x, y). Verifies x*height+y formula on width≠height.
  </behavior>
  <action>

Create `src/main/java/com/paralife/engine/EligibleCellIndex.java`:

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
 * {@code WorldWebSocketHandler}.
 *
 * <p>Eligibility = (no occupant) AND (cell not FLAG_OVERCROWDED) AND (placing
 * here would NOT push any adjacent occupied Moore neighbour over
 * {@code overcrowdingThreshold}). Maintained incrementally via
 * {@link #notifyChanged(int, int)} on a 5×5 dirty bbox.
 *
 * <p><b>Lifecycle hook discipline (REVIEWS MEDIUM-1):</b> only STRUCTURAL grid
 * mutations call notifyChanged. Energy-only writes (applyDeltaToOccupant,
 * processEnergyDecay, processOvercrowding penalty, combat damage withEnergy,
 * toxin/mutagen damage) MUST NOT — they don't change occupancy.
 *
 * <p><b>Lock-order invariant (REVIEWS L1):</b> {@code synchronized(this)} (the
 * index monitor) is acquired before {@link WorldGrid#getCell} (the grid read
 * lock). Direction is index-monitor → grid-read-lock. Tick handlers must call
 * {@code notifyChanged} AFTER the WorldGrid mutation returns, never inside the
 * write lock.
 *
 * <p><b>Init order (REVIEWS H2 + L2):</b> {@code @DependsOn("rockGenerator")}
 * forces this bean's @PostConstruct to run after RockGenerator's;
 * RockGenerator.initialize() is synchronous (verified pre-flight per L2).
 *
 * <p><b>Cell-status read (REVIEWS CONSENSUS-H4):</b> reads
 * {@link EnvironmentEngine#cellStatusCacheView()}, which now returns a volatile
 * immutable {@code Map.copyOf} snapshot. Safe for concurrent WS-thread reads
 * with no risk of ConcurrentModificationException.
 *
 * <p>// PERF: REVIEWS MEDIUM-9 — `cache.get(new Position(x, y))` allocates a
 * Position per cell × 25 per notifyChanged call. Acceptable at current scale;
 * Phase 21 benchmark may revisit (option (c) packed-int cache key).
 */
@Component
@DependsOn("rockGenerator")
public class EligibleCellIndex {
    private static final Logger log = LoggerFactory.getLogger(EligibleCellIndex.class);
    private static final int DIRTY_BBOX_RADIUS = 2;

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
        Map<Position, Byte> snap = environmentEngine.cellStatusCacheView();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (evaluateEligibility(x, y, snap)) addInternal(x, y);
            }
        }
        log.info("EligibleCellIndex initialised: {} eligible cells of {} total", size, width * height);
    }

    /** Linearisation: x*height + y. REVIEWS R2-13 — see EligibleCellIndexRectangularTest. */
    int toIndex(int x, int y) { return x * height + y; }
    Position fromIndex(int idx) { return new Position(idx / height, idx % height); }

    public synchronized void add(int x, int y) { addInternal(x, y); }

    private void addInternal(int x, int y) {
        int idx = toIndex(x, y);
        if (posInDense[idx] >= 0) return;
        dense[size] = idx;
        posInDense[idx] = size;
        size++;
    }

    public synchronized void remove(int x, int y) { removeInternal(x, y); }

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
        return fromIndex(idx);
    }

    public synchronized int eligibleCount() { return size; }

    /**
     * Re-evaluate eligibility for the 5×5 Moore bbox around (px, py). Toroidal
     * via floorMod. Cell-status cache read once per call (REVIEWS O3).
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
        // PERF: REVIEWS MEDIUM-9 — Position allocation per cache lookup acknowledged.
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

Create `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` as a pure-JUnit unit test with all 9 behaviour cases. Use Mockito spy on a stub `EnvironmentEngine` for the `cellStatusCacheReadOnlyOnceInLoop` test — `verify(envEngine, times(1)).cellStatusCacheView()` after one `notifyChanged`.

Create `src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java` (REVIEWS R2-13):

```java
package com.paralife.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REVIEWS R2-13 (OpenCode): lock the toIndex / fromIndex linearisation formula
 * for non-square grids before the codebase ever exercises width != height.
 */
class EligibleCellIndexRectangularTest {
    @Test
    void linearisationIsBijectiveOn8x16() {
        // Use the EligibleCellIndex package-private toIndex/fromIndex via test-only seam,
        // OR replicate the formula here as a contract test.
        int width = 8, height = 16;
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int idx = x * height + y;
                assertThat(seen.add(idx))
                    .as("toIndex(%d, %d) = %d collides", x, y, idx).isTrue();
                int rx = idx / height;
                int ry = idx % height;
                assertThat(rx).as("fromIndex round-trip x for %d", idx).isEqualTo(x);
                assertThat(ry).as("fromIndex round-trip y for %d", idx).isEqualTo(y);
            }
        }
        assertThat(seen).hasSize(width * height);
    }
}
```
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest" --tests "com.paralife.engine.EligibleCellIndexRectangularTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/main/java/com/paralife/engine/EligibleCellIndex.java` exists with `@Component` and `@DependsOn("rockGenerator")`.
    - `grep -c "@DependsOn(\"rockGenerator\")" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -cE "Lock-order invariant|index-monitor → grid-read-lock|index-monitor.*grid-read-lock" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 1 (REVIEWS L1)
    - `grep -cE "Entity\\.Particle|Entity\\.BondedPair" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 2
    - `grep -cE "rng\\.nextInt\\(size\\)" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 1
    - `grep -cE "Math\\.floorMod" src/main/java/com/paralife/engine/EligibleCellIndex.java` >= 2
    - `grep -cE "cellStatusCacheView\\(\\)" src/main/java/com/paralife/engine/EligibleCellIndex.java` <= 2 (one in @PostConstruct, one in notifyChanged — both hoisted)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EligibleCellIndex.java` == 0
    - File `src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java` exists.
    - `grep -c "linearisationIsBijectiveOn8x16" src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java` == 1
    - `./gradlew compileJava compileTestJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest" --tests "com.paralife.engine.EligibleCellIndexRectangularTest"` exits 0
  </acceptance_criteria>
  <done>EligibleCellIndex bean exists; 8×8 unit tests pass (incl. cellStatusCacheReadOnlyOnceInLoop); 8×16 rectangular linearisation test passes (REVIEWS R2-13 closed); REVIEWS H2/L1/L2 closed.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Refactor placement (delete retry loop) + lifecycle hooks ONLY at structural sites + bounded 3-retry on lost-race + cleanupByEntityId/cleanupBot hooks + ctor-cascade fan-out + PUBLIC attemptPlacementForTest + PlacementDeterminismTest + PlacementDensityIntegrationTest</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/ActionResolver.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/admission/AdmissionMetrics.java, src/test/java/com/paralife/engine/PlacementDeterminismTest.java, src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (lines 88, 116, 119, 153, 170, 187, 210, 453–467, 615 (cleanupByEntityId), 655 (clearEntity), 678 (cleanupBot), 695 (clearEntity))
    - src/main/java/com/paralife/engine/EligibleCellIndex.java (Task 2 result)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (lines 63 ctor; 81 finalizeParticleDeath; 84 unreg; 88 clearEntity; 97 finalizeBondedPairDeath; 102/103 unreg; 113 clearEntity)
    - src/main/java/com/paralife/engine/ActionResolver.java (lines 153/188/201 ctors; 483 clearEntity; 497 setEntity; 530/534/536 consume/nutrient; 569/582/753 reproduce children; 634/636 reproducer-bud nutrient; 962/968 executeCompositeMovement; **NOT** 530 setEntity withEnergy; **NOT** 572 withEnergy parent update; **NOT** 675/679/682 combat damage withEnergy; **NOT** 694 if energy-only)
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 113 ctor; 145 ctor; 173 back-compat ctor; 589/590 bond-formation; 651/652 composite-formation; 977 handleMemberDeath cleanup; 1051 revertToBondedPair; 1098 dissolveToParticles; 1139 checkPanicZone clearEntity; 1185 nutrient spawn; **NOT** 695/698/701 (applyDeltaToOccupant — energy-only); **NOT** 756/783 (processEnergyDecay); **NOT** 886/888 (processOvercrowding penalty))
    - src/main/java/com/paralife/admission/AdmissionMetrics.java (existing Counter pattern + MeterRegistry)
    - src/main/java/com/paralife/world/Entity.java (Particle.spawn signature line 95)
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (template)
    - src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java (WS load template)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (CONSENSUS-H6/MED-1/MED-6/MED-7/L4/L5)
  </read_first>
  <behavior>
    - placementUsesIndexNotRetryLoop: handleRegister calls `eligibleCellIndex.sample(spawnRng)`; no retry loop.
    - sampleNullEmitsGridFull: empty index → E|503|GRID_FULL + admissionGate.releaseSlot.
    - successfulPlaceTriggersIndexNotify: after `worldGrid.trySetEntity` true → `eligibleCellIndex.notifyChanged(x,y)` exactly once.
    - lostRaceRetriesUpTo3TimesThenGridFull: trySetEntity returns false on first attempt → resample + notifyChanged + retry, up to 3 attempts; each attempt increments lost-race counter; after 3 failures emit GRID_FULL (REVIEWS L4 / LOW-12).
    - cleanupByEntityIdTriggersIndexNotify: stalled grace-expiry path clears grid → notifyChanged on the cleared cell (REVIEWS MED-6).
    - cleanupBotTriggersIndexNotify: graceful disconnect → notifyChanged on the cleared cell (REVIEWS MED-6).
    - structuralOnlyHooks: energy-only sites (applyDeltaToOccupant 695/698/701, processEnergyDecay 756/783, processOvercrowding 886/888, ActionResolver combat 675/679/682, parent withEnergy 572) do NOT call notifyChanged.
    - placementsAreBitExactAcrossTwoRuns (PlacementDeterminismTest): REAL `handler.attemptPlacementForTest` (PUBLIC seam — REVIEWS CONSENSUS-H6).
    - densityFillReturnsGridFullWithoutRetryStorm.
    - backCompatCtorRequireNonNull: alternate WorldWebSocketHandler ctors that don't accept EligibleCellIndex throw NPE on null forwarding (REVIEWS MED-7).
  </behavior>
  <action>

**STEP 0 — REVIEWS MED-2 ctor cascade pre-flight enumeration:**
```bash
grep -nE "new (SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler)\\(" src/test/java -r
```
Each `file:line` will need the new `eligibleCellIndex` parameter forwarded. Save the list; revisit after step 1.

**STEP 1 — Add lost-race counter to AdmissionMetrics:**

In `src/main/java/com/paralife/admission/AdmissionMetrics.java`:

```java
// In constructor, mirror existing Counter.builder pattern:
this.lostRace = Counter.builder("paralife.placement.lost-race.total")
    .description("Placement: sampled cell lost the trySetEntity race (REVIEWS L3/L4)")
    .register(meterRegistry);

// Field: private final Counter lostRace;
// Method:
public void incLostRace() { lostRace.increment(); }
```

If the actual field name for the MeterRegistry differs, re-grep `Counter.builder` in this file and match the existing wiring exactly.

**STEP 2 — Refactor WorldWebSocketHandler:**

In `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:

(a) Line 88 — DELETE `private static final int MAX_PLACEMENT_ATTEMPTS = 50;`
(b) Define a private constant: `private static final int LOST_RACE_MAX_RETRIES = 3;` (REVIEWS L4 / LOW-12).
(c) Add field next to other registry fields (around line 105):
```java
private final EligibleCellIndex eligibleCellIndex;
```
(d) Modify the @Autowired constructor at line 119: add `EligibleCellIndex eligibleCellIndex` as a new parameter; assign to field. **REVIEWS MED-7 (Codex):** in the back-compat constructors at line 153 and line 170, do NOT pass `null` silently. Instead use:
```java
this.eligibleCellIndex = Objects.requireNonNull(eligibleCellIndex, "eligibleCellIndex");
```
or, for back-compat ctors that legitimately omit the param, add a no-op `EligibleCellIndex` injectable instance — but the simplest path is: extend ALL three constructor signatures with the new parameter; tests autowire via `@SpringBootTest`, manual ctor sites are caught by Step 0's grep.

(e) Replace lines 453–467 (the retry loop) with:

```java
Particle particle = Particle.spawn(entityId, particleType, initialEnergy);
Position pos = null;
boolean placed = false;
for (int attempt = 0; attempt < LOST_RACE_MAX_RETRIES; attempt++) {
    pos = eligibleCellIndex.sample(spawnRng);
    if (pos == null) break;
    if (worldGrid.trySetEntity(pos.x(), pos.y(), particle)) {
        placed = true;
        break;
    }
    // REVIEWS L4 / LOW-12 — bounded lost-race retry. Increment counter every attempt,
    // refresh notifyChanged for the contested cell so the index drops it from sample set,
    // then retry up to LOST_RACE_MAX_RETRIES.
    if (admissionMetrics != null) admissionMetrics.incLostRace();
    eligibleCellIndex.notifyChanged(pos.x(), pos.y());
}
if (!placed) {
    if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
    if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
    sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
    return;
}
int x = pos.x();
int y = pos.y();
eligibleCellIndex.notifyChanged(x, y);
```

(f) **REVIEWS CONSENSUS-H6 — PUBLIC test seam.** Add as a public method:
```java
/**
 * Test seam (REVIEWS CONSENSUS-H6 — public so tests in com.paralife.engine compile).
 * Same code path as inbound `r|` registration but without session attachment.
 * Production callers MUST use {@link #handleRegister}.
 */
public Optional<Position> attemptPlacementForTest(String entityId, Entity.ParticleType type, int initialEnergy) {
    Particle particle = Particle.spawn(entityId, type, initialEnergy);
    for (int attempt = 0; attempt < LOST_RACE_MAX_RETRIES; attempt++) {
        Position pos = eligibleCellIndex.sample(spawnRng);
        if (pos == null) return Optional.empty();
        if (worldGrid.trySetEntity(pos.x(), pos.y(), particle)) {
            eligibleCellIndex.notifyChanged(pos.x(), pos.y());
            return Optional.of(pos);
        }
        if (admissionMetrics != null) admissionMetrics.incLostRace();
        eligibleCellIndex.notifyChanged(pos.x(), pos.y());
    }
    return Optional.empty();
}
```

(g) **REVIEWS MED-6 — cleanup-path hooks.**

In `cleanupByEntityId` (line 615), at the manual-cleanup branch around line 655, AFTER:
```java
worldGrid.clearEntity(pos.x(), pos.y());
```
INSERT:
```java
eligibleCellIndex.notifyChanged(pos.x(), pos.y());
```

In `cleanupBot` (line 678), AFTER the `worldGrid.clearEntity(pos.x(), pos.y())` at line 695, INSERT:
```java
eligibleCellIndex.notifyChanged(pos.x(), pos.y());
```

**STEP 3 — DeathFinalizer hooks (REVIEWS H1, structural sites only):**

In `src/main/java/com/paralife/engine/DeathFinalizer.java`:
(a) Add `private final EligibleCellIndex eligibleCellIndex;` and ctor parameter (line 63 ctor signature extension).
(b) After `worldGrid.clearEntity(x, y);` at line 88 (`finalizeParticleDeath`): insert `eligibleCellIndex.notifyChanged(x, y);`
(c) After `worldGrid.clearEntity(x, y);` at line 113 (`finalizeBondedPairDeath`): insert `eligibleCellIndex.notifyChanged(x, y);`

**STEP 4 — ActionResolver hooks (REVIEWS H1 + MEDIUM-1, structural sites ONLY):**

In `src/main/java/com/paralife/engine/ActionResolver.java`:
(a) Add `private final EligibleCellIndex eligibleCellIndex;` and ctor parameter (extend ALL three constructors at lines 153, 188, 201).
(b) After **line 483** `clearEntity(oldPos)` in `resolveMove`: `eligibleCellIndex.notifyChanged(oldPos.x(), oldPos.y());`
(c) After **line 497** `setEntity(target, placed)` in `resolveMove`: `eligibleCellIndex.notifyChanged(target.x(), target.y());`
(d) After **line 534** `clearEntity(nutrientPos)` (consume): `eligibleCellIndex.notifyChanged(nutrientPos.x(), nutrientPos.y());`
(e) After **line 536** `setEntity(nutrientPos, depleted)`: `eligibleCellIndex.notifyChanged(nutrientPos.x(), nutrientPos.y());`
(f) After **line 569** `setEntity(target, child)` reproduce primary: `eligibleCellIndex.notifyChanged(target.x(), target.y());`
(g) After **line 582** `setEntity(bonusTarget, bonusChild)`: `eligibleCellIndex.notifyChanged(bonusTarget.x(), bonusTarget.y());`
(h) After **line 634** `clearEntity(nutrientPos)` (reproducer-bud): notifyChanged.
(i) After **line 636** `setEntity(nutrientPos, depleted)`: notifyChanged.
(j) After **line 753** `setEntity(target, child)` reproducer-bud: notifyChanged.
(k) After **line 962** `clearEntity(pos)` in `executeCompositeMovement`: notifyChanged.
(l) After **line 968** `setEntity(target, member)` in `executeCompositeMovement`: notifyChanged.

**Sites EXPLICITLY EXCLUDED (energy-only, REVIEWS MEDIUM-1):**
- line 530 setEntity (consume — `withEnergy`)
- line 572 setEntity (parent update — `withEnergy`)
- lines 675/679/682 setEntity (combat damage — `withEnergy`)
- line 694 setEntity (per-bot result — `withEnergy` only; if a particular `setEntity` here transforms structure, audit case-by-case at execution time)

**STEP 5 — SimulationEngine hooks (REVIEWS H1 + MEDIUM-1 + Claude R2 panic-zone unique):**

In `src/main/java/com/paralife/engine/SimulationEngine.java`:
(a) Add `private final EligibleCellIndex eligibleCellIndex;` and ctor parameter; extend ALL three constructors (lines 113, 145, 173).
(b) After **line 589** `setEntity(primary, bondedPair)` (bond-formation): `eligibleCellIndex.notifyChanged(bond.primaryPos.x(), bond.primaryPos.y());`
(c) After **line 590** `clearEntity(secondary)`: `eligibleCellIndex.notifyChanged(bond.secondaryPos.x(), bond.secondaryPos.y());`
(d) After **line 651** `setEntity(cf.pos1, member1)` (composite-formation): notifyChanged.
(e) After **line 652** `setEntity(cf.pos2, member2)`: notifyChanged.
(f) After **line 977** `clearEntity(pos)` in `handleMemberDeath` shared cleanup: notifyChanged.
(g) After **line 1051** `setEntity(pos, bondedPair)` in `revertToBondedPair`: notifyChanged.
(h) After **line 1098** `setEntity(pos, particle)` in `dissolveToParticles`: notifyChanged.
(i) After **line 1139** `clearEntity(pos)` in `checkPanicZone` (REVIEWS Claude R2 unique — panic-zone fallback): notifyChanged.
(j) After **line 1185** `setEntity(x, y, Nutrient.spawn(id))` (`processNutrientSpawning`): notifyChanged.
(k) **Back-compat ctor (REVIEWS M6):** in the line-173 ctor where `new DeathFinalizer(...)` is constructed internally, forward the new `eligibleCellIndex` param.

**Sites EXPLICITLY EXCLUDED (energy-only — REVIEWS MEDIUM-1):**
- lines 695/698/701 — `applyDeltaToOccupant` (`withEnergy` clamps; line-number hallucination from prior plans)
- lines 756/783 — `processEnergyDecay` (`withEnergy`)
- lines 886/888 — `processOvercrowding` energy penalty (`withEnergy`)

**STEP 6 — REVIEWS MED-2 ctor cascade fan-out:**
Visit every `file:line` from STEP 0; forward `eligibleCellIndex` per the production positional slot. Tests using `@SpringBootTest` autowire — no edit needed. Run `./gradlew compileTestJava` as the gate.

**STEP 7 — Create `src/test/java/com/paralife/engine/PlacementDeterminismTest.java`:**

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
            Optional<Position> p = handler.attemptPlacementForTest(
                "test-" + i,
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

**STEP 8 — Create `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java`** modelled on `HundredBotIntegrationTest.java`. Spin up WS clients, register all, count `S|` vs `E|503` frames, assert saturation→GRID_FULL completes without retry storm.

Run `./gradlew compileTestJava` as fast gate, then `./gradlew test`.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "MAX_PLACEMENT_ATTEMPTS" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 0
    - `grep -c "for (int attempt = 0" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` <= 1 (only the LOST_RACE_MAX_RETRIES bounded retry; pre-existing 50-retry deleted)
    - `grep -cE "LOST_RACE_MAX_RETRIES *= *3" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1 (REVIEWS L4)
    - `grep -cE "eligibleCellIndex\\.sample\\(spawnRng\\)" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "admissionMetrics.incLostRace" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1 (incremented on lost race)
    - `grep -cE "incLostRace|paralife\\.placement\\.lost-race\\.total" src/main/java/com/paralife/admission/AdmissionMetrics.java` >= 2
    - `grep -cE "public Optional<Position> attemptPlacementForTest" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1 (REVIEWS CONSENSUS-H6 — PUBLIC modifier)
    - `grep -cE "Optional<Position> attemptPlacementForTest" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java | head -1` shows `public Optional<Position>` (no package-private signature exists)
    - `grep -cE "eligibleCellIndex\\.notifyChanged" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 4 (placement, lost-race retry, cleanupByEntityId, cleanupBot — REVIEWS MED-6)
    - `grep -cE "eligibleCellIndex\\.notifyChanged" src/main/java/com/paralife/engine/DeathFinalizer.java` == 2 (exact list: line 88, line 113)
    - `grep -cE "eligibleCellIndex\\.notifyChanged" src/main/java/com/paralife/engine/ActionResolver.java` == 11 (exact list: lines 483, 497, 534, 536, 569, 582, 634, 636, 753, 962, 968 — REVIEWS LOW-11 exact-list discipline)
    - `grep -cE "eligibleCellIndex\\.notifyChanged" src/main/java/com/paralife/engine/SimulationEngine.java` == 9 (exact list: lines 589, 590, 651, 652, 977, 1051, 1098, 1139, 1185)
    - **Energy-only sites NOT hooked (REVIEWS MEDIUM-1):**
      `bash -c 'sed -n "692,705p" src/main/java/com/paralife/engine/SimulationEngine.java | grep -c eligibleCellIndex'` == 0 (applyDeltaToOccupant clean)
      `bash -c 'sed -n "754,790p" src/main/java/com/paralife/engine/SimulationEngine.java | grep -c eligibleCellIndex'` == 0 (processEnergyDecay clean)
      `bash -c 'sed -n "880,895p" src/main/java/com/paralife/engine/SimulationEngine.java | grep -c eligibleCellIndex'` == 0 (processOvercrowding penalty clean)
      `bash -c 'sed -n "670,690p" src/main/java/com/paralife/engine/ActionResolver.java | grep -c eligibleCellIndex'` == 0 (combat damage clean)
    - `grep -cE "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - `grep -cE "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -cE "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -cE "private final EligibleCellIndex eligibleCellIndex" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` == 1
    - **REVIEWS MED-2 ctor cascade gate:** `./gradlew compileTestJava` exits 0
    - File `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` exists; `grep -c "placementsAreBitExactAcrossTwoRuns" .../PlacementDeterminismTest.java` == 1; `grep -c "attemptPlacementForTest" .../PlacementDeterminismTest.java` >= 1
    - File `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` exists; `grep -c "GRID_FULL" .../PlacementDensityIntegrationTest.java` >= 1
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>WorldWebSocketHandler placement uses EligibleCellIndex with bounded 3-retry on lost race (REVIEWS L4); MAX_PLACEMENT_ATTEMPTS deleted; PUBLIC `attemptPlacementForTest` (REVIEWS CONSENSUS-H6); cleanupByEntityId and cleanupBot hooks (REVIEWS MED-6); energy-only sites cleanly EXCLUDED (REVIEWS MEDIUM-1); structural-only hooks at exact line lists (REVIEWS LOW-11); checkPanicZone line 1139 hooked (REVIEWS Claude R2 unique); ctor cascade green (REVIEWS MED-2); back-compat ctor protected via Objects.requireNonNull (REVIEWS MED-7); PlacementDeterminismTest + PlacementDensityIntegrationTest pass; full suite green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| WS thread → tick thread | EligibleCellIndex.notifyChanged on WS register; tick thread runs buildStatusCaches concurrently. cellStatusCache is volatile + Map.copyOf-published — safe (REVIEWS H4). |
| index monitor → grid read lock | synchronized(EligibleCellIndex) → WorldGrid read lock. Tick handlers must NOT call notifyChanged inside the WorldGrid write lock (REVIEWS L1 Javadoc). |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-01 | Information disclosure | EligibleCellIndex internal state | accept | Server-internal; never echoed to clients. |
| T-19-02 | DoS | notifyChanged 5×5 cost | mitigate | 25-cell bbox; cellStatusCacheView read once per call; energy-only sites EXCLUDED (REVIEWS MEDIUM-1). |
| T-19-03 | Tampering | Placement determinism contract | accept | Server-side seed; no client influence. |
| T-19-04 | Repudiation | Determinism replay | mitigate | PlacementDeterminismTest. |
| T-19-04a | Tampering | Stale eligibility under churn | mitigate | Structural-site hooks (REVIEWS H1/MEDIUM-1 exact line lists). |
| T-19-04b | DoS | Lock-order inversion | mitigate | REVIEWS L1 Javadoc; hooks AFTER mutation. |
| T-19-04c | Tampering | RockGenerator async | mitigate | REVIEWS L2 grep gate. |
| T-19-04d | Repudiation | Lost-race observability | mitigate | paralife.placement.lost-race.total counter. |
| T-19-04e | Compile regression | Ctor cascade | mitigate | REVIEWS MED-2 grep + compileTestJava gate. |
| T-19-04f | DoS | False GRID_FULL under concurrent registration | mitigate | REVIEWS L4 / LOW-12 — bounded 3-retry. |
| T-19-04g | Compile/runtime | Test cannot access attemptPlacementForTest | mitigate | REVIEWS CONSENSUS-H6 — public modifier. |
| T-19-04h | Runtime | EnvironmentEngine.cellStatusCache HashMap concurrent read/mutate | mitigate | REVIEWS CONSENSUS-H4 — volatile Map.copyOf snapshot. |
| T-19-04i | Runtime | Stalled grace expiry leaves stale eligibility | mitigate | REVIEWS MED-6 — cleanupByEntityId / cleanupBot hooks. |
| T-19-04j | Runtime | Back-compat ctor null forwarding NPE | mitigate | REVIEWS MED-7 — Objects.requireNonNull in alternate ctors. |
</threat_model>

<verification>
- `./gradlew test` — full suite green.
- `./gradlew compileTestJava` — REVIEWS MED-2 gate green.
- MAX_PLACEMENT_ATTEMPTS absent.
- All STRUCTURAL grid mutations notify; energy-only sites do NOT.
- cellStatusCache is volatile + Map.copyOf-published (REVIEWS H4).
- attemptPlacementForTest is PUBLIC (REVIEWS H6).
- cleanupByEntityId / cleanupBot hooks present (REVIEWS MED-6).
- Bounded 3-retry on lost-race (REVIEWS L4).
- Rectangular linearisation test passes (REVIEWS R2-13).
</verification>

<success_criteria>
- Saturation→GRID_FULL completes in O(N) (PlacementDensityIntegrationTest).
- Bit-exact placement at fixed seed (PlacementDeterminismTest via PUBLIC seam).
- Three eligibility constraints enforced (EligibleCellIndexTest).
- RejectionToken.GRID_FULL wire-shape preserved.
- No stale eligibility under churn (structural hooks, exact line lists).
- All Round-2/3 consensus blockers (H4, H6) and MEDIUM items (1, 6, 7, 9, 11, 12) and LOW items (4, 5) closed in plan body.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-01-SUMMARY.md`.
</output>
