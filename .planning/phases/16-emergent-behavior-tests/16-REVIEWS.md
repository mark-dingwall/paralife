---
phase: 16
reviewers: [gemini, claude, codex, opencode]
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
skipped: [qwen (not installed), cursor (not installed)]
notes: opencode initially returned empty output (stderr suppressed in orchestrator); retried with stderr visible and succeeded — model z-ai/glm-5.1 via openrouter, agentic mode (spawned Explore subagent + grepped codebase)
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

*Model: `z-ai/glm-5.1` via OpenRouter. Ran in agentic mode — spawned an Explore subagent and grepped the codebase to verify concerns against actual file:line references.*

# Phase 16 Cross-AI Plan Review

## 1. Summary

Phase 16 is a well-architected capstone that correctly separates determinism proof (engine-direct R15) from stability/emergence validation (full-stack R16–R18). The wave ordering is sound, the Micrometer-counters-as-test-evidence pattern is elegant, and the D-10 reframe of R18 to capacity-headroom is the right call. However, several issues survived the prior review round: the TickWorkTimer end-bookend misses the `@Order(200)` keepalive listener, RandomSource's determinism scope is narrower than R15's claims require, heap-delta assertions will flake on GC, and the statistical thresholds remain uncalibrated. The R15 test proves aggregate reproducibility but not sequence-level determinism. These are addressable without restructuring the phase, but they need fixing before execution.

## 2. Strengths

- **Two-test topology** cleanly isolates determinism failures (R15, engine-direct) from concurrency/transport failures (R16–R18, full-stack) — all three prior reviewers agree
- **EmergenceMetrics counters as shared evidence** for both production observability and test assertions validates the monitoring stack by construction
- **Statistical assertion approach** (count ≥ K, share ≥ X%, amplitude ≥ Y) is the correct framing for emergence; byte-exact fixtures would be meaningless
- **D-10 reframe** of R18 from wire-parity to capacity-headroom avoids the trap of defending an ever-receding baseline
- **Wave dependency graph** is correct — infra → helpers → tests → assertions → docs
- **Nullable EmergenceMetrics injection** keeps production codepaths clean; no behavioral regression when counters are absent
- **EMERGENCE log prefix** is grep-friendly and M5-forward-compatible without design commitment
- **Per-component seed derivation** from a single master property is simple and auditable
- **D-12 forced-formation config** is honest — the test exercises composite paths rather than hoping for emergence at production rates
- **Plan 16-06/16-07 split** keeps each plan within a manageable LOC budget; the placeholder assertion in 16-06 is explicitly marked as temporary

## 3. Concerns

### HIGH

- **TickWorkTimer `@Order(101)` misses `WebSocketKeepaliveService @Order(200)`** — Verified in `WebSocketKeepaliveService.java:52`. The timer's Javadoc says "to final listener completion" but the final listener is at Order 200, not 100. The mean/p99 tick-work metrics will under-report by the cost of keepalive pings. This is a **misleading metric**, not just a measurement gap — D-11 assertions on tick-work budget will pass more easily than they should, giving false confidence.
- **Heap-delta assertion (`<20%`) will flake on GC.** `System.gc()` is a hint, not a guarantee. On G1GC (Java 21 default), a minor collection between the two measurement points can shift heap by 30–50%. The 16-07 plan acknowledges this with a fallback ("heap after 1000 < 2x heap after 200") but the primary assertion is still a hard gate.
- **Statistical thresholds (amplitude ≥0.15, floor ≥5% for ≥80% ticks) are uncalibrated.** No dry-run data exists. Forced-formation knobs (bond-probability=0.5, bond-energy-threshold=15) may produce one-type-dominant dynamics that violate the 5% floor, or the oscillation amplitude at 64x64 may naturally be higher or lower than 0.15.
- **RandomSource determinism scope is narrower than R15 claims.** Confirmed leaky sites: `Entity.BondedPair.formBond()` at `Entity.java:265` (ThreadLocalRandom), `WorldWebSocketHandler.java:191` (spawn placement), `FertilityInitializer.java:46` (world init), `BotClient.java:294` (respawn jitter). Plan 16-02 explicitly excludes these per RESEARCH §RNG Audit (b)/(c), but Plan 16-05's engine-direct test doesn't exercise any of these paths — so the R15 test is actually sound **for its scope**. The problem is the framing: the CONTEXT says R15 proves "deterministic seed test for composite formation" but the proof only covers the tick-pipeline RNG, not the full entity lifecycle.
- **R15 proof proves aggregate reproducibility, not sequence determinism.** Three runs with identical counter deltas could result from different per-tick formation sequences that happen to sum to the same total. For a phase that closes R15 ("emergence from rules, not luck"), this is a gap — the test proves the *total count* is deterministic, not the *sequence of events*.

