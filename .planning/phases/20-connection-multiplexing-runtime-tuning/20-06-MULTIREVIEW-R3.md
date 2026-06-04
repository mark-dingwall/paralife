---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T12:59:28Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md"]
models:
  codex: "gpt-5.5"
usage:
  claude: { input: 918674, output: 81, cached: 4146228, tool_calls: 10, elapsed_s: 444.9 }
  gemini: { input: 4132063, output: 1778, cached: 2599388, tool_calls: 23, elapsed_s: 491.1 }
  codex: { input: 1024815, output: 10761, cached: 900224, tool_calls: 32, elapsed_s: 224.7 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 26, elapsed_s: 395.3 }
synthesizer: claude
synthesized_at: 2026-06-04T12:59:28Z
---

# Cross-AI Review

## Claude Review

Verified the suspicion. The existing 62c1b44 metrics sidecar row is written brace-form (`metrics-{100,500,1000}bots-baseline-62c1b44.json`, L387) and `grep -q "metrics-1000bots-baseline-62c1b44.json"` **FAILS** on the current file — confirming the literal-1000-tier grep cannot match the index's own established brace style. I have enough to write the review.

---

# Pre-Execution Review: 20-06-PLAN.md

Scope verified against live repo (`HEAD` = `59e8cfd`). All file paths, SHAs, and line refs cross-checked. Bottom line: the plan is executable and largely converged after 5 prior rounds, with **one HIGH** acceptance-grep/instruction contradiction that mirrors the exact class of bug R1-H1 fixed.

---

### [HIGH] §6 c22e487 history-row greps reject the brace-form output the plan itself prescribes

**Evidence:**
- Task 6.1 step 5 instructs: *"Add a history-only row group for the superseded `c22e487` series (**compressed-brace form matching the existing index style**): `jfr-{100,500,1000}bots-baseline-c22e487.jfr` + … + `metrics-{100,500,1000}bots-baseline-c22e487.json`"*.
- Acceptance greps (20-06-PLAN, Task 6.1): `grep -q "jfr-1000bots-baseline-c22e487\.jfr"` **&&** `grep -q "metrics-1000bots-baseline-c22e487\.json"`.
- The existing 62c1b44 metrics row is brace-form (`20-RUNTIME.md:387` `profiles/metrics-{100,500,1000}bots-baseline-62c1b44.json`). Proof the literal grep can't see it:
  ```
  $ grep -q "metrics-1000bots-baseline-62c1b44\.json" 20-RUNTIME.md ; echo $?
  1   # FAIL — "...,1000}bots..." is not the contiguous substring "1000bots"
  ```
- Run against the plan's own prescribed enumeration string: both `jfr-1000bots-baseline-c22e487.jfr` and `metrics-1000bots-baseline-c22e487.json` greps **FAIL on brace form**.

**Why it matters:** If the executor follows step 5 ("compressed-brace form matching the existing index style") — and the existing style for the metrics sidecar row *is* brace (L387) — the truthful output is rejected by the acceptance gate. This is the same defect class as R1-H1 (a grep that can't match the table's real format). The metrics grep is the firm failure; the JFR grep is recoverable only because the existing 62c1b44 *JFR* rows happen to be individual/literal (L381–383), but the plan's step-5 enumeration writes the JFRs brace-form too, muddying that.

**Suggested fix (pick one):**
- (a) Instruct step 5 to write the c22e487 **JFR and metrics rows as individual per-tier literal rows** (mirror the 62c1b44 JFR rows at L381–383), so `jfr-1000bots-baseline-c22e487.jfr` and `metrics-1000bots-baseline-c22e487.json` appear literally; OR
- (b) Relax both acceptance greps to tolerate brace form, e.g. `grep -qE "metrics-(\{[0-9,]*1000\}|1000)bots-baseline-c22e487\.json"`.
Option (a) is cleaner and keeps the gate meaningful.

---

### [MEDIUM] §4.2 baseline columns mix scenarios (100/500 churn-62c1b44 vs 1000 active-103a615) with no per-tier label

**Evidence:** Task 6.1 step 3 sources the 100/500 baseline cells from `metrics-100bots-baseline-62c1b44.json` + `metrics-500bots-baseline-62c1b44.json` (churn) and `jfr-{100,500}bots-baseline-62c1b44.jfr` for pinning. The 1000-tier baseline cell (already shipped, L344–348) sources from `metrics-1000bots-active-50xfood-103a615.json` (active). The L348 footnote explains **only** the 1000-tier sourcing.

