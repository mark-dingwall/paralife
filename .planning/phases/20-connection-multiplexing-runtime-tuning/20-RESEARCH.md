# Phase 20: Connection Multiplexing & Runtime Tuning — Research

**Researched:** 2026-05-09
**Domain:** JVM/Jetty 12/Spring Boot 3.4.4 runtime tuning + JFR/async-profiler workflow
**Confidence:** HIGH for tooling + Spring/Jetty wiring; MEDIUM for tier-specific GC/heap sizing (real numbers come from D-04/D-05/D-06 profile runs, not from this research); MEDIUM for codec hot-path candidates (gated by D-10 JFR evidence).

## Summary

Phase 20 is **tuning, not multiplexing**. CONTEXT.md D-01 closes the multi-entity question — Paralife keeps WS:entity 1:1 and reduces per-connection cost via four tunable layers (JVM flags, Jetty WS knobs, application-level knobs, codec internals). This research surfaces the concrete wiring, defaults, profiling commands, and risks the planner needs to slice work.

Three findings dominate planning shape:

1. **The Jetty extension point already exists.** `JettyDeflateCustomizer.jettyRequestUpgradeStrategy` at `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java:75-82` already calls `strategy.addWebSocketConfigurer(c -> c.setIdleTimeout(...))`. Adding a `paralife.runtime.jetty.*` `@ConfigurationProperties` record and chaining additional `Configurable` setters on the same lambda is a small, low-risk delta — not a new architectural surface. [VERIFIED: code read]
2. **The toolchain is half-installed locally.** Temurin 21.0.6 ships `jcmd`, `jfr`, and `default.jfc`/`profile.jfc` configs out of the box at `$JAVA_HOME/lib/jfr/`. async-profiler is **NOT** installed and must be added as a Phase 20 prerequisite (download + checked into `tools/` or sourced via ap-loader). JFR alone covers GC, allocation, monitor pinning (`jdk.VirtualThreadPinned`), and lock contention — async-profiler primarily adds native/syscall view (Jetty internals, kernel time on socket I/O). [VERIFIED: `java -version`, `ls $JAVA_HOME/lib/jfr/`, `which async-profiler`]
3. **Synchronized-session-monitor + Jetty blocking write = the highest-risk pinning surface in the codebase.** Java 21 virtual threads still pin their carrier inside `synchronized` (JEP 491 fixed this only in Java 24). Paralife has FOUR documented writers all entering `synchronized(session) { session.sendMessage(...) }` (`OutboundSender.drainLoop`, `WebSocketKeepaliveService.onTick`, `WorldWebSocketHandler.sendOutOfBand`, `WorldWebSocketHandler.sendFrame` back-compat fallback) — each is an opportunity for `jdk.VirtualThreadPinned` events at 1000-bot scale. JFR evidence here is load-bearing: P20 should treat pinning measurement as a first-class headline alongside `paralife.tick.health.work-time-ms`. [CITED: openjdk.org/jeps/491; CLAUDE.md §Outbound concurrency]

**Primary recommendation:** Slice the phase into **6 plans** (see §9). Plan 1 brings up the toolchain and captures the c22e487 baseline. Plans 2-3 add the two `@ConfigurationProperties` records as pure additive bindings (no behaviour change at default values). Plan 4 ships JVM presets as documentation. Plan 5 lands the JFR-evidenced codec hot-path opts and re-runs the three-gate stack. Plan 6 writes `20-RUNTIME.md` and the three rationale-cross-refs (CLAUDE.md / README.md / inline comments) and captures the tuned-state JFRs. **Do not start coding before Plan 1's baseline JFR is committed** — every later plan cites it for before/after deltas.

## Project Constraints (from CLAUDE.md)

