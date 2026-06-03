---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T18:37:17Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md"]
usage:
  claude: { input: 304739, output: 27, cached: 720545, tool_calls: 2, elapsed_s: 201.4 }
  gemini: { input: 159881, output: 285, cached: 0, tool_calls: 0, elapsed_s: 93.6 }
  codex: { input: 394600, output: 4335, cached: 309120, tool_calls: 12, elapsed_s: 94.9 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 6, elapsed_s: 242.5 }
synthesizer: claude
synthesized_at: 2026-06-03T18:37:17Z
---

# Cross-AI Review

## Claude Review

R6 review done. Verified all 4 R5 fixes against live `20-RUNTIME.md` + the four cited evidence sources (20-01c-SUMMARY, 17-ADMISSION §3/§6, meta-sidecar filenames, 20-CONTEXT D-IDs).

## R5 fixes — landed clean

- **Fix 1 (delay=15s):** present all 3 recipes (L202/L234/L276). `delay=15s,duration=Ns,filename=...,settings=profile,name=...` is valid `StartFlightRecording` syntax — delay+duration parse fine, recording runs `[launch+delay, launch+delay+duration]`. New intro para (L168-184) does NOT contradict the per-recipe blocks. ✓
- **Fix 2 (§3.3 lifecycle qualifier):** L306 now carries `(new sessions only — see §2.2 lifecycle note)`. ✓
- **Fix 3 (§3→§6 cross-ref):** 4 sites moved to §6 (L79, L192, L257, L306); top-of-doc L8 correctly stays §3. Verified 17-ADMISSION §6 holds the actual queue-depth math (`128 frames ≈ 64s buffer; tune for workload`, :237) and §3 is the STALLED FSM. ✓
- **Fix 4 (churn caveat):** cites `--duration 200 / rate:50 / 2g/2g / parallelism=8` — matches baseline meta sidecars; points at `profiles/jfr-Nbots-baseline-62c1b44.meta.json` (correct on-disk filename pattern). ✓

## Findings

`L179-181: NIT: tradeoff sentence is internally inconsistent — same para says delay=15s is "aligned with the harness connect/ramp window" (L171, harness_start≈launch+15) yet claims "harness tail ~15 s of un-recorded load". If JFR start is aligned with harness start and both run the same duration, JFR end ≈ harness end → tail loss ≈ 0, not 15 s. The "~15 s" only holds if the harness started at t=0 (it doesn't — it's launched after boot). Fix: drop the specific magnitude ("the harness tail may fall outside the window by roughly the boot-time offset"), or keep the jcmd/bump-duration mitigation without the 15 s figure.`

Everything else scanned reduces to the do-not-reflag backlog (§3.1/§3.2 parallelism rationale, §6 `≤10 MB` placeholders, footer date, reserved fields, D-19 `c22e487` reconciliation, §4 Pending cells). No stray `live-tunable` mislabel of `outbound-queue-size` remains; heap divergence (1g vs captured 2g) already `Pending`-flagged; F1 arithmetic + `RespawnConfig` key intact.

## Verdict: **PASS** — convergence reached

1. All 4 R5 fixes landed cleanly, parse correctly, and the new `delay=15s` intro para is self-consistent with the per-recipe blocks — the core R6 question.
2. One NIT only (tradeoff-magnitude self-inconsistency, a residual of the intentionally-added R5 tail acknowledgment); it misdirects nothing — Plan 5 is codec work, Phase 21 is already told to use jcmd JFR.start / bump duration for precise capture. No data loss, no wrong decision locked.
3. Trend caveman 3 → R4 6 → R5 4 → **R6 0 substantive (1 NIT)**. No new BLOCKER/HIGH/MEDIUM. Loop closed — doc ready for Plan 5 consumption; the NIT + known backlog are Plan 6 polish.

---

## Gemini Review

## Findings

- `L161-169: NIT: The documented JFR tail-truncation tradeoff perfectly addresses the R5 JFR window concern for the smoke-template. Clean resolution with a clear handoff to Plan 5/21 for comparable tail capture.`
- `L87, L186, L233, L285: NIT: All 17-ADMISSION.md queue sizing cross-references correctly point to §6 Backpressure, fixing the R5 misdirect. The yaml block lifecycle qualifier "(new sessions only)" also landed correctly in §3.3.`
- `L144-156: NIT: Churn-baseline reproducibility caveat is explicit and points to the correct meta sidecars, fully resolving Codex's R5 finding.`
- `L352-357: NIT: The remaining placeholder markers (e.g. "_≤10 MB_") in the §6 Profile Index correctly reflect the known Plan 6 backlog.`

## Readiness

**PASS**
Convergence is reached (no new substantive findings). All R5 fixes landed cleanly and self-consistently, and all remaining placeholders are correctly delegated to Plan 5 and Plan 6 as known backlog. The document is structurally flawless, accurate, and fully ready for Plan 5 to inject its measured findings.

---

## Codex Review

Findings: None.

Verified R5 fixes landed cleanly:
- JFR `delay=15s` is present in all recipes (`L202`, `L234`, `L276`), and `L168-L184` now documents both startup cushion and residual tail tradeoff.
- Churn-baseline caveat (`L146-L156`) matches the baseline meta sidecars: `--duration 200`, `rate:50`, `-Xms2g -Xmx2g`, `parallelism=8`.
- Queue-depth refs now point to `17-ADMISSION.md §6 Backpressure`; repo confirms §6 starts at line 224 and queue sizing math is line 237. §3.3 lifecycle qualifier is present at `L306`.

Readiness: **PASS**.  
Rationale: no new substantive findings; R5 fixes are self-consistent and code/artifact-grounded.  
Convergence: **reached** for this round.

