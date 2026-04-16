---
phase: 14
reviewers: [gemini, codex]
reviewed_at: 2026-04-16T22:02:14Z
plans_reviewed: [14-01-PLAN.md, 14-02-PLAN.md, 14-03-PLAN.md, 14-04-PLAN.md, 14-05-PLAN.md, 14-06-PLAN.md]
skipped: [claude (self), coderabbit (missing), opencode (missing), qwen (missing), cursor (missing)]
---

# Cross-AI Plan Review — Phase 14

## Gemini Review

# Phase 14: Environmental Rules - Plan Review

This review evaluates the implementation plans (14-01 through 14-06) for introducing spatially-propagating environmental effects into the Paralife simulation.

## 1. Summary
The implementation plans for Phase 14 are exceptionally thorough and demonstrate a high level of technical rigor. They directly address the complex architectural challenges identified in earlier research—specifically the same-tick death problem (Blocker 1), composite entity integration (Blocker 2), and performance bottlenecks (HIGH 2). The strategy of establishing a solid interface foundation in Wave 1 and then layering on numerical complexity (Catmull-Rom splines, CA diffusion) is well-reasoned. The transition from JSON protocol assumptions to observable-only bot behavior prepares the system well for the Phase 15 protocol overhaul.

## 2. Strengths
*   **Robust Same-Tick Death Model:** The decision to centralize environment-caused deaths in a `processEnvDeaths` sweep at the end of the `EnvironmentEngine` tick (Plan 14-01) is the most critical design choice. It ensures consistency between the engine state and bot perception without requiring expensive re-ordering of existing components.
*   **Comprehensive Composite Support:** Blocker 2 is addressed with impressive granularity. Buffs and hazards are wired through both the solo `SimulationEngine`/`ActionResolver` paths and the composite-specific `ActionResolver`/`CompositeEnergyDistributor` paths.
*   **Numerical Precision & Balance:** Normalizing toxin intensity to a 0.0–1.0 fraction (Plan 14-02) prevents the "balance explosion" risk (where a raw byte value would deal 255 damage) and ensures effects scale meaningfully with config.
*   **Performance Awareness:** Adding `Position` to the `Infection` record and utilizing single-pass grid scans (Plan 14-03) mitigates the O(N * Area) complexity risk, ensuring the simulation remains scalable despite the added CA shadow grids.
*   **Sophisticated Testing Strategy:** The integration test (Plan 14-06) avoids "lazy" assertions. By tracking `everBuffed` latches and localized compost at death sites, the tests prove specific mechanics rather than just global state deltas.

## 3. Concerns
*   **`@DirtiesContext` Test Performance (Plan 14-06) [Severity: LOW]**
    Using `@DirtiesContext` on the deterministic double-run test is the correct way to ensure a fresh Random seed in Spring, but it forces a full context restart. Given the simulation runs for 300 ticks, this might push the test execution time toward the project's upper limits.
*   **Grid Scan Density [Severity: LOW]**
    Between toxin, mutagen, status building, and death sweeps, the simulation performs multiple O(Area) scans per tick. While well within the 500ms budget for a 256x256 grid, the "fast path" guard for toxin (Plan 14-02) is a necessary optimization that should be monitored for other effects if grid size increases in M4.

## 4. Suggestions
*   **STV Voting Clarity:** In Plan 14-05 Task 2, specify if multiple `LOCOMOTOR` members with `MOVEMENT_PLUS_1` each contribute a range-2 vote, or if the buff on a single locomotor is sufficient for the whole composite to shift 2 cells. The current plan implies the latter ("LOCOMOTOR member has the buff"), which is simpler and likely sufficient.
*   **Dead-Cell Status:** In `buildStatusCaches`, consider ensuring that `TOXIN_PRESENT` or `MUTAGEN_ZONE` bits are set even for empty cells. Plans 02/03 already do this via `cellStatusCache.put(new Position(x, y), bits)` regardless of occupancy. Confirm this behavior during implementation to ensure bots can see "danger zones" before entering them.

## 5. Risk Assessment
**Overall Risk: LOW**

