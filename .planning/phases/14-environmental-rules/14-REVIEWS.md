---
phase: 14
cycle: 8
reviewers: [gemini, claude, codex]
reviewed_at: 2026-04-17T22:24:24+10:00
plans_reviewed: [14-01-PLAN.md, 14-02-PLAN.md, 14-03-PLAN.md, 14-04-PLAN.md, 14-05-PLAN.md, 14-06-PLAN.md]
invocation: /gsd-review --phase 14 --all (claude -p explicitly included per user instruction)
---

# Cross-AI Plan Review — Phase 14 (Cycle 8)

## Verdict Matrix

| Reviewer | Risk | Verdict |
|----------|------|---------|
| Gemini   | LOW  | READY FOR EXECUTION |
| Claude   | MEDIUM-HIGH | Conditional: fix 3 MEDIUM API/tracking issues (14-03 BuffRegistry, 14-05 BondedPair aggressor sites, 14-05 BotState.entity) before execute |
| Codex    | MEDIUM-HIGH | Design strong; execution risk in 14-03 (density), 14-05 (API churn), 14-06 (validation gap vs roadmap goal) |

---

## Gemini Review

# Phase 14: Environmental Rules — Plan Review (Cycle 7)

## Summary
The Cycle 7 revision of the Phase 14 implementation plans is exceptionally robust, technically rigorous, and perfectly aligned with the project's architectural standards. The plans have successfully navigated complex hurdles including structural bean cycles, the "same-tick action-path paradox," and cross-component state migration. The introduction of the `EnvPostActionReconciler` ensures honest same-tick semantics for action-driven side effects, while the `EnvCleanupHooksBean` provides a clean, decoupled solution for shared state. The plans now include exhaustive coverage for identity transitions (bonding, compositing, reverting), ensuring that environmental state (infections, buffs, immunity) is never silently orphaned.

## Strengths
*   **Decoupled Dependency Injection:** The use of a third "state-owning" bean (`EnvCleanupHooksBean`) correctly breaks the circular dependency between `EnvironmentEngine` and `DeathFinalizer` while maintaining a single source of truth for environmental state.
*   **Honest Same-Tick Semantics:** The `EnvPostActionReconciler` at `@Order(25)` is a sophisticated solution to the ordering problem between `ActionResolver` and `PerceptionBroadcaster`, ensuring lethal splash damage and attack-triggered cures are finalized before bots receive state.
*   **Identity-Transition Integrity:** The explicit state-transition matrix for mutagen state (Plan 14-03) and the accompanying `transferMutagenState` helper provide a high-confidence solution for state survival across bonding and composite formation.
*   **Exhaustive Mechanic Wiring:** Splash damage and buffs are meticulously enumerated across all three attack families (Solo, Composite-in-Sim, Composite-via-Action), ensuring no mechanical gaps in the rock-paper-scissors dynamics.
*   **Performance Optimization:** The inclusion of O(1) fast-path counters (`nonZeroToxinCellCount`) and the "snapshot-once" `totalNutrients` implementation demonstrates a commitment to scale engineering.
*   **Validation Rigor:** The split between a deterministic engine harness (with explicit particle-only constraints to avoid `ThreadLocalRandom`) and a full-stack WebSocket smoke test provides comprehensive coverage of the feature's lifecycle.

## Concerns
*   **LOW [Re-entrancy Complexity]:** `processEnvDeaths()` is now re-entrant (called twice per tick if damage occurs). While correctly gated by the `envDamageAppliedThisTick` flag, this adds a slight cognitive load for future maintainers tracing the death finalization flow.
*   **LOW [Config Density]:** The `application.yml` is becoming quite dense with environmental parameters. While necessary for R14, the reliance on boxed `Long seed` for determinism vs. production behavior must be carefully documented to avoid confusion during operational tuning.

## Suggestions
*   **`resetForTest` Consistency:** In `EnvironmentEngine.resetForTest()`, consider matching the production constructor's RNG logic exactly: `this.rng = config.seed() == null ? new Random() : new Random(config.seed());` instead of defaulting to `0L`. This ensures that even in unseeded test environments, the "reset" behavior mirrors "start" behavior.
*   **Javadoc Visibility:** Ensure the Javadoc for `EnvCleanupHooksBean` and `DeathCleanupHooks` explicitly references the "Cycle 4/5 structural fix" to explain why state management is split across these components. This will help maintainers understand the DI graph.
*   **Monitoring:** While metrics are slated for Phase 15, adding simple debug log lines in `EnvPostActionReconciler` to track how many deaths were finalized "late" in the tick would be valuable for performance profiling.

## Risk Assessment: LOW
The plans are among the most detailed and well-coordinated seen in the project to date. All previous blockers (API misalignments, hallucinated signatures, and false determinism claims) have been resolved with precise, greppable assertions. The risks are primarily localized to the complexity of the re-entrant death sweep, which is well-managed by existing tests.

**Verdict: READY FOR EXECUTION.** The coordination across the six plans is seamless.

---

## Claude Review

# Phase 14 Cross-AI Plan Review — Cycle 7

## Overall Phase Assessment

**Risk: MEDIUM-HIGH**

