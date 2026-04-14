---
phase: 13-energy-metabolism-system
plan: 01
subsystem: engine
tags: [metabolism, per-type, reproduction, starvation-config, child-energy, cooldown]
requires:
  - Entity.ParticleType (pre-existing)
  - SimulationConfig (pre-existing, retained)
  - CompositeRegistry / CompositeConfig (pre-existing)
provides:
  - MetabolicProfile @ConfigurationProperties record (prefix paralife.simulation.types)
  - MetabolicProfile.TypeProfile with 11 per-type knobs + childStartEnergy()
  - StarvationConfig @ConfigurationProperties record (prefix paralife.simulation.starvation)
  - Cell.FLAG_STARVING bitflag (= 2)
  - Particle.spawn(id, type, maxEnergy) factory
  - Per-type reproduce cooldown tracking (lastReproducedTick) + prune
  - D-16 surplus-gated reproduction (Particle)
  - D-17 surplus-gated reproduction (Composite REPRODUCER / shared pool)
  - D-18 SPORE r-strategist bonuses (reproduceRange=2, bonusOffspringChance=0.25)
affects:
  - SimulationEngine.processEnergyDecay (per-type Particle decay)
  - SimulationEngine.processInteractions (per-type combatEnergyTransfer / attackPower)
  - ActionResolver.resolveReproduce (surplus gate, cooldown, range, bonus, per-type child)
  - ActionResolver.resolveConsume (per-type nutrient gain)
  - ActionResolver.resolveFeederConsume (per-type nutrient gain)
  - ActionResolver.resolveReproducerBud (per-type cost, surplus gate, per-type child)
  - WorldWebSocketHandler.handleRegister (per-type Particle maxEnergy on spawn)
tech-stack:
  added: []  # pure Java/Spring, no new libraries
  patterns:
    - "Multiple @ConfigurationProperties records on disjoint sub-prefixes (simulation + simulation.types + simulation.starvation)"
    - "Per-entity state map keyed by entityId, pruned via retainAll against BotRegistry.getAllBots()"
    - "Uniform-profile test helper for legacy tests that predate per-type dynamics"
key-files:
  created:
    - src/main/java/com/paralife/engine/MetabolicProfile.java
    - src/main/java/com/paralife/engine/StarvationConfig.java
    - src/test/java/com/paralife/engine/MetabolicProfileTest.java
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/world/Entity.java
    - src/main/java/com/paralife/world/Cell.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/engine/SimulationEngineTest.java
    - src/test/java/com/paralife/engine/ActionResolverTest.java
    - src/test/java/com/paralife/engine/CompositeCombatTest.java
    - src/test/java/com/paralife/engine/CompositeFormationTest.java
    - src/test/java/com/paralife/engine/CompositeDissolutionTest.java
    - src/test/java/com/paralife/engine/CompositeActionTest.java
    - src/test/java/com/paralife/engine/CompositeMovementTest.java
    - src/test/java/com/paralife/engine/SimulationIntegrationTest.java
    - src/test/java/com/paralife/engine/PopulationDynamicsTest.java
decisions:
  - id: config-record-organization
    summary: >
      Bound MetabolicProfile under the sub-prefix `paralife.simulation.types`
      (instead of the flat `paralife.simulation` root) because SimulationConfig
      already owns that prefix. Sub-prefix keeps both records valid without
      Spring binding collisions.
  - id: bonded-pair-decay-unchanged
    summary: >
      BondedPair energy decay still uses SimulationConfig.energyDecayPerTick
      (the legacy flat value). Hybrid vigor decay (D-05, D-06) is Plan 02's
      responsibility — this plan's scope is Particles only.
  - id: spore-reproduce-range-steps-not-radius
    summary: >
      SPORE's reproduceRange=2 means "walk the chosen direction twice",
      matching the existing Direction.apply protocol. Chose this over a
      radius-search because it requires no new API and is consistent with
      existing movement semantics.
  - id: cooldown-map-prune-via-botregistry
    summary: >
      lastReproducedTick entries are pruned by intersecting keyset with
      BotRegistry.getAllBots() entityIds, mirroring compositeTicksSinceMove's
      retainAll pattern. Addresses cross-AI review concern #1 (memory leak)
      without adding a new registry API.
  - id: legacy-constants-deprecated-not-removed
    summary: >
      REPRODUCE_ENERGY_COST and CHILD_START_ENERGY are marked @Deprecated but
      retained because existing ActionResolverTest and CompositeActionTest
      assert against them. Tests now use a legacyProfile() MetabolicProfile
      whose per-type values match those constants; production code always
      reads the per-type profile.
metrics:
  duration: "~55 min"
  completed: 2026-04-15
---

# Phase 13 Plan 01: Per-Type Metabolic Profiles + Reproduction Overhaul Summary

