---
phase: 17
plan: "07"
subsystem: websocket
tags: [worldwebsockethandler, botregistry, admission, fsm, stalled, retoken, backpressure]
requirements: [SCALE-01, SCALE-02]

dependency_graph:
  requires:
    - AdmissionGate (Plan 03)
    - AdmissionMetrics (Plans 03/05/06)
    - AdmissionConfig (Plan 01)
    - RejectionToken (Plan 01)
    - OutboundSender (Plan 06)
    - ResumeTokenRegistry (Plan 05)
    - TickEngine.currentTick() (Plan 04)
    - Frame.SyncFrame 3-arg ctor (Plan 02)
  provides:
    - WorldWebSocketHandler refactored — all admission via AdmissionGate.evaluate
    - STALLED FSM with ATTR_STALL_TICK / ATTR_RESUME_TOKEN session attributes
    - public boolean isStalled(WebSocketSession session) — Plan 08 compile gate
    - markStalled: idempotent, out-of-band 408, post-detach send ordering
    - STALLED-aware afterConnectionClosed (entity preserved for grace sweep)
    - cleanupByEntityId: wired as ResumeTokenRegistry.setCleanupCallback
    - BotRegistry.rebindSession helper with withSessionId + collision guard
    - BotRegistry.getSessionByEntity alias
  affects:
    - Plan 08 (TickBroadcaster uses isStalled(WebSocketSession) — compile gate)
    - Plan 10 (WorldWebSocketHandlerTest + PopulationCapTest migration)
    - Plan 11 (StallRecoveryIntegrationTest)

tech_stack:
  added: []
  patterns:
    - "AdmissionGate.evaluate delegation — single admission decision point"
    - "STALLED FSM pivot — ATTR_STALL_TICK as idempotent state gate"
    - "Option B respawn-count migration — ConcurrentHashMap<entityId,Integer> in handler"
    - "Out-of-band 408 delivery — detachSession before synchronized(session) sendMessage"
    - "cleanupByEntityId entity-id-keyed reaper wired as Consumer<String> callback"
    - "ConcurrentHashMap fire-once respawnCountAtStall snapshot"

key_files:
  created:
    - src/test/java/com/paralife/engine/BotRegistryRebindTest.java
  modified:
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/engine/BotRegistry.java

decisions:
  - "Option B chosen for respawn-count migration: external ConcurrentHashMap<entityId,Integer> in WorldWebSocketHandler avoids retroactively widening Plan 05 ResumeTokenRegistry/RebindOutcome API"
  - "BotRegistry.getSessionByEntity added as alias for getSessionForEntity (plan spec name for cleanupByEntityId call site)"
  - "BotState.withSessionId(String) copy-on-write helper added as a record method — BotState has sessionId as a component so withSessionId is required by rebindSession"
  - "Back-compat convenience ctors preserved (6-arg, 7-arg) with null OutboundSender/AdmissionGate/etc — sendFrame falls back to synchronized send when OutboundSender null"
  - "WorldWebSocketHandlerTest and PopulationCapTest failures accepted as expected Plan 10 migration (tests use mock sessions without calling afterConnectionEstablished; OutboundSender queue never attached so offer returns false and frame is dropped silently)"

metrics:
  duration: "~25 minutes"
  completed: "2026-04-27"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 2
---

# Phase 17 Plan 07: WorldWebSocketHandler Wave-2 Integration Summary

**One-liner:** `WorldWebSocketHandler` fully wired to Wave-2 beans — `AdmissionGate.evaluate` replaces all inline admission guards, all 8 free-text rejection strings replaced by `RejectionToken` constants, STALLED FSM added with idempotent `markStalled` / out-of-band 408 / STALLED-aware close, `cleanupByEntityId` entity-keyed reaper, resume token issuance on every successful registration, `BotRegistry.rebindSession` helper with collision guard.

## What Was Built

### Task 1: BotRegistry.rebindSession + contract tests

**`BotRegistry.java`** extended with three additions:

**`BotState.withSessionId(String)`** — copy-on-write record method returning a new `BotState` with the session ID replaced. Required because `BotState` stores `sessionId` as a record component; `rebindSession` uses this to update `bySession` atomically without breaking immutability.

**`rebindSession(String newSessionId, String entityId)`** — synchronized helper that swaps the session→entity binding while preserving all other BotState fields (position, entityId). Contract:
- Unknown entityId → return false (no-op)
- Collision with different entity at newSessionId → throw `IllegalStateException`
- Self-rebind (same sessionId) → succeeds, state remains consistent

**`getSessionByEntity(String entityId)`** — one-line alias for `getSessionForEntity`, used by `WorldWebSocketHandler.cleanupByEntityId` (name from plan spec).

