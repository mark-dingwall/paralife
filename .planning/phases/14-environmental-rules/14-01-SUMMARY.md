---
phase: 14
plan: 01
subsystem: environmental-rules
tags:
  - scaffolding
  - bean-cycle-resolution
  - same-tick-death-model
  - configuration-properties
  - shadow-registry
  - cell-view-projection
dependency_graph:
  requires:
    - SimulationEngine (pre-existing, refactored)
    - SeasonTracker (Phase 13)
    - FertilityConfig (Phase 13)
    - BotRegistry / CompositeRegistry (Phase 11/12)
    - MetabolicProfile / StarvationConfig (Phase 13)
    - Messages.CellView 4-field shape (Phase 13 Plan 02)
  provides:
    - EnvironmentConfig (paralife.simulation.events.*)
    - BuffRegistry (@Component shadow registry)
    - DeathCleanupHooks (interface seam)
    - EnvCleanupHooksBean (@Component impl + canonical env-state owner)
    - DeathFinalizer (@Component — single cleanup path for all deaths)
    - EnvironmentEngine (@Order(14) — tick pipeline slot between SimulationEngine and CompositeEnergyDistributor)
    - EnvPostActionReconciler (@Order(25) — composite attack-path same-tick sweep)
    - Messages.CellView 6-field with cellStatus + entityStatus byte projection
    - paralife.simulation.events.* YAML defaults
  affects:
    - SimulationEngine (constructor signature, handleMemberDeath visibility, Phase 3a delegation, panic-zone pool=0 cleanup)
    - Messages.CellView (3-arg + 4-arg back-compat constructors preserved)
tech-stack:
  added:
    - jakarta.annotation.PostConstruct (for CompostSink registration)
    - org.springframework.context.ApplicationListener<ContextRefreshedEvent> (fail-fast)
  patterns:
    - third-bean DI rework to break construction cycle
    - @Lazy back-edge for SimulationEngine ↔ DeathFinalizer composite delegation
    - setter-injection of a narrow CompostSink collaborator post-construction
    - nullable Long config field for production-unseeded / test-seeded RNG
    - shadow-registry (ConcurrentHashMap + CopyOnWriteArrayList) for buff lifecycle
    - short-circuit flag for end-of-tick death sweep
key-files:
  created:
    - src/main/java/com/paralife/engine/DeathCleanupHooks.java
    - src/main/java/com/paralife/engine/EnvCleanupHooksBean.java
    - src/main/java/com/paralife/engine/EnvironmentConfig.java
    - src/main/java/com/paralife/engine/BuffRegistry.java
    - src/main/java/com/paralife/engine/DeathFinalizer.java
    - src/main/java/com/paralife/engine/EnvironmentEngine.java
    - src/main/java/com/paralife/engine/EnvPostActionReconciler.java
    - src/test/java/com/paralife/engine/BuffRegistryTest.java
    - src/test/java/com/paralife/engine/DeathFinalizerTest.java
    - src/test/java/com/paralife/engine/EnvironmentEngineTest.java
    - src/test/java/com/paralife/engine/CompostTest.java
    - src/test/java/com/paralife/engine/EnvDeathSweepTest.java
    - src/test/java/com/paralife/engine/EnvDeathSweepTest_GracefulDegradation.java
    - src/test/java/com/paralife/engine/EnvDeathSweepTest_Shatter.java
    - src/test/java/com/paralife/engine/EnvPostActionReconcilerTest.java
    - src/test/java/com/paralife/engine/CompostSinkFailFastTest.java
    - src/test/java/com/paralife/engine/ToxinTest.java (@Disabled skeleton)
    - src/test/java/com/paralife/engine/MutagenTest.java (@Disabled skeleton)
    - src/test/java/com/paralife/engine/LightningTest.java (@Disabled skeleton)
    - src/test/java/com/paralife/engine/SeasonalPoissonTest.java (@Disabled skeleton)
    - src/test/java/com/paralife/engine/VisionScopedOvercrowdingTest.java (@Disabled skeleton)
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/websocket/Messages.java
    - src/main/resources/application.yml
