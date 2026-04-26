# Phase 17: Durable Admission Control & Backpressure - Context

**Gathered:** 2026-04-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the temporary `paralife.websocket.max-active-entities=256` global cap (`WorldWebSocketHandler.handleRegister`) with a **durable, explainable admission policy** that survives the v3.0 milestone, plus explicit **overload / backpressure** behavior so slow or excess clients cannot drag tick health down or churn sessions silently. Closes SCALE-01 + SCALE-02 and supersedes the `999.1` backlog stopgap.

**In scope:**
- New `AdmissionConfig` namespace (`paralife.admission.*`) and admission decision surface
- Stable machine-readable rejection-token taxonomy on the wire
- Outbound async sender + per-session bounded queue + STALLED state handling
- Resume-token-based grace-window for stalled-session entity recovery
- Tick-health-aware admission gate
- Operator-visibility metrics, gauges, and log markers
- Migration off `PopulationCapConfig` and `paralife.websocket.max-active-entities`

**Not in scope (other phases):**
- Harness identity / source-tag (Phase 18, SCALE-03/04/05)
- Partition-aware world execution (Phase 19, SCALE-06/07)
- Connection multiplexing & runtime tuning (Phase 20, SCALE-08/09)
- Benchmark gate / scale reports (Phase 21, SCALE-10)
- `/actuator/prometheus` wiring (M5)
- Maintenance-mode actuator endpoint (M5)
- Bot-driven offspring agency / NPC-flower fallback (backlog 999.2)
- Per-spawn scoring / leaderboard via observer (M5)

</domain>

<decisions>
## Implementation Decisions

### Admission Policy Shape