---

## Opencode Review

## R6 Review: 20-RUNTIME.md (post-R5-polish)

**R5 fix verification — all 4 landings confirmed:**

| R5 fix | Verdict | Evidence |
|--------|---------|----------|
| 1. `delay=15s` in all 3 recipes | ✓ | Lines 202, 234, 276 — correct syntax |
| 2. §3.3 yaml lifecycle qualifier | ✓ | Line 306: `(new sessions only — see §2.2 lifecycle note)` |
| 3. 17-ADMISSION §3→§6 cross-ref (4 sites) | ✓ | Lines 79, 192, 257, 306 — all cite §6; L8 correctly stays §3 |
| 4. Churn-baseline reproducibility caveat | ✓ | Lines 146-157 — cites `*-62c1b44.meta.json`, names all 4 params |

**New self-consistency checks:**

| Check | Result |
|-------|--------|
| "new sessions only" at all 4 sites (§2.2, §3 intro, §3.2, §3.3) | ✓ |
| §2.2 table: 4 rows, all `[reserved]` | ✓ |
| 4/4 `Pending — JFR-driven` markers (3× GC + 1× VT parallelism) | ✓ |
| Top-of-file §3→§6 gate: only L8 cites §3 | ✓ |

---

### Findings

**L179-181: MEDIUM: JFR-timing tradeoff sentence is factually wrong — says "harness tail" should say "harness front", and "~15 s" overestimates the loss.**

Arithmetic (100-bot tier):
- t≈5s: server ready, harness starts
- t=15s: JFR starts (`delay=15s`)
- t=75s: JFR stops (`15+60`)
- t≈65-67s: harness exits

JFR ends **after** harness exits at every tier (8-10s margin). The harness tail IS captured. The un-recorded portion is the **front** (~t=5→15s, ~10s of ramp/connect), not the tail. The parenthetical "anything past that point isn't captured" describes post-harness idle, not harness load.

Fix: `s/harness tail/drops the first ~10 s of harness load/` and drop or rework the parenthetical.

---

### Verdict: **FLAG**

**Rationale:** This is neither known-backlog (Pending markers/placeholders) nor known-addressed (R5 fix targets). It's a factual error newly introduced by R5's JFR-timing paragraph rewrite — the coverage math was corrected for the front (`delay=15s`) but the tradeoff sentence reversed the direction of the gap. The recipes themselves (the `-XX:StartFlightRecording` lines) are correct; only the prose tradeoff is wrong. Fix is one sentence rewrite. Not a blocker for Plan 5 codec work.

**Convergence signal:** This rounds the corner. One mistake survived 6 review passes (the tradeoff math wasn't independently recomputed in any round). Trend holds: caveman 3 → R4 6 → R5 4 → **R6 1**. The diminishing-return curve is flattening into genuine nit territory.

---

## Consensus Summary

### Agreed Strengths
- All 4 R5 fixes verified landed cleanly across reviewers: `delay=15s` present in all 3 JFR recipes (L202/L234/L276) with valid `StartFlightRecording` syntax; §3.3 yaml lifecycle qualifier `(new sessions only — see §2.2 lifecycle note)` at L306; 17-ADMISSION §3→§6 cross-ref corrected at 4 sites (L79/L192/L257/L306) with L8 correctly retained at §3; churn-baseline reproducibility caveat citing correct meta sidecars (`*-62c1b44.meta.json`) and all 4 params (`--duration 200`, `rate:50`, `2g/2g`, `parallelism=8`).
- Cross-references are code/artifact-grounded — 17-ADMISSION §6 confirmed to hold the queue-depth math (§6 starts L224, sizing math L237), §3 is the STALLED FSM.
- Remaining placeholders (`≤10 MB` in §6 Profile Index, Pending JFR-driven markers) correctly delegated to Plan 5/6 as known backlog, not stray defects.
- Strong convergence trend: caveman 3 → R4 6 → R5 4 → R6 0–1 substantive. Document structurally sound and ready for Plan 5 consumption.

### Agreed Concerns
- **JFR-timing tradeoff sentence factually wrong (L179-181)** — raised by claude (NIT) and opencode (MEDIUM/FLAG). The tradeoff prose is internally inconsistent with the recipes: it claims a "~15 s harness tail" falls outside the JFR window, but since JFR starts at `delay=15s` (aligned to harness start) and runs the same duration, JFR ends *after* the harness exits (8–10s margin per opencode's tier math). The un-recorded gap is the **front** (~first 10s of ramp/connect), not the tail; the "~15 s" magnitude also overestimates the loss. Fix is a one-sentence rewrite: `s/harness tail/drops the first ~10 s of harness load/` and drop/rework the parenthetical. Not a blocker for Plan 5 codec work.

### Divergent Views
- **Severity + overall verdict diverge on the L179-181 finding.** Three reviewers (claude, gemini, codex) PASS / declare convergence reached, treating the issue as a NIT or not flagging it at all (gemini read L161-169 as a clean tradeoff resolution; codex found nothing). opencode rates it MEDIUM and returns **FLAG**, arguing it's a *newly-introduced* factual error from R5's rewrite — neither known-backlog nor known-addressed — and that the tradeoff math was never independently recomputed in any of 6 rounds. Worth resolving: the disagreement is about whether direction-of-gap (tail vs front) wrongness rises above NIT, not about the underlying arithmetic, which claude and opencode independently agree on. Cheap to fix regardless — recommend correcting the one sentence before closing the loop.
- **gemini's read of the same passage as fully clean** contrasts with claude/opencode both independently identifying the tail-vs-front inconsistency, suggesting gemini did not recompute the timing math. Lean toward the claude/opencode reading.
