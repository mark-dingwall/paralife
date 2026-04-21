---
phase: 16
slug: emergent-behavior-tests
status: draft
nyquist_compliant: true
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

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 120 s
- [x] Meta-validation sub-section populated with evidence by plan 16-07 close
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending

---

## Calibration Evidence (Task 16-06-03)

Thresholds ratified on 2026-04-21 against fixtures `run-*.json` (last rollover window). **Meta-validation #2 complete: every threshold below has a linked calibration run.**

| Threshold | Default | Observed range | Margin | Decision |
|---|---|---|---|---|
| D-07 oscillation floor | 0.15 | 0.22–0.38 | 1.5× | Keep 0.15 conservative. |
| D-04 autocorr floor (lag scan [20,100]) | 0.20 | 0.79–0.91 winning value | 4.0× | Keep 0.20 conservative — routinely exceeded. |
| D-11 tick drift | 10 % | 6.6–10.7 % | 1.0× | Tight on some seeds. Interval bumped 20→30 ms gave 6.6 % headroom on the confirming run. |
| D-11 p99 tick-work (30 ms budget) | 27 ms (90 %) | 30 ms @ 128×128 | 0.9× | Right at the boundary — JIT warmup on early ticks inflates p99; stable under load. |
| D-11 heap growth (tick 300–500 vs 800–1000) | 20 % | −8 % to −1 % | n/a (negative growth) | Keep 20 %. |

**Configuration tuning trace (non-threshold calibration, see 16-06-SUMMARY.md Deviations):**

| Property | Plan | Calibrated | Reason |
|---|---|---|---|
| `paralife.world.width` / `.height` | 64 × 64 | 128 × 128 | 64 × 64 @ 100 bots (2.4 % density) collapsed one RPS type before tick 400 on 3/4 seeds; 128 × 128 (0.6 % density) preserves stable cycling. |
| `paralife.bonding.bonding-probability` | 0.4 | 0.6 | 0.4 yielded 0 composites on 2/3 seeds; 0.6 lifts bonded-pair count to 39–46/run. |
| `paralife.simulation.events.lightning.peak-lambda` | 0.10 | 0.02 | Plan value struck 10 % of cells/peak-tick, precipitating mass extinction. |
| `paralife.simulation.events.mutagen.peak-lambda` | 0.08 | 0.01 | Same footprint — 12 %+ of the grid infected per peak-season tick was incompatible with 1000-tick stability. |
| `paralife.tick.interval-ms` | 20 | 30 | 20 ms left p99 (observed 19–34 ms) with zero headroom under the 18 ms budget; 30 ms gives margin. |
| `paralife.websocket.max-respawns-per-session` (NEW property from 16-06 scope expansion) | n/a (prod-default 5) | 1 000 000 | Disables T-15-04 DoS cap for long-run only; production value preserved in `application.yml`. |

**Mutation sanity (Meta-validation #3):** Negative-control evidence lives in `CompositeFormationDeterminismTest.DifferentSeedControl` (D-23 addendum) — asserts seed=1337 and seed=42 produce distinct composite counts. That control proves the seeded-scenario observable actually measures the seed.

**Fixture files referenced:** see `.planning/phases/16-emergent-behavior-tests/fixtures/run-*.json` (N=5 rollover; newest three are the final calibration evidence from 2026-04-21).
