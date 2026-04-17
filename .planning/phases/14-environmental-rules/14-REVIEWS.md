---
phase: 14
cycle: 5
reviewers: [gemini, claude, codex]
reviewed_at: 2026-04-17T09:45:25Z
plans_reviewed:
  - 14-01-PLAN.md
  - 14-02-PLAN.md
  - 14-03-PLAN.md
  - 14-04-PLAN.md
  - 14-05-PLAN.md
  - 14-06-PLAN.md
---

# Cross-AI Plan Review — Phase 14 — Cycle 5

## Gemini Review

This review evaluates implementation plans 14-01 through 14-06 for Phase 14: Environmental Rules. These plans represent the **cycle 5** revision, having incorporated exhaustive feedback from cycle 4 regarding structural bean cycles, same-tick action-path semantics, and API misalignments.

### Summary
The cycle 5 plans are comprehensive, technically rigorous, and perfectly aligned with the existing architecture. The most significant achievement of this revision is the resolution of the **same-tick action-path paradox**: by introducing the `EnvPostActionReconciler` at `@Order(25)`, the engine now correctly finalizes environmental side-effects of bot actions (like lethal toxic splash or attack-triggered cures) before perception is broadcast. The structural bean-cycle resolution using a third state-owning bean (`EnvCleanupHooksBean`) is a textbook Spring solution that maintains clean construction graphs. All prior API naming and test-visibility issues have been corrected.

### Strengths
*   **Decoupled State Management**: Moving canonical infection and grant state to `EnvCleanupHooksBean` cleanly breaks the `EnvironmentEngine <-> DeathFinalizer` cycle while providing a single source of truth for cleanup.
*   **Honest Same-Tick Semantics**: The reconciler pattern acknowledges the tick pipeline sequence and ensures that action-driven env effects (14-02, 14-03) are not deferred, fulfilling the "immediate" contract.
*   **Exhaustive Combat Coverage**: Buffs and splash damage are meticulously wired across all three attack families (Solo, Composite-in-Sim, Composite-via-Action), ensuring no mechanical gaps.
*   **Performance Awareness**: optimizations like `nonZeroToxinCellCount` and O(Occupied) scans instead of full-grid scans preserve the "Scale Engineering" focus of the project.
*   **Composite Consistency**: The fix for composite `SENSOR_PLUS_1` in `stitchSensorCoverage` ensures that multi-cell organisms receive the same utility from buffs as solo entities.
*   **Validation Rigor**: The split between a deterministic engine harness and a real-WebSocket smoke test provides high confidence without polluting the `BotClient` with test-only seams.

### Concerns
*   **LOW [Config Complexity]**: The `application.yml` is becoming quite dense with environmental knobs. While necessary for R14, the defaults in `EnvironmentConfig.defaults()` must be strictly maintained to ensure the "out-of-the-box" simulation remains stable.
*   **LOW [Re-entrancy]**: `processEnvDeaths()` is now re-entrant (called at `@Order(14)` and `@Order(25)`). The `envDamageAppliedThisTick` flag management is correct (resets after sweep), but this adds a small layer of complexity for future developers to track.

### Suggestions
*   **Documentation**: Ensure the Javadoc for `EnvPostActionReconciler` explicitly calls out that it exists *specifically* to handle side-effects of `ActionResolver`. This is a unique "reconciliation" phase that might confuse future maintainers if not well-documented.
*   **Metrics**: In Phase 15 (next), consider adding Actuator metrics for the `nonZeroToxinCellCount` and `deathEventCount` to provide live observability into environmental volatility.

### Risk Assessment: LOW
The plans have successfully navigated two major architectural hurdles (bean cycles and action-phase timing) that typically plague complex simulations. The reliance on established Paralife patterns (shadow registries, immutable records, event orders) makes the implementation predictable. The integration tests specifically target the most delicate areas (composite death rolls and deterministic reproducibility).

**Verdict: READY FOR EXECUTION.** All prior blockers (Codex HIGH/MEDIUM) and cleanup items (Gemini MEDIUM) are resolved.

---

## Claude Review

# Cycle 5 Review — Phase 14 Plans

## 14-01: Foundation

**Summary**
Third-bean DI (`EnvCleanupHooksBean`) genuinely breaks cycle-3's bogus interface-narrowing claim. `SimulationEngine.handleMemberDeath` stays inline + same-tick via @Lazy back-edge. `EnvPostActionReconciler` @Order(25) seam anticipates 14-02/14-03 composite-path needs. Test split into sibling classes matches project convention.

