---
phase: 13-energy-metabolism-system
plan: 03
subsystem: engine
tags: [fertility, seasons, nutrient-spawning, soil-fertility, cosine-cycle, tick-broadcast]
requires:
  - WorldGrid (existing)
  - Cell.withNutrientLevel (existing, 06-era)
  - SimulationConfig.nutrientSpawnProbability (existing)
  - MetabolicProfile (13-01)
  - StarvationConfig (13-01)
provides:
  - FertilityConfig @ConfigurationProperties (paralife.simulation.fertility)
  - SeasonsConfig @ConfigurationProperties (paralife.simulation.seasons)
  - SeasonTracker @Component — stateless cosine-based season/multiplier math
  - FertilityInitializer @Component @PostConstruct — radial falloff, max-merge, toroidal
  - processNutrientSpawning(width, height, tickNumber) — fertility + seasonal modulated
  - Messages.Tick.seasonPhase (String) and Messages.Tick.seasonalMultiplier (double)
affects:
  - SimulationEngine constructor (now 9-arg, adds SeasonTracker)
  - SimulationEngine.processTick — passes tickNumber through to spawning
  - TickBroadcaster constructor (now 6-arg, adds SeasonTracker)
  - TickBroadcaster.onTick — populates new Messages.Tick season fields
  - application.yml — adds paralife.simulation.fertility and paralife.simulation.seasons
tech-stack:
  added: []
  patterns:
    - "@ConfigurationProperties records on disjoint sub-prefixes under paralife.simulation"
    - "@PostConstruct for one-shot world initialization (mirrors TickEngine's pattern)"
    - "Stateless pure-function bean for temporal math (SeasonTracker — no mutable state)"
    - "Math.floorMod for toroidal wrapping in patch generation"
    - "Math.clamp on computed probability to keep it in [0, 1] even with compounded multipliers"
key-files:
  created:
    - src/main/java/com/paralife/engine/FertilityConfig.java
    - src/main/java/com/paralife/engine/SeasonsConfig.java
    - src/main/java/com/paralife/engine/SeasonTracker.java
    - src/main/java/com/paralife/engine/FertilityInitializer.java
    - src/test/java/com/paralife/engine/FertilityInitializerTest.java
    - src/test/java/com/paralife/engine/SeasonTrackerTest.java
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/websocket/Messages.java
    - src/main/java/com/paralife/websocket/TickBroadcaster.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/engine/SimulationEngineTest.java
    - src/test/java/com/paralife/engine/CompositeFormationTest.java
    - src/test/java/com/paralife/engine/CompositeCombatTest.java
    - src/test/java/com/paralife/engine/CompositeDissolutionTest.java
    - src/test/java/com/paralife/websocket/TickBroadcasterTest.java
decisions:
  - id: cosine-not-sine-for-tick-zero-spring-peak
    summary: >
      Used Math.cos (not Math.sin) in SeasonTracker.getSeasonalMultiplier so
      tick 0 lands on the spring peak (multiplier = 1 + amplitude). CONTEXT.md
      D-14 describes a sine wave, but sin(0)=0 would put tick 0 at baseline,
      not spring abundance. Documented as the phase-corrected version.
  - id: season-enum-quarters-by-tick-position
    summary: >
      Season enum (SPRING/SUMMER/AUTUMN/WINTER) is computed purely from
      position within the year — each season owns a 25% quarter. tick=0
      maps to SPRING, yearLength/4 maps to SUMMER, etc. Kept independent
      from the cosine multiplier so season labels are intuitive.
  - id: patch-radius-int-range-edge-case
    summary: >
      ThreadLocalRandom.nextInt(min, max+1) throws when min == max+1
      (i.e. min == max). Added a guard in initializeFertility that skips
      the range call and uses patchMinRadius directly when min == max.
      Tests exercise this with patchMinRadius == patchMaxRadius.
  - id: default-season-tracker-amplitude-zero-for-legacy-tests
    summary: >
      Legacy SimulationEngineTest fixtures (and Composite* tests) inject a
      SeasonTracker with amplitude=0 so the seasonal multiplier is always
      1.0. This preserves pre-Phase-13 assertions on flat
      nutrientSpawnProbability. Production code uses amplitude=0.5 from
      application.yml.
  - id: clamp-effective-rate-to-unit-interval
    summary: >
      baseRate * (1 + level/100) * (1 + amplitude) can exceed 1.0 when
      a fully fertile cell (level=100) sits in spring peak. Clamped to
      [0, 1] with Math.clamp so rng.nextDouble() < effectiveRate stays
      well-defined. Verified by fertilityClampsAtFullProbability test.
  - id: seasonalMultiplier-not-fertilityMultiplier-field-name
    summary: >
      Messages.Tick's new field is named seasonalMultiplier to distinguish
      from per-cell Cell.nutrientLevel (soil fertility). Addresses cross-AI
      review naming concern flagged in 13-03-PLAN review_changes.
