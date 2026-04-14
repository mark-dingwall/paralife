# Phase 13: Energy & Metabolism System - Research

**Researched:** 2026-04-14
**Domain:** Simulation physics — per-type metabolic profiles, starvation mechanics, soil fertility, seasonal cycles
**Confidence:** HIGH (all findings from direct codebase inspection)

## Summary

Phase 13 replaces the three flat simulation config values (`energyDecayPerTick`, `combatEnergyTransfer`, `nutrientConsumeEnergy`) with a full per-type metabolic profile system (30 config knobs across 3 types). It adds a progressive starvation mechanic with cornered-animal buffs/debuffs, activates the inert `Cell.nutrientLevel` field as soil fertility with random-patch initialization, adds a seasonal sine-wave nutrient spawn modulator, and gates reproduction by surplus energy.

The codebase is well-structured for this change. `SimulationConfig` is an `@ConfigurationProperties` record — the pattern for the new per-type config records is established by `CompositeConfig`. `Cell` already has `nutrientLevel`, `withNutrientLevel()`, and flag infrastructure (`FLAG_OVERCROWDED`) — adding `FLAG_STARVING` is trivial. The primary surgical points are `SimulationEngine.processEnergyDecay()`, `processInteractions()`, `processNutrientSpawning()`, and `ActionResolver.resolveConsume()` / `resolveReproduce()`.

The main complexity risks are: (1) the BondedPair hybrid vigor formula applied at formation vs per-tick — needs a decision on where rates are cached; (2) reproduce cooldown tracking, which is new per-entity state that has no current home; (3) the starvation modifier propagation — attack and nutrient gain modifiers must be threaded through `processInteractions()` and `resolveConsume()` without hardcoding per-type branches.

**Primary recommendation:** Introduce a `MetabolismConfig` record (or nested records per type) bound under `paralife.simulation.{type}`, a new `StarvationProcessor` tick pipeline component at `@Order(12)` (after decay, before actions), and a `FertilityInitializer` `@PostConstruct` component for patch generation. Keep `SimulationConfig` as-is for the non-per-type fields (overcrowding, enabled).

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Energy Architecture**
- D-01: Unified energy model. Single `energy` field = health + metabolism + reproduction currency. No HP/MP split.

**Per-Type Metabolic Profiles**
- D-02: 10 config knobs per ParticleType, 30 total under `paralife.simulation.<type>`: `max-energy`, `decay-per-tick`, `combat-energy-transfer`, `attack-power`, `nutrient-consume-energy`, `reproduce-energy-cost`, `reproduce-cooldown`, `bonus-offspring-chance`, `reproduce-range`, `starvation-threshold`, `starvation-floor`
- D-03: Type archetypes — CATALYST (high decay/attack/combat transfer), MEMBRANE (low decay/high nutrient gain), SPORE (r-strategist: low cost/short cooldown/bonus offspring/range 2)
- D-04: Existing flat `energyDecayPerTick`, `combatEnergyTransfer`, `nutrientConsumeEnergy` fields in `SimulationConfig` replaced by per-type equivalents. Old fields removed or become fallbacks.

**BondedPair Metabolism**
- D-05: Hybrid vigor formula for combat transfer/nutrient gain/attack power/reproduce cost: `rate = avg(A,B) + (max(A,B) - avg(A,B)) * random(bonusMin, bonusMax)`; config: `bond-rate-bonus-min`, `bond-rate-bonus-max` under `paralife.bonding`
- D-06: Bond decay cost reduction formula: `bondedDecay = sum(A,B) * random(bondDecayCostMin, bondDecayCostMax)`; config: `bond-decay-cost-min`, `bond-decay-cost-max` (0.6–0.9)
- D-07: BondedPair `maxEnergy` = sum of both types' `max-energy` (unchanged from current behavior)

**Composite Metabolism**
- D-08: CompositeConfig active/passive drain rates left as-is. Code structured for future type-aware roles. Composites receive modest metabolic discount similar to bonding.

