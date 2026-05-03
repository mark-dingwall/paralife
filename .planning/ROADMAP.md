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
**Plans:** 4/4 plans complete
**Success Criteria:**
- High-density runs avoid pathological spawn collision patterns against rocks and occupied cells.
- World execution gains a partition-aware path or equivalent scale structure that can grow without changing observed simulation semantics.
- Current simulation semantics remain stable at existing milestone workloads.
- Deterministic or explainable placement behavior exists for repeated large runs.

Plans:
- [x] 19-01-placement-index-PLAN.md — EligibleCellIndex sparse-set + WorldWebSocketHandler placement refactor + 3 Wave-0 tests (SCALE-06)
- [x] 19-02-live-entity-registry-PLAN.md — LiveEntityRegistry bean + lifecycle hooks at WS register / DeathFinalizer / SimulationEngine.{revertToBondedPair, dissolveToParticles, updateBotRegistryForFormation, checkPanicZone} (SCALE-07 foundation)
- [x] 19-03-golden-trace-equivalence-PLAN.md — OutboundSender FrameEmitListener seam + GoldenTraceEquivalenceTest (D-10 semantic-equivalence gate, must be green BEFORE Plan 04 lands) (SCALE-07)
- [x] 19-04-entity-list-iteration-PLAN.md — SimulationEngine + EnvironmentEngine per-entity segments via LiveEntityRegistry.snapshot() (TickBroadcaster migration deferred to Phase 20.1+ per H1 Option B) (SCALE-07)

### Phase 19.1: Address P19 multi-review pass-4 findings (F1/F2/F3 unshipped, F4 RNG determinism, MS markStalled deadlock) plus residual phase items per 19-MULTI-REVIEW-pass4-TRIAGE.md (INSERTED)

**Goal:** Land the three F1/F2/F3 unshipped P19 fixes that the pass-2 VALIDATED projection wrongly marked shipped, harden RNG determinism (F4/CE/TX), close the markStalled deadlock (MS), and clear residual lifecycle leaks (AM/BL/FL) and test gaps (GT/LM/EL/codec gate) — all before Phase 20 starts compounding them.
**Requirements**: None — bug-fix hardening of P19/P19.5 work; reliability for SCALE-06/SCALE-07
**Depends on:** Phase 19
**Plans:** 6 plans
**Success Criteria:**
- F1 fixed: composite formation uses `remapEntity`; `BotRegistry.drainDeaths()` empty after formation under deterministic scenario
- F2 fixed: `PerceptionCodec.validateEventCode` accepts `'B'`; round-trip test exists for every code declared in `Event.java`
- F3 fixed: `WorldWebSocketHandler.markDead` calls `ResumeTokenRegistry.clearActive` and removes `ATTR_RESUME_TOKEN`; tokenMap empty after solo-death-without-reconnect
- F4/CE/TX: same-seed run produces byte-exact buff, composite-energy, and toxin-path outcomes across 2 single-threaded runs
- MS: `markStalled` integration test asserts tick-thread service-time stays within a small bound under stuck-VT scenario
- BotClient disconnect leaves zero residual entries in `BuffRegistry`, infection map, FLEEING map, and `AdmissionMetrics` bucket-tags map
- `GoldenTraceWithActionsTest` ships with pinned M/E/R/V baseline; `LiveEntityRegistryInvariantTest` covers movement + reproduction
- `CLAUDE.md` `@Order` table matches `grep -r '@Order' src/main`
- Existing 166-test suite stays green

Plans:
- [ ] 19.1-01-PLAN.md — Unshipped fix verification & landing — F2 codec 'B', F1 composite remap, F3 markDead clearActive
- [ ] 19.1-02-PLAN.md — Determinism hardening — F4 sort pendingGrants, CE sort registry snapshot, TX seeded ToxinPathGenerator + EnvironmentDeterminismTest extensions
- [ ] 19.1-03-PLAN.md — Backpressure correctness — MS close-aware markStalled + tick-bound integration test, EL VT-exit assertion, L1 detach-timeout metric
- [ ] 19.1-04-PLAN.md — Lifecycle leaks — AM remapBucketTags, BL cleanupBot env-state cleanup, FL composite-boundary FLEEING transfer
- [ ] 19.1-05-PLAN.md — Test coverage — GT GoldenTraceWithActionsTest, LM movement/reproduction invariants, codec convention gate
- [ ] 19.1-06-PLAN.md — Doc + nits — OD CLAUDE.md @Order accuracy, FE/FD OutboundSender Javadoc, ES processInteractions snapshot hoist

