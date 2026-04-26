# Phase 17: Durable Admission Control & Backpressure - Research

**Researched:** 2026-04-27
**Domain:** Spring Boot 3.4.4 / Java 21 VT / Micrometer / WebSocket FSM / Backpressure patterns
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Admission Policy Shape**
- D-01: Single global cap. New `AdmissionConfig` owns the durable cap. Per-type quotas, hybrid bands, and source-tagging deferred.
- D-02: In-sim reproduction stays exempt from cap. Admission gates external load injection only (`r|` register / respawn). Code stays neutral on entity origin.
- D-03: Phase 17 stays origin-blind. No source/operator/harness tag on `r|`.

**Migration off 999.1 Stopgap**
- D-04: `PopulationCapConfig` and `paralife.websocket.max-active-entities` config key are deleted (not aliased). `WorldWebSocketHandlerPopulationCapTest` rewritten as `AdmissionGateTest`.

**Rejection Vocabulary**
- D-05: Wire reasons become stable machine-readable tokens. Error frame format stays `E|<code>[|<token>]`. Free-text rejection messages replaced everywhere.
- D-06: 429 family for admission-policy rejections. 503 reserved for placement failure. 409 (already-registered) and 400 (codec) keep their codes.
- D-07: Token taxonomy locked (see CONTEXT.md D-07 table).
- D-08: Token taxonomy spec lives in `17-ADMISSION.md`. `15-SCHEMA.md` gets a single-line cross-reference only.

**Backpressure & Overload**
- D-09: Ingress flood: counter only, no kill. `pendingActions` last-write-wins collapse already protects sim. New `paralife.admission.ingress.overwrites` counter added.
- D-10: Outbound async send via VT-per-session. Per-session bounded outbound queue. Rationale documented in Javadoc AND in new `CLAUDE.md` "Outbound concurrency" sub-section.
- D-11: Queue overflow → STALLED state. Server stops emitting tick frames to stalled session immediately. Any inbound frame from stalled session → `E|408|reconnect-required`, then WS closed. One-way transition; recovery via reconnection.
- D-12: Entity grace window on stall. Entity held for configurable grace window (default ~10 ticks) with resume token. Reconnect within window re-binds to same entity.
- D-13: Resume-token wire shape: first successful `r|` returns `S|<entityId>|<resumeToken>`. Server holds `(token → entityId, expiresAt)` map. Reconnect sends `r|<type>|<resumeToken>`. Missing/unrecognised token = fresh registration (back-compat preserved).
- D-14: Tick-health admission gate. Reuses Phase 16 D-11 tick-work-time contract. Denies new `r|` with `E|429|tick-overload` when rolling mean tick-work-time over last `window-ticks` exceeds `high-water-pct` of tick-interval budget; clears below `low-water-pct`.
- D-15: Watermarks under `paralife.admission.tick-overload.*` with defaults. Constants never used — tuning lives in config.
- D-16: Maintenance mode is static config flag `paralife.admission.maintenance: true`. Restart required to flip.

**Operator Visibility**
- D-17: Single tagged counter `paralife.admission.rejected{reason=<token>}`.
- D-18: Gauges: `paralife.admission.active.entities`, `paralife.admission.maintenance`, `paralife.tick.health.work-time-ms`, `paralife.backpressure.stalled.sessions`.
- D-19: Log-marker prefixes `ADMISSION`, `BACKPRESSURE`, `TICK-HEALTH`. Format examples in CONTEXT.md.
- D-20: `/actuator/prometheus` deferred to M5.

### Claude's Discretion
- Default cap value (suggested: keep 256)
- Bounded outbound queue size per session (e.g. 16 frames)
- Grace-window default duration (~10 ticks suggested)
- Tick-overload watermark defaults (`high=80%`, `low=60%`, `window=10` suggested)
- `AdmissionConfig` decomposition (single record vs split records)
- Resume-token format (UUID vs short hex; < 32 chars)
- Whether `paralife.admission.ingress.overwrites` is per-session-tagged or aggregate
- VT lifecycle hookpoints (spawn on `afterConnectionEstablished`, interrupt on `afterConnectionClosed` AND on STALLED)
- `ResumeTokenRegistry` placement (`com.paralife.websocket` vs new `com.paralife.admission`)
- `AdmissionGate` bean placement (`com.paralife.websocket` vs `com.paralife.admission`)
- Whether `RespawnConfig` folds into `AdmissionConfig` or stays sibling

### Deferred Ideas (OUT OF SCOPE)
- Phase 18: Harness identity / source-tag (SCALE-03/04/05)
- Phase 19: Partition-aware world execution (SCALE-06/07)
- Phase 20: Connection multiplexing & runtime tuning (SCALE-08/09)
- Phase 21: Benchmark gate / scale reports (SCALE-10)
- `/actuator/prometheus` wiring (M5)
- Maintenance-mode actuator endpoint (M5)
- Bot-driven offspring agency / NPC-flower fallback (backlog 999.2)
- Per-spawn scoring / leaderboard via observer (M5)
- Hard ingress rate-limit kill
- Drop-frame-silently outbound policy
- Synchronous send with timeout
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SCALE-01 | Server admission control replaces the temporary `max-active-entities` stopgap with a durable world-level policy that explains register and respawn rejection reasons. | D-01..D-08, D-16: `AdmissionConfig` + token taxonomy + `AdmissionGate` + `17-ADMISSION.md` doc. |
| SCALE-02 | Overload and slow-client paths apply explicit backpressure or shedding rules without causing unbounded tick drift or silent session churn. | D-09..D-15: VT-per-session sender + per-session queue + STALLED FSM + tick-health gate + resume-token grace window. |
</phase_requirements>

---

## Summary

Phase 17 replaces the temporary `PopulationCapConfig` / `paralife.websocket.max-active-entities` guardrail with a durable admission system. Three separable concerns must be implemented: (1) a stable token taxonomy replacing all free-text rejection messages, (2) a VT-per-session outbound queue that structurally isolates slow clients from the tick thread, and (3) a tick-health hysteresis gate backed by the existing `paralife.tick.work.ms` DistributionSummary already recorded in `TickEngine`.

The codebase is well-prepared. Micrometer patterns (`WebSocketMetrics`, `EmergenceMetrics`) are established and directly cloneable for the new `AdmissionMetrics` bean. The VT pattern is already used in `TickEngine.start()` and `BotLauncher`, so VT-per-session senders follow the same idiom. The session FSM (Unregistered / Alive / Dead) is cleanly implemented via session attributes in `WorldWebSocketHandler` and extends naturally by adding a `ATTR_STALL_TICK` attribute.

