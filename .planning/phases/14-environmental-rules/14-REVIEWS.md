---
phase: 14
reviewers: [gemini, codex]
reviewed_at: 2026-04-17T05:58:57Z
plans_reviewed:
  - 14-01-PLAN.md
  - 14-02-PLAN.md
  - 14-03-PLAN.md
  - 14-04-PLAN.md
  - 14-05-PLAN.md
  - 14-06-PLAN.md
cycle: 3
---

# Cross-AI Plan Review — Phase 14: Environmental Rules

## Gemini Review

This review evaluates implementation plans 14-01 through 14-06 for introducing environmental rules into the Paralife simulation. These plans represent the 4th iteration of planning, specifically revised to address deep architectural integration concerns raised by the Codex agent.

### 1. Summary
The implementation plans for Phase 14 are of exceptional quality, demonstrating a level of technical depth and architectural sensitivity rarely seen in automated planning. The most significant achievement is the creation of the `DeathFinalizer` component, which provides a unified, thread-safe, and same-tick-consistent cleanup path for all entity deaths, regardless of whether they are caused by combat, environment, or metabolic failure. The plans successfully pivot from "solo-only" mechanics to a "colony-aware" model for composite entities, particularly in the redesign of movement and upkeep buffs. By addressing same-tick death propagation, deferred-delta combat integration, and structural performance validation, these plans transform a feature-heavy request into a robust engineering design.

### 2. Strengths
- **Unified Death Model:** The `DeathFinalizer` (Plan 14-01) is the "golden key" of this phase. It ensures that `CompositeRegistry` and `BotRegistry` state updates are non-latent, preventing the "stale member" bugs that would otherwise crash the shared energy pool logic or leave ghost sessions.
- **Architectural Alignment for Buffs:** The redesign of composite buffs in Plan 14-05 (e.g., `MOVEMENT_PLUS_1` as a colony-level cooldown bypass) shows a deep understanding of the existing rigid-body movement model. This is vastly superior to the previously proposed "per-member range" which conflicted with the unit-movement logic.
- **Strict Adherence to Sim Integrity:** The toxin splash logic (Plan 14-02) correctly utilizes the `SplashDelta` record inside `SimulationEngine`'s deferred-delta pipeline. This prevents order-dependent execution bugs during the interaction phase.
- **Sophisticated Race Condition Handling:** The `pendingBuffGrants` queue in Plan 14-03 correctly solves the "lethal-cure" race condition. Gating buffs on a post-damage alive-check is crucial for a simulation where damage and state transitions are interleaved.
- **Structural Validation:** The integration test strategy in Plan 14-06, which splits determinism checks from wire-path smoke tests and uses explicit event counters instead of grid-diffing, provides a high-confidence signal while remaining resilient to CI flakiness.

### 3. Concerns
- **`reduceInfection` Performance [LOW]** — In Plan 14-03, `reduceInfection` uses `findOccupantById` (a full grid scan) to find the occupant for the pending grant. Since `reduceInfection` is called during the interaction phase (potentially multiple times per tick), this is O(Attacks * Area). The caller in `SimulationEngine` already knows the `Position`.
- **Circular Dependency Fragility [LOW]** — The use of `@Lazy` on the `DeathFinalizer` -> `SimulationEngine` link is the correct Spring solution, but circular dependencies are always a point of fragility during refactoring.
- **Grid Scan Density [LOW]** — The simulation now performs several O(Area) passes (Toxin CA, Mutagen Gossip, Mutagen Damage index, Status Building, Death Sweep). While well within the 500ms budget for a 256x256 grid, this is a "perf debt" to monitor for the Milestone 4 scale engineering phase.

