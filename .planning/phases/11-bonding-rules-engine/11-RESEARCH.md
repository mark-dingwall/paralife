# Phase 11: Bonding Rules Engine - Research

**Researched:** 2026-04-13
**Domain:** Java sealed-interface extension, Spring Boot `@ConfigurationProperties`, simulation physics refactor
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Predator+prey pairs only for MVP (endosymbiosis model). Catalyst→Spore, Spore→Membrane, Membrane→Catalyst.
- **D-02:** Future expansion to all-combination bonding planned; design must accommodate eventual RPSLS (5 types → 10 bonded pairs) without changing `BondedPair` itself.
- **D-03:** Both entities must exceed a configurable energy threshold AND a probability roll must succeed.
- **D-04:** Global parameters only — single energy threshold and single probability for all predator-prey pairs (no per-pair config).
- **D-05:** `BondedPair` is a new sealed subtype of `Entity`: `BondedPair(id, primaryType, secondaryType, energy, maxEnergy)`.
- **D-06:** Single shared energy pool — sum of both members' energy and maxEnergy at bond time.
- **D-07:** Primary/secondary roles are generic. Bonding rule assigns predator=primary, prey=secondary. Role assignment lives in the bonding rule, not `BondedPair`.
- **D-08:** When a bond forms, the secondary's cell becomes empty; `BondedPair` occupies the primary's cell.
- **D-09:** Replace `processCombat()` with `processInteractions()`. Combat becomes one interaction outcome, bonding another.
- **D-10:** Interaction resolution order: check bonding eligibility first (threshold + probability). If triggers → bond. Otherwise → combat.
- **D-11:** All damage, decay, and consumption operate on the single shared energy pool.
- **D-12:** Combat defense — when `BondedPair`'s primary type would lose combat, secondary's type grants configurable chance (default 25%) to deflect. Probabilistic, not immunity.
- **D-13:** Bonds are irrevocable.
- **D-14:** Death is all-or-nothing: shared pool reaches 0 → `BondedPair` removed.
- **D-15:** Successful combat attacks reduce the shared pool; pool hitting 0 kills the pair.
- **D-16:** All bonding parameters configurable via `application.yml`; prefix choice is Claude's discretion.

### Claude's Discretion

- Config record organization — extend `SimulationConfig` or create a new `BondingConfig` record
- Naming conventions for new methods and config properties
- Whether bonding events are logged at DEBUG or INFO level
- Internal implementation details of the interaction resolution refactor

### Deferred Ideas (OUT OF SCOPE)

- All-combination bonding (not just predator+prey)
- RPSLS expansion to 5 base types
- Per-type-pair bonding parameters
- Sustained proximity bonding condition (N ticks adjacent)
- Reduced energy decay as a bonding benefit
- Bond dissolution mechanics (explicitly rejected)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID  | Description | Research Support |
|-----|-------------|-----------------|
| R01 | Bonding rules configurable via application.yml or dedicated config | Covered: `@ConfigurationProperties` record pattern is established; recommend new `BondingConfig` record |
| R02 | At least two bonding conditions (proximity + energy threshold) | Covered: proximity is implicit (neighbors-only encounter already in `processCombat`); energy threshold is D-03; probability roll is the second condition |
| R03 | Bonding events observable in tick output | Covered: add `bondCount` to `Messages.Tick`; DEBUG-level logging for bond formation |
</phase_requirements>

---

## Summary

Phase 11 is a focused extension to the existing simulation engine. The codebase is already well-structured for this work: `Entity` is a sealed interface accepting new permits, `SimulationConfig` is a `@ConfigurationProperties` record with a clear extension pattern, and `SimulationEngine.processCombat()` contains the exact neighbor-scan + snapshot-read + deferred-write loop that `processInteractions()` will reuse.

The core work has three parts: (1) add `BondedPair` as a new `Entity` permit, (2) refactor `processCombat()` into `processInteractions()` that branches to bond or fight, and (3) wire bonding config and observable events. All downstream consumers (`processDeaths`, `processEnergyDecay`, `processOvercrowding`, `PerceptionBroadcaster`, `TickBroadcaster`) need minimal updates to handle the new entity type via sealed-interface `switch` statements.

