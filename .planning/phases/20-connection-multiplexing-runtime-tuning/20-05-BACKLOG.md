# Plan 20-05 multi-review backlog / do-not-reflag

## Convergence reached at R5 (commit-to-be)

Round-by-round NEW HIGH+ trajectory: R1=4, R2=2, R3=5 (1 BLOCKER), R4=2, **R5=0**. Convergence target met.

## Findings dropped with reason

- **[R1 gemini MEDIUM] `@Disabled` leading-space** — DROPPED (gemini misread).
- **[R3 gemini NIT] kill -KILL order** — DROPPED (KILL is unconditional; opencode rebuttal).
- **[R4 claude LOW] two-guard cosmetic combine (sentinel + empty-OPTS)** — DROPPED (intentional split for distinct error modes).
- **[R4 claude NIT] varbase64 bounds in Base64Codec** — DROPPED (Pass-3 follow-up disposition, already-known).
- **[R5 gemini BLOCKER] `@Disabledb` literal regex** — DROPPED. `cat -A` byte-level verification at L332-337 shows actual content is `@Disabled\b` (backslash + b, interpreted by grep -E as word boundary). Claude AND opencode independently verified the same. Single-reviewer BLOCKER on mechanical regex claim that conflicts with two cross-AI verifications and direct file bytes — disposition: gemini misread.
- **[R5 opencode NIT] TD-22-A..C vs A..D retry-protocol range** — DROPPED. Cosmetic; the generic "unrelated to the codec opt" retry clause already covers Hundred regardless of TD-22-D label scope.

## Findings deferred to backlog (real, low value, future polish)

- **[R2 claude LOW] detach.timeout polled 6×** — observability symmetry.
- **[R3 backlog] JettyDeflateCustomizer.java:69-73 line range slightly drift-prone** — actual L60-73; content-anchor more resilient. 4 sites. Defer to Plan 6.

## Round fix indexes (do not re-raise)

- **R1** `90ca0f7` → `20-05-MULTIREVIEW-R1.md`
- **R2** `d2efeb5` → `20-05-MULTIREVIEW-R2.md`
- **R3** `26eabf8` → `20-05-MULTIREVIEW-R3.md`
- **R4** `9f5af1a` → `20-05-MULTIREVIEW-R4.md`
- **R5** (this commit) → `20-05-MULTIREVIEW-R5.md`

## R5 fixes (LOW polish — convergence already established before applying)

| # | R5 source | Severity | Fix |
|---|-----------|----------|-----|
| L1 | claude LOW | LOW | Output block adds a Plan-5-side SUMMARY first-line fail-fast guard mirroring the Task 5.0 TRIAGE pattern + Pass-3 Concern #29 contract. Catches violations BEFORE Plan 6 Task 6.1's tiered-line-count grep falls through |
| L2 | claude LOW | LOW | TRIAGE fail-fast guard `head -n 5` → `head -n 20` + clearer FATAL message showing the actual first-non-empty line found + the expected prefixes — tolerates a YAML frontmatter or short markdown preamble |

## Codex failure pattern (R1+R2+R3+R4+R5)

5-for-5 fast-fails at ~3-4s rc=1. Consistent per-session capacity/auth flake. 3/4 reviewer coverage maintained every round (claude/gemini/opencode produced full reviews each round).

## Convergence judgement

R5 surfaces no new findings above the LOW polish threshold. The single gemini BLOCKER was a regex misread cross-verified against actual file bytes by both claude and opencode. **Plan 20-05 is executable as-written**, modulo the LOW-tier polish items applied in R5.

---

# Post-execution review loop (EXEC-R1+)

## EXEC-R1 (`20-05-MULTIREVIEW-EXEC-R1.md`) — 0 HIGH+, convergence on first round

3/4 reviewers (codex 6-for-6 fast-fail — root-caused post-review: NOT a capacity flake; the loop's `--model codex=gpt-5` pin is rejected with `400 invalid_request_error: gpt-5 not supported with ChatGPT account` by codex CLI v0.131.0; use `gpt-5.5` or default in future rounds. gemini thin "flawless" — 124 output tokens, weak signal). All numeric claims independently recomputed by claude + opencode: baseline σ=15.74, tuned mean 45.0, |−4.5| < ±15.74 floor — math confirmed sound.

### Fixed (this commit)

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| M1 | MEDIUM | "sub-threshold" GC framing (claude: contradicts D-21 ≤1ms floor heuristic at 22.7ms mean; opencode: tuned data in baseline-headed §4.4 column) | 3 sites reworded — criterion now explicit: 90.8 ms ≈ 0.05% wall-clock vs >2% GC-pause ZGC trigger; tuned/baseline window provenance stated; "no GC delta claim" explicit |
| L1 | LOW | RUNTIME §4.2 VirtualThreadPinned 1000 cells `_Pending_` despite confirmed 0 in both JFRs | filled: 0/min (0 events, 90 s / 180 s) |
| L2 | LOW | ROADMAP 20-05 entry stale "(or forced-fallback runtime knob tightening per B2)" — B2 framing replaced by D-21 pre-execution | reworded to D-21 four-outcome tree + resolved outcome 3 |
| L3 | LOW | ROADMAP "7/8 plans executed" vs STATE "6 of 7" denominator convention | ROADMAP line annotated (checkbox count incl. superseded 20-01b vs active-plan count); self-erases at 20-06 completion |
| N1 | NIT | SUMMARY Task 5.2 "see final commit" | cited `328ff7a` + `f796b55` |
| N2 | NIT | tuned meta.json thinner than baseline sidecar | backfilled `jvm_flags`, `cap_during_run`, `spawn_seed` from capture-script truth |
| N3 | NIT | TRIAGE GC floor row vacuous at n=0 | reworded: vacuous-pass marked, tuned-window contrast cross-referenced |

### Dropped with reason (do not re-flag)

- **[EXEC-R1 claude LOW] STATE.md progress bar 34/35 unchanged across Plan-5 diff** — DROPPED. End-state is correct: 34 done / 35 total project-wide, 20-06 sole pending; verified at tracking-commit time. Diff-shape (which commit bumped the numerator) is not a correctness property of the current state.

### Convergence judgement

EXEC-R1 = 0 NEW HIGH+. **Convergence threshold met on first post-execution round.** Fix-round (this commit) is doc-wording + sidecar-field polish only — no numeric, contract, or evidence change.
