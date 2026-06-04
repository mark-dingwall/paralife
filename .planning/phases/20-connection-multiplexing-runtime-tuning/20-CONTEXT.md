# Phase 20: Connection Multiplexing & Runtime Tuning - Context

**Gathered:** 2026-05-09 (supersedes 2026-05-02 draft preserved as `20-CONTEXT-superseded-pre-P19.1.md`)
**Status:** Ready for planning

> **Audit basis.** This CONTEXT was rebuilt on 2026-05-09 after Phase 19.1 (multi-review fix
> sweep) and Phase 22 (incident-response leak audit) landed. A 2026-05-09 audit of the prior
> draft confirmed 12/16 decisions still valid; 4 needed pinpoint updates (D-06, D-10, D-11,
> D-12); 4 new gray areas were resolved (D-17..D-20). Time pressure on M4 → MVP drove
> recommendations toward "smallest scope that gets there safely". The prior draft is preserved
> as a sibling file for diff/grep purposes.

<domain>
## Phase Boundary

Reduce per-connection overhead and tune the JVM/runtime so the server sustains 1000+
concurrent WebSocket bot sessions without regressing the locked compact-protocol semantics,
the single-thread mutation invariant, or the WS:entity 1:1 architectural principle. Phase 20
produces measured profiles, durable config surface, and per-scale-tier operator guidance.
Phase 21 owns the benchmark gate that consumes Phase 20's outputs. Closes SCALE-08 + SCALE-09.

**In scope:**
- Profile-driven characterisation of per-connection overhead at 100 / 500 / 1000+ bots using
  JFR + async-profiler
- Committed profile artifacts under
  `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` (raw `.jfr` +
  flamegraph HTML, size-bounded)
- Documented JVM-flag presets per scale tier (heap, GC choice, VT carrier parallelism)
- New `paralife.runtime.jetty.*` and `paralife.runtime.app.*` `@ConfigurationProperties`
  records bound from `application.yml`, overridable via `-D` / env / `@TestPropertySource`
- Codec / encode hot-path internal optimisations (buffer reuse, ASCII fast-path,
  thread-local encoder) gated by JFR evidence — internal constants only, no public config
  surface
- New `20-RUNTIME.md` spec doc (mirror of `17-ADMISSION.md` / `18-HARNESS.md` style)
  carrying the WS:entity 1:1 deliberate-choice rationale, the tuning surface, per-tier
  recipes, profile findings, and forward notes
- `CLAUDE.md` §Runtime tuning subsection cross-referencing `20-RUNTIME.md`
- `README.md` operator note codifying the WS:entity 1:1 deliberate-choice rationale
- Inline code comments on the OutboundSender / WS upgrade sites pointing at the same
  rationale so future contributors do not "fix" the design
- Verification: existing protocol + regression tests stay green (excluding the P22 `@Disabled`
  tests held for P21/P22.1, see D-12; *de-stale 2026-06-04: live inventory is 6 `@Disabled`
  across 5 files — 3 P22-origin TD-22-A/B/C plus 3 pre-existing; TD-22-D HundredBot was never
  disabled — see 20-VALIDATION.md Wave 0 intro*); P19 D-10 golden-trace byte-equivalence gate plus the
  new P19.1 D-11/D-12 gates stay green at fixed-seed scenarios; tuned config matrix shipped
  with measured before/after numbers

**Not in scope (other phases / backlog):**
- Genuine multi-entity-per-WS multiplexing — explicitly rejected this phase (see D-01); D-21
  exception ADR not invoked
- Sub-WS transport multiplex (HTTP/2 / single-TCP fan-in) — rejected this phase; revisit only
  if Phase 21 evidence forces it (D-03)
- Benchmark gate / 100/500/1000+ bot reports — Phase 21 (SCALE-10)
- Phase 19.1 parallel read-only sub-steps (parallel `PerceptionBroadcaster`, parallel
  tick-encode) — separate phase, depends on Phase 20 per ROADMAP.md
- Admin-UI live-tune of `paralife.runtime.*` — post-MVP M5 observer scope
- Automated config search (ILP / Bayesian / grid sweep) over the `paralife.runtime.*`
  surface — future bench-harness phase
