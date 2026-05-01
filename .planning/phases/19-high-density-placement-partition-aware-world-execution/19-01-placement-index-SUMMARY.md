---
phase: 19
plan: 01
subsystem: engine/placement
tags: [placement, sparse-set, eligible-cell-index, determinism, websocket, spring-boot, java]
dependency_graph:
  requires:
    - EligibleCellIndex (new)
    - EnvironmentEngine.cellStatusCacheView() (volatile snapshot — Task 1)
  provides:
    - O(1) bot placement via sparse-set index (SCALE-06)
    - Bit-exact placement determinism contract (D-06)
    - Lifecycle hooks at all structural grid mutations
  affects:
    - WorldWebSocketHandler (placement path replaced)
    - SimulationEngine, ActionResolver, DeathFinalizer (all structural mutations hooked)
tech_stack:
  added:
    - "EligibleCellIndex: dense-array + posInDense back-map sparse-set, O(1) add/remove/sample"
    - "@Lazy parameter injection pattern to break Spring circular bean creation"
    - "volatile Map<Position,Byte> + Map.copyOf immutable snapshot (REVIEWS CONSENSUS-H4)"
  patterns:
    - "Setter injection with @Lazy for circular-dependency beans (matches existing EnvironmentEngine pattern)"
    - "@DependsOn('rockGenerator') for post-rock-placement index initialization"
    - "Bounded 3-retry lost-race fallback before GRID_FULL declaration"
key_files:
  created:
    - src/main/java/com/paralife/engine/EligibleCellIndex.java
    - src/test/java/com/paralife/engine/EligibleCellIndexTest.java
    - src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java
    - src/test/java/com/paralife/engine/PlacementDeterminismTest.java
    - src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java
  modified:
    - src/main/java/com/paralife/engine/EnvironmentEngine.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/engine/DeathFinalizer.java
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/admission/AdmissionMetrics.java
decisions:
  - "SCALE-06: O(1) sparse-set replaces 50-retry random scan for bot placement"
  - "D-06: paralife.simulation.spawn.seed now a tested bit-exact contract"
  - "REVIEWS CONSENSUS-H4: cellStatusCache is volatile + Map.copyOf; staging map is tick-thread-only"
  - "REVIEWS MEDIUM-1: only structural grid mutations call notifyChanged; energy-only writes excluded"
  - "@Lazy parameter injection used for EligibleCellIndex setters in DeathFinalizer/ActionResolver/SimulationEngine and for EnvironmentEngine setter in EligibleCellIndex to break Spring circular creation cycle"
metrics:
  duration: "~2 hours"
  completed: "2026-05-01"
  tasks_completed: 3
  files_changed: 11
  lines_changed: "+1056 / -25"
---

# Phase 19 Plan 01: Placement Index Summary

O(1) sparse-set eligible-cell index replacing 50-retry random placement scan. Bit-exact seed contract tested. Lifecycle hooks at all structural grid mutations.

## Tasks Completed

| Task | Description | Commit |
|------|-------------|--------|
| 1 | `cellStatusCache` volatile+immutable snapshot (CONSENSUS-H4) | 1cbbe86 |
| 2 | `EligibleCellIndex` sparse-set + unit tests + rectangular linearisation test | eb6d395 |
| 3 | Placement refactor, lifecycle hooks at all structural sites, determinism + density integration tests | 171785c |

## What Was Built

**Task 1 — Thread-safe cellStatusCache:**
`EnvironmentEngine.cellStatusCache` changed from `HashMap` to `volatile Map<Position,Byte>` (immutable `Map.copyOf` snapshot). A private `cellStatusStaging` map accumulates mutations tick-thread-only; `buildStatusCaches()` publishes `Map.copyOf(staging)` to the volatile field at end of each tick. WS thread reads the snapshot safely with no `ConcurrentModificationException` risk.

**Task 2 — EligibleCellIndex sparse-set:**
Dense-array + back-map sparse-set providing O(1) add/remove/sample. Initialization via `@PostConstruct` after `@DependsOn("rockGenerator")`. Three eligibility constraints: (1) no occupant, (2) cell not OVERCROWDED per `cellStatusCache` bit 0, (3) placement won't push adjacent occupied cell over `overcrowdingThreshold`. `notifyChanged(x,y)` re-evaluates 5×5 toroidal bbox, reads `cellStatusCacheView()` once per call (REVIEWS O3). Lock order: index-monitor → grid-read-lock.

