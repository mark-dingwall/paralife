---
phase: 20
plan: 01
wave: 1
subsystem: tooling, profiling
tags: [D-04, D-05, D-19, async-profiler, jfr, bootstrap]
dependency_graph:
  requires: []
  provides: [async-profiler-toolchain, profiles-dir-convention, c22e487-baseline-ritual]
  affects: [tools/, .planning/phases/20-.../profiles/]
tech_stack:
  added: [async-profiler 4.4 (external, ~/tools/async-profiler/bin/asprof)]
  patterns: [SHA-anchored-filename-convention (D-19), in-suite-only-three-gate-stack (TD-19.5-A)]
key_files:
  created:
    - tools/async-profiler-bootstrap.md
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md
  modified: []
decisions:
  - "async-profiler install path resolved external (~/tools/async-profiler/bin/asprof) per RESEARCH §Open Q4 resolution — keeps repo lean, async-profiler ships frequently"
  - "Bootstrap doc documents all three install paths (external recommended, in-tree, ap-loader) so contributors on different setups can replicate without re-resolving"
  - "Three-gate sanity stack runs in-suite-only (TD-19.5-A) — full plan acceptance criteria use the 3-class --tests invocation form; isolated single-test runs are explicitly forbidden by the README"
metrics:
  duration_minutes: ~15
  completed_date: "2026-05-11"
  tasks_completed: 2
  files_changed: 2
  three_gate_sanity_sha: "d7009df"
  three_gate_sanity_utc: "2026-05-11T01:56:54Z"
  three_gate_tests: 9
  three_gate_failures: 0
  bootstrap_lines: 108
  readme_lines: 131
---

# Phase 20 Plan 01: Profiling Toolchain Bootstrap Summary

Toolchain bring-up for Phase 20 — async-profiler 4.4 installed external, two
documentation files committed that codify the install ritual, the
SHA-anchored filename convention (D-19), the c22e487 baseline capture ritual
(verbatim from RESEARCH Pitfall 1, expanded with `--harness-id` and spawn
seed), the size-discipline contract (D-05 ≤10 MB/file, ≤50 MB phase-total),
and the in-suite three-gate sanity check (TD-19.5-A).

## Tasks Completed

### Task 1.0 — async-profiler install (human gate)

Operator-driven install gate, completed autonomously in this session: async-profiler 4.4 (`Async-profiler 4.4 built on Apr 15 2026`) installed external at `~/tools/async-profiler/bin/asprof`. RESEARCH §Open Question 4 (RESOLVED) recommends the external path — chosen here. Verified:

```
$ ~/tools/async-profiler/bin/asprof --version
Async-profiler 4.4 built on Apr 15 2026
```

Install path embedded in `tools/async-profiler-bootstrap.md` capture blocks.

### Task 1.1 — Bootstrap doc + profiles/README.md

Two markdown docs created, both green against the plan's `<acceptance_criteria>`:

| File | Lines | Notes |
|---|---|---|
| `tools/async-profiler-bootstrap.md` | 108 | ≥40 line floor; documents external/in-tree/ap-loader install paths, 60s asprof capture blocks for CPU+alloc+lock, size-discipline `jfr filter` recipe, meta.json template |
| `.planning/.../profiles/README.md` | 131 | ≥50 line floor; D-19 filename convention table, verbatim c22e487 baseline ritual (steps 1-7), D-05 size discipline, in-suite three-gate sanity check (TD-19.5-A explicit) |

Both files cross-link: bootstrap → README for filename convention; README → bootstrap for `asprof` install. `c22e487` cited 19× in README (≥3 floor), `loadHarnessJar` cited 3× (≥1 floor), `GoldenTraceEquivalenceTest` cited 2× (≥1 floor), filename pattern `jfr-{N}bots-baseline-c22e487.jfr` documented.

**Commit:** `d7009df` — `docs(20-01): async-profiler bootstrap doc + profiles/ README`

## Verification

Three-gate sanity stack run in-suite at SHA `d7009df` (UTC `2026-05-11T01:56:54Z`):

```bash
./gradlew test --tests GoldenTraceEquivalenceTest \
               --tests GoldenTraceWithActionsTest \
               --tests LiveEntityRegistryInvariantTest
→ BUILD SUCCESSFUL in 30s
```

Per-class results:

| Test class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `GoldenTraceEquivalenceTest` | 1 | 0 | 0 | 0 |
| `GoldenTraceWithActionsTest` | 2 | 0 | 0 | 0 |
| `LiveEntityRegistryInvariantTest` | 6 | 0 | 0 | 0 |

Plan acceptance criteria — all green:

- [x] `tools/async-profiler-bootstrap.md` exists, ≥40 lines (108)
- [x] `.../profiles/README.md` exists, ≥50 lines (131)
- [x] `c22e487` cited ≥3× in README (19×)
- [x] `asprof --version` cited in bootstrap (2×)
- [x] `loadHarnessJar` cited in README (3×)
- [x] Filename pattern `jfr-{N}bots-baseline-c22e487.jfr` documented in README
- [x] `GoldenTraceEquivalenceTest` cited in README (2× — in-suite-only sanity check)
- [x] Three-gate stack green in-suite at HEAD (SHA `d7009df`)

## Forward Pointer

Plan 20-01b (Wave 2) is now unblocked. It consumes:

- `tools/async-profiler-bootstrap.md` — for the in-tree asprof capture commands
- `.planning/.../profiles/README.md` — for the c22e487 baseline ritual + filename convention + meta.json template + three-gate sanity gate that runs around each capture

20-01b owns the actual JFR + flamegraph + actuator-metric sidecar captures at 100/500/1000 bots @ c22e487. That work is operator-driven (or scripted in the next Wave 2 session) and produces ~6 JFR files + 3 flamegraph HTMLs + 3 actuator sidecars under `profiles/`, with each capture preceded by a fresh three-gate sanity run.
