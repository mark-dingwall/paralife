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
- **20** ✅ Connection multiplexing & runtime tuning — JFR profiling toolchain, Jetty/app `@ConfigurationProperties`, `docs/RUNTIME.md`.
- **20.1** ✅ Restored SENSOR-stitched composite perception (sensory-organ model; LOCOMOTOR sees the SENSOR union).
- **22** ✅ Integration-test resource-leak audit — ran **out-of-band** as a 2026-05-04 carrier-starvation incident response (not in sequence).
- **21** ✅ Scale Benchmark Gate & Reports — real 100/500/1000-bot tier evidence (throughput, tick
  drift, session stability, rejection, failure-mode coverage) + the M4↔M5/22.1 boundary statement.
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

## Active / Next

1. **⏭ M5 Observability & Operations** — live world visualiser (the unblocker for the gated work
   below). Inherits the deferred tick-drift regression **tripwire** (absolute p99 / baseline-diff,
   per-slot timers) from 22.1 — an absolute perf gate needs M5's stable capacity rig to set a
   meaningful bound. No phase shape drafted yet.

## Later

- (M5 promoted to *Active / Next* — see above.)

## Gated

- **🚫 Emergence / balance-tuning *campaign*** (env, metabolic, global constants) — the full A/B campaign
  stays **GUI / M5-gated**: a 1-D scalar can Goodhart-drive the spatial emergence that is the Core Value,
  so it wants eyes. Death-treadmill sits at prod defaults (~78% starvation) by deliberate deferral.
  *Measurement/tuning split resolved* — [`docs/notes/headless-feedback-loop-adr.md`](docs/notes/headless-feedback-loop-adr.md)
  (**Accepted, 2026-07-01**): *measuring* emergence was never blocked, only *tuning* to it. Death-cause
  **counts are observe-only emergence** (banked to `CLAUDE.md` firewall corollary). The full backlog
  cluster (path-alpha existence proof, cheap gauge, ensemble-N campaign, foraging assay, invariant/checksum
  substrate) lives in [`BACKLOG.md`](BACKLOG.md) §Headless feedback-loop + emergence testing. The campaign
  itself stays gated: **Phase 21 stable AND a spatial-emergence guard exists** (M5 eyes, or a headless
  spatial invariant). Partially supersedes `MEMORY.md` → balance-tuning-deferred (measuring OK, tuning
  deferred).
- **🚫 Population Viability phase** (drafted) — visualiser-gated for the same reason.

## Horizon

- **v4.0 Entity intelligence** — evolve bots from heuristic toward genetic / learning systems. This is
  the project's stated core-value testbed direction; no milestone shape yet.

## Process lanes (post-GSD habits, run alongside phases)

- **Docs editorial** — see *Active / Next #1*.
- **EARS anchor sweep** — opportunistic. Convert remaining oracle-shared / constant-referential clauses
  into clause-isolating anchors, as piloted on SCHEMA §0 R1/R2. Done: SCHEMA R4/R5/R6 encode-isolating
  anchors (PR #19); HARNESS §0 authored (17 clauses, 2026-07-07). Remaining live target: ADMISSION §0
  anchor-hardening (arm the cap gate for precedence edges; `@slow`-only A14/A22 twins; token-string
  literals). Tracked in [`BACKLOG.md`](BACKLOG.md).
- **Deferred / tech-debt** — `999.x` items (offspring agency, verb-role coupling, VT-pinning conversion,
  namespace consolidation, JFR re-baseline) live in [`.planning/ROADMAP.md`](.planning/ROADMAP.md)
  §Backlog and [`BACKLOG.md`](BACKLOG.md). Promote when their trigger fires.