- **D-01:** Single global cap. New `AdmissionConfig` (`@ConfigurationProperties(prefix = "paralife.admission")`) owns the durable cap. The cap counts cap-relevant occupants (today's "live non-rock / non-nutrient" set). Per-type quotas, hybrid bands, and source-tagging are all deferred — the single-counter design is chosen so each can be added cleanly later without breaking the wire vocabulary.
- **D-02:** In-sim reproduction stays exempt from the cap (status quo behavior). Admission gates **external load injection only** (`r|` register / respawn). World rules continue to govern in-sim spawn. **Forward note:** when bot-driven offspring (backlog `999.2`) lands post-MVP, those offspring will arrive over WebSocket via `r|` and will naturally fall under admission at that point. Implementation code SHOULD therefore stay neutral on the origin of the entity — the durable rule must be expressible identically for operator-bot, future-harness-bot, and future-bot-driven-offspring sessions.
- **D-03:** Phase 17 stays origin-blind. No source / operator / harness tag on `r|`. Phase 18 (SCALE-04) introduces harness identity and grows the admission counter with a `source` dimension. Today's single counter is intentionally easy to extend.

### Migration off the 999.1 Stopgap

- **D-04:** `PopulationCapConfig` and the `paralife.websocket.max-active-entities` config key are **deleted** (not aliased, not re-purposed). `WorldWebSocketHandlerPopulationCapTest` is rewritten against the new contract and renamed to match the new component (e.g. `AdmissionGateTest`). The backlog phase `999.1` closes as superseded by Phase 17 — its directory is left in place per backlog convention but the issue is documented as resolved.

### Rejection Vocabulary (SCALE-01)

- **D-05:** Wire reasons become **stable machine-readable tokens**. Error frame format on the wire stays `E|<code>[|<token>]`; the optional message slot is always a token in this taxonomy (no human suffix). Tests, bot clients, and operator tooling branch on the token. Free-text rejection messages are replaced everywhere they live today (`population cap exceeded`, `respawn cap exceeded`, `GRID_FULL`, `Malformed frame`, `already registered`, `no active entity`, `Client cannot send S` / `T`).
- **D-06:** **429 family + token discriminator** for admission-policy rejections. `503` is reserved for placement / "server cannot place this entity right now" failures. `4xx` semantics: client may try again or change request; admission decisions live here. Status quo `409` (already-registered) and `400` (codec) keep their codes — they are not admission decisions.
- **D-07:** Token taxonomy (initial set):

  | Code | Token | Cause |
  |------|-------|-------|
  | 400 | `malformed` | Codec / parse failure |
  | 404 | `no-active-entity` | Action frame on Unregistered session |
  | 408 | `reconnect-required` | Session was STALLED; client must drop and reconnect (D-11) |
  | 409 | `already-registered` | Second `r|` while session Alive |
  | 429 | `world-full` | Global admission cap reached (D-01) |
  | 429 | `respawn-cap` | Per-session respawn cap reached (existing `RespawnConfig`) |
  | 429 | `tick-overload` | Tick-health admission gate firing (D-14) |
  | 429 | `maintenance` | Operator maintenance flag set (D-16) |
  | 503 | `grid-full` | Placement RNG exhausted `MAX_PLACEMENT_ATTEMPTS` |

  Additional ingress-flood token reserved but not emitted this phase (D-09 chose counter-only, no kill).

- **D-08:** Token taxonomy is locked in a new spec doc `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` (authored during planning). `15-SCHEMA.md` gets a single-line cross-reference; `15-SCHEMA.md` is otherwise not mutated (Phase 15 was milestone-locked). Future phases that add admission-relevant tokens extend `17-ADMISSION.md`.

### Backpressure & Overload (SCALE-02)

- **D-09:** **Ingress flood policy: counter only, no kill.** `ActionResolver.pendingActions` is a `ConcurrentHashMap<sessionId, ActionFrame>` — last-write-wins already collapses any flood to one action per tick, protecting sim correctness. New per-session counter `paralife.admission.ingress.overwrites` (or aggregate; tag granularity = Claude's Discretion) gives operators visibility into misbehaving clients. No auto-disconnect this phase. A real flood-kill path can be added in Phase 18 if harness misconfig surfaces it.
- **D-10:** **Outbound async send via virtual-thread-per-session.** Each connected session is paired with one virtual thread that loops `queue.take(); session.sendMessage(...)`. Per-session bounded outbound queue. Tick broadcaster enqueues frames; the per-session VT drains. Rationale (REQUIRED to be documented in code comments on the new sender component AND added to `CLAUDE.md` under a new "Outbound concurrency" sub-section):
  - Matches Paralife's stated philosophy ("simple blocking code, virtual threads do concurrency"; `spring.threads.virtual.enabled: true`).
  - Per-session isolation is structural — one slow socket cannot block the tick thread or any other session.
  - `queue.size()` becomes the explicit backpressure signal — trivially observable as a gauge.
  - Java 21 VTs are cheap (few KB heap each, scheduled on shared carriers); 1000+ VTs is acceptable.
  - Considered alternative (Jetty native async write + write callbacks) was rejected because slow-client detection becomes implicit (write-Future latency / Jetty internals) and the API surface differs across Jetty 12 minor versions.
- **D-11:** Queue overflow → session transitions to **STALLED**. Server stops emitting tick frames to stalled session immediately. Any inbound frame from a stalled session is answered with `E|408|reconnect-required` and the WS connection is then closed. STALLED is a new FSM state alongside Unregistered / Alive / Dead. Transition source: outbound queue depth ≥ configured high-watermark over a configurable consecutive-tick window (defaults: Claude's Discretion / research-confirmed). Transition is one-way; recovery is reconnection-driven (D-13).
- **D-12:** **Entity grace window on stall.** When a session enters STALLED, its entity does NOT immediately leave the grid. The entity is held for a grace window (default suggested: ~10 ticks) bound to a server-issued resume token. If the client reconnects with a valid token before grace expiry, the new WebSocket session re-binds to the same entity (entityId preserved). If grace expires, the entity is reaped via the existing `cleanupBot` path and the resume-token entry is purged.
- **D-13:** **Resume-token wire shape.** First successful `r|` on a fresh session returns `S|<entityId>|<resumeToken>` where `<resumeToken>` is a server-generated opaque string (UUID, short hash, or `RandomGenerator.getDefault().nextLong()` hex — exact format = Claude's Discretion). The server holds a `(resumeToken → entityId, expiresAt)` map. On reconnect, the bot client may send `r|<type>|<resumeToken>`; if the token matches an unexpired entry, the new session re-binds to the existing entity. Missing or unrecognised token = fresh registration (back-compat preserved; older clients keep working). Wire delta is specified in `17-ADMISSION.md`; `15-SCHEMA.md` is referenced, not edited. **STALLED-pivot is orthogonal to the Phase 15.2 death-pivot:** death-pivot keeps the WS open and the client respawns into a *new* entityId; STALLED-pivot closes the WS and the client reconnects into the *same* entityId via resume token.
- **D-14:** **Tick-health admission gate.** Admission denies new `r|` with `E|429|tick-overload` when the rolling mean tick-work-time over the last `window-ticks` exceeds `high-water-pct` of the configured tick-interval budget; the gate clears once mean drops below `low-water-pct`. Hysteresis prevents flapping. Existing sessions are unaffected. Reuses the tick-work-time measurement contract established by Phase 16 D-11 (`TickEvent` dispatch start to final `@Order(100)` listener completion).
- **D-15:** Watermarks expressed as config under `paralife.admission.tick-overload.*` with sensible defaults (suggested: `high-water-pct=80`, `low-water-pct=60`, `window-ticks=10`; final values = Claude's Discretion / research-confirmed during planning). Tests and benchmarks override via `@TestPropertySource`. Constants in code are not used — tuning must live in config so Phase 21 benchmarks can sweep without recompiling.
- **D-16:** **Maintenance mode is a static config flag.** `paralife.admission.maintenance: true` denies all `r|` with `E|429|maintenance`. Existing sessions are unaffected. Restart is required to flip the flag this phase. An actuator-endpoint live-toggle is deferred to M5 (operator UX scope).

### Operator Visibility

- **D-17:** **Single tagged counter** for rejections: `paralife.admission.rejected{reason=<token>}`. Standard Micrometer / Prometheus idiom. Adding new reasons requires no bean change. Counter increments at the rejection-emission site in `WorldWebSocketHandler` (or a dedicated `AdmissionGate` bean — Claude's Discretion).
- **D-18:** Gauges exposed:
  - `paralife.admission.active.entities` — live count of cap-relevant occupants
  - `paralife.admission.maintenance` — 0/1 mirror of the config flag
  - `paralife.tick.health.work-time-ms` — last-tick wall-clock work time (drives D-14 gate)
  - `paralife.backpressure.stalled.sessions` — count of sessions currently in STALLED grace
- **D-19:** **Split log-marker prefixes**, single-line, low-cardinality, grep-friendly — same model as Phase 16 D-15 `EMERGENCE`. Examples:
  ```
  ADMISSION rejected tick=1234 session=abc reason=world-full active=256/256
  ADMISSION rejected tick=1235 session=def reason=tick-overload work-ms=420 budget=500
  ADMISSION maintenance state=on
  BACKPRESSURE stalled tick=1240 session=abc queue-depth=16 limit=16
  BACKPRESSURE resumed tick=1247 session=ghi entity=entity-old-r2 grace-remaining=4
  TICK-HEALTH degraded tick=1234 work-ms=420 high-water-pct=80
  TICK-HEALTH recovered tick=1260 work-ms=180 low-water-pct=60
  ```
  Operator cheat sheet: `grep -E 'ADMISSION|BACKPRESSURE|TICK-HEALTH' server.log`. Log channel pays forward to M5 visualizer / observer.
- **D-20:** `/actuator/prometheus` is **deferred to M5**. Phase 21 benchmark gate reads `/actuator/metrics/<name>` directly; counters and gauges are first-class on Micrometer today.

### Claude's Discretion

- Default cap value (suggested: keep 256 today; Phase 21 benchmarks will re-derive).
- Bounded outbound queue size per session (e.g. 16 frames; depends on tick interval and codec frame size).
- Grace-window default duration (~10 ticks suggested; balance of "tolerate a tab switch" vs "don't hoard reaper slots").
- Tick-overload watermark defaults (`high=80%`, `low=60%`, `window=10` suggested).
- `AdmissionConfig` decomposition (single record with nested sub-records vs split records like `AdmissionConfig`, `AdmissionTickHealthConfig`, `AdmissionBackpressureConfig`).
- Resume-token format (UUID vs short hex; aim for "opaque, unguessable, < 32 chars on wire").
- Whether `paralife.admission.ingress.overwrites` is per-session-tagged or aggregate.
- VT lifecycle hookpoints (spawn on `afterConnectionEstablished`; interrupt on `afterConnectionClosed` AND on STALLED transition).
- Where the resume-token map lives (new `ResumeTokenRegistry` bean vs extension of `BotRegistry`).
- `AdmissionGate` bean placement: `com.paralife.websocket` (cohesive with `WorldWebSocketHandler`) vs new `com.paralife.admission` package (cleaner namespace).
- Whether `RespawnConfig` folds into `AdmissionConfig` or stays as a sibling — fine either way; the durable contract just needs both to surface their cap rejections through the new token taxonomy.

### Folded Todos

None — `gsd-sdk query todo.match-phase 17` returned 0 matches.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & Requirements
- `.planning/ROADMAP.md` — Phase 17 entry (Goal, Depends on Phase 16, Requirements SCALE-01/02, Success Criteria)
- `.planning/REQUIREMENTS.md` — SCALE-01 (durable admission, explainable rejection reasons), SCALE-02 (overload/backpressure preserves tick health, no unbounded drift, no silent session churn)
- `.planning/PROJECT.md` — v3.0 / M4 active milestone, "durable admission control replaces temporary cap" framing

### Prior Phase Context (decisions this phase builds on)
- `.planning/phases/15-protocol-transport-overhaul/15-CONTEXT.md` — compact-text codec, raw `WebSocketHandler`, FSM (Unregistered / Alive / Dead), Micrometer metrics infra (D-10), keepalive ping cadence
- `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` — locked wire format; **read-only** for Phase 17 (Phase 15 is milestone-locked); new admission tokens live in `17-ADMISSION.md` and cross-reference back here
- `.planning/phases/15.2-own-death-event-wiring/SUMMARY.md` — death-pivot session-recovery flow; Phase 17 STALLED-pivot is orthogonal to this (different transitions, same FSM lives next to it)
- `.planning/phases/16-emergent-behavior-tests/16-CONTEXT.md` — D-11 tick-work-time measurement contract (reused by D-14 tick-health gate); D-14 Micrometer counter pattern (`paralife.<area>.<event>`); D-15 grep-friendly log-prefix style (mirrored by D-19 ADMISSION/BACKPRESSURE/TICK-HEALTH prefixes); D-21 PopulationHistory.autocorrelation (unrelated, but `EmergenceMetrics` bean is the closest existing analog for new admission/backpressure beans)
- `.planning/phases/13-energy-metabolism-system/13-CONTEXT.md` — STARVING flag, registries with stable IDs (relevant for future origin-tag work in Phase 18)

### Backlog / Superseded
- `.planning/phases/999.1-replace-temporary-websocket-caps-with-durable-registration-p/` — placeholder backlog phase; Phase 17 closes it as superseded
- `.planning/phases/999.2-offspring-entities-become-bot-driven-m5-flower-rendering-fal/` — context for D-02 forward-note; Phase 17 stays neutral on offspring origin

### Source Files (pattern-mapping inputs)
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — current admission decision site (`handleRegister` line 220+); FSM attribute keys; existing `markDead` pattern; rejection error-frame emission
- `src/main/java/com/paralife/websocket/PopulationCapConfig.java` — **to be deleted** in this phase
- `src/main/java/com/paralife/websocket/RespawnConfig.java` — per-session respawn cap; coexists or folds into `AdmissionConfig` (Claude's Discretion)
- `src/main/java/com/paralife/websocket/SessionRegistry.java` — session bookkeeping; STALLED state may live here or in a new `AdmissionState` map
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — current synchronous send path; outbound async refactor target (D-10 / D-11)
- `src/main/java/com/paralife/engine/ActionResolver.java` — `pendingActions` collapse (line 125, 262, 299); ingress-overwrite counter site (D-09)
- `src/main/java/com/paralife/engine/BotRegistry.java` — session→entity binding; resume-token re-bind site
- `src/main/java/com/paralife/engine/TickEngine.java` — tick-event dispatch + tick-work-time measurement (D-14 gauge source)
- `src/main/java/com/paralife/codec/Frame.java` — error-frame record; token taxonomy emitted via `Frame.ErrorFrame(code, Optional<token>)`
- `src/main/java/com/paralife/codec/PerceptionCodec.java` — wire encoding; tokens flow through unchanged (codec already supports the slot)
- `src/main/java/com/paralife/bot/BotClient.java` — bot-side mirror: handle `E|408|reconnect-required`, store resume token, reconnect with token

### Tests (rewrite / extend targets)
- `src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java` — **rewrite** as `AdmissionGateTest` against the new contract; existing `paralife.websocket.max-active-entities=1` property usage moves to `paralife.admission.cap=1`
- `src/test/java/com/paralife/engine/LoadTest.java` — references the old key `paralife.websocket.max-active-entities=1000000`; migrate to `paralife.admission.cap=1000000`
- New tests required: tick-health gate (hysteresis, watermark crossing), STALLED transition + grace + resume-token re-bind, async sender VT lifecycle, log-marker emission, counter+gauge wiring, maintenance-mode flag

### Build + Config
- `src/main/resources/application.yml` — section `paralife.websocket.max-active-entities` removed; new section `paralife.admission.*` added (cap, maintenance, tick-overload.high-water-pct / low-water-pct / window-ticks, backpressure.outbound-queue-size, backpressure.grace-window-ticks)
- `build.gradle.kts` — no new dependencies expected (Micrometer + Jetty WebSocket already present); confirm during research

### To Be Authored This Phase
- `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` — token taxonomy, resume-token wire shape, FSM diagram including STALLED, log-marker reference; cross-references back to `15-SCHEMA.md` §Error and §Sync frames
- `CLAUDE.md` "Outbound concurrency" sub-section documenting the VT-per-session rationale (D-10)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **Micrometer `MeterRegistry`** + Phase 15 D-10 `WebSocketMetrics` + Phase 16 D-14 `EmergenceMetrics` patterns — drop-in pattern for new `AdmissionMetrics` / `BackpressureMetrics` beans
- **Per-session FSM** (Unregistered / Alive / Dead via session attributes `entityId`, `entityType`, `respawnCount`) — extends naturally to STALLED; a fourth attribute key (e.g. `stallTick`) cleanly captures grace-window expiry
- **`markDead` pattern** in `WorldWebSocketHandler` — pivot template for new `markStalled(session)` (clears entityId from attributes, but with grace-window semantics rather than respawn semantics)
- **`ConcurrentHashMap` last-write-wins collapse** in `ActionResolver.pendingActions` — already protects sim from inbound floods; D-09 just adds an overwrite counter on top
- **`@ConfigurationProperties` record + `@ConstructorBinding` validation** pattern (`PopulationCapConfig`, `RespawnConfig`, `SimulationConfig`, `GridConfig`) — directly applicable to `AdmissionConfig`
- **Spring virtual-thread auto-config** (`spring.threads.virtual.enabled: true`) — already enabled; per-session sender VTs participate without extra config
- **`@EventListener(@Order)`** tick pipeline — admission gate reads tick-health snapshot during `handleRegister` (no new listener required); the gauge feeder may be a small `@Order(100+epsilon)` listener that records `lastTickWorkMs`

### Established Patterns
- Records + sealed interfaces for data modelling (`Frame.ErrorFrame(int code, Optional<String> message)` slot already supports tokens — wire change is purely the *content*, not the *shape*)
- Per-component RNG with optional seeding (resume-token generator can follow this if reproducibility ever matters; not load-bearing for Phase 17)
- Single-threaded simulation core; mutations only inside tick listeners — admission decisions read-only on the tick path; STALLED transition + grace expiry happen in tick listeners (not from broadcaster threads)
- Raw `WebSocketHandler` (no STOMP) — full protocol control, error frames are first-class

### Integration Points
- **Admission decision site** — `WorldWebSocketHandler.handleRegister` line 220+ today. Hard rules (cap, respawn-cap, tick-health, maintenance, resume-token re-bind) all flow through here, or through a new dedicated `AdmissionGate` bean it delegates to
- **Outbound async refactor** — `TickBroadcaster.sendToSession` (or wherever the current `synchronized(session) { sendMessage }` lives) is replaced by `outboundQueue.offer(frame)`; per-session VT consumes the queue
- **VT lifecycle** — spawn on `afterConnectionEstablished`; interrupt on `afterConnectionClosed`; also on STALLED transition (or let the VT detect the queue's poison-pill / closed state and exit)
- **Resume-token registry** — new bean (`ResumeTokenRegistry`) holding `Map<String, ResumeEntry>` with grace-window expiry sweep on tick. Lives in `com.paralife.websocket` or a new `com.paralife.admission` package (Claude's Discretion)
- **Tick-health gauge** — small bean subscribed to `TickEvent` at `@Order(Integer.MAX_VALUE)` (or equivalent post-broadcast) that records last-tick wall-clock work-ms into a rolling window; `AdmissionGate` reads the rolling mean
- **Bot-side mirror** — `BotClient` learns `E|408|reconnect-required`: drops connection, retains resume token, reconnects fresh, sends `r|<type>|<resumeToken>`. Existing reconnect+respawn logic from Phase 15.2 is the closest pattern but is NOT the same flow (death vs stall)

### Known Debt to Resolve This Phase
- **`PopulationCapConfig` deletion** — touches `WorldWebSocketHandler` constructor signatures (incl. back-compat 6-arg / 7-arg ctors lines 125–152), `application.yml`, `WorldWebSocketHandlerPopulationCapTest`, `LoadTest`. Plan must enumerate every callsite.
- **`paralife.websocket.max-active-entities` config key** — search-and-replace across `src/`, `application.yml`, all `@TestPropertySource` literals
- **Free-text rejection messages** — every `new Frame.ErrorFrame(<code>, Optional.of("<free text>"))` callsite in `WorldWebSocketHandler` (lines 181, 189, 191, 226, 231, 240, 281, 303) gets retokened per D-07
- **`TickBroadcaster` synchronous send model** — refactored end-to-end; existing tests that assert "frame received in tick N" must tolerate the queue's <1-tick latency

</code_context>

<specifics>
## Specific Ideas

- **STALLED-pivot vs death-pivot are orthogonal recovery paths.** Phase 15.2 owns death-pivot: WS stays open, server emits `D`, client sends `r|` for a *new* entityId. Phase 17's STALLED-pivot: WS closes, entity is grace-held, client reconnects on a *new* WS and sends `r|<type>|<resumeToken>` to re-bind to the *same* entityId. Token-vs-no-token on `r|` is the wire signal that distinguishes "fresh registration" from "stalled-recovery". This dual-pivot design must be diagrammed in `17-ADMISSION.md`.

- **Single-counter cap chosen with extension axes in mind.** Per-type quotas (CAT/MEM/SPORE), source-tagging (operator/harness), and bot-driven-offspring inclusion are all foreseeable extensions. The single-counter design is intentionally easy to grow into any of those without breaking the wire vocabulary or the `AdmissionConfig` decomposition.

- **VT-per-session is the idiomatic Paralife pattern.** Documented rationale (D-10) is required to live in two places: (a) Javadoc on the new sender component, and (b) a new "Outbound concurrency" sub-section in `CLAUDE.md`. This is to prevent future drift toward Jetty native async (which would lose the per-session-isolation property).

- **Grep-friendly log channel pays forward to M5.** The `ADMISSION` / `BACKPRESSURE` / `TICK-HEALTH` prefixes mirror Phase 16's `EMERGENCE` channel deliberately. M5's observer-endpoint can consume the same log feed without redesigning the emission point.

- **Forward-note for 999.2.** The exemption in D-02 ("in-sim reproduction stays exempt") is a *temporary* property of the durable rule. When backlog `999.2` (bot-driven offspring) lands, those offspring will register over WebSocket via `r|` and will naturally fall under admission. Code MUST stay neutral on the origin of the entity so the exemption disappears cleanly the day offspring become bot-driven, with no special-casing.

- **Resume-token grace-window doubles as a network-hiccup cushion.** Even outside the slow-consumer scenario, a brief network blip that closes the WS will now leave the entity recoverable for ~10 ticks instead of forcing a fresh respawn. This is a quality-of-life improvement for `BotRunner` operators and a robustness improvement for Phase 18 harness traffic.

</specifics>

<deferred>
## Deferred Ideas

### Backlog (already on the roadmap)
- **`999.2` Bot-driven offspring + M5 flower-rendering fallback** — Phase 17 admission code shape stays neutral on offspring origin so this lands cleanly without re-spec.
- **`999.1` Replace temporary WebSocket caps** — closes as superseded by Phase 17 (D-04).

### Pulled to other phases in this milestone
- **Source/origin tag on `r|` (operator vs harness)** — Phase 18 (SCALE-04) introduces harness identity. Today's single-counter admission grows a `source` dimension cleanly later.
- **Reserved operator slots** — defers with harness identity.
- **`/actuator/prometheus` wiring** — Phase 21 benchmark gate scrapes `/actuator/metrics/<name>` directly; full Prometheus surface deferred.
- **Per-type quotas (CAT/MEM/SPORE)** — single-counter design admits trivial extension; no policy commitment until benchmark evidence (Phase 21) demonstrates need.

### Pulled to M5 (Observability & Operations)
- **Live maintenance-mode actuator endpoint** (`POST /actuator/admission/maintenance`) — config-flag-only this phase.
- **Operator dashboard** consuming `paralife.admission.*` and `paralife.tick.health.*` metrics — Phase 17 exposes the counters/gauges; M5 builds the surface.
- **Subjective scoring/leaderboard for successful spawns** — surfaced via M5 SSE/human-observer phase. Idea logged for that milestone's discuss-phase.

### Considered and rejected
- **Hard ingress rate-limit kill** (D-09 alternative) — sim correctness already protected by `pendingActions` collapse; CPU/IO cost handled by Phase 18 harness identity if it surfaces.
- **Drop-frame-silently outbound policy** (D-10 alternative) — fails SCALE-02 "no silent session churn" phrasing; STALLED-with-resume-token gives the same lossy-recovery affordance with explicit signal.
- **Synchronous send with timeout** (D-10 alternative) — one stuck socket would still hold the tick thread; doesn't solve the problem.
- **Counter-per-reason** (D-17 alternative) — bean churn for every new token; tagged counter is the standard Micrometer/Prometheus shape.

### Reviewed Todos (not folded)
None — `gsd-sdk query todo.match-phase 17` returned 0 matches.

</deferred>

---

*Phase: 17-durable-admission-control-backpressure*
*Context gathered: 2026-04-27*
*Closes SCALE-01 + SCALE-02; supersedes backlog 999.1.*
