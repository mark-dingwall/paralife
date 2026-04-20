---
phase: 15-protocol-transport-overhaul
plan: 05
subsystem: codec
tags: [codec, parser, encode, decode, round-trip, dos-bounds, ll1]

requires:
  - phase: 15-protocol-transport-overhaul
    plan: 01
    provides: locked SCHEMA §2/§8/§10 — Vector 9 = v+F-3L5 (4-char relative)
  - phase: 15-protocol-transport-overhaul
    plan: 02
    provides: Frame / Coord / KindData sealed hierarchies + PerceptionCodec stub + ParseCursor + Base64Codec + MAX_S_ENTRIES / MAX_V_ENTRIES constants
provides:
  - PerceptionCodec.encode(Frame) -> String for all 5 Frame subtypes (TickFrame, SyncFrame, RegisterFrame, ActionFrame, ErrorFrame)
  - PerceptionCodec.decode(String) -> Frame with single-pass LL(1) parsing via ParseCursor
  - DoS enforcement on decode — MAX_S_ENTRIES (256) / MAX_V_ENTRIES (32) throw CodecException with the literal constant name in the message
  - Frame.TickFrame.blockOrder — trailing List<Character> field preserving wire-order of optional blocks so round-trip is byte-exact when block ordering deviates from canonical (V11)
  - PerceptionCodecErrorTest — 13 negative-path tests (empty, null, unknown type, out-of-alphabet, truncation, unknown verb, DoS bomb, bounded-entries, bounded-events, round-trip pins for a/r/E)
affects:
  - phase-15-06 (ActionDispatcher uses decode for client-submitted a frames)
  - phase-15-07 (TickBroadcaster uses encode for T frames; PerceptionBroadcaster already builds CellEntry records this plan can round-trip)
  - phase-15-08 (ZeroTrustFilteringTest will grep the codec for entity-id leak paths — none present by construction)
  - phase-15-11 (integration test migration uses decode to assert tick semantics against real WebSocket frames)

tech-stack:
  added: []
  patterns:
    - "Single-pass LL(1) parse via mutable ParseCursor — no backtracking, no split, no regex"
    - "Byte-disposition lookup by first-char class (§2 + §8.1.4 tables) for LL(1) disambiguation"
    - "Look-ahead by counting bytes to next ',' or '|' (remainingToDelim) — enables RLE solo vs run detection without a separate sentinel"
    - "DoS-safe list allocation — bounded entries checked BEFORE append so an attacker cannot allocate the full bomb before the cap trips"
    - "Wire-order preservation via record field — TickFrame.blockOrder lets decode/encode round-trip structurally-equivalent frames that differ in block ordering (V6 vs V11)"

key-files:
  created:
    - src/test/java/com/paralife/codec/PerceptionCodecErrorTest.java
  modified:
    - src/main/java/com/paralife/codec/PerceptionCodec.java
    - src/main/java/com/paralife/codec/Frame.java

key-decisions:
  - "TickFrame gains a trailing List<Character> blockOrder field (Rule 3 deviation — see below). Required to satisfy V6 (s,f,v,p,g) and V11 (g,v) simultaneously; neither a single canonical order nor any schema-derived ordering can reproduce both vectors byte-for-byte."
  - "tickId encodes as fixed 3-char base64 (not 4 as the §6.3.1 header table reads). All vectors in SCHEMA §10 show a 3-char tickId (e.g. '001', '004'); vectors are the authoritative oracle per the plan. Documented inline in encodeTick."
  - "expiryTick / energy / maxEnergy / pool / maxPool encode as variable-length minimum-width base64 integers (0 => '0'). Decoder reads until ':', ',', '/', '|', or end. This matches §6.3.1 ('var' slot) and round-trips both '2E' (V6) and '1Fg8' (V10)."
  - "RLE solo vs run disambiguated by counting bytes to next ',' or '|' (§8.1.4 look-ahead table) — keeps parser LL(1) while handling all four presence/kind-R combinations."
  - "Relative coord emitter always produces exactly 4 chars (sign+mag per axis) and clamps to ±63 per SCHEMA §8.4 lightning-range note; no 6-char extended relative branch exists anywhere in the codec."

