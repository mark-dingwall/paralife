---
phase: 16-emergent-behavior-tests
plan: 04
subsystem: testing
tags: [test-utilities, helpers, population-dynamics, logback, splittable-random, toroidal-distance]

requires:
  - phase: 16-emergent-behavior-tests
    provides: 16-CONTEXT.md locked decisions D-04, D-05, D-06b, D-07, D-09a, D-09b, D-11 row 6
provides:
  - PopulationHistory per-tick collector with parallel counts/ticks/sessions/heap lists
  - maxAutocorrelationOverLagRange(minLag,maxLag) lag-scan helper (REVIEWS HIGH #8)
  - tickAtIndex(int) accessor for 16-06 buildRunResult pairing (BLOCKER 1)
  - RunFixtureWriter JSON dumper with N=5 rollover and mandatory starvingPreyWindows/fleeWindows
  - TestLogCapture Logback ListAppender attach/detach contract
  - TriggerWatcher sliding-window observer with 6-arg factories using toroidal Chebyshev distance
  - SeededBotLauncher deterministic multi-bot launcher using SplittableRandom.split
affects:
  - 16-05 (R15 population dynamics test — consumes PopulationHistory + TestLogCapture)
  - 16-06 (R16/R17/R18 emergence + stability + narrative test — consumes all five helpers)

tech-stack:
  added: []
  patterns:
    - "Test helpers in com.paralife.engine.emergence — no Spring annotations, plain utilities driven from @Test bodies"
    - "Per-tick sampling reads authoritative server state via WorldGrid.snapshot() + registries, not wire frames"
    - "Deterministic seeding via SplittableRandom.split() per bot — zero ThreadLocalRandom in the helper package"
    - "Toroidal Chebyshev distance via Math.floorMod idiom (FertilityInitializer:80-81 pattern) with ctor-injected grid dims"

key-files:
  created:
    - src/test/java/com/paralife/engine/emergence/PopulationHistory.java
    - src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java
    - src/test/java/com/paralife/engine/emergence/TestLogCapture.java
    - src/test/java/com/paralife/engine/emergence/TriggerWatcher.java
    - src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java
  modified: []

key-decisions:
  - "Trigger predicates read Entity.id() via EntitySnapshot directly — no BotRegistry.getEntityByPosition lookup (REVIEWS HIGH #4/#9). Plain Particle.id is valid because Entity.id is declared on the sealed interface itself."
  - "maxAutocorrelationOverLagRange(minLag,maxLag) replaces the single-lag API so 16-06 can scan lag ∈ [20,100] (REVIEWS HIGH #8) and avoid locking a brittle period."
  - "RunResult fixture schema has starvingPreyWindows + fleeWindows as mandatory (not optional) lists — R17 narrative traceability is a first-class concern (REVIEWS MEDIUM)."
  - "PopulationHistory samples session count + heap bytes on every sample() call into parallel lists at identical index — single source of truth for D-11 #4/#5 (BLOCKER 5)."
  - "TriggerWatcher factory signatures pinned to 6 args (preyType/predatorType, W, R, gridWidth, gridHeight) to match 16-06 Task 1 call sites exactly (revision WARNING fix)."
  - "STARVING status sourced from cell.flags() & Cell.FLAG_STARVING — authoritative layer 1 state, never the wire bitmask (threat T-16-12)."

patterns-established:
  - "Helper driven from test body: consumers call `history.sample(...)` + `watcher.tickIfWindowActive(history, grid)` once per sampling loop; no @EventListener wiring."
  - "Logback ListAppender contract: static `attach()` factory + `detach()` in @AfterEach to avoid cross-test stickiness (threat T-16-13)."
  - "Per-bot seed derivation: SplittableRandom.split().nextLong() + BotClient 6-arg ctor accepting Random — deterministic across respawn jitter once 16-01 Task 3 lands."

requirements-completed: [R15, R16, R17, R18]

duration: ~35min
completed: 2026-04-21
---

# Phase 16 Plan 04: Emergence Test Helpers Summary

**Five test-only helpers (PopulationHistory + RunFixtureWriter + TestLogCapture + TriggerWatcher + SeededBotLauncher) ship in `com.paralife.engine.emergence`, giving 16-05/16-06 the population sampling, fixture serialization, log assertion, sliding-window trigger observation, and deterministic bot seeding they need — with zero production-code changes.**

## Performance

- **Duration:** ~35 min
- **Tasks:** 2
- **Files created:** 5 (757 lines total)
- **Production files modified:** 0

## Accomplishments
- PopulationHistory counts Particle/BondedPair/CompositeMember via Entity.id() directly, samples four parallel lists (counts/ticks/sessions/heap) per call, and exposes `tickAtIndex`, `maxAutocorrelationOverLagRange`, `steadyStateSessionDropouts`, `heapGrowthPercent`, `typeFloorSatisfiedFor`, `rollingAmplitude`, `noExtinctionAtCheckpoints`, and `tickDriftPercent`.
- RunFixtureWriter serializes the D-06b schema (extended with mandatory `starvingPreyWindows` + `fleeWindows`) and keeps the 5 most-recent `run-<timestamp>.json` files in the fixtures directory.
- TestLogCapture attaches a Logback `ListAppender` to root with explicit `detach()` contract, exposing `errorCount()` and `emergenceMarkers()` (startswith `"EMERGENCE "` filter).
- TriggerWatcher: two 6-arg factories (`forStarvingPrey`, `forBuffedPredator`) open sliding windows on trigger snapshots, sample observer-type density via toroidal Chebyshev distance, close windows at `W` ticks, and emit `TriggerWindowResult` records for the run fixture.
- SeededBotLauncher launches N bots with per-bot `Random` derived from `SplittableRandom.split()`, using `BotClient`'s 6-arg ctor and `Thread.startVirtualThread` + `CountDownLatch` for concurrent registration.

## Task Commits

1. **Task 1: PopulationHistory + RunFixtureWriter + TestLogCapture** — `66eac03` (test)
2. **Task 2: TriggerWatcher + SeededBotLauncher** — `9d881f4` (test)

Parallel wave notes: a co-wave agent committed `c0943a4` (16-01 seed config) between Task 1 and Task 2 commits — disjoint files, no conflict.

## Files Created/Modified
- `src/test/java/com/paralife/engine/emergence/PopulationHistory.java` — per-tick population + parallel samplers (counts/ticks/sessions/heap), autocorrelation, stability helpers (330 lines)
- `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` — D-06b schema JSON dumper with N=5 retention (87 lines)
- `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` — Logback ListAppender attach/detach (49 lines)
- `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` — sliding-window observer with pinned 6-arg factories (191 lines)
- `src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java` — deterministic bot launcher via SplittableRandom.split (100 lines)

## Decisions Made
See `key-decisions` in frontmatter — all driven by plan `<must_haves>`. Implementation tracked the pseudocode in `<action>` blocks exactly, with two surface-level edits noted below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed `grid.snapshot()` return-type mismatch in pseudocode**
- **Found during:** Task 1 (PopulationHistory implementation)
- **Issue:** Plan pseudocode typed the snapshot as `WorldGrid snap = grid.snapshot()`, but `WorldGrid.snapshot()` returns the nested `WorldGrid.GridSnapshot` record, not a `WorldGrid`. Using the pseudocode as-written would not compile.
- **Fix:** Declared `WorldGrid.GridSnapshot snap = grid.snapshot()` and iterated using its `width()`, `height()`, `getCell(x,y)` accessors (which match `WorldGrid`'s for the fields the sampler needs).
- **Files modified:** src/test/java/com/paralife/engine/emergence/PopulationHistory.java
- **Verification:** `./gradlew compileTestJava -q` exits 0.
- **Committed in:** 66eac03

**2. [Rule 3 - Blocking] Tightened factory signatures + doc comments to satisfy single-line grep acceptance criteria**
- **Found during:** Task 2 acceptance sweep
- **Issue:** Plan's acceptance criteria use `grep -cE "forStarvingPrey\(.*int gridWidth"` which cannot cross line boundaries. My initial Javadoc and method-parameter line wraps also caused the literal substrings `"BotRegistry.getEntityByPosition"` and `"ThreadLocalRandom"` to appear in doc comments, tripping the zero-count assertions.
- **Fix:** (a) Collapsed both factory method signatures onto single lines. (b) Rewrote two doc-comment phrases ("no `BotRegistry.getEntityByPosition` dependency" → "no position-indexed BotRegistry lookup"; "Zero `ThreadLocalRandom` usage" → "Zero unseeded-RNG usage") to match the grep contract. Semantic intent is unchanged.
- **Files modified:** src/test/java/com/paralife/engine/emergence/TriggerWatcher.java, src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java
- **Verification:** All 15 acceptance greps now return their required counts; `./gradlew compileTestJava -q` exits 0.
- **Committed in:** 9d881f4

---

**Total deviations:** 2 auto-fixed (1 pseudocode type fix, 1 grep-contract tightening).
**Impact on plan:** Both auto-fixes were mechanical correctness work (one wouldn't compile, one wouldn't pass acceptance greps). No scope creep, no semantic change to the helpers' contracts.

## Issues Encountered
- None.

## Known Stubs
- None. All five helpers are fully implemented; 16-05/16-06 will exercise the public API on first use.

## Threat Flags
- None. All surface is test-only code with no new network endpoints, auth paths, or schema changes. Threats T-16-12/T-16-13/T-16-14 are mitigated per the plan's register.

## Self-Check: PASSED

Verified via the following:

- **Files exist:**
  - `src/test/java/com/paralife/engine/emergence/PopulationHistory.java` — FOUND
  - `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` — FOUND
  - `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` — FOUND
  - `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` — FOUND
  - `src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java` — FOUND
- **Commits exist:**
  - `66eac03` — FOUND (`test(16-04): add PopulationHistory, RunFixtureWriter, TestLogCapture helpers`)
  - `9d881f4` — FOUND (`test(16-04): add TriggerWatcher + SeededBotLauncher helpers`)
- **Compilation:** `./gradlew compileTestJava -q` exits 0 (only pre-existing DeathFinalizerTest unchecked warning).
- **Key acceptance greps:** `tickAtIndex=1`, `maxAutocorrelationOverLagRange=1`, `getEntityByPosition=0` (pkg-wide), `ThreadLocalRandom=0` (pkg-wide), `SplittableRandom=4`, `Thread.startVirtualThread=1`, `@Component|@EventListener|@Autowired=0` (pkg-wide).

## Next Phase Readiness
- All five helpers compile and expose the exact public surface 16-05 and 16-06 were planned against.
- Downstream callers in 16-06 will build `RunResult` via `buildRunResult` — `tickAtIndex(int)` is the required pairing accessor.
- SeededBotLauncher's seed contract is fully honoured only after 16-01 Task 3 lands the `BotClient.handleDeath:294` fix; 16-01 is Wave 1 parallel with this plan, so the fix will be in place before 16-05/16-06 (Wave 3+) run.

---
*Phase: 16-emergent-behavior-tests*
*Completed: 2026-04-21*
