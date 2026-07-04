# Backlog

The live backlog for deferred work — the home the GSD graduation left open. Items the code/specs
*reference* but don't yet implement live here, not inline in the specs. (Historical, frozen tech-debt
from the GSD era stays in `.planning/` — read it for facts; new deferrals land here.)

Format: each item names **why deferred**, the **trigger** to pick it up, and the **anchor** (symbols /
§sections, not line numbers — those rot).

---

## Phase-21 follow-ups

**By-reason rejection scrape.** *Why deferred:* Phase 21's `ServerMetricsScraper` carries the **aggregate**
`paralife.admission.rejected` COUNT only. The by-reason breakdown needs a two-phase actuator call (GET base →
read `availableTags` for `reason` → GET `?tag=reason:<v>` per value) — extra HTTP round-trips for an
observe-only emergence *count*, not needed to close M4. *Trigger:* a consumer that actually needs
per-reason rejection shares (e.g. an M5 dashboard or a tuning assay), **and** the firewall still forbids
asserting those shares in the default suite (they stay report-only). *Anchor:* `ServerMetricsScraper.scrape(...)`,
`ReportSnapshot.BENCHMARK_METER_NAMES`; the `reason`+`source` tags on `AdmissionMetrics` `paralife.admission.rejected`.

**Total-scrape time budget (→ Phase 22.1).** *Why deferred:* `ServerMetricsScraper.scrape(BENCHMARK_METER_NAMES)`
GETs 7 meters serially, each bounded to 2s, so worst case ≈14s — and it runs inline on `LoadHarness`'s
crash-safe shutdown-hook final write and every periodic write. In practice absent meters 404 fast (not a 2s
timeout) and the write is `try/catch`-wrapped, so a benign run scrapes in well under a second; the worst case
only bites if a genuinely hung server stalls all 7 meters — plausible under 1000-bot overload. P21 ships the
plan-mandated inline design. *Trigger:* if the sync stall is observed to degrade report cadence or the
shutdown write under real overload capture. *Fix options:* an overall scrape deadline (budget across meters),
or run the scrape off the reporter/shutdown critical path. *Anchor:* `ServerMetricsScraper.scrape(...)`,
`LoadHarness` report-assembly path. (Surfaced by the Phase 21 whole-branch review, 2026-07-04.)

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

## Headless feedback-loop + emergence testing (Pelagia harvest)

**Orientation for future-you — read this before the items.** In mid-2026 we investigated *Pelagia*
(a similar-ish sim, `docs/notes/pelagia-comparison.md`) to see what ports here. It produced one ADR
(`docs/notes/headless-feedback-loop-adr.md`) and a cross-project synthesis. Two source claims matter:

1. **You don't need pixels to measure — you need a deterministic *numeric* signal.** Paralife already
   *measures* death causes (`DeathDiagnostics`, flag-gated) where Pelagia only infers them. This
   refuted an over-broad reading of the `balance_tuning_deferred` stance: *measuring* emergence was
   never blocked, only *tuning* to it.
2. **The firewall sharpening (the durable keeper):** a death-cause **label** is pinnable **mechanism**
   (`WHEN a combat sink drives energy==0 THE SYSTEM SHALL attribute cause=COMBAT`); a death-cause
   **count / share** is **observe-only emergence** (most tuning-sensitive scalar in the sim — retune
   `decayPerTick` and it moves). Emergence *is* testable, but **only tuning-invariant, control-anchored
   ratios** (ordinal "evolved forages ≥3× random", not cardinal "starvation==78%"), and only in
   `@Tag("slow")` — never the default gate. This is why the assay ports and the death-share doesn't.

