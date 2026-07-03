# Pelagia → Paralife: Cross-Project Synthesis

Context: https://raw.githubusercontent.com/bastian9819/pelagia/refs/heads/main/README.md

Scope: 7 dimensions, analyst findings reconciled against skeptic portability verdicts. Where a verdict downgraded a finding, the downgrade wins. WebGPU/WGSL never ports — at Pelagia's network sizes the compute is trivial JVM math, so "no compute shader in the JVM" is a non-issue; the real blockers are **where intelligence lives** (client vs server) and **immutable-record / config-mutability** friction.

---

## 1. Executive summary — top 5, ranked

| # | Takeaway | Why it's highest-leverage |
|---|----------|---------------------------|
| 1 | **Determinism + a relative-ratio assay is the missing feedback loop — not a GUI.** Pelagia's `evaluateGate`/`inferExtinctionCause` measure *competence-vs-random* and *per-cause death shares* headlessly. This directly refutes the "tuning deferred until GUI" memory: the missing ingredient is a **numeric** signal, available today. (See correction below on *which* signal is firewall-safe.) | Unblocks the death-treadmill *and* the neural roadmap's selection signal with zero frontend. |

> **Correction (caught by the ADR deliberation, `headless-feedback-loop-adr.md`):** the original wording here claimed the **death-cause share** "honours the firewall (tuning-invariant ratio)." That conflated two different things. The **competence-vs-random ratio** is genuinely tuning-invariant and the only one of these that could anchor a pinned test. The **death-cause share** is the *opposite* — the most tuning-sensitive scalar in the sim (retune `decayPerTick` and it moves), so it is **emergence, observe-only forever, never a default-suite assertion**. What *is* firewall-safe to pin is the cause-**attribution label** (deterministic mechanism: `WHEN a combat sink drives energy==0 THE SYSTEM SHALL attribute cause=COMBAT`), never any count or share magnitude. The shippable signal (§2a) is per-cause **raw counts read as deltas**, treated as an observe-only readout.
| 2 | **Spectator data channel is the keystone for M5** — a separate `/ws/spectator` feed bypassing AdmissionGate/PerceptionCodec. Cheapest first slice (per-species sparkline + Muller chart) puts eyes on the RPS oscillation that today only lives in the opt-in `@Tag("slow")` suite. | Every other visual feature depends on it; the population HUD is an S-effort strong-fit. |
| 3 | **The neural-brain roadmap hinges on one unmade decision: client-side vs server-side genome.** Porting a 17→10→3 MLP is easy; making it *evolve* is not, because reproduction/death are server-authoritative and brains currently live in the external bot client. This is a redesign, not an L-effort field add. | The stated core value is gated entirely on resolving this; the analyst undersold it. |
| 4 | **Allocation-free Moore iteration + Pelagia's benchmark methodology are the clean Phase 21 wins.** Kill `Position.neighbors()` churn; profile per-phase cost with density-held-constant scaling and GoldenTrace as the before/after oracle. | Only strong-fit perf items; the benchmark gates whether the other perf findings are worth it. |
| 5 | **World-state checksum + default-suite invariant harness are cheap determinism upgrades.** A `WorldGrid.stateChecksum()` decouples the determinism oracle from the wire/VT machinery; bounded-invariant assertions (no double-occupancy, energy≥0, toroidal wrap) are mechanism, not emergence — they pass the firewall. | Low-risk, immediate regression safety; the checksum also de-flakes future evolution work. |

---

## 2. Solutions to problems we face

### 2a. The "no visualiser / tuning deferred" blocker

The strongest cross-cutting insight: **you don't need pixels, you need a deterministic numeric feedback loop.**

