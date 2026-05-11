---
phase: 20-connection-multiplexing-runtime-tuning
plan: 03
subsystem: runtime-config
tags: [spring-boot, configuration-properties, runtime-tuning, jetty, codec]

requires:
  - phase: 20-01
    provides: async-profiler toolchain bootstrap + profiles/ filename convention
provides:
  - paralife.runtime.app.* binding surface (D-07 layer 3 / D-09)
  - AppRuntimeConfig record with nested OutboundConfig + EncodeConfig sub-records
  - parallel-encode-threshold reservation for Phase 19.1 follow-up (sentinel -1 = disabled)
  - D-20 invariant proved by AppRuntimeConfigTest.d20AlongsideNotMove_admissionBackpressureUntouched
affects: [20-02-jetty-runtime-config, 20-05-codec-opts, 19.1-parallel-broadcast, m5-admin-ui, 999.4-namespace-consolidation]

tech-stack:
  added: []
  patterns: [@ConfigurationProperties record + nested sub-records (mirrors AdmissionConfig); compact-ctor validation with property-key in error messages; @DefaultValue + sentinel-disabled reservation fields]

key-files:
  created:
    - src/main/java/com/paralife/runtime/AppRuntimeConfig.java
    - src/test/java/com/paralife/runtime/AppRuntimeConfigTest.java
  modified:
    - src/main/resources/application.yml

key-decisions:
  - "D-20 alongside-not-move: AppRuntimeConfig does NOT contain or shadow `outbound-queue-size`. Existing AdmissionConfig.BackpressureConfig.outboundQueueSize stays at paralife.admission.backpressure.outbound-queue-size, unchanged. Phase 999.4 owns namespace consolidation."
  - "Pass-2 Concern #7: all 4 fields tagged `[reserved — no effect in Phase 20]`. PerceptionCodec.encode(Frame) takes no capacity argument and Plan 5 forbids the public-API change required to consume frameSizeBudgetBytes; other reserved fields await M5 admin UI / Phase 19.1 follow-up consumers."
  - "EncodeConfig.parallelEncodeThreshold sentinel -1 = disabled; >=0 reserves a future threshold value Phase 19.1 will read."
  - "Pass-3 Concern #22: BindingRoundTrip uses static @Configuration TestApp wrapper (mirrors AdmissionConfigTest) — avoids heavyweight ParalifeApplication-full-context fallback."

patterns-established:
  - "Outer record + nested sub-records under a single @ConfigurationProperties prefix. Each sub-record carries its own compact-ctor validation with property-key in IllegalArgumentException messages so binding failures point to the exact yaml key."
  - "[reserved | live-tunable | launch-only] javadoc + yaml comment tag convention (D-09) to document which fields are wired vs scaffold-only."

requirements-completed: [SCALE-09]

duration: 25min
completed: 2026-05-11
---

# Plan 20-03: AppRuntimeConfig Summary

**Layer 3 of the Phase 20 runtime tuning surface stood up — binding-only, zero behavioural change, D-20 alongside-not-move invariant proved by test.**

## Accomplishments

- `AppRuntimeConfig` record + 2 nested sub-records (`OutboundConfig`, `EncodeConfig`) bound to `paralife.runtime.app.*` via `@ConfigurationProperties` + `@ConstructorBinding` + `@DefaultValue`.
- `application.yml` carries the new `paralife.runtime.app.*` block alongside (not nested under) `paralife.admission.*` — admission key path untouched.
- All 4 fields tagged `[reserved — no effect in Phase 20]` per Pass-2 Concern #7 (frame-size-budget-bytes structurally unconsumable while Plan 5 forbids `PerceptionCodec.encode(Frame)` API change).
- 8 behavior tests green (7 outer + 1 nested @SpringBootTest binding round-trip).
- D-20 invariant test (`d20AlongsideNotMove_admissionBackpressureUntouched`) verifies `AdmissionConfig.defaults().backpressure().outboundQueueSize() == 128` is preserved alongside.
- Admission/Backpressure regression tests + three-gate stack (GoldenTraceEquivalence, GoldenTraceWithActions, LiveEntityRegistryInvariant) all green in-suite per D-11.

## Task Commits

1. **Task 3.1: AppRuntimeConfig record + AppRuntimeConfigTest** — `d735736` (feat)
2. **Task 3.2: paralife.runtime.app.* yaml block + regression suite** — `7af1740` (feat)

## Files Created/Modified

- `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` (created) — record + nested OutboundConfig + EncodeConfig sub-records.
- `src/test/java/com/paralife/runtime/AppRuntimeConfigTest.java` (created) — 7 outer tests + nested BindingRoundTrip @SpringBootTest.
- `src/main/resources/application.yml` (modified) — added `paralife.runtime:` block with `app:` child. `runtime: jetty:` sibling slot is open for Plan 2 to populate.

## D-20 Invariant Test Result

`AppRuntimeConfigTest.d20AlongsideNotMove_admissionBackpressureUntouched` — green (8 tests / 0 failures).
Asserts `AdmissionConfig.defaults().backpressure().outboundQueueSize() == 128` independent of any `AppRuntimeConfig` binding. The grep guard `! grep -qE '[[:space:]]int[[:space:]]+outboundQueueSize\b' AppRuntimeConfig.java` (Pass-3 Concern #23) also passes — no shadow field declared.

## Three-Gate Result

`./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` — exit 0 at SHA `7af1740` on 2026-05-11. Three-gate stack green in-suite per D-11.

## Forward-Compat Notes

- **Phase 19.1 (parallel PerceptionBroadcaster):** `EncodeConfig.parallelEncodeThreshold` is reserved with sentinel `-1 = disabled`. When Phase 19.1 lands, the consumer reads this field; positive values activate parallel encode above the threshold.
- **Phase 999.4 (codec API extension):** `OutboundConfig.frameSizeBudgetBytes` is bound but unconsumable until `PerceptionCodec.encode(Frame)` takes a capacity argument. Plan 5's no-public-API-change rule freezes this in Phase 20.
- **M5 admin UI:** `OutboundConfig.queueWatermarkPct` is bound but no warning emitter is wired in Phase 20. Slow-client watermark surfacing is M5 follow-up.

## Pass-2 / Pass-3 Concern Confirmations

- **Pass-2 Concern #7:** all 4 fields tagged `[reserved — no effect in Phase 20]` in both the record javadoc and yaml inline comments. `grep -c '\[reserved'` returns 5 in each file (1 header + 4 fields).
- **Pass-3 Concern #22:** `BindingRoundTrip` uses `@SpringBootTest(classes = TestApp.class, webEnvironment = NONE)` with static `@Configuration @EnableConfigurationProperties(AppRuntimeConfig.class) TestApp {}` wrapper — mirrors `AdmissionConfigTest`. Avoids the full-`ParalifeApplication` context boot under `forkEvery=1`.
- **Pass-3 Concern #23:** grep guard `! grep -qE '[[:space:]]int[[:space:]]+outboundQueueSize\b' AppRuntimeConfig.java` passes — comment-stripping false-positive removed.

## Deferred Out of This Plan

- Consumer wiring at `OutboundSender.attachSession` / codec hot paths — none in Phase 20. Plan 5 may wire codec consumers but is gated on 20-01b baseline.
- `JettyRuntimeConfig` (Plan 2) — slot reserved under `paralife.runtime:` for sibling addition.
- 20-01b baseline JFR capture — deferred (human-action gate).

## Status

Plan complete. Wave 2 partial (20-01b still gated on operator-driven baseline capture). Phase 20 will park here pending 20-01b.
