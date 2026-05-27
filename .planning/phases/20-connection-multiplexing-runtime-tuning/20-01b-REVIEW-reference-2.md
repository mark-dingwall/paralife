---
task: generic
mode: reference
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-20T19:51:02Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/TickHealthMonitor.java", "src/main/java/com/paralife/admission/AdmissionGate.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/harness/LoadHarness.java", "src/main/resources/application.yml"]
usage:
  claude: { input: 82, output: 206, cached: 44342, tool_calls: 14, elapsed_s: 255.9 }
  gemini: { input: 146825, output: 2787, cached: 0, tool_calls: 12, elapsed_s: 100.3 }
  codex: { input: 1799114, output: 11775, cached: 1642880, tool_calls: 61, elapsed_s: 258.7 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 15, elapsed_s: 435.8 }
synthesizer: claude
synthesized_at: 2026-05-20T19:51:02Z
---

# Cross-AI Review

## Claude Review

# Review: 20-01b baseline capture — methodology + result validity

## Verdict: **RED**

Top three reasons:

1. **Admission cap of 256 (application.yml:65) flattens the 500-bot and 1000-bot tiers to identical grid load**. AdmissionGate.java:144-145 rejects every fresh connection past 256 with `WORLD_FULL`. 500-tier and 1000-tier therefore both run with ≈256 admitted entities. "10× scale" not measured — 256× ↔ 256× compared. Flat result is tautological.
2. **`paralife.tick.health.work-time-ms` excludes per-session encode + `sendMessage`** — those run on per-session VTs (OutboundSender.java:286-290), not the tick thread. The metric the author sampled cannot, by construction, surface saturation in the work that actually scales with connection count.
3. **The metrics that WOULD show per-connection saturation were not sampled** — `paralife.backpressure.stalled.sessions`, `OutboundSender.queueDepth`, `paralife.admission.rejected` counter delta, `paralife.tick.work.ms` DistributionSummary p95/p99 (TickEngine.java:44). The chosen gauge + counter pair is blind to the dimension the headline claim asserts.

Baseline is misleading as written. Methodology has fixable defects but the framing in 20-01b-SUMMARY.md must not propagate downstream.

---

## A. What does `paralife.tick.health.work-time-ms` actually measure?

Tick gauge bound to `AtomicLong lastTickWorkMs` (AdmissionMetrics.java:65, 87, 153-155). Writer: `AdmissionMetrics.setLastTickWorkMs` (line 407), called by `TickHealthMonitor.onTick` (TickHealthMonitor.java:62-63) which reads `tickEngine.getLastTickWorkMs()`.

`TickEngine.lastTickWorkMs` set at TickEngine.java:116-119:

```
long elapsedNs = System.nanoTime() - startTime;
tickWork.record(elapsedNs / 1_000_000.0);
long elapsed = elapsedNs / 1_000_000;
this.lastTickWorkMs = elapsed;  // written after publishEvent; readers see tick N-1
```

Window measured: `startTime` → `System.nanoTime()` after `eventPublisher.publishEvent(event)` returns. Spring synchronous dispatch — includes every `@EventListener` on the tick thread.

**What's IN the gauge**:
- SimulationEngine `@Order(10)`, EnvironmentEngine `@Order(14)`, CompositeEnergyDistributor `@Order(15)`, ActionResolver `@Order(20)`, EnvPostActionReconciler `@Order(25)`.
- `TickBroadcaster.onTick` `@Order(50)` — the loop at TickBroadcaster.java:207-233 calls `buildTickFrame(bot, ...)` synchronously for every admitted bot. Vision scan, RLE, roster, effects, all computed in tick thread.
- WebSocketKeepaliveService `@Order(200)` (probably calls `sendMessage` synchronously for PINGs but only every `keepalive-ticks=30`).

**What's OUT of the gauge** (the load-bearing exclusion):
- `PerceptionCodec.encode(frame)` runs in `OutboundSender.drainLoop` (OutboundSender.java:286) on a per-session VT.
- `session.sendMessage(...)` runs in the per-session VT inside `synchronized(session)` (OutboundSender.java:289-290).
- `metrics.recordFrameSize(...)` likewise per-session VT (OutboundSender.java:288).

So per-connection encode + transport cost is *structurally invisible* to this gauge. Per the CLAUDE.md "Outbound concurrency" stanza this is the deliberate design — `queue.size()` is supposed to be the backpressure signal. The baseline did not sample queue size.

Gauge is also N-1 lagged for same-tick readers (acknowledged in TickEngine.java:151 javadoc). Not load-bearing for the audit; just noted.

**Conclusion**: gauge measures tick-thread wall-clock = engine pipeline + `buildTickFrame` per bot. Encode + send cost — the work whose linear-in-connections behaviour the headline claim implicitly denies — is in a different thread group and never sampled.

---

## B. What does the harness actually do at `--count 1000`?

LoadHarness drives Picocli → `fleet.launch(serverUri, count, identity, rampUp, speciesMix, factory)` (LoadHarness.java:268). Validation: `--count >= 1` (line 142), warn-only above 5000 (line 148-150). No server-side admission awareness.

