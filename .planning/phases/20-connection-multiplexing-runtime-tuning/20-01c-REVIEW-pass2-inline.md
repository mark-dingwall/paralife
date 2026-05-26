---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-05-20T20:13:59Z
files: ["src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-0824f1a.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-0824f1a.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-0824f1a.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-0824f1a.meta.json"]
usage:
  claude: { input: 10, output: 16, cached: 44342, tool_calls: 0, elapsed_s: 247.8 }
  gemini: { input: 89712, output: 1480, cached: 0, tool_calls: 0, elapsed_s: 91.3 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 0.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 245.9 }
synthesizer: claude
synthesized_at: 2026-05-20T20:13:59Z
---

# Cross-AI Review

## Claude Review

Reviewed remediation. One RED finding (latent over-dec at session close), few YELLOW polish, rest GREEN.

## RED

### N1 — D1 over-decrements at session close (post-markDead → cleanupBot fallback)

**Claim:** D1 fix in `markDead` decrements bucket, but `cleanupBot`'s fallback path at session close ALSO decrements the same bucket via session tags — net `-1` per session after final death.

**Source:**
- `WorldWebSocketHandler.java:1024-1043` (markDead) — decs bucket, releases snapshot, removes `ATTR_ENTITY_ID`. Does NOT remove `ATTR_ENTITY_TYPE`.
- `WorldWebSocketHandler.java:923-941` (cleanupBot decrement block):
  ```
  if (wasRegistered && admissionMetrics != null) {
      bucketTags = entityId != null ? lookupBucketTags(entityId) : null;
      if (bucketTags != null) decActiveBucketByTags(bucketTags);
      else                    decActiveBucket(s);   // fallback uses session tags
  }
  ```

**Trace** (1000-bot session, 5 respawns, then respawn-cap exhausted, then close):
- +5 incs (initial register + 4 respawns)
- −5 decs in `markDead` (one per death)
- Session closes → `cleanupBot`:
  - `wasRegistered = remove(ATTR_ENTITY_TYPE) != null` → **true** (markDead leaves it set)
  - `entityId = remove(ATTR_ENTITY_ID)` → **null** (markDead already removed)
  - `lookupBucketTags(null)` → null → falls to `decActiveBucket(s)` → **−6th dec**
- Net: `+5 / −6 = −1` per closed session. `AtomicInteger.decrementAndGet()` has no floor → gauge underflows.

**Why not visible in sidecars:** harness sessions stay open the full 200 s; sample 6 captures at ~50 s in. Cleanup fanout happens at harness shutdown, after the last sample. The `851 → ?` post-shutdown state is uncaptured. The bug bites in any long-running production: every closed-after-final-death session leaks `−1` from the bucket gauge.

**Severity:** RED — fixes the visible leak (3733 → live count) but introduces an opposite-sign latent bug. Operator-facing gauge accuracy is the entire point of D1.

**Recommendation:** in `cleanupBot`, skip the fallback when `entityId == null` (markDead has already decremented):

```java
if (wasRegistered && admissionMetrics != null) {
    if (entityId != null) {
        Tags bucketTags = admissionMetrics.lookupBucketTags(entityId);
        if (bucketTags != null) admissionMetrics.decActiveBucketByTags(bucketTags);
        else                    admissionMetrics.decActiveBucket(s);
        admissionMetrics.releaseBucketTags(entityId);
    }
    // entityId == null → markDead already decremented; skip
}
```

Slot release stays gated on `wasRegistered` — unaffected. The `cleanupBot` doc-comment ("Hits only when no snapshot was ever captured (e.g. legacy tests)") is also now stale post-D1 — update it too.

Falsifiability: extend the harness or a unit test to keep scraping AFTER `--duration` completes and connections close. Pre-fix bucket goes negative; post-fix stays at 0.

## YELLOW

### N2 — Pushback §"10× scale" arithmetic loose but directionally correct

**Claim:** SUMMARY's pushback derives "1000 × 2 Hz × ~37 s effective ≈ 74 k frames; observed 76 848 ≈ 103 %." The 37 s "effective window" math is hand-wavy.

