---
phase: 20
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-05-09T13:12:15Z
plans_reviewed:
  - 20-01-PLAN.md
  - 20-01b-PLAN.md
  - 20-02-PLAN.md
  - 20-03-PLAN.md
  - 20-04-PLAN.md
  - 20-05-PLAN.md
  - 20-06-PLAN.md
models:
  gemini: gemini-3.1-pro-preview
  claude: opus
  codex: gpt-5.5 (reasoning_effort=high)
  opencode: openrouter/deepseek/deepseek-v4-pro
---

# Cross-AI Plan Review — Phase 20

## Gemini Review

# Phase 20 Plan Review

## Summary
The Phase 20 plans present a highly pragmatic, evidence-driven approach to scale engineering. By rejecting the architectural complexity of genuine connection multiplexing in favor of transport and JVM tuning (adhering strictly to the `WS:entity 1:1` principle), the plans minimize regression risk while directly satisfying SCALE-08 and SCALE-09. The breakdown across 6 distinct plans is exceptionally well-ordered, separating toolchain bootstrapping, baseline measurement, config wiring, and evidence-gated codec optimizations into clear, testable chunks.

## Strengths
- **Evidence-Driven Tuning**: Strictly gating codec and configuration changes behind JFR and async-profiler evidence prevents guesswork and scope creep.
- **Architectural Integrity**: Explicitly documenting and protecting the `WS:entity 1:1` design constraint in `CLAUDE.md`, `README.md`, and inline code ensures the system's foundational concurrency model is preserved.
- **Flake Mitigation**: The strategy to mask the known `TD-19.5-A` golden-trace flake by running the three-gate stack in-suite (and enforcing two consecutive passes) is a smart, pragmatic workaround.
- **Dependency Ordering**: The plan sequence (Toolchain -> Baseline -> Additive Config -> Docs -> Codec Opts -> Finalization) is perfectly pipelined, allowing the config plans (2 and 3) to execute in parallel while waiting for baseline measurements.
- **Backward Compatibility**: The additive nature of the `@ConfigurationProperties` and the careful handling of `outbound-queue-size` (D-20) ensure existing admission logic is not disturbed.

## Concerns
- **Virtual Threads and `ThreadLocal` (Severity: MEDIUM)**: Plan 5 suggests `ThreadLocal<StringBuilder>` as a potential codec optimization. While safe at the current 1,000-connection scale, Virtual Threads are designed to scale to millions. Attaching a `ThreadLocal` to a VT permanently pins memory to that specific VT. Because `OutboundSender` VTs are long-lived (one per session), this essentially behaves like traditional thread-local pooling. While acceptable for M4, it runs counter to the VT philosophy of keeping threads lightweight and could present memory retention issues at much higher scales.
- **Forced-Fallback Fragility (Severity: LOW)**: In Plan 5, if no codec hot paths are found, the plan mandates tightening a config knob to produce a "non-zero, baseline-improving delta" in tick work time or detach timeouts. If the system is already highly optimized, the baseline noise floor might mask any small gains, potentially causing the execution agent to loop or escalate unnecessarily.
- **`@SpringBootTest` Record Binding (Severity: LOW)**: As anticipated in Plan 2, `@SpringBootTest(classes = JettyRuntimeConfig.class)` will likely fail because Spring Boot requires a `@Configuration` class to scan records. The plan provides a fallback, but the execution agent will need to actively use `AdmissionConfigTest.java` as a template to get the wiring right on the first try.

## Suggestions
- **Codec Optimization Alternatives**: If `StringBuilder` allocation is a hot path, first rely on Java 21's Escape Analysis and Generational ZGC (which are highly optimized for short-lived allocations). If pooling is truly needed, consider a bounded `ConcurrentLinkedQueue` or `ArrayBlockingQueue` of reusable buffers rather than `ThreadLocal`, to maintain the lightweight nature of Virtual Threads.
- **Fallback Rule Flexibility**: Amend the Plan 5 forced-fallback rule: if tweaking Jetty/App knobs fails to produce a statistically significant delta above the noise floor, allow the agent to document the noise floor and proceed as a verified no-op, rather than treating it as a hard failure.
- **Test Class Template Strictness**: In Plans 2 and 3, explicitly instruct the agent to copy the inner `@Configuration` / `TestApp` pattern directly from `AdmissionConfigTest.java` to prevent the expected `@SpringBootTest` class loading failures on records.

## Risk Assessment
**LOW**. The phase is predominantly additive, config-driven, and meticulously gated by existing test suites. The commitment to empirical measurement (JFR) before optimization almost entirely mitigates the risk of introducing regressions or performance bottlenecks. The careful handling of the `WS:entity 1:1` invariant further reduces architectural risk.