**Why it matters:** The §4.2 "100 baseline / 500 baseline / 1000 baseline" row becomes apples-to-oranges — two churn tiers and one active tier — without a label. It's the D-13/D-18 headline deliverable, so the inconsistency is reader-visible. Per-tier before/after integrity is preserved (1000 baseline+tuned are both active; 100/500 are baseline-only), so it's a clarity defect, not a measurement error.

**Suggested fix:** Either source the 100/500 baseline cells from the existing `metrics-{100,500}bots-active-50xfood-103a615.json` sidecars (they exist on disk) to make the baseline row scenario-uniform, OR add a one-line note that 100/500 baseline = churn-`62c1b44` while 1000 baseline = active-`103a615` (extend the existing footnote). Executor can recover either way; worth fixing before it ships.

---

### [LOW] Line-count tier for "pinning-dominates" contradicts Concern #13 disposition (inert this run)

**Evidence:** must_haves min_lines + Task 6.1 acceptance group pinning-dominates with null-result at **≥250** (`*"null-result"*|*"pinning-dominates-with-backlog-handoff"*) ≥250`). But `20-REVIEW-DISPOSITIONS.md` Concern #13 places "dominant pinning with backlog-handoff" in the **≥350** ("substantial narrative justified") group alongside codec/knob, and only null-result at ≥250.

**Why it matters:** A genuine drift between the canonical disposition and the plan grouping. **Zero execution impact this run** — Plan 5's outcome is `null-result`, so both schemes yield ≥250 (and the file is already 401 lines pre-execution). Flag only so the disposition and plan don't diverge if reused.

**Suggested fix:** Align the plan's pinning grouping to Concern #13 (≥350), or note the deliberate departure. Optional — inert now.

---

### [LOW] 20-VALIDATION tuned-JFR annotation target is L82, not the cited "~L83"

**Evidence:** Task 6.5 step 3 says the tuned-JFR row carrying `jfr-1000bots-tuned-<sha>.jfr` sits at "~L83"; actual is `20-VALIDATION.md:82`. The c22e487 SHA-bearing row is L81 (correctly identified by R2-M3 as the single SHA row).

**Why it matters:** Off-by-one, tilde-prefixed, and the plan instructs locate-by-quoted-text (`jfr-1000bots-tuned-<sha>.jfr`), which is present verbatim at L82. Harmless; drift-prone line ref already caveated per project convention.

**Suggested fix:** None required; optionally correct "~L83" → "~L82".

---

### [NIT] must_haves artifact line ref "OutboundSender … line ~132-135" drifts from actual L136-138

**Evidence:** must_haves artifact: *"D-02 inline comment block above the `Thread.ofVirtual().name("ws-sender-...")... start()` call at line ~132-135."* Actual `OutboundSender.java`: the `Thread.ofVirtual()…start()` call spans L136–138; the `<interfaces>` block correctly says *"~L128-138 … match on the code block, not line numbers."*

**Why it matters:** Self-caveated (match-on-code-block); the quoted code block is unique and present. Cosmetic.

---

## Items checked and found correct (no action)

- AdmissionMetrics `M_TICK_WORK_MS`=L70, `M_DETACH_TIMEOUT`=L79 → CLAUDE.md template `:70`/`:79` correct; re-verify clause present.
- `application.yml:15` `include: health,info,metrics` → template `application.yml:15` correct.
- 12 `c22e487` files on disk match the step-5 enumeration exactly (3 jfr + 3 meta + 3 metrics + cpu/alloc/lock-1000 html).
- 20-CONTEXT.md D-19 reconcile targets exact: L208/L225/L327/L463.
- profiles/README.md staleness targets present: L11-12/L25 "(always `c22e487`)" + L29 ritual heading.
- WorldWebSocketHandler insertion site exact (`afterConnectionEstablished` L317, `attachSession` L320); CLAUDE.md insertion exact (Connection model ends L141, `GSD:architecture-end` L142).
- Gauge acceptance grep `^\| .?paralife\.(…)` matches backtick rows (R1-H1 fix holds); satisfiable ≥2 once 100/500 baseline cells fill.
- Audit regex (step 6) returns 16 hits on current file, **all** map to steps 1/2/3/5 — matches plan's claimed dry-run; the `_baseline-only — see Phase 21_` tuned cells correctly evade it.
- "Plan 5 outcome: null-result" guard line present (`20-05-SUMMARY.md:50`) → tiered line-count case statement resolves to ≥250.
- Task 6.5 row-count math (2+2+3+3+2+2+3+5 = 22 ≥ 20) holds; all 8 PLAN files present; `20-02-2.1` valid.
- Tuned active-50xfood flamegraphs confirmed **not** on disk → step 5 "not captured" reword is truthful; no capture step.
- No residual staleness: c22e487 treated history-only, 20-01b superseded, "8 plans" throughout, no live tuned-flamegraph capture, already-populated §4.2-1000/§4.4 marked "verify, do not re-derive."
- ≥9 citation gate is zero-margin (6 pre-exec + exactly 3 from §4.3 tier headings) but already mitigated by R2-N1's verbatim-heading instruction and likely prose citations — **not re-flagged** (prior-round backlog item).

