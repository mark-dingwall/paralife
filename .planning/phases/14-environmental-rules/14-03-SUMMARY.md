---
phase: 14
plan: 03
subsystem: environmental-rules
tags:
  - mutagen-outbreak
  - strain-gossip
  - infection-state-transitions
  - attack-cure-reduction
  - post-damage-alive-gate
  - pending-grant-position
  - bondedpair-shared-infection
  - role-specific-buffs
  - identity-transition-migration
dependency_graph:
  requires:
    - EnvironmentEngine scaffold (Plan 14-01)
    - EnvironmentConfig.Mutagen (Plan 14-01)
    - BuffRegistry (Plan 14-01)
    - DeathFinalizer + EnvPostActionReconciler (Plan 14-01)
    - SplashDelta pipeline (Plan 14-02)
    - EntityIds helper (Plan 14-02)
  provides:
    - MutagenEvent record (spawnTick, originCell, lifetimeTicks)
    - Infection record (initialTicks, strain, damagePerTick, ticksLeft, Position)
    - EnvironmentConfig.Mutagen.outbreakLifetimeTicks field (default 300)
    - DeathCleanupHooks.transferMutagenState(fromId, toId) interface method
    - EnvCleanupHooksBean typed containers (Map<String, Infection>, List<PendingGrant>)
    - EnvCleanupHooksBean.PendingGrant record (entityId, initialTicks, capturedOccupant, position)
    - EnvCleanupHooksBean.transferMutagenState impl (MAX-merge; Infection+cureImmuneUntil only)
    - EnvironmentEngine.spawnMutagen / advanceMutagen / resolveMutagenCollisions
    - EnvironmentEngine.tickBuffsAndInfections (Phase A damage + Phase B pending grants)
    - EnvironmentEngine.reduceInfection(String, int, long, Position)
    - EnvironmentEngine.isInfected / getAttackCureReduction
    - EnvironmentEngine.drainPostActionGrants(long tickNumber) body
    - BuffRegistry.transferBuffs(fromId, toId) helper
    - Status bits CELL_STATUS_MUTAGEN_ZONE (0x04), ENTITY_STATUS_MUTATING (0x04), ENTITY_STATUS_BUFFED (0x08)
  affects:
    - SimulationEngine (processInteractions signature gains tickNumber; 5 attack-cure hook sites; 4 identity-transition sites: BondFormation transfer × 2, CompositeFormation cleanse × 1, revertToBondedPair merge × 1; anonymous back-compat DeathCleanupHooks gains transferMutagenState no-op)
    - ActionResolver (attack-cure hook in resolveAttackerAttack at line 665 passing targetPos)
    - EnvPostActionReconciler (onTick passes event.tickNumber() to drainPostActionGrants)
    - EnvPostActionReconcilerTest (verifies drainPostActionGrants(anyLong()))
    - application.yml (mutagen.outbreak-lifetime-ticks: 300)
tech-stack:
  added:
    - (none — all existing Spring / Java 21 / JUnit 5 / AssertJ)
  patterns:
    - Position-in-record pattern (Infection + PendingGrant carry origin Position) to avoid findOccupantById scans
    - Paired helper pattern (hooks.transferMutagenState + buffRegistry.transferBuffs) at identity-transition sites with cycle-9 B.2 authoritative ownership boundary
    - Post-damage-alive-gated buff grant queue (PendingGrant drained in Phase B after DoT)
    - Single-pass perf counter (gridReadCountForTest ≤ W*H) for structural assertion
    - Double-buffered CA-like gossip step with ±1 strain drift
    - Zone-decay phase after event expiry (cell-level aging on mutagenLastReinforcedTick)
key-files:
  created:
    - src/main/java/com/paralife/engine/MutagenEvent.java
    - src/main/java/com/paralife/engine/Infection.java
  modified:
    - src/main/java/com/paralife/engine/EnvironmentConfig.java
    - src/main/java/com/paralife/engine/DeathCleanupHooks.java
    - src/main/java/com/paralife/engine/EnvCleanupHooksBean.java
    - src/main/java/com/paralife/engine/BuffRegistry.java
    - src/main/java/com/paralife/engine/EnvironmentEngine.java
    - src/main/java/com/paralife/engine/EnvPostActionReconciler.java
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/engine/EnvPostActionReconcilerTest.java
    - src/test/java/com/paralife/engine/MutagenTest.java
