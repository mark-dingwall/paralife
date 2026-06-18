---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-06-04T01:46:01Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-05-PLAN.md"]
models:
  claude: "claude-opus-4-7"
  codex: "gpt-5"
  gemini: "gemini-3.1-pro-preview"
usage:
  claude: { input: 10, output: 16, cached: 54964, tool_calls: 0, elapsed_s: 104.2 }
  gemini: { input: 371960, output: 845, cached: 0, tool_calls: 1, elapsed_s: 406.5 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 3.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 16, elapsed_s: 392.4 }
synthesizer: claude
synthesized_at: 2026-06-04T01:46:01Z
---

# Cross-AI Review

## Claude Review

R3 review of Plan 20-05. Focused on R2-fix new surfaces + R1/R2-untouched areas.

## Findings

```
[MEDIUM] L591-598 (Task 5.2 step 9 templating grep contract): The outcomes 2/3/4 templating example `OPTS=$(grep -E '^(triaged|runtime-knob-tightened|null-result|pinning-dominates-with-backlog-handoff):' 20-05-TRIAGE.md | head -1)` assumes Task 5.0/5.1 wrote one of those literal labels as start-of-line in TRIAGE.md. But Task 5.0's resume-signal block uses those labels only as REPLY to orchestrator (L399-403 + L432-435), not as a mandated file-content contract. Task 5.1 outcome 2/3/4 record instructions ("Record the new default + JFR signal in 20-05-TRIAGE.md", "Record the null-result rationale in 20-05-TRIAGE.md", "documented pinning finding cited in 20-05-TRIAGE.md") never mandate the literal label prefix. For an outcome-3 run where executor writes `## Null-result rationale` instead of `null-result: ...`, OPTS resolves empty, jq sets the field to "" or null, sentinel grep passes silently, committed JSON has uninformative `"opts_applied_summary": ""`. Same gap Pass-3 Concern #29 already fixed for Plan 5→Plan 6 SUMMARY handoff. Fix: add explicit TRIAGE.md contract — "the FINAL line written by Task 5.0 (and updated by Task 5.1 for outcome 2 if the label changes) MUST be exactly `<signal>: <evidence>` matching the resume-signal grammar" — and add a hard guard in Task 5.2 (mirror the TEMPLATE_PLACEHOLDER_REPLACE_BEFORE_COMMIT pattern): `[ -n "$OPTS" ] || { echo "FATAL: outcome label not found in 20-05-TRIAGE.md — see contract"; exit 1; }`. (citation: 20-05-PLAN.md L591-598 templating block vs Task 5.0 resume-signal L432-435 / Task 5.1 outcome-2/3/4 record instructions L356, L361, L367)
```

```
[LOW] L540-541 (sample-count gate leaves orphan JFR on disk): The kill-order fix from R2 M1 cleans up processes, but JFR was started inline via `-XX:StartFlightRecording=filename="$JFR_OUT"`. At FATAL exit, JFR_OUT exists as a partial truncated dump (JFR flushes on JVM SIGKILL per Plan 4 §3 intro tradeoff note). Next executor running the capture script sees an existing file at JFR_OUT path — `ls -lh "$JFR_OUT"` at step 6 will succeed (false-positive), `jfr summary` will print something (still likely valid), the gate that JFR ≤10 MB likely passes. The truncated dump silently advances through Plan 5 acceptance. Fix: prepend the fatal-exit cleanup with `rm -f "$JFR_OUT" "$METRICS_OUT"` so a half-captured run doesn't masquerade as complete. (citation: 20-05-PLAN.md L540-541 sample-count gate + L582 step 6 `ls -lh "$JFR_OUT"` verification; Plan 4 SIGTERM tradeoff note in 20-RUNTIME.md §3 intro)
```

```
[NIT] L670-674 (`^@Disabled\b` start-of-line): The acceptance grep assumes class-level `@Disabled`. Per 22-SUMMARY the 4 P22 tests are class-level (TD-22-A..D); pragmatic OK. But future P22.1 work re-enables individual cases under `@Nested` inner classes — those `@Disabled` annotations would be indented and silently skip the check. Fix: tolerable as-is since the 4 named files are class-level; document the assumption as a one-line comment so a future executor extending the gate to nested-class disables knows to relax the anchor. Or use `grep -qE '^[[:space:]]*@Disabled\b'` for forward-compat. (citation: 20-05-PLAN.md L670-674; 22-SUMMARY.md TD-22 entries are class-level)
```

```
[NIT] L598-606 (meta.json sentinel guard runs after step 9 jq update but before §10 RUNTIME edits): The hard-fail sentinel check at L607-610 exits before `20-RUNTIME.md` §4.2/§4.4/§6 edits. Correct ordering. But the meta.json templating block in step 9 is in a comment (`# Outcome 1...`), not enforced code — executor copy-pastes manually. If executor skips the templating step entirely, sentinel fires correctly. If executor pastes outcome-1 awk against an outcome-3 TRIAGE, OPTS="" lands as `"opts_applied_summary": ""`, sentinel doesn't fire (no `TEMPLATE_PLACEHOLDER_REPLACE_BEFORE_COMMIT` string), commit lands with empty field. Same root cause as MEDIUM above. Fix folded into MEDIUM.
```

## Convergence judgement

**No new HIGH+ findings.** One MEDIUM (TRIAGE outcome-label contract — direct mirror of Pass-3 #29's SUMMARY contract, same fix shape), one LOW (orphan-JFR cleanup), two NITs (forward-compat + redundant cross-ref to MEDIUM). 

R2 fixes verify: kill-order at sample-gate hardened (no leak), bare-filename meta.json consistent across `metric_sidecar`/`baseline_pair`/`metric_baseline_pair`, level-only Counter rule sound under outcome 4 (no false-positive — baseline is 0, codebase unchanged so tuned ≈ 0), Task 5.0 decision tree precedence (1→4→2→3) structurally enforced via "only reached if outcomes 1 and 4 do NOT fire" prose at L429-431.

**Convergence reached.** One ~10-line polish closes the MEDIUM (TRIAGE label contract + hard guard in step 9). LOW is one-line `rm -f` prepend. NITs are documentation-grade. Plan executable as-is for outcome 1 (executor writes `### Opt N:` per Task 5.1 step 5 contract); the gap is only at outcomes 2/3/4 where the resume-signal label was never load-bearing as file content.

