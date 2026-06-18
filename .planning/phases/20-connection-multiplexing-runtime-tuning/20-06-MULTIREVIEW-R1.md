---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T12:25:42Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md"]
models:
  codex: "gpt-5.5"
usage:
  claude: { input: 941843, output: 77, cached: 1223936, tool_calls: 8, elapsed_s: 494.2 }
  gemini: { input: 703004, output: 1730, cached: 244102, tool_calls: 6, elapsed_s: 264.0 }
  codex: { input: 561357, output: 8645, cached: 416384, tool_calls: 23, elapsed_s: 176.3 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 32, elapsed_s: 295.0 }
synthesizer: claude
synthesized_at: 2026-06-04T12:25:42Z
---

# Cross-AI Review

## Claude Review

Verified against the live repo. One HIGH (an acceptance grep that rejects truthful output), plus de-stale gaps the plan's own steps don't cover. Full review below.

---

# Pre-execution review — `20-06-PLAN.md` (Phase 20 close-out)

Scope: executability of `verify`/`acceptance_criteria`, residual staleness, internal consistency, MVP discipline. Every finding reproduced against the working tree.

### [HIGH] Task 6.1 gauge-population acceptance grep is unsatisfiable against the real §4.2 table

**Evidence:** `20-06-PLAN.md` Task 6.1 `<acceptance_criteria>`:
```
grep -E "^\| (paralife\.tick\.health\.work-time-ms|paralife\.outbound\.detach\.timeout)" 20-RUNTIME.md \
  | grep -vE "Pending|TBD|^_pending_" | wc -l | awk '$1 >= 2 { exit 0 } { exit 1 }'
```
The §4.2 rows wrap the metric name in backticks — `20-RUNTIME.md:344`:
```
| `paralife.tick.health.work-time-ms` (mean ms) | _Pending …_ | … |
```
The pattern anchors `| ` *directly* onto `paralife`, but the byte after `| ` is a backtick. I ran it:
```
$ grep -E "^\| (paralife\.tick…|paralife\.outbound…)" 20-RUNTIME.md | grep -vE "Pending|TBD" | wc -l
0
```
`wc -l` = 0 → `awk '$1 >= 2'` exits 1. The criterion **fails even when §4.2 is fully and correctly populated** (which it already is for the 1000-tier, and will be for 100/500 baseline after step 3).

**Why it matters:** this is the gate for the D-13/D-18 headline numbers — the central deliverable of the plan. An executor running it on truthful output gets a red gate and is pushed either to churn the table (strip backticks, regressing the reviewed-converged §4.2 form) or to hand-wave the criterion. Rubric: unsatisfiable acceptance grep = HIGH.

**Fix:** allow the leading backtick and drop the brittle line-start coupling, e.g.
```
grep -E "\`paralife\.(tick\.health\.work-time-ms|outbound\.detach\.timeout)\`" 20-RUNTIME.md \
  | grep -vE "Pending|TBD" | wc -l | awk '$1 >= 2 {exit 0} {exit 1}'
```
(or `^\| \`?(paralife…)\`?`). Note the same backtick exists on the `<verify><automated>` block — but that block doesn't use this pattern, so only `<acceptance_criteria>` is affected.

---

### [MEDIUM] Task 6.1 de-stale enumeration is narrower than the doc's actual stale forward-refs; step-6 audit is self-inconsistent

**Evidence:** Step 1 enumerates only the GC-rationale ×3, heap-preset preamble, and VT-parallelism markers. But `20-RUNTIME.md` still carries stale forward-references that no step touches:
- `:314` — `**Tuned JFR target:** `profiles/jfr-1000bots-tuned-<HEAD>.jfr` (Plan 5 produces).` — a dangling placeholder; Plan 5 shipped `jfr-1000bots-active-50xfood-tuned-424e06d.jfr`. In the **final** close-out doc this points at an artifact that never existed under that name, and Phase 21 consumes §3 verbatim.
- `:100` — "Plan 5 / Plan 6 will replace `Pending — JFR-driven` markers…"
- `:119` — "Plan 6 finalises §4 numbers."
- `:143`, `:155` — "Plan 5/6 wires the reproducible per-tier … recipe into §3 once the tuning-rig form factor is decided."

Step 6's own audit `grep -i "pending\|plan [0-9] populates"` **will match line 100** ("Pending"), yet step 1 never lists line 100 for replacement → the executor is handed a step that fails its own audit. Meanwhile `produces` / `finalises` / `wires` (lines 314/119/143/155) are caught by neither the audit nor step 1, so they survive into the shipped doc.

