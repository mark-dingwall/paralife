---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-06-04T01:33:49Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-05-PLAN.md"]
models:
  claude: "claude-opus-4-7"
  codex: "gpt-5"
  gemini: "gemini-3.1-pro-preview"
usage:
  claude: { input: 10, output: 0, cached: 54964, tool_calls: 0, elapsed_s: 207.7 }
  gemini: { input: 170278, output: 317, cached: 0, tool_calls: 0, elapsed_s: 117.2 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 3.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 12, elapsed_s: 482.1 }
synthesizer: claude
synthesized_at: 2026-06-04T01:33:49Z
---

# Cross-AI Review

## Claude Review

R2 review of `20-05-PLAN.md`. Auto-clarity ON for findings (precision needed); caveman for chrome.

## Findings

```
[HIGH] L545-553 (step 7 detach.timeout delta): Baseline metric sidecar has 18 samples × 5s = 90s window per 20-01c capture shape; Plan 5 tuned sidecar has 6 samples × 5s = 30s window per step 5 loop. `paralife.outbound.detach.timeout` is a monotonic Counter, so `.measurements[] | select(.statistic=="COUNT") | .value | .[-1]` reads cumulative count over structurally different durations. Baseline currently 0/sample per 20-01c (issue dormant), but if tuned shows any non-zero count it compares against a 3× longer baseline accumulation — false "tuned looks better" or wrong-direction delta. Fix: either (a) extend tuned `for i in 1 2 3 4 5 6` loop to 18 iterations to match baseline window, (b) report `(.[-1] - .[0]) / window_s` as a rate, or (c) explicitly document "detach.timeout delta is zero/non-zero level only, not count-comparable across asymmetric windows" and gate any non-zero tuned value as regression-flag regardless of magnitude. (citation: 20-05-PLAN.md:545-553 baseline jq vs step 5 6-sample loop; baseline shape in 20-01c-SUMMARY §"9 meters × 6 samples" + §Active-Population Workload header table noting 18 samples)
```

```
[MEDIUM] L540 (sample-count gate exit): `[ "$NON_NULL" -ge 4 ] || { echo "FATAL: only $NON_NULL/6 work_time_ms samples are non-null"; exit 1; }` leaks both $SERVER_PID and $HARNESS_PID — script aborts without killing either. The readiness loop at L505 correctly does `kill -KILL $SERVER_PID 2>/dev/null` before exit; the sample-count gate doesn't. JFR auto-stop at t=195s never reached; harness keeps trying to register; server holds port 8080 until reaped by init. Fix: replace exit-1 with `{ kill -KILL $SERVER_PID $HARNESS_PID 2>/dev/null; exit 1; }`. (citation: 20-05-PLAN.md:540 vs L505 readiness-loop pattern)
```

```
[MEDIUM] L591 (opts_applied_summary templating example): Comment-block example `OPTS=$(awk '/^### Opt/,/^$/' 20-05-TRIAGE.md | grep '^### Opt' | sed 's/^### //' | paste -sd'; ')` works only for outcome 1 (codec opts have `### Opt N:` headings per L379 record-format). For outcomes 2/3/4 the TRIAGE has no `### Opt` headings — awk emits empty, `OPTS=""`, jq sets the field to empty string, sentinel grep passes (no sentinel present), committed JSON has uninformative `"opts_applied_summary": ""`. The comment "(or for outcome 2/3/4, set OPTS to the resume-signal label)" is prose not enforced. Fix: add a second example block:
    # for outcomes 2/3/4 (no ### Opt headings):
    OPTS=$(grep -E "^(null-result|runtime-knob-tightened|pinning-dominates-with-backlog-handoff):" 20-05-TRIAGE.md | head -1)
   so executor doesn't read-and-paste the outcome-1 form into an outcome-3 run. (citation: 20-05-PLAN.md:591-598 templating comment block vs L379 Opt-record schema)
