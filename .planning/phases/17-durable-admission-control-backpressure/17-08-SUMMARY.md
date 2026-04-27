---
phase: 17
plan: "08"
subsystem: websocket
tags: [tickbroadcaster, refactor, outbound, virtual-threads, no-synchronized, backpressure]
dependency_graph:
  requires:
    - OutboundSender (Plan 06 — offer/attachSession/detachSession API)
    - WorldWebSocketHandler.isStalled (Plan 07 compile gate — added here as Rule 3 fix)
  provides:
    - TickBroadcaster non-blocking on outbound — enqueues via OutboundSender.offer
    - STALLED-skip in onTick and drainAndBroadcastDeaths
    - WorldWebSocketHandler.isStalled(WebSocketSession) public overload (Plan 07 prerequisite)
    - WorldWebSocketHandler OutboundSender attach/detach in connection lifecycle
  affects:
    - Plan 07 (WorldWebSocketHandler full refactor — isStalled overload already present)
    - Plan 11 (StallRecoveryIntegrationTest — verifies STALLED pivot via tick saturation)
tech_stack:
  added: []
  patterns:
    - "outboundSender.offer(sessionId, frame) — non-blocking enqueue in tick hot path"
    - "worldWebSocketHandler.isStalled(session) — STALLED-skip before offer"
    - "Setter injection (@Autowired required=false) for OutboundSender and WorldWebSocketHandler on TickBroadcaster"
    - "Setter injection on WorldWebSocketHandler for OutboundSender (Rule 3 minimal wiring)"
key_files:
  created: []
  modified:
    - src/main/java/com/paralife/websocket/TickBroadcaster.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/test/java/com/paralife/websocket/TickBroadcasterProjectionTest.java
    - src/test/java/com/paralife/websocket/CompositePerceptionTest.java
    - src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java
decisions:
  - "Approach A used for STALLED predicate — WorldWebSocketHandler.isStalled(WebSocketSession) injected into TickBroadcaster. No circular dep: WorldWebSocketHandler does NOT inject TickBroadcaster."
  - "recordFrameSize removed from TickBroadcaster — sole measurement point is OutboundSender.drainLoop (AdmissionMetrics.M_FRAME_SIZE). WebSocketMetrics.M_TICK_FRAME_BYTES no longer incremented from tick broadcast path."
  - "Fallback synchronized(session) path eliminated entirely — outboundSender==null silently drops frame; this only occurs in legacy buildTickFrame-only unit tests that never exercise delivery."
  - "Rule 3: added WorldWebSocketHandler.isStalled(WebSocketSession) + ATTR_STALL_TICK constant as Plan 07 compile gate prerequisite."
  - "Rule 3: added OutboundSender attach/detach in WorldWebSocketHandler.afterConnectionEstablished/afterConnectionClosed (setter-injected, required=false) to fix integration test regressions caused by frames being dropped to sessions with no queue."
metrics:
  duration: "~35 minutes"
  completed: "2026-04-27"
  tasks_completed: 1
  tasks_total: 1
  files_created: 0
  files_modified: 5
requirements_satisfied: [SCALE-02]
---

# Phase 17 Plan 08: TickBroadcaster Non-Blocking Refactor Summary

**One-liner:** Replaced `synchronized(session) { session.sendMessage(...) }` with `outboundSender.offer(sessionId, frame)` in the tick broadcast hot path; tick thread no longer blocks on slow socket writes; STALLED sessions skipped via `worldWebSocketHandler.isStalled(session)`; `recordFrameSize` relocated to `OutboundSender.drainLoop` as sole measurement point.

## What Was Built

### TickBroadcaster.java

- **`onTick`:** Removed `synchronized(session)` + direct `sendMessage`. Now calls `outboundSender.offer(bot.sessionId(), frame)` — non-blocking enqueue. Added STALLED-skip before offer: `if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) { skipped++; continue; }`. Removed `metrics.recordFrameSize(...)` call entirely.
- **`drainAndBroadcastDeaths`:** Same refactor — `offer` replaces `sendMessage`, STALLED-skip added, `recordFrameSize` removed, `IOException` catch removed (no longer thrown from non-blocking path).
- **`OutboundSender outboundSender`:** New setter-injected field (`@Autowired required=false`).
- **Import cleanup:** Removed `TextMessage`, `StandardCharsets`, `IOException`, `java.io.IOException`. Added `OutboundSender`, `@Lazy`.

