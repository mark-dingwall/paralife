---
phase: 18-external-load-harness-harness-identity
verified: 2026-04-28T00:00:00Z
status: human_needed
score: 4/4 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Run ./gradlew loadHarnessJar && java -jar build/libs/paralife-*-load-harness.jar --help"
    expected: "Exit 0; --help prints --server-uri, --count, --harness-id, --ramp-up, --duration etc.; NO Spring banner"
    why_human: "Build output directory was clean (no JAR found). The Gradle task definition is correct and complete (mainClass, archiveClassifier, targetJavaVersion all set), but the JAR must be physically built and the --help invocation observed to close Plan 05's dry-run smoke requirement."
  - test: "Run ./gradlew test (or ./gradlew cleanTest test -PincludeLong=true for the full suite)"
    expected: "BUILD SUCCESSFUL — all tests pass including AttributionRebindTest, LoadHarnessIntegrationTest, LoadTest, TickHealthMonitorScalarTest"
    why_human: "Tests were not re-run during this verification session. The summaries report BUILD SUCCESSFUL for each plan, but independent confirmation is needed before marking the phase fully passed."
---

# Phase 18: External Load Harness & Harness Identity — Verification Report

**Phase Goal:** Build the first-class external load harness that scales past BotRunner's single-process limit and attributes sessions and metrics per harness instance.
**Verified:** 2026-04-28
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A standalone harness can launch and sustain large-N bot fleets outside the Gradle runBot path | ✓ VERIFIED | `LoadHarness.java` implements `Callable<Integer>`, zero Spring context, drives `BotFleet.launch()` with no upper bound. `BotFleet` has no 30s ceiling. Gradle `loadHarnessJar` BootJar task confirmed in `build.gradle.kts`. |
| 2 | Harness-origin identity is attached to load traffic and visible in server-side metrics or logs | ✓ VERIFIED | `BotClient.connect()` sets `X-Paralife-Source`/`X-Paralife-Harness` on every WS upgrade. `WorldWebSocketHandler.afterConnectionEstablished` reads headers, sanitizes via `AttributionSanitizer`, stashes `ATTR_SOURCE`/`ATTR_HARNESS`. `AdmissionMetrics` emits two-tag `paralife.admission.active.entities{source, harness}`. HARNESS connected/disconnected log markers present. `AttributionRebindTest` locks the STALLED-pivot attribution-preservation path end-to-end. |
| 3 | BotRunner remains the recommended operator path for <=100 bots | ✓ VERIFIED | `BotRunner.MAX_BOTS = 100` enforced at CLI boundary. `BotRunner.run()` uses `BotFleet` + `BotIdentity.operator()`. `LoadHarness` has no upper-bound cap. `BotRunnerRegressionTest` and `BotRunnerOperatorTagTest` present. |
| 4 | Harness behavior is documented well enough to reproduce benchmark runs | ✓ VERIFIED | `18-HARNESS.md` has 10 sections (§1–§10) including Sample Benchmark Commands with three `java -jar` invocations (100/500/1000 bots). `stalled-held` reason documented in §5. Canonical harness-id regex `^[A-Za-z0-9-]{1,32}$` anchored in §2. `CLAUDE.md` gains Connection model subsection with cross-reference. |

