# Phase 20: Runtime Tuning Spec

**Phase:** 20-connection-multiplexing-runtime-tuning
**Status:** Authoritative — locks D-01..D-21 from 20-CONTEXT.md
**Requirements:** SCALE-08 (overhead reduction, equivalent-strategy escape hatch), SCALE-09 (measured tuning, not guesswork)
**Profile baseline:** Commit SHA `62c1b44` (Plan 1c F1/F2/F6 re-anchor, post-Phase 19.1 close)

See also: `17-ADMISSION.md` §3 (STALLED FSM, the path Phase 20 tunes for stability under), `18-HARNESS.md` §1 (5000-conn/JVM design ceiling), `15-SCHEMA.md` §12 (codec validation bounds — Phase 20 D-10 codec opts MUST NOT relax).

***

## §1 Architectural Principle: WS:entity 1:1 (D-01 / D-02)

**Paralife treats every bot as if it were a user connecting from their own
machine.** Many concurrent independent WebSocket connections is a stated
architectural property (CLAUDE.md §Connection model; 18-HARNESS.md §1 D-05/D-21);
it is not an inefficiency to fix.

Phase 20 takes SCALE-08's "or equivalent transport-level scale strategy" escape
hatch (REQUIREMENTS.md):

> **SCALE-08:** High bot-count runs reduce socket or process overhead through
> connection multiplexing **or an equivalent transport-level scale strategy.**

The equivalent strategy is the four-layer tuning surface in §2: per-connection
cost is reduced by JVM/Jetty/application/codec tuning, never by collapsing
multiple entities onto a shared connection.

**This is a deliberate choice, NOT a missing optimisation.** Future contributors
who notice the 1000+ VT count or the per-session bounded queue should not "fix"
the design without an ADR per Phase 18 D-21.

**Forward note (D-03):** Revisit §1 only if Phase 21 evidence shows the
per-connection overhead path is the binding constraint at 5000 conns/JVM (the
18-HARNESS.md §1 D-02 ceiling) AND tuning under §2 has been exhausted.

***

## §2 Tuning Surface (D-07)

| Layer | Where it lives | Change-time | Phase 20 ships |
|-------|---------------|-------------|----------------|
| 1. JVM/runtime | `java -jar` launch flags, documented in §3 per-tier recipes | launch only | doc-only presets (D-08 — no wrapper script) |
| 2. Jetty/network | `paralife.runtime.jetty.*` `@ConfigurationProperties` (`JettyRuntimeConfig`) | launch (per Jetty `Configurable` API contract) | record + bindings + project-current defaults (Jetty defaults except `idleTimeoutMs=60000`) |
| 3. Application | `paralife.runtime.app.*` `@ConfigurationProperties` (`AppRuntimeConfig` with nested `OutboundConfig`, `EncodeConfig`) | mixed (live-tunable seams reserved for M5 admin UI) | record + bindings + defaults |
| 4. Codec impl | internal constants in `PerceptionCodec` and `Base64Codec` — JFR-validated | code change only | hot-path opts, no public surface |

### §2.1 Layer 2 — Jetty knobs (`JettyRuntimeConfig`)

