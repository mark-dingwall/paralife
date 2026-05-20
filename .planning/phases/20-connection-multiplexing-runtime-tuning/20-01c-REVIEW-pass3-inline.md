---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-20T22:29:40Z
files: ["src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-62c1b44.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-62c1b44.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-62c1b44.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-62c1b44.meta.json"]
usage:
  claude: { input: 10, output: 16, cached: 0, tool_calls: 0, elapsed_s: 216.6 }
  gemini: { input: 102834, output: 920, cached: 0, tool_calls: 0, elapsed_s: 235.8 }
  codex: { input: 1042239, output: 8869, cached: 908672, tool_calls: 31, elapsed_s: 183.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 13, elapsed_s: 428.2 }
synthesizer: claude
synthesized_at: 2026-05-20T22:29:40Z
---

# Cross-AI Review

## Claude Review

Pass-3 review against worktree. Caveman mode active.

# Pass-3 Multi-Review — 20-01c (HEAD 62c1b44)

## GREEN

### G1 — H1 correctness across all four cleanupBot paths

Walked each path. Net dec accounting:

**Path A (alive at close):** `entityId != null` → snapshot lookup → dec → release. +1 inc / −1 dec. ✓

**Path B-no-reconnect (markStalled → grace expire):**
- `markStalled` clears `ATTR_ENTITY_ID`, incs stalled bucket. **Does NOT dec active** (verified `WorldWebSocketHandler.java:992-1015`).
- `afterConnectionClosed` with `wasStalled=true` → does NOT call cleanupBot (early return at line 280). Session unregistered from sessionRegistry only.
- `ResumeTokenRegistry` sweep → `cleanupByEntityId(entityId)`.
- Inside: `botRegistry.getSessionByEntity` still returns sessionId (entity held). `sessionRegistry.getSession(sessionId)` returns null (was unregistered at stalled-close). Falls into manual branch: `decActiveBucketByTags` + `decStalledBucketByTags` + `releaseBucketTags` + grid clear + releaseSlot.
- Net: +1 inc / −1 dec. ✓ Single dec.

**Path B-with-reconnect:** rebind decs OLD snapshot, incs NEW (re-puts snapshot for same entityId). Eventual close → cleanupBot path A. ✓

**Path C (dead at close):**
- markDead removes `ATTR_ENTITY_ID`, decs via snapshot, releases snapshot.
- Session stays open until client closes. cleanupBot: `eid=null`, `wasRegistered=true` (ATTR_ENTITY_TYPE intact), `entityId==null` → **skips entire bucket-dec block** ✓, `releaseSlot` fires (gated only on wasRegistered).
- Net: +1 inc / −1 dec (markDead). ✓ Single dec.

H1 claim holds: post-fix delta of +50 (851→901) at 1000-tier sample 6 = the path-C double-decs that were firing once-per-respawn-cap-exhausted-session before. 99 respawn-cap rejections at sample 6, ~50 closed by scrape time = plausible.

### G2 — Snapshot release ownership (bucketTagsByEntityId cannot grow unbounded)

All four paths release exactly once:
- Path A: cleanupBot `releaseBucketTags(entityId)` at line 938.
- Path B-no-reconnect: cleanupByEntityId at grace-expire.
- Path B-with-reconnect: rebind re-puts (same key), eventual close releases via path A.
- Path C: markDead `releaseBucketTags(entityId)` at line 1004.

No path-C leak via cleanupBot — entityId==null branch correctly skips release call (snapshot already released by markDead). ✓

### G3 — Active.entities trajectory direct derivation (Y1 pushback)

Re-derived against `profiles/metrics-1000bots-baseline-62c1b44.json`:

| sample | utc | active.entities | rejected{respawn-cap}.COUNT |
|---|---|---|---|
| 1 | 22:12:13 | 968 | null |
| 2 | 22:12:19 | 1000 | null |
| 3 | 22:12:24 | 1000 | null |
| 4 | 22:12:29 | 997 | 3 |
| 5 | 22:12:34 | 972 | 28 |
| 6 | 22:12:39 | 901 | 99 |

