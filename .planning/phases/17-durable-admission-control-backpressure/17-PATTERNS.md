# Phase 17: Durable Admission Control & Backpressure - Pattern Map

**Mapped:** 2026-04-27
**Files analyzed:** 20 (12 new, 8 modified)
**Analogs found:** 18 / 20

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `com/paralife/admission/AdmissionConfig.java` | config | request-response | `PopulationCapConfig.java` + `RespawnConfig.java` | exact (same record + `@ConstructorBinding` pattern; nested sub-records follow `MetabolicProfile` precedent) |
| `com/paralife/admission/AdmissionGate.java` | service | request-response | `WorldWebSocketHandler.handleRegister` lines 220–297 | role-match (inline guard logic extracted to bean) |
| `com/paralife/admission/AdmissionMetrics.java` | metrics | request-response | `EmergenceMetrics.java` + `WebSocketMetrics.java` | exact (same `Counter.builder` + `Gauge.builder` + `AtomicInteger` supplier pattern) |
| `com/paralife/admission/TickHealthMonitor.java` | service | event-driven | `TickEngine.java` (`@PostConstruct` + VT loop, `DistributionSummary`) | role-match (`@EventListener @Order` consumer pattern; ring-buffer is new) |
| `com/paralife/admission/ResumeTokenRegistry.java` | service | CRUD | `BotRegistry.java` | role-match (same `ConcurrentHashMap` double-map pattern; tick-driven sweep is new) |
| `com/paralife/admission/OutboundSender.java` | service | event-driven | `TickEngine.java` (VT start/stop) + `BotLauncher.java` (VT spawn) | exact (same `Thread.startVirtualThread` / `Thread.ofVirtual().name().start()` idiom) |
| `com/paralife/codec/Frame.java` (modified) | model | transform | `Frame.java` itself (current `RegisterFrame`, `SyncFrame`) | self-analog |
| `com/paralife/codec/PerceptionCodec.java` (modified) | utility | transform | `PerceptionCodec.parseSync` lines 716–724 (optional-slot precedent) | self-analog |
| `com/paralife/websocket/WorldWebSocketHandler.java` (modified) | controller | request-response | self | self-analog |
| `com/paralife/websocket/TickBroadcaster.java` (modified) | service | event-driven | `TickEngine.tickLoop` (blocking loop with VT) | role-match |
| `com/paralife/bot/BotClient.java` (modified) | client | request-response | `BotClient.onError` lines 276–283 + `handleDeath` lines 290–302 | self-analog |
| `src/main/resources/application.yml` (modified) | config | — | self | self-analog |
| `src/test/java/com/paralife/websocket/AdmissionGateTest.java` (new/rewrite) | test | request-response | `WorldWebSocketHandlerPopulationCapTest.java` | exact |
| `src/test/java/com/paralife/engine/TickHealthGateTest.java` (new) | test | event-driven | existing `@SpringBootTest` + `SimpleMeterRegistry` tests | role-match |
| `src/test/java/com/paralife/websocket/StalledSessionTest.java` (new) | test | event-driven | `WorldWebSocketHandlerPopulationCapTest.java` | role-match |
| `src/test/java/com/paralife/websocket/ResumeTokenTest.java` (new) | test | CRUD | `WorldWebSocketHandlerPopulationCapTest.java` | role-match |
| `src/test/java/com/paralife/metrics/AdmissionMetricsTest.java` (new) | test | request-response | pattern from `EmergenceMetrics` + `SimpleMeterRegistry` | role-match |
| `src/test/java/com/paralife/engine/TickHealthMonitorTest.java` (new) | test | event-driven | pattern from `TickEngine` unit tests | role-match |
| `src/test/java/com/paralife/admission/AdmissionConfigTest.java` (new) | test | config | `WorldWebSocketHandlerPopulationCapTest.java` `@TestPropertySource` pattern | exact |
| `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` (new) | doc | — | no code analog | none |

---

## Pattern Assignments

### `com/paralife/admission/AdmissionConfig.java` (config, request-response)

**Analog:** `src/main/java/com/paralife/websocket/PopulationCapConfig.java` (lines 1–48) and `RespawnConfig.java` (lines 1–50)

**Imports pattern** (`PopulationCapConfig.java` lines 1–5):
```java
package com.paralife.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
```

**Core `@ConfigurationProperties` record pattern** (`PopulationCapConfig.java` lines 25–48):
```java
@ConfigurationProperties(prefix = "paralife.websocket")
public record PopulationCapConfig(int maxActiveEntities) {

    public static final int DEFAULT_MAX_ACTIVE_ENTITIES = 256;

    @ConstructorBinding
    public PopulationCapConfig {
        if (maxActiveEntities <= 0) {
            throw new IllegalArgumentException(
                    "paralife.websocket.max-active-entities must be > 0 (got "
                            + maxActiveEntities + ")");
        }
    }

    /** Convenience for tests that instantiate the handler without Spring. */
    public static PopulationCapConfig defaults() {
        return new PopulationCapConfig(DEFAULT_MAX_ACTIVE_ENTITIES);
    }
}
```

