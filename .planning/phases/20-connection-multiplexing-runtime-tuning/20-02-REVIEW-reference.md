---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-27T16:39:20Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-02-PLAN.md", "src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java", "src/main/java/com/paralife/admission/AdmissionConfig.java", "src/test/java/com/paralife/admission/AdmissionConfigTest.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md", "src/main/resources/application.yml"]
usage:
  claude: { input: 90374, output: 362, cached: 50476, tool_calls: 8, elapsed_s: 294.1 }
  gemini: { input: 56305, output: 1449, cached: 0, tool_calls: 8, elapsed_s: 96.8 }
  codex: { input: 907077, output: 9105, cached: 817024, tool_calls: 30, elapsed_s: 232.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 8, elapsed_s: 134.5 }
synthesizer: claude
synthesized_at: 2026-05-27T16:39:20Z
---

# Cross-AI Review

## Claude Review

Review done. Read plan + all 7 reference files, verified claims against code. Findings below in requested format.

---

# Cross-AI Review — Paralife Phase 20 Plan 20-02

## 1. Summary

The plan is well-constructed and most self-cited concerns hold up. The record shape genuinely mirrors `AdmissionConfig`, the `TestApp` wrapper precedent is real (`AdmissionConfigTest.java:15`/`81-83`), the `resolveEffectiveIdleMs` 4-case truth table is internally consistent, and the three-gate-at-HEAD expectation is sound (the 20-01b stale-golden caveat was fixed before HEAD). However, three issues survived the prior passes: (1) the plan appears unaware that `application.yml` **already contains** a `paralife.runtime:` block, and its yaml snippet would create a duplicate key if followed literally; (2) A1 is cited as living in `20-01-SUMMARY.md` but actually lives in `20-01b-SUMMARY.md`; (3) the idle-timeout fallback has an un-pinned footgun that directly contradicts the plan's own javadoc. None are fatal, but #1 and #3 warrant edits before execution.

## 2. Strengths

- Record/test shape faithfully mirrors `AdmissionConfig` + `AdmissionConfigTest` (verified: `@ConfigurationProperties` prefix, `@ConstructorBinding` compact ctor, per-field `@DefaultValue`, `defaults()` factory, `TestApp` `@EnableConfigurationProperties` wrapper).
- `resolveEffectiveIdleMs` truth table is correct for all four declared cases A–D (traced by hand against the helper logic).
- Pass-3 Concern #22 fix (no bare `record` as `@SpringBootTest(classes=…)`) is correctly applied — the wrapper exists.
- The `@Bean` mutation target (lines 75-82) and current signature match the live file exactly.
- Three-gate green-at-HEAD is achievable: `f6da129` corrected the golden between `c22e487` and HEAD, so the 8/9 baseline caveat does not apply at HEAD.

## 3. Findings

- **[HIGH] `application.yml` already has a `paralife.runtime:` block — the plan's yaml snippet/instructions risk a duplicate-key boot failure.** *Verified against references.* The live file (`application.yml:50-63`) already contains `paralife.runtime.app.*` with a comment at line 48: *"Layer 2 (jetty) is added by Plan 2; this block carries layer 3 (app) only."* The plan (PLAN lines 440-460) shows a standalone `runtime:` → `jetty:` snippet and instructs "insert this `runtime:` block alongside `admission:`" (line 462). Appended literally, that yields **two `runtime:` keys** → SnakeYAML 2.x (Spring Boot 3.4) rejects duplicate mapping keys → boot fails. The correct action is to add `jetty:` as a **sibling of the existing `app:`** under the existing `runtime:`. Aggravating factors: the read_first line reference is stale — it calls `paralife.admission` "at lines 36-57" (PLAN line 366), but admission is actually at `application.yml:64-75`; lines 36-57 are the `websocket`/`runtime` region. The plan never names the existing `runtime.app` block. Only the "Read the file first" instruction (line 464) guards against this, and the dedicated acceptance grep (`grep -A20 "  runtime:" | grep -q "    jetty:"`, line 476) passes *even in the broken duplicate-key state*, so it cannot catch the error. (Downstream `@SpringBootTest`-based gates would eventually fail the build — so it's rework, not silent corruption — hence HIGH not BLOCKER. Would be BLOCKER if the executor follows the snippet verbatim.)

