---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T12:44:21Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md"]
models:
  codex: "gpt-5.5"
usage:
  claude: { input: 676451, output: 94, cached: 3076467, tool_calls: 9, elapsed_s: 536.8 }
  gemini: { input: 2404306, output: 1372, cached: 1125415, tool_calls: 14, elapsed_s: 589.8 }
  codex: { input: 1655094, output: 10049, cached: 1527808, tool_calls: 42, elapsed_s: 223.8 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 41, elapsed_s: 453.2 }
synthesizer: claude
synthesized_at: 2026-06-04T12:44:21Z
---

# Cross-AI Review

## Claude Review

Verification complete. Empirically confirmed: the R1-fixed gauge grep returns exactly 2 on populated rows (satisfiable), the outcome grep maps to the 250-line floor (current doc 400 lines — ample), and the c22e487 set is 12 files of which step 5 enumerates only 9. Writing the review.

---

# Pre-execution review: `20-06-PLAN.md` (Phase 20 final close-out)

Reviewed against live repo at `main` HEAD `cf2e4e3`. Every finding below was checked against actual files/greps. Overall: the de-stale (`3477d39`) + R1 multi-review round did its job — the plan is executable, line refs are accurate, and the previously-flagged unsatisfiable greps (R1 H1) now pass. One real incompleteness in the §6 fix, plus residual-staleness sweepings the plan's own audit can't catch.

---

### [MEDIUM] Step 5 c22e487 row-group omits the 3 `metrics-*-baseline-c22e487.json` sidecars — re-creates a partial R1-H2 defect

**Evidence:** `profiles/` holds **12** committed `c22e487` files:
```
jfr-{100,500,1000}bots-baseline-c22e487.jfr        (3)
jfr-{100,500,1000}bots-baseline-c22e487.meta.json  (3)
{cpu,alloc,lock}-1000bots-baseline-c22e487.html    (3)
metrics-{100,500,1000}bots-baseline-c22e487.json   (3)   ← not enumerated
```
Task 6.1 step 5's c22e487 bullet enumerates only `jfr-…c22e487.jfr` + `.meta.json` + `{cpu,alloc,lock}-1000bots-baseline-c22e487.html` = **9 of 12**. The 3 `metrics-…-baseline-c22e487.json` actuator sidecars are omitted.

**Why it matters:** R1-H2 was raised precisely because "12 committed files unlisted"; the must-have artifact spec requires the Profile Index to list "every committed JFR + flamegraph + **actuator metric sidecar**." The 62c1b44 and 103a615 groups already list their metric sidecars (`20-RUNTIME.md:387,390`), so the c22e487 group is the lone exception. The acceptance grep checks only `grep -q "jfr-1000bots-baseline-c22e487.jfr"` — it passes with the metric sidecars still unlisted, so the gate won't catch the gap. The fix as written leaves a smaller version of the exact defect it was closing.

**Suggested fix:** Add `metrics-{100,500,1000}bots-baseline-c22e487.json` to the step-5 c22e487 bullet enumeration (compressed-brace form, same Notes annotation), mirroring the 62c1b44 group at `20-RUNTIME.md:387`.

---

### [LOW] §6 active-50xfood row Notes cell ("Plan 5/6 tune against this set") is a stale forward-ref no step rewords and the audit regex misses

**Evidence:** `20-RUNTIME.md:388` Notes column: `**transport-overhead evidence — Plan 5/6 tune against this set**`. The step-6 audit regex `pending|plan [0-9/]+ (populates|produces|finalises|wires|will)` does **not** match the verb "tune", and no edit step (1–7) targets line 388 — step 5 only touches placeholder cells / the c22e487 group / the tuned-flamegraph row / the README row.

**Why it matters:** Plan 5 already tuned against this set (done, null-result); Plan 6 writes up, it does not tune. Post-completion the cell reads as if tuning is still pending, in the phase's canonical authoritative doc. It is the one forward-reference in the file that survives both the edit steps and the audit net.

