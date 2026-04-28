---
phase: 18-external-load-harness-harness-identity
reviewed: 2026-04-28T00:00:00Z
depth: standard
files_reviewed: 55
files_reviewed_list:
  - build.gradle.kts
  - CLAUDE.md
  - src/main/java/com/paralife/admission/AdmissionBeansConfig.java
  - src/main/java/com/paralife/admission/AdmissionConfig.java
  - src/main/java/com/paralife/admission/AdmissionGate.java
  - src/main/java/com/paralife/admission/AdmissionMetrics.java
  - src/main/java/com/paralife/admission/AttributionSanitizer.java
  - src/main/java/com/paralife/admission/AttributionTagger.java
  - src/main/java/com/paralife/bot/BotClient.java
  - src/main/java/com/paralife/bot/BotClientOptions.java
  - src/main/java/com/paralife/bot/BotFactory.java
  - src/main/java/com/paralife/bot/BotFleet.java
  - src/main/java/com/paralife/bot/BotIdentity.java
  - src/main/java/com/paralife/bot/BotLauncher.java
  - src/main/java/com/paralife/bot/BotRunner.java
  - src/main/java/com/paralife/bot/RampUpSpec.java
  - src/main/java/com/paralife/bot/SpeciesMix.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/harness/LoadHarness.java
  - src/main/java/com/paralife/harness/LoadHarnessOptions.java
  - src/main/java/com/paralife/harness/RampUpConverter.java
  - src/main/java/com/paralife/harness/ReportSnapshot.java
  - src/main/java/com/paralife/harness/ReportWriter.java
  - src/main/java/com/paralife/harness/SpeciesMixConverter.java
  - src/main/java/com/paralife/websocket/SessionRegistry.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/main/resources/application.yml
  - src/test/java/com/paralife/admission/AdmissionGateTest.java
  - src/test/java/com/paralife/admission/AdmissionLogMarkerTest.java
  - src/test/java/com/paralife/admission/AdmissionMetricsLifecycleTest.java
  - src/test/java/com/paralife/admission/AdmissionMetricsTest.java
  - src/test/java/com/paralife/admission/AttributionRebindTest.java
  - src/test/java/com/paralife/admission/AttributionSanitizerTest.java
  - src/test/java/com/paralife/admission/AttributionTaggerTest.java
  - src/test/java/com/paralife/admission/AttributionTagTest.java
  - src/test/java/com/paralife/admission/CardinalityCapTest.java
  - src/test/java/com/paralife/admission/OutboundSenderTest.java
  - src/test/java/com/paralife/admission/ResumeTokenRegistryTest.java
  - src/test/java/com/paralife/admission/TickHealthMonitorTest.java
  - src/test/java/com/paralife/bot/BotClientHandshakeHeaderTest.java
  - src/test/java/com/paralife/bot/BotFactoryTest.java
  - src/test/java/com/paralife/bot/BotFleetTest.java
  - src/test/java/com/paralife/bot/BotIdentityTest.java
  - src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java
  - src/test/java/com/paralife/bot/BotRunnerRegressionTest.java
  - src/test/java/com/paralife/bot/RampUpSpecTest.java
  - src/test/java/com/paralife/bot/SpeciesMixTest.java
  - src/test/java/com/paralife/engine/LoadTest.java
  - src/test/java/com/paralife/engine/TickHealthMonitorScalarTest.java
  - src/test/java/com/paralife/harness/LoadHarnessIntegrationTest.java
  - src/test/java/com/paralife/harness/LoadHarnessOptionsTest.java
  - src/test/java/com/paralife/harness/ReportWriterTest.java
  - src/test/java/com/paralife/websocket/HarnessLogMarkerTest.java
  - src/test/java/com/paralife/websocket/WorldWebSocketHandlerHandshakeHeaderTest.java
findings:
  critical: 0
  warning: 4
  info: 4
  total: 8
status: issues_found
---

# Phase 18: Code Review Report

**Reviewed:** 2026-04-28
**Depth:** standard
**Files Reviewed:** 55
**Status:** issues_found

## Summary

Phase 18 ships external load harness infrastructure (`LoadHarness`, `BotFleet`, `BotFactory`), harness identity carriage via WebSocket upgrade headers (`BotIdentity`, `AttributionTagger`, `AttributionSanitizer`), per-bucket admission metrics, and cardinality-capped Micrometer tags. The multi-round review process is visible in the code — earlier MEDIUM/HIGH issues have been addressed. The overall quality is high; the issues found are genuine logic gaps and a couple of latent misalignments, none critical.

## Warnings

### WR-01: `AdmissionGate.evaluate` increments slot counter before respawn-cap check

**File:** `src/main/java/com/paralife/admission/AdmissionGate.java:141-158`

