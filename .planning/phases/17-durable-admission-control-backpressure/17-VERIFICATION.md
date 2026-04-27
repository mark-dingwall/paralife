---
phase: 17-durable-admission-control-backpressure
verified: 2026-04-28T03:00:00Z
human_resolved: 2026-04-28T05:00:00Z
status: passed
score: 12/12 must-haves verified
overrides_applied: 0
roadmap_success_criteria: 4/4 verified
re_verification: false
human_verification_resolved:
  - test: "Operator log markers cheat sheet"
    outcome: PASSED
    evidence: "Ran `AdmissionLogMarkersIntegrationTest` (slow-tag) under `-PincludeLong=true`; extracted rendered Logback lines from `build/test-results/test/TEST-com.paralife.admission.AdmissionLogMarkersIntegrationTest*.xml`. All markers single-line, key=value shape, kebab-case reasons (`world-full`, `tick-overload`), no human prose in message slot, distinct ALL-CAPS prefixes greppable. Tiny cosmetic nit logged: `respawnCountRestored=null` literal — captured as tech-debt, non-blocking."
  - test: "Maintenance-mode restart-required behavior"
    outcome: PASSED
    evidence: "Source proof. `AdmissionConfig` is a `record` with `@ConfigurationProperties(\"paralife.admission\")` (immutable post-bind). No `@RefreshScope` anywhere; `spring-cloud-context` not on classpath; no actuator refresh path exists. `AdmissionGate.evaluate:106` reads `admissionConfig.maintenance()` per call but the bound bean is fixed — flipping `application.yml` cannot rebind without restart. Boot-time rejection emission already covered by `AdmissionLogMarkersIntegrationTest$MaintenanceStartup`."
  - test: "`paralife.tick.health.work-time-ms` gauge"
    outcome: PASSED
    evidence: "Live `bootRun` + `curl /actuator/metrics/paralife.tick.health.work-time-ms` — endpoint returned HTTP 200, statistic=VALUE, name + description match D-18 §5 caveat verbatim, value 34–39 ms across a 3-second sample sweep with the tick loop running. Tiny Nyquist gap logged: no JUnit actuator-scrape coverage for this gauge in `MetricsEndpointIntegrationTest` — captured as tech-debt, non-blocking."
  - test: "Sustained 100-bot SLI (rebound / stalled.total ≥ 0.99)"
    outcome: DEFERRED
    deferred_to: "Phase 21 (Scale Benchmark Gate & Reports)"
    rationale: "Steady-state SLI is a derived emergent property of component invariants already proven by `StallRecoveryIntegrationTest`, `ConcurrentAdmissionTest`, `EdgeCasesIntegrationTest`, and `LoadTest`. The 99% claim wants a milestone-grade benchmark across 100/500/1000 bots — Phase 21's explicit charter — not an ad-hoc 5-minute soak. Tracking forward to Phase 21."
---

# Phase 17: Durable Admission Control & Backpressure Verification Report

**Phase Goal:** Replace the temporary world cap with a durable admission-control policy and explicit overload/backpressure behavior that preserves tick health under stress.
**Verified:** 2026-04-28T03:00:00Z
**Human items resolved:** 2026-04-28T05:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Roadmap Success Criteria (Authoritative Contract)