**Suggested fix:** Reword to past tense in step 5 (e.g. "transport-overhead evidence — the set Plan 5 tuned against (null-result)"), or extend the step-6 audit regex verb list with `tune|tunes|reconciles|replaces` so the audit catches stragglers.

---

### [LOW] CLAUDE.md insertion template cites superseded Plan 1b as a live capture source

**Evidence:** Task 6.2 insertion content: *"`application.yml:15` exposes the `metrics` actuator endpoint that **Plan 1b + Plan 5** capture from."* The canonical baseline sidecars are `metrics-*-62c1b44.json`, captured by **Plan 1c** (the re-anchor); Plan 1b produced only the superseded `c22e487` sidecars. (`application.yml:15` itself is correct — `include: health,info,metrics` is line 15.)

**Why it matters:** CLAUDE.md is permanent operator-facing project doc; the same de-stale is replacing "1b-as-active" elsewhere (Task 6.1 step 7, Task 6.5 step 3). Citing Plan 1b here as a current capture source plants the superseded-plan reference back into a doc that outlives the phase. Minor — 1b did originate the actuator-capture ritual — but the canonical capturers are 1c + 5.

**Suggested fix:** "…that Plan 1c + Plan 5 capture from" (or drop the plan attribution: "…captured into sidecars at baseline and tuned time").

---

### [LOW] Acceptance-criteria greps use bare `20-RUNTIME.md` / `20-VALIDATION.md` (phase-dir-relative); fail from repo root

**Evidence:** Task 6.1/6.5 `<acceptance_criteria>` greps target bare `20-RUNTIME.md` / `20-VALIDATION.md` (e.g. `grep -q "## §4.3 Per-tier narrative" 20-RUNTIME.md`), whereas the `<verify><automated>` blocks use full `.planning/phases/20-…/20-RUNTIME.md` paths. Run from repo root, the bare-path acceptance greps return "No such file or directory." (`CLAUDE.md`/`README.md` bare greps are fine — those files sit at repo root.)

**Why it matters:** The review brief treats acceptance greps as executable contracts. Several gate-relevant checks (per-tier 62c1b44 loop, ≥9 count, the c22e487 row grep, the gauge grep, baseline-only grep) live **only** in `<acceptance_criteria>` with bare paths — no full-path twin in the verify block. Recoverable (cd to phase dir or prepend path), and the machine-gated verify blocks are path-correct, so the phase still passes — but a literal copy-paste from repo root produces spurious failures.

**Suggested fix:** Either prepend the phase-dir path in the acceptance greps, or add a one-line "run acceptance greps from the phase directory" note. Low priority given the verify blocks carry the correct paths.

---

### [NIT] ≥9 baseline-JFR citation count is exactly met by the template — zero margin

**Evidence:** `grep -cE "jfr-(100|500|1000)bots-baseline-62c1b44\.jfr"` currently returns **6**; the §4.3 narrative template adds exactly **3** (one baseline-JFR filename per tier heading), landing at exactly 9 for the `awk '$1 >= 9'` gate. If an executor writes the §4.3.3 heading citing only the active/tuned JFRs and phrases the churn-baseline contrast without the literal `jfr-1000bots-baseline-62c1b44.jfr` filename, the count is 8 and the gate fails.

**Why it matters:** Intentional per R1-L2 ("6 index + 3 narrative = exactly 9"), and the provided §4.3 template satisfies it — but the zero-margin makes the gate brittle to benign heading-wording deviation. Narrative bodies will likely push it past 9 anyway.

**Suggested fix:** None required; optionally note in step 2 that each §4.3.N heading must carry its tier's `jfr-Nbots-baseline-62c1b44.jfr` filename verbatim.

---

### [NIT] Task 6.5 step 3 "the two c22e487 Manual-Only Verification rows (~L81-84)" — only one row literally names the SHA

**Evidence:** In `20-VALIDATION.md` the Manual-Only Verification table, only L81 ("Profile artifacts captured against c22e487") contains the literal `c22e487`; the second row in the cited L81–84 range (L83, "Tuned JFR captured against HEAD … deltas vs baseline") references `jfr-1000bots-tuned-<sha>.jfr`, not the SHA. The "two c22e487 rows" phrasing is slightly imprecise.

