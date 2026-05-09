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
  - `frame-size-budget-bytes: 1024` retains `[live-tunable]` (only field with potential Phase-20 codec opts pathway) — **NB: pass-2 Concern #7 retags this to `[reserved]` after gemini+codex showed Plan 5's no-API-change rule makes the field structurally unconsumable**
  - acceptance grep updated to count `[live-tunable|[reserved` ≥4
- `20-04-PLAN.md` §2.2 RUNTIME table — same three fields retagged; `frameSizeBudgetBytes` retains `[live-tunable]` (also retagged in pass-2 #7)

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

## Pass-2 Dispositions (2026-05-09 evening — re-review after pass-1 disposition cycle)

**Source review:** `20-REVIEWS.md` pass 2 (gemini gemini-3-pro-preview, claude opus, codex gpt-5.5 high effort, opencode openrouter/deepseek/deepseek-v4-pro)
**Pass-2 trigger:** orchestrator re-ran cross-AI review after pass-1 dispositions landed. Three reviewers (gemini, claude, opencode) returned LOW risk; codex returned HIGH on two specific issues (metric-capture path + pinning-dominates outcome semantics).

### Pass-2 Summary

| Disposition | Count | Concerns |
|-------------|-------|----------|
| accepted-and-fixed | 11 | #7 (frameSizeBudgetBytes retag), #8 (queueWatermarkPct strip), #9 (pinning-dominates wording + D-21 amend), #10 (actuator metric capture path), #11 (noise-floor convention), #12 (javadoc-update on knob change), #13 (line-count floor tier), #14 (full-suite flake handling), #15 (capture-script hazards), #16 (Plan 2 wording fix), #17 (Plan 6 100/500 baseline-only framing) |
| rejected-out-of-scope | 2 | gemini LOW NEW (D-20 invariant in BindingRoundTripTest — mitigated by 3.2 grep gate), opencode LOW NEW (YAML merge fragility — execution-note, Read-first instruction adequate) |
| resolved-on-verification | 1 | opencode LOW NEW (20-PATTERNS.md exists — verified at 576 lines on filesystem) |
| rejected-disagree | 1 | codex HIGH on pinning-dominates blocking SCALE-08 — adopted OpenCode's reading per D-21 amendment (#9) |

### Concern #7 — `frameSizeBudgetBytes` still tagged `[live-tunable]` despite no consumer (MEDIUM; gemini + codex aggregated; FOLLOW-ON of pass-1 #5)

**Reviewer:** gemini (MEDIUM, FOLLOW-ON of pass-1 #5)
**Concern:** Plan 5 forbids public-API change on `PerceptionCodec.encode(Frame)`, but the codec allocates its own `StringBuilder` internally and takes no capacity argument. `frameSizeBudgetBytes` is structurally unconsumable in Phase 20 — same dead-config problem as `queueWatermarkPct` / `encodeBatchHint` / `parallelEncodeThreshold`.
**Disposition:** **accepted-and-fixed** (extends pass-1 #5 retag to the fourth field; same logic, same shape).
**Rationale:** Pure labelling change; closes the operator-confusion gap; same MVP discipline as pass-1 #5.
**Files changed:**
- `20-03-PLAN.md` — `OutboundConfig.frameSizeBudgetBytes` javadoc updated `[live-tunable]` → `[reserved — no effect in Phase 20] codec sizing hint; consumer wiring deferred (PerceptionCodec.encode(Frame) takes no capacity argument; would require API change forbidden by Plan 5)`; yaml comment updated; acceptance grep updated to count all 4 fields as `[reserved`.
- `20-04-PLAN.md` §2.2 RUNTIME table — `frameSizeBudgetBytes` row retagged `[reserved — no effect in Phase 20]`.

### Concern #8 — `queueWatermarkPct` referenced as effective in Plan 4/5 despite `[reserved]` tag in Plan 3 (MEDIUM; codex; FOLLOW-ON of pass-1 #5)

**Reviewer:** codex (MEDIUM, FOLLOW-ON of pass-1 #5)
**Concern:** Pass-1 #5 retagged `queueWatermarkPct` `[reserved]` in 20-03, but 20-04 §3.2 / §3.3 recipe overrides describe it as "tighter slow-client signal" and 20-05 Task 5.0 lists it as a runtime-knob-tightening candidate (alongside `paralife.outbound.detach.timeout` evidence). With no consumer wiring, changing it cannot produce a measured runtime delta.
**Disposition:** **accepted-and-fixed** (strip from recipes + runtime-knob candidate list; explicitly annotate as reserved).
**Rationale:** Wiring a real consumer in Phase 20 expands scope past MVP; the cleanest fix is to stop pretending the knob is effective. Recipe overrides that "do nothing" mislead Phase 21 benchmark scripts that consume the recipes verbatim.
**Files changed:**
- `20-04-PLAN.md` — §3.2 (500-bot tier) and §3.3 (1000-bot tier) yaml override examples: `queue-watermark-pct` line removed from the override blocks; replaced with comment line `# (paralife.runtime.app.outbound.queue-watermark-pct is [reserved — no effect in Phase 20] — see 20-RUNTIME.md §2.2)`.
- `20-05-PLAN.md` — Task 5.0 outcome-3 candidate-knob table: `queue-watermark-pct` row removed; replaced with note `# queue-watermark-pct is [reserved — no Phase 20 consumer per Plan 3 + Concern #8 disposition]`.

### Concern #9 — Pinning-dominates outcome 1 wording + SCALE-08 closure semantics (HIGH; codex vs opencode divergent)

**Reviewers:** codex (HIGH, FOLLOW-ON of pass-1 #1/#2 — argues outcome 1 should NOT close SCALE-08); opencode (MEDIUM, NEW — argues outcome 1 IS valid completion, "paused" wording is the bug).
**Concern:** Plan 5 outcome 1 ("pinning-dominates → file Phase 999.6 + ship") is ambiguous. Codex reads it as overclaiming SCALE-08 closure with an unresolved overhead path. OpenCode reads the actual flow (Task 5.2 still runs equivalence capture) as a valid completion with bad framing.
**Disposition:** **accepted-and-fixed** for OpenCode's reading + amend D-21 to enumerate it explicitly. **rejected-disagree** for Codex's "block-and-don't-claim-SCALE-08" reading.
**Rationale (the planner adjudicates):** Per CONTEXT.md D-21 the tuning surface (Plan 2 + Plan 3 records + Plan 4 per-tier recipes) IS the SCALE-08 deliverable; SCALE-09 demands measured tuning, and a documented null-result is a measurement. Pinning-dominates with backlog-handoff is structurally identical to outcome 4 (null-result): the system is at the relevant performance floor for the work Plan 5 is permitted to do, the binding constraint is documented with JFR evidence, the unresolved overhead path is filed as Phase 999.6, and the equivalence capture (Task 5.2) proves the codebase is in the same state. Codex's reading would require either downgrading SCALE-08 (rejected — D-21 is sound) OR expanding Plan 5 scope to include the `synchronized → ReentrantLock` conversion (rejected — that's pass-1 #2 disposition Phase 999.6 backlog). The remaining work is wording: "paused" → "proceeds with documented pinning finding". This adopts OpenCode's reading and codifies it in D-21 so a future PASS-3 reviewer cannot re-flag without acknowledging the disposition.
**Files changed:**
- `20-CONTEXT.md` D-21 — amended to explicitly enumerate "(4) **dominant pinning with backlog-handoff** — JFR shows `jdk.VirtualThreadPinned` is the binding constraint AND the conversion work is filed as Phase 999.6 AND the tuned-state JFR confirms equivalence" as a fourth valid outcome alongside the three from pass-1 (codec opts, runtime-knob tightening, documented null-result). The four outcomes (with pinning-dominates promoted to a peer of null-result, not a blocker) are the SCALE-08 evidence-acceptance set. Noise-floor convention added in same edit (Concern #11).
- `20-05-PLAN.md` Task 5.0 outcome 1 — wording "Plan 5 is paused. The orchestrator decides whether to expand scope" replaced with "Plan 5 proceeds to Task 5.2 with the documented pinning finding (Phase 999.6 stub captures the deferred ReentrantLock conversion). The pinning evidence + tuned-state equivalence capture together close SCALE-08 per D-21 outcome 4 (dominant pinning with backlog-handoff). The orchestrator may in parallel decide to expand scope via a separate plan, but Plan 5 does not block on that decision."
- `20-05-PLAN.md` Task 5.1 outcome 1 branch — "Plan 5 is paused" wording replaced with the same "proceeds to Task 5.2 with documented finding" framing; the `synchronized → ReentrantLock` conversion remains explicitly out-of-scope-for-Plan-5 (pass-1 #2 disposition intact).
- `20-05-PLAN.md` resume-signal options — `pinning-dominates` reframed as `pinning-dominates-with-backlog-handoff: <count>/min @ <synchronized site>; Phase 999.6 stub cited`.
- `20-06-PLAN.md` must_haves SCALE-08 inheritance truth — text amended to enumerate all four valid Plan 5 outcomes (codec opts / runtime-knob tightening / documented null-result / dominant pinning with backlog-handoff) explicitly per D-21.

### Concern #10 — Headline metric capture path is not executable (HIGH; codex; NEW)

**Reviewer:** codex (HIGH, NEW)
**Concern:** Plan 5 Task 5.2 step 7 reads `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout` from `/tmp/p20-tuned-server.log`, but these are Micrometer meters registered in `AdmissionMetrics.java:65` and `:74`, not log lines. They are exposed via Spring Boot Actuator at `/actuator/metrics/{name}` (verified: `application.yml` line 11-15 exposes `metrics` endpoint). Plan 20-01b also does not snapshot baseline actuator metrics, so 20-05 / 20-06 may have no baseline values for D-13 / D-18 comparison.
**Disposition:** **accepted-and-fixed** (real correctness gap — without baseline metric values, D-13/D-18 inheritance truths in Plan 6 are unverifiable).
**Rationale:** This is the cheapest path to actually-measurable headline numbers. Actuator is already wired (`management.endpoints.web.exposure.include: health,info,metrics` in `application.yml:15`); Plan 20-01b just needs to `curl` the named meters into a JSON sidecar during the baseline capture window, and Plan 20-05 Task 5.2 needs to do the same against the tuned-state run. No code change required. Periodic sampling during the 180s capture window gives mean + max for the work-time-ms gauge.
**Files changed:**
- `20-01b-PLAN.md` Task 1b.0 — baseline capture ritual extended: between step 4 (flamegraphs) and step 5 (JFR verify), add step 4b "snapshot actuator metrics" that runs a 30-second sampling loop (`curl -s http://localhost:8080/actuator/metrics/paralife.tick.health.work-time-ms` + `paralife.outbound.detach.timeout` every 5s during the steady-state portion of the load window) and writes results to `profiles/metrics-{N}bots-baseline-c22e487.json` sidecars (one per tier). `files_modified` frontmatter extended to include the three new metric sidecar paths.
- `20-05-PLAN.md` Task 5.2 step 7 — replace `grep -E "tick.health.work-time-ms|outbound.detach.timeout" /tmp/p20-tuned-server.log` with the same actuator-curl sampling loop targeting `metrics-1000bots-tuned-${HEAD_SHA}.json` sidecar; document the JSON shape (`measurements` array with `statistic: VALUE` + `value` keys per Spring Boot Actuator's standard format) so Plan 6 §4.2 has a parseable source.
- `20-05-PLAN.md` `files_modified` frontmatter — extend to include `metrics-1000bots-tuned-HEAD.json`.

### Concern #11 — Noise floor undefined in Plan 5 outcome verification (LOW; claude; NEW-3)

**Reviewer:** claude (LOW, NEW-3)
**Concern:** Plan 5 Task 5.2 outcome-4 acceptance says "tuned ≈ baseline within noise floor" and outcome-2/3 says "below noise floor → document the noise-floor evidence and proceed", but noise floor is never defined. Without a concrete threshold, executors may diverge.
**Disposition:** **accepted-and-fixed** (one-line convention added to D-21).
**Rationale:** Convention rather than per-plan repetition keeps it discoverable; D-21 is the canonical SCALE-08 evidence-acceptance decision.
**Files changed:**
- `20-CONTEXT.md` D-21 — appended sentence: **"Noise floor convention: ±5% of baseline mean OR ±1σ, whichever is larger, computed across the JFR sample window."** Plan 5 success criteria + Plan 6 §4.2 cite this convention by reference.

### Concern #12 — Plan 5 outcome 3 (knob tightening) doesn't require updating javadoc on default change (LOW; claude; NEW-1)

**Reviewer:** claude (LOW, NEW-1)
**Concern:** If outcome 3 fires and the executor tightens `paralife.runtime.jetty.idle-timeout-ms` default away from 60000, the existing `JettyDeflateCustomizer.java:69-73` javadoc rationale ("Idle timeout is raised from Jetty's 30s default to ... 60s ... defensive belt to the keepalive service's braces") becomes inaccurate. Plan 5 Task 5.1 outcome-3 path lists the change as "config-only" but doesn't require updating that javadoc.
**Disposition:** **accepted-and-fixed** (one bullet added to Task 5.1 outcome-3 acceptance).
**Rationale:** Trivial doc-drift mitigation; future contributors should see the JFR signal that justified the change at the binding site.
**Files changed:**
- `20-05-PLAN.md` Task 5.1 outcome 3 branch — appended bullet: "If the chosen knob has javadoc/comment at the binding site (e.g., `JettyDeflateCustomizer.java:69-73` for `idleTimeoutMs`, `AdmissionConfig.java:114` for `outboundQueueSize`), update that javadoc/comment to reflect the new default + cite the JFR signal that justified the change. Comment-only change; no code behaviour diff."

### Concern #13 — Plan 6 line-count floor `≥350` is filler-pressure under outcome 4 null-result (LOW; claude; NEW-2)

**Reviewer:** claude (LOW, NEW-2)
**Concern:** Plan 6 Task 6.1 acceptance requires `wc -l 20-RUNTIME.md ≥ 350 lines`. Under outcome 4 (true null-result), §4.3 per-tier narrative and §4.4 codec opts would be substantively sparse. The 350-line floor risks pushing the executor toward filler text rather than recording "we measured, system is at floor, no opt shipped, here's the evidence" concisely.
**Disposition:** **accepted-and-fixed** (tier the floor by Plan 5 outcome).
**Rationale:** Honesty over volume — a concise null-result write-up is correct; padding it to 350 lines is an anti-pattern.
**Files changed:**
- `20-06-PLAN.md` Task 6.1 acceptance — line-count rule changed from `wc -l ≥ 350` to tiered: `≥ 350 lines for outcomes 1/2/3 (codec opts, runtime-knob tightening, dominant pinning with backlog-handoff — substantial narrative justified); ≥ 250 lines for outcome 4 (documented null-result — concise write-up correct; padding to 350 is anti-pattern). Outcome is determined by Plan 5's resume-signal recorded in 20-05-SUMMARY.md.`

### Concern #14 — Plan 5 codec-opt cycle doesn't specify full-suite flake handling (LOW; claude; NEW-4)

**Reviewer:** claude (LOW, NEW-4)
**Concern:** Plan 5 Task 5.1 codec-opt cycle: Run 1 → Run 2 → full suite. If full suite flakes on an unrelated test (e.g., a `forkEvery=1` carrier-starvation timer or one of the TD-22-A..D tests not yet `@Disabled`), the plan doesn't say whether to revert the codec change or retry the full suite.
**Disposition:** **accepted-and-fixed** (one-sentence single-retry-then-revert protocol).
**Rationale:** Aligns with TD-19.5-A in-suite-only convention already in place for the three-gate stack.
**Files changed:**
- `20-05-PLAN.md` Task 5.1 step 4 — appended sentence: "If the full suite reports failures unrelated to the codec opt (per Phase 22 known flake list TD-22-A..D, OR a test outside `src/main/java/com/paralife/codec/` that the opt does not touch), retry the full suite once before reverting; if the same failure recurs, revert the opt and skip. This single-retry-then-revert protocol mirrors the TD-19.5-A in-suite convention for the three-gate stack."

### Concern #15 — Capture commands have execution hazards (LOW; codex; NEW)

**Reviewer:** codex (LOW, NEW)
**Concern:** After `git checkout c22e487`, the `profiles/` directory may not exist, so `JFR_OUT=.../profiles/...` can fail without `mkdir -p`. `build/libs/paralife-*.jar` is ambiguous after `loadHarnessJar` has built the harness jar (matches both). Three sequential 60s flamegraphs can run past the 180s load window.
**Disposition:** **accepted-and-fixed** (3 small edits to 20-01b commands).
**Rationale:** Real shell hazards; cheap to harden.
**Files changed:**
- `20-01b-PLAN.md` Task 1b.0 step 1 — append `mkdir -p .planning/phases/20-connection-multiplexing-runtime-tuning/profiles` after the `git checkout c22e487` line so the JFR_OUT path is creatable on the pinned SHA (the directory may not exist on c22e487 since it's created by Plan 20-01).
- `20-01b-PLAN.md` Task 1b.0 step 2 — replace `-jar build/libs/paralife-*.jar` with `SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1) && java ... -jar "$SERVER_JAR"`. Same fix applied to step 3 harness invocation: `HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1) && java -jar "$HARNESS_JAR" ...`.
- `20-01b-PLAN.md` Task 1b.0 step 4 — append note: "If running 3 sequential 60s flamegraph captures (cpu/alloc/lock = 180s), extend the harness `--duration` to 200 seconds (was 180) so the flamegraph window stays inside the active load window with a 20s margin. Alternatively, run the three captures in parallel via `&` and `wait` — async-profiler supports concurrent attach." Same `SERVER_JAR` / `HARNESS_JAR` glob hardening applied to 20-05 Task 5.2 capture commands.
- `20-05-PLAN.md` Task 5.2 step 2-4 — apply the same `SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1)` / `HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1)` glob hardening; the directory is now guaranteed to exist (Plan 20-01 + 20-01b created it), so no `mkdir -p` needed in Plan 5.

### Concern #16 — Plan 20-02 wording "defaults match Jetty defaults" misleading (LOW; codex; NEW)

**Reviewer:** codex (LOW, NEW)
**Concern:** Plan 20-02 says defaults match Jetty defaults, but `idleTimeoutMs=60000` matches the **project-current** default (the legacy `paralife.websocket.idle-timeout-ms` was already 60000), not Jetty's 30s default. The behavior is right; the text is misleading.
**Disposition:** **accepted-and-fixed** (rename test/text to "project-current defaults").
**Rationale:** Cheap clarity fix; prevents future contributors from "fixing" the 60000 to 30000 thinking it was a typo.
**Files changed:**
- `20-02-PLAN.md` — multiple wording fixes:
  - must_haves truths: "default values matching Jetty 12.0.18 defaults" → "default values matching project-current defaults (Jetty 12.0.18 defaults except `idleTimeoutMs=60000` which inherits the project's pre-existing legacy `paralife.websocket.idle-timeout-ms` value)"
  - Task 2.1 javadoc paragraph: "Defaults match Jetty 12.0.18 defaults" → "Defaults match project-current defaults — Jetty 12.0.18 defaults except `idleTimeoutMs=60000` which inherits the project's pre-existing legacy `paralife.websocket.idle-timeout-ms` value (Jetty's own default is 30000)."
  - Task 2.1 record field javadoc on `idleTimeoutMs`: existing comment already says "Jetty default: 30000; project: 60000" — that line is correct; no edit needed.
  - Task 2.1 behavior Test 1 name: `defaultsMatchJetty12Defaults` → `defaultsMatchProjectCurrentDefaults`; assertion comment line clarified.

### Concern #17 — Plan 6 risks overclaiming 100/500 tuned evidence as `tuned ≡ baseline` (MEDIUM; codex; NEW)

**Reviewer:** codex (MEDIUM, NEW)
**Concern:** Plan 6 Task 6.1 §4.2 fill instruction says "if 100-bot and 500-bot baselines weren't re-captured against tuned state, state 'tuned ≡ baseline' in those cells". But D-13 requires per-tier before/after evidence, and Plan 5 changes can affect all tiers. Equivalence at 100/500 should be measured or explicitly not claimed.
**Disposition:** **accepted-and-fixed** (revise framing — 100/500 are baseline-only tiers; 1000-bot is the tuned-evidence tier).
**Rationale:** Cheaper than expanding 20-05 to capture tuned 100/500 JFRs (which would push Plan 5 well past MVP). The honest framing is: 1000-bot is the M4 target tier; 100/500 baselines are reproducibility anchors and Phase 21 inputs, not Phase 20 tuning targets. D-13 inheritance truth applies to the tier where tuning is shipped (1000-bot) — any incidental effect at 100/500 is captured via Phase 21's full benchmark gate, not Phase 20.
**Files changed:**
- `20-06-PLAN.md` Task 6.1 §4.2 fill instruction — replace the "if not re-captured, state tuned ≡ baseline" sentence with: "100-bot and 500-bot tiers are baseline-only in Phase 20 (the M4 target is 1000 bots; per-tier benchmark evidence is Phase 21's deliverable). State '_baseline-only — see Phase 21_' in the 100-tuned and 500-tuned cells. The 1000-tuned column carries the headline numbers from Plan 5's tuned-state actuator metric sidecar (`profiles/metrics-1000bots-tuned-${HEAD_SHA}.json`). D-13 per-tier evidence requirement applies to the tier where tuning is shipped; incidental effects at lower tiers are Phase 21's gate."
- `20-06-PLAN.md` must_haves SCALE-08 inheritance truth — extended with clarifying note: "Per-tier evidence concentrates at the 1000-bot tier; 100/500-bot baselines are reproducibility anchors handed off to Phase 21."

### Rejected: gemini LOW NEW — D-20 invariant test only checks AdmissionConfig.defaults() static factory

**Reviewer:** gemini (LOW, NEW)
**Concern:** `AppRuntimeConfigTest.d20AlongsideNotMove_admissionBackpressureUntouched` instantiates `AdmissionConfig.defaults()` to check queue size 128. This only tests the static factory; it does not test whether Spring actually avoided shadowing/overwriting during YAML binding.
**Disposition:** **rejected-out-of-scope** (mitigated by 3.2 grep gate).
**Rationale:** Gemini's own note: "the `grep` in Task 3.2 mitigates this risk in practice, making it a low severity issue". The Task 3.2 acceptance includes `grep -A60 "^paralife:" src/main/resources/application.yml | grep -q "outbound-queue-size"` which verifies the admission key is still in yaml. Moving the assertion into `BindingRoundTripTest` would require autowiring `AdmissionConfig` alongside `AppRuntimeConfig` in a test that's deliberately scoped to `AppRuntimeConfig.class` only — the autowiring rework expands test scope past MVP necessity. The combination of (a) the grep gate at the yaml layer, (b) `AdmissionConfig.defaults()` returning 128 (proves no shadowing in the record itself), and (c) the existing admission/backpressure regression sweep run in Task 3.2 (proves binding still works at the application level) is sufficient. If a future operator reports D-20 violation, the gap is at the application-level binding test, which lives under existing admission/backpressure test coverage, not under AppRuntimeConfigTest's scope.

### Rejected: opencode LOW NEW — YAML merge fragility on shared `paralife.runtime.*` subtree

**Reviewer:** opencode (LOW, NEW)
**Concern:** 20-03 Task 3.2 instructs "if Plan 2 has not yet committed the `paralife.runtime.jetty:` block, the executor must Read the file first and create the parent `paralife: runtime:` skeleton if absent". Creates ordering sensitivity between two files adding to the same YAML subtree.
**Disposition:** **rejected-out-of-scope** (execution-note, not defect; Read-first instruction adequate).
**Rationale:** OpenCode's own note: "No code change needed — the Read-first instruction is adequate." The YAML structure is well-understood; both Plan 2 and Plan 3 already include `<read_first>` directives that load the full `application.yml` file. Wave-2 ordering puts both plans in parallel by design (no shared file would force sequential), so the executor MUST Read the current state before edit regardless. Adding a defensive comment in either plan would be redundant with the Read-first instruction already present.

### Resolved-on-verification: opencode LOW NEW — 20-PATTERNS.md referenced but not provided

**Reviewer:** opencode (LOW, NEW)
**Concern:** All seven plans reference `@.planning/phases/20-connection-multiplexing-runtime-tuning/20-PATTERNS.md` in `<context>` or `<read_first>` blocks; if file doesn't exist, executor will hit missing reference.
**Disposition:** **resolved-on-verification** (filesystem check confirms the file exists at 576 lines).
**Rationale:** Verified via `ls -la .planning/phases/20-connection-multiplexing-runtime-tuning/20-PATTERNS.md` — file exists. No edit required. OpenCode's pass-2 review packet did not include the file but the orchestrator's planner-prompt files_to_read block does include it; this was a review-packet completeness issue, not a plan defect.

## Idempotency contract (for future review passes)

A future `/gsd-review --phase 20` (any reviewer, any model) re-flagging any of the concerns above (#1–#17 plus the rejected items) MUST be marked **resolved-by-disposition** during triage. Re-flagging is acceptable ONLY if:
- The disposition was `accepted-as-backlog` AND the backlog item has been promoted (Phase 999.6 lifted) AND new evidence in subsequent phases (P21 benchmark) materially changes the cost/benefit calculus.
- The disposition was `rejected-disagree` (Concern #9 codex-on-pinning is the only one in this set) AND new technical evidence contradicts the disagree rationale.
- The disposition was `resolved-on-verification` (opencode pass-2 PATTERNS.md) AND the underlying artifact has been removed/changed.

The grep gate for review automation: `grep -lE "Concern #[0-9]+|reviewer.*(opus|gemini|codex|opencode)" .planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md` MUST hit this file. Future review tooling reading this file should suppress already-disposed findings. (Pass-2 widens the regex from `#[1-6]` to `#[0-9]+` to cover the 17+ concerns now disposed.)

---

*Phase 20 — review dispositions · planner-signed-off 2026-05-09 (pass-1 morning + pass-2 evening)*
