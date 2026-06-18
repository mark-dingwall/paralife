---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-06-04T02:00:36Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-05-PLAN.md"]
models:
  claude: "claude-opus-4-7"
  codex: "gpt-5"
  gemini: "gemini-3.1-pro-preview"
usage:
  claude: { input: 10, output: 16, cached: 54964, tool_calls: 0, elapsed_s: 120.6 }
  gemini: { input: 1762395, output: 3997, cached: 726498, tool_calls: 23, elapsed_s: 464.9 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 3.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 5, elapsed_s: 156.5 }
synthesizer: claude
synthesized_at: 2026-06-04T02:00:36Z
---

# Cross-AI Review

## Claude Review

Plan read. R4 findings:

```
[HIGH] L319 (Task 5.1 step 4 full-suite run instruction): R3 H1 disabled-test count fix incomplete — narrative still says "excluding the four `@Disabled` P22 tests per D-12 — those stay disabled". Contradicts the same Task 5.1's final invariant check at L335-352 which now enforces 3 tests (Metab/Deflate/PopDyn). Executor reading top-to-bottom sees "4 disabled" and may add a 4th `@Disabled` marker thinking it was missed, OR distrust the per-file grep below as wrong-by-one. Fix: s/four `@Disabled`/three `@Disabled`/. (citation: 20-05-PLAN.md Task 5.1 action step 4, in conflict with the grep-block at lines ~335-352)
```

```
[HIGH] L758-759 (<verification> block): Same R3 H1 orphan — "`./gradlew test` (full suite) exits 0 (excluding 4 P22 `@Disabled` tests per D-12 — DO NOT re-enable...)". Verification block is the contract surface a reviewer/checker greps to confirm the test-count assertion. Stays "4" while truths block + grep enforce 3. Fix: s/4 P22 `@Disabled` tests/3 P22 `@Disabled` tests/ + add the same R3 HIGH correction citation already in the truths block. (citation: 20-05-PLAN.md <verification> block, in conflict with must_haves truths L48-49)
```

```
[MEDIUM] Task 5.0 verification-before-reply (TRIAGE label contract enforced too late): R3 H2 added the "FIRST non-empty line MUST exactly match resume-signal grammar" contract, but only Task 5.2 step 9 OPTS guard fails on violation. Executor with prose-first TRIAGE.md passes Task 5.0 (resume-signal reply is correct), runs Task 5.1 (potentially modifying code under outcome 2 — JettyRuntimeConfig / AdmissionConfig / JettyDeflateCustomizer javadoc + test edits per files_modified L13-19), then fails at Task 5.2 step 9 FATAL after ~3-4 min of capture work. Recoverable but wastes work. Fix: add a hard guard at end of Task 5.0 verification-before-reply: `head -1 20-05-TRIAGE.md | grep -qE '^(triaged|runtime-knob-tightened|null-result|pinning-dominates-with-backlog-handoff):' || { echo "FATAL: TRIAGE.md first line does not match outcome-label contract"; exit 1; }`. Fail fast before Task 5.1 modifies code. (citation: 20-05-PLAN.md Task 5.0 "Verification before reply" bullet citing R3 HIGH contract; Task 5.2 step 9 OPTS guard added L621-626)
```

```
[LOW] Task 5.2 boot-stage FATAL cleanup (~L526): `rm -f "$JFR_OUT" "$METRICS_OUT" 2>/dev/null` runs before step 5 defines `METRICS_OUT`. Under default bash (no `set -u`), `$METRICS_OUT` expands to empty string; `rm -f ""` is benign (silently ignores empty arg). But brittle — if executor adds defensive `set -u` (sane shell hygiene) the FATAL cleanup itself crashes with `METRICS_OUT: unbound variable` before exiting, masking the readiness failure. Fix: either move `METRICS_OUT=...` definition to the top of step 2 (before boot), OR wrap as `rm -f "$JFR_OUT" "${METRICS_OUT:-}" 2>/dev/null`. (citation: 20-05-PLAN.md Task 5.2 step 3 readiness FATAL cleanup vs step 5 METRICS_OUT initialisation)
```

```
[LOW] Task 5.2 step 9 OPTS templating block is prose-comment (L598-606), enforcement at L621-626 fires only if executor pastes the templating bash. If executor skips templating entirely (treats the comment as informational), OPTS is unset → `[ -z "${OPTS:-}" ]` is true → FATAL fires. So sentinel + OPTS guard together catch (a) placeholder unchanged AND (b) templating skipped. Coverage adequate, but two separate guards for two error modes is confusing. Cosmetic. Fix optional: combine into one guard that checks both empty-OPTS AND sentinel-present. (citation: 20-05-PLAN.md Task 5.2 step 9 templating comment vs OPTS guard vs sentinel guard)
```

