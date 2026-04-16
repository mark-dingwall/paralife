---
phase: 14
reviewers: [gemini, codex]
reviewed_at: 2026-04-16T21:00:33Z
plans_reviewed:
  - 14-01-PLAN.md
  - 14-02-PLAN.md
  - 14-03-PLAN.md
  - 14-04-PLAN.md
  - 14-05-PLAN.md
  - 14-06-PLAN.md
skipped_reviewers:
  - claude (runtime environment — would not be independent)
  - coderabbit, opencode, qwen, cursor (not installed)
---

# Cross-AI Plan Review — Phase 14: Environmental Rules

## Gemini Review

# Phase 14: Environmental Rules - Plan Review

This review covers the six-part execution plan for Phase 14, introducing spatially-propagating environmental effects (toxin, mutagen, lightning, compost) into the Paralife simulation.

## 1. Summary
The plans are exceptionally detailed, demonstrating a deep understanding of both the existing codebase and the numerical requirements of cellular-automaton (CA) simulations. The strategy for integrating four distinct environmental effects is sound, utilizing a robust foundation of shadow registries and double-buffered grids to ensure thread safety and order-independent updates. The inclusion of high-signal emergent mechanics, particularly the "attack-accelerates-cure" gamble, adds significant tactical depth. The testing strategy is comprehensive, moving from unit-level verification of math to seeded integration tests that assert outcomes via direct counters rather than indirect proxies.

## 2. Strengths
*   **Tactical Emergence:** The "attack-accelerates-cure" mechanic (D-20) and "toxic zones as cover" (D-10) are well-designed features that transform environmental hazards into strategic terrain for bots.
*   **Robust Numerical Foundation:** Hand-rolling Catmull-Rom splines and double-buffered CA diffusion keeps the simulation lightweight and dependency-free while avoiding common pitfalls like dirty reads or signed-byte arithmetic errors.
*   **Vision-Scoped Overcrowding:** The decision to compute overcrowding per-bot based only on visible neighbors (D-40) is a sophisticated implementation of incomplete information that preserves server authority.
*   **Pipeline Integrity:** Wrapping `EnvironmentEngine.onTick` in a try/catch (Pitfall 9) and ensuring status caches are cleared/rebuilt even when disabled (Pitfall 7) ensures the tick pipeline remains resilient.
*   **Exhaustive Composite Integration:** The role-specific mutagen buff mapping (D-18) is handled via an exhaustive switch on the `Role` enum, ensuring no "wasted" buffs for specialized composite members.
*   **Seeded Reproducibility:** Using an injectable `Random` in the engine ensures that complex path generation and Poisson rolls can be pinned in tests, eliminating flakes.

## 3. Concerns
*   **Performance: Infection Damage Iteration (Plan 03, Task 1, Step 3g) [Severity: MEDIUM]**
    The `applyInfectionDamage` method in `EnvironmentEngine` iterates the entire grid ($W \times H$) for *each* infected entity to apply damage. If many entities are infected (e.g., 200 entities in a large outbreak on a $256 \times 256$ grid), this results in $13$ million iterations per tick. While Java is fast, this O(Infected $\times$ Area) approach is suboptimal.
*   **Buff Stacking vs. Deduplication (Plan 03, Task 1, Step 3g) [Severity: LOW]**
    For `DEFENDER` roles, both the universal buff (`UPKEEP_MINUS_1`) and the role-specific perk (`UPKEEP_MINUS_1`) are granted. The current `BuffRegistry` uses a `CopyOnWriteArrayList` which will store both. In Plan 05, `SimulationEngine` checks `hasBuff(...)`. If `hasBuff` is a boolean check, the effects won't stack (net -1 decay). If stacking (-2 decay) is intended, the logic must count them. If not intended, the duplicate record is a minor memory leak.
*   **Circular Dependency Handling [Severity: LOW]**
    Using `@Lazy` in `SimulationEngine` to break the cycle with `EnvironmentEngine` is effective, but requires careful testing of the Spring context initialization to ensure proxies are correctly applied to the constructor-injected fields.
*   **HeuristicBrain Constant Brittleness [Severity: LOW]**
    `HeuristicBrain` hardcodes `ATTACK_CURE_REDUCTION_DEFAULT = 3`. While acceptable for an MVP, this creates a disconnect if the server config is changed via `application.yml`.

