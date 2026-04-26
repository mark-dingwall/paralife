---
phase: 17
plan: 05
subsystem: admission
tags: [resume-token, grace-window, backpressure, registry, two-state-lifecycle]
requirements: [SCALE-01, SCALE-02]

dependency_graph:
  requires: [17-01, 17-02]
  provides: [ResumeTokenRegistry, AdmissionMetrics]
  affects: [17-03, 17-06, 17-07]

tech_stack:
  added: []
  patterns:
    - ConcurrentHashMap with putIfAbsent loop for collision-safe token minting
    - Two-state enum lifecycle (ACTIVE/STALLED) to eliminate spurious reaping of live entities
    - AtomicInteger gauge supplier for live Micrometer gauge updates
    - compare-and-remove(K,V) for atomic single-use rebind (prevents token replay)

key_files:
  created:
    - src/main/java/com/paralife/admission/ResumeTokenRegistry.java
    - src/main/java/com/paralife/admission/AdmissionMetrics.java
    - src/test/java/com/paralife/admission/ResumeTokenRegistryTest.java
  modified: []

decisions:
  - "Token format locked: r:%016x (18 chars). The r: prefix is the Plan 02 codec disambiguator."
  - "ACTIVE entries are never touched by the sweep — eliminates codex HIGH live-entity-reaping bug."
  - "Gauge counts STALLED entries only — fixes codex/opencode HIGH over-reporting."
  - "Callback contract: Consumer<String entityId>, not sessionId. Plan 07 wires cleanupByEntityId."
  - "AdmissionMetrics created in Plan 05 (not 03) to unblock compilation of ResumeTokenRegistry tests."
  - "tryRebind boundary: expiresAtTick <= currentTick is expired (matches sweep boundary)."

metrics:
  duration: "~12 minutes"
  completed: "2026-04-26T23:42:48Z"
  tasks_completed: 1
  files_changed: 3
---

# Phase 17 Plan 05: ResumeTokenRegistry Summary

One-liner: Two-state grace registry (`ResumeTokenRegistry`) + metrics bean (`AdmissionMetrics`) with ACTIVE/STALLED lifecycle — ACTIVE tokens never reaped, STALLED tokens gauged and expirable, fixing codex/opencode HIGH bugs.

## What Was Built

**`ResumeTokenRegistry`** (`@Component`, `com.paralife.admission`):

The central grace-window registry for Phase 17 backpressure. Implements the two-state lifecycle mandated by codex HIGH and opencode MEDIUM reviews to fix two previously identified bugs:
1. Gauge over-reporting: `tokenMap.size()` counted all armed tokens including live sessions.
2. Live-entity reaping: sweep reaped all entries regardless of session state, killing connected bots.

State machine:
- `ACTIVE`: minted by `issueActive()` on successful registration. `expiresAtTick = Long.MAX_VALUE`. Sweep never touches these. Gauge excluded.
- `STALLED`: entered via `convertToStalled()` when queue overflow is detected. `expiresAtTick = currentTick + graceWindowTicks`. Gauge counts these. Sweep reaps at boundary-inclusive `expiresAtTick <= currentTick`.

Key methods:
- `issueActive(entityId, sessionId)` — mints `r:%016x` token via collision-safe `putIfAbsent` loop
- `clearActive(entityId)` — removes ACTIVE entry on normal disconnect; STALLED entries unaffected
- `convertToStalled(token, currentTick)` — idempotent ACTIVE→STALLED transition; increments gauge
- `tryRebind(token, newSessionId, currentTick)` — STALLED-only; atomic `remove(K,V)` prevents replay; mints fresh ACTIVE; decrements gauge
- `onTick(TickEvent)` at `@Order(1)` — sweeps STALLED-and-expired entries; invokes `Consumer<entityId>` callback

**`AdmissionMetrics`** (`@Component`, `com.paralife.admission`):

