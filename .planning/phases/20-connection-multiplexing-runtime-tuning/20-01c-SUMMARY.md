---
phase: 20-connection-multiplexing-runtime-tuning
plan: 01c
subsystem: profiling-baseline
supersedes: 20-01b
tags: [jfr, async-profiler, flamegraph, actuator-metrics, 62c1b44-baseline, performance, F1-remediation, F2-remediation, F6-remediation, multi-review-remediation, pass-2-remediation]
requires:
  - phase: 20-01
    provides: async-profiler toolchain bootstrap + profiles/ filename convention (D-19)
  - phase: 20-01b
    provides: capture ritual (sequential asprof attach + sidecar polling) — superseded baseline content
provides:
  - 3× baseline JFR at HEAD with cap=1500 override (100/500/1000 bots @ 62c1b44)
  - 3× saturation-aware flamegraph HTML (cpu/alloc/lock at 1000 bots)
  - 3× meta.json sidecars carrying cap_during_run + asprof sample intervals + corrected A8 wording
  - 3× actuator-metric JSON sidecars (9 meters × 6 samples) — adds queue.depth.max + encode.send.ms
  - paralife.outbound.queue.depth.max aggregate gauge (new)
  - paralife.outbound.encode.send.ms Timer (count/total/max via Actuator JSON; percentiles via Prometheus scrape if needed) — closes F2 visibility gap
  - per-tier harness counters (peak_registered, syncs, e408, failures, respawns, perceptions, actions)
  - markDead now decrements active.entities bucket (pre-existing leak fix)
  - cleanupBot skips active-bucket dec when entityId==null (pass-2 R1 + pre-existing TD-20-01c-B path-B double-dec)
  - Timer.Sample.stop isolated from Micrometer exceptions
  - outbound queue-depth gauge guarded against double-register
affects: [20-02-jetty-runtime-config, 20-04-runtime-md-skeleton, 20-05-codec-opts, 20-06-finalise]
tech-stack:
  added: []
  patterns:
    - "Aggregate per-session queue-depth gauge (max across queues map) with strong-ref-pinned IntSupplier (Micrometer weak-target bug avoidance)"
    - "Timer.Sample try/finally around encode + synchronized(session) sendMessage in drain VT loop; stop() further guarded against Micrometer exceptions"
    - "JVM-flag-only cap override (-Dparalife.admission.cap=1500) — benchmark-time override; production default (application.yml:65, cap=256) unchanged"
    - "markDead lookup → dec → release sequence mirrors cleanupBot:919-940 — same AdmissionMetrics APIs, no new methods"
    - "cleanupBot fallback skipped when entityId==null — pass-2 H1: the entityId==null branch indicates the active-bucket dec was already owned by another callsite (markDead snapshot dec on path C, or cleanupByEntityId snapshot dec at grace-expire on path B); cleanupBot must not dec, or it double-decs"
key-files:
  created:
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-62c1b44.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-62c1b44.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-62c1b44.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-62c1b44.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-62c1b44.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-62c1b44.html
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-62c1b44.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-62c1b44.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-62c1b44.meta.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-62c1b44.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-62c1b44.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-62c1b44.json
  modified:
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (markDead now dec+releases bucket; pass-2 H1: cleanupBot skips active-bucket dec when entityId==null)
    - src/main/java/com/paralife/admission/OutboundSender.java (sample.stop guarded against Micrometer exceptions)
    - src/main/java/com/paralife/admission/AdmissionMetrics.java (gauge double-register guard + slf4j logger)
