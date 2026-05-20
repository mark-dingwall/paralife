---
phase: 20-connection-multiplexing-runtime-tuning
plan: 01c
subsystem: profiling-baseline
supersedes: 20-01b
tags: [jfr, async-profiler, flamegraph, actuator-metrics, 0824f1a-baseline, performance, F1-remediation, F2-remediation, F6-remediation, multi-review-remediation]
requires:
  - phase: 20-01
    provides: async-profiler toolchain bootstrap + profiles/ filename convention (D-19)
  - phase: 20-01b
    provides: capture ritual (sequential asprof attach + sidecar polling) — superseded baseline content
provides:
  - 3× baseline JFR at HEAD with cap=1500 override (100/500/1000 bots @ 0824f1a)
  - 3× saturation-aware flamegraph HTML (cpu/alloc/lock at 1000 bots)
  - 3× meta.json sidecars carrying cap_during_run + asprof sample intervals + corrected A8 wording
  - 3× actuator-metric JSON sidecars (9 meters × 6 samples) — adds queue.depth.max + encode.send.ms
  - paralife.outbound.queue.depth.max aggregate gauge (new)
  - paralife.outbound.encode.send.ms Timer (count/total/max via Actuator JSON; percentiles via Prometheus scrape if needed) — closes F2 visibility gap
  - per-tier harness counters (peak_registered, syncs, e408, failures, respawns, perceptions, actions)
  - markDead now decrements active.entities bucket (pre-existing leak fix)
  - Timer.Sample.stop isolated from Micrometer exceptions
  - outbound queue-depth gauge guarded against double-register
affects: [20-02-jetty-runtime-config, 20-04-runtime-md-skeleton, 20-05-codec-opts, 20-06-finalise]
tech-stack:
  added: []
  patterns:
    - "Aggregate per-session queue-depth gauge (max across queues map) with strong-ref-pinned IntSupplier (Micrometer weak-target bug avoidance)"
    - "Timer.Sample try/finally around encode + synchronized(session) sendMessage in drain VT loop; stop() further guarded against Micrometer exceptions"
    - "JVM-flag-only cap override (-Dparalife.admission.cap=1500) — benchmark-time override; production default (application.yml:65, cap=256) unchanged"
    - "markDead lookup → dec → release sequence mirrors cleanupBot:917-935 — same AdmissionMetrics APIs, no new methods"
key-files:
  created:
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-0824f1a.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-0824f1a.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-0824f1a.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-0824f1a.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-0824f1a.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-0824f1a.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-0824f1a.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-0824f1a.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-0824f1a.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-0824f1a.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-0824f1a.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-0824f1a.json
  modified:
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (markDead now dec+releases bucket)
    - src/main/java/com/paralife/admission/OutboundSender.java (sample.stop guarded against Micrometer exceptions)
    - src/main/java/com/paralife/admission/AdmissionMetrics.java (gauge double-register guard + slf4j logger)
