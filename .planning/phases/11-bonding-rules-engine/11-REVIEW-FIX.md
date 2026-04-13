---
phase: 11-bonding-rules-engine
fixed_at: 2026-04-13T09:21:00Z
review_path: .planning/phases/11-bonding-rules-engine/11-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 11: Code Review Fix Report

**Fixed at:** 2026-04-13T09:21:00Z
**Source review:** .planning/phases/11-bonding-rules-engine/11-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4
- Fixed: 4
- Skipped: 0

## Fixed Issues

### WR-04: BondedPair id uses "+" separator which is fragile if entity IDs contain "+"

**Files modified:** `src/main/java/com/paralife/world/Entity.java`, `src/main/java/com/paralife/engine/SimulationEngine.java`
**Commit:** 8a09c03
**Applied fix:** Added `primaryEntityId` and `secondaryEntityId` fields to the `BondedPair` record to store constituent entity IDs explicitly rather than encoding them in the composite ID string. Added a convenience 5-arg constructor that derives IDs from the composite ID for backward compatibility with existing tests. Updated `withEnergy()` to preserve the new fields. Updated `SimulationEngine` bond formation to pass explicit entity IDs from the predator and prey particles.

### WR-01: BondedPair death does not clean up bot registrations for constituent entities

**Files modified:** `src/main/java/com/paralife/engine/SimulationEngine.java`
**Commit:** 44d9f04
**Applied fix:** Added `botRegistry.unregisterByEntity()` calls for both `bp.primaryEntityId()` and `bp.secondaryEntityId()` in the BondedPair death path of `processDeaths()`, matching the existing cleanup pattern used for Particle deaths. Uses the explicit ID fields added in WR-04 rather than parsing the composite ID string.

### WR-02: Overcrowding does not apply to BondedPair entities

**Files modified:** `src/main/java/com/paralife/engine/SimulationEngine.java`
**Commit:** aa3821b
**Applied fix:** Refactored `processOvercrowding()` to check for both `Particle` and `BondedPair` occupants instead of skipping non-Particle cells. BondedPairs now receive the same overcrowding energy penalty as Particles when neighbor count meets the threshold. The neighbor counting logic (which already counted BondedPairs) is unchanged.

### WR-03: ActionResolver does not handle BondedPair as a move blocker

**Files modified:** `src/main/java/com/paralife/engine/ActionResolver.java`
**Commit:** 9c71f8b
**Applied fix:** Added a `BondedPair` instanceof check in `resolveMove()` after the existing `Rock` and `Particle` checks. A particle attempting to move into a cell occupied by a BondedPair now receives a failure result ("Cell occupied by a bonded pair") instead of silently overwriting the BondedPair.

---

_Fixed: 2026-04-13T09:21:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
