---
phase: 12-composite-entities
reviewed: 2026-04-14T16:45:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - src/main/java/com/paralife/world/Entity.java
  - src/main/java/com/paralife/engine/CompositeConfig.java
  - src/main/java/com/paralife/engine/CompositeEnergyDistributor.java
  - src/main/java/com/paralife/engine/CompositeRegistry.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
  - src/main/java/com/paralife/websocket/TickBroadcaster.java
  - src/main/java/com/paralife/websocket/Messages.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/main/java/com/paralife/engine/BotRegistry.java
  - src/main/resources/application.yml
  - src/test/java/com/paralife/engine/CompositeIntegrationTest.java
findings:
  critical: 1
  warning: 6
  info: 3
  total: 10
status: issues_found
---

# Phase 12: Code Review Report

**Reviewed:** 2026-04-14T16:45:00Z
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

The composite entity system introduces a siphonophore-inspired multi-cell organism model layered on top of the existing particle/bonded-pair system. The code is well-structured, follows project conventions (immutable records, sealed interfaces, event-driven pipeline), and the integration test coverage is solid. However, there are several concurrency bugs in `CompositeRegistry.drainEnergy`, a double-accounting energy bug in composite formation, missing validation on composite actions in the WebSocket handler, and some edge-case logic issues in the energy distributor and movement code.

## Critical Issues

### CR-01: Race condition in CompositeState.drainEnergy (non-atomic read-then-modify)

**File:** `src/main/java/com/paralife/engine/CompositeRegistry.java:113-118`
**Issue:** `drainEnergy` performs a non-atomic read-then-modify on `sharedPoolEnergy`. It reads `current` via `get()`, computes `actual`, then blindly subtracts. If two callers invoke `drainEnergy` concurrently (e.g., the CompositeEnergyDistributor healing loop and a FEEDER action resolved on a different thread), both read the same `current` value and both succeed, draining more energy than available and potentially driving the pool negative.

