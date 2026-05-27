# Independent methodology + result review: Paralife Phase 20 Plan 01b baseline capture

## What you are being asked to do

We just shipped a "baseline performance capture" plan (commit `14e96ea` on the `main` branch of the Paralife project). It produced this headline result:

> **Per-tier tick-work-time mean (`paralife.tick.health.work-time-ms`, sampled 6× at 5s):**
> - 100 bots → 17.7 ms
> - 500 bots → 16.5 ms
> - 1000 bots → 15.2 ms
>
> **`paralife.outbound.detach.timeout` count at end of each window:** 0 / 0 / 0.

The author concluded:

> "Headline finding: Tick work-time is essentially flat across the 10× connection-count scale span — server CPU is far from the bottleneck at 1000 bots."

**This result smells wrong** to the project lead. A 10× increase in connected WebSocket clients producing zero observable change in any sampled metric is the kind of finding that, if accepted naively, will cause downstream tuning work to chase the wrong bottleneck. Before this baseline becomes the citation source for Plans 20-04 / 20-05 / 20-06, we want **independent eyes** to either:

1. **Confirm the result is what it appears to be** (the architecture genuinely has this much headroom), with reasoning grounded in the source code — OR —
2. **Identify what is wrong** about the methodology, the metric semantics, the load shape, or the conclusion — and tell us specifically what additional measurements would distinguish "real headroom" from "we measured the wrong thing".

This is not a code-style review. It is a **methodology + result-validity audit**.

## The specific things you must check

### A. What does `paralife.tick.health.work-time-ms` actually measure?

Read `src/main/java/com/paralife/admission/AdmissionMetrics.java` and `src/main/java/com/paralife/admission/TickHealthMonitor.java` and `src/main/java/com/paralife/engine/TickEngine.java`. State explicitly:

- Is this gauge measuring wall-clock time of the simulation tick on a single thread, or is it including broadcast/codec work?
- If it excludes work that runs on per-session virtual threads (broadcast, encode, outbound queueing), does that explain the flatness?
- Is there any chance the gauge is per-bot, per-tick, or otherwise normalised in a way that hides per-connection scaling?

### B. What does the harness actually do at `--count 1000`?

Read `src/main/java/com/paralife/harness/LoadHarness.java` (and its option/ramp classes). State:

- Does `--count 1000 --duration 200 --ramp-up rate:50` actually create 1000 concurrent active WebSocket sessions for the steady-state window? Or does it create connections that idle, disconnect early, get rejected by admission, etc?
- Does each "bot" generate perception/action traffic at every tick, or are some bots silently passive?

### C. Admission cap interaction

`src/main/resources/application.yml` sets `paralife.admission.cap: 256`. `src/main/java/com/paralife/admission/AdmissionGate.java` enforces it. State:

- At `--count 1000`, are the other ~744 sessions actually doing work that affects tick-time, or are they parked / rejected / held off-grid by the admission gate?
- If the entity population is bounded at 256 regardless of `--count`, that alone would explain flat tick work. **Is that the dominant effect here?**
- The 100-bot tier is also under the cap (100 < 256). Could a tier where `--count` > cap (500, 1000) be observably indistinguishable from `--count = cap` (256)?

### D. Where does broadcast / codec work actually live?

Read `src/main/java/com/paralife/websocket/TickBroadcaster.java` and `src/main/java/com/paralife/admission/OutboundSender.java`. State:

- Per the existing architecture (per-session VT + bounded queue) is the per-connection encode + send cost paid in the tick thread or in per-session virtual threads?
- If broadcast/encode is on per-session VTs, what metric **would** capture saturation there? Queue depth? Encode-VT wall time? Drop count? CPU load from JFR? Is any of those in the sampled sidecar?

### E. JFR + flamegraph capture validity

Read the methodology in `20-01b-PLAN.md` §how-to-verify and the deviation notes in `20-01b-SUMMARY.md` "Capture-Process Deviations from Plan 20-01b §how-to-verify". State:

- The plan asks for 60s concurrent cpu/alloc/lock async-profiler capture; the execution discovered async-profiler 4.4 rejects concurrent attach and captured sequentially. The 1000-bot lock flamegraph required a **second** 1000-bot run. Is the resulting lock flamegraph capturing the same workload as the JFR / cpu / alloc?
- The lock flamegraph shows 6 frames vs CPU's 209. The author claims this proves D-10 (per-session VT + queue) successfully isolates contention. Is "6 frames" actually evidence of "no contention" — or could it be evidence the lock event itself is mis-configured, the sampler missed steady state, JVMTI MonitorContendedEnter has known coverage gaps, the workload genuinely doesn't contend, or all of the above?
- 6 samples × 5 s = 30 s metric window. Is that statistically adequate given the 500 ms tick interval (so ~60 ticks observed per window)?

### F. The three-gate result and the stale-golden defence

The author shipped a baseline at SHA `c22e487` knowing `GoldenTraceEquivalenceTest` fails 1/9 deterministically there. They argue it's a stale-golden test-infrastructure issue (fixed post-tag in commit `f6da129`), simulation determinism is preserved, and the baseline measurements are unaffected. State:

- Is that argument structurally sound, or is "the test was correct but the golden hasn't caught up" a face-saving framing that hides an actual code-level regression at `c22e487`? Look at the affected sessions (`trace-sess-9`, `trace-sess-21`) and ask whether the golden delta could in fact reflect a behaviour difference between c22e487 and the post-f6da129 state of the simulation.
- Does the baseline binding to `c22e487` (a SHA that fails its own three-gate) introduce risk that downstream "before/after" diffs are anchored to a known-degraded reference?

### G. Assumption A8 falsification

The summary marks A8 ("Generational ZGC default-on in Temurin 21.0.6") as FALSIFIED based on `-XX:+PrintFlagsFinal -version | grep -iE 'ZGenerational|UseZGC'` returning `false`. State:

- Is reading `PrintFlagsFinal` against a JVM run *without* `-XX:+UseZGC` a valid way to check whether ZGC is "default"? Or does that just show that the JVM isn't running ZGC right now (because nothing requested it)? Could the correct check be different?
- If the falsification reasoning is wrong, downstream Plan 4 may be marking ZGC as opt-in when it's actually default-when-requested. Spell out the right test.

### H. The bigger picture

After A–G, give your overall verdict:

- **GREEN** — methodology is sound, the flat-tick result is genuine, baseline is safe to use.
- **YELLOW** — measurements are valid for what they measure, but the framing in `20-01b-SUMMARY.md` overstates what the result actually proves. List the specific framing edits needed before downstream plans cite this.
- **RED** — the baseline is misleading. List the minimum re-measurement that would actually answer "how does the server respond to 10× more clients".

And — most useful for the project — **list 2–4 specific additional measurements** the author should have captured to definitively close the question "does the server scale linearly with connection count?". Be concrete (metric name, sampling shape, expected signature of saturation).

## Files you have

| File | Purpose |
|---|---|
| `20-01b-PLAN.md` | Methodology contract |
| `20-01b-SUMMARY.md` | Result claim + capture-process notes |
| `profiles/metrics-{100,500,1000}bots-baseline-c22e487.json` | Raw sampled metric data |
| `profiles/jfr-1000bots-baseline-c22e487.meta.json` | Assumption A1-A9 outcomes |
| `src/main/java/com/paralife/admission/AdmissionMetrics.java` | Meter registration |
| `src/main/java/com/paralife/admission/TickHealthMonitor.java` | Where tick work-time is sampled |
| `src/main/java/com/paralife/admission/AdmissionGate.java` | Admission cap enforcement |
| `src/main/java/com/paralife/admission/OutboundSender.java` | Per-session outbound VT + queue |
| `src/main/java/com/paralife/engine/TickEngine.java` | Tick loop, `getLastTickWorkMs()` source |
| `src/main/java/com/paralife/websocket/TickBroadcaster.java` | Per-tick broadcast pipeline |
| `src/main/java/com/paralife/harness/LoadHarness.java` | LoadHarness driver |
| `src/main/resources/application.yml` | `admission.cap=256` and other config |

## Format

Open with a one-paragraph **verdict** (GREEN / YELLOW / RED) and the top three reasons.

Then answer each of **A, B, C, D, E, F, G, H** as its own labelled subsection. Cite specific lines from the source files when you make a claim about what the code does. Speculation is fine — just label it as such.

Do not invent quotes. If a section of the source code does not contain what you expect, say so, name the file you read, and explain what you found instead. The reviewer is genuinely uncertain; flat-out "I cannot tell from this code, here is what I'd need to read" is a valid answer.
