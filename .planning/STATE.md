---
gsd_state_version: 1.0
milestone: v3.0
milestone_name: Scale Engineering
status: executing
stopped_at: "Phase 20 plan-by-plan — 20-01 / 20-01c / 20-02 / 20-03 done (20-01b superseded); 20-04 / 20-05 / 20-06 pending"
last_updated: "2026-06-03T07:55:00.000Z"
last_activity: 2026-06-03 -- Plan 20-02 executed (JettyRuntimeConfig record + Jetty Configurable wiring + yaml block + tests); three-gate stack green; zero-behaviour-change defaults preserved
progress:
  total_phases: 15
  completed_phases: 4
  total_plans: 35
  completed_plans: 33
  percent: 94
---

# Project State

## Project Reference

See: .planning/PROJECT.md

**Core value:** Emergent spatial behaviour from simple local rules — a testbed for evolving entity intelligence.
**Current focus:** Phase 20 — connection-multiplexing-runtime-tuning

## Current Position

Milestone: v3.0 (Scale Engineering / M4) — active
Phase: 20 (connection-multiplexing-runtime-tuning) — EXECUTING
Plan: 4 of 7 executed (20-01, 20-01c, 20-02, 20-03 done; 20-01b superseded by 20-01c)
Status: Ready to execute — next is 20-04 (JVM-flag presets + per-tier recipes in 20-RUNTIME.md)
Last activity: 2026-06-03 -- Plan 20-02 executed (JettyRuntimeConfig + Jetty wiring + yaml + tests)

Progress: [█████████░] 94% (33/35 plans)

