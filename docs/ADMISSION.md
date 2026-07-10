# Admission Control & Backpressure

**Status:** Live capability contract.
**Pinned by:** `AdmissionGateTest` (token taxonomy + the one pinned precedence edge),
`ResumeTokenRegistryTest` (resume-token FSM), `TickHealthMonitorTest` (overload gate),
`OutboundSenderTest` (backpressure).

Durable admission, overload backpressure, the resume-token FSM, and the STALLED connection
lifecycle. Rejection reasons are stable machine-readable tokens (§1); any change updates this
doc before code lands.

> **Normative layer:** the EARS clauses in **§0** are the contract. The prose sections (§1–§9) are
> rationale, tables, and worked examples — where prose reads as a requirement, the §0 clause governs.
> Reference-only sections are tagged *(non-normative)*.

---

## §0 Requirements (EARS)

Each clause is `WHEN <event> THE SYSTEM SHALL <response>`, pinned to an existing test by the exact
assertion it turns on (the line that would go red — not merely "a test exists"). Clauses pin the
**transformation contract** — a config accessor or a behavioural invariant — never a tunable
magnitude: the tick-health watermarks (80/60), the queue size (128), and the grace window (10) are
**test-owned** in their anchors (each anchor builds its own `TickOverloadConfig` / `BackpressureConfig`
/ capacity), not asserted here as literals. The single deliberate wire-literal exception is the
resume-token format `r:%016x` (A9) — the immutable wire contract, regex-pinned. Rejection **tokens**
are pinned *constant-referentially*: the condition → `RejectionToken.X` mapping is the contract; the
literal string value is not asserted in the default suite (see the deferrals note).

