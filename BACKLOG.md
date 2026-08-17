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
  token-tighten for `grid-full`). Residual `reconnect-required`/408 routing ✅ DONE 2026-07-18 —
  ADMISSION §0 **A32** (`WorldWebSocketHandlerTest.stalledSessionInboundRejectedWithReconnectRequired`,
  408-vs-404 payload discrimination on two frame kinds; the 408 send remains best-effort per D-07 —
  the *routing* is now clause-pinned, not the wire delivery). §0 handler-emitted routing sweep complete.
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

---

## Observer visualiser (M5-A) follow-ups

### Observer exposure hardening (prerequisite for public deployment)

The M5-A observer visualiser (`/ws/observer`) ships `enabled=false` + a session cap
only. Before ANY authenticated/public exposure: real auth/authz, non-wildcard origin
policy, and handshake rate-limiting. Until then the endpoint exposes full-world state
(which the bot path deliberately vision-scopes) and must stay operator-only.

### Observer bounded viewport, zoom/pan, and an explicit render budget

Slice A renders the whole world directly at a 6px pitch, sized for the default 256×256
grid. Two independent triggers activate this item — either one is sufficient:

1. **Interactive navigation or a larger world.** Any work that begins zoom/pan gestures, or
   that claims observer support beyond the default 256×256 target.
2. **A measured render cost.** The page shows an observe-only render duration next to the
   tick (R11). If that figure consumes a material fraction of the configured tick interval
   at the *default* grid size, this item is live regardless of trigger 1. This second
   trigger exists because the default world can saturate on its own: a measured late-run
   default configuration held 32,016 rocks, 45,559 mutagen cells, 21,049 toxin cells and
   25,311 nutrients — roughly 124,000 fill operations per frame. A grid-size-only trigger
   would never fire on the case actually measured.

**Trigger 2 has already fired.** Slice A replayed that saturated load through the shipped
renderer in Chrome (~128k fill operations, 1537×1537 backing store): **93–268 ms per frame**
across five runs, against the default 500 ms tick — 19–54% of a tick spent painting, on a
desktop machine. A live early-run frame (3,813 entities, empty env field) cost 8–19 ms, so
the cost is dominated by the environment and nutrient layers, not by entity markers. This
item is therefore live on measurement alone, independent of any zoom/pan work.

**Caching the static layers is not the fix.** Background, grid lines and rocks never change
after bootstrap, so they can be painted once into an offscreen buffer and blitted each frame.
Counting real frames through the shipped `drawWorld` shows why that does not solve this item:
on a live world (tick 78–207, 3.4k entities, ~3.2k toxin cells, no mutagen) the static prefix
is **83% of the frame's fill operations** — but that frame already renders in 11–20 ms
(median 15 ms, 50 samples in Chrome), so there is nothing to win. On the saturated load that
actually fired trigger 2, the same prefix is only ~25% of ~128k operations; the remaining 75%
is environment and nutrient cells, which change every tick and cannot be cached. A static-layer
cache would take the 268 ms worst case to roughly 200 ms — still a fifth of the tick. The
layer that must shrink is the one that changes, which is what a bounded viewport does.

Those two shares were both measured at the old `density-threshold: 128`, which placed 32,224
rocks. The default is now 185 (5,952 rocks), so the cacheable prefix has fallen to roughly 6,500
operations — about 6% of a saturated frame. The conclusion holds a fortiori: there is even less
to win from caching than the figures above suggest. The saturated wall-clock numbers themselves
have not been re-measured since the density change and will have improved somewhat.

