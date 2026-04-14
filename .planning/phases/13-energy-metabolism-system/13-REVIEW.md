---
phase: 13-energy-metabolism-system
reviewed: 2026-04-15T00:00:00Z
depth: standard
files_reviewed: 32
files_reviewed_list:
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/BondingConfig.java
  - src/main/java/com/paralife/engine/FertilityConfig.java
  - src/main/java/com/paralife/engine/FertilityInitializer.java
  - src/main/java/com/paralife/engine/MetabolicProfile.java
  - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
  - src/main/java/com/paralife/engine/SeasonsConfig.java
  - src/main/java/com/paralife/engine/SeasonTracker.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/StarvationConfig.java
  - src/main/java/com/paralife/websocket/Messages.java
  - src/main/java/com/paralife/websocket/TickBroadcaster.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/main/java/com/paralife/world/Cell.java
  - src/main/java/com/paralife/world/Entity.java
  - src/main/resources/application.yml
  - src/test/java/com/paralife/engine/ActionResolverTest.java
  - src/test/java/com/paralife/engine/BondingConfigTest.java
  - src/test/java/com/paralife/engine/CompositeActionTest.java
  - src/test/java/com/paralife/engine/CompositeCombatTest.java
  - src/test/java/com/paralife/engine/CompositeDissolutionTest.java
  - src/test/java/com/paralife/engine/CompositeFormationTest.java
  - src/test/java/com/paralife/engine/CompositeMovementTest.java
  - src/test/java/com/paralife/engine/FertilityInitializerTest.java
  - src/test/java/com/paralife/engine/MetabolicProfileTest.java
  - src/test/java/com/paralife/engine/MetabolismIntegrationTest.java
  - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
  - src/test/java/com/paralife/engine/PopulationDynamicsTest.java
  - src/test/java/com/paralife/engine/SeasonTrackerTest.java
  - src/test/java/com/paralife/engine/SimulationEngineTest.java
  - src/test/java/com/paralife/engine/SimulationIntegrationTest.java
  - src/test/java/com/paralife/websocket/TickBroadcasterTest.java
findings:
  critical: 0
  warning: 3
  info: 6
  total: 9
status: issues_found
---

# Phase 13: Code Review Report

**Reviewed:** 2026-04-15T00:00:00Z
**Depth:** standard
**Files Reviewed:** 32
**Status:** issues_found

## Summary

Phase 13 adds per-type metabolic profiles, starvation modifiers, hybrid-vigor
bond formation, seasonal cycling, and fertility patches. The implementation is
generally well-structured: ConfigurationProperties records perform thorough
validation, starvation intensity is consistently computed from current energy
(avoiding the stale-flag trap called out in Plan 02), and formation-time
randomness in BondedPair ensures per-tick determinism. Backward-compat
constructors on BondingConfig/BondedPair/CellView preserve older tests.

No security issues or Critical correctness bugs found. Three Warning-level
issues are worth addressing: a divide-by-zero in SeasonTracker.getSeason for
small yearLength values, a misleading "auto-consume on move" comment in
ActionResolver.resolveMove where a moved-onto Nutrient is silently destroyed
without any energy gain, and a mismatch in processInteractions combat-event
accounting (composite-member attacks produce one CombatDelta, Particle-vs-X
produce two, then the total is divided by 2 for reporting). Info-level items
cover minor dead-code, integer-division truncation in hybrid vigor, and some
semantic quirks in revertToBondedPair.

## Warnings

### WR-01: SeasonTracker.getSeason divides by zero for yearLength &lt; 4