**Issue:** The CAS loop at Guard 5 increments `reservedSlots` for any non-respawn request, including one where Guard 6 (the per-session respawn-cap check) will subsequently reject the same request. However Guard 6 only fires for `isRespawn=true`, so a fresh registration (`isRespawn=false`) at this code path is always Allowed regardless of Guard 6. The code comment confirms Guard 6 only applies when `isRespawn=true`. On examination, fresh registrations skip Guard 6 (`if (req.isRespawn() && ...)`), so the slot increment is correct for `Allow`. **But:** a respawn request (`isRespawn=true`) skips Guard 5 entirely (the `if (!req.isRespawn())` block is not entered), so no slot is consumed — but the test in `AdmissionGateTest.respawnCapDoesNotApplyOnFreshRegistration` passes `isRespawn=false, respawnCount=999` expecting Allow, which is also fine. On deeper inspection: if the intent is that a respawn reuses an existing slot (already counted at initial registration), the current flow is correct. No slot double-count exists. However the `isRespawn=true` path entirely bypasses the global-cap guard, so a session that has lost its slot (e.g. after `releaseSlot()` was called on the original session's death) could successfully respawn past the global cap, because no new slot is reserved. This is an edge case where grid death + respawn can briefly exceed `cap` by however many simultaneous respawn-cap-passing requests arrive.

**Fix:** Document explicitly whether a respawn is expected to reserve a new slot or reuse the old one. If respawns should be capped, remove the `!req.isRespawn()` guard on the CAS block (or apply the global cap to respawns too, with a note that the slot was released on death). If respawns intentionally bypass the global cap (re-entering via a pre-held slot), add a comment explaining the slot lifecycle for the respawn path.

---

### WR-02: `LoadHarness.runInternal` — reporter VT may write a counter after `fleet.shutdown()` returns

**File:** `src/main/java/com/paralife/harness/LoadHarness.java:206-282`

**Issue:** The reporter virtual thread (`reporterVT`) is interrupted in the `finally` block (line 281: `reporterVT.interrupt()`), but interrupt delivery is asynchronous. The flow is:

1. `fleet.shutdown()` is called (line 268) — bots are disconnected.
2. `writeFinalReport(...)` is called (line 267) — final counters written.
3. `finally` block runs: shutdown hook removed, then `reporterVT.interrupt()`.

Between step 2 and step 3, the reporter VT may wake from its `Thread.sleep(reportIntervalSeconds * 1000L)` if the sleep completes naturally, and may call `writeCounters(...)` again AFTER the final report has already been written. In overwrite mode this overwrites the final report with a stale `exitReason=null` snapshot. The window is narrow (only if `reportIntervalSeconds` elapses between `writeFinalReport` and `reporterVT.interrupt()`), but the exit-reason field would be lost.

**Fix:** Set `exitReason` before calling `writeFinalReport`, or have the reporter check `exitReason.get() != null` before writing (which it does at line 214: `if (exitReason.get() != null) return`). The existing check at line 214 returns early if `exitReason` is already set. So the actual fix requires ensuring `exitReason` is set before `writeFinalReport` is called. Looking at line 266: `String reason = exitReason.get() != null ? exitReason.get() : "duration-reached"` — the local variable `reason` is computed, but the atomic `exitReason` ref may still be null if the duration branch ran. Call `exitReason.compareAndSet(null, reason)` before `writeFinalReport` to ensure the reporter VT's null-check in line 214 can observe the set value:

```java
String reason = exitReason.get() != null ? exitReason.get() : "duration-reached";
exitReason.compareAndSet(null, reason);   // ensures reporter VT's null-check sees it
writeFinalReport(writer, fleet, startedAt, reason);
```

---

### WR-03: `BotFleet.launch` — `liveCount` increment races with `onClose` decrement

**File:** `src/main/java/com/paralife/bot/BotFleet.java:102-124`

**Issue:** The `onClose` hook is registered at line 94 (`bot.onClose(() -> liveCount.decrementAndGet())`), but the corresponding increment (`liveCount.incrementAndGet()`) at line 107 happens after `awaitRegistered` returns inside the launch VT. If the bot registers successfully and then closes (e.g. due to a rapid server-side 429 after the grace window) before line 107 executes, the sequence becomes: `onClose` fires (decrement: 0 → -1), then line 107 fires (increment: -1 → 0). The counter goes transiently negative. `highWater` would capture 0 rather than 1.

The javadoc on `currentRegistered()` already acknowledges it is "best-effort for the ramp window only", and the server-side gauge is authoritative. However, a negative `liveCount` is incorrect and could mislead operators reading the field. Additionally `connectFailuresTotal` would not be incremented in this case (the bot registered successfully), so the report would show 0 failures but also show a peak of 0.

**Fix:** Increment `liveCount` before registering the `onClose` hook, or atomically increment-then-register. Alternatively, document the race window more prominently and clamp `liveCount` to ≥ 0 in `currentRegistered()`:

```java
public int currentRegistered() {
    return Math.max(0, liveCount.get());
}
```

---

### WR-04: `SpeciesMix.pickFor` balanced-mode detection is fragile

**File:** `src/main/java/com/paralife/bot/SpeciesMix.java:58-65`

**Issue:** Balanced mode is detected by comparing `cFrac` and `mFrac` to `1.0/3` within a tolerance of `0.001`. This works for `SpeciesMix.balanced()` (which constructs exactly `1.0/3, 1.0/3, 1.0/3`) but silently treats weighted mixes of `(0.333, 0.333, 0.334)` — a valid near-balanced weighted spec — as balanced and applies round-robin instead of the position-based partitioning the caller intended. A user who passes `--species-mix 0.333:0.333:0.334` via the CLI would get round-robin behavior instead of weighted partitioning.

**Fix:** Either (a) use an explicit boolean flag to record balanced mode at construction time, or (b) check `sFrac` too in the balanced detection:

```java
// Option b: check all three fractions
if (Math.abs(cFrac - 1.0/3) < 0.001
        && Math.abs(mFrac - 1.0/3) < 0.001
        && Math.abs(sFrac - 1.0/3) < 0.001) {
    // balanced round-robin
}
```

This at least requires all three fractions to match, reducing the false-positive surface from near-balanced weighted specs.

---

## Info

### IN-01: `LoadHarnessOptions` record is unused

**File:** `src/main/java/com/paralife/harness/LoadHarnessOptions.java`

**Issue:** `LoadHarnessOptions` is defined as a value-object carrier for CLI options, but `LoadHarness` uses Picocli's field injection directly and never constructs a `LoadHarnessOptions` instance. The record is not referenced by any production code path.

**Fix:** Either use it (refactor `LoadHarness.validateAndDefault()` to return a `LoadHarnessOptions` for passing to `runInternal`) or delete it. If it's retained as a planned refactor target, add a `// TODO: wire into runInternal in follow-up` comment.

---

### IN-02: `HarnessLogMarkerTest` uses `Thread.sleep` for synchronisation

**File:** `src/test/java/com/paralife/websocket/HarnessLogMarkerTest.java:78,90`

**Issue:** Lines 78 (`Thread.sleep(100)`) and 90 (`Thread.sleep(300)`) are used to wait for `afterConnectionEstablished` and `afterConnectionClosed` to fire. These are timing-based synchronisation points that can produce flaky results under CI load. The existing pattern elsewhere in the test suite uses `Awaitility.await()`.

**Fix:** Replace the fixed sleeps with Awaitility assertions on the log appender:

```java
Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(m -> m.contains("HARNESS connected")));
```

---

### IN-03: `BotRunnerOperatorTagTest` assertion is effectively vacuous

**File:** `src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java:48-59`

**Issue:** The Awaitility block at line 48 asserts `assertThat(rc).isEqualTo(0)` — which was already asserted at line 44, outside the await block. The inner assertion in the Awaitility block does not assert anything about the `sessionRegistry` that would fail if BotRunner passed the wrong identity. By the time the Awaitility block polls, the `duration=3s` run has already completed and all bots have disconnected, so `sessionRegistry.getActiveSessions()` will be empty. The filter count is never asserted — the `ops` variable is computed but not checked.

**Fix:** Assert `rc == 0` outside the await block (which already happens), and either remove the Awaitility block (it provides no coverage) or change its assertion to check something meaningful from the completed run, such as a Micrometer gauge:

```java
// After BotRunner.run() returns, verify the server saw operator-attributed sessions.
// This must be verified via a metric that persists after disconnect, not active sessions.
Gauge g = meterRegistry.find("paralife.admission.active.entities")
    .tags("source", "operator")
    .gauge();
// The gauge value will be 0 (bots disconnected), but the gauge must exist,
// proving operator-tagged sessions were registered.
assertThat(g).isNotNull();
```

---

### IN-04: `BotClient.sendFrame` is `synchronized` on `this`, which may contend with respawn flow

**File:** `src/main/java/com/paralife/bot/BotClient.java:334`

**Issue:** `sendFrame` is `synchronized` on the `BotClient` instance. `handleDeath` (line 479) schedules `sendFrame(new Frame.RegisterFrame(species))` via `CompletableFuture.delayedExecutor`, which runs on a thread-pool thread. If `disconnect()` is called concurrently (also `synchronized` on `this` indirectly through `sendFrame`), the delayed re-register frame can block waiting to acquire the lock held by the disconnect path, then send a register frame to a closed session (which is silently swallowed by the null/isOpen check). This is mostly harmless due to the null check, but the `synchronized` on the outer instance (`BotClient`) is a wider lock scope than necessary — the Jetty `Session` already requires `Callback.NOOP`-based send which is thread-safe at the Jetty level. The project convention uses `synchronized(session)` at the server side (documented in CLAUDE.md), but client-side uses `synchronized(this)` on the outer `BotClient`, which is a wider scope that would block e.g. `awaitRegistered` if it needed the lock.

**Fix:** Consider locking on a dedicated `sendLock` object rather than `this`, to avoid unintentionally blocking unrelated synchronized methods:

```java
private final Object sendLock = new Object();

private void sendFrame(Frame f) {
    Session s = this.session;
    if (s == null || !s.isOpen()) return;
    synchronized (sendLock) {
        try {
            s.sendText(PerceptionCodec.encode(f), Callback.NOOP);
        } catch (Exception e) {
            log.warn("Failed to send frame: {}", e.getMessage());
        }
    }
}
```

---

_Reviewed: 2026-04-28_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