- **Single-thread mutation invariant** (CLAUDE.md §Conventions → Concurrency): all world mutations happen in tick event handlers; tuning MUST NOT introduce concurrent writes to `WorldGrid`. Codec hot-path opts run on the drain VT (read-only relative to grid), so they're safe by construction.
- **WS:entity 1:1** (CLAUDE.md §Connection model + 18-HARNESS.md §1): D-01 honors this; research must not propose any multi-entity-per-session opts.
- **Synchronized-session-monitor contract** (CLAUDE.md §Outbound concurrency): every `sendMessage` invocation holds `synchronized(session)`. Encoding stays OUTSIDE the monitor. Codec opts MUST NOT relocate work into the monitor.
- **Tuning lives in config, not constants** (Phase 17 D-15 precedent): `@ConfigurationProperties` records are the ONLY acceptable shape for layers 2 + 3 (D-09).
- **`@Order` table accuracy** (CLAUDE.md §Architecture): if any new bean joins the tick pipeline (none currently planned), the table at line 58-67 must update.

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Keep WS:entity 1:1 — equivalent transport-level overhead reduction, NOT genuine multiplexing. Phase 18 D-05/D-21 stands. SCALE-08's "or equivalent" escape hatch is taken.
- **D-02:** Three-place rationale codification: README.md, CLAUDE.md §Runtime tuning subsection, in-code comments at WS upgrade + OutboundSender VT loop sites.
- **D-03:** Forward-note only — revisit D-01 only if Phase 21 evidence forces it at 5000 conns/JVM.
- **D-04/D-05:** Profiling = JFR + async-profiler. Artifacts committed under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` (raw `.jfr` + flamegraph HTML).
- **D-06:** Profile scenarios at 100 / 500 / 1000 bots via standalone `loadHarnessJar` CLI (NOT Gradle test). P22 `forkEvery=1` + 5-min JUnit timeout do NOT apply.
- **D-07:** Four-layer tuning surface: (1) JVM flags = doc-only presets; (2) `paralife.runtime.jetty.*` `@ConfigurationProperties`; (3) `paralife.runtime.app.*` `@ConfigurationProperties`; (4) codec impl = internal constants, JFR-driven.
- **D-08:** JVM flags as documented presets only (no wrapper script).
- **D-09:** `@ConfigurationProperties` records bind layers 2+3, mirroring AdmissionConfig/RespawnConfig/SpawnConfig/GridConfig/TickConfig pattern. Field doc convention `[live-tunable | launch-only]`. `@RefreshScope` hook seams reserved for future M5 admin-UI live-tune.
- **D-10:** Codec hot-path opts JFR-driven only. Code at `src/main/java/com/paralife/codec/PerceptionCodec.java` (sibling `Base64Codec`). NEVER cross the wire — `15-SCHEMA.md` stays bit-exact.
- **D-11:** Three-gate equivalence stack (P19 D-10 GoldenTraceEquivalenceTest + P19.1 D-11 GoldenTraceWithActionsTest + P19.1 D-12 LiveEntityRegistryInvariantTest). **TD-19.5-A caveat:** GoldenTraceEquivalenceTest is flaky in **isolated** runs (~40% emit ±1) — gate ONLY in-suite, never on isolated runs.
- **D-12:** Existing test suite stays green excluding 4 P22 `@Disabled` tests (TD-22-A..D). DO NOT re-enable any.
- **D-13:** Every recipe in `20-RUNTIME.md` cites baseline JFR + tuned JFR with concrete deltas. Headline gauges: `paralife.tick.health.work-time-ms` (AdmissionMetrics.java:65) and `paralife.outbound.detach.timeout` (AdmissionMetrics.java:74).
- **D-14:** Canonical doc at `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` mirroring `17-ADMISSION.md` / `18-HARNESS.md` style.
- **D-15:** CLAUDE.md §Runtime tuning subsection added.
- **D-16:** README.md operator paragraph added.
- **D-17:** P22.1 owns its own `22-INVARIANTS.md` diff. P20 ships nothing extra.
- **D-18:** L1 `paralife.outbound.detach.timeout` counter promoted to D-13 headline (read-only signal, no new knob).
- **D-19:** Profile baseline anchored to commit SHA `c22e487` (Phase 19.1 close). Filenames cite SHA.
- **D-20:** Layer `paralife.runtime.app.*` keys ALONGSIDE `paralife.admission.backpressure.outbound-queue-size`, do NOT move it. Backlog item filed for Phase 999.4.

### Claude's Discretion

- Concrete `paralife.runtime.jetty.*` and `paralife.runtime.app.*` field names and defaults (driven by profile evidence).
- Profile artifact size bounds (suggested ≤5 MB per file, ≤20 MB total).
- Exact LoadHarness ramp / duration / seed for per-tier profile runs.
- Whether `paralife.runtime.app.*` is a single record or split.
- GC choice per tier (ZGC vs G1) — JFR evidence drives.
- Format of profile-finding citations in `20-RUNTIME.md`.

### Deferred Ideas (OUT OF SCOPE)

- Genuine multi-entity-per-WS or sub-WS transport multiplexing (D-01/D-03).
- Phase 21 benchmark gate (SCALE-10).
- Parallel `PerceptionBroadcaster` / parallel tick-encode (separate phase, depends on P20).
- Admin-UI live-tune (M5).
- Automated config search (future bench-harness phase).
- `15-SCHEMA.md` mutation.
- Re-enabling P22 `@Disabled` tests.
- `paralife.admission.backpressure.outbound-queue-size` namespace migration (Phase 999.4).
- `/actuator/prometheus` (M5).
- Docker / packaging (M6).

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SCALE-08 | High bot-count runs reduce socket or process overhead through connection multiplexing **or an equivalent transport-level scale strategy**. | D-01 takes the "or equivalent" escape hatch. Research §2 (Jetty knobs) + §3 (app knobs) + §4 (JVM presets) + §5 (codec opts) deliver four equivalent overhead-reduction levers. |
| SCALE-09 | Runtime tuning for virtual threads and the compact protocol is measured and documented from real benchmark profiles rather than guesswork. | Research §1 (profiling toolchain), §6 (verification gate), §7 (`20-RUNTIME.md` structure) deliver the measurement infrastructure and documentation contract. |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| JFR capture | Operator / JVM | — | `jcmd` is JVM-attached; runs alongside the running Spring Boot process |
| async-profiler capture | Operator / native | JVM (jattach) | Native agent attaches via jattach; lives outside JVM but reads JVM internals |
| `paralife.runtime.jetty.*` binding | Spring config layer | Jetty 12 (`Configurable` callbacks) | Spring `@ConfigurationProperties` → `JettyRequestUpgradeStrategy.addWebSocketConfigurer` lambda chain |
| `paralife.runtime.app.*` binding | Spring config layer | Application beans (OutboundSender, TickBroadcaster, codec) | Standard `@ConfigurationProperties` injection into `@Component`s |
| Codec hot-path opts | Codec layer (`com.paralife.codec`) | — | Pure static functions; no Spring lifecycle |
| JVM flags | Operator / JVM launch | — | `java -jar` launch flags; documented in `20-RUNTIME.md` per tier |
| Tick-thread tuning | Engine layer | — | Single-thread mutation invariant — no flag we add changes this property |
| Outbound VT-per-session | Admission layer (`OutboundSender`) | Application config | VT count = active session count; queue capacity = `paralife.runtime.app.*` knob |
| Three-gate verification | Test layer | All above | In-suite Gradle invocation; TD-19.5-A flake masked |

## Standard Stack

### Core (Already in repo — verified)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Temurin OpenJDK | 21.0.6+7-LTS | JVM with virtual threads + JFR | LTS, ships `jcmd` + `jfr` + default `.jfc` configs [VERIFIED: `java -version`] |
| Spring Boot | 3.4.4 | Application framework | Pinned [VERIFIED: `build.gradle.kts:4`] |
| Jetty | 12.0.18 | Embedded WS server (via `spring-boot-starter-jetty`) | Pinned [VERIFIED: `build.gradle.kts:36`] |
| Spring Framework `JettyRequestUpgradeStrategy` | 6.x (transitive from Spring Boot 3.4.4) | WS upgrade extension point | `addWebSocketConfigurer` already in use [VERIFIED: `JettyDeflateCustomizer.java:80`] |
| Micrometer | (transitive via Spring Boot Actuator) | Metrics | `MeterRegistry` already injected [VERIFIED: `AdmissionMetrics.java:7-8`] |

### Supporting (Phase 20 introduces)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| async-profiler | 4.x (latest stable) | Native flamegraph capture | When JFR fingers a hot path that needs syscall/native attribution |
| `ap-loader` (alternative) | 4.x-platform-jar | Embedded async-profiler distribution | If Phase 20 chooses to commit the agent into the repo (`tools/`) for reproducibility |

[VERIFIED via WebSearch 2026-05-09]: async-profiler 4.x supports Java 21, can output `.jfr` directly, supports multi-event capture (`-e cpu,alloc,lock`). `jfr2flame` ships in the same project for converting JFR → flamegraph HTML.

**Version verification commands:**
```bash
# When adding async-profiler:
curl -sSL https://github.com/async-profiler/async-profiler/releases/latest -I | grep location
# Pin to a specific tag for reproducibility (D-19 spirit).
```

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| async-profiler | YourKit / JProfiler | Commercial; can't be committed; more polished UI, worse for CI/repro |
| async-profiler | Honest Profiler | Older, less maintained, no JFR output |
| ap-loader (embedded) | external download in `20-RUNTIME.md` | External: lighter repo; ap-loader: hermetic but +5-10MB jar |
| `jcmd` for JFR start | JVM launch flags `-XX:StartFlightRecording=...` | Launch flags = continuous from boot; `jcmd` = on-demand. **Recommend launch flags for long baseline runs (continuous), `jcmd JFR.dump` for tuned-state captures.** |
| 100/500/1000 LoadHarness | One harness JVM per tier | 100 ≤ 1 process; 500/1000 = 1×500 + 2×500 / 2×500 + a 100 = mixed runs allowed per 18-HARNESS.md §1 D-02 (5000-conn ceiling per JVM, so 1 JVM is fine for all three tiers) |

## Architecture Patterns

### System Architecture Diagram (Phase 20 dataflow)

```
[Operator]
     │
     │ 1. git checkout c22e487 (D-19 baseline anchor)
     │ 2. ./gradlew loadHarnessJar
     │ 3. java -XX:StartFlightRecording=... -jar app.jar (server)
     │ 4. java -jar build/libs/paralife-*-load-harness.jar --count 1000 ... (driver)
     │ 5. jcmd <pid> JFR.dump filename=profiles/jfr-1000bots-baseline-c22e487.jfr
     │ 6. async-profiler -d 60 -e cpu -f profiles/cpu-1000bots-baseline-c22e487.html <pid>
     ▼
[JFR + flamegraphs in profiles/]
     │
     │ 7. analyze: GC pauses, jdk.VirtualThreadPinned events, allocation hot paths
     ▼
[Tuning hypotheses]
     │
     │ 8. land paralife.runtime.jetty.* / paralife.runtime.app.* / codec opts
     │ 9. re-run profile capture against HEAD (tuned)
     │ 10. assert ./gradlew test green (in-suite, D-11 three-gate stack)
     ▼
[Tuned JFRs in profiles/ + 20-RUNTIME.md deltas]
```

### Recommended Project Structure (additive only)

```
src/main/java/com/paralife/
├── runtime/                       # NEW Phase 20 package
│   ├── JettyRuntimeConfig.java    # @ConfigurationProperties(prefix="paralife.runtime.jetty")
│   ├── AppRuntimeConfig.java      # @ConfigurationProperties(prefix="paralife.runtime.app")
│   └── RuntimeBeansConfig.java    # @Configuration wiring AppRuntimeConfig into existing beans
├── codec/                         # existing
│   ├── PerceptionCodec.java       # internal constants tuned per D-10 evidence
│   ├── Base64Codec.java           # ditto
│   └── (no new files unless evidence demands a buffer pool helper)
└── websocket/
    ├── JettyDeflateCustomizer.java # MUTATED: jettyRequestUpgradeStrategy reads JettyRuntimeConfig
    └── WorldWebSocketHandler.java  # MUTATED: D-02 inline comment at WS upgrade site
