# Admission Control & Backpressure

**Status:** Live capability contract.

Durable admission, overload backpressure, the resume-token FSM, and the STALLED connection lifecycle.
All wire-emitted rejection reasons are stable machine-readable tokens (§1). Any change updates this
doc before code lands.

---

## §1 Token Taxonomy (D-07)

All rejection reasons emitted on the wire are stable machine-readable tokens. No human-readable suffix is appended. Bot clients and operator tooling branch on the token, not on string fragments.

Wire format: `E|<code>|<token>` — the token slot is always populated for admission rejections.

| HTTP Code | Token | Java Constant | Emitting Site | Cause |
|-----------|-------|---------------|---------------|-------|
| 400 | `malformed` | `RejectionToken.MALFORMED` | `WorldWebSocketHandler.handleTextMessage` (codec exception) | Codec / parse failure on any inbound frame |
| 404 | `no-active-entity` | `RejectionToken.NO_ACTIVE_ENTITY` | `WorldWebSocketHandler.handleAction` | Action frame (`a|`) on Unregistered or Dead session |
| 408 | `reconnect-required` | `RejectionToken.RECONNECT_REQUIRED` | `WorldWebSocketHandler.handleAction` (stall path) | Any inbound frame from a STALLED session; client must drop connection and reconnect |
| 409 | `already-registered` | `RejectionToken.ALREADY_REGISTERED` | `AdmissionGate.evaluate` | Second `r|` frame while session is Alive |
| 429 | `world-full` | `RejectionToken.WORLD_FULL` | `AdmissionGate.evaluate` | Global admission cap reached (D-01); current active entity count >= `AdmissionConfig.cap` |
| 429 | `respawn-cap` | `RejectionToken.RESPAWN_CAP` | `AdmissionGate.evaluate` | Per-session respawn cap reached (`RespawnConfig.maxRespawnsPerSession`) |
| 429 | `tick-overload` | `RejectionToken.TICK_OVERLOAD` | `AdmissionGate.evaluate` | Tick-health admission gate firing (D-14); rolling mean tick-work exceeds high-water mark |
| 429 | `maintenance` | `RejectionToken.MAINTENANCE` | `AdmissionGate.evaluate` | Operator maintenance flag set (`AdmissionConfig.maintenance = true`, D-16) |
| 503 | `grid-full` | `RejectionToken.GRID_FULL` | `WorldWebSocketHandler.handleRegister` (placement) | Placement RNG exhausted `MAX_PLACEMENT_ATTEMPTS`; server cannot find an empty cell |

**Token set is closed for Phase 17.** Future phases extend `ADMISSION.md` with new entries.

Reserved but not emitted this phase: ingress-flood token (D-09 chose counter-only, no kill).

---

## §2 Wire Shape Delta vs `SCHEMA.md`

See `SCHEMA.md` §8.3 for the canonical frame grammar; this document only defines the new resume-token slot and the closed token vocabulary in §1.

### `r` frame (client → server)

```
r|<entityType>                       (existing) — fresh registration
r|<entityType>|<resumeToken>         (NEW Phase 17) — stalled-session recovery
```

- `<entityType>` ∈ `{C, M, S}` — mandatory, unchanged.
- `<resumeToken>` — optional third slot. Present only on stalled-session recovery. Opaque 16-char hex string. Absent = fresh registration (backward-compatible; older clients continue to work).

### `S` frame (server → client)

```
S|<entityId>                         (existing — initial sync, no effects)
S|<entityId>|<resumeToken>           (NEW Phase 17) — first sync after register
S|<entityId>|<resumeToken>|f<effects> — fully populated sync (resumeToken + effects)
```

- `<resumeToken>` is emitted on every successful registration (fresh or re-bind). It is always the second slot when present, before the optional `f<effects>` block.
- The `f<effects>` effects block continues to use the `f` prefix to disambiguate from the resume token (which has no `f`-prefix).

### `E` frame (server → client)

```
E|<code>|<token>                     (token slot now mandatory for admission rejections)
```

- Prior to Phase 17 the message slot held human-readable text. Phase 17 replaces free-text with machine-readable tokens from §1. The frame shape `E|<code>|<message>` is unchanged; only the content of `<message>` changes.

---

## §3 FSM Including STALLED

### States

| State | `entityId` attr | `entityType` attr | `stallTick` attr | Description |
|-------|----------------|-------------------|------------------|-------------|
| **Unregistered** | absent | absent | absent | Fresh session, no `r|` received |
| **Alive** | non-null String | non-null Character | absent | Entity on grid; bot receives tick frames |
| **Dead** | absent (removed by `markDead`) | non-null | absent | Respawn pending; WS stays open (Phase 15.2 death-pivot) |
| **STALLED** | absent | absent | Long (tick number) | Outbound queue overflowed; WS closed by server after `E|408|reconnect-required` |
| **Reaped** | — | — | — | Terminal; entity removed from grid, resume token purged |