**Starvation Mechanic**
- D-09: Progressive starvation intensity: `starvationIntensity = (threshold - currentPercent) / (threshold - floorPercent)`, clamped [0.0, 1.0]
- D-10: Starvation effects — Buffs: attack power boost, nutrient gain boost. Debuffs: cannot reproduce (binary), increased incoming damage, FLAG_STARVING set on cell
- D-11: Global starvation multipliers under `paralife.simulation.starvation`: `max-attack-boost: 0.5`, `max-nutrient-boost: 0.5`, `max-damage-vulnerability: 0.5`

**Cell.nutrientLevel Activation**
- D-12: `Cell.nutrientLevel` = soil fertility (0–100). Modulates nutrient spawn: `effectiveSpawnRate = baseRate * (1 + level / 100)`
- D-13: Fertility patch generation at world init. Config under `paralife.simulation.fertility`: `patch-count: 20`, `patch-min-radius: 3`, `patch-max-radius: 8`, `max-level: 100`. Patches have falloff from center.
- D-14: Seasonal sine wave: `effectiveSpawnRate = baseRate * (1 + amplitude * sin(2π * tick / yearLength))`. Config under `paralife.simulation.seasons`: `year-length-ticks: 200`, `amplitude: 0.5`
- D-15: Season phase and fertility multiplier included in `Messages.Tick` broadcast

**Reproduction Surplus Gating**
- D-16: Reproduce requires: `(energy - reproduceCost) >= (starvationThreshold% * maxEnergy)`
- D-17: Same surplus gate formula for Particle, BondedPair (shared pool), Composite REPRODUCER (shared pool)
- D-18: SPORE bonuses: `bonus-offspring-chance: 0.25`, `reproduce-range: 2`

### Claude's Discretion
- Config record organization (new `MetabolismConfig` vs extend `SimulationConfig` vs per-type records)
- Hybrid vigor and bond decay cost formula timing (formation vs per-tick calculation)
- Fertility patch generation algorithm (circle fill vs Gaussian falloff)
- `FLAG_STARVING` implementation details
- Season phase naming/enum (SPRING/SUMMER/AUTUMN/WINTER) for tick output
- Internal caching of per-type metabolic lookups
- How `reproduce-range: 2` selects target cell (nearest empty vs random in range)

### Deferred Ideas (OUT OF SCOPE)
- Gestation reproduction (multi-tick investment)
- Litter spawning (proportional multi-offspring)
- Type-aware composite role drain rates
- Composite size scaling overhead
- Composite member stat differentiation
- Moving fertility via Perlin/simplex noise
- Fertility regen from entity activity (corpse composting)
- Dispersal range for non-SPORE types
- Dual HP + Energy model
</user_constraints>

---

## Standard Stack

This phase uses no new libraries. All implementation is within the existing stack.

| Component | Already Present | Phase Usage |
|-----------|----------------|-------------|
| `@ConfigurationProperties` records | Yes — `SimulationConfig`, `CompositeConfig`, `BondingConfig` | New per-type metabolic config records |
| `@EventListener` / `@Order` | Yes — tick pipeline at 10, 15, 20, 50, 100 | New `StarvationProcessor` at @Order(12) |
| `@PostConstruct` | Yes — `TickEngine.init()` | New `FertilityInitializer` for patch generation |
| `Cell.withNutrientLevel()` | Yes — inert, never called | Now called during init and spawn logic |
| `Cell.FLAG_OVERCROWDED` | Yes — pattern established | Add `FLAG_STARVING = 2` (next bitflag) |
| `ThreadLocalRandom` | Yes — throughout engine | Hybrid vigor random range, patch generation |

[VERIFIED: codebase inspection]

## Architecture Patterns

### New Components Required

**`MetabolicProfile` record (new)** — Holds the 10 per-type config knobs. One instance per `ParticleType`. Either a nested record inside a parent config, or a standalone record bound under `paralife.simulation.catalyst`, `paralife.simulation.membrane`, `paralife.simulation.spore`.

Design choice (discretion): Per-type sub-records is cleaner and avoids a flat 30-field record. Spring `@ConfigurationProperties` supports nested records. Pattern:

