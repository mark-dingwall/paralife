# Phase 18: External Load Harness & Harness Identity - Context

**Gathered:** 2026-04-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Build a first-class **external load harness** that scales past `BotRunner`'s 100-bot single-process ceiling, attribute traffic per-harness in server-side metrics and logs (extending the origin-blind admission shape locked by Phase 17 D-03), and keep `BotRunner` as the supported ≤100 operator path. Harness usage is documented well enough that Phase 21 can run repeatable 100/500/1000+ benchmark sweeps. Closes SCALE-03 + SCALE-04 + SCALE-05.

**In scope:**
- New standalone harness JVM artifact (`com.paralife.harness.LoadHarness`) with full CLI surface
- Refactor of `BotLauncher` into a small fleet abstraction reused by both BotRunner (≤100) and harness (1000+)
- Harness identity carriage via WebSocket handshake headers (`X-Paralife-Harness`, `X-Paralife-Source`)
- Two-tag attribution scheme (`source`, `harness`) plumbed through admission + backpressure metrics
- Log marker extensions on existing `ADMISSION` / `BACKPRESSURE` / `TICK-HEALTH` channels and new `HARNESS` lifecycle marker
- Bounded-cardinality protection on the `harness` tag value
- Crash-safe JSON run report with overwrite + append (JSONL) modes
- New `18-HARNESS.md` spec doc (mirror of `17-ADMISSION.md` style); CLAUDE.md note codifying the WS:entity 1:1 architectural principle
- BotFactory seam (forward-compat scaffold for backlog 999.2 bot-driven offspring; no producer this phase)

**Not in scope (other phases / backlog):**
- High-density placement / partition-aware world execution (Phase 19, SCALE-06/07)
- Connection multiplexing / runtime tuning (Phase 20, SCALE-08/09)
- Benchmark gate + scale reports (Phase 21, SCALE-10) — Phase 18 only ships the harness
- Bot-driven offspring producer wire shape (backlog 999.2; this phase ships only the BotFactory seam and reserves `source=offspring`)
- Multi-entity-per-session protocol (D-21 strongly discouraged; case-by-case exception path)
- Cross-harness ramp/result coordination (operators run N independent JVMs; aggregation via `jq`)
- Docker packaging (M6 Deployment scope)
- `/actuator/prometheus` wiring (M5; Phase 17 D-20 stays)
- Live-tunable harness whitelist / dashboard surface (M5)

</domain>

<decisions>
## Implementation Decisions

### Scale Model (SCALE-03 / SCALE-05)

- **D-01:** Harness is a single-JVM, virtual-thread-per-bot process (`com.paralife.harness.LoadHarness`). Operational scale-out is handled by running **N independent harness JVMs side-by-side** (`10×100`, `4×250`, etc.) — each with its own harness id; per-harness identity makes server-side attribution work naturally without any built-in cross-instance orchestrator. Single-JVM aligns with `spring.threads.virtual.enabled`, matches Paralife's "simple blocking code, virtual threads do concurrency" philosophy, and is the natural extension of the existing `BotLauncher` pattern.
- **D-02:** Design ceiling: **5000 concurrent WS connections per JVM**. 5× headroom over Phase 21's 1000+ benchmark target; "designed for 5000 concurrent WS connections" is itself a meaningful architectural property. Phase 21 can push past with measured evidence.
- **D-03:** Configurable ramp-up. CLI flag `--ramp-up=instant|rate:<n>|wave:<count>:<sleep-ms>`:
  - `instant` — current `BotLauncher` behavior; fires all VTs in a tight loop. Available specifically for stress-testing the `tick-overload` admission gate.
  - `rate:<n>` (default `rate:50`) — `n` bot starts per second; keeps the tick-overload gate calm during ramp.
  - `wave:<count>:<sleep-ms>` — bursts of `count` bots followed by `sleep-ms` pauses; useful for synthetic traffic shapes.
