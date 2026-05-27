---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-19T06:11:34Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/TickHealthMonitor.java", "src/main/java/com/paralife/admission/AdmissionGate.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/harness/LoadHarness.java", "src/main/resources/application.yml"]
usage:
  claude: { input: 10, output: 16, cached: 44342, tool_calls: 0, elapsed_s: 192.9 }
  gemini: { input: 134484, output: 1835, cached: 0, tool_calls: 0, elapsed_s: 124.0 }
  codex: { input: 805432, output: 8096, cached: 658304, tool_calls: 30, elapsed_s: 175.8 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 234.9 }
synthesizer: claude
synthesized_at: 2026-05-19T06:11:34Z
---

# Cross-AI Review

## Claude Review

## Verdict: **YELLOW**

The measurements are valid for what they actually measure, but the **headline finding overstates what was proved.** The "flat tick-time across 10× connection-count span" is largely an artifact of three architectural facts the SUMMARY does not surface:

1. **`paralife.admission.cap: 256` (`application.yml:62`) pins entity population identically across the 500 and 1000 tiers** — both runs measured "256 entities tick", not "500 / 1000 entities tick".
2. **`paralife.tick.health.work-time-ms` does NOT measure per-connection encode + socket write cost.** Those happen in per-session virtual threads (`OutboundSender.drainLoop`, `OutboundSender.java:236–266`), outside the tick-thread wall clock.
3. **The metric is a one-tick `VALUE` gauge sampled 6× at 5s.** With a 500ms tick interval that's ~60 ticks per window observed indirectly via 6 sub-sampled values — fine for a coarse mean, blind to p95/p99 tails.

Downstream plans can still cite the baseline, but **the framing in `20-01b-SUMMARY.md` must be rewritten** before it becomes the citation source for Plans 04 / 05 / 06. Specifically the SUMMARY's "server CPU is far from the bottleneck at 1000 bots" sentence is unsupported by what was actually captured.

---

### A. What does `paralife.tick.health.work-time-ms` measure?

`TickEngine.tickLoop()` (`TickEngine.java:96–107`):

```java
long startTime = System.nanoTime();
...
eventPublisher.publishEvent(event);
long elapsedNs = System.nanoTime() - startTime;
...
this.lastTickWorkMs = elapsed;
```

Spring `publishEvent` dispatches `@EventListener` chain synchronously on the calling thread. So this gauge captures **wall-clock time on the tick VT** spent running, in `@Order` sequence:

`ResumeTokenRegistry(1)` → `SimulationEngine(10)` → `EnvironmentEngine(14)` → `CompositeEnergyDistributor(15)` → `ActionResolver(20)` → `EnvPostActionReconciler(25)` → `TickBroadcaster(50)` → `WebSocketKeepaliveService(200)` → `TickHealthMonitor(MAX_VALUE)`.

What's IN: simulation logic, env CA, action resolution, **per-bot `buildTickFrame`** including 5×5 vision walk + RLE + roster (`TickBroadcaster.java:188–251`).

What's OUT:
- `PerceptionCodec.encode(frame)` — runs in the per-session VT `OutboundSender.drainLoop` at `OutboundSender.java:240`.
- `session.sendMessage(...)` — same place, `OutboundSender.java:248`.
- `metrics.recordFrameSize(...)` — same place, `OutboundSender.java:242`.

`TickBroadcaster` only calls `outboundSender.offer(sessionId, frame)` (`TickBroadcaster.java:226`) which is a non-blocking `ArrayBlockingQueue.offer`. **Encode + socket I/O are invisible to this gauge.**

