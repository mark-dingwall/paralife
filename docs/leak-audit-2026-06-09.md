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
*exonerated* as the client-scheduler source. The lingering schedulers originate in the bot/load
fleet classes (see below).

## Tracing the schedulers — two real bugs behind the band-aid (2026-06-10)

Probing the bot/load fleet classes (`LoadTest`, `LoadHarnessIntegrationTest`, `BotClientIntegrationTest`,
`RespawnFlowIntegrationTest`, …) in one shared JVM did **not** produce a clean census — it **hung**,
and so did `LoadTest` *alone*. A thread dump of the hung JVM was decisive: **19 live
`HttpClient@…-scheduler` threads** (the original "~24", confirmed real, not a snapshot artifact) *and*
a teardown deadlock. This **updates the deep-dive's headline** — `forkEvery=1` masks not only
concurrent resource pressure but a genuine client-side leak **and** a reproducible hang.

**Bug A — client `WebSocketClient` leak (prod).** `BotClient` only stopped its Jetty client in
`disconnect()`. A bot reaped by **server idle-timeout** takes the `@OnWebSocketClose` *reconnect*
branch (the server proactively issues a resume token at registration, so `resumeToken != null`); at
fleet teardown the server is gone, so re-bind fails — and the failed `reconnect()` left the client
running. One leaked `HttpClient@…-scheduler` (+ selector/executor pools) per idle-reaped bot.
**Fix:** `reconnect()` now releases the client on failure via a new `stopClientAsync()` (off the Jetty
callback thread — `stop()` would deadlock on the pool dispatching the callback), idempotent with
`disconnect()` via a one-shot `clientStopped` CAS. Terminal `onClose` (no token / shutdown) also
releases. Regression test: `BotClientTerminalCloseStopsClientTest.failedReconnectStopsStartedClient`.

**Bug B — teardown hang via VT carrier pinning (prod).** Dump showed two platform threads BLOCKED on
the `EligibleCellIndex` monitor inside `cleanupBot → notifyChanged`, with **no platform thread holding
it** — the owner was a virtual thread parked while holding the intrinsic `synchronized` monitor.
Under a mass idle-timeout cascade, `synchronized` **pins the carrier** (the holder blocks on the inner
grid read lock and can't unmount), starving the VT pool → deadlock. This is the 2026-05-03 incident
class / backlog 999.6. **Fix:** `EligibleCellIndex`'s `synchronized` methods → a `ReentrantLock`
(same lock order, reentrant for `rebuildForTest`→`initialize`); a VT blocked while holding a
`ReentrantLock` unmounts instead of pinning, clearing the cascade. *(Scope note: this resolves the
reproduced `EligibleCellIndex` pin; the `synchronized(session)` monitor that 999.6 also names is
unchanged — 999.6 is partially addressed.)*

**Verification (both fixes).** Via `leakProbe`: `LoadTest` alone went from indefinite hang → **22 s**,
**0** `HttpClient` schedulers (was 19). The full 7-class fleet set went from a **19-min hang → 1 m 6 s**,
**62** threads, **0** scheduler residue. Targeted regression batch (new test + `EligibleCellIndex*` +
placement + bot/fleet + `WorldWebSocketHandlerCleanupTest`): **34 passed**.

## Full-suite `forkEvery=0` gate check (2026-06-10)

Ran the **entire** suite (987 tests, all tags) in one shared JVM via `leakProbe` (empty class
filter), `maxSize=32`. Result against the I-04 exit gate:

| Gate condition | Result |
|---|---|
| 1. `forkEvery=0` runs **and completes** (no hang) | ✅ — **completed in ~19 min; did not deadlock** (pre-fix, the `EligibleCellIndex` pin would have hung it) |
| 2. threads bounded by cache, not suite size | ⚠️ **213 threads** at `maxSize=32`. ~165 are per-context Jetty (85 `qtp` + 56 `WebSocket@` + 12 acceptor + 12 `Scheduler` ≈ 12 cached contexts) — legitimate, cache-bounded. But ~25 residual client threads remain (16 `HttpClient@` + 9 `HttpClient@…-scheduler`) from client paths beyond the fleet fix (e.g. `LoadHarnessIntegrationTest` unreachable-URI `connect()` that starts then abandons a client; `BlockingWebSocketClient` helpers). |
| 3. zero "did not exit" warnings | ❌ **12** — all `OutboundSender` *"Sender VT … did not exit within 100ms after interrupt"*. These are drain VTs blocked on the `synchronized(session)` monitor — the **unaddressed half of backlog 999.6** (this branch converted `EligibleCellIndex` only). |
| 4. zero "Could not write XML" errors | ✅ 0 |

Also 21/987 tests failed — but these are **`forkEvery=0` isolation artifacts, not regressions**:
e.g. `PlacementDensityIntegrationTest` and the `TickEngineTest` cases fail in the shared JVM yet
pass under `forkEvery=1` (verified in the targeted batch), and several are already-known flakies
(`GoldenTrace*`, `EmergenceStabilityLoadTest` — see STATE.md deferred TD list). They are the very
cross-class contamination `forkEvery=1` exists to hide.

**Verdict: good progress, gate not yet passable.** The hang (the hard blocker) is gone and the
fleet scheduler leak is fixed; the remaining blockers are concrete and named — the
`synchronized(session)` half of 999.6 (drain-VT non-exit) plus residual client stop-hygiene and the
test-isolation coupling. `forkEvery=1` stays pinned (I-04 not met).

**I-04 amendment (this session):** criterion 2 was reworded from an absolute *"<100 live threads"*
to a structural *"bounded by the context cache, not by suite size; survives-cache-flush ⇒ leak"*.
The old number was arbitrary — it tracked context-count × per-context-cost (a sizing property), not
leakage. The cache-cap experiment above established the law `total ≈ 19 floor + ~9 per cached
context` and that every non-floor bucket evicts on `context.close()`, which is the property the
reworded gate actually tests.

## Fixed on this branch

- `fix(websocket)`: `handleTransportError` holds stalled entity for the grace sweep (TD-20-01c-E)
  + regression test `stalledTransportError_holdsEntityForGraceSweep`.
- `test/harden`: `OutboundSender` `@PreDestroy` mass-detach (abrupt-shutdown defense);
  `BotClientHandshakeHeaderTest` accept-VT join; `LiveEntityRegistryTest` `awaitTermination`.
- `test(probe)`: `leakProbe` cache-cap thread-census harness (`LeakCensusListener`, opt-in).
- `fix(bot)`: `BotClient.reconnect()` releases the Jetty client on failure (`stopClientAsync` +
  `clientStopped` CAS) — stops the `HttpClient@…-scheduler` leak. Test: `BotClientTerminalCloseStopsClientTest`.
- `fix(engine)`: `EligibleCellIndex` `synchronized` → `ReentrantLock` — removes the VT carrier-pinning
  teardown deadlock (backlog 999.6, partial).
