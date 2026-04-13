---
phase: 12-composite-entities
plan: 05
subsystem: engine, websocket
tags: [perception, stitching, sensor, tick-broadcast, bot-registry]
dependency_graph:
  requires: [CompositeMember, Role, CompositeRegistry, CompositePerception]
  provides: [stitched-perception, composite-tick-count, remapEntity]
  affects: [PerceptionBroadcaster.java, TickBroadcaster.java, BotRegistry.java, Messages.java]
tech_stack:
  added: []
  patterns: [memoization-per-tick, coverage-union-hashset]
key_files:
  created:
    - src/test/java/com/paralife/engine/CompositePerceptionTest.java
  modified:
    - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
    - src/main/java/com/paralife/websocket/TickBroadcaster.java
    - src/main/java/com/paralife/websocket/Messages.java
    - src/main/java/com/paralife/engine/BotRegistry.java
    - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
    - src/test/java/com/paralife/websocket/TickBroadcasterTest.java
    - src/test/java/com/paralife/engine/BotRegistryTest.java
decisions:
  - "stitchSensorCoverage is package-private (not private) for direct unit testing"
  - "Stitched neighbourhood sorted by (y, x) for deterministic row-major output"
  - "Blind composite cache stores null sentinel — checked per-bot to skip perception send"
metrics:
  duration_seconds: 450
  completed: "2026-04-13T17:11:48Z"
  tasks_completed: 2
  tasks_total: 2
  tests_added: 13
  files_created: 1
  files_modified: 7
---

# Phase 12 Plan 05: Perception & Tick Broadcast Summary

SENSOR-based stitched perception for composite members with per-composite memoization, composite count in tick messages, and BotRegistry remapEntity for clean entity transitions.

## One-liner

PerceptionBroadcaster builds union of SENSOR 5x5 circles via HashSet deduplication, memoized per composite per tick; TickBroadcaster includes composite count; BotRegistry gains remapEntity for formation/dissolution transitions.

## Completed Tasks

| Task | Name | Commit | Key Changes |
|------|------|--------|-------------|
| 1 | SENSOR-based stitched perception in PerceptionBroadcaster | 8d115ed | PerceptionBroadcaster.java (stitchSensorCoverage, buildStitchedPerception, composite routing) + CompositePerceptionTest.java (9 tests) + PerceptionBroadcasterTest.java (2 tests) |
| 2 | TickBroadcaster composite count and BotRegistry cleanup | 7db4a04 | Messages.Tick 5-field record, TickBroadcaster CompositeRegistry injection, BotRegistry.remapEntity() |

## Implementation Details

### Task 1: SENSOR-based stitched perception

- Injected `CompositeRegistry` into `PerceptionBroadcaster` as 5th constructor parameter
- `onTick` checks `instanceof Entity.CompositeMember` for each bot and routes to composite perception path
- `stitchSensorCoverage()` iterates composite members, finds SENSORs, collects their 5x5 cells into a `HashSet<Position>` (deduplication)
- `buildStitchedPerception()` converts coverage set to sorted `List<List<CellView>>` neighbourhood
- Memoization via `HashMap<String, Messages.CompositePerception>` — built once per composite per tick, reused for all members (T-12-12 mitigation)
- Blind composites (no SENSOR members) stored as null in cache, all member bots skip perception send (D-20)
- Each composite member receives identical stitched neighbourhood but individual `self` EntityState and `role` string
- 9 tests in CompositePerceptionTest: sensor circle, union, non-sensor exclusion, blind composite, same perception, metadata, memoization, toroidal wrap, blind skip
- 2 tests added to PerceptionBroadcasterTest: composite member routing, regular particle routing

### Task 2: TickBroadcaster composite count and BotRegistry cleanup

- `Messages.Tick` record extended to 5 fields: added `compositeCount`
- `TickBroadcaster` injects `CompositeRegistry`, includes `compositeRegistry.size()` in tick message
- `BotRegistry.remapEntity(sessionId, newEntityId, position)` — cleans up old entity mapping and creates new one, preserving session. Simplifies formation/dissolution code.
- Updated `TickBroadcasterTest` for 5-field constructor and new `compositeCount` test
- 2 new tests in `BotRegistryTest` for `remapEntity`

## Deviations from Plan

None - plan executed exactly as written.

## Verification

- `./gradlew test --tests "com.paralife.engine.CompositePerceptionTest"` -- 9 tests pass
- `./gradlew test --tests "com.paralife.engine.PerceptionBroadcasterTest"` -- 14 tests pass
- `./gradlew test --tests "com.paralife.websocket.TickBroadcasterTest"` -- 7 tests pass
- `./gradlew test --tests "com.paralife.engine.BotRegistryTest"` -- 13 tests pass
- `./gradlew test` -- full suite green

## Self-Check: PASSED

All 9 files verified present. All 3 commits (0ab05c9, 8d115ed, 7db4a04) verified in git log.