**Re-derivation against sidecar** (`metrics-1000bots-baseline-0824f1a.json` + meta.json):
- meta.captured_utc = `06:31:54Z`; sample 6 utc = `06:32:47Z` → 53 s wall window from capture-script start to sample 6.
- Harness ramp `rate:50` for 1000 bots → 20 s ramp; sample 1 utc `06:32:21Z` = 27 s after script start (~7 s post-ramp).
- Effective broadcast window @ 2 Hz: ramp_avg(500 bots × 20 s × 2) + post_ramp(~950 × 33 s × 2) ≈ 20 k + 62.7 k ≈ 83 k frames expected.
- Observed `enc.cnt = 76 848` = ~92 % of expected. Bots ARE at near-full population, not 190 concurrent as claude inline R3 claimed.

The directional pushback stands. The "103 %" precision is overclaim. R3 was wrong on denominator (200 s); SUMMARY counter-argument is approximately right but should drop the precision claim.

**Severity:** YELLOW — rhetoric, not load-bearing.
**Recommendation:** "76 848 frames @ 2 Hz / 1000 bots = 38.4 s of full-throughput equivalent across a ~53 s wall window; consistent with ~800-1000 bots sustained across ramp + steady, NOT 190 concurrent." Drop the 103 % framing.

### N3 — D3 check-then-set non-atomic

`AdmissionMetrics.java:478-484`:
```
if (this.outboundQueueDepthSupplier != null) { log.warn(...); return; }
this.outboundQueueDepthSupplier = peakSupplier;
Gauge.builder(...).register(registry);
```

Two concurrent callers can both pass the null check, both reach the assignment, both register. Micrometer dedupes by `name+tags` at the registry layer so the second `register(...)` is a no-op meter-wise — but the volatile field gets overwritten. In practice, registration is from `OutboundSender` constructor on the Spring init thread, single-threaded, so this is theoretical.

**Severity:** YELLOW — sound for production wiring, fragile for tests.
**Recommendation:** `AtomicReference.compareAndSet`, or document the single-threaded invariant in a one-line comment. Either is fine. The plan's first-wins log-and-continue choice is correct for the failure mode (hot reload / test injection); throw would surprise harnesses.

### N4 — cleanupBot doc-comment stale post-D1

`WorldWebSocketHandler.java:935`: `// Fallback: session tags. Hits only when no snapshot was ever captured (e.g. legacy tests calling cleanupBot without going through Allow path).`

False post-D1 — also hits on post-markDead-then-close (the normal long-running session path). Update once N1 fix lands.

**Severity:** YELLOW — drift in comment vs behaviour.

## GREEN

### N5 — D2 try/catch isolates drain VT correctly
`OutboundSender.java:373-378`: `catch (RuntimeException metricEx) { log.warn(...); }`. Covers NPE/CME from Micrometer histogram-rotation. `OutOfMemoryError` / `StackOverflowError` propagate per JVM contract — acceptable, drain VT should not pretend to survive Errors. Lost timer sample for that frame is the explicit trade — fine; the saturation conclusion does not rest on individual frames.

### N6 — D3 first-wins semantics correct
Production registers exactly once at `OutboundSender` constructor; second-call paths are test-double-injection or hot reload. `log.warn` is consistent with Micrometer's silent dedupe pattern. First-wins matches "the live `OutboundSender` instance owns the gauge" intent.

### N7 — D4 meta.json fields land
All three `jfr-*bots-baseline-0824f1a.meta.json` carry `asprof_cpu_interval_us: 10000` + `asprof_alloc_interval_bytes: 524288`. ✓

### N8 — Recapture sanity
- `metrics-1000bots-baseline-0824f1a.json` sample 6: `active.entities = 851` (NOT 3733). Trajectory 966→1000→1000→998→966→851 — tracks live population minus respawn-cap-exhausted exits. ✓
- `availableTags.reason = ["respawn-cap"]` only at every tier. Zero `world-full` rejections across 18 samples. ✓
- `paralife.outbound.queue.depth.max` reads numeric 0 in all 18 samples — non-NaN, gauge supplier still pinned correctly post-D3. ✓
- `paralife.outbound.detach.timeout = 0` at every sample → D2 try/catch did not introduce drain-VT-exit pathology. ✓

### N9 — SUMMARY §F1 rewrite consistent with code
`AdmissionGate.java:58` (`AtomicInteger reservedSlots`), `:142-150` (single CAS against `admissionConfig.cap()`), `:154` (Guard 6 respawn-cap). The "cap is world-aggregate; nutrients bypass AdmissionGate via SimulationEngine.setEntity:1431; bucket exists only as metric tag" narrative matches source. Per-bucket-cap fiction fully retracted.

