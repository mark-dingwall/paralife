---
phase: 14
reviewers: [gemini, codex]
reviewed_at: 2026-04-17T07:36:29Z
plans_reviewed:
  - 14-01-PLAN.md
  - 14-02-PLAN.md
  - 14-03-PLAN.md
  - 14-04-PLAN.md
  - 14-05-PLAN.md
  - 14-06-PLAN.md
cycle: 4
---

# Cross-AI Plan Review — Phase 14: Environmental Rules (cycle 4)

## Gemini Review

This review evaluates implementation plans 14-01 through 14-06 for introductory environmental rules. These plans represent the **cycle 4** revision, incorporating exhaustive feedback from cycle 3 regarding bean cycles, same-tick death semantics, and execution model alignment.

### Overall Verdict: READY FOR EXECUTION (with minor fixes)

The cycle 4 plans are of exceptional quality. The "Death Model" conflict that dominated cycle 3 has been elegantly resolved through a narrow interface and canonical Spring patterns. Performance regressions and determinism leaks have been aggressively hunted and closed. The project is ready for execution, provided two small BondedPair cleanup details are addressed.

---

### Plan 14-01: Foundation
**Summary**
Establishes the cross-cutting state and cleanup infrastructure. The centerpiece is the `DeathFinalizer`, which centralizes solo-death cleanup while delegating composite-death decisions back to the `SimulationEngine` to preserve existing 97/3 roll semantics.

**Strengths**
- **Cycle Break:** Effectively uses the `DeathCleanupHooks` narrow interface to break the `DeathFinalizer <-> EnvironmentEngine` construction cycle.
- **Semantic Integrity:** Preserves the same-tick graceful-degradation vs. shatter behavior for composite members, addressing the "silent behavior change" risk from cycle 3.
- **Short-circuiting:** Incorporates the `envDamageAppliedThisTick` flag to avoid wasteful grid scans on idle ticks.

**Concerns**
- **MEDIUM [Infection Key Drift]:** `finalizeBondedPairDeath` (Task 2) calls `clearInfectionOnDeath` for the primary and secondary particle IDs. However, Plan 14-03 (Task 2) keys BondedPair infections in the engine map by `bp.id()`. The cleanup must also remove the `bp.id()` key to prevent map leaks.

**Suggestions**
- In `DeathFinalizer.finalizeBondedPairDeath`, add a call to `hooks.clearInfectionOnDeath(bp.id())`.

**Risk Assessment: LOW**
The architecture is now safe and well-ordered.

---

### Plan 14-02: Toxin Spread
**Summary**
Introduces the first weather event with smooth spline paths and double-buffered CA diffusion.

**Strengths**
- **Non-Zero Counter:** Implements the `nonZeroToxinCellCount` O(1) idle-tick optimization.
- **Consolidated Helpers:** Creates `EntityIds.entityIdOf` to prevent logic duplication across the engine and broadcaster.
- **Normalised Damage:** Correctly scales intensity (0..255) to a fraction before applying base damage.

**Concerns**
- None. The plan is technically complete and well-optimized.

**Risk Assessment: LOW**

---

### Plan 14-03: Mutagen Outbreak
**Summary**
Adds a stochastic strain-gossip effect that rewards survivors with buffs.

**Strengths**
- **Cure-Path Fix:** Solves the mid-tick eviction bug by carrying `Position` in the `PendingGrant`. Grants now survive if the target is cured by combat earlier in the tick.
- **O(Attacks) Attack-Cure:** signature for `reduceInfection` now accepts `Position`, eliminating the O(Area) grid scan during the interaction phase.
- **Alive Gate:** Properly gates buff grants on a post-damage check, preventing post-mortem rewards.

**Concerns**
- **MEDIUM [Bonding Cleanup]:** Bonding formation replaces two Particles with a `BondedPair`. The plan misses a hook in `SimulationEngine.processInteractions` to clear the infection map for the constituent particles when a bond forms.

**Suggestions**
- In `SimulationEngine.processInteractions`, after a `BondFormation` result is applied, call `environmentEngine.clearInfectionOnDeath()` for both the predator and prey IDs.

**Risk Assessment: LOW-MEDIUM** (pending the bonding hook).

---