requirements-completed: [R20, R21]

duration: ~25min
completed: 2026-04-20
---

# Phase 15 Plan 05: PerceptionCodec encode/decode Summary

**Full bidirectional codec for the Phase 15 compact wire protocol — 13 SCHEMA §10 round-trip vectors GREEN + 13 negative-path tests (DoS, malformed input, frame round-trips) pinned.**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-04-20T02:38:00Z (approx)
- **Completed:** 2026-04-20T02:46:00Z
- **Tasks:** 2 / 2
- **Test methods added:** 13 (PerceptionCodecErrorTest) on top of the pre-existing 13 RED round-trip vectors (now GREEN).

## Accomplishments

- `PerceptionCodec.encode(Frame)` — sealed switch over 5 Frame subtypes; StringBuilder-based, pre-sized 128, no allocation beyond the result string.
- `PerceptionCodec.decode(String)` — single-pass LL(1) parser via `ParseCursor`; no `split()`, no `Pattern`, no regex anywhere.
- All 13 SCHEMA §10 vectors round-trip byte-for-byte, including:
  - V6 (LOCOMOTOR full frame — s/f/v/p/g blocks, RLE rock run, bonded-primary entry, nutrient, mixed-status cells, FLEEING with abs strike, multi-event v including T with no coord, pool + roster).
  - V8 (minimal form — no sensorRadius slot, v block only, sensorRadius=0 sentinel preserved).
  - V9 (`v+F-3L5` — 4-char relative +15/-3 + L magnitude 5; lightning event bot took from off-radius source while FLEEING effect holds abs strike coord).
  - V11 (`g` before `v`; preserved via `TickFrame.blockOrder` — see Deviations).
  - V13 (RLE run + per-cell env supplements merging at client).
- DoS bounds enforced on decode:
  - `MAX_S_ENTRIES = 256` — 10_000-entry s block trips with "MAX_S_ENTRIES" in message.
  - `MAX_V_ENTRIES = 32` — 100-entry v block trips with "MAX_V_ENTRIES" in message.
  - 100KB malformed bomb rejected in < 500ms (both sentinels complement each other; neither catches what the other misses).
- Negative-path coverage: empty/null input, unknown frame type, out-of-alphabet in header, truncated tick, unknown action verb, plus positive round-trip pins for the simple frame grammars (`a|M|8`, `a|L`, `E|429|respawn cap`, `r|C`).
- Full test suite: 598 tests, 16 failures — all 16 belong to the deferred-items registry (plans 15-11). No new regressions.

## Task Commits

1. **Task 1 — Codec encode/decode implementation (13 vectors GREEN)** — `6e63d50` (feat)
2. **Task 2 — Error-path + bounded-entries DoS tests** — `7fc8bcb` (test)

Note on TDD: Task 1 is marked `tdd="true"` in the plan. The RED phase was already in place at the start of this plan (`PerceptionCodecRoundTripTest` existed since 15-02 and threw `UnsupportedOperationException` against the stub codec — confirmed by running the suite before implementation; 13 of 13 failed). The GREEN commit delivers the encode/decode implementation that flips all 13 vectors GREEN in a single atomic change. A REFACTOR commit was not produced — the codec landed in its final shape without a cleanup pass needed.

## Files Created/Modified

### Created
- `src/test/java/com/paralife/codec/PerceptionCodecErrorTest.java` — 13 negative-path / DoS / simple-frame round-trip tests.

### Modified
- `src/main/java/com/paralife/codec/PerceptionCodec.java` — Full encode/decode body (952 lines including javadoc and helpers). Previously a 43-line stub.
- `src/main/java/com/paralife/codec/Frame.java` — `TickFrame` gains a trailing `List<Character> blockOrder` field with constructor validation (only chars from `scfvpg` permitted). Default value `List.of()` means "use canonical schema order" — so any future caller that does not care about block order can pass an empty list and get schema-default emission.

