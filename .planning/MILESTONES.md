# Milestones

Internal milestone archive. `v1.0` / `v2.0` are planning-era milestone identifiers, not public release versions.


## v2.0 Combination & Emergence (Completed: 2026-04-22)

**Phases completed:** 8 phases, 36 plans

**Key accomplishments:**

- Bonding, composites, richer metabolism, and environmental effects completed across Phases 11-14, turning the original RPS simulation into a multi-system emergent ecosystem.
- Phase 15 replaced the JSON transport with a compact codec-native protocol on Jetty 12, added permessage-deflate negotiation, zero-trust perception filtering, and stateless reactive bots.
- Phase 15.1 landed the permanent `BotRunner` / `runBot` operator path, and Phase 15.2 closed the latent own-death wire gap so Phase 15 UAT returned to `7/7` pass.
- Phase 16 added seeded determinism hooks, emergence metrics, long-run load fixtures, and helper infrastructure for reproducible emergence testing.
- `16-EMERGENCE.md` landed with fixture-backed narrative evidence, and `./gradlew test -PincludeLong=true` passed as the v2.0 regression gate.
- The missing world-level population back-pressure requirement was recovered as a temporary registration cap, with durable-policy redesign and offspring agency both parked in backlog as 999.1 and 999.2.

**Known deferred items at close:** 1 acknowledged artifact item (see `STATE.md` Deferred Items)

---

## v1.0 — Foundation & Living Simulation

**Completed:** 2026-04-12
**Phases:** 10 (01-10)
**Plans:** 11 (across phases 01, 06-10)
**Tests:** 166 passing
**Score:** 14.5/15 success criteria (1 partial)

### Key Accomplishments

- Spring Boot 3.4.4 server with Java 21 virtual threads, toroidal 256x256 grid, configurable tick engine
- Raw WebSocket protocol at `/ws/world` supporting 100+ concurrent connections
- Sealed Entity interface (Particle/Rock/Nutrient) with Cell[][] grid and bitfield flags
- 4-phase SimulationEngine: combat (RPS dynamics), energy decay, death removal, nutrient spawning
- Overcrowding environmental effect via Cell.FLAG_OVERCROWDED
- PerceptionBroadcaster (5x5 neighbourhood) and ActionResolver (move/consume/reproduce/rest)
- HeuristicBrain with 6-priority decision cascade (flee > chase > consume > reproduce > forage > walk)
- Population stability verified over 500+ ticks with all 3 entity types surviving
- Load tested with 100 concurrent bots on 128x128 grid with no data corruption

### Decisions Made

D001-D007 covering: WebSocket connectivity, virtual threads, toroidal grid, Gradle Kotlin DSL, raw WebSocketHandler, Cell[][] with sealed Entity, simple rule-based bots.

### Tech Debt Carried Forward

5 low-severity items (see PROJECT.md). Item #1 (inert Cell.nutrientLevel) targeted for resolution in v2.0 Phase 13.

### Archive

- `milestones/v1.0-ROADMAP.md` — Original roadmap
- `milestones/v1.0-MILESTONE-AUDIT.md` — Completion audit with scores and tech debt inventory