### Plan 14-04: Lightning Strike
**Summary**
Implements a high-impact, single-tick event for damage and fertility.

**Strengths**
- **Deterministic Counter:** Counter semantics are explicitly "attempted-strike" and tested.
- **Inherited Semantic Test:** Includes a test proving lightning-killed composite members update the registry same-tick via the 14-01 finalizer.

**Concerns**
- None.

**Risk Assessment: LOW**

---

### Plan 14-05: Perception & Buffs
**Summary**
Wires environmental state into bot perception and applies active buffs to simulation logic.

**Strengths**
- **Composite Movement Redesign:** Correctly modulates the *existing* `moveInterval` speed gate rather than layering a second cooldown system. Baseline behavior for unbuffed composites is strictly preserved.
- **Live Config Read:** Vision-scoped overcrowding uses the live `simulationConfig` instance rather than a static constant.
- **Exhaustive Attack Wiring:** Applies `ATTACK_PLUS_1` to all 6 attack sites identified in cycle 3.

**Concerns**
- None.

**Risk Assessment: LOW**

---

### Plan 14-06: Integration Test
**Summary**
Splits validation into a deterministic engine-only harness and a full-stack smoke test.

**Strengths**
- **Honest Determinism:** Scopes the determinism test to `onTickEnvOnlyForTest`, acknowledging that `SimulationEngine`'s use of `ThreadLocalRandom` is unreseedable.
- **Full Wipe:** Uses `WorldGrid.clear()` in resets to prevent nutrient/compost leakage across runs.
- **Capture Pattern:** Reuses existing WebSocket capture patterns instead of invasive `BotClient` modifications.

**Concerns**
- None.

**Risk Assessment: LOW**

---

### Cycle-3 Follow-up

| Prior Concern | Status | Evidence |
| :--- | :--- | :--- |
| **14-01**: DeathFinalizer Cycle | **RESOLVED** | Uses `DeathCleanupHooks` interface break. |
| **14-01**: Composite Death Semantics | **RESOLVED** | Delegates to `handleMemberDeath` inline same-tick. |
| **14-02**: BondedPair Resistance Rule | **RESOLVED** | Explicit MAX rule in `resolveToxinCollisions`. |
| **14-02**: Toxin Idle Scan | **RESOLVED** | `nonZeroToxinCellCount` field + CA step capture. |
| **14-03**: Cure-path Bug (Eviction) | **RESOLVED** | `PendingGrant` carries `Position` for lookup. |
| **14-03**: `reduceInfection` Scan | **RESOLVED** | Signature updated to include `Position`. |
| **14-05**: Composite Move Gate | **RESOLVED** | Modulates existing `moveInterval`. No fields added. |
| **14-05**: Overcrowded Constant | **RESOLVED** | Constant set to 6; live config read prioritized. |
| **14-06**: Determinism Leak | **RESOLVED** | Scoped to env-only phases; `WorldGrid.clear()`. |
| **14-06**: BotClient Scope Creep | **RESOLVED** | Uses `StandardWebSocketClient` capture pattern. |
| **Cross-Plan**: consolidated entityIdOf | **RESOLVED** | `EntityIds` static utility created. |
| **Cross-Plan**: flags vs status Javadoc | **RESOLVED** | Added to `Messages.CellView`. |

---

### Final Instructions for Execution

1.  **Modify 14-01 Task 2:** In `DeathFinalizer.finalizeBondedPairDeath`, ensure `hooks.clearInfectionOnDeath(bp.id())` is called.
2.  **Modify 14-03 Task 3:** In `SimulationEngine`, when the `BondFormation` result is applied, call `environmentEngine.clearInfectionOnDeath` for the predator and prey IDs.
3.  Proceed with execution wave 1. The architecture is sound.

---

## Codex Review

## 14-01
**Summary**  
The replan fixes the semantic part of the prior review well: it keeps composite-member death resolution same-tick by delegating back to `SimulationEngine.handleMemberDeath`, and it places `EnvironmentEngine` at `@Order(14)` ahead of `CompositeEnergyDistributor`’s existing `@Order(15)` (`src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:53`). The remaining problem is structural: the claimed bean-cycle fix is not actually a fix in the plan as written, and parts of the test plan no longer match the real registry/test API.

