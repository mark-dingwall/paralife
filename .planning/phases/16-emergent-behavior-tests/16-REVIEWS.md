---
phase: 16
reviewers: [gemini, claude, codex]
reviewed_at: 2026-04-21T03:56:52Z
plans_reviewed:
  - 16-01-PLAN.md
  - 16-02-PLAN.md
  - 16-03-PLAN.md
  - 16-04-PLAN.md
  - 16-05-PLAN.md
  - 16-06-PLAN.md
  - 16-07-PLAN.md
  - 16-08-PLAN.md
skipped: [opencode (empty response), qwen (not installed), cursor (not installed)]
---

# Cross-AI Plan Review — Phase 16: Emergent Behavior Tests

---

## Gemini Review

# Phase 16: Emergent Behavior Tests - Plan Review

## 1. Summary
Phase 16 is a well-structured conclusion to the v2.0 milestone, focusing on the transition from unit-level correctness to system-level behavior. The plan cleverly balances the need for strict determinism (R15) with the inherent chaos of population dynamics (R16) by employing a dual-testing strategy: engine-direct deterministic checks and full-stack statistical assertions. The use of Micrometer counters as the primary source of truth for behavioral assertions is an excellent architectural choice, as it validates the production observability stack simultaneously with the simulation logic.

## 2. Strengths
- **Dual-Layer Verification:** Separating engine-direct tests (16-05) from full-stack load tests (16-06/07) ensures that logic bugs are distinguished from transport/concurrency issues.
- **Observability-Driven Assertions:** Using `EmergenceMetrics` for test assertions ensures that the same metrics used to monitor production "health" are the ones defining "success" in CI.
- **Pragmatic Load Testing:** Reframing R18 from "byte-parity" to "capacity-headroom" (D-10) is a superior engineering approach.
- **Statistical Robustness:** The stability criteria (D-07) recognize that a "stable ecosystem" in a simulation isn't a static number, but a range of frequencies and amplitudes.
- **RNG Refactoring:** Centralizing RNG through `RandomSource` (16-02) is essential for R15 and provides a clean hook for future replay debugging.

## 3. Concerns
- **MEDIUM — Hidden Non-Determinism:** While `RandomSource` provides seeded values, iteration over non-ordered collections (`HashSet`, `HashMap`) in the simulation loop can still cause non-deterministic outcomes even with a fixed seed.
- **MEDIUM — Flakiness of Statistical Thresholds:** Thresholds like `amplitude ≥ 0.15` and `floor ≥ 5%` are sensitive to world size and initial distribution. On a 32x32 world, local density fluctuations can cause "bad luck" extinctions.
- **LOW — Test Duration/Resources:** 1000-tick run at 20ms with 100 bots and `@SpringBootTest(RANDOM_PORT)` may be significant for standard CI runners.
- **LOW — Metric Pollution:** `EmergenceMetrics` counters persisting across tests if context isn't dirtied may cause assertion failures in subsequent tests.

## 4. Suggestions
- **Non-Determinism Audit:** Before Wave 2, verify hot-path sites don't use parallel streams or rely on iteration order of `HashSet`/`HashMap`. Consider `LinkedHashSet`/`LinkedHashMap` or sorting before processing.
- **Warm-up Period:** Allow 100–200 tick warm-up before starting `PopulationHistory` capture to let initial transients settle.
- **Relaxed Failure Modes:** Consider asserting population *recovers* rather than hard "no extinction" for inherently volatile simulations.
- **Explicit Registry Isolation:** Ensure `EmergenceMetrics` uses a local `SimpleMeterRegistry` for unit tests or use `@DirtiesContext` aggressively.
- **JSON Fixture Sampling:** Sample every 10th tick instead of every tick to keep fixture sizes manageable and prevent disk I/O from skewing `TickWorkTimer`.

## 5. Risk Assessment: LOW
Primary risk is CI flakiness due to statistical nature of emergence tests, but fixed `paralife.seed.master` (D-09) significantly mitigates this for R15. The capacity-headroom approach to load testing is much safer than strict performance regression testing.

---

## Claude Review

## Phase 16 Plan Review

### Summary

Solid wave structure with clean dependency ordering. Wave 1–2 infrastructure is well-scoped. The two-test strategy (engine-direct R15, full-stack R16–R18) separates concerns correctly. Main risk lives in Wave 5: 15 assertions, several statistical, none calibrated from real runs. The heap-delta assertion and session-gauge timing are both GC/timing sensitive. 16-06's placeholder-then-fill pattern across waves is an unnecessary split that could confuse CI status.

