# Project

## What This Is

Paralife is a distributed living simulation — a toroidal 2D world populated by competing entity types (Catalyst, Membrane, Spore) in rock-paper-scissors dynamics. A Spring Boot server runs the physics tick loop, broadcasts state via WebSocket, and receives actions from autonomous heuristic bot clients. Built with Java 21 virtual threads for massive concurrency with simple blocking code. Portfolio piece demonstrating scale engineering for a Canva backend engineer application.

## Core Value

Emergent spatial behaviour — spiral waves, population oscillations, and niche formation — arising from simple local rules. A testbed for evolving entity intelligence from heuristic bots toward genetic/learning systems.

## Requirements

### Validated (v1.0 — M001 + M002, shipped 2026-04-12)

- Server boots, tick loop runs at configurable rate, virtual threads enabled
- Bots connect via WebSocket `/ws/world`, receive tick events, submit one action per tick
- 100+ concurrent WebSocket connections sustained without errors
- Toroidal grid wraps correctly
- Three entity types coexist with RPS dynamics producing population oscillations over 500+ ticks
- Entities have energy that decays; consuming restores energy; death at zero clears cell
- Rocks are impassable and nutrients spawn periodically
- Cell flags system supports overcrowding environmental effect
- 100 bot clients connect simultaneously without tick drift exceeding 10%
- Simulation produces different outcomes across runs due to randomness

**Score: 14.5/15 success criteria satisfied** (1 partial: graceful shutdown relies on Spring Boot defaults)

### Active (v2.0 — M3: Combination & Emergence)

- ~~Bonding rules engine: entity types combine under configurable conditions~~ *(Validated in Phase 11: Bonding Rules Engine)*
- Composite entities: multi-cell organisms with shared state, coordinated movement
- Energy & metabolism system: metabolic rates, starvation, reproduction gated by surplus
- Environmental rules: toxin spread, food regeneration, decay, spatial propagation
- Emergent behavior tests: deterministic seed tests, composite clustering, no regressions

### Out of Scope (deferred to future milestones)

- **M4: Scale Engineering (10K+ entities)** — world partitioning, virtual thread tuning, backpressure, connection multiplexing. Can start after M1, parallel with M2/M3.
- **M5: Observability & Operations** — structured logging, metrics (Micrometer/Prometheus), distributed tracing, graceful shutdown, live world visualizer. Needs M2 entities for meaningful metrics.
- **M6: Deployment (Fly.io + Railway)** — Dockerization, multi-region deploy, proxy layer, CI/CD, chaos engineering. Depends on M4 + M5.

## Context

### Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 (virtual threads via Project Loom) |
| Framework | Spring Boot 3.4.4 |
| WebSocket | Raw `WebSocketHandler` (not STOMP) |
| Build | Gradle with Kotlin DSL |
| Testing | JUnit 5 (202 tests, all passing) |

### Codebase

- 23 main source files across 4 packages (world, engine, websocket, bot)
- 20 test files, 166 tests
- 4-layer architecture: World -> Engine -> WebSocket -> Bot
- Event-driven tick pipeline using Spring `@EventListener` with `@Order`

### Tech Debt (5 items, all low severity)

| # | Phase | Item | Severity |
|---|-------|------|----------|
| 1 | 06 | `Cell.nutrientLevel` field is inert — defined but never written | Low (future feature, addressed by Phase 13) |
| 2 | 08 | `PerceptionBroadcaster` UNKNOWN type workaround for dead entities | Low |
| 3 | 09 | `HeuristicBrain.predatorType` ternary dead branch | Low (cosmetic) |
| 4 | 09 | `BotClient` uses raw JsonNode instead of Messages sealed types | Low (cosmetic) |
| 5 | 10 | `LoadTest` omits explicit nutrient-consume-energy property | Low |

### Open Design Questions for M3

1. **Composite entities on grid:** How do multi-cell organisms map to Cell[][]? Each member occupies a cell, or super-entity abstraction spans cells?
2. **Metabolism rework:** Is existing energy system (decay/consume/reproduce) sufficient, or does it need fundamental rework?
3. **Bonding semantics:** Do bonded entities move as a unit? What happens when one member dies?

## Constraints

- Java 21 (required for virtual threads)
- Spring Boot 3.4.4 (existing framework, not negotiable for this milestone)
- Existing architecture patterns: sealed interfaces, immutable records, ReentrantReadWriteLock on WorldGrid, Spring event-driven pipeline
- No external databases — all state in-memory on the server

## Key Decisions

| # | Scope | Decision | Choice | Revisable? |
|---|-------|----------|--------|------------|
| D001 | arch | Client-server connectivity | WebSocket | Yes |
| D002 | arch | Concurrency model | Virtual Threads (Java 21) | Yes |
| D003 | arch | World grid topology | 2D toroidal grid | Yes |
| D004 | library | Build system | Gradle with Kotlin DSL | No |
| D005 | arch | Spring WebSocket approach | Raw WebSocketHandler (not STOMP) | Yes — if routing complexity grows |
| D006 | arch | Grid internal representation | Cell[][] with sealed Entity interface | Yes — if profiling shows overhead |
| D007 | arch | Entity agency model | Simple rule-based bots with local vision | Yes — planned evolution toward genetics |

Full decision rationale in `.gsd/DECISIONS.md`.

---

*Last updated: 2026-04-13*
