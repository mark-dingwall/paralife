---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-19T11:23:23Z
files: ["src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/OutboundSender.java"]
usage:
  claude: { input: 10, output: 16, cached: 0, tool_calls: 0, elapsed_s: 151.4 }
  gemini: { input: 58133, output: 1204, cached: 0, tool_calls: 0, elapsed_s: 107.0 }
  codex: { input: 1558842, output: 10681, cached: 1388288, tool_calls: 43, elapsed_s: 229.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 372.1 }
synthesizer: claude
synthesized_at: 2026-05-19T11:23:23Z
---

# Cross-AI Review

## Claude Review

Reviewed code + SUMMARY claims. Findings:

## RED

**R1 — F1 per-bucket-cap claim contradicts visible code.**
`AdmissionGate.java:140-153` shows ONE `AtomicInteger reservedSlots` compared against ONE `admissionConfig.cap()`. No per-bucket counter, no bucket-aware CAS, no `bucket=` tag emission in `reject(...)`. SUMMARY's "cap is per-bucket; bot buckets never hit cap; nutrient bucket hits cap" is unsupported by this source. `admission.rejected{reason=world-full,bucket=nutrient}` (cited in verification gate #3) requires a `bucket` tag dimension not present in `AttributionTagger.tagsFor(session)` per the code visible. Either (a) bucket tagging lives somewhere not shown and SUMMARY should cite it, or (b) the `bucket=nutrient` qualifier is fabricated and `rejected{reason=world-full}` rejections at 500/1000-tier are actual bot-bucket rejections that contradict the "bot admissions all succeeded" headline. Must clarify before merge.

**R2 — `peak_registered = --count` is not a sound proxy for server-side admission success.**
Harness `--report-out.peakRegistered` counts client-side successful CONNECTs (`LoadHarness.java:423-429` per plan). A bot that connects, gets admitted, then dies and never respawns still contributes to peak_registered. A bot that connects then is immediately rejected with E|429 may or may not, depending on harness counter ordering. SUMMARY conflates "harness saw N registered" with "server admitted N". For F1 to be resolved properly, need server-side: `paralife.admission.rejected{reason=world-full}.count == 0` AND `admission.active.entities` peak ≥ `--count`. The latter is undermined by R3.

**R3 — `enc.cnt` arithmetic implies "10× scale" is partly fictional.**
1000-bot tier: 76,048 encode+send records over 200 s with tick interval 500 ms → 400 ticks → max-possible frames if all 1000 bots alive every tick = 400,000. Observed = 76,048 = 19 % of expected. Implies ~190 bots concurrently alive on average, not 1000. 500-tier: 33,333/200,000 = 17 %. 100-tier: 5,870/40,000 = 15 %. Ratios are similar — consistent with a steady-state where most bots die fast and respawn churn (respawns 480/2500/3133) cycles through low concurrent population. `tick.work.max` flat across tiers (84/99/101 ms) corroborates: tick work isn't scaling because the actual broadcast population isn't scaling 10×. SUMMARY's bottom-line "encode+send not a saturation hotspot" survives, but "10× bot count" headline is misleading — what scaled 10× was harness-side connection count, not server-side concurrent-alive entities.

**R4 — "D-20 invariant preserved" is misapplied.**
D-20 per `CLAUDE.md` "Outbound concurrency" and `application.yml:60` comment scopes to `paralife.admission.backpressure.outbound-queue-size` — NOT to `paralife.admission.cap`. The plan claim "D-20 alongside-not-move preserved" wrt cap is rhetorical; D-20 doesn't bind cap at all. The real invariant is "don't mutate application.yml for baseline runs". Rename it before downstream plans 20-04/05/06 cite D-20 incorrectly.

## YELLOW

**Y1 — `peakQueueDepth()` is at-scrape-instant max, not interval-peak.**
`OutboundSender.java:259-266` walks `queues.values()` and returns instantaneous max. Bursts between scrapes are invisible. With 6 samples × 5 s in a 200 s run, 85 % of the window is unobserved. "qmax=0 in 17/18 samples" is weaker evidence than SUMMARY frames. Fix: track a rolling peak via `AtomicInteger.updateAndGet(Math::max)` written by `offer()` and reset by the gauge supplier (or use `Statistic.MAX` DistributionSummary). Defer-with-rationale OK if you note this in deferred-items; current framing oversells.

