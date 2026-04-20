---
phase: 15-protocol-transport-overhaul
plan: 10
subsystem: observability
tags: [metrics, micrometer, actuator, observability, bytes-saved-deferred]
dependency_graph:
  requires: [15-08]
  provides:
    - "WebSocketMetrics bean (two live Micrometer meters: paralife.ws.active.sessions, paralife.ws.tick.frame.bytes)"
    - "TickBroadcaster → metrics.recordFrameSize wiring on every encoded frame"
    - "SessionRegistry → metrics.setActiveSessions wiring on register/unregister"
  affects:
    - "src/main/java/com/paralife/metrics/"
    - "src/main/java/com/paralife/websocket/SessionRegistry.java"
    - "src/main/java/com/paralife/websocket/TickBroadcaster.java"
tech_stack:
  added:
    - "Micrometer Gauge + DistributionSummary (via starter-actuator, already on classpath)"
  patterns:
    - "Constructor-injected MeterRegistry builds meters in @Component ctor"
    - "AtomicInteger backing store decouples Gauge from downstream bean construction order"
key_files:
  created:
    - "src/main/java/com/paralife/metrics/WebSocketMetrics.java"
    - "src/test/java/com/paralife/websocket/MetricsEndpointIntegrationTest.java"
    - "src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java"
  modified:
    - "src/main/java/com/paralife/websocket/SessionRegistry.java"
    - "src/main/java/com/paralife/websocket/TickBroadcaster.java"
decisions:
  - "bytes.saved deferred per SCHEMA §13 — Jetty 12 lacks stable per-frame post-deflate hook; fabricated 0.6× estimator rejected per cross-AI review consensus #4"
  - "Metric names dot-separated lowercase (RESEARCH Pitfall 7), superseding hyphenated D-38 form"
  - "Gauge wraps AtomicInteger, not SessionRegistry::size directly — avoids bean construction order coupling"
  - "SessionRegistry.unregister(String sessionId) signature preserved to avoid churn in WorldWebSocketHandler callers; plan's WebSocketSession-typed example was illustrative"
metrics:
  duration: "~7 minutes"
  completed_date: "2026-04-20"
  tasks_completed: 4
  files_created: 3
  files_modified: 2
  tests_added: 5
requirements: [R29]
---

# Phase 15 Plan 10: WebSocketMetrics Summary

Expose two live Micrometer meters for WebSocket observability; defer the fabricated bytes-saved meter.

## One-liner

`WebSocketMetrics` bean registers `paralife.ws.active.sessions` (Gauge over `AtomicInteger`) and `paralife.ws.tick.frame.bytes` (`DistributionSummary` of raw pre-deflate UTF-8 lengths with p50/p95/p99), wired into `SessionRegistry` register/unregister and `TickBroadcaster.onTick` so both meters reflect live state.

## Deliverables

### 1. Two live meter names + what drives them end-to-end

| Meter | Type | Driver | Updated when |
|-------|------|--------|--------------|
| `paralife.ws.active.sessions` | Gauge | `SessionRegistry.register` / `unregister` → `metrics.setActiveSessions(sessions.size())` | Every session open and close |
| `paralife.ws.tick.frame.bytes` | DistributionSummary | `TickBroadcaster.onTick` → `metrics.recordFrameSize(encoded.getBytes(UTF_8).length)` | Right after every successful `session.sendMessage(new TextMessage(encoded))` inside the `synchronized (session)` block |

Both meters are discoverable at `/actuator/metrics/<name>` (HTTP 200 with a Micrometer `measurements` payload). Actuator exposure allowlist already included `metrics` in `application.yml` — no YAML change required.

### 2. Deferred meter verified absent

`paralife.ws.bytes.saved` is NOT registered. The integration test `bytesSavedMetricIsAbsent` asserts `/actuator/metrics/paralife.ws.bytes.saved` returns **HTTP 404**, proving the SCHEMA §13 deferral is honoured. The javadoc on `WebSocketMetrics` documents the Jetty 12 rationale without ever naming the deferred meter in the file (verification grep requires zero occurrences of the literal name in `WebSocketMetrics.java`).

### 3. BotRegistry setup for wiring test

`WebSocketMetricsWiringTest.broadcasterTickDrivesTickFrameBytesDistribution` drives the DistributionSummary via `TickBroadcaster.onTick` for a mock-session bot.

