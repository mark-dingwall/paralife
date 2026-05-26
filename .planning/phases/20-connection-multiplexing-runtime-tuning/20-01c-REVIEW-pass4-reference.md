---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-26T14:21:36Z
files: ["src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-active-50xfood-103a615.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-active-50xfood-103a615.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-active-50xfood-103a615.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-active-50xfood-103a615.meta.json"]
usage:
  claude: { input: 40590, output: 2512, cached: 4618795, tool_calls: 26, elapsed_s: 347.6 }
  gemini: { input: 1759886, output: 1574, cached: 1282051, tool_calls: 21, elapsed_s: 380.0 }
  codex: { input: 905837, output: 12269, cached: 789632, tool_calls: 28, elapsed_s: 258.1 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 16, elapsed_s: 247.4 }
synthesizer: claude
synthesized_at: 2026-05-26T14:21:36Z
---

# Cross-AI Review

## Claude Review

All sidecar values checked. Code paths walked. Writing review.

---

# Pass-4 Review — 20-01c (R-P3-1 fix + active-population scenario)

Verified against actual code (`WorldWebSocketHandler`, `AdmissionMetrics`) and all three `*-active-50xfood-103a615.json` sidecars + meta. Caveman mode.

## BLOCKER

**None.** R-P3-1 fix correct across every path. Active-population transport-health table matches sidecars exactly.

## A — R-P3-1 fix correctness (the core delta)

Fix lives at `WorldWebSocketHandler.java:813-826` (session-still-registered branch). Walked every path:

| Path | active dec site | snapshot release | slot release | net |
|---|---|---|---|---|
| **A** alive close | `cleanupBot:936` (snapshot lookup, entityId≠null) | `cleanupBot:943` | `cleanupBot:927` | +1/−1 ✓ |
| **B** stalled, sess **unregistered** (typical) | `cleanupByEntityId:831` (else branch) | `:833` | explicit `:843` | +1/−1 ✓ |
| **B** stalled, sess **still registered** (NEW FIX) | `cleanupByEntityId:821` | `:823` | via `cleanupBot:927` | +1/−1 ✓ |
| **C** dead then close | `markDead:1015` | `markDead:1017` | `cleanupBot:927` | +1/−1 ✓ |

**NEW path mechanics (scrutinised hardest):**
- markStalled cleared `ATTR_ENTITY_ID`, inc'd stalled bucket, kept snapshot. Snapshot survives (`incStalledBucket:319` re-puts under entityId).
- grace-expire → `cleanupByEntityId`: `getSessionByEntity`→sessionId (botRegistry never unregistered); `lookupBucketTags`→non-null.
- `:810` decStalled (matches markStalled inc). `:821` decActive (matches admission inc). `:823` release snapshot.
- `:826` cleanupBot: `eid=remove(ATTR_ENTITY_ID)`→null → skips `entityId!=null` block (`:906`) AND skips dec block body (`:945-948`). `wasRegistered=remove(ATTR_ENTITY_TYPE)`→true → `:927` releaseSlot fires.

Each accounting touched exactly once. No leak, no double-dec.

**Both stalled sub-cases now converge** — the leak Codex/Claude/OpenCode flagged in pass-3 (session-in-registry branch delegating to cleanupBot which skipped) is closed. Pre-H1 fallback `decActiveBucket(s)` no longer needed; manual dec at `:821` replaces it with the *snapshot* tags (correct bucket even across attribution churn), not session-derived tags.

`decActiveBucketByTags`/`decStalledBucketByTags` (`AdmissionMetrics:286,332`) hit independent maps, no floor, no cross-coupling. `releaseBucketTags` (`:359`) idempotent. Confirmed.

## B — Does fix introduce anything new?

