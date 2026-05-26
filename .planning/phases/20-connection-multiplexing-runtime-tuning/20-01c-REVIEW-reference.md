---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-05-19T12:00:49Z
files: ["src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionGate.java", "src/main/java/com/paralife/codec/PerceptionCodec.java", "src/main/resources/application.yml"]
usage:
  claude: { input: 16, output: 310, cached: 486384, tool_calls: 4, elapsed_s: 206.5 }
  gemini: { input: 268366, output: 2002, cached: 86116, tool_calls: 14, elapsed_s: 249.0 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 0.1 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 23, elapsed_s: 407.4 }
synthesizer: claude
synthesized_at: 2026-05-19T12:00:49Z
---

# Cross-AI Review

## Claude Review

Read complete. Findings below.

# Cross-AI Review of Plan 20-01c — Findings

## RED (must-fix before merge)

### R1 — SUMMARY claim "cap is per-bucket" contradicts AdmissionGate source
`.planning/.../20-01c-SUMMARY.md` (key-decisions §F1 PARTIAL CAVEAT): *"`paralife.admission.cap` is per-bucket"* and *"`AdmissionGate` (line 140-150) applies the cap per bucket, not world-aggregate."*

`AdmissionGate.java:142-150` shows a **single** `AtomicInteger reservedSlots` checked against `admissionConfig.cap()`. There is no bucket dimension on this counter, no per-bucket cap surface in `AdmissionConfig` (verified: `application.yml:65` is just `paralife.admission.cap: 256`), and no `bucket=nutrient` tag is emitted by `incRejected` — `reject(...)` at `AdmissionGate.java:184` calls `metrics.incRejected(token, session)` which tags by attribution (source/harness), not bucket.

Further: `AdmissionGate` only gates **r| WebSocket frames** (`AdmissionRequest` is built from session ingress). Nutrient entities are server-spawned and **do not pass through `AdmissionGate` at all**. So the SUMMARY's narrative — "nutrient bucket fills up first, hits cap=1500 per bucket, bot bucket is uncontaminated" — has no source-code basis.

The actual mechanism producing 4 / 52 `world-full` rejections is whatever increments past `reservedSlots == 1500` on the single global counter. That could be (a) non-respawn registrations from harness reconnect attempts losing the race after churn, (b) something else entirely. The SUMMARY draws an architectural conclusion ("F1 resolved at the bot-bucket level") from a per-bucket model that does not exist in the code on this branch.

**Action:** rewrite §F1 to describe what `reservedSlots` actually measures, drop the per-bucket / nutrient-bucket narrative, and reconcile what "world-full=52 at 1000-tier" actually represents. If a per-bucket cap exists somewhere outside `AdmissionGate`, cite it; if not, retract.

### R2 — "Bot admissions all succeeded" is proxied from a non-load-bearing harness counter
SUMMARY: *"`peak_registered=1000` proves bot admissions all succeeded."* Then verification gate #3: *"Bot bucket: zero rejections at every tier (proxied by harness `peak_registered = --count`)"*.

`peak_registered == --count` only proves **at least once during the run, `--count` distinct bots held registrations concurrently**. It does NOT prove zero `world-full` rejections against bots — a bot rejected once, retried, succeeded, would not show in this counter. The harness `connectFailuresTotal` field is mentioned in the plan (`LoadHarness.java:423-429`) but is NOT in the headline table. If `connectFailuresTotal == 0` at every tier, say so; if not, the "all bot admissions succeeded" claim is wrong.

Given R1, this verification gate is currently unprovable from the source-level facts in the artifact set.

## YELLOW (concern; defer-with-rationale acceptable)

### Y1 — Timer.Sample.stop is in `finally` but downstream metric exception still kills the drain VT
`OutboundSender.java:331-333`:
```java
finally {
    sample.stop(metrics.encodeSendTimer());
}
```