**File:** `src/main/java/com/paralife/engine/SeasonTracker.java:53-58`
**Issue:** `SeasonsConfig` only validates `yearLengthTicks > 0` but
`SeasonTracker.getSeason` computes `position / (yearLength / 4)`. With
`yearLengthTicks` ∈ {1, 2, 3}, integer division `yearLength / 4 == 0` and the
subsequent `position / 0` throws `ArithmeticException`. The multiplier path
(`getSeasonalMultiplier`) is safe since it uses the raw yearLength, but any
caller that also derives the season enum (e.g. `TickBroadcaster`) will crash
the tick pipeline. No test currently exercises small yearLengths — the only
defense is a happens-to-work default of 200.
**Fix:**
```java
// Option A: tighten the config validation.
public SeasonsConfig {
    if (yearLengthTicks < 4)
        throw new IllegalArgumentException("yearLengthTicks must be >= 4: " + yearLengthTicks);
    ...
}

// Option B: make getSeason defensive.
public Season getSeason(long tick) {
    int yearLength = config.yearLengthTicks();
    int quarterLen = Math.max(1, yearLength / 4);
    long position = Math.floorMod(tick, (long) yearLength);
    int quarter = (int) (position / quarterLen);
    return Season.values()[Math.min(quarter, 3)];
}
```
Option A is preferable: 4 is the minimum value where the enum semantics hold.

### WR-02: Moving onto a nutrient silently destroys it with no energy gain

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:317-321`
**Issue:** The inline comment reads
`// If target has a nutrient, the particle replaces it (auto-consume on move)`,
but the code simply calls `worldGrid.setEntity(target, ra.particle)`, which
overwrites the nutrient without crediting any energy to the particle and
without depleting the nutrient via `Nutrient.consumed(...)`. Either:
- the comment is wrong (no consumption happens — the nutrient is just
  destroyed), which is a trap for future readers and players, or
- the intent was to auto-consume, which is a missing feature.
Either way the code and comment disagree. The cleanest fix is to grant the
per-type `nutrientConsumeEnergy` here so moving onto a nutrient is no worse
than using a separate `consume` action — otherwise players are penalised for
attempting a reasonable intuition.
**Fix:**
```java
// Execute move
claimedCells.add(target);
worldGrid.clearEntity(ra.bot.position().x(), ra.bot.position().y());

// Auto-consume nutrient if present (fix: match comment intent).
Particle placed = ra.particle;
if (targetCell.occupant() instanceof Nutrient) {
    var profile = metabolicProfile.forType(ra.particle.type());
    int energyGain = profile.nutrientConsumeEnergy();
    // starvation boost parity with resolveConsume
    double intensity = StarvationConfig.computeIntensity(
            ra.particle.energy(), ra.particle.maxEnergy(),
            profile.starvationThreshold(), profile.starvationFloor());
    if (intensity > 0.0) {
        energyGain = (int) (energyGain * (1 + starvationConfig.maxNutrientBoost() * intensity));
    }
    placed = ra.particle.withEnergy(ra.particle.energy() + energyGain);
}
worldGrid.setEntity(target.x(), target.y(), placed);
```
If the behaviour is actually "nutrient destroyed, no gain" by design, at minimum
delete the misleading comment.