---

## Summary

| Severity | Title | One-line fix |
|----------|-------|--------------|
| HIGH | §6 c22e487 greps reject prescribed brace-form output | Write c22e487 JFR+metrics as individual literal per-tier rows (or relax both greps to tolerate `{…,1000}`) |
| MEDIUM | §4.2 baseline row mixes churn-62c1b44 (100/500) + active-103a615 (1000) unlabeled | Source 100/500 from active-103a615 sidecars, or add a per-tier scenario note |
| LOW | Pinning line-count tier (≥250) contradicts Concern #13 (≥350) | Align grouping or note departure — inert (outcome=null-result) |
| LOW | Tuned-JFR row ref "~L83" actually L82 | Locate-by-text works; optionally fix ref |
| NIT | OutboundSender "~132-135" vs actual L136-138 | Match-on-code-block; cosmetic |

**VERDICT: 0 BLOCKER, 1 HIGH, 1 MEDIUM, 2 LOW, 1 NIT**

The HIGH is a true acceptance-vs-instruction contradiction (verified: the analogous 62c1b44 brace row already fails the same literal grep), and is the only finding that would reject truthful execution output. Fix it before execution; the MEDIUM is a headline-table clarity issue worth resolving in the same pass; the rest are inert or cosmetic.

---

## Gemini Review

### [HIGH] Task 6.1 acceptance greps for c22e487 row group fail if executor follows compressed-brace instruction

**Evidence:** `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md` (Task 6.1 step 5 and `<acceptance_criteria>`)

**Why it matters:** In Task 6.1 step 5, the plan instructs the executor to add the superseded `c22e487` series using the compressed-brace form matching the existing index style (e.g., `metrics-{100,500,1000}bots-baseline-c22e487.json` and `jfr-{100,500,1000}bots-baseline-c22e487.jfr`). However, the acceptance criteria explicitly greps for the uncompressed strings: `grep -q "jfr-1000bots-baseline-c22e487\\.jfr"` and `grep -q "metrics-1000bots-baseline-c22e487\\.json"`. An execution agent faithfully following the instruction to write the compressed-brace format will fail the acceptance criteria check because the exact, uncompressed substrings will not be present in `20-RUNTIME.md`.

**Suggested fix:** Update the acceptance criteria greps to match the compressed-brace format requested in the action block (e.g., `grep -q "metrics-{100,500,1000}bots-baseline-c22e487\\.json" 20-RUNTIME.md` and `grep -q "jfr-{100,500,1000}bots-baseline-c22e487\\.jfr" 20-RUNTIME.md`).

### [MEDIUM] AdmissionMetrics line references in Task 6.2 are stale against HEAD

**Evidence:** `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md` (Task 6.2 `<action>` template and `<acceptance_criteria>`), `src/main/java/com/paralife/admission/AdmissionMetrics.java:63,72`

**Why it matters:** The plan provides an insertion template for `CLAUDE.md` with hardcoded line references `:70` and `:79` for `M_TICK_WORK_MS` and `M_DETACH_TIMEOUT` respectively. It then claims in the `<acceptance_criteria>` that these are the correct numbers "as of 2026-06-04" and tells the executor to re-verify and write those. However, at HEAD, these constants live at lines 63 and 72 in `AdmissionMetrics.java`. An executor could blindly copy the template, assuming the plan's claim about 2026-06-04 is accurate, which would introduce inaccurate documentation drift.

**Suggested fix:** Update the `CLAUDE.md` template in the `<action>` block and the corresponding note in the `<acceptance_criteria>` to use the correct line numbers at HEAD: `:63` and `:72`.

### Summary Table