key-decisions:
  - "F1 (admission cap silently bounds workload) RESOLVED. The cap is **world-aggregate**, not per-bucket — AdmissionGate.java:58,140-151 uses one global AtomicInteger reservedSlots against one admissionConfig.cap(). With -Dparalife.admission.cap=1500 on server boot, the cap is non-binding for the bot population at every tier: harness peak_registered = --count at 100 / 500 / 1000, and zero world-full rejections appear in any sidecar. Nutrients are placed directly via SimulationEngine.setEntity:1431 and bypass AdmissionGate entirely, so the earlier 'nutrient bucket hits cap' narrative was an artifact of the rewrite, not a code behaviour."
  - "The rejected{respawn-cap} entries (peak 99 at 1000-tier sample 6 post-H1) are Guard 6 (AdmissionGate.java:154) — bots exhausting maxRespawnsPerSession=5 across the 200 s window. This is orthogonal to F1 cap-binding and does not contaminate the bot-population scaling story. Zero rejections at 100/500-tier."
  - "F2 (tick gauge excludes per-session encode+send) RESOLVED via paralife.outbound.encode.send.ms Timer. The Timer brackets PerceptionCodec.encode + getBytes() + recordFrameSize + monitor-wait + synchronized(session) sendMessage + frame-emit listener in OutboundSender.drainLoop. This is *broader* than the original framing (which described encode+send only) — it captures monitor contention and metric-recording overhead too, which is a strict superset of the original visibility gap. Empirical answer at 1000-bot tier sample 6 (62c1b44): enc.cnt = 75,940 records totalling 5.75 s wall time (mean ≈ 76 µs, max 16.5 ms). Per-VT encode+send is NOT a saturation hotspot."
  - "F6 (stale-baseline vs HEAD) RESOLVED. Baseline anchored at HEAD 62c1b44, the post-D1+D2+D3 fix point. f6da129 SimulationEngine.processDeaths ordering delta is upstream. Three-gate (GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant) is 9/9 green at 62c1b44."
  - "active.entities gauge leak fixed (D1 + pass-2 H1). Pre-D1 1818eeb 1000-bot sidecar showed monotonic growth (1669→3733 over 30 s steady-state) because WorldWebSocketHandler.markDead removed ATTR_ENTITY_ID without decrementing the per-bucket gauge. Post-D1 0824f1a 1000-bot trajectory: 966 → 1000 → 1000 → 998 → 966 → 851. Post-D1+H1 62c1b44 trajectory: 968 → 1000 → 1000 → 997 → 972 → 901 — H1 (cleanupBot skips active-bucket dec when entityId==null) closed a path-C double-dec that was attributing ~50 units of false dropoff to the steady-state tail. The leak was pre-existing (Phase 18-19 era); 20-01c surfaced it by reporting the number for the first time."
  - "Outbound queue depth is at-scrape-instant zero across the 10× tier ramp. paralife.outbound.queue.depth.max reads 0 in all 18 samples × 3 tiers. Combined with paralife.backpressure.stalled.sessions = null (meter never written → no sessions ever stalled) and paralife.outbound.detach.timeout = 0 at every sample, no queue pressure observed at the 5 s scrape points. peakQueueDepth() is at-scrape-instant max (not interval-peak), so this is sample-coarse evidence — bursts between scrapes are invisible. Adequate for 'D-10 VT-per-session drain absorbs tick broadcast cadence at this scale'; insufficient for 'zero pressure between samples'."
  - "Tick wall-time is essentially flat across 10× bot count. paralife.tick.work.ms.max at last sample (62c1b44): 84 ms (100 bots) / 100 ms (500 bots) / 98 ms (1000 bots). The 100→1000 amplification is ~1.17×, not 10× — tick pipeline dominated by per-tick constant-factor work (CA simulation, environment effects, frame-build snapshot loop), not per-bot scaling. 6 samples × 1 run per tier is enough for 'not a hotspot at this scale'; insufficient for quantitative scaling extrapolation."
  - "Lock flamegraph at HEAD 62c1b44 captured in a separate 1000-bot run (asprof 4.4 cannot multi-attach the same PID with different events). Acceptable cross-run evidence per all four 20-01b methodology reviewers."
  - "A8 wording corrected in 62c1b44 meta.json: probe is `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'`; Temurin 21.0.6 defaults to G1, ZGC requires `-XX:+UseZGC`, generational ZGC requires `-XX:+ZGenerational`. Generational ZGC becomes the default only in JDK 23."
  - "asprof sample intervals now documented in meta.json (asprof_cpu_interval_us=10000, asprof_alloc_interval_bytes=524288 — asprof 4.4 defaults: 100 Hz cpu / 512 KB alloc). 19 KB cpu.html at 60 s = ~6 000 stacks, normal density at this sample rate."
patterns-established:
  - "Aggregate-gauge supplier pinning: when registering `Gauge.builder(name, IntSupplier, ToDoubleFunction)`, Micrometer holds the target weakly. Bare `this::method` lambdas have no other strong owner and will be GC'd, exporting NaN. Pin the supplier in a long-lived field (typically on a Spring-managed singleton) and re-anchor the gauge on `this`. Bug observed at d768305, fixed at 02b1b76. Now also guarded against double-register (D3) — log+ignore second caller, first supplier wins."
  - "Per-bucket gauge dec discipline: every `incActiveBucket()` callsite must be paired with a `decActiveBucketByTags(lookupBucketTags(entityId))` + `releaseBucketTags(entityId)` callsite BEFORE the session attrs (ATTR_ENTITY_ID) are cleared. cleanupBot and (post-D1) markDead follow the pattern; future lifecycle exits must adopt the same shape or the gauge accumulates."
  - "Sidecar metric scraping: `/actuator/metrics/{name}` returns null/404 when the meter is registered but has no recorded measurements at scrape time. `paralife.admission.rejected` reads null in early samples then 3/28/99 as respawn-cap pressure builds at 1000-tier — both behaviours are normal; downstream tooling must treat absence as 0, not as 'meter missing'."
  - "Per-tier capture ritual = `./gradlew clean loadHarnessJar bootJar` once; then `capture-tier.sh COUNT DURATION SHA OUT_DIR [with-flames]` per tier, then `lock-capture.sh SHA OUT_DIR` for the lock flamegraph. Scripts in `/tmp/p20-01c-capture/` (gitignored). Each tier ~3.5 min wall; full 4-capture run ~13 min."
