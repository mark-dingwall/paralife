# Backlog

The live backlog for deferred work — the home the GSD graduation left open. Items the code/specs
*reference* but don't yet implement live here, not inline in the specs. (Historical, frozen tech-debt
from the GSD era stays in `.planning/` — read it for facts; new deferrals land here.)

Format: each item names **why deferred**, the **trigger** to pick it up, and the **anchor** (symbols /
§sections, not line numbers — those rot).

---

## Codec decode-semantic unit tests (CoordTest / Base64CodecTest)

**Why:** `SCHEMA.md` §0 clauses R1 (alphabet) and R2 (coordinate disambiguation) are pinned only by
the byte-exact round-trip oracle (`PerceptionCodecRoundTripTest.roundTripsExactly`). Round-trip has a
blind spot: a bug that mis-parses *and* mis-encodes identically (symmetric) survives it. The codec has
**no** decode-semantic unit tests — nothing asserts `decode("6")` → the correct numpad position, or
`decode("+4-2")` → `dx=+4, dy=-2`, independent of re-encoding.

**Trigger:** any change to coordinate parsing or the base64 alphabet; or opportunistically alongside
the R3 producer-clamp slice below (same `Coord` surface).

**Anchor:** new `CoordTest` / `Base64CodecTest` in `src/test/java/com/paralife/codec/`; strengthens
`SCHEMA.md` §0 R1/R2.

## R3 producer-clamp uniformity + >±63 reachability investigation

**Why:** `SCHEMA.md` §0 R3 ("relative offset >±63 → clamp before emission") is enforced unevenly. Two
producers clamp (`TickBroadcaster.gatherLocoRelativeCells`, `buildRosterIfChanged`); one does not — the
alarm v-coord producer (`TickBroadcaster.buildEventsForBot`), which feeds raw deltas into the
`Coord.Relative` ctor (whose ±63 guard would then **throw**). The dead `clampRelative` in
`PerceptionCodec` is also unreachable (the `Coord.Relative` ctor already guards ±63 upstream).

**Investigate first:** is a >±63 relative offset even reachable for *any* producer? Solo vision is ≤±2,
but composite SENSOR-stitching and roster spread cross the torus seam (hence the existing clamps). Check
those paths + the alarm delta against the 256×256 toroidal grid.
- **If reachable on the alarm path** → add the same `Math.max(-63, Math.min(63, …))` clamp at
  `buildEventsForBot`, with a `TickBroadcaster` test (far alarm clamps instead of throwing). Real RED.
- **If unreachable everywhere** → delete dead `PerceptionCodec.clampRelative`, tidy `SCHEMA.md` §2/§8.4
  prose to "type-bounded ±63, producer-clamped." No behavioural fix.

**Trigger:** scheduled as the slice after the EARS pilot ships.

**Anchor:** `TickBroadcaster.buildEventsForBot`, `Coord.Relative`, `PerceptionCodec.clampRelative`;
`SCHEMA.md` §0 R3 + §2/§8.4.