### Strengths

- Wave dependency ordering is correct — infrastructure before helpers before tests before assertions
- Two-test topology (determinism vs stability) isolates failure modes cleanly
- Statistical assertions (count ≥ K, share ≥ X%) are the right approach for emergence; byte-exact would be meaningless
- D-10 reframe of R18 to capacity-headroom is pragmatically sound
- Nullable `@Autowired` EmergenceMetrics keeps production path clean
- EMERGENCE log prefix is grep-friendly and M5-forward-compatible
- Per-component seed derivation from single master property is elegant
- D-12 forced-formation config is honest about what's being tested

### Concerns

**HIGH**

- **Heap-delta assertion (`<20%`) is GC-noise, not signal.** GC can cause apparent heap delta swings of 30–50%+ depending on when measurements land relative to collection cycles. This will be the #1 flakiness source. Either remove it, use retained-heap-after-GC, or reframe as "no OOM / no unbounded growth."

- **Statistical thresholds (amplitude ≥0.15, floor ≥5% for ≥80% ticks) are unverified.** No evidence these values come from observed simulation runs. If population dynamics at forced-formation config don't hit these thresholds, the test is broken-by-design. Calibrate against at least one dry run before committing.

- **`no session dropouts (gauge never drops below 100 post-warmup)`** — "post-warmup" is undefined. What tick does warmup end? This could flake on slow CI where bot handshakes take longer than expected. Needs an explicit warmup tick count.

**MEDIUM**

- **`@DirtiesContext(BEFORE_EACH_TEST_METHOD)` on CompositeFormationDeterminismTest** — two methods = three Spring context lifecycles. With virtual-thread WebSocket setup, each boot could take 2–5s. Consider `BEFORE_CLASS` or restructure to share one context with deterministic reset.

- **SeededBotLauncher teardown unspecified.** 100 WebSocket bots need explicit lifecycle management. If bot threads outlive the test context, they'll throw errors, hold connections, or corrupt the next test's state. Plan needs explicit shutdown/join in `@AfterEach`.

- **`predator-pressure TriggerWatcher mean > baseline`** — if baseline is derived from the same test run, this is trivially satisfiable. The assertion only has value if baseline is a fixed constant or derived independently.

- **RandomSource default `-1L` semantics unspecified.** Does `-1L` mean "fall through to `ThreadLocalRandom`" or "use seed 0"? If it silently seeds with 0 instead of preserving non-determinism, production runs become inadvertently reproducible.

- **16-06 (placeholder) + 16-07 (real assertions) split across waves** creates a test that CI could pass with a trivially weak assertion between waves.

- **`0 ERROR log entries` assertion in 16-07** — 15 assertions on a full-stack 100-bot test makes any stray log ERROR a red failure. Should scope this to paralife-namespace loggers only.

**LOW**

- **Fixture JSON rollover (5-file) with parallel test execution** — if JUnit runs integration tests in parallel, two methods writing to the same rollover pool will corrupt each other.

- **16-08 narrative cites point counter values** — counter ranges are more durable than point values.

- **No `@Timeout` annotation on EmergenceStabilityLoadTest** — a hung bot or deadlock produces an indefinitely blocking test.

### Suggestions

1. **Replace heap-delta assertion** with explicit GC call before measurement (`System.gc()` hint) or drop entirely.
2. **Calibration run before Wave 5** — run 16-06 harness with no assertions, dump PopulationHistory to stdout, pick thresholds from observed distribution with 20–30% margin.
3. **Define warmup boundary** as a constant: `static final int WARMUP_TICKS = 50`.
4. **Merge 16-06 + 16-07** into a single wave.
5. **Scope ERROR log assertion** to `"com.paralife"` logger: `testLogCapture.getEvents("com.paralife").filter(ERROR).count() == 0`.
6. **Add `@Timeout(value = 120, unit = SECONDS)` on EmergenceStabilityLoadTest**.
7. **Document SeededBotLauncher teardown contract** explicitly in `@AfterEach`.

### Risk Assessment: **MEDIUM-HIGH**

Determinism test (16-05) is LOW risk — controlled, engine-direct, deterministic.

