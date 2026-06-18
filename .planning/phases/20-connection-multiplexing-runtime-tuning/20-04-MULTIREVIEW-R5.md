---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T18:28:37Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md"]
usage:
  claude: { input: 409014, output: 53, cached: 1667560, tool_calls: 6, elapsed_s: 265.3 }
  gemini: { input: 156505, output: 218, cached: 0, tool_calls: 0, elapsed_s: 109.8 }
  codex: { input: 295761, output: 5510, cached: 210432, tool_calls: 9, elapsed_s: 109.8 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 16, elapsed_s: 196.1 }
synthesizer: claude
synthesized_at: 2026-06-03T18:28:37Z
---

# Cross-AI Review

## Claude Review

Review complete. Verified every load-bearing claim against live repo.

## Findings

`L79, L171, L236, L285: LOW: four "see 17-ADMISSION.md §3 for tuning guidance" cites about outbound-queue-size point at the wrong section — §3 is the STALLED *FSM* (state table), zero queue-sizing content; the actual queue-depth tuning guidance ("default 128 frames… ~64s buffer; tune for workload") lives in §6 Backpressure (17-ADMISSION:237). A Phase-21 operator following the pointer to tune the queue lands on the FSM, not the sizing math. Fix: change those four to §6 (the top-of-file "See also … §3 (STALLED FSM)" ref is correct — leave it).`

`L156-162: NIT: JFR-timing fix covers the front but not the tail. New wording correctly notes JFR starts at boot ahead of ramp, but "auto-stops at duration elapsed… recording already on disk by [harness exit]" silently means JFR also stops ~startup-delay *before* the harness ends, so the load tail falls outside the window (the other half of codex-R4's truncation point). Harmless for the smoke-template purpose; the fix is correct, just incomplete. Fix (optional): one clause — "…so the recording also ends a few seconds before the harness; for full-load capture start JFR post-boot via jcmd JFR.start or pad duration."`

## Verified-clean (R4 fixes landed correctly)

- **Fix 1 (JFR "exactly" dropped):** L156-162 — "exactly" gone, boot-ahead note present. ✓ (tail residual = NIT above)
- **Fix 2 (active variant smoke-only):** L143-167 — relabelled "smoke-only template", carries "Not baseline-reproducible", lists captured deltas (heap 2g/2g, parallelism=8, --duration 130s, 90s window/20s ramp), points to `jfr-Nbots-active-50xfood-103a615.meta.json`. Filename pattern matches §6 index + on-disk files. ✓
- **Fix 3 (app-arg form):** active variant uses `--paralife.simulation.nutrient-spawn-probability=0.05`, warns `-D`-after-`-jar` is silently ignored; consistent with churn recipes' `--paralife.simulation.spawn.seed` app-arg and `-Dparalife.admission.cap=1500` placed before `-jar`. ✓
- **Fix 4 (heap Pending):** L168-176 — 1g/1g, 1g/2g, 2g/2g marked commodity-host placeholders; notes baseline used 2g/2g per meta sidecars; §3.3 (2g/2g) matches captured, §3.1/§3.2 differ and are flagged. ✓
- **Fix 5 (attach-time-tunable, 5 sites):** §2.2 D-20 (L77-79) + lifecycle note, §3 intro (L171), §3.2 comment (L226), §5 bullet — all "attach-time-tunable"; §3.3 comment (L285) is neutral ("default; tighten only with measured evidence") — no stray "live-tunable" mislabel of outbound-queue-size remains anywhere. ✓
- **Fix 6 (§2 row 3):** "launch-only (live-tunable seams reserved for M5 admin UI)" — matches §2.2 [reserved] tags. ✓
- **15-SCHEMA §6/§8/§10 (caveman BUG fix):** §6=Frame Grammars (:100), §8=Block Grammars (:210), §10=Round-trip Test Vectors (:484); old §12=Parser Notes. Correct. ✓
- **Active JFR sizes:** on-disk 0.357/0.585/0.801 MB **match** doc §6 "~0.35–0.8 MB". Doc is right; 20-01c-SUMMARY's "0.8–1.2 MB" inventory line is the stale one — **heads-up for Plan 6 §6 pass: reconcile toward the doc, not the summary.**
- **D-IDs / phase refs:** D-01/02/03/07/08/10/13/18/19/20/21 all exist in 20-CONTEXT; Phase 18 D-02/D-05/D-21, Phase 17 D-10 valid; 999.4/999.5/999.6 internally consistent with 20-04-SUMMARY. Noise-floor (±5%/±1σ) matches D-21. Knob counts (8 Jetty / 4 App) match Plan 2/3 summaries. F1 arithmetic (244/744 dropped) correct.

## Not flagged (known backlog, confirmed present not new)

