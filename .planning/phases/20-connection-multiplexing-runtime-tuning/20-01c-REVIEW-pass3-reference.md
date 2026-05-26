---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-20T22:38:30Z
files: ["src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-62c1b44.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-62c1b44.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-62c1b44.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-62c1b44.meta.json"]
usage:
  claude: { input: 29, output: 890, cached: 2145189, tool_calls: 8, elapsed_s: 309.9 }
  gemini: { input: 246054, output: 1532, cached: 135709, tool_calls: 8, elapsed_s: 89.9 }
  codex: { input: 1040323, output: 10053, cached: 934400, tool_calls: 33, elapsed_s: 203.3 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 22, elapsed_s: 493.4 }
synthesizer: claude
synthesized_at: 2026-05-20T22:38:30Z
---

# Cross-AI Review

## Claude Review

All inputs read. Independently verified sidecars + code. Writing findings.

# Pass-3 Cross-AI Review — 20-01c HEAD `62c1b44`

## RED

**(none)**

H1 closes both pass-2 R1 (path C) and the pre-existing path-B double-dec (TD-20-01c-B). Skip-when-null is mechanically correct for the four call-paths the prompt enumerates (verified below in GREEN G1). R2 rewrite is factually accurate against the new sidecars and properly retracts the hallucination framing. No must-fix-before-merge findings.

## YELLOW

### Y1 — Latent active-bucket + snapshot leak in stalled-session-still-in-registry race window
**Claim.** Post-H1, `cleanupBot` skips the active-bucket dec + `releaseBucketTags` when `entityId==null`. The pre-H1 fallback `decActiveBucket(s)` previously compensated in one specific path that H1 does NOT close: when `cleanupByEntityId` (grace-expire) fires while the stalled session is still in `sessionRegistry`.

**Trace.**
- `markStalled` clears `ATTR_ENTITY_ID` and calls `outboundSender.detachSession(session, SERVICE_RESTARTED)` (`WorldWebSocketHandler.java:734,756`).
- `OutboundSender.detachSession` (`:207-212`) swallows any exception from `session.close()`. If the close throws (Jetty internal / corrupt session), `afterConnectionClosed` may not fire and the session stays in `sessionRegistry`.
- Grace expires → `cleanupByEntityId` (`:779-835`):
  - `botRegistry.getSessionByEntity` returns the bound `sessionId` (markStalled never unregisters BotRegistry).
  - Line 810: `decStalledBucketByTags(bucketTags)` — stalled bucket decremented.
  - Line 813: `sessionRegistry.getSession(sessionId)` returns the still-registered session.
  - Line 816: `cleanupBot(session)` fires with `entityId==null` → H1 skip → **active bucket NOT decremented**, **`releaseBucketTags(entityId)` NOT called**.
- Pre-H1 the fallback `decActiveBucket(s)` at the old line 930 covered the active dec via session-derived tags; the snapshot was also released by the old code path. Both compensations gone post-H1.

**Severity.** YELLOW. The race window is narrow in production (Jetty fires `afterConnectionClosed` promptly after `session.close()` returns successfully). Not observed in current sidecars because grace-expire likely never fires within the 200 s harness window with steady-state stalled=0. Structurally non-zero. SUMMARY line 140 claim "cleanupByEntityId at grace-expire" releases the snapshot for path B is **only true when sessionId is unregistered** — the if-session-still-in-registry branch (line 813-816) calls cleanupBot, which now skips.

**Cite.** `WorldWebSocketHandler.java:813-816` (no active-dec around the cleanupBot call), vs `:819-823` (manual-cleanup branch correctly dec+releases).

**Recommendation.** In `cleanupByEntityId` add the active-bucket dec + snapshot release to the if-session-in-registry branch so both branches converge:
```java
if (session != null) {
    if (admissionMetrics != null && bucketTags != null) {
        admissionMetrics.decActiveBucketByTags(bucketTags);
        admissionMetrics.releaseBucketTags(entityId);
    }
    cleanupBot(session);
}
```
cleanupBot will then re-skip (entityId==null) without double-deccing. Adds a defensive test that calls `cleanupByEntityId` with the stalled session still in `sessionRegistry` and asserts `totalActiveBucketCount() == 0`, `bucketTagsSize() == 0`. ~5 LOC + 1 test.

### Y2 — Internal contradiction in SUMMARY key-decisions §6 vs tick wall-time table
**Claim.** Line 58 (key-decisions): `tick.work.ms.max` "83 ms (100 bots) / 75 ms (500 bots) / 106 ms (1000 bots)". Line 168-171 (tick wall-time table) AND headline table (line 80-84): 84 / 100 / 98 ms. Sidecar truth: last-sample MAX = 83.6 / 99.7 / 97.9 ms — line 58 wrong on the 500 and 1000 numbers.

**Cite.** `metrics-100bots-baseline-62c1b44.json` sample 6 `paralife.tick.work.ms.MAX = 83.636968`; 500: `99.704254`; 1000: `97.918322`. SUMMARY line 58 vs 168-171.

**Severity.** YELLOW. Internal data inconsistency in a settled-rewrite section; will confuse downstream readers of 20-04/05/06 who cite the F1 key-decisions block.

