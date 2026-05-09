---
phase: 20
pass: 3
reviewers: [gemini, codex, claude, opencode]
reviewed_at: 2026-05-09T20:40:22Z
plans_reviewed:
  - 20-01-PLAN.md
  - 20-01b-PLAN.md
  - 20-02-PLAN.md
  - 20-03-PLAN.md
  - 20-04-PLAN.md
  - 20-05-PLAN.md
  - 20-06-PLAN.md
mvp_framing: |
  Pass 3 was framed for MVP-scope discipline. Reviewers were instructed to flag
  scope-creep AGAINST MVP, treat already-DEFERRED items as out of scope, and
  only raise HIGH on real correctness/safety holes that block phase completion.
models:
  gemini: gemini-3.1-pro-preview
  codex: gpt-5.5 (reasoning_effort=high)
  claude: opus (effort=xhigh)
  opencode: openrouter/deepseek/deepseek-v4-pro
---

# Cross-AI Plan Review — Phase 20 (Pass 3)

This is the third cross-AI review pass. Two prior passes and their dispositions
(`20-REVIEW-DISPOSITIONS.md`) shaped the current plans. Reviewers were
instructed not to relitigate disposed concerns and to flag suggestions that
would re-expand scope as scope creep.

## Gemini Review

Here is the cross-AI review for the Phase 20 (`20-connection-multiplexing-runtime-tuning`) execution plans.

### Plan 20-01 — Profiler bring-up

**1. Summary**
This plan prepares the profiling toolchain for Phase 20 by providing instructions to install `async-profiler` and documenting the conventions for capturing and naming profile artifacts. It establishes the file-size discipline and SHA-anchoring rules, acting as a prerequisite for the actual baseline capture in Plan 1b.

**2. Strengths**
- Clearly delineates manual setup steps from automated workflows, ensuring the executor doesn't stall on local environment dependencies.
- Thoroughly enforces the D-05 file-size discipline and D-19 SHA-anchoring in the documentation.
- Ensures the codebase is stable (three-gate stack green) before proceeding.

**3. Concerns**
- **LOW:** The plan provides instructions to download the `linux-x64` archive of `async-profiler`, which assumes the host machine uses an x86_64 architecture. If the operator/CI runs on ARM (e.g., Apple Silicon or AWS Graviton), the binary will fail to execute.

**4. Suggestions**
- Update the `tools/async-profiler-bootstrap.md` instructions to mention verifying the host architecture (`uname -m`) and downloading the corresponding `linux-aarch64` or `macos` binary if not on x86_64.

**5. Risk Assessment:** LOW

---

### Plan 20-01b — Baseline JFR + flamegraph capture

**1. Summary**
This plan is the crucial execution gate that captures the `c22e487` baseline JFRs, async-profiler flamegraphs, and Spring Boot Actuator metrics for the 100, 500, and 1000-bot load tiers. It establishes the empirical before-state against which all subsequent tuning optimizations will be measured.

**2. Strengths**
- Uses `curl` against Spring Boot Actuator for metric extraction instead of unreliable log grepping.
- Employs precise file globs to disambiguate the server jar from the load harness jar, preventing execution hazards.
- Includes a robust `jq` validation step to ensure the metric JSON sidecars are structurally sound before concluding.

**3. Concerns**
- **LOW:** The 30-second `sleep` loop for sampling metrics via `curl` does not set a timeout on the `curl` command itself. Under severe load or "tick-overload" scenarios, the server might become temporarily unresponsive, causing `curl` to hang and skewing the capture window.

**4. Suggestions**
- Add a max-time limit to the `curl` sampling command (e.g., `curl -m 2 -s http://...`) to guarantee the sampling loop proceeds smoothly even if the server is struggling under 1000-bot stress.

**5. Risk Assessment:** LOW

---

### Plan 20-02 — paralife.runtime.jetty.* config

**1. Summary**
This plan introduces the `JettyRuntimeConfig` `@ConfigurationProperties` record and wires it into the existing `JettyDeflateCustomizer` to configure Jetty 12 WebSocket buffers, frame sizes, and timeouts. It does so while maintaining zero behavioral change at baseline.

**2. Strengths**
- Excellent backward compatibility approach by preserving the legacy `paralife.websocket.idle-timeout-ms` property as a fallback while promoting the new namespace.
- Thorough Spring slice testing (`JettyIdleTimeoutFallbackTest`) that explicitly validates all four YAML override combinations for the timeout fallback logic.

**3. Concerns**
- **LOW:** The plan dictates modifying the `JettyRequestUpgradeStrategy` bean signature to inject the new config record. If other customizers or tests in the codebase instantiate this bean directly rather than relying on Spring's auto-wiring, the signature change could cause isolated compilation breaks.

**4. Suggestions**
- Rely on the Gradle `compileJava` and `test` tasks to flush out any hidden manual instantiations of `JettyRequestUpgradeStrategy`.

**5. Risk Assessment:** LOW

---

### Plan 20-03 — paralife.runtime.app.* config

**1. Summary**
This plan creates the `AppRuntimeConfig` record along with its nested `OutboundConfig` and `EncodeConfig` sub-records. It strictly layers these new keys alongside the existing admission config without moving or shadowing the critical `outbound-queue-size` key.

**2. Strengths**
- Adheres strictly to the D-20 "alongside-not-move" directive, preventing namespace collision and preserving existing documentation links.
- Tags unconsumed fields properly as `[reserved — no effect in Phase 20]` to avoid operator confusion.
- Comprehensive testing includes an explicit invariant check against shadowing the admission configuration.

**3. Concerns**
- None.

**4. Suggestions**
- None required. The plan is surgically precise and MVP-aligned.

**5. Risk Assessment:** LOW

---

### Plan 20-04 — JVM-flag presets + per-tier recipes

**1. Summary**
This plan scaffolds and populates the `20-RUNTIME.md` specification document, specifically inserting the JVM-flag presets and YAML overrides for the 100, 500, and 1000-bot tiers so that they are copy-pasteable for Phase 21 benchmark scripts.

