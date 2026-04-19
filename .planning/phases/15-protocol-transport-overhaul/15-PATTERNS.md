# Phase 15: Protocol & Transport Overhaul - Pattern Map

**Mapped:** 2026-04-20
**Files analyzed:** 30 new/modified files (codec 7, websocket 4, engine 2, bot 3, world 2, config 1, tests 9, resources 2)
**Analogs found:** 28 / 30 (2 files have no existing analog — Micrometer + Jetty-native client)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `src/main/java/com/paralife/codec/Frame.java` (new) | model (sealed iface) | transform | `src/main/java/com/paralife/websocket/Messages.java` | exact |
| `src/main/java/com/paralife/codec/PerceptionCodec.java` (new) | utility (pure fns) | transform | `src/main/java/com/paralife/engine/EntityIds.java` (utility class idiom) + `PerceptionBroadcaster.cellToView` switch (encode shape) | role-match |
| `src/main/java/com/paralife/codec/Base64Codec.java` (new) | utility (static tables) | transform | `src/main/java/com/paralife/engine/EntityIds.java` (final class + private ctor) | role-match |
| `src/main/java/com/paralife/codec/CodecException.java` (new) | model (exception) | — | no direct analog; standard `RuntimeException` subclass | no-analog |
| `src/main/java/com/paralife/codec/ParseCursor.java` (new, package-private) | utility (parser state) | transform | no direct analog; hand-rolled index cursor per RESEARCH §Codec Architecture | no-analog |
| `src/main/java/com/paralife/websocket/Messages.java` (rewrite / delete) | model (sealed iface) | — | self (subtractive edit) | exact |
| `src/main/java/com/paralife/websocket/WebSocketConfig.java` (modify) | config | event-driven (upgrade hook) | self + research §Jetty 12 sketch | partial |
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (rewrite handlers) | controller | request-response | self (retains FSM shape; swaps ObjectMapper for PerceptionCodec) | exact |
| `src/main/java/com/paralife/websocket/TickBroadcaster.java` (new — renamed from `engine/PerceptionBroadcaster.java`) | service (broadcaster) | pub-sub / event-driven | `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` | exact |
| `src/main/java/com/paralife/websocket/TickBroadcaster.java` (old heartbeat — DELETE) | service | pub-sub | self (deletion) | — |
| `src/main/java/com/paralife/websocket/SessionRegistry.java` (modify — add Gauge) | service (registry) | request-response | self + `BuffRegistry` | exact |
| `src/main/java/com/paralife/metrics/WebSocketMetrics.java` (new) | service (metrics) | event-driven | no codebase analog; first Micrometer touch — follow RESEARCH §Micrometer Metrics | no-analog |
| `src/main/java/com/paralife/world/RockGenerator.java` (new) | service (initializer) | batch | `src/main/java/com/paralife/engine/FertilityInitializer.java` | exact |
| `src/main/java/com/paralife/world/RockConfig.java` (new) | config (record) | — | `src/main/java/com/paralife/engine/FertilityConfig.java` | exact |
| `src/main/java/com/paralife/bot/BotClient.java` (rewrite) | controller (client) | request-response | self (retains shape; swaps Spring client → Jetty native; swaps Jackson → codec) | exact |
| `src/main/java/com/paralife/bot/HeuristicBrain.java` (refactor sig) | service (pure fn) | transform | self | exact |
| `src/main/java/com/paralife/bot/BotLauncher.java` (likely no change) | service | batch | self | exact |
| `src/main/java/com/paralife/engine/ActionResolver.java` (modify — swap Action types + IRV) | service | event-driven | self (surgical edit to `queueAction` + `resolveLocomotorVote`) | exact |
| `src/main/resources/rocks/perlin-0{1..5}.png` (new binary resources) | resource | — | no analog (first binary resources) | no-analog |
| `src/main/resources/application.yml` (modify — add `paralife.world.rock-*`) | config | — | self | exact |
| `build.gradle.kts` (modify — starter-jetty swap) | config | — | self | exact |
| `src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java` (new) | test (parameterised) | — | `src/test/java/com/paralife/world/PositionTest.java` (`@ParameterizedTest` + `@CsvSource` usage) | role-match |
| `src/test/java/com/paralife/codec/PerceptionCodecErrorTest.java` (new) | test (unit) | — | `src/test/java/com/paralife/engine/FertilityInitializerTest.java` (plain JUnit 5 style) | role-match |
| `src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java` (new) | test (integration) | — | `src/test/java/com/paralife/websocket/WebSocketIntegrationTest.java` (`@SpringBootTest(RANDOM_PORT)` + `@LocalServerPort`) | role-match |
| `src/test/java/com/paralife/websocket/ServerRefusesUpgradeWithoutDeflateTest.java` (new) | test (integration) | — | `WebSocketIntegrationTest.java` | role-match |
| `src/test/java/com/paralife/bot/BotClientClosesOnMissingServerDeflateTest.java` (new) | test (integration) | — | `src/test/java/com/paralife/bot/BotClientIntegrationTest.java` | role-match |
| `src/test/java/com/paralife/engine/ZeroTrustFilteringTest.java` (new) | test (unit) | — | `src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java` | role-match |
| `src/test/java/com/paralife/world/RockGeneratorTest.java` (new) | test (unit) | — | `src/test/java/com/paralife/engine/FertilityInitializerTest.java` | exact |
| `src/test/java/com/paralife/bot/HeuristicBrainDeterminismTest.java` (new) | test (unit) | — | `src/test/java/com/paralife/bot/HeuristicBrainTest.java` | role-match |
| `src/test/java/com/paralife/bot/RespawnFlowIntegrationTest.java` (new) | test (integration) | — | `src/test/java/com/paralife/bot/BotClientIntegrationTest.java` | role-match |
| `src/test/java/com/paralife/websocket/MetricsEndpointIntegrationTest.java` (new) | test (integration) | — | `WebSocketIntegrationTest.java` + RESEARCH §Verification approach | partial |

---

## Pattern Assignments

### `src/main/java/com/paralife/codec/Frame.java` (model, sealed iface)

**Analog:** `src/main/java/com/paralife/websocket/Messages.java`

**Sealed interface + record subtypes idiom** (`Messages.java:32`, `Messages.java:39-129`):
```java
public sealed interface Messages {
    record Welcome(String sessionId, int worldWidth, int worldHeight, long currentTick)
            implements Messages {}
    record Registered(String entityId, int x, int y) implements Messages {}
    record Error(String code, String message) implements Messages {}
    // ...
    record EntityState(String entityId, String particleType, int energy, int maxEnergy,
                       int x, int y) {} // not part of sealed sum — just a component
}
```