metrics:
  duration: "~25 min"
  completed: 2026-04-15
---

# Phase 13 Plan 03: Fertility Patches + Seasonal Nutrient Cycles Summary

Soil fertility becomes active resource geography via world-init patches; global cosine-based seasonal cycles drive nutrient spawn boom/bust; season phase and multiplier broadcast in every Tick message.

## What changed

- **FertilityConfig** — `@ConfigurationProperties(prefix = "paralife.simulation.fertility")` record with `patchCount`, `patchMinRadius`, `patchMaxRadius`, `maxLevel`. Validates `patchCount >= 0`, `patchMinRadius > 0`, `patchMaxRadius >= patchMinRadius`, `maxLevel > 0`. Defaults: 20 patches of radius 3-8, max level 100.

- **SeasonsConfig** — `@ConfigurationProperties(prefix = "paralife.simulation.seasons")` record with `yearLengthTicks` and `amplitude`. Validates `yearLengthTicks > 0` and `amplitude in [0, 1]`. Defaults: 200-tick year, 0.5 amplitude (±50% spawn swing).

- **SeasonTracker** — `@Component` stateless bean. `getSeasonalMultiplier(tick)` returns `1 + amplitude * cos(2*PI*tick/yearLength)` — cosine (not sine per CONTEXT.md D-14) so tick 0 = spring peak (multiplier = 1 + amplitude). `getSeason(tick)` partitions the year into four equal quarters, returning `Season.{SPRING, SUMMER, AUTUMN, WINTER}`. Reference points at yearLength=200, amplitude=0.5: tick=0→SPRING/1.5, tick=50→SUMMER/1.0, tick=100→AUTUMN/0.5, tick=150→WINTER/1.0.

- **FertilityInitializer** — `@Component` with `@PostConstruct initializeFertility()`. Generates `patchCount` patches at random positions with random radii in `[patchMinRadius, patchMaxRadius]`. Each patch is a disk with radial linear falloff from `maxLevel` at center to 0 at edge. Uses `Math.max(cell.nutrientLevel(), level)` for overlapping patches (max-merge) and `Math.floorMod` for toroidal wrapping. Package-private `generatePatch(cx, cy, radius, width, height)` exposed for direct test invocation.

- **SimulationEngine** — constructor now 9-arg (adds `SeasonTracker`). `processTick(tick)` threads `tickNumber` into `processNutrientSpawning(width, height, tickNumber)`. The inner loop computes `effectiveRate = baseRate * fertilityMultiplier * seasonalMultiplier` per cell where `fertilityMultiplier = 1 + cell.nutrientLevel()/100`. `Math.clamp(effectiveRate, 0.0, 1.0)` keeps it a valid probability even when compounded multipliers would exceed 1.

- **Messages.Tick** — record expanded from 5 to 7 fields. New `seasonPhase` (String — season enum name) and `seasonalMultiplier` (double). Field is named `seasonalMultiplier` (not `fertilityMultiplier`) to avoid confusion with per-cell fertility.

- **TickBroadcaster** — constructor now 6-arg (adds `SeasonTracker`). `onTick` populates `seasonPhase = seasonTracker.getSeason(tick).name()` and `seasonalMultiplier = seasonTracker.getSeasonalMultiplier(tick)`.

- **application.yml** — adds `paralife.simulation.fertility` (20 patches, 3-8 radius, 100 max) and `paralife.simulation.seasons` (200-tick year, 0.5 amplitude).

## Test coverage

- **SeasonTrackerTest** (new) — 17 tests. Cosine math at tick 0/50/100/150/200 (spring peak, equinoxes, trough), multiplier bounds across full year, zero-amplitude no-swing, statelessness; season enum cycling including year wrap; config validation (zero year, negative/above-1 amplitude).

- **FertilityInitializerTest** (new) — 11 tests. FertilityConfig validation (negative patchCount, zero min radius, max < min, zero maxLevel); patch generation producing non-zero cells; `patchCount=0` skip; center has max level; falloff (center > edge > 0); max-merge preserving higher pre-existing value; max-merge does not lower existing; toroidal wrap at (0, 0); initializeFertility preserves values higher than any patch's maxLevel.

- **SimulationEngineTest.NutrientSpawnTests** (extended) — 3 new tests: `fertilityClampsAtFullProbability` with all cells at level=100 and baseRate=1.0 (every empty cell gets a nutrient even though effectiveRate would be 2.0 without clamp); `autumnTroughSpawnsNothingWithLowBaseRate` at amplitude=1.0, tick=100 (seasonal multiplier = 0, zero spawns); `springPeakOutSpawnsAutumnTrough` (probabilistic RepeatedTest(3) comparing counts at tick 0 vs tick 100).