The gauge is also lag-1 (TickEngine sets `lastTickWorkMs` AFTER `publishEvent` returns; `TickHealthMonitor @Order(MAX_VALUE)` reads it during the same dispatch and gets tick N-1's value — class Javadoc at `TickEngine.java:159–166` confirms this). Not a methodology defect at the 30s window scale but worth noting.

**Bottom line:** the gauge is "tick-thread CPU time including frame BUILD, excluding frame ENCODE/SEND."

---

### B. What does `--count 1000` actually do?

`LoadHarness.runInternal()` (`LoadHarness.java:175`) calls `fleet.launch(serverUri, count, identity, rampUp, speciesMix, factory)`. `BotFleet` source not provided in review packet — **cannot verify from code alone** whether all 1000 sessions actually open, stay open through the 200s window, retry after rejection, or back off after `E|429|world-full`.

What CAN be said from the code given:

- `LoadHarness.validateAndDefault` (`LoadHarness.java:152`) only WARNs above `count > 5000`, no hard cap.
- `--ramp-up rate:50` paces creation at 50/s, so all 1000 connect attempts complete within ~20s of the 200s window.
- The harness counts `connect_failures_total` separately (`LoadHarness.java:430`) — if reported as zero in the run report it would confirm 1000 actual WS connections were made.

**Critical missing baseline data:** the SUMMARY does NOT cite the harness JSON report's `connect_failures_total`, `e408_reconnect_required`, or `current_registered` final values. Without those, "1000 bots ran for 200s" is unverified.

---

### C. Admission cap interaction — the dominant effect

`paralife.admission.cap: 256` (`application.yml:62`). `AdmissionGate.evaluate()` rejects with `WORLD_FULL` when `reservedSlots >= cap` (`AdmissionGate.java:127–140`).

Concretely at each tier:

| Tier | `--count` | Entities admitted | Rejected |
|---|---|---|---|
| 100 | 100 | **100** (under cap) | 0 |
| 500 | 500 | **≤256** (capped) | ~244 |
| 1000 | 1000 | **≤256** (capped) | ~744 |

`livingEntityCount()` on the grid drives all tick-listener iteration (`SimulationEngine`, `EnvironmentEngine`, `TickBroadcaster` via `botRegistry.getAllBots()` at `TickBroadcaster.java:198`). At 500 and 1000 connection counts, **the tick path processes ~256 entities each**, identical workload.

Compare the measured means:

| Tier | mean | implied entity count |
|---|---|---|
| 100 | 17.7 ms | 100 entities |
| 500 | 16.5 ms | ~256 entities |
| 1000 | 15.2 ms | ~256 entities |

That the 256-entity tiers measure ~equal to each other is **expected by the architecture**, not evidence of "headroom". The slight downward trend (17.7 → 16.5 → 15.2) is within typical JIT-warming noise / GC variance over a 6-sample window. The 100-bot tier being SLOWER than 500/1000 is consistent with cold-JIT on the first run; that alone is a tell that the sample noise floor is in the same magnitude as any actual cross-tier effect.

**The author's claim of "10× connection-count scale span" is true at the WebSocket layer but false at the entity layer.** The two are decoupled by the admission cap.

The SUMMARY should also have cited `paralife.admission.rejected{reason=world-full}` Counter (`AdmissionMetrics.java:31`, incremented at `AdmissionGate.java:225`) for each tier as supporting evidence. Its absence is a real measurement gap.

---

### D. Where does broadcast / codec work actually live?

Tick thread → `OutboundSender.offer(sessionId, frame)` — non-blocking enqueue into per-session `ArrayBlockingQueue<Frame>` (`OutboundSender.java:198–215`).

Per-session drain VT (`OutboundSender.drainLoop`, `OutboundSender.java:226–270`) does the actual work:

```java
String encoded = PerceptionCodec.encode(frame);          // CPU + alloc
byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8);
metrics.recordFrameSize(encodedBytes.length);            // captured
synchronized (session) {
    session.sendMessage(new TextMessage(encoded));        // socket I/O
}
```

What WOULD capture saturation here, none of which the baseline collected:

| Signal | Where | Captured? |
|---|---|---|
| Per-tick total bytes encoded | `paralife.outbound.frame.size.bytes` DistributionSummary, `AdmissionMetrics.java:218` (count + sum) | **No** |
| Per-session queue depth | `OutboundSender.queueDepth(sessionId)` `OutboundSender.java:218` | **No** |
| Detach timeouts | `paralife.outbound.detach.timeout` Counter `AdmissionMetrics.java:74` | **Yes — 0/0/0** |
| Carrier-pool saturation | JFR `jdk.ThreadCPULoad` per VT carrier | Maybe in JFR — not extracted to SUMMARY |
| Codec CPU% | `cpu-1000bots-baseline-c22e487.html` flamegraph | Captured but not surfaced as a number |

`detach.timeout == 0` confirms the drain VT exited cleanly when `detachSession` was called within 100ms. It does **NOT** prove the drain VT kept up with the queue during steady-state — it only fires on detach. The queue could be perpetually 80% full without ever triggering this counter.

There's also the tick-thread DistributionSummary `paralife.tick.work.ms` registered with p50/p95/p99 (`TickEngine.java:60–64`). This is a far better signal than the one-tick VALUE gauge — and it's exposed via the same actuator endpoint. **The SUMMARY captured the wrong meter.**

---

### E. JFR + flamegraph capture validity

Three issues, in increasing severity:

**E1. async-profiler concurrent attach.** SUMMARY claims `asprof` 4.4 rejects concurrent attach. This is **plausible** (the docs reference issue #436) but **inconsistent with `tools/async-profiler-bootstrap.md`** which says "All three captures can run concurrently — they do not contend on event channels at this scale". One of these documents is wrong; the bootstrap doc should be updated to match observed reality.

**E2. Lock flamegraph from a separate run.** The lock flamegraph was captured during a **second** 1000-bot run (SUMMARY §Deviations, point 1). JIT state, VT scheduling, RNG draw order — all differ between runs even with the same seed (`paralife.simulation.spawn.seed=20251205` controls placement, not VT scheduling). The CPU and lock flamegraphs are **not comparable workloads in the strict sense**. They're "two runs at the same configured load" not "one workload viewed two ways".

**E3. "6 frames vs 209 frames = no contention" claim is weak.** Per the architecture (`OutboundSender.java:97–101` Javadoc), `synchronized(session)` is held by:
- Drain VT (per session, one writer)
- `WebSocketKeepaliveService.onTick` @Order(200) (tick thread, all sessions)
- OOB stall/error frames (`WorldWebSocketHandler.sendOutOfBand`)

The keepalive runs every `paralife.websocket.keepalive-ticks: 30` ticks (15s). For most ticks there's no contender for the drain VT's session monitor — by design contention is rare. So a thin lock flamegraph **is consistent with the design but doesn't independently confirm "VT-per-session isolation working"** — it could equally mean "JVMTI MonitorContendedEnter missed sub-microsecond contention bursts at the 15s keepalive boundary." JVMTI lock instrumentation has known sampling limitations at short hold-times.

The structural claim D-10 makes (per-session isolation) is sound; the lock flamegraph is weak supporting evidence for it.

**E4. Statistical adequacy.** 30s window × 2 ticks/s = 60 ticks. Gauge sampled 6× = 6 of those 60. Mean over 6 samples of a value with single-digit-ms σ has SEM ≈ 0.5–1.2 ms. So the 17.7/16.5/15.2 comparison sits well inside ±2 ms noise band. Calling 15.2 < 17.7 a "trend" is reading signal into noise. **Run `paralife.tick.work.ms` p99 instead** — same source, far more sensitive to tail-latency saturation.

---

### F. Three-gate result and the stale-golden defence

The author's defence is **structurally sound IF and ONLY IF** `f6da129` touches only `src/test/resources/golden-trace-phase19.json` and no `src/main/**` files. The SUMMARY claims this but provides no evidence (no `git show f6da129 --stat`, no file list, no commit-message excerpt).

**The defence's internal logic:**
1. Two consecutive simulations at c22e487 produce identical digests (determinism ✓)
2. Those digests don't match the golden file pinned at c22e487 (golden stale)
3. f6da129 updated the golden; at HEAD the same test passes (golden caught up)

If 1+3 both hold then the simulation behavior at c22e487 is the same as at HEAD for those two trace sessions — only the test fixture was wrong.

**Risk if the defence is wrong:** if a real simulation regression landed before c22e487 and f6da129 papered over it by accepting the wrong output, then the JFR/flamegraph baselines are anchored to a degraded reference, and Phase 21 before/after diffs would be measuring "degraded → tuned" not "correct → tuned". That's not a recoverable error after the fact.

**Cheap remediation:** the SUMMARY must include `git show f6da129 --stat` output (or equivalent) proving the diff touches ONLY the golden JSON. One command, two minutes, closes the question permanently. As shipped, the defence is plausible but unverified.

**Re-anchoring alternative:** if the diff inspection shows f6da129 touches main code, re-baseline against f6da129's parent or HEAD instead of c22e487. D-19's reproducibility goal is served by *any* fixed SHA; nothing requires c22e487 specifically.

---

### G. A8 falsification

The conclusion ("Temurin 21.0.6 default is G1; ZGC requires opt-in") is **correct**. The methodology is **muddled** in a way that could mislead a future operator reading the meta.json.

`java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` returning `UseZGC = false` only proves "ZGC wasn't requested at startup, so the flag's final value is false." It does NOT directly answer "is ZGC the default GC?".

**Correct tests:**

| Question | Correct command |
|---|---|
| "What's the default GC in Temurin 21.0.6?" | `java -XX:+PrintFlagsFinal -version \| grep -E ' (UseG1GC\|UseParallelGC\|UseZGC\|UseSerialGC) '` — look for which is `:= true` from ergonomics |
| "Within ZGC, is generational mode default in JDK 21?" | `java -XX:+UseZGC -XX:+PrintFlagsFinal -version \| grep ZGenerational` — in JDK 21 this is `false` (opt-in), JDK 23+ flipped it to default-true |
| "What do I need to enable Generational ZGC on Temurin 21.0.6?" | `-XX:+UseZGC -XX:+ZGenerational` (both flags, JDK 21 era) |

The Plan 4 recipe `-XX:+UseZGC -XX:+ZGenerational` is correct. Just fix the meta.json wording so the next agent reading it doesn't repeat the muddled methodology.

---

### H. Bigger picture + recommended additional measurements

**Edits the SUMMARY needs before Plans 04/05/06 cite it:**

1. Rewrite "Headline finding" paragraph to read: *"Tick-thread work-time is flat across tiers because (a) the admission cap pins entity population at ≤256 in the 500/1000 tiers, and (b) the gauge excludes encode+send work that lives in per-session VTs. The baseline establishes that tick-thread CPU at 256 entities is ~16 ms (3% of 500 ms budget). It does NOT establish per-connection scaling headroom."*
2. Add a §Caveats block citing the cap interaction and the metric's exclusion of VT-side work.
3. Add `f6da129 --stat` output (or equivalent) to close the stale-golden defence.
4. Fix the A8 methodology wording per §G above.
5. Cite the actually-measured admission counters and outbound throughput, or explicitly mark them as not captured.

**Four additional measurements that would close the actual scaling question:**

| # | Metric | Sampling shape | Expected saturation signature |
|---|---|---|---|
| 1 | `paralife.tick.work.ms` p50 / p95 / p99 (already registered, `TickEngine.java:60–64`) | `curl /actuator/metrics/paralife.tick.work.ms` every 5s × 60s — same shape as current sidecar, different meter | p99 climbing while p50 flat = tail-latency saturation invisible to current gauge |
| 2 | `paralife.outbound.frame.size.bytes` count + sum delta | Snapshot at t=30s and t=180s, divide delta-sum by delta-count and delta-ticks | Per-tick total encode bytes; should scale linearly with active entity count. Reveals whether VT pool is keeping up with the per-tick frame production rate |
| 3 | Process CPU% per JVM | `pidstat -p $SERVER_PID 5 36` sidecar, OR JFR `jdk.CPULoad` event aggregated from existing 1000-bot JFR | At 100 vs 1000 *admitted* sessions, process CPU % gap reveals per-connection cost outside the tick thread. Cap workaround: raise `paralife.admission.cap` to 1000 for one comparison run |
| 4 | Admission outcome distribution | `curl /actuator/metrics/paralife.admission.rejected?tag=reason:world-full` start vs end | Confirms how much of `--count` actually became live entities. Critical for interpreting any other metric |

**The minimum re-measurement that would actually answer "does the server scale linearly with connection count?"**

Raise `paralife.admission.cap` to 1000 (or higher) via `-Dparalife.admission.cap=1500` on the c22e487 launch, re-run the 1000-bot tier, and re-capture all three sidecars + JFRs. With cap removed as the confounder, the tick-work metric would actually reflect 1000-entity processing cost. Sidecar `cap_during_run: 1500` field documents the deviation from production defaults. This single re-run (45 min) converts the YELLOW into a defensible GREEN.

Memory note: this is methodology audit feedback; saving as feedback memory for future reviews of similar baseline-capture work.

---

## Gemini Review

**Verdict:** **RED**
The baseline methodology is fundamentally flawed, and the headline result is entirely misleading. First, the server is configured with a hard admission cap (`paralife.admission.cap: 256`), meaning the 500-bot and 1000-bot runs are both only simulating 256 entities; the flat tick time reflects identical workloads, not linear scalability. Second, the `paralife.tick.health.work-time-ms` metric isolates the tick-thread's wall-clock time, which explicitly excludes the expensive string encoding and network I/O that is delegated to per-session virtual threads. Third, taking 6 point-in-time instantaneous gauge samples across a 30-second window misses 90% of the 500ms ticks, statistically ignoring any transient latency spikes.

### A. What does `paralife.tick.health.work-time-ms` actually measure?
- **Is it measuring wall-clock time or including broadcast?** It measures only the wall-clock time of the single tick thread executing the simulation pipeline. In `TickEngine.java`, `lastTickWorkMs` is recorded immediately after `eventPublisher.publishEvent(event)` completes. 
- **Does it exclude per-session VT work?** Yes. In `TickBroadcaster.java`, the listener merely calls `buildTickFrame(...)` and enqueues it: `outboundSender.offer(bot.sessionId(), frame)`. The actual CPU-bound string encoding (`PerceptionCodec.encode(frame)`) and WebSocket network I/O (`session.sendMessage(...)`) are executed in `OutboundSender.drainLoop(session, queue)`, which runs on isolated virtual threads (`Thread.ofVirtual()...`).
- **Does that explain the flatness?** Yes. The tick thread is only spending time creating Java objects (`Frame`) and pushing references to an `ArrayBlockingQueue`. The true costs of scaling (encoding and network latency) are pushed off the tick thread entirely and are not captured by this gauge.
- **Is it normalised?** No, it is not per-bot or normalised. It is the absolute elapsed time for the tick thread to complete all `@EventListener` routines.

### B. What does the harness actually do at `--count 1000`?
- **Does it create 1000 active sessions?** The `LoadHarness` actively attempts to establish 1000 concurrent connections via `fleet.launch(...)`. 
- **Are they passive?** No, each connected `BotClient` is driven by a `HeuristicBrain` that generates traffic. However, the vast majority of these bots are prevented from interacting with the server (see Section C).

### C. Admission cap interaction
- **Are the other ~744 sessions doing work?** No. `application.yml` explicitly limits the world population with `paralife.admission.cap: 256`. 
- In `AdmissionGate.java`, Guard 5 enforces this global limit: `if (n >= cap) { return reject(req, session, 429, RejectionToken.WORLD_FULL); }`. At `--count 1000`, the first 256 sessions are admitted, while the remaining 744 receive an immediate `429` error and are dropped. They never enter the `BotRegistry` and do not receive tick frames.
- **Is that the dominant effect here?** **YES**. The 500-bot and 1000-bot tiers are both only simulating exactly 256 bots. The tick time is flat because the server's workload hits a ceiling at 256 connections.

### D. Where does broadcast / codec work actually live?
- **Where is the cost paid?** It is paid entirely in the per-session virtual threads. `OutboundSender.drainLoop` consumes the queue, calls `PerceptionCodec.encode(frame)`, and then holds `synchronized (session)` to invoke `session.sendMessage(...)`.
- **What metric would capture saturation?** Saturation on these VTs would manifest as queue backpressure. The correct metric to observe is `paralife.backpressure.stalled.sessions` (incremented in `AdmissionMetrics.incStalledBucket`) or observing `OutboundSender.queueDepth`. 
- The sampled sidecar (`metrics-1000bots-baseline-c22e487.json`) only checks `work-time-ms` and `detach.timeout`, completely blinding the operators to VT encoding saturation or dropped frames.

### E. JFR + flamegraph capture validity
- **Is the lock flamegraph capturing the same workload?** No. Because `async-profiler 4.4` forced sequential capture, the `lock` flamegraph comes from a completely separate run. 
- **Is "6 frames" evidence of no contention?** It is evidence of no contention *for 256 bots*. Because only 256 VTs were active, and each VT acquires a lock exclusively on its own dedicated `WebSocketSession` instance, cross-session contention is structurally avoided. `MonitorContendedEnter` only triggers on actual contention.
- **Is the sampling adequate?** No. The tick interval is 500ms, meaning 60 ticks occur in 30 seconds. A script that runs `curl` every 5 seconds captures exactly 6 point-in-time snapshots, missing 90% of the data points and totally ignoring transient spikes. 

### F. The three-gate result and the stale-golden defence
- **Is the argument structurally sound?** No, the stale-golden defence is a face-saving framing that hides actual mutated behaviour. A failing golden trace test means the simulation's deterministic output at `c22e487` diverged from the expected behavior pinned by the project. 
- **Does it introduce risk?** Yes. By baselining against a known-failing commit, downstream tuning might inadvertently fix, mask, or compound the regression that caused the golden divergence in the first place. You cannot confidently measure performance optimizations against a broken simulation state.

### G. Assumption A8 falsification
- **Is reading `PrintFlagsFinal` without `-XX:+UseZGC` valid?** No. Running `java -XX:+PrintFlagsFinal -version` without any GC flags will simply output the JVM's default GC configuration, which in Temurin 21 is G1 (`UseG1GC = true`, `UseZGC = false`). 
- While it validly falsifies the specific claim that ZGC is "default-on", it is highly misleading for future plans. In Java 21, Generational ZGC is indeed the default mode *when ZGC is activated*. 
- **The right test:** To check if ZGC defaults to generational mode when enabled, the author must explicitly run: `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`.

### H. The bigger picture
- **Verdict:** **RED**. The baseline is entirely misleading and unusable for downstream tuning.

**Minimum re-measurement required:**
You must raise the admission cap to actually subject the server to the load. Modify `application.yml` (or pass `-Dparalife.admission.cap=1000`) before running the 500 and 1000 bot tiers.

**Specific additional measurements required:**
1. **`paralife.admission.active.entities`**: You must sample this gauge to empirically prove that the server actually admitted and is simulating the expected number of bots (100, 500, 1000).
2. **`paralife.backpressure.stalled.sessions`**: Sample this gauge to measure outbound queue saturation. If VTs fall behind on string encoding and network I/O, this metric will climb.
3. **`paralife.tick.work.ms` (DistributionSummary)**: Instead of the instantaneous gauge, query the Micrometer actuator endpoint for this `DistributionSummary`. Capture the `MAX` and `95th/99th percentiles` of tick execution time to stop missing latency spikes between your 5-second `curl` intervals.
4. **Server CPU Load (`process.cpu.usage`)**: Capture overall CPU utilization from the actuator to observe the true cost of 1000 virtual threads performing simultaneous `Base64` encoding and network I/O.

---

## Codex Review

**Verdict: RED.** The baseline is valid only as “tick-loop work under the configured 256-entity admission cap,” not as evidence that the server scales flat across 100→1000 active clients. Top reasons: `paralife.tick.health.work-time-ms` excludes per-session encode/send work; `--count 500/1000` is capped by `paralife.admission.cap: 256`, with surplus bots rejected/disconnected; and the summary overclaims from weak evidence, especially lock-flamegraph sample count and the stale-golden defense.

**A. Metric Semantics**

`paralife.tick.health.work-time-ms` is a scalar gauge, not per-bot and not normalized. It is registered without tags from an `AtomicLong` in [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:65), [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:87), [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:153).

It samples `TickEngine.getLastTickWorkMs()` once per tick in [TickHealthMonitor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:59), [TickHealthMonitor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:62). That value is elapsed wall time around `eventPublisher.publishEvent(event)` in [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:109), [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:114), [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:116), then stored as `lastTickWorkMs` in [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:119).

So it includes synchronous tick listeners, including `TickBroadcaster.onTick` at `@Order(50)` [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:189). It includes per-active-bot frame construction and `outboundSender.offer(...)` [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:207), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:217), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:222).