One critical codec finding: the current `PerceptionCodec.parseRegister` strictly enforces `!c.atEnd()` after reading the entity type, throwing `CodecException` on trailing content. This means `r|C|<resumeToken>` will fail codec parsing today. The `RegisterFrame` record and `parseRegister` method must both be extended to accept an optional third pipe-delimited slot. Similarly, `SyncFrame` must accept an optional resume-token field, and `encodeSync` must emit it when present.

**Primary recommendation:** Implement admission in this order: (1) codec wire extension for resume token → (2) token retokening of all rejection sites → (3) VT-per-session outbound queue replacing `synchronized(session)` sends → (4) STALLED FSM state + grace window + `ResumeTokenRegistry` → (5) tick-health hysteresis gate → (6) metrics + log markers + `AdmissionConfig` cleanup + `PopulationCapConfig` deletion.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Admission decision (cap, respawn-cap, tick-overload, maintenance, resume-token re-bind) | API / Backend (`WorldWebSocketHandler` or delegated `AdmissionGate`) | — | `r|` frames arrive at the handler; all admission logic must run server-side before any entity placement |
| Outbound queue + STALLED transition | API / Backend (new per-session sender VT, `TickBroadcaster` enqueue path) | — | Session isolation is structural; the VT-per-session design ensures one slow socket never blocks the tick thread |
| Resume-token storage + expiry sweep | API / Backend (`ResumeTokenRegistry` bean, tick-driven sweep) | — | Server-owned state; client sees only an opaque token string |
| Tick-health rolling window | API / Backend (new `TickHealthMonitor` or inline in `TickEngine`/post-tick `@EventListener`) | — | Reads `paralife.tick.work.ms` already recorded in `TickEngine`; gate logic needs access to `TickConfig.intervalMs` |
| Metrics (gauges + tagged counter) | API / Backend (new `AdmissionMetrics` bean in `com.paralife.metrics`) | — | Follows established `WebSocketMetrics`/`EmergenceMetrics` pattern; no client-side metric concerns |
| Log markers (ADMISSION / BACKPRESSURE / TICK-HEALTH) | API / Backend (emission sites in `WorldWebSocketHandler`, `TickBroadcaster`, tick-health monitor) | — | Log is server-produced; M5 observer consumes |
| Config (`AdmissionConfig`, `paralife.admission.*`) | API / Backend (`@ConfigurationProperties` record) | — | Server-only tuning; no client config surface |
| Bot-side STALLED recovery | Bot / Client (`BotClient`) | — | Must learn `E|408|reconnect-required`, store resume token, reconnect with token on new WS |
| Wire protocol extension (resume token slot) | Codec (`PerceptionCodec`, `Frame.RegisterFrame`, `Frame.SyncFrame`) | — | Shared codec layer; codec changes must be backward-compatible (absent token = fresh registration) |

---

## Standard Stack

### Core (all already present in build.gradle.kts)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.4.4 | Application framework, `@ConfigurationProperties`, `@EventListener`, VT auto-config | Already in use |
| Java 21 | 21 | Virtual threads (`Thread.ofVirtual().start(...)`) | Already `spring.threads.virtual.enabled: true` |
| Micrometer Core | (Spring Boot BOM) | `Counter.builder(name).tag(k,v).register(registry)`, `Gauge.builder`, `DistributionSummary` | Already in use; `WebSocketMetrics` + `EmergenceMetrics` are the established pattern |
| JUnit 5 + Spring Boot Test | (Spring Boot BOM) | `@SpringBootTest`, `@TestPropertySource` | Already in use |
| Jetty 12 WebSocket | 12.0.18 | `WebSocketSession.sendMessage` thread-safety guarantees | Already in use |

[VERIFIED: build.gradle.kts] — no new dependencies required for Phase 17.

### No New Dependencies

[VERIFIED: build.gradle.kts] — Micrometer, Jetty WebSocket, JUnit 5, and Spring Boot starters are all already declared. Phase 17 needs no additions to `build.gradle.kts`.

---

## Architecture Patterns

### System Architecture Diagram

```
Client (BotClient)
    │
    │  r|C                (fresh registration)
    │  r|C|<resumeToken>  (stalled recovery)
    │  a|<verb>|<arg>
    ▼
WorldWebSocketHandler.handleTextMessage
    │
    ├─► [CODEC] PerceptionCodec.decode(raw) → Frame
    │
    ├─► handleRegister(session, RegisterFrame)
    │       │
    │       ▼
    │   AdmissionGate.evaluate(session, resumeToken?)
    │       ├─ maintenance=true? → E|429|maintenance
    │       ├─ tick-health gate open? → E|429|tick-overload
    │       ├─ cap reached? → E|429|world-full
    │       ├─ respawn-cap reached? → E|429|respawn-cap
    │       ├─ already-alive? → E|409|already-registered
    │       ├─ resume-token valid? → re-bind entity, skip placement
    │       └─ ok → place entity, register in BotRegistry
    │                   │
    │                   ▼
    │           S|<entityId>|<resumeToken>  (new SyncFrame field)
    │
    ├─► handleAction(session, ActionFrame)
    │       ├─ STALLED? → E|408|reconnect-required, close WS
    │       └─ ok → ActionResolver.queueAction(sessionId, action)
    │
    └─► afterConnectionEstablished: spawn per-session VT sender
        afterConnectionClosed: interrupt VT, cleanup grace

Per-session VT Sender (one per open session)
    │
    │  ArrayBlockingQueue<Frame> (capacity = outbound-queue-size)
    │
    ▼
    loop: frame = queue.take()  ← blocks on empty (VT yields carrier)
          session.sendMessage(encode(frame))
          [no synchronized needed — single writer per session]

TickBroadcaster.onTick (@Order 50)
    │
    ├─► drainAndBroadcastDeaths() — unchanged
    │
    ├─► for each live bot:
    │       frame = buildTickFrame(bot, tickNumber)
    │       outboundQueue(session).offer(frame)  ← non-blocking
    │           └─ if queue full → mark STALLED (via tick-stall window counter)
    │
    └─► (no synchronized(session) sendMessage — removed)

TickHealthMonitor (@EventListener @Order > 100)
    │
    │  reads paralife.tick.work.ms (DistributionSummary from TickEngine)
    ▼
    rollingWindow[windowTicks] ← record new sample each tick
    rollingMean → compare to high/low watermarks
    → update `tickOverloadActive` boolean (hysteresis)
    → update `paralife.tick.health.work-time-ms` gauge
    → emit TICK-HEALTH log marker on state transitions

ResumeTokenRegistry
    │
    │  ConcurrentHashMap<token, ResumeEntry(entityId, expiresAtTick)>
    ▼
    sweep() called each tick by @EventListener @Order < 10
    → remove expired entries (expiresAtTick < currentTick)
    → cleanupBot(entityId) on expiry
```

### Recommended Project Structure