The main design tension is config placement. The existing `SimulationConfig` is already at 7 fields with validation logic. Adding 3 bonding fields pushes it to 10 and dilutes the separation of concerns. A separate `BondingConfig` record under `paralife.bonding` is cleaner and aligns with D-16's "or dedicated config" phrasing.

**Primary recommendation:** Add `BondedPair` to the sealed Entity hierarchy, introduce a separate `BondingConfig` record, refactor `processCombat` → `processInteractions` with a bonding-first branch, and log bond formation at DEBUG with `bondCount` added to `Messages.Tick` for WebSocket observability.

---

## Standard Stack

No new external dependencies are required for this phase.

### Core (existing, all verified in codebase)

| Library | Version | Purpose |
|---------|---------|---------|
| Java 21 sealed interfaces | Java 21 | `BondedPair` as new `Entity` permit [VERIFIED: codebase] |
| Spring Boot `@ConfigurationProperties` | 3.4.4 | `BondingConfig` record binding [VERIFIED: codebase] |
| JUnit 5 + AssertJ | Spring Boot 3.4.4 BOM | Unit tests for bonding logic [VERIFIED: build.gradle.kts] |
| Mockito | Spring Boot 3.4.4 BOM | Mocking `BotRegistry` in engine tests [VERIFIED: codebase] |
| SLF4J / Logback | Spring Boot 3.4.4 BOM | Bonding event logging [VERIFIED: codebase] |
| Jackson | Spring Boot 3.4.4 BOM | `Messages.Tick` serialization with new `bondCount` field [VERIFIED: codebase] |

**Installation:** No new dependencies. Existing `build.gradle.kts` is sufficient.

---

## Architecture Patterns

### Pattern 1: Sealed Interface Extension — Adding `BondedPair` to `Entity`

**What:** Add `BondedPair` as a new `permits` entry in the existing `Entity` sealed interface.

**Constraint from D-05:** Flat fields only — `id`, `primaryType`, `secondaryType`, `energy`, `maxEnergy`. No nested member state.

**Example (following existing `Particle` pattern):**
```java
// Source: Entity.java — follows Particle record pattern [VERIFIED: codebase]
public sealed interface Entity permits Entity.Particle, Entity.Rock, Entity.Nutrient, Entity.BondedPair {

    record BondedPair(
            String id,
            ParticleType primaryType,
            ParticleType secondaryType,
            int energy,
            int maxEnergy
    ) implements Entity {

        public BondedPair {
            if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
            if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
        }

        public BondedPair withEnergy(int newEnergy) {
            return new BondedPair(id, primaryType, secondaryType,
                    Math.clamp(newEnergy, 0, maxEnergy), maxEnergy);
        }

        public boolean isAlive() {
            return energy > 0;
        }
    }
}
```

**Downstream impact:** Every `switch` on `Entity` subtypes must add a `BondedPair` arm. Affected:
- `PerceptionBroadcaster.cellToView()` — currently exhaustive switch; will fail to compile until `BondedPair` arm is added [VERIFIED: codebase, line 131-135]
- `SimulationEngine.processDeaths()` — checks `instanceof Particle`; `BondedPair` needs parallel death check
- `SimulationEngine.processEnergyDecay()` — checks `instanceof Particle`; `BondedPair` needs decay applied to shared pool
- Any Jackson serialization paths that switch on occupant type

**Compiler-enforced completeness:** Java sealed interface + exhaustive `switch` expressions produce compile errors for any unhandled `BondedPair` case, making the impact surface fully visible. [VERIFIED: existing exhaustive switch in `PerceptionBroadcaster.cellToView()` confirms this pattern is in use]

---

### Pattern 2: `processInteractions()` — Bonding-First Interaction Resolution

**What:** Rename `processCombat()` to `processInteractions()` and add a bonding branch before the combat branch.

**Existing pattern to preserve** (snapshot reads + deferred writes + shuffled iteration) [VERIFIED: SimulationEngine.java lines 96-147]:
```java
// Shuffle positions — prevents directional bias
Collections.shuffle(particlePositions, ThreadLocalRandom.current());

// Accumulate deltas, apply after scan
List<InteractionResult> results = new ArrayList<>();
for (Position pos : particlePositions) {
    // ... snapshot read, determine outcome, add to results
}
// Apply results
```