## 4. Suggestions
*   **Optimize Infection Damage:** Replace the per-entity grid scan in `applyInfectionDamage` with a single grid pass that checks each occupant against the `infections` map, or store the `Position` within the `Infection` record. Since `EnvironmentEngine` runs after `SimulationEngine` deaths but before `ActionResolver` moves, the position is stable during this phase.
*   **Clarify Buff Stacking:** Explicitly decide in Plan 05 whether multiple buffs of the same type stack their numerical benefits. If they do not stack, modify `BuffRegistry.grant` to avoid adding duplicates of the same `BuffType` for the same entity.
*   **Expose Overcrowding Constant:** Ensure `SimulationEngine.OVERCROWDED_THRESHOLD` is a `public static final` constant to allow `PerceptionBroadcaster` and its tests to reference the single source of truth.
*   **Coordinate Spline Wrapping:** Document in `ToxinPathGenerator` that the path generation must handle the toroidal boundary carefully—intermediate waypoints should be generated in "unwrapped" space and wrapped only when converted to grid cells to prevent the spline from "jumping" across the center of the grid.

## 5. Risk Assessment
**Overall Risk: LOW**

The architectural patterns (shadow registries, synchronous events, back-compat constructors) are already proven in earlier phases of the project. The new math elements are self-contained and unit-testable. The performance concern regarding infection damage is the primary risk to the tick budget, but it is easily optimized. The dependency sequence is logical, with the final integration test serving as a robust gate before the Phase 15 protocol overhaul.

---

## Codex Review

## Overall
The phase is well thought through and mostly aligned with the codebase, but there are three cross-plan issues that materially raise execution risk. First, [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:603) removes deaths before the proposed environment phase, so toxin/lightning/infection damage at `@Order(14)` can leave zero-energy entities alive long enough to act and be perceived that tick. Second, [CompositeEnergyDistributor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:48) already owns `@Order(15)`, so ordering is not just an implementation detail; it changes composite semantics. Third, [ActionResolver.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/ActionResolver.java:279) and [PerceptionBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/PerceptionBroadcaster.java:48) have separate solo/composite paths, and several buff/status plans currently cover solo paths better than composite ones.

- Strengths
- The plans are unusually explicit about contracts, test intent, and traceability.
- The shadow-grid plus registry approach fits the existing code style.
- The phase scope does satisfy R12 and R14 in spirit if implemented correctly.

- Concerns
- [HIGH] Environment-caused deaths are not resolved in the same tick under the current ordering.
- [HIGH] Composite-specific pathways are under-covered in plans 02, 03, and 05.
- [MEDIUM] R13 says "use Cell flags system," but the plans mostly use shadow grids plus `cellStatus` projection instead of actual `Cell.flags`; that is fine given the locked context, but the requirement wording should be reconciled.

- Suggestions
- Decide and document one same-tick death model before execution starts.
- Treat composite buff application as a first-class design track, not an extension of solo-entity behavior.
- Prefer per-test `@TestPropertySource` overrides over a global `application-test.yml` for new environment defaults.

- Risk Assessment
- **HIGH**. The plans are strong, but the ordering/death semantics and composite-path gaps can produce behavior that looks "implemented" while still violating the intended phase rules.

## 14-01-PLAN
**Summary**
Good foundation plan. It establishes the right primitives early, but it carries a few structural assumptions that should be corrected before downstream plans build on them.

- Strengths
- Clear interface freeze for `EnvironmentConfig`, `BuffRegistry`, `EnvironmentEngine`, and `Messages.CellView`.
- Good attention to back-compat in `CellView`.
- Compost + buff cleanup are correctly identified as death-hook concerns.

- Concerns
- [HIGH] It does not solve the same-tick env-death problem; later plans inherit that defect.
- [MEDIUM] The `@Lazy` "break the cycle" note appears unnecessary; `SimulationEngine -> EnvironmentEngine` does not create a cycle by itself.
- [MEDIUM] Adding a global `src/test/resources/application-test.yml` with env enabled/seeded can change unrelated Spring tests.
- [LOW] New tests under `com.paralife.engine.environment` do not mirror the source package structure.

- Suggestions
- Replace the "@Lazy to break cycle" idea with a simpler direct dependency unless a real cycle appears.
- Use class-scoped test properties for env config instead of globally enabling env in all test contexts.
- Add an explicit hook contract for future death-side cleanup (`buffs`, `infections`, `compost`) now.