```
src/main/java/com/paralife/
├── websocket/
│   ├── WorldWebSocketHandler.java     (modified: token retokening, STALLED handling, VT spawn/interrupt, AdmissionGate delegation)
│   ├── SessionRegistry.java           (unchanged)
│   ├── TickBroadcaster.java           (modified: enqueue to per-session queue instead of synchronized sendMessage)
│   ├── RespawnConfig.java             (unchanged or folded into AdmissionConfig)
│   └── [PopulationCapConfig.java]     (DELETED)
├── admission/                         (new package — recommended placement)
│   ├── AdmissionConfig.java           (@ConfigurationProperties prefix "paralife.admission")
│   ├── AdmissionGate.java             (@Component — evaluates all admission rules)
│   ├── ResumeTokenRegistry.java       (@Component — token → entity re-bind map, tick-driven sweep)
│   ├── OutboundSender.java            (per-session VT sender lifecycle manager)
│   └── TickHealthMonitor.java         (@Component @EventListener — rolling window + hysteresis)
├── metrics/
│   ├── WebSocketMetrics.java          (unchanged)
│   ├── EmergenceMetrics.java          (unchanged)
│   └── AdmissionMetrics.java          (new: tagged counter + gauges for admission/backpressure)
├── codec/
│   ├── Frame.java                     (modified: RegisterFrame + optional resumeToken, SyncFrame + optional resumeToken)
│   └── PerceptionCodec.java           (modified: parseRegister + encodeSync/parseSync for token slot)
└── bot/
    └── BotClient.java                 (modified: store resume token, reconnect with token, handle E|408)
```

### Pattern 1: VT-Per-Session Outbound Sender

**What:** One virtual thread per open WS session sits in a `BlockingQueue.take()` loop and calls `session.sendMessage(...)` serially. `TickBroadcaster` enqueues frames non-blocking via `offer()`.

**When to use:** Any time a single-writer-per-session invariant must be maintained without blocking the caller (the tick thread). VTs yield their carrier thread while blocked on `take()`, so N=1000 stalled-on-empty VTs cost O(N × few KB) heap and zero carrier threads.

**VT spawn pattern (established in this codebase):**

```java
// Source: [VERIFIED: TickEngine.java line 74, BotLauncher.java line 52]
// Spawn on afterConnectionEstablished:
Thread senderThread = Thread.ofVirtual()
        .name("ws-sender-" + session.getId())
        .start(() -> drainLoop(session, queue));
senderThreads.put(session.getId(), senderThread);

// Interrupt on afterConnectionClosed:
Thread t = senderThreads.remove(session.getId());
if (t != null) t.interrupt();

// Drain loop:
private void drainLoop(WebSocketSession session, ArrayBlockingQueue<Frame> queue) {
    try {
        while (!Thread.currentThread().isInterrupted()) {
            Frame frame = queue.take(); // VT yields carrier; zero waste
            if (session.isOpen()) {
                String encoded = PerceptionCodec.encode(frame);
                session.sendMessage(new TextMessage(encoded)); // single writer — no sync needed
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // clean exit on close
    } catch (IOException e) {
        log.warn("Send failed for {}: {}", session.getId(), e.getMessage());
    }
}
```

[VERIFIED: TickEngine.java `Thread.startVirtualThread(this::tickLoop)` line 74; BotLauncher.java `Thread.startVirtualThread(...)` line 52]

**Thread-safety:** `session.sendMessage` is NOT inherently thread-safe in Jetty 12. The current `synchronized(session)` in `TickBroadcaster` and `WorldWebSocketHandler.sendFrame` enforces single-writer. With VT-per-session, the single-writer invariant is structural — the VT is the only writer; `synchronized` on `session` can be removed from the send path. The handler's `sendFrame` (error responses on the tick/handler thread) must either enqueue to the same queue or keep its own `synchronized(session)` guard for the minority error-frame path. Recommended: error frames from `handleTextMessage` (inbound thread) enqueue to the per-session queue rather than calling `sendMessage` directly.

[ASSUMED: Jetty 12 WebSocket session.sendMessage is not thread-safe without external synchronization. This is the standard WebSocket API invariant, consistent with existing `synchronized(session)` guards in the codebase, but not verified against Jetty 12 Javadoc in this session.]

### Pattern 2: Tick-Health Hysteresis

**What:** A rolling window of N tick-work-time samples. When the mean crosses `high-water-pct * intervalMs`, the gate opens (rejects new `r|`). It stays open until the mean drops below `low-water-pct * intervalMs`. This prevents flapping on single-tick spikes.

**Measurement source:** `TickEngine` already records each tick's elapsed time into `paralife.tick.work.ms` (a `DistributionSummary`). [VERIFIED: TickEngine.java lines 113, 41-45]. However, `DistributionSummary` accumulates across all ticks — it is not a rolling window. The `TickHealthMonitor` must maintain its own `long[] window` ring buffer fed by a `@EventListener` on `TickEvent` that reads `System.nanoTime()` itself, OR it can be fed by a tick-time measurement exposed from `TickEngine` via a simple `volatile long lastTickWorkMs` field.

**Recommended approach:** Add `volatile long lastTickWorkMs` to `TickEngine` (set alongside the existing `tickWork.record(...)` call). `TickHealthMonitor` listens to `TickEvent` at `@Order(Integer.MAX_VALUE)` (after all pipeline steps including `@Order(100)` `TickBroadcaster`), reads `tickEngine.getLastTickWorkMs()`, and maintains a `long[windowTicks]` ring buffer.

```java
// Source: [ASSUMED] — rolling mean pattern for hysteresis

private final long[] window;    // ring buffer, size = windowTicks config
private int head = 0;
private long sum = 0;
private int filled = 0;
private volatile boolean overloaded = false;

@EventListener
@Order(Integer.MAX_VALUE)
public void onTick(TickEvent event) {
    long sample = tickEngine.getLastTickWorkMs();
    sum -= window[head];
    window[head] = sample;
    sum += sample;
    head = (head + 1) % window.length;
    if (filled < window.length) filled++;

    double mean = (double) sum / filled;
    long budget = tickConfig.intervalMs();

    if (!overloaded && mean > budget * (highWaterPct / 100.0)) {
        overloaded = true;
        log.info("TICK-HEALTH degraded tick={} work-ms={} high-water-pct={}",
                event.tickNumber(), (long)mean, highWaterPct);
    } else if (overloaded && mean < budget * (lowWaterPct / 100.0)) {
        overloaded = false;
        log.info("TICK-HEALTH recovered tick={} work-ms={} low-water-pct={}",
                event.tickNumber(), (long)mean, lowWaterPct);
    }
}

public boolean isOverloaded() { return overloaded; }
```