### N10 — Pass-1 dispositions resolved
- F1 narrative rewrite → addressed by SUMMARY rewrite per N9.
- `rejected{world-full}` → `rejected{respawn-cap}` column rename in headline. ✓
- D-20 reframing: `application.yml:65` cap=256 unchanged; `cap_during_run: 1500` in meta.json and prose. D-20 binding distinguished from cap surface. ✓
- Timer envelope wording (encode + getBytes + recordFrameSize + monitor-wait + sendMessage + listener) now matches `OutboundSender.drainLoop:359-381`. ✓
- `peakQueueDepth` correctly downgraded to "at-scrape-instant", "not interval-peak". ✓
- `tick.health.work-time-ms = null` defended in caveat #3 by direct `jq` against sidecars — verified empty (sample-6 1000-bot sidecar shows `paralife_tick_health_work-time-ms.measurements[0].value = 60.0` actually — wait, let me recheck).

Hold on — caveat #3 says "null in all 18 samples". But sample 6 of the 1000-bot sidecar shows `paralife_tick_health_work-time-ms.measurements[0].value = 60.0`. And earlier samples show 56, 46, 32, 39, 44. **These are NOT null.** SUMMARY caveat #3 is wrong here for the 0824f1a capture — codex+opencode reviewers were right against the new sidecars, even if they were wrong about the 1818eeb sidecars.

Promoting to YELLOW:

### N11 — Caveat #3 contradicts the 0824f1a sidecars

**Claim:** SUMMARY caveat #3: "`paralife.tick.health.work-time-ms = null` in 0824f1a sidecars... verified empty via jq."

**Source:** `metrics-1000bots-baseline-0824f1a.json` sample 6:
```
"paralife_tick_health_work-time-ms":{"measurements":[{"statistic":"VALUE","value":60.0}],...}
```
All six 1000-bot samples carry numeric values (56, 46, 32, 39, 44, 60). Same for 500-bot (35, 28, 25, 30, 22, 55) and 100-bot (23, 21, 20, 25, 19, 31).

The caveat is factually wrong against the post-D1 sidecars. The pushback against codex/opencode "reviewer hallucination" stands ONLY for the 1818eeb sidecars. For 0824f1a, the gauge IS being written — likely `TickHealthMonitor.onTick @Order(Integer.MAX_VALUE)` did wire up between 1818eeb and 0824f1a, or always was wired and the original null observation was a sampling artifact.

**Severity:** YELLOW — factual error in caveat. Either delete caveat #3 or rewrite to: "deprecated scalar still emits a value in this capture; `paralife.tick.work.ms` DistributionSummary is the canonical meter for downstream."

**Recommendation:** verify via `jq '[.samples[] | .["paralife_tick_health_work-time-ms"].measurements[0].value]' metrics-1000bots-baseline-0824f1a.json` and update SUMMARY accordingly. The pushback against pass-1 reviewers (codex inline + opencode reference) should be reframed: their claim was wrong against 1818eeb, right against 0824f1a — both can be true.

---

## Summary

**Must-fix before merge:** N1 (D1 latent over-dec at session close). Concrete: skip cleanupBot bucket-dec when `entityId == null`.

