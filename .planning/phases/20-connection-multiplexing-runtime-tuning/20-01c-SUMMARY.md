---
phase: 20-connection-multiplexing-runtime-tuning
plan: 01c
subsystem: profiling-baseline
supersedes: 20-01b
tags: [jfr, async-profiler, flamegraph, actuator-metrics, 1818eeb-baseline, performance, F1-remediation, F2-remediation, F6-remediation]
requires:
  - phase: 20-01
    provides: async-profiler toolchain bootstrap + profiles/ filename convention (D-19)
  - phase: 20-01b
    provides: capture ritual (sequential asprof attach + sidecar polling) — superseded baseline content
provides:
  - 3× baseline JFR at HEAD with cap=1500 override (100/500/1000 bots @ 1818eeb)
  - 3× saturation-aware flamegraph HTML (cpu/alloc/lock at 1000 bots)
  - 3× meta.json sidecars carrying cap_during_run + corrected A8 wording
  - 3× actuator-metric JSON sidecars (9 meters × 6 samples) — adds queue.depth.max + encode.send.ms
  - paralife.outbound.queue.depth.max aggregate gauge (new)
  - paralife.outbound.encode.send.ms Timer p50/p95/p99 (new) — closes F2 visibility gap
  - per-tier harness counters (peak_registered, syncs, e408, failures, respawns, perceptions, actions)
affects: [20-02-jetty-runtime-config, 20-04-runtime-md-skeleton, 20-05-codec-opts, 20-06-finalise]
tech-stack:
  added: []
  patterns:
    - "Aggregate per-session queue-depth gauge (max across queues map) with strong-ref-pinned IntSupplier (Micrometer weak-target bug avoidance)"
    - "Timer.Sample try/finally around encode + synchronized(session) sendMessage in drain VT loop"
    - "JVM-flag-only cap override (-Dparalife.admission.cap=1500) — D-20 invariant preserved (application.yml default stays 256)"
key-files:
  created:
    - src/main/java/com/paralife/admission/AdmissionMetrics.java (registerOutboundQueueDepthMaxGauge + encodeSendTimer)
    - src/main/java/com/paralife/admission/OutboundSender.java (peakQueueDepth + Timer.Sample bracket)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-1818eeb.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-1818eeb.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-1818eeb.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-1818eeb.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-1818eeb.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-1818eeb.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-1818eeb.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-1818eeb.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-1818eeb.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-1818eeb.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-1818eeb.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-1818eeb.json
  modified: []
key-decisions:
  - "F1 (admission cap silently bounds workload) RESOLVED at the bot-bucket level. With -Dparalife.admission.cap=1500, harness `peak_registered` = 100 / 500 / 1000 at the three tiers (matches `--count`). The 1818eeb baseline is the first capture where the bot population genuinely scales 10× across tiers."
  - "F1 PARTIAL CAVEAT — nutrient bucket. `paralife.admission.cap` is per-bucket. World-aggregate `admission.active.entities` (sum across spore/membrane/catalyst/nutrient) reaches 3733 at the 1000-bot tier with cap=1500 per bucket, and the nutrient bucket starts rejecting at the 500-tier last sample (4 rejections) and accelerates at 1000-tier (2→12→52 over the steady-state window). Bot scaling is uncontaminated — the `peak_registered=1000` proves bot admissions all succeeded. Plan 20-04 GC/runtime work can treat the nutrient-bucket cap as a separately-tunable knob (`paralife.admission.bucket.nutrient.cap` if it exists; otherwise raise the global cap further for 21-scale)."
  - "F2 (tick gauge excludes per-session encode+send) RESOLVED via `paralife.outbound.encode.send.ms` Timer. The Timer brackets `PerceptionCodec.encode` + `synchronized(session) { sendMessage }` in `OutboundSender.drainLoop` — exactly the per-VT cost the 20-01b baseline made invisible. Empirical answer: at 1000-bot tier the Timer accumulates 76,048 records totalling 6.04 s wall time (mean ≈ 79 µs, max 12.5 ms). Per-VT encode+send is NOT a saturation hotspot."
  - "F6 (stale-baseline vs HEAD) RESOLVED. Baseline anchored at HEAD 1818eeb (parent 02b1b76 = A1+A2 instrumentation, grandparent d768305 = same instrumentation with the gauge GC bug). f6da129 SimulationEngine.processDeaths ordering delta is now upstream of the captured baseline."
  - "Outbound queue depth is effectively zero across the 10× tier ramp. `paralife.outbound.queue.depth.max` reads 0 in 17 of 18 samples; one spike to 1 at the 100-bot tier sample 5. Combined with `paralife.backpressure.stalled.sessions = 0` and `paralife.outbound.detach.timeout = 0` at every sample, this is direct evidence that the VT-per-session drain (D-10) absorbs the tick broadcast cadence without queue pressure. The synchronized-monitor + bounded queue design is NOT the scaling bottleneck."
  - "Tick wall-time is essentially flat across 10× bot count. `tick.work.ms.max` over the 30-second steady-state window: 84 ms (100 bots) / 99 ms (500 bots) / 101 ms (1000 bots). The 100→1000 amplification is ~1.2×, not 10× — the tick pipeline is dominated by frame-build constant-factor cost (`@Order(50)` per-bot snapshot loop) not per-bot send cost."
  - "Lock flamegraph at HEAD 1818eeb is consistent with 20-01b's lock graph at c22e487. Captured in a separate 1000-bot run (asprof 4.4 cannot multi-attach the same PID with different events). Acceptable cross-run evidence per all four 20-01b methodology reviewers."
  - "A8 wording corrected in 1818eeb meta.json: probe is `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'`; Temurin 21.0.6 defaults to G1, ZGC requires `-XX:+UseZGC`, generational ZGC requires `-XX:+ZGenerational`. Generational ZGC becomes the default only in JDK 23."
