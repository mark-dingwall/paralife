# Test/Code Resource-Leak Deep-Dive — 2026-06-09

Branch: `claude/test-resource-leak-audit`

## Why

`build.gradle.kts` pins `forkEvery = 1` / `maxParallelForks = 1` as a band-aid for the
2026-05-03 incident (a `WorldGridTest` hang behind ~497 live threads in a shared JVM; see
Phase 22 `SEED.md`). This deep-dive set out to find what the band-aid is actually masking —
whether by bug, flawed implementation, or test hygiene — before deciding whether it can be removed.

A first single-pass audit produced **mistaken** findings (it claimed 12 leaked tick threads while
`TickEngine.java:85` has a `@PreDestroy`). That miss is what motivated a rigorous,
verification-driven re-audit backed by empirical measurement.

## Method

1. **Three verification-driven static audits** (production concurrency lifecycle; production
   memory/handle leaks; test infrastructure). Each required file:line evidence for *both* the
   resource *and* its cleanup, a confidence rating, and a prod-vs-test classification.
2. **Empirical probe** — booted the real app, attached a registered WebSocket session, and
   measured actual `ws-sender-*` VT survival across context close (reading the
   `OutboundSender.senderThreads` map by reflection, because `Thread.getAllStackTraces()` does
   **not** enumerate virtual threads).
3. **`forkEvery=0` experiment** — ran 9 heavy integration classes (incl. the 100-client
   `HundredBotIntegrationTest`) together in one shared JVM with an end-of-suite platform-thread
   census.

## Corrections to the initial audit (all three re-audits concurred)

| Initial claim | Reality |
|---|---|
| "12 leaked tick threads" | `TickEngine` has `@PreDestroy stop()` (interrupt + join). Reclaimed on context close. |
| "~140–240 leaked threads" | Fabricated multipliers; no evidence. |
| "Context proliferation (12+ contexts)" | 62 `@SpringBootTest` classes dedup to **~4 distinct cached contexts**, far under Spring's cache ceiling of 32. |

## Empirical findings

**Probe (graceful path is clean):**

| Scenario | Result |
|---|---|
| Close context with client still connected | `ws-sender` VT alive → **0** within 5s (Jetty graceful stop fires `afterConnectionClosed → detachSession`) |
| Close client first (control) | VT alive → **0** |
| 3× boot→attach→close cycles (single config) | platform threads flat at **16 → 16 → 16 → 16** |

→ A *graceful* context close fully reclaims VTs and platform threads. The static audits' verdict
that `OutboundSender` VTs are "ORPHANED" is **wrong for the common path** — Jetty graceful stop
already detaches them. (It remains a real gap only on *abrupt* teardown; see hardening below.)

**`forkEvery=0` experiment (concurrent pressure is real):** end-of-suite census for **9 classes**:

```
total live platform threads: 268
  160 + 8  WebSocket@…        (Jetty server/connection threads)
   47 + 6  qtp…               (Jetty QueuedThreadPool, ~6 cached server contexts)
   ~24     HttpClient@…-scheduler  (WebSocketClient client pools)
    7      ForkJoinPool-…-worker   (VT carriers)
```

Captured at `testPlanExecutionFinished` — i.e. while contexts are still **cached** (Spring closes
them at JVM shutdown). So 268 is the *concurrent cached-context high-water mark*, not
post-close residue. It is **not** an orphan leak; it is the resource pressure that `forkEvery=1`
eliminates by guaranteeing one context per JVM. Two observations worth follow-up:
the ~24 surviving client `HttpClient` schedulers suggest some integration tests don't fully stop
their `WebSocketClient` instances, and the 168 `WebSocket@` threads scale with concurrent cached
contexts.

## Confirmed findings

| # | Finding | Type | Confidence | Status |
|---|---|---|---|---|
| 1 | **`LiveEntityRegistry` stale entry on stalled-session transport error** — `handleTransportError` called `cleanupBot` unconditionally; post-`markStalled` the entityId is null, so it cleared the held grid cell while skipping `liveEntityRegistry.unregister`, breaking the grace-hold (TD-20-01c-E). | Prod correctness leak | Confirmed | **FIXED** this branch |
| 2 | `OutboundSender` had no `@PreDestroy` mass-detach. Per-session detach only on connection-close. | Prod gap | Confirmed, empirically benign on graceful path | **HARDENED** this branch |
| 3 | `AdmissionMetrics.bucketTagsByEntityId` grows with respawn count; current removal paths clean but fragile. | Prod design risk | Likely | Documented; no change |
| 4 | `BotClientHandshakeHeaderTest` stub-accept VT not joined in `@AfterEach`. | Test | Likely, low | **FIXED** this branch |
| 5 | `LiveEntityRegistryTest` executor `shutdown()` without `awaitTermination`. | Test | Confirmed | **FIXED** this branch |

No other true leaks found. Registries (SessionRegistry, ResumeTokenRegistry, BotRegistry,
BuffRegistry, CompositeRegistry, EligibleCellIndex, OutboundSender maps) have complete removal
coverage across all exit transitions per the memory audit.

## Can `forkEvery=1` be removed?

**Not by a single leak fix.** There is no orphan-leak bug that survives graceful close. The band-aid
masks *concurrent cached-context resource pressure*, not a leak. Removing it safely requires:

- Bound `spring.test.context.cache.maxSize` so fewer heavy Jetty+tick contexts coexist.
- Ensure every integration test fully stops its `WebSocketClient`s (the ~24 lingering
  `HttpClient` schedulers indicate gaps — `HundredBotIntegrationTest` is the prime suspect).
- Consider `@DirtiesContext(AFTER_CLASS)` on the heaviest `RANDOM_PORT` + tick classes.
- Address carrier pinning (backlog 999.6: `synchronized(session)` → `ReentrantLock`), which was
  the proximate cause of the original hang.

These are test-architecture and runtime-tuning levers, properly the domain of Phase 22.1.

## Fixed on this branch

- `fix(websocket)`: `handleTransportError` holds stalled entity for the grace sweep (TD-20-01c-E)
  + regression test `stalledTransportError_holdsEntityForGraceSweep`.
- `test/harden`: `OutboundSender` `@PreDestroy` mass-detach (abrupt-shutdown defense);
  `BotClientHandshakeHeaderTest` accept-VT join; `LiveEntityRegistryTest` `awaitTermination`.