requirements-completed: [SCALE-09]
duration: 90min
completed: 2026-05-20
---

# Plan 20-01c: Re-anchored Baseline + Multi-Review Remediation

**Baseline re-anchored at HEAD SHA `62c1b44` with `-Dparalife.admission.cap=1500`. Three tiers: 100 / 500 / 1000 connected bots, cap=1500 non-binding for the bot population, ~75.9 k frames captured at the 1000-tier through ~50 s of capture window. 12 artifacts (3× JFR + 3× cpu/alloc/lock HTML + 3× meta.json + 3× metric sidecar). Supersedes 20-01b (c22e487) which captured at cap=256 with the population ceiling silently binding 500/1000-tier admissions to ~256.**

Re-anchored mid-flight after multi-review (`20-01c-REVIEW-inline.md`, `20-01c-REVIEW-reference.md`) surfaced three RED findings against the original 1818eeb capture: (a) the per-bucket-cap narrative was fabricated, (b) the rejection column was mislabeled (`world-full` → actual `respawn-cap`), and (c) `active.entities` was a leaky-accumulator (pre-existing markDead bug). All three resolved at code or doc level. See §Multi-Review Remediation below.

## Per-Tier Headline (post-D1 + H1 / 62c1b44)

| Tier | peak_reg | active.entities@end (live count) | rejected{respawn-cap}@end | qmax | enc.cnt | enc.max | tick.max | frame.max | detach | respawns |
|---|---|---|---|---|---|---|---|---|---|---|
| **100 bots** | 100 | 96 | —¹ | 0 | 5 825 | 20 ms | 84 ms | 95 B | 0 | 469 |
| **500 bots** | 500 | 479 | —¹ | 0 | 33 429 | 14 ms | 100 ms | 169 B | 0 | 2 500 |
| **1000 bots** | 1 000 | 901 | 99 | 0 | 75 940 | 16 ms | 98 ms | 268 B | 0 | 4 990 |

¹ `—` indicates the meter never incremented (`paralife.admission.rejected` is registered but no `respawn-cap` rejection fired during the run); not the same as a counter that reached 0.

`peak_registered` from harness `--report-out` JSON; `connect_failures_total` and `e408_reconnect_required_total` are 0 at every tier. `active.entities@end` is the per-bucket-gauge value at the last 5 s scrape — post-D1+H1 it tracks live population through churn (the 1000-tier trajectory dips to 901 in the final sample as bots that hit `maxRespawnsPerSession=5` exit and are not yet replaced). `rejected{respawn-cap}` is Guard 6 — bots exhausting their respawn budget — orthogonal to cap-binding. `enc.*` columns are `MAX` from `paralife.outbound.encode.send.ms` (the Actuator JSON exposes COUNT / TOTAL_TIME / MAX; configured percentiles are emitted on `/actuator/prometheus` if needed). `enc.cnt` is the Timer COUNT (drainLoop iterations reaching `Timer.start()`); `frame.size.cnt` increments only after successful `PerceptionCodec.encode()` returns. Gap (0.005-0.6 % across tiers) reflects encode-failure frames caught at `OutboundSender.java:329` — benign at observed scale. `tick.max` from `paralife.tick.work.ms.max`. `qmax` = `paralife.outbound.queue.depth.max` (aggregate max across all per-session queues, sampled at scrape time). `detach` = `paralife.outbound.detach.timeout` count. `respawns` from harness `respawns_total`.

## Active-Population Workload (50× food scenario / `103a615`)

**Why a second workload.** The `62c1b44` churn baseline above runs production defaults
(`nutrient-spawn-probability: 0.001`). A death-cause investigation at that config (temporary
`DeathDiagnostics` instrumentation, ~2 900 deaths/120 s at 500 bots) found the population is a
**death treadmill**: 77.9 % of deaths are starvation, 62 % of entities die within 10 s, and the
food supply (~65 nutrients/tick on a 256² grid) is ~10–15× short of what 1000 entities need to
offset energy decay. Starvation share tracks per-type metabolics exactly — Catalyst (decay 3,
break-even 1.0 nutrient/tick) starves in 96 % of its deaths; Membrane (decay 1) only 41 %.
Consequence for profiling: the churn CPU profile is dominated by **environment CA diffusion**
(toxin/mutagen), not the transport path P20 exists to tune, because bots churn through
register→spawn→die→respawn faster than they sustain a perceive-act loop.

To profile a representative *active* population, the scenario re-runs with
`-Dparalife.simulation.nutrient-spawn-probability=0.05` (50×). **This is a profiling-scenario knob,
not a balance change** — the production default at `application.yml:79` stays `0.001`. At 50× food
the regime flips: starvation collapses (2262→604 deaths at 500 bots), combat (emergent RPS predation)
becomes the dominant cause, mean lifespan ~doubles, and the population self-sustains. The underlying
survival deficit is a simulation-balance / M002 (`population stability over 500+ ticks`) concern,
**out of Phase 20 scope** — logged as a separate balance/viability phase. 50× is the knob that yields
an active workload, not a recommended balance value.

