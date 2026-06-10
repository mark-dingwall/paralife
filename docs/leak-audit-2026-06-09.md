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

## Follow-up: cache-cap discriminator experiment (2026-06-10)

To test whether the 268 is *legitimate per-context pressure* (evicts on `context.close()`) or a
*leak* (survives eviction), a reusable probe was added — `leakProbe` Gradle task + opt-in
`LeakCensusListener` (`com.paralife.probe`, inert unless `-Dparalife.leakprobe` is set, so the
pinned `forkEvery=1` `test` task is untouched). It runs a curated set of heavy RANDOM_PORT classes
— each with a distinct `@TestPropertySource`, hence a distinct cached context — in one shared JVM
(`forkEvery=0`) and dumps an end-of-suite platform-thread census.

Set of **6 classes → 6 distinct cached contexts** (HundredBot, StallRecovery, Metabolism,
WebSocketIntegration, PerceptionAction, BotFleet). Same set, two cache sizes:

| Bucket | `maxSize=32` (uncapped) | `maxSize=1` | Scales with contexts? |
|---|---:|---:|---|
| `qtp` (Jetty server pool) | 43 | 7 | yes |
| `qtp…acceptor…ServerConnector` | 6 | 1 | yes — exactly 1 per cached context |
| `Scheduler` | 6 | 1 | yes |
| `ForkJoinPool-worker` (VT carriers) | 6 | 6 | no — JVM-shared |
| JVM floor | ~13 | ~13 | no |
| **TOTAL** | **74** | **28** | |

Fitted law: `total ≈ 19 (floor) + ~9 per coexisting cached context` (6 → 73≈74; 1 → 28). Zero
"did not exit" / "Could not write XML" warnings; both runs ~23 s.

**Verdict:** every non-floor bucket scaled down with the cache — i.e. reclaimed by graceful
`context.close()`. **No leak residue** (no bucket stayed constant while contexts were evicted). This
confirms the conclusion above empirically: the count is legitimate per-context Jetty infrastructure,
and **`spring.test.context.cache.maxSize` linearly bounds it** — a measured path to the P22.1 exit
gate (`forkEvery=0` + <100 threads), not another band-aid. The 6-class set is already 28 threads at
`maxSize=1`.

**Correction to the suspect list:** the ~24 `HttpClient@…-scheduler` threads flagged above were
**absent in both runs despite HundredBot being in the set** — so `HundredBotIntegrationTest` is
*exonerated* as the client-scheduler source. The lingering schedulers originate in one of the 3
classes from the original 9-set not included here; that remains the open lead for client-side
`WebSocketClient` stop-hygiene (out of this branch's scope).

## Fixed on this branch

- `fix(websocket)`: `handleTransportError` holds stalled entity for the grace sweep (TD-20-01c-E)
  + regression test `stalledTransportError_holdsEntityForGraceSweep`.
- `test/harden`: `OutboundSender` `@PreDestroy` mass-detach (abrupt-shutdown defense);
  `BotClientHandshakeHeaderTest` accept-VT join; `LiveEntityRegistryTest` `awaitTermination`.