**Why it matters:** Cosmetic; the executor annotating the two baseline-related manual rows resolves it trivially. Line range is drift-caveated. Flagging only for accuracy.

---

## Summary

| Severity | Title | One-line fix |
|----------|-------|--------------|
| MEDIUM | §6 c22e487 group omits 3 `metrics-*-c22e487.json` sidecars (9/12) | Add the metric sidecars to step-5's c22e487 enumeration (mirror the 62c1b44 group) |
| LOW | §6 L388 "Plan 5/6 tune against this set" ships stale (no step + audit misses verb) | Reword past-tense in step 5, or add `tune\|reconciles\|replaces` to the audit regex |
| LOW | CLAUDE.md template cites superseded Plan 1b as live capture source | "Plan 1c + Plan 5 capture from" |
| LOW | Bare-path acceptance greps (`20-RUNTIME.md`/`20-VALIDATION.md`) fail from repo root | Prepend phase-dir path or note cwd; verify blocks already correct |
| NIT | ≥9 62c1b44-JFR count exactly met — zero margin | Note each §4.3.N heading must carry its tier's baseline JFR filename verbatim |
| NIT | "two c22e487 manual rows" — only one names the SHA | Wording only |

**Cross-checked clean (no finding):** AdmissionMetrics `:70`/`:79` (accurate); 20-CONTEXT c22e487 sites L208/225/327/463 (all exact); `application.yml:15` (correct); metrics-JSON keys `paralife_tick_health_work-time-ms`/`paralife_outbound_detach_timeout` (present — fills satisfiable); all 14 step-6 audit hits map to edit steps 1/2/3/7; R1-H1 gauge grep returns 2 on populated rows (empirically verified); outcome grep matches `Plan 5 outcome: null-result` → 250 floor (doc is 400); 20-01c is prose-structured (no `<task>` blocks, maps to A/B/C); task-row total ≈22 (≥20 gate met); inline-comment insertion blocks match `WorldWebSocketHandler.java:316-321` and `OutboundSender.java:133-138` exactly; §4.4/§4.2-1000-tier correctly described as "verify, don't re-derive."

**VERDICT: 0 BLOCKER, 0 HIGH, 1 MEDIUM, 3 LOW, 2 NIT**

Caveman note: plan execution-ready. One MED worth fixing pre-exec (cheap one-line edit to step 5) — closes must-have gap acceptance grep can't see. Rest sweepable or recoverable.

---

## Gemini Review

### [HIGH] Task 6.1 Step 7 misdirects executor to the wrong section for `20-RUNTIME.md` parenthetical removal
**Evidence:** `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md` at Task 6.1 step 7 instructs: `Strip both now-resolved deferred-cleanup parentheticals from 20-RUNTIME.md §6 (~L370 "…Plan 6 reconciles…" and ~L377 "…known doc-drift cleanup deferred to Plan 6 + the 20-06-PLAN VALIDATION flip…").`
**Why it matters:** The first parenthetical (`Plan 6 reconciles`) is actually located in `## §5 Forward Notes` (~L370), not in `§6 Profile Index` (which begins at ~L375). By explicitly restricting the target scope to `§6`, an execution agent (or rigid search script) will fail to find the first match, resulting in a task failure or unintended modifications to the wrong section.
**Suggested fix:** Update the instruction in Task 6.1 step 7 to accurately reflect the sections: `Strip the now-resolved deferred-cleanup parenthetical from 20-RUNTIME.md §5 (~L370 "…Plan 6 reconciles…") and from §6 (~L377 "…known doc-drift cleanup deferred to Plan 6 + the 20-06-PLAN VALIDATION flip…").`

### [MEDIUM] Task 6.5 Action 3 claims there are two `c22e487` Manual-Only Verification rows instead of one
**Evidence:** `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md` at Task 6.5 Action 3 instructs: `Same one-line annotation on the two c22e487 Manual-Only Verification rows (~L81-84)...`
**Why it matters:** In `20-VALIDATION.md`, there is only **one** row between L81 and L84 that contains the `c22e487` string (at L81: `| Profile artifacts captured against c22e487 |...`). An executor looking for a second `c22e487` row to edit in that range might fail or get confused (though an intelligent agent could likely recover by just editing the single match). 
**Suggested fix:** Change the instruction text from `the two c22e487 Manual-Only Verification rows (~L81-84)` to `the single c22e487 Manual-Only Verification row (~L81)`.