**Strengths**
- Preserves the existing same-tick 97/3 composite-death semantics by routing env-killed members back through `SimulationEngine.handleMemberDeath` (`14-01-PLAN.md:34-39`; current behavior at `src/main/java/com/paralife/engine/SimulationEngine.java:665-703`).
- Correctly moves env work to `@Order(14)`, which fits the live pipeline between `SimulationEngine` and `CompositeEnergyDistributor` (`14-01-PLAN.md:30`; `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:53`).
- Centralizes shared death cleanup, which should reduce orphaned `BotRegistry`/buff state.

**Concerns**
- `HIGH` The bean cycle is still present. The plan says `DeathFinalizer` depends on `DeathCleanupHooks` implemented by `EnvironmentEngine` (`14-01-PLAN.md:33,54-60`), while `EnvironmentEngine` still injects `DeathFinalizer` (`14-01-PLAN.md:59-60,899-929`). That is still `EnvironmentEngine -> DeathFinalizer -> EnvironmentEngine`, just via an interface.
- `MEDIUM` The test plan relies on per-method `@TestPropertySource` toggles for dissolution chance (`14-01-PLAN.md:1191-1194,1229-1232`), but the current codebase uses class-level `@TestPropertySource` only (`src/test/java/com/paralife/engine/PerceptionActionIntegrationTest.java:32-39`, `src/test/java/com/paralife/engine/CompositeIntegrationTest.java:46-76`).
- `MEDIUM` `EnvDeathSweepTest` uses a nonexistent composite registration shape (`14-01-PLAN.md:1212-1213,1252-1253`). The live API is `CompositeRegistry.register(String, List<String>, Map<String, Position>, int, int)`, not `register(CompositeState)` (`src/main/java/com/paralife/engine/CompositeRegistry.java:134-145`).
- `LOW` The new env tests live under `src/test/java/com/paralife/engine/environment/...` (`14-01-PLAN.md:16-18`) while the plan also leans on package-private test hooks in `com.paralife.engine`; that package split will block access unless those hooks are made public or the tests move into `com.paralife.engine`.

**Suggestions**
- Replace the `DeathCleanupHooks` implementation on `EnvironmentEngine` with a separate bean, or invert the dependency so `DeathFinalizer` publishes cleanup events instead of calling back into the engine.
- Split the dissolution-branch tests into separate classes or use direct unit construction instead of method-level property overrides.
- Rewrite `EnvDeathSweepTest` against the real `CompositeRegistry.register(...)` signature.
- Keep env test classes in `com.paralife.engine` if they need package-private helpers.

**Risk Assessment**  
`HIGH` — same-tick semantics are fixed, but the construction graph is still unsafe and parts of the test plan will not execute as written.

---

## 14-02
**Summary**  
The toxin plan is materially better than cycle 3: BondedPair resistance is now explicit, the idle-grid scan concern is addressed with `nonZeroToxinCellCount`, and `EntityIds` is consolidated. The remaining gap is on the composite-attack path: toxic splash added inside `ActionResolver` occurs after the env death sweep, so lethal splash damage is not finalized same tick.

**Strengths**
- Explicit BondedPair toxin-resistance rule is now defined (`14-02-PLAN.md:449,566-569`).
- Replaces the O(area) idle-grid scan with `nonZeroToxinCellCount` tracking (`14-02-PLAN.md:42,114-115,505-529`).
- Consolidates `entityIdOf` into a shared `EntityIds` helper (`14-02-PLAN.md:45,298-325`).

**Concerns**
- `HIGH` Same-tick death finalization is broken for toxic splash on composite attacks. The plan adds splash inside the composite attack path (`14-02-PLAN.md:756-767`), but `processEnvDeaths()` only runs in `EnvironmentEngine`’s earlier tick phase (`14-01-PLAN.md:42-44`), while `ActionResolver` runs later (`src/main/java/com/paralife/engine/ActionResolver.java:156,573-627`). A lethal splash kill will linger until next tick.
- `MEDIUM` The plan repeatedly references `ActionResolver.resolveCompositeAttack` (`14-02-PLAN.md:38,66,184,730,738,756`), but the live method is `resolveAttackerAttack` (`src/main/java/com/paralife/engine/ActionResolver.java:573-627`).
- `MEDIUM` The new tests are in `com.paralife.engine.environment` (`14-02-PLAN.md:262-283`) but the plan also relies on package-private helpers like `ToxinPathGenerator.catmullRom` and env test hooks (`14-02-PLAN.md:268,636-643`).