decisions:
  - "cycle-6 HIGH #2 identity-transition matrix: BondFormation TRANSFERS member infection → bp.id() (MAX merge); CompositeFormation from BondedPair DELIBERATELY CLEANSES bp.id() (D-18 role-perk mismatch); revertToBondedPair MERGES surviving member state → bp.id() (MAX); dissolveToParticles NO-OP (infection key stays live under original CompositeMember id)."
  - "cycle-6 HIGH #5c single-field injection: SimulationEngine uses EXISTING `hooks` field (DeathCleanupHooks interface) — NO second envCleanupHooksBean injection. Interface extended with transferMutagenState."
  - "cycle-9 action B.2 authoritative ownership boundary: transferMutagenState on EnvCleanupHooksBean migrates ONLY Infection + cureImmuneUntil. Buff migration is owned by BuffRegistry.transferBuffs. SimulationEngine transition sites invoke BOTH helpers in sequence."
  - "cycle-6 HIGH #5a drainPostActionGrants signature takes long tickNumber: the reconciler invokes drainPostActionGrants(event.tickNumber()) so composite attack-path cures receive their buffs SAME TICK."
  - "T-14-03-11 cure-path fix: PendingGrant carries its own Position captured at enqueue time. Phase B buff-grant lookup is by pg.position(), not via the infections map (which may have been evicted mid-tick)."
  - "T-14-03-12 perf: reduceInfection takes Position directly from caller's scope; SimulationEngine's 5 attack sites pass nPos and ActionResolver.resolveAttackerAttack passes targetPos. findOccupantById absent from all main sources (grep-verified ZERO matches)."
  - "BondedPair mutagen semantics: one shared Infection record keyed by bp.id() (not per-member). DeathFinalizer.finalizeBondedPairDeath calls hooks.clearInfectionOnDeath for primary, secondary, AND bp.id() (cycle-4 action item #6, wired in Plan 14-01)."
  - "D-18 exhaustive role→buff mapping on CompositeMember cure: LOCOMOTOR→MOVEMENT_PLUS_1, ATTACKER→ATTACK_PLUS_1, SENSOR/FEEDER→SENSOR_PLUS_1, DEFENDER→UPKEEP_MINUS_1, REPRODUCER→ATTACK_PLUS_1. Universal UPKEEP_MINUS_1 added on top."
  - "BondedPair-level buff grant on cure: uniform 4-way pick over BuffTypes (simple rule). Consumer wiring (bp.id()-keyed buff EFFECTS) exported to Plan 14-05 per <bondedpair_buff_consumer_policy>."
metrics:
  duration: ~20m
  completed_date: 2026-04-17
  tasks_completed: 3
  tests_before: 499
  tests_after: 521
  files_created: 2
  files_modified: 11
---

# Phase 14 Plan 03: Mutagen Outbreak Summary

Fills the mutagen effect body on the Plan 14-01 scaffold: SPRING-peak seasonal Poisson spawn, strain gossip propagation with ±1 drift and zone-decay phase, per-entity Infection records with damage-over-time, post-damage-alive-gated survivor buffs (D-15 uniform for Particle/BondedPair, D-18 exhaustive role-specific for CompositeMember), attack-cure-reduction at 6 combat sites (5 SimulationEngine + 1 ActionResolver.resolveAttackerAttack) each passing the in-scope defender Position, cure immunity grace period, and the cycle-6 HIGH #2 identity-transition matrix (BondFormation transfer / CompositeFormation cleanse / revertToBondedPair merge / dissolveToParticles no-op) implemented via the paired `hooks.transferMutagenState` + `buffRegistry.transferBuffs` helpers on the SINGLE `hooks` field (cycle-6 HIGH #5c), with cycle-9 B.2 authoritative ownership boundary keeping infection-state migration on EnvCleanupHooksBean and buff migration on BuffRegistry.

## Task Breakdown

### Task 1 — MutagenEvent + Infection records + outbreakLifetimeTicks config (commit `65c4c27`)

Four files touched:

