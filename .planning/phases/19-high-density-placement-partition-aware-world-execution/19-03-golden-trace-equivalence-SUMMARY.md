---
phase: 19-high-density-placement-partition-aware-world-execution
plan: "03"
subsystem: testing
tags: [equivalence-gate, golden-trace, sha-256, determinism, java, spring-boot, websocket, outbound-sender]

requires:
  - phase: 19-01
    provides: EligibleCellIndex + PlacementDeterminismTest infrastructure
  - phase: 19-02
    provides: LiveEntityRegistry with snapshot()/clearForTest() for test iteration

provides:
  - OutboundSender.FrameEmitListener nested interface + setFrameEmitListener seam
  - GoldenTraceCapture per-session SHA-256 digest accumulator (test helper)
  - GoldenTraceEquivalenceTest: 30-bot 16x16 200-tick dual-run D-10 semantic-equivalence gate
  - golden-trace-phase19.json: pinned 26-session digest baseline (re-pin requires rm + re-run)
  - ActionResolver.clearStateForTest() clears lastReproducedTick + compositeTicksSinceMove
  - TickBroadcaster.clearStateForTest() clears lastRosterHashBySession roster-suppression cache

affects:
  - 19-04 (Plan 04 cannot land without keeping GoldenTraceEquivalenceTest green)
  - future phases modifying outbound frame encoding (PerceptionCodec, TickBroadcaster)

tech-stack:
  added: []
  patterns:
    - "FrameEmitListener seam: production-null test hook invoked INSIDE synchronized(session) after sendMessage — post-drain barrier covers in-flight callbacks"
    - "generate-if-missing JSON baseline: first run writes file + fails BASELINE_PINNED; second run asserts equality"
    - "comprehensive resetAll() pattern: every seeded Random + every per-entity ConcurrentHashMap cleared between in-test runs"

key-files:
  created:
    - src/main/java/com/paralife/admission/OutboundSender.java (FrameEmitListener interface + field + setter + drain-loop invocation)
    - src/test/java/com/paralife/engine/GoldenTraceCapture.java
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
    - src/test/resources/golden-trace-phase19.json
  modified:
    - src/main/java/com/paralife/engine/ActionResolver.java (clearStateForTest)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (clearStateForTest)

key-decisions:
  - "FrameEmitListener invocation placed INSIDE synchronized(session) after sendMessage — ensures the post-drain synchronized(session) barrier in tests covers in-flight callbacks (CONSENSUS-H3)"
  - "catch Exception (NOT Throwable) in listener invocation so OOM/SOE propagate per JVM contract (REVIEWS LOW-10)"
  - "Baseline generate-if-missing: first run computes, writes, fails BASELINE_PINNED; second run asserts equality — re-pinning is visible in code review (REVIEWS MED-1)"
  - "Two inter-run state leak sources identified and fixed: ActionResolver.lastReproducedTick (suppresses reproduction for bots reusing same entityId) + TickBroadcaster.lastRosterHashBySession (suppresses g-blocks incorrectly)"

patterns-established:
  - "GoldenTrace pattern: per-session SHA-256 digest map removes cross-session emit-order non-determinism; equality of mapA==mapB==EXPECTED proves byte-identical outbound encoding"
  - "resetAll() comprehensiveness: must clear ALL per-entity ConcurrentHashMaps in addition to seeded Randoms — two-run determinism requires clearing lastReproducedTick, compositeTicksSinceMove, lastRosterHashBySession"

requirements-completed:
  - SCALE-07

duration: 18min
completed: "2026-05-01"
---

# Phase 19 Plan 03: Golden Trace Equivalence Summary

**D-10 semantic-equivalence gate: per-session SHA-256 digest map across 30-bot 16x16 200-tick dual-run scenario, with pinned JSON baseline, proving byte-identical outbound WebSocket encoding before Plan 04 refactor lands**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-05-01T10:32:28Z
- **Completed:** 2026-05-01T10:50:58Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- `OutboundSender.FrameEmitListener` test seam wired into drain loop inside `synchronized(session)`, invoked after every successful `sendMessage`; production listener is always null
- `GoldenTraceCapture` accumulates per-session SHA-256 digests via `onEmit`, exposes `digestsAsHexMap()` + `emitCount()` + `EMPTY_SHA256_HEX` vacuous-baseline constant
- `GoldenTraceEquivalenceTest` drives two identical runs, asserts `mapA == mapB == EXPECTED_DIGESTS`, asserts emitCount > 0, no empty digests, and bond/composite formation > 0
- Pinned `golden-trace-phase19.json` baseline with 26 sessions (26 SHA-256 hashes); Plan 04 must not change these digests

## Task Commits

1. **Task 1: FrameEmitListener seam + GoldenTraceCapture** — `c822594` (feat)
2. **Task 2: GoldenTraceEquivalenceTest + baseline + state fixes** — `72465e4` (feat)

