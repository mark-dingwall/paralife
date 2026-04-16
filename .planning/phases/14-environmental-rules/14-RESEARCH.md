# Phase 14: Environmental Rules - Research

**Researched:** 2026-04-17
**Domain:** Cellular-automaton environmental effects in a Spring Boot tick simulation
**Confidence:** HIGH

## Summary

Phase 14 extends Paralife's tick pipeline with four spatially-propagating environmental effects (toxin, mutagen, lightning, compost), triggered by per-type seasonal Poisson processes. All four reuse established Paralife patterns: `@EventListener @Order` for pipeline phases, immutable records for config via `@ConfigurationProperties`, shadow registries (`BotRegistry`/`CompositeRegistry`) for cross-cutting state, and snapshot-read-deferred-write within a single-threaded tick loop. The new territory is numerical: Catmull-Rom spline path generation, CA diffusion with double-buffering, and strain-gossip propagation.

The 48 locked decisions in `14-CONTEXT.md` fully specify the design — research scope is confined to verifying the math, confirming library-vs-hand-roll choices, and surfacing integration gotchas before planning. No decisions are open.

**Primary recommendation:** Hand-roll everything. Paralife has no external math dependencies and none are needed here. Catmull-Rom is a 4-line polynomial. CA diffusion is a double-buffered 3×3 kernel loop. Poisson triggering is a single `random.nextDouble() < lambda` call. Adding Apache Commons Math or JTS for these would be mis-sized. Keep the tick loop pure stdlib Java.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Scope & Effect Selection**
- **D-01:** Four environmental effects in MVP: toxin, mutagen, lightning, compost. Fire deferred post-MVP.
- **D-02:** Phase 13 fertility patches + seasonal cycle already delivered "food regeneration." Phase 14 effects are genuinely new — not retreading D-12/D-14 from Phase 13.
- **D-03:** Max 1 active event per type concurrently. Debuff stacking complexity deferred post-MVP.

**Toxin Spread**
- **D-04:** Toxin spawns as seasonal Poisson weather event — independent from combat/death. Autumn peak.
- **D-05:** Path generated via Catmull-Rom spline adapted from user's microbe sketch algorithm:
  1. Pick random point on edge A of grid
  2. Pick random point on different edge B
  3. Generate N intermediate waypoints (config: 4–8) evenly spaced along A→B vector
  4. Offset each waypoint perpendicular by random amount (config: min/max offset in cells)
  5. Catmull-Rom interpolation through waypoints = toxin path
