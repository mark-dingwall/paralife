# Phase 13: Energy & Metabolism System - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Richer energy model for Particles and BondedPairs — per-type metabolic profiles, progressive starvation mechanic, surplus-gated reproduction with cooldowns, soil fertility via Cell.nutrientLevel, and seasonal nutrient cycles. Composite metabolism left as-is for MVP (code structured for future type-aware role rates). Unified energy model retained (no HP/MP split).

</domain>

<decisions>
## Implementation Decisions

### Energy Architecture
- **D-01:** Unified energy model retained. Single `energy` field handles health, metabolism, and reproduction currency. Starvation = fragility. No HP/MP split. Composites already layer complexity via dual energy (individual + pool); adding HP would triple accounting.

### Per-Type Metabolic Profiles
- **D-02:** Full metabolic profile per ParticleType. 10 config knobs per type, 30 total. Each type under `paralife.simulation.<type>`:
  - `max-energy` — energy capacity
  - `decay-per-tick` — base metabolic burn rate
  - `combat-energy-transfer` — energy stolen on combat win
  - `attack-power` — damage dealt in combat
  - `nutrient-consume-energy` — energy gained per nutrient consumed
  - `reproduce-energy-cost` — energy cost to reproduce
  - `reproduce-cooldown` — ticks between reproductions
  - `bonus-offspring-chance` — probability of free extra child (SPORE r-strategist bonus)
  - `reproduce-range` — max distance for offspring spawn (SPORE=2, others=1)
  - `starvation-threshold` — % of max energy where starvation begins
  - `starvation-floor` — % of max energy where starvation effects reach maximum

- **D-03:** Type archetypes (semantic match to names):
  - **CATALYST:** Fast hungry predator. High decay, high attack power, high combat transfer. Burns hot, must hunt continuously.
  - **MEMBRANE:** Efficient defensive grazer. Low decay, high nutrient gain, durable. Survives scarcity.
  - **SPORE:** r-strategist breeder. Low reproduce cost, short cooldown, bonus offspring chance, 2-cell reproduce range. Breeds fast, cheap, far.

- **D-04:** Existing flat config values (`energyDecayPerTick`, `combatEnergyTransfer`, `nutrientConsumeEnergy`) replaced by per-type equivalents. Old SimulationConfig fields removed or become defaults/fallbacks.

### BondedPair Metabolism
- **D-05:** BondedPair metabolic rates use hybrid vigor formula — bonding is strictly beneficial:
  ```
  rate = avg(typeA.rate, typeB.rate) + (max(typeA.rate, typeB.rate) - avg(typeA.rate, typeB.rate)) * random(bonusMin, bonusMax)
  ```
  Config: `bond-rate-bonus-min`, `bond-rate-bonus-max` (e.g., 0.1 to 0.5). Applied to: combat transfer, nutrient gain, attack power, reproduce cost.

- **D-06:** BondedPair energy consumption (decay) uses cost reduction formula — bonding reduces total metabolic cost:
  ```
  bondedDecay = sum(typeA.decay, typeB.decay) * random(bondDecayCostMin, bondDecayCostMax)
  ```
  Config: `bond-decay-cost-min`, `bond-decay-cost-max` (e.g., 0.6 to 0.9). Two organisms fused burn 60-90% of their combined cost.

- **D-07:** BondedPair max energy = sum of both types' max energy (existing behavior, unchanged).

### Composite Metabolism
- **D-08:** Composite per-role drain rates left as-is for MVP (CompositeConfig active/passive drain). Code structured to accommodate future type-aware role rates. Composites should receive a modest metabolic discount similar to bonding — emergence uncommon but rewarded when it happens.

### Starvation Mechanic
- **D-09:** Progressive starvation with scaling intensity. Not binary — effects ramp up from threshold to floor:
  ```
  starvationIntensity = (threshold - currentPercent) / (threshold - floorPercent)
  // clamped to [0.0, 1.0]
  ```
  Per-type threshold and floor from metabolic profile (D-02).

- **D-10:** Starvation is a mix of buffs and debuffs — desperate survival mode (cornered animal):
  - **Buff:** Attack power boost: `basePower * (1 + maxAttackBoost * intensity)` — desperate aggression
  - **Buff:** Nutrient gain boost: `baseGain * (1 + maxNutrientBoost * intensity)` — desperate feeding efficiency
  - **Debuff:** Cannot reproduce (binary, at threshold)
  - **Debuff:** Increased incoming damage: `baseDamage * (1 + maxDamageVulnerability * intensity)` — weakened defenses
  - **Debuff:** FLAG_STARVING set on cell, visible in perception — predators see weakness (hyenas circling)

