# M001: Foundation — Tick Engine & Empty World

**Vision:** Spring Boot server boots with Java 21 virtual threads, manages a 2D toroidal grid, runs a deterministic tick loop, accepts WebSocket connections from bot clients, and broadcasts tick events. This milestone proves the core architecture can handle concurrent connections and synchronized state updates.

**Success Criteria:**
- Server starts and runs a tick loop at configurable rate (default 500ms)
- Bots connect via WebSocket `/ws/world`, receive tick events with tick number and world state
- Bots submit one action per tick (initially just "register" and "heartbeat")
- 100+ concurrent WebSocket connections sustained without errors
- Toroidal grid wraps correctly (edge neighbors computed accurately)
- Virtual threads enabled and verified via actuator/metrics
- Graceful shutdown drains connections before exit

---

## Slices

- [x] **S01: Spring Boot Project Scaffold** `risk:low` `depends:[]`
  > After this: `./gradlew bootRun` starts the server, health endpoint responds, virtual threads enabled.

- [x] **S02: World Grid Model** `risk:low` `depends:[S01]`
  > After this: Grid created with configurable dimensions, toroidal neighbor queries work, unit tests pass.

- [x] **S03: Tick Engine** `risk:medium` `depends:[S01]`
  > After this: Server runs a tick loop, logs tick numbers, tick rate is configurable, clean shutdown stops the loop.

- [x] **S04: WebSocket Connection Manager** `risk:high` `depends:[S01,S02,S03]`
  > After this: Bots connect via WebSocket, receive tick broadcasts with world snapshot, server tracks active sessions.

- [x] **S05: Integration Test — 100 Bots** `risk:medium` `depends:[S04]`
  > After this: Automated test spins up 100 concurrent WebSocket clients, all receive sequential tick events without gaps.

---

## Boundary Map

### S01 (leaf — no upstream)
Produces:
  - Spring Boot application entry point with virtual threads enabled
  - `application.yml` config with `spring.threads.virtual.enabled=true`
  - Gradle build with Spring Web, WebSocket, Actuator dependencies
  - Health endpoint at `/actuator/health`

### S01 → S02
S02 Produces:
  - `WorldGrid` — toroidal 2D grid with `getCell(x,y)`, `setCell(x,y,entity)`, `getNeighbors(x,y)`
  - `GridConfig` — configurable width/height from application.yml
  - `Position` — value record for (x,y) coordinates with toroidal math

S02 Consumes from S01:
  - Spring context for `@Component`/`@ConfigurationProperties` wiring

### S01 → S03
S03 Produces:
  - `TickEngine` — scheduled loop that increments tick counter, fires tick events
  - `TickEvent` — record carrying tick number, timestamp
  - `TickConfig` — configurable tick rate from application.yml
  - Spring `ApplicationEvent` publishing for tick events

S03 Consumes from S01:
  - Spring scheduling infrastructure (`@EnableScheduling`, virtual thread executor)

### S02 + S03 → S04
S04 Produces:
  - `WorldWebSocketHandler` — handles connect/disconnect/message for bot sessions
  - `WebSocketConfig` — registers handler at `/ws/world`
  - `SessionRegistry` — tracks active bot sessions, thread-safe
  - `TickBroadcaster` — listens for `TickEvent`, sends world snapshot to all sessions
  - JSON message protocol: `TickMessage`, `ActionMessage`, `WelcomeMessage`

S04 Consumes from S02:
  - `WorldGrid.snapshot()` for world state in tick broadcasts

S04 Consumes from S03:
  - `TickEvent` via Spring event listener to trigger broadcasts

### S04 → S05
S05 Produces:
  - `BotIntegrationTest` — JUnit 5 test that connects 100 WebSocket clients
  - `TestBotClient` — reusable WebSocket test client
  - Verification: all 100 bots receive tick events, no gaps, no duplicates

S05 Consumes from S04:
  - WebSocket endpoint `/ws/world`
  - JSON message protocol for tick events

---

## Key Risks

| Risk | Why It Matters | Mitigation |
|------|---------------|------------|
| WebSocket at scale with virtual threads is newer territory | Pinned threads could kill throughput | Profile early in S04, use JFR to detect pinning |
| Tick broadcast to 100+ sessions must complete within tick interval | Slow broadcasts cause tick drift | Async broadcast with timeout, measure in S05 |
| Spring WebSocket vs raw Jakarta WebSocket choice | Wrong abstraction hurts later milestones | Start with Spring's `WebSocketHandler`, escape hatch to raw if needed |
