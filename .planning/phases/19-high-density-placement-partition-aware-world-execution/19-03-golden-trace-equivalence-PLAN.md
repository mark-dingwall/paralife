---
phase: 19
plan: 03
type: execute
wave: 3
depends_on: [19-01, 19-02]
files_modified:
  - src/main/java/com/paralife/websocket/OutboundSender.java
  - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
  - src/test/java/com/paralife/engine/GoldenTraceCapture.java
autonomous: true
requirements:
  - SCALE-07
tags: [equivalence, golden-trace, sha-256, determinism, java, spring-boot]

must_haves:
  truths:
    - "A seeded scenario can be driven twice in the same JVM and the per-session digest map of all outbound WebSocket frames is byte-identical between runs (proves the test harness itself is deterministic before any refactor lands)."
    - "GoldenTraceEquivalenceTest passes BEFORE Plan 04 (entity-list iteration) is merged — the test serves as the oracle for that refactor."
    - "Test captures outbound frame bytes via a test-only seam on OutboundSender, not by mocking individual handlers — captures the actual wire output observed by the WS layer."
    - "Digest is captured PER SESSION (Map<String, String> sessionId → SHA-256 hex), removing cross-session emit-order non-determinism (REVIEWS H4 / Codex H / OpenCode H — virtual-thread scheduling jitter cannot affect per-session digests)."
    - "EXPECTED_DIGESTS map is captured against the post-Plan-02 / pre-Plan-04 codebase and pinned as a `static final Map<String, String>` constant. Plan 04 must keep this map green."
    - "Test asserts emitCount > 0 AND digest map non-empty AND no per-session digest equals the SHA-256 of empty input — guards against the vacuous-baseline failure mode (REVIEWS H4 / claude L3 — zero frames captured would otherwise pass silently)."
    - "TickEvent dispatch drives the full @Order chain via `applicationEventPublisher.publishEvent(new TickEvent(t))`, NOT a partial-pipeline test seam (REVIEWS H5)."
    - "The scenario uses real WS sessions (mock-but-registered into SessionRegistry + OutboundSender attachment) so TickBroadcaster.onTick does NOT skip every iteration via the open-session check (REVIEWS H4)."
    - "LiveEntityRegistry is in place (Plan 02 dependency satisfied) so that the test runs through the same code paths as Plan 04 will modify."
  artifacts:
    - path: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      provides: "@SpringBootTest dual-run determinism gate. Drives a 50-tick scenario with N bots at fixed seed via applicationEventPublisher.publishEvent(new TickEvent(t)) (REVIEWS H5), captures all outbound frame bytes per session, computes SHA-256 digest map, then resets and runs again. Asserts digestMapA == digestMapB AND digestMapA == EXPECTED_DIGESTS (a private static final map pinned during this plan, BEFORE Plan 04 lands). Asserts emitCount > 0 and no per-session hex equals the empty-digest constant (REVIEWS H4 / L3). Fails if Plan 04 refactor changes any observable wire output."
      min_lines: 160
    - path: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      provides: "Test-only frame-byte accumulator. Maintains a Map<String, MessageDigest> keyed by sessionId; each outbound frame from session S is hashed into digest[S] in send order (per-session order is intrinsically deterministic — single VT drains each session's queue). Exposes Map<String, String> hex digests + total emitCount + sessionsSeen."
      min_lines: 80
  key_links:
    - from: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      to: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      via: "@Autowired wiring; test asserts hashMapA == hashMapB across two driven runs AND hashMapA == EXPECTED_DIGESTS against a pinned baseline; test also asserts emitCount > 0 and no per-session digest is the empty-digest constant"
      pattern: "GoldenTraceCapture"
    - from: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      to: src/main/java/com/paralife/websocket/OutboundSender.java
      via: "Test-only listener hook on OutboundSender, called inside synchronized(session) immediately after session.sendMessage returns successfully"
      pattern: "OutboundSender"
---

<objective>
Build the **D-10 semantic-equivalence gate** — a `GoldenTraceEquivalenceTest` that captures all outbound WebSocket frame bytes during a fixed-seed scenario, computes a **per-session SHA-256 digest map**, asserts byte-equality across two consecutive runs, **and** asserts the map equals a pinned baseline captured against the post-Plan-02 / pre-Plan-04 codebase. This test must exist and pass **before** Plan 04 lands. Once Plan 04 refactors tick-handler iteration to use `LiveEntityRegistry.snapshot()`, this test is the oracle that proves the refactor changed nothing observable.

Per RESEARCH.md Open Question 3 (RESOLVED): full equivalence (frames + metric counters) is aspirational; perception-frame digest is the **minimum-viable contract**. Metric-counter equivalence is deferred to Phase 21 benchmark gate. This plan ships the minimum-viable contract.

**REVIEWS revisions applied (H4 / H5 / L3 / Codex H / OpenCode H):**