| Knob | Default | Bound on | Notes |
|------|---------|----------|-------|
| `paralife.runtime.jetty.input-buffer-size` | 4096 | `Configurable.setInputBufferSize` | Jetty default; bytes |
| `paralife.runtime.jetty.output-buffer-size` | 4096 | `Configurable.setOutputBufferSize` | Jetty default; bytes |
| `paralife.runtime.jetty.max-frame-size` | 65536 | `Configurable.setMaxFrameSize` | T-20-DOS-1 cap |
| `paralife.runtime.jetty.max-binary-message-size` | 65536 | `Configurable.setMaxBinaryMessageSize` | bytes |
| `paralife.runtime.jetty.max-text-message-size` | 65536 | `Configurable.setMaxTextMessageSize` | bytes |
| `paralife.runtime.jetty.idle-timeout-ms` | 60000 | `Configurable.setIdleTimeout` | Project-current default (Jetty's own default is 30000); also bound via legacy `paralife.websocket.idle-timeout-ms` for back-compat |
| `paralife.runtime.jetty.auto-fragment` | true | `Configurable.setAutoFragment` | |
| `paralife.runtime.jetty.max-outgoing-frames` | -1 (unlimited) | `Configurable.setMaxOutgoingFrames` | Carve-out: -1 OR positive ≥1. Secondary to Phase 17 D-10 `OutboundSender` bounded queue (primary outbound backpressure signal); -1 = delegate entirely to D-10 queue |

All eight are launch-only per Jetty 12's `Configurable` contract.

### §2.2 Layer 3 — Application knobs (`AppRuntimeConfig`)

**Pass-2 Concern #7:** All four fields in `AppRuntimeConfig` are tagged `[reserved — no effect in Phase 20]`. The binding surface exists for future consumers (M5 admin UI, Phase 19.1 parallel encode, future codec API extension); changing any of these in Phase 20 is a no-op observable to operators.

| Knob | Default | Notes |
|------|---------|-------|
| `paralife.runtime.app.outbound.queue-watermark-pct` | 80 | [reserved — no effect in Phase 20] slow-client warning threshold; consumer wiring deferred (M5 admin UI) |
| `paralife.runtime.app.outbound.frame-size-budget-bytes` | 1024 | [reserved — no effect in Phase 20] codec sizing hint; PerceptionCodec.encode(Frame) takes no capacity arg, consumer wiring deferred (Pass-2 Concern #7 — Plan 5 forbids the API change required to consume this) |
| `paralife.runtime.app.encode.parallel-encode-threshold` | -1 | [reserved — no effect in Phase 20] Phase 19.1 reservation; -1 = disabled |
| `paralife.runtime.app.encode.encode-batch-hint` | 8 | [reserved — no effect in Phase 20] tick-broadcast batch hint |

**D-20:** the existing `paralife.admission.backpressure.outbound-queue-size`
(default 128) is **NOT** moved into `paralife.runtime.app.*`. It stays in
`AdmissionConfig` for Phase 20; namespace consolidation is Phase 999.4. **The 128
queue depth remains the single most important live-tunable runtime knob in Phase
20** (consumed by `OutboundSender` per Phase 17 D-10); see Phase 17
17-ADMISSION.md §3 for tuning guidance.

### §2.3 Layer 4 — Codec impl (D-10, JFR-driven only)

Internal constants in `src/main/java/com/paralife/codec/PerceptionCodec.java`
and `Base64Codec.java`. Specific opts are picked from JFR evidence per Plan 5;
no public configuration surface. Wire format stays bit-exact (`15-SCHEMA.md`
LOCKED).

***

## §3 Per-Scale-Tier Recipes

> Plan 4 ships these recipes with **placeholder GC choices** marked `Pending —
> JFR-driven; tuned in Plan 5`. Plan 5 (codec opts) and Plan 6 (final docs)
> upgrade them with measured-justified choices once the tuned-state JFRs land.

[Per-tier recipes go here — Task 4.2 populates.]

***

## §4 Profile Findings

> Populated by Plan 5 (codec opts) and Plan 6 (final write-up). Headline numbers
> (D-13 / D-18) live in §4.2.

### §4.1 Methodology

The Phase 20 capture ritual is documented in
`.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md`
and `tools/async-profiler-bootstrap.md`. Every committed JFR cites the source
SHA in its filename per D-19. **Headline gauges (`paralife.tick.health.work-time-ms`,
`paralife.outbound.detach.timeout`) are sourced from `/actuator/metrics/{name}`
JSON sidecars per Pass-2 Concern #10, not from server log grepping.** Noise-floor
convention per D-21: ±5% of baseline mean OR ±1σ, whichever is larger, computed
across the JFR sample window.

### §4.2 Headline numbers (D-13 / D-18)

| Metric | 100 baseline | 100 tuned | 500 baseline | 500 tuned | 1000 baseline | 1000 tuned |
|--------|------|------|------|------|------|------|
| `paralife.tick.health.work-time-ms` (mean ms) | _Pending — Plan 1c actuator sidecar_ | _baseline-only — see Phase 21_ | _Pending — Plan 1c actuator sidecar_ | _baseline-only — see Phase 21_ | _Pending — Plan 1c actuator sidecar_ | _Pending — Plan 5 actuator sidecar_ |
| `paralife.outbound.detach.timeout` (count) | _Pending_ | _baseline-only — see Phase 21_ | _Pending_ | _baseline-only — see Phase 21_ | _Pending_ | _Pending_ |
| `jdk.VirtualThreadPinned` (events/min @ 20ms) | _Pending_ | _baseline-only — see Phase 21_ | _Pending_ | _baseline-only — see Phase 21_ | _Pending_ | _Pending_ |

### §4.3 Per-tier narrative

_Plan 6 populates._

### §4.4 Codec hot-path opts (D-10)

_Plan 5 populates per-opt entries (JFR signal, code change summary, three-gate
stack run record, tuned-state JFR delta) — OR a single "(null-result)" / "(dominant pinning with backlog-handoff)" row per D-21 outcomes 3/4._

***

## §5 Forward Notes

- **Admin-UI live-tune (M5):** `AppRuntimeConfig` fields ship as `[reserved — no effect in Phase 20]` and are pre-shaped for `@RefreshScope`-compatible live-tune in M5; `JettyRuntimeConfig` is launch-only by Jetty 12's `Configurable` contract and is not a live-tune candidate. The live-tunable backpressure knob in Phase 20 is `paralife.admission.backpressure.outbound-queue-size` (Phase 17 D-10).
- **Automated config search:** The CLI override surface (`-Dparalife.runtime.x=y` / env / `@TestPropertySource`) enables future ILP/grid/Bayesian sweeps over the `paralife.runtime.*` knobs without record redesign. Search algorithm itself is deferred (post-MVP).
- **Revisit-multiplex trigger (D-03):** Only if Phase 21 evidence shows per-connection overhead at 5000 conns/JVM is the binding constraint AND §3 tuning has been exhausted.
- **Namespace consolidation (Phase 999.4):** Future fold of `paralife.admission.backpressure.outbound-queue-size` → `paralife.runtime.app.outbound.queue-size` with deprecate-and-alias migration. D-20 deferred this to keep P20 MVP-direct.
- **Reserved-field consumer wiring (Phase 999.4 + Phase 19.1):** The four `[reserved]` fields in `AppRuntimeConfig` await consumers — `parallel-encode-threshold` for Phase 19.1 parallel encode; `frame-size-budget-bytes` for any future PerceptionCodec API that accepts capacity hints; `queue-watermark-pct` and `encode-batch-hint` for M5 admin UI.
- **Phase 19.1 follow-up:** parallel `PerceptionBroadcaster` will consume `AppRuntimeConfig.encode.parallelEncodeThreshold` (currently sentinel-disabled at -1).
- **Phase 999.5:** Re-capture the baseline against post-M4 HEAD for fresh apples-to-apples comparison; the canonical `62c1b44` baseline (Plan 1c F6 re-anchor; D-19 in 20-CONTEXT.md still nominally cites the superseded `c22e487` capture — Plan 6 reconciles) is intentionally frozen for reproducibility.
- **Phase 999.6 (added Pass-1 Concern #2 disposition):** `vt-pinning-reentrantlock-conversion` — lifts if Plan 5 outcome 4 (dominant pinning with backlog-handoff per D-21) is reached, OR if Phase 21 benchmark evidence shows pinning is binding.

***

## §6 Profile Index

> The Phase 20 canonical baseline is anchored at SHA `62c1b44` (Plan 1c F6 re-anchor; supersedes the original `c22e487` capture which surfaced D1/D2/D3 fixes that shifted the post-fix baseline). Both capture sets remain on disk for historical reference; only the `62c1b44` series is cited by §3 recipes, §4 numbers, and D-19. (D-19 in `20-CONTEXT.md` still nominally names `c22e487`; this is a known doc-drift cleanup deferred to Plan 6 + the 20-06-PLAN VALIDATION flip.)

| Filename | Scenario | Source SHA | Captured | Size | Notes |
|----------|----------|------------|----------|------|-------|
| `profiles/jfr-100bots-baseline-62c1b44.jfr` | 100 bots, balanced mix, seed=20251205 | 62c1b44 | _Plan 1c_ | _≤10 MB_ | baseline |
| `profiles/jfr-500bots-baseline-62c1b44.jfr` | 500 bots, balanced mix | 62c1b44 | _Plan 1c_ | _≤10 MB_ | baseline |
| `profiles/jfr-1000bots-baseline-62c1b44.jfr` | 1000 bots, balanced mix | 62c1b44 | _Plan 1c_ | _≤10 MB_ | baseline |
| `profiles/cpu-1000bots-baseline-62c1b44.html` | async-profiler CPU @ 1000 | 62c1b44 | _Plan 1c_ | _≤10 MB_ | flamegraph |
| `profiles/alloc-1000bots-baseline-62c1b44.html` | async-profiler alloc @ 1000 | 62c1b44 | _Plan 1c_ | _≤10 MB_ | flamegraph |
| `profiles/lock-1000bots-baseline-62c1b44.html` | async-profiler lock @ 1000 | 62c1b44 | _Plan 1c_ | _≤10 MB_ | flamegraph |
| `profiles/metrics-{100,500,1000}bots-baseline-62c1b44.json` | actuator metric sidecars (Pass-2 Concern #10) | 62c1b44 | _Plan 1c_ | _~13–14 KB each_ | 6-sample headline-gauge JSON snapshots |
| `profiles/jfr-{100,500,1000}bots-active-50xfood-103a615.jfr` | active-population scenario (50× food, sustained live pop) | 103a615 | _Plan 1c §Active_ | _~0.35–0.8 MB each_ | **transport-overhead evidence — Plan 5/6 tune against this set** (20-01c-SUMMARY:144-147) |
| `profiles/{cpu,alloc,lock}-{100,500,1000}bots-active-50xfood-103a615.html` | async-profiler flamegraphs (active scenario) | 103a615 | _Plan 1c §Active_ | _17–157 KB each_ | active-profile flamegraphs |
| `profiles/metrics-{100,500,1000}bots-active-50xfood-103a615.json` | actuator metric sidecars (active scenario) | 103a615 | _Plan 1c §Active_ | _~39–43 KB each_ | 18-sample headline-gauge JSON snapshots (3× baseline sample count) |
| `profiles/jfr-{100,500,1000}bots-baseline-62c1b44.meta.json` | JFR capture metadata sidecars (baseline) | 62c1b44 | _Plan 1c_ | _~1.2 KB each_ | per-JFR provenance: SHA / cap / seed / asprof rate |
| `profiles/jfr-{100,500,1000}bots-active-50xfood-103a615.meta.json` | JFR capture metadata sidecars (active scenario) | 103a615 | _Plan 1c §Active_ | _~0.7 KB each_ | per-JFR provenance: SHA / cap / seed / asprof rate |
| `profiles/jfr-1000bots-tuned-<HEAD>.jfr` | 1000 bots, tuned-state | _HEAD post-Plan 5_ | _Plan 5_ | _≤10 MB_ | post-codec-opts; Plan 5 commits |
| `profiles/{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html` | async-profiler flamegraphs (tuned) | _HEAD post-Plan 5_ | _Plan 5_ | _≤10 MB each_ | tuned-state flamegraphs |
| `profiles/metrics-1000bots-tuned-<HEAD>.json` | actuator metric sidecar (tuned) | _HEAD post-Plan 5_ | _Plan 5_ | _~13–14 KB_ | tuned-state headline-gauge JSON snapshot |

***

*Phase 20-connection-multiplexing-runtime-tuning · v1 · 2026-06-XX*