### WR-03: Combat-event count is divided by 2 but composite attackers only emit one CombatDelta

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:229-274, 423`
**Issue:** `processInteractions` returns `combatEvents / 2` on the assumption
that every combat emits two `CombatDelta`s (one positive for the attacker, one
negative for the defender). This holds for Particle-vs-Particle and
Particle-vs-BondedPair/CompositeMember, but the composite-member attacker
block only adds a single negative `CombatDelta` for the defender (lines 258,
263, 266, 269). With N particle combats and M composite-member combats, the
reported count is `(2N + M) / 2`, which understates the truth when M is odd
and rounds down otherwise. This is only an observability bug (the counter
feeds a debug log via `lastTickBondCount`/similar paths aren't affected) but
it makes debugging population dynamics harder.
**Fix:** Track each category separately and stop dividing at the end.
```java
int particleCombats = 0;
int compositeMemberCombats = 0;
// ...increment appropriately when CombatDeltas are generated...
return new int[]{particleCombats + compositeMemberCombats, bondEvents, compositeEvents};
```
Or give composite-member attacks a symmetric (zero-valued) attacker delta so
the divide-by-2 accounting remains valid.

## Info

### IN-01: Hybrid vigor truncates mild bonuses to zero via integer division

**File:** `src/main/java/com/paralife/world/Entity.java:286-292`
**Issue:** `hybridRate` computes `avg = (rateA + rateB) / 2` then
`avg + (int)((max - avg) * bonus)`. For `rateA=3, rateB=4`:
`avg = 3 (not 3.5)`, `max - avg = 1`, `bonus ∈ [0.1, 0.5]` → result
`3 + (int)(1 * 0.1..0.5) = 3`. The pair gets zero hybrid boost despite the
random roll. For identical rates (`rateA == rateB`) the bonus is always zero
by design, which is fine. The truncation-to-zero edge case for nearly-equal
rates is a minor physics quirk rather than a bug.
**Fix:** Use floating-point math and round at the end, e.g.
`return (int) Math.round(((rateA + rateB) / 2.0) + ((Math.max(rateA, rateB) - (rateA + rateB) / 2.0) * bonus));`

### IN-02: Dead totalMax==0 guard in BondedPair weighted starvation calc

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:489-495`
**Issue:** `totalMax = profileA.maxEnergy() + profileB.maxEnergy()`.
`TypeProfile` validates `maxEnergy > 0`, so `totalMax` is always ≥ 2. The
`totalMax == 0 ? 0 : ...` ternary branch is unreachable.
**Fix:** Drop the guard, or replace with an `assert totalMax > 0;` for
self-documenting intent.

### IN-03: Composite-member attackers stop after the first neighbour regardless of outcome

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:245-273`
**Issue:** The inner neighbour loop unconditionally `break`s after each
iteration (line 272). Non-ATTACKER members that happen to have a non-prey
neighbour as the first iterated position never check subsequent neighbours
and never attack, even if an adjacent prey sits one cell further in the loop
order. This is likely intentional ("each member attacks at most one neighbour
per tick") but skews toward the first neighbour in `getNeighbors()` order.
Consider shuffling neighbours or using `break` only after a successful action.
**Fix (if behaviour should be "first successful attack wins"):**
```java
boolean actionTaken = false;
for (Position nPos : worldGrid.getNeighbors(pos.x(), pos.y())) {
    // ...existing checks & results.add...
    if (<combat result added>) {
        actionTaken = true;
        break;
    }
}
```

### IN-04: Deprecated flat constants live on ActionResolver with no consumers

**File:** `src/main/java/com/paralife/engine/ActionResolver.java:47-62`
**Issue:** `REPRODUCE_ENERGY_COST = 30` and `CHILD_START_ENERGY = 20` are
marked `@Deprecated` and replaced by per-type profile values but are still
referenced at runtime nowhere in the codebase. Comments suggest "kept for
tests" but a ripgrep of the test directory shows no active usage — if no test
imports them, they can go.
**Fix:** Verify via `grep -rn "REPRODUCE_ENERGY_COST\|CHILD_START_ENERGY"`
and, if truly unused, delete both constants and their javadoc. If any test
still imports them, migrate that test to `MetabolicProfile.defaults()` values
and delete afterwards.

### IN-05: revertToBondedPair uses the same type for primary and secondary

**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:707-712`
**Issue:** When a composite collapses to a single surviving member it is
wrapped in a `BondedPair(id, cm.type(), cm.type(), ...)`. Downstream RPS
checks assume `primaryType` is the predator and `secondaryType` is the prey.
With both equal to the survivor's type, the pair behaves as if it has no
prey-type shielding — an attacker of `cm.type().predator()` matches
`primaryType().predator()` exactly as intended, but the "secondary grants
deflection chance" narrative breaks down semantically. Functionally this
probably doesn't misfire because `bondDefenseChance` is applied regardless
of secondary type, but the model is fuzzy. Consider using a distinct
`BondedPair` subtype ("solo-revert") or adding a comment noting this is an
intentional placeholder for the lone-survivor edge case.
**Fix:** Add a code comment explaining the choice, or introduce a tagged
variant so future readers don't infer a real prey type from the field.

