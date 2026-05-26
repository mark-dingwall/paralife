---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-05-20T20:24:38Z
files: ["src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-0824f1a.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-0824f1a.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-0824f1a.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-0824f1a.meta.json"]
usage:
  claude: { input: 36, output: 1031, cached: 1941630, tool_calls: 13, elapsed_s: 221.4 }
  gemini: { input: 985086, output: 5476, cached: 690670, tool_calls: 23, elapsed_s: 600.9 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 0.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 22, elapsed_s: 390.9 }
synthesizer: claude
synthesized_at: 2026-05-20T20:24:38Z
---

# Cross-AI Review

## Claude Review

Now write review. Caveman mode + auto-clarity for safety findings.

# Pass-2 Review — 20-01c remediation

## RED

### R1 — D1 introduces active.entities double-decrement on session-close-after-death

**Claim.** `markDead` decs bucket + releases tags snapshot. Then on session close `cleanupBot` runs, finds `entityId == null` (markDead already removed `ATTR_ENTITY_ID`), falls into the session-tags fallback at `WorldWebSocketHandler.java:929-930` and decs the **same** bucket again via `tagger.tagsFor(session)`.

**Trace.**
- `markDead` removes `ATTR_ENTITY_ID` (line 994), captures `entityId` locally, calls `decActiveBucketByTags(bucketTags)` (line 1002), then `releaseBucketTags(entityId)` (line 1004). Snapshot gone, attr gone.
- Later `cleanupBot:888` does `s.getAttributes().remove(ATTR_ENTITY_ID)` → returns `null` because markDead already removed it. `entityId` becomes `null`.
- `cleanupBot:922-931`: `bucketTags = entityId != null ? lookupBucketTags(entityId) : null` → `null`. Falls into `else { decActiveBucket(s) }` → calls `tagger.tagsFor(session)` which reads `source`/`harness` from session attrs (NOT removed by markDead) → resolves to **the same Tags** the snapshot held → decrements the same `AtomicInteger`.

**Severity.** RED. Pre-D1 leak was +incs/-1 dec. Post-D1 each full session-lifetime is `+N incs, -N markDead decs, -1 cleanupBot fallback dec = -1 drift`. Likely contributor to the 851 dip at sample 6 (SUMMARY blames it entirely on respawn-cap exits — partial truth).

**Cite.** `WorldWebSocketHandler.java:888,922-931,994-1004`; `AdmissionMetrics.java:278-290,345-347`; `AttributionTagger.java:87-101` (session-attr source/harness lookup).

**Recommendation.** One of:
1. Don't release snapshot in markDead — leave `releaseBucketTags(entityId)` for cleanupBot. cleanupBot would still see null entityId though, so really:
2. Hold off attr removal: markDead clears bucket but leaves `ATTR_ENTITY_ID` in place. cleanupBot is sole owner of the attr-clear + final release.
3. Add a flag attr `ATTR_BUCKET_DECREMENTED` set by markDead; cleanupBot skips bucket-dec when present.

Option 2 cleanest. Mirror pattern: only one of markDead/cleanupBot owns the bucket lifecycle exit, not both.

## R2 — SUMMARY caveat #3 + Pushback claim "tick.health.work-time-ms = null in 0824f1a" is empirically false

**Claim.** Caveat #3 (line 214) and Pushback (line 175-177) say the field reads `null` in the 0824f1a sidecars and accuse codex/opencode of hallucination.

**Cite.** `metrics-1000bots-baseline-0824f1a.json` samples → 56, 46, 32, 39, 44, 60. `metrics-500bots-...` → 35, 28, 25, 30, 22, 55. `metrics-100bots-...` → 23, 21, 20, 25, 19, 31. All 18 samples non-null. Confirmed via `jq` against all three 0824f1a sidecars.

**Severity.** RED. The pushback inverts the truth. Codex/opencode were correct that the gauge shows numeric values in committed sidecars; the SUMMARY pushback is the hallucination.

