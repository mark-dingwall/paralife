---
phase: 16-emergent-behavior-tests
plan: 03
subsystem: metrics
tags: [micrometer, distribution-summary, junit5, tag-slow, gradle, load-stability]

# Dependency graph
requires:
  - phase: 15-protocol-transport-overhaul
    provides: "Micrometer MeterRegistry wiring pattern (WebSocketMetrics) + actuator exposure"
provides:
  - "paralife.tick.work.ms DistributionSummary inline in TickEngine.tickLoop with p50/p95/p99"
  - ".gitignore entry for .planning/phases/16-emergent-behavior-tests/fixtures/*.json"
  - "-PincludeLong=true project property gating @Tag(\"slow\") test inclusion"
affects: [16-06, 16-04, 16-07]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Back-compat ctor overload (2-arg delegates to 3-arg with SimpleMeterRegistry) to preserve direct-instantiation unit tests"
    - "useJUnitPlatform { excludeTags } block driven by project.findProperty for opt-in slow suite"

key-files:
  created: []
  modified:
    - "src/main/java/com/paralife/engine/TickEngine.java"
    - ".gitignore"
    - "build.gradle.kts"

key-decisions:
  - "Inline DistributionSummary inside tickLoop (not a separate @EventListener start/end bean) — single file diff, no pipeline-slot churn, single-writer on virtual tick thread"
  - "@Autowired on 3-arg ctor disambiguates Spring constructor selection when back-compat 2-arg ctor also exists; without it Spring can't pick and falls back to default-ctor search (fatal)"
  - "Sample System.nanoTime() once, derive both double-ms for meter and long-ms for sleep-budget warn — avoids double-reading the clock and keeps existing elapsed-var type intact"
  - "Leading / on .gitignore path anchors to repo root; glob is *.json only (no .gitkeep, no pre-committed dir) — RunFixtureWriter creates dir at runtime"
  - "excludeTags default with includeLong override flips the default to fast — untagged tests always run; no separate slow gradle task needed"

patterns-established:
  - "Spring multi-ctor disambiguation: when a bean has both a production ctor and a test-convenience ctor, mark the production one @Autowired explicitly"
  - "Meter recording inline in the single-threaded hot path: no locking, no separate bean, one .record() call per iteration"

requirements-completed: [R18]

# Metrics
duration: 24min
completed: 2026-04-21
---

# Phase 16 Plan 03: Load-Stability Instrumentation Summary

**paralife.tick.work.ms DistributionSummary (p50/p95/p99) wired inline in TickEngine.tickLoop + @Tag("slow") opt-in via -PincludeLong=true + fixtures dir gitignored**

## Performance

- **Duration:** 24 min
- **Started:** 2026-04-21T07:47:00Z (approx, start of worktree base verification)
- **Completed:** 2026-04-21T08:11:00Z (approx, final commit)
- **Tasks:** 3 (all auto-executed, no checkpoints)
- **Files modified:** 3

## Accomplishments