---

## Claude Review

# Phase 20 Plan Review

## Summary

The Phase 20 plan suite is unusually thorough and well-cross-referenced — 7 plans (counting the 1/1b split) with explicit dependency ordering, strong CONTEXT/RESEARCH/PATTERNS upstream, and consistent application of the project's @ConfigurationProperties + spec-doc-per-phase patterns. The hardest design tension is in Plan 5's "SCALE-08 forced-fallback" rule, which is intended to keep the phase from shipping a zero-delta no-op but creates a measurable evidence-vs-shippable-change conflict that should be tightened. Most other concerns are minor: JFR file-size discipline that may force lossy filtering, an untested legacy-key fallback in Plan 2, and a couple of acceptance-criterion regex weaknesses. The three-gate stack + D-12 enforcement + T-20-V5 bounds checks are solid against codec regression. Overall: ready to execute with three targeted clarifications.

## Strengths

- **Dependency ordering is unambiguous.** 20-01 → 20-01b gates everything; 20-02/20-03 parallel; 20-04/20-05 sequential after 20-01b; 20-06 closes. The split of toolchain bring-up (1) from binary capture (1b) is exactly right — keeps the autonomous portion separate from the human-required ritual.
- **A1–A8 verification gate in Plan 1b.** Routing RESEARCH assumptions into a structured meta.json + summary read by Plan 2 (drop-field-if-A1-fails) and Plan 5 (codec-triage input) closes a common failure mode.
- **D-12 disabled-tests enforcement is per-file, not count-based** (Plan 5 acceptance) — survives test renames better than `grep -c '@Disabled'`.
- **D-02 three-place codification with grep-verifiable acceptance** (Plan 6 Task 6.4: 4-file match for `WS:entity 1:1`). Hard to "fix" the architectural choice silently.
- **Threat model is properly integrated.** T-20-V5 (codec validation bounds), T-20-DOS-1 (frame size cap), and T-20-DOS-2 (pinning) carry through to specific acceptance criteria, not just decoration.
- **Plan 2 keeps legacy `paralife.websocket.idle-timeout-ms` alive** for one phase — correct migration discipline.
- **AppRuntimeConfig.OutboundConfig is silent on outbound-queue-size** (D-20). The Test 7/8 binding round-trip + admission invariant proof in Plan 3 is a good pattern.

## Concerns

### HIGH

- **Plan 5 forced-fallback rule undermines D-10's "JFR-driven only" stance.** If the c22e487 baseline shows no codec hot path AND no pinning storm, Plan 5 mandates a `paralife.runtime.*` knob tightening to manufacture a non-zero delta on `tick.health.work-time-ms` or `outbound.detach.timeout`. This is exactly the guesswork D-10/D-13 forbid. The escape hatch (`escalate-no-safe-tightening`) is too easy to skip because the executor is incentivised to ship. Either: (a) reframe SCALE-08 evidence to allow "negative result with citations" as a valid outcome (preferred — a documented baseline showing the system is already well-tuned at 1000 bots IS measured evidence), or (b) require the fallback knob change to cite a specific baseline JFR signal (not just "plausibly-safe tightening") with the same triage rigor as a codec opt. As written, the rule risks shipping a knob change because the rule says we must, not because evidence supports it.

### MEDIUM

- **JFR file-size cap (≤5 MB/file, ≤20 MB total) is tight.** A 180s × 1000-bot run with `settings=profile` typically produces 50–200 MB raw. Plan 1b says "use `jfr filter` if needed," but aggressive filtering can drop the very allocation/pinning events Plan 5 needs to triage codec hot paths. Recommend either (a) relaxing to ≤10 MB/file, ≤50 MB total, or (b) committing a specific `.jfc` config + `jfr filter` command that preserves `jdk.ObjectAllocationInNewTLAB`, `jdk.GCPhasePause`, `jdk.VirtualThreadPinned`, and `jdk.JavaMonitorEnter` events at full fidelity.
- **Plan 2 legacy-key fallback is untested.** `JettyDeflateCustomizer.jettyRequestUpgradeStrategy` has non-trivial dual-key resolution logic (line: "if `legacyIdleTimeoutMs != 60000L && runtimeConfig.idleTimeoutMs() == 60000L`"). The `JettyRuntimeConfigTest` covers the record but not the fallback. A regression here silently breaks operators who set the legacy key. Add a Spring slice test that exercises both yaml-set combinations.
- **Plan 5 pinning case interacts ambiguously with the forced-fallback rule.** Task 5.0's decision tree lists `pinning-dominates` and `forced-fallback-knob` as parallel branches, but they overlap: if the JFR shows pinning is the binding constraint, the right answer is `pinning-dominates` (scope-expansion checkpoint), not a forced-fallback knob tightening on something unrelated. Make the precedence explicit: pinning-dominates supersedes forced-fallback.
- **No smoke test asserts Plan 2 defaults match Jetty 12.0.18 defaults at runtime.** `JettyRuntimeConfigTest.defaultsMatchJetty12Defaults` checks the record's defaults but not that those values are what Jetty actually applies post-upgrade. A typo in `@DefaultValue("4096")` slips through. Optional: add an integration test that opens a WS connection and inspects the negotiated session policy.