### MEDIUM

- **`buffs.granted` counter semantics ambiguous.** `BuffRegistry.grant()` may extend expiry of an existing buff rather than creating a new one. If the counter increments on grant attempts rather than new buff episodes, the metric's meaning is unclear and could diverge from what the test asserts.
- **`0 ERROR log entries` is unscoped** — any third-party library logging an ERROR (e.g., Jetty, Jackson, Netty) will fail the test. Should scope to `com.paralife` namespace.
- **Session-gauge polling in 16-07 samples at ~1s intervals but the tick interval is 20ms** — that's ~50 ticks between samples. A transient dropout that resolves within 50 ticks would be invisible to D-11 #4. The assertion is weaker than it appears.
- **SeededBotLauncher error handling is silent** — `catch (Exception e) { /* surface via test timeout */ }` at 16-04 line ~485 means a bot that fails to register will only surface as a timeout, not a descriptive failure. The 100-bot registration latch will wait the full 60s before failing.
- **No `@Timeout` on EmergenceStabilityLoadTest** — a hung bot or deadlock blocks CI indefinitely. This was flagged in the prior Claude review and not addressed.

### LOW

- **Fixture JSON in `.planning/` tree** rather than `build/` — not a build artifact, but it's unusual and could confuse developers expecting build outputs in `build/`.
- **`RandomSource(-1L)` sentinel** introduces a third convention alongside existing `0` (RockConfig) and `null` (EnvironmentEngine config.seed) for "unseeded."
- **16-08 narrative hardcodes point counter values** that will drift when config changes. Fixture references + seed are more durable.

## 4. Suggestions

1. **Fix TickWorkTimer end-bookend to `@Order(Integer.MAX_VALUE)`** or move timing into `TickEngine` around `publishEvent()`. The former is the smallest change; the latter is architecturally cleaner but touches production tick-loop code. The `@Order(Integer.MAX_VALUE)` approach ensures any future listeners at arbitrary orders are captured.
2. **Downgrade heap-delta to a diagnostic** — log the value and include it in the summary, but don't hard-gate on it. Alternatively, use `MemoryMXBean.getHeapMemoryUsage().getUsed()` after explicit `System.gc()` + 200ms sleep, and assert `< 2x` (not `< 20%`) which is a meaningful leak detector without being GC-sensitive.
3. **Add a calibration step before Wave 5** — run 16-06 harness with logging of all raw values (amplitude, floor coverage, counter totals) and set thresholds from observed distribution with 20–30% margin. This should be a Task 0 in 16-07.
4. **Scope the ERROR log assertion** to `com.paralife` loggers: `TestLogCapture` should accept a logger-name prefix filter, defaulting to `"com.paralife"`.
5. **Strengthen R15 proof** — add a second assertion comparing the *per-tick population series* across the three runs (e.g., compare PopulationHistory samples at tick 50, 100, 150, 200). This proves sequence-level determinism without requiring byte-exact world-state snapshots.
6. **Add `@Timeout(value = 180, unit = TimeUnit.SECONDS)`** on EmergenceStabilityLoadTest.
7. **Define explicit determinism scope in R15 framing** — the CONTEXT should state: "R15 proves deterministic tick-pipeline outcomes given fixed server-side RNG. Full-stack end-to-end determinism (including bot placement, respawn jitter, and hybrid-vigor rolls) is out of scope and accepted as stochastic."
8. **Clarify `buffs.granted` semantics** — in EmergenceMetrics, the counter should increment only on *new* buff grants, not extensions. Test this in EmergenceMetricsTest.
9. **Increase session-gauge sampling frequency** — poll every 5 ticks (matching the sampling loop cadence) instead of every ~1s, to catch transient dropouts.
10. **Improve SeededBotLauncher error visibility** — log the exception rather than swallowing it, and consider failing fast if any bot registration throws.