| Severity | Title | Fix |
|----------|-------|-----|
| HIGH | Task 6.1 acceptance greps for c22e487 row group fail if executor follows compressed-brace instruction | Update the acceptance greps in Task 6.1 to match the compressed-brace format requested in the action block. |
| MEDIUM | AdmissionMetrics line references in Task 6.2 are stale against HEAD | Update the `CLAUDE.md` template and acceptance criteria in Task 6.2 to reference lines 63 and 72. |

VERDICT: 0 BLOCKER, 1 HIGH, 1 MEDIUM, 0 LOW, 0 NIT

---

## Codex Review

### [HIGH] Heap presets are requested as “measured-justified” despite no matching heap evidence

Evidence: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-PLAN.md:137`; current placeholder text in `20-RUNTIME.md:159-166`; baseline meta sidecars show 100/500 captured with `-Xms2g -Xmx2g` at `profiles/jfr-100bots-baseline-62c1b44.meta.json:8` and `profiles/jfr-500bots-baseline-62c1b44.meta.json:8`; active 1000 baseline also used `-Xms2g -Xmx2g` at `profiles/jfr-1000bots-active-50xfood-103a615.meta.json:7`. Plan 5 evidence only records the 1000 active tuned null-result and GC event summary, not lower-tier heap experiments (`20-05-SUMMARY.md:127-131`).

Why it matters: the plan tells the executor to replace heap `Pending — JFR-driven` text with “measured-justified” statements and says “heap presets (no GC-pressure signal → defaults stand).” That would make `1g/1g` and `1g/2g` look JFR-validated even though the comparable captures did not run with those heaps.

Suggested fix: split GC choice from heap sizing. For heap, say no heap retune was performed in Phase 20; lower-tier values remain operator-friendly smoke presets, while baseline-comparable runs must follow the `.meta.json` capture shape (`-Xms2g -Xmx2g`). If the intent is measured recipes, change the 100/500 recipes to `2g/2g`.

### [MEDIUM] 20-RUNTIME acceptance can pass with `jdk.VirtualThreadPinned` still pending

Evidence: Task 6.1 explicitly says fill all three 100/500 baseline rows, including `jdk.VirtualThreadPinned` (`20-06-PLAN.md:161`), but the executable acceptance grep only checks the two `paralife.*` gauge rows (`20-06-PLAN.md:204`). Current `20-RUNTIME.md` has the VTP row pending at `20-RUNTIME.md:346`.

Why it matters: an executor could fill `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout`, leave the VTP row as `_Pending_`, and still satisfy the listed gauge grep. The action’s audit command catches this, but it is not in `<verify>` or acceptance.

Suggested fix: add an acceptance command for zero pending markers, e.g. `! grep -inE "pending|plan [0-9/]+ (populates|produces|finalises|wires|will|tunes?)" 20-RUNTIME.md`, plus an explicit non-pending grep for the `jdk.VirtualThreadPinned` table row.

### [MEDIUM] `profiles/README.md` de-stale scope may leave wrong tuned artifact paths

Evidence: current `profiles/README.md` still advertises `jfr-{N}bots-tuned-{HEAD_SHA}.jfr` and `jfr-1000bots-tuned-${HEAD_SHA}.jfr` examples (`profiles/README.md:16-17`, `profiles/README.md:75-99`). Plan 20-06 only explicitly calls out baseline SHA framing and the c22e487 ritual, with a generic “tuned” state (`20-06-PLAN.md:177`). The shipped tuned artifact is `jfr-1000bots-active-50xfood-tuned-424e06d.jfr` (`20-05-SUMMARY.md:118`).

Why it matters: this is operator-facing capture guidance. If the executor fixes only the c22e487 baseline ritual, the README can still direct future tuned captures to the stale non-scenario filename shape.

Suggested fix: explicitly require updating every tuned filename example in `profiles/README.md` to a scenario-aware pattern, e.g. `jfr-{N}bots-{scenario}-tuned-{HEAD_SHA}.jfr`, with the Plan 5 concrete example `jfr-1000bots-active-50xfood-tuned-424e06d.jfr`.

### [MEDIUM] `jfr print` source command uses a path that fails from repo root

Evidence: Task 6.1 source instruction uses `jfr print --events jdk.VirtualThreadPinned profiles/jfr-{100,500}bots-baseline-62c1b44.jfr` (`20-06-PLAN.md:161`). The review request says the reviewer/executor is running in repo root, and from repo root that path resolves to `/home/mark/kramtime/paralife/profiles/...`, which does not exist. The real files are under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`.

