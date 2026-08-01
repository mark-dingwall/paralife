This file is the project constitution — the live, authoritative account of what Paralife is and
how it is built. It is injected every session, so it stays lean; deeper detail lives on-demand in
`docs/` (see `docs/README.md` for the map).

> **Frozen corpus guardrail.** `.planning/` and `.gsd/` are the archived GSD-era planning corpus.
> Read them for *facts* (what was decided, why a value is what it is) — **never** copy their
> structure, headings, or the phase / PLAN / SUMMARY / VERIFICATION process onto new work. Those
> are retired conventions. If anything there disagrees with the code or this file, the code and this
> file win.

## Project

Paralife is a distributed living simulation — a toroidal 2D world where three competing particle species (Catalyst, Membrane, Spore) interact in rock-paper-scissors dynamics, alongside richer emergent structures (bonded pairs, composite organisms) and an environment layer (seasons, toxins, mutagens, lightning). A Spring Boot server runs the physics tick loop, broadcasts vision-scoped state via WebSocket over a compact-text wire protocol, and receives actions from autonomous bot clients. Built with Java 21 virtual threads for massive concurrency with simple blocking code.

**Core Value:** Emergent spatial behaviour — spiral waves, population oscillations, and niche formation — arising from simple local rules. A testbed for evolving entity intelligence from heuristic bots toward genetic/learning systems.

**Milestones:** v1.0 Foundation · v2.0 Combination & Emergence · v3.0 Scale Engineering (M4) · M5 Observability & Operations. **Milestone ranges, per-phase status, current position, and what's next are tracked solely in [`ROADMAP.md`](ROADMAP.md)** — the single source of truth; this file names the lineage only, to stay lean and avoid drift. Phase history, decision registers, and per-phase detail are frozen under `.planning/` (see `.planning/README.md`).

## How we work

The loop, graduated off GSD (drop the machinery, keep the habits). These are **available moves, not gated stages** — reach for each when it earns its keep; skip them for trivial changes.

**Spec-doc → TDD RED/GREEN → backlog-defer → PR-per-slice.** One logical unit of work per slice; if scope explodes mid-flight, split into a new slice rather than growing the current one.

**The constitution clause — mechanism vs emergence.** *Mechanism* (combat math, energy decay, the wire protocol, resume-token FSMs, env diffusion) is deterministic: spec it, pin it, TDD it. *Emergence* (population stability, spiral waves, niche formation) is statistical and tuning-sensitive: **never pin it with a default-suite test.** Litmus for "does this earn a spec/test?": *if the implementation can change without changing visible behaviour, it doesn't.* Firewall: if you can't phrase a requirement as an EARS `WHEN … THE SYSTEM SHALL …` clause, it's emergence → opt-in `@Tag("slow")` suite, not the default gate.

**Label vs count (firewall corollary).** A death-cause/effect *label* is pinnable mechanism (`WHEN a combat sink drives energy==0 THE SYSTEM SHALL attribute cause=COMBAT`); a *count / share / rate* derived from it is observe-only emergence — it moves under tuning. **Class-ban:** no default-suite `assertThat` on any per-population statistical aggregate *or any predicate / threshold / structural property derived from one* — shares, composition, hazard, raw counts, rates, lifespans, densities, population magnitude, distinct-bucket counts, off-residual gates, non-degeneracy predicates. Emergence *is* testable, but only as tuning-invariant, control-anchored **ordinal** ratios ("evolved forages ≥3× random", not "starvation==78%"), and only in `@Tag("slow")`, never the default gate. (Origin: Pelagia cross-project research, 2026-07; full backlog + rationale in `BACKLOG.md` §Headless feedback-loop + emergence testing.)

**Spec-doc skeleton** (non-trivial mechanism changes; a markdown doc, not a folder):
- **Why** — bounded (~50–1000 chars), neither blank nor an essay.
- **What changes / Impact** — the behaviour delta + the file list it touches.
- **Assumptions / Open questions** — 2–4 lines before writing code.
- **Non-Goals** — the explicit home for backlog-defer; name what you're *not* doing and where it goes.
- **Readiness:** a one-line **GO / GO-WITH-CAVEATS / NO-GO** that forces the mechanism-vs-emergence triage up front. NO-GO is a legitimate outcome.

**Mechanism specs in EARS.** `WHEN <event> THE SYSTEM SHALL <response>`. Each clause maps to one RED/GREEN assertion pinned to the **transformation contract** (a config accessor — e.g. `50 - profile.forType(CATALYST).decayPerTick()`), never a hardcoded magnitude. Pair every negative assertion with a positive control. Real RED only — the test must fail for the spec reason before you make it pass. (Full test-pinning doctrine: §Testing philosophy.)

