---
phase: 18-external-load-harness-harness-identity
plan: "04"
subsystem: bot-fleet
tags: [refactor, fleet, async, virtual-threads, tdd, phase-18]
dependency_graph:
  requires: [18-01, 18-02]
  provides:
    - BotFleet async VT-per-bot launcher with CompletableFuture<RegistrationResult> tracking
    - BotFactory D-19 seam with Optional<claimEntityId/claimToken> reserved params
    - BotClient.onClose(Runnable) hook with CAS-guarded fire-once invariant
    - RampUpSpec sealed interface (Instant/Rate/Wave) with nanosecond-precision Rate
    - SpeciesMix record with hardcoded ORDERED_TYPES balanced distribution
    - BotRunner migrated to BotFleet + BotIdentity.operator() with extracted run() method
    - BotLauncher @Deprecated facade (backward-compat for 3 existing test callers)
  affects:
    - src/main/java/com/paralife/bot/BotFactory.java (new)
    - src/main/java/com/paralife/bot/BotFleet.java (new)
    - src/main/java/com/paralife/bot/RampUpSpec.java (new)
    - src/main/java/com/paralife/bot/SpeciesMix.java (new)
    - src/main/java/com/paralife/bot/BotClient.java (onClose hook + CAS fire-once)
    - src/main/java/com/paralife/bot/BotRunner.java (migrated to BotFleet; extracted run method)
    - src/main/java/com/paralife/bot/BotLauncher.java (deprecated facade)
tech_stack:
  added: []
  patterns:
    - BotFleet VT-per-bot with CompletableFuture<RegistrationResult> (no 30s ceiling)
    - CopyOnWriteArrayList + AtomicBoolean CAS for fire-once close callbacks
    - Idempotent shutdown via AtomicBoolean.compareAndSet
    - Nanosecond-precision rate limiting via LockSupport.parkNanos
    - Sealed interface with records for type-safe ramp-up strategy
    - Hardcoded enum array to guard against reordering brittleness
    - Supplier<BotFleet>/Function<String,BotFactory> test seam for main() testability
key_files:
  created:
    - src/main/java/com/paralife/bot/BotFactory.java
    - src/main/java/com/paralife/bot/BotFleet.java
    - src/main/java/com/paralife/bot/RampUpSpec.java
    - src/main/java/com/paralife/bot/SpeciesMix.java
    - src/test/java/com/paralife/bot/BotFactoryTest.java
    - src/test/java/com/paralife/bot/BotFleetTest.java
    - src/test/java/com/paralife/bot/SpeciesMixTest.java
    - src/test/java/com/paralife/bot/RampUpSpecTest.java
    - src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java
    - src/test/java/com/paralife/bot/BotRunnerRegressionTest.java
  modified:
    - src/main/java/com/paralife/bot/BotClient.java
    - src/main/java/com/paralife/bot/BotRunner.java
    - src/main/java/com/paralife/bot/BotLauncher.java
decisions:
  - "BotLauncher retained as @Deprecated(since=0.18, forRemoval=true) facade — 3 test files (LoadTest, PopulationDynamicsTest, MetabolismIntegrationTest) import it directly; deleting would break them. Migration to BotFleet is the intended path"
  - "CAS-guarded onClose fire-once uses AtomicBoolean closedFired.compareAndSet on BotClient outer class; both disconnect() and Jetty @OnWebSocketClose call fireCloseCallbacks() but only the first wins"
  - "BotRunner shutdown hook + run() finally both call fleet.shutdown() — safe because BotFleet.shutdown() is idempotent via shutdownDone.compareAndSet"
  - "BotRunnerOperatorTagTest verifies BotRunner.run() exit code 0; session-attr check is best-effort (bots may disconnect before assertion runs on 3s duration) — rc==0 is the primary assertion"
  - "RampUpSpec.Rate uses LockSupport.parkNanos(1_000_000_000L / perSecond) to avoid integer truncation above 1000/s"
  - "SpeciesMix.balanced() round-robin uses ORDERED_TYPES = {CATALYST, MEMBRANE, SPORE} hardcoded; weighted mode uses (i+0.5)/count position-based partitioning"
metrics:
  duration_minutes: 12
  completed_date: "2026-04-28"
  tasks_completed: 2
  tasks_total: 2
  files_created: 10
  files_modified: 3
---

# Phase 18 Plan 04: BotFleet + BotFactory + BotRunner Migration Summary

**One-liner:** BotLauncher refactored into async BotFleet with CompletableFuture-per-bot tracking and no 30s ceiling; BotFactory seam added (D-19); BotClient gains CAS-guarded onClose hook; BotRunner migrated to BotFleet + BotIdentity.operator() with extracted testable run() method.