decisions:
  - "Bean-cycle break: third bean (EnvCleanupHooksBean) is the DeathCleanupHooks impl + canonical owner of infection/cure-immune/pending-grant maps — neither EnvironmentEngine nor DeathFinalizer depends on the other at construction (cycle-4 action item #1)."
  - "Composite-member env deaths delegate to SimulationEngine.handleMemberDeath via @Lazy so the same-tick 97/3 graceful-degradation-vs-shatter roll fires identically whether the death came from combat or environmental damage."
  - "Shared per-member cleanup (bot/buff/infection/compost/clearEntity) centralised in DeathFinalizer for solo/bonded flows AND in the SimulationEngine helper cleanupCompositeMemberCellViaFinalizer for composite flows, so Pitfall 4/5 (every clearEntity site must compost) is impossible to violate."
  - "finalizeBondedPairDeath calls hooks.clearInfectionOnDeath for primary id, secondary id, AND bp.id() — BondedPair infections are keyed by bp.id() in Plan 14-03 (cycle-4 action item #6, Gemini MEDIUM)."
  - "EnvPostActionReconciler @Order(25) runs between ActionResolver(@Order 20) and PerceptionBroadcaster(@Order 50) so composite-attack-path splash deaths and post-action buff grants finalise same-tick (cycle-4 action item #2)."
  - "EnvironmentConfig.seed is a nullable Long. Production yaml omits the key; tests bind via @TestPropertySource paralife.simulation.events.seed=42. EnvironmentEngine's production ctor branches on config.seed() == null (cycle-6 HIGH #1)."
  - "EnvCleanupHooksBean implements ApplicationListener<ContextRefreshedEvent> and throws IllegalStateException if compostSink is still null after context refresh (cycle-9 action A). Defensive runtime null-check in applyCompost preserved for test profiles that deliberately ship no sink."
  - "R13 flag-projection design deviation: env effects appear as byte bits on CellView.cellStatus / entityStatus (not as new Cell.flags constants) because intensity-valued effects like 0-255 toxin cannot fit in a single bit. Documented as authoritative design choice in PLAN <deviations> — not a requirement rewrite."
metrics:
  duration: ~2.3h
  completed_date: 2026-04-17
  tasks_completed: 5
  tests_before: 444 (approximate baseline from @SpringBootTest count + existing plan infrastructure)
  tests_after: 457 (+13 new Phase 14 tests)
  files_created: 21
  files_modified: 3
---

# Phase 14 Plan 01: Environmental-Rules Scaffolding Summary

Scaffolds the six-file DI graph, config surface, and same-tick death contract that plans 02/03/04/05 fill with toxin/mutagen/lightning/perception bodies, using a third-bean cycle break (EnvCleanupHooksBean) plus @Lazy SimulationEngine↔DeathFinalizer delegation so env-killed composite members run the existing 97/3 dissolution roll same tick.

## Self-Check: see end of this document

## Task Breakdown

### Task 1 — EnvironmentConfig + BuffRegistry + DeathCleanupHooks interface + EnvCleanupHooksBean third bean + Messages.CellView extension + application.yml defaults (commit `e01c95f`)

Seven files created / modified:

