---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T07:17:37Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-02-PLAN.md", "src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java", "src/main/java/com/paralife/admission/AdmissionConfig.java", "src/test/java/com/paralife/admission/AdmissionConfigTest.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md", "src/main/resources/application.yml"]
usage:
  claude: { input: 90604, output: 32, cached: 308912, tool_calls: 8, elapsed_s: 217.9 }
  gemini: { input: 530586, output: 1387, cached: 162223, tool_calls: 12, elapsed_s: 485.6 }
  codex: { input: 543696, output: 4560, cached: 456704, tool_calls: 22, elapsed_s: 106.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 8, elapsed_s: 133.9 }
synthesizer: claude
synthesized_at: 2026-06-03T07:17:37Z
---

# Cross-AI Review

## Claude Review

Read all 8 files. Verified plan claims against references. Done.

# Cross-AI Review — Paralife Phase 20 Plan 20-02 (Round 2)

## 1. Summary

Round-1 fixes are **all correctly applied and internally consistent**. The 8th-setter wiring is aligned end-to-end (record = 8 `@DefaultValue` fields, lambda = 8 `c.set*` calls, yaml = 8 keys incl. `max-outgoing-frames: -1`, both `awk '$1 >= 8'` greps pass). The idle-timeout reconciliation is now consistent across all three surfaces: the `@Bean` javadoc ("new wins **except** when new==default 60000"), the 5-case truth table, and the `CaseE_bothSetNewAtDefault` test (asserts `45000L`) — and I hand-traced `resolveEffectiveIdleMs` against all five cases A–E; every one matches its documented expectation. The duplicate-`runtime:` hazard is genuinely closed: yaml nests `jetty:` under the existing `runtime:` as a sibling of `app:`, and the `[ grep -cE '^  runtime:' -eq 1 ]` guard catches even a verbatim-paste of the illustrative snippet (a second top-level `paralife:`/`runtime:` would push the count to 2). A1 references all point at `20-01b-SUMMARY.md` (confirmed: A1 lives there at lines 35/97; `20-01-SUMMARY.md` is the async-profiler bootstrap with no A1 data). **I found nothing at or above HIGH.** Three LOW items and one MEDIUM suspicion below — none block execution.

## 2. Regression check (round-1 fixes)

| Round-1 finding | Verdict | Evidence |
|---|---|---|
| Duplicate `paralife.runtime:` YAML key | **applied-and-correct** | Plan 501-525 nests `jetty:` under existing `runtime:`; guard line 536 `^  runtime:` count==1; `application.yml:50` is the sole `  runtime:` |
| A1 cited from wrong summary | **applied-and-correct** | Repointed at lines 72 (context), 141 (read_first), 270, 493 → `20-01b-SUMMARY.md`; verified A1 present there (35/97), absent in `20-01-SUMMARY.md` |
| 8th setter `setMaxOutgoingFrames` omitted | **applied-and-correct** | Record field 228 (`@DefaultValue("-1") int`), lambda 473, yaml 520, carve-out validation 256-260; all four 8-counts consistent |
| Idle-timeout footgun / javadoc contradiction | **applied-and-correct** | Javadoc 446-456 ("except…default 60000"); truth-table case E line 567 (→45000); test 637-648 asserts `45000L`; helper logic traced A–E all correct |
| T-20-DOS-1 framing | **applied-and-correct** | Threat row 695 reframes DoS control as preserved-65536-default + launch-only; field javadoc 210 matches; lower bound now framed as misconfig floor |
| "every validation path" overstated | **applied-and-correct** | Added `rejectsMaxBinaryTooSmall`/`rejectsMaxTextTooSmall`/`rejectsMaxOutgoingFramesZero`/`acceptsMaxOutgoingFramesUnlimitedAndPositive`; all 7 validation branches now covered (autoFragment has none) |
| Acceptance grep false-positive | **applied-and-correct** | Replaced with `max-outgoing-frames: -1` token (line 537, unique to new block) + `^  runtime:` count gate (536) |
| Task 2.3 mislabeled "Spring slice test" | **applied-and-correct** | read_first 553, behavior 557, javadoc 591 all say "pure unit test of the static helper — no Spring context" |

