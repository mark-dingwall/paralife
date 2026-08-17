# Roadmap

Forward intent — the sequenced companion to [`BACKLOG.md`](BACKLOG.md) (which holds *unsequenced*,
opportunistic, and deferred work). This is the live, lean roadmap the GSD graduation left open: habit,
not machinery. Per-phase detail, success criteria, and decision registers are frozen under
[`.planning/ROADMAP.md`](.planning/ROADMAP.md) and [`.planning/MILESTONES.md`](.planning/MILESTONES.md)
— read them for facts, never copy the phase/PLAN/SUMMARY ceremony onto new work.

Status keys: ✅ done · ⏭ next · ⏳ later · 🚫 gated (blocked on a prerequisite that doesn't exist yet).

---

## Done

**v1.0 Foundation** (Phases 01–10, 2026-04-12) — Spring Boot + Java 21 virtual threads, toroidal
256×256 grid, tick engine, raw WebSocket at `/ws/world`, sealed `Entity` model, RPS combat + energy
decay + nutrient spawning, heuristic bots; 100-bot concurrent load verified.

**v2.0 Combination & Emergence** (Phases 11–16, 2026-04-22) — bonding, composites, richer metabolism,
environment layer (toxins/mutagens/seasons/lightning); compact codec wire protocol on Jetty 12
(replaced JSON), permessage-deflate, zero-trust vision-scoped perception; emergence metrics + seeded
determinism hooks + long-run fixtures.

**v3.0 Scale Engineering (M4)** (Phases 17–22, 2026-07-04) — complete:
- **17** ✅ Durable admission control & backpressure — resume-token FSM, overload/backpressure paths, tagged metrics (replaced the temporary world cap).
- **18** ✅ External load harness + per-instance harness-identity attribution (scales past `BotRunner`'s single process).
- **19** ✅ High-density placement + partition-aware world execution — `LiveEntityRegistry`, golden-trace semantic-equivalence gate.
- **19.1** ✅ P19 review-finding hardening — RNG determinism, lifecycle-leak closure, `markStalled` deadlock fix.
- **20** ✅ WS:entity 1:1 connection model & runtime tuning — JFR profiling toolchain, Jetty/app `@ConfigurationProperties`, `docs/RUNTIME.md`.
- **20.1** ✅ Restored SENSOR-stitched composite perception (sensory-organ model; LOCOMOTOR sees the SENSOR union).
- **22** ✅ Integration-test resource-leak audit — ran **out-of-band** as a 2026-05-04 carrier-starvation incident response (not in sequence).
- **21** ✅ Scale Benchmark Gate & Reports — real 100/500/1000-bot tier evidence (throughput, tick
  work-time/headroom, session stability, rejection, failure-mode coverage) + the M4↔M5/22.1 boundary statement.
  Ran ahead of the docs editorial pass (sequencing decision A superseded, mirroring 22's
  out-of-band precedent). **M4 closed** — see [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).
- **Docs editorial pass** ✅ — restructured `docs/` + added a STYLE guide; `CLAUDE.md` / `README.md`
  updated for relocations (`6ae67f9`, `b266d79`).
- **22.1** ✅ **P22 revalidation & test-debt closeout** (`b2983b0`, PR #18) — resource-leak
  invariants confirmed still holding post-P20/P21 (full suite green under `forkEvery=0`); re-enabled
  `EncodeDeflatePerformanceGateTest` as a portable `@Tag("slow")` starvation guard; widened the
  `HundredBotIntegrationTest` latch (a timeout-under-carrier-starvation flake, **not** a data race);
  fixed stale `forkEvery=1` doc-drift. A cheap in-test super-linearity regression check was attempted
  and **backed out** — tick-work wall-clock is too noise-dominated at this (11× headroom) scale to
  gate on (evidence in `BACKLOG.md`); the regression **tripwire** is deferred to M5 with the rest
  (needs a stable capacity rig).
- **M5-A** ✅ **Live world visualiser** (`b35a492`, PR #27) — read-only `/ws/observer` JSON
  bootstrap/world frames, operator page, full-world species/environment rendering, population
  time-series, session cap, and off-thread latest-wins delivery. Ships disabled by default.

## Active / Next

1. **⏭ M5-B Operations dashboards** — shape the admission/tick/WebSocket health surfaces from the
   existing actuator and Micrometer signals. No implementation shape is drafted yet.

## Later

- **⏳ M5-C tick scheduling/drift regression tripwire** — the cheap in-test wall-clock ratio was
  backed out in 22.1. A meaningful absolute-p99 or baseline-diff gate still needs a stable
  500–1000-bot capacity rig and, ideally, per-slot timers.
- **⏳ Emergence / balance-tuning campaign** — now eligible to schedule: Phase 21 is stable and
  M5-A supplies the human spatial-emergence guard. Scheduling still requires an explicit tuning
  decision; death-cause counts remain observe-only and any automated emergence gate stays ordinal,
  control-anchored, and `@Tag("slow")`.
- **⏳ Population Viability phase** (drafted) — unblocked by M5-A; schedule when selected.

## Gated

- (No roadmap item is currently blocked on a missing prerequisite; future v4 work remains unshaped.)

## Horizon

- **v4.0 Entity intelligence** — evolve bots from heuristic toward genetic / learning systems. This is
  the project's stated core-value testbed direction; no milestone shape yet.

## Process lanes (post-GSD habits, run alongside phases)

- **Docs editorial** — opportunistic canonical-doc merge-backs; the prior editorial pass is complete.
- **EARS anchor sweep** — opportunistic. Convert remaining oracle-shared / constant-referential clauses
  into clause-isolating anchors, as piloted on SCHEMA §0 R1/R2. Done: SCHEMA R4/R5/R6 encode-isolating
  anchors (PR #19); HARNESS §0 authored (17 clauses); ADMISSION §0 precedence edges pinned (A25–A27,
  cap-gate arming); rejection token-string literals pinned (A28, `RejectionTokenWireTest`);
  condition→token routing for the `no-active-entity`/`malformed`/`grid-full`/`reconnect-required`
  handler tokens pinned (A29/A30/A31/A32); A14/A22 stall-recovery mechanism default-gated
  (respawn-count restore twin + recognition of existing A10/A12/A13 coverage; E2E stays `@slow`).
  Remaining marker-shape gaps are tracked in [`BACKLOG.md`](BACKLOG.md).
- **Deferred / tech-debt** — `999.x` items (offspring agency, verb-role coupling, VT-pinning conversion,
  namespace consolidation, JFR re-baseline) live in [`.planning/ROADMAP.md`](.planning/ROADMAP.md)
  §Backlog and [`BACKLOG.md`](BACKLOG.md). Promote when their trigger fires.
