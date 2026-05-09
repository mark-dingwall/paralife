---
phase: 20
pass: 2 (re-review after replan against 20-REVIEW-DISPOSITIONS.md)
reviewers: [gemini, claude, codex, opencode]
models: {gemini: gemini-3-pro-preview, claude: opus, codex: gpt-5.5 (high effort), opencode: openrouter/deepseek/deepseek-v4-pro}
reviewed_at: 2026-05-09T14:01:52Z
plans_reviewed: [20-01-PLAN.md, 20-01b-PLAN.md, 20-02-PLAN.md, 20-03-PLAN.md, 20-04-PLAN.md, 20-05-PLAN.md, 20-06-PLAN.md]
supersedes: 20-REVIEWS.md pass-1 (2026-05-09 morning); pass-1 disposed in 20-REVIEW-DISPOSITIONS.md
---

# Cross-AI Plan Review — Phase 20 (Pass 2)

Re-review after planner applied 20-REVIEW-DISPOSITIONS.md fixes for pass-1 concerns #1–#6.
Reviewers were instructed to skip items already disposed and focus on (a) NEW gaps, (b) DISAGREEMENTS with a specific disposition, (c) FOLLOW-ONs of the fixes.

---

## Gemini Review (gemini-3-pro-preview)

# Phase 20 Cross-AI Plan Review

## 1. Summary

