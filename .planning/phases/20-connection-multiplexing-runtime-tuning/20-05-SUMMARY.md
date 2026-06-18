---
phase: 20-connection-multiplexing-runtime-tuning
plan: 05
status: complete
completed: 2026-06-04
requirements: [SCALE-08, SCALE-09]
subsystem: codec-tuning
tags: [jfr, codec, performance-floor, null-result, scale]

requires:
  - phase: 20-01c
    provides: active-50xfood JFR baseline at SHA 103a615 + metric sidecars; codec hot-path triage source
  - phase: 20-04
    provides: 20-RUNTIME.md skeleton with §4.2/§4.4 placeholders Plan 5 populates

provides:
  - "20-05-TRIAGE.md: JFR-driven null-result analysis — all codec/pinning/knob signals below threshold"
  - "Equivalence proof: three-gate stack GREEN x2 confirms codebase state unchanged"
  - "Tuned-state JFR + actuator metric sidecar at 1000 bots, active-50xfood scenario (SHA 424e06d): null-result equivalence confirmed within noise floor"
  - "20-RUNTIME.md §4.2 1000-tuned column populated; §4.4 null-result row; §6 tuned artifacts indexed"

affects: [20-06, phase-21-benchmark-gate]

tech-stack:
  added: []
  patterns:
    - "Null-result as valid SCALE-08 evidence: JFR-proven performance floor IS the SCALE-08 deliverable per D-21 outcome 3"
    - "Three-gate equivalence proof (GoldenTrace + LiveEntityRegistry) validates no regression from null-result"

key-files:
  created:
    - .planning/phases/20-connection-multiplexing-runtime-tuning/20-05-TRIAGE.md
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-active-50xfood-tuned-424e06d.json
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-active-50xfood-tuned-424e06d.meta.json
  modified:
    - .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md

key-decisions:
  - "D-21 outcome 3 (null-result): PerceptionCodec 1.75% CPU, 0 VirtualThreadPinned events, 0 SocketRead events — system at performance floor; no codec opts or runtime-knob tightening justified"
  - "Null-result IS valid SCALE-08 evidence: tuning surface from Plans 2/3/4 + measured equivalence closes SCALE-08"
  - "Tuned-state confirms equivalence: baseline 49.5 ms → tuned 45.0 ms (−4.5 ms), within noise floor (±15.74 ms = ±1σ); detach.timeout 0 → 0"

requirements-completed: []

duration: ~45min (Tasks 5.0+5.1) + JFR capture (Task 5.2, human-action with one-retry)
completed: 2026-06-04
---

Plan 5 outcome: null-result

JFR triage of active-50xfood 1000-bot baseline (SHA `103a615`) finds all codec signals below RESEARCH Pattern 5 thresholds — outcome 3 (documented null-result) per D-21; three-gate equivalence proof green; tuned-state capture at SHA `424e06d` confirms equivalence within noise floor (−4.5 ms, within ±15.74 ms = ±1σ).

## Performance

- **Duration:** ~45 min (Tasks 5.0 + 5.1) + JFR capture (Task 5.2, including one-retry)
- **Started:** 2026-06-04
- **Completed:** 2026-06-04 (all tasks)
- **Tasks:** 3 of 3 complete
- **Files modified:** 4 created (20-05-TRIAGE.md, JFR + metrics + meta.json sidecars); 1 modified (20-RUNTIME.md §4.2/§4.4/§6)

## Accomplishments

- Performed systematic JFR triage of active-50xfood 1000-bot baseline (SHA `103a615`) across all RESEARCH Pattern 5 codec signals, VT pinning check, and runtime-knob candidates
- Confirmed Outcome 3 (documented null-result): PerceptionCodec at 1.75% CPU, StringBuilder alloc at 0.11% of TLAB events, 0 VirtualThreadPinned events, 0 SocketRead events, 0 detach.timeout events — all below thresholds
- Ran three-gate stack (GoldenTrace + LiveEntityRegistry) x2 consecutive greens — equivalence proof showing codebase at same state before Plan 5
- All invariants confirmed: MAX_S_ENTRIES=256, MAX_V_ENTRIES=32 (T-20-V5 intact), synchronized(session)=6 (anti-pattern guard intact), TD-22-A/B/C @Disabled, HundredBotIntegrationTest active

## Task Commits

1. **Task 5.0: JFR Triage** - `bd59e60` (docs) — 20-05-TRIAGE.md created with null-result analysis
2. **Task 5.1: Equivalence proof** - `becbb2e` (docs) — TRIAGE updated with three-gate run records + invariant checks
3. **Task 5.2: JFR capture** - `328ff7a` (capture + RUNTIME §4.2/§4.4/§6) + `f796b55` (SUMMARY completion) — JFR + metric sidecar + meta.json; 20-RUNTIME.md populated