**Adaptation for `AdmissionConfig`:** Change prefix to `"paralife.admission"`. Use nested sub-records (`TickOverloadConfig`, `BackpressureConfig`) following the `MetabolicProfile` / `SimulationConfig` nested-record precedent in `application.yml` lines 70–118. Replace the single `int` field with the Phase 17 cap, maintenance flag, and nested sub-configs. Compact constructor validates all fields. Keep a `defaults()` static factory.

**`application.yml` replacement** (lines 48–52 delete; new block):
```yaml
paralife:
  websocket:
    # max-active-entities: DELETED — replaced by paralife.admission.cap (Phase 17)
    max-respawns-per-session: 5   # keep; RespawnConfig stays at this prefix
  admission:
    cap: 256
    maintenance: false
    tick-overload:
      high-water-pct: 80
      low-water-pct: 60
      window-ticks: 10
    backpressure:
      outbound-queue-size: 16
      grace-window-ticks: 10
```

---

### `com/paralife/admission/AdmissionGate.java` (service, request-response)

**Analog:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — `handleRegister` method (lines 220–297)

**Imports pattern** (inferred from `WorldWebSocketHandler.java` lines 87–103 + beans used):
```java
package com.paralife.admission;

import com.paralife.codec.Frame;
import com.paralife.engine.BotRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.Optional;
```

**Core admission guard sequence** (`WorldWebSocketHandler.java` lines 220–241):
```java
// Current inline sequence to extract into AdmissionGate.evaluate():
Object existingId = attrs.get(ATTR_ENTITY_ID);
if (existingId != null) {
    sendFrame(session, new Frame.ErrorFrame(409, Optional.of("already registered")));
    return;
}

if (worldGrid.livingEntityCount() >= maxActiveEntities) {
    sendFrame(session, new Frame.ErrorFrame(429, Optional.of("population cap exceeded")));
    return;
}

int respawnCount = respawnCountOf(attrs);
Object storedType = attrs.get(ATTR_ENTITY_TYPE);
boolean isRespawn = storedType != null;
if (isRespawn && respawnCount >= maxRespawnsPerSession) {
    sendFrame(session, new Frame.ErrorFrame(429, Optional.of("respawn cap exceeded")));
    return;
}
```

**Retokening map** (from `WorldWebSocketHandler.java` — RESEARCH §Code Examples):
```
Line 181: Optional.of("Malformed frame")         → Optional.of("malformed")
Line 189: Optional.of("Client cannot send S")    → Optional.of("malformed")
Line 191: Optional.of("Client cannot send T")    → Optional.of("malformed")
Line 226: Optional.of("already registered")      → Optional.of("already-registered")
Line 231: Optional.of("population cap exceeded") → Optional.of("world-full")
Line 239: Optional.of("respawn cap exceeded")    → Optional.of("respawn-cap")
Line 281: Optional.of("GRID_FULL")              → Optional.of("grid-full")
Line 303: Optional.of("no active entity")        → Optional.of("no-active-entity")
```

**STALLED FSM attribute** (extends FSM at `WorldWebSocketHandler.java` lines 87–89):
```java
// Existing FSM keys:
private static final String ATTR_ENTITY_ID    = "entityId";
private static final String ATTR_ENTITY_TYPE  = "entityType";
private static final String ATTR_RESPAWN_COUNT = "respawnCount";

// Phase 17 new key — set to tick number when session enters STALLED:
private static final String ATTR_STALL_TICK   = "stallTick";

// State predicates:
boolean isAlive(Map<String,Object> attrs)   { return attrs.containsKey(ATTR_ENTITY_ID); }
boolean isStalled(Map<String,Object> attrs) { return attrs.containsKey(ATTR_STALL_TICK); }
```

**New Phase 17 gate conditions** (add before existing checks; RESEARCH §Architecture Patterns):
```java
// (1) Maintenance mode
if (admissionConfig.maintenance()) {
    return new AdmissionResult(429, "maintenance");
}
// (2) Tick-health gate
if (tickHealthMonitor.isOverloaded()) {
    return new AdmissionResult(429, "tick-overload");
}
// (3) Cap
if (worldGrid.livingEntityCount() >= admissionConfig.cap()) {
    return new AdmissionResult(429, "world-full");
}
// (4) Respawn cap (RespawnConfig stays at paralife.websocket.max-respawns-per-session)
if (isRespawn && respawnCount >= respawnConfig.maxRespawnsPerSession()) {
    return new AdmissionResult(429, "respawn-cap");
}
// (5) Resume-token re-bind
register.resumeToken().ifPresent(token -> resumeTokenRegistry.tryRebind(token, session));
```

**Error handling pattern** (`WorldWebSocketHandler.java` lines 323–333 — `sendFrame`):
```java
void sendFrame(WebSocketSession session, Frame frame) {
    if (session == null || !session.isOpen()) return;
    try {
        String encoded = PerceptionCodec.encode(frame);
        synchronized (session) {
            session.sendMessage(new TextMessage(encoded));
        }
    } catch (Exception e) {
        log.warn("Failed to send frame to {}: {}", session.getId(), e.getMessage());
    }
}
```
Phase 17 note: after `OutboundSender` is wired, error frames from `handleTextMessage` should enqueue to the per-session queue rather than call `sendMessage` directly, per RESEARCH §Anti-Patterns.