**Close-out gates** (at slice/PR merge):
- **Evidence-bound done** — every "done / fixed / passing" claim quotes a code line or command output. No rubber-stamps.
- **Scope-diff** — one line in the PR: delivered vs spec-doc intent (surfaces creep *and* silent under-delivery).
- **Merge-back** — fold the change into the canonical living doc (`docs/SCHEMA.md`, `docs/ADMISSION.md`, …) at merge. A slice isn't done until the doc matches shipped code.
- **Gates are RED-first** — a verification gate (shell `grep`/`diff`/`comm`, plan check, negative assertion) is not trusted until it has been *shown to fire* on the exact loss it guards: delete the guarded content, watch it go red; restore, watch it go silent. A gate never RED-tested is theatre — it can pass while the thing it protects is already broken. This is "every negative assertion needs a positive control" (§Testing philosophy) extended from JUnit to plan/shell gates. Origin: docs-editorial review 2026-07 — three verification gates (OR-batched across files, word-level, wrong-string) passed vacuously; one even greped strings that didn't exist. All would have died on a 30-second RED-test.
- **Post-convergence edits re-enter review (diff-scoped)** — once an artifact converges through review, a later edit (including one prompted by a *human answer* to a deferred open question) is re-reviewed against **just that diff** before it ships, carrying the prior rejected-list as "known — don't re-flag". Converging with open questions outstanding and deferring them to a human is *legitimate* — forcing an autonomous pass to guess is the real footgun — but the answer's edits are unreviewed until this diff-scoped pass runs. Origin: same review — the HIGH/MED findings were all in hand-authored steps added *after* the workflow's review rounds converged.

**Rigor dial.** Trivial fix → just do it, no spec. Wire/FSM/concurrency change → full spec + EARS + TDD. Review tier by blast radius: `caveman:caveman-review` (low) → `multi-review` (high). **Compounding corpus:** append durable findings to this file / `MEMORY.md` passively as you go — that accumulation, not phase ceremony, is what the workflow is actually for.

## Technology Stack

- **Java 21** — Virtual threads enabled (`spring.threads.virtual.enabled: true`)
- **Spring Boot 3.4.4** — `starter-web`, `starter-websocket`, `starter-actuator`, **`starter-jetty`**
- **Jetty 12** — the embedded servlet/WebSocket container (`starter-tomcat` is **explicitly excluded**); permessage-deflate via `JettyDeflateCustomizer`; `jetty-websocket-jetty-client` used by the load harness
- **Gradle Kotlin DSL** — Build system with wrapper (`./gradlew`); Spotless formatting gate (`ratchetFrom("origin/main")`)
- **JUnit 5** — ~1000 test methods (unit + integration), `forkEvery=0` shared-JVM (leak-sensitive by design)
- **Node 22** — **a hard build prerequisite**: the observer's two renderer modules are covered by `jsTest` (Node's built-in test runner, zero npm dependencies), and `check` depends on it, so `./gradlew build` fails without `node` on PATH
- **JaCoCo** — Coverage reporting (XML + HTML)
- **picocli** — CLI parsing for `LoadHarness` (the in-process `BotRunner` CLI parses its args by hand)
- **Compact-text wire codec** (`com.paralife.codec`) — hand-rolled protocol on the hot path; Jackson (transitive via Spring) is used only for actuator/JSON, not perception frames
- **`@ConfigurationProperties`** bound to 16 records — e.g. `GridConfig`, `TickConfig`, `SimulationConfig`, `MetabolicProfile`, `EnvironmentConfig`, `BondingConfig`, `CompositeConfig`, `SeasonsConfig`, `AdmissionConfig`, `JettyRuntimeConfig`, `AppRuntimeConfig`

## Conventions

**Package structure:** `com.paralife.{world,engine,websocket,codec,admission,bot,harness,metrics,runtime,diagnostics,observer}` — flat single-level per layer. `diagnostics` holds `DeathDiagnostics` (flag-gated death-cause + lifespan census), **OFF by default** (`@ConditionalOnProperty paralife.diagnostics.death-trace.enabled=true`, no yaml key); a no-op unless enabled, wired into the tick pipeline (SimulationEngine / EnvironmentEngine / DeathFinalizer / LiveEntityRegistry). `observer` holds the read-only visualiser endpoint, broadcaster, off-thread sender, frame DTOs; **OFF by default** via `paralife.observer.enabled`.

