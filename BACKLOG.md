# Backlog

The live backlog for deferred work — the home the GSD graduation left open. Items the code/specs
*reference* but don't yet implement live here, not inline in the specs. (Historical, frozen tech-debt
from the GSD era stays in `.planning/` — read it for facts; new deferrals land here.)

Format: each item names **why deferred**, the **trigger** to pick it up, and the **anchor** (symbols /
§sections, not line numbers — those rot).

---

## HARNESS EARS rollout (deferred, gated)

**Why:** ADMISSION §0 landed (EARS Rollout #2); HARNESS is the deliberate next gate, not this slice.
Scope is mechanism **§2–§6 only** — handshake attribution, the sanitizer regex-reject
(`^[A-Za-z0-9-]{1,32}$`), the `source=harness ⇔ id` invariant, the cardinality cap, the source
taxonomy, the JSON-report atomic-rename, and T-18-04 rebind — ≈6–12 anchored clauses. §1 / §7 / §10
stay tagged non-normative. RUNTIME is **no-go** in the Phase 20 state (the knobs aren't live); revisit
only if Phase 21 makes them load-bearing.

**Trigger:** after one real admission-touching change exercises the ADMISSION §0 merge-back — i.e.
prove the living-spec cadence pays for itself once before extending it (resolves GSD-graduation
open-Q3 with evidence, not assertion).

**Anchor:** new `## §0` on `docs/HARNESS.md`; same two-gate rule (firewall-survivor **and**
test-anchored) as `SCHEMA.md` §0 / `ADMISSION.md` §0.

## ADMISSION §0 anchor-hardening follow-ups

**Why:** ADMISSION §0 carries known gaps the plan review surfaced (see its "Pinning & deferrals"
note). Closing them turns partial/annotated clauses into clean ones and kills the `@slow`-only and
constant-referential blind spots.

- Unit asserts for the 404 `no-active-entity` token (zero tests today), and the `malformed` /
  `grid-full` token *strings* (only their HTTP codes are pinned now).
- Engine-direct unit twins for the `@slow`-only **A14** (`stallRecoveryRebindsEntityIdWithinGraceWindow`,
  `respawnCountRestoredAcrossRebind`) and **A22** (`stallExpiryReapsEntityAndForcesFreshRegistration`)
  so they gate in `./gradlew test`.
- A codec admission-path `E`-frame literal test that locks the rejection token *strings* (the §1
  mapping is pinned constant-referentially; the literal value is not).
- Precedence-isolation tests that **arm the `reservedSlots` cap gate** (`@PostConstruct` doesn't fire
  in the current unit tests) so the maintenance > overload, overload > cap, and rebind > cap edges go
  red on regression — promoting them from prose to clauses.

**Trigger:** opportunistic / next admission-touching change.

**Anchor:** `AdmissionGateTest`, `ResumeTokenRegistryTest`, `WorldWebSocketHandlerTest`,
`PlacementDensityIntegrationTest`, `StallRecoveryIntegrationTest`; new codec `E`-frame test in
`src/test/java/com/paralife/codec/`; strengthens `ADMISSION.md` §0 A4/A6/A14/A22 + the partial/orphan
deferrals.

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