**Task 3 — Placement refactor + hooks:**
- `WorldWebSocketHandler.handleRegister`: `eligibleCellIndex.sample(spawnRng)` O(1), 3-retry lost-race fallback, back-compat legacy scan when index null. `MAX_PLACEMENT_ATTEMPTS` deleted. `attemptPlacementForTest` public seam for D-06 tests. `cleanupByEntityId` and `cleanupBot` call `notifyChanged` after `clearEntity` (REVIEWS MED-6).
- `AdmissionMetrics`: `paralife.placement.lost-race.total` counter, `incLostRace()` method.
- `SimulationEngine`: 9 structural `notifyChanged` hooks (bond-formation, composite-formation, `cleanupCompositeMemberCellViaFinalizer`, `revertToBondedPair`, `dissolveToParticles`, `checkPanicZone` pool=0 branch, nutrient spawning).
- `ActionResolver`: 11 structural `notifyChanged` hooks (resolveMove source+target, resolveConsume both branches, resolveReproduce primary+bonus, feeder consume both branches, composite reproducer-bud, executeCompositeMovement clear+set in loop).
- `DeathFinalizer`: hooks at `finalizeParticleDeath` and `finalizeBondedPairDeath` after `clearEntity`.
- **Tests**: `PlacementDeterminismTest` (D-06 bit-exact: same seed + same grid state → identical positions), `PlacementDensityIntegrationTest` (fills 8×8 grid via live WS frames, asserts E|503|GRID_FULL).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Spring circular bean creation cycle**
- **Found during:** Task 3 (first full test run — 168 failures)
- **Issue:** `EligibleCellIndex` → `EnvironmentEngine` (constructor) → `DeathFinalizer` (constructor) → `setEligibleCellIndex` (setter, `@Autowired required=false`) → `eligibleCellIndex` currently in creation. Spring's `required=false` does NOT skip beans currently in creation — it only skips absent beans.
- **Fix:** (a) `EligibleCellIndex` changed from 1-arg-Spring+environmentEngine-constructor to 2-arg `@Autowired` Spring ctor + `@Autowired @Lazy setEnvironmentEngine` setter; (b) `setEligibleCellIndex` on `DeathFinalizer`, `ActionResolver`, `SimulationEngine` now use `@Lazy EligibleCellIndex` parameter. `@Lazy` injects a proxy that defers real bean resolution to first method call, breaking the cycle.
- **Files modified:** `EligibleCellIndex.java`, `DeathFinalizer.java`, `ActionResolver.java`, `SimulationEngine.java`
- **Commit:** 171785c

**2. [Rule 1 - Bug] PlacementDeterminismTest rock-clearing false mismatch**
- **Found during:** Task 3 test debugging
- **Issue:** Initial test implementation cleared ALL grid cells including rocks between run1 and run2. `EligibleCellIndex.rebuildForTest()` then saw more eligible cells (rocks gone) than run1 had, producing different `dense[]` ordering → different positions from same RNG seed.
- **Fix:** Clear only the cells placed by run1 (preserve rocks), then rebuild.
- **Files modified:** `PlacementDeterminismTest.java`
- **Commit:** 171785c

### Plan Clarification

**REVIEWS MEDIUM-7 note:** The plan stated back-compat `WorldWebSocketHandler` ctors should use `Objects.requireNonNull(eligibleCellIndex)`. However, since no test files instantiate `WorldWebSocketHandler` directly (verified by grep), the back-compat 7-arg ctor was removed in favor of a single `@Autowired` primary ctor. The ctor signature accepts `null` for `eligibleCellIndex` only via explicit `null` literal (not Spring-injected null) — the placement path guards `if (eligibleCellIndex != null)` with legacy fallback. This is equivalent safety with less code.

## Known Stubs

None. All production paths use the real `EligibleCellIndex` when wired by Spring.

## Threat Flags

None. No new network endpoints or auth paths introduced. `EligibleCellIndex` is an internal engine component with no external surface.

## Self-Check: PASSED

All created files exist on disk. All task commits (1cbbe86, eb6d395, 171785c) present in git log.