The risk level has been significantly downgraded from the previous cycle because these plans explicitly resolve the identified structural blockers. The dependency chain is clear: foundation → effects → perception/behavior → integration. The inclusion of `EnvDeathSweepTest` in the very first wave acts as an immediate safety check for the entire phase's semantic integrity. The codebase conventions (immutable records, sealed interfaces) are strictly honored.

---
**Reviewer:** Gemini CLI
**Date:** 2026-04-17

---

## Codex Review

**Overall**
The phase is planned with unusually strong detail, good dependency sequencing, and solid awareness of prior patterns in the codebase. The main risks are not missing algorithms; they are integration mismatches with the current engine shape: same-tick env deaths are not fully wired into existing registries, toxin/combat side effects are being inserted into a deferred-delta combat phase, composite buff application in Plan 05 does not match the current rigid-body composite architecture, and the Plan 06 determinism harness is weaker than it looks.

### **14-01**
**Summary**  
Strong foundation plan. It correctly front-loads config, registry, message shape, and test scaffolding, and it resolves the `@Order(15)` collision cleanly with `@Order(14)`. The biggest issue is that its same-tick env-death model is incomplete for the current codebase.

**Strengths**
- Good sequencing: config, registry, message extension, and death hooks are the right first wave.
- Correctly avoids global `application-test.yml` bleed.
- Good call on `@Order(14)` because [`CompositeEnergyDistributor`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:52>) already owns `15`.
- `BuffRegistry` dedup semantics are clear and testable.

**Concerns**
- `HIGH`: `processEnvDeaths()` does not unregister dead bots from [`BotRegistry`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:610>). If env deaths clear the cell same tick, the normal death sweep next tick never sees them, so dead sessions can linger indefinitely.
- `HIGH`: env-killed `CompositeMember`s are cleared from the grid without updating [`CompositeRegistry`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeRegistry.java:189>) or running current composite death logic in [`SimulationEngine.handleMemberDeath`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:665>). That is not “acceptable next-tick latency” because [`CompositeEnergyDistributor`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:54>) and [`ActionResolver`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/ActionResolver.java:157>) run later in the same tick.
- `MEDIUM`: death finalization is split between SimulationEngine and EnvironmentEngine. That is likely to drift.
- `MEDIUM`: `processEnvDeaths()` adds a full-grid pass every tick even when no env effect did damage.

**Suggestions**
- Make env death finalization call the same canonical death path used by SimulationEngine, or extract a shared death-finalizer service.
- Include `BotRegistry` cleanup in the env death path.
- Handle composite env deaths by removing members from `CompositeRegistry` same tick, not “later”.
- Short-circuit `processEnvDeaths()` when no env effect applied lethal-capable damage that tick.

**Risk Assessment**  
`MEDIUM-HIGH` — good structure, but the current same-tick death model is not safe until bot and composite cleanup are unified.

---

### **14-02**
**Summary**  
The toxin mechanics are well thought through: spline path, double-buffered CA, type-scaled damage, and persistence all fit the phase goals. The weak point is combat integration: the plan tries to inject splash into code paths that currently rely on deferred combat deltas and it does not fully match all existing attack paths.

**Strengths**
- Good numerical choices: pre-sampled spline path plus double-buffered CA is the right level of complexity.
- Explicit normalization by `intensity / 255.0` is a strong correction.
- Good fast-path for cold toxin grids.
- Covers all occupant types for env collision damage.

**Concerns**
- `HIGH`: [`SimulationEngine.processInteractions`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:141>) is deferred-delta based. Writing splash damage directly to the grid inside combat branches will violate current tick semantics and make results order-dependent.
- `HIGH`: the plan covers splash in solo combat and `ActionResolver` composite attack, but current composite-member attacks also exist in [`SimulationEngine`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:238>). That path is easy to miss.
- `MEDIUM`: `diffusionRate` is effectively hardcoded at `0.5`, even though D-07 describes it as configurable.
- `MEDIUM`: status cache semantics are a bit inconsistent: D-39 says `TOXIC` for intensity `> 0`, but the cache logic tends to threshold it.