---

### Summary

| Severity | Title | One-line fix |
|----------|-------|--------------|
| HIGH | Task 6.1 Step 7 misdirects executor to the wrong section for `20-RUNTIME.md` parenthetical removal | Correct the instruction to state that the first parenthetical is in `§5 Forward Notes`, not `§6`. |
| MEDIUM | Task 6.5 Action 3 claims there are two `c22e487` Manual-Only Verification rows instead of one | Change "the two c22e487 Manual-Only Verification rows" to "the single c22e487 Manual-Only Verification row (~L81)". |

VERDICT: 0 BLOCKER, 1 HIGH, 1 MEDIUM, 0 LOW, 0 NIT

---

## Codex Review

### [HIGH] Bare acceptance paths fail from repo root

Evidence: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:169`, `:193-201`, `:466-473`

The plan’s automated verify blocks mostly use full paths, but acceptance commands use bare `20-RUNTIME.md` and `20-VALIDATION.md`. From repo root, neither file exists; the actual files are under `.planning/phases/20-connection-multiplexing-runtime-tuning/`. Since acceptance greps are executable contracts, truthful execution from repo root would fail.

Suggested fix: replace every executable bare-path acceptance/audit command with the full phase path, or explicitly `cd .planning/phases/20-connection-multiplexing-runtime-tuning` before those commands.

### [HIGH] c22e487 metric sidecars still omitted from §6 index instructions

Evidence: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:31`, `:163-166`; actual files include `profiles/metrics-{100,500,1000}bots-baseline-c22e487.json`.

The must-have says §6 lists every committed JFR, flamegraph, and actuator metric sidecar. Step 5 correctly notes 12 committed c22e487 files, but the requested history-only row group lists only JFRs, meta sidecars, and flamegraphs: 9 files. It omits the 3 c22e487 metric sidecars. The acceptance grep only checks `jfr-1000bots-baseline-c22e487.jfr`, so execution can pass while leaving sidecars unindexed.

Suggested fix: add `metrics-{100,500,1000}bots-baseline-c22e487.json` to the c22e487 history-only row group and add an acceptance grep for at least `metrics-1000bots-baseline-c22e487.json`.

### [MEDIUM] D-19 reconcile still permits stale c22e487 operator wording

Evidence: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md:208-212`; plan instruction at `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:171-173`

The current D-19 bullet says the profile baseline is anchored to `c22e487` and tells future agents to `git checkout c22e487`. The plan says to “annotate” this with a re-anchor parenthetical, which can leave the stale leading instruction intact and self-contradictory.

Suggested fix: require rewriting the D-19 bullet’s lead sentence and checkout example to name `62c1b44` as canonical churn baseline, with `c22e487` explicitly history-only.

### [MEDIUM] D-12 disabled-test sign-off is stale against repo reality

Evidence: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:24`, `:62`, `:96`; `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:407`, `:456`, `:499`; current test tree has 7 `@Disabled` annotations, and `src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java:49` is active.

Task 6.5 plans to tick “D-12 enforced: 4 `@Disabled` tests untouched,” but the current repo does not match that statement. `20-VALIDATION.md` also names `HundredBotIntegrationTest` as one of four disabled tests, while it is not disabled. Signing this as-is creates a false validation record.

Suggested fix: update the D-12 wording before sign-off: distinguish the original P22 deferrals from current non-P22 disabled tests, remove HundredBot from the disabled list, and require a fresh `rg -n "@Disabled" src/test/java` snapshot in the validation note.

