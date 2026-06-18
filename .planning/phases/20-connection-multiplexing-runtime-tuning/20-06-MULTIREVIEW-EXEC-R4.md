---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T15:39:40Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md", "README.md"]
models:
  claude: "claude-opus-4-8"
usage:
  claude: { input: 320532, output: 4, cached: 38878, tool_calls: 0, elapsed_s: 238.8 }
  gemini: { input: 243926, output: 996, cached: 0, tool_calls: 0, elapsed_s: 122.9 }
  codex: { input: 1499974, output: 9993, cached: 1350656, tool_calls: 30, elapsed_s: 224.3 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 46, elapsed_s: 400.4 }
synthesizer: claude
synthesized_at: 2026-06-04T15:39:40Z
fallbacks:
  gemini:
    attempts: ["gemini-3.1-pro-preview", "gemini-3-flash-preview"]
    used: "gemini-3-flash-preview"
---

# Cross-AI Review

## Claude Review

# Review: Plan 20-06 EXECUTION (post-merge) — ROUND 4

## (a) R3 fix verification — PASS

All five R3 fixes hold against the live post-merge files.

| R3 fix | Verified |
|---|---|
| **Inline baseline sampling loop** (profiles/README step 5) | Bash valid; emits well-formed JSON (`{captured_at_sha,scenario,cap_during_run,samples[]}` + per-sample `sample_utc`). `sed 's/\./_/g'` keys correctly preserve the hyphenated `work-time-ms` segment → `paralife_tick_health_work-time-ms` (matches the committed key the 20-01c pushback established). `--argjson v null` on empty body is valid. 6 samples = correct baseline cadence (active=18). `food_spawn_prob` correctly absent (baseline schema, not active). ✓ |
| **do-not-use-capture-active.sh guidance** | Comment names the exact reasons (`TAG=active-50xfood`, `FOOD=0.05`, 90s window, own server+food flag) — matches `capture-active.sh:8-9,18-20`. ✓ |
| **20-CONTEXT de-stale (4 sites)** | L42-44, L61-62, L168-171, L300-301 all reconcile to live inventory: 6 `@Disabled`/5 files = 3 P22-origin (A/B/C) + 3 pre-existing (Toxin×2 + CellularAutomaton); TD-22-D never disabled. Math checks (3+3=6); consistent with 20-VALIDATION Wave-0 intro. ✓ |
| **README runBot "Single-bot"→up-to-100** | "Operator CLI — launches up to 100 bots per invocation" matches CLAUDE.md §Architecture "100-bot cap". ✓ |
| **§3.3 "default JVM setting"→"explicitly-set parallelism=8"** | 20-RUNTIME L348 reads correctly; consistent with the `-Djdk.virtualThreadScheduler.parallelism=8` in the §3.3 recipe + meta. ✓ |

No fix introduced a new contradiction. The recurring fix-induced failure mode did not recur at R3→R4.

## (b) New findings

### MEDIUM — `profiles/README.md` baseline ritual: harness (step 3) not backgrounded → steps 4–5 capture an idle/drained server

**File:** `profiles/README.md` §"62c1b44 baseline ritual", steps 3–5.
**What's wrong:** Step 3's `java -jar ...-load-harness.jar --count 1000 --duration 200 ...` runs in the **foreground** (no trailing `&`). It blocks the operator's shell for the full 200 s load window. Steps 4 (three serial `$ASYNC_PROFILER -d 60` = 180 s) and 5 (the **new R3 metric loop**, 6×5 s ≈ 30 s) therefore execute *after* the harness has already disconnected all 1000 bots — directly contradicting step 4's own comment "**While load runs: capture flamegraphs concurrently**" and the Pass-2 Concern #10 intent that the metric loop sample headline gauges *under load*. Result of running verbatim: idle-server flamegraphs and drained-population metrics (`active.entities`→~0, `tick.health.work-time-ms`→idle), not the steady-state regime the committed `metrics-1000bots-baseline-62c1b44.json` represents.

The R3 change made this worse: the prior step 5 was a one-shot `curl` (instant); R3 replaced it with a 30 s loop, so even if step 3 were backgrounded, 180 s (flamegraphs) + 30 s (metrics) = 210 s overruns the 200 s load window.