- **H4 (claude/codex/opencode):** Vacuous-baseline failure mode closed. The pre-revision design used direct `botRegistry.register(...)` with synthetic session ids and no real WS plumbing — `TickBroadcaster.onTick` would skip every iteration via the `sessionRegistry.getSession(sessionId) == null` check, producing SHA-256 of empty input. Now the test:
  1. Uses real WS sessions registered into `SessionRegistry` + attached to `OutboundSender` so the broadcast loop emits frames.
  2. Asserts `emitCount > 0` AND `digestMap.size() > 0` BEFORE comparing.
  3. Asserts no per-session hex equals `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (SHA-256 of empty), guarding against per-session vacuous baselines.
- **H5 (claude):** Full @Order chain driven via `applicationEventPublisher.publishEvent(new TickEvent(t))` — confirmed via TickEngine.java line 112 that this is the production tick driver. The test publishes the same event so SimulationEngine + EnvironmentEngine + ActionResolver + EnvPostActionReconciler + TickBroadcaster all fire in @Order chain. Documented inline in the driver method.
- **Cross-session emit order (REVIEWS Codex H / OpenCode H):** moved to per-session digest map. OutboundSender uses VT-per-session queues; intra-session order is deterministic (single drain VT per session, FIFO queue). Cross-session emit order across two JVM runs is NOT guaranteed even at the same seed because virtual-thread scheduling can interleave drain loops differently. Per-session digests sidestep this entirely.
- **Codex MEDIUM (listener placement):** listener fires inside `synchronized(session)` AFTER `session.sendMessage` returns. Listener work is bounded (digest update — sub-µs; never calls back into the sender). The synchronized-session-monitor contract (CLAUDE.md §"Outbound concurrency") explicitly already does work inside the monitor (encoding); the listener is the same shape and lighter weight.

**Critical sequencing — the EXPECTED_DIGESTS pin:**

The naive design (assert only `mapA == mapB`) is insufficient: both maps are computed in the same JVM run after Plan 04 lands, so the test would pass even if Plan 04 changed observable output (both runs would simply produce the same wrong map). The fix is to **capture the baseline map against the pre-Plan-04 codebase, hard-code it as a `static final Map<String, String>` constant, and assert subsequent runs equal that map.**

Workflow within this plan (executed in order, in this plan's wave):
  1. Land Task 1 (FrameEmitListener seam + capture helper) — purely additive instrumentation.
  2. Land Task 2 step (a): write the test scaffold WITHOUT the EXPECTED_DIGESTS pin yet. Use a placeholder map.
  3. Run the test once on the post-Plan-02 codebase. The test prints each session's digest as `BASELINE: <sessionId> -> <hex>`.
  4. Pin the captured map as `private static final Map<String, String> EXPECTED_DIGESTS = Map.of(...)` in the test source.
  5. Re-run the test — it must now pass `mapA == mapB == EXPECTED_DIGESTS`.
  6. Commit. Plan 04's acceptance includes that this same test continues to pass — same EXPECTED_DIGESTS.

Purpose: Without this gate, Plan 04 cannot prove D-10. The test serves as the merge-gate during execution and as the regression sentinel for any future tick-handler change.
Output: One new production-test seam on `OutboundSender` (test-only frame-emit listener), one test capture helper (per-session digest map), one @SpringBootTest equivalence gate with a pinned baseline map AND empty-digest guards AND explicit TickEvent driver.

**Wave assignment rationale:** This plan sits in Wave 3 — depends on Plan 02 (LiveEntityRegistry must exist so the seeded test exercises the same DI graph Plan 04 will modify). Plan 04 (Wave 4) blocks on this plan because the test must be passing with EXPECTED_DIGESTS pinned before the refactor lands.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@CLAUDE.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-VALIDATION.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md
@src/main/java/com/paralife/websocket/OutboundSender.java
@src/main/java/com/paralife/websocket/TickBroadcaster.java
@src/main/java/com/paralife/websocket/SessionRegistry.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/LiveEntityRegistry.java
@src/main/java/com/paralife/engine/TickEngine.java
@src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java
@src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java

<interfaces>
<!-- Existing OutboundSender surface this plan extends with a test-only listener seam. -->

From src/main/java/com/paralife/websocket/OutboundSender.java (existing):
```java
// Phase 17 D-10: VT-per-session bounded queue. Tick handlers enqueue via offer(sessionId, frame).
// The drain VT does the actual sendMessage; that is the byte-emission point.
public boolean offer(String sessionId, Frame frame);
public int queueDepth(String sessionId);
public void attachSession(String sessionId, WebSocketSession session); // re-grep for actual name
// (re-read source to confirm exact method signatures before patching)
```

NEW test seam (production code; minimal scope):
```java
// Functional listener interface — invoked at the point of actual sendMessage,
// inside the synchronized(session) block, AFTER the call returns successfully.
@FunctionalInterface
public interface FrameEmitListener {
    void onEmit(String sessionId, byte[] frameBytes);
}