| Severity | Title | One-line fix |
|---|---|---|
| HIGH | Bare acceptance paths fail from repo root | Use full phase paths or `cd` before executable greps. |
| HIGH | c22e487 metric sidecars omitted | Add c22e487 metric sidecar row/group and grep. |
| MEDIUM | D-19 reconcile leaves stale checkout wording | Rewrite D-19 lead/example to canonical `62c1b44`. |
| MEDIUM | D-12 disabled-test sign-off stale | Refresh disabled-test inventory before ticking sign-off. |

VERDICT: 0 BLOCKER, 2 HIGH, 2 MEDIUM, 0 LOW, 0 NIT

---

## Opencode Review

Let me verify the key claims against the actual repo state.Now I have enough evidence. Let me compile the review.

### HIGH — `@Disabled` test count wrong throughout plan (says 4, actual is 7)

- **Evidence**: Plan says "4 P22 tests still `@Disabled`" at lines 407, 456, 499, 510. 20-VALIDATION.md:24 also says "excludes 4 P22 `@Disabled` tests per D-12". But `find src/test -name "*.java" -exec grep -l "@Disabled" {} \; | wc -l` returns 6 files with 7 `@Disabled` annotations (ToxinTest.java has 2).
- **Why it matters**: An executor trusting the plan's count will see 7 disabled tests on `./gradlew test` output and may investigate a non-issue, wasting time. The plan's claim of "4" is demonstrably wrong against the repo.
- **Fix**: Change "4" → "7" in all four locations (lines 407, 456, 499, 510), or drop the specific count entirely and say "`@Disabled` tests per D-12". Also fix 20-VALIDATION.md:24 as part of Task 6.5.

Wait — let me verify there aren't exactly 4 tests that are "P22" specifically:

The disabled tests are: PopulationDynamicsTest, ToxinTest (×2), CellularAutomatonTest, MetabolismIntegrationTest, EncodeDeflatePerformanceGateTest, and a commented-out reference in LiveEntityRegistryInvariantTest. None are specifically labeled "P22". MetabolismIntegrationTest says "TD-22→P21", EncodeDeflatePerformanceGateTest says "TD-22→P21" — those are P21 not P22. The P22 reference makes no sense against the actual annotations.

Actually — re-reading more carefully: the original count of "4" might have been correct at some earlier commit, and additional tests have been disabled since. Regardless, the plan is wrong at HEAD.

### MEDIUM — `20-05-TRIAGE.md` referenced but not in context or read_first

- **Evidence**: Task 6.1 step 4 (plan:161) says "its outcome label is consistent with `20-05-TRIAGE.md` line 1 (`null-result: ...`)". This file exists (`head -1` confirms `null-result: codec ≤1.8% CPU...`) but is listed in neither the plan's `<context>` block (lines 72-116) nor Task 6.1's `<read_first>` (lines 122-130).
- **Why it matters**: An executor following `read_first` literally won't pre-read this file. They'd have to discover it mid-execution, breaking the plan's prescribed reading order.
- **Fix**: Add `- .planning/phases/20-connection-multiplexing-runtime-tuning/20-05-TRIAGE.md` to Task 6.1's `<read_first>` block.

### MEDIUM — Gauge acceptance grep may miss populated rows if table structure shifts

- **Evidence**: Acceptance criterion at plan:200 uses `grep -E "^\\| .?paralife\\.(tick\\.health\\.work-time-ms|outbound\\.detach\\.timeout)"`. After R1 fix, the `.?` absorbs the backtick wrapping. This works on the current table format but the regex `paralife\.tick\.health\.work-time-ms` must match the literal table content exactly. If the executor formats the populated cells with a different metric-name rendering (e.g., drops backticks or changes spacing), the grep would silently fail.
- **Why it matters**: The acceptance criterion is tightly coupled to a specific markdown table format. An executor changing the table's metric-name rendering to something other than `` `paralife.tick.health.work-time-ms` `` would cause a false-negative acceptance failure.
- **Fix**: Add a note in step 3 saying "preserve the existing table markdown format — backtick-wrapped metric names, pipe-delimited columns". Or widen the regex to not depend on exact backtick placement.

### LOW — CLAUDE.md insertion ~L141/~L144 approximations are ~11 lines off

