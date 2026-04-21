# Phase 16: Emergent Behavior Tests - Context

**Gathered:** 2026-04-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Validate that complex emergent behaviours arise from the combined M2 systems (bonding, composites, metabolism, environment) running over the Phase 15 compact protocol, and that the integrated stack meets the v2.0 milestone acceptance bar for stability and load.

Covers requirements R15–R19:
- R15 — Deterministic seed test for composite formation (emergence from rules, not luck)
- R16 — Population dynamics test with metabolism + environment (stable ecosystem)
- R17 — At least one emergent pattern documented
- R18 — Load test with composites — no regression from v1.0 baseline (reframed — see D-10)
- R19 — All v1.0 tests still pass (no regressions)

**Not in scope:**
- Subjective human-observer UAT / "pleasing to watch" visualisation — moved to M5 (Observability & Operations) visualiser phase
- Live operator UAT script/dashboard — M5 owns this
- Per-bot perception dev-tools / WS inspector — zero-trust design makes single-session inspection ineffective; a global observer endpoint belongs in M5
- Param-sweep / Bayesian tuning for emergent-behaviour config — far-future

</domain>

<decisions>
## Implementation Decisions

### Test Scope Layout
- **D-01:** Hybrid test layout — **two** new integration tests:
  1. `CompositeFormationDeterminismTest` (R15) — short, engine-direct, seeded.
  2. `EmergenceStabilityLoadTest` (R16 + R17 + R18) — long-run, full-stack, 100 bots, captures counters + asserts stability + perf headroom in one seeded run.
- **D-02:** Tick interval minimised for tests (target ~10–20 ms) with modest safety buffer. Long-run test wall-clock target ≤90 s local; CI tolerant of several minutes if needed.
- **D-03:** R19 (no v1.0 regressions) covered by existing CI `./gradlew test` gate — no new test needed. Verifier agent must confirm the full suite is green before phase completion.

### Test Surface (engine-direct vs full-stack)
- **D-17:** R15 runs **engine-direct** — `@SpringBootTest` without web environment, drives `TickEngine` / `SimulationEngine` in-thread, reads `WorldGrid` / `CompositeRegistry` via bean injection. Bypasses WebSocket + codec + virtual-thread I/O non-determinism. Protocol correctness is covered by Phase 15 codec tests + D-18 below.
- **D-18:** R16/R17/R18 run **full-stack** — real `@SpringBootTest(webEnvironment = RANDOM_PORT)`, real `BotClient` instances via `BotLauncher`, Jetty + codec + permessage-deflate on both sides. Their claims are about the running system, not pure sim rules.

### Emergent Signals Tracked (R17)
- **D-04:** Five signals observed during the long-run test. Each gets an assertion (or a soft "observed, recorded" check for the marginal ones):
  1. **Bonded-pair formation** — assert count > 0 by tick N. Config tuning allowed (elevated bond probability / proximity threshold) to force observable rate in test-scale worlds.
  2. **Composite formation** — assert count > 0 if config permits; non-fatal soft-check otherwise.
  3. **Predator pressure on STARVING prey** — trigger-watcher pattern: when a STARVING entity appears, start a window W ticks; compute rolling average of non-bonded predators within radius R of the prey; assert mean > baseline density at window close.
  4. **RPS boom-bust cycle** — autocorrelation / sinusoidal-trend check on per-type population series across the run. Weak assertion (pattern exists and is roughly periodic); strict period match not required.
  5. **Flee-from-strong-predator** — when a buffed or bonded predator appears, trigger-watcher as #3, inverted: assert weaker-type entity density within R *declines* over W ticks.
- **D-05:** Trigger-watcher pattern is the shared mechanism for signals #3 and #5. Appearance of a candidate entity starts a per-entity observation window; per-tick samples accumulate; assertion fires at window close. Noise from unrelated bots accepted; window size sets signal-to-noise balance.

### Documentation Form (R17)
- **D-06:** R17 evidence lands in `16-EMERGENCE.md` — narrative markdown writeup per signal (observed values, seeds used, interpretation). No charts/plots this phase.
- **D-06b:** Long-run test dumps a JSON per-tick fixture of population counts + emergence-counter values to `.planning/phases/16-emergent-behavior-tests/fixtures/run-<timestamp>.json`. Rollover: keep only the most recent **N = 5** runs (older ones deleted at test start). Directory gitignored.

