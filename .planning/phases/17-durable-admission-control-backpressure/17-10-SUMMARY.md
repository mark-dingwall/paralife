---
phase: 17
plan: 10
subsystem: admission
tags: [migration, cleanup, application-yml, claude-md, test-migration, ingress-counter]
requires:
  - 17-01-SUMMARY.md  # AdmissionConfig defines paralife.admission shape
  - 17-03-SUMMARY.md  # AdmissionGateTest is the rewrite of the deleted test
  - 17-07-SUMMARY.md  # WorldWebSocketHandler refactor + STALLED FSM
  - 17-08-SUMMARY.md  # TickBroadcaster non-blocking
  - 17-09-SUMMARY.md  # BotClient resume-reconnect
provides:
  - paralife.admission namespace populated in application.yml
  - PopulationCapConfig.java + WorldWebSocketHandlerPopulationCapTest.java deleted
  - LoadTest.java migrated to paralife.admission.cap=1000000
  - ActionResolver.queueAction wired to AdmissionMetrics.incIngressOverwrite (D-09)
  - CLAUDE.md ## Architecture gains "Outbound concurrency (Phase 17, D-10)" sub-section
affects:
  - src/main/resources/application.yml
  - src/main/java/com/paralife/engine/ActionResolver.java
  - CLAUDE.md
tech-stack:
  added: []
  patterns:
    - Setter-injection (Autowired required=false) for AdmissionMetrics —
      mirrors existing Phase 14 pattern (setEnvironmentEngine, setBuffRegistry)
      so pre-Phase-17 unit-test ctors keep compiling without rewiring.
key-files:
  created: []
  modified:
    - src/main/resources/application.yml
    - src/test/java/com/paralife/engine/LoadTest.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - CLAUDE.md
  deleted:
    - src/main/java/com/paralife/websocket/PopulationCapConfig.java
    - src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java
decisions:
  - "Kept AdmissionConfig @DefaultValue annotations untouched — they're a Wave 1
     hotfix that lets existing partial-property test fixtures keep binding without
     supplying every key. The yml migration is purely additive."
  - "Used setter-injection (required=false) for AdmissionMetrics rather than
     constructor change — preserves three existing test-only ActionResolver
     ctors (lines 173, 186) which would otherwise need fixture rewrites across
     ActionResolverTest, CompositeActionTest, etc."
  - "ActionResolver had only ONE pendingActions.put site (line 264). The plan
     mentioned 'lines 125, 262, 299' from RESEARCH but those line numbers were
     stale — 125 is the field declaration, 299 is the getAndSet drain. One put,
     one D-09 increment guard."
metrics:
  duration: "~30 minutes"
  completed: "2026-04-27"
---

# Phase 17 Plan 10: Migration Sweep + D-09 Counter + D-10 Doc Summary

Final migration sweep that supersedes the Wave 1 999.1 stopgap (D-04). Closes the
admission-namespace migration so the project compiles cleanly with only the new
`paralife.admission` shape, wires the D-09 last-write-wins ingress-overwrite
counter into `ActionResolver`, and lifts the D-10 VT-per-session rationale into
`CLAUDE.md`.

## Tasks completed

| # | Task | Commit |
|---|------|--------|
| 1 | Migrate `application.yml` + delete `PopulationCapConfig` + delete `WorldWebSocketHandlerPopulationCapTest` + migrate `LoadTest` `@TestPropertySource` | `0580f0c` |
| 2 | Wire `ActionResolver` D-09 ingress-overwrite counter + add `### Outbound concurrency (Phase 17, D-10)` to `CLAUDE.md` | `8485a37` |

## What changed

### `application.yml`
- Removed `paralife.websocket.max-active-entities: 256`
- Added `paralife.admission` block with the full Phase 17 default shape:
  ```yaml
  admission:
    cap: 256
    maintenance: false
    tick-overload:
      high-water-pct: 80
      low-water-pct: 60
      window-ticks: 10
    backpressure:
      outbound-queue-size: 16
      grace-window-ticks: 10
  ```