- `paralife.tick.work.ms` DistributionSummary registered with p50/p95/p99 — 16-06's R18 load-stability test can now assert D-11 rows 2 (mean ≤ 50% of tick budget) and 3 (p99 ≤ 90%) by reading `meterRegistry.find("paralife.tick.work.ms").summary().takeSnapshot().percentileValues()`.
- Recording inline in `tickLoop` captures the full end-to-end tick (listener dispatch through final `@Order(100)` listener). Zero new beans, zero new @EventListeners.
- Existing sleep-budget warn path and `getCurrentTick()` accessor (line 134) preserved byte-for-byte — 16-06 sampling loop can consume `getCurrentTick()` unchanged (REVIEWS HIGH #9 closed with zero prod-code surface added).
- `-PincludeLong=true` Gradle project property flag wired to JUnit 5 `excludeTags("slow")` — default `./gradlew test` is fast (excludes 16-06's long-run test); CI/on-demand `./gradlew test -PincludeLong=true` includes it.
- `.gitignore` prevents accidental commits of `run-<timestamp>.json` fixtures dumped by 16-04's `RunFixtureWriter` during long-run runs (D-06b rollover N=5).

## Task Commits

1. **Task 1: Inject MeterRegistry into TickEngine; record paralife.tick.work.ms inline** — `372cf6d` (feat)
2. **Task 2: .gitignore entry for fixtures directory** — `4ccd32d` (chore)
3. **Task 3: Wire -PincludeLong flag + @Tag("slow") exclusion in build.gradle.kts** — `e5393e7` (chore)

Final metadata commit for SUMMARY.md will be added after this file is committed.

## Files Created/Modified

- `src/main/java/com/paralife/engine/TickEngine.java` — Added `DistributionSummary tickWork` field, `@Autowired` 3-arg ctor with `MeterRegistry`, back-compat 2-arg ctor delegating to SimpleMeterRegistry, and `tickWork.record(elapsedNs / 1_000_000.0)` inside tickLoop between `publishEvent(event)` and sleep-budget calculation. Single-writer on virtual tick thread — no contention concerns.
- `.gitignore` — Appended two lines: a header comment and `/.planning/phases/16-emergent-behavior-tests/fixtures/*.json`.
- `build.gradle.kts` — Replaced `useJUnitPlatform()` shorthand with block form containing `excludeTags("slow")` gated on `project.findProperty("includeLong") != "true"`.

## Decisions Made

- **Inline DistributionSummary vs dedicated @EventListener bean:** Chose inline. The alternative would be a new `@EventListener @Order(<lowest>)` bean starting a nanoTime, another `@EventListener @Order(Integer.MAX_VALUE)` bean recording on end. Two beans, two beans to autowire everywhere, zero additional fidelity — tickLoop already owns start/end timing for the overrun warn, and DistributionSummary is thread-safe with a single writer (the virtual tick thread). Inline wins on diff size, test surface, and locality.
- **@Autowired required on 3-arg ctor:** Spring Boot 3.4's constructor-picker requires explicit `@Autowired` when multiple public ctors exist. First test run after adding both ctors produced `BeanCreationException: Failed to instantiate [TickEngine]: No default constructor found` across ~103 context-dependent tests. Added `@Autowired` to the 3-arg ctor — all 567 tests green. Documented in key-decisions for future multi-ctor additions.
- **Double-ms for meter, long-ms for sleep-budget:** Sample `System.nanoTime()` once, compute `elapsedNs`, derive both `elapsedNs / 1_000_000.0` (double, for the meter) and `elapsedNs / 1_000_000` (long, preserved for the existing overrun warn comparison with `config.intervalMs()`). Avoids a second nanoTime read and keeps the existing `elapsed` local's semantics intact.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] @Autowired annotation required on 3-arg ctor for Spring to pick it**

- **Found during:** Task 1 (initial test run, before other-plan worktree-state investigation)
- **Issue:** Plan's ctor snippet omitted `@Autowired`. With two public ctors, Spring Boot 3.4 could not auto-pick and failed with "No default constructor found" across 103 Spring-context tests.
- **Fix:** Added `@Autowired` annotation to the 3-arg ctor; added `import org.springframework.beans.factory.annotation.Autowired`.
- **Files modified:** `src/main/java/com/paralife/engine/TickEngine.java`
- **Verification:** Full test suite re-run — BUILD SUCCESSFUL.
- **Committed in:** `372cf6d` (Task 1 commit — included in the same feat diff).

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Essential for the plan to compile/run. No scope creep.

## Issues Encountered

- **Worktree path confusion (resolved):** Initial Edit calls targeted absolute paths under the main repo (`/home/mark/kramtime/paralife/src/...`) instead of my worktree (`/home/mark/kramtime/paralife/.claude/worktrees/agent-abc914b9/src/...`). Detected by pwd check; reverted main-repo TickEngine via `git checkout --`, re-applied edits in the worktree, verified test suite green in the worktree. No residual leakage into main repo.
- **Unrelated uncommitted state in main repo (not my responsibility):** While diagnosing the path issue I observed the main repo had in-progress modifications to `SimulationConfig.java`, `FertilityConfig.java`, `CompositeConfig.java`, plus new file `SpawnConfig.java` and compile errors in `SimulationEngine.java` referencing an undefined `buildRng()`. These belong to sibling parallel plans (16-01/16-04 likely). Confirmed unrelated to my scope; left untouched.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

**Ready for 16-06 consumption:**

- `MeterRegistry.find("paralife.tick.work.ms").summary()` returns a non-null `DistributionSummary` once at least one tick has been recorded. After the 1000-tick run, `.takeSnapshot().percentileValues()` yields entries at 0.5/0.95/0.99 for D-11 row-3 p99 assertion. `.mean()` feeds D-11 row-2 mean assertion.
- `tickEngine.getCurrentTick()` unchanged at line 134 — 16-06's sampling loop can tail it (anti-pattern-of-`Thread.sleep(tickCount * intervalMs)` replaced by polling `getCurrentTick() >= targetTicks`).
- 16-06's `@Tag("slow")` on the long-run test is automatically excluded by `./gradlew test`; CI pipeline for v2.0 closeout should add a `./gradlew test -PincludeLong=true` job.
- `RunFixtureWriter` writes to `.planning/phases/16-emergent-behavior-tests/fixtures/run-<ts>.json` — directory will be created at first run; gitignore prevents committing fixtures.

**No blockers for Wave 2 plans.**

## Self-Check: PASSED

- `src/main/java/com/paralife/engine/TickEngine.java` — FOUND (modified, contains `paralife.tick.work.ms`, `publishPercentiles(0.5, 0.95, 0.99)`, `tickWork.record`, `MeterRegistry` import, `public long getCurrentTick`).
- `.gitignore` — FOUND (contains `/.planning/phases/16-emergent-behavior-tests/fixtures/*.json`).
- `build.gradle.kts` — FOUND (contains `excludeTags("slow")` and `includeLong`).
- Commit `372cf6d` — FOUND in `git log`.
- Commit `4ccd32d` — FOUND in `git log`.
- Commit `e5393e7` — FOUND in `git log`.
- `./gradlew test` — BUILD SUCCESSFUL (fast suite, 567 tests, 0 failures on a clean rerun in the worktree).

---

*Phase: 16-emergent-behavior-tests*
*Plan: 03*
*Completed: 2026-04-21*