- Wire schema mutation — `15-SCHEMA.md` LOCKED; codec impl tuning never crosses the wire
  boundary
- Re-enabling P22's `@Disabled` tests (TD-22-A/B/C; *de-stale 2026-06-04: TD-22-D HundredBot
  was never `@Disabled` — it runs in-suite with a known latch-race flake*) — P21 / P22.1 territory
- Namespace migration of `paralife.admission.backpressure.outbound-queue-size` →
  `paralife.runtime.app.*` — deferred to backlog (D-20)
- `/actuator/prometheus` wiring — M5
- Docker / packaging — M6 Deployment scope

</domain>

<decisions>
## Implementation Decisions

### Multiplex Shape (SCALE-08)

- **D-01:** **Keep WS:entity 1:1 — equivalent transport-level overhead reduction, NOT
  genuine multiplexing.** Phase 18 D-05/D-21 (`CLAUDE.md` §Connection model) is a stated
  architectural property of Paralife: many concurrent independent WebSocket connections is
  the system's scale story. Each bot is treated as if it were a user connecting from their
  own machine. SCALE-08's "or equivalent transport-level scale strategy" escape hatch is
  taken. Phase 20 cuts per-connection cost via JVM/Jetty/application tuning and codec
  hot-path opts — never by collapsing connections.

- **D-02:** **Deliberate-choice rationale is documented in three places to prevent future
  "optimisation" of the design:**
  1. **`README.md`** — operator-facing note: many-concurrent-connections is the architectural
     goal, not an inefficiency to fix.
  2. **`CLAUDE.md` §Runtime tuning** — concise statement + cross-reference to `20-RUNTIME.md`
     and the existing §Connection model section.
  3. **In-code comments** on the OutboundSender / WS upgrade sites in
     `WorldWebSocketHandler` and the per-session VT loop, pointing at `20-RUNTIME.md` and
     the rationale.

- **D-03:** **Forward note:** if Phase 21 benchmark evidence shows the per-connection
  overhead path is the binding constraint at 5000 conns/JVM (Phase 18 D-02 ceiling) and
  tuning has been exhausted, only then revisit D-01 with a separate phase + ADR.

### Profiling Toolchain (SCALE-09)

- **D-04:** **JFR + async-profiler.** JFR for JVM-internal view (GC pauses, allocation rate,
  lock contention, virtual-thread carrier scheduling). async-profiler for kernel + native
  view (syscall costs on socket I/O, Jetty native internals, thread state outside JVM). JMH
  micro-benchmarks for `PerceptionCodec` deferred unless JFR fingers a codec hot path.

- **D-05:** **Profile artifacts committed under
  `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`.** Raw `.jfr`
  files plus async-profiler flamegraph HTML, size-bounded (target ≤10 MB per artifact, ≤50 MB
  total — exact bounds = Claude's Discretion during planning). Findings written into
  `20-RUNTIME.md` with explicit citations of artifact filenames.

- **D-06:** **Profile run scenarios:** at minimum 100, 500, and 1000-bot LoadHarness runs
  against a deterministically seeded server (`paralife.simulation.spawn.seed` set; mirrors
  Phase 19 D-06 / Phase 15 D-35 reproducibility precedent).
  **Profile measurement runs via the standalone `loadHarnessJar` CLI, not the Gradle test
  task.** Phase 22's unconditional `forkEvery=1` (`build.gradle.kts:75-76`) and 5-minute
  JUnit timeout (`junit-platform.properties`) constrain in-test runs only and DO NOT apply
  to P20 measurement. Re-runs after each tuning iteration are committed alongside (or
  replace) prior artifacts so the diff between baseline and tuned states is observable.

### Tuning Surface (SCALE-09)

- **D-07:** **Four-layer tuning surface, with concrete ownership per layer:**

  | Layer | Where it lives | Change-time | Phase 20 ships |
  |---|---|---|---|
  | 1. JVM/runtime flags | `java -jar` launch flags, documented in `20-RUNTIME.md` per-tier recipes | launch only | doc-only presets |
  | 2. Jetty/network | `paralife.runtime.jetty.*` `@ConfigurationProperties` record bound from `application.yml`; some live, some launch-only | mixed | record + bindings + defaults |
  | 3. Application | `paralife.runtime.app.*` `@ConfigurationProperties` record | live where safe (`@RefreshScope` hooks) | record + bindings + defaults |
  | 4. Codec impl | internal constants in `PerceptionCodec` and frame encoders — JFR-validated | code change only | hot-path opts, no public surface |

  Layers 1–3 programmatically controllable: CLI `-Dparalife.runtime.x=y`, env vars, Spring
  `@TestPropertySource`. Search algorithm deferred (see Deferred).

- **D-08:** **JVM/runtime flags ship as documented presets, not application-bound config.**
  Heap, GC choice, VT carrier parallelism, allocation profiling all live in `20-RUNTIME.md`
  per-tier recipes and (where appropriate) `LoadHarness` invocation examples. No wrapper
  script.

- **D-09:** **Spring `@ConfigurationProperties` records are the binding shape for layers
  2 + 3.** Mirrors the project's existing pattern (`AdmissionConfig`, `RespawnConfig`,
  `SpawnConfig`, `GridConfig`, `TickConfig`). Records SHOULD be designed with future
  live-tune in mind: each field documented as `[live-tunable | launch-only]`, candidate
  live-tune fields annotated with `@RefreshScope` hook seams.

