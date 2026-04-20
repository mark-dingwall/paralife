---
phase: 15-protocol-transport-overhaul
plan: 07
subsystem: protocol-transport
tags: [rename, broadcaster, zero-trust, delete-heartbeat, git-mv, package-move]

requires:
  - phase: 15-protocol-transport-overhaul
    provides: "Plan 15-06 codec-driven WorldWebSocketHandler + Frame.ActionFrame verb dispatch + IRV + AlarmQueue; Messages.java partial strip retaining CellView/Perception/EntityState/CompositePerception; LegacyTickHeartbeat shim in old websocket/TickBroadcaster.java"
provides:
  - "websocket/TickBroadcaster.java — per-bot perception broadcaster renamed from engine/PerceptionBroadcaster (@Order(50), vision-scoped OVERCROWDED invariant intact)"
  - "Deleted old heartbeat broadcaster + test (D-02): websocket/TickBroadcaster.java + TickBroadcasterTest.java; LegacyTickHeartbeat shim removed with the file"
  - "Test package alignment: PerceptionBroadcasterTest/CompositePerceptionTest/VisionScopedOvercrowdingTest moved from engine/ to websocket/ so package-private seams (cellToView, stitchSensorCoverage, computeVisionScopedOvercrowded) remain accessible"
affects: [15-08, 15-11]

tech-stack:
  added: []
  patterns:
    - "git mv + separate delete-then-move commits so git log --follow tracks the rename chain through package boundaries"
    - "Package-private test seams require test class to share package — enforced here by moving three tests into com.paralife.websocket"

key-files:
  created:
    - src/main/java/com/paralife/websocket/TickBroadcaster.java  # renamed from engine/PerceptionBroadcaster.java
    - src/test/java/com/paralife/websocket/TickBroadcasterProjectionTest.java  # renamed from engine/PerceptionBroadcasterTest.java
    - src/test/java/com/paralife/websocket/CompositePerceptionTest.java  # moved from engine/
    - src/test/java/com/paralife/websocket/VisionScopedOvercrowdingTest.java  # moved from engine/ (excluded from test compile)
  modified:
    - build.gradle.kts  # added VisionScopedOvercrowdingTest to plan 15-06 exclusion block

key-decisions:
  - "Test package alignment: moved three perception tests into com.paralife.websocket rather than widening package-private seams (cellToView/stitchSensorCoverage/computeVisionScopedOvercrowded) to public. Preserves encapsulation; scope remains rename-only."
  - "VisionScopedOvercrowdingTest excluded from compile: depends on package-private SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT which is no longer visible from com.paralife.websocket. Plan 15-11 migrates the test body — per 15-07-PLAN §Task 2 explicit guidance for this failure mode."
  - "Comment hygiene only: updated the @Order(50) adjacent comment to drop the stale 'before TickBroadcaster(100)' reference (that heartbeat no longer exists). Javadoc @link to PerceptionBroadcasterTest rewritten to {@code TickBroadcasterProjectionTest}. Did not touch Javadoc references in engine/ActionResolver/SimulationEngine/EnvironmentEngine/CompositeRegistry/EnvPostActionReconciler/EntityIds/Messages — plan anti-escape forbids those edits and the acceptance criteria is grep-based on import statements, not Javadoc @link."

patterns-established:
  - "Rename wave pattern: delete target-slot first (commit 1), then git mv source to the freed slot (commit 2). Keeps git rename detection intact and avoids a transient two-classes-with-same-name state."

requirements-completed: [R20, R25]

duration: 11min
completed: 2026-04-20
---

# Phase 15 Plan 07: Rename engine/PerceptionBroadcaster → websocket/TickBroadcaster Summary

**Per-bot perception broadcaster renamed from engine/PerceptionBroadcaster to websocket/TickBroadcaster (@Order(50) preserved, D-40 vision-scoped OVERCROWDED mask-and-OR intact); old heartbeat broadcaster + test + LegacyTickHeartbeat shim deleted per D-02.**

## Performance

- **Duration:** ~11 min
- **Started:** 2026-04-20T04:24:04Z
- **Completed:** 2026-04-20T04:35:00Z (approx)
- **Tasks:** 2/2
- **Files modified:** 5 (1 src main, 3 src test, 1 build.gradle.kts)

## Accomplishments

