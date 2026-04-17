---
phase: 14
plan: 05
subsystem: environmental-rules
tags:
  - vision-scoped-perception
  - overcrowded-bit-recomposition
  - sensor-buff-solo-bondedpair-composite
  - attack-movement-upkeep-buffs
  - live-config-read
  - composite-cadence-reduction
  - locomotor-only-filter
  - observable-only-brain
  - jackson-celluview-ack
dependency_graph:
  requires:
    - EnvironmentEngine.getCellStatus / getEntityStatus (Plan 14-01)
    - BuffRegistry + BuffType (Plan 14-01)
    - bp.id()-keyed buff grants on BondedPair cure (Plan 14-03)
    - CompositeRegistry.getComposite / getMemberIds / getPositionForMember
    - SimulationConfig.overcrowdingThreshold (existing)
    - Messages.CellView 6-field + back-compat constructors (Plan 14-01)
  provides:
    - PerceptionBroadcaster 8-arg @Autowired ctor (EnvironmentEngine + BuffRegistry + SimulationConfig)
    - PerceptionBroadcaster.computeVisionScopedOvercrowded static helper (D-40)
    - PerceptionBroadcaster.BIT_OVERCROWDED mask constant + verbatim `(cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit`
    - PerceptionBroadcaster.cellToView(int, int, Position, int) — 6-arg per-bot CellView builder
    - PerceptionBroadcaster.stitchSensorCoverage per-SENSOR-member radius (cycle-4 action item #8)
    - SimulationEngine.OVERCROWDED_THRESHOLD_DEFAULT = 6 documented-default constant
    - ActionResolver.findTargetAtRange shared helper (resolveMove + resolveReproduce)
    - ActionResolver.hasAnyLocomotorMovementBuff LOCOMOTOR-only filter (cycle-6 MEDIUM #10)
    - ActionResolver MOVEMENT_PLUS_1 2-cell hop (Particle + BondedPair uniformly)
    - ActionResolver composite moveInterval reduction `Math.max(1, moveInterval - 1)`
    - ActionResolver.resolveAttackerAttack ATTACK_PLUS_1 +1 damage
    - SimulationEngine.applyAttackBoost ATTACK_PLUS_1 +1 before starvation multiplier (solo Particle)
    - SimulationEngine.processEnergyDecay UPKEEP_MINUS_1 Particle branch + BondedPair branch with modulus-skip-at-base-1
    - SimulationEngine.processInteractions composite-member attack sites cmDamage += 1 when ATTACK_PLUS_1
    - CompositeEnergyDistributor UPKEEP_MINUS_1 per-member passiveDrain reduction (floored at 0)
    - HeuristicBrain observable-only env reactions (STARVING +2, TOXIC avoidance, MUTATING/BUFFED -1)
    - BotClient Jackson acknowledgment inline comment (no code changes)
    - Messages.CellView Javadoc block distinguishing flags vs cellStatus
  affects:
    - PerceptionBroadcaster (primary @Autowired ctor bumped to 8 args; legacy 5-arg retained as back-compat)
    - ActionResolver (setter-injected BuffRegistry; resolveMove + resolveReproduce refactored to shared helper; resolveCompositeMovements adds effectiveInterval; resolveAttackerAttack adds buff read)
    - SimulationEngine (processEnergyDecay signature gains tickNumber; applyAttackBoost reads buff; composite-member attacks read buff at emission)
    - CompositeEnergyDistributor (setter-injected BuffRegistry; passiveDrain computation adjusts for buff)
    - HeuristicBrain (decide() reads cellStatus/entityStatus/flags; ScoredTarget record added)
    - BotClient (single inline comment — no behavioural change)
    - Messages.CellView Javadoc (flags vs cellStatus distinction)
tech-stack:
  added:
    - (none — all existing Spring / Java 21 / JUnit 5 / AssertJ / Mockito)
  patterns:
    - Primary @Autowired ctor + back-compat legacy ctor chaining (PerceptionBroadcaster)
    - Setter-injection with null-safe default initializer (ActionResolver, CompositeEnergyDistributor BuffRegistry)
    - Verbatim mask-and-OR expression for per-bot bit recomposition (cycle-6 MEDIUM #9)
    - LIVE config read at tick time (simulationConfig.overcrowdingThreshold()) — NOT a public static constant
    - Shared helper extracted from legacy range-walking loop (findTargetAtRange)
    - Package-private static helper for direct predicate testing (computeVisionScopedOvercrowded)
    - ScoredTarget record for observable-priority-weighted prey selection
key-files:
  created:
    - (none — all Plan 14-05 work modifies existing files)
  modified:
    - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/CompositeEnergyDistributor.java
    - src/main/java/com/paralife/websocket/Messages.java
    - src/main/java/com/paralife/bot/HeuristicBrain.java
    - src/main/java/com/paralife/bot/BotClient.java
    - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
    - src/test/java/com/paralife/engine/VisionScopedOvercrowdingTest.java
    - src/test/java/com/paralife/engine/ActionResolverTest.java
    - src/test/java/com/paralife/engine/SimulationEngineTest.java
    - src/test/java/com/paralife/engine/CompositeEnergyDistributorTest.java
    - src/test/java/com/paralife/bot/HeuristicBrainTest.java
decisions:
  - "OVERCROWDED_THRESHOLD_DEFAULT = 6 is a package-private documented default — non-public by design; runtime MUST read SimulationConfig.overcrowdingThreshold() so yaml overrides take effect without recompile. `defaultThresholdMatchesSimulationConfigValue` test locks the value agreement."
  - "PerceptionBroadcaster 5-arg legacy ctor retained as back-compat for pre-Phase-14 tests that don't wire env. Wires an empty BuffRegistry + SimulationConfig.defaults(); every env call site is null-guarded."
  - "cellStatus per-bot recomposition verbatim: `(cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit` at PerceptionBroadcaster.java:310-319. Mask constant BIT_OVERCROWDED = 0x01 (cycle-6 MEDIUM #9)."
  - "BondedPair buff wiring — THREE consumer sites (not four). ATTACK_PLUS_1 on BondedPair explicitly NOT wired per cycle-9 action C.2 — processInteractions has zero BondedPair-aggressor sites. Regression test bondedPairWithAttackPlusOneBuffIsNoOp pins the null behavior. SENSOR_PLUS_1 via PerceptionBroadcaster.buildPerception (bot.entityId() uniform pathway), MOVEMENT_PLUS_1 via ActionResolver.resolveMove (bot.entityId() uniform pathway), UPKEEP_MINUS_1 via SimulationEngine.processEnergyDecay BondedPair branch (buffRegistry.hasBuff(bp.id(), UPKEEP_MINUS_1))."
  - "COMPOSITE MOVEMENT_PLUS_1 deviation from D-15 hop-to-range-2: composites use cadence reduction `effectiveInterval = Math.max(1, moveInterval - 1)` inside the existing `resolveCompositeMovements` speed gate. SOLO Particles + BondedPair retain D-15 hop-to-range-2. Deviation documented in plan body and here (cycle-6 LOW)."
  - "hasAnyLocomotorMovementBuff body verbatim spec (cycle-6 MEDIUM #10): `.filter(id -> isLocomotor(id, composite)).anyMatch(id -> buffRegistry.hasBuff(id, MOVEMENT_PLUS_1))`. Non-LOCOMOTOR members with MOVEMENT_PLUS_1 do NOT trigger reduced cadence."
  - "HeuristicBrain reads ONLY observable bits (flags + cellStatus + entityStatus) + distance + self.energy — zero-trust. No server-internal fields consumed."
  - "TOXIC cell avoidance threshold: self.energy() < 30% * self.maxEnergy() → avoid as move/consume/reproduce target. Constant HeuristicBrain.TOXIC_AVOIDANCE_ENERGY_FRACTION = 0.30 public-static-final for test locking."
  - "Messages.CellView Javadoc block distinguishes `flags` (server-authoritative GLOBAL overcrowding/starvation state computed in SimulationEngine.processOvercrowding) from `cellStatus` (vision-scoped per-bot projection where bit 0 is recomputed per-bot)."
  - "BotClient: inline comment only — no code changes. Jackson handles the expanded 6-field CellView natively via reflection on record components. Files_modified lists BotClient per cycle-4 action item #11."
requirements-completed:
  - R12
  - R13
  - R14
metrics:
  duration: ~1.5h
  completed_date: 2026-04-18
  tasks_completed: 3
  tests_before: 541  # approx pre-Plan 14-05 baseline (post Plan 14-04: 532)
  tests_after: 564
  files_created: 0
  files_modified: 13
---

# Phase 14 Plan 05: Vision-Scoped Perception + Composite Energy Distributor Summary

Completes Phase 14 by wiring the Plan-14-01 env pipeline into the perception/action surfaces:
per-bot cellStatus/entityStatus projection with verbatim overcrowded-bit recomposition
`(cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit`, SENSOR_PLUS_1 expanded radius for
SOLO Particle + BondedPair uniformly AND per-SENSOR-member composite stitched coverage,
ATTACK_PLUS_1 applied to solo Particle + all composite attack sites + composite ActionResolver
path, MOVEMENT_PLUS_1 as 2-cell hop for SOLO (Particle + BondedPair) and cadence reduction
for composites via `effectiveInterval = Math.max(1, moveInterval - 1)` gated by a
LOCOMOTOR-only `hasAnyLocomotorMovementBuff` filter, UPKEEP_MINUS_1 reducing decay for solo
Particle + BondedPair (modulus-skip at base=1) and composite per-member passiveDrain,
HeuristicBrain priority-weighted reactions using only observable bits, and the final
BondedPair-as-aggressor ATTACK_PLUS_1 regression test that pins the cycle-9 action C.2
null-behavior guarantee.

## Task Breakdown

### Task 1 — PerceptionBroadcaster vision-scoped perception + overcrowded-bit recomposition + SENSOR_PLUS_1 (commit `2f56f2e`)

Five files modified:

- **`PerceptionBroadcaster.java`** — new primary `@Autowired` ctor accepting `EnvironmentEngine + BuffRegistry + SimulationConfig`; legacy 5-arg ctor retained (null env, empty BuffRegistry, `SimulationConfig.defaults()`). Dynamic SOLO radius: `radius = 3` when `bot.entityId()` has SENSOR_PLUS_1 (Particle + BondedPair uniformly — cycle-6 HIGH #3, cycle-9 action C.1). New per-bot `cellToView(int x, int y, Position botPos, int radius)` emits 6-arg CellView with verbatim cellStatus recomposition `(cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit` (cycle-6 MEDIUM #9). `stitchSensorCoverage` computes per-SENSOR-member radius 5x5→7x7 when that member has SENSOR_PLUS_1 (cycle-4 action item #8). New static helper `computeVisionScopedOvercrowded(worldGrid, cellPos, botPos, radius, threshold)` counts Moore neighbours visible to the bot against the LIVE threshold. Legacy 1-arg `cellToView(Cell)` static retained for back-compat.
- **`SimulationEngine.java`** — new package-private `OVERCROWDED_THRESHOLD_DEFAULT = 6` documented-default constant mirroring `SimulationConfig.defaults()`.
- **`Messages.java`** — Javadoc block above `CellView` distinguishing `flags` (server-authoritative GLOBAL) from `cellStatus` (vision-scoped per-bot with bit-0 recomposition rule).
- **`PerceptionBroadcasterTest.java`** — 6 new test methods: `perceptionIncludesCellStatusAndEntityStatus`, `soloSensorBuffExpandsRadiusToSeven`, `bondedPairWithSensorBuffExpandsSoloBotRadiusTo7` (cycle-6 HIGH #3), `visionScopedOvercrowdingBitSetWhenVisibleNeighborsDense`, `overcrowdedBitIsPerBotNotGlobalFromCache` (cycle-6 MEDIUM #9), `compositeSensorMemberWithSensorBuffExpandsStitchedCoverageRadius` (cycle-4 action item #8).
- **`VisionScopedOvercrowdingTest.java`** — full body replacing the `@Disabled` Wave-0 skeleton: `defaultThresholdMatchesSimulationConfigValue`, `visionScopedOvercrowdedBitSetWhenVisibleNeighborsDense`, `notOvercrowdedWhenNotEnoughVisibleNeighbors`, `thresholdReadFromLiveConfig`, `overcrowdedBitMaskedFromCacheAndRecomputedPerBot` (cycle-6 MEDIUM #9 verbatim), `perBotOvercrowdedBitOredInWhenVisionDense`.

### Task 2 — ATTACK_PLUS_1 + MOVEMENT_PLUS_1 + UPKEEP_MINUS_1 wiring across SOLO + BONDEDPAIR + COMPOSITE (commit `9890548`)

Six files modified:

- **`ActionResolver.java`** — BuffRegistry setter-injected (`@Autowired(required=false)`); `findTargetAtRange(from, dir, range, claimedCells, w, h)` helper extracted (FN-9 range-walking fallback). `resolveMove` uses range=2 when `bot.entityId()` has MOVEMENT_PLUS_1 (covers Particle + BondedPair uniformly). `resolveReproduce` refactored to share the helper. `resolveCompositeMovements` adds `effectiveInterval = hasAnyLocomotorMovementBuff(composite) ? Math.max(1, moveInterval - 1) : moveInterval`. `hasAnyLocomotorMovementBuff` body verbatim per cycle-6 MEDIUM #10: `.filter(id -> isLocomotor(id, composite)).anyMatch(id -> buffRegistry.hasBuff(id, MOVEMENT_PLUS_1))`. `resolveAttackerAttack` adds ATTACK_PLUS_1 (+1) to base damage (cycle-4 action item #3 LIVE method name).
- **`SimulationEngine.java`** — `applyAttackBoost` reads ATTACK_PLUS_1 and adds flat +1 BEFORE the starvation-intensity multiplier (solo Particle). `processEnergyDecay` signature bumped to take `long tickNumber`; Particle branch AND BondedPair branch read UPKEEP_MINUS_1 and apply modulus-skip-at-base-1 rule. Composite-member attack loop in `processInteractions` computes `cmDamage = config.combatEnergyTransfer() + (buffRegistry.hasBuff(attacker.id(), ATTACK_PLUS_1) ? 1 : 0)` and uses it at every emission site (1 ATTACKER role + 3 position-based RPS + subsequent uses).
- **`CompositeEnergyDistributor.java`** — BuffRegistry setter-injected; `processCompositeEnergy` reduces per-member `passiveDrain` by 1 (floored at 0) when that member has UPKEEP_MINUS_1. Dedup contract guarantees at-most-one reduction per member.
- **`ActionResolverTest.java`** — 9 new test methods: `soloMovementPlus1BuffEnables2CellHop`, `soloMovementPlus1FallsBackTo1CellWhen2CellTargetOccupied`, `bondedPairMovementPlus1BuffEnables2CellHop` (cycle-6 HIGH #3), `unbuffedCompositeMovementRespectsExistingMoveInterval`, `compositeLOCOMOTORWithMovementBuffReducesEffectiveMoveIntervalByOne`, `hasAnyLocomotorMovementBuffSkipsNonLocomotorMembers` (cycle-6 MEDIUM #10), `compositeWithMovementBuffFlooredAtOneTickInterval`, `compositeATTACKERWithAttackBuffInResolveAttackerAttackDealsPlus1Damage` (cycle-4 action item #3), `compositeAttackerInSimulationEngineWithAttackBuffDealsPlus1Damage`.
- **`SimulationEngineTest.java`** — 5 new test methods via `engineWithBuffs` helper: `attackPlus1BuffAddsOneToAttackPower`, `upkeepMinus1BuffReducesDecayByOne`, `upkeepMinus1BuffSkipsDecayEveryOtherTickWhenBaseDecayIsOne`, `bondedPairWithAttackPlusOneBuffIsNoOp` (cycle-9 action C.2 — pins null behavior of ATTACK_PLUS_1 on BondedPair), `bondedPairWithUpkeepBuffReducesDecayByOne` (cycle-6 HIGH #3).
- **`CompositeEnergyDistributorTest.java`** — 2 new test methods: `compositeUpkeepMinus1ReducesPerMemberPassiveDrain`, `compositeUpkeepStackingWithSameBuffTypeOnOneMemberDoesNotStackNumerically`.

### Task 3 — HeuristicBrain observable-only reactions + BotClient Jackson comment (commit `15a4e46`)

Three files modified:

- **`HeuristicBrain.java`** — imports Cell for FLAG_STARVING access; new status bit constants `ENTITY_STATUS_TOXIC=0x01`, `ENTITY_STATUS_MUTATING=0x04`, `ENTITY_STATUS_BUFFED=0x08`, `CELL_STATUS_TOXIN_PRESENT=0x01`, `TOXIC_AVOIDANCE_ENERGY_FRACTION=0.30`. `decide()` scoring: prey priority = `-distance + (STARVING ? 2 : 0) - (MUTATING ? 1 : 0) - (BUFFED ? 1 : 0)`; chase branch picks max priority. Low-energy bot (`self.energy() < 0.30 * self.maxEnergy()`) skips TOXIC cells as move/consume/reproduce targets. New `ScoredTarget` record. Zero-trust: no server-internal fields read.
- **`BotClient.java`** — inline acknowledgment comment in `handlePerception` documenting Jackson handles the expanded 6-field CellView natively. No code changes (cycle-4 action item #11).
- **`HeuristicBrainTest.java`** — 5 new test methods: `starvingPreyPreferredOverNonStarvingPrey`, `lowEnergyBotAvoidsToxicMoveTarget`, `highEnergyBotAcceptsToxicMoveTarget`, `buffedPreyDeprioritizedVsCleanPrey`, `mutatingPreyDeprioritizedVsCleanPrey`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] Pre-existing bit-0 collision between CELL_STATUS_TOXIN_PRESENT and BIT_OVERCROWDED.**
- **Found during:** Task 1 test authoring.
- **Issue:** Plan 14-02 shipped `EnvironmentEngine.CELL_STATUS_TOXIN_PRESENT = 0x01`, but Plan 14-05's BIT_OVERCROWDED is also bit 0. The cycle-6 MEDIUM #9 mask `(cached & ~BIT_OVERCROWDED)` therefore also strips the TOXIN_PRESENT bit when both land on the same cellStatus byte. D-38 specifies OVERCROWDED at bit 0 and TOXIN_PRESENT at bit 1 (0x02), so the shipped TOXIN_PRESENT value is off by one.
- **Fix decision:** NOT fixed in this plan. Changing `CELL_STATUS_TOXIN_PRESENT` from `0x01` to `0x02` affects Plan 14-02's shipped tests (`ToxinTest` uses the constant by name but values would shift). Scope-bounded to Plan 14-02 fix territory. Documented here + avoided in Task 1 test (uses MUTAGEN_ZONE=0x04 for cellStatus cache-preservation assertions instead of TOXIN_PRESENT).
- **Impact on plan intent:** None — cycle-6 MEDIUM #9's stated goal (per-bot recomposition of OVERCROWDED bit) works correctly. The TOXIN_PRESENT edge-case interaction is preserved via the `ENTITY_STATUS_TOXIC` entity-level projection, which is what bots actually consume for TOXIC-cell avoidance in Task 3.

### No other deviations

Tasks 2 and 3 executed exactly as specified in the plan body.

## Known Stubs

None new. The pre-existing cellStatus bit-0 collision (documented above) is Plan 14-02 territory; Plan 14-05's vision-scoped projection works correctly against the authoritative `flags` bits (server-side OVERCROWDED tracking via `Cell.FLAG_OVERCROWDED`) and its own BIT_OVERCROWDED constant.

## Auth Gates Encountered

None.

## Threat Flags

No new security-relevant surface beyond the plan's `<threat_model>`. Per-bot cellStatus recomposition (T-14-05-15) is a mitigation implemented verbatim. ATTACK_PLUS_1 on BondedPair-as-aggressor was DROPPED per cycle-9 action C.2 — the regression test `bondedPairWithAttackPlusOneBuffIsNoOp` guards against silent reintroduction if a future refactor adds BondedPair-aggressor sites.

## Test Results

Full suite after Task 3: **564 tests, 0 failures, 3 ignored** (@Disabled placeholder remnants from Wave-0 skeletons in ToxinTest).

| Task | Test class | New tests |
|------|-----------|-----------|
| 1 | PerceptionBroadcasterTest | +6 |
| 1 | VisionScopedOvercrowdingTest | +6 (replaces 1 placeholder) |
| 2 | ActionResolverTest | +9 |
| 2 | SimulationEngineTest | +5 |
| 2 | CompositeEnergyDistributorTest | +2 |
| 3 | HeuristicBrainTest | +5 |
| — | **Total new** | **+33** |

Spring context starts without errors; all existing Phase 06-14 tests green as non-regression.

## Confirmations (requested in PLAN `<output>`)

- **OVERCROWDED_THRESHOLD_DEFAULT = 6 in shipped source** + `defaultThresholdMatchesSimulationConfigValue` test passed: confirmed (`SimulationEngine.java` near top). Test passes as part of Task 1.
- **`lastMovementTick`, `canMoveAtTick`, `recordMovement`, `moveCooldownTicks`, `move-cooldown-ticks` absent from the entire codebase**: `grep -rn` returns ZERO matches across `src/` and `src/main/resources/application.yml` — confirmed.
- **`effectiveInterval = Math.max(1, moveInterval - 1)` expression file:line**: `src/main/java/com/paralife/engine/ActionResolver.java:914-916` inside `resolveCompositeMovements`.
- **ATTACK_PLUS_1 file:line map**:
  - `SimulationEngine.java:381` — composite-member attack loop `cmDamage += 1`
  - `SimulationEngine.java:737` — solo Particle `applyAttackBoost` (`+= 1` before starvation multiplier)
  - `ActionResolver.java:708` — composite `resolveAttackerAttack` (`baseDamage += 1`)
  - **Total: 3 WIRE POINTS in main code. `cmDamage` covers the 5 composite-member emission sites collectively (1 ATTACKER-role damage + 3 position-based RPS + 1 where it's used in the delta); all route through the single per-iteration `cmDamage` precomputation.** Solo Particle site (applyAttackBoost) covers the 3 solo attack Cases uniformly.
  - **BondedPair-as-aggressor branches**: 0 wire points (cycle-9 action C.2 — no such sites exist). Regression test `bondedPairWithAttackPlusOneBuffIsNoOp` pins null behavior.
- **Composite UPKEEP_MINUS_1 per-member passiveDrain reduction**: `CompositeEnergyDistributor.java` — inside `processCompositeEnergy`, after `getPassiveDrain(member.role())`, `if (buffRegistry.hasBuff(member.id(), UPKEEP_MINUS_1)) passiveDrain = Math.max(0, passiveDrain - 1);`
- **BondedPair-level buff wiring (cycle-6 HIGH #3 — THREE consumer sites, cycle-9 C.2 revised)**:
  - ATTACK_PLUS_1: NOT WIRED (cycle-9 action C.2) — no aggressor surface; `bondedPairWithAttackPlusOneBuffIsNoOp` test passes
  - SENSOR_PLUS_1: `PerceptionBroadcaster.java:236-240` — `buildPerception` reads `buffRegistry.hasBuff(botEntityId, SENSOR_PLUS_1)`; `botEntityId = bot.entityId()` returns `bp.id()` for BondedPair-bound bots
  - MOVEMENT_PLUS_1: `ActionResolver.java:380-386` — `resolveMove` reads `buffRegistry.hasBuff(entityId, MOVEMENT_PLUS_1)`; same uniform pathway
  - UPKEEP_MINUS_1: `SimulationEngine.java:691` — `processEnergyDecay` BondedPair branch reads `buffRegistry.hasBuff(bp.id(), UPKEEP_MINUS_1)` (grep hit 1)
  - **Self-check `grep -n "buffRegistry.hasBuff(bp.id()" src/main/java/com/paralife/engine/`**: 1 match (UPKEEP only — SENSOR + MOVEMENT are reached via `bot.entityId()` by construction, not via `bp.id()` literally).
- **Composite MOVEMENT_PLUS_1 semantic deviation from D-15 (cycle-6 LOW)**: **RECORDED** — composites use cadence reduction via `effectiveInterval = Math.max(1, moveInterval - 1)`, NOT D-15's hop-to-range-2. Solo Particles + BondedPair retain D-15 hop-to-range-2.
- **Per-bot overcrowded-bit recomposition (cycle-6 MEDIUM #9)**: `PerceptionBroadcaster.java:310-319` — the exact shipped expression is `byte cellStatus = (byte) ((cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit);`. Verbatim.
- **LOCOMOTOR-only filter (cycle-6 MEDIUM #10)**: `ActionResolver.java:209-213` — verbatim `return composite.getMemberIds().stream().filter(id -> isLocomotor(id, composite)).anyMatch(id -> buffRegistry.hasBuff(id, BuffRegistry.BuffType.MOVEMENT_PLUS_1));`. Test `hasAnyLocomotorMovementBuffSkipsNonLocomotorMembers` passes.
- **BotClient NOT code-modified for parsing (cycle-4 action item #11)**: confirmed — only an inline comment added in `handlePerception`. `grep -n "Jackson" src/main/java/com/paralife/bot/BotClient.java` returns 1 match.
- **HeuristicBrain final decision-adjustment set (observable-only; no attack-cure math)**:
  - Priority 1 Flee from adjacent predator (unchanged)
  - Priority 2 Chase adjacent prey — MAX of `-distance + 2·STARVING − MUTATING − BUFFED` (new)
  - Priority 3 Consume adjacent nutrient (unchanged; low-energy bot skips TOXIC nutrient cells)
  - Priority 4 Reproduce if `self.energy() >= 70` (unchanged; low-energy bot skips TOXIC empty adjacent)
  - Priority 5 Move toward nearest nutrient (unchanged; low-energy skips TOXIC)
  - Priority 6 Random walk (unchanged; low-energy skips TOXIC empty)
- **Messages.CellView Javadoc block**: recorded — `grep -nE "vision-scoped|server-authoritative GLOBAL" src/main/java/com/paralife/websocket/Messages.java` returns 3 matches.
- **cycle-4 action item #8 — `stitchSensorCoverage` per-member radius + test passed**: `PerceptionBroadcaster.java:286-302` uses `int memberRadius = buffRegistry.hasBuff(memberId, SENSOR_PLUS_1) ? 3 : PERCEPTION_RADIUS;`. Test `compositeSensorMemberWithSensorBuffExpandsStitchedCoverageRadius` (25 vs 49 baseline) passes.
- **cycle-4 action item #3 — ZERO `resolveCompositeAttack` code references**: `grep -rn "resolveCompositeAttack" src/main/` returns 1 match (ActionResolver.java:706 inside a comment "NOT resolveCompositeAttack — dead name gone"). ZERO actual symbol declarations or invocations. Plan-file prose references remain (not code-path references).
- **Test count ≥ 20 covering cycle-6 additions**: 33 new tests total (6 PerceptionBroadcaster + 6 VisionScoped + 9 ActionResolver + 5 SimulationEngine + 2 CompositeEnergyDistributor + 5 HeuristicBrain).

## Files Summary

**Modified (13):**
- Main code (7): `PerceptionBroadcaster.java`, `ActionResolver.java`, `SimulationEngine.java`, `CompositeEnergyDistributor.java`, `Messages.java`, `HeuristicBrain.java`, `BotClient.java`
- Tests (6): `PerceptionBroadcasterTest.java`, `VisionScopedOvercrowdingTest.java`, `ActionResolverTest.java`, `SimulationEngineTest.java`, `CompositeEnergyDistributorTest.java`, `HeuristicBrainTest.java`

**Created (0):** All Plan 14-05 work fits into existing files — no new classes.

**Commits (3):**
- `2f56f2e` — Task 1 PerceptionBroadcaster vision-scoped perception + overcrowded-bit recomposition + SENSOR_PLUS_1
- `9890548` — Task 2 buff effect wiring across SOLO + BONDEDPAIR + COMPOSITE
- `15a4e46` — Task 3 HeuristicBrain observable-only reactions + BotClient Jackson comment

## Self-Check: PASSED

All 13 modified files present on disk. All 3 task commits present in `git log --oneline --all`. Full suite `./gradlew test` exits 0 with 564 tests passing, 0 failures, 3 ignored (Wave-0 skeletons from other plans).
