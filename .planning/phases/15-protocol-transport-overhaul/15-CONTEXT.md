# Phase 15: Protocol & Transport Overhaul - Context

**Gathered:** 2026-04-19
**Status:** Ready for planning (pending formal schema review — see D-50)

<domain>
## Phase Boundary

Replace JSON-per-tick messaging with a compact text protocol; swap Tomcat → Jetty to gain `permessage-deflate` with `server_no_context_takeover=true` negotiated on both sides; redesign bots as stateless reactive agents driven by a unified tick frame; enforce zero-trust vision filtering (server sends only data derivable from entity's perception range). Procedural rock generation lands this phase to enable terrain-aware tests.

**Explicitly out of scope (moved to other milestones):**
- Precompress fan-out infrastructure (`BroadcastChannel` + `CompressedFrame`) → deferred to M005 (Observability & Operations) visualizer phase. Reason: the only 1:N same-payload broadcast that exists today is the Tick heartbeat, and it's too small to matter under `server_no_context_takeover`. Real use case is an observer/visualizer world-state stream which belongs with the UI, observer endpoint, and transport evaluation (SSE vs WebSocket vs HTTP/2 push).
- Visualizer UI, observer endpoint, world-state serialization for humans → M005.

**Roadmap edit required:** `.planning/ROADMAP.md` line 134 success criterion "Precompress fan-out infrastructure … ready for future visualizer broadcast channel" moves to M005.

**Requirements housekeeping:** `.planning/REQUIREMENTS.md` still tags R15–R19 to Phase 15 but those describe emergence tests. After Phase 14 was inserted, those belong to Phase 16 (Emergent Behavior Tests). Phase 15 currently has no formal R-numbered requirements — all scope derives from ROADMAP.md line 127–139 and this CONTEXT.md. Flag for correction during planning.

</domain>

<decisions>
## Implementation Decisions

### Scope & Rollout
- **D-01:** Big-bang protocol replacement. Every WebSocket message type converts to compact text. Jackson JSON serialization removed from the WebSocket path entirely. `Messages` sealed interface reshaped or replaced. All WebSocket-facing tests migrate to the codec.
- **D-02:** Merged Tick + Perception. `PerceptionBroadcaster` is renamed to `TickBroadcaster` (reflects that the tick carries everything the bot needs for one-tick decision-making: state + effects + events + vision). The existing `TickBroadcaster` — which currently broadcasts an identical `Tick` heartbeat (entityCount/bondCount/compositeCount/season) to every session — is deleted. Global world-stats broadcast becomes an observer-side concern, punted to M005.
- **D-03:** Phase 15 ships exactly these deliverables: compact codec; container + deflate swap; stateless bot refactor (including BotClient tech debt from Phase 09); zero-trust filtering; rock generation + PNG loader; actuator metrics; all existing tests pass.

### Codec Alphabet & Coordinates
- **D-04:** Single shared alphabet across every compact field: `0-9A-Za-z_-` (64 chars, 6 bits/char). Same as Phase 14 D-36 for status bitmasks. One lookup table, one set of tests, one parser.
- **D-05:** Relative coordinates use fixed-width regex `^[+-][0-9A-Za-z_-][+-][0-9A-Za-z_-]$` = 4 chars per (dx, dy) pair. First char of each axis is sign (`+`/`-`), second char is unsigned base64 magnitude (0–63). No zigzag encoding — positional parse removes `-`-in-alphabet ambiguity. Per-axis magnitude covers any realistic vision radius with room for out-of-vision markers.
- **D-06:** Expiry tick IDs are absolute world-tick numbers, 4 chars base64 (max ≈16.7M ticks ≈ 97 days continuous at 0.5s interval). Chosen over "X ticks remaining" so a network hiccup doesn't create buff-duration ambiguity — client recomputes `remaining = expiry - currentTick` from whatever frame arrives.

### Tick Frame Structure (solo bot — `T` frame)
- **D-07:** Framing is pipe-delimited, hybrid of positional + tagged:
  - Positional (always present, fixed order): `T | <tick> | <energy>/<max> | <radius>`
  - Tagged (optional, fixed order when present, each prefixed with `<tag>=`): `t=<typeChg>` → `season=<s>,<mult>` → `b=<effects>` → `ev=<events>` → `c=<cells>`
  - Absent tagged sections are simply omitted — parser splits on `|`, each piece matches by `<tag>=` prefix.
- **D-08:** Self-cell omitted from `c=`. Bot knows it's at relative (0, 0) by definition.
- **D-09:** Absolute world x,y **dropped** from self block. Zero-trust — pure relative vision. Post-MVP feature (return-to-spawn, spatial memory) may reintroduce as dedicated section if justified.
- **D-10:** Sensor radius sent explicitly (`<radius>` positional slot). Self-describing: parser reads zone without separate knowledge of whether SENSOR_PLUS_1 is active. Also lets composite members learn their effective stitched radius without separate channel.
- **D-11:** `id` and stable `type` not re-sent every tick. Sent once on `R` (Registered) frame for id; `type` cached client-side. Type changes (bond/composite/dissolution) push a new `t=<newTypeCode>,<newMaxEnergy>` section alongside a state-change event in `ev=`. Bot is "stateless" in the sense of depending only on frame contents for current-tick decisions, but caches stable identity.

### Effects vs Events vs Vision — conceptual split
- **D-12:** Three distinct concepts, three distinct sections. Never merged:
  | Concept | Semantics | Section | Lifetime |
  |---|---|---|---|
  | Effects | Durational, ongoing, expires at tick-ID | `b=` | Multi-tick |
  | Events | Point-in-time, discrete, happened last tick | `ev=` | Instant (reported once) |
  | Vision | Spatial awareness (local + far-perception) | `c=` | Refreshed every tick |
- **D-13:** Events are only included when **not derivable** from the frame otherwise. Formal redundancy rule:
  - Drop if bot can infer from (current cell status + energy delta + effect list delta).
  - Keep if the attacker/target/event source is transient or out-of-vision.
- **D-14:** Event catalogue (minimum-non-derivable set):
  | Code | Args | Rationale (why not derivable) |
  |---|---|---|
  | `ATT_IN` | `<dir>,<dmg>` | Attacker may flee/die before next vision snapshot |
  | `ATT_OUT` | `<dir>,<dmg>` | Target may die+disappear; client needs ack for its own action |
  | `BOND` | — | Self.type changes; paired with `t=` |
  | `UNBOND` | — | Same |
  | `CJOIN` | — | Same |
  | `CLEAVE` | — | Same |
  | `DSLV` | — | Same |
- **D-15:** Dropped events (derivable, not on wire):
  - `CNS` (consume ok/fail) — consume is silent; bot checks own energy. Failure case = bot-logic miss (no adjacent nutrient despite action submitted).
  - `REP` (reproduce ok/fail) — cooldown exposed as effect `CR:<expiryTick>`; other failure reasons derivable from own energy + visible target cell.
  - `TX` (toxin-dmg) — derivable from cell `TOXIN_PRESENT` + energy delta.
  - `IF` (infected), `CURE` (cured + buff gained) — derivable from `b=` list delta + cell `MUTAGEN_ZONE`.
  - `LH` (lightning-hit) — moves to vision as far-perception marker (see D-21).
  - `STARVED_TICK` — derivable from energy.
- **D-16:** Effects list (`b=` section) content. Entries separated by `;`. Format `<code>:<expiryTick>[,<args>]`:
  | Code | Source | Args after expiry |
  |---|---|---|
  | `S+1` | BuffType.SENSOR_PLUS_1 (Phase 14 D-15) | — |
  | `A+1` | BuffType.ATTACK_PLUS_1 | — |
  | `M+1` | BuffType.MOVEMENT_PLUS_1 | — |
  | `U-1` | BuffType.UPKEEP_MINUS_1 | — |
  | `M` | Infection (EnvironmentEngine.infections map) | — |
  | `CR` | Reproduce cooldown (`lastReproducedTick` + `profile.reproduceCooldown()`) | — |
  | `CA` | (future) attack cooldown | — |
- **D-17:** Per-action-type cooldown codes (D-16 `CR`, future `CA`). Single generic `COOLDOWN:<action>` rejected for brevity + extensibility.

### Entity Status Bitmasks (Phase 14 D-38/D-39 carried forward, revised)
- **D-18:** `cellStatus` byte — 6 bits used, 3 reserved for future. Vision-scoped OVERCROWDED (per Phase 14 D-40) kept — bots at vision edge legitimately miscount. D-40 mask-and-OR recomposition in `PerceptionBroadcaster.cellToView` preserved.
  | Bit | Flag | Source |
  |---|---|---|
  | 0 | OVERCROWDED (vision-scoped) | Per-bot recomputation from visible neighbours |
  | 1 | TOXIN_PRESENT | `toxinGrid` > threshold |
  | 2 | MUTAGEN_ZONE | `mutagenGrid` != 0 |
  | 3–5 | reserved | — |
- **D-19:** `entityStatus` byte revised — **3 bits used, 3 reserved.** STARVING removed (redundant vs energy X/Y on self + `Cell.FLAG_STARVING` global bit on cell flags). TOXIC removed (derivable from occupant-cell `TOXIN_PRESENT` bit — entity doesn't carry toxin after leaving cloud; splash-damage rule is protocol-implicit not entity-intrinsic).
  | Bit | Flag | Source |
  |---|---|---|
  | 0 | STARVING | Server `Cell.FLAG_STARVING` / per-type threshold |
  | 1 | MUTATING | `EnvironmentEngine.infections` map contains occupant id |
  | 2 | BUFFED | `BuffRegistry` has any active buff for occupant id |
  | 3–5 | reserved | — |

  Wait — STARVING moved back in, correcting D-18 interim. Final rationale: neighbour energy is NOT on the wire (zero-trust), so predators have no other way to judge prey health. STARVING on neighbours is genuinely non-derivable. Self doesn't need it (knows own energy).
- **D-20:** Self-bitmask **not sent.** Self learns own state from events + energy value + active-effect durations in `b=`. Bitmask is a neighbour-summary artifact.

### Vision Section `c=`
- **D-21:** Sparse + RLE encoding. Only non-default (occupied, status-bearing, or RLE-compressible) cells emitted. Separator between tokens: `,`.
- **D-22:** In-vision cell token grammar:
  ```
  <coord><kindCode>[<subcodes>][<statusBlock>][<RLE>]
  ```
  - `coord`: 4 chars (D-05).
  - `kindCode`: 1 numeric digit. 0=CATALYST, 1=MEMBRANE, 2=SPORE, 3=ROCK, 4=NUTRIENT, 5=BONDED_PAIR, 6=COMPOSITE_MEMBER. Codes 7–9 reserved.
  - `subcodes`:
    - For kind=5 (BONDED): 2 numeric digits = `<primaryType><secondaryType>`. Example `501` = bonded CAT+MEM.
    - For kind=6 (COMPOSITE): 1 numeric digit + 1 role letter (L=LOCOMOTOR, S=SENSOR, A=ARMOR, T=STORAGE, F=FEEDER). Example `60L` = composite CAT LOCOMOTOR.
    - For kinds 0–4: no subcodes.
  - `statusBlock`: **syntax open** (see D-50).
  - `RLE`: optional `*<count><direction>` suffix for runs of identical kind with no status block. Example `+4-23*3>` = 3 rocks starting at (+4,-2) heading east. Only applies to status-less cells.
- **D-23:** Out-of-vision / far-perception markers allowed in `c=`. Token: `<coord><markerChar>` where `markerChar` signals a world-scale event whose coord can exceed sensor radius. Markers reserved for future: `L` lightning strike epicentre, others TBD. **Open:** marker namespace collides with role letters (L = lightning OR LOCOMOTOR) — disambiguation rule needed (parser knows role letters only appear after kind=6 subcode; far markers appear as bare `<coord><letter>` tokens). See D-50.

### Composite Tick Frame — `CT`
- **D-24:** Composite members whose role grants decision authority receive full `CT` frame. Non-authority members receive minimal `MT` frame.
  - Java: `AbstractCompositeMember` (or equivalent) carries `public static final boolean hasActionAuthority = false;` — LOCOMOTOR and any future voting role overrides to `true`.
  - Current authority matrix (confirm during planning): LOCOMOTOR = true, FEEDER = true (per `resolveFeederConsume`), SENSOR / ARMOR / STORAGE = false.
- **D-25:** `CT` frame adds two tagged sections on top of `T`:
  | Section | Sent | Content |
  |---|---|---|
  | `cp=<pool>/<maxPool>:<size>` | every tick | Shared pool energy, max pool, member count |
  | `cs=<sensors>` | **on-change only** | `<coord>:<radius>;...` per SENSOR member (relative to receiving member). Server tracks last-sent layout per composite; resends when a sensor is added/destroyed. Client caches across ticks. Rotation not supported this phase. |
- **D-26:** Non-authority members receive `MT|<tick>|<energy>/<max>[|ev=<events>]`. Alive-check + own events only, no vision, no effects, no pool state. Substantially smaller payload per tick.
- **D-27:** Role re-sent only on composite join/leave state-change (via `ev=CJOIN` + `t=` with new composite type code). Not re-sent every tick.

### Zero-Trust Vision Filtering
- **D-28:** Neighbour occupant IDs dropped entirely from wire. Visible cells carry only `<coord><kindCode>[<subcodes>][<statusBlock>]`. Cross-tick neighbour tracking impossible from protocol alone — matches stateless-bot redesign goal. Composite coordination continues to work: each member knows its own composite via `t=` state-change + cached.
- **D-29:** Per-session pseudonyms rejected — adds server bookkeeping for marginal benefit and leaks selective tracking capability only to privileged bots.

### Container, WebSocket, and Compression
- **D-30:** Tomcat → Jetty swap preferred mechanism: `spring-boot-starter-web` and `spring-boot-starter-websocket` exclude `spring-boot-starter-tomcat`, add `spring-boot-starter-jetty`. Spring Boot 3.4.4 ships Jetty 12.x with Jakarta EE 10 namespace. **Researcher action:** validate that Spring auto-configuration under Jetty 12 exposes extension-config hooks; fallback = explicit `JettyServletWebServerFactory` bean + `WebSocketServerContainerInitializer` customization if auto-config is opaque.
- **D-31:** `permessage-deflate` with `server_no_context_takeover=true` negotiated programmatically on both sides. Server side: configure Jetty's `JettyWebSocketServerContainer` (or equivalent Jetty 12 API — researcher confirms) to advertise extension with the takeover flag. Client side: `BotClient` configures equivalent extension on its `StandardWebSocketClient`. Spring Boot's `server.compression.enabled` (HTTP response compression) does NOT apply to WebSocket frames and is a known red herring.
- **D-32:** Handshake integration test required. Asserts negotiated `Sec-WebSocket-Extensions` response header contains `permessage-deflate; server_no_context_takeover`. Test must use a raw HTTP client to inspect the upgrade response, not a wrapped WebSocket session.
- **D-33:** Fail-fast enforcement: server refuses upgrade if client doesn't negotiate the extension. `BotClient` closes session if handshake response lacks it. Prevents silent fallback to uncompressed traffic.

### Rock Generation
- **D-34:** Pre-computed Perlin texture PNGs bundled as resources (paths TBD by planner). At world init:
  1. Random file choice from the bundled set.
  2. Random rotation (0°, 90°, 180°, 270°).
  3. Random flip (horizontal, vertical, or neither).
  4. Sample pixel luminance (or alpha channel) against a configured threshold → boolean rock grid.
- **D-35:** Seed can be fixed via config for reproducible emergence tests. `paralife.world.rock-seed` with random default.
- **D-36:** Clustered Poisson-disk + accretion rock generator deferred to post-MVP. PNG approach ships first.
- **D-37:** Rock map delivery mechanism between server and client: **open (D-50)** — embed in `W` (Welcome) frame, or send as separate one-shot frame after Welcome. Same-payload-across-sessions property means it's the one place where precompress-once would genuinely save bytes on reconnect storms; listed in D-50 as a decision point.

### Actuator Metrics
- **D-38:** Three Micrometer metrics ship with the phase:
  - **Counter** `paralife.ws.bytes-saved` — sum of `rawBytes - deflatedBytes` per sent frame. Headline compression-ratio metric.
  - **Gauge** `paralife.ws.active-sessions` — wraps `SessionRegistry.size()`. Standard dashboard health indicator.
  - **DistributionSummary** `paralife.ws.tick-frame-bytes` — per-tick payload size histogram. Catches codec regressions that inflate messages.
- **D-39:** `paralife.ws.compress-ops-saved` (fan-out dedup counter) originally planned — dropped with fan-out infrastructure.

### Codec Architecture
- **D-40:** `PerceptionCodec` — shared encode/decode, single source of truth. Used by `TickBroadcaster` (encode) and `BotClient` (decode). Java module placement: `com.paralife.codec` (new package). Pure functions over records/strings; no Spring dependencies so tests run without `@SpringBootTest`.
- **D-41:** Stateless codec: per-call encode/decode with no hidden state. This matters because `server_no_context_takeover` disables stateful compression — our codec must not rely on cross-frame context either.

### Stateless Bot Refactor
- **D-42:** `BotClient` Phase-09 tech debt eliminated: raw `JsonNode` + `LinkedHashMap` paths removed. All wire I/O goes through `PerceptionCodec`. BotClient holds minimal cached state: own entity id (set on `R`), own type (updated via `t=` deltas). No vision memory, no neighbour tracking.
- **D-43:** `HeuristicBrain` becomes as close to a pure function of the tick frame as practical. Input: decoded tick record. Output: Action record. Internal state limited to deterministic tick-local scratch (current tactical choice, not cross-tick memory).
- **D-44:** Bot memory / fog-of-war / cross-tick planning remains **post-MVP** (carried from Phase 14 deferred ideas §"Post-MVP Priority #1").

### Ancillary Message Frames
- **D-45:** Big-bang rollout means auxiliary frames also go compact. Shape guidance (planner refines):
  - `W|<sessionId>|<worldW>|<worldH>|<tick>[|<rockMap?>]` — Welcome. Rock map embedded or follow-up frame per D-37.
  - `R|<entityId>|<x>|<y>` — Registered ack. Initial x,y sent here (one-shot), not per-tick.
  - `r|<entityType>` — client→server register.
  - `E|<code>|<message>` — error frame. Message remains human-readable string; codes allocated during planning.
- **D-46:** Action frame (client→server) — shape follows tick grammar (pipe-delimited, verb + optional args). Exact syntax locked in formal schema review (D-50). Likely `A|<verb>|<dir?>|<ranks?>` where verbs are `M` move / `C` consume / `R` reproduce / `Z` rest, and ranks are the composite LOCOMOTOR STV preference list (concatenated direction chars).

### REQUIREMENTS.md Renumbering
- **D-47:** `.planning/REQUIREMENTS.md` currently maps R15–R19 to Phase 15 but those describe emergence tests that now belong to Phase 16. Phase 15 has no R-numbered requirements in the current doc. During planning, either:
  - Add new R20–R2N for Phase 15 scope derived from ROADMAP line 127–139 + this CONTEXT.md, and renumber R15–R19 to map to Phase 16.
  - Accept the existing gap and rely on CONTEXT.md as the scope anchor.
  Planner picks.

### Claude's Discretion
None — every open encoding / grammar decision requires user input during formal schema review (D-50). No auto-decide fallback. Planner must pause for user confirmation on each D-50 item before writing PLAN.md.

### Formal Schema Review — pre-planning gate
- **D-48:** **Direction char encoding** — single-char encoding for compass directions used in RLE run suffixes, `ATT_IN` / `ATT_OUT` args, FLEEING from-coord convention, Action frame direction slot. Candidates: 8 alphabetic (`N E S W U V X Y` where U=NE, V=SE, X=SW, Y=NW) vs numeric `0-7`. **User-gated — surface during schema review.**
- **D-49:** **Season multiplier encoding** — how the sine-wave multiplier is packed into the `season=<season>,<multiplier>` section. Candidates: fixed-point decimal string `"1.05"`, base64 fixed-point integer, or omit from wire and re-derive client-side from season phase + tick. **User-gated — surface during schema review.**
- **D-50:** **MANDATORY gate between discuss-phase and plan-phase.** This CONTEXT.md locks the shape; the grammar must be formally reviewed and committed as `15-SCHEMA.md` before PLAN.md is written. Every item below requires user input — planner pauses for confirmation on each before finalising:
  1. **Status block sentinel:** Option A (`:<c><e?>` positional with single sentinel), Option B (`:<c>;<e>` dual separator), Option C (`:<c><e>` fixed 2-char when `:` present). Worked examples + byte-cost table preserved in `15-DISCUSSION-LOG.md`.
  2. **Direction char encoding** (D-48).
  3. **Far-perception marker namespace disambiguation** — `L` = lightning strike token OR LOCOMOTOR role letter. Parser-side disambiguation via positional rule (roles only appear after kind=6 subcode; markers appear as bare `<coord><letter>`), or segregate alphabets.
  4. **Season multiplier encoding** (D-49).
  5. **Rock map delivery** — embedded in Welcome frame vs separate one-shot frame after Welcome (D-37).
  6. **Action frame grammar** — likely `A|<verb>|<dir?>|<ranks?>` mirroring tick frame, verbs M/C/R/Z, ranks = concatenated direction chars for composite LOCOMOTOR STV voting (D-46).
  7. **Final message-type char allocation** — `T`, `CT`, `MT`, `W`, `R`, `r`, `E`, `A` — confirm no collisions, reserve a future namespace.
  8. **Edge-case shakedown** — encode a representative tick sample, decode back through the codec under test, verify round-trip equality on every frame type including empty, single-cell, dense-RLE, and state-change variants.
  9. **FLEEING-in-or-out** — decide whether `F:<expiryTick>,<fromX>,<fromY>` effect + lightning flee formula fold into Phase 15 schema-lock plan, or defer to Phase 14 follow-up. Impacts whether far-perception marker `L` has a concrete consumer this phase.

  **Planner's first task:** drive this review interactively, commit `15-SCHEMA.md` with locked grammar + parse tree + worked test vectors + round-trip proof. Only then is PLAN.md written. If any item is genuinely non-user-gated after discussion, note explicitly in `15-SCHEMA.md` with rationale.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap and Requirements
- `.planning/ROADMAP.md` §Phase 15 (lines 126–139) — phase goal + success criteria. Line 134 slated for edit per D-03 (fan-out moves to M005).
- `.planning/REQUIREMENTS.md` — stale for Phase 15 per D-47; planner updates.
- `.planning/PROJECT.md` — v2.0 active milestone scope; M005 Observability & Operations listed out-of-scope (line 39) — fan-out moves there.

### Prior Phase Context (decisions carried forward)
- `.planning/phases/14-environmental-rules/14-CONTEXT.md` — D-36 base64 alphabet, D-37 fixed-width stance, D-38/D-39 bit layouts, D-40 vision-scoped OVERCROWDED, D-41 per-tick status caches. **Phase 14 deferred ideas §"Protocol & Transport (Phase 15)"** enumerates the inbound scope; D-02 to D-03 supersede it.
- `.planning/phases/12-composite-entities/12-CONTEXT.md` — composite roles, D-26 STV ranked preferences for LOCOMOTOR voting, D-36 stitched perception pattern. Informs D-24 / D-26 / D-46.
- `.planning/phases/13-energy-metabolism-system/13-CONTEXT.md` — per-type reproduce cooldown (reused as `CR` effect code, D-16), seasonal cycle (season + multiplier on wire, D-07).

### Source Files (pattern-mapping inputs)
- `src/main/java/com/paralife/websocket/Messages.java` — sealed interface to reshape per D-01. Current CellView grammar + Phase 14 D-38/D-39 documentation is the starting reference.
- `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` — renamed `TickBroadcaster` per D-02. Current `buildPerception`, `buildStitchedPerception`, `cellToView` (vision-scoped overcrowding per D-40) are the feed into the new codec.
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — **deleted** per D-02. Functionality moves to M005 observer endpoint.
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — raw `WebSocketHandler` entry point; will call `PerceptionCodec` instead of `ObjectMapper`.
- `src/main/java/com/paralife/websocket/WebSocketConfig.java` — Jetty 12 extension wiring lands here per D-30 / D-31.
- `src/main/java/com/paralife/websocket/SessionRegistry.java` — unchanged; feeds the active-sessions gauge D-38.
- `src/main/java/com/paralife/bot/BotClient.java` — raw `JsonNode` tech debt cleanup per D-42.
- `src/main/java/com/paralife/bot/HeuristicBrain.java` — pure-function refactor per D-43.
- `src/main/java/com/paralife/engine/ActionResolver.java` — consume (`resolveConsume` L452) is silent-success model (D-15). Reproduce (`resolveReproduce` L502) has per-type cooldown via `lastReproducedTick` that becomes `CR` effect D-16.
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — toxin is "don't stand in the fire" (`resolveToxinCollisions` L344); mutagen `Infection` map keyed by entity id. Feeds D-19 MUTATING bit and D-16 `M:` effect.
- `src/main/java/com/paralife/engine/BuffRegistry.java` — `ActiveBuff(BuffType, long expiryTick)` record is the source-of-truth for D-06 absolute-expiry semantics.

### Build + Config
- `build.gradle.kts` — starter-tomcat exclusion + starter-jetty per D-30.
- `src/main/resources/application.yml` — new `paralife.world.rock-seed` (D-35); any codec/wire config (tick-id width override, etc.) if externalised.

### Existing Test Surfaces (will migrate to codec)
- `src/test/java/com/paralife/websocket/WebSocketIntegrationTest.java`
- `src/test/java/com/paralife/websocket/TickBroadcasterTest.java`
- `src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java`
- `src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java`
- `src/test/java/com/paralife/bot/BotClientIntegrationTest.java`

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BuffRegistry.ActiveBuff(BuffType, long expiryTick)` — already uses absolute expiry tick. Zero conversion needed for D-06.
- `SessionRegistry` — session bookkeeping ready to wrap in the active-sessions gauge (D-38).
- Phase 14's vision-scoped OVERCROWDED mask-and-OR in `PerceptionBroadcaster.cellToView` (lines 381–418) — logic preserved after rename, feeds D-18 bit 0.
- `EnvironmentEngine.getCellStatus(pos)` / `getEntityStatus(id)` — the per-tick status caches (Phase 14 D-41) feed the codec without rework. Only the byte→char projection moves.
- `@EventListener` + `@Order` tick pipeline — codec lives in the renamed `TickBroadcaster` at `@Order(50)`, unchanged position.

### Established Patterns
- Immutable records throughout for message types — codec output records should follow (e.g. `TickFrame(long tick, int energy, int maxEnergy, int radius, Optional<TypeChange> typeChg, ...)` or similar).
- `@ConfigurationProperties` on records for config binding — rock seed / codec options follow the same shape.
- Raw `WebSocketHandler` (not STOMP) already in place — Jetty swap doesn't disturb handler registration.

### Integration Points
- Tick pipeline `@Order(50)` — renamed `TickBroadcaster` lives here, consuming `EnvironmentEngine` status caches and emitting compact text via codec.
- `BotClient.handleTextMessage` — replaces Jackson-tree parse with `PerceptionCodec.decode`.
- `WebSocketConfig.registerWebSocketHandlers` — extends to attach `permessage-deflate` extension config on the Jetty container (D-30 / D-31).
- `application.yml` → `build.gradle.kts` → starter swap (D-30) — verify `spring-boot-starter-jetty` brings compatible WebSocket support under Jakarta EE 10.

### Known Debt to Resolve This Phase
- `BotClient` raw `JsonNode`/`LinkedHashMap` usage (Phase 09 tech debt #4) — forced eliminated by codec adoption.
- `PerceptionBroadcaster` UNKNOWN type workaround for dead entities (Phase 08 tech debt #2) — opportunistic cleanup; not a blocker.

</code_context>

<specifics>
## Specific Ideas

- **Absolute expiry tick instead of remaining-ticks (D-06).** Hiccup-proof: on reconnect or frame skip, client derives remaining from whatever frame arrives, no drift. Works even if server and client disagree on current tick until the next frame resynchronises.
- **Merged tick frame (D-02).** "Perception" as a concept collapses into "tick" — the full atomic state-delta the bot needs to decide its next action. Naming follows the concept.
- **`hasActionAuthority` role flag (D-24).** Static-final on role base class, overridden per role. Composite members without action authority skip the expensive vision assembly entirely. Performance win plus clearer API contract.
- **Lightning as perception, not event (planned for deferred flee effect).** Bots see the strike in their spatial section via a far-perception marker (D-23) rather than being told via event. Semantically: bots act on what they perceive, not on what they're told. Extensible to future shockwaves, fires, nutrient blooms.
- **Status block as minority-tax (D-50 pending).** Most cells have no status; status-bearing cells are the minority. Whichever sentinel syntax wins, the waste is paid by the minority and deflates away.

</specifics>

<deferred>
## Deferred Ideas

### Moved to M005 (Observability & Operations) visualizer phase
- `BroadcastChannel<T>` + `CompressedFrame` precompress fan-out infrastructure.
- World-state broadcast to observers / visualizers.
- Transport evaluation for observer stream — WebSocket precompress vs SSE vs HTTP/2 push vs raw WebSocket per-observer.
- Global world-stats heartbeat (entityCount / bondCount / compositeCount) — currently in `TickBroadcaster`, removed from bot-facing protocol. Reintroduce server-side for observers.

### Pending formal schema lock (see D-50)
- **FLEEING effect** — user-proposed `F:<expiryTick>,<fleeFromX>,<fleeFromY>` in `b=` list, injected server-side on entities within lightning outer radius at strike moment. Flee strength formula: `flee_strength = CONSTANT - magnitude(<bot pos> - <from>)`. Represents a **new gameplay mechanic** that Phase 14 shipped without. Two routes: (a) fold into Phase 15 schema-lock plan (adds scope but keeps lightning flee behaviour coherent); (b) defer to a small Phase 14 follow-up after schema lock. Decide at schema review.
- **Far-perception marker concrete consumers** — protocol supports the pattern (D-23) but lightning strike token + FLEEING effect both wait for the schema-review decision on (a) vs (b) above.

### Post-MVP (Phase 14 carried forward)
- **Bot memory / fog of war** (Phase 14 Post-MVP Priority #1). Clients cache vision, enabling multi-tick planning and mental models of other entities. Zero-trust drop of neighbour IDs (D-28) is compatible with memory — client can assign local tracking IDs itself.
- **Composite rotation.** Orientation field + rotation event triggers `cs=` re-send. Out of scope for this phase per explicit decision.
- **Per-session pseudonym IDs for neighbours.** Enables single-bot tracking while preventing shared-ID collusion. Needed only if memory-equipped bots request stable local handles without full IDs.
- **Multi-tick gestation reproduction.** Invest energy over multiple ticks, abortable by combat/starvation. Would introduce a `REPRODUCING` effect. Phase 13 shipped single-frame reproduction.
- **Clustered Poisson-disk rock generator** (D-36). Richer terrain generation beyond the PNG-based MVP loader.
- **Persistent POISONED debuff** — residual toxin damage after leaving the cloud. Current toxin = in-cloud only. Adding this would expand Phase 14 scope retroactively.

### Housekeeping flagged
- `.planning/REQUIREMENTS.md` R15–R19 renumbering — belongs to Phase 16 Emergent Behavior Tests (D-47).
- `.planning/ROADMAP.md` line 134 edit — fan-out success criterion moves to M005 (D-03).

</deferred>

<schema_preview>
## Illustrative Tick Frame — for formal review (D-50)

*Not locked. Shown as a worked example for schema-review planning.*

### Solo bot tick, eventful frame
```
T|2A|15/80|2|season=SP,1.05|b=S+1:1Fg8;M:1Ef0;CR:1Ea5|ev=ATT_IN:N,5|c=+4-23*3>,+1+11:3,+G-BL
```

Decoded:
- `T` — solo tick frame
- `2A` — tick number 2A (base64) = 2×64 + 10 = 138
- `15/80` — energy 15 of 80
- `2` — sensor radius 2
- *(`t=` absent — no state change this tick)*
- `season=SP,1.05` — spring, multiplier 1.05
- `b=S+1:1Fg8;M:1Ef0;CR:1Ea5` — SENSOR_PLUS_1 expires tick `1Fg8`, MUTATING expires `1Ef0`, reproduce cooldown expires `1Ea5`
- `ev=ATT_IN:N,5` — attacked from north took 5 damage last tick
- `c=+4-23*3>,+1+11:3,+G-BL` — 3 rocks east from (+4,-2); membrane at (+1,+1) with cellStatus=1 (TOXIN_PRESENT); lightning marker at (+G,-B) = (+16, -11) — far outside vision

### Composite member tick (LOCOMOTOR)
```
CT|2A|30/100|2|cp=120/200:4|cs=+0+0:2;+3+0:3|b=S+1:1Fg8|ev=|c=+4-23*3>,+1+11:3
```

Decoded:
- `CT` — composite tick frame
- `cp=120/200:4` — shared pool 120 of 200, 4 members
- `cs=+0+0:2;+3+0:3` — SENSOR at (+0,+0) radius 2; SENSOR at (+3,+0) radius 3 (one SENSOR_PLUS_1)
- *(other sections as per `T` grammar)*

### Non-authority composite member tick (ARMOR)
```
MT|2A|20/60
```

Alive + energy only. No vision, no effects.

### State-change frame (bonding)
```
T|2A|15/80|2|t=501,80|ev=BOND|c=...
```

- `t=501,80` — new type: bonded CAT+MEM, maxEnergy 80
- `ev=BOND` — the event pairing with the type change

</schema_preview>

---

*Phase: 15-protocol-transport-overhaul*
*Context gathered: 2026-04-19*
*Companion phase: 14 (Environmental Rules) — protocol-ready bit layouts locked there (D-36 through D-41) carried forward as D-04, D-18, D-19.*
*Next gate: formal schema review (D-50) before PLAN.md.*