Load test (16-06/16-07) is HIGH risk for flakiness: heap-delta assertion will fail on GC-heavy runs; uncalibrated statistical thresholds may not be achievable; session-gauge timing is CI-environment-dependent; 15 assertions on a single long-running test means one flaky assertion blocks R16/R17/R18 evidence entirely.

Recommend addressing heap-delta and calibration concerns before Wave 5 execution, otherwise expect 2–3 iteration cycles to tune thresholds.

---

## Codex Review

## Overall
The plan set is directionally strong, but the main risks are determinism scope, test validity, and threshold calibration. In the current repo, randomness still leaks through places outside the proposed 16-02 refactor, manual-tick tests will race unless `tick.auto-start` is disabled, and the load assertions in 16-07 are stronger as goals than as currently proven evidence.

## 16-01 EmergenceMetrics
**Summary:** Good observability slice. It fits the existing Micrometer pattern and gives Phase 16 shared evidence points, but the counter semantics need to be locked down first.

**Strengths:** Reuses the existing metrics style cleanly. Places evidence at authoritative state-change sites instead of scraping logs.

**Concerns:**
- MEDIUM: `buffs.granted` is ambiguous — `BuffRegistry.grant()` dedupes and may extend expiry instead of creating a new buff.
- LOW: `mutagen.infections` overlaps with existing `EnvironmentEngine` event counters, so this can become redundant unless it serves a different audience.

**Suggestions:** Define each counter as "attempted", "applied", or "new episode" and test that exact meaning.

**Risk: LOW-MEDIUM.**

## 16-03 TickWorkTimer
**Summary:** Needed capability, but the bookend-listener design is brittle and does not clearly measure the whole tick.

**Concerns:**
- HIGH: `@Order(101)` misses later tick work — keepalive already runs at `@Order(200)` in `WebSocketKeepaliveService`.
- HIGH: if an earlier listener throws, the end bookend may never fire, so the worst ticks disappear from the metric.

**Suggestions:** Prefer timing inside `TickEngine` around `publishEvent(...)` instead of listener bookends.

**Risk: HIGH. Easy to ship a misleading timer.**

## 16-02 RandomSource
**Summary:** Correct direction, but the stated scope is too narrow for the determinism claims attached to it.

**Concerns:**
- HIGH: determinism still leaks through `BondedPair.formBond()` in Entity.java, bot placement in WorldWebSocketHandler.java, fertility init in FertilityInitializer.java, bot respawn jitter, and `UUID.randomUUID()`.
- MEDIUM: `-1L` introduces a third seed sentinel convention on top of existing `0` and `null`.

**Suggestions:** Define deterministic scope explicitly: engine-only, full-stack-no-respawn, or full-stack-end-to-end.

**Risk: HIGH. As written, it will not fully fix R15/R16 determinism.**

## 16-04 Test Helpers
**Summary:** Useful utilities, but five helpers before the assertions land risks framework-first over-engineering.

**Concerns:**
- MEDIUM: `TriggerWatcher` is underspecified and some planned signals are not clearly grounded in current bot inputs.
- MEDIUM: fixtures written into repo tree (not `build/`).

**Risk: MEDIUM.**

## 16-05 CompositeFormationDeterminismTest
**Summary:** Right requirement boundary, but the proof is too weak and the harness is under-specified.

**Concerns:**
- HIGH: manual tick stepping must override `paralife.tick.auto-start=false`; otherwise the background tick loop races the test.
- HIGH: identical aggregate deltas across runs do not prove deterministic sequence or topology.
- HIGH: three runs inside one test need explicit world/registry reset; `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` does not reset within the method.

**Suggestions:** Compare per-tick series or a canonical final-state hash, not just total counts.

**Risk: HIGH. It can pass without proving determinism.**

## 16-06 EmergenceStabilityLoadTest Harness
**Concerns:**
- HIGH: world size, rock/fertility settings, metabolism overrides, warmup window, and forced-composite config are not specified.
- HIGH: D-12 mentions a "proximity threshold" but there is no such config in current code.

**Risk: HIGH. The harness is underspecified and expensive.**

## 16-07 Assertions
**Concerns:**
- HIGH: `flee-from declining density` is not clearly backed by current bot signals; heuristics react to predators and lightning FLEEING, not density decline.
- HIGH: heap-delta and 20ms drift/headroom thresholds are likely flaky without calibration.
- MEDIUM: active-session gauge == 100 does not prove 100 bots are registered and participating.