```

```
[MEDIUM] L612 (§6 flamegraph row "Captured: _Plan 5_" contradicts deferral): Rewritten row text is `| ... | async-profiler flamegraphs (tuned, active) | _deferred to Plan 6 — JFR-event triage suffices for Plan 5_ | _Plan 5_ | — | flamegraph (deferred) |`. The "Captured" cell says `_Plan 5_` but the row is explicitly deferred to Plan 6 — internally contradictory. If Plan 5 didn't capture, Captured should be `_deferred_` or `_n.a._`. Fix: change the 4th cell to `_deferred_`. (citation: 20-05-PLAN.md:612)
```

```
[LOW] L540 (sample-count floor of 4/6): D-21 noise-floor convention permits ±1σ as alternative to ±5%; σ-estimate at n=4 is structurally noisy (df=3). The ≥4 floor is defensible for the ±5%-mean leg but borderline for the ±1σ leg. Fix: bump to ≥5/6, or add inline note "±5% mean comparison is primary at n<5; ±1σ branch of D-21 requires n≥5". (citation: 20-05-PLAN.md:540 vs D-21 noise-floor convention in 20-CONTEXT.md)
```

```
[LOW] L495 (actuator readiness budget mis-described): Comment "Up to 30s of 1s polls" understates real timing. Each iteration is up to `--max-time 2` (curl) + `sleep 1` = up to 3s; 30 iterations is up to 90s worst-case wall-clock. Vanilla Spring Boot ready in ~5-8s so usually fine, but the 30s vs 90s framing matters if a future executor adds a CI timeout around the capture step. Fix: either tighten to `timeout 30 bash -c 'for i in $(seq 1 15); do ... done'` (real 30s cap) or update comment to "Up to 30 polls (≤90s worst case)". (citation: 20-05-PLAN.md:495-505)
```

```
[LOW] L654-656 (T-20-V5 grep doesn't value-anchor): `grep -E "MAX_S_ENTRIES|MAX_V_ENTRIES" PerceptionCodec.java | grep -vE '^[[:space:]]*(//|\*)'` matches the constant names anywhere (declarations, throw messages, javadoc-stripped-via-filter). A regression like `MAX_S_ENTRIES = 512` would still match. Stronger check: `grep -E "(MAX_S_ENTRIES = 256|MAX_V_ENTRIES = 32)" PerceptionCodec.java` exits 0 iff both literal values intact. Fix: switch to literal-anchored grep, OR keep the presence check but add a second assertion: `grep -q 'MAX_S_ENTRIES = 256' && grep -q 'MAX_V_ENTRIES = 32'`. (citation: 20-05-PLAN.md:654-656 + acceptance L641; PerceptionCodec.java:29,35)
```

```
[LOW] L573-585 (detach.timeout 6× polling is wasteful but harmless): Counter is monotonic-cumulative; reading it 6× in 30s gives no information beyond reading it once at the loop tail. Not a correctness issue. Fix (optional): poll the Counter only at the final sample iteration, or accept the 5 redundant curls as observability symmetry with the Gauge polling. (citation: 20-05-PLAN.md:573-585)
```

```
[NIT] L670 (D-12 disabled grep can match comment text): `grep -q '@Disabled' "$METAB"` matches the literal anywhere — `// removed @Disabled when X` or `* @Disabled is intentional` would both pass. Fix: `grep -qE '^@Disabled\b' "$METAB"` (start-of-line) or `grep -B1 'class MetabolismIntegrationTest' "$METAB" | grep -q '@Disabled'` (immediately preceding class decl). (citation: 20-05-PLAN.md:670-674)
```

```
[NIT] L546-549 (jq schema-split commentary doesn't note rate-invariance): Long explanatory comment explains why baseline uses `paralife_tick_health_work-time-ms` (Micrometer-flat) and tuned uses `work_time_ms` (short). Doesn't mention that `mean` is window-invariant (rate-like) so the 18-sample-vs-6-sample comparison is statistically valid for the gauge, but NOT for the cumulative-counter detach.timeout (re HIGH finding above). Fix: append one sentence to the schema-split comment block: "Note: mean(work_time_ms) is window-invariant; the detach.timeout COUNT comparison below has the asymmetric-window issue noted at L549." (citation: 20-05-PLAN.md:546-549)
```

## Convergence judgement

Plan executable as-is for outcomes 3 (null-result) and 4 (pinning-dominates) — both expected to show baseline=0/tuned=0 on detach.timeout, so HIGH finding stays dormant. For outcomes 1 (codec opts ship) and 2 (knob tightening), HIGH detach.timeout-window-asymmetry should be addressed pre-execution — one-line change to either extend the sample loop to 18 iterations or rewrite the metric as rate/threshold. MEDIUM process-leak fix is a 1-line append. MEDIUM outcome-2/3/4 templating example is a 3-line comment block addition. MEDIUM §6 cell contradiction is a 1-word edit.

One short polish round closes this. No structural rework. R2 convergence reachable.

---

## Gemini Review

[HIGH] Task 5.2 step 2: The `SERVER_JAR` assignment uses an ambiguous glob that will pick the non-executable `-plain.jar`, causing the capture server to fail on boot. Add `| grep -v -- '-plain'` to the assignment pipeline, matching the fix already applied to the recipes in Plan 20-04. (citation: 20-05-PLAN.md Task 5.2 vs 20-04-SUMMARY.md Pass-3 Concern #24)

[LOW] Frontmatter: The tuned-state `meta.json` sidecar is created by Task 5.2 but is missing from the `files_modified` list. Add `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-active-50xfood-tuned-HEAD.meta.json` so downstream tooling correctly tracks the artifact. (citation: 20-05-PLAN.md frontmatter vs Task 5.2 step 9)

## Convergence judgement
The plan is structurally solid and correctly implements the R1 dispositions (safe jq empty-array handling, robust actuator metric polling, deferred flamegraph rows, and template sentinels). The `SERVER_JAR` ambiguity is a critical execution-time blocker but is a trivial one-line bash fix. Once that is applied, the plan is fully executable and ready to ship. No further structural review passes are needed.

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 3.0s

---

## Opencode Review

Now let me check the Plan 1c active metric sidecar shape to ground the jq schema assumption:Ground-truth verification complete. Here are the findings:

## Findings

**[MEDIUM] L565-580:** Task 5.2 step 9 meta.json path conventions are inconsistent. `metric_sidecar` uses bare filename (`metrics-1000bots-active-50xfood-tuned-${HEAD_SHA}.json`) — matching the existing Plan 1c convention (`jfr-1000bots-baseline-62c1b44.meta.json` uses `"metric_sidecar": "metrics-1000bots-baseline-62c1b44.json"` with no prefix). But `baseline_pair` and `metric_baseline_pair` use `profiles/` prefix. Within a single JSON, a consumer can't tell whether paths resolve from the profiles/ directory or the phase directory. Fix: drop the `profiles/` prefix from both `baseline_pair` and `metric_baseline_pair` to align with the existing bare-filename convention. (citation: 20-05-PLAN.md L579-580 vs `jfr-1000bots-baseline-62c1b44.meta.json:12`)

**[LOW] L542:** Task 5.2 step 10 says "20-RUNTIME.md line 391 currently has a placeholder row" for the flamegraph rewrite. Post-Plan-4 R4/R5/R6 polish rounds added ~60+ lines of §3 intro caveats after that file was last indexed. The executor searching for L391 lands on unrelated content. Fix: drop the line number; identify the row by its cell content anchor (e.g., "the row containing `{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html` in §6"). (citation: 20-05-PLAN.md L542; 20-RUNTIME.md §3 has grown since Plan 4's initial 322-line skeleton per 20-04-SUMMARY.md)

**[LOW] L488-496:** The `baseline_capture_shape` field in meta.json documents the 6-vs-18 sample-count delta between tuned and baseline sidecars but doesn't flag the statistical asymmetry: at N=6, σ is ~73% wider than at N=18 (σ ∝ 1/√n), inflating the D-21 ±1σ noise-floor barrier on the tuned side. Plan 6 §4.2 should note this methodological bias toward "within noise floor." (citation: 20-05-PLAN.md L486-496 `baseline_capture_shape`; D-21 noise-floor convention uses ±1σ)

**[NIT] L560:** The `awk + paste` templating example in the `opts_applied_summary` comment references `20-05-TRIAGE.md` without the full `.planning/phases/20-connection-multiplexing-runtime-tuning/` prefix. The executor must already be `cd`'d into the phase directory for the awk command to find the file. Fix: either add the full relative path, or note the assumed CWD. (citation: 20-05-PLAN.md L560)

## R1 fix verification (all checks pass)

| R1 finding | Ground-truth verification |
|---|-------------|
| #1 jq schema mismatch | Verified: baseline sidecar keys are exactly `paralife_tick_health_work-time-ms` and `paralife_outbound_detach_timeout` at sample top-level, with `.measurements[]` inner shape. Both jq aggregations produce valid numeric output against the real `metrics-1000bots-active-50xfood-103a615.json` |
| #2 flamegraph row orphan | Verified: L542 instruction now says rewrite to deferred-to-Plan-6 with active-50xfood filename shape |
| #3 actuator readiness no-abort | Verified: 30× 1s poll loop with `kill -KILL` + `exit 1` on persistent non-200 present at L511-522 |
| #4 heredoc placeholder | Verified: `TEMPLATE_PLACEHOLDER_REPLACE_BEFORE_COMMIT` sentinel + `grep -q` guard present at L568, L582-585 |
| #5 curl no error guard | Verified: `curl -fsS --max-time 5 \|\| echo null` at L531-532; `≥4/6 non-null` sanity gate at L537-538 |
| #6 meta.json filename | Verified: `META_OUT="${JFR_OUT%.jfr}.meta.json"` at L577 — correctly strips `.jfr` |
| #8 T-20-V5 grep filter | Verified: `grep -vE '^[[:space:]]*(//\|\*)'` at both action block and acceptance criteria — correctly strips javadoc ` *` lines |
| #9 jq empty-array guard | Verified: all 4 jq aggregations have `if length == 0 then null else ... end` |
| #10 T-20-V7 glob | Verified: threat model now uses `jfr-1000bots-active-50xfood-tuned-*.jfr` + metric sidecar shape |
| #13 outcome priority | Verified: Objective lists outcomes 1–4 with precedence 1→4→2→3; Task 5.0 decision tree enforces this structurally |

## Surface re-scrutiny specifics

| Surface | Verdict |
|---------|---------|
| 1. Poll-and-abort (30s budget) | Adequate for 1000-tier with `-Xms2g`. `$CODE` uses `\|\| echo 000` fallback; fatal message shows last code. `kill -KILL` guarded by `2>/dev/null`; if java crashed early, PID may be recycled but probability is negligible for human-guided script |
| 2. jq schema split comment | Clear: the L528-533 comment block explains why baseline uses Micrometer-flat keys and tuned uses short keys. A future executor would not reasonably try to "fix" them to match |
| 3. Sentinel + grep for outcomes 2/3/4 | The comment at L568 says `# (or for outcome 2/3/4, set OPTS to the resume-signal label)` — explicitly covers the single-label case |
| 4. Sample-count sanity floor (≥4/6) | Adequate as a broken-capture catch, not a statistical proof. The noise-floor convention handles the stat question |
| 5. §6 flamegraph row-rewrite | Instruction is unambiguous (exact cell content, deferral language, active-scenario filename shift) — verified in plan |
| 6. File line count | **611 lines** on disk — matches R1 commit message claim exactly |
| 7. META_OUT cross-reference | All 3 uses correctly reference `${JFR_OUT%.jfr}.meta.json`; no code path uses the pre-R1 `.jfr.meta.json` form |

## Convergence judgement

Executable with one clarification pass on the MEDIUM finding. The meta.json path inconsistency (#1) is the only ambiguity that could bite Plan 6's consumer. The LOW findings are defensive polish but don't block execution. R1 fixes all verify against actual files on disk — no regressions.

---

## Consensus Summary

### Agreed Strengths
- R1 fixes verified in plan (jq schema split, actuator readiness loop, heredoc sentinel + grep guard, empty-array jq guards, META_OUT filename, T-20-V5/V7 glob updates) — both claude and opencode independently confirm against on-disk artifacts.
- Plan structurally sound and executable for outcomes 3 (null-result) and 4 (pinning-dominates) without further rework — claude and gemini converge.
- Decision tree (outcomes 1→4→2→3) and §6 flamegraph deferral language are unambiguous — claude and opencode agree.

### Agreed Concerns
- **[MEDIUM/HIGH] Tuned-vs-baseline sample-count asymmetry (6 vs 18 samples)** — claude flags this as HIGH for the `paralife.outbound.detach.timeout` cumulative-counter comparison (window-asymmetric, miscompares); opencode flags as LOW for the σ-noise-floor inflation on the gauge mean (±1σ widens ~73% at n=6). Both surface the same root asymmetry. Fix: extend tuned loop to 18 iterations OR convert detach.timeout read to a rate/level-only check AND note σ-inflation caveat for Plan 6 §4.2.
- **[MEDIUM] `opts_applied_summary` templating only covers outcome 1** — claude and opencode both note the `awk '/^### Opt/...'` example produces empty string for outcomes 2/3/4 (no `### Opt` headings exist). Fix: add a second example block for the resume-signal-label form, or make the comment enforceable rather than prose-only.

### Divergent Views
- **[HIGH] `SERVER_JAR` glob picks `-plain.jar`** (gemini only) — gemini cites the Plan 20-04 Pass-3 #24 precedent fix; claude and opencode did not raise this. Worth verifying: if the Task 5.2 step 2 glob is unguarded, this is a real boot-time blocker regardless of other findings. One-line fix (`| grep -v -- '-plain'`).
- **[MEDIUM] meta.json path-prefix inconsistency** (opencode only) — `metric_sidecar` uses bare filename per existing Plan 1c convention, but `baseline_pair`/`metric_baseline_pair` use `profiles/` prefix in the same JSON. Plan 6 consumer ambiguity. Claude and gemini did not flag.
- **[MEDIUM] Sample-count gate leaks $SERVER_PID/$HARNESS_PID on exit** (claude only) — `exit 1` at the ≥4/6 floor bypasses the kill cleanup pattern used elsewhere. Process-leak only; not output-correctness. Gemini and opencode did not flag.
- **[LOW] missing meta.json in frontmatter `files_modified`** (gemini only) — bookkeeping miss; downstream tooling tracking.
- **[LOW] §6 row "Captured: _Plan 5_" contradicts "_deferred to Plan 6_"** (claude only) — internal cell contradiction; one-word edit.
- **[LOW] L542 line-number anchor stale after Plan 4 R4/R5/R6 polish** (opencode only) — executor lookup risk; switch to content anchor.
- **Convergence verdict** — all three reviewers agree one short polish round closes this; no structural rework. Gemini most permissive ("trivial one-line fix"), claude conditions on outcome 1/2, opencode on the meta.json path fix.