```java
// Source: established by CompositeConfig in this codebase
@ConfigurationProperties(prefix = "paralife.simulation")
public record SimulationMetabolismConfig(
    MetabolicProfile catalyst,
    MetabolicProfile membrane,
    MetabolicProfile spore,
    StarvationConfig starvation,
    FertilityConfig fertility,
    SeasonsConfig seasons
) {
    public record MetabolicProfile(
        int maxEnergy,
        int decayPerTick,
        int combatEnergyTransfer,
        int attackPower,
        int nutrientConsumeEnergy,
        int reproduceEnergyCost,
        int reproduceCooldown,
        double bonusOffspringChance,
        int reproduceRange,
        int starvationThreshold,  // percent
        int starvationFloor       // percent
    ) {}
    // ... StarvationConfig, FertilityConfig, SeasonsConfig nested records
}
```

[VERIFIED: codebase inspection — `CompositeConfig` established this exact pattern; Spring @ConfigurationProperties nesting works with records]

**`StarvationProcessor` component (new)** — Tick pipeline at `@Order(12)`. Between `SimulationEngine` (10) and `CompositeEnergyDistributor` (15). Per entity: compute `starvationIntensity`, set/clear `FLAG_STARVING`, cache intensity for use by subsequent processors.

The starvation modifier values (boosted attack, boosted nutrient gain, increased damage taken) must be accessible to `SimulationEngine.processInteractions()` (which runs at Order 10, before StarvationProcessor). This creates an ordering problem.

**Resolution options:**
1. Move starvation flag/intensity computation into `processEnergyDecay()` at Order 10 — inline in `SimulationEngine`. No new component, but makes `SimulationEngine` larger.
2. Run starvation at Order 10 as a separate sub-phase within `processEnergyDecay()` — sets FLAG_STARVING, but combat modifiers apply next tick's combat (acceptable lag of 1 tick).
3. Move combat to after starvation: reorganize Order 10 to do decay first, then a separate `@Order(11)` for combat. Clean but changes existing phase order.

**Recommended approach:** Option 2 — accept 1-tick lag on starvation combat modifier. Set FLAG_STARVING during `processEnergyDecay()`, read starvation modifiers during that same tick's `processInteractions()` by checking flag at interaction time. This keeps the interaction-decay-death-spawn phase structure intact.

[ASSUMED: the 1-tick lag on starvation combat modifiers is acceptable. Confirm if strict simultaneity is required.]

**`FertilityInitializer` component (new)** — `@Component` with `@PostConstruct` that generates fertility patches on `WorldGrid` at startup. Uses `worldGrid.setCell()` (already exists) to set `nutrientLevel` on cells without occupants. Runs once before the tick loop starts (TickEngine also uses `@PostConstruct` but Spring ordering for `@PostConstruct` across beans is not guaranteed — use `@DependsOn` or implement `ApplicationListener<ContextRefreshedEvent>` to ensure WorldGrid exists).

[VERIFIED: TickEngine.java uses @PostConstruct; WorldGrid is a @Component with no @PostConstruct — safe to initialize from FertilityInitializer @PostConstruct as WorldGrid will be available by injection time]

**Seasonal state** — A `SeasonTracker` bean (or field in `SimulationEngine`) that tracks current tick and computes `sin(2π * tick / yearLength)` to get the seasonal multiplier. The multiplier is needed by `processNutrientSpawning()` and by `TickBroadcaster` for `Messages.Tick`.

### Reproduce Cooldown Tracking

Currently, `ActionResolver` has no per-entity cooldown state. It tracks per-composite movement intervals in `compositeTicksSinceMove` — the same pattern works here.

New field in `ActionResolver`:
```java
// Tracks last-reproduced tick per entityId
private final ConcurrentHashMap<String, Long> lastReproducedTick = new ConcurrentHashMap<>();
```

On successful reproduce: `lastReproducedTick.put(entityId, tickNumber)`.
Gate check: `tickNumber - lastReproducedTick.getOrDefault(entityId, 0L) >= reproduceCooldown`.
Cleanup: on entity death/unregister in `BotRegistry`, prune stale entries — same pattern as `compositeTicksSinceMove.keySet().retainAll(activeIds)`.

[VERIFIED: compositeTicksSinceMove in ActionResolver.java lines 598-645 establishes this exact pattern]

### BondedPair Hybrid Vigor — Formation vs Per-Tick

**Decision needed (discretion):** The formula uses per-type config values. BondedPairs have `primaryType` and `secondaryType` but no stored per-type rate fields.

