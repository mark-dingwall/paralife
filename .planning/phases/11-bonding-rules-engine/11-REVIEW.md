---
phase: 11-bonding-rules-engine
reviewed: 2026-04-13T12:00:00Z
depth: standard
files_reviewed: 12
files_reviewed_list:
  - src/main/java/com/paralife/engine/BondingConfig.java
  - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/websocket/Messages.java
  - src/main/java/com/paralife/websocket/TickBroadcaster.java
  - src/main/java/com/paralife/world/Entity.java
  - src/main/resources/application.yml
  - src/test/java/com/paralife/engine/BondingConfigTest.java
  - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
  - src/test/java/com/paralife/engine/SimulationEngineTest.java
  - src/test/java/com/paralife/websocket/TickBroadcasterTest.java
  - src/test/java/com/paralife/world/EntityTest.java
findings:
  critical: 0
  warning: 4
  info: 2
  total: 6
status: issues_found
---

# Phase 11: Code Review Report

**Reviewed:** 2026-04-13T12:00:00Z
**Depth:** standard
**Files Reviewed:** 12
**Status:** issues_found

## Summary

Phase 11 adds the bonding (endosymbiosis) mechanic: predator-prey pairs can fuse into `BondedPair` entities instead of combat, with configurable energy thresholds, bonding probability, and defense chance. The implementation touches Entity (new sealed variant), SimulationEngine (interaction resolution), PerceptionBroadcaster (BondedPair rendering), TickBroadcaster (bond count in tick messages), Messages (bondCount field), and BondingConfig.

Overall quality is solid. The code follows project conventions (immutable records, sealed hierarchy, deferred-write pattern, config validation). Test coverage is thorough with edge cases for double-bonding prevention, energy thresholds, and defense mechanics. Four warnings identified below — two are logic bugs that could cause incorrect simulation behaviour, and two are missing-handling gaps.

## Warnings

### WR-01: BondedPair death does not clean up bot registrations for constituent entities

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:297-299`
**Issue:** When a `BondedPair` dies (energy reaches zero), the death removal phase calls `worldGrid.clearEntity()` but does not call `botRegistry.unregisterByEntity()` for either of the two original entity IDs that were fused into the pair. The `Particle` death path at line 294 calls `unregisterByEntity(p.id())`, but the `BondedPair` path at line 298 does not. Since the BondedPair's `id()` is a composite like `"cat+spo"`, even if `unregisterByEntity` were called with that composite ID, neither original entity's registration would be cleaned up. This means bot sessions whose entities were absorbed into a bond that subsequently dies will remain registered in the `BotRegistry` as ghost entries pointing to nonexistent entities.
**Fix:** Extract the constituent entity IDs from the composite ID and unregister both. Alternatively, unregister both original entity IDs at bond formation time (since the particles no longer exist independently once bonded):
```java
// Option A: Unregister at bond death (SimulationEngine line ~298)
} else if (cell.occupant() instanceof Entity.BondedPair bp && !bp.isAlive()) {
    // BondedPair id format: "predatorId+preyId"
    String[] parts = bp.id().split("\\+", 2);
    for (String entityId : parts) {
        botRegistry.unregisterByEntity(entityId);
    }
    worldGrid.clearEntity(x, y);
    deaths++;
}

// Option B (preferred): Unregister at bond formation time (line ~218 area)
// Add after bondedPair creation:
botRegistry.unregisterByEntity(bond.predator.id());
botRegistry.unregisterByEntity(bond.prey.id());
```

### WR-02: Overcrowding does not apply to BondedPair entities

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:261`
**Issue:** The overcrowding check at line 261 only applies to `Particle` occupants (`if (!(cell.occupant() instanceof Particle p)) continue`). BondedPair entities are skipped entirely — they never receive overcrowding penalties and the `FLAG_OVERCROWDED` flag is never set or cleared for cells containing them. This is inconsistent with energy decay (which does apply to BondedPair at lines 242-247) and may be an intentional design choice, but it creates an asymmetry where BondedPairs are immune to overcrowding pressure while contributing to the neighbor count of adjacent Particles (line 266 counts BondedPairs as neighbors). This means BondedPairs make overcrowding *worse* for neighbors without suffering any penalty themselves.
**Fix:** If intentional, add a comment explaining the design rationale. If not, extend overcrowding to BondedPair:
```java
Entity occupant = cell.occupant();
if (occupant instanceof Particle p) {
    // existing logic...
} else if (occupant instanceof Entity.BondedPair bp) {
    int neighborCount = 0;
    for (Position nPos : worldGrid.getNeighbors(x, y)) {
        Entity neighbor = worldGrid.getCell(nPos.x(), nPos.y()).occupant();
        if (neighbor instanceof Particle || neighbor instanceof Entity.BondedPair) {
            neighborCount++;
        }
    }
    if (neighborCount >= config.overcrowdingThreshold()) {
        worldGrid.setEntity(x, y, bp.withEnergy(bp.energy() - config.overcrowdingEnergyPenalty()));
    }
}
```

