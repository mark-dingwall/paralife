---
phase: 12-composite-entities
plan: 04
subsystem: engine
tags: [composite-combat, dissolution, degradation, panic-zone, death-handling]
dependency_graph:
  requires: [CompositeMember, Role, CompositeConfig, CompositeRegistry, BotRegistry]
  provides: [compositeCombat, handleMemberDeath, dissolveToParticles, revertToBondedPair, checkPanicZone]
  affects: [SimulationEngine.java]
tech_stack:
  added: []
  patterns: [snapshot-deferred-write, dual-energy-model, progressive-shatter]
key_files:
  created:
    - src/test/java/com/paralife/engine/CompositeCombatTest.java
    - src/test/java/com/paralife/engine/CompositeDissolutionTest.java
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
decisions:
  - "DEFENDER role reuses bondDefenseChance from BondingConfig for absorption probability"
  - "CompositeMember attacker scan runs separately after Particle scan, each member attacks at most one neighbor per tick"
  - "previousPoolEnergy ConcurrentHashMap tracks pool energy across ticks for panic zone decrease detection"
  - "dissolveToParticles creates Particles with derived IDs (cm.id() + '-p') and remaps bot sessions"
metrics:
  duration_seconds: 590
  completed: "2026-04-13T17:32:57Z"
  tasks_completed: 2
  tasks_total: 2
  tests_added: 22
  files_created: 2
  files_modified: 1
---

# Phase 12 Plan 04: Composite Combat & Dissolution Summary

Composite combat mechanics in processInteractions and composite dissolution/degradation/panic-zone in processDeaths, completing the mortality system for composite organisms.

## One-liner

Particle-vs-CompositeMember RPS combat hits individual energy, ATTACKER deals true damage, member death triggers 97%/3% graceful/dissolution with BondedPair reversion and progressive panic-zone shatter below 12% pool.

## Completed Tasks

| Task | Name | Commit | Key Changes |
|------|------|--------|-------------|
| 1 | Composite combat mechanics in processInteractions | 1a5d873 | Particle-vs-CM, CM attacker scan, DEFENDER absorption, CombatDelta CM handling |
| 2 | Composite dissolution, degradation, and panic zone in processDeaths | 794e005 | handleMemberDeath, dissolveToParticles, revertToBondedPair, checkPanicZone |

## Implementation Details

### Task 1: Composite combat mechanics in processInteractions

- Added Case 3 to Particle attack loop: Particle-vs-CompositeMember with RPS check (attacker must be predator of CM's type)
- DEFENDER role absorption check reuses `bondDefenseChance` probability from BondingConfig
- New CompositeMember attacker scan: iterates all CM positions (shuffled), checks adjacent cells for enemies
- ATTACKER role: true damage to any adjacent non-same-composite entity (D-10, type-agnostic)
- Non-ATTACKER roles: RPS-based combat against Particles, BondedPairs, and CMs of different composites (D-11)
- Same-composite members skip combat via compositeId equality check (D-13)
- CombatDelta apply section extended to handle `Entity.CompositeMember` via `withEnergy()`
- Each CompositeMember attacks at most one neighbor per tick (break after first valid target)
- 9 tests in CompositeCombatTest

### Task 2: Composite dissolution, degradation, and panic zone in processDeaths

- Phase 3b: CompositeMember death scan — dead members (energy=0) trigger `handleMemberDeath`
- `handleMemberDeath`: removes dead member from grid/BotRegistry/CompositeRegistry, then:
  - 0 remaining: dissolve composite
  - 1 remaining: revert to BondedPair (D-30), remap bot session
  - 2+ remaining: roll dissolution chance (D-29) — graceful degradation or full shatter
- `revertToBondedPair`: creates self-bonded BondedPair with surviving member's type and energy
- `dissolveToParticles`: converts all surviving members to solo Particles, preserving type and energy, remapping bot sessions
- Phase 3c: Panic zone check for all non-processed composites
  - Pool=0: total death — all members removed, composite dissolved (D-31)
  - Pool < criticalEnergyPercent (12%) AND pool decreased since last tick: progressive shatter roll
  - Shatter probability: `(1 - poolPercent/criticalPercent) * 0.5` — max 50% at pool=0
  - Pool stable or increasing: no shatter roll
- `previousPoolEnergy` ConcurrentHashMap tracks pool energy across ticks for decrease detection
- 13 tests in CompositeDissolutionTest

## Deviations from Plan

None - plan executed exactly as written.

## Verification

- `./gradlew test --tests "com.paralife.engine.CompositeCombatTest"` -- 9 tests pass
- `./gradlew test --tests "com.paralife.engine.CompositeDissolutionTest"` -- 13 tests pass
- `./gradlew test` -- full suite green (BUILD SUCCESSFUL)
- Particle attack on CompositeMember reduces individual energy, shared pool unchanged
- 2-member composite loss reverts to BondedPair with correct energy
- Pool depletion kills all members and dissolves composite

## Self-Check: PASSED