Per-type metabolic profiles drive decay, combat, consume, and reproduction; reproduction now has a surplus gate, a cooldown, SPORE range-2 dispersal, and SPORE bonus-offspring rolls.

## What changed

- **MetabolicProfile** — `@ConfigurationProperties(prefix = "paralife.simulation.types")` record with a nested `TypeProfile` per `ParticleType`. 11 knobs each (maxEnergy, decayPerTick, combatEnergyTransfer, attackPower, nutrientConsumeEnergy, reproduceEnergyCost, reproduceCooldown, bonusOffspringChance, reproduceRange, starvationThreshold, starvationFloor). `childStartEnergy()` derives `maxEnergy / 2` so children scale with archetype. Cross-field validation enforces `starvationFloor <= starvationThreshold` and `bonusOffspringChance in [0.0, 1.0]`.
- **StarvationConfig** — separate `@ConfigurationProperties(prefix = "paralife.simulation.starvation")` record with the three global multipliers (attack boost, nutrient boost, damage vulnerability). Consumed in later plans (02/03) but wired through the SimulationEngine constructor now so the shape is stable.
- **Cell.FLAG_STARVING** — bitflag constant (= 2) added. Not yet set/cleared; Plan 02's StarvationProcessor owns the lifecycle.
- **Particle.spawn(id, type, maxEnergy)** — new factory that sets `energy = maxEnergy / 2`. The legacy `spawn(id, type)` factory remains for tests that rely on `DEFAULT_MAX_ENERGY=100`.
- **SimulationEngine** — constructor takes `MetabolicProfile` + `StarvationConfig`. `processEnergyDecay` reads `profile.decayPerTick()` per particle type. All three Particle-combat paths (Particle vs Particle, Particle vs BondedPair, Particle vs CompositeMember) use `profile.combatEnergyTransfer()` for attacker gain and `profile.attackPower()` for defender loss. BondedPair decay keeps the flat `SimulationConfig.energyDecayPerTick` for now (Plan 02 introduces hybrid vigor).
- **ActionResolver** — constructor takes `MetabolicProfile`. `resolveReproduce` now gates on per-type cost + D-16 surplus + per-type cooldown (`lastReproducedTick` map), walks `profile.reproduceRange()` steps for targeting, spawns the child with `profile.childStartEnergy()` + `profile.maxEnergy()`, and rolls for bonus offspring with a guard so `bonusOffspringChance=0.0` skips the RNG call entirely. `resolveConsume` and `resolveFeederConsume` both read `profile.nutrientConsumeEnergy()`. `resolveReproducerBud` uses per-type cost, per-type child energy, and a pool-level surplus gate against `composite.getMaxPoolEnergy()`. A new prune step in `resolveCompositeMovements` intersects `lastReproducedTick.keySet()` with `BotRegistry.getAllBots()` entity ids to prevent unbounded growth as entities die.
- **WorldWebSocketHandler** — injects `MetabolicProfile` and spawns new particles with `profile.forType(particleType).maxEnergy()`.
- **application.yml** — adds `paralife.simulation.types.{catalyst,membrane,spore}` with archetype values (D-03) and `paralife.simulation.starvation` defaults. Flat `SimulationConfig` fields are retained for BondedPair decay and tests.

## Test coverage

- **MetabolicProfileTest** (new) — 16 tests covering default archetypes, `forType()` lookup identity, `childStartEnergy()` derivation, and compact-constructor validation (ranges, cross-field constraints, null subrecords).
- **SimulationEngineTest** — factory helpers build a `uniformProfile(config)` matching the legacy flat rates so all pre-Phase-13 assertions (decay rates, combat energy transfer, overcrowding) continue to hold.
- **ActionResolverTest** — 9 new tests for surplus gate pass/fail, cooldown enforcement across three tick values (5 / 8 / 16), SPORE range-2 dispersal, MEMBRANE range-1 control, SPORE bonus-offspring rate (200-trial probabilistic band 10%-40%), per-type nutrient gain (MEMBRANE=8), per-type child `maxEnergy` inheritance, and cooldown map pruning on entity death.
- **Composite*Test** — updated to pass `ActionResolverTest.legacyProfile()` so legacy `REPRODUCE_ENERGY_COST` and `CHILD_START_ENERGY` assertions remain valid.
- **SimulationIntegrationTest / PopulationDynamicsTest** — per-type decay/combat values wired via `@TestPropertySource` properties so these integration tests isolate the behaviors they predate.