**Recommendation.** Delete caveat #3 and the corresponding pushback bullet. The MAINTENANCE-mode scalar IS being written by `TickHealthMonitor.onTick` and IS visible in sidecars. Replace with one line: "`tick.health.work-time-ms` scalar retained alongside the `tick.work.ms` DistributionSummary; both present in sidecars."

## YELLOW

### Y1 — D1 trajectory explanation in SUMMARY oversimplified

**Claim.** SUMMARY line 124 attributes the 998→966→851 trajectory entirely to "bots that hit `maxRespawnsPerSession=5` exiting." After R1, part of that drift is also the double-dec from cleanupBot-after-markDead.

**Severity.** YELLOW. Wording, not numbers. Once R1 is fixed, the trajectory should reflect pure live count.

**Recommendation.** Defer revision until R1 fixed and recaptured; the headline number changes.

### Y2 — D2 try/catch swallows the failed sample silently

**Claim.** `OutboundSender.java:331-337` wraps `sample.stop(...)` in `try/catch RuntimeException`. Drain VT correctly survives — the explicit goal. But the timer sample is dropped with only a `log.warn`. No counter / metric tracks how often this fires.

**Severity.** YELLOW. Acceptable trade (one lost histogram point per Micrometer failure is harmless vs VT death). Worth a counter for observability if it ever fires in steady-state.

**Recommendation.** Defer-with-rationale, or add `metrics.incEncodeSendStopFailures()` counter (~3 LOC). Not blocking.

### Y3 — D3 first-supplier-wins + `log.warn` is fine, but bean is mid-construction on the second-call path

**Claim.** `AdmissionMetrics.registerOutboundQueueDepthMaxGauge:484-493`. Second-call early-returns. Plan argues production registers once at @PostConstruct (technically constructor of `OutboundSender`, line 109 — not @PostConstruct). The Spring bean lifecycle guarantees one OutboundSender per context, so one registration in prod.

**Severity.** GREEN-leaning-YELLOW. log.warn vs throw — log.warn is correct because (a) test double-injection is the only realistic re-entry path and silently swapping is the bug the guard prevents; (b) throwing during bean construction would fail the application context. First-wins is the right semantics.

**Recommendation.** No change. Maybe add `// First supplier wins; second-callers in test paths silently log.` comment so future reader doesn't try to "improve" to last-wins.

### Y4 — `lookupBucketTags(entityId)` after `getAttributes().remove(ATTR_ENTITY_ID)` is safe — verified

**Claim.** Prompt asked whether D1's `lookupBucketTags` call is safe after attr removal. Yes — markDead at line 994 captures `eid` into local **before** any further code runs; line 996 reads `entityId` from the local; line 1000 passes that local string into `lookupBucketTags`. `bucketTagsByEntityId` map (the lookup target) is keyed by entityId-string, completely independent of session attrs.

**Severity.** GREEN observation.

**Recommendation.** No action.

## GREEN

- **D2 try/catch correctly placed.** `sample.stop` wrapped in dedicated try/catch inside the existing `finally`. RuntimeException from Micrometer internals (registry shutdown, histogram rotation) cannot escape `drainLoop`'s outer try/catch InterruptedException → drain VT survives. (`OutboundSender.java:331-337`)
- **D3 falsifiability — qmax non-NaN.** All 18 samples × 3 tiers show `paralife_outbound_queue_depth_max.measurements[0].value = 0.0`. Gauge supplier-pinning + double-register guard works as advertised. (`metrics-*-0824f1a.json`)
- **D4 meta.json carries asprof intervals.** `jfr-1000bots-baseline-0824f1a.meta.json:10-11` → `asprof_cpu_interval_us: 10000`, `asprof_alloc_interval_bytes: 524288`.
- **F1 cap-narrative rewrite consistent with code.** SUMMARY now correctly cites `AdmissionGate.java:58,140-151` global cap, cap=1500 non-binding, zero `world-full` rejections in any sidecar. Verified against `AdmissionGate.java:139-151` (one AtomicInteger reservedSlots against one cap). Pass-1 R1 settled.
- **Rejection column relabel correct.** SUMMARY table column reads `rejected{respawn-cap}` matching sidecar tags. Pass-1 mislabel settled. (`metrics-1000bots-baseline-0824f1a.json` samples 4-6 → `reason: respawn-cap`)
- **R3 pushback arithmetic re-derived and holds.** Sample 1 (06:32:21) enc.cnt=24,169. Sample 6 (06:32:47) enc.cnt=76,848. Window delta = 26s, frame delta = 52,679. Expected @ 1000 bots × 2 Hz × 26s = 52,000. Observed/expected = 101.3% — bots ARE at full population during steady state. Claude inline R3's 200s denominator was wrong. Pushback survives independent verification.
- **D-20 reference dropped** from SUMMARY. Pass-1 Y2 settled. Now cited as "benchmark-time JVM-flag override; production default `cap=256` at application.yml:65 unchanged" (line 92).
- **F2 timer envelope wording** correctly states "broader superset than original framing" — captures monitor contention and metric-recording overhead. Pass-1 Y3 settled.