- **D-04:** **Refactor `BotLauncher`**, do not fork. Lift the 30s `allDone.await(30, TimeUnit.SECONDS)` ceiling (`BotLauncher.java:67`) so 1000+ bot launches don't generate spurious "Not all bots finished connecting within timeout" log noise. Extract a small fleet abstraction (working name: `BotFleet`; final name = Claude's Discretion) shared by `BotRunner` (≤100, instant ramp by default) and the harness (1000+, rate-limited ramp by default). Both implementations keep using `BotClient` plumbing as-is. Per-bot connect/register tracking becomes async (e.g. `CompletableFuture<RegistrationResult>`) instead of a single `CountDownLatch.await(30s)`. `BotRunner`'s observable behavior must not regress.
- **D-05:** **Architectural principle: WS:entity 1:1 by default.** Many independent WebSocket connections is a stated Paralife property — the project pursues a massively parallel architecture demonstrating scale via concurrent connections. Multiplexing / multi-entity-per-session is **strongly discouraged**, not banned (see D-21). This principle is captured in `18-HARNESS.md` and `CLAUDE.md` so future work has the rationale on hand.

### Identity Carriage (SCALE-04)

- **D-06:** Harness identity rides on the WebSocket **handshake via HTTP headers**:
  - `X-Paralife-Harness: <harness-id>`
  - `X-Paralife-Source: <operator|harness>`

  Server reads from `session.getUpgradeRequest().getHeaders()` at `WorldWebSocketHandler.afterConnectionEstablished` and stashes them as session attributes (suggested keys: `ATTR_SOURCE`, `ATTR_HARNESS`). **Zero codec change**, zero edit to the locked `15-SCHEMA.md` `r|` grammar. The header path was chosen over a new `r|` slot specifically because `15-SCHEMA.md` is milestone-locked; over a query param because URLs leak in proxy logs; and over a control frame because it adds a round-trip without buying flexibility we'd use.