- Deleted `src/main/java/com/paralife/websocket/TickBroadcaster.java` (old 95-line heartbeat + the transient `LegacyTickHeartbeat` shim 15-06 left in place). Global-stats DTO moves to M005 per D-02.
- Deleted `src/test/java/com/paralife/websocket/TickBroadcasterTest.java` (9 tests of the heartbeat).
- `git mv src/main/java/com/paralife/engine/PerceptionBroadcaster.java src/main/java/com/paralife/websocket/TickBroadcaster.java` — git-detected rename at 96% similarity, `git log --follow` preserves full history back to its original creation in plan 11-01.
- Renamed class body: `package com.paralife.engine` → `package com.paralife.websocket`, `PerceptionBroadcaster` → `TickBroadcaster` (class, both constructors, logger).
- Added 7 explicit imports for cross-package references now that `engine` is no longer same-package: `BotRegistry`, `BuffRegistry`, `CompositeRegistry`, `EntityIds`, `EnvironmentEngine`, `SimulationConfig`, `TickEvent`. Dropped redundant `import com.paralife.websocket.Messages` (same-package now).
- Preserved VERBATIM the Phase 14 D-40 vision-scoped OVERCROWDED mask-and-OR block (old line 395, new line 400): `byte cellStatus = (byte) ((cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit);`. Threat T-15-03 mitigated by the literal-expression invariant.
- `@Component` + `@EventListener @Order(50)` + `@Autowired` constructor unchanged.
- Test package alignment: three perception tests moved from `com.paralife.engine` to `com.paralife.websocket` so package-private seams remain accessible. TickBroadcasterProjectionTest + CompositePerceptionTest compile + run green; VisionScopedOvercrowdingTest added to build exclusion (depends on package-private `SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT`, migrates in 15-11).
- Retained all `import com.paralife.websocket.Messages.CellView` / `.EntityState` + bare-name `Messages.Perception` / `Messages.CompositePerception` / `Messages.class` usages — plan 15-08 migrates the projection to codec Frames and removes these.

## Task Commits

Each task was committed atomically:

1. **Task 1: Delete old heartbeat TickBroadcaster + test** — `5be9a70` (refactor)
2. **Task 2: git mv engine/PerceptionBroadcaster.java → websocket/TickBroadcaster.java + package/class rename + test moves + build.gradle.kts exclusion** — `fe1f5cb` (refactor)

_Metadata commit for SUMMARY/STATE/ROADMAP lands after this file is written._

## Files Created/Modified

### Created (via git-detected rename; file content survives with edits)
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — per-bot perception broadcaster (renamed from `engine/PerceptionBroadcaster.java`)
- `src/test/java/com/paralife/websocket/TickBroadcasterProjectionTest.java` — renamed from `engine/PerceptionBroadcasterTest.java` (29 tests)
- `src/test/java/com/paralife/websocket/CompositePerceptionTest.java` — moved from `engine/` (same name; 12 tests)
- `src/test/java/com/paralife/websocket/VisionScopedOvercrowdingTest.java` — moved from `engine/` (excluded from compile pending 15-11)

### Deleted (Task 1)
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` (old heartbeat, 95 lines) — LegacyTickHeartbeat shim bundled with it
- `src/test/java/com/paralife/websocket/TickBroadcasterTest.java` (9 tests)

### Modified
- `build.gradle.kts` — added `com/paralife/websocket/VisionScopedOvercrowdingTest.java` to the plan 15-06 exclusion block; updated comment to mention plan 15-07's addition

## Residual Callsites Touched (per plan's `<output>` requirement)

Every non-trivial callsite identified by `grep -rl PerceptionBroadcaster src/ --include="*.java"` was inspected. Actions taken:

| File | Callsite kind | Action |
|------|---------------|--------|
| `src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java` | Full test class targeting the broadcaster | `git mv` → `websocket/TickBroadcasterProjectionTest.java`, package change + 6 import adds + `PerceptionBroadcaster` → `TickBroadcaster` replace-all (18 replacements). Compiles + runs green. |
| `src/test/java/com/paralife/engine/CompositePerceptionTest.java` | Constructs broadcaster, calls `stitchSensorCoverage` (package-private) | `git mv` → `websocket/CompositePerceptionTest.java`, package change + 3 import adds (BotRegistry/CompositeRegistry/TickEvent) + `PerceptionBroadcaster` → `TickBroadcaster` replace-all. Compiles + runs green. |
| `src/test/java/com/paralife/engine/VisionScopedOvercrowdingTest.java` | Drives static `computeVisionScopedOvercrowded` (package-private) + reads `SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT` (package-private in engine) | `git mv` → `websocket/VisionScopedOvercrowdingTest.java`, package change + 7 import adds + class-ref replace-all. Fails to compile because the package move broke access to `OVERCROWDED_THRESHOLD_DEFAULT`. Added to `build.gradle.kts` exclusion list alongside the 15-06 entries; plan 15-11 migrates the body. |
| `src/main/java/com/paralife/engine/ActionResolver.java` | Javadoc comment `// After SimulationEngine(10), before PerceptionBroadcaster(50)` | Not touched — acceptance criteria checks import statements only; plan anti-escape forbids touching ActionResolver. |
| `src/main/java/com/paralife/engine/SimulationEngine.java` | Comment `// Before TickBroadcaster` | Still semantically correct (the rename preserves the name `TickBroadcaster` at `@Order(50)` — closer than the deleted `@Order(100)` was). Not touched. |
| `src/main/java/com/paralife/engine/AlarmQueue.java`, `EnvironmentEngine.java`, `CompositeRegistry.java`, `EntityIds.java`, `EnvPostActionReconciler.java` | Javadoc references | Not touched — no compile impact, plan anti-escape. |
| `src/main/java/com/paralife/websocket/Messages.java` | Javadoc reference | Not touched — plan 15-08 revisits `Messages.java`. |
| `src/test/java/com/paralife/engine/EnvironmentFullStackSmokeTest.java`, `EnvPostActionReconcilerTest.java`, `MetabolismIntegrationTest.java`, `CompositeIntegrationTest.java`, `EnvironmentPhaseGateIntegrationTest.java` | Javadoc / comment mentions only | Not touched — no compile impact. `CompositeIntegrationTest` is already in the 15-06 exclusion list. |

