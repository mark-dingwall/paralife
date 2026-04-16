# Phase 14: Environmental Rules - Context

**Gathered:** 2026-04-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Richer environment — four spatially-propagating effects stress the metabolism system built in Phase 13:

1. **Toxin spread** — spline-path weather event, CA diffusion + decay, damages entities in its zone
2. **Mutagen outbreak** — strain-gossip cell infection, damage-over-time, survivors receive random buff
3. **Lightning strike** — instant dual-radius: high damage inner, fertility boost outer (nitrogen fixing)
4. **Corpse composting** — entity death bumps Cell.nutrientLevel on death cell + neighbors

Protocol/transport redesign (compact text perception, Jetty, precompress fan-out, stateless bots) **split into Phase 15**. Phase 14 env effects ship using **existing JSON protocol**.

</domain>

<decisions>
## Implementation Decisions

### Scope & Effect Selection
- **D-01:** Four environmental effects in MVP: toxin, mutagen, lightning, compost. Fire deferred post-MVP.
- **D-02:** Phase 13 fertility patches + seasonal cycle already delivered "food regeneration." Phase 14 effects are genuinely new — not retreading D-12/D-14 from Phase 13.
- **D-03:** Max 1 active event per type concurrently. Debuff stacking complexity deferred post-MVP (noted in deferred ideas).

### Toxin Spread
- **D-04:** Toxin spawns as seasonal Poisson weather event — independent from combat/death. Autumn peak.
- **D-05:** Path generated via Catmull-Rom spline adapted from user's microbe sketch algorithm (`~/kramtime/mark-dingwall.github.io/sketches/microbes/microbes.js`):
  1. Pick random point on edge A of grid (top/bottom/left/right)
  2. Pick random point on different edge B
  3. Generate N intermediate waypoints (config: 4–8) evenly spaced along A→B vector
  4. Offset each waypoint perpendicular by random amount (config: min/max offset in cells)
  5. Catmull-Rom interpolation through waypoints = toxin path
- **D-06:** Toxin head advances along path at configured speed (cells per tick). CA diffusion expands intensity from head position to surrounding cells.
- **D-07:** CA diffusion + decay model:
  - Each tick, toxin intensity at a cell spreads to neighbors by configured diffusion rate
  - Each tick, all cells decay intensity by configured decay rate
  - Cells below threshold intensity = toxin cleared (shadow grid cell = 0)