**Should-fix:** N11 (caveat #3 wrong against 0824f1a sidecars); N4 (stale cleanupBot comment); N2 (loosen pushback arithmetic precision).

**Nice-to-have:** N3 (atomic check-then-set or one-line invariant comment).

**Pass-1 issues resolved:** F1 / F2 / F6 narrative rewrites, rejection-column relabel, D-20 reframing, qmax wording — all addressed in SUMMARY and code. D2/D3 land cleanly. D4 evidence trail intact.

---

## Gemini Review

Here is the Cross-AI Review of Plan 20-01c (Pass-2 Remediation).

### 🔴 RED (must-fix before merge)

**R1 — D1 `markDead` fix introduces a double-decrement leak in `active.entities` on the `cleanupBot` path.**
- **Source:** `WorldWebSocketHandler.java:992-1000` (`markDead`) and `917-935` (`cleanupBot`)
- **Severity:** RED
- **Claim:** The `markDead` bucket decrement correctly fixes the `active.entities` leak without over-releasing slots.
- **Observation:** `markDead` removes `ATTR_ENTITY_ID` and decrements the bucket gauge. However, it intentionally leaves `ATTR_ENTITY_TYPE` intact. If a session closes while dead (e.g., after exhausting the `maxRespawnsPerSession=5` cap, or a disconnect prior to sending a respawn frame), `cleanupBot` evaluates `wasRegistered = true` but `entityId = null`. `cleanupBot` then falls into its legacy fallback branch (`admissionMetrics.decActiveBucket(s)`) and decrements the gauge a **second time** using the live session tags. This causes the gauge to unnaturally plummet, which explicitly explains why the 1000-bot trajectory dips so low to 851 (1000 - 58 dead bots - 91 other dead churners = 851) rather than reflecting the true live population.
- **Recommendation:** Fix `cleanupBot` to prevent double-decrementing. Do not execute the legacy fallback `admissionMetrics.decActiveBucket(s)` if the session was simply dead (e.g., have `markDead` set a new `ATTR_DEAD` marker to skip the decrement, or update legacy tests so the dangerous fallback can be removed entirely). 

### 🟡 YELLOW (should-fix or document)

**Y1 — D3 "First supplier wins" gauge registration semantics breaks test double-injection.**
- **Source:** `AdmissionMetrics.java:477-485` (`registerOutboundQueueDepthMaxGauge`)
- **Severity:** YELLOW
- **Claim:** Adding an early-return guards against cross-wire fragility when called twice.
- **Observation:** Micrometer internally dedupes `Gauge` registrations by name+tags. By using an early return to prevent the overwrite of `this.outboundQueueDepthSupplier = peakSupplier`, the logic locks the gauge to the *first* `OutboundSender`'s queue map forever. If tests create a new `OutboundSender` but reuse the `AdmissionMetrics` Spring bean context, the new sender's queue depths will be completely ignored, breaking test-double injectability.
- **Recommendation:** Revert to the original overwriting assignment (`this.outboundQueueDepthSupplier = peakSupplier;`). Allowing test injections to dynamically swap the supplier behind the gauge is the correct behavior for reused registries.

**Y2 — SUMMARY mixes 1818eeb and 0824f1a data in Pushback.**
- **Source:** `20-01c-SUMMARY.md` (Pushback subsection)
- **Severity:** YELLOW
- **Claim:** `enc.cnt = 76 048` at 1000-tier sample 6 reflects ~47 s of activity.
- **Observation:** The arithmetic successfully invalidates Claude's R3 pass-1 claim. However, `76 048` is the stale `1818eeb` number. The new `0824f1a` capture actually reports `76 848` frames. Mixing the old number in the pushback while the new number sits in the headline table is technically correct for defending against the pass-1 review, but it poses a minor continuity confusion for future readers.
- **Recommendation:** No action required, just observation. The arithmetic itself (1000 bots × 2 Hz × ~37 s effective ≈ 74 k frames) is completely sound.

### 🟢 GREEN (LGTM observation)

**G1 — `Timer.Sample.stop` safely isolated without swallowing application errors.**
- **Source:** `OutboundSender.java:323-329`
- **Severity:** GREEN
- **Claim:** Wrapping `sample.stop()` catches the Micrometer histogram-rotation failure mode.
- **Observation:** Verified. The `try/catch (RuntimeException)` tightly surrounds `sample.stop()` inside the `finally` block. This perfectly isolates the drain VT from Micrometer-internal faults without accidentally swallowing `IOException` or other load-bearing application exceptions.

**G2 — Pushback arithmetic for "10x scale" is factually verified.**
- **Source:** `20-01c-SUMMARY.md` (Pushback)
- **Severity:** GREEN
- **Claim:** 1000 bots * 2 Hz * 37s effective window = ~74k frames expected, which aligns with observed ~76k.
- **Observation:** Verified. The 47-second total capture duration (22s ramp at 50/s + 25s steady state) accurately justifies the 76k frame count. The bots are indeed at full population; the pass-1 reviewer used an invalid 200s denominator.

**G3 — F1 narrative correctly rewritten to reflect source.**
- **Source:** `20-01c-SUMMARY.md` (F1 Key Decision)
- **Severity:** GREEN
- **Claim:** The cap is world-aggregate; nutrients bypass `AdmissionGate` entirely.
- **Observation:** Verified. The code confirms that `AdmissionGate` uses a single `AtomicInteger`, and `SimulationEngine.setEntity:1431` bypasses admission. The SUMMARY rewrite perfectly resolves the pass-1 hallucination and correctly relabels the rejection column to `rejected{respawn-cap}`.

**G4 — Recapture JSON metrics successfully report new metadata.**
- **Source:** `profiles/metrics-1000bots-baseline-0824f1a.json` & `jfr-1000bots-baseline-0824f1a.meta.json`
- **Severity:** GREEN
- **Claim:** Rejection metrics surface `respawn-cap`, `qmax` is non-NaN, and `asprof` metadata is added.
- **Observation:** Verified. Sidecars cleanly report `rejected{reason=respawn-cap}` and `paralife.outbound.queue.depth.max = 0.0`. The `meta.json` files correctly report `asprof_cpu_interval_us` and `asprof_alloc_interval_bytes`.

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

I've reviewed all the files. Here are my findings against the remediation at HEAD `62f2ffa`.

---

## RED

**R1 — `tick.health.work-time-ms` pushback is factually wrong.**
The SUMMARY pushback section claims `paralife.tick.health.work-time-ms` is "verified empty … all 18 samples" and "reads null in the 20-01c sidecars", dismissing codex/opencode as hallucinating. The committed 0824f1a sidecars show numeric values in every sample:
- `metrics-1000bots-baseline-0824f1a.json`: values 56.0, 46.0, 32.0, 39.0, 44.0, 60.0
- `metrics-500bots-baseline-0824f1a.json`: values 35.0, 28.0, 25.0, 30.0, 22.0, 55.0
- `metrics-100bots-baseline-0824f1a.json`: values 23.0, 21.0, 20.0, 25.0, 19.0, 31.0

The jq command in the pushback would have returned these numeric values, not null. The pass-1 codex and opencode reviewers were correct. **Recommendation**: rewrite §Pushback "tick.health" bullet and Caveat #3 — drop the "reviewer hallucination" claim and acknowledge the gauge IS live. Optionally investigate why jq was reported as empty (wrong file path? wrong field name?).

**R2 — SUMMARY headline row count mismatch for 1000-tier respawn-cap.**
The headline table shows `rejected{respawn-cap}@end = 58`. The 1000-bot sidecar sample 6 shows `paralife_admission_rejected` COUNT = 58. Correct. However the table implies a monotonically-rising column name `@end`, and the 500-tier shows 0 — which is a non-presence (meter never written → `null` in JSON), not a counter reaching 0. This is at worst a minor presentation inconsistency since the sidecar `null` ≠ `0` at the data level, but 0 rejections is the correct interpretation. **Recommendation**: add a note that `0` at 100/500-tier means "meter never incremented" (= genuinely zero), or represent it as `—` to match the sidecar null representation.

---

## YELLOW

**Y1 — D1 markDead + cleanupBot double-decrement possible under death-then-close interleaving.**
`markDead` at line 994 removes `ATTR_ENTITY_ID`, then does `decActiveBucketByTags` + `releaseBucketTags`. If `cleanupBot` fires on the same session afterward, `cleanupBot:918`'s `remove(ATTR_ENTITY_ID)` returns null, `wasRegistered` stays true (ATTR_ENTITY_TYPE not removed by markDead), and the fallback `decActiveBucket(s)` at line 943 fires — using session tags that likely match the same attribution bucket, causing a second decrement. Severity is low: death-then-close on the same session is a narrow window (entity dies → broadcaster calls markDead → TCP close arrives before next tick). The plan explicitly discusses this race and accepts it. The `releaseSlot()` decision (not calling it from markDead) is independently correct per `AdmissionGate.java:148`'s `req.isRespawn()` guard. **Recommendation**: accept as deferred; document in a one-line comment on markDead that death-then-close races exist and are bounded by the tick pipeline ordering.

**Y2 — enc.cnt in headline sentence undershoots actual.** Headline says "~74 k frames captured at the 1000-tier" but the table shows 76,848. Floor estimate vs actual — a minor mismatch. **Recommendation**: change "~74 k" to "~77 k" or "76,848" for consistency with the table.

**Y3 — SUMMARY enc.max column derivation ambiguous.** Table column says `enc.max` and values are 8/9/22 ms. The SUMMARY key-decision §F2 says these are `MAX` from the Actuator JSON timer. Verified: sidecar 1000-bot sample 6 MAX = 0.022095784 s = 22.1 ms. But the 500-bot sidecar MAX stays at 0.008745972 s across all 6 samples — this is the Timer's since-registration MAX, not a per-sample peak. The column header `enc.max` is fine. **Recommendation**: no action needed; just noting that `tick.max` and `enc.max` are both since-registration DistributionSummary.MAX, which the SUMMARY correctly identifies.

**Y4 — D3 `log.warn` for double-register is too quiet for test misconfiguration.** "First supplier wins" semantics mean a test that calls `registerOutboundQueueDepthMaxGauge(mockSupplier)` after production init silently keeps the production supplier. The test would read `qmax` off the production `OutboundSender` instead of the mock — a test correctness bug with only a single `log.warn` as signal. **Recommendation**: add a comment noting test authors must call `registerOutboundQueueDepthMaxGauge` BEFORE `OutboundSender` construction, or upgrade to `log.error`. Not a merge-blocker.

---

## GREEN

**G1 — D1 markDead pattern matches cleanupBot.** Both now follow the same lookup → dec → release sequence using `lookupBucketTags`, `decActiveBucketByTags`, `releaseBucketTags`. EntityId is captured into a local before the `remove(ATTR_ENTITY_ID)` call, so `lookupBucketTags(entityId)` is safe. ✓

**G2 — D1 does not call `releaseSlot()`.** `AdmissionGate.java:148`'s `if (!req.isRespawn())` guard means respawns don't consume a new slot. Calling `releaseSlot()` in markDead would over-release on every death since the slot is per-session, not per-entity-life. Correct. ✓

**G3 — D2 `sample.stop` try/catch catches RuntimeException.** Micrometer histogram-rotation/registry-shutdown races throw inside `stop`, not Error-level. Drain VT survives, timer sample for that frame is lost — acceptable trade for a profiling baseline. ✓

**G4 — D3 gauge double-register guard is correct.** Micrometer dedupes by name+tags; without the guard, a second call silently swapped the supplier behind the deduped meter. Now early-returns. "First supplier wins" is correct for production (exactly one `@PostConstruct` registration). ✓

**G5 — Recapture sanity checks all pass.**
| Check | 100-bot | 500-bot | 1000-bot |
|-------|---------|---------|-----------|
| `active.entities` tracks live (not leak) | 100→100→100→97→97→100 | 483→494→500→500→500→500 | 966→1000→1000→998→966→851 ✓ |
| `rejected` reason | null | null | `respawn-cap` only (1/17/58) ✓ |
| No `world-full` | ✓ | ✓ | ✓ |
| `qmax` non-NaN all samples | 0.0×6 | 0.0×6 | 0.0×6 ✓ |
| `detach.timeout` = 0 | ✓ | ✓ | ✓ |
| `meta.json` has asprof fields | ✓ | ✓ | ✓ |

**G6 — F1 narrative now consistent with `AdmissionGate.java:58,140-151`.** Cap is world-aggregate, `reservedSlots` is a single `AtomicInteger`, nutrients bypass `AdmissionGate` via `SimulationEngine.setEntity:1431`. Rejection column relabeled `respawn-cap`. No "per-bucket cap" or "nutrient bucket" language remains. ✓

**G7 — D-20 reference removed from cap discussion.** Summary now says "benchmark-time JVM-flag override; production default (`paralife.admission.cap: 256` at `application.yml:65`) unchanged." D-20 properly scoped to `outbound-queue-size`. ✓

**G8 — 10× scale pushback arithmetic verified against sidecar deltas.** Sample 1→6 delta: 76,848 − 24,169 = 52,679 frames over 51 ticks (47→98 COUNT) ≈ 25.5 s. At ~980 average active entities: 980 × 51 = ~50,000 expected; observed 52,679 ≈ 105%. Consistent with full population, not ~190. Claude inline R3's denominator (200s) was genuinely wrong. ✓

**G9 — Verification gate #5 correctly notes Actuator JSON shape.** SUMMARY now says "Actuator JSON exposes COUNT/TOTAL_TIME/MAX only; configured percentiles are available on `/actuator/prometheus`" instead of claiming p50/p95/p99 are in the sidecars. ✓

---

## Summary

- **2 RED**: R1 (tick.health pushback is factually wrong — sidecars show numeric values, not null) must be fixed before merge. R2 is minor (null-vs-0 presentation).
- **4 YELLOW**: D1 double-decrement race (low-probability, pre-existing pattern), enc.cnt headline mismatch, D3 `log.warn` quietness, column derivation note.
- **9 GREEN**: D1/D2/D3 are correct code changes, all recapture sanity checks pass, F1/F2/F6 narratives are now consistent with source, 10× arithmetic verified, D-20 reference cleanup verified.

---

## Consensus Summary

### Agreed Strengths
- **D2 `sample.stop` try/catch** correctly isolates Micrometer histogram-rotation faults from drain VT without swallowing load-bearing exceptions (claude N5, gemini G1, opencode G3).
- **D3 gauge double-register guard** sound for production single-`@PostConstruct` wiring (claude N6, gemini observation, opencode G4).
- **D4 asprof metadata** lands in all `meta.json` files: `asprof_cpu_interval_us`, `asprof_alloc_interval_bytes` (claude N7, gemini G4, opencode G5).
- **Recapture sanity** all green: `active.entities` tracks live population (966→1000→...→851 trajectory, not leak); `rejected` reason = `respawn-cap` only, zero `world-full`; `qmax` numeric 0 across all 18 samples; `detach.timeout` = 0 (claude N8, gemini G4, opencode G5).
- **F1 narrative rewrite** correctly reflects source: cap is world-aggregate, `reservedSlots` single `AtomicInteger` at `AdmissionGate.java:58`, nutrients bypass via `SimulationEngine.setEntity:1431`, rejection column relabeled `respawn-cap` (claude N9, gemini G3, opencode G6).
- **D-20 reference cleanup**: cap discussion correctly separates benchmark JVM-flag override from production default at `application.yml:65` (claude N10, opencode G7).
- **10× scale pushback arithmetic** directionally correct against sidecar deltas — bots at near-full population, claude inline R3's 200s denominator was wrong (claude N2, gemini G2, opencode G8).

### Agreed Concerns
- **RED — D1 `markDead` introduces double-decrement on death-then-close path** (claude N1, gemini R1, opencode Y1). `markDead` at `WorldWebSocketHandler.java:992-1000` removes `ATTR_ENTITY_ID` + decs bucket but leaves `ATTR_ENTITY_TYPE`. On subsequent `cleanupBot`: `wasRegistered = true`, `entityId = null`, falls into legacy fallback `decActiveBucket(s)` at line 943 → second decrement. Net −1 per session closed after final death. Severity split: claude/gemini RED (latent gauge underflow in long-running production / explains 851 dip); opencode YELLOW (narrow race window, plan accepts). Fix: skip cleanupBot bucket-dec when `entityId == null` (markDead already decremented).
- **RED/YELLOW — `tick.health.work-time-ms` pushback factually wrong against 0824f1a sidecars** (claude N11, opencode R1). SUMMARY caveat #3 + Pushback claim "null in all 18 samples / reviewer hallucination", but 1000-bot sample 6 = 60.0 ms, all 18 samples carry numeric values across all tiers. The "hallucination" framing applies to 1818eeb sidecars, NOT 0824f1a. Fix: rewrite caveat #3 / Pushback to acknowledge gauge is live in this capture; verify via correct `jq` path.
- **YELLOW — Stale `cleanupBot` comment + loose pushback precision** (claude N4, gemini Y2, opencode Y2). `WorldWebSocketHandler.java:935` doc-comment "Hits only when no snapshot was ever captured" is false post-D1. SUMMARY pushback mixes stale 76 048 (1818eeb) vs current 76 848 (0824f1a) and overclaims "103%" precision; arithmetic directionally correct but rhetoric should be loosened.

### Divergent Views
- **D3 "first-wins" early-return semantics** — claude N3 / opencode Y4 frame as fragile for tests but acceptable (production single-threaded, log.warn appropriate); gemini Y1 argues the opposite — recommends reverting to overwriting assignment so test double-injection works. Worth investigating: which call site actually re-registers? If only OutboundSender constructor, first-wins is correct; if tests inject post-construction, gemini's revert wins. Resolution depends on test-injection design intent.
- **N1 severity** — claude (RED, operator-facing gauge accuracy is D1's entire point, post-shutdown sampling would expose it) and gemini (RED, explicitly explains 851 dip) vs opencode (YELLOW, narrow death-then-close window). Disagreement on whether the 851 trajectory is the bug manifesting or the genuine respawn-cap-exhausted live count. Falsifiable: extend harness/test to scrape AFTER connections close — pre-fix bucket goes negative.
- **Headline column representation** — opencode R2 flags `0` vs `—` for non-present meters (500/100-tier `rejected{respawn-cap}=0` is meter-never-written, not zero counter); claude/gemini did not raise. Presentation polish, not load-bearing.