It does **not** include per-session encode/send wall time. `TickBroadcaster` explicitly defers encode and frame-size recording to `OutboundSender.drainLoop` [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:218). `OutboundSender` spawns one virtual thread per session [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:132), and encode/send happens there [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:282), [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:286), [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:290). That absolutely can explain flatness for connection-count scaling.

**B. Harness Behavior at `--count 1000`**

The harness attempts to launch 1000 bot clients. `LoadHarness` calls `fleet.launch(...)` then waits `durationSeconds` [LoadHarness.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/harness/LoadHarness.java:268), [LoadHarness.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/harness/LoadHarness.java:277). `rate:50` sleeps between launches [RampUpSpec.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/RampUpSpec.java:42), [RampUpSpec.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/RampUpSpec.java:45).

But “launched” is not “1000 registered active entities.” A bot sends `r|...` after connect [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:194), and only counts as registered after receiving `S|...` [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:362), [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:375). On server `429`, the bot disconnects [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:422), [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:425).

Active registered bots do generate perception/action traffic, but not every bot necessarily sends an action every tick. `onTick` increments perception count, asks `brain.decide`, and only sends if non-null [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:383), [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:400). Passive/bonded-secondary/composite non-locomotor roles return null [HeuristicBrain.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:83), [HeuristicBrain.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:93), [HeuristicBrain.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:100).

