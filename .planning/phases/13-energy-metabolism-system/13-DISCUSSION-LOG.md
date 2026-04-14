# Phase 13: Energy & Metabolism System - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 13-energy-metabolism-system
**Areas discussed:** Per-type metabolic rates, Starvation mechanic, Cell.nutrientLevel activation, Reproduction surplus gating

---

## Energy Architecture (HP vs Energy)

| Option | Description | Selected |
|--------|-------------|----------|
| Unified energy | Single energy field for health + metabolism + reproduction. Starvation = fragility. | ✓ |
| Dual HP + Energy | Separate HP (combat) and energy (metabolism). Starvation doesn't weaken combat. | |
| Unified + starvation debuffs | Single energy with graduated threshold debuffs. | |

**User's choice:** Unified energy
**Notes:** Keeps codebase simple. Composites already layer complexity via dual energy (individual + pool). Adding HP would triple composite accounting.

---

## Per-Type Metabolic Rates

### Per-type config knobs

| Option | Description | Selected |
|--------|-------------|----------|
| Decay + combat transfer | 2 knobs per type (6 total) | |
| Full metabolic profile | 4+ knobs per type (12+ total) | ✓ |
| Uniform rates, defer to composites | Keep all rates flat | |

**User's choice:** Full metabolic profile
**Notes:** User added attack power as additional knob. Final profile: 10 knobs per type.

### Type archetypes (user correction)

Original proposal had MEMBRANE as r-strategist. User corrected:
- CATALYST = fast hungry predator (high decay/attack/combat)
- MEMBRANE = efficient defensive grazer (low decay/high nutrient gain)
- SPORE = r-strategist breeder (low reproduce cost/short cooldown)

Archetypes match names semantically.

### Per-type max energy

| Option | Description | Selected |
|--------|-------------|----------|
| Per-type max energy | Each type gets own max energy capacity | ✓ |
| Flat max energy | Keep 100 for all | |

**User's choice:** Per-type max energy

### BondedPair metabolic rates

| Option | Description | Selected |
|--------|-------------|----------|
| Inherit from primary | Use predator's metabolic profile | |
| Average both types | Blend primary + secondary rates | ✓ |
| Own flat profile | Independent config for BondedPairs | |

**User's choice:** Average both types with hybrid vigor formula
**Notes:** User specified formula: `avg(A,B) + (max(A,B) - avg(A,B)) * random(bonusMin, bonusMax)`. Bonding strictly beneficial.

### BondedPair decay

User rejected all presented options and specified own formula:
`sum(A,B) * random(bondDecayCostMin, bondDecayCostMax)`
Bonding always reduces total metabolic cost. Two configurable range knobs.

### Composite per-role rates

| Option | Description | Selected |
|--------|-------------|----------|
| Scale by composite size | Higher per-member overhead for larger composites | |
| Type-aware role rates | Member's ParticleType modifies role drain | |
| Leave as-is | Keep existing CompositeConfig rates | ✓ |

**User's choice:** Leave as-is for MVP
**Notes:** Code structured for future type-awareness. Composites should receive modest metabolic discount — emergence uncommon but rewarded.

### Reproduction mechanic

| Option | Description | Selected |
|--------|-------------|----------|
| Instant + cooldown | Spend energy, child appears, cooldown per type | ✓ |
| Gestation | Invest energy over time, abortable | |
| Instant, no cooldown | Current mechanic | |
| Litter spawning | Multiple offspring at proportional cost | |

**User's choice:** Instant + cooldown
**Notes:** Gestation deferred to post-MVP.

### SPORE multi-spawn bonus

User rejected initial options (pay per child — not a real bonus). Requested genuine evolutionary advantage.

Final decision: Free bonus offspring + dispersal range.
- `bonus-offspring-chance: 0.25` — 25% chance for free extra child
- `reproduce-range: 2` — spawn 2 cells away
- Per-type config (available to all types, CATALYST/MEMBRANE default to 0.0/1)

---

## Starvation Mechanic

### Starvation effects

| Option | Description | Selected |
|--------|-------------|----------|
| Threshold debuffs | Below threshold: can't reproduce, reduced combat | ✓ (modified) |
| Accelerated decay | Below threshold: decay rate doubles | |
| Both debuffs + accelerated decay | Maximum starvation pressure | |

**User's choice:** Threshold debuffs, but modified — mix of buffs AND debuffs (desperate survival mode)