**Cross-cutting constraints:**
- Existing 166-test suite stays green

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

### Phase 20.1: Restore Composite Vision (SENSOR-stitched perception)
**Goal:** Restore the SENSOR-based stitched perception that Phase 12 designed and Phase 15's protocol overhaul dropped, so SENSOR role members produce tangible value to their composite — extended FOV that informs LOCOMOTOR voting and AUTHORITY_LITE tactical decisions, rather than rest-acting passively.
**Depends on:** Phase 19 (golden-trace equivalence gate must exist before perception semantics change), Phase 20 (avoid colliding with transport tuning churn)
**Requirements:** None — post-Phase-15 design recovery; no SCALE-* assignment
**Plans:** 0 plans
**Success Criteria:**
- LOCOMOTOR's tick frame carries a stitched neighbourhood derived from the union of SENSOR member 5×5 cells, deduplicated and deterministically ordered.
- Composite without any SENSOR member retains today's per-member FULL / AUTHORITY_LITE / PASSIVE behaviour (no regression).
- D-10 byte-for-byte equivalence is intentionally re-baselined for scenarios containing composites with SENSORs; new golden-trace digests are regenerated and pinned.
- Tests verify that adding a SENSOR strictly widens (never shrinks) LOCOMOTOR's visible cells, and that SENSORs themselves remain PASSIVE on the wire.
- Must land before M5 (Observability & Operations) so the live world visualiser exposes faithful colony perception, not a stub.

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
- Re-enable + pass `MetabolismIntegrationTest` and `EncodeDeflatePerformanceGateTest` under benchmark conditions (TD inherited from Phase 22).

## Backlog

### Phase 999.2: Offspring entities become bot-driven; M5 flower rendering fallback (BACKLOG)
**Goal:** Eliminate the current NPC offspring asymmetry by assigning spawned `child-*` entities to bots or bot-summoning infrastructure. Until that lands, treat unassigned offspring as flowers in the future M5 visualizer so their stationary / edible / ephemeral behavior reads intentionally.
**Requirements:** TBD
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `$gsd-review-backlog` when ready)

### Phase 22: Integration test resource leak audit

**Goal:** Stabilise the shared-JVM test suite after the 2026-05-03 carrier-starvation incident; ship test-infra invariants and defer load-bearing simulation/perf bugs to their natural homes.
**Requirements:** TBD (none mapped — incident-driven)
**Depends on:** none (ran out-of-order as incident response 2026-05-03; restored sequence has P19.5/P20/P21 ahead)
**Status:** closing 2026-05-04 — see `phases/22-integration-test-resource-leak-audit/22-SUMMARY.md`. Revalidation handed to Phase 22.1.
**Plans:** 0 plans (incident response — no formal plan breakdown)

### Phase 22.1: Revalidation of P22 test-infra fixes + deferred items

**Goal:** Confirm P22's load-bearing invariants still hold after P19.5/P20/P21 changes, and address items deferred from P22.
**Depends on:** Phase 21
**Scope:**
- Diff current code vs `.planning/phases/22-integration-test-resource-leak-audit/22-INVARIANTS.md`. Any drift → either re-validate fix or reimplement.
- Re-enable + pass `MetabolismIntegrationTest`, `EncodeDeflatePerformanceGateTest` under benchmark conditions (handed off from P21 SC).
- Fix `HundredBotIntegrationTest` connect-latch race (60 s OR shared `WebSocketClient`).
- Final exit gate: `./gradlew test` with `forkEvery=0` → <100 live threads at end, zero "did not exit" warnings, zero "Could not write XML" errors.

### Phase 999.x: PopulationDynamicsTest determinism (BACKLOG)

Pin RNG seed or widen variation tolerance for `PopulationDynamicsTest.allThreeTypesSurvive500Ticks` (probabilistic flat-line on extinction). Not scale-related; separate from P21/P22.1.