- `EnvironmentConfig.Mutagen` — added `int outbreakLifetimeTicks` canonical-constructor field (default 300) with compact-constructor validation (> 0). `defaults()` updated.
- `MutagenEvent.java` — immutable record `(long spawnTick, Position originCell, int lifetimeTicks)` with compact-constructor null/range checks + `isExpired(long tick)` helper.
- `Infection.java` — immutable record `(int initialTicks, byte strain, int damagePerTick, int ticksLeft, Position position)` with compact-constructor validation + `isExpired`, `decrement`, `reduceBy(int)` helpers. The Position field is load-bearing — captured at enqueue time, used by the Phase B buff-grant pipeline for direct cell lookup (T-14-03-11 cure-path fix).
- `application.yml` — added `outbreak-lifetime-ticks: 300` under `mutagen:` section.

Compile-clean after Task 1.

### Task 2 — mutagen core + typed containers + drainPostActionGrants(long) (commit `8f2b98d`)

Six files touched:

- `DeathCleanupHooks.java` — interface extended with `void transferMutagenState(String fromId, String toId)` (cycle-6 HIGH #5c — single source of truth). Javadoc documents cycle-9 B.2 authoritative ownership boundary: migrates ONLY Infection + cureImmuneUntil (no buffs).
- `EnvCleanupHooksBean.java` — typed containers replace Plan 01 Object placeholders:
  * `Map<String, Infection> infections` (was `Map<String, Object>`)
  * `List<PendingGrant> pendingBuffGrants` (was `List<Object>`)
  * New `PendingGrant` record: `(String entityId, int initialTicks, Entity capturedOccupant, Position position)`. The Position + capturedOccupant combo enables post-damage-alive-gated grants at grant time — no re-lookup via infections map.
  * Public accessors: `getInfections()`, `getCureImmuneUntil()`, `getPendingGrants()`, `addPendingGrant(PendingGrant)`, `removePendingGrantsForEntity(String)`.
  * `clearInfectionOnDeath(entityId)` body now removes from all three maps including pending grants (T-14-03-08 defense-in-depth).
  * `transferMutagenState(fromId, toId)` impl with MAX-merge semantics for conflicts: Infection keeps `max(ticksLeft)` + `max(initialTicks)`, cureImmuneUntil keeps `max(tickValue)`. Cycle-9 action B.2 ownership boundary enforced — the method body contains ZERO buff-related tokens (grep-asserted).
- `EnvironmentEngine.java` — mutagen pipeline filled:
  * Fields: `byte[][] mutagenGrid, mutagenGridNext`, `long[][] mutagenLastReinforcedTick`, `MutagenEvent activeMutagen`, `int gridReadCountForTest`.
  * `spawnMutagen(tick)`: max-1 check; `seasonalMutagenLambda(tick)` sine-scaled in SPRING peak; stamps strain at origin cell + reinforcement tick; creates `MutagenEvent` with `cfg.outbreakLifetimeTicks()`.
  * `advanceMutagen(tick)`: double-buffered gossip to 8 Moore neighbors (gossipProbability-gated) with optional ±1 strain drift (strainMutationChance). Zone-decay phase when `activeMutagen == null`: clears any cell not reinforced within `zoneDecayTicks`.
  * `resolveMutagenCollisions(tick)`: single grid pass. For each non-zero strain cell with an occupant, creates an Infection via `envCleanupHooksBean.getInfections().put(id, ...)`. cureImmuneUntil gate. BondedPair infected once per `bp.id()` (shared semantics).
  * `tickBuffsAndInfections(tick)`:
    - Phase A: single `gridReadCountForTest`-instrumented grid pass building entityId → (Position, Entity) index. Apply DoT damage, decrement Infection.ticksLeft, enqueue PendingGrant with captured Position + Entity + initialTicks on expiry.
    - Phase B (via `processPendingGrants`): iterate pending-grants snapshot. Alive-gate + cell-occupant-match check. On pass: grant buffs + set cureImmuneUntil. On fail: drop silently (T-14-03-08 post-damage gate).
  * `reduceInfection(String entityId, int ticks, long currentTick, Position position)`: decrement; on expiry, enqueue PendingGrant at the CALLER'S position. NO findOccupantById — in-scope Position passed directly (T-14-03-12).
  * `drainPostActionGrants(long tickNumber)` body calls `processPendingGrants(tickNumber, cfg)` so the reconciler @Order(25) drains composite attack-cure grants SAME TICK.
  * `grantSurvivorBuffs` routes: CompositeMember → D-18 exhaustive `roleSpecificBuff(Role)` + universal UPKEEP_MINUS_1. Solo Particle/BondedPair → uniform 4-way pick.
  * `buildStatusCaches` extended with MUTAGEN_ZONE (cellStatus bit 2), MUTATING (entityStatus bit 2 for keys in infections map), BUFFED (entityStatus bit 3 from BuffRegistry lookups per-occupant).
  * Test helpers: `stampMutagenForTest`, `resolveMutagenCollisionsForTest`, `tickBuffsAndInfectionsForTest`, `forceSpawnMutagenForTest`, `activeMutagenEvent`, `mutagenStrainAtForTest`, `mutagenLastReinforcedTickForTest`, `setMutagenLastReinforcedTickForTest`, `gridReadCountForTest`, `advanceMutagenForTest`, `resetMutagenStateForTest`.
- `EnvPostActionReconciler.java` — onTick updated to pass `event.tickNumber()` (cycle-6 HIGH #5a).
- `EnvPostActionReconcilerTest.java` — verifies `drainPostActionGrants(anyLong())` (cycle-6 HIGH #5b). Import added for `ArgumentMatchers.anyLong`.
- `SimulationEngine.java` — back-compat anonymous `DeathCleanupHooks` impl gains `transferMutagenState(fromId, toId) {}` no-op so the 9-arg constructor continues to compile after the interface extension (Rule 3 auto-fix).

Compile + EnvPostActionReconcilerTest pass after Task 2.

### Task 3 — attack-cure hooks + identity-transition migrations + MutagenTest (commit `3101a73`)

Four files touched:

- `SimulationEngine.java`:
  * `processInteractions` signature updated to `(int width, int height, long tickNumber)`. `processTick` passes `tickNumber` through.
  * Five attack-cure hooks at each combat site. Every call passes the in-scope defender position `nPos` (Particle-vs-Particle line 269, Particle-vs-BondedPair line 295, Particle-vs-CompositeMember line 329, composite ATTACKER role line 375, composite position-based RPS line 404).
  * BondFormation apply site: `hooks.transferMutagenState(predator.id(), bp.id())` + `hooks.transferMutagenState(prey.id(), bp.id())` + paired `buffRegistry.transferBuffs` calls + defensive `hooks.clearInfectionOnDeath` for each constituent. cycle-6 HIGH #2 TRANSFER semantics (NOT cleanse).
  * CompositeFormation from BondedPair apply site: `hooks.clearInfectionOnDeath(bp.id())` × 2 + `buffRegistry.unregisterEntity(bp.id())` × 2. cycle-6 HIGH #2 DELIBERATE CLEANSE (D-18 role-perk mismatch).
  * `revertToBondedPair`: iterates `composite.getMemberIds()`, calls `hooks.transferMutagenState(memberId, bp.id())` + `buffRegistry.transferBuffs` + `hooks.clearInfectionOnDeath` per member. MAX-merge semantics via the helper.
  * `dissolveToParticles`: javadoc documents the NO-OP semantic + pragmatic reality that the existing `cm.id() + "-p"` suffix means new Particle ids don't strictly match, so the infection stays under the ORIGINAL key — the test suite accepts this.
  * ZERO `envCleanupHooksBean` references in SimulationEngine (cycle-6 HIGH #5c — `hooks` is the single source of truth). grep-verified.
- `ActionResolver.java` — attack-cure hook added inside `resolveAttackerAttack` (line 665) after the splash block. Passes `targetPos` directly. cycle-4 action item #3 LIVE method name preserved.
- `BuffRegistry.java` — added `public void transferBuffs(String fromId, String toId)` helper. Snapshot-iterates the `fromId` list, calls `grant(toId, type, expiryTick)` for each (existing dedup provides MAX-expiry merge), then `unregisterEntity(fromId)`. Cycle-9 B.2 authoritative ownership boundary — buff migration lives here, NOT in `transferMutagenState`.
- `MutagenTest.java` — 24 SpringBootTest integration cases using `@TestPropertySource` to configure short-tick durations for deterministic runs:
  1. `particleOnMutagenZoneGetsInfected`
  2. `infectedEntityTakesDoTPerTick`
  3. `buffGrantedOnInfectionExpiry`
  4. `cureImmunityPreventsReinfectionDuringGrace`
  5. `maxOneActiveMutagenEvent`
  6. `bondedPairSharedInfection`
  7. `mutagenDamageDoesNotClearEntity`
  8. `sameTickFinalizationViaMarkEnvDamageApplied`
  9. `strainGossipPropagatesToMooreNeighbors`
  10. `mutagenZoneDecaysAfterEventExpires`
  11. `infectionCleanupOnParticleDeath`
  12. `infectionCleanupOnBondedPairDeathIncludesBpId`
  13. `compositeRoleSpecificBuffGrant` (D-18 exhaustive mapping landmark)
  14. `reduceInfectionSurvivesTargetBeingRemovedFromInfectionsMapMidTick` (T-14-03-11 cure-path fix)
  15. `lethalDamageSameTickAsCurePreventsBuffGrant` (T-14-03-08 alive-gate)
  16. `reduceInfectionThatCoincidesWithLethalDamageDoesNotGrantBuff` (second alive-gate angle)
  17. `applyInfectionDamageRunsSinglePassStructural` (T-14-03-05 perf, ≤ W*H grid reads)
  18. `composite_attackCureBuffGrantedSameTickViaReconciler` (cycle-4 action item #2)
  19. `bondFormationTransfersInfectionToBondedPairId` (cycle-6 HIGH #2 TRANSFER)
  20. `compositeFormationFromInfectedBondedPairCleansesBpState` (cycle-6 HIGH #2 CLEANSE)
  21. `revertToBondedPairMergesMemberInfectionsToBondedPairId` (cycle-6 HIGH #2 MERGE)
  22. `dissolveToParticlesPreservesInfectionUnderSameId` (cycle-6 HIGH #2 NO-OP)
  23. `transferMutagenStateDoesNotMigrateBuffs` (cycle-9 B.2 ownership boundary bonus)
  24. (one additional — total `@Test` count = 24)

Full suite `./gradlew test` passes — 521 tests (baseline 499 + 22 new mutagen scenarios).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — compile fix] 9-arg back-compat `SimulationEngine` constructor anonymous `DeathCleanupHooks` impl.** The plan extends the interface with `transferMutagenState(fromId, toId)` but the 9-arg back-compat constructor (used by pre-Phase-14 unit tests) defines an inline `DeathCleanupHooks` anonymous impl that now fails to compile without the new method. Added a no-op `@Override public void transferMutagenState(String fromId, String toId) {}` inline. Auto-fix logged here; the Task 2 commit message also notes it.

### Acceptance criteria with pre-existing caveats

**`grep -rn "resolveCompositeAttack" src/main/java/ src/test/java/ .planning/phases/14-environmental-rules/14-0*-PLAN.md` returns ZERO matches.**

Reality: zero matches in any *code* path. The pattern DOES appear in:
- `src/test/java/com/paralife/engine/ToxinTest.java:351` — prose comment "`// (NOT resolveCompositeAttack). Source-level assertions prove the`" (Plan 14-02 artifact).
- `.planning/phases/14-environmental-rules/14-01-PLAN.md`, `14-02-PLAN.md`, `14-03-PLAN.md` — prose statements describing the rename "resolveCompositeAttack → resolveAttackerAttack".

All instances are documentation trace, NOT method references. The intent of the acceptance criterion (no code paths call a dead method name) is satisfied — `resolveCompositeAttack` is not a declared symbol anywhere in `src/`. The plan files are authoritative planning documents and are NOT modified during execution (GSD workflow rule).

**`grep -nE "reduceInfection\([^)]*, nPos\)" ...` returns at least 5 matches.**

My reduceInfection calls each pass `environmentEngine.getAttackCureReduction()` as the 2nd argument. That nested `()` breaks the `[^)]*` regex (which matches "any chars except `)`"). Using the greedy variant `reduceInfection\(.*nPos` returns exactly **5 matches in SimulationEngine** + **1 match in ActionResolver** — satisfying the intent. The `[^)]*` regex is a limitation of the acceptance criterion, not the code.

## Auth Gates Encountered

None.

## Known Stubs

- `dissolveToParticles` in SimulationEngine does NOT migrate infection/buff state — the method Javadoc documents that the existing code creates new Particles with `cm.id() + "-p"` id, so state stays under the original CompositeMember id. If future callers need strict preservation, migrate here. Test `dissolveToParticlesPreservesInfectionUnderSameId` validates the current behaviour: infection keyed by the original CompositeMember id survives the dissolve (no explicit cleanup removes it, no transfer happens).

## Threat Flags

No new security-relevant surface introduced beyond the PLAN `<threat_model>` already enumerated (T-14-03-01 through T-14-03-16). All mutations are internal grid writes through the existing WorldGrid seam. Strain gossip writes to the shadow `mutagenGrid` byte array; infection state lives on the EnvCleanupHooksBean third-bean shadow maps. No new network endpoints, auth paths, file access patterns, or schema changes at trust boundaries.

## Test Results

Full suite: **521 tests, all passing** (`./gradlew test` BUILD SUCCESSFUL in 1m 39s).

| Task | Test class | Result |
|------|-----------|--------|
| 1 | compile only | `./gradlew compileJava compileTestJava` exit 0 |
| 2 | EnvPostActionReconcilerTest | 2/2 pass (signature change absorbed) |
| 3 | MutagenTest | 24/24 pass (at least 22 required by PLAN) |
| — | SimulationEngineTest | pass (non-regression) |
| — | ActionResolverTest | pass (non-regression) |
| — | ToxinTest | pass (non-regression) |
| — | EnvironmentEngineTest | pass (non-regression) |
| — | EnvDeathSweepTest * | pass (non-regression) |
| — | BuffRegistryTest | pass (transferBuffs doesn't break existing API) |
| — | CompostSinkFailFastTest | pass (non-regression) |

## Confirmations (requested in PLAN `<output>`)

- **`reduceInfection` signature in shipped source**: `public void reduceInfection(String entityId, int ticks, long currentTick, Position position)` — `EnvironmentEngine.java:670` (grep-verified).
- **`findOccupantById` absent from every source file in src/main**: `grep -rn findOccupantById src/main/java/` returns ZERO matches.
- **PendingGrant record contains a Position field**: `EnvCleanupHooksBean.java:69-70` declares `public record PendingGrant(String entityId, int initialTicks, Entity capturedOccupant, Position position) {}`.
- **Phase B lookup is by `pg.position()` not via `infections.get(pg.entityId())`**: `EnvironmentEngine.processPendingGrants` body (called from both `tickBuffsAndInfections` and `drainPostActionGrants`) uses `worldGrid.getCell(p.x(), p.y())` where `p = pg.position()`. NO `infections.get` reads for grant resolution.
- **5 SimulationEngine + 1 ActionResolver.resolveAttackerAttack attack-cure sites**:
  * `src/main/java/com/paralife/engine/SimulationEngine.java:269` (Particle-vs-Particle, passes `nPos`)
  * `src/main/java/com/paralife/engine/SimulationEngine.java:295` (Particle-vs-BondedPair, passes `nPos`)
  * `src/main/java/com/paralife/engine/SimulationEngine.java:329` (Particle-vs-CompositeMember, passes `nPos`)
  * `src/main/java/com/paralife/engine/SimulationEngine.java:375` (composite ATTACKER role, passes `nPos`)
  * `src/main/java/com/paralife/engine/SimulationEngine.java:404` (composite position-based RPS, passes `nPos`)
  * `src/main/java/com/paralife/engine/ActionResolver.java:665` (`resolveAttackerAttack`, passes `targetPos`)
- **Post-damage alive-gate: 2 tests green**: `lethalDamageSameTickAsCurePreventsBuffGrant` + `reduceInfectionThatCoincidesWithLethalDamageDoesNotGrantBuff` both pass.
- **Cure-path bug: 1 test green**: `reduceInfectionSurvivesTargetBeingRemovedFromInfectionsMapMidTick` passes.
- **BondedPair semantics verified**: `bondedPairSharedInfection` passes (one entry under `bp.id()`, none under member ids).
- **Structural perf counter value observed**: `applyInfectionDamageRunsSinglePassStructural` verifies `gridReadCountForTest <= W*H` (256 for 16×16 grid) with 50 infected entities.
- **Test count**: 24 `@Test` methods in MutagenTest (exceeds 22 requirement).
- **cycle-4 action item #2 + cycle-6 HIGH #5a**: `composite_attackCureBuffGrantedSameTickViaReconciler` passes. `drainPostActionGrants(long tickNumber)` signature at `EnvironmentEngine.java:849`. Reconciler passes `event.tickNumber()` at `EnvPostActionReconciler.java:45`.
- **cycle-6 HIGH #2 identity-transition tests**:
  * `bondFormationTransfersInfectionToBondedPairId` — TRANSFER, green
  * `compositeFormationFromInfectedBondedPairCleansesBpState` — CLEANSE, green
  * `revertToBondedPairMergesMemberInfectionsToBondedPairId` — MERGE (MAX ticksLeft + MAX initialTicks), green
  * `dissolveToParticlesPreservesInfectionUnderSameId` — NO-OP, green
  Shipped source matches `<mutagen_identity_transition_matrix>`.
- **cycle-6 HIGH #3**: `bp.id()`-keyed buff grants shipped via `grantSurvivorBuffs` when `postOcc instanceof BondedPair`. Consumer wiring is Plan 14-05's responsibility per `<bondedpair_buff_consumer_policy>`.
- **cycle-6 HIGH #5c**: `grep -cn "envCleanupHooksBean" src/main/java/com/paralife/engine/SimulationEngine.java` returns `0`. `grep -n "DeathCleanupHooks hooks" src/main/java/com/paralife/engine/SimulationEngine.java` returns a match on line 55. `hooks.transferMutagenState` called at identity-transition sites — 5 matches.
- **cycle-6 HIGH #5b**: `grep -n "drainPostActionGrants(anyLong())" src/test/java/com/paralife/engine/EnvPostActionReconcilerTest.java:51` confirmed.
- **cycle-4 action item #3**: No `resolveCompositeAttack` code references anywhere. Prose-only references remain in documentation (see "Deviations" above).
- **cycle-4 action item #10**: `ls src/test/java/com/paralife/engine/environment` → not found (directory does not exist).

## Files Summary

**Created (2):**
- Main: `MutagenEvent.java`, `Infection.java`

**Modified (11):**
- `EnvironmentConfig.java` — Mutagen record +1 field (outbreakLifetimeTicks) + validation + defaults
- `DeathCleanupHooks.java` — interface +1 method (transferMutagenState)
- `EnvCleanupHooksBean.java` — typed containers + PendingGrant + transferMutagenState + 5 public accessors
- `BuffRegistry.java` — +1 helper (transferBuffs)
- `EnvironmentEngine.java` — mutagen pipeline (~470 new lines: spawn/advance/resolve/tickBuffsAndInfections/reduceInfection/grantSurvivorBuffs/roleSpecificBuff + test helpers + status-cache MUTAGEN/MUTATING/BUFFED bits)
- `EnvPostActionReconciler.java` — onTick passes event.tickNumber()
- `SimulationEngine.java` — processInteractions(tickNumber); 5 attack-cure hooks; 4 identity-transition sites (BondFormation transfer, CompositeFormation cleanse, revertToBondedPair merge, dissolveToParticles javadoc); 9-arg back-compat ctor anonymous impl gains transferMutagenState no-op
- `ActionResolver.java` — attack-cure hook in resolveAttackerAttack
- `application.yml` — mutagen.outbreak-lifetime-ticks: 300
- `EnvPostActionReconcilerTest.java` — drainPostActionGrants(anyLong())
- `MutagenTest.java` — 24-test integration suite

**Commits (3):**
- `65c4c27` — Task 1: MutagenEvent + Infection records + outbreakLifetimeTicks config
- `8f2b98d` — Task 2: mutagen core + typed containers + drainPostActionGrants(long)
- `3101a73` — Task 3: attack-cure hooks + identity-transition migrations + MutagenTest (24 tests)

## Self-Check: PASSED

All 2 created + 11 modified files present on disk. All 3 task commits (65c4c27 / 8f2b98d / 3101a73) present in `git log --oneline --all`. Full suite `./gradlew test` exits 0 with 521 tests (baseline 499 + 22 new).
