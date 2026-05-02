# Phase 20: Connection Multiplexing & Runtime Tuning - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-02
**Phase:** 20-connection-multiplexing-runtime-tuning
**Areas discussed:** Multiplex shape (SCALE-08), Profiling toolchain (SCALE-09), Tuning surface scope, Operator guidance doc

---

## Multiplex shape (SCALE-08)

| Option | Description | Selected |
|--------|-------------|----------|
| Equivalent: keep WS:entity 1:1, attack overhead elsewhere | Skip multiplex. Reduce per-conn cost via Jetty/SO_SNDBUF tuning, encode caching, batch flush, frame coalescing. No D-21 exception ADR. Lowest risk; matches arch principle. | ✓ |
| Sub-WS transport multiplex (HTTP/2 or single-TCP fan-in) | One TCP carries many WS upgrades. Keeps WS:entity 1:1 above transport but cuts TCP/TLS handshake + FD cost. Bigger blast radius. | |
| Genuine multi-entity-per-WS (invoke D-21 exception) | Author D-21 exception ADR + per-frame entity demux + multi-entity codec extension. Highest reward (FD count drops 100x) but mutates locked codec surface and contradicts CLAUDE.md §Connection model. | |
| Defer shape, Phase 20 ships profiles only | Run JFR/async-profiler at 1000 bots first. Pick shape based on which overhead dominates. Risk: Phase 21 then depends on follow-up phase. | |

**User's choice:** Keep WS:entity 1:1 — equivalent transport-level overhead reduction.
**Notes:** User added explicit requirement that the deliberate-choice rationale be documented in code comments AND README.md AND CLAUDE.md. Framing: "pretend each bot is a user connecting from their own machine". Many-concurrent-connections is a stated architectural goal of the project, not an inefficiency to optimise. Captured as D-01 + D-02; D-03 records the trigger condition (Phase 21 evidence + 5000-conn-ceiling-binding) for any future revisit.

---

## Profiling toolchain (SCALE-09)

| Option | Description | Selected |
|--------|-------------|----------|
| JFR + async-profiler, snapshots committed (recommended) | JFR for JVM/GC/VT view, async-profiler for kernel/syscall view. Commit `.jfr` + flamegraph.html under `.planning/phases/20/profiles/`. | ✓ |
| JFR only, snapshots committed | Lighter. Built-in tool only. No kernel/syscall view. | |
| JFR + async-profiler + JMH (codec micro) | Adds JMH benchmarks for `PerceptionCodec` + frame encoders as committed `src/jmh/`. Larger time budget. | |
| JFR + async-profiler, snapshots gitignored | Keep repo lean. No re-derivation possible without rerun at 1000-bot scale. | |

**User's choice:** JFR + async-profiler, snapshots committed (recommended).
**Notes:** User asked for ELI5 of each tool's strengths and trade-offs before deciding. Discussion clarified: JFR = built-in JVM-internal view (GC, VT carriers, allocation); async-profiler = external native sampling (kernel, syscalls, native Jetty); JMH = NOT a profiler but a microbenchmark framework. JMH deferred unless JFR fingers a codec hot path. Committed artifacts honour the project's "evidence over assertion" stance (Phase 17 watermarks-measured precedent).

---

## Tuning surface scope

| Option | Description | Selected |
|--------|-------------|----------|
| JVM presets (doc) + paralife.runtime.* config records + codec internal (recommended) | Doc-only JVM flag presets in 20-RUNTIME.md; new `paralife.runtime.jetty.*` + `paralife.runtime.app.*` `@ConfigurationProperties` records bound from `application.yml`; codec opts as internal constants tuned via JFR. Override via `-D` / env / `@TestPropertySource`. Admin-UI live-tune + automated config search captured deferred. | ✓ |
| Same plus stub admin actuator endpoint (read-only) | Adds `/actuator/runtime-config` GET-only endpoint. Future M5 admin UI consumes. | |
| Same plus minimal sweep harness | Adds `RuntimeSweepHarness` skeleton: reads override scenarios from YAML, runs each as a separate JVM, emits CSV. No search algo. | |
| JVM doc + Jetty config only, leave app knobs as constants | Minimal. Conflicts with Phase 17 D-15 'tuning lives in config' precedent. | |

**User's choice:** JVM presets + paralife.runtime.* records + codec internal (recommended).
**Notes:** Discussion expanded the four-layer tuning surface (JVM / Jetty / Application / Codec) with change-time properties and ownership. User raised two future-facing requirements: (a) admin observer UI for runtime knobs (readonly for non-admin, mutable for admin) — captured deferred to M5 with `@RefreshScope` hook seams designed in this phase; (b) programmatic control surface for local test-bench automated config search ("ILP maybe? Something simpler?") to find configurations producing "interesting sims" — Phase 20 ships the override surface (CLI `-D`, env, `@TestPropertySource`) so the search algorithm can land in a future bench-harness phase without runtime-side rework. Both captured in `<deferred>`.

---

## Operator guidance doc

| Option | Description | Selected |
|--------|-------------|----------|
| 20-RUNTIME.md spec doc, recipes cite committed profile artifacts (recommended) | Sections: WS:entity 1:1 Rationale, Tuning Surface, Per-Scale-Tier Recipes, Profile Findings, Forward Notes. CLAUDE.md cross-ref. Mirrors 17-ADMISSION.md / 18-HARNESS.md. | ✓ |
| 20-RUNTIME.md + harness CLI presets | Doc + LoadHarness `--scale-preset=small\|medium\|large` flag bundling JVM + ramp + queue config. More impl work this phase. | |
| 20-RUNTIME.md spec doc only, no profile artifacts in repo | Smaller repo. Reviewers cannot open raw JFR. | |

**User's choice:** 20-RUNTIME.md spec doc with committed profile artifact citations (recommended).
**Notes:** User asked the clarifying question whether the operator guidance doc was distinct from a config file. Clarified two-artifact pattern: `application.yml` (machine values) + `20-RUNTIME.md` (human wisdom + per-tier recipes + measurement evidence) — same pattern Phase 17 (`17-ADMISSION.md`) and Phase 18 (`18-HARNESS.md`) established.

---

## Claude's Discretion

- Concrete `paralife.runtime.jetty.*` and `paralife.runtime.app.*` field names + defaults — driven by profile evidence in planning
- Profile artifact size bounds (suggested ≤5 MB / file, ≤20 MB total)
- Exact LoadHarness ramp / duration / seed combinations for per-tier profile runs
- Whether `paralife.runtime.app.*` is a single record or split (e.g., `.outbound.*` + `.encode.*`)
- GC choice per tier (ZGC vs G1) — JFR-driven
- Format of profile-finding citations in 20-RUNTIME.md (filename + flamegraph frame name + screenshot)
- Whether Phase 17's existing outbound-queue config retires under new `paralife.runtime.app.*` namespace or cross-references

## Deferred Ideas

- Admin Observer UI live-tune of `paralife.runtime.*` knobs (M5 observer scope; Phase 20 designs `@RefreshScope` hook seams)
- Automated config search over `paralife.runtime.*` for "interesting sims" (ILP / grid / Bayesian — future bench-harness phase; Phase 20 ships override surface enabling it)
- Sub-WS transport multiplex / genuine multi-entity-per-WS (rejected this phase per D-01; revisit only on Phase 21 evidence per D-03)
- JMH micro-benchmarks for codec hot paths (add only if JFR fingers a codec hot path)
- Phase 19.1 parallel read-only sub-steps (separate roadmapped phase; depends on Phase 20)