**Two do-now precursors — NOT filed below (they aren't deferred):** *(a)* bank the firewall label-vs-count
doctrine + the class-ban into `CLAUDE.md` §Testing philosophy + a memory; *(b)* ratify the ADR (direction
only — it never converged; ratify the *shape*, not the 5 escalations) and harvest both notes into
`ROADMAP.md`/here before they rot (they're untracked). Do these first; the items below assume the doctrine
exists.

The launch question — *"is a headless feedback **loop** available today?"* — the ADR left open. It answers
only the cheap *measurement* half (yes). The *loop* half is what **B1 (path-alpha)** actually settles.

### B1 · path-alpha — prove the open-loop link exists

**Why:** the one piece of new evidence the whole 2.5M-token exercise pointed at and *skipped*. Flag on →
read per-`[cause][type]` counts → bump **one** constant (`decayPerTick`) → re-read → diff. If the number
moves, the actuator→metric signal path is live — upgrades "a number exists" to "the feedback signal is
live." Observe-only, manual, **discard the result, never commit it** → not tuning (no constant selected to
optimise), so firewall-clean and compatible with the M5 tuning deferral.

**Scope guard (important):** this proves the **open loop** (actuator→metric) *only*. It reads a 1-D
cause-share and is **structurally blind to spatial collapse** — you can move the scalar while flattening
the spiral-wave/niche emergence that is the Core Value, and the read still says "success." So it does
**NOT** bear on the tune-pre-M5 hazard (see the #5 cross-link at the end). Existence proof, not a
safe-to-tune proof.

**Trigger:** opportunistic — whenever you want the loop-buildable claim settled by evidence, not debate.

**Anchor:** `paralife.diagnostics.death-trace.enabled` flag; `DeathDiagnostics.java:97-102` (the per-cause
counter); `MetabolicProfile` `decayPerTick`. *Effort: S (throwaway read).*

### B2 · Cheap always-on death-cause gauge

**Why:** if we ever want the cause signal always-on (not flag-gated), the *counting* is already cheap —
`causeCounts` is a `LongAdder` map (`DeathDiagnostics.java:53,97`). The always-on tax is elsewhere and
must be stripped: the per-death Micrometer **re-registration** (`Counter.builder()…register()` rebuilds
Id+tags+lookup *every death*, `:98-102`), the per-death `log.info` (`:105`, the dominant cost), and the
lifespan **census** maps (`birthTick`/`preHitEnergy`, `:50,52`) a cause gauge doesn't need. Lighter shape
= register **one** `Gauge` over `causeCounts`, drop the rest.

**Trigger:** **gated** — only when the tuning campaign (B4) is authorized. This is the only PR that can
violate the class-ban, so it carries that ban.

**Notes to carry:** write-side is single-writer (`recordDeath`, tick-thread) → volatile-array / VarHandle
publish for the read; add a **kind dimension** (free / composite / BONDED) or inherit the mixed-taxonomy
confound (`BONDED` spans two species, unapportionable); value is cumulative → delta-between-scrapes. The
javadoc "negligible" (`:37`) scopes to `hintLethal`, **not** `recordDeath` — the per-death cost is real
when enabled (fine at the 500ms prod interval; only bites at compressed harness intervals).

**Anchor:** `DeathDiagnostics.java:50-53,88-107`; `MeterRegistry`. *Effort: M.*

### B3 · Paired in-process synchronous bot-driver

**Why:** robust A/B (not a single-seed fluke) needs paired runs — identical worlds varying one knob. The
named `@Tag("slow")` `EmergenceStabilityLoadTest` **cannot pair**: live-WS action-delivery timing is an
unseeded 8th source the GoldenTrace gate doesn't cover (Cov≈0 at zero perturbation), so a paired delta on
it is noise. A genuinely paired loop needs a new synchronous in-process driver.

**Trigger:** **YAGNI** — build only once **ensemble-N (B4) is *proven* too noisy** to read the signal. Not
on spec.

**Anchor:** `ActionResolver.java:158,353` (action drain timing); `BotFactory.java:44` (unseeded prod bots);
new driver beside `com.paralife.harness`. *Effort: M.*

### B4 · Ensemble-N tuning campaign

**Why:** the actual perturb→measure→compare→adjust loop over N seeds, diffing distributions (not one
chaotic run vs another).

**Trigger:** **gated** — Phase 21 landed (density/timing stable, else you tune a soon-stale world) **AND**
a Core-Value guard exists (M5 visualiser is the default guard; a headless spatial-emergence invariant is a
possible substitute — don't nail the lift to M5-the-artifact, tie it to M5's *function*). Matches the
`balance_tuning_deferred` stance.

**Anchor:** `SimulationConfig` / `MetabolicProfile` (the knobs); `EmergenceStabilityLoadTest`;
`com.paralife.harness`. *Effort: M+ (campaign).*

### E1 · Competence-vs-random foraging assay — harness scaffold

**Why:** the firewall-safe *emergence-test* shape (Pelagia's `evaluateGate`/`assayForaging`): an **ordinal
ratio** (`evolved/random ≥ K` across `≥X%` of seeds) in a frozen-lifecycle arena, seed-folded. Tuning-
invariant because the random baseline lives in the *same* retuned world — both arms shift together, the
ratio survives. Buildable **now** with `HeuristicBrain` as the subject: a real `@slow` regression guard
("the brain forages better than a flailing random bot") + de-risks the v4.0 scaffold.

**Caveat:** heuristic-vs-random straddles mechanism/emergence (the heuristic is deterministic logic) — the
reusable asset is the **construction** (arena + ratio-vs-control + ensemble), not the learning claim.
Moderate value. **Hard rule:** `@Tag("slow")` only, never the default gate; ordinal/relative only — the
instant someone rewrites `≥3× random` as `rate ≥ 42` it's a cardinal and back behind the firewall.

**Trigger:** opportunistic, or when v4.0 neural scaffolding starts.

**Anchor:** new `EvolvedVsRandomForagingTest` `@Tag("slow")`; `HeuristicBrain.decide(...)`; needs a
headless subject-injecting driver. *Effort: M.*

### E2 · Foraging assay as real selection signal

**Why:** the assay only measures *learning* once there's an evolved subject that can beat random.

**Trigger:** **gated** — downstream of MLP + genome inheritance (v4.0 core-value work). Cannot build first.

**Anchor:** E1 harness + `bot/NeuralBrain`, `MutationConfig`. *Effort: M (on top of v4.0).*

### S1 · Long-run invariant harness (default suite)

**Why:** seeded `WorldGrid`, N ticks, assert **no double-occupancy, energy≥0, toroidal wrap, pop ≤
admission cap**. Pure mechanism, EARS-phrasable, closes the gap between unit tests and `@slow`. Enabling
substrate for the assay's determinism.

**Watch:** resist drifting a bound into emergence — `pop>0` **re-crosses the firewall** (the treadmill
legitimately empties the world). Invariants only, never population targets.

**Anchor:** `WorldGrid`; new `WorldInvariantTest`. *Effort: S.*

### S2 · World-state checksum oracle

**Why:** `WorldGrid.stateChecksum()` (FNV-1a over cells) decouples the determinism oracle from the
wire / VT / mock-session machinery (drops `OutboundSender`, mock sessions, the VT-drain barrier). Int
energy → no float-fold fragility.

**Caveat:** cross-run reproducibility still needs seeded-bean reset (only lands with the RNG rewrite);
single-run / GoldenTrace use works today.

**Anchor:** new `WorldGrid.stateChecksum()`; `GoldenTraceEquivalenceTest`. *Effort: S.*

### Dependency shape + the #5 cross-link

```
firewall doctrine (do-now precursor) ─ everything below references it
    ├─ B1 path-alpha        (independent, opportunistic)
    ├─ B2 cheap gauge ──── B4 campaign ─┐
    ├─ B3 paired driver ───────────────┤ (gated: Phase 21 stable + Core-Value guard)
    ├─ S1 invariants ─┐
    ├─ S2 checksum  ──┴─ E1 assay scaffold ── E2 real assay (gated: v4.0 genome)
```

Zero-gate / actionable now: **B1, E1, S1, S2** (+ the two do-now precursors). Everything else waits on a
real trigger.

**The #5 open question (non-blocking):** *should the tuning deferral ever lift before M5?* Its hazard is
**not** "can the number move" (B1 settles that) — it is that a 1-D scalar can be *hit while silently
destroying spatial emergence*. Its unlock is therefore a **spatial-emergence discriminator** — M5's eyes,
or a headless tuning-invariant spatial-structure test (the E1 family) — **never any 1-D death read.** Stays
open, non-blocking, M5-gated; matches `balance_tuning_deferred`.