---

## Gemini Review

[HIGH] L5: The frontmatter 'files_modified' list omits the configuration and test files that would be modified under Outcome 2 (runtime-knob tightening). Add the potential Outcome 2 target files (e.g., `JettyRuntimeConfig.java`, `AdmissionConfig.java`, their test files, and `JettyDeflateCustomizer.java`) to the 'files_modified' array so downstream tooling correctly tracks them. (citation: 20-05-PLAN.md:5-13 vs L254-263)

[HIGH] L516: The comments for `detach.timeout` instruct the executor that if the tuned value is non-zero AT ALL, it is a regression flag that blocks claiming equivalence for outcome 3 (null-result) or outcome 4 (pinning-dominates). Restrict the 'non-zero = regression' rule to outcomes 1 and 2; explicitly state that for outcomes 3 and 4 (where no code is changed), a non-zero value is environmental noise and does not break equivalence. (citation: 20-05-PLAN.md:516-518)

[HIGH] L588: The grep extracting the `opts_applied_summary` for Outcomes 2/3/4 looks for lines starting with 'null-result:' or 'runtime-knob-tightened:' in 20-05-TRIAGE.md, but Task 5.0 only instructs the executor to use these prefixes in the *resume-signal reply*, not within the file itself. Explicitly instruct the executor in Task 5.0 to write the exact resume-signal label at the top of 20-05-TRIAGE.md, or change the fallback logic to capture the first few lines of the file. (citation: 20-05-PLAN.md:588-589 vs L202-211)

[MEDIUM] L425: The disabled-tests grep `^@Disabled\b` requires the annotation to be at the exact start of the line with no leading whitespace, which will falsely fail and abort the script if the annotation is applied to an indented method. Change the regex to `^[[:space:]]*@Disabled\b`. (citation: 20-05-PLAN.md:425-428)