## Recommendation

R1 must fix before merge — D1 introduces a new active.entities double-dec that contaminates the falsifiability metric the plan was built to repair. R2 must fix before merge — SUMMARY pushback against codex/opencode is itself a hallucination against the 0824f1a sidecars. Both fixable in <30 min: R1 = move `ATTR_ENTITY_ID` removal from markDead to cleanupBot (and let cleanupBot own bucket-dec), recapture; R2 = delete caveat #3 + tick.health pushback bullet.

Y1–Y4 are wording/observability follow-ups; not blocking.

---

## Gemini Review

Here is the review of the Phase 20-01c remediation branch.

## RED Findings

**D1 — `active.entities` double-decrement bug**
- **State the claim**: The `markDead` implementation removes `ATTR_ENTITY_ID` from the session attributes, causing `cleanupBot` to fall back to session-based tags and perform a second decrement on the active bucket when the session eventually closes.
- **Cite the source**: `WorldWebSocketHandler.java:994` (`Object eid = session.getAttributes().remove(ATTR_ENTITY_ID);`) and `WorldWebSocketHandler.java:932` (`admissionMetrics.decActiveBucket(s);` fallback hit because `entityId` is null).
- **Severity**: RED.
- **Recommendation**: In `markDead`, do NOT remove `ATTR_ENTITY_ID` from the session attributes (or introduce a new `ATTR_DEAD` marker so `cleanupBot` knows not to decrement again). 

## YELLOW Findings

**D3 — Gauge double-register "first supplier wins" semantics**
- **State the claim**: The "first supplier wins" logic correctly avoids Micrometer cross-wiring and matches the plan, but it leaves the gauge reading a stale supplier if the Spring bean is recreated (e.g., during test hot-reloads).
- **Cite the source**: `AdmissionMetrics.java:482` (`if (this.outboundQueueDepthSupplier != null) { log.warn(...); return; }`).
- **Severity**: YELLOW.
- **Recommendation**: Consider overwriting `outboundQueueDepthSupplier` on subsequent calls instead of early-returning to better support test context recreation, or defer with rationale since it is a singleton in production.

## GREEN Findings

**D1 — `markDead` slot release omission**
- **State the claim**: The plan's decision to deliberately omit `releaseSlot()` in `markDead` is structurally correct, as respawns bypass cap acquisition and a slot is bound per-session, not per-entity-life.
- **Cite the source**: `AdmissionGate.java:148` (`if (!req.isRespawn())` guard).
- **Severity**: GREEN.
- **Recommendation**: No action, just observation.

**D2 — `Timer.Sample.stop` Exception isolation**
- **State the claim**: Wrapping `sample.stop` in a `try/catch(RuntimeException)` correctly isolates the drain VT from Micrometer rotation/shutdown exceptions without swallowing fatal `Error`s.
- **Cite the source**: `OutboundSender.java:375-381` (`finally` block).
- **Severity**: GREEN.
- **Recommendation**: No action, just observation.

