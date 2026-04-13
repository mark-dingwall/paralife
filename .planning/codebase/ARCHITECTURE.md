# Architecture

**Analysis Date:** 2026-04-12

## Pattern Overview

**Overall:** Event-driven, single-threaded tick simulation with concurrent WebSocket I/O

**Key Characteristics:**
- **Tick-based determinism:** All world state changes synchronized to discrete ticks published via Spring events
- **Virtual thread concurrency:** Bot I/O (WebSocket) uses Java 21 virtual threads; simulation core remains single-threaded
- **Read-write locking:** Grid access protected by `ReentrantReadWriteLock` for thread-safe snapshots during broadcast
- **Sealed entity hierarchy:** Type-safe representation of world occupants (Particle, Rock, Nutrient)
- **Spring event-driven processing:** `TickEvent` triggers 4-phase pipeline (Simulation → ActionResolver → PerceptionBroadcaster → TickBroadcaster)

## Layers

**World Layer:**
- Purpose: 2D toroidal grid state and spatial operations
- Location: `src/main/java/com/paralife/world/`
- Contains: 
  - `WorldGrid.java` - 256×256 grid of `Cell` records, thread-safe via read-write lock
  - `Cell.java` - Immutable cell with occupant, flags, nutrient level
  - `Entity.java` - Sealed interface: Particle (active agent), Rock (terrain), Nutrient (consumable)
  - `Position.java` - Toroidal coordinate with wrapping and neighbor calculation
  - `GridConfig.java` - Configuration (width, height) bound from `application.yml`
- Depends on: None (no external dependencies)
- Used by: SimulationEngine, ActionResolver, PerceptionBroadcaster, TickBroadcaster

**Simulation Layer:**
- Purpose: Physics engine — 4-phase per-tick world evolution
- Location: `src/main/java/com/paralife/engine/`
- Contains:
  - `SimulationEngine.java` (@Order(10)) - Combat resolution, energy decay, death removal, nutrient spawning
  - `ActionResolver.java` (@Order(20)) - Bot action queue draining and resolution (move/consume/reproduce/rest)
  - `PerceptionBroadcaster.java` (@Order(50)) - Per-bot local perception (5×5 neighbourhood)
  - `TickEngine.java` - Heartbeat loop on virtual thread, publishes `TickEvent` at configurable interval
  - `BotRegistry.java` - Session ↔ Entity ↔ Position mapping
- Depends on: World, WebSocket (SessionRegistry)
- Used by: WebSocket handlers, tests

**WebSocket Layer:**
- Purpose: Async bot communication, session management, message routing
- Location: `src/main/java/com/paralife/websocket/`
- Contains:
  - `WorldWebSocketHandler.java` - Inbound connection/message handling (Register, Action, Heartbeat)
  - `TickBroadcaster.java` (@Order(100)) - Outbound tick broadcast to all clients
  - `SessionRegistry.java` - Thread-safe WebSocket session map
  - `WebSocketConfig.java` - Spring configuration for `/ws/world` endpoint
  - `Messages.java` - Sealed interface for all message types (Welcome, Tick, Perception, Action, Error, etc.)
- Depends on: Simulation, World, Jackson (JSON)
- Used by: Bot clients (external), Simulation (result callbacks)

**Bot Client Layer:**
- Purpose: Example client implementation connecting to simulation
- Location: `src/main/java/com/paralife/bot/`
- Contains:
  - `BotClient.java` - Standalone client with heuristic brain, handles Register/Perception/ActionResult
  - `HeuristicBrain.java` - Decision logic consuming perception to produce actions
  - `BotLauncher.java` - Test utility for spawning multiple bot clients
- Depends on: WebSocket (Messages), Spring WebSocket client
- Used by: Integration tests (not deployed with server)

## Data Flow

**Per-Tick Cycle (500ms default):**

1. **TickEngine.tickLoop()** (virtual thread)
   - Increments tick counter
   - Publishes `TickEvent(tickNumber)` via Spring `ApplicationEventPublisher`

2. **SimulationEngine.onTick()** @Order(10)
   - Reads current grid state via `WorldGrid.getCell()` (read lock)
   - Phase 1: Combat — particle RPS interactions via snapshot reads + deferred delta writes
   - Phase 2: Energy decay — all particles lose energy
   - Phase 3: Death — zero-energy particles removed
   - Phase 4: Nutrient spawning — probabilistic nutrient creation in empty cells
   - All writes via `WorldGrid.setEntity()` (write lock)

3. **ActionResolver.onTick()** @Order(20)
   - Drains pending actions (queued since last tick via `queueAction()`)
   - For each action: validates bot state, resolves (move/consume/reproduce/rest)
   - Conflict resolution: shuffled execution, first-win on target cell claims
   - Sends `ActionResult` back to bot via WebSocket
   - Updates `BotRegistry` with new positions for movers

4. **PerceptionBroadcaster.onTick()** @Order(50)
   - For each registered bot: reads local 5×5 neighbourhood grid
   - Builds `Perception` message (self state + cell views)
   - Sends to bot via WebSocket (cached session from `SessionRegistry`)

