---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T15:28:08Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md", "README.md"]
models:
  claude: "claude-opus-4-8"
usage:
  claude: { input: 806335, output: 73, cached: 6312855, tool_calls: 12, elapsed_s: 282.6 }
  gemini: { input: 225203, output: 625, cached: 12263, tool_calls: 0, elapsed_s: 71.2 }
  codex: { input: 912805, output: 7877, cached: 776704, tool_calls: 24, elapsed_s: 168.4 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 16, elapsed_s: 355.8 }
synthesizer: claude
synthesized_at: 2026-06-04T15:28:08Z
fallbacks:
  gemini:
    attempts: ["gemini-3.1-pro-preview", "gemini-3-flash-preview"]
    used: "gemini-3-flash-preview"
---

# Cross-AI Review

## Claude Review

Verification complete. Everything load-bearing checks against disk. One new LOW.

# Review: Plan 20-06 EXECUTION — Round 3

## (a) R2 fix verification — PASS

Both R2 fixes correct, no new errors introduced.

**SUMMARY GC bullet (R2 HIGH fix):** Correct. `20-06-SUMMARY.md` now reads churn `62c1b44` 10/57.2ms/0.03% @100, 18/283.7ms/0.14% @500, 18/213.0ms/0.11% @1000; active `103a615` 0 all tiers; tuned `424e06d` 4/90.8ms/0.05%. Matches RUNTIME exactly. `GCPausePause` typo gone from SUMMARY/RUNTIME — survives only inside the R2 review record (quoting the old bug), which is correct.

**profiles/README 200s ritual (R2 fix):** Correct. JFR `duration=200s`, harness `--duration 200`, `--harness-id baseline-62c1b44-t1000`, size note "180–200s". Matches meta `duration_s: 200`.

**"5× food" → "50×" (R2):** No `5× food` remains. Fixed.

**Active flamegraph "16–153 KB" (R2):** Disk min=15452 B (`ls -lh`→16K), max=156507 B (→153K). Matches the ls-lh sourcing method. Fine.

## Disk-verified facts (all correct)

| Claim | Disk | Match |
|---|---|---|
| GC counts 10/18/18 churn, 0/0/0 active, 4 tuned | `jfr print` → 10/18/18, 0/0/0, 4 | ✓ |
| GC sums 57.2/283.7/213.0/90.8 ms | 57.2/283.7/213.0/90.8 | ✓ |
| GC max 11.8 (100), 41.9 (500) | 11.8 / 41.9 | ✓ |
| §6 sizes 2.3/3.9/4.5M, active 0.35/0.57/0.78M, tuned 3.5M, 80/29/19KB | exact | ✓ |
| Dates 2026-05-20 baseline, 2026-06-04 tuned, active 2026-05-25 (from sidecar `sample_utc`) | exact; active meta has no `captured_utc` | ✓ |
| tuned "180 s window" | meta `jfr_duration_s: 180` (200 is harness `--duration`) | ✓ |
| c22e487 = 12 files | 12 | ✓ |
| RUNTIME 558 lines; "548 at execution" provenance | 558 | ✓ |
| D-02 four-file grep = 4 | 4 | ✓ |
| VALIDATION 22 rows, 20-06-6.5 present | 22, present | ✓ |
| `AdmissionMetrics.java:70/:79`, `application.yml:15` | M_TICK_WORK_MS=70, M_DETACH_TIMEOUT=79, include line 15 | ✓ |
| zero-pending audit; footer `2026-06-05` (no XX) | clean | ✓ |

## (b) NEW finding

### LOW — README.md §Build & run, line 18 — `runBot` mischaracterized as "Single-bot"

**What's wrong:** `./gradlew runBot # Single-bot operator CLI (<=100 bots)`. Self-contradictory — "Single-bot" vs "≤100 bots" in one line. `BotRunner.java:12` = "Single-**process** operator CLI for launching bot **clients**"; `:22` "Hard cap: 100 bots per invocation"; `:50` "count — 1 <= count <= 100". It launches *up to 100* bots from one process, not one bot. CLAUDE.md architecture table agrees ("100-bot cap"). Pre-existing from the Task 6.3 template; both R1/R2 (GC-focused) missed it.

**Fix:** `# Single-process operator CLI (1–100 bots)` or `# Operator CLI — launches up to 100 bots`.

**Evidence:** `BotRunner.java:12,22,50`; CLAUDE.md §Architecture line 56.