**What changes:** the Jackson annotations (`@JsonTypeInfo` / `@JsonSubTypes` at `Messages.java:13-30`) are NOT carried forward — the codec is pure-Java, no Jackson. Keep the sealed-iface-with-nested-records shape; drop the annotations.

**Nested sealed subtypes for data variants** (pattern that RESEARCH §Codec Architecture prescribes for `Coord` and `KindData`):
```java
// From RESEARCH lines 344-353:
public sealed interface Coord {
    record Numpad(char digit) implements Coord {}
    record Relative(int dx, int dy) implements Coord {}
    record Absolute(int x, int y) implements Coord {}
}
public sealed interface KindData {
    record Simple(char code) implements KindData {}
    record RockSolo() implements KindData {}
    record RockRun(char direction, int additionalCount) implements KindData {}
}
```
The project idiom (e.g. `Entity` sealed iface at `src/main/java/com/paralife/world/Entity.java:17`) places permits/records inside the interface — same pattern applies here.

**Record validation** (compact canonical constructor, `Entity.java:75-78`):
```java
public Particle {
    if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
    if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
}
```
Apply to `TickFrame`, `ActionFrame` etc. for pre-condition checks (e.g. `tickId >= 0`, `energy >= 0`, `sensorRadius ∈ {0,1,2,3}`).

---

### `src/main/java/com/paralife/codec/PerceptionCodec.java` (utility, pure fns)

**Analog for class shape:** `src/main/java/com/paralife/engine/EntityIds.java` (non-instantiable utility class).

**Utility class pattern** (`EntityIds.java:17-40`):
```java
public final class EntityIds {

    private EntityIds() {
        // utility — not instantiable
    }

    public static String entityIdOf(Entity e) {
        if (e == null) return null;
        return switch (e) {
            case Particle p -> p.id();
            case BondedPair bp -> bp.id();
            case CompositeMember cm -> cm.id();
            case Entity.Rock r -> null;
            case Entity.Nutrient n -> null;
        };
    }
}
```

Apply same `public final class` + `private PerceptionCodec()` + `public static` method shape. No Spring annotations (`@Component` deliberately absent — D-40 says no Spring deps).

**Encode dispatch — switch on sealed iface** (mirrors RESEARCH §Codec Architecture sketch lines 868-892 and existing project idiom in `PerceptionBroadcaster.typeCodeFor` at line 425-433):
```java
// From PerceptionBroadcaster.java:425-433 — pattern: switch-expression over sealed Entity
private static String typeCodeFor(Entity occupant) {
    return switch (occupant) {
        case Particle p -> p.type().name();
        case Entity.Rock r -> "ROCK";
        case Entity.Nutrient n -> "NUTRIENT";
        case Entity.BondedPair bp -> "BONDED_" + bp.primaryType() + "_" + bp.secondaryType();
        case Entity.CompositeMember cm -> "COMPOSITE_" + cm.type() + "_" + cm.role();
    };
}
```
Codec's `encode(Frame)` follows the identical exhaustive-switch-over-sealed idiom.

**Decode dispatch** — switch on leading char (RESEARCH §Code Examples lines 881-892):
```java
public static Frame decode(String s) {
    ParseCursor c = new ParseCursor(s);
    char type = c.next();
    return switch (type) {
        case 'T' -> parseTickFrame(c);
        case 'S' -> parseSyncFrame(c);
        case 'r' -> parseRegister(c);
        case 'a' -> parseAction(c);
        case 'E' -> parseError(c);
        default -> throw new CodecException("Unknown frame type: " + type);
    };
}
```

---

### `src/main/java/com/paralife/codec/Base64Codec.java` (utility, static tables)

**Analog:** `src/main/java/com/paralife/engine/EntityIds.java` (`public final class` + `private` ctor pattern).

**Static init block for lookup tables** — no codebase analog; RESEARCH §Codec Architecture lines 407-425 is the spec:
```java
public final class Base64Codec {
    public static final char[] INT_TO_CHAR = new char[64];
    public static final int[] CHAR_TO_INT = new int[128];
    static {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-";
        Arrays.fill(CHAR_TO_INT, -1);
        for (int i = 0; i < 64; i++) {
            char c = alphabet.charAt(i);
            INT_TO_CHAR[i] = c;
            CHAR_TO_INT[c] = i;
        }
    }
    public static int decodeDigit(char c) {
        int v = c < 128 ? CHAR_TO_INT[c] : -1;
        if (v < 0) throw new CodecException("Invalid base64 char: " + c);
        return v;
    }
    private Base64Codec() {}
}
```

**Alphabet is SCHEMA §1 authoritative** — `0-9A-Za-z_-` (64 chars, 6 bits/char). Do not reorder; round-trip vectors depend on the exact ordering (e.g. vector 3 `R62` where `6` = east numpad + `2` = base64 digit 2).

---

### `src/main/java/com/paralife/websocket/Messages.java` (rewrite — deletion per D-01)

**Analog:** self (subtractive edit).

**What survives:** *nothing* for the wire path. Per RESEARCH §Stateless Bot Refactor "Messages.java reshape" table (lines 546-564): every current subtype deletes. Recommendation (a) from RESEARCH line 564 — delete entirely and let `com.paralife.codec.Frame` carry all wire types. Tests currently referencing `Messages.CellView`, `Messages.Perception`, `Messages.Registered` etc. migrate to `Frame` records one class at a time.

**Risk callout:** Jackson annotations at `Messages.java:3-31` must be removed before the class is emptied — otherwise transient compile errors during wave 0. Delete the annotations in the same commit as the final subtype deletions.

---

### `src/main/java/com/paralife/websocket/WebSocketConfig.java` (modify — Jetty extension hook)

**Analog (current shape):** self (`WebSocketConfig.java` all 26 lines).

**Current registration pattern** (`WebSocketConfig.java:11-26`):
```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorldWebSocketHandler worldWebSocketHandler;

    public WebSocketConfig(WorldWebSocketHandler worldWebSocketHandler) {
        this.worldWebSocketHandler = worldWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(worldWebSocketHandler, "/ws/world")
                .setAllowedOrigins("*");
    }
}
```

