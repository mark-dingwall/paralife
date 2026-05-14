---
phase: 20-connection-multiplexing-runtime-tuning
plan: 01b
subsystem: profiling-baseline
tags: [jfr, async-profiler, flamegraph, actuator-metrics, c22e487-baseline, performance]
requires:
  - phase: 20-01
    provides: async-profiler toolchain bootstrap + profiles/ filename convention
provides:
  - 3× baseline JFR (100/500/1000 bots @ c22e487)
  - 3× baseline flamegraph HTML (cpu/alloc/lock at 1000 bots)
  - 3× meta.json sidecars (A1/A2/A6/A7/A8/A9 verification outcomes)
  - 3× actuator-metric JSON sidecars (Pass-2 Concern #10; D-13/D-18 baseline values)
  - Per-tier baseline tick work-time means + detach-timeout counts (Plan 6 §4.2 inputs)
affects: [20-02-jetty-runtime-config, 20-04-runtime-md-skeleton, 20-05-codec-opts, 20-06-finalise]
tech-stack:
  added: []
  patterns: [JFR continuous recording + ZGC-or-G1 + tuned VT carrier count; async-profiler sequential attach per event type; actuator-metric polling sidecars; SHA-anchored filename convention (D-19)]
key-files:
  created:
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json
  modified: []
key-decisions:
  - "A1 VERIFIED: all 8 Jetty 12.0.18 Configurable setters present — Plan 2 may include the full set; no fields need dropping. javap evidence captured."
  - "A8 FALSIFIED: Temurin 21.0.6 default GC is G1, NOT generational ZGC. Plan 4 GC analysis must reflect that ZGC is opt-in only (-XX:+UseZGC -XX:+ZGenerational)."
  - "A9 three-gate at baseline is PARTIAL (8/9 green) — GoldenTraceEquivalenceTest fails deterministically on sess-9 + sess-21 due to STALE GOLDEN file at c22e487; corrected later in f6da129 'fix(19.1): pass-1 multi-review follow-up sweep'. Test PASSES at HEAD with corrected golden. Simulation determinism CONFIRMED (identical digests across two consecutive runs at c22e487). Baseline measurements unaffected — this is a test infrastructure issue, not a baseline integrity issue."
  - "async-profiler 4.x DOES NOT support concurrent attach to the same JVM with different event types (asprof returns 'Profiler already started'). Plan 1b execution must run cpu/alloc/lock SEQUENTIALLY, not concurrently. RESEARCH §Capture's claim of concurrency is incorrect for asprof 4.4."
  - "Lock contention is minimal at 1000-bot load — 6 frame nodes in lock flamegraph vs 209 in cpu. Confirms Phase 17/19.1 D-10 architectural goal (VT-per-session + bounded queue isolates contention). Plan 5 Task 5.0 triage should not target lock-contention as a hot path."
patterns-established:
  - "Sequential async-profiler captures during steady-state portion of LoadHarness window; each event type 30-60s; full 1000-bot capture sequence (cpu → alloc → lock) requires ≥3 min of sustained load. Schedule accordingly."
  - "Per-tier actuator-metric sidecar polling 6×5s during steady-state (Pass-2 Concern #10). Sample shape: `{captured_at_sha, scenario, samples: [{sample_utc, work_time_ms: <actuator-response>, detach_timeout: <actuator-response>}]}`."
  - "Worktree-pinned baseline capture: `git worktree add /tmp/p20-baseline-c22e487 c22e487` keeps HEAD clean for parallel work; `.planning/.../profiles/` mkdir -p required (didn't exist at c22e487 — only at HEAD via Plan 20-01)."
requirements-completed: [SCALE-09]
duration: 60min
completed: 2026-05-15
---

# Plan 20-01b: Baseline JFR + Flamegraph + Actuator-Metric Capture Summary

**Baseline measurements committed at SHA `c22e487`. All 12 artifacts in tree, under 10 MB per file and 50 MB total. RESEARCH assumptions A1/A2/A6/A7 verified; A8 falsified; A9 partial (8/9 gates, root cause known).**

## Artifact Inventory

| File | Size | SHA segment |
|---|---|---|
| `jfr-100bots-baseline-c22e487.jfr` | 2.0 MB | c22e487 |
| `jfr-500bots-baseline-c22e487.jfr` | 2.4 MB | c22e487 |
| `jfr-1000bots-baseline-c22e487.jfr` | 3.0 MB | c22e487 |
| `cpu-1000bots-baseline-c22e487.html` | 48 KB | c22e487 |
| `alloc-1000bots-baseline-c22e487.html` | 27 KB | c22e487 |
| `lock-1000bots-baseline-c22e487.html` | 18 KB | c22e487 |
| `metrics-100bots-baseline-c22e487.json` | 3.0 KB | c22e487 |
| `metrics-500bots-baseline-c22e487.json` | 3.0 KB | c22e487 |
| `metrics-1000bots-baseline-c22e487.json` | 3.0 KB | c22e487 |
| `jfr-100bots-baseline-c22e487.meta.json` | 2.3 KB | c22e487 |
| `jfr-500bots-baseline-c22e487.meta.json` | 1.1 KB | c22e487 |
| `jfr-1000bots-baseline-c22e487.meta.json` | 2.7 KB | c22e487 |
| **TOTAL** | **7.4 MB** | (cap: 50 MB) |

All filenames contain `c22e487` per D-19. No file exceeds the 10 MB per-file cap (D-05). Total 7.4 MB is 15% of the 50 MB phase-total budget.

## Per-Tier Baseline Metric Snapshot (Pass-2 Concern #10)

`paralife.tick.health.work-time-ms` (VALUE, ms) — sampled 6× at 5s intervals during steady-state:

| Tier | Samples | Mean | Notes |
|---|---|---|---|
| 100 bots | [19, 16, 16, 16, 19, 20] | **17.7 ms** | ~3.5% of 500 ms tick budget |
| 500 bots | [17, 16, 14, 17, 20, 15] | **16.5 ms** | ~3.3% of 500 ms tick budget |
| 1000 bots | [14, 18, 14, 14, 15, 16] | **15.2 ms** | ~3.0% of 500 ms tick budget |

`paralife.outbound.detach.timeout` (COUNT) end-of-window value:

| Tier | End count |
|---|---|
| 100 bots | 0 |
| 500 bots | 0 |
| 1000 bots | 0 |

**Headline finding:** Tick work-time is essentially flat across the 10× connection-count scale span — server CPU is far from the bottleneck at 1000 bots. Zero `detach.timeout` events at any tier confirms the synchronized-session-monitor + drain-VT model holds under sustained load. Plan 6 §4.2 reads these values into the 1000-bot baseline column.

## Assumptions A1–A8 Verification (RESEARCH §Assumptions Log)

| ID | Status | Evidence |
|---|---|---|
| **A1** Jetty 12.0.18 `Configurable` setters | **VERIFIED** | `javap -public` on `jetty-websocket-jetty-api-12.0.18.jar` confirms all 8 setters present: `setIdleTimeout`, `setInputBufferSize`, `setOutputBufferSize`, `setMaxBinaryMessageSize`, `setMaxTextMessageSize`, `setMaxFrameSize`, `setAutoFragment`, `setMaxOutgoingFrames`. **Plan 2 may include all 8 in JettyRuntimeConfig — no fields need to be dropped.** |
| **A2** async-profiler 4.x for Java 21 | **VERIFIED** | `asprof --version` → `Async-profiler 4.4 built on Apr 15 2026`. Installed at `~/tools/async-profiler/bin/asprof` per recommended external path (Plan 20-01 bootstrap doc). |
| **A6** LoadHarness sustains 1000 bots from single JVM | **VERIFIED** | Harness log records `BotFleet: 1000 bots disconnected` on clean exit after 200 s steady load. D-02 5000/JVM design ceiling thus has ≥20% headroom-proved. |
| **A7** JFR ≤10 MB at 60s × 1000 bots | **VERIFIED** | Tier-1000 JFR 3.0 MB at 220 s; tier-500 2.4 MB; tier-100 2.0 MB. All under cap. |
| **A8** Generational ZGC default-on in Temurin 21.0.6 | **FALSIFIED** | `java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` returns `UseZGC = false` and `ZGenerational = false`. Default GC is G1. **Plan 4 GC choice analysis must reflect that ZGC is opt-in only** (`-XX:+UseZGC -XX:+ZGenerational`). |

## A9 — Three-Gate Stack at Baseline SHA

Ran `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` **while still in the `c22e487` worktree**:

| Test class | Tests | Failures |
|---|---|---|
| GoldenTraceEquivalenceTest | 1 | **1** |
| GoldenTraceWithActionsTest | 2 | 0 |
| LiveEntityRegistryInvariantTest | 6 | 0 |
| **Total** | **9** | **1** |

Failing test: `GoldenTraceEquivalenceTest.byteIdenticalOutputAcrossTwoRuns` — actual digests for `trace-sess-9` and `trace-sess-21` (2 of 26 sessions) deterministically differ from the pinned values in `c22e487:src/test/resources/golden-trace-phase19.json`.

**Root cause** (investigated): the golden file at c22e487 has STALE digests for sess-9 and sess-21. Commit `f6da129 fix(19.1): pass-1 multi-review follow-up sweep` updated the golden to match actual simulation output — but f6da129 landed BETWEEN c22e487 and HEAD. At HEAD, the same test PASSES with the corrected golden. At c22e487, the simulation produces the *correct* digests (verified deterministic across two consecutive runs at c22e487) — but the pinned golden in the c22e487 tree has not yet caught up.

**Implication for Phase 20:** the simulation behavior at c22e487 is fully deterministic — this is a test-fixture issue, not a determinism issue, and **the baseline JFR/flamegraph/metric measurements are unaffected**. Downstream plans (20-04, 20-05, 20-06) may treat the baseline as authoritative for tick health / GC / lock-contention analysis. If a future plan needs to re-run the three-gate at a fresh baseline SHA, the operator should pick a commit at or after f6da129.

## RESEARCH Open Questions / Plan-2-Forward Impacts

- **Plan 2 (JettyRuntimeConfig):** A1 verified — include all 8 Configurable setters. No fields drop.
- **Plan 4 (runtime tuning recipes):** A8 falsified — recipe text must explain that Temurin 21.0.6 default is G1; ZGC sections are opt-in flags only.
- **Plan 5 (codec opts):** the cpu flamegraph at 1000 bots (`cpu-1000bots-baseline-c22e487.html`) is the Task 5.0 triage input. Lock flamegraph shows ~6 frames vs 209 in cpu → lock contention is not a hot path; codec opts should target CPU/alloc, not synchronization.
- **Plan 6 (finalise):** baseline-column values for the §4.2 headline-numbers table come from the metric sidecars (mean tick-work-time 17.7 / 16.5 / 15.2 ms across 100/500/1000; zero detach timeouts at any tier).

## Capture-Process Deviations from Plan 20-01b §how-to-verify

These deviations were forced by environment / discovery during execution; downstream plans must read this section before assuming the §how-to-verify ritual was followed verbatim.

1. **`asprof` concurrent attach is NOT supported.** RESEARCH §Capture and Plan 20-01b script run `cpu/alloc/lock` in parallel with `& wait`. Async-profiler 4.4 rejects the second attach with `[ERROR] Profiler already started`. Execution ran captures **sequentially** instead — `cpu` (60 s) during the main 1000-bot run, `alloc` (60 s) immediately after, then a **second 1000-bot LoadHarness run** to capture `lock` (60 s) with active load. Total wall time ~4 min for the 1000-bot tier vs the plan's nominal 200 s window.
2. **`mkdir -p .planning/.../profiles/` was required at c22e487** (Pass-2 Concern #15 hazard confirmed live) — the directory was added by Plan 20-01 against HEAD and does not exist in the c22e487 tree.
3. **WSL2 `kernel.perf_event_paranoid = 2`** — left at OS default. async-profiler's `lock` event uses JVMTI MonitorContendedEnter (not perf-events), so no kernel privilege escalation was needed. Documented in each meta.json under `perf_event_paranoid_observed`.
4. **A9 three-gate runs as a single `./gradlew test` invocation** that internally still respects `forkEvery=1` (build.gradle.kts:75 at c22e487) — three-gate at baseline is 8/9, root-caused as above.

## Forward-Compat Notes

- **Plan 2 (JettyRuntimeConfig):** safe to land the full 8-field record. A1 evidence above.
- **Plans 4/5/6:** all baseline artifacts present and SHA-anchored. Cite filenames verbatim per RESEARCH §Citation pattern.
- **Re-capture trigger:** any production change that materially affects tick path, OutboundSender, or codec hot paths invalidates these baselines. Re-running this plan against a new SHA produces a fresh `tuned-{HEAD_SHA}` set per `profiles/README.md` filename convention.

## Pass-2 / Pass-3 Concern Confirmations

- **Pass-2 Concern #10:** Per-tier actuator-metric JSON sidecars present (3/3), each with ≥3 samples (6 actual). Plan 6 §4.2 baseline column source unblocked.
- **Pass-2 Concern #15:** `mkdir -p` performed at c22e487 (dir did not exist); `SERVER_JAR` / `HARNESS_JAR` disambiguated via `ls | grep -v load-harness | head -1` and `ls *-load-harness.jar | head -1`; harness `--duration 200` for tier-1000 with sequential capture honoured the steady-state requirement.
- **Pass-3 Concern #21:** three-gate ran while still on c22e487 worktree (NOT after `git checkout -`), per the resume-signal contract — outcome is A9 above (8/9, known stale-golden root cause).

## Status

Plan complete with caveats explicitly documented. **Three-gate is 8/9 at baseline due to known stale-golden in c22e487 — non-blocker.** Baseline measurements valid and load-bearing for Plans 2 / 4 / 5 / 6. Resume signal: `baseline-captured-with-A9-caveat` (operator-discretion override of the verbatim `baseline-captured` per Plan 20-01b resume-signal contract; rationale documented in this SUMMARY).
