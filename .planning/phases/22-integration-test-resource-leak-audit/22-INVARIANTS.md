# Phase 22 — Load-Bearing Invariants

These are the assumptions Phase 22's test-infra fixes depend on. P22.1's review = diff of current code vs this list. Drift = re-validate or reimplement.

## I-01 WS:entity is 1:1

Per CLAUDE.md D-05 / D-21 (`.planning/phases/18-.../18-HARNESS.md` §1).

A1's safety story (close-then-interrupt detach + `synchronized(session)` monitor + per-session VT drain) **assumes a single owner** for each session's outbound queue. Multi-entity-per-session would invalidate the detach semantics.

If P20 introduces connection multiplexing in any form that violates 1:1, A1 must be redesigned, not retrofitted.

## I-02 OutboundSender detach overload semantics

`OutboundSender.detachSession(WebSocketSession)` (used by `WorldWebSocketHandler.afterConnectionClosed`):
- Closes the queue, interrupts the drain VT, **does not join**.
- Reason: caller holds `synchronized(session)` indirectly via WS close path; joining the drain VT can deadlock against the monitor.

`OutboundSender.detachSession(String sessionId)` (used by `markStalled`):
- Retains 100 ms bounded join.
- Reason: STALLED transition is initiated from a writer-side overflow callback, not a close path; the drain VT is allowed to finish its current `sendMessage` before the session is reissued via `ResumeTokenRegistry`.

**Do not collapse the overloads.** They serve different threading contexts.

## I-03 WorldGrid uses non-fair ReentrantReadWriteLock

`MetabolismIntegrationTest` timeout (B1) is read-lock starvation under continuous tick-loop write pressure on a non-fair lock. This is an architectural property.

If P20 changes the lock model (fair lock, StampedLock, segmented locking, partition-aware execution), the test may pass without targeted work. Conversely, if the lock model is unchanged, P22.1 must apply a real fix (pin a fair lock for this test, or restructure the read path).

## I-04 forkEvery=1 masks leaks rather than fixing them

Current state: `build.gradle.kts` sets `forkEvery=1` / `maxParallelForks=1` unconditionally. This isolates each test class in its own JVM, so leaked threads from one test cannot starve subsequent tests.

P22 exit gate (deferred to P22.1): `forkEvery=0` + **<100 live threads** at end of suite + zero "did not exit" warnings + zero "Could not write XML" errors.

Do **not** remove `forkEvery=1` until that exit gate passes.

## I-05 5-minute global JUnit timeout via SEPARATE_THREAD mode

`src/test/resources/junit-platform.properties` enforces a 5 m default timeout per test method, in `SEPARATE_THREAD` mode so parked threads can be interrupted (not just SAME_THREAD-detected).

Any test that legitimately needs longer than 5 m must declare `@Timeout(value = N, unit = ...)` explicitly.

## I-06 Bounded join for any test spawning concurrent VTs

`WorldGridTest.concurrentReadsDontBlock` was the trigger incident. Pattern: any test that spawns N worker virtual threads and joins them must use a bounded `Thread.join(Duration)` deadline with an explicit failure message naming carrier-starvation as the suspect cause.

Unbounded `Thread.join()` is the canonical 2-hour-hang anti-pattern in this codebase.

---

## Diff procedure for P22.1

For each invariant above:
1. Read the referenced code/config.
2. If unchanged since P22 close commit: invariant holds, skip.
3. If changed: assess whether the dependent P22 fix is still valid.
   - If yes: note the assessment, leave fix in place.
   - If no: re-design the fix in P22.1 scope.

Cross-reference: `22-SUMMARY.md` (this phase's close-out) and `19-MULTI-REVIEW-pass3-VALIDATED.md` (out-of-scope simulation/codec fixes handled by separate agent).
