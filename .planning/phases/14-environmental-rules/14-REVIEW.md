---
phase: 14-environmental-rules
reviewed: 2026-04-17T16:14:47Z
depth: standard
files_reviewed: 46
files_reviewed_list:
  - src/main/java/com/paralife/bot/BotClient.java
  - src/main/java/com/paralife/bot/HeuristicBrain.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/BuffRegistry.java
  - src/main/java/com/paralife/engine/CellularAutomaton.java
  - src/main/java/com/paralife/engine/CompositeEnergyDistributor.java
  - src/main/java/com/paralife/engine/DeathCleanupHooks.java
  - src/main/java/com/paralife/engine/DeathFinalizer.java
  - src/main/java/com/paralife/engine/EntityIds.java
  - src/main/java/com/paralife/engine/EnvCleanupHooksBean.java
  - src/main/java/com/paralife/engine/EnvironmentConfig.java
  - src/main/java/com/paralife/engine/EnvironmentEngine.java
  - src/main/java/com/paralife/engine/EnvPostActionReconciler.java
  - src/main/java/com/paralife/engine/Infection.java
  - src/main/java/com/paralife/engine/MutagenEvent.java
  - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/ToxinEvent.java
  - src/main/java/com/paralife/engine/ToxinPathGenerator.java
  - src/main/java/com/paralife/websocket/Messages.java
  - src/main/resources/application.yml
  - src/test/java/com/paralife/bot/HeuristicBrainTest.java
  - src/test/java/com/paralife/engine/ActionResolverTest.java
  - src/test/java/com/paralife/engine/BuffRegistryTest.java
  - src/test/java/com/paralife/engine/CellularAutomatonTest.java
  - src/test/java/com/paralife/engine/CompositeEnergyDistributorTest.java
  - src/test/java/com/paralife/engine/CompostSinkFailFastTest.java
  - src/test/java/com/paralife/engine/CompostTest.java
  - src/test/java/com/paralife/engine/DeathFinalizerTest.java
  - src/test/java/com/paralife/engine/EnvDeathSweepTest_GracefulDegradation.java
  - src/test/java/com/paralife/engine/EnvDeathSweepTest.java
  - src/test/java/com/paralife/engine/EnvDeathSweepTest_Shatter.java
  - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java
  - src/test/java/com/paralife/engine/EnvironmentEngineTest.java
  - src/test/java/com/paralife/engine/EnvironmentFullStackSmokeTest.java
  - src/test/java/com/paralife/engine/EnvironmentPhaseGateIntegrationTest.java
  - src/test/java/com/paralife/engine/EnvPostActionReconcilerTest.java
  - src/test/java/com/paralife/engine/LightningTest.java
  - src/test/java/com/paralife/engine/MutagenTest.java
  - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
  - src/test/java/com/paralife/engine/SeasonalPoissonTest.java
  - src/test/java/com/paralife/engine/SimulationEngineTest.java
  - src/test/java/com/paralife/engine/ToxinPathGeneratorTest.java
  - src/test/java/com/paralife/engine/ToxinTest.java
  - src/test/java/com/paralife/engine/VisionScopedOvercrowdingTest.java
findings:
  critical: 0
  warning: 3
  info: 5
  total: 8
status: issues_found
---

# Phase 14: Code Review Report

**Reviewed:** 2026-04-17T16:14:47Z
**Depth:** standard
**Files Reviewed:** 46
**Status:** issues_found

## Summary

Phase 14 ships four environmental effects (lightning, toxin, mutagen, compost), a survivor-buff registry, and the supporting pipeline wiring. The code is high-quality, thoroughly tested (the full-stack phase-gate integration test is excellent), and shows strong attention to detail — fail-fast CompostSink registration, cycle-6 nullable seed semantics, per-bot vision-scoped OVERCROWDED recomposition, attempted-strike counter semantics documented in Javadoc, and explicit `Math.max(0, ...)` clamps grep-asserted at splash sites.

No Critical security or correctness bugs found. Three Warnings concern latent state-management issues around `BondedPair.id()`-keyed registry entries (buff cleanup asymmetry versus the Phase-14 infection cleanup, revertToBondedPair using `cm.id()` for both member ids, and the non-`break` attacker loop whose multi-hit behaviour is only implicitly documented). Five Info items are minor hygiene (dead code documented in CLAUDE.md tech debt, unused imports, unused overload, a couple of documentation clarifications).

## Warnings