- **[MEDIUM] A1 verification is cited from the wrong summary file.** *Verified against references.* The plan references `20-01-SUMMARY.md` as the A1 source in four places: `<context>` (line 72), Task 2.1 read_first (line 141), the "Reading from Plan 1's summary" branch (line 252), and Task 2.2 (line 434). A1 is **not in `20-01-SUMMARY.md`** — that summary is the async-profiler toolchain bootstrap and contains no setter verification. A1 lives in `20-01b-SUMMARY.md` (frontmatter line 35; body lines 96-97, 122, 138): *"all 8 Jetty 12.0.18 Configurable setters present … no fields need dropping."* The frontmatter dep was correctly fixed to `[20-01b]` (Concern #6), but the body's A1 pointers were not updated to match. The outcome is benign (nothing to drop), but the plan's conditional instruction — "if `20-01-SUMMARY.md` reports A1 found a setter unavailable, drop the field" — directs the executor to read a file that doesn't contain A1, which could prompt a wasted hunt or a wrong "A1 didn't run" conclusion.

- **[MEDIUM] Idle-timeout fallback has an un-pinned footgun that contradicts the plan's own javadoc.** *Verified against references.* The proposed javadoc states: *"If both keys are set, `paralife.runtime.jetty.idle-timeout-ms` wins"* (PLAN lines 396-397). But the proxy logic (`legacyIdleTimeoutMs != 60000L && runtimeConfig.idleTimeoutMs() == 60000L`) means: if an operator **explicitly sets the new key to 60000** *and* sets the legacy key to e.g. 45000, the result is **45000 — the legacy value wins**, not the new key. This is precisely the scenario the review brief flagged, and the plan's 4-case table (lines 499-504) omits it (its case D uses new=30000, never new=60000-with-legacy-set). Task 2.3 acknowledges the *general* primitive-default limitation but never enumerates this specific contradiction, and no test covers it. Minimum fix: correct the javadoc to "new key wins, **except** when the new key equals the default 60000 (indistinguishable from unset), in which case the legacy key wins" and add the 5th case to the table/test. (Non-urgent because the legacy key is transitional per "Phase 999.x", but it's a real doc/behaviour mismatch.)

- **[LOW] `success_criteria` claims "every validation path" but only 4 of 6 are tested.** *Verified.* The record validates 6 fields (input, output, maxFrame, maxBinary, maxText, idle); the test plan covers input/output/maxFrame/idle (Tests 2-5, lines 290-316). `maxBinaryMessageSize < 1024` and `maxTextMessageSize < 1024` rejection paths are uncovered, yet line 634 asserts "covers … every validation path."

- **[LOW] Acceptance grep for the jetty idle-timeout key has a false-positive.** *Verified.* Line 475: `grep -q "paralife.runtime.jetty.idle-timeout-ms" … || grep -q "idle-timeout-ms:"`. The yaml writes the short form `idle-timeout-ms: 60000`, so the first clause fails and the second runs — but `idle-timeout-ms:` **also matches the pre-existing legacy key** `application.yml:42`. The gate passes even if the jetty key was never added. (The `[launch-only]` count gate at line 477 partly compensates.)

- **[LOW] T-20-DOS-1 mitigation framing is muddled — only a lower bound is enforced, no upper cap.** *Verified.* The threat row (line 616) cites "compact-ctor enforces lower bound (≥1024)" as the giant-frame-DoS mitigation, and the field is tagged "Security: T-20-DOS-1 cap" (line 206). A *lower* bound (`maxFrameSize < 1024`, lines 227-230) does nothing against giant-frame memory exhaustion — that needs an *upper* bound. The real mitigation is the preserved 65536 default plus the fact the knob is operator-launch-only (not attacker-controllable). An operator can still set it arbitrarily high, disabling the "security cap." Consider an enforced ceiling or correct the rationale.

- **[NIT] 8th setter `setMaxOutgoingFrames` silently dropped.** *Verified.* A1 found 8 available setters (`20-01b-SUMMARY.md:97`) and explicitly says "Plan 2 may include all 8." The plan wires 7 without acknowledging the omission of `setMaxOutgoingFrames`. Likely a deliberate scope choice — just unstated.

- **[NIT] Internal line-range inconsistency for the untouched region.** *Verified.* Task 2.2 read_first says "line 96-180 must remain untouched" (line 364) while the action says "lines 84-180" (line 436). The deflate region actually spans 84-180 (`deflateEnforcementFilter()` begins at line 84).

- **[NIT] `grep -v '^#'` is a no-op filter for Java.** *Verified.* Line 472 strips lines starting with `#`; Java comments start with `//`/`*`. The criterion still passes (the string is in a live `@Value`), but the "non-comment" intent isn't achieved.

- **[NIT] `@ExtendWith(SpringExtension.class)` is redundant and `webEnvironment` deviates from precedent.** *Verified.* `@SpringBootTest` already meta-includes `SpringExtension` (PLAN line 322 adds it; `AdmissionConfigTest` omits it). The plan also drops `webEnvironment = WebEnvironment.NONE` that the cited precedent uses (`AdmissionConfigTest.java:16`). Harmless with the minimal `TestApp`, but inconsistent with the analog it claims to mirror.

**Suspicion, not confirmed:** Whether `./gradlew test --tests JettyRuntimeConfigTest` (acceptance line 355) actually selects the *static nested* `BindingRoundTripTest` (Test 6). I could not verify Gradle's `--tests` selection of static nested JUnit 5 classes without running it. The `AdmissionConfigTest` precedent (same static-nested `@SpringBootTest` structure) suggests it works, so this is likely fine.

## 4. Suggestions

1. **Rewrite the yaml action** to show merging `jetty:` under the existing `paralife.runtime:` (as a sibling of `app:`), not a fresh `runtime:` block. Fix the stale "admission at lines 36-57" reference (it's 64-75). Add an acceptance gate that fails on a duplicate key, e.g. assert exactly one `^  runtime:$` line: `[ "$(grep -c '^  runtime:' application.yml)" -eq 1 ]`.
2. **Repoint all A1 references** from `20-01-SUMMARY.md` to `20-01b-SUMMARY.md` (lines 72, 141, 252, 434).
3. **Pin the idle-timeout footgun**: correct the javadoc, add the "new=60000 + legacy set" case to the Task 2.3 table, and add a 5th nested test asserting the documented (legacy-wins) behaviour so it can't silently regress.
4. Add the two missing validation-bound tests (maxBinary, maxText) or soften the "every validation path" claim.
5. Tighten the idle-timeout-ms yaml acceptance grep to the dotted `paralife.runtime.jetty.idle-timeout-ms`-scoped form, or grep within the jetty block.
6. Either add an upper bound to `maxFrameSize` or reword the T-20-DOS-1 rationale to "default preserves 65536; launch-only operator knob" rather than implying the lower bound is the DoS control.

## 5. Risk Assessment

**MEDIUM.** The yaml duplicate-`runtime:` hazard (HIGH) is the primary execution risk: the authoritative snippet is wrong and the dedicated acceptance grep can't detect the failure mode — only the "Read first" instruction and downstream `@SpringBootTest` gates stand between it and a boot failure. Everything else is benign-outcome or cosmetic. Fix suggestions 1-3 and execution risk drops to LOW.

---

## Gemini Review

# Cross-AI Review Request — Paralife Phase 20 Plan 20-02

## 1. Summary

The `20-02-PLAN.md` is a detailed and well-structured implementation plan that generally adheres to the established testing and `@ConfigurationProperties` patterns seen in `AdmissionConfig`. However, the review verified several critical discrepancies against the ground-truth references. The plan introduces a blocker by instructing the creation of a duplicate YAML key, it misses a Jetty `Configurable` setter that was discovered during the `20-01b` baseline phase, and it points to the wrong summary file for the A1 verification results.

## 2. Strengths

- **Pattern Adherence:** Correctly mirrors the `AdmissionConfig` pattern, including the use of `@ConfigurationProperties`, compact-constructor validation, and the `defaults()` factory method.
- **Test Context Efficiency:** Identifies the correct way to test `@ConfigurationProperties` using a nested `@Configuration` test app to avoid loading the heavyweight `ParalifeApplication` context.
- **Backwards Compatibility:** Maintains the legacy `paralife.websocket.idle-timeout-ms` property and establishes a testable fallback mechanism to handle the transition safely.

## 3. Findings

- **`BLOCKER` — Duplicate YAML Key Insertion (Task 2.2):** Verified against `application.yml`. The plan instructs the executor to insert a new `runtime:` block alongside the `admission:` block. However, `application.yml` already contains a `runtime:` key (which holds the `app:` configurations). Inserting another `runtime:` block will result in duplicate keys, which will either break YAML parsing or cause Spring Boot to drop the existing `runtime.app` bindings.
- **`HIGH` — Missing Jetty Setter and Incorrect Summary Reference (Task 2.1 / 2.2):** Verified against `20-01-SUMMARY.md` and `20-01b-SUMMARY.md`. The plan instructs reading `20-01-SUMMARY.md` for A1 verification results and hardcodes 7 setters. The A1 verification actually lives in `20-01b-SUMMARY.md`. Furthermore, `20-01b-SUMMARY.md` explicitly states that there are **8** setters available on Jetty 12.0.18, including `setMaxOutgoingFrames`. The plan completely omits this 8th setter from the record, the yaml, and the customizer wiring.
- **`MEDIUM` — Operator Footgun in Legacy Fallback Logic (Task 2.3):** Verified against the proposed code and truth table. The `resolveEffectiveIdleMs` fallback proxy assumes that if `runtimeConfig == 60000L`, the new key was unset. If an operator explicitly configures the new key to exactly `60000` but has a stale legacy key set to a different value (e.g. `45000`), the legacy key will incorrectly win. This violates the plan's claim for Case D ("new key wins when both set"). Even if a full `Binder` migration is out of scope (per Concern #4), this specific failure mode should be accurately documented so operators are aware.
- **`LOW` — Line Number Mismatch (Task 2.2):** Verified against `JettyDeflateCustomizer.java`. The plan claims lines 75-82 hold the `@Bean` to mutate and lines 84-180 hold the deflate filter. The file only has 154 lines. The `@Bean` is actually at lines 73-80, and the deflate filter spans lines 82-154. 

## 4. Suggestions

- **Fix YAML Insertion:** Update Task 2.2's `application.yml` instructions to append the `jetty:` block *nested under* the existing `runtime:` key, rather than creating a new `runtime:` sibling.
- **Include All 8 Setters:** Add `maxOutgoingFrames` to the `JettyRuntimeConfig` record, `application.yml`, and `addWebSocketConfigurer` chaining in `JettyDeflateCustomizer` to reflect the full 8 setters verified by A1.
- **Fix File Reference:** Correct the `read_first` reference in Task 2.1 to point to `20-01b-SUMMARY.md` so the executor reads the actual A1 verification results.
- **Acknowledge Case D Limitation:** Update the Case D explanation in Task 2.3 to explicitly document the footgun where an explicitly set new key of 60000 will be overwritten by a legacy override. 

## 5. Risk Assessment

**HIGH** — The duplicate YAML key is guaranteed to cause configuration loading issues or drop existing runtime settings, and the omitted setter means the implementation will be incomplete compared to the A1 verification. Both the YAML conflict and the missing setter must be corrected before execution.

---

## Codex Review

## Summary
The plan is close, but I found several execution risks at MEDIUM or above. The biggest are stale assumptions against the current `application.yml`, the wrong A1 summary source, an unhandled idle-timeout precedence footgun, and acceptance/test gaps that could let the plan “pass” without proving its key claims.

## Strengths
- Verified: `JettyDeflateCustomizer.java` currently has the target `@Bean` at lines 75-82, and the deflate filter starts at line 84 and should remain untouched.
- Verified: `AdmissionConfig` uses the claimed `@ConfigurationProperties` record pattern with `@ConstructorBinding`, `@DefaultValue`, compact validation, and `defaults()`.
- Verified: `AdmissionConfigTest` has the `TestApp` wrapper precedent using `@EnableConfigurationProperties`.
- The plan correctly preserves the legacy idle timeout path for default and legacy-only cases.

## Findings
- **HIGH — YAML insertion is stale and risks duplicate `paralife.runtime` keys. Verified against reference files.**  
  `application.yml` already has `paralife.runtime.app` at lines 48-63, but the plan says to add a new `runtime:` block after/alongside `admission` (`20-02-PLAN.md` lines 438-464). Executing that literally can create a duplicate `paralife.runtime` key, which may break YAML parsing or shadow the existing app runtime config. The plan should instead merge `jetty:` under the existing `paralife.runtime` block as a sibling of `app`.

- **HIGH — A1 is read from the wrong summary, and the actual A1 result found 8 setters, not 7. Verified.**  
  The plan depends on `20-01b` but repeatedly tells the executor to read `20-01-SUMMARY.md` for A1 (`20-02-PLAN.md` lines 141, 252, 434). `20-01-SUMMARY.md` does not contain A1; `20-01b-SUMMARY.md` lines 35 and 97 say A1 verified all 8 setters, including `setMaxOutgoingFrames`. I also verified `Configurable` in the local Jetty 12.0.18 jar exposes `setMaxOutgoingFrames(int)`. The plan either needs to include that eighth field or explicitly explain why D-07 intentionally excludes it despite A1.

- **HIGH — “new key wins if both keys are set” is false when the new key is explicitly set to `60000`. Verified by plan logic.**  
  `resolveEffectiveIdleMs` falls back to legacy whenever `legacyIdleTimeoutMs != 60000L && runtimeConfig.idleTimeoutMs() == 60000L` (`20-02-PLAN.md` lines 424-430). That means `paralife.runtime.jetty.idle-timeout-ms=60000` plus legacy `45000` resolves to `45000`, contradicting the javadoc claim at lines 395-397. The four-case table does not pin this operator-visible case.

- **MEDIUM — The “Spring slice test” for idle fallback is not actually a Spring/property-binding test. Verified.**  
  Task 2.3 says to test yaml-set combinations via Spring slice (`20-02-PLAN.md` lines 487-495), but the implementation directly constructs `JettyRuntimeConfig` and passes primitive legacy values to a helper (`20-02-PLAN.md` lines 531-568). That does not verify `@Value`, `@DefaultValue`, property precedence, or explicit-vs-default binding behavior.

- **MEDIUM — Binding round-trip test may be skipped by the stated verification. Verified from JUnit engine behavior/source and plan shape.**  
  The proposed `BindingRoundTripTest` is a static nested class without `@Nested` (`20-02-PLAN.md` lines 322-338). JUnit Jupiter’s class selector only discovers nested classes annotated with `@Nested` when selecting the outer class, so `./gradlew test --tests JettyRuntimeConfigTest` may run only the outer tests and miss the binding test. Use a top-level `JettyRuntimeConfigBindingTest`, or make the binding test a normal top-level test method using `ApplicationContextRunner`.

- **MEDIUM — T-20-DOS-1 says “cap,” but validation only enforces lower bounds. Verified.**  
  The record rejects small `maxFrameSize`, `maxBinaryMessageSize`, and `maxTextMessageSize` values (`20-02-PLAN.md` lines 227-237), but has no upper bound. The threat model claims mitigation for memory exhaustion via a cap (`20-02-PLAN.md` lines 616-617). Defaults are capped, but operator overrides are unbounded.

- **MEDIUM — Validation test coverage does not match “every validation path.” Verified.**  
  The plan validates `maxBinaryMessageSize` and `maxTextMessageSize` in code (`20-02-PLAN.md` lines 231-237), but the test list only covers input buffer, output buffer, idle timeout, and max frame (`20-02-PLAN.md` lines 147-151, 290-316). Success criteria claim every validation path is covered at line 634.

- **LOW — Some acceptance gates can pass without proving the intended YAML/code state. Verified.**  
  The yaml gate `grep -q "paralife.runtime.jetty.idle-timeout-ms" ... || grep -q "idle-timeout-ms:" ...` (`20-02-PLAN.md` line 475) can pass today because legacy `paralife.websocket.idle-timeout-ms` already exists in `application.yml` line 42. The plan also does not gate against duplicate `runtime:` keys.

## Suggestions
- Change all A1 references from `20-01-SUMMARY.md` to `20-01b-SUMMARY.md`.
- Decide explicitly whether to add `maxOutgoingFrames`; if excluding it, document why despite A1.
- Merge yaml under the existing block:

```yaml
paralife:
  runtime:
    app:
      ...
    jetty:
      ...
```

- Add an explicit test for `new=60000, legacy=45000`; either document legacy wins for that edge case or switch to a nullable/Binder-based implementation that detects whether the new key was explicitly set.
- Move the binding round-trip into a top-level test class or `ApplicationContextRunner`.
- Add upper bounds for frame/message sizes, or stop calling the validation a DoS cap.
- Add tests for `maxBinaryMessageSize` and `maxTextMessageSize`.

## Risk Assessment
**HIGH** — execution can mutate YAML incorrectly and ship precedence/security/test gaps while still satisfying several current grep gates.

---

## Opencode Review

# Review: Paralife Phase 20 Plan 20-02

## Summary

A solid plan that has clearly benefited from three prior review passes. The record design faithfully mirrors `AdmissionConfig`, the wiring path is correct, and the fallback logic's 4-case truth table is internally consistent. Two issues need attention: the `<read_first>` block incorrectly points to a file that doesn't contain the A1 verification data it claims, and the plan omits the 8th Jetty `Configurable` setter (`setMaxOutgoingFrames`) that A1 confirmed available, without documenting why.

## Strengths

- Record shape, `TestApp` wrapper, `defaults()` factory, and compact-ctor validation all faithfully match the `AdmissionConfig` precedent (`AdmissionConfig.java:22-53`, `AdmissionConfigTest.java:81-83`)
- `JettyDeflateCustomizer.java` line ranges checked and confirmed: the current `@Bean` is at 75-82, the deflate filter spans 84-180, both match the plan's claims exactly
- The `resolveEffectiveIdleMs` 4-case logic is internally consistent — all documented cases produce correct results
- Threat model, three-gate stack verification, and "zero behavioural change at boot" goal are well-specified
- Javadoc thoroughly attributes decisions (D-07, D-09, Pass-2 Concern #16) to prevent future maintainers from "fixing" idleTimeoutMs=60000 to 30000

## Findings

1. **MEDIUM — Task 2.1 `<read_first>` directs to wrong file for A1 verification** (verified against reference files)
   - Plan line 141 says: `20-01-SUMMARY.md (A1 verification — IF a setter was reported unavailable on Jetty 12.0.18, drop that field from the record)`
   - `20-01-SUMMARY.md` contains **zero** A1 data — it's the toolchain bootstrap doc (async-profiler install + profiles/README). No setter availability check anywhere in its 108 lines.
   - A1 actually lives in `20-01b-SUMMARY.md` line 97: "A1 VERIFIED: all 8 Jetty 12.0.18 Configurable setters present … Plan 2 may include all 8 in JettyRuntimeConfig"
   - The plan's `<context>` block (lines 66-77) also lists `20-01-SUMMARY.md` but NOT `20-01b-SUMMARY.md`. The `depends_on: [20-01b]` means the executor should have already read 20-01b, but the explicit `<read_first>` instruction is actively misleading.
   - **Fix:** change line 141 to reference `20-01b-SUMMARY.md`.

2. **MEDIUM — Plan omits `setMaxOutgoingFrames` without justification** (verified against reference files)
   - A1 (`20-01b-SUMMARY.md:97`) confirmed all **8** setters: `setIdleTimeout`, `setInputBufferSize`, `setOutputBufferSize`, `setMaxBinaryMessageSize`, `setMaxTextMessageSize`, `setMaxFrameSize`, `setAutoFragment`, **`setMaxOutgoingFrames`**
   - The plan claims 7 setters throughout — the record has 7 fields, the wiring lambda has 7 calls, the acceptance grep counts ≥7. `setMaxOutgoingFrames` is absent.
   - The plan's truth statement (line 19) says "minus any setter Plan 1's A1 verification reported as unavailable" — but A1 declared *all 8 available*, so this branch doesn't apply.
   - No comment or javadoc explains the exclusion. A future reader comparing A1 against the record will wonder whether this was deliberate or an oversight.
   - **Fix:** either include the 8th field (with `@DefaultValue` reflecting Jetty's default) or add a javadoc note explicitly stating `setMaxOutgoingFrames` was intentionally excluded and why.

3. **MEDIUM — Operator footgun: new key explicitly set to 60000 + legacy key overridden → legacy honored silently** (verified via logic analysis)
   - If an operator sets `paralife.runtime.jetty.idle-timeout-ms: 60000` (explicitly) AND `paralife.websocket.idle-timeout-ms: 45000`, the `resolveEffectiveIdleMs` logic returns **45000** (honors legacy). The operator expected the new key to win (per Case D "new key wins when both set").
   - This happens because `runtimeConfig.idleTimeoutMs() == 60000L` is used as a proxy for "new key unset," and 60000 is indistinguishable from the default.
   - The plan acknowledges the primitive-default detection limitation but doesn't test this specific variant. The existing Case D test uses `new=30000, legacy=45000`, which masks the issue.
   - **Fix:** add a 5th test case or an explicit note in the fallback test's javadoc documenting this footgun: "If both keys are set and the new key is explicitly 60000 (same as default), the legacy key will be honored because the default-detection proxy cannot distinguish explicit-60000 from unset."

4. **LOW — YAML block insertion instruction wording is ambiguous** (verified against reference files)
   - Plan line 462 says "Insert this `runtime:` block alongside `admission:`" but `application.yml` lines 50-63 already have a `paralife.runtime.app:` block. The actual operation is adding `jetty:` as a sibling under the *existing* `runtime:`.
   - The acceptance grep (`grep -A20 "  runtime:" ... | grep -q "    jetty:"`) is correct, so the executor will figure it out. But the prose could mislead someone doing a quick edit without `read_first`.

5. **LOW — `AdmissionConfigTest` uses `webEnvironment = NONE`; plan doesn't** (verified against reference files)
   - `AdmissionConfigTest.java:16` explicitly sets `webEnvironment = SpringBootTest.WebEnvironment.NONE` for the binding round-trip test. The plan's `BindingRoundTripTest` doesn't include this. Without it, Spring may create an embedded server (random port) unnecessarily, adding ~1-2s to test startup.
   - Not a correctness bug, but for consistency with the precedent the plan claims to mirror: add `webEnvironment = SpringBootTest.WebEnvironment.NONE` or note the deviation.

6. **NIT — `@ExtendWith(SpringExtension.class)` is redundant on `BindingRoundTripTest`** (verified against reference files)
   - `@SpringBootTest` is already meta-annotated with `@ExtendWith(SpringExtension.class)`. `AdmissionConfigTest.BindsAllKeys` (line 15-27) doesn't include the redundant annotation.
   - Harmless, but inconsistent with the precedent.

## Suggestions

1. Fix the `<read_first>` A1 source reference (point to `20-01b-SUMMARY.md`, not `20-01-SUMMARY.md`)
2. Either add `maxOutgoingFrames` as an 8th field with a Jetty-default `@DefaultValue`, or add an explicit javadoc comment explaining its deliberate exclusion
3. Add a test case or javadoc note for the `new=60000+legacy≠60000` footgun
4. Add `webEnvironment = SpringBootTest.WebEnvironment.NONE` to `BindingRoundTripTest` for consistency with `AdmissionConfigTest`
5. Drop the redundant `@ExtendWith(SpringExtension.class)` from `BindingRoundTripTest`

## Risk Assessment

**MEDIUM** — The plan is structurally sound but has one confirmed misdirection (wrong A1 source file) and an unexplained exclusion of an available Jetty setter. Neither is a plan-breaker, but both should be resolved before execution to avoid executor confusion.

---

## Consensus Summary

### Agreed Strengths
- Record faithfully mirrors `AdmissionConfig` pattern — `@ConfigurationProperties` prefix, `@ConstructorBinding` compact-ctor validation, per-field `@DefaultValue`, `defaults()` factory (all 4 reviewers, verified against `AdmissionConfig.java`).
- Test context uses the `TestApp` / nested `@Configuration` + `@EnableConfigurationProperties` wrapper precedent instead of loading full `ParalifeApplication` (claude, gemini, codex, opencode).
- `resolveEffectiveIdleMs` 4-case truth table is internally consistent for the declared cases A–D (claude, opencode; codex partial).
- Legacy `paralife.websocket.idle-timeout-ms` path preserved for safe transition (gemini, codex).
- `JettyDeflateCustomizer` `@Bean` mutation target and untouched deflate region line ranges verified (claude, codex, opencode — note line-range disagreement below).

### Agreed Concerns
- **[HIGH consensus — severity LOW→BLOCKER] Duplicate `paralife.runtime:` YAML key.** `application.yml` already has a `runtime:` block holding `app:`. The plan's snippet/instruction ("insert `runtime:` block alongside `admission:`") followed verbatim creates a second `runtime:` key → SnakeYAML 2.x rejects / Spring drops existing `runtime.app` bindings. All 4 flagged. Fix: nest `jetty:` as a sibling of `app:` under the existing `runtime:`. Note: the dedicated acceptance grep passes even in the broken duplicate-key state, so it cannot catch this. (gemini BLOCKER, claude/codex HIGH, opencode LOW — see divergence.)
- **[MEDIUM–HIGH] A1 verification cited from wrong summary file.** Plan points the executor to `20-01-SUMMARY.md` (lines 72/141/252/434) for A1 setter results; that file is the async-profiler bootstrap and contains no A1 data. A1 lives in `20-01b-SUMMARY.md` (line 97). All 4 flagged. Fix: repoint all A1 references to `20-01b-SUMMARY.md`.
- **[MEDIUM–HIGH] Idle-timeout fallback footgun contradicts its own javadoc.** Proxy logic `legacy != 60000 && runtime == 60000` means an operator who explicitly sets the new key to `60000` *and* legacy to `45000` gets `45000` — legacy wins, contradicting the "new key wins when both set" javadoc/Case D. The 4-case table omits this case and no test covers it. All 4 flagged. Fix: correct javadoc, add 5th case to table + test, or use nullable/`Binder` explicit-set detection.
- **[MEDIUM, divergent severity] 8th Jetty setter `setMaxOutgoingFrames` omitted.** A1 confirmed all 8 setters available; plan wires only 7 with no stated justification. gemini/codex HIGH (must include), opencode MEDIUM (include or document), claude NIT (likely deliberate scope). Fix: add the field with Jetty-default `@DefaultValue`, or add javadoc explaining the deliberate exclusion.
- **[LOW–MEDIUM] T-20-DOS-1 framing wrong — only lower bounds enforced, no upper cap.** `maxFrameSize`/`maxBinary`/`maxText` reject values `<1024` but have no ceiling; an operator can set arbitrarily high, so the "DoS cap" mitigation rationale is misleading. The real protection is the preserved default + launch-only knob. (claude LOW, codex MEDIUM.) Fix: add upper bound or reword rationale.
- **[LOW–MEDIUM] "every validation path" claim overstated.** Record validates 6 fields; tests cover only 4 (input/output/maxFrame/idle). `maxBinaryMessageSize`/`maxTextMessageSize` rejection paths untested while success_criteria claims full coverage. (claude LOW, codex MEDIUM.) Fix: add the two tests or soften the claim.
- **[LOW] Acceptance grep false-positives.** `idle-timeout-ms:` short-form grep also matches the pre-existing legacy key (`application.yml:42`), so the gate passes even if the jetty key was never added. (claude, codex.) Fix: scope grep to dotted `paralife.runtime.jetty.idle-timeout-ms` or grep within the jetty block.
- **[NIT] Test annotations deviate from cited precedent.** `@ExtendWith(SpringExtension.class)` is redundant under `@SpringBootTest`, and the plan drops `webEnvironment = NONE` that `AdmissionConfigTest` uses (adds ~1–2s startup). (claude, opencode.)

### Divergent Views
- **YAML severity:** gemini calls the duplicate key a guaranteed BLOCKER; opencode rates it LOW, reasoning the acceptance grep is correct so the executor will infer the right nesting. claude/codex land HIGH — wrong if followed verbatim, but downstream `@SpringBootTest` gates would eventually fail (rework, not silent corruption). Worth resolving the snippet before execution regardless.
- **8th setter:** is the omission deliberate scope or an oversight? gemini/codex treat as incomplete-vs-A1 (HIGH); claude reads it as a likely intentional scope choice (NIT). Plan should state intent either way.
- **Binding round-trip test discoverability (codex MEDIUM, claude unconfirmed suspicion; gemini/opencode silent):** codex argues `BindingRoundTripTest` as a static nested class *without* `@Nested` may not be discovered by `./gradlew test --tests JettyRuntimeConfigTest`, silently skipping the binding proof. claude flagged the same uncertainty but couldn't verify; the `AdmissionConfigTest` precedent (same structure) suggests it works. Worth a one-command check, or move to a top-level test / `ApplicationContextRunner`.
- **Idle fallback test fidelity (codex only):** codex notes the "Spring slice test" actually constructs `JettyRuntimeConfig` directly and passes primitives to the helper, so it never exercises `@Value`/`@DefaultValue`/property precedence. Other reviewers did not raise this — investigate whether the test proves what it claims.