| #   | Success Criterion                                                                                              | Status     | Evidence                                                                                                                                                                                                                              |
| --- | --------------------------------------------------------------------------------------------------------------- | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Register / respawn admission is governed by a durable policy rather than a temporary fixed cap.                 | VERIFIED | `AdmissionConfig.java:22` `@ConfigurationProperties("paralife.admission")` with cap, maintenance, tick-overload, backpressure sub-records. `AdmissionGate.evaluate` (`AdmissionGate.java:103`) is the single decision point delegated by `WorldWebSocketHandler.handleRegister:315`. `PopulationCapConfig.java` is deleted. `application.yml:48-57` defines the `paralife.admission` namespace.                                                                                                  |
| 2   | Over-cap and overload paths return explicit, testable rejection reasons and expose operator-visible metrics.    | VERIFIED | All 9 D-07 tokens defined in `RejectionToken.java`. Tagged counter `paralife.admission.rejected{reason=<token>}` (`AdmissionMetrics.incRejected`, line 107) emitted on every rejection; D-19 log markers (`ADMISSION rejected ...`, `AdmissionGate.java:167`) present. 4 D-18 gauges wired (`AdmissionMetrics.java:87-98`). `AdmissionGateTest` covers all rejection paths (12 tests). `MetricsEndpointIntegrationTest` exists for actuator scrape.                                          |
| 3   | Slow or overloaded clients cannot drive unbounded tick drift or silent session churn.                          | VERIFIED | `OutboundSender.java` per-session VT + bounded queue (default 128, configurable). `TickBroadcaster.onTick:206` uses non-blocking `outboundSender.offer` and skips STALLED via `WorldWebSocketHandler.isStalled(session)`; `synchronized(session)` removed from the tick hot path. STALLED-pivot wired through `markStalled` (idempotent, `WorldWebSocketHandler.java:470`); 10-tick grace-window via `ResumeTokenRegistry`. `TickHealthMonitor` hysteresis gate denies new admissions while overloaded. Heartbeat 100-bot `LoadTest` and `StallRecoveryIntegrationTest` exercise these end-to-end. |
| 4   | The temporary `999.1` stopgap is superseded by milestone-owned behavior.                                       | VERIFIED | `PopulationCapConfig.java`, `WorldWebSocketHandlerPopulationCapTest.java` deleted (`ls` returns no-such-file). `paralife.websocket.max-active-entities` absent from `application.yml` and from all `*.java` under `src/`. `LoadTest.java:35` uses `paralife.admission.cap=1000000`. `CLAUDE.md:83` carries the "Outbound concurrency" sub-section per D-10.                                                                                                                              |

**Roadmap SC Score:** 4/4 verified.

### Observable Truths (PLAN frontmatter — merged across 11 plans)