**Suggestions**
- Either move composite toxic splash into a same-tick death path, or explicitly add a post-`ActionResolver` death-finalization phase if splash can kill.
- Rename all `resolveCompositeAttack` references to `resolveAttackerAttack` in the plan and acceptance criteria.
- Align test packages with helper visibility.

**Risk Assessment**  
`MEDIUM` — the toxin model itself is sound, but the composite attack/splash timing bug is real and changes death semantics.

---

## 14-03
**Summary**  
The replan does fix the original pending-grant bug and removes the `findOccupantById` hot-path scan. The new issue is timing: the “immediate buff on early cure” contract now works for pre-env combat, but not for the later `ActionResolver` composite-attack path, because the grant queue is only drained during `EnvironmentEngine`’s tick.

**Strengths**
- Fixes the original cure-path loss by carrying `Position` in `PendingGrant` (`14-03-PLAN.md:32-38,97-99,287,315-325`).
- Removes the `findOccupantById` full-grid scan from the hot path (`14-03-PLAN.md:32,99,295,529`).
- Makes mutagen lifecycle and survivor-buff timing more explicit.

**Concerns**
- `HIGH` “Receives buff immediately” is still false for composite attacks. The grant queue is applied in `tickBuffsAndInfections` (`14-03-PLAN.md:291-295,386-451`), but the composite cure trigger is wired in the later action phase (`14-03-PLAN.md:711-721`; live attack path at `src/main/java/com/paralife/engine/ActionResolver.java:573-627`). That means composite attack-cures will not update buffs/status/perception until next tick.
- `MEDIUM` The plan again targets nonexistent `resolveCompositeAttack` sites (`14-03-PLAN.md:58,81,99,630,639,711,721`) instead of `resolveAttackerAttack` (`src/main/java/com/paralife/engine/ActionResolver.java:573-627`).
- `MEDIUM` The test package/helper mismatch persists: tests are under `src/test/java/com/paralife/engine/environment/MutagenTest.java` (`14-03-PLAN.md:15`) but rely on package-private env hooks and even a package-private field write (`14-03-PLAN.md:567-590,829`).

**Suggestions**
- For the `ActionResolver` composite attack path, either grant the buff inline on cure or add a same-tick post-action infection/buff reconciliation step before perception.
- Fix all `resolveCompositeAttack` references.
- Move mutagen tests into `com.paralife.engine` or widen helper visibility intentionally.

**Risk Assessment**  
`MEDIUM` — the original cycle-3 bug is fixed, but the composite early-cure semantics are still wrong.

---

## 14-04
**Summary**  
The lightning design is clean and bounded, and the attempted-strike counter semantics are clearer than before. The weak point is the test harness: the new same-tick composite-death regression test cannot validate what it claims, because it wires `DeathFinalizer` to a mocked `SimulationEngine` while expecting real composite-registry side effects.

**Strengths**
- Dual-radius damage/fertility behavior is well specified.
- Attempted-strike counter semantics are now explicit (`14-04-PLAN.md:23-25,52-54`).
- Adds direct same-tick death coverage for lightning, which is the right inheritance from 14-01.

**Concerns**
- `MEDIUM` `LightningTest` cannot prove same-tick composite cleanup as written. It constructs `DeathFinalizer` with `SimulationEngine sim = mock(SimulationEngine.class)` (`14-04-PLAN.md:286-289`), but then expects `compositeRegistry.removeMember(...)` to have happened (`14-04-PLAN.md:339-353`). That side effect lives behind the real `SimulationEngine.handleMemberDeath` path (`src/main/java/com/paralife/engine/SimulationEngine.java:665-703`), not a mock.
- `MEDIUM` The test file is again in `com.paralife.engine.environment` (`14-04-PLAN.md:271`) while calling package-private env test hooks like `applyLightningAtForTest` / `processEnvDeathsForTest` (`14-04-PLAN.md:299,333-350`).

**Suggestions**
- Use a real `SimulationEngine` in the same-tick composite-death lightning test, or reduce the test to verifying delegation only.
- Align test package and helper visibility before execution.