- **D-11:** Starvation buff/debuff max multipliers are global (not per-type). 3 config values:
  ```yaml
  paralife.simulation.starvation:
    max-attack-boost: 0.5         # +50% attack at floor
    max-nutrient-boost: 0.5       # +50% nutrient gain at floor
    max-damage-vulnerability: 0.5 # +50% incoming damage at floor
  ```
  Per-type differentiation comes from threshold/floor placement in metabolic profile.

### Cell.nutrientLevel Activation (Soil Fertility)
- **D-12:** Cell.nutrientLevel represents soil fertility (0-100). High fertility cells spawn Nutrient entities faster. Existing Nutrient entity system unchanged — fertility modulates spawn probability:
  ```
  effectiveSpawnRate = baseRate * (1 + level / 100)
  ```

- **D-13:** Initial fertility distribution: random patches at world init. Config:
  ```yaml
  paralife.simulation.fertility:
    patch-count: 20
    patch-min-radius: 3
    patch-max-radius: 8
    max-level: 100
  ```
  Patches have falloff from center. Creates natural geography (oases and barrens). Starting point — will be replaced by noise-based initial state in future.

- **D-14:** Fertility regeneration: slow global decay + regen, framed as seasonal cycle. Sine wave modulates nutrient spawn rate globally:
  ```
  effectiveSpawnRate = baseRate * (1 + amplitude * sin(2π * tick / yearLength))
  ```
  Config:
  ```yaml
  paralife.simulation.seasons:
    year-length-ticks: 200        # full cycle (4 seasons of 50 ticks)
    amplitude: 0.5                # ±50% spawn rate swing
  ```
  Spring = nutrient abundance. Winter = scarcity. Population dynamics driven by external pressure.

- **D-15:** Current season phase and fertility multiplier broadcast in tick output (Messages.Tick). Visible to all connected clients.

### Reproduction Surplus Gating
- **D-16:** Reproduction gated by post-cost energy surplus. Entity must remain above starvation threshold after paying reproduce cost:
  ```
  canReproduce = (energy - reproduceCost) >= (starvationThreshold% * maxEnergy)
  ```
  Prevents suicidal reproduction. Natural integration with starvation mechanic. Zero new config knobs.

- **D-17:** Same surplus gate formula for all entity types:
  - Particle: checks own energy
  - BondedPair: checks shared pool
  - Composite REPRODUCER: checks composite shared pool
  Composite members self-regulate via pool depletion — if pool empty, can't draw energy, can't reproduce.

- **D-18:** SPORE r-strategist reproduction bonuses:
  - `bonus-offspring-chance: 0.25` — 25% chance for free extra child per reproduction
  - `reproduce-range: 2` — can spawn offspring 2 cells away (others limited to 1)
  - Combined with shortest cooldown and lowest cost = three reproductive edges
  These are per-type config knobs (available to all types, CATALYST/MEMBRANE default to 0.0/1).

### Claude's Discretion
- Config record organization — new `MetabolismConfig` record or extend `SimulationConfig`, or per-type config records
- How hybrid vigor and bond decay cost formulas are calculated at formation time vs per-tick
- Fertility patch generation algorithm (simple circle fill vs Gaussian falloff)
- FLAG_STARVING implementation details (new Cell flag constant)
- Season phase naming/enum (SPRING/SUMMER/AUTUMN/WINTER) for tick output
- Internal caching of per-type metabolic lookups
- How `reproduce-range: 2` selects target cell (nearest empty vs random in range)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Entity Model
- `src/main/java/com/paralife/world/Entity.java` — Sealed Entity hierarchy; Particle.DEFAULT_MAX_ENERGY and DEFAULT_START_ENERGY become per-type
- `src/main/java/com/paralife/world/Cell.java` — Cell record with nutrientLevel field (currently inert), flags system (add FLAG_STARVING)

### Simulation Engine
- `src/main/java/com/paralife/engine/SimulationEngine.java` — processEnergyDecay needs per-type rates, processInteractions needs per-type attack power/combat transfer, processNutrientSpawning needs fertility + seasonal modulation
- `src/main/java/com/paralife/engine/SimulationConfig.java` — Flat config values to be replaced by per-type metabolic profiles
- `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` — Composite drain rates (leave as-is, structure for future type-awareness)

### Action Resolution
- `src/main/java/com/paralife/engine/ActionResolver.java` — REPRODUCE_ENERGY_COST becomes per-type, resolveConsume needs per-type nutrient gain, resolveReproduce needs cooldown + surplus gate + bonus offspring + range