## 5. Risk Assessment: **MEDIUM-HIGH**

| Subsystem | Risk | Justification |
|-----------|------|---------------|
| 16-01 EmergenceMetrics | **LOW** | Proven pattern, nullable injection, unit-tested |
| 16-02 RandomSource | **MEDIUM** | Implementation is straightforward, but determinism scope claims exceed what the refactor delivers |
| 16-03 TickWorkTimer | **MEDIUM** — but **HIGH** if `@Order(101)` ships as-is | The metric will be misleading; the D-11 assertions built on it will give false confidence |
| 16-04 Test Helpers | **LOW** | Test-only code, well-specified, TDD'd |
| 16-05 R15 Test | **MEDIUM** | Test will pass, but the proof it provides is weaker than stated. `auto-start=false` is correctly set. The three-run-in-one-method pattern is sound per the plan's analysis |
| 16-06 Harness | **MEDIUM** | Likely to need knob-tuning on first run; expensive to iterate (~90s per run) |
| 16-07 Assertions | **HIGH** | 15 uncalibrated statistical assertions on a single expensive test. Heap-delta and amplitude thresholds are the top flakiness risks. One flaky assertion blocks R16/R17/R18 evidence entirely |
| 16-08 Narrative | **LOW** | Documentation only; depends entirely on 16-05/16-07 results |

**Overall: MEDIUM-HIGH.** The phase structure is sound and the design decisions are defensible, but execution risk concentrates in Wave 5 (16-07). The three fixes with the highest ROI before execution are: (1) fix the TickWorkTimer order, (2) calibrate or de-gate the heap-delta assertion, and (3) add a calibration pass for statistical thresholds. Without these, expect 2–3 iteration cycles to get the long-run test green.

---

## Consensus Summary

Four reviewers completed substantive reviews (Gemini, Claude, Codex, OpenCode). All four converged on the same core issues.

### Agreed Strengths

- **Two-test topology is correct** — engine-direct for R15 determinism, full-stack for R16/R17/R18 stability (all four reviewers)
- **Statistical assertions are the right approach** for emergence; byte-exact would be meaningless (Gemini, Claude, OpenCode)
- **D-10 R18 reframe to capacity-headroom** is superior to v1.0 wire-parity (Gemini, Claude, OpenCode)
- **EmergenceMetrics pattern mirrors WebSocketMetrics cleanly** (Gemini, Codex, OpenCode)
- **EMERGENCE log prefix is M5-forward-compatible** (Claude, OpenCode; implicit in Codex)
- **Wave dependency ordering is correct** — infrastructure before helpers before tests (Claude, OpenCode)
- **Nullable EmergenceMetrics injection keeps production path clean** (Claude, OpenCode)

### Agreed Concerns (HIGH severity — 2+ reviewers)

