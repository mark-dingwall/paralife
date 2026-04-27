---
phase: 17-durable-admission-control-backpressure
plan: 11
subsystem: testing
tags: [integration, tests, stalled-recovery, hysteresis, log-markers, edge-cases, blocking-ws-client]

requires:
  - phase: 17-07-WorldWebSocketHandler-refactor
    provides: markStalled, ATTR_STALL_TICK guard, sendOutOfBand, BACKPRESSURE resumed log
  - phase: 17-04-TickHealthMonitor
    provides: hysteresis gate with strict watermark + window-fill warmup
  - phase: 17-05-ResumeTokenRegistry
    provides: two-state ACTIVE/STALLED lifecycle, sweep, RebindOutcome
  - phase: 17-06-OutboundSender
    provides: per-session VT, fire-once overflow callback, frame-size metric
  - phase: 17-03-AdmissionMetrics
    provides: M_STALLED_SESSIONS gauge, recordFrameSize summary
provides:
  - End-to-end STALLED-pivot integration coverage (queue overflow → markStalled → close → rebind → respawn-count restore)
  - Tick-health hysteresis admission-gate verified via real Spring wiring incl. window-fill warmup
  - All D-19 log markers asserted via SUBSTRING matchers (no brittle regex)
  - Edge-case coverage: double-stall, concurrent rebind, offer-after-detach, sweep-throws, idempotent markStalled
  - BlockingWebSocketClient test utility for deterministic backpressure scenarios (M5/M6 reuse)
affects: [m2-uat, post-merge full-suite verification, future bot-client refactors]

tech-stack:
  added: []   # test-only — no production code or new build deps
  patterns:
    - "BlockingWebSocketClient — raw Jetty 12 WS client with latch-holdable onTextFrame; drives outbound queue overflow deterministically"
    - "ListAppender per-test attach/detach with synchronized snapshot — mirrors TestLogCapture but loosely coupled per-test"
    - "Substring log matchers (loose) — claude LOW review fix: tests survive harmless message tweaks"
    - "@SpringBootTest(webEnvironment=NONE) + ReflectionTestUtils-driven sample injection — TickHealthMonitor exercised via real bean wiring without TickEngine.start()"
    - "FakeSession with one-shot CountDownLatch that nulls itself on InterruptedException — prevents deadlock when markStalled detaches+sendOutOfBand on the same session"

key-files:
  created:
    - src/test/java/com/paralife/websocket/BlockingWebSocketClient.java
    - src/test/java/com/paralife/websocket/StallRecoveryIntegrationTest.java
    - src/test/java/com/paralife/admission/TickHealthGateIntegrationTest.java
    - src/test/java/com/paralife/admission/AdmissionLogMarkersIntegrationTest.java
    - src/test/java/com/paralife/admission/EdgeCasesIntegrationTest.java
  modified: []

key-decisions:
  - "Driving overflow via direct OutboundSender.offer() with client holding receive — TickEvent broadcasts alone are too lossy because TickBroadcaster pumps small frames the loopback TCP buffer absorbs without backpressuring the VT"
  - "Per-test isolation in @SpringBootTest classes via ReflectionTestUtils — singleton beans (TickHealthMonitor window, ResumeTokenRegistry cleanup callback) require explicit reset between methods because the Spring context is reused"
  - "MaintenanceStartup as a @Nested class with its own @TestPropertySource(maintenance=true) — the @PostConstruct log fires once at context init; nested context isolates that bootstrap path"
  - "Skipped Task 6 full-suite + JaCoCo gate per worktree handoff: 3 pre-existing failures from unmerged Plan 10 (population-cap migration) make full-suite execution misleading. Deferred to post-merge."

patterns-established:
  - "BlockingWebSocketClient: holdReceive(true) → spam direct OutboundSender.offer → BACKPRESSURE stalled fires deterministically within ~5000 offers on a 2-frame queue"
  - "FakeSession deadlock-safe latch: clear holdLatch on InterruptedException so post-detach sendOutOfBand calls don't re-block on the same latch"
  - "Log assertion idiom: assertThat(messages).anyMatch(m -> m.contains(prefix) && m.contains(keyField=)) — per claude LOW review"

