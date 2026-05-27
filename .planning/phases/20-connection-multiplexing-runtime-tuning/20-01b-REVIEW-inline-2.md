---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-20T19:58:18Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/TickHealthMonitor.java", "src/main/java/com/paralife/admission/AdmissionGate.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/harness/LoadHarness.java", "src/main/resources/application.yml"]
usage:
  claude: { input: 22, output: 377, cached: 1571602, tool_calls: 4, elapsed_s: 221.0 }
  gemini: { input: 135446, output: 1792, cached: 8160, tool_calls: 0, elapsed_s: 87.8 }
  codex: { input: 3166794, output: 13102, cached: 3012480, tool_calls: 66, elapsed_s: 290.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 10, elapsed_s: 397.7 }
synthesizer: claude
synthesized_at: 2026-05-20T19:58:18Z
---

# Cross-AI Review

## Claude Review

Verdict and findings below. I read every file the request named plus `WorldWebSocketHandler.java`, `BotFleet.java`, and `BotClient.java` to validate the cap-interaction chain end-to-end.

---

# Verdict: **RED**

The baseline measurements are internally consistent (artifacts exist, sizes within budget, anchored to `c22e487`) but the **headline conclusion is wrong**, and downstream plans citing the 17.7 / 16.5 / 15.2 ms trio will chase the wrong bottleneck. Three reasons:

1. **`paralife.admission.cap: 256`** (`application.yml:67`) collapses the 100/500/1000 tiers to ~the same on-grid workload. At `--count 1000`, only 256 bots become entities; the other ~744 are rejected with `WORLD_FULL` and **immediately disconnect themselves** (`BotClient.java:422-426`). The "10× connection-count scale span" never happened — the harness drove 10× more connection *attempts*, not 10× more sustained sessions.
2. **`paralife.tick.health.work-time-ms` excludes the per-session encode + send path.** It measures the tick thread (`TickEngine.tickLoop` start-to-end at `TickEngine.java:104-119`, set via `lastTickWorkMs = elapsed`). Inside that window, `TickBroadcaster.onTick` enqueues frames non-blockingly (`TickBroadcaster.java:172` → `outboundSender.offer(...)`); the encode + `sendMessage` runs on the per-session VT in `OutboundSender.drainLoop` (`OutboundSender.java:259-285`). So per-connection cost is **structurally invisible** to this gauge.
3. The conclusion "server CPU is far from the bottleneck at 1000 bots" doesn't follow from a metric that (a) only sees ≤256 entities regardless of N and (b) excludes the per-connection work that should scale.

A documented null-result is still a valid SCALE-08 outcome (D-21), but **not this null-result.** The pre-cap framing has to be rewritten before Plan 6 §4.2 cites the numbers.

---

## A. What does `paralife.tick.health.work-time-ms` actually measure?

It is a **scalar gauge of the most-recently-completed tick wall-clock time on the tick thread**, written at `TickEngine.java:113` (`this.lastTickWorkMs = elapsed`) after `eventPublisher.publishEvent(event)` returns at line 108. The sample window spans **every synchronous `@EventListener` on `TickEvent`**: `ResumeTokenRegistry @Order(1)`, `SimulationEngine @Order(10)`, `EnvironmentEngine @Order(14)`, `CompositeEnergyDistributor @Order(15)`, `ActionResolver @Order(20)`, `EnvPostActionReconciler @Order(25)`, **`TickBroadcaster @Order(50)`**, `WebSocketKeepaliveService @Order(200)` (per `CLAUDE.md` §Architecture).

`TickBroadcaster.onTick` IS in the window — but it only does frame **build** + non-blocking **offer**: line 172 `outboundSender.offer(bot.sessionId(), frame)`. The codec encode (`PerceptionCodec.encode`) and `session.sendMessage` happen later, off-thread, in `OutboundSender.drainLoop` (`OutboundSender.java:266-282`).

`TickHealthMonitor.onTick` at `@Order(Integer.MAX_VALUE)` mirrors `lastTickWorkMs` into the Micrometer gauge (`TickHealthMonitor.java:55-56`) — so the actuator value the sidecar polls is the same tick-thread number. **The gauge is per-tick, NOT per-bot and NOT normalized**; it's a single ms reading of one wall-clock window per tick. That's correct as a tick-health signal but inadequate as "headroom at 1000 bots."

So the flatness explanation **is** structural: per-connection encode/send overhead lives off-thread and is filtered out of this metric by design.

## B. What does the harness actually do at `--count 1000`?

`LoadHarness.runInternal` calls `fleet.launch(...)` (line 248), which in `BotFleet.launch` (`BotFleet.java:84-135`) spawns one VT per bot. Each VT calls `bot.connect()` then `bot.awaitRegistered(15_000L)` (line 114). The default `--ramp-up rate:50` means ~50 connect attempts/s → all 1000 attempts complete around t=20s; well under the 200s steady-state window.

But "connected" ≠ "active." Each `BotClient`:
- Opens WS, sends `r|<species>` register frame.
- Waits for `S|...` sync. If received → `awaitRegistered` returns true → bot starts driving actions.
- If receives `E|<code>|<token>` instead — and the code is `429` — it calls `disconnect()` itself (`BotClient.java:422-426`).

So at `--count 1000` with cap=256, only the first ~256 to reach admission generate sustained traffic. The remaining ~744 collect a 429 rejection and disconnect within their 15s timeout window. The fleet log `BotFleet: 1000 bots disconnected` on exit (cited in `jfr-1000bots-baseline-c22e487.meta.json` A6 entry) does **not** mean 1000 sustained sessions — it means 1000 `BotClient` instances were created and all eventually called `disconnect()`. **A6 was over-claimed.**

A direct test: `fleet.currentRegistered()` (`BotFleet.java:171`) or `fleet.connectFailuresTotal()` (`BotFleet.java:150`) would have shown the truth. The sidecar didn't capture them.

## C. Admission cap interaction — **this is the dominant effect**

`application.yml:67` sets `paralife.admission.cap: 256`. `AdmissionGate.evaluate` enforces it at lines 121-130:

```java
if (!req.isRespawn()) {
    while (true) {
        int n = reservedSlots.get();
        if (n >= cap) return reject(req, session, 429, RejectionToken.WORLD_FULL);
        if (reservedSlots.compareAndSet(n, n + 1)) break;
    }
}
```

`WorldWebSocketHandler.handleRegister` then sends `E|429|world-full` to the rejected bot (`WorldWebSocketHandler.java:477-480`), and as noted in (B) the client self-disconnects. Net effect:

- **At `--count 100` (under cap):** 100 entities, all 100 sustained sessions.
- **At `--count 500`:** 256 entities, 256 sustained sessions, ~244 rejected and disconnected.
- **At `--count 1000`:** 256 entities, 256 sustained sessions, ~744 rejected and disconnected.

`SimulationEngine`, `EnvironmentEngine`, `ActionResolver`, `TickBroadcaster` all iterate `BotRegistry`/grid contents, which are bounded by entity count, not connection-attempt count. Tick work-time therefore reflects ~100/256/256 entities across the three tiers, NOT 100/500/1000. The ~13% drop in mean from tier-100 to tier-1000 is well within the per-sample variance (raw 1000-tier values [14,18,14,14,15,16] σ ≈ 1.5 ms) — the cap-saturated tiers are statistically indistinguishable.

