---
phase: 20-connection-multiplexing-runtime-tuning
plan: 01c
type: execute
wave: 2
depends_on: [20-01, 20-01b]
supersedes: 20-01b
files_modified:
  - src/main/java/com/paralife/admission/AdmissionMetrics.java
  - src/main/java/com/paralife/admission/OutboundSender.java
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-{HEAD_SHA}.jfr
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-{HEAD_SHA}.jfr
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-{HEAD_SHA}.jfr
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-{HEAD_SHA}.html
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-{HEAD_SHA}.html
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-{HEAD_SHA}.html
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-{HEAD_SHA}.meta.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-{HEAD_SHA}.meta.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-{HEAD_SHA}.meta.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-{HEAD_SHA}.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-{HEAD_SHA}.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-{HEAD_SHA}.json
autonomous: false
requirements: [SCALE-09]
---

# Plan 20-01c — Re-anchor baseline at HEAD with corrected scope + saturation metrics

## Why this plan exists

The multi-agent methodology review of Plan 20-01b returned 2 RED / 2 YELLOW. Three substantive findings are upheld:

| Finding | Evidence |
|---|---|
| **F1** — `admission.cap: 256` silently bounds workload. At `--count 1000` the 100 / 500 / 1000 tiers measured ~100 / 256 / 256 admitted entities, not a 10× span. | `application.yml:65`, `AdmissionGate.java:140-150`, `BotClient.java:422-426`, `BotRegistry.java:66`, `TickBroadcaster.java:201`. |
| **F2** — `paralife.tick.health.work-time-ms` excludes per-session encode + send. | `TickEngine.java:110-119` brackets `eventPublisher.publishEvent(event)` only; `PerceptionCodec.encode` + `session.sendMessage` run on per-session VTs in `OutboundSender.drainLoop:273-313`. |
| **F6** — `c22e487` baseline is stale vs HEAD. | `f6da129` between c22e487 and HEAD modified `SimulationEngine.processDeaths` ordering — sess-9 / sess-21 digests legitimately differ. |

`paralife.tick.work.ms` DistributionSummary (TickEngine.java:44-48), `admission.rejected{reason}`, `admission.active.entities`, `backpressure.stalled.sessions`, `outbound.frame.size.bytes` are already registered but were not scraped by the 20-01b sidecar. Two real gaps: aggregate per-session queue-depth and an encode+send Timer.

## Approach

One execution loop ships: (A) source instrumentation, (B) re-baseline against HEAD with `cap=1500`, (C) docs that supersede 20-01b.

### Phase A — Source instrumentation
- A1: Add `OutboundSender.peakQueueDepth()`; register `paralife.outbound.queue.depth.max` gauge in `AdmissionMetrics` via a new `registerOutboundQueueDepthMaxGauge(IntSupplier)` method invoked from `OutboundSender`'s constructor.
- A2: Bracket `PerceptionCodec.encode` + `synchronized(session) { session.sendMessage(...) }` in `OutboundSender.drainLoop` with a `Timer.Sample`; register `paralife.outbound.encode.send.ms` with `publishPercentiles(0.5, 0.95, 0.99)` matching the `tick.work.ms` shape.
- A3: `./gradlew test` green. New meters are additive; no existing surface modified.

### Phase B — Re-baseline at HEAD (cap=1500 JVM override)
Three tiers (100 / 500 / 1000 bots), 200 s steady-state, ramp-up rate:50.

JVM boot flag adds `-Dparalife.admission.cap=1500` alongside the existing 20-01b ritual. `application.yml` default unchanged (D-20 invariant — `paralife.admission.backpressure.outbound-queue-size` is the only D-20 surface).

Per-tier sidecar fields (all already-registered, just unscraped):
- `paralife.tick.work.ms` count/total/p50/p95/p99/max
- `paralife.admission.active.entities` (sum across buckets)
- `paralife.admission.rejected{reason=world-full}` count (expected 0 at cap=1500)
- `paralife.backpressure.stalled.sessions` (sum across buckets)
- `paralife.outbound.frame.size.bytes` count/p95/max
- `paralife.outbound.queue.depth.max` (new from A1)
- `paralife.outbound.encode.send.ms` count/p95/p99 (new from A2)
- `paralife.outbound.detach.timeout` (continuity with 20-01b)
- `paralife.tick.health.work-time-ms` (deprecated scalar, retained for cross-baseline diff)

Sampling shape: 6×5s during steady-state — DistributionSummary now carries the tail-latency signal.

Harness `--report-out` captures `peakRegistered`, `currentRegistered`, `connectFailuresTotal`, `syncsReceivedTotal`, `e408ReconnectRequiredTotal` per tier (`LoadHarness.java:423-429`).

JFR + flamegraphs sequential per the 20-01b discovery. Lock flamegraph from a separate 1000-bot run — accepted exploratory evidence per all four reviewers.

### Phase C — Docs
- `20-01c-SUMMARY.md` replaces `20-01b-SUMMARY.md` headline. Per-tier table includes admitted entities, `tick.work.ms.p95`, `encode.send.ms.p95`, `queue.depth.max`, `frame.size.bytes.p95`, harness `peakRegistered` + `connectFailuresTotal`.
- Soften lock flamegraph claim. State explicitly that `tick.health.work-time-ms` measures only synchronous tick dispatch; per-connection encode+send scaling now visible via `outbound.encode.send.ms`.
- Mark 14e96ea baseline superseded; link to it as the prior "capped-population" capture but flag the f6da129 SimulationEngine delta as the disqualifier.
- `meta.json` carries `cap_during_run: 1500` + corrected A8 wording (G1 default; `-XX:+UseZGC -XX:+ZGenerational` opt-in).
- `.planning/STATE.md` + `.planning/ROADMAP.md` updated via gsd-sdk.

## Deferred (recorded in deferred-items, not promoted to 20-01c)

| Item | Reason |
|---|---|
| 3× replication per tier for noise floor | Phase 21 scale-benchmark gate concern. DistributionSummary now carries p95/p99 inside each run. |
| Per-thread JFR `jdk.CPULoad` / `jdk.ThreadCPULoad` extraction | Tooling work for existing JFRs; not load-bearing for MVP. |
| Per-bucket tagging on `outbound.encode.send.ms` | Untagged version sufficient for saturation detection. |
| `tools/async-profiler-bootstrap.md` rewrite | Pushback — doc "concurrent" refers to background-process parallelism, not single-asprof multi-attach. |
| Replace lock flamegraph cross-run capture with same-run | Pushback — all four reviewers accept cross-run capture as adequate. |

## Verification

1. `./gradlew test` green after A1/A2.
2. Three-gate (`GoldenTraceEquivalenceTest` + `GoldenTraceWithActionsTest` + `LiveEntityRegistryInvariantTest`) green at HEAD pre-capture.
3. `admission.rejected{reason=world-full}` count = 0 at every tier (cap=1500 not binding).
4. `admission.active.entities` ≈ `--count` per tier (ramp-up + churn tolerance).
5. `outbound.encode.send.ms.p95` and `outbound.queue.depth.max` show monotonic-or-bounded growth across 100 / 500 / 1000.
6. `meta.json` carries `cap_during_run: 1500` and the corrected A8 wording.
7. SUMMARY headline contains no naked "10×" without an entity-count qualifier.
8. `paralife.outbound.detach.timeout` count = 0 at every tier (instrumentation preservation check).