**Defaults reasoning** (10Hz tick = 100ms budget per LoadTest; 500ms production):
- `high=80%` → gate opens at 80ms mean work / 400ms mean work. Leaves 20% headroom before actual drift.
- `low=60%` → gate clears at 60ms mean / 300ms. 20-point hysteresis band prevents flapping.
- `window=10` → at 10Hz test tick, 10 ticks = 1 second of data. Smooths single-tick GC spikes without being too slow to respond.

[ASSUMED: Default values are analytically sound but not empirically validated. Phase 21 benchmarks will re-derive if needed.]

### Pattern 3: Micrometer Tagged Counter (D-17)

**What:** Single counter with a `reason` tag, replacing per-reason counter fields.

**Established pattern** in this codebase uses named fields. D-17 explicitly chose tagged counter. The idiomatic Micrometer form:

```java
// Source: [VERIFIED: Micrometer API — Counter.builder pattern confirmed in EmergenceMetrics.java]
// Don't cache per-tag Counter instances in a Map<String,Counter>.
// Micrometer's registry already caches by (name, tags) internally.
Counter.builder("paralife.admission.rejected")
        .tag("reason", token)           // token = "world-full", "tick-overload", etc.
        .description("Admission rejections by reason token")
        .register(meterRegistry)
        .increment();
```

Calling `Counter.builder(...).tag(...).register(registry)` on each rejection is safe — `MeterRegistry.counter(name, tags)` uses a meter cache internally; repeated calls with the same name+tags return the same `Counter` instance. [ASSUMED: Micrometer registry caches by (name, tags) key — this is standard Micrometer design per training knowledge but not verified against Micrometer docs in this session.]

**Alternative (counter map):** Pre-populate a `Map<String, Counter>` at bean construction for each known token. Slightly faster (avoids cache lookup per increment), but requires updating the map for every new token. Tagged counter wins for maintainability.

### Pattern 4: `@ConfigurationProperties` Record for `AdmissionConfig`

Established pattern — directly follows `PopulationCapConfig`, `RespawnConfig`, `SimulationConfig`. [VERIFIED: multiple examples in codebase]

```java
// Source: [VERIFIED: PopulationCapConfig.java, RespawnConfig.java patterns]
@ConfigurationProperties(prefix = "paralife.admission")
public record AdmissionConfig(
        int cap,                                     // replaces PopulationCapConfig
        boolean maintenance,
        TickOverloadConfig tickOverload,
        BackpressureConfig backpressure
) {
    @ConstructorBinding
    public AdmissionConfig { /* validation */ }

    public record TickOverloadConfig(int highWaterPct, int lowWaterPct, int windowTicks) {}
    public record BackpressureConfig(int outboundQueueSize, int graceWindowTicks) {}
}
```

This uses nested sub-records within a single `@ConfigurationProperties` record — the decomposition recommended over multiple top-level records because `paralife.admission.*` is a coherent namespace. Spring Boot 3.4.4 supports nested records in `@ConfigurationProperties`. [VERIFIED: existing `SimulationConfig` nests sub-config; `MetabolicProfile` has nested type configs under `paralife.simulation.types.*`]

### Pattern 5: Resume Token Format

The codebase uses `UUID.randomUUID().toString().substring(0, 8)` for composite/member IDs (SimulationEngine lines 630-642). For resume tokens, a stronger recommendation: 16-char hex string from two `random.nextLong()` calls, giving 64 bits of entropy and staying well under the "< 32 chars" target.

```java
// Source: [VERIFIED: SimulationEngine.java UUID pattern; [ASSUMED] hex generation idiom]
private static String generateToken(Random rng) {
    return String.format("%016x", rng.nextLong()); // 16 hex chars, 64-bit entropy
}
```

UUID-v4 (36 chars with hyphens, 32 without) is also safe but 16-hex is shorter and simpler. The `rng` can be `ThreadLocalRandom.current()` since tokens need not be reproducible.

### Pattern 6: STALLED FSM State

The current FSM uses three session attributes as implicit state:

| State | `entityId` attr | `entityType` attr | `respawnCount` attr |
|-------|----------------|-------------------|---------------------|
| Unregistered | absent/null | absent | absent |
| Alive | non-null String | non-null Character | Integer (≥0) |
| Dead (respawn pending) | absent (removed by `markDead`) | non-null | Integer |

STALLED requires distinguishing it from Unregistered (both have absent `entityId`). Recommended: add `ATTR_STALL_TICK` attribute (Long, set to the tick number when STALLED). The FSM check becomes:

```java
// [VERIFIED: WorldWebSocketHandler FSM pattern - attributes approach]
boolean isStalled(Map<String,Object> attrs) {
    return attrs.containsKey(ATTR_STALL_TICK);
}
```

On `markStalled(session, stallTick)`:
1. Set `ATTR_STALL_TICK = currentTick`
2. Enqueue poison-pill to the per-session outbound queue (OR interrupt the sender VT) to stop outbound
3. Remove `entityId` attribute (entity still on grid; `BotRegistry` entry kept until grace expires or reconnect)

On resume-token re-bind (reconnect with valid token, new WS session):
1. New session receives the `entityId` from `ResumeTokenRegistry`
2. `BotRegistry` is updated: old sessionId removed, new sessionId mapped to existing entityId
3. New session gets `S|<entityId>|<newResumeToken>` (fresh token issued; old token consumed/purged)

On grace expiry (`ResumeTokenRegistry.sweep()`):
1. Entry's `expiresAtTick < currentTick` → call existing `cleanupBot(entityId)`
2. Remove token entry

### Anti-Patterns to Avoid

- **Calling `session.sendMessage` from two threads without synchronization.** Even after moving to VT-per-session, error frames from `handleTextMessage` (inbound Jetty thread) could race with the sender VT. Enqueue error frames to the same bounded queue, or use a distinct synchronized path only for the minority error-frame case.
- **Holding a `DistributionSummary.mean()` between ticks for the health gate.** `DistributionSummary` does not expose a rolling mean — it is a cumulative histogram. Use a dedicated ring buffer.
- **Using `Thread.startVirtualThread(...)` directly vs `Thread.ofVirtual().name(...).start(...)`**: prefer the named form for observability in thread dumps; both work.
- **Token expiry as wall-clock time.** Use tick numbers (`expiresAtTick`), consistent with the existing `BuffRegistry.ActiveBuff(expiryTick)` pattern [VERIFIED: BuffRegistry]. Tick numbers survive server restarts only within a session; for grace-window purposes this is fine.
- **Growing `lastRosterHashBySession` map for stalled sessions.** When a session enters STALLED and the WS is ultimately closed, `TickBroadcaster`'s `lastRosterHashBySession` map must be cleaned up in `afterConnectionClosed` (same as it is for any close).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Bounded blocking queue | Custom ring buffer with lock | `ArrayBlockingQueue<Frame>(capacity)` | JDK-standard; `take()` blocks VT yield; `offer()` is non-blocking drop path |
| Tagged metrics | `Map<String, Counter>` | `Counter.builder(name).tag(k,v).register(registry)` | Micrometer registry caches internally; no bean churn per new token |
| UUID/token generation | Custom entropy source | `String.format("%016x", rng.nextLong())` or `UUID.randomUUID()` | Standard JDK; sufficient entropy for short-lived session tokens |
| Config validation | Inline if-checks | `@ConstructorBinding` on record with precondition block | Consistent with existing `PopulationCapConfig`, `RespawnConfig` pattern |
| Tick-driven scheduled sweep | `ScheduledExecutorService` timer | `@EventListener @Order(1)` on `TickEvent` | Stays on the single-threaded sim tick; avoids concurrency on `ResumeTokenRegistry` map |

