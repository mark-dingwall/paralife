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

**Total-scrape time budget — ✅ RESOLVED — concurrent `sendAsync` + single shared harvest deadline
bounds the whole scrape (connect+headers+body), not just header receipt (Phase-21 review
remediation).** Originally deferred to 22.1: 7 serial meters × 2s ≈ 14s worst case on the inline
shutdown-hook final write could lose the most-stressed tier's report under a bounded supervisor
kill-grace. The Phase-21 post-implementation review (codex + whole-branch adversarial both flagged
it) took the "overall scrape deadline" fix option: `ServerMetricsScraper.scrape` now fires every
meter request concurrently and harvests each within one shared ~2s budget (`TOTAL_BUDGET`), which
also closes the reporter-VT clobber race (the `join(2000)` drain reliably beats a ≤2s scrape).
*Anchor:* `ServerMetricsScraper.TOTAL_BUDGET`. Off-critical-path scraping (a cached background
snapshot) remains a possible future refinement but is no longer needed for report safety.

**`--actuator-uri` override (deferred).** *Why deferred:* `ServerMetricsScraper.actuatorBaseFrom`
derives the actuator base from `--server-uri` root-only (`ws→http`, `wss→https`); context-path or
`management.port`-separated deployments have no way to point the scraper at the right base. *Trigger:*
a deployment that puts actuator behind a context path or a separate management port. *Anchor:*
`ServerMetricsScraper.actuatorBaseFrom`; `docs/HARNESS.md` §11 "Endpoint dependency."

**`run-tiers.sh` server-lifecycle ownership (deferred).** *Why deferred:* the isolated
fresh-server-per-tier protocol (HIGH-3 fix) is currently manual, per `docs/BENCHMARKS.md`
"Commands run" — `tools/benchmark/run-tiers.sh` still assumes one long-lived server across the
whole sweep. *Trigger:* wanting a one-command isolated sweep instead of hand-run per-tier restarts.
*Anchor:* `tools/benchmark/run-tiers.sh`; `docs/HARNESS.md` §12.

**`scrape()` fail-soft unit coverage (test-debt, partially resolved).** *Why deferred:* `ServerMetricsScraper.scrape()`
holds the hard contract (omit on non-200, omit on exception + continue to next meter, `InterruptedException` →
restore flag + return partial, whole-set budget). The **whole-scrape budget + omit-stalled** leg now has a direct
Mockito unit test (`ServerMetricsScraperTest.scrapeOmitsStalledMeterAndStaysWithinBudget`, added in the Phase-21
review remediation). Still untested: the non-200 (404) omit, the junk-200 parse-omit, and the `InterruptedException`
→ restore-flag-and-return-partial legs — all trivially mockable via the injected `HttpClient`. Also same file:
`actuatorBaseFrom` no-port branch and a bare-`NaN`-token parse case are untested; the public
`ServerMetricsScraper(URI,...)` constructor's load-bearing trailing-slash invariant is undocumented. *Trigger:*
next touch of `ServerMetricsScraper`, or a P22.1 harness-hardening slice. *Anchor:* `ServerMetricsScraperTest`.
(Surfaced by the Phase 21 post-implementation review, 2026-07-04; budget leg closed 2026-07-05.)

**`EncodeDeflatePerformanceGateTest` must be `@Tag("slow")` when re-enabled (firewall, latent). ~~open~~ RESOLVED 2026-07-06 (Phase 22.1).**
*Why deferred:* the test was `@Disabled` so there was no active breach, but `build.gradle.kts:92` excludes only
`@Tag("slow")` from the default gate — `@Tag("performance")` is **not** excluded, so re-enabling as-is would have
flipped a live `p99 < 2×interval-ms` tick-work-aggregate assertion into the default suite (firewall breach).
*Resolution:* re-enabled with `@Tag("performance")`→`@Tag("slow")`; the class Javadoc + an inline comment now pin
the reason to the firewall. Verified: the default `./gradlew test` **excludes** it, `-PincludeLong=true` runs it
green (p99 ~35ms of a 400ms budget). *Anchor:* `EncodeDeflatePerformanceGateTest`; `build.gradle.kts:86-92`.
(Surfaced by the Phase-21 review-remediation final whole-branch review, 2026-07-05.)