### LOW

- **Plan 4 §3 GC-choice rationale uses `Pending — JFR-driven` text that Plan 6 must replace.** Easy to miss. Plan 6 Task 6.1 has `grep -ciE "^\\s*_pending_"` but won't catch the un-italicised `Pending — JFR-driven`. Strengthen the regex or use a sentinel like `_PENDING_PLAN_5_` that's hard to leave in by accident.
- **`OutboundConfig.queueWatermarkPct` and `EncodeConfig.encodeBatchHint` bind but no consumer reads them in Phase 20.** Dead-config risk. Defensible (live-tunable seam for M5 admin UI), but call it out in `20-RUNTIME.md` §2.2 so a future reader doesn't assume they're wired.
- **Plan 1b's git checkout dance** (`git checkout c22e487` → capture → `git checkout -`) assumes a clean worktree. Plan 1's commits (bootstrap doc + profiles README) must already be committed before 1b runs, otherwise checkout fails. Plan 1b's resume-signal should explicitly verify `git status` clean before the checkout.
- **Plan 5 `synchronized(session)` count check** has no recorded baseline value, just "unchanged from c22e487 baseline." If someone added a `synchronized(session)` block in Plan 5's codec opt by accident, the comparison fails open. Pin it: "exactly 4 occurrences in OutboundSender.java" (or whatever the actual count is at c22e487).
- **Plan 4 manual verification asks for three full recipe runs (100/500/1000 bots).** That's ~10–15 minutes of wall time per recipe at full duration. Acceptable smoke shape: short-duration `--duration 30` for the 100/500 verification, full duration only for 1000.
- **Plan 5 Task 5.1 "two consecutive in-suite greens" doesn't actually address TD-19.5-A.** TD-19.5-A only flakes in *isolated* runs; in-suite is supposed to be reliable. So 2× in-suite is belt-and-braces but doesn't guard against a *new* flake source. Document what 2× actually catches (state-pollution flakes), not TD-19.5-A.

## Suggestions

1. **Reframe SCALE-08 evidence acceptance.** Add to CONTEXT or 20-RUNTIME.md §1: "Measured evidence that the baseline is already at the relevant performance floor at the 1000-bot tier IS sufficient SCALE-08 evidence — a documented null-result is a measurement, not a no-op." This lets Plan 5 ship the negative finding without forced-fallback theatre.
2. **If you keep the forced-fallback rule, require it to cite a specific baseline JFR signal** (not just "plausibly-safe tightening"). Wire the rule to the same triage rigor as codec opts.
3. **Relax profile size discipline OR pin a `.jfc` filter.** ≤10 MB/file, ≤50 MB total is more realistic for 1000-bot 180s runs at `settings=profile`. Alternatively, commit `tools/p20-profile.jfc` that selects exactly the events Plan 5 needs.
4. **Add a Plan 2 test for the legacy `paralife.websocket.idle-timeout-ms` fallback.** Spring slice test exercising (legacy-only set), (new-only set), (both set) — assert effectiveIdleMs in each case.
5. **Make pinning-dominates precedence explicit in Plan 5 Task 5.0.** Decision tree should list pinning first; only if pinning ≤ threshold does forced-fallback enter consideration.
6. **Add an "if-then" matrix in 20-04 §3** — for each tier, the recipe's GC choice depends on baseline JFR finding X; document the decision predicate so the executor can replace `Pending — JFR-driven` mechanically rather than narratively.
7. **Plan 6 Task 6.5: extend the Per-Task Verification Map row count check.** 14 is a soft lower bound — pin actual expected count once all PLAN.md files are written, otherwise Task 6.5 silently passes with stub-row leakage.

## Risk Assessment

**MEDIUM**.

Justification: The plans are technically sound, well-ordered, and exhaustively cross-referenced — code-correctness risk is LOW (the @ConfigurationProperties wiring is a direct mirror of `AdmissionConfig`; defaults match Jetty defaults; three-gate stack guards equivalence). The disabled-test enforcement (D-12) and codec validation bound checks (T-20-V5) are robust.

