# Wire Protocol — Compact-Text Codec

**Status:** Live capability contract — byte-exact.
**Pinned by:** `PerceptionCodecRoundTripTest` (round-trip vectors, §10), `PerceptionCodecErrorTest`
(negative paths + DoS bounds, §12), `TickBroadcasterProjectionTest` (vision / tier projection, §7–§8).

> **Normative layer:** the EARS clauses in **§0** are the contract; the prose sections (§1–§13) are
> rationale, tables, and worked examples — non-normative unless tagged otherwise.

---

## 0. Requirements (EARS)

Each clause is `WHEN <event> THE SYSTEM SHALL <response>`, pinned to an existing test by the exact
assertion it turns on (the line that would go red — not merely "a test exists"). Wire-constant
literals (the base64 alphabet, the status bitmask values) are pinned *as literals* by deliberate
exception to the "pin accessors, not magnitudes" rule: they are the immutable wire contract, not
tunable defaults.

| # | Requirement | § | Pinned by — anchor (test method · quoted assertion · symbol) |
|---|---|---|---|
| R1 | WHEN encoding or decoding any compact field THE SYSTEM SHALL use the single shared 64-char base64 alphabet. | §1 | `Base64CodecTest.decodeDigitMapsEachCharToIndex` — `assertEquals(i, Base64Codec.decodeDigit(c))` across all 64 indices vs a **test-owned** alphabet literal (decode-isolating); `encodeDigitMapsEachIndexToChar` (inverse); `decodeDigitRejectsInvalidChars`. Joint backstop: `PerceptionCodecRoundTripTest.roundTripsExactly`, all 13 vectors. |
| R2 | WHEN parsing a coordinate token THE SYSTEM SHALL select the form by first-char class: `+`/`-` → 4-char relative, `1`–`9` → numpad, absolute only in fixed positional slots. | §2 | `CoordTest` (decode-isolating, via `decode()` of a hand-authored frame) — `numpadDispatch` (`Numpad('6')`), `relativeDispatch` (`Relative(4,-2)`/`(-4,2)`), `relativeMagnitudeIsPositional` (`Relative(63,5)` from `+-+5` — `-` is digit 63 in the magnitude slot, not a sign), `absoluteIsPositionalOnly` (curX/curY off the fixed header), `invalidFirstCharRejected` (negative + the numpad/relative cases as positive controls). Joint backstop: `roundTripsExactly` V2/V3. |
| R3 | WHEN a relative coordinate's source offset would exceed ±63 THE SYSTEM SHALL bound it to ±63 before emission, never widening the 4-char relative form. | §2, §8.4 | type invariant `Coord.Relative` ctor (±63 guard, the backstop) + pre-construction producer clamps where offsets *can* be large (`TickBroadcaster.gatherLocoRelativeCells`, `buildRosterIfChanged`); V9 exercises the in-range max. *Reachability investigation (closed): no current producer emits >±63 — composites are size-2 adjacent, solo vision ≤±2; the redundant post-construction codec clamp was removed.* |
| R4 | WHEN emitting a block THE SYSTEM SHALL separate list entries with `,` and intra-entry structure with `:`; `;` SHALL NOT appear. | §3 | `PerceptionCodecEncodeContractTest.encodeUsesCommaBetweenEntriesColonIntraEntryNeverSemicolon` (encode-isolating: builds a two-`s`-entry + `f`-effect frame directly, asserts `,` between entries + the primary `code:` intra-entry separator + `;` absent everywhere). Joint backstop: `roundTripsExactly` V6/V13. |
| R5 | WHEN emitting a spatial block (`s`/`g`/`v`) THE SYSTEM SHALL place coord first; WHEN emitting a type block (`f`/`c`) THE SYSTEM SHALL place code first. | §4 | `PerceptionCodecEncodeContractTest.encodePlacesCoordFirstInSpatialBlockCodeFirstInTypeBlock` (encode-isolating: `s` entry leads with coord, `f` entry leads with code + trailing ctx coord). Only the `f` leg is test-pinned: the `c` type block is code-first *by construction* (§4/§8.2 — it has no coord field, so nothing can misorder), making a `c`-leg assertion unfalsifiable. Joint backstop: `roundTripsExactly` V6. |
| R6 | WHEN encoding a full `T` frame THE SYSTEM SHALL emit present optional blocks in the order `s, c, f, v, p, g`. | §6.3.1 | `PerceptionCodecEncodeContractTest.encodeEmitsBlocksInCanonicalOrder` (encode-isolating: all-six-blocks frame, asserts prefix sequence `containsExactly('s','c','f','v','p','g')`). Closes a round-trip blind spot — **no §10 vector carries both a `c` and an `f` block**, so a c/f reorder survives `roundTripsExactly`. Joint backstop: V6 + V11 (`v` before `g`). |
| R7 | WHEN decoding a frame THE SYSTEM SHALL accept exactly the five types `r/S/T/a/E` and reject any other. | §5, §6 | `PerceptionCodecErrorTest.unknownFrameTypeRejected` — `assertTrue(ex.getMessage().contains("Unknown frame type"))` |
| R8 | WHEN a client registers THE SYSTEM SHALL encode `r\|<entityType>` with type ∈ {C,M,S}. | §6.1 | `RegisterFrameResumeTokenTest.encodeRegisterWithoutToken` — `assertEquals("r\|C", encoded)` |
| R9 | WHEN syncing THE SYSTEM SHALL encode `S\|<entityId>[\|effects]`, the effects segment present only on resync. | §6.2 | `SyncFrameResumeTokenTest.parseSyncEntityOnly` — `assertEquals("abc-123", sf.entityId())`; V10 |
| R10 | WHEN a bot is a passive composite member (SENSOR/DEFENDER) THE SYSTEM SHALL send the minimal `T` form (no vision/effects/pool/roster); WHEN authority-lite (FEEDER/ATTACKER/REPRODUCER) THE SYSTEM SHALL set sensorRadius = 1. | §7, §6.3.2 | `TickBroadcasterProjectionTest.compositeSensorMemberReceivesMinimalForm` — `assertThat(frame.isMinimal())…isTrue()`; `authorityLiteFeederHasSensorRadius1` — `assertThat(frame.sensorRadius())…isEqualTo(1)` |
| R11 | WHEN emitting an error THE SYSTEM SHALL encode `E\|<code>[\|<message>]` with a 3-digit code. | §6.5 | `PerceptionCodecErrorTest.errorFrameRoundTrips` — `assertEquals("E\|429\|respawn cap", encoded)` |
| R12 | WHEN emitting a vision cell THE SYSTEM SHALL prefix a presence byte (bit 0 entity, bit 1 env) and SHALL NOT emit presence=0 cells; entity kind per the §8.1.1 table. | §8.1, §8.1.1 | `TickBroadcasterProjectionTest.emptyCellsOmittedFromSBlock`; `tickFrameShowsNearbyEntitiesWithCorrectKindCodes` — `assertThat(kindCodeOf(east)).isEqualTo('M')` |
| R13 | WHEN projecting entity status onto a cell THE SYSTEM SHALL encode STARVING=`0x01`, MUTATING=`0x02`, BUFFED=`0x04`. | §8.1.2 | `TickBroadcasterProjectionTest.entityStateBitConstantsMatchSchema` — `assertThat(EnvironmentEngine.ENTITY_STATUS_STARVING).isEqualTo((byte) 0x01)` (+ MUTATING `0x02`, BUFFED `0x04`) |
| R14 | WHEN a tick produces a state transition THE SYSTEM SHALL carry at most one `c` token in the frame; on multiple candidates the server picks one (§8.2). | §8.2 | structural: `Frame.TickFrame.change` is `Optional<StateChange>` — singular by type; V5 (`cC:7A`) shows the encoding. Conflict-resolution is server-side emission, not round-trip-pinned (`TickBroadcaster` c-block, currently `Optional.empty()`). |
| R15 | WHEN emitting an event THE SYSTEM SHALL accept every code in `Event.ALL_CODES` and reject any unknown code. | §8.4 | `everyEventCodeRoundTrips` (drives `Event.ALL_CODES`); `PerceptionCodecErrorTest.validateEventCodeRejectsZ` — `contains("Unknown event code 'Z'")` |
| R16 | WHEN a client submits an action THE SYSTEM SHALL accept verbs `M/E/A/R/V/L` and reject any other. | §8.6 | `PerceptionCodecErrorTest.actionRoundTrips` — `assertEquals("a\|M\|8", encoded)`; `unknownActionVerbRejected` |
| R17 | WHEN any valid frame is decoded then re-encoded THE SYSTEM SHALL produce byte-identical output. | §10 | `PerceptionCodecRoundTripTest.roundTripsExactly` — `assertEquals(wireFrame, reEncoded, …)`, all 13 vectors |
| R18 | WHEN an `s` block exceeds `MAX_S_ENTRIES` (256) or a `v` block exceeds `MAX_V_ENTRIES` (32) THE SYSTEM SHALL throw `CodecException` (server then emits `E\|400`). | §12 | `PerceptionCodecErrorTest.boundedEntriesRejected` — `contains("MAX_S_ENTRIES")`; `boundedEventsRejected` — `contains("MAX_V_ENTRIES")` |