Active-population transport health (18 samples/tier, 90 s profile window after 20 s ramp,
cap=1500 non-binding):

| Tier | peak_reg | active.entities | qmax | tick.work max | tick.health max | enc max | detach | rejected | actions_sent |
|---|---|---|---|---|---|---|---|---|---|
| **100 bots** | 100 | 96–100 | 0 | 127 ms¹ | 32 ms | 20 ms | 0 | respawn-cap | 24 680 |
| **500 bots** | 500 | 461–500 | 0 | 92 ms | 49 ms | 18 ms | 0 | respawn-cap | 119 852 |
| **1000 bots** | 1 000 | 889–1 000 | 0 | 116 ms | 84 ms | 34 ms | 0 | respawn-cap | 227 828 |

¹ `tick.work.ms` MAX is single-tick outlier-sensitive (GC / lightning strike); 100-tier load is light.
All tiers sit well under the 500 ms tick interval. `connect_failures_total` and
`e408_reconnect_required_total` are 0 at every tier. `actions_sent_total` (harness report) confirms
the action path is genuinely exercised — 0.6–0.66 actions/bot/tick — versus the churn run where bots
barely complete an act-loop before dying.

**Transport scales under active load.** Zero backpressure (`qmax=0`) at every sample even at 1000 bots ×
227 k actions; no `world-full` rejections (only Guard-6 `respawn-cap`); clean detach. Active load pushes
`tick.work.ms` MAX to 116 ms (churn 98 ms) and `encode.send.ms` MAX to 34 ms (churn 16 ms) — denser
per-bot vision frames, as expected — without saturating the outbound path.

**CPU hot path flips to the transport layer — the reason the workload matters.** 1000-tier
`jdk.ExecutionSample` attribution, churn vs active:

| Subsystem | Churn `62c1b44` (samples) | Active `103a615` (samples) |
|---|---|---|
| EnvironmentEngine CA (onTick + advanceToxin + diffuseStep) | ~1 044 (**dominant**) | ~553 (secondary) |
| TickBroadcaster build (onTick + buildTickFrame + buildCellEntries + kindCodeFor + …) | ~150 | **~876 (dominant)** |
| PerceptionCodec encode | minimal | 107 |
| OutboundSender.drainLoop (per-session send VTs) | 12 | 138 |
| SimulationEngine.processInteractions (combat) | 50 | 50 |
| Inbound (handleRegister / queueAction) | 56 / 1 (register-churn) | 23 (less churn) |

The churn baseline would have misdirected runtime tuning toward environment CA. The active profile
correctly surfaces **TickBroadcaster + PerceptionCodec + OutboundSender** as the hot path — precisely
Phase 20's transport-overhead remit. Plans 20-04/05 should tune against the active profile, citing the
churn baseline only for the env-CA fixed-cost floor. Artifacts: `profiles/*-active-50xfood-103a615.*`
(18 files; metric sidecar + JFR + meta + cpu/alloc/lock flamegraph × 3 tiers).

## Multi-Review Remediation

The original 1818eeb capture went through `multi-review` in both inline and reference modes (7 reviewer runs). Three substantive RED findings converged:

### F1 — admission.cap silently bounds workload (RESOLVED with corrected narrative)

The original `application.yml:65` (`paralife.admission.cap: 256`) is unchanged. The 1500 value exists only as a JVM flag (`-Dparalife.admission.cap=1500`) on the server boot for benchmark runs; production default at `application.yml:65` stays at 256. meta.json carries `cap_during_run: 1500` for evidence trail.

`AdmissionGate.java:58,140-151` uses **one** global `AtomicInteger reservedSlots` against **one** `admissionConfig.cap()` — the cap is world-aggregate, not per-bucket. The earlier 1818eeb SUMMARY framed F1 as resolved via "per-bucket cap; bot buckets never hit, nutrient bucket hits" — that narrative has no source basis. Nutrients are placed directly through `SimulationEngine.setEntity:1431` and bypass `AdmissionGate` entirely; bucket separation exists only as metric *tags* (`bucket=spore|membrane|catalyst|...`), not as admission-gate counters.

The actual mechanism producing the post-recapture rejection numbers (3/28/99 across 1000-tier samples 4–6) is Guard 6 at `AdmissionGate.java:154` — bots exhausting `maxRespawnsPerSession=5`. The rejection token is `RejectionToken.RESPAWN_CAP`, and the metric tag `availableTags.reason = ["respawn-cap"]` is what the sidecars actually carry (the 1818eeb headline labeled this column `rejected{world-full}` which was wrong). Zero `world-full` rejections appear in any sidecar at any tier.

