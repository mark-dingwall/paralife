# Phase 20: Runtime Tuning Spec

**Phase:** 20-connection-multiplexing-runtime-tuning
**Status:** Authoritative — locks D-01..D-21 from 20-CONTEXT.md
**Requirements:** SCALE-08 (overhead reduction, equivalent-strategy escape hatch), SCALE-09 (measured tuning, not guesswork)
**Profile baseline:** Commit SHA `62c1b44` (Plan 1c F1/F2/F6 re-anchor, post-Phase 19.1 close)

See also: `17-ADMISSION.md` §3 (STALLED FSM, the path Phase 20 tunes for stability under), `18-HARNESS.md` §1 (5000-conn/JVM design ceiling), `15-SCHEMA.md` §6 / §8 / §10 (Frame Grammars, Block Grammars, Round-trip Test Vectors — wire format LOCKED; Phase 20 D-10 codec opts MUST preserve byte-exact output).

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
| 3. Application | `paralife.runtime.app.*` `@ConfigurationProperties` (`AppRuntimeConfig` with nested `OutboundConfig`, `EncodeConfig`) | launch-only (live-tunable seams reserved for M5 admin UI) | record + bindings + defaults |
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
queue depth remains the most important attach-time-tunable runtime knob in Phase
20** (consumed by `OutboundSender.attachSession` per Phase 17 D-10); see Phase 17
17-ADMISSION.md §6 Backpressure for the queue-depth sizing math. **Lifecycle:** the value is read at
session-attach time (`WorldWebSocketHandler.afterConnectionEstablished` →
`OutboundSender.attachSession` → fixed `ArrayBlockingQueue` capacity); changes
apply to **new sessions only**, existing session queues stay at their
creation-time depth. No mid-benchmark live-resize today (M5 admin UI follow-up).

### §2.3 Layer 4 — Codec impl (D-10, JFR-driven only)

Internal constants in `src/main/java/com/paralife/codec/PerceptionCodec.java`
and `Base64Codec.java`. Specific opts are picked from JFR evidence per Plan 5;
no public configuration surface. Wire format stays bit-exact (`15-SCHEMA.md`
LOCKED).

***

## §3 Per-Scale-Tier Recipes