This is an excellent replan that maturely and thoroughly resolves the first-pass review concerns. The decision to embrace a "documented null-result" (Concern #1 reframing) is the standout improvement—it removes the perverse incentive to ship fragile, manufactured optimizations just to satisfy a diff requirement, grounding the phase entirely in evidence. The extraction of the legacy idle-timeout fallback logic into a testable helper (Concern #4) is pragmatic and correctly avoids scope-creeping into a full Spring `Binder` migration. The plan slicing, particularly the split of toolchain bootstrap (20-01) from the baseline capture (20-01b), creates a clear, executable sequence.

## 2. Strengths

*   **Evidence-Driven Decision Tree:** Plan 5's triage protocol—evaluating VT pinning, then codec hot-paths, then knob-tightening, and finally defaulting to a null-result—is a masterclass in safe performance engineering.
*   **Flake Mitigation:** Mandating two consecutive *in-suite* green runs for the three-gate stack in Plan 5 perfectly navigates the known `GoldenTraceEquivalenceTest` flake (TD-19.5-A) without blocking progress.
*   **Architectural Defense:** The rigorous three-place codification of the `WS:entity 1:1` invariant ensures future contributors will understand the design intent before attempting misguided multiplexing "optimizations."

## 3. Concerns

*   **MEDIUM — Follow-on of Concern #5: `frameSizeBudgetBytes` is a dead config.**
    *   *Type:* FOLLOW-ON
    *   *Detail:* The replan successfully tagged `queueWatermarkPct`, `encodeBatchHint`, and `parallelEncodeThreshold` as `[reserved]` because they lack consumers. However, it left `frameSizeBudgetBytes` tagged as `[live-tunable]`. Plan 5 strictly dictates "No public API change (`encode(Frame)`, `decode*`, `validate*` signatures unchanged)". Because `PerceptionCodec.encode(Frame)` allocates its own `StringBuilder` internally and takes no capacity argument, it is structurally impossible for the codec to consume `frameSizeBudgetBytes` from `AppRuntimeConfig` without an API change. Therefore, this config is just as dead as the others.
*   **LOW — Weak D-20 invariant unit test.**
    *   *Type:* NEW
    *   *Detail:* In `AppRuntimeConfigTest`, Test 8 (`d20AlongsideNotMove_admissionBackpressureUntouched`) instantiates `AdmissionConfig.defaults()` to check that the queue size is 128. This only tests the static factory method; it does not test whether Spring actually avoided shadowing/overwriting the value during YAML binding. (Note: The `grep` in Task 3.2 mitigates this risk in practice, making it a low severity issue).

## 4. Suggestions

*   **Fix for the MEDIUM concern (Dead Config):** In Plan 20-03, update both `AppRuntimeConfig.java` and `application.yml` to tag `frameSizeBudgetBytes` as `[reserved — no effect in Phase 20] codec sizing hint; consumer wiring deferred`. This keeps the schema additive but prevents operator confusion, perfectly aligning with the disposition for Concern #5.
*   **Fix for the LOW concern (Weak Test):** (Optional) In `AppRuntimeConfigTest`, move the D-20 assertion into `BindingRoundTripTest`. Autowire `AdmissionConfig` alongside `AppRuntimeConfig` and assert that the loaded Spring context preserved the `128` value for the admission backpressure queue.

## 5. Risk Assessment

**LOW.** 

The implementation constraints are incredibly tight, and the test gates (including the consecutive-run requirement and strict bounds checking) provide a massive safety net. The reframing to allow a documented null-result eliminates the primary risk of this phase (shipping bad code to hit arbitrary performance targets). Once the minor dead-config tag is addressed, this phase is exceptionally safe to execute.

---

## Claude Review (opus)

# Phase 20 Re-Review (Pass 2)

## Summary

Replan adequately addresses prior-pass concerns. D-21 (null-result acceptance) cleanly resolves Concern #1's perverse incentive. Task 2.3 (legacy idle-timeout fallback) closes Concern #4 with appropriate scope (unit test + helper extraction, not Spring `Binder` migration). Concern #6 wave-bump cascade is canonical and self-documenting. Concern #5 retag eliminates dead-config operator confusion. File-size cap relaxation (Concern #3) plus `jfr filter` deferred-fallback is pragmatic. Phase 999.6 stub for the `synchronized → ReentrantLock` conversion (Concern #2) is correctly backlog-not-MVP. Plan 5's four-outcome decision tree with explicit precedence (pinning-dominates > codec opts > runtime-knob > null-result) is the right shape. Risk: **LOW**.

## Strengths

- Plan 5 Task 5.0 decision tree precedence ordering correctly puts pinning-dominates first, blocking the failure mode where forced-fallback knob tightening would mask a real architectural problem.
- D-21 codifies that the tuning surface (Plans 2+3+4) IS the SCALE-08 deliverable — measurement of equivalence is itself evidence. This is a structurally cleaner SCALE-08 framing than the original "must show non-zero delta" rule.
- Task 2.3 helper-extraction approach (`resolveEffectiveIdleMs` package-private) avoids Spring `Binder` / nullable-wrapper migration scope creep while still pinning all 4 yaml combinations.
- Disposition document's idempotency contract ("future review re-flagging MUST be marked resolved-by-disposition") is the right shape for review-loop convergence.
- Wave numbering now follows `wave = max(deps_waves) + 1` canonically. Verified: 20-01=1, 20-01b=2, 20-02=3, 20-03=2, 20-04=4, 20-05=4, 20-06=5.

## Concerns

### NEW-1 — Plan 5 outcome 3 (runtime-knob tightening) doesn't require updating downstream-affected docs (LOW)

Type: NEW (not in dispositions).

If outcome 3 fires and the executor tightens `paralife.runtime.jetty.idle-timeout-ms` default away from 60000, the existing `JettyDeflateCustomizer.java:69-73` javadoc rationale ("Idle timeout is raised from Jetty's 30s default to ... 60s ... defensive belt to the keepalive service's braces") is no longer accurate. Plan 5 Task 5.1 outcome-3 path lists the change as "config-only" but doesn't require updating that javadoc. Minor doc-drift risk if outcome 3 lands.

**Fix:** Plan 5 Task 5.1 outcome-3 acceptance — add a checklist item: "If a knob default changes, update the javadoc/comment at the binding site (e.g., `JettyDeflateCustomizer.java:69-73`) to reflect the new default + cite the JFR signal that justified the change." One-line addition.

### NEW-2 — Plan 6 Task 6.1 §4.3 narrative depth pressure for outcome 4 (null-result) (LOW)

Type: NEW.

Plan 6 Task 6.1 acceptance requires `wc -l 20-RUNTIME.md ≥ 350 lines`. For a true null-result outcome, §4.3 (per-tier narrative) and §4.4 (codec opts) would be substantively sparse. The 350-line floor risks pushing the executor toward filler text rather than recording "we measured, system is at floor, no opt shipped, here's the evidence" concisely.

**Fix:** soften acceptance to `≥250 lines for outcome 4 (null-result), ≥350 lines for outcomes 2/3`, OR drop the line-count bar in favour of a section-presence grep (which is already there separately). Honesty > volume.

### NEW-3 — Plan 5 outcome verification: noise-floor threshold not defined (LOW)

Type: NEW.

Plan 5 Task 5.2 outcome-4 acceptance says "tuned ≈ baseline within noise floor" but never defines noise floor. Plan 5 outcome-2/3 acceptance says "If the measured delta is below noise floor, document the noise-floor evidence and proceed." Without a concrete threshold (e.g., `±5%` on `paralife.tick.health.work-time-ms` mean across 3 sample windows), executors may diverge in interpretation.

**Fix:** add a one-line noise-floor convention to D-21 or to Plan 5's success criteria — e.g., "noise floor = ±5% of baseline mean OR within 1 standard deviation, whichever is larger; computed across the JFR sample window." Plan 5 Task 5.2 cites this convention.

### NEW-4 — Codec-opt verification: full-suite flake handling unspecified (LOW)

Type: NEW.

Plan 5 Task 5.1 codec-opt cycle: Run 1 (three-gate, in-suite) → Run 2 (three-gate, in-suite) → full suite. If full suite flakes on an unrelated test (e.g., a `forkEvery=1` carrier-starvation timer), the plan doesn't say whether to revert the codec change or retry the full suite. Could lead to inappropriate reverts or repeat cycles.

**Fix:** Plan 5 Task 5.1 — add a one-sentence note: "If full suite reports failures unrelated to the codec opt (per Phase 22 known flake list TD-22-A..D), retry the full suite once before reverting; if the same failure recurs, revert and skip." Aligns with TD-19.5-A in-suite-only convention already in place for the three-gate stack.

### Resolved-by-disposition cross-check

I scanned for items already disposed and confirm none are re-raised in this concern set. Concerns #1-#6 in `20-REVIEW-DISPOSITIONS.md` are all addressed in the replan; the four NEW concerns above are follow-on/edge-case clarifications, not disagreements with dispositions.

## Suggestions

1. **Plan 5 Task 5.1 outcome-3 acceptance (NEW-1):** add javadoc/comment-update checklist item when knob default changes.
2. **Plan 6 Task 6.1 acceptance (NEW-2):** tier line-count requirement by Plan 5 outcome OR replace with section-presence grep.
3. **D-21 or Plan 5 success criteria (NEW-3):** define "noise floor" — `±5% of baseline mean OR ±1σ, whichever larger`.
4. **Plan 5 Task 5.1 codec cycle (NEW-4):** specify single-retry-then-revert protocol for unrelated full-suite flakes.
5. **Trust-but-verify (out-of-band):** disposition doc asserts `.planning/phases/999.6-vt-pinning-reentrantlock-conversion/CONTEXT.md` exists — confirm via filesystem before sign-off.

## Risk Assessment

**LOW.** All four NEW concerns are minor process/doc clarifications, not architectural or correctness gaps. SCALE-08 / SCALE-09 success criteria are achievable under all four Plan 5 outcomes. Wave ordering, dependency declarations, and security controls (T-20-V5 codec bounds, T-20-DOS-1 Jetty maxFrameSize, D-12 disabled-tests untouched) are all intact. The replan is shippable as-is; the four NEW items can land as 5-line edits to the affected plans without further review.

---

## Codex Review (gpt-5.5 high effort)

**Summary**

The replan addresses the main first-pass concerns well. I treated dispositions #1-#6 as resolved-by-disposition and did not re-raise them. The remaining issues are mostly follow-ons introduced by the fixes: the plan now permits a couple of evidence paths that either cannot be measured as written or could accidentally claim SCALE-08 completion while documenting an unresolved bottleneck.

**Strengths**

- The forced-fallback problem is fixed: Plan 20-05 now permits a documented null-result and removes pressure to manufacture a delta.
- The ReentrantLock/pinning work is correctly treated as evidence-triggered and not silently expanded into MVP scope.
- Dependency ordering is much cleaner after splitting 20-01/20-01b and gating 20-02 on A1.
- D-20 alongside-not-move is explicitly protected in Plan 20-03.
- The D-02 rationale is now hard to miss across README, CLAUDE, and source comments.

**Concerns**

- **HIGH — NEW:** The headline metric collection path is not executable as written.  
  Plan 20-05 says to read `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout` from `/tmp/p20-tuned-server.log`, but these are Micrometer meters registered in `AdmissionMetrics`, not log lines. Plan 20-01b also does not persist baseline actuator metric snapshots, so Plan 20-05/20-06 may not have valid baseline values for D-13/D-18.

- **HIGH — FOLLOW-ON of dispositions #1/#2:** The `pinning-dominates` branch can still be shipped as a SCALE-08 outcome.  
  D-21 permits a documented null-result only when the baseline is already at the performance floor. If JFR shows `jdk.VirtualThreadPinned` is dominant, that is not a null-result; it is evidence of an unresolved overhead path. Plan 20-05 should not allow “file Phase 999.6 and ship Plan 5 as SCALE-08” unless the requirement is explicitly downgraded.

- **MEDIUM — FOLLOW-ON of disposition #5:** `queue-watermark-pct` is still treated as effective in later plans.  
  Plan 20-03 correctly labels it `[reserved — no effect in Phase 20]`, but Plan 20-04 recipes describe it as a tighter slow-client signal, and Plan 20-05 lists it as a runtime-knob tightening candidate for `detach.timeout`. With no consumer wiring, changing this value cannot produce a measured runtime delta.

- **MEDIUM — NEW:** Plan 20-06 overclaims 100/500 tuned evidence.  
  It says to mark 100/500 as `tuned ≡ baseline` if only the 1000-bot tuned JFR was captured. But D-13 requires per-tier before/after evidence, and Plan 5 changes can affect all tiers. Equivalence should be measured or explicitly not claimed.

- **LOW — NEW:** Capture commands have execution hazards.  
  After `git checkout c22e487`, the new `profiles/` directory may not exist, so `JFR_OUT=.../profiles/...` can fail without `mkdir -p`. Also `build/libs/paralife-*.jar` is ambiguous once `loadHarnessJar` exists, and the three sequential 60s flamegraphs can run past the 180s load window.

- **LOW — NEW:** Plan 20-02 wording says defaults match Jetty defaults, but `idleTimeoutMs=60000` matches the current project default, not Jetty’s 30s default. The behavior is probably right; the text/test name should be corrected.

**Suggestions**

- Add metric snapshot artifacts to 20-01b and 20-05: use `/actuator/metrics/paralife.tick.health.work-time-ms`, `/actuator/metrics/paralife.outbound.detach.timeout`, and preferably periodic sampling or `paralife.tick.work.ms` distribution stats for mean work time.
- Make `pinning-dominates` a blocking outcome for Phase 20 acceptance unless scope is expanded or the phase explicitly records SCALE-08 as not closed.
- Remove `queue-watermark-pct` from Plan 20-04 recipe overrides and Plan 20-05 runtime-knob candidates, unless Plan 20-05 also wires a real consumer and tests it.
- Capture tuned 100/500 JFR + metric sidecars, or revise D-13/20-RUNTIME so those tiers are baseline-only and not presented as before/after evidence.
- Harden capture scripts with `mkdir -p`, explicit `SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1)`, and either longer harness duration or separate flamegraph runs.
- Rename “Jetty defaults” to “project-current defaults; Jetty defaults except idle timeout.”

**Risk Assessment**

**HIGH** until the metric-capture and pinning-dominates acceptance issues are fixed. The implementation scope is otherwise controlled, but as written the phase could produce documentation that appears to satisfy SCALE-08/SCALE-09 without actually having valid headline measurements or resolving a measured dominant overhead path.

---

## OpenCode Review (openrouter/deepseek/deepseek-v4-pro)

# Cross-AI Review — Phase 20 Plans (Re-Review)

## Summary

The replan adequately addressed prior-pass concerns. Concern #1 (null-result acceptance), #4 (idle-timeout test), #5 (reserved tags), and #6 (dependency bump) are correctly resolved. Concern #2 (ReentrantLock backlogging) and #3 (JFR cap relaxation) are properly deferred. The wave renumbering cascade is self-consistent. No disagreements with any disposition. One new MEDIUM concern (Plan 5 "paused" ambiguity) and two LOW observations.

## Strengths

- **D-21 null-result acceptance** closes the forced-fallback perverse incentive cleanly — all four Plan 5 outcomes are traceable and none manufacture a delta
- **Concern #4 fix** (four-case idle-timeout test + `resolveEffectiveIdleMs` extraction) is surgically minimal — adds coverage without scope-creeping into Spring `Binder`/nullable migration
- **Concern #5 fix** retags reserved fields to `[reserved — no effect in Phase 20]` — zero-risk change that kills the operator-confusion gap
- **Three-gate two-consecutive-green rule** (RESEARCH Pitfall 4) is the right TD-19.5-A flake mitigation — running once is ~40% flaky; twice consecutive makes false-green vanishingly unlikely
- **Plan 6's four-file D-02 grep gate** (`grep -lE "WS:entity 1:1" README.md CLAUDE.md WorldWebSocketHandler.java OutboundSender.java | wc -l == 4`) is the right paranoia check — cheap and definitive

## Concerns

### Concern #7 — Plan 5 outcome 1 "paused" wording ambiguous (MEDIUM, NEW)

Plan 20-05 Task 5.1 says for pinning-dominates: "Plan 5 is paused. The orchestrator decides whether to expand scope." But Plan 6 depends on Plan 5, and Task 5.2 (tuned-JFR capture) still needs to run even under outcome 1 (to produce equivalence evidence). The intent — don't silently convert `synchronized` — is correct, but "paused" reads as a blocking state rather than a valid completion. Plan 6 can proceed with outcome 1's documented finding (pinning count + affected sites in 20-05-TRIAGE.md) without waiting for scope expansion.

**Suggested fix:** In Task 5.1 outcome 1 text, replace "Plan 5 is paused. The orchestrator decides whether to expand scope (in which case the executor receives an updated plan...)" with "Plan 5 proceeds to Task 5.2 with the documented pinning finding; the orchestrator decides in parallel whether to expand scope via a separate plan. The pinning evidence is itself a valid Plan 5 completion." Task 5.2's tuned-JFR capture still runs (equivalence proof regardless of outcome).

### Concern #8 — `20-PATTERNS.md` referenced but not provided (LOW, NEW)

All seven plans reference `@.planning/phases/20-connection-multiplexing-runtime-tuning/20-PATTERNS.md` in their `<context>` or `<read_first>` blocks. This file is not in the review packet. If it doesn't exist, the executor will encounter a missing reference.

**Suggested fix:** Either include 20-PATTERNS.md (if it exists) or remove the reference from context blocks (the plans are self-contained enough without it).

### Concern #9 — Plan 20-03 / Plan 20-02 YAML merge fragility (LOW, NEW)

Plan 20-03 Task 3.2 says "if Plan 2 (Wave 2 sibling) has not yet committed the `paralife.runtime.jetty:` block when this task runs, the executor must Read the file first and create the parent `paralife: runtime:` skeleton if absent." This is handled explicitly but creates an ordering sensitivity between two files adding to the same YAML subtree.

**Suggested fix:** No code change needed — the Read-first instruction is adequate. This is an execution-note concern, not a plan defect.

## Suggestions

1. **Concern #7:** Clarify Plan 5 outcome 1 as a non-blocking completion per above
2. **Concern #8:** Resolve 20-PATTERNS.md existence before execution
3. **Minor:** Task 6.4's `oldString` for `afterConnectionEstablished` depends on exact whitespace matching — the executor should `Read` the actual file first (which the plan's `<read_first>` already instructs) to avoid Edit-tool failure on whitespace drift