- **D-10:** **Codec / encode hot-path optimisations are JFR-driven only.** Concrete opts
  (buffer pool reuse, ASCII fast-path on `Position` encoding, thread-local `StringBuilder`,
  allocation elimination on tick-frame encode) are picked from profile evidence, not guessed.
  None cross the wire boundary — `15-SCHEMA.md` stays bit-exact.
  **Code lives at `src/main/java/com/paralife/codec/PerceptionCodec.java`** (corrected from
  prior draft's `com.paralife.engine.PerceptionCodec`); sibling `Base64Codec` may be
  affected by the same opts.

### Verification & Regression Safety

- **D-11:** **Three-gate equivalence stack:**
  1. Phase 19 D-10 `GoldenTraceEquivalenceTest` — primary byte-equivalence check for codec /
     encode tuning.
  2. Phase 19.1 D-11 `GoldenTraceWithActionsTest` — extends gate 1 with action-driven traces.
  3. Phase 19.1 D-12 `LiveEntityRegistryInvariantTest` — entity-lifecycle invariant gate.

  **Caveat (TD-19.5-A):** `GoldenTraceEquivalenceTest` is flaky in **isolated** runs (~40%
  emit ±1) due to `OutboundSender.awaitAllSessionQueuesDrained` VT race — masked when run
  in the full suite. P20 codec tuning re-runs the gate **in-suite** only; do not gate CI on
  isolated runs. P22.1 will revalidate after P20.

- **D-12:** **Existing test suite stays green excluding the `@Disabled` tests held for
  P21 / P22.1:** TD-22-A `MetabolismIntegrationTest` (read-lock starvation under tick-write
  pressure), TD-22-B `EncodeDeflatePerformanceGateTest` (real perf regression), TD-22-C
  `PopulationDynamicsTest` (probabilistic flat-line). *De-stale 2026-06-04: TD-22-D
  `HundredBotIntegrationTest` (connect-latch race) was never `@Disabled` — it runs in-suite
  with a known flake; live inventory is 6 `@Disabled` annotations across 5 files (3 P22-origin
  above + Toxin×2 + CellularAutomaton perf-only) — see 20-VALIDATION.md Wave 0 intro.*
  **P20 MUST NOT re-enable any of these** — that's P21 / P22.1
  territory. P20 tuning may incidentally resolve TD-22-A's read-lock starvation, but proving
  it is P21's job. Stale "166+ tests" figure dropped; current count is 136 test files.

- **D-13:** **Tuning recommendations must include measured before/after.** Each recipe in
  `20-RUNTIME.md` per-scale-tier section cites a baseline JFR + a tuned JFR with concrete
  metric deltas. **Headline gauges:**
  - `paralife.tick.health.work-time-ms` (P17 D-18 — primary scalar)
  - `paralife.outbound.detach.timeout` (P19.1 D-13/D-14, `AdmissionMetrics.java:74` — direct
    signal of slow-client wedging the drain VT past close-aware detach budget)

  Recommendations without measurement are not shipped.

### Operator Deliverable (`20-RUNTIME.md`)

- **D-14:** **Canonical doc:**
  `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md`. Mirror of
  `17-ADMISSION.md` / `18-HARNESS.md` style. Section list:
  - **Architectural Principle: WS:entity 1:1** — D-01 / D-02 rationale
  - **Tuning Surface** — the four-layer table from D-07
  - **Per-Scale-Tier Recipes** — 100 / 500 / 1000+ bot recipes with concrete profile
    artifact citations
  - **Profile Findings** — narrative walkthrough of JFR + async-profiler results
  - **Forward Notes** — admin-UI live-tune (M5), automated config search, revisit-multiplex
    trigger (D-03), namespace consolidation (D-20)

- **D-15:** **`CLAUDE.md` cross-reference:** new §Runtime tuning subsection, concise.

- **D-16:** **`README.md` cross-reference:** operator-level note, one paragraph + link.

### Post-P19.1 Amendments (resolved 2026-05-09)

- **D-17 (was G-01):** **P22.1 owns its own invariant diff against `22-INVARIANTS.md`.**
  P20 ships nothing extra for that revalidation. Rationale: cheapest path; P22.1 already
  exists as the safety net specifically because P20/P21 may drift those invariants. Drift is
  caught at most one phase later, not six months later. MVP-direct.

- **D-18 (was G-02):** **L1 detach-timeout counter (`paralife.outbound.detach.timeout`) is
  promoted to D-13 headline numbers.** Read-only signal, no new tunable knob. Tracked
  alongside `paralife.tick.health.work-time-ms` in every before/after delta in
  `20-RUNTIME.md`.

- **D-19 (was G-03):** **Profile baseline anchored to commit SHA `c22e487`** (Phase 19.1
  close — "docs(19.1): mark phase 19.1 complete"). Every committed baseline JFR cites this
  SHA in its filename (e.g. `jfr-1000bots-baseline-c22e487.jfr`) and in the
  `20-RUNTIME.md` profile-findings section. Reviewers and future agents (incl. P22.1) can
  `git checkout c22e487` and re-run the harness to reproduce. Reproducibility is cheap
  insurance for the project's "evidence over assertion" stance (D-05).
  (re-anchored to `62c1b44` by Plan 1c F6; active-scenario evidence set `103a615`; tuned capture `424e06d` — see 20-RUNTIME.md §6)

- **D-20 (was G-04):** **Layer `paralife.runtime.app.*` keys alongside the existing
  `paralife.admission.backpressure.outbound-queue-size`** rather than moving it.
  Cheapest; CLAUDE.md / 17-ADMISSION.md / `AdmissionConfig.java:114` cross-references stay
  intact. **Backlog item filed:** future namespace consolidation under
  `paralife.runtime.app.*` (Phase 999.x — see ROADMAP backlog).

- **D-21 (added 2026-05-09 per cross-AI review concern #1; amended pass-2 evening per Concerns #9 + #11 — see 20-REVIEW-DISPOSITIONS.md):** **SCALE-08 evidence acceptance permits a documented null-result OR a documented dominant-pinning finding.** D-10 / D-13's "JFR-driven only" stance applies to *codec* opts. SCALE-08's measurable evidence requirement is satisfied by ANY of the following four outcomes (in precedence order — earlier outcomes supersede later ones during Plan 5 triage):

  1. **Shipped codec opts** with JFR-cited delta on `paralife.tick.health.work-time-ms` or `paralife.outbound.detach.timeout` (D-10 / D-13).
  2. **JFR-justified runtime-knob tightening** (Plan 2 / Plan 3 record default change) with measured delta — same triage rigor as a codec opt (JFR signal + two-consecutive-green three-gate verification + tuned-state JFR delta documented).
  3. **Documented null-result** showing the c22e487 baseline is at the relevant performance floor at 1000 bots (e.g., `jdk.GCPhasePause` mean ≤1ms, `jdk.VirtualThreadPinned` count <10/min, codec stack ≤2% CPU, allocation steady-state). The tuning surface (Plan 2 + Plan 3 records + Plan 4 per-tier recipes + Plan 1/1b baseline JFRs + Plan 5 tuned-state equivalence capture) IS the SCALE-08 deliverable; a measured null-result is a measurement, not a no-op.
     (superseded by Plan 1c re-anchor — see D-19 annotation; actual evidence set is `103a615` active + `424e06d` tuned)
  4. **Dominant pinning with backlog-handoff** (added pass-2 per Concern #9 — adopted OpenCode's reading) — JFR shows `jdk.VirtualThreadPinned` is the binding constraint at the relevant scale AND the `synchronized → ReentrantLock` conversion work is filed as Phase 999.6 (`vt-pinning-reentrantlock-conversion`) per pass-1 Concern #2 disposition AND the tuned-state JFR confirms equivalence (no regression introduced by Plan 5). Structurally identical to outcome 3: the system is at the performance floor for the work Plan 5 is permitted to do; the unresolved overhead path is documented and handed off to a backlog phase rather than masked by a forced-fallback knob change. **Codex's pass-2 reading (block-and-don't-claim-SCALE-08) is rejected per Concern #9 disposition:** the alternative would either downgrade SCALE-08 (rejected — D-21 rationale is sound) or expand Plan 5 scope to include the conversion (rejected — Phase 999.6 backlog is pass-1 #2 disposition).

  **Pinning-dominates supersedes runtime-knob tightening (do NOT manufacture a fallback delta on top of dominant pinning — outcome 4 is the correct disposition; outcome 2 is wrong when pinning is dominant).**

  **Noise floor convention (added pass-2 per Concern #11):** "tuned ≈ baseline within noise floor" means **±5% of baseline mean OR ±1σ, whichever is larger**, computed across the JFR sample window. Plan 5 success criteria + Plan 6 §4.2 cite this convention by reference. Below the noise floor, document the noise-floor evidence and proceed (outcome 3 with a sub-noise-floor delta is acceptable when JFR justified the tightening).

### Claudes Discretion

- Concrete `paralife.runtime.jetty.*` and `paralife.runtime.app.*` field names and defaults
  — driven by profile evidence in planning.
- Profile artifact size bounds (suggested: ≤10 MB per file, ≤50 MB total — relaxed from initial ≤5/≤20 per cross-AI review concern #3).
- Exact LoadHarness ramp / duration / seed combinations for the per-tier profile runs.
- Whether `paralife.runtime.app.*` is a single record or split.
- Choice of GC for each tier (ZGC vs G1) — JFR evidence drives this.
- Format of profile-finding citations in `20-RUNTIME.md`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level

- `.planning/REQUIREMENTS.md` — SCALE-08 / SCALE-09 acceptance text
- `.planning/PROJECT.md` — overall vision; M4 scale milestone scope
- `.planning/STATE.md` — current milestone position; deferred-items table (TD-19.5-A,
  TD-22-A..E) constrains D-11 / D-12
- `.planning/ROADMAP.md` — Phase 20 entry, Phase 21 / 22.1 dependencies, Phase 19.1
  dependency on Phase 20
- `CLAUDE.md` §Conventions → Concurrency — single-threaded mutation invariant must be
  preserved by any tuning
- `CLAUDE.md` §Outbound concurrency — Phase 17 D-10 VT-per-session queue; Phase 20 tunes
  its parameters
- `CLAUDE.md` §Connection model — Phase 18 D-05/D-21 WS:entity 1:1 codification (D-01
  honours)
- `CLAUDE.md` §markStalled close-then-best-effort-OOB — P19.1 D-07; baseline-shifting change
  D-19 anchors against
- `README.md` — D-16 adds operator-level rationale here

### Prior phases referenced as locked contracts

- `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` — milestone-locked compact
  wire grammar; D-10 codec tuning must not mutate
- `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` — admission +
  backpressure surface; tuning must not regress
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` D-10 —
  VT-per-session outbound queue (joined by `paralife.runtime.app.*`)
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` D-15 —
  "tuning lives in config" precedent the runtime records follow
- `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` §1 —
  D-05/D-21 WS:entity 1:1 architectural principle + 5000-conn/JVM ceiling
- `.planning/phases/18-external-load-harness-harness-identity/18-CONTEXT.md` D-02 —
  5000 concurrent WS connections per JVM design ceiling
- `.planning/phases/18-external-load-harness-harness-identity/18-CONTEXT.md` D-21 —
  multi-entity-per-WS exception path (NOT invoked, see D-01)
- `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md`
  D-08 / D-10 / D-11 — single-thread mutation core preserved; golden-trace byte-equivalence
  gate (D-11 reuses)
- `.planning/phases/19.1-address-p19-multi-review-pass-4-findings-f1-f2-f3-unshipped-/19.1-03-PLAN.md`
  D-07 / D-13 / D-14 — markStalled close-aware detach + L1 detach-timeout counter
  (D-18 promotes; D-19 anchors)
- `.planning/phases/19.1-address-p19-multi-review-pass-4-findings-f1-f2-f3-unshipped-/19.1-05-PLAN.md`
  D-11 / D-12 — `GoldenTraceWithActionsTest` + `LiveEntityRegistryInvariantTest` (D-11
  three-gate stack)
- `.planning/phases/22-integration-test-resource-leak-audit/22-SUMMARY.md` — TD-22-A..D
  tech-debt register (A/B/C `@Disabled`; D never disabled — in-suite flake); D-12 inherits

### In-tree code referenced as ground truth

- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — WS upgrade site;
  D-02 inline comment lands here
- `src/main/java/com/paralife/admission/OutboundSender.java` — per-session VT loop +
  bounded queue; D-02 inline comment + outbound queue depth field is part of the new
  `paralife.runtime.app.*` record (note: lives under `com.paralife.admission`, NOT
  `com.paralife.websocket` as prior draft claimed)
- `src/main/java/com/paralife/admission/AdmissionMetrics.java:65, :74, :175-176, :451` —
  `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout` registration
  sites
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — frame encode hot path
  candidate for D-10 codec opts
- `src/main/java/com/paralife/codec/PerceptionCodec.java` (and `Base64Codec`) — primary
  target for D-10 codec hot-path opts (corrected path)
- `src/main/java/com/paralife/admission/AdmissionConfig.java:114` —
  `outbound-queue-size` binding site; D-20 leaves intact, layers new keys alongside
- `src/main/java/com/paralife/engine/SpawnConfig.java`, `RespawnConfig.java`,
  `GridConfig.java`, `TickConfig.java` — `@ConfigurationProperties` precedent records the
  new `paralife.runtime.*` records mirror
- `src/main/resources/application.yml` — config root; new `paralife.runtime.*` keys land
  here with sensible defaults; **`management.endpoints.web.exposure.include: health,info,metrics` (line 15) exposes `/actuator/metrics/{name}` — Plan 1c (originally Plan 1b, superseded) + Plan 5 use this for headline-gauge JSON sidecar capture per pass-2 Concern #10 disposition**
- `build.gradle.kts:75-76` — `forkEvery=1` enforced unconditional (P22); D-06 notes profile
  runs are out-of-test so this does not apply
- `src/test/resources/junit-platform.properties` — 5-min JUnit timeout (P22); same
  exclusion logic as `forkEvery=1`

### Phase-internal artifacts to be created

- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` — D-14
  canonical operator/contributor doc
- `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` — D-05 committed
  JFR + flamegraph artifacts; baseline filenames cite `c22e487` per D-19; **per-tier metric sidecar JSON files (`metrics-{N}bots-baseline-c22e487.json` + `metrics-1000bots-tuned-{HEAD}.json`) added pass-2 per Concern #10 disposition**
  (superseded by Plan 1c re-anchor — see D-19 annotation; shipped names use `62c1b44` churn baseline / `active-50xfood-103a615` active scenario / `active-50xfood-tuned-424e06d` tuned)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **Phase 17 D-10 VT-per-session OutboundSender + bounded queue** (lives at
  `com.paralife.admission.OutboundSender`) — the outbound queue depth config already exists
  (`paralife.admission.backpressure.outbound-queue-size`). D-20 leaves it in place; new
  `paralife.runtime.app.*` keys layer alongside. The queue depth remains the single most
  important live-tunable runtime knob; stalled-session callback + metric gauge
  (`paralife.backpressure.stalled.sessions`) intact.
- **Existing `@ConfigurationProperties` records** (`AdmissionConfig`, `RespawnConfig`,
  `SpawnConfig`, `GridConfig`, `TickConfig`, `SimulationConfig`) — established record-based
  binding pattern. New `paralife.runtime.*` records follow same shape.
- **Three-gate equivalence stack** (P19 D-10 + P19.1 D-11 + P19.1 D-12) — already in repo;
  D-11 reuses unchanged. TD-19.5-A flake caveat: in-suite signal trustworthy, isolated runs
  not. **L1 detach-timeout counter** (`paralife.outbound.detach.timeout` at
  `AdmissionMetrics.java:74`) — new headline gauge per D-18.
- **`paralife.simulation.spawn.seed` (SpawnConfig)** — D-06 profile runs use it for
  reproducibility; same precedent as Phase 19 D-06 placement-determinism contract.
- **LoadHarness (Phase 18)** — the 1000-bot driver. Phase 20 profile runs invoke it directly
  via `loadHarnessJar` CLI (D-06); no harness-side code change. `--harness-id` and JSON run
  report give per-run attribution.
- **Spring Boot Actuator at `/actuator/metrics/{name}`** (already wired via `application.yml:15`) — Plan 1c baseline capture (originally Plan 1b, superseded by the `62c1b44` re-anchor) + Plan 5 tuned capture poll the named meters via `curl` into JSON sidecars during the load window (200 s churn / 90 s active / 180 s tuned). No code change required (pass-2 Concern #10 disposition).

### Established Patterns

- **`@ConfigurationProperties` records bound from `application.yml`** (CLAUDE.md §Spring
  patterns) — D-09 follows.
- **Spec-doc-per-phase mirroring** — `17-ADMISSION.md`, `18-HARNESS.md` precedents;
  `20-RUNTIME.md` (D-14) follows the same structure.
- **Tuning lives in config, not constants** (Phase 17 D-15 precedent) — D-07 / D-08 / D-09
  inherit.
- **Evidence-driven tuning** (Phase 17 reference) — D-04 / D-05 / D-13 inherit.
- **Commit-SHA-anchored evidence artifacts** (new convention via D-19) — every committed
  baseline JFR cites the SHA that produced it.

### Integration Points

- **Config record binding sites:** new records bound at the `@SpringBootApplication` /
  config-class layer; injected into `OutboundSender`, `TickBroadcaster`, codec components,
  `WebSocketConfig`.
- **Profile runs:** invoked from CLI scripts via standalone `loadHarnessJar`. P22's
  `forkEvery=1` and 5-min JUnit timeout do not apply (D-06).
- **Documentation cross-refs:** `CLAUDE.md` §Runtime tuning (new); `README.md` operator
  paragraph (new); `20-RUNTIME.md` (new). `17-ADMISSION.md` and `18-HARNESS.md` cross-link
  Phase 20 only if Phase 20 changes their referenced behaviour (probably not — P20 is
  additive; D-20 confirms namespace stays put).
- **Verification gate:** Three-gate stack from D-11 + full existing test suite minus 4
  `@Disabled` (D-12). No new gate.
- **Phase 19.1 boundary:** parallel `PerceptionBroadcaster` + tick encode lands AFTER
  Phase 20 per ROADMAP.md. Phase 20's `paralife.runtime.app.*` record SHOULD reserve fields
  for parallelism settings (e.g., `parallel-encode-threshold`) so 19.1 extends cleanly.
- **Phase 21 boundary:** Phase 21 benchmark scripts consume `20-RUNTIME.md` per-tier
  recipes directly. Recipes must be copy-pasteable shell + config snippets.
- **Phase 22.1 boundary:** P22.1 owns its own invariant diff (D-17). P20 ships no extra
  invariant gate.

</code_context>

<specifics>
## Specific Ideas

- **WS:entity 1:1 deliberate-choice rationale is non-negotiable architectural identity.**
  User framing: "pretend each bot is a user connecting from their own machine." D-01 / D-02
  codify this in three places (README.md, CLAUDE.md, in-code comments) specifically so
  future contributors do not "fix" it by introducing multiplexing.
- **Programmatic config control is required.** Drives a future local test bench that sweeps
  `paralife.runtime.*` configs (and possibly an automated search — ILP, grid, Bayesian) to
  find combinations that yield "interesting sims". Phase 20 ships the override surface; the
  search algorithm itself is deferred.
- **Admin observer UI for runtime knobs is a stated future goal** (post-MVP M5). Phase 20
  designs records to be live-tunable so the future actuator endpoint and admin UI can
  mutate them without a record redesign.
- **Profile artifacts in repo are the project's "evidence over assertion" stance.**
  Reviewers can open the JFR to challenge any tuning recommendation. D-19 strengthens this:
  baseline JFRs name the commit SHA they were captured against, so the comparison is
  reproducible months later.
- **Time pressure on M4 → MVP** drove G-01 → D-17 (P22.1 owns its diff) over the
  alternatives. Same MVP lens applied to D-20 (layer alongside, defer rename).

</specifics>

<deferred>
## Deferred Ideas

### Admin Observer UI live-tune of `paralife.runtime.*` knobs (M5)

- **What:** Web admin UI exposing `paralife.runtime.jetty.*` and `paralife.runtime.app.*`
  records as readable-by-everyone, writable-by-admin form. Live-tunable fields apply via a
  Spring actuator endpoint backed by `@RefreshScope`.
- **Why deferred:** M5 scope (operator UX). Phase 20 designs records to make this clean.

### Automated config search over `paralife.runtime.*` for "interesting sims"

- **What:** Test-bench harness driving many sims with config overrides, scoring each on
  emergent-behaviour metrics. Search algorithm TBD (ILP, grid, or Bayesian).
- **Phase 20 enables it:** CLI override surface exists; LoadHarness JSON run report
  carries per-run counters; profile artifacts give per-config evidence base.

### Sub-WS transport multiplex / genuine multi-entity-per-WS

- **What:** HTTP/2-style transport multiplexing or genuine multi-entity-per-WS via P18 D-21
  exception path.
- **Why deferred (and almost certainly never built):** Contradicts D-01 / D-02. Revisit ONLY
  if Phase 21 evidence shows per-connection overhead is the binding constraint at 5000
  conns/JVM and tuning has been exhausted (D-03).

### JMH micro-benchmarks for codec hot paths

- **What:** Committed `src/jmh/` harness exercising `PerceptionCodec.encode` and frame
  encoders.
- **Why deferred:** Add only if JFR + async-profiler evidence in Phase 20 fingers a codec
  hot path. Codec micro is unlikely to dominate at 1000-conn scale.

### Phase 19.1 parallel read-only sub-steps

- **What:** Parallel `PerceptionBroadcaster` + parallel tick-encode.
- **Phase 20 enables it:** `paralife.runtime.app.*` record reserves parallelism-related
  fields (e.g., `parallel-encode-threshold`) so 19.1 extends cleanly.
- **Why deferred:** Already a separately roadmapped phase; depends on Phase 20.

### Backlog items added by this CONTEXT (post-MVP)

- **Phase 999.x: `paralife.runtime.app.*` namespace consolidation** — fold
  `paralife.admission.backpressure.outbound-queue-size` (and any sibling keys grown post-P20)
  under `paralife.runtime.app.outbound.*`. Includes deprecate-and-alias migration, doc
  rewrites in CLAUDE.md / `17-ADMISSION.md`, test renames. Deferred per D-20 to keep P20
  MVP-direct.
- **Phase 999.x: P20 baseline JFR re-run for apples-to-apples comparison** — once M4
  closes (P21 done) and any P22.1 cleanup lands, re-capture the P20 baseline JFR against
  the latest tuned-system HEAD and re-publish the before/after deltas in `20-RUNTIME.md`.
  The original `c22e487`-anchored baseline (D-19) is intentionally frozen for
  reproducibility; this backlog item produces a fresh, post-MVP baseline so future tuning
  decisions compare against current reality, not the P19.1-close snapshot.
  (superseded by Plan 1c re-anchor — see D-19 annotation; canonical frozen baseline is now `62c1b44`)

### Reviewed Todos (not folded)

None — no pending todos surfaced for Phase 20.

</deferred>

---

*Phase: 20-connection-multiplexing-runtime-tuning*
*Context gathered: 2026-05-09 (rebuilt from 2026-05-02 superseded draft + audit)*
*Pass-2 amendments: 2026-05-09 evening — D-21 outcome 4 added (dominant pinning with backlog-handoff); noise-floor convention added; actuator metric exposure noted in canonical refs; per-tier metric sidecars added to phase-internal artifacts list*