**Risk Assessment**  
`MEDIUM` — the feature plan is fine, but the main regression proof is currently invalid.

---

## 14-05
**Summary**  
This plan incorporates most of the important cycle-3 feedback: the overcrowding default is corrected to 6, `CellView.flags` vs `cellStatus` is documented, and composite movement now reuses the live `moveInterval` gate instead of inventing a second cooldown model. The remaining miss is composite sensor buffs: solo perception expands to 7x7, but stitched composite sensor coverage is still fixed at 5x5 in the live broadcaster and is not updated in the plan.

**Strengths**
- Corrects `OVERCROWDED_THRESHOLD_DEFAULT` to match shipped config (`14-05-PLAN.md:27,121,247-248`; `src/main/java/com/paralife/engine/SimulationConfig.java:35-37`; `src/main/resources/application.yml:33`).
- Fixes the prior composite movement model by reusing `compositeTicksSinceMove` / `moveInterval` (`14-05-PLAN.md:33,96-119,490,561-573`; live gate at `src/main/java/com/paralife/engine/ActionResolver.java:743-771`).
- Adds the missing `flags` vs `cellStatus` documentation (`14-05-PLAN.md:28,271-295`).

**Concerns**
- `MEDIUM` Composite `SENSOR_PLUS_1` is still missing. The plan only expands solo-bot radius in `buildPerception` (`14-05-PLAN.md:248-252,317-340`), but composite vision today comes from `stitchSensorCoverage`, which uses fixed `PERCEPTION_RADIUS` around SENSOR members (`src/main/java/com/paralife/engine/PerceptionBroadcaster.java:205-227`).
- `MEDIUM` The plan still refers to nonexistent `resolveCompositeAttack` sites (`14-05-PLAN.md:32,44,66,80,209,473,489,613`); the live method is `resolveAttackerAttack` (`src/main/java/com/paralife/engine/ActionResolver.java:573-627`).
- `LOW` Task 3 modifies `BotClient.java`, but `files_modified` does not list it (`14-05-PLAN.md` task/file metadata drift).

**Suggestions**
- Update `stitchSensorCoverage` so each SENSOR member uses radius 3 when that member has `SENSOR_PLUS_1`.
- Rename the composite attack hook points to `resolveAttackerAttack` everywhere, including acceptance criteria.
- Keep the `files_modified` list synchronized with the task body.

**Risk Assessment**  
`MEDIUM` — most cycle-3 issues are fixed, but the composite sensor-buff path is still incomplete.

---

## 14-06
**Summary**  
This is a strong correction of the cycle-3 determinism problems. The plan now scopes determinism honestly to env-owned phases, resets with `WorldGrid.clear()`, and uses the existing raw-WebSocket capture pattern instead of inventing BotClient seams. The remaining issue is narrower: the determinism test no longer checks all of the observables the plan claims it will, especially nutrient totals.

**Strengths**
- Correctly limits determinism to env-only execution via `onTickEnvOnlyForTest` (`14-06-PLAN.md:23,27,69-92,384-398,517-518`).
- Fixes the reset leak by using `WorldGrid.clear()` instead of `clearEntity()` (`14-06-PLAN.md:26,96,386,497-506`; current world behavior at `src/main/java/com/paralife/world/WorldGrid.java:103-132`).
- Reuses the shipped raw-WebSocket capture pattern from `PerceptionActionIntegrationTest` (`14-06-PLAN.md:24,98,630-639,676-687`; current pattern at `src/test/java/com/paralife/engine/PerceptionActionIntegrationTest.java:74-121`).

**Concerns**
- `MEDIUM` The plan says deterministic double-run assertions include grid-scale nutrient totals (`14-06-PLAN.md:29`), but the proposed `RunObservables` and equality assertions omit them (`14-06-PLAN.md:459-466,488-493`). That weakens coverage for compost/lightning fertility leakage, which is exactly what this harness is meant to guard.
- `LOW` The same test only snapshots infection count at tick 150, not any nutrient/compost aggregate; if you keep the env-only scope, nutrient totals are the more valuable cross-run invariant.

**Suggestions**
- Add `totalNutrients` (or center + ring fertility totals) to `RunObservables` and compare it across both runs.
- Keep the current env-only boundary and raw-WebSocket smoke design; those parts are correct.