**This alone is enough to invalidate the headline finding.** The 100-tier baseline is also under the cap, so the comparison was 100-entities vs 256-entities vs 256-entities, not 100/500/1000.

## D. Where does broadcast / codec work live?

Per Phase 17 D-10 (also `CLAUDE.md` §Outbound concurrency): one VT per session, draining a bounded `ArrayBlockingQueue<Frame>`. The hot path:

| Step | Where it runs | Captured by tick-work-time? |
|---|---|---|
| `buildTickFrame` per bot | tick thread, `TickBroadcaster @Order(50)` | yes |
| `outboundSender.offer(...)` | tick thread, non-blocking | yes (microseconds) |
| `PerceptionCodec.encode(frame)` | per-session VT (`OutboundSender.java:267`) | **no** |
| `metrics.recordFrameSize(...)` | per-session VT | **no** |
| `synchronized(session) { session.sendMessage(...) }` | per-session VT | **no** |
| WSS write to socket | per-session VT into Jetty | **no** |

What would capture per-session saturation:
- **`paralife.outbound.frame.size.bytes`** (`AdmissionMetrics.java:51`, registered line 159) — `DistributionSummary`, count + sum directly reflects encode work per tier.
- **`OutboundSender.queueDepth(sessionId)`** (not exposed as a meter; `OutboundSender.java:226`) — would show backpressure rising.
- **`paralife.backpressure.stalled.sessions`** gauge (`AdmissionMetrics.java:48`).
- **JFR `jdk.ThreadCPULoad` filtered to `ws-sender-*` VTs** — captures actual encode CPU.
- **async-profiler with `--threads`** — separates per-session CPU from the tick thread.

None of these were in the sidecar. The CPU flamegraph (`cpu-1000bots-baseline-c22e487.html`) would show the truth across all threads, but only if the captured workload had real per-connection traffic — and (C) says it didn't.

## E. JFR + flamegraph capture validity

Multiple issues, in order of severity:

1. **Sequential vs concurrent capture is OK *if* the workload is steady-state.** The summary acknowledges async-profiler 4.4 rejects concurrent attach and ran cpu→alloc→lock sequentially. The lock capture required a *second* 1000-bot run. Two runs against the same code at the same load should produce statistically similar flamegraphs, so cross-comparing lock to cpu is valid in principle. But it's NOT the same workload moment — JFR + cpu + alloc are run 1, lock is run 2. The summary's framing should acknowledge this.

2. **6 frames in the lock flamegraph is weak evidence of "no contention."** Reasons it could be misleading:
   - JVMTI `MonitorContendedEnter` (which asprof `-e lock` uses on JDK 21) fires only on *contended* monitor enter — uncontended fast-path enters are invisible. The 4 `synchronized(session)` writers documented in `CLAUDE.md` §Outbound concurrency are per-session, so cross-session contention is structurally low even before tuning — confirming D-10 architectural intent, but not really news.
   - **More importantly:** if (C) is right and only ~256 sessions are doing real work at the 1000-tier capture, you measured low-contention workload by accident. A genuine 1000-active-session test (cap=2000 or unlimited) could show different lock numbers, especially around `WorldGrid`'s `ReentrantReadWriteLock` — 1000 broadcaster reads per tick instead of 256.
   - "6 vs 209 frames" is a frame-node count, not a sample count. Frame nodes scale with stack-depth diversity, not contention magnitude. The right comparison is total *time spent in monitor wait* — present in the underlying flamegraph data but not extracted into the summary.
   - **Speculation:** the lock flamegraph could also be sparse because async-profiler's default lock-event threshold (`-t 10ms` by default) filters out short waits. `synchronized(session) { sendMessage(...) }` holds the monitor for a single Jetty write; sub-ms contention wouldn't sample.

3. **6 samples × 5s = 30s window vs 500ms tick = 60 ticks observed.** Statistically adequate for a steady-state mean IF the rolling-window hysteresis (`TickHealthMonitor` window of 10 ticks) has settled before sampling starts. 30s after harness ramp completion is fine. σ ≈ 1.5 ms / mean 15.2 ms ≈ 10% CV, which is acceptable for "what's the typical tick cost" but weak for detecting small (e.g., 2–5%) regressions Plan 5 might care about. Pass-2 Concern #11's noise-floor convention (±5% or ±1σ whichever is larger) means anything under ~1.5 ms delta is invisible — which is fine for an order-of-magnitude baseline but should be called out.

## F. Three-gate result and the stale-golden defence

The argument is **structurally sound but unverified by the summary itself.** The claim shape is: (a) two consecutive runs at c22e487 produce identical actual digests → simulation determinism holds at c22e487; (b) the pinned golden in c22e487 doesn't match those digests → the golden was wrong at c22e487; (c) f6da129 updated the golden to match → the gap was a fixture issue, not a code regression.

The hole is (c). The summary doesn't show that **f6da129's diff is golden-file-only**. If f6da129 also touched any `*.java` in the broadcast / codec / sim path, then c22e487's actual digests at sess-9/21 ARE different from HEAD's — and the simulation at c22e487 differs from HEAD in some way, which means the baseline JFRs were captured against a slightly different system than the one Plan 5's tuned-state runs against. Likelihood is low (the commit subject says "pass-1 multi-review follow-up sweep" which usually means doc / minor fixes), but the summary should have included `git show --stat f6da129` to settle it.

Compounding risk: c22e487 is the Phase 19.1 close anchor, but by Plan 5's tuned-state capture HEAD will be at least 6–10 commits ahead (Plans 20-01, 20-01b, 20-03 already landed, then 20-02 / 20-04 / 20-05 follow). Each commit is a small drift in the baseline-to-tuned diff. D-19 reproducibility is preserved (anyone can `git checkout c22e487` and re-run), but the "before/after" framing in Plan 6 §4.2 will be measuring "Phase 19.1 close vs end-of-Phase-20" — not "Phase-20-only tuning effect." This is acknowledged in the backlog item Phase 999.5 (post-MVP re-baseline), so it's a known trade-off — but it deserves an explicit caveat in `20-RUNTIME.md` §4.2 saying so.

## G. Assumption A8 falsification

**Conclusion correct, methodology weak.**

The author ran `java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` and observed both flags = `false`. What that actually proves: in *this specific JVM invocation, with no GC flags passed,* neither ZGC nor generational-mode is in effect. It does **not** directly prove "Generational ZGC is not the JVM's default" — because `PrintFlagsFinal` shows effective flag values for the current invocation, not the JVM's chosen default GC.

