---
phase: 19
plan: 03
type: execute
wave: 3
depends_on: [19-02]
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
    - "A seeded scenario can be driven twice in the same JVM and the digest of all outbound WebSocket frames is byte-identical between runs (proves the test harness itself is deterministic before any refactor lands)."
    - "GoldenTraceEquivalenceTest passes BEFORE Plan 04 (entity-list iteration) is merged — the test serves as the oracle for that refactor."
    - "Test captures outbound frame bytes via a test-only seam on OutboundSender (or equivalent), not by mocking individual handlers — captures the actual wire output."
    - "Digest is SHA-256 over the concatenation of (sessionId, frame.bytes()) tuples in deterministic order per tick."
    - "A baseline digest is captured against the post-Plan-02 codebase (BEFORE Plan 04 lands) and pinned as `EXPECTED_DIGEST` in the test source. The acceptance contract is: hashA == hashB AND hashA == EXPECTED_DIGEST. Plan 04 must keep this assertion green — that is the D-10 promise made operational."
    - "LiveEntityRegistry is in place (Plan 02 dependency satisfied) so that the test runs through the same code paths as Plan 04 will modify."
  artifacts:
    - path: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      provides: "@SpringBootTest dual-run determinism gate. Runs a 50-tick scenario with N bots at fixed seed, captures all outbound frame bytes, computes SHA-256 digest, then resets and runs again. Asserts digestA == digestB AND digestA == EXPECTED_DIGEST (a private static final hex constant pinned during this plan, BEFORE Plan 04 lands). Fails if Plan 04 refactor changes any observable wire output."
      min_lines: 130
    - path: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      provides: "Test-only frame-byte accumulator. Subscribes to a frame-emit hook on OutboundSender (or wraps the bean) and feeds (sessionId, frameBytes) into a MessageDigest in tick-stable order. Exposes digest as both byte[] and lowercase hex String for EXPECTED_DIGEST pinning."
      min_lines: 70
  key_links:
    - from: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      to: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      via: "@Autowired wiring; test asserts hashA == hashB across two driven runs AND hashA == EXPECTED_DIGEST against a pinned baseline"
      pattern: "GoldenTraceCapture"
    - from: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      to: src/main/java/com/paralife/websocket/OutboundSender.java
      via: "Test-only listener hook on OutboundSender.offer or a CapturingOutboundSender test-double registered as @Primary in the test profile"
      pattern: "OutboundSender"
---

<objective>
Build the **D-10 semantic-equivalence gate** — a `GoldenTraceEquivalenceTest` that captures all outbound WebSocket frame bytes during a fixed-seed scenario, computes a SHA-256 digest, asserts byte-equality across two consecutive runs, **and** asserts the digest equals a pinned baseline constant captured against the post-Plan-02 / pre-Plan-04 codebase. This test must exist and pass **before** Plan 04 lands. Once Plan 04 refactors tick-handler iteration to use `LiveEntityRegistry.snapshot()`, this test is the oracle that proves the refactor changed nothing observable.

Per RESEARCH.md Open Question 3 (RESOLVED): full equivalence (frames + metric counters) is aspirational; perception-frame digest is the **minimum-viable contract**. Metric-counter equivalence is deferred to Phase 21 benchmark gate. This plan ships the minimum-viable contract.

**Critical sequencing — the EXPECTED_DIGEST pin:**

The naive design (assert only `hashA == hashB`) is insufficient: hashA and hashB are both computed in the same JVM run after Plan 04 lands, so the test would pass even if Plan 04 changed observable output (both runs would simply produce the same wrong digest). The fix is to **capture the baseline digest against the pre-Plan-04 codebase, hard-code it as a constant, and assert subsequent runs equal that constant.**

