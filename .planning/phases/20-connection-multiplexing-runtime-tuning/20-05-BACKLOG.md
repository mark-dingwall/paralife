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
