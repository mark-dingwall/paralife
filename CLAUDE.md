<!-- GSD:project-start -->
## Project

Paralife is a distributed living simulation — a toroidal 2D world populated by competing entity types (Catalyst, Membrane, Spore) in rock-paper-scissors dynamics. A Spring Boot server runs the physics tick loop, broadcasts state via WebSocket, and receives actions from autonomous heuristic bot clients. Built with Java 21 virtual threads for massive concurrency with simple blocking code.

**Core Value:** Emergent spatial behaviour — spiral waves, population oscillations, and niche formation — arising from simple local rules. A testbed for evolving entity intelligence from heuristic bots toward genetic/learning systems.

**Requirements** (from milestone success criteria):
- M001: Server boots, tick loop runs, WebSocket broadcasts, 100 concurrent bots sustained
- M002: RPS entity dynamics, 4-phase physics, perception/action protocol, heuristic bots, population stability over 500+ ticks
<!-- GSD:project-end -->

<!-- GSD:technology-start -->
## Technology Stack

- **Java 21** — Virtual threads enabled (`spring.threads.virtual.enabled: true`)
- **Spring Boot 3.4.4** — `starter-web`, `starter-websocket`, `starter-actuator`
- **Gradle Kotlin DSL** — Build system with wrapper (`./gradlew`)
- **JUnit 5** — 166 tests (unit + integration)
- **JaCoCo** — Coverage reporting (XML + HTML)
- **Jackson** — JSON serialization (transitive via Spring)
- **`@ConfigurationProperties`** bound to records: `GridConfig`, `TickConfig`, `SimulationConfig`
<!-- GSD:technology-end -->

<!-- GSD:conventions-start -->
## Conventions

**Package structure:** `com.paralife.{world,engine,websocket,bot}` — flat single-level per layer. Plus `com.paralife.diagnostics` — `DeathDiagnostics` (flag-gated death-cause + lifespan census). **OFF by default** (`@ConditionalOnProperty paralife.diagnostics.death-trace.enabled=true`, no yaml key); a no-op unless enabled. Shipped out-of-band (not a GSD phase) via PR #2 `464594e` 2026-05-27; wired into the tick pipeline (SimulationEngine / EnvironmentEngine / DeathFinalizer / OutboundSender / LiveEntityRegistry). Provenance + follow-ups (TD-PR2-A..E): `.planning/STATE.md` §Roadmap Evolution.

**Data modeling:** Immutable records throughout. Sealed interfaces for polymorphism (`Entity`, `Messages`). Mutations produce new instances (`Cell.withOccupant()`, `Particle.withEnergy()`).

**Concurrency:** Single-threaded simulation core (all world mutations in tick event handlers). Virtual threads for I/O (WebSocket, tick loop heartbeat). `ReentrantReadWriteLock` on `WorldGrid` — read lock for snapshots, write lock for mutations.

**Spring patterns:** `@Component` beans for all services. `@EventListener` with `@Order` for tick pipeline sequencing. `@ConfigurationProperties` on records for type-safe config binding. Raw `WebSocketHandler` (not STOMP) for full protocol control.

**Testing:** `*Test.java` for unit tests, `*IntegrationTest.java` for integration tests. Mirror source directory structure. `@SpringBootTest` for integration tests.

**Build commands:**
```bash
./gradlew test              # Run all tests
./gradlew bootRun           # Start server on :8080
./gradlew jacocoTestReport  # Generate coverage report
```
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start -->
## Architecture

**4 layers:**

| Layer | Package | Key Files |
|-------|---------|-----------|
| World | `com.paralife.world` | `WorldGrid`, `Cell`, `Entity` (sealed), `Position` |
| Engine | `com.paralife.engine` | `SimulationEngine`, `ActionResolver`, `TickEngine`, `BotRegistry` |
| WebSocket | `com.paralife.websocket` | `WorldWebSocketHandler`, `TickBroadcaster`, `Messages` (sealed), `SessionRegistry` |
| Bot | `com.paralife.bot` | `BotClient`, `HeuristicBrain`, `BotLauncher`, `BotRunner` (autonomous client processes; `BotRunner` is the operator CLI invoked via `./gradlew runBot`, 100-bot cap) |

**Tick pipeline** (Spring `@EventListener` on `TickEvent`):
1. `ResumeTokenRegistry` `@Order(1)` — Grace-expiry sweep; dead-entity cleanup before SimulationEngine
2. `SimulationEngine` `@Order(10)` — Combat, energy decay, death removal, nutrient spawning
3. `EnvironmentEngine` `@Order(TICK_ORDER)` — Toxin/mutagen/lightning/compost; rebuilds status caches (TICK_ORDER=14)
4. `CompositeEnergyDistributor` `@Order(15)` — Composite passive energy drain
5. `ActionResolver` `@Order(20)` — Drain pending bot actions, resolve moves/consume/reproduce/rest
6. `EnvPostActionReconciler` `@Order(TICK_ORDER)` — Apply post-action buff grants, clear cure-immunity (TICK_ORDER=25)
7. `TickBroadcaster` `@Order(50)` — Per-bot tick frame (5x5 vision, wire bitmask, perception)
8. `WebSocketKeepaliveService` `@Order(200)` — Keepalive PINGs
9. `TickHealthMonitor` `@Order(Integer.MAX_VALUE)` — Sample tick wall-time into ring buffer