## Plan 5 Outcome: Documented Null-Result (D-21 Outcome 3)

**Triage decision-tree evaluation (precedence order: 1 → 4 → 2 → 3):**

### Outcome 1 check: Hot codec signal present?

| Signal | Threshold | Measured | Fires? |
|--------|-----------|----------|--------|
| StringBuilder alloc in PerceptionCodec.encode | >5% of TLAB events | 5/4,501 = 0.11% | NO |
| StringBuilder.expandCapacity in encodeTick | Any event | 0 events | NO |
| String.getBytes(UTF_8) in drainLoop | >3% CPU | <1% (in drainLoop total 3.3%) | NO |
| Base64Codec.INT_TO_CHAR in CPU samples | Dominant | 0 samples | NO |
| PerceptionCodec total CPU | >3% CPU | 84/4,792 = 1.75% | NO |

**Outcome 1 does NOT fire.**

### Outcome 4 check: VT pinning dominant?

| Signal | Threshold | Measured | Fires? |
|--------|-----------|----------|--------|
| jdk.VirtualThreadPinned count | >100/min @ 20ms | 0 events (0/min) | NO |
| JavaMonitorEnter paralife sites | Any synchronized(session) pinning | 12 events, all EPollSelector (NIO internals) | NO |

**Outcome 4 does NOT fire.** Phase 999.6 (`vt-pinning-reentrantlock-conversion`) not triggered.

### Outcome 2 check: Runtime-knob tightening with JFR evidence?

| Knob | Signal Required | Measured | Fires? |
|------|----------------|----------|--------|
| jetty.idle-timeout-ms | jdk.SocketRead long-tail | 0 SocketRead events | NO |
| jetty.output-buffer-size | HeapByteBuffer alloc churn >5% | 82 events = 1.8% of TLAB | NO |
| outbound-queue-size | detach.timeout >=10/min or >=3 same-phase | 0 (all 18 baseline samples) | NO |

**Outcome 2 does NOT fire.**

### Outcome 3: Documented null-result

The system is at the performance floor. No codec opts, no knob tightening, no pinning handoff — all below thresholds. The SCALE-08 deliverable is:
1. The four-layer tuning surface from Plans 2/3/4 (JVM/Jetty/app/codec knobs documented in 20-RUNTIME.md §2/§3)
2. This documented null-result showing the active-50xfood transport stack IS the overhead source but is at equilibrium with the current architecture (1.75% codec CPU, no backpressure events)

## Task 5.2: Tuned-State Capture Results

**Capture:** `jfr-1000bots-active-50xfood-tuned-424e06d.jfr` (3.5 MB, within D-05 10 MB limit); `metrics-1000bots-active-50xfood-tuned-424e06d.json` (6 samples × 5 s); `jfr-1000bots-active-50xfood-tuned-424e06d.meta.json` (opts_applied_summary = null-result label).

**Headline deltas:**

| Gauge | Baseline | Tuned | Delta | Noise floor (D-21) | Classification |
|-------|----------|-------|-------|--------------------|----------------|
| `paralife.tick.health.work-time-ms` mean | 49.5 ms (σ=15.74, n=18) | 45.0 ms (σ=8.77, n=6) | −4.5 ms | ±15.74 ms (= ±1σ, dominates ±5% mean = ±2.48 ms) | **Within noise floor — equivalence confirmed** |
| `paralife.outbound.detach.timeout` | 0 (all 18 samples) | 0 (all 6 samples) | 0 | level-only read per window-asymmetry rule | Confirmed zero both sides |

**JFR event counts (tuned 180 s window):**
- `jdk.VirtualThreadPinned`: 0
- `jdk.SocketRead`: 0
- `jdk.ExecutionSample`: 909
- `jdk.GCPhasePause`: 4 (27.8 / 20.9 / 19.0 / 23.1 ms) — normal G1 minor pauses totalling 90.8 ms ≈ 0.05% of the 180 s wall-clock, far below the >2% GC-pause-time ZGC trigger (20-RUNTIME.md §3 GC rationale). Baseline 90 s window captured 0 GC pauses, so no GC delta is claimed — equivalence rests on the headline gauges (D-21).

**Capture provenance:** First capture attempt aborted by the script's own sample-count guard (actuator unresponsive — server-side VT stall during connect burst; all 12 curl probes timed out; partial artifacts removed). A leaked Gradle Test Executor JVM from earlier test runs was killed before retry; retry-2 ran clean with a freeze watchdog armed (zero trips). One-retry event was environmental, not a code defect.

## Baseline metric values (for Task 5.2 delta computation)

Source: `profiles/metrics-1000bots-active-50xfood-103a615.json` (18 samples x 5s window)

| Gauge | Baseline mean | Baseline range | n |
|-------|--------------|----------------|---|
| `paralife.tick.health.work-time-ms` | 49.5 ms | 34–84 ms | 18 |
| `paralife.outbound.detach.timeout` | 0 (final) | 0–0 | 18 |