The MEDIUM rating is driven primarily by the Plan 5 forced-fallback rule, which encodes a perverse incentive in the very phase whose D-10/D-13 decisions explicitly forbid evidence-free recommendations. If the c22e487 baseline shows the system is already well-tuned (a real possibility — Phase 19.1 closed many performance items), the executor faces a tension between shipping evidence-free changes and triggering a checkpoint that may be perceived as "failing" the phase. Reframing SCALE-08 to accept a null-result + the JVM/Jetty/codec tuning *surface* as the deliverable resolves this cleanly.

The JFR file-size discipline is the next-most-likely operational friction point during execution. Everything else is cosmetic or readily fixable in flight.

---

## Codex Review

## Summary

The phase plan is unusually thorough and mostly coherent: it protects the locked WS:entity 1:1 architecture, makes SCALE-09 evidence-driven, and gives operators a concrete tuning surface. The main risks are dependency mismatches around Plan 20-01/20-01b, some Spring/Jetty binding assumptions that may not compile cleanly, and a forced “non-zero improvement” requirement in Plan 20-05 that could incentivize unsafe tuning if the measured baseline is already good.

## Strengths

- Strong traceability from SCALE-08/SCALE-09 to plans, artifacts, validation, and docs.
- Good decision discipline around “equivalent overhead reduction” instead of real multiplexing.
- Baseline SHA anchoring with `c22e487` is a solid reproducibility move.
- The three-gate equivalence stack is correctly reused for compact protocol safety.
- D-12 disabled-test constraints are explicit and repeatedly protected.
- Plan 20-03 handles D-20 well by not moving or shadowing `paralife.admission.backpressure.outbound-queue-size`.
- Documentation deliverables are not an afterthought; `20-RUNTIME.md`, README, CLAUDE, and inline comments reinforce the architecture.

## Concerns

- **HIGH: Plan 20-02 depends on 20-01 but needs A1 from 20-01b.**  
  Plan 20-02 repeatedly says to read `20-01-SUMMARY.md` for Jetty 12.0.18 setter availability, but A1 is verified in 20-01b. Either Plan 20-02 must depend on 20-01b, or A1 must move into Plan 20-01.

- **HIGH: Plan 20-05’s “must show non-zero improvement” rule is risky.**  
  If the baseline is already near floor, forcing a default tightening just to produce a measurable delta can create cargo-cult tuning. SCALE-08/SCALE-09 require measured strategy and evidence, not necessarily a guaranteed improvement on two selected gauges from every Plan 5 change. A defensible “no codec hot path; no safe tuning; no change shipped” result should be acceptable if documented.

- **HIGH: JFR artifact size budget may be unrealistic.**  
  `settings=profile` for 60-180 seconds at 1000 bots may exceed 5 MB. The plan mentions filtering, but acceptance requires raw files under 5 MB. Decide whether filtered JFRs are acceptable evidence, and preserve enough metadata to avoid losing the events needed for later review.

- **MEDIUM: Jetty idle-timeout defaults are described inconsistently.**  
  Plan 20-02 says defaults match Jetty 12.0.18 defaults, but `idleTimeoutMs=60000` is the project’s existing default, while the plan itself says Jetty default is 30000. Reword to “defaults preserve current project behavior” rather than “Jetty defaults.”

- **MEDIUM: Legacy/new idle-timeout precedence is not reliably detectable.**  
  With a primitive `@DefaultValue("60000")`, the record cannot tell whether `paralife.runtime.jetty.idle-timeout-ms=60000` was explicitly set or defaulted. The fallback logic cannot correctly implement “new key wins if both are set” in all cases. Use Spring `Binder` or a nullable wrapper if this distinction matters.

- **MEDIUM: Spring config tests may be brittle.**  
  Static inner test classes with `@SpringBootTest(classes = JettyRuntimeConfig.class)` / `AppRuntimeConfig.class` may not be discovered or may not bind as intended. Prefer `ApplicationContextRunner` with `ConfigurationPropertiesAutoConfiguration` and `withPropertyValues(...)`.

- **MEDIUM: Plan 20-03 creates config knobs with no consumers.**  
  This is acceptable if framed as binding surface only, but the plan currently calls some fields `[live-tunable]` and “application-level tuning” while no runtime behavior changes. That weakens SCALE-08 unless later plans consume the knobs or `20-RUNTIME.md` is explicit that they are future-ready only.

- **MEDIUM: async-profiler install path conflicts with repo-size policy.**  
  Research resolved “external + bootstrap docs,” but Plan 20-01 still allows in-tree `tools/async-profiler/`. Since artifacts already have a tight size budget, keep the tool external unless there is a deliberate reason to commit it.

- **LOW: README replacement may be scope-creepy.**  
  Replacing the one-line README with project overview is fine, but Plan 20’s objective is tuning. Keep it minimal and avoid accidentally becoming a broader documentation rewrite.