### WR-01: BuffRegistry orphan entries after BondedPair death

**File:** `src/main/java/com/paralife/engine/DeathFinalizer.java:97-116`
**Issue:** `finalizeBondedPairDeath` clears buffs for `primaryId` and `secondaryId` but NOT for `bp.id()`. Meanwhile, both `SimulationEngine.processInteractions` (BondFormation path, line 533-534) and `SimulationEngine.revertToBondedPair` (line 980) transfer buffs TO `bp.id()` via `buffRegistry.transferBuffs(..., bondedPair.id())`. When that BondedPair later dies, buffs keyed under `bp.id()` are not removed — the entity leaks into `BuffRegistry.byEntity` until its individual expiry tick.

This is asymmetric with cycle-4 action item #6, which specifically added `hooks.clearInfectionOnDeath(bp.id())` on line 110 to handle Plan-14-03 infections keyed by the pair id. The analogous `buffRegistry.unregisterEntity(bp.id())` call was not added.

Impact is bounded (buffs have expiry ticks; `expireBuffs` eventually cleans them), but `BuffRegistry.getRegisteredEntityIds()` is now used by `EnvironmentPhaseGateIntegrationTest.countActiveBuffs()` and holds orphan entries in the interim.

**Fix:**
```java
public void finalizeBondedPairDeath(int x, int y, BondedPair bp) {
    deathEventCount++;
    String primaryId = bp.primaryEntityId();
    String secondaryId = bp.secondaryEntityId();

    botRegistry.unregisterByEntity(primaryId);
    botRegistry.unregisterByEntity(secondaryId);

    buffRegistry.unregisterEntity(primaryId);
    buffRegistry.unregisterEntity(secondaryId);
    buffRegistry.unregisterEntity(bp.id());  // WR-01: mirror clearInfectionOnDeath(bp.id())

    hooks.clearInfectionOnDeath(primaryId);
    hooks.clearInfectionOnDeath(secondaryId);
    hooks.clearInfectionOnDeath(bp.id());

    hooks.applyCompost(new Position(x, y));
    worldGrid.clearEntity(x, y);
    // ...
}
```
Add a regression test in `DeathFinalizerTest.bondedPairDeathCleansBothMemberIdsAndBpId` that also grants a buff to `bp.id()` before invoking `finalizeBondedPairDeath` and asserts `buffRegistry.getBuffs(bp.id())` is empty afterwards.

### WR-02: revertToBondedPair uses cm.id() for both primaryEntityId and secondaryEntityId

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:966-968`
**Issue:** The revert path constructs `new Entity.BondedPair("bp-" + cm.id(), cm.type(), cm.type(), cm.energy(), cm.maxEnergy(), cm.id(), cm.id())` — passing `cm.id()` for BOTH the primary and secondary entity-id slots. The inline Javadoc comment acknowledges type duplication but not the id duplication.

Functional consequences:
- `finalizeBondedPairDeath` calls `botRegistry.unregisterByEntity(cm.id())` twice and `buffRegistry.unregisterEntity(cm.id())` twice. Both are idempotent, so no correctness break today.
- But the `secondaryEntityId` field is semantically nonsense (no such second entity exists in a revert), which will silently confuse any future code that treats primary/secondary as a pair. Combined with WR-01, this slot is a landmine if a future contributor "fixes" WR-01 by also cleaning `secondaryEntityId` differently.

**Fix:** Mint a distinguishable placeholder id (or null, if the BondedPair record allows) for the empty secondary slot, and add a doc note to the BondedPair record that identical `primaryEntityId == secondaryEntityId` indicates a revert-from-composite (degenerate) pair:
```java
var bondedPair = new Entity.BondedPair(
        "bp-" + cm.id(), cm.type(), cm.type(), cm.energy(), cm.maxEnergy(),
        cm.id(),
        cm.id() + "-placeholder");  // WR-02: distinct placeholder for the absent second member
```
Or, if the BondedPair contract permits `null`, use `null` and document that null-secondary marks a degenerate (revert) pair. Either choice is better than the silent id duplication.

### WR-03: Solo particle attacker may hit up to 8 neighbours per tick — only implicitly documented

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:249-350`
**Issue:** Inside the attacker loop (line 249), the neighbour loop (line 253) does NOT have a `break;` after a hit lands — unlike the composite-member attacker loop (line 431 explicitly breaks). This means one solo particle can trigger combat deltas against every RPS-valid prey among its 8 Moore neighbours in one tick. `ToxinTest.multiNeighborAttackStacksSplashOncePerToxicTarget` treats this as intended ("each hit is a discrete engagement") and Phase 14's splash damage stacking depends on it.