**C. Admission Cap Interaction**

This is the dominant issue. The production config sets `paralife.admission.cap: 256` [application.yml](/home/mark/kramtime/paralife/src/main/resources/application.yml:64), [application.yml](/home/mark/kramtime/paralife/src/main/resources/application.yml:65). `AdmissionGate` rejects fresh registrations once `reservedSlots >= cap` [AdmissionGate.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:139), [AdmissionGate.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:143), [AdmissionGate.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:145).

At `--count 1000`, roughly 744 bots are not active simulation participants. They may briefly connect and be rejected, but they are not in `BotRegistry.getAllBots()` and therefore do not participate in per-tick broadcast construction [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:201), [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:207).

A 500/1000 tier can therefore be indistinguishable from `--count 256` for tick work. The only surprising part is 100 bots not being visibly lower than 256; that needs active-entity counts, tick distribution, and noise controls before drawing conclusions.

**D. Broadcast / Codec Work Location**

Per-connection encode/send cost is mostly paid outside the tick thread. Tick thread: build `Frame.TickFrame`, enqueue via `queue.offer` [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:217), [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:225). Per-session VT: `PerceptionCodec.encode`, `recordFrameSize`, synchronized `sendMessage` [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:286), [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:288), [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:289).

Metrics that would capture that layer: outbound queue depth distribution, offer failures/overflow count, encode/send wall-time timer in `drainLoop`, `paralife.outbound.frame.size.bytes` throughput/count, attached sender count, active entity count, rejected-by-reason count, and process CPU/JFR thread samples attributed to `ws-sender-*`. The sampled sidecars captured only tick gauge and detach timeout, so they do not answer VT saturation.

**E. JFR + Flamegraph Validity**

The plan requested concurrent async-profiler captures [20-01b-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md:145), [20-01b-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md:150). The summary says execution was sequential, and lock used a second 1000-bot run [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:131). The meta file also says lock was a follow-up run independent of the main JFR [jfr-1000bots-baseline-c22e487.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json:12). So no, the lock flamegraph is not guaranteed to capture the exact same workload instance as JFR/cpu/alloc.

“6 frame nodes” is weak evidence. It is consistent with low contention, but also with low event volume, wrong event coverage, missed steady state, or a workload capped at 256 active entities. The summary’s claim that it “confirms” lock contention is not a hot path [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:124) should be softened.