// Public method on OutboundSender:
public void setFrameEmitListener(FrameEmitListener listener);   // null clears
```

From src/main/java/com/paralife/engine/TickEngine.java (line 112 — production tick driver, CONFIRMED via grep):
```java
// var event = new TickEvent(tickNumber);
// eventPublisher.publishEvent(event);
//
// To drive the full @Order chain in a test (REVIEWS H5), use the same shape:
//   applicationEventPublisher.publishEvent(new TickEvent(t));
// This fires every @EventListener on TickEvent in @Order — SimulationEngine(10),
// EnvironmentEngine(14), ActionResolver(20), EnvPostActionReconciler(25),
// PerceptionBroadcaster(50)/TickBroadcaster.onTick(50), TickBroadcaster(100).
```

From src/main/java/com/paralife/engine/LiveEntityRegistry.java (provided by Plan 02):
```java
public List<EntityEntry> snapshot();
public void clearForTest();
public int size();
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Add FrameEmitListener test seam on OutboundSender + GoldenTraceCapture per-session digest helper</name>
  <files>src/main/java/com/paralife/websocket/OutboundSender.java, src/test/java/com/paralife/engine/GoldenTraceCapture.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/OutboundSender.java (entire file — find the exact line where the drain VT calls `session.sendMessage(...)` inside `synchronized(session)`; that is the byte-emission point)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 200–230 — confirm `outboundSender.offer(sessionId, frame)` is the only enqueue call; if there are out-of-band sendMessage paths via WorldWebSocketHandler.sendOutOfBand, the listener also needs to fire there per CLAUDE.md "Synchronized-session-monitor contract")
    - CLAUDE.md §"Outbound concurrency (Phase 17, D-10)" — invariants the listener must respect
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples — Golden-Trace Test Pattern; SHA-256 digest sketch)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H4 / Codex H / OpenCode H — per-session digest mandate)
  </read_first>
  <behavior>
    - listenerSeesAllOutboundBytes: register a listener; enqueue 3 frames to session "s1"; drain runs; listener.onEmit invoked exactly 3 times with sessionId="s1" and the correct (sessionId, bytes) tuples in send-order.
    - listenerCapturesPerSessionOrder: enqueue frames to "s1" and "s2" interleaved; assert per-session emit order matches per-session enqueue order (intra-session FIFO is the only ordering guarantee — cross-session order is intentionally not asserted, REVIEWS Codex H / OpenCode H).
    - listenerNullClearsHook: setFrameEmitListener(null) afterwards; subsequent offers do not invoke any listener.
    - listenerExceptionDoesNotKillDrainLoop: a listener that throws RuntimeException must not break the drain VT; subsequent frames continue to send. Wrap the listener invocation in try/catch with a `log.warn` on failure.
  </behavior>
  <action>
1. **Modify** `src/main/java/com/paralife/websocket/OutboundSender.java`:

   (a) Add the functional interface (top of file, package-private or public):
   ```java
   @FunctionalInterface
   public interface FrameEmitListener {
       /** Invoked AFTER session.sendMessage succeeds; bytes are the actual wire output. Test-only. */
       void onEmit(String sessionId, byte[] frameBytes);
   }
   ```

   (b) Add a `private volatile FrameEmitListener frameEmitListener;` field and a setter:
   ```java
   /**
    * Test-only seam: register a listener invoked AFTER each successful
    * session.sendMessage. Captures the actual wire output. Production code
    * never sets this — it is null in production runs.
    *
    * <p>Listener fires inside {@code synchronized(session)} so it observes
    * the exact byte boundaries the WS observes. Listener implementations
    * MUST be sub-microsecond and MUST NOT call back into the sender.
    */
   public void setFrameEmitListener(FrameEmitListener listener) {
       this.frameEmitListener = listener;
   }
   ```

   (c) In the drain loop (find the line where `session.sendMessage(message)` returns inside `synchronized(session)`), add right after the successful send:
   ```java
   FrameEmitListener listener = this.frameEmitListener;
   if (listener != null) {
       try {
           listener.onEmit(sessionId, encodedBytes);
       } catch (Throwable t) {
           log.warn("FrameEmitListener threw, ignoring: {}", t.toString());
       }
   }
   ```
   The exact variable names (`sessionId`, `encodedBytes`, `message`) depend on the existing code — re-read OutboundSender.java to find the right names. The listener invocation MUST stay inside `synchronized(session)` so it observes the same byte boundaries the real WS observes (REVIEWS Codex MEDIUM accepted — listener is sub-µs and never calls back).