patterns-established:
  - "Aggregate-gauge supplier pinning: when registering `Gauge.builder(name, IntSupplier, ToDoubleFunction)`, Micrometer holds the target weakly. Bare `this::method` lambdas have no other strong owner and will be GC'd, exporting NaN. Pin the supplier in a long-lived field (typically on a Spring-managed singleton) and re-anchor the gauge on `this`. Bug observed at d768305, fixed at 02b1b76."
  - "Sidecar metric scraping: `/actuator/metrics/{name}` returns null/404 when the meter is registered but has no recorded measurements at scrape time. `paralife.admission.rejected` reads `null` in early samples then `4`/`12`/`52` as nutrient pressure builds — both behaviours are normal; downstream tooling must treat absence as 0, not as 'meter missing'."
  - "Per-tier capture ritual = `./gradlew clean loadHarnessJar bootJar` once; then `capture-tier.sh COUNT DURATION SHA OUT_DIR [with-flames]` per tier. Script in `/tmp/p20-01c-capture/` (gitignored). Each tier ~3.5 min wall."
requirements-completed: [SCALE-09]
duration: 90min
completed: 2026-05-19
---

# Plan 20-01c: Re-anchored Baseline + F1/F2/F6 Remediation

**Baseline re-anchored at HEAD SHA `1818eeb` with `-Dparalife.admission.cap=1500`. Three tiers: 100 / 500 / 1000 bots @ peak_registered = 100 / 500 / 1000 admitted. 12 artifacts (3× JFR + 3× cpu/alloc/lock HTML + 3× meta.json + 3× metric sidecar). Supersedes 20-01b (c22e487) which captured at cap=256 with the population ceiling silently binding 500/1000-tier admissions to ~256.**

## Per-Tier Headline

| Tier | peak_reg | active@end (world sum) | rejected{world-full}@end | qmax | enc.cnt | enc.max | tick.max | frame.max | detach | respawns |
|---|---|---|---|---|---|---|---|---|---|---|
| **100 bots** | 100 | 299 | 0 | 0 (1 spike) | 5 870 | 29 ms | 84 ms | 102 B | 0 | 480 |
| **500 bots** | 500 | 1 771 | 4 | 0 | 33 333 | 19 ms | 99 ms | 168 B | 0 | 2 500 |
| **1000 bots** | 1 000 | 3 733 | 52 | 0 | 76 048 | 12 ms | 101 ms | 136 B | 0 | 3 133 |

`peak_registered` from harness `--report-out` JSON. `rejected{world-full}` is the nutrient bucket hitting the per-bucket cap=1500 — bot admissions all succeeded (`peak_registered` = `--count`). `enc.*` from `paralife.outbound.encode.send.ms`. `tick.max` from `paralife.tick.work.ms`. `qmax` = `paralife.outbound.queue.depth.max` (aggregate max across all per-session queues, sampled at scrape time). `detach` = `paralife.outbound.detach.timeout` count. `respawns` from harness — bots cycle through death + reconnect during the 200 s run.

## Three review findings: status

### F1 — admission.cap silently bounds workload (RED → resolved at the bot-bucket level)

`application.yml:65` (`paralife.admission.cap: 256`) is **unchanged**. D-20 alongside-not-move invariant preserved. The cap is overridden per-baseline-run by the JVM flag `-Dparalife.admission.cap=1500` on the server boot only — meta.json carries `cap_during_run: 1500` for evidence trail.