**Data modeling:** Immutable records throughout. Sealed interface for polymorphism (`Entity` permits `Particle`, `Rock`, `Nutrient`, `BondedPair`, `CompositeMember`; `Particle` carries the `ParticleType` species enum CATALYST/MEMBRANE/SPORE, `CompositeMember` a `Role` enum). Mutations produce new instances (`Cell.withOccupant()`, `Entity.Particle.withEnergy()`). Wire frames are modelled by the `com.paralife.codec` record family (`Frame`, `CellEntry`, `Event`, `StateChange`).

**Concurrency:** Single-threaded simulation core (all world mutations in tick event handlers). Virtual threads for I/O (WebSocket, tick loop heartbeat). `ReentrantReadWriteLock` on `WorldGrid` — read lock for snapshots, write lock for mutations.

**Spring patterns:** `@Component` beans for all services. `@EventListener` with `@Order` for tick pipeline sequencing. `@ConfigurationProperties` on records for type-safe config binding. Raw `WebSocketHandler` (not STOMP) for full protocol control.

**Testing:** `*Test.java` for unit tests, `*IntegrationTest.java` for integration tests. Mirror source directory structure. `@SpringBootTest` for integration tests.

**Testing philosophy:**

- **Pin mechanics; defer emergence.** Deterministic local mechanics (combat math, energy decay, reproduce cost/cooldown/floor gates, nutrient gain, population census, codec round-trips) belong in fast JUnit tests with assertions pinned to config/spec values. Emergent, statistical outcomes (multi-type survival, population oscillation, niche formation over long runs) are **not** gated in the default suite — they are inherently seed- and tuning-sensitive, so a pinned assertion on them is brittle and breaks the moment entity/env constants are tuned. They live in the opt-in `@Tag("slow")` `EmergenceStabilityLoadTest` (excluded from `./gradlew test`; run via `-PincludeLong=true`) and are otherwise deferred to hyperparameter tuning and the M5 visualiser. **Smell test:** if a "mechanics" test only goes green because the simulation happened to survive a multi-tick run, it is an emergence test in disguise — decompose it into engine-direct assertions on the underlying rule.

- **Assert against independent constants, not the code under test.** Expected values are hand-computed literals or config-derived — never recomputed by calling the same production function the test exercises. A self-referential expected value shifts together with the bug and stays green (e.g. assert a starvation-boosted gain equals the literal `10`, not `(int)(base * (1 + boost * StarvationConfig.computeIntensity(...)))`). Pin to the *intended* contract so code drift fails the test, rather than mirroring whatever the code currently computes.

  **Production constants (entity/env defaults) WILL be tuned to encourage emergence — never couple a mechanics test to their specific magnitudes.** A literal is only sound when the test *owns the inputs that produce it*: build a local `profile(...)` / `StarvationConfig` with constants the test fixes itself, so a hand-computed literal stays valid no matter how `MetabolicProfile.defaults()`, `StarvationConfig.defaults()`, or `SimulationConfig.defaults()` are retuned (this is why the boost literal `10` above is fine — its inputs are test-owned, not the production defaults). When a test genuinely exercises a production default, read the expectation back from the config accessor (e.g. `50 - profile.forType(CATALYST).decayPerTick()`) so it tracks tuning and pins the *transformation contract* — "the engine subtracts exactly the profile's rate" — not the number. A magnitude hardcoded off a tunable default (`isEqualTo(47)` for "default decay 3") is brittle: it goes red on the first tuning pass while telling you nothing about correctness.

- **Every negative assertion needs a positive control.** A test that asserts "X does not happen" is vacuous if the action never fired (e.g. an unregistered bot whose action is silently dropped, so the cell is empty for the wrong reason). Pair it with a control proving the same harness *does* produce X under the opposite input — and isolate the gate under test so a co-located guard can't mask its regression (the floor gate subsumes the cost gate for sub-cost energy, so "below cost" pins behaviour, not the cost gate alone).

**Build commands:**
```bash
./gradlew test              # Run all tests
./gradlew jsTest            # Observer renderer JS tests (needs Node 22; bound to `check`)
./gradlew spotlessCheck     # Lint (formatting gate)
./gradlew bootRun           # Start server on :8080
./gradlew jacocoTestReport  # Generate coverage report
```

## Architecture

High-level map. Deeper subsystem rationale (outbound concurrency / backpressure FSM, connection model, runtime tuning) is in `docs/ARCHITECTURE.md`; the canonical capability contracts are `docs/SCHEMA.md` (wire protocol), `docs/ADMISSION.md` (admission / backpressure / resume-token FSM), `docs/HARNESS.md` (load harness + connection model), `docs/RUNTIME.md` (per-connection tuning).