### Stability Criteria (R16)
- **D-07:** Three assertions, each with its own threshold, all must hold:
  1. **No extinction** — all three particle types alive at every snapshot checkpoint across the run.
  2. **Per-type floor** — each type's share of total particles ≥ 5 % for ≥ 80 % of ticks (tolerates short dips).
  3. **Oscillation amplitude** — over a rolling window of 200 ticks, `(max − min) / mean` per type ≥ configured floor (default 0.15) for at least one type — rules out a degenerate "frozen equilibrium" pass.
- **D-08:** Long-run test duration = **1000 ticks**.

### Seeding Strategy
- **D-09:** Component-seeded RNG with a single logged master seed.
  - Test picks a master seed (fixed for R15, deliberately varied / logged for R16 long-run).
  - Master deterministically derives per-component seeds for: bot brains (one per bot), env RNG (Poisson rolls, gossip, strain mutation), world init, tie-break RNG inside `ActionResolver`.
  - Assertions are **statistical** (count ≥ K, share ≥ X %, amplitude ≥ Y), not byte-exact snapshot comparisons. Byte-stable fixtures explicitly rejected — too fragile under virtual-thread I/O and JDK `HashMap` iteration order.
  - Master seed logged in test output so any failure can be reproduced locally.
- **D-09a:** `BotClient` ctor already accepts an injectable `Random`; tests construct bots with seeded per-bot `Random`s derived from the master seed instead of the default `ThreadLocalRandom.current()`.
- **D-09b:** Server-side components (`EnvironmentEngine`, `ActionResolver`, world init, respawn jitter) need an audit for RNG sources. Any currently reading `ThreadLocalRandom` directly must accept a `Random` / `RandomSource` via Spring injection (configurable via `@TestPropertySource` seed property). The research agent should map every `Random` / `ThreadLocalRandom` / `Math.random` usage on the server side before the planner commits to a concrete injection surface.

### Load Stability (R18 — reframed)
- **D-10:** R18 reframed from "no regression from v1.0 baseline" (wire-parity framing — misleading because v2 systems add legitimate traffic) to **capacity-headroom stability**. Test name: `EmergenceStabilityLoadTest` covers both R16 and R18 in one run. Rationale: we will keep adding features (effects, bot skills) that legitimately increase wire volume; the contract we need to defend is "we never run out of runway," not "we never grow."
- **D-11:** Load-stability assertions (feature-agnostic ratios and absolutes):

  | Metric | Assertion | Catches |
  |---|---|---|
  | Tick drift | < 10 % (matches v1 `LoadTest` threshold) | Scheduler can't keep up |
  | Mean tick work time | ≤ 50 % of configured tick-interval budget | Safety buffer eroding |
  | p99 tick work time | ≤ 90 % of tick-interval budget | Worst-case spikes near cliff |
  | Session dropouts during steady state | 0 | Protocol / transport health |
  | Heap growth, last 200 ticks vs first 200 (post-warmup) | < 20 % delta | Leak detection |
  | ERROR-level log entries during run | 0 | Hidden exceptions |
  | Active-session gauge | == configured bot count throughout | Silent disconnects |

  Tick work time = wall-clock from `TickEvent` dispatch start to final `@Order(100)` listener completion.
- **D-12:** Load-stability config **forces composite formation** (elevated bond rate / proximity threshold + energy surplus) so R18 actually exercises the composite path — otherwise R18 becomes a particle-only load test in disguise. Specific config values = Claude's Discretion during planning, informed by existing `CompositeFormationTest` knobs.

### Operator UAT Scope
- **D-13:** Phase 16 is **JUnit-only**. No operator UAT script, no `runBot`-based evidence gathering, no `16-UAT.md`. R17 evidence comes from the automated long-run test + `16-EMERGENCE.md` writeup.
  - Rationale: the useful UAT here would be a human judging the sim visually ("is this pleasing / interesting to watch"); that requires a visualiser, and visualiser is M5's scope (per Phase 15 context). A log-grep UAT would duplicate what JUnit already asserts and add nothing.
  - v2.0 milestone closeout should note: "subjective observer UAT for emergence deferred to M5 visualiser phase."

