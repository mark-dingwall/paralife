---
phase: 17
plan: "06"
subsystem: admission
tags: [outbound, virtual-threads, backpressure, queue, stalled, metrics]
dependency_graph:
  requires:
    - AdmissionConfig (Plan 01)
    - RejectionToken (Plan 01)
    - Frame / PerceptionCodec (Plan 02 codec layer)
    - AdmissionMetrics (Plan 03 — created here as Rule 3 blocking fix)
  provides:
    - OutboundSender @Component with attachSession/detachSession/offer/setOverflowCallback/queueDepth/attachedCount
    - Per-session ArrayBlockingQueue<Frame> + named VT "ws-sender-<id>" (D-10)
    - Overflow-fire-once guard via AtomicBoolean overflowFiredFlags (codex HIGH)
    - Bounded detach race fix via t.join(100ms) (claude HIGH)
    - Frame-size metric at drain loop (codex MEDIUM — AdmissionMetrics.M_FRAME_SIZE)
    - AdmissionMetrics bean (Rule 3 prerequisite, full Plan 03 spec shape)
  affects:
    - Plan 07 (WorldWebSocketHandler integration — wires attachSession/detachSession/setOverflowCallback)
    - Plan 08 (TickBroadcaster must NOT add duplicate recordFrameSize call)
tech_stack:
  added: []
  patterns:
    - "Thread.ofVirtual().name(...).start() — named virtual thread per session (D-10)"
    - "ArrayBlockingQueue<Frame> — bounded outbound queue, explicit backpressure signal"
    - "AtomicBoolean compareAndSet(false, true) — fire-once guard (codex HIGH)"
    - "t.join(Duration.ofMillis(100)) — bounded detach race (claude HIGH)"
    - "ConcurrentHashMap per-session state maps (queues, senderThreads, overflowFiredFlags)"
key_files:
  created:
    - src/main/java/com/paralife/admission/OutboundSender.java
    - src/main/java/com/paralife/admission/AdmissionMetrics.java
    - src/test/java/com/paralife/admission/OutboundSenderTest.java
  modified: []
decisions:
  - "Manual awaitUntil polling helper used instead of awaitility — no new test dependency added (verified by git diff build.gradle.kts returning empty)"
  - "Overflow callback fires with (sessionId, queue.size()) at fire time — queueCapacity not captured at attach time, matches actual depth at overflow instant"
  - "sendPending volatile boolean added to FakeSession to signal VT has entered sendMessage before latch — makes offerReturnsFalseWhenQueueFull race-free"
  - "AdmissionMetrics created as Rule 3 blocking fix — full Plan 03 spec shape; Plan 03 executor must NOT recreate this file"
metrics:
  duration: "~5 minutes"
  completed: "2026-04-27"
  tasks_completed: 1
  tasks_total: 1
  files_created: 3
  files_modified: 0
requirements_satisfied: [SCALE-02]
---

# Phase 17 Plan 06: OutboundSender VT-Per-Session Sender Summary

**One-liner:** Per-session virtual-thread outbound sender with overflow-fire-once guard (`AtomicBoolean compareAndSet`), bounded detach race (`t.join(100ms)`), and frame-size metric in drain loop — isolates slow clients from the tick thread (D-10, D-11).

## What Was Built

### OutboundSender (primary deliverable)

`src/main/java/com/paralife/admission/OutboundSender.java` — `@Component` bean that pairs each WebSocket session with:

- **`ArrayBlockingQueue<Frame>(queueCapacity)`** — bounded outbound queue; `offer()` is non-blocking.
- **Named virtual thread `ws-sender-<sessionId>`** — drains the queue via `queue.take()` + `session.sendMessage(new TextMessage(encoded))`. Single writer per session — no synchronization needed on the send path.
- **`AtomicBoolean overflowFiredFlags`** — per-session fire-once guard. `compareAndSet(false, true)` in `offer()` ensures the overflow callback is invoked at most once per attach lifecycle. Repeated overflow calls after saturation are silently dropped. Re-attach resets the flag.
- **`t.join(Duration.ofMillis(100))`** in `detachSession` — interrupts the sender VT then waits up to 100ms. Bounds the window during which a Plan 07 fallback `synchronized(session)` send could race the dying VT (claude HIGH review).
- **`metrics.recordFrameSize(byteLen)`** in `drainLoop` after encode — single measurement point for `paralife.outbound.frame.size.bytes`. Plan 08 (`TickBroadcaster` refactor) must NOT add a duplicate `recordFrameSize` call.
- **IOException-tolerant drain loop** — logs warn and continues on `IOException` or `RuntimeException`; `InterruptedException` from `take()` → re-sets interrupt flag and exits cleanly.

Public API (Plan 07 integration surface):
- `attachSession(WebSocketSession, int queueCapacity)` — idempotent; re-attach detaches existing sender first
- `detachSession(String sessionId)` — interrupt + bounded join
- `offer(String sessionId, Frame)` — non-blocking enqueue; returns false on no-such-session or full
- `setOverflowCallback(BiConsumer<String, Integer>)` — wired by Plan 07 at startup
- `queueDepth(String sessionId)` — diagnostic / gauge
- `attachedCount()` — diagnostic / gauge

### AdmissionMetrics (Rule 3 blocking fix)

`src/main/java/com/paralife/admission/AdmissionMetrics.java` created to unblock compilation. Full Plan 03 spec shape: tagged rejection counter (`paralife.admission.rejected{reason=...}`), ingress-overwrites counter, 4 D-18 gauges (`active.entities`, `maintenance`, `tick.health.work-time-ms`, `backpressure.stalled.sessions`), and frame-size `DistributionSummary` (`paralife.outbound.frame.size.bytes`).

**Plan 03 executor:** `AdmissionMetrics.java` already exists with the correct spec shape. Do NOT recreate it. Do create `AdmissionResult.java`, `AdmissionGate.java`, and their tests as planned.

### OutboundSenderTest (9 tests)

| Test | What it verifies |
|------|-----------------|
| `attachAndOfferEnqueuesAndDelivers` | Happy path — frame delivered, encoded with `r|C` prefix |
| `offerToUnknownSessionReturnsFalse` | No queue → false |
| `offerReturnsFalseWhenQueueFull` | queue=1, VT in-flight; next offer fills queue; overflow → false |
| `overflowCallbackFiresExactlyOncePerAttach` | Callback fires once; repeated overflow skipped |
| `reattachResetsOverflowFiredFlag` | Detach+re-attach resets flag; callback fires again |
| `detachJoinsVTWithinTimeout` | detach returns < 200ms; queueDepth = -1 after |
| `drainLoopSurvivesIOException` | First send throws; loop continues; second send captured |
| `frameSizeMetricRecordedAfterEncode` | 2 frames → summary count=2, totalAmount > 0 |
| `reattachAfterDetachIsIdempotent` | Second attach works; frame delivered to new session |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] AdmissionMetrics missing — Plan 03 not yet executed**
- **Found during:** Pre-implementation compile check
- **Issue:** `OutboundSenderTest` references `AdmissionMetrics.M_FRAME_SIZE` and `new AdmissionMetrics(meterReg)`. Plan 03 produces this class but was not yet executed (also wave 2). Without it, compilation fails.
- **Fix:** Created `AdmissionMetrics.java` with the full Plan 03 spec shape (tagged rejection counter, ingress-overwrites counter, 4 gauges, frame-size DistributionSummary). All method signatures match what Plan 03 specifies.
- **Files modified:** `src/main/java/com/paralife/admission/AdmissionMetrics.java` (created)
- **Commit:** `05b4247` (RED gate commit)