- Risk Assessment
- **MEDIUM**. Strong setup, but it needs one design correction on death ordering and one on test-profile isolation.

## 14-02-PLAN
**Summary**
This is a solid implementation split for toxin, with good math/testing separation. The main risk is that the current plan under-specifies entity-form coverage and same-tick consequences.

- Strengths
- `ToxinPathGenerator`, `CellularAutomaton`, and `ToxinEvent` are sensible separations.
- The CA test plan is good and catches the signed-byte pitfall.
- Pre-sampled paths are pragmatic for "cells per tick" motion.

- Concerns
- [HIGH] The plan's own sketch only fully applies toxin collision damage to `Particle`; `BondedPair` and `CompositeMember` are left as comments.
- [HIGH] Splash damage is wired only through `SimulationEngine` combat, but composite attacker combat lives in `ActionResolver`.
- [HIGH] Zero-energy entities damaged by toxin can still survive into `ActionResolver` on the same tick.
- [MEDIUM] `diffusionRadius` is configured but not actually used; diffusion behavior is effectively hardcoded.
- [MEDIUM] `splashFraction * intensity` on a `0..255` scale is very large; `0.2 * 255 = 51` is a balance red flag.

- Suggestions
- Make toxin effects exhaustive across `Particle`, `BondedPair`, and `CompositeMember`.
- Either normalize intensity to `0..1` for damage/splash formulas or rename/document the 8-bit scaling explicitly.
- Add a fast path so toxin diffusion does not scan the full grid when there is no active toxin and no residual intensity.

- Risk Assessment
- **HIGH**. The math is fine; the game-rule integration is not complete enough yet.

## 14-03-PLAN
**Summary**
Mutagen is the most ambitious sub-plan and the most fragile. The buff-duration handling is good, but the cell-state lifecycle and cleanup story are not complete.

- Strengths
- Capturing `initialTicks` for D-16 is correct.
- The exhaustive role-perk mapping is strong.
- `reduceInfection` is the right public seam for D-20.

- Concerns
- [HIGH] `mutagenGrid` appears to spread but never decay or clear; outbreak zones risk becoming permanent.
- [HIGH] Infection cleanup on death is mentioned in threat/verification text but not actually integrated in the task steps.
- [HIGH] `grantSurvivorBuffs` can grant buffs to dead/missing entities if cleanup timing is off.
- [MEDIUM] `applyInfectionDamage` and `findOccupantById` scan the full grid repeatedly; this can get expensive under heavy infection counts.
- [MEDIUM] Outbreak lifetime is arbitrary and not obviously tied to config.

- Suggestions
- Add explicit mutagen-zone decay/clear semantics to the plan, not just entity infection expiry.
- Add `clearInfectionOnDeath(entityId)` to the main task body, not just the threat model.
- Track infected entity positions or resolve infection against the per-tick grid scan once, rather than full-grid lookup per infected entity.

- Risk Assessment
- **HIGH**. Good mechanics, incomplete lifecycle.

## 14-04-PLAN
**Summary**
Lightning is the cleanest plan. It is focused, testable, and low-overhead, but it still inherits the same ordering problem as toxin and mutagen.

- Strengths
- Simple dual-radius effect with clear rules.
- The `lightningStrikeCount()` counter is a good testing affordance.
- Test coverage is appropriately direct.

- Concerns
- [HIGH] Lightning kills still occur after `SimulationEngine.processDeaths`, so dead entities may persist and act that tick.
- [MEDIUM] The test accessor increments the same strike counter; that is fine, but the contract must be documented clearly.
- [LOW] No dedicated event record is fine, but the plan should state that this is intentionally single-tick state.

- Suggestions
- Pair lightning damage with a same-tick death-finalization pass.
- Keep the counter semantics strict: increment only on real strikes, plus a clearly named test helper wrapper.

- Risk Assessment
- **MEDIUM**. Low algorithmic risk, moderate integration risk.

## 14-05-PLAN
**Summary**
Necessary plan, but currently the weakest in completeness. It closes the loop for solo bots well, yet misses several composite-specific buff applications and adds some unnecessary client work.

- Strengths
- Vision-scoped overcrowding is well targeted and worth testing directly.
- Reusing the SPORE range helper for movement is a good idea.
- Extending `CellView` through `PerceptionBroadcaster` is the right integration point.

