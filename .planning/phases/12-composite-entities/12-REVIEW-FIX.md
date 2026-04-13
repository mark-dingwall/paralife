---
phase: 12-composite-entities
fixed_at: 2026-04-14T17:00:00Z
review_path: .planning/phases/12-composite-entities/12-REVIEW.md
iteration: 1
findings_in_scope: 7
fixed: 6
skipped: 1
status: partial
---

# Phase 12: Code Review Fix Report

**Fixed at:** 2026-04-14T17:00:00Z
**Source review:** .planning/phases/12-composite-entities/12-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 7 (1 critical, 6 warnings)
- Fixed: 6
- Skipped: 1

## Fixed Issues

### CR-01: Race condition in CompositeState.drainEnergy (non-atomic read-then-modify)

**Files modified:** `src/main/java/com/paralife/engine/CompositeRegistry.java`
**Commit:** 63ae07e
**Applied fix:** Replaced non-atomic read-then-modify in `drainEnergy` with `AtomicInteger.getAndUpdate` using a CAS loop that atomically computes the clamped drain amount. Also fixed `addEnergy` to use `getAndUpdate` with clamping to `maxPoolEnergy`, preventing pool overflow.

### WR-01: Double-counted energy on composite formation

**Files modified:** `src/main/java/com/paralife/engine/SimulationEngine.java`
**Commit:** c291e6b
**Applied fix:** Changed shared pool calculation from `bp1.energy() + bp2.energy()` (which double-counted energy already allocated to individual members) to `(bp1.energy() - individualEnergy1) + (bp2.energy() - individualEnergy2)`. Total system energy is now conserved: individual allocations + pool = original BondedPair energies.

### WR-02: compositeTicksSinceMove never initialized for new composites

**Files modified:** `src/main/java/com/paralife/engine/ActionResolver.java`
**Commit:** cef461e
**Applied fix:** Added `putIfAbsent(compositeId, 0)` for all active composites before the increment loop in `resolveCompositeMovements`. New composites start at 0, get incremented to 1 on their first tick, and must wait for the speed gate interval before moving -- preventing immediate movement on formation tick.

### WR-03: Stale compositeTicksSinceMove entries leak memory

**Files modified:** `src/main/java/com/paralife/engine/ActionResolver.java`
**Commit:** c27efea
**Applied fix:** Added `retainAll` cleanup at the end of `resolveCompositeMovements` that prunes entries for dissolved composites by intersecting tracked keys with the set of active composite IDs from the registry.

### WR-04: BotRegistry double-mapping on composite formation

**Files modified:** `src/main/java/com/paralife/engine/SimulationEngine.java`
**Commit:** dea1823
**Applied fix:** Changed `updateBotRegistryForFormation` so the primary entity's session wins control of the new CompositeMember, and the secondary entity's session is cleanly unregistered (instead of being silently overwritten). This prevents ghost state where one bot's session points to an entity mapped to a different session.

### WR-06: CompositeMember overcrowding and energy decay are silently skipped

**Files modified:** `src/main/java/com/paralife/engine/SimulationEngine.java`
**Commit:** a63e0eb
**Applied fix:** Corrected misleading comments in `processEnergyDecay` and `processOvercrowding` sections. The energy decay comment now accurately states that passive role drain in `CompositeEnergyDistributor` replaces base `energyDecayPerTick`. The overcrowding comment now explicitly documents that CompositeMember entities are exempt, with energy costs governed by composite-specific drain rates.

## Skipped Issues

### WR-05: BondedPair convenience constructor splits ID for entity IDs -- fragile assumption

**File:** `src/main/java/com/paralife/world/Entity.java:172-177`
**Reason:** Code at `revertToBondedPair` (SimulationEngine:593-595) already uses the 7-arg constructor with explicit entity IDs, which is exactly what the reviewer recommends. The finding is about the 5-arg constructor's fallback behavior being fragile, but the actual code path is already correct. Deprecating the 5-arg constructor is a separate refactoring concern, not a bug fix.
**Original issue:** The 5-arg `BondedPair` constructor derives entity IDs by splitting on `+`, producing identical primary/secondary IDs when the ID has no `+`. The `revertToBondedPair` method constructs IDs without `+`, but already uses the 7-arg constructor so the fallback is never triggered for this call site.

---

_Fixed: 2026-04-14T17:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