**Score:** 4/4 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/paralife/bot/BotIdentity.java` | Identity record with SOURCE_TAXONOMY + factories + invariant ctor | ✓ VERIFIED | `SOURCE_TAXONOMY = Set.of(...)`, `operator()`, `harness()`, `unknown()`, symmetric `isHarnessSource != hasId` invariant, `c < 0x20` control-char rejection |
| `src/main/java/com/paralife/bot/BotClientOptions.java` | Options record per Pitfall 2 | ✓ VERIFIED | File exists; `defaults()` factory with `BotIdentity.unknown()` |
| `src/main/java/com/paralife/bot/BotClient.java` | Header injection in connect(); onClose CAS hook; e408/syncs counters | ✓ VERIFIED | `req.setHeader("X-Paralife-Source", ...)` at line 173-174; `closedFired.compareAndSet`; `getE408ReconnectRequiredCount()`, `getSyncsReceivedCount()` |
| `src/main/java/com/paralife/admission/AttributionSanitizer.java` | Shared harness-id normalizer | ✓ VERIFIED | `sanitizeHarnessId(String)` returns `Optional<String>`, used by `WorldWebSocketHandler.afterConnectionEstablished` |
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` | Header read + ATTR stash + HARNESS markers + session-evaluate wiring | ✓ VERIFIED | `getHandshakeHeaders()` called; `ATTR_SOURCE`/`ATTR_HARNESS` stashed; `HARNESS connected tick=...` and `HARNESS disconnected tick=...` log markers; `stalled-held` close reason; `admissionGate.evaluate(req, session)` called |
| `src/main/java/com/paralife/admission/AttributionTagger.java` | Tags factory with overflow folding; synchronized slot-claim; warn-once | ✓ VERIFIED | `synchronized (slotLock)` present; `overflowWarned.compareAndSet`; `HARNESS overflow first-seen tick=...` log warn |
| `src/main/java/com/paralife/admission/AdmissionMetrics.java` | Two-tag emission; MeterFilter cap; incStalledBucket(session, entityId) | ✓ VERIFIED | `MeterFilter.maximumAllowableTags` (2 calls); `tagger.tagsFor` used throughout; `incStalledBucket(WebSocketSession session, String entityId)` signature; `setActiveEntities`/`setStalledSessions` are no-ops |
| `src/main/java/com/paralife/admission/AdmissionGate.java` | session-bearing evaluate/reject | ✓ VERIFIED | `evaluate(AdmissionRequest req, WebSocketSession session)` method exists; `metrics.incRejected(token, session)` called |
| `src/main/java/com/paralife/websocket/SessionRegistry.java` | getById O(1) lookup | ✓ VERIFIED | `public WebSocketSession getById(String sessionId)` at line 66 |
| `src/main/java/com/paralife/engine/ActionResolver.java` | Uses getById not stream | ✓ VERIFIED | `sessionRegistry.getById(sessionId)` present; no `getActiveSessions().stream()` in action hot path |
| `src/main/java/com/paralife/bot/BotFactory.java` | D-19 seam with claimEntityId/claimToken | ✓ VERIFIED | `create(char, BotIdentity, Optional<String> claimEntityId, Optional<String> claimToken)` |
| `src/main/java/com/paralife/bot/BotFleet.java` | Async VT launcher; no 30s ceiling; idempotent shutdown; peak tracking | ✓ VERIFIED | `CompletableFuture<RegistrationResult>`; no `allDone.await(30`; `shutdownDone.compareAndSet`; `highWater.updateAndGet`; `connectFailuresTotal` counter |
| `src/main/java/com/paralife/bot/RampUpSpec.java` | Sealed interface; nanosecond Rate | ✓ VERIFIED | `sealed interface`; `1_000_000_000L / perSecond`; `LockSupport.parkNanos` |
| `src/main/java/com/paralife/bot/SpeciesMix.java` | Hardcoded ORDERED_TYPES; no ParticleType.values() | ✓ VERIFIED | `ORDERED_TYPES = { CATALYST, MEMBRANE, SPORE }` hardcoded; `ParticleType.values()` absent |
| `src/main/java/com/paralife/bot/BotRunner.java` | Uses BotFleet; BotIdentity.operator(); MAX_BOTS=100; extracted run() | ✓ VERIFIED | `public static int run(String[] args, Supplier<BotFleet>, Function<String,BotFactory>)`; `BotIdentity.operator()`; `MAX_BOTS = 100` |
| `src/main/java/com/paralife/harness/LoadHarness.java` | @Command implements Callable<Integer>; zero Spring; env:VAR syntax; signal exit | ✓ VERIFIED | `implements Callable<Integer>`; no `@SpringBootApplication` or `SpringApplication.run`; `${env:PARALIFE_HARNESS_*}` on all options; single `"signal"` exit reason; `removeShutdownHook` in finally |
| `src/main/java/com/paralife/harness/ReportWriter.java` | ATOMIC_MOVE + SNAKE_CASE | ✓ VERIFIED | `ATOMIC_MOVE`; `PropertyNamingStrategies.SNAKE_CASE`; `StandardOpenOption.SYNC` |
| `build.gradle.kts` | picocli dep + loadHarnessJar + runHarness | ✓ VERIFIED | `picocli:4.7.7`; `loadHarnessJar` BootJar with `targetJavaVersion.set(VERSION_21)`; `runHarness` JavaExec |
| `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` | 10-section spec | ✓ VERIFIED | 10 sections counted; `stalled-held` documented in §5 (5 occurrences); canonical regex `^[A-Za-z0-9-]{1,32}$` in §2; `AttributionSanitizer` cross-reference; 3+ `java -jar` benchmark commands |
| `CLAUDE.md` | Connection model subsection | ✓ VERIFIED | `### Connection model (Phase 18, D-05 / D-21)` present; cross-reference to `18-HARNESS.md §1` |
| `src/test/java/com/paralife/admission/AttributionRebindTest.java` | STALLED-pivot attribution lock | ✓ VERIFIED | `verifyMarkStalledSignature()` pre-flight; `unknownBefore`/`unknownAfter` before/after comparison; `isLessThanOrEqualTo(unknownBefore)`; `harness=test-attribution` assertions; 5s Awaitility budgets |
| `src/test/java/com/paralife/engine/LoadTest.java` | Migrated to harness-tagged path | ✓ VERIFIED | `BotIdentity.harness("test-load")`; `BotFleet` used; 64-cap comment present |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `BotClient.connect` | `ClientUpgradeRequest.setHeader("X-Paralife-Source/Harness")` | Jetty 12 native | ✓ WIRED | Lines 173-174 in BotClient.java |
| `WorldWebSocketHandler.afterConnectionEstablished` | `AttributionSanitizer.sanitizeHarnessId` | shared validator | ✓ WIRED | Line 244 in WorldWebSocketHandler.java |
| `WorldWebSocketHandler.afterConnectionEstablished` | `session.getAttributes().put(ATTR_SOURCE/ATTR_HARNESS)` | stash | ✓ WIRED | Line 238 and 244-246 in WorldWebSocketHandler.java |
| `AdmissionMetrics.incRejected(reason, session)` | `AttributionTagger.tagsFor(session)` | Attribution | ✓ WIRED | Lines 183, 202 in AdmissionMetrics.java |
| `WorldWebSocketHandler.markStalled` | `admissionMetrics.incStalledBucket(session, entityId)` BEFORE `attrs.remove` | call ordering | ✓ WIRED | Lines 543 then 549 in WorldWebSocketHandler.java |
| `LoadHarness.call` | `BotFleet.launch` via `BotIdentity.harness(harnessId)` | fleet abstraction | ✓ WIRED | Lines 183, 236 in LoadHarness.java |
| `ReportWriter.writeOverwrite` | `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` | atomic rename | ✓ WIRED | Line 137 in ReportWriter.java |
| `AttributionTagger.foldHarnessIfOverCap` | warn-once log at fold site (not MeterFilter) | `overflowWarned.compareAndSet` | ✓ WIRED | Line 161-166 in AttributionTagger.java |
| `SessionRegistry.getById` | `ActionResolver` O(1) lookup | hot path | ✓ WIRED | Line 297 in ActionResolver.java |
| `BotRunner.main` | `BotFleet.launch` via `BotIdentity.operator()` | fleet abstraction | ✓ WIRED | Lines 129, 134 in BotRunner.java |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `LoadHarness.computeCountersSnapshot` | `peakRegistered`, `connectFailures`, `syncs`, etc. | `BotFleet.peakRegistered()`, `BotFleet.connectFailuresTotal()`, `BotClient.getSyncsReceivedCount()` — all monotonic atomics | Yes — incremented at real events (registration success, connect failure, sync frame receipt) | ✓ FLOWING |
| `AdmissionMetrics.activeBuckets` gauge | per-bucket `AtomicInteger` | `incActiveBucket(session)` on Allow path; `decActiveBucket(session)` on close | Yes — driven by real session lifecycle events | ✓ FLOWING |
| `WorldWebSocketHandler.afterConnectionEstablished` ATTR_HARNESS | `AttributionSanitizer.sanitizeHarnessId(rawHarnessId)` | real WS handshake header from BotClient | Yes — BotClient injects real `X-Paralife-Harness` set from `BotIdentity.harnessId()` | ✓ FLOWING |

