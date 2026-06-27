<!-- GSD:project-start -->
## Project

Paralife is a distributed living simulation — a toroidal 2D world where three competing particle species (Catalyst, Membrane, Spore) interact in rock-paper-scissors dynamics, alongside richer emergent structures (bonded pairs, composite organisms) and an environment layer (seasons, toxins, mutagens, lightning). A Spring Boot server runs the physics tick loop, broadcasts vision-scoped state via WebSocket over a compact-text wire protocol, and receives actions from autonomous bot clients. Built with Java 21 virtual threads for massive concurrency with simple blocking code.

**Core Value:** Emergent spatial behaviour — spiral waves, population oscillations, and niche formation — arising from simple local rules. A testbed for evolving entity intelligence from heuristic bots toward genetic/learning systems.

**Milestones / requirements** (current position lives in `.planning/STATE.md`, `.planning/ROADMAP.md`, `.planning/REQUIREMENTS.md`):
- **v1.0 Foundation & Living Simulation** (M001/M002, Phases 01–10, ✅ complete) — server boots, tick loop, WebSocket broadcast, 100 concurrent bots; RPS entity dynamics, perception/action protocol, heuristic bots, population stability over 500+ ticks.
- **v2.0 Combination & Emergence** (Phases 11–16, ✅ complete) — bonding/endosymbiosis, composite organisms, energy metabolism, environmental rules, compact-text transport, emergent-behaviour tests.
- **v3.0 Scale Engineering / M4** (Phases 17–22, 🚧 active) — durable admission control + backpressure, external load harness, connection-multiplexing runtime tuning, high-density placement; latest sequenced phase complete is Phase 20.1, and Phase 21 (scale benchmark gate) is the next planned phase. Phase 22 (integration-test resource-leak audit) already ran out-of-band as 2026-05-03 incident response (merged via PR #3, `f941417`); its revalidation (Phase 22.1) is planned but **not yet executed** — see `.planning/STATE.md` / `.planning/ROADMAP.md`.
<!-- GSD:project-end -->

<!-- GSD:technology-start -->
## Technology Stack

- **Java 21** — Virtual threads enabled (`spring.threads.virtual.enabled: true`)
- **Spring Boot 3.4.4** — `starter-web`, `starter-websocket`, `starter-actuator`, **`starter-jetty`**
- **Jetty 12** — the embedded servlet/WebSocket container (`starter-tomcat` is **explicitly excluded**); permessage-deflate via `JettyDeflateCustomizer`; `jetty-websocket-jetty-client` used by the load harness
- **Gradle Kotlin DSL** — Build system with wrapper (`./gradlew`)
- **JUnit 5** — ~990 test methods across 144 files (unit + integration)
- **JaCoCo** — Coverage reporting (XML + HTML)
- **picocli** — CLI parsing for `LoadHarness` (the in-process `BotRunner` CLI parses its args by hand)
- **Compact-text wire codec** (`com.paralife.codec`) — hand-rolled protocol on the hot path; Jackson (transitive via Spring) is used only for actuator/JSON, not perception frames
- **`@ConfigurationProperties`** bound to 16 records — e.g. `GridConfig`, `TickConfig`, `SimulationConfig`, `MetabolicProfile`, `EnvironmentConfig`, `BondingConfig`, `CompositeConfig`, `SeasonsConfig`, `AdmissionConfig`, `JettyRuntimeConfig`, `AppRuntimeConfig`
<!-- GSD:technology-end -->

<!-- GSD:conventions-start -->
## Conventions

**Package structure:** `com.paralife.{world,engine,websocket,codec,admission,bot,harness,metrics,runtime,diagnostics}` — flat single-level per layer. `diagnostics` holds `DeathDiagnostics` (flag-gated death-cause + lifespan census). **OFF by default** (`@ConditionalOnProperty paralife.diagnostics.death-trace.enabled=true`, no yaml key); a no-op unless enabled. Shipped out-of-band (not a GSD phase) via PR #2 `464594e` 2026-05-27; wired into the tick pipeline (SimulationEngine / EnvironmentEngine / DeathFinalizer / LiveEntityRegistry). Provenance + follow-ups (TD-PR2-A..E): `.planning/STATE.md` §Roadmap Evolution.

**Data modeling:** Immutable records throughout. Sealed interface for polymorphism (`Entity` permits `Particle`, `Rock`, `Nutrient`, `BondedPair`, `CompositeMember`; `Particle` carries the `ParticleType` species enum CATALYST/MEMBRANE/SPORE, `CompositeMember` a `Role` enum). Mutations produce new instances (`Cell.withOccupant()`, `Entity.Particle.withEnergy()`). Wire frames are modelled by the `com.paralife.codec` record family (`Frame`, `CellEntry`, `Event`, `StateChange`) — the old sealed `Messages` type was **deleted in Phase 15-11**.

**Concurrency:** Single-threaded simulation core (all world mutations in tick event handlers). Virtual threads for I/O (WebSocket, tick loop heartbeat). `ReentrantReadWriteLock` on `WorldGrid` — read lock for snapshots, write lock for mutations.

**Spring patterns:** `@Component` beans for all services. `@EventListener` with `@Order` for tick pipeline sequencing. `@ConfigurationProperties` on records for type-safe config binding. Raw `WebSocketHandler` (not STOMP) for full protocol control.

**Testing:** `*Test.java` for unit tests, `*IntegrationTest.java` for integration tests. Mirror source directory structure. `@SpringBootTest` for integration tests.

**Testing philosophy** (learned the hard way via the TD-22-A / TD-22-C decompositions — see `.planning/STATE.md`):

- **Pin mechanics; defer emergence.** Deterministic local mechanics (combat math, energy decay, reproduce cost/cooldown/floor gates, nutrient gain, population census, codec round-trips) belong in fast JUnit tests with assertions pinned to config/spec values. Emergent, statistical outcomes (multi-type survival, population oscillation, niche formation over long runs) are **not** gated in the default suite — they are inherently seed- and tuning-sensitive, so a pinned assertion on them is brittle and breaks the moment entity/env constants are tuned. They live in the opt-in `@Tag("slow")` `EmergenceStabilityLoadTest` (excluded from `./gradlew test`; run via `-PincludeLong=true`) and are otherwise deferred to hyperparameter tuning and the M5 visualiser. **Smell test:** if a "mechanics" test only goes green because the simulation happened to survive a multi-tick run, it is an emergence test in disguise — decompose it into engine-direct assertions on the underlying rule.

- **Assert against independent constants, not the code under test.** Expected values are hand-computed literals or config-derived — never recomputed by calling the same production function the test exercises. A self-referential expected value shifts together with the bug and stays green (e.g. assert a starvation-boosted gain equals the literal `10`, not `(int)(base * (1 + boost * StarvationConfig.computeIntensity(...)))`). Pin to the *intended* contract so code drift fails the test, rather than mirroring whatever the code currently computes.

  **Production constants (entity/env defaults) WILL be tuned to encourage emergence — never couple a mechanics test to their specific magnitudes.** A literal is only sound when the test *owns the inputs that produce it*: build a local `profile(...)` / `StarvationConfig` with constants the test fixes itself, so a hand-computed literal stays valid no matter how `MetabolicProfile.defaults()`, `StarvationConfig.defaults()`, or `SimulationConfig.defaults()` are retuned (this is why the boost literal `10` above is fine — its inputs are test-owned, not the production defaults). When a test genuinely exercises a production default, read the expectation back from the config accessor (e.g. `50 - profile.forType(CATALYST).decayPerTick()`) so it tracks tuning and pins the *transformation contract* — "the engine subtracts exactly the profile's rate" — not the number. A magnitude hardcoded off a tunable default (`isEqualTo(47)` for "default decay 3") is brittle: it goes red on the first tuning pass while telling you nothing about correctness. This is the same principle as "pin mechanics; defer emergence" applied to constants — mechanics tests must survive retuning; only emergence outcomes are allowed to be tuning-sensitive (and those aren't in the default suite).

- **Every negative assertion needs a positive control.** A test that asserts "X does not happen" is vacuous if the action never fired (e.g. an unregistered bot whose action is silently dropped, so the cell is empty for the wrong reason). Pair it with a control proving the same harness *does* produce X under the opposite input — and isolate the gate under test so a co-located guard can't mask its regression (the floor gate subsumes the cost gate for sub-cost energy, so "below cost" pins behaviour, not the cost gate alone).

**Build commands:**
```bash
./gradlew test              # Run all tests
./gradlew bootRun           # Start server on :8080
./gradlew jacocoTestReport  # Generate coverage report
```
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start -->
## Architecture

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
8. `WebSocketKeepaliveService` `@Order(200)` — Keepalive PINGs
9. `TickHealthMonitor` `@Order(Integer.MAX_VALUE)` — Sample tick wall-time into ring buffer

**Env state projection — three layers** (Phase 14, decisions D-38/D-39/D-40/D-41):

| Layer | Surface | Owner | Purpose |
|-------|---------|-------|---------|
| 1. Shadow grids | `byte[][] toxinGrid`, `mutagenGrid` (intensity 0–255) | `EnvironmentEngine` | Authoritative effect state; CA diffusion, spline paths, gossip |
| 2. Status caches | `Map<Position,Byte> cellStatusCache`, `Map<String,Byte> entityStatusCache` | `EnvironmentEngine.buildStatusCaches()` | Per-tick read-only bitmask projection (D-41). Derived from layer 1 + registries (BuffRegistry, Infection map). Rebuilt every tick, not a second source of truth |
| 3. Wire bitmask | `cellStatus` / `entityStatus` bytes carried on the codec `CellEntry` inside each per-bot `Frame` | `TickBroadcaster` `@Order(50)` (per-bot) | Zero-trust vision-scoped bitmask. OVERCROWDED is **redacted per bot**: `cellStatus = (layer2 & ~BIT_OVERCROWDED) \| perBotOvercrowdedBit` — bit 0 recomputed from bot's 5x5 Moore count so outer vision cells correctly under-report global overcrowding (D-40 incomplete-information design) |

Bit layout (D-38 `cellStatus` / D-39 `entityStatus`): OVERCROWDED=bit 0, TOXIN_PRESENT=bit 1 (`0x02`), MUTAGEN_ZONE=bit 2 (`0x04`). STARVING lives on `Cell.flags` (not `entityStatus`) as server-global entity-intrinsic state. `Cell.flags` retains `FLAG_OVERCROWDED`/`FLAG_STARVING` unchanged; env effects do NOT extend `Cell.flags` — intensity values don't fit single bits and cache locality favours per-effect shadow grids.

**Entry points:**
- `ParalifeApplication.main()` — Spring Boot startup
- `TickEngine.onApplicationReady()` (`@EventListener(ApplicationReadyEvent.class)`) — Starts the virtual-thread tick loop (if `paralife.tick.auto-start: true`)
- `WorldWebSocketHandler` — Client connections at `/ws/world`

**Error handling:** Graceful degradation. WebSocket errors send an error frame (`E|<code>|<reason>`, e.g. `E|408|reconnect-required`) to the client. Tick loop catches exceptions and continues. No exception bubbling.

### Outbound concurrency (Phase 17, D-10)

Each connected WebSocket session is paired with one virtual thread that loops
`queue.take(); session.sendMessage(...)` over a per-session bounded
`ArrayBlockingQueue<Frame>` (capacity from `paralife.admission.backpressure.outbound-queue-size`).

**Why VT-per-session and not Jetty native async write:**
- Matches Paralife's stated philosophy — simple blocking code, virtual threads do concurrency.
- Per-session isolation is structural — one slow socket cannot block the tick thread or any other session.
- `queue.size()` is the explicit backpressure signal — observable as
  `paralife.backpressure.stalled.sessions` gauge and per-session via `OutboundSender.queueDepth(sessionId)`.
- Java 21 VTs scheduled on shared carriers; per-VT cost is a few KB heap. 1000+ VTs is acceptable.
- Slow-client detection becomes implicit with Jetty native async (write-Future latency / Jetty internals);
  the API surface differs across Jetty 12 minor versions.

When the queue overflows, the session transitions to STALLED:
- `OutboundSender.offer` invokes the overflow callback registered by `WorldWebSocketHandler`.
- `WorldWebSocketHandler.markStalled` removes `ATTR_ENTITY_ID`, sets `ATTR_STALL_TICK`,
  issues a resume token via `ResumeTokenRegistry.issue`, and detaches the sender VT.
- The next inbound frame from the stalled session receives `E|408|reconnect-required` and the WS is closed.
- The entity is held on the grid for `paralife.admission.backpressure.grace-window-ticks` ticks.
- If the client reconnects with `r|<species>|<resumeToken>` within the grace window, `AdmissionGate` consults
  `ResumeTokenRegistry.tryRebind` and re-binds the new session to the preserved entityId.

Synchronized-session-monitor contract: every writer to a session holds `synchronized(session)` for
the actual `sendMessage` call. Writers: drain VT (`OutboundSender.drainLoop`), keepalive PING
(`WebSocketKeepaliveService.onTick`), out-of-band stall/error frames
(`WorldWebSocketHandler.sendOutOfBand`), and the back-compat fallback in
`WorldWebSocketHandler.sendFrame`. Encoding and metric recording stay outside the monitor — the
monitor only protects the non-thread-safe `sendMessage` invocation.

**markStalled close-then-best-effort-OOB (Phase 19.1, D-07):** `WorldWebSocketHandler.markStalled`
invokes `OutboundSender.detachSession(WebSocketSession, CloseStatus.SERVICE_RESTARTED)` (the
close-aware overload with caller-supplied status), not the `String` overload. The transport-close
fires first, which causes any blocked Jetty write inside the drain VT's `synchronized(session)`
block to throw `IOException`, allowing the VT to exit cleanly. The OOB 408 frame that follows is
best-effort: `WorldWebSocketHandler.sendOutOfBand` carries an `isOpen()` guard (≈`WorldWebSocketHandler.java:1054`);
the close itself is the reconnect signal — OOB is not load-bearing. No second
`session.close(...)` is issued; the close-aware detach already carried
`SERVICE_RESTARTED` to the wire. The close itself is the reconnect signal — clients observing it
issue an `r|<species>|<resumeToken>` against the grace window. This trade-off is intentional: it
eliminates the tick-thread block that the previous `String`-overload path suffered when a slow
client kept the Jetty write blocked.

### Connection model (Phase 18, D-05 / D-21)

**WS:entity 1:1** — one WebSocket connection per entity, always. Every entity on the grid has
exactly one WebSocket session; every WebSocket session owns exactly one entity during the Alive phase.

Many concurrent WebSocket connections is a stated architectural goal. Scale-out is achieved by
running more connections (more bots, more `LoadHarness` JVMs), never by multiplexing multiple
entities over a single connection. Multi-entity-per-session is **strongly discouraged** and requires
an explicit ADR with justification before any exception is considered.

See `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` §1 for full
rationale, exception policy, and the 5 000-connections-per-JVM design ceiling (D-02).

### Runtime tuning (Phase 20)

Per-connection overhead reduction at scale lives in `paralife.runtime.jetty.*` and
`paralife.runtime.app.*` `@ConfigurationProperties` records (Phase 20 D-07 layers
2 + 3, see `JettyRuntimeConfig` and `AppRuntimeConfig`). JVM flags ship as
documented per-scale-tier presets in
`.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` §3, NOT
as wrapper scripts (D-08).

**The WS:entity 1:1 model from §Connection model is non-negotiable.** Tuning
reduces per-connection cost; it does not collapse connections. SCALE-08's "or
equivalent transport-level scale strategy" escape hatch was intentionally taken.
See `20-RUNTIME.md` §1 for the full rationale and §3 for per-tier recipes.

The two metric gauges to watch when tuning are `paralife.tick.health.work-time-ms`
(`AdmissionMetrics.java:70`) and `paralife.outbound.detach.timeout`
(`AdmissionMetrics.java:79`, P19.1 D-18). Profile artifacts under
`.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` are pinned
to commit SHAs (e.g. `62c1b44` churn baseline, `103a615` active-scenario) for
reproducibility per D-19. Headline-gauge
values are sampled from `/actuator/metrics/{name}` into JSON sidecars at capture
time (Pass-2 Concern #10) — `application.yml:15` exposes the `metrics` actuator
endpoint that Plan 1c + Plan 5 capture from.

D-20 keeps `paralife.admission.backpressure.outbound-queue-size` in
`AdmissionConfig` rather than moving it under `paralife.runtime.app.*`; namespace
consolidation is Phase 999.4. Codec hot-path opts (D-10, layer 4 of the tuning
surface) are JFR-driven and never cross the wire — `15-SCHEMA.md` stays bit-exact.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start -->
## Project Skills

No project-specific skills configured. (GSD workflow commands are being retired — see §GSD Workflow Enforcement.)
<!-- GSD:skills-end -->

## On-Demand Skills (web/mobile fallback)

We do ~95% of development via the Claude Code **CLI**, where the
[Superpowers](https://github.com/obra/Superpowers) skill library is installed as
a plugin and the skills below load automatically. **Web/mobile** sessions clone
this repo fresh and do **not** have that plugin. We chose not to vendor the
skills into the repo (they're ~10k lines). Instead:

> **If the user requests any of the following skills and they are NOT already
> available to you, fetch and read them from the raw URLs below before
> proceeding.** Read the listed companion files too — they're load-bearing and
> the skills reference them by relative path that won't resolve over raw HTTP.

Pinned base (commit `896224c`, plugin v6.0.3 — swap the SHA for `main` to track latest):
`https://raw.githubusercontent.com/obra/Superpowers/896224c4b1879920ab573417e68fd51d2ccc9072/skills/`

| Skill | `SKILL.md` path | Also read (companions) |
|-------|-----------------|------------------------|
| `receiving-code-review` | `receiving-code-review/SKILL.md` | — |
| `requesting-code-review` | `requesting-code-review/SKILL.md` | `requesting-code-review/code-reviewer.md` |
| `test-driven-development` | `test-driven-development/SKILL.md` | `test-driven-development/testing-anti-patterns.md` |
| `writing-plans` | `writing-plans/SKILL.md` | — |
| `subagent-driven-development` | `subagent-driven-development/SKILL.md` | `subagent-driven-development/implementer-prompt.md`, `subagent-driven-development/task-reviewer-prompt.md` |

**`subagent-driven-development` references sibling Superpowers skills.** Two of
them — `requesting-code-review`, `test-driven-development` — are in the table
above. The other three are intentionally **omitted because they don't apply in a
web/mobile remote session**; when SDD points to them, do this instead:

- `using-git-worktrees` → **skip.** The remote container is already an isolated,
  fresh clone on a dedicated branch; SDD's "set up an isolated workspace" step is
  already satisfied. (This skill exists mainly to dodge CLI worktree gotchas.)
- `executing-plans` → **skip.** It's the no-subagent fallback; subagents *are*
  available here, so `subagent-driven-development` already supersedes it.
- `finishing-a-development-branch` → **skip.** Branch integration is governed by
  the remote harness (commit/push to the designated branch; open a PR only when
  the user explicitly asks).

<!-- GSD:gsd-workflow-start -->
## GSD Workflow Enforcement

> **⚠️ Migration period (since 2026-06-23):** Paralife is migrating off GSD. The
> rules below were authored by GSD's tooling and are **no longer hard
> requirements**. In particular, the "never modify docs outside a GSD workflow"
> constraint is **relaxed**: directly editing docs — including files under
> `.planning/`, `docs/`, this `CLAUDE.md`, `README.md`, and the decision/roadmap
> registers — is fine and **encouraged where it keeps them accurate**. You no
> longer need a GSD skill to touch a doc. Treat the items below as historical
> context for the GSD-era process, not as gates. (See
> `workflow-migration-investigation-prompt.md` for the migration background.)

This project used the GSD (Get Shit Done) workflow. During the GSD era the rules were:
- ~~Never modify files in `.planning/` without being instructed by a GSD workflow~~ — relaxed during migration; doc edits are fine.
- Always check `.planning/STATE.md` for current project position before starting work
- Follow the phase-based development process: discuss → plan → execute → verify
- Respect dependency ordering between phases as defined in `.planning/ROADMAP.md`
<!-- GSD:gsd-workflow-end -->

<!-- GSD:developer-profile-start -->
## Developer Profile

No developer profile generated yet. Run `/gsd-profile-user` to create one.
<!-- GSD:developer-profile-end -->

## Planning Artifacts Guide

This project has artifacts from two different project management tools due to a mid-project migration.

### GSD2 artifacts (`.gsd/`)

Used for M001 (Foundation) and initial M002 tracking. **Only a markdown subset is retained in the repo** — the live GSD2 SQLite database (`gsd.db`), `state-manifest.json`, and `event-log.jsonl` are **not present** (defunct/gitignored). What remains under `.gsd/`:

- **`DECISIONS.md`** — Decision register (D001–D005). Markdown projection of the original GSD2 `decisions` table.
- **`milestones/`** — Archived milestone hierarchy (`M00x-ROADMAP.md`, `M00x-SUMMARY.md`, slice/task plans).

### GSD1 artifacts (`.planning/`)

Adopted after the 2026-04-11 migration; the live planning corpus from M002 onward (now milestone v3.0).

- **`PROJECT.md`** — Project vision, core value, current milestone.
- **`ROADMAP.md`** — Phase checklist across all milestones (v1.0–v3.0).
- **`STATE.md`** — Session continuity state (current position, last activity, blockers).
- **`REQUIREMENTS.md`** — Active-milestone requirements (currently v3.0 `SCALE-01..10`).
- **`MILESTONES.md`** — Milestone archive index.
- **`config.json`** — Workflow preferences (`{"version": 1}`).
- **`codebase/`** — `ARCHITECTURE.md`, `STRUCTURE.md`, `STACK.md`, `INTEGRATIONS.md` — detailed codebase analysis. ⚠️ Partly stale (e.g. `ARCHITECTURE.md` lists only 3 of the 5 `Entity` permits and a "4-phase" pipeline); cross-check against code.
- **`DOC-RECONCILIATION-FINDINGS.md`** — prior doc-vs-code audit (2026-04-20).
- **`phases/01–22/` (plus `999.x`)** — Per-phase directories. Phases 01–05 (GSD2-era) have only `CONTEXT.md`; later phases add `PLAN.md`, `SUMMARY.md`, research/review/task breakdowns.
- **`milestones/`** — Archived per-milestone audits & roadmaps (`v1.0-MILESTONE-AUDIT.md`, `v2.0-MILESTONE-AUDIT.md`, `v2.0-REQUIREMENTS.md`, …).

### Migration notes

- **GSD is being retired (migration started 2026-06-23).** The old "only a GSD
  skill may edit GSD-managed docs" rule no longer applies — see §GSD Workflow
  Enforcement above. Editing any doc directly (including `.planning/` artifacts)
  is permitted and encouraged where it keeps them accurate.
- M001 phases (01–05) were managed by GSD2 — their archived artifacts are under `.gsd/milestones/`.
- M002 phases (06–10) have artifacts in both systems: GSD2 markdown plus GSD1-format plans/summaries under `.planning/phases/`.
- `.planning/REQUIREMENTS.md` is the active-milestone requirements doc (v3.0). M001/M002 (GSD2) had **no** `REQUIREMENTS.md` — GSD2 used `success_criteria` JSON in its milestones DB instead.

### Known tech debt (from the v1.0 milestone audit) — all resolved

The four items the v1.0 audit flagged have since been fixed (re-confirmed against code 2026-06-23):

| Phase | Item | Status |
|-------|------|--------|
| 06 | `Cell.nutrientLevel` was inert | ✅ Resolved — now written by `EnvironmentEngine`/`FertilityInitializer` and read as a fertility multiplier in `SimulationEngine` |
| 09 | `HeuristicBrain.predatorType` dead-branch ternary | ✅ Resolved — unconditional `myType.predator()` (Phase 09 #3 fix) |
| 09 | `BotClient` used raw `JsonNode`/`LinkedHashMap` | ✅ Resolved — now uses `com.paralife.codec` (`Frame` + `PerceptionCodec`) |
| 10 | `LoadTest` omitted `nutrient-consume-energy` | ✅ Resolved — set explicitly in `LoadTest` |
