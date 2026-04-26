---
phase: 17
plan: "03"
subsystem: admission
tags: [admission, gate, metrics, micrometer, tdd]
dependency_graph:
  requires:
    - AdmissionConfig (Plan 01)
    - RejectionToken (Plan 01)
    - TickHealthMonitor type (Plan 04 owns full impl; stub provided here)
    - ResumeTokenRegistry type (Plan 05 owns full impl; stub provided here)
  provides:
    - AdmissionResult sealed interface (Allow | Reject | Rebind)
    - AdmissionMetrics @Component (tagged counter + 4 D-18 gauges + ingress-overwrites + frame-size)
    - AdmissionGate @Component (single admission decision point; 6-guard evaluate())
    - TickHealthMonitor compile stub (Plan 04 replaces with full @Component + @EventListener)
    - ResumeTokenRegistry compile stub + RebindOutcome record (Plan 05 replaces with full impl)
  affects:
    - Plan 06 (OutboundSender calls AdmissionMetrics.recordFrameSize)
    - Plan 07 (WorldWebSocketHandler delegates to AdmissionGate.evaluate)
    - Plan 05 (ResumeTokenRegistry stub replaced; RebindOutcome interface locked)
    - Plan 04 (TickHealthMonitor stub replaced; isOverloaded() interface locked)
tech_stack:
  added: []
  patterns:
    - "Sealed interface result type with singleton Allow, record Reject, record Rebind"
    - "Micrometer tagged counter (single name + reason tag, D-17 design)"
    - "Micrometer Gauge over AtomicInteger/AtomicLong suppliers"
    - "Micrometer DistributionSummary for frame-size distribution"
    - "TDD RED-GREEN cycle with compile-fail RED gate"
    - "Compile stubs for parallel-wave dependencies"
key_files:
  created:
    - src/main/java/com/paralife/admission/AdmissionResult.java
    - src/main/java/com/paralife/admission/AdmissionMetrics.java
    - src/main/java/com/paralife/admission/AdmissionGate.java
    - src/main/java/com/paralife/admission/TickHealthMonitor.java
    - src/main/java/com/paralife/admission/ResumeTokenRegistry.java
    - src/test/java/com/paralife/admission/AdmissionMetricsTest.java
    - src/test/java/com/paralife/admission/AdmissionGateTest.java
  modified: []
decisions:
  - "Guard order corrected per codex MEDIUM: already-registered runs BEFORE resume-token — live session gets 409, tryRebind not called (T-17-confused mitigation)"
  - "Compile stubs authored for TickHealthMonitor and ResumeTokenRegistry (parallel wave; Plans 04/05 replace these with @Component + @EventListener implementations)"
  - "recordFrameSize(int) DistributionSummary restored in AdmissionMetrics per codex MEDIUM operational-regression review — OutboundSender (Plan 06) is the recording site, not TickBroadcaster"
  - "Stubs committed in the RED gate commit alongside test code so the RED failure is a compile error (class not found) rather than a runtime failure"
metrics:
  duration: "~12 minutes"
  completed: "2026-04-27"
  tasks_completed: 2
  tasks_total: 2
  files_created: 7
  files_modified: 0
requirements_satisfied: [SCALE-01, SCALE-02]
---

# Phase 17 Plan 03: AdmissionGate + AdmissionMetrics Summary

**One-liner:** `AdmissionGate` 6-guard decision bean with corrected guard ordering (already-registered before resume-token), `AdmissionMetrics` with tagged rejection counter + 4 D-18 gauges + frame-size DistributionSummary, and `AdmissionResult` sealed type — pure backend logic, `WorldWebSocketHandler` unwired (Plan 07).

## What Was Built

### Task 1: AdmissionResult + AdmissionMetrics (TDD)

**RED:** `AdmissionMetricsTest` committed first — failed to compile because `AdmissionMetrics` and `AdmissionResult` did not exist.

**GREEN:** Two production files implemented:

**`AdmissionResult.java`** — Sealed interface with three permits:
- `Allow` — singleton instance (`Allow.INSTANCE`), allocated once, zero-alloc on the hot Allow path.
- `Reject(int code, String token)` — compact constructor validates `code` 100..999 and non-blank `token`.
- `Rebind(String entityId, String freshResumeToken)` — both fields validated non-blank.

