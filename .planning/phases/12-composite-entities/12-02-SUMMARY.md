---
phase: 12-composite-entities
plan: 02
subsystem: engine
tags: [composite-formation, energy-distribution, tick-pipeline]
dependency_graph:
  requires: [CompositeMember, Role, CompositeConfig, CompositeRegistry]
  provides: [CompositeFormation, CompositeEnergyDistributor]
  affects: [SimulationEngine.java, SimulationEngineTest.java]
tech_stack:
  added: []
  patterns: [snapshot-deferred-write, tick-pipeline-ordering]
key_files:
  created:
    - src/main/java/com/paralife/engine/CompositeEnergyDistributor.java
    - src/test/java/com/paralife/engine/CompositeFormationTest.java
    - src/test/java/com/paralife/engine/CompositeEnergyDistributorTest.java
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/test/java/com/paralife/engine/SimulationEngineTest.java
decisions:
  - "CompositeEnergyDistributor finds members via CompositeRegistry position tracking rather than BotRegistry coupling"
  - "Composite formation scan uses separate scannedForComposite set during snapshot phase to prevent double-pairing before deferred writes"
  - "Passive drain tests isolate drain behavior by setting shared pool to 0"
metrics:
  duration_seconds: 553
  completed: "2026-04-13T17:13:10Z"
  tasks_completed: 2
  tasks_total: 2
  tests_added: 17
  files_created: 3
  files_modified: 2
---

# Phase 12 Plan 02: Formation & Energy Distribution Summary

Composite formation logic in SimulationEngine for BondedPair merging, and CompositeEnergyDistributor at @Order(15) for dual energy accounting with passive drain and shared pool healing.

## One-liner

Adjacent BondedPairs merge into CompositeMember entities via deferred-write formation, CompositeEnergyDistributor handles per-tick passive drain and shuffled pool healing at @Order(15).

## Completed Tasks

| Task | Name | Commit | Key Changes |
|------|------|--------|-------------|
| 1 | Composite formation from adjacent BondedPairs | 5295283 | SimulationEngine formation logic + CompositeFormationTest (9 tests) |
| 2 | CompositeEnergyDistributor tick pipeline component | 5a71fbd | CompositeEnergyDistributor.java + CompositeEnergyDistributorTest (8 tests) |

## Implementation Details

### Task 1: Composite formation from adjacent BondedPairs in SimulationEngine

- Added `CompositeRegistry` and `CompositeConfig` as constructor dependencies to SimulationEngine
- Added `CompositeFormation` sealed InteractionResult type alongside CombatDelta and BondFormation
- Composite formation scan: after particle interactions, scans all BondedPair positions (shuffled), pairs adjacent BondedPairs using `scannedForComposite` set to prevent double-pairing
- Formation apply: creates two CompositeMember entities from BondedPairs, assigns FEEDER/LOCOMOTOR roles based on surface constraint (D-09: more empty neighbors = FEEDER)
- Energy accounting: individual member energy = half of source BondedPair energy, shared pool = sum of both BondedPair energies
- BotRegistry update: maps original BondedPair entity IDs (primary/secondary for both pairs) to new CompositeMember IDs via `updateBotRegistryForFormation` helper
- `claimedForBonding` set extended to block both bond and composite formation on same positions
- Return value changed from `int[2]` to `int[3]` (combat, bonds, composites)
- Removed early return when no particles present (would skip composite formation scan)
- Updated SimulationEngineTest constructor calls for new 6-parameter signature
- 9 tests in CompositeFormationTest

### Task 2: CompositeEnergyDistributor tick pipeline component

- New `@Component` with `@EventListener @Order(15)` on `onTick(TickEvent)`
- Iterates all composites via `compositeRegistry.getAll()`
- For each composite, shuffles member list before processing (prevents healing starvation)
- Phase 1 (passive drain): decrements member energy by role-specific passive drain rate
- Phase 2 (healing): members below maxEnergy draw from shared pool; heal amount = min(passiveDrain, deficit)
- Uses `composite.getPositionForMember()` to find members on grid (decoupled from BotRegistry)
- Exhaustive switch over all 6 Role values for passive drain rate lookup
- Updates grid via `worldGrid.setEntity()` with `member.withEnergy()`
- 8 tests including RepeatedTest for shuffle verification

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Early return in processInteractions blocked composite formation**
- **Found during:** Task 1 implementation
- **Issue:** `if (particlePositions.isEmpty()) return new int[]{0, 0}` would short-circuit before composite formation scan when no particles existed
- **Fix:** Removed early return so composite formation scan always runs
- **Files modified:** SimulationEngine.java

**2. [Rule 1 - Bug] Drain-only tests failed due to healing interference**
- **Found during:** Task 2 TDD GREEN
- **Issue:** Tests for passive drain expected energy-1 but members below maxEnergy were getting healed from shared pool in the same tick
- **Fix:** Set shared pool to 0 in drain-only tests to isolate the behavior under test
- **Files modified:** CompositeEnergyDistributorTest.java

## Verification

- `./gradlew test --tests "com.paralife.engine.CompositeFormationTest"` -- 9 tests pass
- `./gradlew test --tests "com.paralife.engine.CompositeEnergyDistributorTest"` -- 8 tests pass (incl. 5x RepeatedTest)
- `./gradlew test` -- full suite green, no regressions

## Self-Check: PASSED

All 3 created files exist. All 2 modified files exist. Both commits (5295283, 5a71fbd) verified in git log. SUMMARY.md exists.