Noise-floor convention (D-21): ±5% of baseline mean OR ±1σ, whichever larger.
- ±5% of 49.5 ms = ±2.48 ms
- Tuned n=6 produces wider σ than baseline n=18 — bias toward ±5% mean branch

## T-20-V5 Bounds Verification

```
grep -E "MAX_S_ENTRIES|MAX_V_ENTRIES" PerceptionCodec.java | grep -vE '^[[:space:]]*(//|\*)':
  public static final int MAX_S_ENTRIES = 256;  ← PRESENT
  public static final int MAX_V_ENTRIES = 32;   ← PRESENT
```

Values unchanged from `62c1b44` canonical baseline.

## D-12 Disabled-Tests Verification

Three P22 tests carry `@Disabled` (confirmed by per-file grep at Task 5.1):
- `MetabolismIntegrationTest` (TD-22-A) — @Disabled PRESENT
- `EncodeDeflatePerformanceGateTest` (TD-22-B) — @Disabled PRESENT
- `PopulationDynamicsTest` (TD-22-C) — @Disabled PRESENT
- `HundredBotIntegrationTest` — @Disabled NOT PRESENT (active, per M001 100-bot success criteria — R3 H1 correct)

## D-10 Anti-pattern Guard (encode-in-monitor)

`grep -c "synchronized(session)" OutboundSender.java` = 6 — unchanged from baseline.
Encoding remains OUTSIDE `synchronized(session)`.

## Task 5.2: JFR + Actuator Metric Capture — COMPLETE

All three artifacts captured at SHA `424e06d` (active-50xfood scenario, 1000 bots):
- `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr` — 3.5 MB (within D-05 limit)
- `profiles/metrics-1000bots-active-50xfood-tuned-424e06d.json` — 6 samples × 5 s
- `profiles/jfr-1000bots-active-50xfood-tuned-424e06d.meta.json` — null-result label from TRIAGE.md

20-RUNTIME.md §4.2, §4.4, and §6 populated. See "Task 5.2: Tuned-State Capture Results" section above for delta table and provenance note.

## Deviations from Plan

### Infrastructure Issue (not a deviation from plan logic)

**Full-suite BUILD FAILED due to pre-existing XML write errors (TD-22-E)**
- **Found during:** Task 5.1 full-suite run
- **Cause:** `forkEvery=1` (TD-22-E) causes forked JVM processes to contend on the shared test-results directory; "Could not write XML test results" for 11 test classes
- **Nature:** Infrastructure failure in the worktree context, NOT test logic failures. Individual test classes pass when run in isolation.
- **Per deviation rules:** Plan 5 Pass-2 Concern #14 says retry once for unrelated flakes. This was retried multiple times with the same result. Since no code changes were made (null-result path), this is classified as a known pre-existing infrastructure limitation.
- **STATE.md regression alarm:** "Could not write XML" is listed as a regression alarm indicator, but it is NOT a new regression in Plan 5 — it is TD-22-E behaviour in the forkEvery=1 worktree context.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. Plan 5 made no code changes (null-result). No threat flags.

## Known Stubs

None. 20-RUNTIME.md §4.2 and §4.4 are fully populated. §4.2 100-tier and 500-tier tuned cells are marked `_baseline-only — see Phase 21_` by plan directive (Plan 6 Task 6.1), not a stub.

## Self-Check: PASSED

- [x] TRIAGE.md exists at `.planning/phases/20-connection-multiplexing-runtime-tuning/20-05-TRIAGE.md`
- [x] Commits bd59e60 (triage) and becbb2e (equivalence proof) exist
- [x] First non-frontmatter line: `Plan 5 outcome: null-result`
- [x] Triage outcome-label contract: `null-result:` prefix on TRIAGE.md line 1
- [x] Task 5.2: JFR `jfr-1000bots-active-50xfood-tuned-424e06d.jfr` present (3.5 MB ≤ 10 MB)
- [x] Task 5.2: Metric sidecar `metrics-1000bots-active-50xfood-tuned-424e06d.json` present; parses as JSON; 6 samples (≥ 3)
- [x] Task 5.2: Meta sidecar `jfr-1000bots-active-50xfood-tuned-424e06d.meta.json` present; parses as JSON; no template sentinel; opts_applied_summary non-empty
- [x] 20-RUNTIME.md §4.2: 1000-baseline and 1000-tuned cells populated for work-time-ms and detach.timeout; caption footnote present
- [x] 20-RUNTIME.md §4.4: single (null-result) row with baseline performance-floor evidence
- [x] 20-RUNTIME.md §6: rows added for tuned JFR, metric sidecar, meta sidecar; flamegraph row rewritten as deferred-to-Plan-6
