---
phase: 19
plan: 03
type: execute
wave: 3
depends_on: [19-01, 19-02]
files_modified:
  - src/main/java/com/paralife/admission/OutboundSender.java
  - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
  - src/test/java/com/paralife/engine/GoldenTraceCapture.java
  - src/test/resources/golden-trace-phase19.json
autonomous: true
requirements:
  - SCALE-07
tags: [equivalence, golden-trace, sha-256, determinism, java, spring-boot]

must_haves:
  truths:
    - "A seeded scenario can be driven twice in the same JVM and the per-session digest map of all outbound WebSocket frames is byte-identical between runs (proves the test harness is deterministic before any refactor lands)."
    - "GoldenTraceEquivalenceTest passes BEFORE Plan 04 — the test serves as the oracle for that refactor."
    - "Test captures outbound frame bytes via a test-only `FrameEmitListener` seam on `com.paralife.admission.OutboundSender` (REVIEWS CONSENSUS-H5 — verified package), invoked AFTER `synchronized(session) { session.sendMessage(...) }` returns, so the listener observes actual wire output."
    - "Digest is captured PER SESSION (Map<String, String> sessionId → SHA-256 hex), removing cross-session emit-order non-determinism."
    - "EXPECTED_DIGESTS is loaded from `src/test/resources/golden-trace-phase19.json` (generate-if-missing). First run with absent file: test computes digests, writes file, fails with `BASELINE_PINNED — re-run test`. Second run reads file → asserts equality. (REVIEWS MED-1.)"
    - "Test asserts emitCount > 0 AND digest map non-empty AND no per-session hex equals SHA-256(empty input) (REVIEWS H4 / L3)."
    - "TickEvent dispatch drives the full @Order chain via `applicationEventPublisher.publishEvent(new com.paralife.engine.TickEvent(t))` (REVIEWS CONSENSUS-H5 + R2-15 — package and ctor verified)."
    - "Mock sessions are registered via the EXACT production signatures: `outboundSender.attachSession(WebSocketSession, int)` and `sessionRegistry.register(WebSocketSession)` — verified pre-flight grep (REVIEWS CONSENSUS-H5 + MED-4)."
    - "`awaitAllSessionQueuesDrained` iterates explicit `registeredSessionIds` then **acquires `synchronized(session) {}` per session** to wait for any in-flight `sendMessage` to complete — closes the queueDepth-only race (REVIEWS CONSENSUS-H3)."
    - "Scenario density: 30 bots on 16×16 grid (~12% density) for 200 ticks; deterministic adjacent-RPS placement step BEFORE main loop forces ≥1 bond formation; test asserts `bondFormationCount > 0` OR `compositeFormationCount > 0` over the run (REVIEWS CONSENSUS-H2)."
    - "`resetAll()` cross-run cleanup: per registered sessionId, calls `outboundSender.detachSession(sid)` and `sessionRegistry.unregister(sid)` to drop sender VTs and registry entries; then clears `registeredSessionIds`. Two consecutive `driveScenario` calls within one @Test do NOT leak duplicate VTs (REVIEWS MEDIUM-5)."
    - "`OutboundSender.FrameEmitListener` catch is `Exception` (NOT `Throwable`) — does not swallow OOM/StackOverflow (REVIEWS LOW-10)."
  artifacts:
    - path: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      provides: "@SpringBootTest dual-run determinism gate. Drives a 30-bot 16×16 200-tick scenario with deterministic adjacent-RPS placement and queued ActionFrames at fixed seed via applicationEventPublisher.publishEvent(new TickEvent(t)). Captures per-session SHA-256 frame digests. Loads EXPECTED_DIGESTS from `src/test/resources/golden-trace-phase19.json` (generate-if-missing — REVIEWS MED-1). Asserts digestMapA == digestMapB == EXPECTED_DIGESTS; emitCount > 0; no per-session digest is SHA-256(empty); ≥1 bond OR composite formation occurred (REVIEWS CONSENSUS-H2). Drain await uses post-queueDepth synchronized(session) barrier (REVIEWS CONSENSUS-H3)."
      min_lines: 220
    - path: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      provides: "Test-only frame-byte accumulator. Maintains Map<String, MessageDigest> keyed by sessionId; each outbound frame from session S is hashed into digest[S] in send order. Exposes Map<String, String> hex digests + total emitCount + sessionsSeen."
      min_lines: 80
    - path: src/test/resources/golden-trace-phase19.json
      provides: "Pinned per-session SHA-256 digest map. Generated on first run; read-only on subsequent CI runs. Re-pinning requires deletion + re-run (visible in code review)."
      min_lines: 1
  key_links:
    - from: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      to: src/main/java/com/paralife/admission/OutboundSender.java
      via: "FrameEmitListener invoked inside drainLoop AFTER synchronized(session) sendMessage returns; bytes are the actual wire output"
      pattern: "FrameEmitListener"
---

<objective>
Build the **D-10 semantic-equivalence gate** — a `GoldenTraceEquivalenceTest` that captures all outbound WebSocket frame bytes during a fixed-seed scenario, computes a **per-session SHA-256 digest map**, asserts byte-equality across two consecutive runs, and asserts the map equals a pinned baseline loaded from a JSON resource file. This test must exist and pass **before** Plan 04 lands.

**REVIEWS Round 2 + Round 3 fixes encoded in plan body:**

