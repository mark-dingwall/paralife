# Paralife Roadmap

Internal milestone tracker. `v1.0` / `v2.0` are milestone IDs, not release versions.

## Milestones

- ✅ **v1.0 Foundation & Living Simulation** — Phases 01-10 (completed 2026-04-12)
- ✅ **v2.0 Combination & Emergence** — Phases 11-16 plus 15.1/15.2 follow-ups (completed 2026-04-22)
- 🚧 **v3.0 Scale Engineering (M4)** — Phases 17-21 (active)

## Archived Milestones

<details>
<summary>v1.0 — Foundation & Living Simulation</summary>

- Archive: `.planning/milestones/v1.0-ROADMAP.md`
- Audit: `.planning/milestones/v1.0-MILESTONE-AUDIT.md`

</details>

<details>
<summary>v2.0 — Combination & Emergence</summary>

- Archive: `.planning/milestones/v2.0-ROADMAP.md`
- Requirements: `.planning/milestones/v2.0-REQUIREMENTS.md`
- Audit: `.planning/milestones/v2.0-MILESTONE-AUDIT.md`

</details>

## Roadmap v3.0: Scale Engineering (M4)

Prove the architecture handles large-scale externally driven load without losing simulation correctness, operational control, or reproducibility.

### Phase 17: Durable Admission Control & Backpressure ✅
**Status:** Verified 2026-04-28 — see `phases/17-durable-admission-control-backpressure/17-VERIFICATION.md` (passed; 4/4 SC; 12/12 must-haves; 662 tests green; SLI item carried forward to Phase 21)
**Goal:** Replace the temporary world cap with a durable admission-control policy and explicit overload/backpressure behavior that preserves tick health under stress.
**Depends on:** Phase 16 (baseline emergence stack and temporary cap behavior)
**Requirements:** SCALE-01, SCALE-02
**Plans:** 11 plans
**Success Criteria:**
- Register / respawn admission is governed by a durable policy rather than a temporary fixed cap.
- Over-cap and overload paths return explicit, testable rejection reasons and expose operator-visible metrics.
- Slow or overloaded clients cannot drive unbounded tick drift or silent session churn.
- The temporary `999.1` stopgap is superseded by milestone-owned behavior.

Plans:
- [x] 17-01-PLAN.md — AdmissionConfig record + RejectionToken constants + 17-ADMISSION.md spec doc
- [x] 17-02-PLAN.md — Codec extension for resume-token slot on r| and S| frames
- [x] 17-03-PLAN.md — AdmissionGate bean + AdmissionMetrics tagged counter and gauges
- [x] 17-04-PLAN.md — TickHealthMonitor rolling-window hysteresis gate + TickEngine.lastTickWorkMs
- [x] 17-05-PLAN.md — ResumeTokenRegistry mint/lookup/expiry sweep with grace window
- [x] 17-06-PLAN.md — OutboundSender per-session VT-per-queue with overflow callback
- [x] 17-07-PLAN.md — WorldWebSocketHandler refactor: delegate admission, retoken errors, STALLED FSM
- [x] 17-08-PLAN.md — TickBroadcaster refactor: enqueue via OutboundSender, remove synchronized(session)
- [x] 17-09-PLAN.md — BotClient resume-token storage + STALLED-pivot reconnect
- [x] 17-10-PLAN.md — Migration: delete PopulationCapConfig, migrate application.yml + LoadTest, ActionResolver D-09 counter, CLAUDE.md Outbound concurrency
- [x] 17-11-PLAN.md — Integration tests: STALLED recovery, hysteresis gate, log markers

### Phase 18: External Load Harness & Harness Identity ✅
**Status:** Verified 2026-04-28 — see `phases/18-external-load-harness-harness-identity/18-VERIFICATION.md` (4/4 SC; status: human_needed for `loadHarnessJar` `--help` smoke; cross-AI review remediation chunks A/B/C complete 2026-04-30, full suite green)
**Goal:** Build the first-class external load harness that scales past `BotRunner`'s single-process limit and attributes sessions and metrics per harness instance.
**Depends on:** Phase 17 (durable admission contract must exist before scale traffic is generated)
**Requirements:** SCALE-03, SCALE-04, SCALE-05
**Plans:** 6 plans
**Success Criteria:**
- A standalone harness can launch and sustain large-N bot fleets outside the Gradle `runBot` path.
- Harness-origin identity is attached to load traffic and visible in server-side metrics or logs.
- `BotRunner` remains the recommended operator path for <=100 bots.
- Harness behavior is documented well enough to reproduce benchmark runs.