**Suggestions**
- Keep toxin splash in the same delta model as other interaction effects inside SimulationEngine.
- Audit all attack sites, not just “solo vs composite”; there are at least three families now.
- Add explicit toxin diffusion-rate config or document why it is intentionally fixed.
- Separate cell-visibility threshold from entity “currently toxic” semantics.

**Risk Assessment**  
`MEDIUM` — the toxin core is strong, but the combat-path integration needs tightening to avoid regressions.

---

### **14-03**
**Summary**  
This is the most feature-dense plan and mostly hangs together: gossip, infections, buffs, cure acceleration, immunity, and zone decay are all covered. The main remaining risks are cure/buff ordering around lethal hits, a few underspecified semantics, and a brittle perf test strategy.

**Strengths**
- Good reuse of the shadow-registry pattern.
- `zoneDecayTicks` closes a real lifecycle gap.
- Explicit composite role mapping is good; it removes “wasted buff” outcomes.
- Single-pass infected-entity indexing is the right performance direction.

**Concerns**
- `HIGH`: buff grant ordering is unsafe around lethal hits. A target cured by `reduceInfection()` or DoT expiry can receive a survivor buff even if the same tick’s combat/DoT also killed it.
- `HIGH`: this still inherits the 14-01 composite death problem; infected composite members removed by env death can leave stale composite registry state.
- `MEDIUM`: BondedPair mutagen semantics are not really decided. They are treated like a single entity, but D-18 only clearly specifies Particle and CompositeMember outcomes.
- `MEDIUM`: outbreak lifetime is implicit (`infectionDurationMax * 10`) instead of a named config value.
- `MEDIUM`: the `< 50ms` performance assertion is likely flaky on CI and is a poor proxy for algorithmic complexity.

**Suggestions**
- Only grant survivor buffs after confirming the entity is still alive after all damage for that tick.
- Make outbreak lifetime an explicit config field.
- Decide BondedPair mutagen semantics explicitly.
- Replace the wall-clock perf assertion with an instrumented structural assertion or at least a much looser smoke bound.

**Risk Assessment**  
`MEDIUM` — broad coverage, but same-tick cure/death ordering needs explicit rules or this will produce edge-case bugs.

---

### **14-04**
**Summary**  
This is the cleanest plan. Lightning is simple, well-bounded, and correctly uses direct aftermath instead of new protocol. Most of its risk is inherited from the Plan 14-01 env-death model.

**Strengths**
- Scope is tight and appropriate.
- Dual-radius implementation matches the requirement well.
- Good use of existing nutrient-level infrastructure.
- Counter-based observability is a good testing choice.

**Concerns**
- `HIGH`: lethal lightning hits still depend on the incomplete env-death finalizer from 14-01.
- `LOW`: `lightningStrikeCount` increment-before-apply is fine for tests, but it should be documented as “attempted/applied strike” semantics if exceptions can happen mid-apply.
- `LOW`: no explicit test coverage for bot/session cleanup after lethal lightning, but that is really the shared 14-01 problem.

**Suggestions**
- Treat lightning as blocked until shared env death cleanup is correct.
- Document the strike counter semantics precisely.

**Risk Assessment**  
`LOW-MEDIUM` — mechanically straightforward; risk is mostly inherited, not local.

---

### **14-05**
**Summary**  
This is the weakest plan in the set. The perception changes are mostly sound, but the buff-application design for composites does not match how composites currently move, attack, and consume energy. This is where the plans drift furthest from the actual code.

**Strengths**
- Perception-side status encoding and dynamic vision radius are well aligned with the current broadcaster shape.
- Good instinct to keep `HeuristicBrain` observable-only.
- Good attention to dedup/stacking semantics.