**Re-measured 2026-08-02, at `density-threshold: 185`, during the Slice B visual pass.** An
operator watched a `bootRun` session to full environment saturation — mutagen covering every
cell, toxin widespread, most remaining cells rocks or nutrients — and observed a **40–60 ms**
worst case against the 500 ms tick (8–12%), versus the 93–268 ms recorded above. The rock
reduction accounts for most of it. Two caveats on reading this as headroom: the world held
**zero entities** (no bot clients were connected, so the marker layers were empty — see
`observer-render.js`'s entity loop, the one layer that scales with population), and the toxin
figure was inflated by the permanent-stain bug recorded below, which paints intensity-1 cells
forever. Trigger 2 remains fired on the original measurement; this narrows the gap rather than
closing it, and the item stays live pending a saturated-env-plus-full-population figure.

Work: a bounded or tiled viewport with zoom/pan, plus a stated render budget. Reintroducing
an **offscreen full-world buffer is the first move** once panning is in scope — panning under
direct rendering repaints the entire world every pan frame, whereas panning over a buffer is a
blit. The seam already exists: `drawWorld(ctx, state)` takes its context as an argument, so
this means creating a buffer and passing its context, touching no marker or layer-order code.
It was deliberately NOT shipped dormant in Slice A (an unused transform had already produced an
odd-dimension centering defect in review). Also deferred here: composite role glyphs, which
need more than a 5px content square.

### Observer UI headless-browser JS smoke

`observer.html` render fidelity is judged by eye (the stack has no browser-test harness).
The frame contract is covered automatically by `ObserverEndpointIntegrationTest` (real
handshake + Jackson parse), but the page's own JS (`JSON.parse` → canvas render → `#status`
tick signal) is not executed by any test. When a headless-browser harness is justified
(htmlunit for JVM-only, or Playwright for real canvas), add a smoke that loads the page,
completes the observer handshake, and asserts `#status` shows a tick — RED-tested with a
deliberate JS error. Deferred per the M5-A review (2026-07-19); not blocking MVP.

## Environment persistence defects (found 2026-08-02 by observer visual pass)

Findings from the first two visual sessions on the Slice B panel. The observer did not cause any
of them — it made pre-existing engine behaviour visible for the first time, which is the point of
building it. Verdicts are from static-read investigation; each cites the line that decides it.
**None is fixed.** E-1..E-4 came from session 1, E-5..E-9 from session 2.

### E-1 · Toxin never reaches zero — every event leaves a permanent stain

`CellularAutomaton.java:61` computes `(int) Math.round(mixed * (1.0 - decayRate))`. With the
configured `decayRate = 0.1` (agreeing in `application.yml:188` and `EnvironmentConfig.Toxin
.defaults()`), a locally uniform value `v` decays to `round(0.9v)`, which **equals `v` for every
`v` in 1..5** — `Math.round` is half-up, so even `round(4.5) = 5`. Intensities 1–5 are fixed
points. The `if (after < threshold) after = 0` clear on the next line uses a hardcoded
`threshold = 1` (passed at `EnvironmentEngine.java:468`), so it only zeroes cells that already
rounded to 0; it never breaks the plateau.

Reproduced numerically against the production loop (64×64 torus, one 255 stamp, real
parameters): by tick 10 the stamp has flattened to 49 cells at intensity 1, and at tick 400 it
is still 49 cells at intensity 1. Each toxin event therefore unions a permanent radius-3 Moore
stain onto the map. It is permanently *visible* because the snapshot (`EnvironmentEngine.java
:1076`) and the renderer (`observer-render.js:31`, `alpha = 0.15 + 0.6*(i/255)`) both draw
anything `> 0` — intensity 1 paints at alpha 0.152.

Secondary cost: `advanceToxin`'s idle short-circuit (`EnvironmentEngine.java:439`) tests
`nonZeroToxinCellCount`, which can never return to 0 once any toxin has spawned. The full
O(W·H·nonzero) CA sweep therefore runs every tick for the life of the process.

Fix is a code change in `CellularAutomaton.diffuseStep` — guarantee strict monotonic decay when
`decayRate > 0` (floor instead of round, plus a `self - 1` fallback when the result fails to
descend). Raising the hardcoded `threshold` literal is smaller but clamps the symptom rather
than fixing the arithmetic. No config-only fix: `decay-rate >= 0.6` would clear value-1 cells
but guts toxin lethality and still rides the rounding boundary.

**Mechanism, not emergence** — "toxin intensity strictly decreases each tick while decayRate > 0"
is an EARS-shaped invariant and should be pinned. The resulting *coverage share* is emergence
and must not be.

**Resolved** (`5b059e0`, EARS-1/EARS-2). `CellularAutomaton.diffuseStep` now floors the decay
(`Math.floor`, not `Math.round`), so the grid maximum strictly descends every tick and an
undisturbed field reaches all-zero within a bounded number of ticks (the 49-cell stain clears by
tick 7). Floor alone suffices — no `self - 1` fallback — because `floor(mixed·(1−d)) < M` for any
`d > 0`, `M ≥ 1` (proof in the frozen plan, Task 1).

### E-2 · Mutagen blooms are unbounded and ratchet across outbreaks

