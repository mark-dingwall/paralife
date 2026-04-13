# Phase 11: Bonding Rules Engine - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-13
**Phase:** 11-bonding-rules-engine
**Areas discussed:** Bonding eligibility, Bonding conditions, Bond representation, Pipeline integration, Bonding abilities, Bond dissolution

---

## Bonding Eligibility

| Option | Description | Selected |
|--------|-------------|----------|
| Same-type only | Catalyst+Catalyst, Membrane+Membrane, Spore+Spore | |
| Cross-type (non-predator) | Any two types not in predator-prey relationship | |
| Any adjacent particles | Any two Particles regardless of type | |
| RPS-complementary only | Only predator+prey pairs can bond | ✓ |

**User's choice:** Predator+prey only, modeled on endosymbiosis (mitochondrial formation). Predator engulfs prey as alternative to combat.
**Notes:** User explicitly requested endosymbiosis model. MVP scoped to predator+prey; all-combination bonding deferred. User noted pair count scales as triangle number n(n-1)/2 and plans eventual RPSLS expansion (5 types, 10 pairs).

---

## Bonding Conditions

| Option | Description | Selected |
|--------|-------------|----------|
| Energy threshold | Both entities above energy threshold | |
| Probability roll | Configurable probability per encounter | |
| Energy + probability | Both threshold AND probability | ✓ |
| Sustained proximity | Adjacent for N consecutive ticks | |

**User's choice:** Energy threshold + probability roll (both must pass).
**Notes:** Global parameters — single threshold and probability for all pairs. No per-type-pair config for MVP.

---

## Bond Representation

| Option | Description | Selected |
|--------|-------------|----------|
| Bond registry | Separate lookup table, particles stay in own cells | |
| BondedPair sealed subtype | New Entity subtype holding bond state inline | ✓ |
| Particle with bond field | Optional field on Particle record | |

**User's choice:** BondedPair as new sealed subtype of Entity.
**Notes:** User drove efficiency discussion — with thousands of particles, registry lookups on hot path are too expensive. instanceof check is free. Later revised to flat fields (primaryType, secondaryType, energy, maxEnergy) instead of embedded Particles, after deciding on single shared energy pool.

### Sub-decision: Member roles

| Option | Description | Selected |
|--------|-------------|----------|
| Primary/secondary | Generic ordered roles, bonding rule assigns | ✓ |
| Unordered (set-like) | No role distinction | |

**User's choice:** Primary/secondary with generic names. Bonding rule assigns roles (MVP: predator=primary).

### Sub-decision: Flat vs embedded

| Option | Description | Selected |
|--------|-------------|----------|
| Flat fields | BondedPair(id, primaryType, secondaryType, energy, maxEnergy) | ✓ |
| Embedded Particles | BondedPair(id, Particle primary, Particle secondary) | |

**User's choice:** Flat fields — no embedded Particles needed since energy is a single shared pool.

---

## Pipeline Integration

| Option | Description | Selected |
|--------|-------------|----------|
| Inside combat phase | Check bonding within processCombat() | |
| Separate phase after combat | New processBonding() method | |
| Separate @Component | New BondingEngine with own @Order | |
| Unified interaction step | Replace processCombat() with processInteractions() | ✓ |

**User's choice:** Replace processCombat() with processInteractions() — unified concept where combat and bonding are two outcomes of the same encounter.
**Notes:** User proposed this approach (not from the original options). More elegant than any bolt-on option. Single grid scan, naturally extensible for future interaction types.

---

## Bonding Abilities

| Option | Description | Selected |
|--------|-------------|----------|
| Shared energy pool | Combined energy from both members | ✓ |
| Combat advantage | Defense from secondary's type | ✓ |
| Reduced energy decay | Slower decay rate for bonded pairs | |
| Claude's discretion | Let Claude pick | |

**User's choice:** Shared energy pool + combat advantage (multi-select).
**Notes:** Combat advantage refined by user: secondary confers a configurable percent chance (starting 0.25) to deflect attacks that would defeat the primary's type. Not immunity — probabilistic defense.

---

## Bond Dissolution

| Option | Description | Selected |
|--------|-------------|----------|
| Member death | One dies, other becomes solo | |
| Energy starvation | Bond breaks below threshold | |
| Configurable lifespan | Auto-dissolve after N ticks | |
| Combat damage | Attack breaks bond | |
| Irrevocable bond | No dissolution — both live or die together | ✓ |

**User's choice:** Bonds are irrevocable. No dissolution mechanism.
**Notes:** User explicitly designed for high risk, high reward. If shared energy pool hits 0, both die. No solo survivor. User rejected energy starvation dissolution and combat damage dissolution in favor of permanent commitment. Separation mechanics not needed.

### Sub-decision: Damage distribution

| Option | Description | Selected |
|--------|-------------|----------|
| Type-targeted | Damage routes to vulnerable member | |
| Single shared pool | All damage to merged energy pool | ✓ |
| Even split | 50/50 between members | |
| Random weighted split | Randomised allocation | |

**User's choice:** Single shared pool — simplest model for MVP.

---

## Claude's Discretion

- Config record organization (extend SimulationConfig vs new BondingConfig)
- Naming conventions for new methods and config properties
- Log level for bonding events
- Internal implementation details of interaction resolution refactor

## Deferred Ideas

- All-combination bonding (not just predator+prey)
- RPSLS expansion to 5 base types
- Per-type-pair bonding parameters
- Sustained proximity bonding condition
- Reduced energy decay as bonding benefit
- Bond dissolution mechanics