Why it matters: this is not just prose; it is the command needed to populate the VTP cells. An executor running from repo root will get a file-not-found error.

Suggested fix: use repo-root paths in the task action, or add an explicit `cd .planning/phases/20-connection-multiplexing-runtime-tuning` before the `jfr print` examples.

| Severity | Title | One-line fix |
|---|---|---|
| HIGH | Heap presets requested as measured without evidence | Document them as smoke presets or switch recipes to measured `2g/2g` capture shape |
| MEDIUM | VTP row not enforced by acceptance | Add zero-pending audit and explicit VTP row grep |
| MEDIUM | `profiles/README.md` tuned paths may remain stale | Require scenario-aware tuned filename rewrites everywhere |
| MEDIUM | `jfr print` path fails from repo root | Use full phase-relative repo paths or explicit `cd` |

VERDICT: 0 BLOCKER, 1 HIGH, 3 MEDIUM, 0 LOW, 0 NIT

---

## Opencode Review

Let me continue reading the plan and verify key claims against the repository.Now I have a complete picture. Here is my review, verified against the actual repository at HEAD.

---

### [LOW] Task 6.1 step 5: `ls -lh` for capture metadata is imprecise

**Evidence:** `20-06-PLAN.md:166` — "Replace placeholder cells (`_Plan 1c_` capture dates, `_≤10 MB_` / `_~KB-class_` sizes) with actuals via `ls -lh ...`"

The "Captured" column in §6 (20-RUNTIME.md:379-396) currently contains *provenance labels* (`_Plan 1c_`, `_Plan 5_`, `_Plan 1c §Active_`), not dates. `ls -lh` gives filesystem mtimes (which are roughly correct here but imprecise) and file sizes. The `.meta.json` sidecars in the same directory contain authoritative `captured_at` timestamps.

**Why it matters:** The executor would get correct sizes from `ls -lh` but may replace useful provenance labels (`_Plan 1c_`) with raw timestamps, losing the traceability link to planning artifacts. The executor can recover (keep labels + add dates), but the instruction is vague.

**Suggested fix:** Clarify: `ls -lh` for file sizes; `.meta.json` for capture timestamps; retain the provenance label (e.g., `Plan 1c — 2026-05-27`).

**Verified**: `ls -1 .planning/.../profiles/*.meta.json` — per-file metas exist for every capture set (bash output). ✓

---

### [LOW] Task 6.5: 20-VALIDATION.md L62 "four P22 @Disabled" + TD-22-D listing not in rewrite scope

**Evidence:** `20-VALIDATION.md:62` — "D-12 explicitly forbids re-enabling the **four** P22 `@Disabled` tests (TD-22-A, TD-22-B, TD-22-C, **TD-22-D `HundredBotIntegrationTest`**)"

The plan correctly identifies stale "4" references at `20-VALIDATION.md:24` ("excludes 4 P22 @Disabled tests") and `20-VALIDATION.md:96` (sign-off: "4 @Disabled tests untouched"). Task 6.5 step 4 (`20-06-PLAN.md:460`) explicitly tells the executor to rewrite both. However, L62 in the Wave 0 Requirements intro text also carries "four P22" and lists TD-22-D as disabled — TD-22-D was **re-enabled** (as the plan itself documents at L411: "TD-22-D `HundredBotIntegrationTest` was RE-ENABLED").

After execution, L24 and L96 would be corrected, but L62 would still say "four" and list TD-22-D — an internal inconsistency in the same file the executor just fixed most of.

**Why it matters:** Low severity — the plan's live-inventory protocol is well-established (the R2 fix rewrote counts in 4 locations); the executor following the plan's pattern would likely catch this too. But the plan doesn't explicitly scope it, so it could be missed.

**Suggested fix:** Add L62 to the Task 6.5 step 4 rewrite scope: "also correct the Wave 0 intro sentence at ~L62 listing 'four P22 tests' to the live 6-annotation inventory."

**Verified**: `grep -rn "@Disabled(" src/test/java` at HEAD returns 6 annotations / 5 files (bash output). TD-22-D `HundredBotIntegrationTest` is not in the disabled list. ✓

---

### [NIT] Task 6.2 CLAUDE.md template carries `<application.yml:15>` with re-verify but no separate acceptance grep

