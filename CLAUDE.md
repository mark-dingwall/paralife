<!-- GSD:project-start -->
## Project

Paralife is a distributed living simulation — a toroidal 2D world populated by competing entity types (Catalyst, Membrane, Spore) in rock-paper-scissors dynamics. A Spring Boot server runs the physics tick loop, broadcasts state via WebSocket, and receives actions from autonomous heuristic bot clients. Built with Java 21 virtual threads for massive concurrency with simple blocking code.

**Core Value:** Emergent spatial behaviour — spiral waves, population oscillations, and niche formation — arising from simple local rules. A testbed for evolving entity intelligence from heuristic bots toward genetic/learning systems.

**Requirements** (from milestone success criteria):
- M001: Server boots, tick loop runs, WebSocket broadcasts, 100 concurrent bots sustained
- M002: RPS entity dynamics, 4-phase physics, perception/action protocol, heuristic bots, population stability over 500+ ticks
<!-- GSD:project-end -->

<!-- GSD:technology-start -->
## Technology Stack

- **Java 21** — Virtual threads enabled (`spring.threads.virtual.enabled: true`)
- **Spring Boot 3.4.4** — `starter-web`, `starter-websocket`, `starter-actuator`
- **Gradle Kotlin DSL** — Build system with wrapper (`./gradlew`)
- **JUnit 5** — 166 tests (unit + integration)
- **JaCoCo** — Coverage reporting (XML + HTML)
- **Jackson** — JSON serialization (transitive via Spring)
- **`@ConfigurationProperties`** bound to records: `GridConfig`, `TickConfig`, `SimulationConfig`
<!-- GSD:technology-end -->

<!-- GSD:conventions-start -->
## Conventions

**Package structure:** `com.paralife.{world,engine,websocket,bot}` — flat single-level per layer.

**Data modeling:** Immutable records throughout. Sealed interfaces for polymorphism (`Entity`, `Messages`). Mutations produce new instances (`Cell.withOccupant()`, `Particle.withEnergy()`).

**Concurrency:** Single-threaded simulation core (all world mutations in tick event handlers). Virtual threads for I/O (WebSocket, tick loop heartbeat). `ReentrantReadWriteLock` on `WorldGrid` — read lock for snapshots, write lock for mutations.

**Spring patterns:** `@Component` beans for all services. `@EventListener` with `@Order` for tick pipeline sequencing. `@ConfigurationProperties` on records for type-safe config binding. Raw `WebSocketHandler` (not STOMP) for full protocol control.

**Testing:** `*Test.java` for unit tests, `*IntegrationTest.java` for integration tests. Mirror source directory structure. `@SpringBootTest` for integration tests.

**Build commands:**
```bash
./gradlew test              # Run all tests
./gradlew bootRun           # Start server on :8080
./gradlew jacocoTestReport  # Generate coverage report
```
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start -->
## Architecture

**4 layers:**

| Layer | Package | Key Files |
|-------|---------|-----------|
| World | `com.paralife.world` | `WorldGrid`, `Cell`, `Entity` (sealed), `Position` |
| Engine | `com.paralife.engine` | `SimulationEngine`, `ActionResolver`, `PerceptionBroadcaster`, `TickEngine`, `BotRegistry` |
| WebSocket | `com.paralife.websocket` | `WorldWebSocketHandler`, `TickBroadcaster`, `Messages` (sealed), `SessionRegistry` |
| Bot | `com.paralife.bot` | `BotClient`, `HeuristicBrain`, `BotLauncher` (test-only, not deployed) |

**Tick pipeline** (Spring `@EventListener` on `TickEvent`):
1. `SimulationEngine` `@Order(10)` — Combat, energy decay, death removal, nutrient spawning
2. `ActionResolver` `@Order(20)` — Drain pending bot actions, resolve moves/consume/reproduce/rest
3. `PerceptionBroadcaster` `@Order(50)` — Send 5x5 neighbourhood perception to each bot
4. `TickBroadcaster` `@Order(100)` — Broadcast tick snapshot to all connected clients