- **Evidence**: Plan's `<interfaces>` block (plan:114) says `~L141` for end of Connection model, `~L144` for Project Skills. Actual: Connection model starts L130, Project Skills L145. L141 is the *last content line* of Connection model (not the section end), and L144 is `<!-- GSD:skills-start -->` (not the Project Skills heading at L145).
- **Why it matters**: The plan caveats "verify exact lines by Read first; they drift" — so this is recoverable. But the `~L141` estimate would place insertion before the GSD:architecture-end comment (L142), which is technically inside the prior section's closing markup.
- **Fix**: Update to `~L142` (after `<!-- GSD:architecture-end -->`) and `~L145` (`## Project Skills`). Or drop the line numbers entirely since the interfaces block already explains location conceptually.

### LOW — Task 6.1 read_first lists `20-01c-SUMMARY.md` but context block lists `20-01-SUMMARY.md`

- **Evidence**: Context block (plan:76) says `@.planning/.../20-01-SUMMARY.md`. Task 6.1 read_first (plan:124) says `20-01c-SUMMARY.md`. Both files exist — they're different files. The context block provides framing docs; the task references only 20-01c.
- **Why it matters**: An executor cross-referencing context block files against read_first might wonder why they differ. Both files exist, so no execution failure. Confusion at worst.
- **Fix**: Clarify in context block that 20-01-SUMMARY.md is the original Plan 1 summary (historical) while 20-01c-SUMMARY.md is the re-anchor summary (the one Task 6.1 needs).

### LOW — OutboundSender comment-insertion line ~132 vs actual L136

- **Evidence**: Plan interfaces block says "(line 124-135)" for OutboundSender.attachSession but `Thread.ofVirtual()` actually starts at L136. The `attachSession` method signature is at L128 (plan says ~124). The code block in interfaces is accurate; the line approximations are just ~4 lines off.
- **Why it matters**: Edit tool matches on code content, not line numbers. No execution impact. Cosmetic.
- **Fix**: Update the line range annotation if desired; not load-bearing.

### NIT — `application.yml:15` line reference unverified in plan text

- **Evidence**: CLAUDE.md insertion template (plan:241) says `application.yml:15 exposes the metrics actuator endpoint`. Verified: L15 is `include: health,info,metrics` — correct. But the plan doesn't instruct the executor to verify this; it's baked into the insertion template.
- **Why it matters**: If application.yml L15 ever shifts, this CLAUDE.md reference becomes stale. The plan currently has no verification step for it.
- **Fix**: The Acceptance Criteria for Task 6.2 already asks the executor to verify `AdmissionMetrics.java` line numbers — add `application.yml` L15 there too. Or add a note that the executor should `grep -n "include.*metrics" application.yml` and use that line number.

---

**Summary table:**

| Severity | Title | One-line fix |
|----------|-------|-------------|
| HIGH | `@Disabled` count says 4, actual is 7 | s/4/7/ in plan L407/456/499/510 + 20-VALIDATION.md L24 (or drop specific count) |
| MEDIUM | `20-05-TRIAGE.md` not in read_first | Add to Task 6.1 `<read_first>` |
| MEDIUM | Gauge acceptance grep fragile to table format | Add format-preservation note in Task 6.1 step 3 |
| LOW | CLAUDE.md ~L141/~L144 ≈11 lines off | Update to ~L142/~L145 |
| LOW | Context block has 20-01-SUMMARY.md, task needs 20-01c | Clarify why both exist in context block or add 20-01c-SUMMARY.md |
| LOW | OutboundSender ~132 vs actual L136 | Update line annotation |
| NIT | application.yml L15 reference unverified | Add verification step or note |

**VERDICT: 0 BLOCKER, 1 HIGH, 2 MEDIUM, 3 LOW, 1 NIT**

---

## Consensus Summary

### Agreed Strengths
- **Zero blockers, plan fundamentally executable** — all four verdicts return 0 BLOCKER. claude and opencode both confirm line refs are accurate or recoverable; nothing structural prevents execution.
- **R1 de-stale fixes verified holding** — claude empirically confirms the previously-unsatisfiable R1-H1 gauge grep now returns 2 on populated rows; opencode independently confirms the `.?` backtick-absorb fix works on current table format.
- **`application.yml:15` reference confirmed correct** — claude and opencode both grep-verified L15 = `include: health,info,metrics`, matching the CLAUDE.md insertion template.