**Packages / layers:**

| Layer | Package | Key Files |
|-------|---------|-----------|
| World | `com.paralife.world` | `WorldGrid`, `Cell`, `Entity` (sealed: Particle/Rock/Nutrient/BondedPair/CompositeMember), `Position`, `GridConfig` |
| Engine | `com.paralife.engine` | `SimulationEngine`, `ActionResolver`, `TickEngine`, `BotRegistry`, `EnvironmentEngine`, `CompositeRegistry`/`CompositeEnergyDistributor`, `SeasonTracker`, `CellularAutomaton`, plus ~10 config records (metabolism, bonding, composites, environment, seasons, spawn, fertility) |
| WebSocket | `com.paralife.websocket` | `WorldWebSocketHandler`, `TickBroadcaster`, `SessionRegistry`, `WebSocketConfig`, `WebSocketKeepaliveService`, `JettyDeflateCustomizer` |
| Codec | `com.paralife.codec` | Compact-text wire protocol (13 files): `Frame` (`r`/`S`/`T`/`a`/`E`), `PerceptionCodec`, `Base64Codec`, `CellEntry`, `Event`, `StateChange`, `KindData` |
| Admission | `com.paralife.admission` | `AdmissionGate` (single authority for registration/respawn), `ResumeTokenRegistry`, `OutboundSender`, `AdmissionConfig`, `AdmissionMetrics`, `TickHealthMonitor`, `AttributionTagger`/`AttributionSanitizer` |
| Bot | `com.paralife.bot` | `BotClient`, `HeuristicBrain`, `BotLauncher`, `BotRunner` (operator CLI via `./gradlew runBot`, 100-bot cap), `BotFleet`, `BotFactory`, `BotIdentity`, `SpeciesMix` |
| Harness | `com.paralife.harness` | `LoadHarness` — standalone picocli load-test CLI (`./gradlew runHarness` / `loadHarnessJar`); harness-identity attribution (Phase 18) |
| Metrics | `com.paralife.metrics` | `EmergenceMetrics` (bonded-pairs/composites/buffs/infections), `WebSocketMetrics` |
| Runtime | `com.paralife.runtime` | `JettyRuntimeConfig`, `AppRuntimeConfig` — Phase 20 per-connection tuning knobs |

**Tick pipeline** (Spring `@EventListener` on `TickEvent`):
1. `ResumeTokenRegistry` `@Order(1)` — Grace-expiry sweep; dead-entity cleanup before SimulationEngine
2. `SimulationEngine` `@Order(10)` — Combat, energy decay, death removal, nutrient spawning
3. `EnvironmentEngine` `@Order(TICK_ORDER)` — Toxin/mutagen/lightning/compost; rebuilds status caches (TICK_ORDER=14)
4. `CompositeEnergyDistributor` `@Order(15)` — Composite passive energy drain
5. `ActionResolver` `@Order(20)` — Drain pending bot actions, resolve verbs `M/E/A/R/V/L` (move / eat / attack / reproduce / composite-vote / alarm)
6. `EnvPostActionReconciler` `@Order(TICK_ORDER)` — Apply post-action buff grants, clear cure-immunity (TICK_ORDER=25)
7. `TickBroadcaster` `@Order(50)` — Per-bot tick frame (5x5 vision, wire bitmask, perception)
8. `ObserverBroadcaster` `@Order(60)` — Bounded snapshot + serialize-once + non-blocking offer to observer mailboxes (off-thread delivery via `ObserverOutboundSender` drain VTs)
9. `WebSocketKeepaliveService` `@Order(200)` — Keepalive PINGs
10. `TickHealthMonitor` `@Order(Integer.MAX_VALUE)` — Sample tick wall-time into ring buffer

**Env state projection — three layers** (Phase 14, decisions D-38/D-39/D-40/D-41):

| Layer | Surface | Owner | Purpose |
|-------|---------|-------|---------|
| 1. Shadow grids | `byte[][] toxinGrid`, `mutagenGrid` (intensity 0–255) | `EnvironmentEngine` | Authoritative effect state; CA diffusion, spline paths, gossip |
| 2. Status caches | `Map<Position,Byte> cellStatusCache`, `Map<String,Byte> entityStatusCache` | `EnvironmentEngine.buildStatusCaches()` | Per-tick read-only bitmask projection (D-41). Derived from layer 1 + registries (BuffRegistry, Infection map). Rebuilt every tick, not a second source of truth |
| 3. Wire bitmask | `cellStatus` / `entityStatus` bytes carried on the codec `CellEntry` inside each per-bot `Frame` | `TickBroadcaster` `@Order(50)` (per-bot) | Zero-trust vision-scoped bitmask. OVERCROWDED is **redacted per bot**: `cellStatus = (layer2 & ~BIT_OVERCROWDED) \| perBotOvercrowdedBit` — bit 0 recomputed from bot's 5x5 Moore count so outer vision cells correctly under-report global overcrowding (D-40 incomplete-information design) |

