---
phase: 17
plan: "04"
subsystem: admission
tags: [tick-health, hysteresis, admission, gate, metrics]
dependency_graph:
  requires: [17-01]
  provides: [TickHealthMonitor.isOverloaded, TickEngine.getLastTickWorkMs, TickEngine.currentTick, AdmissionMetrics]
  affects: [17-03, 17-05, 17-06, 17-07]
tech_stack:
  added: [AdmissionMetrics (Micrometer Gauge / AtomicLong), TickHealthMonitor (Spring @EventListener)]
  patterns: [rolling-window ring buffer, hysteresis state machine, window-fill guard, @Order(Integer.MAX_VALUE) post-broadcast listener]
key_files:
  created:
    - src/main/java/com/paralife/admission/AdmissionMetrics.java
    - src/main/java/com/paralife/admission/TickHealthMonitor.java
    - src/test/java/com/paralife/admission/TickHealthMonitorTest.java
  modified:
    - src/main/java/com/paralife/engine/TickEngine.java
decisions:
  - "Strict comparators (mean > highThreshold, mean < lowThreshold) chosen — boundary samples do NOT flip state. Plan 11 integration tests must use mean values strictly outside the band."
  - "AdmissionMetrics created as Rule 2 deviation (Wave 1 did not produce it; TickHealthMonitor depends on it as its gauge feed)."
  - "currentTick written BEFORE publishEvent so same-tick listeners read the in-flight tick number. lastTickWorkMs written AFTER publishEvent for N-1 semantics."
metrics:
  duration: "~12 minutes"
  completed: "2026-04-27"
  tasks_completed: 1
  tasks_total: 1
  files_created: 3
  files_modified: 1
---

# Phase 17 Plan 04: Tick-Health Monitor Summary

Rolling-window hysteresis admission gate with window-fill guard, `TickEngine` volatile getters, and `AdmissionMetrics` gauge feed.

## Tasks Completed

| # | Name | Commit | Files |
|---|------|--------|-------|
| 1 | Add lastTickWorkMs + currentTick to TickEngine + TickHealthMonitor + tests | 456255a | TickEngine.java, AdmissionMetrics.java, TickHealthMonitor.java, TickHealthMonitorTest.java |

## What Was Built

**`TickEngine` (additive changes only):**
- `volatile long lastTickWorkMs` — written after `publishEvent` returns; exposes `getLastTickWorkMs()` with Javadoc clarifying N-1 sampling semantics.
- `volatile long currentTick` — written before `publishEvent` so same-tick `@EventListener` readers (Plan 07 `markStalled`, Plan 05 `ResumeTokenRegistry`) see the dispatched tick number; exposed via `currentTick()`.

**`AdmissionMetrics` (new — Rule 2 deviation, see below):**
- Registers `paralife.tick.health.work-time-ms` Micrometer gauge backed by an `AtomicLong`.
- `setLastTickWorkMs(long ms)` called each tick by `TickHealthMonitor`.
- `M_TICK_WORK_MS` constant for test/operator reference.

**`TickHealthMonitor` (new `@Component`):**
- `@EventListener @Order(Integer.MAX_VALUE)` — runs after all other tick listeners including `TickBroadcaster @Order(100)`.
- Ring-buffer rolling window of `windowTicks` samples (default 10).
- **Window-fill guard:** gate evaluation deferred until `filled == window.length`. During warm-up, `isOverloaded()` returns false regardless of sample magnitude. A single cold-start GC spike cannot trip overload.
- Hysteresis state machine: `mean > budget * highWaterPct/100.0` flips `overloaded=true`; `mean < budget * lowWaterPct/100.0` flips `overloaded=false`. Strict comparators — boundary values do not trigger transitions.
- `TICK-HEALTH degraded` / `TICK-HEALTH recovered` log markers emitted exactly on state transitions (D-19).
- `isOverloaded()` — volatile read; thread-safe for `AdmissionGate` readers on Jetty threads.

**`TickHealthMonitorTest` — 9 test cases:**
- `initiallyNotOverloaded`
- `singleSpikeBeforeWindowFillsCannotTriggerOverload`
- `warmupSamplesNeverTriggerOverload`
- `overloadFiresWhenRollingMeanExceedsHighWatermark`
- `hysteresisPreventsImmediateRecovery`
- `recoversWhenRollingMeanDropsBelowLowWatermark`
- `noFlappingOnSamplesInBetweenWatermarks`
- `rollingWindowOverwritesOldestSample`
- `gaugeUpdatedDuringWarmupEvenWhenGateDeferred`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] Created AdmissionMetrics**
- **Found during:** Task 1 implementation
- **Issue:** `TickHealthMonitor` depends on `AdmissionMetrics` (for `setLastTickWorkMs` + `M_TICK_WORK_MS` gauge constant) but Wave 1 did not produce this class. It was referenced in the plan's action pseudocode as a given dependency.
- **Fix:** Created `src/main/java/com/paralife/admission/AdmissionMetrics.java` — `@Component` registering the `paralife.tick.health.work-time-ms` gauge (D-18) via Micrometer `Gauge.builder` backed by an `AtomicLong`. No architectural change; this is the exact bean the plan assumed would exist.
- **Files modified:** `src/main/java/com/paralife/admission/AdmissionMetrics.java` (created)
- **Commit:** 456255a

## Key Decisions for Downstream Plans

### Strict-vs-non-strict comparators
`mean > highThreshold` and `mean < lowThreshold` are **strict**. A sample exactly at the boundary does NOT flip state. Plan 11 integration tests that target gate transitions must use sample sequences that produce means **strictly** above/below the watermark percentages.

### Sampling-tick semantics (codex MEDIUM acknowledgement)
`TickEngine.getLastTickWorkMs()` reads the value from tick N-1 during tick N's listener dispatch. This is because `lastTickWorkMs` is written after `publishEvent` returns, so any `@EventListener` on the same `TickEvent` sees the previous tick's value. The `TickHealthMonitor` rolling window is therefore computed over a contiguous but 1-tick-lagged window. This has no correctness impact on hysteresis transitions but operators reading the `paralife.tick.health.work-time-ms` gauge live will see N-1 latency relative to the tick that triggered a `TICK-HEALTH degraded/recovered` log marker.

### currentTick() availability
`TickEngine.currentTick()` is now published and available. Plan 07 (`markStalled`) and Plan 05 (`ResumeTokenRegistry`) MUST consume this getter rather than re-reading `tickCounter` internals or any other TickEngine field. The value is set before `publishEvent` so it is correct for same-tick listeners.

## Known Stubs

None. All gauge values are live (fed each tick). No placeholder data.

## Threat Surface Scan

No new network endpoints, auth paths, file access, or schema changes introduced. All new surface is internal to the JVM (volatile fields, Micrometer gauge, Spring EventListener). Matches threat register T-17-07, T-17-warmup, T-17-misc — all mitigated as designed.

## Self-Check: PASSED

| Item | Status |
|------|--------|
| src/main/java/com/paralife/engine/TickEngine.java | FOUND |
| src/main/java/com/paralife/admission/AdmissionMetrics.java | FOUND |
| src/main/java/com/paralife/admission/TickHealthMonitor.java | FOUND |
| src/test/java/com/paralife/admission/TickHealthMonitorTest.java | FOUND |
| commit 456255a | FOUND |