requirements-completed: [SCALE-01, SCALE-02]

duration: ~50min
completed: 2026-04-27
---

# Phase 17 Plan 11: Integration Tests Summary

**Phase 17 acceptance gate authored — five test files (1 utility + 4 @SpringBootTest classes, 21 @Test methods) verify SCALE-01/SCALE-02 end-to-end via real Spring wiring.**

## Performance

- **Duration:** ~50 minutes
- **Started:** 2026-04-27T01:18Z (worktree spawn)
- **Completed:** 2026-04-27T02:25Z
- **Tasks:** 5/5 (Task 6 deferred — see "Deferred Gates" below)
- **Files created:** 5
- **Files modified:** 0
- **Tests added:** 21 @Test methods + 1 utility class

## Accomplishments

- **BlockingWebSocketClient test utility** — minimal raw Jetty 12 client with latch-holdable
  `onTextFrame`, ships ready for M5/M6 reuse. API: `connect`, `send`, `holdReceive`,
  `releaseReceive`, `received`, `awaitReceiveCount`, `awaitClose`, `close`.
- **StallRecoveryIntegrationTest** (5 @Tests) — full STALLED-pivot flow, including
  respawn-count restore (T-17-stallbypass) and idempotent markStalled fire-once.
- **TickHealthGateIntegrationTest** (5 @Tests) — hysteresis open/close, warm-up guard,
  in-band stability, AdmissionGate.evaluate observes the gate boolean.
- **AdmissionLogMarkersIntegrationTest** (6 @Tests) — every D-19 log marker verified via
  loose substring matchers (claude LOW review fix); no `.matches(` regex anywhere.
- **EdgeCasesIntegrationTest** (5 @Tests) — double-stall, concurrent rebind, offer after
  detach, sweep-throws, idempotent markStalled.

## Task Commits

Each task committed atomically against base `f1a4c87`:

1. **Task 1: BlockingWebSocketClient utility** — `101ac07` (test)
2. **Task 2: StallRecoveryIntegrationTest** — `a38bb98` (test)
3. **Task 3: TickHealthGateIntegrationTest** — `4bd6cb7` (test)
4. **Task 4: AdmissionLogMarkersIntegrationTest** — `fa3029f` (test)
5. **Task 5: EdgeCasesIntegrationTest** — `3cabf56` (test)

**Plan metadata commit:** to follow this SUMMARY.md.

## Files Created/Modified

- `src/test/java/com/paralife/websocket/BlockingWebSocketClient.java` (206 lines) — Jetty 12 raw client with holdable receive
- `src/test/java/com/paralife/websocket/StallRecoveryIntegrationTest.java` (391 lines) — end-to-end STALLED-pivot
- `src/test/java/com/paralife/admission/TickHealthGateIntegrationTest.java` (165 lines) — hysteresis gate
- `src/test/java/com/paralife/admission/AdmissionLogMarkersIntegrationTest.java` (364 lines) — D-19 markers
- `src/test/java/com/paralife/admission/EdgeCasesIntegrationTest.java` (249 lines) — robustness edge cases

Total: **1375 LOC of test code**, no production code touched, no `build.gradle.kts` deltas.

## BlockingWebSocketClient API Surface

For future M5/M6 reuse (e.g. UAT scripts, BotClient hardening):

```java
public class BlockingWebSocketClient {
    public void connect(URI uri, Duration timeout) throws Exception;
    public void send(String text) throws IOException;
    public void holdReceive(boolean hold);            // pause inbound on a latch
    public void releaseReceive();                     // release any in-progress hold
    public List<String> received();                   // snapshot of decoded text frames
    public boolean awaitReceiveCount(int count, Duration timeout);
    public boolean awaitClose(Duration timeout);
    public boolean isClosed();
    public int getCloseCode();                        // Jetty status code, -1 if not closed
    public String getCloseReason();
    public Throwable getLastError();
    public void close();                              // idempotent
}
```

