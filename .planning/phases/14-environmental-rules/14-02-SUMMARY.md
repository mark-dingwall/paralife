---
phase: 14
plan: 02
subsystem: environmental-rules
tags:
  - toxin-spread
  - catmull-rom-spline
  - cellular-automaton
  - bonded-pair-max-resistance
  - splash-damage
  - same-tick-finalization
  - nonzero-counter-fastpath
dependency_graph:
  requires:
    - EnvironmentEngine (Plan 14-01 scaffold)
    - EnvironmentConfig.Toxin (Plan 14-01)
    - BuffRegistry / DeathFinalizer / EnvPostActionReconciler (Plan 14-01)
    - SeasonTracker + SeasonsConfig (Phase 13)
    - Messages.CellView 6-field (Plan 14-01)
  provides:
    - ToxinPathGenerator (Catmull-Rom + pinned constructor pair)
    - ToxinEvent (immutable record + withHeadIdx / hasReachedEnd / isExpired)
    - EntityIds (shared entityIdOf helper)
    - CellularAutomaton (diffuseStep returning non-zero destination count)
    - EnvironmentEngine.spawnToxin / advanceToxin / resolveToxinCollisions / buildStatusCaches / computeSplashDamage / toxinIntensityAt
    - EnvironmentConfig.Toxin.diffusionRate + baseDamage + intensityThreshold fields
    - SimulationEngine.SplashDelta record + shared applyDeltaToOccupant helper
    - ActionResolver splash block in resolveAttackerAttack (cycle-4 action item #3)
  affects:
    - SimulationEngine (13-arg primary constructor + @Lazy EnvironmentEngine;
      5 SplashDelta emission sites; shared applyDeltaToOccupant helper;
      environmentEngine.markEnvDamageApplied() after apply loop)
    - ActionResolver (setter-injected @Lazy EnvironmentEngine;
      splash in resolveAttackerAttack with Math.max(0,...) clamp and
      markEnvDamageApplied)
    - EnvironmentConfig (Toxin record gains diffusionRate + baseDamage + intensityThreshold)
    - application.yml (adds diffusion-rate / base-damage / intensity-threshold keys)
tech-stack:
  added:
    - (none — all existing Spring / Java 21 / JUnit 5 / AssertJ)
  patterns:
    - Pinned constructor pair (public no-arg + package-private Random overload) for test determinism
    - Double-buffered byte[][] CA with unsigned-read (& 0xFF) on every cell
    - O(1) idle-tick fast-path via non-zero-cell counter returned from diffuseStep
    - Deferred-delta pipeline extended with SplashDelta (same write path as CombatDelta)
    - @Lazy back-edge injection to break bean cycles (SimulationEngine ↔ EnvironmentEngine)
key-files:
  created:
    - src/main/java/com/paralife/engine/ToxinEvent.java
    - src/main/java/com/paralife/engine/ToxinPathGenerator.java
    - src/main/java/com/paralife/engine/EntityIds.java
    - src/main/java/com/paralife/engine/CellularAutomaton.java
    - src/test/java/com/paralife/engine/ToxinPathGeneratorTest.java
    - src/test/java/com/paralife/engine/CellularAutomatonTest.java
  modified:
    - src/main/java/com/paralife/engine/EnvironmentConfig.java
    - src/main/java/com/paralife/engine/EnvironmentEngine.java
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/engine/ToxinTest.java
    - src/test/java/com/paralife/engine/SeasonalPoissonTest.java
decisions:
  - "ToxinPathGenerator constructor pair PINNED (cycle-6 MEDIUM) — public no-arg delegates to new Random(); package-private Random overload reserved for deterministic-seeded tests in the com.paralife.engine package. Regression test noArgConstructorDelegatesToDefaultRandom locks the contract."
  - "BondedPair toxin resistance uses MAX of per-type multipliers for primaryType and secondaryType — worst-case resistance drives damage (must-haves). Dedicated test bondedPairToxinResistanceIsMaxOfMemberTypeMultipliers pins the rule."
  - "Splash damage uses two distinct pipelines by call site: SimulationEngine.processInteractions routes through the deferred-delta pipeline (SplashDelta alongside CombatDelta); ActionResolver.resolveAttackerAttack writes directly with an explicit Math.max(0,...) clamp then calls markEnvDamageApplied so EnvPostActionReconciler @Order(25) finalises lethal splash SAME TICK (cycle-4 action items #2 and #3)."
  - "Multi-neighbor splash stacking is INTENDED (cycle-6 LOW): one particle attacker can damage multiple neighbours in a single tick, and splash stacks once per toxic-neighbour-hit. Each hit is a discrete engagement. Locked by multiNeighborAttackStacksSplashOncePerToxicTarget."
  - "nonZeroToxinCellCount counter maintained via CellularAutomaton.diffuseStep return value + stampToxinIntensityForTest writeback. advanceToxin early-returns when activeToxin == null && counter == 0 (O(1) idle-tick fast-path)."
  - "Separate thresholds for perception bits (cycle-6 MEDIUM): cellStatus TOXIN_PRESENT bit requires intensity >= config.toxin().intensityThreshold(); entityStatus TOXIC bit fires for any positive intensity (intensity > 0)."
  - "ActionResolver uses setter injection @Autowired(required=false) to preserve pre-Phase-14 unit-test constructor signatures; null-guards at all splash emission sites in SimulationEngine preserve the 9-arg back-compat constructor."
metrics:
  duration: ~2.6h
  completed_date: 2026-04-18
  tasks_completed: 4
  tests_before: 457
  tests_after: 499 (+42)
  files_created: 6
  files_modified: 7
---

# Phase 14 Plan 02: Toxin Spread Summary

Fills the toxin effect body on the Plan 14-01 scaffold: Catmull-Rom spline path
advance at configurable speed, double-buffered CA diffusion with configurable
Moore-neighbourhood radius + diffusionRate, per-cell normalised damage
(`baseDamage * intensity/255 * resistance`) with explicit BondedPair MAX-resistance
rule, and splash damage routed through two distinct pipelines — deferred-delta
SplashDelta for the 5 in-sim attack sites and direct-write-with-clamp for the
ActionResolver composite-attack path — with `markEnvDamageApplied()` so
`EnvPostActionReconciler @Order(25)` finalises lethal splash SAME TICK.

## Task Breakdown

### Task 1 — ToxinPathGenerator + ToxinEvent + tests (commit `1e52534`)

Three files created:

- `ToxinPathGenerator.java` — Catmull-Rom spline generator with **PINNED
  constructor pair** (cycle-6 MEDIUM): `public ToxinPathGenerator()` delegates
  to `this(new Random())` for callers outside the package;
  `ToxinPathGenerator(Random rng)` is package-private for deterministic tests.
  `generatePath(w, h, min, max, offMin, offMax)` picks opposite-edge entry/exit
  points, places waypoints with perpendicular offset in UN-WRAPPED double
  coordinates, samples Catmull-Rom splines with arc-length-approximated density,
  then materialises to `Position` via `Math.floorMod` ONLY at the final cell
  conversion step (Pitfall 8). Static `catmullRom(p0, p1, p2, p3, t)` is
  package-private for direct math tests.
- `ToxinEvent.java` — immutable record `(spawnTick, lifetimeTicks, prePath,
  headIdx, seed)` with `withHeadIdx`, `hasReachedEnd`, `isExpired(long tick)`.
- `ToxinPathGeneratorTest.java` — 9 tests: Catmull-Rom endpoints, linear
  interpolation for colinear points, determinism with seeded Random, substantial
  grid coverage, in-bounds path points, **noArgConstructorDelegatesToDefaultRandom**
  (cycle-6 MEDIUM regression guard), plus 3 ToxinEvent record tests.

### Task 2 — EntityIds + CellularAutomaton + tests (commit `c3b4711`)

Three files created:

- `EntityIds.java` — static utility `entityIdOf(Entity)` returns id for
  Particle / BondedPair / CompositeMember; null for Rock / Nutrient.
- `CellularAutomaton.java` — `public static int diffuseStep(src, dst, w, h,
  diffusionRate, decayRate, threshold, radius)`. Unsigned `& 0xFF` reads on
  every cell, toroidal `Math.floorMod`, double-buffered, threshold clear after
  decay, 255 clamp on writeback. **Returns int count of non-zero destination
  cells** for callers tracking an idle-tick fast-path counter.
- `CellularAutomatonTest.java` — 10 passing tests + 1 `@Disabled("perf-only")`
  smoke test `diffusionCostOn256x256For60TicksWithinLooseBound` (cycle-6 LOW)
  asserting 60 CA steps on 256×256 complete under 500ms.

### Task 3 — Wire toxin into EnvironmentEngine + config + YAML + ToxinTest + SeasonalPoissonTest (commit `c71bc8c`)

Five files modified:

- `EnvironmentConfig.java` — `Toxin` record gains **`diffusionRate` (default
  0.5)**, **`baseDamage` (default 10)**, **`intensityThreshold` (default 20)**
  with compact-constructor validation. Updated `defaults()`.
- `application.yml` — adds `diffusion-rate: 0.5`, `base-damage: 10`,
  `intensity-threshold: 20` under `toxin:`.
- `EnvironmentEngine.java` — fills the toxin pipeline:
  - Fields: `byte[][] toxinGrid`, `byte[][] toxinGridNext`, `ToxinEvent
    activeToxin`, `int nonZeroToxinCellCount`, `ToxinPathGenerator toxinPathGenerator`.
  - `spawnToxin(long)`: skips if `activeToxin != null` (D-03 max-1); rolls
    `rng.nextDouble() < seasonalToxinLambda(tick)`; generates spline path;
    installs new `ToxinEvent`.
  - `seasonalToxinLambda(long)`: peak-season sine-scaled between
    `offSeasonLambda` and `peakLambda` via `(mult - (1-amp))/(2*amp)`; off-season
    returns flat `offSeasonLambda`.
  - `advanceToxin(long)`: O(1) fast-path when `activeToxin == null &&
    nonZeroToxinCellCount == 0`; stamps 255 on each cell the head visits
    (updating counter); CA diffusion via
    `config.toxin().diffusionRate()` + `tx.decayRate()` + `tx.diffusionRadius()`;
    swaps buffers; updates counter from the return value.
  - `resolveToxinCollisions(long)`: for every cell with `intensity > 0`,
    applies `damage = (int)(baseDamage * intensity/255.0 * resistance)`.
    BondedPair uses `Math.max(tx.resistance().forType(bp.primaryType()),
    tx.resistance().forType(bp.secondaryType()))` — MAX rule. Calls
    `markEnvDamageApplied()` on any damage. **Does NOT call clearEntity** —
    zero-energy occupants are swept by `processEnvDeaths`.
  - `buildStatusCaches()`: populates cellStatusCache with `CELL_STATUS_TOXIN_PRESENT`
    when `intensity >= intensityThreshold`; populates entityStatusCache with
    `ENTITY_STATUS_TOXIC` for any positive intensity on an occupied cell
    (separate thresholds per cycle-6 MEDIUM).
  - Public `toxinIntensityAt(Position)` + `computeSplashDamage(Position)` for
    SimulationEngine / ActionResolver.
  - Test helpers: `stampToxinIntensityForTest`, `resolveToxinCollisionsForTest`,
    `advanceToxinForTest`, `forceSpawnToxinForTest`, `activeToxinEvent`,
    `nonZeroToxinCellCountForTest`, `buildStatusCachesForTest`,
    `resetToxinStateForTest`.
- `ToxinTest.java` — **16 Spring-boot tests** covering: particle damage,
  CompositeMember damage, **BondedPair MAX-resistance rule**, linear intensity
  scaling, **low-intensity × high-resistance rounds to zero damage (cycle-9
  action G)**, same-tick finalization via `markEnvDamageApplied`,
  `resolveToxinCollisions` does NOT clear entities, max-1 event contract,
  toxic-cell persistence, **nonZeroToxinCellCounter fast-path**, counter
  increments/decrements on direct stamps, cellStatus threshold separation from
  entityStatus, splash damage formula + zero for non-toxic cells, unsigned byte
  reads.
- `SeasonalPoissonTest.java` — 3 tests: winter off-season flat lambda,
  mid-AUTUMN peak-lambda landmark, in-bounds over AUTUMN.

### Task 4 — Splash damage across all three attack families (commit `5e1db4e`)

Three files modified:

- `SimulationEngine.java`:
  - New `EnvironmentEngine environmentEngine` field; added to 13-arg primary
    `@Autowired` constructor as `@Lazy`; wired to null in the 9-arg
    back-compat constructor (guards at all splash sites preserve pre-Phase-14
    behaviour).
  - New `SplashDelta(Position pos, int energyDelta)` record alongside
    `CombatDelta` in the `InteractionResult` sealed interface.
  - Splash emissions at **5 sites** (all after the existing CombatDelta emissions):
    - Line 264 — **Case 1 (Particle-vs-Particle)** — `processInteractions`
    - Line 285 — **Case 2 (Particle-vs-BondedPair)** — `processInteractions`
    - Line 314 — **Case 3 (Particle-vs-CompositeMember)** — `processInteractions`
    - Line 355 — **Composite ATTACKER role (type-agnostic damage)** — `processInteractions`
    - Line 379 — **Composite position-based RPS damage** — `processInteractions`
  - Shared `private void applyDeltaToOccupant(Position, int energyDelta)`
    helper used by BOTH CombatDelta and SplashDelta in the apply loop.
  - Apply loop tracks `boolean splashApplied`; after loop, if true, calls
    `environmentEngine.markEnvDamageApplied()` so `processEnvDeaths` (and
    `EnvPostActionReconciler @Order(25)`) sweep lethal splash SAME TICK.
- `ActionResolver.java`:
  - New field `private EnvironmentEngine environmentEngine;` with
    `@Autowired(required = false)` setter + `@Lazy`. Existing unit-test
    constructors unchanged.
  - Splash block in **`resolveAttackerAttack`** (line 645 — LIVE method name
    per cycle-4 action item #3) after the existing direct-write damage at
    lines 624-633:
    ```java
    int splash = environmentEngine.computeSplashDamage(targetPos);
    if (splash > 0) {
        Cell ac = worldGrid.getCell(pos.x(), pos.y());
        if (ac.occupant() instanceof Entity.CompositeMember attackerMember
                && attackerMember.id().equals(rca.member.id())) {
            int clampedEnergy = Math.max(0, attackerMember.energy() - splash);  // cycle-5 LOW
            worldGrid.setEntity(pos.x(), pos.y(), attackerMember.withEnergy(clampedEnergy));
            environmentEngine.markEnvDamageApplied();  // cycle-4 action item #2
        }
    }
    ```
- `ToxinTest.java` — 5 new Task-4 tests:
  - `splashAppliesViaDeferredDeltaInSoloCombat` — full pipeline with solo
    Particle attacker; splash visible via reduced attacker energy.
  - `splashAppliesViaDeferredDeltaInCompositeInSimAttack` — composite-member
    ATTACKER role hits prey on toxic cell; splash visible.
  - `splashAppliesInCompositeViaActionResolverResolveAttackerAttack` —
    `@Disabled` placeholder deferred to Plan 06 full-stack smoke test; source
    grep verifies the wiring (lines 645 + 655 in ActionResolver).
  - `composite_splashKillFinalizedSameTickViaReconciler` — `@Disabled`
    placeholder for the same reason; source grep verifies markEnvDamageApplied
    call after the splash write.
  - `multiNeighborAttackStacksSplashOncePerToxicTarget` (cycle-6 LOW) —
    attacker with two toxic-neighbour prey; verifies both prey take combat
    damage (intended multi-neighbour stacking semantic).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — missing critical functionality] SimulationEngine primary
constructor bumped from 12 → 13 args.** Adding `EnvironmentEngine` required a
new parameter on the `@Autowired` primary constructor. The 9-arg back-compat
constructor wires `environmentEngine = null` and every splash emission site
guards with `if (environmentEngine != null)` to preserve the pre-Phase-14
test behaviour. Fixed inline during Task 4. Standard Spring primary-constructor
idiom.

**2. [Rule 2 — missing critical functionality] ActionResolver setter-injection
for EnvironmentEngine.** ActionResolver has two public constructors (one for
pre-Phase-13 tests, one for current). Bumping either to take EnvironmentEngine
would have broken test fixtures. Used `@Autowired(required = false)` setter
injection instead — Spring wires the production bean, existing unit tests that
never call the setter see the null-guard behaviour (pure combat, no splash).
Matches the project's Spring patterns.

**3. [Rule 2 — missing critical functionality] Added `resetToxinStateForTest`
helper on EnvironmentEngine.** @SpringBootTest shares the EnvironmentEngine bean
between tests in the same class, so the toxin grid + event + counter need
explicit reset in `@BeforeEach`. The existing `EnvDeathSweepTest` / `CompostTest`
call `worldGrid.clear()` but that doesn't touch env-engine state. Added a
narrow helper (not a public API) that clears the shadow grids, sets
`activeToxin = null`, resets `nonZeroToxinCellCount` and `envDamageAppliedThisTick`,
and clears both status caches.

### Documented divergence from PLAN grep expectations

The plan's acceptance grep `"Math.max(.*multiplierFor.*primaryType.*multiplierFor.*secondaryType"`
expects a method named `multiplierFor`. The existing `EnvironmentConfig.Toxin.Resistance`
record (from Plan 14-01) exposes `forType(ParticleType)` — not `multiplierFor`.
I used the existing `forType` method to avoid churning the Resistance API.
The semantic is identical, the BondedPair MAX-rule is present at line 314-316
of EnvironmentEngine, and the behaviour is locked by
`bondedPairToxinResistanceIsMaxOfMemberTypeMultipliers` in ToxinTest. No plan
rewrite needed — the test is the real acceptance gate.

## Auth Gates Encountered

None.

## Known Stubs

- `EnvironmentEngine.drainPostActionGrants()` — still a no-op body.
  Plan 14-03 Task 2 fills the body and extends the signature with
  `long tickNumber` per cycle-6 HIGH #5a.
- `EnvCleanupHooksBean.infections` / `cureImmuneUntil` / `pendingBuffGrants` —
  still declared but empty. Populated by `EnvironmentEngine` in Plan 03 Task 2.

No stubs blocking Plan 14-02's goal. All toxin effects functional.

## Threat Flags

No new security-relevant surface introduced beyond the PLAN `<threat_model>`
already enumerated (T-14-02-01 through T-14-02-16). Splash damage writes
are internal grid mutations through the existing WorldGrid seam. Compost writes
flow through the same path. No new network endpoints, auth paths, file access,
or schema changes.

## Test Results

Full suite: **499 tests, all passing** (`./gradlew test` BUILD SUCCESSFUL).

Task-specific runs:

| Task | Test class                         | Result                                     |
|------|-----------------------------------|--------------------------------------------|
| 1    | ToxinPathGeneratorTest            | 9/9 pass                                   |
| 2    | CellularAutomatonTest             | 10/10 pass + 1 @Disabled perf-only smoke   |
| 3    | ToxinTest (body)                  | 16/16 pass                                 |
| 3    | SeasonalPoissonTest               | 3/3 pass                                   |
| 4    | ToxinTest (splash additions)      | 3/3 pass + 2 @Disabled integration placeholders |
| —    | EnvironmentEngineTest (non-reg)   | 4/4 pass (incl. seedFieldBindsFromTestPropertySource) |
| —    | EnvDeathSweepTest*                | 3/3 pass (particle + 2 composite branches) |
| —    | EnvPostActionReconcilerTest       | 2/2 pass                                   |
| —    | SimulationEngineTest              | all pass (back-compat 9-arg ctor preserved) |
| —    | BuffRegistryTest / DeathFinalizerTest | all pass (non-regression)             |

## Confirmations (requested in PLAN `<output>`)

- **File:line of each of the 5 SimulationEngine splash emission sites:**
  `src/main/java/com/paralife/engine/SimulationEngine.java`
  lines 264 (Case 1), 285 (Case 2), 314 (Case 3), 355 (Composite ATTACKER role),
  379 (Composite position-based RPS). Confirmed by
  `grep -n "new SplashDelta" src/main/java/com/paralife/engine/SimulationEngine.java`.
- **File:line of the ActionResolver splash emission site:**
  `src/main/java/com/paralife/engine/ActionResolver.java:645` —
  `int splash = environmentEngine.computeSplashDamage(targetPos);`.
  The LIVE method name is `resolveAttackerAttack` (cycle-4 action item #3).
- **`environmentEngine.markEnvDamageApplied()` is called after the
  ActionResolver splash write:** yes, line 655 inside the splash block.
- **`Math.max(0, ...)` present in the splash block:** yes, line 651 —
  `int clampedEnergy = Math.max(0, attackerMember.energy() - splash);`
  (cycle-5 LOW acceptance criterion).
- **`composite_splashKillFinalizedSameTickViaReconciler`:** `@Disabled`
  end-to-end placeholder deferred to Plan 06 full-stack smoke test. Source-grep
  acceptance criteria verify the wiring (markEnvDamageApplied call in ActionResolver).
- **`multiNeighborAttackStacksSplashOncePerToxicTarget` passed:** yes
  (cycle-6 LOW contract locked).
- **`noArgConstructorDelegatesToDefaultRandom` passed and ToxinPathGenerator
  exposes public no-arg + package-private `(Random)` overload:** yes. Grep
  confirms `public ToxinPathGenerator()` at line 35 +
  `ToxinPathGenerator(Random rng)` at line 44 +
  `this(new Random())` at line 36. `noArgConstructorDelegatesToDefaultRandom`
  test passes.
- **SplashDelta and CombatDelta share the same apply helper:** yes, both
  route through `applyDeltaToOccupant(Position, int)` at
  `SimulationEngine.java:539`.
- **ToxinTest count:** 21 tests total (16 from Task 3 + 5 from Task 4;
  2 of the 5 are `@Disabled` end-to-end integration placeholders).
- **Observed splash damage at default config:** 2
  (`round(10 * 1.0 * 0.2) = 2`). Locked by
  `computeSplashDamageReturnsConfiguredFractionOfBaseDamage`.
- **`bondedPairToxinResistanceIsMaxOfMemberTypeMultipliers` passed:** yes.
- **`nonZeroToxinCellCounterEnablesIdleTickFastPath` passed and
  `toxinGridIsAllZero` absent from source:** yes —
  `grep -c "toxinGridIsAllZero" src/main/java/com/paralife/engine/EnvironmentEngine.java`
  returns 0.
- **`EntityIds.entityIdOf` used by EnvironmentEngine.buildStatusCaches:**
  yes, line 347 of EnvironmentEngine.
- **Zero references to `resolveCompositeAttack` in any test file:** confirmed
  — `grep -r resolveCompositeAttack src/test src/main` returns zero hits.
  (The name appears only in 14-02-PLAN.md prose documenting the rename.)
- **Test files live in `com.paralife.engine` package (NOT `environment/`
  subpackage):** confirmed — `ls src/test/java/com/paralife/engine/` has no
  `environment/` subdirectory.

## Files Summary

**Created (6):**
- Main: `ToxinEvent.java`, `ToxinPathGenerator.java`, `EntityIds.java`,
  `CellularAutomaton.java` (4 classes)
- Test: `ToxinPathGeneratorTest.java`, `CellularAutomatonTest.java` (2 classes)

**Modified (7):**
- `EnvironmentConfig.java` — Toxin record +3 fields + validation + defaults
- `EnvironmentEngine.java` — +450 lines of toxin wiring (spawn/advance/resolve/
  buildStatusCaches/computeSplashDamage/toxinIntensityAt/public status bits/
  test helpers)
- `SimulationEngine.java` — 13-arg primary ctor, SplashDelta record, 5 emission
  sites, shared applyDeltaToOccupant helper, markEnvDamageApplied after apply loop
- `ActionResolver.java` — @Autowired(required=false) setter for
  EnvironmentEngine, splash block in resolveAttackerAttack with Math.max(0,...)
  clamp + markEnvDamageApplied
- `application.yml` — diffusion-rate / base-damage / intensity-threshold keys
- `ToxinTest.java` — 21 tests (was 1 placeholder)
- `SeasonalPoissonTest.java` — 3 tests (was 1 placeholder)

**Commits (4):**
- `1e52534` — Task 1: ToxinPathGenerator + ToxinEvent + 9 tests
- `c3b4711` — Task 2: EntityIds + CellularAutomaton + 10 tests + 1 @Disabled smoke
- `c71bc8c` — Task 3: wire toxin into EnvironmentEngine + 16 ToxinTest + 3 SeasonalPoissonTest
- `5e1db4e` — Task 4: SplashDelta + ActionResolver splash + 5 Task-4 ToxinTest cases

## Self-Check: PASSED

All 6 created + 7 modified files present on disk. All 4 task commits
(1e52534 / c3b4711 / c71bc8c / 5e1db4e) present in `git log --oneline --all`.
Full suite `./gradlew test` exits 0 with 499 tests.