`EnvironmentEngine.advanceMutagen` (`:565-627`). Two independent defects:

1. **No radius cap and no intensity decay.** While an outbreak is active, every non-zero cell
   gossips outward each tick. There is no distance-from-origin test anywhere, and the strain
   byte is only copied (`:602`), never attenuated — the optional ±1 mutation is a *hue* drift,
   not a magnitude. The sole brake is `outbreak-lifetime-ticks: 300`.
2. **The bloom ratchets.** The gossip loop at `:581-583` iterates every non-zero cell in
   `mutagenGrid`, not just the active outbreak's descendants, and `spawnMutagen` (`:536`) stamps
   a new origin without clearing the grid. Any surviving legacy zone becomes a full-strength
   gossip source the moment the next outbreak begins, so the bloom grows from its whole existing
   perimeter rather than from a fresh point. Zone decay (`:612-626`) only runs when
   `activeMutagen == null`.
3. **The whole field clears in one tick, which reads as a visual glitch.**
   `mutagenLastReinforcedTick[nx][ny]` is written at `:603`, but `:592`'s
   `if (existingStrain != 0) continue` short-circuits first — so a cell's timestamp is stamped
   once at colonization and never refreshed. Every cell in a bloom therefore ages out within a
   few ticks of its neighbours, and once the outbreak ends the entire field vanishes together
   instead of receding. Fix travels with the decay model: attenuate intensity per tick and draw
   alpha from it, so the bloom fades rather than being switched off.

Smallest fixes, independent of each other: gate gossip on a new `maxRadius` against the origin
already carried on `MutagenEvent` (~3 lines at `:586-593`); and source the gossip loop only from
cells belonging to the active outbreak, e.g. `mutagenLastReinforcedTick[x][y] >= activeMutagen
.startTick()` (one condition at `:583`). No config-only fix — lowering the lifetime shortens each
bloom without touching the cross-outbreak accumulation.

Not statically determinable: whether the observed session actually crossed the 300-tick lifetime
plus 50 quiet ticks needed for full clearance. The ratchet holds either way.

**Resolved** (`77f4e99` + `fa17c26`). Defect 1 (unbounded radius): gossip is now capped at a new
`max-radius` by toroidal Chebyshev distance from the outbreak origin (`77f4e99`, EARS-3). Defect 2
(cross-outbreak ratchet): the gossip loop sources only cells colonized at or after the active
outbreak's `spawnTick` (`77f4e99`, EARS-4). Defect 3 (whole-field one-tick clear): zone decay now
runs every tick rather than only when idle, so blooms age out rolling rather than vanishing together
(`fa17c26`, EARS-5). Intensity-attenuation redraw was **not** taken (out of scope — see E-3).

**Follow-up (observer tuning): the `max-radius` cap was dropped.** The Chebyshev cap drew a hard
square and needed an arbitrary magnitude. It is replaced by a per-outbreak **grow-window**: the
bloom gossips outward for a random `grow-ticks-min..max` (default 30..60) ticks, then the front
freezes — a time bound that is now the natural size cap (and tunable, later, from the observer
controls). Defect 1 stays resolved via a different mechanism; EARS-4/EARS-5 are unchanged.

### E-3 · Mutagen bloom shape is a near-solid diamond, not a ragged front

Wanted: a more irregular, organic bloom. The current shape is 8-neighbour Moore with
`gossip-probability: 0.3` rolled per neighbour per tick, and `if (existingStrain != 0) continue`
at `:592` means a cell is written once and never revisited. Because the roll re-fires every tick
against a persistent frontier, gaps fill within a few ticks — the frontier advances ~0.3 cells
per tick in Chebyshev distance and the interior is solid, leaving only a thin ragged rim.

Raggedness will not come from changing the neighbourhood. It needs one of: per-cell
susceptibility (weight the probability by fertility or terrain), a one-shot infection roll per
neighbour instead of a repeating one, or an anisotropic / noise-modulated probability field.

**Emergence, not mechanism** — bloom shape is tuning-sensitive and cannot be phrased as an EARS
clause. Per the constitution clause it gets no default-suite test; judge it by eye on the
visualiser, and pin at most a tuning-invariant ordinal ratio in the `@Tag("slow")` suite.