key-decisions:
  - "F1 (admission cap silently bounds workload) RESOLVED. The cap is **world-aggregate**, not per-bucket — AdmissionGate.java:58,140-151 uses one global AtomicInteger reservedSlots against one admissionConfig.cap(). With -Dparalife.admission.cap=1500 on server boot, the cap is non-binding for the bot population at every tier: harness peak_registered = --count at 100 / 500 / 1000, and zero world-full rejections appear in any sidecar. Nutrients are placed directly via SimulationEngine.setEntity:1431 and bypass AdmissionGate entirely, so the earlier 'nutrient bucket hits cap' narrative was an artifact of the rewrite, not a code behaviour."
  - "The rejected{respawn-cap} entries (peak 58 at 1000-tier sample 6) are Guard 6 (AdmissionGate.java:154) — bots exhausting maxRespawnsPerSession=5 across the 200 s window. This is orthogonal to F1 cap-binding and does not contaminate the bot-population scaling story. Zero rejections at 100/500-tier."
  - "F2 (tick gauge excludes per-session encode+send) RESOLVED via paralife.outbound.encode.send.ms Timer. The Timer brackets PerceptionCodec.encode + getBytes() + recordFrameSize + monitor-wait + synchronized(session) sendMessage + frame-emit listener in OutboundSender.drainLoop. This is *broader* than the original framing (which described encode+send only) — it captures monitor contention and metric-recording overhead too, which is a strict superset of the original visibility gap. Empirical answer at 1000-bot tier sample 6: enc.cnt = 76,848 records totalling 7.14 s wall time (mean ≈ 93 µs, max 22.1 ms). Per-VT encode+send is NOT a saturation hotspot."
  - "F6 (stale-baseline vs HEAD) RESOLVED. Baseline anchored at HEAD 0824f1a, the post-D1+D2+D3 fix point. f6da129 SimulationEngine.processDeaths ordering delta is upstream. Three-gate (GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant) is 9/9 green at 0824f1a."
  - "active.entities gauge leak fixed (D1). Pre-fix 1818eeb 1000-bot sidecar showed monotonic growth (1669→3733 over 30 s steady-state) because WorldWebSocketHandler.markDead removed ATTR_ENTITY_ID without decrementing the per-bucket gauge. Post-fix 0824f1a 1000-bot trajectory: 966 → 1000 → 1000 → 998 → 966 → 851 — tracks live population through churn instead of accumulating. The leak was pre-existing (Phase 18-19 era); 20-01c surfaced it by reporting the number for the first time."
  - "Outbound queue depth is at-scrape-instant zero across the 10× tier ramp. paralife.outbound.queue.depth.max reads 0 in all 18 samples × 3 tiers. Combined with paralife.backpressure.stalled.sessions = null (meter never written → no sessions ever stalled) and paralife.outbound.detach.timeout = 0 at every sample, no queue pressure observed at the 5 s scrape points. peakQueueDepth() is at-scrape-instant max (not interval-peak), so this is sample-coarse evidence — bursts between scrapes are invisible. Adequate for 'D-10 VT-per-session drain absorbs tick broadcast cadence at this scale'; insufficient for 'zero pressure between samples'."
  - "Tick wall-time is essentially flat across 10× bot count. paralife.tick.work.ms.max at last sample: 83 ms (100 bots) / 75 ms (500 bots) / 106 ms (1000 bots). The 100→1000 amplification is ~1.3×, not 10× — tick pipeline dominated by per-tick constant-factor work (CA simulation, environment effects, frame-build snapshot loop), not per-bot scaling. 6 samples × 1 run per tier is enough for 'not a hotspot at this scale'; insufficient for quantitative scaling extrapolation."
  - "Lock flamegraph at HEAD 0824f1a captured in a separate 1000-bot run (asprof 4.4 cannot multi-attach the same PID with different events). Acceptable cross-run evidence per all four 20-01b methodology reviewers."
  - "A8 wording corrected in 0824f1a meta.json: probe is `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'`; Temurin 21.0.6 defaults to G1, ZGC requires `-XX:+UseZGC`, generational ZGC requires `-XX:+ZGenerational`. Generational ZGC becomes the default only in JDK 23."
  - "asprof sample intervals now documented in meta.json (asprof_cpu_interval_us=10000, asprof_alloc_interval_bytes=524288 — asprof 4.4 defaults: 100 Hz cpu / 512 KB alloc). 19 KB cpu.html at 60 s = ~6 000 stacks, normal density at this sample rate."
patterns-established:
  - "Aggregate-gauge supplier pinning: when registering `Gauge.builder(name, IntSupplier, ToDoubleFunction)`, Micrometer holds the target weakly. Bare `this::method` lambdas have no other strong owner and will be GC'd, exporting NaN. Pin the supplier in a long-lived field (typically on a Spring-managed singleton) and re-anchor the gauge on `this`. Bug observed at d768305, fixed at 02b1b76. Now also guarded against double-register (D3) — log+ignore second caller, first supplier wins."
  - "Per-bucket gauge dec discipline: every `incActiveBucket()` callsite must be paired with a `decActiveBucketByTags(lookupBucketTags(entityId))` + `releaseBucketTags(entityId)` callsite BEFORE the session attrs (ATTR_ENTITY_ID) are cleared. cleanupBot and (post-D1) markDead follow the pattern; future lifecycle exits must adopt the same shape or the gauge accumulates."
  - "Sidecar metric scraping: `/actuator/metrics/{name}` returns null/404 when the meter is registered but has no recorded measurements at scrape time. `paralife.admission.rejected` reads null in early samples then 1/17/58 as respawn-cap pressure builds at 1000-tier — both behaviours are normal; downstream tooling must treat absence as 0, not as 'meter missing'."
  - "Per-tier capture ritual = `./gradlew clean loadHarnessJar bootJar` once; then `capture-tier.sh COUNT DURATION SHA OUT_DIR [with-flames]` per tier, then `lock-capture.sh SHA OUT_DIR` for the lock flamegraph. Scripts in `/tmp/p20-01c-capture/` (gitignored). Each tier ~3.5 min wall; full 4-capture run ~13 min."