**What to add:** a second `@Bean` (`WebServerFactoryCustomizer<JettyServletWebServerFactory>`) that hooks Jetty 12's `ServerWebSocketContainer` for the extension-config lambda. RESEARCH §Code Examples lines 897-925 is the sketch. The existing `WebSocketConfigurer` stays — it handles Spring's handler registration; the customizer handles Jetty-native extension negotiation. Two concerns, two beans.

**No project analog for the customizer** — this is first-touch Jetty API. Planner follows RESEARCH §Jetty 12 server-side section verbatim.

---

### `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (rewrite handlers per verb table)

**Analog:** self.

**Current FSM — register + action handlers** (`WorldWebSocketHandler.java:70-89`):
```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    try {
        Messages msg = objectMapper.readValue(message.getPayload(), Messages.class);

        switch (msg) {
            case Messages.Register register -> handleRegister(session, register);
            case Messages.Heartbeat heartbeat -> handleHeartbeat(session);
            case Messages.Action action -> handleAction(session, action);
            case Messages.CompositeAction compositeAction -> handleCompositeAction(session, compositeAction);
            default -> {
                log.warn("Unexpected message type from {}: {}", session.getId(), msg.getClass().getSimpleName());
                sendMessage(session, new Messages.Error("UNKNOWN_MESSAGE", "Unhandled message type"));
            }
        }
    } catch (Exception e) {
        log.warn("Invalid message from {}: {}", session.getId(), e.getMessage());
        sendMessage(session, new Messages.Error("INVALID_MESSAGE", e.getMessage()));
    }
}
```

**Transformation:** swap `objectMapper.readValue(..., Messages.class)` for `PerceptionCodec.decode(message.getPayload())`; swap pattern-match subtypes for `Frame.RegisterFrame` / `Frame.ActionFrame`; map error response to compact `E|400` / `E|429` via `PerceptionCodec.encode(new Frame.ErrorFrame(400, ...))`.

**Respawn FSM addition** (RESEARCH §Respawn flow lines 540-545): session gains an `Alive` / `Dead` state attribute; `r` frame is accepted in `Dead` state (currently rejected). Per-session respawn cap counter compared against config; `E|429` when exceeded. Place FSM logic in `handleRegister` — that method already has the grid-placement loop (`WorldWebSocketHandler.java:117-156`) which is the natural site.

**Send pattern — synchronised on session** (`WorldWebSocketHandler.java:175-180`):
```java
private void sendMessage(WebSocketSession session, Messages message) throws Exception {
    String json = objectMapper.writeValueAsString(message);
    synchronized (session) {
        session.sendMessage(new TextMessage(json));
    }
}
```
Keep the `synchronized (session)` block — underlying transport is not multi-write safe. Swap `objectMapper.writeValueAsString(msg)` for `PerceptionCodec.encode(frame)`.

**Error-response pattern** (`WorldWebSocketHandler.java:145-148`):
```java
if (!placed) {
    sendMessage(session, new Messages.Error("GRID_FULL", "No empty cell found after "
            + MAX_PLACEMENT_ATTEMPTS + " attempts"));
    return;
}
```
New equivalent: `sendFrame(session, new Frame.ErrorFrame(503, "GRID_FULL"))` or whatever code allocation the schema planner picks. RESEARCH §8.5 says numeric 3-digit HTTP-style codes; message optional.

---

### `src/main/java/com/paralife/websocket/TickBroadcaster.java` (new — renamed from `engine/PerceptionBroadcaster.java`, rewrite projection)

**Analog:** `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` (511 lines — renamed, retained logic, swapped projection).

**Imports + `@Component` + constructor DI** (`PerceptionBroadcaster.java:53-104`):
```java
@Component
public class PerceptionBroadcaster {

    public static final int PERCEPTION_RADIUS = 2;
    static final byte BIT_OVERCROWDED = 0x01;

    private final BotRegistry botRegistry;
    private final SessionRegistry sessionRegistry;
    private final WorldGrid worldGrid;
    private final ObjectMapper objectMapper;          // REMOVE — replaced by codec
    private final CompositeRegistry compositeRegistry;
    private EnvironmentEngine environmentEngine;
    private BuffRegistry buffRegistry;
    private SimulationConfig simulationConfig;

    @Autowired
    public PerceptionBroadcaster(BotRegistry botRegistry, SessionRegistry sessionRegistry,
                                  WorldGrid worldGrid, ObjectMapper objectMapper,
                                  CompositeRegistry compositeRegistry,
                                  EnvironmentEngine environmentEngine,
                                  BuffRegistry buffRegistry,
                                  SimulationConfig simulationConfig) { ... }
}
```
**Migration:** drop `ObjectMapper`; add `WebSocketMetrics` (or equivalent) for the frame-size / bytes-saved counters (D-38). Keep constructor shape — wide dependency list is the project idiom.

**`@EventListener` + `@Order(50)` tick handler** (`PerceptionBroadcaster.java:126-194`):
```java
@EventListener
@Order(50) // After SimulationEngine(10) + ActionResolver(20), before TickBroadcaster(100)
public void onTick(TickEvent event) {
    var bots = botRegistry.getAllBots();
    if (bots.isEmpty()) return;

    Map<String, Messages.CompositePerception> compositePerceptionCache = new HashMap<>();

    int sent = 0;
    int failed = 0;

    for (var bot : bots) {
        WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
        if (session == null || !session.isOpen()) continue;

        try {
            // ... build per-bot perception ...
            String json = objectMapper.writeValueAsString(msg);          // REPLACE
            synchronized (session) {                                     //   with
                session.sendMessage(new TextMessage(json));              //   PerceptionCodec.encode(frame)
            }
            sent++;
        } catch (IOException e) {
            failed++;
            log.warn("Failed to send perception to session {}: {}", bot.sessionId(), e.getMessage());
        }
    }
    // ...
}
```
**Keep:** `@Order(50)` — RESEARCH §Architectural Responsibility Map confirms no pipeline change. `if (bots.isEmpty()) return;` early exit. Per-tick composite memo cache. The per-bot `session.isOpen()` guard. `synchronized (session)` send block. `try/catch IOException` + metric increment.