Bit layout — two separate bytes, defined by `docs/SCHEMA.md` §8.1.2/§8.1.3 (the wire contract):
- **`cellStatus` / `envState`** (D-38): OVERCROWDED=bit 0 (`0x01`, vision-scoped — redacted per bot), TOXIN_PRESENT=bit 1 (`0x02`), MUTAGEN_ZONE=bit 2 (`0x04`).
- **`entityStatus` / `entityState`** (D-39): STARVING=bit 0 (`0x01`, projected from `Cell.FLAG_STARVING`), MUTATING=bit 1 (`0x02`, active infection), BUFFED=bit 2 (`0x04`, active survivor buff). There is intentionally **no** entity-level TOXIC bit — "entity on a toxic cell" is derivable from the cell-level TOXIN_PRESENT bit at the same coordinate. `EnvironmentEngine.buildStatusCaches()` is the encoder, `HeuristicBrain` the canonical decoder; both are pinned to these schema literals by contract tests (`TickBroadcasterProjectionTest`, `HeuristicBrainDeterminismTest`).

`Cell.flags` retains `FLAG_OVERCROWDED`/`FLAG_STARVING`; STARVING is both an intrinsic cell flag (set by `SimulationEngine` `@Order(10)`) **and** projected onto `entityStatus` bit 0 at `@Order(14)` so bots can see prey starvation. Env effects do NOT extend `Cell.flags` — intensity values don't fit single bits and cache locality favours per-effect shadow grids.

**Entry points:**
- `ParalifeApplication.main()` — Spring Boot startup
- `TickEngine.onApplicationReady()` (`@EventListener(ApplicationReadyEvent.class)`) — Starts the virtual-thread tick loop (if `paralife.tick.auto-start: true`)
- `WorldWebSocketHandler` — Client connections at `/ws/world`

**Error handling:** Graceful degradation. WebSocket errors send an error frame (`E|<code>|<reason>`, e.g. `E|408|reconnect-required`). Tick loop catches exceptions and continues. No exception bubbling.

## Skills (web/mobile fallback)

We do ~95% of development via the Claude Code **CLI**, where the
[Superpowers](https://github.com/obra/Superpowers) skill library is installed as a plugin and loads
automatically. **Web/mobile** sessions clone this repo fresh and do **not** have that plugin. We
chose not to vendor the skills (~10k lines). So: **if the user requests one of these skills and it
is not already available, fetch and read it from the raw URL below first** (read the listed
companions too — they're load-bearing and referenced by relative paths that won't resolve over raw HTTP).

Pinned base (commit `896224c`, plugin v6.0.3 — swap the SHA for `main` to track latest):
`https://raw.githubusercontent.com/obra/Superpowers/896224c4b1879920ab573417e68fd51d2ccc9072/skills/`

| Skill | `SKILL.md` path | Also read (companions) |
|-------|-----------------|------------------------|
| `receiving-code-review` | `receiving-code-review/SKILL.md` | — |
| `requesting-code-review` | `requesting-code-review/SKILL.md` | `requesting-code-review/code-reviewer.md` |
| `test-driven-development` | `test-driven-development/SKILL.md` | `test-driven-development/testing-anti-patterns.md` |
| `writing-plans` | `writing-plans/SKILL.md` | — |
| `subagent-driven-development` | `subagent-driven-development/SKILL.md` | `subagent-driven-development/implementer-prompt.md`, `subagent-driven-development/task-reviewer-prompt.md` |

`subagent-driven-development` references three sibling skills that **don't apply in a web/mobile
remote session** — when it points to them, do this instead: `using-git-worktrees` → **skip** (the
remote container is already an isolated fresh clone); `executing-plans` → **skip** (subagents are
available, so `subagent-driven-development` supersedes it); `finishing-a-development-branch` →
**skip** (branch integration is governed by the remote harness — open a PR only when explicitly asked).

**Cloud-sandbox quirk:** in the web/mobile remote environment, the structured `AskUserQuestion`
picker loses the user's selection in transit (observed repeatedly). Prefer asking in plain prose
(numbered options the user can reply to in free text) when in that environment.
