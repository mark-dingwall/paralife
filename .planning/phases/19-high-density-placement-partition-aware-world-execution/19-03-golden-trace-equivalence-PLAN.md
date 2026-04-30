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
  - src/test/resources/golden-trace-phase19.json
autonomous: true
requirements:
  - SCALE-07
tags: [equivalence, golden-trace, sha-256, determinism, java, spring-boot]

must_haves:
  truths:
    - "A seeded scenario can be driven twice in the same JVM and the per-session digest map of all outbound WebSocket frames is byte-identical between runs (proves the test harness itself is deterministic before any refactor lands)."
    - "GoldenTraceEquivalenceTest passes BEFORE Plan 04 (entity-list iteration) is merged — the test serves as the oracle for that refactor."
    - "Test captures outbound frame bytes via a test-only seam on OutboundSender, not by mocking individual handlers — captures the actual wire output observed by the WS layer."
    - "Digest is captured PER SESSION (Map<String, String> sessionId → SHA-256 hex), removing cross-session emit-order non-determinism (REVIEWS H4 / Codex H / OpenCode H)."
    - "EXPECTED_DIGESTS is loaded from a generate-if-missing JSON resource file at `src/test/resources/golden-trace-phase19.json` (REVIEWS MED-1). First run with absent file: test computes digests, writes file, fails with `BASELINE_PINNED — re-run test, file written to {path}`. Second run reads file → asserts equality. Subsequent CI runs are read-only. NO manual hex-into-Java-source step."
    - "Test asserts emitCount > 0 AND digest map non-empty AND no per-session digest equals the SHA-256 of empty input (REVIEWS H4 / L3 — vacuous-baseline guards)."
    - "TickEvent dispatch drives the full @Order chain via `applicationEventPublisher.publishEvent(new TickEvent(t))` (REVIEWS H5)."
    - "The scenario uses real WS sessions (mock-but-registered into SessionRegistry + OutboundSender attachment) so TickBroadcaster.onTick does NOT skip every iteration (REVIEWS H4)."
    - "`awaitAllSessionQueuesDrained` iterates the explicit `registeredSessionIds` list built in driveScenario, NOT `capture.sessionsSeen()` — closes the first-tick race where sessionsSeen is empty before any drain has happened (REVIEWS HIGH-2)."
    - "Pre-flight signature check: Task 2 first reads `OutboundSender.java` and `SessionRegistry.java`, documents actual `attachSession` / `register` signatures, and adjusts `driveScenario` accordingly BEFORE writing the rest of the test (REVIEWS MED-4)."
    - "LiveEntityRegistry is in place (Plan 02 dependency satisfied)."
  artifacts:
    - path: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      provides: "@SpringBootTest dual-run determinism gate. Drives a 50-tick scenario with N bots at fixed seed via applicationEventPublisher.publishEvent(new TickEvent(t)) (REVIEWS H5), captures all outbound frame bytes per session, computes SHA-256 digest map, then resets and runs again. Loads EXPECTED_DIGESTS from `src/test/resources/golden-trace-phase19.json` (REVIEWS MED-1 — generate-if-missing). Asserts digestMapA == digestMapB AND digestMapA == EXPECTED_DIGESTS. Asserts emitCount > 0 and no per-session hex equals the empty-digest constant. Uses an explicit registeredSessionIds list for queue-drain awaits (REVIEWS HIGH-2)."
      min_lines: 180
    - path: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      provides: "Test-only frame-byte accumulator. Maintains a Map<String, MessageDigest> keyed by sessionId; each outbound frame from session S is hashed into digest[S] in send order. Exposes Map<String, String> hex digests + total emitCount + sessionsSeen."
      min_lines: 80
    - path: src/test/resources/golden-trace-phase19.json
      provides: "Pinned per-session SHA-256 digest map for the D-10 equivalence gate. Generated on first run; read-only on subsequent CI runs. Format: `{\"<sessionId>\":\"<64-hex>\",...}` (alphabetical, JSON-stringify-stable). Re-pinning requires deleting and re-running — visible in code review."
      min_lines: 1
  key_links:
    - from: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      to: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      via: "@Autowired wiring; test asserts hashMapA == hashMapB across two driven runs AND hashMapA == EXPECTED_DIGESTS (loaded from JSON resource); test also asserts emitCount > 0 and no per-session digest is the empty-digest constant"
      pattern: "GoldenTraceCapture"
    - from: src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
      to: src/test/resources/golden-trace-phase19.json
      via: "loaded via `getClass().getResourceAsStream(\"/golden-trace-phase19.json\")` at test setup; if absent, written on first run via `Files.writeString(Path.of(\"src/test/resources/golden-trace-phase19.json\"), json)` and test fails with BASELINE_PINNED message"
      pattern: "golden-trace-phase19\\.json"
    - from: src/test/java/com/paralife/engine/GoldenTraceCapture.java
      to: src/main/java/com/paralife/websocket/OutboundSender.java
      via: "Test-only listener hook on OutboundSender, called inside synchronized(session) immediately after session.sendMessage returns successfully"
      pattern: "OutboundSender"