**SUMMARY rewrite quality — Pushback Arithmetic**
- **State the claim**: The SUMMARY's pushback arithmetic correctly refutes the "10× scale is partly fictional" claim by re-deriving the frame count expectations based on the actual 47s capture window instead of the 200s harness window.
- **Cite the source**: `20-01c-SUMMARY.md` (§Pushback "10× scale is partly fictional").
- **Severity**: GREEN.
- **Recommendation**: No action, just observation.

**Recapture sanity — sidecar fields & leakage**
- **State the claim**: The `metrics-1000bots-baseline-0824f1a.json` sidecar confirms the baseline leak is structurally addressed (population declines appropriately at the end rather than monotonic growth) and rejections are properly labeled as `respawn-cap`.
- **Cite the source**: `metrics-1000bots-baseline-0824f1a.json` (sample 6 `active.entities` = 851.0; `rejected` tag `reason=["respawn-cap"]`) and `jfr-1000bots-baseline-0824f1a.meta.json` (`asprof_cpu_interval_us` = 10000).
- **Severity**: GREEN.
- **Recommendation**: No action, just observation.

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 0.0s

Stderr tail:
```
Error loading config.toml: Failed to read project hooks config file /home/mark/kramtime/paralife/.codex/config.toml: Not a directory (os error 20)
```

---

## Opencode Review

Let me read the remaining source locations I need:Now let me check the specific `tick.health.work-time-ms` values in all 3 sidecars and verify the pushback arithmetic independently:Now I have all the data. Let me do one final verification — the `enc.cnt` vs `frame_size` discrepancy and the exact sample count:All evidence gathered. Here is the pass-2 review:

---

# Pass-2 Multi-Review: 20-01c Remediation (HEAD `62f2ffa`)

## RED

**R-P2-1 — SUMMARY pushback on `tick.health.work-time-ms` is factually false**

Claim: SUMMARY caveat #3 (line 213) and pushback § (lines 175-177) state `paralife.tick.health.work-time-ms = null` in the 0824f1a sidecars, call codex/opencode pass-1 reports of numeric values "reviewer hallucination", and claim verification via `jq` showed emptiness.

Source contradiction:

| File | Sidecar values (6 samples each) |
|---|---|
| `metrics-100bots-baseline-0824f1a.json` | **23, 21, 20, 25, 19, 31** |
| `metrics-500bots-baseline-0824f1a.json` | **35, 28, 25, 30, 22, 55** |
| `metrics-1000bots-baseline-0824f1a.json` | **56, 46, 32, 39, 44, 60** |

Every sample across all 18 scrape points has a real numeric value. `TickHealthMonitor.onTick()` at `TickHealthMonitor.java:63` writes this gauge on every tick (`admissionMetrics.setLastTickWorkMs(sample)`). The gauge has an `AtomicLong` backend — it cannot return null. Codex (inline) and opencode (reference) pass-1 reviewers were **correct**. The "reviewer hallucination" accusation against them is false and damaging to cross-AI trust.

**Severity:** RED. The SUMMARY makes a demonstrably false claim about its own committed data artifacts and falsely impugns two reviewers. Must fix before merge.

**Recommendation:** Delete caveat #3 entirely (the gauge IS emitting values). Delete the tick.health pushback paragraph (lines 175-177). Replace with a one-line note: "`paralife.tick.health.work-time-ms` emits numeric values (23-60 ms range across tiers) — the scalar is live but deprecated in favor of `tick.work.ms` DistributionSummary. Retained for SHA-to-SHA continuity with 20-01b."

---

## YELLOW

**Y-P2-1 — Pushback "10× scale" arithmetic uses overlapping windows without clear separation**

Claim (lines 165-169): "~47 s" total from run-start, "~37 s effective" for computation. Scrape timestamps (`metrics-1000bots-baseline-0824f1a.json`) span 26 s across 7 samples (06:32:21 → 06:32:47). Active population: 966, 1000, 1000, 998, 966, 851 — genuinely ~1000 through most of the window.