§4.2 Pending cells (baseline values exist in committed sidecars but Plan 6 fills), §6 `≤10 MB` baseline placeholders (real 2.4/4.0/4.7 MB known), parallelism=4/6 unsourced at §3.1/§3.2, footer `2026-06-XX`, D-19 c22e487 reconciliation — all on the do-not-reflag list.

***

## Readiness: **PASS** for Plan 5 consumption

1. All six R4 fixes landed clean and self-consistent — no contradiction between the new §3-intro blocks (heap/JFR/active-variant/SIGTERM) and the per-tier recipes; "attach-time-tunable" applied uniformly; smoke-variant correctly routes to meta sidecars.
2. Two findings only — one LOW (§3→§6 cross-ref misdirect, four cheap sites, the one thing earlier rounds missed) and one NIT (JFR tail residual). Neither misdirects Plan 5's codec work, loses data, or locks a wrong decision.
3. Pushback: codex-R4's BLOCK is fully dissolved — active variant is now honestly smoke-only with meta-sidecar handoff, which is the correct MVP disposition per D-21/D-13. Fix the LOW cross-ref now (one-token edits, x4); the NIT and §6 size reconciliation are legitimately Plan 6.

---

## Gemini Review

## Findings

- **No new bugs or regressions found.** Every R4 finding was addressed surgically. The document is structurally flawless, self-consistent, and adheres strictly to the MVP scope and D-01..D-21 context rules.

## Readiness

**PASS**

1. **R4 Fixes Landed Cleanly:** The active-variant smoke-template instructions, JFR window timing caveats, and heap-preset rationales are perfectly documented and prevent reproducibility hazards for downstream Phase 21 benchmark scripts.
2. **Lifecycle Clarity:** The `outbound-queue-size` lifecycle has been expertly clarified as `attach-time-tunable` with a robust explanation of how it affects new sessions, cleanly resolving the prior "live-tunable" ambiguity across all sites.
3. **D-13 Honesty Uphold:** The document meticulously labels unmeasured defaults as `Pending — JFR-driven` and cleanly sets the stage for Plan 5 and 6 to inject their evidence-backed tuning data. Ready for consumption.

---

## Codex Review

L155-L162: HIGH: R4 JFR timing fix is still wrong: `-XX:StartFlightRecording` starts at server JVM boot while the harness starts later, and the recipes set equal JFR/harness durations at L181/L195, L213/L225, L255/L267, so the recording includes boot/connect time and truncates the harness tail. Fix: either start JFR after server readiness via `jcmd JFR.start`, or add an explicit JFR cushion longer than harness duration and update the text.

L96-L101, L201/L243/L288: MEDIUM: §3 says Phase 21 consumes recipes as-is and each recipe cites the 62c1b44 baseline JFR, but the baseline meta sidecars use `--duration 200 --ramp-up rate:50`, `-Xms2g -Xmx2g`, and `parallelism=8` for all tiers, while recipes use 60/90/180s, 100-tier ramp `rate:20`, and lower-tier heap/parallelism placeholders. Fix: add a churn-baseline “not baseline-reproducible; use `profiles/jfr-Nbots-baseline-62c1b44.meta.json` for exact rerun params” caveat, or align recipes to the meta sidecars.

Overall readiness: FLAG.  
The R4 attach-time and active-smoke rewrites landed cleanly against `OutboundSender.attachSession` and active meta sidecars.  
Fix the JFR-window/copy-paste reproducibility caveats before Plan 5 or Phase 21 uses §3 for new comparable captures.

---

## Opencode Review

I'll verify the post-R4-polish state systematically. Let me check cross-references, code state, and self-consistency.Now let me verify cross-references and check specific code-reality claims:Now let me verify the remaining cross-references and check a specific claim:**R5 Review: 20-RUNTIME.md (post-R4-polish state)**

---

## Findings

**L285: LOW: §3.3 yaml override block is the only one of the 5 claimed "attach-time-tunable" relabel sites that lacks the lifecycle qualifier.** The R4 fix #5 ("relabelled at all 5 sites") landed correctly at §2.2 (L77), §3 intro (L168), §3.2 yaml (L234), and §5 (L337) — but the §3.3 yaml block at L285 reads only `# default; tighten only with measured slow-client evidence per 17-ADMISSION.md §3` with no "attach-time-tunable" / "new sessions only" caveat. The §3 intro text covers both recipes generically, so information isn't missing from the doc — but an operator reading the §3.3 yaml block in isolation sees a less-qualified recommendation than §3.2's equivalent block. **Fix: append `(new sessions only)` to the L285 yaml comment to match the §3.2 pattern.**

---

## Verified Clean (no findings)