2. Create `src/test/java/com/paralife/engine/GoldenTraceCapture.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.OutboundSender;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 19 SCALE-07 D-10: test-only PER-SESSION digest accumulator for the
 * GoldenTraceEquivalenceTest. Maintains a Map&lt;String, MessageDigest&gt; keyed
 * by sessionId. Each outbound frame from session S is hashed into digest[S]
 * in send order. Per-session order is intrinsically deterministic because
 * OutboundSender uses one drain VT per session over a FIFO bounded queue.
 *
 * <p>REVIEWS H4 / Codex HIGH / OpenCode HIGH: cross-session emit order across
 * two JVM runs is NOT deterministic (virtual-thread scheduling can interleave
 * drain loops differently). Per-session digests sidestep this entirely.
 *
 * <p>Empty-digest guard (REVIEWS H4 / L3): the GoldenTraceCapture caller MUST
 * assert {@link #emitCount()} &gt; 0 AND no per-session hex equals
 * {@link #EMPTY_SHA256_HEX} before relying on the digest map for equivalence.
 */
public class GoldenTraceCapture {

    /** SHA-256 of the empty byte array — the vacuous-baseline indicator (REVIEWS L3). */
    public static final String EMPTY_SHA256_HEX =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final Map<String, MessageDigest> digestsBySession = new HashMap<>();
    private final AtomicLong emitCount = new AtomicLong();
    private final List<String> sessionsSeen = new ArrayList<>();

    public synchronized void onEmit(String sessionId, byte[] frameBytes) {
        MessageDigest d = digestsBySession.computeIfAbsent(sessionId, k -> {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        });
        d.update(frameBytes);
        emitCount.incrementAndGet();
        if (!sessionsSeen.contains(sessionId)) sessionsSeen.add(sessionId);
    }

    /**
     * Finalise and return per-session digests as a TreeMap (sortedBy sessionId
     * for stable test output). Each call resets the underlying digests so call
     * AT MOST ONCE per run; use {@link #digestsAsHexMap()} to capture for pinning.
     */
    public synchronized Map<String, byte[]> currentDigests() {
        Map<String, byte[]> out = new TreeMap<>();
        digestsBySession.forEach((s, d) -> out.put(s, d.digest()));
        return out;
    }

    /**
     * Convenience: per-session lowercase hex map. Use this to print and pin
     * EXPECTED_DIGESTS. TreeMap so iteration is deterministic.
     */
    public synchronized Map<String, String> digestsAsHexMap() {
        Map<String, String> out = new TreeMap<>();
        digestsBySession.forEach((s, d) -> {
            byte[] bytes = d.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            out.put(s, sb.toString());
        });
        return out;
    }

    public synchronized void reset() {
        digestsBySession.clear();
        emitCount.set(0);
        sessionsSeen.clear();
    }

    public long emitCount() { return emitCount.get(); }
    public List<String> sessionsSeen() { return new ArrayList<>(sessionsSeen); }
}
```

3. Quick smoke test the seam by running the full regression suite — no behaviour change is expected; `OutboundSender` listener defaults to null in production.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "interface FrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` == 1
    - `grep -c "setFrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` >= 2 (declaration + invocation site)
    - `grep -c "frameEmitListener.onEmit\|listener.onEmit" src/main/java/com/paralife/websocket/OutboundSender.java` >= 1
    - `grep -c "private volatile FrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` == 1
    - File `src/test/java/com/paralife/engine/GoldenTraceCapture.java` exists.
    - `grep -c "MessageDigest.getInstance(\"SHA-256\")" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "Map<String, MessageDigest>\\|digestsBySession" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1 (REVIEWS H4 — per-session map structure)
    - `grep -c "digestsAsHexMap" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "EMPTY_SHA256_HEX\\|e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1 (REVIEWS L3 / H4 — empty-digest guard constant)
    - `./gradlew test` exits 0 (regression — listener=null in production runs, no observable change)
  </acceptance_criteria>
  <done>FrameEmitListener seam wired into OutboundSender at the post-sendMessage point inside synchronized(session); production listener is null; GoldenTraceCapture computes per-session SHA-256 digests (REVIEWS H4 / Codex H / OpenCode H) and exposes both byte[] map and hex String map forms; empty-digest constant exposed for the test's vacuous-baseline guard; full regression suite green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Author GoldenTraceEquivalenceTest — dual-run per-session digest map equality + pinned EXPECTED_DIGESTS + emitCount/empty-digest guards + explicit TickEvent driver</name>
  <files>src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java</files>
  <read_first>
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template; copy @SpringBootTest setup, @TestPropertySource, resetAll pattern, dual-run drive structure)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-01-placement-index-PLAN.md (Plan 01 — defines the `WorldWebSocketHandler.attemptPlacementForTest(entityId, type, energy)` seam used by `driveScenario()`; also defines `EligibleCellIndex.rebuildForTest()` used in `resetAll()`. W3 — read this so the contract is explicit, no codebase scavenger hunt)
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (production source where Plan 01 Task 2 adds `attemptPlacementForTest`; confirm the seam method exists and matches the signature this test calls)
    - src/main/java/com/paralife/engine/TickEngine.java (line 112 confirmed — `eventPublisher.publishEvent(event)` is the canonical driver; this test uses `applicationEventPublisher.publishEvent(new TickEvent(t))` per REVIEWS H5)
    - src/main/java/com/paralife/websocket/OutboundSender.java (the FrameEmitListener seam from Task 1; the `attachSession` method or equivalent that registers a session for drain)
    - src/main/java/com/paralife/websocket/SessionRegistry.java (confirm method to register a `WebSocketSession` so `TickBroadcaster.onTick` does not skip via the open-session check — REVIEWS H4)
    - src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java (real-WS-clients pattern; if mock-but-registered sessions prove insufficient, switch to this pattern)
    - src/test/java/com/paralife/engine/GoldenTraceCapture.java (helper from Task 1; use `digestsAsHexMap()` for the baseline pin print)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Plan 02 dependency — confirms `clearForTest()` and `snapshot()` are available)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples — Golden-Trace Test Pattern; §Open Questions Q3 RESOLVED — minimum viable contract)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 374–388 — golden-trace test analog)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H4 / H5 / L3)
  </read_first>
  <behavior>
    - byteIdenticalOutputAcrossTwoRuns: drive a fixed-seed 50-tick scenario, capture digestsA; reset all state; drive the same scenario again, capture digestsB; assert mapA equals mapB AND mapA equals EXPECTED_DIGESTS.
    - emitCountIsConsistent: capture.emitCount() is identical across the two runs (sanity check before digest comparison).
    - emitCountIsNonZero: capture.emitCount() > 0 — REVIEWS H4 vacuous-baseline guard.
    - sessionsSeenIsNonEmpty: capture.sessionsSeen() is non-empty AND matches the expected number of bots — REVIEWS H4 secondary guard.
    - noPerSessionDigestIsEmptyDigest: for every (sessionId, hex) in the digest map, hex != GoldenTraceCapture.EMPTY_SHA256_HEX — REVIEWS L3 / H4 — guards against per-session vacuous baseline.
    - testIsSelfConsistentBeforeAnyRefactor: this test passes on the codebase as it stands today (Plan 02 wired LiveEntityRegistry but did not change tick handlers). If it doesn't pass on baseline, the harness has non-determinism that must be fixed BEFORE Plan 04 runs.
    - baselineDigestsArePinned: a `private static final Map<String, String> EXPECTED_DIGESTS = Map.of(...)` constant exists with real SHA-256 hex values (not placeholders); test asserts run's hex map equals this constant.
    - tickPipelineIsDrivenByTickEventPublisher: the test driver publishes `new TickEvent(t)` via `ApplicationEventPublisher.publishEvent` — verifies the FULL @Order chain fires (REVIEWS H5).
  </behavior>
  <action>