1000 bots × 2 Hz × 26 s = 52k expected in steady state alone. With ramp (~20s at rate:50), total expected ~72k. Observed `enc.cnt` = 76,848 (sample 7). The pushback's conclusion that "10× scale is not fictional" survives — the active population IS sustained near 1000. But the derivation uses 25s between sample-1 and sample-6 (actual: 21s), conflates a "~37 s effective" figure with the "~47 s" headline, and rounds aggressively. The arithmetic is directionally correct but imprecise — document the exact timestamps and window boundaries.

**Severity:** YELLOW. Conclusion survives but derivation should be tightened (or replaced with direct `active.entities` proof which is stronger and simpler).

**Recommendation:** Replace the window arithmetic with: "active.entities reads 966 / 1000 / 1000 / 998 / 966 / 851 across 6 snapshots — population is genuinely at scale. 76,848 frames observed. The pass-1 R3 arithmetic used the full 200 s harness duration rather than the ~48 s metered window." 

---

**Y-P2-2 — `markDead` + `cleanupBot` interleaving can double-dec active bucket (theoretical)**

`markDead` (WorldWebSocketHandler.java:998-1004): decs via `lookupBucketTags(entityId)`, then `releaseBucketTags(entityId)`. If `cleanupBot` fires afterward on the same entity, `lookupBucketTags` returns null (entry already released), and `cleanupBot` falls back to session-derived tags via `decActiveBucket(s)` (line 930). If the session tags match the original snapshot tags, this double-decs.

In practice: `cleanupBot` is triggered by session close, which requires the session to have no entity. After `markDead` removes ATTR_ENTITY_ID, the broadcaster detects this, sends a respawn frame, and the bot's `r|` frame triggers re-registration — which rebinds a new entityId. By the time the old session is fully closed, the entityId no longer maps to anything. The sidecar trajectory (851 at end, not oscillating) confirms no observed double-dec at 1000-bot scale.

**Severity:** YELLOW. Pre-existing concern (cleanupBot's fallback path has always existed); markDead adding a dec makes the window slightly wider but does not create a new mechanism. Guard with a `wasAlreadyDecd` flag or document the interleaving as accepted noise.

**Recommendation:** Add a one-line comment in `markDead` noting the interleaving possibility and that the sidecar data shows it as benign at observed scale. No code change required.

---

**Y-P2-3 — `enc.cnt` (Timer) consistently exceeds `frame.size.bytes` COUNT (undocumented)**

At 100-bot sample 3: frame_size = 2,578 vs encode_send_ms = 2,678 (+100). At 500-bot sample 7: 33,279 vs 33,489 (+210). At 1000-bot sample 7: 76,833 vs 76,848 (+15). The gap is encode-failure frames: `PerceptionCodec.encode` throws `RuntimeException` (caught at OutboundSender.java:329) — the Timer `finally` records it but `recordFrameSize` at line 311 never executes because encode failed before reaching it.

**Severity:** YELLOW. Benign at this scale. The SUMMARY headline table uses the Timer COUNT as `enc.cnt` — that's defensible (it's the number of drainLoop iterations that reached the encode stage). Just undocumented.

**Recommendation:** Add one line in the headline table caption noting that `enc.cnt` includes encode-failure iterations, not just successfully-sent frames.

---

## GREEN

### D1 — markDead decrements active.entities bucket (WorldWebSocketHandler.java:992-1010)

- `entityId` captured to local (line 996) before `remove(ATTR_ENTITY_ID)` (line 994). `lookupBucketTags(entityId)` reads from `ConcurrentHashMap`, not session attrs — safe. ✓
- `lookupBucketTags → decActiveBucketByTags → releaseBucketTags` mirrors `cleanupBot:917-935`. ✓
- No `releaseSlot()` — correct: `AdmissionGate.java:141` Guard 5 skips on `req.isRespawn()`. One slot per session, reused across respawns. `cleanupBot` and `markStalled` are the only slot releasers. ✓
- Sidecar verification: active.entities trajectory 966 → 1000 → 1000 → 998 → 966 → 851 — tracks live population, not monotonic growth. D1 falsifiability gate passed. ✓