**Key insight:** The per-session VT-drain pattern solves the slow-client isolation problem with ~15 lines of code. The entire `synchronized(session)` guard in `TickBroadcaster` becomes unnecessary — structural isolation replaces the lock.

---

## Critical Codec Finding (Wire Protocol Delta)

[VERIFIED: PerceptionCodec.java parseRegister line 729-738; encodeSync lines 111-117]

**The current codec REJECTS `r|C|<resumeToken>` with a CodecException.** `parseRegister` reads the entity type char and then asserts `c.atEnd()` — any trailing content throws. Phase 17 requires codec changes:

### Change 1: `Frame.RegisterFrame` — add optional resume token

```java
// Current:
record RegisterFrame(char entityType) implements Frame {}

// Phase 17:
record RegisterFrame(char entityType, Optional<String> resumeToken) implements Frame {}
```

### Change 2: `PerceptionCodec.parseRegister` — accept optional third slot

```java
private static Frame.RegisterFrame parseRegister(ParseCursor c) {
    char t = c.next();
    if (t != 'C' && t != 'M' && t != 'S') throw new CodecException("...");
    Optional<String> token = Optional.empty();
    if (!c.atEnd() && c.peek() == '|') {
        c.next(); // consume '|'
        token = Optional.of(c.readRun(c.remaining()));
    }
    return new Frame.RegisterFrame(t, token);
}
```

### Change 3: `encodeRegister` — emit token when present

```java
private static void encodeRegister(StringBuilder sb, Frame.RegisterFrame r) {
    sb.append('r').append('|').append(r.entityType());
    r.resumeToken().ifPresent(t -> sb.append('|').append(t));
}
```

### Change 4: `Frame.SyncFrame` — add optional resume token

```java
// Current:
record SyncFrame(String entityId, List<ActiveEffect> effects) implements Frame {}

// Phase 17: 
record SyncFrame(String entityId, Optional<String> resumeToken, List<ActiveEffect> effects) implements Frame {}
```

### Change 5: `encodeSync` and `parseSync` — emit/parse token slot

Current `encodeSync` emits `S|<entityId>[|<effects>]`. Phase 17 extends to `S|<entityId>[|<resumeToken>][|<effects>]`. Parser must distinguish token from effects block — effects begin with `f` prefix per SCHEMA §8.3. Token is an opaque string with no `f`-prefix, so order is: `S|<entityId>|<token>|f<effects>` when both present.

[VERIFIED: encodeSync line 111-117; PerceptionCodec encodeEffectList uses 'f' prefix confirmed by SCHEMA §8.3 cross-reference in codec]

**This codec change is prerequisite to all other Phase 17 work.** Plan Wave 0 should include codec extension + codec unit tests before any admission-gate work.

---

## Runtime State Inventory

This is a config-migration + refactor phase (deleting `PopulationCapConfig`, moving to `paralife.admission.*`). Runtime state check:

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | No databases; `WorldGrid` is in-memory; no persistence of `PopulationCapConfig` values | None — in-memory only |
| Live service config | `application.yml` key `paralife.websocket.max-active-entities=256` and `paralife.websocket.max-respawns-per-session=5` | Config key rename: `max-active-entities` → `paralife.admission.cap`; respawn key stays at `paralife.websocket.max-respawns-per-session` (or folds into `paralife.admission.respawn-cap`) |
| OS-registered state | None — no OS-level registration of config keys | None |
| Secrets/env vars | None — config keys are not secret | None |
| Build artifacts | None — no compiled artifact embeds the config key string | None |
| Test `@TestPropertySource` literals | `WorldWebSocketHandlerPopulationCapTest`: `"paralife.websocket.max-active-entities=1"` (rewritten as `AdmissionGateTest`); `LoadTest`: `"paralife.websocket.max-active-entities=1000000"` (migrated to `"paralife.admission.cap=1000000"`) | Code edit — both test files |

[VERIFIED: WorldWebSocketHandlerPopulationCapTest.java line 29; LoadTest.java line 35]

**Nothing found outside source/config files** — no runtime stores, no external services, no OS registrations.

---

## Common Pitfalls

### Pitfall 1: TickBroadcaster Sends After STALLED Transition
**What goes wrong:** Between the tick that marks a session STALLED and the drainLoop detecting the poison-pill, one more tick frame may be enqueued. The client receives a tick frame after the STALLED decision.
**Why it happens:** `onTick` runs at `@Order(50)`; STALLED detection may happen at `@Order(50)` too (same listener, same tick). The queue might have already accepted one frame before STALLED is set.
**How to avoid:** Check STALLED status inside `onTick` before `queue.offer()`. If `isStalled(session)`, skip enqueuing entirely. The stall-trigger tick increments the per-tick-stall-count; only after `window` consecutive ticks is the STALLED transition committed — so the first overload tick does not immediately stall.
**Warning signs:** Tests for STALLED transition may see one extra tick frame — account for this in assertions.

### Pitfall 2: Poison-Pill vs VT Interrupt for Stopping the Sender
**What goes wrong:** Using only `interrupt()` to stop the sender VT works when the VT is blocked in `queue.take()`. But if the VT is between the `take()` return and the `sendMessage()` call when `interrupt()` fires, the interrupt flag is set but the thread continues through the send, sending one frame to a closed session.
**How to avoid:** Check `Thread.currentThread().isInterrupted()` at the top of the loop AND guard `session.isOpen()` before sending. Treat `InterruptedException` from `take()` as clean shutdown signal.
**Warning signs:** IOExceptions from sending to closed sessions after shutdown — benign but noisy in logs.