**Tick-work regression tripwire — cheap in-test versions don't work; needs the M5 capacity rig (deferred, gated).**
*Why deferred:* Phase 22.1 wanted a cheap, portable approximation of a perf-regression tripwire riding on the
re-enabled EncodeDeflate test. A **same-machine super-linearity ratio** (`meanTickWork(2P)/meanTickWork(P)`, windowed
from the `paralife.tick.work.ms` summary by `totalAmount/count` delta) was built and **backed out** — it is
noise-dominated at this scale and cannot gate honestly. *Evidence (8C16T, warm, bound 3.0):* healthy ratio swung
**1.0–1.42** over 3 forced-fresh runs, with one run at **0.996** (doubling the population measured as *less* work —
physically nonsensical ⇒ signal < noise floor). *Root cause:* the tick is ~3–5ms (11× under a 200ms budget), so the
population-scaling signal is sub-ms and swamped by (a) GC pauses landing in one window, (b) VT-carrier scheduling +
thermal jitter on a wall-clock proxy, (c) stochastic world drift between the two windows (reproduction grows entities
so phase-2 isn't a clean 2× of phase-1), then (d) the ratio amplifies all of it. This empirically re-confirms the
CLAUDE.md firewall: tick-work aggregates are genuinely noise-dominated, not merely tuning-sensitive. *What the real
tripwire needs (M5):* either (i) a big, stable signal — run at 500–1000 bots near the admission cap where the tick is
tens of ms and per-bot cost dominates fixed cost + GC noise (needs the capacity rig), or (ii) a **deterministic signal**
— an op-count / allocation budget on a fixed fixture instead of wall-clock (machine-independent, zero timing noise; a
small production instrumentation hook). Also inherits the absolute-p99 / baseline-diff (`docs/benchmarks/*.json`) gate
and per-slot `@Order` timers from the Phase-21/22.1 discussion. *Anchor:* M5 observability; `TickEngine.tick.work.ms`;
`docs/BENCHMARKS.md`. (Attempted + backed out in Phase 22.1, 2026-07-06.)

## HARNESS EARS rollout — ✅ LANDED 2026-07-07

**Status:** `docs/HARNESS.md` §0 authored with **17** anchored clauses (H1–H17), each pinned to a
verified existing test assertion; normative-layer note + honest-gaps deferral list added. Same
two-gate rule (firewall-survivor **and** test-anchored) as `SCHEMA.md` §0 / `ADMISSION.md` §0.

**Scope-diff vs original estimate:** this item scoped "§2–§6 only, ≈6–12 clauses." Delivered spans
§2/§3/§4/§5/§6/§8 **and §11** (ServerMetricsScraper — Phase 21 mechanism added *after* this item was
written, live + test-anchored, so folded in) at **17** clauses — above the estimate. §1 / §7 / §10
stay non-normative as planned; RUNTIME left out (knobs still not load-bearing). Trigger ("prove the
living-spec cadence once") was satisfied by the SCHEMA R4/R5/R6 merge-back and then user-directed.

**Historical why:** ADMISSION §0 landed (EARS Rollout #2); HARNESS was the deliberate next gate.

## HARNESS §0 anchor-hardening follow-ups

**Why:** HARNESS §0 carries known gaps its "Pinning & deferrals" note records — documented prose not
minted as clauses because no isolating test pins them. Closing them promotes prose to clauses.

- **`BotIdentity.CLIENT_ALLOWED_SOURCES` set membership** — H3 pins the behavioural fold
  (out-of-subset → `unknown`) server-side, but no unit test asserts the constant's contents (contrast
  `BotIdentityTest.sourceTaxonomyContainsExactly5Values` for the full taxonomy).
- **CLI required-flag enforcement** — no test omits `--server-uri` / `--count` and asserts non-zero
  exit; requiredness is implicit. H9–H11 pin value grammars only.
- **`PARALIFE_HARNESS_*` env-var resolution** — `LoadHarnessOptionsTest` pins the `${env:…}`
  annotation string + int type contract, but never sets a real env var end-to-end.
- **`exit_reason` absence on non-final writes** + the **`fatal-error` trigger** — H15 pins presence +
  enum on the final write; no test pins a periodic counter object omits `exit_reason`, and
  `fatal-error` is enumerated but never exercised.
- **ServerMetricsScraper non-200 / thrown-response omission** — H16's fail-soft is pinned via a
  never-completing future (budget) + parse-layer nulls; the explicit `statusCode()==404`/throwing
  branch is untested.

**Trigger:** opportunistic / next harness-touching change.

**Anchor:** `AttributionSanitizerTest`, `BotIdentityTest`, `LoadHarnessOptionsTest`,
`LoadHarnessIntegrationTest`, `ServerMetricsScraperTest`; strengthens `HARNESS.md` §0 H3/H9/H15/H16 +
the deferrals note.

## ADMISSION §0 anchor-hardening follow-ups

**Why:** ADMISSION §0 carries known gaps the plan review surfaced (see its "Pinning & deferrals"
note). Closing them turns partial/annotated clauses into clean ones and kills the `@slow`-only and
constant-referential blind spots.

- ~~Condition→token **routing** asserts for the 404 `no-active-entity`, `malformed`, and `grid-full`
  tokens~~ ✅ DONE 2026-07-11 — ADMISSION §0 **A29/A30/A31** (`WorldWebSocketHandlerTest` unit anchors
  for `no-active-entity`/`malformed` incl. `@SpyBean` not-queued isolation; `PlacementDensityIntegrationTest`
  token-tighten for `grid-full`). Residual: `reconnect-required`/408 routing stays `@slow`-only
  best-effort per D-07 (not yet a clean clause).
- ~~Engine-direct unit twins for the `@slow`-only **A14** and **A22** so they gate in `./gradlew test`~~
  ✅ DONE 2026-07-11 — most of the mechanism was already default-gated (A14 entityId/grace → A10/A13;
  A22 reap-detection → A12; fresh-registration routing → `AdmissionGateTest.unknownResumeTokenFallsThroughToFreshRegistration`);
  the sole gap, A14's **respawn-count restore**, is now pinned by
  `WorldWebSocketHandlerTest.rebindRestoresRespawnCountFromStallSnapshot` (engine-direct, no overflow).
  The `StallRecoveryIntegrationTest` `@slow` anchors stay as the E2E overflow-driven wiring (A22's
  callback-body grid/registry removal remains integration-shaped).
- ~~A codec admission-path `E`-frame literal test that locks the rejection token *strings*~~
  ✅ **DONE 2026-07-11** — ADMISSION §0 **A28** (`RejectionTokenWireTest`) pins all 9 §1 token literals
  in the default suite against independent `E|<code>|<token>` literals (RED-tested by mutating
  `RejectionToken.WORLD_FULL`).
- ~~Precedence-isolation tests that **arm the `reservedSlots` cap gate**~~ ✅ **DONE 2026-07-10** —
  `AdmissionGateTest` arms the cap via `seedReservedSlots()` + `seededCapAloneRejectsWorldFull`
  control; maintenance > overload/cap, overload > cap, rebind > cap now pinned as ADMISSION §0
  A25–A27 (RED-tested by cap-first guard reorder).
- Shape-pin the three `BACKPRESSURE` marker shapes emitted but asserted by no test —
  `held-on-close`, `rebind-stale`, `transport-error-held` (STALLED-lifecycle edge transitions;
  the other 7 of 10 marker shapes are pinned by `AdmissionLogMarkersIntegrationTest` /
  `TickHealthGateIntegrationTest`).

**Trigger:** opportunistic / next admission-touching change.

**Anchor:** `AdmissionGateTest`, `ResumeTokenRegistryTest`, `WorldWebSocketHandlerTest`,
`PlacementDensityIntegrationTest`, `StallRecoveryIntegrationTest`; new codec `E`-frame test in
`src/test/java/com/paralife/codec/`; strengthens `ADMISSION.md` §0 A4/A6/A14/A22 + the partial/orphan
deferrals.

## Post-MVP / M005 follow-ups (ex-SCHEMA §13)

**Why:** folded from `SCHEMA.md` §13 (docs editorial pass, Task 2) — the heading stays as a stub
pointing here. These are M005 / post-MVP **FEATURE** deferrals, not docs housekeeping.

- Precompress fan-out infrastructure (`BroadcastChannel`, `CompressedFrame`) → M005.
- Visualizer UI + observer endpoint → M005.
- Composite rotation, multi-tick gestation, persistent POISONED debuff, Poisson-disk rock generator,
  per-session pseudonym IDs → post-MVP.
- Bot memory / fog-of-war / A* / shadowcasting → post-MVP (curCoords is the foundation).
- FEEDER / ATTACKER / REPRODUCER advanced target-selection heuristics (authority-lite client-side
  brain branches) → post-MVP (MVP ships fallback-auto + server-side dispatch only).
- **`paralife.ws.bytes.saved` metric deferred** — Jetty 12 does not expose per-frame post-deflate byte
  length without reaching into extension internals. Phase 15 ships only `paralife.ws.active.sessions`
  (Gauge) and `paralife.ws.tick.frame.bytes` (DistributionSummary). The bytes-saved Counter lands once
  Jetty exposes a stable post-deflate length hook, or via observer-phase (M005) fan-out
  instrumentation. See plan 15-10.

**Trigger:** M005 milestone start (fan-out/visualizer/bytes-saved items); opportunistic for the
post-MVP feature items.

**Anchor:** `SCHEMA.md` §13 (stub); `WebSocketMetrics.java:26` (bytes.saved javadoc);
`MetricsEndpointIntegrationTest.java:73,79` (deferred-metric assertions).

## Docs housekeeping

**Why:** two note-ref/broken-cite cleanups routed from the docs editorial pass R1/R2 review,
backlog-dispositioned so they land rather than silently dropped.

- *Convert note↔note raw-line refs to section anchors* — `headless-feedback-loop-adr.md:54` cites
  `pelagia-comparison.md:58-60` by raw line range; survives this pass only by position. A future
  re-trim above pelagia L58 breaks it invisibly.

**Trigger:** opportunistic.

**Anchor:** `docs/notes/headless-feedback-loop-adr.md:54`, `docs/notes/pelagia-comparison.md`.

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