### Behavioral Spot-Checks

Step 7b: Spot-checks were not run (server not started during verification). The key behavioral invariants are covered by the test suite asserted in SUMMARY files. The dry-run smoke (`./gradlew loadHarnessJar && java -jar ... --help`) is routed to human verification because the JAR was not present in the build output directory.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| SCALE-03 | Plans 04, 05, 06 | Operators can launch standalone external harness beyond 100-bot ceiling | ✓ SATISFIED | `LoadHarness` + `BotFleet` with no upper-bound cap; `loadHarnessJar` Gradle task; `LoadHarnessIntegrationTest` |
| SCALE-04 | Plans 01, 02, 03 | Each harness instance identifies itself in logs and metrics | ✓ SATISFIED | `BotIdentity.harness(id)` → handshake headers → server ATTR stash → `AttributionTagger` two-tag metrics → `HARNESS connected/disconnected` log markers |
| SCALE-05 | Plans 04, 06 | BotRunner remains supported local operator path for <=100 bots | ✓ SATISFIED | `BotRunner.MAX_BOTS = 100` enforced; `BotRunnerRegressionTest` green; `BotRunnerOperatorTagTest` green |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `ResumeTokenRegistry.java` | 146, 179, 226 | Calls `metrics.setStalledSessions(int n)` | ℹ️ Info | No impact — `AdmissionMetrics.setStalledSessions()` is now a documented no-op (empty body with comment). `ResumeTokenRegistry` still calls it but it does nothing. This is a pre-existing call site that wasn't cleaned up, not a blocker. |