| #   | Truth                                                                                                                            | Status     | Evidence                                                                                                                                                       |
| --- | -------------------------------------------------------------------------------------------------------------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Single-decision-point admission gate: `AdmissionGate.evaluate` is invoked by `WorldWebSocketHandler.handleRegister` for every `r|` frame and emits the D-07 token taxonomy. | VERIFIED | `AdmissionGate.java:103` (sealed `AdmissionResult`), called at `WorldWebSocketHandler.java:316`. Guard order corrected per codex review: maintenance → tick-overload → already-registered → resume-token → world-full → respawn-cap. |
| 2   | All 9 D-07 rejection tokens defined as constants and emitted on the wire (no free-text rejection messages remain).                | VERIFIED | `RejectionToken.java:14-39` — MALFORMED, NO_ACTIVE_ENTITY, RECONNECT_REQUIRED, ALREADY_REGISTERED, WORLD_FULL, RESPAWN_CAP, TICK_OVERLOAD, MAINTENANCE, GRID_FULL. Grep for `Optional.of("` style free-text in `WorldWebSocketHandler.java` returns 0 hits. |
| 3   | Codec parses and round-trips `r|<type>|r:<16hex>` and `S|<id>|r:<16hex>[|effects]`; `r:` sentinel is the sole disambiguator.       | VERIFIED | `PerceptionCodec.java:44` defines `RESUME_TOKEN_SENTINEL = "r:"`; `parseSync` (line 727) and `parseRegister` (line 762) both branch on `r:` prefix only. `RegisterFrameResumeTokenTest`, `SyncFrameResumeTokenTest`, `PerceptionCodecRoundTripTest`, `PerceptionCodecErrorTest` all green. |
| 4   | `TickHealthMonitor` rolling-window hysteresis gate with window-fill guard; opens above high-water-pct, clears below low-water-pct. | VERIFIED | `TickHealthMonitor.java:74` defers gate eval until `filled == window.length` (claude MEDIUM fix). Hysteresis at lines 84-92. `TICK-HEALTH degraded`/`recovered` log markers at lines 86, 90. 9 unit tests + 7 integration tests in `TickHealthGateIntegrationTest`. |
| 5   | `ResumeTokenRegistry` two-state lifecycle (ACTIVE/STALLED); single-use `tryRebind`; tick-driven sweep at @Order(1); cleanup-by-entityId callback. | VERIFIED | `ResumeTokenRegistry.java:50-99` — `issueActive`, `convertToStalled`, `tryRebind`, sweep at `@Order(1)` (line 193). `RebindOutcome` carries fresh ACTIVE token. Stalled-sessions gauge tracks STALLED entries only via `stalledCount` AtomicInteger (codex/opencode HIGH fix). 17 unit tests + edge cases. |
| 6   | `OutboundSender` per-session VT + bounded queue; non-blocking `offer`; fire-once overflow callback; detach joins for ≤100ms.       | VERIFIED | `OutboundSender.java:88-100` — `attachSession` spawns `Thread.ofVirtual()`, queue is `ArrayBlockingQueue` of capacity from config; `offer` (line 133) uses per-session `AtomicBoolean overflowFiredFlags` (line 138-148, codex HIGH fix). `detachSession` calls `t.join(Duration.ofMillis(100))` (line 114, claude HIGH fix). Drain loop calls `metrics.recordFrameSize` after encode (line 184, codex MEDIUM regression resolved at the right layer). |
| 7   | STALLED FSM via `ATTR_STALL_TICK`; idempotent `markStalled`; STALLED-aware `afterConnectionClosed` does NOT reap entity. | VERIFIED | `WorldWebSocketHandler.java:470` — idempotent guard at line 475 (`if (attrs.containsKey(ATTR_STALL_TICK)) return`). `afterConnectionClosed` (line 224-243) checks `wasStalled` and skips `cleanupBot` when STALLED; entity held in grace by `ResumeTokenRegistry.sweep` (claude HIGH / codex HIGH fix). E|408 sent OUT-OF-BAND post-detach (line 505) so it always reaches the client even when the queue was saturated (codex HIGH fix). |
| 8   | `BotClient` STALLED-pivot reconnect with resume token; orthogonal to Phase 15.2 death-pivot. | VERIFIED | `BotClient.java:77` `volatile String resumeToken`. `onError` (line 296) routes E|408 to `handleStalled` (line 320). `@OnWebSocketClose` (line 399) triggers `reconnect()` when `resumeToken != null && !shutdown`. `sendInitialRegister` (line 336) sends `r|<species>|<resumeToken>` if held, else fresh. WebSocketClient instance reused across reconnects (line 113, BotClient stop fix from `86c6056`). |
| 9   | `TickBroadcaster` non-blocking via `outboundSender.offer`; skips STALLED sessions; no `synchronized(session)` on hot path; `recordFrameSize` removed (now in OutboundSender). | VERIFIED | `TickBroadcaster.java:195` checks `isStalled(session)` and skips. Lines 206 + 255 enqueue via `outboundSender.offer`. `grep "recordFrameSize\|synchronized"` returns no synchronized blocks on the hot path. Confirmed by `TickBroadcasterProjectionTest` + `WebSocketIntegrationTest`. |
| 10  | `BotRegistry.rebindSession` swaps sessionId preserving entityId/position; `getSessionByEntity` reverse lookup added. | VERIFIED | `BotRegistry.java:154` `getSessionByEntity`, line 168 `synchronized rebindSession` with collision guard at line 180. `BotRegistryRebindTest` covers the contract. |
| 11  | `ActionResolver` ingress-overwrite counter wired (D-09); CLAUDE.md "Outbound concurrency" doc present. | VERIFIED | `ActionResolver.java:295` — `admissionMetrics.incIngressOverwrite()` on every last-write-wins overwrite. `CLAUDE.md:83` "### Outbound concurrency (Phase 17, D-10)" sub-section with full VT-per-session rationale + STALLED transition explanation. |
| 12  | `application.yml` migrated to `paralife.admission.*`; `PopulationCapConfig` and population-cap test deleted; `LoadTest` migrated to new key. | VERIFIED | `application.yml:48-57` defines `paralife.admission.{cap,maintenance,tick-overload,backpressure}`; `grep max-active-entities` returns 0 hits across `src/`. `PopulationCapConfig.java` and `WorldWebSocketHandlerPopulationCapTest.java` not found. `LoadTest.java:35` uses `paralife.admission.cap=1000000`. |