Population at ±3% of nominal through samples 1-4, tails as respawn-cap-exhausted bots exit. The "10× scale is real" claim is supported by the gauge itself — no window arithmetic required. SUMMARY swap to active.entities-direct-proof is the right call. ✓

### G4 — R2 SUMMARY tick.health rewrite

Caveat #3 + Pushback retraction at SUMMARY lines 153-159, 213-217 are correct against the sidecars. Verified ranges:
- 100-tier values: 12, 8, 14, 7, 6, 7 → SUMMARY says "6-14 ms" ✓
- 500-tier: 33, 27, 29, 34, 29, 29 → SUMMARY says "27-34 ms" ✓
- 1000-tier: 51, 39, 37, 39, 60, 39 → SUMMARY says "37-60 ms" ✓

Hallucination framing dropped without defensiveness. Root cause (`jq` underscore-vs-hyphen on `work-time-ms` segment) cited concretely. ✓

### G5 — Regression checks all pass

| Check | Pass |
|---|---|
| `rejected.reason` = `respawn-cap` only at every tier | ✓ (no `world-full` anywhere across 18 samples) |
| `paralife.outbound.queue.depth.max` numeric 0 in every sample | ✓ |
| `paralife.tick.health.work-time-ms` numeric in every sample | ✓ |
| `paralife.outbound.detach.timeout` = 0 every sample | ✓ |

### G6 — D2 + D3 (carried from pass 2)

`sample.stop` try/catch unchanged from pass 2 (lines 373-378), still correct. Double-register guard at AdmissionMetrics.java:484-488 unchanged. ✓

## YELLOW

### Y1 — SUMMARY internal inconsistency about tick.health "deprecated"

SUMMARY contradicts itself across three sections:

| Location | Says |
|---|---|
| Caveat #3 (line 215) | "is **live**, not deprecated" + "**Both are live tick-cost meters; neither is deprecated**" |
| Pushback §"Pass-1 tick.health.work-time-ms reports" (line 218) | "The scalar is kept alongside the `tick.work.ms` DistributionSummary for SHA-continuity with 20-01b; it is **not** deprecated" |
| F2 Key Decision (line 89) | "it reads `null` in the 20-01c sidecars — see Caveat #3 below; the new Timer is the actually-useful encode+send measurement" |
| Deferred items (line 232) | "Wire up `paralife.tick.health.work-time-ms` MAINTENANCE-mode write path / **Deprecated scalar**; replaced by `tick.work.ms` DistributionSummary" |

F2 Key Decision still parrots the wrong "reads null" claim. Deferred items table still calls it "Deprecated scalar". Caveat #3 + Pushback retract both. Pick one position.

**Severity:** YELLOW. Doc bug, not load-bearing for the H1/D1/D2/D3 code changes.

**Recommendation:** Update F2 Key Decision (line 89) to drop the "reads null" parenthetical. Update Deferred Items row 5 to either remove (gauge is live, nothing to wire up) or rewrite the reason as "scalar retained for SHA-continuity; tick.work.ms is the canonical going-forward". Caveat #3 + Pushback retraction stay as-is.

### Y2 — handleTransportError → cleanupBot on stalled session breaks Phase 17 D-12 hold-for-grace invariant (pre-existing, surfaced by H1 analysis)

`WorldWebSocketHandler.handleTransportError:293-298` calls `cleanupBot(session)` unconditionally — even when the session is STALLED. cleanupBot:
- runs `botRegistry.unregisterBySession(sessionId)` at line 921
- (post-H1) skips active-bucket dec because entityId==null (markStalled cleared it)
- releases slot via `wasRegistered=true`

Then grace-expire fires `cleanupByEntityId(entityId)` → `botRegistry.getSessionByEntity(entityId)` returns empty → `sessionId==null` branch → decs both buckets via snapshot, releases snapshot, **does NOT call releaseSlot a second time** (correct).

Net active dec: 0 (cleanupBot skips) + 1 (grace-expire manual branch) = 1. ✓ Counter math correct.

But the entity has been **unregistered from BotRegistry at transport-error time**, which violates the D-12 "entity held on grid for grace-expiry sweep" invariant. Grid clear runs immediately via cleanupBot's `botRegistry.getBySession(sessionId).ifPresent(state -> worldGrid.clearEntity(...))` — the cell is freed before grace window expires, so client cannot rebind to the original position.