Created in this plan (rather than Plan 03) to unblock compilation of `ResumeTokenRegistryTest` in the parallel wave-2 execution. Plan 03's `AdmissionGate` and `AdmissionGateTest` will compile against this class as-is.

Exposes: tagged rejection counter `paralife.admission.rejected{reason=<token>}`, ingress-overwrite counter, frame-size distribution summary, and 4 D-18 gauges (active entities, maintenance, last-tick work ms, stalled sessions).

## Tests

17 `@Test` methods in `ResumeTokenRegistryTest` (plan required ≥14):

| Test | Coverage |
|------|----------|
| `issueActiveMatchesFormatAndDoesNotIncrementGauge` | Token format `^r:[0-9a-f]{16}$`, gauge=0 |
| `convertToStalledFlipsStateAndIncrementsGauge` | State transition, expiresAtTick=currentTick+5, gauge=1 |
| `convertToStalledOnUnknownTokenIsNoOp` | No-op on missing token |
| `convertToStalledIsIdempotent` | Second call leaves expiry unchanged; gauge stays 1 |
| `tryRebindRejectsActiveTokens` | ACTIVE token returns empty |
| `tryRebindOnStalledReturnsFreshActiveToken` | Full rebind path: entity correct, fresh token format, old consumed, new ACTIVE, gauge=0 |
| `tryRebindUnknownReturnsEmpty` | Unknown token returns empty |
| `tryRebindNullReturnsEmpty` | Null-safe |
| `doubleRebindOfSameTokenFails` | Single-use: second rebind returns empty |
| `tryRebindExpiredStalledReturnsEmpty` | Expired token rejected |
| `sweepReapsOnlyStalledExpiredAndInvokesCallbackWithEntityId` | t1+t2 reaped at tick 105 (boundary inclusive), t3 stays, ACTIVE never reaped, callback receives entityId |
| `sweepDoesNotReapActiveEntries` | ACTIVE entries survive even at tick 1_000_000 |
| `sweepWithoutCallbackStillRemovesEntries` | Entries removed even if no callback wired |
| `clearActiveRemovesActiveEntryForEntity` | ACTIVE entry removed |
| `clearActiveDoesNotTouchStalledEntry` | STALLED entry preserved |
| `issuedTokensAreUnique` | Two tokens for different entities differ |
| `gaugeFiltersToStalledOnly` | 3 ACTIVE + 1 STALLED → gauge=1, size=4 |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created AdmissionMetrics in Plan 05 worktree**
- **Found during:** Task 1, RED phase (compileTestJava)
- **Issue:** `ResumeTokenRegistryTest` imports and instantiates `AdmissionMetrics` (a Plan 03 artifact). Since plans 03 and 05 execute in parallel worktrees from the same base commit, `AdmissionMetrics` did not yet exist in this worktree's source tree.
- **Fix:** Created `AdmissionMetrics.java` with the full D-17/D-18 surface (identical to Plan 03's spec) so compilation succeeds in this worktree. Plan 03 will produce the same file; the orchestrator's merge will resolve identically.
- **Files modified:** `src/main/java/com/paralife/admission/AdmissionMetrics.java`
- **Commit:** 2ce6ca9

## Threat Surface Scan

No new trust boundaries introduced. `ResumeTokenRegistry` closes T-17-01 (replay), T-17-02 (brute-force), T-17-03 (live-entity-reaping), T-17-05 (unbounded growth), and T-17-live-rebind (confused-deputy) as specified in the plan threat model.

## Self-Check: PASSED

| Check | Result |
|-------|--------|
| `ResumeTokenRegistry.java` exists | FOUND |
| `AdmissionMetrics.java` exists | FOUND |
| `ResumeTokenRegistryTest.java` exists | FOUND |
| Commit d169f65 (RED) exists | FOUND |
| Commit 2ce6ca9 (GREEN) exists | FOUND |
| `./gradlew test --tests ResumeTokenRegistryTest` exits 0 | PASSED (17 tests) |
| `./gradlew compileJava compileTestJava` exits 0 | PASSED |