Architecture is sound after 6-7 cycles of review. Bean-cycle resolution, same-tick death model, and identity-transition matrix are well-specified. Remaining risk is almost entirely **scope and execution complexity**, not design. Cycle-7 just caught two BLOCKER compile errors from hallucinated API signatures (`compositeRegistry.all()`, wrong `snapshot()` return type) — this is a late-stage red flag that similar undetected hallucinations may exist elsewhere.

**Core concern:** 6 plans, ~30+ new files, 22+ tests per plan, multi-cycle state transitions, 4 concurrent effect types, 4 buff types × 4 wire sites each, deterministic test harnesses, @SpringBootTest proliferation. This has drifted into specification-as-artifact territory rather than code-as-artifact. Execution will take considerably longer than initially estimated and will likely surface more API hallucinations at compile time.

---

## Plan 14-01: Foundation

### Summary
Third-bean DI resolution (`EnvCleanupHooksBean`) for the SimulationEngine ↔ EnvironmentEngine cycle is textbook Spring. `DeathFinalizer` centralizes per-member cleanup while keeping the composite 97/3 decision tree in `SimulationEngine.handleMemberDeath`. `EnvPostActionReconciler` @Order(25) is the right mechanism for same-tick composite-attack-path finalization. Cycle-6 resolved the nullable `Long seed` field ambiguity cleanly.