Six samples at 5s over 30s covers about 60 ticks at 500ms [application.yml](/home/mark/kramtime/paralife/src/main/resources/application.yml:33). But each actuator sample is a last-value gauge, not an aggregate over the 10 intervening ticks. So the metric sidecar observes 6 tick values, not 60 values. That is not statistically adequate for a baseline claim.

**F. Three-Gate / Stale Golden**

The argument is only partially sound. Deterministic repeatability at `c22e487` would show the simulation is stable, but it does not prove the golden is merely stale or that the failing SHA is a safe baseline. The summary admits `c22e487` fails its own gate [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:105), [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:114).

Also, `f6da129` was not just a golden update. It changed simulation/runtime code, including deterministic ordering in `SimulationEngine`, plus codec and websocket behavior. The golden file changed only `trace-sess-9` and `trace-sess-21`, but the same commit contains code changes. That weakens the “test fixture only” framing unless the actual digest logs from `c22e487` are committed as evidence.

Anchoring downstream before/after diffs to a SHA that fails its own three-gate is risky. It may be acceptable for CPU profiling if documented as “profiling-only baseline under known failing golden,” but not as a clean reference.

**G. A8 ZGC Falsification**

`PrintFlagsFinal` without `-XX:+UseZGC` is valid to answer “is ZGC the default collector for this JVM invocation?” If `UseZGC=false`, the default run is not ZGC. The actual baseline also used `-XX:+UseG1GC` [jfr-1000bots-baseline-c22e487.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json:7), so the captures are definitely G1.

But that test does not answer “if ZGC is requested, is it generational by default?” The right checks are:

```bash
java -XX:+PrintFlagsFinal -version | grep -iE 'UseG1GC|UseZGC|ZGenerational'
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'
java -XX:+UseZGC -XX:+ZGenerational -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'
```

So “default GC is G1” is supported; “Generational ZGC requires both flags” needs the second and third commands, not only the first.

**H. Bigger Picture**

**RED.** The flat tick result is real for the narrow sampled metric, but the headline conclusion is misleading. Required framing edits before citation:

- Replace “10× connection-count scale span” with “100 bots versus capped 256 active entities, with surplus rejected.”
- Replace “server CPU is far from bottleneck at 1000 bots” with “tick-thread dispatch work is below budget under the configured admission cap.”
- State that encode/send VT work was not measured by the actuator sidecars.
- Remove “detach timeout confirms model holds”; zero detach timeout only says no drain-VT join timeout occurred.

Minimum re-measurement to answer “10× more clients”:

1. Capture `paralife.admission.active.entities{source=harness,harness=...}` and `paralife.admission.rejected{reason=world-full,...}` every 5s. Saturation signature: active plateaus at cap while rejects climb.

2. Add/sample outbound queue depth: p50/p95/max per session, plus offer-failure/overflow count. Saturation signature: p95/max queue depth grows, overflow fires, stalled sessions rise.

3. Add timers around `OutboundSender.drainLoop`: encode ms and send ms per frame, tagged or summarized globally. Saturation signature: encode/send p95 rises with active sessions even when tick-work stays flat.

4. Run tiers with either `admission.cap >= count` or separate “connection-only” and “active-entity” scenarios: 100/256/500/1000 active admitted entities, sampled for full tick distribution (`paralife.tick.work.ms` count/mean/p95/p99), process CPU, and active connection count.

---

## Opencode Review

I've read through all provided source files. Let me now compile the full methodology review.

---

## Verdict: **YELLOW**

**Top three reasons:**

1. **Admission cap (256) is the dominant confound.** At `--count 500` and `--count 1000`, the entity grid population is bounded at ~256 regardless. `buildTickFrame()` work per tick is O(entities), not O(connections). The 500 and 1000 tiers are therefore measuring the ~same workload and cannot test scaling.

2. **The metric excludes the per-connection scaling cost.** `paralife.tick.health.work-time-ms` captures only the tick thread's synchronous dispatch wall time (`publishEvent` → return). Per-session encode + send + queue drain all run on VTs outside the tick thread. A genuine 10× connection-count increase could saturate outbound queues, drain VTs, or carrier threads while this gauge stays flat.

3. **The flat trend is expected under the cap, not evidence of headroom.** The "headline finding" frames the result as discovery, but it's predicted by the architecture given the cap. The 100-bot tier (19, 16, 16, 16, 19, 20 ms) and 1000-bot tier (14, 18, 14, 14, 15, 16 ms) are statistically indistinguishable — which is what you'd expect when both runs operate on the same ~256-entity population.

---

## A. What does `paralife.tick.health.work-time-ms` actually measure?

The chain is:

1. **`TickEngine.tickLoop()`** (`TickEngine.java:96-115`): calls `eventPublisher.publishEvent(event)` and records elapsed nanos from just before `publishEvent` to just after it returns. This wall-clock span includes **all synchronous `@EventListener` handlers** dispatched during that `publishEvent` call.

2. **`TickHealthMonitor.onTick()`** (`TickHealthMonitor.java:56-81`) at `@Order(Integer.MAX_VALUE)` reads `tickEngine.getLastTickWorkMs()` — but because `lastTickWorkMs` is set AFTER `publishEvent` returns (line 110 of TickEngine), this listener reads tick **N-1**'s value during tick N's dispatch. It then calls `admissionMetrics.setLastTickWorkMs(sample)` which writes the `AtomicLong` backing the Micrometer gauge.

3. **What's included**: All `@Order` listeners that run synchronously within `publishEvent`:
   - `ResumeTokenRegistry` @Order(1) — grace-expiry sweep
   - `SimulationEngine` @Order(10) — combat, decay, death, nutrients
   - `EnvironmentEngine` @Order(14) — toxin/mutagen/lightning/compost
   - `ActionResolver` @Order(20) — resolve moves/consume/reproduce
   - `TickBroadcaster` @Order(50) — **`buildTickFrame()` for every registered bot** (this is where per-entity codec construction costs land)
   - `TickHealthMonitor` @Order(INTEGER.MAX_VALUE) — the sampler itself

4. **What's NOT included** (all on per-session VTs):
   - `PerceptionCodec.encode(frame)` — the actual text serialization (`OutboundSender.java:275`)
   - `session.sendMessage(...)` — the WebSocket write (`OutboundSender.java:279`)
   - `metrics.recordFrameSize(...)` — frame-size metric recording (`OutboundSender.java:278`)
   - Queue drain/backpressure behavior

5. **Is it per-bot or normalized?** No — it's a single scalar gauge updated once per tick. The `AtomicLong lastTickWorkMs` is set once per tick loop iteration (line 110 of TickEngine). It's the total wall time of one complete dispatch cycle, not a per-bot or per-tick average.

There's also a separate `DistributionSummary` (`paralife.tick.work.ms` at `TickEngine.java:50`) that records the same elapsed time with percentile histograms — this was not sampled in the metric sidecars.