**Concerns**
- `HIGH`: using a public static `OVERCROWDED_THRESHOLD` as the runtime source of truth conflicts with current config-driven overcrowding in [`SimulationEngine.processOvercrowding`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:565>). Perception can diverge from actual sim behavior when config changes.
- `HIGH`: composite `MOVEMENT_PLUS_1` is planned as a per-member 2-cell hop, but current composite movement is rigid-body whole-organism movement in [`executeCompositeMovement`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/ActionResolver.java:830>). That design does not fit.
- `HIGH`: composite `UPKEEP_MINUS_1` is planned as reducing a shared-pool decay contribution, but current [`CompositeEnergyDistributor`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/CompositeEnergyDistributor.java:60>) drains member energy, not a pool-decay scalar. The proposed implementation targets the wrong mechanism.
- `HIGH`: composite `ATTACK_PLUS_1` is planned in `ActionResolver`, but composite-member attacks also exist in [`SimulationEngine.processInteractions`](</home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:238). Buff behavior will be inconsistent if that path is not updated too.
- `MEDIUM`: “enter toxic cell if it’s the only path toward an attractor” is beyond the current brain’s model and risks scope creep.

**Suggestions**
- Keep `OVERCROWDED_THRESHOLD` as a named default only; use live config for runtime perception logic.
- Redesign composite movement buff as a colony-level speed/rate modifier, not a per-member hop distance.
- Apply `UPKEEP_MINUS_1` to member passive drain, not a nonexistent pool-decay term.
- Audit both composite attack systems before claiming Blocker 2 is closed.
- Keep HeuristicBrain changes to local priority adjustments only.

**Risk Assessment**  
`HIGH` — the perception half is fine, but the composite buff application model is not aligned with current architecture.

---

### **14-06**
**Summary**  
Good intent, but the integration harness overstates what it proves. It validates environment activity, but not full protocol/bot integration, and the deterministic double-run approach is not robust against the amount of singleton mutable state already in the Spring context.

**Strengths**
- Good move to use per-class `@TestPropertySource`.
- Good use of direct observables for lightning, buffs, status, and event counts.
- Composite-aware population counting is correct.

**Concerns**
- `HIGH`: the deterministic double-run/reset strategy is insufficient. Clearing the grid and `BuffRegistry` does not reset env shadow grids, active events, infection maps, composite state, bot registry, or RNG-driven singleton state.
- `HIGH`: this is not really “full-stack” in the same sense as existing bot integration tests; it publishes `TickEvent`s directly and does not actually exercise WebSocket perception delivery or bot decision loops.
- `MEDIUM-HIGH`: `deathPositions` inferred from disappeared occupied cells is noisy because movement also creates disappeared positions. That is not a reliable death detector.
- `MEDIUM`: the population stability bound may be too brittle for seeded stochastic runs.
- `MEDIUM`: direct status-cache probing is useful, but it does not prove `CellView` serialization or broadcaster integration.

**Suggestions**
- Split this into two tests: a deterministic engine-only harness and a lighter real-bot integration smoke test.
- Add explicit reset hooks to `EnvironmentEngine`, `CompositeRegistry`, and `BotRegistry` if determinism is required in one JVM.
- Replace “disappeared position” compost detection with an explicit death/compost event counter or callback.
- Assert at least one real `Perception`/`CellView` payload if protocol integration is in scope.

**Risk Assessment**  
`MEDIUM-HIGH` — useful coverage, but the determinism claim and “full-stack” framing are stronger than the harness actually supports.

---

**Bottom Line**
The phase plan is broadly good and should achieve R12/R14 if executed carefully. The main blocker to R13-quality confidence is the shared death/finalization model: fix env death cleanup first, then revisit composite buff application in Plan 05. If those two areas are corrected, the rest of the phase is mostly `MEDIUM` risk rather than `HIGH`.

---

## Consensus Summary

Two independent reviewers — Gemini and Codex — reached sharply different overall risk assessments (Gemini: LOW; Codex: MEDIUM-HIGH with HIGH items on Plans 01/02/03/05). The divergence is signal: Codex cross-referenced plans against specific source lines in the current codebase and surfaced integration mismatches Gemini missed. Gemini assessed design coherence; Codex assessed fit-to-code. Both are useful, but the Codex findings should be treated as blocking unless refuted.

### Agreed Strengths

- Same-tick env-death *intent* is correct (centralize in EnvironmentEngine sweep)
- Composite awareness is substantially better than prior cycle
- Numerical choices are strong — intensity normalization to 0.0–1.0, double-buffered CA, Catmull-Rom spline
- Fast-path guard for cold toxin grids is appropriate perf hygiene
- Shadow-registry pattern (BuffRegistry) is a clean reuse of BotRegistry/CompositeRegistry
- Dependency sequencing (foundation → effects → perception/behavior → integration) is sound