- **D-08:** Damage to entities in toxin zone proportional to cell intensity: `damage = baseDamage * intensity`
- **D-09:** Per-type toxin resistance coefficients. CATALYST/MEMBRANE/SPORE each have own multiplier (defaults TBD, Claude's Discretion).
- **D-10:** Attacker splash damage when attacking entity in toxic cell: `splashDamage = splashFraction * cellToxinIntensity`. Creates toxic zones as strategic cover for prey. Config: `splash-damage-fraction`.
- **D-11:** Toxin lifetime configurable (default guess: 80 ticks total from spawn to full decay).

### Mutagen Outbreak
- **D-12:** Mutagen spawns as seasonal Poisson weather event. Spring peak. Very rare outside spring (off-season λ = 0.0025).
- **D-13:** Propagation via **strain gossip**: stochastic neighbor infection with ±1 strain byte mutation per hop. Cell-level infection state on shadow grid, not entity-level. Entities moving through infected cells get infected.
- **D-14:** Infection duration per entity: configurable range (default 20–30 ticks). Entity takes damage-over-time while infected.
- **D-15:** On infection end, entity receives **survivor buff** (random pick, equal weight):
  - `+1 attack` — base attack power +1
  - `+1 movement` — hop-to-range-2 enabled (reuses SPORE reproduce-range=2 target-selection code; 8+16=24 movement candidates)
  - `+1 sensor range` — vision 5×5 → 7×7 (per-entity range)
  - `-1 energy upkeep` — decay rate -1; if already at 1, modulus skip (decay fires every other tick)
- **D-16:** Buff duration = infection duration × 10 (default: 200–300 ticks). Configurable via `buff-duration-multiplier`.
- **D-17:** Survivors receive temporary immunity (post-cure grace period). Config: `cure-ticks` (default 5).
- **D-18:** Composite member mutagen rewards differ: universal `-1 upkeep` + role-specific perk (avoids wasted rolls where e.g. `+1 attack` on a LOCOMOTOR is useless). Exact role-perk mapping = Claude's Discretion during planning.
- **D-19:** BuffState stored in shadow `BuffRegistry` (pattern match: BotRegistry, CompositeRegistry). Avoids bloating immutable Particle/CompositeMember records. Maps entity ID → active buffs + expiry ticks.
- **D-20:** **Attack-accelerates-cure mechanic**: each successful attack against MUTATING entity reduces remaining infection ticks by configured amount (`attack-cure-reduction`, default 3). If infection ticks reach 0, entity cured early and receives buff immediately. Creates a gamble — kill fast or don't engage; half-measures create stronger enemy.

### Lightning Strike
- **D-21:** Lightning spawns as seasonal Poisson weather event. Summer peak.
- **D-22:** Single-tick instant effect. Dual radius:
  - Inner radius: high damage to all entities in cells
  - Outer radius (ring between inner and outer): fertility boost (nitrogen fixing — `Cell.nutrientLevel += boost`)
- **D-23:** Bot flee behavior emerges from seeing aftermath in vision (damaged cells, dead entities, fertility spike). Lightning does **not** get a dedicated broadcast — bots react to what they perceive.

### Corpse Composting
- **D-24:** On entity death: 100% compost strength applied to death cell, 50% to 8 neighbors.
  ```java
  grid.set(deathPos, cell.withNutrientLevel(
      Math.min(cell.nutrientLevel() + COMPOST_FULL, MAX_NUTRIENT)));
  for (neighbor : grid.neighbors8(deathPos)) {
      grid.set(neighbor, nc.withNutrientLevel(
          Math.min(nc.nutrientLevel() + COMPOST_HALF, MAX_NUTRIENT)));
  }
  ```
- **D-25:** Config: `compost.full-strength` (default 30), `compost.half-strength` (default 15).

### Seasonal Poisson Triggering
- **D-26:** Each event type has peak season with sine-scaled λ during peak, flat off-season λ outside.
- **D-27:** Sine formula within peak season (verified correct):
  ```java
  double x = 2 * Math.PI * tickInSeason / SEASON_LENGTH;
  double lambda = 0.5 * (Math.sin(x - Math.PI / 2) + 1) * (peakLambda - offLambda) + offLambda;
  boolean fires = random.nextDouble() < lambda;
  ```
  Starts at offλ, smoothly ramps to peakλ at mid-season, ramps back down to offλ at season end. No discontinuity at season boundaries.
- **D-28:** Per-tick Poisson rolls (true Poisson process, memoryless). Skipped when another event of same type is already active.
- **D-29:** Winter: no events. Nutrient scarcity (from Phase 13 seasonal cycle) provides sufficient challenge. Contrast makes other seasons meaningful.
- **D-30:** Rate defaults:

| Event | Peak season | Peak λ | Off-season λ |
|---|---|---|---|
| Lightning | SUMMER | 0.04 | 0.005 |
| Toxin | AUTUMN | 0.03 | 0.005 |
| Mutagen | SPRING | 0.02 | 0.0025 |

### Storage Architecture — Shadow Grids
- **D-31:** Parallel shadow grids per effect type (not unified multi-effect grid). Decision rationale: cache locality during CA passes + additive evolution of effects.
  - `byte[][] toxinGrid` — intensity 0–255 per cell
  - `byte[][] mutagenGrid` — cell infection strain byte (0 = clean, 1–255 = strain id)
- **D-32:** Shadow grids owned by new `EnvironmentEngine` component.
- **D-33:** CA (cellular automaton) diffusion + decay chosen over reaction-diffusion / Gray-Scott. Rationale: entity-level RPS already provides sophisticated emergence; don't double-dip. Simpler CA sufficient for MVP.

### Environment–Entity Collision Resolution
- **D-34:** Server-side per-tick resolution. Entities moving through toxin/mutagen cells take damage / get infected on next tick. No client-side simulation.
- **D-35:** Environment state may be precomputed (e.g. toxin path geometrically deterministic), but entity–env collisions always resolved at server at tick time. Hybrid model: env state predictable, entity interactions authoritative.

### Status Bitmasks (for perception output)
- **D-36:** Base64 alphabet for status chars: `0-9A-Za-z_-` (64 values, 6 bits per char). Index 0 = `'0'`, index 10 = `'A'`, index 62 = `'_'`, index 63 = `'-'`.
- **D-37:** Two separate status fields per visible occupied cell: cell status char + entity status char. Fixed-width (always 2 chars when cell is occupied), not omit-if-zero. Parse simplicity > marginal bandwidth saving.
- **D-38:** Cell status bitmask (6 bits):

| Bit | Flag | Source |
|---|---|---|
| 0 | OVERCROWDED (vision-scoped) | Recomputed per bot using only visible neighbors |
| 1 | TOXIN_PRESENT | toxinGrid cell > threshold |
| 2 | MUTAGEN_ZONE | mutagenGrid cell != 0 |
| 3 | Reserved | — |
| 4 | Reserved | — |
| 5 | Reserved | — |

- **D-39:** Entity status bitmask (6 bits):

| Bit | Flag | Source |
|---|---|---|
| 0 | STARVING | Existing FLAG_STARVING / per-type starvation calc |
| 1 | TOXIC | Currently in toxin cell (intensity > 0) |
| 2 | MUTATING | Currently infected (BuffRegistry or infection map) |
| 3 | BUFFED | Active mutagen survivor buff |
| 4 | Reserved | — |
| 5 | Reserved | — |

- **D-40:** Vision-scoped overcrowding: computed per-bot from neighbors **within** bot's vision range. Cells at vision edge may appear NOT overcrowded to the bot even when globally overcrowded (out-of-vision neighbors unknown). Authoritative server-side penalty still applies regardless. Incomplete information = strategic depth. Zero-trust maintained.
- **D-41:** Per-tick status caches built during env processing: `Map<Position, Byte> cellStatusCache` + `Map<String, Byte> entityStatusCache`. Derived read-only projections from grid + registries. Rebuilt every tick. Not a second source of truth — same pattern as materialized views. HashMap O(1) lookup during perception encoding.
- **D-42:** BONDED and COMPOSITE_MEMBER are **not** entity status flags. They're distinct entity types in vision grammar (e.g. `B` for BondedPair, `X` for CompositeMember). Different entities, not entities with state.

### Behavioral Effects of Visible Status
- **D-43:** Behavioral mapping (for predator/prey decision-making in HeuristicBrain):
  - **STARVING:** +50% incoming damage vulnerability (max at floor, scales with intensity — Phase 13 progressive starvation). Predator RPS targets. Easy prey.
  - **TOXIC:** Attacking attacker takes splash damage proportional to cell toxin intensity. Toxic zones = dangerous hunting ground.
  - **MUTATING:** Each attack accelerates cure by `attack-cure-reduction` ticks. Gamble: kill fast or don't engage. Half-measures create buffed enemy.
  - **BUFFED:** Informational only. Bot knows entity is buffed but not which buff. Caution flag.
- **D-44:** Starvation effects are progressive (Phase 13 D-09/D-10). Not binary at STARVING flag — attacker doesn't know exact intensity. Another gamble layer.

### EnvironmentEngine Component
- **D-45:** New `@Component` `EnvironmentEngine` follows `SimulationEngine` pattern. `@EventListener` on `TickEvent` with `@Order` between existing SimulationEngine (10) and ActionResolver (20). Proposed `@Order(15)`.
- **D-46:** EnvironmentEngine responsibilities:
  - Roll seasonal Poisson for each event type
  - Spawn events (toxin path gen, mutagen origin, lightning strike point)
  - Advance active events one tick (toxin head motion, mutagen strain gossip, CA diffusion/decay)
  - Resolve entity–environment collisions (damage, infection)
  - Apply compost on death (hooked into SimulationEngine.processDeaths)
  - Build cellStatusCache + entityStatusCache for PerceptionBroadcaster
  - Update BuffRegistry (expiry checks, grant survivor buffs)
- **D-47:** `BuffRegistry` new `@Component` following BotRegistry/CompositeRegistry pattern. Shadow state for mutagen buffs. `Map<String, List<ActiveBuff>>` keyed by entity ID.

### Configuration
- **D-48:** All env config under `paralife.simulation.events.*`:
  ```yaml
  paralife.simulation.events:
    lightning:
      peak-season: SUMMER
      peak-lambda: 0.04
      off-season-lambda: 0.005
      inner-radius: 2
      outer-radius: 4
      damage: 40
      fertility-boost: 25
    toxin:
      peak-season: AUTUMN
      peak-lambda: 0.03
      off-season-lambda: 0.005
      path-points-min: 4
      path-points-max: 8
      path-offset-min: 5
      path-offset-max: 25
      speed: 3
      lifetime-ticks: 80
      diffusion-radius: 3
      decay-rate: 0.1
      splash-damage-fraction: 0.2
      resistance:
        catalyst: 1.0
        membrane: 0.7
        spore: 1.3
    mutagen:
      peak-season: SPRING
      peak-lambda: 0.02
      off-season-lambda: 0.0025
      infection-duration-min: 20
      infection-duration-max: 30
      buff-duration-multiplier: 10
      cure-ticks: 5
      attack-cure-reduction: 3
      damage-per-tick: 2
      strain-mutation-chance: 0.1
      gossip-probability: 0.3
    compost:
      full-strength: 30
      half-strength: 15
  ```

### Claude's Discretion
- Exact toxin per-type resistance defaults (D-09)
- Composite role-perk mapping for mutagen buffs (D-18)
- Shadow grid cell data type precision (byte vs int) and value scaling
- Exact CA diffusion kernel (Moore neighborhood vs Von Neumann, weighting)
- Poisson random source (shared `Random` vs dedicated env RNG seed for reproducibility)
- HeuristicBrain updates to react to new status flags (TOXIC avoidance, MUTATING gamble evaluation)
- Test strategy split across PLANs (each effect gets unit tests + integration test at end)
- Path generation: deterministic seed stored on event for future visualizer reconstruction

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Reference Implementation
- `~/kramtime/mark-dingwall.github.io/sketches/microbes/microbes.js` — toxin path generation algorithm (lines 207–223 for waypoint generation, 242–254 for Catmull-Rom spline evaluation). Adapt to grid cells, not pixels.

### Entity Model
- `src/main/java/com/paralife/world/Entity.java` — Sealed Entity hierarchy
- `src/main/java/com/paralife/world/Cell.java` — Cell record with flags (add new FLAG_TOXIC / FLAG_MUTAGEN_ZONE if cell flags used alongside shadow grids) and nutrientLevel (used by compost D-24)

### Simulation Engine
- `src/main/java/com/paralife/engine/SimulationEngine.java` — Pattern for new EnvironmentEngine; processDeaths() hook point for compost (D-24); processOvercrowding() pattern referenced by D-40
- `src/main/java/com/paralife/engine/SeasonTracker.java` — Phase 13 season phase computation, reused for Poisson triggering (D-26)
- `src/main/java/com/paralife/engine/SeasonsConfig.java` — Phase 13 seasonal config, year-length-ticks reused for Poisson phase calculation
- `src/main/java/com/paralife/engine/BotRegistry.java` / `src/main/java/com/paralife/engine/CompositeRegistry.java` — Shadow registry pattern for BuffRegistry (D-19, D-47)
- `src/main/java/com/paralife/engine/ActionResolver.java` — resolveReproduce uses SPORE reproduce-range=2 code reused for `+1 movement` buff (D-15)
- `src/main/java/com/paralife/engine/MetabolicProfile.java` / `StarvationConfig.java` — Phase 13 starvation mechanics referenced by D-43/D-44

### WorldGrid
- `src/main/java/com/paralife/world/WorldGrid.java` — neighbors8() method for compost falloff (D-24), vision-scoped overcrowding (D-40)

### Perception & Messages
- `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` — Consumer of cellStatusCache + entityStatusCache (D-41) for status field encoding in Perception message
- `src/main/java/com/paralife/websocket/Messages.java` — CellView extension: add `cellStatus` (byte) and `entityStatus` (byte) fields. Existing 3-arg constructor stays for back-compat.

### Configuration
- `src/main/resources/application.yml` — New `paralife.simulation.events.*` section (D-48)

### Prior Phase Context
- `.planning/phases/13-energy-metabolism-system/13-CONTEXT.md` — Seasonal infrastructure (D-13 through D-15), progressive starvation (D-09/D-10), FLAG_STARVING pattern, Cell.nutrientLevel activation

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SeasonTracker` — compute current season + tick-in-season for Poisson sine modulation
- `BotRegistry` / `CompositeRegistry` — shadow registry pattern for `BuffRegistry`
- `Cell.withNutrientLevel()` — immutable mutation for compost application
- `WorldGrid.neighbors8()` — 8-neighbor lookup for compost falloff and overcrowding scoping
- SPORE reproduce-range=2 target selection code — reused for `+1 movement` buff hop-to-range-2
- `Cell.FLAG_STARVING` / `FLAG_OVERCROWDED` — pattern for any new cell flags (though shadow grids preferred over flags for intensity-valued effects)
- `@EventListener` + `@Order` — tick pipeline component pattern

### Established Patterns
- Immutable records for all config (`@ConfigurationProperties`)
- Shadow registries for cross-cutting state avoiding entity record bloat
- Snapshot-read + deferred-write in tick pipeline phases
- Spring `@Component` + constructor injection throughout

### Integration Points
- `SimulationEngine.processDeaths()` — compost hook (D-24): on entity removal, apply nutrient bump to cell + neighbors
- `SimulationEngine.processOvercrowding()` — reference implementation for D-40 vision-scoped recomputation (don't reuse directly; separate per-bot calc)
- `PerceptionBroadcaster` — read cellStatusCache + entityStatusCache to populate new CellView status fields
- Tick pipeline: `EnvironmentEngine @Order(15)` between SimulationEngine(10) and ActionResolver(20) — so env damage/infection resolves before bot actions process, and so shadow grids are fresh for perception at @Order(50)
- `Messages.CellView` — extend with cellStatus + entityStatus byte fields; keep 3-arg constructor for back-compat during transition

</code_context>

<specifics>
## Specific Ideas

- **Strain gossip with mutation** — mutagen strain byte drifts ±1 per propagation hop. Creates visual/behavioral heterogeneity in outbreak zones (adjacent infected cells may have different strains → future post-MVP enables strain-specific behavior). MVP: strain byte exists but all strains behave identically.
- **Attack-accelerates-cure gamble** — sharpest tactical layer in Phase 14. Predator facing MUTATING entity: commit to kill (bet energy on finishing before cure) or disengage (denies self reward but avoids creating buffed enemy). Pack hunting naturally emerges — solo predator can't reach kill threshold before cure triggers.
- **Vision-scoped overcrowding** — server authoritative, but computed per-bot from visible neighbors only. Bot at vision edge may misread cell as non-overcrowded. Natural incomplete-information model without client-side calculation.
- **Toxic zones as cover** — prey in toxic cells harder to hunt (attacker takes splash). Emergent tactical terrain without scripting it. Expect observed behavior: weak entities gravitate toward toxin edges.
- **Composite role-aware buff rewards** — composite member mutagen rewards use role-specific perk tables (+ universal `-1 upkeep`). Avoids e.g. `+1 attack` on LOCOMOTOR waste.
- **Shadow grid parallelism** — independent grids per effect trivially parallelizable in future (per-effect virtual thread doing its own CA pass). Shows up as perf lever in interview narrative.

</specifics>

<deferred>
## Deferred Ideas

### Post-MVP Priority #1
- **Bot memory / fog of war** — clients cache vision across ticks, enabling exploration incentive, object permanence, multi-tick planning (pathfinding, flee routes, hunting patterns), mental models of other entities. Unlocks significant behavioral richness.

### Mutagen Evolution
- **Permanent mutation** — entity surviving N mutagen infections has buff become permanent trait. Evolutionary pressure mechanic. Seeds genetic/inheritance system.
- **Contagion via combat** — attacking MUTATING entity infects attacker. Currently rejected (overlaps with strain gossip; double-punishes attackers who already take splash from TOXIC). Revisit if combat needs more risk.
- **Strain-specific behavior** — different strain bytes produce different buff weightings or damage profiles. MVP: byte exists but uniform behavior.
- **Carrier-entity plague** — disease propagates via infected entity movement, not cell-level gossip. Deferred explicitly as "plague post-MVP" during discussion.

### Cell Effects Visibility
- **HIGH_FERTILITY cell status bit** — locked post-MVP buff: entity with this sensor upgrade can see fertility levels of visible cells. Adds another reward to mutagen survivor pool.

### Combat & Status
- **Debuff stacking** — multiple concurrent events of same type layering damage/effects. Rejected MVP (max 1 per type). Revisit with balance data.
- **Line-of-sight (LOS)** — rocks/obstacles block vision. Recommended server-side shadowcasting when added. Not MVP.

### Protocol & Transport (Phase 15)
- Compact text perception protocol with sparse coords, status bitmasks, fixed-width fields
- Stateless bot redesign (no client-side state, no desync problem class)
- Zero-trust perception model (server filters, no privileged data to client)
- Tomcat → Jetty swap
- `permessage-deflate` with `server_no_context_takeover=true`
- Precompress fan-out for visualizer broadcast channel
- Actuator metrics (bytes saved, compress ops saved, active sessions)
- Rock generation algorithm + rock run-length encoding

### Environmental Effects (Post-MVP Expansion)
- **Fire/blight** — explicitly dropped from MVP during scope lock
- **Earthquake / flood** — forced movement effects (would change case for sending absolute position in perception)
- **Moving fertility via Perlin noise** — replace static random patches with dynamic drift (Phase 13 deferred)
- **Winter weather events** — e.g. blizzard. MVP winter intentionally quiet.

### Reproduction
- **Gestation reproduction** — invest energy over multiple ticks, abortable by combat/starvation (Phase 13 deferred)
- **Litter spawning** — multiple offspring at proportional cost (Phase 13 deferred)

</deferred>

---

*Phase: 14-environmental-rules*
*Context gathered: 2026-04-17*
*Companion phase: 15 (Protocol & Transport Overhaul) — compact text perception, Jetty, precompress fan-out, stateless bots. Phase 14 ships env effects with existing JSON protocol.*
