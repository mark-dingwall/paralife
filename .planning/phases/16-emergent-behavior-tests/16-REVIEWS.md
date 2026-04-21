---
phase: 16
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-04-21
plans_reviewed: [16-01-PLAN.md, 16-02-PLAN.md, 16-03-PLAN.md, 16-04-PLAN.md, 16-05-PLAN.md, 16-06-PLAN.md, 16-07-PLAN.md]
---

# Cross-AI Plan Review — Phase 16 (Emergent Behavior Tests)

Four independent reviewers. Verdicts: **gemini LOW**, **opencode MEDIUM**, **claude MEDIUM**, **codex HIGH**. See consensus summary at bottom.

---

## Gemini Review

# Phase 16: Emergent Behavior Tests - Plan Review

Overall, the plan set for Phase 16 is **exceptionally thorough, architecturally sound, and demonstrates a deep understanding of the system's concurrency and non-determinism challenges.** The strategy of splitting tests into "engine-direct identity" (R15) and "full-stack statistical" (R16/17/18) is the correct approach for a distributed simulation using virtual threads.

## Strengths

- **Determinism Strategy (Plan 16-01/16-05):** The RNG audit is exhaustive. Refactoring `ThreadLocalRandom` to constructor-injected seeded `Random` instances is the only way to satisfy R15. The identity assertion across 3 runs in 16-05 is a high-confidence gate.
- **Observability (Plan 16-02/16-03):** The addition of `EmergenceMetrics` and `TickEngine` timing instrumentation provides the necessary telemetry for R18 and R17 without over-engineering. Dot-separated names and Micrometer builder patterns align perfectly with project conventions.
- **Test Utility Encapsulation (Plan 16-04):** Extracting `TriggerWatcher`, `PopulationHistory`, and `SeededBotLauncher` into helper classes prevents 16-06 from becoming an unmaintainable "God Test." The `TriggerWatcher` sliding-window design is a clever way to verify behavioral trends in a noisy environment.
- **Robust Verification (Plan 16-06):** The use of `try-finally` to ensure fixture dumps occur even on assertion failure is a vital engineering standard. The threshold calibration step (Task 3) addresses the risk of "magic number" flakiness by requiring empirical evidence.
- **Negative Control (Plan 16-05):** Including a `DifferentSeedControl` nested class provides meta-validation that the test is actually measuring RNG impact, not just a static scenario.

## Concerns

- **Constructor Bloat (Plan 16-01/16-02):** **LOW.** `SimulationEngine` and `EnvironmentEngine` are gaining more collaborators. Total count climbing toward the point where a `SimCollaborators` DTO/bean might be cleaner. For this milestone, the surgical ctor injection is acceptable.
- **Autocorrelation Sensitivity (Plan 16-06/D-21):** **MEDIUM.** Signal #4 (RPS boom-bust) relies on an autocorrelation floor of 0.2 at lag=50. Population dynamics can be highly sensitive to initial conditions. If calibration runs show high variance, this assertion may become flaky in CI.
- **TriggerWatcher Signal #5 Trigger (Plan 16-04/D-19):** **LOW.** Scoping the flee-signal trigger to stable IDs only (Bonded/Composite) is a logical choice for tracking, but creates a small observability blind spot for plain-Particle flee responses. Reasonable trade-off.

## Suggestions

- **Calibration Property:** In 16-06 Task 3, make the `oscillationAmplitudeFloor` a test property (e.g., `paralife.test.stability.oscillation-floor`) so CI can adjust sensitivity via env vars.
- **Active-Session Gauge Check:** Ensure the "mid-run" gauge check occurs after the first 50–100 ticks to allow the `BotLauncher` connect-storm to settle (current tick ≈ 500 is safe).
- **Log Marker Atomicity:** Ensure `EMERGENCE` log markers in `SimulationEngine`/`BuffRegistry` are emitted immediately after state write.

## Risk Assessment

**Overall Risk: LOW.** The plans are highly prescriptive and grounded in established codebase patterns. The dependency ordering correctly builds infrastructure before assertions. The main risk — non-deterministic flakiness in the full-stack test — is mitigated by statistical assertions and an explicit calibration wave.