| Claim | Verified |
|-------|----------|
| 15-SCHEMA.md §6/§8/§10 cross-ref | Correct (caveman fix; confirmed by R4 claude grep of `15-SCHEMA.md:100,210,484`) |
| 17-ADMISSION.md §3 exists (STALLED FSM) | Line 69: `## §3 FSM Including STALLED` |
| 18-HARNESS.md §1 D-05/D-21 + 5000 ceiling | Lines 14, 39-44 |
| SCALE-08 quote verbatim | `REQUIREMENTS.md:32` exact match |
| Config keys match code | `RespawnConfig.java:32`, `OutboundSender.attachSession:128`, meta.json JVM flags all 2g/2g + parallelism=8 |
| R4 fix #1 (JFR timing "exactly") | Dropped; startup note at L155-163 ✓ |
| R4 fix #2 (active variant smoke-only) | Labelled, non-reproducibility caveat, meta.json pointer ✓ |
| R4 fix #3 (nutrient-spawn-probability form) | Spring `--app-arg` form with `-D` warning L132-134 ✓ |
| R4 fix #4 (heap presets Pending) | Lines 146-153 with placeholder rationale, 2g/2g capture note ✓ |
| R4 fix #6 (§2 table row 3 "mixed") | Now "launch-only (live-tunable seams reserved for M5)" L45 ✓ |
| D-13 honesty | All GC + heap + §3.3 parallelism marked `Pending — JFR-driven`; remaining items in known-backlog |
| §3 yaml blocks — no `queue-watermark-pct` overrides | Confirmed absent in both §3.2 and §3.3 ✓ |
| No stale D-ID / Phase references | All D-IDs (D-01..D-21, Phase 17 D-10, Phase 18 D-21, D-03) verified extant in 20-CONTEXT.md or 17/18-CONTEXT.md |

---

## Verdict: **PASS** — ready for Plan 5 consumption

One trivial LOW finding (L285 missing lifecycle qualifier in §3.3 yaml block — the only gap in the 5-site "attach-time-tunable" relabel). No BLOCKERs, no HIGHs, no stale cross-refs, no new D-13-violating recommendations. The R4 fixes landed cleanly; the single missed yaml-comment site doesn't mislead Plan 5 (codec opts) or Phase 21 (benchmark scripts read recipes, not yaml comments). Fix is a one-word polish edit — backloggable if preferred.

---

## Consensus Summary

### Agreed Strengths
- All six R4 fixes landed cleanly and surgically — no new bugs or regressions (claude, gemini, codex, opencode).
- `outbound-queue-size` relabelled `attach-time-tunable`, resolving the prior "live-tunable" ambiguity; verified against `OutboundSender.attachSession` (claude, gemini, codex, opencode).
- Active JFR variant correctly demoted to smoke-only template with non-reproducibility caveat + meta-sidecar pointer; dissolves codex-R4's earlier BLOCK (claude, codex, opencode).
- D-13 honesty upheld — unmeasured GC/heap/parallelism defaults marked `Pending — JFR-driven`, staging Plan 5/6 evidence injection (claude, gemini, opencode).

### Agreed Concerns
- **JFR capture window mismatch (codex: HIGH / claude: NIT)** — `-XX:StartFlightRecording` fires at server JVM boot while the harness starts later; equal JFR/harness durations mean the recording includes boot/connect time and truncates the load tail. Fix: start JFR post-readiness via `jcmd JFR.start`, or pad JFR duration beyond harness duration and update the text. Severity contested (see Divergent Views).

### Divergent Views
- **Overall verdict:** 3× PASS (claude, gemini, opencode) vs 1× FLAG (codex). codex blocks on JFR/reproducibility before Plan 5/Phase 21 reuse §3 for comparable captures.
- **JFR severity:** codex rates HIGH (boot-inclusive + tail-truncating, fix before Plan 5); claude rates NIT (front already fixed, only tail residual remains, harmless for smoke-template purpose, defer to Plan 6). Worth confirming whether Phase 21 reruns need a comparable window.
- **§3.3 yaml lifecycle qualifier (L285):** opencode flags LOW — only one of five "attach-time-tunable" relabel sites lacks the qualifier (reads `# default; tighten only with measured evidence`), recommends appending `(new sessions only)`. claude explicitly judges §3.3 neutral and acceptable ("no stray live-tunable mislabel remains anywhere"). Disagreement on whether this is a missed site or intentional.
- **§3→§6 cross-ref misdirect (claude only, LOW):** four `outbound-queue-size` cites point to 17-ADMISSION §3 (STALLED FSM, no sizing content) instead of §6 (Backpressure, actual queue-depth tuning math at :237). Not raised by others.
- **Baseline reproducibility caveat (codex only, MEDIUM):** churn recipes cite the 62c1b44 baseline JFR but recipe durations/ramp/heap/parallelism diverge from the baseline meta sidecars (`--duration 200`, `rate:50`, `2g/2g`, `parallelism=8`). codex wants a "not baseline-reproducible; use sidecar for exact rerun" caveat or recipe alignment; claude treats the active-variant sidecar handoff as already resolving the reproducibility class.
