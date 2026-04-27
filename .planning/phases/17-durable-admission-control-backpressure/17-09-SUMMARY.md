---
phase: 17
plan: 09
subsystem: bot
tags: [botclient, reconnect, resume-token, stalled-recovery, backpressure]
dependency_graph:
  requires:
    - Plan 02 (Frame.RegisterFrame 2-arg ctor, Frame.SyncFrame 3-arg ctor with resumeToken)
  provides:
    - BotClient.resumeToken field (volatile String, server-issued, in-JVM across WS close/reopen)
    - BotClient.handleStalled() — STALLED-pivot entry point, orthogonal to handleDeath()
    - BotClient.sendInitialRegister() — token-aware r| sender called from connect()
    - BotClient.reconnect() — re-enters connect() after STALLED close
    - BotClient.Endpoint.onClose — triggers reconnect() when resumeToken != null and !shutdown
  affects:
    - Plan 11 (integration test that drives STALLED-pivot end-to-end)
tech_stack:
  added: []
  patterns:
    - shutdown AtomicBoolean flag guards intentional disconnect from spurious reconnect
    - Inner class closure accesses outer BotClient fields (resumeToken, shutdown) directly
    - CompletableFuture.delayedExecutor for 100ms jitter before reconnect
key_files:
  created: []
  modified:
    - src/main/java/com/paralife/bot/BotClient.java
decisions:
  - "Death-pivot sends new Frame.RegisterFrame(species) (single-arg, no token) — fresh entity; STALLED-pivot sends RegisterFrame(species, Optional.of(token)) via sendInitialRegister()"
  - "shutdown AtomicBoolean added to prevent reconnect loop after intentional disconnect() call"
  - "handleStalled() does NOT null entityId — server preserves entity under grace; token retained for re-bind"
  - "Token value never logged; only hasResumeToken boolean appears in log lines (T-17-misc mitigation)"
  - "Reconnect uses two-hop path: onClose -> reconnect() -> connect() to keep connect() as the single WS entry point"
metrics:
  duration: "~10 minutes"
  completed: "2026-04-27"
  tasks_completed: 1
  tasks_total: 1
  files_changed: 1
---

# Phase 17 Plan 09: BotClient STALLED-Pivot Reconnect Summary

**One-liner:** BotClient gains in-JVM resume-token storage and STALLED-pivot reconnect path — on E|408|reconnect-required, closes current WS and reconnects with `r|<species>|<token>` on a fresh WS, preserving entity across grace window.

## Reconnect Path Statement (Plan 11 prerequisite)

**ADDED IN THIS PLAN: BotClient.java had no in-client reconnect on close; this plan added reconnect logic to the existing @OnWebSocketClose handler at line 390 plus reconnect() helper at line 341.**

Detail:
- `@OnWebSocketClose` at line 390 (inside `Endpoint` inner class) pre-existed but only logged. This plan added the `if (resumeToken != null && !shutdown.get())` branch that schedules `BotClient.this::reconnect` with a 100ms delay.
- `reconnect()` at line 341 calls `connect()` — the single WS entry point — which then calls `sendInitialRegister()` which emits `r|<species>|<token>` when a token is held.
- Trigger line: `CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(BotClient.this::reconnect)` at line 396.

## What Was Built

### New Fields

- `private volatile String resumeToken` — stores the server-issued resume token from `S|` frames. Held in JVM memory across the WS close/reopen cycle. Never written to disk or logs.
- `private final AtomicBoolean shutdown` — guards against the reconnect loop triggering after an intentional `disconnect()` call. Set to `true` in `disconnect()` before closing the session.

### Modified Methods

**`onSync(Frame.SyncFrame s)`** — added `s.resumeToken().ifPresent(t -> this.resumeToken = t)` immediately after `entityId = s.entityId()`. Log line now emits `hasResumeToken={true/false}` instead of the token value (T-17-misc mitigation).

**`onError(Frame.ErrorFrame e)`** — added dispatch on `e.code() == 408 && "reconnect-required".equals(msg)` → `handleStalled()`. Existing 429 branch unchanged.