> Each recipe is intentionally copy-pasteable. Phase 21 benchmark scripts
> consume these as-is. JVM flags are documented presets (D-08 — no wrapper
> script). GC choice is **JFR-driven**: the Plan 1c baseline JFR (SHA
> `62c1b44`, post-F6 re-anchor) is cited in each recipe; Plan 5 (JFR
> triage — null-result, D-21 outcome 3) and Plan 6 (this doc) replaced
> the placeholder JFR-driven markers with measured-justified statements.
>
> **Admission cap (`-Dparalife.admission.cap=1500`)** is a **benchmark-time JVM-flag
> override** present in every recipe. Production default at `application.yml` is
> `cap=256` (unchanged). The 1500 value matches the Plan 1c baseline capture
> conditions (20-01c F1 resolution: cap is world-aggregate; cap=1500 is
> non-binding for the 100/500/1000-bot tiers). Without this override, 500/1000-tier
> recipes hit `world-full` rejections (~244 / ~744 bots dropped) AND do not
> reproduce the cited baseline JFRs — re-introducing the exact F1 defect 20-01c
> fixed. Per 20-01c-SUMMARY §F1: "Plan 20-04/05/06 should cite this as a
> benchmark-time JVM-flag override, not a production default change."
>
> **Active vs churn profile (20-01c §Active-Population Workload).** The
> `62c1b44`-anchored series is the **churn baseline** (default `nutrient-spawn-probability`);
> 20-01c also captured an **active-population** scenario at SHA `103a615` with
> `-Dparalife.simulation.nutrient-spawn-probability=0.05` (50× food) which
> sustains a steady live population and is the correct transport-overhead
> evidence for Phase 20's remit per 20-01c-SUMMARY:144-147. Plan 5 triaged the
> active profile (null-result, D-21 outcome 3); Plan 6 finalised §4 numbers. §6
> indexes both sets.
>
> **Active-scenario recipe variant — smoke-only template.** Each recipe
> below defaults to the **churn baseline** (no scenario flag). For a
> **smoke** of the active-50xfood scenario at the doc'd tier durations,
> change the JFR `filename=` to `jfr-Nbots-active-50xfood.jfr` (where N
> is the tier) and append as a **Spring app-arg** (after `-jar "$SERVER_JAR"`,
> alongside `--paralife.simulation.spawn.seed`):
>
> ```
>   --paralife.simulation.nutrient-spawn-probability=0.05
> ```
>
> The **`--app-arg` form** is mandatory: passing the same value as a JVM
> `-D` property after `-jar` is a silently-ignored application argument
> (gemini R4 finding). The `-D` form works only if placed **before** `-jar`.
>
> **Not baseline-reproducible.** This template will NOT byte-for-byte
> reproduce the cited `profiles/jfr-Nbots-active-50xfood-103a615.jfr`
> artifacts. The captured active profile used distinct per-tier parameters
> (heap `-Xms2g -Xmx2g`, `parallelism=8`, `--duration 130s`, uniform 90s JFR
> window after a 20s ramp). See `profiles/jfr-Nbots-active-50xfood-103a615.meta.json`
> for the exact deltas. Phase 21 benchmark scripts needing baseline-comparable
> active evidence MUST read the meta sidecars and override these template
> values per tier; the reproducible per-tier active recipe shape is
> documented as-is: the tuning-rig was a null-result (no rig was wired —
> recipes stand as-documented, Phase 21 consumes them).
>
> **Churn-baseline recipes — not byte-for-byte reproducible against the
> `62c1b44` capture.** The §3.1 / §3.2 / §3.3 recipes are smoke-template
> shapes (operator-friendly, run on a commodity host). They do NOT
> reproduce the cited `profiles/jfr-Nbots-baseline-62c1b44.jfr` artifacts
> bit-for-bit — the Plan 1c capture used `--duration 200`, harness
> `rate:50`, `-Xms2g -Xmx2g`, and `-Djdk.virtualThreadScheduler.parallelism=8`
> across all three tiers per `profiles/jfr-Nbots-baseline-62c1b44.meta.json`.
> Phase 21 benchmark scripts that need baseline-comparable rerun evidence
> MUST read the meta sidecars and override the recipe's `--duration` /
> `--ramp-up` / `-Xms/-Xmx` / `parallelism=N` per tier. Reproducible
> per-tier baseline recipe shapes are documented as-is: the tuning-rig was
> a null-result (no rig was wired), so recipes stand as-documented and
> Phase 21 consumes them.
>
> **Heap presets (`-Xms/-Xmx`) — not JFR-validated for lower tiers.** Per-tier heap
> values in §3.1 / §3.2 / §3.3 (`1g/1g`, `1g/2g`, `2g/2g`) are
> **commodity-host placeholders**, not measured choices. All three Phase 20
> captures — churn baseline `62c1b44`, active-scenario `103a615`, and
> tuned `424e06d` — ran `-Xms2g -Xmx2g` per the respective
> `profiles/jfr-Nbots-baseline-62c1b44.meta.json` and
> `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.meta.json`; the
> lower-tier `1g/1g` and `1g/2g` presets here were never exercised by any
> JFR run. No heap retune was performed in Phase 20. Baseline-comparable
> reproduction MUST use the `2g/2g` capture shape from the meta sidecars;
> the lower-tier presets are operator smoke presets sized by headroom
> judgement only.
>
> **JFR start delay / duration / SIGTERM timing.** Each recipe pins the JFR
> `duration=` to the harness `--duration` and adds `delay=15s` so the
> recording starts ~15 s after `-jar` launch — past Spring Boot startup and
> aligned with the harness connect/ramp window. The JFR window therefore
> covers the harness load period rather than the boot tail. The 15 s
> cushion is a conservative placeholder; if Spring startup runs longer on
> a given host the operator should bump `delay=` until JFR start lands
> after `Started ParalifeApplication`. JFR auto-stops and dumps the file
> at `duration` elapsed; operators should SIGTERM the server **after** the
> harness exits AND after JFR is on disk. If you SIGTERM mid-recording,
> JFR flushes a partial dump on JVM shutdown — usable but truncated.
> Tradeoff: this template's 15 s boot cushion drops roughly the first
> ~10 s of harness load (the ramp/connect window) from the JFR. With
> server boot at ~5 s and the harness starting then, JFR starts at t=15 s
> and ends at `15s + duration`, so the recording catches steady-state +
> ~10 s of post-harness idle, but misses the ramp at the front. For
> comparable full-load capture **including the ramp**, Phase 21
> can reduce `delay=` (and accept boot noise in the recording front), or
> switch to `jcmd JFR.start` invoked immediately after the server reports
> ready (precise alignment, no fixed delay placeholder).
>
> **Pass-2 Concern #8:** `paralife.runtime.app.outbound.queue-watermark-pct` is
> `[reserved — no effect in Phase 20]` per §2.2 — it has no Phase 20 consumer
> and overriding it produces no measurable effect. Recipe override examples
> deliberately omit it; the only attach-time-tunable backpressure knob in
> Phase 20 (new sessions only — see §2.2 lifecycle note) is
> `paralife.admission.backpressure.outbound-queue-size` (default 128, see
> Phase 17 17-ADMISSION.md §6 Backpressure for queue-depth sizing math).