**PLAN must-haves Score:** 12/12 verified.

### Required Artifacts

| Artifact                                                                                       | Expected                                                              | Status   | Details                                                                                                              |
| ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | -------- | -------------------------------------------------------------------------------------------------------------------- |
| `src/main/java/com/paralife/admission/AdmissionConfig.java`                                    | `@ConfigurationProperties("paralife.admission")` record               | VERIFIED | 6.4 kB; cap, maintenance, TickOverloadConfig, BackpressureConfig sub-records with validation.                       |
| `src/main/java/com/paralife/admission/RejectionToken.java`                                     | All 9 D-07 token constants                                            | VERIFIED | 1.6 kB; constants present and lined to D-07 codes.                                                                  |
| `src/main/java/com/paralife/admission/AdmissionGate.java`                                      | `@Component` with `evaluate(AdmissionRequest)` + atomic reservation   | VERIFIED | 8.9 kB; reservation slots A4 fix in place; correct guard order.                                                     |
| `src/main/java/com/paralife/admission/AdmissionResult.java`                                    | Sealed `Allow|Reject|Rebind`                                          | VERIFIED | 2.1 kB; sealed type with 3 variants; matches `AdmissionGate.evaluate` return.                                       |
| `src/main/java/com/paralife/admission/AdmissionMetrics.java`                                   | Tagged rejected counter + 4 D-18 gauges + frame-size summary          | VERIFIED | 8.3 kB; counters: `paralife.admission.rejected{reason=}`, ingress.overwrites, rebound, terminal.dropouts, stalled.total. Gauges: active.entities, maintenance, tick.health.work-time-ms, backpressure.stalled.sessions. DistributionSummary: paralife.outbound.frame.size.bytes. |
| `src/main/java/com/paralife/admission/TickHealthMonitor.java`                                  | Rolling-window hysteresis gate + window-fill guard                    | VERIFIED | 4.6 kB; @Order(Integer.MAX_VALUE), filled-window guard, hysteresis log markers.                                     |
| `src/main/java/com/paralife/admission/ResumeTokenRegistry.java`                                | Two-state ACTIVE/STALLED registry + sweep                             | VERIFIED | 12.0 kB; State enum, atomic compare-and-remove on tryRebind, sweep CAS-remove before cleanup (A3 fix from `92115ad`). |
| `src/main/java/com/paralife/admission/OutboundSender.java`                                     | VT-per-session sender + fire-once overflow + detach join              | VERIFIED | 9.1 kB; per-session AtomicBoolean overflowFiredFlags; t.join(100ms); single-writer drain loop.                      |
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (refactor)                   | AdmissionGate delegation, STALLED FSM, retoken errors, idempotent markStalled | VERIFIED | All admission paths delegate to AdmissionGate; ATTR_STALL_TICK gates idempotency; `cleanupByEntityId` resolves entityId→sessionId via BotRegistry. |
| `src/main/java/com/paralife/websocket/TickBroadcaster.java` (refactor)                         | Enqueue via OutboundSender; skip STALLED; no synchronized             | VERIFIED | Non-blocking offer; isStalled check; no synchronized blocks on hot path.                                            |
| `src/main/java/com/paralife/bot/BotClient.java` (extension)                                    | Resume-token storage + reconnect on E|408                            | VERIFIED | volatile resumeToken; handleStalled; @OnWebSocketClose triggers reconnect.                                          |
| `src/main/java/com/paralife/codec/Frame.java` (extension)                                      | RegisterFrame/SyncFrame Optional<String> resumeToken                  | VERIFIED | Both records carry the slot; back-compat ctors preserved.                                                            |
| `src/main/java/com/paralife/codec/PerceptionCodec.java` (extension)                            | r:-sentinel disambiguator                                             | VERIFIED | RESUME_TOKEN_SENTINEL = "r:" used as sole disambiguator.                                                              |
| `src/main/java/com/paralife/engine/BotRegistry.java` (extension)                                | rebindSession + getSessionByEntity                                    | VERIFIED | Synchronized rebindSession with collision guard; getSessionByEntity reverse map lookup.                              |
| `src/main/java/com/paralife/engine/TickEngine.java` (extension)                                | volatile lastTickWorkMs + currentTick getters                         | VERIFIED | Used by TickHealthMonitor and WorldWebSocketHandler markStalled.                                                     |
| `src/main/resources/application.yml`                                                            | paralife.admission namespace; max-active-entities removed             | VERIFIED | Lines 48-57 define new namespace; old key absent.                                                                    |
| `CLAUDE.md` (extension)                                                                         | "Outbound concurrency" section per D-10                                | VERIFIED | Line 83-110 — full VT-per-session rationale and STALLED transition narrative.                                        |
| `.planning/phases/17-.../17-ADMISSION.md`                                                       | Spec authored: token taxonomy, FSM, wire shape, hysteresis defaults  | VERIFIED | 352 lines; all 9 sections present; D-05/D-07/D-08 locked.                                                            |