(README package-layout list — all 10 packages `world…bot` — verified present on disk. Accurate.)

## Convergence judgement

**No new HIGH+ — convergence reached.** The R1 GC re-attribution (the round's priority risk) is correct to the event against `jfr print` on all seven JFRs — counts, sums, and maxes. R2's SUMMARY-propagation and 200s-ritual fixes are both correct and clean. The only new item is one LOW README wording inaccuracy (`Single-bot` → up-to-100-bots), non-load-bearing. Recommend fixing the LOW opportunistically and shipping to `/gsd-verify-work 20`.

---

## Gemini Review

I have completed the pre-publication QA (Round 3) of the executed output for Phase 20 Plan 20-06.

### Review Summary

The execution of Plan 20-06 has successfully finalised Phase 20's documentation and architectural codification. All **Round 2 fixes** have been applied correctly and consistently across the artifact set. The **GC-pause re-attribution** (the priority risk from R1) is now mathematically and narratively consistent across all seven relevant passages in `20-RUNTIME.md` and has been correctly propagated to `20-06-SUMMARY.md`.

The **D-02 three-place rationale codification** is complete and accurate across `README.md`, `CLAUDE.md`, and the two Java source files. `20-VALIDATION.md` has been correctly updated with a comprehensive 22-row verification map, and its disabled-test count aligns with the live inventory (6 annotations across 5 files). All stale references to the `c22e487` baseline have been annotated as history-only, with the canonical anchor correctly set to `62c1b44`.

### Verification of R2 Fixes

- **SUMMARY GC propagation:** `20-06-SUMMARY.md` now correctly reports the churn-baseline pause events (10/18/18) and the active/tuned numbers, matching `20-RUNTIME.md` exactly.
- **50× food multiplier:** Verified consistent at §4.3.2 and throughout the spec doc.
- **profiles/README.md Ritual:** Baseline durations are now 200s, and the harness-id carries the tier suffix (`-t1000`), matching the meta-sidecar evidence.
- **Line Counts:** `20-06-SUMMARY.md` correctly accounts for the EXEC-R1 expansion (558 lines).
- **20-CONTEXT.md:** All residual Plan-1b references are now correctly annotated as superseded by Plan 1c.

### Findings

No new `BLOCKER`, `HIGH`, or `MEDIUM` issues found.

#### LOW / NIT
- **NIT** — `20-RUNTIME.md` §6: The flamegraph size range (16–153 KB) is now accurate to the byte.
- **NIT** — `20-RUNTIME.md` footer: completion date `2026-06-05` correctly replaces the `XX` placeholder.

### Convergence Judgement

**No new HIGH+ — convergence reached.** The documentation is authoritative, internally consistent, and fulfills the Phase 20 contract. The phase is ready for final verification.

```bash
/gsd-verify-work 20
```

---
*QA Review · v3 · 2026-06-05*

---

## Codex Review

**Findings**

- **HIGH** — [profiles/README.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:73) baseline ritual tells operators to use `../capture-active.sh` for the canonical `62c1b44` churn-baseline metric sidecar, but [capture-active.sh](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/capture-active.sh:2) is explicitly the active-50xfood script. It hard-codes `TAG=active-50xfood`, `FOOD=0.05`, `HARNESS_DUR=130`, active filenames, and starts the server with the food flag at lines 8-9, 18-20, 28-30, and 37-39. That contradicts the README’s “Run the baseline ... verbatim” claim and would mint `metrics-{N}bots-active-50xfood-62c1b44.json`, not the canonical `metrics-{N}bots-baseline-62c1b44.json` sidecars.
  **Fix:** replace this step with a baseline-safe sampling loop or point to a parameterized baseline capture script that preserves scenario=`baseline`/`{N}bots`, no food flag, 200s harness shape, and `metrics-{N}bots-baseline-62c1b44.json` filenames. Keep `capture-active.sh` only for active-scenario reruns.

- **MEDIUM** — [20-CONTEXT.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md:41), [20-CONTEXT.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md:59), and [20-CONTEXT.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md:162) still say “4 P22 `@Disabled`” / TD-22-A..D and treat `HundredBotIntegrationTest` as held. This contradicts [20-VALIDATION.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:24) and the live grep: 6 annotations across 5 files, with TD-22-D re-enabled.
  **Fix:** annotate or update the D-12/context wording to the same live inventory now used in 20-VALIDATION.

**Verified Clean**

The R2 fixes themselves hold: [20-06-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md:74) now has the corrected GC counts, no `GCPausePause` residue remains in final docs, [20-RUNTIME.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:441) says `50× food`, and profiles/README uses 200s for the baseline JFR/harness duration. Local `jfr print` counts confirmed churn baselines 10/18/18 and tuned 4.

D-02 codification is complete across README, CLAUDE.md, and both Java comments. 20-VALIDATION frontmatter and 22-row task map are present.

**Convergence Judgement**

NEW HIGH+: one HIGH remains: the `profiles/README.md` baseline metric-sidecar ritual points at the active-50xfood capture script. Convergence not reached.

---

## Opencode Review

I'll systematically verify the R2 fixes and sweep for new issues across all provided artifacts.Now let me do a systematic sweep for missed issues and consistency checks across all artifacts.All R2 fixes verified. Now let me check for any propagation issues the rounds missed.All stale forward-refs purged from deliverable files. Let me now do the final consistency check between the SUMMARY and RUNTIME on GC numbers.## Review: Plan 20-06 EXECUTION (post-merge) — ROUND 3

### (a) R2 fix verification — ALL PASS

| Item | Expected | Actual | Verdict |
|------|----------|--------|---------|
| SUMMARY GC bullet | 10/57.2ms @100, 18/283.7ms @500, 18/213.0ms @1000 churn, 0 active, 4/90.8ms tuned | Exact match (line 74) | ✓ |
| §4.3.2 "5×→50× food" | `50× food` | `The 50× food active-scenario contrast JFR` (line 441) | ✓ |
| §6 flamegraph range | `16–153 KB` | `16–153 KB each` (line 534) | ✓ |
| Line counts 548→558 | `558` in all 4 locations | 70, 140, 179, 196 all say 558 | ✓ |
| profiles/README ritual 180s→200s | `duration=200s`, `--duration 200`, `--harness-id baseline-62c1b44-t1000` | All 3 present (lines 53, 63, 64) | ✓ |
| 20-CONTEXT Plan 1b→1c | `Plan 1c (originally Plan 1b, superseded)` at L318/L357 | Both annotated | ✓ |

**Zero regressions.** `grep -iE "GCPausePause"` returns 0 hits in deliverable files (only in R2 exec-review documenting the finding). `grep "5× food"` hits 0 in deliverable files. `grep "Plan 5/6"` hits 0 in 20-RUNTIME.md and 20-06-SUMMARY.md.

### (b) NEW findings — both rounds missed

#### LOW — SUMMARY `key-decisions` line 49 conflates unrelated domains

```
"0 jdk.VirtualThreadPinned events at all three tiers — G1 confirmed, Phase 999.6 remains backlog"
```

Three independently-true facts are chained by em-dashes in a way that reads as causal: G1 confirming the pinning result, and pinning relating to backlog 999.6. G1 is a GC (irrelevant to VT pinning), and Phase 999.6 is about `synchronized → ReentrantLock` conversion (irrelevant to G1). No fact is false, but a reader would be confused by the implied relationship. **Fix:** split into separate bullets or reword: `"0 jdk.VirtualThreadPinned events at all three tiers; G1 confirmed for GC; Phase 999.6 (ReentrantLock conversion) remains backlog."`

#### LOW — 20-RUNTIME.md §3.3 "default JVM setting" for explicitly-set parallelism

> Line 348: "shows no carrier-thread saturation at the default JVM setting"

`parallelism=8` was explicitly set via `-Djdk.virtualThreadScheduler.parallelism=8` in the capture command line (per meta.json). The JVM default is `Runtime.availableProcessors()`, not 8. A reader might assume the default was good enough without realizing the knob was explicitly tuned. **Fix:** `s/default JVM setting/explicitly-set parallelism=8/` or `s/default JVM setting/captured setting of parallelism=8/`.

### (c) Verified clean — no issues

- **GC number consistency:** All 7 passages (§3.1/3.2/3.3/4.3.1/4.3.2/4.3.3/4.4) agree. Math verified: 57.2ms/200s=0.0286%≈0.03%, 283.7/200=0.14%, 213.0/200=0.11%, 90.8/180=0.05%.
- **D-02 four-place codification:** `grep -lE "WS:entity 1:1"` = 4 files. ✓
- **D-21 outcome enumeration in §4.3.3:** "No codec opts, no knob tightening, no pinning handoff was justified" names outcomes 1/2/4 by negation; outcome 3 is the explicit heading. All four covered.
- **c22e487 history group:** 12 files indexed (3 JFR + 3 meta + 3 flamegraphs + 3 metrics JSON). ✓
- **CLAUDE.md insertion:** After Connection model, before `GSD:architecture-end` marker. Line refs `:70`/`:79` and `application.yml:15` verified correct.
- **profiles/README.md tuned pattern:** Scenario-aware `jfr-1000bots-active-50xfood-tuned-424e06d.jfr`. ✓
- **Zero-pending audit:** passes. `_baseline-only — see Phase 21_` cells are intentional Phase-21 hand-off, not stubs.

### Convergence judgement

**No new HIGH or MEDIUM — convergence reached.** Two LOW findings: one grammar conflation in SUMMARY key-decisions frontmatter, one imprecise "default JVM setting" wording in 20-RUNTIME.md §3.3. Neither is a factual error, broken cross-reference, or contract violation. Both are reader-clarity polish items well below the shipping threshold for this finalization deliverable.

---

## Consensus Summary

### Agreed Strengths
- **R2 fixes applied correctly, zero regressions** (all 4 reviewers). SUMMARY GC propagation (10/57.2ms·18/283.7ms·18/213.0ms churn, 0 active, 4/90.8ms tuned) matches RUNTIME exactly; `5×`→`50× food` fixed at §4.3.2; flamegraph range `16–153 KB` accurate to the byte; line counts 548→558 corrected in all 4 places.
- **GC-pause re-attribution (R1 priority risk) verified against `jfr print`** (claude, gemini, codex, opencode). Counts 10/18/18 churn baselines + 4 tuned confirmed on disk; sums and maxes consistent across all RUNTIME passages; `GCPausePause` typo gone from all deliverables (survives only in review records that quote it).
- **D-02 three-place/four-file codification complete** (all 4). `WS:entity 1:1` present across README, CLAUDE.md, and both Java sources; grep = 4 files.
- **20-VALIDATION.md 22-row task map present; disabled-test count aligns to live inventory** (6 `@Disabled` across 5 files) (claude, gemini, codex, opencode).
- **`c22e487` correctly demoted to history-only (12 files), canonical anchor `62c1b44`** (claude, gemini, opencode). Footer date `2026-06-05` replaces `XX` placeholder; zero-pending audit clean.

### Agreed Concerns
- None raised by 2+ reviewers. All new findings this round are singletons (see Divergent Views).

### Divergent Views
- **profiles/README.md baseline ritual — HIGH (codex) vs. clean (claude/gemini/opencode).** Three reviewers verified the 200s duration / `-t1000` harness-id fix as correct and stopped there. Codex went a level deeper: the baseline-capture step points operators at `capture-active.sh`, which hard-codes `TAG=active-50xfood`, `FOOD=0.05`, `HARNESS_DUR=130` and starts the server with the food flag — so following it verbatim mints `metrics-{N}bots-active-50xfood-62c1b44.json`, not the canonical `metrics-{N}bots-baseline-62c1b44.json` sidecars. **Worth investigating before ship** — if codex is right, the "run the baseline verbatim" instruction is unrunnable as written. Decides convergence: 3/4 say reached, codex says not.
- **20-CONTEXT.md stale disabled-test inventory — MEDIUM (codex only).** §41/§59/§162 still say "4 P22 `@Disabled`" / TD-22-A..D and treat `HundredBotIntegrationTest` as held, contradicting the live 6-annotation inventory now in 20-VALIDATION. Other three reviewers checked CONTEXT only for the Plan-1b→1c annotation (which all confirmed clean) and did not cross-check the disabled-test count there.
- **Two unrelated LOW wording items, one reviewer each:** README §Build & run `runBot` labeled "Single-bot" but launches up to 100 (claude); SUMMARY key-decisions line conflates G1/VT-pinning/Phase-999.6 by em-dash chaining, and §3.3 says "default JVM setting" for an explicitly-set `parallelism=8` (opencode). All non-load-bearing polish.

**Net:** Verify codex's two findings (HIGH README ritual script, MEDIUM CONTEXT inventory) against disk. If both hold, fix before `/gsd-verify-work 20`; the HIGH is a runnability defect, not cosmetics. Other LOWs fix opportunistically.