- **LOW: “666-test suite” does not match supplied context.**  
  The context says current count is 136 test files and stale counts were dropped. The plans should avoid repeating any “666 tests” or similar numeric claim unless verified.

## Suggestions

- Change Plan 20-02 dependency to `depends_on: [20-01b]`, or move A1 verification into Plan 20-01.
- Rewrite Jetty defaults language: “defaults preserve current project behavior and Jetty’s existing frame/buffer defaults where applicable.”
- Replace idle-timeout fallback logic with one of:
  - keep only legacy key for idle timeout this phase and document new key as not yet owning that field, or
  - bind with `Binder`/nullable properties so explicit-new vs default-new can be detected.
- Use `ApplicationContextRunner` for `JettyRuntimeConfigTest` and `AppRuntimeConfigTest`.
- Relax Plan 20-05 acceptance to: “ship an optimization only if measured improvement exists; otherwise document no safe change and preserve baseline evidence.” Do not force tuning.
- Clarify whether committed `.jfr` files are raw, filtered, or summarized. If filtered, require `*.meta.json` to list included/excluded events.
- Add a small compile gate immediately after Plan 20-02 because Jetty API setter/package assumptions are one of the highest compile-risk areas.
- In Plan 20-03, label unused fields as “reserved” rather than “live-tunable” unless a consumer actually reads them.
- Add explicit server lifecycle cleanup to human capture plans: kill server on failure, confirm port 8080 is free before each run, and avoid mixing stale JFRs from failed attempts.

## Risk Assessment

**Overall risk: MEDIUM.**

The plan is strong on traceability, documentation, and protocol-regression safety, so the architecture is unlikely to drift accidentally. The biggest risks are execution risks: profile capture may be harder than specified, Plan 20-02 may hit Spring/Jetty binding issues, and Plan 20-05’s forced-improvement requirement could push unsafe or meaningless tuning. Fixing the dependency mismatch and softening the forced-delta rule would bring the plan close to low risk.

---

## OpenCode Review

Let me also check a few more key files to complete my analysis:
# Cross-AI Plan Review — Phase 20

## 1. Summary

Phase 20's plans define a cautious, defensible tuning phase: profile the c22e487 baseline, add two `@ConfigurationProperties` records for Jetty and application knobs, ship JVM presets as documentation, land JFR-driven codec opts (or a forced-fallback runtime knob tightening if evidence is absent), and finalise `20-RUNTIME.md` with three-place D-02 rationale codification. The 6-plan (7 with 20-01b) wave structure respects strict dependency ordering. The decision to take SCALE-08's "or equivalent" escape hatch (tune per-connection cost rather than collapse connections) is explicit, well-argued, and defended at three codification sites. The core risk is execution uncertainty in Plan 5 — its output is gated on JFR evidence that doesn't exist yet.

## 2. Strengths

- **Sound dependency ordering.** Wave 1 toolchain → Wave 2 baseline + additive records in parallel → Wave 3 presets + codec opts → Wave 4 docs. No cycles, no false parallelism.
- **Reproducibility discipline.** D-19 SHA-anchored baselines (c22e487) with `git checkout` ritual, `.meta.json` sidecars, and explicit re-run instructions — makes "evidence over assertion" actionable.
- **D-20 alongside-not-move is well-defended.** Plan 3 includes an explicit Test 8 (`d20AlongsideNotMove`) that verifies `AdmissionConfig.backpressure().outboundQueueSize() == 128`, and Plan 5 adds per-file `@Disabled` grep to prevent accidental re-enable.
- **Plan 20-05's decision tree is well-structured.** Three outcomes (codec-opts → pinning-dominates → forced-fallback) each have a clear verification gate and escalation path. The B2/W5 non-zero-delta requirement prevents silent no-op shipment.
- **Jetty extension point reuse.** `addWebSocketConfigurer` already exists at `JettyDeflateCustomizer.java:75-82`; Plan 2 chains 7 additional setters onto the same lambda — minimal surface area, zero new architectural points.
- **Legacy key fallback is correct.** Plan 2's `effectiveIdleMs` logic correctly gives the new `paralife.runtime.jetty.idle-timeout-ms` priority when explicitly set, while still resolving the legacy `paralife.websocket.idle-timeout-ms` when the new key is at default.
- **Three-gate stack + TD-19.5-A flake mitigation.** D-11 explicitly gates on in-suite runs only (masks the ~40% isolated flake), and Plan 5 requires two consecutive greens per opt — pragmatic given the known VT race.

## 3. Concerns