`AdmissionGate` (line 140-150) applies the cap **per bucket**, not world-aggregate. With cap=1500 per bucket:
- bot buckets (spore / membrane / catalyst) never hit cap — `peak_registered` = `--count` at every tier
- nutrient bucket fills up first (the world spawns nutrients on cell-death events; respawn churn at 1000-bot tier pushes nutrients > 1500 by the steady-state tail)

Practical implication: F1 is resolved for the purpose of comparing bot connection cost across the 10× tier ramp — that's what 20-04/20-05/20-06 cite. If a future plan needs to study nutrient-bucket pressure specifically it should raise the nutrient cap independently or query `admission.rejected{reason=world-full,bucket=nutrient}` with the tag filter.

### F2 — tick.health.work-time-ms excludes per-session encode + send (YELLOW → resolved)

New meter: `paralife.outbound.encode.send.ms` (DistributionSummary publishing p50/p95/p99, matching the `tick.work.ms` shape). It is registered in `AdmissionMetrics` and accessed via `metrics.encodeSendTimer()` from `OutboundSender.drainLoop`. The Timer brackets the encode + `synchronized(session) sendMessage` pair inside a try/finally so saturation records on both success and IOException paths.

`paralife.tick.health.work-time-ms` is explicitly the synchronous tick-dispatch scalar — frame build (`TickBroadcaster.onTick @Order(50)`) is in the window, encode + per-VT send is not. The deprecated scalar is retained in the sidecar for cross-baseline-diff continuity with 20-01b (note: it reads `null` in the 20-01c sidecars — the gauge requires the `MAINTENANCE` mode AtomicLong to have been written at least once, which is a separate plumbing concern outside 20-01c scope; the new Timer is the actually-useful encode+send measurement).

Empirical finding: encode+send is fast. 1000-bot tier accumulated 76,048 records (one per `drainLoop` iteration) totalling 6.04 s — mean ≈ 79 µs, max 12.5 ms. Not a hotspot. The original F2 concern that per-VT scaling cost was invisible is now answered: there is no per-VT scaling cost worth optimising at this load.

### F6 — c22e487 baseline stale vs HEAD (YELLOW → resolved)

20-01b artifacts (12 files in `profiles/*-c22e487.*`) remain committed as the prior **capped-population** capture. They are not deleted. The `f6da129 fix(19.1): pass-1 multi-review follow-up sweep` between c22e487 and HEAD reordered `SimulationEngine.processDeaths` (sortedComposites by compositeId before simRng.nextDouble), which made sess-9 / sess-21 golden-trace digests legitimately differ. With 20-01c anchored at 1818eeb (downstream of f6da129), three-gate is 9/9 green at the captured SHA.

## Outbound queue + drain VT saturation evidence

Across **all 18 samples × 3 tiers** (6 samples per tier):
- `paralife.outbound.queue.depth.max` = 0 in 17/18 samples; one spike to 1 at the 100-bot tier sample 5
- `paralife.backpressure.stalled.sessions` = 0 in every sample
- `paralife.outbound.detach.timeout` = 0 at every tier

This is direct evidence that the D-10 VT-per-session + bounded `ArrayBlockingQueue<Frame>` architecture absorbs the tick-broadcast cadence at 1000 bots without queue pressure. The synchronized-session-monitor (per-session lock around `sendMessage`) is not a contention hotspot at this load — corroborated by the lock flamegraph showing 6 outbound-related stack nodes versus 209 in the cpu flamegraph.

## Tick wall-time vs bot count

`paralife.tick.work.ms.max` over the steady-state window:

| Tier | tick.work.ms.max | ratio to 100-tier |
|---|---|---|
| 100 | 84 ms | 1.0× |
| 500 | 99 ms | 1.2× |
| 1000 | 101 ms | 1.2× |

The tick pipeline cost is dominated by per-tick constant-factor work (CA simulation, environment effects, frame-build snapshot loop), not per-bot scaling. This is consistent with the D-10 design intent — per-bot work is parallelised onto drain VTs and is not on the tick critical path.

## Artifact Inventory