requirements-completed: [SCALE-09]
duration: 90min
completed: 2026-05-20
---

# Plan 20-01c: Re-anchored Baseline + Multi-Review Remediation

**Baseline re-anchored at HEAD SHA `0824f1a` with `-Dparalife.admission.cap=1500`. Three tiers: 100 / 500 / 1000 connected bots, cap=1500 non-binding for the bot population, ~76 k frames captured at the 1000-tier through ~47 s of capture window. 12 artifacts (3× JFR + 3× cpu/alloc/lock HTML + 3× meta.json + 3× metric sidecar). Supersedes 20-01b (c22e487) which captured at cap=256 with the population ceiling silently binding 500/1000-tier admissions to ~256.**

Re-anchored mid-flight after multi-review (`20-01c-REVIEW-inline.md`, `20-01c-REVIEW-reference.md`) surfaced three RED findings against the original 1818eeb capture: (a) the per-bucket-cap narrative was fabricated, (b) the rejection column was mislabeled (`world-full` → actual `respawn-cap`), and (c) `active.entities` was a leaky-accumulator (pre-existing markDead bug). All three resolved at code or doc level. See §Multi-Review Remediation below.

## Per-Tier Headline (post-D1 / 0824f1a)

| Tier | peak_reg | active.entities@end (live count) | rejected{respawn-cap}@end | qmax | enc.cnt | enc.max | tick.max | frame.max | detach | respawns |
|---|---|---|---|---|---|---|---|---|---|---|
| **100 bots** | 100 | 100 | 0 | 0 | 5 810 | 8 ms | 83 ms | 95 B | 0 | 500 |
| **500 bots** | 500 | 500 | 0 | 0 | 33 489 | 9 ms | 75 ms | 104 B | 0 | 2 495 |
| **1000 bots** | 1 000 | 851 | 58 | 0 | 76 848 | 22 ms | 106 ms | 105 B | 0 | 4 996 |

`peak_registered` from harness `--report-out` JSON; `connect_failures_total` and `e408_reconnect_required_total` are 0 at every tier. `active.entities@end` is the per-bucket-gauge value at the last 5 s scrape — post-D1 it tracks live population through churn (the 1000-tier trajectory dips to 851 in the final sample as bots that hit `maxRespawnsPerSession=5` exit and are not yet replaced). `rejected{respawn-cap}` is Guard 6 — bots exhausting their respawn budget — orthogonal to cap-binding. `enc.*` columns are `MAX` from `paralife.outbound.encode.send.ms` (the Actuator JSON exposes COUNT / TOTAL_TIME / MAX; configured percentiles are emitted on `/actuator/prometheus` if needed). `tick.max` from `paralife.tick.work.ms.max`. `qmax` = `paralife.outbound.queue.depth.max` (aggregate max across all per-session queues, sampled at scrape time). `detach` = `paralife.outbound.detach.timeout` count. `respawns` from harness.

## Multi-Review Remediation

The original 1818eeb capture went through `multi-review` in both inline and reference modes (7 reviewer runs). Three substantive RED findings converged:

### F1 — admission.cap silently bounds workload (RESOLVED with corrected narrative)

The original `application.yml:65` (`paralife.admission.cap: 256`) is unchanged. The 1500 value exists only as a JVM flag (`-Dparalife.admission.cap=1500`) on the server boot for benchmark runs; production default at `application.yml:65` stays at 256. meta.json carries `cap_during_run: 1500` for evidence trail.