**Suggestions:**
- Use sustained-share and repeated-oscillation criteria, not a single amplitude check.
- Make heap growth diagnostic first, not a hard gate.

**Risk: HIGH. Most milestone credibility depends on this slice.**

## 16-08 Documentation
**Concerns:**
- MEDIUM: copying exact counter values into markdown will rot as behavior changes. Document seed/config plus fixture references instead.

**Risk: MEDIUM.**

## Priority Fixes (Codex)
1. Tighten 16-02 so determinism scope is honest and complete.
2. Rework 16-05 so it proves deterministic sequence/state, not just matching totals.
3. Calibrate 16-07 thresholds against real measurements before making them hard gates.

---

## OpenCode Review

OpenCode review failed or returned empty output.

---

## Consensus Summary

Three reviewers completed substantive reviews (Gemini, Claude, Codex). All three converged on the same core issues.

### Agreed Strengths

- **Two-test topology is correct** — engine-direct for R15 determinism, full-stack for R16/R17/R18 stability (all three reviewers)
- **Statistical assertions are the right approach** for emergence; byte-exact would be meaningless (Gemini, Claude)
- **D-10 R18 reframe to capacity-headroom** is superior to v1.0 wire-parity (Gemini, Claude)
- **EmergenceMetrics pattern mirrors WebSocketMetrics cleanly** (Gemini, Codex)
- **EMERGENCE log prefix is M5-forward-compatible** (Claude, implicit in Codex)
- **Wave dependency ordering is correct** — infrastructure before helpers before tests (Claude)

### Agreed Concerns (HIGH severity — 2+ reviewers)

| Concern | Reviewers | Severity |
|---------|-----------|----------|
| **Uncalibrated statistical thresholds** (amplitude ≥0.15, floor ≥5%) — no evidence from real runs | Claude, Codex, Gemini | HIGH |
| **Heap-delta assertion is GC-noise** — swings of 30–50%+ are normal; this WILL flake | Claude, Codex | HIGH |
| **RandomSource determinism scope too narrow** — UUID, bot placement, fertility init, respawn jitter still leak non-determinism | Codex (explicit), Gemini (implicit as "hidden non-determinism") | HIGH |
| **TickWorkTimer @Order(101) misses keepalive at @Order(200)** — misleading metric | Codex | HIGH |
| **R15 proof too weak** — matching aggregate counter deltas ≠ deterministic sequence | Codex | HIGH |
| **`paralife.tick.auto-start` must be false** in R15 test else background tick races test | Codex | HIGH |
| **Session-gauge "post-warmup" undefined** — needs explicit warmup tick constant | Claude | HIGH |
| **SeededBotLauncher teardown unspecified** | Claude | MEDIUM |
| **`0 ERROR log entries` should scope to paralife namespace** | Claude | MEDIUM |
| **`buffs.granted` counter semantics ambiguous** (new buff vs extend expiry) | Codex | MEDIUM |
| **16-06/16-07 wave split creates CI false signal** | Claude | MEDIUM |

### Divergent Views

- **Overall risk rating:** Gemini: LOW; Claude: MEDIUM-HIGH; Codex: HIGH on multiple plans. Codex examined the actual codebase (cited file:line references), so its findings on TickWorkTimer @Order(200) and RandomSource leakage sites carry more weight.
- **Test helpers (16-04):** Gemini sees no issue; Codex flags over-engineering risk; Claude doesn't rate it separately.
- **16-08 narrative:** Codex concerns about value drift are valid; Gemini and Claude both consider it lower priority.

### Recommended Actions Before Execution

1. **Fix TickWorkTimer order** — move end bookend to `@Order(201)` or time inside `TickEngine.publishEvent()` instead.
2. **Extend RandomSource audit** — enumerate UUID/placement/fertility/respawn jitter sources; declare explicit determinism scope.
3. **Run calibration pass** before Wave 5 — run 16-06 harness with no assertions, observe actual threshold values, set margins.
4. **Drop or de-gate heap-delta assertion** — use diagnostic (log + test summary) not hard gate, or call `System.gc()` before snapshot.
5. **Define `WARMUP_TICKS` constant** and scope session-gauge + population-floor assertions to post-warmup window.
6. **Strengthen R15 proof** — compare per-tick series or final-state hash, not just aggregate counter deltas.
7. **Add `@Timeout` on EmergenceStabilityLoadTest**.