**Pre-existing, not introduced by H1.** Surfaced because pass-3 prompt asked to check unintended side-effects of the dec-skip restructure. Not blocking.

**Recommendation:** track as STATE.md TD entry: handleTransportError should follow the same wasStalled-skip pattern as afterConnectionClosed (line 280) — leave the entity for grace-expiry sweep.

### Y3 — enc.cnt vs frame_size COUNT scrape-instant skew (informational, not regression)

100-tier sample 5: frame_size=4687, enc.cnt=4787 (gap +100). Sample 6: both 5825. Per-sample delta: frame_size +1138, enc.cnt +1038 — frame_size grew more than enc.cnt, which is impossible if recordFrameSize only fires after successful encode (verified at OutboundSender.java:310-311 before sendMessage).

Mechanism: Timer.start fires before encode; Timer.stop fires in finally after sendMessage. recordFrameSize fires between encode and sendMessage. At scrape instant, frames in-flight can have recordFrameSize done but Timer.stop pending, momentarily inverting the gap. 100 bots × 1-in-flight-per-VT = up to 100-frame skew possible. Sample 5 caught this state; sample 6 caught the post-drain state.

Not a counter bug, not a finding. SUMMARY caption footnote on "gap (0.005-0.6%) reflects encode-failure frames caught at OutboundSender.java:329" is the steady-state interpretation; the 100-tier sample 5 anomaly is a scrape-skew artifact (different mechanism, same direction).

**Severity:** YELLOW informational. The caption is approximately right but conflates two different mechanisms.

**Recommendation:** Optional one-line addition to the caption: "Sub-percent gap also possible from scrape-instant skew where frames are mid-flight between recordFrameSize and Timer.stop." Or just leave it — the saturation conclusion doesn't depend on this.

## Disposition vs prior passes

Pass-2 RED items: **all addressed.**
- R1 (cleanupBot double-dec): resolved by H1 — verified across paths A/B/C above.
- R2 (tick.health pushback hallucination): retracted in Caveat #3 + Pushback (Y1 flags residual inconsistency in F2 + Deferred items, not the retraction itself).

Pass-2 YELLOW items: mostly resolved.
- N2/N4 (loose pushback arithmetic, stale cleanupBot comment): pushback now uses active.entities direct evidence; cleanupBot comment at line 932 accurate post-H1 ("Fallback: session tags. Hits only when no snapshot was ever captured (e.g. legacy tests…)") — only fires inside `if (entityId != null)` else branch now, narrowly correct. ✓
- N3 (D3 atomic check-then-set): unchanged from pass 2 disposition; production single-threaded init makes log+ignore fine.

## Summary

| ID | Severity | Action |
|---|---|---|
| G1-G6 | GREEN | no action |
| Y1 | YELLOW | reconcile F2 key decision + Deferred items with new Caveat #3 / Pushback retraction (three places must agree) |
| Y2 | YELLOW | log STATE.md TD: handleTransportError needs wasStalled-skip pattern (pre-existing, out-of-scope) |
| Y3 | YELLOW | optional caption polish |

H1 fix is correct. R2 rewrite retraction is correct. No RED. Y1 is a 5-line doc cleanup; ship-with or fix-now both reasonable.

---

## Gemini Review

# Cross-AI Review: Plan 20-01c (Pass-3 Remediation)

## RED

**R1 — H1 skip-when-null introduces an active-bucket and tags-snapshot leak during stalled-close race.**
- **Claim:** If a stalled session's grace period expires (`cleanupByEntityId`) before its transport close fully unregisters it, `cleanupByEntityId` delegates to `cleanupBot`, but `cleanupBot` now skips the active-bucket decrement and tag release because `entityId` is null — meaning neither method performs the required cleanup.
- **Source:** `WorldWebSocketHandler.java:827-832` (delegation) and `935-950` (skip logic).
- **Severity:** RED.
- **Recommendation:** In `cleanupByEntityId`, if `session != null`, it should explicitly perform the `decActiveBucketByTags(bucketTags)` and `releaseBucketTags(entityId)` if `session.getAttributes().get(ATTR_ENTITY_ID)` is already null (meaning `cleanupBot` will skip them). Alternatively, `cleanupByEntityId` could always own the active-dec for stalled sessions instead of conditionally delegating to `cleanupBot`.