---

### `com/paralife/admission/AdmissionMetrics.java` (metrics, request-response)

**Analog:** `src/main/java/com/paralife/metrics/EmergenceMetrics.java` (lines 1–65) for counter pattern; `src/main/java/com/paralife/metrics/WebSocketMetrics.java` (lines 1–60) for gauge + `AtomicInteger` pattern.

**Imports pattern** (`EmergenceMetrics.java` lines 1–6 + `WebSocketMetrics.java` lines 1–9):
```java
package com.paralife.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;
```

**Named counter constants pattern** (`EmergenceMetrics.java` lines 31–34):
```java
// EmergenceMetrics — one constant per counter, registered in ctor:
public static final String M_BONDED_PAIRS  = "paralife.emergence.bonded.pairs.formed";
public static final String M_COMPOSITES    = "paralife.emergence.composites.formed";
// ...
private final Counter bondedPairs;
// ...ctor: this.bondedPairs = Counter.builder(M_BONDED_PAIRS).description("...").register(registry);
public void incBondedPair() { bondedPairs.increment(); }
```

**Tagged counter pattern** (D-17 — `AdmissionMetrics` differs from `EmergenceMetrics` here; RESEARCH §Pattern 3):
```java
// D-17 requires a single counter with a 'reason' tag, NOT per-reason fields.
// Micrometer registry caches by (name, tags) — repeated calls with same tags return same Counter.
public static final String M_REJECTED = "paralife.admission.rejected";

public void incRejected(String reason) {
    Counter.builder(M_REJECTED)
            .tag("reason", reason)
            .description("Admission rejections by reason token")
            .register(registry)
            .increment();
}
```

**Gauge pattern** (`WebSocketMetrics.java` lines 36–48):
```java
private final AtomicInteger activeSessionCount = new AtomicInteger();

this.activeSessions = Gauge.builder(M_ACTIVE_SESSIONS, activeSessionCount, AtomicInteger::get)
        .description("Current active WebSocket sessions")
        .register(registry);

public void setActiveSessions(int count) { activeSessionCount.set(count); }
```

**Phase 17 gauges to register** (D-18):
```java
// AtomicInteger supplier form (WebSocketMetrics pattern):
Gauge.builder("paralife.admission.active.entities", activeEntitiesCount, AtomicInteger::get)
     .description("Live cap-relevant occupants").register(registry);
Gauge.builder("paralife.admission.maintenance", maintenanceFlag, AtomicInteger::get)
     .description("Maintenance mode 0/1").register(registry);
Gauge.builder("paralife.backpressure.stalled.sessions", stalledSessionCount, AtomicInteger::get)
     .description("Sessions in STALLED grace").register(registry);
// paralife.tick.health.work-time-ms uses a volatile long supplier — same Gauge.builder form
// but supplier lambda reads tickEngine.getLastTickWorkMs()
```

**Log-marker pattern** (D-19 — mirrors Phase 16 `EMERGENCE` prefix established in `EmergenceMetrics` Javadoc):
```java
// Emit from admission decision site and TickHealthMonitor:
log.info("ADMISSION rejected tick={} session={} reason={} active={}/{}",
         tickNumber, sessionId, reason, activeCount, cap);
log.info("TICK-HEALTH degraded tick={} work-ms={} high-water-pct={}",
         tickNumber, workMs, highWaterPct);
```

---

### `com/paralife/admission/TickHealthMonitor.java` (service, event-driven)

**Analog:** `src/main/java/com/paralife/engine/TickEngine.java` — VT lifecycle pattern (lines 57–98) + `@EventListener` usage in `TickBroadcaster.java` (lines 159–160).

**Imports pattern** (from `TickEngine.java` lines 1–16 and `TickBroadcaster.java` lines 34–36):
```java
package com.paralife.admission;

import com.paralife.engine.TickEngine;
import com.paralife.engine.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
```

**`@EventListener @Order` pattern** (`TickBroadcaster.java` lines 159–160):
```java
@EventListener
@Order(50) // After SimulationEngine(10) + ActionResolver(20)
public void onTick(TickEvent event) { ... }
```
For `TickHealthMonitor`, use `@Order(Integer.MAX_VALUE)` to fire after `@Order(100)` `TickBroadcaster`, capturing full tick work including broadcast time.

**TickEngine VT start/stop pattern** (`TickEngine.java` lines 68–98):
```java
// start():
running.set(true);
tickThread = Thread.startVirtualThread(this::tickLoop);
tickThread.setName("tick-engine");

// stop():
running.set(false);
if (tickThread != null) {
    tickThread.interrupt();
    try {
        tickThread.join(config.intervalMs() * 2);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

**`tickWork.record(...)` measurement source** (`TickEngine.java` lines 107–113):
```java
long startTime = System.nanoTime();
var event = new TickEvent(tickNumber);
eventPublisher.publishEvent(event);