**Pinning & deferrals.** R4/R5/R6 now carry **encode-isolating** anchors
(`PerceptionCodecEncodeContractTest`) that build a frame directly from independent literals, encode
it, and assert the one structural property each clause names — with `roundTripsExactly` retained as
the joint backstop. R1/R2 previously leaned on that oracle alone (a symmetric mis-parse/mis-encode
bug survives round-trip); they now carry decode-isolating anchors (`Base64CodecTest`, `CoordTest`)
that assert the decode direction against independent literals. **R17 stays round-trip-only by
definition** — it *is* the byte-exact round-trip contract, so there is nothing to isolate from.

*RED-test evidence (2026-07-07), honest split:* a pure-encode regression breaks byte-exact
round-trip, so `roundTripsExactly` **co-catches** the R4 (`,`→`;`) and R5 (coord-first-broken)
perturbations via the §10 vectors — the encode-isolating gain there is the `;`-never leg for frame
shapes no vector covers, plus clause-named failures. **R6 is the genuine blind-spot closure:** an
f-before-c reorder was caught by `encodeEmitsBlocksInCanonicalOrder` **alone** — `roundTripsExactly`
stayed green because no §10 vector exercises a `c` and an `f` block in the same frame. (R3's >±63 reachability check is now closed: investigation found no producer can emit
>±63 in the current feature set, so the redundant codec clamp was removed and no behavioural fix was
needed — see the R3 anchor note above.)

---

## 1. Alphabet

Single shared alphabet across every compact field:

```
0 1 2 3 4 5 6 7 8 9 A B C D E F G H I J K L M N O P Q R S T U V W X Y Z
a b c d e f g h i j k l m n o p q r s t u v w x y z _ -
```

64 chars, 6 bits per char. Carries forward from Phase 14 D-36. Same lookup table everywhere — one encoder, one decoder, one fuzz surface.

---

## 2. Coordinate Encodings

Three forms coexist. Parser chooses by first-char class + known context slot.

