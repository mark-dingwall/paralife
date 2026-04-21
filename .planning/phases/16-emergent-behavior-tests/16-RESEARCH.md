# Phase 16: Emergent Behavior Tests - Research

**Researched:** 2026-04-21
**Domain:** Integration testing, deterministic seeding, statistical assertions, Micrometer instrumentation, trigger-watcher observers on Spring `@EventListener` tick pipeline
**Confidence:** HIGH for codebase facts (grep-verified), MEDIUM for tuning thresholds (D-12 config values require phase-execution calibration)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

Test scope layout
- **D-01:** Hybrid — two new integration tests: `CompositeFormationDeterminismTest` (R15, engine-direct, seeded) and `EmergenceStabilityLoadTest` (R16+R17+R18, full-stack, 100 bots, seeded-statistical).
- **D-02:** Minimise tick interval for tests (~10–20 ms) with modest safety buffer. Long-run wall-clock ≤90 s local; CI tolerant of minutes.
- **D-03:** R19 covered by existing CI `./gradlew test` gate — no new test artefact; verifier confirms full suite green.

Test surface
- **D-17:** R15 runs engine-direct — `@SpringBootTest` without web env; drives `TickEngine`/`SimulationEngine` in-thread; reads `WorldGrid`/`CompositeRegistry` via bean injection.
- **D-18:** R16/R17/R18 run full-stack — `@SpringBootTest(webEnvironment = RANDOM_PORT)`, real `BotClient` via `BotLauncher`, Jetty + codec + permessage-deflate end-to-end.

Emergent signals tracked (D-04, all during long-run)
1. Bonded-pair formation — assert count > 0 by tick N.
2. Composite formation — assert count > 0 if config permits; soft-check otherwise.
3. Predator pressure on STARVING prey — trigger-watcher: window W on STARVING appearance, rolling mean predator density within R > baseline.
4. RPS boom-bust cycle — weak sinusoidal/autocorrelation assertion on per-type series.
5. Flee-from-strong-predator — trigger-watcher, inverted: on buffed/bonded predator appearance, weaker-type density within R declines over W.
- **D-05:** Trigger-watcher pattern — per-candidate-entity observation window; per-tick samples; assertion at window close.

Documentation form (R17)
- **D-06:** Narrative markdown at `16-EMERGENCE.md` (no charts).
- **D-06b:** JSON fixture dump `.planning/phases/16-emergent-behavior-tests/fixtures/run-<timestamp>.json`; rollover N=5; directory gitignored.

Stability (R16)
- **D-07:** Three assertions (all must hold):
  1. No extinction — all 3 types alive at every snapshot checkpoint.
  2. Per-type floor — share ≥ 5 % for ≥ 80 % of ticks.
  3. Oscillation amplitude — rolling 200-tick window, `(max − min) / mean` ≥ 0.15 for ≥ 1 type.
- **D-08:** Long-run duration = 1000 ticks.

Seeding (D-09/D-09a/D-09b)
- Component-seeded with a single logged master seed.
- Master derives per-component seeds for: per-bot brains, env RNG, world init, tie-break RNG inside `ActionResolver`.
- Assertions are statistical — byte-exact snapshots explicitly rejected.
- `BotClient` ctor already accepts an injectable `Random`.
- Research must audit all server-side `Random`/`ThreadLocalRandom`/`Math.random` sites (this RESEARCH.md §RNG Audit).

Load stability (R18, reframed)
- **D-10:** R18 reframed from "no wire-parity regression" to capacity-headroom stability.
- **D-11:** Seven assertions (see table under §Load-Stability Instrumentation).
- **D-12:** Config forces composite formation (elevated bond/proximity/energy knobs).

Operator UAT scope
- **D-13:** Phase 16 is JUnit-only. No UAT script, no `runBot` evidence. Subjective UAT deferred to M5.

Emergence observability (paid forward to M5)
- **D-14:** Four Micrometer counters in `paralife.emergence.*`:
  - `paralife.emergence.bonded.pairs.formed`
  - `paralife.emergence.composites.formed`
  - `paralife.emergence.buffs.granted`
  - `paralife.emergence.mutagen.infections`
  - Bean pattern mirrors existing `metrics.WebSocketMetrics`.
- **D-15:** Grep-friendly `EMERGENCE` INFO log markers at each trigger site. Single-line format documented in CONTEXT.md.

### Claude's Discretion
- Exact `BondingConfig`/`CompositeConfig` values to force observable formation (informed by `CompositeFormationTest` knobs).
- Trigger-watcher window W and radius R.
- Per-component seed-derivation scheme (`SplittableRandom.split()` vs `masterSeed + tag.hashCode()`).
- Oscillation-amplitude floor (default 0.15 starting point).
- Heap measurement mechanism (`Runtime.freeMemory` vs Micrometer `jvm.memory.used`).
- `EmergenceMetrics` bean placement (`engine`, `websocket`, or `metrics`).
- Assertion order (fail-fast vs accumulate).
- `@Tag("slow")` for long-run test.

### Deferred Ideas (OUT OF SCOPE)
- Subjective human-observer UAT → M5 visualiser.
- Live operator HTML dashboard → M5.
- Per-session WS inspector / browser devtools → M5 global-observer endpoint.
- Bayesian / param-sweep tuning → post-MVP.
- Byte-stable fixture snapshots → rejected (too fragile vs virtual threads + HashMap iteration).
- Prometheus-scrape for emergence counters → M5.
- Chart/plot rendering → M5 or dedicated writeup phase.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description (from REQUIREMENTS.md) | Research Support |
|----|------------------------------------|------------------|
| R15 | Deterministic seed test for composite formation | §RNG Audit + §Seeding Surface; engine-direct pattern from `EnvironmentDeterminismTest`; `CompositeFormationTest` knobs for `BondingConfig`/`CompositeConfig`. |
| R16 | Population dynamics test with metabolism + environment | §Population-Observable Surface (`worldGrid.snapshot()` + `PopulationDynamicsTest` pattern); §Load-Stability Instrumentation for tick drift. |
| R17 | At least one emergent pattern documented | §Emergence-Counter Surface (D-14 sites); §Trigger-Watcher Pattern (D-05); `16-EMERGENCE.md` narrative; `run-<timestamp>.json` fixture. |
| R18 | Load test with composites — no regression | §Load-Stability Instrumentation; §LoadTest Harness Pattern; `LoadTest.java` reference for 100-bot, tick-drift check. |
| R19 | All v1.0 tests still pass | `./gradlew test` CI gate — no new artefact; verifier agent gate. |
</phase_requirements>

## Summary

Phase 16 produces two JUnit tests that close R15–R18 plus a narrative `16-EMERGENCE.md` for R17. CONTEXT.md locks the architecture: engine-direct for R15, full-stack `@SpringBootTest(RANDOM_PORT)` for R16/R17/R18; component-seeded statistical assertions (no byte-stable snapshots); four `paralife.emergence.*` Micrometer counters + `EMERGENCE` log markers as M5 seed instrumentation; feature-agnostic load-stability assertions (tick drift, tick-work budget, dropouts, heap growth, ERROR count, active-session gauge).

Three things in the codebase are load-bearing but need planner attention. First, ten distinct server-side RNG sites use `ThreadLocalRandom` / unseeded `new Random()` / `UUID.randomUUID()` — the `EnvironmentEngine` file comment at lines 1208–1216 explicitly acknowledges "the whole sim uses ThreadLocalRandom" as the honest boundary. For R15's deterministic-emergence claim we need to seed the hot-path subset that actually affects composite formation. Second, the existing `LoadTest` uses loose assertions (≥50 % bots still connected after 10 s) — R18's new capacity-headroom bar is materially stricter (tick drift < 10 %, 0 ERROR logs, 0 steady-state dropouts, active-session gauge stable), so we can't just copy it. Third, `BotLauncher` currently constructs `BotClient` with the 2-arg default ctor (`ThreadLocalRandom.current()`); seeded bots need a parallel launch path or a helper overload.

**Primary recommendation:** Follow the `EnvironmentDeterminismTest` pattern for R15 (engine-direct, autowired beans, `worldGrid.clear()` + `environmentEngine.resetForTest()` between runs, assertions comparing counter deltas and population fingerprints rather than byte-exact snapshots). Follow the `EnvironmentFullStackSmokeTest` + `PopulationDynamicsTest` hybrid for R16/R17/R18 (Jetty WebSocketClient, BotLauncher, autowired read-side beans, per-tick sampling via the existing `worldGrid.snapshot()` API — not via bot perception, which is zero-trust filtered). Introduce a thin `EmergenceMetrics` bean mirroring `WebSocketMetrics`; increment at the five precise sites identified below.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Deterministic composite-formation test (R15) | Engine (in-JVM bean-injected) | — | Pure sim-rules claim; WebSocket introduces non-determinism (virtual-thread I/O, Jetty timers) — D-17. Authoritative state is on `WorldGrid` / `CompositeRegistry` beans. |
| Full-stack long-run emergence test (R16/R17/R18) | Full tier stack (Jetty + codec + tick pipeline + bots) | — | Claims are about the running system; D-18 mandates real transport so integration-level issues (dropouts, ERROR logs, extension negotiation) are detectable. |
| Emergence counters | `metrics` package (new `EmergenceMetrics` bean) | Read from `engine.*` trigger sites + `websocket.TickBroadcaster` (bond formation event is currently produced inside `SimulationEngine`, not broadcast-time) | Mirrors `metrics.WebSocketMetrics` (Phase 15 Plan 10). Single counter bean owns all four meters; increment sites live where the event is observed. |
| Load-stability metrics (tick drift, tick work, heap, active-session) | `engine.TickEngine` (tick timing) + `metrics` (expose) | `websocket.SessionRegistry` (session gauge already exists) + JVM `Runtime` (heap) | Per-tick elapsed is already measured in `TickEngine.tickLoop` line 91–97 but only log-warned; expose as Micrometer meter. Active-session gauge already published (Phase 15). |
| Fixture JSON dump | Test harness (not production code) | — | Pure test-side concern. `java.nio.file.Files` + `Comparator` rollover. |
| Trigger-watcher per-entity observer | Test harness reading `WorldGrid` + `BotRegistry` + `BuffRegistry` via autowired injection | — | Do NOT add a production `@EventListener` — keep observers test-scoped. Drive via `@EventListener(TickEvent)` with `@Order(200)` in the test class (runs after `TickBroadcaster`@50, `WebSocketKeepaliveService`@200 is already the tail; tie-break with @Order(201)). |