- Concerns
- [HIGH] `ATTACK_PLUS_1`, `MOVEMENT_PLUS_1`, and `UPKEEP_MINUS_1` are planned mainly for solo pathways; composite attack/move/upkeep paths live elsewhere.
- [HIGH] Composite universal `UPKEEP_MINUS_1` should affect `CompositeEnergyDistributor`, not just `SimulationEngine.processEnergyDecay`.
- [HIGH] Composite `LOCOMOTOR` movement buff should affect composite movement resolution, not just `resolveMove`.
- [MEDIUM] The proposed HeuristicBrain "commit-or-flee" logic assumes info the protocol does not expose, like remaining infection ticks.
- [MEDIUM] BotClient parsing changes are mostly unnecessary because Jackson already deserializes the expanded `Messages.CellView`.
- [MEDIUM] The test sketch references a hardcoded `SimulationEngine.OVERCROWDED_THRESHOLD`, but the real threshold is config-driven.

- Suggestions
- Split buff application explicitly into solo-path and composite-path work.
- Drop BotClient manual parsing unless you intentionally stop using typed deserialization.
- Simplify HeuristicBrain changes to what bots can actually observe: status bits, distance, and own energy.

- Risk Assessment
- **HIGH**. This plan is the main gap between "environment exists" and "environment actually changes behavior."

## 14-06-PLAN
**Summary**
Good capstone intent, but the current assertions are not reliable enough to be the final gate. Several "must haves" are either indirect or not actually implemented in the task body.

- Strengths
- Manual tick publishing matches existing integration-test style.
- Rising-edge counting for toxin/mutagen is a good pattern.
- Direct lightning assertion via counter is the right idea.

- Concerns
- [HIGH] "Buff observed" is asserted as "buff exists at the end," which can miss valid runs where buffs expired.
- [HIGH] Compost is still asserted via total nutrient sum, which is confounded by lightning/fertility/spawn and is not localized.
- [HIGH] The plan claims perception status-byte observation and deterministic double-run behavior, but those checks are not actually implemented.
- [MEDIUM] Population counting only `Particle` undercounts when bonded/composite forms exist.
- [MEDIUM] Putting env defaults into global `application-test.yml` can bleed into unrelated tests.

- Suggestions
- Track `everBuffed`, `nonZeroStatusSeen`, and a direct compost observable during the run.
- Count `Particle`, `BondedPair`, and `CompositeMember`, like the existing metabolism test does.
- Use `@TestPropertySource` on this test class instead of widening the global test profile.

- Risk Assessment
- **HIGH**. Good direction, but not yet a trustworthy phase gate.

## Bottom Line
The plans are strong enough to proceed, but not as-is. I would approve them only after three fixes are made explicit:

- Same-tick handling for environment-caused deaths.
- Composite-path coverage for toxin/mutagen/buff effects.
- A stricter, direct integration-test strategy for compost, buffs, and status visibility.

With those corrected, the phase looks capable of meeting R12-R14 cleanly.

---

## Consensus Summary

Two reviewers, one diverging strongly on overall risk. Gemini rates the phase LOW risk, treating the plans as architecturally proven with a single performance nit. Codex, grounding claims against specific files and line numbers (`SimulationEngine.java:603`, `CompositeEnergyDistributor.java:48`), rates the phase HIGH risk due to structural ordering/semantics gaps. The divergence itself is the most important signal: Codex found concrete structural problems that Gemini missed, which argues for Codex's assessment being more load-bearing — but Gemini's performance concern is also real and should not be lost.

### Agreed Strengths
- **Contract clarity and traceability** — both cite the explicit interface freeze, test intent, and requirement mapping.
- **Shadow-grid + registry architecture** — both view the pattern as well-suited to the existing codebase style and thread-safety model.
- **Direct/counter-based test assertions** (lightning strike counter, role perk mapping) over indirect proxies.

### Agreed Concerns
- **Infection damage performance (Plan 03)** — both flag `applyInfectionDamage` / full-grid scan per infected entity. Gemini MEDIUM, Codex MEDIUM. Fix: per-tick single grid pass, or store `Position` on the `Infection` record. *(Gemini's proposed fix depends on entities being stable during `EnvironmentEngine` — which collides with Codex's death-ordering concern; address ordering first, then optimize.)*
- **Circular-dependency / `@Lazy` risk (Plan 01)** — Gemini LOW (initialization care), Codex MEDIUM (cycle may not actually exist). Consolidated: re-evaluate whether `@Lazy` is needed at all; if not, drop it.