`AdmissionGate.java:58,140-151` uses **one** global `AtomicInteger reservedSlots` against **one** `admissionConfig.cap()` — the cap is world-aggregate, not per-bucket. The earlier 1818eeb SUMMARY framed F1 as resolved via "per-bucket cap; bot buckets never hit, nutrient bucket hits" — that narrative has no source basis. Nutrients are placed directly through `SimulationEngine.setEntity:1431` and bypass `AdmissionGate` entirely; bucket separation exists only as metric *tags* (`bucket=spore|membrane|catalyst|...`), not as admission-gate counters.

The actual mechanism producing the post-recapture rejection numbers (1/17/58 across 1000-tier samples 4–6) is Guard 6 at `AdmissionGate.java:154` — bots exhausting `maxRespawnsPerSession=5`. The rejection token is `RejectionToken.RESPAWN_CAP`, and the metric tag `availableTags.reason = ["respawn-cap"]` is what the sidecars actually carry (the 1818eeb headline labeled this column `rejected{world-full}` which was wrong). Zero `world-full` rejections appear in any sidecar at any tier.

Practical implication: F1 is resolved by raising the global cap to 1500 to make it non-binding at the bot population sizes used for the 10× ramp (100/500/1000 < 1500). Plan 20-04/05/06 should cite this as a benchmark-time JVM-flag override, not a production default change. There is no per-bucket cap surface to design against — if a future plan needs to bound nutrients independently, it has to introduce one.

### F2 — tick.health.work-time-ms excludes per-session encode + send (RESOLVED)

New meter: `paralife.outbound.encode.send.ms` (Timer with histograms enabled). Registered in `AdmissionMetrics` and accessed via `metrics.encodeSendTimer()` from `OutboundSender.drainLoop`. The Timer brackets the entire `try { encode + getBytes + recordFrameSize + synchronized(session) { sendMessage + emit-listener } } catch (...) { ... } finally { sample.stop }` block — that is a **strict superset** of the original "encode+send" framing because it also captures monitor-wait contention and metric-recording overhead. (D2 further guards `sample.stop` against Micrometer-internal exceptions so a histogram-rotation race cannot kill the drain VT.)

