# ADR: Headless feedback-loop metric for balance tuning

- **Status:** **Accepted (direction only), 2026-07-01.** Ratified across two independent sessions that landed the same five dispositions. The 6-round non-convergence was over *details*, not direction; the direction is settled. Actionable content harvested to `BACKLOG.md` §Headless feedback-loop + emergence testing; the durable firewall corollary (label = mechanism, count/share = emergence) banked to `CLAUDE.md`. This file is now a **research record**, not an open decision.

  **Escalation dispositions (converged):** *(1)* in-slice act = **citation-only** (the species spot-check is a flag-on nicety, do ad hoc, don't commit). *(2)* raw counts carry a mixed-taxonomy confound → kind dimension **advisable, not mandatory**; note it on the backlogged gauge, don't gold-plate now. *(3)* paired driver is **YAGNI** — build only if ensemble-N is *proven* too noisy. *(4)* **keep flag-gated**, no always-on gauge (the counting is already a cheap `LongAdder`; the always-on tax is the per-death Micrometer re-register + `log.info` + unused lifespan census). *(5)* the tuning deferral **stays, non-blocking, M5-gated** — its unlock is a *spatial-emergence discriminator*, never a 1-D death read.

  **Framing fix (post-deliberation):** The question posed (Pelagia note §1) — *"is a headless feedback **loop** available today?"* — splits in two: the *measurement* half is trivially yes (a usable death-cause number **already exists**, `DeathDiagnostics.java:97-102`, and was already produced — the ~78% figure in memory); the *loop* half (perturb → measure → compare → adjust) is **deferred in full** (paired driver backlogged, ensemble-N deferred, tuning campaign M5/Phase-21-gated). **Path-alpha** — show the existing number *move* under a one-constant change (see Axis B runners-up / dissent) — would settle the loop half and is firewall-clean, but it is **structurally blind to spatial collapse** (so it does not bear on escalation (5)) and was identified and deliberately **not run**, on tuning-stance (not firewall) grounds. Ratify accordingly.

---

## Context

The saved project stance is "balance tuning deferred until a GUI" — the belief that without a visualiser there is no feedback loop to tune against. The Pelagia cross-project note (`docs/notes/pelagia-comparison.md` §2a) challenges this: the missing ingredient is a deterministic **numeric** signal, not pixels, and Paralife already *measures* death causes (`DeathDiagnostics`) where Pelagia only infers them.

The hard constraint is the **constitution firewall** (see `CLAUDE.md`): any proposal that would freeze an emergence number into `./gradlew test` is a blocking objection.

The panel converged on one thing only: **measuring emergence does not pin it.** Everything past that is contested.

---

## Decision (per axis)

| Axis | Locked-enough resolution | Rationale | Effort |
|---|---|---|---|
| **A. Override defer-till-GUI?** | **Non-conflict, not an override.** The memory defers *tuning*, not *measuring*; `CLAUDE.md` permits measuring emergence (`EmergenceMetrics` ships always-on ungated counters). **In-slice act = a documentation CITATION** of `DeathDiagnostics.java:97-102` + the **78% STARVATION** memory figure. Together these discharge the existence claim *and* (near-certainly) the cause-axis non-degeneracy: the entailment 78% < 100% ⇒ ≥2 causes carry mass is valid, but its input (the 78%/16% split) is a **single unseeded prod-default run**, not a robustness-established figure — so treat the cause-axis discharge as near-certain-pending-a-duration-scoped-read, not proven. Optionally, **one manual, duration-scoped, observe-only read** to spot-check the species-axis conjunct (and firm up the cause-axis figure). Tuning campaign stays deferred. | Citing already-committed evidence refutes the narrow "no number without pixels" claim at ~0 cost and 0 firewall risk. | **S** (cite-only); **S** (optional manual read) |
| **B. Which metric?** | **Death-cause signal ONLY.** Deliverable = **per-`[type-tag][cause]` RAW COUNTS / deltas, observe-only** (the checked-in ADR locks raw counts, rejects the normalized share as compositionally closed + base-rate-confounded — see this ADR's Axis B + its runners-up). Backlog food-per-capita and the competence assay. | Counter is the cheapest pre-built signal; raw counts drop the closed-sum confound; assay is downstream of a nonexistent MLP+genome; food-per-capita is a weak observable + a second `O(W·H)` scan. | **S** |
| **C. Firewall classification** | **Split each metric: PLUMBING (deterministic, default-pinnable to label/contract) vs VALUE/COUNT/COMPOSITION (emergence, observe-only forever).** Ban stated as a **class**: no default-suite `assertThat` on any per-population aggregate **or any predicate/threshold/structural property derived from one** — including distinct-bucket counts, off-residual-mass gates, and non-degeneracy predicates. | The proposal's own non-degeneracy gate is a count predicate that flips pass→fail under pure tuning (`decayPerTick` up → off-residual mass collapses toward the STARVATION default `:89`); an aggregate-type-only ban waves it through. | **S** (a PR-checklist rule, no code) |
| **D. Seeded determinism a prerequisite?** | **NO.** Engine's 7 RNG streams are seeded + canonicalized with a GoldenTrace dual-run gate (engine rewrite cost = 0), but the **live-WS harness action-delivery timing is an 8th unseeded source the gate does not cover** (`ActionResolver.java:158,353,409-413`). Stage-1 needs no determinism. **Stage-2 default = unseeded ENSEMBLE-N**; the named `@Tag("slow")` harness **cannot pair** (Cov≈0 from the start). A genuinely paired loop needs a **new in-process synchronous bot-driver** — a backlog rewrite. | "Set the seed" is refuted: `paralife.simulation.seed` seeds only `simRng` (1 of 7); prod bots are unseeded (`BotFactory.java:44`, `BotClient.java:150`). Bit-reproducibility under live bots is unreachable without lockstep tick-gating that kills the virtual-thread model. | Stage-1 **S**; paired driver **M** (backlog) |
| **E. Counter primitive?** | **Build ZERO new production primitive in this slice.** The existing flag already emits the per-`[type][cause]` counter (`:98-102`) + greppable `DEATH-TRACE` log (`:105-106`). Backlog the `long[type][cause]` + lazy scrape-time Gauge to the M5-gated standing-gauge campaign. | Stage-1 needs only a citation (or a one-off flag-on read). The standing gauge carries real constraints (below) but none are stage-1 work. | Stage-1 **0**; standing gauge **M** (backlog) |

**Backlogged standing-gauge constraints (axis E, when built):**
- Drop the per-death `log.info` (`:105-106`) **and** per-death `Counter.builder/register` (`:98-102`) — both scale with the ~78% death-treadmill (the javadoc "negligible" `:37` scopes to `hintLethal`, **not** `recordDeath`).
- Shape **per-`[type][cause]`, not aggregate** (avoids Simpson). Numerator-only → emits raw counts cheaply but **cannot** emit a hazard without an added per-species at-risk census (the rejected `O(W·H)` scan).
- Add a **kind dimension** to disambiguate the `BONDED` literal and the free-vs-composite merge, or the array inherits the mixed-taxonomy confound.
- **Concurrency (verified):** carry maps are written from two threads — `recordBirth`/`forget` on the WS/admission thread (`LiveEntityRegistry.java:130,148`), `recordDeath`/`hintLethal` on the tick thread (`DeathFinalizer.java:121,145`). **Keep `lethalHint` concurrent**; drop `birthTick`+`preHitEnergy`. The `long[type][cause]` **write side is single-writer** (`recordDeath` tick-thread-only) → volatile-array-swap / VarHandle publish for the gauge read holds.
- Value is cumulative → delta-between-scrapes or restart-per-arm with warmup discard. Drop "+ set seed" (cargo-cult).

**Stage-1 read scoping (if the optional spot-check is run):** scope by **DURATION at modest population / prod-default interval** (`application.yml:34`, `interval-ms:500`), **NOT** small population. Off-residual gate causes (COMBAT `SimulationEngine.java:469,496,884-888`, OVERCROWDING `:1092`) are density-dependent and rarest at low density, so a small-population read biases onto the STARVATION residual for a base-rate reason. Cumulative counts (`:96-102`) let a long modest-pop run accrue the rare tail. The per-death cost is interval-regime-specific — trivially safe at the 500ms prod default; it only bites at the compressed harness interval.

---

## Runners-up (out-voted / rejected)

**Axis A**
1. Sell the manual read as a **"legibility upgrade"** — rejected: the gate (1e) is necessary-not-sufficient (COMBAT is a catch-all, `SimulationEngine.java:884-888`), so a PASS can only certify non-degeneracy, never legible per-cause attribution. Relabel as a species-axis non-degeneracy spot-check.
2. Treat the manual read as the tuning-relevant **floor** — superseded: the cause-axis conjunct is pre-discharged by the 78% figure, so the read's marginal content is the species conjunct alone (an optional nicety).
3. **Full override** — start an A/B tuning campaign now — rejected: premature before Phase 21; risks freezing an emergence number into a gate.

**Axis B**
1. Per-species cause **share/composition** — superseded: the ADR rejects the normalized share (closed-sum + base-rate confounded); buys nothing over raw counts for the gate.
2. Per-species per-cause **hazard** over a window — not deliverable: needs a per-species exposure census that does not exist (only the rejected `O(W·H)` scan).
3. **Competence-vs-random assay first** — the only default-gate-pinnable ratio, but vaporware (no neural/genome forager, `pelagia-comparison.md:58-60`).

**Axis C**
1. Ban stated as aggregate **types only** — under-protects: omits predicate/threshold/structural forms; the non-degeneracy gate slips through a magnitude-only reviewer check.
2. One attribution test **per lethal sink, this slice** — demoted: scoped to sink-isolated OVERCROWDING + COMBAT-positive only; env sinks + COMBAT-negative defer post-fix.

**Axis D**
1. Stage-2 **leads with a ~3-seed paired delta** on `EmergenceStabilityLoadTest` — refuted: the harness cannot pair (unseeded live-WS timing dominates, Cov≈0 at zero perturbation).
2. **Null arm = RNG-inert constant** — refuted: zero stream reordering → Cov→~1, false pairing optimism.
3. **Full bit-determinism under live bots** — unreachable without lockstep tick-gating that destroys the virtual-thread model.

**Axis E**
1. Scope the stage-1 read to **small population** — refuted (density base-rate, above).
2. Cut 3 carry maps → **1 plain HashMap** — refuted: `forget` runs cross-thread on the WS thread, a plain map races; correct is 3→1 *concurrent* map.
3. Build the `long[Cause]`+gauge **in this slice** — premature: stage-1 needs only the existing flag or a citation.

---

## Recorded dissent (preserved, not averaged)

- **devils-advocate (stands, refuses to concede):** the read is mis-sold as legibility, and the cause-axis half of the gate is already in memory — so the honest stage-1 deliverable is **citation-ONLY**; the species spot-check is a pure nicety whose flag-on tax may not be worth it. Exporting a biased, order-prioritised readout as a *standing* gauge with an implied target is a one-directional slope toward the firewall breach the saved memory warns of — safest posture is **no standing gauge at all**. Tuning a 1-D scalar (starvation %) can hit target while silently wrecking the spatial emergence (spiral waves, niche formation) that is the stated Core Value. Tuning is premature before Phase 21 shifts density/timing.
- **sim-scientist:** even per-`[type][cause]` raw counts inherit a **mixed-taxonomy confound** — the non-species `BONDED` stratum (`DeathFinalizer.java:145`; a bond spans two species, `Entity.java:174-175`, unapportionable) + each species bucket merging free-particle vs composite-member sub-populations of tuning-sensitive mix. The Simpson de-confound is only **partial**; a clean breakdown needs an extra kind dimension. The stage-1 read must be **duration-scoped** or its PASS/FAIL is a density artifact. Stage-3 target is confounded counts, not hazard.
- **determinism-realist:** the named seeded harness **cannot pair**; ensemble-N is the stage-2 default and paired-seeding needs a **new in-process synchronous driver** (uncosted backlog rewrite). When pairing exists, measure Cov-under-perturbation with the smallest *real* tuning step; Cov is not bounded to (0,1); window each arm to its own measured period.
- **perf-runtime:** the stage-1 read cost is **interval-regime-specific** (safe at 500ms prod default; the "~4ms p99 `:124-126`" figure is invented — those lines are a brace + histogram javadoc). Keep `lethalHint` concurrent; the counter write side is single-writer.
- **yagni:** don't build the standing primitive in this slice; finalize the ADR with **one merge-back edit**, not a per-axis overturn. Note: **path-alpha** (show the number *move* under a one-constant change) would be the stronger "prove the loop works" pick for the same flag-on cost — but it stays deferred on tuning-stance grounds.
- **constitution-keeper (held):** the manual-never-committed form is a sufficient firewall guard. Do **not** pin TOXIN/MUTAGEN/LIGHTNING nor the COMBAT toxin-splash negative against current output (cements documented-wrong behaviour, `EnvironmentEngine.java:1281-1287`; the COMBAT toxin-splash mislabel is at `SimulationEngine.java:884-886`). The OVERCROWDING positive control is firewall-clean **only** when sink-isolated **and** the test owns its threshold/penalty config.

---

## Consequences

**Commits us to:**
- A cite-only stage-1 slice: fold the live decision into `docs/notes/headless-feedback-loop-adr.md` in **one merge-back edit** at slice close (flip Status → Accepted). No new production code, no committed test.
- A standing **class-level firewall ban** (PR-checklist) that travels with the backlogged standing-gauge PR — the only PR that can introduce the violation it guards.

**Unblocks:**
- Refutes the narrow "no headless number without pixels" claim immediately, at zero firewall risk.
- A clear, costed backlog: standing `long[type][cause]` gauge (M), in-process synchronous paired bot-driver (M), ensemble-N tuning campaign (M5/Phase-21-gated).

**Defers:**
- The whole A/B **tuning campaign** (stages 2–3) on tuning-stance + Phase-21-prematurity grounds — **not** on a firewall basis (path-alpha is firewall-clean).
- Food-per-capita gauge, competence-vs-random assay, the standing primitive, and all seeded-pairing infrastructure.

---

## Escalated / needs maintainer call

Convergence was not reached. Each item below has live, unresolved disagreement — **maintainer ruling required.**

1. **Is the in-slice ACT cite-only, or cite + optional manual read?** With the cause-axis non-degeneracy pre-discharged by the 78% memory figure, the read collapses to an optional species-axis spot-check. *devils-advocate + yagni:* honest deliverable is **citation-only**; the spot-check is a nicety whose flag-on tax may not be worth it. *Others:* the optional duration-scoped read adds the one un-discharged conjunct cheaply. **No consensus.**

2. **Do raw counts fully resolve the closed-sum objection?** *sim-scientist:* even raw per-`[type][cause]` counts carry the mixed-taxonomy confound (`BONDED` + free/composite merge); a kind dimension is required before *any* legibility claim. Unresolved whether the kind dimension is mandatory for the backlogged gauge or merely advisable.

3. **Is the paired in-process driver the right unlock, or YAGNI?** *determinism-realist:* the named harness cannot pair, so paired-seeding needs a new synchronous driver. *yagni/devils-advocate:* ensemble-N on the existing harness may suffice for the whole campaign, making the driver-rewrite speculative. **Open: build the driver or rely on ensemble-N?**

4. **Standing gauge at all?** Note the framing: a Prometheus-scrapeable Micrometer `Counter` (`cause`+`type` tagged) **already ships** behind the flag (`DeathDiagnostics.java:98-102`, registers to `MeterRegistry`), so the real question is *make it always-on and cheaper*, not *introduce a standing surface de novo*. *devils-advocate (refuses to concede):* an always-on actuator gauge with an implied target is a structural slope toward the firewall breach; safest posture is no always-on surface — keep everything flag-gated / `@Tag("slow")`. The rest of the panel accepts an always-on gauge guarded by the class-level ban. **Unresolved.**

5. **The deferral itself.** The contrarian stance is preserved, not overturned: tuning may be premature before Phase 21 lands (density/timing shift); a 1-D scalar can be tuned to target while destroying the spatial emergence that is the stated Core Value; M5 (numbers *and* eyes) may be the better spend. This ADR defers tuning but does **not** settle that the deferral should ever lift pre-M5.

---

## Firewall note

| Metric / artifact | Classification |
|---|---|
| Per-sink lethal-hint **tagging** (combat death tags COMBAT not STARVATION default) — the PLUMBING | **Pinnable mechanism** — default-suite legal, but **only** OVERCROWDING (`SimulationEngine.java:1090-1101`) is clean *now*, and only when sink-isolated + test-owns-config. COMBAT-positive ships; COMBAT-negative + TOXIN/MUTAGEN/LIGHTNING defer until the `hintLethal`-at-damage-site fix lands. |
| Death-cause **raw counts / share / 78% figure** — the VALUE | **Observe-only emergence forever** — most tuning-sensitive scalar in the sim. Never a default-suite assertion. |
| **Non-degeneracy predicate** (≥2 causes ∧ ≥2 species off-residual) | **Observe-only emergence** — it is a count predicate that flips under pure tuning; the manual read must stay **manual, never committed as a test**. |
| Food-per-capita value / population magnitude / lifespans / densities | **Observe-only emergence.** |
| Competence-vs-random assay ratio | **Tuning-invariant → pinnable, but `@Tag("slow")` ONLY**, never the default gate — and vaporware until MLP+genome exist. |

**Class ban (the rule):** no default-suite `assertThat` on any per-population statistical aggregate **or any predicate/threshold/structural property derived from one** — shares, composition, hazard, raw counts read as emergence, rates, lifespans, densities, population magnitude, distinct-bucket counts, off-residual-mass gates, or non-degeneracy predicates. Any emergence threshold lives in `@Tag("slow") EmergenceStabilityLoadTest`.