**Vision-scoped OVERCROWDED mask-and-OR — PRESERVE VERBATIM** (`PerceptionBroadcaster.java:381-418`, especially line 395):
```java
// cycle-6 MEDIUM #9: start with cached env cellStatus, STRIP the global
// OVERCROWDED bit, then OR in the per-bot vision-scoped value.
byte cached = environmentEngine != null ? environmentEngine.getCellStatus(cellPos) : (byte) 0;
byte perBotOvercrowdedBit = computeVisionScopedOvercrowded(
        worldGrid, cellPos, botPos, radius, simulationConfig.overcrowdingThreshold())
        ? BIT_OVERCROWDED : 0x00;
byte cellStatus = (byte) ((cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit);
```
This expression is **load-bearing** per CLAUDE.md §Architecture "Env state projection — three layers" and Phase 14 D-40. The codec's `envState` char writer consumes the same `cellStatus` byte — no change to this block.

**Zero-trust projection — MUST CHANGE** (`PerceptionBroadcaster.java:407-417`, `typeCodeFor` at 425-433):
```java
String occupantType = typeCodeFor(occupant);
String displayId = switch (occupant) {
    case Particle p -> p.id();
    case Entity.Rock r -> r.id();
    // ...
};
return new CellView(occupantType, displayId, cell.nutrientLevel(), flags, cellStatus, entityStatus);
```
**Under Phase 15:** `displayId` is DROPPED entirely from wire (D-28 zero-trust). `typeCodeFor` collapses to SCHEMA §8.1.1 kind-code chars (`C`/`M`/`S`/`D`/`N`/`T`/`0`-`5`/`R`/`F`). `BONDED_<P>_<S>` becomes `D`/`N`/`T` (primary type only — secondary hidden per SCHEMA §9 D-28 reversal). `COMPOSITE_<type>_<role>` collapses to a single role digit.

**Tick frame assembly — NEW CODE** driven by SCHEMA §6.3 header + §8 block grammars. No direct analog; `buildPerception` at line 205-248 is the feed but its output record is replaced by `Frame.TickFrame`. Planner writes new encoder per SCHEMA §8 block-by-block.

**Composite stitching** (`PerceptionBroadcaster.java:254-322`) — carries forward unchanged for LOCOMOTOR. Authority-lite tier (FEEDER/ATT/REP, radius 1) is NEW — per SCHEMA §7 use `radius = 1` in the existing `buildPerception` method instead of `PERCEPTION_RADIUS`. Passive tier (DEFENDER/SENSOR) sends the minimal `T` form per SCHEMA §6.3.2 — no vision block.

---

### `src/main/java/com/paralife/websocket/TickBroadcaster.java` (OLD heartbeat — DELETE)

**Analog:** self (`src/main/java/com/paralife/websocket/TickBroadcaster.java:1-95`).

Per D-02: the old broadcaster (which currently emits global stats `entityCount/bondCount/compositeCount/season` to every session) is **deleted entirely**. Its functionality moves to M005 observer endpoint. The file path is reused by the renamed `PerceptionBroadcaster` — plan sequence matters: delete first, then rename.

**Delete-order note for planner:** delete `websocket/TickBroadcaster.java` + `test/websocket/TickBroadcasterTest.java` in the same commit. Rename `engine/PerceptionBroadcaster.java` → `websocket/TickBroadcaster.java` in a subsequent commit (Git will detect the rename when the file paths are distinct).

---

### `src/main/java/com/paralife/websocket/SessionRegistry.java` (modify — add Gauge injection)

**Analog:** self (`SessionRegistry.java:1-42`).

**Current pattern** (`SessionRegistry.java:14-42`) is already ideal for the D-38 active-sessions gauge. Addition — constructor takes a `MeterRegistry`, registers a `Gauge` wired to `size()`:

```java
// RESEARCH §Gauge: paralife.ws.active-sessions (lines 707-713)
public SessionRegistry(MeterRegistry registry) {
    Gauge.builder("paralife.ws.active-sessions", this, SessionRegistry::getSessionCount)
            .description("Current active WebSocket sessions")
            .register(registry);
}
```

**Naming caveat** (RESEARCH §Pitfall 7 lines 852-857): prefer dot-separated lowercase (`paralife.ws.active.sessions`) over hyphens to avoid Prometheus name coercion. Planner picks final names and locks them in SCHEMA follow-up. D-38 names in CONTEXT use hyphens (`bytes-saved`, `tick-frame-bytes`) — flag for planner re-confirmation.

---

### `src/main/java/com/paralife/metrics/WebSocketMetrics.java` (new)

**Analog:** NONE in codebase (Micrometer is first-touch this phase).

Follow RESEARCH §Micrometer Metrics lines 666-735 as the spec. Component shape matches `SessionRegistry` — `@Component`, constructor-injected `MeterRegistry`, final fields for each meter. Inject into `TickBroadcaster` for the per-frame recording. `DistributionSummary` publishes p50/p95/p99 percentiles per RESEARCH line 725.

**Bytes-saved caveat** (RESEARCH §Counter caveat lines 693-700): Jetty 12 per-frame post-deflate byte count may not be publicly queryable. Planner decides: (a) ship as "estimate" with documentation, or (b) wrap Jetty's `OutgoingFrames` and count directly, or (c) defer to future phase. Document the decision.

---

### `src/main/java/com/paralife/world/RockGenerator.java` (new)

**Analog:** `src/main/java/com/paralife/engine/FertilityInitializer.java` (exact match — same init-time world-seeding role, same `@PostConstruct` trigger, same random-placement loop pattern).

**Component + `@PostConstruct` pattern** (`FertilityInitializer.java:27-63`):
```java
@Component
public class FertilityInitializer {

    private static final Logger log = LoggerFactory.getLogger(FertilityInitializer.class);

    private final WorldGrid worldGrid;
    private final FertilityConfig config;

    public FertilityInitializer(WorldGrid worldGrid, FertilityConfig config) {
        this.worldGrid = worldGrid;
        this.config = config;
    }

    @PostConstruct
    public void initializeFertility() {
        if (config.patchCount() == 0) {
            log.debug("Fertility patchCount=0, skipping initialization");
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int width = worldGrid.getWidth();
        int height = worldGrid.getHeight();

        for (int i = 0; i < config.patchCount(); i++) {
            // ... place patches ...
        }
    }
}
```

**RockGenerator adaptations:**
- Inject `RockConfig` instead of `FertilityConfig`.
- Swap `ThreadLocalRandom` for a `Random` whose seed source is `RockConfig.rockSeed()` (D-35: `seed == 0 → new Random()`, else `new Random(seed)`).
- Load PNG via `ImageIO.read(getClass().getResourceAsStream(resource))` per RESEARCH §PNG format handling.
- Per-pixel placement loop mirrors `generatePatch` at `FertilityInitializer.java:72-87` — toroidal wrap via `Math.floorMod`, guard against overwriting occupants with `worldGrid.trySetEntity`.