**`AdmissionMetrics.java`** — `@Component` with 7 metric-name constants:
- `M_REJECTED = "paralife.admission.rejected"` — tagged `reason=<token>` counter (D-17). `incRejected(String)` calls `Counter.builder(...).tag("reason", reason).register(registry).increment()` — single counter name, tags distinguish reasons.
- `M_INGRESS_OVERWRITES = "paralife.admission.ingress.overwrites"` — aggregate counter for last-write-wins collapse (D-09). `incIngressOverwrite()`.
- `M_ACTIVE_ENTITIES = "paralife.admission.active.entities"` — Gauge backed by `AtomicInteger`. `setActiveEntities(int)`.
- `M_MAINTENANCE = "paralife.admission.maintenance"` — Gauge backed by `AtomicInteger` (0/1 mirror). `setMaintenance(boolean)`.
- `M_TICK_WORK_MS = "paralife.tick.health.work-time-ms"` — Gauge backed by `AtomicLong`. `setLastTickWorkMs(long)`.
- `M_STALLED_SESSIONS = "paralife.backpressure.stalled.sessions"` — Gauge backed by `AtomicInteger`. `setStalledSessions(int)`. Counts STALLED entries only (ACTIVE armed tokens excluded per codex/opencode HIGH review).
- `M_FRAME_SIZE = "paralife.outbound.frame.size.bytes"` — `DistributionSummary`. `recordFrameSize(int)`. Restored per codex MEDIUM: `OutboundSender.drainLoop` (Plan 06) is the recording site; `TickBroadcaster` no longer records frame size (Plan 08).

**Test coverage:** 7 tests pass — tagged rejection counter, aggregate ingress counter, frame-size summary, activeEntities gauge, maintenance 0/1 gauge, tickWorkMs gauge, stalledSessions gauge.

### Task 2: AdmissionGate + stubs (TDD)

**RED:** `AdmissionGateTest` committed first alongside two compile stubs (`TickHealthMonitor`, `ResumeTokenRegistry`) — failed to compile because `AdmissionGate` did not exist. Stubs committed in the RED commit to keep the RED gate a compile failure.

**GREEN:** Three production files:

**`TickHealthMonitor.java`** — Compile stub (Plan 04 replaces with full `@Component @EventListener` implementation). Interface locked: `isOverloaded() → boolean`.

**`ResumeTokenRegistry.java`** — Compile stub (Plan 05 replaces with full two-state implementation). Interface locked:
- `tryRebind(String token, String newSessionId, long currentTick) → Optional<RebindOutcome>`
- `record RebindOutcome(String entityId, String freshResumeToken)` — both fields accessed by Plan 03 `AdmissionGate` directly.

**`AdmissionGate.java`** — `@Component`; 6-guard `evaluate(AdmissionRequest) → AdmissionResult`:

```
Guard 1: maintenance flag         → 429 maintenance
Guard 2: tick-overload             → 429 tick-overload
Guard 3: already-registered        → 409 already-registered  ← BEFORE resume-token
Guard 4: resume-token rebind        → Rebind(entityId, freshToken) or fall-through
Guard 5: global cap                 → 429 world-full
Guard 6: per-session respawn cap    → 429 respawn-cap (isRespawn==true only)
         → Allow.INSTANCE
```

Every rejection: `metrics.incRejected(token)` + D-19 log marker:
```
ADMISSION rejected tick={} session={} reason={} active={}/{}
```

`AdmissionRequest` is an inner record: `sessionId`, `tickNumber`, `alreadyAlive`, `isRespawn`, `respawnCount`, `resumeToken(Optional<String>)`.

**Test coverage:** 12 tests (plan required ≥11):
- `allowWhenAllGuardsPass`
- `rejectsMaintenanceFirst`
- `rejectsTickOverloadAheadOfCap`
- `rejectsAlreadyRegisteredBeforeResumeToken` ← guard-order regression test (Mockito.never on tryRebind)
- `rejectsAlreadyRegistered`
- `rejectsWorldFull`
- `rejectsRespawnCap`
- `respawnCapDoesNotApplyOnFreshRegistration`
- `rebindOnValidResumeToken`
- `unknownResumeTokenFallsThroughToFreshRegistration`
- `rebindBypassesWorldFull`
- `counterIncrementsOnEachRejection`

## API Contracts Confirmed for Downstream Plans

### `WorldGrid.livingEntityCount()`

Confirmed at `src/main/java/com/paralife/world/WorldGrid.java:185`. Method exists, returns `int`, counts live non-terrain occupants (Particle, BondedPair, CompositeMember with energy > 0). Name matches the plan exactly — no alias needed.

### `ResumeTokenRegistry.RebindOutcome`

Locked contract: `record RebindOutcome(String entityId, String freshResumeToken)`. Plan 05 must match this exact record definition when replacing the stub. `AdmissionGate` accesses `outcome.entityId()` and `outcome.freshResumeToken()` directly.

### `RespawnConfig` constructor shape