**No non-trivial `@Autowired` / `@MockBean` / field-injection references to the old heartbeat `TickBroadcaster` existed** — all Javadoc. That's why Task 1 cleanly deleted both files without a cascading migration.

## Decisions Made

- **Test package alignment over seam widening.** Three tests drive package-private methods (`cellToView(Cell)`, `stitchSensorCoverage`, `computeVisionScopedOvercrowded`) of the broadcaster. Keeping seams package-private preserves encapsulation; the alternative (widening to `public`) would leak implementation detail. The plan's "recommend rename" for `PerceptionBroadcasterTest` extends cleanly to the other two by the same argument.
- **Exclude VisionScopedOvercrowdingTest rather than patch SimulationEngine.** The test reads `SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT` (package-private constant in `engine`). The plan explicitly names this failure pattern and prescribes "add to the build.gradle.kts exclusion block". Patching `SimulationEngine` would be scope creep.
- **Commit ordering: delete heartbeat first, then rename perception broadcaster.** Without the delete, Task 2's `git mv` target would collide. Two separate commits also keep `git log --follow` clean for each file.

## Deviations from Plan

### Rule 3 — Blocking: VisionScopedOvercrowdingTest requires exclusion

- **Found during:** Task 2 (after moving 3 tests to `com.paralife.websocket`, `./gradlew compileTestJava` failed)
- **Issue:** `VisionScopedOvercrowdingTest` in `websocket` package can no longer access `SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT` (package-private in `engine`).
- **Fix:** Added `exclude("com/paralife/websocket/VisionScopedOvercrowdingTest.java")` to the plan 15-06 exclusion block in `build.gradle.kts`. Per-plan `<action>` explicitly prescribed this failure mode: *"if it fails to compile post-rename ... add it to the same `build.gradle.kts` exclusion block plan 15-06 established"*. Plan 15-11 migrates the test body.
- **Files modified:** `build.gradle.kts`
- **Verification:** `./gradlew compileTestJava` green after adding the exclusion.
- **Committed in:** `fe1f5cb` (part of Task 2).

### Rule 2 — Missing Critical: Stale @Order(50) comment fixed

- **Found during:** Task 2 (reading the renamed file)
- **Issue:** The `@Order(50)` line's trailing comment read `"before TickBroadcaster(100)"` — but the `@Order(100)` heartbeat class was deleted in Task 1. Leaving the stale comment would mislead future readers.
- **Fix:** Changed comment to `"After SimulationEngine(10) + ActionResolver(20) — tick-pipeline perception step"`. Purely cosmetic; zero bytecode or wire impact.
- **Files modified:** `src/main/java/com/paralife/websocket/TickBroadcaster.java` (line 132)
- **Committed in:** `fe1f5cb` (part of Task 2).

### Cosmetic: `{@link PerceptionBroadcasterTest}` → `{@code TickBroadcasterProjectionTest}`