### Pitfall 3: Resume Token vs Entity ID Binding After BotRegistry Remap
**What goes wrong:** When a bot entity transitions from `Particle` to `BondedPair` or `CompositeMember`, `BotRegistry.remapEntity()` changes the entityId. If a resume token was issued for the original entityId, the `ResumeTokenRegistry` entry points to a stale entityId.
**Why it happens:** Resume tokens are issued at registration time (entityId = `entity-<sessionUUID>`); bonding/composite formation creates new entityIds (`cm-...`).
**How to avoid:** Phase 17 is single-counter + simple-particle scope. The entityId stored in `ResumeTokenRegistry` should be the BotRegistry's current `BotState.entityId()` at the time the STALLED entry is created (not the registration-time entityId). If remap has occurred by then, the token points to the post-remap id.
**Warning signs:** Grace-window re-bind fails silently when the entity has bonded since stall — test with stall-during-bonded scenarios.

### Pitfall 4: `@ConfigurationProperties` on Multiple Records Sharing the Same Prefix Prefix
**What goes wrong:** Both `RespawnConfig` and the old `PopulationCapConfig` use `prefix = "paralife.websocket"`. If `RespawnConfig` is kept as-is and `AdmissionConfig` is added at `prefix = "paralife.admission"`, the config key migration is clean. But if `RespawnConfig` is folded into `AdmissionConfig`, the existing `@TestPropertySource` literals that set `paralife.websocket.max-respawns-per-session` in many tests will break.
**How to avoid:** Enumerate every `@TestPropertySource` literal for both keys before deleting. Prefer keeping `RespawnConfig` at `paralife.websocket.max-respawns-per-session` (sibling, not folded) to minimise test churn. D-17 notes this is Claude's Discretion.
**Warning signs:** `BindException` or `IllegalArgumentException` at Spring context startup in tests after key migration.

### Pitfall 5: `paralife.tick.work.ms` vs `lastTickWorkMs`
**What goes wrong:** Reading `paralife.tick.work.ms` from `MeterRegistry.find(...).distributionSummary().mean()` gives a cumulative mean since startup, not a rolling-window mean. The gate would never open in a long-running server that had fast ticks early on.
**How to avoid:** Maintain a dedicated ring buffer in `TickHealthMonitor`. Use `TickEngine.getLastTickWorkMs()` (new `volatile long` getter) as the per-tick data source.

### Pitfall 6: Codec Backward Compatibility on `r|`
**What goes wrong:** Old `BotClient` implementations and tests that send `r|C` (no token) will be rejected if the codec change accidentally makes the entity-type field optional.
**How to avoid:** The entity type field remains mandatory positional. The token is an optional third slot. `parseRegister` logic: read type (required), if `!atEnd()` expect `|` then read token. Old `r|C` (no `|` suffix) still parses correctly with `token = Optional.empty()`.

---

## Code Examples

### Verified Patterns From Codebase

### Existing VT Start Pattern
```java
// Source: [VERIFIED: TickEngine.java line 74]
tickThread = Thread.startVirtualThread(this::tickLoop);

// Named variant (preferred for observability):
// Source: [VERIFIED: Thread.ofVirtual() is Java 21 standard]
Thread.ofVirtual().name("ws-sender-" + sessionId).start(runnable);
```

### Existing `@ConfigurationProperties` Record Pattern
```java
// Source: [VERIFIED: PopulationCapConfig.java]
@ConfigurationProperties(prefix = "paralife.websocket")
public record PopulationCapConfig(int maxActiveEntities) {
    @ConstructorBinding
    public PopulationCapConfig {
        if (maxActiveEntities <= 0) throw new IllegalArgumentException("...");
    }
    public static PopulationCapConfig defaults() { ... }
}
```

### Existing Micrometer Counter Pattern
```java
// Source: [VERIFIED: EmergenceMetrics.java lines 42-54]
Counter.builder(M_BONDED_PAIRS)
        .description("...")
        .register(registry);
```

### Existing Micrometer Gauge Pattern (AtomicInteger supplier)
```java
// Source: [VERIFIED: WebSocketMetrics.java lines 46-48]
Gauge.builder(M_ACTIVE_SESSIONS, activeSessionCount, AtomicInteger::get)
        .description("...")
        .register(registry);
```

### Existing Error Frame Emission Pattern
```java
// Source: [VERIFIED: WorldWebSocketHandler.java line 181]
sendFrame(session, new Frame.ErrorFrame(400, Optional.of("Malformed frame")));
// Phase 17: replace free-text with token:
sendFrame(session, new Frame.ErrorFrame(400, Optional.of("malformed")));
```

### All Free-Text Rejection Sites to Retoke
```
[VERIFIED: WorldWebSocketHandler.java]
Line 181: Optional.of("Malformed frame")        → Optional.of("malformed")
Line 189: Optional.of("Client cannot send S")   → Optional.of("malformed")  (400)
Line 191: Optional.of("Client cannot send T")   → Optional.of("malformed")  (400)
Line 226: Optional.of("already registered")     → Optional.of("already-registered")
Line 231: Optional.of("population cap exceeded")→ Optional.of("world-full")
Line 239: Optional.of("respawn cap exceeded")   → Optional.of("respawn-cap")
Line 281: Optional.of("GRID_FULL")             → Optional.of("grid-full")
Line 303: Optional.of("no active entity")       → Optional.of("no-active-entity")
```

---

## Environment Availability Audit

Step 2.6: SKIPPED — Phase 17 is code/config-only changes with no external tool dependencies beyond the existing Gradle/JVM build already confirmed working.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|-----------------|--------------|--------|
| `synchronized(session)` tick send | VT-per-session + `BlockingQueue` drain | Phase 17 (this phase) | Tick thread no longer blocks on slow sockets |
| Free-text error messages | Machine-readable token taxonomy | Phase 17 | Bot clients can branch on rejection reason; tests assert on token not string |
| Implicit `PopulationCapConfig` temp guardrail | `AdmissionConfig` durable policy | Phase 17 | Survives v3.0 milestone; extensible to per-type quotas and source-tagging |
| No outbound flow control | STALLED state + grace-window + resume token | Phase 17 | Slow clients shed cleanly; entity preserved for reconnect |

