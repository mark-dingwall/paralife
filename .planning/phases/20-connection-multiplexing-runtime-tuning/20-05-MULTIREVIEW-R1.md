---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-06-04T01:19:15Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-05-PLAN.md"]
models:
  claude: "claude-opus-4-7"
  codex: "gpt-5"
  gemini: "gemini-3.1-pro-preview"
usage:
  claude: { input: 10, output: 16, cached: 0, tool_calls: 0, elapsed_s: 175.6 }
  gemini: { input: 163828, output: 553, cached: 0, tool_calls: 0, elapsed_s: 203.8 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 4.4 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 14, elapsed_s: 260.6 }
synthesizer: claude
synthesized_at: 2026-06-04T01:19:15Z
---

# Cross-AI Review

## Claude Review

# Plan 20-05 multi-review findings (post-drift-fix)

## Findings

**[HIGH] L530-543 (Task 5.2 step 7): jq query assumes Plan 1c active baseline sidecar has top-level `.samples[].work_time_ms.measurements[]` shape — schema match unverified.** Plan 5 writes its tuned sidecar with `{captured_at_sha, scenario, baseline_pair, samples: [...]}` shape, but 20-01c-SUMMARY only says "9 meters × 6 samples" / "18-sample headline-gauge JSON snapshots" without quoting the literal JSON shape. If Plan 1c sidecar uses a different top-level key or a flat shape, the baseline-side jq returns `null` and §4.2 delta is uncomputable. **Fix:** before running capture, `head -20` one Plan 1c active sidecar and either confirm shape match OR rewrite the baseline-side jq to match Plan 1c's actual structure; add the verified jq path as a comment in Task 5.2.

**[MEDIUM] L580 (Task 5.2 step 9, meta.json heredoc): `"opts_applied_summary": "<list from 20-05-TRIAGE.md>"` is a literal placeholder, not a shell-templated expansion.** Heredoc will write the literal angle-bracket text into the committed JSON. **Fix:** either template it (e.g., `OPTS=$(jq -r '.opts | tostring' 20-05-TRIAGE.md)` if TRIAGE is JSON, or a `sed`/`grep` extraction) OR mark it as a manual-fill placeholder with explicit `EDIT BEFORE COMMIT` comment so executor doesn't drop the placeholder text into the repo.

**[MEDIUM] §6 Profile Index update instructions miss flamegraph rows.** Current 20-RUNTIME.md §6 has placeholder rows for `{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html` (3 flamegraph files) — Plan 5 capture script does NOT capture flamegraphs, only JFR + metric sidecar. Task 5.2 step 10 only instructs updating the JFR + metric sidecar rows. **Fix:** either (a) drop the 3 flamegraph rows from §6 in Task 5.2, citing MVP scope; or (b) explicitly defer them to Plan 6 with a "deferred to Plan 6 — see flamegraph capture forward-note" cell. Currently they'd land in-repo as untouched placeholder rows pointing at files that don't exist.

**[MEDIUM] L515-525 (actuator sample loop) has no curl-failure guard.** If actuator returns 5xx, hangs, or returns empty body mid-sample, the heredoc `echo "{\"sample_utc\":...,\"work_time_ms\":$WORK_TIME_MS,...}"` produces invalid JSON like `"work_time_ms":,"detach_timeout":...`. The final `jq . "$METRICS_OUT"` validation catches it but only after the run completes (capture wasted). **Fix:** add `curl -fS --max-time 5` and a fallback (`WORK_TIME_MS=${WORK_TIME_MS:-null}`) so a single bad scrape produces a recoverable sample, not a corrupt file.

**[LOW] L640-641 (Task 5.1 acceptance, T-20-V5 grep): `grep -v "^//"` doesn't strip javadoc.** PerceptionCodec.java uses ` *` javadoc style (line 11 onward), not `//`. The grep won't filter the `@link #MAX_S_ENTRIES` and `@link #MAX_V_ENTRIES` javadoc references at lines 12-13. Doesn't break the check (constants still surface with their literal values at lines 29/35) but the filter is misleading. **Fix:** `git diff c22e487..HEAD -- src/main/java/com/paralife/codec/PerceptionCodec.java | grep -E "MAX_S_ENTRIES|MAX_V_ENTRIES"` would assert literal stability stronger than presence-grep.