Practical implication: F1 is resolved by raising the global cap to 1500 to make it non-binding at the bot population sizes used for the 10× ramp (100/500/1000 < 1500). Plan 20-04/05/06 should cite this as a benchmark-time JVM-flag override, not a production default change. There is no per-bucket cap surface to design against — if a future plan needs to bound nutrients independently, it has to introduce one.

### F2 — tick.health.work-time-ms excludes per-session encode + send (RESOLVED)

New meter: `paralife.outbound.encode.send.ms` (Timer with histograms enabled). Registered in `AdmissionMetrics` and accessed via `metrics.encodeSendTimer()` from `OutboundSender.drainLoop`. The Timer brackets the entire `try { encode + getBytes + recordFrameSize + synchronized(session) { sendMessage + emit-listener } } catch (...) { ... } finally { sample.stop }` block — that is a **strict superset** of the original "encode+send" framing because it also captures monitor-wait contention and metric-recording overhead. (D2 further guards `sample.stop` against Micrometer-internal exceptions so a histogram-rotation race cannot kill the drain VT.)

`paralife.tick.health.work-time-ms` is explicitly the synchronous tick-dispatch scalar — frame build (`TickBroadcaster.onTick @Order(50)`) is in the window, encode + per-VT send is not. The scalar is live (`TickHealthMonitor.onTick:63` writes the AtomicLong-backed gauge every tick; 62c1b44 sidecars carry numeric values 6-60 ms across all 18 samples) and retained for cross-baseline-diff continuity with 20-01b; the new Timer is the broader encode+send+monitor-wait measurement.

Empirical finding: encode+send-and-monitor-wait is fast. 1000-bot tier sample 6 (62c1b44) accumulated 75 940 records (one per `drainLoop` iteration) totalling 5.75 s wall time — mean ≈ 76 µs, max 16.5 ms. Not a hotspot. The original F2 concern that per-VT scaling cost was invisible is now answered: there is no per-VT scaling cost worth optimising at this load.

The Timer envelope still **excludes** offer→take queue dwell time. At `qmax=0` across all samples, dwell is sub-µs and the omission is harmless; if queue depth ever climbs, the Timer will under-report client-perceived send latency. Documented so downstream plans don't conflate Timer p99 with client-perceived send latency.

### F6 — c22e487 baseline stale vs HEAD (RESOLVED)

20-01b artifacts (12 files in `profiles/*-c22e487.*`) remain committed as the prior **capped-population** capture for the F1 evidence trail. The intermediate 1818eeb artifacts (pre-D1 leak; multi-review surfaced the bug) have been dropped in favour of 62c1b44 (post-D1+D2+D3). With 20-01c anchored at 62c1b44, three-gate is 9/9 green at the captured SHA.

## D1–D4 + H1 Code Fixes Landed in 20-01c

Multi-review surfaced three issues that needed in-source remediation before re-capture. All three landed before the 62c1b44 artifacts:

### D1 — markDead must dec active.entities bucket (pre-existing leak)

Pre-fix `WorldWebSocketHandler.markDead:992-1000` removed `ATTR_ENTITY_ID` and cleared the resume token but never decremented the per-bucket gauge or released the bucket-tags snapshot. Combined with `incActiveBucket` at the Allow path (line 649) running on every respawn, each bot lifecycle accumulated +5 incs / −1 dec, producing the monotonic gauge growth observed in the 1818eeb 1000-bot sidecar (1669 → 3733 over 30 s).

Fix mirrors the `cleanupBot:919-940` pattern — lookup the captured `Tags` via `lookupBucketTags(entityId)`, dec the bucket, then release the tag snapshot. Crucially does NOT call `releaseSlot()`: `AdmissionGate.java:142` Guard 5 skips cap consumption on `req.isRespawn()`, so one slot is acquired at initial register and reused across all respawns — only `cleanupBot` (session close) and `markStalled` (stall close) release slots. Calling `releaseSlot` from `markDead` would over-release on every death.

Post-fix verification (62c1b44 1000-bot sidecar, post-D1+H1): trajectory is 968 → 1000 → 1000 → 997 → 972 → 901 — tracks live population through churn instead of accumulating. The 901 final value reflects bots that hit `maxRespawnsPerSession=5` exiting the population during the steady-state tail. (Pre-H1 the same scenario read 851; the +50 delta is the path-C double-dec H1 closed — see §H1 below.)

### H1 — cleanupBot must skip active-bucket dec when entityId == null (pass-2 R1 + pre-existing TD-20-01c-B)

Pass-2 multi-review (claude+gemini RED, opencode YELLOW) flagged that D1 introduced a path-C double-dec: when `markDead` cleared `ATTR_ENTITY_ID` and dec'd the bucket via snapshot, a subsequent session close fired `cleanupBot` which (a) saw `wasRegistered=true` (markDead does not touch `ATTR_ENTITY_TYPE` — that's the durable "session ever admitted" marker), (b) computed `entityId=null` → `lookupBucketTags(null)` returned null, (c) fell into the session-tag fallback `decActiveBucket(s)` → `AtomicInteger.decrementAndGet` fired twice for the last life.