**New branching logic (D-10):**
```java
// Source: derived from existing processCombat pattern [VERIFIED: codebase]
if (attacker instanceof Particle a && defender instanceof Particle d && a.beats(d)) {
    if (bondingEligible(a, d, bondingConfig) && rng.nextDouble() < bondingConfig.bondingProbability()) {
        // Bond outcome: create BondedPair, clear secondary cell
        results.add(new BondResult(attackerPos, defenderPos, a, d));
    } else {
        // Combat outcome: existing energy transfer logic
        results.add(new CombatResult(attackerPos, simConfig.combatEnergyTransfer()));
        results.add(new CombatResult(defenderPos, -simConfig.combatEnergyTransfer()));
    }
}
// Also handle BondedPair defense:
if (attacker instanceof Particle a && defender instanceof BondedPair bp
        && a.type() == bp.primaryType().predator()) {
    if (rng.nextDouble() >= bondingConfig.bondDefenseChance()) {
        // Attack not deflected — reduce shared pool
        results.add(new CombatResult(defenderPos, -simConfig.combatEnergyTransfer()));
    }
}
```

**Bond application (D-08):**
```java
// Primary cell gets BondedPair; secondary cell cleared
worldGrid.setEntity(primaryPos.x(), primaryPos.y(), bondedPair);
worldGrid.clearEntity(secondaryPos.x(), secondaryPos.y());
```

---

### Pattern 3: `BondingConfig` as a Separate `@ConfigurationProperties` Record

**Recommendation:** Create a new `BondingConfig` record under prefix `paralife.bonding`. Rationale:
- `SimulationConfig` already has 7 fields with distinct validation rules; adding 3 bonding fields mixes two separate concerns.
- D-16 explicitly says "or dedicated config".
- Future phases (12+) will add more bonding-related config; a dedicated prefix keeps the `application.yml` readable.

**Pattern (following `SimulationConfig`):**
```java
// Source: follows SimulationConfig pattern [VERIFIED: codebase]
@ConfigurationProperties(prefix = "paralife.bonding")
public record BondingConfig(
        /** Minimum energy both entities must have for bonding eligibility. */
        int bondEnergyThreshold,
        /** Probability (0.0–1.0) that an eligible encounter results in bonding. */
        double bondingProbability,
        /** Probability (0.0–1.0) that a BondedPair deflects an attack on the primary. */
        double bondDefenseChance
) {
    public BondingConfig {
        if (bondEnergyThreshold < 0) throw new IllegalArgumentException(...);
        if (bondingProbability < 0 || bondingProbability > 1) throw new IllegalArgumentException(...);
        if (bondDefenseChance < 0 || bondDefenseChance > 1) throw new IllegalArgumentException(...);
    }

    public static BondingConfig defaults() {
        return new BondingConfig(50, 0.1, 0.25);
    }
}
```

**`application.yml` addition:**
```yaml
paralife:
  bonding:
    bond-energy-threshold: 50
    bonding-probability: 0.10
    bond-defense-chance: 0.25
```

**Registration:** `ParalifeApplication` uses `@ConfigurationPropertiesScan` with no arguments, which scans `com.paralife` recursively. `BondingConfig` placed in `com.paralife.engine` is automatically found. [VERIFIED: ParalifeApplication.java — `@ConfigurationPropertiesScan` present with no arguments]

---

### Pattern 4: Bonding Event Observability (R03)

**What:** Surface bond count in tick output so clients can observe bonding activity.

**Recommendation:** Log at DEBUG (no INFO noise at 256×256 scale), and add `bondCount` to `Messages.Tick`.

**Preferred approach — augment `Messages.Tick`:**
```java
// Minimal change; bondCount=0 on ticks with no bonding
record Tick(long tickNumber, long timestamp, int entityCount, int bondCount) implements Messages {}
```

This requires no new `@JsonSubTypes` registration, no new message handling in bot clients, and satisfies R03 ("observable in tick output") directly. A separate `BondFormed` event with per-bond details (position, participants) can be added in Phase 12 if bots need richer data — that's a Claude's Discretion call for that phase.

`TickBroadcaster` will need to receive the bond count from `SimulationEngine`. Options: return it from `processInteractions()` and pass via a `TickContext` object, or expose it via a per-tick atomic counter on `SimulationEngine`. The counter approach is consistent with `nutrientIdCounter` already present.

---

### Pattern 5: `PerceptionBroadcaster` Update

**What:** `cellToView()` uses a sealed switch — will fail to compile when `BondedPair` is added to Entity. Must be extended.