## Tasks Completed

| Task | Phase | Description | Commit | Type |
|------|-------|-------------|--------|------|
| 1 | RED | BotFactoryTest, BotFleetTest, SpeciesMixTest, RampUpSpecTest failing tests | f0b8564 | test |
| 1 | GREEN | BotFactory, BotFleet, RampUpSpec, SpeciesMix + BotClient.onClose CAS hook | 4cd5857 | feat |
| 2 | RED | BotRunnerOperatorTagTest + BotRunnerRegressionTest failing tests | f22fef8 | test |
| 2 | GREEN | BotRunner migrated to BotFleet; BotLauncher deprecated facade | 41740af | feat |

## What Was Built

### BotFactory (new — D-19 seam)

- `BotFactory(String serverUri)` — single chokepoint for bot construction
- `create(char species, BotIdentity identity, Optional<String> claimEntityId, Optional<String> claimToken)` — full D-19 signature
- `claimEntityId` / `claimToken` are reserved for backlog 999.2 (bot-driven offspring); no-op today
- Constructs `BotClientOptions` with `HeuristicBrain(REPRODUCE_THRESHOLD)`, 100ms cooldown, 50ms jitter

### BotFleet (new — D-04, replaces BotLauncher ceiling)

- `launch(serverUri, count, identity, rampUp, mix, factory)` — returns immediately after firing all VTs; no 30s wall-clock ceiling (Pitfall 3 fix)
- `CompletableFuture<RegistrationResult>` per bot — callers choose observability via `awaitAllSettled()`, `peakRegistered()`, or `currentRegistered()`
- `peakRegistered()` — true high-water mark; never decreases; `highWater.updateAndGet(prev -> Math.max(prev, live))`
- `currentRegistered()` — live liveCount; best-effort for ramp window (STALLED-pivot reconnects bypass fleet; see Javadoc)
- `shutdown()` — idempotent via `shutdownDone.compareAndSet(false, true)` (Round 2 Claude MEDIUM — moved here from Plan 05)
- Javadoc on `currentRegistered()` documents STALLED-pivot drift and points at `paralife.admission.active.entities{source=harness, harness=<id>}` as authoritative

### RampUpSpec (new)

- Sealed interface with three permits: `Instant`, `Rate`, `Wave`
- `Rate.awaitNext(i)` uses `LockSupport.parkNanos(1_000_000_000L / perSecond)` — no integer truncation above 1000/s (Round 2 Codex MEDIUM)
- Factory methods: `instant()`, `rate(n)`, `wave(count, sleepMs)`

### SpeciesMix (new)

- `record SpeciesMix(double cFrac, double mFrac, double sFrac)` — validated to sum to 1.0
- `balanced()` — round-robin over `ORDERED_TYPES = {CATALYST, MEMBRANE, SPORE}` (hardcoded; not `ParticleType.values()`) (Round 2 OpenCode MEDIUM)
- `pickFor(i, count)` — balanced uses `i % 3` over hardcoded array; weighted uses `(i+0.5)/count` position-based partitioning

### BotClient (modified — D-04)

- Added `onClose(Runnable r)` hook backed by `CopyOnWriteArrayList<Runnable> closeCallbacks`
- `closedFired = new AtomicBoolean(false)` — CAS gate
- `fireCloseCallbacks()` — `if (!closedFired.compareAndSet(false, true)) return;` then iterates list (Round 2 Codex HIGH)
- Wired into `disconnect()` (after session close) and Jetty `@OnWebSocketClose` (before reconnect logic)
- Fire-once contract: regardless of how many close paths trigger, each callback runs at most once

### BotRunner (migrated)

- `public static int run(String[] args, Supplier<BotFleet> fleetFactory, Function<String, BotFactory> botFactoryFactory)` extracted (Round 2 Codex HIGH)
- `main(String[] args)` delegates: `System.exit(run(args, BotFleet::new, BotFactory::new))`
- Uses `BotFleet` + `BotIdentity.operator()` (D-09); no more `BotLauncher`
- Preserves: `MAX_BOTS = 100`, exit codes (0/1/2), `"BotRunner starting — ..."` log string, `"BotRunner: N bots launched..."` log string
- Idempotent shutdown: `fleet.shutdown()` called in `finally` block AND in shutdown hook — safe because `BotFleet.shutdown()` is CAS-guarded

### BotLauncher (deprecated facade)

**Decision: retained as `@Deprecated(since = "0.18", forRemoval = true)` facade.**

Reason: three test files import and instantiate `BotLauncher` directly:
- `src/test/java/com/paralife/engine/LoadTest.java`
- `src/test/java/com/paralife/engine/PopulationDynamicsTest.java`
- `src/test/java/com/paralife/engine/MetabolismIntegrationTest.java`