- **Found during:** Task 2 (the Javadoc on the static `cellToView(Cell cell)` overload references the test class by name)
- **Issue:** `PerceptionBroadcasterTest` no longer exists (renamed to `TickBroadcasterProjectionTest`) — the `{@link}` would dangle.
- **Fix:** Downgraded to `{@code TickBroadcasterProjectionTest}` to dodge the direct @link dependency (which would require the test class to be on the doclet classpath) while still naming the test.
- **Committed in:** `fe1f5cb` (part of Task 2).

---

**Total deviations:** 3 (1 Rule 3 blocking, 2 cosmetic/Rule 2).
**Impact on plan:** All strictly in scope. VisionScopedOvercrowdingTest exclusion was anticipated by the plan. Stale @Order(50) comment and Javadoc @link were caused by this plan's own deletions/renames — fixing them in the same commit is the natural cleanup.

## Issues Encountered

- **Expected**: `./gradlew test` reports 532 tests / 13 failures / 3 skipped vs baseline 547/13/3. Failure count unchanged — no new regressions. Test-count drop is arithmetically consistent: −9 from deleted `TickBroadcasterTest` (Task 1) + −6 from excluded `VisionScopedOvercrowdingTest` (Task 2 deviation) = −15; 547 − 15 = 532. Pre-existing 13 failures remain in the deferred-registry bucket owned by plan 15-11 (`ActionResolverTest`, `CompositeActionTest`, etc — same list as 15-06).

## Next Phase Readiness

- **Plan 15-08** (Wave 5 — reads this SUMMARY) can swap the projection in `TickBroadcaster.onTick` from `objectMapper.writeValueAsString(Messages.Perception)` to emitting `Frame.TickFrame`/`Frame.PerceptionFrame` via the codec. The broadcaster name is now stable; the `Messages.*` imports are still present for 15-08 to surgically remove. The mask-and-OR invariant at line 400 must continue to feed `cellStatus` into the codec's `envState` byte.
- **Plan 15-11** owns:
  1. Rewriting `VisionScopedOvercrowdingTest` around whatever public API survives the 15-08 projection swap, removing the `build.gradle.kts` exclusion.
  2. Migrating the other 5 excluded tests (`ActionResolverTest`, `CompositeActionTest`, `CompositeIntegrationTest`, `CompositeMovementTest`, `WebSocketIntegrationTest`).
- **No blockers for Wave 5 parallelism.** Wire path is temporarily incoherent (WorldWebSocketHandler on codec, TickBroadcaster on Jackson/Messages) — acknowledged by plan 15-07 objective as by-design until 15-08 closes it.

## Threat Flags

None — this plan is pure rename + delete. Threat T-15-03 (vision-scoped OVERCROWDED information disclosure) mitigated by verbatim preservation of the mask-and-OR at line 400 per `<threat_model>`.

## Self-Check: PASSED

- Created files exist:
  - `src/main/java/com/paralife/websocket/TickBroadcaster.java` — FOUND
  - `src/test/java/com/paralife/websocket/TickBroadcasterProjectionTest.java` — FOUND
  - `src/test/java/com/paralife/websocket/CompositePerceptionTest.java` — FOUND
  - `src/test/java/com/paralife/websocket/VisionScopedOvercrowdingTest.java` — FOUND
- Deleted files absent:
  - `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` — ABSENT
  - (pre-rename) old `src/main/java/com/paralife/websocket/TickBroadcaster.java` content (the heartbeat) — ABSENT (git log shows Task 1 deletion)
  - `src/test/java/com/paralife/websocket/TickBroadcasterTest.java` — ABSENT
- Commits:
  - `5be9a70` (Task 1 delete) — FOUND in `git log`
  - `fe1f5cb` (Task 2 rename) — FOUND in `git log`
- Acceptance criteria:
  - `package com.paralife.websocket;` line 1 — PRESENT
  - `public class TickBroadcaster` line 59 — PRESENT
  - `@Order(50)` line 132 — PRESENT
  - `cached & ~BIT_OVERCROWDED` line 400 — PRESENT (verbatim; also at lines 53, 375 in Javadoc)
  - No `import com.paralife.engine.PerceptionBroadcaster` anywhere in `src/` — CONFIRMED (grep returns no matches)
  - `./gradlew compileJava` — PASSED
  - `./gradlew compileTestJava` — PASSED
  - `git log --follow src/main/java/com/paralife/websocket/TickBroadcaster.java` — shows rename from `engine/PerceptionBroadcaster.java` back to the original 2025 creation commit `d97dbca`.

---

*Phase: 15-protocol-transport-overhaul*
*Plan: 07 (Wave 4)*
*Completed: 2026-04-20*
