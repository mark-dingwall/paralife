# External Integrations

**Analysis Date:** 2026-04-12

## APIs & External Services

**WebSocket Communication:**
- Server endpoint: `/ws/world` (configured in `com.paralife.websocket.WebSocketConfig`)
  - Handles bot client connections
  - Authentication: None (open connections, session-based tracking)
  - Origin policy: `*` (all origins allowed)

**No external APIs:** Paralife is a standalone simulation server without outbound API calls to external services.

## Data Storage

**Databases:**
- None - Simulation state held entirely in-memory
  - World grid stored as 2D `Cell[][]` array in `WorldGrid`
  - No persistence to disk between runs
  - Session/bot registry held in `ConcurrentHashMap`

**File Storage:**
- Local filesystem only - Not used

**Caching:**
- In-memory event-driven architecture
  - Grid snapshots taken per tick via `WorldGrid.snapshot()` for broadcast
  - No external cache (Redis, Memcached, etc.)

## Authentication & Identity

**Auth Provider:**
- Custom session-based (no external auth)
  - WebSocket sessions identified by Spring session ID
  - Bot registration via `Register` message with `entityType` parameter
  - No JWT, OAuth, or API key authentication

**Session Tracking:**
- `SessionRegistry` (`src/main/java/com/paralife/websocket/SessionRegistry.java`)
  - Maps session ID → `WebSocketSession`
  - Thread-safe via `ConcurrentHashMap`
- `BotRegistry` (`src/main/java/com/paralife/engine/BotRegistry.java`)
  - Bidirectional: session ID ↔ entity ID + position

## Monitoring & Observability

**Error Tracking:**
- None (no external error service)

**Logs:**
- SLF4J with Logback (built-in to Spring Boot)
- Output to console by default
- Log levels configurable via `application.yml` (not currently overridden)
- Key log points:
  - `TickEngine` - Tick loop lifecycle
  - `SimulationEngine` - Physics simulation per phase
  - `ActionResolver` - Action resolution
  - `PerceptionBroadcaster` - Perception delivery
  - `TickBroadcaster` - Tick broadcast status
  - `WorldWebSocketHandler` - Connection/disconnect events

**Metrics:**
- Spring Boot Actuator endpoints: `/actuator/health`, `/actuator/info`, `/actuator/metrics`
  - Health check: `localhost:8080/actuator/health`
  - No custom metrics instrumentation

## CI/CD & Deployment

**Hosting:**
- Not configured - Intended for local/development execution
- Spring Boot embedded Tomcat on port 8080

**CI Pipeline:**
- Gradle build system with `./gradlew test` for testing
- JaCoCo coverage reports generated to `build/reports/jacoco/`
- No GitHub Actions, Jenkins, or external CI configured

## Environment Configuration

**Required env vars:**
- None - All configuration via `application.yml`

**Secrets location:**
- None - No secrets management (no API keys, database credentials, etc.)
- `.env` files: Not used
- Configuration properties are explicit in `application.yml`

## Webhooks & Callbacks

**Incoming:**
- None - Only WebSocket-based client connections at `/ws/world`

**Outgoing:**
- None - Server does not initiate external calls

## Message Protocol

**WebSocket Message Types:**

Server → Client:
- `Welcome` - Initial connection metadata (session ID, world dimensions, current tick)
- `Tick` - Broadcast each tick with tick number and entity count
- `Registered` - Confirmation of successful entity registration
- `Perception` - Per-entity perception (self state + 5×5 neighbourhood view)
- `ActionResult` - Success/failure response to submitted actions
- `Error` - Error messages for invalid requests

Client → Server:
- `Register` - Request entity registration with type (CATALYST, MEMBRANE, SPORE)
- `Action` - Submit action (move/consume/reproduce/rest) with optional direction
- `Heartbeat` - Keep-alive signal

Full message schema: `src/main/java/com/paralife/websocket/Messages.java` (sealed interface with record implementations)

---

*Integration audit: 2026-04-12*
