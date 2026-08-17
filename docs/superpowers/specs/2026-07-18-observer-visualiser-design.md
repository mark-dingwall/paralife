# Observer Visualiser — Design

**Milestone:** M5 Observability & Operations · sub-project **A** (of A/B/C — see Scope)
**Date:** 2026-07-18
**Status:** Design — awaiting spec review before planning
**Readiness:** **GO-WITH-CAVEATS** (mechanism-heavy, emergence display-only; two integration blockers
and a set of correctness fixes folded in after codex review 2026-07-18 — see Readiness)

---

## Why

The gated work cluster — the balance-tuning campaign and the Population Viability phase — is blocked
on "eyes on spatial emergence." A 1-D scalar can Goodhart-drive the spiral-wave / oscillation /
niche-formation behaviour that is the Core Value, so tuning wants a human looking at the actual
spatial state. Nothing renders the world today: bots receive only 5×5 vision-scoped frames. This
sub-project delivers the missing god's-eye view — a live, operator-deployable full-world visualiser —
which is the single unblocker that turns the 🚫 rows in `ROADMAP.md` green.

## Goal & success criterion

A human opens a browser page and watches the **entire** world evolve in real time: species
positions, environment fields, emergent structures, and a population time-series. Success = the
spatial-emergence signals (spiral waves, population oscillation, niche formation) are visible by eye,
well enough to judge a tuning change. The tool ships **inside the Spring app** (no build toolchain) and
is **operator-deployable** — enabled by flag, session-capped — but is **not** a hardened public
endpoint in this slice: it exposes full-world state that the bot path deliberately vision-scopes, so
authenticated/public exposure is a separate named hardening slice (see §1 H4, Backlog).

## Scope

M5-the-milestone is three loosely-coupled pieces. **This design is piece A only.**

| Piece | In this spec? | Why |
|-------|---------------|-----|
| **A. Live world visualiser** | ✅ | the unblocker for the gated cluster |
| B. Ops dashboards (admission/tick/WS health) | ❌ later | doesn't unblock the cluster; actuator/Micrometer already emit most of it |
| C. Tick-drift regression tripwire | ❌ later | separate perf gate; needs a stable capacity rig |

### Non-Goals (backlog-defer)

- **Replayable capture / A-B replay** → BACKLOG (piece A ships live-only; capture is a follow-on).
- **Headless spatial invariant** → dropped for now (user chose the human-watch path).
- **Live tuning knobs/dials** → layout reserves space; the controls themselves are a later slice.
- **Precompress-once fan-out** (`BroadcastChannel` / `CompressedFrame`) → already a BACKLOG M005 item;
  only needed at hundreds of observers. MVP does single-serialize + per-connection deflate.
- **Ops dashboards & tick-drift tripwire** → M5 pieces B and C.

---

## Architecture

```
SimulationEngine tick ──▶ ObserverBroadcaster  @Order (after TickBroadcaster @Order(50))
                          │  ON THE TICK THREAD, bounded work ONLY:
                          │  1. capture ONE immutable WorldSnapshot (grid via WorldGrid.snapshot(),
                          │     env values copied, ownership set copied, counters read)
                          │  2. Jackson → ONE JSON String   (single application-payload serialize)
                          │  3. non-blocking offer(payload) to each observer's capacity-1 mailbox
                          ▼          (NEVER calls session.sendMessage on the tick thread)
                   ObserverOutboundSender — one drain virtual-thread per observer
                          │  latest-wins slot; synchronized(session) send;
                          │  overwrite pending frame on lag; close ONLY on transport error / shutdown
                          ▼
                   /ws/observer  ── ObserverWebSocketHandler (raw WebSocketHandler)
                          │         read-only · no admission FSM · no vision-scoping
                          │         handshake: EXEMPT from the deflate-enforcement filter (C1)
                          ▼  each drain VT sends the shared payload to its own session
                   observer.html  (src/main/resources/static/, vanilla JS)
                          │  JSON.parse
                          ├─ <canvas> full-grid render (glyphs per kind/species/brained)
                          ├─ <canvas> population time-series (client-side history)
                          ├─ scoreboard panel (cumulative per-species spawns)
                          └─ reserved area for future knobs/dials
```

**Decisions (locked in brainstorming, revised after codex review 2026-07-18):**

