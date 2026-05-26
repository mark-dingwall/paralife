---
gsd_state_version: 1.0
milestone: v3.0
milestone_name: Scale Engineering
status: executing
stopped_at: Phase 20 Wave 2 partial complete — 20-01b baseline JFR/flamegraph/metric capture shipped; 20-02/04/05/06 pending
last_updated: "2026-05-14T23:38:45.439Z"
last_activity: 2026-05-15 -- Phase 20 Plan 01b complete (baseline measurements captured at c22e487)
progress:
  total_phases: 15
  completed_phases: 4
  total_plans: 34
  completed_plans: 31
  percent: 91
---

# Project State

## Project Reference

See: .planning/PROJECT.md

**Core value:** Emergent spatial behaviour from simple local rules — a testbed for evolving entity intelligence.
**Current focus:** Phase 20 — connection-multiplexing-runtime-tuning

## Current Position

Milestone: v3.0 (Scale Engineering / M4) — active
Phase: 20 (connection-multiplexing-runtime-tuning) — EXECUTING
Plan: 2 of 7
Status: Ready to execute
Last activity: 2026-05-15 -- Phase 20 Plan 01b complete (baseline measurements captured at c22e487)

Progress: [████████▌░] 85%

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
| tech-debt | TD-PR2-A — `DeathDiagnostics` env-cause precision: tag the true `Cause` at each lethal-damage site (toxin splash → TOXIN via delta type; mutagen DoT → MUTAGEN in `tickBuffsAndInfections`; lightning → LIGHTNING in strike loop) and make `envCauseAt` a labeled fallback + `UNKNOWN` default. Closes M3 misattributions (splash→COMBAT, DoT-off-cell→LIGHTNING, lightning-on-grid→TOXIN). Documented in code; deferred until the Population Viability work needs env-bucket precision. | open | 2026-05-27 |
| tech-debt | TD-PR2-B — `DeathDiagnostics.histogram()` + `causeCounts` (LongAdder map) duplicate the tagged `paralife.diag.deaths` Micrometer counter (two tallies, one unwired). Remove both and query the meter, or wire the planned periodic/shutdown summary. Marked as an intentional hook in javadoc (L1). | open | 2026-05-27 |
| tech-debt | TD-PR2-C — combat-killed `CompositeMember` deaths still default STARVATION: `applyDeltaToOccupant` only hints Particle/BondedPair. Add a member combat hint if/when composites take routed combat damage. Minor census gap; env + decay member deaths are covered post-M1. | open | 2026-05-27 |
| tech-debt | TD-PR2-D — env lethal hints pass literal `preHitEnergy=0`, which reads like a measurement in `DEATH-TRACE`. Re-read occupant energy at the env sweep before finalize, or log `n/a` when unhinted (L3, trivial). | open | 2026-05-27 |
| tech-debt | TD-PR2-E — `DeathDiagnostics.recordDeath` rebuilds the Micrometer `Counter.builder(...).register()` per death (registry lookup). Pre-create the 28 (4 types × 7 causes) counters or cache via `computeIfAbsent` if flag-on death volume proves it measurable. opencode-only, flag-on path only. | open | 2026-05-27 |

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260502-sds | implement planned P19 fixes from multi-reviews | 2026-05-02 | 3960fcc | [260502-sds-implement-planned-p19-fixes-from-multi-r](./quick/260502-sds-implement-planned-p19-fixes-from-multi-r/) |

## Session Continuity

Last session: 2026-05-11T01:56:54Z
Stopped at: Phase 20 Wave 1 complete (20-01 toolchain bootstrap shipped at d7009df)
Resume file: .planning/phases/20-connection-multiplexing-runtime-tuning/.resume-state.md
Next command: `/gsd-execute-phase 20 --wave 2 --interactive` — Wave 2 runs 20-01b (operator-driven JFR baseline captures @ c22e487, 100/500/1000 bots — see profiles/README.md ritual) and 20-03 (`AppRuntimeConfig` record, autonomous)

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