---

<objective>
Build the **D-10 semantic-equivalence gate** — a `GoldenTraceEquivalenceTest` that captures all outbound WebSocket frame bytes during a fixed-seed scenario, computes a **per-session SHA-256 digest map**, asserts byte-equality across two consecutive runs, **and** asserts the map equals a pinned baseline loaded from a JSON resource file. This test must exist and pass **before** Plan 04 lands.

**REVIEWS revisions applied (this revision):**

- **HIGH-2 (gemini/codex/claude — drain race):** `awaitAllSessionQueuesDrained` now iterates an explicit `List<String> registeredSessionIds` populated in `driveScenario` Step 1, NOT `capture.sessionsSeen()`. The previous design had a vacuous truth: `sessionsSeen` is populated AS frames drain, so on the first tick `sessionsSeen.isEmpty()` and the loop returns "all drained" prematurely → next tick fires before previous tick's frames finish → digests non-deterministic. The fix tracks the known sessions at registration time.
- **MED-1 (claude/opencode — EXPECTED_DIGESTS pinning workflow):** EXPECTED_DIGESTS moved out of a `Map.of(...)` literal in Java source. The test loads it from `src/test/resources/golden-trace-phase19.json` (generate-if-missing pattern). First run with absent file: compute digests, write JSON, fail with `BASELINE_PINNED — re-run test, file written to {path}`. Second run reads JSON, asserts equality. Subsequent CI runs are read-only. Eliminates the manual two-pass "copy hex into Java source, recompile, re-run" workflow. Re-pinning requires deleting the file — conspicuous in code review (governs threat T-19-09a).
- **MED-4 (claude — pre-flight signature check):** Task 2's first action is to read `OutboundSender.java` and `SessionRegistry.java`, document the actual signatures of `attachSession` and `register` (`session.getId()`-derived vs explicit-id), and adjust `driveScenario` accordingly. Explicit grep gate: confirm `outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize())` shape (or whatever the production signature is). Adapt mock plumbing.
- **H4 (claude/codex/opencode):** Vacuous-baseline failure mode closed. `emitCount > 0` AND `digestMap.size() > 0` AND no per-session hex equals SHA-256 of empty input.
- **H5 (claude):** Full @Order chain driven via `applicationEventPublisher.publishEvent(new TickEvent(t))`.
- **Cross-session emit order (REVIEWS Codex H / OpenCode H):** per-session digest map sidesteps cross-session non-determinism.

Purpose: Without this gate, Plan 04 cannot prove D-10. The test serves as the merge-gate during execution and as the regression sentinel for any future tick-handler change.
Output: One new production-test seam on `OutboundSender` (test-only frame-emit listener), one test capture helper (per-session digest map), one @SpringBootTest equivalence gate, one JSON resource file pinning the baseline (generate-if-missing).

**Wave assignment:** Plan 03 sits in Wave 3 — depends on Plans 01 + 02. Plan 04 (Wave 4) blocks on this plan.
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

From src/main/java/com/paralife/websocket/OutboundSender.java (existing — Task 2 must re-grep for actual signatures per REVIEWS MED-4):
```java
// Phase 17 D-10: VT-per-session bounded queue.
public boolean offer(String sessionId, Frame frame);
public int queueDepth(String sessionId);
// attachSession / register signatures: confirm via grep before writing test
// (REVIEWS MED-4). The production wiring at WorldWebSocketHandler line 223 is:
//   outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize())
// — i.e. takes a WebSocketSession and an int queue size, derives id via session.getId().
```