**Two-pass workflow (executed in order):**

**Pass 1 — write the scaffold without the pin, capture the baseline digest map:**

1. Create `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.OutboundSender;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.TickEvent;
import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 19 SCALE-07 D-10 semantic-equivalence gate.
 *
 * <p>Drives a fixed-seed scenario twice in the same JVM, captures all outbound
 * frame bytes per session via the OutboundSender FrameEmitListener seam,
 * computes per-session SHA-256 digests, asserts byte-equality across runs AND
 * byte-equality against a pinned baseline map captured against the post-Plan-02
 * / pre-Plan-04 codebase.
 *
 * <p>REVIEWS revisions (H4 / H5 / L3 / Codex H / OpenCode H):
 * <ul>
 *   <li>Per-session digest map (not a single global digest) — virtual-thread
 *       scheduling cannot affect per-session order.</li>
 *   <li>Mock-but-registered WS sessions wired into SessionRegistry +
 *       OutboundSender so TickBroadcaster.onTick does not skip via
 *       open-session check.</li>
 *   <li>Explicit `applicationEventPublisher.publishEvent(new TickEvent(t))`
 *       drives the full @Order chain — same path TickEngine uses in production.</li>
 *   <li>emitCount > 0 AND no per-session digest equals SHA-256("") — guards
 *       against vacuous-baseline silent passes.</li>
 * </ul>
 *
 * <p>Plan 04 (entity-list iteration refactor) must keep this test green —
 * the EXPECTED_DIGESTS map is the operational form of the D-10 promise.
 *
 * <p>Per RESEARCH.md Open Question 3 (RESOLVED), this is the minimum-viable
 * contract: outbound frame bytes only. Metric counter equivalence is deferred
 * to Phase 21 benchmark gate.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false",
    "paralife.world.width=32",
    "paralife.world.height=32"
})
class GoldenTraceEquivalenceTest {

    /**
     * Pinned baseline per-session digest map. Captured during Plan 03 execution
     * against the post-Plan-02 / pre-Plan-04 codebase. Plan 04's refactor must
     * produce the same map — that is the D-10 promise made operational.
     *
     * <p>To re-pin (e.g. after an intentional scenario change): comment out
     * the EXPECTED_DIGESTS assertion, run the test, copy the printed
     * "BASELINE: <sessionId> -> <hex>" lines into this Map.of(...), restore
     * the assertion.
     */
    private static final Map<String, String> EXPECTED_DIGESTS = Map.of(
        "REPLACE_ME_AFTER_FIRST_RUN", "PLACEHOLDER"
    );

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired ApplicationEventPublisher applicationEventPublisher;

    private GoldenTraceCapture capture;

    @BeforeEach
    void setUp() {
        capture = new GoldenTraceCapture();
        outboundSender.setFrameEmitListener(capture::onEmit);
    }

    @AfterEach
    void tearDown() {
        outboundSender.setFrameEmitListener(null);
    }

    @Test
    void byteIdenticalOutputAcrossTwoRuns() throws Exception {
        // ---- Run 1 ----
        resetAll();
        driveScenario();
        long emitsA = capture.emitCount();
        Map<String, String> mapA = capture.digestsAsHexMap();

        // Print for first-time baseline capture. Operator copies these lines
        // into EXPECTED_DIGESTS Map.of(...) on first run.
        mapA.forEach((s, h) -> System.out.println("BASELINE: " + s + " -> " + h));

        // ---- Vacuous-baseline guards (REVIEWS H4 / L3) ----
        assertThat(emitsA).as("REVIEWS H4 — emit count must be > 0; otherwise digest is vacuous").isGreaterThan(0);
        assertThat(mapA).as("REVIEWS H4 — digest map must contain at least one session").isNotEmpty();
        mapA.forEach((s, h) -> assertThat(h)
            .as("REVIEWS L3 — per-session digest must NOT be SHA-256 of empty input for session " + s)
            .isNotEqualTo(GoldenTraceCapture.EMPTY_SHA256_HEX));

        // ---- Run 2 ----
        capture.reset();
        resetAll();
        driveScenario();
        long emitsB = capture.emitCount();
        Map<String, String> mapB = capture.digestsAsHexMap();

        assertThat(emitsB).as("emit count stable across runs").isEqualTo(emitsA);
        assertThat(mapB).as("D-10 byte-identical outbound frames per session (run B vs run A)").isEqualTo(mapA);
        assertThat(mapA).as("D-10 EXPECTED_DIGESTS baseline pinned in Plan 03; Plan 04 must not change this").isEqualTo(EXPECTED_DIGESTS);
    }

    private void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        handler.resetSeed();
        // EligibleCellIndex, SessionRegistry, OutboundSender per-session queues
        // also need reset where they hold per-run state — re-read each bean
        // and add the reset call here. SessionRegistry: `clearForTest()` if
        // it exists, else iterate sessionsSeen and detach.
    }

    /**
     * Drive a deterministic 10-bot 50-tick scenario through the full @Order
     * pipeline. Uses mock-but-registered WS sessions so TickBroadcaster.onTick
     * does NOT skip via the open-session check (REVIEWS H4).
     *
     * Tick driver: `applicationEventPublisher.publishEvent(new TickEvent(t))`
     * fires every @Order handler — same path as TickEngine.java line 112
     * uses in production (REVIEWS H5).
     */
    private void driveScenario() throws Exception {
        // Step 1: register N bots through the real handleRegister path
        // (uses Plan 01's EligibleCellIndex.sample for placement; no fixed
        // hardcoded positions). Drives via the package-private test seam from
        // Plan 01 task 2(f): handler.attemptPlacementForTest(entityId, type, energy).
        for (int i = 0; i < 10; i++) {
            String entityId = "trace-bot-" + i;
            String sessionId = "trace-sess-" + i;
            Optional<Position> pos = handler.attemptPlacementForTest(
                entityId, Entity.ParticleType.values()[i % 3], 100);
            assertThat(pos).isPresent();
            // Wire a mock WS session into SessionRegistry + OutboundSender so
            // TickBroadcaster.onTick treats this as an active session.
            WebSocketSession mockSession = mock(WebSocketSession.class);
            when(mockSession.isOpen()).thenReturn(true);
            when(mockSession.getId()).thenReturn(sessionId);
            sessionRegistry.register(sessionId, mockSession);   // re-grep for actual register method name
            outboundSender.attachSession(sessionId, mockSession); // re-grep for actual attach method name
            botRegistry.register(sessionId, entityId, pos.get());
            liveEntityRegistry.register(entityId, pos.get());
        }

        // Step 2: drive 50 ticks via the production tick-event publisher.
        // REVIEWS H5: this fires the full @Order chain — SimulationEngine(10),
        // EnvironmentEngine(14), ActionResolver(20), EnvPostActionReconciler(25),
        // PerceptionBroadcaster(50), TickBroadcaster(100). NOT a partial seam.
        for (int t = 0; t < 50; t++) {
            applicationEventPublisher.publishEvent(new TickEvent(t));
            // The drain VTs are async; give them a chance to drain before the
            // next tick fires. If the OutboundSender exposes a synchronous
            // "flush" or "awaitDrain" method, prefer it over a sleep.
            // Otherwise: drain by polling queueDepth(sessionId) per session
            // until all are zero, with a short timeout. Hardcoded sleeps will
            // make the test flaky — avoid them.
            awaitAllSessionQueuesDrained();
        }
    }

    private void awaitAllSessionQueuesDrained() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L; // 2s timeout
        while (System.nanoTime() < deadline) {
            boolean allDrained = true;
            for (String sid : capture.sessionsSeen()) {
                if (outboundSender.queueDepth(sid) > 0) { allDrained = false; break; }
            }
            if (allDrained) return;
            Thread.sleep(1);
        }
        throw new IllegalStateException("OutboundSender queues did not drain in 2s — drain VT may be blocked");
    }
}
```

