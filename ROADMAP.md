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

**v3.0 Scale Engineering (M4)** — in progress; completed so far:
- **17** ✅ Durable admission control & backpressure — resume-token FSM, overload/backpressure paths, tagged metrics (replaced the temporary world cap).
- **18** ✅ External load harness + per-instance harness-identity attribution (scales past `BotRunner`'s single process).
- **19** ✅ High-density placement + partition-aware world execution — `LiveEntityRegistry`, golden-trace semantic-equivalence gate.
- **19.1** ✅ P19 review-finding hardening — RNG determinism, lifecycle-leak closure, `markStalled` deadlock fix.
- **20** ✅ Connection multiplexing & runtime tuning — JFR profiling toolchain, Jetty/app `@ConfigurationProperties`, `docs/RUNTIME.md`.
- **20.1** ✅ Restored SENSOR-stitched composite perception (sensory-organ model; LOCOMOTOR sees the SENSOR union).
- **22** ✅ Integration-test resource-leak audit — ran **out-of-band** as a 2026-05-04 carrier-starvation incident response (not in sequence).

## Active / Next

> **Sequencing decision (A):** the docs editorial pass comes **before** Phase 21 — we're working in
> the docs/process space now, the context-cost is real and recurring, and Phase 21 leans heavily on
> `SCHEMA`/`ARCHITECTURE`/`RUNTIME` (cheaper to reference once they're lean).

1. **⏭ Docs editorial pass** *(process lane — next actionable)* — restructure `docs/`, trim prose to
   the load-bearing meat, establish an editorial style guide, and update `CLAUDE.md` + `README.md` for
   any relocations. One atomic pass (don't restructure then re-trim). See *Process lanes* below.
2. **⏭ Phase 21 — Scale Benchmark Gate & Reports** *(M4 close; the product spine)* — repeatable
   benchmark evidence for 100 / 500 / 1000+ bot runs: throughput, tick drift, session stability,
   rejection counts, failure modes. 0 plans yet. Unblocked (mechanism, not emergence → not
   visualiser-gated). Closes M4 with a validated scale envelope beyond the original 100-bot baseline.

## Later

- **⏳ Phase 22.1 — P22 revalidation** *(after 21)* — confirm the resource-leak invariants still hold
  post-P20/P21; re-enable + pass `MetabolismIntegrationTest` and `EncodeDeflatePerformanceGateTest`
  under benchmark conditions; fix the `HundredBotIntegrationTest` connect-latch race.
- **⏳ M5 Observability & Operations** — live world visualiser (the unblocker for the gated work below).

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
  into clause-isolating anchors, as piloted on SCHEMA §0 R1/R2. Live targets: ADMISSION §0
  anchor-hardening, the remaining round-trip-joint SCHEMA clauses (R4/R5/R6/R17), the HARNESS §0 gate.
  Tracked in [`BACKLOG.md`](BACKLOG.md).
- **Deferred / tech-debt** — `999.x` items (offspring agency, verb-role coupling, VT-pinning conversion,
  namespace consolidation, JFR re-baseline) live in [`.planning/ROADMAP.md`](.planning/ROADMAP.md)
  §Backlog and [`BACKLOG.md`](BACKLOG.md). Promote when their trigger fires.