NEW test seam (production code; minimal scope):
```java
@FunctionalInterface
public interface FrameEmitListener {
    void onEmit(String sessionId, byte[] frameBytes);
}

public void setFrameEmitListener(FrameEmitListener listener);   // null clears
```

From src/main/java/com/paralife/engine/TickEngine.java (line 112 — production tick driver):
```java
// applicationEventPublisher.publishEvent(new TickEvent(t));   // fires full @Order chain
```

From src/main/java/com/paralife/engine/LiveEntityRegistry.java (provided by Plan 02):
```java
public List<EntityEntry> snapshot();
public void clearForTest();
public int size();
public record EntityEntry(String entityId, Position position, Optional<String> sessionId) { }
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Add FrameEmitListener test seam on OutboundSender + GoldenTraceCapture per-session digest helper</name>
  <files>src/main/java/com/paralife/websocket/OutboundSender.java, src/test/java/com/paralife/engine/GoldenTraceCapture.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/OutboundSender.java (entire file — find the line where the drain VT calls `session.sendMessage(...)` inside `synchronized(session)`)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 200–230 — confirm `outboundSender.offer(sessionId, frame)` is the only enqueue call)
    - CLAUDE.md §"Outbound concurrency (Phase 17, D-10)"
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples — Golden-Trace Test Pattern)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H4 / Codex H / OpenCode H — per-session digest mandate)
  </read_first>
  <behavior>
    - listenerSeesAllOutboundBytes: register a listener; enqueue 3 frames to session "s1"; drain runs; listener.onEmit invoked exactly 3 times in send-order.
    - listenerCapturesPerSessionOrder: enqueue frames to "s1" and "s2" interleaved; per-session emit order matches per-session enqueue order.
    - listenerNullClearsHook: setFrameEmitListener(null); subsequent offers do not invoke any listener.
    - listenerExceptionDoesNotKillDrainLoop: a listener that throws RuntimeException must not break the drain VT.
  </behavior>
  <action>
1. **Modify** `src/main/java/com/paralife/websocket/OutboundSender.java`:

   (a) Add the functional interface:
   ```java
   @FunctionalInterface
   public interface FrameEmitListener {
       /** Invoked AFTER session.sendMessage succeeds; bytes are the actual wire output. Test-only. */
       void onEmit(String sessionId, byte[] frameBytes);
   }
   ```

   (b) Add a `private volatile FrameEmitListener frameEmitListener;` field and a setter.

   (c) In the drain loop, right after the successful `session.sendMessage(message)` inside `synchronized(session)`, add:
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
   Match exact variable names from existing code.

2. Create `src/test/java/com/paralife/engine/GoldenTraceCapture.java`:

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

/**
 * Phase 19 SCALE-07 D-10: per-session SHA-256 digest accumulator. REVIEWS H4 /
 * Codex H / OpenCode H — per-session digests sidestep cross-session VT
 * scheduling jitter.
 */
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

3. Run the full regression suite. Listener defaults to null in production.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "interface FrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` == 1
    - `grep -c "setFrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` >= 2
    - `grep -c "frameEmitListener.onEmit\|listener.onEmit" src/main/java/com/paralife/websocket/OutboundSender.java` >= 1
    - `grep -c "private volatile FrameEmitListener" src/main/java/com/paralife/websocket/OutboundSender.java` == 1
    - File `src/test/java/com/paralife/engine/GoldenTraceCapture.java` exists.
    - `grep -c "MessageDigest.getInstance(\"SHA-256\")" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "Map<String, MessageDigest>\\|digestsBySession" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "digestsAsHexMap" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `grep -c "EMPTY_SHA256_HEX\\|e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" src/test/java/com/paralife/engine/GoldenTraceCapture.java` >= 1
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>FrameEmitListener seam wired into OutboundSender at the post-sendMessage point inside synchronized(session); production listener is null; GoldenTraceCapture computes per-session SHA-256 digests; full regression suite green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Author GoldenTraceEquivalenceTest with resource-file digest pinning (REVIEWS MED-1) + explicit registered-sessions drain await (REVIEWS HIGH-2) + signature pre-flight (REVIEWS MED-4)</name>
  <files>src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java, src/test/resources/golden-trace-phase19.json</files>
  <read_first>
    - **REVIEWS MED-4 PRE-FLIGHT — first action of this task before writing any test code:**
      ```bash
      grep -nE "public.*attachSession|public.*register" src/main/java/com/paralife/websocket/OutboundSender.java src/main/java/com/paralife/websocket/SessionRegistry.java
      grep -nE "outboundSender\\.attachSession" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      ```
      Document the actual signatures (e.g. `attachSession(WebSocketSession session, int queueSize)` derives id internally vs `attachSession(String sessionId, WebSocketSession session)` takes id explicitly). Adjust `driveScenario` mock-session plumbing to match. If `WebSocketSession.getId()` is the production source of session id, the mock plumbing must `when(mockSession.getId()).thenReturn(sessionId)` and call the actual signature accordingly.
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — full template)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-01-placement-index-PLAN.md (Plan 01 — `attemptPlacementForTest` seam, `EligibleCellIndex.rebuildForTest()`)
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/engine/TickEngine.java (line 112 — `applicationEventPublisher.publishEvent` driver)
    - src/main/java/com/paralife/websocket/OutboundSender.java
    - src/main/java/com/paralife/websocket/SessionRegistry.java
    - src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java
    - src/test/java/com/paralife/engine/GoldenTraceCapture.java (helper from Task 1)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Plan 02 dependency)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-2, MED-1, MED-4, H4, H5, L3)
  </read_first>
  <behavior>
    - byteIdenticalOutputAcrossTwoRuns: drive a fixed-seed 50-tick scenario, capture digestsA; reset all state; drive again, capture digestsB; assert mapA equals mapB AND mapA equals EXPECTED_DIGESTS (loaded from resource file).
    - emitCountIsConsistent: capture.emitCount() identical across the two runs.
    - emitCountIsNonZero: capture.emitCount() > 0 (REVIEWS H4).
    - sessionsSeenIsNonEmpty: matches expected number of bots (REVIEWS H4).
    - noPerSessionDigestIsEmptyDigest: every (sessionId, hex) → hex != EMPTY_SHA256_HEX (REVIEWS L3 / H4).
    - firstRunMissingResourceWritesAndFails: with `golden-trace-phase19.json` absent, the test computes digests, writes them to that file, and fails with `BASELINE_PINNED — re-run test, file written to ...`. Subsequent runs (file present) succeed (REVIEWS MED-1).
    - awaitDrainTracksRegisteredSessionsNotSessionsSeen: `awaitAllSessionQueuesDrained` iterates a `List<String> registeredSessionIds` populated at session registration in `driveScenario`, NOT `capture.sessionsSeen()`. (REVIEWS HIGH-2.)
    - tickPipelineIsDrivenByTickEventPublisher: full @Order chain (REVIEWS H5).
  </behavior>
  <action>

**STEP 0 — REVIEWS MED-4 signature pre-flight (do this FIRST):**

Run the grep commands listed in `<read_first>`. Document the discovered signatures. Common shapes to expect:

- `OutboundSender.attachSession(WebSocketSession session, int queueSize)` (id derived via `session.getId()`) — production wiring uses this shape per WorldWebSocketHandler line 223.
- `SessionRegistry.register(WebSocketSession session)` (id derived via `session.getId()`) vs `SessionRegistry.register(String sessionId, WebSocketSession session)` (explicit id).

Adjust `driveScenario`'s mock-session plumbing to use the discovered signatures. If both signatures derive id from `session.getId()`, the mock setup is:
```java
WebSocketSession mockSession = mock(WebSocketSession.class);
when(mockSession.isOpen()).thenReturn(true);
when(mockSession.getId()).thenReturn(sessionId);
sessionRegistry.register(mockSession);   // adapt to actual signature
outboundSender.attachSession(mockSession, /*queueSize*/ 64);   // adapt to actual signature
```

Acceptance grep: `grep -c "outboundSender\\.attachSession" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 — the form must match the production signature shape.