## Standard Stack

### Core (verified against codebase)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 5 | via `spring-boot-starter-test` 3.4.4 | Test framework | Already 166 tests on this harness. |
| Spring Boot Test | 3.4.4 | `@SpringBootTest` + `@TestPropertySource` | Used by every integration test in `src/test/java/com/paralife/engine/*IntegrationTest.java`. |
| AssertJ | via starter-test | Fluent assertions | Pattern used throughout (`assertThat(...).as(...).isEqualTo(...)`). |
| Micrometer `MeterRegistry` | transitive via `spring-boot-starter-actuator` 3.4.4 | Counter/Gauge/DistributionSummary | Already in use by `metrics.WebSocketMetrics` (Phase 15 Plan 10). |
| Jetty 12 WebSocket client | `org.eclipse.jetty.websocket:jetty-websocket-jetty-client:12.0.18` | Raw Jetty client for full-stack tests | Pinned version already in `build.gradle.kts` line 33; used by `EnvironmentFullStackSmokeTest`, `BotClient`, `BotClientClosesOnMissingServerDeflateTest`. |
| Spring Boot Actuator | 3.4.4 | `/actuator/metrics/*` exposure | Already configured in `application.yml` (`management.endpoints.web.exposure.include=health,info,metrics`). |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jackson (via Spring) | transitive | JSON fixture serialization (D-06b) | `new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), data)` — no new dep. |
| `java.nio.file.Files` + `Comparator` | JDK 21 | N=5 rollover of fixture files | See §Fixture Rollover. |
| `java.util.SplittableRandom` | JDK 21 | Master → per-component seed derivation | `SplittableRandom.split()` gives uncorrelated derived streams. Alternative: `new Random(masterSeed ^ "bot-0".hashCode())`. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `@SpringBootTest(RANDOM_PORT)` for R16/R17/R18 | Engine-direct via `ApplicationEventPublisher.publishEvent(new TickEvent(n))` | Locked by D-18 — full-stack mandated. Engine-direct would miss dropout/ERROR-log checks. |
| Inject `Random` into every RNG site | Inject a single `RandomSource` bean façade | `RandomSource` adds indirection not currently modelled; `Random` matches the existing `BotClient`/`EnvironmentEngine` injection pattern. Planner's call — noted in CONTEXT.md §Claude's Discretion. |
| Record bot-observed perceptions for emergence signals | Autowire `WorldGrid`/`CompositeRegistry` server-side beans | Bot perception is zero-trust filtered (3-layer env state projection per CLAUDE.md) — the wire bitmask under-reports global overcrowding and omits entity ids. For R16/R17, test must read authoritative server state. |
| `Thread.sleep()` + wall-clock loop | Inject `ApplicationEventPublisher` and call `publishEvent(new TickEvent(n))` directly | For R15 (engine-direct, `paralife.tick.auto-start=false`), direct publish is deterministic. For R16/R17/R18, tick loop must run live so WebSocket dispatch races are included in the coverage (per D-18). |

**Verification:** Each library version above is already in `build.gradle.kts` or transitive via Spring Boot 3.4.4 BOM. No new dependency additions required.

## Architecture Patterns

### System Architecture (as it affects this phase)

```
Test harness (Junit @Test)
  ├─ (R15 engine-direct) Autowire WorldGrid, SimulationEngine, CompositeRegistry
  │                      + ApplicationEventPublisher for direct TickEvent drive
  │                      + master seed → derive per-component seeds
  │                      + run N ticks in-thread, read CompositeRegistry.size()
  │                      + @DirtiesContext to reset bean state between runs
  │
  └─ (R16/R17/R18 full-stack)
      │
      ├─ Jetty client side: BotLauncher.launch(uri, 100) → 100 BotClient (seeded)
      │
      ├─ Spring side:
      │   TickEngine (virtual thread, 10-20ms interval)
      │     └─ publishEvent(TickEvent)
      │          ├─ SimulationEngine @Order(10)   ──┐
      │          │    (combat, bonds, composites    │  ← emergence counter
      │          │     formation, death, nutrients) │    increment sites
      │          ├─ EnvironmentEngine @Order(14)    │  (Plans 16-01 / 16-02)
      │          ├─ CompositeEnergyDistributor @15  │
      │          ├─ ActionResolver @Order(20)       │
      │          ├─ EnvPostActionReconciler @25     │
      │          ├─ (new) TickWorkStartTimer @0     │  ← brackets pipeline
      │          ├─ PerceptionBroadcaster @50       │
      │          ├─ TickBroadcaster @50             │
      │          ├─ WebSocketKeepaliveService @200  │
      │          └─ (new) TickWorkEndTimer @201     │  ← records elapsed ns
      │
      └─ Test-side observers (autowired):
          ├─ WorldGrid                 → population snapshots per tick
          ├─ CompositeRegistry         → R16 composite count, R17 signal #2
          ├─ BuffRegistry              → R17 signal #5 (buffed-predator)
          ├─ BotRegistry               → R17 active-session gauge
          ├─ EnvCleanupHooksBean       → infection map for R17 signal #4
          ├─ MeterRegistry             → all four emergence counters + tick-work
          └─ TestLogCapture (Logback)  → ERROR count + EMERGENCE markers
```

### Recommended Test Structure

```
src/test/java/com/paralife/engine/
  CompositeFormationDeterminismTest.java    # R15 — engine-direct
  EmergenceStabilityLoadTest.java           # R16 + R17 + R18 — full-stack long-run
  emergence/
    TriggerWatcher.java                     # Per-entity observation window (D-05)
    PopulationHistory.java                  # Per-tick snapshots + oscillation math
    RunFixtureWriter.java                   # run-<ts>.json + N=5 rollover
    TestLogCapture.java                     # ListAppender for ERROR + EMERGENCE
    SeededBotLauncher.java                  # Parallel to BotLauncher, seeds per-bot Random

src/main/java/com/paralife/metrics/
  EmergenceMetrics.java                     # @Component, 4 counters — mirrors WebSocketMetrics
  TickWorkTimer.java                        # @EventListener bracketing pipeline (or inline in TickEngine — see §Tick-Work Timing)

src/main/java/com/paralife/engine/RandomSource.java  # OPTIONAL — seed-injection façade bean
```

### Pattern 1: Engine-Direct Deterministic Test (R15)

Verbatim from `EnvironmentDeterminismTest.java:37-45, 110-160` (proven pattern in this codebase).

```java
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",          // seeds EnvironmentEngine rng
        "paralife.tick.auto-start=false",              // drive ticks manually
        // bumped knobs from D-12 to force composite formation in 1000-tick window
        "paralife.bonding.bonding-probability=0.5",
        "paralife.bonding.bond-energy-threshold=20",
        "paralife.composite.can-form-composites=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class CompositeFormationDeterminismTest {
    @Autowired WorldGrid worldGrid;
    @Autowired SimulationEngine simulationEngine;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired EmergenceMetrics metrics;

    @Test
    void sameSeedYieldsSameCompositeCount() {
        long masterSeed = 42L;
        int runs = 3;
        List<Integer> compositeCounts = new ArrayList<>();
        for (int r = 0; r < runs; r++) {
            seedWorld(masterSeed);                     // place BondedPairs deterministically
            for (long t = 1; t <= 200; t++) {
                simulationEngine.processTick(t);       // direct call — no event listeners
            }
            compositeCounts.add(compositeRegistry.size());
            resetBetweenRuns();
        }
        assertThat(new HashSet<>(compositeCounts))
            .as("seed=%d — all %d runs must produce identical composite counts", masterSeed, runs)
            .hasSize(1);
    }
}
```

**Source:** `src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java:37-160` [VERIFIED].

### Pattern 2: Full-Stack Long-Run Test (R16/R17/R18)

Skeleton borrowed from `EnvironmentFullStackSmokeTest.java` + `PopulationDynamicsTest.java` + `LoadTest.java`.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=15",                 // D-02: 10-20ms
        "paralife.tick.auto-start=true",
        "paralife.world.width=64",
        "paralife.world.height=64",
        // D-12 forced composite config
        "paralife.bonding.bonding-probability=0.4",
        "paralife.bonding.bond-energy-threshold=30",
        "paralife.composite.can-form-composites=true",
        // env on (R17 signal #4 mutagen-infections + signal #5 buffs)
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.events.lightning.peak-lambda=0.1",
        "paralife.simulation.events.mutagen.peak-lambda=0.08"
})
// @Tag("slow")  // planner's call
class EmergenceStabilityLoadTest {
    @LocalServerPort int port;
    @Autowired WorldGrid worldGrid;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired BuffRegistry buffRegistry;
    @Autowired SessionRegistry sessionRegistry;
    @Autowired MeterRegistry meterRegistry;
    @Autowired EmergenceMetrics emergenceMetrics;

    private final SeededBotLauncher launcher = new SeededBotLauncher();
    private TestLogCapture logCapture;

    @BeforeEach void setUp() { logCapture = TestLogCapture.attach(); }
    @AfterEach  void tearDown() { launcher.shutdown(); logCapture.detach(); }