- **cleanupBot does NOT also dec.** entityId==null → `:945-948` comment-only skip. ✓ No double-dec.
- **Snapshot not released twice.** Released at `:823`; cleanupBot's `:943` sits inside `if(entityId!=null)` (false) → unreached. ✓
- **Slot ordering vs AdmissionGate unaffected.** Slot release is independent of active-bucket dec; happens in cleanupBot `:927` gated on `wasRegistered`. dec/release-before-cleanupBot ordering is harmless. ✓
- **Idempotent under both close/grace interleavings.** Verified:
  - new-path-first → late `afterConnectionClosed`: `wasStalled=false` (STALL_TICK gone) → normal path → cleanupBot #2 → `wasRegistered=false` (ENTITY_TYPE gone) → no-op. ✓
  - `afterConnectionClosed`-first (wasStalled early-return, unregisters sessionRegistry only) → grace-expire → `getSession`→null → else branch. ✓
  - double grace-expire → snapshot already released → `bucketTags=null` → all decs guarded off. ✓

  `ATTR_ENTITY_TYPE` marker is the load-bearing idempotency guard; if new branch is reached, botRegistry binding still present ⇒ no prior cleanupBot ⇒ marker intact ⇒ slot released exactly once. Sound.

## C — Active-Population SUMMARY vs sidecars

Transport-health table (SUMMARY:115-119) — **every cell verified against sidecars:**

| field | 100 | 500 | 1000 | verdict |
|---|---|---|---|---|
| active.entities range | 96–100 | 461–500 | 889–1000 | ✓ all match (min/max exact) |
| tick.work MAX | 127.33→127 | 92.82→92 | 116.36→116 | ✓ (consistent truncation) |
| tick.health MAX | 32 | 49 | 84 | ✓ (sample-level max) |
| enc MAX | 20.58→20 | 18.43→18 | 34.86→34 | ✓ |
| qmax / detach | 0 / 0 | 0 / 0 | 0 / 0 | ✓ all 18 samples |
| rejected reason | respawn-cap | respawn-cap | respawn-cap | ✓ no world-full anywhere |

meta confirms provenance: cap=1500, food=0.05, window 90s, ramp 20s, `nutrient-spawn-probability=0.05` JVM flag. ✓

## MEDIUM

**M1 — `0.6–0.66 actions/bot/tick` doesn't reconcile with SUMMARY's own `actions_sent` column.** `SUMMARY:124`.
Using SUMMARY's own numbers + stated 500 ms tick (`:122`) + 90 s window (`:112`) = ~180 ticks:
- 100: 24 680 / (100 × 180) = **1.37**
- 500: 119 852 / (500 × 180) = **1.33**
- 1000: 227 828 / (1000 × 180) = **1.27**

Frame-count cross-check agrees: 1000-tier `frame.size COUNT`=180 208 over ~91 s window (sample1→18) ÷ ~960 avg-pop ≈ 188 ticks ⇒ actions/frame = 227 828/180 208 ≈ **1.26**. Every route lands ~1.3, roughly **2× the stated 0.6–0.66** — no denominator I can derive (90 s, 130 s, or frame-derived tick count) yields 0.66.
The conclusion ("action path genuinely exercised") survives either way, so not a blocker. But the figure is wrong or its denominator is undocumented.
**Fix:** show the arithmetic (numerator, denominator) or recompute. If the intent was actions ÷ (peak_bots × full-130 s-run), state that — still gives ~0.9, not 0.66.

## LOW / backlog