[LOW] L202: The verification checklist requires 20-05-TRIAGE.md to be '≥30 lines', which could cause executor hesitation or filler-padding for outcome 3 (null-result) or outcome 4 (pinning-dominates) where a concise summary is expected. Relax the line-count floor for outcomes 3 and 4, similar to how Plan 6's line-count floor was tiered in R2. (citation: 20-05-PLAN.md:202)

[NIT] L493: `kill -KILL $SERVER_PID $HARNESS_PID` kills the server before the harness, which could cause the harness to briefly log connection-reset errors when the server drops. Change to `kill -KILL $HARNESS_PID $SERVER_PID 2>/dev/null` to kill the load generator first. (citation: 20-05-PLAN.md:493)

Convergence judgement
There are NEW issues at HIGH severity this round. The frontmatter omission for Outcome 2 risks breaking tracking tools, the false-positive regression trap on detach.timeout could block outcomes 3 and 4, and the `opts_applied_summary` grep mismatch will silently corrupt the meta.json templating. One more round of structural fixes is required before execution.

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 3.2s

---

## Opencode Review

Now let me verify a few more things for the review:Now let me verify the exact indentation of all three actually-@Disabled tests:Here are the R3 findings:

**[BLOCKER]** L308-311,331-332: `grep -qE '^@Disabled\b'` never matches — all three `@Disabled` annotations in the codebase are indented with 4 spaces (`    @Disabled(...)`). The `^` start-of-line anchor (added by R2 N1) silently breaks the D-12 disabled-tests enforcement in both the final invariant check and acceptance criteria. The `&&` chain fails on `$METAB` (the first check), no subsequent file is ever tested. Fix: use `grep -qE '^\s*@Disabled\b'` or remove the `^` anchor. (citation: 20-05-PLAN.md:308-311,331-332 vs actual source — MetabolismIntegrationTest.java:82, EncodeDeflatePerformanceGateTest.java:112, PopulationDynamicsTest.java:96 — all carry 4-space indent)

**[HIGH]** L303-311,332: HundredBotIntegrationTest carries no `@Disabled` annotation at all. The plan repeatedly asserts "4 @Disabled P22 tests" and the per-file grep searches for `@Disabled` on Hundred, but the file has zero `@Disabled` markers. The check fails independently of finding #1. Fix: remove Hundred from the @Disabled enforcement (3 remaining tests only), or document it as "active known-flaky" with a separate test-method-existence check. (citation: 20-05-PLAN.md:303-311,332 vs HundredBotIntegrationTest.java — grep confirms zero `@Disabled` occurrences)

**[MEDIUM]** L521: Readiness-loop FATAL message says "not 200 after 30s" but the loop can run up to ~90s worst-case (30 polls × ≤3s each per the corrected comment at L511-513). An executor hitting this after a long boot sees "30s" and may assume the script is broken, not just patient. Fix: change "30s" to "~90s (30 polls)". (citation: 20-05-PLAN.md:521 vs comment at L511-513)

**[LOW]** L598-599: The commented-out `opts_applied_summary` grep searches TRIAGE.md for `^(triaged|runtime-knob-tightened|null-result|pinning-dominates-with-backlog-handoff):` at line-start. Task 5.0 prescribes these only as chat resume-signal formats, not as mandatory TRIAGE.md output. If the executor writes TRIAGE.md as free-form prose without these labels at column 0, the grep returns empty — recoverable (sentinel guard fires), but the misleading comment can waste time. Fix: add "if the grep returns empty, manually set OPTS to the resume-signal label" or prescribe the label-at-line-start convention in Task 5.0's output spec. (citation: 20-05-PLAN.md:598-599 vs Task 5.0:217-228)

---

**Convergence judgement:** BLOCKER + HIGH findings present — R3 **not converged**. One polish round: fix `^@Disabled\b` to `^\s*@Disabled\b` (4 sites), remove Hundred from the check list (4 sites), update the FATAL message, add the grep note.