## Risk Assessment

**LOW.** The plans are coherent, dependency ordering is consistent after the wave renumbering cascade, and all six prior concerns are correctly disposed. The one new MEDIUM concern (outcome 1 "paused" ambiguity) is a language fix, not a structural problem — Plan 5's actual task flow (triage → act-or-document → capture-tuned-JFR) works correctly for all four outcomes. No blocking issues.


---

## Consensus Summary

All four reviewers confirm the replan adequately addresses pass-1 dispositions #1–#6 and explicitly mark concerns #1–#6 as resolved-by-disposition (no re-flagging). Risk verdicts split: Gemini, Claude, OpenCode call it **LOW**; Codex calls it **HIGH** until two specific issues are tightened (metric capture path + pinning-dominates acceptance).

### Agreed Strengths (2+ reviewers)

- **D-21 null-result acceptance** removes the forced-fallback perverse incentive (gemini, claude, codex, opencode).
- **Decision-tree precedence** (pinning-dominates → codec opts → runtime-knob → null-result) (gemini, claude, codex).
- **Concern #4 helper extraction** (`resolveEffectiveIdleMs`) avoids Spring `Binder` migration scope creep (gemini, claude, opencode).
- **Concern #5 reserved retag** kills operator-confusion gap on dead-tunable fields (gemini, claude, opencode).
- **D-02 three-place codification** of the WS:entity 1:1 invariant (gemini, claude, codex; opencode flags the four-file grep gate).
- **TD-19.5-A flake mitigation** via two-consecutive-green / in-suite rule (claude, opencode).
- **Plan slicing 20-01 / 20-01b** improves dependency ordering (gemini, codex).