### Emergence Observability Instrumentation
- **D-14:** Four Micrometer counters in the `paralife.emergence.*` namespace, reachable via `/actuator/metrics/paralife.emergence.*`. Piggybacks on the Phase 15 D-10 metrics infra (`WebSocketMetrics` bean pattern; new companion bean `EmergenceMetrics` or equivalent):
  - `paralife.emergence.bonded.pairs.formed` (counter)
  - `paralife.emergence.composites.formed` (counter)
  - `paralife.emergence.buffs.granted` (counter)
  - `paralife.emergence.mutagen.infections` (counter)

  Incremented from the existing server-side components that already detect these transitions (bonding processor, composite formation code, `BuffRegistry`, `EnvironmentEngine` infection map). Planner identifies exact increment sites during phase research.
- **D-15:** Structured `EMERGENCE` INFO-level log markers at each trigger point, single-line format:
  ```
  EMERGENCE bonded-pair-formed tick=234 types=CAT+MEM at=(45,78)
  EMERGENCE composite-formed tick=512 size=4 role-mix=[L,F,S,A]
  EMERGENCE buff-granted tick=401 entity=<id> buff=S+1 survivor-of=mutagen
  EMERGENCE infection-started tick=355 entity=<id> strain=12
  ```
  Grep-friendly; same prefix so `grep EMERGENCE server.log` is the operator cheat-sheet line. Low cardinality; no per-tick spam. These markers pay forward — M5 visualiser can consume the same log channel.

### Claude's Discretion
- Exact `BondingConfig` / `CompositeConfig` values that reliably force observable bonded-pair and composite formation within 1000 ticks at the long-run test's grid size (planner picks based on `CompositeFormationTest` existing knobs).
- Rolling-window sizes W and radii R for signals #3 and #5 (D-04) — planner tunes for signal-to-noise.
- Per-component seed-derivation scheme (e.g. `SplittableRandom.split()` vs `XorShift` subseed vs `masterSeed + componentTag.hashCode()`).
- Oscillation-amplitude floor default (0.15 suggested, refine during planning if sims show higher/lower natural variance).
- Heap measurement mechanism (`Runtime.getRuntime()` snapshot at checkpoints vs JFR vs Micrometer `jvm.memory.used`) — whichever is simplest + robust.
- Exact `EmergenceMetrics` bean placement (`engine`, `websocket`, or new `metrics` package).
- Order of assertions inside the long-run test (fail-fast on stability vs accumulate-all-then-report).
- Whether the long-run test tags `@Tag("slow")` for selective CI runs.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & Requirements
- `.planning/ROADMAP.md` — Phase 16 entry (lines 176–184) + overall milestone success criteria
- `.planning/REQUIREMENTS.md` — R15–R19 with "Pending" status; this phase closes them
- `.planning/PROJECT.md` — v2.0 active requirements list; "Emergent spatial behaviour" core value statement

### Prior Phase Context (decisions this phase builds on)
- `.planning/phases/11-bonding-rules-engine/11-CONTEXT.md` — bond triggering conditions, BondedPair entity
- `.planning/phases/12-composite-entities/12-CONTEXT.md` — composite formation rules, member roles, dissolution
- `.planning/phases/13-energy-metabolism-system/13-CONTEXT.md` — seasonal cycle, metabolic profiles, STARVING flag
- `.planning/phases/14-environmental-rules/14-CONTEXT.md` — env effects, BuffRegistry, emergence-relevant status bytes (D-38/D-39)
- `.planning/phases/15-protocol-transport-overhaul/15-CONTEXT.md` — compact protocol, zero-trust vision, Micrometer metrics infra (D-10), observer-endpoint deferral to M5
- `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` — locked wire format; the long-run test speaks this protocol
- `.planning/phases/15.1-bot-operator-cli/` — `BotRunner` / `BotLauncher` as pattern reference for full-stack bot orchestration
- `.planning/phases/15.2-own-death-event-wiring/SUMMARY.md` — respawn flow + session-stable entity IDs; long-run stability test must tolerate respawn churn

### Source Files (pattern-mapping inputs)