User specified:
- **Buff:** Attack power boost (desperate aggression)
- **Buff:** Nutrient gain boost (desperate feeding efficiency)
- **Debuff:** Cannot reproduce
- **Debuff:** Increased incoming damage (vulnerability)
- **Debuff:** FLAG_STARVING visible in perception (hyenas/vultures circling)
- All effects scale progressively from threshold to floor

### Starvation thresholds

| Option | Description | Selected |
|--------|-------------|----------|
| Per-type thresholds | Each type enters starvation at different % | ✓ |
| Global thresholds | Same % for all types | |

**User's choice:** Per-type thresholds (threshold + floor in metabolic profile)

### Starvation multipliers

| Option | Description | Selected |
|--------|-------------|----------|
| Global multipliers | Same max boost/vulnerability for all types | ✓ |
| Per-type multipliers | Each type has own max boost/vulnerability | |

**User's choice:** Global multipliers (3 config values)

---

## Cell.nutrientLevel Activation

### What nutrientLevel represents

| Option | Description | Selected |
|--------|-------------|----------|
| Soil fertility (spawns Nutrients) | High level = faster Nutrient entity spawning | ✓ |
| Passive feeding source | Entities on high-nutrient cells gain energy passively | |
| Replace Nutrient entities entirely | Remove Nutrient entity type | |
| Nutrient entity quality modifier | Level modifies energy gained from consuming | |

**User's choice:** Soil fertility
**Notes:** Deferred: moving fertility using noise (Perlin/simplex drift over time)

### Initial fertility distribution

| Option | Description | Selected |
|--------|-------------|----------|
| Random patches | Scatter fertile patches at world init | ✓ |
| Uniform then diverge | Start equal, diverge from entity activity | |
| Gradient bands | Horizontal or radial gradient | |

**User's choice:** Random patches
**Notes:** Starting point — will be replaced by noise-based initial state eventually.

### Fertility regeneration

| Option | Description | Selected |
|--------|-------------|----------|
| Deplete on spawn, regen when empty | Entity activity drives depletion/recovery | |
| Slow global decay + regen | Global cycle, framed as seasons | ✓ |
| Entity-activity driven only | No passive regen, corpse composting | |

**User's choice:** Slow global decay + regen, framed as seasonal sine wave
**Notes:** User specified sine wave formula for seasonal cycle. Year length = full cycle. Season and multiplier visible in tick output.

### Seasonal cycle target

| Option | Description | Selected |
|--------|-------------|----------|
| Nutrient spawn rate only | Sine wave modulates spawn probability | ✓ |
| Both spawn rate + fertility regen | Double seasonal impact | |
| Fertility regen only | Local geographic effect | |

**User's choice:** Nutrient spawn rate only
**Notes:** User corrected formula: `2πt / yearLength` (full year cycle, not season length).

---

## Reproduction Surplus Gating

### Surplus gate mechanic

| Option | Description | Selected |
|--------|-------------|----------|
| Minimum post-reproduction energy | Must remain above starvation threshold after cost | ✓ |
| Percentage of max energy | Must have N% to start | |
| Must not be starving (simple) | Starvation blocks reproduction | |

**User's choice:** Minimum post-reproduction energy

### Entity type consistency

| Option | Description | Selected |
|--------|-------------|----------|
| Same rule, all entity types | Same formula for Particle, BondedPair, Composite | ✓ |
| Different thresholds for composites | Use panic zone threshold instead | |

**User's choice:** Same rule, all entity types
**Notes:** Composite members self-regulate via pool depletion — if pool empty, can't draw energy, can't reproduce.

---

## Scope Decision

### Composite member stat differentiation

| Option | Description | Selected |
|--------|-------------|----------|
| Defer to later phase | Per-role stat modifiers (DEFENDER max energy, ATTACKER power) in own phase | ✓ |
| Include in Phase 13 | Add to this phase alongside metabolism rework | |

**User's choice:** Defer

---

## Claude's Discretion

- Config record organization
- Hybrid vigor and bond decay cost calculation timing
- Fertility patch generation algorithm
- FLAG_STARVING implementation
- Season phase naming for tick output
- Per-type metabolic lookup caching
- Reproduce range target cell selection

## Deferred Ideas

- Gestation reproduction (invest energy over time)
- Litter spawning at proportional cost
- Type-aware composite role rates
- Composite size scaling
- Composite member stat differentiation (per-role modifiers)
- Moving fertility via noise (Perlin/simplex drift)
- Fertility regen from entity activity (corpse composting)
- Dual HP + Energy model