| Action | Pelagia mechanism | Target paralife file(s) | Effort | Payoff |
|--------|-------------------|--------------------------|--------|--------|
| Per-species death-cause rollup + food-per-capita ratio, exported as actuator gauge | `extinction.ts inferExtinctionCause` (the *aggregate narrative*, not the trait-inference half — paralife's measured causes are better) | `diagnostics/DeathDiagnostics` (+`EmergenceMetrics`); MeterRegistry export | S | high |
| Plain-language `WorldNarrative.describe(snapshot)` over census/metrics for log/report legibility | `observatory.ts narrative()` | new `metrics`/`diagnostics` helper → `harness/ReportWriter` | S | med |
| Numeric A/B tuning loop: seed S, tweak one constant, rerun, diff starvation% | `share.ts` seed-folding + god-params | `SimulationConfig`/`MetabolicProfile`, `harness/ReportWriter` | M | high* |

\* **Caveats (skeptic):** a single chaotic run vs another is misleading — diff **ensembles** (N seeds, compare distributions). Production currently runs **unseeded** (`new Random()`/`ThreadLocalRandom` when seed null); making seeding the live path + pinning hash-iteration order is real determinism engineering. `DeathDiagnostics` is `@ConditionalOnProperty` **OFF by default** and per-entity-map costly at scale — surfacing the 78% signal means either enabling a cost-gated diagnostic or building a lighter always-on `LongAdder` per cause.

### 2b. Death-treadmill / population stability

Reframe (correct but **future-tense**): Pelagia's constant starvation *is* its selection engine — but only because lineages are heritable. Under paralife's current architecture deaths are bots respawning the **same scripted brain**, so there is zero heritability and the treadmill is not yet a selection engine. It becomes one only after server-side genome inheritance (§2c).

Mechanisms to **build now, tune later** (tuning stays GUI/feedback-gated):

| Action | Pelagia mechanism | Target paralife file | Effort | Payoff | Notes |
|--------|-------------------|----------------------|--------|--------|-------|
| Global per-tick nutrient budget (carrying-cap knob) replacing per-empty-cell Bernoulli | `life_grid.wgsl` budget + `life_cycle.wgsl foodRespawn` atomic decrement | `SimulationEngine.processNutrientSpawning:1442`, `SimulationConfig` | M | high | Hidden cost: weighted-by-`nutrientLevel` sampling needs an alias/Fenwick table over `EligibleCellIndex`, not free. Needs a maintained nutrient counter (none today). |
| Direct carrion drop (fraction of dead energy → Nutrient at death cell) | `life_cycle.wgsl death` pellet drop | `EnvironmentEngine.applyCompost`/`DeathFinalizer`, `EnvironmentConfig.Compost` | M | med | **Overlaps the budget knob** — reconcile against it or you double-inject and defeat the cap. Augments the fertility field, doesn't replace it. |
| Density-scaled reproduction cost (negative feedback on births) | `life_cycle.wgsl repro` size-scaled threshold | `ActionResolver.resolveReproduce:651`, `SimulationConfig.overcrowdingThreshold` | M | med | **Do not** reuse TickBroadcaster's 5×5 count — that's the zero-trust *wire* projection; recount Moore in ActionResolver (server-authoritative). |
| Global food carrying-cap as a single stability lever (duplicate framing of budget) | `food.ts` capacity + accumulator | same as budget row | S | med | — |

Already present — **do not re-implement:** seasonal boom/bust (`SeasonTracker.getSeasonalMultiplier`, D-14) and food patchiness (`FertilityConfig` patches, D-12/13). Pellet *drift* is a client immersion touch — poor-fit on a server (per-tick nutrient relocation = grid churn + wire deltas for zero headless value). Skip.

### 2c. Heuristic → neural/genetic roadmap

The core value made concrete — but the **architectural crux is unresolved and gates everything else**.

| Action | Pelagia mechanism | Target paralife | Effort | Payoff | Verdict |
|--------|-------------------|-----------------|--------|--------|---------|
| Fixed-topology MLP brain (17→10→3 tanh, flat `float[]` genome) beside HeuristicBrain | `brain.ts forward`/`mutateGenome` | new `bot/NeuralBrain` (same `decide(Frame.TickFrame, BotState, Random)` sig) | M | high | **qualified** — ports cleanly, but as written it lands client-side = just a differently-scripted bot. Value contingent on inheritance below. |
| Genome inheritance + mutation on reproduce | `population.ts reproduce`, `life_cycle.wgsl repro` (NaN/Inf gene healing) | `ActionResolver.resolveReproduce`, `Entity.Particle`, new `MutationConfig` | L→**redesign** | high | **poor-fit as scoped.** Brains live in external WS clients; server spawns brainless `Particle` clones with no session. Inheriting at the R verb means relocating inference **server-side** → N forward passes on the single-threaded hot path, breaks the "bots become standalone clients" intent + 100-bot cap + admission model. This is *where intelligence lives*, not a field add. |
| Evolved-vs-random foraging assay (deterministic "learning" test) | `metrics.ts evaluateGate`/`assayForaging` (≥3× random across ≥75% seeds, frozen-lifecycle arena) | new `EvolvedVsRandomForagingTest` `@Tag("slow")`; needs genome-injecting headless driver | M | high | **qualified** — methodologically the strongest idea in the whole set, legitimately pinnable. Fully downstream of MLP+inheritance; cannot be built first. |
| Per-entity phenotype/trade-off genes scaling existing defaults | `brain.ts` morphology genes → real trade-offs | layer onto `MetabolicProfile`, combat math, env layer | L | med | **qualified** — `MetabolicProfile.forType` is per-TYPE (3 profiles); per-ENTITY genes force a genome lookup every entity every tick on the hot path = tick-pipeline change, GUI-gated. |
| Activation genes (grow/lose neurons in fixed vector); sexual crossover + lineage tree | `brain.ts` activation gate; `life_cycle.wgsl` crossover/mate-choice | — | S / L | low | **backlog.** Speculative, gated on MLP+inheritance + M5. Crossover-on-BondedPair misreads semantics (BondedPair is predator+prey *fusion*, not mating). |

**Recommendation:** sequence is MLP (client, as baseline) → **decide brain location** → server-side genome + inheritance → foraging assay. Until the location decision is made, the neural work produces no evolution.

Supporting infra: **per-entity lineage + generation tracking** (`generation:int`, `lineageId:String` on `Particle`, increment on reproduce) — S→M effort (Particle has ~6 construction/spawn/withEnergy sites + reproduce plumbing to thread; codec wire family is separate, so **no wire-protocol change**), med payoff. Sharpens death-treadmill analysis now (which lineages collapse, at what generation depth) and is the foundation for the M5 lineage view.

---

## 3. Inspiration & immersion (what M5 should borrow)

Build order is **keystone → cheap eyes → richer views**.

| Feature | Pelagia source | Target paralife | Effort | Payoff | Verdict |
|---------|----------------|-----------------|--------|--------|---------|
| **Spectator/god data channel (keystone)** | `observatory.ts` periodic read-backs | new `/ws/spectator` or REST in `websocket`; ring buffer mirroring `TickHealthMonitor` | M | high | **strong** — bypass AdmissionGate/PerceptionCodec to keep firewall intact. `WorldGrid.snapshot()` takes the readLock + deep-copies — serialise **on demand only**; keep the per-tick ring buffer to cheap scalar aggregates. |
| Population HUD + per-species sparkline (**best first slice**) | `ui.ts buildUi`/`drawSpark` | add Catalyst/Membrane/Spore tallies to `livingEntityCount()` loop → spectator sample | S | high | **strong** — puts eyes on RPS oscillation, no config mutability, no firewall conflict. |
| Species-composition Muller / stacked-area | `observatory.ts drawMuller` | reuses HUD time series + frontend renderer | M (low, reuses #HUD data) | high | **strong** — canonical RPS-cycling view. "Graduates to clades" is speculative (no lineage yet). |
| Colour-by-status / heatmap render mode | `ui.ts colorBtn` | project `cellStatus`/`entityStatus` caches + `toxin/mutagenGrid` (0-255) via spectator channel | S | med | **qualified** — data exists & cheap, but "already on the wire" is wrong: that wire is per-bot vision-scoped/redacted; spectator needs a **full-grid** projection. |
| Agent inspector "decision tape" (EEG) | `brainView.ts` (node-graph half N/A for scripted brain) | new per-entity spectator stream off Frame data | M | med | **qualified** — decision-tape works for any brain (stream verb M/E/A/R/V/L + inputs); needs new stream + pause/step. Natural home for neural brain view later. |
| Pause / step / variable-speed transport | `ui.ts` transport bar | `TickEngine` (has start/stop, **no pause/step/speed**) + spectator-authority guard | M | med | **qualified** — single-step is high-value for the deterministic core. Speed = mutating immutable `TickConfig.intervalMs` → same config-mutability wall as god-mode. |
| God-mode live sliders (unblocks tuning) | `god.ts` writes params uniform | runtime-mutable overlay in `runtime` (cf. `AppRuntimeConfig`); route hot-path reads through it | **M→L** | high | **qualified** — strategically right but underscoped. All tunables are `private final @ConfigurationProperties` records bound once; needs an overlay + safe publication from WS virtual threads into the single-threaded core. Restrict to runtime-safe params (no grid resize). |
| Share-by-seed token (seed + god-params) | `share.ts` (pure encode/decode, versioned, defensive) | new `ShareToken` + thread one `WorldSeed` through `config.seed()` accessors | M | med | **qualified** — pure codec is clean mechanism (EARS+TDD). But "bit-exact, stronger than Pelagia" is overstated: prod is unseeded, and live-bot action ordering depends on WS/VT timing → bit-exact only for a **closed headless run**. Downstream of god-mode. |
| Two-level friendly/technical tooltips | `tooltip.ts` | M5 frontend | S | med | **strong** — pure frontend, portfolio value (self-explaining RPS model). |
| Floating panel toolkit + "render only while open" | `ui.ts` makeDraggable/Resizable | M5 frontend | S | low | **strong** — right structural pattern; keep server accumulation to cheap scalars. |
| Watch-list / track-a-creature | `brainView.ts` Track + `observatory.ts renderWatched` | `DeathDiagnostics` (birthTick/lifespan) + per-entity stream | M | low | **qualified** — leaf feature on two unbuilt pieces (#inspector + cost-gated diagnostics). |
| Display-name sidecar off the authoritative id | `lineageNames.ts` (`Map<id,name>`, never mutates sim) | future — when lineage substrate exists | L | med | **qualified** — sound principle, no substrate today (no ancestry). File, don't schedule. |

**Poor-fits for now:** full Observatory/Muller/cladogram *as an artifact* (DOM/canvas frontend doesn't exist — capture the **data-contract lesson**: a vision-unscoped god-view snapshot sampled off the tick). Inline-SVG icons / i18n `t(key)` / OG branding — browser-only, no server analog; M5 bookmarks.

---

## 4. Quality & engineering ideas

### Scaling (Phase 21)

| Action | Target | Effort | Payoff | Verdict |
|--------|--------|--------|--------|---------|
| **Adopt Pelagia's benchmark methodology** (per-phase cost isolation; scale GridConfig with bot count to hold density; GoldenTrace as before/after oracle) | `harness/LoadHarness` + JMH on engine; `TickHealthMonitor` ring buffer; `GoldenTraceEquivalenceTest` | M | high | **strong** — load-bearing; gates whether the rest is worth it. No CPU-vs-GPU "match%" analogue (single impl) — GoldenTrace substitutes correctly. |
| **Allocation-free Moore iteration** (`forEachNeighbor(x,y,IntBiConsumer)` or static int[8] dx/dy + `Math.floorMod`) | `Position.neighbors():21-32`; callers `SimulationEngine:451/565/648/904/1085`, `EligibleCellIndex`, `ActionResolver` | M | high | **strong** — cleanest, lowest-risk win. Note: the "TickBroadcaster 5×5 calls neighbors()" claim is **false** (grep: no call) — that benefit is illusory. Migration must hand callers ints/scratch Position, not lambda-captured `Position`. |
| Cache per-tick entity snapshot (kill ~7 redundant sorts) | `LiveEntityRegistry.snapshot():184` (~8×/tick) | S→M | high→med | **qualified** — current cost ~80µs/tick, below budget, so high payoff unproven until profiled. Invalidation is a **correctness hazard**: ActionResolver mutates positions mid-tick, so the hook must fire on every in-tick move, not just register/unregister. |
| Packed-int cell keys for status caches + `EligibleCellIndex.notifyChanged` | `EnvironmentEngine` `cellStatusCache` (grid-sized → `byte[]` via volatile swap); `EligibleCellIndex:63-65` | S | med | **qualified** — applies to `cellStatusCache` only; `entityStatusCache` is keyed by String entityId, **not** position-indexable. Must publish by volatile-ref swap (WS threads read concurrently). |
| SoA / primitive side-store for hot energy field | `SimulationEngine:956-959` (decay re-allocs Particle+Cell) | L | med | **qualified** — full SoA violates "immutable records throughout". Salvageable form: `int[] energy` side-store keyed by entity index, records as API surface — but splits the source of truth the tests pin. Post-Phase-21, gated on GC being the proven bottleneck. |
| Double-buffer grid (volatile `Cell[][]` swap) | `WorldGrid` RRWL TODO:19-22 | L | high→**low confirmed** | **qualified/defer** — GPU ping-pong doesn't map: paralife's pipeline is sequentially dependent (deaths removed, then later @Order handlers read the mutated grid). Lock is **uncontended** (sim + broadcaster both on tick thread) so removing it saves ~tens of ns, not contention. Phase 21 design item only. |

### Determinism & testing

| Action | Pelagia source | Target | Effort | Payoff | Verdict |
|--------|----------------|--------|--------|--------|---------|
| **World-state checksum oracle** decoupled from wire | `hash.ts hashWorld` (FNV-1a) | new `WorldGrid.stateChecksum()` + plain `@SpringBootTest` | S | med | **strong** — drops OutboundSender/mock sessions/VT drain/2s barrier; int energy means no f32-fold fragility. Still needs seeded-bean reset for cross-run (that half only collapses if the RNG rewrite lands). |
| **Long-run invariant harness** (default suite) | `world-loop.test.ts` bounded invariants | seeded `WorldGrid` N ticks; assert no double-occupancy, energy≥0, toroidal wrap, pop ≤ admission cap | S | med | **strong** — invariants are mechanism, EARS-phrasable, **not** emergence. Closes the gap between unit tests and `@Tag("slow")`. Resist drifting any bound into emergence (e.g. `pop>0` re-crosses the firewall — treadmill legitimately empties the world). |
| Reference/differential oracle for `EligibleCellIndex` | `gridBench.ts` CPU-vs-GPU (literal N/A) | property test: index eligible set == naive full-grid scan after seeded mutations | M | low | **qualified** — idiomatic, catches incremental-maintenance bugs `EligibleCellIndexTest`'s scripted cases miss; payoff bounded by churn (Phase 21 may touch it). |
| Counter-based stateless RNG keyed by (seed,tick,entity,stream) | `rng.ts` PCG hash | `SimulationEngine.simRng`, `ActionResolver.actionRng`, et al. | L | high→med | **qualified** — pain is real (`resetAll()` ~40 lines across 8 beans). But: (1) **not a drop-in for `Collections.shuffle(list, simRng)`** at SimulationEngine:436/642 — variable, order-dependent draw count → must reimplement indexed Fisher-Yates; (2) "order-independent" barely applies (core is single-threaded, deliberately order-dependent); (3) re-pins every golden baseline + perturbs emergence. Own spec'd slice, justified by teardown ergonomics + roadmap optionality, not a cheap win. |
| Unified seed+stream discipline | `world.ts Stream` enum | — | M→**S** | med→low | **qualified/already-done** — `EmergenceStabilityLoadTest` already fans ONE master via `SplittableRandom.split()` to all 7 seeded config records. Remaining delta: a **production-side** master fan-out (today only the test wires it). Minor tidy-up, not greenfield. |

---

## 5. Other points of interest

- **The death-treadmill is conceptually Pelagia's selection engine** — but a valid *future* property gated on server-side genome inheritance (§2c) + M5, not an actionable reframe today. (inspiration, S/med)
- **Genome split (behaviour weights vs phenotype genes)** is the right template for the genetic milestone; phenotype half ports cleanly onto `MetabolicProfile`/combat, behaviour half hits the same client-vs-server wall. (inspiration, L/med)
- **Double-buffered sense/think/move** — paralife already gets perception double-buffering free (bots act on prev-tick frame) and resolves contention via seeded `Collections.shuffle` + `claimedCells` first-claim-wins. The gather-then-resolve pass is more principled but solves a mitigated problem. (poor-fit, L/low)
- **Counting-sort spatial hash** — N/A; paralife's one-occupant grid is already O(1) neighbour. The real gap (census full-scan, `livingEntityCount` 65k cells) is already in-house via `LiveEntityRegistry`; remaining work is per-species typing on the registry to avoid the scan. (poor-fit as import, in-house refactor)
- **Paralife's coroner is already better than Pelagia's** — `DeathDiagnostics` *measures* cause at engine sinks; Pelagia *infers* from traits. Don't port the inference; port only the narrative presentation (subsumed by §2a). (footnote)

---

## 6. Honest non-fits

| Item | Why it doesn't port |
|------|---------------------|
| **WebGPU/WGSL compute & render shaders** | No JVM equivalent needed *and* none required — Pelagia's network/sim sizes are trivial CPU math. The shaders themselves are pure rendering/compute plumbing with zero server analog. |
| **Genome inheritance at the R verb as an "L field-add"** | Inverts paralife's design — brains live in external WS clients, server spawns brainless clones with no session. It's a redesign of *where intelligence lives* (hot-path inference, 100-bot cap, admission model), not a field addition. |
| **God-mode / live speed control as "sliders"** | Config is immutable `@ConfigurationProperties` bound once; there is no params-uniform to write. Needs a bespoke mutable overlay + safe cross-thread publication. Doable but M→L, and partly bends the "immutable records throughout" convention. |
| **SoA entity layout** | Violates "immutable records / sealed Entity hierarchy"; the codec + `Cell.withOccupant` assume records. Only the bounded `int[]` side-store survives, and it splits the energy source-of-truth the tests pin. |
| **Double-buffer grid (read-frozen/write-next)** | Pelagia's phases are independent; paralife's pipeline is sequentially dependent (intra-tick mutated-grid reads). Read-frozen would break visibility; lock is uncontended anyway. |
| **Share-by-URL "bit-exact replay" superiority claim** | Prod runs unseeded; live-bot action ordering is WS/VT-timing dependent. Bit-exact only for closed headless runs, not the live system. |
| **Pelagia's trait-inference coroner; sexual crossover on BondedPair; pellet drift** | Trait inference is worse than paralife's measured causes; BondedPair is predator+prey fusion not mating; pellet drift is a client immersion touch with negative server value (grid churn + wire deltas). |
| **Lineage-name sidecar, Observatory/cladogram, inline-SVG icons, i18n, OG branding** | No substrate (no ancestry) or no surface (no frontend). M5 references, not current-reality ports. |

**Bottom line:** the two genuinely transformative, available-today moves are (1) a **deterministic relative-ratio assay + per-cause feedback signal** that breaks the tuning blocker without a GUI, and (2) the **spectator channel + population/Muller HUD** as the M5 foundation. The neural roadmap is real but blocked on one architectural decision (genome location) that the analysis consistently undersold.