**Deprecated this phase:**
- `PopulationCapConfig`: deleted entirely. Tests and production code must migrate to `AdmissionConfig.cap`.
- `paralife.websocket.max-active-entities`: replaced by `paralife.admission.cap`.
- `synchronized(session) { session.sendMessage(...) }` in `TickBroadcaster`: replaced by `queue.offer(frame)`.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Micrometer registry caches `Counter` by (name, tags) key internally — repeated `Counter.builder(...).tag(k,v).register(registry)` calls on every rejection are safe without a local cache | Pattern 3: Tagged Counter | If Micrometer does NOT cache, repeated `.register()` calls create duplicate meters. Fix: pre-warm a `Map<String, Counter>` in `AdmissionMetrics` ctor for each known token. |
| A2 | Jetty 12 WebSocket `session.sendMessage` is not thread-safe — external synchronization (or single-writer pattern) is required | Pattern 1: VT-Per-Session Sender | If Jetty 12 made `sendMessage` thread-safe, the `synchronized(session)` could be removed without VT-per-session, but VT-per-session is still the right design for backpressure isolation. Low risk — existing code already uses `synchronized(session)`. |
| A3 | `@Order(Integer.MAX_VALUE)` on `TickHealthMonitor` fires after `@Order(100)` `TickBroadcaster` — i.e., the measurement captures broadcast time too | Pattern 2: Tick-Health Hysteresis | If Spring sorts `Integer.MAX_VALUE` before lower `@Order` values (unlikely), the measurement would precede broadcast. Fix: use `@Order(200)` and verify tick pipeline order in tests. |
| A4 | Default watermarks `high=80%, low=60%, window=10` are appropriate for both 100ms (test) and 500ms (production) tick intervals | Pattern 2: Tick-Health Hysteresis | If the production 500ms tick budget means the watermarks never fire under normal load, the gate is effectively disabled. Non-critical: Phase 21 benchmarks will validate. |

**Claims tagged `[ASSUMED]`: A1, A2, A3, A4. Low–medium risk; most are covered by existing patterns in the codebase.**

---

## Open Questions

1. **`RespawnConfig` fold vs sibling**
   - What we know: `RespawnConfig` is at `paralife.websocket.max-respawns-per-session`. Many tests use this key via `@TestPropertySource`.
   - What's unclear: Whether folding into `AdmissionConfig` is worth the test-migration cost.
   - Recommendation: Keep `RespawnConfig` as a sibling at its existing prefix. The rejection token for respawn-cap is still emitted through `AdmissionGate` (or `WorldWebSocketHandler`), satisfying D-07 token taxonomy without moving the config key. Only merge if the planner sees a compelling cohesion reason.

2. **`AdmissionGate` bean — `WorldWebSocketHandler` delegation vs inline**
   - What we know: All admission checks are currently inline in `handleRegister`. The method is ~80 lines with 5 sequential guard clauses.
   - What's unclear: Whether a separate `AdmissionGate` @Component with an `evaluate(...)` method is worth the extra bean.
   - Recommendation: Extract `AdmissionGate` as a separate bean in `com.paralife.admission`. Rationale: it has its own dependencies (`AdmissionConfig`, `ResumeTokenRegistry`, `TickHealthMonitor`, `MeterRegistry`), and extracting it makes `WorldWebSocketHandler` easier to test with a mock gate.

3. **Sender VT interrupt vs poison-pill on STALLED**
   - What we know: STALLED transition closes the WS. The sender VT must stop. Interrupt works when blocked in `queue.take()`; a sentinel `POISON_PILL` frame in the queue works when the VT is between `take()` and `sendMessage()`.
   - Recommendation: Use interrupt only — the `isOpen()` guard before `sendMessage()` handles the between-take-and-send race harmlessly. Poison-pill adds complexity for negligible benefit.

4. **Position of `lastTickWorkMs` exposure from `TickEngine`**
   - What we know: `TickEngine` already records to `tickWork` DistributionSummary but doesn't expose a per-tick volatile.
   - Recommendation: Add `volatile long lastTickWorkMs` field to `TickEngine`, set alongside `tickWork.record(...)`. `TickHealthMonitor` reads it via a simple getter. Alternatively, `TickHealthMonitor` can do its own `System.nanoTime()` measurement in an `@Order` after broadcast — simpler, no `TickEngine` change.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Spring Boot BOM) |
| Config file | `build.gradle.kts` `useJUnitPlatform` block |
| Quick run command | `./gradlew test` (excludes `@Tag("slow")`) |
| Full suite command | `./gradlew test -PincludeLong=true` |

[VERIFIED: build.gradle.kts useJUnitPlatform excludeTags("slow") block]

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SCALE-01 | `paralife.admission.cap=1` rejects second registration with `E|429|world-full` | Unit (mock session) | `./gradlew test --tests "*.AdmissionGateTest"` | ❌ Wave 0 (rewrite of `WorldWebSocketHandlerPopulationCapTest`) |
| SCALE-01 | Respawn cap rejected with `E|429|respawn-cap` token | Unit (mock session) | `./gradlew test --tests "*.AdmissionGateTest"` | ❌ Wave 0 |
| SCALE-01 | `E|429|tick-overload` emitted when gate is open | Unit (controlled `TickHealthMonitor` state) | `./gradlew test --tests "*.TickHealthGateTest"` | ❌ Wave 0 |
| SCALE-01 | `E|429|maintenance` emitted when flag set | Unit | `./gradlew test --tests "*.AdmissionGateTest"` | ❌ Wave 0 |
| SCALE-01 | `E|409|already-registered` token (retokened) | Unit | `./gradlew test --tests "*.AdmissionGateTest"` | ❌ Wave 0 |
| SCALE-01 | `E|400|malformed` token (retokened) | Unit (codec test) | `./gradlew test --tests "*.PerceptionCodecTest"` | ✅ (codec tests exist; new assertion needed) |
| SCALE-01 | `E|503|grid-full` token (retokened) | Unit | `./gradlew test --tests "*.AdmissionGateTest"` | ❌ Wave 0 |
| SCALE-01 | `E|404|no-active-entity` token (retokened) | Unit | `./gradlew test --tests "*.AdmissionGateTest"` | ❌ Wave 0 |
| SCALE-01 | `paralife.admission.rejected{reason}` counter increments per rejection | Unit (Micrometer SimpleMeterRegistry) | `./gradlew test --tests "*.AdmissionMetricsTest"` | ❌ Wave 0 |
| SCALE-02 | Session with full outbound queue transitions to STALLED after window ticks | Integration (controlled queue drain) | `./gradlew test --tests "*.StalledSessionTest"` | ❌ Wave 0 |
| SCALE-02 | Stalled session inbound frame answered `E|408|reconnect-required`, WS closed | Integration | `./gradlew test --tests "*.StalledSessionTest"` | ❌ Wave 0 |
| SCALE-02 | Valid resume token on reconnect re-binds entity (entityId preserved) | Integration | `./gradlew test --tests "*.ResumeTokenTest"` | ❌ Wave 0 |
| SCALE-02 | Expired grace window causes entity reap, token purged | Integration (tick-driven expiry) | `./gradlew test --tests "*.ResumeTokenTest"` | ❌ Wave 0 |
| SCALE-02 | `paralife.backpressure.stalled.sessions` gauge reflects stall count | Unit (Micrometer) | `./gradlew test --tests "*.AdmissionMetricsTest"` | ❌ Wave 0 |
| SCALE-02 | TICK-HEALTH log marker emitted on degraded/recovered transitions | Unit (logback appender capture) | `./gradlew test --tests "*.TickHealthMonitorTest"` | ❌ Wave 0 |
| SCALE-02 | Tick drift < 10% under 100-bot load with outbound queue in place | Integration (`LoadTest` variant) | `./gradlew test --tests "*.LoadTest"` | ✅ (existing; confirm passes after VT refactor) |
| SCALE-01/02 | Codec: `r|C|<token>` parses to `RegisterFrame(C, Optional.of(token))` | Unit (codec) | `./gradlew test --tests "*.PerceptionCodecTest"` | ✅ (test file exists; new test case needed) |
| SCALE-01/02 | Codec: `S|<id>|<token>` encodes/decodes round-trip | Unit (codec) | `./gradlew test --tests "*.PerceptionCodecTest"` | ✅ (test file exists; new test case needed) |
| SCALE-01/02 | `AdmissionConfig` binds correctly from `@TestPropertySource` | Unit (Spring context) | `./gradlew test --tests "*.AdmissionConfigTest"` | ❌ Wave 0 |
| SCALE-01 | `PopulationCapConfig` deletion: no compilation errors in `WorldWebSocketHandler`, `LoadTest` | Build gate | `./gradlew compileTestJava` | — |

