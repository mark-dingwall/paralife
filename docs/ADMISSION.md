# Admission Control & Backpressure

**Status:** Live capability contract.
**Pinned by:** `AdmissionGateTest` (gate routing + precedence edges),
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
/ capacity), not asserted here as literals. The deliberate wire-literal pins are two: the resume-token
*format* `r:%016x` (A9) — the immutable wire format, regex-pinned — and the rejection-token
*vocabulary* strings (A28, below). Rejection **tokens**
are pinned two ways. Condition → `RejectionToken.X` *routing*: constant-referentially for the
**gate-emitted** subset (A1–A6, A25–A27), and by **independent wire literal** for all four
**handler-emitted** tokens — `no-active-entity`/`malformed`/`grid-full`/`reconnect-required`
(A29/A30/A31/A32, emitted by `WorldWebSocketHandler`, not `AdmissionGate`). Token *string value*: the
frozen strings of all 9 enum-backed §1 tokens are pinned as independent wire literals by A28
(`RejectionTokenWireTest`).

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
| A9 | WHEN issuing a resume token THE SYSTEM SHALL format it `r:%016x` (`r:` + 16 lowercase-hex chars). | §4 | `ResumeTokenRegistryTest.issueActiveMatchesFormatAndDoesNotIncrementStalledSize` — `token…matches(TOKEN_FORMAT)` where `TOKEN_FORMAT = ^r:[0-9a-f]{16}$`. The one literal-pinned wire *format* (A28 pins the fixed rejection-token *vocabulary*). |
| A10 | WHEN a rebind is attempted THE SYSTEM SHALL succeed only for a STALLED token and reject an ACTIVE token. | §4 | `tryRebindOnStalledReturnsFreshActiveToken` — result present, fresh ACTIVE token ≠ old; `tryRebindRejectsActiveTokens` — `r…isEmpty()` |
| A11 | WHEN a token is rebound THE SYSTEM SHALL consume it so a second rebind of the same token fails (no replay). | §4 | `doubleRebindOfSameTokenFails` — first `isPresent()`, second `isEmpty()` |
| A12 | WHEN the per-tick sweep runs THE SYSTEM SHALL reap only STALLED+expired entries and invoke the cleanup callback with the entityId, never reaping ACTIVE entries. | §4 | `sweepReapsOnlyStalledExpiredAndInvokesCallbackWithEntityId` — `reaped…containsExactlyInAnyOrder("entity-1","entity-2")` + ACTIVE token retained; `sweepDoesNotReapActiveEntries` — `reaped…isEmpty()` |
| A13 | WHEN a rebind presents a missing or expired token THE SYSTEM SHALL return empty (caller falls through to fresh registration). | §4 | `tryRebindExpiredStalledReturnsEmpty` — `r…isEmpty()` (unit, default-gated) |
| A14 | WHEN a stalled session rebinds within the grace window THE SYSTEM SHALL restore its entityId and respawn count. | §4 | **Mechanism default-gated:** entityId → A10 (`RebindOutcome.entityId…isEqualTo` original); grace-window boundary → A10 (rebind within) + A13 (expired → empty); **respawn-count restore** → `WorldWebSocketHandlerTest.rebindRestoresRespawnCountFromStallSnapshot` — engine-direct twin driving register→respawn→`markStalled`→rebind with mock sessions (no overflow), asserting the new session's `respawnCount==1` (control: entityId preserved; RED-tested by deleting the L560-562 restore → red). **E2E wiring** `StallRecoveryIntegrationTest.stallRecoveryRebindsEntityIdWithinGraceWindow`, `respawnCountRestoredAcrossRebind` — ⚠ `@Tag("slow")` (kept as the end-to-end overflow-driven anchor). |
| A15 | WHEN the rolling-mean tick work exceeds the high-watermark THE SYSTEM SHALL open the overload gate. | §5 | `TickHealthMonitorTest.overloadFiresWhenRollingMeanExceedsHighWatermark` — mean crosses 80 → `isOverloaded()…isTrue()` (test-owned `TickOverloadConfig(80,60,5)`) |
| A16 | WHEN the rolling mean drops below the low-watermark THE SYSTEM SHALL clear the gate, and SHALL NOT flap between the watermarks. | §5 | `recoversWhenRollingMeanDropsBelowLowWatermark`; `hysteresisPreventsImmediateRecovery`; `noFlappingOnSamplesInBetweenWatermarks` |
| A17 | WHEN the sample window is not yet full THE SYSTEM SHALL never trip the gate. | §5 | `warmupSamplesNeverTriggerOverload`; `singleSpikeBeforeWindowFillsCannotTriggerOverload` |
| A18 | WHEN constructing a tick-overload config THE SYSTEM SHALL require high-water-pct > low-water-pct. | §5 | `AdmissionConfigTest.rejectsHighWaterAtOrBelowLowWater` — `IllegalArgumentException`…`hasMessageContaining("must be > low-water-pct")` (pins the invariant, not a number) |
| A19 | WHEN the outbound queue is full THE SYSTEM SHALL return false from `offer`. | §6 | `OutboundSenderTest.offerReturnsFalseWhenQueueFull` — capacity=1; `b…isFalse()` with positive control `a…isTrue()` |
| A20 | WHEN the first offer overflows THE SYSTEM SHALL fire the STALLED overflow callback exactly once per attach (idempotent). | §6 | `overflowCallbackFiresExactlyOncePerAttach` — `callbackCount…isEqualTo(1)` after repeated overflow (unit, default-gated) |
| A21 | WHEN a session detaches THE SYSTEM SHALL join the sender VT within the timeout, and SHALL increment the detach-timeout counter when the join times out. | §6 | `detachJoinsVTWithinTimeout` — `elapsedMs…isLessThan(200)`; `detachTimeoutIncrementsCounter` — `after - before…isEqualTo(1.0)` |
| A22 | WHEN the grace window expires THE SYSTEM SHALL reap the held entity and force fresh registration. | §6 | **Mechanism default-gated:** sweep *detection* → A12 (`sweepReapsOnlyStalledExpiredAndInvokesCallbackWithEntityId` — STALLED+expired at grace boundary, dispatch-with-entityId, via a fake callback); empty-return precondition → A13 (`tryRebindExpiredStalledReturnsEmpty`); empty→`Allow` *fresh-registration routing* → `AdmissionGateTest.unknownResumeTokenFallsThroughToFreshRegistration` (`isInstanceOf(Allow)`). The callback *body* (real grid/registry removal + `respawnCountAtStall.remove`) is integration-shaped — **E2E wiring** `StallRecoveryIntegrationTest.stallExpiryReapsEntityAndForcesFreshRegistration` (⚠ `@Tag("slow")`) covers the removal end-to-end. |
| A23 | WHEN a registration is rejected THE SYSTEM SHALL increment `paralife.admission.rejected` tagged `reason=<token>`. | §7 | `AdmissionMetricsTest.rejectedCounterTaggedByReason` — `counter(M_REJECTED,"reason",WORLD_FULL,…).count()…isEqualTo(2.0)` (+ TICK_OVERLOAD=1.0, MAINTENANCE=0.0 negative control) |
| A24 | WHEN an inbound action overwrites a pending action THE SYSTEM SHALL increment `paralife.admission.ingress.overwrites`. | §7 | `AdmissionMetricsTest.ingressOverwriteCounterIsAggregate` — `counter(M_INGRESS_OVERWRITES,…).count()…isEqualTo(2.0)` |
| A25 | WHEN the maintenance flag is set THE SYSTEM SHALL reject `maintenance` ahead of every lower guard — over tick-overload (guard 2) and over a reached global cap (guard 5). | §1, §5 | `AdmissionGateTest.maintenanceRejectedEvenWhenOverloaded` — overloaded + maintenance → `token()…isEqualTo(MAINTENANCE)` (maintenance > overload); `maintenanceRejectedEvenWhenCapReached` — cap armed via `seedReservedSlots()` (grid at cap) → `MAINTENANCE` (maintenance > cap). Positive control `seededCapAloneRejectsWorldFull` proves the seed genuinely arms the cap guard (→ `WORLD_FULL` with no higher guard). |
| A26 | WHEN the tick-health gate is overloaded AND the global cap is already reached THE SYSTEM SHALL reject `tick-overload` (guard 2 > guard 5). | §1, §5 | `AdmissionGateTest.tickOverloadRejectedEvenWhenCapReached` — overloaded + cap armed via `seedReservedSlots()`, then `token()…isEqualTo(TICK_OVERLOAD)`. (Supersedes A4's "cap arg inert" caveat: this genuinely arms the reservation counter.) |
| A27 | WHEN a valid STALLED resume token is presented AND the global cap is already reached THE SYSTEM SHALL rebind (guard 4 > guard 5), never reject `world-full`. | §1, §4, §5 | `AdmissionGateTest.validRebindWinsOverReachedCap` — `tryRebind` returns present + cap armed via `seedReservedSlots()`, then result `isInstanceOf(AdmissionResult.Rebind.class)`. |
| A28 | WHEN encoding a rejection `ErrorFrame` that carries an enum-backed §1 token THE SYSTEM SHALL produce the exact wire literal `E\|<code>\|<token>` verbatim (the frozen string value of the corresponding `RejectionToken` constant, all 9 enum-backed rows). | §1 | `RejectionTokenWireTest.tokenEncodesToExactWireLiteral` — parameterized over all 9 `RejectionToken` rows; `PerceptionCodec.encode(new Frame.ErrorFrame(code, Optional.of(RejectionToken.X)))…isEqualTo` an **independent** literal (e.g. `"E\|429\|world-full"`), so renaming any constant's string value goes red. Pins the wire-encoding boundary, **not** condition→token routing. The direct `stale-resume-token` race response is the named unpinned orphan below. |
| A29 | WHEN an `ActionFrame` (`a\|`) arrives on a session with no active entity (`ATTR_ENTITY_ID` absent) THE SYSTEM SHALL reject `E\|404\|no-active-entity` and SHALL NOT queue the action. | §1 | `WorldWebSocketHandlerTest.actionOnUnregisteredSessionRejectedNoActiveEntity` — captured send `…equals("E\|404\|no-active-entity")` (independent literal) + `verify(actionResolver, never()).queueAction(eq("s1"),…)` isolates the not-queued conjunct (`@SpyBean`). Positive control `actionOnRegisteredSessionIsQueued` — registered session's action → `verify(...).queueAction(eq("s2"),…)`, proving both conjuncts are condition-specific. RED-tested: token swap → reject row red; `return` removed → not-queued row red. |
| A30 | WHEN inbound text fails codec decode OR decodes to a client-illegal frame direction (`Sync`/`Tick`, server→client only) THE SYSTEM SHALL reject `E\|400\|malformed`. | §1 | `WorldWebSocketHandlerTest.malformedFrameProducesError400` — CodecException path, captured send `…equals("E\|400\|malformed")` (independent literal); `clientIllegalFrameDirectionRejectedAsMalformed` — parameterized `{Sync,Tick}` wire (encode-derived; `assertInstanceOf` precondition proves the illegal-direction arm is reached, not the CodecException fallback), each captured send `…equals("E\|400\|malformed")`. RED-tested: Sync-arm token swap → only the Sync row red, Tick green (per-arm isolation). Covers all 3 `MALFORMED` emit sites. |
| A31 | WHEN registration placement exhausts the eligible set THE SYSTEM SHALL reject `E\|503\|grid-full`. | §1 | `PlacementDensityIntegrationTest.fillsGridAndReceivesGridFullOnExhaustion` — captured exhaustion-boundary 503 payload `…isEqualTo("E\|503\|grid-full")`, upgrading the prior code-only `startsWith("E\|503")` to the exact token. ⚠ **integration-anchored / non-isolating** (survive-a-run fill; condition→token pinned but not unit-isolated). RED-tested: token swap → red (`was "E\|503\|maintenance"`). |
| A32 | WHEN any inbound frame arrives on a STALLED session THE SYSTEM SHALL reject `E\|408\|reconnect-required` (best-effort per D-07 — the OOB send is `isOpen()`-gated; in production `markStalled` closes the transport first, so it typically does not reach the wire) and close the transport (`SERVICE_RESTARTED`), short-circuiting before the decode/dispatch switch. | §1/§4 | `WorldWebSocketHandlerTest.stalledSessionInboundRejectedWithReconnectRequired` — engine-direct: register→`markStalled`→inbound (mock session, no overflow). The **408-vs-404 payload** is the discriminator: `markStalled` clears `ATTR_ENTITY_ID`, so with the guard removed an action falls through to the null-entity branch and emits `E\|404\|no-active-entity`; the test asserts the exact literal `…equals("E\|408\|reconnect-required")` + `verify(sc).close(SERVICE_RESTARTED)` on **two** frame kinds (`a\|M\|1` and `r\|C`) so a per-frame-kind guard relocation is caught. (A `never().queueAction` check would be vacuous — the null-entity branch returns before `queueAction` guard-or-no-guard.) `clearInvocations` discards `markStalled`'s own best-effort OOB 408. RED-tested: L474 stall guard disabled → 408 assert `WantedButNotInvoked` (frame falls through to 404). **Sole** coverage of the L474 guard — the `@slow` `StallRecoveryIntegrationTest.stalledSessionInboundIsRejectedWith408AndClosed` pins overflow→stall→close only (no post-stall inbound, does not assert the 408). |

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

- **Handler-emitted routing — ✅ now pinned (A29/A30/A31/A32).** A28 pins every §1 token's *string
  value*; condition→token **routing** for the handler-emitted subset is now clause-pinned:
  `no-active-entity`/404 (A29, unit-isolated, `@SpyBean` on the not-queued conjunct), `malformed`/400
  (A30, all three inbound-illegal emit sites), `grid-full`/503 (A31, integration-anchored/non-isolating),
  `reconnect-required`/408 (A32, unit-isolated engine-direct — 408-vs-404 payload discrimination on two
  frame kinds; the 408 send is best-effort per D-07, observable here only because the mock stays open).
  The `@slow` `StallRecoveryIntegrationTest.stalledSessionInboundIsRejectedWith408AndClosed` pins
  overflow→stall→**close** only (it sends no post-stall inbound frame and does not assert the 408), so
  A32's unit test is the **sole** coverage of the L474 inbound guard. **§0 routing sweep now
  complete** — all four handler-emitted tokens have default-gated condition→token pins.
- **Token wire-strings — ✅ now literal-pinned (A28).** Previously constant-referential only (§1
  clauses pin condition → `RejectionToken.X` *constant*, so renaming a token's string value stayed
  green); `RejectionTokenWireTest` now pins all 9 literals in the **default** suite against independent
  `E\|<code>\|<token>` literals. (The `@slow` literal assertions in the non-normative
  `AdmissionLogMarkersIntegrationTest` remain as-is.)
- **A14/A22 — ✅ mechanism now default-gated (E2E stays `@slow`).** The engine-direct decomposition is
  done: A14's entityId/grace-window by A10/A13, its respawn-count restore by the new
  `rebindRestoresRespawnCountFromStallSnapshot` twin; A22's reap-detection by A12 and fresh-registration
  routing by A13 + `AdmissionGateTest.unknownResumeTokenFallsThroughToFreshRegistration`. The
  `StallRecoveryIntegrationTest` `@slow` anchors are retained as the end-to-end overflow-driven wiring
  (run via `-PincludeLong=true`), no longer the sole gate.
- **Orphans (excluded from §0):** inbound collapse-to-one *behaviour* (only the counter A24 is
  pinned), plus the direct `stale-resume-token` rebind-race response (no `RejectionToken` constant,
  metric increment, or exact-wire test). → BACKLOG. (`no-active-entity`/404 routing is no longer an
  orphan — now pinned by A29. The §5 N-1 gauge-lag caveat is a documented observability note.)
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
| 400 | `stale-resume-token` | — (direct literal; backlog orphan) | `WorldWebSocketHandler.handleRegister` | Token rebind succeeded, but the preserved entity's `BotRegistry` binding was absent at handler commit |
| 404 | `no-active-entity` | `RejectionToken.NO_ACTIVE_ENTITY` | `WorldWebSocketHandler.handleAction` | Action frame (`a|`) on Unregistered or Dead session |
| 408 | `reconnect-required` | `RejectionToken.RECONNECT_REQUIRED` | `WorldWebSocketHandler.handleTextMessage` (stall guard) | Any inbound frame from a STALLED session; best-effort send, then close/reconnect |
| 409 | `already-registered` | `RejectionToken.ALREADY_REGISTERED` | `AdmissionGate.evaluate` | Second `r|` frame while session is Alive |
| 429 | `world-full` | `RejectionToken.WORLD_FULL` | `AdmissionGate.evaluate` | Atomic fresh-registration reservation count is at `AdmissionConfig.cap` |
| 429 | `respawn-cap` | `RejectionToken.RESPAWN_CAP` | `AdmissionGate.evaluate` | Per-session respawn cap reached (`RespawnConfig.maxRespawnsPerSession`) |
| 429 | `tick-overload` | `RejectionToken.TICK_OVERLOAD` | `AdmissionGate.evaluate` | Tick-health admission gate firing (D-14); rolling mean tick-work exceeds high-water mark |
| 429 | `maintenance` | `RejectionToken.MAINTENANCE` | `AdmissionGate.evaluate` | Operator maintenance flag set (`AdmissionConfig.maintenance = true`, D-16) |
| 503 | `grid-full` | `RejectionToken.GRID_FULL` | `WorldWebSocketHandler.handleRegister` (placement) | Eligible set exhausted or placement attempts cannot win a free cell |

The nine `RejectionToken` values are the literal-pinned Phase-17 vocabulary. The direct
`stale-resume-token` row is a later implementation orphan: live on the wire but not yet normalized
through the enum/metric/test path (tracked in `BACKLOG.md`).

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
| **STALLED** | absent | non-null Character (retained) | Long (tick number) | Outbound queue overflowed; WS closes with `SERVICE_RESTARTED`; `E|408|reconnect-required` is best-effort |
| **Reaped** | — | — | — | Terminal; entity removed from grid, resume token purged |

Predicate definitions:
- `isAlive(session)`: `entityId` attr present and non-null
- `isDead(session)`: `entityId` absent AND `entityType` present AND `stallTick` absent
- `isStalled(session)`: `stallTick` attr present

### FSM Summary *(non-normative — illustrative; transition clauses A7/A8 are the contract)*

- Unregistered → (`r|`, AdmissionGate OK) → Alive
- Alive → (`D` event, Phase 15.2) → Dead → (`r|` fresh) → Alive (new entityId, WS stays open)
- Alive → (outbound queue full, D-11) → STALLED (`SERVICE_RESTARTED` close; best-effort `E|408`) → (resume-token re-bind) → Alive (same entityId)
- STALLED → (grace window expires) → Reaped (entity removed from grid)
- Death-Pivot vs Stall-Pivot: death keeps the WS open and respawns without *presenting* a resume
  token; successful respawn returns a fresh token. Stall closes the WS, holds the entity for
  `graceWindowTicks`, and re-binds via the cached resume token.

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
    entityId:          String
    originalSessionId: String
    expiresAtTick:     long
    state:             ACTIVE | STALLED
}
```

Key is the opaque token string. ACTIVE entries use `Long.MAX_VALUE` and are not sweep-reapable;
`convertToStalled` changes the existing token to STALLED with a finite grace expiry.

### Re-bind Flow

Client reconnects on a new WebSocket, sends `r|<type>|<resumeToken>`:

1. `AdmissionGate` looks up token in `ResumeTokenRegistry`.
2. If found, STALLED, and `currentTick < expiresAtTick`: re-bind entity.
   - `BotRegistry` updated: old sessionId removed, new sessionId mapped to existing entityId.
   - Token consumed and purged.
   - Fresh token issued, returned in `S|<entityId>|<newResumeToken>`.
3. If missing or expired: treat as fresh registration (backward-compatible).

### Expiry Sweep

`ResumeTokenRegistry.sweep(currentTick)` called each tick at `@Order(1)` (before `SimulationEngine @Order(10)`):

1. Iterate STALLED entries where `expiresAtTick <= currentTick`.
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
3. Retain `entityType` for reconnect/respawn state.
4. Convert the cached ACTIVE resume token to STALLED with a finite expiry.
5. Close the transport with `SERVICE_RESTARTED`; any later inbound frame gets a best-effort
   `E|408|reconnect-required` only if the session still reports open.
6. Sender VT is detached/interrupted.

### Grace Window (D-12)

Entity held on grid for `graceWindowTicks` (default 10) ticks. `ResumeTokenRegistry` holds the
STALLED entry described in §4. If a client reconnects with a valid token strictly before expiry,
the entity is re-bound; at the expiry tick it is reaped.

---

## §7 Operator Visibility

### Counters

| Metric | Type | Tags | Increment Site |
|--------|------|------|----------------|
| `paralife.admission.rejected` | Counter | `reason`, `source`[, `harness`] | Gate/handler rejection paths that route through `AdmissionMetrics` |
| `paralife.admission.ingress.overwrites` | Counter | `source`[, `harness`] | `ActionResolver` on each `pendingActions.put` overwrite |

### Gauges

| Metric | Value | Source |
|--------|-------|--------|
| `paralife.admission.active.entities` | Count of admitted cap-relevant occupants by `source`[, `harness`] (Alive plus STALLED grace-held entities) | `AdmissionMetrics.activeBuckets`, maintained by handler lifecycle paths |
| `paralife.admission.maintenance` | Intended 0/1 maintenance state | `AdmissionMetrics.maintenanceState`; startup initialization gap tracked in `BACKLOG.md` |
| `paralife.tick.health.work-time-ms` | Most recently completed tick work time (ms) | `TickEngine.getLastTickWorkMs()` |
| `paralife.backpressure.stalled.sessions` | Count of sessions in STALLED grace by `source`[, `harness`] | `AdmissionMetrics.stalledBuckets`, maintained by handler lifecycle paths |

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