## YELLOW

**Y1 — SUMMARY contradicts the "deprecated" framing of tick.health.work-time-ms.**
- **Claim:** The SUMMARY correctly acknowledges the scalar emits values, but explicitly claims "neither is deprecated," which directly contradicts the pass-2 instruction to maintain the "deprecated in favor of `tick.work.ms` DistributionSummary" framing.
- **Source:** `20-01c-SUMMARY.md:215-218` (Caveat #3).
- **Severity:** YELLOW.
- **Recommendation:** Update Caveat #3 to restore the pass-2 recommended phrasing: "`paralife.tick.health.work-time-ms` emits numeric values... the scalar is live but deprecated in favor of `tick.work.ms` DistributionSummary."

## GREEN

**G1 — H1 skip logic is correct for Path C (markDead).**
- **Claim:** The `cleanupBot` skip logic works perfectly for Path C; `markDead` performs the active dec and tag release, and `cleanupBot` correctly skips the duplicate decrement when the session eventually closes.
- **Source:** `WorldWebSocketHandler.java:935-950`.
- **Severity:** GREEN.
- **Recommendation:** No action, just observation.

**G2 — Pushback correctly utilizes direct active.entities trajectory.**
- **Claim:** The revised pushback drops the brittle overlapping window arithmetic and directly proves sustained population using the `active.entities` trajectory (968→1000→1000→997→972→901), which identically matches the 62c1b44 sidecar.
- **Source:** `20-01c-SUMMARY.md` (§Pushback) and `metrics-1000bots-baseline-62c1b44.json`.
- **Severity:** GREEN.
- **Recommendation:** No action, just observation.

**G3 — Recapture sanity and regression checks pass.**
- **Claim:** The 62c1b44 sidecars confirm that `qmax` is numeric `0.0`, `rejected` reason is `respawn-cap` only (no `world-full`), `tick.health` reads non-null numeric values, and `peak_registered` matches the tier counts.
- **Source:** `profiles/metrics-*-baseline-62c1b44.json`.
- **Severity:** GREEN.
- **Recommendation:** No action, just observation.

**G4 — enc.cnt vs frame.size.bytes gap documented.**
- **Claim:** The SUMMARY headline table caption correctly clarifies that `enc.cnt` includes encode-failure iterations (caught at `OutboundSender.java:329`), cleanly explaining the numerical gap between the metrics.
- **Source:** `20-01c-SUMMARY.md` (Headline Table Caption).
- **Severity:** GREEN.
- **Recommendation:** No action, just observation.

---

## Codex Review

**RED**

**R1 — SUMMARY still contradicts itself on `tick.health.work-time-ms`.**  
Source: [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:108) still says the scalar reads `null`; [line 236](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:236) correctly says it is live; [line 250](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:250) still calls it deprecated and unwired. Sidecars are numeric: 1000-tier values are `51,39,37,39,60,39`, and `TickHealthMonitor.onTick` writes it every tick at [TickHealthMonitor.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/TickHealthMonitor.java:63).  
Severity: RED.  
Recommendation: remove the stale `null` sentence and deferred item; keep the later “live, not deprecated” framing.

**YELLOW**

**Y1 — SUMMARY has stale `respawn-cap=58` counts for the 62c1b44 capture.**  
Source: [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:53) and [line 235](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:235) say peak/last `respawn-cap` is `58`; the 62c1b44 1000-tier sidecar final sample has `paralife_admission_rejected.measurements[0].value = 99.0`, matching the table at [line 84](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:84).  
Severity: YELLOW.  
Recommendation: replace `58` with `99` in the key-decision and caveat text.

**Y2 — H1 path-B narrative says stalled close fires `cleanupBot`, but normal stalled close explicitly does not.**  
Source: SUMMARY [line 134](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:134) says stalled close fires `cleanupBot`; code returns before cleanup on stalled close at [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:388). Grace expiry owns the dec in `cleanupByEntityId` at [line 820](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:820).  
Severity: YELLOW.  
Recommendation: rewrite path B as “stalled close skips `cleanupBot`; grace-expire `cleanupByEntityId` owns active dec/release.”

**Y3 — `cleanupBot` Javadoc is orphaned/misplaced.**  
Source: cleanup doc at [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:837) is followed by another Javadoc for `onEntityRemapped`, then `cleanupBot` starts at [line 885](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:885) with no attached Javadoc.  
Severity: YELLOW.  
Recommendation: move the cleanup comment immediately above `cleanupBot`, and include the entityId-null skip semantics there.

**GREEN**

**G1 — H1 skip-when-null is correct for the production cleanup ownership model.**  
Source: `cleanupBot` only decrements active when `entityId != null` at [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:919); `markDead` owns path C dec/release at [line 1001](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:1001); stalled grace expiry owns path B dec/release at [line 820](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:820).  
Severity: GREEN.  
Recommendation: no code action.

**G2 — 62c1b44 sidecar regression checks pass.**  
Source: all 18 samples have `qmax=0` and numeric `tick.health`; rejection reason is only `respawn-cap` at 1000-tier; no `world-full` appears. Harness reports show `peak_registered = target_count` for 100/500/1000 and `connect_failures_total = 0`.  
Severity: GREEN.  
Recommendation: no action, just observation.

**G3 — Active population pushback is now supported by direct trajectory.**  
Source: 1000-tier `active.entities` is `968→1000→1000→997→972→901` in `metrics-1000bots-baseline-62c1b44.json`; SUMMARY repeats this at [line 185](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:185).  
Severity: GREEN.  
Recommendation: no action; this is stronger than the old `enc.cnt` arithmetic.

---

## Opencode Review

I'll walk through each focus area against the source code and sidecars. Let me start by reading the key code locations.Now let me check the `cleanupByEntityId` and `afterConnectionClosed` paths, plus the sidecars:Let me read the crucial `markStalled` and `afterConnectionClosed` sections:Now let me read `cleanupByEntityId` fully:Now let me verify the sidecar data for the regression checks:All regression checks pass. Now let me verify the SUMMARY claims about `enc.cnt` derivation:Now let me check whether the `cleanupByEntityId` session-in-registry branch has a gap:I've now read all the relevant code and verified all 18 samples across all 3 sidecars. Here is the pass-3 review:

---

## RED

None.

---

## YELLOW

### Y-P3-1 — cleanupByEntityId session-in-registry branch is structurally fragile

**Claim:** The H1 cleanupBot skip-when-entityId-null guard is correct for all 4 paths, but `cleanupByEntityId:814-816` (session-still-in-registry branch) calls `cleanupBot(session)` which skips active-bucket dec when `entityId==null` (markStalled cleared it). `cleanupByEntityId` then returns without dec'ing active or calling `releaseBucketTags`. If this branch ever fires before `afterConnectionClosed` unregisters the session, the active bucket leaks and the snapshot is never released.

**Cite:** `WorldWebSocketHandler.java:813-816` (calls cleanupBot), `:919-939` (skip when entityId==null), `:808-811` (stalled dec only), vs `:818-823` (else branch correctly decs+releases).

**Severity:** YELLOW. Practically impossible — `afterConnectionClosed` fires within microseconds of `SERVICE_RESTARTED` transport close, while `cleanupByEntityId` fires after `grace-window-ticks × 500ms` (≥5 s). The else-branch is the dominant path. But the code has no guard: if a pathological TCP stack delays the close past the grace window, the active gauge accumulates.

**Recommendation:** Add a one-line comment noting the structural dependency on `afterConnectionClosed` firing first, or add a defensive `decActiveBucketByTags(bucketTags) + releaseBucketTags(entityId)` in the session-in-registry branch if `entityId` (i.e. the snapshot lookup) is non-null.

### Y-P3-2 — Key-decision tick.max prose has stale numbers

**Claim:** The key-decisions section says `paralife.tick.work.ms.max at last sample: 83 ms (100 bots) / 75 ms (500 bots) / 106 ms (1000 bots)` and "~1.3× amplification." The 62c1b44 sidecars show `tick_work_ms.MAX = 83.6 / 99.7 / 97.9` (matches the headline table's 84/100/98, not the prose's 83/75/106).

**Cite:** `20-01c-SUMMARY.md` key-decisions §7 tick-wall-time paragraph vs headline table vs `metrics-*-62c1b44.json` tick_work_ms MAX field.

**Severity:** YELLOW. The headline table is correct; the prose appears to carry numbers from an earlier capture (20-01b or 0824f1a). The amplification ratio should be `98/84 ≈ 1.17×`, not `~1.3×`.

**Recommendation:** Rewrite the prose to match the headline table: "84 ms (100) / 100 ms (500) / 98 ms (1000) — ≈1.17×, not 10×."

### Y-P3-3 — `enc.max` column carries since-registration global MAX, not per-sample peak

**Claim:** The headline table column `enc.max` shows 20/14/16 ms, but these are the Timer's since-registration MAX (identical across all 6 samples per tier per the sidecars), not frame-level max within that sample window. This is correctly identified in the caption but not in the column header itself.

**Cite:** `metrics-1000bots-baseline-62c1b44.json` sample 1-6 all show `MAX=0.016490071` for encode_send_ms.

**Severity:** YELLOW. Minor presentational ambiguity — same as the already-documented `tick.max` semantic. The caption covers it; the column name could be clearer.

**Recommendation:** No change needed — caption is sufficient. Observation only.

---

## GREEN

### G-P3-1 — H1 cleanupBot skip-when-null is correct across all 4 nominal paths

Verified against source:

| Path | markDead dec? | cleanupBot dec? | cleanupByEntityId dec? | Net | 
|---|---|---|---|---|
| A (alive close) | no | yes (entityId non-null → lookup snapshot) | no | 1 dec ✓ |
| B-no-reconnect (stalled→grace-expire) | no | no (afterConnectionClosed returns early at `:397`) | yes (`:820-821` else-branch, session unregistered) | 1 dec ✓ |
| B-with-reconnect | no | yes (rebind re-incs + re-puts snapshot at `:511`; eventual close decs via entityId present) | no | 1 dec net ✓ |
| C (dead→close) | yes (`:1005`) | no (entityId==null → skip at `:920`) | no | 1 dec ✓ |

No slot-leak: `admissionGate.releaseSlot()` at `cleanupBot:917` is gated on `wasRegistered` (ATTR_ENTITY_TYPE removal), not `entityId`, so the H1 guard doesn't affect slot release.

**Cite:** `WorldWebSocketHandler.java:919-939` (cleanupBot guard), `:995-1013` (markDead), `:388-397` (afterConnectionClosed stalled early-return), `:808-823` (cleanupByEntityId), `:482-512` (rebind path).

**Severity:** GREEN.

### G-P3-2 — R2 tick.health rewrite is correct

The caveat #3 rewrite correctly retracts the "reviewer hallucination" framing, explains the root cause (underscore-vs-hyphen `jq` query error), and states both meters are live. Verified: all 18 samples × 3 tiers carry numeric `tick.health.work-time-ms` values in 62c1b44 sidecars. `TickHealthMonitor.onTick:63` writes the `AtomicLong`-backed gauge every tick.

**Cite:** `20-01c-SUMMARY.md` caveat #3, `metrics-*-62c1b44.json` `paralife_tick_health_work-time-ms` field.

**Severity:** GREEN.

### G-P3-3 — Y1 active.entities trajectory pushback verified

1000-tier 62c1b44: 968 → 1000 → 1000 → 997 → 972 → 901. Population sits at full ±3% through samples 1-4, tails into respawn-cap-exhaustion at 5-6. The +50 delta vs pre-H1 0824f1a (851→901) = path-C double-decs H1 closed. The `active.entities` gauge directly proves "10× is real" — no window arithmetic needed.

**Cite:** `metrics-1000bots-baseline-62c1b44.json` sample values.

**Severity:** GREEN.

### G-P3-4 — All regression checks pass

- `rejected{reason}` = `respawn-cap` only at 500/1000-tier; `null` (meter never written) at 100-tier. Zero `world-full`. ✓
- `qmax` = 0.0 at all 18 samples — non-NaN, gauge supplier pinning holds. ✓
- `tick.health.work-time-ms` numeric at all 18 samples (6–60 ms range). ✓
- `detach.timeout` = 0 at all samples — D2 drain VT preservation holds. ✓
- `enc.cnt` / `frame.size.cnt` gap ≤ 100 frames (0.6% at 100-tier S5, 0.03% at 1000-tier S1) — encode-failure frames documented. ✓

**Cite:** all three `metrics-*-62c1b44.json` sidecars.

**Severity:** GREEN.

### G-P3-5 — D1/D2/D3 code changes mechanically correct

- D1 markDead: `entityId` captured to local before `remove(ATTR_ENTITY_ID)`, `lookupBucketTags(entityId)` reads from `ConcurrentHashMap`, safe. No `releaseSlot()` — respawns bypass slot acquisition. ✓
- D2: `sample.stop` wrapped in `try/catch RuntimeException` inside `finally`. ✓
- D3: `registerOutboundQueueDepthMaxGauge` early-returns on second call; `log.warn` correctly non-fatal. ✓
- D4: `meta.json` carries `asprof_cpu_interval_us=10000`, `asprof_alloc_interval_bytes=524288`, `cap_during_run=1500`. ✓

**Cite:** `WorldWebSocketHandler.java:995-1013`, `OutboundSender.java:373-378`, `AdmissionMetrics.java:484-488`, `jfr-*-62c1b44.meta.json`.

**Severity:** GREEN.

### G-P3-6 — `bucketTagsByEntityId` snapshot map cannot grow unbounded

Every path that writes an entityId→Tags snapshot has a corresponding release:
- Path A: incActiveBucket writes, cleanupBot releases (`:933`)
- Path C: incActiveBucket writes, markDead releases (`:1007`)
- Path B-no-reconnect: incStalledBucket writes, cleanupByEntityId releases (`:804` or `:823`)
- Path B-with-reconnect: incActiveBucket writes (rebind at `:511`), cleanupBot releases (`:933`)

The theoretical gap (cleanupByEntityId session-in-registry branch, Y-P3-1) is the only uncovered path — practically unreachable.

**Cite:** `AdmissionMetrics.java:285-291` (incActiveBucket puts snapshot), `WorldWebSocketHandler.java:933,1007,804,823`.

**Severity:** GREEN.

---

## Summary

| ID | Severity | Claim |
|---|---|---|
| Y-P3-1 | YELLOW | cleanupByEntityId session-in-registry branch fragile if reached before afterConnectionClosed |
| Y-P3-2 | YELLOW | Key-decision tick.max prose has stale numbers (83/75/106 vs actual 84/100/98) |
| Y-P3-3 | YELLOW | enc.max column carries global-since-registration MAX (caption covers it) |
| G-P3-1 | GREEN | H1 cleanupBot skip-when-null correct across all 4 nominal paths |
| G-P3-2 | GREEN | R2 tick.health rewrite correct — retracts hallucination framing, cites wrong jq query |
| G-P3-3 | GREEN | Y1 active.entities trajectory pushback verified against 62c1b44 sidecar |
| G-P3-4 | GREEN | All 4 regression checks pass against 62c1b44 sidecars |
| G-P3-5 | GREEN | D1/D2/D3 code changes mechanically correct |
| G-P3-6 | GREEN | bucketTagsByEntityId snapshot map has release on every path |

**No RED findings.** The H1 fix is correct across all 4 paths. Y-P3-1 is the only structural concern — practically unreachable (requires TCP close to outlast grace window), but worth documenting. Y-P3-2 is a minor copy-paste number mismatch in prose vs headline table.

---

## Consensus Summary

### Agreed Strengths
- **H1 cleanupBot skip-when-null correct across all 4 paths.** Claude (G1), Gemini (G1, Path C), Codex (G1), OpenCode (G-P3-1) all walked paths A/B/C and confirmed net +1 inc / −1 dec, no slot/snapshot leak.
- **`bucketTagsByEntityId` snapshot map bounded.** Claude (G2) and OpenCode (G-P3-6) verified release on every reachable path.
- **`active.entities` direct-trajectory pushback (Y1) is sound.** Claude (G3), Gemini (G2), Codex (G3), OpenCode (G-P3-3) all re-derived 968→1000→1000→997→972→901 against the 62c1b44 1000-tier sidecar; stronger evidence than the prior window arithmetic.
- **R2 tick.health rewrite is correct.** Claude (G4), Codex (G1 implicit), OpenCode (G-P3-2) confirmed the retraction matches sidecar values; jq underscore-vs-hyphen root cause cited.
- **Regression checks pass at 62c1b44.** Claude (G5), Gemini (G3), Codex (G2), OpenCode (G-P3-4) all confirmed: `qmax=0`, `rejected.reason=respawn-cap` only, `tick.health` numeric at all 18 samples, `detach.timeout=0`, peak_registered matches tier.
- **D1/D2/D3 code changes mechanically correct.** Claude (G6 carried), OpenCode (G-P3-5) — `sample.stop` try/catch, double-register guard, markDead local-capture all hold.

### Agreed Concerns
- **[HIGH] SUMMARY internal inconsistency on tick.health.work-time-ms deprecation/null framing.** Claude (Y1), Gemini (Y1), Codex (R1) all flag the same defect: Caveat #3 (line 215) and Pushback (line 218) say "live, not deprecated"; F2 Key Decision (line 89) still says "reads null"; Deferred Items (line 232/250) still calls it "Deprecated scalar". Gemini reads it as a missed instruction (wants "deprecated" restored); Claude & Codex read it as residual stale text that should be removed. Three places must agree — pick one position. Codex rates this RED; Claude & Gemini rate YELLOW.
- **[MED] SUMMARY narrative mismatches code or sidecars in 2+ places.** Codex (Y1: stale `respawn-cap=58` should be 99 to match the table) + OpenCode (Y-P3-2: stale `tick.max=83/75/106` and "~1.3×" prose vs actual 84/100/98 and ~1.17× in the headline table). Codex also flags (Y2) that path-B narrative says stalled close fires `cleanupBot`, but code returns early at `:388-397` — grace-expire owns the dec. All are doc-vs-evidence drift in the same SUMMARY, not code bugs.
- **[LOW] `cleanupByEntityId` session-in-registry branch has a structural dependency on `afterConnectionClosed` firing first.** OpenCode (Y-P3-1) flagged this directly; Claude's Y2 (handleTransportError → cleanupBot on stalled session) is a related variant of the same "stalled session reaches cleanupBot with entityId==null and the H1 skip leaves dec/release to a different owner" pattern. Practically unreachable in production (TCP close fires within microseconds; grace window ≥5 s), but worth a comment or defensive dec.

### Divergent Views
- **Severity of the tick.health doc inconsistency.** Codex calls it RED (blocks ship); Claude & Gemini call it YELLOW (5-line doc cleanup). The functional code is correct in all three reads — disagreement is only about whether the doc inconsistency blocks merge.
- **Desired resolution of the tick.health framing.** Gemini (Y1) wants Caveat #3 *re-edited* to restore "deprecated in favor of tick.work.ms"; Claude (Y1) and Codex (R1) want the *opposite* — keep "live, not deprecated" and scrub F2 + Deferred Items to match. Worth a deliberate call rather than splitting the difference.
- **Gemini R1 (active-bucket leak in cleanupByEntityId delegation).** Gemini rates RED claiming neither method performs cleanup. Claude (G1 path B-no-reconnect), Codex (G1), OpenCode (G-P3-1) all walked the same path and concluded the `cleanupByEntityId` else-branch at `:818-823` *does* own the dec+release when `cleanupBot` skips. Three-of-four say not a bug; recommend treating as a misread unless someone can produce a repro trace. (OpenCode's Y-P3-1 flags the adjacent session-in-registry branch as fragile but practically unreachable — a different concern.)
- **enc.cnt vs frame_size gap interpretation.** Claude (Y3) proposes the steady-state caption ("encode-failure frames") conflates two mechanisms and a scrape-skew artifact may explain the 100-tier sample-5 anomaly; Gemini (G4) and OpenCode (G-P3-4) treat the caption as sufficient. Optional polish, not a finding.