**[LOW] L535 jq `{mean: (add/length), max: max}` fails on empty array.** If `.samples` is empty or `.measurements[]` filter returns nothing (e.g., gauge had no recorded value at scrape), `add/length` = `null/0` → jq error. **Fix:** `if length == 0 then null else {mean: (add/length), max: max} end` — graceful null beats fatal error in a verification step.

**[NIT] L515 boot-readiness check is single-shot, not retry.** `sleep 10 && curl -s -o /dev/null -w "%{http_code}\n" .../paralife.tick.health.work-time-ms` then "Expect 200; if 404, abort and report". A single 404 at t=10 could be transient Spring boot-tail vs persistent meter-not-registered. Either is recoverable by a 2-3 retry loop. Not load-bearing.

**[NIT] L516 — comment "Expect 200; if 404, abort" is prose, not enforced.** No actual exit-on-fail. Executor reads the HTTP code from stdout. If actuator returns 404 silently, harness still launches at L527. Strict check would be `[ "$(curl ...)" = "200" ] || { kill -9 $SERVER_PID; exit 1; }`.

## Convergence judgement

Executable as-is for outcome 3 (null-result) and outcome 4 (pinning-dominates) paths — both produce equivalence-proof tuned-state JFR + metric sidecar without behavioural delta, so the jq-schema concern is non-blocking (delta computation returns null/null, which IS the equivalence proof).

For outcomes 1/2 (codec opts ship / runtime-knob tightened) the HIGH jq-schema concern needs ~5min pre-execution verification (one `head -20` on a Plan 1c active sidecar) before the capture script will reliably surface the delta. Once that's verified-and-pinned, the plan is ready.

No structural rework needed. One round of polish on Task 5.2 (jq shape + meta.json templating + §6 flamegraph row disposition + curl guards) finishes this.

---

## Gemini Review

[BLOCKER] L248: Task 5.2 capture script completely omits the async-profiler capture commands (cpu, alloc, lock flamegraphs) and does not instruct the executor to update their filenames in `20-RUNTIME.md` §6. Add the async-profiler capture commands to run alongside the harness, and update the §6 Profile Index instruction at L310 to include updating the flamegraph placeholders to the `active-50xfood` shape. (citation: `20-RUNTIME.md` §6 Profile Index, line 381)

[HIGH] L236: The actuator metrics readiness check only prints the HTTP code but does not abort the script on failure. Capture the curl output and explicitly exit if it is not 200 to prevent a silent 180s capture failure. (citation: `20-05-PLAN.md` Task 5.2)

[HIGH] L297: The heredoc for `meta.json` contains the literal string `"<list from 20-05-TRIAGE.md>"`. Since this is inside a bash block intended to be run, it will write a literal placeholder into the artifact instead of the actual data. Use a shell command or explicitly prompt the executor to replace it before running. (citation: `20-05-PLAN.md` Task 5.2)

[MEDIUM] L350: The grep for disabled tests looks for `' @Disabled'` (with a leading space), which will fail if the annotation is placed at the beginning of a line. Change it to `grep -q '@Disabled'`. (citation: `20-05-PLAN.md` Task 5.1)

[NIT] L43: The objective section lists the four outcomes "in priority order" as 1, 2, 3, 4, but L53 correctly states that outcome 4 supersedes outcome 2 (actual priority is 1, 4, 2, 3, matching the correctly ordered decision tree in Task 5.0). Update the list order to match actual precedence. (citation: `20-05-PLAN.md` Objective)

### Convergence judgement
The plan is highly detailed and structurally sound, properly enforcing the D-21 four-outcome precedence in the decision tree and thoroughly documenting the metric capture. However, because it completely misses generating the flamegraph artifacts required by `20-RUNTIME.md` and contains a few brittle bash script issues, it requires one more round of structural work before execution.

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 4.4s

---

## Opencode Review

Here's the post-amend re-review of Plan 20-05.

---

## Findings

**[MEDIUM] L443**: meta.json naming convention produces `jfr-1000bots-active-50xfood-tuned-<SHA>.jfr.meta.json` instead of the project convention `jfr-1000bots-active-50xfood-tuned-<SHA>.meta.json` (`.jfr` stripped before `.meta.json` appended). The script writes `"$JFR_OUT.meta.json"` where `JFR_OUT` contains the full path including `.jfr`. Fix: `${JFR_OUT%.jfr}.meta.json`. (citation: 20-05-PLAN.md:443 vs existing `profiles/jfr-1000bots-active-50xfood-103a615.meta.json` convention)