Each bot connects to `ws://localhost:8080/ws/world` and sends `r|<species>`. Server admission (AdmissionGate.evaluate) decides Allow/Reject/Rebind. Fresh harness bots carry no resume token (LoadHarness/BotClient/BotFactory don't generate one for first-attempt registration in the code I read; cannot verify exhaustively without reading BotClient itself).

LoadHarness exposes counters via `BotFleet.peakRegistered()` and `currentRegistered()` in `computeCountersSnapshot` (LoadHarness.java:428-430). These are written to the per-harness JSON report (e.g. `harness-baseline-c22e487-report.json`) but **the 20-01b-SUMMARY.md does not cite them**. That report would prove or refute "1000 active entities on the grid" directly. It was captured (Pass-2 inputs imply it) but ignored in favour of A6's prose claim "BotFleet: 1000 bots disconnected". "Disconnected" is not "was on the grid driving tick work".

Bots that hit `WORLD_FULL` receive `E|429|world-full`. Whether they then idle the socket open or close-and-retry depends on `BotClient` behaviour (not read in this audit) — that's a real gap to verify.

---

## C. Admission cap interaction — THE DOMINANT EFFECT

application.yml:64-65:
```yaml
admission:
  cap: 256
```

AdmissionGate.java:140-150 — Guard 5 enforces it with a CAS loop on `reservedSlots`:
```
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

Effective load per tier:

| `--count` | Cap | Admitted (driving tick work) | Rejected `WORLD_FULL` |
|---|---|---|---|
| 100 | 256 | 100 | 0 |
| 500 | 256 | 256 | ~244 |
| 1000 | 256 | 256 | ~744 |

**500-tier and 1000-tier present IDENTICAL load to the tick pipeline**. The "10× connection-count scale span" only spans 100→256 (a 2.56× admitted-entity scale, not 10×). The flatness between 500 and 1000 is a measurement of one configuration twice, not scaling.

Rejected sessions do consume some work (Jetty accept, frame parse, AdmissionGate.evaluate, error frame send, close) — but that's bounded one-shot work, not steady-state per-tick load. After the rejection burst at ramp-up, they drop out of the tick pipeline entirely.

Plus: TickBroadcaster.java:201 iterates `botRegistry.getAllBots()`. Rejected sessions never enter BotRegistry. Stalled sessions are skipped (line 211-214). Per-tick `buildTickFrame` cost therefore scales with admitted-and-Alive entities, capped at 256.

**This single fact explains the entire flat result**. The author's "headline finding" is, restated honestly: "256 entities on a 256×256 grid produces ~16 ms tick work, with ≈±3 ms variance, regardless of how many extra connection attempts get rejected at the door."

---

## D. Where does broadcast / codec work actually live?

Trace:

| Step | Code | Thread |
|---|---|---|
| `buildTickFrame(bot, ...)` (vision scan, RLE, roster, effects, events) | TickBroadcaster.java:217 / 349-409 | **Tick thread** |
| `outboundSender.offer(sessionId, frame)` — non-blocking enqueue | TickBroadcaster.java:222 / OutboundSender.java:225-243 | Tick thread |
| `queue.take()` | OutboundSender.java:283 | Per-session VT |
| `PerceptionCodec.encode(frame)` | OutboundSender.java:286 | Per-session VT |
| `metrics.recordFrameSize(encodedBytes.length)` | OutboundSender.java:288 | Per-session VT |
| `synchronized(session) { session.sendMessage(...) }` | OutboundSender.java:289-290 | Per-session VT |

So per-connection cost is split: **frame build in tick thread, encode + send in per-session VT**. `buildTickFrame` work scales with admitted entities (capped at 256), not connections. Encode+send scales with admitted entities too (one frame per admitted bot per tick) — but the cost lands on N independent VTs, not the tick thread.

Metrics that *would* capture per-connection saturation:

| Metric | Where | What it shows |
|---|---|---|
| `paralife.backpressure.stalled.sessions` (gauge, per bucket) | AdmissionMetrics.java:66, 289-292 | Sessions in STALLED grace = queues that overflowed |
| `paralife.backpressure.stalled.total` (counter) | AdmissionMetrics.java:70, 161-163 | Cumulative stall transitions |
| `paralife.backpressure.terminal.dropouts` (counter) | AdmissionMetrics.java:69, 164-166 | Stalled sessions whose grace expired |
| `paralife.outbound.frame.size.bytes` (DistributionSummary) | AdmissionMetrics.java:67, 167-170 | Encoded frame size distribution |
| `paralife.outbound.detach.timeout` (counter) | AdmissionMetrics.java:74 | Drain VT stuck in `sendMessage` past 100 ms — direct slow-client signal |
| `paralife.tick.work.ms` (DistributionSummary with p50/p95/p99) | TickEngine.java:44-48 | Tail latency of tick |
| `OutboundSender.queueDepth(sessionId)` | OutboundSender.java:249-252 | Per-session real-time backpressure — NOT exposed as a Micrometer meter; would need a registration |

Sidecar captured only `paralife.tick.health.work-time-ms` + `paralife.outbound.detach.timeout`. Of those, only the second is even per-session-relevant, and it only fires on the *worst* failure mode (drain VT stuck > 100 ms during detach). Zero detach timeouts proves "no session got so backlogged that its drain VT was wedged when we tore the session down". It does NOT prove "queues stayed empty" or "encode was keeping up".

Also missing: JFR has `jdk.ThreadCPULoad` events. Filtering the captured JFR by thread name `ws-sender-*` would surface per-session encode/send CPU share *post-hoc*. The author has the file (`jfr-1000bots-baseline-c22e487.jfr`) but the SUMMARY does not report this slice. Easy follow-up.

---

## E. JFR + flamegraph capture validity

### Concurrent attach → sequential capture

Deviation #1 in SUMMARY is honest: async-profiler 4.4 rejects concurrent attach. CPU + alloc captured during the *first* 1000-bot run; lock captured during a *second* 1000-bot run.

Implication: cpu/alloc and lock flamegraphs observe statistically-similar but not identical workloads. For a deterministic single-threaded simulation with the same seed (`spawn.seed=20251205`), the first ~60 s of each run should be very close. But the lock flamegraph in particular captures whatever transient contention happens to occur during *its* 60 s window. Comparing "lock has 6 frames, cpu has 209" without same-window co-capture is suggestive, not conclusive.

### "6 frames" as proof of "no contention" — overclaim

async-profiler's `-e lock` event uses JVMTI `MonitorContendedEnter`/`MonitorContendedEntered`. Coverage:

- **Captured**: contended `synchronized` blocks / methods.
- **NOT captured**: `java.util.concurrent.locks.*` (ReentrantLock, ReentrantReadWriteLock, StampedLock). These do NOT generate JVMTI MonitorContended events — they're built on `LockSupport.park/unpark`.

This matters here. CLAUDE.md states: *"`ReentrantReadWriteLock` on `WorldGrid` — read lock for snapshots, write lock for mutations."* If WorldGrid read contention is the actual hot spot, **the `lock` flamegraph cannot see it**. 6 frames is consistent with "no `synchronized` contention" — which says nothing about the RWL.

Other locks in the codebase that are invisible to JVMTI lock event:
- `ArrayBlockingQueue.take/offer` internal `ReentrantLock` — wait-blocked drain VTs would show as `Thread.State.WAITING`, not as contention
- Spring event publisher internal locks (depending on impl)

Visible to JVMTI lock event:
- `synchronized(session)` blocks in OutboundSender.drainLoop:289, WorldWebSocketHandler.sendOutOfBand, WebSocketKeepaliveService.onTick, sendFrame back-compat (per CLAUDE.md "Synchronized-session-monitor contract").

So the 6-frame finding can credibly claim: "the per-session-monitor pattern doesn't produce visible cross-session contention" — which is what D-10 architecturally promised, and good news. It CANNOT credibly claim: "no contention anywhere". The SUMMARY says *"Lock contention is minimal at 1000-bot load"* and *"Plan 5 Task 5.0 triage should not target lock-contention as a hot path"* — that second sentence overgeneralises from a partial-coverage instrument.

If a Plan 5 lock-contention question matters, the right tool is `jfr print --events jdk.JavaMonitorWait,jdk.JavaMonitorEnter,jdk.ThreadPark` and looking for `ThreadPark` stacks with large duration — that surfaces the `j.u.c.locks` blind spot.

### Sample window adequacy

6 samples × 5 s = 30 s window. Tick interval 500 ms ⇒ ~60 ticks observed per window. Gauge returns last-completed-tick scalar, so 6 samples = 6 independent ticks observed (not all 60, because of polling interval > tick interval is false here — actually polling 5 s vs tick 0.5 s means each sample reads the latest of ~10 ticks; effective sample is one of those 10, semi-aliased).

Spread in raw values per tier (work_time_ms):
- 100: 19, 16, 16, 16, 19, 20 → mean 17.7, range 4
- 500: 17, 16, 14, 17, 20, 15 → mean 16.5, range 6
- 1000: 14, 18, 14, 14, 15, 16 → mean 15.2, range 4

Pooled SD ~ 2 ms; SEM per tier ~ 0.8 ms. Tier means differ by ~1.2 ms (100→500) and ~1.3 ms (500→1000). At n=6 these are not significant; reading "tick work-time is essentially flat" out of these numbers is unsupported precision either way — could be flat, could be a small trend buried in 6-sample noise. A DistributionSummary p95/p99 over the same window via `paralife.tick.work.ms` would have given orders-of-magnitude more signal; it was already registered (TickEngine.java:44-48), just unsampled.

Tail latency missing entirely. If 1 tick in 200 takes 100 ms, the mean of 6 single-tick samples misses it almost certainly. p99 from the DistributionSummary catches it.

---

## F. Stale-golden defence

Argument structure in SUMMARY §A9:

1. `GoldenTraceEquivalenceTest.byteIdenticalOutputAcrossTwoRuns` fails at c22e487 on sess-9, sess-21.
2. Two consecutive runs at c22e487 produce identical actual digests → simulation IS deterministic at c22e487.
3. Same test passes at HEAD because f6da129 updated the golden file.
4. Therefore the c22e487 golden is stale; baseline measurements unaffected.

(2) is necessary but not sufficient. Determinism between two runs at c22e487 does not establish that c22e487's behaviour matches HEAD's behaviour. The argument requires a fourth claim:

4'. f6da129 only updated `golden-trace-phase19.json` and did NOT change any code path that affects sess-9/21's trace.

The SUMMARY does not show this. I did not inspect f6da129 directly — quick git verification needed:

```
git show --stat f6da129
git show f6da129 -- src/main/  # any production code changed?
```

If f6da129 touches *only* `src/test/resources/golden-trace-phase19.json`, the stale-golden story holds and the baseline is safe. If it touches `src/main/java/com/paralife/{engine,websocket,codec}/...`, then c22e487 has a behaviour bug that was fixed in f6da129, and the JFRs / metric sidecars captured behaviour *of the buggy version*. Downstream "before/after" deltas would compare buggy-baseline vs fixed-tuned, and any improvement could be the fix, not the tuning.

The commit subject `fix(19.1): pass-1 multi-review follow-up sweep` is ambiguous. "fix" word + "sweep" suggests it may not be golden-only. **The reviewer should `git show f6da129` to settle this before the baseline is honoured.**

Anchoring a baseline to a SHA that fails its own three-gate is, regardless of cause, a smell. At minimum the SUMMARY should explicitly enumerate what f6da129 changed (paths, not just commit subject) so a future reader can verify the optimistic story without re-inspecting git.

---

## G. A8 falsification — methodology error

A8 claim: *"Generational ZGC default-on in Temurin 21.0.6"*.

Falsification command per meta.json:
```
java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'
# returns UseZGC = false and ZGenerational = false
```

**This does not show what the SUMMARY says it shows.** `PrintFlagsFinal -version` prints the *resolved configuration of THIS invocation*. Since the command does not pass `-XX:+UseZGC`, the JVM resolves to its actual default GC (G1 in Temurin 21.0.6), and `UseZGC = false` simply reflects "you didn't ask for ZGC, you didn't get ZGC".

It does NOT prove "ZGC is not the default GC" — even though that conclusion *happens to be correct* for Temurin 21.0.6.

Two-part question phrased correctly:

**Q1: Is ZGC the default GC?** Right check:
```
java -XX:+PrintFlagsFinal -version | grep -E '\sUseG1GC\s|\sUseZGC\s|\sUseParallelGC\s|\sUseSerialGC\s'
```
Look for which flag has value `true`. In Temurin 21.0.6 expected: `UseG1GC = true`. THAT is the falsification of "default-on".

Or simpler: `java -Xlog:gc*=info -version` and read which GC initialises.

**Q2: When ZGC is enabled, is the generational variant default?** This is what "Generational ZGC" really refers to. Right check:
```
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -i ZGenerational
```
In JDK 21.0.x, `ZGenerational` defaults to **false** — must be explicitly enabled with `-XX:+ZGenerational`. Becomes default-true only in JDK 23+.

So A8 has two failure modes (default GC not ZGC; ZGenerational not default when ZGC enabled), and the author's command shows neither correctly. The CONCLUSION ("ZGC is opt-in only") happens to be right, but the EVIDENCE doesn't establish it. Plan 4 should rewrite the GC analysis section with the right checks captured verbatim.

---

## H. Overall verdict + required additional measurements

### Verdict: RED

The flat-tick claim isn't wrong because the server is broken — it's wrong because the experiment didn't actually scale connection-driven tick load. The admission cap clamped 500 and 1000 tiers to identical effective load; the chosen gauge by construction excludes the work that DOES scale per-connection (encode + send on per-session VTs); and the metrics that *would* surface per-connection saturation were not sampled.

The baseline JFRs and flamegraphs are not worthless — they capture useful steady-state behaviour at 256 admitted entities on a fast workstation. But the SUMMARY's headline finding *as written* will mislead Plan 4/5/6 into thinking server CPU has been proven to scale linearly with connection count. **It has not been measured at all.**

### Required SUMMARY edits before downstream plans cite this

1. Replace the headline finding with: *"At 256 admitted entities (admission cap), mean tick work-time is ~16 ms (n=6 samples per tier, 30 s window). The 500-bot and 1000-bot tiers exceed the admission cap and produce identical grid load; only the 100→256 transition is observed."*
2. Move the 10× claim out of the document or restate it as: *"10× connection attempts. Admitted entities: 100, 256, 256."*
3. Add the f6da129 path-list verification (per §F).
4. Rewrite A8 with the correct PrintFlagsFinal check.
5. Reframe the lock flamegraph claim to: *"6 frames in JVMTI MonitorContended at 1000-bot load — `synchronized(session)` monitor pattern shows no visible cross-session contention. j.u.c.locks (incl. WorldGrid's RWL) are JVMTI-invisible; this finding does not extend to them."*

### Minimum re-measurement to actually answer "does the server scale linearly with connection count?"

Either (a) raise `paralife.admission.cap` for the duration of the scaling experiment so 1000 admitted entities is achievable on the grid, or (b) keep the cap and explicitly measure per-connection cost on the cohort that scales (Jetty accept + frame parse + admission decision per rejected connection, plus the per-session VT cost for accepted ones).

### 4 concrete additional measurements

1. **Admitted-entity gauge per tier, sampled in same 30 s window**:
   Metric: `paralife.admission.active.entities` (per-bucket gauge, AdmissionMetrics.java:240). Same 6×5 s polling shape as existing sidecar.
   Expected signature: 100 / 256 / 256 (current setup) or 100 / 500 / 1000 (with raised cap).
   This single number falsifies or confirms the implicit assumption that the tiers differ.

2. **Outbound queue-depth histogram + stall counters**:
   New gauge: register `paralife.outbound.queue.depth.max` and `.avg` derived from `OutboundSender.queueDepth(sessionId)` across attached sessions, OR push the existing per-session depth into a Micrometer DistributionSummary on each enqueue.
   Sample existing counters in the sidecar: `paralife.backpressure.stalled.total`, `paralife.backpressure.terminal.dropouts`, `paralife.backpressure.stalled.sessions`.
   Saturation signature: queue depth p95 climbing through tier sweep; non-zero stalled.total; rising terminal dropouts.

3. **Tick-work DistributionSummary percentiles**:
   `paralife.tick.work.ms` already registered with p50/p95/p99 in TickEngine.java:44-48. Add it to the sidecar — same `curl /actuator/metrics/paralife.tick.work.ms` form, returns percentile measurements. Tail latency surfaces here before it surfaces in the scalar mean gauge.

4. **JFR per-thread-group CPU breakdown post-hoc**:
   On the existing `jfr-1000bots-baseline-c22e487.jfr` (already in tree):
   ```
   jfr print --events jdk.ThreadCPULoad --json jfr-1000bots-baseline-c22e487.jfr \
     | jq '.events[] | select(.thread.name | test("ws-sender-|tick-engine|ForkJoinPool"))'
   ```
   Aggregate CPU share by thread-name prefix. Re-run across tiers. Expected: `ws-sender-*` aggregate scales with admitted-bot count; `tick-engine` is roughly tier-invariant; carrier-pool (`ForkJoinPool-1-worker-*`) scales with VT activity.
   Saturation signature: `ws-sender-*` aggregate approaching one carrier-core's worth of CPU, indicating encode is becoming the bottleneck.

(Bonus 5: confirm what BotClient does on `E|429|world-full`. If it retries, the rejected sessions DO drive ongoing accept/reject CPU and that's measurable via the `paralife.admission.rejected` counter delta over the window. If it closes-and-dies, the 744 sessions on the 1000-tier contribute nothing after ramp-up. Either way it's directly testable.)

---

*Files I did not inspect that would tighten this audit*: `BotClient.java` (admission-reject behaviour), `BotFleet.java` (peakRegistered semantics, did it ever reach 1000?), `WorldWebSocketHandler.java` (how rejection frames are sent / closed), git commit `f6da129` itself (sealing the stale-golden argument).

---

## Gemini Review

**VERDICT: RED** — The baseline is fundamentally misleading. The headline finding that tick work-time is "flat across the 10× connection-count scale" is an illusion caused by a configuration constraint and a misunderstanding of what the metrics capture. The top three reasons for this verdict are:
1. **The load is capped at 256 bots:** `application.yml` enforces a hard limit of 256 active entities. The 500-bot and 1000-bot runs are both observing exactly 256 active bots in the simulation, rendering the scale comparison useless.
2. **The metric excludes the actual per-connection workload:** `paralife.tick.health.work-time-ms` measures only the synchronous `TickEngine` dispatch loop. It entirely excludes JSON encoding and WebSocket I/O, which are offloaded to per-session virtual threads.
3. **Severe statistical under-sampling:** The 30s sampling window reads a point-in-time scalar gauge exactly 6 times. With a 500ms tick interval, this ignores 54 out of 60 ticks occurring in that window, missing any variance or spikes that the existing `DistributionSummary` meter tracks.

### A. What does `paralife.tick.health.work-time-ms` actually measure?

- **Wall-clock vs Broadcast:** This gauge measures the wall-clock time of the simulation tick on a single thread. In `src/main/java/com/paralife/engine/TickEngine.java` (lines 97-106), `lastTickWorkMs` is calculated as `System.nanoTime() - startTime` immediately following `eventPublisher.publishEvent(event)`.
- **Exclusion of per-session work:** It does include `buildTickFrame` inside `TickBroadcaster.java` (`@EventListener @Order(50)`, line 173), but it explicitly **excludes** broadcast encoding and WebSocket I/O. `TickBroadcaster` simply enqueues the un-encoded frame via `outboundSender.offer(bot.sessionId(), frame);`.
- **Normalization:** The gauge is scalar, per-tick, and server-global. Because the most CPU-intensive per-bot work (codec JSON encoding and network dispatch) is offloaded to per-session virtual threads, the tick thread's work primarily consists of iterating the `botRegistry` and object allocation. This exclusion of codec work heavily contributes to the apparent flatness of the metric.

### B. What does the harness actually do at `--count 1000`?

- In `src/main/java/com/paralife/harness/LoadHarness.java` (line 218), the harness executes `fleet.launch(serverUri, count, ...)` which genuinely creates 1000 concurrent active WebSocket sessions.
- However, the server's admission logic sits between those network connections and the simulation. A connection does not automatically mean an active, perceiving bot.

### C. Admission cap interaction

- **Cap Enforcement:** `src/main/resources/application.yml` sets `paralife.admission.cap: 256`. In `src/main/java/com/paralife/admission/AdmissionGate.java` (lines 125-135), "Guard 5: Global cap" explicitly rejects any registration beyond the `reservedSlots` cap with a `429 world-full` rejection token.
- **The Dominant Effect:** Yes, this is absolutely the dominant effect. At `--count 1000`, the first 256 sessions are admitted. The remaining 744 sessions are held at the gate and rejected. 
- Because `TickBroadcaster.onTick` iterates over `botRegistry.getAllBots()` (line 155), the 744 rejected sessions generate zero `buildTickFrame` work. The 500-bot tier and the 1000-bot tier are observably indistinguishable because they are both running exactly 256 active entities on the grid.

### D. Where does broadcast / codec work actually live?

- **Virtual Threads:** The per-connection encode and send costs are paid exclusively in the per-session virtual threads. `src/main/java/com/paralife/admission/OutboundSender.java` operates a `drainLoop` (lines 249-253) on a dedicated VT that calls `PerceptionCodec.encode(frame)` and `session.sendMessage(...)`.
- **Saturation Metrics:** If these VTs were saturating, it would manifest as an increase in queue depth. `OutboundSender.java` implements a bounded queue and tracks depth via `queueDepth(String sessionId)`, but **this metric is not exposed to the sidecar**. The sidecar only polls `work-time-ms` and `detach.timeout` (which only increments if a VT fails to join during a disconnect). Neither metric in the sidecar would capture an encoding bottleneck or queue saturation.

### E. JFR + flamegraph capture validity

- **Workload equivalence:** The lock flamegraph was captured in a second run. Because the load is structurally identical (hard-capped at 256 active bots in both runs), the workload is largely the same. However, running a completely separate harness execution is a methodological vulnerability when comparing flamegraphs directly.
- **Contention vs Contended Events:** The author's claim that "6 frames" proves no contention is flawed. `async-profiler -e lock` relies on JVMTI `MonitorContendedEnter`. It does not record all lock acquisitions; it only records *contended* lock acquisitions. The `synchronized(session)` block in `OutboundSender.java` (line 252) will not generate an event unless two threads attempt to write to the same session simultaneously. 6 frames means there were 6 *collisions*, not 6 acquisitions.
- **Statistical Inadequacy:** 6 samples × 5s = 30s metric window is entirely inadequate for this gauge. `paralife.tick.health.work-time-ms` is a point-in-time scalar gauge reflecting the single most recent tick. Sampling it every 5 seconds means measuring exactly 6 ticks out of the 60 that occurred in that window. `TickEngine.java` registers a `DistributionSummary` named `paralife.tick.work.ms` (line 46) specifically to capture percentiles across all ticks, but the sidecar completely ignores it.

### F. The three-gate result and the stale-golden defence

- **Soundness of the argument:** The framing is structurally unsound. A test failing against a "stale golden" means the server's behavioral output has drifted from the previously verified correct state. 
- **Anchoring Risk:** By anchoring the performance baseline to `c22e487`—a commit known to produce different output than the golden file—you are profiling unverified simulation logic. While the simulation may be deterministic *with itself*, the trace delta could easily represent an expensive bug (e.g., an infinite loop, broken pathing, or dropped events) introduced prior to `c22e487`. Downstream diffs will be anchored to a potentially degraded reference.

### G. Assumption A8 falsification

- **Falsification methodology:** The author's check (`java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'`) is invalid. Running this command without specifying a GC simply proves that the JVM defaults to G1GC (which is well known). Because ZGC was not requested, `UseZGC` is false, and therefore `ZGenerational` is false.
- **The correct check:** To verify if Generational ZGC is the default *when ZGC is enabled*, the command must be: `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`. In Java 21, this returns `true`. The author's flawed conclusion will lead downstream plans to treat Generational ZGC as an opt-in flag rather than a default feature of ZGC.

### H. The bigger picture

This baseline capture must be thrown out. The current artifacts measure how a 256-bot simulation performs while 744 network sockets sit idle, heavily under-sampling the tick thread, and entirely ignoring the codec/VT tier where the real per-connection compute happens. 

To actually answer "how does the server respond to 10× more clients", the author must either raise the `admission.cap` in `application.yml` to ≥1000 for the test runs, or use an `application-test.yml` profile to override it during the harness executions.

**4 specific additional measurements required for a valid baseline:**
1. **`paralife.admission.active.entities` (Gauge):** To verify that the server is actually simulating 100/500/1000 bots, rather than artificially capping the population.
2. **`paralife.tick.work.ms` (DistributionSummary):** Poll the actual Micrometer percentiles (p50, p95, p99) over the window to capture all ticks, rather than spot-checking the scalar `health.work-time-ms` gauge.
3. **`paralife.outbound.queue.depth` (New Gauge):** Expose `OutboundSender` queue sizes to Micrometer. A rising queue depth is the expected signature of VT/encode saturation.
4. **`system.cpu.usage` or `process.cpu.usage` (Micrometer/JFR):** A global CPU load metric is required to observe total system saturation, as the tick-thread metric is blind to the fleet of encoding VTs.

---

## Codex Review

**Verdict: RED** — the flat tick result is real for the narrow sampled gauge, but the baseline is misleading as evidence of 1000-client headroom. Top reasons: `--count 500/1000` is capped to about 256 admitted entities by `paralife.admission.cap`; the headline gauge excludes per-session encode/send work on outbound virtual threads; and the metric sidecars sampled only six “latest tick” gauge values while omitting active entities, rejections, active sessions, queue depth, send latency, and CPU saturation.

**A. Tick Metric**
`paralife.tick.health.work-time-ms` is a scalar gauge over `lastTickWorkMs`, registered without tags in [AdmissionMetrics.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:153). `TickHealthMonitor` copies `tickEngine.getLastTickWorkMs()` into that gauge once per tick in [TickHealthMonitor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:62).

The underlying value is measured in `TickEngine` from just before `eventPublisher.publishEvent(event)` until after it returns, then stored as `lastTickWorkMs` in [TickEngine.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEngine.java:109). That includes synchronous tick listeners: simulation, action resolution, tick-frame construction, queue offering, and periodic keepalive work. It does not include asynchronous outbound encode/send done later in per-session VTs.

There is no per-bot normalization here. The metric is scalar, no tags, one latest completed tick value.

**B. Harness Shape**
`--count 1000` launches 1000 `BotClient`s via `BotFleet.launch`, one virtual thread per bot, in [BotFleet.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotFleet.java:84). `rate:50` delays starts by `1_000_000_000 / 50` ns, so ramp takes about 20 seconds for 1000 bots in [RampUpSpec.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/RampUpSpec.java:42).

But “launched” is not “1000 concurrent admitted entities.” A bot sends an initial register frame after connect in [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:194). If the server returns `E|429`, the bot disconnects in [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:422). Only registered bots receive tick frames and run the brain/action path in [BotClient.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotClient.java:383).

Some admitted bots may later become passive roles and emit no action; `HeuristicBrain` returns null for bonded secondary and non-locomotor composite roles in [HeuristicBrain.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/HeuristicBrain.java:92). Rejected bots do no steady-state tick/action traffic.

**C. Admission Cap**
This is the dominant validity problem. The config sets `paralife.admission.cap: 256` in [application.yml](/home/mark/kramtime/paralife/src/main/resources/application.yml:65). `AdmissionGate` rejects fresh registrations when `reservedSlots >= cap` in [AdmissionGate.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionGate.java:139).

So at `--count 1000`, roughly 744 sessions should be rejected as `world-full`, aside from churn. They may briefly create connection/register/error/close load, but they do not become grid entities and do not participate in steady-state tick simulation or per-tick broadcast.

Yes: `--count 500` and `--count 1000` can be observably indistinguishable from `--count 256` for tick work. The 100-bot tier being similar is not proof of 1000-bot headroom; it could be fixed tick overhead plus very sparse sampling.

**D. Broadcast / Codec Work**
`TickBroadcaster` runs in the tick listener and builds a `Frame.TickFrame` per live bot, then calls `outboundSender.offer` in [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:207). The comment explicitly says encode and frame-size recording happen in `OutboundSender.drainLoop`, not here, in [TickBroadcaster.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:216).

`OutboundSender` attaches one VT per session in [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:124). Encode and send happen inside the drain loop in [OutboundSender.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:273): `PerceptionCodec.encode`, byte counting, then synchronized `session.sendMessage`.

Useful saturation metrics would be queue depth, offer failures/drops, stalled transitions, encode timer, send timer, active sender count, active sessions, and JFR CPU/alloc per VT/carrier. The sampled sidecars captured only tick health and detach timeout.

**E. JFR / Flamegraph Validity**
The plan requested concurrent async-profiler captures in [20-01b-PLAN.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-PLAN.md:145). The summary says execution had to run CPU/alloc/lock sequentially, and lock required a second 1000-bot run in [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:131). The meta file also marks lock as a follow-up run, independent from the main JFR run in [jfr-1000bots-baseline-c22e487.meta.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json:9).

So the lock flamegraph is not strictly the same workload instance as the JFR/CPU/alloc captures. “6 frames vs 209” is weak evidence. It could mean low contention, but also sampler/event coverage issues, missed steady state, JVMTI monitor-event limitations, or simply too little admitted load.

Also, 6 actuator scrapes at 5s do not observe ~60 tick samples. They observe six latest-gauge values. The raw 1000-bot sidecar has six values in [metrics-1000bots-baseline-c22e487.json](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json:2). To observe 60 ticks, sample the `paralife.tick.work.ms` `DistributionSummary` delta over the window.

**F. Three-Gate / Stale Golden**
The stale-golden argument is only partially sound. The summary says `GoldenTraceEquivalenceTest` fails at `c22e487` for `trace-sess-9` and `trace-sess-21` in [20-01b-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md:114). `f6da129` changed exactly those two golden digests, but it also changed `SimulationEngine` behavior by sorting composite iteration before RNG consumption.

So “HEAD passes with corrected golden” does not, by itself, prove c22e487 had only stale test data. Deterministic repeated output proves repeatability, not correctness against the intended golden. Anchoring downstream before/after work to a SHA that fails its own gate adds risk, especially if later comparisons include post-`f6da129` simulation behavior.

For performance, the failure may not materially affect this baseline, but the summary overstates certainty.

**G. A8 ZGC**
Running `PrintFlagsFinal` without `-XX:+UseZGC` is valid for checking whether ZGC is the default collector: local Temurin 21.0.6 reports `UseG1GC = true` and `UseZGC = false`. But it does not answer whether ZGC is generational by default when ZGC is explicitly selected.

The right checks are:

```bash
java -XX:+PrintFlagsFinal -version | grep -iE 'UseG1GC|UseZGC|ZGenerational'
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'
java -XX:+UseZGC -XX:+ZGenerational -XX:+PrintFlagsFinal -version | grep -iE 'UseZGC|ZGenerational'
```

On this machine, `-XX:+UseZGC` still leaves `ZGenerational=false`; `-XX:+UseZGC -XX:+ZGenerational` makes both true. The summary’s conclusion is right, but its evidence conflates “default collector” with “ZGC mode default.”

**H. Minimum Fix**
RED. Re-measure before citing this as “1000-client headroom.”

Minimum re-measurement:

1. Raise `paralife.admission.cap` above the target, e.g. 1200, and rerun 100/500/1000 with the same duration/ramp.
2. Sample `paralife.admission.active.entities`, `paralife.ws.active.sessions`, and `paralife.admission.rejected{reason=world-full}`.
3. Use `paralife.tick.work.ms` DistributionSummary deltas for count/mean/max/p95 over the steady-state window, not six latest-value gauge scrapes.
4. Add or capture outbound queue depth/offer-failure/stalled counters and encode/send timers.

Concrete expected signatures:

`paralife.admission.rejected{reason=world-full}`: current-cap run should show ~0 / ~244 / ~744 for 100/500/1000. Raised-cap run should remain 0.

`paralife.admission.active.entities`: should prove the actual load, either 100/256/256 current-cap or 100/500/1000 raised-cap.

`paralife.tick.work.ms`: count should rise by about 60 per 30s window; p95/mean should reveal real tick scaling.

Outbound metrics: queue depth approaching 128, increasing offer failures/stalls, or rising encode/send p95 would indicate per-session VT saturation even if tick work remains flat.

---

## Opencode Review

I'll read all the files first, then deliver the audit. Let me batch the reads.Now let me read all the source files and the metric sidecar data.Now let me read the BotFleet and related files to understand the actual bot behavior, and the metric sidecars.Now I have all the evidence. Here is the audit.

---

# VERDICT: **RED** — This baseline is misleading and should not be cited by downstream plans.

**Top three reasons:**

1. **The admission cap (`cap: 256`) is the dominant effect.** The 500-bot and 1000-bot tiers are measuring the same workload (~256 entities). The claimed "10× connection-count scale span" is actually a 2.5× span (100 vs 256 entities on grid), and the 500 → 1000 tier is a flatline identical workload. The "server CPU is far from the bottleneck at 1000 bots" conclusion is based on measuring 256 entities, not 1000.

2. **`paralife.tick.health.work-time-ms` measures only synchronous tick-thread work** — it excludes `PerceptionCodec.encode()`, `session.sendMessage()`, and per-session VT drain activity. The per-connection costs (the very thing that would scale with connection count) live on virtual threads and are invisible to this gauge.

3. **The lock flamegraph (6 frames) was captured against a different run than the JFR/cpu/alloc captures** (forced by `asprof` rejecting concurrent attach). Combined with 6-sample metric windows, this baseline does not meet the statistical rigour needed to anchor tuning decisions.

---

## A. What does `paralife.tick.health.work-time-ms` actually measure?

It measures the wall-clock time of the **synchronous listener dispatch** on the tick thread — from `System.nanoTime()` before `eventPublisher.publishEvent(event)` until it returns (`TickEngine.java:110-119`). The value is then pushed into the Micrometer gauge via `admissionMetrics.setLastTickWorkMs(sample)` in `TickHealthMonitor.onTick()` at `@Order(Integer.MAX_VALUE)` (`TickHealthMonitor.java:61-63`).

The gauge **includes** everything that runs synchronously on the tick thread during `@EventListener` dispatch:
- `SimulationEngine` @Order(10) — combat, decay, overcrowding
- `EnvironmentEngine` @Order(14) — toxin/mutagen/lightning/compost, rebuilds status caches
- `CompositeEnergyDistributor` @Order(15) — drain
- `ActionResolver` @Order(20) — resolve bot actions
- `EnvPostActionReconciler` @Order(25) — buff grants
- `TickBroadcaster` @Order(50) — **frame construction for every bot** (building `CellEntry` lists, iterating 5×5 vision windows, RLE passes, kind-code lookups per `TickBroadcaster.java:207-233`)
- `WebSocketKeepaliveService` @Order(200) — PINGs
- `TickHealthMonitor` @Order(Integer.MAX_VALUE) — samples the value

The gauge **excludes** everything that runs on per-session virtual threads:
- `PerceptionCodec.encode(frame)` — runs inside `OutboundSender.drainLoop()` (`OutboundSender.java:286`), on per-session VTs
- `session.sendMessage(new TextMessage(encoded))` — same drain VT (`OutboundSender.java:290`)
- Queue drain/take operations — same drain VT

**This directly explains part of the flatness.** Building `CellEntry` lists for N bots is O(N) and does show up in the gauge. But the encode + send cost (which also scales with connection count) does NOT show up. The gauge is a measure of tick-thread work, not total system work.

**Normalisation:** The gauge is a single `AtomicLong` — one value per server, updated once per tick. It is NOT per-bot, NOT per-tick averaged. The value is the wall-clock ms of the most recent completed tick dispatch. It does not normalise to per-connection — it's a global scalar. No hidden normalisation is hiding scaling effects here. The gauge semantics are honest; the problem is simply that it measures tick-thread work only.

---

## B. What does the harness actually do at `--count 1000`?

Based on `BotFleet.java` (via the explore agent findings):

1. **1000 virtual threads are spawned**, each calling `bot.connect()` to open a WebSocket connection and send `r|<species>`.
2. Each connection **passes admission evaluation** at `AdmissionGate.java:110-158`. The first ~256 bots pass guard 5 (cap check at line 140-150) and receive `S|<entityId>` sync frames. They become active entities on the grid and enter the tick loop — building and sending action frames every tick.
3. **~744 bots receive `E|429|world-full`** from the admission gate. `BotClient.onError()` calls `disconnect()` (closes the WS), and `awaitRegistered()` times out. These bots are **gone from the steady-state** — no open WebSocket, no entity, no action traffic.
4. **Ramp-up is `rate:50`** (`LoadHarness.java:74`), meaning 50 bots per second. At 1000 bots that's 20 seconds to launch all VTs. Bots #257+ get rejection quickly (within a few connection + r-frame round trips). By the time the 30s "sleep then steady-state capture" starts, only ~256 bots remain.

**Each active bot generates perception/action traffic every tick** — `BotClient.onTick()` calls `brain.decide()` and sends an action frame back (`BotClient.java:400-403`). The ~744 rejected bots generate zero traffic — their VTs exit after timeout, connections closed.

---

## C. Admission cap interaction

**Yes, the cap is the dominant effect.** Here's what each tier actually produced:

| `--count` | Entities on grid (cap=256) | Actual load multiplier |
|-----------|---------------------------|----------------------|
| 100 | ~100 | 1× (baseline) |
| 500 | ~256 | ~2.5× |
| 1000 | ~256 | ~2.5× |

The 500 and 1000 tiers are **measuring the same workload**. The 10× span in `--count` (100 → 1000) collapses to a ~2.5× span in actual active entities (100 → 256). The "flat" result between 500 and 1000 is tautological — they're the same workload.

This is confirmed by the raw metric samples:
- 100 bots: [19, 16, 16, 16, 19, 20] — mean 17.7
- 500 bots: [17, 16, 14, 17, 20, 15] — mean 16.5
- 1000 bots: [14, 18, 14, 14, 15, 16] — mean 15.2

The values are within noise of each other across all three tiers. If the workload were actually scaling 10×, you'd expect a clear trend. The fact that 100, 500, and 1000 all produce ~15-18ms makes perfect sense when you realise 500 and 1000 are both ~256 actual entities, and 100 is ~100.

**Even the 100 → 256 delta is modest** because the tick-thread work is frame-construction (O(active_entities × vision_area)) — building 5×5 vision windows for 256 bots (25 × 256 = 6400 CellData lookups) vs 100 bots (2500 lookups) is only 2.5×.

**`paralife.outbound.detach.timeout = 0` at all tiers** is also explained by the cap: at 256 entities, there are 256 drain VTs. With `outbound-queue-size: 128` and 500ms ticks, there's simply not enough traffic for any queue to overflow, and no VT to stall.

---

## D. Where does broadcast/codec work actually live?

**Frame construction** runs on the tick thread in `TickBroadcaster.onTick()` (`TickBroadcaster.java:190-239`). For each bot, it calls `buildTickFrame()` which:
- Reads from `WorldGrid.getCell()` (line 350-351)
- Builds CellEntry lists via `buildCellEntries()` — iterating vision windows, RLE passes
- Computes entity status, env state, vision-scoped overcrowding
- Constructs events, effects, pool snapshots, roster hashes

This construction cost IS included in `tick-work-time-ms`.

**Frame encoding and sending** runs on per-session drain VTs in `OutboundSender.drainLoop()` (`OutboundSender.java:273-313`):
- `PerceptionCodec.encode(frame)` (line 286) — text encoding
- `encoded.getBytes(StandardCharsets.UTF_8)` (line 287)
- `session.sendMessage(new TextMessage(encoded))` inside `synchronized(session)` (lines 289-290)
- `metrics.recordFrameSize(encodedBytes.length)` (line 288)

These costs are NOT included in `tick-work-time-ms`.

**To capture saturation on the per-connection VT side**, the missing metrics are:
1. **Queue depth:** `OutboundSender.queueDepth(sessionId)` — available per-session but not exposed as an aggregate gauge
2. **Encode-VT wall time:** Not instrumented at all — needs per-frame timing inside `drainLoop`
3. **Drop count:** The `detach.timeout` counter could indicate VT stalls, but it fires only at detach time (when join exceeds 100ms), not during steady-state operation
4. **CPU load from JFR:** The JFR would capture drain VT CPU usage, but the summaries don't mention any drain-VT analysis

None of these are in the metric sidecars.

---

## E. JFR + flamegraph capture validity

**Lock flamegraph workload validity:** Per `20-01b-SUMMARY.md` deviation #1: `asprof` rejected concurrent attach with `[ERROR] Profiler already started`. The execution ran **cpu then alloc sequentially**, then a **second 1000-bot LoadHarness run** to capture lock. The lock flamegraph therefore does NOT capture the same server process state as the JFR/cpu/alloc captures. The meta.json confirms this (`flamegraphs.lock: "follow-up run; main 1000-bot run for JFR is independent"`). This undermines cross-flamegraph comparisons (e.g., "6 frames vs 209") — different processes, different tick sequences, different entity populations.

**"6 frames" as evidence of no contention:** This claim is overstated for multiple reasons:
- The lock event in async-profiler 4.4 uses JVMTI `MonitorContendedEnter` (per meta.json: `perf_event_paranoid_observed=2` → async-profiler couldn't use perf events). JVMTI-based lock profiling has known coverage gaps — it reports Java monitor contention only, not `java.util.concurrent` lock contention, not I/O blocking, not VT scheduling delays. A 6-frame lock flamegraph under JVMTI means "few Java monitors were contended during this 60s window" — not "the architecture has no contention."
- The second-follow-up-run caveat means even if the result were valid for that run, it doesn't represent the same workload as the JFR/cpu captures.
- 6 frame nodes vs 209 in cpu is a comparison between different profiling modes on different runs — the denominator isn't comparable.

**Statistical adequacy of 6 samples × 5s:** With a 500ms tick interval, the metric window covers ~60 ticks (30s / 0.5s). The 6 samples capture 6 point-in-time readings of `lastTickWorkMs`, which represents the most recent completed tick's duration. With 60 ticks happening, 6 evenly-spaced snapshots would capture roughly 1 in 10 ticks. This is adequate for detecting a trend if the per-tick variance were low. However, the raw samples (e.g., 1000-bot: [14, 18, 14, 14, 15, 16]) show values varying between 14-18ms across samples — which is well within noise given the tiny absolute values (~3% of 500ms budget). The 6 samples are sufficient to say "this metric is small" but insufficient to make a quantitative claim like "tick work is flat across tiers" when the values are this close.

---

## F. The three-gate result and the stale-golden defence

**The author's argument is structurally weak** for two reasons:

1. **Sessions `trace-sess-9` and `trace-sess-21`** failing deterministically means those sessions produce different digest output at `c22e487` vs the golden. The author claims the golden is stale and the simulation is deterministic. But: if the golden was generated from a commit where the simulation produced one output, and `c22e487` produces a different output for these sessions, then **something in the simulation did change between those commits** — even if it's a test-only config difference, a seed change, or a subtle order-of-operations shift. The author verified "identical digests across two consecutive runs at c22e487" which proves determinism AT c22e487, but not determinism relative to the golden's original commit. The corrected golden in `f6da129` might be matching a different simulation state than what produced the original golden.

2. **Binding to `c22e487` (a SHA that fails its own three-gate) creates a poisoned reference.** Downstream plans doing "before/after" diffs against c22e487 will compare against a baseline where 8/9 gates pass. If a future change causes `GoldenTraceEquivalenceTest` to fail on sessions we don't currently understand (sessions 9 and 21 are already "known-failing"), the operator can dismiss it as "the same stale-golden issue" — but it might actually be a new regression. An anchor SHA should at minimum pass all its own tests at the time of capture.

**Risk to downstream:** Medium. The stale-golden is genuinely a known issue (confirmed fixed at HEAD). The simulation behavior IS deterministic at c22e487 (two runs produce same digests). The pre/post semantics of a Plan 5 codec change could still be compared against c22e487's actual simulation output, not the golden. The problem is trust erosion — if the baseline SHA can't pass tests, what else about it might be off?

---

## G. Assumption A8 falsification

**The falsification reasoning is wrong.** Running `java -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` without any GC flag reports that ZGC is not the currently-selected GC. That is correct — Temurin 21.0.6 defaults to G1. But this doesn't answer the real question: **when you DO opt into ZGC via `-XX:+UseZGC`, is `ZGenerational` on by default?**

The correct test would be:
```bash
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'
```

In Temurin 21.0.6, `ZGenerational` defaults to `true` — but only when ZGC is the selected collector. The A8 assumption should have been written as "Generational ZGC is the default ZGC mode when ZGC is enabled" and tested accordingly. The current "FALSIFIED" label is correct in the narrow sense that "ZGC is not the default GC" — but the downstream Plan 4 impact note says "ZGC is opt-in only (`-XX:+UseZGC -XX:+ZGenerational`)" which may be overly conservative: `-XX:+ZGenerational` might be redundant.

**Correct statement:** `-XX:+UseZGC` alone enables generational ZGC in Temurin 21.0.6. The `-XX:+ZGenerational` flag is not needed separately. Plan 4 should note both interpretations.

---

## H. The bigger picture — overall verdict

**Verdict: RED**

The baseline is misleading because:
1. The admission cap of 256 means the 500 and 1000 tiers measured the same workload. The claimed 10× scale span is actually 2.5× (100 → 256 entities).
2. The sole sampled throughput metric (`tick-work-time-ms`) excludes the two costs most likely to scale with connections: per-frame encode and per-session WebSocket send.
3. The "zero detach timeouts" and "flat tick work" conclusions are artefacts of measuring a bounded workload with a partial metric, not evidence of architectural headroom.

### Minimum re-measurement to answer "does the server scale with connection count?"

**1. Re-run with `paralife.admission.cap` set above `--count` for each tier.**
Set `paralife.admission.cap: 1200` in `application.yml` and re-capture the three tiers. This ensures 100/500/1000 actual entities on the grid, not 100/256/256.

**2. Add `paralife.admission.active.entities` gauge to the sidecar.**
The sidecar currently captures `work-time-ms` and `detach.timeout`. Add `active.entities` to confirm the actual grid population at capture time. Without this, you cannot distinguish "100 bots running" from "1000 bots intending to run but only 256 succeeding."

**3. Add per-session drain VT wall-time instrumentation.**
Inside `OutboundSender.drainLoop()`, add a `System.nanoTime()` around the encode+send block and record to a `DistributionSummary`. This captures the per-connection cost that `tick-work-time-ms` excludes. Expected saturation signature: p99 encode+send time trending up as connection count rises.

**4. Add `OutboundSender.queueDepth` aggregate (max or p99 across all sessions) to the sidecar.**
This is the backpressure signal the architecture was designed around. If queue depth at 1000 active entities is close to the configured `outbound-queue-size: 128`, you're approaching the VT drain rate limit. Expected saturation signature: mean/max queue depth increasing with connection count, especially if encode is not parallelised.

---

## Consensus Summary

### Agreed Strengths
- None — all four reviewers issued RED. No reviewer cited material strengths worth defending.

### Agreed Concerns
- **[CRITICAL] Admission cap=256 collapses 500/1000 tiers to identical load** (all 4). `application.yml:65` + `AdmissionGate.java:140-150` reject past 256. Effective admitted entities: 100/256/256, not 100/500/1000. Headline "10× scale span" is tautological — flatness between 500/1000 is one config measured twice.
- **[CRITICAL] `paralife.tick.health.work-time-ms` excludes per-session encode+send** (all 4). `PerceptionCodec.encode` + `session.sendMessage` run on per-session VTs in `OutboundSender.drainLoop` (line 286-290), not tick thread. Gauge structurally blind to per-connection cost that scales with admitted entities.
- **[HIGH] Sidecar omits metrics that would show per-connection saturation** (all 4). Missing: `paralife.admission.active.entities`, `paralife.admission.rejected{world-full}`, `paralife.tick.work.ms` DistributionSummary p95/p99 (already registered in `TickEngine.java:44-48`), `OutboundSender.queueDepth`, `paralife.backpressure.stalled.*`.
- **[HIGH] 6 samples × 5s window statistically inadequate** (all 4). Scalar gauge sampled 6× across ~60 ticks; raw values [14,18,14,14,15,16] for 1000-tier within noise of other tiers. Existing `paralife.tick.work.ms` DistributionSummary would give count/p95/p99 — unsampled.
- **[HIGH] A8 ZGC falsification methodology wrong** (all 4). `java -XX:+PrintFlagsFinal -version` without `-XX:+UseZGC` only proves ZGC is not default collector. Correct check: `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational`. Conclusion happens to be partly right but evidence conflates "default collector" with "ZGC-mode default". Note: claude/gemini/codex say `ZGenerational=false` even with `+UseZGC` in 21.0.x (opt-in until JDK 23+); opencode claims `ZGenerational=true` by default with `+UseZGC` in Temurin 21.0.6 — see Divergent Views.
- **[MEDIUM] Lock flamegraph "6 frames = no contention" overclaim** (all 4). Captured in second run (asprof rejected concurrent attach) — different workload instance than CPU/alloc. async-profiler `-e lock` uses JVMTI `MonitorContendedEnter` which covers `synchronized` only; `java.util.concurrent.locks.*` (incl. `WorldGrid`'s `ReentrantReadWriteLock`, `ArrayBlockingQueue` internals) are invisible. Finding only validates `synchronized(session)` pattern, not "no contention anywhere".
- **[MEDIUM] Stale-golden defence incomplete** (claude/codex/opencode). Determinism between two runs at c22e487 ≠ behavioural equivalence with HEAD. f6da129 subject `fix(19.1): pass-1 multi-review follow-up sweep` ambiguous; codex notes it also changed `SimulationEngine` composite-iteration ordering. Required: enumerate paths changed in f6da129 before honouring baseline. Anchoring perf baseline to SHA that fails its own three-gate is a smell regardless.

### Required SUMMARY edits (consensus)
- Replace headline with: "At ~256 admitted entities (admission cap), mean tick work-time ~16 ms; 500/1000 tiers produce identical grid load."
- Drop "10× scale span" claim.
- Reframe lock finding to scope it to `synchronized(session)` monitor pattern only.
- Rewrite A8 with correct `+UseZGC +PrintFlagsFinal` check.
- Enumerate f6da129's changed paths.

### Required re-measurement (consensus)
1. Raise `paralife.admission.cap` ≥1200 (or per-profile override) and re-run 100/500/1000.
2. Sample `paralife.admission.active.entities` + `paralife.admission.rejected{reason=world-full}` to verify actual grid population.
3. Sample `paralife.tick.work.ms` DistributionSummary deltas (count/mean/p95/p99) over steady-state window, not 6× scalar gauge.
4. Expose + sample `OutboundSender.queueDepth` aggregate (max/p95) + `paralife.backpressure.stalled.*` counters. Add per-frame encode+send timer inside `drainLoop`.
5. Post-hoc JFR slice by thread-name prefix (`ws-sender-*` vs `tick-engine` vs `ForkJoinPool-1-worker-*`) using `jfr print --events jdk.ThreadCPULoad` on existing `.jfr` file — captures per-connection CPU share retroactively.

### Divergent Views
- **ZGenerational default-when-ZGC-enabled in Temurin 21.0.6**: claude/gemini/codex assert `ZGenerational=false` even with `+UseZGC` (opt-in until JDK 23+); opencode asserts `ZGenerational=true` automatically with `+UseZGC` in Temurin 21.0.6, making `+ZGenerational` redundant. Worth resolving with local `java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep ZGenerational` before Plan 4 writes the GC section. Material impact on whether the flag must be added explicitly.
- **`BotClient` behaviour on `E|429|world-full`**: codex/opencode read `BotClient.onError` → `disconnect()` (rejected sessions drop out; no ongoing accept/reject CPU after ramp-up). claude flags this as unverified (didn't read `BotClient.java`) — affects whether the 744 rejected sessions contribute steady-state load. Codex+opencode reading is concrete with line refs; treat as resolved unless contradicted.
- **Baseline salvageability**: claude/gemini call for full re-capture; codex frames as "do not cite as 1000-client headroom" but leaves narrower uses open; opencode notes pre/post codec-change diffs could still use c22e487 as a deterministic anchor even if golden is stale. Practical impact: whether existing JFRs have any downstream value, or are sunk cost.
- **Severity of f6da129 risk**: claude wants explicit path enumeration before honouring baseline; codex highlights the `SimulationEngine` composite-ordering change as load-bearing concern; gemini/opencode frame stale-golden as smell/trust-erosion but not blocker. Settle by running `git show --stat f6da129`.