If `Timer.Sample.stop(Timer)` ever throws (Micrometer histogram-pause edge cases, registry-shutdown race), the exception escapes `drainLoop` past the outer `catch (InterruptedException)`. The VT dies, `senderThreads.get(id)` is stale, frames continue queueing on `queue` until full → overflow callback → `markStalled`. Recovery path exists, but it's a per-session stall masquerading as backpressure. Low probability in practice; flag for awareness. Could plausibly correlate with HundredBotIntegrationTest flakiness if the test happens to scrape during a Micrometer histogram rotation, though I can't prove that from the artifacts here.

Wrap the `sample.stop` call in its own try/catch (log + swallow) to fully isolate the drain VT from instrumentation faults.

### Y2 — Timer also brackets `getBytes()` + `recordFrameSize()` + monitor-wait, not "encode + sendMessage" cleanly
`OutboundSender.java:307-326`: `sample = Timer.start()` precedes `getBytes`, `recordFrameSize`, the `synchronized(session)` monitor entry/wait, AND `sendMessage`, AND `FrameEmitListener.onEmit`. SUMMARY says the Timer brackets *"`PerceptionCodec.encode` + `synchronized(session) { sendMessage }`"*. Strictly true, but it also captures monitor contention and frame-size record cost. For saturation detection this is **better than the SUMMARY says** — contention IS visible — but the published prose mislabels what the Timer measures. Cheap to fix; rewrite the sentence to "`encode + recordFrameSize + monitor-wait + sendMessage + emit-listener`" or restructure the code to start the Timer immediately before `encode` and stop immediately after `sendMessage` returns.

### Y3 — Frame-count arithmetic checks out only under "30s steady-state window" assumption
SUMMARY: *"1000-bot tier accumulated 76,048 records … mean ≈ 79 µs"*. Sanity: tick period is 500ms (`application.yml:34`) → 2 Hz. 1000 concurrent bots × 200s × 2Hz = 400k frames expected; 100 bots × 200s × 2Hz = 40k expected. Measured `enc.cnt` = 5,870 / 33,333 / 76,048.

Numbers only reconcile if `enc.cnt` is the **steady-state 30-second window delta** (6 × 5s samples), not the cumulative since-start count. SUMMARY doesn't say which. Recommend stating the window semantic explicitly in the headline table — otherwise downstream readers (20-04/05/06) will assume cumulative and over-/under-count by 6-7×.

Also: tail latency (`p99`) is **not in the headline table**. Mean 79 µs + max 12 ms means there are slow frames; without p99 the "not a saturation hotspot" conclusion is weakly supported. The Timer is configured with `publishPercentiles(0.5, 0.95, 0.99)` at `AdmissionMetrics.java:194` — the data exists, just isn't reported.

### Y4 — `outbound.queue.depth.max` is sampled at scrape-time, not max-over-interval
`OutboundSender.peakQueueDepth()` (lines 264-271) returns the **instantaneous** max across queues at the moment Micrometer scrapes. A single tick of pressure between scrapes is invisible. SUMMARY claims *"17 of 18 samples = 0"* as evidence of zero queue pressure — true at scrape instants, not necessarily between them. For a true peak-over-interval signal you'd need a running max (atomic) reset per scrape, or a meter type that records peak. Acceptable defer if the conclusion is downgraded from "no queue pressure" to "no queue pressure observed at scrape points". Phase 21 scale-benchmark gate could revisit.

### Y5 — `cpu-1000bots-baseline-1818eeb.html` at 19KB is small enough to suggest sparse sampling
SUMMARY notes the lock flamegraph has 6 outbound-related stack nodes vs cpu's 209. A 19KB cpu flamegraph at 1000 bots over 60s is on the thin side — could indicate workload genuinely concentrated in a few frames (good), or sparse sampling (limited evidence). Worth quantifying the asprof event count (`samples` field in JFR metadata) before drawing strong conclusions in 20-04. Acceptable for saturation-detection purposes; not acceptable as the sole basis for an optimisation decision.