**Entry points:**
- `ParalifeApplication.main()` — Spring Boot startup
- `TickEngine.@PostConstruct` — Starts virtual thread tick loop (if `paralife.tick.auto-start: true`)
- `WorldWebSocketHandler` — Client connections at `/ws/world`

**Error handling:** Graceful degradation. WebSocket errors send `Error` message to client. Tick loop catches exceptions and continues. No exception bubbling.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start -->
## Project Skills

No project-specific skills configured. Use GSD workflow commands for project management.
<!-- GSD:skills-end -->

<!-- GSD:gsd-workflow-start -->
## GSD Workflow Enforcement

This project uses GSD (Get Shit Done) workflow. Follow these rules:
- Never modify files in `.planning/` without being instructed by a GSD workflow
- Always check `.planning/STATE.md` for current project position before starting work
- Follow the phase-based development process: discuss → plan → execute → verify
- Respect dependency ordering between phases as defined in `.planning/ROADMAP.md`
<!-- GSD:gsd-workflow-end -->

<!-- GSD:developer-profile-start -->
## Developer Profile

No developer profile generated yet. Run `/gsd-profile-user` to create one.
<!-- GSD:developer-profile-end -->

## Planning Artifacts Guide

This project has artifacts from two different project management tools due to a mid-project migration.

### GSD2 artifacts (`.gsd/`)

Used for M001 (Foundation) and initial M002 tracking.

- **`gsd.db`** — SQLite database. Authoritative source for milestones, slices, tasks, decisions, quality gates, verification evidence, and assessments. Query with `sqlite3 .gsd/gsd.db`.
- **`DECISIONS.md`** — Append-only decision register (D001–D007). Markdown projection of the `decisions` table.
- **`milestones/M00x/`** — Hierarchy: `M00x-ROADMAP.md`, `M00x-SUMMARY.md`, `M00x-VALIDATION.md`, then `slices/S0x/S0x-PLAN.md` and `tasks/T0x-PLAN.md` / `T0x-SUMMARY.md`.
- **`state-manifest.json`** — JSON snapshot for headless queries.
- **`event-log.jsonl`** — JSONL event stream.

### GSD1 artifacts (`.planning/`)

Used for M002 (Living Simulation) after migration on 2026-04-11.

- **`PROJECT.md`** — Project vision, core value, requirements.
- **`ROADMAP.md`** — Phase checklist (all 10 phases, both milestones).
- **`STATE.md`** — Session continuity state (current position, last activity, blockers).
- **`config.json`** — Workflow preferences (`{"version": 1}`).
- **`codebase/`** — `ARCHITECTURE.md`, `STRUCTURE.md`, `STACK.md`, `INTEGRATIONS.md` — detailed codebase analysis.
- **`phases/01–10/`** — Per-phase directories. Phases 01–05 (GSD2-era) have only `CONTEXT.md`. Phases 06–10 (GSD1-era) have `PLAN.md`, `SUMMARY.md`, task breakdowns.
- **`v1.0-MILESTONE-AUDIT.md`** — Post-completion audit with requirement scores, tech debt, resolved issues.

### Migration notes

- M001 phases (01–05) were fully managed by GSD2 — their artifacts are in `.gsd/milestones/M001/`.
- M002 phases (06–10) have artifacts in both systems: GSD2 DB has slice/task records, `.planning/phases/` has GSD1-format plans and summaries.
- No `REQUIREMENTS.md` exists — GSD2 used `success_criteria` JSON in the `milestones` DB table instead.

### Known tech debt (from milestone audit)

| Phase | Item | Severity |
|-------|------|----------|
| 06 | `Cell.nutrientLevel` field is inert — defined and serialised but never written | Low |
| 08 | `PerceptionBroadcaster` sends UNKNOWN type for dead entities (workaround) | Low |
| 09 | `HeuristicBrain.predatorType` ternary has dead branch (both branches return same value) | Low |
| 09 | `BotClient` uses raw `JsonNode`/`LinkedHashMap` instead of `Messages` sealed types | Low |
| 10 | `LoadTest` omits explicit `nutrient-consume-energy` property (falls back to default) | Low |