The conclusion is still right (Temurin 21.0.6's default GC is G1; ZGC and Generational ZGC are both opt-in). But the cleaner tests are any of:

- `java -version` — Temurin's banner doesn't always show the GC; better:
- `java -XshowSettings:vm -version` — sometimes shows "Using G1 Young Generation"
- `java -XX:+PrintFlagsFinal -version 2>&1 | awk '/UseG1GC|UseZGC|UseParallelGC|UseSerialGC/ {print}'` — and inspect which row carries the `{ergonomic}` marker (that's the JVM's chosen default).
- Or run an idle JVM and `jcmd <pid> VM.flags` to see the effective active GC.
- Or in JDK 21+: `java -Xlog:gc::time -version` will print a single GC start line naming the collector.

In JDK 21 Temurin, `UseG1GC` is marked `{ergonomic}` true by default → G1 is the default. ZGC requires `-XX:+UseZGC`. In JDK 21, generational ZGC requires `-XX:+UseZGC -XX:+ZGenerational` (it became default-when-ZGC-is-on in JDK 23+). Plan 4 should re-test with the ergonomic-flag method and explicitly verify the JDK 21 ZGenerational flag behaviour against the actual Temurin 21.0.6 build.

## H. Overall verdict and required re-measurements

**Verdict: RED.** The baseline must be re-framed (minimum) or re-measured (better) before Plan 6 §4.2 cites these numbers. The artifacts on disk are usable as *raw evidence* of the c22e487 codebase's behaviour at ≤256 entities; they are NOT a 100/500/1000-bot scaling study.

### Minimum framing fix (no re-capture)

Rewrite the headline in `20-01b-SUMMARY.md` to:

> Across all three harness tiers (100/500/1000 connect attempts), the server admitted at most 256 entities (`paralife.admission.cap: 256`); above the cap, excess sessions received `E|429|world-full` and self-disconnected (`BotClient.java:422`). Per-tick simulation work measured at the tick thread (excluding per-session encode and `sendMessage`, which run on per-session VTs per Phase 17 D-10) was 17.7 / 16.5 / 15.2 ms across the three tiers — effectively flat because the effective on-grid workload is 100 / 256 / 256 entities, not 100 / 500 / 1000. This baseline measures the server at the *admission-cap* envelope; it is NOT evidence of headroom for 1000 sustained sessions.

Then `20-01b-SUMMARY.md` §"Headline finding" must be deleted or replaced — the current claim is unsupported.

### Required re-measurements to actually answer "does the server scale with connection count?"

Pick 2–4 of:

1. **Raise the cap or unset it for the baseline run.** Temporarily set `paralife.admission.cap: 1500` (or whatever > tier-1000) for the *measurement* runs, captured as a separate set named `jfr-{N}bots-uncapped-baseline-c22e487.jfr`. Filename convention preserves D-19. The harness can then actually drive 1000 concurrent sustained sessions and you'll see real per-connection scaling. Expected saturation signature: tick-work-time grows roughly linearly with on-grid entity count from the broadcaster's frame-build loop (`TickBroadcaster.onTick` iterates `BotRegistry.getAllBots()` line 161); also `paralife.outbound.frame.size.bytes` count grows linearly.

2. **Snapshot `paralife.admission.rejected{reason=world-full}` and `paralife.outbound.frame.size.bytes` counters into the sidecar.** Both meters are already registered (`AdmissionMetrics.java:96-104, 159-163`); add to the curl sampling loop in `20-01b-PLAN.md` Task 1b.0 step 4b. Expected signature: capped tier shows rejected counter growing → confirms the cap saturation; uncapped tier shows frame-size count growing linearly in N → confirms per-connection broadcast scaling. Cost: 2 extra `curl` calls per sample, negligible.

3. **JFR `--threads` view OR async-profiler `--threads` aggregating ws-sender-* VT CPU.** Captures the actual cost of per-connection encode/send work that `tick.health.work-time-ms` doesn't see. Expected signature: total `ws-sender-*` thread CPU grows linearly in active session count. Sampling shape: existing 60s cpu flamegraph with `-t` flag added (no extra run needed; just re-render).

4. **`OutboundSender.queueDepth` summary stat per tier.** Currently observable per-session but not aggregated. Adding a Gauge that publishes `max(queueDepth)` across attached sessions, or just sampling `attachedCount()` (already exists, line 233) into the sidecar, would surface whether the per-session VTs are keeping up. Expected signature: in a healthy run, max queueDepth stays near 0; if it grows, you're seeing the first sign of saturation (well before STALLED triggers).

If only ONE of these is done, pick (1) — it's the structural fix; the others are useful but secondary.

Side note on the "interview portfolio" framing pinned in memory: this review should be told straight regardless. The honest framing — "we measured the wrong thing, here's the cap interaction, here's the right re-measurement" — is more impressive than a fragile clean baseline that downstream tuning then contradicts.

---

## Gemini Review

**VERDICT: RED**

The baseline is fundamentally misleading and cannot be used as a valid reference for downstream tuning. The top three reasons are:
1. **Admission Cap Invalidates the Load Test:** The server configuration caps admitted entities at 256. At the 500 and 1000 bot tiers, the vast majority of connections are rejected and generate no simulation or encoding workload.
2. **Blind to Connection Scaling Costs:** The headline metric (`paralife.tick.health.work-time-ms`) explicitly excludes the text-encoding and WebSocket broadcast work, which are offloaded to per-session virtual threads. It only measures the fixed-cap simulation physics.
3. **Flawed GC Verification:** The methodology used to falsify the ZGC assumption (A8) was technically invalid, risking incorrect garbage collector tuning in downstream plans. 

---

### A. What does `paralife.tick.health.work-time-ms` actually measure?
Read from `src/main/java/com/paralife/engine/TickEngine.java`, the gauge measures the wall-clock time required for the single `tick-engine` virtual thread to publish the `TickEvent` and execute all synchronous `@EventListener` methods. This **includes** building the perception payload (`TickBroadcaster.buildTickFrame`) but explicitly **excludes** the heavy codec string generation and network broadcast. 

In `TickBroadcaster.java`, the tick thread merely enqueues the unencoded frame (`outboundSender.offer(bot.sessionId(), frame)`). This absolutely explains the metric's flatness: the true per-bot scaling costs (JSON/Base64 encoding and TCP socket I/O) are completely hidden from this gauge.

### B. What does the harness actually do at `--count 1000`?
Per `src/main/java/com/paralife/harness/LoadHarness.java`, the harness successfully connects 1000 concurrent WebSocket sessions and begins sending registration/action frames. However, because of the server's admission logic, these bots do not all become active participants. The bots that are rejected are effectively silenced—they do not generate perception or action traffic in the tick loop because they do not possess an entity on the grid.

### C. Admission cap interaction
`src/main/resources/application.yml` sets `paralife.admission.cap: 256`. 
In `src/main/java/com/paralife/admission/AdmissionGate.java`, Guard 5 enforces this limit strictly:
```java
        int cap = admissionConfig.cap();
        if (!req.isRespawn()) {
            while (true) {
                int n = reservedSlots.get();
                if (n >= cap) {
                    return reject(req, session, 429, RejectionToken.WORLD_FULL);
                }
// ...
```
At `--count 1000`, 744 sessions are rejected with `429 WORLD_FULL` and held off-grid. This is the **dominant effect** explaining the flat tick work-time. The 100-bot tier simulates 100 entities. Both the 500-bot and 1000-bot tiers simulate exactly 256 entities. From the perspective of the `TickEngine`, the 500-tier and 1000-tier are practically identical, rendering the scaling comparison entirely meaningless.

### D. Where does broadcast / codec work actually live?
As seen in `src/main/java/com/paralife/admission/OutboundSender.java`, the per-connection encode and send costs are paid entirely inside per-session virtual threads:
```java
    private void drainLoop(WebSocketSession session, ArrayBlockingQueue<Frame> queue) {
        // ...
                Frame frame = queue.take();
                if (!session.isOpen()) continue;
                try {
                    String encoded = PerceptionCodec.encode(frame);
                    byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8);
                    metrics.recordFrameSize(encodedBytes.length);
                    synchronized (session) {
                        session.sendMessage(new TextMessage(encoded));
```
None of this CPU load surfaces in the sampled sidecar metrics. To capture saturation in the broadcast/codec layer, the sidecar would need to sample overall `process.cpu.usage`, or measure queue depths/stalls via the `paralife.backpressure.stalled.sessions` gauge.

### E. JFR + flamegraph capture validity
The lock flamegraph is capturing a completely different workload. Because async-profiler 4.4 cannot attach concurrently, the operator ran a *second* 1000-bot run for the lock flamegraph. 

Furthermore, claiming the 6 frames prove "no contention" is deeply flawed. Since 744 of the 1000 bots are rejected by the admission gate and parked, they aren't generating tick frames or trying to write to their sockets. The workload is artificially serene. `JVMTI MonitorContendedEnter` also has known coverage gaps as it only tracks actual contention, not uncontended lock acquisitions.

Finally, 6 samples × 5s = 30s is perfectly adequate to establish a statistical mean, but it is measuring a system artificially constrained by a 256-entity limit, making the stability of the mean irrelevant to the scale question.

### F. The three-gate result and the stale-golden defence
The author claims the baseline is safe because determinism is preserved despite the failure of `GoldenTraceEquivalenceTest` at `c22e487`. 

While it is entirely possible that `f6da129` only updated a stale JSON golden file, binding a baseline to a SHA that fails its own three-gate is dangerous. The commit title `fix(19.1): pass-1 multi-review follow-up sweep` heavily implies actual code changes were made. If `f6da129` changed simulation semantics to fix a bug, then downstream "before/after" diffs will be comparing against a broken implementation, permanently muddying the tuning delta.

### G. Assumption A8 falsification
The author checked for ZGC default behavior by running `java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'`.
This is invalid. Running `PrintFlagsFinal` without explicitly requesting ZGC just proves that the JVM's overarching default garbage collector is G1GC. It does not answer whether ZGC operates in generational mode *when ZGC is activated*. 

To correctly test assumption A8, the author must run:
`java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`
If this returns `false`, then ZGC defaults to non-generational. If `true`, it is generational by default. 

### H. The bigger picture

**RED** — the baseline is completely misleading. The admission cap hides the true scale of the load, and the sampled metric hides the true cost of the connections. Downstream plans citing this will be tuning against phantom headroom.

**Minimum re-measurement required:**
The baseline must be recaptured with `paralife.admission.cap` set to at least `1000` (or `5000`) so that all 1000 bots actually enter the grid, interact, and generate tick/encoding work.

**Additional measurements the author must capture:**
1. **`process.cpu.usage` (Micrometer Gauge):** Sampled via actuator alongside the other metrics. This is mandatory to definitively prove whether the server's overall CPU (including the per-session VTs performing the encoding) scales linearly.
2. **`paralife.admission.active.entities` (Micrometer Gauge):** To definitively prove that the server actually admitted 100, 500, and 1000 entities to the grid, rather than quietly rejecting them.
3. **`paralife.backpressure.stalled.sessions` (Micrometer Gauge):** To detect if the per-session VTs are falling behind the physics tick rate and dropping frames, which would manifest as artificially low CPU utilization.

---

## Codex Review

**Verdict: RED**. The flat tick-work result is real for the metric that was sampled, but it is not valid evidence that the server scales flat across 1000 active clients. Top three reasons: `paralife.tick.health.work-time-ms` excludes per-session encode/send work and mostly captures synchronous tick listener work; the production admission cap is `256`, so the 500/1000 tiers are not 500/1000 active simulated bots; and the sampled sidecars omit the metrics that would prove connection-scale behavior: active sessions, active entities, rejection counts, queue depth, enqueue failures, drain latency, and sender-thread CPU.

**A. Metric Semantics**

`paralife.tick.health.work-time-ms` is a scalar gauge, not per-bot and not normalized. The meter name is defined at [AdmissionMetrics.java:65](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:65), backed by one `AtomicLong` at [AdmissionMetrics.java:86](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:86), registered without tags at [AdmissionMetrics.java:149](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:149), and written directly via `setLastTickWorkMs` at [AdmissionMetrics.java:406](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:406).

The sampled value comes from `TickEngine.getLastTickWorkMs()` in [TickHealthMonitor.java:62](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:62). `TickEngine` measures wall-clock elapsed time around `eventPublisher.publishEvent(event)` at [TickEngine.java:109](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:109) through [TickEngine.java:119](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:119). That means it includes synchronous tick listeners, including `TickBroadcaster @Order(50)`, but only the work those listeners do before returning.

Critically, `TickBroadcaster` builds a `Frame.TickFrame` on the tick thread, then calls `outboundSender.offer(...)` at [TickBroadcaster.java:216](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:216) through [TickBroadcaster.java:223](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:223). Its comment explicitly says encode and `recordFrameSize` happen inside `OutboundSender.drainLoop`, not in the broadcaster, at [TickBroadcaster.java:218](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:218).

So the gauge includes frame construction and queue offer for active bots, but excludes per-session encode, byte conversion, and WebSocket send. That alone can explain why it fails to expose per-connection sender saturation.

**B. Harness Behavior**

`--count 1000` asks `LoadHarness` to launch 1000 bots. The option is defined at [LoadHarness.java:62](/home/mark/kramtime/paralife/src/main/java/com/paralife/harness/LoadHarness.java:62), and `runInternal()` calls `fleet.launch(serverUri, count, ...)` at [LoadHarness.java:267](/home/mark/kramtime/paralife/src/main/java/com/paralife/harness/LoadHarness.java:267). With `rate:50`, the ramp sleeps between starts; `RampUpSpec.Rate.awaitNext` parks `1_000_000_000 / perSecond` nanos at [RampUpSpec.java:42](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/RampUpSpec.java:42). So 1000 launch attempts take about 20 seconds before the duration wait begins.

But “1000 launched” is not “1000 active simulated bots.” `BotFleet` increments `liveCount` only when `BotClient.awaitRegistered(15_000L)` succeeds at [BotFleet.java:113](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotFleet.java:113) through [BotFleet.java:118](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotFleet.java:118). A server `S|...` frame is what releases `registeredLatch` at [BotClient.java:362](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:362) through [BotClient.java:375](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:375).

Registered bots receive tick frames and usually send action frames at [BotClient.java:383](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:383) through [BotClient.java:403](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:403). Rejected bots do not. On `E|429`, `BotClient` disconnects instead of retrying at [BotClient.java:422](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:422) through [BotClient.java:426](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:426).

Some registered bots can also be action-passive later: `HeuristicBrain.decide` returns `null` for bonded secondaries and non-locomotor composite members at [HeuristicBrain.java:83](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:83) through [HeuristicBrain.java:100](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:100). Initial solo bots are active, but the harness is not guaranteed to keep every logical bot action-active throughout the run.

**C. Admission Cap**

This is the dominant issue. The configured cap is `256` at [application.yml:64](/home/mark/kramtime/paralife/src/main/resources/application.yml:64) through [application.yml:65](/home/mark/kramtime/paralife/src/main/resources/application.yml:65). `AdmissionGate` enforces it using `reservedSlots`: if `n >= cap`, it rejects with `WORLD_FULL` at [AdmissionGate.java:139](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:139) through [AdmissionGate.java:146](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:146).

A rejected registration sends an error frame and returns before placement at [WorldWebSocketHandler.java:477](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:477) through [WorldWebSocketHandler.java:480](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:480). Only the allow path reaches `botRegistry.register(...)` at [WorldWebSocketHandler.java:624](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:624), stores `ATTR_ENTITY_ID`, sends sync, and increments active-entity metrics at [WorldWebSocketHandler.java:633](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:633) through [WorldWebSocketHandler.java:650](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:650).

Therefore, at `--count 500` and `--count 1000`, the excess sessions are not active simulated entities in steady state. They briefly connect, send `r|...`, receive `E|429|world-full`, and disconnect. They do not contribute to `botRegistry.getAllBots()` in `TickBroadcaster` at [TickBroadcaster.java:201](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:201).

A 500-bot or 1000-bot tier can absolutely look like a 256-entity tier for tick work. The 100-bot tier is below cap, so the meaningful comparison here is closer to 100 active entities versus about 256 active entities, not 100 versus 1000.

**D. Broadcast / Codec Work Placement**

`OutboundSender.attachSession` creates an `ArrayBlockingQueue` and starts one virtual sender thread per session at [OutboundSender.java:124](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:124) through [OutboundSender.java:135](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:135). `offer` is non-blocking at [OutboundSender.java:225](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:225) through [OutboundSender.java:242](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:242).

Encode and send are paid in the sender VT: `PerceptionCodec.encode(frame)`, UTF-8 conversion, frame-size recording, and `session.sendMessage(...)` happen at [OutboundSender.java:282](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:282) through [OutboundSender.java:303](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:303).

Metrics that would capture saturation there:

- `paralife.admission.active.entities{source=harness,harness=...}`: confirms active simulated population. Registered at [AdmissionMetrics.java:236](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:236) through [AdmissionMetrics.java:245](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:245).
- `paralife.ws.active.sessions`: confirms live WebSocket session population. Updated by `SessionRegistry` at [SessionRegistry.java:31](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/SessionRegistry.java:31) through [SessionRegistry.java:40](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/SessionRegistry.java:40), metric name at [WebSocketMetrics.java:32](/home/mark/kramtime/paralife/src/main/java/com/paralife/metrics/WebSocketMetrics.java:32).
- Outbound queue depth and attached sender count: `queueDepth` and `attachedCount` exist at [OutboundSender.java:249](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:249) through [OutboundSender.java:256](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:256), but are not registered as sampled meters.
- Sender VT drain latency / encode latency: not currently metered.
- Queue overflow / offer failures: callback exists, but the sidecar did not sample a per-tick enqueue-failure/drop metric.
- JFR CPU by thread name `ws-sender-*`, plus allocation by `PerceptionCodec.encode`.

The sampled sidecar only has `work-time-ms` and `detach.timeout`, so it cannot prove sender-side headroom.

**E. JFR / Flamegraph Validity**

The plan asked for concurrent `cpu`, `alloc`, and `lock` captures at [20-01b-PLAN.md:145](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md:145) through [20-01b-PLAN.md:153](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md:153). The summary says async-profiler 4.4 rejected concurrent attach and execution ran sequentially, with the lock flamegraph captured during a second 1000-bot run at [20-01b-SUMMARY.md:127](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:127) through [20-01b-SUMMARY.md:131](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:131). The meta file also marks lock as a follow-up run at [jfr-1000bots-baseline-c22e487.meta.json:9](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json:9) through [jfr-1000bots-baseline-c22e487.meta.json:12](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json:12).

So no, the lock flamegraph is not the same workload instance as JFR/cpu/alloc. It may be similar, but it is a separate run with a separate admission/rejection/death/respawn history.

“6 frames” is not strong evidence of “no contention.” It could mean low contention, but it could also mean event configuration, coverage limits, insufficient sample opportunity, different workload, or the cap keeping the real active set small. I also do not fully trust the summary’s “6 frame nodes” wording: counting flamegraph data entries locally gives 89 rows in the lock HTML versus 1348 in CPU, and root widths of 266 versus 436. The summary should report event type, duration, root sample count, and capture timing, not infer architecture success from a frame-count comparison.

The metric sampling is also thin. The sidecar captured 6 actuator scrapes at 5-second intervals, but each scrape reads the “most recently completed tick” gauge, not a 5-second mean. With a 500 ms tick interval, the run contained about 60 ticks during the 30-second window, but the sidecar observed only 6 individual tick values. The existing `paralife.tick.work.ms` `DistributionSummary` at [TickEngine.java:44](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:44) through [TickEngine.java:48](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:48) would have been a better actuator source for count/total/max/percentiles.

**F. Three-Gate / Stale Golden**

The stale-golden defence is not structurally sound as written. The summary says the c22e487 failure is a fixture-only issue at [20-01b-SUMMARY.md:114](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:114) through [20-01b-SUMMARY.md:118](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:118). But `f6da129` did more than update the golden file: it changed `SimulationEngine.processDeaths` to sort composites before RNG-consuming panic-zone checks. The diff adds `Comparator`, creates `sortedComposites`, and replaces `compositeRegistry.getAll()` iteration with sorted iteration. That is a code-level behavior/determinism fix, not just a golden refresh.

The commit message itself says the two-key golden refresh was an “intended consequence” of the `SimulationEngine.processDeaths` ordering change. That means the `trace-sess-9` and `trace-sess-21` delta could reflect real behavior difference between c22e487 and the post-f6da129 simulation. Deterministic output at c22e487 is not enough; deterministically wrong or deterministically pre-fix behavior still fails the gate.

Binding performance baselines to a SHA that fails its own three-gate adds downstream risk. It may not invalidate CPU measurements directly, but it weakens the claim that the baseline is a clean semantic reference for before/after comparisons.

**G. A8 / ZGC Check**

The no-flag command is valid for checking the JVM’s default collector. Locally, Temurin 21.0.6 reports:

```text
UseG1GC = true  {ergonomic}
UseZGC = false  {default}
ZGenerational = false {default}
```

So “default GC is G1, not ZGC” is supported.

But that command alone does not answer “if ZGC is requested, is it generational by default?” The correct checks are:

```bash
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational|UseG1GC'
java -XX:+UseZGC -XX:+ZGenerational -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational|UseG1GC'
```

On this machine, `-XX:+UseZGC` leaves `ZGenerational=false`, and adding `-XX:+ZGenerational` sets it true. So the final operational conclusion is still “ZGC and generational ZGC are opt-in,” but the summary should separate “default collector” from “ZGC mode when requested.”

**H. Bigger Picture**

**RED**. The baseline is misleading for the question “how does the server respond to 10x more clients?” It is only valid for “how much synchronous tick listener work occurs under the default 256-entity admission cap while 100/500/1000 clients attempt to connect.”

Minimum re-measurement:

- Run tiers that separate attempted connections from admitted entities: `100`, `256`, `500`, `1000`, with `paralife.admission.cap` explicitly set per scenario.
- For true 10x active-entity scaling, run with cap at least 1000 and prove `paralife.admission.active.entities` reaches the target.
- For true 10x connection-only scaling, keep cap fixed but explicitly frame the test as idle/rejected/connected-session overhead and capture active session count plus rejection rate.
- Capture sender-side metrics and JFR CPU by `ws-sender-*` threads.

Specific additional measurements to close the question:

1. `paralife.admission.active.entities{source=harness,harness=baseline-c22e487}` and `paralife.ws.active.sessions`, sampled every 1s for the full run. Expected saturation signature: active entities plateau at 256 while active sessions spike/fall; that would prove the current flat tick result is cap-bound.

2. `paralife.admission.rejected{reason=world-full,source=harness,harness=...}` and harness `connect_failures_total` / `syncs_received_total`. Expected signature: 500/1000 tiers show hundreds of world-full rejections and sync count near cap, not near requested count.

3. Outbound queue depth gauges: p50/p95/max queue depth across sessions, plus enqueue failure/overflow count per tick. Expected saturation signature: queue depth rises toward `128`, offer failures increase, and stalled-session counters grow before tick work necessarily rises.

4. Sender VT encode/send timing: `paralife.outbound.drain.latency.ms`, `paralife.outbound.encode.ms`, and JFR CPU/alloc filtered to `ws-sender-*`. Expected saturation signature: sender CPU/alloc and drain latency scale with active delivered frames even if `tick.health.work-time-ms` stays flat.

---

## Opencode Review

I'll systematically audit the baseline methodology. Let me start by reading all the critical source files in parallel.Now let me check the BotFleet/BotClient to understand what rejected bots actually do, and the SimulationEngine to confirm the entity population cap effect.I have all the evidence I need. Here is my verdict and analysis.

---

## VERDICT: RED

**The baseline is misleading.** The "flat tick work-time across 10× connection count" finding is entirely explained by `paralife.admission.cap: 256` — every tier above the cap measures the same workload (~256 grid entities). The metric also excludes all per-session encode/send work. This baseline must not be cited by downstream plans without re-measurement at cap > count.

Top three reasons:

1. **Admission cap flattens tiers** — `admission.cap: 256` ensures the grid never holds more than ~256 entities regardless of `--count`. The 500 and 1000 tiers are measuring ~the same workload.
2. **The gauge excludes per-connection cost** — `paralife.tick.health.work-time-ms` measures only synchronous event dispatch on the single tick VT; it excludes all per-session drain-VT work (encode, send, synchronized(session) write) that actually scales with connection count.
3. **No harness report was committed** — the `peakRegistered`/`currentRegistered` counters from the harness would have shown the 256 ceiling directly. The actuator metric sidecards show tick-ms dropping slightly as tier rises (17.7 → 16.5 → 15.2 ms), which is noise; the point estimate per tier is 6 samples over ~60 ticks — statistically inadequate.

---

### A. What does `paralife.tick.health.work-time-ms` actually measure?

**It measures synchronous tick-dispatch wall-clock on the single tick-engine VT, excluding per-session encode/send.**

In `TickEngine.java:109-119`:
```java
long startTime = System.nanoTime();
eventPublisher.publishEvent(event);         // line 114 — all @Order listeners run here
long elapsed = elapsedNs / 1_000_000;      // line 118
this.lastTickWorkMs = elapsed;             // line 119
```

The `publishEvent` call synchronously dispatches to every `@EventListener` on the calling thread: `SimulationEngine` (@Order 10), `ActionResolver` (@Order 20), `TickBroadcaster` (@Order 50), through to `TickHealthMonitor` (@Order Integer.MAX_VALUE).

**Crucially, `TickBroadcaster.onTick` only calls `outboundSender.offer()`** (`TickBroadcaster.java:222`) — a non-blocking `ArrayBlockingQueue.offer()`. The actual `PerceptionCodec.encode()` + `session.sendMessage()` + `synchronized(session)` work runs on **per-session drain VTs** in `OutboundSender.drainLoop` (`OutboundSender.java:273-312`).

The comment at `TickEngine.java:44-45` explicitly says this includes "end-to-end (listener dispatch + all @Order slots)". This is true for the synchronous dispatch, but the async per-session work is invisible to this gauge.

**Is it per-bot or per-tick?** It's per-tick — a single scalar from `AtomicLong lastTickWorkMs`, set once per tick loop iteration and read by `TickHealthMonitor.onTick` at `@Order(Integer.MAX_VALUE)`. It's the wall-time of one complete tick dispatch, not divided by anything. However, because `lastTickWorkMs` is set AFTER `publishEvent` returns at `TickEngine.java:119`, readers during tick N's dispatch see tick N-1's value (documented at `TickEngine.java:147-150`). The 1-tick lag is immaterial here.

**Yes, excluding per-session VT work explains the flatness** — but only partially. The dominant confound is the admission cap (see C below).

---

### B. What does the harness actually do at `--count 1000`?

**It launches 1000 WebSocket connections, each attempting `r|<species>|...` registration. All 1000 `BotClient` instances are in the fleet list. However, only ~256 acquire grid entities — the rest get `E|429|world-full` and disconnect permanently.**

From `BotFleet.java`:
- `fleet.launch()` spawns 1000 VTs, each calling `bot.connect()` → `bot.awaitRegistered(15000)` (lines 105-134). Returns immediately.
- `fleet.getBots()` returns ALL 1000 instances from the pre-allocated list.

From `BotClient.java` (lines 411-426):
- On receiving `E|429`, the bot calls `disconnect()` — closes WebSocket, stops Jetty client, fires close callbacks. **No retry, no keepalive loop.** The bot is gone.

From `WorldWebSocketHandler.java` (line 479):
- After `AdmissionGate` returns `Reject`, the handler sends the error frame and returns. The session is still open at this point, but the client immediately disconnects upon receiving the frame.

**Each bot generates no perception/action traffic for ticks it never reaches.** Rejected bots never reach the "alive" state, never have an entity, never appear in `BotRegistry.getAllBots()`, and are invisible to `TickBroadcaster` and `SimulationEngine`.

The meta.json claim "Harness log records BotFleet: 1000 bots disconnected" is true — 1000 connections were made and 1000 were eventually torn down. But it says nothing about how many lived on the grid.

---

### C. Admission cap interaction

**Yes, the cap is the dominant effect. This alone explains the flat tick work.**

`application.yml:65`: `paralife.admission.cap: 256`

`AdmissionGate.java:140-150`:
```java
int cap = admissionConfig.cap();   // 256
if (!req.isRespawn()) {
    while (true) {
        int n = reservedSlots.get();
        if (n >= cap) {
            return reject(req, session, 429, RejectionToken.WORLD_FULL);
        }
        if (reservedSlots.compareAndSet(n, n + 1)) { break; }
    }
}
```

The CAS-protected `reservedSlots` counter enforces a hard ceiling. Once 256 bots register, every subsequent bot gets `429 WORLD_FULL` and disconnects.

**At `--count 100`**: All 100 bots admitted (under cap). Grid has ~100 entities.
**At `--count 500`**: ~256 admitted, ~244 rejected and disconnected. Grid has ~256 entities.
**At `--count 1000`**: ~256 admitted, ~744 rejected. Grid has ~256 entities.

The 500 and 1000 tiers are measuring **approximately identical actual workloads**. The slight decrease (17.7 → 16.5 → 15.2 ms) is noise within the natural jitter range (±2 ms per sample) — 6 samples × 5s = 30s over ~60 ticks is too few for statistical confidence on such small deltas. The directionality could even be measurement artifact (the first tier's samples were taken near the end of ramp-up).

**This is not "10× connection-count scale span" at all.** It's 1× → 2.5× → 2.5× actual entity scale.

---

### D. Where does broadcast/codec work actually live?

**Encode + send is on per-session drain VTs. The tick VT only builds `Frame.TickFrame` objects and enqueues them.**

`OutboundSender.drainLoop` (`OutboundSender.java:273-312`):
```java
while (!Thread.currentThread().isInterrupted()) {
    Frame frame = queue.take();
    if (!session.isOpen()) continue;
    String encoded = PerceptionCodec.encode(frame);           // line 286 — encode
    byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8); // line 287
    metrics.recordFrameSize(encodedBytes.length);             // line 288 — metric
    synchronized (session) {                                  // line 289
        session.sendMessage(new TextMessage(encoded));        // line 290 — send
    }
}
```

The tick VT (`TickEngine.tickLoop`) blocks on `eventPublisher.publishEvent(event)` until all synchronous listeners return. Inside `TickBroadcaster.onTick`, per-bot `Frame.TickFrame` construction (including `buildCellEntries`, `buildEffectsForBot`, `buildRosterIfChanged`, etc.) IS on the tick VT and IS included in `lastTickWorkMs`. The `offer()` call is a non-blocking `ArrayBlockingQueue.offer()`.

**What metric WOULD capture per-session saturation?**
- `OutboundSender.queueDepth(sessionId)` — current queue depth per session. No Micrometer gauge is registered for this (it's a diagnostic method only).
- System-level CPU load — would show encode/send VT saturation on carrier threads.
- `paralife.outbound.frame.size.bytes` (DistributionSummary) — registered at `AdmissionMetrics.java:167-169`, called from `drainLoop` line 288. This does measure per-session work indirectly.
- JFR socket I/O events — would show write latency.
- None of these are in the committed sidecars. The sidecars carry only `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout`.

---

### E. JFR + flamegraph capture validity

**The lock flamegraph captured a different run than JFR/cpu/alloc.** The SUMMARY admits:
> "Execution ran captures **sequentially** instead — cpu (60s) during the main 1000-bot run, alloc (60s) immediately after, then a **second 1000-bot LoadHarness run** to capture lock (60s)."

The lock flamegraph captures a different server process with its own grid state (different entity positions, different RNG state), making it incomparable to the cpu/alloc flamegraphs from the first run. The claim that "6 frames indicates no contention" on the lock flamegraph is weak for multiple reasons:

1. **Different run entirely** — grid state differs, so the lock-contention surface may differ.
2. **JVMTI MonitorContendedEnter has known coverage gaps** — it only samples lock acquisitions that block, not contended CAS or `tryLock` paths. `AdmissionGate.reservedSlots` uses CAS (`compareAndSet`) which never enters `MonitorContendedEnter`.
3. **6 frames vs. 209 CPU frames is comparing apples to oranges** — they're different event types, different sampling engines (JFR for cpu/alloc, JVMTI for lock), different runs. The ratio says nothing about lock contention prevalence.
4. **6 samples × 5s = 30s, ~60 ticks per window** — adequate for the tick-work gauge (a stationary signal at steady state), but marginal for flamegraph profiling that depends on event rate. The lock flamegraph captured 6 frame nodes total over 60s, which is extremely sparse. This could mean "no contention" OR "sampler didn't catch it" OR "the lock event was sparse during that exact window."

The claim that D-10 architectural isolation is "proven" by 6 lock frames is incorrect — at best it's "consistent with" the design, not proven by it. A real contention test would instrument the queue offer path and the `synchronized(session)` monitor inside drainLoop.

---

### F. Three-gate result and stale-golden defence

**The argument is structurally sound** — the stale golden is a test-infrastructure issue, not a simulation regression. Evidence:

- GoldenTraceEquivalenceTest.byteIdenticalOutputAcrossTwoRuns: the test runs the simulation twice and compares digests. If the simulation digests are identical across two consecutive runs at c22e487 (which the SUMMARY claims was verified), the issue truly is a stale golden file, not a code regression.
- GoldenTraceWithActionsTest (2/2 green): actions produce correct traces.
- LiveEntityRegistryInvariantTest (6/6 green): grid invariants hold.

**However**, the baseline SHA `c22e487` is now a commit that fails its own tests. Any future "before/after" diff that checks out `c22e487` to re-measure will encounter the same test failure, creating workflow friction. The risk is modest because the simulation is deterministic at that SHA and the delta is in a golden file, not code behavior. Recommendation: downstream plans should cite `f6da129` instead as the baseline anchor (same simulation code, corrected golden). If staying on c22e487, document that `GoldenTraceEquivalenceTest` is expected to fail at that SHA.

---

### G. Assumption A8 falsification

**The falsification reasoning is wrong. Reading `PrintFlagsFinal` without `-XX:+UseZGC` tells you only that ZGC isn't active, not whether it's available or default.**

`java -XX:+PrintFlagsFinal -version | grep UseZGC` returns `UseZGC = false` because nothing requested ZGC. This is the JVM's default GC selection in action — it picked G1 and correctly reports `UseZGC=false`. But this tells you nothing about whether ZGC is the default *collector selection policy* — i.e., whether the JVM would use ZGC if you just said `-XX:+UseZGC` without `-XX:+ZGenerational`.

The correct check for "is generational ZGC the default when ZGC is enabled" is:
```bash
java -XX:+UseZGC -XX:+PrintFlagsFinal -version 2>&1 | grep ZGenerational
```

In Temurin 21.0.6 with `-XX:+UseZGC`, `ZGenerational` should be `true` by default (it became default in JDK 21). The `-XX:+UseZGC` flag is needed to enter the ZGC code path where `ZGenerational` is evaluated.

**The current falsification conclusion ("ZGC is opt-in only") conflates two different things:**
1. "Is ZGC the default GC?" → True, it's not. G1 is the default. `UseZGC=false` without `-XX:+UseZGC`.
2. "Is generational mode the default within ZGC?" → This requires `-XX:+UseZGC` to test. The current test doesn't answer this.

The downstream impact: Plan 4 might mark ZGC as "requires `-XX:+UseZGC -XX:+ZGenerational`" when the correct instruction may just be "requires `-XX:+UseZGC`" (with generational being default-within-ZGC in JDK 21).

---

### H. Overall verdict: RED

The baseline is misleading. The flat tick work-time is entirely explained by `admission.cap: 256` binding the grid population at ~256 regardless of `--count`. The gauge also excludes per-session encode/send work. The "10× scale span" framing is factually wrong — it measures at most 2.5× actual entity scale.

**Specific additional measurements to definitively answer "does the server scale linearly with connection count?"**

1. **`paralife.admission.active.entities` gauge (per-bucket)** — already registered at `AdmissionMetrics.java:240-244`. Must be captured in the metric sidecar alongside `work-time-ms`. At 1000 bots with cap=256, this MUST read ~256 (not ~1000). Expose it in the harvester script.

2. **Re-run with cap > count** — set `paralife.admission.cap: 1500` (or `-Dparalife.admission.cap=1500`) so ALL `--count` bots actually get entities on the grid. Then the tiers (100/500/1000) measure genuinely different workloads. Without this, no baseline is meaningful.

3. **Harness report `peak_registered`/`current_registered`** — the summary itself speculates about 10× connection scale but the harness report (which would disprove it with `peak_registered ~ 256`) was never committed. Must be captured and cited.

4. **Downstream queue-depth gauge** — register a Micrometer gauge on `OutboundSender.queueDepth()` per session (or aggregate max across all sessions). At 1000 actual grid entities with per-tick per-bot frames, the outbound queue depth is the real backpressure signal that tick-work-ms can't see. Sampling signature of saturation: queue depth approaching capacity (128) with rising overflow rates.

5. **JFR socket write duration** — the per-session `sendMessage` blocking time on drain VTs. If carrier threads saturate writing to 1000 sockets, this shows as increased socket I/O time in JFR, even though tick-work-ms stays flat.

---

## Consensus Summary

### Agreed Strengths
- JFR + flamegraph artifacts exist, are anchored to `c22e487`, and fit within stated size budgets — raw evidence is preserved and reproducible.
- The stale-golden defence is *structurally* recognised by all reviewers (deterministic re-run at `c22e487` → fixture issue, not sim regression). Sound shape, weakly evidenced.
- Falsification conclusion for A8 ("ZGC + generational not in effect for this JVM invocation") is directionally correct — Temurin 21.0.6 default is G1.
- Sequential cpu→alloc→lock capture is acknowledged in summary/meta; not hidden.

### Agreed Concerns
- **[CRITICAL] Admission cap = 256 collapses tiers.** `application.yml:65` caps admitted entities at 256; `AdmissionGate` rejects excess with `429 WORLD_FULL`, `BotClient:422` self-disconnects. Tiers 500 and 1000 measure ~256 entities, not 500/1000. Headline "10× connection-count scale" is false; effective scale ≈ 100 → 256 → 256.
- **[CRITICAL] Gauge excludes per-connection cost.** `paralife.tick.health.work-time-ms` is set at `TickEngine.java:119` *after* `publishEvent` returns — captures synchronous listeners only. `TickBroadcaster` enqueues non-blockingly (`outboundSender.offer`); `PerceptionCodec.encode` + `synchronized(session) { sendMessage }` runs on per-session drain VTs in `OutboundSender.drainLoop` and is structurally invisible to this gauge.
- **[CRITICAL] Headline conclusion unsupported.** "Server CPU far from bottleneck at 1000 bots" doesn't follow from a metric that sees ≤256 entities and excludes the per-connection work that should scale. Downstream Plan 6 §4.2 must not cite the 17.7/16.5/15.2 ms trio as-is.
- **[HIGH] Missing observability in sidecar.** No capture of `paralife.admission.active.entities`, `paralife.admission.rejected{reason=world-full}`, `paralife.ws.active.sessions`, `paralife.outbound.frame.size.bytes`, `OutboundSender.queueDepth`, `paralife.backpressure.stalled.sessions`, or harness `peakRegistered`/`currentRegistered`. Any one would have exposed the cap-bound truth.
- **[HIGH] Lock flamegraph is a different run.** Captured during a *second* 1000-bot LoadHarness invocation (different RNG, different grid state). "6 frames ⇒ no contention" is weak: JVMTI `MonitorContendedEnter` misses uncontended/CAS/short waits; frame-node count ≠ contention magnitude; capture window saw an artificially serene workload (cap-bound).
- **[HIGH] Statistical thinness.** 6 actuator scrapes × 5s over ~60 ticks; σ ≈ 1.5 ms / mean 15.2 ms ≈ 10% CV. The 17.7 → 16.5 → 15.2 ms gradient is within noise and indistinguishable across cap-saturated tiers. `paralife.tick.work.ms` `DistributionSummary` (count/total/max/percentiles) would have been a stronger source than the scalar gauge.
- **[MEDIUM] A8 methodology invalid even though conclusion holds.** `java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` (no `-XX:+UseZGC`) only proves G1 is the *default collector*; it does NOT answer "is generational the default *within* ZGC?". Correct test: `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational` (also test with explicit `-XX:+ZGenerational`). Risk: Plan 4 may carry the wrong opt-in flag combination.

### Divergent Views
- **Severity of the stale-golden / `f6da129` issue.** Claude/opencode: low risk (likely fixture-only refresh, structurally sound, document caveat). Codex: not just fixture — `f6da129` modified `SimulationEngine.processDeaths` to sort composites before RNG-consuming panic-zone checks (a determinism/behaviour fix), so c22e487 sim semantics differ from HEAD; baseline anchor risk is higher. **Worth resolving**: pull `git show --stat f6da129` and inspect whether any non-test Java changed — if codex is right, the baseline anchor needs to move to `f6da129` (or be explicitly re-framed as "Phase-19.1-close, pre-determinism-fix").
- **Whether 100-bot tier is "real" too.** All reviewers note 100 is under cap. Claude/codex frame the meaningful comparison as 100 vs ~256 vs ~256 (still ~2.5× span on the low end). Opencode is slightly more dismissive ("1× → 2.5× → 2.5×"). Practical impact identical: re-measurement required.
- **Minimum re-measurement scope.** Claude: pick one of {raise cap, add metric counters, JFR `--threads`, queueDepth gauge} — preferring raising the cap. Gemini: raise cap + add `process.cpu.usage` + `active.entities` + `stalled.sessions` (3 mandatory additions). Codex: matrix of tiers separating *attempted* from *admitted* (100/256/500/1000 with explicit per-scenario cap) + sender-side metrics + JFR by `ws-sender-*`. Opencode: raise cap + commit harness report + queue-depth gauge + JFR socket-write duration. **Consensus minimum**: (a) re-run with `paralife.admission.cap` ≥ tier-count, (b) capture active-entities / active-sessions / rejection counters in the sidecar, (c) surface per-session sender-VT cost (queue depth, frame-size summary count, or JFR thread filter).