**Strengths**
- Construction order traces cleanly: `EnvCleanupHooksBean` → `DeathFinalizer(@Lazy SE)` → `SimulationEngine(DF)` → `EnvironmentEngine(DF, bean)`. No real cycle.
- CompostSink setter via @PostConstruct defers coupling to post-construction.
- `deathEventCount` as explicit observable beats disappeared-position heuristic.
- Sibling test classes for dissolution-chance pin (0.0 / 1.0) correctly use class-level `@TestPropertySource`.

**Concerns**
- `HIGH` **`EnvironmentConfig.seed()` referenced but field not declared in Task 1 body.** Task 3 `EnvironmentEngine` constructor calls `config.seed() == null ? new Random() : new Random(config.seed())`. Task 1 describes Lightning/Toxin/Mutagen/Compost nested records + `zoneDecayTicks` but no `Long seed` field. Test property sources set `paralife.simulation.events.seed=42`. If field missing, Task 3 will not compile AND seed test properties are silently no-op across all phases. Need to either: (a) add `Long seed` to `EnvironmentConfig` canonical constructor + defaults + yaml, OR (b) document cycle-3 carryover explicitly. This blocks every downstream plan.
- `MEDIUM` **Plan 01 Task 1 `EnvCleanupHooksBean` uses `Map<String, Object>` / `List<Object>` typed state, later replaced by Plan 03 with `Map<String, Infection>` / `List<PendingGrant>`.** This is breaking within the phase. Works if execution is strictly sequential, but any parallel wave execution of 02+03 would race. Acceptable since `depends_on` is linear, but should note as single-order constraint.
- `LOW` `DeathFinalizerTest` body shows all 4 cases but frontmatter `must_haves.artifacts` description under-describes that it's Mockito-based (unit-scope only, not @SpringBootTest). Minor.

**Suggestions**
- Add `Long seed` field to `EnvironmentConfig` canonical constructor (nullable so prod yaml can omit) + update `defaults()` + add `seed:` key to yaml (commented-out) OR remove `seed` references from test `@TestPropertySource` and use deterministic harness via `new Random(42L)` in package-private test constructor only.
- Verify `@TestPropertySource` `paralife.simulation.events.seed=42` actually binds — run one test and inspect `EnvironmentConfig.seed()` return.

**Risk Assessment: MEDIUM-HIGH** — bean cycle fix and same-tick semantics are right. `seed` field ambiguity is a real HIGH compile-blocker. Fix before execution.

---

## 14-02: Toxin Spread

**Summary**
BondedPair MAX-resistance explicit. `nonZeroToxinCellCount` fast-path via CA return value is elegant. SplashDelta routes through existing deferred-delta pipeline. ActionResolver composite-attack splash uses `markEnvDamageApplied` + reconciler seam — cycle-4 action item #2 resolved honestly.

**Strengths**
- 5 SimulationEngine emission sites + 1 ActionResolver site exhaustively enumerated. Grep-verifiable.
- `EntityIds` helper consolidation prevents drift.
- `diffuseStep` return-value trick saves O(W*H) idle scan.
- TOXIN_PRESENT vs TOXIC threshold split documented.

**Concerns**
- `MEDIUM` **`ToxinPathGenerator` constructor shape ambiguity.** Plan 04 Task 1 Step 2 creates `new ToxinPathGenerator()` (no-arg). Cycle-3 Pattern 2 in research shows `ToxinPathGenerator(Random rng)`. Plan 02 Task 1 doesn't pin shape explicitly. If constructor requires Random, Plan 04 test won't compile.
- `LOW` `diffusionRate` added to Toxin record — existing yaml `toxin:` block needs the new key or Spring binding fails (plan says add). Verify yaml `diffusion-rate: 0.5` actually appears.
- `LOW` Splash writes `Math.max(0, ...)` + `markEnvDamageApplied()` in ActionResolver, but the acceptance criteria only greps for `markEnvDamageApplied` — doesn't assert the splash write itself clamps. Could pass grep while still negative-energy-crashing.

**Suggestions**
- Pin `ToxinPathGenerator` constructor shape in Plan 02 Task 1: either `public ToxinPathGenerator()` with static math, or document Random injection. Update Plan 04 test accordingly.
- Add acceptance criterion: `grep -n "Math.max(0," src/main/java/com/paralife/engine/ActionResolver.java` returns match inside the splash block.

**Risk Assessment: LOW-MEDIUM** — technical design sound; minor ambiguity on ToxinPathGenerator API shape.

---

## 14-03: Mutagen Outbreak

