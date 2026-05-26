# 20-01c pass-4 multi-review prompt

## What you are reviewing

This is **pass 4** of multi-review for Phase 20 Plan 01c (paralife project — a
Spring Boot virtual-thread WebSocket simulation server). Passes 1–3 are supplied
as `--context` (`20-01c-REVIEW-pass3-inline.md` / `-reference.md`). **Do not
re-litigate settled pass-1/2/3 findings** — they are addressed or backlogged.

Pass 4 reviews ONLY the delta that landed **after** pass-3 was run and was
therefore never itself reviewed:

1. **The fix for pass-3's RED (`R-P3-1`)** — a resource leak in
   `WorldWebSocketHandler.cleanupByEntityId`. Pass-3 found that after the H1 fix
   (`cleanupBot` skips all active-bucket dec/release when `entityId == null`),
   the grace-expiry path could leak. Specifically: when `cleanupByEntityId`
   finds the stalled session **still registered** (`sessionRegistry.getSession`
   returns non-null) and calls `cleanupBot(session)`, `cleanupBot` hits the
   `entityId == null` guard (markStalled cleared the attr) and skips — so neither
   the `active.entities` gauge is decremented nor the `bucketTagsByEntityId`
   snapshot released. **Double leak.** The fix makes `cleanupByEntityId` own the
   `decActiveBucketByTags(bucketTags)` + `releaseBucketTags(entityId)` itself,
   before calling `cleanupBot`, on the session-still-registered branch. See
   `WorldWebSocketHandler.cleanupByEntityId` (~lines 808–835).

2. **An active-population profiling scenario** added to `20-01c-SUMMARY.md` — a
   "50× food" capture (`paralife.simulation.nutrient-spawn-probability=0.05`) with
   its own sidecars (`*-active-50xfood-103a615.json`). This is a **profiling-only
   knob** to move the workload off the starvation-dominated death-treadmill seen
   at production defaults, so the CPU hot path lands on the transport layer
   (Phase 20's actual remit). Production default is UNCHANGED.

## What to focus on — verify against the ACTUAL code, line by line

**A. `R-P3-1` fix correctness.** Walk every path that reaches the active-bucket
accounting and confirm each entity contributes exactly one net decrement and one
snapshot release — no leak, no double-dec:
- **Path A** — alive session closes normally (`entityId` present).
- **Path B-no-reconnect** — stalled → detach → close, no rebind. `markStalled`
  cleared `ATTR_ENTITY_ID`. Two sub-cases that matter:
  - grace-expiry fires while the session is **already unregistered**
    (`afterConnectionClosed` ran first) → `cleanupByEntityId` manual-cleanup branch.
  - grace-expiry fires while the session is **still registered** → the NEW fix path.
    **This is the one the fix targets — scrutinise it hardest.**
- **Path B-with-reconnect** — rebind re-incs active + re-puts the snapshot.
- **Path C** — `markDead` already dec'd via snapshot; `cleanupBot` skips.

  For each: cite the exact file:line where the dec happens (or is correctly
  skipped), and confirm `bucketTagsByEntityId` is released exactly once. Flag any
  path where `wasRegistered == true` but no callsite decrements, OR where two
  callsites both decrement (double-dec / negative gauge).

**B. Does the fix introduce anything new?** The fix adds dec+release in
`cleanupByEntityId` *before* `cleanupBot`. Confirm `cleanupBot` then does NOT also
dec (it must hit its own `entityId == null` skip). Confirm ordering vs
`AdmissionGate` slot release (`releaseSlot`) is unaffected. Check the snapshot is
not released twice (once here, once in `cleanupBot`).

**C. active-population SUMMARY section vs data.** Verify the numeric claims in the
new SUMMARY "Active-Population Workload" section against the
`metrics-*-active-50xfood-103a615.json` sidecars (and the `*.meta.json` for capture
provenance). Flag any figure in the prose that the sidecars don't support.

## Out of scope — do NOT raise as blockers (backlog at most)

- **Population balance / death-treadmill / starvation rates.** Environmental and
  metabolic balance tuning is an explicit, logged deferral (gated behind a future
  user-facing UI). The 50× food value is a profiling knob, not a balance change.
  Do not raise "population isn't stable / entities die too fast" as a finding.
- Settled pass-1/2/3 items (D1–D4, R1/R2, Y1/Y2, B1/B2). Already dispositioned.
- Profiling artifact volume / binary diff noise.

## Locality

All inputs are local files. Do **NOT** call `gh`, `WebFetch`, `curl`, or any
network tool. The work is on branch `worktree-phase-20-01c-baseline-rebuild`;
do not fetch the PR — everything is in the worktree.

## Output

Group findings by severity: **BLOCKER** (must fix before merge) / **MEDIUM** /
**LOW / backlog**. For each finding give: `file:line`, the concrete mechanism, and
your recommended fix. Be specific — quote the code. Pushback on prior findings is
fine but must cite code. If you find no blockers, say so plainly.
