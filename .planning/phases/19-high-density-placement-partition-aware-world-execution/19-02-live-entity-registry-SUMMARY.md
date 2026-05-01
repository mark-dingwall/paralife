---
phase: 19
plan: 02
subsystem: engine/registry
tags: [registry, sparse-set, lifecycle, java, spring-boot, scale-07]
completed: "2026-05-01T10:29:39Z"
duration_minutes: ~60

dependency_graph:
  requires: [19-01-placement-index]
  provides: [LiveEntityRegistry sparse-set with full lifecycle hooks]
  affects: [SimulationEngine, DeathFinalizer, ActionResolver, WorldWebSocketHandler]

tech_stack:
  added: []
  patterns:
    - Sparse-set (dense ArrayList + HashMap id→index) for O(1) register/unregister/updatePosition
    - Setter injection (@Autowired required=false + @Lazy) for pre-Phase-19 unit test backward compat
    - Optional<String> vestigial sessionId field (CONSENSUS-H1 OPTION B USER-LOCKED)
    - Row-major sort on snapshot() as Phase 19 compatibility shim (REVIEWS HIGH-1)

key_files:
  created:
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java
    - src/test/java/com/paralife/engine/LiveEntityRegistryTest.java
    - src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java
  modified:
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/engine/DeathFinalizer.java
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java

decisions:
  - "CONSENSUS-H1 OPTION B USER-LOCKED: TickBroadcaster NOT migrated in Phase 19; EntityEntry.sessionId vestigial — populated only at WS handshake, Optional.empty() for all server-internal creations"
  - "Setter injection (@Autowired required=false) for LiveEntityRegistry in ActionResolver, DeathFinalizer, SimulationEngine — null-guarded at each hook site so pre-Phase-19 tests compile unchanged"
  - "register() throws IllegalStateException on conflicting re-register (REVIEWS MEDIUM-3) — defence in depth against silently-dropped lifecycle hooks"
  - "snapshot() sorts by row-major linear index x*height+y — Phase 19 shim to preserve Collections.shuffle determinism across Plan 04 cut (REVIEWS HIGH-1 / R2-14)"

metrics:
  completed_date: "2026-05-01"
  tasks_completed: 3
  files_created: 3
  files_modified: 4
  test_count_added: 15
  full_suite_result: PASS
  full_suite_duration: ~3m35s
---

# Phase 19 Plan 02: LiveEntityRegistry Summary

**One-liner:** Sparse-set LiveEntityRegistry with O(1) register/unregister/updatePosition, row-major snapshot sort, IllegalStateException on conflict, and full lifecycle hooks wired across 13 structural mutation sites in 4 files.

## What Was Built

`LiveEntityRegistry` is a `@Component` sparse-set (dense `ArrayList<EntityEntry>` + `HashMap<String,Integer>` id→index) that maintains the authoritative list of live grid-occupant entities. Plan 04 will consume it to replace the O(width×height) grid scans in `SimulationEngine` and `EnvironmentEngine`.

### Core Registry (LiveEntityRegistry.java — 169 lines)

- `register(entityId, position, Optional<String> sessionId)` — O(1); throws `IllegalStateException` on conflicting re-register; idempotent on identical re-register
- `unregister(entityId)` — O(1) swap-and-pop; idempotent on missing id
- `updatePosition(entityId, Position)` — O(1); preserves sessionId; noop on missing id
- `snapshot()` — O(N + N log N) shallow copy sorted by row-major linear index `x*height+y`
- `clearForTest()` — test helper only

### Lifecycle Hooks (4 files, 13 structural sites)

**ActionResolver.java** (3 sites):
- `resolveMove` — `updatePosition(ra.particle.id(), target)` after solo Particle placement
- `resolveReproduce` — `register(child.id(), target, Optional.empty())` for primary + bonus children
- `resolveReproducerBud` — `register(child.id(), target, Optional.empty())` for composite-reproducer bud
- `executeCompositeMovement` — `updatePosition(member.id(), target)` per moved CompositeMember