## Files Created/Modified

- `src/main/java/com/paralife/admission/OutboundSender.java` — Added `FrameEmitListener` nested interface, `volatile frameEmitListener` field, `setFrameEmitListener()` setter, listener invocation in `drainLoop` inside `synchronized(session)`; reuse `encodedBytes[]` to avoid second UTF-8 encode
- `src/test/java/com/paralife/engine/GoldenTraceCapture.java` — Per-session SHA-256 accumulator; thread-safe `onEmit`/`digestsAsHexMap()`/`reset()`; `EMPTY_SHA256_HEX` constant
- `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` — @SpringBootTest dual-run 30-bot 16x16 200-tick equivalence gate; all REVIEWS consensus fixes applied
- `src/test/resources/golden-trace-phase19.json` — Pinned 26-session digest baseline
- `src/main/java/com/paralife/engine/ActionResolver.java` — Added `clearStateForTest()` (clears `lastReproducedTick`, `compositeTicksSinceMove`, `pendingActions`, `pendingVoteBallots`)
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — Added `clearStateForTest()` (clears `lastRosterHashBySession` roster-suppression cache)

## Decisions Made

- Placed `FrameEmitListener` invocation INSIDE `synchronized(session)` rather than after: the post-drain `synchronized(session)` barrier in `awaitAllSessionQueuesDrained()` must cover the in-flight callback to prevent partial-capture races (CONSENSUS-H3)
- `catch (Exception e)` not `catch (Throwable e)` in listener invocation: REVIEWS LOW-10 requires OOM/SOE to propagate
- `resetAll()` calls `resetAll` then `capture.reset()` (not reverse): VTs must be joined before clearing the accumulator so late-firing `onEmit` callbacks from run 1's last tick don't pollute run 2's digests

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ActionResolver per-entity state not cleared between runs**
- **Found during:** Task 2 (GoldenTraceEquivalenceTest run 1 vs run 2 comparison)
- **Issue:** `ActionResolver.lastReproducedTick` (keyed by entityId) persisted across runs. Since both runs use identical entityIds (`trace-bot-0`…`trace-bot-25`), bot-9's reproduction was suppressed in run 2 at ticks where run 1's cooldown entry still applied, producing a different perception frame and diverging `trace-sess-9` digest
- **Fix:** Added `ActionResolver.clearStateForTest()` clearing `lastReproducedTick`, `compositeTicksSinceMove`, `pendingActions`, `pendingVoteBallots`; called in `resetAll()`
- **Files modified:** `src/main/java/com/paralife/engine/ActionResolver.java`
- **Committed in:** `72465e4` (Task 2 commit)

**2. [Rule 1 - Bug] TickBroadcaster roster-suppression cache not cleared between runs**
- **Found during:** Task 2 (persistent `trace-sess-9` digest divergence after fix 1 was insufficient)
- **Issue:** `TickBroadcaster.lastRosterHashBySession` caches the last composite-roster hash per session to suppress unchanged `g` blocks. Stale run-1 entries for `trace-sess-9` caused run 2 to suppress a `g` block that run 1 had sent (or vice versa), producing different frame bytes
- **Fix:** Added `TickBroadcaster.clearStateForTest()` clearing `lastRosterHashBySession`; called in `resetAll()`
- **Files modified:** `src/main/java/com/paralife/websocket/TickBroadcaster.java`
- **Committed in:** `72465e4` (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (both Rule 1 — deterministic inter-run state leaks)
**Impact on plan:** Both fixes required for the equivalence gate to be meaningful. State leaks were in production beans (ActionResolver, TickBroadcaster) but the fixes are test-only methods; no production behavior changed.

## Issues Encountered

Two inter-run state leaks caused `trace-sess-9` to diverge deterministically between run 1 and run 2 (always same sessions, always same hashes). The fix required adding `clearStateForTest()` to `ActionResolver` and `TickBroadcaster`. Both leaks were in `ConcurrentHashMap` fields keyed by `entityId`/`sessionId` that survive between in-test runs when entityIds are reused (which the test does by design for reproducibility).

## Threat Surface Scan

No new network endpoints, auth paths, or schema changes. `FrameEmitListener` seam is production-null; `clearStateForTest()` methods are test-only. No new threat surface beyond what the plan's threat model already covers (T-19-08 through T-19-09i).

## Next Phase Readiness

- D-10 semantic-equivalence gate is green and pinned before Plan 04 lands
- Plan 04 (`@Order`-chain entity-list refactor) can now be validated against this baseline
- Re-pinning the baseline requires `rm src/test/resources/golden-trace-phase19.json` + re-run (diff visible in code review)

---
*Phase: 19-high-density-placement-partition-aware-world-execution*
*Completed: 2026-05-01*