The client negotiates `permessage-deflate; server_no_context_takeover` on upgrade
(D-33). No Jackson, no Spring StandardWebSocketClient — pure Jetty 12 native.

## Test-by-Test Coverage

| File                                  | @Tests | What it asserts                                                                                                  |
| ------------------------------------- | -----: | ---------------------------------------------------------------------------------------------------------------- |
| StallRecoveryIntegrationTest          | 5      | Token-rebind preserves entityId; expired-token = fresh registration; E\|408 + close on stalled inbound; respawn-count restored across rebind; markStalled fires exactly once |
| TickHealthGateIntegrationTest         | 5      | Warm-up guard; hysteresis open/close with TICK-HEALTH log markers; AdmissionGate.evaluate observes overload boolean; recovery; in-band samples don't flap |
| AdmissionLogMarkersIntegrationTest    | 6      | ADMISSION rejected world-full + tick-overload; ADMISSION maintenance state=on (nested); BACKPRESSURE stalled / expired / resumed — all via SUBSTRING matchers |
| EdgeCasesIntegrationTest              | 5      | convertToStalled idempotent; concurrent rebind 1-of-2 wins; offer-after-detach=false; sweep-removes-despite-callback-throw; markStalled idempotent |

**Total: 21 @Test methods, all passing in scoped run on this worktree.**

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] FakeSession deadlock on sendOutOfBand path**
- **Found during:** Task 4 (AdmissionLogMarkersIntegrationTest first run hung)
- **Issue:** WorldWebSocketHandler.markStalled detaches the OutboundSender VT (joining for 100ms) then immediately calls sendOutOfBand which invokes session.sendMessage. With a FakeSession that holds sendMessage on a CountDownLatch, the post-detach sendOutOfBand re-blocks on the SAME latch — eternal hang.
- **Fix:** FakeSession.sendMessage now nulls the holdLatch field when caught InterruptedException fires (interrupt comes from detachSession). Subsequent sendMessage calls bypass the latch immediately.
- **Files modified:** `src/test/java/com/paralife/admission/AdmissionLogMarkersIntegrationTest.java` (FakeSession inner class)
- **Commit:** `fa3029f`
- **Note:** This fix is local to the test FakeSession — the production OutboundSender / WorldWebSocketHandler interaction is unchanged.

**2. [Rule 3 - Blocking issue] Real-WS overflow drive too lossy via TickEvent broadcast**
- **Found during:** Task 2 (StallRecoveryIntegrationTest first run — `BACKPRESSURE stalled` never fired)
- **Issue:** Plan suggested publishing 5 TickEvents to drive overflow on a queue-size=2 outbound queue. With a 16×16 grid and tick-broadcaster pushing tiny T-frames, the loopback TCP receive buffer absorbed all frames before any backpressure registered server-side. Even 800 events didn't trip overflow. Worse, EnvironmentEngine + SimulationEngine listeners on each TickEvent triggered lightning strikes etc. that filled the grid and caused subsequent register attempts to receive `E|503|grid-full`.
- **Fix:** (a) `simulation.enabled=false` and `simulation.events.enabled=false` so listeners are quiescent; (b) `world.rock.density-threshold=255` so no rocks; (c) drive overflow via direct `outboundSender.offer()` calls in a tight loop (5000 iterations cap) — same code path that the live wiring uses, but fast enough to fill the bounded queue before the VT drains it.
- **Files modified:** `src/test/java/com/paralife/websocket/StallRecoveryIntegrationTest.java` `driveOverflow` helper + `@TestPropertySource`.
- **Commit:** `a38bb98`