Concern: the intent is nowhere stated in the production source — the code just lacks the `break`. A well-meaning future refactor could easily mis-add `break;` and silently regress Plan 14-02's splash accumulation. The analogous composite-member loop explicitly notes "Each member attacks at most one neighbor per tick" on its `break;` line; the solo path should symmetrically document its opposite choice.

**Fix:** Add a one-line comment at the top of the neighbour loop explaining that the absence of `break;` is deliberate, and reference the locking test:
```java
// Solo Particle attackers engage EVERY RPS-valid neighbour in one tick
// (no break — intentional). Splash damage stacking relies on this; see
// ToxinTest.multiNeighborAttackStacksSplashOncePerToxicTarget.
for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
```
No code change required, but the comment converts tribal knowledge into a grep-able contract.

## Info

### IN-01: HeuristicBrain predatorType ternary has identical branches

**File:** `src/main/java/com/paralife/bot/HeuristicBrain.java:108`
**Issue:** `ParticleType predatorType = preyType.predator() == myType ? myType.predator() : predatorOf(myType);` — both branches evaluate to the same value. In the three-type RPS cycle (CATALYST→SPORE→MEMBRANE→CATALYST), `myType.prey().predator()` always equals `myType`, so the condition is always true and `predatorOf(myType)` (the else branch) is unreachable. Already tracked in CLAUDE.md as known tech debt.
**Fix:** `ParticleType predatorType = myType.predator();` — removes the dead branch and the now-unused private `predatorOf(ParticleType)` helper at line 249-251.

### IN-02: Unused imports in BotClient

**File:** `src/main/java/com/paralife/bot/BotClient.java:18-19`
**Issue:** `import java.util.ArrayList;` and `import java.util.List;` are declared but unused in the class body (grep confirms no List/ArrayList references).
**Fix:** Remove both import lines.

### IN-03: Unused extractRankedPreferences(Messages.CompositeAction) overload

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:1056-1069`
**Issue:** The `extractRankedPreferences(Messages.CompositeAction action)` overload has no callers. The call site at line 878 invokes the `Messages.Action` overload instead. Ranked preferences are delivered through the separate `pendingRankedPreferences` map (line 113) during `queueCompositeAction`, which means this overload was superseded.
**Fix:** Delete lines 1056-1069, or add `@SuppressWarnings("unused")` with a comment explaining why it's kept as a public/package API. Preferred: delete — the live path (pendingRankedPreferences) is well-tested and adding a dead reader is confusing.

### IN-04: CompositeEnergyDistributor buffRegistry default-to-empty pattern is subtle

**File:** `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:47`
**Issue:** `private BuffRegistry buffRegistry = new BuffRegistry();` — non-final field initialised to a throwaway empty registry, then replaced by Spring via `setBuffRegistry` at injection time. The ActionResolver uses the same pattern (line 100). The pattern works but is easy to misread — someone could mistakenly believe each instance has its own registry. The javadoc on `setBuffRegistry` documents intent; the field itself should too.
**Fix:** Add a one-line field javadoc:
```java
/**
 * Spring-injected shared registry (see {@link #setBuffRegistry}). Initialised
 * to an empty throwaway instance so pre-Phase-14 direct-construction tests
 * see a null-safe no-op default. Production always replaces this via setter.
 */
private BuffRegistry buffRegistry = new BuffRegistry();
```

### IN-05: MutagenEvent originCell field is unused after the first tick

**File:** `src/main/java/com/paralife/engine/MutagenEvent.java:20`
**Issue:** The `originCell` field is stored in the event record but only read by test helpers (not by `advanceMutagen`, `resolveMutagenCollisions`, or `tickBuffsAndInfections`). The strain origin drives only the initial `mutagenGrid[ox][oy]` stamp at spawn (EnvironmentEngine.java:402-403); after that, propagation is grid-driven. Keeping the field is fine for observability/debugging, but the Javadoc should clarify that runtime behavior does not depend on it.
**Fix:** Update the record Javadoc:
```java
/**
 * ...
 * @param originCell    cell where the first strain byte was stamped. Used
 *                      only for logging + test helpers; runtime propagation
 *                      is driven by the shadow grid, not this field.
 * ...
 */
```

---

_Reviewed: 2026-04-17T16:14:47Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