**Pass 1 first run:** with `EXPECTED_DIGESTS = Map.of("REPLACE_ME...", "PLACEHOLDER")`, the test will fail the EXPECTED_DIGESTS assertion. That is expected. Read the test output, locate the lines `BASELINE: <sessionId> -> <hex>`, copy the entries.

**Pass 2 — pin the baseline digest map:**

2. Edit `EXPECTED_DIGESTS` in the test source to the captured map (one entry per `BASELINE:` print line):

```java
private static final Map<String, String> EXPECTED_DIGESTS = Map.of(
    "trace-sess-0", "<64-char hex>",
    "trace-sess-1", "<64-char hex>",
    "trace-sess-2", "<64-char hex>",
    "trace-sess-3", "<64-char hex>",
    "trace-sess-4", "<64-char hex>",
    "trace-sess-5", "<64-char hex>",
    "trace-sess-6", "<64-char hex>",
    "trace-sess-7", "<64-char hex>",
    "trace-sess-8", "<64-char hex>",
    "trace-sess-9", "<64-char hex>"
);
```

If more than 10 entries are needed (e.g. session id is `WebSocketSession.getId()` for a real session, not the synthetic `trace-sess-N`), use `Map.ofEntries(Map.entry(...), ...)`.

3. Re-run the test. All assertions must now pass:
   - `emitsA > 0`
   - per-session hex != EMPTY_SHA256_HEX for every session
   - `mapB == mapA`
   - `mapA == EXPECTED_DIGESTS`

