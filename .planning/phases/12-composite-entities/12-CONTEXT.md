# Phase 12: Composite Entities - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Multi-cell organisms (composites) with shared state that move and act as a unit on the grid. Composites form when adjacent BondedPairs merge (stepping stone from Phase 11 endosymbiosis). Each member occupies its own cell, specializes into a role (siphonophore model), and draws energy from a shared pool at role-determined rates. Basic combat mechanics included; detailed composite-vs-composite combat deferred to Phase 13+.

</domain>

<decisions>
## Implementation Decisions

### Formation & Transition (BondedPair → Composite)
- **D-01:** Stepping stone model — BondedPair + adjacent BondedPair → Composite. BondedPair remains single-cell (endosymbiosis); Composite is multi-cell (siphonophore). Two distinct entity types on the grid.
- **D-02:** `canFormComposites` property on entities to toggle between stepping stone (Option A) and upgrade path (Option B) in future. Design for tweakability.
- **D-03:** Biological inspiration: BondedPairs = endosymbiosis (mitochondria model, Phase 11). Composites = siphonophores (colonial organisms like Portuguese man-o'-war).

### Grid Representation
- **D-04:** Linked members model — new `CompositeMember` entity type in each member's cell, all sharing a `compositeId`. Separate `CompositeRegistry` (like `BotRegistry`) holds shared state: member list, positions, energy pool, formation shape.
- **D-05:** `CompositeMember` record implements `Entity` sealed interface. Fields: `id`, `compositeId`, `type` (original ParticleType), `role`, `energy` (individual), `maxEnergy`.

### Roles (Siphonophore Zooid Specialization)
- **D-06:** Six roles via explicit `Role` enum: `LOCOMOTOR`, `FEEDER`, `ATTACKER`, `DEFENDER`, `REPRODUCER`, `SENSOR`.
- **D-07:** For MVP, all ParticleTypes can fill any role. Future versions will add type-to-role constraints.
- **D-08:** Role assignment at formation: initial pair (from BondedPair merge) retain basic Particle-level abilities (generalists). Every new member added picks a specialized role. Once composite size > 5, initial pair also specialize, optimizing for missing roles.
- **D-09:** Surface FEEDER constraint: at least one member capable of feeding must be present on the surface (edge) of all composites at all times. Prevents starvation by design.

### Combat (Basics — Phase 12 Scope)
- **D-10:** Composites bypass RPS system entirely. ATTACKER members deal "true damage" (type-agnostic). DEFENDER members absorb additional damage. Inspired by stinging tendrils vs defensive chitin.
- **D-11:** Offense is position-based for non-role combat — members adjacent to enemies engage using base mechanics. ATTACKER role adds damage multiplier on top.
- **D-12:** Solo entity attacks CompositeMember → damage hits targeted member's individual energy. If member energy reaches 0, member dies (triggers dissolution rules).
- **D-13:** Composite-vs-composite: emergent from member-by-member interactions. No special composite-level combat logic for MVP. Detailed combat formulas deferred to Phase 13+.

### Energy Model (Dual: Individual + Shared Pool)
- **D-14:** Each CompositeMember has individual energy. Composite also has a shared energy pool. Energy flows between them.
- **D-15:** Energy flow rules:
  - FEEDER consumes nutrient → energy goes to **shared pool**
  - Energy decay (per tick) → drawn from **individual member energy**
  - Healing: members draw from **shared pool → individual energy** at role-determined rates
  - Combat damage → hits **targeted member's individual energy** directly
  - Member death trigger: individual energy = 0
- **D-16:** Draw rates set at Role level (configurable). No limit on how fast pool can be drained — prevents artificial composite size caps. Energy economics self-regulate maximum viable size.
- **D-17:** Active and passive draw rates for action-capable roles:
  - ATTACKER: active (dealing damage) / passive (idle) — expensive role, high active upkeep
  - LOCOMOTOR: active (composite moves) / passive (idle) — moderate-high
  - REPRODUCER: active (budding) / passive (idle) — moderate
  - FEEDER: active (consuming) / passive (idle) — moderate
  - SENSOR: passive only — cheap (always sensing)
  - DEFENDER: passive only — efficient (reactive, not active choice)
- **D-18:** Emergent archetypes from energy economics: aggressive predators (many ATTACKERs, high drain, must hunt continuously) vs sedentary grazers (many DEFENDERs, low drain, thick shells).

### Vision & Perception
- **D-19:** Only SENSOR members produce vision. Each SENSOR generates a 5×5 perception circle around its position. Composite perception = union of all SENSOR circles, stitched together and deduplicated.
- **D-20:** No SENSOR members = composite is blind. Strong evolutionary pressure to include at least one sensor.
- **D-21:** Non-SENSOR members contribute no vision. Computational cost is cheap — grid reads are O(1) array lookups, stitching via HashSet deduplication on Position.

### Coordinated Movement
- **D-22:** LOCOMOTOR-driven movement. No LOCOMOTOR = sessile (stationary) composite. Enables sedentary grazer archetype.
- **D-23:** Movement speed = `locomotor_count / colony_size * constant` (configurable). Speed < 1 means composite can only move every Nth tick. Bigger colonies need more locomotors to maintain speed.
- **D-24:** Rigid body translation — all members shift in the same direction. Formation shape preserved. Blocked if ANY member's target cell is occupied.
- **D-25:** Movement decision via composite-level brain using stitched sensor perception and energy level. Priority cascade: flee threats > hunt (ATTACKER-driven) > eat (FEEDER seeks nutrients) > grow (absorb adjacent BondedPairs) > bud (REPRODUCER spawns Particle) > explore > rest.

### Movement Voting (LOCOMOTOR Consensus)
- **D-26:** LOCOMOTOR members vote on movement direction. Single Transferable Vote (STV) with max 3 ranked preferences, random tie-break.
- **D-27:** Each LOCOMOTOR bot receives composite's stitched perception, independently evaluates, and submits ranked direction preferences. Disagreements are emergent — create realistic hesitation behavior.
- **D-28:** No PROCESSOR role — composite "intelligence" emerges from SENSOR placement quality. Better sensor coverage → richer stitched perception → better informed LOCOMOTOR votes → smarter movement. Emergence over centralization.

### Dissolution & Death
- **D-29:** Member death (individual energy = 0): 97% chance of graceful degradation (composite shrinks, dead member removed). 3% chance of dissolution to components (composite shatters, surviving members revert to BondedPairs/Particles). Configurable percentages.
- **D-30:** 2-member composite losing a member → reverts to BondedPair. BondedPair death follows Phase 11 D-14 (all-or-nothing).
- **D-31:** Shared pool depletion: energy < 12% (configurable threshold) triggers progressive shatter die roll on each energy decrease. Lower energy = higher shatter chance. Energy stable or increasing = no die roll. Energy = 0: total death (all members removed, consistent with BondedPair D-14).

### Reproduction
- **D-32:** REPRODUCER buds a solo Particle into adjacent empty cell. Reuses existing reproduce mechanic and energy cost (from shared pool). Particle type inherited or random. Maintains lifecycle: Particle → BondedPair → Composite.

### Bot Control (Scale Demo Preservation)
- **D-33:** All bot sessions stay connected when their entities join a composite. No disconnections. Preserves hundreds/thousands of active WebSocket connections for portfolio demo.
- **D-34:** Reactive roles auto-act without consensus: FEEDER eats if food in range, ATTACKER attacks if prey in range, DEFENDER defends if under attack, REPRODUCER buds if energy sufficient. SENSOR is passive.
- **D-35:** Only movement requires consensus (LOCOMOTOR STV voting). All other roles are autonomous based on local conditions.
- **D-36:** All member bots receive composite's stitched perception each tick. All remain active WebSocket clients sending and receiving messages.

### Claude's Discretion
- CompositeRegistry internal data structures and thread-safety approach
- CompositeMember record field naming and convenience constructors
- Config property organization (extend SimulationConfig, new CompositeConfig, or both)
- Healing draw order when pool has insufficient energy for all members in a tick
- Logging levels for composite lifecycle events
- How stitched perception is serialized for WebSocket broadcast

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Entity Model
- `src/main/java/com/paralife/world/Entity.java` — Sealed Entity hierarchy; CompositeMember will be added here alongside BondedPair
- `src/main/java/com/paralife/world/Cell.java` — Cell record with flags system

### Simulation Engine
- `src/main/java/com/paralife/engine/SimulationEngine.java` — processInteractions needs composite combat handling; processDeaths needs composite member death logic
- `src/main/java/com/paralife/engine/SimulationConfig.java` — `@ConfigurationProperties` record pattern for config binding
- `src/main/java/com/paralife/engine/BondingConfig.java` — Bonding config pattern to follow for CompositeConfig

### Action Resolution
- `src/main/java/com/paralife/engine/ActionResolver.java` — Currently only handles Particles; must handle composite member actions (reactive roles + LOCOMOTOR voting)
- `src/main/java/com/paralife/engine/BotRegistry.java` — Pattern for CompositeRegistry; session↔entity mapping must support composite membership

### Perception
- `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` — Must support per-SENSOR perception circles, stitching, and broadcast to all composite members

### WebSocket
- `src/main/java/com/paralife/websocket/Messages.java` — New message types needed for composite perception, role-based actions, STV voting
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — Grid snapshot serialization must include CompositeMember data

### Configuration
- `src/main/resources/application.yml` — Composite config values (role draw rates, dissolution chances, speed constants, critical thresholds)

### Prior Phase Context
- `.planning/phases/11-bonding-rules-engine/11-CONTEXT.md` — BondedPair decisions that composites build on (D-05 through D-16)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Entity` sealed interface with immutable record pattern — CompositeMember follows same convention
- `BotRegistry` ConcurrentHashMap-based registry — pattern for CompositeRegistry
- `BondingConfig` @ConfigurationProperties record — pattern for CompositeConfig
- `SimulationEngine.processInteractions()` snapshot-read/deferred-write pattern — reusable for composite combat
- `Cell.withOccupant()` / `Cell.cleared()` — immutable mutation for composite formation
- `WorldGrid.getNeighbors()` — Moore neighbourhood for composite surface detection

### Established Patterns
- Immutable records for all data — CompositeMember must follow
- Sealed interfaces for polymorphism — CompositeMember extends Entity sealed interface
- `@ConfigurationProperties` on records with constructor validation
- Spring `@EventListener` with `@Order` for tick pipeline sequencing
- Snapshot reads + deferred writes in interaction resolution

### Integration Points
- `Entity` sealed interface — add `CompositeMember` permit
- `SimulationEngine.processInteractions()` — add composite combat cases
- `SimulationEngine.processDeaths()` — add composite member death with dissolution logic
- `ActionResolver` — add composite member action handling (reactive roles + STV)
- `PerceptionBroadcaster` — per-SENSOR circles with stitching
- `TickBroadcaster` — composite data in grid snapshots
- `Messages.java` — new message types for composite interactions
- `application.yml` — composite configuration properties
- New `CompositeRegistry` component — shared state management
- New tick pipeline phase for composite energy distribution (healing/draw)

</code_context>

<specifics>
## Specific Ideas

- **Siphonophore model** — composites are colonial organisms where each member (zooid) specializes for a function. Portuguese man-o'-war is the reference organism. Individual zooids can't survive alone but the colony operates as a superorganism.
- **Emergent intelligence without centralization** — no PROCESSOR role. Composite "intelligence" emerges from sensor placement quality. Better sensor coverage → better informed locomotor votes → smarter movement. This preserves the "emergent behavior from simple rules" core value.
- **Energy economics as natural selection** — role upkeep costs (active/passive draw rates) create natural selection pressure. Aggressive predator composites (many ATTACKERs) must hunt continuously or starve. Sedentary grazers (many DEFENDERs) are sustainable but slow. Optimal composition is an emergent discovery, not a designed outcome.
- **Scale demo preservation** — all bot sessions remain active WebSocket connections when joining composites. Composite members are MORE traffic-intensive than solo particles (receive stitched perception, send role-specific actions, LOCOMOTOR voting). Growing composites = growing message volume per tick.
- **Focused fire viability** — dual energy model (individual + shared pool) with rate-limited healing means concentrated damage on one member can overwhelm healing and kill the member, even if the pool has energy. Rewards tactical play.
- **Panic zone** — composites below 12% energy progressively destabilize with increasing shatter chance. Creates dramatic colony collapses and prevents zombie composites lingering at near-zero energy.

</specifics>

<deferred>
## Deferred Ideas

- Per-type-role constraints (e.g., CATALYST can only be FEEDER or LOCOMOTOR) — future version after MVP
- Colony fission (composite splits into two at sufficient size + energy) — reproduction at colony level
- Composite-vs-composite detailed combat formulas, damage scaling, formation bonuses — Phase 13+
- Internal growth (REPRODUCER adds member directly, skipping Particle/BondedPair stages)
- ANCHOR role (resistance to displacement, terrain bonding)
- Environmental sensing for SENSOR (cell flags, toxins) — best paired with Phase 14
- Composite perception radius extension (SENSOR gets 7×7 instead of base 5×5)
- Dynamic role switching for small composites (deferred in favor of generalist founders model)
- PROCESSOR role for centralized decision-making (rejected — undermines emergence + scale demo)

</deferred>

---

*Phase: 12-composite-entities*
*Context gathered: 2026-04-14*