- **Transport** — a **new `/ws/observer`** raw-`WebSocketHandler`, wholly separate from `/ws/world`.
  An observer never registers an entity, never consumes an admission slot, never stalls/resumes. This
  keeps the bot admission / backpressure / resume-token FSM and its zero-trust invariants untouched.
  **Handshake (C1):** the existing `DeflateEnforcementFilter` (`JettyDeflateCustomizer`, URL pattern
  `/*`) rejects any upgrade whose `Sec-WebSocket-Extensions` offer lacks `permessage-deflate;
  server_no_context_takeover` with HTTP 400. Bot clients advertise that explicitly
  (`BotClient.java:226`); **a browser cannot** — it generates the extensions header itself and does
  not offer `server_no_context_takeover`. So `/ws/observer` **must be exempted** from that filter
  (scope the filter to `/ws/world`, or skip enforcement for the observer path). Verified by an
  end-to-end handshake test using a browser-equivalent offer, not a mocked handler.
- **Delivery — off the tick thread (C2).** Spring publishes `TickEvent` **synchronously** on the
  tick-engine virtual thread (`TickEngine.java:114`; `lastTickWorkMs` is written only *after*
  `publishEvent` returns, so `TickHealthMonitor` sees overrun a tick late). Therefore the observer's
  `@Order` listener does **bounded work only**: capture an immutable snapshot, serialize once, and
  **non-blocking `offer`** the payload to each observer's mailbox. It **never** calls
  `session.sendMessage` on the tick thread — a single blocked socket would otherwise add unbounded
  latency to tick work and eventually trip the very tick-overload admission gate this project guards.
  This mirrors the **real** `TickBroadcaster` pattern: it builds each bot frame on the tick thread but
  hands off via `OutboundSender.offer` (`TickBroadcaster.java:226`) to a per-session drain VT — it does
  **not** write synchronously. A new **`ObserverOutboundSender`** provides the observer analog: one
  drain virtual-thread per observer + a **single latest-wins slot** (`AtomicReference<String>.getAndSet`
  + a wake signal), `synchronized(session)` around the actual send. **Overflow policy — one, unambiguous
  (M-drop):** a full slot is **overwritten** (latest-wins); a lagging tab simply shows the newest world,
  it is **not** disconnected for lag. The observer is closed **only** on transport error / handler
  close / server shutdown — that close path removes it from the registry, interrupts the drain VT, and
  discards the slot. (There is no separate "overflow-drop" disconnect — the earlier draft's mention of
  one was self-contradictory with latest-wins and is removed.) Observers do **not** route through the
  bot STALLED/resume FSM.
- **Bootstrap ordering (bootstrap-barrier).** The once-per-connection **bootstrap** frame (static
  rocks + grid dims + `schemaVersion`) is delivery-critical: if a world frame reaches a new observer
  first, or overwrites a pending bootstrap in the latest-wins slot, that observer **permanently** lacks
  terrain. So bootstrap is **not** droppable and is ordered strictly first: the connection sequence is
  *attach sender → send bootstrap under `synchronized(session)` → only then publish the observer to
  `ObserverBroadcaster`'s registry*. A tick cannot offer a world frame to an observer that isn't yet in
  the registry, so bootstrap always precedes the first world frame. (O-clause O8 pins this; a
  concurrency test stalls the drain, fires a tick mid-connect, and proves bootstrap-first.)