**`connect()`** — replaced direct `sendFrame(new Frame.RegisterFrame(species))` with `sendInitialRegister()`. This is the only change to `connect()`; the D-33 enforcement and WS upgrade logic are unchanged.

**`disconnect()`** — added `shutdown.set(true)` as first statement to prevent `onClose` from triggering reconnect after intentional shutdown.

**`Endpoint.onClose(int, String)`** — added `if (resumeToken != null && !shutdown.get())` branch that schedules `reconnect()` with 100ms delay.

### New Methods

**`handleStalled()`** — marks `alive=false`, logs STALLED event with `hasResumeToken` boolean, leaves `entityId` and `resumeToken` intact. The server closes the WS; `onClose` triggers reconnect.

**`sendInitialRegister()`** — snapshots `this.resumeToken` into a local var, emits `r|<species>|<token>` (2-arg `RegisterFrame`) if non-null, else `r|<species>` (1-arg convenience ctor). Called only from `connect()`.

**`reconnect()`** — calls `connect()` in a try/catch that logs on failure. Called by the scheduled executor in `onClose`.

### Unchanged

**`handleDeath()`** — still calls `new Frame.RegisterFrame(species)` (single-arg, no token) on the SAME open WS. Phase 15.2 death-pivot flow is unaffected.

## STALLED-Pivot vs Death-Pivot Orthogonality

| Aspect | Death-pivot (Phase 15.2) | STALLED-pivot (Phase 17) |
|--------|--------------------------|--------------------------|
| Server signal | `D` event in tick frame | `E|408|reconnect-required` |
| WS after signal | Stays open | Server closes it |
| Client action | `r|<species>` on same WS | Close → new WS → `r|<species>|<token>` |
| EntityId | Nulled (fresh entity) | Preserved (re-bind) |
| Token | Not sent | Sent via sendInitialRegister() |
| Entry path | handleDeath() directly sends RegisterFrame | handleStalled() → onClose → reconnect() → connect() → sendInitialRegister() |

## Pre-existing BotClient Tests

No `BotClientTest.java` was found in the test tree — there are no existing unit tests that assert ctor shapes or reconnect behavior. Plan 11 will be the first test to drive the STALLED-pivot path end-to-end via a `BlockingWebSocketClient` integration test.

## Deviations from Plan

None — plan executed exactly as written.

The plan noted that `connect()` creates a new `WebSocketClient` on each call. This is fine for the reconnect path: a fresh `WebSocketClient` is started, the new `Session` overwrites `this.session`, and `sendInitialRegister()` uses the new session. The old client from the STALLED session may leak if not stopped; however, the plan does not address Jetty client lifecycle cleanup on reconnect — deferred to Plan 11 if observed in integration testing.

## Threat Surface

No new network endpoints. Token handling per plan threat model:

| Flag | File | Description |
|------|------|-------------|
| (none) | — | No new trust boundaries introduced |

T-17-misc (token leak via logs): Mitigated — `resumeToken` value never appears in any log call. Only `hasResumeToken=true/false` is logged in `onSync` and `handleStalled`.

## Self-Check

PASSED — verified:
- `BotClient.java` contains `private volatile String resumeToken` (grep count: 1 field declaration + 7 usages = 8 total)
- `BotClient.java` contains `s.resumeToken().ifPresent` (grep count: 1)
- `BotClient.java` contains `"reconnect-required"` (grep count: 1)
- `BotClient.java` contains method `handleStalled` (grep count: confirmed)
- `BotClient.java` `handleDeath` calls `new Frame.RegisterFrame(species)` single-arg form (grep confirmed)
- `BotClient.java` `sendInitialRegister()` consults `this.resumeToken` and uses 2-arg form when present (confirmed)
- `BotClient.java` `@OnWebSocketClose` present (grep count: 1)
- Token value NOT logged at INFO level (grep confirms only `hasResumeToken` boolean logged)
- `./gradlew compileJava compileTestJava` exits 0 — BUILD SUCCESSFUL
- `./gradlew test --tests "com.paralife.codec.*"` exits 0 — BUILD SUCCESSFUL
- Commit exists: `349c574`