**`BotRegistryRebindTest.java`** — 6 contract tests:
1. `rebindSwapsSessionIdPreservingEntity` — new session gets entity; old session gone
2. `rebindUpdatesReverseEntityToSessionMapping` — `getSessionForEntity` returns new session
3. `rebindUnknownEntityReturnsFalse` — unknown entity is a no-op
4. `rebindRefusesCollisionWithDifferentEntity` — `IllegalStateException` with message
5. `rebindIsIdempotentForSameSessionAndEntity` — self-rebind preserves consistent state
6. `rebindPreservesEntityPosition` — position field unchanged after swap
7. `rebindDoesNotAffectOtherRegisteredEntities` — unrelated sessions/entities untouched

All 7 tests pass (plan required ≥ 4; delivered 7 for full coverage).

### Task 2: WorldWebSocketHandler refactor

**Complete rewrite of the admission and lifecycle logic.** All must-have truths satisfied:

**Admission delegation** — `handleRegister` builds an `AdmissionGate.AdmissionRequest` from session attrs and calls `admissionGate.evaluate(req)`. The single-dispatch pattern replaces all inline `if` guards. Three branches:
- `Reject(code, token)` → `sendFrame(E|<code>|<token>)`, return
- `Rebind(entityId, freshToken)` → swap session, restore respawn count, emit `SyncFrame`, return
- `Allow` → run placement logic, issue token, emit `SyncFrame`

**Retokenisation** — All 8 free-text rejection strings replaced by `RejectionToken` constants. `admissionMetrics.incRejected(token)` called at every rejection site. `grep` confirms zero free-text survivors.

**STALLED FSM keys** — `ATTR_STALL_TICK` and `ATTR_RESUME_TOKEN` added. Two `isStalled` overloads: `boolean isStalled(Map<String,Object> attrs)` (internal) and `public boolean isStalled(WebSocketSession session)` (Plan 08 compile gate).

**`@PostConstruct wireCrossBeanCallbacks`** — wires two cross-bean callbacks:
1. `outboundSender.setOverflowCallback(...)` → calls `markStalled(session, currentTick)` on overflow
2. `resumeTokenRegistry.setCleanupCallback(this::cleanupByEntityId)` — entity-id-keyed reaper

**`afterConnectionEstablished`** — calls `outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize())` after session registry register.

**STALLED-aware `afterConnectionClosed`** (consensus HIGH fix) — if `wasStalled`, detach + unregister WS only; `cleanupBot` is NOT called. Entity held for grace sweep. If not stalled, clears ACTIVE token and runs `cleanupBot`.

**`markStalled`** (idempotent, out-of-band 408, codex HIGH fix):
1. Guard: `if (attrs.containsKey(ATTR_STALL_TICK)) return;` — FIRST line, prevents re-fire
2. Set `ATTR_STALL_TICK = stallTick`
3. Snapshot `respawnCountAtStall.put(entityId, respawnCountOf(attrs))`
4. `resumeTokenRegistry.convertToStalled(activeToken, stallTick)` — ACTIVE → STALLED
5. `outboundSender.detachSession(session.getId())` — joins VT ≤ 100ms
6. `sendOutOfBand(session, E|408|reconnect-required)` — direct synchronized send POST-detach
7. `session.close(CloseStatus.SERVICE_RESTARTED)` — force client reconnect

**`cleanupByEntityId`** — resolves entity → session via `botRegistry.getSessionByEntity`, calls `cleanupBot`, removes `respawnCountAtStall` entry.

**`sendFrame`** — routes through `outboundSender.offer`; drops frame silently if queue full/detached (overflow callback handles stall); falls back to synchronized send if `outboundSender == null` (back-compat ctors).

**`sendOutOfBand`** — direct `synchronized(session) sendMessage` for out-of-band 408 delivery.

**`cleanupBot`** — extended to clear `ATTR_STALL_TICK`, `ATTR_RESUME_TOKEN`, and `respawnCountAtStall` in addition to BotRegistry / grid cleanup. Calls `admissionMetrics.setActiveEntities` after cleanup.

**Allow branch** — `resumeTokenRegistry.issueActive(entityId, session.getId())` called after placement; `attrs.put(ATTR_RESUME_TOKEN, resumeToken)` caches token for `markStalled`; `SyncFrame(entityId, Optional.of(resumeToken), List.of())` emits D-13 wire shape.

**Rebind branch** — `respawnCountAtStall.remove(rebind.entityId())` restores `ATTR_RESPAWN_COUNT` on rebind (Option B — external map in handler).

## Plan 05 API Option