**STEP 1 — Author the test:**

Create `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.OutboundSender;
import com.paralife.websocket.SessionRegistry;
import com.paralife.websocket.TickEvent;
import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * <p>REVIEWS revisions (HIGH-2 / MED-1 / MED-4 / H4 / H5 / L3 / Codex H / OpenCode H):
 * <ul>
 *   <li>HIGH-2: explicit `registeredSessionIds` for queue-drain awaits (no `sessionsSeen()` race).</li>
 *   <li>MED-1: EXPECTED_DIGESTS loaded from `src/test/resources/golden-trace-phase19.json`
 *       (generate-if-missing). No manual hex-into-Java-source step.</li>
 *   <li>MED-4: signature pre-flight performed; `attachSession` / `register` shapes match production.</li>
 *   <li>Per-session digest map; emitCount > 0; no-empty-digest guards; full @Order chain via
 *       TickEvent publisher.</li>
 * </ul>
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

    /** Resource path on the classpath. */
    private static final String RESOURCE_PATH = "/golden-trace-phase19.json";
    /** Source-tree path used to write the file on first-run pinning. */
    private static final Path SOURCE_PATH = Path.of("src/test/resources/golden-trace-phase19.json");

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
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
    }

    @Test
    void byteIdenticalOutputAcrossTwoRuns() throws Exception {
        // ---- Run 1 ----
        resetAll();
        driveScenario();
        long emitsA = capture.emitCount();
        Map<String, String> mapA = capture.digestsAsHexMap();

        // Vacuous-baseline guards (REVIEWS H4 / L3)
        assertThat(emitsA).as("REVIEWS H4 — emit count must be > 0").isGreaterThan(0);
        assertThat(mapA).as("REVIEWS H4 — digest map must be non-empty").isNotEmpty();
        mapA.forEach((s, h) -> assertThat(h)
            .as("REVIEWS L3 — per-session digest != SHA-256(empty) for session " + s)
            .isNotEqualTo(GoldenTraceCapture.EMPTY_SHA256_HEX));

        // ---- Run 2 ----
        capture.reset();
        resetAll();
        driveScenario();
        long emitsB = capture.emitCount();
        Map<String, String> mapB = capture.digestsAsHexMap();

        assertThat(emitsB).as("emit count stable across runs").isEqualTo(emitsA);
        assertThat(mapB).as("D-10 byte-identical outbound frames per session (run B vs A)").isEqualTo(mapA);

        // ---- EXPECTED_DIGESTS via resource file (REVIEWS MED-1) ----
        Map<String, String> expected = loadExpectedDigests();
        if (expected == null) {
            // First-run pinning: write the file and fail loudly.
            writeBaseline(mapA);
            fail("BASELINE_PINNED — re-run test, file written to " + SOURCE_PATH.toAbsolutePath()
                + ". The next run will load these digests as EXPECTED_DIGESTS and assert equality.");
        }
        assertThat(mapA)
            .as("D-10 EXPECTED_DIGESTS (loaded from " + RESOURCE_PATH
                + "); Plan 04 must not change this map")
            .isEqualTo(expected);
    }

    /**
     * Load the pinned baseline. Returns null if the resource is absent — the
     * caller writes the baseline and fails with BASELINE_PINNED.
     */
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
        // Sort by key for stable output; pretty-print for human review.
        Map<String, String> sorted = new TreeMap<>(digests);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted);
        Files.createDirectories(SOURCE_PATH.getParent());
        Files.writeString(SOURCE_PATH, json);
    }

    private void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        handler.resetSeed();
        // EligibleCellIndex.rebuildForTest() if applicable.
    }

    /**
     * Drive a deterministic 10-bot 50-tick scenario through the full @Order chain.
     * REVIEWS H5: full @Order via applicationEventPublisher.publishEvent.
     * REVIEWS HIGH-2: track registeredSessionIds explicitly for drain awaits.
     */
    private void driveScenario() throws Exception {
        for (int i = 0; i < 10; i++) {
            String entityId = "trace-bot-" + i;
            String sessionId = "trace-sess-" + i;
            Optional<Position> pos = handler.attemptPlacementForTest(
                entityId, Entity.ParticleType.values()[i % 3], 100);
            assertThat(pos).isPresent();

            // REVIEWS MED-4: signatures verified before this code was written.
            // Adjust the mock plumbing below to match the discovered signatures.
            WebSocketSession mockSession = mock(WebSocketSession.class);
            when(mockSession.isOpen()).thenReturn(true);
            when(mockSession.getId()).thenReturn(sessionId);
            sessionRegistry.register(mockSession);                       // adapt per pre-flight
            outboundSender.attachSession(mockSession, /*queueSize*/ 64); // adapt per pre-flight

            botRegistry.register(sessionId, entityId, pos.get());
            liveEntityRegistry.register(entityId, pos.get(), Optional.of(sessionId));

            // REVIEWS HIGH-2: track for explicit drain await.
            registeredSessionIds.add(sessionId);
        }

        for (int t = 0; t < 50; t++) {
            applicationEventPublisher.publishEvent(new TickEvent(t));
            awaitAllSessionQueuesDrained();
        }
    }

    /**
     * REVIEWS HIGH-2: iterate the explicit registeredSessionIds (built at session
     * registration), NOT capture.sessionsSeen() (which is populated AS frames
     * drain — empty on first tick → loop returns vacuously true → next tick fires
     * before previous tick's frames finish → digests non-deterministic).
     */
    private void awaitAllSessionQueuesDrained() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L; // 2s timeout
        while (System.nanoTime() < deadline) {
            boolean allDrained = true;
            for (String sid : registeredSessionIds) {
                if (outboundSender.queueDepth(sid) > 0) { allDrained = false; break; }
            }
            if (allDrained) return;
            Thread.sleep(1);
        }
        throw new IllegalStateException(
            "OutboundSender queues did not drain in 2s for registered sessions=" + registeredSessionIds);
    }
}
```