**Recommendation.** Delete line 58's tick.work.ms.max numbers (the table at 168-171 is the canonical surface) or sync them to 84/100/98. Cheap edit.

### Y3 — Caveat #3 "not deprecated" contradicts Deferred section line 250
**Claim.** Caveat #3 (line 234-236) says "**Both are live tick-cost meters; neither is deprecated.**" Deferred line 250 says "Wire up `paralife.tick.health.work-time-ms` MAINTENANCE-mode write path | **Deprecated scalar**; replaced by `tick.work.ms` DistributionSummary."

**Cite.** SUMMARY line 236 vs line 250. Source (`AdmissionMetrics.java:70,167-169,428`) does not mark the meter `@Deprecated`.

**Severity.** YELLOW. Wording drift inside the same document, introduced when caveat #3 was rewritten but Deferred row wasn't touched.

**Recommendation.** Pick one. Either retract "deprecated" from the Deferred row, or keep "deprecated" and rewrite caveat #3 to say "scalar is live but superseded by `tick.work.ms`; retained for SHA-continuity." The Deferred row's "MAINTENANCE-mode write path" framing is also stale — `TickHealthMonitor.onTick` already writes the gauge every tick per caveat #3, so there's no remaining wire-up work.

### Y4 — `enc.cnt` vs `frame.size.bytes` COUNT gap framing inverted
**Claim.** Headline-table caption (line 88): "`enc.cnt` is the Timer COUNT (drainLoop iterations reaching `Timer.start()`); `frame.size.cnt` increments only after successful `PerceptionCodec.encode()` returns. Gap (0.005-0.6 % across tiers) reflects encode-failure frames caught at `OutboundSender.java:329` — benign at observed scale."

Reality from sidecars at the final sample:
| Tier | enc.cnt | frame.size.cnt | Gap |
|---|---|---|---|
| 100 | 5,825 | 5,825 | 0 |
| 500 | 33,429 | 33,429 | 0 |
| 1000 | 75,940 | 75,940 | 0 |

Looking at earlier samples:
- 100-tier sample 6: enc=4787 vs frame=4687 → +100 enc
- 1000-tier sample 1: enc=24,336 vs frame=24,329 → +7 enc

Gap exists but it's NOT encode-failure frames. Reading `OutboundSender.drainLoop:308-338`: `Timer.start()` runs at line 307, then `encode` at 309, then `getBytes` + `recordFrameSize` at 310-311. If `encode` throws `CodecException` (RuntimeException), the `catch RuntimeException` at line 329 runs, `finally` stops the Timer. **In that path `recordFrameSize` was never called.** So when encode succeeds and `sendMessage` throws IOException, frame.size IS recorded; encode-failure cases are the only place where Timer > frame_size.

But the 1000-tier final has zero gap while sample 1 has +7. That means encode failures only happened in *some* samples, not steadily. More likely explanation for the gap: **Timer.Sample.stop is recorded synchronously inside the drain VT, but `recordFrameSize` writes to a separate `DistributionSummary` that may be flushed under a different lock — small under-count on the scrape side rather than encode failures.** Or simpler: scrape race — Timer COUNT incremented in the drain VT's call to `sample.stop()` after `recordFrameSize`, but the scrape happens to read Timer COUNT before `frame_size` COUNT is updated for a few in-flight frames.

**Severity.** YELLOW. Caption attributes the gap to a mechanism (encode failures) that's plausible but not the only candidate; closing gap at later samples is hard to reconcile with steady encode-failure rate. Either verify with a one-shot grep on the logs (`Send error for session=` warns at line 330) to confirm if any RuntimeException fired in the 1000-tier run, or rewrite the caption as "small per-sample timing skew between Timer COUNT and DistributionSummary COUNT updates; encode failures (if any) would also widen the gap."

**Cite.** Sidecars + `OutboundSender.java:307-338`.

**Recommendation.** Spot-check the 1000-tier log for `"Send error for session="` warns; if zero, rewrite the gap caption to attribute to scrape-timing skew, not encode failures.

## GREEN

### G1 — H1 skip-when-null is mechanically correct for the four enumerated paths
Walked each path against `WorldWebSocketHandler.cleanupBot:885-940` + `markDead:995-1013` + `markStalled:712-766` + `cleanupByEntityId:779-835`:
- **Path A (alive at close):** `entityId` non-null. Snapshot lookup → dec via `decActiveBucketByTags` → `releaseBucketTags`. Slot released via line 916. Net +1 inc / −1 dec ✓.
- **Path B-no-reconnect (afterConnectionClosed runs before grace):** markStalled clears entityId, no slot release. `afterConnectionClosed` line 388-398 wasStalled early-return → cleanupBot NOT called. Session unregistered (`:382`). grace-expire → `cleanupByEntityId` → `sessionRegistry.getSession(sessionId)` returns null → manual-cleanup branch (line 819-834) decs active + releases snapshot + releases slot. Net +1/−1 ✓.
- **Path B-with-reconnect:** rebind (line 504-512) decs OLD stalled + active, re-incs active on new session, recaptures snapshot. Eventual cleanupBot path A decs once. Net 0 ✓.
- **Path C (markDead then close, no respawn):** markDead decs via snapshot + releases (line 1004-1007), keeps `ATTR_ENTITY_TYPE` intact. cleanupBot: `entityId==null` → H1 skip; `wasRegistered=true` → slot released. Net +1/−1 ✓ (no double-dec — pass-2 R1 fixed).

