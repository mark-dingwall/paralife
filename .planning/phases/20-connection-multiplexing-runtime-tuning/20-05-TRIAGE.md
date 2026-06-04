null-result: codec ≤1.8% CPU (84/4792 samples), StringBuilder alloc ≤0.11% (5/4501 TLAB events), 0 VirtualThreadPinned events, 0 SocketRead events — all signals below RESEARCH Pattern 5 thresholds; system at performance floor

---

# Plan 20-05 Triage: JFR Signal Analysis

## Triage outcome

**Outcome 3 — Documented null-result** per D-21.

No codec hot path, no pinning storm, no SocketRead long-tail, and no plausibly-safe
runtime-knob tightening with JFR evidence. The system is at the performance floor
for the work Plan 5 is permitted to do. The SCALE-08 deliverable is the tuning
surface from Plans 2/3/4 plus this documented null-result showing equivalence at
1000-bot active-50xfood tier.

## Primary source: active-50xfood baseline, 1000-bot tier (SHA `103a615`)

JFR file: `profiles/jfr-1000bots-active-50xfood-103a615.jfr`
Metric sidecar: `profiles/metrics-1000bots-active-50xfood-103a615.json`
JFR duration: 96 s (delay=15s, recording starts during harness load, captures
transport-overhead dominated steady-state per 20-01c-SUMMARY directive)

Primary source per 20-01c-SUMMARY:144-147: active-50xfood scenario is the
transport-overhead hot-path evidence target for Phase 20. The churn baseline
(`62c1b44`) was not used as the primary codec-hot-path triage source.

## Decision-tree evaluation (REVISED per D-21, precedence order: 1 → 4 → 2 → 3)

### Step 1: Hot codec signal present?

**Signal: `StringBuilder` allocation in `PerceptionCodec.encode` > 5% of total alloc**

- Total TLAB allocation events in active baseline JFR: 4,501
- `java.lang.StringBuilder` TLAB allocations total: 29 (0.64% of total)
- `java.lang.StringBuilder` TLAB allocations IN PerceptionCodec context: 5 (0.11%)
- 5% threshold requires: >= 225 events
- Result: **5 events — BELOW 5% threshold. Signal absent.**

**Signal: `StringBuilder.expandCapacity` in `encodeTick` stack**

- No `StringBuilder.expandCapacity` events observed in the active JFR
- The initial capacity of 128 (`new StringBuilder(128)` at `PerceptionCodec.java:56`)
  is sufficient for typical tick frames at active-50xfood load
- Result: **Signal absent.**

**Signal: `String.getBytes(UTF_8)` in `OutboundSender.drainLoop` > 3% CPU**

- OutboundSender `drainLoop` accounted for 158 samples (3.3%) of 4,792 total
  ExecutionSample events. This includes the full drainLoop, not just getBytes.
- No samples show `String.getBytes` as a top frame in OutboundSender drainLoop stacks
- Result: **3% CPU threshold for getBytes specifically not met. Signal absent.**

**Signal: `Base64Codec.INT_TO_CHAR` lookups dominating `encode` CPU**

- No ExecutionSample events in `Base64Codec` observed in the active JFR
- `Base64Codec.encodeDigit` is the only codec path; zero samples hit it as top frame
- Result: **Signal absent.**

**PerceptionCodec CPU total:**

- CPU samples (ExecutionSample): 84/4,792 = 1.75% — below the 3% CPU threshold
- Breakdown: `encode()` 58 samples, `encodeSBlock` 24, `encodeTick` 25,
  `encodeCellEntry` 18, `encodeVarBase64` 5
- TickBroadcaster.buildTickFrame/buildCellEntries dominates at 291 samples (6.1%)
  but is out of scope for codec opts

**Conclusion for Step 1: No hot codec signal present. Outcome 1 (codec opts ship) does NOT fire.**

### Step 2: Pinning-dominates check?

**Signal: `jdk.VirtualThreadPinned` count > 100/min @ 20ms threshold**

- Active JFR summary shows: jdk.VirtualThreadPinned count = **0 events** across
  the full 96-second recording window
- Rate: 0/min — far below the 100/min threshold
- JFR lock contention (`jdk.JavaMonitorEnter`): 12 events, all in EPollSelectorImpl
  (JDK NIO selector internals, not `synchronized(session)` VT-pinning sites)

**Conclusion for Step 2: No dominant pinning. Outcome 4 (dominant pinning with backlog-handoff) does NOT fire.**

