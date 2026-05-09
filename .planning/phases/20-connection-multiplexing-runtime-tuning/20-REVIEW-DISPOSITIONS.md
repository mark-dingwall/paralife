# Phase 20 — Cross-AI Review Dispositions

**Author:** /gsd-plan-phase 20 --reviews (planner)
**Date:** 2026-05-09
**Source review:** `20-REVIEWS.md` (gemini gemini-3.1-pro-preview, claude opus, codex gpt-5.5, opencode openrouter/deepseek/deepseek-v4-pro)
**Authoritative directive:** user MVP-scope rule (verbatim in `<user_directive_mvp_scope>` block of the planner prompt 2026-05-09): "if a review finding is out of scope, instead update the plan explaining why we won't do it or at least will backlog it, so the same issue isn't re-flagged in the future."

This document is **idempotent for future review passes.** Each row names the original reviewer + concern with severity tag. A subsequent `/gsd-review --phase 20` (any reviewer, any model) that re-flags an item already listed below should be marked as resolved-by-disposition rather than re-triaged.

## Summary

| Disposition | Count | Concerns |
|-------------|-------|----------|
| accepted-and-fixed | 5 | #1, #4, #5, #6 (concern #3 partially) |
| accepted-as-backlog | 2 | #2 (Phase 999.6), #3 (jfr filter alternative deferred) |
| rejected-out-of-scope | 4 | gemini ThreadLocal-at-scale, codex stale 666-tests, codex README scope-creep, gemini forced-fallback fragility (folded into #1) |
| rejected-disagree | 0 | — |

## Disposition Table

### Concern #1 — Plan 20-05 forced-fallback rule (HIGH; all 4 reviewers)

**Reviewer:** gemini (LOW), claude (HIGH), codex (HIGH), opencode (HIGH)
**Concern:** "must show non-zero improvement" rule contradicts D-10 / D-13's evidence-only stance. If the c22e487 baseline is already at performance floor, the executor faces pressure to ship a knob change because the rule says we must, not because evidence supports it.
**Disposition:** **accepted-and-fixed**
**Rationale:** This is plan-quality fix, not scope creep, and removes a perverse incentive. Concern #1 + Claude's MEDIUM "pinning-dominates supersedes forced-fallback" sub-concern + Gemini's LOW "forced-fallback fragility" sub-concern were folded into a single reframing.
**Files changed:**
- `20-CONTEXT.md` — added **D-21**: "SCALE-08 evidence acceptance permits a documented null-result"
- `20-05-PLAN.md` — Objective rewritten to enumerate four valid outcomes (pinning-dominates, codec opts, runtime-knob tightening with JFR evidence, documented null-result) in precedence order; Task 5.0 decision tree reordered to put pinning-dominates first; Task 5.1 fallback handling updated; Task 5.2 acceptance criteria allow zero-delta when outcome 4 was reached at Task 5.0; success criteria rewritten; resume-signal options updated (replaced `forced-fallback-knob` with `runtime-knob-tightened`; added `null-result`; removed `escalate-zero-delta`)
- `20-06-PLAN.md` — must_haves SCALE-08 inheritance truth updated to accept either measured-delta OR documented null-result

### Concern #2 — `synchronized(session)` → `ReentrantLock` conversion (HIGH OpenCode primary, MEDIUM gemini secondary)

**Reviewer:** opencode (HIGH primary), gemini (MEDIUM secondary)
**Concern:** RESEARCH §Pitfall 2 identifies 4 `synchronized(session)` pinning sites. OpenCode argues conversion should be a first-class Plan 5 task when JFR `jdk.VirtualThreadPinned` count > 50/min, not deferred to a scope-expansion checkpoint.
**Disposition:** **accepted-as-backlog**
**Rationale:** At MVP fidelity, the existing scope-expansion checkpoint in Plan 5 is correct: only convert if JFR baseline shows pinning > threshold. The conversion is a 4-site refactor with non-trivial test surface (synchronized-session-monitor contract per CLAUDE.md §Outbound concurrency); folding it into Plan 5 would expand scope past M4-MVP. The Plan 5 decision tree (post-#1 rewrite) explicitly invokes Phase 999.6 when pinning dominates, so a future review pass cannot re-flag this without acknowledging the disposition.
**Backlog ref:** **Phase 999.6** — `vt-pinning-reentrantlock-conversion` (CONTEXT.md stub created at `.planning/phases/999.6-vt-pinning-reentrantlock-conversion/CONTEXT.md`)
**Files changed:**
- `20-05-PLAN.md` decision-tree outcome 1 explicitly references "file Phase 999.6 (`vt-pinning-reentrantlock-conversion`) for the deferred work"
- `.planning/phases/999.6-vt-pinning-reentrantlock-conversion/CONTEXT.md` — new placeholder; lifts on JFR evidence

### Concern #3 — JFR file-size budget too tight (MEDIUM; codex + claude)

**Reviewer:** codex (MEDIUM), claude (MEDIUM)
**Concern:** ≤5 MB/file, ≤20 MB total is unrealistic for 1000-bot 180s `settings=profile`. Aggressive `jfr filter` to meet the cap risks dropping the events Plan 5 needs (allocation/pinning).
**Disposition:** **accepted-and-fixed** (relax-cap variant; `.jfc` filter alternative deferred)
**Rationale:** Smaller change (cap relaxation), no new artefacts to commit. Per user directive: "prefer relaxing the cap (smaller change, no new artefacts)."
**Files changed:**
- `20-01-PLAN.md` — size discipline references and acceptance text updated from `≤5 MB/file, ≤20 MB total` → `≤10 MB/file, ≤50 MB total`
- `20-01b-PLAN.md` — same; the automated find/du checks updated (`-size +5M` → `-size +10M`; `20971520` → `52428800` bytes)
- `20-04-PLAN.md` — Profile Index size column entries updated `_≤5 MB_` → `_≤10 MB_`
- `20-05-PLAN.md` — Task 5.2 capture verification updated `≤5 MB` → `≤10 MB`
- D-05 (in 20-CONTEXT.md) text relaxed to `≤10 MB per artifact, ≤50 MB total` — D-05 explicitly authorised this via "exact bounds = Claude's Discretion during planning"; the change is the discretion call, recorded in CONTEXT for future reference.

**Deferred:** committing `tools/p20-profile.jfc` filter to preserve `jdk.ObjectAllocationInNewTLAB`, `jdk.GCPhasePause`, `jdk.VirtualThreadPinned`, `jdk.JavaMonitorEnter` at full fidelity. If the relaxed cap still proves insufficient at execute-time (1000-bot 180s actually generates >10 MB even at relaxed cap), the executor escalates back to the orchestrator and we ship the `.jfc` filter as a follow-up edit. Tracking note: this is not a separate backlog item; it's a fail-open path.

### Concern #4 — Plan 20-02 legacy idle-timeout fallback untested (MEDIUM; codex + claude)

**Reviewer:** codex (MEDIUM), claude (MEDIUM)
**Concern:** With primitive `@DefaultValue("60000")`, the record cannot distinguish "explicitly set to 60000" from "defaulted." `effectiveIdleMs` resolution logic ("new key wins if both set") cannot be reliably implemented in all cases. Convergent fix: add Spring slice tests covering all four yaml-set combinations.
**Disposition:** **accepted-and-fixed**
**Rationale:** A single small-cost test pins current behaviour so future operators relying on the legacy key for one phase don't silently regress. Acknowledges the primitive-default detection limitation explicitly rather than scope-creeping into Spring `Binder` / nullable-wrapper migration (which is what Codex recommended as the alternative).
**Files changed:**
- `20-02-PLAN.md` — added **Task 2.3** that creates `src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java` with 4 nested test classes covering cases A (neither), B (new-only), C (legacy-only), D (both); acknowledges the primitive-default detection limitation in its `<behavior>` block. Updated Task 2.2 to extract `JettyDeflateCustomizer.resolveEffectiveIdleMs` as a package-private helper so the test can call it directly without bean-lifecycle plumbing. `files_modified` frontmatter updated.

**Rejected scope-expansion alternative:** Codex's "use Spring `Binder` or nullable wrapper to detect explicit-set" — out of scope. The 4-case test pins observable behaviour. If a future operator needs perfect explicit-set detection for the legacy key, that's a Phase 999.4 namespace-consolidation task, not a Phase 20 fix.

### Concern #5 — Reserved/unconsumed config knobs labelled `[live-tunable]` (MEDIUM; claude + opencode + codex)

**Reviewer:** claude (LOW labelled MEDIUM-aggregate), opencode (MEDIUM), codex (MEDIUM)
**Concern:** `queueWatermarkPct`, `encodeBatchHint`, `parallelEncodeThreshold` bind but no Phase-20 consumer reads them. Defensible as live-tunable seam but creates "dead-config" risk for operators who tune them and see no effect.
**Disposition:** **accepted-and-fixed**
**Rationale:** Pure labelling change; zero risk; closes the operator-confusion gap.
**Files changed:**
- `20-03-PLAN.md` — yaml comments + javadoc tags + acceptance grep updated:
  - `queue-watermark-pct: 80` → `[reserved — no effect in Phase 20] slow-client watermark; consumer wiring deferred to M5 admin UI`
  - `parallel-encode-threshold: -1` → `[reserved — no effect in Phase 20] Phase 19.1 follow-up consumer; -1 = disabled`
  - `encode-batch-hint: 8` → `[reserved — no effect in Phase 20] tick-broadcast batch sizing hint; consumer wiring deferred`
  - `frame-size-budget-bytes: 1024` retains `[live-tunable]` (only field with potential Phase-20 codec opts pathway)
  - acceptance grep updated to count `[live-tunable|[reserved` ≥4
- `20-04-PLAN.md` §2.2 RUNTIME table — same three fields retagged; `frameSizeBudgetBytes` retains `[live-tunable]`

### Concern #6 — Plan 20-02 dependency mismatch (MEDIUM; codex)

**Reviewer:** codex (MEDIUM single but well-evidenced)
**Concern:** Plan 20-02 declares `depends_on: [20-01]` but its compile/wiring relies on Plan 20-01b's A1 verification (Jetty 12.0.18 setter availability).
**Disposition:** **accepted-and-fixed** (frontmatter fix variant)
**Rationale:** Trivially correct one-line frontmatter fix; cheaper than moving A1 into 20-01.
**Files changed:**
- `20-02-PLAN.md` frontmatter: `depends_on: [20-01]` → `depends_on: [20-01b]`

This bumps Plan 20-02 from Wave 2 (parallel with 20-01b) into Wave 2-or-later (gated by 20-01b's baseline capture). Wave numbers in frontmatter remain `wave: 2` because 20-01b is also wave 2 and the wave field reflects max-of-deps wave; the orchestrator's wave assignment can run 20-01b and 20-02 sequentially within wave 2 OR upgrade 20-02 to wave 3 — the executor sees the explicit dep and waits on 20-01b's SUMMARY before starting Task 2.1's "Reading from Plan 1's summary" step. No further changes required because that step is already in Task 2.1's `<read_first>` and the dep is now declared.

**Iteration 1 follow-up (planner-checker WARN-2, 2026-05-09):** the plan-checker re-flagged the wave-numbering inconsistency. The canonical rule is `wave = max(deps_waves) + 1`. Decision: **bump-cascade** rather than in-frontmatter-comment. Updated:
- `20-02-PLAN.md`: `wave: 2` → `wave: 3` (deps include 20-01b which is wave 2)
- `20-04-PLAN.md`: `wave: 3` → `wave: 4` (deps include 20-02 which is now wave 3)
- `20-05-PLAN.md`: `wave: 3` → `wave: 4` (deps include 20-02 which is now wave 3)
- `20-06-PLAN.md`: `wave: 4` → `wave: 5` (deps include 20-04 + 20-05 which are now wave 4)

Rationale: cleaner long-term — canonical numbering, no executor-strictness risk, no comment-as-load-bearing-rationale. The previous "stay at wave 2 sequential within wave" framing relied on orchestrator-side judgement; the bumped numbering is self-documenting. Future review passes that re-flag this MUST be marked resolved-by-disposition.

### Rejected: Gemini MEDIUM concern — VT `ThreadLocal` pooling at higher scales

**Reviewer:** gemini (MEDIUM)
**Concern:** `ThreadLocal<StringBuilder>` pins memory to long-lived `OutboundSender` VTs; runs counter to VT philosophy at scales like "millions of VTs."
**Disposition:** **rejected-out-of-scope**
**Rationale:** Paralife's stated design ceiling is **5,000 connections per JVM** (Phase 18 D-02 in `18-CONTEXT.md`; codified in `CLAUDE.md` §Connection model). At that scale, ThreadLocal-per-VT is fine — 5,000 × ~200 bytes (capacity-tuned StringBuilder) ≈ 1 MB heap, negligible vs the 2GB heap allocation in §3.3 1000-bot recipe. Gemini's concern applies at "millions of VTs," not at Paralife's design envelope. If Plan 5 picks the `ThreadLocal<StringBuilder>` opt and a future Phase 21 benchmark shows it's actually a problem, that's tracked in Phase 999.5 (P20 baseline JFR re-run).

### Rejected: Codex LOW concern — stale "666 tests" numeric claim

**Reviewer:** codex (LOW)
**Concern:** Plans should not repeat any "666 tests" claim; current count is 136 test files per CONTEXT.
**Disposition:** **rejected-out-of-scope** (already-resolved)
**Rationale:** Already addressed in 20-CONTEXT.md D-12 ("Stale '166+ tests' figure dropped; current count is 136 test files"). Verified: no Phase 20 PLAN.md mentions a stale numeric test count. No further action required. If future review re-flags this, point to D-12's note.

### Rejected: Codex LOW concern — README replacement scope-creep risk in Plan 6

**Reviewer:** codex (LOW)
**Concern:** Phase 20's objective is tuning, not documentation rewrite; Plan 6 should keep the README minimal.
**Disposition:** **rejected-out-of-scope** (already-minimal)
**Rationale:** The existing README replacement in `20-06-PLAN.md` Task 6.3 is scoped per CONTEXT D-16 (one operator paragraph) plus minimal scaffolding (project name + one-line value-prop + build commands + project layout + the Runtime tuning paragraph). Per user directive 5: "the existing README replacement is one-line per CONTEXT. As long as it stays one paragraph, fine." The Task 6.3 template is ~30 lines total — well within "minimal but readable structure" framing. No further action.

### Rejected: Gemini LOW concern — forced-fallback fragility if no hot path found

**Reviewer:** gemini (LOW)
**Concern:** If no codec hot path exists, the noise floor might mask any small gains, causing the agent to loop or escalate unnecessarily.
**Disposition:** **rejected-out-of-scope** (folded into #1)
**Rationale:** Concern #1's reframing (D-21 + Plan 5 four-outcome decision tree) directly resolves this — outcome 4 (documented null-result) makes the concern moot. Do not duplicate.

## Idempotency contract (for future review passes)

A future `/gsd-review --phase 20` (any reviewer, any model) re-flagging any of the six concerns above MUST be marked **resolved-by-disposition** during triage. Re-flagging is acceptable ONLY if:
- The disposition was `accepted-as-backlog` AND the backlog item has been promoted (Phase 999.6 lifted) AND new evidence in subsequent phases (P21 benchmark) materially changes the cost/benefit calculus.
- The disposition was `rejected-disagree` (none in this pass) AND new technical evidence contradicts the disagree rationale.

The grep gate for review automation: `grep -lE "Concern #[1-6]|reviewer.*(opus|gemini|codex|opencode)" .planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md` MUST hit this file. Future review tooling reading this file should suppress already-disposed findings.

---

*Phase 20 — review dispositions · planner-signed-off 2026-05-09*