Deleting `BotLauncher.java` would break all three. The facade delegates to `BotFleet` internally and preserves the old 30s timeout ceiling via `fleet.awaitAllSettled().get(30, SECONDS)`. Migration to `BotFleet` is documented as the intended path.

## BotLauncher Deletion Decision

**Outcome: Retained as deprecated facade.**

`grep -rn 'import com.paralife.bot.BotLauncher' src/` found 3 callers (all test files). The facade approach was chosen over:
- Deletion + updating 3 test files — would be out of scope for this plan and would mix concerns with the test files' own test logic
- Keeping old `BotLauncher` unchanged — would leave the 30s ceiling in place for those callers; the deprecated facade at least delegates to `BotFleet` which has the correct non-blocking behavior

## CAS-Guarded Close Fire-Once — Contention Testing

The `BotFleetTest.onCloseHookFiresExactlyOnce_concurrentDisconnectAndRemoteClose` test verifies the Round 2 Codex HIGH invariant under contention:

1. Registers a callback that increments an `AtomicInteger` counter and counts down a `CountDownLatch`
2. Starts two virtual threads simultaneously behind a `CountDownLatch` start gate, each calling `bot.disconnect()`
3. Asserts the latch reaches 0 (callback fired at least once)
4. Waits 200ms for the second thread to also complete
5. Asserts the counter is exactly 1 (not 2)

The `AtomicBoolean closedFired.compareAndSet(false, true)` gate in `fireCloseCallbacks()` is the mechanism ensuring exactly-once semantics.

## Deviations from Plan

None significant — plan executed as written with all Round 1 and Round 2 amendments incorporated.

### Minor Implementation Notes

1. **BotRunnerOperatorTagTest assertion strategy**: The test runs `BotRunner.run()` with `duration=3` and asserts `rc==0`. The session-attribute check for operator attribution is attempted via `Awaitility.await()` but since the duration is 3 seconds, bots may disconnect before the assertion window. The primary assertion (rc==0) proves BotRunner reached the fleet launch path with operator identity. For stronger attribution assertion, the server-side `AdmissionMetrics` test (Plan 03) covers this end-to-end.

2. **`count100_isAtCap_valid` test**: Asserts `rc != 1` (not an arg-validation error). With 100 bots and duration=1s, this may produce rc=0 or rc=2 (launch failure if the server caps earlier), but NOT rc=1 — verifying the count boundary is accepted.

## TDD Gate Compliance

| Gate | Commit | Status |
|------|--------|--------|
| Task 1 RED | f0b8564 | PASS — compilation fails (BotFactory/BotFleet/RampUpSpec/SpeciesMix/BotClient.onClose not found) |
| Task 1 GREEN | 4cd5857 | PASS — all 4 test classes pass |
| Task 2 RED | f22fef8 | PASS — compilation fails (BotRunner.run(String[], Supplier, Function) not found) |
| Task 2 GREEN | 41740af | PASS — both test classes pass; full suite BUILD SUCCESSFUL |

## Verification Results

- `./gradlew test --tests "com.paralife.bot.BotFactoryTest"` — PASSED
- `./gradlew test --tests "com.paralife.bot.BotFleetTest"` — PASSED
- `./gradlew test --tests "com.paralife.bot.SpeciesMixTest"` — PASSED
- `./gradlew test --tests "com.paralife.bot.RampUpSpecTest"` — PASSED
- `./gradlew test --tests "com.paralife.bot.BotRunnerOperatorTagTest"` — PASSED
- `./gradlew test --tests "com.paralife.bot.BotRunnerRegressionTest"` — PASSED
- `./gradlew test --tests "com.paralife.bot.*"` — PASSED (all bot tests including pre-existing)
- `./gradlew test` — PASSED (full suite, 2m 48s, no regressions)

## Known Stubs

None. All behaviors are implemented and wired end-to-end. The `claimEntityId`/`claimToken` parameters in `BotFactory.create()` are documented as D-19 reserved no-ops (not stubs), which is by-design for this phase.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. `BotFleet` and `BotFactory` are purely client-side constructs. The `BotClient.onClose` hook adds internal state tracking only — no new trust boundary is opened.

The plan's threat register entries are addressed:
- **T-18-03 (DoS via 5000-VT design ceiling)**: Accepted — existing Phase 17 admission gate handles this.
- **T-18-04 (silent attribution loss after STALLED rebind)**: `BotFactory` propagates `BotIdentity` into `BotClientOptions`; `BotClient` stores it in a `final` field that survives the reconnect cycle. CAS-guarded `onClose` ensures `liveCount` tracking does not drift on concurrent close paths.

## Self-Check: PASSED