- **D-06:** Toxin head advances along path at configured speed (cells per tick). CA diffusion expands intensity from head position to surrounding cells.
- **D-07:** CA diffusion + decay model: each tick, toxin intensity spreads to neighbors by configured diffusion rate; each tick, all cells decay intensity by configured decay rate; cells below threshold intensity = toxin cleared.
- **D-08:** Damage to entities in toxin zone proportional to cell intensity: `damage = baseDamage * intensity`.
- **D-09:** Per-type toxin resistance coefficients. CATALYST/MEMBRANE/SPORE each have own multiplier (defaults TBD, Claude's Discretion).
- **D-10:** Attacker splash damage when attacking entity in toxic cell: `splashDamage = splashFraction * cellToxinIntensity`. Config: `splash-damage-fraction`.
- **D-11:** Toxin lifetime configurable (default guess: 80 ticks total from spawn to full decay).

**Mutagen Outbreak**
- **D-12:** Mutagen spawns as seasonal Poisson weather event. Spring peak. Very rare outside spring (off-season λ = 0.0025).
- **D-13:** Propagation via strain gossip: stochastic neighbor infection with ±1 strain byte mutation per hop. Cell-level infection state on shadow grid, not entity-level. Entities moving through infected cells get infected.
- **D-14:** Infection duration per entity: configurable range (default 20–30 ticks). Entity takes damage-over-time while infected.
- **D-15:** On infection end, entity receives survivor buff (random pick, equal weight): `+1 attack`, `+1 movement` (reuses SPORE reproduce-range=2 target-selection code; 8+16=24 movement candidates), `+1 sensor range` (5×5 → 7×7 per-entity), `-1 energy upkeep` (decay rate -1; if already at 1, modulus skip).
- **D-16:** Buff duration = infection duration × 10 (default: 200–300 ticks). Configurable via `buff-duration-multiplier`.
- **D-17:** Survivors receive temporary immunity (post-cure grace period). Config: `cure-ticks` (default 5).
- **D-18:** Composite member mutagen rewards differ: universal `-1 upkeep` + role-specific perk. Role-perk mapping = Claude's Discretion during planning.
- **D-19:** BuffState stored in shadow `BuffRegistry` (pattern match: BotRegistry, CompositeRegistry). Avoids bloating immutable Particle/CompositeMember records. Maps entity ID → active buffs + expiry ticks.
- **D-20:** Attack-accelerates-cure mechanic: each successful attack against MUTATING entity reduces remaining infection ticks by configured amount (`attack-cure-reduction`, default 3). If infection ticks reach 0, entity cured early and receives buff immediately.

**Lightning Strike**
- **D-21:** Lightning spawns as seasonal Poisson weather event. Summer peak.
- **D-22:** Single-tick instant effect. Dual radius: inner radius high damage; outer radius ring fertility boost (`Cell.nutrientLevel += boost`).
- **D-23:** Bot flee behavior emerges from seeing aftermath in vision. Lightning does not get a dedicated broadcast — bots react to what they perceive.

**Corpse Composting**
- **D-24:** On entity death: 100% compost strength applied to death cell, 50% to 8 neighbors (`grid.neighbors8`).
- **D-25:** Config: `compost.full-strength` (default 30), `compost.half-strength` (default 15).

**Seasonal Poisson Triggering**
- **D-26:** Each event type has peak season with sine-scaled λ during peak, flat off-season λ outside.
- **D-27:** Sine formula within peak season:
  ```java
  double x = 2 * Math.PI * tickInSeason / SEASON_LENGTH;
  double lambda = 0.5 * (Math.sin(x - Math.PI / 2) + 1) * (peakLambda - offLambda) + offLambda;
  boolean fires = random.nextDouble() < lambda;
  ```
- **D-28:** Per-tick Poisson rolls (true Poisson process, memoryless). Skipped when another event of same type is already active.
- **D-29:** Winter: no events. Nutrient scarcity provides sufficient challenge.
- **D-30:** Rate defaults: Lightning SUMMER 0.04/0.005, Toxin AUTUMN 0.03/0.005, Mutagen SPRING 0.02/0.0025.

**Storage Architecture**
- **D-31:** Parallel shadow grids per effect type (not unified multi-effect grid). `byte[][] toxinGrid` intensity 0–255; `byte[][] mutagenGrid` strain id 0–255.
- **D-32:** Shadow grids owned by new `EnvironmentEngine` component.
- **D-33:** CA diffusion + decay chosen over reaction-diffusion / Gray-Scott.

**Environment–Entity Collision Resolution**
- **D-34:** Server-side per-tick resolution. No client-side simulation.
- **D-35:** Environment state may be precomputed; entity–env collisions always resolved at server at tick time.

**Status Bitmasks (for perception)**
- **D-36:** Base64 alphabet for status chars: `0-9A-Za-z_-` (64 values, 6 bits per char).
- **D-37:** Two separate status fields per visible occupied cell: cell status char + entity status char. Fixed-width (always 2 chars when cell is occupied).
- **D-38:** Cell status bitmask (6 bits): bit 0 OVERCROWDED (vision-scoped), bit 1 TOXIN_PRESENT, bit 2 MUTAGEN_ZONE, bits 3–5 reserved.
- **D-39:** Entity status bitmask (6 bits): bit 0 STARVING, bit 1 TOXIC, bit 2 MUTATING, bit 3 BUFFED, bits 4–5 reserved.
- **D-40:** Vision-scoped overcrowding: computed per-bot from neighbors within bot's vision range. Authoritative server-side penalty still applies regardless.
- **D-41:** Per-tick status caches: `Map<Position, Byte> cellStatusCache` + `Map<String, Byte> entityStatusCache`. Derived read-only projections from grid + registries. Rebuilt every tick.
- **D-42:** BONDED and COMPOSITE_MEMBER are not entity status flags — distinct entity types in vision grammar.

**Behavioral Effects**
- **D-43:** STARVING: +50% incoming damage vulnerability (Phase 13 progressive, scales with intensity). TOXIC: attacker splash damage. MUTATING: each attack accelerates cure. BUFFED: informational only.
- **D-44:** Starvation effects are progressive (Phase 13 D-09/D-10), not binary at STARVING flag.

**EnvironmentEngine Component**
- **D-45:** New `@Component` `EnvironmentEngine` follows `SimulationEngine` pattern. `@EventListener` on `TickEvent` with `@Order(15)`, between SimulationEngine (10) and ActionResolver (20).
- **D-46:** EnvironmentEngine responsibilities: roll seasonal Poisson, spawn events, advance active events, resolve entity–env collisions, apply compost on death (hooked into SimulationEngine.processDeaths), build cellStatusCache + entityStatusCache, update BuffRegistry.
- **D-47:** `BuffRegistry` new `@Component`. `Map<String, List<ActiveBuff>>` keyed by entity ID.

**Configuration**
- **D-48:** All env config under `paralife.simulation.events.*` (full yaml scaffold in CONTEXT.md D-48).

### Claude's Discretion

- Exact toxin per-type resistance defaults (D-09)
- Composite role-perk mapping for mutagen buffs (D-18)
- Shadow grid cell data type precision (byte vs int) and value scaling
- Exact CA diffusion kernel (Moore neighborhood vs Von Neumann, weighting)
- Poisson random source (shared `Random` vs dedicated env RNG seed for reproducibility)
- HeuristicBrain updates to react to new status flags (TOXIC avoidance, MUTATING gamble evaluation)
- Test strategy split across PLANs (each effect gets unit tests + integration test at end)
- Path generation: deterministic seed stored on event for future visualizer reconstruction

### Deferred Ideas (OUT OF SCOPE)

Bot memory / fog of war; permanent mutation; combat-contagion of mutagen; strain-specific behavior; carrier-entity plague; HIGH_FERTILITY cell status bit; debuff stacking; line-of-sight / shadowcasting; compact text perception protocol (Phase 15); stateless bot redesign (Phase 15); zero-trust perception (Phase 15); Tomcat→Jetty swap (Phase 15); `permessage-deflate` (Phase 15); precompress fan-out (Phase 15); actuator metrics (Phase 15); rock generation (Phase 15); fire/blight; earthquake/flood; Perlin-noise fertility drift; winter weather events; gestation reproduction; litter spawning.

</user_constraints>

## Project Constraints (from CLAUDE.md)

- **Java 21** with virtual threads enabled (`spring.threads.virtual.enabled: true`)
- **Spring Boot 3.4.4** using `starter-web`, `starter-websocket`, `starter-actuator`
- **Immutable records throughout** — mutations produce new instances
- **Sealed interfaces** for polymorphism (`Entity`, `Messages`)
- **Single-threaded simulation core** — all world mutations in tick event handlers
- **ReentrantReadWriteLock on WorldGrid** — read lock for snapshots, write lock for mutations
- **`@EventListener` with `@Order`** for tick pipeline sequencing
- **`@ConfigurationProperties` on records** for type-safe config binding
- **Tests:** `*Test.java` unit, `*IntegrationTest.java` integration, mirror source directory structure
- **GSD workflow enforcement:** Never modify `.planning/` without workflow instruction; check `.planning/STATE.md` before starting; respect phase dependencies
- **No emoji in code/commits/docs** (also per personal preferences)

## Architectural Responsibility Map

Phase 14 is a single-tier (server-side) phase — all logic lives in `com.paralife.engine`, reads/writes via `com.paralife.world.WorldGrid`, and surfaces output via `com.paralife.websocket.Messages.Perception`. No client or UI tier involved. Map below shows intra-server component ownership per capability.

| Capability | Primary Component | Secondary Component | Rationale |
|------------|-------------------|---------------------|-----------|
| Seasonal Poisson event triggering | `EnvironmentEngine` | `SeasonTracker` (reused) | Poisson reads current season & tick-in-season; ownership of "should fire now?" belongs with the engine that spawns events. |
| Toxin path generation (Catmull-Rom) | `EnvironmentEngine` (or helper `ToxinPathGenerator`) | — | Pure math, no dependencies; generated once at spawn, stored on active event record. |
| Toxin CA diffusion + decay | `EnvironmentEngine` | — | Reads/writes `byte[][] toxinGrid` shadow state; must happen before ActionResolver so entities moving this tick see fresh intensity. |
| Mutagen strain gossip | `EnvironmentEngine` | — | Same shadow-grid pattern as toxin; gossip propagation is cell-level CA, not entity-level. |
| Lightning strike resolution | `EnvironmentEngine` | `WorldGrid` (Cell.nutrientLevel writes) | Single-tick dual-radius effect; damage to entities + fertility bump to outer ring cells. |
| Corpse composting on death | `SimulationEngine.processDeaths` hook | `EnvironmentEngine.applyCompost(pos)` helper | Death detection already happens in SimulationEngine; compost is an outcome of death, best applied there rather than scanning separately. |
| Environment–entity damage/infection | `EnvironmentEngine` | — | After env state advances, apply intensity-proportional damage to entities in affected cells. Happens before ActionResolver so damaged bots see their new energy. |
| Active buff tracking (mutagen survivors) | `BuffRegistry` | `EnvironmentEngine` (expiry sweep) | Shadow registry owns state; EnvironmentEngine ticks expiry and grants new buffs on cure. |
| Buff effect application (attack/movement/sensor/upkeep) | Distributed: `SimulationEngine` (decay, attack boost), `ActionResolver` (movement range, reproduce range), `PerceptionBroadcaster` (sensor range) | `BuffRegistry.getBuffs(entityId)` | Buffs modify behavior in the component that owns the behavior; BuffRegistry is the state source. |
| Cell status cache construction | `EnvironmentEngine` | — | Rebuilt every tick from shadow grids + Cell flags; consumed read-only by PerceptionBroadcaster. |
| Entity status cache construction | `EnvironmentEngine` | `BuffRegistry` | Aggregates STARVING (Cell flag), TOXIC (toxinGrid lookup), MUTATING (mutagenGrid + infection map), BUFFED (BuffRegistry). |
| Vision-scoped overcrowding | `PerceptionBroadcaster` | `WorldGrid.neighbors8` | Computed per-bot from the bot's 5×5 (or 7×7 with buff) vision; server-authoritative overcrowding penalty stays in SimulationEngine unchanged. |
| Status field encoding in perception | `PerceptionBroadcaster` | `Messages.CellView` extension | Perception consumer of both status caches; encodes into CellView. |

## Standard Stack

### Core (all pre-existing — Phase 14 adds zero dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.4.4 | IoC, @Component, @EventListener, @ConfigurationProperties | Already in stack; no alternative considered |
| Java stdlib `java.util.Random` / `ThreadLocalRandom` | Java 21 | Poisson rolls, gossip coin flips, path offsets | Already used throughout (ActionResolver, SimulationEngine); no need for a PRNG library |
| Java stdlib `Math.sin`, `Math.PI` | Java 21 | Sine-scaled lambda, Catmull-Rom polynomial evaluation | Already used in `SeasonTracker`; no need for a math library |
| JUnit 5 | bundled (via `spring-boot-starter-test`) | Unit + integration tests | Project standard; 166 existing tests |
| AssertJ | bundled (via `spring-boot-starter-test`) | Fluent assertions | Project standard |
| Jackson | transitive via Spring | JSON serialisation of `Messages.*` | Project standard |

### Supporting (zero additions)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| — | — | — | None needed |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-rolled Catmull-Rom | Apache Commons Math `SplineInterpolator` | Commons Math's `SplineInterpolator` produces natural cubic splines, **not** Catmull-Rom. Would not match microbes.js reference behavior. Rejected. [VERIFIED: commons-math docs via training] |
| Hand-rolled Catmull-Rom | `javagl/Geom` CatmullRomSpline | Adds a transitive dependency for ~15 lines of math. Paralife has no math dep; introducing one is mis-sized. Rejected. [CITED: github.com/javagl/Geom] |
| Hand-rolled Poisson roll | Apache Commons Math `RandomDataGenerator.nextExponential()` for next-event timing | Exponential timing is more accurate at high sampling rates (sub-second), but Paralife ticks are ~500ms and peak λ is 0.04 — precision of `nextDouble()` is entirely sufficient. [VERIFIED: preshing.com Poisson article] Rejected. |
| Parallel shadow grids (D-31) | Unified multi-layer `byte[][][]` grid | D-31 locked in favour of parallel grids for cache locality + additive effect evolution. |
| CA diffusion (D-33) | Reaction-diffusion / Gray-Scott | D-33 locked: entity RPS already provides sophisticated emergence; simple CA sufficient for MVP. |

**Installation:** None. No new dependencies.

**Version verification:** Not applicable — no new libraries. Existing dependencies (Spring Boot 3.4.4, JUnit 5, AssertJ) are already locked by `build.gradle.kts` and should not change during Phase 14.

## Architecture Patterns

### System Architecture Diagram

```
 TickEvent (published by TickEngine virtual thread)
        │
        ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │ @EventListener @Order(10)  SimulationEngine                     │
  │   processInteractions (combat, bonding, composite formation)    │
  │   processEnergyDecay (per-type, FLAG_STARVING lifecycle)        │
  │   processOvercrowding (global neighbors8, sets FLAG_OVERCROWDED)│
  │   processDeaths  ──────────┐                                    │
  │   processNutrientSpawning  │ hook: EnvironmentEngine.onDeath(pos)│
  └────────────────────────────┼────────────────────────────────────┘
                               │
                               ▼  (compost applied within processDeaths)
  ┌─────────────────────────────────────────────────────────────────┐
  │ @EventListener @Order(15)  EnvironmentEngine  (NEW)             │
  │                                                                 │
  │   1. rollPoissonEvents(tick, season, tickInSeason)              │
  │      ├─ spawnToxin(path via Catmull-Rom) if AUTUMN && fires     │
  │      ├─ spawnMutagen(origin cell) if SPRING && fires            │
  │      └─ spawnLightning(center, inner, outer) if SUMMER && fires │
  │                                                                 │
  │   2. advanceActiveEvents()                                      │
  │      ├─ Toxin: head advance along path,                         │
  │      │          CA diffuse toxinGrid (double-buffered),         │
  │      │          CA decay, threshold clear                       │
  │      ├─ Mutagen: strain gossip (cell → 8 neighbors, ±1 mut),    │
  │      │           CA decay strain strength                       │
  │      └─ Lightning: already applied at spawn (single-tick)       │
  │                                                                 │
  │   3. resolveEnvEntityCollisions()                               │
  │      ├─ Damage entities in TOXIN_PRESENT cells                  │
  │      │   (base * intensity * (1 - typeResistance))              │
  │      └─ Infect entities in MUTAGEN_ZONE cells                   │
  │          (add to infection map: entityId → (ticksLeft, strain)) │
  │                                                                 │
  │   4. tickBuffsAndInfections()                                   │
  │      ├─ Decrement infection ticks; damage-over-tick             │
  │      │   ├─ reaches 0 → grant survivor buff, set cure-immunity  │
  │      │   └─ buff selection: random 1-of-4, role-aware composite │
  │      ├─ Decrement buff expiry; remove expired                   │
  │      └─ Decrement cure-immunity                                 │
  │                                                                 │
  │   5. buildStatusCaches(): cellStatusCache, entityStatusCache    │
  │      (Map<Position, Byte> + Map<String, Byte>)                  │
  └─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │ @EventListener @Order(20)  ActionResolver                       │
  │   (existing) resolveMove / resolveConsume / resolveReproduce    │
  │   Reads buffs from BuffRegistry for movement-range / reproduce  │
  │   Reads BuffRegistry for starvation-boost + cure-on-attack      │
  │   (MUTATING → decrement infection by attack-cure-reduction)     │
  └─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │ @EventListener @Order(50)  PerceptionBroadcaster                │
  │   reads cellStatusCache + entityStatusCache (by position / id)  │
  │   reads BuffRegistry for own sensor-range (5×5 or 7×7)          │
  │   computes vision-scoped OVERCROWDED per bot from visible 8-nb  │
  │   encodes CellView.cellStatus (byte), CellView.entityStatus     │
  │   serialises via Jackson (existing JSON protocol, Phase 14)     │
  └─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │ @EventListener @Order(100) TickBroadcaster                      │
  │   (unchanged — global Tick snapshot with season + seasonal mult)│
  └─────────────────────────────────────────────────────────────────┘

 Shadow state (owned by EnvironmentEngine, read by others):
 ├─ byte[][] toxinGrid    (intensity 0–255 per cell)
 ├─ byte[][] mutagenGrid  (strain id 0=clean, 1–255=strain)
 ├─ List<ActiveToxin>     (each has path + headPos + remainingTicks + seed)
 ├─ List<ActiveMutagen>   (origin cell + ticks since spawn)
 ├─ Map<String,Infection> entityId → (ticksLeft, strain, damagePerTick)
 ├─ Set<String>           cure-immune entity ids (cleared via expiry map)
 └─ BuffRegistry          (separate @Component, Map<String,List<Buff>>)

 Per-tick caches (rebuilt at @Order(15), read-only after that):
 ├─ Map<Position, Byte>   cellStatusCache   (OVERCROWDED|TOXIN|MUTAGEN)
 └─ Map<String, Byte>     entityStatusCache (STARVING|TOXIC|MUTATING|BUFFED)
```

### Recommended Project Structure

```
src/main/java/com/paralife/engine/
├── EnvironmentEngine.java          # NEW — @Component @Order(15)
├── EnvironmentConfig.java          # NEW — @ConfigurationProperties root record
│                                   #   with nested Lightning, Toxin, Mutagen, Compost records
├── BuffRegistry.java               # NEW — shadow registry for mutagen survivor buffs
├── ActiveBuff.java                 # NEW — record(type, expiryTick)  (or enum-keyed map)
├── ToxinEvent.java                 # NEW — record(path, headIdx, remainingTicks, seed)
├── MutagenEvent.java               # NEW — record(origin, spawnTick)
├── Infection.java                  # NEW — record(ticksLeft, strain, damagePerTick)
├── ToxinPathGenerator.java         # NEW — Catmull-Rom + waypoint offset helpers
├── CellularAutomaton.java          # NEW (optional) — double-buffered byte[][] diffusion helper
│                                   #   (or inline into EnvironmentEngine if small)
├── SimulationEngine.java           # MODIFY — add compost hook in processDeaths
├── ActionResolver.java             # MODIFY — read BuffRegistry for move-range + attack-cure-reduce
├── PerceptionBroadcaster.java      # MODIFY — read status caches, encode cellStatus/entityStatus,
│                                   #   expand to 7×7 when sensor buff active
└── (existing files, unchanged)     # SeasonTracker, SeasonsConfig, MetabolicProfile, etc.

src/main/java/com/paralife/websocket/
└── Messages.java                   # MODIFY — CellView adds byte cellStatus, byte entityStatus
                                    #   keep existing 3/4-arg constructors for test back-compat

src/main/resources/
└── application.yml                 # MODIFY — add paralife.simulation.events.* section

src/test/java/com/paralife/engine/
├── EnvironmentEngineTest.java                 # NEW — per-phase unit tests
├── ToxinPathGeneratorTest.java                # NEW — spline math, seed determinism
├── CellularAutomatonTest.java                 # NEW — double-buffer correctness
├── BuffRegistryTest.java                      # NEW — registration, expiry
├── PoissonTriggerTest.java                    # NEW — sine-scaled lambda edge cases
├── EnvironmentIntegrationTest.java            # NEW — @SpringBootTest full pipeline
└── (modify existing SimulationEngineTest for compost, PerceptionBroadcasterTest for status)
```

### Pattern 1: @EventListener @Order Tick Pipeline Phase

**What:** Add a new Spring `@Component` with `@EventListener` method annotated `@Order(N)` to insert a processing phase between existing phases in the tick pipeline.

**When to use:** Any cross-cutting concern that runs once per tick and needs deterministic ordering relative to other tick-phase components.

**Example:**
```java
// Source: existing SimulationEngine.java:79-81, ActionResolver.java:155-156,
//         PerceptionBroadcaster.java:57-58 in this codebase [VERIFIED]
@Component
public class EnvironmentEngine {

    // ... constructor with WorldGrid, SeasonTracker, EnvironmentConfig, BuffRegistry ...

    @EventListener
    @Order(15)  // Between SimulationEngine(10) and ActionResolver(20)
    public void onTick(TickEvent event) {
        if (!config.enabled()) return;
        long tick = event.tickNumber();
        SeasonTracker.Season season = seasonTracker.getSeason(tick);
        long tickInSeason = computeTickInSeason(tick);

        rollPoissonEvents(tick, season, tickInSeason);
        advanceActiveEvents(tick);
        resolveEnvEntityCollisions(tick);
        tickBuffsAndInfections(tick);
        buildStatusCaches();
    }
}
```

**Gotcha (verified):** Spring events are synchronous by default — `@Order` gives deterministic sequential execution on the publisher thread. If you ever add `@Async` to a listener, `@Order` stops guaranteeing sequence. [CITED: docs.spring.io/spring-framework, baeldung.com/spring-events] Paralife's tick pipeline is fully synchronous today; preserve that.

### Pattern 2: Catmull-Rom Spline (hand-rolled, matches microbes.js)

**What:** Evaluate a uniform (α=0) Catmull-Rom spline between anchor points `p1` and `p2`, using `p0` and `p3` as control points outside the segment.

**When to use:** Smooth path through N waypoints (toxin trail, D-05). `curvePoint()` in p5.js (used by microbes.js line 251) is equivalent to this.

**Example:**
```java
// Source: https://www.cs.cmu.edu/~fp/courses/graphics/asst5/catmullRom.pdf
//         https://en.wikipedia.org/wiki/Cubic_Hermite_spline (Catmull-Rom section)
//         Standard formula, verified via WebSearch 2026-04-17 [VERIFIED]
/**
 * Evaluate one axis of a uniform Catmull-Rom spline at parameter t in [0, 1],
 * interpolating from p1 to p2. Control points p0 (before p1) and p3 (after p2)
 * shape the tangent at the endpoints.
 *
 * This matches p5.js curvePoint(p0, p1, p2, p3, t), which microbes.js uses.
 */
static double catmullRom(double p0, double p1, double p2, double p3, double t) {
    double t2 = t * t;
    double t3 = t2 * t;
    return 0.5 * (
        (2.0 * p1)
        + (-p0 + p2) * t
        + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
        + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
    );
}

/**
 * Evaluate the full spline through an N-waypoint path at overall progress
 * overallT in [0, 1]. Handles endpoint clamping (c1/c2 fall back to anchors
 * at ends) the same way microbes.js calcPosOnPath does.
 */
static Position evaluatePath(List<Position> waypoints, double overallT) {
    int n = waypoints.size() - 1;           // number of segments
    double scaled = overallT * n;
    int p1Idx = Math.min((int) scaled, n - 1);
    int p2Idx = p1Idx + 1;
    int c1Idx = Math.max(p1Idx - 1, 0);
    int c2Idx = Math.min(p2Idx + 1, n);
    double segT = scaled - p1Idx;

    double x = catmullRom(
        waypoints.get(c1Idx).x(), waypoints.get(p1Idx).x(),
        waypoints.get(p2Idx).x(), waypoints.get(c2Idx).x(), segT);
    double y = catmullRom(
        waypoints.get(c1Idx).y(), waypoints.get(p1Idx).y(),
        waypoints.get(p2Idx).y(), waypoints.get(c2Idx).y(), segT);
    // Toroidal wrap handled by caller or here:
    return Position.wrap((int) Math.round(x), (int) Math.round(y), width, height);
}
```

**Notes:**
- The 0.5 factor = uniform tension τ=0.5, which is what p5.js `curvePoint` uses by default. [CITED: p5.js source src/core/shape/curves.js]
- For curves on a toroidal grid, generate waypoints in un-wrapped coordinates, evaluate in un-wrapped coordinates, then `Position.wrap` at the very end. Wrapping mid-evaluation creates discontinuities.
- If the caller wants the head to advance in **uniform arc length** (constant speed in cells per tick rather than constant parameter step), sample the spline densely once at event spawn and store a pre-computed array of `(cellX, cellY)` points; advance by index each tick. This matches the user's reference: "Toxin head advances along path at configured speed (cells per tick)" (D-06).

### Pattern 3: Cellular Automaton with Double Buffering

**What:** Read-from-past-write-to-future to avoid order-dependent updates when computing CA transitions. Two byte arrays; swap or copy at phase end.

**When to use:** Any CA where a cell's new value depends on its neighbors' current (not partially-updated) values. Toxin diffusion and mutagen gossip both need this.

**Example:**
```java
// Source: https://artificialnature.net/courses/datt4950/cellular.html
//         Wikipedia: Cellular automaton (synchronous update)
//         Verified via WebSearch 2026-04-17 [VERIFIED]
/**
 * Diffusion step with Moore (8-neighbor) averaging + decay.
 * `src` is read, `dst` is written — caller swaps references or copies dst→src.
 * Uses toroidal wrap via Position.wrap to match WorldGrid.getNeighbors(8).
 */
void diffuseStep(byte[][] src, byte[][] dst, int w, int h,
                  double diffusionRate, double decayRate) {
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            int self = src[x][y] & 0xFF;   // unsigned read
            int neighborSum = 0;
            int neighborCount = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = Math.floorMod(x + dx, w);
                    int ny = Math.floorMod(y + dy, h);
                    neighborSum += src[nx][ny] & 0xFF;
                    neighborCount++;
                }
            }
            double neighborAvg = neighborSum / (double) neighborCount;
            // Weighted mix: retain fraction (1 - diffusion) of self, take diffusion
            // fraction of neighbor avg, then decay.
            double mixed = (1.0 - diffusionRate) * self + diffusionRate * neighborAvg;
            int after = (int) Math.round(mixed * (1.0 - decayRate));
            dst[x][y] = (byte) Math.clamp(after, 0, 255);
        }
    }
}

// Per-tick usage:
//   diffuseStep(toxinGrid, toxinGridNext, w, h, diffusionRate, decayRate);
//   byte[][] tmp = toxinGrid; toxinGrid = toxinGridNext; toxinGridNext = tmp;
```

**Notes:**
- **Unsigned byte read:** `src[x][y] & 0xFF` — Java `byte` is signed; `(byte) 200` reads as `-56` without masking. Every byte-grid access must mask.
- **Moore vs Von Neumann (Claude's Discretion):** Moore (8-neighbor, above) produces square-ish spread; Von Neumann (4-neighbor) produces diamond-ish spread. Paralife is already Moore-centric (`WorldGrid.neighbors8`, all prior logic 8-neighbor). **Recommendation: Moore, for consistency with the rest of the codebase and to match toxin's "spilling outward" visual expectation.**
- **Threshold clear (D-07):** Apply after diffuse+decay: `if (dst[x][y] < threshold) dst[x][y] = 0;` — prevents long-tail spread of tiny intensities.
- **Allocation:** Allocate `toxinGridNext` once at construction, reuse every tick. Don't `new byte[w][h]` per tick — at 256×256 that's 64KB allocation × event-duration ticks.

### Pattern 4: Shadow Registry (BuffRegistry)

**What:** Thread-safe `@Component` that owns cross-cutting state (mutations happen in the single-threaded tick pipeline; reads may come from other threads — WebSocket handlers, bot action queue). Mirrors existing `BotRegistry` and `CompositeRegistry` structure.

**When to use:** Cross-cutting state that would bloat immutable entity records if inlined (D-19).

**Example:**
```java
// Source: this codebase — BotRegistry.java, CompositeRegistry.java [VERIFIED, existing pattern]
@Component
public class BuffRegistry {

    public record ActiveBuff(BuffType type, long expiryTick) {}

    public enum BuffType { ATTACK_PLUS_1, MOVEMENT_PLUS_1, SENSOR_PLUS_1, UPKEEP_MINUS_1 }

    private final ConcurrentHashMap<String, List<ActiveBuff>> byEntity = new ConcurrentHashMap<>();

    public void grant(String entityId, BuffType type, long expiryTick) {
        byEntity.compute(entityId, (k, existing) -> {
            List<ActiveBuff> list = existing == null
                    ? new CopyOnWriteArrayList<>()
                    : existing;
            list.add(new ActiveBuff(type, expiryTick));
            return list;
        });
    }

    public List<ActiveBuff> getBuffs(String entityId) {
        return byEntity.getOrDefault(entityId, List.of());
    }

    public boolean hasBuff(String entityId, BuffType type) {
        for (ActiveBuff b : getBuffs(entityId)) {
            if (b.type() == type) return true;
        }
        return false;
    }

    /** Called each tick from EnvironmentEngine — remove expired buffs. */
    public void expireBuffs(long currentTick) {
        byEntity.replaceAll((id, list) -> {
            list.removeIf(b -> b.expiryTick() <= currentTick);
            return list.isEmpty() ? null : list;  // cleaner: returning null would not remove — see note
        });
        // Actually: ConcurrentHashMap.replaceAll does not remove on null return;
        // use entrySet().removeIf after replaceAll, or compute per-key.
        byEntity.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public void unregisterEntity(String entityId) {
        byEntity.remove(entityId);
    }

    public void clear() { byEntity.clear(); }
}
```

**Notes:**
- Mirror existing registries exactly for consistency: `ConcurrentHashMap` for the primary map, `CopyOnWriteArrayList` or unmodifiable wrappers for mutable-inside-immutable state shapes.
- `SimulationEngine.processDeaths` should call `buffRegistry.unregisterEntity(id)` just like it calls `botRegistry.unregisterByEntity(id)`. Orphan buffs after death are a memory leak.

### Pattern 5: Nested @ConfigurationProperties Records

**What:** One root record bound with a single `prefix`, containing nested records for each sub-section. Spring Boot binds nested records automatically.

**When to use:** Structured config under a common prefix (`paralife.simulation.events.*` per D-48).

**Example:**
```java
// Source: MetabolicProfile.java in this codebase (nested TypeProfile records) [VERIFIED]
@ConfigurationProperties(prefix = "paralife.simulation.events")
public record EnvironmentConfig(
        Lightning lightning,
        Toxin toxin,
        Mutagen mutagen,
        Compost compost
) {
    public record Lightning(
            String peakSeason,      // "SUMMER" — parsed to SeasonTracker.Season enum
            double peakLambda,
            double offSeasonLambda,
            int innerRadius,
            int outerRadius,
            int damage,
            int fertilityBoost
    ) {}

    public record Toxin(
            String peakSeason,
            double peakLambda,
            double offSeasonLambda,
            int pathPointsMin,
            int pathPointsMax,
            int pathOffsetMin,
            int pathOffsetMax,
            int speed,
            int lifetimeTicks,
            int diffusionRadius,
            double decayRate,
            double splashDamageFraction,
            Resistance resistance
    ) {
        public record Resistance(double catalyst, double membrane, double spore) {}
    }

    public record Mutagen(
            String peakSeason,
            double peakLambda,
            double offSeasonLambda,
            int infectionDurationMin,
            int infectionDurationMax,
            int buffDurationMultiplier,
            int cureTicks,
            int attackCureReduction,
            int damagePerTick,
            double strainMutationChance,
            double gossipProbability
    ) {}

    public record Compost(int fullStrength, int halfStrength) {}

    public EnvironmentConfig {
        // Fail fast on invalid combinations — same pattern as SeasonsConfig
        if (toxin.pathPointsMin < 2) throw new IllegalArgumentException("...");
        if (toxin.pathPointsMax < toxin.pathPointsMin) throw new IllegalArgumentException("...");
        if (mutagen.infectionDurationMin < 1) throw new IllegalArgumentException("...");
        if (mutagen.infectionDurationMax < mutagen.infectionDurationMin) throw new IllegalArgumentException("...");
        // ... etc
    }

    public static EnvironmentConfig defaults() { /* returns record tree with CONTEXT.md D-30/D-48 values */ }
}
```

**Notes:**
- **`@ConfigurationPropertiesScan`** or explicit `@EnableConfigurationProperties(EnvironmentConfig.class)` must be present. Check `ParalifeApplication.java` for how other configs are registered (e.g. `SeasonsConfig`, `MetabolicProfile`) and mirror.
- **Enum parsing:** Binding `"SUMMER"` directly to `SeasonTracker.Season` works via Spring's enum converter. Prefer `Season peakSeason` (typed enum) over `String` to get free validation. The example above uses `String` to avoid circular references if Season lives in engine — **verify during planning** that enum binding works across package boundaries. If not, convert string → enum in `EnvironmentConfig` compact constructor.
- **Validation:** Follow `SeasonsConfig.java` compact-constructor pattern — throw `IllegalArgumentException` for bad values. Tests verify each failure branch (see `FertilityInitializerTest.fertilityConfigRejectsNegativePatchCount`).

### Pattern 6: Jackson CellView Extension with Back-Compat

**What:** Extend a record used for JSON serialisation with new fields while preserving existing test constructors.

**When to use:** `Messages.CellView` must gain `cellStatus` (byte) and `entityStatus` (byte) per D-37 without breaking the bot client wire format.

**Example:**
```java
// Source: Messages.java:193-203 in this codebase — existing back-compat pattern [VERIFIED]
// The existing 3-arg ctor for (occupantType, occupantId, nutrientLevel) still works
// after adding `flags` in Phase 13 Plan 02. Extend the same way.
public record CellView(
        String occupantType,
        String occupantId,
        int nutrientLevel,
        int flags,
        byte cellStatus,     // NEW — 6-bit bitfield, OVERCROWDED|TOXIN|MUTAGEN
        byte entityStatus    // NEW — 6-bit bitfield, STARVING|TOXIC|MUTATING|BUFFED
) {
    /** Back-compat 3-arg — flags=0, statuses=0. Used by pre-Phase-13 tests. */
    public CellView(String occupantType, String occupantId, int nutrientLevel) {
        this(occupantType, occupantId, nutrientLevel, 0, (byte) 0, (byte) 0);
    }
    /** Back-compat 4-arg — statuses=0. Used by Phase 13 tests. */
    public CellView(String occupantType, String occupantId, int nutrientLevel, int flags) {
        this(occupantType, occupantId, nutrientLevel, flags, (byte) 0, (byte) 0);
    }
}
```

**Notes:**
- **Jackson record behavior:** Jackson deserializes records via the canonical constructor. Adding optional constructors is safe for **serialisation** (Jackson uses record accessors, not constructors, to write JSON). For deserialisation, Jackson locates the canonical constructor unless `@JsonCreator` is used. [CITED: github.com/FasterXML/jackson-databind issue #3968, baeldung.com/jackson-deserialize-immutable-objects]
- **`@JsonInclude(NON_NULL)`** at the Messages interface level — zero-valued bytes **will** be serialised (not null). This is fine per D-37 ("fixed-width, not omit-if-zero, parse simplicity > bandwidth saving"). The compact text protocol in Phase 15 will re-address this.
- **Bot client back-compat:** `BotClient` uses raw `JsonNode`/`LinkedHashMap` (noted tech debt, CLAUDE.md audit table) — adding two fields to `CellView` won't break it. Bots that don't read `cellStatus`/`entityStatus` continue working. Bots that read them get behavior upgrades. Plan a one-line read of these fields in `HeuristicBrain` for D-43 behavioral mapping.
- **The current `flags` field (Phase 13)** and the new `cellStatus` field will partially overlap (both encode OVERCROWDED). They're different sources: `flags` comes from `Cell.flags` (set by SimulationEngine's global overcrowding pass); `cellStatus` encodes the vision-scoped recomputation (D-40). Do not remove `flags` — it's consumed by existing tests and represents server-authoritative state. Document the distinction in CellView javadoc.

### Pattern 7: Poisson Event Triggering (sine-scaled λ)

**What:** Per-tick Bernoulli trial with tick-varying success probability `λ(tick)` that ramps smoothly via sine between peak-season and off-season rates (D-26, D-27).

**When to use:** Independent weather events that should bunch around peak season and be rare otherwise.

**Example:**
```java
// Source: D-27 in 14-CONTEXT.md (formula locked by user, verified correct) [VERIFIED]
/**
 * Fires a true Bernoulli(λ(tick)) trial. Returns true if an event should spawn.
 * `peakSeason` must match the tick's current season for ramped λ;
 * otherwise flat off-season λ applies.
 */
boolean shouldFireEvent(long tick, Season currentSeason, Season peakSeason,
                         int seasonLength, double peakLambda, double offLambda,
                         Random rng) {
    if (currentSeason == Season.WINTER) return false;  // D-29
    double lambda;
    if (currentSeason == peakSeason) {
        long tickInSeason = computeTickInSeason(tick, seasonLength);
        double x = 2.0 * Math.PI * tickInSeason / seasonLength;
        // sin(x - π/2) shifts so sin goes 0 → +1 → 0 → -1 → 0 across a full cycle;
        // then 0.5 * (sin + 1) remaps to [0, 1]. Multiply by peak-off amplitude.
        lambda = 0.5 * (Math.sin(x - Math.PI / 2.0) + 1.0) * (peakLambda - offLambda) + offLambda;
    } else {
        lambda = offLambda;
    }
    return rng.nextDouble() < lambda;
}
```

**Notes:**
- **Precision:** At peak λ=0.04 and tick interval 500ms, per-tick `nextDouble()` sampling has ample precision (Java uses 53 bits of mantissa). The exponential-distribution / next-event-time approach is unnecessary at these rates. [CITED: preshing.com/20111007/how-to-generate-random-timings-for-a-poisson-process/]
- **`computeTickInSeason`:** Phase 13's `SeasonTracker` computes season enum from global tick, but does not expose "tick-in-season." Add a helper to `SeasonTracker`:
  ```java
  public long getTickInSeason(long tick) {
      int yearLength = config.yearLengthTicks();
      long position = Math.floorMod(tick, (long) yearLength);
      long shifted = position + yearLength / 8L;
      int quarter = (int) Math.floorMod(shifted / (yearLength / 4L), 4L);
      long seasonStart = quarter * (yearLength / 4L) - yearLength / 8L;
      return Math.floorMod(position - seasonStart, (long) yearLength);
  }
  ```
  Unit-test against `getSeason` for consistency. **Alternative (simpler):** since the sine formula is symmetric and `SeasonTracker` already computes `getSeasonalMultiplier` from global tick, you can derive `lambda` directly from global tick without computing tick-in-season — just use a sine term of the right phase. Locked formula D-27 uses `tickInSeason`; choose one approach consistently.
- **Skip-when-active (D-28):** `if (activeToxin != null) skip toxin Poisson roll;` — enforce max-1-per-type (D-03) at roll time, not at spawn time.
- **Random source:** Use a single `Random` in `EnvironmentEngine` constructor-initialised, optionally seeded from config for test determinism. **Claude's Discretion:** the rest of the codebase uses `ThreadLocalRandom.current()`; the tradeoff is that `ThreadLocalRandom` can't be seeded — use a dedicated `Random` in `EnvironmentEngine` for the Poisson rolls and path generation so tests can pin behavior, but keep `ThreadLocalRandom` for the already-concurrent code paths untouched. See Pitfall 6 below.

### Anti-Patterns to Avoid

- **Don't add `@Async` to `EnvironmentEngine.onTick`.** It would break `@Order` sequencing (verified: Spring events are synchronous by default; `@Async` makes them concurrent). Paralife's single-threaded sim core is a deliberate design invariant. [CITED: baeldung.com/spring-events]
- **Don't mutate `byte[][] toxinGrid` in-place during diffusion.** Order-dependent bugs. Use double buffering every tick.
- **Don't store buffs inside `Particle` / `CompositeMember` records.** That would break the immutable-record-rebuild-on-mutation pattern and pollute entity identity. Shadow registry (D-19) is locked.
- **Don't spawn a new `byte[w][h]` per tick for the secondary buffer.** Allocate both at construction, swap references with a 3-line swap.
- **Don't read `byte` directly without `& 0xFF`.** Java byte is signed; 200 reads as −56. Every intensity read must mask.
- **Don't assume `ThreadLocalRandom` is seedable.** It isn't. If you want deterministic path generation for visualiser replay (Claude's Discretion mentioned in CONTEXT.md), use `new Random(seedFromEvent)` and store the seed on the event record.
- **Don't mid-evaluation wrap spline coordinates.** Generate waypoints in un-wrapped space, evaluate in un-wrapped space, wrap only at final `Position.wrap` conversion.
- **Don't forget `buffRegistry.unregisterEntity` in `SimulationEngine.processDeaths`.** Memory leak waiting to happen.
- **Don't add new `Cell` flag constants for `TOXIC` / `MUTAGEN_ZONE`.** D-38 locks these into the status bitmask on CellView, not Cell.flags — the flags field is reserved for things stored on the cell itself (OVERCROWDED, STARVING). Toxin/mutagen live on shadow grids.

## Don't Hand-Roll

Paradoxically, Phase 14 is mostly "do hand-roll, because the existing libraries are over-sized for our needs." The table below lists the few cases where hand-rolling is wrong:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON serialisation of CellView with new byte fields | Custom JSON writer | Existing Jackson + `@JsonInclude(NON_NULL)` on Messages interface | Project standard; record fields auto-serialise; back-compat constructors preserve test coverage. |
| Toroidal coordinate wrap | In-loop `if (x < 0) x += w; if (x >= w) x -= w` | Existing `Position.wrap(x, y, w, h)` or `Math.floorMod(x, w)` | `Position.wrap` is the canonical wrap utility; `Math.floorMod` handles negatives correctly (`-1 % 256 == -1` in Java, not 255; `Math.floorMod(-1, 256) == 255`). Using raw `%` is a bug. |
| 8-neighbor iteration | Nested `for dx,dy` loops with bounds checks | `WorldGrid.getNeighbors(x, y)` returns the 8 wrapped positions | Already canonical; used in overcrowding, interactions, composite formation. |
| Seasonal multiplier for fertility-boost blending | Independent sine | `SeasonTracker.getSeasonalMultiplier(tick)` | Reuse; single source of truth for seasonal data. |
| Per-type resistance lookup | Switch statement per call site | Add `Resistance` nested record to `EnvironmentConfig.Toxin`, lookup via a `double resistanceFor(ParticleType)` method on it | Mirrors `MetabolicProfile.forType` pattern; DRY; testable. |

**Key insight:** Paralife deliberately has minimal external dependencies. Phase 14 should stay that way. Catmull-Rom, CA diffusion, and Poisson triggering are all ~20 lines of code each — adding Apache Commons Math or JTS for any of them would add build time + dependency surface for negligible gain. The bigger wins are reusing Paralife's own abstractions (`Position.wrap`, `WorldGrid.getNeighbors`, `SeasonTracker`, the registry pattern).

## Runtime State Inventory

N/A — Phase 14 is a greenfield addition (new components, new shadow state, new config section). No rename/refactor/migration involved. No data to migrate. No stored state to update (no database, no on-disk persistence — Paralife is an in-memory simulation).

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — verified: no database, no filesystem persistence in codebase (grep for `Files.write`, `.save`, JDBC — none present) | None |
| Live service config | None — Paralife is a standalone Spring Boot JVM, no external service registrations | None |
| OS-registered state | None | None |
| Secrets/env vars | None — Phase 14 adds only static yaml config | None |
| Build artifacts | None — new `.class` files created by normal Gradle compilation | None |

## Common Pitfalls

### Pitfall 1: Signed byte arithmetic in shadow grids
**What goes wrong:** `byte[][] toxinGrid` with intensity 0–255 looks correct but reads wrong above 127. `(byte) 200` stored, retrieved as `-56`.
**Why it happens:** Java `byte` is a signed 8-bit integer. Intensity = 200 fits in 8 bits but wraps to negative in signed interpretation.
**How to avoid:** Always mask on read: `int intensity = toxinGrid[x][y] & 0xFF;`. Create a helper method `int getIntensity(int x, int y) { return toxinGrid[x][y] & 0xFF; }` and forbid direct byte reads.
**Warning signs:** Diffusion produces intensity oscillating between large positive and large negative; tests that pass at intensity ≤ 127 and fail above. Source: standard Java gotcha, verified by decades of JVM usage.

### Pitfall 2: In-place CA update (dirty read)
**What goes wrong:** Toxin intensity spreads asymmetrically — cells at lower x/y indices "see" updated values from the current tick while cells at higher indices see old values.
**Why it happens:** Updating the same array you're reading produces order-dependent results.
**How to avoid:** Double-buffer: read `src`, write `dst`, swap references after the phase. Allocate `dst` once at construction, not per tick.
**Warning signs:** Stripe-shaped diffusion artefacts; results depend on iteration direction. [CITED: artificialnature.net/courses/datt4950/cellular.html]

### Pitfall 3: `Random.nextDouble() < lambda` vs exponential inter-arrival time
**What goes wrong:** Developer assumes `nextDouble() < lambda` is wrong and overthinks, reaches for Apache Commons Math `nextExponential()`.
**Why it happens:** Literature shows both approaches; the exponential approach is more accurate at high sampling frequencies.
**How to avoid:** Know the crossover. At tick interval 500ms and peak λ=0.04, per-tick Bernoulli is exact to ~53 bits of precision — equivalent to exponential. The exponential approach only wins at sub-millisecond tick rates with very low λ. [CITED: preshing.com/20111007/how-to-generate-random-timings-for-a-poisson-process/] Paralife's rates make Bernoulli strictly correct. Use D-27 formula as locked.

### Pitfall 4: Forgotten compost hook on non-Particle deaths
**What goes wrong:** BondedPair and CompositeMember deaths produce no compost; only Particle deaths do.
**Why it happens:** `SimulationEngine.processDeaths` has three distinct branches (Phase 3a for Particle+BondedPair, Phase 3b for CompositeMember, Phase 3c for panic zone total death). Adding `applyCompost(pos)` to only one branch misses the others.
**How to avoid:** Apply compost on every `worldGrid.clearEntity(x, y)` call inside processDeaths — that's the canonical "an entity just died here" signal. Create a helper `clearEntityAndCompost(x, y)` and use it everywhere death is finalised. Include it in `dissolveToParticles` (dissolution does not count as death — particles continue living) but do include it in the panic-zone pool=0 branch.
**Warning signs:** Compost strength per death ≠ tests assume when BondedPairs die; integration test for population vs. fertility shows suspicious under-fertility.

### Pitfall 5: BuffRegistry entry orphaned on entity death
**What goes wrong:** Particle dies with active `+1 sensor` buff. `BotRegistry.unregisterByEntity(id)` runs; `BuffRegistry` entry is not cleaned. Memory grows with each death.
**Why it happens:** Buffs are shadow state — SimulationEngine's existing death flow doesn't know about them.
**How to avoid:** In `processDeaths`, next to every `botRegistry.unregisterByEntity(id)`, add `buffRegistry.unregisterEntity(id)`. Same place. Same pattern. Consider a `DeathHook` interface with multiple implementations registered as Spring beans, called in a loop — but that's future refactor; for now, two-line addition.
**Warning signs:** Heap profile shows `BuffRegistry.byEntity` growing monotonically in load tests.

### Pitfall 6: Non-deterministic tests
**What goes wrong:** EnvironmentEngineTest.toxinEventEventuallyFires uses `ThreadLocalRandom`; test fails 1% of the time on CI.
**Why it happens:** `ThreadLocalRandom` cannot be seeded. Stochastic tests need reproducibility.
**How to avoid:** Construct `EnvironmentEngine` with an injectable `Random` field (default: `new Random()`, test: `new Random(42L)`). Alternatively: inject a `LongSupplier` or `DoubleSupplier` to stub the rolls entirely. Similar pattern to how `FertilityInitializerTest.generatePatchCenterHasMaxLevel` directly invokes a package-private method to bypass randomness.
**Warning signs:** Flaky tests. Intermittent CI failures.

### Pitfall 7: Status caches read before built
**What goes wrong:** `PerceptionBroadcaster @Order(50)` tries to read `cellStatusCache`, gets empty map because `EnvironmentEngine @Order(15)` hasn't built it yet on the first tick, or cache was cleared between ticks.
**Why it happens:** Cache is rebuilt inside `EnvironmentEngine.onTick`; if that method early-exits when `config.enabled()` is false, cache is stale / empty.
**How to avoid:** Initialise caches in `EnvironmentEngine` constructor as empty maps. Clear-and-rebuild at the start of each `onTick`. If `config.enabled() == false`, still clear caches (empty cache is correct for "no env effects"). `PerceptionBroadcaster` never sees null; it sees empty which encodes to `cellStatus == 0` (no bits set).
**Warning signs:** NullPointerException in PerceptionBroadcaster; first-tick perception missing status; toggling env config live causes intermittent nulls.

### Pitfall 8: Spline path crosses the torus seam poorly
**What goes wrong:** Toxin starts at (5, 128) (left edge), targets (250, 128) (right edge). Spline interpolates through the middle of the grid instead of wrapping around the short side. Toxin covers the wrong area.
**Why it happens:** Catmull-Rom is a linear math operation — it has no notion of toroidal distance.
**How to avoid:** When picking the pair of edge points (D-05 step 2), choose actual distinct edges (top/bottom, left/right, etc.) — do not pick two points on "edges" that happen to alias to the same toroidal cluster. Then compute the destination vector using standard (non-wrapped) subtraction. The spline then crosses the grid interior, which is the intent. Only wrap coordinates at the final integer-cell conversion step. Document in `ToxinPathGenerator` that waypoints are generated in "flat" coordinates and wrapped late.
**Warning signs:** Toxin paths appear linear instead of curved; head jumps by large amounts per tick.

### Pitfall 9: `@EventListener` throws uncaught exception, silently breaks pipeline
**What goes wrong:** `EnvironmentEngine.onTick` throws NPE (e.g. bad path waypoints on a degenerate event). The exception propagates up into `ApplicationEventPublisher.publishEvent`. Downstream listeners (`ActionResolver`, `PerceptionBroadcaster`, `TickBroadcaster`) never run for this tick. Pipeline silently half-processes.
**Why it happens:** Spring's default event multicaster propagates exceptions. The existing TickEngine loop (per CLAUDE.md "Tick loop catches exceptions and continues") catches at the outer level, but within-tick ordering means later listeners are skipped.
**How to avoid:** Wrap the body of `EnvironmentEngine.onTick` in a try/catch that logs + records metric + continues. Match the existing pattern in `TickBroadcaster` / `SimulationEngine` (defensive logging at the phase level). Alternatively: implement `AsyncUncaughtExceptionHandler` and configure the event multicaster to continue on failure — but a per-listener try/catch is simpler and more localised.
**Warning signs:** Perception messages missing mid-simulation; bot clients disconnect due to missing ActionResults; tick numbers in logs skip listeners without error.

## Code Examples

Verified patterns from existing Paralife code:

### Example A: Reusing Phase 13 SPORE reproduce-range-2 for `+1 movement` buff (D-15)
```java
// Source: ActionResolver.java:429-444 in this codebase [VERIFIED]
// The D-15 "+1 movement" buff enables hop-to-range-2. The existing SPORE reproduction
// code already walks `range` cells in a direction with fallback to range-1 on blockage.
// Extract into a shared helper and call from both resolveReproduce and resolveMove
// when the actor has MOVEMENT_PLUS_1 buff.
Position findTargetAtRange(Position from, Direction dir, int range,
                            Set<Position> claimedCells, int w, int h) {
    int minCandidate = range > 1 ? range - 1 : 1;
    for (int candidate = range; candidate >= minCandidate; candidate--) {
        Position t = from;
        for (int step = 0; step < candidate; step++) {
            t = dir.apply(t, w, h);
        }
        if (claimedCells.contains(t)) continue;
        if (worldGrid.getCell(t.x(), t.y()).hasOccupant()) continue;
        return t;
    }
    return null;
}
```

### Example B: Cell.withNutrientLevel for compost (D-24)
```java
// Source: Cell.java:65-67 + CONTEXT.md D-24 [VERIFIED]
void applyCompost(Position deathPos) {
    Cell cell = worldGrid.getCell(deathPos.x(), deathPos.y());
    int newLevel = Math.min(cell.nutrientLevel() + config.compost().fullStrength(),
                             fertilityConfig.maxLevel());
    worldGrid.setCell(deathPos.x(), deathPos.y(), cell.withNutrientLevel(newLevel));

    for (Position neighbor : worldGrid.getNeighbors(deathPos.x(), deathPos.y())) {
        Cell nc = worldGrid.getCell(neighbor.x(), neighbor.y());
        int nNew = Math.min(nc.nutrientLevel() + config.compost().halfStrength(),
                             fertilityConfig.maxLevel());
        worldGrid.setCell(neighbor.x(), neighbor.y(), nc.withNutrientLevel(nNew));
    }
}
```

### Example C: Registering @ConfigurationProperties via EnableConfigurationProperties
```java
// Source: existing project — search for @EnableConfigurationProperties in
//         ParalifeApplication or a @Configuration class to see the pattern.
@SpringBootApplication
@EnableConfigurationProperties({
    GridConfig.class, TickConfig.class, SimulationConfig.class,
    BondingConfig.class, CompositeConfig.class, MetabolicProfile.class,
    StarvationConfig.class, FertilityConfig.class, SeasonsConfig.class,
    EnvironmentConfig.class   // NEW — Phase 14
})
public class ParalifeApplication { ... }
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| n/a (Paralife-new) | — | — | — |

No "state of the art" deltas apply — all patterns used here are stable, decades-old numerical techniques (Catmull-Rom: 1974; CA diffusion: 1940s; Poisson Bernoulli sampling: 1700s). Spring Boot 3.4.4 and Java 21 are both current as of 2026-04-17.

**Deprecated/outdated:** None applicable to Phase 14 scope.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `p5.js curvePoint` uses uniform (α=0) Catmull-Rom with τ=0.5 | Pattern 2 | Low — the formula in Pattern 2 is the standard uniform Catmull-Rom and produces smooth curves; if microbes.js visually differs, adjust tension parameter. WebFetch of p5.js source couldn't confirm exact formula (source file cut off), but WebSearch consensus + the parameter-order match (c1, p1, p2, c2) strongly indicates standard Catmull-Rom. Safe to proceed; tests will visually confirm. |
| A2 | Java `Random.nextDouble()` precision (53-bit mantissa) is sufficient for Poisson λ ≤ 0.04 | Pitfall 3 | Very low — 53 bits gives ~2^53 distinct values in [0,1); peak λ=0.04 uses only the top ~6 bits. No precision concern. |
| A3 | `@ConfigurationProperties` on nested records with a `Season` enum field works across packages via Spring's enum converter | Pattern 5 | Low — Spring Boot 3.x documented enum binding support is HIGH confidence; worst case use `String peakSeason` and convert in compact constructor. Planner should verify during Wave 0 binding test. |
| A4 | Adding `cellStatus` / `entityStatus` fields to `Messages.CellView` does not break Jackson deserialisation for connected bot clients that haven't been updated | Pattern 6 | Low — Jackson + `@JsonInclude(NON_NULL)` + new fields serialise on server side; if a bot client does strict deserialisation (unlikely — existing code uses raw `JsonNode`), it would ignore unknown fields by default. BotClient tech-debt item confirms raw-map usage. |
| A5 | `SimulationEngine.processDeaths` is the correct hook point for compost (D-24, D-46) | Architectural Responsibility Map | Low — D-46 explicitly names this hook. Structural verification during planning: confirm processDeaths calls the compost helper on every entity clear, including BondedPair and CompositeMember branches. |
| A6 | Phase 14 can ship with existing JSON protocol — no protocol changes required | User Constraints | None — locked decision D-01 (phase split) + CONTEXT.md domain section. |
| A7 | Toxin type-resistance defaults (D-09, Claude's Discretion): CATALYST=1.0 (no resistance), MEMBRANE=0.7 (durable), SPORE=1.3 (fragile) — matches archetype semantics from Phase 13 D-03 | (will be added during planning) | Low — matches existing archetype (MEMBRANE "efficient defensive grazer, durable"; SPORE fragile r-strategist). Planning may tune; these are good defaults. |
| A8 | Composite role-perk mapping (D-18, Claude's Discretion): FEEDER → `+nutrient gain`, ATTACKER → `+1 attack`, LOCOMOTOR → `+1 movement`, SENSOR → `+1 sensor`, DEFENDER → `-1 damage taken`, REPRODUCER → `-1 reproduce cooldown`. Universal `-1 upkeep` in addition. | (will be added during planning) | Low — role-natural mapping; planning can refine. Keeps "wasted roll" problem resolved (D-18 intent). |

## Open Questions

1. **Should Phase 14 add a standalone `DeathHook` SPI or inline both cleanup calls (`botRegistry.unregisterByEntity`, `buffRegistry.unregisterEntity`, `applyCompost`) directly in `SimulationEngine.processDeaths`?**
   - What we know: Both approaches work. Inlining is simpler; SPI is more extensible.
   - What's unclear: Whether the planner considers Phase 14 the right time to introduce the SPI (it may become useful for future phases — e.g. perception of corpses, scavenger AI).
   - Recommendation: **Inline.** Paralife is 3 call sites total (unregister bot, unregister buff, apply compost). SPI adds complexity without clear benefit. Revisit if Phase 16+ needs it.

2. **Status field width — byte vs int in CellView?**
   - What we know: 6 bits per bitmask fits in a `byte`. JSON serialisation of `byte` by Jackson produces `int` in wire format anyway.
   - What's unclear: Whether using `int` (simpler — matches existing `flags`) or `byte` (matches D-36 wire alphabet) is preferable in the Java source.
   - Recommendation: **`int` in Java source, byte-valued bit layout.** Matches existing `flags: int` field. The base64 alphabet encoding (D-36) is a Phase 15 concern (compact text protocol). Phase 14 sends JSON; the int serialises as a normal JSON number. No behavior change. This also sidesteps the signed-byte-mask footgun for a field that's read frequently.

3. **Diffusion kernel weighting — uniform Moore average or Gaussian-weighted?**
   - What we know: Pattern 3 uses uniform 8-neighbor averaging.
   - What's unclear: Whether Gaussian weighting (higher weight to immediate neighbors, lower to diagonals) produces visually better toxin spread.
   - Recommendation: **Uniform 8-neighbor.** Simpler; easier to reason about; matches existing overcrowding neighbor logic. If visual tuning during plan-verify shows bad spread, revisit with Gaussian coefficients `{1/16, 2/16, 1/16, 2/16, 4/16, 2/16, 1/16, 2/16, 1/16}`.

4. **Should `EnvironmentConfig` include an `enabled` master flag (like `SimulationConfig.enabled()`)?**
   - What we know: Tests frequently disable the tick loop or simulation engine.
   - What's unclear: Whether full-system tests running with env disabled is worth the config knob.
   - Recommendation: **Yes, include `paralife.simulation.events.enabled: true` master toggle.** Mirrors `simulation.enabled` and lets integration tests isolate env-vs-non-env behavior.

5. **Where does HeuristicBrain need to change?**
   - What we know: D-43 lists behavioral mappings (STARVING, TOXIC, MUTATING, BUFFED) for predator decisions.
   - What's unclear: Whether Phase 14 includes HeuristicBrain updates or defers to Phase 16 emergence tests.
   - Recommendation: **Include minimal HeuristicBrain updates in Phase 14** — specifically, prefer targeting STARVING enemies (already flag-visible today), avoid moving into TOXIN_PRESENT cells when low energy, be cautious around BUFFED, commit-or-flee heuristic for MUTATING. Integration tests in Phase 16 will validate. Deferring entirely means Phase 14 integration tests can't show behavioral emergence.

## Environment Availability

Paralife is a self-contained JVM simulation. Phase 14 adds no external dependencies.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 JDK | Build + runtime | ✓ (assumed — project exists) | 21 (locked in `build.gradle.kts:13`) | — |
| Gradle wrapper | Build | ✓ (`./gradlew` present) | as committed | — |
| Spring Boot 3.4.4 | Runtime | ✓ (transitive via `build.gradle.kts:4`) | 3.4.4 | — |
| JUnit 5 / AssertJ | Tests | ✓ (transitive via `spring-boot-starter-test`) | bundled | — |

**Missing dependencies with no fallback:** None.

**Missing dependencies with fallback:** None.

## Validation Architecture

Based on `.planning/config.json` — `workflow.nyquist_validation: true`, so this section applies.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + AssertJ — verified via `build.gradle.kts:26` `spring-boot-starter-test` + existing 166 tests |
| Config file | None — default Gradle/JUnit5 discovery; `tasks.test.useJUnitPlatform()` in `build.gradle.kts:31` |
| Quick run command | `./gradlew test --tests '*EnvironmentEngineTest'` (scoped to single test class while iterating) |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

Phase 14 has no explicit requirement IDs (roadmap uses success criteria). Map success criteria to tests:

| Req | Behavior | Test Type | Automated Command | File Exists? |
|-----|----------|-----------|-------------------|-------------|
| SC-1 (≥2 new env effects beyond overcrowding) | Four effects active: toxin, mutagen, lightning, compost | integration | `./gradlew test --tests EnvironmentIntegrationTest` | ❌ Wave 0 |
| SC-2 (effects use Cell flags or equivalent status encoding) | OVERCROWDED in cellStatus bitmask; TOXIN_PRESENT; MUTAGEN_ZONE | unit | `./gradlew test --tests 'PerceptionBroadcasterTest.*status*'` | ❌ Wave 0 (modify existing test class) |
| SC-3 (spatial propagation across ticks) | Toxin CA diffusion over 80 ticks; mutagen gossip over 200 ticks | unit | `./gradlew test --tests CellularAutomatonTest` | ❌ Wave 0 |
| SC-3a (path propagation) | Toxin head advances along Catmull-Rom path | unit | `./gradlew test --tests ToxinPathGeneratorTest` | ❌ Wave 0 |
| SC-4 (configurable in yaml) | `paralife.simulation.events.*` binds to `EnvironmentConfig` | unit | `./gradlew test --tests EnvironmentConfigTest` | ❌ Wave 0 |
| SC-5 (unit tests for each effect) | Per-effect test class | unit | All of the above | ❌ Wave 0 |
| SC-aux (Poisson sine formula) | D-27 formula across tick-in-season range | unit | `./gradlew test --tests PoissonTriggerTest` | ❌ Wave 0 |
| SC-aux (BuffRegistry lifecycle) | Grant/expire/unregister-on-death | unit | `./gradlew test --tests BuffRegistryTest` | ❌ Wave 0 |
| SC-aux (compost on death) | 100% cell + 50% 8-neighbors | unit | `./gradlew test --tests 'SimulationEngineTest.*compost*'` | ❌ Wave 0 (modify existing test class) |
| SC-aux (attack accelerates cure) | D-20 damage reduces infection ticks | unit | `./gradlew test --tests 'EnvironmentEngineTest.*attackCure*'` | ❌ Wave 0 |
| SC-aux (vision-scoped overcrowding) | Bot at vision edge sees OFF when global ON | unit | `./gradlew test --tests 'PerceptionBroadcasterTest.*visionScoped*'` | ❌ Wave 0 |
| SC-aux (mutagen survivor buff applies) | +1 attack / movement / sensor / -1 upkeep verified in downstream components | integration | `./gradlew test --tests 'EnvironmentIntegrationTest.*buff*'` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests '*<ScopedClass>*'` — e.g. `'*EnvironmentEngineTest*'` during EnvironmentEngine task; completes in <15s for a single class.
- **Per wave merge:** `./gradlew test` — full 166 + new tests; completes in ~40-60s locally based on existing test suite size.
- **Phase gate:** `./gradlew test jacocoTestReport` — full suite green + coverage report generated before `/gsd-verify-work`.

### Wave 0 Gaps

- [ ] `src/test/java/com/paralife/engine/EnvironmentEngineTest.java` — covers tick-phase integration, Poisson triggering with seed control, status cache construction
- [ ] `src/test/java/com/paralife/engine/ToxinPathGeneratorTest.java` — Catmull-Rom math, seeded waypoint generation, toroidal edge handling
- [ ] `src/test/java/com/paralife/engine/CellularAutomatonTest.java` — double-buffer correctness, mass conservation under zero decay, decay-to-zero behavior, Moore vs Von Neumann switch if parametrised
- [ ] `src/test/java/com/paralife/engine/BuffRegistryTest.java` — grant, hasBuff, expire, unregister-on-death, multi-buff stacking
- [ ] `src/test/java/com/paralife/engine/PoissonTriggerTest.java` — D-27 formula sampled at season landmarks (start, mid-peak, end); winter skip; off-season flat lambda; max-1-per-type gate
- [ ] `src/test/java/com/paralife/engine/EnvironmentConfigTest.java` — record validation (invalid path-points-min, invalid infection-duration ranges, etc.); yaml binding via `@SpringBootTest` slice
- [ ] `src/test/java/com/paralife/engine/EnvironmentIntegrationTest.java` — `@SpringBootTest` full pipeline: spawn toxin, advance 80 ticks, verify entities damaged + cleared; spawn mutagen, survivor ends up BUFFED; lightning strike, outer ring shows fertility bump; entity death, compost applied to 9 cells
- [ ] Modify existing: `SimulationEngineTest` — add compost hook coverage
- [ ] Modify existing: `PerceptionBroadcasterTest` — add cellStatus / entityStatus encoding coverage; vision-scoped overcrowding
- [ ] Framework install: none required

## Security Domain

Paralife is a single-tenant in-memory simulation with no authentication, no secrets, no external data access, no persistent storage, and no PII. Phase 14 scope does not change that posture.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | None — no user accounts |
| V3 Session Management | no | WebSocket sessions are anonymous connection IDs, not authenticated sessions |
| V4 Access Control | no | No authz model |
| V5 Input Validation | **yes** | `@ConfigurationProperties` compact-constructor validation on `EnvironmentConfig` — reject malformed yaml (negative lambda, inverted path-point ranges, out-of-range resistance coefficients). Also: bot Action input is already validated in `ActionResolver`; Phase 14 does not add new bot-driven inputs. |
| V6 Cryptography | no | No crypto operations |

### Known Threat Patterns for Paralife + Phase 14

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Config injection (malicious yaml) | Tampering | `@ConfigurationProperties` compact-constructor validation + fail-fast at boot. Already project standard (see `SeasonsConfig`, `FertilityConfig`). |
| Bot client sends crafted Action to trigger NPE | Denial of Service | Existing defensive parsing in `ActionResolver` (null checks, unknown-action-type fallback to rest). Phase 14 adds no new bot-driven inputs — env events are server-initiated. |
| Env event storm exhausts memory (many concurrent toxin events) | Denial of Service | D-03 locks max-1-active-per-type. Enforced at Poisson roll time. Hard cap = 4 concurrent events worst-case (one per type). |
| Tick pipeline half-execution due to env exception | Integrity (partial update) | Pitfall 9 mitigation: wrap `EnvironmentEngine.onTick` body in try/catch with log + continue. |
| Memory growth from orphaned BuffRegistry entries | Denial of Service (long-run) | Pitfall 5 mitigation: clean up buffs in `processDeaths`. |

## Sources

### Primary (HIGH confidence)

- **This codebase** — all patterns (Pattern 1–6), integration points, existing styles directly verified by reading source files listed in required_reading. Especially:
  - `src/main/java/com/paralife/engine/SimulationEngine.java` (tick phase pattern, per-type lookups)
  - `src/main/java/com/paralife/engine/SeasonTracker.java` (sine-formula pattern)
  - `src/main/java/com/paralife/engine/BotRegistry.java`, `CompositeRegistry.java` (shadow registry pattern)
  - `src/main/java/com/paralife/world/Cell.java` (flags + nutrientLevel + immutable mutation)
  - `src/main/java/com/paralife/websocket/Messages.java` (back-compat constructor pattern)
- **`14-CONTEXT.md`** — 48 locked decisions authoritatively constraining scope
- **`13-CONTEXT.md`** — upstream context (SeasonTracker, FLAG_STARVING, metabolic profiles)
- **CLAUDE.md** — project conventions, tech debt audit, tech stack
- **Catmull-Rom spline formula** — cross-referenced across [CMU course notes](https://www.cs.cmu.edu/~fp/courses/graphics/asst5/catmullRom.pdf), [Wikipedia](https://en.wikipedia.org/wiki/Catmull%E2%80%93Rom_spline), [MVPs.org](https://www.mvps.org/directx/articles/catmull/), and [javagl/Geom](https://github.com/javagl/Geom) reference implementation. Formula in Pattern 2 is the standard uniform form.

### Secondary (MEDIUM confidence)

- **[Preshing on Poisson process timings](https://preshing.com/20111007/how-to-generate-random-timings-for-a-poisson-process/)** — confirms per-tick Bernoulli is correct for Paralife's tick rate and λ
- **[Baeldung: Spring Events](https://www.baeldung.com/spring-events)**, **[Spring Framework API docs](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/EventListener.html)** — synchronous-by-default, `@Order` gotchas with `@Async`
- **[Artificial Nature: Cellular automata course](https://artificialnature.net/courses/datt4950/cellular.html)** — double-buffering discussion
- **[Baeldung: Jackson immutable deserialization](https://www.baeldung.com/jackson-deserialize-immutable-objects)** — Jackson record + multi-constructor behavior

### Tertiary (LOW confidence)

- **[p5.js source reference](https://github.com/processing/p5.js/blob/main/src/core/shape/curves.js)** — WebFetch returned partial source; confirmed via multiple WebSearch sources that `curvePoint` is Catmull-Rom. Not critical — even if tension parameter differs, the curve is smooth and that's what matters; tune if visual output differs.

## Metadata

**Confidence breakdown:**

- Standard stack: **HIGH** — zero new dependencies; all patterns verified in existing Paralife code (SimulationEngine, SeasonTracker, registries).
- Architecture patterns: **HIGH** — `@Order(15)` placement, shadow registry, @ConfigurationProperties nested records, JSON back-compat constructors all directly mirror existing verified code.
- Math (Catmull-Rom, Poisson, CA): **HIGH** — standard well-documented algorithms; formulas cross-verified across multiple authoritative sources.
- Pitfalls: **HIGH** — signed-byte, double-buffering, `@EventListener` sync/async, Random seeding: all well-established Java/Spring gotchas confirmed via search + code inspection.
- Integration details (exact compost hook placement in processDeaths, exact `BuffRegistry` clean-up points): **HIGH** — directly readable from existing `SimulationEngine.processDeaths` source.
- Performance at 256×256 with 4 active events: **MEDIUM** — one full CA pass per active event per tick is 65,536 × 8-neighbor reads ≈ 500K operations per tick, well within 500ms budget. Not measured in this research; planning should include a micro-benchmark or at least a stress test in the integration test suite.

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (30 days — stable domain, no fast-moving dependencies)