- **Single-serialize, N compressions.** The snapshot is serialized to **one** immutable JSON String
  per tick (shared across all observers — "identical bytes" = identical *application payload*).
  permessage-deflate then runs **per connection** — each session owns its own deflate context and
  produces its wire bytes independently. Thread placement: the **one Jackson encode runs on the tick
  thread** (inside the `@Order` listener, per C2's snapshot-and-serialize step); the **N deflations and
  N writes run off-thread** in the per-observer drain VTs. **No context-takeover claim:** `/ws/observer`
  is exempt from the
  enforcement filter, so the browser negotiates whatever `permessage-deflate` variant it offers —
  `server_no_context_takeover` is **not** guaranteed present, and the spec makes no per-message-reset
  claim. The single-serialize win (one Jackson encode shared) survives regardless. App-level
  precompress-once (one shared compressed frame) remains the deferred BACKLOG lever for hundreds of
  observers; it is **not** needed for correctness.
- **Format** — **JSON** via Jackson (already on the classpath for actuator). Not a perception frame,
  not hot-path — consistent with the ethos ("compact-text on the hot path; Jackson for JSON, not
  perception frames").
- **UI** — a single **static `observer.html` + `<canvas>` + vanilla JS** under
  `src/main/resources/static/`. No npm/build pipeline; ships in the app; deployable as-is.

**Sizing.** Grid 256×256, tick interval 500 ms → **2 ticks/sec**. Typical sparse (occupied-cells-only)
frames, deflated, are tens of KB. **Worst case is not free (H6):** a saturated toxin *or* mutagen field
is up to 65 536 non-zero cells ≈ ~2 MB raw per layer before deflate, and **rocks are static yet the
naïve design retransmits them every tick.** Mitigations folded into this slice: **send static terrain
(rocks) once at connection bootstrap**, not per tick; latest-wins slot (a slow observer drops frames,
never accumulates). **Scope of the off-thread guarantee:** off-thread delivery removes *network write*
time from the tick — **socket writes never stall the tick**. But snapshot capture, sparse-entry
allocation, and the single Jackson encode still run **synchronously on the tick thread** inside
`publishEvent`, so a multi-MB worst-case frame can still add encode/alloc latency to tick work (and both
toxin *and* mutagen can saturate in the same tick, plus a dense entity layer). Therefore planning
**must** measure capture+encode latency on a both-layers-saturated + dense-occupant fixture against the
400 ms overload watermark as a **GO-on-scale gate**; the defined fallback if it exceeds budget is to
move the single serialize behind its own capacity-1 encoder VT (snapshot handed off unencoded, encoded
once off-thread, then fanned out). MVP ships the on-thread encode; the gate decides if that holds.

---

## Observer world-frame (JSON)

**Style rule:** full-word camelCase keys; `x`/`y` for coordinates (universal convention, kept);
enum values spelled out (`CATALYST`, not `C`); sparse layers as arrays of full-key objects, not
positional tuples — deflate makes the verbosity free.

Two frame types share the style: a **bootstrap** frame (sent once on connect — static terrain +
schema/grid dims) and the per-tick **world** frame (everything dynamic).

```json
// bootstrap (once, on connect) — static terrain that never changes tick-to-tick (H6)
{ "type":"bootstrap", "schemaVersion":1, "grid":{ "width":256, "height":256 },
  "rocks":[ { "x":7, "y":9 } ] }

// world (per tick)
{
  "type":"world", "schemaVersion":1, "tick":12345,
  "entities": [
    { "x":12, "y":34, "kind":"particle", "species":"CATALYST", "energy":45, "brained":true  },
    { "x":50, "y":10, "kind":"particle", "species":"SPORE",    "energy":30, "brained":false },
    { "x":3,  "y":4,  "kind":"nutrient", "energy":20 },
    { "x":20, "y":21, "kind":"bondedPair", "primarySpecies":"CATALYST", "secondarySpecies":"SPORE",
      "energy":60, "brained":true },
    { "x":40, "y":41, "kind":"compositeMember", "species":"MEMBRANE", "compositeId":"c-7",
      "role":"FEEDER", "energy":33, "brained":true }
  ],
  "env": {
    "toxin":     [ { "x":11, "y":12, "intensity":180 } ],
    "mutagen":   [ { "x":30, "y":31, "strain":42 } ],
    "lightning": [ { "x":64, "y":70 } ]
  },
  "scoreboard":  { "CATALYST":123, "MEMBRANE":98, "SPORE":140 },
  "populations": { "CATALYST":9, "MEMBRANE":7, "SPORE":11 }
}
```

- `entities` carries every live **dynamic** occupant (particle / nutrient / bondedPair /
  compositeMember). **Rocks are NOT here** — they are static, sent once in `bootstrap` (H6).
- **Subtype fields (H1)** — the sealed `Entity` subtypes do not share one `species`:
  `BondedPair` has `primaryType`/`secondaryType` (`Entity.java:174`) → emit **`primarySpecies`** +
  **`secondarySpecies`**; `CompositeMember` has a `compositeId` + `role` → emit both, plus its
  `species`. `role` is the exact `Entity.Role` enum name — one of `LOCOMOTOR`/`FEEDER`/`ATTACKER`/
  `DEFENDER`/`REPRODUCER`/`SENSOR` (`Entity.java:29`), **not** an invented value. The render colours a
  pair by both species and groups composite members by `compositeId`.
- **`brained` (H2)** — present on **every controllable kind** (particle, bondedPair, compositeMember),
  computed from the captured ownership set (see Brained classification). `BotRegistry.remapEntity`
  (`BotRegistry.java:166`) **preserves** the controlling session when a particle fuses into a
  bondedPair/composite, so those structures are frequently `brained:true`; emitting `brained` only on
  particles would misclassify controlled organisms as autonomous. **"Flower"** is the render name for
  an **unbrained solo particle only** (`kind:"particle"`, `brained:false`).
- **`env.toxin`** lists non-zero cells with an **`intensity`** 1–255 (effect strength;
  `toxinIntensityAt` is a real magnitude). **`env.mutagen`** lists non-zero cells with a **`strain`**
  1–255 — a **strain identifier, not a magnitude** (`EnvironmentEngine:166`, `0=clean`). The render
  MUST draw mutagen as **categorical/uniform zones** (colour ≠ heat by number), or a heatmap would make
  arbitrary strain IDs read as stronger/weaker exposure. Toxin may render as a true heat gradient.
- `env.lightning` lists strikes for **this tick only** (transient; empty on non-strike ticks) — an
  env-state layer alongside toxin/mutagen.
- `scoreboard` = cumulative committed spawns per species (new counter). `populations` = per-species
  **occupancy census** (see rule below); the browser accumulates it into the time-series
  (**client-side history — server keeps none**).

**Population census rule (H3, H5).** `populations` is undefined without an explicit transformation, and
the existing project census (`PopulationHistory`) counts a bonded pair toward **both** its species and
applies **no liveness filter**. This is an **occupancy** census, not a "live" one: `CompositeEnergyDistributor`
`@Order(15)` can drain a member to zero energy *after* `SimulationEngine`'s death sweep, so a frame at
`@Order` 50 can legitimately contain a zero-energy `compositeMember` awaiting next-tick cleanup —
`entities` and `populations` **include** it (matching `PopulationHistory`). Pin the exact rule, reused
for frame production and tooling: **particle → +1 to its species; bondedPair → +1 to primarySpecies AND
+1 to secondarySpecies; compositeMember → +1 to its species; rock/nutrient excluded; no energy filter.**
Derived from the same immutable snapshot as `entities` (no second read). O7b seeds a zero-energy member
so this is armed.

---

## Server components

### 1. `ObserverWebSocketHandler` (new) — `/ws/observer`

Raw `WebSocketHandler`. Tracks open observer sessions in a **`ConcurrentHashMap.newKeySet()`** (M4) —
connection open/close callbacks run on Jetty threads while the tick thread iterates observers, so a
plain `HashSet` would risk `ConcurrentModificationException` / lost removals. On open: attach sender →
send **bootstrap** → publish to the broadcaster registry (bootstrap-barrier order, above). Removes it
idempotently on close/error. Inbound frames ignored (read-only). No admission, no vision-scoping, no
resume/stall. Registered in `WebSocketConfig`.

**Enablement + cap (H4, minimal gate this slice).** `paralife.observer.enabled` defaults **false**.
`paralife.observer.max-sessions` caps concurrent observers, enforced with an **atomic permit** so the
cap is never exceeded under concurrent handshakes: a plain `size() < max; add()` is a check-then-act
race (three simultaneous handshakes all pass `size()==max-1`). Use a **`Semaphore(maxSessions)`
`tryAcquire`** in a **`HandshakeInterceptor`** (pre-upgrade — `afterConnectionEstablished` is
*post-upgrade* and cannot refuse the HTTP handshake). When disabled or at cap, the handshake is refused
(503/close). **Release exactly once (release-once lease):** `handleTransportError` and
`afterConnectionClosed` both fire for a single failed connection (`WorldWebSocketHandler.java:397,446`),
so releasing on "every close/error path" would **double-release** and inflate the semaphore above
`maxSessions` — bypassing the cap. Guard release with a remove-once marker (an `AtomicBoolean.compareAndSet`
or an atomic session-attribute removal), exactly as the existing bot cleanup gates `releaseSlot()` on
`attrs.remove(ATTR_ENTITY_TYPE) != null` (`WorldWebSocketHandler.java:972,995`). Pin it (O-clause O9):
an error→close sequence and a duplicate close both leave `availablePermits()` correct, never exceeding
`maxSessions`. Real auth / non-wildcard origin policy / rate-limiting are deferred to a **named later
hardening slice** (BACKLOG). This closes the uncapped-exhaustion and always-on holes without Spring
Security now.

### 2. `ObserverBroadcaster` (new) — tick `@Order` after `TickBroadcaster`

On each `TickEvent`, **on the tick thread, bounded work only**: (a) capture one immutable
`WorldSnapshot` — occupants via **`WorldGrid.snapshot()`** (the only whole-grid read that holds the
read lock across the full copy; a per-cell `getCell` loop does **not** give an atomic snapshot),
env non-zero values **copied by value** into the snapshot (never a reference into the mutable shadow
arrays), the **brained ownership set** copied from `BotRegistry`, the spawn counter, and this tick's
lightning list; (b) serialize to **one** JSON String; (c) **non-blocking `offer`** to each observer's
mailbox via `ObserverOutboundSender`. Zero observers → cheap early-out. **Never** calls
`session.sendMessage` here. Per-frame/per-session failures are caught inside the component so one bad
session cannot abort the fan-out or the tick.

### 2a. `ObserverOutboundSender` (new) — off-thread delivery (C2)

The observer analog of `OutboundSender`, **not** routed through the bot STALLED/resume FSM. Per
observer: one drain virtual-thread + a **capacity-1 latest-wins** mailbox. `offer` from the tick thread
is non-blocking and overwrites any unsent frame (a slow tab shows the newest world, never a backlog).
The drain VT does the `synchronized(session)` `sendMessage` (single-writer contract) and the
per-connection deflate. Close path (transport error / handler close / shutdown — **not** lag): remove
from the registry, close the transport to unblock any in-flight Jetty write, interrupt the drain VT,
discard the slot — so the tick thread never waits on a send, join, or slot space.

### 3. `EnvironmentSnapshot` API (new, M1)

The broadcaster cannot read env state today: the toxin/mutagen shadow arrays are private, mutagen has
only a **package-private test accessor**, and the published status cache carries presence **bits**, not
raw values. Add a production `EnvironmentEngine.snapshot()` returning an immutable value — sparse
non-zero **toxin `intensity`** entries (a real magnitude) + sparse non-zero **mutagen `strain`** entries
(a **strain identifier**, 1–255, `0=clean` — NOT a magnitude) + this tick's lightning coords —
published from one tick-owned read. This is the safe source for the `env` and `lightning` frame layers.
Note the two layers carry semantically different values (intensity vs strain id), reflected in the frame
keys and the render rules (H — mutagen renders categorical, not as a heat gradient).

### 4. Per-tick lightning-strike surface (M5)

`EnvironmentEngine.lightningStrikeCount()` is cumulative and increments on the **attempted** strike
(before `applyLightningAtInternal`). Add a per-tick list of strike coordinates for the frame with an
**explicit semantic: successfully-applied strikes** (append at the apply site, not the attempt site),
**cleared at the start of `EnvironmentEngine.onTick`**, published immutable via the `EnvironmentSnapshot`.
Tests exercise multiple coordinates even though current generation yields at most one, so the surface
stays honest.

### 5. Per-species spawn counter (new, M2)

No cumulative per-species spawn counter exists today. A **spawn** is a **committed biological
birth/admission** — increment **only after successful placement/registration**, using an **atomic**
per-species counter (`AtomicLongArray` or per-species `AtomicLong`; admission runs on WebSocket threads,
reproduction on the tick thread, so a plain `long` map would lose increments). Increment sites, each
guarded on success:

- successful fresh admission / respawn — `WorldWebSocketHandler` (after `trySetEntity` succeeds, ~`:631`)
- primary offspring + bonus offspring — `ActionResolver` (~`:674`, `:692`)
- composite reproducer bud — `ActionResolver` (~`:936`)

**Explicitly excluded:** immutable `Particle` record replacements (energy updates construct new
records), composite-dissolution particle creation (`SimulationEngine` ~`:1349` — a transition, not a
birth), and any construction **before** a failed `grid-full` placement. Reset semantics: process
lifetime. Exposed as an immutable snapshot for the broadcaster.

### Brained classification (H2)

`Particle` carries no owner field (its "owner ID" Javadoc is stale). Ownership is in
`BotRegistry.entityToSession`, queried via `getSessionForEntity(entityId)`. The broadcaster copies the
**ownership set into the immutable snapshot** on the tick thread and classifies from that copy — it does
**not** query the live registry during later serialization. Present → `brained:true`, absent →
`brained:false`. Applies to particles **and** structures (`remapEntity` keeps the session across bond
formation). **Registration-race caveat (verified):** fresh registration places the particle in
`WorldGrid` (~`:631`) but publishes the `BotRegistry` mapping only later (~`:670`), and these run on a
WebSocket thread not covered by the grid lock — so an observer can briefly see a just-placed particle as
`brained:false`. Acceptable for a human-watch tool (self-corrects next tick); documented as the
consistency bound rather than hidden (see Assumptions).

---

## UI — render layers & rules

`observer.html`: a main `<canvas>` for the grid, a small second `<canvas>` for the time-series, a
scoreboard panel, and a reserved (empty) area for future knobs/dials. A tiny WS client parses each
frame and repaints.

| Layer | Render rule |
|-------|-------------|
| Species | cell colour by `species` (Catalyst / Membrane / Spore) |
| Brained vs flower | distinct glyph/marker for `brained:false` particles |
| Rocks / nutrients | static glyphs distinct from particles |
| Emergent structures | bondedPair / compositeMember drawn distinctly (marker or outline) |
| Env zones | toxin as a heat gradient keyed to `intensity`; mutagen as **categorical** zones keyed to `strain` (never a heat ramp — strain is an id, not a magnitude) |
| Lightning | transient flash at strike coords for the frame it appears in |
| Population time-series | line-per-species chart, appended client-side each tick |
| Scoreboard | cumulative per-species spawn totals, text panel |

Render fidelity is judged **by eye** — this is a human-watch tool. No pixel-level test is specified.

---

## EARS mechanism clauses & test plan (RED/GREEN)

The **frame contract**, the delivery mechanism, and the new counters are mechanism → default-suite TDD,
pinned to the contract (not to emergent magnitudes). One clause = one RED assertion; the canvas render
is judged by eye + a real browser smoke test (below). Clauses were split per review (M3) so each pins a
single mechanism.

**Delivery / lifecycle:**
- **O1** — WHEN a tick completes AND ≥1 observer mailbox is open THE SYSTEM SHALL **offer** exactly one
  frame to each open observer mailbox (a non-blocking offer, **not** a guaranteed network send —
  droppable delivery cannot promise one send per tick). *(control: zero observers → no offer, no error.)*
- **O1b** — WHEN an observer's mailbox already holds an unsent frame THE SYSTEM SHALL overwrite it
  (latest-wins) and SHALL NOT block the tick thread. *(positive control: a deliberately stalled drain
  VT — prove the tick thread's offer returns promptly and the newest frame wins.)*
- **O1c** — WHEN an observer session **closes, errors, or the server shuts down** (NOT on lag — a
  lagging observer is kept, latest-wins) THE SYSTEM SHALL remove it from the registry idempotently and
  interrupt its drain VT (no leaked thread/session). *(control: a still-open, lagging observer remains
  registered and keeps receiving the newest frame.)*
- **O8** — WHEN an observer connects THE SYSTEM SHALL deliver the **bootstrap** frame before any world
  frame, and the bootstrap SHALL NOT be overwritten by a concurrent tick. *(armed: stall the drain,
  fire a tick mid-connect, prove bootstrap is received first and carries all rocks + grid dims +
  `schemaVersion`. This pins the attach→bootstrap→publish ordering.)*
- **O9** — WHEN a single observer connection fails such that BOTH `handleTransportError` and
  `afterConnectionClosed` fire THE SYSTEM SHALL release its session permit **exactly once**.
  *(armed: drive error→close and a duplicate close; assert `availablePermits()` returns to baseline and
  never exceeds `maxSessions`. Positive control: a normal single close also releases exactly one. This
  pins the release-once lease so the cap can't be inflated.)*
- **O2a** — WHEN a frame is built for ≥1 observer THE SYSTEM SHALL invoke the JSON serializer **exactly
  once** regardless of observer count. *(injected serializer seam/spy counts calls == 1 for N
  observers; RED if a per-observer serialize sneaks in.)*
- **O2b** — WHEN ≥2 observers receive that frame THE SYSTEM SHALL deliver the **same application
  payload** to each. *(precondition control: assert each observer received one non-empty frame before
  comparing — byte-equality of two empty captures is vacuous.)*

**Projection (each a separate clause, M3):**
- **O3** — WHEN a controllable entity has a `BotRegistry` session THE SYSTEM SHALL emit `brained:true`;
  WHEN it has none, `brained:false`. *(negative + positive control on one frame: a reproduction-child
  flower vs an admitted bot; exercise a bondedPair to pin structure ownership too.)*
- **O3b** — WHEN a `BondedPair` / `CompositeMember` is projected THE SYSTEM SHALL emit its subtype
  fields (`primarySpecies`+`secondarySpecies`; `compositeId`+`role`+`species`) per H1.
- **O4** — WHEN a particle of species T is **committed** (placed/registered) THE SYSTEM SHALL increment
  `spawns[T]` by **exactly 1**, asserted as an independently-captured **before/after delta**
  (`after[T]-before[T]==1`) — the counter's local state-transition contract, **never** an accumulated
  total, share, or `>0` (those are banned count-assertions). *(control: committing species U leaves
  `spawns[T]` unchanged; and a failed `grid-full` placement increments nothing. Exercise each
  production creation path; no simulation is advanced to reach a total.)*
- **O5** — WHEN a lightning strike is **applied** on tick N THE SYSTEM SHALL include its coordinate in
  tick N's `env.lightning[]` and clear it by tick N+1. *(one armed two-tick sequence: an injected strike
  **present at N** is the positive control; **absent at N+1** pins clearing — not a separate
  empty-tick test.)*
- **O6a** — WHEN a session connects to `/ws/observer` THE SYSTEM SHALL NOT create an entity.
  *(control: a `/ws/world` connection in the same harness **does** create one — so the `never()` isn't
  vacuous from a failed observer connection; also assert the observer connected + got an O1 frame.)*
- **O6b** — WHEN a session connects to `/ws/observer` THE SYSTEM SHALL NOT consume an admission slot.
  *(own control (rev4): capture `AdmissionGate.reservedSlots()` (accessor at `AdmissionGate.java:93`)
  before/after — observer leaves it unchanged, AND a fresh `/ws/world` registration changes it by
  exactly `+1`, proving the slot gate is live not inert.)*
- **O6c** — WHEN a session connects to `/ws/observer` THE SYSTEM SHALL NOT add a `BotRegistry` entry.
  *(control: `/ws/world` connection adds one.)* Each conjunct RED-tested independently.
- **O7** — WHEN building a frame from a **seeded, engine-direct fixture (zero ticks advanced)** THE
  SYSTEM SHALL include every occupant with its `kind`+coordinates (including a **zero-energy composite
  member** — occupancy census, no liveness filter), and every non-zero env cell. Expected literals
  derive **solely from the test-owned fixture**, not from the production census function. *(control:
  seed both zero and non-zero env cells so "only non-zero cells" is armed.)*
- **O7b** — WHEN computing `populations` from that same seeded fixture THE SYSTEM SHALL apply the census
  rule exactly (particle +1 to species; bondedPair +1 to **both**; compositeMember +1 to species;
  rock/nutrient excluded). Test-owned fixture, zero ticks — see Firewall boundary for why this is
  permitted mechanism, not a banned aggregate.

Each negative clause is paired with a positive control; each gate is RED-tested (break the guarded line,
watch it fail for the spec reason, restore).

**Browser smoke test (not pixel fidelity, M6):** one real end-to-end test that (a) completes the
`/ws/observer` handshake with a **browser-equivalent** extensions offer (guards C1 — a mocked handler
would not), (b) receives + parses a bootstrap and a world frame, and (c) exposes a non-pixel
render/status signal. A page-*serves* check alone passes with broken JS or a rejected handshake.

## Firewall / emergence boundary

The visualiser **displays** counts, shares, populations, and spatial patterns — *observation*, which the
firewall permits. The firewall forbids **asserting** on emergent aggregates in the default suite. The
review adjudicated the two boundary cases explicitly against `CLAUDE.md`:

- **O4 spawn counter — safe as an event-local delta.** `after[T]-before[T]==1` for one committed
  creation is the counter's deterministic state-transition (a transformation contract), **not** a claim
  about resulting population. Banned instead: `spawns[T]==N` after advancing ticks, `spawns[T]>0`,
  shares, cross-species totals (`CLAUDE.md:27`).
- **O7/O7b seeded census — permitted mechanism, not a banned aggregate.** `CLAUDE.md:73` explicitly
  names "population census" as deterministic fast-suite mechanism, and `:77` permits literals whose
  inputs are **test-owned**. The class-ban (`:27`) targets per-population *statistical aggregates* and
  tuning-sensitive magnitudes **from a run**. The safe form (mandated in O7/O7b): an engine-direct
  fixture, **zero ticks advanced**, expected values enumerated from the fixture — testing the exact
  `snapshot → populations` transformation. It must **not** run N ticks then assert populations,
  survival, non-zero-ness, composition, or any derived structural predicate — that would be the
  "emergence test in disguise" smell.

So: pin the frame **contract** (structure, subtype fields, brained classification, spawn-delta,
lightning transient-clear, seeded census) — never "populations oscillate after N ticks" or any
share / rate / magnitude from a real run. The scoreboard is displayed freely, tested only at the
increment-delta contract. No emergence is gated by this work.

## Consistency model (was Assumption 1 — now specified)

The per-tick *tick-pipeline* mutations are settled: `EnvironmentEngine` (`@Order(14)`) and all engine
stages run before the observer's `@Order` on the **same tick thread**, so env/grid cannot tear during
capture — provided capture **copies values** into the immutable snapshot and hands off only that (never
a live reference into the mutable shadow arrays or a lazy registry query). Occupants are captured via
`WorldGrid.snapshot()` (whole-grid read lock), not a per-cell loop.

The **one** residual inconsistency is cross-surface, and it's bounded, not hidden: WebSocket
registration/disconnect mutate `WorldGrid` + `BotRegistry` on a Jetty thread **not** on the tick
thread, in two steps (grid place `~:631`, `BotRegistry` publish `~:670`). So a frame can momentarily
show a just-placed particle as `brained:false`, or a population count that disagrees with the spawn
counter by one, for a single tick. **Accepted** for a human-watch tuning tool (self-corrects next tick).
If strict atomicity is ever required (it isn't for MVP), the fix is to funnel connection-lifecycle
mutations onto the tick thread — noted, not done.

## Assumptions / Open questions

1. **Worst-case frame budget (GO-on-scale gate).** Off-thread delivery isolates *network writes* from
   the tick, but capture + single Jackson encode still run on the tick thread. Planning **must** measure
   capture+encode latency on a fixture with **both** toxin and mutagen saturated (65 536 cells each) +
   a dense entity layer, against the **400 ms overload watermark**. Fallback if it exceeds budget: move
   the single serialize behind its own capacity-1 encoder VT (snapshot handed off unencoded). MVP ships
   on-thread encode; this measurement gates scale, not the MVP's correctness.
2. **Spawn-counter placement** — increment at the guarded success sites (§5); the
   `LiveEntityRegistry.register` chokepoint lacks species and is not widened unless planning finds it
   cheap and safe.
3. **Enablement default** — ships `paralife.observer.enabled=false`; operator opts in. The named auth /
   origin / rate-limit hardening slice (BACKLOG) is the prerequisite for any *public* exposure.

## Backlog-defer (explicit homes)

- **Observer exposure hardening** (auth/authz, non-wildcard origin policy, handshake rate-limiting) →
  BACKLOG, named prerequisite for any public deployment. MVP ships `enabled=false` + session cap only.
- Replayable capture + A/B replay → BACKLOG.
- Precompress-once fan-out → existing BACKLOG M005 item.
- Live tuning knobs/dials → later M5 slice (layout reserves space now).
- Ops dashboards (piece B), tick-drift tripwire (piece C) → later M5 sub-projects.

## Readiness

**GO-WITH-CAVEATS** (revised down from GO after codex review 2026-07-18 surfaced two integration
blockers the first draft missed). The work is still mechanism (transport, frame contract, off-thread
delivery, two small counters/surfaces, a static render page) and gates no emergence. But it is **not**
the "one endpoint + one broadcaster" the first draft claimed — the review verified against source that:

- **(C1)** the existing deflate-enforcement filter 400s a browser handshake → `/ws/observer` must be
  filter-exempt;
- **(C2)** `TickEvent` is published synchronously on the tick thread → delivery **must** be off-thread
  (`ObserverOutboundSender`, capacity-1 mailbox + drain VT), or a slow observer trips the tick-overload
  gate. "Mirrors TickBroadcaster" was verified false-as-written (TickBroadcaster already hands off async).

With those + the schema (H1/H2/H3), `EnvironmentSnapshot` API (M1), committed-spawn counter (M2),
concurrent registry (M4), and the minimal enablement gate (H4) folded in, the design is sound. Blast
radius is real but contained: new endpoint + broadcaster + `ObserverOutboundSender` + `EnvironmentSnapshot`
+ static page + two additive counters; **zero change to the bot `/ws/world` path** and **zero change to
the sealed `Entity` model** (subtype fields are read, not added). Splits at planning into a **server
slice** (endpoint + off-thread delivery + snapshot API + frame + counters, TDD) and a **UI slice**
(`observer.html`), shippable independently. Remaining pre-GO-on-scale item: the worst-case saturated-field
frame-budget measurement (Assumption 1).