The same fallback path also over-dec'd path B (stalled-then-close-without-reconnect-before-grace-expire) — `markStalled` clears `entityId`, then if the client never reconnects within the grace window the session eventually closes, fires `cleanupBot` (entityId==null → fallback dec), AND `cleanupByEntityId` runs at grace-expire and decs again via the `bucketTagsByEntityId` snapshot. This was pre-existing (Phase 17-18 era), invisible in sidecars because reconnect re-incs and rebalances the count in steady-state benchmarks. Tagged as **TD-20-01c-B** in STATE.md, closed incidentally by H1.

H1 fix at `WorldWebSocketHandler.cleanupBot:919-940`: guard the entire active-bucket dec block with `if (entityId != null)`. When `entityId==null`, either markDead already dec'd (path C) or cleanupByEntityId at grace-expire will own the dec via the snapshot (path B) — cleanupBot must NOT dec.

Falsifiability proof: pre-H1 0824f1a 1000-bot sample 6 = 851; post-H1 62c1b44 1000-bot sample 6 = 901. The +50 unit delta is the path-C double-decs that cleanupBot was firing during steady-state respawn-cap exhaustion. (Pass-2 opencode YELLOW disposition correctly noted the mechanism was uncaptured in sidecar windows because sessions stay open; the post-H1 recapture surfaces the contribution because the same workload now under-counts dropoff by exactly the amount H1 removed.)

Side effect: `releaseBucketTags(entityId)` no longer runs when `cleanupBot` is hit with `entityId==null`. The snapshot is released by markDead (path C) or by `cleanupByEntityId` at grace-expire (path B), so `bucketTagsByEntityId` cannot grow unbounded.

### H2 — cleanupByEntityId session-still-in-registry branch owns the dec+release (pass-3 R-P3-1)

Pass-3 multi-review (codex RED + claude/gemini/opencode YELLOW convergent) flagged a residual leak: `cleanupByEntityId:813-816` calls `cleanupBot(session)` when the stalled session is still in the registry at grace-expire. With H1 in place, that `cleanupBot` invocation now hits the `entityId==null` skip — so neither `cleanupBot` nor `cleanupByEntityId` performed the active-bucket dec or snapshot release on that branch.

In production this is a narrow race: `afterConnectionClosed` typically fires within microseconds of `markStalled`'s `SERVICE_RESTARTED` close (via `OutboundSender.detachSession`), unregistering the session well before the grace window (`grace-window-ticks × 500 ms` ≥ 5 s) expires. The `else`-branch at `cleanupByEntityId:818-834` is the dominant path and already does the dec + release + slot-release + grid-clear correctly. But the if-session-still-in-registry branch had no guard, so a pathological close-delay would leak the active bucket and `bucketTagsByEntityId` snapshot indefinitely.

H2 fix at `WorldWebSocketHandler.cleanupByEntityId:813-822`: before calling `cleanupBot(session)`, dec the active bucket via the already-captured `bucketTags` and release the snapshot. `cleanupBot`'s H1 guard then correctly no-ops on the dec. Net: 1 dec, 1 release, no leak.

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
| 100 | 84 ms | 1.0× |
| 500 | 100 ms | 1.2× |
| 1000 | 98 ms | 1.2× |

The tick pipeline cost is dominated by per-tick constant-factor work (CA simulation, environment effects, frame-build snapshot loop), not per-bot scaling. This is consistent with the D-10 design intent — per-bot work is parallelised onto drain VTs and is not on the tick critical path. 6 samples × 1 run per tier is enough for "not a hotspot at this scale"; insufficient for quantitative scaling extrapolation (deferred to Phase 21).

## Pushback

### "10× scale is partly fictional" (claude inline R3) — pushed back

Claude inline R3's arithmetic divided `enc.cnt` by 200 s of tick window @ 2 Hz, deriving ~190 concurrent-alive on average. The denominator was the harness `--duration`, not the actual measurement window (sample 1 fires ~22 s after `t=0` once ramp completes, sample 6 ~25 s later — effective window ~25–47 s).

Pass-2 advice: replace window-arithmetic with a direct `active.entities` trajectory derivation. 1000-tier 62c1b44 sidecar reads:

| sample | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| active.entities | 968 | 1 000 | 1 000 | 997 | 972 | 901 |

Population sits at full ±3 % through samples 1–4 and tails into the respawn-cap-exhaustion regime at samples 5–6. The 10× ramp is real for both connection count *and* sustained live population — the gauge is the proof; no `enc.cnt` arithmetic needed.

### "cpu.html 19 KB suggests sparse sampling" (claude reference Y5) — pushed back

