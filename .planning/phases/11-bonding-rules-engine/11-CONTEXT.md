# Phase 11: Bonding Rules Engine - Context

**Gathered:** 2026-04-13
**Status:** Ready for planning

<domain>
## Phase Boundary

Define which entity types can combine and under what conditions. Implement bonding logic as a new simulation phase that replaces the existing combat phase with a unified interaction resolution step. Bonding produces a `BondedPair` entity on the grid with a shared energy pool and combat defense benefits. Bonds are irrevocable — both members live or die together.

</domain>

<decisions>
## Implementation Decisions

### Bonding Eligibility
- **D-01:** Predator+prey pairs only for MVP (endosymbiosis model). The predator "engulfs" the prey as an alternative outcome to combat. Catalyst→Spore, Spore→Membrane, Membrane→Catalyst.
- **D-02:** Future expansion to all-combination bonding is planned (scales as triangle number n(n-1)/2). Design should accommodate eventual RPSLS (5 base types → 10 bonded pair types) without code changes to BondedPair itself.

### Bonding Conditions
- **D-03:** Both entities must exceed a configurable energy threshold AND a probability roll must succeed. Two config knobs.
- **D-04:** Parameters are global — single energy threshold and single probability for all predator-prey pairs. No per-type-pair configuration for MVP.

### Bond Representation
- **D-05:** `BondedPair` is a new sealed subtype of `Entity` with flat fields: `BondedPair(id, primaryType, secondaryType, energy, maxEnergy)`.
- **D-06:** Single shared energy pool — energy and maxEnergy are the sum of both members' values at bonding time. No individual member energy tracking.
- **D-07:** Primary/secondary roles are generic (not RPS-specific). For MVP, bonding rule assigns predator=primary, prey=secondary. Role assignment logic lives in the bonding rule, not in BondedPair.
- **D-08:** When a bond forms, the secondary's cell becomes empty. The BondedPair occupies the primary's cell.

### Pipeline Integration
- **D-09:** Replace `processCombat()` with `processInteractions()`. Same grid scan and neighbor iteration, but outcome branches to combat or bonding based on conditions. Combat becomes one interaction outcome, bonding another. Single concept, naturally extensible for future interaction types.
- **D-10:** Interaction resolution order: when predator meets prey, check bonding eligibility first (energy threshold + probability). If bonding triggers, create BondedPair. Otherwise, resolve as combat (existing logic).

### Bonding Abilities
- **D-11:** Shared energy pool — combined energy of both members at bonding time. All damage, decay, and consumption operates on this single pool.
- **D-12:** Combat defense — when a BondedPair's primary type would lose combat, the secondary's type grants a configurable chance to deflect the attack. Starting value: 25% (`bond-defense-chance: 0.25`). Probabilistic, not immunity.

### Bond Dissolution & Death
- **D-13:** Bonds are irrevocable. No mechanism to dissolve a bond and split back into solo Particles.
- **D-14:** Death is all-or-nothing: if shared energy pool hits 0, both members die (BondedPair removed from grid). No solo survivor.
- **D-15:** Successful combat attacks (past the defense chance) reduce the shared energy pool. If this brings energy to 0, the pair dies.

### Configuration
- **D-16:** All bonding parameters configurable via `application.yml` under `paralife.simulation` (or a new `paralife.bonding` prefix — Claude's discretion on config organization):
  - Energy threshold for bonding eligibility
  - Bonding probability
  - Defense chance (starting at 0.25)

### Claude's Discretion
- Config record organization — whether to extend `SimulationConfig` or create a new `BondingConfig` record
- Naming conventions for new methods and config properties
- Whether bonding events are logged at DEBUG or INFO level
- Internal implementation details of the interaction resolution refactor

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Entity Model
- `src/main/java/com/paralife/world/Entity.java` — Sealed Entity hierarchy; BondedPair will be added here
- `src/main/java/com/paralife/world/Cell.java` — Cell record with flags system

### Simulation Engine
- `src/main/java/com/paralife/engine/SimulationEngine.java` — Contains `processCombat()` to be refactored into `processInteractions()`
- `src/main/java/com/paralife/engine/SimulationConfig.java` — `@ConfigurationProperties` record pattern for config binding

### Configuration
- `src/main/resources/application.yml` — Where bonding config values will be added

### Architecture
- `.planning/codebase/ARCHITECTURE.md` — Full system architecture and data flow documentation

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Entity` sealed interface with immutable record pattern — BondedPair follows the same convention
- `SimulationConfig` `@ConfigurationProperties` record — pattern for bonding config
- `processCombat()` neighbor iteration and snapshot-read/deferred-write pattern — reusable for interaction resolution
- `Cell.withOccupant()` / `Cell.cleared()` — immutable mutation for bond formation (clear secondary's cell, update primary's cell)
- `WorldGrid.getNeighbors()` — Moore neighbourhood lookup for proximity checks

### Established Patterns
- Immutable records for all data — BondedPair must follow this
- Sealed interfaces for polymorphism — BondedPair extends Entity sealed interface
- Snapshot reads + deferred writes in combat — interaction resolution should use the same pattern
- `@ConfigurationProperties` on records with constructor validation

### Integration Points
- `SimulationEngine.processTick()` — refactor combat phase to interaction phase
- `Entity` sealed interface — add `BondedPair` permit
- `Messages.java` — bonding events may need new message types for tick output observability
- `PerceptionBroadcaster` — needs to handle `BondedPair` entities in 5×5 neighbourhood
- `TickBroadcaster` — grid snapshot serialization needs to include BondedPair data
- `application.yml` — new bonding config properties

</code_context>

<specifics>
## Specific Ideas

- **Endosymbiosis model** — bonding mirrors how mitochondria formed: predator engulfs prey, both benefit from mutualism. This is the core narrative for the mechanic.
- **High risk, high reward** — irrevocable bonds with shared fate create meaningful strategic tension. Players (bots) must weigh the defensive benefit against the permanent commitment.
- **Unified interaction concept** — combat and bonding are two outcomes of the same encounter, not separate systems. This is more elegant than bolt-on bonding and naturally extensible.

</specifics>

<deferred>
## Deferred Ideas

- All-combination bonding (not just predator+prey) — scales as triangle number n(n-1)/2 for new entity types
- RPSLS expansion to 5 base types (10 bonded pair types)
- Per-type-pair bonding parameters (different thresholds per combination)
- Sustained proximity bonding condition (entities adjacent for N ticks before bonding)
- Reduced energy decay as a bonding benefit
- Bond dissolution mechanics (explicitly rejected for MVP — bonds are irrevocable)

</deferred>

---

*Phase: 11-bonding-rules-engine*
*Context gathered: 2026-04-13*