**STEP 2 — First execution (BASELINE_PINNED):**

Run `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"`. Expected outcome on first run:
1. `golden-trace-phase19.json` does not exist.
2. Test computes both maps, asserts internal consistency (Run B == Run A, vacuous-baseline guards pass).
3. Test calls `writeBaseline(mapA)`, which creates `src/test/resources/golden-trace-phase19.json`.
4. Test fails with `BASELINE_PINNED — re-run test, file written to {path}`.
5. The new file is on disk. Commit it.

**STEP 3 — Second execution (BASELINE green):**

Re-run the same gradle command. With the resource file present:
1. `loadExpectedDigests()` returns the parsed map.
2. `assertThat(mapA).isEqualTo(expected)` passes.
3. Test green.

If Step 3 is also red, that's a real failure (cross-run determinism broken or vacuous-baseline triggered). Investigate.

**Caveats:**

(a) `WorldGrid.clear()`, `BotRegistry.clear()`, `LiveEntityRegistry.clearForTest()`, `WorldWebSocketHandler.resetSeed()` — confirm each exists.

(b) The mock-session plumbing in `driveScenario` MUST match the production signatures discovered in the pre-flight. If both `sessionRegistry.register` and `outboundSender.attachSession` derive id internally via `session.getId()`, no explicit id parameter is needed.