**Risk Assessment**  
`LOW-MEDIUM` — the structure is now right; it just needs one more observability assertion to match its own stated scope.

---

## Cycle-3 Follow-Up
- `14-01 bean cycle` — `UNRESOLVED`. The plan still has `EnvironmentEngine -> DeathFinalizer -> DeathCleanupHooks(EnvironmentEngine)` (`14-01-PLAN.md:33,54-60,899-929`).
- `14-01 same-tick composite death semantics` — `RESOLVED`. Env-killed composite members are delegated back to `SimulationEngine.handleMemberDeath` same tick (`14-01-PLAN.md:34-39`; `src/main/java/com/paralife/engine/SimulationEngine.java:665-703`).
- `14-02 BondedPair toxin resistance semantics` — `RESOLVED`. Explicit rule now present (`14-02-PLAN.md:449,566-569`).
- `14-02 idle toxin-grid scan / non-zero tracking` — `RESOLVED`. `nonZeroToxinCellCount` added (`14-02-PLAN.md:42,114-115,505-529`).
- `14-03 pending grant lost when cure removes infection entry` — `RESOLVED`. `PendingGrant` now carries `Position` (`14-03-PLAN.md:32-38,97-99,287,315-325`).
- `14-03 findOccupantById perf regression` — `RESOLVED`. Hot-path grid scan removed (`14-03-PLAN.md:32,99,295,529`).
- `14-05 composite movement double-gate / fixed cooldown regression` — `RESOLVED`. Buff now modulates existing `moveInterval` (`14-05-PLAN.md:33,96-119,490,561-573`; `src/main/java/com/paralife/engine/ActionResolver.java:743-771`).
- `14-05 overcrowding default 5 vs shipped 6` — `RESOLVED`. Plan now uses 6 (`14-05-PLAN.md:27,121,247-248`; live defaults at `SimulationConfig.java:35-37` and `application.yml:33`).
- `14-05 flags vs cellStatus documentation` — `RESOLVED`. Added in `Messages.CellView` plan text (`14-05-PLAN.md:28,271-295`).
- `14-06 determinism overclaim` — `RESOLVED`. Harness is now env-only, not full-pipeline (`14-06-PLAN.md:23,27,69-92,384-398,517-518`).
- `14-06 clearEntity reset leak` — `RESOLVED`. Reset now uses `WorldGrid.clear()` (`14-06-PLAN.md:26,96,386,497-506`; live semantics at `WorldGrid.java:103-132`).
- `14-06 BotClient instrumentation scope creep` — `RESOLVED`. Replaced with raw `StandardWebSocketClient` capture (`14-06-PLAN.md:24,98,630-639,676-687`).
- `cross-cutting entityIdOf consolidation` — `RESOLVED`. Shared `EntityIds` helper added in toxin plan (`14-02-PLAN.md:45,298-325`).
- `Gemini grid-scan-density perf debt` — `PARTIAL`. Toxin idle scans are fixed, but the overall phase still intentionally adds several area passes; that is acceptable for Phase 14 but remains perf debt for later scale work.

## Overall Verdict
**Targeted fixes required before execution.**

The plan set is much closer than cycle 3, and 14-05/14-06 in particular improved substantially. But I would not start execution yet because there are still two correctness-class blockers and several execution mismatches:

- `14-01` still has an unresolved Spring construction cycle.
- `14-02` and `14-03` still mishandle same-tick semantics on the later `ActionResolver` composite-attack paths.
- `14-01` through `14-04` contain plan/test mismatches against the real codebase: wrong registry API, wrong method names, and package/test visibility problems.

I would do one more correction pass, not a full phase rewrite. The minimum fix set is:
1. Rework the `DeathFinalizer`/`EnvironmentEngine` dependency graph so it is actually acyclic.
2. Rename all `resolveCompositeAttack` references to `resolveAttackerAttack`.
3. Decide whether composite toxic splash / attack-cure must finalize same tick; if yes, add a post-action finalization step or inline handling.
4. Fix the env test package/API mismatches.
5. Extend 14-05 to apply `SENSOR_PLUS_1` to composite stitched vision.
6. Add nutrient-total assertions to 14-06 determinism coverage.

