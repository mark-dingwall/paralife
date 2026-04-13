---
phase: 12-composite-entities
reviewed: 2026-04-14T05:10:00Z
depth: standard
files_reviewed: 29
files_reviewed_list:
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/BotRegistry.java
  - src/main/java/com/paralife/engine/CompositeConfig.java
  - src/main/java/com/paralife/engine/CompositeEnergyDistributor.java
  - src/main/java/com/paralife/engine/CompositeRegistry.java
  - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/websocket/Messages.java
  - src/main/java/com/paralife/websocket/TickBroadcaster.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/main/java/com/paralife/world/Entity.java
  - src/main/resources/application.yml
  - src/test/java/com/paralife/engine/ActionResolverTest.java
  - src/test/java/com/paralife/engine/BotRegistryTest.java
  - src/test/java/com/paralife/engine/CompositeActionTest.java
  - src/test/java/com/paralife/engine/CompositeCombatTest.java
  - src/test/java/com/paralife/engine/CompositeConfigTest.java
  - src/test/java/com/paralife/engine/CompositeDissolutionTest.java
  - src/test/java/com/paralife/engine/CompositeEnergyDistributorTest.java
  - src/test/java/com/paralife/engine/CompositeFormationTest.java
  - src/test/java/com/paralife/engine/CompositeIntegrationTest.java
  - src/test/java/com/paralife/engine/CompositeMovementTest.java
  - src/test/java/com/paralife/engine/CompositePerceptionTest.java
  - src/test/java/com/paralife/engine/CompositeRegistryTest.java
  - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
  - src/test/java/com/paralife/engine/SimulationEngineTest.java
  - src/test/java/com/paralife/websocket/TickBroadcasterTest.java
  - src/test/java/com/paralife/world/CompositeMemberTest.java
  - src/test/java/com/paralife/world/EntityTest.java
findings:
  critical: 2
  warning: 4
  info: 3
  total: 9
status: issues_found
---

# Phase 12: Code Review Report

**Reviewed:** 2026-04-14T05:10:00Z
**Depth:** standard
**Files Reviewed:** 29
**Status:** issues_found

## Summary

Phase 12 introduces composite entities (siphonophore model) -- a significant feature adding formation, movement, combat, energy distribution, perception, and dissolution mechanics. The architecture is sound: `CompositeRegistry` for shared state, `CompositeEnergyDistributor` at `@Order(15)` for passive drain/healing, and extensions to `ActionResolver` and `SimulationEngine` for composite actions and lifecycle.

The prior review's CR-01 (drainEnergy race condition), WR-02/WR-03 (compositeTicksSinceMove initialization/pruning), and WR-04 (secondary bot ghost state on formation) have been fixed. However, the WR-02 fix (initializing compositeTicksSinceMove to 0) introduced a regression that breaks 4 movement tests. Additionally, the prior review's WR-01 (shared pool energy calculation in formation) was implemented correctly in the source code, but the corresponding test assertion was never updated to match -- it still asserts the old, wrong value.

**5 tests are currently failing** (confirmed via `./gradlew test`):
- `CompositeFormationTest.formationSharedPoolEnergyIsSumOfBondedPairs` -- expected 140 but was 70
- `CompositeMovementTest.locomotorVotesMoveComposite` -- null assertion failure
- `CompositeMovementTest.rigidBodyPreservesFormation` -- null assertion failure
- `CompositeMovementTest.movementUpdatesRegistries` -- expected (6,5) but was (5,5)
- `CompositeMovementTest.movementSpeedGate` -- expected (8,7) but was (8,8)

## Critical Issues

### CR-01: WR-02 Fix Regression -- Speed Gate Blocks All First-Tick Movement for Multi-Member Composites

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:581-609`
**Issue:** The WR-02 fix changed `compositeTicksSinceMove` initialization from the implicit `getOrDefault(compositeId, moveInterval)` fallback to an explicit `putIfAbsent(compositeId, 0)` at line 582. Combined with the increment at line 587 (`merge(compositeId, 1, Integer::sum)`), a new composite starts at 0, gets incremented to 1, then hits the speed gate check at line 609: `if (ticksSince < moveInterval)`. For a 2-member composite with 1 LOCOMOTOR: speed=0.5, moveInterval=ceil(1/0.5)=2, ticksSince=1 -- movement is always blocked on the first tick. The composite can only move on tick 2 (when ticksSince reaches 2).

This breaks 4 tests that expect first-tick movement (`locomotorVotesMoveComposite`, `rigidBodyPreservesFormation`, `movementUpdatesRegistries`, `movementSpeedGate`). The original behavior (before the WR-02 fix) used `getOrDefault(compositeId, moveInterval)` which allowed first-tick movement.

**Fix:** Initialize to `moveInterval` instead of `0`, so the first tick passes the speed gate. The WR-02 fix intent was to prevent composites formed *this tick* from immediately moving, but the fix overcorrected by blocking *all* first-tick movement including composites formed on previous ticks:
```java
// Line 581-583: Initialize to moveInterval so first action tick allows movement
for (var composite : compositeRegistry.getAll()) {
    compositeTicksSinceMove.putIfAbsent(composite.getCompositeId(), Integer.MAX_VALUE);
}
```
Using `Integer.MAX_VALUE` ensures a newly tracked composite passes the speed gate on its first tick, then resets to 0 on successful movement (line 616).

### CR-02: Test Assertion Wrong -- formationSharedPoolEnergyIsSumOfBondedPairs Expects 140, Correct Value is 70

**File:** `src/test/java/com/paralife/engine/CompositeFormationTest.java:155`
**Issue:** The test asserts `getSharedPoolEnergy()).isEqualTo(140)` with the comment `// 80 + 60`, expecting all BondedPair energy goes to the shared pool. But `SimulationEngine.java:357-369` correctly splits energy: half to individual member, half to shared pool. With bp1.energy=80 and bp2.energy=60: individualEnergy1=40, individualEnergy2=30, sharedPool=(80-40)+(60-30)=70. Confirmed failing: `expected: 140 but was: 70`.

