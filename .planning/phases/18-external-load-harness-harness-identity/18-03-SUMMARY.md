---
phase: 18
plan: "03"
subsystem: admission-metrics
tags: [metrics, attribution, micrometer, tdd, phase-18]
dependency-graph:
  requires: [18-01, 18-02]
  provides: [two-tag-metrics, per-bucket-gauges, attribution-tagger-spring-bean]
  affects: [AdmissionMetrics, WorldWebSocketHandler, ActionResolver, SessionRegistry]
tech-stack:
  added: [AdmissionBeansConfig]
  patterns: [per-bucket-gauge-lifecycle, meterfilter-cardinality-cap, explicit-entityid-param]
key-files:
  created:
    - src/main/java/com/paralife/admission/AttributionTagger.java
    - src/main/java/com/paralife/admission/AdmissionBeansConfig.java
    - src/test/java/com/paralife/admission/AttributionTaggerTest.java
    - src/test/java/com/paralife/admission/AttributionTagTest.java
    - src/test/java/com/paralife/admission/CardinalityCapTest.java
    - src/test/java/com/paralife/admission/AdmissionMetricsLifecycleTest.java
  modified:
    - src/main/java/com/paralife/admission/AdmissionMetrics.java
    - src/main/java/com/paralife/admission/AdmissionConfig.java
    - src/main/java/com/paralife/websocket/SessionRegistry.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/admission/AdmissionGateTest.java
    - src/test/java/com/paralife/admission/AdmissionMetricsTest.java
    - src/test/java/com/paralife/admission/OutboundSenderTest.java
    - src/test/java/com/paralife/admission/ResumeTokenRegistryTest.java
    - src/test/java/com/paralife/admission/TickHealthMonitorTest.java
decisions:
  - "D-11: AttributionTagger is the single source of truth for source/harness tag derivation; overflow folding lives there, not in MeterFilter"
  - "D-12: maintenance and tick-work gauges stay scalar (no source/harness tags); all other admission/backpressure metrics gain two-tag attribution"
  - "MeterFilter cap set to cap+1 to allow the overflow bucket to register alongside cap legitimate harness buckets"
  - "@Autowired on primary 4-arg AdmissionMetrics constructor disambiguates for Spring when back-compat 1-arg constructor coexists"
  - "AdmissionBeansConfig @Configuration factory produces AttributionTagger Spring bean; constructor params (maxCardinality, tickEngine) not directly injectable"
metrics:
  duration: "~3 hours (two sessions, context compaction mid-execution)"
  completed: "2026-04-28"
  tasks-completed: 2
  files-changed: 17
---

# Phase 18 Plan 03: Metric Attribution (AdmissionMetrics + AttributionTagger) Summary

Server-side metric attribution via `AttributionTagger` + rewritten `AdmissionMetrics` with per-bucket active/stalled gauges, cardinality-capped overflow folding, and Round 2 snapshot-loss fix for markStalled ordering.

## What Was Built

### Task 1: AttributionTagger + AdmissionConfig.AttributionConfig

`AttributionTagger` is the single source of truth for `source`/`harness` tag derivation from WebSocket session attributes. Key design choices:

- `foldHarnessIfOverCap(String)`: synchronized `slotLock` block eliminates the cap-boundary race (two threads both observe `size < cap` and both insert). After cap is reached, all new ids fold to `"overflow"`. Warn-once log fires HERE (not in MeterFilter) because the raw 65th harness id is still in scope.
- `AtomicBoolean overflowWarned`: ensures exactly one WARN line across all overflow events.
- `AdmissionConfig.AttributionConfig`: new 5th field on `AdmissionConfig` record; `defaults()` uses cap=64. yml adds `paralife.admission.attribution.max-harness-cardinality: 64`.

### Task 2: AdmissionMetrics rewrite + call-site wiring

**AdmissionMetrics (4-arg constructor, @Autowired):**
- `ConcurrentHashMap<Tags, AtomicInteger> activeBuckets` / `stalledBuckets`: per-bucket gauge lifecycle. `Gauge.builder(...).tags(t).register(registry)` lazily creates gauges on first `incActiveBucket`/`incStalledBucket` call for that tag combination.
- `ConcurrentHashMap<String, Tags> bucketTagsByEntityId`: snapshot map keyed by entityId, populated at `incActiveBucket`/`incStalledBucket`. Used by `lookupBucketTags(entityId)` for grace-expiry reapers and `cleanupByEntityId` callers that have no session.
- `MeterFilter.maximumAllowableTags(..., cap+1, deny())`: cap+1 (not cap) to leave room for the `harness=overflow` bucket alongside the cap legitimate harness buckets.
- `@Deprecated` no-op `setActiveEntities`/`setStalledSessions`: back-compat for any remaining callers.

