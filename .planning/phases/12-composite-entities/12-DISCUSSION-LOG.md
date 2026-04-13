# Phase 12: Composite Entities - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 12-composite-entities
**Areas discussed:** BondedPair→Composite transition, Grid representation, Roles, Combat, Vision/Sensor, Movement, Dissolution, Bot control, Energy model

---

## BondedPair → Composite Transition

| Option | Description | Selected |
|--------|-------------|----------|
| Stepping stone | BondedPair + BondedPair → Composite. Two distinct entity types. | ✓ |
| Upgrade path | BondedPair evolves into Composite when it bonds with another entity. | |
| Separate mechanisms | BondedPairs and Composites coexist as independent systems. | |

**User's choice:** Stepping stone
**Notes:** User wants `canFormComposites` toggle for future tweakability between Option A and B. Biological metaphor: BondedPairs = endosymbiosis, Composites = siphonophores (Portuguese man-o'-war).

---

## Grid Representation

| Option | Description | Selected |
|--------|-------------|----------|
| Linked members | CompositeMember entities in cells, CompositeRegistry holds shared state. | ✓ |
| Head + body cells | One head entity owns state, body cells are lightweight pointers. | |
| Virtual overlay | Members stay as BondedPairs, composite exists only in registry. | |

**User's choice:** Linked members
**Notes:** Follows existing BotRegistry pattern.

---

## Roles (Zooid Specialization)

| Option | Description | Selected |
|--------|-------------|----------|
| Type-derived roles | Roles derived from ParticleType, no enum needed. | |
| Explicit role enum | Named roles assigned at formation. | ✓ |
| No specialization | All members equivalent for MVP. | |

**User's choice:** Explicit role enum
**Notes:** User wants all types to fill any role for MVP. Future versions add constraints. Discussed available roles:

### MVP Role Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Core four (LOCOMOTOR, FEEDER, DEFENDER, REPRODUCER) | Maps 1:1 to existing simulation mechanics. | ✓ |
| Add SENSOR | Extends perception for composites. | ✓ |
| Add ANCHOR | Resistance to displacement. | |
| Start with just two | Minimum viable. | |

**User's choice:** Core four + SENSOR (5 roles), then added ATTACKER (6 total)

---

## Offensive Combat

| Option | Description | Selected |
|--------|-------------|----------|
| Position-based | No role needed, offense emergent from positioning. | |
| Add ATTACKER role | Separate role boosting outgoing damage. | ✓ |
| DEFENDER dual-purpose | Rename to WARRIOR, handles both offense and defense. | |

**User's choice:** Add ATTACKER role
**Notes:** User proposed composites bypass RPS entirely. ATTACKERs deal "true damage" (type-agnostic), DEFENDERs absorb additional damage. ATTACKERs have high energy upkeep, DEFENDERs are energy-efficient. Enables emergent archetypes: aggressive predators vs sedentary grazers.

---

## SENSOR Mechanics

| Option | Description | Selected |
|--------|-------------|----------|
| Extended perception radius | Each SENSOR adds +1 ring beyond base 5×5. | |
| Threat detection overlay | Type-only detection at range, like echolocation. | |
| Environmental sensing | Detects cell flags at range, pairs with Phase 14. | |
| Per-member vision circles (user-proposed) | Each SENSOR produces own 5×5 circle, composite gets stitched union. | ✓ |

**User's choice:** Per-member vision circles
**Notes:** User proposed restricting vision to SENSOR members themselves. Each SENSOR produces a 5×5 radius circle of vision, composite gets all sensor details stitched together. Computationally cheap (O(1) array lookups + HashSet deduplication). No SENSOR = blind composite.

---

## Coordinated Movement

| Option | Description | Selected |
|--------|-------------|----------|
| LOCOMOTOR-driven | LOCOMOTOR members drive movement, no locomotor = sessile. | ✓ |
| Head-driven rigid body | First member is head, drives all movement. | |
| Snake-like follow | Lead moves, others follow into predecessor's cell. | |

**User's choice:** LOCOMOTOR-driven
**Notes:** Speed = `locomotor_count / colony_size * constant`. Speed < 1 = move every Nth tick. Movement decision at composite level using stitched sensor perception.

---

## Movement Voting

| Option | Description | Selected |
|--------|-------------|----------|
| Simple majority, random tie-break | Each LOCOMOTOR gets 1 vote. | |
| Energy-weighted voting | Healthier members have more influence. | |
| Threat-priority override | Flee votes trump food-seeking votes. | |
| STV with ranked preferences (user-proposed) | Single Transferable Vote, max 3 preferences. | ✓ |

**User's choice:** STV with max 3 ranked preferences, random tie-break
**Notes:** User specifically requested STV over simple majority.

---

## PROCESSOR Role Discussion

**User raised:** Should there be a PROCESSOR type that aggregates information and directs LOCOMOTORs?

**Conclusion:** Rejected. Two reasons:
1. Core value conflict — project is about emergent behavior from simple rules, PROCESSOR is top-down intelligence
2. Scale demo conflict — PROCESSOR would be server-side logic, not a bot connection

Composite "intelligence" emerges from SENSOR placement quality instead.

---

## Reproduction (REPRODUCER Budding)

| Option | Description | Selected |
|--------|-------------|----------|
| Spawn Particle | REPRODUCER buds solo Particle, reuses existing mechanic. | ✓ |
| Colony fission | Split composite into two at sufficient size. | |
| Both spawn + fission | Small composites bud, large composites fission. | |
| Internal growth | Add member directly, skip Particle/BondedPair stages. | |

**User's choice:** Spawn Particle
**Notes:** Maintains lifecycle: Particle → BondedPair → Composite. Budding recycles to start.

---

## Dissolution vs Degradation

| Option | Description | Selected |
|--------|-------------|----------|
| Graceful degradation | Dead member removed, composite shrinks. | ✓ (97%) |
| Total dissolution | Any member death = whole composite dies. | |
| Dissolution to components | Composite shatters, members revert to BondedPairs. | ✓ (3%) |

**User's choice:** 97% graceful degradation, 3% dissolution to components. Configurable.

---

## Member Death Mechanic

| Option | Description | Selected |
|--------|-------------|----------|
| Critical hit chance (5%) | Random chance to destroy member outright. | |
| Individual energy depletion (user-proposed) | Members die when their individual energy = 0. | ✓ |

**User's choice:** Individual energy depletion
**Notes:** User replaced critical hit RNG model with deterministic individual energy. Members draw from shared pool (rate-limited per role). Focused fire can overwhelm healing rate and kill a member even if pool has energy.

---

## Early Composite Survival

| Option | Description | Selected |
|--------|-------------|----------|
| Base abilities + role bonuses | All members retain basic abilities, roles add bonuses. | |
| Formation energy bonus + reduced upkeep | Energy boost at formation, upkeep scales with size. | |
| Dynamic role switching | Members switch roles until size threshold. | |
| Generalist founders (user-proposed) | Initial pair are generalists, recruits specialize, founders specialize at size > 5. | ✓ |

**User's choice:** Generalist founders model
**Notes:** Initial pair retain basic Particle-level abilities. Every new member picks a specialized role. Once size > 5, initial pair also specialize (optimize for missing roles). Surface FEEDER constraint ensures at least one feeder on composite edge at all times.

---

## Energy Model

| Option | Description | Selected |
|--------|-------------|----------|
| Shared pool only (original) | Single pool, all damage/income/decay against it. | |
| Dual: individual + shared pool (user-proposed) | Members have individual energy + shared pool with rate-limited healing. | ✓ |

**User's choice:** Dual energy model
**Notes:** FEEDER income → shared pool. Decay → individual energy. Combat damage → individual energy. Healing: members draw from pool at role-determined rates. No limit on pool drain rate (prevents artificial size caps).

### Draw Rate Model

| Option | Description | Selected |
|--------|-------------|----------|
| Independent draw | Each member pulls from pool at role's rate per tick. | ✓ |
| Prioritized draw | Members draw in priority order, expensive roles starve first. | |

**User's choice:** Independent draw with per-role rates
**Notes:** Active/passive draw rates for action-capable roles (ATTACKER, LOCOMOTOR, REPRODUCER, FEEDER). Passive-only for SENSOR and DEFENDER.

---

## Starvation (Shared Pool Depletion)

| Option | Description | Selected |
|--------|-------------|----------|
| Total death | Pool = 0, all members die. | ✓ (at 0%) |
| Emergency dissolution | Pool = 0, shatter to components with 1 HP. | |
| Cascading member death | Members die one by one as pool depletes. | |
| Progressive shatter (user-proposed) | Energy < 12%: die roll on each decrease, escalating chance. | ✓ (below 12%) |

**User's choice:** Progressive shatter below 12% energy + total death at 0%
**Notes:** Energy < 12% (configurable): each energy decrease triggers shatter die roll with increasing probability. Energy stable or increasing: no die roll. Energy = 0: total death.

---

## Bot Control

| Option | Description | Selected |
|--------|-------------|----------|
| Composite brain (server-side) | Autonomous, bot sessions released. | |
| Role-based delegation | Each bot controls its role's actions. | |
| Voting each tick | All bots submit full action, majority wins. | |
| Hybrid: reactive roles + LOCOMOTOR voting (emerged from discussion) | Reactive roles auto-act, only movement needs consensus via STV. | ✓ |

**User's choice:** Reactive auto-act for most roles, LOCOMOTOR STV for movement
**Notes:** User identified that only movement truly needs consensus. FEEDER/ATTACKER/DEFENDER/REPRODUCER are reactive (if condition met, act). SENSOR is passive. All bots stay connected — preserves scale demo (hundreds/thousands of active WebSocket connections).

---

## Claude's Discretion

- CompositeRegistry internals and thread-safety
- CompositeMember record field naming
- Config property organization
- Healing draw order when pool insufficient
- Logging levels for composite events
- Stitched perception serialization format

## Deferred Ideas

- Per-type-role constraints (future version)
- Colony fission (reproduction at colony level)
- Composite-vs-composite detailed combat formulas (Phase 13+)
- Internal growth (skip Particle/BondedPair stages)
- ANCHOR role
- Environmental sensing for SENSOR (Phase 14)
- SENSOR extended radius (7×7)
- Dynamic role switching
- PROCESSOR role (rejected — undermines emergence + scale demo)