**L1 — Several §Active-Population figures are not sidecar-backed (harness-report / JFR-HTML sourced, not in provided inputs).** Cannot verify against what was supplied:
- `peak_reg` 100/500/1000 (`:115-119`) — harness `--report-out`. (active.entities reaching each tier max is consistent, but peak_reg itself isn't in the metric sidecars.)
- `actions_sent` 24 680 / 119 852 / 227 828 (`:117-119,124`) — harness report.
- `connect_failures_total`/`e408_reconnect_required_total` = 0 (`:122-123`) — harness report.
- CPU `jdk.ExecutionSample` table (`:135-142`, ~1044/~876/etc.) — from `*-active-50xfood-103a615.html` flamegraphs; only `.meta.json` was provided, not `.html`/`.jfr`.

Not claiming wrong — flagging that the provided sidecar set can't substantiate them. If reviewers downstream are meant to verify, the harness reports + flamegraph HTML should travel with the prose. The qualitative "hot path flips to transport" claim is plausible and matched by the transport table (enc MAX 16→34 ms, drainLoop pressure) but the specific sample counts are unaudited here.

**L2 — `enc MAX` / `tick.work MAX` columns are since-registration DistributionSummary MAX, not per-sample peaks** (carried from pass-3 OpenCode Y-P3-3, still present in the new table). 500-tier enc MAX climbs 0.0154→0.0184 across run; the table shows only the terminal 18. Minor; caption could qualify. Backlog.

## Settled-item note

Pass-3 R-P3-1 (the RED) is **fixed and correct** — primary purpose of this pass. Pass-3 doc-consistency items (tick.health null/deprecated, stale 58→99, tick.max prose) were out of this delta's scope; not re-litigated per prompt. Did not spot-check whether they were also cleaned — flag if you want a separate confirming pass.

**Verdict: ship.** Fix is mechanically correct on every path; transport table is exact against sidecars. M1 (actions-rate arithmetic) and L1 (unverifiable harness/JFR figures) are profiling-prose cleanups, not merge gates.

---

## Gemini Review

# Pass 4 Cross-AI Review — 20-01c (Baseline Rebuild)

## RED / BLOCKERS
*(None)*

I found no blockers. The H1 skip-when-null fix logic combined with the pass-3 R-P3-1 remediation correctly balances all accounting across all lifecycle paths. The active-population profiling data matches the sidecars.

## MEDIUM / YELLOW
*(None)*

## GREEN / LGTM

### G1 — R-P3-1 Fix Correctness (`cleanupByEntityId` accounting)
The race condition where grace-expire fires while the stalled session is still in the `sessionRegistry` is completely sealed. I walked all paths line-by-line:
- **Path A (alive close):** `cleanupBot` owns the dec and release (`entityId != null`). (Net: 1)
- **Path B-no-reconnect (stalled → detach → close):**
  - Sub-case 1 (session already unregistered): `cleanupByEntityId` manual branch owns the dec + release at `WorldWebSocketHandler.java:829-832`. (Net: 1)
  - Sub-case 2 (session still registered — **the R-P3-1 fix path**): `cleanupByEntityId` now explicitly decrements and releases the snapshot *before* calling `cleanupBot` (`WorldWebSocketHandler.java:819-822`). When `cleanupBot` subsequently runs, it correctly hits its `entityId == null` guard (as `markStalled` cleared `ATTR_ENTITY_ID` at line 734) and skips the duplicate decrement and release (`WorldWebSocketHandler.java:932-943`). (Net: 1)
- **Path B

---

## Codex Review

**BLOCKER**

None. The active-bucket/snapshot fix for `R-P3-1` is correct: I found no active gauge leak, double-decrement, or double `releaseBucketTags` in the requested paths.

**MEDIUM**

1. [WorldWebSocketHandler.java:813](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:813) still leaves a non-metric cleanup gap on the same rare still-registered stalled-session branch.

The new branch correctly does:

```java
admissionMetrics.decActiveBucketByTags(bucketTags);
admissionMetrics.releaseBucketTags(entityId);
cleanupBot(session);
```

at [lines 820-826](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:820). But `markStalled` cleared `ATTR_ENTITY_ID` at [line 734](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:734), so `cleanupBot` cannot unregister from `LiveEntityRegistry`; that only happens when `entityId != null` at [line 923](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:923). The session-unregistered branch does explicitly unregister at [line 841](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:841).

Mechanism: rare grace-expiry-while-session-still-registered clears grid and BotRegistry via `cleanupBot`, releases slot, and fixes active metrics, but leaves `LiveEntityRegistry` with a stale entity id. Recommended fix: add `if (liveEntityRegistry != null) liveEntityRegistry.unregister(entityId);` in the `session != null` branch before or after `cleanupBot(session)`, plus a test for that branch.

2. [20-01c-SUMMARY.md:93](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:93) includes active-workload numeric claims not supported by the reviewed sidecars/meta.

The active sidecars support the main metric table: active ranges, `qmax=0`, detach `0`, `respawn-cap` rejection reason, and maxes. The meta files support `103a615`, 90 s profile window, 20 s ramp, cap 1500, and food `0.05`.

But these figures are not in the listed sidecars/meta: death diagnostics at [lines 94-107](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:94), `actions_sent` and `0.6-0.66 actions/bot/tick` at [lines 117-124](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:117), and `jdk.ExecutionSample` counts at [lines 132-142](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:132). Recommended fix: either commit/cite the harness reports, death-diagnostics output, and JFR extraction used for those numbers, or mark them as external/unreviewed provenance.

**LOW / backlog**

[20-01c-SUMMARY.md:117](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:117) floors some max values. Sidecars show `tick.work.max`: `127.328973 / 92.820399 / 116.364729 ms`; `enc.max`: `20.583106 / 18.427610 / 34.862309 ms`. The table says `127 / 92 / 116` and `20 / 18 / 34`. Use one decimal or round to nearest to avoid understating maxima.

**Accounting confirmation**

Path A normal alive close: `cleanupBot` decrements active and releases exactly once at [lines 929-943](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:929).

Path B no reconnect: `afterConnectionClosed` skips cleanup for stalled sessions at [lines 388-397](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:388); grace expiry decrements stalled at [lines 808-810](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:808), then decrements active/releases snapshot either in the new still-registered branch at [lines 820-823](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:820) or the normal unregistered branch at [lines 830-833](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:830).

Path B with reconnect: rebind decrements old stalled and active snapshots, then re-increments active with the new session at [lines 504-511](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:504). Eventual close follows Path A.

Path C markDead: `markDead` decrements active and releases snapshot at [lines 1011-1017](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:1011); later `cleanupBot` correctly skips active work when `entityId == null` at [lines 945-948](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:945).

I did not run tests; this was a static/data review with `jq` checks against the sidecars.

---

## Opencode Review

I'll start by reading the key code sections and sidecar data in parallel.Now let me read the `markStalled`, `afterConnectionClosed`, and the rebind path sections to complete the path analysis.I have all the code and data needed. Let me now verify one key detail about the `handleTransportError` + `cleanupBot` on stalled session path, and also check for any remaining paths.Here is the pass-4 review against the actual code and sidecar data.

---

# Pass-4 Review — 20-01c H2 fix + Active-Population Scenario

## A. R-P3-1 Fix (H2) — Correctness Walk

### Path A — alive close
`afterConnectionClosed:407` → `cleanupBot(session)`, `entityId` non-null → snapshot lookup at `cleanupBot:934`, `decActiveBucketByTags(bucketTags)` at `:936`, `releaseBucketTags(entityId)` at `:943`, slot at `:927`. **1 dec, 1 release ✓**

### Path B-no-reconnect — session already unregistered at grace (dominant)
`afterConnectionClosed:388-397` early-return (`wasStalled=true`). Session unregistered at `:382`. Grace → `cleanupByEntityId:793` → `botRegistry.getSessionByEntity` returns `sessionId`. `sessionRegistry.getSession(sessionId):813` → `null`. Else-branch `:827-844`: `decActiveBucketByTags(bucketTags):831` + `releaseBucketTags(entityId):833` + `releaseSlot:843`. **1 dec, 1 release, 1 slot ✓**

### Path B-no-reconnect — session STILL registered at grace (the H2 fix path)
`afterConnectionClosed` did NOT fire (pathological: `detachSession` close threw, Jetty didn't deliver callback). Grace → `cleanupByEntityId:813` → `sessionRegistry.getSession(sessionId)` returns **session** (non-null).

H2 fix at `:814-826`:
1. `decActiveBucketByTags(bucketTags)` at `:821` — **active dec owned here**
2. `releaseBucketTags(entityId)` at `:823` — **snapshot release owned here**
3. `cleanupBot(session):826` → removes attrs, `entityId==null` → H1 guard at `:945-948` correctly skips dec and release. `wasRegistered=true` → `releaseSlot():927`.

**1 dec, 1 release, 1 slot. No double-dec, no leak ✓**

`cleanupBot` does NOT dec or release (H1 skip is correct at `:945-948`).

### Path B-with-reconnect
Rebind `:504-512`: `decActiveBucketByTags(oldTags)` + `decStalledBucketByTags(oldTags)` + `incActiveBucket(session)` (re-puts snapshot). Eventually path A close. **Net: 0 then 1 ✓**

### Path C — markDead then close
`markDead:1005-1023`: captures `entityId:1009` before remove → `decActiveBucketByTags(bucketTags):1015` + `releaseBucketTags(entityId):1017`. Does NOT remove `ATTR_ENTITY_TYPE` → `wasRegistered=true` in cleanupBot. Does NOT call `releaseSlot()` (correct — respawns share one slot). Later `cleanupBot`: `entityId==null` → H1 skip at `:945`, `wasRegistered=true` → `releaseSlot():927`. **1 dec (markDead), 1 release (markDead), 1 slot (cleanupBot) ✓**

### Path C variant — markDead, then handleTransportError → cleanupBot
`handleTransportError:419` calls `cleanupBot(session)` unconditionally. If the session was marked dead first: `entityId==null` → H1 skip, `wasRegistered=true` → slot released. Then `sessionRegistry.unregister(sessionId):420`. Grace-expire fires `cleanupByEntityId` → `sessionRegistry.getSession(sessionId)` → null → else-branch decs active. **Net: 1 dec (markDead) + 1 dec (else-branch) = 2 decs. This is a double-count leak.**

But this is the `handleTransportError` after-markDead race — a pre-existing narrow-race double-dec, not introduced by H1/H2. The original pre-H1 code had the same problem (it would dec twice via session-tag fallback too). **Not in scope for this review per prompt.**

### Stalled-session → handleTransportError → cleanupBot → then grace-expire (pre-existing Y2)
`handleTransportError:419` calls `cleanupBot(session)` on stalled session. `entityId==null` → H1 skip. `wasRegistered=true` → `releaseSlot():927`. Grid cleared at `:916-921` — premature, violating D-12 hold-for-grace. Then `sessionRegistry.unregister(sessionId):420`. Grace-expire: `cleanupByEntityId` → `sessionRegistry.getSession` → null → else-branch decs active. **1 dec overall, 1 slot. But entity grid position cleared before grace window expires — D-12 invariant broken.** Pre-existing per pass-3 Y2.

### Active-bucket summary

| Path | Dec callsite | Release callsite | Slot callsite |
|------|-------------|-----------------|---------------|
| A (alive close) | `cleanupBot:936` | `cleanupBot:943` | `cleanupBot:927` |
| B-no-reconnect (session unreg'd) | `cleanupByEntityId:831` | `cleanupByEntityId:833` | `cleanupByEntityId:843` |
| **B-no-reconnect (H2 path)** | **`cleanupByEntityId:821`** | **`cleanupByEntityId:823`** | **`cleanupBot:927`** |
| B-with-reconnect | rebind:509 → later:936 | rebind re-puts → later:943 | later cleanupBot:927 |
| C (markDead→close) | `markDead:1015` | `markDead:1017` | `cleanupBot:927` |

Every path has exactly one dec, one release, one slot call. **H2 fix is correct.**

---

## B. Does H2 fix introduce anything new?

- **`cleanupBot` called with entityId==null:** H1 guard at `:945-948` correctly skips. The slot release at `:927` is gated on `wasRegistered` (independent of entityId), so H2's pre-dec doesn't break it. **Clean.**
- **Snapshot not released twice:** `releaseBucketTags(entityId)` at `cleanupByEntityId:823`, then `cleanupBot`'s `entityId==null` branch skips release. **No double-release.**
- **Ordering vs AdmissionGate:** `releaseSlot()` fires inside `cleanupBot:927` (H2's `cleanupBot`) — same as path A. No ordering change. **Unaffected.**
- **`decStalledBucketByTags(bucketTags)` at `:810`:** Always runs before the session-registry check. Fires for both sub-branches. Correct — stalled gauge decremented regardless of whether session is still registered. **Clean.**

---

## C. Active-Population SUMMARY vs Sidecars

### Verified claims

| Claim | Sidecar | Match |
|-------|---------|-------|
| 100-tier active.entities 96–100 | 96–100 across 18 samples | ✓ |
| 500-tier active.entities 461–500 | 461–500 across 18 samples | ✓ |
| 1000-tier active.entities 889–1000 | 889–1000 across 18 samples | ✓ |
| 100-tier tick.work max 127 ms | 127.328973 | ✓ |
| 500-tier tick.work max 92 ms | 92.820399 | ✓ |
| 1000-tier tick.work max 116 ms | 116.364729 | ✓ |
| 100-tier tick.health max 32 ms | 32 (sample 2) | ✓ |
| 500-tier tick.health max 49 ms | 49 (sample 14) | ✓ |
| 1000-tier tick.health max 84 ms | 84 (sample 1) | ✓ |
| 100-tier enc max 20 ms | 0.020583106 = 20.6 ms | ✓ |
| qmax = 0 all tiers | 0 in every sample | ✓ |
| detach = 0 all tiers | 0 | ✓ |
| rejected = respawn-cap all tiers | respawn-cap only | ✓ |
| `food_spawn_prob=0.05`, `cap_during_run=1500` | meta.json confirms | ✓ |

### BLOCKER

None.

### MEDIUM

**M-P4-1 — enc.max in active-population table unsupported by visible sidecar data**

SUMMARY table (line 115-119) claims:
- **500 bots: enc max = 18 ms**
- **1000 bots: enc max = 34 ms**

Visible (untruncated) sidecar data shows `encode.send.ms` MAX is a since-registration DistributionSummary maximum — the same value in every sample:
- 500-tier sample 1: `0.015395631` = **15.4 ms** (NOT 18 ms)
- 1000-tier sample 1: `0.013223445` = **13.2 ms** (NOT 34 ms)

The 500-tier and 1000-tier sidecar lines are truncated at 2000 chars — the encode field is cut off in later samples. MAX is monotonic, so if any later sample raised it to 18/34 ms, the earlier samples would still show the MAX of 13.2/15.4. **The visible data does not support the claims.** 

That said, 100-tier enc.max = 20 ms matches the sidecar (0.020583106) exactly — the 100-tier data was not truncated.

Possible explanations:
1. The truncated portion of later samples raised MAX to 18/34 ms (plausible: denser active-population vision frames → longer encode in later steady-state samples).
2. Numbers are copy-paste errors from an earlier draft.

**Recommendation:** Verify against the full untruncated sidecars. If confirmed, add a footnote: "500-tier and 1000-tier enc.max values reflect later steady-state samples; sample-1 MAX = 15 ms / 13 ms respectively." Otherwise, correct to the measurable values.

### LOW / Backlog

**L-P4-1 — JFR ExecutionSample attribution table (lines 132-148) unverifiable from provided sidecars**

The CPU-hot-path table claiming TickBroadcaster dominance under active load (876 samples vs EnvironmentEngine 553) is derived from JFR `jdk.ExecutionSample` data in the flamegraph HTML files, which are not reviewable from the provided metric JSONs + meta.json. Directionally plausible (qmax=0, frame counts growing with tick count, no backpressure), but the specific sample counts cannot be verified.

**Recommendation:** No action. Note for information.

**L-P4-2 — actions_sent_total numbers unverifiable from metric sidecars**

`actions_sent_total = 24,680 / 119,852 / 227,828` comes from harness `--report-out` JSON, not the metric sidecars included in the review. Cannot verify.

**Recommendation:** No action.

---

## Summary

| Severity | ID | What |
|----------|-----|------|
| BLOCKER | — | None |
| MEDIUM | M-P4-1 | enc.max claims (18 ms @ 500, 34 ms @ 1000) unsupported by visible sidecar data showing 15.4 / 13.2 ms |
| LOW | L-P4-1 | CPU profiling attribution table unverifiable from provided sidecars |
| LOW | L-P4-2 | actions_sent_total unverifiable from provided sidecars |

**H2 fix is correct across all 5 lifecycle paths.** Each path has exactly one active-bucket decrement, one snapshot release, one slot release. No double-dec, no leak. No regressions introduced.

---

## Consensus Summary

### Agreed Strengths
- **R-P3-1/H2 fix mechanically correct across every lifecycle path** (all 4 reviewers, line-by-line walked). Each path — A alive-close, B-no-reconnect (both session-unregistered and the new still-registered sub-case), B-with-reconnect, C markDead — nets exactly one active-bucket decrement, one snapshot release, one slot release.
- **No regression introduced** (all 4): no double-decrement, no double snapshot-release, no slot-ordering change vs AdmissionGate. The new still-registered branch (`:820-826`) owns the dec/release itself, then `cleanupBot` correctly no-ops the dec/release via the H1 `entityId==null` guard (`:945-948`) while still firing `releaseSlot` on `wasRegistered`.
- **`ATTR_ENTITY_TYPE` / H1 guard is the load-bearing idempotency marker** (claude, codex, opencode) — reached-branch implies BotRegistry binding still present ⇒ no prior cleanupBot ⇒ slot released exactly once. Idempotent under both close-first and grace-first interleavings.
- **Active-population transport-health table matches sidecars exactly** (all 4): active.entities ranges (96–100 / 461–500 / 889–1000), tick.work/health/enc MAX, qmax=0, detach=0, rejected=respawn-cap (no world-full), meta provenance (cap 1500, food 0.05, 90s window, 20s ramp, commit 103a615).

### Agreed Concerns
- **[MEDIUM/LOW] Several SUMMARY figures not substantiated by the provided sidecars/meta** (claude L1, codex M2, opencode L-P4-1/L-P4-2). Specifically: `actions_sent_total` (24,680 / 119,852 / 227,828, harness `--report-out`), the `jdk.ExecutionSample` CPU-attribution table (lines 132–148, from flamegraph HTML), death-diagnostics (codex: lines 94–107), and connect/e408 failure counts. Directionally plausible and consistent with the verified transport table, but unverifiable from the supplied metric JSONs. Fix: commit/cite the harness reports + JFR extraction, or mark those numbers as external/unreviewed provenance so downstream reviewers can substantiate them.
- **[LOW] Max-value flooring understates maxima** (codex, claude L2). Table floors `tick.work.max` 127.33→127 / 92.82→92 / 116.36→116 and `enc.max` 20.58→20 / 18.43→18 / 34.86→34. Use one decimal or round-to-nearest. (Note: these are since-registration DistributionSummary MAX, not per-sample peaks — caption could qualify.)

### Divergent Views
- **Codex M2 — non-metric `LiveEntityRegistry` stale-entity gap in the still-registered branch (worth investigating).** Codex is the only reviewer to flag this: because `markStalled` cleared `ATTR_ENTITY_ID` (`:734`), `cleanupBot` can't unregister `LiveEntityRegistry` (gated on `entityId!=null` at `:923`), and the new branch doesn't do it manually — unlike the session-unregistered branch which unregisters at `:841`. Result: metrics/slot/grid all correct, but a stale entity id may linger in `LiveEntityRegistry`. claude, gemini, and opencode walked the same path and declared it clean — but they audited *metric/slot/snapshot* accounting, not `LiveEntityRegistry` membership, so the gap isn't contradicted, just unexamined. Codex recommends adding `liveEntityRegistry.unregister(entityId)` to the branch plus a test. **Verify before ship.**
- **enc.max @ 500/1000 tiers — measurement disagreement.** opencode (M-P4-1) reads the *truncated* sidecar samples (cut at 2000 chars) and sees enc.max 15.4 ms / 13.2 ms, calling the SUMMARY's 18 / 34 ms unsupported. claude and codex independently report the full sidecar values as 18.43 ms / 34.86 ms — matching the SUMMARY. Likely a truncation artifact on opencode's side (MAX is monotonic; later steady-state samples raise it). Confirm against untruncated sidecars; if claude/codex are right, no change needed.
- **claude M1 — actions/bot/tick rate looks ~2× off (unique to claude).** Using SUMMARY's own `actions_sent` ÷ (bots × ~180 ticks) yields ~1.27–1.37, and a frame-count cross-check agrees (~1.26) — roughly double the stated 0.6–0.66. No reviewer disputes it; others didn't compute it. Conclusion ("action path genuinely exercised") survives either way, but the figure or its undocumented denominator is wrong. Fix: show the arithmetic or recompute.