## Decisions Made

- **Rule 3 deviation — `TickFrame.blockOrder`:** V6 and V11 place `g` in different positions relative to `v`. No single canonical ordering reproduces both. Adding a block-order field is the minimum viable change that keeps the Frame record immutable and keeps the parser LL(1). Legacy callers using `List.of()` get the canonical schema order for free; wire-replaying decoders carry the exact observed order. Plan's `files_modified` did not list `Frame.java`; adding it was necessary to satisfy V11 — documented here as a scope deviation.
- **tickId as fixed 3-char base64:** SCHEMA §6.3.1 header table reads "4 base64" for tickId, but every vector in §10 uses a 3-char tickId (`001`, `004`, `005`). Tests are the authoritative oracle per the plan. Encoding width kept at 3 chars; this constrains tickId to 0..262143 (~4 minutes of simulation wall-clock at 20 Hz), which is inside M002's expected session scope. Post-MVP widening to 4 chars is a wire break and should land with a dedicated schema revision.
- **Variable-length base64 ints for expiry/energy/pool:** Decoder reads until delimiter; encoder emits minimum width (0 → `0`). Round-trips `2E` (142), `50` (5·64=320), `1Fg8` (326280) without ambiguity because they always sit adjacent to a delimiter in their slots.
- **RLE disambiguation by byte count:** §8.1.4 look-ahead table has four cases (presence×(R-solo vs R-run)). Counting bytes between the kind byte and the next `,`/`|`/end gives a clean LL(1) decision. Cost is one linear scan per entry — negligible against 5×5 = 25 cells typical.
- **Relative coord clamping:** `encodeRelative` clamps to ±63 per SCHEMA §8.4 lightning-range note. The codec does not log the clamp (no logger in this pure-Java class) — callers that need a warning should check the magnitude before encode.

## Deviations from Plan

### Rule 3 — TickFrame.blockOrder field
**Found during:** Task 1 implementation — the round-trip tests for V6 and V11 produced conflicting requirements:

- **V6** (5 blocks present): `T|004|0A1B|15/80|2|s...|f...|v...|p...|g...` — order s, f, v, p, g.
- **V11** (2 blocks present): `T|005|0A1B|30/100|2|g...|v...` — order g, v.

Encoding V11 in canonical schema order (s, c, f, v, p, g) produces `...|v...|g...`, which does not match the test literal. The TickFrame record had no way to carry the observed wire-order, so every canonical-order encoder would fail V11.

**Fix:** Added `List<Character> blockOrder` as the trailing field of `TickFrame`. Decoder populates it with the block chars in their wire-order; encoder follows it. `List.of()` means "use canonical order" — preserves forward-compat for any producer that does not care about order.

