---
phase: 14
slug: environmental-rules
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 14 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (existing) |
| **Config file** | `build.gradle.kts` (test task) |
| **Quick run command** | `./gradlew test --tests "com.paralife.engine.environment.*" --tests "com.paralife.engine.BuffRegistryTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds (quick), ~90 seconds (full) |

---

## Sampling Rate

- **After every task commit:** Run quick run command
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

*Populated during planning. Each task gets one row.*

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 14-01-XX | 01 | 1 | SC-1 (≥2 env effects) | — | N/A | unit | `./gradlew test --tests "*EnvironmentEngineTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Pre-execution test scaffolding needed before feature tasks can verify:

- [ ] `src/test/java/com/paralife/engine/environment/EnvironmentEngineTest.java` — skeleton for EnvironmentEngine lifecycle tests (deterministic seed via injected Random)
- [ ] `src/test/java/com/paralife/engine/environment/ToxinTest.java` — skeleton for toxin path generation, CA diffusion, damage application
- [ ] `src/test/java/com/paralife/engine/environment/MutagenTest.java` — skeleton for strain gossip, infection, survivor buff grant, attack-accelerates-cure
- [ ] `src/test/java/com/paralife/engine/environment/LightningTest.java` — skeleton for dual-radius damage + fertility boost
- [ ] `src/test/java/com/paralife/engine/environment/CompostTest.java` — skeleton for death hook nutrient bump + neighbor falloff
- [ ] `src/test/java/com/paralife/engine/BuffRegistryTest.java` — skeleton for buff add/expiry/remove lifecycle
- [ ] `src/test/java/com/paralife/engine/SeasonalPoissonTest.java` — skeleton for sine-scaled λ formula + rate within tolerance over N ticks
- [ ] `src/test/java/com/paralife/engine/VisionScopedOvercrowdingTest.java` — skeleton for per-bot vision-scoped overcrowding computation
- [ ] `src/test/resources/application-test.yml` — test profile with deterministic seed and short year-length for fast tests (if not already present)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Visual plausibility of toxin spline path | D-05 | Catmull-Rom smoothness is geometric/aesthetic — unit tests prove points lie on curve, but visual inspection confirms path looks like natural weather pattern | Run `./gradlew bootRun`, observe tick log for a spawned toxin event with dumped control points; plot in external tool or sketch to confirm shape matches expectation |
| Gameplay balance of event frequencies | D-30 | λ rate balance is emergent — automated test only confirms rate within tolerance, not whether "feels right" | Run `./gradlew bootRun` for 10+ minutes, observe event log, assess subjective frequency |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