| Form | Shape | Range | Use |
|---|---|---|---|
| **Absolute** | `XXYY` unsigned base64 (4 chars) | 0..4095 per axis | self position in `T` header; strike coord in `fF:` effect context |
| **Relative** | `[+-]X[+-]Y` signed base64 (4 chars) | ±63 per axis | visible cells, events with source |
| **Numpad** | single digit `1`-`9` | 8 adjacent + `5`=self | actions, RLE direction, 3-rank vote, adjacent-event coords |

**There is no "extended relative" 6-char coord form.** All relative coords are exactly 4 chars (1 sign + 1 base64 magnitude, per axis). Any event/effect whose source coord exceeds ±63 must be expressed either in absolute form (stored as trailing ctx) or clamped — not by widening relative. If a coord must exceed ±63 on the wire, add a new positional slot; do not overload relative.

### Numpad layout

```
7 8 9
4 5 6
1 2 3
```

- `5` = self (rarely emitted — self cell never appears in `s` block; `5` in a vote/action means "stay").
- Directions map to numpad keys as on a physical numeric keypad: `1`=SW, `2`=S, `3`=SE, `4`=W, `6`=E, `7`=NW, `8`=N, `9`=NE.

### Parser disambiguation rule

- First char of a token is `+` or `-` → relative coord (consume 4 chars).
- First char is `1`-`9` digit → numpad coord (consume 1 char).
- Absolute coords only appear in fixed positional slots (`T` header, `fF:` trailing context) — never compete with other forms at token start.

---

## 3. Separator Convention

- **Between list entries** inside a block: `,`.
- **Intra-entry structure:** `:` (used inside `f` effects, `a` actions, `p` pool, `c` changes where trailing ctx follows).
- **`;` is not used anywhere.** Env/entity status presence is encoded via a bitmask byte within `s` cell tokens — no secondary separator needed.

---

## 4. Position Convention

### Coord-first (spatial blocks)

Blocks whose entries describe a **spatial anchor** put coord first: `s`, `g`, `v`.
- Within these blocks, the first char of each entry determines coord presence:
  - Digit `1`-`9` → numpad coord present (consume 1).
  - `+`/`-` → relative coord present (consume 4).
  - Letter (A-Z, a-z) → no coord; event/entry applies at own cell or is coord-less.

### Code-first (type blocks)

