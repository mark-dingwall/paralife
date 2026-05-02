# Phase 20: Connection Multiplexing & Runtime Tuning - Context

**Gathered:** 2026-05-02
**Status:** Ready for planning

<domain>
## Phase Boundary

Reduce per-connection overhead and tune the JVM/runtime so the server sustains 1000+ concurrent WebSocket bot sessions without regressing the locked compact-protocol semantics, the single-thread mutation invariant, or the WS:entity 1:1 architectural principle. Phase 20 produces measured profiles, durable config surface, and per-scale-tier operator guidance. Phase 21 owns the benchmark gate that consumes Phase 20's outputs. Closes SCALE-08 + SCALE-09.

**In scope:**
- Profile-driven characterisation of per-connection overhead at 100 / 500 / 1000+ bots using JFR + async-profiler
- Committed profile artifacts under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` (raw `.jfr` + flamegraph HTML, size-bounded)
- Documented JVM-flag presets per scale tier (heap, GC choice, VT carrier parallelism)
- New `paralife.runtime.jetty.*` and `paralife.runtime.app.*` `@ConfigurationProperties` records bound from `application.yml`, overridable via `-D` / env / `@TestPropertySource`
- Codec / encode hot-path internal optimisations (buffer reuse, ASCII fast-path, thread-local encoder) gated by JFR evidence — internal constants only, no public config surface
- New `20-RUNTIME.md` spec doc (mirror of `17-ADMISSION.md` / `18-HARNESS.md` style) carrying the WS:entity 1:1 deliberate-choice rationale, the tuning surface, per-tier recipes, profile findings, and forward notes
- `CLAUDE.md` §Runtime tuning subsection cross-referencing `20-RUNTIME.md`
- `README.md` operator note codifying the WS:entity 1:1 deliberate-choice rationale (every bot pretend independent user from own machine — many concurrent connections is a stated architectural property, not an accident to be optimised away)
- Inline code comments on the OutboundSender / WS upgrade sites pointing at the same rationale so future contributors do not "fix" the design
- Verification: existing protocol + regression tests stay green; Phase 19 D-10 golden-trace byte-equivalence gate stays green at fixed-seed scenarios; tuned config matrix shipped with measured before/after numbers

**Not in scope (other phases / backlog):**
- Genuine multi-entity-per-WS multiplexing — explicitly rejected this phase (see D-01); D-21 exception ADR not invoked
- Sub-WS transport multiplex (HTTP/2 / single-TCP fan-in) — rejected this phase; revisit only if Phase 21 evidence forces it
- Benchmark gate / 100/500/1000+ bot reports — Phase 21 (SCALE-10)
- Phase 19.1 parallel read-only sub-steps (parallel `PerceptionBroadcaster`, parallel tick-encode) — separate phase, depends on Phase 20 per ROADMAP.md
- Admin-UI live-tune of `paralife.runtime.*` — post-MVP M5 observer scope; Phase 20 designs records to be live-tunable but does not build the endpoint or UI
- Automated config search (ILP / Bayesian / grid sweep) over the `paralife.runtime.*` surface — future bench-harness phase; Phase 20 ships override surface + harness JSON report enabling it
- Wire schema mutation — `15-SCHEMA.md` LOCKED; codec impl tuning never crosses the wire boundary
- Docker / packaging concerns — M6 Deployment scope
- `/actuator/prometheus` wiring — M5

</domain>

<decisions>
## Implementation Decisions

### Multiplex Shape (SCALE-08)

- **D-01:** **Keep WS:entity 1:1 — equivalent transport-level overhead reduction, NOT genuine multiplexing.** Phase 18 D-05/D-21 (`CLAUDE.md` §Connection model) is a stated architectural property of Paralife: many concurrent independent WebSocket connections is the system's scale story. Each bot is treated as if it were a user connecting from their own machine. SCALE-08's "or equivalent transport-level scale strategy" escape hatch is taken. Phase 20 cuts per-connection cost via JVM/Jetty/application tuning and codec hot-path opts — never by collapsing connections.

- **D-02:** **Deliberate-choice rationale is documented in three places to prevent future "optimisation" of the design:**
  1. **`README.md`** — operator-facing note: many-concurrent-connections is the architectural goal, not an inefficiency to fix.
  2. **`CLAUDE.md` §Runtime tuning** — concise statement + cross-reference to `20-RUNTIME.md` and the existing §Connection model section.
  3. **In-code comments** on the OutboundSender / WS upgrade sites in `WorldWebSocketHandler` and the per-session VT loop, pointing at `20-RUNTIME.md` and the rationale.
  Sub-WS transport multiplex (HTTP/2-over-TLS, single-TCP fan-in) and genuine multi-entity-per-WS (D-21 exception) were explicitly considered and rejected — both contradict the architectural goal.

- **D-03:** **Forward note:** if Phase 21 benchmark evidence shows the per-connection overhead path is the binding constraint at 5000 conns/JVM (Phase 18 D-02 ceiling) and tuning has been exhausted, only then revisit D-01 with a separate phase + ADR. Until measured, this stays the project's design stance.

### Profiling Toolchain (SCALE-09)

- **D-04:** **JFR + async-profiler.** JFR (Java Flight Recorder, JDK built-in) for JVM-internal view: GC pauses, allocation rate, lock contention, virtual-thread carrier scheduling. async-profiler (external native sampling profiler) for kernel + native view: syscall costs on socket I/O, Jetty native internals, thread state outside JVM. Combination chosen because Phase 20's bottlenecks are expected to be FD count, syscall rate, GC behaviour, and VT scheduling — all of which need both lenses. JMH micro-benchmarks for `PerceptionCodec` deliberately deferred unless JFR evidence fingers a codec hot path; codec micro is unlikely to dominate at 1000-conn scale.

- **D-05:** **Profile artifacts committed under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`.** Raw `.jfr` files plus async-profiler flamegraph HTML, size-bounded (target ≤5 MB per artifact, ≤20 MB total — exact bounds = Claude's Discretion during planning). Reviewers, Phase 21 benchmark scripts, and any future operator can open the files to challenge a tuning recommendation. Findings written into `20-RUNTIME.md` with explicit citations of artifact filenames. Mirrors the project's "evidence over assertion" pattern (e.g., Phase 17 watermarks measured, not chosen).

- **D-06:** **Profile run scenarios:** at minimum 100, 500, and 1000-bot LoadHarness runs against a deterministically seeded server (`paralife.simulation.spawn.seed` set; mirrors Phase 19 D-06 / Phase 15 D-35 reproducibility precedent). Exact tick count and warmup window = Claude's Discretion / planning research. Re-runs after each tuning iteration are committed alongside (or replace) prior artifacts so the diff between baseline and tuned states is observable.

### Tuning Surface (SCALE-09)

- **D-07:** **Four-layer tuning surface, with concrete ownership per layer:**

  | Layer | Where it lives | Change-time | Phase 20 ships |
  |---|---|---|---|
  | 1. JVM/runtime flags | `java -jar` launch flags, documented in `20-RUNTIME.md` per-tier recipes | launch only | doc-only presets |
  | 2. Jetty/network | `paralife.runtime.jetty.*` `@ConfigurationProperties` record bound from `application.yml`; some live, some launch-only | mixed | record + bindings + defaults |
  | 3. Application | `paralife.runtime.app.*` `@ConfigurationProperties` record (joins existing Phase 17 D-10 outbound queue config; new keys for batch-flush threshold, encode cache size, etc.) | live where safe (`@RefreshScope` hooks) | record + bindings + defaults |
  | 4. Codec impl | internal constants in `PerceptionCodec` and frame encoders — JFR-validated | code change only | hot-path opts, no public surface |

  Layers 1–3 are programmatically controllable: CLI `-Dparalife.runtime.x=y`, env vars, Spring `@TestPropertySource`. This satisfies the "local test bench can sweep configs to find interesting sims" requirement raised during discussion — the surface exists; the search algorithm is deferred (see Deferred).

- **D-08:** **JVM/runtime flags ship as documented presets, not application-bound config.** Heap (`-Xmx`), GC choice (`-XX:+UseZGC` vs `-XX:+UseG1GC`), VT carrier parallelism (`-Djdk.virtualThreadScheduler.parallelism=N`), allocation profiling (`-XX:+UnlockDiagnosticVMOptions ...`) all live in `20-RUNTIME.md` per-tier recipes and (where appropriate) `LoadHarness` invocation examples. Phase 20 does NOT add a wrapper script; `java -jar ...` with explicit flags stays the operator interface.

- **D-09:** **Spring `@ConfigurationProperties` records are the binding shape for layers 2 + 3.** Mirrors the project's existing pattern (`AdmissionConfig`, `RespawnConfig`, `SpawnConfig`, `GridConfig`, `TickConfig`). Records SHOULD be designed with future live-tune in mind: each field documented as `[live-tunable | launch-only]`, candidate live-tune fields annotated with `@RefreshScope` hook seams. Building the actuator endpoint that exposes / mutates them = deferred (M5).

- **D-10:** **Codec / encode hot-path optimisations are JFR-driven only.** Concrete opts (buffer pool reuse, ASCII fast-path on `Position` encoding, thread-local `StringBuilder`, allocation elimination on tick-frame encode) are picked from profile evidence, not guessed. None cross the wire boundary — `15-SCHEMA.md` stays bit-exact. Phase 19 D-10 golden-trace byte-equivalence gate is the regression check.

### Verification & Regression Safety

- **D-11:** **Phase 19 D-10 golden-trace gate is reused as the byte-equivalence check** for codec / encode tuning. Same fixture, same seeds, same expected digests. Tuning that breaks the digest fails CI. No new gate authored.

- **D-12:** **Existing test suite (166+ tests) must stay green.** No protocol regression, no admission regression, no Phase 17 backpressure / STALLED-pivot regression, no Phase 18 harness identity regression. Phase 20 changes are additive: new config records + internal codec opts + measurement artifacts + documentation.

- **D-13:** **Tuning recommendations must include a measured before/after.** Each recipe in `20-RUNTIME.md` per-scale-tier section cites a baseline JFR + a tuned JFR with a concrete metric delta (e.g., "p99 tick work-time 420ms → 240ms at 1000 bots, see `profiles/jfr-1000bots-baseline.jfr` + `profiles/jfr-1000bots-tuned.jfr`"). Recommendations without measurement are not shipped.

### Operator Deliverable (`20-RUNTIME.md`)

- **D-14:** **Canonical doc:** `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md`. Mirror of `17-ADMISSION.md` / `18-HARNESS.md` style. Section list:
  - **Architectural Principle: WS:entity 1:1** — D-01 / D-02 rationale, codified for future contributors.
  - **Tuning Surface** — the four-layer table from D-07, with every config key, change-time, and default.
  - **Per-Scale-Tier Recipes** — 100 / 500 / 1000+ bot recipes: JVM flags + `application.yml` overrides + LoadHarness invocation; each cites concrete profile artifacts.
  - **Profile Findings** — narrative walkthrough of the JFR + async-profiler results: what dominated cost at each tier, what tuning addressed it, what's left for Phase 21 to measure.
  - **Forward Notes** — admin-UI live-tune (M5), automated config search (future bench-harness phase), revisit-multiplex trigger (D-03).

- **D-15:** **`CLAUDE.md` cross-reference:** new §Runtime tuning subsection. Concise: codifies WS:entity 1:1 design choice (with §Connection model and §Outbound concurrency cross-refs), points at `20-RUNTIME.md` for full surface. Existing CLAUDE.md sections stay untouched.

- **D-16:** **`README.md` cross-reference:** operator-level note that many-concurrent-connections is the architectural goal. One paragraph + link to `20-RUNTIME.md`. Distinct purpose from CLAUDE.md (operators vs contributors).

### Claude's Discretion

- Concrete `paralife.runtime.jetty.*` and `paralife.runtime.app.*` field names and defaults — driven by profile evidence in planning.
- Profile artifact size bounds (suggested: ≤5 MB per file, ≤20 MB total, but verify in planning against typical JFR run sizes).
- Exact LoadHarness ramp / duration / seed combinations for the per-tier profile runs.
- Whether `paralife.runtime.app.*` is a single record or split (e.g., `paralife.runtime.app.outbound.*` + `paralife.runtime.app.encode.*`).
- Choice of GC for each tier (ZGC vs G1) — JFR evidence drives this; recipe stays config-only, no compile-time choice.
- Format of profile-finding citations in `20-RUNTIME.md` (filename + flamegraph frame name + screenshot? all three?).
- Decision on whether to retire Phase 17's existing outbound-queue config under the new `paralife.runtime.app.*` namespace, or leave it where it is and cross-reference. Backwards-compat surface = planning consideration.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level

- `.planning/REQUIREMENTS.md` — SCALE-08 / SCALE-09 acceptance text
- `.planning/PROJECT.md` — overall vision; M4 scale milestone scope; thousands-of-concurrent-connections goal
- `.planning/STATE.md` — current milestone position
- `.planning/ROADMAP.md` — Phase 20 entry, Phase 21 dependency, Phase 19.1 dependency on Phase 20
- `CLAUDE.md` §Conventions → Concurrency — single-threaded mutation invariant must be preserved by any tuning
- `CLAUDE.md` §Outbound concurrency — Phase 17 D-10 VT-per-session queue; Phase 20 tunes its parameters
- `CLAUDE.md` §Connection model — Phase 18 D-05/D-21 WS:entity 1:1 codification (D-01 honours this)
- `README.md` — D-16 adds operator-level rationale here

### Prior phases referenced as locked contracts

- `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` — milestone-locked compact wire grammar; D-10 codec tuning must not mutate
- `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` — admission + backpressure surface; tuning must not regress
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` D-10 — VT-per-session outbound queue (joined by `paralife.runtime.app.*`)
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` D-15 — "tuning lives in config" precedent the runtime records follow
- `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` §1 — D-05/D-21 WS:entity 1:1 architectural principle + 5000-conn/JVM ceiling (Phase 20 makes that ceiling real via tuning)
- `.planning/phases/18-external-load-harness-harness-identity/18-CONTEXT.md` D-02 — 5000 concurrent WS connections per JVM design ceiling
- `.planning/phases/18-external-load-harness-harness-identity/18-CONTEXT.md` D-21 — multi-entity-per-WS exception path (NOT invoked this phase, see D-01)
- `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md` D-08 — single-thread mutation core preserved
- `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md` D-10 — golden-trace byte-equivalence gate (D-11 reuses)
- `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md` D-11 — single-threaded mutation invariant intact

### In-tree code referenced as ground truth

- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — WS upgrade site; D-02 inline comment lands here
- `src/main/java/com/paralife/websocket/OutboundSender.java` (and the per-session VT loop introduced Phase 17) — D-02 inline comment + outbound queue depth field is part of the new `paralife.runtime.app.*` record
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — frame encode hot path candidate for D-10 codec opts
- `src/main/java/com/paralife/engine/PerceptionCodec.java` (and the codec siblings under Phase 15) — primary target for D-10 codec hot-path opts
- `src/main/java/com/paralife/engine/AdmissionConfig.java`, `SpawnConfig.java`, `RespawnConfig.java`, `GridConfig.java`, `TickConfig.java` — `@ConfigurationProperties` precedent records the new `paralife.runtime.*` records mirror
- `src/main/resources/application.yml` — config root; new `paralife.runtime.*` keys land here with sensible defaults

### Phase-internal artifacts to be created

- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` — D-14 canonical operator/contributor doc
- `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` — D-05 committed JFR + flamegraph artifacts

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **Phase 17 D-10 VT-per-session OutboundSender + bounded queue** — the outbound queue depth config already exists (`paralife.admission.backpressure.outbound-queue-size`). D-09 may absorb it under `paralife.runtime.app.*` or cross-reference; consideration in planning. The queue depth is the single most important live-tunable runtime knob; it already has a stalled-session callback and metric gauge (`paralife.backpressure.stalled.sessions`).
- **Existing `@ConfigurationProperties` records** (`AdmissionConfig`, `RespawnConfig`, `SpawnConfig`, `GridConfig`, `TickConfig`, `SimulationConfig`) — established record-based binding pattern. The new `paralife.runtime.*` records follow the same shape: `record RuntimeJettyConfig(...)`, `record RuntimeAppConfig(...)`.
- **Phase 19 golden-trace test (D-10)** — already in repo as the equivalence harness; D-11 reuses unchanged.
- **`paralife.simulation.spawn.seed` (SpawnConfig)** — D-06 profile runs use it for reproducibility; same precedent as Phase 19 D-06 placement-determinism contract.
- **LoadHarness (Phase 18)** — the 1000-bot driver. Phase 20 profile runs invoke it directly; no harness-side code change needed. `--harness-id` and the JSON run report give per-run attribution and rolling counters that feed back into profile interpretation.
- **Existing tick-health gauge `paralife.tick.health.work-time-ms` (Phase 17 D-18)** — primary scalar metric tracked across tuning iterations. Before/after deltas in D-13 cite this gauge as the headline number.

### Established Patterns

- **`@ConfigurationProperties` records bound from `application.yml`** (CLAUDE.md §Spring patterns) — D-09 follows.
- **Spec-doc-per-phase mirroring** — `17-ADMISSION.md`, `18-HARNESS.md` precedents; `20-RUNTIME.md` (D-14) follows the same structure.
- **Tuning lives in config, not constants** (Phase 17 D-15 precedent: watermarks tunable so Phase 21 benchmarks sweep without recompiling) — D-07 / D-08 / D-09 inherit this.
- **Evidence-driven tuning** (Phase 17 reference: rolling tick-work-time gauge measured, not assumed) — D-04 / D-05 / D-13 inherit.
- **Atomic temp-+-rename for crash-safe artifacts** (Phase 18 D-17) — applies if Phase 20 grows any persistent artifact writers (probably not; profiles are tool-output).

### Integration Points

- **Config record binding sites:** new records bound at the `@SpringBootApplication` / config-class layer; injected into `OutboundSender`, `TickBroadcaster`, codec components, `WebSocketConfig`.
- **Profile runs:** invoked from CLI scripts (no test-suite hookup unless a tiny smoke check is added). Artifacts written under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`.
- **Documentation cross-refs:** `CLAUDE.md` §Runtime tuning (new); `README.md` operator paragraph (new); `20-RUNTIME.md` (new); `17-ADMISSION.md` and `18-HARNESS.md` cross-link Phase 20 only if Phase 20 changes their referenced behaviour (probably no — Phase 20 is additive).
- **Verification gate:** Phase 19 D-10 golden-trace test + full existing 166+ test suite. No new gate.
- **Phase 19.1 boundary:** Phase 19.1 (parallel `PerceptionBroadcaster` + tick encode) lands AFTER Phase 20 per ROADMAP.md. Phase 20's `paralife.runtime.app.*` record SHOULD reserve fields for parallelism settings (e.g., `parallel-encode-threshold`) so 19.1 extends cleanly without a record migration.
- **Phase 21 boundary:** Phase 21 benchmark scripts consume `20-RUNTIME.md` per-tier recipes directly. Recipes must be copy-pasteable shell + config snippets.

</code_context>

<specifics>
## Specific Ideas

- **WS:entity 1:1 deliberate-choice rationale is non-negotiable architectural identity.** The user framed it as: "pretend each bot is a user connecting from their own machine." Many-concurrent-connections is the system's scale story, not an inefficiency. D-01 / D-02 codify this in three places (README.md, CLAUDE.md, in-code comments) specifically so future contributors do not "fix" it by introducing multiplexing.
- **Programmatic config control is required.** The user wants to drive a future local test bench that sweeps `paralife.runtime.*` configs (and possibly runs an automated search — ILP, grid, Bayesian) to find combinations that yield "interesting sims". Phase 20 ships the override surface (CLI `-D`, env, Spring `@TestPropertySource`); the search algorithm itself is deferred.
- **Admin observer UI for runtime knobs is a stated future goal** (post-MVP M5). Phase 20 designs records to be live-tunable (`@RefreshScope` hook seams, per-field `[live-tunable | launch-only]` annotation in doc) so the future actuator endpoint and admin UI can mutate them without a record redesign.
- **Profile artifacts in repo are the project's "evidence over assertion" stance.** Reviewers can open the JFR to challenge any tuning recommendation. The same stance underpins Phase 17 (watermarks measured, not chosen) and Phase 18 (5000-conn ceiling stated as a property, with measurement deferred to Phase 21).

</specifics>

<deferred>
## Deferred Ideas

### Admin Observer UI live-tune of `paralife.runtime.*` knobs (M5)

- **What:** A web admin UI that exposes `paralife.runtime.jetty.*` and `paralife.runtime.app.*` records as readable-by-everyone, writable-by-admin form. Live-tunable fields apply via a Spring actuator endpoint backed by `@RefreshScope`. Launch-only fields render as informational.
- **Why deferred:** M5 scope (operator UX). Phase 20 designs records to make this clean to add.
- **Trigger:** Start of M5 observer work.

### Automated config search over `paralife.runtime.*` for "interesting sims"

- **What:** Test-bench harness that drives many sims with config overrides and scores each on emergent-behaviour metrics. Search algorithm = TBD (user mentioned ILP; simpler grid or Bayesian likely first).
- **Phase 20 enables it:** CLI override surface exists; LoadHarness JSON run report (Phase 18 D-17) carries per-run counters; profile artifacts give per-config evidence base.
- **Why deferred:** Not on the M4 scale-engineering critical path. Belongs in a future bench-harness phase under M5/M6.

### Sub-WS transport multiplex / genuine multi-entity-per-WS

- **What:** Either HTTP/2-style transport multiplexing (one TCP, many WS upgrades) or genuine multi-entity-per-WS via the Phase 18 D-21 exception path.
- **Why deferred (and almost certainly never built):** Contradicts the architectural goal codified in D-01 / D-02. Revisit ONLY if Phase 21 evidence shows per-connection overhead is the binding constraint at 5000 conns/JVM and tuning has been exhausted (D-03).

### JMH micro-benchmarks for codec hot paths

- **What:** Committed `src/jmh/` harness exercising `PerceptionCodec.encode` and frame encoders.
- **Why deferred:** Add only if JFR + async-profiler evidence in Phase 20 fingers a codec hot path. Codec micro is unlikely to dominate at 1000-conn scale; FDs / syscalls / GC / VT scheduling dominate.

### Phase 19.1 parallel read-only sub-steps

- **What:** Parallel `PerceptionBroadcaster` (per-bot independent reads on snapshot) + parallel tick-encode (per-session encode in parallel, send via Phase 17 D-10 VT-per-session queues).
- **Phase 20 enables it:** `paralife.runtime.app.*` record reserves parallelism-related fields (e.g., `parallel-encode-threshold`) so 19.1 extends cleanly. Phase 20 tuning may also informs 19.1's parallelism decisions (e.g., VT carrier count cap).
- **Why deferred:** Already a separately roadmapped phase; depends on Phase 20.

### Reviewed Todos (not folded)

None — no pending todos surfaced for Phase 20.

</deferred>

---

*Phase: 20-connection-multiplexing-runtime-tuning*
*Context gathered: 2026-05-02*