**On the other surfaces:**
- Surface 1 (`kill -KILL` order): No issue — `kill` signals both PIDs, order doesn't matter with `-KILL`.
- Surface 3 (level-only detach.timeout under outcome 4): The "investigate" wording (not "fail") is a conservative trigger, not a false-positive gate. No issue.
- Surface 5 (grep matching TRIAGE.md): Covered as LOW finding #4 above — recoverable but imprecise.
- Surface 6 (cross-finding interactions): No contradictions found. R2 bare-filename fix for meta.json is internally consistent; R2 jq schema-split comment correctly documents the key difference.
- Surface 7 (R1/R2-untouched surfaces): Task 5.0 decision tree correctly implements D-21 four-outcome precedence (1→4→2→3). Task 5.1 single-retry-then-revert protocol present. Success criteria cover all 4 outcomes. Output block has required first-line format. No issues.
- Surface 8 (new): JFR 180s window + harness 200s duration leaves ~10-13s of harness teardown uncaptured. Not flagged — the JFR captures steady-state through t=195s and the teardown tail is benign; the plan's R5/R6 trades explicitly document the front-cushion/shape mismatch.

---

## Consensus Summary

### Agreed Strengths
- R2 fixes verified intact across reviewers: kill-order at sample-gate, bare-filename meta.json consistency, decision-tree precedence (1→4→2→3), Task 5.0 resume-signal structure.
- No new HIGH+ findings on R1/R2-untouched surfaces (Task 5.0 decision tree, Task 5.1 single-retry-revert, success criteria, output block).

### Agreed Concerns
- **[HIGH/MEDIUM] TRIAGE.md outcome-label contract gap (L588-598)** — claude, gemini, opencode all flag: `grep -E '^(triaged|runtime-knob-tightened|null-result|pinning-dominates-with-backlog-handoff):'` against TRIAGE.md, but Task 5.0 only prescribes those labels as resume-signal reply format, not as mandated file content. Outcome 2/3/4 silently produces empty `opts_applied_summary` with no sentinel fire. **Fix:** mandate label-at-line-start in Task 5.0's TRIAGE.md output spec + add hard guard `[ -n "$OPTS" ] || { echo FATAL; exit 1; }` in step 9.
- **[BLOCKER/MEDIUM] `^@Disabled\b` anchor too strict (L308-311, 331-332, 425, 670-674)** — opencode (BLOCKER, with code verification), gemini (MEDIUM), claude (NIT) converge: all `@Disabled` annotations in codebase are 4-space indented; `^` anchor never matches; D-12 enforcement silently broken. **Fix:** `^[[:space:]]*@Disabled\b` or `^\s*@Disabled\b`.

### Divergent Views
- **HundredBotIntegrationTest @Disabled status** — opencode (HIGH) reports zero `@Disabled` annotation in the file via grep; claim is the "4 disabled tests" assertion is wrong by one. Claude/gemini did not flag. Worth verifying: if true, remove Hundred from the @Disabled check list (4 sites).
- **detach.timeout regression rule under outcomes 3/4 (L516-518)** — gemini flags HIGH (non-zero is environmental noise when no code changed, blocks null-result/pinning equivalence); opencode explicitly disagrees ("investigate" wording is conservative trigger, not fail-gate); claude did not flag. Worth investigating: re-read the wording and clarify whether outcomes 3/4 are blocked or merely flagged.
- **Frontmatter `files_modified` omits Outcome 2 targets (L5)** — gemini HIGH; claude/opencode did not flag. Likely low-impact (frontmatter is descriptive, not enforced), but cheap to add.
- **Orphan JFR cleanup on FATAL (L540-541)** — claude LOW (truncated JFR masquerades as complete next run); not flagged by others. Cheap one-line `rm -f` prepend.
- **Readiness-loop FATAL message "30s" vs ~90s worst case (L521)** — opencode MEDIUM; cosmetic clarity, not load-bearing.
- **Kill order `kill -KILL $SERVER_PID $HARNESS_PID` (L493)** — gemini NIT (harness logs connection-reset); opencode explicitly disagrees (order doesn't matter with `-KILL`). Cosmetic.