### 4. Suggestions
- **Optimize `reduceInfection` Signature:** Change `reduceInfection(id, ticks, currentTick)` to `reduceInfection(id, ticks, currentTick, position)`. The caller already has the position in scope (it's `nPos` in `SimulationEngine` or `targetPos` in `ActionResolver`), which would eliminate the need for the `findOccupantById` grid scan.
- **Consolidate `entityIdOf`:** This helper is planned for both `EnvironmentEngine` and `PerceptionBroadcaster`. Consider moving it to a static utility or a shared base interface to ensure consistency (e.g., if a new entity type is added in the future).
- **Explicit `cellStatus` Documentation:** In Plan 14-05, ensure the distinction between `CellView.flags` (server-authoritative global state) and `CellView.cellStatus` (vision-scoped client-perspective state) is explicitly documented for future bot developers.

### 5. Risk Assessment
**Overall Risk: LOW**

The risk level is low because the plans are "defensively designed"—they assume the most difficult integration constraints and build around them. The heavy focus on Wave 1 foundation (`DeathFinalizer`) and the exhaustive enumeration of attack sites (6 sites across 3 families) ensures that the most likely points of failure are addressed first. The dependency sequencing is logical, and the threat model is thoroughly addressed. This phase is ready for execution.

---

## Codex Review

### Overall Summary

The plan set is much stronger than the prior review cycle. Most earlier feedback was incorporated well: 14-02 fixes the deferred-delta splash problem, 14-03 adds explicit buff-grant gating and named mutagen lifetime, 14-05 moves perception back onto live config, and 14-06 splits deterministic vs wire-path testing. The remaining issues are concentrated in execution-model fit, not feature coverage: 14-01 still has a wiring/semantics problem, 14-03 has one real cure-path bug, 14-05's composite movement redesign conflicts with the current movement gate, and 14-06 overclaims determinism.

### 14-01

**Summary** — Good foundation sequencing, but the death-finalization refactor still has two serious structural problems.

**Strengths**
- Correctly moves env work to `@Order(14)` ahead of `CompositeEnergyDistributor` (`src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:53`).
- Centralizing death cleanup is the right direction.
- Same-tick env-death tests are well targeted.

**Concerns**
- `HIGH`: the proposed wiring creates a direct `DeathFinalizer <-> EnvironmentEngine` bean cycle. `@Lazy SimulationEngine` does not solve that; the cycle is already present before `SimulationEngine` is considered.
- `HIGH`: the refactor drops current same-tick composite death behavior. Today, `SimulationEngine.handleMemberDeath` (`src/main/java/com/paralife/engine/SimulationEngine.java:665`) immediately runs the 97% graceful-degradation / 3% shatter roll at lines 695-701. The plan defers `remaining >= 2` handling to "next tick", which is a semantic change, not a refactor.

**Suggestions**
- Break the cycle by extracting compost/infection cleanup behind a narrow interface or event, not by having `DeathFinalizer` depend on `EnvironmentEngine` directly.
- Keep the full composite-death decision tree same-tick, either entirely in `SimulationEngine` or entirely in the new finalizer.

**Risk Assessment** — `HIGH` — solid intent, but the current 14-01 design is not safe to implement as written.

### 14-02

**Summary** — This is one of the strongest plans. The toxin core, CA model, and deferred splash integration are well aligned with the current engine.

**Strengths**
- Normalized toxin damage avoids balance blow-ups.
- Splash is routed through the existing deferred-delta model in `SimulationEngine.processInteractions` (`src/main/java/com/paralife/engine/SimulationEngine.java:141`).
- Coverage explicitly includes the `ActionResolver` composite attack path at `resolveAttackerAttack` (`src/main/java/com/paralife/engine/ActionResolver.java:573`).

**Concerns**
- `MEDIUM`: BondedPair toxin resistance semantics are still implicit. If resistance is derived from only one member type, toxin balance becomes order-sensitive.
- `LOW`: the cold-grid fast path is good, but `toxinGridIsAllZero()` is still an O(area) scan every idle tick.

**Suggestions**
- State a BondedPair rule explicitly: `primary`, `secondary`, `average`, or `max`.
- If idle-scan cost matters later, track a non-zero-cell count instead of scanning the whole grid.

**Risk Assessment** — `LOW-MEDIUM` — feature-complete and well-shaped; remaining issues are mostly balance-definition details.

### 14-03

**Summary** — Good coverage overall, but one cure-path bug and one perf regression remain in the proposed implementation details.

**Strengths**
- `outbreakLifetimeTicks` and `zoneDecayTicks` make lifecycle behavior explicit.
- Post-damage buff-grant gating is the right model.
- The BondedPair mutagen decision is finally explicit.

**Concerns**
- `HIGH`: the `reduceInfection()` cure path appears broken. The plan's Phase B grant pass indexes only entities still present in `infections`; a target cured earlier in the tick is removed from that map, so its pending grant has no position lookup and is silently dropped.
- `MEDIUM`: `reduceInfection()` calls `findOccupantById()`, which is a full-grid scan per cure. That undermines the single-pass perf fix the plan adds elsewhere.

**Suggestions**
- Put `Position` in `PendingGrant`, or carry it forward from `Infection`, so combat-cure grants do not depend on the entity remaining in `infections`.
- Remove `findOccupantById()` from the hot path; it reintroduces O(attacks × area) behavior.

**Risk Assessment** — `MEDIUM` — the design is good, but the attack-cure implementation needs one more pass.

### 14-04

**Summary** — Clean, bounded plan. Most risk is inherited from 14-01 rather than local to lightning itself.

**Strengths**
- Dual-radius effect matches the requirement well.
- Uses existing nutrient-level mechanics cleanly.
- Strike-counter semantics are documented instead of implied.

**Concerns**
- `LOW`: correctness still depends on 14-01's env-death path being fixed.
- `LOW`: the counter is "attempted strike", not "fully applied strike"; that's fine, but tests should keep that terminology exact.

**Suggestions**
- Treat this as blocked on 14-01 finalization correctness, but otherwise keep it as-is.

**Risk Assessment** — `LOW` — mechanically straightforward.

### 14-05

**Summary** — Perception changes are mostly sound, but the composite movement redesign still does not fit the current movement implementation.

**Strengths**
- Reading live `SimulationConfig.overcrowdingThreshold()` matches `processOvercrowding` (`src/main/java/com/paralife/engine/SimulationEngine.java:565`).
- Observable-only `HeuristicBrain` changes are appropriately scoped.
- Composite upkeep moving into `CompositeEnergyDistributor.processCompositeEnergy` (`src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:60`) is the right architectural fit.

**Concerns**
- `HIGH`: the new `CompositeState.lastMovementTick` / `moveCooldownTicks` model is layered on top of the existing `compositeTicksSinceMove` speed gate in `ActionResolver.resolveCompositeMovements` (`src/main/java/com/paralife/engine/ActionResolver.java:743`). As written, unbuffed composites become double-gated, and buffed composites are still blocked by the old `moveInterval` path.
- `HIGH`: switching baseline composite movement to a fixed cooldown of 2 ticks changes current composition-based speed behavior, not just buff behavior.
- `MEDIUM`: the sample constant `OVERCROWDED_THRESHOLD_DEFAULT = 5` conflicts with the actual shipped default of 6 in `SimulationConfig.defaults()` (`src/main/java/com/paralife/engine/SimulationConfig.java:35`) and `application.yml` (`src/main/resources/application.yml:33`).

**Suggestions**
- Reuse the existing `moveInterval` model. Make `MOVEMENT_PLUS_1` reduce effective interval or boost effective locomotor speed; do not add a second cooldown system.
- Ensure the documentation constant matches the real shipped default: `6`.

**Risk Assessment** — `HIGH` — perception is good, but the composite movement plan still conflicts with the current action engine.

### 14-06

**Summary** — The test split is good, but the determinism claim is still stronger than the harness actually supports.

**Strengths**
- Splitting deterministic engine coverage from wire-path smoke coverage is the right correction.
- Adding an explicit death counter is much better than inferring deaths from disappeared positions.
- Reusing existing WebSocket test patterns is the right direction.

**Concerns**
- `HIGH`: the deterministic harness still runs through `SimulationEngine.processInteractions` (`src/main/java/com/paralife/engine/SimulationEngine.java:141`), which uses `ThreadLocalRandom` for shuffle/combat/bonding/dissolution. Resetting only `EnvironmentEngine` RNG cannot make the full run deterministic.
- `HIGH`: `resetAll()` clears occupants with `clearEntity`, but `WorldGrid.clearEntity` (`src/main/java/com/paralife/world/WorldGrid.java:106`) preserves nutrients/flags. Compost and lightning fertility changes will leak across runs unless the harness uses `WorldGrid.clear()` (`src/main/java/com/paralife/world/WorldGrid.java:121`) or reconstructs baseline cell state.
- `MEDIUM`: the smoke test depends on instrumenting `BotClient` (`src/main/java/com/paralife/bot/BotClient.java:112`), but that class currently has no callback seam. This is implementable, but not "free".

**Suggestions**
- Either disable non-env randomness for the deterministic test, or scope it to env-only components instead of the full simulation loop.
- Use `worldGrid.clear()` in reset, then explicitly reseed any baseline fertility/world state needed for the run.
- If BotClient instrumentation is too invasive, use the existing raw-WebSocket capture pattern from `PerceptionActionIntegrationTest` for the smoke test and avoid pretending it is a BotClient test.

**Risk Assessment** — `HIGH` — the structure is better, but determinism is not actually guaranteed yet.

### Bottom Line

The plans are close. I would approve 14-02 and 14-04 as low-risk once 14-01 is corrected. I would ask for replanning on 14-01, 14-05, and 14-06, and a targeted fix pass on 14-03. The unresolved issues are specific and fixable, but they are structural enough that they should be corrected before execution.

---

## Consensus Summary

Two reviewers. Sharp divergence on overall risk: Gemini rates the set `LOW` and ready-for-execution; Codex rates multiple plans `HIGH` and asks for replanning on 14-01 / 14-05 / 14-06 plus a targeted 14-03 fix. Codex's critique is grounded in specific file-and-line evidence against the existing codebase, which makes it the stronger signal for the planner to act on. Gemini endorses the architectural direction but does not cross-check against the current implementation.

### Agreed Strengths
- **DeathFinalizer direction (14-01):** both reviewers agree centralizing same-tick death cleanup is the correct architectural move, even if the wiring needs work.
- **Toxin splash via deferred deltas (14-02):** both flag the `SplashDelta` routing through `SimulationEngine.processInteractions` as correct and well-integrated.
- **Post-damage buff-grant gating (14-03):** both endorse the `pendingBuffGrants` / alive-check pattern as the right race-condition solution.
- **Test split in 14-06:** both agree separating deterministic engine tests from wire-path smoke tests is the right structure.

### Agreed Concerns
- **`reduceInfection` perf regression (14-03):** Gemini flags `LOW`, Codex flags `MEDIUM`. Both identify `findOccupantById`'s full-grid scan as undermining the phase's other perf work. Fix is identical: pass `Position` into `reduceInfection`.
- **Circular dependency around `DeathFinalizer` (14-01):** Gemini flags `LOW` (fragility), Codex flags `HIGH` (the cycle is not actually solved by `@Lazy SimulationEngine` because `DeathFinalizer <-> EnvironmentEngine` is a prior cycle). Codex's analysis is more specific and should drive the fix.

### Divergent Views
- **14-01 composite-death semantics:** Codex flags `HIGH` that the plan defers the `remaining >= 2` graceful-degradation / shatter roll to next tick, which silently changes current same-tick behavior at `SimulationEngine.handleMemberDeath:695-701`. Gemini does not mention this. Worth investigating — Codex's claim is concrete and, if correct, is a behavior-change masquerading as a refactor.
- **14-05 composite movement gating:** Codex flags `HIGH` that the new `lastMovementTick` / `moveCooldownTicks` model double-gates with the existing `compositeTicksSinceMove` speed gate in `ActionResolver.resolveCompositeMovements:743`, and that a fixed 2-tick cooldown baseline alters current composition-based speed. Gemini praises the same redesign as "vastly superior" without checking against the action resolver. Codex's integration-level evidence should take precedence.
- **14-05 `OVERCROWDED_THRESHOLD_DEFAULT` constant:** Codex catches a `5` vs shipped `6` mismatch against `SimulationConfig.defaults():35` and `application.yml:33`. Gemini does not.
- **14-06 determinism:** Codex flags `HIGH` that `SimulationEngine.processInteractions` uses `ThreadLocalRandom` for shuffle/combat/bonding/dissolution, so resetting only `EnvironmentEngine` RNG cannot make the full loop deterministic; additionally, `WorldGrid.clearEntity` preserves nutrients/flags so compost/lightning fertility leak across runs. Gemini endorses the test strategy unreservedly. Codex's objection is load-bearing — the deterministic harness will not be deterministic as specified.
- **Overall readiness:** Gemini says "ready for execution." Codex says "approve 14-02/14-04 once 14-01 is corrected; replan 14-01/14-05/14-06; fix pass on 14-03." Recommend treating Codex's verdict as operative because its concerns are backed by file-and-line evidence against the current code.

### Action Items for Planner (priority order)
1. **14-01 — HIGH:** Break the `DeathFinalizer <-> EnvironmentEngine` cycle via narrow interface or event, not `@Lazy`. Preserve same-tick composite-death semantics (`remaining >= 2` graceful-degradation / shatter roll) that currently live in `SimulationEngine.handleMemberDeath:695-701`.
2. **14-05 — HIGH:** Fold `MOVEMENT_PLUS_1` into the existing `compositeTicksSinceMove` / `moveInterval` gate in `ActionResolver.resolveCompositeMovements:743` rather than adding a second cooldown. Fix `OVERCROWDED_THRESHOLD_DEFAULT` constant to `6`.
3. **14-06 — HIGH:** Either disable non-env RNG for the deterministic test or scope it to env-only components. Use `WorldGrid.clear()` instead of `clearEntity()` in `resetAll()`. Replace BotClient instrumentation with the existing raw-WebSocket capture pattern from `PerceptionActionIntegrationTest`.
4. **14-03 — MEDIUM/HIGH:** Add `Position` to `PendingGrant` (or carry from `Infection`) so combat-cure grants survive targets that are cured out of the `infections` map mid-tick. Drop `findOccupantById()` from the hot path.
5. **14-02 — MEDIUM:** State explicit BondedPair toxin-resistance rule (`primary` / `secondary` / `average` / `max`).
6. **14-02 / cross-cutting — LOW:** Track non-zero toxin-cell count instead of O(area) `toxinGridIsAllZero()` scan; consolidate `entityIdOf` helper.
