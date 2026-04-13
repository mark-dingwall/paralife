---
phase: 12-composite-entities
plan: 03
subsystem: engine, websocket
tags: [composite-actions, stv-voting, rigid-body-movement, role-actions]
dependency_graph:
  requires: [CompositeMember, CompositeRegistry, CompositeConfig, CompositeAction, Direction]
  provides: [resolveFeederConsume, resolveAttackerAttack, resolveReproducerBud, resolveLocomotorVote, executeCompositeMovement, queueCompositeAction]
  affects: [ActionResolver.java, WorldWebSocketHandler.java]
tech_stack:
  added: []
  patterns: [stv-voting, rigid-body-translation, speed-gating, dual-action-queue]
key_files:
  created:
    - src/test/java/com/paralife/engine/CompositeActionTest.java
    - src/test/java/com/paralife/engine/CompositeMovementTest.java
  modified:
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/test/java/com/paralife/engine/ActionResolverTest.java
decisions:
  - "Ranked preferences stored in separate pending map alongside Action queue, merged at resolution time"
  - "CompositeAction converted to regular Action for unified queuing, with ranked preferences extracted separately"
  - "Speed gate uses compositeTicksSinceMove ConcurrentHashMap, first move always allowed"
metrics:
  duration_seconds: 793
  completed: "2026-04-13T17:17:07Z"
  tasks_completed: 2
  tasks_total: 2
  tests_added: 22
  files_created: 2
  files_modified: 3
---

# Phase 12 Plan 03: Composite Member Actions Summary

ActionResolver extended with composite member reactive role actions (FEEDER/ATTACKER/REPRODUCER auto-act, DEFENDER/SENSOR passive) and LOCOMOTOR STV voting for coordinated rigid body movement with speed gating.

## One-liner

FEEDER consumes to shared pool, ATTACKER deals true damage, REPRODUCER buds from pool, LOCOMOTOR first-preference plurality voting drives rigid body translation with speed gating by composite size.

## Completed Tasks

| Task | Name | Commit | Key Changes |
|------|------|--------|-------------|
| 1 | Composite reactive role actions | efc8e6a | ActionResolver + CompositeActionTest (9 tests) |
| 2 | LOCOMOTOR STV voting and rigid body movement | 9e00e72 | CompositeMovementTest (13 tests) |

## Implementation Details

### Task 1: Composite reactive role actions

- Added `CompositeRegistry` and `CompositeConfig` as ActionResolver constructor dependencies
- Phase 1 of `resolveActions` now routes CompositeMember entities to `resolvedCompositeList` alongside Particle `resolvedList`
- `ResolvedCompositeAction` record tracks member, bot state, and action
- `resolveFeederConsume`: finds adjacent nutrient, adds energy to composite shared pool (not individual member energy per D-15), charges feederActiveDrain from pool
- `resolveAttackerAttack`: applies type-agnostic true damage (D-10) to adjacent Particle/BondedPair/CompositeMember of different composite, charges attackerActiveDrain from pool
- `resolveReproducerBud`: checks shared pool >= REPRODUCE_ENERGY_COST, spawns Particle with member's type (D-32), deducts from pool plus reproducerActiveDrain
- DEFENDER and SENSOR roles treated as passive — action results in rest
- WorldWebSocketHandler now routes `CompositeAction` messages to `queueCompositeAction`
- Separate `pendingRankedPreferences` map stores LOCOMOTOR STV preferences from CompositeAction messages

### Task 2: LOCOMOTOR STV voting and coordinated rigid body movement

- `resolveCompositeMovements`: groups LOCOMOTOR votes by compositeId, resolves direction, checks speed gate, executes rigid body move
- `resolveLocomotorVote`: first-preference plurality with random tie-break (simplified STV per research — full Droop quota unnecessary for 8 directions)
- Speed gate: `speed = locomotor_count / colony_size * speedConstant`, `moveInterval = ceil(1/speed)`, tracked via `compositeTicksSinceMove` map
- `executeCompositeMovement`: calculates all target positions, checks none occupied or claimed, atomically claims all targets, clears source cells, places members at targets
- Correctly handles composite members already occupying each other's target positions (intra-composite overlap allowed)
- Updates both BotRegistry positions and CompositeRegistry positions after movement
- Charges `locomotorCount * locomotorActiveDrain` from shared pool on successful move
- Ranked preferences from CompositeAction capped at 3 entries, invalid directions filtered (T-12-06, T-12-08)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical functionality] WebSocket CompositeAction routing**
- **Found during:** Task 1
- **Issue:** Plan mentioned updating WorldWebSocketHandler "if needed" for CompositeAction deserialization. The handler's switch statement would route CompositeAction to the `default` (error) branch without an explicit case.
- **Fix:** Added `case Messages.CompositeAction` to the switch and `handleCompositeAction` method that calls `queueCompositeAction`
- **Files modified:** WorldWebSocketHandler.java
- **Commit:** efc8e6a

**2. [Rule 2 - Missing critical functionality] Dual pending queue for ranked preferences**
- **Found during:** Task 1
- **Issue:** CompositeAction contains `rankedPreferences` field not present in regular Action. Storing only as Action would lose STV voting data.
- **Fix:** Added separate `pendingRankedPreferences` AtomicReference map alongside `pendingActions`, drained together on tick
- **Files modified:** ActionResolver.java
- **Commit:** efc8e6a

## Verification

- `./gradlew test --tests "com.paralife.engine.CompositeActionTest"` -- 9 tests, 0 failures
- `./gradlew test --tests "com.paralife.engine.CompositeMovementTest"` -- 13 tests, 0 failures
- `./gradlew test` -- full suite green (BUILD SUCCESSFUL)
- FEEDER consume verified to add energy to `composite.addEnergy()` not individual member energy
- STV voting verified with majority wins, tied vote resolution, invalid direction handling
- Rigid body movement verified with formation preservation, blocking, registry updates

## Self-Check: PASSED

All 5 key files exist. Both commits (efc8e6a, 9e00e72) verified in git log.