- **CONSENSUS-H2 — Golden-trace scenario density:** 30 bots on 16×16 (~12% density) for 200 ticks, with a deterministic adjacent-RPS placement step BEFORE the main loop (places predator + prey pairs in adjacent cells with energies ≥ `bondingConfig.bondEnergyThreshold` and `bondingProbability=1.0` to force bond formation). Queue deterministic `ActionFrame` moves so ActionResolver path is exercised. Acceptance: `assertThat(bondFormationCount + compositeFormationCount).isGreaterThan(0)` over the run.
- **CONSENSUS-H3 — drain-race fix:** `awaitAllSessionQueuesDrained` after the `queueDepth == 0` check acquires `synchronized(session) {}` per registered session — guarantees any in-flight `sendMessage` / listener callback has completed before the next tick fires. Per CLAUDE.md "synchronized-session-monitor contract" (every writer holds the monitor for the actual sendMessage call), acquiring the monitor post-drain is sufficient.
- **CONSENSUS-H5 — package paths:** `OutboundSender` is at `com.paralife.admission.OutboundSender`. `TickEvent` is at `com.paralife.engine.TickEvent`. **Verified by grep before any test code is authored.**
- **MEDIUM-5 — cross-run cleanup:** `resetAll()` calls `outboundSender.detachSession(sid)` and `sessionRegistry.unregister(sid)` per registered sessionId; clears `registeredSessionIds`. Two consecutive `driveScenario` calls within one @Test do NOT reuse stale VTs.
- **LOW-10 — `Throwable` → `Exception`** in OutboundSender FrameEmitListener catch.
- **MED-4 / R2-15 — pre-flight signature check:** Task 2 begins with grep on `OutboundSender.attachSession` / `OutboundSender.detachSession` / `SessionRegistry.register` / `SessionRegistry.unregister` / `TickEvent` ctor BEFORE writing test code.

**Wave assignment:** Plan 03 sits in Wave 3, depends on Plans 01 + 02. Plan 04 (Wave 4) blocks on this plan.
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
@src/main/java/com/paralife/admission/OutboundSender.java
@src/main/java/com/paralife/websocket/TickBroadcaster.java
@src/main/java/com/paralife/websocket/SessionRegistry.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/LiveEntityRegistry.java
@src/main/java/com/paralife/engine/TickEngine.java
@src/main/java/com/paralife/engine/TickEvent.java
@src/main/java/com/paralife/engine/BondingConfig.java
@src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java
@src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java

<interfaces>
<!-- VERIFIED via grep against current source. -->

From src/main/java/com/paralife/admission/OutboundSender.java (CONSENSUS-H5 — package=admission):
```java
package com.paralife.admission;   // VERIFIED line 1

public OutboundSender(AdmissionMetrics metrics);           // line 68
public void attachSession(WebSocketSession session, int queueCapacity);   // line 88
public void detachSession(String sessionId);              // line 107  ← used in resetAll() (REVIEWS MEDIUM-5)
public boolean offer(String sessionId, Frame frame);      // line 133
public int queueDepth(String sessionId);                  // line 157

// Drain loop body (line 176 drainLoop; line 185 synchronized(session); line 186 sendMessage):
// for (;;) {
//     Frame frame = queue.take();
//     if (!session.isOpen()) continue;
//     try {
//         String encoded = PerceptionCodec.encode(frame);
//         int byteLen = encoded.getBytes(StandardCharsets.UTF_8).length;
//         metrics.recordFrameSize(byteLen);
//         synchronized (session) {
//             session.sendMessage(new TextMessage(encoded));
//         }   ← INSERT FrameEmitListener invocation HERE (still inside synchronized for monitor handover)
//     } catch (IOException e) { ... } catch (RuntimeException e) { ... }
// }
```

From src/main/java/com/paralife/websocket/SessionRegistry.java:
```java
public void register(WebSocketSession session);            // line 31  ← takes session, derives id via .getId()
public void unregister(String sessionId);                  // line 37  ← used in resetAll() (REVIEWS MEDIUM-5)
public WebSocketSession getSession(String sessionId);      // line 57
```

From src/main/java/com/paralife/engine/TickEvent.java (CONSENSUS-H5 — package=engine; R2-15 ctor verified):
```java
package com.paralife.engine;   // VERIFIED — NOT websocket
public record TickEvent(long tickNumber, Instant timestamp) {
    public TickEvent(long tickNumber) { this(tickNumber, Instant.now()); }   // `new TickEvent(1L)` valid
}
```

From src/main/java/com/paralife/engine/BondingConfig.java:
```java
@ConfigurationProperties(prefix = "paralife.bonding")
public record BondingConfig(int bondEnergyThreshold, double bondingProbability, ...) { ... }
// Test override: paralife.bonding.bonding-probability=1.0 forces bond on every eligible encounter
```

From src/main/java/com/paralife/engine/LiveEntityRegistry.java (Plan 02):
```java
public List<EntityEntry> snapshot();
public void clearForTest();
public int size();
public record EntityEntry(String entityId, Position position, Optional<String> sessionId) { }
```