### Agreed Concerns
- **[HIGH/MEDIUM split] §6 Profile Index omits the 3 `metrics-*-baseline-c22e487.json` sidecars (9 of 12 files)** — codex (HIGH), claude (MEDIUM). Step-5 c22e487 bullet lists JFR + meta + flamegraphs but drops the actuator metric sidecars; the must-have spec requires every sidecar listed, and the 62c1b44 group already lists its sidecars. Acceptance grep only checks `jfr-1000bots-baseline-c22e487.jfr`, so the gate passes with the gap intact — a smaller re-creation of the exact R1-H2 defect it closes. Fix: add `metrics-{100,500,1000}bots-baseline-c22e487.json` to the enumeration + an acceptance grep for at least the 1000-tier sidecar.
- **[HIGH/MEDIUM split] `@Disabled`/D-12 sign-off stale against repo HEAD** — opencode (HIGH), codex (MEDIUM). Plan says "4 P22 `@Disabled` tests" (L407/456/499/510 + 20-VALIDATION.md:24) but repo has 7 `@Disabled` annotations across 6 files. codex adds depth: `HundredBotIntegrationTest` is named disabled but is actually active, and some deferrals are P21 not P22. Ticking the sign-off as-is creates a false validation record. Fix: refresh inventory with a live `rg -n "@Disabled" src/test/java` snapshot, drop HundredBot, distinguish P22 from non-P22 before signing.
- **[HIGH/LOW split] Bare-path acceptance greps fail from repo root** — codex (HIGH), claude (LOW). Acceptance commands target bare `20-RUNTIME.md`/`20-VALIDATION.md` while verify blocks use full phase paths. From repo root the files don't exist → spurious failures. Several gate-relevant checks live only in `<acceptance_criteria>` with bare paths. Fix: prepend phase-dir path or `cd` before executable greps.
- **[MEDIUM/NIT split] Task 6.5 "two c22e487 Manual-Only Verification rows (~L81-84)" — only one row names the SHA** — gemini (MEDIUM), claude (NIT). Only L81 literally contains `c22e487`; the second row in range references `jfr-1000bots-tuned-<sha>.jfr`. Fix: change to "the single c22e487 row (~L81)".

### Divergent Views
- **Severity calibration diverges sharply on the two top concerns.** codex rates the §6 sidecar omission and bare-path greps as HIGH (truthful execution from root fails; gate can pass with defect); claude rates them MEDIUM/LOW (recoverable, verify blocks carry correct paths). Worth a deciding call on whether these block execution or are sweepable.
- **gemini's HIGH on Task 6.1 step 7 wrong-section is single-reviewer and contradicts claude.** gemini: the first parenthetical (`…Plan 6 reconciles…`, ~L370) lives in `§5 Forward Notes`, not `§6` as the step claims — a scoped search would miss it. No other reviewer flagged this, and claude explicitly stated "all 14 step-6 audit hits map to edit steps." **Investigate directly** — verify which section ~L370 actually sits in before trusting either.
- **`@Disabled` framing depth differs.** opencode treats it as a simple count error (s/4/7/); codex treats it as a correctness problem in the validation record (wrong test named, wrong milestone tag). Same root, different remediation scope — codex's deeper fix supersedes opencode's.
- **Single-reviewer residual-staleness items** (each raised once, worth folding into the de-stale sweep): codex — D-19 bullet still says "anchor to c22e487 / `git checkout c22e487`" with only an annotation, leaving self-contradictory lead text (MEDIUM); opencode — `20-05-TRIAGE.md` referenced in step 4 but absent from `<read_first>` (MEDIUM) and gauge grep coupled tightly to exact backtick table format (MEDIUM); claude — §6 L388 "Plan 5/6 tune against this set" stale forward-ref the audit regex's verb list misses, and CLAUDE.md template cites superseded Plan 1b instead of 1c as live capture source (both LOW).