### Sampling Rate

- **Per task commit:** `./gradlew test` (fast suite, slow tests excluded)
- **Per wave merge:** `./gradlew test -PincludeLong=true` (full suite including `EmergenceStabilityLoadTest`)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

The following test files must be created before or during the wave that implements the feature they cover:

- [ ] `src/test/java/com/paralife/websocket/AdmissionGateTest.java` — rewrite of `WorldWebSocketHandlerPopulationCapTest`; covers SCALE-01 rejection tokens (world-full, respawn-cap, already-registered, grid-full, no-active-entity, maintenance). REQ: SCALE-01
- [ ] `src/test/java/com/paralife/engine/TickHealthGateTest.java` — tick-health hysteresis: feed controlled samples to `TickHealthMonitor`, assert gate opens/closes on crossing. REQ: SCALE-01 (tick-overload token)
- [ ] `src/test/java/com/paralife/websocket/StalledSessionTest.java` — fill outbound queue, verify STALLED transition + `E|408|reconnect-required` + WS close. REQ: SCALE-02
- [ ] `src/test/java/com/paralife/websocket/ResumeTokenTest.java` — valid/expired/missing token; entity re-bind on reconnect; grace expiry reaps entity. REQ: SCALE-02
- [ ] `src/test/java/com/paralife/metrics/AdmissionMetricsTest.java` — counter increments per token, gauge values. REQ: SCALE-01/02
- [ ] `src/test/java/com/paralife/engine/TickHealthMonitorTest.java` — log-marker emission on transitions. REQ: SCALE-02
- [ ] `src/test/java/com/paralife/admission/AdmissionConfigTest.java` — `@ConfigurationProperties` binding for new keys. REQ: SCALE-01

*(Existing test infrastructure — JUnit 5, `@SpringBootTest`, `@TestPropertySource`, `SimpleMeterRegistry` — covers all phase requirements without new framework installs)*

---

## Security Domain

No new authentication, session management, or cryptographic surfaces are introduced. The resume token is an opaque server-generated random value; guessing it yields at most the ability to re-bind to an entity session that would otherwise have been reaped — equivalent to reconnecting normally. No privilege escalation path.

ASVS V5 (Input Validation) applies lightly: the resume token arriving in `r|<type>|<token>` is looked up in `ResumeTokenRegistry` — if not found, treated as fresh registration (no injection surface). Token is opaque string; no parsing beyond equality check.

---

## Sources

### Primary (HIGH confidence — verified in codebase)
- `WorldWebSocketHandler.java` — all free-text rejection sites; FSM attribute keys; `sendFrame` `synchronized(session)` pattern; `markDead` pattern
- `TickBroadcaster.java` — `synchronized(session) sendMessage` at lines 181-183, 230-232; `@Order(50)` position; death drain pattern
- `TickEngine.java` — `Thread.startVirtualThread(this::tickLoop)` line 74; `tickWork.record(...)` line 113; existing `paralife.tick.work.ms` DistributionSummary
- `WebSocketMetrics.java`, `EmergenceMetrics.java` — canonical Micrometer gauge and counter patterns
- `BotLauncher.java` — `Thread.startVirtualThread(...)` VT pattern line 52
- `PopulationCapConfig.java`, `RespawnConfig.java` — `@ConfigurationProperties` record pattern
- `Frame.java` — `ErrorFrame(int code, Optional<String> message)` current shape
- `PerceptionCodec.java` — `parseRegister` strict `atEnd()` enforcement (lines 729-738); `encodeSync` current shape (lines 111-117)
- `application.yml` — `paralife.websocket.max-active-entities=256`; `spring.threads.virtual.enabled: true`
- `build.gradle.kts` — no new dependencies needed; `useJUnitPlatform excludeTags("slow")` pattern
- `WorldWebSocketHandlerPopulationCapTest.java` — `@TestPropertySource("paralife.websocket.max-active-entities=1")` literal
- `LoadTest.java` — `@TestPropertySource("paralife.websocket.max-active-entities=1000000")` literal
- `BotRegistry.java` — `remapEntity` method; `deathsThisTick` drain pattern

### Secondary (MEDIUM confidence)
- Phase 15 CONTEXT.md D-10 — Micrometer `WebSocketMetrics` pattern as basis for new metrics beans
- Phase 16 CONTEXT.md D-11 — tick-work-time measurement contract (`TickEvent` dispatch start to `@Order(100)` completion)
- Phase 16 CONTEXT.md D-14/D-15 — `EmergenceMetrics` counter pattern; `EMERGENCE` log prefix style (mirrored in `ADMISSION`/`BACKPRESSURE`/`TICK-HEALTH`)
- Phase 15.2 SUMMARY.md — `markDead` pivot template; orthogonality of death-pivot vs STALLED-pivot

### Tertiary (LOW confidence)
- A1 (Micrometer registry caches by name+tags): standard Micrometer design, not verified in this session
- A2 (Jetty 12 sendMessage not thread-safe): consistent with existing synchronized guards but not verified against Jetty 12 Javadoc

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all patterns verified in codebase
- Architecture: HIGH — all components map to existing patterns; VT-per-session is a mechanical extension of existing `Thread.startVirtualThread` use
- Pitfalls: MEDIUM — identified from code reading; not all have been exercised in tests yet
- Codec finding: HIGH — `parseRegister` `atEnd()` enforcement verified by reading source

**Research date:** 2026-04-27
**Valid until:** 2026-05-27 (stable stack; Spring Boot 3.4.4 + Java 21 VT is not fast-moving)