### Key Link Verification

| From                                          | To                                              | Via                                  | Status | Details                                                                                                  |
| --------------------------------------------- | ----------------------------------------------- | ------------------------------------ | ------ | -------------------------------------------------------------------------------------------------------- |
| `WorldWebSocketHandler.handleRegister`        | `AdmissionGate.evaluate`                        | delegation                           | WIRED  | Line 316: `admissionGate.evaluate(req)`; result switch handles Allow/Reject/Rebind.                      |
| `AdmissionGate.evaluate`                      | `AdmissionMetrics.incRejected`                  | rejection emission                   | WIRED  | `AdmissionGate.reject` (line 165) calls `metrics.incRejected(token)`.                                    |
| `OutboundSender.offer` (overflow)             | `WorldWebSocketHandler.markStalled`             | overflow callback                    | WIRED  | `WorldWebSocketHandler.@PostConstruct` (line 200-201) registers `(s, depth) -> markStalled(...)`.        |
| `ResumeTokenRegistry.sweep` (cleanup)         | `WorldWebSocketHandler.cleanupByEntityId`       | entity-id-keyed reaper               | WIRED  | `WorldWebSocketHandler.@PostConstruct` line 205: `resumeTokenRegistry.setCleanupCallback(this::cleanupByEntityId)`. |
| `TickBroadcaster.onTick` / drainAndBroadcastDeaths | `OutboundSender.offer`                          | non-blocking enqueue                | WIRED  | Lines 206 + 255; STALLED-skip via `worldWebSocketHandler.isStalled(session)` upstream.                  |
| `TickHealthMonitor.onTick`                    | `TickEngine.getLastTickWorkMs`                  | @Order(Integer.MAX_VALUE) sample read | WIRED  | `TickHealthMonitor.java:62`.                                                                              |
| `AdmissionGate.evaluate`                      | `TickHealthMonitor.isOverloaded`                | tick-overload guard                  | WIRED  | `AdmissionGate.java:111`.                                                                                 |
| `ActionResolver.queueAction` (overwrite)      | `AdmissionMetrics.incIngressOverwrite`          | D-09 counter                         | WIRED  | `ActionResolver.java:295`.                                                                                |
| `BotClient.onError(E|408)`                    | `BotClient.handleStalled` → `reconnect()`       | E|408 dispatch                        | WIRED  | `BotClient.java:299` → `handleStalled` line 320 → `@OnWebSocketClose` line 402 → `reconnect()`.          |
| `application.yml paralife.admission.cap`      | `AdmissionConfig`                                | @ConfigurationProperties             | WIRED  | Boot-bound; verified by `AdmissionConfigTest`.                                                            |