(c) `liveEntityRegistry.register(entityId, pos.get(), Optional.of(sessionId))` matches Plan 02's revised signature with `Optional<String> sessionId`.

(d) **DO NOT** modify `golden-trace-phase19.json` outside this test. Re-pinning requires `rm src/test/resources/golden-trace-phase19.json` then re-running — visible in code review.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"</automated>
    <!-- First execution: BASELINE_PINNED red is EXPECTED — the test wrote the resource
         file. Commit the file, re-run; second execution must be GREEN. -->
  </verify>
  <acceptance_criteria>
    - **BASELINE pinned via resource (REVIEWS MED-1):** File `src/test/resources/golden-trace-phase19.json` exists after Pass 1; `test -f src/test/resources/golden-trace-phase19.json && jq -r 'keys|length' < src/test/resources/golden-trace-phase19.json` >= 1 (at least one session pinned)
    - `grep -cE "[\"][0-9a-f]{64}[\"]" src/test/resources/golden-trace-phase19.json` >= 1 (real 64-char lowercase-hex digests pinned)
    - `grep -c "REPLACE_ME_AFTER_FIRST_RUN\\|PLACEHOLDER" src/test/resources/golden-trace-phase19.json` == 0 (no placeholder strings)
    - `grep -c "Map\\.of\\|Map.ofEntries" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0 OR the only `Map.of` usages are NOT for EXPECTED_DIGESTS (REVIEWS MED-1 — digests live in JSON, not Java source)
    - File `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` exists.
    - `grep -c "@SpringBootTest" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "paralife.simulation.spawn.seed=42" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "byteIdenticalOutputAcrossTwoRuns" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 1
    - `grep -c "golden-trace-phase19.json" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (RESOURCE_PATH + SOURCE_PATH constants — REVIEWS MED-1)
    - `grep -c "BASELINE_PINNED" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS MED-1 — first-run pinning message)
    - `grep -c "writeBaseline\\|Files.writeString" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS MED-1 — first-run write path)
    - `grep -c "registeredSessionIds" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 3 (REVIEWS HIGH-2 — declared, populated, iterated in awaitDrain)
    - `grep -cE "for \\(String sid : registeredSessionIds\\)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS HIGH-2 — drain await iterates registered list, not sessionsSeen)
    - `grep -c "capture.sessionsSeen()" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` == 0 OR (if used) the use is NOT inside `awaitAllSessionQueuesDrained` (REVIEWS HIGH-2 — must not be the drain-iteration source)
    - `grep -c "isGreaterThan(0)" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS H4)
    - `grep -c "EMPTY_SHA256_HEX\\|isNotEqualTo(GoldenTraceCapture.EMPTY" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS L3)
    - `grep -c "applicationEventPublisher.publishEvent(new TickEvent" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS H5)
    - `grep -c "outboundSender.setFrameEmitListener" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 2 (set + clear in tearDown)
    - `grep -c "sessionRegistry.register\\|outboundSender.attachSession" src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` >= 1 (REVIEWS H4 + MED-4 — real-or-mock-but-registered sessions; signature matches production pre-flight)
    - **Final state:** `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (Pass 2 green; resource file present and pinned)
    - `./gradlew test` exits 0 (full regression remains green)
  </acceptance_criteria>
  <done>GoldenTraceEquivalenceTest exists and passes on the post-Plan-02 baseline. EXPECTED_DIGESTS loaded from `src/test/resources/golden-trace-phase19.json` via generate-if-missing pattern (REVIEWS MED-1 closed — no manual hex-into-Java-source step). `awaitAllSessionQueuesDrained` iterates explicit `registeredSessionIds` not `capture.sessionsSeen()` (REVIEWS HIGH-2 closed). Signature pre-flight performed before driveScenario authored (REVIEWS MED-4 closed). emitCount > 0 + no-empty-digest + tickEvent-driven-full-pipeline guards all asserted (REVIEWS H4 / H5 / L3). Plan 04 cannot land without keeping this test green against the pinned digests.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Test → production seam | `OutboundSender.setFrameEmitListener` is test-only; production listener is null. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-08 | Tampering | Test seam left wired in production | mitigate | Listener defaults to null; setter is the only wiring. |