The test name and comment are misleading -- the shared pool is NOT the sum of BondedPair energies, it's the remainder after individual allocation.

**Fix:** Update the assertion and comment:
```java
assertThat(composite.get().getSharedPoolEnergy()).isEqualTo(70); // remainder: (80-40) + (60-30)
```

## Warnings

### WR-01: Non-Atomic Clear+PutAll in updateAllPositions

**File:** `src/main/java/com/paralife/engine/CompositeRegistry.java:87-89`
**Issue:** `updateAllPositions()` calls `memberPositions.clear()` then `memberPositions.putAll(positions)`. Between these two calls, a concurrent reader (e.g., `PerceptionBroadcaster`) could see an empty map. The class javadoc claims "Thread-safe for concurrent reads" but this window violates that contract. Currently low risk because all pipeline components run on the same event thread, but the concurrent data structures used throughout suggest the intent is to support concurrent reads.

**Fix:** Replace with a non-clearing approach:
```java
public void updateAllPositions(Map<String, Position> positions) {
    memberPositions.putAll(positions);
    memberPositions.keySet().retainAll(positions.keySet());
}
```

### WR-02: Active Drain Return Values Ignored -- Possible Free-Energy Exploit

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:429,485,545,548,618`
**Issue:** `composite.drainEnergy()` returns the actual amount drained (clamped to available pool energy), but the return value is ignored at 5 call sites. When the pool is nearly empty, actions succeed at reduced energy cost. For example, REPRODUCER budding (line 545) deducts `REPRODUCE_ENERGY_COST` from the pool, then line 548 charges the active drain -- but if the pool hit zero from the reproduction cost, the active drain is silently partial. This gives composites free actions when their pool is low.

**Fix:** Either check the return value and adjust success/failure accordingly, or explicitly document this as intended graceful degradation. The most impactful case is the REPRODUCER, which should verify the active drain was fully applied:
```java
int drained = composite.drainEnergy(compositeConfig.reproducerActiveDrain());
// At minimum, log if partial: if (drained < compositeConfig.reproducerActiveDrain()) ...
```

### WR-03: CompositeEnergyDistributor Can Set Negative Energy Before Clamping

**File:** `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:81-92`
**Issue:** Line 81 computes `newEnergy = member.energy() - passiveDrain` which can go negative. If there's no pool energy for healing, the negative value reaches `member.withEnergy(newEnergy)` at line 92. The `withEnergy()` method clamps via `Math.clamp(newEnergy, 0, maxEnergy)`, so the entity is safe. However, the calculation on line 84 (`Math.max(newEnergy, 0)`) suggests the author was aware of the negative possibility and partially addressed it, but inconsistently -- the deficit and heal calculations use the clamped value while the final assignment may pass a negative value.

**Fix:** Clamp before the conditional for clarity:
```java
newEnergy = Math.max(member.energy() - passiveDrain, 0);
```

### WR-04: Composite Formation Only Assigns FEEDER and LOCOMOTOR -- Blind Composites by Default

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:351-352`
**Issue:** When two BondedPairs merge, role assignment only uses FEEDER and LOCOMOTOR. Newly formed composites have no SENSOR (so they receive no perception per D-20 blind composite rule), no ATTACKER (can't deal true damage), no DEFENDER (can't deflect), and no REPRODUCER (can't bud). This means composite perception (`CompositePerception`) is never sent to any newly formed composite's members -- they are functionally blind until role diversification occurs (which has no mechanism in the current code).

**Fix:** This may be intentional for phase 12 as a minimal viable implementation. If so, document it. Otherwise, consider assigning at least one SENSOR role on formation, or implementing a role-mutation mechanism for future phases.

## Info

### IN-01: Unused Variable `actionType` in resolveAttackerAttack

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:443`
**Issue:** `String actionType = rca.action.actionType() != null ? rca.action.actionType().toLowerCase() : "";` is computed but never read.
**Fix:** Remove the unused variable.

### IN-02: Dead Code -- extractRankedPreferences(Messages.CompositeAction) Never Called

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:733-746`
**Issue:** The `extractRankedPreferences(Messages.CompositeAction)` overload is never invoked. `queueCompositeAction()` converts `CompositeAction` to `Action` and stores ranked preferences in a separate map. The only call site uses the `extractRankedPreferences(Messages.Action)` overload.
**Fix:** Remove the unused method.

### IN-03: Test Name Misleading -- formationSharedPoolEnergyIsSumOfBondedPairs

**File:** `src/test/java/com/paralife/engine/CompositeFormationTest.java:144`
**Issue:** The test name `formationSharedPoolEnergyIsSumOfBondedPairs` states the pool equals the sum of BondedPair energies, but the correct behavior (implemented in the source) is that the pool equals the *remainder* after individual energy allocation. Even after fixing the assertion (CR-02), the test name will be misleading.
**Fix:** Rename to `formationSharedPoolEnergyIsRemainderAfterIndividualAllocation` or similar.

---

_Reviewed: 2026-04-14T05:10:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