**Toroidal placement loop** (`FertilityInitializer.java:72-87`):
```java
void generatePatch(int cx, int cy, int radius, int width, int height) {
    for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
            // ...
            int x = Math.floorMod(cx + dx, width);
            int y = Math.floorMod(cy + dy, height);
            Cell cell = worldGrid.getCell(x, y);
            int merged = Math.max(cell.nutrientLevel(), level);
            worldGrid.setCell(x, y, cell.withNutrientLevel(merged));
        }
    }
}
```
RockGenerator uses the same `Math.floorMod` + `getCell`/`setCell` idiom. Use `trySetEntity` (not `setCell`) so bot placement races are avoided — `WorldGrid.trySetEntity` at `WorldGrid.java:60-72` is the right primitive.

**Ordering** — RESEARCH §Init pipeline integration line 647-655: `@PostConstruct` runs before WebSocket port binds. No `@DependsOn` needed; construction order is sufficient. Verified via `FertilityInitializer` which uses the same pattern successfully.

---

### `src/main/java/com/paralife/world/RockConfig.java` (new)

**Analog:** `src/main/java/com/paralife/engine/FertilityConfig.java` (exact — same `@ConfigurationProperties` record idiom, same prefix `paralife.world.*`).

**Config record + validation + defaults** (`FertilityConfig.java:17-40`):
```java
@ConfigurationProperties(prefix = "paralife.simulation.fertility")
public record FertilityConfig(
        int patchCount,
        int patchMinRadius,
        int patchMaxRadius,
        int maxLevel
) {
    public FertilityConfig {
        if (patchCount < 0)
            throw new IllegalArgumentException("patchCount must be >= 0: " + patchCount);
        // ...
    }

    public static FertilityConfig defaults() {
        return new FertilityConfig(20, 3, 8, 100);
    }
}
```

**RockConfig shape** (follows RESEARCH §Config binding lines 628-640):
- prefix `paralife.world` — collides with existing `GridConfig` at `src/main/java/com/paralife/world/GridConfig.java:9`. **Planner must reconcile** — either nest rock under `paralife.world.rock.*` with its own prefix or extend `GridConfig` to carry rock fields. Recommend separate prefix `paralife.world.rock` to preserve `GridConfig`'s tight focus.
- fields: `long rockSeed`, `int rockDensityThreshold`, `List<String> rockTextures`.
- validation: `rockDensityThreshold ∈ [0, 255]`; `rockTextures` non-empty if enabled.
- `defaults()` static factory for tests.

**Auto-scan** — `ParalifeApplication.java:8` has `@ConfigurationPropertiesScan` — new config record auto-registered, zero `@EnableConfigurationProperties` boilerplate.

---

### `src/main/java/com/paralife/bot/BotClient.java` (rewrite — swap transports + codec)

**Analog:** self (`BotClient.java` all 193 lines).

**Keep verbatim:**
- Constructor arg shape + field naming (`BotClient.java:33-51`).
- `connect()` / `waitForRegistered()` / `disconnect()` / `isConnected()` / `isRegistered()` public API (`BotClient.java:53-99`).
- Metrics counters `actionCount` / `perceptionCount` (`BotClient.java:41-42`).
- CountDownLatches `connectedLatch` / `registeredLatch` (`BotClient.java:43-44`).

**Swap Spring's `StandardWebSocketClient` for Jetty native** (`BotClient.java:57-66`):
```java
// CURRENT — D-33 non-compliant (no extension negotiation)
public void connect() throws Exception {
    var client = new StandardWebSocketClient();
    var handler = new BotWebSocketHandler();
    session = client.execute(handler, new WebSocketHttpHeaders(),
            URI.create(serverUri)).get(10, TimeUnit.SECONDS);
    connectedLatch.countDown();
}
```
Replace with Jetty 12 `org.eclipse.jetty.websocket.client.WebSocketClient` + `ClientUpgradeRequest.addExtensions` per RESEARCH §Jetty 12 client negotiation lines 258-271 and §Code Examples lines 929-947.

**Delete Jackson path** (`BotClient.java:6-7, 36, 116-132, 151-173`):
```java
// DELETE — Phase 09 tech debt
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// ...
private final ObjectMapper objectMapper;
// ...
JsonNode node = objectMapper.readTree(message.getPayload());
String type = node.get("type").asText();
switch (type) { /* ... */ }
```
Replace with `Frame f = PerceptionCodec.decode(payload);` + pattern-match over `Frame` sealed subtypes. Matches the project idiom of switch-over-sealed at `WorldWebSocketHandler.java:75-84`.

**Delete LinkedHashMap path** (`BotClient.java:136-139, 166-172`):
```java
// DELETE — Phase 09 tech debt #4
var registerMap = new java.util.LinkedHashMap<String, Object>();
registerMap.put("type", "register");
registerMap.put("entityType", entityType);
wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerMap)));
```
Replace with `wsSession.sendMessage(new TextMessage(PerceptionCodec.encode(new Frame.RegisterFrame(entityType.charAt(0)))))` — `r|<C/M/S>` per SCHEMA §6.1.

**Post-handshake D-33 enforcement** — new code, RESEARCH §Code Examples lines 940-947:
```java
String serverExt = session.getUpgradeResponse().getHeaders().get("Sec-WebSocket-Extensions");
if (serverExt == null || !serverExt.contains("permessage-deflate")) {
    session.close(1002, "Server did not negotiate permessage-deflate");
    throw new IllegalStateException("Compression not negotiated");
}
```

**Respawn flow** (RESEARCH §Respawn flow lines 531-545): on decoding `T` frame whose `v` block contains `D` (died) event, DO NOT close the session; wait cooldown; send new `r|<entityType>` frame. State machine held in `BotClient` as a `volatile Dead`/`Alive` flag.

---

### `src/main/java/com/paralife/bot/HeuristicBrain.java` (refactor signature)

**Analog:** self (`HeuristicBrain.java` all 261 lines).

**Current signature** (`HeuristicBrain.java:84`):
```java
public Decision decide(Perception perception) { ... }
```