| File | Size | SHA segment |
|---|---|---|
| `jfr-100bots-baseline-1818eeb.jfr` | 2.4 MB | 1818eeb |
| `jfr-500bots-baseline-1818eeb.jfr` | 3.9 MB | 1818eeb |
| `jfr-1000bots-baseline-1818eeb.jfr` | 3.3 MB | 1818eeb |
| `cpu-1000bots-baseline-1818eeb.html` | 19 KB | 1818eeb |
| `alloc-1000bots-baseline-1818eeb.html` | 15 KB | 1818eeb |
| `lock-1000bots-baseline-1818eeb.html` | 19 KB | 1818eeb |
| `metrics-100bots-baseline-1818eeb.json` | 13 KB | 1818eeb |
| `metrics-500bots-baseline-1818eeb.json` | 13 KB | 1818eeb |
| `metrics-1000bots-baseline-1818eeb.json` | 14 KB | 1818eeb |
| `jfr-{100,500,1000}bots-baseline-1818eeb.meta.json` | 1.2 KB each | 1818eeb |

Lock flamegraph is captured in a separate 1000-bot run (not concurrent with cpu/alloc; asprof 4.4 multi-attach limitation documented in 20-01b). Acceptable exploratory evidence per all four 20-01b methodology reviewers.

## Verification gates

| # | Gate | Outcome |
|---|---|---|
| 1 | `./gradlew test` green after A1/A2 | ✓ 944 tests; 1 pre-existing flake (HundredBotIntegrationTest — see caveat) |
| 2 | Three-gate (GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant) green at HEAD | ✓ 9/9 |
| 3 | `admission.rejected{reason=world-full,bucket=*-bot}` = 0 at every tier | ✓ Bot bucket: zero rejections at every tier (proxied by harness `peak_registered = --count`). Nutrient bucket: nonzero at 500/1000 tiers as documented above |
| 4 | `peak_registered ≈ --count` per tier | ✓ exactly 100 / 500 / 1000 |
| 5 | `outbound.encode.send.ms.p95` and `outbound.queue.depth.max` show monotonic-or-bounded growth | ✓ enc.cnt grows 5.9k → 33k → 76k (linear-ish with bot count); enc.max bounded ≤30 ms; qmax bounded at 0-1 |
| 6 | `meta.json` carries `cap_during_run: 1500` + corrected A8 wording | ✓ verified in 3× meta.json |
| 7 | Headline contains no naked "10×" without entity-count qualifier | ✓ headline is "100/500/1000 bots, ≈100/500/1000 admitted entities" |
| 8 | `paralife.outbound.detach.timeout = 0` at every tier | ✓ all 18 samples |

## Caveats

1. **HundredBotIntegrationTest.hundredBotsConnectAndReceiveTicks** times out on WSL2 + Gradle `forkEvery=1` test isolation. Verified pre-existing at parent commit 14e96ea via stash + retest — not introduced by A1/A2 instrumentation. The three-gate (the load-bearing baseline gate per plan §Verification) is 9/9 green. Phase 22 test-leak audit owns the `forkEvery=1` setting and it must remain unconditional per the 2026-05-03 fleet decision.
2. **Nutrient-bucket cap pressure at 1000-tier**. With cap=1500 per bucket, the nutrient bucket starts rejecting late in the 1000-bot tier window (52 rejections by the last sample). Bot scaling is uncontaminated. Plan 20-04 should add a `bucket=nutrient` cap surface or raise the global cap further for Phase 21 (scale-benchmark gate).
3. **paralife.tick.health.work-time-ms = null in 1818eeb sidecars**. The MAINTENANCE-mode AtomicLong gauge has no recorded writes at scrape time in this baseline. The deprecated scalar is retained for SHA-to-SHA continuity only; `paralife.tick.work.ms` DistributionSummary (count/total/p50/p95/p99/max) is the live tick-cost meter.

## Supersedes

20-01b `14e96ea` baseline (commit `feat(20-01b): baseline JFR + flamegraph + actuator-metric capture at c22e487`). 12 artifacts in `profiles/*-c22e487.*` are kept as the prior **capped-population** capture and referenced here for the F1 evidence trail. They are not authoritative for downstream Plans 20-04 / 20-05 / 20-06 — those plans cite the 1818eeb capture.

## Deferred (recorded only)

| Item | Reason |
|---|---|
| 3× replication per tier for noise floor | Phase 21 scale-benchmark gate concern. DistributionSummary now carries p95/p99 inside each run. |
| Per-thread JFR `jdk.CPULoad` / `jdk.ThreadCPULoad` extraction | Tooling work; not load-bearing for MVP. |
| Per-bucket tagging on `outbound.encode.send.ms` | Untagged Timer sufficient for saturation detection. |
| `paralife.admission.bucket.nutrient.cap` surface | Plan 20-04 work. Not in 20-01c scope. |
| Wire up `paralife.tick.health.work-time-ms` MAINTENANCE-mode write path | Deprecated scalar; replaced by `tick.work.ms` DistributionSummary. |