**Current exhaustive switch (lines 131-135):**
```java
return switch (occupant) {
    case Particle p -> new CellView(p.type().name(), p.id(), cell.nutrientLevel());
    case Entity.Rock r -> new CellView("ROCK", r.id(), cell.nutrientLevel());
    case Entity.Nutrient n -> new CellView("NUTRIENT", n.id(), cell.nutrientLevel());
};
```

**Required addition:**
```java
case Entity.BondedPair bp -> new CellView(
        "BONDED_" + bp.primaryType() + "_" + bp.secondaryType(),
        bp.id(), cell.nutrientLevel());
```

The `EntityState` self-state in `Perception` is built from `instanceof Particle` check. A `BondedPair` at a bot's position would fall to the `UNKNOWN` fallback (existing tech debt pattern from Phase 08). Acceptable for Phase 11 — bots controlling a `BondedPair` is out of scope.

---

### Anti-Patterns to Avoid

- **Adding bonding fields to `SimulationConfig`:** Mixes physics and bonding concerns; breaks single-responsibility for the record and its defaults method. Also breaks arity of 8+ existing test constructor calls.
- **Mutable bonding state during scan:** The snapshot-read + deferred-write pattern must be preserved. Do not apply bond formation mid-scan — it would clear a secondary cell that another position still holds a snapshot reference to.
- **Storing `BondedPair` members as references to `Particle` instances:** D-05 specifies flat fields only (`primaryType`, `secondaryType`). Flat fields also prevent the pair from holding stale references to dead member records.
- **BondedPair in BotRegistry:** Bots control `Particle` entities. `BondedPair` entities are simulation-managed. Do not register `BondedPair` with `BotRegistry`.
- **Defense deflection as immunity:** D-12 is explicit — probabilistic deflection, not guaranteed. Implement as `rng.nextDouble() >= config.bondDefenseChance()` (attack succeeds) guard, not a bypass.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Probability rolls | Custom RNG utility | `ThreadLocalRandom.current().nextDouble()` — already used in `processCombat` [VERIFIED: codebase] |
| Config validation | Manual property checks in `@PostConstruct` | Record compact constructor validation — existing pattern in `SimulationConfig` [VERIFIED: codebase] |
| Sealed switch exhaustiveness | Runtime `instanceof` chains | Java 21 sealed switch expressions — compiler enforces completeness |
| JSON type discrimination | Manual `type` field logic | Jackson `@JsonSubTypes` + `@JsonTypeInfo` — established in `Messages.java` [VERIFIED: codebase] |

---

## Common Pitfalls

### Pitfall 1: Deferred-Write Ordering for Bond Formation
**What goes wrong:** Bond creation applied while still iterating `particlePositions`. Position B's cell is cleared as a secondary bond target, but position C still tries to scan it.
**Why it happens:** The existing combat code accumulates `CombatResult` deltas and applies them after the full scan. Bond formation (clearing the secondary cell) must follow the same deferred-apply pattern.
**How to avoid:** Add `BondResult` alongside `CombatResult` in a unified results list; apply all bond formations after the scan loop completes.
**Warning signs:** Tests with multiple adjacent predator-prey pairs producing inconsistent `BondedPair` counts.

### Pitfall 2: Double-Bonding the Same Particle
**What goes wrong:** Two predators both scan the same prey particle in the shuffle loop and both decide to bond with it.
**Why it happens:** Snapshot reads at scan time — both see the prey as eligible. Both add `BondResult` entries for the same target position.
**How to avoid:** During the bond-apply phase, check whether the secondary position still holds an eligible `Particle` before creating the `BondedPair` (same pattern used in `ActionResolver` for cell conflicts). Track claimed secondary positions in a `Set<Position>` during results application.
**Warning signs:** `BondedPair` appears in primary cell but secondary cell is already empty (or holds a different entity).

### Pitfall 3: `SimulationConfig` Constructor Arity Mismatch in Tests
**What goes wrong:** `SimulationEngineTest` uses `new SimulationConfig(0, 10, 0.0, 5, true, 8, 0)` inline. If bonding fields are added to `SimulationConfig` instead of a new record, all test constructor calls break.
**Why it happens:** Records have canonical constructors — adding fields changes the arity.
**How to avoid:** Use a separate `BondingConfig` record (recommended), keeping `SimulationConfig` constructor arity stable.
**Warning signs:** Compile errors in `SimulationEngineTest` and `PopulationDynamicsTest` after config change.