**Answer**: The gauge measures the synchronous tick-dispatch wall time. It includes frame *construction* (building CellEntry lists, kind-code lookups, envState computations) for all registered bots, but excludes per-session encode/send/queue work. This exclusion directly explains why the gauge could stay flat even if outbound saturation were occurring — the tick thread would return quickly while per-session VTs pile up work.

---

## B. What does the harness do at `--count 1000 --duration 200 --ramp-up rate:50`?

From `LoadHarness.java`:

1. `validateAndDefault()` (`LoadHarness.java:121`) checks `count >= 1`, warns if > 5000 (D-02 ceiling), but does NOT validate against the server's admission cap.

2. `runInternal()` (`LoadHarness.java:179`) calls `fleet.launch(serverUri, count, identity, rampUp, speciesMix, factory)`. `BotFleet.launch()` creates `count` `BotClient` instances, each connecting via WebSocket to `ws://localhost:8080/ws/world`.

3. Each `BotClient` sends an `r|<species>|<token>` registration frame on connect, then enters the perception→action loop.

4. `rampUp = rate:50` — 50 bots per second connect rate. At 1000 count, that's ~20 seconds to ramp all connections.

5. `duration = 200` — waits 200 seconds after ramp (or until signal).

**Key question: do all 1000 bots stay connected and generate traffic?**

The harness creates 1000 `BotClient` instances that all attempt to connect. However, the server's `AdmissionGate` at `AdmissionGate.java:145-153` rejects connections once `reservedSlots >= cap (256)` with `429 world-full`. Whether the harness bots retry after rejection depends on `BotClient`'s reconnect logic (source not provided, but typical bot implementations will retry `r|` frames or reconnect).

Even if bots retry, the admission gate rejects at the `r|` frame evaluation — the entity is never placed on the grid, never registered in `BotRegistry`, and therefore never appears in `botRegistry.getAllBots()`. The rejected sessions don't consume tick compute in `buildTickFrame()`.

**Answer**: The harness creates 1000 BotClient instances, but only the first ~256 (cap) that win the admission race actually get entities on the grid and participate in the tick loop. The remaining ~744 connections either receive 429 rejections and sit idle/retrying, or connect and get rejected. They do NOT generate perception/action traffic in the tick path.

---

## C. Admission cap interaction — is this the dominant effect?

`application.yml` line (approximately line 115):
```yaml
admission:
  cap: 256
```

`AdmissionGate.evaluate()` (`AdmissionGate.java:145-153`):
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

This is a CAS-guarded global cap. Once 256 entities occupy the grid, every subsequent `r|` frame from any source receives `E|429|world-full`. The rejected sessions:
- Never get `incActiveBucket()` called
- Never appear in `BotRegistry.getAllBots()`
- Never have `buildTickFrame()` called for them
- May or may not retry (depends on BotClient reconnect behavior)

**Impact on the three measurement tiers:**

| Tier | Harness count | Grid entities (capped) | Tick work driver |
|------|---------------|------------------------|-------------------|
| 100  | ≤100          | ~100                   | ~100 buildTickFrame calls |
| 500  | 500 attempted | ~256                   | ~256 buildTickFrame calls |
| 1000 | 1000 attempted| ~256                   | ~256 buildTickFrame calls |

The 500-bot and 1000-bot tiers are measuring the **same entity population** (~256). The 100-bot tier measures roughly 40% of that. The flatness between 500 and 1000 is trivially explained by the cap, and the ~15% drop from 100 to 500/1000 could reflect simulation-stable state (entity death/replacement settling) rather than scaling behavior.

**Answer**: Yes, the admission cap is the dominant effect. The 500-bot and 1000-bot tiers are observationally indistinguishable because they both measure the ~same 256-entity workload. A tier where `--count > cap` will always be indistinguishable from `--count = cap`. The summary's "10× connection-count scale span" framing is incorrect — it's at most a 2.5× entity-count span (100 vs 256), and the 500/1000 tiers add no new information.

---

## D. Where does broadcast/codec work actually live?

The split is:

**Tick thread** (inside `publishEvent`, captured by the gauge):
- `TickBroadcaster.onTick()` → `buildTickFrame(bot, tickNumber)` — for each registered bot:
  - `buildCellEntries(pos, radius)` — vision cell iteration, RLE rock-run assembly
  - `buildEffectsForBot(bot, occupant)` — buff/infection/FLEEING lookups
  - `buildEventsForBot(bot, occupant, tickId, tier)` — alarm drain, event assembly
  - `buildPool(tier, occupant)` — composite pool snapshot
  - `buildRosterIfChanged(bot, tier, occupant, pos)` — roster hash computation
- `outboundSender.offer(bot.sessionId(), frame)` — non-blocking queue insert (returns immediately, no wait)

**Per-session VT** (outside tick thread, NOT captured by gauge):
- `OutboundSender.drainLoop()` (`OutboundSender.java:261-301`):
  ```java
  String encoded = PerceptionCodec.encode(frame);       // line 275 - codec serialization
  byte[] encodedBytes = encoded.getBytes(StandardCharsets.UTF_8);
  metrics.recordFrameSize(encodedBytes.length);          // line 278
  synchronized (session) {
      session.sendMessage(new TextMessage(encoded));     // line 279 - WebSocket write
  }
  ```

**What metric would capture per-session saturation?**

None of these are in the sampled sidecars, but they exist or could be added:

1. **`OutboundSender.queueDepth(sessionId)`** — at `OutboundSender.java:222-225`, returns current queue depth per session. Not exposed as a Micrometer gauge. A max-queue-depth-per-tick gauge or distribution summary would show whether queues are filling up.

2. **`paralife.backpressure.stalled.sessions`** — already registered as a per-bucket gauge (`AdmissionMetrics.java:57`). Tracked but not sampled in these sidecars. A stalled-session count rising with connection count would be the clearest saturation signal.

3. **`paralife.outbound.frame.size.bytes`** — DistributionSummary registered at `AdmissionMetrics.java:141`. Not sampled.

4. **Encode-VT wall time** — not instrumented. Could be added as a per-session timing metric inside `drainLoop()`.

5. **JFR thread dump / VT monitor events** — would show drain VTs in `BLOCKED`, `RUNNABLE`, or `WAITING` states. The JFR profiles exist but the summary doesn't report drain-VT thread state distributions.

6. **CPU load / carrier thread utilization** — `jdk.virtualThreadScheduler.parallelism=8` means only 8 carrier threads. If all drain VTs are busy encoding/sending, contention on those 8 carriers would manifest. JFR thread dumps or OS CPU monitoring would show this.

**Answer**: Per-connection encode + send cost is fully on per-session VTs, outside the tick thread, and therefore invisible to `work-time-ms`. The metric sidecars capture none of the metrics that would detect outbound saturation. Queue depth, stalled-session count, frame-size distribution, and carrier-thread utilization are all absent from the sampled data.

---

## E. JFR + flamegraph capture validity

**Concurrent attach failure**: The plan (`20-01b-PLAN.md` §how-to-verify step 4) specifies concurrent async-profiler captures with `& + wait`. The summary (`20-01b-SUMMARY.md` Capture-Process Deviations §1) documents that `asprof` 4.4 rejects this with "[ERROR] Profiler already started." Execution ran captures sequentially: `cpu` (60s) during the main 1000-bot run, `alloc` (60s) immediately after, then a **second 1000-bot LoadHarness run** to capture `lock` (60s).