## Deferred Items

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| artifact | 13-HUMAN-UAT.md retained as passed historical evidence | acknowledged | 2026-04-22 |
| backlog | 999.2 offspring become bot-driven; M5 flower fallback | open | 2026-04-22 |
| uat | Phase 17 SLI item 4 — sustained 100-bot rebound/stalled.total ≥ 0.99 | deferred → Phase 21 (Scale Benchmark Gate) | 2026-04-28 |
| tech-debt | TD-17-A — `respawnCountRestored=null` literal in `BACKPRESSURE resumed` log marker | open | 2026-04-28 |
| tech-debt | TD-17-B — `MetricsEndpointIntegrationTest` missing scrape for `paralife.tick.health.work-time-ms` gauge | open | 2026-04-28 |
| uat | Phase 18 dry-run smoke — `./gradlew loadHarnessJar && java -jar build/libs/paralife-*-load-harness.jar --help` (per 18-VERIFICATION.md, status human_needed) | open | 2026-04-28 |
| tech-debt | TD-19.5-A — `OutboundSender.awaitAllSessionQueuesDrained` VT race; `GoldenTraceEquivalenceTest` flaky in isolated runs (~40% emit ±1), masked in suite. Pre-existing; surfaced by 260502-sds H2. Candidate for Phase 20 / backlog. | open | 2026-05-02 |
| tech-debt | TD-22-A — `MetabolismIntegrationTest.allTypesSurviveWithMetabolism` `@Disabled`; WorldGrid read-lock starvation under tick-loop write pressure. Re-enable + pass under P21 benchmark conditions. | open | 2026-05-04 |
| tech-debt | TD-22-B — `EncodeDeflatePerformanceGateTest.encodeDeflateUnder100BotsTickDrift` `@Disabled`; real perf regression. Bisect during P21 benchmark gate. | open | 2026-05-04 |
| tech-debt | TD-22-C — `PopulationDynamicsTest.allThreeTypesSurvive500Ticks` `@Disabled`; probabilistic flat-line. Pin RNG seed or widen tolerance — backlog Phase 999.x. | open | 2026-05-04 |
| tech-debt | TD-22-D — `HundredBotIntegrationTest` `connectLatch.await(30s)` race against 100 sequential `WebSocketClient.start()` cold-starts. Bump to 60s or share single client. Defer to P22.1. | open | 2026-05-04 |
| tech-debt | TD-22-E — `forkEvery=1` masks leaks rather than fixing them. Final exit gate (`forkEvery=0` + <100 live threads) deferred to P22.1. | open | 2026-05-04 |
| tech-debt | TD-20-01c-A — `OutboundSender.drainLoop` records `frame.size.bytes` BEFORE the synchronized `sendMessage` block; on `IOException` the metric counts a frame that was never sent. Trivial impact on saturation gauge; real impact only if downstream tooling reads `frame.size.bytes` for precise egress accounting. Move `recordFrameSize` after successful `sendMessage` (~5 lines). | open | 2026-05-20 |
| tech-debt | TD-20-01c-B — Pre-existing `markStalled→cleanupBot→cleanupByEntityId` double-dec on path B (stalled-then-close-without-reconnect-before-grace-expire). Same `cleanupBot` fallback root cause as the D1-introduced path-C bug; closed incidentally by Plan 20-01c pass-2 H1 (cleanupBot skips active-bucket dec when `entityId == null`). | closed | 2026-05-21 |
| tech-debt | TD-20-01c-C — D2 `OutboundSender.drainLoop` swallows `sample.stop(...)` RuntimeException with `log.warn` only; no counter tracks frequency. Add `paralife.outbound.encode.send.stop.failures` counter (~3 LOC) if the warn ever fires in steady-state. Observability nice-to-have. | open | 2026-05-21 |
| tech-debt | TD-20-01c-D — D3 `registerOutboundQueueDepthMaxGauge` check-then-set is non-atomic. Theoretical only (production single-threaded `@PostConstruct`). Consider `AtomicReference.compareAndSet` OR a one-line invariant comment. | open | 2026-05-21 |
| tech-debt | TD-20-01c-E — `WorldWebSocketHandler.handleTransportError:293-298` calls `cleanupBot(session)` unconditionally — even for stalled sessions, which violates the Phase 17 D-12 "entity held on grid for grace-expiry sweep" invariant (the cell is freed at transport-error time, so client cannot rebind to the original position on reconnect). Pre-existing; surfaced by pass-3 H1 analysis (counter math stays correct via grace-expire dec, but grid state is wrong). Fix: skip cleanupBot if `wasStalled`, mirroring `afterConnectionClosed:388-397`. | open | 2026-05-21 |
| tech-debt | TD-20-01c-F — 20-01c-SUMMARY §Active-Population prose accuracy (pass-4 triage, MVP-prose; transport-health table + R-P3-1 fix are verified-correct, this is doc-only). (a) `actions/bot/tick = 0.6–0.66` (SUMMARY:124) doesn't reconcile with the section's own `actions_sent` ÷ (bots × ~180 ticks) ≈ 1.3 — figure or its denominator is wrong; show the arithmetic or recompute. (b) `actions_sent`, `jdk.ExecutionSample` CPU-attribution table, death-diagnostics, connect/e408 counts are sourced from harness `--report-out` + committed `cpu-*.html`/`jfr-*.jfr` flamegraphs but not cited inline — add provenance pointers. (c) MAX columns floor values (`127.33→127`, `20.58→20`, `34.86→34`) — understates maxima; use one decimal or round-to-nearest. Also re-confirm pass-3 doc-consistency items (tick.health null/deprecated wording) weren't reintroduced. | open | 2026-05-27 |
| tech-debt | TD-PR2-A — `DeathDiagnostics` env-cause precision: tag the true `Cause` at each lethal-damage site (toxin splash → TOXIN via delta type; mutagen DoT → MUTAGEN in `tickBuffsAndInfections`; lightning → LIGHTNING in strike loop) and make `envCauseAt` a labeled fallback + `UNKNOWN` default. Closes M3 misattributions (splash→COMBAT, DoT-off-cell→LIGHTNING, lightning-on-grid→TOXIN). Documented in code; deferred until the Population Viability work needs env-bucket precision. | open | 2026-05-27 |
| tech-debt | TD-PR2-B — `DeathDiagnostics.histogram()` + `causeCounts` (LongAdder map) duplicate the tagged `paralife.diag.deaths` Micrometer counter (two tallies, one unwired). Remove both and query the meter, or wire the planned periodic/shutdown summary. Marked as an intentional hook in javadoc (L1). | open | 2026-05-27 |
| tech-debt | TD-PR2-C — combat-killed `CompositeMember` deaths still default STARVATION: `applyDeltaToOccupant` only hints Particle/BondedPair. Add a member combat hint if/when composites take routed combat damage. Minor census gap; env + decay member deaths are covered post-M1. | open | 2026-05-27 |
| tech-debt | TD-PR2-D — env lethal hints pass literal `preHitEnergy=0`, which reads like a measurement in `DEATH-TRACE`. Re-read occupant energy at the env sweep before finalize, or log `n/a` when unhinted (L3, trivial). | open | 2026-05-27 |
| tech-debt | TD-PR2-E — `DeathDiagnostics.recordDeath` rebuilds the Micrometer `Counter.builder(...).register()` per death (registry lookup). Pre-create the 28 (4 types × 7 causes) counters or cache via `computeIfAbsent` if flag-on death volume proves it measurable. opencode-only, flag-on path only. | open | 2026-05-27 |
| tech-debt | TD-20-02-A — `JettyIdleTimeoutFallbackTest` constructs `JettyRuntimeConfig` directly + passes a primitive legacy value into the helper; cannot catch a broken `@Value("${paralife.websocket.idle-timeout-ms:60000}")` or property-source precedence regression. Helper-logic coverage is the bug surface; `BindingRoundTripTest` exercises Spring binding for new keys; legacy fallback removed in 999.x. Surfaced by multi-review pass 1, codex MEDIUM. See `20-02-MULTIREVIEW-backlog.md`. | open | 2026-06-03 |
| tech-debt | TD-20-02-B — No targeted test observes the resulting Jetty `Configurable` state after `addWebSocketConfigurer` — a silently-dropped setter in `JettyDeflateCustomizer:97-106` only fails via the full-context deflate tests. Mockito-spy on `Configurable` is the cleanup, not a contract gap. Surfaced by multi-review pass 1, codex MEDIUM. See `20-02-MULTIREVIEW-backlog.md`. | open | 2026-06-03 |
| tech-debt | TD-22.1-A — `EligibleCellIndex.add()/remove()` public mutators take only the index lock; a future tick handler calling them under the grid write lock would invert lock order (grid-write→index) and could deadlock against `notifyChanged` (index→grid-read). Verified ZERO production callers today (only `notifyChanged`/`sample`); latent hardening only. Make package-private or assert write-lock-not-held. Surfaced by PR #3 multi-review round 1. See `22-01-MULTIREVIEW-backlog.md`. | open | 2026-06-11 |
| tech-debt | TD-22.1-B — `OutboundSender.@PreDestroy` uses the non-close-aware `detachSession(String)` + sequential O(N×100ms) join; a drain VT mid-`sendMessage` (blocked on `synchronized(session)`) burns the full join + WARNs (the 12 "did not exit" warnings). The untouched `synchronized(session)` half of backlog 999.6 — the reason `forkEvery=1` is retained (TD-22-E). Use the close-aware overload + parallel detach. Surfaced by PR #3 multi-review round 1. See `22-01-MULTIREVIEW-backlog.md`. | open | 2026-06-11 |
| tech-debt | TD-22.1-C — `BotClient.disconnect()` is not truly synchronous: a racing Jetty `onClose` can win the `clientStopped` CAS and move `c.stop()` onto the async commonPool task, so `disconnect()` may return before pools are released. Leak-free (client still stops); only the timing contract is loose (leak-probe false-positive source). Have `stopClientAsync()` return a future for `disconnect()` to join. Surfaced by PR #3 multi-review round 1. See `22-01-MULTIREVIEW-backlog.md`. | open | 2026-06-11 |
| tech-debt | TD-22.1-D — `BotClientTerminalCloseStopsClientTest` asserts `isClientStopped()`/`hasLiveClient()` (flip when CAS wins / field nulled) — proves the stop path fires + no client retained, not that scheduler/qtp threads died. The 4 PR #3 tests pin every fix path; a thread-disappearance assertion (via `LeakCensusListener.normalise`) is additive + flaky-prone. Surfaced by PR #3 multi-review round 1. See `22-01-MULTIREVIEW-backlog.md`. | open | 2026-06-11 |
| tech-debt | TD-22.1-E — `BotClient.connect()` lazy-init (`if (c==null){ fresh.start(); this.client=fresh; }`) is non-atomic for *concurrent* non-shutdown connect — two concurrent calls could both start a client, leaking the loser's. Unreachable today (initial launch-VT connect + once-per-`onClose` reconnect are sequential). Gate create on an `AtomicBoolean`/`ReentrantLock` (NOT `synchronized` — `stop()` blocks, pins the carrier) if a second trigger is ever added. Surfaced by PR #3 multi-review round 2. See `22-01-MULTIREVIEW-backlog.md`. | open | 2026-06-11 |

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260502-sds | implement planned P19 fixes from multi-reviews | 2026-05-02 | 3960fcc | [260502-sds-implement-planned-p19-fixes-from-multi-r](./quick/260502-sds-implement-planned-p19-fixes-from-multi-r/) |