### Configuration
- `src/main/resources/application.yml` — Per-type metabolic profiles, starvation config, fertility config, seasonal config
- `src/main/java/com/paralife/engine/BondingConfig.java` — Add bond-rate-bonus-min/max and bond-decay-cost-min/max
- `src/main/java/com/paralife/engine/CompositeConfig.java` — Leave as-is for MVP

### WebSocket
- `src/main/java/com/paralife/websocket/Messages.java` — Add season phase and fertility multiplier to Tick message
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — Include seasonal data in broadcast

### World Init
- `src/main/java/com/paralife/world/WorldGrid.java` — Fertility patch generation at world init

### Prior Phase Context
- `.planning/phases/12-composite-entities/12-CONTEXT.md` — Composite energy model decisions (D-14 through D-18)
- `.planning/phases/11-bonding-rules-engine/11-CONTEXT.md` — BondedPair energy decisions (D-05 through D-07)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SimulationConfig` @ConfigurationProperties record — pattern for per-type metabolic config
- `CompositeConfig` per-role drain rates — pattern for per-type decay rates
- `Cell.withNutrientLevel()` — already exists, ready for fertility activation
- `Cell.FLAG_OVERCROWDED` — pattern for FLAG_STARVING
- `Nutrient.spawn()` / `Nutrient.consumed()` — existing nutrient lifecycle, unchanged
- `CompositeEnergyDistributor` — pattern for tick pipeline energy processing component

### Established Patterns
- Immutable records for all data — metabolic profile config follows same convention
- `@ConfigurationProperties` on records with constructor validation
- Spring `@EventListener` with `@Order` for tick pipeline sequencing
- Snapshot reads + deferred writes in simulation phases
- Cell flags for environmental effects (overcrowding → starvation)

### Integration Points
- `SimulationEngine.processEnergyDecay()` — per-type decay rates instead of flat
- `SimulationEngine.processInteractions()` — per-type attack power and combat transfer
- `SimulationEngine.processNutrientSpawning()` — fertility-modulated + seasonal spawn rates
- `SimulationEngine.processDeaths()` — unchanged (death at energy=0 stays)
- `ActionResolver.resolveConsume()` — per-type nutrient gain
- `ActionResolver.resolveReproduce()` — per-type cost, cooldown, surplus gate, bonus offspring, range
- `WorldGrid` init — fertility patch generation
- `Messages.Tick` — season phase + fertility multiplier fields
- New starvation processing step in tick pipeline (check thresholds, set flags, apply modifiers)

</code_context>

<specifics>
## Specific Ideas

- **Cornered animal starvation** — starving entities become more dangerous but fragile (glass cannon). Attack and feeding efficiency increase with desperation while defense crumbles. FLAG_STARVING visible in perception enables predator targeting of weak entities (hyenas/vultures circling).
- **Hybrid vigor** — bonding is strictly metabolically beneficial. Rates trend toward the better parent with random bonus. Decay cost is reduced. Strong endosymbiosis incentive.
- **Seasonal cycles** — sine wave nutrient spawning creates external boom/bust pressure on populations. Spring abundance → population growth → summer competition → winter scarcity → population crash → cycle repeats. Emergent without entity-level intelligence.
- **Three reproductive edges for SPORE** — breeds fastest (short cooldown), cheapest (chance for free extra child), and furthest (2-cell range). Clear evolutionary advantage offsetting combat weakness.
- **Soil fertility geography** — random patches at init create resource hotspots. Migration pressure toward fertile zones. Territorial competition at oases.

</specifics>

<deferred>
## Deferred Ideas

- **Gestation reproduction** — invest energy over multiple ticks, abortable by combat/starvation. Higher risk, more strategic depth.
- **Litter spawning** — r-strategists spawn multiple offspring at proportional cost (not free bonus — different from D-18).
- **Type-aware composite role rates** — member's ParticleType modifies role drain. CATALYST FEEDER burns more but feeds faster. 18 potential combinations.
- **Composite size scaling** — larger composites have higher per-member overhead (coordination cost). Natural size cap via economics.
- **Composite member stat differentiation** — per-role stat modifiers (DEFENDER higher max energy, ATTACKER higher attack power). Pairs with per-type-role constraints (deferred from Phase 12 D-07).
- **Moving fertility via noise** — Perlin/simplex noise drift over time for soil fertility. Replaces static random patches with dynamic terrain.
- **Fertility regen from entity activity** — consume depletes nearby cells, dead entities fertilize (corpse composting).
- **Dispersal range for all types** — currently only SPORE has range 2; other types could gain range through evolution/bonding.
- **Dual HP + Energy model** — rejected for MVP but remains viable for future complexity if unified model proves limiting.

</deferred>

---

*Phase: 13-energy-metabolism-system*
*Context gathered: 2026-04-14*
