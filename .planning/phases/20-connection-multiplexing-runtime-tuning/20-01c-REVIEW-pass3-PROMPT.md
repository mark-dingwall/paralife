# 20-01c pass-3 multi-review prompt

## What you are reviewing

This is **pass 3** of multi-review for Phase 20 Plan 01c (paralife project). Pass
1 (`20-01c-REVIEW-inline.md` + `20-01c-REVIEW-reference.md`) surfaced findings
against an early baseline at HEAD `1818eeb`; pass 2 (`20-01c-REVIEW-pass2-*.md`,
supplied as context) reviewed the D1/D2/D3 + 0824f1a re-capture remediation. Two
RED findings from pass 2 have now been addressed:

- **R1 — D1 path-C double-decrement in `cleanupBot`.** `cleanupBot` previously
  fell into the session-tag fallback `decActiveBucket(s)` when `entityId == null`
  (the state after `markDead` already dec'd via snapshot). Fix: skip the entire
  active-bucket dec block when `entityId == null` — both path C (markDead-then-
  close) and the pre-existing path B (markStalled-then-close-without-reconnect-
  before-grace-expire) now leave the dec to the snapshot-owning callsite. See
  `WorldWebSocketHandler.cleanupBot` ~lines 919–940.
- **R2 — SUMMARY tick.health.work-time-ms = null claim.** The pass-2 SUMMARY's
  caveat #3 and pushback-section bullet claimed `paralife.tick.health.work-time-ms`
  was empty/null and called the codex+opencode pass-1 reports "hallucination". Pass-2
  reviewers convergently flagged this as factually wrong against the 0824f1a sidecars
  (which carry numeric values 19–60 ms). The SUMMARY caveat + pushback have been
  rewritten to acknowledge the original reports were correct and retract the
  hallucination framing.

Plus YELLOW polish:

- **Y1 — pushback "10× / 103%" arithmetic** replaced with a direct active.entities
  trajectory derivation; window-arithmetic surface removed.
- **Y2 — `cleanupBot` doc-comment** refresh to match the new skip-when-null semantics.
- **B1 — enc.cnt vs frame.size.cnt caption** added (gap = encode-failure frames).
- **B2 — `rejected{respawn-cap}=0`** rendered as `—` for the 100/500-tier samples
  where the meter never incremented.

Full re-capture at HEAD `62c1b44` (new baseline SHA). 12 new `*-62c1b44.*` artifacts
in `profiles/`; pass-2's `*-0824f1a.*` artifacts removed.

The work lives on branch `worktree-phase-20-01c-baseline-rebuild` (PR #1 against
`main`, but **do not fetch the PR** — everything you need is in this worktree).

**Locality.** All review inputs are local files. Do NOT call `gh`, `WebFetch`, or
any network tool.

## What to focus on

1. **H1 — `cleanupBot` skip-when-null correctness.** Walk all four cleanupBot entry
   paths against the new skip block:
   - Path A (alive at close): `entityId` non-null. Existing snapshot-lookup→dec→release
     path runs unchanged.
   - Path B-no-reconnect (stalled→close-via-detachSession→cleanupBot, no rebind):
     `entityId == null` (markStalled cleared it). cleanupBot now skips. At grace-
     expire, `cleanupByEntityId(entityId)` walks the `sessionId == null` branch and
     decs active via `bucketTagsByEntityId` snapshot once. **Verify single net dec.**
   - Path B-with-reconnect: rebind's Allow path re-incs active + re-puts snapshot.
     Eventual close fires cleanupBot with entityId present → decs once.
   - Path C (dead at close, no respawn): markDead already dec'd via snapshot.
     cleanupBot now skips. Single net dec.

   **Specifically check:** does the new skip introduce any path where `wasRegistered =
   true` but `entityId == null` AND no other dec callsite runs? If yes, that's a
   slot-leak. (The plan claims path B's grace-expire branch + path C's markDead
   cover both null cases — verify this claim against the code.)

2. **R2 — SUMMARY tick.health rewrite.** Does the rewrite (a) retract the
   hallucination framing without being defensive, (b) explain WHY the scalar reads
   numeric values (it does — `TickHealthMonitor.onTick` writes the AtomicLong-backed
   gauge every tick), and (c) preserve the "deprecated, kept for SHA-continuity"
   framing without contradicting itself? Spot-check the 0824f1a-vs-62c1b44 sidecar
   gauge value to confirm it's still numeric.

3. **Y1 — active.entities trajectory pushback.** Pass-2 pushback was rebuilt
   around the `active.entities` trajectory (966→1000→1000→998→966→NNN) instead of
   `enc.cnt` window arithmetic. **Independently re-derive against
   `profiles/metrics-1000bots-baseline-62c1b44.json`.** If the trajectory tells a
   different story (e.g., post-H1 the population stays higher because path C no
   longer over-decs through the harness teardown), the pushback narrative may need
   adjustment.

4. **Regression checks against the new sidecars:**
   - `paralife.admission.rejected.availableTags.reason` should be `respawn-cap`
     only (no `world-full`) at 500/1000-tier.
   - `paralife.outbound.queue.depth.max` (`qmax`) non-NaN at every sample (D3
     regression).
   - `paralife.tick.health.work-time-ms` numeric at every sample (R2 spot-check).
   - `peak_registered = --count` at every tier (harness report).

5. **New mechanisms introduced by H1 that prior passes might miss.** The H1 fix
   restructures the dec block; reviewers should look for unintended side-effects
   (e.g., `releaseBucketTags(entityId)` no longer running for the `entityId == null`
   branch — verify the snapshot map cannot grow unbounded as a result; cf. the
   bucketTagsByEntityId map and its other release callsites).

## Out of scope

- Re-litigating pass-1 or pass-2 findings already addressed.
- The 12 binary/HTML profile artifacts — too large for inline review. Sidecar JSON
  is sufficient for the falsifiability checks above.
- Phase 20-02 / 20-04 / 20-05 / 20-06 plans — separate work, not in this PR.
- Settled rewrites (F1 cap narrative, F2 timer envelope, rejection-column rename
  to `respawn-cap`, D-20 reframing, verification-gate #5 MAX-vs-p95 note).

## Output format

Use the same RED / YELLOW / GREEN finding-ID scheme as prior passes. For every
finding:
- **State the claim** (one sentence).
- **Cite the source** (file:line OR sidecar field + value).
- **Severity** RED (must-fix before merge) / YELLOW (should-fix or document) / GREEN (LGTM observation).
- **Recommendation** (concrete next action — or "no action, just observation").

If you defer to pass-1/pass-2 disposition, say so explicitly rather than re-stating the
finding.