- All other yml sections preserved verbatim. `max-respawns-per-session: 5`
  retained under `paralife.websocket` (unrelated knob).

### `PopulationCapConfig.java` — deleted
Superseded by `com.paralife.admission.AdmissionConfig` (Plan 03). The Javadoc
reference in `AdmissionConfig` ("replaces `PopulationCapConfig`") is the only
remaining mention and is intentional historical context.

### `WorldWebSocketHandlerPopulationCapTest.java` — deleted
Superseded by `AdmissionGateTest` (Plan 03, D-04 rewrite). The old test asserted
on `paralife.websocket.max-active-entities=1`-driven `E|429`; the new test
covers the equivalent and broader admission-rejection taxonomy.

### `LoadTest.java`
- `@TestPropertySource` literal `paralife.websocket.max-active-entities=1000000`
  → `paralife.admission.cap=1000000`. All other test properties unchanged.
- LoadTest is slow-tagged and not run as part of Plan 10 verification; Plan 11
  re-runs it.

### `ActionResolver.java` — D-09 ingress-overwrite counter (Task 2)
- `import com.paralife.admission.AdmissionMetrics;`
- New nullable field `private AdmissionMetrics admissionMetrics;`
- New setter `@Autowired(required = false) public void setAdmissionMetrics(...)`
  matching the established Phase-14 setter-injection pattern.
- `queueAction(String, Frame.ActionFrame)`:
  ```java
  Frame.ActionFrame previous = pendingActions.get().put(sessionId, action);
  if (previous != null && admissionMetrics != null) {
      admissionMetrics.incIngressOverwrite();
  }
  ```
  Observational only (D-09): no auto-disconnect, no rate-limit feedback. The
  collapse itself is the protective behavior. The null-guard on
  `admissionMetrics` keeps every pre-Phase-17 unit-test ctor (3 test-only
  overloads at lines 173, 186 of the original file) silently working.

### `CLAUDE.md` — `### Outbound concurrency (Phase 17, D-10)` sub-section
Inserted under `## Architecture` (between "Error handling:" and the
`<!-- GSD:architecture-end -->` marker). Documents:
- VT-per-session loop pattern (`queue.take(); session.sendMessage(...)`).
- Why VT-per-session, not Jetty native async (5 bullets).
- STALLED transition flow: `OutboundSender.offer` overflow callback →
  `markStalled` → `ResumeTokenRegistry.issue` → grace-window → `tryRebind`.
- Single-writer invariant on `session.sendMessage` (per-session VT only;
  `sendFrame` post-detach uses `synchronized(session)`).

## Verification (gates from PLAN)

| Gate | Result |
|------|--------|
| `./gradlew compileJava compileTestJava` | ✅ BUILD SUCCESSFUL |
| `./gradlew test --tests "com.paralife.admission.*" --tests "com.paralife.codec.*" --tests "com.paralife.engine.*"` | ✅ BUILD SUCCESSFUL (2m 11s) |
| `! test -f src/main/java/com/paralife/websocket/PopulationCapConfig.java` | ✅ deleted |
| `! test -f src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java` | ✅ deleted |
| `grep -c "Outbound concurrency" CLAUDE.md` | ✅ 1 hit |
| `grep -c "incIngressOverwrite" src/main/java/com/paralife/engine/ActionResolver.java` | ✅ 1 hit |
| `grep -r "max-active-entities" src/` | ✅ no matches |
| `grep -r "PopulationCapConfig" src/` | ✅ only Javadoc reference in AdmissionConfig (intentional historical) |

## Acceptance criteria

All Task 1 and Task 2 acceptance criteria met (yml structure, file deletions,
test migration, counter wiring, CLAUDE.md content, compile clean, targeted
suites green).

## Deviations from Plan

### Auto-fixed