### WR-03: ActionResolver does not handle BondedPair as a move blocker

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:183-189`
**Issue:** The move resolution checks for `Rock` and `Particle` occupants to block movement, but does not check for `BondedPair`. A particle attempting to move into a cell occupied by a `BondedPair` will succeed — the `BondedPair` will be silently overwritten by `worldGrid.setEntity()` at line 197. This is almost certainly a bug: BondedPairs should block movement the same way Particles do.
**Fix:**
```java
if (targetCell.occupant() instanceof Rock) {
    sendResult(ra.sessionId, tickNumber, false, "move", "Cannot move into rock");
    return false;
}
if (targetCell.occupant() instanceof Particle) {
    sendResult(ra.sessionId, tickNumber, false, "move", "Cell occupied by another entity");
    return false;
}
if (targetCell.occupant() instanceof Entity.BondedPair) {
    sendResult(ra.sessionId, tickNumber, false, "move", "Cell occupied by a bonded pair");
    return false;
}
```

### WR-04: Combat energy delta double-counted between mutual predator-prey pairs

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:138-158`
**Issue:** The interaction loop iterates all particles and for each one checks all neighbors. When particle A is adjacent to particle B and A beats B, a CombatDelta pair `(A, +transfer)` and `(B, -transfer)` is added. But when the loop reaches B, B also checks neighbor A — and if B does *not* beat A, nothing happens (correct). However, in a three-way RPS cluster where A beats B and B beats C and C beats A, each combat pair is only recorded once (A attacking B, B attacking C, C attacking A). The `combatEvents / 2` division at line 226 assumes each combat event generates 2 CombatDelta records (attacker + defender), so dividing by 2 is correct for counting *events*. But the combat deltas themselves are applied independently and are not double-counted. This is actually correct — I initially flagged this but on closer analysis the logic is sound. **However**, there is still a subtle issue: when a BondedPair deflects an attack (line 165), no CombatDelta records are generated, but the `combatEvents / 2` divisor still applies to the remaining combat deltas. This means the combat event count is accurate since the division only applies to CombatDelta records that were actually created. Revising: the real issue is that the same attacker can generate multiple combat deltas against multiple neighbors in the same tick without the combat count reflecting this accurately, since one attacker fighting two neighbors produces 4 deltas (counted as 2 events), which is correct per-pair but means the attacker gets energy from *both* victories. This is pre-existing behavior, not introduced by this phase, but the bond deflection mechanic adds a new edge case: an attacker fights a BondedPair (deflected) and also fights an adjacent prey Particle in the same tick. The attacker generates combat deltas only for the non-deflected fight, which is correct.

After analysis: the combat counting is correct. **Withdrawing this warning.** Replacing with a different observation:

The `combatEvents / 2` at line 226 will produce incorrect counts when a BondedPair receives combat damage. A CombatDelta for the attacker Particle (+energy) and a CombatDelta for the BondedPair (-energy) are both applied. The attacker's delta increments `combatEvents` at line 186-187, and the BondedPair's delta increments it again at lines 188-193. So each attack on a BondedPair produces 2 combat event increments (correct for the /2 divisor). This is fine.

**Revised WR-04:** BondedPair id uses "+" separator which is fragile if entity IDs contain "+"

**File:** `src/main/java/com/paralife/world/Entity.java:143` and `src/main/java/com/paralife/engine/SimulationEngine.java:212`
**Issue:** The BondedPair composite ID is formed as `predator.id() + "+" + prey.id()` at SimulationEngine line 212. If WR-01's fix (Option A) parses this with `split("\\+", 2)`, it will break if any entity ID contains a `+` character. Currently entity IDs are either `"child-N"` (from ActionResolver), `"nutrient-N"`, or user-supplied during registration. There is no validation on entity IDs to reject `+` characters. More importantly, nested bonding (a BondedPair bonding with another entity) would produce IDs like `"cat+spo+mem"` which would split incorrectly.
**Fix:** Use a separator that cannot appear in entity IDs (e.g., `"::"`), or store the constituent IDs as separate fields on BondedPair rather than encoding them in the ID string. Adding fields is the more robust approach:
```java
record BondedPair(
    String id,
    ParticleType primaryType,
    ParticleType secondaryType,
    int energy,
    int maxEnergy,
    String primaryEntityId,   // for bot cleanup
    String secondaryEntityId  // for bot cleanup
) implements Entity { ... }
```

## Info

### IN-01: BondedPair validation does not enforce valid RPS relationship

**File:** `src/main/java/com/paralife/world/Entity.java:151-154`
**Issue:** The `BondedPair` compact constructor validates energy constraints but does not validate that `primaryType` and `secondaryType` form a valid predator-prey pair (i.e., `primaryType.prey() == secondaryType`). This allows constructing nonsensical bonds like `BondedPair("x", CATALYST, MEMBRANE, ...)` where CATALYST does not beat MEMBRANE. While the `SimulationEngine` only creates valid pairs, defensive validation would catch misuse in tests or future code paths.
**Fix:**
```java
public BondedPair {
    if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
    if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
    if (primaryType.prey() != secondaryType) throw new IllegalArgumentException(
        "Invalid bond: " + primaryType + " does not beat " + secondaryType);
}
```

### IN-02: PerceptionBroadcaster sends "UNKNOWN" type when bot's entity is displaced

**File:** `src/main/java/com/paralife/engine/PerceptionBroadcaster.java:100-104`
**Issue:** This is pre-existing known tech debt (documented in CLAUDE.md). When a bot's entity has been displaced (bonded or killed), the perception falls back to `"UNKNOWN"` type with 0 energy. With bonding, this code path will now be hit more frequently — whenever a bot's entity is absorbed into a BondedPair, subsequent perception messages will show `"UNKNOWN"` until the bot is cleaned up. This is not a new bug but the bonding mechanic increases its occurrence surface.
**Fix:** Consider detecting the BondedPair case specifically and sending the bond's type info, or ensure bot cleanup (WR-01) happens before perception broadcast (it does, since SimulationEngine is Order(10) and PerceptionBroadcaster is Order(50), but only if the BondedPair has already died — not at formation time).

---

_Reviewed: 2026-04-13T12:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