### D2 — Timer.Sample.stop guarded (OutboundSender.java:331-337)

- Wraps `sample.stop(metrics.encodeSendTimer())` in `try/catch RuntimeException` with `log.warn`. The described failure mode (Micrometer histogram-rotation / registry-shutdown race throwing inside `stop`) is caught. Drain VT survives. ✓
- `detach.timeout = 0` across all 18 samples — preservation check that D2 did not introduce drain-VT latency regressions. ✓

### D3 — gauge double-register guard (AdmissionMetrics.java:484-488)

- Early-returns with `log.warn` when `outboundQueueDepthSupplier != null`. "First supplier wins" is correct: production registers exactly once at `@PostConstruct`; Micrometer dedupes by name+tags at registry layer. ✓
- `qmax` = 0.0 at every sample — no NaN. Gauge supplier pinning fix (02b1b76) holds. ✓

### D4 / Recapture sanity (profiles/*-0824f1a.*)

- `active.entities`: near 1000 through steady-state, dips to 851 as bots exhaust respawn budget. ✓
- `rejected`: `reason=respawn-cap` only (1 at sample 4, 17 at sample 5, 58 at sample 6/7). Zero `world-full`. ✓
- `qmax`: 0.0 at all 18 samples. ✓
- `meta.json`: carries `asprof_cpu_interval_us: 10000`, `asprof_alloc_interval_bytes: 524288`, `cap_during_run: 1500`. ✓

### SUMMARY F1 rewrite — cap narrative corrected

"Cap is world-aggregate" now cites `AdmissionGate.java:58,140-151`. Nutrients bypass story corrected. Column is `rejected{respawn-cap}` not `world-full`. Cap=1500 JVM-flag override, not default change. ✓

### SUMMARY F2 rewrite — Timer envelope documented

Now states Timer brackets "encode + getBytes() + recordFrameSize + monitor-wait + synchronized(session) sendMessage + frame-emit listener" — broader than original framing, captures monitor contention. Excludes queue dwell — documented as caveat. ✓

---

## Summary

| ID | Severity | What | Action |
|---|---|---|---|
| **R-P2-1** | **RED** | tick.health pushback is false — all 18 sidecar samples show numeric values | Delete caveat #3 + pushback ¶; replace with one-line note |
| Y-P2-1 | YELLOW | Pushback arithmetic uses overlapping windows | Tighten derivation or replace with active.entities direct proof |
| Y-P2-2 | YELLOW | markDead+cleanupBot interleaving could double-dec | Document as accepted noise; not observed in data |
| Y-P2-3 | YELLOW | enc.cnt > frame_size COUNT (Timer counts encode-failure frames) | One-line clarification in table caption |

The D1, D2, D3 code changes are correct. The recapture artifacts are internally consistent. The SUMMARY rewrite is correct on F1/F2/F6 but contains one RED factual error (tick.health pushback) that must be fixed before merge.

---

## Consensus Summary

### Agreed Strengths
- **D1 markDead bucket dec mechanically correct** (claude G, gemini G, opencode G): `entityId` captured to local before `remove(ATTR_ENTITY_ID)`; `lookupBucketTags` reads from `ConcurrentHashMap`, safe after attr removal. No `releaseSlot()` is intentional — `AdmissionGate` skips slot acquisition on respawn (one slot per session, reused).
- **D2 Timer.Sample.stop try/catch correctly placed** (claude G, gemini G, opencode G): RuntimeException from Micrometer rotation/shutdown isolated; drain VT survives; `detach.timeout = 0` across all samples confirms no latency regression.
- **D3 first-supplier-wins gauge guard works** (claude G, gemini Y-leaning-accept, opencode G): `qmax = 0.0` non-NaN across all 18 samples × 3 tiers; production singleton registers once.
- **D4 / recapture artifacts internally consistent** (gemini G, opencode G; claude implicit via verification): `active.entities` tracks live population (966→1000→1000→998→966→851), `rejected{reason=respawn-cap}` only (zero `world-full`), `meta.json` carries `asprof_cpu_interval_us=10000` / `asprof_alloc_interval_bytes=524288`.
- **F1 cap narrative rewrite correct** (claude G, opencode G): world-aggregate cap cited at `AdmissionGate.java:58,140-151`; cap=1500 documented as JVM-flag override; rejection column relabeled to `rejected{respawn-cap}`.
- **F2 Timer envelope wording correct** (claude G, opencode G): captures monitor contention + sendMessage + recordFrameSize; "broader superset" framing accurate; queue-dwell exclusion documented.
- **Pushback against pass-1 R3 "10× scale fictional" survives** (claude G, gemini G, opencode G — directionally): `active.entities` directly proves ~1000 sustained; 76,848 frames consistent with ~26 s × 1000 bots × 2 Hz.