**Files modified:** `src/main/java/com/paralife/codec/Frame.java` (not in the plan's `files_modified`). `src/main/java/com/paralife/codec/PerceptionCodec.java` (in plan).

**Commit:** `6e63d50`.

**Why not a checkpoint:** The plan explicitly directs "resolve inline using the schema — do NOT block waiting for me" for Vector 9. V11 is analogous: a vector-dictated constraint that cannot be satisfied without a minimum-viable record change. The alternative (reporting back) would block the wave unnecessarily; the plan's own language encourages inline resolution of test-vector constraints.

### No other substantive deviations

Plan executed as written otherwise. Vector 9 round-tripped on the first attempt (schema was pre-corrected in 15-01). No CHECKPOINT was needed.

## Issues Encountered

### Out-of-scope: 16 pre-existing integration tests still fail
Expected and pre-registered in `deferred-items.md`. Failing classes: WebSocketIntegrationTest (4), PerceptionActionIntegrationTest (6), HundredBotIntegrationTest (1), BotClientIntegrationTest (1), EnvironmentFullStackSmokeTest (1), LoadTest (1), MetabolismIntegrationTest (1), PopulationDynamicsTest (1) — total 16. All owned by plan 15-11.

Verified at commit `7fc8bcb`: full suite reports `598 tests completed, 16 failed, 3 skipped`. Counts match deferred registry exactly.

### Acceptance criteria — all satisfied
- `wc -l src/main/java/com/paralife/codec/PerceptionCodec.java` = 952 (≥ 300 ✓).
- `grep -rE "org\.springframework|com\.fasterxml\.jackson" src/main/java/com/paralife/codec/PerceptionCodec.java` = empty ✓.
- `grep -E "split\(|Pattern\." src/main/java/com/paralife/codec/PerceptionCodec.java` = empty ✓.
- `grep -c "MAX_S_ENTRIES" ...` = 4 (≥ 2 ✓).
- `grep -c "MAX_V_ENTRIES" ...` = 3 (≥ 2 ✓).
- No CHECKPOINT language used ✓.

## User Setup Required

None — pure-Java codec, no config, no resources.

## Next Phase Readiness

- **15-06 (ActionDispatcher):** Can call `PerceptionCodec.decode(payload)` and switch on `Frame.ActionFrame` to dispatch verbs. ActionFrame's `arg` is already validated by `parseAction` (numpad digit for M/E/A/R; 3-char numpad for V; empty for L), so the dispatcher can trust the shape.
- **15-07 (TickBroadcaster):** Can build a `Frame.TickFrame` from world state + `PerceptionBroadcaster`'s per-bot `CellView` list (after mapping to `CellEntry` records) and call `encode`. `blockOrder=List.of()` gives canonical schema order for server-emitted frames.
- **15-08 (ZeroTrustFilteringTest):** The codec never accepts or emits an entity id on a `CellEntry` (the record has no id field by construction per 15-02). A grep-based test asserting zero entity-id tokens in serialised `T` frames will pass trivially.
- **15-11 (integration test migration):** The existing 16 failing tests can be rewritten against `PerceptionCodec.decode` to extract tickId, energy, cells, events from real WebSocket payloads, replacing the Jackson-based JSON assertions.

## Self-Check: PASSED

Files exist:
- FOUND: `src/main/java/com/paralife/codec/PerceptionCodec.java` (952 lines)
- FOUND: `src/main/java/com/paralife/codec/Frame.java` (modified)
- FOUND: `src/test/java/com/paralife/codec/PerceptionCodecErrorTest.java` (new)

Commits on worktree branch `worktree-agent-a5440c1c`:
- FOUND: `6e63d50` — feat(15-05): implement PerceptionCodec encode/decode — 13 vectors GREEN
- FOUND: `7fc8bcb` — test(15-05): add codec error-path + bounded-entries DoS tests

Tests:
- PerceptionCodecRoundTripTest — 13 / 13 pass.
- PerceptionCodecErrorTest — 13 / 13 pass.
- Full suite — 598 tests, 16 failed (all deferred / owned by 15-11).

## TDD Gate Compliance

Plan-level gate not applicable (plan `type: execute`, not `type: tdd`). Task 1's `tdd="true"` attribute was honoured:

- **Task 1 RED gate:** The failing test file (`PerceptionCodecRoundTripTest`) existed at the start of this plan from 15-02's scaffold; the stub codec made all 13 vectors fail with `UnsupportedOperationException`. Confirmed RED on a clean gradle run before writing any implementation. No separate RED commit needed in this plan — the RED artefact was shipped by plan 15-02.
- **Task 1 GREEN gate:** Commit `6e63d50` (feat) flips all 13 vectors to GREEN in a single atomic change. Also passes Task 2's negative-path tests (added in commit `7fc8bcb`, which is a `test` commit, not a feat commit — but Task 2 is not marked `tdd="true"`, so the RED→GREEN ordering requirement does not apply there).
- **Task 1 REFACTOR gate:** Not needed — implementation was designed structured from the outset. No cleanup pass.

---
*Phase: 15-protocol-transport-overhaul*
*Completed: 2026-04-20*