### Agreed Concerns

- Status-cache semantics around `TOXIC` vs `TOXIN_PRESENT` (Gemini noted visibility of empty danger cells; Codex noted threshold drift between cell-bit and entity-bit) — confirm intensity > 0 vs > threshold rule is consistent across plans

### Codex-Only Concerns (Gemini did not catch)

HIGH items flagged by Codex only, worth investigating:

1. **Env death cleanup incomplete (Plan 14-01, inherited by 14-03/04)** — `processEnvDeaths()` does not unregister dead bots from `BotRegistry` or update `CompositeRegistry`. Since `CompositeEnergyDistributor` (@Order 15) and `ActionResolver` (@Order 20) run later in the same tick, stale composite state can persist. Extract a canonical death-finalizer service shared with `SimulationEngine.handleMemberDeath`.
2. **Toxin splash in deferred-delta combat (Plan 14-02)** — `SimulationEngine.processInteractions` uses deferred deltas. Writing splash directly to the grid inside combat branches violates tick semantics and introduces order-dependence. Keep splash in the delta model.
3. **Attack-path coverage (Plan 14-02, 14-05)** — composite-member attacks exist in both `ActionResolver` AND `SimulationEngine.processInteractions` (~line 238). Plans only cover one path. Audit all three attack families before claiming Blocker 2 closed.
4. **Cure/buff grant ordering (Plan 14-03)** — a target cured by `reduceInfection()` in the same tick it took lethal damage can receive a survivor buff post-death. Gate buff grant on "still alive after all damage this tick."
5. **Composite buff model mismatch (Plan 14-05)** — `MOVEMENT_PLUS_1` is designed as per-member 2-cell hop, but `executeCompositeMovement` uses rigid-body whole-organism movement. `UPKEEP_MINUS_1` targets a pool-decay scalar that doesn't exist; `CompositeEnergyDistributor` drains member energy directly. Redesign as colony-level modifiers.
6. **Deterministic double-run harness (Plan 14-06)** — clearing grid + BuffRegistry does not reset env shadow grids, active events, infection maps, composite state, bot registry, or RNG singletons. Determinism claim is weaker than advertised. Add explicit reset hooks or split into engine-only deterministic test + lighter real-bot smoke.
7. **OVERCROWDED_THRESHOLD public static (Plan 14-05)** — conflicts with config-driven `processOvercrowding`. Perception can diverge from sim when config changes. Use live config for runtime path.

### Divergent Views

- **Overall risk:** Gemini LOW vs Codex MEDIUM-HIGH. Codex's line-level cross-references are more credible here — Gemini reviewed plans for internal consistency, not fit to existing code.
- **Plan 06 quality:** Gemini flagged only `@DirtiesContext` perf cost (LOW); Codex identified the harness does not actually exercise WebSocket/bot loops, infers deaths from "disappeared positions" (movement false-positives), and does not reset env state between runs (MEDIUM-HIGH).
- **Plan 05 buff application:** Gemini gave suggestions on STV voting clarity; Codex flagged the entire composite buff model as architecturally misaligned (HIGH). Codex is more likely correct — the plan appears to assume a movement model the codebase does not implement.

### Recommended Next Actions

1. Replan Plan 14-01 env-death finalization: extract shared death-finalizer, include BotRegistry + CompositeRegistry cleanup same-tick.
2. Replan Plan 14-02 toxin splash: route through existing deferred-delta combat model; audit all three attack-path families.
3. Replan Plan 14-05 composite buff application: colony-level movement modifier, member-drain `UPKEEP_MINUS_1`, audit composite attack paths.
4. Tighten Plan 14-03 cure/death ordering: explicit "alive after damage" gate on survivor buff grant.
5. Rework Plan 14-06: split deterministic engine-only + real-bot smoke; add env reset hooks; replace disappeared-position compost detection with event counter.
6. Re-run `/gsd-plan-phase 14 --reviews` after the above changes.
