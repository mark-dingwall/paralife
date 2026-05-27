---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-19T06:06:57Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/TickHealthMonitor.java", "src/main/java/com/paralife/admission/AdmissionGate.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/harness/LoadHarness.java", "src/main/resources/application.yml"]
usage:
  claude: { input: 28, output: 857, cached: 1773527, tool_calls: 14, elapsed_s: 195.2 }
  gemini: { input: 233005, output: 2870, cached: 56693, tool_calls: 16, elapsed_s: 184.7 }
  codex: { input: 2066519, output: 11016, cached: 1919872, tool_calls: 59, elapsed_s: 259.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 20, elapsed_s: 304.5 }
synthesizer: claude
synthesized_at: 2026-05-19T06:06:57Z
---

# Cross-AI Review

## Claude Review

# Verdict: **YELLOW**

The flat-tick result is consistent with what the code measures, but the SUMMARY framing overstates what the experiment proves. Three top reasons:

1. **The admission cap (256) silently bounds the workload.** `--count 1000` does NOT mean 1000 admitted entities. `AdmissionGate.evaluate` (lines 110–158) caps `reservedSlots` at `admissionConfig.cap()` = 256 (`application.yml:65`). The 100-tier (under cap), 256-tier-equivalent (500), and 256-tier-equivalent (1000) all converge to the same admitted-entity steady state. The summary does not quote the rejected-counter, and the meta.json contains no `world-full` count.
2. **The metric excludes the per-connection cost surface.** `paralife.tick.health.work-time-ms` is the tick-thread wall time bracketing `eventPublisher.publishEvent(event)` in `TickEngine.tickLoop` (lines 110–119). That includes frame *build* (`TickBroadcaster.onTick @Order(50)`), but encode + `sendMessage` runs on per-session VTs in `OutboundSender.drainLoop` (lines 273–313). Per-connection scaling cost lives in the VTs, not in the gauge.
3. **The 1000-bot lock flamegraph is from a different run.** The capture deviation in `20-01b-SUMMARY.md` lines 131 admits the lock graph required a *second* 1000-bot harness invocation. JFR + cpu + alloc capture one workload instance; lock captures another. "6 frame nodes" is suggestive of low contention but not equivalent evidence for the same load.

---

## A. What `paralife.tick.health.work-time-ms` actually measures

`TickEngine.java:110-119`:
```
long startTime = System.nanoTime();
...
eventPublisher.publishEvent(event);
long elapsedNs = System.nanoTime() - startTime;
...
this.lastTickWorkMs = elapsed;
```
`AdmissionMetrics.java:153-155` registers it as a Gauge over `lastTickWorkMs`. `TickHealthMonitor.onTick @Order(Integer.MAX_VALUE)` reads `tickEngine.getLastTickWorkMs()` and mirrors it via `setLastTickWorkMs`. The actuator gauge therefore = wall time of synchronous `@EventListener` dispatch.

**Single-threaded?** Yes for in-tick-thread listeners: `ResumeTokenRegistry @Order(1)`, `SimulationEngine @Order(10)`, `EnvironmentEngine @Order(14)`, `CompositeEnergyDistributor @Order(15)`, `ActionResolver @Order(20)`, `TickBroadcaster @Order(50)`, `WebSocketKeepaliveService @Order(200)`, `TickHealthMonitor @Order(Integer.MAX_VALUE)`. Spring's default `ApplicationEventMulticaster` invokes listeners synchronously on the publisher thread.

**Includes broadcast/codec?** Partial. `TickBroadcaster.onTick` (broadcaster line 188–239) iterates `botRegistry.getAllBots()`, builds each `Frame.TickFrame` (vision window, RLE pass, env-state masks, roster), and calls `outboundSender.offer(...)`. `offer` (OutboundSender line 225–243) is a non-blocking `ArrayBlockingQueue.offer()`. So frame **build** is in the gauge, but `PerceptionCodec.encode` + `session.sendMessage` are not — they run inside `drainLoop` on per-session VTs. This is the missing factor: at 256 admitted bots × constant vision radius, frame-build is bounded; the per-connection cost (encode + UTF-8 + write) is invisible to this gauge.

**Per-bot normalisation?** No — it's a per-tick wall scalar (`AtomicLong`), not normalised. But it IS bounded by what runs in the tick thread, which scales with admitted bots, not with connection count.

## B. What the harness does at `--count 1000`

`LoadHarness.runInternal` (line 268): `fleet.launch(serverUri, count, identity, rampUp, speciesMix, factory)`. Each "bot" is a VT. Ramp-up `rate:50` opens TCP connections at 50/s, so 1000 connections take ~20 s to attempt. Whether each connection is **admitted** is decided by `AdmissionGate.evaluate` — see §C.

Final-report fields counted in `computeCountersSnapshot` (line 416) include `connect_failures_total` and `e408 reconnect-required`, but the SUMMARY does not surface harness-side `peakRegistered` / `currentRegistered` / connect failures. The summary's "BotFleet: 1000 bots disconnected" line in the harness log only proves that 1000 client VTs eventually exited their loop — not that 1000 entities lived on the grid simultaneously.

Whether each bot generates perception/action traffic at every tick: the bot only sends `a|...` frames when it gets `t|...` ticks back. Bots that get `E|429|world-full` on registration never enter the tick-receive path. They contribute TCP+TLS handshake load and one inbound `r|` frame each, no more.

## C. Admission cap interaction — **the dominant effect**

`application.yml:65`: `cap: 256`. `AdmissionGate.evaluate` lines 139–151:
```
int cap = admissionConfig.cap();
if (!req.isRespawn()) {
    while (true) {
        int n = reservedSlots.get();
        if (n >= cap) {
            return reject(req, session, 429, RejectionToken.WORLD_FULL);
        }
        if (reservedSlots.compareAndSet(n, n + 1)) break;
    }
}
```

At `--count 1000` against a clean grid, the first ~256 registrations succeed and the remaining ~744 are rejected with `E|429|world-full`. Those rejected sessions:
- Did not pass `AdmissionResult.Allow` → did not call `attachSession` in WorldWebSocketHandler → no `OutboundSender` VT was spawned for them → they don't appear in `botRegistry.getAllBots()` → they're invisible to `TickBroadcaster.onTick`.
- They DID consume a TCP connection. They DID send an `r|` frame. They received an error frame. Whether they then disconnect or sit idle depends on `BotClient.onError` behaviour (not shown here). Either way they don't contribute per-tick CPU.