### Agreed Concerns (2+ reviewers — highest priority)

#### A. Reserved-tag follow-on — additional dead configs still tagged `[live-tunable]` (Gemini MEDIUM + Codex MEDIUM, both FOLLOW-ON of disposition #5)

Concern #5's retag fix was incomplete:
- **Gemini:** `frameSizeBudgetBytes` is still `[live-tunable]` but `PerceptionCodec.encode(Frame)` allocates its own `StringBuilder` and takes no capacity argument — and Plan 5 forbids public-API change. Structurally impossible for the codec to consume this config.
- **Codex:** `queueWatermarkPct` is `[reserved]` in 20-03 but Plan 20-04 recipes describe it as a "tighter slow-client signal" and Plan 20-05 lists it as a runtime-knob tightening candidate. With no consumer wiring, changing it cannot produce a measured runtime delta.

**Recommended fix:** Apply the same Concern #5 retag to `frameSizeBudgetBytes` (in 20-03 / application.yml). Strip references to `queueWatermarkPct` from 20-04 recipe overrides and 20-05 runtime-knob candidates (or wire a real consumer + test). Roughly 5-line edits.

#### B. Pinning-dominates outcome treatment is under-specified (Codex HIGH + OpenCode MEDIUM — but reviewers DISAGREE on direction)