**Env state projection — three layers** (Phase 14, decisions D-38/D-39/D-40/D-41):

| Layer | Surface | Owner | Purpose |
|-------|---------|-------|---------|
| 1. Shadow grids | `byte[][] toxinGrid`, `mutagenGrid` (intensity 0–255) | `EnvironmentEngine` | Authoritative effect state; CA diffusion, spline paths, gossip |
| 2. Status caches | `Map<Position,Byte> cellStatusCache`, `Map<String,Byte> entityStatusCache` | `EnvironmentEngine.buildStatusCaches()` | Per-tick read-only bitmask projection (D-41). Derived from layer 1 + registries (BuffRegistry, Infection map). Rebuilt every tick, not a second source of truth |
| 3. Wire bitmask | `Messages.CellView.cellStatus`, `entityStatus` bytes | `TickBroadcaster` `@Order(50)` (per-bot) | Zero-trust vision-scoped bitmask. OVERCROWDED is **redacted per bot**: `cellStatus = (layer2 & ~BIT_OVERCROWDED) \| perBotOvercrowdedBit` — bit 0 recomputed from bot's 5x5 Moore count so outer vision cells correctly under-report global overcrowding (D-40 incomplete-information design) |

Bit layout (D-38 `cellStatus` / D-39 `entityStatus`): OVERCROWDED=bit 0, TOXIN_PRESENT=bit 1 (`0x02`), MUTAGEN_ZONE=bit 2 (`0x04`). STARVING lives on `Cell.flags` (not `entityStatus`) as server-global entity-intrinsic state. `Cell.flags` retains `FLAG_OVERCROWDED`/`FLAG_STARVING` unchanged; env effects do NOT extend `Cell.flags` — intensity values don't fit single bits and cache locality favours per-effect shadow grids.

**Entry points:**
- `ParalifeApplication.main()` — Spring Boot startup
- `TickEngine.@PostConstruct` — Starts virtual thread tick loop (if `paralife.tick.auto-start: true`)
- `WorldWebSocketHandler` — Client connections at `/ws/world`

**Error handling:** Graceful degradation. WebSocket errors send `Error` message to client. Tick loop catches exceptions and continues. No exception bubbling.

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
best-effort: `WorldWebSocketHandler.sendOutOfBand` carries an `isOpen()` guard at line 965;
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

No project-specific skills configured. Use GSD workflow commands for project management.
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

This project uses GSD (Get Shit Done) workflow. Follow these rules:
- Never modify files in `.planning/` without being instructed by a GSD workflow
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

Used for M001 (Foundation) and initial M002 tracking.

- **`gsd.db`** — SQLite database. Authoritative source for milestones, slices, tasks, decisions, quality gates, verification evidence, and assessments. Query with `sqlite3 .gsd/gsd.db`.
- **`DECISIONS.md`** — Append-only decision register (D001–D007). Markdown projection of the `decisions` table.
- **`milestones/M00x/`** — Hierarchy: `M00x-ROADMAP.md`, `M00x-SUMMARY.md`, `M00x-VALIDATION.md`, then `slices/S0x/S0x-PLAN.md` and `tasks/T0x-PLAN.md` / `T0x-SUMMARY.md`.
- **`state-manifest.json`** — JSON snapshot for headless queries.
- **`event-log.jsonl`** — JSONL event stream.

### GSD1 artifacts (`.planning/`)

Used for M002 (Living Simulation) after migration on 2026-04-11.

- **`PROJECT.md`** — Project vision, core value, requirements.
- **`ROADMAP.md`** — Phase checklist (all 10 phases, both milestones).
- **`STATE.md`** — Session continuity state (current position, last activity, blockers).
- **`config.json`** — Workflow preferences (`{"version": 1}`).
- **`codebase/`** — `ARCHITECTURE.md`, `STRUCTURE.md`, `STACK.md`, `INTEGRATIONS.md` — detailed codebase analysis.
- **`phases/01–10/`** — Per-phase directories. Phases 01–05 (GSD2-era) have only `CONTEXT.md`. Phases 06–10 (GSD1-era) have `PLAN.md`, `SUMMARY.md`, task breakdowns.
- **`v1.0-MILESTONE-AUDIT.md`** — Post-completion audit with requirement scores, tech debt, resolved issues.

### Migration notes

- M001 phases (01–05) were fully managed by GSD2 — their artifacts are in `.gsd/milestones/M001/`.
- M002 phases (06–10) have artifacts in both systems: GSD2 DB has slice/task records, `.planning/phases/` has GSD1-format plans and summaries.
- No `REQUIREMENTS.md` exists — GSD2 used `success_criteria` JSON in the `milestones` DB table instead.

### Known tech debt (from milestone audit)

| Phase | Item | Severity |
|-------|------|----------|
| 06 | `Cell.nutrientLevel` field is inert — defined and serialised but never written | Low |
| 09 | `HeuristicBrain.predatorType` ternary has dead branch (both branches return same value) | Low |
| 09 | `BotClient` uses raw `JsonNode`/`LinkedHashMap` instead of `Messages` sealed types | Low |
| 10 | `LoadTest` omits explicit `nutrient-consume-energy` property (falls back to default) | Low |