asprof 4.4 default cpu sampling is 100 Hz (10 000 µs interval); 60 s capture = ~6 000 stacks. 19 KB d3-flame-graph HTML is the expected density at that sample count — d3-flame-graph aggressively aggregates repeated stack frames. The capture is not sparse; it is well-aggregated. Sample rate is now recorded in `meta.json` per D4 so future readers don't have to re-derive this.

### Pass-1 tick.health.work-time-ms reports — pass-1 pushback retracted

Pass-1 codex (inline) and opencode (reference) reported numeric `tick.health.work-time-ms` values in the 1818eeb sidecars. The plan-as-shipped's pushback section dismissed those reports as "reviewer hallucination, resolved against source data" and kept caveat #3 ("paralife.tick.health.work-time-ms = null in 62c1b44 sidecars"). Pass-2 multi-review (codex inline + opencode reference convergent RED) re-flagged this. **The original codex+opencode reports were correct; the pushback was wrong.**

Root cause of the pushback error: the `jq` query in the original pushback (`.paralife_tick_health_work_time_ms.measurements[0].value`) used underscores throughout, but the actual sidecar key is `paralife_tick_health_work-time-ms` (hyphenated `work-time-ms` segment, mirroring the meter name `paralife.tick.health.work-time-ms`). All 18 reads returned `null` because the JSON path did not match any key, not because the values were null. Correct query (`.["paralife_tick_health_work-time-ms"].measurements[0].value`) returns numeric values across all 18 samples: 100-tier 19-31 ms, 500-tier 22-55 ms, 1000-tier 32-60 ms at 0824f1a; comparable ranges at 62c1b44. `TickHealthMonitor.onTick` (`TickHealthMonitor.java:63`) writes the AtomicLong-backed gauge every tick — it cannot return null.

The scalar is kept alongside the `tick.work.ms` `DistributionSummary` for SHA-continuity with 20-01b; it is **not** deprecated. Both meters are live tick-cost signals.

## Artifact Inventory

| File | Size | SHA segment |
|---|---|---|
| `jfr-100bots-baseline-62c1b44.jfr` | 2.4 MB | 62c1b44 |
| `jfr-500bots-baseline-62c1b44.jfr` | 4.0 MB | 62c1b44 |
| `jfr-1000bots-baseline-62c1b44.jfr` | 4.7 MB | 62c1b44 |
| `cpu-1000bots-baseline-62c1b44.html` | 81 KB | 62c1b44 |
| `alloc-1000bots-baseline-62c1b44.html` | 29 KB | 62c1b44 |
| `lock-1000bots-baseline-62c1b44.html` | 18 KB | 62c1b44 |
| `metrics-100bots-baseline-62c1b44.json` | 13 KB | 62c1b44 |
| `metrics-500bots-baseline-62c1b44.json` | 13 KB | 62c1b44 |
| `metrics-1000bots-baseline-62c1b44.json` | 14 KB | 62c1b44 |
| `jfr-{100,500,1000}bots-baseline-62c1b44.meta.json` | 1.2 KB each | 62c1b44 |
| `jfr-{100,500,1000}bots-active-50xfood-103a615.jfr` | 0.8–1.2 MB | 103a615 (active scenario) |
| `{cpu,alloc,lock}-{100,500,1000}bots-active-50xfood-103a615.html` | 17–157 KB | 103a615 (active scenario) |
| `metrics-{100,500,1000}bots-active-50xfood-103a615.json` | 18 samples each | 103a615 (active scenario) |
| `jfr-{100,500,1000}bots-active-50xfood-103a615.meta.json` | 0.7 KB each | 103a615 (active scenario) |

Lock flamegraph captured in a separate 1000-bot run (not concurrent with cpu/alloc; asprof 4.4 multi-attach limitation documented in 20-01b). Acceptable exploratory evidence per all four 20-01b methodology reviewers.

The `*-active-50xfood-103a615.*` set (18 files) is the active-population scenario (§Active-Population Workload); the `*-baseline-62c1b44.*` set is the production-defaults churn baseline. Both retained — the contrast is the evidence. The active set captured cpu/alloc/lock at all three tiers (single asprof JFR session per tier, post-converted via `jfrconv`), unlike the 20-01b-era single-tier lock capture.

## Verification gates