**2. Strengths**
- Clearly marks GC choices as `Pending — JFR-driven`, enforcing the rule that tuning must be evidence-based rather than assumed.
- Honors the pass-2 disposition to explicitly strip the unconsumed `queue-watermark-pct` from the recipe overrides.

**3. Concerns**
- **LOW:** Hardcoding the JVM memory settings (e.g., `-Xms2g -Xmx2g`) in the documentation templates *before* JFR profiles are fully analyzed might lead to them being permanently overlooked if Plan 5's tuning ultimately suggests a different heap size or GC tuning parameter.

**4. Suggestions**
- Add a reminder in Plan 6's finalization step to explicitly double-check if the `-Xmx` values in the recipes need adjusting based on the final tuned state.

**5. Risk Assessment:** LOW

---

### Plan 20-05 — Codec hot-path opts + tuned JFR

**1. Summary**
This plan represents the core tuning loop: triaging the baseline JFRs to identify codec or runtime bottlenecks, applying changes justified by evidence (or formally declaring a null-result/dominant-pinning outcome), and executing a rigid two-pass regression suite before capturing the tuned-state profiles.

**2. Strengths**
- The "four valid outcomes" decision tree explicitly removes the perverse incentive to tweak code just to show a diff, formally recognizing a "null-result" as a valid outcome.
- Enforces a strong double-green regression run to mitigate the known `TD-19.5-A` test flakiness.
- Captures the exact before/after metrics using the reliable Actuator JSON sidecars.

**3. Concerns**
- **MEDIUM:** The triage of the JFR files and flamegraphs relies heavily on human/LLM heuristic interpretation (e.g., deciding if `StringBuilder` allocation is truly the hot path vs. a secondary effect). This subjective step might result in misidentifying the primary bottleneck.

**4. Suggestions**
- Provide the executor with a strict fallback heuristic: if the JFR signals are ambiguous or do not show a glaring hot path (>5%), default to Outcome 3 (Documented null-result) rather than risking a destabilizing codec change.

**5. Risk Assessment:** MEDIUM

---

### Plan 20-06 — RUNTIME.md finalisation + VALIDATION flip

**1. Summary**
The final plan writes the narrative findings into `20-RUNTIME.md`, applies the D-02 architectural rationale comments across the codebase and documentation, and flips the validation status to complete the phase.

**2. Strengths**
- Enforces strict line-count tiers to ensure honesty over volume (e.g., a concise narrative is preferred for null-results).
- Codifies the `WS:entity 1:1` architectural invariant in multiple places so future contributors understand the design intent.
- Clearly bounds the M4 target tier (1000 bots) while handing off the 100/500 baseline comparisons to Phase 21.

**3. Concerns**
- **LOW:** The automated verification uses `awk` line-count checks (`>= 350` or `>= 250`) based on string parsing the Plan 5 summary file. This shell scripting is slightly brittle if the formatting of the summary file varies even slightly.

**4. Suggestions**
- If the automated line-count check fails due to minor formatting or text density discrepancies, instruct the executor to manually verify the depth of the narrative rather than artificially padding the document with filler text just to pass the `awk` script.

**5. Risk Assessment:** LOW

---

## Codex Review

## Plan 20-01 — Profiler Bring-Up

**Summary**
Good MVP-shaped setup plan: it keeps binary profile capture out of Plan 1 and limits the work to bootstrap docs plus filename conventions.

**Strengths**
- Correctly separates toolchain bring-up from expensive baseline capture.
- Documents SHA anchoring, file-size discipline, and async-profiler usage.
- Keeps async-profiler install as a human checkpoint, which is realistic.

**Concerns**
- **MEDIUM:** The plan requires the three-gate stack to be green, but no task explicitly runs it or records the result before the summary.
- **LOW:** Version pinning/checksum verification for async-profiler is recommended but not enforced. Acceptable for MVP, but the doc should at least record the exact version used.

**Suggestions**
- Add a small final auto step that runs the three-gate command and writes `20-01-SUMMARY.md`.
- In `tools/async-profiler-bootstrap.md`, require recording the exact async-profiler version string.

**Risk Assessment:** LOW-MEDIUM

---

## Plan 20-01b — Baseline JFR + Flamegraph Capture

**Summary**
This is the evidence anchor for the whole phase. The artifact set and metric sidecars are well scoped, but the git-checkout ritual has one correctness ambiguity that should be fixed before execution.

**Strengths**
- Captures 100/500/1000 JFRs plus 1000-bot flamegraphs.
- Adds actuator metric sidecars, which is the right source for headline gauges.
- Uses SHA-anchored filenames and size limits.

**Concerns**
- **HIGH:** The three-gate sanity check appears to run after `git checkout -`, so it may verify HEAD rather than `c22e487`. Since these artifacts claim to be c22e487 baselines, the gate should run and be recorded while still on `c22e487`.
- **MEDIUM:** Meta JSON examples contain placeholders like `<verbatim setter availability check>`, but acceptance does not fail if placeholders remain.
- **LOW:** The 100/500 repeat instructions are under-specified compared with the 1000-bot path.

**Suggestions**
- Move the three-gate command before returning to HEAD, or explicitly run and record both `c22e487` and HEAD gates.
- Add a verification grep that fails on `<...>` placeholder values in `*.meta.json`.
- Add short concrete command blocks for 100 and 500 rather than “adjust proportionally.”

**Risk Assessment:** MEDIUM-HIGH

---

## Plan 20-02 — `paralife.runtime.jetty.*`

**Summary**
The Jetty config surface is MVP-aligned and additive. The main risk is test discoverability: the Spring binding round-trip may not actually run as written.

**Strengths**
- Mirrors existing `@ConfigurationProperties` record patterns.
- Keeps legacy idle-timeout behavior alive.
- Adds tests for fallback semantics without expanding into Binder/nullability migration.