### Data-Flow Trace (Level 4)

| Artifact                              | Data Variable                  | Source                                                                       | Produces Real Data | Status     |
| ------------------------------------- | ------------------------------ | ---------------------------------------------------------------------------- | ------------------ | ---------- |
| `AdmissionGate.reservedSlots`         | `AtomicInteger reservedSlots`  | `evaluate` increments on Allow; `releaseSlot` decrements via cleanupBot       | Yes — atomic CAS verified by `ConcurrentAdmissionTest`            | FLOWING |
| `AdmissionMetrics.rejected{reason=}`  | tagged counter                 | `AdmissionGate.reject()` increments on every rejection; reasons drawn from RejectionToken | Yes — exercised by 9 token-specific test cases | FLOWING |
| `ResumeTokenRegistry.tokenMap`        | `ConcurrentHashMap`            | `issueActive` (register) → `convertToStalled` (markStalled) → `tryRebind` (re-register) | Yes — unit + edge-cases + integration | FLOWING |
| `TickHealthMonitor.window`             | `long[] window`                | `TickEngine.getLastTickWorkMs` sampled @Order(Integer.MAX_VALUE)             | Yes — TickEngine writes lastTickWorkMs after publishEvent (N-1 lag documented in javadoc) | FLOWING |
| `OutboundSender.queues[sid]`           | `ArrayBlockingQueue<Frame>`    | `TickBroadcaster.onTick` enqueues; drain VT consumes; `recordFrameSize` after encode | Yes — verified by `OutboundSenderTest` + `WebSocketIntegrationTest` | FLOWING |
| `paralife.backpressure.stalled.sessions` gauge | `AtomicInteger stalledCount`   | `convertToStalled` increments; `tryRebind` and sweep decrement                | Yes — fixed gauge correctness (codex/opencode HIGH); `EdgeCasesIntegrationTest` covers concurrent rebind 1-of-2-wins | FLOWING |
| `paralife.tick.health.work-time-ms` gauge | `AtomicLong lastTickWorkMs`    | `TickHealthMonitor.onTick` → `admissionMetrics.setLastTickWorkMs(sample)`     | Yes — single writer; lag documented | FLOWING |

### Behavioral Spot-Checks

| Behavior                                                     | Command                                                                                  | Result                                                                                      | Status |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ------ |
| Full test suite green (Phase 17 baseline regression)         | `./gradlew test` (background task `belb2zruo`)                                            | `BUILD SUCCESSFUL in 2m 59s`. Aggregated: 662 tests, 3 skipped, 0 failures, 0 errors across 96 test suites. | PASS   |
| New deletions absent from filesystem                          | `ls .../PopulationCapConfig.java .../WorldWebSocketHandlerPopulationCapTest.java`         | Both files: "No such file or directory"                                                     | PASS   |
| Old config key absent from `src/`                             | `grep -rn "max-active-entities" src/`                                                     | No results                                                                                  | PASS   |
| New namespace bound in application.yml                        | `grep "paralife.admission\|max-active-entities" src/main/resources/application.yml`       | `48: admission:` with cap/maintenance/tick-overload/backpressure children                   | PASS   |
| Free-text rejection messages absent from handler              | `grep -E 'new Frame\.ErrorFrame.*Optional\.of\("' WorldWebSocketHandler.java`              | 0 hits                                                                                       | PASS   |
| RejectionToken constants used in handler                      | `grep -oE "RejectionToken\.[A-Z_]+" WorldWebSocketHandler.java`                            | GRID_FULL, MALFORMED, NO_ACTIVE_ENTITY, RECONNECT_REQUIRED (rest emitted via AdmissionGate result) | PASS   |
| CLAUDE.md Outbound concurrency section present                | `grep "Outbound concurrency" CLAUDE.md`                                                   | line 83 match                                                                                | PASS   |

