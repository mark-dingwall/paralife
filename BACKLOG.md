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

## Codec decode-semantic unit tests (CoordTest / Base64CodecTest) — CLOSED (done)

**Resolution:** `Base64CodecTest` and `CoordTest` landed in `src/test/java/com/paralife/codec/`,
closing the R1/R2 symmetric-bug blind spot. R1 (`Base64CodecTest`) asserts `decodeDigit`/`encodeDigit`
across all 64 indices against a **test-owned** alphabet literal (not `Base64Codec.ALPHABET`), plus
invalid-char/out-of-range rejection. R2 (`CoordTest`) asserts the decode direction via `decode()` of a
minimal hand-authored frame — numpad/relative dispatch, the positional `-`-as-digit-63 subtlety, the
absolute-positional-only invariant, and first-char rejection (numpad/relative cases as positive
controls). `SCHEMA.md` §0 R1/R2 re-anchored to these (round-trip kept as joint backstop). The
investigation found **no** hidden symmetric bug — code was correct; the value was converting two
oracle-shared clauses into clause-isolating ones. Non-vacuity proven by mutation (alphabet reorder →
5 reds; relative-sign drop → reds).

## R3 producer-clamp uniformity + >±63 reachability investigation — CLOSED (unreachable)

**Resolution:** investigation found a >±63 relative offset is **not reachable for any producer** in the
current feature set, so the "unreachable everywhere" branch applied — dead `PerceptionCodec.clampRelative`
removed, `relativeTo` javadoc corrected, `SCHEMA.md` §0 R3 / §2 / §8.4 prose tidied. No behavioural fix,
no RED. Evidence chain: composites are always **exactly two adjacent members** (`compositeRegistry.register`
is called only at `SimulationEngine` formation with `List.of(memberId1, memberId2)`; `addMember` is never
called in production, SENSOR is never assigned at formation); D-01 forms them from two adjacent BondedPairs
(spread ≤1); rigid-body movement (`executeCompositeMovement` applies one `dir` to every member) preserves
that ≤1 spread. The alarm cell is the raiser's own position (`ActionResolver.handleAlarmAction`), so the
alarm offset is ≤±1 → `coordFor` always emits numpad, never constructing `Coord.Relative`. The two
producer clamps (`gatherLocoRelativeCells`, `buildRosterIfChanged`) and the SENSOR-stitch s-block are
forward-defensive (dormant: no SENSOR role today).

**Forward trigger (latent gap):** if composites ever grow past two members or gain SENSOR-stitching, the
alarm producer (`TickBroadcaster.buildEventsForBot`) — the one path with no clamp and no numpad guarantee —
will feed >±63 deltas into the `Coord.Relative` ctor and **throw**. The stopgap is the same
`Math.max(-63, Math.min(63, …))` clamp there, with a `TickBroadcaster` RED. The `relativeTo` javadoc flags
this inline.

**Forward design option — "bearing-only" coord (instead of clamping).** Clamping to ±63 *lies*: it reports a
definite cell at the box edge that isn't where the thing is. A better long-term form, if the ±63 limit ever
genuinely bites, is a direction-known / range-unknown coord — an emergent long-range sense ("something
happened far off, that way; you can't tell how far"):
- Draw the vector from the entity to the target cell (toroidal-shortest, as `relativeTo` already does).
- Take the point where that vector crosses the **circle of radius 63** centred on the entity.
- Emit those coords with `^`/`v` sign markers instead of `+`/`-`, in the same 4-char `<sign><digit><sign><digit>` shape as the relative form — magnitudes are single **base64** chars, not decimal. E.g. a bearing of `(30, 40)` is `^Uve` on the wire (`U` = base64 30, `e` = base64 40).

Properties / caveats to carry into any implementation:
- **Semantics, stated loud:** under `^`/`v`, X and Y encode a **bearing** (the point `(63·cosθ, 63·sinθ)`),
  **NOT distance**. Decoders must never read them as displacement — the form is deliberately non-invertible
  to a position (that *is* "unsure how far"). Multiple distant cells collapse to one bearing code by design.
- **Scope is a full wire-protocol change**, not an encoding tweak: a new `Coord` variant/flag + `PerceptionCodec`
  encode/decode + the §2/R2 first-char dispatch + SCHEMA §2/§8 + **every decoder** (`HeuristicBrain`). Spec +
  EARS + TDD tier.
- **Geometry is clean:** projecting onto the *circle* r=63 guarantees `|x|,|y| ≤ 63`, so both magnitudes always
  fit the 1-char base64 slot — no overflow (the ±63 *box* corner is at distance ~89; the inscribed circle sits
  inside it). Parsing stays positional (the sign sits at fixed offsets, exactly as `-` already coexists as both
  a sign and a base64 digit).
- **Glyph choice:** `^` is clean (not in the base64 alphabet, not a block tag); `v` is triple-booked — it is the
  events-block tag, base64 digit 57, *and* the proposed sign. Unambiguous positionally but a grep/readability
  hazard; consider a second glyph outside the alphabet, e.g. `~` (`^X~Y`). Reuse the existing form's `+0`/`-0`
  zero-on-axis convention.
- **The real gate is a design decision, not the codec:** should entities get long-range directional sensing at
  all? Today solo vision is ±2; this adds a "sense something far off" channel — a perception-model change that
  (per the GUI-gated tuning stance) wants visual feedback to judge before it ships.

**Anchor:** `TickBroadcaster.buildEventsForBot` (+ its `relativeTo`), `Coord.Relative`; `SCHEMA.md`
§0 R3 + §2/§8.4.