Plan 5 outcome 1 ("pinning-dominates") wording is ambiguous and reviewers split on what it should mean for SCALE-08 closure:
- **Codex (HIGH, FOLLOW-ON of #1/#2):** if JFR shows `jdk.VirtualThreadPinned` dominant, that is **not** a null-result — it is evidence of an unresolved overhead path. Plan 20-05 should NOT allow "file Phase 999.6 and ship Plan 5 as SCALE-08" unless SCALE-08 is explicitly downgraded. Treat outcome 1 as blocking.
- **OpenCode (MEDIUM, NEW):** "Plan 5 is paused" reads as blocking but the actual triage flow (Task 5.2 still runs; equivalence evidence is captured) supports outcome 1 as a valid Plan 5 completion. Reframe wording so Plan 6 can proceed with documented pinning finding without waiting for scope expansion.

**Divergent views — see "Divergent Views" section below.** The planner must pick a direction (block vs. proceed-with-documented-finding) and codify it explicitly in Plan 20-05 outcome 1, plus Plan 20-06 inheritance truth.

#### C. Metric capture path / noise floor (Codex HIGH + Claude LOW NEW-3)

Two related instrumentation gaps:
- **Codex (HIGH, NEW):** Plan 20-05 reads `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout` from `/tmp/p20-tuned-server.log`, but these are Micrometer meters (in `AdmissionMetrics`), not log lines. Plan 20-01b also does not persist baseline actuator metric snapshots, so 20-05/20-06 may have no baseline values for D-13/D-18 comparison.
- **Claude (LOW, NEW-3):** "tuned ≈ baseline within noise floor" is referenced by Plan 5 but noise floor itself is undefined. Executors may diverge.

**Recommended fix:** Plan 20-01b + 20-05 should explicitly snapshot `/actuator/metrics/paralife.tick.health.work-time-ms` (and other named meters) to a JSON sidecar; D-21 or Plan 5 success criteria define noise floor as e.g. `±5% of baseline mean OR ±1σ, whichever is larger`.

### Single-reviewer concerns (LOW unless noted)

- **Claude NEW-1 (LOW):** Plan 5 outcome 3 (knob tightening) doesn't require updating javadoc at `JettyDeflateCustomizer.java:69-73` if idle-timeout default changes.
- **Claude NEW-2 (LOW):** Plan 6 `wc -l 20-RUNTIME.md ≥ 350` line-count floor is filler-pressure under outcome 4 (null-result) — soften to ≥250 OR replace with section-presence grep.
- **Claude NEW-4 (LOW):** Plan 5 codec-opt cycle doesn't specify how to handle unrelated full-suite flakes (TD-22-A..D) — recommend single-retry-then-revert.
- **Codex MEDIUM (NEW):** Plan 20-06 risks overclaiming 100/500 tuned evidence as `tuned ≡ baseline` if only the 1000-bot tuned JFR was captured — D-13 requires per-tier before/after.
- **Codex LOW (NEW):** Capture commands have hazards — `profiles/` may not exist after `git checkout c22e487` (need `mkdir -p`); `build/libs/paralife-*.jar` ambiguous after `loadHarnessJar`; three sequential 60s flamegraphs may run past 180s load window.
- **Codex LOW (NEW):** 20-02 wording "defaults match Jetty defaults" is wrong — `idleTimeoutMs=60000` matches **project-current** default, not Jetty's 30s default. Test/text naming.
- **Gemini LOW (NEW):** `AppRuntimeConfigTest.d20AlongsideNotMove_admissionBackpressureUntouched` only tests `AdmissionConfig.defaults()` static factory — does not test that Spring binding preserved 128 (mitigated by 3.2 grep gate).
- **OpenCode LOW (NEW):** Plans reference `20-PATTERNS.md` in `<context>` blocks. **Verified: file exists** at `.planning/phases/20-connection-multiplexing-runtime-tuning/20-PATTERNS.md` (576 lines). Concern resolved-on-verification.
- **OpenCode LOW (NEW):** 20-03 / 20-02 YAML-merge ordering on shared `paralife.runtime.*` subtree — execution-note, not a defect.

### Divergent Views

**Pinning-dominates outcome treatment (Concern B above):** Codex says block-and-don't-claim-SCALE-08; OpenCode says proceed-as-valid-completion. Both reviewers agree the current "paused" wording is ambiguous; they disagree on what the resolved wording should encode. Worth a short ADR or explicit D-21 amendment before execute-phase.

### Risk Verdict

| Reviewer | Risk | Notes |
|----------|------|-------|
| Gemini   | LOW  | Once dead-config retag (`frameSizeBudgetBytes`) is applied, exceptionally safe. |
| Claude   | LOW  | Four NEW concerns are 5-line process/doc fixes; replan shippable as-is. |
| Codex    | HIGH | Until metric-capture path and pinning-dominates acceptance are tightened, phase could ship docs that appear to satisfy SCALE-08/09 without valid headline measurements. |
| OpenCode | LOW  | One MEDIUM language fix on outcome 1; no structural problem. |

**Aggregate verdict:** Replan is materially stronger than pass-1; three reviewers concur LOW risk, one (Codex) HIGH on two fixable issues. The two HIGH-Codex issues (metric-capture path; pinning-dominates outcome semantics) and the gemini+codex agreed dead-config follow-on warrant a short third planner pass before execution. Estimated effort: ~15 min of edits across 20-03 / 20-04 / 20-05 / 20-01b / 20-06.

### Recommended next step

```
/gsd-plan-phase 20 --reviews
```

Planner should triage: (A) extend Concern #5 retag to `frameSizeBudgetBytes` + strip `queueWatermarkPct` references; (B) pick a direction on pinning-dominates outcome semantics (block vs. proceed-with-finding) and codify in 20-05 + 20-06; (C) add actuator-metric snapshot to 20-01b/20-05 + define noise floor in D-21 or 20-05; then disposition the remaining LOW items as accepted-and-fixed or rejected-out-of-scope per the user MVP-scope rule.
