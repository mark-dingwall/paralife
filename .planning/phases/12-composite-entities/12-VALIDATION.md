---
phase: 12
slug: composite-entities
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-14
---

# Phase 12 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "com.paralife.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "com.paralife.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 12-01-01 | 01 | 1 | Composite entity representation | -- | N/A | unit | `./gradlew test --tests "com.paralife.world.CompositeMemberTest"` | No W0 | pending |
| 12-01-02 | 01 | 1 | Registry + position tracking | -- | N/A | unit | `./gradlew test --tests "com.paralife.engine.CompositeRegistryTest"` | No W0 | pending |
| 12-02-01 | 02 | 2 | Composite formation | -- | N/A | unit | `./gradlew test --tests "com.paralife.engine.CompositeFormationTest"` | No W0 | pending |
| 12-03-01 | 03 | 2 | Coordinated movement | -- | N/A | unit | `./gradlew test --tests "com.paralife.engine.CompositeMovementTest"` | No W0 | pending |
| 12-03-02 | 03 | 2 | STV voting | -- | N/A | unit | `./gradlew test --tests "com.paralife.engine.CompositeMovementTest"` | No W0 | pending |
| 12-04-01 | 04 | 3 | Dissolution + death | -- | N/A | unit | `./gradlew test --tests "com.paralife.engine.CompositeDissolutionTest"` | No W0 | pending |
| 12-05-01 | 05 | 2 | Perception stitching | -- | N/A | unit | `./gradlew test --tests "com.paralife.engine.CompositePerceptionTest"` | No W0 | pending |
| 12-06-01 | 06 | 4 | Integration test | -- | N/A | integration | `./gradlew test --tests "com.paralife.engine.CompositeIntegrationTest"` | No W0 | pending |

*Status: pending / green / red / flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/world/CompositeMemberTest.java` -- stubs for CompositeMember entity
- [ ] `src/test/java/com/paralife/engine/CompositeRegistryTest.java` -- stubs for CompositeRegistry CRUD + position tracking
- [ ] `src/test/java/com/paralife/engine/CompositeFormationTest.java` -- stubs for BondedPair->Composite formation
- [ ] `src/test/java/com/paralife/engine/CompositeMovementTest.java` -- stubs for coordinated movement + STV
- [ ] `src/test/java/com/paralife/engine/CompositeDissolutionTest.java` -- stubs for dissolution/death

*Existing JUnit 5 infrastructure covers all framework needs.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Emergent archetype formation | Energy economics | Requires observation over many ticks | Run 500+ tick sim, inspect composite compositions via WebSocket |
| Visual composite movement | Coordinated movement | Rendering verification | Connect browser client, observe composite translation on grid |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 30s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