**Concerns**
- **MEDIUM:** `static class BindingRoundTripTest` inside `JettyRuntimeConfigTest` may not be discovered by JUnit unless explicitly selected as a nested test class. If skipped, Spring binding is not verified.
- **LOW:** The plan says all defaults match project-current behavior, but actual Jetty defaults are assumed from research rather than tested at runtime. Acceptable if A1/A-summary confirms it.

**Suggestions**
- Use a top-level `JettyRuntimeConfigBindingTest`, or use `ApplicationContextRunner` inside a normal `@Test`.
- Add a Gradle verification command that proves the binding test method ran, not just that the outer class passed.

**Risk Assessment:** MEDIUM

---

## Plan 20-03 — `paralife.runtime.app.*`

**Summary**
The plan correctly keeps all app knobs reserved and preserves D-20. However, one acceptance check will fail deterministically.

**Strengths**
- Strong MVP discipline: all unconsumed knobs are clearly `[reserved — no effect in Phase 20]`.
- Correctly avoids moving `paralife.admission.backpressure.outbound-queue-size`.
- Good nested-record shape matching local config style.

**Concerns**
- **HIGH:** Acceptance says `grep -v '^[[:space:]]*//' AppRuntimeConfig.java | grep -q "outboundQueueSize"` must fail, but the proposed javadoc includes `AdmissionConfig.BackpressureConfig#outboundQueueSize()`. Since the grep does not strip javadoc block comments, the check will fail.
- **MEDIUM:** Same nested/static Spring binding test discoverability risk as Plan 20-02.

**Suggestions**
- Replace the brittle grep with a source-level check for record fields, e.g. assert no `@DefaultValue... outboundQueueSize` field exists, or use `grep -q "int outboundQueueSize"` and expect no match.
- Make binding tests top-level or `ApplicationContextRunner` based.

**Risk Assessment:** HIGH until the grep is fixed

---

## Plan 20-04 — JVM Presets + Recipes

**Summary**
The doc plan is useful and keeps JVM flags as documentation only. Two recipe details need tightening because Phase 21 will copy these commands verbatim.

**Strengths**
- Keeps SCALE-09 evidence-first by marking GC choices pending until profiles justify them.
- Correctly removes ineffective `queue-watermark-pct` overrides.
- Clear handoff to Phase 21.

**Concerns**
- **HIGH:** Recipes still use ambiguous `build/libs/paralife-*.jar`, which can match both server and load-harness jars. This was fixed in capture plans but not in the operator recipes.
- **MEDIUM:** §3.3 says Plan 5 “may convert `synchronized(session)` → `ReentrantLock`,” which conflicts with the settled disposition that this is Phase 999.6 unless separately promoted.

**Suggestions**
- Use the hardened recipe form:
  `SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1)` and equivalent `HARNESS_JAR`.
- Rewrite the pinning note to say Plan 5 documents dominant pinning and files/cites Phase 999.6; it does not perform the conversion.

**Risk Assessment:** MEDIUM

---

## Plan 20-05 — Codec Opts + Tuned JFR

**Summary**
The plan now has the right MVP shape: measured opt, measured null-result, or documented pinning handoff are all acceptable. The conditional runtime-knob branch needs one guard to avoid inconsistent defaults.

**Strengths**
- Correctly rejects forced deltas.
- Keeps wire schema and security bounds protected.
- Requires tuned JFR plus actuator metric sidecar.
- Preserves D-12 disabled-test boundary.

**Concerns**
- **HIGH:** Outcome 2 says to tighten a knob “default” in `application.yml`, but does not require updating the corresponding record `@DefaultValue`, `defaults()` factory, tests, and docs. That can create split-brain defaults.
- **MEDIUM:** The decision tree still lets runtime-knob tightening appear before the dominant-pinning branch. The prose says pinning supersedes runtime-knob tightening, but the branch order can still mislead executors.
- **LOW:** Validation of `MAX_S_ENTRIES` / `MAX_V_ENTRIES` is grep-based without an explicit baseline comparison.

**Suggestions**
- For outcome 2, either make the change a recipe override only, or require updating every default source: yaml, record `@DefaultValue`, `defaults()`, tests, comments, and `20-RUNTIME.md`.
- Add one line before outcome 2: “Only enter this branch after confirming `jdk.VirtualThreadPinned` is not dominant.”
- Record current codec bound values in `20-05-TRIAGE.md` before edits and compare after.

**Risk Assessment:** MEDIUM-HIGH

---

## Plan 20-06 — Finalization + Validation Flip

**Summary**
The finalization plan closes the documentation loop and the D-02 rationale lock. It has one concrete plan-metadata bug.

**Strengths**
- Good three-place D-02 codification across README, CLAUDE, and source comments.
- Correctly frames 100/500 tiers as baseline-only for Phase 20.
- Keeps README expansion minimal and relevant.
- Requires full tests after comment/doc changes.

**Concerns**
- **HIGH:** `20-VALIDATION.md` is modified in Task 6.5 but is missing from `files_modified`. That will confuse execution/review tooling.
- **LOW:** Line-count rules are still somewhat artificial. They are less harmful after pass-2, but content completeness should matter more than line count.

**Suggestions**
- Add `.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md` to `files_modified`.
- Keep the line-count check, but make the primary acceptance checks section/table completeness and absence of unresolved `Pending` markers.

**Risk Assessment:** MEDIUM

---

## Codex Overall Risk Assessment

**Overall risk: MEDIUM.** The phase is mostly MVP-disciplined and the prior dispositions are reflected well. The main blockers to fix before execution are small but concrete: c22e487 gate timing in Plan 01b, the failing Plan 03 grep, ambiguous jar globs in Plan 04 recipes, split-brain defaults in Plan 05 outcome 2, and the missing `20-VALIDATION.md` entry in Plan 06. These are plan-quality fixes, not scope expansion.

---

## Claude Review

# Phase 20 Cross-AI Review (Pass 3)

## Overall Assessment