**The lock flamegraph is from a DIFFERENT run** than the cpu/alloc flamegraphs and the JFR. This matters because:
- Simulation seed determines entity placement and action outcomes
- Different runs produce different entity positions, combat outcomes, and action patterns
- Lock contention patterns depend on which entities are at which positions doing what
- You cannot directly compare a lock flamegraph from run #2 against a CPU flamegraph from run #1

**Is "6 frames" evidence of "no contention"?**

The lock flamegraph uses `asprof -e lock`, which on async-profiler 4.x captures via JVMTI `MonitorContendedEnter` events — only threads that actually BLOCK waiting for a monitor. It does NOT capture:
- Uncontended `synchronized` acquisitions (fast path, biased locking)
- `ReentrantReadWriteLock` contention in the read/write path
- `AtomicInteger.compareAndSet` spin loops (no monitor involved)
- `ConcurrentHashMap` internal locking

The architecture genuinely has minimal monitor contention:
- The tick thread is single-threaded
- Each drain VT only acquires `synchronized(session)` on its own session
- `WorldGrid` uses a `ReentrantReadWriteLock` (not a monitor)

So "6 frames" is consistent with the architecture, but:
1. The separate-run issue means it's not a controlled comparison against the CPU profile
2. JVMTI `MonitorContendedEnter` is inherently selective — it only shows contended Java monitor entries
3. The lack of `ReentrantReadWriteLock` contention sampling means the main shared lock isn't measured
4. Genuinely low contention is a possibility, and the architecture suggests it

**Statistical adequacy**: 6 samples × 5s = 30s window, 500ms tick interval. That's ~60 ticks observed. For a gauge that updates once per tick, 6 independent samples is minimal but not unreasonable for a steady-state baseline. The issue isn't the sample count — it's that the metric being sampled doesn't capture the scaling dimension of interest.

**Answer**: The lock flamegraph is from a different run, breaking the controlled-comparison assumption. The JVMTI `MonitorContendedEnter` approach captures only contended Java monitor entry, missing `ReentrantReadWriteLock` and uncontended paths. While 6 frames is consistent with the architecture's low contention, it's not definitive proof — it's equally consistent with "the event type has coverage gaps" or "the second run had different entity positions."

---

## F. The three-gate result and stale-golden defence

**Is the argument structurally sound?**

The summary claims:
> "the simulation produces the **correct** digests (verified deterministic across two consecutive runs at c22e487) — but the pinned golden in the c22e487 tree has not yet caught up."

There's a logical gap here. "Deterministic across two consecutive runs at c22e487" proves the simulation is self-consistent at c22e487, but does NOT prove that c22e487's digests match HEAD's post-f6da129 digests. The actual question is:

1. **Did the code change between c22e487 and f6da129?** The commit message says `fix(19.1): pass-1 multi-review follow-up sweep`. This implies code changes, not just golden-file updates. If code changed, the "stale golden" framing is wrong — the golden was correct for the earlier code, and the code change altered simulation behavior.

2. **Only 2 of 26 sessions fail** (`trace-sess-9`, `trace-sess-21`). If this were purely a golden-file staleness issue, you'd expect either zero sessions or all sessions to be affected (if a code-level determinism regression). Two specific sessions failing suggests either:
   - A targeted code fix that affects specific entity types or positions (plausible for a "multi-review follow-up sweep")
   - A non-determinism issue that happens to manifest in those two sessions

3. **The claim that "baseline measurements are unaffected"** assumes the code change between c22e487 and f6da129 did NOT affect tick performance, only test golden files. If the fix touched `SimulationEngine`, `ActionResolver`, `OutboundSender`, `WorldWebSocketHandler.markStalled`, or any other tick-path code, then performance could differ.

**Risk to downstream before/after diffs**: Yes, anchoring to c22e487 introduces risk if c22e487 → f6da129 contains meaningful code changes. Downstream plans will diff "now" against c22e487 and attribute all differences to their own changes, but some could be from the c22e487→f6da129 delta.

**Answer**: The argument has a logical gap — "self-consistent at c22e487" doesn't prove "identical behavior to post-f6da129 HEAD." The 2/26 session specificity suggests a targeted code change, not just a golden-file update. Binding baselines to c22e487 introduces risk that before/after diffs conflate Phase 20 changes with the c22e487→f6da129 intercept. A safer approach: re-baseline at or after f6da129 where the three-gate goes 9/9.

---

## G. Assumption A8 falsification

**Is `PrintFlagsFinal` without `-XX:+UseZGC` a valid way to check defaults?**

Yes, IF you interpret the `=` vs `:=` annotation correctly:
- `UseZGC = false` means the flag has its **default value** (not overridden)
- `UseZGC := false` means the flag was **explicitly set** to false

The meta.json records `UseZGC = false` and `ZGenerational = false` (with `=`, implying defaults). This IS a valid check: the JVM's built-in defaults for these flags are `false`.

**Is A8 actually falsified?**

The assumption was: "Generational ZGC default-on in Temurin 21.0.6." This could mean two things:
1. "ZGC is the default GC, and it uses generational mode" — **Falsified**: G1 is the default GC in JDK 21
2. "When ZGC is enabled, generational mode is the default" — **True** in JDK 21+, but the flag `ZGenerational` default is `false` because it only matters when `UseZGC=true`

The way the flag check was done is reasonable for interpretation #1 (the one that matters for Plan 4). The conclusion "ZGC is opt-in only" is correct. The methodology could be improved by also checking:
- `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational` — this would show `ZGenerational := true` confirming generational is default-when-ZGC-is-on

**Right test to distinguish "default GC" from "default-when-requested":**
```bash
# What's the default GC?
java -XX:+PrintFlagsFinal -version 2>&1 | grep -E 'UseG1GC|UseZGC|UseParallelGC|UseSerialGC' | grep '= '
# Output should show UseG1GC = true (JDK 21 default)

# When ZGC is requested, is generational the default mode?
java -XX:+UseZGC -XX:+PrintFlagsFinal -version 2>&1 | grep ZGenerational
# Should show ZGenerational := true
```

**Answer**: The methodology is valid for the question "is ZGC the default GC?" — `PrintFlagsFinal` with `=` annotation correctly shows built-in defaults. The conclusion is correct: ZGC is not the default GC in JDK 21. However, the right test to check "generational is default **when** ZGC is enabled" requires passing `-XX:+UseZGC` to see the conditional default. The downstream risk is minor since Plan 4 already treats ZGC as opt-in.

---

## H. Overall verdict: **YELLOW**

**Measurements are valid for what they measure, but the framing in `20-01b-SUMMARY.md` overstates what the result proves.**

Specific framing edits needed before downstream plans cite this:

1. **Replace "10× connection-count scale span"** with "2.5× entity-count span (100→256 entities, bounded by admission cap)." The 500 and 1000 tiers are measuring the same entity population.