Predicate definitions:
- `isAlive(session)`: `entityId` attr present and non-null
- `isDead(session)`: `entityId` absent AND `entityType` present AND `stallTick` absent
- `isStalled(session)`: `stallTick` attr present

### FSM Diagram

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
            ┌───────▼────────┐                               │
   connect  │  Unregistered  │                               │
  ─────────►│                │                               │
            └───────┬────────┘                               │
                    │ r| (fresh)                             │
                    │ AdmissionGate.evaluate OK              │
                    ▼                                         │
            ┌───────────────┐     r| (fresh or re-bind)     │
            │     Alive     │◄────after reconnect/respawn────┘
            │               │
            └──┬────────┬───┘
               │        │
    D (death   │        │  outbound queue full ×
    event via  │        │  window-ticks (D-11)
    Phase 15.2)│        │
               ▼        ▼
        ┌─────────┐  ┌──────────────────┐
        │  Dead   │  │    STALLED       │
        │  (WS    │  │    (WS closed    │
        │  open)  │  │    by server     │
        └────┬────┘  │    after E|408)  │
             │       └────────┬─────────┘
    r| fresh │                │
    (respawn)│  grace window  │
             │  expires or    │
             │  resume token  │
             │  re-bind       │
             │                ▼
             │       ┌────────────────┐
             │       │    Reaped      │
             │       │ (entity removed│
             │       │ from grid)     │
             └──────►└────────────────┘
                (or stays in Alive
                 if respawn succeeds)