These plans have been through two prior review passes with 17+ concerns disposed. The MVP discipline is evident: every "extra" idea is either backlogged (Phase 999.4/999.5/999.6) or explicitly tagged `[reserved — no effect in Phase 20]`. Decisions traceable. Wire safety preserved (`15-SCHEMA.md` LOCKED + three-gate stack + T-20-V5 grep). D-02 three-place rationale codification well-engineered to resist future "optimisation" of the WS:entity 1:1 design.

The plans actually achieve SCALE-08/SCALE-09: SCALE-08 via the four-layer tuning surface (D-21 four-outcome evidence acceptance closes the previous "must-show-non-zero-improvement" hole), SCALE-09 via JFR-anchored measurements + actuator metric sidecars (Pass-2 Concern #10).

I deliberately avoided re-flagging anything in `20-REVIEW-DISPOSITIONS.md` per the idempotency contract. Findings below are **new** issues (not previously raised) or **gaps in disposition follow-through**.

**Risk: LOW.**

---

## Plan-by-Plan

### Plan 20-01 (toolchain bring-up)

**Strengths**
- Clean human-checkpoint isolates the only network step; everything downstream is doc-only.
- `tools/async-profiler-bootstrap.md` documents three install paths (in-tree / external / ap-loader) — operator can pick.
- Three-gate sanity check on profiles/README.md is a nice cheap precondition for 1b.

**Concerns**
- **LOW.** Task 1.0 verification grep `grep -E "async-profiler [0-9]"` is case-sensitive. Recent async-profiler releases print `Async-profiler 4.0` (capital A), so the check may report "not installed" on a working install. Use `grep -iE "async[- ]?profiler [0-9]"` instead.

**Suggestions** — none material.

---

### Plan 20-01b (baseline JFR + actuator metric sidecars)

**Strengths**
- D-19 SHA-anchoring rigour is excellent; Pass-2 Concern #15 capture-script hardening (`mkdir -p`, `SERVER_JAR=$(ls ... | grep -v load-harness | head -1)`, 200s `--duration` for flamegraph margin) addresses real shell hazards.
- Pass-2 Concern #10 actuator metric sidecars close the previous broken "grep server log for Micrometer values" path.
- A1-A8 assumption verification recorded in meta.json gives Plan 2 a concrete signal to react to (drop a Jetty setter if 12.0.18 lacks it).

**Concerns**
- **MEDIUM.** Actuator metric sidecar JSON is built via bash echo + variable embedding inside a heredoc-style loop:
  ```bash
  echo "{\"sample_utc\":\"$TS\",\"work_time_ms\":$WORK_TIME_MS,\"detach_timeout\":$DETACH_TO}" >> "$METRICS_OUT"
  ```
  If the actuator endpoint returns the meter not registered (404 → Spring's JSON error body), or if Spring is configured for indented JSON (multi-line), the embedded variable produces malformed sidecar JSON. The `jq . > /dev/null && echo "OK"` validation runs once at the end — if it fails, the script doesn't roll back the partial sidecar; downstream Plan 5/6 read an unparseable file. Use `jq -n --argjson w "$WORK_TIME_MS" --argjson d "$DETACH_TO" '...' >> "$METRICS_OUT"` to fail-fast on per-sample parse error AND write per-sample JSON robustly.
- **LOW.** Endpoint health check is one-shot at boot (step 2's `curl -s -o /dev/null -w "%{http_code}\n"`). If the meter goes away or the endpoint becomes unhealthy mid-sampling (e.g., `AdmissionMetrics` bean cycle), subsequent `curl` calls return empty/error and silently corrupt the sidecar. Add `[ -n "$WORK_TIME_MS" ] || { echo "BAIL: empty meter response at sample $i"; exit 1; }` inside the loop.

**Suggestions**
- Replace the bash heredoc-style JSON construction with `jq -n --slurpfile w <(curl ...) ...` or write a tiny Python sampler script under `tools/`. Concern #15 hardened the surrounding shell hazards; this is the next-tier ergonomic fix and prevents silent sidecar corruption.

---

### Plan 20-02 (`paralife.runtime.jetty.*`)

**Strengths**
- Pass-2 Concern #16 wording fix ("project-current defaults" not "Jetty defaults") correctly prevents future contributors from "fixing" `idleTimeoutMs=60000` to `30000`.
- Pass-1 Concern #4 `JettyIdleTimeoutFallbackTest` four-case Spring slice test is exactly the right shape — pins observable behaviour without scope-creep into nullable-wrapper migration.
- `resolveEffectiveIdleMs` extracted as a package-private helper is a clean test seam.

**Concerns**
- **LOW.** `Test 6 — BindingRoundTripTest` uses `@SpringBootTest(classes = JettyRuntimeConfig.class) @EnableConfigurationProperties(JettyRuntimeConfig.class)`. Loading a single record class as the Spring root context can be brittle on Spring Boot 3.4.4 (the autowiring fallback path the plan describes is to load `ParalifeApplication`'s full context, but the executor may not realize this until the test fails). Consider preempting: switch the example to `@SpringBootTest @EnableConfigurationProperties(JettyRuntimeConfig.class)` from the start — the slow context load is acceptable for one test.

**Suggestions** — none material.

---

### Plan 20-03 (`paralife.runtime.app.*`)

**Strengths**
- D-20 alongside-not-move proof via `d20AlongsideNotMove_admissionBackpressureUntouched` test asserting `AdmissionConfig.defaults().backpressure().outboundQueueSize() == 128` is exactly right.
- Pass-2 Concern #7 retag of `frameSizeBudgetBytes` to `[reserved]` closes the structural-unconsumability gap correctly.
- All four AppRuntimeConfig fields tagged `[reserved]` is honest — operator-confusion surface eliminated.

**Concerns**
- None new.

**Suggestions** — none material.

---

### Plan 20-04 (per-tier recipes)

**Strengths**
- Recipes are copy-pasteable shell + yaml; Phase 21 can consume verbatim.
- Pass-2 Concern #8 stripping `queue-watermark-pct` from §3.2/§3.3 override blocks is correct — recipe overrides for `[reserved]` knobs would mislead Phase 21 benchmark scripts.
- D-13 honesty: `Pending — JFR-driven` markers explicit until Plan 5 lands measured-justified GC/heap choices.

**Concerns**
- **LOW.** §3 recipes still ship 100/500-bot **server launch** flags that are likely never used as tuning targets in Phase 20 (Pass-2 Concern #17 says only 1000-bot is the M4 target tier). The 100/500 recipes are valuable as Phase 21 inputs, but the §3 prose could clarify that `--duration 60` / `--duration 90` for those tiers is just the harness shape, not a tuning target. Minor framing.

**Suggestions** — none material.

---

### Plan 20-05 (codec opts + tuned JFR)

**Strengths**
- Four-outcome decision tree (D-21) closes the previous forced-fallback rule cleanly; no perverse incentive for the executor to manufacture a delta on top of dominant pinning.
- Pass-2 Concern #14 single-retry-then-revert protocol for unrelated full-suite flakes mirrors TD-19.5-A in-suite convention.
- T-20-V5 grep + `synchronized(session)` count check are concrete invariants that any silent codec regression would trip.

**Concerns**
- **MEDIUM.** **Decision-tree precedence has internal tension with D-21's supersedes rule.** Task 5.0 lists outcomes 1→2→3→4 with "Evaluate in this PRECEDENCE order — earlier outcomes supersede later ones". But the same task body also says `**Pinning-dominates supersedes runtime-knob tightening**`. An executor reading the tree top-to-bottom could fire outcome 2 (knob tightening) when the JFR shows BOTH a tightenable knob signal AND dominant pinning — exactly the case D-21 forbids ("do NOT manufacture a fallback delta on top of dominant pinning"). The supersedes rule is asserted but not structurally enforced in the tree. Fix: hoist outcome 4 evaluation BEFORE outcome 2 in the tree, OR add an explicit gate before outcome 2: "If `jdk.VirtualThreadPinned` count exceeds the outcome-4 threshold, skip outcome 2 and evaluate outcome 4 directly." This is a follow-on from Pass-2 Concern #9 disposition that wasn't fully resolved at the tree-evaluation level — flaggable as new per the idempotency contract.
- **LOW.** Outcome 2 candidate-knob table for `paralife.admission.backpressure.outbound-queue-size` says "JFR signal required: `paralife.outbound.detach.timeout` non-zero at baseline (≥1 event in baseline window)". A single detach-timeout event during a 180s 1000-bot run is statistical noise (could be GC blip, OS scheduler hiccup) — tightening a critical backpressure default on this evidence is overshoot. Raise to `≥10 events/min` or `≥3 events at the same harness phase`.

**Suggestions**
- Reorder Task 5.0 decision tree to: 1 (codec opts) → 4 (pinning-dominates check) → 2 (knob tightening) → 3 (null-result). Honors D-21 supersedes rule structurally. Renumber requires also updating Plan 6 case-statement labels.
- Tighten outcome-2 candidate thresholds; emit them as a triage table operators can copy.

---

### Plan 20-06 (RUNTIME.md finalisation + D-02 three-place codification)

**Strengths**
- D-02 four-file grep test (`grep -lE "WS:entity 1:1" README.md CLAUDE.md WorldWebSocketHandler.java OutboundSender.java | wc -l == 4`) is a tight, durable lock on the deliberate-choice rationale.
- Pass-2 Concern #13 tiered line-count floor (≥350 outcomes 1/2; ≥250 outcomes 3/4) prevents filler text under null-result.
- Pass-2 Concern #17 framing of 100/500-tuned columns as "_baseline-only — see Phase 21_" is honest.
- Validation map population from actual task IDs (Pass-2 I7 fix) makes the validation file genuinely executor-facing.

**Concerns**
- **MEDIUM.** Task 6.1 case-statement for tiered line-count floor reads `OUTCOME=$(grep -E "^Plan 5 outcome:" 20-05-SUMMARY.md | head -1)`. But Plan 5's `<output>` block says only "Triage outcome from Task 5.0 (opts list / null-result declaration / pinning-dominates declaration)" — it does NOT mandate the literal prefix `Plan 5 outcome:` at start-of-line. If Plan 5's executor writes the outcome as "## Outcome" or "**Outcome:**" instead, the grep returns empty, the case falls through to default `*) [ "$LINES" -ge 250 ]`, and outcomes 1/2 with substantive shipped opts get the lower 250-line bar (an underclaim of effort) without any error. Fix: Plan 5's `<output>` block must mandate a single-line `Plan 5 outcome: <signal>` at the top of `20-05-SUMMARY.md` so Plan 6's grep is reliable.
- **LOW.** §4.2 headline-numbers table will have 6 columns at full populate (100b/100t/500b/500t/1000b/1000t). With "_baseline-only — see Phase 21_" in the tuned-100 and tuned-500 cells, the table renders awkwardly in markdown. Consider pivoting to 3 rows × 2 columns (per-tier × baseline/tuned with a row per tier) so the Phase-21-handoff cells don't dominate visual real estate.

**Suggestions**
- Add a one-line `**Plan 5 outcome:** <signal>` at the head of Plan 5's required SUMMARY output. Update Plan 6 Task 6.1 case-statement to match the actual format. Closes the coordination gap.
- Consider table-pivot in §4.2 for cleaner null-result / pinning-dominates rendering.

---

## Cross-Cutting Observations (Claude)

### Strengths

- **MVP discipline is real.** Every "extra" knob is `[reserved]` with deferred-consumer rationale; every backlog item has a Phase 999.x stub.
- **Three-gate stack rigour** (in-suite × 2 consecutive greens per opt, single-retry-then-revert for unrelated flakes) is the right shape for TD-19.5-A.
- **D-02 three-place codification** + the four-file grep test makes "future contributor accidentally introduces multi-entity-per-WS" structurally hard.
- **Actuator metric sidecars (Pass-2 Concern #10)** correctly replace the previous broken log-grep path; D-13/D-18 inheritance truths in Plan 6 are now actually verifiable.

### Real risks (MVP-aligned, not scope-creep)

1. **Decision-tree precedence ambiguity** (Plan 5 Task 5.0) — outcome 2 vs outcome 4. Could fire outcome 2 when D-21 mandates outcome 4. **MEDIUM.**
2. **Plan 5 → Plan 6 outcome-signal coordination** — Plan 6's tiered line-count case-statement depends on a literal prefix Plan 5 doesn't enforce. **MEDIUM.**
3. **Bash-built JSON sidecar fragility** (Plan 1b + Plan 5) — silent corruption possible if actuator endpoint returns multi-line or error body. **MEDIUM.**

These three are coordination/robustness, not correctness holes. None block MVP completion; all three have small-scope fixes.

### Scope-creep red flags (none found)

- No suggestion to expand record fields beyond `[reserved]` usefulness.
- No re-litigation of D-01 (multi-entity-per-WS) anywhere.
- No re-flagging of pass-1/pass-2 concerns.
- No request to wire reserved-field consumers in Phase 20.

---

## Claude Risk Assessment

**Overall: LOW.**

The phase is over-reviewed in the best sense — three passes have shaken out coordination and wording issues and only minor follow-throughs remain. The three MEDIUM concerns above (decision-tree precedence, outcome-signal coordination, JSON sidecar robustness) are all addressable with localized edits to one plan each; none require restructuring. SCALE-08/SCALE-09 evidence acceptance is sound under D-21's four-outcome model. M4 closure via Plan 6 is realistic.

Recommend: apply the three MEDIUM fixes inline (don't trigger pass-4 review), then proceed to execution.

---

## OpenCode Review

# Cross-AI Plan Review — Phase 20 (Pass 3)

## 1. Summary

These plans are extraordinarily thorough — 7 plans, 2 prior review passes, 17 disposed concerns, a well-considered D-21 four-outcome SCALE-08 evidence-acceptance framework, and tight dependency ordering via the wave-bump cascade. The MVP-scope discipline on display (layering new `AppRuntimeConfig` alongside `AdmissionConfig` rather than moving it, reserving fields as `[reserved — no effect]`, deferring namespace consolidation to Phase 999.x, filing ReentrantLock conversion as 999.6) is correct and disciplined. I found no HIGH correctness or safety holes. Three NEW findings below: two MEDIUM (test-shape divergence from project precedent, Plan-4 recipe `SERVER_JAR` glob fragility) and a LOW (varbase64 bounds gap in Plan 5 grep).

**Overall risk: LOW.**

## 2. Strengths

- **D-21 four-outcome decision tree** eliminates perverse incentives to ship untraceable changes. Outcome 4 (dominant pinning with backlog-handoff per Pass-2 Concern #9) is the right reading — structurally identical to null-result, filed not masked.
- **Three-place D-02 codification** (README, CLAUDE, inline comments) creates a grep-verifiable defense against future "optimization" that would collapse the WS:entity 1:1 model.
- **Pass-2 Concern #10 actuator sidecar capture path** is now executable — `application.yml:15` already exposes `metrics`, no code change needed.
- **TD-19.5-A flake mitigation** via `two-consecutive-green in-suite` is pragmatically correct — isolates flake risk without blocking progress.
- **Concern #4 legacy-idle-timeout fallback test** (Task 2.3) correctly handles all 4 yaml-combination cases with a helper extraction, acknowledging the primitive-default detection limitation rather than scope-creeping.

## 3. Concerns

### Concern #18 — Plan 2 + Plan 3 `@SpringBootTest(classes = <record>)` diverges from project precedent (MEDIUM; NEW)

**Detail:** Both Plan 20-02 Task 2.1 and Plan 20-03 Task 3.1 specify `@SpringBootTest(classes = JettyRuntimeConfig.class)` and `@SpringBootTest(classes = AppRuntimeConfig.class)` for the binding-round-trip tests. The canonical project pattern at `AdmissionConfigTest.java:15` wraps the slim config test in:

```java
@SpringBootTest(classes = AdmissionConfigTest.TestApp.class, ...)
...
@Configuration
@EnableConfigurationProperties(AdmissionConfig.class)
static class TestApp {}
```

A plain Java `record` is not a valid Spring `@Configuration` class. `@SpringBootTest(classes = <record>)` will fail with a Spring context startup error (no bean definitions). The plans DO include a fallback instruction ("fall back to `@SpringBootTest` loading `ParalifeApplication`'s full context") — but the full-context fallback is heavyweight (2-5 second cold-start with Jetty/Acutator wiring, blocks `forkEvery=1` per-class) and defeats the test-isolation purpose of a slim binding test. The `TestApp` wrapper pattern avoids this entirely.

**Suggested fix:** In Plan 20-02 Task 2.1, replace:
```java
@SpringBootTest(classes = JettyRuntimeConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
```
with:
```java
@SpringBootTest(classes = JettyRuntimeConfigTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
```
and add:
```java
@Configuration
@EnableConfigurationProperties(JettyRuntimeConfig.class)
static class Config {}
```
Same fix for Plan 20-03 Task 3.1 `AppRuntimeConfigTest`. This is a 3-line change to each test that eliminates the executor from having to hit the fallback path.

### Concern #19 — Plan 20-04 Task 4.2 `SERVER_JAR` glob matches ALL jars produced by `bootJar` + `loadHarnessJar` (MEDIUM; NEW, execution hazard)

**Detail:** Task 4.2's recipe verification step runs `./gradlew bootJar loadHarnessJar` then `java -jar build/libs/paralife-*.jar` (the un-hardened recipe from RESEARCH). At step-2 verification time for 100/500-bot tiers, the recipes as written in the human-verification instructions use `-jar build/libs/paralife-*.jar` which matches BOTH the server jar AND the load harness jar. The Plan 1b (20-01b) capture commands already contain the `SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1)` hardening per Pass-2 Concern #15, but Plan 20-04's §3 recipes themselves (which Phase 21 copy-pastes verbatim) still use the ambiguous `paralife-*.jar` pattern. The human at Task 4.2 might start the harness as the server (randomly picking the wrong jar), waste 30 seconds debugging, and resubmit.

**Suggested fix:** In 20-04-PLAN.md Task 4.2's §3 recipe templates, harden the `java -jar build/libs/paralife-*.jar` line to the Plan 1b `SERVER_JAR` / `HARNESS_JAR` pattern, OR replace the wildcard with explicit classifier-pinned paths: `-jar build/libs/paralife-*-SNAPSHOT.jar` for server (the bootJar has no classifier) and `-jar build/libs/paralife-*-load-harness.jar` for harness. The `bootJar` task produces `build/libs/paralife-0.0.1-SNAPSHOT.jar`; the `loadHarnessJar` task produces `build/libs/paralife-0.0.1-SNAPSHOT-load-harness.jar`. The wildcard `paralife-*.jar` matching both is the ambiguity.

Alternatively (cheapest fix): add a `Boot server:` / `Drive harness:` prose separator that makes it clear which command applies to which jar, with explicit harness-jar classifier in the second command — already partially present but the server line still uses `paralife-*.jar`.

### Concern #20 — Plan 5 T-20-V5 grep omits varbase64 length bounds (LOW; NEW)

**Detail:** Plan 20-05 Task 5.1's codec-opt cycle verification says `grep -E "MAX_S_ENTRIES|MAX_V_ENTRIES" src/main/java/com/paralife/codec/PerceptionCodec.java` to verify bounds unchanged. The 15-SCHEMA.md also defines varbase64 length bounds (e.g., `encodeVarBase64` output limits, `decodeVarBase64` input sanity checks). These bounds might live in `Base64Codec.java` or inline in `PerceptionCodec.java`. A codec opt that changes the StringBuilder or encoding path could accidentally relax an inline integer constant. The `MAX_S_ENTRIES`/`MAX_V_ENTRIES` grep alone won't catch a drift in `Base64Codec` bounds.

**Suggested fix:** Extend Plan 5 Task 5.1's bounds-verification grep to include `Base64Codec.java` and search for any integer constant that appears to be a size bound (e.g., `git diff c22e487 -- src/main/java/com/paralife/codec/Base64Codec.java | grep '^[+-]' | grep -E '[0-9]{2,}'` as a diff-driven sanity). Or explicitly note in the task that the executor should diff `c22e487` against HEAD in both `PerceptionCodec.java` AND `Base64Codec.java` and manually audit any changed integer literal for wire-impact.

## 4. Suggestions (OpenCode)

- **#18 (preferred):** Use the `@Configuration @EnableConfigurationProperties` wrapper pattern in Plan 2/3 binding tests — eliminates fallback-path execution entirely, matches project precedent, and is a 3-line change per test file.
- **#19:** Harden `paralife-*.jar` in Plan 4 §3 recipe templates to either `paralife-*-SNAPSHOT.jar` (server) / `paralife-*-load-harness.jar` (harness), or match the Plan 1b shell-variable pattern.
- **#20:** Extend Plan 5 T-20-V5 bounds-verification to include `Base64Codec.java` via a `git diff`-against-`c22e487` sanity check.
- **PATTERNS.md cleanup (no concern — just a note):** `20-PATTERNS.md` line 14 lists `RuntimeBeansConfig.java` (NEW) as a "bean factory" file, but no plan declares it as `files_modified`. This confused me for ~30s. Either remove it from PATTERNS.md (if it was intentionally dropped during plan reduction) or add a note that it's a future-injection point deferred to Plan 5 or Phase 999.x. Does not block execution — executors read plans, not PATTERNS.md as a todo list.

## 5. Risk Assessment (OpenCode)

**LOW.** The two MEDIUM findings are test-shape and recipe-glob issues caught by the executor at test/verify time (not silent runtime failures). The three-gate equivalence stack (D-11), two-consecutive-green protocol (TD-19.5-A), and the D-21 four-outcome SCALE-08 evidence-acceptance framework together form a sufficient safety net. No scope-creep concerns — the plans are disciplined.

All pass-1 (#1-6) and pass-2 (#7-17) concerns are already disposed. None of my findings re-open any of them.

---

## Consensus Summary

Three of four reviewers rate overall risk **LOW**; Codex rates **MEDIUM**. All
four reviewers agree the plans are MVP-disciplined and that prior dispositions
are reflected well. **No reviewer raised scope-creep against MVP**, and no
reviewer relitigated disposed concerns.

### Agreed Strengths (2+ reviewers)

- **D-21 four-outcome decision tree** — closes the forced-fallback hole; null-result and pinning-dominates are valid outcomes (Codex, Claude, OpenCode, Gemini).
- **D-02 three-place codification** of WS:entity 1:1 with grep-verifiable lock — durable defense against future regressions (Claude, OpenCode, Gemini).
- **Pass-2 Concern #10 actuator metric sidecars** are the right shape, replacing the broken log-grep path (Claude, OpenCode, Gemini).
- **MVP discipline real** — reserved-knob tagging, alongside-not-move (D-20), Phase 999.x deferrals are correctly applied (all four).
- **TD-19.5-A flake mitigation** (in-suite double-green, single-retry-then-revert) pragmatically correct (Codex, Claude, OpenCode).

### Agreed Concerns (raised by 2+ reviewers — highest priority)

1. **Plan 20-04 §3 recipes use ambiguous `paralife-*.jar` glob** — Codex (HIGH), OpenCode (MEDIUM). Plan 1b was hardened to `SERVER_JAR=$(ls ... | grep -v load-harness | head -1)` (Pass-2 Concern #15) but Plan 4's recipe templates that Phase 21 will copy-paste still use the ambiguous wildcard. Fix: harden recipes to the same `SERVER_JAR`/`HARNESS_JAR` shell-variable pattern, or pin classifiers (`paralife-*-SNAPSHOT.jar` server vs `paralife-*-load-harness.jar` harness).

2. **Plan 20-02 + Plan 20-03 binding-test shape is brittle** — Codex (MEDIUM), Claude (LOW), OpenCode (MEDIUM). All three converge: the `@SpringBootTest(classes = <record>)` form plus the nested `static class BindingRoundTripTest` may fail to discover or fail to start the context. OpenCode flags it most strongly: a plain Java `record` is not a valid `@Configuration` class. Project precedent at `AdmissionConfigTest.java:15` uses a `TestApp` wrapper (`@Configuration @EnableConfigurationProperties(...) static class TestApp {}`). Fix: adopt the `TestApp` wrapper pattern in both Plan 2 and Plan 3 binding tests, OR move the binding test to a top-level `*BindingTest` class (Codex's alternative).

3. **Plan 20-05 decision-tree precedence vs D-21 supersedes rule** — Codex (MEDIUM), Claude (MEDIUM). Task 5.0 outcome list reads top-to-bottom 1→2→3→4 but D-21 says pinning-dominates (outcome 4) supersedes runtime-knob tightening (outcome 2). An executor reading the tree linearly could fire outcome 2 when JFR shows BOTH a tightenable knob signal AND dominant pinning. Fix: hoist outcome 4 evaluation before outcome 2, or add an explicit gate before outcome 2 ("If `jdk.VirtualThreadPinned` count exceeds outcome-4 threshold, skip outcome 2").

4. **Plan 20-05 outcome-2 default-update coordination + Plan 20-06 outcome-signal grep coordination** — Codex and Claude raise adjacent coordination gaps. Codex (HIGH): outcome 2 only mandates editing `application.yml`, missing record `@DefaultValue`, `defaults()`, tests, and docs — split-brain defaults possible. Claude (MEDIUM): Plan 6 Task 6.1's tiered line-count case-statement greps `^Plan 5 outcome:` from `20-05-SUMMARY.md`, but Plan 5's `<output>` block doesn't mandate that literal prefix — fall-through to default would underclaim shipped opts as null-result effort. Fix: either route outcome 2 changes to recipe overrides only OR require updating every default source; AND mandate a single-line `Plan 5 outcome: <signal>` at the head of `20-05-SUMMARY.md`.

### Single-Reviewer Concerns (worth dispositioning)

- **Codex HIGH (Plan 20-01b):** Three-gate sanity check may run after `git checkout -`, verifying HEAD instead of `c22e487`. Move three-gate before returning to HEAD or record both gates.
- **Codex HIGH (Plan 20-03):** `grep -v '^[[:space:]]*//' AppRuntimeConfig.java | grep -q "outboundQueueSize"` will fail because the proposed javadoc includes `AdmissionConfig.BackpressureConfig#outboundQueueSize()` and the grep doesn't strip block comments. Replace with `grep -q "int outboundQueueSize"` and expect no match, or check record fields directly.
- **Codex HIGH (Plan 20-06):** `20-VALIDATION.md` is modified in Task 6.5 but is missing from `files_modified`. Add it.
- **Codex MEDIUM (Plan 20-04 §3.3):** Notes a comment that Plan 5 "may convert `synchronized(session)` → `ReentrantLock`," conflicting with the disposition that this is Phase 999.6. Reword to "documents pinning and cites Phase 999.6."
- **Claude MEDIUM (Plan 20-01b):** Bash-built actuator-sidecar JSON is fragile under multi-line / error-body responses; suggests `jq -n --argjson w ... --argjson d ...` per-sample for fail-fast parse safety.
- **Codex MEDIUM (Plan 20-01):** Three-gate stack precondition isn't explicitly run/recorded in any task before the summary.
- **Codex / OpenCode LOW (Plan 20-05 bounds):** T-20-V5 grep covers `MAX_S_ENTRIES`/`MAX_V_ENTRIES` in `PerceptionCodec.java` but doesn't cover varbase64 bounds in `Base64Codec.java`. Extend grep or add a `git diff c22e487` audit on `Base64Codec.java`.
- **Claude LOW (Plan 20-05 outcome-2 threshold):** `paralife.outbound.detach.timeout ≥1 event in baseline window` is statistical noise; raise to `≥10/min` or `≥3 at the same harness phase` before tightening a critical default.
- **Gemini LOW × 6:** Architecture/x86 assumption in Plan 1, `curl -m 2` timeout in Plan 1b, signature ripple in Plan 2, heap settings before JFR in Plan 4, JFR triage subjectivity in Plan 5 (raised to MEDIUM by Gemini), `awk` line-count brittleness in Plan 6.
- **Claude LOW (Plan 20-01):** `grep -E "async-profiler [0-9]"` is case-sensitive; recent releases print "Async-profiler 4.0". Use `grep -iE "async[- ]?profiler [0-9]"`.
- **OpenCode (note, not concern):** `20-PATTERNS.md` line 14 references `RuntimeBeansConfig.java` (NEW) but no plan declares it as `files_modified` — clean up PATTERNS.md or stub the deferred consumer.

### Divergent Views

- **Overall risk rating:** Claude/OpenCode/Gemini → LOW; Codex → MEDIUM. Driven by Codex weighting plan-quality bugs (HIGH-severity grep errors, missing `files_modified`, ambiguous globs in operator recipes) more heavily; the others frame the same issues as small-scope follow-throughs catchable at test time.
- **`@SpringBootTest(classes = <record>)` failure mode:** OpenCode argues this fails outright at Spring context startup (record can't be `@Configuration`); Codex and Claude treat the fallback path as workable but suboptimal. Verify against actual Spring Boot 3.4.4 behaviour before dispositioning.
- **JFR triage subjectivity:** Gemini raises this to MEDIUM (recommends defaulting to outcome 3 if no >5% hot path); other reviewers treat the same surface as adequately bounded by the four-outcome framework. Worth a one-line explicit triage threshold in Plan 5.

### Recommendation

Apply the four agreed concerns plus Codex's three single-reviewer HIGHs as inline plan revisions. The phase has now had three review passes — a fourth pass is unlikely to surface new substance and would burn time. Disposition all NEW concerns, replan inline, then proceed to execution.