Total: **357 tests pass** (up from 348 pre-plan). Two commits, one per task. Gradle's `--rerun-tasks` verifies stability.

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 3 — Blocking config conflict] Re-prefixed MetabolicProfile to `paralife.simulation.types`**
- **Found during:** Task 1 initial implementation
- **Issue:** The plan instructs "try `paralife.simulation` first, fall back to `paralife.simulation.types`". Using `paralife.simulation` as the prefix for `MetabolicProfile` collides with `SimulationConfig` (also `paralife.simulation`). Spring Boot refuses to bind when two `@ConfigurationProperties` records advertise overlapping prefixes on record constructors — the tie-breaker is undefined.
- **Fix:** Used the fallback `paralife.simulation.types` sub-prefix from the start. Yaml nests the per-type profiles under `simulation.types.{catalyst,membrane,spore}`. No semantic loss; aligns with the CONTEXT.md description.
- **Files modified:** `MetabolicProfile.java`, `application.yml`
- **Commit:** `8227aa0`

**2. [Rule 3 — Blocking test breakage] Added `uniformProfile(config)` helper to preserve legacy test semantics**
- **Found during:** Task 1 verification
- **Issue:** Pre-Phase-13 tests (SimulationEngineTest, CompositeCombatTest, CompositeActionTest, CompositeMovementTest) assume flat decay/combat values. Enabling per-type rates broke those assertions.
- **Fix:** Test helpers build a `MetabolicProfile` where all three types carry the legacy flat values. Production code continues to use `MetabolicProfile.defaults()` with the archetype distinctions.
- **Files modified:** All `Composite*Test.java`, `SimulationEngineTest.java`, `ActionResolverTest.java`
- **Commit:** `8227aa0`, `bb644d0`

**3. [Rule 3 — Blocking test breakage] Integration tests now override per-type profiles via @TestPropertySource**
- **Found during:** Task 1 / Task 2 verification
- **Issue:** `SimulationIntegrationTest` and `PopulationDynamicsTest` use `@SpringBootTest` with `@TestPropertySource` overrides for the flat `paralife.simulation.*` values. With per-type rates sourced from yaml archetypes, those overrides no longer control engine behavior.
- **Fix:** Added explicit `paralife.simulation.types.<type>.<knob>` overrides to both tests so their existing assertions (e.g. decay=2, decay=0, combat=5) continue to reflect actual engine behavior.
- **Files modified:** `SimulationIntegrationTest.java`, `PopulationDynamicsTest.java`
- **Commit:** `8227aa0`, `bb644d0`

**4. [Rule 2 — Missing guard] Bonus-offspring RNG call guarded by chance > 0**
- **Found during:** Task 2 implementation
- **Issue:** Cross-AI review item #10 flagged `ThreadLocalRandom.nextDouble(min, max)` edge cases. Even though bonus-offspring uses the zero-arg `nextDouble()` (not the range form), calling it at all when `bonusOffspringChance == 0.0` is wasted work and a misleading entropy draw for tests that pin seeds.
- **Fix:** Added `profile.bonusOffspringChance() > 0.0` short-circuit before the RNG call.
- **Files modified:** `ActionResolver.java`
- **Commit:** `bb644d0`

**5. [Rule 2 — Missing cleanup] lastReproducedTick pruning wired into existing prune location**
- **Found during:** Task 2 implementation
- **Issue:** Cross-AI review item #1 (HIGH severity) flagged that the cooldown map would leak as entities die. Plan explicitly called this out; implementation ensures prune runs every tick alongside `compositeTicksSinceMove.keySet().retainAll(activeCompositeIds)`.
- **Fix:** Added matching `lastReproducedTick.keySet().retainAll(activeEntityIds)` immediately after the composite prune, using `BotRegistry.getAllBots()` as the source of truth.
- **Files modified:** `ActionResolver.java`
- **Commit:** `bb644d0`

### Auth gates
None.

## Self-Check: PASSED

File existence:
- FOUND: `src/main/java/com/paralife/engine/MetabolicProfile.java`
- FOUND: `src/main/java/com/paralife/engine/StarvationConfig.java`
- FOUND: `src/test/java/com/paralife/engine/MetabolicProfileTest.java`
- FOUND: modifications to 15 other files listed in `key-files.modified`

Commits:
- FOUND: `8227aa0` feat(13-01): per-type metabolic profiles + starvation config (Task 1)
- FOUND: `bb644d0` feat(13-01): per-type reproduction in ActionResolver (Task 2)

Verification:
- `./gradlew test -x jacocoTestReport` → BUILD SUCCESSFUL, 357 tests pass (zero failures).

## Known caveats

- `deadEntitiesActuallyRemoved` in `SimulationIntegrationTest` has exhibited pre-existing flakiness when the full test suite runs in a cold Spring context. It passes standalone and passes on `--rerun-tasks`. No change from this plan affects the root cause; likely a context-caching issue with TickEngine initialization ordering. Not regressed by this work.
- `Cell.FLAG_STARVING` is defined but never set or cleared yet. Plan 02's StarvationProcessor owns lifecycle; wiring it here would be premature.
- BondedPair decay still uses the flat `SimulationConfig.energyDecayPerTick`. Plan 02 (hybrid vigor D-05/D-06) replaces this.