```

`.planning/phases/20-connection-multiplexing-runtime-tuning/`
```
├── 20-CONTEXT.md            # exists
├── 20-RESEARCH.md           # this file
├── 20-RUNTIME.md            # D-14 deliverable
├── profiles/                # D-05 committed artifacts
│   ├── jfr-100bots-baseline-c22e487.jfr
│   ├── jfr-500bots-baseline-c22e487.jfr
│   ├── jfr-1000bots-baseline-c22e487.jfr
│   ├── jfr-1000bots-tuned-<HEAD-sha>.jfr
│   ├── cpu-1000bots-baseline-c22e487.html
│   ├── alloc-1000bots-baseline-c22e487.html
│   └── README.md            # filename convention + how to re-run
└── 20-PLAN-XX-*.md          # plan files
```

### Pattern 1: `@ConfigurationProperties` record bound from `application.yml`

**Source:** `src/main/java/com/paralife/admission/AdmissionConfig.java:22-46` (project canonical pattern); `src/main/java/com/paralife/engine/SpawnConfig.java:20-25` (minimal example); `src/main/java/com/paralife/engine/TickConfig.java:9-25` (validation in compact constructor).

**When to use:** Layers 2 + 3 of D-07. Record + `@ConstructorBinding` + `@DefaultValue` + compact-constructor validation.

**Phase 20 example (sketch — exact fields driven by profile evidence):**

```java
// src/main/java/com/paralife/runtime/JettyRuntimeConfig.java
@ConfigurationProperties(prefix = "paralife.runtime.jetty")
public record JettyRuntimeConfig(
        @DefaultValue("4096")  int inputBufferSize,         // [launch-only]
        @DefaultValue("4096")  int outputBufferSize,        // [launch-only]
        @DefaultValue("65536") int maxFrameSize,            // [launch-only]
        @DefaultValue("65536") int maxBinaryMessageSize,    // [launch-only]
        @DefaultValue("65536") int maxTextMessageSize,      // [launch-only]
        @DefaultValue("60000") long idleTimeoutMs,          // [launch-only — already exists in JettyDeflateCustomizer:77]
        @DefaultValue("true")  boolean autoFragment) {      // [launch-only]
    @ConstructorBinding
    public JettyRuntimeConfig {
        if (inputBufferSize  < 256) throw new IllegalArgumentException(...);
        if (idleTimeoutMs    < 1000) throw new IllegalArgumentException(...);
        // ... etc
    }
    public static JettyRuntimeConfig defaults() {
        return new JettyRuntimeConfig(4096, 4096, 65536, 65536, 65536, 60000, true);
    }
}
```

**Wiring (mutates `JettyDeflateCustomizer.java:75-82`):**

```java
@Bean
public JettyRequestUpgradeStrategy jettyRequestUpgradeStrategy(
        JettyRuntimeConfig runtimeConfig,
        @Value("${paralife.websocket.idle-timeout-ms:60000}") long legacyIdleTimeoutMs) {
    JettyRequestUpgradeStrategy strategy = new JettyRequestUpgradeStrategy();
    strategy.addWebSocketConfigurer(c -> {
        c.setIdleTimeout(Duration.ofMillis(runtimeConfig.idleTimeoutMs()));
        c.setInputBufferSize(runtimeConfig.inputBufferSize());
        c.setOutputBufferSize(runtimeConfig.outputBufferSize());
        c.setMaxFrameSize(runtimeConfig.maxFrameSize());
        c.setMaxBinaryMessageSize(runtimeConfig.maxBinaryMessageSize());
        c.setMaxTextMessageSize(runtimeConfig.maxTextMessageSize());
        c.setAutoFragment(runtimeConfig.autoFragment());
    });
    return strategy;
}
```

**Migration note for legacy `paralife.websocket.idle-timeout-ms`:** keep the legacy key alive via fallback (D-20 spirit — additive, not breaking) OR wrap in a deprecation warning. The planner should choose; recommendation = leave legacy untouched and have `JettyRuntimeConfig.idleTimeoutMs` default-initialise from it for one phase to avoid a silent default change.

### Pattern 2: `addWebSocketConfigurer` lambda chain (Jetty 12 + Spring 6.x)

**Source:** [Spring Framework 6.2 JettyRequestUpgradeStrategy javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/server/jetty/JettyRequestUpgradeStrategy.html); [Jetty 12 Configurable javadoc](https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/api/Configurable.html).

The `Configurable` interface accepts: `setIdleTimeout(Duration)`, `setInputBufferSize(int)`, `setOutputBufferSize(int)`, `setMaxFrameSize(long)`, `setMaxBinaryMessageSize(long)`, `setMaxTextMessageSize(long)`, `setAutoFragment(boolean)`. All are launch-only — applied at WS upgrade time per session, no live mutation API.

[VERIFIED via WebSearch 2026-05-09]

### Anti-Patterns to Avoid

- **Don't extract codec encoding INTO the `synchronized(session)` monitor.** It's currently outside (CLAUDE.md §Outbound concurrency: "Encoding and metric recording stay outside the monitor"). Moving encode under the monitor would re-introduce a multi-second writer-stall hazard that Phase 17 deliberately fixed.
- **Don't add a wrapper script around `java -jar`.** D-08 explicitly forbids this. JVM flags ship as documentation in `20-RUNTIME.md`, applied by the operator.
- **Don't move `paralife.admission.backpressure.outbound-queue-size`.** D-20 layers new keys alongside; namespace consolidation is Phase 999.4 territory.
- **Don't rely on `GoldenTraceEquivalenceTest` running in isolation.** TD-19.5-A: ~40% flake rate when isolated, masked in-suite. Verification gate is `./gradlew test` (full suite).
- **Don't try to replace VT-per-session with Jetty native async writes.** OutboundSender:32-44 documents why this was already rejected. Per-session isolation + explicit `queue.size()` backpressure signal are structural properties.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Profile capture mechanics | Custom JFR start/stop wrapper | `-XX:StartFlightRecording=...` (continuous) + `jcmd <pid> JFR.dump filename=...` (snapshot) | JDK ships them; widely understood format; JMC-readable |
| Flamegraph rendering | Custom flamegraph generator | `async-profiler -f *.html` or `jfr2flame` | Battle-tested; SVG zoom; standard format |
| Pinning detection | Custom monitor inspection | `jdk.VirtualThreadPinned` JFR event (>20ms by default) | Built-in Java 21 event; preferred over `-Djdk.tracePinnedThreads` (the latter is removed in Java 24, JFR event is forward-compatible) |
| Per-session VT lifecycle | New abstraction | `OutboundSender` already has it (`Thread.ofVirtual().name("ws-sender-...")`) | Phase 17 D-10 — load-bearing, working |
| Backpressure signal | Polling / heuristic | `OutboundSender.queueDepth(sessionId)` + `paralife.backpressure.stalled.sessions` gauge | Already wired; expose via tuning surface, don't reinvent |
| Config record skeleton | Bespoke yaml parser | Spring `@ConfigurationProperties` records + `@DefaultValue` + `@ConstructorBinding` | Project convention (D-09); validation via compact constructor |
| Test seam for codec equivalence | New harness | `OutboundSender.setFrameEmitListener` (Phase 19 D-10) + `GoldenTraceEquivalenceTest` | Already exists at `OutboundSender.java:91-99` |
| Buffer pooling | Custom `ByteBuffer` pool | Java 21 `ThreadLocal<StringBuilder>` (initial), or netty-style pool ONLY if JFR proves StringBuilder churn is hot | Heavyweight pools introduce GC complexity that often loses to ZGC + good thread-locals at our scale |

**Key insight:** The infrastructure for measurement, configuration, and testing already exists in the codebase. Phase 20's job is to wire the right knobs onto the right beans, capture profiles, and document tier presets — not to build new abstractions.

## Runtime State Inventory

Phase 20 is additive. There is **no rename, refactor, or string replacement.** This section is intentionally short.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — Phase 20 ships zero migrations | None |
| Live service config | `application.yml` gains new top-level `paralife.runtime.{jetty,app}.*` subtrees | None operational; documented in `20-RUNTIME.md` |
| OS-registered state | None | None |
| Secrets/env vars | None — config keys are non-secret runtime knobs | None |
| Build artifacts | New `tools/async-profiler/` (if committed) or `~/.cache/...` (if external) | Document in `20-RUNTIME.md` how to bootstrap on a fresh machine |

The one wrinkle: the `paralife.admission.backpressure.outbound-queue-size` key keeps its current binding under D-20 — old k8s/env-var/property overrides keep working. Phase 999.4 owns the eventual consolidation.

## Common Pitfalls

### Pitfall 1: Baseline drift across the c22e487 anchor (D-19 protection)

**What goes wrong:** Operator runs the baseline JFR against HEAD instead of `c22e487`, then runs the tuned JFR against the same HEAD — there is nothing to diff.

**Why it happens:** D-19 anchors the baseline to a specific commit but the worktree currently sits on HEAD; running `./gradlew loadHarnessJar` builds against HEAD; JFR snapshot is HEAD-vs-HEAD.

**How to avoid:** Plan 1 of P20 documents the exact ritual:
```bash
git stash
git checkout c22e487
./gradlew clean loadHarnessJar bootJar
# capture baseline JFR
git checkout - && git stash pop
./gradlew clean loadHarnessJar bootJar
# capture tuned JFR (after each iteration)
```
Embed the SHA in the JFR filename (D-19) so file names self-document the source.

**Warning signs:** JFR filenames lack SHA suffix; `git log -1 --format=%H` doesn't match the filename's SHA segment.

### Pitfall 2: `synchronized(session)` carrier pinning at scale

**What goes wrong:** At 1000 connections × 5 Hz tick × 4 writers (drain VT + keepalive + sendOutOfBand + back-compat fallback), the `jdk.VirtualThreadPinned` event rate spikes when Jetty's internal write call blocks (slow client, GC, full TCP send buffer). Pinned VTs hold their carrier; carrier pool exhausts; tick thread starves; `paralife.tick.health.work-time-ms` rises; STALLED transitions cascade.

**Why it happens:** JEP 491 (synchronize VTs without pinning) is Java 24-only — Paralife pins on Java 21. Every `synchronized(session) { session.sendMessage(...) }` is an at-risk site.

**How to avoid:** Make `jdk.VirtualThreadPinned` a tracked metric. JFR enables this event by default for blocking >20ms; pull the histogram into `20-RUNTIME.md`'s headline numbers alongside `paralife.tick.health.work-time-ms`. If the count is non-trivial, two mitigations:
1. **Move encode/metric work outside the monitor** (already done — verify nothing regressed).
2. **Convert `synchronized(session)` to `ReentrantLock`** — releases the carrier when blocked. Cost: small refactor; benefit: removes the entire pinning class. ONLY pursue if JFR proves it's a real bottleneck — speculative work without evidence is excluded by D-10's "JFR-driven only" rule.

**Warning signs:** `jdk.VirtualThreadPinned` event count > N per minute (threshold TBD from baseline); tick-work-time mean rising under fixed bot count.

[CITED: openjdk.org/jeps/491; CLAUDE.md §Outbound concurrency lines 110-115]

### Pitfall 3: ZGC tail-pause assumption vs reality

**What goes wrong:** Operator assumes "ZGC is always best for low-latency apps" and switches the 1000-bot tier to ZGC without measuring. ZGC's per-region overhead at small heaps (≤2 GB) can OUTPACE G1's actual pauses; allocation rate from frame encoding may force frequent concurrent cycles.

**Why it happens:** Generic "Java tuning" advice is wrong for specific workloads. Paralife at 1000 bots × 256-byte frames × 5 Hz = ~1.3 MB/s outbound allocation — modest. Generational ZGC (Java 21+) handles this differently from old single-gen ZGC.

**How to avoid:** D-10 spirit: pick GC from JFR evidence, not heuristic. The G1 default may be fine through 500 bots and only need ZGC at 1000. Per-tier recommendations in `20-RUNTIME.md` MUST cite the JFR file that justified the choice.

**Warning signs:** Recipes that say "use ZGC at 1000 bots" without a JFR citation should be flagged in plan-checker review.

### Pitfall 4: TD-19.5-A flake masking real codec breakage

**What goes wrong:** A codec hot-path opt subtly changes encode behaviour (e.g., StringBuilder reuse leaks state). `GoldenTraceEquivalenceTest` is the primary equivalence gate but flakes ~40% of isolated runs. Operator re-runs in-suite, gets green, ships — but the green was the flake masking a real ±1 byte regression that doesn't manifest under in-suite ordering.

**Why it happens:** TD-19.5-A's mechanism (`OutboundSender.awaitAllSessionQueuesDrained` VT race) hides drift signals.

**How to avoid:** Plan 5 (codec opts) MUST run the **three-gate stack** in-suite: GoldenTraceEquivalenceTest + GoldenTraceWithActionsTest + LiveEntityRegistryInvariantTest. Two of those three gates do not have the flake. Ship a codec opt only if **all three** are green for two consecutive in-suite runs. Document the run command + commit SHA + run timestamp in `20-RUNTIME.md`'s codec-opt section.

**Warning signs:** A codec change ships with only one gate passing; only one consecutive green; gate timeout on `concurrentReadsDontBlock` (Phase 22 alarm).

### Pitfall 5: Jetty buffer-default mismatch with our 256-byte typical frame

**What goes wrong:** Jetty 12 default `inputBufferSize` is 4 KB. With 256-byte typical TickFrames and 1000 sessions, that's 4 MB of input buffer permanently allocated for what could fit in 1 MB. NOT a bug — but at 5000 conns/JVM (D-02 ceiling), 20 MB of buffers may pressure the heap that ZGC otherwise wants for survivors.

**Why it happens:** Jetty defaults target generic HTTP/WS, not text-only 256-byte protocols.

**How to avoid:** Plan 2 lands `inputBufferSize` and `outputBufferSize` as `paralife.runtime.jetty.*` knobs. JFR-driven tuning at 1000 bots picks the right value; defaults stay at 4 KB to avoid surprising existing operators.

**Warning signs:** JFR shows TLAB exhaustion + young-gen pressure on `byte[]` allocations attributed to Jetty internals.

## Code Examples

Verified patterns from the existing codebase + canonical Jetty/Spring docs:

### Capturing JFR continuously from boot (preferred for baselines)

```bash
# Source: Oracle JFR docs + Java 21 javadoc (jdk.jfr.Recording)
# https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/Recording.html