```
[NIT] Threat model T-20-V5 row "PerceptionCodec MAX_S_ENTRIES / MAX_V_ENTRIES / varbase64 length bounds" — varbase64 bounds live in `Base64Codec.java` (separate file from MAX_S/MAX_V in `PerceptionCodec.java`). The Task 5.1 acceptance grep checks PerceptionCodec only. If a Plan 5 opt touches Base64Codec (allowed per truths + threat-model scope), no value-anchored regression check fires. Pass-3 filed-as-followup already mentions this. Tolerable as-is per the existing disposition; documenting here so a R5 re-flag is resolved-by-disposition. (citation: 20-05-PLAN.md threat-model T-20-V5 + Task 5.1 acceptance criteria 4th bullet)
```

**Convergence judgement:** 2× HIGH findings — orphan "four/4" disabled-test references in Task 5.1 step 4 + <verification> block. Both direct regressions of the R3 H1 fix (which the user notes was intended to sweep "4 disabled tests" narrative). **Not converged.** One small polish closes both HIGHs (two s/4/3/ edits) + the recommended MEDIUM (one `head -1 | grep` line at Task 5.0). LOWs/NIT are documentation-grade.

Other surfaces clean post-R3:
- outcome-2 conditional file paths (Surface 4): all correct — `runtime/JettyRuntimeConfig`, `admission/AdmissionConfig`, `websocket/JettyDeflateCustomizer` match repo structure
- detach.timeout by-outcome rule (Surface 5): outcome 2 borderline case handled by "investigate; if attributable revert per Concern #14; if environmental document and proceed"
- Multi-site `rm -f` idempotency (Surface 6): `-f` silent-on-missing; partial-JFR-exists at boot-FATAL is benign
- outcomes 3/4 ≥10 line floor (Surface 7): adequate for label + JFR cite + acceptance summary

---

## Gemini Review

[HIGH] L594: The OPTS-empty guard destructively deletes the successfully captured JFR and metrics sidecar if the executor misses the templating step. Remove the `rm -f "$JFR_OUT" "$METRICS_OUT" "$META_OUT"` command from the OPTS-empty FATAL block so the executor can safely fix the meta.json file without re-running the capture. (citation: 20-05-PLAN.md Task 5.2 step 9)

[MEDIUM] L274: Orphaned references to "four @Disabled P22 tests" and "4 P22 @Disabled tests" remain in the execution steps and output block despite dropping HundredBotIntegrationTest. Change "four" or "4" to "3" in Task 5.1 step 4, the outcome 2/3 branches, and the Task 5.2 Output block. (citation: 20-05-PLAN.md Task 5.1 and Output block)

Convergence judgement: There are NEW issues at HIGH severity. The destructive artifact cleanup on an OPTS-templating miss risks destroying valid captures. Convergence not reached.

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 3.0s

---

## Opencode Review

All outcome-2 file paths exist. Now I'll systematically examine each scrutiny surface.

**Surface 1 (OPTS guard order):** Lines 577-582 guard fires before the sentinel check at 585. If executor skips the prose-comment template, `${OPTS:-}` → empty, FATAL fires. If they paste the grep but it returns empty (bad TRIAGE.md), FATAL fires. If they paste and it's non-empty but forget the `jq` templating, sentinel at 585 catches it. Guard ordering is correct — no issue.

**Surface 2 (TRIAGE contract enforcement):** Contract is prose at Task 5.0 L233. Enforcement is at Task 5.2 L577. Same pattern as Pass-3 Concern #29's SUMMARY contract. Adequate — no earlier check exists (TRIAGE.md doesn't exist yet when the contract is stated), but the hard guard in Task 5.2 catches violations. Acceptable.

**Surface 3 (orphaned "4" narrative):** Found 5 stale "4"/"four" references:

**Surface 5 (detach.timeout outcome 2):** Lines 487-490 are explicit: "treat non-zero tuned as a regression flag — investigate the JFR... if confirmed attributable to the change, revert; if environmental, document and proceed." Clear and correct — outcome 2 touches config defaults (not logic), but the conservative flag is defensible since investigation is the ask, not blind revert.

**Surface 6 (rm -f idempotency):** All 3 FATAL exits (L418, L458, L580) are idempotent. L418 is boot-stage where JFR_OUT may not exist yet (`delay=15s`) — `rm -f` on non-existent file is silent success. No issue.

**Surface 7 (≥10 lines floor):** 1 line signal + ~3 lines JFR citation + ~2 lines evidence + ~2 lines acceptance + ~1 line footer = ~9 lines. Tight but sufficient for outcomes 3/4 which are fundamentally "nothing to code; here's the evidence." Acceptable.