### Divergent Views (worth investigating)

| Issue | Gemini | Codex | Action |
|---|---|---|---|
| **Overall risk** | LOW | HIGH | Side with Codex — its concerns are file-grounded. |
| **Same-tick environment-caused death** | Not flagged | HIGH (pervades 02/03/04) | Decide and document one same-tick death model in 14-01 before touching 02/03/04. |
| **Composite-path coverage (Plans 02/03/05)** | Not flagged (Gemini believed role mapping covered it) | HIGH — composite buff/damage pathways miss `CompositeEnergyDistributor`, composite `ActionResolver` branch, `BondedPair`/`CompositeMember` toxin damage | Split buff/damage application explicitly into solo-path vs composite-path tasks in 14-05 (and toxin collision in 14-02). |
| **Mutagen-zone decay** | Not flagged | HIGH — `mutagenGrid` spreads but never decays/clears | Add explicit zone decay semantics to 14-03. |
| **Infection cleanup on death** | Not flagged | HIGH — referenced in threat model but missing from task steps | Promote `clearInfectionOnDeath(entityId)` into 14-03 main task body. |
| **Global `application-test.yml`** | Not flagged | MEDIUM — can bleed into unrelated tests | Use `@TestPropertySource` per test class in 14-01 and 14-06. |
| **R13 wording vs shadow grids** | Not flagged | MEDIUM — requirement says "Cell flags system" but plans use shadow grids + `cellStatus` | Reconcile requirement wording or note deviation in CONTEXT.md. |
| **Splash damage scale (0.2×255 = 51)** | Not flagged | MEDIUM balance concern | Normalize intensity to 0..1 or document 8-bit scaling in 14-02. |
| **`diffusionRadius` configured but unused** | Not flagged | MEDIUM dead config | Wire it or remove it in 14-02. |
| **14-06 buff-exists-at-end assertion** | Not flagged | HIGH — misses valid runs with expired buffs | Track `everBuffed` / `nonZeroStatusSeen` booleans across ticks. |
| **14-06 compost via total nutrient sum** | Not flagged | HIGH — confounded by fertility/spawn | Add direct localized compost observable. |
| **Buff stacking semantics** | LOW (Plan 03) | Not flagged | Clarify in 14-05 whether same-type buffs stack numerically. |
| **HeuristicBrain hardcoded `ATTACK_CURE_REDUCTION_DEFAULT`** | LOW cosmetic | Not flagged directly (flagged broader brain scope MEDIUM) | Drive from config or accept as documented MVP constant. |
| **Spline toroidal wrapping** | LOW — wrap only at grid-cell conversion | Not flagged | Document in `ToxinPathGenerator` in 14-02. |

### Recommended Pre-Execution Fixes (prioritized)

1. **[BLOCKER]** Decide same-tick death model in 14-01 (affects 02/03/04).
2. **[BLOCKER]** Add composite-path coverage tasks to 14-02 (toxin collision + splash through `ActionResolver`) and 14-05 (`CompositeEnergyDistributor` + composite movement buff path).
3. **[BLOCKER]** Add mutagen-zone decay semantics + `clearInfectionOnDeath` task to 14-03.
4. **[BLOCKER]** Rewrite 14-06 assertions: `everBuffed` tracking, localized compost observable, explicit status-byte check, composite-aware population count.
5. **[HIGH]** Replace global `application-test.yml` with per-test `@TestPropertySource` in 14-01 and 14-06.
6. **[HIGH]** Fix infection damage algorithm — single per-tick grid pass or `Position` on `Infection` (after death-ordering is decided).
7. **[MEDIUM]** Reconcile R13 wording or add CONTEXT note explaining shadow-grid + `cellStatus` satisfies the flag-system spirit.
8. **[MEDIUM]** Normalize toxin intensity scale or document 8-bit scaling in 14-02; wire or remove `diffusionRadius`.
9. **[LOW]** Drop `@Lazy` unless a real cycle appears; clarify buff stacking; document spline toroidal wrapping.

Feed this review back into planning:
```
/gsd-plan-phase 14 --reviews
```