5. **TickBroadcaster.onTick()** @Order(100)
   - Takes grid snapshot via `WorldGrid.snapshot()` (read lock)
   - Broadcasts `Tick` message to all connected clients

**Bot Action Submission (Asynchronous):**

1. Bot sends `Action` message over WebSocket
2. `WorldWebSocketHandler.handleAction()` queues in `ActionResolver.pendingActions`
3. Action waits until next `ActionResolver.onTick()` cycle
4. Result sent back immediately after resolution

**State Management:**
- **Immutable entities:** Particle, Rock, Nutrient are records; mutations create new instances
- **Cell immutability:** Cell is a record; mutations via `withOccupant()`, `cleared()`, etc.
- **Grid write lock:** Only write lock during entity placement/removal; read lock during snapshots
- **Session state:** Stored in `SessionRegistry` (session ID → WebSocketSession) and `BotRegistry` (session ↔ entity mapping)

## Key Abstractions

**Particle (Entity):**
- Purpose: Active agent controlled by a bot
- Examples: `src/main/java/com/paralife/world/Entity.java` (record)
- Pattern: Record with energy state; `withEnergy()` produces new instance for immutability
- RPS type system: CATALYST beats SPORE, SPORE beats MEMBRANE, MEMBRANE beats CATALYST
- Lifecycle: spawned at registration, gains/loses energy via actions and combat, dies at energy = 0

**Cell (World state):**
- Purpose: Single grid position container
- Examples: `src/main/java/com/paralife/world/Cell.java` (record)
- Pattern: Immutable record; separates occupant (entity) from environment (flags, nutrient level)
- Used for: Fast grid access without entity type checking

**Position (Spatial):**
- Purpose: Toroidal coordinate with wrapping
- Examples: `src/main/java/com/paralife/world/Position.java` (record)
- Pattern: Static `wrap()` for automatic coordinate wrapping; `neighbors()` for Moore neighborhood
- Used by: Bot registry, action resolver, perception broadcaster

**BotRegistry.BotState:**
- Purpose: Immutable session ↔ entity ↔ position mapping
- Examples: `src/main/java/com/paralife/engine/BotRegistry.java` (record)
- Pattern: Single record per registered bot, updated atomically via `updatePosition()`

**Messages (WebSocket Contract):**
- Purpose: Sealed interface for type-safe message handling
- Examples: `src/main/java/com/paralife/websocket/Messages.java`
- Pattern: Sealed interface with record implementations, JSON type discrimination via "type" field
- Used by: Jackson for polymorphic deserialization

## Entry Points

**ParalifeApplication.main():**
- Location: `src/main/java/com/paralife/ParalifeApplication.java`
- Triggers: Spring Boot application startup
- Responsibilities: Initialize Spring context, enable configuration properties scanning

**TickEngine startup:**
- Location: `src/main/java/com/paralife/engine/TickEngine.java` @PostConstruct
- Triggers: If `paralife.tick.auto-start: true` (default)
- Responsibilities: Start virtual thread tick loop on boot

**WebSocket handler:**
- Location: `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`
- Triggers: Client connects to `/ws/world`
- Responsibilities: Register session, send Welcome, handle Register/Action/Heartbeat messages

## Error Handling

**Strategy:** Graceful degradation with logging

**Patterns:**
- **WebSocket errors:** Caught in `WorldWebSocketHandler` message handlers; send `Error` message back to client
- **Entity death:** If entity dies during tick, action resolver detects (entity not on grid) and sends failure result
- **Action conflicts:** Multiple entities targeting same cell — shuffled execution, losers get "Cell claimed" error
- **Tick loop errors:** Caught in `TickEngine.tickLoop()` catch block, logged, loop continues
- **JSON deserialization:** Invalid messages logged and `Error` sent back

No exception bubbling; all errors reported back to client via WebSocket message or logged.

## Cross-Cutting Concerns

**Logging:**
- Approach: SLF4J with Logback (built into Spring Boot)
- Configured via `application.yml` (not currently overridden, defaults to INFO level)
- Key components log tick counts, action counts, session lifecycle, errors

**Validation:**
- Approach: Constructor validation in records (e.g., energy > 0, grid dimensions > 0)
- Action validation: Direction parsing, energy checks (reproduce cost), cell availability checks
- Configuration validation: `SimulationConfig` and `TickConfig` constructors validate bounds

**Authentication:**
- Approach: None — WebSocket session ID used as weak identifier
- Per-session entity ownership tracked in `BotRegistry`
- No cryptographic authentication or authorization

**Grid Access Synchronization:**
- Approach: `ReentrantReadWriteLock` on `WorldGrid`
- Read lock: `getCell()`, snapshot reads during broadcast
- Write lock: `setEntity()`, `clearEntity()`, `setCell()`, `clear()`
- Per-cell locking is a future optimization; current single-lock covers entire grid

---

*Architecture analysis: 2026-04-12*