### WorldWebSocketHandler.java (Rule 3 fixes)

**Rule 3 fix 1 — Plan 07 compile gate prerequisite:**
- Added `static final String ATTR_STALL_TICK = "stallTick"` (package-visible constant; Plan 07 will promote to the full FSM).
- Added `public boolean isStalled(WebSocketSession session)` — checks `session.getAttributes().containsKey(ATTR_STALL_TICK)`. Required by `TickBroadcaster.onTick` and `drainAndBroadcastDeaths`.

**Rule 3 fix 2 — Integration test regressions:**
Without `attachSession` being called, `outboundSender.offer` always returns false (no queue) and all frames are silently dropped. All `@SpringBootTest` integration tests (`WebSocketIntegrationTest`, `HundredBotIntegrationTest`, `BotClientIntegrationTest`, etc.) timed out on tick frame receipt.
- Added `private com.paralife.admission.OutboundSender outboundSender` (setter-injected, `required=false`).
- Added `setOutboundSender(@Autowired required=false)` setter.
- `afterConnectionEstablished`: calls `outboundSender.attachSession(session, 16)` when sender is wired.
- `afterConnectionClosed`: calls `outboundSender.detachSession(session.getId())` before cleanup.

### Test updates

**TickBroadcasterProjectionTest:**
- Added `OutboundSender outboundSenderMock` (Mockito mock, `when offer(...) thenReturn true`).
- Injected via `broadcaster.setOutboundSender(outboundSenderMock)` in `setUp`.
- Updated `onTickSendsFrameToRegisteredBots` → `onTickEnqueuesFrameToRegisteredBots`: asserts `verify(outboundSenderMock).offer(eq("s1"), any(Frame.class))` instead of `verify(session).sendMessage(...)`.
- Updated `onTickSkipsClosedSessions`: asserts `verify(outboundSenderMock, never()).offer(...)`.
- Updated `deathNoticeEmitsTerminalVDFrame` → `deathNoticeEnqueuesTerminalVDFrameViaOutboundSender`: captures `Frame` from `offer`, asserts `events().get(0).code() == 'D'` directly on the `TickFrame` object (no codec round-trip needed).
- Updated `onTickWithNoDeathsDoesNotBlockLiveBots` → `onTickWithNoDeathsEnqueuesOnlyLiveBotFrame`.
- Added `when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>())` to mocked sessions (needed for STALLED-skip predicate).
- Removed `TextMessage` import.

**CompositePerceptionTest:** Same pattern — added `outboundSenderMock`, wired via setter, updated `onTickSendsCodecEncodedFrame` → `onTickEnqueuesCodecFrameViaOutboundSender` and `allMembersReceiveTickFrames` → `allMembersReceiveTickFramesViaOutboundSender` to capture `Frame` from `offer` rather than decoding from `sendMessage`. Added `getAttributes()` stub to `mockSession` helper.

**WebSocketMetricsWiringTest:** Updated `broadcasterTickDrivesTickFrameBytesDistribution` → `broadcasterTickDrivesFrameSizeDistribution`. Now asserts on `AdmissionMetrics.M_FRAME_SIZE` (`paralife.outbound.frame.size.bytes`) rather than `WebSocketMetrics.M_TICK_FRAME_BYTES`. Test attaches session to `OutboundSender` directly (`outboundSender.attachSession(mock, 16)`) so the VT drain loop runs; waits up to 2s for metric to increment via a polling loop.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] WorldWebSocketHandler.isStalled(WebSocketSession) absent — Plan 07 not yet executed**
- **Found during:** Pre-implementation check
- **Issue:** Plan 08 depends on `WorldWebSocketHandler.isStalled(WebSocketSession)` (Plan 07 compile gate). Plan 07 had not been executed; the method did not exist. Without it, Plan 08 code does not compile.
- **Fix:** Added `ATTR_STALL_TICK` constant and `public boolean isStalled(WebSocketSession session)` to `WorldWebSocketHandler`. Minimal surface — exactly what Plan 08 needs; does not implement the full STALLED FSM.
- **Files modified:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`
- **Commit:** `10fa760`

**2. [Rule 3 - Blocking] Integration tests broken — OutboundSender not attached to sessions**
- **Found during:** Full test run after Plan 08 refactor
- **Issue:** `TickBroadcaster.onTick` now calls `outboundSender.offer(sessionId, frame)`. Without `attachSession` being called for each session on connect, `offer` always returns false (no queue in the map). All `@SpringBootTest` integration tests that connect real WebSocket clients failed: `WebSocketIntegrationTest`, `HundredBotIntegrationTest`, `BotClientIntegrationTest`, `RespawnFlowIntegrationTest`, `PerceptionActionIntegrationTest`, `MetabolismIntegrationTest`, `EnvironmentFullStackSmokeTest`, `LoadTest`.
- **Fix:** Added setter-injected `OutboundSender` to `WorldWebSocketHandler`; wired `attachSession(session, 16)` in `afterConnectionEstablished` and `detachSession(session.getId())` in `afterConnectionClosed`. Full Plan 07 replaces these with `admissionConfig.backpressure().outboundQueueSize()`.
- **Files modified:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`
- **Commit:** `10fa760`