- **(HIGH) SCALE-08 evidence is structurally fragile.** Plan 5 could ship with a single knob tightening (e.g., `idle-timeout-ms` reduction) as the sole SCALE-08 deliverable. The forced-fallback rule prevents a true no-op, but a single default change may not constitute meaningful "overhead reduction at high bot counts." The requirement text's "or equivalent" clause technically permits this, but Phase 21's benchmark may reveal the tuning didn't materially move the needle. Consider adding a minimum effect-size threshold to the Plan 5 success criteria (e.g., `work-time-ms` delta ≥ baseline_p95 × 5%).

- **(HIGH) `jdk.VirtualThreadPinned` is load-bearing but Plan 5's scope excludes the most impactful fix.** RESEARCH §Pitfall 2 identifies 4 `synchronized(session)` pinning sites as the highest-risk overhead at scale. If the baseline JFR shows non-trivial pinning, Plan 5's decision tree escalates to a scope-expansion checkpoint rather than applying the fix (`synchronized` → `ReentrantLock`). The `ReentrantLock` conversion is a small, well-understood refactor that could dominate all other tuning wins combined. Deferring it to a "pinning-dominates" checkpoint that may or may not be picked up introduces a real bottleneck risk.

- **(MEDIUM) CLAUDE.md insertion spans GSD boundary markers.** Plan 6 Task 6.2 inserts the Runtime tuning subsection after line 142 (`<!-- GSD:architecture-end -->`) but before line 144 (`<!-- GSD:skills-start -->`). The content fits the Architecture section semantically, but GSD tools that parse section-boundary comments may need updating if they rely on strict adjacency. Low practical risk, but worth flagging.

- **(MEDIUM) `idleTimeoutMs` overrides interact with keepalive silently.** Plan 2 exposes Jetty's idle timeout as a tunable knob with default 60000ms. The keepalive PING cadence is `paralife.websocket.keepalive-ticks: 30` × `tick.interval-ms: 500` = 15s. If an operator tunes `idleTimeoutMs` below 15s without also adjusting keepalive cadence, idle connections close spuriously. No cross-validation guard exists. Consider adding a compact-ctor or `@PostConstruct` validation in `JettyDeflateCustomizer` that warns when `idleTimeoutMs < 2 × keepaliveTicks × tickIntervalMs`.

- **(MEDIUM) `encodeBatchHint` and `parallelEncodeThreshold` are defined but never consumed.** Plan 3 states consumer wiring is "deferred to later plans" — acceptable for reservation fields. However, their presence in `application.yml` could confuse operators who try tuning them and see no effect. Documenting them as `[reserved for Phase 19.1 — no effect in Phase 20]` in the yaml comments and `20-RUNTIME.md` §2.2 would close this gap.

- **(LOW) `OutputBufferSize` knob lacks a lower-bound cross-check with typical frame sizes.** Jetty's `outputBufferSize` defaults to 4096; our typical TickFrame is ~256 bytes. Reducing it below 256 could cause Jetty to fragment or allocate internal overflow. Plan 2's compact-ctor rejects `outputBufferSize < 256` which is sufficient, but the doc doesn't explain why 256 was chosen.

- **(LOW) Profile capture assumes a single `loadHarnessJar` naming pattern.** The shell commands use `build/libs/paralife-*-load-harness.jar` glob. If Phase 18's build produces multiple JARs matching that pattern (e.g., a fat jar and a slim jar), the glob may pick the wrong one. The existing `build.gradle.kts` `loadHarnessJar` task produces a single artifact, so this is currently safe.

## 4. Suggestions

- **Add an effect-size threshold to Plan 5's forced-fallback rule.** Instead of "any non-zero delta," require the delta to exceed `max(1ms, baseline_p95 * 0.05)` for `work-time-ms` or `max(1, baseline_count * 0.05)` for `detach.timeout`. Prevents a token change from satisfying SCALE-08.

- **Move the `ReentrantLock` conversion from "scope-expansion checkpoint" to a first-class Plan 5 task.** If the baseline JFR shows `jdk.VirtualThreadPinned` count > 50/min, Plan 5 should land the conversion directly (only if three-gate stack stays green post-conversion). This is the single highest-impact code change Phase 20 could ship. The `synchronized(session)` → `ReentrantLock` conversion is well-understood, small (~20 line change across 4 sites), and carries low semantic risk since the monitor is only used for mutex, never for `wait()`/`notify()`.

- **Add a keepalive-vs-idle cross-validation check in Plan 2.** In `JettyDeflateCustomizer.jettyRequestUpgradeStrategy()`, add: `if (runtimeConfig.idleTimeoutMs() < keepaliveTicks * tickIntervalMs * 2) log.warn(...)`. Optional — operator-documentation in `20-RUNTIME.md` §3 would also suffice.