| Concern | Reviewers | Severity |
|---------|-----------|----------|
| **Uncalibrated statistical thresholds** (amplitude ≥0.15, floor ≥5%) — no evidence from real runs | Gemini, Claude, Codex, OpenCode | HIGH |
| **Heap-delta assertion is GC-noise** — swings of 30–50%+ are normal; this WILL flake | Claude, Codex, OpenCode | HIGH |
| **TickWorkTimer @Order(101) misses keepalive at @Order(200)** — misleading metric (OpenCode verified at `WebSocketKeepaliveService.java:52`) | Codex, OpenCode | HIGH |
| **RandomSource determinism scope too narrow** — `Entity.java:265`, `WorldWebSocketHandler.java:191`, `FertilityInitializer.java:46`, `BotClient.java:294` still leak non-determinism (OpenCode cited exact file:line) | Codex, OpenCode (explicit), Gemini (as "hidden non-determinism") | HIGH |
| **R15 proof too weak** — matching aggregate counter deltas ≠ deterministic sequence | Codex, OpenCode | HIGH |
| **`paralife.tick.auto-start` must be false** in R15 test else background tick races test | Codex | HIGH |
| **Session-gauge "post-warmup" undefined** — needs explicit warmup tick constant | Claude | HIGH |
| **SeededBotLauncher teardown / error handling unspecified** | Claude, OpenCode | MEDIUM |
| **`0 ERROR log entries` should scope to paralife namespace** | Claude, OpenCode | MEDIUM |
| **`buffs.granted` counter semantics ambiguous** (new buff vs extend expiry) | Codex, OpenCode | MEDIUM |
| **No `@Timeout` on EmergenceStabilityLoadTest** — runaway test blocks CI | Claude, OpenCode | MEDIUM |
| **Session-gauge sampling cadence too slow** (~1s vs 20ms tick) — dropouts within 50 ticks invisible | OpenCode | MEDIUM |
| **16-06/16-07 wave split creates CI false signal** | Claude | MEDIUM |

### Divergent Views

- **Overall risk rating:** Gemini: LOW; Claude: MEDIUM-HIGH; OpenCode: MEDIUM-HIGH; Codex: HIGH on multiple plans. Codex and OpenCode both examined the actual codebase (cited file:line references), so their findings on TickWorkTimer @Order(200) and RandomSource leakage sites carry more weight. OpenCode partially rehabilitates Plan 16-05: "the R15 test is actually sound **for its scope**" — the issue is framing, not correctness.
- **Test helpers (16-04):** Gemini sees no issue; Codex flags over-engineering risk; OpenCode rates LOW ("test-only code, well-specified, TDD'd"); Claude doesn't rate it separately.
- **16-08 narrative:** Codex and OpenCode both flag counter-value drift risk; Gemini and Claude consider it lower priority.

### Recommended Actions Before Execution

1. **Fix TickWorkTimer order** — OpenCode recommends `@Order(Integer.MAX_VALUE)` to capture any future listener; alternative is to time inside `TickEngine.publishEvent()`.
2. **Extend RandomSource audit** — enumerate UUID/placement/fertility/respawn jitter sources; declare explicit determinism scope ("R15 proves deterministic tick-pipeline outcomes; full-stack stochastic by design" — OpenCode suggestion 7).
3. **Run calibration pass** before Wave 5 — run 16-06 harness with no assertions, observe actual threshold values, set margins (+20–30%).
4. **Drop or de-gate heap-delta assertion** — diagnostic-only; or use `MemoryMXBean` after explicit `System.gc()` + 200ms sleep and assert `< 2x`, not `< 20%`.
5. **Define `WARMUP_TICKS` constant** and scope session-gauge + population-floor assertions to post-warmup window.
6. **Strengthen R15 proof** — OpenCode specifically suggests comparing per-tick `PopulationHistory` samples at tick 50/100/150/200 across the three runs (sequence-level determinism without byte-exact snapshots).
7. **Add `@Timeout(value = 180, unit = SECONDS)` on EmergenceStabilityLoadTest**.
8. **Scope ERROR log assertion** to `com.paralife` loggers; add prefix filter to `TestLogCapture`.
9. **Clarify `buffs.granted` semantics** — increment only on new episodes, not expiry extensions; test this in `EmergenceMetricsTest`.
10. **Increase session-gauge sampling frequency** — poll every 5 ticks, not every ~1s, to catch transient dropouts.