**[LOW] L511**: T-20-V7 threat model entry references `profiles/jfr-1000bots-tuned-*.jfr + metrics-1000bots-tuned-*.json` — the pre-drift-fix filename shape. The committed tuned-state files will use the active-scenario shape (`jfr-1000bots-active-50xfood-tuned-*.jfr`). The old glob won't match the actual files. Update both to `jfr-1000bots-active-50xfood-tuned-*.jfr` and `metrics-1000bots-active-50xfood-tuned-*.json`. (citation: 20-05-PLAN.md:511 vs Task 5.2 step 3/5 active-scenario filenames)

---

## Convergence judgement

**Executable as-is.** The two findings above are `MEDIUM`/`LOW` and non-blocking. Plan 5 correctly implements the drift-fix: all 22 working references point to `103a615` (active) or `62c1b44` (churn floor), the 3 `c22e487` mentions are intentional rhetoric provenance, D-21 four-outcome precedence is correctly structured (codec → pinning-dominates → runtime-knob → null-result) with Pass-3 Concern #26 enforcing the pinning-supersedes-knob rule, Task 5.2 capture script is Bash-correct (SIGTERM/SIGKILL lifecycle, actuator metric polling, JFR flag syntax), filename scheme is consistent across all task instructions, and cross-doc markers from Plan 4 are properly scoped (§4.2/§4.4 populated by Plan 5; GC/parallelism markers remain for Plan 6).

---

## Consensus Summary

### Agreed Strengths
- Plan structurally sound post-drift-fix; D-21 four-outcome precedence correctly encoded in decision tree.
- Executable for null-result / pinning-dominates paths without rework.
- Filename scheme + active-scenario references largely consistent; SIGTERM/SIGKILL lifecycle and JFR flag syntax in capture script are correct.

### Agreed Concerns
- **[HIGH] Task 5.2 meta.json heredoc `"opts_applied_summary": "<list from 20-05-TRIAGE.md>"`** — literal placeholder lands in committed JSON. Template via shell expansion or mark `EDIT BEFORE COMMIT`. (claude, gemini)
- **[HIGH] §6 Profile Index flamegraph rows orphaned** — capture script omits async-profiler (cpu/alloc/lock) runs, but §6 has placeholders for `{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html`. Either add async-profiler capture commands + update §6 to active-scenario shape, or explicitly defer to Plan 6 with forward-note. (claude MEDIUM, gemini BLOCKER)
- **[HIGH/MEDIUM] Actuator readiness check is advisory only** — prints HTTP code but does not abort on non-200; risks silent 180s wasted capture. Add `[ "$code" = "200" ] || exit 1` guard. (claude NIT×2, gemini HIGH)

### Divergent Views
- **Flamegraph omission severity**: gemini calls it a BLOCKER (must capture now); claude treats it as MEDIUM with deferral-to-Plan-6 acceptable; opencode doesn't flag it. Worth a quick scope decision before execute.
- **jq schema assumption (claude HIGH, L530-543)**: claude wants pre-execution `head -20` verification that Plan 1c sidecar shape matches the `.samples[].work_time_ms.measurements[]` query; gemini/opencode silent. Cheap to verify, worth doing for outcomes 1/2.
- **meta.json filename convention (opencode MEDIUM, L443)**: only opencode caught `$JFR_OUT.meta.json` producing `.jfr.meta.json` vs project convention `.meta.json` (strip `.jfr` first). Independent finding, likely real.
- **T-20-V7 threat-model filename glob (opencode LOW, L511)**: only opencode flagged the pre-drift-fix glob shape; independent and likely real.
- **Disabled-test grep pattern (gemini MEDIUM, L350)**: only gemini flagged `' @Disabled'` leading-space brittleness; plausible but low blast radius.
- **Outcome priority list ordering (gemini NIT, L43)**: only gemini noted the L43 enumeration mismatches the actual 1/4/2/3 precedence stated at L53. Cosmetic.
- **jq `add/length` on empty array (claude LOW)**: only claude flagged; defensive polish, not load-bearing.