**Round 2 amendments applied:**
- **Claude HIGH (snapshot-loss fix)**: `incStalledBucket(WebSocketSession, String entityId)` takes explicit entityId. `WorldWebSocketHandler.markStalled` reads entityId BEFORE `attrs.remove(ATTR_ENTITY_ID)`, then calls `incStalledBucket(session, entityId)`, THEN removes. Without this ordering, `bucketTagsByEntityId` would receive null and grace-expiry reaper would have no Tags to decrement.
- **Claude MEDIUM (rebind path)**: `WorldWebSocketHandler` rebind path calls `decStalledBucketByTags(lookupBucketTags(rebind.entityId()))` only — does NOT re-increment active bucket. Active was already incremented at the original Allow and stays incremented.
- **Codex HIGH (SessionRegistry.getById O(1))**: `SessionRegistry.getById(String)` returns `sessions.get(sessionId)` — O(1) backed by existing `ConcurrentHashMap`. `ActionResolver.queueAction` uses it instead of linear scan.

**AdmissionBeansConfig**: new `@Configuration` class producing the `AttributionTagger` Spring bean. Without this, Spring couldn't auto-wire `AttributionTagger` into `AdmissionMetrics` (it has non-injectable constructor params: maxCardinality from config, TickEngine ref).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] MeterFilter cap must be cap+1 for overflow bucket**
- **Found during:** Task 2 GREEN — `CardinalityCapTest.sixtyFifthAndSixtysSixthHarnessFoldToOverflow` failed
- **Issue:** `MeterFilter.maximumAllowableTags(..., cap, deny())` blocked the `harness=overflow` gauge registration when cap (64) unique harness ids were already registered. The 65th registration attempt (with `harness=overflow`) was denied, leaving the overflow gauge null.
- **Fix:** Changed filter cap to `cap + 1` so the `overflow` bucket can register alongside the 64 legitimate harness buckets. The tagger ensures no more than cap+1 distinct harness values ever reach the registry.
- **Files modified:** `AdmissionMetrics.java`
- **Commit:** 877dfcd

**2. [Rule 2 - Missing critical functionality] @Autowired on primary constructor**
- **Found during:** Task 2 GREEN — all Spring integration tests failed with `BeanInstantiationException: No default constructor found`
- **Issue:** `AdmissionMetrics` has two constructors (4-arg primary + 1-arg back-compat). Spring Boot cannot auto-select the primary constructor when multiple exist — it attempts a no-arg constructor, fails.
- **Fix:** Added `@Autowired` on the 4-arg constructor to disambiguate.
- **Files modified:** `AdmissionMetrics.java`
- **Commit:** 877dfcd

**3. [Rule 2 - Missing critical functionality] AdmissionBeansConfig for AttributionTagger Spring bean**
- **Found during:** Task 2 GREEN — after @Autowired fix, Spring still couldn't wire `AttributionTagger` (not a Spring bean)
- **Issue:** `AttributionTagger` needs `maxCardinality` (from `AdmissionConfig`) and `TickEngine` reference, so it cannot be `@Component`. No `@Bean` factory existed.
- **Fix:** Created `AdmissionBeansConfig.java` — a `@Configuration` class with a `@Bean attributionTagger(AdmissionConfig, TickEngine)` method.
- **Files modified:** `AdmissionBeansConfig.java` (created)
- **Commit:** 877dfcd

**4. [Rule 1 - Bug] ResumeTokenRegistryTest.gaugeFiltersToStalledOnly scalar gauge assertion**
- **Found during:** Task 2 GREEN — `MeterNotFoundException` at `ResumeTokenRegistryTest.java:196`
- **Issue:** Test used `meterReg.get(M_STALLED_SESSIONS).gauge().value()` expecting a scalar gauge that no longer exists (replaced by per-bucket gauges). `ResumeTokenRegistry` no longer drives the gauge directly.
- **Fix:** Replaced with `registry.stalledSize()` assertion — tests the same invariant via the registry's internal count.
- **Files modified:** `ResumeTokenRegistryTest.java`
- **Commit:** 877dfcd

### Pre-existing Out-of-Scope Issues

**PopulationDynamicsTest flaky**: Stochastic simulation test; MEMBRANE population occasionally goes to 0 in short-run scenarios. Pre-existing issue, confirmed not caused by Phase 18 changes. Documented to deferred-items.

## TDD Gate Compliance

| Gate | Commit | Status |
|------|--------|--------|
| Task 1 RED | 6cce086 | PASS — `AttributionTaggerTest` failing before implementation |
| Task 1 GREEN | caebccc | PASS — all AttributionTagger tests pass |
| Task 2 RED | 7e8be57 | PASS — `AttributionTagTest`, `CardinalityCapTest`, `AdmissionMetricsLifecycleTest` failing |
| Task 2 GREEN | 877dfcd | PASS — all 3 new test classes + 6 updated test files pass; full suite BUILD SUCCESSFUL |

## Known Stubs

None. All per-bucket gauge lifecycle paths are wired end-to-end. No placeholder values.

## Self-Check: PASSED