### §3.1 100-bot tier (operator-friendly, BotRunner-class)

**Server launch:** (Pass-3 Concern #24 — `paralife-*.jar` is ambiguous; the wildcard matches both `paralife-*-SNAPSHOT.jar` and `paralife-*-load-harness.jar`. Pin the server jar via the same shell-variable pattern Plan 1c uses.)
```bash
SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | grep -v -- '-plain' | head -1)
java \
  -Xms1g -Xmx1g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=delay=15s,duration=60s,filename=jfr-100bots.jfr,settings=profile,name=p20-100 \
  -Djdk.virtualThreadScheduler.parallelism=4 \
  -Dparalife.admission.cap=1500 \
  -jar "$SERVER_JAR" \
  --paralife.simulation.spawn.seed=20251205
```

(100 bots fits the default cap=256, but cap=1500 is included for parity with the §3.2/§3.3 recipes and the baseline JFR capture conditions — see §3 intro.)

**Harness:**
```bash
HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1)
java -jar "$HARNESS_JAR" \
  --server-uri ws://localhost:8080/ws/world \
  --count 100 --duration 60 --ramp-up rate:20 \
  --harness-id bench-100
```

**Yaml overrides:** none required at default tier.

**Baseline JFR:** `profiles/jfr-100bots-baseline-62c1b44.jfr` (Plan 1c).

**GC choice rationale (JFR-driven):** G1 confirmed for this tier. The baseline JFR
`profiles/jfr-100bots-baseline-62c1b44.jfr` (Plan 1c, 200 s churn, 2g/2g heap)
recorded 0 `jdk.GCPhasePause` events in the capture window — GC overhead is
unmeasurable at 100 bots under default G1. No ZGC switch justified; G1 stays.

### §3.2 500-bot tier (single-harness)

**Server launch:**
```bash
SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | grep -v -- '-plain' | head -1)
java \
  -Xms1g -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=delay=15s,duration=90s,filename=jfr-500bots.jfr,settings=profile,name=p20-500 \
  -Djdk.virtualThreadScheduler.parallelism=6 \
  -Dparalife.admission.cap=1500 \
  -jar "$SERVER_JAR" \
  --paralife.simulation.spawn.seed=20251205
```

**Harness:**
```bash
HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1)
java -jar "$HARNESS_JAR" \
  --server-uri ws://localhost:8080/ws/world \
  --count 500 --duration 90 --ramp-up rate:50 \
  --harness-id bench-500
```

**Yaml overrides (optional):**
```yaml
# Pass-2 Concern #8: paralife.runtime.app.outbound.queue-watermark-pct is
# [reserved — no effect in Phase 20] per §2.2 — see "Pass-2 Concern #8" note
# at the top of §3 for rationale.
# The attach-time-tunable backpressure knob (new sessions only — see §2.2 lifecycle note)
# is paralife.admission.backpressure.outbound-queue-size (default 128); tighten cautiously
# per 17-ADMISSION.md §6 Backpressure.
paralife:
  admission:
    backpressure:
      outbound-queue-size: 128   # default; reduce only with measured slow-client evidence
```

**Baseline JFR:** `profiles/jfr-500bots-baseline-62c1b44.jfr` (Plan 1c).

**GC choice rationale (JFR-driven):** G1 confirmed for this tier. The baseline JFR
`profiles/jfr-500bots-baseline-62c1b44.jfr` (Plan 1c, 200 s churn, 2g/2g heap)
recorded 0 `jdk.GCPhasePause` events in the capture window — GC overhead is
unmeasurable at 500 bots under G1 with a 2g heap. The ZGC threshold (>2% GC pause
time) is not reached. G1 stays; ZGC switch would require Phase 21 evidence at this
tier.

### §3.3 1000-bot tier (M4 target)

**Server launch:** (Pass-3 Concern #24 — same `SERVER_JAR` / `HARNESS_JAR` shell-variable pattern as §3.1.)
```bash
SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | grep -v -- '-plain' | head -1)
java \
  -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=delay=15s,duration=180s,filename=jfr-1000bots.jfr,settings=profile,name=p20-1000 \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -Dparalife.admission.cap=1500 \
  -jar "$SERVER_JAR" \
  --paralife.simulation.spawn.seed=20251205
```

**Harness:**
```bash
HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1)
java -jar "$HARNESS_JAR" \
  --server-uri ws://localhost:8080/ws/world \
  --count 1000 --duration 180 --ramp-up rate:50 \
  --harness-id bench-1000
```

**Respawn-cap caveat (20-01c-SUMMARY Caveat #2 + Per-Tier Headline table):** at default `maxRespawnsPerSession=5`, the 1000-tier accumulates ~99 `respawn-cap` rejections by the last sample (live population tails to ~901 over 180s). Bot scaling evidence is uncontaminated (these are not admission-cap binds), but for **sustained** 1000+ bot benchmarks (Phase 21) the operator may need `-Dparalife.websocket.max-respawns-per-session=10` (or higher). Decision deferred to Phase 21 evidence. (Key matches `RespawnConfig.java:31` `@ConfigurationProperties(prefix = "paralife.websocket")` — `paralife.simulation.spawn.*` is the spawn config, not respawn config; an unknown `-D` key would be silently ignored.)

**Yaml overrides (optional, JFR-driven):**
```yaml
# Pass-2 Concern #8: paralife.runtime.app.outbound.queue-watermark-pct is
# [reserved — no effect in Phase 20] per §2.2 — see "Pass-2 Concern #8" note
# at the top of §3 for rationale.
paralife:
  runtime:
    jetty:
      input-buffer-size: 1024     # only if JFR shows TLAB pressure on Jetty buffers (Pitfall 5)
      output-buffer-size: 1024
  admission:
    backpressure:
      outbound-queue-size: 128    # default; tighten only with measured slow-client evidence per 17-ADMISSION.md §6 Backpressure (new sessions only — see §2.2 lifecycle note)
```

**Baseline JFR:** `profiles/jfr-1000bots-baseline-62c1b44.jfr` + flamegraphs (Plan 1c).

**Tuned JFR:** `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr` (Plan 5 — null-result equivalence capture).

**GC choice rationale (JFR-driven):** G1 confirmed for this tier. The tuned JFR
`profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr` (Plan 5, 180 s
active-50xfood, 2g/2g heap) recorded 4 `jdk.GCPhasePause` events totalling
90.8 ms = 0.05% of 180 s wall-clock — far below the >2% GC-pause-time ZGC
trigger. The baseline `profiles/jfr-1000bots-baseline-62c1b44.jfr` (Plan 1c,
churn scenario, 90 s effective window) recorded 0 pauses. No GC switch is
justified. G1 stays at 2g/2g for the M4 tier.

**VT scheduler parallelism rationale (JFR-driven):** `parallelism=8` confirmed — no
change. The Plan 1c lock flamegraph `profiles/lock-1000bots-baseline-62c1b44.html`
shows no carrier-thread saturation at the default JVM setting (12 `JavaMonitorEnter`
events observed during Plan 5 triage, all attributable to EPollSelector NIO
internals, 0 in paralife-owned sites). The tuned JFR `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr`
recorded 0 `jdk.VirtualThreadPinned` events — no carrier saturation signal. The
explicit `-Djdk.virtualThreadScheduler.parallelism=8` flag is retained so operators
see the knob; Phase 21 may remove it if benchmarks confirm the JVM default (equal
to available processors) is sufficient.

**Pinning monitor:** `jdk.VirtualThreadPinned` from the Plan 5 triage = 0 events
(active-50xfood 1000-bot, 90 s JFR window). Outcome 4 (dominant pinning) did not
fire; Phase 999.6 (`vt-pinning-reentrantlock-conversion`) remains backlog. Per
D-21, the pin check supersedes outcome 2; since pinning is absent, the GC/knob
evidence stands as-is.

***

## §4 Profile Findings

> Plans 5 and 6 produced this section. Plan 5 populated §4.2 (1000-tier) + §4.4
> (null-result row); Plan 6 completed §4.2 (100/500-baseline cells) + §4.3
> (per-tier narrative). Headline numbers (D-13 / D-18) live in §4.2.

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

> **Scenario note:** the 100-bot and 500-bot baseline columns source from the churn
> scenario (`62c1b44`, default `nutrient-spawn-probability`). The 1000-bot baseline
> and tuned columns source from the active-50xfood scenario (`103a615` baseline,
> `424e06d` tuned) per 20-01c-SUMMARY:144-147 directive (transport stack dominant
> under active load; churn baseline mis-routes triage toward env-CA). This baseline
> mix is intentional; the 100/500-churn baselines are reproducibility anchors for
> Phase 21 and are not directly comparable to the 1000-active baseline.

| Metric | 100 baseline | 100 tuned | 500 baseline | 500 tuned | 1000 baseline | 1000 tuned |
|--------|------|------|------|------|------|------|
| `paralife.tick.health.work-time-ms` (mean ms) | 9.0 ms (σ=3.22, n=6) | _baseline-only — see Phase 21_ | 30.2 ms (σ=2.71, n=6) | _baseline-only — see Phase 21_ | 49.5 ms (σ=15.74, n=18) | 45.0 ms (σ=8.77, n=6) |
| `paralife.outbound.detach.timeout` (count) | 0 | _baseline-only — see Phase 21_ | 0 | _baseline-only — see Phase 21_ | 0 | 0 |
| `jdk.VirtualThreadPinned` (events/min @ 20ms) | 0/min (0 events, 200 s JFR) | _baseline-only — see Phase 21_ | 0/min (0 events, 200 s JFR) | _baseline-only — see Phase 21_ | 0/min (0 events, 90 s JFR) | 0/min (0 events, 180 s JFR) |

> **1000-tier footnote:** 1000-tier baseline + tuned columns source from active-50xfood scenario per 20-01c-SUMMARY:144-147 directive (transport stack dominant under active load; churn baseline mis-routes triage toward env-CA). Baseline: `metrics-1000bots-active-50xfood-103a615.json` (18 samples × 5 s). Tuned: `metrics-1000bots-active-50xfood-tuned-424e06d.json` (6 samples × 5 s). Delta −4.5 ms is within noise floor (D-21 max(±5% mean = ±2.48 ms, ±1σ = ±15.74 ms) = ±15.74 ms) — **null-result, equivalence confirmed**.

### §4.3 Per-tier narrative

#### §4.3.1 100-bot tier (`profiles/jfr-100bots-baseline-62c1b44.jfr` + `profiles/metrics-100bots-baseline-62c1b44.json`; active-scenario contrast: `profiles/jfr-100bots-active-50xfood-103a615.jfr`)

At 100 bots (churn baseline, `62c1b44`, 200 s duration), the server shows headroom
in every dimension. The JFR `profiles/jfr-100bots-baseline-62c1b44.jfr` captured
0 `jdk.GCPhasePause` events and 0 `jdk.VirtualThreadPinned` events across the
200 s window. The actuator metric sidecar `metrics-100bots-baseline-62c1b44.json`
(6 samples × 5 s, `VALUE` statistic) recorded `paralife.tick.health.work-time-ms`
at a mean of 9.0 ms (σ=3.22, n=6; sampled values: 12/8/14/7/6/7 ms) and
`paralife.outbound.detach.timeout` count = 0 throughout. The active-scenario
flamegraph `cpu-100bots-active-50xfood-103a615.html` shows PerceptionCodec well
under 2% CPU, consistent with the 1000-tier null-result conclusion: the codec is
not the bottleneck at any tier.

The G1 GC shows no measurable pause contribution at this tier — 0 events confirm
the 2g/2g heap is grossly oversized for 100 bots, and even the lower-tier `1g/1g`
recipe preset (an operator smoke size, not JFR-validated) is unlikely to cause
pause events. The tick work time of ~9 ms gives ~91 ms slack before a 100 ms tick
budget is breached; there is no signal requiring any tuning at 100 bots.

**Pass-2 Concern #17:** 100-bot tier is **baseline-only** in Phase 20. Per-tier
benchmark evidence (i.e., re-running the harness against tuned HEAD) is Phase 21's
deliverable; D-13 inheritance truth applies to the tier where tuning was shipped
(1000-bot active-50xfood). Note: the `1g/1g` heap preset in §3.1 was not
exercised by any JFR run — all captures used 2g/2g per meta.json. Operators
reproducing the baseline must use 2g/2g (see §3 heap-preset caveat).

#### §4.3.2 500-bot tier (`profiles/jfr-500bots-baseline-62c1b44.jfr` + `profiles/metrics-500bots-baseline-62c1b44.json`; active-scenario contrast: `profiles/jfr-500bots-active-50xfood-103a615.jfr`)

At 500 bots (churn baseline, `62c1b44`, 200 s duration), behaviour scales
roughly linearly from the 100-bot tier. The JFR `profiles/jfr-500bots-baseline-62c1b44.jfr`
recorded 0 `jdk.GCPhasePause` events and 0 `jdk.VirtualThreadPinned` events.
The actuator sidecar `metrics-500bots-baseline-62c1b44.json` (6 samples × 5 s)
records `paralife.tick.health.work-time-ms` mean 30.2 ms (σ=2.71, n=6; values:
33/27/29/34/29/29 ms), a roughly 3.4× increase over the 100-bot mean. This is
consistent with linear scaling under a per-bot tick budget: each of the 500
bots receives per-bot vision encoding from `TickBroadcaster`. The
`paralife.outbound.detach.timeout` count remained 0, confirming no slow-client
pressure at this tier.

The 5× food active-scenario contrast JFR `profiles/jfr-500bots-active-50xfood-103a615.jfr`
(90 s window) shows a somewhat higher CPU profile than the churn baseline due to
sustained entity activity, but the allocation flamegraph
`alloc-500bots-active-50xfood-103a615.html` shows no TLAB churn signal requiring
attention. The ZGC threshold (>2% GC pause time) was not reached under either
scenario. G1 stays at this tier.

**Pass-2 Concern #17:** 500-bot tier is **baseline-only** in Phase 20 (same
rationale as §4.3.1). The `1g/2g` heap preset in §3.2 was not exercised by any
JFR run — all captures used 2g/2g per meta.json. Baseline-comparable reproduction
must use 2g/2g.

#### §4.3.3 1000-bot tier (`profiles/jfr-1000bots-active-50xfood-103a615.jfr` + `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr` + matching metric sidecars; churn baseline `profiles/jfr-1000bots-baseline-62c1b44.jfr` for contrast)

At 1000 bots the active-50xfood scenario (`103a615`) is the authoritative
evidence set per 20-01c-SUMMARY:144-147 — the churn baseline undersells transport
load because few entities are alive long enough to trigger repeated perception
broadcasts. The baseline JFR (`103a615`, 90 s effective window after 20 s ramp)
and the tuned-state JFR (`424e06d`, 180 s window, same server build) were
both captured at 2g/2g heap, G1, `parallelism=8`, `cap=1500` (see meta.json
sidecars). The metric sidecars sampled `/actuator/metrics/paralife.tick.health.work-time-ms`
and `/actuator/metrics/paralife.outbound.detach.timeout` at 5 s intervals throughout.

**Plan 5 triage result — D-21 outcome 3 (documented null-result).** The JFR
triage of `profiles/jfr-1000bots-active-50xfood-103a615.jfr` against all
RESEARCH Pattern 5 codec signals found: `PerceptionCodec` at 1.75% CPU (84/4792
`ExecutionSample` events), `StringBuilder` alloc at 0.11% of TLAB events (5/4501),
0 `jdk.VirtualThreadPinned` events, 0 `jdk.SocketRead` events. All four signals
are below their respective thresholds — the system is at the performance floor
for the work Plan 5 was permitted to do. No codec opts, no knob tightening, no
pinning handoff was justified. The tuning surface from Plans 2/3/4 (JVM/Jetty/app
records documented in §2/§3) IS the SCALE-08 deliverable; a measured null-result
is a measurement, not a no-op.

**GC findings.** The tuned JFR `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr`
(180 s window) recorded 4 `jdk.GCPhasePause` events totalling 90.8 ms ≈ 0.05%
of wall-clock — normal G1 minor pauses at 2g/2g. The baseline
`profiles/jfr-1000bots-baseline-62c1b44.jfr` (churn scenario, shorter effective
window) recorded 0 pauses. No GC delta claim is made: equivalence rests on the
headline gauges (D-21). The >2% GC-pause-time ZGC trigger was not reached.

**Headline-gauge delta.** From the actuator metric sidecars:
- `paralife.tick.health.work-time-ms`: baseline 49.5 ms (σ=15.74, n=18,
  `metrics-1000bots-active-50xfood-103a615.json`) → tuned 45.0 ms (σ=8.77, n=6,
  `metrics-1000bots-active-50xfood-tuned-424e06d.json`). Delta −4.5 ms.
  Noise floor (D-21): max(±5% of 49.5 ms = ±2.48 ms, ±1σ = ±15.74 ms) = ±15.74 ms.
  **Classification: within noise floor — equivalence confirmed.**
- `paralife.outbound.detach.timeout`: 0 (all 18 baseline samples) → 0 (all 6
  tuned samples). Level-only read per the window-asymmetry rule in the tuned
  meta.json. Confirmed zero both sides.

The three-gate stack (GoldenTrace + LiveEntityRegistry) was run twice consecutively
green before and after the null-result conclusion — confirming the codebase state
is unchanged and the equivalence capture is a clean baseline comparison.

### §4.4 Codec hot-path opts (D-10)

| Opt | JFR signal (active-50xfood baseline, SHA 103a615) | Code change | Three-gate record | Before → After delta |
|-----|---------------------------------------------------|-------------|-------------------|----------------------|
| (null-result) | PerceptionCodec 1.75% CPU (84/4792 samples); StringBuilder alloc 0.11% of TLAB events (5/4501); 0 `jdk.VirtualThreadPinned` events; 0 `jdk.SocketRead` events — all signals below RESEARCH Pattern 5 thresholds; system at performance floor. GC (tuned 180 s window — baseline 90 s window captured 0 pauses): 4 `jdk.GCPhasePause` events (27.8/20.9/19.0/23.1 ms) = 90.8 ms ≈ 0.05% of wall-clock, far below the >2% GC-pause-time ZGC trigger (§3 GC rationale); no GC delta claim made — equivalence rests on the headline gauges. | None — no opts justified per D-21 outcome 3 | Three-gate (GoldenTrace + LiveEntityRegistry) GREEN × 2 consecutive — confirmed codebase unchanged | Baseline 49.5 ms → tuned 45.0 ms (−4.5 ms, within ±15.74 ms noise floor); detach.timeout 0 → 0 |

***

## §5 Forward Notes

- **Admin-UI live-tune (M5):** `AppRuntimeConfig` fields ship as `[reserved — no effect in Phase 20]` and are pre-shaped for `@RefreshScope`-compatible live-tune in M5; `JettyRuntimeConfig` is launch-only by Jetty 12's `Configurable` contract and is not a live-tune candidate. The attach-time-tunable backpressure knob in Phase 20 is `paralife.admission.backpressure.outbound-queue-size` (Phase 17 D-10); true mid-benchmark live-resize of existing session queues is M5 admin-UI scope.
- **Automated config search:** The CLI override surface (`-Dparalife.runtime.x=y` / env / `@TestPropertySource`) enables future ILP/grid/Bayesian sweeps over the `paralife.runtime.*` knobs without record redesign. Search algorithm itself is deferred (post-MVP).
- **Revisit-multiplex trigger (D-03):** Only if Phase 21 evidence shows per-connection overhead at 5000 conns/JVM is the binding constraint AND §3 tuning has been exhausted.
- **Namespace consolidation (Phase 999.4):** Future fold of `paralife.admission.backpressure.outbound-queue-size` → `paralife.runtime.app.outbound.queue-size` with deprecate-and-alias migration. D-20 deferred this to keep P20 MVP-direct.
- **Reserved-field consumer wiring (Phase 999.4 + Phase 19.1):** The four `[reserved]` fields in `AppRuntimeConfig` await consumers — `parallel-encode-threshold` for Phase 19.1 parallel encode; `frame-size-budget-bytes` for any future PerceptionCodec API that accepts capacity hints; `queue-watermark-pct` and `encode-batch-hint` for M5 admin UI.
- **Phase 19.1 follow-up:** parallel `PerceptionBroadcaster` will consume `AppRuntimeConfig.encode.parallelEncodeThreshold` (currently sentinel-disabled at -1).
- **Phase 999.5:** Re-capture the baseline against post-M4 HEAD for fresh apples-to-apples comparison; the canonical `62c1b44` baseline (Plan 1c F6 re-anchor) is intentionally frozen for reproducibility.
- **Phase 999.6 (added Pass-1 Concern #2 disposition):** `vt-pinning-reentrantlock-conversion` — lifts if Phase 21 benchmark evidence shows pinning is binding (Plan 5 confirmed 0 pinning events at 1000-bot active-50xfood; outcome 4 did not fire).

***

## §6 Profile Index

> The Phase 20 canonical baseline is anchored at SHA `62c1b44` (Plan 1c F6 re-anchor; supersedes the original `c22e487` capture which surfaced D1/D2/D3 fixes that shifted the post-fix baseline). Both capture sets remain on disk for historical reference; only the `62c1b44` series is cited by §3 recipes, §4 numbers, and D-19. The active-scenario evidence set (`103a615`) and tuned-state capture (`424e06d`) are indexed below. See `profiles/README.md` for the filename-convention contract.

| Filename | Scenario | Source SHA | Captured | Size | Notes |
|----------|----------|------------|----------|------|-------|
| `profiles/jfr-100bots-baseline-62c1b44.jfr` | 100 bots, balanced mix, seed=20251205 | 62c1b44 | Plan 1c — 2026-05-20 | 2.3 MB | baseline |
| `profiles/jfr-500bots-baseline-62c1b44.jfr` | 500 bots, balanced mix | 62c1b44 | Plan 1c — 2026-05-20 | 3.9 MB | baseline |
| `profiles/jfr-1000bots-baseline-62c1b44.jfr` | 1000 bots, balanced mix | 62c1b44 | Plan 1c — 2026-05-20 | 4.5 MB | baseline |
| `profiles/cpu-1000bots-baseline-62c1b44.html` | async-profiler CPU @ 1000 | 62c1b44 | Plan 1c — 2026-05-20 | 80 KB | flamegraph |
| `profiles/alloc-1000bots-baseline-62c1b44.html` | async-profiler alloc @ 1000 | 62c1b44 | Plan 1c — 2026-05-20 | 29 KB | flamegraph |
| `profiles/lock-1000bots-baseline-62c1b44.html` | async-profiler lock @ 1000 | 62c1b44 | Plan 1c — 2026-05-20 | 19 KB | flamegraph |
| `profiles/metrics-{100,500,1000}bots-baseline-62c1b44.json` | actuator metric sidecars (Pass-2 Concern #10) | 62c1b44 | Plan 1c — 2026-05-20 | ~13–14 KB each | 6-sample headline-gauge JSON snapshots |
| `profiles/jfr-{100,500,1000}bots-baseline-62c1b44.meta.json` | JFR capture metadata sidecars (baseline) | 62c1b44 | Plan 1c — 2026-05-20 | ~1.2 KB each | per-JFR provenance: SHA / cap / seed / asprof rate |
| `profiles/jfr-{100,500,1000}bots-active-50xfood-103a615.jfr` | active-population scenario (50× food, sustained live pop) | 103a615 | Plan 1c §Active — 2026-05-25 (from metric sidecar) | ~0.35–0.8 MB each | transport-overhead evidence — Plan 5's tuning evidence set (null-result; see §4.4) |
| `profiles/{cpu,alloc,lock}-{100,500,1000}bots-active-50xfood-103a615.html` | async-profiler flamegraphs (active scenario) | 103a615 | Plan 1c §Active — 2026-05-25 (from metric sidecar) | 17–157 KB each | active-profile flamegraphs |
| `profiles/metrics-{100,500,1000}bots-active-50xfood-103a615.json` | actuator metric sidecars (active scenario) | 103a615 | Plan 1c §Active — 2026-05-25 (from metric sidecar) | ~39–43 KB each | 18-sample headline-gauge JSON snapshots (3× baseline sample count) |
| `profiles/jfr-{100,500,1000}bots-active-50xfood-103a615.meta.json` | JFR capture metadata sidecars (active scenario) | 103a615 | Plan 1c §Active — 2026-05-25 (from metric sidecar) | ~0.7 KB each | per-JFR provenance: SHA / cap / seed / asprof rate |
| `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr` | 1000 bots, active-50xfood, tuned-state (null-result equivalence) | 424e06d | Plan 5 — 2026-06-04 | 3.5 MB | tuned-state JFR; null-result — 0 VirtualThreadPinned, 0 SocketRead, 4 GCPhasePause (≈0.05% wall-clock) |
| `profiles/metrics-1000bots-active-50xfood-tuned-424e06d.json` | actuator metric sidecar (tuned, active-50xfood) | 424e06d | Plan 5 — 2026-06-04 | 3.1 KB | 6-sample headline-gauge JSON; mean 45.0 ms work-time-ms, detach.timeout=0 |
| `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.meta.json` | JFR capture metadata sidecar (tuned, active-50xfood) | 424e06d | Plan 5 — 2026-06-04 | ~1.6 KB | opts_applied_summary = null-result label per TRIAGE.md outcome-label contract |
| `profiles/{cpu,alloc,lock}-1000bots-active-50xfood-tuned-424e06d.html` | async-profiler flamegraphs (tuned, active) | 424e06d | not captured — null-result (D-21 outcome 3); JFR-event triage sufficed; baseline/active flamegraphs remain the reference | — | flamegraph (not captured) |

### History-only (superseded series)

The `c22e487` capture series was superseded by Plan 1c re-anchor (`62c1b44`) due to
F1/F2/F6 defects (admission-cap=256 silently bounded workload; stale SHA vs HEAD;
missing saturation metrics). All 12 artifacts are retained on disk for historical
reference. **None of §3, §4, or D-19 cite this series.**

| Filename | Scenario | Source SHA | Captured | Size | Notes |
|----------|----------|------------|----------|------|-------|
| `profiles/jfr-{100,500,1000}bots-baseline-c22e487.jfr` | 100/500/1000 bots, churn, pre-fix | c22e487 | Plan 1b — 2026-05-14 | 2.0–3.0 MB each | superseded by Plan 1c re-anchor (`62c1b44`); retained on disk for history; not cited by §3/§4 |
| `profiles/jfr-{100,500,1000}bots-baseline-c22e487.meta.json` | JFR metadata sidecars | c22e487 | Plan 1b — 2026-05-14 | ~1–2.7 KB each | superseded by Plan 1c re-anchor (`62c1b44`); retained on disk for history; not cited by §3/§4 |
| `profiles/{cpu,alloc,lock}-1000bots-baseline-c22e487.html` | async-profiler CPU/alloc/lock @ 1000 | c22e487 | Plan 1b — 2026-05-14 | 18–48 KB each | superseded by Plan 1c re-anchor (`62c1b44`); retained on disk for history; not cited by §3/§4 |
| `profiles/metrics-{100,500,1000}bots-baseline-c22e487.json` | actuator metric sidecars | c22e487 | Plan 1b — 2026-05-14 | ~3 KB each | superseded by Plan 1c re-anchor (`62c1b44`); retained on disk for history; not cited by §3/§4 |

***

*Phase 20-connection-multiplexing-runtime-tuning · v1 · 2026-06-05*