2. **Replace "server CPU is far from the bottleneck at 1000 bots"** with "tick thread synchronous dispatch time is sub-5% of the 500ms budget at the ~256-entity cap, with per-session encode/send work not measured by this gauge."

3. **Add explicit caveat**: "This gauge does NOT capture per-session codec encoding, WebSocket send, or outbound queue saturation. A full end-to-end scaling assessment requires queue-depth, stalled-session, and carrier-thread utilization metrics at scale."

4. **Note the cap confound**: "`--count 500` and `--count 1000` both operate on the same ~256-entity grid due to `admission.cap: 256`. The metric sidecars for these tiers are therefore not measuring different workloads and cannot distinguish 500 from 1000."

**Minimum re-measurements to answer "does the server scale linearly with connection count?":**

1. **Queue depth distribution per tick** (`OutboundSender.queueDepth()`). Sample `max(queueDepth)` across all sessions at the end of `TickBroadcaster.onTick()` — this captures whether per-session queues are building up faster than drain VTs can emit. Expected saturation signature: max queue depth rising linearly with entity count, then plateauing/spiking as queues fill.

2. **Drain-VT wall time histogram** — instrument `drainLoop()` to record wall time from `queue.take()` to `sendMessage` completion. This captures encode cost + network write latency per session. Expected saturation signature: P99 drain time increasing, then spiking as network buffers fill.

3. **`paralife.backpressure.stalled.sessions` gauge** sampled at each tier. Current sidecars only sample `work-time-ms` and `detach.timeout`. Stalled-session count rising above zero is the definitive signal that outbound backpressure is engaging.

4. **Repeat the experiment with `admission.cap` raised** to 1000 (or at least above `--count`). This is the only way to actually test whether the tick thread scales when faced with more entities on the grid. Without this, the 500/1000 tiers are wasted effort — they measure noise around the same cap-bounded population.

---

## Consensus Summary

### Agreed Strengths
- Determinism check (two consecutive c22e487 runs producing identical digests) is real evidence simulation is self-consistent at baseline SHA.
- A8 conclusion correct: G1 is default GC in Temurin 21.0.6; ZGC is opt-in. Plan 4 recipe (`-XX:+UseZGC -XX:+ZGenerational`) is correct.
- Per-session VT isolation architecture (D-10) is structurally sound; low lock contention is consistent with design.
- Sidecar/JFR/flamegraph artifacts captured and committed at fixed SHA satisfy reproducibility intent of D-19.
- Lock-event coverage limitation (JVMTI `MonitorContendedEnter` only) acknowledged consistently across reviewers.

### Agreed Concerns
- **CRITICAL — Admission cap (`paralife.admission.cap: 256`) is the dominant confound.** 500-bot and 1000-bot tiers both measure ~256 admitted entities; remaining sessions receive `E|429|world-full` and never enter `BotRegistry`. The "10× connection-count scale span" headline is false at the entity layer where tick work actually lives. (All 4 reviewers; codex/gemini = RED, claude/opencode = YELLOW.)
- **CRITICAL — `paralife.tick.health.work-time-ms` excludes per-connection scaling cost.** Gauge captures only synchronous tick-thread dispatch wall time around `eventPublisher.publishEvent(event)` (`TickEngine.java:96-115`). Per-session `PerceptionCodec.encode`, `session.sendMessage`, queue drain, and `metrics.recordFrameSize` all run on per-session VTs in `OutboundSender.drainLoop` — invisible to this gauge. (All 4 reviewers.)
- **HIGH — Saturation-detecting metrics not captured.** Missing from sidecars: `paralife.admission.active.entities`, `paralife.admission.rejected{reason=world-full}`, `paralife.backpressure.stalled.sessions`, per-session `OutboundSender.queueDepth`, `paralife.outbound.frame.size.bytes`, `paralife.tick.work.ms` DistributionSummary (p95/p99) — the last is already registered at `TickEngine.java:60-64` but unsampled. (All 4 reviewers.)
- **HIGH — Sampling shape inadequate.** 6 instantaneous gauge samples at 5s over 30s covers ~60 ticks but observes only 6 values; blind to tail latency. p95/p99 from registered DistributionSummary would resolve this from the same actuator. (All 4 reviewers.)
- **HIGH — Lock flamegraph from a separate run.** Async-profiler 4.4 sequential constraint forced a second 1000-bot run for lock capture; not strictly comparable to CPU/alloc/JFR workload instance. "6 frames = no contention" claim should be softened. (All 4 reviewers.)
- **MEDIUM — Stale-golden defence under-evidenced.** Self-consistency at c22e487 does not prove c22e487 simulation behavior matches post-f6da129 HEAD. f6da129 commit description ("multi-review follow-up sweep") and 2/26 session-specific failure pattern suggest code changes, not pure fixture update. SUMMARY must include `git show f6da129 --stat` to close the question, or re-anchor baseline to f6da129/HEAD. (claude, codex, opencode.)
- **MEDIUM — Headline framing overstates result.** "Server CPU far from bottleneck at 1000 bots" / "10× scale span" must be rewritten before Plans 04/05/06 cite as evidence. Correct framing: "tick-thread dispatch work at ~256 capped entities is ~16ms (3% of 500ms budget); per-connection encode/send not measured." (All 4 reviewers.)
- **LOW — A8 methodology wording muddled.** `PrintFlagsFinal` without `-XX:+UseZGC` answers "is ZGC default GC?" but not "is generational default when ZGC requested?" The correct second test is `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`. Conclusion stands; meta.json wording needs fix to prevent future misreading. (All 4 reviewers.)

### Divergent Views
- **Overall verdict:** codex and gemini = **RED** (baseline misleading/unusable as-is); claude and opencode = **YELLOW** (valid for narrow scope, framing edits sufficient before citation). Disagreement is severity, not substance — all four agree the same edits + cap-raised re-run are required.
- **Minimum remediation path:** claude and opencode propose a single re-run with `paralife.admission.cap` raised to ≥1000 + SUMMARY rewrite to convert to GREEN. codex and gemini imply broader re-instrumentation (queue-depth distribution, drain-VT timer instrumentation in `drainLoop`) before any defensible baseline. Worth deciding: cheap re-run with existing metrics, or instrument first then re-run.
- **Lock flamegraph evidentiary weight:** claude treats "6 frames" as weak supporting evidence consistent with design but not independently confirmatory (JVMTI sampling gaps at sub-µs hold times). opencode/codex treat it as consistent-with-architecture but compromised by separate-run issue. gemini treats it as structurally trivial given per-session monitor isolation — contention impossible by construction at 256 sessions. No reviewer treats it as strong evidence.
- **`tools/async-profiler-bootstrap.md` consistency:** only claude flags that this doc claims concurrent capture works, contradicting the SUMMARY's observed sequential-only behavior — bootstrap doc needs update.
- **f6da129 anchoring risk magnitude:** codex/opencode treat the c22e487→f6da129 delta as a real risk requiring re-anchor or commit-stat evidence. gemini frames it as "baselining against a known-failing commit" — stronger language. claude offers cheapest remediation (`git show f6da129 --stat`); if main code untouched, defence holds.