With those changes, the plans would be ready and would satisfy R12-R14 cleanly.

---

## Consensus Summary

Two reviewers; wide verdict gap. **Gemini**: READY FOR EXECUTION (LOW) with two small BondedPair cleanup fixes. **Codex**: TARGETED FIXES REQUIRED before execution — flags a `HIGH` structural bean-cycle blocker on 14-01, `HIGH` same-tick attack-path blockers on 14-02 and 14-03, plus several plan/test mismatches against the real API across 14-01…14-05. Codex's objections are backed by file-and-line evidence against the current codebase; Gemini's approval is architectural but does not cross-check the composite attack path or the Spring construction graph. The planner should treat Codex's verdict as operative and Gemini's as cycle-3-delta-validation (which is useful and mostly corroborates RESOLVED status).

### Agreed RESOLVED from cycle 3
- **14-01 same-tick composite-death semantics** — both agree the delegation to `SimulationEngine.handleMemberDeath` preserves the 97/3 graceful-degradation / shatter behavior same tick.
- **14-02 BondedPair toxin resistance + idle-scan perf** — both confirm explicit rule + `nonZeroToxinCellCount` tracking.
- **14-03 pending-grant eviction bug + `findOccupantById` hot path** — both confirm `Position` now carried in `PendingGrant` and scan removed.
- **14-05 composite movement gating + overcrowding default** — both confirm reuse of `moveInterval` and constant correction to 6.
- **14-05 flags vs cellStatus** — both confirm documentation added.
- **14-06 determinism scope + reset leak + BotClient scope** — both confirm env-only harness, `WorldGrid.clear()`, raw-WebSocket capture.
- **Cross-cutting `entityIdOf` consolidation** — both confirm `EntityIds` helper.

### Agreed NEW concerns introduced by cycle 4
- **BondedPair infection-map cleanup gaps** — both flag cleanup hooks missing, but target different call sites:
  - Gemini (`14-01 MEDIUM`): `finalizeBondedPairDeath` should also clear by `bp.id()` (death path).
  - Gemini (`14-03 MEDIUM`): `processInteractions` should clear by constituent particle IDs when a `BondFormation` lands (formation path).
  - Codex does not flag these specifically, but they are structurally consistent with its "map-leak risk" patterns. Fix both sides.

### Divergent Views (Codex-only, load-bearing)
- **14-01 Spring bean cycle — `UNRESOLVED` (Codex HIGH) vs `RESOLVED` (Gemini)**. Gemini argues the `DeathCleanupHooks` interface breaks the cycle. Codex counters: `DeathFinalizer` still needs a `DeathCleanupHooks` impl (Spring wires `EnvironmentEngine`) while `EnvironmentEngine` still injects `DeathFinalizer` — the cycle persists at bean construction. Interface narrowing is semantic, not topological. Codex is correct on Spring's dependency graph; the named `@Lazy SimulationEngine` is not the right mitigation because the cycle is pre-`SimulationEngine`. Fix: either move the `DeathCleanupHooks` impl onto a distinct bean, invert via `ApplicationEventPublisher`, or use `@Lazy` on one of the two legs. The interface-break claim should be dropped from the plan.
- **14-02 toxic splash same-tick death on composite attacks — Codex HIGH, Gemini silent**. Codex: splash added inside `ActionResolver` composite attack path can be lethal, but `EnvironmentEngine.processEnvDeaths()` already ran at `@Order(14)`, so splash-kills linger until next tick. Gemini does not analyze the action-resolver timing. Either inline the finalization, add a post-action env-death sweep, or document the one-tick lag as acceptable.
- **14-03 composite early-cure buff timing — Codex HIGH, Gemini silent**. Same shape as 14-02: composite attack-cures fire in `ActionResolver`, but the pending-grant queue drains in `EnvironmentEngine`'s earlier phase. "Receives buff immediately" is true for solo/env-triggered cures, false for composite attacks. Gemini endorses the race-condition fix without checking the two drain sites. Either grant inline on composite cure, or add a post-action reconciliation step before perception.
- **Plan/test API mismatches (14-01, 14-02, 14-03, 14-04, 14-05) — Codex MEDIUM, Gemini silent**:
  - `resolveCompositeAttack` references throughout — the live method is `resolveAttackerAttack` at `ActionResolver.java:573-627`. Blanket rename needed.
  - `CompositeRegistry.register(CompositeState)` — live API is `register(String, List<String>, Map<String, Position>, int, int)` at `CompositeRegistry.java:134-145`.
  - Per-method `@TestPropertySource` — codebase only uses class-level.
  - `com.paralife.engine.environment` test package — tests rely on package-private helpers in `com.paralife.engine`; either move tests up or widen visibility intentionally.