The plan correctly instructs the executor to re-verify `application.yml:15` at execution time (acceptance criteria note at `20-06-PLAN.md:263`). The line number is currently correct (`src/main/resources/application.yml:15` = `include: health,info,metrics`). However, unlike the `AdmissionMetrics.java:[0-9]+` pattern which has a dedicated acceptance grep, the `application.yml` line ref in the inserted text has no automated acceptance check — only the manual re-verify instruction. The executor could forget the re-verify and write `:15` blindly (which happens to be correct right now, so this is truly NIT territory).

**Verified**: `grep -n "include:" src/main/resources/application.yml` → `15:        include: health,info,metrics`. ✓

---

### Summary

| Severity | Title | Suggested fix |
|----------|-------|---------------|
| LOW | Task 6.1 step 5: `ls -lh` for capture metadata is imprecise | Clarify: sizes from `ls -lh`, dates from `.meta.json`, retain provenance labels |
| LOW | Task 6.5: 20-VALIDATION.md L62 still lists 4 tests + TD-22-D as disabled | Add L62 to step 4 rewrite scope |
| NIT | Task 6.2: no automated check for `application.yml:15` line ref drift | Optional: add a grep acceptance check post-insertion |

**VERDICT: 0 BLOCKER, 0 HIGH, 0 MEDIUM, 2 LOW, 1 NIT**

The plan is execution-ready. The two LOW findings are recoverable without plan changes — the executor will encounter the ambiguities and resolve them sensibly using the live-inventory protocol already established throughout the plan. The prior R1+R2 review rounds converged effectively (16+ issues fixed, zero introduced).

---

## Consensus Summary

### Agreed Strengths
- Plan is execution-ready / converged after prior R1–R2 (and 5-round) review loops — many issues fixed (16+), zero introduced (claude, opencode).
- R1-H1 gauge-grep fix holds; large set of file paths, SHAs, and line refs cross-check correct against HEAD (claude, opencode — both independently verified subsets like `application.yml:15`).

### Agreed Concerns
- **[HIGH] Task 6.1 §6 c22e487 acceptance greps contradict the plan's own brace-form instruction** (claude, gemini). Step 5 tells executor to write `metrics-{100,500,1000}bots-baseline-c22e487.json` / `jfr-{...}` in compressed-brace form "matching existing index style," but acceptance greps require literal uncompressed substrings `metrics-1000bots-baseline-c22e487.json` / `jfr-1000bots-baseline-c22e487.jfr`. claude proved the literal grep already FAILS on the existing brace-form 62c1b44 metrics row (`20-RUNTIME.md:387`). Faithful execution yields truthful output that the gate rejects — same defect class as R1-H1. Fix before execution.

### Divergent Views
- **AdmissionMetrics line refs in Task 6.2 — direct factual conflict, must resolve before execution.** claude lists `M_TICK_WORK_MS`=L70 / `M_DETACH_TIMEOUT`=L79 under "checked and found correct," validating the plan's `:70`/`:79` template. gemini [MEDIUM] claims HEAD has these at L63/L72 and the template is stale. Both cite the same file at the same HEAD; one is wrong. Re-read `AdmissionMetrics.java` to settle. (Mitigant: plan carries a re-verify-at-execution clause either way.)
- **Fix direction for the HIGH grep contradiction.** claude prefers rewriting c22e487 JFR+metrics as individual literal per-tier rows (keeps gate meaningful) or relaxing greps; gemini prefers updating the greps to match brace form. Pick one — literal-rows keeps the acceptance check substantive.
- **Severity ceiling diverges sharply by reviewer.** claude/gemini found 1 HIGH (the brace grep); codex found a *different* HIGH (heap presets requested as "measured-justified" without matching heap evidence — 100/500 captured at `2g/2g`, plan would make `1g`/`1g-2g` look JFR-validated) plus 3 MEDIUMs (unenforced VTP-pending row, stale `profiles/README.md` tuned paths, `jfr print` path fails from repo root); opencode found nothing above LOW and called it execution-ready. None of codex's 4 findings were corroborated by another reviewer — worth a targeted check of the heap-evidence claim and the repo-root `jfr print` path, as those are concrete and verifiable.
- **Single-reviewer concerns to spot-check** (not consensus, but cheap to verify): §4.2 baseline row mixes churn-62c1b44 (100/500) with active-103a615 (1000) unlabeled (claude, MEDIUM); 20-VALIDATION L62 still lists "four" P22 tests + re-enabled TD-22-D outside rewrite scope (opencode, LOW).