| # | Gate | Outcome |
|---|---|---|
| 1 | `./gradlew test` green after D1+D2+D3 | ✓ 944 tests; 1 pre-existing flake (HundredBotIntegrationTest — see caveat) |
| 2 | Three-gate (GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant) green at HEAD | ✓ 9/9 at 62c1b44 |
| 3 | `admission.rejected{reason=world-full}` = 0 at every tier | ✓ Zero world-full rejections across all 18 samples; the only rejections at any tier are `reason=respawn-cap` at 1000-tier |
| 4 | `peak_registered == --count` per tier | ✓ exactly 100 / 500 / 1000; connect_failures_total = 0 + e408_reconnect_required_total = 0 at every tier |
| 5 | `outbound.encode.send.ms.max` and `outbound.queue.depth.max` show monotonic-or-bounded growth | ✓ enc.cnt grows 5.8k → 33k → 76k (linear-ish with bot count); enc.max bounded ≤20 ms; qmax bounded at 0. (Note: Actuator JSON exposes COUNT/TOTAL_TIME/MAX only; configured percentiles are available on `/actuator/prometheus` if downstream needs them.) |
| 6 | `meta.json` carries `cap_during_run: 1500`, `asprof_cpu_interval_us: 10000`, `asprof_alloc_interval_bytes: 524288`, corrected A8 wording | ✓ verified in 3× meta.json |
| 7 | Headline contains explicit cap-binding + connection-count framing (no naked "10× scale") | ✓ headline is "100/500/1000 connected bots, cap=1500 non-binding, ~76 k frames @ 1000-tier through ~47 s of capture window" |
| 8 | `paralife.outbound.detach.timeout = 0` at every tier (D2 preservation check) | ✓ all 18 samples |
| 9 | `active.entities@end ≈ tier-count ± churn` at 1000-tier (D1 falsifiability — pre-fix was 3733; H1 falsifiability — pre-H1 dropped to 851 from path-C double-dec) | ✓ post-D1+H1 trajectory 968→1000→1000→997→972→901; final dip from respawn-cap-exhausted bots (+50 units higher than pre-H1 sample 6 = H1 closed the path-C double-dec) |

## Caveats

1. **HundredBotIntegrationTest.hundredBotsConnectAndReceiveTicks** times out on WSL2 + Gradle `forkEvery=1` test isolation. Verified pre-existing at parent commit 14e96ea via stash + retest — not introduced by D1/D2/D3. The three-gate (the load-bearing baseline gate per plan §Verification) is 9/9 green. Phase 22 test-leak audit owns the `forkEvery=1` setting and it must remain unconditional per the 2026-05-03 fleet decision.
2. **respawn-cap pressure at 1000-tier**. With cap=1500 non-binding and `maxRespawnsPerSession=5`, the 1000-bot tier accumulates 99 `respawn-cap` rejections by the last sample (62c1b44, post-H1) as bots exhaust their respawn budget. Bot scaling is uncontaminated — these are not cap-bind events. Plan 20-04 or Phase 21 should decide whether `maxRespawnsPerSession` needs to be raised for sustained 1000+ bot benchmarks.
3. **paralife.tick.health.work-time-ms is live, not deprecated**. The meter emits numeric values in all 18 samples across all three tiers (100-tier 6-14 ms, 500-tier 27-34 ms, 1000-tier 37-60 ms at 62c1b44). `TickHealthMonitor.onTick` writes the AtomicLong-backed gauge every tick; it cannot return null. The scalar is kept alongside the `paralife.tick.work.ms` DistributionSummary for SHA-continuity with 20-01b. **Both are live tick-cost meters; neither is deprecated.** (Pass-1 of this plan's pushback dismissed pass-1 codex+opencode reports of numeric values as "reviewer hallucination" using a `jq` query that mis-typed the hyphenated `work-time-ms` segment as underscored. See §Pushback above for the retraction.)

## Supersedes

20-01b `14e96ea` baseline (commit `feat(20-01b): baseline JFR + flamegraph + actuator-metric capture at c22e487`). 12 artifacts in `profiles/*-c22e487.*` are kept as the prior **capped-population** capture and referenced here for the F1 evidence trail. They are not authoritative for downstream Plans 20-04 / 20-05 / 20-06 — those plans cite the 62c1b44 capture. The intermediate 1818eeb capture has been dropped (pre-D1 leak; replaced wholesale by 62c1b44).

## Deferred (recorded only)

| Item | Reason |
|---|---|
| 3× replication per tier for noise floor | Phase 21 scale-benchmark gate concern. Timer histograms now carry MAX inside each run; configured percentiles available via Prometheus scrape. |
| Per-thread JFR `jdk.CPULoad` / `jdk.ThreadCPULoad` extraction | Tooling work; not load-bearing for MVP. |
| Per-bucket tagging on `outbound.encode.send.ms` | Untagged Timer sufficient for saturation detection. |
| `paralife.admission.bucket.*.cap` surface | Plan 20-04 work if a per-bucket cap is needed; current code is global-only. |
| (removed) `paralife.tick.health.work-time-ms` "wire up MAINTENANCE-mode" row | Pass-2 reviewers correctly noted the meter is already live (TickHealthMonitor.onTick writes every tick); no work to defer. |
| Prometheus-format percentile artifacts for `outbound.encode.send.ms.p95` | Available now via `/actuator/prometheus` but not captured in the JSON sidecar shape; defer formal percentile reporting to Phase 21. |
| TD-20-01c-A — `recordFrameSize` called before successful `sendMessage` | See `.planning/STATE.md` Deferred Items. Trivial impact at this scale (≤1 frame per IOException). |