### Agreed Concerns
- **RED — SUMMARY caveat #3 + tick.health pushback factually false** (claude R2, opencode R-P2-1). All 18 samples across 3 sidecars show numeric `paralife.tick.health.work-time-ms` values (100-bot: 19–31, 500-bot: 22–55, 1000-bot: 32–60). `TickHealthMonitor.onTick` writes the `AtomicLong`-backed gauge every tick — cannot return null. Pass-1 codex/opencode were correct; the "reviewer hallucination" accusation is itself the hallucination. **Action:** delete caveat #3 + pushback bullet; replace with one-line note that scalar is live but deprecated in favor of `tick.work.ms` DistributionSummary, retained for SHA-to-SHA continuity.
- **`markDead` + `cleanupBot` double-decrement risk on active.entities bucket** (claude R1 RED, gemini RED, opencode Y-P2-2). `markDead` removes `ATTR_ENTITY_ID` at line 994 then decs bucket; later `cleanupBot:888` finds `entityId == null` and falls through to `decActiveBucket(s)` fallback (line 930) which resolves the same Tags via `tagger.tagsFor(session)` (session-attr source/harness untouched) → same `AtomicInteger` decremented twice. **Severity split:** claude+gemini RED (mechanism present, contaminates the falsifiability metric the plan was built to repair, plausibly explains part of 851 dip); opencode YELLOW (data shows no observed oscillation at 1000-bot scale, calls it benign noise). **Recommendation convergence:** move `ATTR_ENTITY_ID` removal out of `markDead` so `cleanupBot` is sole owner of attr-clear + final bucket release (claude option 2 = gemini's recommendation). Alternatively `ATTR_BUCKET_DECREMENTED` flag.

### Divergent Views
- **Severity of markDead/cleanupBot double-dec.** Claude + gemini call it RED and must-fix-before-merge: mechanism is present in code, contaminates the very gauge D1 was meant to fix, and the 851→ drift in sample 6 SUMMARY attributes wholly to respawn-cap exits — partial truth at best. Opencode calls it YELLOW based on absence of oscillation in current sidecar trajectory. **Recommend treating as RED**: the data does not refute the mechanism (steady decrement is invisible when bots are continuously exiting), and the fix is ~5 LOC. Recapture after fix; if 851 number stays, opencode was right; if it shifts upward, claude+gemini were right. Either way merge with the fix.
- **D3 gauge-supplier semantics.** Claude argues first-supplier-wins is correct (test-double-injection is the realistic re-entry, throw would fail context); gemini suggests considering last-wins for test hot-reload, then defers. **Recommend keep first-wins** with one-line comment explaining intent, per claude — gemini's defer-with-rationale aligns.
- **Pushback arithmetic precision** (opencode Y-P2-1). Opencode wants tighter window derivation or direct `active.entities` proof; claude independently re-derives with 26 s window and confirms 101.3 % expected, calls arithmetic sound. **Recommend opencode's swap to direct `active.entities` evidence** — stronger, simpler, removes the window-conflation surface.
- **`enc.cnt` vs `frame.size.bytes` count gap** (opencode Y-P2-3 only). Encode-failure frames inflate Timer COUNT vs frame-size DistributionSummary COUNT. Not raised by claude/gemini. Defensible as-is; one-line caption clarification cheap.