Options:
1. **Compute at formation** — store effective rates as new fields on `BondedPair` record. Requires changing the record signature (adding fields like `effectiveDecayRate`, `effectiveAttackPower`, etc.). Makes BondedPair self-contained.
2. **Compute per-tick** — look up types from `BondedPair.primaryType()` / `.secondaryType()`, compute hybrid vigor each tick. Slightly more computation but no record change.

**Recommended:** Option 2 (compute per-tick). The record is already large (7 fields). Per-tick computation is cheap (a few int operations). Avoids schema migration for existing BondedPair instances in tests.

[ASSUMED: per-tick computation is preferred. Confirm if formation-time caching is important for performance.]

### BondedPair Hybrid Vigor — Applicable Domains

D-05 specifies hybrid vigor applies to: combat transfer, nutrient gain, attack power, reproduce cost.

**Applicability analysis based on current entity architecture:**
- **Combat transfer / attack power:** Applied in `processInteractions()` when a BondedPair is an attacker (currently BondedPairs don't initiate combat — they are passive entities formed during Particle combat). However, they ARE defenders in combat, and attacker stats are per the attacker's type, not the BondedPair's. Hybrid vigor for combat stats applies if BondedPairs ever gain combat agency.
- **Nutrient gain:** BondedPairs are not bot-controlled and do not take consume actions. `resolveConsume()` only handles Particle entities via `ResolvedAction.particle`. No consume path exists for BondedPairs.
- **Reproduce cost:** BondedPairs do not take reproduce actions. No reproduce path exists for BondedPairs in `ActionResolver`.

**Conclusion:** D-05 hybrid vigor is fully implemented for **decay cost** (D-06, applied in `processEnergyDecay()`). The `hybridRate()` helper method is available for combat transfer and attack power — these will activate when BondedPairs gain combat/action agency in a future phase. Nutrient gain and reproduce cost hybrid vigor formulas are defined but have no current application path because BondedPairs are passive entities that don't take bot actions.

### Reproduce Range (SPORE range=2)

`Direction.apply()` returns a single adjacent cell. Range 2 requires finding cells within Chebyshev distance 2. Since direction is user-specified, the simplest interpretation is: apply the direction vector twice (walk 2 steps in that direction on the toroidal grid). Alternative: pick random empty cell within radius 2.

**Recommended:** Walk 2 steps in the specified direction. Consistent with the existing direction protocol, no new API. For range=1 (CATALYST/MEMBRANE): unchanged behavior.

[ASSUMED: "2-cell reproduce range" means 2 steps in the chosen direction, not a radius search. Confirm if random-in-radius was intended.]

### FLAG_STARVING Bitflag

`Cell.FLAG_OVERCROWDED = 1` (bit 0). `FLAG_STARVING = 2` (bit 1). Pattern confirmed from `Cell.java`.

The flag must be cleared each tick when entity is no longer starving (same as overcrowding clearing logic in `processOvercrowding()`).

### Season Phase Naming

Discretion — recommend enum `Season { SPRING, SUMMER, AUTUMN, WINTER }` computed as:
```java
int seasonIndex = (int)((tick % yearLength) / (yearLength / 4));
Season season = Season.values()[seasonIndex];
```
Broadcast as string in `Messages.Tick`.

### Config Record Organization

Recommended (discretion): Keep `SimulationConfig` for the non-metabolic fields (overcrowding threshold/penalty, nutrient spawn probability, enabled). Create a new `SimulationMetabolismConfig` for the 30 per-type knobs + starvation + fertility + seasons config. Bound under `paralife.simulation` using nested records.

Do NOT put everything in one 35+ field record — defeats readability.

Extend `BondingConfig` with `bondRateBonusMin`, `bondRateBonusMax`, `bondDecayCostMin`, `bondDecayCostMax` (4 new fields). Already established pattern.

[VERIFIED: BondingConfig.java — current 3-field record; safe to add fields]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Config binding for nested records | Custom YAML parser | Spring `@ConfigurationProperties` with nested records — already working for CompositeConfig |
| Sine wave seasonal cycle | Manual sine table | `Math.sin()` + `Math.PI` — standard Java |
| Random range in hybrid vigor | Manual rand(min, max) | `ThreadLocalRandom.current().nextDouble(min, max)` |
| Toroidal distance for reproduce range 2 | Custom distance calc | `Position.wrap()` applied twice — already handles toroidal wrapping |
| Cooldown tracking | Per-tick state machine | `ConcurrentHashMap<String, Long>` keyed by entityId — same as `compositeTicksSinceMove` |

## Common Pitfalls

### Pitfall 1: Starvation modifier applied to wrong tick's combat

**What goes wrong:** If `FLAG_STARVING` is set during `StarvationProcessor @Order(12)` but combat runs at `@Order(10)`, starvation modifiers never apply to the current tick's combat — they're always 1 tick behind.

**How to avoid:** Set `FLAG_STARVING` inside `processEnergyDecay()` (within `SimulationEngine @Order(10)`) before `processInteractions()` runs in the same tick processing. Or accept the 1-tick lag as a design choice (see Architecture Patterns above).

**Warning signs:** Test shows starvation flag set but no attack boost observable on the same tick.

### Pitfall 2: SimulationConfig validation fails after removing fields

**What goes wrong:** Tests construct `SimulationConfig` directly (e.g., `combatOnly()`, `decayOnly()` factory methods in `SimulationEngineTest.java`). Removing `energyDecayPerTick`, `combatEnergyTransfer`, `nutrientConsumeEnergy` from `SimulationConfig` breaks all existing test constructors.

**How to avoid:** If these fields are fully removed, update all test factory methods and `SimulationConfig.defaults()`. If kept as global fallbacks, no test breakage. Audit usages before removing.

[VERIFIED: SimulationEngineTest.java lines 36-61 constructs SimulationConfig directly with those fields]

### Pitfall 3: FLAG_STARVING persists after entity leaves starvation

**What goes wrong:** `FLAG_OVERCROWDED` is explicitly cleared in `processOvercrowding()` when the entity is no longer crowded. If `FLAG_STARVING` is only set but never cleared, entities permanently show as starving after recovering energy.

**How to avoid:** Mirror the overcrowding pattern exactly — set flag when starving, clear flag when not starving, every tick.

[VERIFIED: SimulationEngine.java lines 462-476 — the clear pattern exists]

### Pitfall 4: BondedPair maxEnergy uses per-type config at formation

**What goes wrong:** `BondedPair.maxEnergy` = sum of both members' `maxEnergy`. Currently `Particle.DEFAULT_MAX_ENERGY = 100`. After D-02, each type has a different `max-energy`. BondedPair formation in `SimulationEngine.processInteractions()` currently uses `bond.predator.maxEnergy() + bond.prey.maxEnergy()` — this still works because Particle now carries type-specific maxEnergy from spawn. But Particle spawn must use per-type `max-energy` config, not `DEFAULT_MAX_ENERGY`.

**How to avoid:** When a Particle is spawned (in `WorldWebSocketHandler`), pass the type-specific `maxEnergy` from `SimulationMetabolismConfig.profileFor(type).maxEnergy()`. `Particle.DEFAULT_MAX_ENERGY` and `Particle.DEFAULT_START_ENERGY` constants can be kept for backward compat in tests or removed.

[VERIFIED: WorldWebSocketHandler.java lines 131-133 uses `worldGrid.trySetEntity(x, y, particle)` where particle is created; Entity.Particle.spawn() uses DEFAULT_MAX_ENERGY = 100]

### Pitfall 5: Reproduce cooldown map leaks on entity death

**What goes wrong:** Entities that die before their cooldown expires leave stale entries in `lastReproducedTick`. Over a long session with many births/deaths, the map grows without bound.

**How to avoid:** Prune on tick (retain only entries whose entity IDs are still registered in `BotRegistry`), or prune in the existing `processDeaths()` loop. The `compositeTicksSinceMove` map uses `retainAll(activeCompositeIds)` — same pattern applies.

[VERIFIED: ActionResolver.java lines 640-645 — stale-entry pruning pattern]

### Pitfall 6: WorldGrid nutrientLevel overwritten by setEntity

**What goes wrong:** `WorldGrid.setEntity()` calls `cell.withOccupant(entity)` — this preserves `flags` and `nutrientLevel` (Cell is immutable, withOccupant only replaces occupant). So fertility is NOT overwritten by entity placement. But `WorldGrid.clear()` resets to `Cell.EMPTY` (nutrientLevel=0). Integration tests that call `grid.clear()` before setup will lose fertility patches.

**How to avoid:** Fertility initializer must run after any test setup that calls `clear()`. In tests needing fertility, set nutrient levels explicitly after clearing, or use `@TestPropertySource` to verify the initializer runs.

[VERIFIED: Cell.java line 44 `withOccupant` only replaces occupant; WorldGrid.java line 121 `clear()` resets to Cell.EMPTY with level=0]

### Pitfall 7: CHILD_START_ENERGY static constant with per-type maxEnergy

**What goes wrong:** `ActionResolver.CHILD_START_ENERGY = 20` is static. When reproducing, child is spawned with `CHILD_START_ENERGY` and `ra.particle.maxEnergy()` as max. With per-type maxEnergy, CHILD_START_ENERGY should probably be `(per-type startEnergy)` or a fraction of parent's maxEnergy. If CATALYST has maxEnergy=80 and CHILD_START_ENERGY stays at 20, that's 25% — may be appropriate, but should be intentional.

**How to avoid:** Replace `CHILD_START_ENERGY` constant with per-type start energy from metabolic config, or keep as fraction of type's maxEnergy.

[VERIFIED: ActionResolver.java line 49-50]

## Code Examples

### Starvation Intensity Computation (D-09)

```java
// Source: formula from 13-CONTEXT.md D-09
double computeStarvationIntensity(int energy, int maxEnergy,
                                   int thresholdPercent, int floorPercent) {
    double currentPercent = (double) energy / maxEnergy * 100;
    if (currentPercent >= thresholdPercent) return 0.0;
    double intensity = (thresholdPercent - currentPercent)
                       / (double)(thresholdPercent - floorPercent);
    return Math.clamp(intensity, 0.0, 1.0);
}
```

### Effective Nutrient Spawn Rate (D-12 + D-14 combined)

```java
// Source: formula from 13-CONTEXT.md D-12 and D-14
double effectiveRate(double baseRate, int fertilityLevel, long tickNumber,
                     int yearLength, double amplitude) {
    double fertilityMultiplier = 1.0 + fertilityLevel / 100.0;
    double seasonalMultiplier  = 1.0 + amplitude * Math.sin(2 * Math.PI * tickNumber / yearLength);
    return baseRate * fertilityMultiplier * seasonalMultiplier;
}
```

### Fertility Patch Generation (D-13 — circle falloff)

```java
// Source: CONTEXT.md D-13 — falloff from center; algorithm is Claude's discretion
void generateFertilityPatch(WorldGrid grid, int cx, int cy, int radius, int maxLevel,
                              int width, int height, Random rng) {
    for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > radius) continue;
            double falloff = 1.0 - (dist / radius);
            int level = (int)(maxLevel * falloff);
            int x = Math.floorMod(cx + dx, width);
            int y = Math.floorMod(cy + dy, height);
            Cell cell = grid.getCell(x, y);
            grid.setCell(x, y, cell.withNutrientLevel(Math.max(cell.nutrientLevel(), level)));
        }
    }
}
```

### Hybrid Vigor Formula (D-05)

```java
// Source: formula from 13-CONTEXT.md D-05
int hybridRate(int rateA, int rateB, double bonusMin, double bonusMax, ThreadLocalRandom rng) {
    int avg = (rateA + rateB) / 2;
    int max = Math.max(rateA, rateB);
    double bonus = rng.nextDouble(bonusMin, bonusMax);
    return avg + (int)((max - avg) * bonus);
}

// Bond decay cost (D-06)
int bondDecay(int decayA, int decayB, double costMin, double costMax, ThreadLocalRandom rng) {
    double factor = rng.nextDouble(costMin, costMax);
    return (int)((decayA + decayB) * factor);
}
```

### Messages.Tick Extension (D-15)

```java
// Source: Messages.java Tick record — add two fields
record Tick(
    long tickNumber,
    long timestamp,
    int entityCount,
    int bondCount,
    int compositeCount,
    String seasonPhase,       // "SPRING" / "SUMMER" / "AUTUMN" / "WINTER"
    double fertilityMultiplier // current global seasonal multiplier
) implements Messages {}
```

### Reproduce Surplus Gate (D-16)

```java
// Source: formula from 13-CONTEXT.md D-16
boolean canReproduce(int energy, int reproduceCost, int starvationThresholdPercent, int maxEnergy) {
    int energyAfterCost = energy - reproduceCost;
    int starvationFloor = (int)(starvationThresholdPercent / 100.0 * maxEnergy);
    return energyAfterCost >= starvationFloor;
}
```

## State of the Art

| Old Approach | New Approach | Impact |
|--------------|-------------|--------|
| Flat `SimulationConfig.energyDecayPerTick` (single value) | Per-type `MetabolicProfile.decayPerTick` (3 values) | CATALYST burns faster, MEMBRANE conserves |
| Binary reproduction gate (energy >= cost) | Surplus gate: must stay above starvation threshold post-cost | No suicidal reproduction |
| `Cell.nutrientLevel` field defined but never written | Soil fertility with patch initialization and spawn-rate modulation | Resource geography creates emergent territories |
| Flat nutrient spawn probability (constant rate) | Seasonal sine wave * fertility multiplier | Population boom/bust cycles without entity-level intelligence |
| No starvation | Progressive starvation: cornered-animal buff + fragility | Starving entities are dangerous but targetable |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | 1-tick lag between decay and starvation modifiers on combat is acceptable | Architecture Patterns — StarvationProcessor ordering | If simultaneous application required, needs redesign of tick pipeline ordering |
| A2 | Hybrid vigor rates computed per-tick (not cached at formation) | Architecture Patterns — BondedPair | Performance overhead if many BondedPairs exist; formation-time caching would require adding fields to BondedPair record |
| A3 | "reproduce-range: 2" means 2 direction steps on the toroidal grid, not radius search | Architecture Patterns — Reproduce Range | If radius-search intended, needs different target-finding algorithm |

## Open Questions (RESOLVED)

1. **Starvation modifier ordering within the tick** (RESOLVED)
   - What we know: combat runs in `processInteractions()` before `processEnergyDecay()` in `processTick()`. Starvation status is determined by energy level after decay.
   - What's unclear: should starvation modifiers on combat apply to the same tick they're detected, or next tick?
   - Recommendation: Accept 1-tick lag (simpler implementation). Document it as intended behavior.
   - **Resolution:** Accepted 1-tick lag. FLAG_STARVING set during processEnergyDecay; combat modifiers read the flag from the previous tick. Documented as intended behavior in Plan 02 Task 2.

2. **Where does `MetabolicProfile` get injected?** (RESOLVED)
   - What we know: `SimulationEngine` already takes `SimulationConfig`. Adding `SimulationMetabolismConfig` means adding a constructor param to `SimulationEngine` and `ActionResolver`.
   - What's unclear: should these share the same config record or inject separately?
   - Recommendation: Single `SimulationMetabolismConfig` injected into both; keeps `SimulationConfig` for non-metabolic fields.
   - **Resolution:** `MetabolicProfile` (under `paralife.metabolism` prefix) injected as constructor param into both `SimulationEngine` and `ActionResolver`. `SimulationConfig` kept for non-metabolic fields. Specified in Plan 01 Tasks 1 and 2.

3. **Particle spawn max-energy source** (RESOLVED)
   - What we know: `WorldWebSocketHandler` calls `Particle.spawn(id, type)` which hardcodes `DEFAULT_MAX_ENERGY = 100`.
   - What's unclear: `WorldWebSocketHandler` doesn't currently have access to `SimulationMetabolismConfig`. Should it, or should particle spawn be moved?
   - Recommendation: Inject `SimulationMetabolismConfig` into `WorldWebSocketHandler` or create a factory method that receives the config.
   - **Resolution:** `MetabolicProfile` injected into `WorldWebSocketHandler`; uses `metabolicProfile.forType(particleType).maxEnergy()` when spawning. New `Particle.spawn(id, type, maxEnergy)` factory method added. Specified in Plan 01 Task 1.

## Environment Availability

Step 2.6: SKIPPED — phase is code/config changes only. No external tool dependencies beyond existing Java/Gradle stack.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ |
| Config file | None (Spring Boot Test auto-configures) |
| Quick run command | `./gradlew test --tests "*.MetabolismTest" --tests "*.StarvationTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Behavior | Test Type | Notes |
|----------|-----------|-------|
| Per-type decay rates applied correctly (CATALYST faster than MEMBRANE) | Unit | New `MetabolismTest.java` or extend `SimulationEngineTest` |
| Starvation threshold detection and FLAG_STARVING set/clear | Unit | New `StarvationProcessorTest.java` |
| Starvation buffs (attack boost, nutrient boost) applied at correct intensity | Unit | Verifiable without bots |
| Starvation debuffs (damage vulnerability, reproduce block) | Unit | |
| Reproduction surplus gate (cannot reproduce if would starve) | Unit | Extend `ActionResolverTest` |
| Reproduce cooldown enforced per entity | Unit | New test case in `ActionResolverTest` |
| SPORE bonus offspring chance fires at ~25% | Statistical test (RepeatedTest N=100) | |
| SPORE reproduce-range=2 places offspring 2 cells away | Unit | |
| Fertility patch initialization: cells have nutrientLevel > 0 in patches | Unit | `FertilityInitializerTest` |
| Fertility modulates nutrient spawn rate (high fertility cell spawns more) | Unit | |
| Seasonal multiplier computation for given tick | Unit | Pure math, no Spring context needed |
| Messages.Tick includes seasonPhase and fertilityMultiplier | Unit | `TickBroadcasterTest` extension |
| Population dynamics with metabolism enabled (integration) | Integration | New `MetabolismPopulationDynamicsTest` extending `PopulationDynamicsTest` pattern |

### Wave 0 Gaps

- [ ] `MetabolismPopulationDynamicsTest.java` — integration test per phase success criteria
- [ ] `StarvationProcessorTest.java` (or nested class in `SimulationEngineTest`) — unit tests for starvation intensity + flag lifecycle
- [ ] `FertilityInitializerTest.java` — verify patch generation against `WorldGrid` state
- [ ] Update `SimulationEngineTest` factory methods (`combatOnly()`, `decayOnly()`) to work with new config structure

## Security Domain

Not applicable — no external input validation changes, no authentication, no new endpoints. The existing WebSocket protocol is unchanged except for the `Messages.Tick` record extension (additive, backward-compatible).

## Sources

### Primary (HIGH confidence — direct codebase inspection)

- `src/main/java/com/paralife/engine/SimulationEngine.java` — tick pipeline, processEnergyDecay, processInteractions, processNutrientSpawning
- `src/main/java/com/paralife/engine/ActionResolver.java` — reproduce/consume logic, cooldown pattern from compositeTicksSinceMove
- `src/main/java/com/paralife/engine/SimulationConfig.java` — current flat config fields being replaced
- `src/main/java/com/paralife/engine/BondingConfig.java` — extension target for hybrid vigor config
- `src/main/java/com/paralife/engine/CompositeConfig.java` — pattern for per-type/per-role config
- `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` — @Order(15) pattern, comp energy accounting
- `src/main/java/com/paralife/world/Cell.java` — FLAG_OVERCROWDED pattern, withNutrientLevel
- `src/main/java/com/paralife/world/Entity.java` — Particle/BondedPair records, DEFAULT_MAX_ENERGY
- `src/main/java/com/paralife/world/WorldGrid.java` — setCell/setEntity distinction, toroidal wrapping
- `src/main/java/com/paralife/websocket/Messages.java` — Tick record fields to extend
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — Tick message construction
- `src/main/resources/application.yml` — current config structure
- `src/test/java/com/paralife/engine/PopulationDynamicsTest.java` — integration test pattern
- `src/test/java/com/paralife/engine/SimulationEngineTest.java` — factory method patterns that will break
- `.planning/phases/13-energy-metabolism-system/13-CONTEXT.md` — all locked decisions

## Metadata

**Confidence breakdown:**
- Locked decisions from CONTEXT.md: HIGH — user-confirmed
- Integration points (which methods to change): HIGH — verified by reading actual source
- Config record organization: MEDIUM — discretion area, recommendation based on existing patterns
- Starvation ordering: MEDIUM — implementation approach recommended, 1 assumption flagged
- BondedPair hybrid vigor timing: MEDIUM — assumption flagged for confirmation

**Research date:** 2026-04-14
**Valid until:** Stable (pure Java/Spring, no external version dependencies)
