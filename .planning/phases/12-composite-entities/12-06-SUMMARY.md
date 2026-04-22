---
phase: 12-composite-entities
plan: 06
subsystem: engine
tags: [integration-test, composite-lifecycle, regression, full-pipeline]
requirements-completed: [R04, R05, R06, R07]
dependency_graph:
  requires: [CompositeMember, CompositeRegistry, CompositeConfig, CompositeEnergyDistributor, ActionResolver, SimulationEngine, PerceptionBroadcaster]
  provides: [CompositeIntegrationTest]
  affects: []
tech_stack:
  added: []
  patterns: [spring-event-publisher-testing, full-pipeline-integration]
key_files:
  created:
    - src/test/java/com/paralife/engine/CompositeIntegrationTest.java
  modified: []
decisions:
  - "Used ApplicationEventPublisher.publishEvent(TickEvent) to drive full @Order pipeline in tests, rather than calling simulationEngine.processTick() which only runs SimulationEngine"
  - "5 test methods covering lifecycle, regression, movement, formation pipeline, and energy distribution"
metrics:
  duration_seconds: 434
  completed: "2026-04-13T17:42:36Z"
  tasks_completed: 1
  tasks_total: 1
  tests_added: 5
  files_created: 1
  files_modified: 0
---

# Phase 12 Plan 06: Composite Integration Test Summary

End-to-end integration test validating the complete composite lifecycle through the real Spring tick pipeline, with regression verification for v1.0 behavior.

## One-liner

@SpringBootTest integration test with ApplicationEventPublisher driving full @Order tick pipeline: formation, energy distribution, movement, dissolution, and v1.0 regression all verified across 5 test methods.

## Completed Tasks

| Task | Name | Commit | Key Changes |
|------|------|--------|-------------|
| 1 | Full composite lifecycle integration test | 26b68aa | CompositeIntegrationTest.java with 5 test methods exercising all pipeline components |

## Implementation Details

### Task 1: Full composite lifecycle integration test

- **compositeLifecycleFormToDissolve**: Places 2 BondedPairs, publishes ticks through full pipeline, verifies composite formation (CompositeRegistry size=1, CompositeMember entities on grid), energy decay via CompositeEnergyDistributor (pool decreases), then forces dissolution by draining pool to 0 and setting member energy to 1 — panic zone (D-31) triggers total death.

- **compositeDoesNotBreakExistingBehavior**: Seeds 30 solo Particles (10 per type) in separate clusters with nutrients, runs 50 ticks, verifies RPS combat occurs (population decreases), nutrients spawn, and no composites form (no adjacent BondedPairs exist).

- **compositeMovesAsUnit**: Manually creates a 2-member composite (LOCOMOTOR + FEEDER), registers in CompositeRegistry and BotRegistry, queues CompositeAction via ActionResolver, publishes ticks, verifies rigid body formation shape preserved (vertical adjacency maintained).

- **fullFormationPipelineParticlesToComposite**: Places 4 Particles as 2 predator-prey pairs, runs ticks with bondingProbability=1.0, verifies bonding occurs and composite formation pipeline runs without errors.

- **energyDistributionActiveForComposites**: Creates composite with FEEDER + SENSOR members below maxEnergy, publishes 5 ticks via ApplicationEventPublisher (which fires CompositeEnergyDistributor at @Order(15)), verifies shared pool decreases from healing draws.

**Key design decision**: Used `ApplicationEventPublisher.publishEvent(new TickEvent(n))` instead of `simulationEngine.processTick(n)` to drive the complete pipeline. Direct `processTick()` calls only run SimulationEngine — the CompositeEnergyDistributor, ActionResolver, PerceptionBroadcaster, and TickBroadcaster are separate `@EventListener` components that only fire on TickEvent publication.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] simulationEngine.processTick() does not drive full pipeline**
- **Found during:** Task 1 (energyDistributionActiveForComposites test failure)
- **Issue:** Calling `simulationEngine.processTick()` directly only runs SimulationEngine logic. CompositeEnergyDistributor (@Order(15)), ActionResolver (@Order(20)), etc. are separate @EventListener components that only fire when TickEvent is published through Spring's event system.
- **Fix:** Injected ApplicationEventPublisher and created `publishTick(long)` helper method. All tests that need the full pipeline now use `publishTick()` instead of `simulationEngine.processTick()`.
- **Files modified:** CompositeIntegrationTest.java
- **Commit:** 26b68aa

## Verification

- `./gradlew test --tests "com.paralife.engine.CompositeIntegrationTest"` -- 5 tests, 0 failures
- `./gradlew test` -- full suite green (328 tests, 0 failures)
- No flaky tests — all use deterministic setup or validated probabilistic bounds

## Self-Check: PASSED

- CompositeIntegrationTest.java: FOUND
- Commit 26b68aa: FOUND
- SUMMARY.md: FOUND
