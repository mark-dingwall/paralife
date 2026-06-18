---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T13:14:14Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md"]
models:
  codex: "gpt-5.5"
usage:
  claude: { input: 770790, output: 87, cached: 5163154, tool_calls: 13, elapsed_s: 504.8 }
  gemini: { input: 1367875, output: 1630, cached: 1175127, tool_calls: 13, elapsed_s: 316.0 }
  codex: { input: 858692, output: 8729, cached: 722304, tool_calls: 27, elapsed_s: 182.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 38, elapsed_s: 500.8 }
synthesizer: claude
synthesized_at: 2026-06-04T13:14:14Z
fallbacks:
  gemini:
    attempts: ["gemini-3.1-pro-preview", "gemini-3-flash-preview"]
    used: "gemini-3-flash-preview"
---

# Cross-AI Review

## Claude Review

Verification complete. All cited SHAs resolve correctly (`ddf0dfa`=PR#1 merge, `870440d`=EXEC-R1, `c22e487`=19.1-close anchor, etc.). Footer date placeholder confirmed. Writing the review.

---

# Pre-Execution Review: 20-06-PLAN.md (Paralife Phase 20, final plan)

Verified against the live repo at HEAD `4887739`. Every acceptance grep was either run directly or tested against the prescribed output string; every SHA/path/line-ref was checked against the actual files. The plan is in strong shape after R1–R3 — most of what I checked is correct. Findings below are the residue.

---

### [MEDIUM] Task 6.4 full-suite gate (`./gradlew test` exit 0) is at risk on the WSL2 execution host, and the retry clause doesn't cover the documented failure mode

**Evidence:**
- `src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java:49` — live inventory confirms it is **not** `@Disabled` (re-enabled per TD-22-D; my `grep -rn "@Disabled(" src/test/java` returns 6 annotations / 5 files, none being HundredBot — matching the plan's own R2-H1 statement).
- `20-01c-SUMMARY.md` Caveat #1: *"HundredBotIntegrationTest.hundredBotsConnectAndReceiveTicks times out on WSL2 + Gradle `forkEvery=1` test isolation. Verified pre-existing at parent commit 14e96ea."*
- This environment is WSL2 (`Linux … microsoft-standard-WSL2`); `src/test/resources/junit-platform.properties` sets a 5-min `SEPARATE_THREAD` timeout, so a hung HundredBot test fails the suite.
- Plan 6 Task 6.4 verify is `… --tests <three-gate> && ./gradlew test` — full suite is a hard `&&` gate.
- The plan's TD-22-E retry clause is scoped narrowly: *"if the full suite fails ONLY with `Could not write XML test results` / `forkEvery=1` contention … retry once … **Any actual test failure = stop and report.**"* A HundredBot connect-latch timeout presents as a test failure/timeout, **not** an XML-write error — so the executor, following the plan verbatim, would stop-and-report on a pre-existing, non-Plan-6 condition.

**Why it matters:** Plan 5 never actually obtained a green full suite (its run died on TD-22-E XML contention and proceeded via the null-result path), so there is no confirmed green-full-suite-on-WSL2-with-HundredBot-enabled evidence anywhere in the phase record. If HundredBot times out during Task 6.4, a comment-only/doc-only plan blocks on the environment.

**Suggested fix:** Widen the Task 6.4 flake clause to also tolerate a pre-existing HundredBotIntegrationTest timeout on WSL2 (cite 20-01c Caveat #1 + STATE.md), OR make the load-bearing gate the in-suite three-gate stack (already reliable) and demote the full suite to best-effort with an explicit "record any pre-existing failure, do not revert comments" instruction. The plan's own philosophy — "a comment-only change cannot break tests" — supports proceeding.

*Caveat: I did not run the full suite (5–15 min); per the rubric I cap this at MEDIUM. The risk is real and documented but not confirmed-failing on this checkout.*

---

### [LOW] 20-RUNTIME.md footer date placeholder `2026-06-XX` is not addressed by any task step and survives the "FINAL / every marker replaced" gate

**Evidence:**
- `20-RUNTIME.md:400` (last line): `*Phase 20-connection-multiplexing-runtime-tuning · v1 · 2026-06-XX*`
- The must_haves artifact for 20-RUNTIME.md declares it `FINAL — every section populated, every Pending marker replaced`, but `2026-06-XX` is neither a `_Pending_` marker nor a `plan N <verb>` forward-ref, so the step-6 zero-pending audit (`! grep -iqE "pending|plan [0-9/]+ (populates|...)"`) does **not** catch it. No Task 6.1 step instructs stamping the date.

**Why it matters:** The canonical operator doc ships "final" with an unfilled `XX` in its version line. Cosmetic, but it's the closing artifact of the phase.

**Suggested fix:** Add a one-line instruction in Task 6.1 to set the footer date to the completion date (2026-06-04), or fold it into the step-6 audit scope.

---

### [NIT] Task 6.3 README template nests a ` ```bash ` fence inside a ` ```markdown ` fence

**Evidence:** The Task 6.3 `<action>` presents the entire README body wrapped in a ` ```markdown ` block that itself contains a ` ```bash ` block for the build commands.

**Why it matters:** The executor uses `Write` with the literal content, so the final file is well-formed — but the inner fence boundary is a known copy-ambiguity for a ~35-line block.

**Suggested fix:** None required; optionally present the README body unfenced or with `~~~markdown` outer delimiters. Marginal.

---

## What I verified as correct (not findings — for confidence)

| Check | Result |
|---|---|
| AdmissionMetrics `M_TICK_WORK_MS`=L70, `M_DETACH_TIMEOUT`=L79 | ✓ plan's `:70`/`:79` correct |
| `application.yml:15` actuator `include:` | ✓ |
| c22e487 files on disk = exactly **12** (3 jfr + 3 meta + 3 html + 3 metrics) | ✓ matches step 5 enumeration + must-have |
| Gauge grep `^\| .?paralife\.(…)` | ✓ matches backtick-wrapped rows; returns 0 now (Pending present), counts after fill |
| c22e487 brace-tolerant greps (jfr + metrics) | ✓ MATCH prescribed compressed-brace strings (R3-H1 sound) |
| Line-count case → `null-result` → ≥250 branch | ✓ (`Plan 5 outcome: null-result` parses; file already 400 lines) |
| **All 16 zero-pending audit hits** in current RUNTIME | ✓ every one maps to a plan step (1/2/3/5) — gate is satisfiable |
| @Disabled live inventory = 6 annotations / 5 files, HundredBot re-enabled | ✓ exactly as plan R2-H1 states |
| CLAUDE.md: Connection model L130, `GSD:architecture-end` L142 | ✓ insertion point accurate |
| WorldWebSocketHandler attachSession site L320; OutboundSender `Thread.ofVirtual` L136-138 | ✓ both match interfaces block (R3-N1 corrected to L136-138) |
| 20-VALIDATION.md: frontmatter false/false, stub rows L46-51, no Plan-5 line, L62/L67/L81/L82/L96/L97 refs | ✓ all accurate |
| profiles/README.md staleness (L11-12, L17, L25, L29 ritual, L75-99) | ✓ exactly as step 7 describes |
| 20-CONTEXT.md D-19/D-21 sites L208/L225/L327/L463 | ✓ all four c22e487 sites real |
| Provenance SHAs (ddf0dfa, 870440d, 62c1b44, 103a615, 424e06d, c22e487, 328ff7a) | ✓ all exist, semantically correct |
| README package list (10 pkgs), `runBot`/`loadHarnessJar` tasks | ✓ all real |
| No residual `c22e487`-as-live / `20-01b`-as-active / "7 plans" / tuned-flamegraph-capture step / already-populated-as-to-fill | ✓ none found; §4.2/§4.4 1000-tier correctly framed "verify, do not re-derive" |

---

## Summary

| Severity | Title | One-line fix |
|---|---|---|
| MEDIUM | Task 6.4 full-suite gate vs WSL2 HundredBot timeout | Widen flake clause to tolerate pre-existing HundredBot timeout, or make three-gate the load-bearing gate |
| LOW | RUNTIME footer `2026-06-XX` placeholder unaddressed | Add date-stamp instruction to Task 6.1 (→ 2026-06-04) |
| NIT | README template nested code fence | Optional: unfence or use `~~~` outer delimiter |

**VERDICT: 0 BLOCKER, 0 HIGH, 1 MEDIUM, 1 LOW, 1 NIT**

Execution-ready. No blocker or HIGH defect: all acceptance greps are satisfiable by truthful output, every SHA/path/line-ref resolves, and the zero-pending gate's 16 current hits are all step-addressed. The single MEDIUM is an environment-conditional gate risk (not a plan-logic error) that an executor could plausibly recover from but which the plan's written flake clause doesn't authorize.

---

## Gemini Review

I have completed the pre-execution review of **20-06-PLAN.md**. The plan is comprehensive, consistent with the current repository state, and addresses the documentation close-out requirements of Phase 20 with high precision.

### [OK] Executability & Verification Robustness
The verification commands and acceptance greps are well-structured to handle potential drift and formatting nuances:
- **Gauge Grep (R1-H1 fix):** The pattern `^\| .?paralife\.(tick\.health\.work-time-ms|outbound\.detach\.timeout)` correctly accounts for the backticks used in the `20-RUNTIME.md` tables, preventing the match failure identified in earlier rounds.
- **Brace-tolerant EREs:** Task 6.1 acceptance criteria for the superseded `c22e487` series use `(\{[0-9,]+\}|1000)bots`, which correctly matches the compressed-brace style prescribed in the Task 6.1 action block (addressing the R3-H1 defect).
- **Line Reference Flexibility:** Task 6.2 acknowledges that `AdmissionMetrics.java` line numbers drift. The acceptance grep `AdmissionMetrics\.java:[0-9]+` is robust, while the instruction to re-verify `:70/:79` at execution time ensures the documentation remains accurate. At current `HEAD`, these constants are indeed at lines 70 and 79.

### [OK] Residual Staleness & Provenance
The plan successfully manages the transition from the superseded `c22e487` baseline to the authoritative `62c1b44` (churn) and `103a615` (active) sets:
- **Doc-Drift Reconciliation:** Task 6.1 step 7 explicitly de-stales `20-CONTEXT.md` and `profiles/README.md`, ensuring that future operators are directed to the correct canonical baseline.
- **Artifact Indexing:** Task 6.1 step 5 ensures all 12 committed `c22e487` artifacts are indexed for historical reference, satisfying the "every committed artifact" must-have.
- **Live Inventory for Tests:** Task 6.5 correctly identifies the current state of disabled tests (6 annotations across 5 files), specifically noting that `HundredBotIntegrationTest` has been re-enabled. My search of the codebase confirmed this inventory is accurate.

### [OK] Internal Consistency & Scope
- **Frontmatter Alignment:** `.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md` is correctly included in `files_modified` (addressing Pass-3 Concern #30).
- **Outcome-Based Scaling:** The plan appropriately tiers the line-count floor for `20-RUNTIME.md` based on the Plan 5 outcome (`null-result`), preventing "filler pressure" while maintaining high documentation standards.
- **Three-Place Rationale:** The D-02 codification is correctly targeted at `README.md`, `CLAUDE.md`, and the two specific WebSocket upgrade/attach sites in the source code.

### [NIT] AdmissionMetrics Line Number Citation
While the plan correctly instructs the executor to re-verify, I verified that `M_TICK_WORK_MS` is currently at line 70 and `M_DETACH_TIMEOUT` is at line 79 in `src/main/java/com/paralife/admission/AdmissionMetrics.java`. The plan's expectation of `:70/:79` is accurate for the current codebase.

### Summary of Findings

| Severity | Title | One-line Fix |
| :--- | :--- | :--- |
| **NONE** | Execution-Ready | No blockers or high-severity issues found. |

**VERDICT: 0 BLOCKER, 0 HIGH, 0 MEDIUM, 0 LOW, 0 NIT**

The plan is ready for execution.

---

## Codex Review

### [HIGH] Profile Index Date Source Names A Nonexistent Field

Evidence: [20-06-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:166) says capture dates come from `.meta.json` `captured_at` fields. Live meta files use `captured_utc` where present, e.g. [jfr-100bots-baseline-62c1b44.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-62c1b44.meta.json:6) and [jfr-1000bots-active-50xfood-tuned-424e06d.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-active-50xfood-tuned-424e06d.meta.json:12). The active `103a615` meta files have no capture timestamp field at all, e.g. [jfr-1000bots-active-50xfood-103a615.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-active-50xfood-103a615.meta.json:1).

Why it matters: a literal executor cannot truthfully replace §6 Captured placeholders from `captured_at`; the field is absent. This can lead to invented dates or unfinished placeholders.

Suggested fix: change step 5 to use `captured_utc` when present. For `103a615` active files, either use first/last `sample_utc` from matching metric sidecars with an explicit note, or add `captured_utc` to the active meta sidecars before indexing.

### [MEDIUM] 100-Tier Narrative Template Can Reintroduce False Heap Validation

Evidence: [20-06-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:137) correctly says the 100-tier `1g/1g` and 500-tier `1g/2g` presets were never JFR-exercised. But the §4.3 template then suggests the 100-tier conclusion “default G1 + 1g heap sustains” at [20-06-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:146). Live meta confirms baseline captures used `-Xms2g -Xmx2g`, e.g. [jfr-100bots-baseline-62c1b44.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-62c1b44.meta.json:8).

Why it matters: this is not a grep failure, but it risks writing a false canonical runtime statement immediately after the plan fixed that exact heap overclaim.

Suggested fix: replace the suggested conclusion with “default G1 showed headroom in the 2g/2g capture; the 1g recipe heap is an operator smoke preset, not JFR-validated.”

### [LOW] Line-Count Summary Prose Still Groups Pinning With Null-Result

Evidence: corrected acceptance puts pinning at `≥350` and null-result at `≥250` in [20-06-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:185). But objective prose still says `≥250 for outcomes 3/4` at [20-06-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:64), and the verification summary repeats it at [20-06-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:509).

Why it matters: inert for this run because Plan 5 outcome is `null-result`, but it leaves contradictory plan guidance.

Suggested fix: update both prose sites to `≥350 for outcomes 1/2/4; ≥250 for outcome 3`.

| Severity | Title | One-line fix |
|---|---|---|
| HIGH | Profile Index date source names nonexistent field | Use `captured_utc`; define fallback/source for active `103a615` dates |
| MEDIUM | 100-tier narrative can overclaim heap validation | Reword 1g/2g heap language as smoke preset only |
| LOW | Line-count prose stale | Align objective/verification prose with corrected acceptance branch |

VERDICT: 0 BLOCKER, 1 HIGH, 1 MEDIUM, 1 LOW, 0 NIT

---

## Opencode Review

I'll conduct the review by verifying key claims against the live repository. Let me start by checking critical files in parallel.Now I have all the evidence needed. Let me compile the final review.

---

## Review Findings

### Verification log

- `AdmissionMetrics.java`: `M_TICK_WORK_MS` at **L70**, `M_DETACH_TIMEOUT` at **L79** — plan's `:70`/`:79` correct (acceptance uses line-agnostic pattern anyway).
- `application.yml`: `include:` at **L15** — plan's `:15` correct.
- `@Disabled` live inventory: **6 annotations in 5 files** — plan correctly describes this (fixed from stale "4").
- CLAUDE.md insertion point: Connection model ends at **L141**, `GSD:architecture-end` marker at **L142** — plan correctly identifies this.
- `20-RUNTIME.md` line count: **400** — ≥250 floor for null-result (outcome 3).
- Pending markers: **16 hits** in RUNTIME.md — all covered by plan steps 1-5.
- `20-05-SUMMARY.md:50`: `Plan 5 outcome: null-result` — plan's acceptance case statement matches correctly.
- `20-05-TRIAGE.md:1`: `null-result: codec ≤1.8% CPU...` — matches plan's step 4 reference.
- c22e487 files on disk: 12 confirmed (3 JFR, 3 meta.json, 3 flamegraphs, 3 metrics-json).
- `profiles/README.md`: still says "always `c22e487`" — plan correctly identifies this as stale.
- `20-VALIDATION.md`: frontmatter false, 6 stub rows — plan correctly prescribes flipping both.
- `20-01c-PLAN.md`: exists, prose-structured (Phase A/B/C) — plan correctly handles this.

---

### [MEDIUM] L84 in VALIDATION.md carries a stale `see 20-RUNTIME` sub-pattern in the D-02 codification grep

**Evidence:** `20-VALIDATION.md:84`:
```
grep -rE "WS:entity 1:1\|see 20-RUNTIME" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java src/main/java/com/paralife/admission/OutboundSender.java
```
The inline comments Task 6.4 creates use "See 20-RUNTIME**.md** §1" (capital S, `.md` suffix). The `see 20-RUNTIME` branch matches *neither* file — wrong case, wrong next-character. The first alternative `WS:entity 1:1` carries, so the grep still passes, but the sub-pattern is a dead weight. Task 6.5 step 3 explicitly addresses L81/L82 annotations but doesn't mention L84.

**Why it matters:** This is the primary D-02 codification verification instruction in the Manual-Only table. A dead branch doesn't break execution, but it erodes diagnostic precision — if someone later only checks `see 20-RUNTIME` (e.g. copy-pasting the alternative), they'd get a false negative. The plan's Task 6.5 is already editing this file; fixing this pattern while there costs nothing.

**Suggested fix:** In Task 6.5 step 3, extend the row-annotation scope to L84: replace `see 20-RUNTIME` with `20-RUNTIME\\.md` (or simply drop the dead branch — `WS:entity 1:1` alone suffices since all 4 files carry it per the new 4-file acceptance grep).

---

### [LOW] Task 6.1 step 5: §6 `profiles/README.md` row instruction is semantically incongruous with the table schema

**Evidence:** Step 5 item "Add a row for `profiles/README.md` if absent." The §6 table columns are `Filename | Scenario | Source SHA | Captured | Size | Notes`. A doc file has no scenario, no SHA, no capture date — it doesn't fit. The "if absent" hedge gives the executor discretion, but an ambiguous instruction risks an executor adding a misplaced row with `—` in three columns.

**Why it matters:** Low impact — execution won't fail. But a documentation file row in a profile-artifact index is clutter. The `profiles/README.md` is already cited in the §6 preamble at L332; a table row adds nothing.

**Suggested fix:** Drop "if absent" → "do NOT add a row — profiles/README.md is a documentation file, not a profile artifact; it's already cited in the §6 preamble."

---

### [LOW] Task 6.1 step 6 audit regex does not cover the literal string `_plan 4` that could appear in reworded prose

**Evidence:** After step 1 rewrites the §3 intro prose, sentences like "The original Plan 4 recipe template remains unchanged" or similar settled-past-tense text could theoretically appear. The audit regex `plan [0-9/]+ (populates|produces|finalises|wires|will|tunes?)` only flags plan-refs followed by active verbs (tunes, produces, etc.) — it correctly ignores "Plan 4 recipe template" (no verb match). However, the audit pattern `plan [0-9/]+` with `-i` flag would match `Plan 4` anywhere, and then the verb-group (*populates*|*produces*|...) is attempted. If the reworded text says something like "Plan 4 drafted the per-tier recipes" — "drafted" isn't in the verb set, so it passes. If it says "Plan 4 produced the recipes" — that's a false positive (past-tense factual statement, not a forward-ref). An executor seeing this would falsely think there's still work to do.

**Why it matters:** LOW — the reworded text in step 1 deliberately uses settled past-tense like "Plan 5's tuned JFR shows..." and the plan's instructions say to reword to past tense. The regex verb set is designed for *forward-refs* specifically. A false positive on a past-tense "produced" is harmless (executor verifies it's already done and moves on).

**Suggested fix:** No action required — the regex design is documented as "forward-ref only" and works correctly for the audit's purpose.

---

### [LOW] Task 6.4 `OutboundSender` comment site: plan says `~L136-138` but `Thread.ofVirtual()` sits at L136 with `.name()` at L137 and `.start(...)` at L138 — the "match on code block" note correctly resolves this

**Evidence:** `OutboundSender.java:136-138` is `Thread.ofVirtual()\n.name("ws-sender-" + id)\n.start(() -> drainLoop(session, queue));`. The plan's `must_haves` says `~L136-138 (match on code block, not line numbers)` and the `<interfaces>` block provides the exact code block to match. The `must_haves` artifact entry also carries `~L136-138`. The R3 backlog says this was corrected from `~132-135`. The current value 136-138 is correct.

**Why it matters:** LOW — the `~` prefix and "match on code block" note make this executor-safe. The line number in the must_haves is informational only.

**Suggested fix:** No action needed.

---

### Verdict summary

| Severity | Title | Fix |
|----------|-------|-----|
| MEDIUM | VALIDATION L84 `see 20-RUNTIME` grep branch is dead (case + suffix mismatch) | Extend Task 6.5 step 3 to fix L84 pattern or drop the dead branch |
| LOW | §6 profiles/README table row incongruous with table schema | Clarify "do NOT add a row" or drop the instruction |
| LOW | Audit regex could false-positive on past-tense "produced" | No action — harmless |
| LOW | OutboundSender comment line ~136-138 matches actual code | No action — `~` prefix + code-block match handles it |

**VERDICT: 0 BLOCKER, 0 HIGH, 1 MEDIUM, 3 LOW, 0 NIT**

The plan is execution-ready after 3 multi-review rounds. All file paths, SHAs, line references, and acceptance greps are satisfiable against the live repository. The one MEDIUM finding (dead grep branch in a pre-existing VALIDATION row) doesn't break execution but is a cheap fix while that file is already being edited.

---

## Consensus Summary

### Agreed Strengths
- **No blockers; plan is execution-ready.** All four reviewers concur there is no BLOCKER and the plan is satisfiable against the live repo after R1–R3 (gemini found zero issues; claude/codex/opencode found only residual single-reviewer items).
- **Gauge grep R1-H1 fix is sound.** The backtick-aware pattern `^\| .?paralife\.(...)` correctly matches the RUNTIME table rows (claude, gemini, opencode all verified).
- **Brace-tolerant EREs (R3-H1) work.** `(\{[0-9,]+\}|1000)bots` matches the compressed-brace style in the action block (claude, gemini).
- **AdmissionMetrics line refs correct + robust.** `M_TICK_WORK_MS`=L70, `M_DETACH_TIMEOUT`=L79 confirmed; the line-agnostic acceptance pattern tolerates drift (claude, gemini, codex implicit, opencode).
- **@Disabled inventory accurate.** 6 annotations / 5 files, HundredBot re-enabled — exactly as plan states (claude, gemini, opencode).
- **c22e487 = 12 artifacts on disk** (3 jfr + 3 meta + 3 html + 3 metrics), matching step-5 enumeration (claude, opencode).
- **Zero-pending audit is satisfiable.** All 16 current Pending hits in RUNTIME map to plan steps 1–5 (claude, opencode).
- **Outcome-based line-count tiering appropriate.** null-result → ≥250 floor prevents filler pressure; branch parses correctly (claude, gemini, opencode).
- **Staleness/provenance reconciliation correct.** profiles/README + 20-CONTEXT de-staling from superseded c22e487 to authoritative 62c1b44/103a615; 20-VALIDATION frontmatter + stub rows handled (claude, gemini, opencode).

### Agreed Concerns
- **[Theme: date-filling — HIGH/LOW, 2 reviewers, distinct artifacts]** Both codex and claude flag that "final" date fields are not cleanly resolvable as written:
  - codex **HIGH**: step 5 sources §6 Captured dates from `.meta.json` `captured_at`, but live files use `captured_utc` (62c1b44, 424e06d) or carry **no** timestamp field at all (103a615 active set). A literal executor cannot truthfully fill these → risk of invented dates or stranded placeholders. Fix: use `captured_utc`; define a fallback (e.g. `sample_utc` from metric sidecars, noted) or add `captured_utc` to the active metas first.
  - claude **LOW**: `20-RUNTIME.md:400` footer `2026-06-XX` is neither a Pending marker nor a forward-ref, so the zero-pending gate misses it and no task stamps it. Fix: add a date-stamp step (→ 2026-06-04).
  - Net: ship-final date handling needs one explicit, field-accurate instruction before execution. Severity contested (see Divergent).

### Divergent Views
- **Whether anything remains at all.** gemini: 0 findings, fully ready. claude/opencode: 1 MEDIUM each; codex: 1 HIGH + 1 MEDIUM. Worth a second look — gemini's clean pass did not exercise the specific residuals the others surfaced.
- **The three MEDIUMs are each single-reviewer, uncorroborated** — each reviewer found a *different* residual, none confirmed by the others. Worth investigating precisely because they lack cross-confirmation:
  - claude — **Task 6.4 full-suite `&& ./gradlew test` gate vs WSL2 HundredBot timeout**: the TD-22-E retry clause covers only XML-write/forkEvery contention, not a HundredBot connect-latch timeout, which presents as a real failure → executor would stop-and-report on a pre-existing, non-Plan-6 condition. claude self-caps at MEDIUM (did not run the 5–15 min suite). Fix: widen the flake clause to tolerate pre-existing HundredBot timeout, or make the in-suite three-gate the load-bearing gate.
  - codex — **§4.3 100-tier narrative template may overclaim heap validation**: suggested conclusion "default G1 + 1g heap sustains" risks a false canonical statement; baseline captures actually ran `-Xms2g -Xmx2g`, and the 1g/2g presets were never JFR-exercised. Fix: reword the 1g recipe as an operator smoke preset, not JFR-validated.
  - opencode — **20-VALIDATION.md:84 dead grep branch** `see 20-RUNTIME` (wrong case + missing `.md`) matches neither file; the `WS:entity 1:1` alternative carries so it passes, but the branch is dead weight in the primary D-02 verification row. Fix: while Task 6.5 already edits this file, swap to `20-RUNTIME\.md` or drop the dead branch.
- **Date-handling severity split**: codex rates the metadata-field mismatch HIGH (truthfulness blocker for a literal executor); claude rates its related footer placeholder LOW; gemini/opencode did not flag dates. Reconcile the field name (`captured_utc`, not `captured_at`) and the 103a615 no-timestamp gap before treating either as cosmetic.
- **codex LOW (uncorroborated)**: line-count prose at L64 and L509 still says `≥250 for outcomes 3/4`, contradicting the corrected acceptance (`≥350` pinning / `≥250` null-result). Inert this run (outcome is null-result) but leaves contradictory guidance; align both prose sites.