**Requirements Coverage:** R15 → 16-05. R16 → 16-06 (D-07). R17 → 16-02, 16-06, 16-07. R18 → 16-03 and 16-06 (D-11). R19 → 16-07 checkpoint.

Plans approved for execution.

---

## Claude Review

# Phase 16 Plan Review — Caveman Mode

## 1. Summary

Plan set strong. Clean-slate replan pay off — 7 plans, 5 waves, dependency graph clean (16-01/16-03/16-04 parallel wave 1, 16-02 wave 2, 16-05 wave 3, 16-06 wave 4, 16-07 wave 5). Analog density high — every new file mapped to verbatim line-range precedent. Big risks concentrate in 16-06: 100-bot × 1000-tick seeded full-stack under virtual threads walking line between "stable statistical assertions" and flake. Plans acknowledge this (D-09 rejects byte-exact, D-22 fail-fast, Task 3 calibration) but threshold margins shown in Task 3 javadoc example (p99 1.0× margin on 15ms budget) tight. R17 narrative closes cleanly if fixture schema extended for trigger-window results (16-06 Task 2 flags as optional — should be mandatory).

## 2. Strengths

- **RNG audit rigorous.** 17 sites enumerated with line-exact grep references + hot/warm/cold classification. 16-01 minimum set (#1–3, #5, #13, #17) justified against R15 claim, not over-scoped to all 17.
- **Analog coverage 15/15.** PATTERNS.md nails verbatim line ranges.
- **D-22 fail-fast + try-finally fixture dump.** First failure surfaces cleanly with `.as(...)` message; evidence always lands on disk.
- **D-23 negative control via sibling @Nested.** Live evidence (seed=42 vs seed=1337 divergence) not hypothetical.
- **D-24 log-counter symmetry.** Unified on `wasNewBuff[0]` branch — prevents downstream "3 log lines but 2 counter increments" false alarm.
- **16-03 inline DistributionSummary over bookend listeners.** Smaller diff; no double-sample pitfall.
- **Counter-read convention stays in double.** PATTERNS.md Pitfall 6 honoured.
- **16-06 mid-run + end-of-run active-session gauge capture.** D-11 #7 "throughout" handled correctly.
- **paralife.tick.work.ms priming for actuator.** 1000-tick run guarantees samples before readout.
- **@Tag("slow") + -PincludeLong=true wired in 16-03 Task 3.** Prevents hidden/never-run long-run test.

## 3. Concerns

### HIGH

- **16-06 p99 tick-work budget margin too tight.** D-11 #3: p99 ≤ 90% of 15ms = 13.5ms. Task 3 calibration table shows observed p99 in 11.8–13.4ms range — 1.0× margin. GC pause, CI noisy neighbour, or cold JIT on first 100 ticks will blow this. **Fix:** bump tick-interval to 20ms (D-02 ceiling) → 18ms budget. Or exclude first 100 ticks via warmup window.
- **16-06 heapGrowthPercent window arithmetic fragile under respawn churn.** Phase 15.2 showed ~2.3 respawns/sec. Over 985 ticks × 15ms = 14.8s steady state → ~34 respawns, each allocating Session + BotState + codec buffers. 20% threshold may false-positive. **Fix:** warmup ≥200 ticks post-bot-connect BEFORE firstWindow starts (raise firstWindowStart from 250 to 300+). Or `System.gc()` before window reads.
- **16-06 D-04 #4 autocorrelation floor 0.2 at lag=50 unvalidated.** No calibration evidence cited. Risk: RPS dynamics on 64×64 may not produce periodic signal at lag=50 (period could be 120+). **Fix:** calibrate 3 seeds BEFORE locking floor; scan `lag∈[20,100]` and assert `max(autocorrelation(type, lag)) ≥ 0.2`.
- **16-02 Task 3 BondingConfig binding fail-fast masks deeper risk.** No equivalent fail-fast for the 5 yaml seed keys (simulation.seed, action-seed, fertility.seed, spawn-seed, rock.seed). **Fix:** 16-05 should autowire config records and assert `seed() == 42L` at test start. Otherwise "test passes but seeds never bound" is a silent failure mode.

### MEDIUM

- **16-01 CompositeEnergyDistributor seed decorrelation via `config.seed() ^ 0xC0FFEEL` is a code smell.** Cleaner: add `CompositeConfig.seed()` nullable Long per EnvironmentConfig convention.
- **16-04 Step 1e depends on BotRegistry.getEntityByPosition(x,y) which may not exist.** Production code change slipping into a test-utility plan. Should be explicit task flagged in `files_modified`.
- **16-06 midRunActiveSessions single sample at tick=500 is not "throughout".** D-11 #7 reasonably means: gauge == 100 at EVERY sample. **Fix:** `history.sessionCountSeries().stream().allMatch(c -> c == configuredBotCount)` after 50-tick warmup.
- **16-06 fixture schema doesn't include trigger-window results.** Without it R17 narrative for signals #3/#5 degrades to qualitative. **Fix:** mandatory `List<TriggerWindowResult>` in RunResult record.
- **D-19 scoping creates observability blind spot.** Plain Particle buffs never enter flee-signal window; assertion may trigger with zero windows opened. **Fix:** gate on `buffedWatcher.results().size() > 0` not raw `buffsGrantedCount() > 0`.
- **16-05 uses `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` with 3 @Tests + 1 @Nested.** ~20-30s overhead per run for an R15 test claimed as "short, engine-direct". **Fix:** drop @DirtiesContext; use manual `resetAll()` OR `AFTER_CLASS`.
- **16-06 bond-energy-threshold=30 with decay-per-tick defaults active.** 100 bots starting at energy 80 will decay below 30 within first N ticks. Composites may fail to form. **Fix:** add `paralife.simulation.energy-decay-per-tick=0`.
- **16-04 autocorrelation uses biased denominator vs lagged numerator.** Fine for "≥ 0.2" threshold but document in javadoc.

### LOW

- **16-01 extending SimulationEngine ctor and 16-02 extending again = two ctor migrations in back-to-back waves.** Consider bundling EmergenceMetrics forward-declaration into 16-01.
- **TriggerWatcher margin=0.5 hardcoded in factories.** Calibration in 16-06 Task 3 can't tune it without re-editing helper.
- **16-07 Task 1 Step 4 ticks VALIDATION sign-off unilaterally.** Some items should be checker-verified.
- **16-06 Task 2 Step 4 hoists ~5 variables to outer scope for try-finally.** Extract `RunResult buildRunResult(...)` helper.
- **No plan captures fixture-directory cleanup between test methods.** N=5 rollover handles it long-term; local devs see noise.

## 4. Suggestions

- Add SimulationConfig seed fail-fast check to 16-05 Task 1 (one-liner defence).
- Promote trigger-window results to mandatory fixture field.
- Bump tick-interval to 20ms OR add warmup exclusion.
- Replace XOR-decorrelation with CompositeConfig.seed() nullable Long.
- Promote D-11 #7 assertion to series-wide `allMatch` after warmup skip.
- Add `energy-decay-per-tick=0` to 16-06 @TestPropertySource.
- Hoist BotRegistry.getEntityByPosition into 16-04's files_modified.
- 100-tick warmup minimum before first heap window.
- 16-06 Task 3 MUST calibrate autocorrelation floor before locking.
- Consider merging EmergenceMetrics injection into 16-01.
- Add grep-check in 16-07 that VALIDATION sign-off items tied to automated verification.
- Document autocorrelation denominator choice in javadoc.

## 5. Risk Assessment

**MEDIUM.** Execution risk LOW for 16-01 through 16-05. 16-06 carries residual risk: three HIGH concerns (p99 margin, heap-growth window under respawn churn, autocorrelation floor) plus four MEDIUM. Tunable via Task 3 calibration, but thresholds look optimistic. Most likely failure mode: first CI run of 16-06 blows p99 assertion on a noisy runner. Second-most-likely: autocorrelation floor misses on certain seeds. Recommend: resolve HIGH concerns before executing 16-06.

---

## Codex Review

## Summary

The plan set is strong at the architectural level: the `R15` engine-direct test and the `R16/R17/R18` full-stack long-run split is the right shape, the waves are mostly sensible, and the evidence stack of tests + metrics + logs + fixtures + narrative is well thought out. But as written, there are several concrete blockers that will make execution either flaky or misleading: `R15` does not actually reset seeded RNG state between its 3 in-method runs, the "single master seed" story is not wired end-to-end, the planned `buffs.granted` metric site is not atomic to the real domain event, and D-19 is based on a false assumption about entity IDs.

## Strengths

- The `16-05`/`16-06` split is correct. Keeping `R15` engine-direct and `R16/R18` full-stack avoids dragging transport nondeterminism into the determinism proof.
- Plans map cleanly to `R15–R19` and to existing repo patterns (EnvironmentDeterminismTest, EnvironmentFullStackSmokeTest, WebSocketMetricsWiringTest).
- Wave ordering is mostly pragmatic: `16-01`, `16-03`, `16-04` can largely move in parallel; `16-02` then unlocks the real assertions; `16-06` is the right final integration gate.
- Using Micrometer counters plus grep-friendly `EMERGENCE` markers is good pay-forward design for M5.
- `.gitignore` + fixture rollover + seed logging choices are good operational hygiene for long-run tests.
- `@Tag("slow")` + `-PincludeLong=true` is the right CI ergonomics move.

## Concerns

- **[HIGH] 16-05's 3-run identity check will not prove determinism as written.** The plan resets world state between runs, but not the seeded `Random` state inside beans like SimulationEngine, ActionResolver, or CompositeEnergyDistributor. `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` only helps between test methods, not inside the 3-run loop. `FertilityInitializer` is also `@PostConstruct` only, so `worldGrid.clear()` does not recreate fertility patches for run 2/3.
- **[HIGH] The "single master seed" design is not actually wired in 16-06.** The plan still hardcodes server-side seeds to `42` in `@TestPropertySource`, so the per-run `masterSeed` only affects `SeededBotLauncher`, not simulation/action/fertility/spawn RNG. That breaks D-09/D-20's stated contract.
- **[HIGH] `paralife.emergence.buffs.granted` is not atomic if incremented in `BuffRegistry.grant`.** `BuffRegistry` is a generic registry and `transferBuffs()` calls `grant()`, so you would count identity-transfer/migration as new emergence events. The real domain event is in `EnvironmentEngine.grantSurvivorBuffs()`, not in the registry.
- **[HIGH] D-19 is built on a false premise.** Plain `Particle` already has a stable `id()` on the occupant itself in `Entity.java`, and `BotRegistry.java` does not have the proposed `getEntityByPosition(...)`. Scoping signal #5 to only `BondedPair`/`CompositeMember` creates an unnecessary observability blind spot and forces an unnecessary production/helper change.
- **[HIGH] The fertility seed property path in 16-01 is wrong.** `FertilityConfig` binds `paralife.simulation.fertility.*`, not `paralife.world.fertility.*`. As written, that seed will silently not bind.
- **[MEDIUM] The RNG audit is incomplete.** `ActionResolver` lines 330 and 361 use no-arg `Collections.shuffle(...)`, which is another nondeterministic tie-break path the plan does not seed.
- **[MEDIUM] 16-06's long-run loop measures `1000` samples, not `1000` ticks.** The stop condition is based on `history.tickCount()` plus `Thread.sleep(15)`, not actual `tickEngine.getCurrentTick()` advancement. That weakens D-08, checkpoint assertions, and drift math.
- **[MEDIUM] The planned 16-05 negative control is too brittle.** Nested `@SpringBootTest` + ordering + shared static state is hard to trust and maintain. At odds with some of the plan's own acceptance greps.
- **[MEDIUM] 16-05 is over-scoped.** Requiring all four `EMERGENCE` marker families in the determinism test couples R15 to env/buff/infection paths it does not need.
- **[MEDIUM] Fail-fast in 16-06 not ideal for a 90s test.** After paying full run cost, stopping on first failure is expensive. Only works if fixture is always written and rich enough to diagnose the rest.
- **[MEDIUM] Fixture writing in `finally` can mask real assertion failure if JSON write/rollover throws.** Error-handling gap.
- **[MEDIUM] p99/mean headroom too thin at 15ms ticks.** Classic CI flake pattern.
- **[MEDIUM] 16-04 is not actually file-disjoint.** Says "add `BotRegistry.getEntityByPosition` if absent" but doesn't include BotRegistry.java in `files_modified`. Should not need that method at all if it uses `Entity.id()`.
- **[MEDIUM] Constructor churn is understated.** `SimulationConfig`, `SimulationEngine`, `TickEngine`, `BuffRegistry` directly instantiated across many existing tests; plans should be explicit about overloads/no-op defaults to avoid avoidable fallout.
- **[LOW] "Four atomic trigger sites" claim only fully true for infection.** For bond/composite, incrementing before registry/remap/cleanup completes would over-report partial failures.
- **[LOW] `EnvPostActionReconciler` named as part of buff-grant wiring but actual path is in `EnvironmentEngine`.** Extra file churn, confusion.

## Suggestions

- Replace 16-05's "3 runs inside one test method" with either three separate context-rebuilding test methods or explicit reset hooks for every seeded component, including fertility re-init.
- Introduce a dedicated test seed source for 16-01/16-06 so one master seed actually derives and injects simulation/action/fertility/spawn/env seeds. `@DynamicPropertySource`, test `@Configuration`, or a small `TestSeedConfig` bean.
- Move `buffs.granted` counter/log to `EnvironmentEngine.grantSurvivorBuffs` or `processPendingGrants`; keep `BuffRegistry` generic.
- Drop D-19 "stable IDs only" restriction and build watcher snapshots from `Entity.id()` directly. If a "strong predator" subset matters, filter on `hasBuffs()` or entity kind.
- Extend RNG audit to no-arg shuffles in `ActionResolver`, or explicitly document that full-stack replay is only statistical.
- Make the long-run sampling loop tick-driven: wait for `tickEngine.getCurrentTick()` to advance, sample once per actual tick.
- Keep 16-05 narrow. Let 16-02 prove marker/counter wiring; R15 should focus on composite determinism only.
- Prefer a hybrid post-run assertion model in 16-06: compute all metrics, always dump the fixture, then assert in groups.
- If keeping fail-fast, ensure fixture write failures do not replace the original assertion failure.
- Use `20ms` instead of `15ms` for the long-run test.

## Risk Assessment

**HIGH.** The overall design is good and it does cover R15–R19 in concept, but the current plan text has multiple execution blockers in core areas: determinism reset semantics, master-seed plumbing, buff-metric atomicity, and D-19's incorrect ID assumption. Those are not polish issues; they directly affect whether the tests mean what they claim. If those four are corrected up front, the risk drops to **MEDIUM**.

---

## OpenCode Review

# Phase 16 Plan Review

## Summary

This is a well-structured, deeply researched plan set that methodically builds deterministic-seed infrastructure (16-01), observability instrumentation (16-02/03), test helpers (16-04), and two integration tests (16-05/06) before closing with narrative documentation and a full-suite gate (16-07). The wave ordering is sound, the analog-driven pattern matching is disciplined, and the RNG audit is exhaustive. The primary risks are: (a) D-04 signal #5 may produce vacuous observations if buffs land on plain Particles rather than BondedPair/CompositeMember entities, (b) fail-fast assertion ordering on a 90-second test discards diagnostic information that accumulate-all would preserve, and (c) several implicit production-code changes (BotRegistry position lookup, TickEngine.getCurrentTick()) are assumed but not explicitly scoped in any plan's `files_modified`.

## Strengths

- **Exhaustive RNG audit** with line-level call sites, hot-path classification, and explicit in/out-of-scope decisions — the strongest aspect. The 10-site refactoring in 16-01 is thorough.
- **Analog-driven planning** — every new file has a verbatim-line-range reference to an in-tree analog (PATTERNS.md). Dramatically reduces implementation risk.
- **D-09 explicit rejection of byte-stable fixtures** — the statistical-assertion approach is correct for a virtual-thread system.
- **Counter-increment site precision** — atomic trigger sites for all 4 counters identified down to line number; BuffRegistry new-buff vs. refresh distinction explicitly addressed (D-24).
- **BotClient.handleDeath:294 bug fix** correctly scoped as incidental fix during RNG injection.
- **Wave parallelism** — 16-01, 16-03, 16-04 are all Wave 1 with disjoint `files_modified`.
- **Negative control (D-23)** — DifferentSeedControl is elegant meta-validation.
- **Fixture rollover** — N=5 with automatic pruning prevents disk accumulation.

## Concerns

### HIGH

1. **D-04 signal #5 is observationally fragile.** The `forBuffedPredator` trigger requires `e.id() != null && e.hasBuffs()`, meaning only BondedPair/CompositeMember entities open observation windows. If buffs land primarily on plain Particles, the watcher opens zero windows, and the `if (emergenceMetrics.buffsGrantedCount() > 0)` gate passes while the actual flee-signal is never tested. Test could green-light without exercising the behavioral claim.

2. **Implicit production-code changes not tracked in `files_modified`.** 16-04 Task 1 mentions "if `BotRegistry.getEntityByPosition(int, int)` does not exist, add it" and 16-06 assumes `TickEngine.getCurrentTick()` exists. Neither file appears in any plan's `files_modified`. Violates GSD workflow file-tracking contract.

### MEDIUM

3. **Fail-fast (D-22) discards diagnostic information on a 90-second test.** When the first D-07 assertion fails, D-04 and D-11 results are invisible without manual JSON inspection. AssertJ `assertSoftly` would give the complete failure picture in a single run.

4. **`WorldWebSocketHandler` spawn-seed uses `@Value` instead of `@ConfigurationProperties`.** All other seeds flow through typed records. A typo in the property name would silently fall back to null with no compile-time or startup-time error.

5. **`CompositeEnergyDistributor` XOR magic constant.** `new Random(config.seed() ^ 0xC0FFEEL)` is ad-hoc. If `config.seed()` == `0xC0FFEEL`, the derived seed is zero — a known-bad seed. `SplittableRandom.split()` would be more principled.

6. **EmergenceMetricsWiringTest composite-formation path may be under-tuned.** Requires "seed two adjacent BondedPair instances such that composite formation fires" but doesn't specify the exact setup. Could pass vacuously or flake.

7. **`BuffRegistry.grant` return type change has unscoped blast radius.** Changing from `void` to `boolean` requires updating ALL callers, not just those in 16-02's `files_modified`.

### LOW

8. `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` in 16-05 — ~9s overhead, acceptable for determinism proof.
9. p99 extraction iterates `percentileValues()` with float-equality check — fragile if Micrometer changes percentile representation.
10. `PopulationHistory.sample()` signature is wide (6 parameters). A snapshot record would reduce param count.
11. 16-07 marked `autonomous: false` — narrative authoring (Task 1) could be autonomous.

## Suggestions

1. Add D-04 #5 fallback: count plain-Particle buff windows. Or add secondary counter `paralife.emergence.buffs.granted.to-registered-entity`.
2. Switch to `assertSoftly` for 16-06's assertion block. Keep try-finally fixture dump.
3. Promote `WorldWebSocketHandler.spawn-seed` to `@ConfigurationProperties` record.
4. Use `SplittableRandom` for all per-component seed derivation.
5. Explicitly add `BotRegistry.getEntityByPosition()` and `TickEngine.getCurrentTick()` to 16-04's `files_modified` (or Wave-0.5 mini-plan).
6. Add a `@TestConfiguration` inner class in EmergenceMetricsWiringTest reusing `seedDeterministicScenario()` from CompositeFormationTest.
7. Add counter or tag dimension to make D-19's scoping boundary observable.
8. Gate D-04 #5 on watcher window count, not buff count.

## Risk Assessment

**MEDIUM.** The core architecture (engine-direct R15 + full-stack R16/R17/R18, statistical assertions, component-seeded RNG) is sound. R15 and R18 are high-confidence; R16 and R17 depend on threshold calibration and the D-04 #5 signal respectively.

---

## Consensus Summary

### Agreed Strengths (2+ reviewers)

- **Engine-direct R15 + full-stack R16/R17/R18 split** — all 4 reviewers agree this is the correct architectural shape for a virtual-thread system.
- **Exhaustive RNG audit** — gemini, claude, codex, opencode. Line-level call sites + hot/warm/cold classification is the strongest aspect.
- **Analog-driven planning (PATTERNS.md)** — gemini, claude, opencode. Verbatim line ranges for every new file reduce implementation risk.
- **D-23 negative control** — gemini, claude, opencode. Elegant meta-validation.
- **EmergenceMetrics + EMERGENCE log markers as M5 seed** — all 4. Good pay-forward design.
- **Wave parallelism / disjoint files_modified** — claude, codex, opencode.
- **Statistical assertions over byte-stable fixtures (D-09)** — gemini, codex, opencode. Correct for virtual-thread I/O non-determinism.
- **Fixture rollover + @Tag("slow") + try-finally** — claude, codex, opencode. Good operational hygiene.

### Agreed Concerns (highest-priority, ranked)

**HIGH — 3+ reviewers converge:**

1. **D-19 signal #5 observability blind spot** (codex HIGH, opencode HIGH, claude MEDIUM, gemini LOW). Watcher may open zero windows if buffs land on plain Particles; assertion can green-light without testing the flee claim. Codex additionally asserts the premise is wrong — plain `Particle` already has stable `id()`, so scoping is unnecessary. **Unified fix:** gate the R17 flee-signal assertion on `buffedWatcher.results().size() > 0`, not `buffsGrantedCount() > 0`. Consider dropping the scoping entirely per codex.

2. **Untracked production-code changes (`BotRegistry.getEntityByPosition`, `TickEngine.getCurrentTick`)** (opencode HIGH, codex MEDIUM, claude MEDIUM). Violates GSD file-tracking contract. **Fix:** explicitly add these files to 16-04's `files_modified` or create a Wave-0.5 mini-plan; alternatively remove the dependency by using `Entity.id()` directly.

3. **16-06 p99 tick-work budget margin too tight at 15ms** (claude HIGH, codex MEDIUM). Calibration example shows 1.0× margin on 13.5ms budget — any GC pause or CI noise blows it. **Fix:** bump tick-interval to 20ms (D-02 ceiling) or exclude first 100 ticks via warmup window.

4. **Master-seed story not wired end-to-end in 16-06** (codex HIGH, claude HIGH re: no fail-fast on seed binding). 16-06 still hardcodes server-side seeds to `42`; per-run `masterSeed` only affects `SeededBotLauncher`. **Fix:** introduce `TestSeedConfig` / `@DynamicPropertySource` to derive all component seeds from one master; add `assertThat(simulationConfig.seed()).isEqualTo(42L)` fail-fast in both 16-05 and 16-06.

5. **Fail-fast (D-22) vs accumulate-all for a 90s test** (codex MEDIUM, opencode MEDIUM, claude not flagged). First failure hides D-04/D-11 context. **Fix:** AssertJ `assertSoftly` + keep try-finally fixture dump; catch fixture I/O errors so they don't replace the real assertion failure.

**HIGH — single reviewer but high-impact blockers:**

6. **R15 3-run reset doesn't reset seeded Random state inside beans** (codex HIGH). `worldGrid.clear()` does not re-init `FertilityInitializer` (`@PostConstruct`) or reset bean-level RNG state. Three identical runs will diverge. **Fix:** either three separate context-rebuilding test methods or explicit reset hooks for every seeded component.

7. **`paralife.emergence.buffs.granted` not atomic — `BuffRegistry.transferBuffs()` calls `grant()`** (codex HIGH). Identity-transfer/migration events will be counted as new emergence events. **Fix:** move the increment to `EnvironmentEngine.grantSurvivorBuffs` / `processPendingGrants`; keep `BuffRegistry` generic.

8. **FertilityConfig yaml prefix mismatch** (codex HIGH). Plan writes `paralife.world.fertility.seed` but actual binding is `paralife.simulation.fertility.*` — seed silently does not bind. **Fix:** correct yaml path in 16-01.

9. **16-06 heap-growth window fragile under respawn churn** (claude HIGH). ~34 respawns in steady-state window allocate Session + BotState + codec buffers; 20% threshold may false-positive. **Fix:** raise firstWindowStart to 300+ or `System.gc()` before window reads.

10. **Autocorrelation floor 0.2 @ lag=50 unvalidated** (claude HIGH). Period could be 120+. **Fix:** scan `lag∈[20,100]` and assert `max(autocorr) ≥ 0.2`; calibrate in Task 3 before locking.

**MEDIUM — recurring themes:**

- **RNG audit incomplete** — `ActionResolver` no-arg `Collections.shuffle` at lines 330/361 not seeded (codex MEDIUM).
- **Constructor churn understated across 16-01 + 16-02** (codex MEDIUM, gemini LOW, claude LOW). Two ctor migrations in back-to-back waves.
- **CompositeEnergyDistributor XOR magic constant** (claude MEDIUM, opencode MEDIUM). Replace with `CompositeConfig.seed()` or `SplittableRandom.split()`.
- **`WorldWebSocketHandler` spawn-seed `@Value` vs `@ConfigurationProperties`** (opencode MEDIUM). Silent fallback on typo.
- **16-06 sampling loop not tick-driven** (codex MEDIUM). `Thread.sleep(15)` + sample count ≠ 1000 actual ticks.
- **Mid-run session gauge single sample ≠ "throughout"** (claude MEDIUM).
- **Fixture schema missing trigger-window results** (claude MEDIUM). Required for quantitative R17 narrative.
- **`energy-decay-per-tick=0` missing from 16-06** (claude MEDIUM). Bonding threshold may never fire.

### Divergent Views

- **Overall risk verdict:** gemini **LOW** ("approved for execution"), opencode **MEDIUM**, claude **MEDIUM**, codex **HIGH**. Codex identifies four blockers the others miss or treat as polish: R15 reset semantics, master-seed plumbing, buff-metric atomicity, D-19 false premise. Recommendation: treat codex's four HIGH blockers as gating before 16-06 execution.
- **D-19 scoping:** gemini calls it "a reasonable trade-off"; claude calls it an observability gap; opencode says the test could green-light vacuously; codex says the premise is factually wrong (plain Particle has `id()`, so no scoping needed). Weight codex's specific code-reference claims higher.
- **R15 scope:** codex wants 16-05 narrower (composite determinism only, no EMERGENCE marker assertions); other reviewers accept the current breadth.
- **Fail-fast vs soft-assert on 16-06:** gemini and claude accept fail-fast; codex and opencode argue for hybrid or soft-assert given 90s cost. Two-against-two.

### Recommended Next Steps

1. **Before executing 16-06:** resolve the 4 consensus HIGH blockers (D-19 gating, untracked prod-code, p99 margin, master-seed plumbing).
2. **Before executing 16-05:** resolve R15 reset semantics + fertility yaml prefix + consider narrowing scope.
3. **Before executing 16-02:** move `buffs.granted` increment to `EnvironmentEngine.grantSurvivorBuffs`; verify atomicity (no `transferBuffs` double-count).
4. **During 16-06 Task 3 calibration:** validate autocorrelation floor across 3 seeds with lag scan; tune heap-window start; add `energy-decay-per-tick=0`; switch to tick-driven sampling loop.
5. **Plan-level housekeeping:** extend `files_modified` for implicit production-code touches; switch `spawn-seed` to `@ConfigurationProperties`; replace XOR-decorrelation with `SplittableRandom.split()`.

To incorporate feedback: `/gsd-plan-phase 16 --reviews`