JFR_OUT=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr"

java \
  -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=duration=180s,filename="$JFR_OUT",settings=profile,name=p20-baseline-1000 \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -jar build/libs/paralife-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=classpath:application.yml \
  --paralife.simulation.spawn.seed=20251205
```

Then drive load:
```bash
java -jar build/libs/paralife-0.0.1-SNAPSHOT-load-harness.jar \
  --server-uri ws://localhost:8080/ws/world \
  --count 1000 --duration 180 --ramp-up rate:50 \
  --harness-id baseline-c22e487
```

JFR closes automatically when duration elapses; file appears at `$JFR_OUT`. **Note:** the SHA must match the source SHA of `paralife-*.jar`; verify with `git rev-parse --short HEAD` before the build.

### On-demand JFR snapshot (preferred for tuned-state captures)

```bash
# Source: Oracle JFR docs (https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/Recording.html)
SERVER_PID=$(jps -l | grep ParalifeApplication | awk '{print $1}')
HEAD_SHA=$(git rev-parse --short HEAD)

# Start a named recording...
jcmd "$SERVER_PID" JFR.start name=p20-tuned-1000 settings=profile duration=180s \
  filename=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-tuned-${HEAD_SHA}.jfr"

# ...drive load with LoadHarness as above...

# Verify it closed (or force dump):
jcmd "$SERVER_PID" JFR.dump name=p20-tuned-1000 \
  filename=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-tuned-${HEAD_SHA}.jfr"
