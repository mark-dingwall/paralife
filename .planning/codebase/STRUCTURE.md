# Codebase Structure

**Analysis Date:** 2026-04-12

## Directory Layout

```
/home/mark/kramtime/paralife/
├── build.gradle.kts                  # Gradle build configuration (Java 21, Spring Boot 3.4.4)
├── settings.gradle.kts               # Gradle project settings
├── gradle/                            # Gradle wrapper
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── src/main/java/com/paralife/
│   ├── ParalifeApplication.java       # Spring Boot entry point
│   ├── world/                         # World layer (grid, entities, positions)
│   │   ├── Cell.java
│   │   ├── Entity.java
│   │   ├── GridConfig.java
│   │   ├── Position.java
│   │   └── WorldGrid.java
│   ├── engine/                        # Simulation layer (tick, simulation, actions)
│   │   ├── ActionResolver.java
│   │   ├── BotRegistry.java
│   │   ├── Direction.java
│   │   ├── PerceptionBroadcaster.java
│   │   ├── SimulationConfig.java
│   │   ├── SimulationEngine.java
│   │   ├── TickConfig.java
│   │   ├── TickEngine.java
│   │   └── TickEvent.java
│   ├── websocket/                     # WebSocket layer (communication, sessions)
│   │   ├── Messages.java
│   │   ├── SessionRegistry.java
│   │   ├── TickBroadcaster.java
│   │   ├── WebSocketConfig.java
│   │   └── WorldWebSocketHandler.java
│   └── bot/                           # Bot client layer (example implementations)
│       ├── BotClient.java
│       ├── BotLauncher.java
│       └── HeuristicBrain.java
├── src/main/resources/
│   └── application.yml                # Spring Boot configuration
├── src/test/java/com/paralife/
│   ├── ParalifeApplicationTest.java
│   ├── world/
│   │   ├── CellTest.java
│   │   ├── EntityTest.java
│   │   ├── PositionTest.java
│   │   └── WorldGridTest.java
│   ├── engine/
│   │   ├── ActionResolverTest.java
│   │   ├── BotRegistryTest.java
│   │   ├── LoadTest.java
│   │   ├── PerceptionActionIntegrationTest.java
│   │   ├── PerceptionBroadcasterTest.java
│   │   ├── PopulationDynamicsTest.java
│   │   ├── SimulationEngineTest.java
│   │   ├── SimulationIntegrationTest.java
│   │   ├── TickConfigTest.java
│   │   ├── TickEngineTest.java
│   │   └── TickEventTest.java
│   ├── websocket/
│   │   ├── HundredBotIntegrationTest.java
│   │   └── WebSocketIntegrationTest.java
│   └── bot/
│       ├── BotClientIntegrationTest.java
│       └── HeuristicBrainTest.java
├── .planning/
│   ├── PROJECT.md                     # Project overview
│   ├── ROADMAP.md                     # Development roadmap
│   ├── STATE.md                       # Current state
│   └── codebase/                      # Codebase analysis (this document here)
│       ├── STACK.md
│       ├── INTEGRATIONS.md
│       ├── ARCHITECTURE.md
│       └── STRUCTURE.md
└── docs/                              # (if present) Project documentation
```

## Directory Purposes

**`src/main/java/com/paralife/world/`:**
- Purpose: World grid state and entity definitions
- Contains: Immutable records for Cell, Entity (Particle/Rock/Nutrient), Position
- Key files:
  - `WorldGrid.java` - Thread-safe grid access with read-write lock
  - `Entity.java` - Sealed entity hierarchy with RPS logic
  - `Position.java` - Toroidal coordinate handling

**`src/main/java/com/paralife/engine/`:**
- Purpose: Simulation engine, tick loop, action resolution, perception
- Contains: Spring @Component beans for core logic, event listeners
- Key files:
  - `TickEngine.java` - Virtual thread heartbeat
  - `SimulationEngine.java` - 4-phase physics (combat, decay, death, nutrients)
  - `ActionResolver.java` - Bot action queue and resolution
  - `PerceptionBroadcaster.java` - Per-bot perception delivery
  - `BotRegistry.java` - Session ↔ entity mapping

**`src/main/java/com/paralife/websocket/`:**
- Purpose: WebSocket communication, session management, message routing
- Contains: Spring @Component handlers, message types, session registry
- Key files:
  - `WorldWebSocketHandler.java` - Client connection and message handler
  - `TickBroadcaster.java` - Tick broadcast to all clients
  - `Messages.java` - Sealed interface for all message types

**`src/main/java/com/paralife/bot/`:**
- Purpose: Example bot client implementation (will become standalone)
- Contains: BotClient with internal WebSocket handler, HeuristicBrain decision logic
- Not deployed with server; used in tests

**`src/main/resources/`:**
- Purpose: Application configuration and resources
- Files:
  - `application.yml` - Spring Boot properties (grid size, tick interval, simulation params)