**Summary**
Moves shared state into `EnvCleanupHooksBean` (cycle-4 action item #1 propagation). `drainPostActionGrants(long)` body + reconciler drain fires same-tick for composite attack-cures. BondFormation hook clears constituent infections. PendingGrant.position + post-damage-alive gate preserved.

**Strengths**
- Typed-container replacement on the bean is well-scoped.
- 6 attack-cure sites enumerated with in-scope Position.
- `composite_attackCureBuffGrantedSameTickViaReconciler` test locks cycle-4 HIGH fix.
- `bondFormationClearsMemberInfectionsFromCleanupHooks` locks Gemini MEDIUM fix.

**Concerns**
- `HIGH` **Plan 03 Task 2 changes `EnvPostActionReconciler.onTick` to call `drainPostActionGrants(event.tickNumber())`, modifying a file from Plan 01 that IS NOT in Plan 03's `files_modified` frontmatter.** Cross-plan modification without declaration. Autonomous executor may miss it. Also breaks Plan 01's `EnvPostActionReconcilerTest.onTickCallsProcessEnvDeathsThenDrainPostActionGrants` — that test verifies `inOrder.verify(env).drainPostActionGrants()` no-arg; after signature change the verify won't match. Test is not listed as modified in Plan 03.
- `MEDIUM` Plan 03 says "Ensure `EnvCleanupHooksBean` is injected into SimulationEngine (add to constructor if not already present from Plan 01)." Plan 01 adds `DeathCleanupHooks hooks` (interface) not `EnvCleanupHooksBean` (bean). Plan 03 BondFormation cleanup calls `envCleanupHooksBean.clearInfectionOnDeath(...)` — but `hooks.clearInfectionOnDeath(...)` would work via the interface. Two paths possible; plan should pick one. Injecting the concrete bean when interface already exists is redundant.
- `LOW` `currentTickForDrain()` helper mentioned then rejected in favor of signature change. Good. But signature change cascades into Plan 01's test stubs without acknowledgment.

**Suggestions**
- Add `src/main/java/com/paralife/engine/EnvPostActionReconciler.java` AND `src/test/java/com/paralife/engine/EnvPostActionReconcilerTest.java` to Plan 03 `files_modified` frontmatter.
- Update `EnvPostActionReconcilerTest.onTickCallsProcessEnvDeathsThenDrainPostActionGrants` to verify `drainPostActionGrants(42L)` or `drainPostActionGrants(anyLong())`.
- Use `hooks` field (DeathCleanupHooks) in BondFormation cleanup instead of injecting `EnvCleanupHooksBean` separately. One field, one source of truth.

**Risk Assessment: MEDIUM-HIGH** — semantics right, but file/test tracking gap on the reconciler signature change is a real HIGH execution-blocker.

---

## 14-04: Lightning Strike

**Summary**
Dual-radius + fertility clean. 'Attempted-strike' counter documented + tested. Nested `@SpringBootTest` inner class uses REAL `SimulationEngine` (cycle-4 MEDIUM fix for 14-04) — correctly replaces cycle-3's mocked-sim vacuous assertion.

**Strengths**
- `lightningStrikeCount++` in BOTH prod spawn AND test helper before apply — ordering invariant locked.
- Inner class `CompositeCleanupIntegration` `@Nested @SpringBootTest` is valid JUnit 5; `@TestPropertySource` pins dissolution-chance=0.0.
- Uses real `CompositeRegistry.register(String, List, Map, int, int)` signature.

**Concerns**
- `MEDIUM` Same `ToxinPathGenerator` constructor ambiguity as 14-02 — `new ToxinPathGenerator()` no-arg in test setup will fail if constructor requires Random.
- `LOW` LightningTest mocked unit-test setup manually constructs the full dep graph (`BotRegistry`, `BuffRegistry`, `mock(CompositeRegistry)`, `mock(SimulationEngine)`, `DeathFinalizer`, `EnvCleanupHooksBean`, `EnvironmentEngine`, `SeasonTracker`, `SeasonsConfig`, `FertilityConfig`, `ToxinPathGenerator`, `Random`). That's 11+ constructor dependencies — brittle. Any future constructor change breaks all 10+ unit tests.

**Suggestions**
- Test-builder helper (e.g. package-private `EnvironmentEngineFixtures.newEngineWithMocks(Random rng)`) would absorb constructor churn. Not blocking but improves resilience.
- Confirm `ToxinPathGenerator` no-arg constructor exists.

**Risk Assessment: LOW** — design and test integrity sound once ToxinPathGenerator shape is pinned.

---

## 14-05: Perception & Buffs

**Summary**
`SENSOR_PLUS_1` propagates to composite `stitchSensorCoverage` per-member (cycle-4 action item #8). `MOVEMENT_PLUS_1` composite reuses existing `moveInterval` via `effectiveInterval = max(1, moveInterval - 1)` — no parallel cooldown. All 6 `ATTACK_PLUS_1` sites enumerated. `OVERCROWDED_THRESHOLD_DEFAULT = 6` matches shipped config.

**Strengths**
- Per-SENSOR-member radius in stitched coverage is the right shape — not a global composite radius flag.
- `flags` vs `cellStatus` Javadoc clarifies long-term confusion.
- `unbuffedCompositeMovementRespectsExistingMoveInterval` baseline test guards against regression.
- Live-config read for overcrowding threshold (not a public static) matches convention.

**Concerns**
- `LOW` Plan 05 Task 1 extends `PerceptionBroadcaster` constructor to take `EnvironmentEngine + BuffRegistry + SimulationConfig`. Existing autowired tests (`CompositeIntegrationTest`, `PerceptionActionIntegrationTest`) rely on Spring DI and should work, but any direct-construction tests will break. Plan lists `PerceptionBroadcasterTest` as modified — good.
- `LOW` `hasAnyLocomotorMovementBuff(composite)` grep target specified but loop body not described. If the helper scans all members (not just LOCOMOTORs), buff check may return true when a non-LOCOMOTOR has `MOVEMENT_PLUS_1`. Implementation spec needs "iterate members where role == LOCOMOTOR and check buff."
- `LOW` Task 3 Plan 05: `BotClient.java` added to `files_modified` for cycle-4 action item #11 but only comment-only change. Satisfies #11 but is misleading in a "what files does this phase touch" scan.

**Suggestions**
- Spec `hasAnyLocomotorMovementBuff` body explicitly: `return composite.getMemberIds().stream().anyMatch(id -> isLocomotor(id) && buffRegistry.hasBuff(id, MOVEMENT_PLUS_1));`
- Confirm `PerceptionBroadcaster` constructor DI order is consistent with Spring's auto-resolution (order within @Autowired constructor doesn't matter; positional matters only for test direct-construction).

**Risk Assessment: LOW** — ships clean once LOCOMOTOR-filter semantic is spec'd explicitly.

---

## 14-06: Integration Test

**Summary**
`totalNutrients` observable closes cycle-4 action item #9 fertility-drift gap. `resetForTest` clears EnvCleanupHooksBean maps — cycle-4 action item #1 reset propagation correctly extends to where state moved in 14-03. `onTickEnvOnlyForTest` honest determinism scope. Raw-WebSocket smoke test reuses PerceptionActionIntegrationTest pattern.

**Strengths**
- `totalNutrients` samples end-of-run — guards the exact compost/lightning invariant the harness exists for.
- Pre-check asserts `totalNutrients > 0` — fails loudly if compost/lightning path silently stops mutating nutrients. Avoids false-positive equality.
- RNG reseeding tied to `config.seed()` (pending #14-01 HIGH).
- Full-wipe `WorldGrid.clear()` in reset avoids fertility leak.

**Concerns**
- `HIGH` Same `config.seed()` dependency as 14-01. `resetForTest` body: `long seed = config.seed() == null ? 0L : config.seed();` — requires `Long seed` (boxed/nullable) on `EnvironmentConfig`. Blocks compile if 14-01 HIGH not fixed.
- `MEDIUM` `totalNutrients()` iterates full grid via `worldGrid.getCell(x, y)` (takes read lock per cell). At 256×256 = 65k lock acquisitions. Only called once per run — acceptable — but note that `WorldGrid.getCell` in live source (WorldGrid.java:46-54) takes read lock per call. Could be expensive under contention. At test time, single-threaded, no issue. Document it.
- `LOW` `EnvironmentDeterminismTest` driver iterates 300 ticks calling `environmentEngine.onTickEnvOnlyForTest(tick)` — but SimulationEngine's `processInteractions` uses `ThreadLocalRandom` and is NOT driven in env-only mode. That's the whole point of "honest env-only scope" — correctly called out. Works only if no Plan-14 code path implicitly triggers SimulationEngine behavior via event publishing. Confirm none of the env methods publish a `TickEvent` or similar.

**Suggestions**
- After 14-01 HIGH fix on `seed` field, this Plan's `resetForTest` stays as written.
- Consider caching `worldGrid.snapshot()` for `totalNutrients()` to avoid per-cell lock churn — one snapshot + one iteration. Minor perf polish.

**Risk Assessment: MEDIUM** — blocked on 14-01 `seed` ambiguity; otherwise clean.

---

## Cross-Plan Consensus

### Blocking HIGH issues (must fix before execution)

1. **`EnvironmentConfig.seed` field declaration** — referenced in 14-01, 14-06, and every `@TestPropertySource`. Never shown in Task 1 body. Either add to the record or remove all references. Blocks compile.
2. **14-03 file tracking gap** — `EnvPostActionReconciler.java` modification + `EnvPostActionReconcilerTest.java` update required but not in `files_modified`. Autonomous executor will leave Plan 01 test broken.

### MEDIUM issues (fix before wave 5/6)

3. **`ToxinPathGenerator` constructor shape** — pin no-arg vs `(Random)` explicitly. Affects 14-02 and 14-04 tests.
4. **14-03 `hooks` vs `envCleanupHooksBean` field naming** — collapse to one.
5. **14-05 `hasAnyLocomotorMovementBuff` spec** — explicit role filter.

### What cycle-4 genuinely fixed (both reviewers corroborated)

- True third-bean DI break (14-01) vs cycle-3's interface-narrowing fiction.
- Composite attack-path same-tick semantics via reconciler @Order(25) (14-02, 14-03).
- `resolveCompositeAttack` → `resolveAttackerAttack` rename throughout.
- Real `CompositeRegistry.register` signature in tests.
- Class-level `@TestPropertySource` sibling classes, no per-method overrides.
- `totalNutrients` observable closes fertility-drift gap.
- Composite `SENSOR_PLUS_1` in stitched coverage.

### Overall phase risk

**MEDIUM** — architecture is now sound; the surviving HIGH issues are mechanical (missing field, missing file tracking) not design-class. One more targeted replan cycle addressing items 1 and 2 + three MEDIUMs should clear execution. Cycle-4 did the heavy lifting; cycle-5 is final polish.

---

## Codex Review

**Top Findings**
- `HIGH`: [14-03-PLAN.md](</home/mark/kramtime/paralife/.planning/phases/14-environmental-rules/14-03-PLAN.md:31>) still does not define how mutagen state survives identity-changing transitions. Current bond/composite transitions create new ids in [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:341) and [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:382), but the plan only clears old keys.
- `HIGH`: `bp.id()` survivor buffs are granted in 14-03, but 14-05 only wires buff effects for solo Particles and CompositeMembers (`14-03-PLAN.md:31`, `14-05-PLAN.md:33-38`). BondedPair buffs are currently dead state.
- `HIGH`: [14-06-PLAN.md](</home/mark/kramtime/paralife/.planning/phases/14-environmental-rules/14-06-PLAN.md:21>) overclaims determinism. `onTickEnvOnlyForTest()` still reaches composite death handling, and [SimulationEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/SimulationEngine.java:695) uses `ThreadLocalRandom`.
- `MEDIUM`: `EnvironmentFullStackSmokeTest` is likely flaky as written: 60 ticks plus one local-perception bot on a 256x256 world does not guarantee any visible status even if events fire (`14-06-PLAN.md:497-501`, `src/main/resources/application.yml:22-33`).

**14-01**
Summary: Strong foundation plan. The cycle-breaking bean split, same-tick death model, and reconciler are all well reasoned and line up with the live code’s actual ordering constraints.

Strengths:
- Preserves existing composite death semantics instead of reimplementing them.
- Fixes the real DI cycle rather than papering over it with interface narrowing.
- Test design is much tighter than earlier cycles, especially for same-tick composite cleanup.

Concerns:
- `MEDIUM`: The plan still explicitly deviates from R13 by using shadow grids plus projected status bits instead of actual `Cell` flags (`14-01-PLAN.md:160-164`). That can turn into a phase-acceptance dispute later.
- `LOW`: `EnvCleanupHooksBean.applyCompost()` no-oping before sink registration is convenient for isolated tests, but it can also mask a broken runtime wiring path.

Suggestions:
- Either amend the requirement text up front or add a minimal “authoritative flag projection” explanation in validation docs so R13 is not debated at verify time.
- Make missing `CompostSink` fail fast after context startup in non-test profiles.

Risk Assessment: `MEDIUM` because the design is coherent now, but it is still a large foundational refactor and the R13 deviation remains governance risk.

**14-02**
Summary: Good, focused toxin plan. It covers the right engine seams and correctly handles the special composite action path with the post-action reconciler.

Strengths:
- Explicitly covers all attack families, including `resolveAttackerAttack`.
- Uses a reusable CA helper and an O(1) idle fast path.
- Normalizes toxin damage by `intensity / 255.0`, avoiding the byte-scaling footgun.

Concerns:
- `LOW`: Current `SimulationEngine.processInteractions()` lets a particle affect multiple neighbors in one tick (`SimulationEngine.java:165-235`), so toxic splash can stack on one attacker multiple times. That may be fine, but the balance implication is not called out.
- `LOW`: The plan has correctness tests, but no explicit max-grid regression check for radius-N diffusion cost.

Suggestions:
- Add one test that documents whether multi-hit splash stacking in a single tick is intended.
- Add a simple “256x256, active toxin, N ticks” bounded smoke/perf assertion.

Risk Assessment: `LOW-MEDIUM`. The architecture is solid; remaining risk is mostly balance and perf tuning.

**14-03**
Summary: This is the most sophisticated plan and the one with the biggest remaining correctness gaps. The cure/grant ordering is much better now, but identity transitions are still under-specified.

Strengths:
- `PendingGrant.position` and post-damage alive-gating are the right fixes.
- Same-tick composite attack-cure handling via the reconciler is well thought through.
- Structural perf checks are better than wall-clock assertions.

Concerns:
- `HIGH`: Bond formation currently clears particle-keyed infections but does not transfer them to the new `bp.id()` infection record (`14-03-PLAN.md:31-32, 418-423`; `SimulationEngine.java:341-375`). That makes bonding an implicit mutagen cleanse.
- `HIGH`: There is no stated migration/cleanup policy for infected BondedPairs entering composite formation, or for buffed/infected members reverting/dissolving into new ids (`SimulationEngine.java:382-430`, `708-757`). ID-keyed infection/buff state can orphan or silently disappear.
- `LOW`: The plan modifies `EnvPostActionReconciler` but does not list it in `files_modified`, and Task 1 still says “see prior plan versions” (`14-03-PLAN.md:7-16, 215, 339-349`).

Suggestions:
- Add an explicit state-transition matrix for infection, immunity, and buffs across BondFormation, CompositeFormation, `revertToBondedPair`, and `dissolveToParticles`.
- If bonding is meant to cure mutagen, make that a locked decision and test it as such; otherwise transfer state into the new id.
- Make the plan self-contained and sync `files_modified`.

Risk Assessment: `HIGH` because the mutagen feature is materially incorrect until identity-transition behavior is defined.

**14-04**
Summary: Clean and low-risk. The plan is narrow, the semantics are clear, and the test strategy now uses the real `SimulationEngine` where it matters.

Strengths:
- Correctly avoids the earlier fake composite cleanup proof.
- “Attempted-strike” counter semantics are explicit and testable.
- Same-tick lethal cleanup follows the already-established env-death model.

Concerns:
- `LOW`: The composite cleanup integration test pins `dissolution-chance=0.0`, so it only proves the graceful-degradation branch here.

Suggestions:
- Explicitly say that shatter-path coverage remains owned by 14-01’s env-death tests, so 14-04 doesn’t look incomplete.

Risk Assessment: `LOW`. No major architectural gaps left here.

**14-05**
Summary: Strong perception/buff integration plan, but one cross-plan hole remains: BondedPair buffs still do not have a consumer.

Strengths:
- Fixes the real composite SENSOR dead-letter issue.
- Keeps overcrowding runtime-config-driven.
- Avoids leaking hidden server math into `HeuristicBrain`.

Concerns:
- `HIGH`: 14-03 grants survivor buffs to `bp.id()`, but 14-05 only applies buffs on solo Particles and CompositeMembers (`14-03-PLAN.md:31`; `14-05-PLAN.md:33-38, 47-55`). BondedPair survivors can carry buff records with no gameplay effect.
- `MEDIUM`: The plan says PerceptionBroadcaster reads cached `cellStatus` and also recomputes vision-scoped overcrowding (`14-05-PLAN.md:28-30, 233-240`), but it never explicitly says bit 0 must be recomposed per bot rather than forwarded from the cache.
- `LOW-MEDIUM`: Composite `MOVEMENT_PLUS_1` is reinterpreted as faster cadence, not D-15’s range-2 hop. That may be okay, but it is a semantic deviation.

Suggestions:
- Either define BondedPair buff semantics or explicitly exclude BondedPairs from survivor buffs.
- Spell out `cellStatus = (envStatus without overcrowded bit) | per-bot overcrowded bit`.
- Record the composite movement interpretation as an explicit deviation.

Risk Assessment: `MEDIUM-HIGH` because the perception work is good, but the BondedPair buff gap is still real.

**14-06**
Summary: Good split between deterministic and full-stack validation, but this is still the riskiest test plan because the determinism boundary is weaker than claimed and the smoke test is spatially flaky.

Strengths:
- `totalNutrients` is the right added invariant.
- Reuses the existing raw WebSocket capture pattern instead of inventing test-only client code.
- Reset coverage is much better than earlier cycles.

Concerns:
- `HIGH`: `onTickEnvOnlyForTest()` still calls `processEnvDeaths()`, and composite env deaths flow into `SimulationEngine.handleMemberDeath()` randomness (`14-06-PLAN.md:21-27, 169-170`; `SimulationEngine.java:665-703`). The harness is only deterministic if it guarantees no composites ever hit that path.
- `MEDIUM`: One bot on a 256x256 map is not enough to reliably observe non-zero `cellStatus`/`entityStatus` within ~60 ticks (`14-06-PLAN.md:497-501`).
- `LOW-MEDIUM`: Key helpers are still omitted as “unchanged from cycle 3”, and `.planning/STATE.md` is modified by Task 4 but missing from `files_modified`.

Suggestions:
- Make the determinism harness explicitly particle-only, or inject deterministic randomness into the composite death branch for test mode.
- Shrink the world and/or register several bots spread across the map for the smoke test.
- Inline the omitted helper bodies and sync `files_modified`.

Risk Assessment: `HIGH` because false-determinism and smoke-test flakiness both undercut the value of the final verification layer.

**Overall Risk**
`HIGH`. Plans 14-01, 14-02, and 14-04 are in good shape. The remaining blocking issues are cross-plan: mutagen/buff state does not yet survive identity changes correctly, BondedPair buffs have no defined effect, and the final validation harness still overstates what it can prove.

---

## Consensus Summary

Cycle 5 replan resolved cycle-4 structural feedback cleanly (true third-bean DI break, reconciler `@Order(25)` same-tick seam, real `CompositeRegistry` signatures, class-level `@TestPropertySource` sibling tests, `totalNutrients` observable). Architecture now sound. Remaining HIGHs are **mechanical / cross-plan tracking gaps** (not design-class) plus **one material correctness hole** in mutagen identity transitions. Gemini approves at LOW risk; Claude flags MEDIUM-HIGH (seed field + 14-03 file tracking); Codex flags HIGH on identity-transition state survival + BondedPair buff dead-letter + false determinism in 14-06 harness.

### Agreed Strengths

- Third-bean `EnvCleanupHooksBean` DI cycle break is correct and textbook (gemini, claude).
- `EnvPostActionReconciler @Order(25)` delivers honest same-tick semantics for action-driven env effects (gemini, claude, codex).
- `totalNutrients` sampling in 14-06 closes the fertility-drift gap (claude, codex).
- `resolveAttackerAttack` rename + 6-site enumeration for `ATTACK_PLUS_1` prevent mechanical gaps (gemini, claude).
- Composite `SENSOR_PLUS_1` in `stitchSensorCoverage` is the right per-member shape (claude, codex).
- 14-04 real-`SimulationEngine` `@Nested @SpringBootTest` replaces cycle-3's vacuous mocked assertion (claude, codex).

### Agreed Concerns — HIGH (must fix before execution)

1. **HIGH — 14-03 mutagen state does NOT survive identity transitions** (codex). Bond formation clears particle-keyed infections but does not transfer to new `bp.id()`; composite formation from infected BondedPair / revert / dissolve paths are unspecified. ID-keyed infection + buff state orphans silently. Locked decision needed: either (a) treat bonding/compositing as implicit mutagen cleanse (and test it), or (b) add explicit state-transition matrix migrating infection + buffs + immunity across BondFormation / CompositeFormation / revertToBondedPair / dissolveToParticles. References: `14-03-PLAN.md:31-32, 418-423`; `SimulationEngine.java:341-375, 382-430, 708-757`.

2. **HIGH — BondedPair survivor buffs are dead letters** (codex). 14-03 grants buffs to `bp.id()`; 14-05 only wires buff effects for solo `Particle` and `CompositeMember`. Fix: either define BondedPair buff-application semantics in 14-05 (attack/sensor/movement/upkeep for the pair as a unit), or explicitly exclude BondedPairs from survivor grants in 14-03 (cite D-18 composite-role precedent).

3. **HIGH — 14-06 `onTickEnvOnlyForTest` determinism claim false** (codex). Harness calls `processEnvDeaths()`, which can reach `SimulationEngine.handleMemberDeath()` which uses `ThreadLocalRandom` (`SimulationEngine.java:665-703`). Fix: either make harness strictly particle-only (skip composite env deaths entirely, or no composites in harness setup), or inject deterministic `Random` into composite death branch under a test flag. Document which path.

4. **HIGH — 14-01 `EnvironmentConfig.seed()` field missing** (claude). Task 1 body lists Lightning/Toxin/Mutagen/Compost nested records + `zoneDecayTicks` but no `Long seed` field. Task 3 calls `config.seed() == null ? new Random() : new Random(config.seed())`; 14-06 `resetForTest` does the same; `@TestPropertySource paralife.simulation.events.seed=42` binds to nothing. Fix: add `Long seed` (nullable) to canonical constructor + `defaults()` + yaml, OR remove all `config.seed()` references.

5. **HIGH — 14-03 cross-plan file-modification tracking gap** (claude, codex). Task 2 changes `EnvPostActionReconciler.drainPostActionGrants` to `(long tickNumber)` signature, modifying a file Plan 01 owns. Breaks Plan 01's `EnvPostActionReconcilerTest.onTickCallsProcessEnvDeathsThenDrainPostActionGrants` (verifies no-arg). Neither file in 14-03 `files_modified` frontmatter. Autonomous executor will leave test broken. Fix: add `EnvPostActionReconciler.java` + `EnvPostActionReconcilerTest.java` to 14-03 `files_modified`; update test to verify `drainPostActionGrants(anyLong())`.

### Agreed Concerns — MEDIUM

6. **MEDIUM — `ToxinPathGenerator` constructor shape unpinned** (claude). 14-02 Task 1 doesn't specify; 14-04 tests use `new ToxinPathGenerator()` (no-arg); cycle-3 research showed `(Random)`. Pin explicitly in 14-02.

7. **MEDIUM — 14-06 full-stack smoke test spatially flaky** (codex). 1 bot on 256×256 grid, 60 ticks — no reliable guarantee of visible non-zero `cellStatus`/`entityStatus`. Fix: shrink world for the smoke test or register several bots spread across the map.

8. **MEDIUM — R13 `Cell flags` requirement deviation** (codex). Phase uses shadow grids + projected status bits instead of actual `Cell` flags per R13 (`14-01-PLAN.md:160-164`). Pre-emptively either amend requirement text or add authoritative flag-projection note in validation docs to avoid verify-time dispute.

9. **MEDIUM — 14-05 composite overcrowded-bit recomposition path unspecified** (codex). `cellStatus` cache already contains a globally-computed OVERCROWDED bit; per-bot vision-scoped recomputation must mask bit 0 from cache and OR per-bot bit. Spell out: `cellStatus = (cached & ~OVERCROWDED) | perBotOvercrowdedBit`.

10. **MEDIUM — 14-05 `hasAnyLocomotorMovementBuff` role-filter unspec** (claude). Current grep spec matches on helper name but doesn't require LOCOMOTOR filter in body. Explicit: `composite.members().stream().filter(m -> role==LOCOMOTOR).anyMatch(m -> buffRegistry.hasBuff(m.id(), MOVEMENT_PLUS_1))`.

### Agreed Concerns — LOW

- 14-01 `DeathFinalizerTest` Mockito scope under-described in frontmatter (claude).
- 14-02 multi-neighbor splash stacking per tick undocumented (codex).
- 14-02 missing perf smoke test for max-grid diffusion cost (codex).
- 14-03 `hooks` (DeathCleanupHooks interface) vs `envCleanupHooksBean` (concrete) dual-path injection — pick one (claude).
- 14-05 composite `MOVEMENT_PLUS_1` semantic deviation from D-15 (cadence vs range-2 hop) — record explicitly (codex).
- 14-06 `STATE.md` modified by Task 4 missing from `files_modified` (codex).
- 14-06 `WorldGrid.getCell` read-lock churn across 65k cells for `totalNutrients` — snapshot + iterate (claude).
- 14-04 Lightning unit-test builds 11+ dep mocks manually — extract fixtures helper (claude).

### Divergent Views

- **Gemini: LOW risk / phase ready** vs **Claude: MEDIUM-HIGH** vs **Codex: HIGH**. Divergence driven by what each reviewer checked against: gemini reviewed architectural story within the plans themselves; claude cross-referenced plan task bodies against their own frontmatter / test stubs (found file-tracking + field-missing bugs); codex cross-referenced plans against live `SimulationEngine.java` (found identity-transition and determinism reaches into real randomness code). Codex's HIGH findings are material correctness holes that neither other reviewer surfaced — treat codex as authoritative on cross-code correctness.

- **14-06 harness determinism**: claude calls it "MEDIUM — honest scope called out" because the harness intentionally skips `processInteractions`; codex checked the actual `processEnvDeaths → handleMemberDeath` path and found `ThreadLocalRandom` still reachable via composite env deaths. Codex correct; harness scope is narrower than claimed.

- **Mutagen state transitions**: neither gemini nor claude surfaced the identity-transition gap. Codex traced through live `SimulationEngine` transitions and found it. Material feature-correctness bug.

### Recommendation

**Do NOT execute yet.** Run one more focused replan cycle (cycle 6) addressing items 1–5 (HIGH). Suggested scope:

- **14-01 patch**: add `Long seed` field + `defaults()` + yaml line, OR strip all `config.seed()` references from 14-01/14-06.
- **14-03 patch**: (a) state-transition matrix for infection + buff + immunity across BondFormation / CompositeFormation / revertToBondedPair / dissolveToParticles; (b) add `EnvPostActionReconciler.java` + test to `files_modified`, update test to `anyLong()`; (c) single-field decision (`hooks` vs `envCleanupHooksBean`).
- **14-05 patch**: BondedPair buff semantics (define or exclude) + overcrowded-bit recomposition spec + LOCOMOTOR filter body.
- **14-06 patch**: either particle-only harness constraint (no composites registered in harness setup) or test-mode deterministic `Random` injection into `handleMemberDeath`; shrink smoke-test world or add bots.
- **14-02 patch**: pin `ToxinPathGenerator` constructor shape.

After cycle-6 replan, re-run `/gsd-review --phase 14 --all` — expect all-LOW and green light to execute.