This is **pre-existing** (the missing `&` dates to Plan 1's README) but sits inside the R3-rewritten region and defeats the purpose of the R3 loop, so it's fair to surface now. The "not byte-for-byte reproducible" caveat covers *imprecision*, not a *wrong-regime* capture.

**Concrete fix:** Background step 3 (`... --harness-id baseline-62c1b44-t1000 &` + capture its PID) and either run the three flamegraphs concurrently (`&` + `wait`, which the bootstrap doc already supports) or raise `--duration` so 180 s + 30 s fits inside the load window with margin.
**Evidence:** `profiles/README.md` step 3 (no `&`) vs step 4 comment "While load runs"; harness `--duration 200`; committed baseline sidecars produced by `capture-tier.sh` (20-01c-SUMMARY:66 "capture-tier.sh ... per tier"), not this ritual.

### LOW — `20-RUNTIME.md` §4.3.1: "100 ms tick budget" is an unsupported threshold

**File:** `20-RUNTIME.md` §4.3.1 (~L417): "The tick work time of ~9 ms gives ~91 ms slack before a 100 ms tick budget is breached."
**What's wrong:** The documented tick **interval** is 500 ms (20-01c-SUMMARY: "well under the 500 ms tick interval"; CLAUDE/keepalive "30 ticks × 500ms = 15s"). No 100 ms tick budget appears anywhere in the phase artifacts — it reads as a defined threshold but isn't one. The arithmetic (100−9=91) is internally consistent, but the premise under-states headroom (real slack vs the 500 ms cadence is ~491 ms). Harmless to the section's conclusion ("no tuning needed at 100 bots"), which holds under either figure.
**Concrete fix:** Either cite the real 500 ms interval, or drop the specific number ("ample slack under the 500 ms tick cadence"). New text introduced by Plan 6 §4.3.1; missed by all rounds (R1–R3 focused on GC numbers).
**Evidence:** 20-01c-SUMMARY §Active table footnote (500 ms interval); no `100 ms` budget in config/yaml.

### NIT — `20-VALIDATION.md` row 20-01-1.1 asserts `c22e487 ≥ 3` in a file the phase de-staled

**File:** `20-VALIDATION.md` Per-Task Map, row `20-01-1.1` automated command: `grep -c "c22e487" .../profiles/README.md | awk '$1 >= 3 ...'`.
**What's wrong:** The R1 de-stale dropped `c22e487` mentions in `profiles/README.md` from 19 → **exactly 3** (filename-convention note, ritual-intro retention note, "Do NOT use c22e487"). The green historical row passes only at its floor; any future trim of one mention silently red-flags a row already marked ✅. Cosmetic — the row documents Plan 1's original (19-mention) deliverable, not current state.
**Fix (optional):** Lower the floor to `>= 1`, or annotate the row that the count reflects the original Plan 1 capture, not post-de-stale state.
**Evidence:** `profiles/README.md` current `c22e487` count = 3 (filename-convention §, ritual-intro ×2).

## Convergence judgement

**No new HIGH+ — convergence reached.** R3's fixes verify clean (the inline sampling loop is correct bash, correct schema, correct keys vs the committed baseline sidecar; the de-stale annotations reconcile to the live 6-annotation inventory). The new items are one MEDIUM operator-ritual defect (pre-existing missing `&`, fix opportunistically), one LOW unsupported-number, and one NIT brittle assertion — none block ship. Recommend fixing the MEDIUM before the README is treated as the canonical reproduction contract, then `/gsd-verify-work 20`.

---

## Gemini Review

# Review: Paralife Phase 20 Plan 20-06 EXECUTION (post-merge) — ROUND 4

**Round 4 convergence check complete.**

Verification of the R3 fixes and a final deep-sweep of the documentation-finalisation artifacts confirms that all factual errors and internal contradictions identified in previous rounds have been resolved. The deliverable is internally consistent, accurately reflects the Phase 20 evidence set, and provides clear, runnable instructions for operators and future benchmark phases.

## (a) R3 fix verification — PASS

| Item | Fix Confirmation | Verdict |
|:---|:---|:---:|
| **Baseline Metric Ritual** | `profiles/README.md` Step 5 now uses an inline sampling loop that correctly produces the committed JSON schema (`captured_at_sha`, `scenario`, `samples[]`) without relying on the active-scenario-only `capture-active.sh`. Loop logic correctly transforms `.` to `_` in keys and polls all 9 relevant meters. | **PASS** |
| **Disabled Test Inventory** | `20-CONTEXT.md` (§38, §59, §162, §298) correctly de-staled: "4 P22 @Disabled" purged; live inventory (6 across 5 files) cited; `HundredBotIntegrationTest` (TD-22-D) correctly noted as active but flaky. | **PASS** |
| **README runBot** | `README.md` L18 accurately characterizes `runBot` as an operator CLI launching up to 100 bots, removing the "Single-bot" contradiction. | **PASS** |
| **SUMMARY Em-dash Split** | `20-06-SUMMARY.md` L49 correctly splits independent facts (G1, VT pinning, Phase 999.6), removing misleading causal chains. | **PASS** |
| **Parallelism Wording** | `20-RUNTIME.md` L348 correctly describes `parallelism=8` as "explicitly-set", removing the "default JVM setting" inaccuracy. | **PASS** |

## (b) Factual & Consistency Sweep — PASS

*   **GC Numbers:** MATHEMATICALLY CONSISTENT across all seven narrative passages and §4.2/§4.4 tables. All counts (10/18/18 churn, 0/0/0 active, 4 tuned) and percentages match the `jfr print` evidence.
*   **D-02 Three-Place Codification:** Verified in all 4 required files (README, CLAUDE, `WorldWebSocketHandler`, `OutboundSender`). All grep for `WS:entity 1:1` and cite the correct phase spec sections.
*   **20-RUNTIME.md Honesties:** §3 introduction correctly identifies lower-tier heap presets as operator smoke sizes (1g) vs the 2g capture shape used for the evidence base. §4.3.1/§4.3.2 correctly frame lower tiers as "baseline-only" reproductions for Phase 21.
*   **Audit Pass:** `grep -iqE "pending|plan [0-9/]+ (populates|produces|finalises|wires|will|tunes?)" 20-RUNTIME.md` returns 0 hits (excluding intentional forward notes for Phase 21/999.x).
*   **Validation Map:** 22-row map in `20-VALIDATION.md` is complete, correctly identifying superseded tasks and prose-section mappings for Plan 1c.

## Findings

No new `HIGH`, `MEDIUM`, or `LOW` issues found.

#### NIT — `20-06-SUMMARY.md` Commit Reference
- **File:** `20-06-SUMMARY.md` L110 / L207.
- **Note:** The validation sign-off and commit list cite `d37d281` (Task 6.4) as the green point. While Task 6.5 (`d592593`) is the final commit of the plan, `d37d281` is the correct SHA where the behavioural/comment changes were completed and the three-gate stack was verified green. This is technically precise and correct.

## Convergence judgement

**No new HIGH+ — convergence reached.**

The artifacts are authoritative and fulfill the Phase 20 contract (SCALE-08, SCALE-09). The documentation is pre-publication ready.

```bash
/gsd-verify-work 20
```

---

## Codex Review

**Findings**

- **HIGH** — [profiles/README.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:60) §62c1b44 baseline ritual: the R3 inline metric loop is bash-valid and matches the committed baseline sidecar schema, and the “do not use `capture-active.sh`” guidance is correct. But the ritual still runs the harness in the foreground at lines 60–64, so the flamegraph captures at lines 66–71 and the new metric loop at lines 73–98 execute only after the 200 s load has finished. That contradicts “While load runs” and would produce off-load flamegraphs/sidecars if followed verbatim.
  **Fix:** run the harness in the background, e.g. `... --harness-id baseline-62c1b44-t1000 & HARNESS_PID=$!`, run flamegraph + metric sampling while it is active, then `wait "$HARNESS_PID"` before restore. Prefer a `TIER=1000` variable so `SIDE`, `scenario`, JFR name, and harness-id all change together for 100/500 repeats.
  **Evidence checked:** [capture-active.sh](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/capture-active.sh:47) samples during the profile window before waiting for the harness; committed `metrics-1000bots-baseline-62c1b44.json` samples are `22:12:13`→`22:12:39`, shortly after JFR `captured_utc=22:11:46`, so the real artifacts were sampled during the run.

- **LOW** — [20-CONTEXT.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md:180) and nearby canonical refs still cite stale `AdmissionMetrics.java` line numbers (`:74`, `:65/:74/:175-176/:451`). Current source has `M_TICK_WORK_MS` at line 70 and `M_DETACH_TIMEOUT` at line 79; CLAUDE.md and 20-06-SUMMARY already use 70/79.
  **Fix:** update the context citations to `AdmissionMetrics.java:70` / `:79`, or remove exact line numbers from the historical context file.
  **Evidence checked:** `grep -n "M_TICK_WORK_MS\\|M_DETACH_TIMEOUT" src/main/java/com/paralife/admission/AdmissionMetrics.java`.

Verified clean: R3’s `20-CONTEXT` c22e487 de-stale annotations are present; disabled-test inventory matches live grep (6 annotations across 5 files); `20-RUNTIME.md` zero-pending audit passes; JFR GC counts match corrected values: churn 10/18/18, active 0/0/0, tuned 4; pinning is 0 across checked JFRs.

**Convergence judgement:** NEW HIGH+: one HIGH remains, the `profiles/README.md` baseline ritual runs its captures after load. Convergence not reached.

---

## Opencode Review

Let me read the post-merge files and verify the R3 fixes systematically, then sweep for anything missed.All R3 fixes verified against post-merge files. Now final cross-artifact sweep:## Round 4 Review: Plan 20-06 EXECUTION (post-merge)

### (a) R3 fix verification — ALL PASS

| # | R3 Finding | Fix location | Verified |
|---|-----------|-------------|----------|
| H1 | c22e487 brace-form acceptance greps | §6 history rows use `{100,500,1000}bots` | ✅ L549-555 |
| H2 | Heap presets not JFR-validated | §3 intro `not JFR-validated for lower tiers` L162 | ✅ |
| M1 | Zero-pending audit gate | `grep -iqE "pending|plan..."` → rc=1 | ✅ |
| M2 | profiles/README tuned filename scenario-aware | naming table + ritual use scenario-aware pattern | ✅ |
| M3 | §4.2 scenario-note | "Scenario note: the 100-bot and 500-bot..." L383 | ✅ |
| M4 | jfr print repo-root path | `--events jdk.VirtualThreadPinned .planning/phases/...` | ✅ |
| L1 | SUMMARY em-dash conflation split | `"0 jdk.VirtualThreadPinned...; G1 confirmed for GC; Phase 999.6..."` L49 | ✅ |
| L2 | §3.3 "default JVM" → "explicitly-set parallelism=8" | L348 | ✅ |
| L3 | 20-CONTEXT de-staled at 4 sites | L41-43, L61-62, L169-170, L301 all correct | ✅ |
| L4 | README "Single-bot" → "up to 100 bots" | L18 | ✅ |
| N1 | OutboundSender line corrected | OK |
| — | Footer `2026-06-XX` → `2026-06-05` | L558 | ✅ |
| — | VALIDATION Plan 5 checklist line | L93 present ✅ |
| — | VALIDATION L105 D-02 grep `20-RUNTIME\.md` | Dead branch fixed ✅ |

**Disk-verified factual claims:**
- Line count: 558 matches `wc -l` ✅
- 41 `62c1b44` citations ✅
- 22 VALIDATION rows, 5 Plan 6 rows ✅
- `AdmissionMetrics.java:70`/`:79` correct at HEAD ✅
- `application.yml:15` `include: health,info,metrics` correct ✅
- D-02: `grep -lE "WS:entity 1:1"` = 4 files ✅
- CLAUDE.md Runtime tuning at L143, between Connection model (L130) and `GSD:architecture-end` (L171) ✅
- Inline sampling loop schema fields (`captured_at_sha`, `scenario`, `cap_during_run`, `samples[]`, `sample_utc`, dot→underscore key transform) match committed `metrics-1000bots-baseline-62c1b44.json` ✅
- METRICS array matches `capture-active.sh` ✅
- `Do NOT use ../capture-active.sh` warning with correct rationale ✅

### (b) What all three rounds missed

**No new HIGH+ findings.** One LOW observation:

**LOW** — `profiles/README.md` inline sampling loop hardcodes `SIDE="$OUT_DIR/metrics-1000bots-baseline-62c1b44.json"` and `"scenario":"1000bots"`. The README says "Repeat steps 2-5 for --count 100 and --count 500 tiers" — the operator already manually adjusts JFR_OUT for each tier (step 2), so adjusting SIDE is the same manual pattern. Consistent with existing README convention; below threshold.

### Convergence judgement

**No new HIGH+ — convergence reached.** All four rounds' fixes verified correct against post-merge files. Internal consistency across `20-RUNTIME.md`, `20-06-SUMMARY.md`, `20-VALIDATION.md`, `20-CONTEXT.md`, `profiles/README.md`, `README.md`, `CLAUDE.md`, and both Java source files confirmed. Zero pending markers, zero stale forward-refs, all D-21 outcomes enumerated, D-02 codification grep-verifiable, GC numbers consistent across all seven passages.

---

## Consensus Summary

### Agreed Strengths
- **All R3 fixes verify clean** (4/4 reviewers): inline baseline metric loop is bash-valid and matches committed `metrics-1000bots-baseline-62c1b44.json` schema (`captured_at_sha`, `scenario`, `cap_during_run`, `samples[]`, `sample_utc`); dot→underscore key transform correct; METRICS array matches `capture-active.sh`.
- **`do-not-use capture-active.sh` guidance correct** (claude, gemini, codex, opencode): rationale names exact reasons (TAG, FOOD flag, 90s window).
- **20-CONTEXT de-stale holds** (4/4): live disabled-test inventory = 6 `@Disabled` across 5 files (3 P22 + 3 pre-existing); TD-22-D active-but-flaky correctly noted.
- **README runBot + §3.3 parallelism wording fixes correct** (claude, gemini, opencode): "up to 100 bots" matches CLAUDE.md cap; "explicitly-set parallelism=8" accurate.
- **GC numbers mathematically consistent** (claude, gemini, codex, opencode): churn 10/18/18, active 0/0/0, tuned 4, pinning 0 — match `jfr print` evidence across all passages/tables.
- **D-02 WS:entity 1:1 codified in 4 files** (gemini, opencode): grep-verifiable.

### Agreed Concerns
- **Baseline ritual runs harness in foreground → captures fire after load drains** (HIGH per codex, MEDIUM per claude; gemini/opencode missed): `profiles/README.md` §62c1b44 step 3 `java -jar ...load-harness.jar --duration 200` lacks trailing `&`. Flamegraphs (180s) + new R3 metric loop (~30s) execute *after* the 200s window closes → off-load/idle-server flamegraphs and drained-population sidecars, contradicting step 4's "While load runs" comment and Pass-2 Concern #10 intent. Claude notes R3 made it worse (one-shot curl → 30s loop) and 180+30=210s overruns 200s even if backgrounded. **Fix (both agree):** background harness (`& HARNESS_PID=$!`), capture concurrently, `wait` before restore; prefer a `TIER`/`SIDE` variable so sidecar name, scenario, JFR name, harness-id change together per tier. Evidence: committed baseline sidecar samples (22:12:13→22:12:39, after JFR 22:11:46) prove real artifacts came from `capture-tier.sh` during the run, not this ritual.

### Divergent Views
- **Severity of the foreground-harness defect** — codex rates HIGH and declares convergence NOT reached; claude rates MEDIUM (pre-existing missing `&` from Plan 1, fix opportunistically, doesn't block ship); gemini and opencode did not flag it at all and declared convergence reached. Worth resolving before treating README as the canonical reproduction contract — three of four either downgrade or miss it, but the one HIGH is well-evidenced.
- **Stale `AdmissionMetrics.java` line numbers in 20-CONTEXT** (codex LOW only): cites `:74`, `:65/:74/:175-176/:451`; live source has `M_TICK_WORK_MS` at :70, `M_DETACH_TIMEOUT` at :79 (CLAUDE.md/SUMMARY already use 70/79). Not raised by others — verify and either update or strip line numbers from historical context file.
- **Minor brittle/cosmetic items, single-reviewer each:** claude — 20-RUNTIME §4.3.1 "100 ms tick budget" unsupported (real interval 500ms), LOW; claude — VALIDATION row 20-01-1.1 `c22e487 >= 3` passes only at floor, NIT; opencode — inline loop hardcodes `SIDE`/`scenario=1000bots` (consistent with existing per-tier manual pattern), below threshold; gemini — SUMMARY cites `d37d281` (Task 6.4) as green point, deemed technically correct.