4. Commit. Plan 04 acceptance includes that this test continues to pass with the same EXPECTED_DIGESTS.

**Important caveats for the executor:**

(a) `WorldGrid.clear()`, `BotRegistry.clear()`, `LiveEntityRegistry.clearForTest()`, `WorldWebSocketHandler.resetSeed()` — confirm each exists; add test-only methods if missing. `EligibleCellIndex.rebuildForTest()` was added in Plan 01.

(b) `SessionRegistry.register(...)` and `OutboundSender.attachSession(...)` — re-grep for actual method names; the names above are illustrative. If only the production WS handshake path can register a session, fall back to real WS test clients per `HundredBotIntegrationTest`.

(c) The scenario uses `handler.attemptPlacementForTest` from Plan 01 to place bots through the real handleRegister sub-step (sample → trySetEntity → notifyChanged). This exercises Plan 01's EligibleCellIndex AND Plan 02's LiveEntityRegistry hooks (the registration `botRegistry.register` + `liveEntityRegistry.register` calls) — i.e. the full DI graph that Plan 04 will modify.

(d) **Cross-session digest order is not asserted** (REVIEWS Codex H / OpenCode H closed): per-session digests are insensitive to virtual-thread scheduling.

(e) **The EXPECTED_DIGESTS must be captured against the post-Plan-02 / pre-Plan-04 codebase.** If by accident this plan executes after Plan 04 has already been merged, the digest is contaminated and the D-10 promise is unenforceable. The wave structure (this plan = Wave 3, Plan 04 = Wave 4) prevents that.