| T-19-09 | Repudiation | Plan 04 changes observable output undetected | mitigate | EXPECTED_DIGESTS pinned via resource file (REVIEWS MED-1). |
| T-19-09a | Tampering | EXPECTED_DIGESTS re-pinned silently | mitigate | Re-pinning requires `rm src/test/resources/golden-trace-phase19.json` — diff visible in code review (REVIEWS MED-1). |
| T-19-09b | Tampering | Vacuous baseline | mitigate | REVIEWS H4 / L3 — emitCount > 0 + no-empty-digest. |
| T-19-09c | Tampering | Cross-session emit-order non-determinism | mitigate | Per-session digest map (REVIEWS Codex H / OpenCode H). |
| T-19-09d | Tampering | Test exercises partial pipeline | mitigate | REVIEWS H5 — explicit TickEvent publishEvent. |
| T-19-09e | Tampering | Drain-await race produces flaky digests | mitigate | REVIEWS HIGH-2 — explicit registeredSessionIds list, not sessionsSeen(). |
| T-19-09f | Compile-error regression | Mock-session signatures don't match production | mitigate | REVIEWS MED-4 — pre-flight grep on attachSession/register before authoring driveScenario. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — passes (with resource file pinned).
- `test -f src/test/resources/golden-trace-phase19.json` — file exists, contains real 64-char hex digests.
- `./gradlew test` — full regression green.
- FrameEmitListener seam exists; production listener null.
- `awaitAllSessionQueuesDrained` iterates `registeredSessionIds` (REVIEWS HIGH-2).
- Tick driver is `applicationEventPublisher.publishEvent(new TickEvent(t))` (REVIEWS H5).
</verification>

<success_criteria>
- Semantic-equivalence gate exists and is green BEFORE Plan 04 begins, with EXPECTED_DIGESTS loaded from a JSON resource (REVIEWS MED-1) and all REVIEWS H4/H5/L3/HIGH-2/MED-4 guards.
- The test captures the actual wire output post-sendMessage.
- The pinned digest map operationalises D-10 — any future change to any tick handler that alters observable output causes this test to fail loudly.
- D-10 is enforced as a CI-visible regression gate, not as a planning aspiration.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-03-SUMMARY.md`.
</output>