Workflow within this plan (executed in order, in this plan's wave):
  1. Land Task 1 (FrameEmitListener seam + capture helper) — this is purely additive instrumentation.
  2. Land Task 2 step (a): write the test scaffold WITHOUT the EXPECTED_DIGEST pin yet.
  3. Run the test once on the post-Plan-02 codebase. The test prints the digest produced by Run 1 (e.g. `BASELINE DIGEST: 9f1c…`).
  4. Pin that digest as `private static final String EXPECTED_DIGEST = "9f1c…";` in the test source.
  5. Re-run the test — it must now pass `hashA == hashB == EXPECTED_DIGEST`.
  6. Commit. Plan 04's acceptance includes that this same test continues to pass — same EXPECTED_DIGEST.

Purpose: Without this gate, Plan 04 cannot prove D-10. The test serves as the merge-gate during execution and as the regression sentinel for any future tick-handler change.
Output: One new production-test seam on `OutboundSender` (test-only frame-emit listener), one test capture helper, one @SpringBootTest equivalence gate with a pinned baseline digest.

**Wave assignment rationale:** This plan sits in Wave 3 — depends on Plan 02 (LiveEntityRegistry must exist so the seeded test exercises the same DI graph Plan 04 will modify). Plan 04 (Wave 4) blocks on this plan because the test must be passing with EXPECTED_DIGEST pinned before the refactor lands.
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
@src/main/java/com/paralife/websocket/OutboundSender.java
@src/main/java/com/paralife/websocket/TickBroadcaster.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/LiveEntityRegistry.java
@src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java

<interfaces>
<!-- Existing OutboundSender surface this plan extends with a test-only listener seam. -->

From src/main/java/com/paralife/websocket/OutboundSender.java (existing):
```java
// Phase 17 D-10: VT-per-session bounded queue. Tick handlers enqueue via offer(sessionId, frame).
// The drain VT does the actual sendMessage; that is the byte-emission point.
public boolean offer(String sessionId, Frame frame);
public int queueDepth(String sessionId);
// (re-read source to confirm exact method signatures before patching)
```

NEW test seam (production code; minimal scope):
```java
// Functional listener interface — invoked at the point of actual sendMessage,
// inside the synchronized(session) block, before the call returns to drainLoop.
@FunctionalInterface
public interface FrameEmitListener {
    void onEmit(String sessionId, byte[] frameBytes);
}

// Public method on OutboundSender:
public void setFrameEmitListener(FrameEmitListener listener);   // null clears
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
  <name>Task 1: Add FrameEmitListener test seam on OutboundSender + GoldenTraceCapture helper</name>
  <files>src/main/java/com/paralife/websocket/OutboundSender.java, src/test/java/com/paralife/engine/GoldenTraceCapture.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/OutboundSender.java (entire file — find the exact line where the drain VT calls `session.sendMessage(...)` inside `synchronized(session)`; that is the byte-emission point)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 200–230 — confirm `outboundSender.offer(sessionId, frame)` is the only enqueue call; if there are out-of-band sendMessage paths via WorldWebSocketHandler.sendOutOfBand, the listener also needs to fire there per CLAUDE.md "Synchronized-session-monitor contract")
    - CLAUDE.md §"Outbound concurrency (Phase 17, D-10)" — invariants the listener must respect (do NOT hold a lock during `listener.onEmit`; do NOT call back into the sender from the listener)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples — Golden-Trace Test Pattern; SHA-256 digest sketch)
  </read_first>
  <behavior>
    - listenerSeesAllOutboundBytes: register a listener; enqueue 3 frames; drain runs; listener.onEmit invoked exactly 3 times with the correct (sessionId, bytes) tuples in send-order per session.
    - listenerNullClearsHook: setFrameEmitListener(null) afterwards; subsequent offers do not invoke any listener.
    - listenerExceptionDoesNotKillDrainLoop: a listener that throws RuntimeException must not break the drain VT; subsequent frames continue to send. Enforce by wrapping the listener invocation in try/catch with a `log.warn` on failure. (Optional but recommended — the test seam should be defensive.)
    - listenerFiresInsideSynchronizedSessionBlock: not directly testable, but verify by code inspection that the listener call is inside `synchronized(session)` and AFTER `session.sendMessage(...)` returns successfully (so the listener captures only successfully-sent bytes — same surface a real WS client would observe). Document this in Javadoc.
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
           // frameBytes is the same byte array passed to sendMessage —
           // re-encode if needed, or capture before send. Easiest: encode once,
           // pass into sendMessage as TextMessage, and feed the encoded bytes
           // into the listener.
           listener.onEmit(sessionId, encodedBytes);
       } catch (Throwable t) {
           log.warn("FrameEmitListener threw, ignoring: {}", t.toString());
       }
   }
   ```
   The exact variable names (`sessionId`, `encodedBytes`, `message`) depend on the existing code — re-read OutboundSender.java to find the right names. The listener invocation MUST stay inside `synchronized(session)` so it observes the same byte boundaries the real WS observes.

2. Create `src/test/java/com/paralife/engine/GoldenTraceCapture.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.OutboundSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 19 SCALE-07 D-10: test-only digest accumulator for the
 * GoldenTraceEquivalenceTest. Subscribes to OutboundSender.frameEmitListener;
 * SHA-256 over (sessionId UTF-8 bytes, frameBytes) per emit, in emit order.
 *
 * <p>Digest stability requires deterministic emit order. OutboundSender is
 * a per-session VT-per-queue; ordering inside one session is trivially stable.
 * Cross-session ordering is not — but the test scenario uses fixed seeds and a
 * single-threaded driver, so emit order is deterministic per scenario run.
 *
 * <p>If cross-session ordering proves flaky in CI, switch to per-session digests
 * and assert each session's digest equals across runs (stronger contract).
 */
public class GoldenTraceCapture {

    private final MessageDigest digest;
    private final AtomicLong emitCount = new AtomicLong();
    private final List<String> sessionsSeen = new ArrayList<>();

    public GoldenTraceCapture() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void onEmit(String sessionId, byte[] frameBytes) {
        digest.update(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(frameBytes);
        emitCount.incrementAndGet();
        if (!sessionsSeen.contains(sessionId)) sessionsSeen.add(sessionId);
    }

    /**
     * Finalise and return the digest as a raw byte array. Calling this
     * resets the underlying MessageDigest, so use {@link #digestAsHex()}
     * if you want a stable display form for pinning.
     */
    public synchronized byte[] currentDigest() {
        return digest.digest();
    }

    /**
     * Convenience: lowercase hex of currentDigest(). Use this to print the
     * baseline digest during EXPECTED_DIGEST pinning.
     */
    public synchronized String digestAsHex() {
        byte[] d = digest.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public synchronized void reset() {
        digest.reset();
        emitCount.set(0);
        sessionsSeen.clear();
    }

    public long emitCount() { return emitCount.get(); }
    public List<String> sessionsSeen() { return new ArrayList<>(sessionsSeen); }
}
```

This class is in `src/test/java`, NOT in production code. It's a plain class, not a `@Component` — the test wires it via @Autowired by hand or registers it manually.

3. Quick smoke test the seam by running the full regression suite — no behaviour change is expected; `OutboundSender` listener defaults to null in production.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "interface FrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` == 1
    - `grep -c "setFrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` >= 2 (declaration + invocation site)
    - `grep -c "frameEmitListener.onEmit\|listener.onEmit" src/main/java/com/paralife/websocket/OutboundSender.java` >= 1
    - Production code path: listener defaults to null (no behaviour change). Verify by `grep -c "private volatile FrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` == 1.
    - File `src/test/java/com/paralife/engine/GoldenTraceCapture.java` exists.
    - `grep -c "MessageDigest.getInstance(\"SHA-256\")" src/test/java/com/paralife/engine/GoldenTraceCapture.java` == 1
    - `grep -c "public synchronized void onEmit" src/test/java/com/paralife/engine/GoldenTraceCapture.java` == 1
    - `grep -c "digestAsHex" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1 (helper for EXPECTED_DIGEST pinning in Task 2)
    - `./gradlew test` exits 0 (regression — listener=null in production runs, no observable change)
  </acceptance_criteria>
  <done>FrameEmitListener seam wired into OutboundSender at the post-sendMessage point inside synchronized(session); production listener is null; GoldenTraceCapture computes SHA-256 over (sessionId,bytes) emit pairs and exposes both byte[] and hex String forms; full regression suite green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Author GoldenTraceEquivalenceTest — dual-run digest equality + pinned EXPECTED_DIGEST baseline</name>
  <files>src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java</files>
  <read_first>
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template; copy @SpringBootTest setup, @TestPropertySource, resetAll pattern, dual-run drive structure)
    - src/main/java/com/paralife/engine/TickEngine.java (find the test-only tick-advance method — `runOneTick()` or `tickOnce()`; confirms how to drive ticks deterministically with auto-start=false)
    - src/main/java/com/paralife/websocket/OutboundSender.java (the FrameEmitListener seam from Task 1)
    - src/test/java/com/paralife/engine/GoldenTraceCapture.java (helper from Task 1; use `digestAsHex()` for the baseline pin print)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Plan 02 dependency — confirms `clearForTest()` and `snapshot()` are available)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples — Golden-Trace Test Pattern; §Open Questions Q3 RESOLVED — minimum viable contract)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 374–388 — golden-trace test analog)
  </read_first>
  <behavior>
    - byteIdenticalOutputAcrossTwoRuns: drive a fixed-seed 50-tick scenario, capture digest A; reset all state; drive the same scenario again, capture digest B; assert hashA equals hashB AND hashA equals EXPECTED_DIGEST.
    - emitCountIsConsistent: capture.emitCount() is identical across the two runs (sanity check before digest comparison).
    - testIsSelfConsistentBeforeAnyRefactor: this test passes on the codebase **as it stands today** (Plan 02 wired LiveEntityRegistry but did not change tick handlers). If it doesn't pass on baseline, the harness has non-determinism that must be fixed BEFORE Plan 04 runs.
    - baselineDigestIsPinned: a `private static final String EXPECTED_DIGEST = "<hex>"` constant exists in the test source; the test asserts the run's hex digest equals this constant. The constant is captured against the post-Plan-02 / pre-Plan-04 codebase during this plan's execution.
  </behavior>
  <action>
**Two-pass workflow (executed in order):**

**Pass 1 — write the scaffold without the pin, capture the baseline digest:**

1. Create `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.OutboundSender;
import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Particle;
import com.paralife.world.ParticleType;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Phase 19 SCALE-07 D-10 semantic-equivalence gate.
 *
 * <p>Drives a fixed-seed scenario twice in the same JVM, captures all outbound
 * frame bytes via the OutboundSender FrameEmitListener seam, computes SHA-256
 * digests, asserts byte-equality across runs AND byte-equality against a pinned
 * baseline digest captured against the post-Plan-02 / pre-Plan-04 codebase.
 *
 * <p>Plan 04 (entity-list iteration refactor) must keep this test green —
 * the EXPECTED_DIGEST is the operational form of the D-10 promise.
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
     * Pinned baseline digest. Captured during Plan 03 execution against the
     * post-Plan-02 / pre-Plan-04 codebase. Plan 04's refactor must produce
     * the same digest — that is the D-10 promise made operational.
     *
     * To re-pin (e.g. after an intentional scenario change): comment out the
     * EXPECTED_DIGEST assertion below, run the test, copy the printed
     * "BASELINE DIGEST: <hex>" line into this constant, restore the assertion.
     */
    private static final String EXPECTED_DIGEST = "REPLACE_ME_AFTER_FIRST_RUN";

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired TickEngine tickEngine;

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
        // Run 1
        resetAll();
        driveScenario();
        long emitsA = capture.emitCount();
        String hexA = capture.digestAsHex();

        // Print for first-time baseline capture. Leave this print in — it is
        // the operator's signal when re-pinning is needed.
        System.out.println("BASELINE DIGEST: " + hexA);

        // Run 2
        capture.reset();
        resetAll();
        driveScenario();
        long emitsB = capture.emitCount();
        String hexB = capture.digestAsHex();

        assertThat(emitsB).as("emit count stable across runs").isEqualTo(emitsA);
        assertThat(hexB).as("D-10 byte-identical outbound frames (run B vs run A)").isEqualTo(hexA);
        assertThat(hexA).as("D-10 EXPECTED_DIGEST baseline pinned in Plan 03; Plan 04 must not change this").isEqualTo(EXPECTED_DIGEST);
    }

    private void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        handler.resetSeed();
        // EligibleCellIndex and any other seeded beans must also reset to deterministic state.
        // If the executor finds additional seeded beans during implementation, add reset hooks here.
    }

    private void driveScenario() throws Exception {
        // Step 1: register N bots directly via test seams (skip the WS handshake
        // — the goal is to exercise the tick pipeline, not the WS layer).
        // Use a synthetic session-id pattern that is deterministic.
        for (int i = 0; i < 10; i++) {
            String entityId = "trace-bot-" + i;
            String sessionId = "trace-sess-" + i;
            // Place via deterministic loop — same pattern as existing
            // EnvironmentDeterminismTest.driveRun if such a helper exists.
            Position pos = new Position(i * 3, i * 3);
            worldGrid.trySetEntity(pos.x(), pos.y(), Particle.spawn(
                entityId, ParticleType.values()[i % 3], 100));
            botRegistry.register(sessionId, entityId, pos);
            liveEntityRegistry.register(entityId, pos);
        }

        // Step 2: drive 50 ticks. tickEngine.runOneTick() is the test seam.
        // If the actual method is named differently, follow EnvironmentDeterminismTest's
        // tick-driving helper (see lines 90–108 of that test).
        for (int t = 0; t < 50; t++) {
            tickEngine.runOneTick(/* args per existing seam */);
        }
    }
}
```

**Pass 1 first run:** with `EXPECTED_DIGEST = "REPLACE_ME_AFTER_FIRST_RUN"`, the test will fail the third assertion. That is expected. Read the test output, locate the line `BASELINE DIGEST: <hex>`, copy the hex value.

**Pass 2 — pin the baseline digest:**

2. Edit `EXPECTED_DIGEST` in the test source to the captured hex value:

```java
private static final String EXPECTED_DIGEST = "<paste the 64-char hex from BASELINE DIGEST line>";
```

3. Re-run the test. All three assertions must now pass:
   - `emitsB == emitsA`
   - `hexB == hexA`
   - `hexA == EXPECTED_DIGEST`

4. Commit. Plan 04 acceptance includes that this test continues to pass with the same EXPECTED_DIGEST.

**Important caveats for the executor:**

(a) `WorldGrid.clear()`, `BotRegistry.clear()`, `LiveEntityRegistry.clearForTest()`, `WorldWebSocketHandler.resetSeed()` — confirm each exists. If `BotRegistry.clear()` is missing, add it as a test-only method (similar to `WorldGrid.clear()`).

(b) `TickEngine.runOneTick()` — re-read `TickEngine.java` to find the actual test-driving method. `EnvironmentDeterminismTest` already drives ticks for its own seeded run; copy that exact mechanism.

(c) The scenario in `driveScenario()` is intentionally minimal — 10 bots, 50 ticks, no WS handshake. This exercises the tick pipeline (SimulationEngine, EnvironmentEngine, ActionResolver, TickBroadcaster) but not the WS layer. Plan 04 modifies the tick pipeline; that is what this test covers. If the executor finds the scenario produces zero outbound frames (no WS sessions registered → outboundSender never enqueues), expand to use real test WS sessions modelled on `HundredBotIntegrationTest` — but the simpler scenario should suffice if `botRegistry.register(sessionId, ...)` is enough to make TickBroadcaster broadcast (verify by reading TickBroadcaster.onTick — if it requires `sessionRegistry.getSession(sessionId)` to be non-null and open, the test must use real WS sessions).

(d) Cross-session digest order: this test uses a deterministic single-threaded scenario, so emit order should be stable. If flake appears, switch to per-session digest map and compare entries.

(e) **The EXPECTED_DIGEST must be captured against the post-Plan-02 / pre-Plan-04 codebase.** If by accident this plan executes after Plan 04 has already been merged, the digest is contaminated and the D-10 promise is unenforceable. The wave structure (this plan = Wave 3, Plan 04 = Wave 4) prevents that — but if a re-run of this plan happens after Plan 04 lands, abort and re-pin against a clean checkout of the pre-Plan-04 baseline.

(f) **This test must pass on the post-Plan-02 baseline**. If `hexA != hexB`, the test infrastructure has a non-determinism bug — fix it BEFORE pinning EXPECTED_DIGEST. Common culprits:
   - HashMap iteration order in TickBroadcaster (use LinkedHashMap or sort by sessionId)
   - ConcurrentHashMap.values() in BotRegistry (Plan 04 will replace with LiveEntityRegistry.snapshot, but until then the baseline test must still be deterministic — sort the values by entityId in the test driver if needed)
   - System.nanoTime / clock reads in any frame payload (should not be present, but check if digest fails)
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` exists.
    - `grep -c "@SpringBootTest" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "byteIdenticalOutputAcrossTwoRuns" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "private static final String EXPECTED_DIGEST" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -E "EXPECTED_DIGEST = \"[0-9a-f]{64}\"" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` returns a match (a real 64-char lowercase-hex SHA-256 digest, NOT the placeholder `REPLACE_ME_AFTER_FIRST_RUN`)
    - `grep -c "isEqualTo(EXPECTED_DIGEST)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "outboundSender.setFrameEmitListener" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (set + clear in tearDown)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (passes on the baseline with EXPECTED_DIGEST pinned — this is the precondition for Plan 04)
    - `./gradlew test` exits 0 (full regression remains green)
  </acceptance_criteria>
  <done>GoldenTraceEquivalenceTest exists and passes on the post-Plan-02 baseline. The dual-run digest assertion is in place AND the EXPECTED_DIGEST baseline is pinned (real 64-char hex constant, not the placeholder). Plan 04 cannot land without keeping this test green against the same EXPECTED_DIGEST — D-10 enforced.</done>
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
| T-19-09 | Repudiation | Plan 04 changes observable output undetected | mitigate | This test IS the mitigation — EXPECTED_DIGEST pinned against pre-Plan-04 baseline. Failure = visible CI break. |
| T-19-09a | Tampering | EXPECTED_DIGEST re-pinned silently after a tick-handler change without operator review | mitigate | Re-pinning workflow is documented inline in the test (comment out assertion, capture, restore). Any commit changing EXPECTED_DIGEST is conspicuous in code review. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — passes on the baseline (Plan 02 merged, Plan 04 not yet started) WITH EXPECTED_DIGEST pinned.
- `./gradlew test` — full regression remains green.
- The FrameEmitListener seam exists on OutboundSender; production listener is null.
- The test runs deterministically: emitCount and digest equal across two consecutive runs AND the digest equals the pinned EXPECTED_DIGEST.
</verification>

<success_criteria>
- Semantic-equivalence gate exists and is green BEFORE Plan 04 begins, with EXPECTED_DIGEST pinned.
- The test captures the actual wire output (frame bytes post-sendMessage), not a mocked surface.
- The pinned digest constant operationalises the D-10 promise: any future change to any tick handler that alters observable output causes this test to fail loudly, not silently agree with a cohort run.
- D-10 is enforced as a CI-visible regression gate, not as a planning aspiration.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-03-SUMMARY.md`.
</output>