**DeathFinalizer.java** (3 sites):
- `finalizeParticleDeath` — `unregister(id)` immediately after `botRegistry.unregisterByEntity`
- `finalizeBondedPairDeath` — `unregister(primaryId)` + `unregister(secondaryId)` after bot unregs; `unregister(bp.id())` at grid clear

**SimulationEngine.java** (7 sites):
- Bond-formation — `unregister(predator)` + `unregister(prey)` + `register(bondedPair, primaryPos, Optional.empty())`
- Composite-formation — `unregister(bp1)` + `unregister(bp2)` + `register(member1, pos1)` + `register(member2, pos2)`
- `cleanupCompositeMemberCellViaFinalizer` (handleMemberDeath) — `unregister(id)`
- `revertToBondedPair` — `unregister` all surviving member ids + `register(bondedPair, pos)`
- `dissolveToParticles` — `unregister(cm.id())` + `register(particle.id(), pos)`
- `checkPanicZone` (2 sites) — `unregister(memberId)` for no-position and non-member-cell paths

**WorldWebSocketHandler.java** (2 sites):
- `handleRegister` — `register(entityId, pos, Optional.of(session.getId()))` after `botRegistry.register`
- `cleanupByEntityId` stalled-close path — `unregister(entityId)` before `botRegistry.unregisterBySession`
- `cleanupBot` — `unregister(entityId)` before `botRegistry.unregisterBySession`

All hooks are null-guarded; beans use `@Autowired(required=false) @Lazy` setter injection so pre-Phase-19 unit tests that instantiate these classes directly still compile and run without Spring context.

### Tests

**LiveEntityRegistryTest.java** (225 lines, 14 unit tests):
- register/unregister/updatePosition basic behaviour
- `registerThrowsOnConflictingSessionId` (REVIEWS MEDIUM-3)
- `snapshotIsSortedByRowMajor` + `snapshotIsSortedByRowMajorAfterRemovals` (REVIEWS HIGH-1)
- `concurrentRegisterIsSafe` — 4 threads × 100 unique ids

**LiveEntityRegistryInvariantTest.java** (336 lines, 4 `@SpringBootTest` scenarios):
- `registryMatchesGridOccupantsAtRest` — 4 manually-registered particles
- `registryMatchesGridOccupantsAfterDeath` — energy-0 particle removed by tick
- `registryMatchesGridOccupantsAfterBondFormation` — bonding-probability=1.0, CATALYST+SPORE bond on tick 1 **(REVIEWS MEDIUM-4 MANDATORY)**
- `registryMatchesGridOccupantsAfterCompositeFormation` — two adjacent BondedPairs merge on tick 1 **(REVIEWS MEDIUM-4 MANDATORY)**

Each invariant scenario asserts two-way set equality between registry positions and non-Rock/non-Nutrient occupied grid cells. `sessionIdAgreesWithBotRegistry` assertion dropped (CONSENSUS-H1 OPTION B — TickBroadcaster does not consume sessionId in Phase 19).

## Deviations from Plan

None — plan executed exactly as specified. The Wave-1 hotfix (commit `d91f1b2`) was pre-applied before this executor started; the hook changes were pre-staged by the previous executor and verified/committed by this executor.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| `38d1dfa` | feat | LiveEntityRegistry sparse-set + ROW-MAJOR snapshot + unit tests (Wave-1, prior executor) |
| `d91f1b2` | fix | Defer TickEngine start + swap-and-allocate cellStatusCache (Wave-1 hotfix, prior executor) |
| `1b96146` | feat | Wire LiveEntityRegistry lifecycle hooks at all structural sites |
| `6d05308` | test | LiveEntityRegistryInvariantTest — 4 lifecycle scenarios |

## Self-Check: PASSED

| Check | Result |
|-------|--------|
| `LiveEntityRegistry.java` exists | FOUND |
| `LiveEntityRegistryTest.java` exists | FOUND |
| `LiveEntityRegistryInvariantTest.java` exists | FOUND |
| `SUMMARY.md` exists | FOUND |
| Commit `38d1dfa` exists | FOUND |
| Commit `1b96146` exists | FOUND |
| Commit `6d05308` exists | FOUND |
| Full `./gradlew test` passes | PASS (3m 35s, 0 failures) |