Setup needed:
- `sessionRegistry.register(mockSession)` — mocked `WebSocketSession` with `getId`/`isOpen`/`getAttributes` stubbed.
- `botRegistry.register(sid, "wiring-entity-1", new Position(1, 1))` — the existing three-arg signature needs **no** live `Particle` in `WorldGrid`. `TickBroadcaster.buildTickFrame` already handles `null` occupants by emitting a zero-energy alive-check frame (see the existing `if (occupant instanceof Particle p) { ... } else { energy = 0; ... }` branch). The mock session's `sendMessage` is a no-op; `metrics.recordFrameSize` is called unconditionally right after.
- `@AfterEach botRegistry.clear()` to prevent cross-test pollution (Spring context is shared across the two wiring tests).

## Tasks Completed

| # | Task | Commit |
|---|------|--------|
| 1 | `WebSocketMetrics` bean (two live meters, no estimator) | `cef0ea6` |
| 2 | Wire into `SessionRegistry` + `TickBroadcaster` | `bf85361` |
| 3 | `MetricsEndpointIntegrationTest` — actuator reachability (3 tests) | `1bd2040` |
| 4 | `WebSocketMetricsWiringTest` — end-to-end wiring (2 tests) | `2fa3371` |

## Deviations from Plan

### [Rule 3 — Blocking] Preserved existing `SessionRegistry.unregister(String sessionId)` signature

- **Found during:** Task 2
- **Issue:** Plan 2's example code shows `unregister(WebSocketSession session)`, but the existing signature in `SessionRegistry.java` is `unregister(String sessionId)` and is already called from `WorldWebSocketHandler` at two call sites (`sessionRegistry.unregister(session.getId())`).
- **Fix:** Kept the String-ID signature unchanged; only added `metrics.setActiveSessions(sessions.size())` inside both `register` and `unregister`. No caller churn, behaviour identical, metric wiring correct. Plan's example is illustrative (its preamble says "current register/unregister signatures" should be read first).
- **Files modified:** `src/main/java/com/paralife/websocket/SessionRegistry.java`
- **Commit:** `bf85361`

### [Rule 3 — Blocking] Test scoping — explicit `@AfterEach botRegistry.clear()`

- **Found during:** Task 4
- **Issue:** The two wiring tests share a `@SpringBootTest` context. Leaking bots from one test into the other would make `broadcasterTickDrivesTickFrameBytesDistribution` flaky if run in arbitrary order.
- **Fix:** Added `@AfterEach cleanupBots()` calling the existing `BotRegistry.clear()` method.
- **Files modified:** `src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java`
- **Commit:** `2fa3371`

### Javadoc rephrased to avoid the deferred meter literal

- **Found during:** Task 1 verify gate
- **Issue:** First draft of `WebSocketMetrics` javadoc contained the literal string `paralife.ws.bytes.saved` in the Deferred section. The plan's verify gate (`! grep -q "paralife.ws.bytes.saved" ...`) and acceptance criterion both require the literal to be absent from the file.
- **Fix:** Rephrased the javadoc to describe the deferred meter as "a third meter for post-deflate bytes-saved" without naming it. `15-SCHEMA.md §13` retains the full deferral reference. File-level grep now returns 0.
- **Commit:** `cef0ea6` (single commit — rephrased before the first commit landed).

## Verification Gate Results

| Gate | Result |
|------|--------|
| `grep -c "paralife\.ws\.active\.sessions" WebSocketMetrics.java` | **1** (expected 1) |
| `grep -c "paralife\.ws\.tick\.frame\.bytes" WebSocketMetrics.java` | **1** (expected 1) |
| `grep -c "paralife\.ws\.bytes\.saved" WebSocketMetrics.java` | **0** (expected 0) |
| No hyphens in metric names | PASS |
| `MetricsEndpointIntegrationTest` (3 tests) | PASS |
| `WebSocketMetricsWiringTest` (2 tests) | PASS |
| Full test suite: 509 tests, 13 failed, 3 skipped | Matches baseline — 13 pre-existing Jetty websocket-upgrade failures unchanged, **no new regressions** |

## Threat Surface

No new threat flags. STRIDE T-15-MX-01 (Information Disclosure on `/actuator/metrics`) remains *accepted* — the allowlist is `health,info,metrics` (no `env`/`configprops`/`beans`), and the two registered meters emit only aggregate numbers (a scalar session count, a byte-length distribution). No entity ids, session ids, or bot secrets leave the server.

## Self-Check: PASSED

- `src/main/java/com/paralife/metrics/WebSocketMetrics.java` — FOUND
- `src/test/java/com/paralife/websocket/MetricsEndpointIntegrationTest.java` — FOUND
- `src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java` — FOUND
- Commit `cef0ea6` — FOUND
- Commit `bf85361` — FOUND
- Commit `1bd2040` — FOUND
- Commit `2fa3371` — FOUND