**`src/test/java/com/paralife/`:**
- Purpose: Unit and integration tests
- Organization: Mirrors `src/main/java/` structure
- Key test suites:
  - `*Test.java` - Unit tests for individual classes
  - `*IntegrationTest.java` - Multi-component integration tests
  - `HundredBotIntegrationTest.java` - Scale test with 100+ concurrent bots

## Key File Locations

**Entry Points:**
- `src/main/java/com/paralife/ParalifeApplication.java` - Spring Boot main class
- `src/main/java/com/paralife/engine/TickEngine.java` - Tick loop startup via @PostConstruct
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` - WebSocket `/ws/world` endpoint

**Configuration:**
- `src/main/resources/application.yml` - All runtime parameters
- `build.gradle.kts` - Java 21 toolchain, dependency versions

**Core Logic:**
- `src/main/java/com/paralife/engine/SimulationEngine.java` - Physics simulation
- `src/main/java/com/paralife/engine/ActionResolver.java` - Action processing
- `src/main/java/com/paralife/world/WorldGrid.java` - Grid state container

**Testing:**
- `src/test/java/com/paralife/engine/SimulationIntegrationTest.java` - Full simulation cycle
- `src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java` - Concurrency test
- `src/test/java/com/paralife/world/WorldGridTest.java` - Grid synchronization

## Naming Conventions

**Files:**
- `*.java` - Source files follow class name (e.g., `BotRegistry.java` for `class BotRegistry`)
- Test files: `*Test.java` or `*IntegrationTest.java` (no `Test` suffix on integration is mixed; prefer `*IntegrationTest.java`)

**Directories:**
- Package structure mirrors: `com.paralife.LAYER` where LAYER ∈ {world, engine, websocket, bot}
- Test directories mirror main structure

**Classes:**
- `PascalCase` for all class names
- Records use same naming (e.g., `record Cell(...)`)
- Interfaces: `PascalCase` (e.g., `sealed interface Messages`)

**Methods & Variables:**
- `camelCase` for all methods and variables
- Constants: `UPPER_SNAKE_CASE` (e.g., `DEFAULT_MAX_ENERGY`, `PERCEPTION_RADIUS`)

**Package Names:**
- `com.paralife.LAYER` - Flat single-level organization
- `com.paralife.bot` - Bot client package (temporary; intended as external module)

## Where to Add New Code

**New Feature (e.g., fire spread mechanic):**
- Primary code: `src/main/java/com/paralife/engine/SimulationEngine.java` (add new phase method)
- Configuration: `src/main/resources/application.yml` + `SimulationConfig.java` (new parameters)
- Tests: `src/test/java/com/paralife/engine/SimulationEngineTest.java` (new test methods)

**New Entity Type (e.g., Predator with AI):**
- Definition: Add record to `src/main/java/com/paralife/world/Entity.java` (sealed hierarchy)
- Interaction: Add pattern matching case in `SimulationEngine` and `ActionResolver`
- Tests: `src/test/java/com/paralife/world/EntityTest.java`

**New Action Type (e.g., sleep/hibernate):**
- Message: Add record to `src/main/java/com/paralife/websocket/Messages.java` (action subtypes)
- Handler: Add case to `ActionResolver.resolveActions()` switch statement
- Tests: `src/test/java/com/paralife/engine/ActionResolverTest.java`

**New Bot Strategy:**
- Location: `src/main/java/com/paralife/bot/` (e.g., `AiritBrain.java` alongside `HeuristicBrain.java`)
- Tests: `src/test/java/com/paralife/bot/AiritBrainTest.java`

**Utilities / Shared Helpers:**
- No dedicated utilities package yet; place in nearest logical package
- Consider creating `src/main/java/com/paralife/util/` if shared code grows

## Special Directories

**`build/`:**
- Purpose: Gradle build outputs (generated, not committed)
- Generated: Yes
- Committed: No
- Contains: compiled classes, JAR, test reports, coverage reports

**`.planning/codebase/`:**
- Purpose: Codebase analysis documents (STACK.md, ARCHITECTURE.md, etc.)
- Generated: Yes (by GSD codebase mapper)
- Committed: Yes

**`gradle/wrapper/`:**
- Purpose: Gradle wrapper JAR and properties for reproducible builds
- Generated: No (checked in)
- Committed: Yes

## Test Organization

**Test file structure:**
- Location: Mirror source structure in `src/test/java/`
- Naming: `ClassName` + `Test.java` for unit tests
- Naming: `ClassName` + `IntegrationTest.java` for multi-component tests

**Test patterns:**
- Junit 5 with `@Test` annotations
- Spring Boot Test with `@SpringBootTest` for integration tests
- No test fixtures directory; inline data or factory methods in test classes

**Running tests:**
```bash
./gradlew test              # All tests
./gradlew test --tests '*IntegrationTest' # Integration only
./gradlew jacocoTestReport  # Coverage report
```

---

*Structure analysis: 2026-04-12*