**New signature** per D-43 + RESEARCH §HeuristicBrain refactor lines 522-528:
```java
public Decision decide(Frame.TickFrame frame, char currentType) { ... }
```

**Keep verbatim:**
- Priority ordering comments at `HeuristicBrain.java:18-25` (flee → chase → consume → reproduce → random walk).
- Scan-neighbourhood loop idiom at `HeuristicBrain.java:110-148` — iterate `(dx, dy)` over radius, score cells, collect per-category lists. Adapt cell access from `perception.neighbourhood().get(row).get(col)` to whatever `Frame.TickFrame.cells()` shape exposes (List of CellEntry per SCHEMA §8.1 + RESEARCH §Codec Architecture lines 341-342).
- Observable-only priorities at `HeuristicBrain.java:140-142` (STARVING +2, MUTATING -1, BUFFED -1).
- Flee logic at `HeuristicBrain.java:213-230`.

**Dead-branch fix** (Phase 09 tech debt #3, RESEARCH §HeuristicBrain refactor lines 510-519) at `HeuristicBrain.java:108`:
```java
// BEFORE (both branches return same value — bug)
ParticleType predatorType = preyType.predator() == myType ? myType.predator() : predatorOf(myType);

// AFTER
ParticleType predatorType = myType.predator();
```

**Deterministic-seed injection** (RESEARCH §HeuristicBrain refactor lines 505-508) — accept `Random rng` in constructor, default `ThreadLocalRandom.current()`; tests inject seeded `Random`. Replaces `HeuristicBrain.java:183, 203, 229` `ThreadLocalRandom.current().nextInt(...)` calls.

**Direction mapping** — `HeuristicBrain.java:68-71`:
```java
public static Decision move(Direction dir) { return new Decision("move", dir.name()); }
public static Decision consume() { return new Decision("consume", null); }
```
Under SCHEMA §8.6 action grammar: actions become `M`/`E`/`A`/`R` single-char verbs + numpad-digit args. `Direction.java:9-17` already maps to `(dx, dy)` offsets; add a numpad-digit mapping (e.g. `N→'8'`, `E→'6'`) in `Direction` or in the codec. RESEARCH §ActionResolver wire impact lines 566-574 lists the mapping:
- `move` + compass `N` → `M` + numpad `8`
- `consume` → `E` + numpad for nutrient direction
- `reproduce` + compass `SE` → `R` + numpad `3`
- `rest` → no action frame (server auto-fallback)

---

### `src/main/java/com/paralife/engine/ActionResolver.java` (modify — swap Action types + IRV)

**Analog:** self.

**Current `queueAction` signature** (called from `WorldWebSocketHandler.java:158-162`):
```java
actionResolver.queueAction(session.getId(), action);  // action is Messages.Action
```

**Transformation:** change `queueAction(String, Messages.Action)` → `queueAction(String, Frame.ActionFrame)`. Method body currently reads `action.actionType()` + `action.direction()` — new reads are `action.verb()` + `action.arg()`. Internal per-verb dispatch branches on `verb == 'M' / 'E' / 'A' / 'R' / 'V' / 'L'`.

**IRV vote replacement** (`ActionResolver.java:953-973`) — RESEARCH §10 vector + SCHEMA §8.6 note line 404:
```java
// CURRENT — plurality with random tie-break
Direction resolveLocomotorVote(List<List<String>> rankedVotes) {
    Map<Direction, Integer> counts = new EnumMap<>(Direction.class);
    for (List<String> prefs : rankedVotes) {
        if (prefs != null && !prefs.isEmpty()) {
            Direction d = Direction.fromString(prefs.get(0));
            if (d != null) counts.merge(d, 1, Integer::sum);
        }
    }
    if (counts.isEmpty()) return null;

    int max = Collections.max(counts.values());
    List<Direction> winners = counts.entrySet().stream()
            .filter(e -> e.getValue() == max)
            .map(Map.Entry::getKey)
            .toList();
    return winners.get(ThreadLocalRandom.current().nextInt(winners.size()));
}
```
**Replace** with proper IRV (elimination rounds): while no direction has majority, eliminate lowest-count direction, redistribute votes to next preference. Tiebreak per SCHEMA §8.6: "lowest numpad digit." Keep method package-private + same signature (input is now list of numpad-digit rank strings).

---

### `src/main/resources/application.yml` (modify — add rock config)

**Analog:** self (`application.yml` lines 20-23 for `paralife.world.*`).

**Current pattern** (lines 20-23):
```yaml
paralife:
  world:
    width: 256
    height: 256
```

**Additions** (per RESEARCH §Config binding lines 628-640):
```yaml
paralife:
  world:
    width: 256
    height: 256
    rock:
      seed: 0                    # 0 → unseeded; any non-zero → deterministic
      density-threshold: 128     # 0..255 luminance
      textures:
        - /rocks/perlin-01.png
        - /rocks/perlin-02.png
        - /rocks/perlin-03.png
        - /rocks/perlin-04.png
        - /rocks/perlin-05.png
```
Matches existing nested-property pattern (e.g. `paralife.simulation.types.catalyst.*` at lines 38-51). `@ConfigurationPropertiesScan` at `ParalifeApplication.java:8` auto-binds.

---

### `build.gradle.kts` (modify — starter-jetty swap)

**Analog:** self (all 42 lines).

**Current dependencies** (`build.gradle.kts:21-28`):
```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

**Apply RESEARCH §Standard Stack lines 180-194** block verbatim — exclude starter-tomcat from both starter-web and starter-websocket; add starter-jetty. No change to test deps.

---

## Test Pattern Assignments

### `src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java` (new, parameterised)

**Analog:** `src/test/java/com/paralife/world/PositionTest.java` — only existing `@ParameterizedTest` in the repo.

**Parameterised test pattern** (`PositionTest.java:1-10`):
```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
```

**For 13 vectors use `@MethodSource` over `@CsvSource`** — multi-char commas in vectors (e.g. vector 6 `...s61R,91F,43C1,+3-21R62,+3+33M32|...`) would collide with CSV delimiters. Pattern from RESEARCH §Parameterised round-trip test lines 949-969:
```java
class PerceptionCodecRoundTripTest {
    @ParameterizedTest(name = "Vector {index}: {0}")
    @MethodSource("vectors")
    void roundTripsExactly(String wireFrame) {
        Frame decoded = PerceptionCodec.decode(wireFrame);
        String reEncoded = PerceptionCodec.encode(decoded);
        assertEquals(wireFrame, reEncoded);
    }

    static Stream<String> vectors() {
        return Stream.of(
            "T|001|0A1B|15/80|2",
            "T|001|0A1B|15/80|2|s61F",
            // ... all 13 from SCHEMA §10
            "T|001|0A1B|15/80|2|s43R824,124,-1-124"
        );
    }
}
```

**Vector 9 clarification required** — RESEARCH §Open Questions Q1 (lines 987-991): vector 9 `v+0F-03L5` appears 6-char relative coord; SCHEMA §2 locks relative to 4 chars. Planner must clarify or amend before codec consumer logic is written.

---

### `src/test/java/com/paralife/websocket/WebSocketDeflateHandshakeIntegrationTest.java` (new, integration)

**Analog:** `src/test/java/com/paralife/websocket/WebSocketIntegrationTest.java`.

**Integration test boilerplate** (`WebSocketIntegrationTest.java:24-44`):
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",
        "paralife.tick.auto-start=true",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocketSession clientSession;

    @AfterEach
    void tearDown() throws Exception {
        if (clientSession != null && clientSession.isOpen()) {
            clientSession.close();
        }
    }
    // ...
}
```

**For handshake inspection** (RESEARCH §Handshake Inspection Test lines 275-295) replace `WebSocketSession` with raw `java.net.http.HttpClient` sending the upgrade request manually:
```java
HttpClient http = HttpClient.newHttpClient();
HttpRequest upgrade = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws/world"))
        .header("Connection", "Upgrade")
        .header("Upgrade", "websocket")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
        .header("Sec-WebSocket-Extensions", "permessage-deflate; server_no_context_takeover")
        .GET().build();
HttpResponse<Void> resp = http.send(upgrade, BodyHandlers.discarding());
assertEquals(101, resp.statusCode());
String ext = resp.headers().firstValue("Sec-WebSocket-Extensions").orElseThrow();
assertTrue(ext.contains("permessage-deflate"));
assertTrue(ext.contains("server_no_context_takeover"));
```
Keep the `@LocalServerPort` + `@SpringBootTest(RANDOM_PORT)` shell from the analog.

---

### `src/test/java/com/paralife/world/RockGeneratorTest.java` (new)

**Analog:** `src/test/java/com/paralife/engine/FertilityInitializerTest.java` (exact — same init-time generator test pattern).

**Setup + count helpers** (`FertilityInitializerTest.java:11-35`):
```java
class FertilityInitializerTest {

    private static final int WIDTH = 20;
    private static final int HEIGHT = 20;

    private WorldGrid grid;

    @BeforeEach
    void setUp() {
        grid = new WorldGrid(new GridConfig(WIDTH, HEIGHT));
    }

    private FertilityInitializer init(FertilityConfig cfg) {
        return new FertilityInitializer(grid, cfg);
    }

    private int countNonZeroCells() {
        int count = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                if (grid.getCell(x, y).nutrientLevel() > 0) count++;
            }
        }
        return count;
    }
}
```
Apply same `@BeforeEach setUp` + `WorldGrid(new GridConfig(...))` + `init(cfg)` factory pattern. Count method becomes "count cells whose occupant is Rock." Determinism test: construct two generators with the same seed, assert identical rock positions.

**Config validation tests** (`FertilityInitializerTest.java:40-57`):
```java
@Test
void fertilityConfigRejectsNegativePatchCount() {
    try {
        new FertilityConfig(-1, 3, 8, 100);
        org.junit.jupiter.api.Assertions.fail("expected IAE");
    } catch (IllegalArgumentException expected) {
        assertThat(expected.getMessage()).contains("patchCount");
    }
}
```
Mirror for `RockConfig` — reject negative threshold, empty textures, out-of-range threshold.

---

### `src/test/java/com/paralife/websocket/MetricsEndpointIntegrationTest.java` (new)

**Analog:** partial — `WebSocketIntegrationTest.java` for the Spring harness shell; no project analog for Micrometer assertions.

Follow RESEARCH §Verification approach lines 736-743:
- `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`
- hit `http://localhost:PORT/actuator/metrics/paralife.ws.active-sessions`
- assert JSON response has `measurements` array, value equals connected bot count after a short wait.

---

## Shared Patterns

### Logging
**Source:** `src/main/java/com/paralife/engine/FertilityInitializer.java:6-8, 30`
**Apply to:** every new `@Component` (RockGenerator, WebSocketMetrics).
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(MyClass.class);
```
Log-level convention: `log.info(...)` for lifecycle + cardinal events; `log.debug(...)` guarded with `isDebugEnabled()` when the message does any string work; `log.warn(...)` for recoverable anomalies; `log.error(...)` for unexpected exceptions.

### Spring bean wiring
**Source:** `src/main/java/com/paralife/engine/FertilityInitializer.java:27-38`
**Apply to:** every new `@Component` in this phase.
```java
@Component
public class FertilityInitializer {
    private final WorldGrid worldGrid;
    private final FertilityConfig config;

    public FertilityInitializer(WorldGrid worldGrid, FertilityConfig config) {
        this.worldGrid = worldGrid;
        this.config = config;
    }
}
```
Constructor injection, `final` fields, no field injection, no `@Autowired` on the constructor (single-ctor implicit). `PerceptionBroadcaster.java:89` has `@Autowired` because it has TWO constructors (back-compat 5-arg + production 8-arg); new code with single constructor omits the annotation.

### `@ConfigurationProperties` records
**Source:** `src/main/java/com/paralife/engine/FertilityConfig.java:17-40` + `src/main/java/com/paralife/world/GridConfig.java:9-25`
**Apply to:** `RockConfig`.
```java
@ConfigurationProperties(prefix = "paralife.world.rock")
public record RockConfig(long seed, int densityThreshold, List<String> textures) {
    public RockConfig {
        if (densityThreshold < 0 || densityThreshold > 255)
            throw new IllegalArgumentException("densityThreshold out of range: " + densityThreshold);
        if (textures == null || textures.isEmpty())
            throw new IllegalArgumentException("textures must not be empty");
    }

    public static RockConfig defaults() {
        return new RockConfig(0L, 128, List.of("/rocks/perlin-01.png"));
    }
}
```
Auto-scanned via `@ConfigurationPropertiesScan` on `ParalifeApplication`. Validate in compact constructor. Provide `defaults()` static factory for test convenience.

### Immutable records + pattern matching
**Source:** `src/main/java/com/paralife/world/Entity.java:17` (sealed iface), `PerceptionBroadcaster.java:425-433` (switch-over-sealed)
**Apply to:** `Frame`, `Coord`, `KindData`, `Event`, `ActiveEffect`, `StateChange`, `PoolSnapshot`, `RosterMember` sealed hierarchies in `com.paralife.codec`.
Every new data shape is a record. Variants live inside a sealed interface. Pattern matching via exhaustive `switch` expression.

### `synchronized (session)` for WebSocket sends
**Source:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:175-180`, `PerceptionBroadcaster.java:180-182`
**Apply to:** every WebSocket write site in `TickBroadcaster`, `WorldWebSocketHandler`, `BotClient`.
```java
synchronized (session) {
    session.sendMessage(new TextMessage(encoded));
}
```
WebSocket transports are not safe for concurrent writes; this guard is load-bearing for the 100-bot concurrency target.

### Integration test boilerplate
**Source:** `src/test/java/com/paralife/websocket/WebSocketIntegrationTest.java:24-44`
**Apply to:** every new integration test (`WebSocketDeflateHandshakeIntegrationTest`, `ServerRefusesUpgradeWithoutDeflateTest`, `RespawnFlowIntegrationTest`, `MetricsEndpointIntegrationTest`, `BotClientClosesOnMissingServerDeflateTest`).
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",
        "paralife.tick.auto-start=true",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class MyIntegrationTest {
    @LocalServerPort
    private int port;

    @AfterEach
    void tearDown() { /* close any open sessions */ }
}
```

### Mockito unit test shell
**Source:** `src/test/java/com/paralife/websocket/TickBroadcasterTest.java:36-60`
**Apply to:** new unit tests that exercise `TickBroadcaster` / `BotClient` / `WorldWebSocketHandler` with mocked `WebSocketSession`.
```java
WebSocketSession session = mock(WebSocketSession.class);
when(session.getId()).thenReturn("s1");
when(session.isOpen()).thenReturn(true);
sessionRegistry.register(session);

broadcaster.onTick(new TickEvent(1));

ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
verify(session).sendMessage(captor.capture());
// Assertions on captor.getValue().getPayload()
```

### Entity ID zero-trust drop (D-28)
**Source:** `src/main/java/com/paralife/engine/PerceptionBroadcaster.java:407-417`
**Apply to:** every cell serialization site in `TickBroadcaster` → `PerceptionCodec`.
Current code passes `displayId` into `CellView`. The new codec emits ONLY kind code (`C`/`M`/`S`/`D`/`N`/`T`/`0`-`5`/`R`/`F`) per SCHEMA §8.1.1 — never the entity id. Bonded secondary type hidden — `D`/`N`/`T` marks only primary (SCHEMA §9 D-28 reversal).

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `src/main/java/com/paralife/metrics/WebSocketMetrics.java` | service (metrics) | event-driven | First Micrometer touch in the codebase. Follow RESEARCH §Micrometer Metrics (lines 663-744) as the spec. |
| `src/main/java/com/paralife/codec/ParseCursor.java` | utility (parser state) | transform | Hand-rolled index cursor has no parallel — the closest existing idiom is `Position.wrap(...)` (`src/main/java/com/paralife/world/Position.java`) which is a static helper, not a mutable cursor. Novel construction this phase. |
| `src/main/java/com/paralife/codec/CodecException.java` | model (exception) | — | No custom `RuntimeException` subclasses exist in `src/main/java/com/paralife`. Define as plain `public class CodecException extends RuntimeException`. |
| `src/main/resources/rocks/*.png` | resource | — | First binary resource files. Ship 5 pre-made 64×64 grayscale PNGs per RESEARCH §Minimum viable PNG set. |
| Jetty-native client integration (within `BotClient.java`) | transport | request-response | No existing Jetty-native API usage in the codebase. Follow RESEARCH §Jetty 12 client negotiation lines 253-271 and §Code Examples lines 929-947. |
| Jetty-native server extension hook (within `WebSocketConfig.java`) | transport | event-driven | No existing `WebServerFactoryCustomizer<JettyServletWebServerFactory>` bean in the codebase. Follow RESEARCH §Server-side permessage-deflate lines 208-250 and §Code Examples lines 895-925. |

---

## Metadata

**Analog search scope:**
- `src/main/java/com/paralife/websocket/*.java` (Messages, WebSocketConfig, WorldWebSocketHandler, SessionRegistry, TickBroadcaster)
- `src/main/java/com/paralife/engine/*.java` (PerceptionBroadcaster, FertilityInitializer, FertilityConfig, EnvironmentConfig, BuffRegistry, BotRegistry, TickEngine, EntityIds, Direction, ActionResolver)
- `src/main/java/com/paralife/world/*.java` (Entity, Cell, WorldGrid, Position, GridConfig)
- `src/main/java/com/paralife/bot/*.java` (BotClient, HeuristicBrain, BotLauncher)
- `src/main/java/com/paralife/ParalifeApplication.java`
- `src/test/java/com/paralife/**/*.java` (WebSocketIntegrationTest, TickBroadcasterTest, PerceptionBroadcasterTest, FertilityInitializerTest, HeuristicBrainTest, EnvironmentDeterminismTest, HundredBotIntegrationTest, PositionTest)
- `build.gradle.kts`
- `src/main/resources/application.yml`

**Files scanned (read at least partially):** 26
**Pattern extraction date:** 2026-04-20
**Grep passes:** 3 (@ParameterizedTest, @EnableConfigurationProperties, io.micrometer)
**Key findings:**
- Strong analog coverage for the broadcaster rewrite (`PerceptionBroadcaster` → `TickBroadcaster`), rock generator (`FertilityInitializer`), and every config record.
- WebSocket handshake + Jetty native client/server paths are first-touch; RESEARCH §Jetty 12 sections are the sole reference.
- Micrometer wiring is first-touch; RESEARCH §Micrometer Metrics section is the sole reference.
- The existing `Messages.java` sealed interface + nested records is the perfect template for `Frame.java` — drop Jackson annotations, keep the structural idiom.
- Round-trip parameterised test has only one in-repo precedent (`PositionTest.java`); use `@MethodSource` over `@CsvSource` because test vectors contain commas.
- `PerceptionBroadcaster.cellToView` mask-and-OR at line 395 is load-bearing for SCHEMA §8.1.3 envState bit 0; preserve verbatim through the rename.