```

### async-profiler simultaneous CPU + alloc + lock + pinning capture

```bash
# Source: github.com/async-profiler/async-profiler README (verified 2026-05-09)
# Output: HTML flamegraph (small file size — easy to keep ≤5 MB).

ASYNC_PROFILER=tools/async-profiler/bin/asprof
SERVER_PID=$(jps -l | grep ParalifeApplication | awk '{print $1}')
HEAD_SHA=$(git rev-parse --short HEAD)
OUT_DIR=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles"

# CPU flamegraph
$ASYNC_PROFILER -d 60 -e cpu -f "$OUT_DIR/cpu-1000bots-tuned-${HEAD_SHA}.html" "$SERVER_PID"

# Allocation flamegraph
$ASYNC_PROFILER -d 60 -e alloc -f "$OUT_DIR/alloc-1000bots-tuned-${HEAD_SHA}.html" "$SERVER_PID"

# Lock contention
$ASYNC_PROFILER -d 60 -e lock -f "$OUT_DIR/lock-1000bots-tuned-${HEAD_SHA}.html" "$SERVER_PID"
```

JFR can run concurrently — they don't conflict on event channels at this scale. [CITED: github.com/async-profiler/async-profiler/issues/436]

### File-size discipline (D-05 spirit — ≤5 MB per file, ≤20 MB total)

```bash
# JFR settings=profile produces 50-200 MB at 180s/1000 bots — too large.
# Two options: shorter duration OR settings=default.
# Recommendation: 60s settings=profile for tier-1000 (~15-30 MB raw),
# strip via `jfr filter --include-events ...` if needed.

jfr summary "$OUT_DIR/jfr-1000bots-tuned-${HEAD_SHA}.jfr" | head
# If > 5MB: use `jfr filter` with the events of interest only.
```

### Embedding the SHA into the recording's metadata (belt-and-braces)

JFR recordings carry process metadata — but NOT git SHA. Two reproducibility moves:
1. SHA in **filename** (D-19, mandatory).
2. Sibling `*.meta.json` written by the operator script:
```json
{ "captured_at_sha": "c22e487", "scenario": "1000bots", "duration_s": 180, "harness_args": "...", "captured_utc": "2026-..." }
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `-Djdk.tracePinnedThreads=full` | `jdk.VirtualThreadPinned` JFR event | Java 21+ (event); Java 24 removes the flag | Use the JFR event — forward-compat through Java 24+ removal |
| Single-gen ZGC | Generational ZGC | Java 21 default for `-XX:+UseZGC` (with `-XX:+ZGenerational`) | Better small-heap behaviour; survivor handling closer to G1 |
| `synchronized` always pins VTs | `synchronized` no longer pins (JEP 491) | Java 24 (NOT applicable to us yet — Java 21) | We still pin; plan around it |
| Jetty 11 `JettyWebSocketCreator` | Jetty 12 `Configurable` interface | Spring Boot 3.2+ → Jetty 12 | We're on Jetty 12.0.18 already; the new API is what we use [VERIFIED: build.gradle.kts:36] |

**Deprecated/outdated (do not use):**
- `-Djdk.tracePinnedThreads`: removed in Java 24. Use the JFR event instead.
- Spring 5.x `JettyRequestUpgradeStrategy.setExposeJettyApi(true)` API: gone in Spring 6. Use `addWebSocketConfigurer` (already in use at `JettyDeflateCustomizer.java:80`).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Jetty 12.0.18 `Configurable` interface exposes `setInputBufferSize`, `setMaxFrameSize`, `setAutoFragment` etc. as listed | §Standard Stack, §Pattern 2 | Some setter doesn't exist on 12.0.18 specifically — Plan 2 must verify against the pinned 12.0.18 javadoc, not the 12.0.29 / 12.1.4 docs surfaced by web search. **[ASSUMED]** — the Configurable docs I read are 12.0.29 / 12.1.4; 12.0.18 may differ slightly. |
| A2 | async-profiler 4.x is current stable for Java 21 with JFR output + flamegraph HTML | §Standard Stack | Wrong version assumption could fail on Java 21. **[ASSUMED]** — surfaced via WebSearch summary; pin to a verified release tag in Plan 1. |
| A3 | At 1000 bots × 5 Hz × ~256-byte frames the allocation rate is ~1.3 MB/s | §Pitfall 3 | Real frame-size distribution differs (varbase64 frames vary 50-300 bytes; composite g blocks add 20+ bytes per member). **[ASSUMED]** — first JFR run replaces this estimate with measured. |
| A4 | `jdk.VirtualThreadPinned` event default 20ms threshold is appropriate for Paralife | §Pitfall 2 | Pinning under 20ms could be missed at scale. **[ASSUMED]** — Plan 1 may need to lower threshold via custom `.jfc`. |
| A5 | The four `synchronized(session)` writers documented in CLAUDE.md are exhaustive | §Summary, §Pitfall 2 | A fifth writer exists somewhere not yet documented. **[VERIFIED via grep]** — `grep -rn "synchronized(session)" src/main` matches only the four sites; downgraded to verified. (Result: removed from assumed list. Keeping the entry for transparency.) |
| A6 | LoadHarness can sustain 1000 bots from a single JVM under the c22e487 codebase | §Architecture Diagram | If LoadHarness itself is the bottleneck, profile attributes wrong cause. **[ASSUMED]** — 18-HARNESS.md §1 D-02 design ceiling is 5000/JVM, but Phase 18 verification only covered ≤1000. Plan 1 verifies before serious tuning. |
| A7 | `-XX:StartFlightRecording=settings=profile` produces ≤5 MB at 60s × 1000 bots | §Code Examples | Real recording could exceed; need `jfr filter` post-processing | **[ASSUMED]** — confirm in Plan 1; if always >5 MB, plan for filtering. |
| A8 | Generational ZGC default-on in Temurin 21.0.6 | §State of the Art | If still single-gen, GC choice analysis differs | **[ASSUMED]** — `-XX:+UseZGC -XX:+ZGenerational` is the explicit form; verify per-vendor default in Plan 4. |

**The presence of A1-A8 means:** Plan 1 should explicitly verify A1, A2, A6, A7, A8 before later plans depend on them. A3 is replaced by first JFR. A4 may need .jfc tweaking.

## Open Questions (RESOLVED)

1. **What's the actual `jdk.VirtualThreadPinned` event rate at 1000 bots on c22e487?**
   - What we know: pinning is structurally possible at 4 sites; mitigation is `ReentrantLock`.
   - What's unclear: whether it's a real bottleneck or theoretical.
   - RESOLVED-DEFERRED-TO-PLAN-5-TRIAGE: Plan 1 baseline JFR resolves this. If pinning dominates, Plan 5 may need to expand from "codec opts" to "codec opts + monitor → ReentrantLock conversion."

2. **Is `paralife.runtime.app.*` one record or several?**
   - What we know: D-09 says either is acceptable.
   - What's unclear: cohesion of the candidate fields (outbound queue + encode-batch + parallel-encode-threshold + buffer-pool capacity).
   - RESOLVED: single record `AppRuntimeConfig` with nested sub-records (`OutboundConfig`, `EncodeConfig`) — matches `AdmissionConfig`'s nested-record style.