Blocks whose entries describe a **type anchor** put code first, coord as trailing context:
- `f` (FLEEING stores abs strike coord in trailing `<ctx>` slot — abs coords can't participate in coord-first parsing because the leading digit collides with numpad).
- `c` (single change token; no coord).

---

## 5. Frame Inventory

*(Informative inventory. Frame-type acceptance is normative — §0 R7.)*

| Char | Direction | Purpose | Frequency |
|---|---|---|---|
| `r` | C→S | Register entity type | Once (first msg) + on respawn after death |
| `S` | S→C | Sync (entity id + effects on resync) | Once per session + on request |
| `T` | S→C | Tick (state + events + effects + vision + composite blocks) | Every tick |
| `a` | C→S | Action (M/E/A/R/V/L) | 0..1 per tick |
| `E` | S→C | Error (includes `E|429` on respawn cap) | Opportunistic |

### World constants not on wire

`worldSize`, `yearTicks`, `seasonAmp`, `reproduceCooldowns` are delivered by shared compiled classes (`com.paralife.config` / `com.paralife.world`) that `BotClient` imports from the same build as the server. Zero wire cost. This replaces the anticipated `season=<season>,<multiplier>` tagged slot in D-07.

---

## 6. Frame Grammars

### 6.1 `r` — Register (client → server)

```
r|<entityType>
```

- `<entityType>` ∈ `{C, M, S}` (Catalyst, Membrane, Spore).

Sent as the first message on a fresh session, and again on respawn after receiving a `D` (died) event. Server replies `S|...` on success or `E|429` if the per-session respawn cap is reached.

### 6.2 `S` — Sync (server → client)

```
S|<entityId>[|<activeEffects>]
```

- `<entityId>` — base64, ≥ 1 char, unbounded length (server allocates).
- `<activeEffects>` — **present only on resync** (not on initial register when bot has no history). Same token format as `f` block content but without the `f` prefix: `<code>:<expiryTick>[:<ctx>],...`.

Initial `S` post-`r`: no effects segment. Resync `S` (after reconnect): carries any live effects.

### 6.3 `T` — Tick (server → client)

Two forms. Server chooses based on authority tier (see §7).

#### 6.3.1 Full / authority-lite form

```
T|<tickId>|<curX><curY>|<energy>/<maxEnergy>|<sensorRadius>
  [|s<cell>,<cell>,...]
  [|c<change>]
  [|f<effect>,<effect>,...]
  [|v<event>,<event>,...]
  [|p<pool>/<maxPool>]
  [|g<coord><role>,<coord><role>,...]
```

Positional header slots (always present, in this order):

| Slot | Chars | Meaning |
|---|---|---|
| `<tickId>` | 4 base64 | Absolute world tick |
| `<curX><curY>` | 4 absolute base64 | Self world position (enables FLEEING direction; foundation for post-MVP shadowcasting / A* / memory) |
| `<energy>/<maxEnergy>` | var | Slash-separated base64 ints |
| `<sensorRadius>` | 1 base64 | `1` = 3×3 (authority-lite), `2` = 5×5 default, `3` = 7×7 with SENSOR_PLUS_1 |

Tagged optional blocks (letter-prefix, no `=`):

| Prefix | Present when | Contents |
|---|---|---|
| `s` | Radius > 0 and at least one non-default cell visible | Vision cell list (§8.1) |
| `c` | This tick has a state-change transition | Exactly one change token (§8.2) |
| `f` | At least one effect newly applied or still active at send-once gate | Effect list (§8.3) |
| `v` | At least one event fired for this bot last tick | Event list (§8.4) |
| `p` | Bot is in a composite and has full-authority tier | `<pool>/<maxPool>` shared-pool snapshot |
| `g` | Composite roster changed since last `T` to this bot | Member coord + role list (§8.5) |

**Block ordering.** Present optional blocks MUST be emitted in the canonical order above: `s, c, f, v, p, g`. Absent blocks are skipped; order among those present is fixed. Decoders MAY accept other orders for forward-compat, but encoders MUST emit canonical. Round-trip tests therefore assume canonical emit order.

#### 6.3.2 Minimal form (passive composite members)

```
T|<tickId>|<curX><curY>|<energy>/<maxEnergy>[|v<event>,...]
```

No vision, no effects, no pool, no roster. Alive-check + energy + own events only. SENSOR and DEFENDER receive this form. Substantially smaller.

### 6.4 `a` — Action (client → server)

```
a|<verb>[|<arg>]
```

Grammar per verb in §8.6.

### 6.5 `E` — Error (server → client)

```
E|<code>[|<message>]
```

- `<code>` — 3-digit numeric HTTP-style (e.g. `429` respawn cap, `400` parse error, `403` unauthorised action).
- `<message>` — optional human-readable string; clients may log but should not parse.

---

## 7. Authority Tiers

Three tiers determine which `T` form a bot receives and what actions are accepted.

| Tier | Entity types | `T` form | Sensor radius | Actions |
|---|---|---|---|---|
| **Full authority** | Solo Particle, Bonded Pair, composite **LOCOMOTOR** | Full, plus `p` + `g` for LOCOMOTOR | 2 (3 with SENSOR_PLUS_1) | M, E, A, R (solo/bonded); V (LOCOMOTOR); L (composite members) |
| **Authority-lite** | composite **FEEDER**, **ATTACKER**, **REPRODUCER** | Full, no `p`/`g` | 1 (adjacent cells only) | E (FEEDER), A (ATTACKER), R (REPRODUCER), L |
| **Passive** | composite **SENSOR**, **DEFENDER** | Minimal | — | L only |

**Per-role notes** *(non-normative)*:
- LOCOMOTOR — V primary; M fallback if size = 1. Sensor scope: Composite-stitched (SENSORs' combined field) — distinct from the radius above. Orchestrates composite movement.
- Bonded Pair — Primary decides.
- DEFENDER — Passive absorber.
- SENSOR — feeds stitched vision to LOCOMOTOR.

### Authority-lite rationale

FEEDER / ATTACKER / REPRODUCER see radius-1 (multiple valid targets); they may submit an action to choose, else the server auto-picks a fallback. Phase 15 ships server-side dispatch only (E/A/R verbs + auto-fallback); authority-lite **client-side brain logic** is out of scope for Phase 15 — the MVP `HeuristicBrain` handles solo/bonded/LOCOMOTOR only, and authority-lite brain branches land post-MVP. Tracked in §13.

All composite members (regardless of tier) may submit `a|L` (routed via `BotRegistry` composite lookup — see §8.6 for the alarm delivery mechanics).

---

## 8. Block Grammars

### 8.1 `s` block — vision cells (coord-first, presence bitmask)

```
s<entry>,<entry>,...
```

#### Entry shape

```
<coord><presence><kindData?><entityState?><envState?>
```

- `<coord>` — 1-char numpad OR 4-char relative. Self at (0,0) implicit; never emitted.
- `<presence>` — 1 base64 digit. Low 2 bits define what data follows; bits 2-5 reserved for future expansion:
  - `0` — forbidden (empty cell not emitted).
  - `1` — entity only (bit 0).
  - `2` — env only (bit 1).
  - `3` — both (bits 0 + 1).
- `<kindData>` — present iff presence bit 0 set:
  - Non-rock kinds: 1 char from kind table (§8.1.1).
  - Solo rock: `R` (1 char, no RLE).
  - Rock run: `R<dir><count>` (3 chars) where `<dir>` is numpad direction of the run and `<count>` is **additional** cells beyond the starter (base64 1 char, 1..63). Solo rock = plain `R`; 2-rock run = `R<dir>1`; 3-rock run = `R<dir>2`.
  - **RLE is kind-only.** `<envState>` attached to the starter entry applies to the starter cell ONLY. Additional cells in the run inherit "rock, no env." To attach env state to other cells in a run, emit supplementary **env-only** entries (presence=2) at those cells' positions. Client merges by cell position: RLE populates kind across the run; env-only entries add env state per cell.
- `<entityState>` — 1 char base64 bitmask. **Optional** — omitted when value is 0. Present iff presence bit 0 set AND value ≠ 0 AND kind ≠ R.
- `<envState>` — 1 char base64 bitmask. **Required** whenever presence bit 1 set (emitted even if 0; in practice presence=2/3 implies nonzero env).

#### 8.1.1 Kind codes

| Code | Meaning |
|---|---|
| `C` | Solo Catalyst |
| `M` | Solo Membrane |
| `S` | Solo Spore |
| `D` | Bonded pair, primary = Catalyst (secondary hidden) |
| `N` | Bonded pair, primary = Membrane |
| `T` | Bonded pair, primary = Spore |
| `0` | Composite LOCOMOTOR |
| `1` | Composite FEEDER |
| `2` | Composite ATTACKER |
| `3` | Composite DEFENDER |
| `4` | Composite REPRODUCER |
| `5` | Composite SENSOR |
| `R` | Rock (solo or RLE run) |
| `F` | Nutrient (food) |

#### 8.1.2 `entityState` bits

| Bit | Flag |
|---|---|
| 0 | STARVING |
| 1 | MUTATING |
| 2 | BUFFED |
| 3-5 | reserved |

#### 8.1.3 `envState` bits (Phase 14 D-38 carried)

| Bit | Flag |
|---|---|
| 0 | OVERCROWDED (vision-scoped — Phase 14 D-40, recomputed per bot) |
| 1 | TOXIN_PRESENT |
| 2 | MUTAGEN_ZONE |
| 3-5 | reserved |

#### 8.1.4 Parser look-ahead table

Token ends at next `,` or end-of-block. After coord + presence byte, inspect kind (if present) then count remaining:

| Case | Remaining chars | Layout |
|---|---|---|
| presence=1, non-R kind | 0 | no state |
| presence=1, non-R kind | 1 | entityState |
| presence=1, kind=R solo | 0 | — |
| presence=1, kind=R run | 2 | `<dir><count>` |
| presence=2 | 1 | envState |
| presence=3, non-R kind | 1 | envState only (entityState omitted because 0) |
| presence=3, non-R kind | 2 | entityState + envState |
| presence=3, kind=R solo | 1 | envState |
| presence=3, kind=R run | 3 | `<dir><count><envState>` (envState applies to starter only) |

#### 8.1.5 Worked examples

| Token | Parse |
|---|---|
| `61D` | E, entity-only, bonded-CAT-primary, no state |
| `61D2` | E, entity-only, bonded-CAT-primary, MUTATING |
| `31S1` | SE, entity-only, Spore, STARVING |
| `33S1` | SE, both, Spore, no entity state, OVERCROWDED |
| `33S31` | SE, both, Spore, STARVING+MUTATING, OVERCROWDED |
| `23222` | S, both, ATTACKER composite, MUTATING, TOXIN |
| `621` | E, env-only, OVERCROWDED |
| `41R` | W, entity-only, solo rock |
| `41R82` | W, entity-only, rock run of 3 north (starter + 2 more) |
| `43R2` | W, both, solo rock + TOXIN |
| `43R821` | W, both, rock run of 3 north with starter OVERCROWDED (other rocks get no env unless supplemented) |
| `61F` | E, entity-only, nutrient |

### 8.2 `c` block — state change (code-first, single token)

```
c<type>[:<ctx>]
```

**Exactly one token per tick.** An entity cannot undergo two distinct transitions in the same tick (bonding AND composite-join simultaneously is physically meaningless). If the tick resolution would produce multiple candidates, the server picks one by priority (ties broken randomly) and silently drops the rest. Rare.

| Code | Meaning | Context |
|---|---|---|
| `C` / `M` / `S` | Bonded with that type; you are primary | new stat block (maxEnergy derived from shared config) |
| `D` / `N` / `T` | Bonded with that type; you are secondary | primary entityId |
| `0`-`5` | Became composite member with that role | new stats + composite id |
| `Z` | Composite dissolved; reverted to solo | new stats (server re-rolls from previousType) |

### 8.3 `f` block — effect list (code-first)

```
f<effect>,<effect>,...
```

Per-effect shape:

```
<code>:<expiryTick>[:<ctx>]
```

Temporary, timed. Sent once on initial application unless otherwise noted. `<expiryTick>` is absolute world tick (4 base64 chars per D-06). FLEEING's abs strike coord is in the trailing `<ctx>` slot — abs coords can't live in coord-first parsing because of digit ambiguity with numpad.

| Code | Meaning | Context | Send rule |
|---|---|---|---|
| `I` | MUTATING (infection) | — | once |
| `F` | FLEEING (from lightning) | `XXYY` abs strike coord | once |
| `A` | ATTACK_PLUS_1 | — | once |
| `M` | MOVEMENT_PLUS_1 (hop range 2) | — | once |
| `S` | SENSOR_PLUS_1 (7×7 vision) | — | once |
| `U` | UPKEEP_MINUS_1 (slower decay) | — | once |

### 8.4 `v` block — event list (coord-first)

```
v<event>,<event>,...
```

Per-event shape:

- No coord, no magnitude: `<code>` — e.g. `S`, `D`.
- No coord, has magnitude: `<code><magnitude>` — e.g. `T3`.
- Has coord, no magnitude: `<coord><code>` — e.g. `6N`, `+1+0N`.
- Has coord, has magnitude: `<coord><code><magnitude>` — e.g. `6H3`, `+2-1A5`.

Parser rule:
1. First char digit → numpad coord (consume 1).
2. First char `+`/`-` → relative coord (consume 4).
3. First char letter → no coord.
4. Then read code letter (1 char) + optional magnitude (base64, 1 char).

Magnitude bound to code per the table below; parser knows per-code whether to consume the next char.

| Code | Meaning | Magnitude | Coord |
|---|---|---|---|
| `E` | Ate; gained X energy | yes | source cell (rel/numpad) |
| `A` | Attacked; dealt X dmg | yes | target cell (rel/numpad) |
| `H` | Hit — took X combat dmg | yes | attacker cell (rel/numpad) |
| `T` | Took X toxin dmg | yes | — (own cell) |
| `M` | Took X mutagen tick dmg | yes | — |
| `R` | Took X reflection / splash dmg | yes | — |
| `L` | Took X lightning dmg | yes | strike coord (rel/numpad) |
| `N` | Member alarm (LOCOMOTOR-only) | no | alarming member's cell (rel/numpad) |
| `S` | Reproduced successfully | no | — |
| `D` | Died | no | — |

**Lightning coord range.** Lightning damage is currently surfaced via the `FLEEING` state change, which carries the strike as an **absolute** `XXYY` coord (`fF:` effect context) — so the ±63 relative bound does not bite on the live path. The documented `L`-event-with-relative-coord wire form (Vector 9) would, if emitted, use the same 4-char relative coord as any other `v` event, type-bounded to ±63 by `Coord.Relative`; lightning visibility already requires proximity enough to flee, so >±63 is not reachable in practice. There is NO special 6-char "extended relative" coord for lightning.

### 8.5 `g` block — composite roster (coord-first)

```
g<coord><role>,<coord><role>,...
```

Sent **on change only**. Each entry is `<coord><role>` with no `:` separator — both fields are fixed width (coord 1 or 4 chars via prefix rule; role always 1 char from `0`-`5`). Covers all members; `<size>` is derivable as `count(g entries)` so the old `:<size>` field is dropped.

### 8.6 `a` block — action verbs

```
a|<verb>[|<arg>]
```

| Verb | Meaning | Arg format |
|---|---|---|
| `M` | Move | single numpad digit (direction) |
| `E` | Eat | single numpad digit (nutrient direction) |
| `A` | Attack | single numpad digit (target direction) |
| `R` | Reproduce | single numpad digit (birth direction) |
| `V` | Vote-move (LOCOMOTOR) | 3-char numpad string (ranks 1-3) |
| `L` | Alarm | — |

#### Vote example

`a|V|493` → 1st choice NE, 2nd N, 3rd SE. Resolver runs **IRV** (Instant Runoff): eliminate the lowest-count candidate each round and redistribute by rank until one has majority. Replaces current plurality-only `ActionResolver.resolveLocomotorVote` (src/main/java/com/paralife/engine/ActionResolver.java:957-973). Ties at elimination broken by lowest numpad digit.

#### Alarm example

`a|L`. Routed via `BotRegistry` composite lookup. Appears in LOCOMOTOR's next `T` as `vN<relCoord>` event.

---

## 9. Decision Reversal Table

*(Non-normative — design history retained for facts/rationale.)*

Decisions from `15-CONTEXT.md` superseded by this schema:

| Decision | Was | Now | Reason |
|---|---|---|---|
| **D-07** | `season=<season>,<multiplier>` section on wire | Dropped entirely; client-derives from shared `SimulationConfig` | Zero wire cost; same compiled constants on both ends |
| **D-09** | Absolute x,y dropped from self | `<curX><curY>` in every `T` header | Required for FLEEING direction; foundation for post-MVP shadowcasting / A* / memory |
| **D-11** | `R` (Registered) frame carries id | Replaced by `S` (Sync) frame | Single sync concept covers register ack and reconnect resync |
| **D-13 / D-14** | Events "minimum non-derivable" | Expanded catalogue (E/A/H/T/M/R/L/N/S/D) | Bot reactions simpler with explicit events; deflate amortises |
| **D-15** | CNS/REP/TX/IF/CURE/LH/STARVED_TICK dropped | E/R/H/T/M kept; LH becomes `<coord>L<X>` event | User preference for explicit events |
| **D-16** | `CR` reproduce cooldown effect | Dropped from wire | Derivable client-side from shared config |
| **D-17** | Per-action-cooldown code family | Dropped | No cooldowns on wire at all |
| **D-18 / D-19** | `cellStatus` + `entityStatus` bytes in token | Same bit layouts, but entry shape changes: coord + presence byte + kind + optional states | Presence byte replaces `:` sentinel options from D-50 #1 |
| **D-21** | Role codes `L/S/A/T/F` | Role codes `0`-`5` (LOCO / FEED / ATT / DEF / REP / SENS) | Matches `Entity.java` enum; frees letters for other uses |
| **D-22** | `kind=6` composite + 2-char subcode | Single-char kind `0`-`5` per member kind | Simpler; roster correction. Nutrient gets `F`. |
| **D-23** | Far-perception marker `<coord><letter>` | Lightning is now `v<coord>L<X>` event, not a `s` marker | Eliminates the `L` ambiguity entirely — D-50 #3 resolved by migrating lightning to events |
| **D-24** | LOCOMOTOR + FEEDER = authority | 3-tier: full (solo / bonded / LOCO), authority-lite (FEED / ATT / REP), passive (SENS / DEF) | FEEDER / ATT / REP still autonomous but can choose among targets |
| **D-25** | `cp=<pool>/<maxPool>:<size>` + `cs=...` sensor layout | `p<pool>/<maxPool>` + `g<coord><role>,...` | `:<size>` derivable from `g` count; `=` dropped for consistency; `g` roster covers all members; fixed-width coord+role permits `:` drop |
| **D-26** | STV-based LOCOMOTOR vote | IRV (proper elimination rounds) | Code currently plurality-only; IRV matches design intent |
| **D-28** | Neighbour IDs dropped | Preserved + bonded-secondary also hidden | Stricter zero-trust |
| **D-37** | Rock map bulk delivery | Not delivered in bulk; per-cell in vision only (zero-trust). Visualizer / M005 may receive separately | Zero-trust; consistent with "perceive, don't be told" |
| **D-45** | `W` (Welcome) + `R` (Registered) frames | `r` + `S` collapse both | Fewer frame types; simpler state machine |
| **D-46** | Action grammar with `<ranks?>` slot | Unified `a|<verb>[|<arg>]` | Cleaner single-arg grammar |
| **D-48** | Direction char encoding (alphabetic vs numeric) | **Numpad `1`-`9`** | Single spec covers 8 dirs + `5`=self marker + rank strings |
| **D-49** | Season multiplier wire encoding | Client-derives via shared config | Zero wire cost |
| **D-50 #1** | Status sentinel Options A/B/C | **Presence bitmask byte** (low 2 bits: entity/env, 4 bits reserved) | Removes ambiguity between kind chars and env state chars; drops `;` entirely; future-expandable |
| **D-50 #3** | `L` ambiguity disambiguation rule | Lightning moved to events; no `L` marker in `s` block | Collision eliminated by migration |
| **D-50 #5** | Rock map delivery mechanism | Not delivered; zero-trust per-cell only | See D-37 reversal |
| **D-50 #9** | FLEEING in-or-out | **IN for Phase 15** | Lightning flee mechanic lands alongside wire redesign |

### New scope additions

1. **FLEEING effect + lightning flee mechanic** (D-50 #9 → IN).
2. **Alarm action `a|L`** + `vN<coord>` event delivery to LOCOMOTOR.
3. **Proper IRV vote resolution** (replaces plurality).
4. **Client-side respawn flow** (session stays open post-death; randomised cooldown; `r` re-register; server `S` or `E|429`).
5. **Coord-first convention** for spatial blocks (`s` / `g` / `v`); code-first for type blocks (`f` / `c`).
6. **Authority-lite tier** for FEEDER / ATTACKER / REPRODUCER — radius-1 vision; target choice permitted (server-side); client-side brain for authority-lite is post-MVP.
7. **Nutrient kind `F`** in `s` block (was missing from initial lock).
8. **Presence bitmask** in `s` cell tokens (supersedes `;` sentinel from D-50 #1).

### Decisions carried forward unchanged

- D-01, D-02, D-03 (scope & rollout).
- D-04 (alphabet).
- D-05 (relative coord width).
- D-06 (absolute expiry ticks).
- D-08 (self cell omitted).
- D-10 (sensor radius self-describing).
- D-12 (effects vs events vs vision split).
- D-20 (self bitmask not sent).
- D-29 (pseudonym IDs rejected).
- D-30, D-31, D-32, D-33 (container / compression / handshake).
- D-34, D-35, D-36 (rock generation).
- D-38 (three Micrometer metrics — but `bytes.saved` Counter is deferred per §13; Phase 15 ships only `active.sessions` + `tick.frame.bytes`).
- D-39 (fan-out metric dropped with infra).
- D-40, D-41 (codec architecture).
- D-42, D-43, D-44 (stateless bot refactor).
- D-47 (REQUIREMENTS.md renumber during planning).

---

## 10. Round-trip Test Vectors

These MUST all satisfy `PerceptionCodec.encode(decode(x)) == x` byte-for-byte. Implementation ships a parameterised JUnit test (`PerceptionCodecRoundTripTest`) keyed on this table. Adding a vector requires adding a row AND the test.

| # | Scenario | Frame |
|---|---|---|
| 1 | Empty tick (solo bot, quiet) | `T\|001\|0A1B\|15/80\|2` |
| 2 | Adjacent nutrient (numpad coord) | `T\|001\|0A1B\|15/80\|2\|s61F` |
| 3 | Rock RLE run (relative anchor + numpad RLE dir, count = additional) | `T\|001\|0A1B\|15/80\|2\|s+4-21R62` |
| 4 | Mixed-status cell (entity + env states) | `T\|001\|0A1B\|15/80\|2\|s+1+13M32` |
| 5 | State-change + event (bonding with primary = Catalyst; reproduced success) | `T\|001\|0A1B\|15/80\|2\|cC:7A\|vS` |
| 6 | LOCOMOTOR full frame (pool + roster + vision + alarm + own dmg + FLEEING) | `T\|004\|0A1B\|15/80\|2\|s61R,91F,43C1,+3-21R62,+3+33M32\|fF:2E:0F03\|v6H3,6N,T3\|p120/200\|g62,93,+0+21` |
| 7 | Authority-lite FEEDER (radius-1 vision of a nutrient south) | `T\|004\|0C1E\|20/60\|1\|s21F` |
| 8 | Passive member (DEFENDER) minimal frame | `T\|004\|0D2F\|18/50\|v6H3` |
| 9 | FLEEING active (effect carries abs strike; event carries rel lightning-hit) | `T\|001\|0A1B\|15/80\|2\|fF:2E:0F03\|v+F-3L5` |
| 10 | Resync (Sync with two active effects, no `f` prefix) | `S\|7A\|S:1Fg8,I:1Ef0` |
| 11 | Multi-member alarm (LOCO sees two alarms) | `T\|005\|0A1B\|30/100\|2\|v6N,9N\|g62,93,+0+21` |
| 12 | Env-only cell (empty cell with toxin hazard, relative anchor) | `T\|001\|0A1B\|15/80\|2\|s+2+022` |
| 13 | RLE with per-cell env supplements (rock column of 3 south from W, all MUTAGEN_ZONE) | `T\|001\|0A1B\|15/80\|2\|s43R824,124,-1-124` |

### Vector notes

- **Vector 3** — `+4-21R62` parses as: relative (+4,-2), presence=1 (entity-only), kind=R with dir=6 (E), count=2 (2 additional = 3 rocks total).
- **Vector 4** — `+1+13M32`: relative (+1,+1), presence=3 (both), kind=M, entityState=3 (STARVING|MUTATING), envState=2 (TOXIN).
- **Vector 5** — `cC:7A` = bonded primary = Catalyst, new maxEnergy slot `7A` (carried in ctx).
- **Vector 6** — `43C1` combines "Catalyst at W" + OVERCROWDED into one presence=3 entry (kind=C, no entity state = omitted, envState=1). `R62` run = starter + 2 = 3 rocks east. `fF:2E:0F03` = FLEEING expires tick `2E` with strike coord abs (15, 3).
- **Vector 9** — `fF:2E:0F03` + `v+F-3L5`: effect stores abs strike (15, 3); event says bot took 5 lightning dmg from relative offset (+15, -3). The relative coord is 4 chars: sign `+`, magnitude `F` (base64 → 15), sign `-`, magnitude `3` (base64 → 3). This is the standard §2 relative form; there is NO 6-char "extended relative" coord. If vision extends only to ±2, the relative coord still parses but falls outside the 5×5 snapshot — acceptable; lightning events can originate off-grid from the vision scope. An L event's source is type-bounded to ±63 by `Coord.Relative` and is not reachable beyond that in practice (see §8.4 lightning coord range note).
- **Vector 10** — `S:1Fg8,I:1Ef0` = SENSOR_PLUS_1 expires `1Fg8`, MUTATING expires `1Ef0`.
- **Vector 13** — `43R824,124,-1-124`: starter at W is presence=3 rock run of 3 south (`R82`) with envState=4 (MUTAGEN) on starter; supplements at SW (`124` = numpad 1, presence=2, envState=4) and relative (-1,-2) (`-1-124` = presence=2, envState=4). Client merges: 3 rocks in column, each with MUTAGEN_ZONE.

---

## 12. Parser Implementation Notes

Implementer hints (non-normative), **except the DoS bounds, which are wire-observable and normative —
pinned by §0 R18**.

- **Single pass.** All grammars are LL(1) given the position rules. No backtracking required.
- **Character class table.** A 64-entry lookup table (`charToInt[128]`) handles decoding; a 64-char array (`intToChar[64]`) handles encoding. Share between all fields.
- **Presence bitmask expansion.** Reserve bits 2-5. Future entity-kind flags (e.g. "multi-entity in cell", "cell has special structure") can extend presence without a schema break.
- **Tagged-block detection.** After the positional header, parser loops on `|`-separated segments, branching on the first char: `s` → vision, `c` → change, `f` → effects, `v` → events, `p` → pool, `g` → roster. Unknown leading char → `E|400` at server, or warn+skip at client (forward compat).
- **`a` is the lone client-→server frame** — handler dispatches on first byte only.
- **DoS bounds.** Codec enforces `MAX_S_ENTRIES = 256` (vision cells per `s` block) and `MAX_V_ENTRIES = 32` (events per `v` block). Exceeding either throws `CodecException` → server emits `E|400`. Bounds are constants in `PerceptionCodec` and documented in Frame javadocs.

---

## 13. Known Follow-ups (out of scope)

> Known follow-ups are tracked in BACKLOG.md.

---

## 14. Observer Frame (`/ws/observer`)

A separate, JSON-based read-only protocol for the M5-A observer visualiser — distinct from the
compact-text codec in §0–§13 above (no relation to `PerceptionCodec`; Jackson-serialized, full-word
camelCase keys, `com.paralife.observer.ObserverFrame`). Every frame carries `schemaVersion=1`.
Pinned by `ObserverEndpointIntegrationTest` (real handshake + Jackson parse) and
`ObserverFrameBuilderTest`.

Two frame types, distinguished by `type`.

### `bootstrap` — sent once per connection, before any `world` frame

```json
{
  "type": "bootstrap",
  "schemaVersion": 1,
  "grid": { "width": 64, "height": 64 },
  "rocks": [ { "x": 3, "y": 7 } ]
}
```

Static terrain only (grid dims + rock coordinates). Never retransmitted.

### `world` — sent every tick, latest-wins (a slow observer skips ticks, never sees a queue backlog)

```json
{
  "type": "world",
  "schemaVersion": 1,
  "tick": 1042,
  "entities": [ ],
  "env": { "toxin": [], "mutagen": [], "lightning": [] },
  "scoreboard": { "CATALYST": 12, "MEMBRANE": 9, "SPORE": 15 },
  "populations": { "CATALYST": 40, "MEMBRANE": 38, "SPORE": 21 }
}
```

- `scoreboard` — cumulative committed spawns per species since process start (`SpeciesSpawnCounter`,
  process-lifetime, never reset).
- `populations` — current occupancy census per species, this tick — **no liveness filter** (see
  census rule below).

#### Census rule

Same rule as `PopulationHistory`: `particle` → +1 its species; `bondedPair` → +1 **both**
`primarySpecies` AND `secondarySpecies`; `compositeMember` → +1 its species — **no liveness
filter** (a zero-energy member awaiting next-tick cleanup still counts). Rock and nutrient are
excluded from the census (rocks are bootstrap-only and never appear in `entities`; nutrients appear
in `entities` but carry no species).

#### `entities[]` — one entry per dynamic occupant, shape varies by `kind`

Nullable fields are omitted from JSON (Jackson `NON_NULL`) — each `kind` uses a different field
subset beyond the always-present `x`, `y`, `kind`:

| `kind` | Fields present |
|---|---|
| `particle` | `species`, `energy`, `brained` |
| `nutrient` | `energy` (nutrient level) |
| `bondedPair` | `primarySpecies`, `secondarySpecies`, `energy`, `brained` |
| `compositeMember` | `species`, `compositeId`, `role`, `energy`, `brained` |

`species` / `primarySpecies` / `secondarySpecies` ∈ `{CATALYST, MEMBRANE, SPORE}`. `role` ∈
`{LOCOMOTOR, FEEDER, ATTACKER, DEFENDER, REPRODUCER, SENSOR}`. `brained` marks an entity currently
owned by a connected bot (vs. server-idle/unowned).

#### `env` — per-cell env layers, non-zero cells only

- `toxin: [{x, y, intensity}]` — `intensity` is a **magnitude** (1–255).
- `mutagen: [{x, y, strain}]` — `strain` is a **categorical id** (1–255), NOT a magnitude — render
  it as a distinct category, not an intensity gradient.
- `lightning: [{x, y}]` — strike coordinates applied on this tick only.

---