**The 100-bot tier (100 < cap=256) and the 1000-bot tier are not measuring 10× more work — both eventually steady-state at 256 admitted entities once cap is reached.** Even the 500 tier hits cap. The 100 tier is the *only* tier where admitted-count tracks `--count`. So the 17.7 → 16.5 → 15.2 ms trend is misread as "flat under 10× load" when it's actually "100 admitted → ~256 admitted → ~256 admitted". The mild downward trend is plausibly noise or warm-up artefacts; it is not evidence of any scaling property.

This alone is enough to mark the summary's "10× connection-count scale span" framing as misleading.

## D. Where broadcast / codec work actually lives

`TickBroadcaster.onTick` line 188–239 (tick thread): builds the frame, calls `outboundSender.offer`. Lines 207–233 iterate `botRegistry.getAllBots()` — this is the **admitted-bot** set, bounded by cap.

`OutboundSender.drainLoop` lines 273–313 (per-session VT): `queue.take()` → `PerceptionCodec.encode(frame)` → `getBytes(UTF_8)` → `metrics.recordFrameSize` → `synchronized(session) { session.sendMessage(...) }`.

So encode + write IS on per-session VTs. The metrics that *would* capture saturation there:
- `paralife.outbound.frame.size.bytes` — exists, but is a size DistributionSummary, not a rate.
- `paralife.backpressure.stalled.sessions` — gauge for STALLED transitions.
- `paralife.outbound.detach.timeout` — counter for drain-VT stuck on Jetty write.
- `OutboundSender.queueDepth(sessionId)` — exists (line 249) but **is not registered as a Micrometer gauge**. There is no aggregate "max/p99 queue depth" meter. This is a real observability gap.

None of these were sampled in the sidecars. The summary's claim that "the synchronized-session-monitor + drain-VT model holds under sustained load" rests entirely on zero `detach.timeout` — a binary counter that only fires when a VT cannot exit on detach. That is not the same thing as "outbound is keeping up". You can have a queue that's permanently at 80% capacity without ever tripping `detach.timeout`.

## E. JFR + flamegraph capture validity

**Sequential capture vs concurrent.** The plan asked for concurrent CPU/alloc/lock attach. The execution discovered async-profiler 4.4 rejects concurrent attach (consistent with upstream — async-profiler's `start` is exclusive per JVM; the `multimode` flag exists but the asprof launcher doesn't expose it for HTML output). Fine. But the *consequence*: at the 1000-bot tier, lock was captured in a **second** harness run (per SUMMARY line 131). Different RNG entropy, different VT scheduling, different placement → different workload instance. Calling that "the same workload" is hand-wave. If lock contention is bursty (e.g. WorldGrid write-lock during placement), the second-run probe could easily miss it.

**6 frames vs 209 frames.** Plausible but not conclusive evidence of "no contention". Async-profiler's `lock` event uses JVMTI MonitorContendedEnter, which:
- only fires when a thread *blocks* on a monitor, not when it serializes briefly;
- can miss contention shorter than the sampling interval (default 10 ms for lock);
- does not see `ReentrantLock` or `synchronized` interactions that complete before the contention threshold;
- and (importantly here) **the per-session VT model means contention manifests as queue.take backpressure, not as monitor contention** — by design.

So "6 frames" is consistent with the D-10 isolation working, but it's also consistent with the sampler missing short-burst contention windows (e.g. `WorldGrid.writeLock` during a placement burst near tick boundaries). Calling this "confirmed" is too strong.

**Statistical adequacy.** 6 samples at 5s × 500ms tick = ~10 ticks per sample window, ~60 ticks total per tier. For a metric with sample variance ~2 ms on a mean of ~17 ms (CV ~12%), the standard error of the mean over n=6 is ~1 ms. That makes 17.7 vs 16.5 vs 15.2 statistically indistinguishable from each other and from noise. The summary's three-decimal precision is spurious.

## F. Three-gate result and stale-golden defence

The argument in the SUMMARY (lines 114–118) is **plausible but not airtight as documented**. Two specific gaps:

1. The SUMMARY claims "identical digests across two consecutive runs at c22e487" → simulation is deterministic at c22e487. Granted. But that doesn't prove the c22e487 *simulation behaviour* matches the post-f6da129 simulation. If `f6da129` touched ANY simulation-code path that affected sess-9/sess-21 trajectories, then the c22e487 baseline runs are anchored to a *different* simulation than current HEAD. I would want to see `git show --stat f6da129` and confirm the diff is exclusively `src/test/resources/golden-trace-phase19.json` (or the diff in test fixtures only). The SUMMARY asserts this but does not cite the diff. **Action: have the author paste the f6da129 file list.**