## 3. New findings

- **[MEDIUM — suspicion, NOT verifiable against the manifest] Default *values* (4096/4096/65536/65536/65536/-1) are asserted to be Jetty 12.0.18 defaults, but A1 only verified setter *existence*, not default values.** A1 (`20-01b-SUMMARY.md:97`) is `javap -public` evidence that the 8 setters are present — it says nothing about their default values. The "zero behavioural change vs c22e487" truth depends on each explicitly-set value equalling Jetty's own runtime default; at baseline the `@Bean` set *only* `idleTimeout`, so Jetty's internal defaults governed buffers/frames. If any default differs (e.g. Jetty's real `outputBufferSize` default ≠ 4096), explicitly setting 4096 changes behaviour — and **no gate catches it**: the three-gate stack is golden-trace *simulation* equivalence, which never exercises WS buffer sizing. From general Jetty 12 knowledge these values look right (inputBuffer 4096, maxFrame/maxMessage 65536, autoFragment true, maxOutgoingFrames -1, idle 30000), so I rate this MEDIUM not HIGH — but the Jetty jar is not in the manifest, so I could not confirm. Cheap mitigation: a one-line note that the *defaults* (not just setters) were eyeballed, or a boot-time assert. Under the MVP lens, acceptable to proceed.

- **[LOW — verified] Stale "six tests" prose in Task 2.1.** Plan line 274: "Cover the six tests listed in `<behavior>` above… for Test 6 binding round-trip." After the round-1 additions, `<behavior>` lists nine items and the binding round-trip is Test 9 (Test 6 is now `rejectsMaxTextTooSmall`). The embedded code template (276-390) is complete and authoritative, and the executor copies it, so no functional impact — but the count/number references are leftover from before the fix. Trivial doc-sync.

- **[LOW — verified] "Replace the existing @Bean method (lines 75-82)" leaves the old javadoc orphaned.** In the live file the `@Bean` is lines 75-82 but its javadoc is lines 61-74. The plan supplies a *new* javadoc (430-456) and instructs replacing "lines 75-82" — followed literally, the old javadoc (61-74, "raised from Jetty's 30s default to 60s… defensive belt") survives as a second, now-stale block stacked above the new one. It compiles (a floating doc comment is legal) and every gate still passes (`grep -q "project-current defaults"` matches the new block regardless), so this only risks a stale duplicate javadoc. A careful executor reading the full file (read_first mandates it) subsumes 61-82. Suggest the instruction say "replace lines 61-82 (javadoc + method)".

- **[LOW — suspicion] `key_links` frontmatter pattern won't match the real structure.** Line 43: `pattern: "paralife:\\n\\s+runtime:\\n\\s+jetty:"` expects `paralife:` immediately followed by `runtime:`. In `application.yml`, `paralife:` (20) is followed by `world:`/`tick:`/`websocket:` before `runtime:` (50). This is frontmatter metadata, not an `<acceptance_criteria>` gate, so it cannot wedge execution — but a verifier consuming key_links as a literal regex would get a false-negative. Pre-existing, not introduced by the round-1 fixes. I couldn't confirm how key_links patterns are consumed downstream, hence suspicion.

## 4. Risk Assessment

**LOW.** The round-1 HIGH (duplicate `runtime:`) and all MED/LOW findings are correctly fixed with no regressions, and the 8th-setter + idle-timeout reconciliations are consistent end-to-end across record/yaml/lambda/grep/javadoc/table/test. Remaining items are one unverifiable-but-plausible default-values suspicion (MEDIUM) and three cosmetic LOWs; none breaks execution or wedges a gate. Plan is ready to execute.

---

## Gemini Review

# Cross-AI Review — Paralife Phase 20 Plan 20-02 (Round 2)

## 1. Summary

The Round-1 fixes have been successfully and comprehensively applied without introducing new regressions. I verified the revised plan against the provided ground-truth references (`application.yml` structure, `JettyDeflateCustomizer.java` line ranges, `20-01-SUMMARY.md` vs `20-01b-SUMMARY.md`, and the `AdmissionConfig` pattern). The critical duplicate YAML key risk is fully mitigated with clear insertion instructions and a correct acceptance gate. The A1 source mismatch has been corrected. The 8th Jetty setter (`setMaxOutgoingFrames`) is fully integrated into the record, tests, and configuration. The fallback footgun logic is pinned with a dedicated unit test and documented accurately. The plan is structurally sound and ready for execution.

## 2. Regression check

- **Duplicate `paralife.runtime:` YAML key:** applied-and-correct
- **A1 cited from wrong summary:** applied-and-correct
- **8th Jetty setter `setMaxOutgoingFrames` omitted:** applied-and-correct
- **Idle-timeout footgun:** applied-and-correct
- **T-20-DOS-1 framing muddled:** applied-and-correct
- **"every validation path" overstated:** applied-and-correct
- **Acceptance grep false-positive:** applied-and-correct
- **Task 2.3 mis-described as "Spring slice test":** applied-and-correct

## 3. New findings

After genuinely checking the references (`application.yml`'s current structure, `JettyDeflateCustomizer.java`'s exact line ranges and `@Bean` definitions, the actual contents of `20-01-SUMMARY.md` vs `20-01b-SUMMARY.md`, and the `AdmissionConfig` pattern), I found **no new issues at or above HIGH severity**.

- The `application.yml` snippet and instructions correctly nest `jetty:` as a sibling of `app:` without risking the duplication of the `runtime:` key. The acceptance gate `[ "$(grep -cE '^  runtime:' src/main/resources/application.yml)" -eq 1 ]` validates this perfectly.
- `JettyDeflateCustomizer.java` line ranges (75-82 for the mutating `@Bean` and 84-180 for the untouched deflate filter) are completely accurate against the live file, proving the Round 1 'LOW' mismatch finding from Gemini was an artifact of reviewing an incorrect or older state.
- The A1 verification correctly points to and relies on `20-01b-SUMMARY.md`.
- `AdmissionConfigTest`'s `TestApp` wrapper structure is flawlessly mirrored, circumventing context-load failures.
- The static nested helper test `JettyIdleTimeoutFallbackTest` successfully exercises all five precedence logic paths independently of a Spring context.

The plan is robust, avoids the previously identified hazards, and accurately fulfills its behavioral contracts.

## 4. Risk Assessment

**LOW.** The plan is sound. The known failure modes from Round 1 have been systematically patched and validated against the actual project structure.

---

## Codex Review

**Summary** — Round 1 fixes appear correctly applied. I verified the target plan against the live references: `JettyDeflateCustomizer` has the mutable `@Bean` at lines 75-82 and the filter region at 84-180; `20-01b-SUMMARY.md` contains A1’s all-8-setter result while `20-01-SUMMARY.md` is only the async-profiler bootstrap; `AdmissionConfig` / `AdmissionConfigTest.BindsAllKeys` provide the claimed record and binding-test precedent; `application.yml` already has one `paralife.runtime:` block at line 50. I found no new `BLOCKER` or `HIGH` issue.

**Regression Check**

| Round-1 fix | Status |
|---|---|
| Duplicate `paralife.runtime:` YAML key | applied-and-correct |
| A1 wrong summary file | applied-and-correct |
| Missing `setMaxOutgoingFrames` | applied-and-correct |
| Idle-timeout explicit-60000 footgun | applied-and-correct |
| T-20-DOS-1 lower-bound framing | applied-and-correct |
| “Every validation path” overstated | applied-and-correct |
| YAML acceptance grep false-positive | applied-and-correct |
| Task 2.3 “Spring slice” mislabel | applied-and-correct |

**New Findings**

- No new `BLOCKER` or `HIGH` findings verified against the reference files.

- `LOW` — verified against the plan only: Task 2.1 still has stale prose at [20-02-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-02-PLAN.md:274), saying “Cover the six tests” and “Tests 2-5,” while the behavior list and code now define 9 behavior tests. This is not an execution risk under the MVP lens because the concrete test body and acceptance criteria are correct.

**Risk Assessment** — `LOW`. The material round-1 execution hazards are fixed, and the remaining issue is minor stale prose.

---

## Opencode Review

# Cross-AI Review — Paralife Phase 20 Plan 20-02 (ROUND 2)

## 1. Summary

All eight round-1 fixes are correctly applied — no regressions. The plan is internally consistent end-to-end: 8 fields in record → 8 setters in wiring lamgmbda → 8 fields in yaml → ≥8 acceptance grep → 8 `[launch-only]` tags. A1 correctly points to `20-01b-SUMMARY.md`, the yaml correctly nests `jetty:` as sibling of `app:` under the single existing `runtime:`, and the idle-timeout truth table (now 5 cases A–E) correctly pins the documented footgun. No new HIGH+ issues found. The plan is ready to execute.

## 2. Regression check

| Round-1 finding | Status |
|---|---|
| HIGH: Duplicate `paralife.runtime:` YAML key | **Applied-and-correct** — CRITICAL warning present, acceptance has exact `runtime:` count gate, yaml snippet correctly nests `jetty:` under existing `runtime:` |
| MED: A1 cited from wrong summary | **Applied-and-correct** — all A1 references point to `20-01b-SUMMARY.md`; context block includes only that file; `read_first` has explicit NOTE |
| MED: 8th Jetty setter `setMaxOutgoingFrames` omitted | **Applied-and-correct** — 8 fields in record, 8 calls in wiring lambda, 8 yaml lines, acceptance grep counts ≥8 |
| MED: Idle-timeout footgun | **Applied-and-correct** — javadoc says "except when new == default 60000", truth table has Case E asserting 45000L, test `CaseE_bothSetNewAtDefault` exists |
| LOW-MED: T-20-DOS-1 framing muddled | **Applied-and-correct** — threat row reframed to "preserved 65536 default cap" + "launch-only operator knob", field javadoc consistent |
| LOW: "every validation path" overstated | **Applied-and-correct** — 9 behavior tests (1 default + 6 field-validation + 1 carve-out + 1 binding round-trip), acceptance says "All 9" |
| LOW: Acceptance grep false-positive | **Applied-and-correct** — legacy `idle-timeout-ms` grep replaced with `max-outgoing-frames: -1` (unique token) + `runtime:` count gate |
| LOW: Task 2.3 mis-described as "Spring slice test" | **Applied-and-correct** — relabeled as "pure unit test of the static helper — no Spring context" throughout |

## 3. New findings

No findings at-or-above HIGH. All round-1 fixes verified; all specific verification requests confirmed:

- **`JettyDeflateCustomizer.java` line ranges verified:** `@Bean` at 75–82, deflate filter at 84–180. Matches plan claims exactly.
- **A1 source verified:** `20-01b-SUMMARY.md:97` lists all 8 setters. `20-01-SUMMARY.md` contains zero A1 data (toolchain bootstrap only). Plan correctly references `20-01b` throughout.
- **`AdmissionConfig` precedent verified:** `@ConfigurationProperties` record with `@ConstructorBinding`, `@DefaultValue`, compact-ctor validation, `defaults()` factory — plan mirrors it faithfully.
- **`AdmissionConfigTest.BindsAllKeys` precedent verified:** `TestApp` `@Configuration` wrapper pattern at lines 81–83, `@SpringBootTest(classes = TestApp.class)` at line 27 — plan mirrors it.
- **`application.yml` existing `runtime:` block verified:** Lines 48–63 hold `runtime:` → `app:`. Plan's yaml action (lines 501–523) correctly nests `jetty:` as sibling. Duplicate-key guard `[ "$(grep -cE '^  runtime:' ...)" -eq 1 ]` present at line 536.
- **Idle-timeout truth table verified:** 5 cases (A–E) at lines 561–567; Case E asserts legacy-wins 45000L. Test `CaseE_bothSetNewAtDefault` at lines 637–648. Javadoc at lines 448–455 says "new key wins...**except** when the new key is left at (or explicitly set to) its default `60000`."

One **LOW** observation — not a plan defect, but worth noting:

- **LOW** — The `BindingRoundTripTest` (line 378) omits `webEnvironment = SpringBootTest.WebEnvironment.NONE` that its precedent `AdmissionConfigTest.BindsAllKeys` uses. Spring Boot 3.x `@SpringBootTest` defaults to `MOCK` when running without a web container on classpath — both `MOCK` and `NONE` work for a binding-only test, so this is harmless. Already in the known-deferred list.

## 4. Risk Assessment

**LOW** — All round-1 fixes applied correctly, no regressions, no new HIGH+ findings. Plan is internally consistent and ready to execute.

---

## Consensus Summary

### Agreed Strengths
- All 8 round-1 fixes applied correctly with no regressions — verified against live references by all 4 reviewers (`JettyDeflateCustomizer` line ranges, `20-01b-SUMMARY.md`, `AdmissionConfig` precedent, `application.yml` structure).
- 8th setter (`setMaxOutgoingFrames`) wired consistently end-to-end: 8 record fields → 8 lambda `c.set*` calls → 8 yaml keys → ≥8 acceptance grep → 8 `[launch-only]` tags (claude, gemini, codex, opencode).
- Duplicate `paralife.runtime:` YAML hazard genuinely closed — `jetty:` nests under existing `runtime:` as sibling of `app:`; `[ grep -cE '^  runtime:' -eq 1 ]` guard catches verbatim-paste duplication (all 4).
- A1 references correctly repointed to `20-01b-SUMMARY.md:97` (all-8-setter `javap` evidence); `20-01-SUMMARY.md` confirmed to be async-profiler bootstrap only (all 4).
- Idle-timeout footgun pinned consistently across javadoc ("new wins except when new==default 60000"), 5-case truth table (Case E → 45000L), and `CaseE_bothSetNewAtDefault` test; claude hand-traced all 5 cases A–E (all 4).
- `JettyDeflateCustomizer` line ranges (`@Bean` 75-82, deflate filter 84-180) verified accurate against live file — gemini notes its round-1 mismatch flag was an artifact of stale state (gemini, codex, opencode).

### Agreed Concerns
- **LOW** — Stale prose in Task 2.1 (`20-02-PLAN.md:274`): says "Cover the six tests" / "Tests 2-5" / "Test 6 binding round-trip" but `<behavior>` now lists 9 tests (binding round-trip is Test 9). Embedded code template is complete and authoritative, executor copies it → no functional impact, doc-sync only (claude, codex, opencode).

### Divergent Views
- **MEDIUM (claude only, unverifiable)** — Default *values* (4096/4096/65536/65536/65536/-1) asserted as Jetty 12.0.18 defaults, but A1 only verified setter *existence*, not default values. Baseline `@Bean` set only `idleTimeout`, so Jetty internal defaults governed buffers/frames; if any default differs, explicitly setting it changes behaviour — and the 3-gate golden-trace stack never exercises WS buffer sizing, so nothing catches it. Claude rates MEDIUM (values look right from general Jetty knowledge; jar not in manifest). **Worth a one-line confirmation that defaults — not just setters — were checked.** Other 3 reviewers did not raise this.
- **LOW (claude only)** — "Replace lines 75-82" leaves old javadoc (61-74, "raised…to 60s defensive belt") orphaned above the new one. Compiles, all gates pass; suggest instruction read "replace lines 61-82 (javadoc + method)".
- **LOW (claude only)** — `key_links` frontmatter regex `paralife:\n\s+runtime:` won't match real structure (`paralife:` → `world/tick/websocket` before `runtime:` at line 50). Pre-existing, frontmatter not a gate → cannot wedge execution.
- **LOW (opencode only)** — `BindingRoundTripTest` omits `webEnvironment = NONE` that precedent uses; both MOCK and NONE work for binding-only test → harmless, already in known-deferred list.

Consensus risk: **LOW** across all 4 reviewers. Plan internally consistent end-to-end; no findings at or above HIGH; ready to execute.