**Option B** used: external `ConcurrentHashMap<String, Integer> respawnCountAtStall` in `WorldWebSocketHandler`, populated at `markStalled` time and consumed in the Rebind branch. This avoids retroactively widening Plan 05's `ResumeTokenRegistry.RebindOutcome` record or adding a new `convertToStalled` overload. Option A (extending `RebindOutcome.respawnCount`) remains valid for a future refactor if the external map becomes a maintenance concern.

## BotRegistry additions from this plan

`BotRegistry.getSessionByEntity` — **added by this plan** (did not exist before; `getSessionForEntity` existed but the plan spec required this alias name for the `cleanupByEntityId` call site).

`BotState.withSessionId` — **added by this plan** (BotState had no copy-with methods before).

## Tests broken pending Plan 10 migration

| Test class | Failure | Root cause |
|---|---|---|
| `WorldWebSocketHandlerPopulationCapTest` | `populationCapEnforcedWithError429` | Mock session not attached to `OutboundSender`; `sendFrame` drops frame silently via `offer` returning false |
| `WorldWebSocketHandlerTest` | `malformedFrameProducesError400`, `respawnCapEnforced` | Same root cause — mock sessions skip `afterConnectionEstablished` so no queue is attached |

These are the Plan 10-expected migrations. The tests assert on `verify(session, atLeastOnce()).sendMessage(...)` but the frame now flows through `OutboundSender.offer` → VT drain loop → `session.sendMessage`, bypassing the mock capture. Plan 10 must either (a) register the mock session with `outboundSender.attachSession` in setUp, or (b) rewrite the tests against the new admission API.

## Deviations from Plan

None — plan executed exactly as written. All consensus HIGH/MEDIUM fixes applied. Option B chosen as directed by plan (§ "Decision: use Option B in this plan").

## Verification Results

| Check | Result |
|---|---|
| `./gradlew compileJava compileTestJava` exits 0 | PASS |
| `RejectionToken.` count ≥ 7 | 13 |
| No free-text rejection strings | 0 matches |
| `cleanupByEntityId` count ≥ 2 | 5 |
| Idempotent guard count == 1 | 1 |
| `respawnCountAtStall` count ≥ 3 | 6 |
| Wave-2 tests (`admission.*`, `codec.*`, `BotRegistryRebindTest`) | PASS |
| `WorldWebSocketHandlerPopulationCapTest` | FAIL (Plan 10 expected) |
| `WorldWebSocketHandlerTest` (2 tests) | FAIL (Plan 10 expected) |

## Threat Surface Scan

No new network endpoints, auth paths, or schema changes. All threat mitigations from the plan's `<threat_model>` are implemented:

| Threat ID | Mitigation | Implemented |
|---|---|---|
| T-17-01 — token replay | `tryRebind` consumes token; `ATTR_RESPAWN_COUNT` restored on rebind | Yes |
| T-17-03 — slow-consumer DoS | `markStalled` idempotent; detach joins VT; grace expiry reaps | Yes |
| T-17-stall-reap — live entity reaped on STALLED close | `if (wasStalled)` skips `cleanupBot` entirely | Yes |
| T-17-stallbypass — stall-cycle bypasses respawn cap | `respawnCountAtStall.put/remove` snapshot + restore on Rebind | Yes |
| T-17-04 — memory exhaustion via flood r| | `sendFrame` drop path; no synchronized fallback when queue full | Yes |
| T-17-06 — maintenance bypass | `AdmissionGate.evaluate` is FIRST call in `handleRegister` | Yes |
| T-17-misc — STALLED → re-attach race | `ATTR_STALL_TICK` check at top of `handleTextMessage` | Yes |

## Self-Check: PASSED

| Check | Result |
|---|---|
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` exists | FOUND |
| `src/main/java/com/paralife/engine/BotRegistry.java` contains `rebindSession` | FOUND |
| `src/test/java/com/paralife/engine/BotRegistryRebindTest.java` exists | FOUND |
| Commit b071eca (Task 1) exists | FOUND |
| Commit 53ed93d (Task 2) exists | FOUND |
| `grep -F 'public synchronized boolean rebindSession' BotRegistry.java` | 1 match |
| `grep -F 'public boolean isStalled(WebSocketSession session)' WorldWebSocketHandler.java` | 1 match |
| `grep -F 'admissionGate.evaluate(' WorldWebSocketHandler.java` | 1 match |
| `grep -F 'attrs.put(ATTR_RESUME_TOKEN, resumeToken)' WorldWebSocketHandler.java` | 1 match |
| `grep -F 'new Frame.SyncFrame(entityId, Optional.of(resumeToken)' WorldWebSocketHandler.java` | 1 match |