2. Even granting f6da129 is fixture-only, **the baseline binding to a SHA that fails its own three-gate** is a real anchoring weakness for downstream before/after diffs. Plans 4/5/6 will produce a `tuned-{HEAD}` set and diff against `baseline-c22e487`. If HEAD is post-f6da129, the diff isn't apples-to-apples for any test that uses the golden. For *runtime* tuning that's tolerable (the golden test doesn't drive the JFR), but the SUMMARY should explicitly note that the baseline is anchored to a SHA where the in-tree three-gate is red, even if the root cause is benign.

## G. A8 falsification

The SUMMARY's test is wrong even though its conclusion is right.

The author ran `java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` (no `-XX:+UseZGC`). With `UseZGC=false`, the JVM is running G1 — and `ZGenerational` defaults to `false` because it's only consulted when ZGC is selected. `PrintFlagsFinal` shows the *resolved* flag values after ergonomics; many ergonomic defaults are gated on which collector is selected. So this test cannot answer "is generational ZGC default *when ZGC is enabled*".

The correct test is:
```
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -E 'ZGenerational|UseZGC'
```
or equivalently `java -XX:+UseZGC -Xlog:gc -version` and check for the "Mode: Generational" / "Mode: Non-generational" startup banner.

For Temurin 21.0.6 specifically, the actual answer: generational ZGC became opt-in (`-XX:+ZGenerational`) in JDK 21, and *default* with `-XX:+UseZGC` only from JDK 23 onward (JEP 474 finalised then). So in 21.0.6 the SUMMARY's conclusion ("ZGC is opt-in only") is correct, but it's correct by accident — the chosen probe could just as easily have returned a misleading answer on a JDK that changed the default. Plan 4's recipe text should specify `-XX:+UseZGC -XX:+ZGenerational` for 21.0.6 and note that on JDK 23+ only `-XX:+UseZGC` is required.

## H. Bigger picture and what to measure next

**Verdict: YELLOW.** The measurements are internally consistent; the methodology is reasonable for what it actually measures; but the headline framing ("server CPU is far from the bottleneck at 1000 bots") is unsupported by the captured data. Before this becomes the citation in Plans 4/5/6, **edit the SUMMARY**:

1. State explicitly that admitted-entity steady state at 500-bot and 1000-bot tiers is bounded by `admission.cap=256`, not by `--count`. Mark "10× connection-count scale span" as misleading and re-frame as "10× connection-attempt span; 256-entity steady state at 256-bot, 500-bot, 1000-bot tiers".
2. Add the `paralife.admission.rejected{reason=world-full}` count per tier (re-query the metric or, if recapture is too expensive, note explicitly that the count is unobserved).
3. Note explicitly that the gauge measured (tick work-time) excludes encode + sendMessage by design, and that the per-connection cost surface is not captured by this baseline.
4. Soften the lock-contention claim from "confirms D-10" to "consistent with D-10, but lock flamegraph was captured in a separate harness run from the JFR + CPU + alloc graphs".
5. Fix A8's evidence: run with `-XX:+UseZGC` set or document the startup-banner check.

### Additional measurements to actually answer "does the server scale linearly with connection count?"

1. **`paralife.admission.rejected{reason=world-full}` counter, sampled per tier.** Definitive proof of how many of `--count` actually entered the grid. Expected signature of saturation: 0 at 100, ~244 at 500, ~744 at 1000. If those numbers don't appear, the cap may be raised but not measured.

2. **Per-tick outbound queue-depth histogram.** Either (a) add a Micrometer DistributionSummary that `OutboundSender.drainLoop` records `queue.size()` into on every iteration, or (b) add a periodic sweep that calls `queueDepth(sessionId)` for every attached session and emits min/p50/p95/max. Expected signature of saturation: p95 climbs from 0 toward `outbound-queue-size=128` as connection count grows.

3. **Process CPU% and per-thread CPU time from JFR `jdk.ThreadCPULoad`/`jdk.CPULoad` events.** Tick-thread wall time can stay flat while *carrier* threads saturate (encoding 256 frames × ~2 Hz = 512 encodes/s; at 1000 connections including admitted+respawn churn that's higher). Expected signature: aggregate `jvmSystem` + `jvmUser` from `jdk.CPULoad` rises with admitted-entity count and is invariant to rejected count.

4. **Raise admission.cap and re-run.** The cleanest experiment: re-capture at `admission.cap=1000` (or remove the cap) with `--count 1000` so the workload actually has 1000 admitted entities. THEN compare tick work-time. That is the experiment the SUMMARY's framing implies was already run; it was not.

Bonus: log the harness final-report fields `peak_registered`, `current_registered`, `connect_failures_total`, `e408_count` per tier — the harness already collects these; the summary just doesn't quote them. They are essentially free verification that admitted count moved with `--count`.

---

If you want me to spot-check `git show --stat f6da129` (the only piece of §F I can't conclude from the files alone), say the word.

---

## Gemini Review

**Verdict: RED** — The baseline performance capture is highly misleading.
1. The `paralife.admission.cap` limit of 256 was not raised for the load test, meaning the 500 and 1000-bot tiers simply rejected the excess connections and processed identical payloads to the 256-bot ceiling.
2. The sampled metric (`paralife.tick.health.work-time-ms`) explicitly excludes the actual per-connection encoding and broadcasting work, which was offloaded to virtual threads.
3. The `lock` flamegraph methodology is blind to contention on `java.util.concurrent` primitives because `async-profiler`'s lock event only triggers on `synchronized` monitor contention.

---

### A. What does `paralife.tick.health.work-time-ms` actually measure?
Based on `src/main/java/com/paralife/engine/TickEngine.java`, `TickHealthMonitor.java`, and `TickBroadcaster.java`:
- **Wall-clock time:** It measures the wall-clock time of the simulation tick on a single virtual thread. `TickEngine.java` records `System.nanoTime() - startTime` spanning the `eventPublisher.publishEvent(event)` call.
- **Excludes VT work:** It **excludes** the broadcast and codec work. `TickBroadcaster.onTick` runs synchronously during the event dispatch, but it only enqueues unencoded frames (`outboundSender.offer(bot.sessionId(), frame)`). The heavy lifting of `PerceptionCodec.encode` and WebSocket `session.sendMessage` happens asynchronously on per-session virtual threads inside `OutboundSender.drainLoop`.
- **Flatness:** The offloading to virtual threads partially explains the flatness, but the dominant reason for the perfectly flat tick time is the admission cap preventing more than 256 entities from being simulated (see C).
- **Normalization:** The gauge is a raw timer (milliseconds per tick) and is not normalized per-bot or per-connection.

### B. What does the harness actually do at `--count 1000`?
Based on `src/main/java/com/paralife/harness/LoadHarness.java`:
- The harness does attempt to launch 1000 concurrent connections via `BotFleet.launch`.
- However, because the server enforces a global admission cap of 256, the extra 744 connections will receive an HTTP 429 rejection. The harness tracks these internally via `connectFailuresTotal()`, but the capture summary completely ignored client-side failure metrics.
- The 744 rejected connections are completely passive on the server side. They never receive an entity ID, never join the `BotRegistry`, and generate absolutely no perception or action traffic.

### C. Admission cap interaction
Based on `src/main/resources/application.yml` and `src/main/java/com/paralife/admission/AdmissionGate.java`:
- `application.yml` sets `paralife.admission.cap: 256`. The run command in the `how-to-verify` script does not override this property.
- `AdmissionGate.evaluate` enforces this strictly using an atomic counter: `if (n >= cap) { return reject(req, session, 429, RejectionToken.WORLD_FULL); }`.
- At `--count 1000`, the other 744 sessions are rejected and held off-grid.
- **This is the dominant effect.** The tick engine's workload scales with `botRegistry.getAllBots()`, which maxes out at 256. A tier where `--count > cap` (500, 1000) is observably indistinguishable from `--count = cap` (256) on the tick thread because the server is doing the exact same amount of work.

### D. Where does broadcast / codec work actually live?
Based on `src/main/java/com/paralife/websocket/TickBroadcaster.java` and `src/main/java/com/paralife/admission/OutboundSender.java`:
- The per-connection encode and send cost is paid exclusively in the per-session virtual threads inside `OutboundSender.drainLoop`.
- To capture saturation in this pipeline, one must measure the `ArrayBlockingQueue` depth (`OutboundSender.queueDepth`), the latency of the encode/send loop, or the JVM's aggregate `process.cpu.usage`.
- None of these saturation metrics are present in the sampled actuator sidecar, which exclusively polled `tick.health.work-time-ms` and `detach.timeout`.

### E. JFR + flamegraph capture validity
Based on `20-01b-PLAN.md` and `20-01b-SUMMARY.md`:
- Running the `lock` capture in a separate 1000-bot run means it doesn't represent the exact identical workload sequence, though it is likely an acceptable proxy for a steady-state load generator.
- **"6 frames" is NOT evidence of "no contention" across the architecture.** The `async-profiler` `lock` event uses `JVMTI MonitorContendedEnter`, which ONLY profiles Java `synchronized` blocks. `AdmissionGate` uses `AtomicInteger` (no monitors). `OutboundSender` uses `synchronized (session)`, but only ONE virtual thread (the drain VT) ever writes to a given session, guaranteeing zero monitor contention by design. The flamegraph is completely blind to contention on `java.util.concurrent` primitives (like CAS loops or `ConcurrentHashMap` bins).
- 6 samples × 5s = 30s (60 ticks) is statistically inadequate. Measuring a steady state requires observing it long enough to capture periodic background tasks and garbage collection pauses, which a 30s window can easily miss.

### F. The three-gate result and the stale-golden defence
Based on `20-01b-SUMMARY.md`:
- The "stale-golden" argument is structurally unsound. If `GoldenTraceEquivalenceTest` deterministically produces a different output at `c22e487` than the golden file, it means the simulation's behavioral output has demonstrably changed since the golden file was recorded. "Stale golden" is just a face-saving framing for "the code behavior changed, and we haven't updated the test to reflect it".
- Binding the baseline to a SHA with a known behavioral divergence from the golden trace introduces risk. Downstream plans will compare their diffs against a known-degraded or behaviorally-shifted reference, potentially masking regressions or incorrectly crediting performance gains to skipped logic.

### G. Assumption A8 falsification
Based on `20-01b-SUMMARY.md`:
- Executing `java -XX:+PrintFlagsFinal -version` without `-XX:+UseZGC` only proves that G1 is the default JVM garbage collector for Java 21. It does NOT prove that Generational ZGC isn't the default *when ZGC is actively requested*.
- The correct check is `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`. On Java 21, this correctly shows `ZGenerational = true`.
- Downstream Plan 4 will incorrectly assume they must supply `-XX:+ZGenerational`, which is redundant.

### H. The bigger picture
**Verdict: RED.** The baseline is deeply misleading. The server didn't scale to 1000 bots; it artificially capped at 256 and ignored the rest. The minimum required re-measurement must append `--paralife.admission.cap=1000` to the server boot command in the `how-to-verify` script to actually admit the connections.

**Specific additional measurements needed to prove linear scaling:**
1. **`paralife.admission.active.entities` and `paralife.admission.rejected`:** Sample these gauges via Actuator to verify the server actually admitted the requested `--count` of bots (should reach 1000) and that the rejected count remained 0.
2. **`OutboundSender` queue depth:** Expose `OutboundSender.queueDepth(sessionId)` as a Micrometer DistributionSummary or Gauge. **Expected signature of saturation:** queue depths consistently > 0 and backing up toward the 128 watermark.
3. **`process.cpu.usage`:** Sample JVM CPU utilization from `java.lang.management.OperatingSystemMXBean` via Actuator. **Expected signature of saturation:** linear or super-linear growth scaling with the connection count, revealing the true cost of the encode/send virtual threads.
4. **Harness Client Metrics:** Inspect the LoadHarness `report-out` JSON for `connectFailuresTotal` and `syncs_received_total`. **Expected signature:** 0 failures, and `syncs_received_total` scaling proportionally with connection count and duration.

---

## Codex Review

**Verdict: RED** — the flat tick result is real for the two sampled gauges, but the conclusion is misleading. Top reasons: `paralife.tick.health.work-time-ms` excludes per-session encode/send work; the default admission cap is 256, so 500/1000 requested bots do not mean 500/1000 admitted tick-participating entities; and the metric sidecars did not capture active sessions, active entities, rejections, queue depth, send latency, or CPU saturation. This baseline is unsafe as evidence that “server CPU is far from the bottleneck at 1000 bots.”

**A. Metric Semantics**
`paralife.tick.health.work-time-ms` is a scalar gauge backed by `AdmissionMetrics.lastTickWorkMs`, described as “most-recently-completed tick wall-clock work time” in [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:153). `TickHealthMonitor` samples `tickEngine.getLastTickWorkMs()` and writes it to the gauge in [TickHealthMonitor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:62).

The source value is measured around `eventPublisher.publishEvent(event)` in [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:109), then stored after dispatch returns at [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:116). That means it includes synchronous tick-listener dispatch, including `TickBroadcaster.onTick`.

It includes frame construction and queue offer in `TickBroadcaster`: the broadcaster iterates `botRegistry.getAllBots()`, builds a `Frame.TickFrame`, and calls `outboundSender.offer(...)` at [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:201) and [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:217).

It excludes encode and actual WebSocket send, which happen later in the per-session sender VT: `PerceptionCodec.encode(frame)`, `recordFrameSize`, then `session.sendMessage(...)` in [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:273). That exclusion absolutely can explain flatness if the missing cost is where per-connection scaling lives.

It is not per-bot or normalized. It is a scalar last-tick value, not divided by bot count; see scalar registration at [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:149).

**B. Harness Behavior**
`--count 1000 --duration 200 --ramp-up rate:50` launches 1000 bot clients over roughly 20 seconds: `BotFleet.launch(...)` loops `count` times and applies `rampUp.awaitNext(i)` in [BotFleet.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotFleet.java:84), with `Rate` sleeping `1_000_000_000 / perSecond` ns at [RampUpSpec.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/RampUpSpec.java:37).

Each bot connects and immediately sends an `r|...` register frame in [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:155) and [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:194). But registration success is not guaranteed: `awaitRegistered(15_000L)` only waits for a sync frame in [BotFleet.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotFleet.java:111).

Bots that receive server `E|429` disconnect rather than retrying, per [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:422). So “1000 bots disconnected” proves 1000 clients were launched, not that 1000 were concurrently admitted or tick-active.

For active bots, actions are client-driven after receiving tick frames: `onTick` calls `brain.decide(...)` and sends only if non-null at [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:383). Some bots can be passive: bonded secondaries and most composite roles return `null` in [HeuristicBrain.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:92).

**C. Admission Cap**
The default cap is 256 in [application.yml](/home/mark/kramtime/paralife/src/main/resources/application.yml:64). `AdmissionGate` rejects fresh registrations once `reservedSlots >= cap` at [AdmissionGate.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:139).

At `--count 1000`, roughly 744 fresh registrants should be rejected as `world-full`, unless churn frees slots. Rejected sessions may briefly do handshake/register/error/close work, but they do not become grid entities and do not participate in steady-state tick simulation or tick broadcast.

This is the dominant issue for interpreting the flat 500/1000 tiers. With cap 256, `--count 500` and `--count 1000` can easily be indistinguishable from “~256 admitted entities plus rejected extras.” The 100 tier is below cap, but the comparison is then 100 vs at most 256 active entities, not 100 vs 1000.

**D. Broadcast / Codec Placement**
Per tick, `TickBroadcaster` builds per-bot frames and enqueues them, but explicitly comments that encode and frame-size recording happen in `OutboundSender.drainLoop` at [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:218).

The per-session sender VT does the encode and send at [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:286). Queue operations are non-blocking via `offer`, and queue depth is available only as an accessor at [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:225) and [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:249), not as a sampled metric.

Metrics that would capture this layer: outbound queue depth distribution, offer-failure/stall count, sender encode+send wall time, frames/sec, active sender count, process CPU, and carrier-thread saturation. The sampled sidecars captured only work-time and detach timeout.

**E. JFR / Flamegraph Validity**
The plan requested concurrent 60s cpu/alloc/lock capture at [20-01b-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md:145). The summary says async-profiler 4.4 rejected concurrent attach and captures were sequential, with lock captured in a second 1000-bot run at [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:127).

So the lock flamegraph is the same nominal scenario, not the same workload instance as the JFR/cpu/alloc captures. That is acceptable as exploratory evidence, but weak as a proof.

“6 frame nodes vs 209” is not proof of no contention. It is consistent with low contention, but also with profiler configuration, event threshold, short capture, workload not exercising the contended path, or lock-event coverage gaps. Treat it as “no lock contention observed,” not “contention isolated by architecture.”

The 6×5s metric window is also weak. Because this is a gauge of the last completed tick, the sidecar has 6 point samples, not 60 tick samples, even though 60 ticks elapsed at 500ms intervals.

**F. Three-Gate / Stale Golden**
The structural argument is only partially sound. The test itself checks both run-to-run determinism and equality to a pinned golden: [GoldenTraceEquivalenceTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java:161). A deterministic mismatch proves the code is stable across two runs, but not automatically that the golden is stale rather than the code having changed behavior.

The affected golden keys changed from `trace-sess-21 = 5913...` and `trace-sess-9 = 0b60...` at `c22e487` to `d894...` and `71b9...` at current/f6da state; see [golden-trace-phase19.json](/home/mark/kramtime/paralife/src/test/resources/golden-trace-phase19.json:16). Commit `f6da129` also changed production code, including deterministic composite iteration in `SimulationEngine`, not just the golden. That makes the “test infrastructure only” framing too strong unless the actual c22e487 digest output was archived.

Binding the baseline to a SHA that fails its own three-gate is a downstream risk. For performance-only comparisons it may be tolerable, but it should be labelled “known three-gate failure, not a clean semantic baseline.”

**G. A8 ZGC Falsification**
The no-flag check is valid for “what GC is the JVM using by default.” Locally, Temurin 21.0.6 reports `UseG1GC = true`, `UseZGC = false`, `ZGenerational = false` with no GC flags.

But it is incomplete for “is generational ZGC default when ZGC is requested.” The correct checks are both:

```bash
java -XX:+PrintFlagsFinal -version | grep -iE 'UseG1GC|UseZGC|ZGenerational'
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseG1GC|UseZGC|ZGenerational'
```

On this machine, even `-XX:+UseZGC` leaves `ZGenerational = false`; `-XX:+UseZGC -XX:+ZGenerational` is required. So the conclusion “ZGC/generational ZGC is opt-in” is right, but the reasoning should distinguish default collector from ZGC submode default.

**H. Overall**
**RED.** The baseline measures “tick dispatch under default cap with 100/500/1000 launched clients,” not “server response to 10× more active clients.” The flat work-time result should not be cited as evidence of 1000-client CPU headroom.

Minimum re-measurement:

1. Run with `paralife.admission.cap >= 1000`, or explicitly state the cap-bound scenario.
2. Sample `paralife.ws.active.sessions`, `paralife.admission.active.entities`, and `paralife.admission.rejected{reason=world-full}` through ramp and steady state.
3. Capture tick distribution, not six gauge points: use `paralife.tick.work.ms` count/total/max/p95 over the window.
4. Add outbound-layer metrics: queue depth p50/p95/max, offer failures/stalled sessions, encode+send latency, frames/sec, and process CPU/JFR carrier utilization.

Expected saturation signatures: active entities plateauing at 256 proves cap masking; queue depth growth or stalled sessions proves outbound saturation; rising tick p95 with active entities proves tick-thread scaling; CPU/carrier saturation with flat tick work proves work moved outside the tick thread.

---

## Opencode Review

Now let me read the metric sidecars and the BotFleet/RampUpSpec classes:---

## VERDICT: **RED** — the baseline is misleading for downstream tuning use.

**Top three reasons:**

1. **The admission cap (`paralife.admission.cap: 256`) is the dominant confound.** The 500- and 1000-bot tiers have identical active entity populations (~256); they cannot meaningfully differ. The conclusion "flat across 10× scale span" describes a measurement artifact, not architectural headroom.

2. **`paralife.tick.health.work-time-ms` excludes all per-connection cost that scales with connection count** — encode, sendMessage, queue drain. The metric captures the tick-thread wall time (including frame construction + `offer()`), but every bot's encode + wire write runs on per-session VTs outside the measurement window. Flatness of this gauge tells us only that frame construction is cheap.

3. **The headline framing silently crosses two distinct questions.** "Does the tick-loop thread scale with active entity count?" (measured: yes, flat at 100–256 entities) is not the same as "Does the architecture scale with 10× more connected bots?" (untested — the cap hid 744 of 1000 bots).

---

## A. What does `paralife.tick.health.work-time-ms` actually measure?

**Source trace:**

1. `TickEngine.tickLoop()` at line 109–117: `System.nanoTime()` before `eventPublisher.publishEvent(event)` and after. The elapsed nanos → `lastTickWorkMs = elapsedNs / 1_000_000` (line 119). This field is written **after** `publishEvent` returns.

2. `TickHealthMonitor.onTick()` at `@Order(Integer.MAX_VALUE)` (line 59–93) reads `tickEngine.getLastTickWorkMs()` → calls `admissionMetrics.setLastTickWorkMs(sample)` (line 63).

3. `AdmissionMetrics` line 150–155 registers the gauge as a simple `Gauge` wrapping `AtomicLong lastTickWorkMs`.

**What's inside the measurement window:** ALL synchronous `@EventListener` handlers on `TickEvent`, including `TickBroadcaster` at `@Order(50)`. Specifically, `TickBroadcaster.onTick()` at line 190 runs: iterate all live bots, call `buildTickFrame()` per bot (grid lookups, CellEntry construction, RLE assembly, env-state computation, effect/event building), and `outboundSender.offer()`.

**What's EXCLUDED:**
- `PerceptionCodec.encode(frame)` — runs in `OutboundSender.drainLoop()` line 286 on per-session VTs
- `session.sendMessage(new TextMessage(encoded))` — runs in `drainLoop()` line 289–290, on per-session VTs, inside `synchronized(session)`
- `queue.take()` and all queue-management work — on per-session VTs
- The `WebSocketKeepaliveService` PING work runs at `@Order(200)`, which IS inside the measurement

**Does this explain flatness?** Partially. Frame construction for modest entity counts (100–256) on a 256×256 grid is O(entities × vision_area), and with `PERCEPTION_RADIUS=2` the vision is 5×5=24 cells per bot. At 256 bots that's ~6K cell lookups per tick — plausibly cheap. The per-bot encode + send scaling from 100→256 VTs is hidden from this gauge.

**Is the gauge normalized?** No. It's a raw scalar of the most recent tick's wall time. It lags by 1 tick relative to the dispatching event (documented at `TickHealthMonitor` lines 25–30). It is NOT per-bot or per-tick averaged in a way that obscures scaling.

---

## B. What does the harness actually do at `--count 1000`?

**Source:** `LoadHarness.runInternal()` → `BotFleet.launch()` → one VT per bot → each VT calls `bot.connect()` then `bot.awaitRegistered(15_000L)`.

**With `cap: 256` and `--ramp-up rate:50`:**

1. Bots connect at 50/sec. Each `BotClient.connect()` (`BotClient.java:155`) creates a Jetty WebSocket, sends `r|<species>`. 
2. ~256 bots get `S|<entityId>` — they register successfully, receive T frames every tick, and send A frames back. These are the **active** population during steady state.
3. ~744 bots get `E|429|world-full` — `BotClient.onError()` at line 422–427 detects 429 and calls `disconnect()`, closing the WS and stopping the Jetty client. Their VTs complete with `registered=false`.
4. The harness runs 200s, then `fleet.shutdown()` iterates all 1000 `BotClient` instances, producing "BotFleet: 1000 bots disconnected" in the log. **This means 1000 BotClient objects were iterated — not that 1000 were concurrently connected during steady state.**

**So does `--count 1000` create 1000 concurrent active WebSocket sessions?** No. It creates ~256 active sessions + ~744 transient sessions that connect, receive 429, and disconnect. The transient bots generate one frame each (register) and one error response back — negligible steady-state load.

**Are active bots generating traffic every tick?** Yes — `BotClient.onTick()` at line 383 always calls `brain.decide()` and sends an action frame unless it's a terminal frame (death/absorb). Every registered bot produces one A frame per T frame.

---

## C. Admission cap interaction

**This IS the dominant effect.** Here's the math:

| Tier | --count | Cap | Active entities (steady) | Transient (rejected) |
|------|---------|-----|--------------------------|----------------------|
| 100  | 100     | 256 | ~100 (all register)      | 0                    |
| 500  | 500     | 256 | ~256                     | ~244                 |
| 1000 | 1000    | 256 | ~256                     | ~744                 |

The admission gate at `AdmissionGate.evaluate()` line 141–151 enforces `reservedSlots.get() >= cap` → reject for non-respawn registrations. Respawns bypass the global cap (line 141: `if (!req.isRespawn())`).

**So:**
- The 500 and 1000 tiers are effectively identical — both have ~256 active entities after the ramp window. The extra 244/744 bots never enter steady state.
- The 100-bot tier has 100 entities — **fewer** than the 500/1000 tiers, yet its measured tick time is marginally *higher* (17.7 vs 16.5/15.2 ms). This is within measurement noise (all tiers span 14–20 ms range) and may reflect different entity composition at lower density (more death-churn, different RPS dynamics).
- **The claimed 10× connection-count span is actually a 100→256 entity-count span** — roughly 2.5×, not 10×.

**What happens to the extra connections?** They receive `E|429` and disconnect. They never hold open WS sessions during steady state (line 422–427 explicitly calls `disconnect()`). They do not contribute to tick work at all.

**Could `--count = cap` (256) differ from `--count = 1000`?** Not meaningfully for steady-state tick work. Both would have ~256 active entities. The 1000-bot run generates 744 transient connect-reject-disconnect cycles during ramp-up, but those are one-shot events outside the metric sampling window.

---

## D. Where does broadcast/codec work actually live?

**Tick thread (inside gauge):** `TickBroadcaster.onTick()` at `@Order(50)`, lines 207–238:
- Iterates `botRegistry.getAllBots()` — O(active entities)
- Calls `buildTickFrame()` per bot: grid lookups, CellEntry construction, RLE assembly, envState computation, kind-code mapping, effect/event/roster building
- Calls `outboundSender.offer(bot.sessionId(), frame)` — non-blocking `ArrayBlockingQueue.offer()`

**Per-session VT (EXCLUDED from gauge):** `OutboundSender.drainLoop()` lines 273–313:
- `queue.take()` — blocks until frame available
- `PerceptionCodec.encode(frame)` line 286 — string encoding (the expensive part)
- `session.sendMessage(new TextMessage(encoded))` line 290 — wire write under `synchronized(session)`
- `metrics.recordFrameSize(encodedBytes.length)` line 288

**So the tick thread's per-bot cost is frame construction + offer.** Frame construction includes env-state computation (which calls `envStateFor()` → `computeVisionScopedOvercrowded()` — up to 8 Moore neighbor checks per cell in the 5×5 window). This is O(entities × vision_area) work.

**What metric WOULD capture saturation here?**
- Per-session VT wall clock time — NOT captured. JFR's "Thread CPU Load" or "Java Monitor Blocked" would show it, but no dedicated gauge exists.
- `paralife.outbound.frame.size.bytes` — DistributionSummary at `AdmissionMetrics` line 167, records frame byte sizes. Not sampled in the sidecar.
- Queue depth `OutboundSender.queueDepth(sessionId)` — NOT exposed as a gauge. It's queryable per-session but the sidecar doesn't poll it.
- Drain VT join timeout `paralife.outbound.detach.timeout` — counter only; 0 means no VT took >100ms to exit on detach. With only 256 VTs and a 2GB heap, this is expected.
- JFR recording of VT execution — present in the 180s JFRs. These should be examined for per-session VT CPU usage.

The two metrics sampled in the sidecar cannot detect per-session saturation. They tell a narrow story: the tick loop thread is healthy and VTs exit fast. Neither proves the per-session outbound pipeline is uncongested at scale.

---

## E. JFR + flamegraph capture validity

**Sequential capture vs. concurrent:** The plan script at line 150–153 launches three captures in parallel with `& wait`. The summary reports async-profiler 4.4 rejects concurrent attach. The deviation is genuine and forced by tooling. However, the deviation's impact:

- **cpu capture**: During the main 1000-bot harness run (first 60s of the 200s window). Valid workload.
- **alloc capture**: "immediately after" cpu — the summary is ambiguous about whether the first harness was still running. If harness completed its 200s before alloc started, the alloc flamegraph reflects server idle, not 1000-bot load. This is a **significant data-quality gap**.
- **lock capture**: From a second 1000-bot LoadHarness run. Same cap=256 environment, so comparable steady-state workload. The lock flamegraph is valid for the capped population.

**Lock flamegraph: 6 frames vs CPU's 209 — evidence of "no contention"?** 

6 frames in a JVMTI `MonitorContendedEnter` profile is genuinely low. Given:
- `synchronized(session)` in `drainLoop()` line 289 is held only for the `sendMessage` call (a single write per tick per bot)
- Keepalive PINGs and OOB frames also briefly synchronize on sessions, but are low-frequency
- With ~256 active VTs and 8 carrier threads, VTs would mostly acquire monitors without blocking

So 6 frames is consistent with the architecture successfully isolating contention. However, it does NOT prove "no contention" — only that no thread was *sampled while blocked* on monitor entry. JVMTI `MonitorContendedEnter` only fires when `Thread.State == BLOCKED`. If VTs yield briefly on `synchronized` but the carrier thread is always available, the event never fires. The result is qualitatively useful but not a proof of zero contention.

**Statistical adequacy:** 6 samples × 5s = 30s at 500ms ticks = ~60 ticks observed per window. For a gauge that already shows low within-tier variance (std dev ~2ms), this is adequate to measure tick work-time. For detecting rare latency spikes or infrequent backpressure events, 60 ticks is marginal.

---

## F. The three-gate result and stale-golden defence

**The argument is structurally sound IF** the golden file at c22e487 is truly stale (i.e., the simulation at c22e487 produces deterministic output that was correct all along and the golden was merely a stale test artifact) AND commit f6da129 made no behavioral changes.

**Risk:** The commit message `fix(19.1): pass-1 multi-review follow-up sweep` suggests code changes beyond just a golden update. If those code changes include simulation-affecting fixes, then:
- c22e487 has simulation behavior that differs from HEAD
- The golden test at c22e487 is CORRECTLY flagging that difference
- Dismissing the failure as "stale golden" hides a real regression

The author claims "simulation determinism confirmed: identical actual digests across two consecutive runs at c22e487." This proves determinism within c22e487 — not that c22e487's simulation output matches f6da129's. The "stale golden" framing is plausible but I'd want to see the actual diff between the golden at c22e487 and the golden at f6da129 for sess-9/sess-21 to distinguish "stale artifact" from "code change changed simulation output."

**Risk to downstream baselining:** Moderate. If c22e487 has different simulation behavior than the codebase HEAD that Plans 20-04/05/06 will eventually measure against, the "before/after" diffs will include both tuning effects AND the behavioral delta between c22e487 and HEAD. The c22e487 anchor isn't a known-degraded reference — but it's a known-diverged one.

---

## G. Assumption A8 falsification

**The check was wrong:**
```bash
java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'
```
This runs Java WITHOUT `-XX:+UseZGC`. `PrintFlagsFinal` shows the flags for *this invocation*. Without `-XX:+UseZGC`, ZGC is not active, so `UseZGC=false` is tautologically true regardless of what the JVM's default GC is.

**The correct check would be:**
```bash
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational
```
This tests whether `ZGenerational` defaults to `true` WHEN ZGC is active. That's the question A8 intended to answer: "does generational ZGC default on when you use ZGC on Temurin 21.0.6?"

**Is the conclusion wrong?** The conclusion ("Temurin 21.0.6 default GC is G1") happens to be correct — G1 has been the default GC since Java 9, and ZGC has never been the default without explicit `-XX:+UseZGC`. But the methodology was testing the wrong question.

**Downstream risk:** Plan 4 GC recipes should correctly note that `-XX:+UseZGC -XX:+ZGenerational` are required for generational ZGC. Since the conclusion is accidentally correct, the practical impact is nil — but the methodology error means the author cannot distinguish between "ZGenerational defaults to false" (gen Z is opt-in even with ZGC) vs "ZGenerational defaults to true when ZGC is active" (gen Z is default-when-ZGC). The real answer (for Temurin 21, ZGenerational=true IS the default when ZGC is active) might be the opposite of what the flawed test suggests.

---

## H. Overall verdict and recommended re-measurement

**Verdict: RED.** The baseline is misleading as cited. The admission cap at 256 means the 500- and 1000-bot tiers are measuring the same system state. The sampled metric excludes per-connection cost. The headline conclusion overstates what was actually measured.

**The framing fix needed IF anyone insists on keeping these measurements:** Replace every instance of "10× connection-count scale" with "population range 100–256 active entities (admission cap: 256)." Retitle the headline: "Tick work-time is flat across 100–256 active entity range."

### Minimum re-measurement to answer "does the server scale with connection count?"

**Measurement 1: Raise the admission cap to 1000 and re-run all three tiers.**

```yaml
paralife.admission.cap: 1000
```
Re-capture: 100, 500, 1000 bots with cap raised to ≥1000. If tick work-time remains flat (e.g., 15–20ms at all three tiers), that genuinely proves tick-path scalability to 1000 active entities. If it climbs (e.g., 17ms → 50ms → 120ms), you've found where the tick bottleneck lives. This is the only reliable hardware of the "flat" result.

**Measurement 2: Add a per-session outbound saturation gauge.**

In `OutboundSender.drainLoop()`, add a Micrometer `Timer` around the `encode() + sendMessage()` block, tagged with session source. Sample as gauge `paralife.outbound.encode.ms.p99` per harness bucket. If this climbs with connection count while tick-work stays flat, the bottleneck is encode/write, not tick logic. This is the missing counterpoint to the tick-work gauge.

**Measurement 3: Queue-depth gauge snapshots per tier.**

The sidecar should sample `OutboundSender.queueDepth()` for a representative subset of sessions (or the max depth across all sessions). At 1000 bots, if queues consistently hover near capacity, the system is encode-saturated regardless of tick work-time. A `paralife.outbound.queue.depth.max` gauge doesn't exist today but is trivial to add.

**Measurement 4: JFR "Application Wall Clock" or "Thread CPU Load" per VT.**

The existing JFRs contain thread-level CPU data. Before re-running, extract VT CPU usage from the existing 100/500/1000 JFRs — if per-session VT threads show increasing CPU load across tiers, that's the hidden scaling cost. This requires no code changes, just JFR analysis that the author hasn't yet performed.

---

## Consensus Summary

### Agreed Strengths
- Tick work-time gauge measurement is internally consistent — captured wall time around `eventPublisher.publishEvent(event)` in `TickEngine.tickLoop` is correctly recorded by `TickHealthMonitor` and exposed via `AdmissionMetrics`.
- Frame construction (`TickBroadcaster.onTick`) IS inside the measurement window; only encode + `sendMessage` (per-session VT in `OutboundSender.drainLoop`) is excluded — all four reviewers traced the code path identically.
- Low lock-frame count (6 vs 209) is consistent with the D-10 per-session VT isolation design working as intended.
- A8's conclusion (ZGC and generational ZGC are opt-in on Temurin 21.0.6) is correct, even if accidentally so.

### Agreed Concerns
- **CRITICAL — Admission cap (`paralife.admission.cap: 256`) silently bounds workload.** All four reviewers independently identified this as the dominant confound. At `--count 500` and `--count 1000`, ~244 and ~744 bots receive `E|429|world-full` from `AdmissionGate.evaluate` (line 139–151) and disconnect. Steady-state active entities at all three tiers converge near 100/256/256, not 100/500/1000. The "10× connection-count scale span" framing is misleading; actual active-entity span is ~2.5×.
- **CRITICAL — Sampled gauge excludes per-connection cost.** `paralife.tick.health.work-time-ms` does not include `PerceptionCodec.encode` or `session.sendMessage` (both run on per-session VTs in `drainLoop` lines 273–313). Per-connection scaling cost lives in the VTs and is invisible to this metric. Flatness proves tick-thread dispatch scales over 100–256 entities; it does NOT prove "server CPU is far from the bottleneck at 1000 bots."
- **HIGH — Critical saturation metrics not sampled.** Sidecars captured only tick work-time and `detach.timeout`. Missing: `paralife.admission.rejected{reason=world-full}` counter, active-entity / active-session gauges, `OutboundSender.queueDepth` (exposed as accessor at line 249 but not registered as Micrometer gauge — observability gap), encode+send latency, process CPU / carrier-thread saturation, harness final-report fields (`peakRegistered`, `connectFailuresTotal`).
- **HIGH — A8 methodology wrong (conclusion accidentally correct).** `java -XX:+PrintFlagsFinal -version | grep ZGenerational` without `-XX:+UseZGC` is tautological — `ZGenerational` is only consulted when ZGC is active. Correct check: `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`. Downstream Plan 4 GC recipes should still specify `-XX:+UseZGC -XX:+ZGenerational` explicitly for Temurin 21.0.6.
- **MEDIUM — Lock flamegraph captured from a different harness run** than JFR + cpu + alloc (async-profiler 4.4 rejected concurrent attach). Acceptable as exploratory evidence but not proof that all four captures reflect the same workload instance. "6 frames = no contention" overstates: JVMTI `MonitorContendedEnter` only fires when threads `BLOCK`, and is blind to `java.util.concurrent` primitives, CAS loops, and `ConcurrentHashMap` contention — the per-session VT model means backpressure manifests as queue depth, not monitor contention.
- **MEDIUM — Statistical adequacy weak.** 6 point samples × 5s windows = 6 last-tick scalars per tier (not 60 tick samples). Three-decimal precision (17.7 / 16.5 / 15.2 ms) is spurious given within-tier variance.

### Required Re-measurement (all four reviewers converged)
- Raise `paralife.admission.cap` to ≥1000 and re-run all tiers to actually admit `--count` bots.
- Sample `paralife.admission.rejected`, active-sessions, active-entities gauges per tier — verify admitted count actually moved with `--count`.
- Add and sample outbound queue-depth distribution (p50/p95/max), encode+send latency, and process CPU / JFR `jdk.CPULoad`.
- Quote harness `peakRegistered` / `currentRegistered` / `connectFailuresTotal` (already collected, just unsurfaced).

### Divergent Views
- **Verdict severity.** Claude says **YELLOW** (measurements internally consistent, framing overstated, fixable via SUMMARY edits + optional re-capture). Gemini, codex, and opencode say **RED** (baseline is fundamentally misleading and unsafe as evidence for downstream Plans 4/5/6 — minimum re-measurement required, not just re-framing).
- **Stale-golden defence (three-gate failure at c22e487).** Claude treats it as "plausible but needs `git show --stat f6da129` to confirm fixture-only"; codex calls it "partially sound" but flags that `f6da129` touched production code (`SimulationEngine` deterministic composite iteration), not only test fixtures; opencode notes the commit message "pass-1 multi-review follow-up sweep" suggests behavioral code changes; gemini calls the framing "structurally unsound — face-saving for code-behavior change." Investigation needed: diff `f6da129` and confirm whether simulation-code paths affecting sess-9/sess-21 changed.
- **Severity of the lock-flamegraph cross-run capture.** Claude and codex treat as acceptable exploratory evidence with caveats; opencode raises a separate concern that the *alloc* capture may have started after the first harness run completed (timing ambiguous in SUMMARY), making alloc-graph workload validity a distinct data-quality gap.
- **Whether re-framing SUMMARY alone is sufficient** (Claude) or whether re-capture with raised cap is mandatory before citing the baseline in Plans 4/5/6 (Gemini, codex, opencode).