**2. [Rule 1 - Bug] offerReturnsFalseWhenQueueFull test had unreliable timing**
- **Found during:** Task 1 GREEN phase
- **Issue:** Original test used capacity=2 and assumed VT dequeues first frame before offers 2 and 3 are called — timing-dependent, fails on fast machines. `assertThat(a).isTrue()` failed: VT had not yet taken frame 1, so queue was already full at offer 2.
- **Fix:** Added `volatile boolean sendPending` to `FakeSession` — set true at entry to `sendMessage` before latch await, allowing `awaitUntil(() -> blocking.sendPending, 2000)` to synchronize. Changed capacity to 1 to match the reliable fire-once pattern used elsewhere in the test class.
- **Files modified:** `src/test/java/com/paralife/admission/OutboundSenderTest.java`
- **Commit:** `8886f8f` (GREEN gate commit)

## Overflow-Callback Contract for Plan 07

`markStalled` (Plan 07) is invoked at most once per stall transition via `OutboundSender.setOverflowCallback`. The fire-once guard (`AtomicBoolean.compareAndSet(false, true)`) prevents:
- Duplicate `STALLED` token issuance
- Log spam from repeated overflow calls
- Repeated FSM-transition attempts on an already-stalled session

Plan 07 must call `setOverflowCallback` exactly once at startup (e.g. in `@PostConstruct` or constructor injection). The callback receives `(sessionId, currentQueueDepth)` — depth is the queue size at overflow time, not the configured capacity.

## Detach Join Timeout Note for Plan 07

`detachSession` joins the sender VT for up to 100ms. Plan 07's fallback `synchronized(session)` error-frame send (if any) is safe to call after `detachSession` returns — the dying VT has either exited or will exit within 100ms. Combined with the `session.isOpen()` guard in the drain loop, the race window is bounded.

## Frame-Size Metric Note for Plan 08

`OutboundSender.drainLoop` calls `metrics.recordFrameSize(byteLen)` after encoding each frame. This is the **sole** measurement point for `paralife.outbound.frame.size.bytes`. Plan 08 (`TickBroadcaster` refactor) must NOT add a duplicate `recordFrameSize` call — doing so would double-count every frame.

## Known Stubs

None — `OutboundSender` is fully wired to `AdmissionMetrics` and `PerceptionCodec`. No mock data, no placeholder text. Plan 07 wires `OutboundSender` into `WorldWebSocketHandler` and `TickBroadcaster`.

## Threat Surface

No new network endpoints or auth paths. Threat mitigations per plan threat model:

| Threat | Mitigation |
|--------|-----------|
| T-17-03: Slow-consumer DoS | Per-session bounded queue — tick thread never blocks on slow socket |
| T-17-callback-flood: Overflow callback spam | AtomicBoolean fire-once guard — callback invoked at most once per attach lifecycle |
| T-17-detach-race: Concurrent writer during detach | t.join(100ms) bounds post-detach window; session.isOpen() guard in drain loop |
| T-17-misc: Concurrent writer on sendMessage | Single VT per session — only the drain loop calls sendMessage during normal operation |

## Self-Check: PASSED

Files created:
- FOUND: `src/main/java/com/paralife/admission/OutboundSender.java`
- FOUND: `src/main/java/com/paralife/admission/AdmissionMetrics.java`
- FOUND: `src/test/java/com/paralife/admission/OutboundSenderTest.java`

Acceptance criteria:
- `@Component` present: 1 occurrence
- `Thread.ofVirtual()`: 1 occurrence
- `name("ws-sender-"`: 1 occurrence
- `compareAndSet(false, true)`: 1 occurrence
- `t.join(`: 1 occurrence
- `metrics.recordFrameSize(`: 1 occurrence
- `Outbound concurrency rationale`: 1 occurrence
- `git diff build.gradle.kts`: empty (no new dependency)
- 9 tests, 0 failures: CONFIRMED

Commits:
- RED gate: `05b4247` — test(17-06): add failing OutboundSenderTest + AdmissionMetrics prerequisite
- GREEN gate: `8886f8f` — feat(17-06): implement OutboundSender