Plans:
- [x] 18-01-PLAN.md — BotIdentity + BotClientOptions + BotClient handshake-header injection (SCALE-04 client foundation)
- [x] 18-02-PLAN.md — Server-side handshake-header read + ATTR_SOURCE/ATTR_HARNESS + HARNESS log markers + ADMISSION/BACKPRESSURE marker extensions (SCALE-04 server ingress)
- [x] 18-03-PLAN.md — AttributionTagger + AdmissionConfig.AttributionConfig + two-tag metric emission + MeterFilter cardinality cap (SCALE-04 metric surface + T-18-02 mitigation)
- [x] 18-04-PLAN.md — BotFactory seam (D-19) + BotFleet async launcher (D-04) + BotRunner regression (SCALE-03 / SCALE-05)
- [x] 18-05-PLAN.md — LoadHarness main + Picocli CLI + JSON/JSONL ReportWriter + Gradle loadHarnessJar/runHarness tasks (SCALE-03)
- [x] 18-06-PLAN.md — 18-HARNESS.md spec doc + CLAUDE.md Connection model + AttributionRebindTest + LoadTest harness-tagged migration (SCALE-03/04/05 lock-in)

### Phase 19: High-Density Placement & Partition-Aware World Execution
**Goal:** Introduce scale-path world execution and placement mechanics that keep dense runs fair, reproducible, and semantically equivalent to current behavior.
**Depends on:** Phase 18 (real harness needed to exercise dense placement)
**Requirements:** SCALE-06, SCALE-07
**Plans:** 4 plans
**Success Criteria:**
- High-density runs avoid pathological spawn collision patterns against rocks and occupied cells.
- World execution gains a partition-aware path or equivalent scale structure that can grow without changing observed simulation semantics.
- Current simulation semantics remain stable at existing milestone workloads.
- Deterministic or explainable placement behavior exists for repeated large runs.

Plans:
- [ ] 19-01-placement-index-PLAN.md — EligibleCellIndex sparse-set + WorldWebSocketHandler placement refactor + 3 Wave-0 tests (SCALE-06)
- [ ] 19-02-live-entity-registry-PLAN.md — LiveEntityRegistry bean + lifecycle hooks at WS register / DeathFinalizer / SimulationEngine.collapseToMember (SCALE-07 foundation)
- [ ] 19-03-golden-trace-equivalence-PLAN.md — OutboundSender FrameEmitListener seam + GoldenTraceEquivalenceTest (D-10 semantic-equivalence gate, must be green BEFORE Plan 04 lands) (SCALE-07)
- [ ] 19-04-entity-list-iteration-PLAN.md — SimulationEngine + EnvironmentEngine per-entity segments + TickBroadcaster.onTick @Order(50) consume LiveEntityRegistry.snapshot() (SCALE-07)

### Phase 20: Connection Multiplexing & Runtime Tuning
**Goal:** Reduce socket/process overhead and tune the runtime for sustained high bot counts without regressing the compact protocol semantics.
**Depends on:** Phase 19 (world execution path must be in place before tuning transport overhead)
**Requirements:** SCALE-08, SCALE-09
**Plans:** 0 plans
**Success Criteria:**
- Connection fan-in / multiplexing or an equivalent overhead-reduction path exists for high bot counts.
- Virtual-thread or runtime tuning guidance is grounded in measured profiles rather than guesswork.
- The tuned system still passes the existing protocol and regression tests.
- Operators have concrete configuration guidance for benchmark runs.

### Phase 21: Scale Benchmark Gate & Reports
**Goal:** Close M4 with repeatable benchmark evidence for 100, 500, and 1000+ bot runs, including throughput, stability, and tick-health reporting.
**Depends on:** Phase 20 (all scale-path implementation and tuning work complete)
**Requirements:** SCALE-10
**Plans:** 0 plans
**Success Criteria:**
- Benchmark runs exist for 100, 500, and 1000+ bots with repeatable commands and saved reports.
- Reports cover tick drift, session stability, throughput, rejection counts, and major failure modes.
- The milestone establishes a new validated scale envelope beyond the original 100-bot baseline.
- M4 closes with clear evidence for what still belongs to M5/M6 versus what is solved here.

## Backlog

### Phase 999.2: Offspring entities become bot-driven; M5 flower rendering fallback (BACKLOG)
**Goal:** Eliminate the current NPC offspring asymmetry by assigning spawned `child-*` entities to bots or bot-summoning infrastructure. Until that lands, treat unassigned offspring as flowers in the future M5 visualizer so their stationary / edible / ephemeral behavior reads intentionally.
**Requirements:** TBD
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `$gsd-review-backlog` when ready)