| # | Requirement | § | Pinned by — anchor (test method · quoted assertion · symbol) |
|---|---|---|---|
| A1 | WHEN a second `r\|` frame arrives while the session is Alive THE SYSTEM SHALL reject `already-registered` (409). | §1 | `AdmissionGateTest.rejectsAlreadyRegistered` — `code()…isEqualTo(409)` + `token()…isEqualTo(RejectionToken.ALREADY_REGISTERED)` |
| A2 | WHEN the global reservation count is at cap THE SYSTEM SHALL reject `world-full` (429). | §1 | `AdmissionGateTest.rejectsWorldFull` — after two `Allow`s, third `token()…isEqualTo(RejectionToken.WORLD_FULL)` |
| A3 | WHEN a respawning session is at its per-session respawn cap THE SYSTEM SHALL reject `respawn-cap` (429). | §1 | `AdmissionGateTest.rejectsRespawnCap` — `token()…isEqualTo(RESPAWN_CAP)`; integration control `WorldWebSocketHandlerTest.respawnCapEnforced` |
| A4 | WHEN the tick-health gate is overloaded THE SYSTEM SHALL reject `tick-overload` (429). | §1, §5 | `AdmissionGateTest.rejectsTickOverloadAheadOfCap` — overload → `token()…isEqualTo(TICK_OVERLOAD)`. *Cap arg inert:* the gate reads the reservation counter, not `livingEntityCount()=99`, so this pins overload → token, **not** overload-before-cap precedence. |
| A5 | WHEN the maintenance flag is set THE SYSTEM SHALL reject `maintenance` (429). | §1 | `AdmissionGateTest.rejectsMaintenanceFirst` — `token()…isEqualTo(MAINTENANCE)` |
| A6 | WHEN a session is already Alive and presents a resume token THE SYSTEM SHALL reject `already-registered` (409) and SHALL NOT attempt rebind. | §1 | `AdmissionGateTest.rejectsAlreadyRegisteredBeforeResumeToken` — `code()…isEqualTo(409)` + `verify(resumeRegistry, never()).tryRebind(...)` (armed isolating control) |
| A7 | WHEN a session transitions to Dead (`markDead`) THE SYSTEM SHALL remove its `entityId` attribute and clear its active resume-token slot. | §3 | `WorldWebSocketHandlerMarkDeadTest.markDeadCallsClearActiveOnResumeTokenRegistry` — `verify(resumeTokenRegistry).clearActive("entity-1")`; `markDeadRemovesEntityIdAttribute` — `attrs…doesNotContainKey(ATTR_ENTITY_ID)` |
| A8 | WHEN a session's outbound transport errors into stall THE SYSTEM SHALL hold its entity on the grid for the grace sweep (not remove it immediately). | §3 | `WorldWebSocketHandlerRemediationTest.stalledTransportError_holdsEntityForGraceSweep` |
| A9 | WHEN issuing a resume token THE SYSTEM SHALL format it `r:%016x` (`r:` + 16 lowercase-hex chars). | §4 | `ResumeTokenRegistryTest.issueActiveMatchesFormatAndDoesNotIncrementStalledSize` — `token…matches(TOKEN_FORMAT)` where `TOKEN_FORMAT = ^r:[0-9a-f]{16}$`. **The one literal-pinned wire constant.** |
| A10 | WHEN a rebind is attempted THE SYSTEM SHALL succeed only for a STALLED token and reject an ACTIVE token. | §4 | `tryRebindOnStalledReturnsFreshActiveToken` — result present, fresh ACTIVE token ≠ old; `tryRebindRejectsActiveTokens` — `r…isEmpty()` |
| A11 | WHEN a token is rebound THE SYSTEM SHALL consume it so a second rebind of the same token fails (no replay). | §4 | `doubleRebindOfSameTokenFails` — first `isPresent()`, second `isEmpty()` |
| A12 | WHEN the per-tick sweep runs THE SYSTEM SHALL reap only STALLED+expired entries and invoke the cleanup callback with the entityId, never reaping ACTIVE entries. | §4 | `sweepReapsOnlyStalledExpiredAndInvokesCallbackWithEntityId` — `reaped…containsExactlyInAnyOrder("entity-1","entity-2")` + ACTIVE token retained; `sweepDoesNotReapActiveEntries` — `reaped…isEmpty()` |
| A13 | WHEN a rebind presents a missing or expired token THE SYSTEM SHALL return empty (caller falls through to fresh registration). | §4 | `tryRebindExpiredStalledReturnsEmpty` — `r…isEmpty()` (unit, default-gated) |
| A14 ⚠ | WHEN a stalled session rebinds within the grace window THE SYSTEM SHALL restore its entityId and respawn count. | §4 | `StallRecoveryIntegrationTest.stallRecoveryRebindsEntityIdWithinGraceWindow`, `respawnCountRestoredAcrossRebind` — ⚠ `@Tag("slow")`, excluded from `./gradlew test` |
| A15 | WHEN the rolling-mean tick work exceeds the high-watermark THE SYSTEM SHALL open the overload gate. | §5 | `TickHealthMonitorTest.overloadFiresWhenRollingMeanExceedsHighWatermark` — mean crosses 80 → `isOverloaded()…isTrue()` (test-owned `TickOverloadConfig(80,60,5)`) |
| A16 | WHEN the rolling mean drops below the low-watermark THE SYSTEM SHALL clear the gate, and SHALL NOT flap between the watermarks. | §5 | `recoversWhenRollingMeanDropsBelowLowWatermark`; `hysteresisPreventsImmediateRecovery`; `noFlappingOnSamplesInBetweenWatermarks` |
| A17 | WHEN the sample window is not yet full THE SYSTEM SHALL never trip the gate. | §5 | `warmupSamplesNeverTriggerOverload`; `singleSpikeBeforeWindowFillsCannotTriggerOverload` |
| A18 | WHEN constructing a tick-overload config THE SYSTEM SHALL require high-water-pct > low-water-pct. | §5 | `AdmissionConfigTest.rejectsHighWaterAtOrBelowLowWater` — `IllegalArgumentException`…`hasMessageContaining("must be > low-water-pct")` (pins the invariant, not a number) |
| A19 | WHEN the outbound queue is full THE SYSTEM SHALL return false from `offer`. | §6 | `OutboundSenderTest.offerReturnsFalseWhenQueueFull` — capacity=1; `b…isFalse()` with positive control `a…isTrue()` |
| A20 | WHEN the first offer overflows THE SYSTEM SHALL fire the STALLED overflow callback exactly once per attach (idempotent). | §6 | `overflowCallbackFiresExactlyOncePerAttach` — `callbackCount…isEqualTo(1)` after repeated overflow (unit, default-gated) |
| A21 | WHEN a session detaches THE SYSTEM SHALL join the sender VT within the timeout, and SHALL increment the detach-timeout counter when the join times out. | §6 | `detachJoinsVTWithinTimeout` — `elapsedMs…isLessThan(200)`; `detachTimeoutIncrementsCounter` — `after - before…isEqualTo(1.0)` |
| A22 ⚠ | WHEN the grace window expires THE SYSTEM SHALL reap the held entity and force fresh registration. | §6 | `StallRecoveryIntegrationTest.stallExpiryReapsEntityAndForcesFreshRegistration` — ⚠ `@Tag("slow")`, excluded from `./gradlew test` |
| A23 | WHEN a registration is rejected THE SYSTEM SHALL increment `paralife.admission.rejected` tagged `reason=<token>`. | §7 | `AdmissionMetricsTest.rejectedCounterTaggedByReason` — `counter(M_REJECTED,"reason",WORLD_FULL,…).count()…isEqualTo(2.0)` (+ TICK_OVERLOAD=1.0, MAINTENANCE=0.0 negative control) |
| A24 | WHEN an inbound action overwrites a pending action THE SYSTEM SHALL increment `paralife.admission.ingress.overwrites`. | §7 | `AdmissionMetricsTest.ingressOverwriteCounterIsAggregate` — `counter(M_INGRESS_OVERWRITES,…).count()…isEqualTo(2.0)` |
| A25 | WHEN the maintenance flag is set THE SYSTEM SHALL reject `maintenance` ahead of every lower guard — over tick-overload (guard 2) and over a reached global cap (guard 5). | §1, §5 | `AdmissionGateTest.maintenanceRejectedEvenWhenOverloaded` — overloaded + maintenance → `token()…isEqualTo(MAINTENANCE)` (maintenance > overload); `maintenanceRejectedEvenWhenCapReached` — cap armed via `seedReservedSlots()` (grid at cap) → `MAINTENANCE` (maintenance > cap). Positive control `seededCapAloneRejectsWorldFull` proves the seed genuinely arms the cap guard (→ `WORLD_FULL` with no higher guard). |
| A26 | WHEN the tick-health gate is overloaded AND the global cap is already reached THE SYSTEM SHALL reject `tick-overload` (guard 2 > guard 5). | §1, §5 | `AdmissionGateTest.tickOverloadRejectedEvenWhenCapReached` — overloaded + cap armed via `seedReservedSlots()`, then `token()…isEqualTo(TICK_OVERLOAD)`. (Supersedes A4's "cap arg inert" caveat: this genuinely arms the reservation counter.) |
| A27 | WHEN a valid STALLED resume token is presented AND the global cap is already reached THE SYSTEM SHALL rebind (guard 4 > guard 5), never reject `world-full`. | §1, §4, §5 | `AdmissionGateTest.validRebindWinsOverReachedCap` — `tryRebind` returns present + cap armed via `seedReservedSlots()`, then result `isInstanceOf(AdmissionResult.Rebind.class)`. |

**Guard order (prose — precedence edges beyond A6 now clause-pinned).** `AdmissionGate.evaluate`
applies six guards in fixed order (source: `AdmissionGate.java` guards 1–6 + javadoc lines 22–34):
*maintenance → tick-overload → already-registered → resume-token-rebind → global cap → respawn-cap*.
Five precedence edges are now clause-pinned: already-registered → resume-token (A6, armed isolating
control `verify(...never()).tryRebind`); maintenance > overload and maintenance > cap (A25);
overload > cap (A26); rebind > cap (A27). (The one adjacent edge still unpinned is
tick-overload → already-registered.) The cap-involving edges are pinned by arming the
reservation counter via `seedReservedSlots()` in-test (the production `@PostConstruct` seed path,
which does not fire in unit tests) — without arming, the cap guard is inert and the precedence
assertions pass vacuously; the positive control `seededCapAloneRejectsWorldFull` proves the seed
genuinely trips the cap guard. The remaining edge — respawn-cap being lowest — is structural (guard 6
only runs for `isRespawn` requests, which the cap guard skips), not a reorderable precedence.

**Pinning & deferrals.** Honest gaps — *not* minted as clauses:

- **Partial (clause-adjacent, gap annotated):** `malformed`/400 — code pinned by
  `WorldWebSocketHandlerTest.malformedFrameProducesError400` (`err.code() == 400`), token string
  un-asserted. `reconnect-required`/408 — close pinned by
  `StallRecoveryIntegrationTest.stalledSessionInboundIsRejectedWith408AndClosed` (`@slow`), token
  best-effort per D-07. `grid-full`/503 — the 503 code + placement-exhaustion pinned by
  `PlacementDensityIntegrationTest.fillsGridAndReceivesGridFullOnExhaustion` (`response.startsWith("E\|503")`),
  but token string un-asserted **and** a non-isolating survive-a-run integration test — demoted here,
  not a clean clause.
- **Token wire-strings are constant-referential, not literal-pinned.** §1 clauses pin
  condition → `RejectionToken.X` *constant*; renaming a token's string value stays green in the
  default suite (the literal-string assertions live in `@slow` integration tests such as the
  non-normative `AdmissionLogMarkersIntegrationTest`). A codec admission-path `E`-frame literal
  test → BACKLOG.
- **Integration / `@slow`-only anchors (A14, A22):** real but excluded from `./gradlew test` (run via
  `-PincludeLong=true`). Engine-direct unit decomposition → BACKLOG.
- **Orphans (excluded from §0):** `no-active-entity`/404 (zero tests — grep-confirmed); inbound
  collapse-to-one *behaviour* (only the counter A24 is pinned). → BACKLOG. (The §5 N-1 gauge-lag
  caveat is a documented observability note, not a deferred item — see §5.)
- **Cross-guard precedence edges** beyond A6 — ✅ **now pinned** (A25 maintenance > overload/cap, A26
  overload > cap, A27 rebind > cap): the unit tests arm the `reservedSlots` cap gate via
  `seedReservedSlots()` (the production seed path), with `seededCapAloneRejectsWorldFull` as the
  positive control. RED-tested by moving the cap check ahead of guards 1/2/4 (all three go red;
  control stays green).

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

## §2 Wire Shape Delta vs `SCHEMA.md` *(non-normative — canonical grammar is `SCHEMA.md` §6 / §0)*

See `SCHEMA.md` §6 for the canonical frame grammar. This doc adds only the resume-token slot:
`r|<entityType>|<resumeToken>` (optional third slot, stalled-session recovery, opaque 16-char hex,
absent = fresh registration); `S|<entityId>|<resumeToken>` (emitted on every successful
registration, before any `f<effects>` block); `E|<code>|<token>` (message slot now carries a
machine-readable token from §1 — frame shape unchanged).

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

### FSM Summary *(non-normative — illustrative; transition clauses A7/A8 are the contract)*

- Unregistered → (`r|`, AdmissionGate OK) → Alive
- Alive → (`D` event, Phase 15.2) → Dead → (`r|` fresh) → Alive (new entityId, WS stays open)
- Alive → (outbound queue full, D-11) → STALLED (WS closed, `E|408`) → (resume-token re-bind) → Alive (same entityId)
- STALLED → (grace window expires) → Reaped (entity removed from grid)
- Death-Pivot vs Stall-Pivot: death keeps the WS open and mints a new entityId on respawn with no
  token; stall closes the WS, holds the entity for `graceWindowTicks`, and re-binds via resume token.

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

64-bit token entropy (1.8×10¹⁹ combinations) makes guessing infeasible within the grace window;
TLS protects transit in production; consumption-on-re-bind plus grace expiry prevents replay.

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

`paralife.tick.health.work-time-ms` lags the dispatching `TickEvent` by one sample (N-1), so a
gauge spike can appear one tick after the corresponding `ADMISSION rejected reason=tick-overload`
log line; hysteresis correctness is unaffected. Observability note only — no deferred action.

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

**Rationale (D-10)** *(non-normative)*: Matches Paralife's VT philosophy — per-session isolation is
structural (one slow socket can't block the tick thread or other sessions); `queue.size()` is the
explicit backpressure gauge; VTs are cheap enough for 1000+ sessions.

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

### Log Markers (D-19) *(non-normative — marker string formats are not pinned)*

Grep-friendly single-line structured markers — same style as Phase 16 `EMERGENCE` channel.

```
ADMISSION rejected tick=1234 session=abc reason=world-full active=256/256
BACKPRESSURE stalled tick=1240 session=abc queue-depth=16 limit=16
TICK-HEALTH degraded tick=1234 work-ms=420 high-water-pct=80
```

The block above is illustrative (one shape per prefix), not exhaustive. **Full marker vocabulary
(source-of-truth, zero staleness risk):** `grep -rhoE '"(ADMISSION|BACKPRESSURE|TICK-HEALTH)[^"{]*' src/main/java`
(10 shapes as of Phase 20); a subset is shape-pinned by `AdmissionLogMarkersIntegrationTest` and
`TickHealthGateIntegrationTest`.

**Operator cheat sheet:** `grep -E 'ADMISSION|BACKPRESSURE|TICK-HEALTH' server.log`

Log channel pays forward to M5 visualizer / observer — no redesign of emission points required.

---

## §8 Migration Notes (closes 999.1) *(non-normative)*

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

---

## §9 Forward Notes *(non-normative)*

- **Origin-blind:** Admission stays origin-blind (D-03). Phase 18 adds a `source` tag to the `paralife.admission.rejected` counter.
- **In-sim reproduction exempt:** In-sim reproduction stays exempt from the cap (D-02). When bot-driven offspring (backlog 999.2) arrives over WebSocket via `r|`, those calls fall under admission naturally — code stays neutral on entity origin so this transition requires no special-casing.
- **`/actuator/prometheus`:** Deferred to M5 (D-20). Phase 21 benchmark gate reads `/actuator/metrics/<name>` directly.
- **Maintenance actuator endpoint:** Deferred to M5. Config-flag-only this phase; restart required to flip.
- **Per-type quotas:** Deferred pending Phase 21 benchmark evidence. Single-counter design admits trivial extension.