**3. [Rule 1 - Bug] WebSocketMetricsWiringTest asserted on wrong metric**
- **Found during:** Full test run
- **Issue:** `WebSocketMetricsWiringTest.broadcasterTickDrivesTickFrameBytesDistribution` asserted on `WebSocketMetrics.M_TICK_FRAME_BYTES` (`paralife.ws.tick.frame.bytes`), which `TickBroadcaster` no longer increments. The metric moved to `AdmissionMetrics.M_FRAME_SIZE` (`paralife.outbound.frame.size.bytes`) in `OutboundSender.drainLoop`.
- **Fix:** Updated test to assert on `AdmissionMetrics.M_FRAME_SIZE`, attach the mock session to `OutboundSender` so the VT drain path runs, and poll up to 2s for the metric to increment.
- **Files modified:** `src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java`
- **Commit:** `10fa760`

## Circular Dependency Confirmation

Confirmed: `WorldWebSocketHandler` does NOT inject `TickBroadcaster`. Injecting `WorldWebSocketHandler` into `TickBroadcaster` is acyclic. **Approach A used** (handler bean, not Approach B constant duplication).

## recordFrameSize Removal

`TickBroadcaster` no longer calls `metrics.recordFrameSize`. The existing `WebSocketMetrics.recordFrameSize` method still exists (no signature removed), but is no longer called from the tick broadcast path. The canonical measurement point is `OutboundSender.drainLoop` calling `AdmissionMetrics.recordFrameSize` (Plan 06). No double-counting.

## Known Stubs

None — `outboundSender.offer` is fully wired end-to-end in the Spring context. Sessions are attached on connect, drained by VTs, detached on close.

## Threat Surface

No new network endpoints or auth paths introduced. Threat mitigations applied:

| Threat | Mitigation |
|--------|-----------|
| T-17-03: Slow-consumer tick drift | `synchronized(session).sendMessage` removed; tick thread never blocks on socket I/O |
| T-17-misc: STALLED session receives frame after stall | `isStalled(session)` check before `offer` — skips enqueue. Even if a race occurs, `OutboundSender.offer` returns false harmlessly (queue map entry already removed by `detachSession`) |

## Self-Check: PASSED

Files modified:
- FOUND: `src/main/java/com/paralife/websocket/TickBroadcaster.java`
- FOUND: `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`
- FOUND: `src/test/java/com/paralife/websocket/TickBroadcasterProjectionTest.java`
- FOUND: `src/test/java/com/paralife/websocket/CompositePerceptionTest.java`
- FOUND: `src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java`

Acceptance criteria:
- `outboundSender.offer` count = 2: CONFIRMED
- `synchronized (session)` count = 0: CONFIRMED
- `session.sendMessage` count = 0: CONFIRMED
- `recordFrameSize` calls = 0: CONFIRMED
- `worldWebSocketHandler.isStalled` count = 2: CONFIRMED
- `OutboundSender` field declared: CONFIRMED
- `WorldWebSocketHandler` field declared: CONFIRMED
- `public boolean isStalled(WebSocketSession session)` in handler: CONFIRMED
- `./gradlew compileJava compileTestJava` exits 0: CONFIRMED
- `./gradlew test` 655 tests, 0 failures: CONFIRMED

Commits:
- Task 1: `10fa760` — feat(17-08): refactor TickBroadcaster to non-blocking OutboundSender enqueue