**Y2 — Gauge supplier pinning works but is fragile.**
`AdmissionMetrics.java:228-230` — `Gauge.builder(name, this, m -> m.outboundQueueDepthSupplier.getAsInt())` is correct (gauge target = `this` Spring singleton, strongly held). `volatile` on the supplier field is needed for scrape-thread visibility — also correct. BUT: NPE risk if scrape fires before `registerOutboundQueueDepthMaxGauge(...)` runs (registration order in `OutboundSender` constructor depends on Spring bean init order). Currently safe because the gauge is *registered* inside the same call that sets the supplier — but only if no path constructs `AdmissionMetrics` without `OutboundSender` (the back-compat single-arg ctor at `:115` does exactly that — fine because it doesn't register the gauge either, but the coupling is implicit). Simpler pattern: make the gauge registration the constructor's last act and require a non-null supplier at construction time, or use `MultiGauge` keyed on session id.

**Y3 — Timer brackets close encode+send gap but miss queue dwell time.**
`OutboundSender.java:359-381` — Timer covers encode, `recordFrameSize`, `synchronized(session)` monitor acquisition (good — captures lock-wait), `sendMessage`, listener. NOT covered: time between `offer()` and `queue.take()` (queue dwell). At qmax=0–1 this is sub-µs, so the omission doesn't matter here — but if qmax ever climbs, encode+send Timer will under-report apparent latency. Note this in the SUMMARY caveat so downstream plans don't conflate "Timer p99=X ms" with "client-perceived send latency".

**Y4 — 6×5s samples in 200s run is thin.**
Per-tier window covers 15 % of steady-state. SUMMARY's "encode+send is fast" survives because the floor is so low (mean 79 µs vs 500 ms tick budget) that noise floor doesn't change the conclusion. But "tick.work.ms.max flat across 10× bot count" rests on a 15 % sample of three runs — the 84/99/101 ms peaks could each have been single outlier ticks. Acceptable for "not a hotspot at this scale" claim; insufficient for any quantitative scaling extrapolation downstream plans might attempt.

**Y5 — HundredBotIntegrationTest "pre-existing, not regressing" framing is plausible but unproven.**
Timer.Sample addition itself doesn't introduce a hang vector: `sample.stop(metrics.encodeSendTimer())` in `finally` returns a long, won't block. `encodeSendTimer` is final-init, won't be null. InterruptedException catch sits outside the inner try/finally so an interrupt during `sendMessage` still exits the loop cleanly. The Jetty-blocking-write story (Phase 22 TD-19.5-A documented in `OutboundSender.java:198-210`) is the actual hang root cause and predates 20-01c. Verified-at-parent-stash is reasonable evidence; not a regression.

**Y6 — Lock flamegraph from separate 1000-bot run.**
Cross-run capture limitation acknowledged in SUMMARY + `key-decisions`. Reviewers accepted at 20-01b. OK, but: lock contention is *workload-state-dependent*. A second run with different RNG/scheduling could show different lock hotspots. The "consistent with 20-01b's lock graph at c22e487" claim is a visual-similarity assertion, not a digest match. Fine for exploratory but weak for any "no lock regression" guarantee.

**Y7 — `cpu.html` 19 KB is small for 1000 bots / 60 s capture.**
Could indicate concentrated hot paths (good for analysis) OR sparse stack sampling (bad). Plan should record `asprof -e cpu -i <interval>` flag in `meta.json` if not already. Without sampling rate documented, the 19 KB size means little.

## GREEN

- Timer.Sample try/finally covers IOException, RuntimeException, and normal return paths (`OutboundSender.java:362-381`). `sample.stop()` in finally fires on every exit.
- Gauge weak-target bug at d768305 fixed correctly: target = `this`, supplier strongly referenced. Re-anchor pattern documented in patterns-established.
- Gauge registration is idempotent at the registry layer (Micrometer dedupes by name+tags). Double-registration in tests is benign.
- F6 baseline-vs-HEAD remediation: HEAD 1818eeb is downstream of all the cited drift commits per the visible commit chain (d768305 → 02b1b76 → 1818eeb → 5306e34). Plausible — could not verify f6da129 ancestry of d768305 from context, but the chain is consistent.
- `peakQueueDepth()` is iteration-safe over ConcurrentHashMap.values() (weakly-consistent iterator, no CME, no NPE).
- `paralife.outbound.detach.timeout = 0` at every tier is genuinely strong evidence — counter is interval-cumulative, not at-scrape-instant.

## Recommendation

Resolve R1 (per-bucket-cap source) and R3 (low concurrent-alive bot ratio) before merge. R1 is a documentation-vs-code conflict that downstream plans will inherit. R3 reframes what "10× scale ramp" actually means — call it "10× connection count, ~190/~85/~30 concurrent-alive" or run longer until population stabilises higher. R2 and R4 are wording fixes. Y1–Y7 are defer-with-rationale.

---

## Gemini Review

Here is the review of Plan 20-01c.

### 🔴 RED: F1 "Per-bucket cap" Claim is Factually Incorrect
- **Location:** `20-01c-SUMMARY.md` (Key Decisions) & `AdmissionGate.java:140-150`
- **Issue:** The SUMMARY explicitly claims: "`AdmissionGate` (line 140-150) applies the cap **per bucket**, not world-aggregate. With cap=1500 per bucket... bot buckets never hit cap... nutrient bucket hits cap". 
- **Evidence:** Looking at `AdmissionGate.java:140-150`, there is absolutely no bucket separation for the cap. `reservedSlots` is a single, global `AtomicInteger` that tracks *all* admitted non-respawning entities globally against a single `admissionConfig.cap()`. 
- **Reality:** Bots succeed because they are placed *first*, before the world hits 1500. Later, nutrients spawn on cell death and push the *global* slot reservation over 1500, causing subsequent admissions (which happen to be nutrients) to be rejected. 
- **Action:** The SUMMARY must be rewritten. The cap is world-aggregate. F1 was "remediated" simply because raising the global cap to 1500 provided enough headroom for the bots, not because of an imaginary per-bucket implementation.

### 🟡 YELLOW: "queue.depth.max" is not a true peak gauge
- **Location:** `OutboundSender.java:237-244` (`peakQueueDepth()`) & `20-01c-SUMMARY.md`
- **Issue:** The SUMMARY concludes that "outbound queue depth is effectively zero... because `queue.depth.max` reads 0 in 17 of 18 samples".
- **Evidence:** The Micrometer gauge invokes `peakQueueDepth()` at scrape time. This function loops over `queues.values()` and returns the *current* maximum depth at that exact microsecond. It does not track peaks between 5-second polling intervals. 
- **Impact:** Any intra-scrape spikes (pressure bursts between polls) are completely invisible. While the conclusion is likely correct (supported by 0 detach timeouts and 0 stalled sessions), the gauge itself provides weak evidence for "zero pressure" because of its instantaneous sampling. The name `max` is slightly misleading in a Micrometer context (where `DistributionSummary.max()` actually tracks window peaks).

### 🟡 YELLOW: Misunderstanding of Gauge semantics for tick.work.ms
- **Location:** `20-01c-SUMMARY.md` (Caveats §3) & `AdmissionMetrics.java:143`
- **Issue:** The SUMMARY caveat claims: "`paralife.tick.health.work-time-ms = null`. The MAINTENANCE-mode AtomicLong gauge has no recorded writes at scrape time".
- **Evidence:** There is no "MAINTENANCE-mode" logic attached to `M_TICK_WORK_MS`. The metric is backed simply by `lastTickWorkMs` (an `AtomicLong`). A Gauge backed by an `AtomicLong` always has a value (it will be `0` if unwritten). If the Actuator endpoint returns `null` or 404, it's either an Actuator serialization quirk or the gauge wasn't registered correctly, not a Micrometer "recorded writes" requirement. The SUMMARY conflates `M_MAINTENANCE` with `M_TICK_WORK_MS`.

### 🟢 GREEN: Instrumentation correctness (Supplier Pinning & Timer)
- **Location:** `AdmissionMetrics.java:319-323` & `OutboundSender.java:286-310`
- **Verification:** 
  - The `outboundQueueDepthSupplier` volatile field safely pins the lambda, explicitly avoiding Micrometer's weak-target GC bug. `this` is a Spring Singleton, ensuring the gauge survives.
  - The `Timer.Sample` placement is excellent. The `try/finally` block correctly captures both the success and `IOException` paths, guaranteeing that a stuck or throwing Jetty write doesn't leak the timer sample. 

### 🟢 GREEN: F2 Visibility Gap Closed
- **Location:** `20-01c-SUMMARY.md` (Key Decisions)
- **Verification:** The Timer correctly isolates the per-session VT cost (encode + `sendMessage`). The reported `79 µs` average / `12.5 ms` max across 76k records is highly credible for a rapid string encode and local socket buffer write. It solidly confirms that per-VT scaling cost is not the bottleneck.

### 🟢 GREEN: F6 Baseline Anchoring
- **Location:** `20-01c-SUMMARY.md`
- **Verification:** The git history progression (`d768305 -> 02b1b76 -> 1818eeb`) confirms the new baseline is safely downstream of the `f6da129` simulation engine delta that broke the golden traces. 

### 🟢 GREEN: HundredBotIntegrationTest Flake
- **Location:** `20-01c-SUMMARY.md` (Caveats §1)
- **Verification:** The Timer instrumentation does not leak or hang the VT on exception, meaning the pre-existing WSL2/forkEvery=1 flake is legitimately pre-existing and not a regression introduced by the `OutboundSender` try/catch block.

---

## Codex Review

**RED**

- **F1 cap model is misdocumented.** `AdmissionGate` uses one global `reservedSlots` counter for non-respawn admissions, not per-bucket caps. The “per bucket / nutrient bucket” claim is false. See [AdmissionGate.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/AdmissionGate.java:139) and the contradictory summary claim at [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:82).

- **The rejection evidence is mislabeled.** The table says `rejected{world-full}`, but the sidecars show `reason=respawn-cap`, not `world-full`, at 500/1000. See [metrics-500bots](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-1818eeb.json:7), [metrics-1000bots](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-1818eeb.json:7), and the bad table header at [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:68).

- **`active@end (world sum)` is not proven to be world live count.** `incActiveBucket()` is called on every registration/respawn, while `markDead()` does not decrement the active gauge. That makes `3733` look like churn/accounting accumulation, not “world sum”. See [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:648) and [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:992). The summary’s downstream conclusion is overbuilt.

- **The p50/p95/p99 artifact claim is unsupported.** Code calls `publishPercentiles`, but committed Actuator sidecars expose only `COUNT`, `TOTAL_TIME`, and `MAX` for `outbound.encode.send.ms`; no p50/p95/p99 are present. See [AdmissionMetrics.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/AdmissionMetrics.java:192), [metrics-1000bots](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-1818eeb.json:7), and the unsupported verification at [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:146).

**YELLOW**

- **`peak_registered == --count` proves initial registration high-water, not sustained 1000-bot load.** `BotFleet.peakRegistered()` is explicitly a never-decreasing high-water mark, and `currentRegistered()` is only best-effort after churn. See [BotFleet.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/bot/BotFleet.java:137) and [BotFleet.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/bot/BotFleet.java:155). With `respawn-cap` rejections, the “bot scaling uncontaminated” language should be softened.

- **Timer placement is useful but not a complete send-cost envelope.** It includes encode, UTF-8 bytes, `recordFrameSize`, monitor wait, `sendMessage`, and test listener work. It excludes queue wait, frame construction, enqueue latency, and closed-session drops. See [OutboundSender.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/OutboundSender.java:307).

- **Metric unit naming is confusing.** The meter is named `.ms`, but Actuator reports Timer `baseUnit:"seconds"` and `TOTAL_TIME` in seconds. The summary converts correctly, but downstream tooling can misread it. See [metrics-1000bots](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-1818eeb.json:7).

- **Queue-depth evidence is instantaneous scrape evidence, not proof of no pressure.** `peakQueueDepth()` is safe over `ConcurrentHashMap`, but a 5s scrape can miss short queue spikes. The conclusion at [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:107) should say “not observed”, not “direct evidence”.

- **`tick.health.work-time-ms = null` caveat is false for committed sidecars.** The sidecars show numeric values. The write path is live via `TickHealthMonitor.onTick()`. See [metrics-1000bots](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-1818eeb.json:2), [TickHealthMonitor.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/TickHealthMonitor.java:61), and the wrong caveat at [20-01c-SUMMARY.md](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md:155).

- **Saturation conclusions are too strong for 6 samples.** `tick.max` flatness over 30s and one run per tier is weak evidence. Defer replication if needed, but label conclusions as preliminary.

- **D-20 default preservation is technically correct but needs louder labeling.** `application.yml` stays 256 at [application.yml](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/resources/application.yml:65); meta carries `cap_during_run:1500`. Downstream plans must cite this as a benchmark override, not a production default.

- **HundredBot flake framing is plausible, but not proven by the three-gate.** Timer `finally` should stop on normal exceptions; `sample.stop()` throwing is not a realistic VT leak vector. Still, this test exercises the exact many-session tick path and should not be dismissed forever.

**GREEN**

- **Gauge weak-target fix is structurally OK.** `AdmissionMetrics` is a Spring singleton target, the supplier is strongly held, and `volatile` is appropriate for cross-thread scrape visibility. See [AdmissionMetrics.java](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/src/main/java/com/paralife/admission/AdmissionMetrics.java:480).

- **Timer arithmetic is credible.** `6.044649747s / 76048 = ~79.5µs`; max `0.012474062s = 12.47ms`. See [metrics-1000bots](/home/mark/kramtime/paralife/.claude/worktrees/phase-20-01c-baseline-rebuild/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-1818eeb.json:7).

- **F6 ancestry is verified.** `f6da129` is an ancestor of `d768305`; the re-anchored baseline is downstream of the `SimulationEngine.processDeaths` ordering change.

---

## Opencode Review

Below is the review. RED = must-fix before merge.

---

## RED #1 — "Per-bucket cap" claim is factually wrong

**SUMMARY.md:78–83 + 20-01c-PLAN.md appendix**

The SUMMARY asserts:

> "`AdmissionGate` (line 140-150) applies the cap **per bucket**, not world-aggregate."

The code says otherwise. `AdmissionGate.java:141-153`:

```java
int cap = admissionConfig.cap();
if (!req.isRespawn()) {
    while (true) {
        int n = reservedSlots.get();
        if (n >= cap) {
            return reject(req, session, 429, RejectionToken.WORLD_FULL);
        }
        if (reservedSlots.compareAndSet(n, n + 1)) {
            break;
        }
    }
}
```

There is one `AtomicInteger reservedSlots`, one `admissionConfig.cap()`, one guard. No per-bucket counters. No per-bucket caps. The rejection token is literally `WORLD_FULL`.

The observed behaviour (bot admissions succeed, nutrient spawns rejected) has a simpler explanation that requires no per-bucket cap fiction:

1. Bots connect during ramp-up and fill `reservedSlots` to ≤1000 (well under 1500). Cap never binds for any tier.
2. The simulation spawns nutrients on cell-death events after bots are placed. By the time nutrients arrive, `reservedSlots` is near/at the cap from initial bot registrations.
3. Additionally, `respawns` bypass the cap entirely — line 148 `if (!req.isRespawn())` skips the whole cap block for respawns, which is how `admission.active.entities` can reach 3733 while `reservedSlots` ≤ 1500.

The per-bucket tagging exists on *metrics* (`incRejected`, `incActiveBucket`) for attribution, not on admission logic. These are orthogonal.

**Impact**: Downstream plans (20-04/20-05/20-06) reading "per-bucket cap" could design a per-bucket tuning surface that doesn't exist, or assume nutrients can be capped independently with an existing knob.

**Fix**: Reword the F1 resolution. The cap is world-aggregate. The 1500 override simply makes the cap non-binding for the 100/500/1000 bot tiers (since all `--count` values < 1500). Per-bucket attribution is a *metric tag*, not an admission mechanism.

---

## YELLOW #2 — Gauge scraping misses transient queue pressure

**OutboundSender.java:304-310 + SUMMARY.md:58-62**

`peakQueueDepth()` returns the instantaneous max across `queues.values()` at scrape time. The gauge is NOT max-over-interval — it's point-in-time. At a 5s scrape interval, a queue that spikes to 300 and drains within 1s would read 0 at both adjacent scrapes. The summary conclusion:

> "This is direct evidence that the VT-per-session drain absorbs the tick broadcast cadence without queue pressure."

should be qualified as *"no queue pressure observed at 5s scrape intervals."* Coarse scraping is adequate for this baseline, but the unqualified claim is overconfident. One-line caveat suffices.

---

## YELLOW #3 — `volatile` on `outboundQueueDepthSupplier` not load-bearing

**AdmissionMetrics.java:114**

The supplier is set exactly once during `OutboundSender`'s constructor, which is called during Spring's singleton initialization. Spring's bean lifecycle provides a happens-before (constructor → post-construct → bean registry → `ApplicationContext` publish). The Micrometer scraper accesses the gauge only after the context is up. `volatile` is harmless defense-in-depth, but without a comment explaining it, a future reader may think there's a concurrent swap pattern here. Consider adding a one-line `// volatile: Micrometer scrapes from a background scheduler; ensures visibility from the bean-publishing thread` or similar.

---

## YELLOW #4 — D-20 confusion risk for downstream plans

**application.yml:65 + SUMMARY.md:24**

`application.yml` stays at `cap: 256`. The 1500 value exists only as a JVM flag in meta.json. Downstream plans (20-04/20-05/20-06) citing the 1818eeb baseline table without reading meta.json could mistake 1500 as the production default. The per-tier headline table in the SUMMARY does not carry `cap_during_run: 1500` in any column. Suggestion: add `cap=1500` as a column or parenthetical in the headline table header so it's visible without cross-referencing meta.json.

---

## YELLOW #5 — `recordFrameSize` called on encode-only success; IOException path still records

**OutboundSender.java:304-309**

```java
String encoded = PerceptionCodec.encode(frame);
byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8);
metrics.recordFrameSize(encodedBytes.length);
synchronized (session) {
    session.sendMessage(new TextMessage(encoded));
}
```

If `encode` succeeds but `sendMessage` throws `IOException`, `recordFrameSize` has already fired — reporting a byte count for a frame that was never sent. For a saturation gauge this is negligible noise. If a future plan relies on `frame.size.bytes` for precise egress byte-counting, it will overcount on connection-drop frames. Minor, but documentable.

---

## GREEN findings (verified)

1. **Timer.Sample try/finally** — all exit paths accounted for. `queue.take()` exception escapes to outer `catch (InterruptedException)`. `continue` on closed session never starts a sample. `CodecException`/`IOException`/`RuntimeException` all fall into catch blocks inside the try/finally. `finally { sample.stop(...) }` runs on every path. Correct.

2. **Gauge strong-reference fix** — `Gauge.builder(name, this, m -> m.outboundQueueDepthSupplier.getAsInt())` uses the Spring singleton `this` as target. The IntSupplier is reachable through `this.outboundQueueDepthSupplier`. No GC risk. The d768305→02b1b76 fix is sound.

3. **Arithmetic check** — 6.04s / 76,048 = 79.4 µs mean. Matches the SUMMARY's ~79 µs.

4. **`peakQueueDepth` CHM iterator safety** — `ConcurrentHashMap.values()` weakly-consistent iterator. No `ConcurrentModificationException`. Session-add during iteration may be missed; session-remove may or may not be observed. For a max-aggregate diagnostic gauge, this is fine.

5. **F6 resolved** — three-gate 9/9 at captured SHA. ancestry validated by test passing.

6. **HundredBotIntegrationTest flake** — pre-existing at parent 14e96ea. Timer.Sample machinery (`Timer.start()` = nanoTime, `sample.stop()` = record to histogram) has no blocking operations, cannot cause VT hangs. Sound.

7. **Deferred items** — DistributionSummary replacing the deprecated `tick.health.work-time-ms` scalar is the correct call for this scope. `null` reading is likely an actuator metric-registry behaviour (meter registered but never written → `/metrics/{name}` returns null), not a bug, and Phase 21 can investigate if needed.

---

## Consensus Summary

### Agreed Strengths
- Gauge weak-target fix (d768305 → 02b1b76) structurally correct: `this` Spring singleton as target, supplier strongly held, `volatile` for scrape visibility (claude, gemini, codex, opencode)
- `Timer.Sample` try/finally placement sound — covers IOException, RuntimeException, normal return; no VT-leak/hang vector (claude, gemini, codex, opencode)
- Timer arithmetic credible: 6.04s / 76,048 ≈ 79 µs mean, max 12.47 ms; encode+send genuinely not a saturation hotspot at this scale (claude, gemini, codex, opencode)
- F6 baseline re-anchoring verified: 1818eeb downstream of f6da129 SimulationEngine ordering delta (claude, gemini, codex)
- HundredBotIntegrationTest flake pre-existing, not a 20-01c regression — Timer machinery is non-blocking (claude, gemini, codex, opencode)
- `peakQueueDepth()` iteration over `ConcurrentHashMap.values()` is safe (weakly-consistent, no CME) (claude, opencode)

### Agreed Concerns
- **[RED] F1 "per-bucket cap" claim contradicts code** (claude R1, gemini RED, codex RED, opencode RED #1). `AdmissionGate.java:140-153` uses one global `AtomicInteger reservedSlots` against one `admissionConfig.cap()`. No per-bucket counters, no per-bucket CAS, rejection token is `WORLD_FULL`. Bot admissions succeed because cap=1500 is non-binding for 100/500/1000 tiers, not because of per-bucket isolation. Per-bucket exists only as a metric *tag*, not an admission mechanism. SUMMARY must be rewritten before downstream plans (20-04/05/06) inherit the fiction.
- **[RED] `peak_registered`/active-count proxies oversold** (claude R2/R3, codex RED #3, codex YELLOW). `BotFleet.peakRegistered()` is a never-decreasing high-water of client-side connects, not server-side concurrent-alive. `enc.cnt` arithmetic (76k/200k/400k frames = 15–19% of "all bots alive every tick") implies ~190/~85/~30 concurrent-alive — the "10× scale" headline conflates connection count with sustained live population. `incActiveBucket()` without matching decrement on `markDead()` makes "world sum = 3733" churn-accumulation, not live count (codex). Reframe scale claim and audit active-bucket bookkeeping.
- **[YELLOW] `queue.depth.max` is instantaneous-at-scrape, not interval-peak** (claude Y1, gemini YELLOW, codex YELLOW, opencode #2). 6 samples × 5s in a 200s run leaves 85% of the window unobserved; bursts between scrapes invisible. Conclusion survives because floor is so low, but reword from "direct evidence" to "not observed at 5s scrape". Defer-with-rationale: rolling peak via `AtomicInteger.updateAndGet(Math::max)` or `DistributionSummary` MAX.
- **[YELLOW] Saturation conclusions thin on 6 samples × 1 run/tier** (claude Y4, codex YELLOW). `tick.work.max` flatness across tiers (84/99/101 ms) is adequate for "not a hotspot at this scale" but insufficient for quantitative scaling extrapolation. Label downstream-relevant claims as preliminary.
- **[YELLOW] Timer envelope incomplete** (claude Y3, codex YELLOW, opencode #5). Covers encode + UTF-8 + `recordFrameSize` + monitor wait + `sendMessage` + listener. Excludes queue dwell time (offer→take), frame construction, IOException-path frame-size overcounting. Document so downstream doesn't conflate Timer p99 with client-perceived send latency.
- **[YELLOW] D-20 framing risks downstream confusion** (claude R4, codex YELLOW, opencode #4). `application.yml` stays at cap=256; 1500 is JVM-flag override in meta.json only. D-20 properly binds `outbound-queue-size`, not `cap`. Make `cap_during_run=1500` visible in the headline table or rename invariant claim.

### Divergent Views
- **`tick.health.work-time-ms = null` caveat**: claude/gemini accept SUMMARY framing or treat as Actuator quirk; codex says caveat is *false* — committed sidecars show numeric values via `TickHealthMonitor.onTick()`. Worth verifying directly against the sidecar JSONs before merge — if codex is right, the caveat should be deleted, not deferred.
- **p50/p95/p99 percentile artifacts**: codex (RED) notes `publishPercentiles` is called but committed Actuator sidecars expose only COUNT/TOTAL_TIME/MAX — so percentile claims in the verification gate are unsupported. Other reviewers didn't flag this. Verify whether percentiles live in a separate endpoint or are genuinely missing from artifacts.
- **Rejection reason label**: codex (RED) reads sidecars as `reason=respawn-cap` at 500/1000 tiers, contradicting SUMMARY's `world-full` table header. Other reviewers debated bucket semantics but didn't catch the reason-token mismatch. If correct, this changes the interpretation entirely — respawns hitting `respawn-cap` is a different system (the `if (!req.isRespawn())` bypass at AdmissionGate:148 doesn't apply to respawn-cap path) and points to a respawn-rate limiter, not world-full saturation.
- **Y2 gauge registration robustness**: claude flags implicit coupling/NPE risk if `AdmissionMetrics` constructed without `OutboundSender`; opencode says `volatile` is "not load-bearing" given Spring lifecycle happens-before. Both can be true — the safety relies on bean-init ordering that isn't enforced by the type system. Decide whether to harden (constructor-mandatory supplier or `MultiGauge`) or just document the coupling.