## Session Continuity

Last session: 2026-05-27 (session-drift reconciliation)
Stopped at: Phase 20 plan-by-plan — 20-01 / 20-01c / 20-03 done (20-01b superseded); 20-01c hardened pass-2..4 (PR#1) + out-of-band diagnostics shipped (PR#2); 20-02 / 20-04 / 20-05 / 20-06 pending
Resume file: .planning/phases/20-connection-multiplexing-runtime-tuning/.resume-state.md (refreshed 2026-05-27 — wave model retired, plan-by-plan now)
Next command: `/gsd-execute-phase 20 --plan 20-02` (paralife.runtime.jetty.* @ConfigurationProperties + Jetty wiring) — citable baseline is now `profiles/*-baseline-1818eeb.*` (20-01c), not the c22e487 capture

## Regression Alarm — fast-track P22.1 if any reappear during P20/P21

- Sender-VT "did not exit" warnings reappear in any test run
- Leaked-thread count regresses
- "Could not write XML" volume regresses (>1 per failed test)

**Planned Phase:** 19 (high-density-placement-partition-aware-world-execution) — 4 plans — 2026-05-01T03:22:18.863Z

## Accumulated Context

### Roadmap Evolution

- Phase 22 added: Integration test resource leak audit (2026-05-03; trigger — `WorldGridTest.concurrentReadsDontBlock` 2h hang from carrier starvation, 497 leaked threads in shared test JVM. Quick fixes shipped: bounded join, global JUnit timeout, unconditional forkEvery=1. SEED.md in phase dir.)
- Phase 22 closing 2026-05-04: ran out-of-order as incident response. A1 (OutboundSender close-then-interrupt detach) shipped `42e9251`. 3 perf tests (Metabolism / EncodeDeflate / PopulationDynamics) `@Disabled` with TD pointers; HundredBot connect-race deferred to P22.1. Restored sequence: P19.5 rework → P20 → P21 → P22.1 (revalidation). Phase 22.1 stub + Phase 999.x backlog added.
- Phase 19.1 inserted after Phase 19: Address P19 multi-review pass-4 findings (F1/F2/F3 unshipped, F4 RNG determinism, MS markStalled deadlock) plus residual phase items per 19-MULTI-REVIEW-pass4-TRIAGE.md (URGENT)
- 20-01c hardening close (2026-05-20 → 2026-05-27): the 20-01c baseline plan (counted done 2026-05-19) underwent multi-review passes 2/3/4 — code fixes for outbound-queue gauge double-register, markDead/cleanupBot active-bucket dec leaks (commits ef26da2..f42abe6), SUMMARY rewrites, and 3-tier re-captures. Landed via **PR #1** (worktree `worktree-phase-20-01c-baseline-rebuild`, merged `ddf0dfa`). No new GSD plan — all under the 20-01c plan umbrella. Residual doc/observability nits parked as TD-20-01c-A..F.
- **Out-of-band feature — diagnostics instrumentation (2026-05-25 → 2026-05-27, NOT a planned phase):** `com.paralife.diagnostics.DeathDiagnostics` (flag-gated death-cause + lifespan census) shipped to `main` via `feat(diagnostics)` 40cc5b7 then **PR #2** (`feat/diagnostics-instrumentation`, merged `464594e`, multi-reviewed, 4/4 H1/M1/M2/M3 fixed). **Gated `@ConditionalOnProperty(paralife.diagnostics.death-trace.enabled, havingValue=true)` — OFF by default, no yaml key, zero default-behaviour change.** Wired (no-op unless enabled) into SimulationEngine / EnvironmentEngine / DeathFinalizer / OutboundSender / LiveEntityRegistry. Provenance for the new package lives here + commits + `20-PR2-REVIEW-*` artifacts; follow-ups parked as TD-PR2-A..E. Not assigned a SCALE-* requirement; relates to the visualiser-gated Population Viability work (balance tuning deferred). Promote to a formal phase only if it needs further build-out.