(f) **This test must pass on the post-Plan-02 baseline**. If `mapA != mapB`, the test infrastructure has a non-determinism bug — fix it BEFORE pinning EXPECTED_DIGESTS. Common culprits:
   - `botRegistry.getAllBots()` iteration order from `ConcurrentHashMap.values()` in `TickBroadcaster.onTick` pre-Plan-04 (the current code path). Per-session digests sidestep this — frames sent TO a given session are byte-identical regardless of which session was iterated first. If per-session digests still flake on the baseline, the bug is downstream and must be diagnosed before pinning.
   - System.nanoTime / clock reads in any frame payload (should not be present, but check if digest fails).
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"</automated>
    <!-- B3 commentary: a RED test on Pass 1 (with EXPECTED_DIGESTS still containing the
         "REPLACE_ME_AFTER_FIRST_RUN" placeholder) IS EXPECTED — that run prints the
         "BASELINE: <sessionId> -> <hex>" lines used to fill the map. Do NOT declare
         task failure on Pass 1 red. Proceed to Pass 2 (pin EXPECTED_DIGESTS to the
         captured hex map, re-run). The task is complete only when Pass 2 is GREEN.
         If Pass 2 is also red, that is a real failure — investigate. -->
  </verify>
  <acceptance_criteria>
    - **B3 — Pass-2 done state (BOTH conditions required):** (a) `grep -c "REPLACE_ME_AFTER_FIRST_RUN" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0 AND `grep -cE '"[0-9a-f]{64}"' src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (real 64-char lowercase-hex SHA-256 digests pinned per session, no placeholder strings remain) AND (b) the gate command `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 against the filled `EXPECTED_DIGESTS` map (Pass 2 green). A Pass-1 red on the unfilled placeholder is part of the workflow and is NOT task failure; only a Pass-2 red is.
    - File `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` exists.
    - `grep -c "@SpringBootTest" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "byteIdenticalOutputAcrossTwoRuns" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -cE "private static final Map<String, String> EXPECTED_DIGESTS" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1 (REVIEWS H4 — per-session map)
    - `grep -c "REPLACE_ME_AFTER_FIRST_RUN" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0 (REVIEWS L3 — no placeholder; real hex pinned)
    - `grep -cE "[\"][0-9a-f]{64}[\"]" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (at least one real 64-char lowercase-hex SHA-256 digest pinned in EXPECTED_DIGESTS)
    - `grep -c "isEqualTo(EXPECTED_DIGESTS)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
    - `grep -c "isGreaterThan(0)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS H4 — emitCount guard)
    - `grep -c "EMPTY_SHA256_HEX\\|isNotEqualTo(GoldenTraceCapture.EMPTY" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS L3 — vacuous-baseline guard)
    - `grep -c "applicationEventPublisher.publishEvent(new TickEvent" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS H5 — explicit full-@Order driver)
    - `grep -c "outboundSender.setFrameEmitListener" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (set + clear in tearDown)
    - `grep -c "sessionRegistry.register\\|outboundSender.attachSession" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS H4 — real-or-mock-but-registered WS sessions)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (passes on the baseline with EXPECTED_DIGESTS pinned — this is the precondition for Plan 04)
    - `./gradlew test` exits 0 (full regression remains green)
  </acceptance_criteria>
  <done>GoldenTraceEquivalenceTest exists and passes on the post-Plan-02 baseline. The dual-run per-session digest assertion is in place AND the EXPECTED_DIGESTS map is pinned (real 64-char hex constants per session, not the placeholder). emitCount > 0 + no-empty-digest + tickEvent-driven-full-pipeline guards all asserted (REVIEWS H4 / H5 / L3 closed). Plan 04 cannot land without keeping this test green against the same EXPECTED_DIGESTS — D-10 enforced.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Test → production seam | `OutboundSender.setFrameEmitListener` is a test-only seam; production listener is null. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-08 | Tampering | Test seam left wired in production by mistake | mitigate | Listener defaults to null; setter is the only way to wire one. Acceptance criterion `grep` confirms no production code calls setFrameEmitListener. |
| T-19-09 | Repudiation | Plan 04 changes observable output undetected | mitigate | This test IS the mitigation — EXPECTED_DIGESTS pinned against pre-Plan-04 baseline. Failure = visible CI break. |
| T-19-09a | Tampering | EXPECTED_DIGESTS re-pinned silently after a tick-handler change without operator review | mitigate | Re-pinning workflow is documented inline in the test (comment out assertion, capture, restore). Any commit changing EXPECTED_DIGESTS is conspicuous in code review. |
| T-19-09b | Tampering | Vacuous baseline (no frames captured → SHA-256 of empty agrees with itself) | mitigate | REVIEWS H4 / L3 — emitCount > 0 AND no per-session hex equals EMPTY_SHA256_HEX guards. |
| T-19-09c | Tampering | Cross-session emit-order non-determinism produces flaky baseline | mitigate | REVIEWS Codex H / OpenCode H — per-session digest map sidesteps cross-session order. |
| T-19-09d | Tampering | Test exercises partial pipeline, missing Plan-04 modifications | mitigate | REVIEWS H5 — explicit `applicationEventPublisher.publishEvent(new TickEvent(t))` drives the full @Order chain. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — passes on the baseline (Plan 02 merged, Plan 04 not yet started) WITH EXPECTED_DIGESTS pinned and all guards (emitCount > 0, no-empty-digest, real WS sessions wired) asserted.
- `./gradlew test` — full regression remains green.
- The FrameEmitListener seam exists on OutboundSender; production listener is null.
- The test runs deterministically: per-session emitCount and digest map equal across two consecutive runs AND the map equals the pinned EXPECTED_DIGESTS.
- Tick driver is `applicationEventPublisher.publishEvent(new TickEvent(t))` — full @Order chain fires.
</verification>

<success_criteria>
- Semantic-equivalence gate exists and is green BEFORE Plan 04 begins, with EXPECTED_DIGESTS pinned and all REVIEWS H4/H5/L3 guards asserted.
- The test captures the actual wire output (frame bytes post-sendMessage), not a mocked surface.
- The pinned digest map operationalises the D-10 promise: any future change to any tick handler that alters observable output causes this test to fail loudly, not silently agree with a cohort run or a vacuous baseline.
- D-10 is enforced as a CI-visible regression gate, not as a planning aspiration.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-03-SUMMARY.md`.
</output>
</content>