- **14-05 composite `SENSOR_PLUS_1` gap — Codex MEDIUM, Gemini silent**. `buildPerception` expands solo radius, but `stitchSensorCoverage` (`PerceptionBroadcaster.java:205-227`) still uses fixed `PERCEPTION_RADIUS` around SENSOR members. Composite sensor buffs are dead-letter.
- **14-06 determinism observability gap — Codex MEDIUM, Gemini silent**. Plan (`14-06:29`) claims double-run assertions include grid-scale nutrient totals; `RunObservables` / equality assertions at `14-06:459-466,488-493` omit them. This is the exact dimension the harness is meant to guard for compost/lightning fertility leakage.
- **Overall readiness**: Gemini says proceed with wave 1 + two fixes. Codex enumerates six minimum corrections before execution. Recommend Codex's verdict — the bean-cycle and same-tick-attack-path issues are correctness-class, not polish.

### Action Items for Planner (priority order)
1. **14-01 HIGH — Rework `DeathFinalizer`/`EnvironmentEngine` dependency graph so it is actually acyclic.** `DeathCleanupHooks` interface narrowing does not break the Spring construction cycle. Options: (a) extract hooks into a third bean, (b) use `ApplicationEventPublisher` events for cleanup, (c) add `@Lazy` on the leg that permits it. Drop the "interface-break solves it" claim from plan text.
2. **14-02 HIGH + 14-03 HIGH — Resolve composite attack-path same-tick semantics.** Toxic splash (14-02) and attack-cure buff grants (14-03) both fire in `ActionResolver.resolveAttackerAttack` *after* `EnvironmentEngine`'s env-death and buff-grant passes. Either: (a) inline death finalization + buff grant in the attack path, (b) add a post-action reconciliation phase at `@Order(~25)`, or (c) document and accept a one-tick lag explicitly (but note this contradicts plan claims).
3. **14-01..14-05 MEDIUM — Rename all `resolveCompositeAttack` → `resolveAttackerAttack`** in plan bodies, acceptance criteria, and test stubs. The live method name is authoritative.
4. **14-01 MEDIUM — Fix `EnvDeathSweepTest` to use the real `CompositeRegistry.register(String, List<String>, Map<String, Position>, int, int)` signature.** No `register(CompositeState)` method exists.
5. **14-01 MEDIUM — Replace per-method `@TestPropertySource` with class-level** (or use constructor-injected config overrides). Match the `PerceptionActionIntegrationTest` / `CompositeIntegrationTest` patterns.
6. **14-01..14-05 LOW — Resolve env-test package placement vs package-private helper access.** Move tests into `com.paralife.engine` or widen helper visibility intentionally.
7. **14-01 MEDIUM — Add `hooks.clearInfectionOnDeath(bp.id())`** in `DeathFinalizer.finalizeBondedPairDeath` (Gemini).
8. **14-03 MEDIUM — Clear infection map by constituent particle IDs on `BondFormation`** in `SimulationEngine.processInteractions` (Gemini).
9. **14-05 MEDIUM — Propagate `SENSOR_PLUS_1` into `stitchSensorCoverage`** so composite SENSOR members actually expand their stitched radius.
10. **14-06 MEDIUM — Add `totalNutrients` (or center/ring fertility totals) to `RunObservables`** and include in double-run equality assertion, so compost/lightning fertility drift is actually guarded.
11. **14-05 LOW — Sync `files_modified` list** with Task 3 body (currently omits `BotClient.java`).

### Overall Verdict

**Replan cycle 4 with the 6 Codex-identified correctness fixes + 5 execution-mismatch corrections above.** After that pass, the plans should be genuinely executable and satisfy R12–R14 cleanly. Expected effort: one targeted replan cycle, not a rewrite.