- **Tag `encodeBatchHint` and `parallelEncodeThreshold` as `[Phase 19.1 reservation]` in yaml comments.** Replace `[live-tunable]` with `[Phase 19.1 reservation — no-op in Phase 20]` so operators don't waste time tuning these before the consumer is wired.

- **Add a `profiles/.gitattributes` entry or `.gitignore` note.** JFR binary files benefit from `*.jfr binary` in `.gitattributes` and should have max size documented so reviewers don't accidentally commit a 200MB recording.

## 5. Risk Assessment

**OVERALL: MEDIUM**

The phase is well-scoped, well-ordered, and well-guarded against regression (three-gate stack + full-suite minus 4 disabled + D-20 invariant test). The architecture decisions (WS:entity 1:1, no wire mutation, no multi-entity-per-session) are locked and defended at three codification sites.

The medium risk comes from Plan 5's evidence-dependence: the phase's measurable SCALE-08 output is contingent on JFR findings that don't exist yet. The forced-fallback rule (B2/W5) prevents a true zero-delta shipment but may produce a weak result. The `ReentrantLock` conversion — the single most impactful tuning change — is gated behind a scope-expansion checkpoint rather than treated as a first-class task. If the baseline JFR shows significant pinning and Plan 5 doesn't land the Lock conversion, Phase 21's benchmark may find the per-connection overhead path is still the binding constraint, triggering a late-cycle revisit of D-01/D-03 that could have been avoided.

---

## Consensus Summary

Four reviewers agree the plan is well-ordered, traceability-strong, and architecturally
disciplined (WS:entity 1:1 protected, D-20 alongside-not-move correctly handled, three-gate
equivalence stack reused, D-12 per-file disabled-test enforcement). Divergence is mostly on
overall risk: Gemini = LOW; Codex / Claude / OpenCode = MEDIUM. The MEDIUM camp's pivotal
concern is Plan 20-05's forced-fallback rule.

### Agreed Strengths

- **Dependency ordering** — Wave 1 toolchain → Wave 2 baseline + additive records (parallel)
  → Wave 3 presets + codec opts → Wave 4 finalisation. Codex/Gemini/Claude/OpenCode all
  endorse the 6-plan (7 with 20-01b) split.
- **Reproducibility discipline** — D-19 SHA-anchored baseline against `c22e487`,
  `.meta.json` sidecars, structured A1–A8 verification gate in 20-01b. Codex/Claude/OpenCode.
- **Architectural integrity** — WS:entity 1:1 codified at three sites (README / CLAUDE.md /
  inline). All four reviewers cite this as the right architectural posture for SCALE-08's
  "or equivalent" escape hatch.
- **D-20 alongside-not-move** — `paralife.admission.backpressure.outbound-queue-size` left
  authoritative; explicit invariant test (`d20AlongsideNotMove`). Codex/OpenCode call this
  the canonical pattern for the new `paralife.runtime.app.*` record.
- **Threat-model integration** — T-20-V5 (codec validation bounds), T-20-DOS-1 (frame size
  cap), T-20-DOS-2 (pinning) all flow to specific acceptance criteria. Claude flags this
  explicitly; Codex/OpenCode endorse implicitly via "well-defended" and "no decoration"
  framings.
- **Backward compatibility** — additive `@ConfigurationProperties` + legacy idle-timeout
  fallback for one phase. Gemini/OpenCode/Codex.
- **Codec safety** — Three-gate stack + D-12 enforcement + T-20-V5 bounds checks block
  protocol regression. Claude/OpenCode.

### Agreed Concerns (highest priority — raised by 2+ reviewers)

1. **(HIGH — Codex, Claude, Gemini, OpenCode) Plan 20-05 forced-fallback rule
   creates evidence-vs-shippable tension.** The "must show non-zero improvement"
   requirement contradicts D-10/D-13's evidence-only stance: if the c22e487 baseline shows
   the system is already well-tuned, the executor faces pressure to ship a knob change
   because the rule says we must, not because evidence supports it.
   - Convergent fix: reframe SCALE-08 to accept a **documented null-result** as valid
     measured evidence (Claude, Codex). Failing that, require the fallback knob change
     to cite a specific baseline JFR signal with the same triage rigor as a codec opt
     (Claude). OpenCode adds: enforce a minimum effect-size threshold (≥ 5 % of
     `work-time-ms` p95) so token-sized changes can't satisfy SCALE-08.

2. **(HIGH — OpenCode primary, Gemini secondary) `synchronized(session)` →
   `ReentrantLock` conversion is the highest-impact tuning change but is gated behind a
   scope-expansion checkpoint.** RESEARCH §Pitfall 2 identifies 4 pinning sites; OpenCode
   argues the conversion should be a first-class Plan 5 task when JFR
   `jdk.VirtualThreadPinned` count > 50/min, not deferred. Risk: Phase 21's benchmark
   discovers the per-connection overhead path is still the binding constraint and
   triggers late-cycle rework.