**Existing tests to mirror / extend:**
- `src/test/java/com/paralife/engine/PopulationDynamicsTest.java` — 500-tick 3-type survival pattern; reference for R16 assertion style
- `src/test/java/com/paralife/engine/LoadTest.java` — 100-bot tick-drift pattern; R18 assertions build on this
- `src/test/java/com/paralife/engine/CompositeFormationTest.java` — unit test for formation mechanic; R15 test exercises the *emergent* variant
- `src/test/java/com/paralife/engine/EnvironmentFullStackSmokeTest.java` — 300-tick full-stack env pattern; closest analog to the new long-run test structure
- `src/test/java/com/paralife/engine/MetabolismIntegrationTest.java` — metabolism-stack integration pattern

**Production code the tests exercise:**
- `src/main/java/com/paralife/engine/TickEngine.java` — tick loop; R15 drives this directly
- `src/main/java/com/paralife/engine/SimulationEngine.java` — physics + combat + death finalisation
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — Poisson triggers, shadow grids, status caches
- `src/main/java/com/paralife/engine/ActionResolver.java` — move/consume/reproduce/rest + tie-break RNG (D-09b)
- `src/main/java/com/paralife/engine/CompositeRegistry.java` — composite state; counter increments (D-14)
- `src/main/java/com/paralife/engine/BotRegistry.java` — bot session tracking; death notices (Phase 15.2)
- `src/main/java/com/paralife/engine/BuffRegistry.java` — buff grant site; counter increments (D-14)
- `src/main/java/com/paralife/engine/BondingProcessor.java` (or equivalent from Phase 11) — bond formation site; counter increments (D-14)
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — full-stack path; respawn-safe per Phase 15.2
- `src/main/java/com/paralife/bot/BotClient.java` — seed injection point (D-09a); Jetty-native transport
- `src/main/java/com/paralife/bot/HeuristicBrain.java` — pure function of (TickFrame, BotState, Random); accepts seeded rng
- `src/main/java/com/paralife/bot/BotLauncher.java` — bulk launch helper for full-stack tests
- Micrometer metrics bean from Phase 15 Plan 15-10 (`WebSocketMetrics` or similar) — pattern for `EmergenceMetrics`

### Build + Config
- `build.gradle.kts` — existing JUnit 5 + Spring Boot test config; new test follows same pattern
- `src/main/resources/application.yml` — new test properties via `@TestPropertySource` on the integration test (seeds, tick interval, grid size, forced-bonding config)
- `.gitignore` — add `.planning/phases/16-emergent-behavior-tests/fixtures/*.json` entry (D-06b)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PopulationDynamicsTest` — template for 3-type survival assertions, @TestPropertySource override pattern
- `LoadTest` — template for 100-bot full-stack harness, tick-drift assertion
- `EnvironmentFullStackSmokeTest` — closest to target: 300-tick full-stack covering env + metabolism; R16+R17+R18 test structurally extends this
- `CompositeFormationTest` — existing unit-test knobs for bonding / composite config that force formation; reference for R15 seeded-emergence test and R18 composite-load config (D-12)
- `BotLauncher` — bulk bot launch for full-stack
- `BotClient(uri, species, brain, pollMs, respawnJitterMs, random)` — ctor already accepts seeded `Random` (D-09a); no refactor needed on the bot side
- Micrometer `MeterRegistry` + existing `WebSocketMetrics` bean (Phase 15 Plan 15-10) — drop-in pattern for `EmergenceMetrics` (D-14)
- `SeasonTracker` + `SeasonsConfig` — deterministic season schedule given fixed `year-length-ticks`; reproducible when master seed is fixed

### Established Patterns
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@TestPropertySource` for full-stack integration tests
- `@SpringBootTest` without web env for engine-direct tests (R15)
- Record + `@ConfigurationProperties` for test-config overrides
- Single-threaded tick pipeline with `@EventListener` + `@Order` (deterministic given fixed RNG + fixed iteration order)
- Immutable records for `Cell`, `Particle`, `BondedPair`, `CompositeMember`; mutations produce new instances (no mid-tick reordering issues)
- Shadow registries (`BotRegistry`, `CompositeRegistry`, `BuffRegistry`) for cross-cutting state — all three are read by emergence counters (D-14)