long elapsedNs = System.nanoTime() - startTime;
tickWork.record(elapsedNs / 1_000_000.0);
long elapsed = elapsedNs / 1_000_000;
```

**Phase 17 adaptation:** Add `volatile long lastTickWorkMs` field to `TickEngine` alongside `tickWork.record(...)` at line 113. `TickHealthMonitor` reads it via a new `getLastTickWorkMs()` getter. Ring buffer pattern (from RESEARCH §Pattern 2) maintains a rolling window; no existing analog — implement from scratch. Hysteresis boolean `overloaded` is read by `AdmissionGate.isOverloaded()`.

---

### `com/paralife/admission/ResumeTokenRegistry.java` (service, CRUD)

**Analog:** `src/main/java/com/paralife/engine/BotRegistry.java` (lines 1–161)

**Imports pattern** (`BotRegistry.java` lines 1–15):
```java
package com.paralife.admission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
```

**Double-map `ConcurrentHashMap` pattern** (`BotRegistry.java` lines 45–47):
```java
// BotRegistry uses two maps for bidirectional lookup:
private final ConcurrentHashMap<String, BotState> bySession = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, String> entityToSession = new ConcurrentHashMap<>();
```

**`ResumeTokenRegistry` map shape** (single map; RESEARCH §Pattern 6):
```java
// One map: token → ResumeEntry(entityId, sessionId, expiresAtTick)
public record ResumeEntry(String entityId, String originalSessionId, long expiresAtTick) {}
private final ConcurrentHashMap<String, ResumeEntry> tokenMap = new ConcurrentHashMap<>();
```

**Register / lookup / remove pattern** (`BotRegistry.java` lines 52–79):
```java
// register:
bySession.put(sessionId, state);
entityToSession.put(entityId, sessionId);

// lookup with Optional:
public Optional<BotState> getBySession(String sessionId) {
    return Optional.ofNullable(bySession.get(sessionId));
}

// unregister:
var removed = bySession.remove(sessionId);
if (removed != null) {
    entityToSession.remove(removed.entityId);
}
```

**Tick-driven sweep** (new pattern; closest analog is `BotRegistry.drainDeaths()` lines 102–109):
```java
// drainDeaths() — synchronized list drain pattern:
public List<DeathNotice> drainDeaths() {
    synchronized (deathsThisTick) {
        if (deathsThisTick.isEmpty()) return List.of();
        var copy = new ArrayList<>(deathsThisTick);
        deathsThisTick.clear();
        return copy;
    }
}
// Phase 17 sweep equivalent:
@EventListener
@Order(1)  // Before SimulationEngine @Order(10) — sweep expired tokens first
public void onTick(TickEvent event) {
    long currentTick = event.tickNumber();
    tokenMap.entrySet().removeIf(e -> {
        if (e.getValue().expiresAtTick() < currentTick) {
            cleanupBot(e.getValue().entityId());  // delegate to WorldWebSocketHandler.cleanupBot
            return true;
        }
        return false;
    });
}
```

**Token generation** (from RESEARCH §Pattern 5; analog `SimulationEngine` UUID pattern):
```java
// SimulationEngine uses UUID.randomUUID().toString().substring(0, 8);
// Phase 17 uses 16-hex for stronger entropy and < 32 chars:
private static String generateToken() {
    return String.format("%016x", ThreadLocalRandom.current().nextLong());
}
```

**Remap pattern on reconnect** (`BotRegistry.remapEntity` lines 115–123):
```java
public void remapEntity(String sessionId, String newEntityId, Position position) {
    var old = bySession.get(sessionId);
    if (old != null) {
        entityToSession.remove(old.entityId());
    }
    var state = new BotState(sessionId, newEntityId, position);
    bySession.put(sessionId, state);
    entityToSession.put(newEntityId, sessionId);
}
```
Phase 17 `tryRebind(token, newSession)` follows the same pattern: remove old session from `BotRegistry`, put new session, preserve entityId.

---

### `com/paralife/admission/OutboundSender.java` (service, event-driven)

**Analog:** `src/main/java/com/paralife/engine/TickEngine.java` lines 68–98 (VT lifecycle) and `src/main/java/com/paralife/bot/BotLauncher.java` lines 52–63 (VT spawn per item).

**Imports pattern** (from `TickEngine.java` lines 1–16):
```java
package com.paralife.admission;

import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
```

**VT start/stop pattern** (`TickEngine.java` lines 68–98):
```java
// TickEngine.start() — VT named form:
tickThread = Thread.startVirtualThread(this::tickLoop);
tickThread.setName("tick-engine");