**Why it matters:** this is precisely the "residual staleness" the de-stale was meant to close, sitting in the section Phase 21 copy-pastes. The executor *could* recover (hunt every `Plan 5/6` ref after step 6 flags line 100), but the plan doesn't direct it and step 6 is stricter than step 1.

**Fix:** add to step 1: "rewrite §3.3 Tuned JFR target to the actual `jfr-1000bots-active-50xfood-tuned-424e06d.jfr` (Plan 5, null-result); convert §3-intro / §3 forward-reference prose at L100/119/143/155 from future tense to settled (Plan 5 = null-result, no rig wired — deferred)." Widen the step-6 audit to `pending|plan [0-9] (populates|produces|finalises|wires)|will replace`.

---

### [MEDIUM] Task 6.4 mandates `./gradlew test` exit 0 with no TD-22-E flake-handling note

**Evidence:** Task 6.4 `<verify>`/`<acceptance_criteria>` require `./gradlew test` exit 0. Plan 5's SUMMARY (`20-05-SUMMARY.md:184-189`) records the full suite **BUILD FAILED** under `forkEvery=1` XML-write contention (TD-22-E) in the worktree context — an environmental failure, not test logic. Task 6.4's change is comment-only (zero behavioural diff), so any failure here is by definition that same infra flake, yet the plan gives the executor no recovery path. (Plan 5 itself carries the Concern #14 single-retry-then-revert protocol for exactly this; Plan 6 omits it.)

**Why it matters:** a comment-only change gated on a command that demonstrably BUILD-FAILED in the immediately prior plan under the same harness risks a false-negative ship gate. Executor can likely recover by retrying, but it's worth pinning.

**Fix:** append a one-liner to Task 6.4: "If `./gradlew test` fails only with TD-22-E `Could not write XML test results` / forkEvery=1 contention (comment-only change cannot break tests), retry once; record the green SHA. Do not revert comments on an infra flake."

---

### [LOW] Task 6.5 flips the D-19 sign-off to 62c1b44 but leaves c22e487 as the baseline in the same file's body

**Evidence:** Step 4 rewrites the Validation Sign-Off D-19 line to "canonical churn baseline series cites `62c1b44`". But Task 6.5 does not touch:
- `20-VALIDATION.md:67` — Wave-0 "MUST create … `jfr-{100,500,1000}bots-baseline-c22e487.jfr` — Plan 1 (D-19 SHA-anchored)"
- `:81-84` — Manual-Only Verifications describing capture against c22e487 / `jfr-1000bots-baseline-c22e487.jfr`

Post-execution the file asserts both "canonical = 62c1b44" (sign-off) and "baseline = c22e487" (Wave-0 list / manual rows). The c22e487 captures genuinely exist on disk and are historically what Plan 1 produced, so these lines aren't *wrong* — but they read as live next to a flipped sign-off.

**Why it matters:** minor self-inconsistency in the phase's own validation record, in a file the plan is already editing. Recoverable / cosmetic.

**Fix:** in step 3, annotate L67/L81-84 as "(superseded by Plan 1c re-anchor → 62c1b44; c22e487 retained on disk for history)" — or leave as-is and add a one-line "historical, intentionally not updated" note so a future reader doesn't read it as a contradiction.

---

### [LOW] Line-count tier case-statement patterns carry a trailing colon the outcome signal does not

**Evidence:** Task 6.1 acceptance:
```
case "$OUTCOME" in
  *"triaged:"*|*"runtime-knob-tightened:"*) [ "$LINES" -ge 350 ] …
  *"null-result:"*|*"pinning-dominates-with-backlog-handoff:"*) [ "$LINES" -ge 250 ] …
  *) [ "$LINES" -ge 250 ] … ;;
```
But the Concern #29 contract and the actual file have **no colon**: `20-05-SUMMARY.md:50` = `Plan 5 outcome: null-result`. So `*"null-result:"*` does not match → falls through to the default `*)` branch (also 250). For this run the floor is correct (null-result wants 250, default gives 250), so it's benign — but the classifier is non-functional: had Plan 5 shipped `triaged`/`runtime-knob-tightened`, the 350 floor would silently degrade to 250 (the exact underclaim Concern #29 was added to prevent). The colon appears in `20-05-TRIAGE.md:1` (`null-result:`) but Plan 6 greps the SUMMARY, not TRIAGE.

**Fix:** drop the colons in the case patterns to match the no-colon signal contract (`*"triaged"*|*"runtime-knob-tightened"*`, `*"null-result"*|*"pinning…"*`).

---

### [NIT] Task 6.5 example task-id `20-02-2.0` likely doesn't exist

`20-02` ships Tasks 2.1/2.2/2.3 (per `20-02-SUMMARY.md`), so the illustrative id `20-02-2.0` in step 2's example list mismatches. Harmless — step 2 directs the executor to read each PLAN for real ids — but tidy the example to avoid a copied-through stub.

---

## Checks that passed (verified, not assumed)

| Plan claim | Verified |
|---|---|
| `AdmissionMetrics.java:70` (M_TICK_WORK_MS), `:79` (M_DETACH_TIMEOUT) | ✅ exact, current HEAD (`grep -n`) |
| `application.yml:15` exposes `metrics` actuator | ✅ line 15 = `include: health,info,metrics` |
| `20-VALIDATION.md` frontmatter `nyquist_compliant`/`wave_0_complete` within `head -10` | ✅ lines 5–6 |
| README `## Project layout` package list | ✅ all 10 (`admission…world`, `harness`, `metrics`, `runtime`) exist |
| `grep -cE "jfr-(100\|500\|1000)bots-baseline-62c1b44\.jfr" ≥ 6` | ✅ = 6 now (grows with §4.3) |
| §6 placeholder fills (`_Plan 1c_`, `_≤10 MB_`) via `ls -lh` | ✅ all cited profiles present on disk |
| All 8 PLAN/SUMMARY files (incl. `20-01c-PLAN.md`) | ✅ present |
| No "7 plans" / 20-01b-as-active staleness; tuned-flamegraph "not captured" | ✅ consistent; no tuned `.html` on disk |
| D-19 reconcile line refs (`20-CONTEXT.md:208`, `20-RUNTIME.md:370/377`) | ✅ accurate |

---

## Summary

| Severity | Title | One-line fix |
|---|---|---|
| HIGH | §4.2 gauge grep unsatisfiable (backtick vs `^\| paralife`) | match the leading backtick / drop the line-start anchor |
| MEDIUM | Task 6.1 de-stale set narrower than doc; step-6 audit self-inconsistent | add L314/100/119/143/155 to step 1; widen audit regex |
| MEDIUM | Task 6.4 `./gradlew test` gate lacks TD-22-E flake note | add single-retry-on-infra-flake clause (mirror Concern #14) |
| LOW | 6.5 sign-off says 62c1b44 but body (L67/81-84) keeps c22e487 baseline | annotate as historical/superseded |
| LOW | line-count case patterns have trailing colon; signal has none | drop colons (benign now, latent underclaim) |
| NIT | example id `20-02-2.0` likely nonexistent | fix illustrative id |

**VERDICT: 0 BLOCKER, 1 HIGH, 2 MEDIUM, 2 LOW, 1 NIT**

The plan is close to clean — de-stale of the already-shipped Plan 5 work is correctly framed as "verify, don't re-derive." The one blocking-quality item is the §4.2 grep: fix that one pattern and the plan is executable. The two MEDIUMs (incomplete §3 forward-ref cleanup, missing infra-flake note) prevent a fully clean run-through but are recoverable.

---

## Gemini Review

### [HIGH] `AdmissionMetrics.java` line number drift causes unsatisfiable grep
* **Evidence:** `20-06-PLAN.md` (Task 6.2 acceptance criteria) requires `grep -q "AdmissionMetrics.java:70" CLAUDE.md && grep -q "AdmissionMetrics.java:79" CLAUDE.md`. However, in `src/main/java/com/paralife/admission/AdmissionMetrics.java`, `M_TICK_WORK_MS` is at line 52 and `M_DETACH_TIMEOUT` is at line 59.
* **Why it matters:** The plan explicitly tells the executor to "re-verify constants' actual line numbers at execution time; they drift". If the executor follows this instruction and correctly writes `:52` and `:59` into `CLAUDE.md`, the hardcoded grep checking for `:70` and `:79` will fail. A grep that rejects truthful output is a HIGH defect.
* **Suggested fix:** Loosen the acceptance grep to match any line number (e.g., `grep -qE "AdmissionMetrics\.java:[0-9]+" CLAUDE.md`) or update it to the actual current line numbers (`52` and `59`).

### [MEDIUM] Incomplete instructions for `_Pending_` markers in `20-RUNTIME.md` §4.2
* **Evidence:** `20-06-PLAN.md` (Task 6.1 Action step 3) instructs the executor to "fill the 100/500-baseline `_Pending — Plan 1c actuator sidecar_` cells from `metrics-100bots-baseline-62c1b44.json` + `metrics-500bots-baseline-62c1b44.json`." It omits instructions for the two `_Pending_` cells in the `jdk.VirtualThreadPinned` row for the 100 and 500 baseline tiers. 
* **Why it matters:** Task 6.1 step 6 "Audit pass" requires that *every* `_Pending_` marker is replaced. Without explicit instructions to parse the 100/500 baseline `.jfr` files for pinning counts (or providing the baseline values directly), the executor may leave them as `_Pending_` (violating the audit pass) or hallucinate values.
* **Suggested fix:** Update Task 6.1 step 3 to explicitly instruct the executor to read the pinning values from the respective 100/500 baseline `.jfr` files, or provide the exact baseline values in the plan instructions to replace the `_Pending_` markers.

### [MEDIUM] Stale Wave 0 Requirements checklist in `20-VALIDATION.md`
* **Evidence:** `20-06-PLAN.md` (Task 6.5 Action step 3) instructs the executor to tick the "MUST create" line for Plan 5 (`20-05-TRIAGE.md` and the tuned JFR). However, `20-VALIDATION.md`'s "Wave 0 Requirements" checklist does not contain an entry for Plan 5 artifacts at all.
* **Why it matters:** The executor is instructed to verify and tick a box that does not exist in the target document. This causes execution ambiguity; the executor may fail the step or unnecessarily mutate the document structure to inject the missing checklist item.
* **Suggested fix:** Instruct the executor to *add* the missing Plan 5 requirement row to the Wave 0 Requirements checklist in `20-VALIDATION.md` before ticking it, or acknowledge the omission in the plan so the executor expects to insert it.

### [LOW] Over-broad grep in Task 6.1 Audit Pass
* **Evidence:** `20-06-PLAN.md` (Task 6.1 step 6) tells the executor to run `grep -i "pending\|plan [0-9] populates" 20-RUNTIME.md` to ensure no matches in active sections. `20-RUNTIME.md` contains the phrase `Pending — JFR-driven` in its introductory prose (L120: `will replace Pending — JFR-driven markers with measured-justified choices.`).
* **Why it matters:** An automated audit `grep -i pending` will match the instructional prose block. While an executor might recover by recognizing it's not an "active section", it risks causing confusion or prompting the executor to inadvertently modify the prose block to satisfy the grep.
* **Suggested fix:** Refine the audit pass grep to exclude the instructional prose, e.g., `grep -i "pending\|plan [0-9] populates" 20-RUNTIME.md | grep -v "will replace Pending"`.

### [LOW] Weak acceptance criteria for baseline JFR citations
* **Evidence:** `20-06-PLAN.md` (Task 6.1 acceptance criteria) checks `grep -cE "jfr-(100|500|1000)bots-baseline-62c1b44\.jfr" 20-RUNTIME.md | awk '$1 >= 6 { exit 0 } { exit 1 }'` to verify citations in the narrative and index. Currently, there are exactly 6 citations in the document *before* the executor even adds the 3 narrative paragraphs. 
* **Why it matters:** The command will pass even if the executor fails to add the JFR citations to the narrative paragraphs. It is an under-constrained test.
* **Suggested fix:** Increase the threshold to `awk '$1 >= 9'` to account for the new citations added to the narrative, or explicitly grep the narrative section.

## Summary

| Severity | Title | One-line fix |
|---|---|---|
| HIGH | `AdmissionMetrics.java` line number drift causes unsatisfiable grep | Update grep to check for actual lines 52 and 59 or use regex. |
| MEDIUM | Incomplete instructions for `_Pending_` markers in `20-RUNTIME.md` | Add explicit instructions for the `VirtualThreadPinned` cells in §4.2. |
| MEDIUM | Stale Wave 0 Requirements checklist in `20-VALIDATION.md` | Instruct executor to add the missing Plan 5 row before ticking it. |
| LOW | Over-broad grep in Task 6.1 Audit Pass | Refine `grep -i pending` to exclude instructional prose. |
| LOW | Weak acceptance criteria for baseline JFR citations | Increase grep count threshold from 6 to 9. |

VERDICT: 0 BLOCKER, 1 HIGH, 2 MEDIUM, 2 LOW, 0 NIT

---

## Codex Review

### [HIGH] Profile Index Work Falsely Assumes `c22e487` Rows Already Exist

Evidence: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:30` says §6 must list every committed JFR/flamegraph/metric sidecar, and line 162 says the index is already structurally complete across `c22e487`, `62c1b44`, `103a615`, and `424e06d`. But the live `20-RUNTIME.md` index at `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:381-396` lists `62c1b44`, `103a615`, and `424e06d` only. The historical `c22e487` artifacts are real; for example `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:16-20` documents the `c22e487` JFR/flamegraph patterns.

Why it matters: an executor following Task 6.1 will only replace placeholder sizes/dates and will not add the missing `c22e487` rows, yet the plan’s must-have says the Profile Index lists every committed artifact. Acceptance greps also do not check for `c22e487`, so this can silently ship an incomplete phase record.

Suggested fix: change Task 6.1 step 5 from “already structurally complete” to explicitly add history-only `c22e487` rows for JFRs, flamegraphs, metrics, and meta sidecars. Add acceptance checks such as `grep -q "jfr-1000bots-baseline-c22e487.jfr" 20-RUNTIME.md` and `grep -q "metrics-1000bots-baseline-c22e487.json" 20-RUNTIME.md`.

### [HIGH] D-19 De-Staling Is Too Narrow

Evidence: Task 6.1 step 7 only tells the executor to annotate the D-19 bullet in `20-CONTEXT.md` (`20-06-PLAN.md:169`). But stale live-baseline wording remains elsewhere: `20-CONTEXT.md:225` says the null-result shows the `c22e487` baseline is at the performance floor, `20-CONTEXT.md:327` says baseline filenames cite `c22e487`, and `20-CONTEXT.md:463` still calls the original `c22e487` baseline the frozen baseline. `profiles/README.md:10-27` also says baseline is “always” `c22e487`.

Why it matters: Plan 5’s actual null-result is based on active `103a615` plus tuned `424e06d`, while canonical churn baseline is `62c1b44`. Leaving these references makes final docs internally contradictory after the plan “closes” D-19.

Suggested fix: expand Task 6.1 step 7 to update all stale `20-CONTEXT.md` baseline/null-result references and add `profiles/README.md` to `files_modified` with a short de-stale edit: `c22e487` is history-only; canonical churn is `62c1b44`; active evidence is `103a615`; tuned evidence is `424e06d`.

### [MEDIUM] 20-VALIDATION Acceptance Can Pass Without 20-06 Rows

Evidence: Task 6.5 requires rows across all 8 plans including `20-06` (`20-06-PLAN.md:418`), but `read_first` omits `20-06-PLAN.md` (`20-06-PLAN.md:402-410`). The verify block only greps for `20-01`, `20-01b`, `20-01c`, and `20-05` (`20-06-PLAN.md:448`). The row-count criterion is `>=16` (`20-06-PLAN.md:457`), while pre-20-06 rows alone are about 17.

Why it matters: the validation map can omit all five Plan 6 task rows and still satisfy the executable contract, despite the must-have requiring all 8 plans.

Suggested fix: add `20-06-PLAN.md` to `read_first`, require `grep -q '^| 20-06-6\.' 20-VALIDATION.md`, and raise the row floor to the expected total or require one grep per plan.

### [MEDIUM] Close-Out Summary And ROADMAP Update Are Untracked And Unverified

Evidence: frontmatter `files_modified` lists seven files only (`20-06-PLAN.md:7-14`). The required summary is only in the output block (`20-06-PLAN.md:500-507`), and the ROADMAP completion update is also only described there (`20-06-PLAN.md:509`). Neither file has a task, artifact entry, or acceptance grep.

Why it matters: an executor focused on tasks/verification can complete all acceptance criteria while failing to create `20-06-SUMMARY.md` or update the completion tracking note.

Suggested fix: add `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md` and `.planning/ROADMAP.md` to `files_modified`; add a Task 6.6 or explicit acceptance checks for `test -f .../20-06-SUMMARY.md` and the final ROADMAP count.

### [LOW] Some Acceptance Greps Are Lax Rather Than Contract-Accurate

Evidence: `20-06-PLAN.md:187` claims all three `62c1b44` baseline JFRs must be cited, but the command only checks total match count `>=6`; it does not prove distinct 100/500/1000 citations. Also, the outcome case at `20-06-PLAN.md:177-183` expects `null-result:` but the live summary is `Plan 5 outcome: null-result` (`20-05-SUMMARY.md:50`), so it falls into the default branch.

Why it matters: not execution-blocking here, but these are weak executable contracts in a plan that treats greps as acceptance gates.

Suggested fix: check each tier explicitly, and match `*"null-result"*` or parse the value after `Plan 5 outcome:`.

| Severity | Title | One-line fix |
|---|---|---|
| HIGH | Profile Index missing `c22e487` rows | Explicitly add history-only `c22e487` artifact rows and greps |
| HIGH | D-19 de-staling too narrow | Update all stale `20-CONTEXT.md` and `profiles/README.md` baseline references |
| MEDIUM | Validation can omit 20-06 rows | Add 20-06 read/grep requirements and stronger row count |
| MEDIUM | Summary/ROADMAP untracked | Add files/tasks/acceptance for `20-06-SUMMARY.md` and ROADMAP |
| LOW | Lax greps | Make tier and outcome checks exact |

VERDICT: 0 BLOCKER, 2 HIGH, 2 MEDIUM, 1 LOW, 0 NIT

---

## Opencode Review

I'll systematically verify the plan against the actual repository. Let me start by reading the key files the plan references.Let me verify against the actual repository - checking source files, plan files, and profile directory.Let me verify the acceptance grep directly against the current file:Now let me verify the full suite run and check a few more details:Here's my review of `20-06-PLAN.md` against the actual repository:

---

### [HIGH] Acceptance grep `^\| (paralife\.tick...` can’t match due to markdown backticks

**File:** `20-06-PLAN.md:190`  
**Why:** The acceptance criteria grep `^\\| (paralife\\.tick\\.health\\.work-time-ms|paralife\\.outbound\\.detach\\.timeout)` resolves to `^\| (paralife\.tick...` which fails because the actual §4.2 table rows are:
```
| `paralife.tick.health.work-time-ms` (mean ms) | _Pending — Plan 1c ...
| `paralife.outbound.detach.timeout` (count) | _Pending_ ...
```
The backtick after `| ` means zero matches — the executor will think the table never satisfied the criterion.

**Fix:** Change to `grep -E "paralife\.(tick\.health\.work-time-ms|outbound\.detach\.timeout)" 20-RUNTIME.md | grep -vE "Pending|TBD" | wc -l`  
(`grep` confirmed 0 hits with the current pattern, 2 hits with the backtick-aware alternative.)

---

### [MEDIUM] 20-VALIDATION.md Wave 0 checkbox at line 67 still cites `c22e487`

**File:** `20-06-PLAN.md` (task 6.5 step 3); `20-VALIDATION.md:67`  
**Why:** The checkbox text says `jfr-{100,500,1000}bots-baseline-c22e487.jfr — Plan 1`. Plan 1c re-anchored to `62c1b44`; `c22e487` is superseded. Task 6.5 step 3 says to "tick each box" but doesn't explicitly say to rewrite the stale SHA in the box text. The sign-off update (step 4) covers the bottom-of-file section, not the Wave 0 checklist.

**Fix:** Add an explicit Rewrite instruction in Task 6.5 step 3: "Rewrite the `c22e487` reference in the Plan 1 checkbox to `62c1b44` (Plan 1c re-anchor), and note the superseded `c22e487` series retained on disk."

---

### [MEDIUM] `20-05-TRIAGE.md` line 1 format check is fine; plan over specifies

**File:** `20-06-PLAN.md:160`  
**Why:** Task 6.1 step 4 says "Verify the row is present and its outcome label is consistent with `20-05-TRIAGE.md` line 1 (`null-result: ...`)". The actual TRIAGE.md line 1 is `null-result: codec ≤1.8% CPU...` — matches. Verified. But the plan also says "Do NOT reformat toward any template", which is correct. However, the step says to read `20-05-MULTIREVIEW-EXEC-R1.md` — this file exists but the executor would need to know it's optional context. This is a documentation preference; the step is clear. This finding is borderline LOW but the plan's scope note about "already-populated cells described as to-fill" in the review brief makes it relevant for residual-shift checks.

**Resolution:** No change needed — verified correct. Remove this finding if you want only actionable items.

---

### [MEDIUM] §3 pending markers include `line 100` narrative that Plan 6 can't fully "replace"

**File:** `20-06-PLAN.md` (Task 6.1 step 1); `20-RUNTIME.md:100`  
**Why:** Line 100 says: "Plan 5 / Plan 6 will replace `Pending — JFR-driven` markers with measured-justified choices." After Plan 6 execution, this sentence is a resolved past-tense event, not a pending marker. The plan's step says to replace "ALL remaining `Pending — JFR-driven` markers", listing the 5 specific locations. This meta-narrative (line 100) and the heap-presets preamble (line 159) are in-scope for rewrite per step 1 ("heap-presets preamble"). The plan covers this — but if the executor only mechanically replaces the 5 explicitly listed markers without touching the preamble narrative, there'll be residual "will replace" wording that's stale.

**Fix:** Add an explicit note in Task 6.1 step 1: "Also rewrite the §3 preamble narrative at line 100 and line 159 from future-tense (`Plan 5 / Plan 6 will replace...`) to past-tense (`Plan 5 replaced...` — resolved at `424e06d`)."

---

### [LOW] CLAUDE.md insertion `<interfaces>` note has confusing direction

**File:** `20-06-PLAN.md:113`  
**Why:** The note says "BEFORE `### Outbound concurrency (Phase 17, D-10)` is already above" — Outbound concurrency IS above Connection model in the actual file (line 86 vs 130), so "already above" is factually correct but confusingly stated. The task action correctly says "AFTER the existing `### Connection model`". The executor follows the task body, not the interface note.

**Fix:** Rewrite to: "Insert between `### Connection model` (ends line 141) and `## Project Skills` (line 144)." (Optional — LOW severity.)

---

### [LOW] `OutboundSender.attachSession` insertion line ~132–135 off by ~1–2 lines

**File:** `20-06-PLAN.md:109`  
**Evidence:** `OutboundSender.java:132` is `detachSession(id);`. `Thread.ofVirtual()` is at line 136, not 135. The comment insertion point is "immediately above" line 136.

**Fix:** None needed — the task action provides the exact code block for the Edit tool to match. LOW.

---

### [NIT] 20-VALIDATION.md not in `<context>` block but in `files_modified`

**File:** `20-06-PLAN.md:8-9` vs `20-06-PLAN.md:71-85`  
**Why:** `20-VALIDATION.md` is in `files_modified` and in Task 6.5's `<read_first>` but not in the top-level `<context>` block. The executor will read it for Task 6.5 anyway.

---

### Summary

| Severity | Finding | One-line fix |
|----------|---------|-------------|
| HIGH | Task 6.1 acceptance grep `^\| (paralife\.tick...` fails due to markdown backticks | Use `grep -E "paralife\.(tick\.health\.work-time-ms\|outbound\.detach\.timeout)"` without `^\|` anchor |
| MEDIUM | 20-VALIDATION.md Wave 0 checkbox text still cites `c22e487` | Add explicit Rewrite instruction in Task 6.5 step 3 |
| MEDIUM | §3 preamble meta-narrative (line 100/159) may retain future-tense after execution | Add past-tense rewrite note to Task 6.1 step 1 |
| LOW | `<interfaces>` CLAUDE.md note has confusing "already above" wording | Rewrite for clarity (optional) |
| LOW | `OutboundSender` line refs off by ~2 lines | No fix needed — Edit uses code block match |
| NIT | 20-VALIDATION.md absent from `<context>` block | Add to context block |

**VERDICT: 0 BLOCKER, 1 HIGH, 2 MEDIUM, 2 LOW, 1 NIT**

The plan is fundamentally sound and executable. The HIGH is a mechanical grep bug (backticks) that would cause a valid execution to appear to fail its own acceptance check — trivially fixable. No residual staleness issues beyond the ones the plan already addresses (D-19 reconciliation, §6 parenthetical stripping). No gold-plating — Plan 6 is documentation-only close-out, which matches the phase scope.

---

## Consensus Summary

### Agreed Strengths
- Plan is fundamentally sound and executable; the de-stale work is correctly framed as "verify, don't re-derive" against already-shipped Plan 5 output (claude, opencode).
- No gold-plating — documentation-only close-out matches the phase scope (claude, opencode).

### Agreed Concerns
- **[HIGH] §4.2 gauge-population acceptance grep is unsatisfiable due to markdown backticks.** The pattern `^\| (paralife\.tick\.health\.work-time-ms|paralife\.outbound\.detach\.timeout)` anchors `| ` directly onto `paralife`, but the real §4.2 rows wrap the metric in a backtick (`| ` `` `paralife...` ``). Both reviewers ran it: 0 hits → `awk '$1 >= 2'` exits 1, failing the gate even when the table is fully and correctly populated. This is the gate for the D-13/D-18 headline numbers. Fix: match the leading backtick / drop the `^\|` anchor, e.g. `grep -E "paralife\.(tick\.health\.work-time-ms|outbound\.detach\.timeout)" | grep -vE "Pending|TBD"` (claude, opencode).
- **[MEDIUM, breadth-of-evidence HIGH] De-stale enumeration is narrower than the doc's actual residual staleness.** Three reviewers flag stale forward-references the plan's step-1 list doesn't cover, in sections Phase 21 consumes verbatim: §3 future-tense prose ("Plan 5/6 will replace…", L100/159), dangling JFR placeholder (L314 `jfr-1000bots-tuned-<HEAD>.jfr`, never shipped under that name), forward-ref prose at L119/143/155, plus 20-CONTEXT.md baseline/null-result references (L225/327/463) and `profiles/README.md` ("baseline always c22e487"). claude additionally notes step-6's audit grep matches L100 yet step 1 never lists it → the step fails its own audit. Fix: widen step 1 to rewrite all future-tense/forward-ref prose to settled past tense, add `profiles/README.md` to `files_modified`, and broaden the audit regex (claude, codex, opencode).
- **[MEDIUM] 20-VALIDATION.md Wave-0 still cites the superseded `c22e487` baseline while Task 6.5 flips the sign-off to `62c1b44`.** Post-execution the file asserts both "canonical = 62c1b44" (sign-off) and "baseline = c22e487" (Wave-0 checkbox L67 / manual rows L81–84), reading as a self-contradiction. Fix: add an explicit instruction in Task 6.5 step 3 to rewrite the `c22e487` reference to `62c1b44` (Plan 1c re-anchor) and annotate the `c22e487` series as history-only/retained on disk (claude [LOW], opencode [MEDIUM], related: codex, gemini).
- **[LOW] Line-count case-statement / outcome-label colon mismatch.** The acceptance classifier matches `*"null-result:"*` (with colon), but the live signal is `Plan 5 outcome: null-result` (no colon after the value) → falls through to the default branch. Benign for this null-result run (both floors = 250), but a latent underclaim: a `triaged`/`runtime-knob-tightened` outcome would silently degrade the 350 floor to 250 — the exact regression the contract was meant to prevent. Fix: drop the colons in the case patterns (claude, codex).
- **[LOW] Lax baseline-JFR citation acceptance grep.** The `jfr-(100|500|1000)bots-baseline-62c1b44.jfr` check requires `>= 6`, but exactly 6 already exist before the executor adds the narrative paragraphs — so it passes without verifying the new citations and doesn't prove distinct per-tier coverage. Fix: raise the threshold (gemini suggests 9) and/or assert each tier explicitly (gemini, codex).

### Divergent Views
- **AdmissionMetrics.java line numbers — direct contradiction worth resolving first.** gemini read the source and reports `M_TICK_WORK_MS` at line 52 and `M_DETACH_TIMEOUT` at line 59, declaring the `:70`/`:79` grep unsatisfiable (HIGH). claude ran `grep -n` and reports `:70`/`:79` as exact-at-current-HEAD (✅ in its passed-checks table). Only one can be right — verify the live line numbers before acting; if claude is correct the grep is fine, if gemini is correct it's a second HIGH. Either way, the robust fix gemini proposes (`AdmissionMetrics\.java:[0-9]+` regex, since the plan itself warns the constants drift) closes the question regardless.
- **Profile Index missing `c22e487` rows (codex, solo HIGH).** codex says §6 lists only `62c1b44`/`103a615`/`424e06d` and the plan's "index already structurally complete" claim plus its greps will silently ship without the real history-only `c22e487` rows. claude's passed-checks table instead records §6 placeholder fills as verified and present on disk, without flagging a missing-row gap. Worth a direct check of whether `c22e487` rows exist in the §6 index table.
- **Untracked close-out artifacts (codex, solo MEDIUM).** codex flags that `20-06-SUMMARY.md` and the ROADMAP completion update live only in the output block with no task/`files_modified` entry/acceptance check, and that the 20-VALIDATION row-count floor (`>=16`) can be satisfied without any `20-06` rows. No other reviewer raised these; assess whether they're real gaps or covered by the output-block convention.
- **Task 6.4 `./gradlew test` gate lacks a TD-22-E infra-flake note (claude, solo MEDIUM).** A comment-only change is gated on a command that BUILD-FAILED under `forkEvery=1` XML contention in the immediately prior plan; claude wants a single-retry-on-infra-flake clause mirroring Plan 5's Concern #14. Not raised by others — judge whether the false-negative-ship risk warrants the note.