`paralife.tick.health.work-time-ms` is explicitly the synchronous tick-dispatch scalar — frame build (`TickBroadcaster.onTick @Order(50)`) is in the window, encode + per-VT send is not. The scalar is retained for cross-baseline-diff continuity with 20-01b (note: it reads `null` in the 20-01c sidecars — see Caveat #3 below; the new Timer is the actually-useful encode+send measurement).

Empirical finding: encode+send-and-monitor-wait is fast. 1000-bot tier sample 6 accumulated 76 848 records (one per `drainLoop` iteration) totalling 7.14 s wall time — mean ≈ 93 µs, max 22.1 ms. Not a hotspot. The original F2 concern that per-VT scaling cost was invisible is now answered: there is no per-VT scaling cost worth optimising at this load.

The Timer envelope still **excludes** offer→take queue dwell time. At `qmax=0` across all samples, dwell is sub-µs and the omission is harmless; if queue depth ever climbs, the Timer will under-report client-perceived send latency. Documented so downstream plans don't conflate Timer p99 with client-perceived send latency.

### F6 — c22e487 baseline stale vs HEAD (RESOLVED)

20-01b artifacts (12 files in `profiles/*-c22e487.*`) remain committed as the prior **capped-population** capture for the F1 evidence trail. The intermediate 1818eeb artifacts (pre-D1 leak; multi-review surfaced the bug) have been dropped in favour of 0824f1a (post-D1+D2+D3). With 20-01c anchored at 0824f1a, three-gate is 9/9 green at the captured SHA.

## D1–D3 Code Fixes Landed in 20-01c

Multi-review surfaced three issues that needed in-source remediation before re-capture. All three landed before the 0824f1a artifacts:

### D1 — markDead must dec active.entities bucket (pre-existing leak)

Pre-fix `WorldWebSocketHandler.markDead:992-1000` removed `ATTR_ENTITY_ID` and cleared the resume token but never decremented the per-bucket gauge or released the bucket-tags snapshot. Combined with `incActiveBucket` at the Allow path (line 649) running on every respawn, each bot lifecycle accumulated +5 incs / −1 dec, producing the monotonic gauge growth observed in the 1818eeb 1000-bot sidecar (1669 → 3733 over 30 s).

Fix mirrors the `cleanupBot:917-935` pattern — lookup the captured `Tags` via `lookupBucketTags(entityId)`, dec the bucket, then release the tag snapshot. Crucially does NOT call `releaseSlot()`: `AdmissionGate.java:142` Guard 5 skips cap consumption on `req.isRespawn()`, so one slot is acquired at initial register and reused across all respawns — only `cleanupBot` (session close) and `markStalled` (stall close) release slots. Calling `releaseSlot` from `markDead` would over-release on every death.

Post-fix verification (0824f1a 1000-bot sidecar): trajectory is 966 → 1000 → 1000 → 998 → 966 → 851 — tracks live population through churn instead of accumulating. The 851 final value reflects bots that hit `maxRespawnsPerSession=5` exiting the population during the steady-state tail.

### D2 — Timer.Sample.stop guarded against Micrometer exceptions

`OutboundSender.drainLoop`'s `finally { sample.stop(...) }` could (low-probability) propagate a `RuntimeException` from inside Micrometer during histogram rotation or registry shutdown, killing the drain VT for that session and silently stalling outbound frames. Wrapped in `try / catch RuntimeException` with a warn-and-continue. Defensive; no emitted-metric changes.

### D3 — outbound queue-depth gauge double-register guard

`AdmissionMetrics.registerOutboundQueueDepthMaxGauge` previously overwrote the `outboundQueueDepthSupplier` on every call. Micrometer dedupes the gauge meter by name+tags at the registry layer, so the second registration looked like a no-op there — but the supplier swap silently changed what the still-registered gauge read. Now early-returns with a warn when called twice; first supplier wins.

### D4 — asprof sample rate documented in meta.json

`capture-tier.sh` now records `asprof_cpu_interval_us=10000` and `asprof_alloc_interval_bytes=524288` (asprof 4.4 defaults: 100 Hz cpu, 512 KB alloc) in the per-tier `meta.json`. Pushes back on the multi-review claim that 19 KB `cpu.html` indicated sparse sampling — at the documented rate, 60 s capture = ~6 000 stacks, which is normal density.

## Outbound queue + drain VT saturation evidence

Across **all 18 samples × 3 tiers** (6 samples per tier):
- `paralife.outbound.queue.depth.max` = 0 in every sample
- `paralife.backpressure.stalled.sessions` = null (meter registered but never written → no sessions stalled)
- `paralife.outbound.detach.timeout` = 0 at every tier

This is "no queue pressure observed at 5 s scrape points across 18 samples × 3 tiers". `peakQueueDepth()` is at-scrape-instant (not interval-peak), so bursts between scrapes are invisible — adequate evidence for "the D-10 VT-per-session + bounded `ArrayBlockingQueue<Frame>` architecture absorbs the tick-broadcast cadence at 1000 bots without queue pressure", weaker evidence for "no pressure at any moment". The synchronized-session-monitor (per-session lock around `sendMessage`) is not a contention hotspot at this load — corroborated by the lock flamegraph.

## Tick wall-time vs bot count

`paralife.tick.work.ms.max` over the steady-state window (last sample):

| Tier | tick.work.ms.max | ratio to 100-tier |
|---|---|---|
| 100 | 83 ms | 1.0× |
| 500 | 75 ms | 0.9× |
| 1000 | 106 ms | 1.3× |

The tick pipeline cost is dominated by per-tick constant-factor work (CA simulation, environment effects, frame-build snapshot loop), not per-bot scaling. This is consistent with the D-10 design intent — per-bot work is parallelised onto drain VTs and is not on the tick critical path. 6 samples × 1 run per tier is enough for "not a hotspot at this scale"; insufficient for quantitative scaling extrapolation (deferred to Phase 21).

## Pushback

Two multi-review claims did not survive verification against the source data:

### "10× scale is partly fictional" (claude inline R3) — pushed back

Claude inline R3's arithmetic: 76 048 enc records / 200 s tick window @ 2 Hz = 19 % of "all 1000 bots alive every tick", implying ~190 concurrent-alive on average.

The denominator is wrong. `enc.cnt` is cumulative since meter registration (server boot). At 1000-bot tier sample 6 the metric reflects activity from ramp-start through ~47 s later: sample 1 fires immediately after the ramp completes (~22 s into the run), sample 6 is +25 s after sample 1. 1000 bots × 2 Hz × ~37 s effective broadcast window ≈ 74 k frames expected; observed 76 848 ≈ 103 % of theoretical. Bots ARE at full population during the measurement, the 200 s denominator was the error.

Post-D1 0824f1a verification corroborates: `active.entities` reads 966 / 1000 / 1000 / 998 / 966 / 851 across the 6 samples — the population genuinely is ~1000 through most of the steady-state window. The 10× ramp is real for connection count *and* sustained live population.

### "cpu.html 19 KB suggests sparse sampling" (claude reference Y5) — pushed back

asprof 4.4 default cpu sampling is 100 Hz (10 000 µs interval); 60 s capture = ~6 000 stacks. 19 KB d3-flame-graph HTML is the expected density at that sample count — d3-flame-graph aggressively aggregates repeated stack frames. The capture is not sparse; it is well-aggregated. Sample rate is now recorded in `meta.json` per D4 so future readers don't have to re-derive this.

### "tick.health.work-time-ms shows numeric values" (codex inline + opencode reference) — pushed back

Codex inline and opencode reference reported `tick.health.work-time-ms = "43.0, 39.0, ..."` in the 1818eeb sidecars. Verified empty via `jq '[.samples[] | .paralife_tick_health_work_time_ms.measurements[0].value]'` on all three 1818eeb sidecars: all 18 values are `null`. Same result on the 0824f1a sidecars. The gauge has no recorded writes at scrape time — reviewer hallucination, resolved against source data. The plan-as-shipped's caveat #3 (kept below) is factually correct.

## Artifact Inventory

| File | Size | SHA segment |
|---|---|---|
| `jfr-100bots-baseline-0824f1a.jfr` | 2.5 MB | 0824f1a |
| `jfr-500bots-baseline-0824f1a.jfr` | 3.7 MB | 0824f1a |
| `jfr-1000bots-baseline-0824f1a.jfr` | 4.7 MB | 0824f1a |
| `cpu-1000bots-baseline-0824f1a.html` | 80 KB | 0824f1a |
| `alloc-1000bots-baseline-0824f1a.html` | 34 KB | 0824f1a |
| `lock-1000bots-baseline-0824f1a.html` | 19 KB | 0824f1a |
| `metrics-100bots-baseline-0824f1a.json` | 12 KB | 0824f1a |
| `metrics-500bots-baseline-0824f1a.json` | 13 KB | 0824f1a |
| `metrics-1000bots-baseline-0824f1a.json` | 13 KB | 0824f1a |
| `jfr-{100,500,1000}bots-baseline-0824f1a.meta.json` | 1.2 KB each | 0824f1a |

Lock flamegraph captured in a separate 1000-bot run (not concurrent with cpu/alloc; asprof 4.4 multi-attach limitation documented in 20-01b). Acceptable exploratory evidence per all four 20-01b methodology reviewers.

## Verification gates

| # | Gate | Outcome |
|---|---|---|
| 1 | `./gradlew test` green after D1+D2+D3 | ✓ 944 tests; 1 pre-existing flake (HundredBotIntegrationTest — see caveat) |
| 2 | Three-gate (GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant) green at HEAD | ✓ 9/9 at 0824f1a |
| 3 | `admission.rejected{reason=world-full}` = 0 at every tier | ✓ Zero world-full rejections across all 18 samples; the only rejections at any tier are `reason=respawn-cap` at 1000-tier |
| 4 | `peak_registered == --count` per tier | ✓ exactly 100 / 500 / 1000; connect_failures_total = 0 + e408_reconnect_required_total = 0 at every tier |
| 5 | `outbound.encode.send.ms.max` and `outbound.queue.depth.max` show monotonic-or-bounded growth | ✓ enc.cnt grows 5.8k → 33k → 77k (linear-ish with bot count); enc.max bounded ≤22 ms; qmax bounded at 0. (Note: Actuator JSON exposes COUNT/TOTAL_TIME/MAX only; configured percentiles are available on `/actuator/prometheus` if downstream needs them.) |
| 6 | `meta.json` carries `cap_during_run: 1500`, `asprof_cpu_interval_us: 10000`, `asprof_alloc_interval_bytes: 524288`, corrected A8 wording | ✓ verified in 3× meta.json |
| 7 | Headline contains explicit cap-binding + connection-count framing (no naked "10× scale") | ✓ headline is "100/500/1000 connected bots, cap=1500 non-binding, ~76 k frames @ 1000-tier through ~47 s of capture window" |
| 8 | `paralife.outbound.detach.timeout = 0` at every tier (D2 preservation check) | ✓ all 18 samples |
| 9 | `active.entities@end ≈ tier-count ± churn` at 1000-tier (D1 falsifiability — pre-fix was 3733) | ✓ trajectory 966→1000→1000→998→966→851; final dip from respawn-cap-exhausted bots |

## Caveats

1. **HundredBotIntegrationTest.hundredBotsConnectAndReceiveTicks** times out on WSL2 + Gradle `forkEvery=1` test isolation. Verified pre-existing at parent commit 14e96ea via stash + retest — not introduced by D1/D2/D3. The three-gate (the load-bearing baseline gate per plan §Verification) is 9/9 green. Phase 22 test-leak audit owns the `forkEvery=1` setting and it must remain unconditional per the 2026-05-03 fleet decision.
2. **respawn-cap pressure at 1000-tier**. With cap=1500 non-binding and `maxRespawnsPerSession=5`, the 1000-bot tier accumulates 58 `respawn-cap` rejections by the last sample as bots exhaust their respawn budget. Bot scaling is uncontaminated — these are not cap-bind events. Plan 20-04 or Phase 21 should decide whether `maxRespawnsPerSession` needs to be raised for sustained 1000+ bot benchmarks.
3. **paralife.tick.health.work-time-ms = null in 0824f1a sidecars**. The MAINTENANCE-mode AtomicLong gauge has no recorded writes at scrape time in this baseline. Codex (inline) and opencode (reference) reported numeric values during multi-review; verified empty via `jq '[.samples[] | .paralife_tick_health_work_time_ms.measurements[0].value]'` on all three sidecars (1818eeb and 0824f1a) — reviewer hallucination, resolved against source data. The deprecated scalar is retained for SHA-to-SHA continuity only; `paralife.tick.work.ms` DistributionSummary (count/total/max via Actuator JSON; configured percentiles via Prometheus scrape) is the live tick-cost meter.

## Supersedes

20-01b `14e96ea` baseline (commit `feat(20-01b): baseline JFR + flamegraph + actuator-metric capture at c22e487`). 12 artifacts in `profiles/*-c22e487.*` are kept as the prior **capped-population** capture and referenced here for the F1 evidence trail. They are not authoritative for downstream Plans 20-04 / 20-05 / 20-06 — those plans cite the 0824f1a capture. The intermediate 1818eeb capture has been dropped (pre-D1 leak; replaced wholesale by 0824f1a).

## Deferred (recorded only)

| Item | Reason |
|---|---|
| 3× replication per tier for noise floor | Phase 21 scale-benchmark gate concern. Timer histograms now carry MAX inside each run; configured percentiles available via Prometheus scrape. |
| Per-thread JFR `jdk.CPULoad` / `jdk.ThreadCPULoad` extraction | Tooling work; not load-bearing for MVP. |
| Per-bucket tagging on `outbound.encode.send.ms` | Untagged Timer sufficient for saturation detection. |
| `paralife.admission.bucket.*.cap` surface | Plan 20-04 work if a per-bucket cap is needed; current code is global-only. |
| Wire up `paralife.tick.health.work-time-ms` MAINTENANCE-mode write path | Deprecated scalar; replaced by `tick.work.ms` DistributionSummary. |
| Prometheus-format percentile artifacts for `outbound.encode.send.ms.p95` | Available now via `/actuator/prometheus` but not captured in the JSON sidecar shape; defer formal percentile reporting to Phase 21. |
| TD-20-01c-A — `recordFrameSize` called before successful `sendMessage` | See `.planning/STATE.md` Deferred Items. Trivial impact at this scale (≤1 frame per IOException). |