**Partially addressed (MVP, observer tuning).** The grow-window (see E-2 follow-up) freezes the
Moore front mid-advance, so the bloom stops while its frontier is still ragged instead of filling to
a solid square. Good enough by eye for now; the *mechanism* pinned is only "gossip stops after
`growTicks`" (`MutagenGrowthTest`) — the raggedness itself stays observe-only emergence. A genuinely
organic front (per-cell susceptibility / one-shot rolls) is still the fuller fix if wanted.

### E-4 · No way to watch life without running bots

Not a defect — recording it because it cost an operator two confused sessions. Particles exist
if and only if a WebSocket client registered one: `WorldWebSocketHandler.java:619` is the sole
spawn site, with reproduction (`ActionResolver.java:685/704/949`) and composite dissolution
(`SimulationEngine.java:1349`) the only other constructions. Startup places rocks
(`RockGenerator.java:110`) and fertility only; nutrients arrive per-tick. No `initial-population`
key exists anywhere in `src/main`.

So a bare `bootRun` renders a lifeless world forever. The operator workaround is a second shell:

```
./gradlew runBot --args="ws://localhost:8080/ws/world 100"
```

Positional args are `<server-uri> <count 1..100> [duration-seconds]` (`BotRunner.java:44-53`,
capped at `MAX_BOTS`); omit the duration for a visualiser session so it runs until Ctrl-C.

If "start the server and watch life" is wanted as a first-class mode, it is a code change — an
`ApplicationReadyEvent` seeder placing N particles through the existing `EligibleCellIndex`
path, gated on a new key defaulting to 0. Note that unowned particles have no brain, so they
would decay and never act; the fuller version is an auto-started in-process bot fleet.

### E-5 · Bots cannot perceive the residual toxin field, and mostly ignore the rest

Observed: a Membrane bot repeatedly walking straight through visible toxin. Three independent
deciders, in firing order — fixing any one alone changes nothing:

1. **The bit is never set for the residual field.** `EnvironmentEngine.java:966` sets
   `CELL_STATUS_TOXIN_PRESENT` only when `intensity >= intensity-threshold` (`20`,
   `application.yml:191`). E-1's permanent stain sits at intensity `1`. The renderer paints
   anything `> 0`, so the operator sees a hazard the bots are never told about. **E-5 is
   downstream of E-1 — fix E-1 first, then re-observe.**
2. **Avoidance is gated on near-starvation.** `HeuristicBrain.java:154/161/331` all read
   `if (!(lowEnergy && toxic))`, with `lowEnergy` = energy < 30% of max
   (`TOXIC_AVOIDANCE_ENERGY_FRACTION`, `:73`). Above 30% the bit is decoded into a local and
   never branched on. A healthy bot is *designed* to ignore toxin.
3. **It is exclusion, not repulsion.** Those three sites only drop toxic cells from candidate
   lists. The flee, chase, and fallback-walk branches never consult `cellStatus` at all, so a
   bot fleeing a predator or chasing prey will cross toxin regardless of energy.

Decide intent before coding: (2) may well be deliberate ("desperate bots take risks"). (1) is a
plain defect once E-1 is fixed.

**Resolved** (`5b059e0`, EARS-2) — **no production change of its own**. Decider (1), the only
in-scope defect, existed *because* of E-1's residual stain: the `TOXIN_PRESENT` bit was correctly
gated on `intensity >= threshold`, but E-1 left a permanent intensity-1 band below it. With E-1
fixed the field decays to 0, so no sub-threshold band lingers and the bit is absent for the right
reason. EARS-2 pins that the band is transient. Deciders (2) the 30%-energy avoidance gate and
(3) exclusion-not-repulsion were left exactly as they are by user decision — deliberate behaviour,
not defects.

### E-6 · Bonding and composites are statistically unreachable at current defaults

Observed: no bonded pairs or composites in two long sessions. Both are passive engine scans
(`SimulationEngine.java:456-462` and `:632-658`) — no bot verb is required, so this is not a
brain gap. (`V` is a locomotor ballot, not composite formation.)

The blocker is the energy gate: bonding needs **both** partners at ≥ `bond-energy-threshold: 50`,
and children spawn at `childStartEnergy() = maxEnergy / 2` (`MetabolicProfile.java:93`):

| species | child E | decay/tick | nutrient gain | net per eat-tick | combat win |
|---------|---------|------------|---------------|------------------|------------|
| CATALYST | 40 | 3 | 3 | **0** | +15 |
| SPORE | 30 | 2 | 5 | +3 | +8 |
| MEMBRANE | 60 | 1 | 8 | +7 | +5 |