```

### Death-Pivot vs Stall-Pivot Orthogonality

| Dimension | Death-Pivot (Phase 15.2) | Stall-Pivot (Phase 17) |
|-----------|--------------------------|------------------------|
| WS state | Stays open | Closed by server |
| entityId continuity | New entity on respawn | Same entity on re-bind |
| Client signal | `D` event in `v` block | `E|408|reconnect-required` |
| Recovery action | `r|<type>` (fresh entity) | `r|<type>|<resumeToken>` (re-bind) |
| Entity on grid | Removed immediately | Held for `graceWindowTicks` |
| Token issued | None | Resume token in `S` reply |

---

## §4 Resume-Token Lifecycle

### Issuance

- Issued on every successful `r|` (fresh registration or re-bind).
- Included in the `S|<entityId>|<resumeToken>` reply.
- Format: 18-char string, `String.format("r:%016x", ThreadLocalRandom.current().nextLong())` — 64-bit entropy, unguessable. The `r:` prefix is the codec disambiguator for the `r|<species>|<resumeToken>` frame so the parser can distinguish a token from an entity-type-only frame.
- Example: `S|entity-abc123|r:4f8b2e9d1a7c3f50`

### Storage

Stored in `ResumeTokenRegistry` as `Map<String, ResumeEntry>`:

```
ResumeEntry {
    entityId:      String     // BotRegistry's current entity ID at stall time
    expiresAtTick: long       // currentTick + graceWindowTicks (default 10)
}
```

Key is the opaque token string. Registry lives in `com.paralife.admission`.

### Re-bind Flow

Client reconnects on a new WebSocket, sends `r|<type>|<resumeToken>`:

1. `AdmissionGate` looks up token in `ResumeTokenRegistry`.
2. If found and `currentTick <= expiresAtTick`: re-bind entity.
   - `BotRegistry` updated: old sessionId removed, new sessionId mapped to existing entityId.
   - Token consumed and purged.
   - Fresh token issued, returned in `S|<entityId>|<newResumeToken>`.
3. If missing or expired: treat as fresh registration (backward-compatible).

### Expiry Sweep

`ResumeTokenRegistry.sweep(currentTick)` called each tick at `@Order(1)` (before `SimulationEngine @Order(10)`):

1. Iterate entries where `expiresAtTick < currentTick`.
2. Call `cleanupBot(entityId)` — removes entity from grid, clears `BotRegistry` entry.
3. Remove token from map.

### Rotation

Old token is consumed (purged) on every successful re-bind. Fresh token issued. Prevents replay of stale tokens.

### Threats

- **Token guessing:** 64-bit entropy = 1.8×10¹⁹ possible tokens. Brute-force is infeasible within a 10-tick (≈1 second) grace window.
- **Token leak:** Token travels on WebSocket (TLS in production). Plain-text only in dev/test.
- **Replay:** Token is consumed on re-bind — cannot be replayed. Grace expiry further limits window.

---

## §5 Tick-Health Gate (D-14 / D-15)

### Mechanism

`TickHealthMonitor` (`@Component`, `@EventListener @Order(Integer.MAX_VALUE)`) maintains a ring buffer of the last `windowTicks` tick-work-time samples.

- **Data source:** `TickEngine.getLastTickWorkMs()` — a `volatile long` field set alongside the existing `tickWork.record(...)` call.
- **Rolling mean:** `sum / filled` over the ring buffer.
- **Gate opens** (`overloaded = true`): `mean > intervalMs * (highWaterPct / 100.0)`.
- **Gate clears** (`overloaded = false`): `mean < intervalMs * (lowWaterPct / 100.0)`.
- Hysteresis prevents flapping on single-tick GC spikes.

### Defaults

| Config key | Default | Reasoning |
|-----------|---------|-----------|
| `paralife.admission.tick-overload.high-water-pct` | 80 | Opens at 80ms mean / 400ms mean. 20% headroom before actual drift. |
| `paralife.admission.tick-overload.low-water-pct` | 60 | Clears at 60ms / 300ms. 20-point hysteresis band prevents flapping. |
| `paralife.admission.tick-overload.window-ticks` | 10 | At 10Hz test tick, covers 1 second of data. Smooths GC spikes. |

### Gate Integration

`AdmissionGate.evaluate()` calls `TickHealthMonitor.isOverloaded()`. If `true`, admission is denied with `E|429|tick-overload`.

### Operator Caveat — Gauge Sampling Lag

The `paralife.tick.health.work-time-ms` gauge value reflects the most recently completed tick — that is, samples lag by 1 tick relative to the dispatching `TickEvent`. During tick-overload episodes, operators reading the gauge live see N-1 latency compared to the tick that triggered the gate transition. This is acceptable for hysteresis correctness (the rolling mean is still computed over a contiguous window) but worth noting when correlating gauge spikes against `ADMISSION rejected reason=tick-overload` log markers. The N-1 lag means a gauge spike may appear one tick after the corresponding admission rejection in the log.

---

## §6 Backpressure (D-09 / D-10 / D-11 / D-12)

### Outbound: VT-Per-Session Sender

Each connected session is paired with one virtual thread (spawned in `afterConnectionEstablished`) that loops:

```java
Frame frame = queue.take();   // VT yields carrier while blocking
if (session.isOpen()) {
    session.sendMessage(new TextMessage(encode(frame)));
}
```

- Queue: `ArrayBlockingQueue<Frame>(outboundQueueSize)` — default 128 frames. At the 30ms test tick with 2 frames/tick/bot (perception + tick snapshot) this gives ≈2s of buffered frames per session — survives GC pauses and scheduler jitter at sustained 100-bot fan-out without false-positive STALLED. Production tick at 500ms makes the same 128 frames a ~64s buffer; tune for workload.
- `TickBroadcaster` enqueues frames via `queue.offer(frame)` (non-blocking). If `offer` returns `false` (queue full), the session transitions to STALLED immediately (single-shot, not windowed).

**Rationale (D-10):** Matches Paralife's VT philosophy (simple blocking code, VTs do concurrency). Per-session isolation is structural — one slow socket cannot block the tick thread or any other session. `queue.size()` is the explicit backpressure signal, trivially observable as a gauge. Java 21 VTs are cheap (few KB heap each); 1000+ VTs is acceptable.

### Inbound: Last-Write-Wins Collapse (D-09)

`ActionResolver.pendingActions` is a `ConcurrentHashMap<sessionId, ActionFrame>`. Any inbound flood collapses to one action per tick — sim correctness is protected. A new aggregate counter `paralife.admission.ingress.overwrites` gives operators visibility into misbehaving clients. No auto-disconnect this phase.

### STALLED Transition (D-11)

When `OutboundSender.offer` returns false (queue full), the session transitions to STALLED immediately (single-shot, not windowed). Windowed-stall could be reintroduced if false-positives become an operator concern, but the empirical 100-bot LoadTest hit ≥99% recovery rate without windowing — so single-shot stays the default.

On the first failed offer:

1. Set `ATTR_STALL_TICK` session attribute to `currentTick`.
2. Remove `entityId` attribute (entity stays on grid under grace).
3. Remove `entityType` attribute.
4. Issue resume token, store in `ResumeTokenRegistry`.
5. Any further inbound frame → `E|408|reconnect-required`, then close WS.
6. Sender VT interrupted (exits `queue.take()` with `InterruptedException`).

### Grace Window (D-12)

Entity held on grid for `graceWindowTicks` (default 10) ticks. `ResumeTokenRegistry` holds `(token → entityId, expiresAtTick)`. If client reconnects with valid token before expiry, entity is re-bound. If grace expires, entity is reaped.

---

## §7 Operator Visibility

### Counters

| Metric | Type | Tags | Increment Site |
|--------|------|------|----------------|
| `paralife.admission.rejected` | Counter | `reason=<token>` | `AdmissionGate.evaluate()` / handler on each rejection |
| `paralife.admission.ingress.overwrites` | Counter | (aggregate) | `ActionResolver` on each `pendingActions.put` overwrite |

### Gauges

| Metric | Value | Source |
|--------|-------|--------|
| `paralife.admission.active.entities` | Count of cap-relevant occupants | `WorldGrid` live count |
| `paralife.admission.maintenance` | 0 or 1 | `AdmissionConfig.maintenance()` |
| `paralife.tick.health.work-time-ms` | Most recently completed tick work time (ms) | `TickEngine.getLastTickWorkMs()` |
| `paralife.backpressure.stalled.sessions` | Count of sessions in STALLED grace | `ResumeTokenRegistry.size()` |

### Log Markers (D-19)

Grep-friendly single-line structured markers — same style as Phase 16 `EMERGENCE` channel.

```
ADMISSION rejected tick=1234 session=abc reason=world-full active=256/256
ADMISSION rejected tick=1235 session=def reason=tick-overload work-ms=420 budget=500
ADMISSION rejected tick=1236 session=ghi reason=maintenance
ADMISSION rejected tick=1237 session=jkl reason=respawn-cap count=5/5
ADMISSION rejected tick=1238 session=mno reason=already-registered
ADMISSION rejected tick=1239 session=pqr reason=grid-full
ADMISSION maintenance state=on
BACKPRESSURE stalled tick=1240 session=abc queue-depth=16 limit=16
BACKPRESSURE resumed tick=1247 session=ghi entity=entity-old-r2 grace-remaining=4
TICK-HEALTH degraded tick=1234 work-ms=420 high-water-pct=80
TICK-HEALTH recovered tick=1260 work-ms=180 low-water-pct=60
```

**Operator cheat sheet:** `grep -E 'ADMISSION|BACKPRESSURE|TICK-HEALTH' server.log`

Log channel pays forward to M5 visualizer / observer — no redesign of emission points required.

---

## §8 Migration Notes (closes 999.1)

### Config Key Changes

| Old Key | New Key | Default | Notes |
|---------|---------|---------|-------|
| `paralife.websocket.max-active-entities` | `paralife.admission.cap` | 256 | `PopulationCapConfig` deleted |
| (none) | `paralife.admission.maintenance` | `false` | New flag |
| (none) | `paralife.admission.tick-overload.high-water-pct` | 80 | New gate |
| (none) | `paralife.admission.tick-overload.low-water-pct` | 60 | New gate |
| (none) | `paralife.admission.tick-overload.window-ticks` | 10 | New gate |
| (none) | `paralife.admission.backpressure.outbound-queue-size` | 128 | New queue |
| (none) | `paralife.admission.backpressure.grace-window-ticks` | 10 | New grace |

`paralife.websocket.max-respawns-per-session` stays at `RespawnConfig` (sibling, not folded) to minimise test churn. The `respawn-cap` token still flows through `AdmissionGate`.

### Deleted Components

- `PopulationCapConfig` — replaced by `AdmissionConfig.cap`
- All free-text error messages — replaced by tokens from §1:
  - `"population cap exceeded"` → `"world-full"`
  - `"respawn cap exceeded"` → `"respawn-cap"`
  - `"GRID_FULL"` → `"grid-full"`
  - `"Malformed frame"` → `"malformed"`
  - `"already registered"` → `"already-registered"`
  - `"no active entity"` → `"no-active-entity"`
  - `"Client cannot send S"` / `"Client cannot send T"` → `"malformed"`

### Test Migration

- `WorldWebSocketHandlerPopulationCapTest` rewritten as `AdmissionGateTest` (Plan 10).
- `LoadTest` property `paralife.websocket.max-active-entities=1000000` migrated to `paralife.admission.cap=1000000`.

---

## §9 Forward Notes

- **Origin-blind:** Admission stays origin-blind (D-03). Phase 18 adds a `source` tag to the `paralife.admission.rejected` counter.
- **In-sim reproduction exempt:** In-sim reproduction stays exempt from the cap (D-02). When bot-driven offspring (backlog 999.2) arrives over WebSocket via `r|`, those calls fall under admission naturally — code stays neutral on entity origin so this transition requires no special-casing.
- **`/actuator/prometheus`:** Deferred to M5 (D-20). Phase 21 benchmark gate reads `/actuator/metrics/<name>` directly.
- **Maintenance actuator endpoint:** Deferred to M5. Config-flag-only this phase; restart required to flip.
- **Per-type quotas:** Deferred pending Phase 21 benchmark evidence. Single-counter design admits trivial extension.

---

*Authored: Phase 17 Plan 01 execution. Canonical source for downstream phases 18–21 and M5 observer.*