3. **Should `paralife.runtime.app.outbound-queue-size` shadow `paralife.admission.backpressure.outbound-queue-size`?**
   - What we know: D-20 says no — leave the admission key in place.
   - What's unclear: whether `AppRuntimeConfig.outbound` should reference the admission value (read-through) or stay silent on the queue.
   - RESOLVED: silent — per D-20, queue size lives in admission. `AppRuntimeConfig.outbound` carries SIBLING knobs (e.g., `queue-watermark-pct`, `frame-size-budget-bytes`).

4. **Is `tools/async-profiler/` committed or external?**
   - What we know: D-05 wants reproducibility; ap-loader allows hermetic embedding (~5 MB).
   - What's unclear: whether the team prefers a fat repo or external bootstrap docs.
   - RESOLVED: external + bootstrap docs — keeps repo small, async-profiler ships frequently.

5. **Should `20-RUNTIME.md` cite JFR via relative paths or commit hashes?**
   - What we know: D-19 anchors filenames to SHA.
   - What's unclear: whether `20-RUNTIME.md` text says `profiles/jfr-1000bots-baseline-c22e487.jfr` or also embeds the SHA-vs-HEAD diff text.
   - RESOLVED: relative paths in body, SHA-vs-HEAD pair listed in a top-level "Profile Index" table. Mirrors `17-ADMISSION.md` style.

6. **Where exactly does the new CLAUDE.md §Runtime tuning subsection land?**
   - What we know: D-15 says "concise" + cross-ref `20-RUNTIME.md`.
   - What's unclear: whether it goes before §Outbound concurrency, after §Connection model, or as a sibling.
   - RESOLVED: AFTER §Connection model (line 142) — natural reading order is Outbound concurrency → Connection model → Runtime tuning. Place a back-reference in §Outbound concurrency pointing forward.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 (Temurin) | Server, harness, JFR, jcmd, jfr | ✓ | 21.0.6+7-LTS | — |
| `jcmd` | JFR snapshot capture | ✓ | (bundled) | `-XX:StartFlightRecording=...` continuous mode |
| `jfr` CLI tool | JFR file inspection / filter | ✓ | (bundled) | JMC GUI |
| `default.jfc` / `profile.jfc` | JFR settings presets | ✓ | (bundled) | custom `.jfc` (Plan 1 may produce one) |
| async-profiler | Native flamegraph capture | ✗ | — | Plan 1 installs to `tools/async-profiler/` (or via ap-loader); JFR alone covers ~70% of P20 needs as fallback |
| `jps` | Process discovery for jcmd | ✓ | (bundled with JDK) | `pgrep -f Paralife` |
| Gradle | Build | ✓ | (wrapper) | — |
| `git` | SHA pinning per D-19 | ✓ | (system) | — |

**Missing dependencies with no fallback:** None blocking. async-profiler is the only missing piece; JFR can carry the phase if async-profiler install proves troublesome.