### IN-06: cellToView emits synthetic type strings (BONDED_X_Y, COMPOSITE_T_R)

**File:** `src/main/java/com/paralife/engine/PerceptionBroadcaster.java:281-287`
**Issue:** `CellView.occupantType` returns strings like
`"BONDED_CATALYST_MEMBRANE"` and `"COMPOSITE_SPORE_FEEDER"` built via string
concatenation. Clients that want to discriminate on type (e.g. bots looking
for "any bonded pair") must do string prefix matching. This locks a fragile
contract into the wire format. Worth documenting in `Messages.CellView`
javadoc so downstream consumers aren't surprised by the composite shape, or
replacing with a structured `{type, subtype, role}` tuple if it becomes a
pain point.
**Fix:** Add a javadoc note on `Messages.CellView` enumerating the possible
string patterns, or add optional `subtype`/`role` fields and keep
`occupantType` as the coarse kind only.

---

_Reviewed: 2026-04-15T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

## Resolutions (2026-04-15 triage pass)

Triage plan mapped each warning/info finding to Fix Now / Defer. Every
non-deferred item is landed as an atomic commit on `master`. Deferred items
are carried forward in `v2.0-TECH-DEBT.md` (tech-debt log) until the
milestone closes.

| Finding | Resolution | Commit |
|---|---|---|
| WR-01 SeasonTracker divide-by-zero | FIXED (subsumed by FN-4 validation bump to `yearLengthTicks >= 8`) | 106c578 |
| WR-02 Move-onto-nutrient silently destroys it | FIXED — auto-consume on move with per-type gain + starvation boost | ecbddc7 |
| WR-03 Combat event count undercounts composite attacks | FIXED — count at emission, split particle vs composite-member attackers | d73b5a3 |
| IN-01 Hybrid vigor integer truncation | DEFERRED (DF-C) — defensible behavior; float math would churn a record for a minor tuning concern | — |
| IN-02 Dead `totalMax==0` guard | FIXED — ternary replaced by invariant comment | 7f09bad |
| IN-03 Composite attacker break-on-first-neighbour | DEFERRED (DF-D) — design intent unclear; revisit during composite combat tuning | — |
| IN-04 Deprecated flat constants with no consumers | PARTIALLY FIXED — grep proved the `@Deprecated` + "no consumers" claim false. Constants removed from `@Deprecated`, javadoc corrected to describe their actual role as shared test fixtures | 7f09bad |
| IN-05 `revertToBondedPair` same-type ambiguity | FIXED — inline comment documents the intentional placeholder | 7f09bad |
| IN-06 Synthetic wire-format type strings | DEFERRED (DF-E) — single-client today; restructure when a second consumer appears | — |

Cross-AI plan review (`13-REVIEWS.md`) additions:

| Concern | Resolution | Commit |
|---|---|---|
| E (sin vs cos seasons) | FIXED — sin-based formula + `+L/8` shift so SPRING/SUMMER/AUTUMN/WINTER are centered on rising/max/falling/min phases | 106c578 |
| F (SPORE reproduction fails in dense areas) | FIXED — range-1 fallback preserves r-strategist ergonomics without erasing range-2 preference | c76f699 |
| B (`WorldGrid.clear()` wipes `nutrientLevel`) | FIXED — added `clearOccupants()` alongside `clear()` with distinct-behavior javadoc | 441064c |
| D (BondedPair speculative cached fields) | DEFERRED (DF-A) — future-proof for active-agent BondedPairs | — |
| C (MetabolismIntegrationTest weakness) | DEFERRED (DF-B) — passes today; revisit if it flakes | — |
| G (Cell.nutrientLevel regen/decay) | DEFERRED (DF-F) — out of scope for Phase 13; revisit once ecology exposes a need | — |

All post-fix tests green (`./gradlew test`), bootRun smoke clean (no
ArithmeticException, tick engine running, seasonal multiplier cycling).