All three legal bond pairs (C→S, S→M, M→C) need a Catalyst or Spore at ≥50. **A Catalyst child
cannot get there by feeding at all** — gain 3 exactly cancels decay 3 — so its only route up is
winning combat. Spore needs ~7 net eat-ticks. Then a 0.10 roll. Composites need two such pairs
adjacent — roughly the square of an already-small rate.

Config-only fixes exist (lower the threshold, or raise Catalyst's `nutrientConsumeEnergy` to 4).
This is **balance tuning, not a defect** — it belongs with E-9, not ahead of it.

### E-7 · The solo attack verb is a no-op, so bot combat intent is discarded

`ActionResolver.java:509-512` handles `case 'A'` by incrementing `restCount` and nothing else.
`HeuristicBrain.java:199` emits `A` whenever prey is at distance 1. So the brain's one offensive
decision is dropped every time, and *all* combat is the passive engine scan.

Consequence, given E-6: Catalyst's only energy route up is combat, and combat is something it
cannot choose to do. This is the strongest single candidate for E-9's extinction ordering.

Not obviously a bug — the comment says composite-`A` dispatch was Phase 3 work and solo-`A` was
left equivalent to rest deliberately. But brain and resolver now disagree, which is a real seam.
Cheapest honest fix is to stop emitting `A` in the brain; the interesting one is to make it do
something.

**Resolved** (`3dd1aee`, EARS-6). The `HeuristicBrain` chase branch now emits `M` toward adjacent
prey, never solo `A`. Chosen scope was the cheap honest fix (stop emitting `A`); making `A` a real
bonus attack was rejected as a balance change layered on the untested E-9 hypothesis.
`ActionResolver`'s `case 'A'` is unchanged — it stays correct for the composite path.

### E-8 · Lightning fires as configured but is unobservable

Not a spawn bug — confirmed empirically, not inferred. A bare `bootRun` logged 5 strikes in the
first 257 ticks (~2 min), via `EnvironmentEngine.java:1126`:

```
Lightning strike: tick=48  center=(246,2)   inner=2 outer=4 damage=40 fertility=25 fleeing=8
Lightning strike: tick=55  center=(209,42)  ...
Lightning strike: tick=212 center=(222,186) ...
Lightning strike: tick=249 center=(200,201) ...
Lightning strike: tick=257 center=(227,145) ...
```

Consistent with λ = 0.04 at summer peak / 0.005 off-season over a 200-tick year
(`application.yml:165-168`).

It is invisible because `EnvironmentEngine.java:370` clears `lightningStrikesThisTick` every
tick, so a strike appears in **exactly one 500ms frame** — one 6px square on a 1536px canvas.
The wire also carries only the centre `Position` (`:1184`), never `outer-radius: 4`, so the
rendered mark is 1/81 of the area actually damaged.

Two independent fixes: hold the strike in the observer frame for N ticks (or fade it client-side),
and put the radius on the wire so the affected disc is drawn. Renderer-side persistence alone is
enough to make it visible.

**Resolved** (`2d6fa01` + `a858dc4`) — both fixes taken (user chose persistence + radius on wire).
E-8a (`2d6fa01`, EARS-7): each strike now carries its Euclidean outer radius on the wire alongside
the centre. E-8b (`a858dc4`, EARS-8/EARS-9): the renderer holds each strike for
`LIGHTNING_TRAIL_TICKS` (6) frames at strictly decreasing opacity and draws it as its true toroidal
Euclidean disc rather than a single centre cell.

### E-9 · Extinction ordering: Catalyst first, then Spore; Membrane persists

Observed across both sessions. **This is the balance-tuning signal that was being waited on** —
tuning was deferred until a GUI existed to give visual feedback, and it now does.

The E-6 table is a plausible mechanism (Catalyst net-zero on feeding, Spore marginal, Membrane
comfortable) and E-7 removes Catalyst's only escape route. **Neither has been tested** — the
ordering matching the arithmetic so neatly is exactly when to be suspicious. Confirm before
tuning on it.

**Emergence, not mechanism.** Per the constitution clause, population outcomes get no
default-suite test. Verify by instrumented observation (`DeathDiagnostics` census, which already
exists behind `paralife.diagnostics.death-trace.enabled`) and judge by eye on the visualiser.