**3. [Rule 1 - Bug] Test isolation across @SpringBootTest methods (singleton beans)**
- **Found during:** Task 3 (TickHealthGateIntegrationTest's `warmupSpikeDoesNotTrip` failed when other tests ran first)
- **Issue:** Spring's `TickHealthMonitor` bean is a singleton across @Test methods — its rolling-window `window`, `head`, `sum`, `filled`, `overloaded` fields persist. Prior tests' samples poisoned later assertions.
- **Fix:** `@BeforeEach` resets all five fields via `ReflectionTestUtils.setField`. Same pattern adopted in `AdmissionLogMarkersIntegrationTest`.
- **Files modified:** TickHealthGateIntegrationTest.java, AdmissionLogMarkersIntegrationTest.java
- **Commits:** `4bd6cb7`, `fa3029f`

**4. [Rule 1 - Bug] sweepRemovesEntryEvenIfCallbackThrows brittle to leftover STALLED entries**
- **Found during:** Task 5
- **Issue:** Other tests in the class leave STALLED entries in the singleton `ResumeTokenRegistry` map. When sweep-throws test calls `onTick(110)`, ALL leftover entries are reaped, inflating `callbackCalls`.
- **Fix:** Pre-sweep with a no-op callback at `Long.MAX_VALUE/2` clears any leftovers; then install the throwing callback; then issue + convert + sweep our specific entity. Assert `callbackCalls >= 1` AND `entityIdSeen == "e-sweep-throw"` (positive identification).
- **Files modified:** `src/test/java/com/paralife/admission/EdgeCasesIntegrationTest.java`
- **Commit:** `3cabf56`

### Authentication Gates

None.

## Deferred Gates (Task 6 — by handoff directive)

The plan's Task 6 calls for `./gradlew test -PincludeLong=true` full-suite green plus
`./gradlew jacocoTestReport`. **Both are deferred to post-merge.** The handoff message
from the orchestrator notes:

> 3 pre-existing tests fail at this base (`WorldWebSocketHandlerPopulationCapTest.populationCapEnforcedWithError429`,
> `WorldWebSocketHandlerTest.respawnCapEnforced`, `WorldWebSocketHandlerTest.malformedFrameProducesError400`).
> Plan 10 (running in parallel in another worktree) deletes/migrates them. Your worktree CONTAINS those failures — they are NOT your responsibility.

**Scoped run replacing Task 6 verify gate:** all 21 new @Test methods pass via

```bash
./gradlew test \
  --tests "com.paralife.websocket.StallRecoveryIntegrationTest" \
  --tests "com.paralife.admission.TickHealthGateIntegrationTest" \
  --tests "com.paralife.admission.AdmissionLogMarkersIntegrationTest" \
  --tests "com.paralife.admission.EdgeCasesIntegrationTest" \
  -PincludeLong=true
```

→ `BUILD SUCCESSFUL`.

Plus full compile gate:

```bash
./gradlew compileTestJava
```

→ `BUILD SUCCESSFUL`.

**Post-merge follow-ups (NOT this plan's responsibility):**
1. Run `./gradlew test -PincludeLong=true` after Plan 10 is merged — should be green.
2. Run `./gradlew jacocoTestReport` and confirm `com.paralife.admission` package coverage exceeds 70% line / 60% branch.
3. Confirm `LoadTest` still passes within the prior tolerance band (Plan 08's async tick-broadcast may shift frame arrival by < 1 tick — the plan notes a possible ±1 tick widening that may or may not be needed).

## Self-Check: PASSED

**Files verified to exist:**

```bash
[ -f src/test/java/com/paralife/websocket/BlockingWebSocketClient.java ] && echo FOUND
[ -f src/test/java/com/paralife/websocket/StallRecoveryIntegrationTest.java ] && echo FOUND
[ -f src/test/java/com/paralife/admission/TickHealthGateIntegrationTest.java ] && echo FOUND
[ -f src/test/java/com/paralife/admission/AdmissionLogMarkersIntegrationTest.java ] && echo FOUND
[ -f src/test/java/com/paralife/admission/EdgeCasesIntegrationTest.java ] && echo FOUND
```

All 5 files present. All 5 task commits (101ac07, a38bb98, 4bd6cb7, fa3029f, 3cabf56) present
in `git log f1a4c87..HEAD`.

`grep -c '\.matches(' src/test/java/com/paralife/admission/AdmissionLogMarkersIntegrationTest.java` → `0` (per plan acceptance criterion).