- **D-07:** **Identity granularity: per-process.** One harness id per JVM; all bots in that process share it. Bounded cardinality for Micrometer tags. Per-bot triage continues to use existing `entityId` / `sessionId` in logs.
- **D-08:** **Default when handshake header is absent: `source=unknown`.** Sessions without explicit identity (ad-hoc `wscat` probes, integration tests that didn't set the header) tag as `unknown`. Reserved strictly for sessions whose origin we can't classify.
- **D-09:** **`BotRunner` explicitly sets `X-Paralife-Source: operator`** (no `X-Paralife-Harness`). Update path: `BotClient` accepts handshake-header configuration via constructor or builder; `BotRunner` opts in. This keeps `unknown` semantically distinct from the supported ≤100 operator path.
- **D-10:** **Cardinality policy: bounded + overflow.** Cap of **64 distinct `harness` tag values** observed per JVM lifetime. The 65th-and-beyond fold into `harness=overflow`, and a one-time warning log line is emitted on first overflow:
  ```
  HARNESS overflow first-seen tick=<n> harness-id=<truncated>
  ```
  The cap is config-tunable via `paralife.admission.attribution.max-harness-cardinality` (default 64; sub-namespace = Claude's Discretion — could equally live under `paralife.harness.*`). Rationale: costless Prometheus safety net against a misconfigured harness loop minting a uuid-per-launch.

### Attribution Surface (extends Phase 17 D-03 / D-17 / D-18 / D-19)

- **D-11:** **Two-tag scheme:**
  - `source` ∈ `{operator, harness, unknown, overflow, offspring}` — bounded taxonomy. `offspring` is **reserved-now-with-no-producer** (D-20 forward-compat for backlog 999.2).
  - `harness=<id>` — emitted **only when `source=harness`**. Bounded by D-10.

  Single-tag schemes (e.g. `source=harness-A`) were rejected because they conflate "kind of session" with "instance id" and make grafana queries like "all harness traffic regardless of which instance" awkward.
- **D-12:** **All admission and backpressure metrics gain the new tags:**
  - `paralife.admission.rejected{reason, source[, harness]}` — extends the Phase 17 D-17 single tagged counter
  - `paralife.admission.active.entities{source[, harness]}` — extends Phase 17 D-18
  - `paralife.backpressure.stalled.sessions{source[, harness]}` — extends Phase 17 D-18
  - `paralife.admission.ingress.overwrites{source[, harness]}` — extends Phase 17 D-09 counter
  - `paralife.admission.maintenance` — **stays scalar** (server-global, not per-source)
  - `paralife.tick.health.work-time-ms` — **stays scalar** (server-global; tick health is a property of the server, not of any individual session origin)
- **D-13:** **Log marker prefixes extended.** Every emission of `ADMISSION` / `BACKPRESSURE` / `TICK-HEALTH` log markers gains a `source=<v>[ harness=<id>]` field. Operator cheat-sheet from Phase 17 (`grep -E 'ADMISSION|BACKPRESSURE|TICK-HEALTH' server.log`) extends naturally to `grep 'harness=harness-A'` for per-instance triage.
- **D-14:** **New session-lifecycle log markers** (matches Phase 16 / Phase 17 grep-friendly low-cardinality style):
  ```
  HARNESS connected    tick=<n> session=<sid> harness=<id> source=<v> active=<count>
  HARNESS disconnected tick=<n> session=<sid> harness=<id> source=<v> reason=<token|graceful>
  ```
  Emitted from `WorldWebSocketHandler.afterConnectionEstablished` / `afterConnectionClosed`. Phase 17's existing connection-lifecycle logging stays; these are structured supplements that join the per-harness picture.

### Repro & Invocation (SCALE-04 / Phase 21 enabler)

- **D-15:** **Packaging:** shaded fat JAR (Gradle task name = Claude's Discretion; suggested `loadHarnessJar`) producing a runnable artifact with main class `com.paralife.harness.LoadHarness`, plus a `./gradlew runHarness --args='...'` task that invokes the same main class for dev iteration. Spring Boot's existing fat-jar tooling is reused. **No Docker this phase** — Deployment concerns are M6 scope; `java -jar` is sufficient for Phase 21 and any local/CI benchmark scripting.
- **D-16:** **Config surface: CLI flags only**, with `PARALIFE_HARNESS_*` env vars as overrides for headless contexts. **No YAML config file** this phase (revisit only if Phase 21 ends up maintaining many committed benchmark profiles).

  Required flags:
  - `--server-uri` (e.g. `ws://localhost:8080/ws/world`)
  - `--count` (e.g. `1000`)

  Optional flags (with sensible defaults):
  - `--harness-id=<id>` — auto-generated `harness-<short-uuid>` if omitted; logged at startup so operators can correlate
  - `--ramp-up=instant|rate:<n>|wave:<count>:<sleep-ms>` — default `rate:50`
  - `--species-mix=balanced|<C-frac>:<M-frac>:<S-frac>` — default `balanced` (matches `BotLauncher`)
  - `--duration=<seconds>` — default indefinite (SIGTERM/SIGINT exit clean)
  - `--report-out=<path>` — default `./harness-<id>-report.json`
  - `--report-mode=overwrite|append` — default `overwrite`
  - `--report-interval=<seconds>` — default `30`, allowed range `10..300`
- **D-17:** **JSON run report:**
  - **Always** written via atomic temp + rename (`<path>.tmp` → `<path>`) so external readers never observe a half-written file.
  - **Overwrite mode (default):** single JSON object always reflecting current state.
  - **Append mode:** **JSONL** — first line is a header object carrying static config (harness id, server URI, target count, start wall-time, JVM version, build SHA when available); subsequent lines carry **only rolling counters** to keep per-line size small.
  - **Counter set** (per-write content):
    - `peak_registered`, `current_registered`
    - `connect_failures_total`
    - `e408_reconnect_required_total` — count of STALLED-pivot signals received from server (Phase 17 D-11)
    - `respawns_total` — sum across all bots
    - `actions_sent_total`, `perceptions_received_total`, `syncs_received_total`
    - `wall_time_seconds_elapsed`
    - `exit_reason` — present **only on the final write**; one of `signal-int`, `signal-term`, `duration-reached`, `fatal-error`
- **D-18:** **Documentation home:**
  - **Canonical:** `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` — full spec; mirror of `17-ADMISSION.md` style. Sections: **Architectural Principles** (codifies D-05 / D-21), **Identity Wire Shape**, **CLI Surface**, **Attribution Tagging Schema**, **JSON Report Schema**, **Sample Benchmark Commands** (100/500/1000-bot recipes), **Forward Notes** (offspring producer placeholder for 999.2; multi-entity exception path; multi-instance coordination).
  - **`CLAUDE.md`:** new "Connection model" subsection (or extension of the existing "Outbound concurrency" section) cross-referencing `18-HARNESS.md`. Codifies WS:entity 1:1 (D-05 / D-21) for future contributors.

### Forward-Compat for Backlog 999.2 (Bot-Driven Offspring)

- **D-19:** **`BotFactory` seam.** During the BotLauncher refactor (D-04), extract bot-creation into a `BotFactory` (final API name = Claude's Discretion) that the fleet uses both at startup and (later) on demand. The factory signature includes optional parameters reserved for 999.2 (e.g. `claimEntityId`, `claimToken`) that are no-ops / null today. WS:entity stays 1:1 (D-05); when 999.2 lands, a new bot mints a fresh WS connection and uses a future claim opcode (the wire shape is **999.2's call**, not this phase's). The factory makes "spin up a new bot programmatically when triggered" possible without rewriting the fleet abstraction. ~1-2 hours of design work now vs a multi-day rework when 999.2 ships.

  **Note on rejected alternative — Brain×Entity decoupling:** an earlier proposal would have decoupled the `Brain` from the connection so a single connection could carry multiple brains. That design is rejected as over-abstraction given D-05; with WS:entity 1:1 a Brain is always per-entity-per-connection, and a `BotFactory` is the right-sized seam for the producer story.
- **D-20:** **`source=offspring` reserved in the source taxonomy from day one.** No producer this phase; documented in `18-HARNESS.md` as a known-future value 999.2 will populate. Dashboards and grafana queries built off `18-HARNESS.md` should treat it as a reserved value so they don't need rework when 999.2 ships.
- **D-21:** **Multi-entity-per-session is strongly discouraged but not banned.** Default policy: WS:entity 1:1 (D-05). Rationale (recorded in `18-HARNESS.md` and `CLAUDE.md`): many concurrent WS connections is a core architectural goal. Exceptions are reviewed case-by-case — deviation requires an explicit justification in an ADR or future-phase spec. `18-HARNESS.md` adds a Forward Notes entry describing the open question for 999.2: fresh-WS-per-offspring (default path; cheap given Phase 17 resume tokens / VT outbound / admission already paid for) vs case-by-case exception.

### Claude's Discretion

- Auto-generated harness-id format: keep `< 32 chars`, alphanumeric + dash. UUID short / `hostname-pid-suffix` / random hex are all fine.
- `BotFactory` final API name and module location (`com.paralife.harness.BotFactory` vs `com.paralife.bot.BotFactory`).
- Fleet abstraction final name (`BotFleet` / `BotPool` / `BotSwarm` — semantic flavor).
- `LoadHarness` package location (`com.paralife.harness` vs `com.paralife.bot.harness`). Suggested `com.paralife.harness` for clean namespace.
- Whether `paralife.admission.attribution.max-harness-cardinality` lives under `paralife.admission.*` (cohesive with admission tags) or `paralife.harness.*` (cohesive with harness namespace).
- Sub-record decomposition of any new harness-side `@ConfigurationProperties` (none required server-side beyond D-10's cardinality cap).
- Whether sample benchmark commands in `18-HARNESS.md` are copy-paste shell scripts or just documented invocations — pick whichever Phase 21 will find easier to extend.
- Exact field name choices in the JSON report (`peak_registered` vs `registered.peak` etc.) — pick one casing convention and stay consistent.
- Whether the Phase 17 `ResumeTokenRegistry` rebind path needs to preserve the new `ATTR_HARNESS` / `ATTR_SOURCE` session attributes across the rebind — strongly suggested yes; verify during planning.
- Whether `LoadTest.java` is updated to opt-in to the harness-tagged path (recommended) or stays untouched as the original pre-attribution baseline (less recommended; reduces test coverage of the new path).

### Folded Todos

None — `gsd-sdk query todo.match-phase 18` returned 0 matches.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & Requirements
- `.planning/ROADMAP.md` — Phase 18 entry (Goal, Depends on Phase 17, Requirements SCALE-03/04/05, Success Criteria); v3.0 Scale Engineering milestone framing
- `.planning/REQUIREMENTS.md` — SCALE-03 (standalone harness > 100 bots), SCALE-04 (harness identity in metrics/logs), SCALE-05 (BotRunner stays for ≤100)
- `.planning/PROJECT.md` — v3.0 / M4 active milestone; "external multi-process load harness with harness identity, per-harness metrics, and repeatable 100/500/1000+ bot runs"

### Prior Phase Context (decisions this phase builds on)
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` — D-02 forward note (admission stays origin-neutral; offspring will fall under admission naturally), **D-03** (Phase 17 is origin-blind; Phase 18 grows the source axis), token taxonomy locked, resume-token wire shape locked
- `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` — **§9 Forward Notes** ("Phase 18 adds a `source` tag to `paralife.admission.rejected`"); rejected counter shape (D-17); FSM diagram including STALLED — read for the full admission contract Phase 18 layers attribution onto
- `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` — **§6.1 `r|` grammar**; **milestone-locked** for Phase 18 (we extend identity via HTTP handshake headers, NOT the wire grammar). Cross-reference only; **do not edit**
- `.planning/phases/15-protocol-transport-overhaul/15-CONTEXT.md` — compact-text codec, raw `WebSocketHandler`, FSM (Unregistered/Alive/Dead/STALLED), Micrometer metrics infra (D-10), Jetty 12 native `WebSocketClient` patterns
- `.planning/phases/16-emergent-behavior-tests/16-CONTEXT.md` — D-14 Micrometer tagged-counter pattern (`paralife.<area>.<event>`); D-15 grep-friendly log-prefix style (mirrored by D-13 / D-14 here)

### Backlog / Forward
- `.planning/phases/999.2-offspring-entities-become-bot-driven-m5-flower-rendering-fal/` — directly informs D-19 / D-20 / D-21 forward-compat decisions

### Source Files (refactor / extension targets)
- `src/main/java/com/paralife/bot/BotRunner.java` — ≤100 cap CLI; **add** `X-Paralife-Source: operator` header on launched `BotClient` instances (D-09); update non-goals Javadoc to point at the new harness as the M4-supplied alternative (D-15)
- `src/main/java/com/paralife/bot/BotLauncher.java` — **refactor target** for D-04. Lift `allDone.await(30, TimeUnit.SECONDS)` (line 67); split into a fleet abstraction shared with the new harness; per-bot connect/register tracking becomes async (e.g. `CompletableFuture<RegistrationResult>`)
- `src/main/java/com/paralife/bot/BotClient.java` — **add** support for setting `X-Paralife-Harness` and `X-Paralife-Source` headers on the Jetty `ClientUpgradeRequest` (likely a new constructor arg or builder addition; Jetty's `ClientUpgradeRequest.addHeader` already supports this — D-06 implementation is one method call). Preserve the resume-token / respawn FSM (Phase 17 D-13) untouched
- `src/main/java/com/paralife/bot/HeuristicBrain.java` — referenced by `BotFactory` (D-19); behavior unchanged this phase
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — `afterConnectionEstablished` reads `X-Paralife-Harness` / `X-Paralife-Source` from upgrade headers; stash as session attributes (`ATTR_HARNESS`, `ATTR_SOURCE`); pass to `AdmissionGate` / metric-emission sites; emit new `HARNESS connected` / `HARNESS disconnected` log markers (D-14)
- `src/main/java/com/paralife/admission/AdmissionGate.java` — read source/harness session attrs; pass through to `AdmissionMetrics` for tagging (D-12)
- `src/main/java/com/paralife/admission/AdmissionMetrics.java` — extend tag schema for `source` and `harness` per D-11 / D-12; implement bounded-cardinality + overflow logic (D-10) for the harness tag value
- `src/main/java/com/paralife/admission/ResumeTokenRegistry.java` — verify rebind path preserves `ATTR_HARNESS` / `ATTR_SOURCE` across the new session (Phase 17 D-13 STALLED-pivot)
- `src/main/java/com/paralife/admission/AdmissionConfig.java` — add `attribution.max-harness-cardinality` config (default 64) per D-10 (placement = Claude's Discretion)
- `src/main/resources/application.yml` — new attribution config; document harness CLI usage in `18-HARNESS.md`, not in `application.yml` comments

### Tests (extend / new)
- `src/test/java/com/paralife/engine/LoadTest.java` — existing 100-bot reference; **don't break**. Suggested update: add `--harness-id=test-harness` and `X-Paralife-Source: harness` to its connections to validate the attribution path end-to-end (Claude's Discretion — see notes)
- `src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java` — verify behavior unchanged; if it asserts on log markers, update for new fields
- New: `src/test/java/com/paralife/admission/AttributionTagTest.java` — validates two-tag scheme, bounded cardinality + overflow, default `unknown`, BotRunner→`operator`
- New: `src/test/java/com/paralife/harness/LoadHarnessTest.java` — integration test booting the harness against an embedded server, validates ramp-up modes, JSON report writing (overwrite + append + atomic-rename invariant + JSONL header line)
- New: `BotFleet` / refactor regression test — proves `BotRunner` ≤100 path keeps working unchanged

### Build & Cross-Cutting
- `build.gradle.kts` — new `loadHarnessJar` shaded jar task; new `runHarness` task. Reuse Spring Boot fat-jar tooling
- `CLAUDE.md` — extend "Outbound concurrency" subsection (or add a new "Connection model" subsection) with the WS:entity 1:1 architectural principle (D-05 / D-21) + cross-reference to `18-HARNESS.md` (D-18)

### To Be Authored This Phase
- `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` — canonical spec for the harness. Sections: Architectural Principles (D-05 / D-21), Identity Wire Shape (D-06 / D-07 / D-08 / D-09), CLI Surface (D-15 / D-16), Attribution Tagging Schema (D-10 / D-11 / D-12), Log Marker Catalog (D-13 / D-14), JSON Report Schema (D-17), Sample Benchmark Commands (100/500/1000), Forward Notes (D-19 / D-20 / D-21 — offspring producer; multi-entity exception path; multi-instance coordination). Cross-references back to `17-ADMISSION.md` §1 and `15-SCHEMA.md` §6.1
- `CLAUDE.md` "Connection model" subsection — D-21 architectural principle + pointer to `18-HARNESS.md`

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`BotLauncher` VT-per-bot pattern** + balanced species mix — direct foundation for the harness. The 30s `allDone.await` is the only refactor-blocker (D-04).
- **`BotClient` with Jetty 12 native `WebSocketClient`** — `ClientUpgradeRequest.addHeader(name, value)` already supports custom handshake headers. D-06 is a one-method-call extension.
- **`AdmissionMetrics` Micrometer tagged-counter mechanics from Phase 17** — D-12 extends the existing tag schema rather than introducing a new bean.
- **Phase 17 `paralife.admission.rejected{reason}` tagged counter** (D-17) — D-12 just adds another tag dimension.
- **Phase 16 `EMERGENCE` log marker style** + Phase 17 `ADMISSION` / `BACKPRESSURE` / `TICK-HEALTH` markers — direct template for the new `HARNESS` lifecycle marker (D-14) and field-extension on existing markers (D-13).
- **Spring Boot fat-jar tooling** — already used for the server `bootJar`; reuse for the harness shaded jar (D-15).
- **`@ConfigurationProperties` record pattern** (`AdmissionConfig`, `RespawnConfig`, `SimulationConfig`, `GridConfig`) — applies if any new server-side config is needed (cardinality cap; D-10).
- **Phase 17 `ResumeTokenRegistry` STALLED-pivot grace window** — harness fleets inherit hiccup-tolerance for free; multi-instance harness deployments benefit naturally.

### Established Patterns
- Records + sealed interfaces for data modelling — JSON report records can use Jackson + records (existing dependency)
- Micrometer tagged metrics over per-name counters (Phase 17 D-17) — D-11 / D-12 follow the pattern
- Grep-friendly low-cardinality log marker prefixes (Phase 16 D-15) — D-13 / D-14 follow it
- Single-threaded simulation core; mutations only inside tick listeners — admission attribution stays read-only on the tick path
- Origin-neutral admission code (Phase 17 D-02 / D-03 forward note) — D-19 / D-20 design preserves this

### Integration Points
- **Identity ingress** — `WorldWebSocketHandler.afterConnectionEstablished` reads `X-Paralife-Harness` / `X-Paralife-Source` from `session.getUpgradeRequest().getHeaders()`; stash as session attributes; emit `HARNESS connected` marker
- **Identity persistence** — Session attributes flow with the session for its lifetime; `ResumeTokenRegistry.tryRebind` (Phase 17 D-13) must preserve `ATTR_HARNESS` / `ATTR_SOURCE` across the new session in the rebind path
- **Metric emission sites** — every counter / gauge update reads source/harness from session attrs and emits with tags; cardinality cap + overflow folding lives in `AdmissionMetrics`
- **Admission decision site** — `AdmissionGate` reject path increments `rejected{reason, source[, harness]}` instead of `rejected{reason}`
- **CLI scaffolding** — new `com.paralife.harness.LoadHarness` main class; mirrors `BotRunner.java` structure but with the broader CLI surface (D-16) and run-report output (D-17)
- **Build outputs** — Gradle produces two runnable jars: server (`bootJar`) + harness shaded jar (new `loadHarnessJar`)

### Known Refactor Surface
- **`BotLauncher.launch` 30s window assumption** — single biggest blocker for 1000+ bot launches. The fleet abstraction (D-04) lifts this without breaking BotRunner.
- **`BotClient` constructor sprawl** — already 3 overloads; adding handshake-header config likely warrants a builder pattern or options record. Plan must decide.
- **Free-text rejection paths** were already retokened in Phase 17 — no work needed here on rejection messages, just on the **tags carried alongside them**.

</code_context>

<specifics>
## Specific Ideas

- **WS:entity 1:1 is now an explicit project principle (D-05 / D-21).** Many concurrent WebSocket connections is a stated architectural goal — multiplexing is strongly discouraged with rationale captured in `18-HARNESS.md` and `CLAUDE.md`. Exceptions are case-by-case and require an ADR or future-phase spec.

- **Two-tag attribution shape (D-11)** chosen over one-tag for grafana ergonomics: `source` is the bounded type taxonomy, `harness` is the instance id only when `source=harness`. The `offspring` value is **reserved-now-with-no-producer** (D-20) so dashboards built this phase don't need rework when 999.2 ships.

- **HTTP handshake headers (D-06)** chosen over wire-grammar extension (`r|` slot) explicitly to avoid editing the milestone-locked `15-SCHEMA.md`. URL query params were considered and rejected because harness ids leak into proxy logs.

- **Bounded + overflow cardinality (D-10)** is a costless Prometheus safety net. Config-tunable cap of 64 covers realistic operator deployments; the `harness=overflow` fold + one-time warning log handles misconfigured harness loops without operational pain.

- **Single-JVM with multi-instance deployment (D-01)** is the operational scale-out story, not a built-in orchestrator. Operators run N independent harness JVMs side-by-side (e.g. `10×100`); per-harness identity (D-06 / D-07) makes server attribution work naturally across them. Cross-harness coordination (synchronised ramp pulses, unified reports) is deferred — `jq` over per-harness JSONLs handles aggregation.

- **JSON report append-mode is JSONL (D-17)** with a static-config header line and per-write rolling-counter lines. Crash-safe via atomic temp-rename. 30s default interval aligns with typical Prometheus scrape cadence so client- and server-side timelines overlay cleanly when Phase 21 wires up dashboards.

- **`BotFactory` seam is the right-sized forward-compat shape (D-19)**, not Brain×Entity decoupling. The smaller seam reflects the WS:entity 1:1 commitment — when 999.2 lands and the server sends an offspring offer (wire shape is 999.2's call), the harness mints a fresh `BotClient` on a fresh WS connection, preserving full parallelism. The future per-harness `paralife.admission.active.entities{harness=...}` gauge (D-12) is exactly the data source a future "server picks least-loaded harness" assignment policy would query.

- **Phase 21 owns benchmark numbers, not Phase 18.** This phase ships the harness; default cap values (256 today), tick-overload watermarks (80/60/10 today), and queue sizes (128 today) are not Phase 18 concerns. Phase 21 sweeps those and decides actual production envelope based on measured runs.

- **Attribution ergonomics on `ResumeTokenRegistry` rebind** — explicitly verify during planning that the rebind path preserves `ATTR_HARNESS` / `ATTR_SOURCE`. Without this, a harness session that recovers via STALLED-pivot would silently lose its attribution for the remainder of its lifetime — a subtle but high-cost regression.

</specifics>

<deferred>
## Deferred Ideas

### Backlog / future phases (not lost)
- **Bot-driven offspring producer wire shape** (backlog 999.2) — Phase 18 ships the `BotFactory` seam (D-19) and reserves `source=offspring` in the taxonomy (D-20); 999.2 chooses the wire opcode (server-push `O|<entityId>|<species>` vs client-pull control topic vs alternative).
- **Multi-entity-per-session protocol** — strongly discouraged (D-21); case-by-case exception path documented in `18-HARNESS.md` Forward Notes. Would require redesigning FSM, admission, and breaking 15-SCHEMA — outside this phase.
- **Cross-harness ramp coordination / unified run report** — N independent harness JVMs each ramp independently; aggregation handled by `jq` over per-harness JSONLs. Centralised coordination probably never (Phase 21 can shell-orchestrate synchronised pulses if needed).
- **Live-tunable harness whitelist** — bounded + overflow (D-10) is the policy this phase. Whitelist would add config friction every new harness; revisit if a hostile-multi-tenant scenario emerges (it won't in M4).
- **YAML config file for the harness** — flag-only this phase (D-16). Revisit only if Phase 21 maintains many committed benchmark profiles.
- **Per-tick CSV report** — defer unless Phase 21 explicitly wants per-tick client-side timelines. The 30s JSONL append mode (D-17) already covers ramp-up shape.
- **Per-harness gauge of registered bots, exposed for actuator scrape** — `paralife.admission.active.entities{source=harness, harness=<id>}` (D-12) already gives this; no extra surface needed.

### Pulled to other phases / milestones
- **Phase 19** (SCALE-06 / 07) — high-density placement; partition-aware world execution. Harness traffic exercises the dense placement path; placement work itself is Phase 19's.
- **Phase 20** (SCALE-08 / 09) — connection multiplexing / runtime tuning. **Note:** D-21 strongly discourages multiplexing; Phase 20 should default to runtime-tuning for many connections rather than reducing connection count.
- **Phase 21** (SCALE-10) — benchmark gate; Phase 18 ships the harness, Phase 21 runs the benchmarks and decides actual production cap values.
- **M5** (Observability & Operations) — `/actuator/prometheus` (Phase 17 D-20 stays); operator dashboard consuming `paralife.admission.*` and `paralife.tick.health.*`; live maintenance-mode actuator endpoint; subjective scoring/leaderboard surface.
- **M6** (Deployment) — Docker packaging of the harness; multi-region; CI/CD.

### Considered and rejected
- **One-tag attribution scheme** (single `source` carrying instance ids like `harness-A`) — conflates type with instance; awkward grafana queries.
- **WS query param for identity** (`ws://host/ws/world?harness=<id>`) — works but harness ids leak into URL/proxy logs.
- **New `r|` slot for identity** — would force a `15-SCHEMA.md` edit; Phase 15 is milestone-locked.
- **Initial control frame for identity** (`H|<harnessId>` opcode) — adds round-trip without flexibility we'd actually use.
- **Per-bot identity granularity** — high-cardinality nightmare (1000+ tag values per harness).
- **Refusing header-less sessions** — breaks `BotRunner` default behavior + ad-hoc `wscat` probing; heavy-handed.
- **Brain×Entity decoupling** (Brain factory + brain-per-entity) — over-abstraction given WS:entity 1:1 (D-05); `BotFactory` is the right-sized seam.
- **Multi-entity-per-session as default** — actively undermines the parallel-WS architectural property (D-05). Reserved as case-by-case exception (D-21), not default.
- **Multi-process orchestrator with parent supervisor** — operationally heavier than running N independent harness JVMs; per-harness identity (D-06) makes the latter give the same scale-out for free.
- **`./gradlew runHarness` only (no shaded jar)** — Gradle daemon startup cost makes this wrong for ops; jar-only would lose dev convenience. Keep both (D-15).
- **YAML-only config** — added Spring config infra to a tool that doesn't otherwise need it; inflexible for one-off runs.
- **Per-tick CSV** at 1000 bots × N harnesses × 30+ minute runs = file bloat. JSONL append at 30s suffices.

### Reviewed Todos (not folded)
None — `gsd-sdk query todo.match-phase 18` returned 0 matches.

</deferred>

---

*Phase: 18-external-load-harness-harness-identity*
*Context gathered: 2026-04-28*