// TickEngine.stop() — interrupt + join:
if (tickThread != null) {
    tickThread.interrupt();
    try { tickThread.join(config.intervalMs() * 2); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}
```

**VT spawn pattern** (`BotLauncher.java` lines 52–63):
```java
Thread.startVirtualThread(() -> {
    try {
        bot.connect();
        // ...
    } catch (Exception e) {
        log.warn("Bot failed to connect: {}", e.getMessage());
    } finally {
        allDone.countDown();
    }
});
```

**Phase 17 `OutboundSender` core pattern** (RESEARCH §Pattern 1; adapted from both analogs):
```java
// Per-session maps (ConcurrentHashMap pattern from BotRegistry):
private final ConcurrentHashMap<String, ArrayBlockingQueue<Frame>> queues = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Thread> senderThreads = new ConcurrentHashMap<>();

// Spawn on afterConnectionEstablished (named VT form, preferred over Thread.startVirtualThread):
public void attachSession(WebSocketSession session, int queueCapacity) {
    var queue = new ArrayBlockingQueue<Frame>(queueCapacity);
    queues.put(session.getId(), queue);
    Thread t = Thread.ofVirtual()
            .name("ws-sender-" + session.getId())
            .start(() -> drainLoop(session, queue));
    senderThreads.put(session.getId(), t);
}

// Interrupt on afterConnectionClosed:
public void detachSession(String sessionId) {
    queues.remove(sessionId);
    Thread t = senderThreads.remove(sessionId);
    if (t != null) t.interrupt();
}

// Drain loop (blocking take — VT yields carrier; analogous to TickEngine.tickLoop):
private void drainLoop(WebSocketSession session, ArrayBlockingQueue<Frame> queue) {
    try {
        while (!Thread.currentThread().isInterrupted()) {
            Frame frame = queue.take(); // VT yields carrier thread while empty
            if (session.isOpen()) {
                String encoded = PerceptionCodec.encode(frame);
                session.sendMessage(new TextMessage(encoded)); // single writer — no sync needed
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // clean exit on detach/STALLED
    } catch (IOException e) {
        log.warn("Send failed for {}: {}", session.getId(), e.getMessage());
    }
}

// Enqueue (non-blocking — called by TickBroadcaster, analogous to eventPublisher.publishEvent):
public boolean offer(String sessionId, Frame frame) {
    var queue = queues.get(sessionId);
    return queue != null && queue.offer(frame);
}
```

**Error handling pattern** (`TickEngine.tickLoop` lines 125–131):
```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    break;
} catch (Exception e) {
    log.error("Error in tick {}: {}", tickCounter.get(), e.getMessage(), e);
}
```

---

### `com/paralife/codec/Frame.java` (model, transform) — modified

**Analog:** `src/main/java/com/paralife/codec/Frame.java` itself (lines 1–105)

**Current `RegisterFrame`** (lines 14–20):
```java
/** Client → Server. entityType ∈ {C, M, S}. */
record RegisterFrame(char entityType) implements Frame {
    public RegisterFrame {
        if (entityType != 'C' && entityType != 'M' && entityType != 'S') {
            throw new IllegalArgumentException("entityType must be C/M/S: " + entityType);
        }
    }
}
```

**Phase 17 change** (RESEARCH §Critical Codec Finding, Change 1):
```java
record RegisterFrame(char entityType, Optional<String> resumeToken) implements Frame {
    public RegisterFrame {
        if (entityType != 'C' && entityType != 'M' && entityType != 'S') {
            throw new IllegalArgumentException("entityType must be C/M/S: " + entityType);
        }
        if (resumeToken == null) resumeToken = Optional.empty();
    }
}
```

**Current `SyncFrame`** (lines 23–29):
```java
/** Server → Client. Initial sync (no effects) or resync (with effects). */
record SyncFrame(String entityId, List<ActiveEffect> effects) implements Frame {
    public SyncFrame {
        if (entityId == null || entityId.isEmpty()) {
            throw new IllegalArgumentException("entityId must not be blank");
        }
        effects = (effects == null) ? List.of() : List.copyOf(effects);
    }
}
```

**Phase 17 change** (RESEARCH §Critical Codec Finding, Change 4):
```java
record SyncFrame(String entityId, Optional<String> resumeToken, List<ActiveEffect> effects) implements Frame {
    public SyncFrame {
        if (entityId == null || entityId.isEmpty()) {
            throw new IllegalArgumentException("entityId must not be blank");
        }
        if (resumeToken == null) resumeToken = Optional.empty();
        effects = (effects == null) ? List.of() : List.copyOf(effects);
    }
}
```

**Error handling pattern** — `ErrorFrame` (lines 97–104) is unchanged; `Optional<String> message` slot already supports token strings:
```java
/** Server → Client. 3-digit HTTP-style numeric code. */
record ErrorFrame(int code, Optional<String> message) implements Frame {
    public ErrorFrame {
        if (code < 100 || code > 999) {
            throw new IllegalArgumentException("code must be 3-digit: " + code);
        }
    }
}
```

---

### `com/paralife/codec/PerceptionCodec.java` (utility, transform) — modified

**Analog:** `src/main/java/com/paralife/codec/PerceptionCodec.java` — `parseSync` (lines 716–724) for optional-slot pattern; `encodeSync` (lines 111–117) for encoding pattern.

**Current `parseRegister`** (lines 729–737):
```java
private static Frame.RegisterFrame parseRegister(ParseCursor c) {
    char t = c.next();
    if (!c.atEnd()) {
        throw new CodecException("Register frame has trailing bytes at " + c.index());
    }
    if (t != 'C' && t != 'M' && t != 'S') {
        throw new CodecException("Register entityType must be C/M/S at " + (c.index() - 1) + ": " + t);
    }
    return new Frame.RegisterFrame(t);
}
```

**Phase 17 change** (RESEARCH §Critical Codec Finding, Change 2 — CRITICAL: current code throws on trailing bytes):
```java
private static Frame.RegisterFrame parseRegister(ParseCursor c) {
    char t = c.next();
    if (t != 'C' && t != 'M' && t != 'S') {
        throw new CodecException("Register entityType must be C/M/S at " + (c.index() - 1) + ": " + t);
    }
    Optional<String> resumeToken = Optional.empty();
    if (!c.atEnd() && c.peek() == '|') {
        c.next(); // consume '|'
        resumeToken = Optional.of(c.readUntil('|', false)); // read to end or next '|'
    }
    return new Frame.RegisterFrame(t, resumeToken);
}
```

**Current `encodeSync`** (lines 111–117):
```java
private static void encodeSync(StringBuilder sb, Frame.SyncFrame s) {
    sb.append('S').append('|').append(s.entityId());
    if (!s.effects().isEmpty()) {
        sb.append('|');
        encodeEffectList(sb, s.effects());
    }
}
```

**Phase 17 change** (RESEARCH §Critical Codec Finding, Change 5):
```java
private static void encodeSync(StringBuilder sb, Frame.SyncFrame s) {
    sb.append('S').append('|').append(s.entityId());
    s.resumeToken().ifPresent(token -> sb.append('|').append(token));
    if (!s.effects().isEmpty()) {
        sb.append('|');
        encodeEffectList(sb, s.effects());
    }
}
```

**`parseSync` optional-slot precedent** (lines 716–724 — this pattern is already correct for the effects slot; resume token follows the same idiom):
```java
private static Frame.SyncFrame parseSync(ParseCursor c) {
    String entityId = c.readUntil('|', false);
    if (entityId.isEmpty()) throw new CodecException("Sync entityId missing at " + c.index());
    List<ActiveEffect> effects = List.of();
    if (!c.atEnd() && c.peek() == '|') {
        c.next(); // consume '|'
        effects = parseEffectList(c);
    }
    return new Frame.SyncFrame(entityId, effects);
}
```

Phase 17 `parseSync` must distinguish token from effects. Effects begin with `f` prefix (RESEARCH §Critical Codec Finding, Change 5). Token is an opaque hex string (no `f` prefix). After consuming the `|`, peek at next char: if `f`, parse effects; otherwise read token, then optionally `|f...` effects.

---

### `com/paralife/websocket/WorldWebSocketHandler.java` (controller, request-response) — modified

**Analog:** Self. Key modification sites documented with line references.

**Constructor change** (lines 105–123 — remove `PopulationCapConfig`, add `AdmissionGate`):
```java
// Current 9-arg @Autowired ctor uses: ..., RespawnConfig, PopulationCapConfig
// Phase 17: remove PopulationCapConfig; inject AdmissionGate + OutboundSender
@Autowired
public WorldWebSocketHandler(..., RespawnConfig respawnConfig,
                               AdmissionGate admissionGate,
                               OutboundSender outboundSender) { ... }
```

**`afterConnectionEstablished` modification** (current line 167–171):
```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    sessionRegistry.register(session);
    // Phase 17: spawn per-session VT sender
    outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
    log.info("Client connected: {} (total: {})", session.getId(), sessionRegistry.getSessionCount());
}
```

**`afterConnectionClosed` modification** (current lines 197–202):
```java
@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    outboundSender.detachSession(session.getId());  // Phase 17: interrupt sender VT
    lastRosterHashBySession.remove(session.getId()); // TickBroadcaster map cleanup (RESEARCH §Anti-Patterns)
    cleanupBot(session.getId());
    sessionRegistry.unregister(session.getId());
    log.info("Client disconnected: ...");
}
```

**`markDead` pattern** (lines 346–352 — template for `markStalled`):
```java
public void markDead(WebSocketSession session) {
    if (session == null) return;
    session.getAttributes().remove(ATTR_ENTITY_ID);
}