`RespawnConfig(int maxRespawnsPerSession)` — single-field record at `paralife.websocket.max-respawns-per-session`. `new RespawnConfig(3)` in tests matches the constructor. Static factory `RespawnConfig.defaults()` also available.

### `recordFrameSize(int)` wiring for Plan 06

`AdmissionMetrics.recordFrameSize(int bytes)` is implemented. Plan 06's `OutboundSender.drainLoop` should call `admissionMetrics.recordFrameSize(encoded.length())` after encoding each frame. `TickBroadcaster` should NOT record frame size (Plan 08 removes that call).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Compile stubs for Plans 04/05 types**
- **Found during:** Task 2 setup — `TickHealthMonitor` and `ResumeTokenRegistry` do not exist in this worktree (Plans 04 and 05 are parallel wave 2 plans, not yet executed).
- **Issue:** `AdmissionGate` references both types; without them, `compileJava` fails.
- **Fix:** Created minimal compile stubs: `TickHealthMonitor.isOverloaded()` returns false; `ResumeTokenRegistry.tryRebind()` returns `Optional.empty()`. Both stubs include the locked interface contract in Javadoc. Plans 04 and 05 replace these stubs with full `@Component @EventListener` implementations; the merge resolution is straightforward (same package, same method signatures).
- **Files added:** `src/main/java/com/paralife/admission/TickHealthMonitor.java`, `src/main/java/com/paralife/admission/ResumeTokenRegistry.java`
- **Commit:** `fd19a0c`

## TDD Gate Compliance

| Gate | Task | Commit | Status |
|------|------|--------|--------|
| RED (Task 1) | AdmissionMetricsTest — compile fail (AdmissionMetrics missing) | `7c5d3a9` | PASS |
| GREEN (Task 1) | AdmissionMetrics + AdmissionResult — 7 tests pass | `28f2998` | PASS |
| RED (Task 2) | AdmissionGateTest + stubs — compile fail (AdmissionGate missing) | `fd19a0c` | PASS |
| GREEN (Task 2) | AdmissionGate — 12 tests pass | `633cdfd` | PASS |

## Known Stubs

| Stub | File | Reason |
|------|------|--------|
| `TickHealthMonitor.isOverloaded()` always returns false | `src/main/java/com/paralife/admission/TickHealthMonitor.java` | Parallel wave: Plan 04 owns the ring-buffer hysteresis implementation |
| `ResumeTokenRegistry.tryRebind()` always returns empty | `src/main/java/com/paralife/admission/ResumeTokenRegistry.java` | Parallel wave: Plan 05 owns the two-state token lifecycle implementation |

These stubs do not prevent the plan's goal — `AdmissionGate` logic is fully exercised by mocks in tests. The stubs exist only to allow `compileJava` to succeed before Plans 04/05 merge.

## Threat Flags

No new network endpoints, auth paths, file access patterns, or schema changes introduced by this plan. `AdmissionGate` is a pure decision bean — not wired to any WebSocket handler (Plan 07 does that). The threat mitigations in the plan's `<threat_model>` are all implemented:

- **T-17-04** (memory exhaustion): All guards are O(1) — config field reads, single boolean, single map.get. No per-attempt allocations beyond the small `Reject` record.
- **T-17-06** (maintenance bypass): First guard checked before any other state.
- **T-17-01** (token replay): `tryRebind` not called for already-alive sessions.
- **T-17-confused** (confused deputy): `rejectsAlreadyRegisteredBeforeResumeToken` test verifies `tryRebind` never invoked via `Mockito.never()`.

## Self-Check: PASSED

Files created:
- FOUND: `src/main/java/com/paralife/admission/AdmissionResult.java`
- FOUND: `src/main/java/com/paralife/admission/AdmissionMetrics.java`
- FOUND: `src/main/java/com/paralife/admission/AdmissionGate.java`
- FOUND: `src/main/java/com/paralife/admission/TickHealthMonitor.java`
- FOUND: `src/main/java/com/paralife/admission/ResumeTokenRegistry.java`
- FOUND: `src/test/java/com/paralife/admission/AdmissionMetricsTest.java`
- FOUND: `src/test/java/com/paralife/admission/AdmissionGateTest.java`

Commits verified:
- FOUND: `7c5d3a9` — test(17-03): add failing AdmissionMetricsTest (RED gate Task 1)
- FOUND: `28f2998` — feat(17-03): implement AdmissionResult sealed type + AdmissionMetrics bean (GREEN Task 1)
- FOUND: `fd19a0c` — test(17-03): add failing AdmissionGateTest + stub types (RED gate Task 2)
- FOUND: `633cdfd` — feat(17-03): implement AdmissionGate bean (GREEN Task 2)