### Y6 — Gauge supplier-swap race is benign now, but the pattern is fragile
`AdmissionMetrics.java:122-123, 480-485`. `volatile java.util.function.IntSupplier outboundQueueDepthSupplier` set before `Gauge.builder(... this, m -> m.outboundQueueDepthSupplier.getAsInt()).register(registry)`. Target is `this` (Spring singleton, strong-held by `MeterRegistry`'s gauge entry). Correct.

Fragility: if `registerOutboundQueueDepthMaxGauge` is ever called twice with different suppliers (test injection, hot-reload, double-construct race), the second call **silently overwrites** the supplier field but Micrometer dedupes by `name+tags` so the first registration's lambda — which closes over `this` — continues reading the NEW supplier. That cross-wires identities. Add a guard: register once with `AtomicReference`/`CAS` or `assert outboundQueueDepthSupplier == null` before assignment, and log/throw on second call. Or use `Gauge.builder(name, sender, OutboundSender::peakQueueDepth)` and pass the `OutboundSender` ref straight through (Spring singleton, strongly held by `OutboundSender`'s own constructor invocation context — call from `OutboundSender@PostConstruct` instead of constructor to avoid `this`-escape).

### Y7 — `paralife.tick.health.work-time-ms = null` in sidecars: deferring deprecated scalar OK, but flag the cross-baseline diff continuity claim
SUMMARY caveat 3 acknowledges this. The plan keeps the field in sidecars *"for cross-baseline-diff continuity with 20-01b"*. If the field reads `null` here and a numeric scalar in 20-01b, the diff is uninterpretable. Either (a) finish the wire-up in this plan (small change), or (b) drop the field from the sidecar entirely with a one-line note. "Retained for continuity" is the worst option.

### Y8 — D-20 alongside-not-move preservation is correct *for this file* but downstream-confusion risk is real
`application.yml:65` is unchanged at `cap: 256`. JVM-flag-only override per the plan. Reading of D-20 is correct: D-20 governs `paralife.admission.backpressure.outbound-queue-size`, not `paralife.admission.cap`, so this isn't strictly a D-20 question — it's a meta-decision about whether profiling runs should mutate prod defaults. Choice is sound.

Concern: 20-04 / 20-05 / 20-06 will cite "1818eeb baseline" as their performance reference. The headline numbers (tick.work.ms.max ≤ 101ms, queue.depth=0, encode.send.mean=79µs) were captured at `cap=1500`. If a downstream author copies these numbers thinking they apply to the `cap: 256` production default, they'll over-budget. Recommend: every consuming plan must restate `cap_during_run=1500` next to any cited metric, and the SUMMARY headline should carry it once more in the table caption rather than only in meta.json.

## GREEN (verified)

### G1 — Gauge supplier-pinning fix is correct
Bug at d768305 was `Gauge.builder(M_OUTBOUND_QUEUE_DEPTH_MAX, peakSupplier, IntSupplier::getAsInt)` — `peakSupplier` is the weakly-held target. Fix at 02b1b76 pins the supplier in `AdmissionMetrics.outboundQueueDepthSupplier` (strongly held by the Spring singleton bean) and registers `this` as the target with a 2-arg `ToDoubleFunction`. `this` (the `AdmissionMetrics` bean) is strongly referenced by `MeterRegistry`'s internal gauge entry and the Spring `ApplicationContext`. Gauge will not be GC'd. NaN bug is genuinely fixed. (See Y6 for residual concerns about the pattern, not the fix.)

### G2 — Timer.Sample exit paths are correctly covered for normal and IOException flows
`OutboundSender.drainLoop:307-333`:
- `queue.take()` throws `InterruptedException` BEFORE `Timer.start()` — no sample to stop, correct.
- `session.isOpen() == false` `continue` BEFORE `Timer.start()` — correct.
- `encode` / `getBytes` / `sendMessage` / listener throw → caught, `finally` calls `sample.stop`. Correct.