No placeholder implementations, TODO stubs, or empty handlers found in production code under the new harness package or modified bot/admission files.

### Human Verification Required

#### 1. Load-Harness JAR Build and --help Smoke Test

**Test:** `cd /path/to/paralife && ./gradlew loadHarnessJar && java -jar build/libs/paralife-*-load-harness.jar --help`
**Expected:** Exit 0; help output includes `--server-uri`, `--count`, `--harness-id`, `--ramp-up`, `--species-mix`, `--duration`, `--report-out`, `--report-mode`, `--report-interval`; no Spring banner printed
**Why human:** Build output directory was clean at verification time — no pre-built JAR exists. The Gradle task definition is correct and complete, but the actual fat-jar invocation must be observed to satisfy Plan 06 Task 2's dry-run smoke requirement.

#### 2. Full Test Suite Confirmation

**Test:** `./gradlew cleanTest test -PincludeLong=true`
**Expected:** BUILD SUCCESSFUL; all tests pass including `AttributionRebindTest`, `LoadHarnessIntegrationTest`, `TickHealthMonitorScalarTest`, `AdmissionMetricsLifecycleTest`, and `LoadTest`
**Why human:** Tests were not re-executed during this verification session. Each SUMMARY claims BUILD SUCCESSFUL with all tests green, but an independent run is needed to confirm the combined suite still passes (integration between plans may surface issues not caught in per-plan runs).

### Gaps Summary

No substantive gaps found. All four roadmap success criteria are verified against actual code. The `human_needed` status reflects two items that require execution-time observation rather than static analysis: the JAR build smoke test and independent test-suite confirmation.

The one informational finding — `ResumeTokenRegistry` still calling the now-no-op `setStalledSessions` — is not a blocker: the method body is empty and documented as such.

---

_Verified: 2026-04-28_
_Verifier: Claude (gsd-verifier)_