---

Now the findings:

```
[MEDIUM] L281/303/306/658/691: Five sites still reference "four"/"4"/"TD-22-A..D" for @Disabled P22 tests. R3 removed HundredBotIntegrationTest (correctly — carries zero @Disabled annotations), reducing the disabled count from 4 to 3 (Metab + Deflate + PopDyn). The enforcing grep code at L325-330 correctly checks 3 files, but the narrative text is stale. Fix: s/four/three/ (L281), s/4 P22/3 P22/ (L303, L306, L658), s/still 4/still 3/ (L691), s/TD-22-A..D/TD-22-A..C/ (L281). (citation: 20-05-PLAN.md:35 (correctly says "3") vs L281 ("four" + "TD-22-A..D"), L303 ("4"), L306 ("4"), L658 ("4"), L691 ("4"))
```

That's the only finding. Verifying there's nothing else:

**Convergence judgement: NO new HIGH+ findings.** One MEDIUM — 5 stale narrative "4" references that R3 missed in its sweep. The enforcing code at L325-330 is correct; the grep contracts at L328-330, L351, and acceptance_criteria L347/350 all correctly check 3 files. The plan is executable as-is with a minor inaccuracy in prose. One more 5-site s/four/three/ polish and this converges.

---

## Consensus Summary

### Agreed Strengths
- Outcome-2 conditional file paths verified correct (JettyRuntimeConfig, AdmissionConfig, JettyDeflateCustomizer) — claude + opencode
- OPTS-empty + sentinel guard pairing catches both templating-skip and bad-TRIAGE error modes — claude + opencode
- `rm -f` idempotency across all FATAL exits is safe (silent on missing files) — claude + opencode
- detach.timeout outcome-2 "investigate; revert-if-attributable, document-if-environmental" rule is clear — claude + opencode
- Outcomes 3/4 ≥10-line floor is tight but adequate — claude + opencode

### Agreed Concerns
- **[HIGH/MEDIUM] Stale "four"/"4" @Disabled P22 narrative — orphaned by R3 H1 sweep.** All three reviewers flag. Sites: Task 5.1 step 4 ("four"), `<verification>` block ("4 P22"), outcome 2/3 branches, Task 5.2 Output block, plus TD-22-A..D ref. Enforcing greps at L325-330/L351 correctly check 3 files (Metab/Deflate/PopDyn), but narrative contradicts. Fix: sweep s/four/three/, s/4 P22/3 P22/, s/TD-22-A..D/TD-22-A..C/. Claude rates HIGH (executor confusion risk between top-down narrative and bottom enforcement); gemini+opencode rate MEDIUM. (claude L319 + L758-759; gemini L274; opencode L281/303/306/658/691)

### Divergent Views
- **[HIGH per gemini] Destructive `rm -f` on OPTS-empty FATAL destroys valid JFR/metrics captures.** Gemini flags as new HIGH: if executor captures successfully but misses templating, the OPTS guard deletes `$JFR_OUT $METRICS_OUT $META_OUT` — executor must re-run the full capture instead of just fixing meta.json. Claude + opencode examined the same surface and judged the guard pairing correct without flagging the destructiveness. Worth investigating — gemini's point is that the failure mode (forgot to template) is recoverable without re-capture, so deletion is over-eager. Recommend removing the `rm -f` from the OPTS-empty FATAL block specifically (keep it on boot/readiness FATALs where partial JFR is genuinely invalid).
- **[MEDIUM per claude only] Task 5.0 TRIAGE-label contract enforced too late.** Claude proposes adding `head -1 20-05-TRIAGE.md | grep -qE '^(triaged|...):'` guard at end of Task 5.0 to fail-fast before Task 5.1 modifies code. Opencode examined the same surface and judged "adequate — no earlier check possible since TRIAGE.md doesn't exist yet." Disagreement on whether late-enforcement (after ~3-4 min of capture work) is acceptable. Claude's guard is cheap to add and prevents wasted code edits under outcome 2.
- **[LOW per claude] `METRICS_OUT` referenced in step 3 cleanup before step 5 defines it** — benign under default bash, brittle under `set -u`. Other reviewers did not flag. Cosmetic; one-line `${METRICS_OUT:-}` wrap closes it.

**Convergence:** Not reached. Unanimous agreement on the stale-"4" narrative sweep (severity split HIGH/MEDIUM). Gemini's destructive-cleanup HIGH is a divergent finding worth resolving before R5. One polish pass closes both: (a) sweep s/four|4/three|3/ at all 5-7 sites + TD-22-A..D → TD-22-A..C; (b) drop `rm -f` from OPTS-empty FATAL only. Optional: claude's TRIAGE first-line guard at Task 5.0.