- **TickBroadcasterTest** (extended) — 3 new assertions/tests: `tickMessageContainsExpectedFields` now verifies `seasonPhase` and `seasonalMultiplier` are present; `tickMessageIncludesSpringAtTickZero` (SPRING/1.5); `tickMessageReportsAutumnTroughAtHalfYear` (AUTUMN/0.5).

- **Legacy test fixtures** — `SimulationEngineTest.defaultSeasonTracker()` returns `SeasonTracker(SeasonsConfig(200, 0.0))` so the seasonal multiplier is always 1.0 and pre-Phase-13 spawn-probability assertions continue to hold. `CompositeFormationTest`, `CompositeCombatTest`, and `CompositeDissolutionTest` pass the same zero-amplitude tracker.

Total: **full suite passes** after Task 2 (standalone tests: 31 passed across the new tracker + initializer suites, plus all extended broadcaster + spawn tests). Two commits, one per task.

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 3 — Blocking runtime] FertilityInitializer guards against `ThreadLocalRandom.nextInt(min, max)` when `min == max`**
- **Found during:** Task 1 test design review.
- **Issue:** `nextInt(min, max)` requires `min < max`; throws `IllegalArgumentException` otherwise. The plan's `rng.nextInt(config.patchMinRadius(), config.patchMaxRadius() + 1)` fails when `patchMinRadius == patchMaxRadius`, which is a natural config (e.g., tests using fixed-size patches).
- **Fix:** Added a `min == max` short-circuit that uses the fixed radius directly.
- **Files modified:** `FertilityInitializer.java`
- **Commit:** `0dbb75d`

**2. [Rule 2 — Missing fixture] Default SeasonTracker injected in 4 test classes**
- **Found during:** Task 2 — SimulationEngine constructor signature changed to 9-arg.
- **Issue:** `SimulationEngineTest`, `CompositeFormationTest`, `CompositeCombatTest`, `CompositeDissolutionTest` all directly construct `SimulationEngine`. Without an explicit tracker, the full test suite won't compile.
- **Fix:** Each fixture now injects `new SeasonTracker(new SeasonsConfig(200, 0.0))` (amplitude=0 → multiplier=1.0 always). This keeps pre-Phase-13 nutrient-spawn assertions exact.
- **Files modified:** `SimulationEngineTest.java`, `CompositeFormationTest.java`, `CompositeCombatTest.java`, `CompositeDissolutionTest.java`
- **Commit:** `f04d841`

**3. [Rule 2 — Missing imports] Added `SeasonTracker` + `SeasonsConfig` imports to `TickBroadcaster` and `TickBroadcasterTest`**
- **Found during:** Task 2.
- **Fix:** Updated imports alongside the constructor signature change.
- **Commit:** `f04d841`

### Auth gates
None.

## Self-Check: PASSED

File existence:
- FOUND: `src/main/java/com/paralife/engine/FertilityConfig.java`
- FOUND: `src/main/java/com/paralife/engine/SeasonsConfig.java`
- FOUND: `src/main/java/com/paralife/engine/SeasonTracker.java`
- FOUND: `src/main/java/com/paralife/engine/FertilityInitializer.java`
- FOUND: `src/test/java/com/paralife/engine/FertilityInitializerTest.java`
- FOUND: `src/test/java/com/paralife/engine/SeasonTrackerTest.java`
- FOUND: modifications to `SimulationEngine.java`, `Messages.java`, `TickBroadcaster.java`, `application.yml`, plus test fixtures.

Commits:
- FOUND: `0dbb75d` feat(13-03): fertility patches and cosine seasonal cycle (Task 1)
- FOUND: `f04d841` feat(13-03): wire fertility + seasonal modulation into spawning and Tick (Task 2)

Verification:
- `./gradlew test --tests "com.paralife.engine.FertilityInitializerTest" --tests "com.paralife.engine.SeasonTrackerTest"` → BUILD SUCCESSFUL.
- `./gradlew test --tests "com.paralife.engine.SimulationEngineTest" --tests "com.paralife.websocket.TickBroadcasterTest"` → BUILD SUCCESSFUL.
- `./gradlew test -x jacocoTestReport` → BUILD SUCCESSFUL (full suite).

## Known caveats

- **No integration test for multi-year dynamics yet** — the plan's Task list covers unit behavior of fertility + seasons. Plan 13-04 (deferred) is expected to exercise ≥600 ticks and verify population oscillations track the cosine cycle.
- **Fertility patches are static** — no regeneration, consumption doesn't deplete, corpses don't fertilize. All deferred ideas from CONTEXT.md.
- **`processOvercrowding` still runs before spawn** — the tick ordering (interactions → decay → overcrowding → deaths → spawn) is unchanged. Fertility/season modulators act only on the final phase.