Slot accounting orthogonal: line 916 fires on `wasRegistered` only — H1 doesn't touch that gate.

### G2 — Pass-2 R2 SUMMARY rewrite factually accurate
Sidecar `paralife.tick.health.work-time-ms` values verified against caveat #3:
- 100-tier: 12, 8, 14, 7, 6, 7 → range **6-14** ✓
- 500-tier: 33, 27, 29, 34, 29, 29 → range **27-34** ✓
- 1000-tier: 51, 39, 37, 39, 60, 39 → range **37-60** ✓

Caveat #3 + Pushback (line 193-199) correctly retract the hallucination framing, identify the underscore-vs-hyphen `jq` bug as root cause, and cite `TickHealthMonitor.onTick` writing the AtomicLong every tick. No defensiveness. (Y3 above is a separate consistency issue between caveat #3 and Deferred section, not a factual error.)

### G3 — Pass-2 Y1 active.entities trajectory pushback re-derived against 62c1b44
Sidecar trajectory: 968 → 1000 → 1000 → 997 → 972 → 901 (samples 1-6). SUMMARY line 185-187 matches sidecar exactly. Population sits at ≥97% capacity through samples 1-4; the 972→901 tail (samples 5-6) coincides with `rejected{respawn-cap}` rising from 28→99 — bots exhausting `maxRespawnsPerSession=5` and exiting. The 10× ramp is real both for connection count and sustained live population. Direct gauge evidence stronger than the previous window-arithmetic pushback. ✓

