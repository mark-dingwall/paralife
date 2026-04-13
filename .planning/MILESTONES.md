# Milestones

## v1.0 — Foundation & Living Simulation

**Shipped:** 2026-04-12
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