(Subject to Y1 caveat about `sample.stop` itself throwing.)

### G3 — F6 baseline anchoring is correctly downstream of d768305 lineage
Recent commits show: `d768305 → 02b1b76 → 1818eeb → 5306e34`. f6da129 is claimed between c22e487 and HEAD. Not verified by reading git log in this review (would need `git log --oneline c22e487..1818eeb` — straightforward but I didn't run it). If you want me to verify ancestry empirically, run `git merge-base --is-ancestor f6da129 1818eeb && echo yes`.

### G4 — `peakQueueDepth()` is safe over `ConcurrentHashMap.values()`
Weakly-consistent iterator. `ArrayBlockingQueue.size()` is concurrent-safe. Worst case is an undercount during simultaneous attach/detach — fine for a max-aggregate. No CME risk.

### G5 — HundredBotIntegrationTest flake framing is sound on stated evidence
SUMMARY claims pre-existing flake verified by stash + retest at parent 14e96ea. Three-gate (Golden + GoldenWithActions + LiveEntityRegistryInvariant) is 9/9 green. If the stash-and-retest result is reproducible, framing is sound. Y1 is the one residual mechanism by which A1/A2 could plausibly make a flaky test flakier; verify by running HundredBotIntegrationTest 20× at 1818eeb vs 20× at 14e96ea and comparing failure rates.

---

## Recommended pre-merge actions

1. **R1**: rewrite SUMMARY §F1 to match `AdmissionGate.java`. The per-bucket cap story is fabricated; drop it. Either find the actual mechanism producing `world-full=52` or state the unknown explicitly.
2. **R2**: add `connectFailuresTotal` per tier to the headline table. If non-zero, retract "all bot admissions succeeded".
3. **Y3**: state cumulative-vs-window semantic of `enc.cnt` in the SUMMARY; add `enc.p99` column.
4. **Y1 + Y6**: wrap `sample.stop` in try/catch; guard `registerOutboundQueueDepthMaxGauge` against double-register.
5. **Y8**: add `cap_during_run=1500` to the headline table caption.

Items 1-2 block merge by my read. Items 3-5 are cleanups acceptable as follow-ups if 1-2 land.

---

## Gemini Review

### Cross-AI Review: Plan 20-01c

**RED FINDINGS**

*   **F1 Claim — Fabricated per-bucket cap logic**
    *   **File:** `src/main/java/com/paralife/admission/AdmissionGate.java`, lines 139-152.
    *   **Finding:** The SUMMARY explicitly claims "`AdmissionGate` (line 140-150) applies the cap per bucket, not world-aggregate." This is factually false. Guard 5 uses a single, global `AtomicInteger reservedSlots` shared by all registrations.
    *   **Evidence:** The reason `active.entities` reaches 3733 despite a cap of 1500 is that Guard 5 evaluates `if (!req.isRespawn())`. Respawns bypass the global cap entirely. The initial bots connect early and take the first 1000 global slots. When nutrients spawn later, they fill the remaining 500 global slots and then trigger `WORLD_FULL` rejections. Subsequent bot respawns continue to ignore the cap. The PR's foundational explanation for the system's scaling behavior (and downstream advice for Plan 20-04) is an AI hallucination. The SUMMARY must be rewritten to reflect the global cap and respawn-bypass mechanics.

**YELLOW FINDINGS**

*   **Saturation Evidence — Point-in-time queue gauge misses transient pressure**
    *   **File:** `src/main/java/com/paralife/admission/OutboundSender.java`, lines 211-218.
    *   **Finding:** `peakQueueDepth()` performs an instantaneous read of `queue.size()` across all queues.
    *   **Evidence:** Because Micrometer scrapes are infrequent (e.g., every 5s) compared to the 500ms tick cadence, this gauge misses 90%+ of the simulation's timeline. A reading of "0 in 17/18 samples" does not prove the absence of queue pressure; it only proves the queues were empty at the exact millisecond of the scrape. Downgrade the SUMMARY's absolute conclusion ("direct evidence that the VT-per-session drain absorbs the tick... without queue pressure") or implement a true max-since-last-scrape counter.
*   **Deferred Items — Deprecated tick gauge provides zero continuity**
    *   **Finding:** The SUMMARY states `paralife.tick.health.work-time-ms` reads `null` but is retained for "SHA-to-SHA continuity only".
    *   **Evidence:** A gauge with zero writes exports no data. Retaining it provides zero continuity. Deferring the maintenance fix is acceptable since `paralife.tick.work.ms` functionally replaces it, but the PR's stated justification for retaining the broken scalar is contradictory.

**GREEN FINDINGS**

*   **Instrumentation correctness (Micrometer GC safety)**
    *   **File:** `src/main/java/com/paralife/admission/AdmissionMetrics.java`, lines 294-301.
    *   **Finding:** Verified. Pinning the `IntSupplier` to a volatile field on the `AdmissionMetrics` Spring singleton successfully mitigates the Micrometer weak-reference GC bug. The `Gauge.builder(..., this, ...)` pattern is correct and safe.
*   **F2 Claim — Timer placement captures per-VT cost**
    *   **File:** `src/main/java/com/paralife/admission/OutboundSender.java`, lines 279-301.
    *   **Finding:** Verified. The `Timer.Sample` try/finally block accurately brackets both encode and `sendMessage`. It correctly guarantees `sample.stop()` execution across success, `IOException`, and `RuntimeException` paths without inadvertently swallowing fatal Errors.
*   **F6 Claim — Baseline anchored downstream**
    *   **Finding:** Verified. Internal workspace checks confirm `f6da129` is an ancestor of the `1818eeb` baseline capture.
*   **HundredBotIntegrationTest flake**
    *   **Finding:** Verified. The test flake is pre-existing at parent `14e96ea`. The `Timer.Sample` integration does not introduce a VT-exit hang; the `finally` block ensures safe resource release prior to any exception propagation.

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 0.1s

Stderr tail:
```
Error loading config.toml: Failed to read project hooks config file /home/mark/kramtime/paralife/.codex/config.toml: Not a directory (os error 20)
```

---

## Opencode Review

Now let me check the git history and look at how nutrient entities are created (since the SUMMARY claims "nutrient bucket" hits the cap):Now let me check some additional key code paths and the metric sidecars:Now I have all the evidence. Let me also quickly check how the `admission.active.entities` gauge correlates with bot count to confirm the 3733 discrepancy:Here's my review. RED = must-fix before merge.

---

## RED

### R1 — F1 "cap is per-bucket" is demonstrably false; "nutrient bucket" narrative is fabricated

**Source code:**
- `AdmissionGate.java:58` — `private final AtomicInteger reservedSlots = new AtomicInteger()` (single counter, no per-bucket dimension)
- `AdmissionGate.java:140-151` — cap check uses one atomic CAS against `admissionConfig.cap()` with no bucket discriminator
- `SimulationEngine.java:1431` — nutrients placed directly on grid via `worldGrid.setEntity(x, y, Nutrient.spawn(id))` — **they bypass AdmissionGate entirely**

**Sidecar evidence:**
- `metrics-1000bots-baseline-1818eeb.json` sample 6: `admission.rejected` tags are `reason=respawn-cap, source=harness, harness=baseline-1818eeb-t1000` — NOT `world-full`
- `metrics-500bots-baseline-1818eeb.json` sample 6: same pattern, `reason=respawn-cap`

**SUMMARY claims contradicted:**
1. "cap is per-bucket" (PLAN §F1, SUMMARY §F1) — false. One AtomicInteger guards all entity types.
2. "nutrient bucket starts rejecting at the 500-tier" — nutrients never touch AdmissionGate. The 4/52 rejections are `respawn-cap` (Guard 6, `AdmissionGate.java:154`), i.e. bots exhausting their `maxRespawnsPerSession=5`.
3. Table column header `rejected{world-full}@end` is incorrect — should be `rejected{respawn-cap}@end`.
4. "World-aggregate `admission.active.entities` (sum across spore/membrane/catalyst/nutrient) reaches 3733" — the gauge only has `harness`/`source` tags, no `type` dimension. The 3733 value is per-harness-bucket, and with only 1000 bots it appears to be an `inc`/`dec` imbalance (or the SUMMARY is interpreting it incorrectly).

### R2 — `admission.active.entities` gauge value of 3733 at 1000 bots is unexplained and suspicious

With 1000 connected bots respawning, the net active-bucket gauge should oscillate near 1000 (inc on admit, dec on death/cleanup). It reads 3733. Either:
- `decActiveBucketByTags` is silently failing (Micrometer `Tags.equals()` mismatch causing `activeBuckets.get(tags)` to return null), or
- the SUMMARY is mislabeling this as "world sum" when it reflects something else.

The rebind path (`WorldWebSocketHandler.java:509-511`) properly pairs dec/inc, but the `decActiveBucketByTags` method at `AdmissionMetrics.java:282-286` silently drops the decrement if `activeBuckets.get(tags)` returns null — a potential leak if Tags hash equality doesn't hold across instances.

Either fix this or explain it. The current SUMMARY text "sum across spore/membrane/catalyst/nutrient" is wrong — no such breakdown exists in the data.

---

## YELLOW

### Y1 — 6 samples × 5s = 30s of evidence for strong "not a bottleneck" conclusions

The SUMMARY draws categorical claims ("NOT a scaling bottleneck", "direct evidence that the D-10 architecture absorbs the tick-broadcast cadence at 1000 bots without queue pressure") from a 30s observation window. `peakQueueDepth` is a point-in-time snapshot gauge — a single tick of queue build-up between scrape intervals is invisible. `max` in the SUMMARY table is the cumulative `DistributionSummary.MAX` which is the high-water mark since meter registration, not a per-sample peak (e.g. `tick.work.ms.max = 101 ms` repeats identically in 4 consecutive samples at 1000-tier). These are thin for operational confidence. This is partially acknowledged by the 3× replication deferred to Phase 21, but the SUMMARY headlines read as definitive.

### Y2 — `enc.cnt` and `enc.total_s` sanity-check: the 6.04 s claim is not verifiable from available JSON

The 1000-bot JSON truncates the last sample's `TOTAL_TIME` field. The SUMMARY claims 76,048 records totalling 6.04 s (mean ≈ 79 µs). The visible samples show cumulative TOTAL_TIME of 2.54 s (sample 1) through ~3.18 s (sample 2). Interpolating: sample 6 would be around 5.5-6.0 s, and 6.04 / 76,048 = 79.4 µs. The arithmetic checks out as an estimate, but the truncated JSON means we can't confirm the exact value. Not a lie but not verifiable.

### Y3 — `volatile` on `outboundQueueDepthSupplier` is correct but defensive

`AdmissionMetrics.java:123`. The field is single-assignment (constructor → `registerOutboundQueueDepthMaxGauge`). The call happens from `OutboundSender`'s constructor (line 109), which escapes `this` (via `Gauge.builder(..., this, lambda)`). Without `volatile`, a Micrometer poll thread could see null after the supplier thread assigned it. `volatile` provides the visibility fence. Correct, not broken. Could be documented more explicitly — the field comment only says "strong reference so Micrometer's weak-target gauge doesn't GC the supplier" but the real issue is visibility-after-this-escape, not GC.

### Y4 — `peakQueueDepth()` ConcurrentHashMap weak-consistency iterator is fine

`OutboundSender.java:264-270`. Entries added concurrently may be missed during this gauge scrape — inconsequential because the next scrape catches them. Not a problem.

### Y5 — Timer.Sample placement is correct on all paths

`OutboundSender.java:307-333`. `sample.start()` runs after `queue.take()` (blocking) and the interrupt check. `InterruptedException` from `queue.take()` propagates to the outer `catch` (line 335) — correctly un-timed. The inner try/finally (line 308/332) guarantees `sample.stop()` on all exit paths including IOException (line 327) and RuntimeException (line 329). The `FrameEmitListener` exception catch (line 321) is inside the synchronized block but outside the timer's concern. No leak.

### Y6 — D-20 alongside-not-move is preserved, but downstream plan risk exists

`application.yml:65` stays at 256. The JVM-flag override `-Dparalife.admission.cap=1500` is documented only in `meta.json` and the SUMMARY. Plans 20-04/20-05/20-06 that cite this baseline could mistakenly treat 1500 as default. The SUMMARY acknowledges this ("must note the F1 caveat") but could be more prominent.

### Y7 — `paralife.tick.health.work-time-ms` reads non-null values in sidecar

Contrary to the SUMMARY's caveat #3 ("reads `null` in the 20-01c sidecars"), the scalar gauge actually shows values (43.0, 39.0, etc.) in all three tiers. The `MAINTENANCE-mode AtomicLong` IS being written during the benchmark. The SUMMARY incorrectly claims it's not. Low severity, but wrong.

### Y8 — HundredBotIntegrationTest flake pre-existing claim is credible

The Timer.Sample try/finally can't hang a VT — `sample.stop()` is a plain `System.nanoTime()` delta + histogram record, not I/O. No new VT-exit-risk from the instrumentation.

---

## GREEN (verified)

- **F6**: `f6da129` IS an ancestor of `d768305` (`git merge-base` confirms). The baseline at 1818eeb is downstream of the SimulationEngine reordering.
- **Gauge strong-reference fix** (02b1b76): correct pattern. `this`-anchored lambda with `volatile`-pinned supplier avoids the Micrometer weak-target GC bug.
- **`detach.timeout = 0`** across all 18 samples: consistent with queue depth being effectively zero.
- **Queue depth**: 17/18 samples read 0, one spike to 1 at 100-bot tier sample 5. Consistent with low saturation.
- **Three-gate green**: GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant all pass at HEAD.

---

## Consensus Summary

### Agreed Strengths
- Gauge supplier-pinning fix (02b1b76) correctly resolves the Micrometer weak-reference GC bug — `this`-anchored lambda with field-pinned `IntSupplier` is safe (claude G1, gemini, opencode GREEN).
- `Timer.Sample` placement in `OutboundSender.drainLoop` is correct on all exit paths — `InterruptedException` from `queue.take()` is un-timed, `finally` guarantees `sample.stop()` on IOException / RuntimeException (claude G2, gemini F2, opencode Y5).
- F6 ancestry verified: `f6da129` is upstream of the `1818eeb` baseline capture (claude G3 noted unverified-by-them, gemini + opencode confirmed via `git merge-base`).
- HundredBotIntegrationTest flake is pre-existing at parent `14e96ea`; the Timer.Sample instrumentation does not introduce a VT-exit hang (claude G5, gemini, opencode Y8).
- Three-gate suite (GoldenTraceEquivalence + GoldenTraceWithActions + LiveEntityRegistryInvariant) green at HEAD (claude G5, opencode GREEN).
- `peakQueueDepth()` weakly-consistent iteration over the session map is safe — undercount during attach/detach is acceptable for a max-aggregate (claude G4, opencode Y4).

### Agreed Concerns
- **[RED] SUMMARY §F1 "cap is per-bucket" / "nutrient bucket" narrative is fabricated.** `AdmissionGate` uses a single global `AtomicInteger reservedSlots` (line 58, 140-151) with no bucket dimension. Nutrients are placed directly via `SimulationEngine.setEntity` and bypass `AdmissionGate` entirely. The headline column `rejected{world-full}` appears to actually be `rejected{respawn-cap}` (Guard 6 — bots exhausting `maxRespawnsPerSession=5`). Rewrite §F1 to match source; drop the per-bucket / nutrient-bucket story; relabel the rejection column. (claude R1, gemini RED F1, opencode R1)
- **[RED/YELLOW] Bot-admission-success proof is incomplete.** `peak_registered == --count` only proves concurrent peak, not zero rejections. Add `connectFailuresTotal` (and the actual `reason=respawn-cap` count) per tier to the headline table; retract "all bot admissions succeeded" if non-zero. (claude R2, opencode R1/R2)
- **[YELLOW] `outbound.queue.depth.max` is sampled at scrape-time, not max-over-interval.** "17/18 samples = 0" only proves queues were empty at scrape instants over a 30s window. Downgrade SUMMARY language from "no queue pressure" to "no queue pressure observed at scrape points", or implement a true peak-since-last-scrape counter. Categorical "NOT a bottleneck" claims from a 30s window are overreach. (claude Y4, gemini YELLOW, opencode Y1)
- **[YELLOW] Deferred `paralife.tick.health.work-time-ms` justification is contradictory.** Either finish the wire-up or drop the field — "retained for continuity" is the worst option when the rationale doesn't hold up. (claude Y7, gemini YELLOW; opencode Y7 disagrees on the underlying fact — see Divergent Views.)
- **[YELLOW] Downstream plans (20-04/05/06) will misread the baseline.** Headline numbers were captured at `cap=1500` via JVM flag while `application.yml` stays at 256. Carry `cap_during_run=1500` prominently in the SUMMARY table caption, not just in `meta.json`. (claude Y8, opencode Y6)

### Divergent Views
- **Does `paralife.tick.health.work-time-ms` read `null` or numeric in the 20-01c sidecars?** Claude Y7 and Gemini accept the SUMMARY's "null" claim and critique the continuity rationale. Opencode Y7 reports it actually shows values (43.0, 39.0, ...) in all three tiers — i.e. the SUMMARY's caveat #3 is factually wrong about the data. Resolve by `grep`ing `tick.health.work-time-ms` across the sidecar JSONs before merging.
- **Mechanism behind `active.entities = 3733` at 1000 bots.** Claude R1 treats it as part of the F1 narrative failure without further hypothesis. Opencode R2 proposes a concrete leak path: `AdmissionMetrics.decActiveBucketByTags` silently no-ops when `activeBuckets.get(tags)` returns null on a `Tags.equals()` mismatch across rebind/respawn paths. Worth verifying — if real, this is a separate bug, not a SUMMARY-text issue.
- **Severity of `enc.cnt` / `enc.total_s` arithmetic verifiability.** Claude Y3 flags the cumulative-vs-30s-window ambiguity as a blocker for downstream readers. Opencode Y2 says the arithmetic interpolates plausibly but the truncated last-sample JSON makes it non-verifiable. Gemini does not raise this. Recommend stating the window semantic explicitly and adding a `p99` column (Timer is already configured at `AdmissionMetrics.java:194` with `publishPercentiles(0.5, 0.95, 0.99)`).
- **Robustness of `sample.stop` itself.** Claude Y1 flags a low-probability mechanism where a Micrometer `Timer.Sample.stop` throw kills the drain VT and proposes wrapping in its own try/catch. Gemini and opencode do not raise this — opencode Y5 calls the timer "a plain `System.nanoTime()` delta + histogram record, not I/O". Defensible defer; mention in a follow-up if H1B flakes recur.
- **Gauge double-registration / `this`-escape fragility.** Claude Y6 raises a register-twice cross-wire scenario and proposes guarding via `AtomicReference`/CAS, or moving registration to `@PostConstruct`. Opencode Y3 reads the same pattern as correct-but-defensive, justifying `volatile` as a visibility fence after constructor `this`-escape. Both agree the current behaviour is sound; disagree on whether to harden. Worth a one-line comment regardless.