    @Test
    void longRunStabilityAndEmergence() throws Exception {
        long masterSeed = System.nanoTime();            // logged for repro
        log.info("master-seed={}", masterSeed);

        String uri = "ws://localhost:" + port + "/ws/world";
        var bots = launcher.launchSeeded(uri, 100, masterSeed);

        PopulationHistory history = new PopulationHistory();
        TriggerWatcher starvationWatcher = TriggerWatcher.forStarving(...);
        TriggerWatcher buffedPredatorWatcher = TriggerWatcher.forBuffed(...);

        int targetTicks = 1000;
        long deadlineMs = System.currentTimeMillis() + 90_000;
        while (history.tickCount() < targetTicks && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(15);
            history.sample(worldGrid, compositeRegistry, buffRegistry, botRegistry);
            starvationWatcher.tickIfWindowActive(history);
            buffedPredatorWatcher.tickIfWindowActive(history);
        }

        // D-07 assertions + D-04 signals + D-11 load-stability assertions
        assertThat(history.noExtinctionAtCheckpoints()).isTrue();
        assertThat(history.typeFloorSatisfiedFor(0.05, 0.80)).isTrue();
        assertThat(history.rollingAmplitude(200).orElse(0.0)).isGreaterThanOrEqualTo(0.15);

        assertThat(emergenceMetrics.bondedPairsFormed()).isGreaterThan(0);
        assertThat(logCapture.errorCount()).isZero();
        assertThat(history.tickDriftPercent()).isLessThan(10.0);
        // ... (full D-11 table — see §Load-Stability Instrumentation)

        RunFixtureWriter.dump(history, emergenceMetrics, Path.of(".planning/phases/16-emergent-behavior-tests/fixtures"));
    }
}
```

### Anti-Patterns to Avoid

- **Reading bot perception to assert emergence.** Zero-trust projection (CLAUDE.md §Env state projection) strips entity IDs and rewrites OVERCROWDED per-bot. Any assertion based on `Frame.TickFrame` cell data will under-report. Read `WorldGrid.snapshot()`, `CompositeRegistry.getAll()`, `BuffRegistry`, `EnvCleanupHooksBean.getInfections()` via Spring injection instead.
- **Byte-exact fixture snapshots.** Explicitly rejected in D-09. Virtual-thread scheduling + HashMap iteration order make byte-stability impossible across JDK patch versions. Use statistical thresholds (count ≥ K, share ≥ X %, amplitude ≥ Y).
- **Adding a new `@EventListener` in production code for observation.** Phase-16 observers live in the test class — any production listener would ship in release builds for a test-only purpose. Counter increments at existing trigger sites are the only production-code additions (D-14).
- **Calling `Thread.sleep(tickCount * intervalMs)` to wait a target tick count.** `TickEngine` is a virtual-thread loop — sleep slippage accumulates. Sample periodically and break when `TickEngine.getCurrentTick() >= target` (or use `history.tickCount()` from the `PerceptionBroadcaster` frame count if already sampling).
- **Asserting on `CompositeFormationTest` default `BondingConfig.defaults()` (0.1 probability, 50 threshold) in the long-run.** Those defaults produce near-zero observable bonding in 1000 ticks at 100 bots — D-12 explicitly requires elevated knobs. Plausible starting values: `bondingProbability=0.3-0.5`, `bondEnergyThreshold=20-30`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Counter/Gauge/Summary | Custom `AtomicLong` exposed via ad-hoc endpoint | Micrometer `MeterRegistry` — already autowired everywhere after Phase 15 | `metrics.WebSocketMetrics` is the blessed pattern; `/actuator/metrics/<name>` falls out free. |
| Capturing log entries for assertions | Parsing stdout | `ch.qos.logback.core.read.ListAppender` attached to `(Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)` | Spring Boot's default logger is Logback; ListAppender is the canonical test-side capture. No new dep. |
| Bot orchestration at scale | Raw Jetty WebSocketClient per bot | `BotLauncher` (extend to `SeededBotLauncher`) | `BotLauncher` already handles virtual-thread connect/await-registered. Subclass or wrap. |
| Waiting for tick count | Manual `Thread.sleep(N * interval)` | Sample a `BotClient.getPerceptionCount()` or `TickEngine.getCurrentTick()` | Sleep slippage inherent; sampling is cheap and accurate. |
| Round-robin per-bot seeding | Hashing strings manually | `SplittableRandom` tree: `masterRng.split()` per bot/component | `SplittableRandom` JDK 8+; uncorrelated streams by design. |
| Heap leak detection | JFR, heap dumps | `Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()` at checkpoints | CONTEXT.md §Claude's Discretion suggests "whichever is simplest"; `Runtime` is zero-dep. Alternative: `meterRegistry.find("jvm.memory.used").gauge()` (already published by Spring Boot Actuator). |

**Key insight:** Every mechanism this phase needs is already in the codebase or the JDK. No new libraries, no new dependency entries in `build.gradle.kts`. The scope is pure wiring + test authoring.

## Runtime State Inventory

> Phase 16 is test-authoring and observability instrumentation. Two runtime-state concerns:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — fixtures are gitignored per-run JSON, not a database. | None. |
| Live service config | None — emergence counters live in `MeterRegistry`, published via actuator. No external dashboard, no external config store. | None. |
| OS-registered state | None — no Windows/systemd registration, no pm2 process names. | None. |
| Secrets/env vars | None — no secrets in scope. Config is `paralife.*` yaml + `@TestPropertySource`. | None. |
| Build artifacts | New test files in `src/test/java`; optional `EmergenceMetrics.class`, `TickWorkTimer.class` compiled into the main jar. Reinstall is via `./gradlew test` — automatic. | None (standard build). |

**Nothing found in other categories:** Verified by (1) the RNG audit grep, (2) the CONTEXT.md review, (3) the existing `.gitignore` inspection (only `.gradle/`, `build/`, IDE + OS + Spring Boot logs; no runtime-state paths). The `.planning/phases/16-emergent-behavior-tests/fixtures/*.json` directory will need a single new `.gitignore` entry (D-06b).

## RNG Audit (D-09b)

Exhaustive grep over `src/main/java` for `ThreadLocalRandom` | `new Random(` | `Math.random` | `new SecureRandom` | `SplittableRandom` | `UUID.randomUUID`. **No `Math.random` or `SecureRandom` usage found anywhere.**

**Legend:** HOT = called per tick or per event during sim; WARM = called at world init / spawn / respawn; COLD = called once at startup (rock generation).

| # | File:line | Call site | Current source | Hotness | Affects emergence (R15/R16)? | Injection strategy |
|---|-----------|-----------|----------------|---------|------------------------------|---------------------|
| 1 | `engine/SimulationEngine.java:238` | `Collections.shuffle(particlePositions, ThreadLocalRandom.current())` — combat iteration order | `ThreadLocalRandom` | HOT | **YES — directly affects combat winner order, composite formation eligibility** | Inject `Random simRng` via ctor; pass to shuffle. |
| 2 | `engine/SimulationEngine.java:241` | `ThreadLocalRandom rng = ThreadLocalRandom.current()` — used at lines 262, 294, 323 for bonding roll, bondDefenseChance, DEFENDER deflection | `ThreadLocalRandom` | HOT | **YES — bonding probability check is the R15 core event** | Inject `Random simRng` (same as #1). |
| 3 | `engine/SimulationEngine.java:446` | `Collections.shuffle(bondedPairPositions, ThreadLocalRandom.current())` — composite formation iteration order | `ThreadLocalRandom` | HOT | **YES — changes which pair wins the first composite slot** | Use `simRng` from #1. |
| 4 | `engine/SimulationEngine.java:556, 567, 568` | `UUID.randomUUID().toString().substring(0, 8)` — composite + member IDs | `UUID.randomUUID()` | HOT | NO — only affects ID strings, not any probability or position | Can stay. For fingerprint-stable fixtures, swap for `"composite-" + compositeIdCounter.incrementAndGet()`. Optional. |
| 5 | `engine/SimulationEngine.java:942` | `ThreadLocalRandom.current().nextDouble() < compositeConfig.dissolutionChance()` — member-death dissolution roll | `ThreadLocalRandom` | HOT | **YES — affects composite lifetime, R16 population curves** | Use `simRng`. |
| 6 | `engine/SimulationEngine.java:1079` | `ThreadLocalRandom.current().nextDouble() < shatterProb` — progressive panic-zone shatter | `ThreadLocalRandom` | HOT | YES — affects composite dissolution rate | Use `simRng`. |
| 7 | `engine/SimulationEngine.java:1090, 1105` | `ThreadLocalRandom rng` + `rng.nextDouble() < effectiveRate` — nutrient spawning per empty cell | `ThreadLocalRandom` | HOT | **YES — affects nutrient availability → energy → population dynamics** | Use `simRng`. |
| 8 | `engine/ActionResolver.java:520` | `ThreadLocalRandom.current().nextDouble() < profile.bonusOffspringChance()` — bonus offspring on reproduce | `ThreadLocalRandom` | HOT | YES (but `bonusOffspringChance=0.0` in production defaults → dead code at default config). Still needs seeding if tests elevate. | Inject `Random actionRng`. |
| 9 | `engine/CompositeEnergyDistributor.java:77` | `Collections.shuffle(memberIds, ThreadLocalRandom.current())` — fair energy distribution order | `ThreadLocalRandom` | HOT | YES — member starvation order under energy scarcity | Inject `Random compositeRng` (or reuse `simRng`). |
| 10 | `engine/FertilityInitializer.java:46-55` | `ThreadLocalRandom rng = ThreadLocalRandom.current()` — fertility patch placement at `@PostConstruct` | `ThreadLocalRandom` | WARM (world init) | YES — spatial nutrient distribution affects emergence pattern | Inject `Random fertilityRng` via ctor; currently constructs at PostConstruct. |
| 11 | `engine/EnvironmentEngine.java:220, 1164` | `config.seed() == null ? new Random() : new Random(config.seed())` + `resetForTest` rebuild | seeded `Random` if `EnvironmentConfig.seed` set | HOT (Poisson rolls, gossip, mutagen spread, toxin spawn) | YES (already seeded via `paralife.simulation.events.seed=42`) | **ALREADY SEED-INJECTABLE** — no change needed. D-09 master-seed derivation writes `paralife.simulation.events.seed` via `@TestPropertySource`. |
| 12 | `engine/ToxinPathGenerator.java:36, 44` | `this(new Random())` in prod ctor; `ToxinPathGenerator(Random)` test-only | unseeded `new Random()` in production path | WARM (per toxin spawn) | YES (toxin path placement) | `EnvironmentEngine:229` constructs `new ToxinPathGenerator()` — unseeded. Planner should change this to pass the engine's own seeded `rng` down, OR accept that toxin *path shape* is non-deterministic (lambdas + origins are seeded; only waypoint jitter isn't). |
| 13 | `world/Entity.java:265, 286, 295` | `ThreadLocalRandom rng = ThreadLocalRandom.current()` in `BondedPair.formBond()` — hybrid vigor + decay cost ranges | `ThreadLocalRandom` | HOT (called at every bond formation) | **YES — affects BondedPair energy/combat/attack stats; composite formation pool energy derives from these** | Change signature: `formBond(..., ThreadLocalRandom rng)` → `formBond(..., RandomGenerator rng)` (accepts both `ThreadLocalRandom` and seeded `Random`). Caller in `SimulationEngine:505` passes its injected `simRng`. |
| 14 | `world/RockGenerator.java:121-122` | `config.seed == 0 ? new Random(ThreadLocalRandom.current().nextLong()) : new Random(config.seed)` | seeded `Random` if `paralife.world.rock.seed != 0` | COLD (one-shot at startup) | NO — terrain placement is static; seed already controllable via yaml | **ALREADY SEED-INJECTABLE** via `paralife.world.rock.seed` (R28, Phase 15). Test sets this in `@TestPropertySource`. |
| 15 | `bot/BotClient.java:76, 82, 86, 95` | Ctor default: `ThreadLocalRandom.current()` for `rng`; 6-arg ctor accepts `Random` | `Random` — injectable | HOT (respawn jitter) | YES (respawn timing) | **ALREADY SEED-INJECTABLE**, but `BotLauncher.launch()` uses the 2-arg ctor. Fix: `SeededBotLauncher` wires the 6-arg ctor with per-bot derived seeds. |
| 16 | `bot/BotClient.java:294` | `ThreadLocalRandom.current().nextLong(respawnJitterMs)` inside `handleDeath()` | `ThreadLocalRandom` directly — **BYPASSES the injected `rng` field!** | HOT (every respawn) | YES (respawn jitter affects bot clustering, session-cap interaction) | **BUG / Phase 16 fix-site:** change `ThreadLocalRandom.current().nextLong(...)` → `rng.nextLong(...)` (line 294). Single-line fix; restores the ctor's seeded-Random contract. |
| 17 | `websocket/WorldWebSocketHandler.java:191` | `ThreadLocalRandom rng = ThreadLocalRandom.current()` — initial spawn/respawn position placement | `ThreadLocalRandom` | WARM (per register/respawn) | YES — starting positions affect emergence pattern | Inject `Random spawnRng` via ctor. Low-risk — single file. |

**Summary:**
- **10 sites** affect emergence and need seeding for R15 determinism (sites #1–3, #5–10, #13, #17 — counted as distinct injection points; #1/#2/#3 share one `simRng`).
- **3 sites** are already seed-injectable but wired unseeded in production (#11 WARM unseeded default, #12 unseeded ToxinPathGenerator, #15 `BotLauncher` uses 2-arg ctor). Seeding is a wiring change, not a signature change.
- **1 site is a bug** (#16 `BotClient.handleDeath` bypasses its injected `rng`) — fix incidentally.
- **1 site is cosmetic** (#4 `UUID.randomUUID` for ID strings — doesn't affect probability outcomes; skip unless byte-stable fixtures are ever revived).
- **1 site is COLD** (#14 rock seed) and already controllable via yaml — no change.

**Planner's scoping question:** D-09b says "those in the tick pipeline hot path" are in-scope. The 10-site list above is the complete hot-path set. The planner should scope either (a) all ten via a single `RandomSource` bean façade, or (b) the minimum set needed for R15 composite-formation determinism only (#1–3, #5, #13 suffice — composite formation is the R15 claim).

**Recommended minimum set for R15 determinism:** #1, #2, #3, #5, #13. These cover combat iteration order, bonding probability roll, composite-formation iteration order, dissolution roll, and BondedPair formation ranges. Plus #17 for starting-position determinism.

## Emergence-Counter Surface (D-14)

For each of the four `paralife.emergence.*` counters, the exact increment site in existing code:

| Counter | Increment site | Why there | Accompanying `EMERGENCE` log marker |
|---------|---------------|-----------|-------------------------------------|
| `paralife.emergence.bonded.pairs.formed` | `engine/SimulationEngine.java:505` — inside the `for (InteractionResult result : results)` loop, after `worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair)` (line 521). | That's the one atomic site where a `BondedPair` transitions from "scheduled" to "placed on grid" — any earlier and the formation could still be claimed by a competing bond (line 492 guard). | `log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})", tick, primary.type().name().charAt(0), secondary.type().name().charAt(0), bond.primaryPos.x(), bond.primaryPos.y())`. |
| `paralife.emergence.composites.formed` | `engine/SimulationEngine.java:576-578` — where `worldGrid.setEntity` places both `CompositeMember`s after the `CompositeFormation` result is applied. | Same atomicity reasoning — earlier than the two-cell write could double-count if the guard at line 550 rejects. | `log.info("EMERGENCE composite-formed tick={} size=2 compositeId={} role-mix=[{},{}]", tick, compositeId, role1, role2)`. |
| `paralife.emergence.buffs.granted` | `engine/BuffRegistry.java:72` — inside `grant(...)` method, **only on the `list.add(new ActiveBuff(...))` branch** (new buff, not the expiry-refresh branch on line 67). | `BuffRegistry.grant` is called from `EnvironmentEngine.grantBuff` (buff-on-mutagen-cure, D-15) and from `EnvPostActionReconciler`. Counting only the new-buff branch avoids double-counting expiry refreshes. | `log.info("EMERGENCE buff-granted tick={} entity={} buff={} expiry={}", tick, entityId, type, expiryTick)`. Tick is not currently a parameter of `grant()` — may need to thread `currentTick` through, or call the logger from the caller side. |
| `paralife.emergence.mutagen.infections` | `engine/EnvironmentEngine.java` — search for `infections.put(...)` or the site where a new `Infection` entry is first written to `envCleanupHooksBean.getInfections()`. The existing `getMutagenInfectionEventCount()` at line 1188 already exposes this count as a pass-through; the counter just mirrors it. | Single source of truth. `getMutagenInfectionEventCount()` already used by `EnvironmentFullStackSmokeTest:233` and `EnvironmentPhaseGateIntegrationTest`. | `log.info("EMERGENCE infection-started tick={} entity={} strain={}", tick, entityId, strain)` at the same site (strain is line 443 `int strain = 1 + rng.nextInt(255)` area — the strain index is not currently stored per-infection; may need inspecting `Infection` record). |

**Bean shape (mirrors `metrics/WebSocketMetrics.java:30-49`):**

```java
@Component
public class EmergenceMetrics {
    public static final String M_BONDED_PAIRS   = "paralife.emergence.bonded.pairs.formed";
    public static final String M_COMPOSITES     = "paralife.emergence.composites.formed";
    public static final String M_BUFFS_GRANTED  = "paralife.emergence.buffs.granted";
    public static final String M_INFECTIONS     = "paralife.emergence.mutagen.infections";

    private final Counter bondedPairs;
    private final Counter composites;
    private final Counter buffsGranted;
    private final Counter infections;

    public EmergenceMetrics(MeterRegistry registry) {
        this.bondedPairs  = Counter.builder(M_BONDED_PAIRS).register(registry);
        this.composites   = Counter.builder(M_COMPOSITES).register(registry);
        this.buffsGranted = Counter.builder(M_BUFFS_GRANTED).register(registry);
        this.infections   = Counter.builder(M_INFECTIONS).register(registry);
    }

    public void incBondedPair()   { bondedPairs.increment(); }
    public void incComposite()    { composites.increment(); }
    public void incBuffGranted()  { buffsGranted.increment(); }
    public void incInfection()    { infections.increment(); }

    // Test accessors
    public double bondedPairsFormed() { return bondedPairs.count(); }
    public double compositesFormed()  { return composites.count(); }
    public double buffsGrantedCount() { return buffsGranted.count(); }
    public double infectionsStarted() { return infections.count(); }
}
```

**Injection sites:** `SimulationEngine` and `BuffRegistry` both already have several `@Autowired` collaborators — add `EmergenceMetrics` to each ctor. `EnvironmentEngine` is the most complex injection target (11 collaborators already); adding a 12th is mechanically fine but test constructors will need updating.

## Trigger-Watcher Pattern (D-05)

The observation window for signals #3 (predator pressure on STARVING prey) and #5 (flee-from-strong-predator). This is a **test-side** component, never a production `@EventListener` (see Anti-Patterns).

**Mechanism:**

```java
class TriggerWatcher {
    private final Predicate<EntitySnapshot> trigger;   // e.g., isStarving
    private final Predicate<EntitySnapshot> observer;  // e.g., isPredatorOf(triggerType)
    private final int windowTicks;                     // W
    private final int radius;                          // R
    private final double thresholdBaseline;            // baseline density
    private final boolean directionUp;                 // signal #3: YES; signal #5: NO

    private final List<ActiveWindow> activeWindows = new ArrayList<>();

    void tickIfWindowActive(PopulationHistory history) {
        // 1. Scan new triggers in this tick's snapshot.
        for (EntitySnapshot e : history.latestEntities()) {
            if (trigger.test(e) && !alreadyTracking(e.id())) {
                activeWindows.add(new ActiveWindow(e, history.currentTick(), windowTicks));
            }
        }
        // 2. For each active window, sample observer density within radius.
        activeWindows.removeIf(w -> w.sampleOrClose(history, observer, radius));
    }

    List<WindowResult> results() { return closedWindows; }  // drained after test loop
}
```

**Trigger detection sources:**
- **STARVING entities (signal #3):** `Cell.flags` carries `FLAG_STARVING` per CLAUDE.md. Scan the snapshot: `worldGrid.snapshot().getCell(x,y).flags() & FLAG_STARVING != 0`. (Note: STARVING lives on `Cell.flags`, NOT on `entityStatus` bitmask per CLAUDE.md §Env state projection.)
- **Buffed predators (signal #5):** `buffRegistry.getBuffs(entityId)` non-empty list. Any of ATTACK_PLUS_1 / MOVEMENT_PLUS_1 / SENSOR_PLUS_1 / UPKEEP_MINUS_1 counts. Bonded predators: entity is `BondedPair` instance.

**Observer density sampling:** Iterate cells within `radius` (Chebyshev distance, toroidal via `Math.floorMod`) around the trigger cell's position; count entities matching the `observer` predicate.

**Window close:** When `history.currentTick() - window.startTick >= W`, compute rolling mean of samples, compare to baseline, record outcome. If `directionUp` (signal #3), assert `mean > baseline + margin`; else (signal #5), assert `mean < baseline - margin`.

**Suggested starting values (Claude's Discretion per CONTEXT.md):**
- `W = 20 ticks` (balance signal-to-noise; at 15 ms/tick = 300 ms window)
- `R = 5` cells (matches bot perception vision but not zero-trust — we read server state)
- `thresholdBaseline` = pre-window mean density sampled in the 20 ticks before the trigger fired

**@Order slot for test observer:** `WebSocketKeepaliveService` is already `@Order(200)` (final listener currently). For the test-side sampler, the cleaner pattern is polling from the `@Test` method body after a `Thread.sleep(intervalMs)` rather than adding a new listener — keeps tick-pipeline production code untouched.

## Load-Stability Instrumentation (D-11)

Seven D-11 assertions mapped to measurement mechanisms:

| Metric | Assertion | Current availability | Wiring needed |
|--------|-----------|----------------------|---------------|
| Tick drift | < 10 % over steady-state | **Gap.** `TickEngine.tickLoop` line 91–97 computes `elapsed` + warns on overrun, but doesn't publish a running drift metric. | Add `paralife.tick.drift.ratio` gauge OR compute in test: `(observedTicks / expectedTicks) * 100 - 100` using `TickEngine.getCurrentTick()` and wall-clock deltas. |
| Mean tick work time | ≤ 50 % of tick budget | **Gap.** No tick-work timing metric published currently. | Add `paralife.tick.work.ms` DistributionSummary (Micrometer). Record from `TickWorkTimer` bracketing listeners at `@Order(0)` (start timestamp ThreadLocal) and `@Order(201)` (stop + record). Read via `meterRegistry.find("paralife.tick.work.ms").summary().mean()`. |
| p99 tick work time | ≤ 90 % of tick budget | Same as above | Same meter; `.summary().takeSnapshot().percentileValues()` for p99. Ensure `publishPercentiles(0.5, 0.95, 0.99)` on builder. |
| Session dropouts (steady state) | == 0 | **Partial.** `SessionRegistry.unregister` fires on any close. Need to distinguish startup-connect churn from mid-run drops. | Sample `sessionRegistry.getSessionCount()` periodically; define "steady state" as ticks > 50; assert no decrease except when bot explicitly disconnects. Simpler: subscribe to WebSocket close events via `WorldWebSocketHandler` logs or track a new monotonic `totalDropouts` counter. |
| Heap growth | last 200 ticks vs first 200 post-warmup < 20 % delta | **Available via JDK.** `Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()` any time. Already published by Spring Boot Actuator as `jvm.memory.used` (tag `area=heap`). | Sample at ticks 50, 250, 800, 1000. Assert `mean(ticks[800..1000]) < 1.20 * mean(ticks[50..250])`. JFR not needed. |
| ERROR-level log entries | == 0 during run | **Gap.** No test-side capture. | `TestLogCapture` — attach Logback `ListAppender` to root logger in `@BeforeEach`, filter `event.getLevel() == Level.ERROR` in `@AfterEach`. Detach after test to avoid leak. |
| Active-session gauge | == configured count throughout | **Available.** `paralife.ws.active.sessions` already published via `WebSocketMetrics` (Phase 15 Plan 10); `SessionRegistry.register/unregister` drives it. | Sample via `meterRegistry.find("paralife.ws.active.sessions").gauge().value()`. Compare `== 100` at mid-run and end-of-run checkpoints (skip first 50 ticks for connect transient). |

**Tick-work timer design decision:**

Two options; planner picks:

1. **Dedicated listener bracket (recommended).** Two new beans:
   - `TickWorkStartTimer` `@Order(Ordered.HIGHEST_PRECEDENCE)` — stores `nanoTime()` in a `ThreadLocal<Long>`.
   - `TickWorkEndTimer` `@Order(201)` (one after keepalive's 200) — reads ThreadLocal, records elapsed into the DistributionSummary.
   - Advantage: no changes to `TickEngine`. Disadvantage: relies on Spring's listener dispatch being sequential on the same thread — it is (Spring publishes events synchronously on the publisher's thread unless `@Async`).
2. **Inline in `TickEngine.tickLoop`** — already measures `elapsed` on line 91. Add a `@Autowired(required=false) MeterRegistry` and record there.
   - Advantage: one-liner addition. Disadvantage: measures only through `publishEvent()` return, which includes all listeners — same scope as option 1 — so equivalent.

Option 2 is smaller. Recommend option 2 for minimum diff.

## Seeding Surface (D-09 / D-09a)

Master-seed flow:

```
masterSeed (long, set by test — usually System.nanoTime() logged for repro;
            fixed 42L for R15)
    │
    ├─ SplittableRandom master = new SplittableRandom(masterSeed);
    │
    ├─ long envSeed          = master.split().nextLong();  // → @TestPropertySource("paralife.simulation.events.seed")
    ├─ long simSeed          = master.split().nextLong();  // → SimulationEngine.simRng via ctor
    ├─ long actionSeed       = master.split().nextLong();  // → ActionResolver.actionRng via ctor
    ├─ long worldInitSeed    = master.split().nextLong();  // → FertilityInitializer.fertilityRng + starting-position
    ├─ long respawnSeed      = master.split().nextLong();  // → WorldWebSocketHandler.spawnRng
    │
    └─ for i in 0..99:
          long botSeed_i     = master.split().nextLong();
          → BotClient(..., new Random(botSeed_i))         // via 6-arg ctor
```

**Propagation mechanism:**

- `paralife.simulation.events.seed` already flows via `@TestPropertySource` → `EnvironmentConfig.seed()` → `new Random(config.seed())` at `EnvironmentEngine:220`. **No code change needed.**
- `paralife.world.rock.seed` already flows via `@TestPropertySource` → `RockConfig` → `RockGenerator`. **No code change needed.**
- **New properties the planner may define:**
  - `paralife.simulation.seed` (new long) — consumed by `SimulationEngine` ctor if non-null, else falls back to unseeded.
  - `paralife.simulation.action-seed` — consumed by `ActionResolver`.
  - `paralife.world.fertility.seed` — consumed by `FertilityInitializer`.
  - `paralife.world.spawn-seed` — consumed by `WorldWebSocketHandler`.
- **OR** — a single `paralife.seed.master` that a new `RandomSource` bean expands into all sub-seeds via `SplittableRandom.split()`. Cleaner from a test-author POV (one property to set) but requires a new bean + ctor plumbing into all seeding sites. Trade-off; planner's call.

**Per-bot seeding:** `BotClient` ctor at line 85-97 already accepts `Random`. `BotLauncher` currently calls the 2-arg ctor (line 47-48). `SeededBotLauncher` (new test util):

```java
public class SeededBotLauncher {
    public List<BotClient> launchSeeded(String uri, int count, long masterSeed) throws Exception {
        SplittableRandom master = new SplittableRandom(masterSeed);
        List<BotClient> launched = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(count);
        ParticleType[] types = ParticleType.values();
        for (int i = 0; i < count; i++) {
            char species = switch (types[i % types.length]) {
                case CATALYST -> 'C'; case MEMBRANE -> 'M'; case SPORE -> 'S';
            };
            long botSeed = master.split().nextLong();
            BotClient bot = new BotClient(uri, species,
                    new HeuristicBrain(HeuristicBrain.REPRODUCE_THRESHOLD),
                    100L, 50L, new Random(botSeed));
            launched.add(bot);
            Thread.startVirtualThread(() -> {
                try { bot.connect(); bot.waitForRegistered(10, TimeUnit.SECONDS); }
                catch (Exception ignored) {} finally { done.countDown(); }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        return new ArrayList<>(launched);
    }
}
```

**Incidental fix required:** `BotClient.handleDeath()` line 294 uses `ThreadLocalRandom.current().nextLong(respawnJitterMs)` directly — bypasses the ctor's injected `rng`. Change to `rng.nextLong(respawnJitterMs)`. One line; restores seeding contract.

## Fixture Rollover (D-06b)

Target directory: `.planning/phases/16-emergent-behavior-tests/fixtures/` — **must be added to `.gitignore`** (entry: `/.planning/phases/16-emergent-behavior-tests/fixtures/run-*.json` OR simpler: `/.planning/phases/16-emergent-behavior-tests/fixtures/*.json`).

JSON schema (proposal — planner refines):

```json
{
  "master_seed": 1745164800000000000,
  "run_started": "2026-04-21T12:00:00Z",
  "tick_count": 1000,
  "bot_count": 100,
  "world": {"width": 64, "height": 64},
  "emergence": {
    "bonded_pairs_formed": 42,
    "composites_formed": 7,
    "buffs_granted": 3,
    "mutagen_infections": 11
  },
  "stability": {
    "tick_drift_percent": 3.2,
    "tick_work_ms_mean": 6.8,
    "tick_work_ms_p99": 13.4,
    "session_dropouts": 0,
    "heap_growth_percent": 8.1,
    "error_log_count": 0,
    "active_sessions_final": 100
  },
  "populations": [
    {"tick": 10, "catalyst": 33, "membrane": 34, "spore": 33},
    ...
  ]
}
```

**Rollover logic using `java.nio.file`:**

```java
public final class RunFixtureWriter {
    private static final int KEEP = 5;

    public static void dumpAndRollover(Path dir, RunResult result) throws IOException {
        Files.createDirectories(dir);
        String ts = Instant.now().toString().replaceAll("[:.]", "-");
        Path out = dir.resolve("run-" + ts + ".json");
        try (OutputStream os = Files.newOutputStream(out)) {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(os, result);
        }
        // Keep only KEEP most recent — delete older
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> runs = files
                .filter(p -> p.getFileName().toString().matches("run-.*\\.json"))
                .sorted(Comparator.comparing((Path p) ->
                        Files.readAttributes(p, BasicFileAttributes.class).lastModifiedTime(),
                        Comparator.reverseOrder()))
                .collect(toList());
            for (int i = KEEP; i < runs.size(); i++) {
                Files.deleteIfExists(runs.get(i));
            }
        }
    }
}
```

(The `Comparator.comparing(... throws IOException ...)` needs a wrapper; exact idiom is the planner's call — this is the pattern.)

## Population-Observable Surface (R16)

For the three D-07 stability assertions, the authoritative server state source per concern:

| What we need | Source (all `@Autowired` beans) | Why this source |
|--------------|---------------------------------|-----------------|
| Per-type Particle/BondedPair/CompositeMember counts | `worldGrid.snapshot()` → iterate cells → `cell.occupant() instanceof Particle/BondedPair/CompositeMember` | Mirrors `PopulationDynamicsTest:157-166` pattern verbatim. `snapshot()` returns a read-locked snapshot — safe during live tick loop. |
| Composite count | `compositeRegistry.size()` or `compositeRegistry.getAll().size()` | Single source of truth for active composites. |
| STARVING entities | `worldGrid.snapshot()` → `cell.flags() & Cell.FLAG_STARVING` | CLAUDE.md §Env state projection: STARVING lives on `Cell.flags`, not wire bitmask. |
| Infection map | `envCleanupHooksBean.getInfections()` | Used by `EnvironmentDeterminismTest:191-192` as the canonical read. |
| Active buff entities | `buffRegistry.getBuffs(entityId)` per entity iteration | Used by `EnvironmentEngine:855` — the `.isEmpty()` check is the idiom. |
| Active sessions | `sessionRegistry.getSessionCount()` | Already published via `paralife.ws.active.sessions` gauge. |

**BondedPair sub-type classification for R16 oscillation:** A `BondedPair` has `primaryType` and `secondaryType` (both `ParticleType`). For "per-type share" assertions, the planner should decide whether a CM-bonded pair counts as 0.5 C + 0.5 M or 1 C (by primary) + 1 M (by secondary). Recommended: count both members (i.e., a CM BondedPair contributes +1 to both `C-count` and `M-count`) — this matches Phase 16's "population stability" framing where species identity is more fundamental than bond structure.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via `spring-boot-starter-test` 3.4.4) |
| Config file | `build.gradle.kts` (JaCoCo + `tasks.withType<Test> { useJUnitPlatform() }`) |
| Quick run command | `./gradlew test --tests "com.paralife.engine.CompositeFormationDeterminismTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| R15 | Deterministic composite formation given seed | integration (engine-direct) | `./gradlew test --tests "*CompositeFormationDeterminismTest"` | ❌ Wave 0 |
| R16 | Population stability (no extinction / floor / amplitude) over 1000 ticks | integration (full-stack) | `./gradlew test --tests "*EmergenceStabilityLoadTest"` | ❌ Wave 0 |
| R17 | Five emergent signals observed + narrative | integration + manual doc | `./gradlew test --tests "*EmergenceStabilityLoadTest"` + `16-EMERGENCE.md` | ❌ Wave 0 |
| R18 | Capacity-headroom stability (7 D-11 metrics) | integration (full-stack) | `./gradlew test --tests "*EmergenceStabilityLoadTest"` | ❌ Wave 0 |
| R19 | All v1.0 tests pass | full suite | `./gradlew test` | ✅ |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "com.paralife.engine.*"` (package-scoped; avoids the full 500+ suite per commit).
- **Per wave merge:** `./gradlew test` (full suite) — verifies R19 at merge.
- **Phase gate:** Full suite green + both new tests green + `16-EMERGENCE.md` written.

### Wave 0 Gaps

- [ ] `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` — covers R15.
- [ ] `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` — covers R16/R17/R18.
- [ ] `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` — D-05 utility.
- [ ] `src/test/java/com/paralife/engine/emergence/PopulationHistory.java` — per-tick sampler + oscillation math.
- [ ] `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` — D-06b fixture dump + N=5 rollover.
- [ ] `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` — Logback ListAppender helper for ERROR count.
- [ ] `src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java` — per-bot seeded Random parallel to `BotLauncher`.
- [ ] `src/main/java/com/paralife/metrics/EmergenceMetrics.java` — four `paralife.emergence.*` counters.
- [ ] `src/main/java/com/paralife/engine/RandomSource.java` (OPTIONAL — only if master-seed façade is chosen over per-site properties).
- [ ] RNG injection changes across 6+ files (§RNG Audit).
- [ ] `BotClient.java:294` — single-line fix to use injected `rng`.
- [ ] `.planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md` — narrative writeup after the long-run test runs.
- [ ] `.gitignore` — add fixtures-dir entry.
- [ ] Framework install: none — JUnit 5 + Spring Boot already present; no new deps.

### Meta-validation (validating that the new tests actually measure what they claim)

This phase produces tests. We should also check those tests are well-formed:

1. **Seed reproducibility drill** — run `CompositeFormationDeterminismTest` 3 times in isolation with the same seed; assert all three produce identical `CompositeRegistry.size()`. (Actually — this is the test itself. Good. But we should also run with **different** seeds and assert the counts differ, to catch a "always zero" false pass where any seed gives zero composites.)
2. **Threshold calibration** — before locking the 0.15 oscillation-amplitude floor and the 5 %/80 % per-type floor, run `EmergenceStabilityLoadTest` 5 times with 5 different seeds (or: fixed seed, varied `BondingConfig` knobs) and record the distribution of each observed value. If the default thresholds fail half the time, they're too tight; if they pass every time even with bogus configs, they're too loose. **This calibration step should be part of plan 16-07 assertions-wiring, not a hidden assumption.** Planner may choose to encode the calibrated floor as a test property so future tuning is yaml-driven.
3. **Mutation testing** — not feasible this phase. `pitest` is not in `build.gradle.kts` and adding it is out of scope. The test quality bar is: each D-07 / D-11 assertion fails meaningfully when given synthetic bad data. Plan 16-07 should include "assertion fail-mode smoke tests" that prove each threshold is tripped by the obvious broken-case.
4. **Counter wiring verification** — for each of the four `paralife.emergence.*` counters, plan 16-01 should include a unit-level wiring test (pattern: `WebSocketMetricsWiringTest`) that constructs a minimal `SimulationEngine` + fake bond/composite event, calls the trigger, and asserts the counter increments. This is cheap and catches the "metric exposed but never incremented" failure mode, which actuator reachability doesn't catch (see `MetricsEndpointIntegrationTest` — it passes even for zero-count meters because of priming).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 | Build toolchain | ✓ | 21 (toolchain configured in `build.gradle.kts:12-14`) | — |
| Gradle wrapper | Build | ✓ | Pinned | — |
| Spring Boot 3.4.4 | Runtime + test | ✓ | 3.4.4 (plugin declared) | — |
| Jetty 12 | WebSocket + client (full-stack tests) | ✓ | 12.0.18 (pinned dep) | — |
| Actuator `/metrics` endpoint | D-14 counters readable via REST | ✓ | Already in `management.endpoints.web.exposure.include` | — |
| Micrometer `MeterRegistry` | Counter registration | ✓ | transitive via actuator | — |
| Logback | `TestLogCapture` ListAppender | ✓ | transitive via Spring Boot logging | — |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Project Constraints (from CLAUDE.md)

The planner and implementers MUST honour these. Research has verified each against the live codebase:

| Constraint | Source | Impact on Phase 16 |
|------------|--------|--------------------|
| Java 21 virtual threads (`spring.threads.virtual.enabled: true`) | CLAUDE.md §Tech Stack | Long-run test runs under virtual-thread scheduling; do **not** assume deterministic thread-interleaving. Byte-stable fixtures explicitly rejected in D-09 for exactly this reason. |
| Immutable records throughout; mutations produce new instances | CLAUDE.md §Conventions | `EmergenceMetrics` is a `@Component` with mutable counter fields (standard Micrometer pattern). No new mutable records introduced. |
| Single-threaded simulation core (all world mutations in tick event handlers) | CLAUDE.md §Conventions | Trigger-watcher observer does NOT mutate world state — reads only. Emergence counters are per-event increments at mutation sites, which already run on the tick thread. Safe. |
| `ReentrantReadWriteLock` on `WorldGrid` — read lock for snapshots, write lock for mutations | CLAUDE.md §Conventions | `worldGrid.snapshot()` takes the read lock. Test observers sampling during live tick loop are safe. |
| `@EventListener` with `@Order` for tick pipeline sequencing | CLAUDE.md §Architecture | Occupied slots: 10 (Simulation), 14 (Environment), 15 (CompositeEnergyDistributor), 20 (ActionResolver), 25 (EnvPostActionReconciler), 50 (PerceptionBroadcaster, TickBroadcaster), 200 (WebSocketKeepaliveService). New: @Order(0) TickWorkStart, @Order(201) TickWorkEnd — OR skip listener pattern and instrument `TickEngine` directly (recommended — simpler). |
| Three-layer env state projection; bot perception is zero-trust filtered | CLAUDE.md §Architecture | Test observers read layer 1 (shadow grids) and authoritative registries, NOT layer 3 (wire bitmask). Asserting on `Frame.TickFrame` cell status would under-report because OVERCROWDED is redacted per-bot (D-40). |
| `@ConfigurationProperties` on records for type-safe config binding | CLAUDE.md §Spring patterns | Any new seed property (`paralife.simulation.seed`, etc.) goes on a record. `EnvironmentConfig.seed()` is the existing example. |
| No exception bubbling; tick loop catches exceptions and continues | CLAUDE.md §Error handling | D-11 ERROR-count assertion (== 0) will catch these. Counter wiring must not throw — increment on a throw-safe path. |

## Common Pitfalls

### Pitfall 1: `ThreadLocalRandom` masquerading as "seedable"
**What goes wrong:** Tests set `@TestPropertySource("paralife.simulation.events.seed=42")` and expect the whole simulation to be deterministic, but `SimulationEngine` / `ActionResolver` / `BondedPair.formBond` still use `ThreadLocalRandom`.
**Why it happens:** The `EnvironmentEngine.resetForTest` comment at line 1208–1216 explicitly acknowledges this gap — it's the "honest boundary between deterministic env engine and the whole sim uses ThreadLocalRandom."
**How to avoid:** Implement the §RNG Audit injection strategy. Don't trust `events.seed` alone for R15.
**Warning signs:** `CompositeFormationDeterminismTest` passes with seed=42 but composite counts drift across runs. If this happens, at least one of sites #1, #2, #3, #5, #13 in the audit table is not yet seeded.

### Pitfall 2: `BotLauncher` silently ignores bot seeding
**What goes wrong:** Test passes per-bot seeds to `SeededBotLauncher`, but the bots connect and the brain still uses `ThreadLocalRandom` because `HeuristicBrain.decide(TickFrame, BotState, Random)` gets its `Random` from wherever the caller provides it — and `BotClient.handleDeath()` line 294 bypasses the injected `rng` for respawn jitter regardless.
**Why it happens:** Missed seeding point + existing bug.
**How to avoid:** Fix `BotClient.handleDeath:294` (use `rng.nextLong(...)`, not `ThreadLocalRandom.current().nextLong(...)`). Verify `HeuristicBrain.decide` receives the per-bot seeded `Random` — check `BotClient.onTick` call site.
**Warning signs:** Bot actions vary across runs with identical master seed.

### Pitfall 3: Asserting on wire-level perception data
**What goes wrong:** Test tries to count STARVING entities by scanning `Frame.TickFrame.cells()` bitmask — gets zero because STARVING is on `Cell.flags` not `entityStatus` (CLAUDE.md §Env state projection).
**Why it happens:** Confusion between the three projection layers.
**How to avoid:** Always read the authoritative server state (`worldGrid.snapshot()`, registries) for assertions. Never parse bot-side `Frame.TickFrame`.
**Warning signs:** Trigger-watcher sees zero STARVING entities despite energy decay producing obvious starvation.

### Pitfall 4: `SpringBootTest` context leakage between R15 runs
**What goes wrong:** The R15 test runs the same seed three times via `@RepeatedTest(3)` and expects identical composite counts. But `CompositeRegistry` still holds state from the previous run, so run 2 starts with non-empty state.
**Why it happens:** Spring context is shared across `@Test` methods in the same class unless you reset.
**How to avoid:** Use `@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)` (heavy — full context rebuild) OR explicit `@BeforeEach` reset: `worldGrid.clear(); compositeRegistry.clear(); botRegistry.clear(); buffRegistry.clear(); environmentEngine.resetForTest();` (lighter — pattern from `EnvironmentFullStackSmokeTest:113-121`).
**Warning signs:** First run passes, subsequent runs have different counter totals.

### Pitfall 5: Heap-growth delta mistakes transient GC variation for a leak
**What goes wrong:** `Runtime.freeMemory()` bounces around within a GC cycle — sampling at single points can report 30 % "growth" that's actually just GC timing.
**Why it happens:** Minor-collection pauses.
**How to avoid:** Sample N ticks, take the median or mean of the window (first 200 ticks mean vs last 200 ticks mean, per D-11 row 5). Optionally call `System.gc()` at each window boundary (not for production — test-only).
**Warning signs:** Heap-growth assertion fails flakily across runs despite no real leak.

### Pitfall 6: `Counter.count()` is `double`, not `long`
**What goes wrong:** Assertion reads `emergenceMetrics.bondedPairsFormed()` expecting `long`, gets `double`, silently compiles, compares against literal `0` which is `int` — works but looks fragile; or uses `isEqualTo(5L)` and fails with `5.0` vs `5`.
**Why it happens:** Micrometer's API surface.
**How to avoid:** Use `assertThat(counter.count()).isEqualTo(5.0)` or cast `(long) counter.count()`. Pattern used in `WebSocketMetricsWiringTest`.
**Warning signs:** Flaky assertion on otherwise correct counter.

### Pitfall 7: `/actuator/metrics/<name>` returns 404 for unsampled DistributionSummary
**What goes wrong:** The counter pattern works, but for the tick-work-time DistributionSummary (if added), `/actuator/metrics/paralife.tick.work.ms` returns 404 until at least one sample has been recorded.
**Why it happens:** Spring Boot Actuator filters out meters with zero samples.
**How to avoid:** Either (a) prime the meter in `@BeforeEach` as `MetricsEndpointIntegrationTest:42-48` does, or (b) ensure at least one tick happens before reading (trivially true in the long-run test).
**Warning signs:** Actuator test passes for Counters, fails for the new DistributionSummary.

## Code Examples

### Seeded engine-direct drive loop (R15 core)

```java
// Source: based on EnvironmentDeterminismTest.java:122-160 (VERIFIED pattern)
@Autowired ApplicationEventPublisher publisher;
@Autowired TickEngine tickEngine;

void driveRun(long seed, int ticks) {
    // TickEngine auto-start=false so we drive publisher directly.
    for (long t = 1; t <= ticks; t++) {
        publisher.publishEvent(new TickEvent(t));
    }
}
```

### Tick-work-time instrumentation (inline in TickEngine)

```java
// Source: proposed change to TickEngine.java:79-108
private final MeterRegistry meterRegistry;  // @Autowired ctor
private final DistributionSummary tickWork = DistributionSummary.builder("paralife.tick.work.ms")
        .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);

private void tickLoop() {
    while (running.get()) {
        long tickNumber = tickCounter.incrementAndGet();
        long startNs = System.nanoTime();
        eventPublisher.publishEvent(new TickEvent(tickNumber));
        long elapsedNs = System.nanoTime() - startNs;
        tickWork.record(elapsedNs / 1_000_000.0);           // ms
        long sleepTime = Math.max(0, config.intervalMs() - elapsedNs / 1_000_000);
        if (sleepTime > 0) Thread.sleep(sleepTime);
    }
}
```

### Emergence-counter increment at SimulationEngine bond site

```java
// Source: proposed edit at SimulationEngine.java:521-524 (VERIFIED site)
worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair);
worldGrid.clearEntity(bond.secondaryPos.x(), bond.secondaryPos.y());
claimedForBonding.add(bond.primaryPos);
claimedForBonding.add(bond.secondaryPos);
emergenceMetrics.incBondedPair();                                                   // + new
log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})",             // + new
        tickNumber, bond.predator.type().name().charAt(0),
        bond.prey.type().name().charAt(0), bond.primaryPos.x(), bond.primaryPos.y());
```

### Test-side ERROR-log capture

```java
// Source: standard Logback test pattern; no codebase prior but widely documented
class TestLogCapture {
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    static TestLogCapture attach() {
        TestLogCapture c = new TestLogCapture();
        c.appender.start();
        c.root.addAppender(c.appender);
        return c;
    }
    void detach() { root.detachAppender(appender); }
    long errorCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }
    List<String> emergenceMarkers() {
        return appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.startsWith("EMERGENCE "))
            .toList();
    }
}
```

### Oscillation-amplitude computation (D-07 row 3)

```java
// Rolling window (max - min) / mean across 200-tick windows
double rollingAmplitude(List<Integer> typeSeries, int windowSize) {
    double maxAmp = 0;
    for (int i = windowSize; i <= typeSeries.size(); i++) {
        List<Integer> win = typeSeries.subList(i - windowSize, i);
        int max = Collections.max(win), min = Collections.min(win);
        double mean = win.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean > 0) {
            double amp = (max - min) / mean;
            maxAmp = Math.max(maxAmp, amp);
        }
    }
    return maxAmp;
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `spring-boot-starter-tomcat` | Jetty 12 (`starter-jetty`) | Phase 15 Plan 06 | `@SpringBootTest(RANDOM_PORT)` uses Jetty; permessage-deflate negotiation is Jetty-specific. |
| Spring `StandardWebSocketClient` | Jetty native `WebSocketClient` | Phase 15 Plan 09 | Full-stack tests must use Jetty's `ClientUpgradeRequest.addExtensions(...)` (pattern in `EnvironmentFullStackSmokeTest:170-171`). |
| JSON wire + Jackson on bot | Compact codec (`PerceptionCodec`) | Phase 15 Plan 06 | `BotClient` no longer uses Jackson. Tests do not decode cell-level data from bot side (zero-trust). |
| `paralife.ws.bytes.saved` | Deferred | Phase 15 Plan 10 | Third metric explicitly NOT added. Phase 16 respects this — no attempt to re-introduce. |
| `BondedPair.formBond(...)` without RNG injection | Still `ThreadLocalRandom` (unchanged) | — | **Phase 16 changes this** per §RNG Audit #13. |

**Deprecated/outdated:**
- Byte-stable snapshot testing — rejected in D-09 for statistical emergence tests.
- Operator UAT script (`16-UAT.md`) — D-13 defers to M5.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Elevated `BondingConfig.bondingProbability=0.3-0.5` will produce observable composite formation in 1000 ticks at 100-bot 64×64 grid. | Pattern 2, D-12 discretion | Long-run test passes the "composites formed > 0" check too easily (over-tuned) or never fires (under-tuned). Mitigation: calibration runs in plan 16-07 before thresholds lock. `[ASSUMED]` |
| A2 | `BondedPair` primary+secondary both count toward per-type share for D-07 row 2. | §Population-Observable Surface | Share assertion fails when composites dominate because bonded pairs under-count one type. Mitigation: document the choice in `16-EMERGENCE.md`; surface the count rule in an assertion message. `[ASSUMED]` |
| A3 | Test-side trigger-watcher `W=20`, `R=5` yields adequate signal-to-noise for D-04 signals #3 and #5. | §Trigger-Watcher Pattern | Too-tight window misses signal; too-loose gets swamped by RPS boom-bust noise. Mitigation: same calibration runs. `[ASSUMED]` |
| A4 | 0.15 oscillation amplitude floor (D-07 row 3) matches observed natural variance for at least one of the three types. | §Load-Stability Instrumentation | Test passes/fails based on initial population seeding, not real dynamics. Mitigation: 5-seed calibration run before locking the default. CONTEXT.md already marks this as Claude's Discretion to refine. `[ASSUMED]` |
| A5 | `TickEngine.tickLoop` thread is the same thread that runs all listener `@Order`s inclusive — so a bracket timer inside the loop captures the full pipeline. | §Load-Stability Instrumentation, §Tick-Work Timing | If Spring dispatches any listener asynchronously, the elapsed measurement excludes that listener. Verified: `application.yml` does not set `@Async` on any known listener, and `EventListener` is synchronous by default. `[VERIFIED: Spring docs — @EventListener synchronous unless @Async; codebase grep confirms no @Async on any tick listener]` |
| A6 | `Runtime.freeMemory()` sampling at tick-window boundaries is stable enough for the D-11 row 5 "< 20 % growth" check to not flake on normal GC activity. | §Load-Stability Instrumentation, Pitfall 5 | Flaky test. Mitigation: use mean-of-window and a generous 20 % threshold. JFR would be more robust but out of scope. `[ASSUMED]` |
| A7 | Four `paralife.emergence.*` counter names from D-14 don't collide with any existing meter. | §Emergence-Counter Surface | `MeterRegistry.add` throws on duplicate ID. Grep `paralife\.emergence\.` over `src/main` returned no matches — safe. `[VERIFIED: grep]` |
| A8 | `DeathFinalizer`, `EnvCleanupHooksBean`, and `BotRegistry` `clear()` methods can be called mid-test without corrupting in-flight state. | Pattern 1, Pitfall 4 | Between-run reset leaves dangling references. Verified: `EnvironmentFullStackSmokeTest.setUp()` (line 113-121) does exactly this sequence and passes. `[VERIFIED: existing test]` |
| A9 | Elevated env-event lambdas (`lightning.peak-lambda=0.1`, `mutagen.peak-lambda=0.08`) produce at least one `paralife.emergence.buffs.granted` and one `paralife.emergence.mutagen.infections` in 1000 ticks. | Pattern 2 | Signals #4 and #5 (buffed predators) never fire; narrative can't be written. Mitigation: `EnvironmentFullStackSmokeTest:89-92` uses lambdas 0.25/0.30/0.25 on a 12×12 grid in 60 ticks successfully — scaling to 1000 ticks on 64×64 the event count should be comfortably non-zero even at 0.08. `[CITED: EnvironmentFullStackSmokeTest lambdas]` |
| A10 | `BotClient.handleDeath:294` fix (use injected `rng`) doesn't break any existing test. | §RNG Audit #16 | Regression on a passing test. Mitigation: the injected `rng` defaults to `ThreadLocalRandom.current()` via the 2-arg ctor (line 76), so production behaviour is preserved byte-for-byte — this is a pure test-enabling refactor. `[ASSUMED — needs the test suite to confirm after the edit]` |

**If the Assumptions Log is non-trivial:** Items A1, A3, A4 should be resolved by a calibration pass in plan 16-07 (wire assertions) before thresholds lock. Items A6 and A10 should be flagged for verifier-agent attention.

## Open Questions

1. **Seed-property shape — single master vs per-component?**
   - What we know: CONTEXT.md §Claude's Discretion lists "per-component seed-derivation scheme" as planner's call; both approaches work.
   - What's unclear: Whether a single `paralife.seed.master` yaml property plus a new `RandomSource` bean is preferable to five or six independent `paralife.*.seed` properties.
   - Recommendation: Go with **per-component properties** (no new bean). Simpler diff, matches the existing `paralife.simulation.events.seed` and `paralife.world.rock.seed` patterns. The test `@TestPropertySource` derives all values from a master and logs it.

2. **Should `EmergenceMetrics` live in `metrics/` or `engine/`?**
   - What we know: CONTEXT.md §Claude's Discretion explicitly lists this as planner's call.
   - What's unclear: `metrics/` is a sibling to other subsystems (the Phase 15 package); `engine/` keeps the counter next to its primary consumers.
   - Recommendation: `metrics/` — same rationale as `WebSocketMetrics` (Phase 15 Plan 10 chose this). Keep all Micrometer wiring in one package.

3. **Should the long-run test be `@Tag("slow")`?**
   - What we know: D-02 says CI tolerates minutes; local target ≤90 s.
   - What's unclear: Whether every `./gradlew test` run on developer laptops should pay the 90 s tax.
   - Recommendation: Yes — `@Tag("slow")`. Include `./gradlew test -P excludeTags=slow` as the default fast-loop gate; R19's full-suite gate uses the untagged default. Document in the phase summary.

4. **What's the minimum seeding scope for R15?**
   - What we know: Five RNG sites (#1–3, #5, #13) cover composite-formation determinism.
   - What's unclear: Whether elevated `BondingConfig` in R15's test config produces enough composites that a few non-seeded sites (e.g., `CompositeEnergyDistributor` shuffle #9) still yield deterministic counts in practice.
   - Recommendation: **Seed the minimum 5-site set.** If `CompositeFormationDeterminismTest` with the 5-site set passes across 3 repeated runs, we're done. If it flakes, add sites #9 and #17 incrementally. Plan 16-02 may scope "nullable Random" ctor — seeded in test, `ThreadLocalRandom` in prod.

5. **`BondedPair.formBond` signature change — breaking for existing tests?**
   - What we know: 6 existing tests call `BondedPair.formBond(...)` directly (grep `formBond`).
   - What's unclear: Whether changing the last param from `ThreadLocalRandom` to `RandomGenerator` (JDK 17+) breaks callers.
   - Recommendation: `RandomGenerator` is a supertype of `ThreadLocalRandom`, so widening the parameter type is source-compatible but binary-incompatible for method resolution. The safer path: overload — keep the existing `formBond(..., ThreadLocalRandom)` and add `formBond(..., Random)`. Planner to decide.

## Sources

### Primary (HIGH confidence)
- `src/main/java/**/*.java` — grep-verified line-exact citations throughout (10 RNG sites, @Order slots, counter increment sites, existing meter patterns).
- `src/test/java/**/*Test.java` — pattern sources for engine-direct (`EnvironmentDeterminismTest`) and full-stack (`EnvironmentFullStackSmokeTest`, `PopulationDynamicsTest`, `LoadTest`, `MetricsEndpointIntegrationTest`).
- `.planning/phases/16-emergent-behavior-tests/16-CONTEXT.md` — user-locked decisions.
- `.planning/phases/16-emergent-behavior-tests/16-DISCUSSION-LOG.md` — alternatives considered.
- `.planning/REQUIREMENTS.md` — R15–R19 canonical text.
- `CLAUDE.md` — project-wide architecture constraints (@Order contract, three-layer env state projection, virtual-thread concurrency).
- `build.gradle.kts` — dependency versions, toolchain.
- `src/main/resources/application.yml` — config surface.

### Secondary (MEDIUM confidence)
- Spring Framework docs (training data, not live-fetched this session) — `@EventListener` synchronous-by-default, `@Order` priority semantics, `@DirtiesContext` scope.
- Micrometer docs (training data) — `Counter`/`DistributionSummary`/`Gauge` APIs.
- Logback docs (training data) — `ListAppender` pattern for test capture.

### Tertiary (LOW confidence)
- None — no unverified web-search-only claims in this research.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every library is already on the classpath (grep-verified in `build.gradle.kts`).
- Architecture (test surfaces, @Order slots, observable surfaces): HIGH — all grep-verified against the live codebase.
- RNG audit: HIGH — exhaustive grep over `src/main/java` with `ThreadLocalRandom|Math.random|new Random\(|SecureRandom|SplittableRandom|UUID.randomUUID`; 10+ sites cross-referenced to their line numbers.
- Emergence-counter increment sites: HIGH for three of four; MEDIUM for `paralife.emergence.mutagen.infections` — the exact line where `infections.put(...)` happens was not single-grep-located (the `EnvironmentEngine.java` file is 1528 lines and the infection write is spread across multiple sites). Planner should confirm the exact site in plan 16-01.
- Pitfalls: HIGH — each pitfall is grounded in a specific observed pattern (existing comment, existing test, bug site).
- Tuning thresholds (A1, A3, A4, A6): MEDIUM — starting values are educated guesses; calibration is the standard mitigation.

**Research date:** 2026-04-21
**Valid until:** 2026-05-21 (stable — Phase 15/15.1/15.2 are closed, Phase 16 is the last M2 phase; codebase changes to seeding surfaces would invalidate the RNG audit but the counter/test patterns remain valid)