**1. [Rule 1 — Plan-line-number drift] RESEARCH cited 3 `pendingActions.put`
sites; only 1 exists**
- **Found during:** Task 2 step 1
- **Issue:** Plan said RESEARCH cites lines 125, 262, 299 of `ActionResolver`.
  Inspecting the current file: line 125 is the field declaration, 264 is the
  one and only `put`, 299 is the `getAndSet` drain (not a put).
- **Fix:** Wired the D-09 increment at the single put site only. Per the plan's
  own guidance ("If there are multiple put sites, every one of them needs this
  guard") — there is exactly one, so exactly one guard.
- **Files:** `src/main/java/com/paralife/engine/ActionResolver.java`
- **Commit:** `8485a37`

**2. [Rule 1 — Constructor-change scope] Used setter-injection instead of
ctor-injection for `AdmissionMetrics`**
- **Found during:** Task 2 step 1
- **Issue:** Plan suggested "If `ActionResolver` already has a constructor
  taking dependencies, append `AdmissionMetrics admissionMetrics` to the
  parameter list and store it as a final field." Doing so would break THREE
  test-only convenience constructors (lines 173, 186 in the pre-edit file)
  which are called from many test fixtures (`ActionResolverTest`,
  `CompositeActionTest`, etc.). The existing established pattern in this class
  for late-bound deps is setter-injection (`setEnvironmentEngine`,
  `setBuffRegistry`).
- **Fix:** Followed the existing Phase-14 setter-injection pattern. Field is
  nullable; null-guard at the increment site is a no-op (the collapse still
  happens; only the operator-visibility counter is suppressed).
- **Files:** `src/main/java/com/paralife/engine/ActionResolver.java`
- **Commit:** `8485a37`

### None other

The plan executed cleanly. No Rule 2 / Rule 3 / Rule 4 deviations.

## Residual issues (out of scope for Plan 10)

`./gradlew test --tests "com.paralife.websocket.WorldWebSocketHandlerTest"`
fails with 2 tests:
- `WorldWebSocketHandlerTest.malformedFrameProducesError400`
- `WorldWebSocketHandlerTest.respawnCapEnforced`

Both fail with the same Mockito error: "Wanted but not invoked:
`webSocketSession.sendMessage(...)`". These tests assert on direct mock
`session.sendMessage` calls, but Plan 06+ routes all outbound through
`OutboundSender` (per-session VT loop) — the mock never sees a direct
`sendMessage` call.

**Confirmed pre-existing**: I checked out base commit `f1a4c87` and ran the
same test class against it; both tests failed identically. These failures
predate Plan 10 and are NOT caused by this plan's migration. Per the prompt:
"the other two will be migrated by Plan 10 if they reference deleted symbols.
Read those test files first; if they compile fine after PopulationCapConfig
deletion (because Plan 07 already migrated their assertions), leave them
alone." — they don't reference `PopulationCapConfig` at all (only
`MAX_RESPAWNS_PER_SESSION`-style respawn-cap behavior), so per-prompt
direction I left them untouched.

These should be tracked as a Phase 17 OutboundSender mock-design follow-up
(not a Plan 10 deliverable).

## Self-Check: PASSED

- ✅ `src/main/java/com/paralife/engine/ActionResolver.java` exists, contains
  `incIngressOverwrite`
- ✅ `src/main/resources/application.yml` exists, contains `paralife.admission`,
  does not contain `max-active-entities`
- ✅ `src/test/java/com/paralife/engine/LoadTest.java` exists, contains
  `paralife.admission.cap=1000000`
- ✅ `CLAUDE.md` exists, contains `Outbound concurrency`, `STALLED`,
  `paralife.admission.backpressure.outbound-queue-size`,
  `paralife.admission.backpressure.grace-window-ticks`, `ResumeTokenRegistry`,
  `virtual thread that loops`
- ✅ `src/main/java/com/paralife/websocket/PopulationCapConfig.java` does NOT
  exist
- ✅ `src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java`
  does NOT exist
- ✅ Commit `0580f0c` exists in `git log`
- ✅ Commit `8485a37` exists in `git log`