// Phase 17 new method following same pattern:
public void markStalled(WebSocketSession session, long stallTick) {
    if (session == null) return;
    session.getAttributes().remove(ATTR_ENTITY_ID);   // entity still on grid
    session.getAttributes().put(ATTR_STALL_TICK, stallTick);
    outboundSender.detachSession(session.getId());    // stop outbound immediately
}
```

**`sendFrame` — Phase 17 adaptation** (current lines 323–333 uses `synchronized(session)`):
After `OutboundSender` is wired, error frames from `handleTextMessage` should enqueue via `outboundSender.offer(session.getId(), frame)` instead of calling `sendMessage` directly. The `synchronized(session)` guard in `sendFrame` can be removed from the tick-broadcast path.

---

### `com/paralife/websocket/TickBroadcaster.java` (service, event-driven) — modified

**Analog:** Self. `onTick` method (lines 159–201) — the `synchronized(session)` send block (lines 181–183) is the primary refactor target.

**Current synchronous send** (lines 178–196):
```java
for (BotRegistry.BotState bot : bots) {
    WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
    if (session == null || !session.isOpen()) continue;
    try {
        Frame.TickFrame frame = buildTickFrame(bot, event.tickNumber());
        String encoded = PerceptionCodec.encode(frame);
        synchronized (session) {
            session.sendMessage(new TextMessage(encoded));  // ← replace this
        }
        metrics.recordFrameSize(encoded.getBytes(StandardCharsets.UTF_8).length);
    } catch (IOException e) { ... }
}
```

**Phase 17 replacement** (RESEARCH §Pattern 1):
```java
for (BotRegistry.BotState bot : bots) {
    WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
    if (session == null || !session.isOpen()) continue;
    if (isStalled(session)) continue;   // STALLED: do not enqueue
    try {
        Frame.TickFrame frame = buildTickFrame(bot, event.tickNumber());
        boolean offered = outboundSender.offer(bot.sessionId(), frame);
        if (!offered) {
            // Queue full — increment per-tick-stall counter for this session
            // after window ticks, markStalled(session, event.tickNumber())
        }
        // metrics.recordFrameSize still useful — call after encode if needed
    } catch (RuntimeException e) { ... }
}
```

**Death frame drain** (lines 214–239 `drainAndBroadcastDeaths`) — also refactors to `outboundSender.offer()` for death frame path.

---

### `com/paralife/bot/BotClient.java` (client, request-response) — modified

**Analog:** Self. `onError` method (lines 276–283) and `handleDeath` method (lines 290–302).

**Current `onError`** (lines 276–283):
```java
private void onError(Frame.ErrorFrame e) {
    log.warn("Server error {}: {}", e.code(), e.message().orElse(""));
    if (e.code() == 429) {
        disconnect();
    }
}
```

**Phase 17 change** (D-13: handle `E|408|reconnect-required`, store resume token):
```java
private void onError(Frame.ErrorFrame e) {
    log.warn("Server error {}: {}", e.code(), e.message().orElse(""));
    if (e.code() == 408 && "reconnect-required".equals(e.message().orElse(""))) {
        // STALLED: session will be closed by server. Retain resumeToken for reconnect.
        // resumeToken is the last value stored from onSync().
        handleStalled();
        return;
    }
    if (e.code() == 429) {
        disconnect();
    }
}
```

**Current `onSync`** (lines 236–247 — stores entityId; Phase 17 also stores resumeToken):
```java
private void onSync(Frame.SyncFrame s) {
    entityId = s.entityId();
    alive.set(true);
    state.set(BotState.initial(species));
    if (syncCount.getAndIncrement() == 0) {
        registeredLatch.countDown();
    } else {
        respawnCount.incrementAndGet();
    }
    log.info("Bot registered: entity={} species={}", entityId, species);
}
// Phase 17 addition:
// s.resumeToken().ifPresent(t -> this.resumeToken = t);
```

**`handleDeath` pattern** (lines 290–302 — template for `handleStalled`):
```java
private void handleDeath() {
    alive.set(false);
    entityId = null;
    long jitter = respawnJitterMs > 0 ? rng.nextLong(respawnJitterMs) : 0L;
    long waitMs = respawnCooldownMs + jitter;
    CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS).execute(() -> {
        Session s = this.session;
        if (s != null && s.isOpen()) {
            sendFrame(new Frame.RegisterFrame(species));
        }
    });
}
```

**`handleStalled` adaptation** (Phase 17 — STALLED closes WS; reconnect on new WS with token):
```java
private void handleStalled() {
    alive.set(false);
    // Do NOT nullify resumeToken — needed for reconnect
    // Server will close WS; @OnWebSocketClose triggers reconnect with token:
    // new connect() call → sendFrame(new Frame.RegisterFrame(species, Optional.ofNullable(resumeToken)))
}
```

**`sendFrame` pattern** (lines 208–216 — `synchronized` on Jetty session):
```java
private synchronized void sendFrame(Frame f) {
    Session s = this.session;
    if (s == null || !s.isOpen()) return;
    try {
        s.sendText(PerceptionCodec.encode(f), Callback.NOOP);
    } catch (Exception e) {
        log.warn("Failed to send frame: {}", e.getMessage());
    }
}
```

---

### Test files (new) — `AdmissionGateTest`, `StalledSessionTest`, `ResumeTokenTest`, `AdmissionMetricsTest`, `TickHealthGateTest`, `TickHealthMonitorTest`, `AdmissionConfigTest`

**Analog:** `src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java`

**`@SpringBootTest` + `@TestPropertySource` pattern** (`WorldWebSocketHandlerPopulationCapTest.java` lines 10–31):
```java
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.websocket.max-respawns-per-session=100",
        "paralife.admission.cap=1"      // Phase 17 key (was max-active-entities=1)
})
class AdmissionGateTest { ... }
```

**`LoadTest` migration** (`LoadTest.java` line 22 — `@TestPropertySource`):
```java
// Current: "paralife.websocket.max-active-entities=1000000"
// Phase 17: "paralife.admission.cap=1000000"
```

**`SimpleMeterRegistry` pattern for metrics tests** (from existing Micrometer test usage in codebase):
```java
private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
private final AdmissionMetrics metrics = new AdmissionMetrics(registry);

