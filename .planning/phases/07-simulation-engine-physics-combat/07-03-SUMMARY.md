---
phase: "07"
plan: "03"
---

# T03: Integration test proving population dynamics — deaths, decay, nutrient spawning all verified.

> Integration test proving population dynamics — deaths, decay, nutrient spawning all verified.

## What Happened
---
id: T03
parent: S02
milestone: M002
key_files:
  - src/test/java/com/paralife/engine/SimulationIntegrationTest.java
  - src/main/java/com/paralife/world/WorldGrid.java
key_decisions:
  - Added WorldGrid.clear() for test isolation between @SpringBootTest methods sharing context
  - Integration test uses auto-start=false to drive ticks manually for deterministic assertions
duration: ""
verification_result: passed
completed_at: 2026-04-01T14:34:01.110Z
blocker_discovered: false
---

# T03: Integration test proving population dynamics — deaths, decay, nutrient spawning all verified.

**Integration test proving population dynamics — deaths, decay, nutrient spawning all verified.**

## What Happened

Created integration test with 3 tests: populationChangesOverTime (seeds 90 particles, runs 50 ticks, verifies deaths and nutrients), deadEntitiesActuallyRemoved (low-energy particle dies after 2 ticks), nutrientsAppearOnEmptyCells (empty grid gains nutrients over ticks). Fixed test pollution by adding WorldGrid.clear() and @BeforeEach reset.

## Verification

./gradlew test --rerun — BUILD SUCCESSFUL (98 tests, 0 failures, run twice for stability)

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew test --rerun` | 0 | ✅ pass | 16700ms |
| 2 | `./gradlew test --rerun` | 0 | ✅ pass | 14000ms |


## Deviations

Added WorldGrid.clear() method to support test isolation — grid state was polluted between test methods sharing the same Spring context.

## Known Issues

None.

## Files Created/Modified

- `src/test/java/com/paralife/engine/SimulationIntegrationTest.java`
- `src/main/java/com/paralife/world/WorldGrid.java`


## Deviations
Added WorldGrid.clear() method to support test isolation — grid state was polluted between test methods sharing the same Spring context.

## Known Issues
None.