### Step 3: Optional runtime-knob tightening with JFR evidence?

**Candidate: `paralife.runtime.jetty.idle-timeout-ms`**
- Signal required: `jdk.SocketRead` events showing long idle tails at default 60000ms
- Actual SocketRead events in active JFR: **0**
- Result: **No SocketRead signal. Knob not justified.**

**Candidate: `paralife.runtime.jetty.output-buffer-size`**
- Signal required: allocation flamegraph showing oversized buffer churn
- Actual: `java.nio.HeapByteBuffer` TLAB allocations: 82 events (1.8% of 4,501 total)
- Frame size from metric sidecar (COUNT=19,872, TOTAL=2,186,702 bytes): mean ~110 bytes, MAX 157 bytes
- Current `output-buffer-size=4096` is 26x the measured MAX frame size
- However: the 82 HeapByteBuffer TLAB events do not specifically anchor to Jetty
  output-buffer churn; Jetty likely reuses buffers. The allocation signal is sub-threshold.
- Result: **Below 5% alloc threshold. Knob not justified by JFR evidence alone.**

**Candidate: `paralife.admission.backpressure.outbound-queue-size`**
- Signal required: `paralife.outbound.detach.timeout` >= 10 events/min sustained
  OR >= 3 events at same harness phase (Pass-3 Concern #28)
- Active baseline metric sidecar (`metrics-1000bots-active-50xfood-103a615.json`):
  detach.timeout COUNT final value = **0** (all 18 samples show 0)
- Result: **0 detach.timeout events. Knob not justified.**

**Conclusion for Step 3: No JFR-evidenced runtime-knob tightening candidates. Outcome 2 does NOT fire.**

### Step 4: Documented null-result (Outcome 3)

All three outcomes (1, 4, 2) failed to fire. The baseline JFR shows:
- No codec hot path (all codec signals below RESEARCH Pattern 5 thresholds)
- No VT pinning storm (0 VirtualThreadPinned events)
- No plausibly-safe runtime-knob tightening (0 SocketRead, 0 detach.timeout events)

**Outcome 3 (documented null-result) is the correct disposition per D-21.**

## Performance-floor evidence summary

| Signal | Threshold | Measured | Floor reached? |
|--------|-----------|----------|----------------|
| `jdk.VirtualThreadPinned` count | > 100/min @ 20ms | 0 events (0/min) | Yes |
| `jdk.GCPhasePause` mean | > 1ms | No GCPhasePause events in JFR | Yes (no GC pauses captured) |
| PerceptionCodec CPU % | > 3% CPU | 1.75% (84/4,792 samples) | Yes |
| StringBuilder alloc in codec | > 5% alloc events | 0.11% (5/4,501 events) | Yes |
| `jdk.SocketRead` events | Any idle long-tail | 0 events | Yes |
| `paralife.outbound.detach.timeout` | >= 10/min or >= 3 same-phase | 0 (18-sample window) | Yes |

## Baseline metric values (for Task 5.2 delta computation)

**Source: `metrics-1000bots-active-50xfood-103a615.json`** (18 samples x 5s window)

`paralife.tick.health.work-time-ms`:
- mean: 49.5 ms
- min: 34 ms
- max: 84 ms
- n: 18 samples

`paralife.outbound.detach.timeout`:
- final COUNT: 0
- all samples: 0 (no detach events in baseline window)

Noise-floor convention per D-21: +/-5% of baseline mean OR +/-1 sigma, whichever is larger.
- 5% of mean = +/-2.48 ms (of 49.5 ms)
- sigma for tuned (n=6) will be wider than baseline sigma (n=18) — bias toward +/-5% mean
  branch per meta.json R2 caveat

## RESEARCH Pitfall 4 mitigation note

No codec opts to apply. Task 5.1 will run the three-gate stack twice in-suite
(equivalence proof — confirming the codebase is in the same green state before Plan 5 started)
and the full suite once. No revert cycle needed (no code changes).

## Disabled-test inventory

Three P22 tests carry `@Disabled`:
- TD-22-A: `MetabolismIntegrationTest` (read-lock starvation under tick-loop write pressure)
- TD-22-B: `EncodeDeflatePerformanceGateTest` (real perf regression)
- TD-22-C: `PopulationDynamicsTest` (probabilistic flat-line, needs RNG pinning or tolerance widening)

HundredBotIntegrationTest carries NO `@Disabled` annotation (active suite per M001 100-bot
success criteria). TD-22-D label was a documentation mis-classification per R3 H1 correction.