### Pitfall 4: `BondedPair` Missed in Death Phase
**What goes wrong:** `processDeaths()` only checks `instanceof Particle`. A `BondedPair` with `energy == 0` is not removed, leaving a ghost entity on the grid.
**Why it happens:** The death phase predates `BondedPair`.
**How to avoid:** Extend `processDeaths()` to check `instanceof BondedPair bp && !bp.isAlive()` and clear its cell.
**Warning signs:** Integration test shows non-zero entity count after all energy depleted.

### Pitfall 5: `BondedPair` Defense Logic — Wrong Predator Lookup
**What goes wrong:** Defense check uses `a.beats(bp)` but `BondedPair` doesn't implement `beats()`. Or the check incorrectly applies when the attacker is the prey of the primary type rather than the predator.
**Why it happens:** `Particle.beats()` operates on `Particle` type, not `Entity`. `BondedPair` has `primaryType` but no `beats()` method.
**How to avoid:** Defense triggers when `attacker.type() == bp.primaryType().predator()` (the attacker is the natural predator of the pair's primary). Use `ParticleType.predator()` which is already implemented in the Entity enum.
**Warning signs:** Defense deflects attacks from non-predator types, or never deflects at all.

---

## Code Examples

### `@ConfigurationPropertiesScan` in `ParalifeApplication` (verified)
```java
// Source: ParalifeApplication.java [VERIFIED: codebase]
@SpringBootApplication
@ConfigurationPropertiesScan  // scans com.paralife.** — BondingConfig in com.paralife.engine is auto-discovered
public class ParalifeApplication { ... }
```

### Unit test pattern for bonding (following `SimulationEngineTest` structure)
```java
// Source: mirrors SimulationEngineTest helper pattern [VERIFIED: codebase]
private BondingConfig bondingConfig(int threshold, double probability, double defense) {
    return new BondingConfig(threshold, probability, defense);
}

@Test
void eligibleEncounterFormsBond() {
    // Both particles above threshold, probability=1.0 (always bond)
    var bondCfg = bondingConfig(30, 1.0, 0.25);
    Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
    Particle spore = new Particle("spo", ParticleType.SPORE, 50);
    grid.setEntity(5, 5, catalyst);
    grid.setEntity(5, 6, spore);

    engineWith(combatOnly(), bondCfg).processTick(1);

    assertThat(grid.getCell(5, 5).occupant()).isInstanceOf(Entity.BondedPair.class);
    assertThat(grid.getCell(5, 6).isEmpty()).isTrue();
}

@Test
void belowEnergyThresholdNoBond() {
    var bondCfg = bondingConfig(60, 1.0, 0.25); // threshold=60, particles have 50
    Particle catalyst = new Particle("cat", ParticleType.CATALYST, 50);
    Particle spore = new Particle("spo", ParticleType.SPORE, 50);
    grid.setEntity(5, 5, catalyst);
    grid.setEntity(5, 6, spore);

    engineWith(combatOnly(), bondCfg).processTick(1);

    // Falls through to combat
    assertThat(grid.getCell(5, 5).occupant()).isInstanceOf(Particle.class);
    assertThat(grid.getCell(5, 6).occupant()).isInstanceOf(Particle.class);
}

@Test
void zeroProbabilityNeverBonds() {
    var bondCfg = bondingConfig(30, 0.0, 0.25); // probability=0 → always combat
    // ... setup and assert combat outcome (energy transferred, no BondedPair)
}
```

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ + Mockito (Spring Boot 3.4.4 BOM) |
| Config file | None — auto-detected via `useJUnitPlatform()` in build.gradle.kts |
| Quick run command | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| R01 | `BondingConfig` binds from `application.yml`; compact constructor validates bounds | unit | `./gradlew test --tests "com.paralife.engine.BondingConfigTest"` | Wave 0 |
| R02 | Bond triggers when both above threshold AND probability roll succeeds | unit | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` | Extend existing |
| R02 | No bond when either particle below energy threshold | unit | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` | Extend existing |
| R02 | No bond at probability=0.0; always bonds at probability=1.0 | unit | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` | Extend existing |
| R02 | Defense deflection probabilistic at configured rate | unit | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` | Extend existing |
| R03 | `bondCount` in `Messages.Tick` reflects number of bonds formed each tick | unit | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` | Extend existing |
| — | `BondedPair` death (pool→0) removes entity from grid | unit | `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"` | Extend existing |
| — | `PerceptionBroadcaster.cellToView()` handles `BondedPair` | unit | `./gradlew test --tests "com.paralife.engine.PerceptionBroadcasterTest"` | Extend existing |
| — | No regression on existing tests | regression | `./gradlew test` | All existing |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.paralife.engine.SimulationEngineTest"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** `./gradlew test` green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/paralife/engine/BondingConfigTest.java` — covers R01 (config validation, defaults)
- [ ] Extend `SimulationEngineTest` with `BondingTests` nested class — covers R02, R03, BondedPair death

*(Existing test infrastructure covers all other gaps — framework, fixtures, and integration test harness already in place.)*

---

## Security Domain

No new authentication, session, or access control surface is introduced. Phase 11 is a pure simulation physics change. No ASVS categories apply beyond V5 Input Validation already covered by record compact constructor validation (config bounds checking).

---

## Assumptions Log

**This table is empty — all claims in this research were verified or cited against the codebase. No user confirmation needed.**

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| — | — | — | — |

---

## Open Questions (RESOLVED)

1. **`bondCount` surfacing to `TickBroadcaster`**
   - What we know: `TickBroadcaster` currently reads only `worldGrid.snapshot().entityCount()`. It has no channel to receive per-tick bond counts from `SimulationEngine`.
   - What's unclear: Best coupling approach — shared per-tick counter on `SimulationEngine` (consistent with `nutrientIdCounter`), or a `TickContext` object published alongside `TickEvent`.
   - Recommendation: Add a `AtomicInteger lastTickBondCount` field to `SimulationEngine`, reset at start of `processInteractions()`, incremented per bond. `TickBroadcaster` injects `SimulationEngine` and reads it. Simple, no new event types.
   - RESOLVED: Using `AtomicInteger lastTickBondCount` on `SimulationEngine`. `TickBroadcaster` injects `SimulationEngine` and calls `getLastTickBondCount()`. Implemented in Plan 11-01 Task 2 and Plan 11-02 Task 1.

2. **Energy decay rate for `BondedPair` — flat or scaled?**
   - What we know: D-11 says "decay operates on the single pool." The existing `processEnergyDecay` applies `energyDecayPerTick` flat to every `Particle`.
   - What's unclear: Should a `BondedPair` decay at the same flat rate as a `Particle`, or at 2× (representing two metabolisms)? D-11 doesn't specify.
   - Recommendation: Apply the same flat `energyDecayPerTick` to the shared pool in Phase 11. Phase 13 (Energy & Metabolism) will introduce per-type metabolism rates — that's the right phase for this distinction.
   - RESOLVED: Applying flat `energyDecayPerTick` to the shared pool (same rate as a Particle). Scaled decay deferred to Phase 13 (Energy & Metabolism). Implemented in Plan 11-01 Task 2.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 11 is pure code/config changes with no external dependencies beyond the existing Java 21 + Gradle build toolchain (verified operational from prior phases).

---

## Sources

### Primary (HIGH confidence)
- Codebase: `Entity.java`, `Cell.java`, `SimulationEngine.java`, `SimulationConfig.java`, `application.yml`, `Messages.java`, `PerceptionBroadcaster.java`, `TickBroadcaster.java`, `SimulationEngineTest.java`, `build.gradle.kts`, `ParalifeApplication.java` — all read directly in this session.
- `11-CONTEXT.md` — all locked decisions sourced from here.
- `REQUIREMENTS.md` — R01, R02, R03 requirements sourced from here.
- Java 21 sealed interface exhaustive switch behavior — confirmed by existing exhaustive switch in `PerceptionBroadcaster.cellToView()` [VERIFIED: codebase]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new deps; all existing tooling verified in codebase
- Architecture patterns: HIGH — derived directly from existing code patterns verified in session
- Pitfalls: HIGH — derived from existing code structure and known Java record/sealed-interface mechanics
- Config recommendation: HIGH — derived from existing `SimulationConfig` pattern; `@ConfigurationPropertiesScan` scope verified in `ParalifeApplication.java`

**Research date:** 2026-04-13
**Valid until:** 2026-05-13 (stable Java/Spring domain; no ecosystem churn risk)