### G4 — Regression sidecar checks
| Check | 100-tier | 500-tier | 1000-tier |
|---|---|---|---|
| `rejected.availableTags.reason == ["respawn-cap"]` only | null (no rejections) | null (no rejections) | `respawn-cap` at samples 4-6 (3/28/99), no `world-full` ✓ |
| `qmax` non-NaN at every sample | 0.0 × 6 | 0.0 × 6 | 0.0 × 6 ✓ |
| `tick.health.work-time-ms` numeric at every sample | 12/8/14/7/6/7 | 33/27/29/34/29/29 | 51/39/37/39/60/39 ✓ |
| `detach.timeout = 0` at every sample | ✓ | ✓ | ✓ |
| `peak_registered = --count` | trust harness report (not in metric sidecar; SUMMARY verification gate #4 claim) | | |

All five regression gates pass on the sidecars I read directly.

### G5 — H1 fix preserves D2 + D3
- `OutboundSender.drainLoop:331-337`: `sample.stop` still wrapped in `try { ... } catch RuntimeException` — no D2 regression.
- `AdmissionMetrics.registerOutboundQueueDepthMaxGauge:484-493`: first-supplier-wins guard preserved + `qmax` reads 0.0 (not NaN) across all 18 samples — D3 still effective.

### G6 — meta.json 62c1b44 carries D4 fields + corrected A8 + cap_during_run
`jfr-1000bots-baseline-62c1b44.meta.json` lines 9-11: `cap_during_run: 1500`, `asprof_cpu_interval_us: 10000`, `asprof_alloc_interval_bytes: 524288`. A8 wording at line 17 mentions G1 default / `-XX:+UseZGC` / `-XX:+ZGenerational` / "default in JDK 23 only" — matches SUMMARY key-decisions ✓.

### G7 — F2 Timer arithmetic checks
1000-bot sample 6: TOTAL_TIME=5.754173 s ÷ COUNT=75,940 = 75.8 µs ≈ 76 µs ✓. MAX=0.016490 s ≈ 16.5 ms ✓. SUMMARY F2 and the headline table consistent with sidecar.

---

## Recommendation

No merge blockers. Y1 is the only finding with structural correctness implications and the race window is narrow in production. Suggest landing the ~5 LOC defensive fix + test for Y1 in a follow-up commit (or in this PR if cheap) and adding it to STATE.md as TD-20-01c-D if deferred. Y2/Y3/Y4 are doc-only polish — fix in a single SUMMARY edit pass.

Pass-2 findings (D1 markDead leak, R2 tick.health rewrite, Y1 trajectory pushback, qmax/respawn-cap regression checks) all properly addressed at 62c1b44.

---

## Gemini Review

Here is the Cross-AI Review for Pass-3 of Plan 20-01c.

# Cross-AI Review

## Gemini Review

### 🟡 YELLOW (should-fix or document)

**R2 — `tick.health.work-time-ms` un-deprecated instead of preserving prior framing**
- **State the claim**: The SUMMARY rewrite correctly retracts the hallucination claim and accurately explains the numeric values by identifying the `jq` typo, but explicitly un-deprecates the metric instead of preserving the requested "deprecated, kept for SHA-continuity" framing.
- **Cite the source**: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md` Caveats §3 ("Both are live tick-cost meters; neither is deprecated") and Pushback section.
- **Severity**: YELLOW
- **Recommendation**: Change "neither is deprecated" to "the scalar is deprecated in favor of the DistributionSummary, but retained for SHA-to-SHA continuity" to match the prior established architectural intent, or formally accept the un-deprecation as a conscious decision.

### 🟢 GREEN (LGTM observation)

**H1 — `cleanupBot` skip-when-null prevents double-decrement without leaking slots**
- **State the claim**: The H1 fix correctly skips the active-bucket decrement in `cleanupBot` when `entityId == null` (handling both Path B and Path C), effectively preventing the double-decrement while leaving the `releaseBucketTags` responsibility safely delegated to `markDead` or `cleanupByEntityId`.
- **Cite the source**: `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:932-943`
- **Severity**: GREEN
- **Recommendation**: No action, just observation. The snapshot map `bucketTagsByEntityId` cannot grow unbounded because the snapshot release is cleanly handled by the other callsites prior to or during the entityId-null state.

**Y1 — `active.entities` trajectory pushback is empirically sound**
- **State the claim**: The rebuilt 10x scale pushback correctly utilizes the `active.entities` trajectory to empirically prove a sustained full population, completely aligning with the post-H1 sidecar data and eliminating the need for fragile frame-count arithmetic.
- **Cite the source**: `.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md` (Pushback) and `metrics-1000bots-baseline-62c1b44.json` (samples 1-6 `active.entities` = 968, 1000, 1000, 997, 972, 901).
- **Severity**: GREEN
- **Recommendation**: No action, just observation. The trajectory perfectly captures the +50 unit delta recovered by the H1 fix for path-C exits, and correctly supports the scale arguments.

**R4 — Regression checks against 62c1b44 sidecars pass cleanly**
- **State the claim**: The new 62c1b44 sidecars exhibit no regressions: rejections are exclusively `respawn-cap` at the 1000-tier (zero `world-full`), `qmax` is non-NaN, and `tick.health.work-time-ms` is correctly written as a numeric value across all tiers.
- **Cite the source**: `metrics-1000bots-baseline-62c1b44.json` (Sample 6: `reason=["respawn-cap"]`, `paralife_outbound_queue_depth_max=0.0`, `paralife_tick_health_work-time-ms=39.0`).
- **Severity**: GREEN
- **Recommendation**: No action, just observation. The headline `peak_registered = --count` assertions also perfectly match expectations.

---

## Codex Review

**RED**

**R-P3-1 — H1 leaves one `entityId == null` cleanup path with no active-bucket decrement.**  
Source: [WorldWebSocketHandler.java:813](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:813) calls `cleanupBot(session)` when grace expiry finds the old stalled session still registered; [cleanupBot:919](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:919) now skips all active-bucket decrement/release work when `entityId == null`.  
Severity: RED.  
Recommendation: in `cleanupByEntityId`, if `session != null` but the session no longer has `ATTR_ENTITY_ID`, decrement active via the already-captured `bucketTags` and release the snapshot before/around `cleanupBot`; otherwise that branch leaks both active count and `bucketTagsByEntityId`.

**R-P3-2 — SUMMARY tick-health rewrite is still internally contradictory.**  
Source: [20-01c-SUMMARY.md:108](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:108) still says `tick.health.work-time-ms` reads `null`; [20-01c-SUMMARY.md:193](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:193) and [20-01c-SUMMARY.md:236](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:236) correctly retract that; [20-01c-SUMMARY.md:250](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:250) again calls it unwired/deprecated. Sidecars show numeric values in all 18 samples, and [TickHealthMonitor.java:61](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/TickHealthMonitor.java:61) writes the gauge every tick.  
Severity: RED.  
Recommendation: remove the stale `null` and “wire up” deferred text. Use one framing consistently: legacy/live scalar retained for SHA continuity, with `tick.work.ms` as the canonical DistributionSummary if that is the intended direction.

**YELLOW**

**Y-P3-1 — Caveat #2 still carries the old 0824f1a rejection count.**  
Source: [20-01c-SUMMARY.md:235](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:235) says 58 `respawn-cap` rejections; `metrics-1000bots-baseline-62c1b44.json` sample 6 has `paralife_admission_rejected COUNT=99`, `reason=respawn-cap`.  
Severity: YELLOW.  
Recommendation: update the caveat to 99, matching the headline and sidecar.

**Y-P3-2 — `enc.cnt` vs `frame.size.cnt` caption over-attributes transient gaps to encode failures.**  
Source: [20-01c-SUMMARY.md:88](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:88). The 62c1b44 sidecars show transient gaps that later disappear, e.g. 100-bot sample 5 `enc=4787`, `frame=4687`, then sample 6 both `5825`; 1000-bot sample 1 gap 7, then sample 2 equal. A true cumulative encode-failure gap should not heal.  
Severity: YELLOW.  
Recommendation: describe the gap as endpoint scrape skew or in-flight measurement unless a persistent cumulative gap is present.

**GREEN**

**G-P3-1 — H1 is correct for the main cleanup paths.**  
Source: alive close uses snapshot dec/release at [cleanupBot:919](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:919); death path decs/releases in [markDead:997](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:997) and cleanup skips at [cleanupBot:935](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:935); normal stalled close unregisters the session and grace expiry decs/releases in [cleanupByEntityId:817](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:817).  
Severity: GREEN.  
Recommendation: no action beyond the RED edge-branch fix.

**G-P3-2 — New sidecar regression checks pass.**  
Source: all 18 `62c1b44` samples have `qmax=0` and numeric `tick.health`; rejection reason is only `respawn-cap` at 1000-tier; harness reports in `/tmp/p20-01c-capture/tier-*` show `peak_registered=100/500/1000` and `connect_failures_total=0`.  
Severity: GREEN.  
Recommendation: no action.

**G-P3-3 — Active population pushback is now grounded in the gauge, not fragile frame arithmetic.**  
Source: `metrics-1000bots-baseline-62c1b44.json` `active.entities` trajectory is `968 → 1000 → 1000 → 997 → 972 → 901`.  
Severity: GREEN.  
Recommendation: no action; this is the right basis for the “10x population is real” pushback.

---

## Opencode Review

Now let me read the sidecar JSONs and the full drainLoop to verify the claims:Now let me check the remaining sidecars and key code paths:Let me verify the markStalled code path and check the `cleanupByEntityId` sessionId==null branch more closely:Now let me verify the stale doc references precisely:I have all the evidence. Here is the pass-3 review:

---

# Pass-3 Multi-Review: 20-01c H1/R2/Y1 Remediation (HEAD `62c1b44`)

## RED

### R-P3-1 — F2 narrative at line 108 contradicts Caveat #3

**Claim:** The F2 section body at line 108 still says `"it reads null in the 20-01c sidecars — see Caveat #3 below"`, but Caveat #3 at line 236 correctly retracts this and says the gauge emits numeric values in all 18 samples.

**Source:** `20-01c-SUMMARY.md:108` vs `20-01c-SUMMARY.md:236`

**Evidence:** 
- Line 108: `"note: it reads null in the 20-01c sidecars"`
- Line 236: `"The meter emits numeric values in all 18 samples across all three tiers (100-tier 6-14 ms, 500-tier 27-34 ms, 1000-tier 37-60 ms at 62c1b44)"`
- Sidecar confirms: 1000-bot samples = 51/39/37/39/60/39, all numeric

**Severity:** RED — contradictory statements in the same document. The pass-2 retraction updated Caveat #3 and the Pushback section but missed this stale sentence in the F2 body. A reader stopping at line 108 won't know the gauge is live.

**Recommendation:** Change line 108 to: `"(note: the scalar emits numeric values in all sidecar samples — see Caveat #3; the new Timer is the actually-useful encode+send measurement)"`.

---

### R-P3-2 — Deferred-items line 250 contradicts Caveat #3's "not deprecated"

**Claim:** The deferred-items table at line 250 says `"Deprecated scalar; replaced by tick.work.ms DistributionSummary"` but Caveat #3 at line 236 explicitly says `"not deprecated. Both are live tick-cost meters."`

**Source:** `20-01c-SUMMARY.md:250` vs `20-01c-SUMMARY.md:236`

**Severity:** RED — same internal contradiction as R-P3-1. Fixing line 108 leaves this second stale reference.

**Recommendation:** Change line 250 to: `"Wire up |paralife.tick.health.work-time-ms| scalar (live alongside |tick.work.ms| DistributionSummary — see Caveat #3)"` or delete the row entirely since it contradicts Caveat #3's assertion that the write path is already wired.

---

## YELLOW

### Y-P3-1 — "~50 s of capture window" (line 74) is loose

**Claim:** Line 74 says `"~75.9 k frames captured at the 1000-tier through ~50 s of capture window."` The actual metered scrape window (sample 1 UTC to sample 6 UTC) is 26 seconds (22:12:13 → 22:12:39). The `~50 s` figure apparently refers to approximate time from harness-ready to last scrape, not the MeterRegistry scrape window actually used.

**Source:** `20-01c-SUMMARY.md:74`; `metrics-1000bots-baseline-62c1b44.json` samples 1–6 UTC

**Severity:** YELLOW — directionally not wrong but confusing. The `enc.cnt` arithmetic (75,940 frames / 26 s scrape window = ~2,920 frames/s; 1,000 bots × 2 Hz = 2,000 frames/s expected) actually works better with 26 s.

**Recommendation:** Replace `"~50 s"` with `"~26 s of metered scrape window"` to match the actual sidecar timestamps, or quote the meta.json `captured_utc` → `captured_utc_end` span of ~227 s and label it "wall-clock window."

---

### Y-P3-2 — releaseBucketTags fragile-invariant risk after H1

**Claim:** H1 removes `releaseBucketTags(entityId)` from the `cleanupBot` null-entityId path. All three null-entityId paths (path C markDead, path B-no-reconnect cleanupByEntityId, path B-with-reconnect cleanupBot with entityId present) have alternative release callsites — verified no unbounded growth. However, any future lifecycle exit path that sets entityId=null AND enters cleanupBot without a prior release will silently leak entries in `bucketTagsByEntityId`.

**Source:** `WorldWebSocketHandler.java:919-940` (H1 skip block), `WorldWebSocketHandler.java:1007` (markDead release), `WorldWebSocketHandler.java:804,823` (cleanupByEntityId release)

**Severity:** YELLOW — not a current bug, but the invariant is split across 3 callers with no enforcement.

**Recommendation:** Add a defensive `releaseBucketTags(entityId)` before the `entityId==null` guard in cleanupBot (no-op when null) as a safety net, OR add a `bucketTagsSize()` gauge exposed to `/actuator/metrics` for leak detection, OR at minimum add a comment: `"// H1: entityId==null means releaseBucketTags was already called by markDead or cleanupByEntityId."`

---

### Y-P3-3 — `enc.max` / `tick.max` headline columns are since-registration MAX, unqualified in table

**Claim:** The headline table columns `enc.max` and `tick.max` show single values per tier (20/14/16 ms and 84/100/98 ms). These are the DistributionSummary.MAX across all samples (the same value repeats in all 6 sidecar samples per tier because it's the highest observed since meter creation). The table does not label these as "since-registration max."

**Source:** `20-01c-SUMMARY.md:80-84`; `metrics-1000bots-baseline-62c1b44.json` — tick.work.ms MAX = 97.918322 in all 6 samples; enc.send.ms MAX = 0.014841637 in samples 1–3, then possibly higher in samples 4–6 (sample 6 MAX = 0.016490071 = 16.5 ms)

**Severity:** YELLOW — correctly described in verification gate #5 (line 227) but unqualified in the headline table that downstream plans (20-04/05/06) will cite.

**Recommendation:** Add a parenthetical to the table caption: `"enc.max and tick.max are since-registration DistributionSummary.MAX, not per-sample peaks."`

---

## GREEN

### G-P3-1 — H1 skip-when-null verified correct for all 4 lifecycle paths

**Path A (alive at close):** entityId non-null → `lookupBucketTags` → `decActiveBucketByTags` → `releaseBucketTags`. Single net dec. ✓

**Path B-no-reconnect:** `markStalled` clears entityId → session close returns early (`wasStalled=true`) → grace expire fires `cleanupByEntityId` which decs + releases via snapshot. The `cleanupByEntityId:798` sessionId==null branch (lines 800-804) and the `:816` session-found→cleanupBot path (entityId==null → H1 skip) both deliver single net dec. ✓

**Path B-with-reconnect:** `markStalled` clears entityId → rebind at line 504-511: `decActiveBucketByTags(oldTags)` + `incActiveBucket(session)` (new snapshot captured) → eventual close: cleanupBot with entityId present → `lookupBucketTags` → `decActiveBucketByTags` → `releaseBucketTags`. Single net dec. ✓

**Path C (dead at close):** `markDead:997` removes `ATTR_ENTITY_ID` → `lookupBucketTags` → `decActiveBucketByTags` → `releaseBucketTags` (single dec+release). Later `cleanupBot`: `entityId==null` → H1 skips. Single net dec. ✓

**No slot-leak.** When `wasRegistered=true` and `entityId==null`, either `markDead` (path C) or `cleanupByEntityId` (path B) has already decremented. H1 prevents the double-dec that existed at 0824f1a.

**Source:** `WorldWebSocketHandler.java:885-940`, `WorldWebSocketHandler.java:779-835`, `WorldWebSocketHandler.java:995-1013`

---

### G-P3-2 — tick.health retraction correct in Pushback + Caveat #3

The Pushback paragraph (lines 193-199) explicitly retracts: `"The original codex+opencode reports were correct; the pushback was wrong."` Explains the jq underscore-vs-hyphen root cause. Caveat #3 (line 236) states `"paralife.tick.health.work-time-ms is live, not deprecated"` and `"Both are live tick-cost meters; neither is deprecated."` Spot-check: all 18 sidecar samples carry numeric values (12→60 ms range). The retraction is complete in these two sections. (Subject to R-P3-1 and R-P3-2 above.)

---

### G-P3-3 — active.entities trajectory directly proves sustained full population

62c1b44 1000-tier trajectory: 968 → 1000 → 1000 → 997 → 972 → 901. Samples 1-4 show population within ±3% of 1,000. The descent at samples 5-6 (972→901) reflects respawn-cap-exhausted bots exiting — consistent with 99 `respawn-cap` rejections accumulated. The pass-2 recommendation to replace window arithmetic with direct gauge evidence was implemented correctly (lines 181-187).

**Source:** `20-01c-SUMMARY.md:181-187`; `metrics-1000bots-baseline-62c1b44.json` samples 1–6

---

### G-P3-4 — All 5 regression checks pass against 62c1b44 sidecars

| Check | 100-tier | 500-tier | 1000-tier |
|---|---|---|---|
| `rejected` reason `respawn-cap` only (no `world-full`) | ✓ (null — meter never written) | ✓ (null) | ✓ (3/28/99, `respawn-cap` only) |
| `qmax` non-NaN at every sample | ✓ (0.0 × 6) | ✓ (0.0 × 6) | ✓ (0.0 × 6) |
| `tick.health.work-time-ms` numeric at every sample | ✓ (6–14 ms) | ✓ (27–34 ms) | ✓ (37–60 ms) |
| `peak_registered = --count` | ✓ (100) | ✓ (500) | ✓ (1,000) |
| `detach.timeout = 0` at every tier | ✓ | ✓ | ✓ |

**Source:** All three `metrics-*.json` sidecars

---

### G-P3-5 — D2 drainLoop isolation correct; detach.timeout confirms no regression

`OutboundSender.java:331-337` wraps `sample.stop(...)` in `try/catch RuntimeException` inside the existing `finally`. The drain VT survives Micrometer histogram-rotation / registry-shutdown races, losing only that frame's timer sample (`log.warn`). `paralife.outbound.detach.timeout = 0` across all 18 samples confirms the D2 change introduced no drain-VT-exit latency regressions.

---

### G-P3-6 — Timer.Sample exit paths all covered

`OutboundSender.java:300-338`: `queue.take()` throws `InterruptedException` before `Timer.start()` → un-timed ✓. `session.isOpen()==false` → `continue` before `Timer.start()` → un-timed ✓. `encode` / `getBytes` / `sendMessage` / listener throw → caught in inner catch blocks, `finally { sample.stop() }` fires ✓. Encode-failure frames captured by Timer COUNT but not `frame.size.bytes` COUNT — documented in headline notes (line 88) ✓.

---

### G-P3-7 — meta.json carries cap_during_run + asprof intervals

`jfr-1000bots-baseline-62c1b44.meta.json` confirms: `cap_during_run: 1500`, `asprof_cpu_interval_us: 10000`, `asprof_alloc_interval_bytes: 524288`, `jvm_flags` includes `-Dparalife.admission.cap=1500`. Assumptions A8 and F1_cap_override verified ✓.

---

### G-P3-8 — SUMMARY F1 rewrite, D-20 separation, rejection-column relabel, bucketTagsByEntityId release verified

- F1 cap narrative: world-aggregate, `AdmissionGate.java:58` single `AtomicInteger`, nutrients bypass via `SimulationEngine.setEntity:1431`. No per-bucket-cap language remains ✓
- Rejection column: `rejected{respawn-cap}` ✓
- D-20: `application.yml:65 cap=256` unchanged; `cap_during_run=1500` in meta.json. D-20 binding correctly scoped to `outbound-queue-size` ✓
- `releaseBucketTags` not called from cleanupBot null-entityId path but all null paths have alternative release. `bucketTagsByEntityId` cannot grow unbounded per path analysis ✓

---

## Summary

| ID | Severity | What | Action |
|---|---|---|---|
| **R-P3-1** | **RED** | F2 body line 108 says "reads null" — contradicts Caveat #3 retraction | Rewrite line 108 to match Caveat #3 |
| **R-P3-2** | **RED** | Deferred items line 250 says "Deprecated scalar" — contradicts Caveat #3 | Rewrite or delete row |
| Y-P3-1 | YELLOW | "~50 s capture window" is loose (actual scrape window = 26 s) | Replace with "~26 s metered window" |
| Y-P3-2 | YELLOW | releaseBucketTags invariant fragile after H1 | Add defensive release or comment |
| Y-P3-3 | YELLOW | enc.max/tick.max unqualified as since-registration MAX in headline table | Add qualifier to table caption |
| G-P3-1 | GREEN | H1 skip-when-null correct for all 4 paths | No action |
| G-P3-2 | GREEN | tick.health retraction correct in Pushback + Caveat #3 | No action (subject to R-P3-1/2) |
| G-P3-3 | GREEN | active.entities trajectory proves full population | No action |
| G-P3-4 | GREEN | All 5 regression checks pass | No action |
| G-P3-5 | GREEN | D2 drainLoop isolation correct | No action |
| G-P3-6 | GREEN | Timer.Sample exit paths covered | No action |
| G-P3-7 | GREEN | meta.json carries cap + asprof intervals | No action |
| G-P3-8 | GREEN | F1/D-20/columns all correct | No action |

**Verdict:** H1 is structurally correct for all 4 lifecycle paths. The active.entities trajectory in the 62c1b44 sidecar directly proves sustained full population. All regression checks pass. The tick.health pushback retraction is correct in Caveat #3 and Pushback §, but two stale sentences (F2 body at line 108, Deferred items at line 250) still carry the pre-retraction "null"/"deprecated" framing. Fix those two lines before merge.

---

## Consensus Summary

### Agreed Strengths
- **H1 skip-when-null is mechanically correct** for the four enumerated lifecycle paths (alive-close, path-B-no-reconnect, path-B-with-reconnect, path-C dead-then-close). All four reviewers walked the paths against `WorldWebSocketHandler.java` and confirmed net +1/−1 with no double-decrement and no slot leak in the main flows. (claude G1, gemini H1, codex G-P3-1, opencode G-P3-1)
- **`active.entities` trajectory pushback (Y1) is empirically sound.** Sidecar samples `968 → 1000 → 1000 → 997 → 972 → 901` directly prove sustained near-capacity population; trajectory-based evidence is materially stronger than the prior window-arithmetic argument. (all four reviewers)
- **Regression gates pass on 62c1b44 sidecars:** `rejected` reason `respawn-cap` only (no `world-full`), `qmax` non-NaN (0.0) across all 18 samples, `tick.health.work-time-ms` numeric across all tiers, `detach.timeout=0`, `peak_registered = --count`. (claude G4, gemini R4, codex G-P3-2, opencode G-P3-4)
- **R2 tick.health retraction is factually correct** in Caveat #3 + Pushback section — `jq` underscore-vs-hyphen identified as root cause, `TickHealthMonitor.onTick` writes the gauge every tick, numeric ranges 6-14 / 27-34 / 37-60 ms verified. (claude G2, gemini R2, codex R-P3-2 source, opencode G-P3-2)

### Agreed Concerns
- **YELLOW (3 reviewers) — Latent leak in `cleanupByEntityId` if stalled session still in registry.** When grace-expire fires while the stalled session is still in `sessionRegistry` (e.g., `session.close()` threw in `detachSession` and `afterConnectionClosed` never ran), line 813-816 calls `cleanupBot(session)` with `entityId==null` → H1 skip → active bucket NOT decremented and `releaseBucketTags` NOT called. The pre-H1 fallback `decActiveBucket(s)` previously masked this. Fix: in `cleanupByEntityId`'s session-found branch, dec via captured `bucketTags` + release snapshot before calling `cleanupBot`. ~5 LOC + 1 test. (claude Y1, codex R-P3-1 [as RED], opencode Y-P3-2 [as invariant fragility]). Codex classifies as RED; claude/opencode YELLOW — race window is narrow in practice but structurally non-zero.
- **RED/YELLOW (3 reviewers) — SUMMARY internal contradictions on tick.health deprecation status.** Caveat #3 line 236 says "neither is deprecated, both live tick-cost meters", but Deferred items line 250 still says "Deprecated scalar; replaced by `tick.work.ms` DistributionSummary". Opencode additionally flags F2 body line 108 still saying `"reads null in the 20-01c sidecars"` — directly contradicting the retraction. Fix: pick one framing (recommend "live scalar, retained for SHA-continuity") and apply consistently to lines 108, 236, 250. (claude Y3, codex R-P3-2, opencode R-P3-1 + R-P3-2)
- **YELLOW (2 reviewers) — `enc.cnt` vs `frame.size.cnt` gap caption over-attributes to encode failures.** Sidecars show transient gaps that *heal* in later samples (100-tier: +100 sample 5 → 0 sample 6; 1000-tier: +7 sample 1 → 0 sample 2). A genuine cumulative encode-failure gap should not close. More likely cause: scrape-timing skew between Timer COUNT and DistributionSummary COUNT updates. Fix: rewrite caption as "endpoint scrape skew / in-flight measurement; encode failures would also widen the gap" or verify via log grep for `"Send error for session="`. (claude Y4, codex Y-P3-2)
- **YELLOW (2 reviewers, partially) — Stale rejection-count / numeric inconsistencies in SUMMARY.** Codex Y-P3-1: Caveat #2 line 235 still shows 58 `respawn-cap` rejections from 0824f1a; sidecar sample 6 = 99. Claude Y2: key-decisions §6 line 58 shows `tick.work.ms.max` = 83/75/106 ms but the headline table and wall-time table show 84/100/98 ms (sidecar truth: 83.6/99.7/97.9). Both call for sync edits to a single canonical surface.

### Divergent Views
- **Severity of the `cleanupByEntityId` session-in-registry race window (Y1 / R-P3-1).** Codex calls it RED — leaks both active count and `bucketTagsByEntityId` and should block merge. Claude calls it YELLOW — race window is narrow in production (Jetty fires `afterConnectionClosed` promptly after successful `session.close()`) and not observed in current sidecars. Opencode frames it as "invariant fragility" (defensive comment / leak gauge sufficient) rather than a present bug. Resolution path: the 5 LOC fix is cheap regardless; whether to gate merge on it is the open question.
- **Whether the F2/Deferred contradictions are RED or YELLOW.** Codex + opencode call line-108 / line-250 / line-236 inconsistencies RED (document directly contradicts itself; downstream readers will be misled). Claude calls the same inconsistencies YELLOW (doc-only polish). Gemini sees only Caveat #3's "not deprecated" wording as the issue and asks whether to restore "deprecated, retained for SHA-continuity" framing as a conscious decision. Resolution: pick one deprecation framing, apply to all three lines.
- **Capture-window framing for the 1000-bot tier.** Opencode Y-P3-1 flags `"~50 s of capture window"` (line 74) as loose — actual metered scrape window between sample 1 and sample 6 is 26 s; harness wall-clock is ~227 s per meta.json. No other reviewer flagged this. Worth a one-line edit to clarify "metered scrape window" vs "harness wall-clock window".
- **Headline-table column qualification.** Opencode Y-P3-3 wants `enc.max` / `tick.max` labelled as "since-registration MAX" in the headline table since downstream plans (20-04/05/06) will cite the table. No other reviewer raised this; verification gate #5 (line 227) does label it correctly. Minor caption clarification.