- `DeathCleanupHooks.java` — two-method interface (`clearInfectionOnDeath`, `applyCompost`). Plan 14-03 Task 2 Step 1 extends with `transferMutagenState` per cycle-6 HIGH #5c.
- `EnvCleanupHooksBean.java` — `@Component` implementing `DeathCleanupHooks` + `ApplicationListener<ContextRefreshedEvent>`. Owns canonical `infections` / `cureImmuneUntil` / `pendingBuffGrants` maps (populated in Plan 03). `CompostSink` interface + setter-injection to avoid construction cycle. Fail-fast listener throws `IllegalStateException("CompostSink was never registered — EnvironmentEngine @PostConstruct ordering regressed")` if sink is still null after context refresh (cycle-9 action A). Defensive `log.debug` in `applyCompost` preserved for test profiles that deliberately don't register a sink.
- `EnvironmentConfig.java` — `@ConfigurationProperties(prefix = "paralife.simulation.events")` record tree. **`Long seed` nullable field (cycle-6 HIGH #1)** declared in the canonical constructor, `defaults()` returns null, production yaml omits the key. Nested `Lightning` / `Toxin` / `Toxin.Resistance` / `Mutagen` / `Compost` records with compact-constructor validation. `Mutagen.zoneDecayTicks` pre-wired (default 50).
- `BuffRegistry.java` — `@Component` with `BuffType` enum (ATTACK_PLUS_1, MOVEMENT_PLUS_1, SENSOR_PLUS_1, UPKEEP_MINUS_1), `ActiveBuff` record, `ConcurrentHashMap<String, CopyOnWriteArrayList<ActiveBuff>>`. `grant()` dedups same `BuffType` per entity using `Math.max(existing.expiryTick(), newExpiryTick)` so shorter re-grants cannot shrink active longer buffs. Full API: `grant / getBuffs / hasBuff / expireBuffs / unregisterEntity / clear / size`.
- `Messages.CellView` — extended from 4-field to 6-field with `byte cellStatus` + `byte entityStatus` (D-38 / D-39). **3-arg and 4-arg back-compat constructors preserved** so Phase 13 callers and tests compile unchanged.
- `application.yml` — appended `paralife.simulation.events:` section with all D-30 / D-48 defaults. `seed:` key deliberately omitted so production runs unseeded.
- `BuffRegistryTest.java` — 9 JUnit 5 unit tests (plain, not Spring): `grantAddsBuffToEmptyEntity`, `getBuffsReturnsEmptyListForUnknownEntity`, `hasBuffReturnsTrueAfterGrant`, `expireBuffsRemovesAtOrBeforeExpiryTick`, `expireBuffsDropsEntityWhenAllBuffsExpire`, `unregisterEntityRemovesAllBuffs`, `clearRemovesAllEntities`, `grantDedupsSameBuffType`, `grantDedupPreservesLaterExpiry`.

### Task 2 — DeathFinalizer @Component + SimulationEngine refactor (commit `bc2fd0e`)

Three files modified / created:

- `DeathFinalizer.java` — `@Component` centralising the 5-step shared cleanup for every entity death. Injects `WorldGrid`, `BotRegistry`, `BuffRegistry`, `CompositeRegistry`, `DeathCleanupHooks` (wired to `EnvCleanupHooksBean` — the interface dependency is the compile-time seam), and `@Lazy SimulationEngine`. **Zero imports of EnvironmentEngine** — cycle-4 action item #1.
  - `finalizeParticleDeath` — 5-step shared cleanup then `worldGrid.clearEntity`.
  - `finalizeBondedPairDeath` — shared cleanup for both member ids; `hooks.clearInfectionOnDeath` called for primary, secondary, AND `bp.id()` (cycle-4 action item #6).
  - `finalizeCompositeMemberDeath` (with-set overload + convenience overload) — delegates entirely to `simulationEngine.handleMemberDeath(...)`. No 97/3 roll logic in DeathFinalizer — preserves existing combat behavior.
- `SimulationEngine.java`
  - Constructor: added `BuffRegistry buffRegistry, DeathCleanupHooks hooks, @Lazy DeathFinalizer deathFinalizer`. `@Autowired` applied to the 12-arg primary constructor so Spring picks it over the 9-arg back-compat overload.
  - 9-arg back-compat constructor: retained for existing unit tests — wires a fresh `BuffRegistry`, a no-op `DeathCleanupHooks` anonymous impl, and a fresh `DeathFinalizer` wired back to `this` (no Spring lifecycle — direct reference).
  - New package-private helper `cleanupCompositeMemberCellViaFinalizer(CompositeMember cm, Position pos)` performs the 5-step shared cleanup without removing the member from the registry (registry removal stays in `handleMemberDeath` so the decision tree can read count before removal).
  - `handleMemberDeath` — visibility widened to `public` so `DeathFinalizer` can call back. FIRST STEP now calls `cleanupCompositeMemberCellViaFinalizer`; the existing memberCount 0/1/else checks and 97/3 roll are preserved inline unchanged.
  - Phase 3a solo-death loop — delegates to `deathFinalizer.finalizeParticleDeath` / `finalizeBondedPairDeath`.
  - Panic-zone pool=0 loop — per-member cleanup routes through `cleanupCompositeMemberCellViaFinalizer`; the `compositeRegistry.dissolve(...)` call stays inline after the per-member loop.
  - `revertToBondedPair` and `dissolveToParticles` untouched (Plan 14-03 Task 3 will add state-transition migration there per cycle-6 HIGH #2).
- `DeathFinalizerTest.java` — **Mockito-only unit test, NOT `@SpringBootTest`** (cycle-6 LOW clarification). 4 cases:
  - `particleDeathInvokesAllCleanupSteps` — InOrder verification of the 5-step recipe.
  - `bondedPairDeathCleansBothMemberIdsAndBpId` — asserts all three `clearInfectionOnDeath` calls (primary, secondary, bp.id()).
  - `compositeMemberDeathDelegatesToSimulationEngineHandleMemberDeath` — captures the delegated `(cm, pos, processedComposites)` triple.
  - `compositeMemberConvenienceOverloadCreatesFreshProcessedSet` — asserts the no-set overload passes a fresh empty HashSet.

### Task 3 — EnvironmentEngine skeleton + Wave-0 env test skeletons + EnvDeathSweepTest sibling classes + seed regression test (commit `8b4adf2`)

Twelve files created (+ SimulationEngine @Autowired touch):

- `EnvironmentEngine.java` — `@Component` at `@Order(14)` (one slot before `CompositeEnergyDistributor`'s `@Order(15)` — the PATTERNS/cycle-9 design_note choice). Implements `EnvCleanupHooksBean.CompostSink` (NOT `DeathCleanupHooks` — cycle-4 action item #1). Registers itself as the sink in `@PostConstruct`. Production ctor reads `config.seed()` and picks `new Random()` or `new Random(seed)`; package-private test ctor accepts an explicit `Random`. `@Autowired` on the production ctor so Spring disambiguates.
  - `onTick` wrapped in try/catch (Pitfall 9); status caches rebuilt every tick (Pitfall 7). Calls `buffRegistry.expireBuffs(tick)` every tick. Final step is `processEnvDeaths()` which is short-circuited by `envDamageAppliedThisTick`.
  - `applyCompost(Position)` — full-strength on death cell, half-strength on 8 Moore neighbors, clamped to `fertilityConfig.maxLevel()`.
  - `markEnvDamageApplied()` package-private; called by damage sites in plans 02/03/04.
  - `processEnvDeaths()` — public (for EnvPostActionReconciler). Full-grid scan for zero-energy occupants; routes Particle / BondedPair / CompositeMember through DeathFinalizer.
  - `drainPostActionGrants()` — no-arg stub; Plan 14-03 Task 2 gains a `long tickNumber` arg.
  - Test helpers (package-private): `markEnvDamageAppliedForTest`, `processEnvDeathsForTest`, `killParticleAtForTest`, `killCompositeMemberAtForTest`, `cellStatusCacheView`, `entityStatusCacheView`, `envDamageAppliedThisTickForTest`.
  - Public accessors: `getCellStatus(Position)`, `getEntityStatus(String)` — PerceptionBroadcaster will use these in Plan 05.
- `EnvironmentEngineTest.java` — Spring integration test with class-level `@TestPropertySource(paralife.simulation.events.seed=42)`. Cases: `seedFieldBindsFromTestPropertySource` (cycle-6 HIGH #1 regression — `config.seed()` returns `42L`), `onTickNoOpWhenDisabled`, `processEnvDeathsShortCircuitsWhenNoDamageApplied`, `getCellStatusReturnsZeroForUnknownPosition`.
- `CompostTest.java` — Spring integration: `applyCompostBumpsCenterAndNeighbors` (full 30 at center, half 15 at each of 8 neighbors), `applyCompostClampsAtFertilityMaxLevel`.
- `EnvDeathSweepTest.java` — Particle scenario. Asserts env-damaged zero-energy particle is swept from grid AND unregistered from BotRegistry in the same call to `processEnvDeaths`.
- `EnvDeathSweepTest_GracefulDegradation.java` — 3-member composite, `@TestPropertySource(paralife.composite.dissolution-chance=0.0)`. One member env-killed → composite survives with 2 members same tick.
- `EnvDeathSweepTest_Shatter.java` — 3-member composite, `@TestPropertySource(paralife.composite.dissolution-chance=1.0)`. One member env-killed → survivors become Particles same tick.
- `ToxinTest.java` / `MutagenTest.java` / `LightningTest.java` / `SeasonalPoissonTest.java` / `VisionScopedOvercrowdingTest.java` — `@Disabled` skeletons with placeholder methods; plans 02/03/04/05 fill them.

### Task 4 — EnvPostActionReconciler @Order(25) + test (commit `e57d883`)

Two files created:

- `EnvPostActionReconciler.java` — `@Component` at `@Order(25)`. Injects `EnvironmentEngine`. `onTick` calls `processEnvDeaths()` then `drainPostActionGrants()`, wrapped in try/catch. `TICK_ORDER = 25` constant exposed for the test. Plan 14-03 Task 2 Step 4 updates onTick body to pass `event.tickNumber()` to `drainPostActionGrants(long)`.
- `EnvPostActionReconcilerTest.java` — Mockito unit test. `tickOrderIs25BetweenActionResolverAndPerceptionBroadcaster` reflects the `@Order` annotation off `onTick`; `onTickCallsProcessEnvDeathsThenDrainPostActionGrants` uses `InOrder` to verify the call sequence.

### Task 5 — CompostSinkFailFastTest (commit `dfb6f0c`)

One file created:

- `CompostSinkFailFastTest.java` — Directly instantiates `EnvCleanupHooksBean`, fires a synthetic `ContextRefreshedEvent` with a mocked ApplicationContext, asserts `IllegalStateException` with message containing `"CompostSink was never registered"`. Guards against future refactors that silently drop the `ApplicationListener` contract. Simpler than bootstrapping a minimal Spring context that would have to exclude `EnvironmentEngine` from the `com.paralife` package scan.

## Deviations from Plan

None — plan executed exactly as written.

Two small adaptations documented as auto-fixes (Rule 2 — missing critical functionality):

- **[Rule 2 — missing critical functionality] `@Autowired` on primary constructors.** The plan did not explicitly call out that adding a second constructor to `SimulationEngine` (9-arg back-compat) or `EnvironmentEngine` (package-private test ctor) causes Spring to fail context init with `NoSuchMethodException: <init>()` because Spring cannot disambiguate multiple candidate constructors without a primary hint. Fixed inline by adding `@Autowired` to the 12-arg `SimulationEngine` and 7-arg `EnvironmentEngine` production constructors. No plan deviation — this is the standard Spring primary-constructor idiom.
- **[Rule 2 — missing critical functionality] 9-arg back-compat constructor on `SimulationEngine`.** Seven existing unit test call sites instantiate `SimulationEngine` directly without Spring (CompositeFormationTest, CompositeCombatTest, CompositeDissolutionTest, and four in SimulationEngineTest). Updating each of those to pass the three new Phase 14 collaborators would have touched many files for no behavioral gain. Added a 9-arg back-compat constructor that wires no-op defaults (fresh BuffRegistry, anonymous no-op DeathCleanupHooks, fresh DeathFinalizer wired back to `this`). Documented in-code as a test-only shim — production code uses the 12-arg Spring constructor.

## R13 Design Deviation (cycle-6 MEDIUM #8) — documented

REQUIREMENTS.md R13 reads "Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern)." Phase 14 delivers environmental effects via **shadow grids + status-byte projection** (future `byte[][] toxinGrid` + `byte[][] mutagenGrid` in plans 02/03 with `cellStatus` + `entityStatus` bytes on `Messages.CellView`), NOT via `Cell.flags` directly.

Rationale (authoritative design decision, documented in PLAN `<deviations>`):

1. Intensity-valued effects (toxin 0-255, mutagen strain byte) cannot be represented by single `Cell.flags` bits.
2. Cache locality + additive evolution (D-31 / D-33 locked in discuss phase) favour parallel per-effect shadow grids.
3. Composite SENSOR stitched coverage (D-40) benefits from a per-bot projection surface that `Cell.flags` cannot emulate.
4. Extensibility — new env effects become new bits in `cellStatus` / `entityStatus` (6 bits each, 3 reserved) without expanding `Cell.flags`.

`Cell.flags` retains `FLAG_OVERCROWDED` + `FLAG_STARVING` unchanged. The SPIRIT of R13 — extensible bit-based environmental state visible to perception — is delivered via a new projection surface rather than by extending `Cell.flags`. See 14-VALIDATION.md (to be updated by verifier) for the full argument.

## Auth Gates Encountered

None.

## Known Stubs

- `EnvironmentEngine.drainPostActionGrants()` — empty no-op body. Plan 14-03 Task 2 fills the body and extends the signature with `long tickNumber` per cycle-6 HIGH #5a. Not a stub blocking Plan 14-01's goal — this method is called ONLY by `EnvPostActionReconciler`, which Plan 14-03 rewires simultaneously.
- `EnvironmentEngine.onTick` body is `cellStatusCache.clear(); entityStatusCache.clear(); buffRegistry.expireBuffs(tick); if (!enabled) return; [TODO plans 02/03/04]; processEnvDeaths();`. The TODO placeholder is explicit — plans 02/03/04 fill Poisson rolls, event spawn/advance, status cache population.
- `EnvCleanupHooksBean.infections` / `cureImmuneUntil` / `pendingBuffGrants` — declared but empty in Plan 01. Populated by EnvironmentEngine in Plan 03 Task 2.

All of these are intentional Plan 01 scaffolding — documented in the plan's frontmatter `artifacts.provides` fields.

## Threat Flags

No new security-relevant surface introduced by this plan beyond what the PLAN's `<threat_model>` already enumerated (T-14-01 through T-14-18). Compost writes are internal grid mutations through the existing `WorldGrid` seam. The `seed` field is ops-controlled config only, never serialised to WebSocket clients (T-14-02).

## Test Results

Full suite after Task 5: **457 tests, all passing** (`./gradlew test` BUILD SUCCESSFUL in 1m 45s).

Task-specific runs:

| Task | Test class | Result |
|------|-----------|--------|
| 1 | BuffRegistryTest | 9/9 pass |
| 1 | PerceptionBroadcasterTest | unchanged/pass (back-compat) |
| 2 | DeathFinalizerTest | 4/4 pass |
| 2 | SimulationEngineTest | unchanged/pass (non-regression) |
| 3 | EnvironmentEngineTest | 4/4 pass (incl. seedFieldBindsFromTestPropertySource) |
| 3 | CompostTest | 2/2 pass |
| 3 | EnvDeathSweepTest | 1/1 pass |
| 3 | EnvDeathSweepTest_GracefulDegradation | 1/1 pass |
| 3 | EnvDeathSweepTest_Shatter | 1/1 pass |
| 4 | EnvPostActionReconcilerTest | 2/2 pass |
| 5 | CompostSinkFailFastTest | 1/1 pass |

Spring bootRun confirms no context failures: `Started ParalifeApplication in 2.15 seconds`. No ERROR / Exception lines during init.

## Verification Checklist (from PLAN)

- [x] `./gradlew test` exits 0 — BUILD SUCCESSFUL, 457 tests pass
- [x] `./gradlew bootRun` starts without Spring errors
- [x] DeathFinalizerTest: 4 Mockito unit-scope cases (cycle-6 LOW clarification — NOT @SpringBootTest)
- [x] EnvDeathSweepTest + sibling classes: all three composite branches green
- [x] EnvPostActionReconcilerTest: @Order(25) + invocation order locked
- [x] SimulationEngineTest remains green
- [x] BuffRegistryTest passes 9 tests including dedup
- [x] cycle-6 HIGH #1 — `Long seed` field, defaults null, production yaml omits, `seedFieldBindsFromTestPropertySource` passes
- [x] cycle-6 MEDIUM #8 — R13 flag-projection rationale recorded in `<deviations>` + this SUMMARY.md
- [x] cycle-6 LOW — DeathFinalizerTest has NO `@SpringBootTest` annotation or import (only in javadoc prose)
- [x] cycle-9 action A — CompostSinkFailFastTest locks the fail-fast contract

## Confirmations (requested in PLAN `<output>`)

- **EnvCleanupHooksBean is the DeathCleanupHooks impl**: `grep -n "implements DeathCleanupHooks" src/main/java/com/paralife/engine/EnvCleanupHooksBean.java` → line 43 (confirmed). `grep -n "import com.paralife.engine.EnvironmentEngine" src/main/java/com/paralife/engine/DeathFinalizer.java` → zero matches (confirmed).
- **SimulationEngine.handleMemberDeath is public and begins with cleanupCompositeMemberCellViaFinalizer**: `grep -n "public void handleMemberDeath" src/main/java/com/paralife/engine/SimulationEngine.java` → line 734 (confirmed). Body begins with `cleanupCompositeMemberCellViaFinalizer(deadMember, deadPos)` at line 740 (early-return path) and line 747 (normal path).
- **finalizeBondedPairDeath clears infection on all three ids**: `grep -n "hooks.clearInfectionOnDeath" src/main/java/com/paralife/engine/DeathFinalizer.java` → 3 matches (primaryId, secondaryId, bp.id()).
- **EnvPostActionReconciler @Order(25)**: `grep -n "public static final int TICK_ORDER = 25" src/main/java/com/paralife/engine/EnvPostActionReconciler.java` → line 31 (confirmed). `tickOrderIs25BetweenActionResolverAndPerceptionBroadcaster` test passes.
- **Sibling EnvDeathSweepTest classes with class-level @TestPropertySource**: `paralife.composite.dissolution-chance=0.0` in GracefulDegradation; `=1.0` in Shatter. Class-level only (1 `@TestPropertySource` occurrence per file).
- **No tests under src/test/java/com/paralife/engine/environment/**: directory does not exist (cycle-4 action item #10).
- **EnvironmentConfig has `Long seed` field (cycle-6 HIGH #1)**: `grep -nE "^\s+Long seed\b"` → line 22. `seedFieldBindsFromTestPropertySource` test passes. `application.yml` has ZERO `seed:` matches.
- **DeathFinalizerTest NO @SpringBootTest import (cycle-6 LOW)**: zero matches for `@SpringBootTest` in the file (only prose in javadoc).
- **R13 flag-projection rationale (cycle-6 MEDIUM #8)**: recorded in PLAN `<deviations>` and in this SUMMARY.md's "R13 Design Deviation" section.
- **Spring startup**: `Started ParalifeApplication in 2.15 seconds` — no context errors.

## Files Summary

**Created (21):**
- Main: `DeathCleanupHooks.java`, `EnvCleanupHooksBean.java`, `EnvironmentConfig.java`, `BuffRegistry.java`, `DeathFinalizer.java`, `EnvironmentEngine.java`, `EnvPostActionReconciler.java` (7 classes)
- Test: `BuffRegistryTest`, `DeathFinalizerTest`, `EnvironmentEngineTest`, `CompostTest`, `EnvDeathSweepTest`, `EnvDeathSweepTest_GracefulDegradation`, `EnvDeathSweepTest_Shatter`, `EnvPostActionReconcilerTest`, `CompostSinkFailFastTest`, `ToxinTest`, `MutagenTest`, `LightningTest`, `SeasonalPoissonTest`, `VisionScopedOvercrowdingTest` (14 classes)

**Modified (3):**
- `SimulationEngine.java` — constructor signature, `@Autowired`, handleMemberDeath visibility, Phase 3a delegation, panic-zone pool=0 cleanup, new `cleanupCompositeMemberCellViaFinalizer` helper
- `Messages.java` — CellView 4→6 fields with 3-arg and 4-arg back-compat constructors
- `application.yml` — appended `paralife.simulation.events:` section (seed key omitted)

**Commits (5):**
- `e01c95f` — Task 1 scaffolding (interface + third bean + config + BuffRegistry + CellView + yml + BuffRegistryTest)
- `bc2fd0e` — Task 2 DeathFinalizer + SimulationEngine refactor + DeathFinalizerTest
- `8b4adf2` — Task 3 EnvironmentEngine skeleton + Wave-0 env tests + sibling composite-branch tests
- `e57d883` — Task 4 EnvPostActionReconciler + test
- `dfb6f0c` — Task 5 CompostSinkFailFastTest

## Self-Check: PASSED

All 17 created/modified files present on disk. All 5 Task commits (e01c95f / bc2fd0e / 8b4adf2 / e57d883 / dfb6f0c) present in `git log --oneline --all`. Full suite `./gradlew test` exits 0 with 457 tests. `./gradlew bootRun` starts successfully in 2.15s with no ERROR / Exception lines during init.
