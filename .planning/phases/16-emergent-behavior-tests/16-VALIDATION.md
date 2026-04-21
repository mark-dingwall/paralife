---
phase: 16
slug: emergent-behavior-tests
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-21
---

# Phase 16 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot 3.4.4 (existing) |
| **Config file** | `build.gradle.kts` (existing) |
| **Quick run command** | `./gradlew test --tests '*CompositeFormationDeterminismTest*'` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90 s (full suite); long-run test ~60–180 s alone |

---

## Sampling Rate

- **After every task commit:** Run the narrowest `--tests` pattern that covers the modified code.
- **After every plan wave:** Run the full phase test group (`./gradlew test --tests '*16*' --tests '*Emergence*' --tests '*CompositeFormationDeterminism*'`).
- **Before `/gsd-verify-work`:** Full `./gradlew test` must be green (covers R19 regression guarantee).
- **Max feedback latency:** 180 s per test; full suite under 5 min.

---

## Per-Task Verification Map

Populated by gsd-planner. Each task lands here with its `<automated>` command. Tasks without automated verify must declare Wave 0 dependencies or live in `## Manual-Only Verifications`.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | R15/R16/R17/R18/R19 | — | N/A (test-only phase) | unit / integration | TBD | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/main/java/com/paralife/metrics/EmergenceMetrics.java` — stub bean so counter-increment-site tasks have a target (Wave 0 bean scaffold, methods throw until wired).
- [ ] `src/main/java/com/paralife/engine/RandomSource.java` (or equivalent) — central seedable-RNG abstraction per 16-RESEARCH.md §Server-Side RNG Audit.
- [ ] `src/test/java/com/paralife/engine/EmergenceTestHelpers.java` — shared harness for master-seed derivation, per-tick sample accumulator, trigger-watcher registration, fixture JSON dump.
- [ ] `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` — skeleton with `@SpringBootTest(RANDOM_PORT)` + placeholder `@Disabled` test so Wave 1 can wire asserts progressively.
- [ ] `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` — skeleton with `@SpringBootTest` (no web env) + placeholder.
- [ ] `.gitignore` addition — `.planning/phases/16-emergent-behavior-tests/fixtures/` (D-06b).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `16-EMERGENCE.md` narrative writeup quality | R17 | Writeup is prose documenting observed values per signal; correctness is reviewable, not unit-testable | Reviewer reads `16-EMERGENCE.md`, confirms all 5 signals (D-04) have an observed-values section, master seed is logged, and at least one signal has a qualitative interpretation paragraph |

---

## Per-Requirement Validation Checks

Derived from 16-RESEARCH.md §Validation Architecture; planner must turn each into concrete tasks:

- **R15 determinism:** Re-run `CompositeFormationDeterminismTest` with same master seed N=3 times; assert bond/composite counter values match across runs (statistical equality of counts, not byte equality of state).
- **R16 stability:** Three `EmergenceStabilityLoadTest` assertions from D-07 (no extinction, per-type ≥5% floor for ≥80% of ticks, oscillation amplitude ≥ configured floor).
- **R17 emergence:** Per-signal assertions from D-04 (#1–#5). Soft-observed signals still emit a `EMERGENCE` log marker the test captures via `ListAppender<ILoggingEvent>`.
- **R18 load-stability:** All 7 rows of D-11 table assert during the same 1000-tick run (tick drift <10%, mean tick-work ≤50% budget, p99 ≤90% budget, 0 session dropouts, heap delta <20%, 0 ERROR log entries, active-session gauge == configured count).
- **R19 no regression:** `./gradlew test` green — verifier agent gates phase closeout.

Cross-validation: deliberate `Thread.sleep(5ms)` injection in a `@Disabled` smoke variant proves the tick-work timer fires (guards against false-green if the bookend listener silently mis-registers).

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