### Requirements Coverage

| Requirement | Source Plan(s)                  | Description                                                                                                                                | Status     | Evidence                                                                                                                                                                              |
| ----------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SCALE-01    | 17-01..17-05, 17-07, 17-10, 17-11 | Server admission control replaces the temporary `max-active-entities` stopgap with a durable world-level policy that explains register and respawn rejection reasons. | SATISFIED  | Roadmap SC #1 + #4 verified above. Evidence: AdmissionConfig durable namespace + AdmissionGate single decision point + 9-token D-07 vocabulary + PopulationCapConfig deletion. AdmissionGateTest + ConcurrentAdmissionTest exercise the cap behaviour at unit + concurrent levels. |
| SCALE-02    | 17-04, 17-06, 17-08, 17-09, 17-11 | Overload and slow-client paths apply explicit backpressure or shedding rules without causing unbounded tick drift or silent session churn. | SATISFIED  | Roadmap SC #2 + #3 verified above. Evidence: VT-per-session OutboundSender + bounded queue + STALLED FSM + resume-token grace + TickHealthMonitor hysteresis + BotClient reconnect-with-token. StallRecoveryIntegrationTest, TickHealthGateIntegrationTest, EdgeCasesIntegrationTest, AdmissionLogMarkersIntegrationTest cover the integration surfaces. |

No orphaned requirements: REQUIREMENTS.md maps SCALE-01 and SCALE-02 to Phase 17, both are claimed by plans 17-01..17-11.

### Anti-Patterns Found

None blocking the goal.

