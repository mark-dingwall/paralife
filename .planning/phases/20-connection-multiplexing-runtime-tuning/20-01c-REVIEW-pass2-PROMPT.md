# 20-01c pass-2 multi-review prompt

## What you are reviewing

This is **pass 2** of multi-review for Phase 20 Plan 01c (paralife project). Pass 1
(`20-01c-REVIEW-inline.md` + `20-01c-REVIEW-reference.md`) surfaced findings against
an earlier baseline at HEAD `1818eeb`. Those findings were triaged into a remediation
plan (`vast-inventing-simon.md`, supplied as context) and implemented as four code/data
changes (D1–D4) plus a full re-capture at HEAD `0824f1a` and a SUMMARY rewrite.

The remediation lives on branch `worktree-phase-20-01c-baseline-rebuild` at HEAD
`62f2ffa` (PR #1 against `main`, but **do not fetch the PR** — everything you need
is in this worktree). Your job is to review **the remediation itself** — not to
re-litigate pass-1 findings (those are settled by either implementation or
documented pushback).

**Locality.** All review inputs are local files. Do NOT call `gh`, `WebFetch`, or
any network tool. The diff is reproducible offline via
`git diff main..62f2ffa -- <path>`; the 11 commits are in `git log main..62f2ffa`.

## What to focus on

1. **D1 — `WorldWebSocketHandler.markDead` decrements `active.entities` bucket**
   - Compare against `cleanupBot` (same file, ~lines 917–935) which the plan claims is
     the canonical pattern.
   - Concurrency: `markDead` and `cleanupBot` can both fire on the same session under
     adverse interleavings (death-then-close). Does the new code over-release? The
     plan deliberately omits `releaseSlot()` because slots are per-session, not
     per-entity-life — sanity-check that argument against `AdmissionGate.java:142`
     `req.isRespawn()` guard.
   - Is `lookupBucketTags(entityId)` safe to call after `session.getAttributes().remove(ATTR_ENTITY_ID)` already ran? (entityId is captured into a local first — verify.)

2. **D2 — `OutboundSender.drainLoop` wraps `Timer.Sample.stop(...)` in try/catch RuntimeException**
   - Does the wrap actually catch the failure mode the plan describes (Micrometer
     histogram-rotation / registry-shutdown races throwing inside `stop`)?
   - Does it swallow anything load-bearing? (The drain VT itself must survive — that
     is the explicit goal — but the `encode.send` timer sample for that frame is then
     lost. Acceptable trade?)

3. **D3 — `AdmissionMetrics.registerOutboundQueueDepthMaxGauge` early-returns on second call**
   - "First supplier wins" — is that the right semantics, or should second call
     replace? (Plan argues first-wins because second-call only happens via
     test-double-injection or hot reload; production registers exactly once at
     `@PostConstruct`.)
   - `log.warn` vs throw — the bean is mid-construction on the second call path; is a
     log-and-continue too quiet?

4. **SUMMARY rewrite quality (`20-01c-SUMMARY.md`)**
   - Pass-1 RED findings: F1 narrative (cap is global, not per-bucket), respawn-cap
     column relabel, `active.entities` post-D1 numbers. Are the rewritten claims now
     consistent with the code (`AdmissionGate.java:58,140-151`) and the new sidecars?
   - Pass-1 YELLOW: F2 timer envelope, D-20 reference, MAX-vs-p95 in actuator JSON.
     Did the rewrites land or did anything get missed?
   - Pushback section: does it defend the two pushed-back claims (claude inline R3
     "10× scale fictional" arithmetic; codex+opencode `tick.health.work-time-ms`
     hallucination) on **arithmetic and source-data** grounds, or just by assertion?
     **Independently re-derive R3** against the new sidecars
     (`profiles/metrics-1000bots-baseline-0824f1a.json` — `enc.cnt = 76 048` at the
     reported sample) and the capture window described in SUMMARY. If the pushback
     arithmetic is also wrong, flag it.

5. **Recapture sanity (`profiles/*-0824f1a.*`)**
   - `metrics-1000bots-baseline-0824f1a.json`: does the last sample show
     `active.entities ≈ 1000 ± churn` (D1 falsifiability), `rejected.measurements`
     reasons = `respawn-cap` only (no `world-full`), `qmax` non-NaN at every sample
     (D3 regression check)?
   - `jfr-*.meta.json`: do `asprof_cpu_interval_us` and `asprof_alloc_interval_bytes`
     fields land at every tier (D4)?

## Out of scope

- Re-litigating pass-1 findings already addressed (cap-narrative rewrite,
  respawn-cap relabel, etc.). If the rewrite resolves them, mark resolved and move on.
- D4 itself (`meta.json` field additions) — trivial and verifiable by direct read.
- The 12 binary/HTML profile artifacts — too large for inline review. Sidecar JSON
  is sufficient for the falsifiability checks above. Flamegraph HTMLs are
  human-only artifacts.
- Phase 20-02 / 20-04 / 20-05 / 20-06 plans — separate work, not in this PR.

## Output format

Use the same RED / YELLOW / GREEN finding-ID scheme as pass-1 reviews. For every
finding:
- **State the claim** (one sentence).
- **Cite the source** (file:line OR sidecar field + value).
- **Severity** RED (must-fix before merge) / YELLOW (should-fix or document) / GREEN (LGTM observation).
- **Recommendation** (concrete next action — or "no action, just observation").

If you defer to pass-1 disposition, say so explicitly rather than re-stating the
finding.