NEW test seam (production code; minimal scope):
```java
@FunctionalInterface
public interface FrameEmitListener {
    /** Invoked AFTER session.sendMessage succeeds; bytes are the actual wire output. Test-only. */
    void onEmit(String sessionId, byte[] frameBytes);
}

public void setFrameEmitListener(FrameEmitListener listener);   // null clears
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Add FrameEmitListener seam on com.paralife.admission.OutboundSender (catch Exception, NOT Throwable — REVIEWS LOW-10) + GoldenTraceCapture per-session digest helper</name>
  <files>src/main/java/com/paralife/admission/OutboundSender.java, src/test/java/com/paralife/engine/GoldenTraceCapture.java</files>
  <read_first>
    - **PRE-FLIGHT — REVIEWS CONSENSUS-H5 PACKAGE VERIFICATION:**
      ```bash
      grep -n "package com.paralife" src/main/java/com/paralife/admission/OutboundSender.java
      grep -n "package com.paralife" src/main/java/com/paralife/engine/TickEvent.java
      grep -nE "public.*attachSession|public.*detachSession|public.*offer\\(|public.*queueDepth\\(" src/main/java/com/paralife/admission/OutboundSender.java
      grep -nE "public.*register\\(|public.*unregister\\(|public.*getSession\\(" src/main/java/com/paralife/websocket/SessionRegistry.java
      ```
      Expected output:
      - `com.paralife.admission` for OutboundSender
      - `com.paralife.engine` for TickEvent
      - `attachSession(WebSocketSession, int)` line 88; `detachSession(String)` line 107
      - `register(WebSocketSession)` line 31; `unregister(String)` line 37
    - src/main/java/com/paralife/admission/OutboundSender.java (entire file — find synchronized(session)/sendMessage in drainLoop ~line 185)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 200–230 — confirm `outboundSender.offer` is the only enqueue call)
    - CLAUDE.md §"Outbound concurrency (Phase 17, D-10)" — synchronized-session-monitor contract
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples — Golden-Trace Test Pattern)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (CONSENSUS-H5; LOW-10 catch Exception)
  </read_first>
  <behavior>
    - listenerSeesAllOutboundBytes: register a listener; enqueue 3 frames to session "s1"; drain runs; listener.onEmit invoked exactly 3 times in send-order.
    - listenerCapturesPerSessionOrder: enqueue frames to "s1" and "s2" interleaved; per-session emit order matches per-session enqueue order.
    - listenerNullClearsHook: setFrameEmitListener(null); subsequent offers do not invoke any listener.
    - listenerExceptionDoesNotKillDrainLoop: a listener that throws RuntimeException must not break the drain VT.
    - listenerThrowsErrorPropagates: a listener that throws Error (e.g. OOM) is NOT swallowed — propagates per JVM contract (REVIEWS LOW-10).
  </behavior>
  <action>

**STEP 0 — REVIEWS CONSENSUS-H5 + MED-4 pre-flight (run grep commands listed in `<read_first>` and document outputs).** Outputs MUST match the expected lines/packages. If they don't, STOP and reconcile against the source — package or ctor drift between authoring and execution invalidates the test.

**STEP 1 — Add FrameEmitListener seam to `src/main/java/com/paralife/admission/OutboundSender.java`:**

(a) Add the functional interface as a public nested type:
```java
@FunctionalInterface
public interface FrameEmitListener {
    /** Invoked AFTER session.sendMessage succeeds; bytes are the actual wire output. Test-only. */
    void onEmit(String sessionId, byte[] frameBytes);
}
```

(b) Add a `private volatile FrameEmitListener frameEmitListener;` field and a public setter:
```java
public void setFrameEmitListener(FrameEmitListener listener) {
    this.frameEmitListener = listener;
}
```

(c) In the drain loop at ~line 185, immediately after the successful `session.sendMessage(new TextMessage(encoded));` call (still INSIDE the `synchronized(session)` block, so the listener invocation is part of the synchronized-session-monitor contract — guarantees the post-drain barrier in REVIEWS CONSENSUS-H3 covers in-flight listener invocations):

```java
synchronized (session) {
    session.sendMessage(new TextMessage(encoded));
    FrameEmitListener listener = this.frameEmitListener;
    if (listener != null) {
        try {
            listener.onEmit(session.getId(), encoded.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // REVIEWS LOW-10: catch Exception (NOT Throwable) so OOM/StackOverflowError still propagates.
            log.warn("FrameEmitListener threw, ignoring: {}", e.toString());
        }
    }
}
```

If the existing variable name is not `encoded`, match what's in source. The `byteLen = encoded.getBytes(...)` call earlier in the same try-block already computed the bytes — if that buffer is reused, prefer reusing it to avoid a second encoding pass.

**STEP 2 — Create `src/test/java/com/paralife/engine/GoldenTraceCapture.java`:**

```java
package com.paralife.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 19 SCALE-07 D-10: per-session SHA-256 digest accumulator. */
public class GoldenTraceCapture {

    /** SHA-256 of empty byte array. */
    public static final String EMPTY_SHA256_HEX =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final Map<String, MessageDigest> digestsBySession = new HashMap<>();
    private final AtomicLong emitCount = new AtomicLong();
    private final List<String> sessionsSeen = new ArrayList<>();

    public synchronized void onEmit(String sessionId, byte[] frameBytes) {
        MessageDigest d = digestsBySession.computeIfAbsent(sessionId, k -> {
            try { return MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        });
        d.update(frameBytes);
        emitCount.incrementAndGet();
        if (!sessionsSeen.contains(sessionId)) sessionsSeen.add(sessionId);
    }

    public synchronized Map<String, String> digestsAsHexMap() {
        Map<String, String> out = new TreeMap<>();
        digestsBySession.forEach((s, d) -> {
            // d.digest() resets the digest; clone before to allow continued updates.
            // For test-only one-shot snapshotting, reset is acceptable.
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

**STEP 3 — Run regression.** Listener defaults to null in production; no observable behaviour change.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - **CONSENSUS-H5 package verification:** `grep -c "package com.paralife.admission" src/main/java/com/paralife/admission/OutboundSender.java` == 1
    - `grep -c "interface FrameEmitListener" src/main/java/com/paralife/admission/OutboundSender.java` == 1
    - `grep -c "setFrameEmitListener" src/main/java/com/paralife/admission/OutboundSender.java` >= 2
    - `grep -c "private volatile FrameEmitListener" src/main/java/com/paralife/admission/OutboundSender.java` == 1
    - `grep -cE "listener\\.onEmit" src/main/java/com/paralife/admission/OutboundSender.java` >= 1
    - **REVIEWS LOW-10:** `grep -c "catch (Throwable" src/main/java/com/paralife/admission/OutboundSender.java` == 0 (NOT Throwable)
    - `grep -cE "catch \\(Exception " src/main/java/com/paralife/admission/OutboundSender.java` >= 1 (in the listener-invocation block)
    - File `src/test/java/com/paralife/engine/GoldenTraceCapture.java` exists.
    - `grep -c "MessageDigest.getInstance(\"SHA-256\")" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -cE "Map<String, MessageDigest>|digestsBySession" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "EMPTY_SHA256_HEX" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" src/test/java/com/paralife/engine/GoldenTraceCapture.java` == 1
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>FrameEmitListener seam wired into the correct OutboundSender (com.paralife.admission — REVIEWS CONSENSUS-H5 closed); listener invoked inside synchronized(session) AFTER successful sendMessage so post-drain barrier covers it (REVIEWS CONSENSUS-H3 prerequisite); catch-Exception NOT Throwable (REVIEWS LOW-10 closed); production listener null; GoldenTraceCapture computes per-session SHA-256 digests; full regression green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Author GoldenTraceEquivalenceTest with dense scripted scenario (REVIEWS CONSENSUS-H2) + post-drain synchronized(session) barrier (REVIEWS CONSENSUS-H3) + cross-run cleanup (REVIEWS MEDIUM-5) + resource-file digest pinning (REVIEWS MED-1) + correct package imports (REVIEWS CONSENSUS-H5)</name>
  <files>src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java, src/test/resources/golden-trace-phase19.json</files>
  <read_first>
    - **PRE-FLIGHT — already done in Task 1; confirm:**
      - `OutboundSender` is `com.paralife.admission.OutboundSender`
      - `TickEvent` is `com.paralife.engine.TickEvent`
      - `outboundSender.attachSession(WebSocketSession, int)` line 88
      - `outboundSender.detachSession(String)` line 107
      - `sessionRegistry.register(WebSocketSession)` line 31
      - `sessionRegistry.unregister(String)` line 37
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template)
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (line 223 production attachSession site for shape match)
    - src/main/java/com/paralife/engine/TickEngine.java (line 112 — production driver: applicationEventPublisher.publishEvent)
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 327–328 bondEnergyThreshold gate; bond formation pairing for adjacent-RPS placement script)
    - src/main/java/com/paralife/engine/BondingConfig.java (defaults; bondingProbability override)
    - src/test/java/com/paralife/engine/GoldenTraceCapture.java (helper from Task 1)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Plan 02 dependency)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (CONSENSUS-H2/H3/H5; MEDIUM-5 cross-run cleanup; MED-1 resource pinning)
  </read_first>
  <behavior>
    - byteIdenticalOutputAcrossTwoRuns: drive 30-bot 16×16 200-tick scenario, capture digestsA; resetAll; drive again, capture digestsB; assert mapA == mapB == EXPECTED_DIGESTS.
    - emitCountIsConsistentAndNonZero (REVIEWS H4).
    - sessionsSeenIsNonEmpty (REVIEWS H4).
    - noPerSessionDigestIsEmptyDigest (REVIEWS L3 / H4).
    - bondOrCompositeFormationOccurredInScenario: count `bondFormationCount + compositeFormationCount > 0` over the run via SimulationEngine counters or registry deltas (REVIEWS CONSENSUS-H2).
    - awaitDrainHoldsSessionMonitorAfterQueueEmpty: `awaitAllSessionQueuesDrained` does both queueDepth==0 AND `synchronized(session) {}` per session (REVIEWS CONSENSUS-H3).
    - resetAllDetachesSendersAndUnregistersSessions: after `resetAll`, each previously-registered sessionId is no longer in `sessionRegistry`, and `outboundSender.detachSession` was called per session (REVIEWS MEDIUM-5).
    - firstRunMissingResourceWritesAndFails: with `golden-trace-phase19.json` absent, the test computes digests, writes them, fails with `BASELINE_PINNED — re-run test`. Subsequent runs (file present) succeed (REVIEWS MED-1).
  </behavior>
  <action>

**STEP 0 — verify package paths (already done in Task 1's Step 0). Re-confirm:**
```bash
grep -nE "package com\\.paralife" src/main/java/com/paralife/admission/OutboundSender.java src/main/java/com/paralife/engine/TickEvent.java
```
Both must be present and at the expected packages.

**STEP 1 — Author the test:**

Create `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java`:

```java
package com.paralife.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.admission.OutboundSender;       // REVIEWS CONSENSUS-H5: admission, NOT websocket
import com.paralife.engine.TickEvent;                // REVIEWS CONSENSUS-H5: engine, NOT websocket
import com.paralife.websocket.SessionRegistry;
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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 19 SCALE-07 D-10 semantic-equivalence gate.
 *
 * <p>REVIEWS Round 2 + Round 3 fixes encoded:
 * <ul>
 *   <li>CONSENSUS-H2 — 30 bots × 16×16 × 200 ticks; deterministic adjacent-RPS placement
 *       forces bond formation; ActionFrames queued for ActionResolver coverage.</li>
 *   <li>CONSENSUS-H3 — `awaitAllSessionQueuesDrained` post-loop synchronized(session) barrier.</li>
 *   <li>CONSENSUS-H5 — OutboundSender from com.paralife.admission; TickEvent from com.paralife.engine.</li>
 *   <li>MEDIUM-5 — resetAll detachSession + sessionRegistry.unregister per registered sid.</li>
 *   <li>MED-1 — EXPECTED_DIGESTS via generate-if-missing JSON resource.</li>
 *   <li>H4 / L3 — emitCount > 0 + no-empty-digest + map-non-empty guards.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false",
    "paralife.world.width=16",
    "paralife.world.height=16",
    // REVIEWS CONSENSUS-H2 — force bond formation on every eligible adjacency.
    "paralife.bonding.bonding-probability=1.0"
})
class GoldenTraceEquivalenceTest {

    private static final String RESOURCE_PATH = "/golden-trace-phase19.json";
    private static final Path SOURCE_PATH = Path.of("src/test/resources/golden-trace-phase19.json");
    private static final int BOT_COUNT = 30;
    private static final int TICK_COUNT = 200;
    private static final int OUTBOUND_QUEUE_SIZE = 64;

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired EligibleCellIndex eligibleCellIndex;
    @Autowired WorldWebSocketHandler handler;
    @Autowired OutboundSender outboundSender;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired ApplicationEventPublisher applicationEventPublisher;

    private GoldenTraceCapture capture;
    private final List<String> registeredSessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        capture = new GoldenTraceCapture();
        outboundSender.setFrameEmitListener(capture::onEmit);
        registeredSessionIds.clear();
    }

    @AfterEach
    void tearDown() {
        outboundSender.setFrameEmitListener(null);
        // Final cleanup so suite-mode does not leak VTs.
        for (String sid : new ArrayList<>(registeredSessionIds)) {
            outboundSender.detachSession(sid);
            sessionRegistry.unregister(sid);
        }
        registeredSessionIds.clear();
    }

    @Test
    void byteIdenticalOutputAcrossTwoRuns() throws Exception {
        // ---- Run 1 ----
        resetAll();
        long initialBondsA = bondAndCompositeFormationCount();
        driveScenario();
        long emitsA = capture.emitCount();
        Map<String, String> mapA = capture.digestsAsHexMap();
        long formationsA = bondAndCompositeFormationCount() - initialBondsA;

        // Vacuous-baseline guards (REVIEWS H4 / L3)
        assertThat(emitsA).as("REVIEWS H4 — emit count > 0").isGreaterThan(0);
        assertThat(mapA).as("REVIEWS H4 — digest map non-empty").isNotEmpty();
        mapA.forEach((s, h) -> assertThat(h)
            .as("REVIEWS L3 — per-session digest != SHA-256(empty), session=" + s)
            .isNotEqualTo(GoldenTraceCapture.EMPTY_SHA256_HEX));

        // REVIEWS CONSENSUS-H2 — scenario actually exercises the volatile paths.
        assertThat(formationsA)
            .as("REVIEWS CONSENSUS-H2 — scenario must trigger ≥1 bond OR composite formation")
            .isGreaterThan(0);

        // ---- Run 2 ----
        capture.reset();
        resetAll();
        long initialBondsB = bondAndCompositeFormationCount();
        driveScenario();
        long emitsB = capture.emitCount();
        Map<String, String> mapB = capture.digestsAsHexMap();
        long formationsB = bondAndCompositeFormationCount() - initialBondsB;

        assertThat(emitsB).as("emit count stable across runs").isEqualTo(emitsA);
        assertThat(formationsB).as("bond/composite formation count stable").isEqualTo(formationsA);
        assertThat(mapB).as("D-10 byte-identical outbound frames per session").isEqualTo(mapA);

        // ---- EXPECTED_DIGESTS resource (REVIEWS MED-1) ----
        Map<String, String> expected = loadExpectedDigests();
        if (expected == null) {
            writeBaseline(mapA);
            fail("BASELINE_PINNED — re-run test, file written to " + SOURCE_PATH.toAbsolutePath());
        }
        assertThat(mapA)
            .as("D-10 EXPECTED_DIGESTS pinned at " + RESOURCE_PATH + "; Plan 04 must not change.")
            .isEqualTo(expected);
    }

    private Map<String, String> loadExpectedDigests() throws Exception {
        try (InputStream is = getClass().getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) return null;
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> raw = mapper.readValue(is, new TypeReference<Map<String, String>>() {});
            return new TreeMap<>(raw);
        }
    }

    private void writeBaseline(Map<String, String> digests) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> sorted = new TreeMap<>(digests);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted);
        Files.createDirectories(SOURCE_PATH.getParent());
        Files.writeString(SOURCE_PATH, json);
    }

    /**
     * REVIEWS MEDIUM-5: cross-run cleanup. Detach sender VTs and unregister
     * sessions so two consecutive driveScenario calls in one @Test do NOT
     * leak duplicate VTs / digest corruption.
     */
    private void resetAll() {
        // Drop sender VTs and SessionRegistry entries from any prior driveScenario.
        for (String sid : new ArrayList<>(registeredSessionIds)) {
            outboundSender.detachSession(sid);
            sessionRegistry.unregister(sid);
        }
        registeredSessionIds.clear();

        worldGrid.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        handler.resetSeed();
        eligibleCellIndex.rebuildForTest();
    }

    /**
     * REVIEWS CONSENSUS-H2: 30 bots on 16×16; deterministic adjacent-RPS pairs
     * placed BEFORE main loop force ≥1 bond formation; main loop drives
     * ActionResolver via TickEvent dispatch. ActionFrame queueing wires bot
     * actions into the inbound channel — re-grep `inboundActions` /
     * `botRegistry.queueAction` for the actual API name and adapt.
     */
    private void driveScenario() throws Exception {
        // Step 1: deterministic adjacent-RPS pair seeding (forces ≥1 bond formation).
        // Use direct worldGrid + registry registration so adjacency is guaranteed.
        seedAdjacentBondingPair("seed-pred", "seed-prey", 5, 5, 5, 6,
            Entity.ParticleType.CATALYST, Entity.ParticleType.CATALYST.prey(), 200);
        // CHECKER-ROUND-3 WARNING #4 — post-seed worldGrid assertion: confirms
        // the seeded pair actually landed before the main tick loop drives bond formation.
        assertThat(worldGrid.getCell(5, 5).occupant()).isInstanceOf(Entity.Particle.class);
        assertThat(worldGrid.getCell(5, 6).occupant()).isInstanceOf(Entity.Particle.class);

        seedAdjacentBondingPair("seed-pred-2", "seed-prey-2", 9, 9, 9, 10,
            Entity.ParticleType.MEMBRANE, Entity.ParticleType.MEMBRANE.prey(), 200);
        assertThat(worldGrid.getCell(9, 9).occupant()).isInstanceOf(Entity.Particle.class);
        assertThat(worldGrid.getCell(9, 10).occupant()).isInstanceOf(Entity.Particle.class);

        // Step 2: register the remaining bots randomly via attemptPlacementForTest.
        for (int i = 0; i < BOT_COUNT - 4; i++) {
            String entityId = "trace-bot-" + i;
            String sessionId = "trace-sess-" + i;
            Optional<Position> pos = handler.attemptPlacementForTest(
                entityId, Entity.ParticleType.values()[i % 3], 100);
            assertThat(pos).isPresent();

            // REVIEWS CONSENSUS-H5 — production signature: attachSession(WebSocketSession, int).
            WebSocketSession mockSession = mock(WebSocketSession.class);
            when(mockSession.isOpen()).thenReturn(true);
            when(mockSession.getId()).thenReturn(sessionId);
            sessionRegistry.register(mockSession);
            outboundSender.attachSession(mockSession, OUTBOUND_QUEUE_SIZE);

            botRegistry.register(sessionId, entityId, pos.get());
            liveEntityRegistry.register(entityId, pos.get(), Optional.of(sessionId));

            registeredSessionIds.add(sessionId);
        }

        // Step 3: drive ticks. (ActionFrame queueing — re-grep production API for the
        // actual queue method; if the only inbound path is per-session WebSocket
        // frames, the bond/composite formation in step 1 is sufficient to satisfy
        // CONSENSUS-H2's ≥1-formation requirement; ActionResolver paths are still
        // exercised by botRegistry-driven actions per tick.)
        for (int t = 0; t < TICK_COUNT; t++) {
            applicationEventPublisher.publishEvent(new TickEvent(t));
            awaitAllSessionQueuesDrained();
        }
    }

    private void seedAdjacentBondingPair(String predId, String preyId,
                                          int px, int py, int qx, int qy,
                                          Entity.ParticleType predType,
                                          Entity.ParticleType preyType,
                                          int energy) {
        Entity.Particle pred = Entity.Particle.spawn(predId, predType, energy);
        Entity.Particle prey = Entity.Particle.spawn(preyId, preyType, energy);
        assertThat(worldGrid.trySetEntity(px, py, pred)).isTrue();
        assertThat(worldGrid.trySetEntity(qx, qy, prey)).isTrue();
        liveEntityRegistry.register(predId, new Position(px, py), Optional.empty());
        liveEntityRegistry.register(preyId, new Position(qx, qy), Optional.empty());
        eligibleCellIndex.notifyChanged(px, py);
        eligibleCellIndex.notifyChanged(qx, qy);
    }

    /**
     * REVIEWS CONSENSUS-H3: post-queueDepth-zero `synchronized(session) {}`
     * barrier per registered session. The OutboundSender drain VT dequeues
     * frames BEFORE entering synchronized(session) { sendMessage; listener };
     * queueDepth hits 0 while the listener.onEmit may still be in-flight inside
     * the session monitor. Acquiring the monitor here is sufficient because
     * every writer holds it for the actual sendMessage + listener call (CLAUDE.md
     * "synchronized-session-monitor contract").
     */
    private void awaitAllSessionQueuesDrained() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L; // 2s timeout
        while (System.nanoTime() < deadline) {
            boolean allDrained = true;
            for (String sid : registeredSessionIds) {
                if (outboundSender.queueDepth(sid) > 0) { allDrained = false; break; }
            }
            if (allDrained) {
                // REVIEWS CONSENSUS-H3 — wait for any in-flight sendMessage / listener
                // to complete by acquiring the per-session monitor each writer holds.
                for (String sid : registeredSessionIds) {
                    WebSocketSession s = sessionRegistry.getSession(sid);
                    if (s != null) {
                        synchronized (s) {
                            // No-op body — acquiring + releasing the monitor is the barrier.
                        }
                    }
                }
                return;
            }
            Thread.sleep(1);
        }
        throw new IllegalStateException(
            "OutboundSender queues did not drain in 2s for sessions=" + registeredSessionIds);
    }

    /**
     * Read SimulationEngine bond/composite formation counters. The exact accessor
     * name may differ; if no explicit counter exists, count via
     * `liveEntityRegistry` deltas: track the count of `Entity.BondedPair` /
     * `Entity.CompositeMember` grid occupants since the last reset.
     */
    private long bondAndCompositeFormationCount() {
        long bonds = 0, composites = 0;
        for (LiveEntityRegistry.EntityEntry e : liveEntityRegistry.snapshot()) {
            int x = e.position().x(), y = e.position().y();
            var occ = worldGrid.getCell(x, y).occupant();
            if (occ instanceof Entity.BondedPair) bonds++;
            else if (occ instanceof Entity.CompositeMember) composites++;
        }
        return bonds + composites;
    }
}
```

**STEP 2 — First execution (BASELINE_PINNED expected):**

Run `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"`.
1. `golden-trace-phase19.json` does not exist.
2. Test asserts intra-run consistency + vacuous-baseline guards + bond/composite formation count > 0.
3. Test calls `writeBaseline(mapA)` → creates `src/test/resources/golden-trace-phase19.json`.
4. Test fails with `BASELINE_PINNED — re-run test`.
5. Commit the new file.

**STEP 3 — Second execution (BASELINE green):**
Re-run. With the file present, `loadExpectedDigests()` parses it; `assertThat(mapA).isEqualTo(expected)` passes.

**Caveats:**
- `WorldGrid.clear()`, `BotRegistry.clear()`, `LiveEntityRegistry.clearForTest()`, `WorldWebSocketHandler.resetSeed()`, `EligibleCellIndex.rebuildForTest()` — confirm each exists at execution time.
- The `attemptPlacementForTest` is PUBLIC per Plan 01 / REVIEWS CONSENSUS-H6.
- The `bondAndCompositeFormationCount` heuristic counts current grid occupants; for the run-difference check, capture before/after counts. If a SimulationEngine counter exists (`incBondFormation` / similar), prefer that — re-grep `paralife\\..*\\.formation\\.total` or similar Counter names in `AdmissionMetrics` / `SimulationMetrics` if the bean exists.
- DO NOT modify `golden-trace-phase19.json` outside this test. Re-pinning requires `rm` + re-run.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"</automated>
    <!-- First execution: BASELINE_PINNED red is EXPECTED. Commit the file, re-run; second execution must be GREEN. -->
  </verify>
  <acceptance_criteria>
    - File `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` exists.
    - **Package import correctness (REVIEWS CONSENSUS-H5):**
      `grep -c "import com.paralife.admission.OutboundSender" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
      `grep -c "import com.paralife.engine.TickEvent" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
      `grep -c "import com.paralife.websocket.OutboundSender" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0
      `grep -c "import com.paralife.websocket.TickEvent" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0
    - `grep -c "@SpringBootTest" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - **REVIEWS CONSENSUS-H2 — dense scenario:**
      `grep -cE "BOT_COUNT *= *30" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
      `grep -cE "TICK_COUNT *= *200" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
      `grep -cE "paralife\\.world\\.width=16|paralife\\.world\\.height=16" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2
      `grep -cE "paralife\\.bonding\\.bonding-probability=1\\.0" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
      `grep -cE "seedAdjacentBondingPair" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (called for 2 seed pairs)
      **CHECKER-ROUND-3 WARNING #4 — post-seed worldGrid assertions:**
      `grep -cE "worldGrid\.getCell\(5, 5\)\.occupant\(\)\)\.isInstanceOf\(Entity\.Particle" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -cE "worldGrid\.getCell\(5, 6\)\.occupant\(\)\)\.isInstanceOf\(Entity\.Particle" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -cE "worldGrid\.getCell\(9, 9\)\.occupant\(\)\)\.isInstanceOf\(Entity\.Particle" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -cE "worldGrid\.getCell\(9, 10\)\.occupant\(\)\)\.isInstanceOf\(Entity\.Particle" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -cE "bondAndCompositeFormationCount" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2
      `grep -cE "isGreaterThan\\(0\\)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (emit count + formation count)
    - **REVIEWS CONSENSUS-H3 — post-drain monitor barrier:**
      `grep -cE "synchronized \\(s\\) \\{" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -cE "for \\(String sid : registeredSessionIds\\)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (drain check + monitor barrier)
    - **REVIEWS MEDIUM-5 — cross-run cleanup:**
      `grep -cE "outboundSender\\.detachSession" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (resetAll + tearDown)
      `grep -cE "sessionRegistry\\.unregister" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2
    - **REVIEWS MED-1 — resource pinning:**
      `grep -c "BASELINE_PINNED" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -c "writeBaseline\\|Files.writeString" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
      `grep -c "golden-trace-phase19.json" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2
      `grep -c "Map.of\\|Map.ofEntries" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0 (digests live in JSON, not Java source)
    - **REVIEWS H4 / L3:**
      `grep -c "EMPTY_SHA256_HEX" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
    - **REVIEWS H5 (TickEvent driver):**
      `grep -c "applicationEventPublisher.publishEvent(new TickEvent" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1
    - `grep -c "outboundSender.setFrameEmitListener" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (set + clear)
    - `grep -c "outboundSender.attachSession(mockSession, OUTBOUND_QUEUE_SIZE" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS MED-4 — production signature shape)
    - **Final state:** `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (Pass 2 green).
    - **Pinned baseline:** `test -f src/test/resources/golden-trace-phase19.json` AND `jq -r 'keys|length' < src/test/resources/golden-trace-phase19.json` >= 1 AND `grep -cE "[\"][0-9a-f]{64}[\"]" src/test/resources/golden-trace-phase19.json` >= 1
    - `./gradlew test` exits 0 (full regression remains green)
  </acceptance_criteria>
  <done>GoldenTraceEquivalenceTest passes against the post-Plan-02 baseline. All Round 2 + Round 3 consensus blockers closed in plan body: H2 dense scenario with bond/composite formation gate; H3 post-drain synchronized(session) barrier; H5 correct package imports; MEDIUM-5 cross-run cleanup; MED-1 resource pinning; H4/L3 vacuous-baseline guards. Plan 04 cannot land without keeping this test green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Test → production seam | OutboundSender.setFrameEmitListener is test-only; production listener is null. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation |
|-----------|----------|-----------|-------------|------------|
| T-19-08 | Tampering | Test seam left wired in production | mitigate | Listener defaults to null. |
| T-19-09 | Repudiation | Plan 04 changes observable output undetected | mitigate | EXPECTED_DIGESTS pinned via resource (REVIEWS MED-1). |
| T-19-09a | Tampering | EXPECTED_DIGESTS re-pinned silently | mitigate | Re-pinning requires `rm` of resource — diff visible in code review. |
| T-19-09b | Tampering | Vacuous baseline | mitigate | REVIEWS H4 / L3 — emitCount > 0 + no-empty-digest. |
| T-19-09c | Tampering | Cross-session emit-order non-determinism | mitigate | Per-session digest map. |
| T-19-09d | Tampering | Test exercises partial pipeline | mitigate | REVIEWS H5 — TickEvent publishEvent. |
| T-19-09e | Tampering | Drain-await race produces flaky digests | mitigate | REVIEWS CONSENSUS-H3 — post-loop synchronized(session) barrier. |
| T-19-09f | Tampering | Sparse scenario hides regressions | mitigate | REVIEWS CONSENSUS-H2 — 30×16×200 + bond formation gate. |
| T-19-09g | Tampering | Cross-run state leaks (duplicate VTs) | mitigate | REVIEWS MEDIUM-5 — detachSession + sessionRegistry.unregister in resetAll. |
| T-19-09h | Compile error | Wrong package import | mitigate | REVIEWS CONSENSUS-H5 — pre-flight grep. |
| T-19-09i | Tampering | Listener catches Throwable, swallows OOM | mitigate | REVIEWS LOW-10 — catch Exception only. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — passes with resource file pinned.
- `test -f src/test/resources/golden-trace-phase19.json` — exists with real 64-char hex digests.
- `./gradlew test` — full regression green.
- FrameEmitListener seam exists; production listener null; catch Exception (not Throwable).
- `awaitAllSessionQueuesDrained` does both queueDepth==0 AND `synchronized(session)` barrier (REVIEWS CONSENSUS-H3).
- Tick driver is `applicationEventPublisher.publishEvent(new TickEvent(t))` (REVIEWS H5 + CONSENSUS-H5).
- 30 bots × 16×16 × 200 ticks scenario; ≥1 bond/composite formation asserted (REVIEWS CONSENSUS-H2).
- resetAll detaches sender VTs + unregisters sessions (REVIEWS MEDIUM-5).
- Imports: `com.paralife.admission.OutboundSender`, `com.paralife.engine.TickEvent` (REVIEWS CONSENSUS-H5).
</verification>

<success_criteria>
- D-10 semantic-equivalence gate exists and is green BEFORE Plan 04 begins.
- All Round 2 + Round 3 consensus blockers closed in plan body: H2/H3/H5 (this plan touches H5 most directly via the OutboundSender + TickEvent imports), MED-1, MEDIUM-5, LOW-10.
- The test captures actual wire output post-sendMessage.
- The pinned digest map operationalises D-10.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-03-SUMMARY.md`.
</output>