### Integration Points
- **`EmergenceMetrics` increment sites** — planner identifies the formation/grant/infection trigger points in the existing engine; counters increment there, `EMERGENCE` log markers emit from the same sites
- **Seed injection points** — `BotClient` ctor (per-bot), `EnvironmentEngine` (Poisson + gossip), `ActionResolver` (tie-break), world init, respawn jitter; tests supply seeded `Random` via a test-only `RandomSource` bean or direct ctor args
- **`EmergenceStabilityLoadTest` fixture dump** — test `@AfterEach` (or equivalent) reads counters from `MeterRegistry` + per-tick population snapshot accumulator, writes `run-<timestamp>.json`, prunes older fixtures > N=5
- **Tick-work timing** — test wraps `TickEvent` dispatch with timing hooks, or reads an existing timing metric if Phase 15 already exposes one (research agent confirms)
- **Respawn churn in long-run** — Phase 15.2 respawn flow means sessions reconnect on death; load-stability test's "active-session gauge" check must read the steady-state value not the startup transient

### Known Debt to Resolve This Phase
- RNG sources (`ThreadLocalRandom`, bare `new Random()`, `Math.random()`) on server-side components that currently prevent reproducibility must be converted to injectable `Random` / `RandomSource` inputs for R15/R16 determinism. Audit list produced by research agent; planner scopes which are in-scope for Phase 16 (those in the tick pipeline hot path) vs which can stay unseeded (e.g. one-shot startup randomness that doesn't affect emergence).

</code_context>

<specifics>
## Specific Ideas

- **Trigger-watcher for behavioural signals (D-04 #3, #5)** — appearance of the candidate entity (STARVING prey / buffed predator) starts a per-entity observation window; each tick within the window records predator/prey density within radius R; at window close, assert rolling mean satisfies the directional signal. Noise from unrelated bots accepted; window/radius chosen for signal-to-noise balance.
- **Forced emergence via config knobs** — long-run test `@TestPropertySource` bumps bond probability / proximity threshold / energy surplus to observable rates for test-scale worlds. Not tuning the production defaults; test config only.
- **`EMERGENCE` log prefix as M5 seed** — single grep-friendly prefix on every emergence event marker. Phase 16 operator can `grep EMERGENCE server.log` today; M5 visualiser can consume the same channel tomorrow without a new feed design.
- **Heap leak detection via post-warmup delta** — compare last-200-tick vs first-200-tick (post-warmup) heap size. Rules out warmup/allocation noise while still catching real leaks.
- **Respawn churn as a feature, not a bug** — 100-bot long-run under realistic combat produces hundreds of deaths + respawns (Phase 15.2 shows 414 respawns in a 180s run). Load-stability assertions must hold *across* this churn; active-session gauge reads steady-state, not transient.

</specifics>

<deferred>
## Deferred Ideas

### Moved to M5 (Observability & Operations)
- **Subjective human-observer UAT / "pleasing to watch" evaluation** — requires a visualiser; belongs with M5's observer endpoint + grid renderer + live event feed.
- **Live operator dashboard** (HTML canvas polling `/actuator/metrics/paralife.emergence.*`) — would be a half-step towards M5; cleaner to do it properly in M5 context.
- **Per-session WS inspector / devtools integration** — zero-trust design makes single-session inspection low-value for emergence observation; M5 builds a proper global-observer endpoint instead.

### Post-MVP Emergence Work
- **Bayesian / param-sweep tuning for emergent-behaviour config** — overnight runs searching `BondingConfig`, `CompositeConfig`, `EnvironmentConfig` space for settings that maximise signal-to-noise on the five tracked signals. Far-future; needs M5 telemetry + some compute budget.
- **Byte-stable fixture snapshots** — considered and explicitly rejected for R15/R16. Too fragile vs seeded-statistical assertions under virtual-thread I/O and JDK iteration-order changes. Revisit only if forensic-replay need arises.
- **Emergence counters as Prometheus-scrape targets** — M5 will wire `/actuator/prometheus`; Phase 16 counters become first-class metrics then.
- **Chart / plot rendering of per-tick population series** — `16-EMERGENCE.md` is markdown-only; charts wait until M5 visualiser or a dedicated post-MVP writeup phase.

### Out-of-Scope for this Phase (but observed)
- **R19 "all v1.0 tests still pass"** is covered by the existing `./gradlew test` CI gate — no new test artefact produced, but the verifier agent must confirm green before phase closeout.

</deferred>

<addendum>
## Post-Research Addendum (2026-04-21, from planner revision round 1)

Added during revision to resolve checker-flagged blockers without silent scope reduction. Each decision below is as binding as D-01..D-18.

- **D-19 — TriggerWatcher signal #5 trigger scoped to entities with stable IDs (BondedPair, CompositeMember).** Plain Particle has no server-global ID on `Cell.occupant()` — IDs live on `BotRegistry`, `CompositeRegistry`, `BondedPair`. Therefore `forBuffedPredator` triggers only when the buffed entity is a `BondedPair` or `CompositeMember` (both registered, both with `hasBuffs()` resolvable via `BuffRegistry.getBuffs(id)`). Plain-Particle buffs are still counted by `paralife.emergence.buffs.granted` (D-14) and logged by `EMERGENCE buff-granted` (D-15); only the per-window flee-signal observation is scoped. Rationale: the signal intent is "prey flees a stronger-than-baseline predator" — bonded/composite predators are precisely the stronger-than-baseline case, so the scoping narrows the observation to the population the signal meaningfully describes.

- **D-20 — R15/R16 reproducibility via `paralife.test.master-seed` property override.** `EmergenceStabilityLoadTest` reads `@Value("${paralife.test.master-seed:#{null}}") Long` at class construction. When the property is absent, `masterSeed = System.nanoTime()` (preserves D-09 statistical-sampling intent). When set (e.g. via `-Dparalife.test.master-seed=12345` or `@TestPropertySource`), the run uses the override, so any observed failure is locally reproducible via a single CLI flag. The master seed is logged at INFO unconditionally. Supersedes the earlier planning note that override was "unwired."

- **D-21 — Signal #4 (RPS boom-bust) gets a dedicated weak assertion.** `PopulationHistory.autocorrelation(type, lag)` computes lag-k autocorrelation of the per-type population series. Signal #4 asserts that at least one of the three types has autocorrelation above a weak floor (default 0.2) at lag = 50 ticks — consistent with D-04 "weak assertion (pattern exists and is roughly periodic); strict period match not required." If observed values cluster near zero (no periodicity), the floor is tightened in calibration; if consistently high, slot is left. Shares no slot with D-07 row 3 (oscillation amplitude).

- **D-22 — Load-stability assertion ordering chosen: fail-fast + try-finally fixture dump.** Claude's Discretion slot in D-11 resolved. Rationale: fail-fast surfaces the first violation cleanly; `try { all-asserts } finally { RunFixtureWriter.dumpAndRollover(...) }` guarantees evidence on disk for any failure. Accumulate-all was considered but rejected — SoftAssertions + 15-rule block is harder to diagnose than a single AssertJ failure with explicit `.as(...)` messages. PLAN truths updated to reflect fail-fast.

- **D-23 — Meta-validation #3 negative control lands in 16-05 as a sibling `@Nested` class with seed=1337.** Outer class uses seed=42 across 3 runs (same-seed identity). Sibling `@Nested` `DifferentSeedControl` uses `@TestPropertySource` override to seed=1337, captures composite count, and asserts `observations.get(0).compositeCount() != sharedSeed42Count` via a shared static holder. Proves the test measures the seed, not always-zero/always-same.

- **D-24 — `EMERGENCE buff-granted` log gated on the new-buff branch only.** Symmetric with the counter rule (D-14 — counter increments on `list.add`, not `list.set`). Previously the PLAN had counter-gated-but-log-unconditional; this addendum unifies the two — both gate on `wasNewBuff[0]`. Assertion in `EmergenceMetricsWiringTest.buffCounterIncrementsOnNewBuffOnly` extended to also assert log-marker count matches new-buff count.

</addendum>

---

*Phase: 16-emergent-behavior-tests*
*Context gathered: 2026-04-21*
*Closes v2.0 milestone pending subjective UAT deferred to M5.*
