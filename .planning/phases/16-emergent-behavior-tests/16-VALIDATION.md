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
| **Framework** | JUnit 5 (existing) |
| **Config file** | `build.gradle.kts` (existing), no new test config required |
| **Quick run command** | `./gradlew test --tests "Composite*" --tests "EmergenceStabilityLoadTest" -PincludeLong=true -q` |
| **Full suite command** | `./gradlew test -PincludeLong=true` |
| **Estimated runtime** | ~90 s local (long-run test dominates); full suite ~3–5 min |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests <ClassAffected>` (narrow target)
- **After every plan wave:** Run `./gradlew test` (exclude long-run unless wave installs it)
- **Before `/gsd-verify-work`:** Full suite including long-run must be green
- **Max feedback latency:** ~120 s (long-run test, per D-02 wall-clock target)

---

## Per-Task Verification Map

*Populated by planner — one row per task across all plans. See each `16-NN-PLAN.md` for `<automated>` / `<manual>` hooks.*

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| *TBD* | | | | | | | | | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Populated by planner from RESEARCH.md Wave-0 file list. Placeholder pending plan output.*

- [ ] `EmergenceMetrics` bean (new) — counter harness feeding R17 assertions
- [ ] `TickWorkTimer` bookend listeners (`@Order 0` / `@Order 101`) — D-11 tick-work metric
- [ ] `fixtures/` dir + `.gitignore` entry — D-06b rollover target
- [ ] Test helpers: `TriggerWatcher`, `PopulationHistory`, `RunFixtureWriter`, `TestLogCapture`, `SeededBotLauncher` — shared across two new integration tests
- [ ] RNG seeding surface — `@TestPropertySource` keys per component (D-09b)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| R17 narrative writeup quality | R17 | Narrative judgement — "is this a documented emergent pattern?" cannot be grep-asserted | Reviewer reads `16-EMERGENCE.md` after long-run completes; confirms ≥1 signal has observation + seed + interpretation text |

---

## Meta-Validation (Tests-Validating-Tests)

Because Phase 16 outputs are themselves tests, Dimension 8 needs explicit meta-validation:

1. **Seed reproducibility** — R15 test run twice with the same master seed must produce identical assertion outcomes (composite count, bond formation tick). Non-identity implies hidden RNG leak; planner owns re-running RNG audit.
2. **Threshold calibration log** — each tunable threshold (oscillation 0.15, per-type floor 5 %, heap drift 20 %, trigger window W, trigger radius R) MUST be committed with an evidence line citing the calibration-run fixture JSON that justified the value. No magic numbers without a linked `fixtures/run-*.json`.
3. **Mutation sanity** — at least one negative-control run: disable one emergence mechanism (e.g., set bond-probability to 0) and confirm the relevant assertion FAILS. Proves the test actually measures what it claims.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120 s
- [ ] Meta-validation sub-section populated with evidence by plan 16-07 close
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
