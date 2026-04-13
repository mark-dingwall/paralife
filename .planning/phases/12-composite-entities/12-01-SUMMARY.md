---
phase: 12-composite-entities
plan: 01
subsystem: world, engine, websocket
tags: [entity-model, config, registry, messages, foundation]
dependency_graph:
  requires: []
  provides: [CompositeMember, Role, CompositeConfig, CompositeRegistry, CompositePerception, CompositeAction, CompositeJoined]
  affects: [Entity.java, PerceptionBroadcaster.java, SimulationEngine.java, ActionResolver.java, Messages.java, application.yml]
tech_stack:
  added: []
  patterns: [sealed-interface-extension, configurationproperties-record, concurrent-registry]
key_files:
  created:
    - src/main/java/com/paralife/engine/CompositeConfig.java
    - src/main/java/com/paralife/engine/CompositeRegistry.java
    - src/test/java/com/paralife/world/CompositeMemberTest.java
    - src/test/java/com/paralife/engine/CompositeConfigTest.java
    - src/test/java/com/paralife/engine/CompositeRegistryTest.java
  modified:
    - src/main/java/com/paralife/world/Entity.java
    - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/websocket/Messages.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/world/EntityTest.java
decisions:
  - "CompositeState is a mutable class (not record) for tick-pipeline efficiency with CopyOnWriteArrayList + AtomicInteger for safe concurrent reads"
  - "Role enum placed inside Entity interface alongside ParticleType for consistency"
  - "CompositeConfig as separate @ConfigurationProperties record (not extending SimulationConfig) following BondingConfig pattern"
metrics:
  duration_seconds: 557
  completed: "2026-04-13T16:59:02Z"
  tasks_completed: 2
  tasks_total: 2
  tests_added: 30
  files_created: 5
  files_modified: 7
---

# Phase 12 Plan 01: Foundation Types Summary

CompositeMember entity record with Role enum, CompositeConfig for all configurable parameters, CompositeRegistry for shared composite state with position tracking, and three new Message types for composite WebSocket communication.

## One-liner

Sealed Entity hierarchy extended with CompositeMember/Role, CompositeRegistry tracks member positions and shared energy pool, three composite message types added to Messages sealed interface.

## Completed Tasks

| Task | Name | Commit | Key Changes |
|------|------|--------|-------------|
| 1 | CompositeMember entity, Role enum, CompositeConfig | c3cfcd8 | Entity.java + CompositeConfig.java + application.yml + all exhaustive switch fixes |
| 2 | CompositeRegistry and new Message types | 060afce | CompositeRegistry.java + Messages.java (3 new types) |

## Implementation Details

### Task 1: CompositeMember entity record, Role enum, and CompositeConfig

- Added `CompositeMember` record to Entity sealed interface with fields: id, compositeId, type (ParticleType), role (Role), energy, maxEnergy
- Added `Role` enum inside Entity: LOCOMOTOR, FEEDER, ATTACKER, DEFENDER, REPRODUCER, SENSOR
- Created `CompositeConfig` @ConfigurationProperties record with 14 fields: dissolutionChance, criticalEnergyPercent, speedConstant, 10 role drain rates, canFormComposites
- Added `paralife.composite` section to application.yml with all default values
- Updated `PerceptionBroadcaster.cellToView()` exhaustive switch with CompositeMember arm
- Updated both exhaustive switches in `EntityTest.java`
- Added CompositeMember move-blocking case in `ActionResolver`
- Annotated SimulationEngine phases 2/2.5/3 with "CompositeMember handled by CompositeEnergyDistributor" comments
- 12 new tests in CompositeMemberTest, 9 new tests in CompositeConfigTest

### Task 2: CompositeRegistry and new Message types

- Created `CompositeRegistry` @Component with ConcurrentHashMap-based storage
- `CompositeState` mutable class with CopyOnWriteArrayList (members), ConcurrentHashMap (positions), AtomicInteger (energy pool)
- Key APIs: register, getComposite, getCompositeForMember, getPositionForMember, updateMemberPositions, removeMember, dissolve, addEnergy, drainEnergy
- Added three message types to Messages sealed interface: CompositePerception (stitched view), CompositeAction (STV voting), CompositeJoined (formation notification)
- All @JsonSubTypes entries added for Jackson type discrimination
- 18 new tests in CompositeRegistryTest

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Worktree missing prerequisite source files**
- **Found during:** Task 1 setup
- **Issue:** Worktree branch was based on a commit where many source files (BotRegistry, SimulationConfig, Cell, Direction, etc.) were untracked in the main repo and thus absent from the worktree
- **Fix:** Copied all untracked and modified source files from main repo to worktree, committed as prerequisite chore commit
- **Commit:** d662435

## Verification

- Full test suite passes (`./gradlew test` exits 0)
- CompositeMemberTest: 12 tests pass
- CompositeConfigTest: 9 tests pass
- CompositeRegistryTest: 18 tests pass
- EntityTest: all existing tests pass with updated switch expressions
- No regressions from sealed interface change

## Self-Check: PASSED

All 5 created files exist. All 3 commits verified in git log.