**Missing dependencies with fallback:** async-profiler — fall back to JFR-only for native-attribution work; document the gap in `20-RUNTIME.md`.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (junit-jupiter; `org.springframework.boot:spring-boot-starter-test` — bundled with Spring Boot 3.4.4) |
| Config file | `src/test/resources/junit-platform.properties` (5-minute global timeout per test method, SEPARATE_THREAD mode) |
| Quick run command | `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` (in-suite-style, all three gates) |
| Full suite command | `./gradlew test` (default — excludes `@Tag("slow")`; `forkEvery=1` per build.gradle.kts:75) |
| Three-gate stack | the three tests above; D-11 |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SCALE-08 | Per-connection overhead reduced; equivalence preserved | regression (codec equivalence) | `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` | ✅ |
| SCALE-08 | WS:entity 1:1 invariant unchanged | regression | `./gradlew test --tests *Admission* --tests *Attribution*` | ✅ |
| SCALE-09 | Tuning measured, not guessed | manual | profile artifact citations in `20-RUNTIME.md`; Plan 1+5 capture | ⚠️ Wave 0 (the artifacts don't exist yet — Plan 1 creates) |
| SCALE-09 | Documentation exists | manual | `test -f .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` | ❌ Wave 0 (Plan 6 creates) |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` (the three-gate stack runs in-suite-style — fast, ~30-60s).
- **Per wave merge:** `./gradlew test` (full suite minus `@Tag("slow")`; ~5-15 min with `forkEvery=1`).
- **Phase gate:** Full suite green + JFR-anchored measurements in `20-RUNTIME.md` before `/gsd-verify-work`.

### Wave 0 Gaps

- [ ] `tools/async-profiler/` install or `tools/async-profiler-bootstrap.md` — Plan 1
- [ ] `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md` — Plan 1 (filename convention + how to re-run + which SHA)
- [ ] `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-{100,500,1000}bots-baseline-c22e487.jfr` — Plan 1
- [ ] `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` — Plan 2
- [ ] `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` — Plan 3
- [ ] `20-RUNTIME.md` skeleton — Plan 6 final write
- [ ] CLAUDE.md §Runtime tuning subsection — Plan 6
- [ ] README.md operator paragraph — Plan 6
- [ ] Inline rationale comments at `WorldWebSocketHandler` WS-upgrade and `OutboundSender.attachSession` — Plan 6

(Existing tests cover all regression behaviours. No NEW test files needed for Phase 20 — D-12 explicitly forbids re-enabling the four `@Disabled` tests, and the three-gate stack already exists.)

## Security Domain

Phase 20 is internal performance tuning. The wire schema is locked (`15-SCHEMA.md`); the codec doesn't accept new untrusted shapes; admission control is unchanged. Standard ASVS categories that apply:

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | (none — bot-level only, Phase 17 admission already in place) |
| V3 Session Management | no | unchanged from Phase 17 |
| V4 Access Control | no | unchanged |
| V5 Input Validation | yes (regression) | `PerceptionCodec` validation paths must remain intact through codec opts (D-10); `MAX_S_ENTRIES`, `MAX_V_ENTRIES`, varbase64 length bounds are load-bearing security controls per `15-SCHEMA.md` §12. Plan 5 must not relax these |
| V6 Cryptography | no | none (no crypto in path) |
| V7 Errors / Logging | yes | Verify new config surface logs no secrets; Phase 18 attribution-tag cardinality cap (`maxHarnessCardinality`) is unchanged |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Memory exhaustion via giant frames | DoS | Already mitigated via `MAX_S_ENTRIES` (256), `MAX_V_ENTRIES` (32), `paralife.runtime.jetty.maxFrameSize`. P20 must keep the bounds. |
| TLAB pressure / GC pinning | DoS | Tuning surface lets operators pick GC + heap; defaults conservative |
| Pinned VT exhaustion | DoS | New: `jdk.VirtualThreadPinned` JFR event becomes a tracked metric; if non-trivial, Plan 5 may convert `synchronized` → `ReentrantLock` |

## Sources

### Primary (HIGH confidence)

- `src/main/java/com/paralife/admission/OutboundSender.java` (lines 32-44, 132-135, 273-313) — VT-per-session contract
- `src/main/java/com/paralife/admission/AdmissionMetrics.java` (lines 65, 74, 175-176, 451) — headline gauge registration sites
- `src/main/java/com/paralife/admission/AdmissionConfig.java` (lines 22-46, 113-148) — `@ConfigurationProperties` precedent + `outbound-queue-size` binding
- `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` (lines 75-82) — existing `JettyRequestUpgradeStrategy` extension point
- `src/main/java/com/paralife/websocket/WebSocketConfig.java` (lines 22-42) — sole WS handler registration site
- `src/main/java/com/paralife/codec/PerceptionCodec.java` (entire file) — codec hot-path target
- `src/main/java/com/paralife/codec/Base64Codec.java` (entire file) — codec sibling target
- `src/main/resources/application.yml` (lines 36-57) — config root for new `paralife.runtime.*` keys
- `build.gradle.kts` (lines 36, 75-76, 95-102) — Jetty 12.0.18 pin, `forkEvery=1`, `loadHarnessJar` task
- `src/test/resources/junit-platform.properties` — 5-min JUnit timeout
- `CLAUDE.md` (lines 86-141) — Outbound concurrency + Connection model + markStalled close-then-best-effort-OOB
- `.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md` — D-01..D-20 (authoritative)
- `.planning/REQUIREMENTS.md` (lines 31-33) — SCALE-08 / SCALE-09 acceptance text
- `.planning/STATE.md` (lines 38-52) — TD-19.5-A, TD-22-A..D constraints
- `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` — style precedent for `20-RUNTIME.md`
- `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` §1 — D-05/D-21 WS:entity 1:1 + 5000-conn ceiling
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) — Java 24 fix; we're on 21
- [Java 21 Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
- [Java 21 jdk.jfr.Recording javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/Recording.html)

### Secondary (MEDIUM confidence — verified via WebSearch 2026-05-09)

- [Spring Framework 6.2 JettyRequestUpgradeStrategy javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/server/jetty/JettyRequestUpgradeStrategy.html) — `addWebSocketConfigurer(Configurable)` API
- [Jetty 12 Configurable API](https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/api/Configurable.html) — setter list (12.0.29 — A1 caveat: 12.0.18 may differ)
- [Jetty 12 WebSocketPolicy](https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/ee9/websocket/api/WebSocketPolicy.html)
- [async-profiler GitHub README](https://github.com/async-profiler/async-profiler) — multi-event JFR capture
- [Continuous monitoring of pinned threads with Spring Boot and JFR (Mike My Bytes)](https://mikemybytes.com/2024/04/17/continuous-monitoring-of-pinned-threads-with-spring-boot-and-jfr/)
- [Java 24 Thread Pinning Revisited (Mike My Bytes)](https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/)
- [foojay: How to Diagnose and Mitigate Pinning](https://foojay.io/today/how-to-diagnose-and-mitigate-pinning-in-javas-virtual-thread-execution/)
- [foojay: Using Async-Profiler and Jattach Programmatically with AP-Loader](https://foojay.io/today/using-async-profiler-and-jattach-programmatically-with-ap-loader/)

### Tertiary (LOW confidence — flagged for Plan 1 verification)

- async-profiler version pin (4.x assumed; verify on install)
- Jetty 12.0.18-specific `Configurable` setter availability (vs 12.0.29 docs read)
- Generational ZGC default-on in Temurin 21.0.6 (verify with `java -XX:+PrintFlagsFinal | grep ZGenerational`)

## Plan Slicing Recommendation

**6 plans.** Order strictly by dependency.

### Plan 1 — Profiling toolchain bring-up + c22e487 baseline capture

**Owns:** D-04, D-05, D-06, D-19, A1-A2-A6-A7-A8 verification.

**Output:**
- `tools/async-profiler-bootstrap.md` (or committed `tools/async-profiler/`)
- `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md` (filename convention, ritual, SHA-anchoring discipline)
- `profiles/jfr-100bots-baseline-c22e487.jfr`
- `profiles/jfr-500bots-baseline-c22e487.jfr`
- `profiles/jfr-1000bots-baseline-c22e487.jfr`
- `profiles/cpu-1000bots-baseline-c22e487.html`
- `profiles/alloc-1000bots-baseline-c22e487.html`
- `profiles/lock-1000bots-baseline-c22e487.html`
- Sibling `*.meta.json` files

**Verification:** All artifacts < 5 MB each, total ≤ 20 MB. Server SHA matches filename SHA. Three-gate stack green afterward (sanity).

**Blocks every later plan.**

### Plan 2 — `paralife.runtime.jetty.*` record + Jetty wiring

**Owns:** D-07 layer 2, D-09.

**Output:**
- `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` (`@ConfigurationProperties`, validation, defaults match Jetty defaults so no behaviour change)
- `src/main/java/com/paralife/runtime/RuntimeBeansConfig.java` (or extend `JettyDeflateCustomizer`) — wires record into `JettyRequestUpgradeStrategy.addWebSocketConfigurer`
- `application.yml`: empty `paralife.runtime.jetty: {}` block + comment block listing each field with `[launch-only | live-tunable]` per D-09
- `JettyRuntimeConfigTest` (`@DefaultValue` round-trip, validation bounds)

**Verification:** three-gate stack green; existing `JettyDeflateCustomizerTest` passes; idle-timeout regression check (legacy `paralife.websocket.idle-timeout-ms` still works).

**Parallelisable with Plan 3.**

### Plan 3 — `paralife.runtime.app.*` record + application wiring

**Owns:** D-07 layer 3, D-09, D-20 (alongside-not-move).

**Output:**
- `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` (record with nested `OutboundConfig`, `EncodeConfig` — Open Question 2 resolution = single-record-with-nested)
- Inject into `OutboundSender` (e.g., overflow-watermark) and `TickBroadcaster` (encode-batch hint, parallel-encode-threshold reserved for Phase 19.1+)
- `application.yml`: `paralife.runtime.app: {}` block with field doc per D-09

**Verification:** three-gate stack green; admission backpressure tests still pass (proves D-20 alongside-not-move).

**Parallelisable with Plan 2.**

### Plan 4 — JVM-flag presets + per-tier recipe stubs

**Owns:** D-07 layer 1, D-08.

**Output:**
- `20-RUNTIME.md` §3 "Per-Scale-Tier Recipes" populated with 100/500/1000 launch flags
- Each recipe cites the relevant baseline JFR from Plan 1
- `tools/run-server-100bots.sh.example` etc. (optional helper scripts — D-08 forbids wrappers, but `.example` files are fine documentation)

**Verification:** smoke-run each recipe against current HEAD (no tuning yet); confirm server boots.

**Sequential after Plan 1 (needs the baseline JFRs to cite).**

### Plan 5 — JFR-driven codec hot-path opts

**Owns:** D-10, D-13.

**Output:**
- 1-N internal-constant changes in `PerceptionCodec` and/or `Base64Codec`, each justified by a citation to a Plan 1 JFR finding (e.g., "JFR shows StringBuilder allocation rate at 12 MB/s in `encodeTick` → introduce thread-local StringBuilder")
- Tuned-state JFR captured per change at 1000-bot tier
- `20-RUNTIME.md` §4 "Profile Findings" populated with per-opt before/after table

**Verification:** D-11 three-gate stack green IN-SUITE for two consecutive runs per change (mitigates TD-19.5-A flake); `paralife.outbound.frame.size.bytes` distribution unchanged (codec opts don't change wire bytes).

**Sequential after Plan 1; orthogonal to Plans 2-4.**

### Plan 6 — `20-RUNTIME.md` finalisation + cross-refs + inline comments

**Owns:** D-02, D-13, D-14, D-15, D-16, D-18.

**Output:**
- Complete `20-RUNTIME.md` with all sections per D-14 outline (see §7 below)
- CLAUDE.md §Runtime tuning subsection added after §Connection model
- README.md operator paragraph added (Open Question 6 / pre-requisite: README.md is currently 1 line — Plan 6 may also need to add basic README structure)
- Inline comments at `WorldWebSocketHandler` (WS upgrade site near the `setHandshakeHandler(handshake)` call) and `OutboundSender.attachSession` / `drainLoop` pointing at `20-RUNTIME.md`
- Headline before/after delta table for `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout`

**Verification:** docs-only; manual review.

**Sequential — last.**

### Parallelisation Map

```
Plan 1 (baseline)
   │
   ├──► Plan 2 (Jetty record)  ─┐
   ├──► Plan 3 (App record)    ─┤
   ├──► Plan 4 (JVM presets)   ─┤
   └──► Plan 5 (codec opts)    ─┤
                                ▼
                            Plan 6 (docs + cross-refs)
```

Plans 2/3/4/5 can land in any order or in parallel after Plan 1 — none depend on each other's outputs (codec opts use Plan 1's JFR directly; docs in Plan 6 reference everything else). The planner may bundle Plans 4 and 6 if profile evidence is shallow.

## §7 Recommended `20-RUNTIME.md` Outline (D-14)

Mirror of `17-ADMISSION.md` and `18-HARNESS.md`.

```
# Phase 20: Runtime Tuning Spec

**Phase:** 20-connection-multiplexing-runtime-tuning
**Status:** Authoritative — locks D-01..D-20 from 20-CONTEXT.md
**Requirements:** SCALE-08 (overhead reduction), SCALE-09 (measured tuning)

## §1 Architectural Principle: WS:entity 1:1 (D-01 / D-02)

[copy CONTEXT D-01 rationale; cross-ref CLAUDE.md §Connection model + 18-HARNESS.md §1]
[explicit "this is a deliberate choice, NOT a missing optimisation" callout]

## §2 Tuning Surface (D-07)

| Layer | Where it lives | Change-time | Knobs |
|-------|---------------|-------------|-------|
| 1. JVM/runtime | launch flags | launch-only | (table here) |
| 2. Jetty/network | paralife.runtime.jetty.* | mostly launch | (table here) |
| 3. Application | paralife.runtime.app.* | mixed | (table here) |
| 4. Codec impl | internal | code change | (callout: not user-tunable) |

## §3 Per-Scale-Tier Recipes

### §3.1 100-bot tier (operator-friendly, BotRunner-class)
[launch flags + yaml overrides + cite jfr-100bots-baseline-c22e487.jfr]

### §3.2 500-bot tier (single-harness)
[same shape + cite jfr-500bots-baseline-c22e487.jfr]

### §3.3 1000-bot tier (M4 target)
[same shape + cite jfr-1000bots-baseline-c22e487.jfr + jfr-1000bots-tuned-{HEAD}.jfr]

## §4 Profile Findings

### §4.1 Methodology
[Plan 1 ritual; SHA-pinning per D-19]

### §4.2 Headline numbers (D-13 / D-18)
| Metric | 100 baseline | 100 tuned | 500 baseline | 500 tuned | 1000 baseline | 1000 tuned |
|--------|------|------|------|------|------|------|
| paralife.tick.health.work-time-ms (mean) | ... | ... | ... | ... | ... | ... |
| paralife.outbound.detach.timeout (count) | ... | ... | ... | ... | ... | ... |
| jdk.VirtualThreadPinned (events/min @ 20ms) | ... | ... | ... | ... | ... | ... |

### §4.3 Per-tier narrative
[walkthrough of each JFR + flamegraph; what was found; what was changed; per-knob delta]

### §4.4 Codec hot-path opts (D-10)
[per-opt entry: JFR signal that justified, code change summary, three-gate stack run record, tuned JFR delta]

## §5 Forward Notes

- Admin-UI live-tune (M5) — record fields tagged `@RefreshScope` ready
- Automated config search — CLI surface enables it
- Revisit-multiplex trigger (D-03) — only if Phase 21 hits 5000-conn ceiling
- Namespace consolidation (Phase 999.4) — fold backpressure key under runtime.app.outbound

## §6 Profile Index

| Filename | Scenario | Source SHA | Captured | Size | Notes |
|----------|----------|-----------|----------|------|-------|
| profiles/jfr-1000bots-baseline-c22e487.jfr | 1000 bots, 180s, balanced mix, seed=20251205 | c22e487 | 2026-05-XX | 4.8 MB | per Plan 1 |
| ... | | | | | |
```

This is approximately the same depth as `17-ADMISSION.md` (~25 KB) — long enough to be authoritative, short enough to be readable.

## §8 Three-Place Rationale Codification (D-02 / D-15 / D-16)

### CLAUDE.md insertion point

After line 142 (end of §Connection model). New subsection:

```markdown
### Runtime tuning (Phase 20)

Per-connection overhead reduction at scale lives in `paralife.runtime.jetty.*` and
`paralife.runtime.app.*` `@ConfigurationProperties` records (Phase 20 D-07). JVM
flags ship as documented per-scale-tier presets in
`.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md`, NOT
as wrapper scripts.

**The WS:entity 1:1 model from §Connection model is non-negotiable.** Tuning
reduces per-connection cost; it does not collapse connections. See `20-RUNTIME.md`
§1 for the full rationale and `20-RUNTIME.md` §3 for per-tier recipes.

The two metric gauges to watch when tuning are `paralife.tick.health.work-time-ms`
(AdmissionMetrics.java:65) and `paralife.outbound.detach.timeout`
(AdmissionMetrics.java:74). Profile artifacts under `.planning/phases/20-...
/profiles/` are pinned to commit SHAs for reproducibility.
```

### README.md insertion paragraph

Currently the file is a single line (`# paralife`). Plan 6 should add minimal structure with a Runtime tuning paragraph:

```markdown
## Runtime tuning

Paralife is built around many concurrent WebSocket connections — one per entity, by
design (see [`CLAUDE.md`](CLAUDE.md) §Connection model). At scale, per-connection
overhead is reduced via the four-layer tuning surface in
[`.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md`](.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md):
JVM flags, Jetty/network, application-level, and codec internals.
**Multi-entity-per-connection is not part of the design** — operational scale-out
is achieved by running more JVMs and more connections, not by collapsing them.
```

### Inline comment templates

**At `WorldWebSocketHandler.afterConnectionEstablished` near the WS upgrade site** (or near `outboundSender.attachSession(session, ...)`):

```java
// Phase 20 D-02 — WS:entity 1:1 is a deliberate architectural choice, not an
// optimisation gap. See 20-RUNTIME.md §1 (and 18-HARNESS.md §1, CLAUDE.md
// §Connection model). Tuning per-connection cost is the equivalent
// transport-level scale strategy (SCALE-08); collapsing entities onto a shared
// session would require explicit ADR per D-21 of Phase 18.
outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize());
```

**At `OutboundSender.attachSession` (or top of `drainLoop`):**

```java
// Phase 20 D-02 — One drain VT per session is structural per the WS:entity 1:1
// model (CLAUDE.md §Connection model). Per-VT cost is a few KB heap; 1000+ VTs
// is acceptable. Per-connection cost is reduced via paralife.runtime.* tuning
// (see 20-RUNTIME.md), not by sharing the drain VT across sessions.
Thread t = Thread.ofVirtual()
        .name("ws-sender-" + id)
        .start(() -> drainLoop(session, queue));
```

## Metadata

**Confidence breakdown:**
- Tooling (Jetty `addWebSocketConfigurer`, JFR commands, async-profiler basics): HIGH — verified against project code + official Jetty/Spring/Java docs.
- `@ConfigurationProperties` pattern: HIGH — direct project precedent (`AdmissionConfig`, `SpawnConfig`, `TickConfig`).
- Concrete per-tier GC/heap recommendations: NOT YET KNOWN — must come from Plan 1's JFR evidence; this research deliberately does not pre-commit to numbers.
- Codec hot-path candidates: MEDIUM — qualitative ranking is sound; specific shipped opts depend on Plan 1's JFR.
- Verification gate: HIGH — D-11 three-gate stack already exists in repo.
- Pinning risk: MEDIUM-HIGH — structurally certain it can happen; rate at 1000 bots is unknown until Plan 1 measures.

**Research date:** 2026-05-09
**Valid until:** ~2026-06-09 (30 days for stable; Java 21, Spring Boot 3.4.4, Jetty 12.0.18 are all pinned, so longer is fine. RECAPTURE if any of those bumps materially.)

---

*Phase: 20-connection-multiplexing-runtime-tuning*
*Researched: 2026-05-09*