| File                                              | Line   | Pattern                                                  | Severity | Impact                                                                                                                                                                                                                                                                                                       |
| ------------------------------------------------- | ------ | -------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ResumeTokenRegistry.java`                        | 128, 134 | `return null`                                            | Info     | Both are defensive null/unknown-token guards in `convertToStalled` (early-return when token arg is null OR map.compute observes no existing entry). Not a stub — caller contract is "no-op on unknown token, log warning"; verified by `EdgeCasesIntegrationTest`.                                            |
| `WorldWebSocketHandler.java`                      | —      | TODO/FIXME/PLACEHOLDER                                    | —        | None present.                                                                                                                                                                                                                                                                                                  |
| `OutboundSender.drainLoop`                        | 188    | catch IOException → warn, continue loop                   | Info     | Intentional: per Plan 06 / D-10, send failures to a single session must not kill the VT or affect other sessions. Per-session isolation is the contract. Logged for operator visibility.                                                                                                                       |
| `BotClient.reconnect`                             | 350    | catch Exception → warn, no retry                          | Info     | Acceptable: reconnect-on-close is best-effort; if the network is dead the bot client gives up and the server reaps the entity at grace expiry. No unbounded retry loop (anti-pattern would be the reverse).                                                                                                  |

### Hotfix / Test / Doc Commits Review (post-`cc663ae`)

The 12+ commits between `cc663ae` ("ready for verification") and HEAD were spot-checked against the must-haves. None reduced goal coverage; each addresses a real gap surfaced by informal review:

| Commit    | Summary                                                                | Coverage Impact                                                  |
| --------- | --------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `cb1fc97` | synchronized(session) invariant for ALL writers (A1/A2)               | Closes Jetty 12 sendMessage thread-safety hole flagged by claude HIGH review. Adds robustness, no contract change. |
| `92115ad` | ResumeTokenRegistry sweep CAS-remove before cleanup (A3)              | Closes a race between `tryRebind` and `sweep` reaping the same entry. Strengthens single-use guarantee. |
| `9b7bbfe` | atomic admission reservation + stall-storm SLI + log-marker fix (A4/B3/D3) | Adds `reservedSlots` AtomicInteger + new `paralife.backpressure.stalled.total` counter (SLI denominator). Core durable-cap correctness fix. |
| `86c6056` | BotClient client=null after stop for reconnect-after-disconnect (B2)  | Test reliability fix; no production-contract change.             |
| `4465ec9` | concurrent admission cap-overshoot + drain serialization tests (C1/F11) | Adds `ConcurrentAdmissionTest` (17-DEV-01) — verifies A4 atomic reservation under contention. |
| `9ea6b82` | reconcile 17-ADMISSION.md + Outbound concurrency contract (E1-E4/F9)  | Doc reconciliation only.                                          |
| `2a10be0` | reconcile validation map + add deviation/orphan-test rows             | Doc reconciliation only.                                          |

These are corrections-not-new-features. They strengthen rather than weaken goal achievement. Recorded under 17-DEV-01..04 in `17-VALIDATION.md`.

### Human Verification — Resolved 2026-04-28

Three of four items confirmed in-session; the fourth deferred to Phase 21 with explicit rationale.

| # | Item | Outcome | Evidence |
| - | ---- | ------- | -------- |
| 1 | Operator log markers cheat sheet | PASSED | Rendered Logback lines extracted from `AdmissionLogMarkersIntegrationTest` XML output (run with `-PincludeLong=true`). Single-line shape, key=value fields, kebab-case reasons (`world-full`, `tick-overload`), no human prose in message slot, ALL-CAPS prefixes distinctly greppable. |
| 2 | Maintenance flag restart-required | PASSED | Source-proof: `AdmissionConfig` is a `record` with `@ConfigurationProperties("paralife.admission")` (immutable post-bind); no `@RefreshScope` anywhere; `spring-cloud-context` not on classpath. Bean cannot re-bind without restart. Boot-time rejection emission covered by `AdmissionLogMarkersIntegrationTest$MaintenanceStartup`. |
| 3 | `paralife.tick.health.work-time-ms` gauge | PASSED | Live `bootRun` + `curl /actuator/metrics/paralife.tick.health.work-time-ms` returned HTTP 200, statistic=VALUE, name + description match D-18 §5 caveat verbatim, value 34–39 ms across a 3-second sample sweep with the tick loop running. |
| 4 | Sustained 100-bot SLI (≥99%) | DEFERRED → Phase 21 | Steady-state SLI is the explicit charter of Phase 21 (Scale Benchmark Gate & Reports) across 100/500/1000 bots. Component invariants underlying the SLI are already proven by `StallRecoveryIntegrationTest`, `ConcurrentAdmissionTest`, `EdgeCasesIntegrationTest`, `LoadTest`. Carrying forward, not blocking. |

### Tech Debt — Surfaced During Human Verification

Both items are non-blocking polish; neither affects goal achievement or M4 close.

| ID | Severity | Location | Description |
| -- | -------- | -------- | ----------- |
| TD-17-A | Low (cosmetic) | `WorldWebSocketHandler.java:338` log marker | `BACKPRESSURE resumed ... respawnCountRestored=null` renders the literal string `null` when no prior count exists. Operator-readable but slightly noisy. Candidate: render `=0` or `=-`. |
| TD-17-B | Low (test gap) | `MetricsEndpointIntegrationTest` | Test scrapes `paralife.ws.*` actuator paths but does not scrape `paralife.tick.health.work-time-ms`. Wiring confirmed by live curl; JUnit-level reachability assertion missing. ~5-line addition in the existing file would close it. |

### Gaps Summary

No gaps. Every roadmap success criterion met by working code. Every plan must-have has executable evidence. Full suite green (662 tests, 0 failures). All four human-verification items resolved (3 verified in-session, 1 explicitly deferred to Phase 21). Two micro tech-debt items captured for tracking — neither blocks milestone close.

---

_Verified: 2026-04-28T03:00:00Z_
_Human items resolved: 2026-04-28T05:00:00Z_
_Verifier: Claude (gsd-verifier + interactive resolution)_