@Test void rejectedCounterIncrementsPerReason() {
    metrics.incRejected("world-full");
    metrics.incRejected("world-full");
    metrics.incRejected("tick-overload");
    assertThat(registry.counter("paralife.admission.rejected", "reason", "world-full").count())
            .isEqualTo(2.0);
}
```

---

## Shared Patterns

### `@ConfigurationProperties` Record with `@ConstructorBinding` Validation
**Source:** `src/main/java/com/paralife/websocket/PopulationCapConfig.java` lines 25–48
**Apply to:** `AdmissionConfig`
```java
@ConfigurationProperties(prefix = "paralife.admission")
public record AdmissionConfig(...) {
    @ConstructorBinding
    public AdmissionConfig {
        if (cap <= 0) throw new IllegalArgumentException("paralife.admission.cap must be > 0");
        // validate nested sub-records similarly
    }
    public static AdmissionConfig defaults() { return new AdmissionConfig(...); }
}
```

### Virtual Thread Lifecycle (Spawn + Named + Interrupt)
**Source:** `src/main/java/com/paralife/engine/TickEngine.java` lines 68–98
**Apply to:** `OutboundSender`
```java
// Preferred named form over Thread.startVirtualThread(runnable):
Thread t = Thread.ofVirtual().name("ws-sender-" + sessionId).start(runnable);
// On shutdown: t.interrupt(); t.join(timeoutMs);
```

### Micrometer Gauge with `AtomicInteger` Supplier
**Source:** `src/main/java/com/paralife/metrics/WebSocketMetrics.java` lines 36–48
**Apply to:** `AdmissionMetrics` for all four D-18 gauges
```java
private final AtomicInteger count = new AtomicInteger();
Gauge.builder("paralife.admission.active.entities", count, AtomicInteger::get)
     .description("...").register(registry);