The comment says "Tick pipeline is single-threaded for mutations" but `drainEnergy` is also called from `ActionResolver` which processes queued actions, and `addEnergy` is called similarly. While the current event-driven pipeline is synchronous, the `AtomicInteger` usage signals an intent for thread-safety that is not actually achieved. If any future change introduces concurrency (or if Spring's event model changes), this silently corrupts state.

**Fix:** Use `AtomicInteger.getAndUpdate` or a CAS loop:
```java
public int drainEnergy(int amount) {
    int[] drained = new int[1];
    sharedPoolEnergy.getAndUpdate(current -> {
        drained[0] = Math.min(current, amount);
        return current - drained[0];
    });
    return drained[0];
}
```

Alternatively, since `addEnergy` has the same issue (can exceed `maxPoolEnergy`):
```java
public void addEnergy(int amount) {
    sharedPoolEnergy.getAndUpdate(current ->
        Math.min(current + amount, maxPoolEnergy));
}
```

## Warnings

### WR-01: Double-counted energy on composite formation

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:354-371`
**Issue:** When a composite forms from two BondedPairs, individual member energy is set to `bp.energy() / 2` and the shared pool is set to `bp1.energy() + bp2.energy()`. This means the total system energy after formation is `bp1.energy()/2 + bp2.energy()/2 + bp1.energy() + bp2.energy()` = 1.5x the original energy. Energy is created from nothing.

The pool should either exclude the energy already allocated to individual members, or the individual energy should be set to 0 with all energy going to the pool.

**Fix:**
```java
// Option A: Pool = remainder after individual allocation
int individualEnergy1 = cf.bp1().energy() / 2;
int individualEnergy2 = cf.bp2().energy() / 2;
int sharedPool = (cf.bp1().energy() - individualEnergy1) + (cf.bp2().energy() - individualEnergy2);
```

### WR-02: compositeTicksSinceMove never initialized for new composites

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:580-583`
**Issue:** The tick-since-move counter is only incremented for composites already in the map (line 581). New composites (formed this tick) are not added to `compositeTicksSinceMove`, so `getOrDefault(compositeId, moveInterval)` on line 603 returns `moveInterval`, immediately allowing movement. This may be intentional, but combined with the increment loop on lines 580-583 which only iterates existing keys, newly formed composites will always pass the speed gate on their first tick with a LOCOMOTOR vote, regardless of speed ratio.

The increment loop also never adds new entries -- it only touches existing ones. A composite must successfully move at least once (line 611 puts 0) before it starts being tracked.

**Fix:** Initialize the counter when a composite is formed:
```java
// In processInteractions composite formation block, after compositeRegistry.register():
compositeTicksSinceMove.put(compositeId, 0);
```

### WR-03: Stale compositeTicksSinceMove entries leak memory

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:62`
**Issue:** `compositeTicksSinceMove` is never cleaned up when a composite is dissolved. Over time, dissolved composites accumulate entries that are never removed. While each entry is small (String + Integer), in a long-running simulation this grows without bound.

**Fix:** Add cleanup in `SimulationEngine.dissolveToParticles` / `handleMemberDeath` / `checkPanicZone` -- or have `ActionResolver` prune stale entries each tick:
```java
// At the end of resolveCompositeMovements:
compositeTicksSinceMove.keySet().retainAll(
    compositeRegistry.getAll().stream()
        .map(CompositeRegistry.CompositeState::getCompositeId)
        .collect(Collectors.toSet()));
```

### WR-04: BotRegistry double-mapping on composite formation

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:396-407`
**Issue:** `updateBotRegistryForFormation` maps both the primary and secondary entity's sessions to the *same* new member ID. Since `BotRegistry.register` overwrites by session, the second call wins for the session map. But `entityToSession` now maps the single `newMemberId` to whichever session was last registered. The first session's bot effectively becomes orphaned -- `botRegistry.getBySession(firstSession)` returns the old (now non-existent) state until overwritten, and `entityToSession` only points to the second session.

This means one of the two bot clients that were controlling the original BondedPair's constituent particles loses its entity mapping silently.

**Fix:** This is a design issue. If two bots contributed to a BondedPair and that pair becomes a CompositeMember, only one bot can control the resulting member. The other should either be notified of death/ejection or assigned a different role. At minimum, the losing session should be explicitly unregistered to avoid ghost state.

### WR-05: BondedPair convenience constructor splits ID for entity IDs -- fragile assumption

**File:** `src/main/java/com/paralife/world/Entity.java:172-177`
**Issue:** The 5-arg `BondedPair` constructor derives `primaryEntityId` and `secondaryEntityId` by splitting `id` on `+`. If the ID does not contain `+`, both entity IDs are set to the same value as `id`. This is used in tests and the `revertToBondedPair` method (line 591-592) which constructs `"bp-" + cm.id()` -- an ID that never contains `+`. The resulting BondedPair has `primaryEntityId == secondaryEntityId == "bp-" + cm.id()`, which means `BotRegistry.unregisterByEntity` during BondedPair death (line 486-487) will try to unregister the same entity ID twice. The second call is a no-op, but it indicates the constructor's fallback behavior masks a real data modeling gap.

**Fix:** The `revertToBondedPair` method at SimulationEngine:591 should use the 7-arg constructor with explicit entity IDs rather than relying on the fallback:
```java
var bondedPair = new Entity.BondedPair(
    "bp-" + cm.id(), cm.type(), cm.type(), cm.energy(), cm.maxEnergy(),
    cm.id(), cm.id()); // already correct, but document intent
```
This is currently correct by accident. Consider deprecating the 5-arg constructor or adding a clear warning in its Javadoc.

### WR-06: CompositeMember overcrowding and energy decay are silently skipped

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:410-411, 434`
**Issue:** Comments state that CompositeMember energy decay and overcrowding are "handled by CompositeEnergyDistributor (Plan 12-02)". However, `CompositeEnergyDistributor` only handles passive role drain and pool healing -- it does not apply the same `energyDecayPerTick` that Particles and BondedPairs receive, and it does not apply overcrowding penalties. This means CompositeMember entities are exempt from the base energy decay and overcrowding penalty that all other entity types pay, giving composites a systematic survival advantage that may not be intended.

**Fix:** Either:
1. Apply `energyDecayPerTick` to CompositeMember individual energy in `processEnergyDecay` (same as Particles/BondedPairs), or
2. Explicitly document in `CompositeEnergyDistributor` that passive drain replaces base decay, and verify the drain rates are calibrated accordingly.

## Info

### IN-01: Unused import in ActionResolver

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:7`
**Issue:** `com.paralife.world.Entity.Rock` is not directly referenced in ActionResolver (rock checks use `instanceof Rock` via wildcard import style, but the explicit import `Entity.Rock` is not used since the `instanceof` pattern uses the simple name from the `Entity` sealed interface).

Actually, on closer inspection: `Entity.Rock` is not imported. `Rock` at line 257 works via `instanceof Rock` which resolves through the existing `import com.paralife.world.Entity`. The wildcard-style `import java.util.*` at line 15 is a minor style deviation from the project's otherwise explicit import convention.

**Fix:** Replace `import java.util.*;` with explicit imports on line 15 for consistency.

### IN-02: Dead code path -- extractRankedPreferences(Messages.CompositeAction) is never called

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:721-734`
**Issue:** The overloaded `extractRankedPreferences(Messages.CompositeAction)` method is defined but never called. The `queueCompositeAction` method converts `CompositeAction` to a regular `Action` and stores ranked preferences separately. The only call to `extractRankedPreferences` in `resolveCompositeMovements` (line 574) uses the `Messages.Action` overload. This method is dead code.

**Fix:** Remove the unused overload or mark it as package-private with a test that exercises it if it's intended for future use.

### IN-03: CompositeRegistry.updateAllPositions has a non-atomic clear+putAll window

**File:** `src/main/java/com/paralife/engine/CompositeRegistry.java:87-89`
**Issue:** `updateAllPositions` calls `clear()` then `putAll()` on a `ConcurrentHashMap`. Between these two calls, any concurrent reader (e.g., PerceptionBroadcaster reading positions) will see an empty map. This is noted as acceptable because the tick pipeline is single-threaded for mutations, but the Javadoc claims thread-safety for reads, and PerceptionBroadcaster runs on the same event thread at a later `@Order`. If the architecture ever changes to parallel pipeline stages, this would be a race. Low risk currently.

**Fix:** Consider replacing with a single `ConcurrentHashMap` swap pattern if thread safety becomes a harder requirement:
```java
public void updateAllPositions(Map<String, Position> positions) {
    memberPositions.keySet().retainAll(positions.keySet());
    memberPositions.putAll(positions);
}
```

---

_Reviewed: 2026-04-14T16:45:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