### Strengths
- **Third-bean DI is genuinely correct** — neither engine nor finalizer depends on the other; only back-edge is the canonical `SimulationEngine → DeathFinalizer → @Lazy SimulationEngine`.
- **`envDamageAppliedThisTick` short-circuit** avoids 65k-cell idle scans; good perf hygiene.
- **BondedPair bp.id() infection cleanup** (cycle-4 action item #6) surfaces a subtle leak that earlier cycles missed.
- **Sibling test classes for dissolution-chance pinning** (0.0 vs 1.0) correctly use class-level `@TestPropertySource` per project convention.
- **`seedFieldBindsFromTestPropertySource` regression test** is exactly the right shape to prevent silent binding drift.

### Concerns
- **LOW** — The `CompostSink` setter-injected via `@PostConstruct` is fragile. If the ordering of `@PostConstruct` callbacks changes (Spring doesn't guarantee ordering across beans without explicit dependencies), `applyCompost` silently no-ops. The current defensive `if (sink == null) log.debug` masks the failure. Consider a startup assertion in a non-test profile.
- **LOW** — `EnvCleanupHooksBean` uses `Map<String, Object>` / `List<Object>` in Plan 01, then Plan 03 replaces with typed containers. This is a refactor-breaking-within-phase concern — if Plans 02 and 03 somehow executed out of order (or in parallel waves), there would be compilation conflicts. Explicit `depends_on: [14-01]` is present so this is mostly theoretical, but the Object-typed interim shouldn't ship to production even briefly if Plan 02 lands between them.
- **LOW** — `DeathFinalizer` has 6+ constructor dependencies (WorldGrid, BotRegistry, BuffRegistry, CompositeRegistry, DeathCleanupHooks, @Lazy SimulationEngine). Brittle for future extension.

### Suggestions
- Add a non-test-profile `@PostConstruct` in `EnvCleanupHooksBean` that `throws IllegalStateException` if `compostSink == null` after the Spring context has fully started — catches wiring regressions loudly.
- Consider splitting `DeathFinalizer`'s constructor dependencies via `@Qualifier`-bundled collaborator records to reduce the 6-arg constructor churn.

### Risk Assessment: **LOW** — design is correct; execution complexity is the main cost.

---

## Plan 14-02: Toxin Spread

### Summary
Catmull-Rom path generation, CA diffusion with configurable radius, per-type resistance with explicit BondedPair MAX rule, and splash damage wiring across all three attack families (solo in-sim, composite in-sim, composite via-action). Cycle-6 pinned the `ToxinPathGenerator` constructor pair and documented multi-neighbor splash stacking.

### Strengths
- **Explicit BondedPair MAX-resistance rule** — worst-case resistance drives damage, ruling out order-sensitive bugs.
- **`nonZeroToxinCellCount` O(1) idle fast-path** via CA return-value trick is elegant; avoids full-grid scans on cold ticks.
- **`EntityIds.entityIdOf` consolidation** eliminates the entityIdOf drift that would otherwise accumulate.
- **SplashDelta deferred-delta routing** in SimulationEngine uses the existing combat machinery rather than bolting on a parallel path.
- **Pinned constructor pair** (public no-arg + package-private Random) cleanly supports both production and deterministic-test usage without introducing a constructor argument every caller must thread through.

### Concerns
- **MEDIUM** — Toxin damage formula `baseDamage * (intensity/255.0) * resistance` depends on `intensity/255.0` being an un-surprising normalization. For resistance values > 1.0 (SPORE at 1.3), low-intensity toxin can produce fractional damage that rounds to 0. Is that intentional? No test explicitly verifies the floor behavior at low intensity × high resistance.
- **LOW** — Multi-neighbor splash stacking is documented as INTENDED (cycle-6 LOW) but this is a balance decision that may emerge as a problem later. Consider: a CATALYST attacker in a 3-toxic-neighbor cluster takes 3× splash per tick. That's ~6 energy at default config on a CATALYST with 80 max — 7.5% of max per tick just from being adjacent. Locked decision, but surface this in phase verification output so reviewers notice.
- **LOW** — `diffuseStep` returns `int` count but the signature doesn't document what the caller should do if the return is unused. Future callers may accidentally drop the count, breaking the `nonZeroToxinCellCount` invariant.

### Suggestions
- Add a low-intensity × high-resistance rounding test to `ToxinTest` to lock behavior explicitly.
- Consider `@CheckReturnValue` or equivalent annotation on `diffuseStep` to prevent accidental count-drop.

### Risk Assessment: **LOW-MEDIUM** — solid design; balance decisions will need real-run validation in Phase 16.

---

## Plan 14-03: Mutagen Outbreak

### Summary
The most complex plan by a wide margin. Strain gossip, infection DoT, survivor buff grants, attack-accelerates-cure, BondedPair shared semantics, and the cycle-6 HIGH #2 identity-transition matrix. `drainPostActionGrants(long)` signature change propagates correctly to the reconciler and its test. Interface extension for `transferMutagenState` keeps `SimulationEngine` using a single `hooks` field.

### Strengths
- **Identity-transition matrix is now authoritative and complete** — BondFormation TRANSFER, CompositeFormation CLEANSE, revertToBondedPair MAX-MERGE, dissolveToParticles NO-OP. Each case has explicit justification and a locking test. This is exactly what was missing in cycles 3-5.
- **Cross-plan file tracking fixed** (cycle-6 HIGH #5a/#5b) — `EnvPostActionReconciler` + test now in `files_modified`.
- **Single `hooks` field** (cycle-6 HIGH #5c) — rejects the dual-injection drift that would otherwise have `SimulationEngine` importing both the interface AND the concrete bean.
- **PendingGrant.position** captured at enqueue — the cycle-3 cure-path bug is locked shut.
- **Structural perf counter** replaces wall-clock perf assertion.

### Concerns
- **MEDIUM** — `transferMutagenState` MAX-merge semantics: on BondFormation, if both predator and prey are infected, the plan merges via MAX ticksLeft + MAX initialTicks but KEEPS the target's strain/damagePerTick. This silently discards one side's strain. MVP says strains are uniform so it doesn't matter, but post-MVP strain-specific behavior will surface this as a bug. Document the choice explicitly in a comment with a TODO pointer.
- **MEDIUM** — `BuffRegistry.transferBuffs` helper is added in Task 3 but `BuffRegistry.java` is NOT in the plan's `files_modified` frontmatter (noted in `<self_check_files_modified>` as "belt-and-suspenders"). This is the SAME pattern of cross-plan file tracking gap that cycle-5 Claude flagged as HIGH #5a/#5b. Autonomous executor may miss that the BuffRegistry helper needs to land here. **Fix: add `src/main/java/com/paralife/engine/BuffRegistry.java` to `files_modified`.**
- **MEDIUM** — The cycle-6 interface extension adds `transferMutagenState` to `DeathCleanupHooks`, but this interface was introduced in Plan 01 with a clean 2-method surface. By Plan 03 it's a 3-method interface. Interface churn across plans within a single phase suggests the interface boundary wasn't right from the start. Acceptable for MVP, but flag for post-phase refactor.
- **LOW** — 22+ tests in MutagenTest. Any single breakage cascades. `@SpringBootTest` startup cost × 22 tests is noticeable.
- **LOW** — `compositeFormationFromInfectedBondedPairCleansesBpState` — the cleanse-on-composite decision is correct but subtle. A user-visible outcome is that a "lucky" infected BondedPair that forms a composite LOSES its buff progression. This may surprise players; document in release notes / Phase 14 verification output.

### Suggestions
- **Add `src/main/java/com/paralife/engine/BuffRegistry.java` to `files_modified`** — otherwise `transferBuffs` helper may not land.
- Inline a `// TODO(post-MVP): strain merge currently discards fromId strain` comment in `transferMutagenState` for future strain-specific-behavior work.
- Consider whether the 22-test MutagenTest should be split into `MutagenInfectionTest` + `MutagenBuffTest` + `MutagenIdentityTransitionTest` for parallel JUnit execution.

### Risk Assessment: **MEDIUM** — design is now correct; execution risk is high due to sheer mechanical complexity and the BuffRegistry file-tracking gap.

---

## Plan 14-04: Lightning Strike

### Summary
Cleanest plan in the phase. Dual-radius damage + outer-ring fertility, `attempted-strike` counter semantics, and `@SpringBootTest` with real `SimulationEngine` for the same-tick composite-cleanup test. Cycle-6 corrections were minor (no-arg `ToxinPathGenerator` usage + test scope clarification).

### Strengths
- **Real `SimulationEngine` in the composite-cleanup integration test** (cycle-4 MEDIUM fix) — the cycle-3 mocked-SimulationEngine made the assertion vacuously true. Fix is correct.
- **`attempted-strike` counter semantics** documented explicitly; both prod spawn and test helper increment BEFORE apply to preserve invariant.
- **Scope clarification** (cycle-6 LOW) — dissolution-chance=0.0 pins graceful-degradation; shatter ownership explicitly delegated to 14-01's sibling test. Prevents reviewers from thinking 14-04 is test-incomplete.

### Concerns
- **LOW** — Lightning unit-test `@BeforeEach` manually constructs 11+ dependencies (`BotRegistry`, `BuffRegistry`, `mock(CompositeRegistry)`, `mock(SimulationEngine)`, `DeathFinalizer`, `EnvCleanupHooksBean`, `new EnvironmentEngine(...)`, `SeasonTracker`, `SeasonsConfig`, `FertilityConfig`, `ToxinPathGenerator`, `Random`). Any future constructor change breaks all 11+ unit tests. Consider a package-private `EnvironmentEngineFixtures.newEngineWithMocks(Random rng)` test-builder.
- **LOW** — No test asserts lightning damage is NOT applied to Rock or Nutrient occupants. Current `damageEntityAt` returns `false` for them, but a test pinning the behavior would be cheap and catch a future refactor that accidentally adds damage to all occupants.

### Suggestions
- Add `rockAndNutrientCellsAreNotDamagedByLightning` unit test.
- Optional: extract test-builder helper to reduce constructor churn brittleness.

### Risk Assessment: **LOW** — small plan, clean design, well-tested.

---

## Plan 14-05: Perception Integration + Buff Effects + HeuristicBrain

### Summary
Buff EFFECTS wired across PerceptionBroadcaster, SimulationEngine, ActionResolver, CompositeEnergyDistributor. Vision-scoped overcrowding with per-bot bit recomposition. BondedPair-level buff consumers (cycle-6 HIGH #3 four new wire sites). LOCOMOTOR-only filter for composite MOVEMENT_PLUS_1 (cycle-6 MEDIUM #10). Composite MOVEMENT_PLUS_1 uses effective-moveInterval reduction (not D-15 hop-to-range-2 — documented deviation).

### Strengths
- **Per-bot overcrowded-bit recomposition spec is verbatim** (cycle-6 MEDIUM #9): `(cached & ~BIT_OVERCROWDED) | perBotOvercrowdedBit`. Grep-asserted. Leaves no room for ambiguity.
- **LOCOMOTOR-only filter body is verbatim** (cycle-6 MEDIUM #10) — the filter is part of the acceptance criteria, not just the helper name.
- **BondedPair buff wiring at all 4 sites** (cycle-6 HIGH #3) — ATTACK, SENSOR, MOVEMENT, UPKEEP each have a test and a grep assertion.
- **Composite MOVEMENT_PLUS_1 deviation from D-15 documented explicitly** — avoids a verify-time dispute.
- **`OVERCROWDED_THRESHOLD_DEFAULT = 6` is a default constant, not the runtime source** — runtime reads live `simulationConfig`. Correctly avoids the "public static" drift.

### Concerns
- **MEDIUM** — BondedPair-level ATTACK_PLUS_1 wiring says "EVERY site where `bp` emits its OWN attack damage" — but cycle-6 doesn't enumerate those sites with file:line pointers. A BondedPair can be an aggressor in: Particle-vs-BondedPair outer-loop flip (BondedPair defending itself but emitting combat), BondedPair-vs-Particle (if that exists), BondedPair-vs-BondedPair. Reading `SimulationEngine.processInteractions`, BondedPair is primarily a DEFENDER in the current code. If the actual attack-emission sites are zero or ambiguous, the "cycle-6 HIGH #3 — 4 consumer sites" truth doesn't hold for ATTACK_PLUS_1. **Verify during execution: does SimulationEngine have BondedPair-as-aggressor emission sites today?** If no, either drop the ATTACK_PLUS_1 BondedPair wiring (and document why) or add the aggressor path (much larger scope).
- **MEDIUM** — SENSOR_PLUS_1 on BondedPair-bound bot uses `EntityIds.entityIdOf(bot.entity())` — but `BotRegistry.BotState` doesn't currently expose a `bot.entity()` accessor; it exposes `entityId()`. The plan body says "entityId from EntityIds.entityIdOf" but passing a string into `entityIdOf(Entity)` won't compile. **Check the actual `BotState` record shape.** This may be an API mismatch similar to the cycle-7 `compositeRegistry.all()` BLOCKER.
- **LOW** — The CellView Javadoc "flags (server-authoritative global) vs cellStatus (vision-scoped)" distinction adds a documentation layer that future readers may still find confusing. Both fields can carry OVERCROWDED bits — just with different semantics. Consider renaming `cellStatus` to `visionScopedStatus` or similar.

### Suggestions
- **Before executing Task 2, verify `SimulationEngine` has actual BondedPair-as-aggressor attack emission sites.** If not, scope-check the ATTACK_PLUS_1 BondedPair wiring and potentially remove it.
- **Verify `BotRegistry.BotState` exposes an `entity()` accessor or equivalent path to get the occupant `Entity`.** This is the exact class of API-hallucination bug that cycle-7 caught.
- Add a grep-assert counting the actual number of BondedPair-as-aggressor ATTACK_PLUS_1 sites (e.g. `grep -c "buffRegistry.hasBuff(bp.id(), ...ATTACK_PLUS_1)" ...`) so execution can't silently under-wire.

### Risk Assessment: **MEDIUM** — two potential API-hallucination issues (BondedPair aggressor sites exist? `bot.entity()` exists?) that mirror the cycle-7 BLOCKER findings. Surface early in execution.

---

## Plan 14-06: Integration Test

### Summary
Two-file split: deterministic env-only harness (`EnvironmentDeterminismTest`) + real-WebSocket smoke test (`EnvironmentFullStackSmokeTest`). `totalNutrients` observable closes the fertility-drift gap. Particle-only harness constraint (cycle-6 HIGH #4) enforced via `compositeRegistry.getAll().isEmpty()` guard. Cycle-7 fixed two BLOCKER compile errors from hallucinated API signatures.

### Strengths
- **Cycle-7 BLOCKER fixes are correct** — `compositeRegistry.getAll()` (not `.all()`) and `GridSnapshot` record accessors (not raw `Cell[][]`). Shipped code will now actually compile.
- **Particle-only harness constraint** — enforced via runtime guard rather than Javadoc-only. A future maintainer seeding composites gets a loud failure, not silent non-determinism.
- **`totalNutrients` observable** is exactly the right invariant to guard compost + lightning fertility drift. Snapshot-once implementation avoids the 65k-lock hot path.
- **Smoke test reuses existing raw-WebSocket pattern** — no `BotClient` instrumentation proliferation.
- **Smoke test world shrunk to 32×32** (cycle-6 MEDIUM #7) — 1-bot observation reliably within 60 ticks.

### Concerns
- **MEDIUM** — Cycle-7 caught TWO compile-time API hallucinations. This strongly suggests there are more undetected ones in 14-01 through 14-05. The review process has been thorough on design but the plans assert specific API shapes (method names, return types, constructor signatures) without always verifying them against live source. Execution Wave 0 will surface more of these.
- **LOW** — `EnvironmentDeterminismTest`'s `seedInitialPopulation` body is elided ("body unchanged from cycle 3 modulo the particle-only constraint") and `anyNonZeroStatus` returns a placeholder. If an executor copies the Task 1 code literally, the test won't actually function. Ensure the full bodies land.
- **LOW** — Two-run determinism test relies on `@SpringBootTest` running both runs in the same JVM (same `EnvironmentEngine` singleton), which is correct, but if JUnit reorders test methods the runs could interleave state. Tests use separate `@Test` methods so JUnit's default serial execution handles this, but note for parallel-execution configs.
- **LOW** — The smoke test uses aggressive peak-lambda (0.20-0.25) to guarantee events fire. These lambdas are ~10× production values (0.04 etc.). Any future reviewer reading the test might mistake the config for a meaningful production value.

### Suggestions
- **Add a Wave 0 compile-check pass** where an executor compiles just the interfaces from 14-01 Task 1 and 14-02 Task 1 against the live codebase BEFORE executing Tasks 2+. Catches API-hallucination issues early (as cycle-7 demonstrated).
- Inline `seedInitialPopulation` and `anyNonZeroStatus` full bodies in Task 2 — don't elide.
- Add a comment on the aggressive smoke-test lambdas: `// NOT production values — forces event firing within 60-tick smoke window`.

### Risk Assessment: **MEDIUM** — the cycle-7 catches indicate residual API-hallucination risk across the phase. Design is correct.

---

## Cross-Plan Consensus

### Remaining HIGH / MEDIUM items (blocking before execution)

1. **MEDIUM (14-03) — `BuffRegistry.java` missing from `files_modified`.** `transferBuffs` helper lands in Task 3 but the file isn't tracked. Same pattern that cycle-5 Claude flagged as HIGH #5a/#5b for `EnvPostActionReconciler`. **Fix: add to frontmatter.**

2. **MEDIUM (14-05) — BondedPair-as-aggressor ATTACK_PLUS_1 sites assumed to exist but not file:line verified.** If `SimulationEngine.processInteractions` has zero BondedPair-as-aggressor emission sites, the wiring is vacuous. **Fix: grep the live source for BondedPair attack emission sites before execution; adjust wiring accordingly.**

3. **MEDIUM (14-05) — `EntityIds.entityIdOf(bot.entity())` assumes `BotState` exposes `entity()`.** Cycle-7 just caught `compositeRegistry.all()` which didn't exist; this is the same class of issue. **Fix: verify `BotRegistry.BotState` shape before execution.**

4. **MEDIUM (phase-wide) — residual API-hallucination risk.** Cycle-7 caught 2 BLOCKERS in 14-06 alone. Plans 14-01 through 14-05 have not been re-grepped against live source with the same rigor.

### Agreed Strengths Across Plans

- Bean-cycle resolution is correct (cycle-4/6).
- Identity-transition matrix is complete and authoritative (cycle-6).
- Same-tick semantics via `EnvPostActionReconciler` @Order(25) are honest.
- `totalNutrients` fertility-drift guard + `deathEventCount` explicit observables.
- Test package placement + class-level `@TestPropertySource` conventions are consistent.
- BondedPair buff wiring contract exported cleanly 14-03 → 14-05.

### Overall Recommendation

**Condition on: fixing the 3 MEDIUM tracking/API issues above (14-03 BuffRegistry, 14-05 BondedPair aggressor sites, 14-05 BotState.entity()), then execute.**

Plans 14-01, 14-02, 14-04, 14-06 are in good shape. 14-03 has a mechanical frontmatter gap. 14-05 has two potential API hallucinations that mirror the cycle-7 BLOCKER findings and should be verified against live source first.

**Separately: the phase is over-engineered for its goals.** 6 plans, 7 review cycles, 22-test files, extensive same-tick reconciliation machinery — the complexity is disproportionate to what these four environmental effects need. This is not a "fix before execution" concern; it's a feedback point for future phase scoping. The project exists to demonstrate scale engineering on Java 21 virtual threads; adding four environmental effects doesn't need this much orchestration. Ship what exists, but trim ambition on Phase 15 / 16.

---

## Codex Review

# Phase 14 Plan Review

Overall, the phase is well thought through and technically serious. The strongest parts are the explicit handling of same-tick semantics, composite edge cases, and testability. The main weaknesses are scope density, cross-plan API churn, and a late-phase validation strategy that does not fully match the original “300-tick full-stack” goal. The highest-risk plans are `14-03` and `14-06`.

## 14-01-PLAN

**Summary**  
Strong foundation plan with good attention to same-tick death semantics, bean-cycle avoidance, and test scaffolding. The main issue is that it front-loads a lot of architectural machinery before any environmental effect exists, which raises complexity and merge risk early.

**Strengths**
- The same-tick death model is explicit and consistent.
- Composite-member death handling is treated as a first-class requirement, not an afterthought.
- Seeded RNG support is a good call for later deterministic tests.
- Status-cache scaffolding and `CellView` extension are set up cleanly.
- The R13 shadow-grid rationale is documented instead of left implicit.

**Concerns**
- HIGH: `EnvCleanupHooksBean.applyCompost()` can silently no-op before `CompostSink` registration, which risks lost compost events during startup.
- MEDIUM: This plan introduces a lot of infrastructure at once: `DeathFinalizer`, `EnvCleanupHooksBean`, `EnvPostActionReconciler`, `DeathCleanupHooks`, test seams, and cache scaffolding.
- MEDIUM: Public API churn is already planned (`drainPostActionGrants()` signature change later, interface extension later), which increases cross-plan coordination risk.
- MEDIUM: R13 is being reinterpreted rather than implemented literally. That may be fine technically, but it is still a validation/sign-off risk.
- LOW: The plan uses `@Order(14)` instead of the documented `@Order(15)` without a clear reason.

**Suggestions**
- Fail fast if `CompostSink` is not registered instead of logging and skipping.
- Lock the core cross-plan APIs now instead of changing them in later plans.
- Get explicit approval on the R13 “status projection instead of `Cell.flags`” interpretation before implementation.
- Keep the event-order value aligned with the phase context unless a concrete reason exists.

**Risk Assessment**  
**MEDIUM** — The design is strong, but the amount of foundation machinery is high for the first plan.

---

## 14-02-PLAN

**Summary**  
Good toxin design with solid math, strong attack-path coverage, and sensible performance awareness. The biggest risk is not the toxin model itself, but the number of combat paths being modified and the fragility of the toxin-cell bookkeeping.

**Strengths**
- Path generation, CA diffusion, and seasonal triggering are clearly specified.
- The plan explicitly covers all attack families for splash damage.
- `nonZeroToxinCellCount` is a smart optimization for idle ticks.
- BondedPair resistance handling is explicit rather than implicit.

**Concerns**
- MEDIUM: `nonZeroToxinCellCount` is easy to get wrong if any toxin write path forgets to maintain it.
- MEDIUM: Splash damage is implemented through multiple branches in two different components, which creates consistency risk.
- MEDIUM: There is no explicit decision on whether rocks block toxin spread or toxic splash relevance.
- LOW: The disabled perf test is useful, but it is not a real guard against future hot-path regressions.

**Suggestions**
- Centralize toxin writes behind one helper that always updates the non-zero counter.
- Add one mixed-path regression test where the same attacker takes splash from more than one combat source in a tick.
- Decide now whether rocks should block toxin diffusion or whether environment effects intentionally ignore terrain.
- Consider recording actual attack-site file/line ownership in the plan body, not only in summary output.

**Risk Assessment**  
**MEDIUM** — The toxin model is fine; the risk is integration sprawl.

---

## 14-03-PLAN

**Summary**  
This is the most ambitious and highest-risk plan in the phase. It addresses the right hard problems, especially identity transitions and same-tick cure/grant ordering, but it carries the most internal complexity and the most places where small ordering bugs will be costly.

**Strengths**
- The identity-transition matrix is excellent and materially reduces ambiguity.
- Pending grants carrying `Position` is the right fix for same-tick eviction issues.
- The alive-after-damage gate is a strong correctness choice.
- Single-pass infection damage/indexing shows good performance discipline.
- BondedPair shared-infection semantics are explicitly defined.

**Concerns**
- HIGH: The plan is internally inconsistent about `transferMutagenState`. Some sections say it migrates buffs; other sections say buff transfer stays separate in `BuffRegistry`.
- HIGH: Scope is very large for one plan: outbreak spawning, gossip, DoT, cure, buffing, transition migration, reconciler update, and combat hooks.
- MEDIUM: The “BondedPair → Composite formation cleanses state” rule is technically reasonable but breaks the otherwise transfer-preserving mental model.
- MEDIUM: The ordering between direct writes, `markEnvDamageApplied`, `reduceInfection`, death sweep, and post-action grant drain is brittle.
- MEDIUM: Metadata drift is already visible here; the plan changes files that are not consistently reflected in `files_modified`.

**Suggestions**
- Pick one authoritative owner for buff transfer and remove the competing path.
- Treat the BondedPair→Composite cleanse as a design rule requiring explicit milestone-level sign-off.
- Add one integration test combining attack-cure, lethal damage, and identity transition in one tick.
- If schedule slips, split identity-transition migration tests from the core outbreak mechanics rather than trimming correctness logic.

**Risk Assessment**  
**HIGH** — Correct in intent, but this is the easiest plan to regress or partially implement.

---

## 14-04-PLAN

**Summary**  
Clean and relatively low-risk. Lightning is well scoped, and the correction to use a real `SimulationEngine` for composite cleanup verification is the right fix.

**Strengths**
- Simple dual-radius model with good testability.
- Same-tick death behavior is correctly tied back to the canonical death path.
- The “attempted-strike” counter semantics are explicit.
- The composite cleanup integration test is now structurally honest.

**Concerns**
- LOW: Terrain interaction is still unspecified here too.
- LOW: “Attempted strike” and “successful effect application” are different semantics; that distinction may confuse later observability.
- LOW: The composite integration test only proves graceful degradation, not shatter, though the ownership split is documented.

**Suggestions**
- Document whether lightning ignores rocks and line-of-sight.
- If metrics become user-facing, consider separate counters for attempted vs applied strikes.
- Carry the “shatter branch covered in 14-01” note into validation output, not just plan text.

**Risk Assessment**  
**LOW** — This plan is tight and focused.

---

## 14-05-PLAN

**Summary**  
Strong consumer-side integration plan, but it touches many user-visible behaviors at once. The plan is sound, though broader than it first looks because it spans perception, solo movement, composite cadence, BondedPair buff consumption, decay rules, and bot heuristics.

**Strengths**
- Per-bot overcrowded-bit recomposition is clearly specified.
- Composite movement reuses the existing cadence system instead of inventing another cooldown mechanism.
- BondedPair buff consumers are finally wired explicitly.
- HeuristicBrain stays observable-only, which is the right trust boundary.

**Concerns**
- HIGH: This plan changes a lot of gameplay surfaces simultaneously, so regression risk is high even if each individual change is simple.
- MEDIUM: `Messages.java` is described as modified but is not in `files_modified`, which is a workflow/process risk.
- MEDIUM: Composite `MOVEMENT_PLUS_1` no longer matches D-15 literally; it is a semantic deviation, not just an implementation detail.
- MEDIUM: BondedPair attack-buff coverage is still described somewhat loosely and should be enumerated more concretely.
- LOW: Vision-scoped overcrowding recomputation may become expensive at higher bot counts, though it is acceptable for this milestone.

**Suggestions**
- Add `Messages.java` to plan metadata.
- Enumerate exact BondedPair attack sites the same way composite attack sites are enumerated.
- Surface the composite movement semantic deviation in validation docs, not only in the plan.
- Consider separating HeuristicBrain behavior tuning from server-side buff wiring if schedule or regression budget tightens.

**Risk Assessment**  
**MEDIUM** — Good plan, but wide behavioral blast radius.

---

## 14-06-PLAN

**Summary**  
Useful harness plan with honest determinism boundaries and better reset hygiene, but it does not fully satisfy the original roadmap promise of a seeded 300-tick full-stack integration test covering all effects, buffs, and population stability.

**Strengths**
- Separating env-only determinism from full-stack smoke is intellectually honest.
- The particle-only determinism guard is a good correction.
- `totalNutrients` is a strong invariant for compost/lightning drift.
- Reusing the existing raw-WebSocket test pattern is the right call.

**Concerns**
- HIGH: The plan does not actually deliver the roadmap’s stated `14-06` goal. An env-only deterministic harness plus a 60-tick smoke test is not equivalent to a 300-tick seeded full-stack validation.
- MEDIUM: The smoke test can still be flaky because it proves visibility, not integrated stability.
- MEDIUM: The determinism harness excludes composites and most full-pipeline behavior, so it misses some of the hardest regressions introduced earlier.
- LOW: `getCompostEventCount()` delegates to a death counter, which is broader than compost-specific behavior.

**Suggestions**
- Add one real phase-gate integration test: 300 ticks, full pipeline, seeded, assertions that all four effects occurred, at least one buff was granted, and populations remained within sane bounds.
- Keep the env-only harness as a supplemental determinism check, not the main validation artifact.
- In the smoke test, prefer “run until status seen or timeout” over a fixed 60-tick expectation.
- Rename or document the death counter more carefully if it is used as a compost proxy.

**Risk Assessment**  
**HIGH** — Good supplemental test strategy, but incomplete against the original phase objective.

---

## Cross-Plan View

**Strengths**
- The plans are unusually explicit about ordering, ownership, and failure modes.
- Composite semantics are treated seriously across all six plans.
- Test strategy is broad and mostly well aligned with the implementation risks.

**Concerns**
- HIGH: `14-03` and `14-05` are both very dense and regression-prone.
- HIGH: `14-06` does not fully prove the phase goal as written in the roadmap.
- MEDIUM: Several plans show metadata/frontmatter drift from the actual described edits.
- MEDIUM: Rock/terrain interaction for environmental propagation remains under-specified across the phase.

**Overall Risk Assessment**  
**MEDIUM-HIGH** — The design quality is strong, but the phase is complex, API-heavy, and currently under-validated at the full-stack phase-gate level.

---

## Consensus Summary

### Agreed Strengths (2+ reviewers)

- **Same-tick semantics via `EnvPostActionReconciler` @Order(25)** — Gemini, Claude, and Codex all cite this as a sophisticated, correct resolution of the `ActionResolver` → `PerceptionBroadcaster` ordering paradox.
- **Third-bean DI (`EnvCleanupHooksBean`) breaks the `SimulationEngine ↔ EnvironmentEngine ↔ DeathFinalizer` cycle cleanly** — Gemini and Claude both call this textbook Spring.
- **Composite edge cases treated as first-class** — all three reviewers highlight explicit member-death handling, identity-transition rigor (bonding/compositing/reverting), and exhaustive attack-path enumeration (Solo / Composite-in-Sim / Composite-via-Action).
- **Deterministic test strategy** — Gemini, Claude, and Codex all approve of the split between seeded engine harness and full-stack WebSocket smoke test, plus the `seedFieldBindsFromTestPropertySource` regression test.
- **Performance hygiene** — `envDamageAppliedThisTick` short-circuit and `nonZeroToxinCellCount` O(1) counters are called out positively by Gemini and Claude.

### Agreed Concerns (2+ reviewers)

- **HIGH — 14-03 and 14-05 are dense and regression-prone** (Claude, Codex). Multi-cycle state transitions, 4 buff types × 4 wire sites, identity-transition transfers. Highest execution risk in the phase.
- **MEDIUM — Late-phase API hallucinations are a pattern** (Claude, Codex). Cycle-7 caught `compositeRegistry.all()` and a wrong `snapshot()` return type at BLOCKER severity. Codex flags metadata/frontmatter drift; Claude flags two more potentially hallucinated APIs in 14-05 (`BondedPair aggressor sites`, `BotState.entity()`) that should be greppable against live source before execute.
- **LOW/HIGH — `CompostSink` setter-injection fragility** (Claude LOW, Codex HIGH). `@PostConstruct` ordering is not guaranteed by Spring; current `if (sink == null) log.debug` silently masks misordering. Both suggest a startup assertion / fail-fast.
- **MEDIUM — Scope density vs. phase goal** (Claude, Codex). ~30 new files, 22+ tests per plan, 4 effect types, extensive same-tick reconciliation — complexity is high relative to the roadmap-level "four environmental effects + perception-visible status" goal.

### Divergent Views (worth investigating)

- **Overall risk:** Gemini (LOW, ready) vs. Claude/Codex (MEDIUM-HIGH, conditional). Gemini treats the plan as battle-hardened after 7 review cycles; Claude and Codex treat the same cycles as evidence of compounding complexity and latent API drift.
- **Plan 14-06 validation adequacy:** Gemini praises the 300-tick harness as comprehensive; Codex assesses it as **not fully proving the roadmap-stated phase goal at the full-stack gate** (HIGH). Worth re-reading 14-06 against the literal ROADMAP.md success criteria to adjudicate.
- **Plan 14-01 foundation weight:** Gemini says "foundation sets up cleanly"; Codex flags MEDIUM on "front-loads a lot of architectural machinery before any environmental effect exists" and calls R13 a reinterpretation rather than a literal implementation.
- **`@Order(14)` vs. documented `@Order(15)`** — only Codex caught this drift. Worth a grep to confirm.

### Recommended Action Before Execute

1. **Grep-verify** the 3 API claims Claude flagged in 14-05 (`BondedPair aggressor sites`, `BotState.entity()`, and any `BuffRegistry` tracking reference in 14-03) against live source. Cycle-7 caught two BLOCKERs this way; the pattern suggests more exist.
2. **Resolve `CompostSink` fragility** — swap the setter-inject + log-warn for a startup assertion, or restructure to constructor injection.
3. **Re-read 14-06 against ROADMAP.md success criteria** — confirm the 300-tick harness actually exercises "spatial propagation of effects across ticks (not just local)" and "unit tests for each environmental effect" at the phase-gate level.
4. **Reconcile `@Order` drift** (14 vs. 15) with documented convention.
5. **Accept Gemini's LOW-risk verdict only if steps 1–4 check out**; otherwise execute conditionally and patch during integration testing.