3. **(MEDIUM — Codex, Claude) JFR file-size budget (≤ 5 MB/file, ≤ 20 MB total) is
   unrealistic for 1000-bot 180 s `settings=profile` runs.** Aggressive `jfr filter` to
   meet the cap risks dropping the very allocation/pinning events Plan 5 needs. Convergent
   fix: relax to ≤ 10 MB/file / ≤ 50 MB total **or** commit a pinned `.jfc` filter
   preserving `ObjectAllocationInNewTLAB`, `GCPhasePause`, `VirtualThreadPinned`,
   `JavaMonitorEnter` at full fidelity.

4. **(MEDIUM — Codex, Claude) Plan 20-02 legacy-key idle-timeout fallback is untested
   and undetectable.** With primitive `@DefaultValue("60000")`, the record cannot
   distinguish "explicitly set to 60000" from "defaulted." `effectiveIdleMs` resolution
   logic ("new key wins if both set") cannot be reliably implemented. Convergent fix:
   add Spring slice tests covering all four yaml-set combinations; consider Spring
   `Binder` or nullable wrapper to detect explicit-set.

5. **(MEDIUM — Claude, OpenCode, Codex) Reserved/unconsumed config knobs in
   `paralife.runtime.app.*` (`queueWatermarkPct`, `encodeBatchHint`,
   `parallelEncodeThreshold`) bind but no Phase-20 consumer reads them.** Defensible as
   live-tunable seam but creates "dead-config" risk for operators. Convergent fix:
   tag yaml comments + `20-RUNTIME.md` §2.2 with `[reserved — no effect in Phase 20]`
   instead of `[live-tunable]`.

6. **(MEDIUM — Codex single, but well-evidenced) Plan 20-02 declares
   `depends_on: [20-01]` but its compile/wiring relies on Plan 20-01b's A1 (Jetty 12.0.18
   setter availability).** Either move A1 verification into 20-01 or change 20-02's
   declared dependency to `[20-01b]`. As written, Plan 20-02 can be claimed runnable
   when its real precondition isn't met.

### Divergent Views

- **Overall risk** — Gemini: LOW. Codex / Claude / OpenCode: MEDIUM. The split traces
  back to whether Plan 20-05's forced-fallback is interpreted as a guardrail (Gemini)
  or a perverse incentive (the others).
- **VT-`ThreadLocal` codec pooling (Plan 20-05)** — Gemini flags MEDIUM concern: pinning
  memory to long-lived `OutboundSender` VTs runs counter to VT philosophy at higher
  scales; suggests bounded queue of reusable buffers instead. No other reviewer raises
  this. Worth investigating during Plan 5 codec-opt triage.
- **TD-19.5-A flake mitigation** — Three reviewers endorse the "two consecutive in-suite
  greens" rule. Claude alone notes that TD-19.5-A only flakes in *isolated* runs, so the
  2× rule actually guards against state-pollution flakes, not TD-19.5-A — recommends
  documenting what 2× catches accurately.
- **`@SpringBootTest` record binding** — Gemini predicts the `classes = JettyRuntimeConfig.class`
  shape will fail because Spring needs a `@Configuration` class to scan records;
  recommends explicit `AdmissionConfigTest.java` template copy. Codex recommends
  `ApplicationContextRunner`. The plans already have a fallback path but reviewers
  agree it should be the primary path, not the fallback.
- **README replacement (Plan 20-06)** — Codex flags LOW scope-creep risk
  (Phase 20's objective is tuning, not documentation rewrite). Other reviewers don't
  raise it.
- **`66x-test suite` numeric claim** — Codex flags a stale "666 tests" reference. Worth a
  cleanup pass against the current test count.

### Recommended Pre-Execute Actions

Before launching `/gsd-execute-phase 20`, address (in priority order):

1. Resolve the Plan 20-05 forced-fallback / SCALE-08 evidence framing (concern #1).
2. Decide whether `synchronized → ReentrantLock` is a first-class Plan 5 task gated by JFR
   evidence, or stays in scope-expansion (concern #2). Document the decision in
   20-CONTEXT.md.
3. Either pin a `.jfc` filter or relax JFR file-size caps (concern #3).
4. Fix Plan 20-02 dependency declaration to `[20-01b]` or move A1 into 20-01 (concern #6).
5. Add Spring slice test coverage for legacy idle-timeout fallback (concern #4).
6. Tag reserved knobs explicitly in yaml + RUNTIME.md (concern #5).

To incorporate this feedback into planning, run:

```
/gsd-plan-phase 20 --reviews
```