public void set(int n) { count.set(n); }
```

### Micrometer Counter (Non-Tagged)
**Source:** `src/main/java/com/paralife/metrics/EmergenceMetrics.java` lines 41–54
**Apply to:** `AdmissionMetrics` if any per-fixed-name counters are needed
```java
Counter.builder(METRIC_NAME).description("...").register(registry);
```

### `@EventListener @Order` on `TickEvent`
**Source:** `src/main/java/com/paralife/websocket/TickBroadcaster.java` lines 159–160
**Apply to:** `TickHealthMonitor` (`@Order(Integer.MAX_VALUE)`), `ResumeTokenRegistry.sweep` (`@Order(1)`)
```java
@EventListener
@Order(50)
public void onTick(TickEvent event) { ... }
```

### FSM Session Attribute Pattern
**Source:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` lines 87–89, 346–352
**Apply to:** STALLED state additions in `WorldWebSocketHandler`
```java
// Existing keys: ATTR_ENTITY_ID, ATTR_ENTITY_TYPE, ATTR_RESPAWN_COUNT
// New key: ATTR_STALL_TICK
// markDead removes ATTR_ENTITY_ID; markStalled removes ATTR_ENTITY_ID + sets ATTR_STALL_TICK
session.getAttributes().remove(ATTR_ENTITY_ID);   // Jetty attrs is ConcurrentHashMap; no null values
```

### `synchronized` BotClient Send
**Source:** `src/main/java/com/paralife/bot/BotClient.java` lines 208–216
**Apply to:** `BotClient.sendFrame` — unchanged; this is the Jetty-client-side guard, orthogonal to server-side VT-per-session.

### Error Frame Emission
**Source:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` lines 323–333
**Apply to:** All admission rejection sites in `AdmissionGate` / `WorldWebSocketHandler`
```java
sendFrame(session, new Frame.ErrorFrame(429, Optional.of("world-full")));
// After OutboundSender wired: route through outboundSender.offer() not sendMessage directly
```

### Log-Marker Prefix Style
**Source:** Phase 16 `EMERGENCE` prefix established in `EmergenceMetrics` Javadoc (analog)
**Apply to:** All D-19 emission sites
```
ADMISSION rejected tick=... session=... reason=... active=.../...
BACKPRESSURE stalled tick=... session=... queue-depth=... limit=...
TICK-HEALTH degraded tick=... work-ms=... high-water-pct=...
```
Pattern: single-line, low-cardinality, `grep -E 'ADMISSION|BACKPRESSURE|TICK-HEALTH'` friendly.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `17-ADMISSION.md` | doc | — | No documentation file analog — author from scratch using 15-SCHEMA.md as structural template |
| Ring buffer in `TickHealthMonitor` | utility | event-driven | No rolling-window hysteresis exists in the codebase; implement `long[] window` + `head`/`sum`/`filled` from scratch per RESEARCH §Pattern 2 |

---

## Metadata

**Analog search scope:** `src/main/java/com/paralife/{websocket,engine,codec,bot,metrics}/`, `src/main/resources/application.yml`, `src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java`
**Files read:** 14 source files + 2 test/config files
**Pattern extraction date:** 2026-04-27
