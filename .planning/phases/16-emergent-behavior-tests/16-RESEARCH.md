# Phase 16: Emergent Behavior Tests - Research

**Researched:** 2026-04-21
**Domain:** Integration-test design, seeded-RNG reproducibility, Micrometer instrumentation, load-stability measurement
**Confidence:** HIGH (all findings verified against live codebase; no external library speculation)

## Summary

Phase 16 builds two new tests — `CompositeFormationDeterminismTest` (engine-direct, R15) and `EmergenceStabilityLoadTest` (full-stack, R16+R17+R18) — plus an `EmergenceMetrics` Micrometer bean and `EMERGENCE` log markers. All 18 CONTEXT decisions are locked; the planner's job is to scope waves, not re-decide.

The single largest unresolved engineering surface is the **server-side RNG audit**. 15 call-sites use `ThreadLocalRandom.current()` or bare `new Random()`. Exactly **6** sit on hot paths that influence emergence signals and MUST be seeded for R15 determinism. The rest are either already seed-aware (`EnvironmentEngine`, `ToxinPathGenerator`, `RockGenerator`, `BotClient`) or out-of-scope (startup-only / non-emergence). A single injected `RandomSource`-style bean per hot-path component is cleaner than a global singleton.

Two smaller-but-load-bearing decisions the planner must make from this research:
1. **Tick-work-time measurement** — recommend bookend `@EventListener` pair (`@Order(0)` + `@Order(101)`) storing a start timestamp on an `AtomicLong` then recording the delta into a Micrometer `Timer`. TickEngine's existing start/elapsed calculation is private and publishes no metric.
2. **Counter increment sites** — all four counters have a single clean host site already in the code: `SimulationEngine` (bond + composite), `EnvironmentEngine.grantSurvivorBuffs` (buff), `EnvironmentEngine.scanMutagenInfections` (infection). An `EmergenceMetrics` bean mirroring `WebSocketMetrics` is the correct pattern.

**Primary recommendation:** Wave 0 does the RNG audit refactor + `EmergenceMetrics` bean + test-fixtures `.gitignore` entry. Wave 1 builds the engine-direct R15 test. Wave 2 builds the full-stack long-run test with its stability + emergence-signal assertions. Wave 3 writes `16-EMERGENCE.md` from a real seeded run.

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Two new tests — `CompositeFormationDeterminismTest` (R15, engine-direct, seeded) + `EmergenceStabilityLoadTest` (R16+R17+R18, full-stack, 100 bots, 1000 ticks).
- **D-02:** Test tick interval ~10–20 ms; long-run wall-clock target ≤90 s local; CI tolerant of several minutes.
- **D-03:** R19 covered by existing `./gradlew test` CI gate — no new test needed.
- **D-04:** Five tracked emergent signals: bonded-pair formation, composite formation, predator-pressure-on-STARVING (trigger-watcher), RPS boom-bust (autocorrelation), flee-from-strong-predator (trigger-watcher).
- **D-05:** Trigger-watcher pattern shared between behavioural signals 3 + 5 — per-entity observation window, per-tick samples, rolling-mean assertion at window close.
- **D-06 / D-06b:** R17 evidence writeup → `16-EMERGENCE.md`; per-tick fixture dump → `fixtures/run-<timestamp>.json`, rollover N=5, directory gitignored.
- **D-07:** Three R16 stability assertions — no extinction, each type ≥5 % share ≥80 % of ticks, oscillation amplitude `(max−min)/mean` ≥ 0.15 over rolling-200-tick window for at least one type.
- **D-08:** Long-run = 1000 ticks.
- **D-09 / D-09a / D-09b:** Component-seeded RNG with single master seed. Statistical (not byte-exact) assertions. Byte-stable fixtures rejected. `BotClient` ctor already accepts injectable `Random` — no refactor on bot side. Server-side RNG audit required (this research delivers it).
- **D-10:** R18 reframed to capacity-headroom stability — `EmergenceStabilityLoadTest` covers R16 + R18 jointly.
- **D-11:** Load-stability assertions — tick drift <10 %, mean tick-work ≤50 % of interval budget, p99 ≤90 %, zero steady-state session dropouts, heap delta (last 200 vs first 200 ticks post-warmup) <20 %, zero ERROR log entries, active-session gauge == configured bot count.
- **D-12:** Load-stability config forces composite formation via elevated bond rate / proximity threshold / energy surplus.
- **D-13:** Phase 16 is JUnit-only. No operator UAT script. Subjective observer UAT deferred to M5.
- **D-14:** Four Micrometer counters in `paralife.emergence.*` namespace — `bonded.pairs.formed`, `composites.formed`, `buffs.granted`, `mutagen.infections`. Implemented as an `EmergenceMetrics` bean mirroring `WebSocketMetrics`.
- **D-15:** `EMERGENCE` INFO log markers — single-line, grep-friendly, low cardinality; pay forward to M5 visualiser.
- **D-17:** R15 runs engine-direct — `@SpringBootTest` without web environment, drives `TickEngine` / `SimulationEngine` in-thread.
- **D-18:** R16/R17/R18 run full-stack — `@SpringBootTest(RANDOM_PORT)` with real `BotClient` + Jetty + codec.

### Claude's Discretion

- Exact `BondingConfig` / `CompositeConfig` knob values that reliably force formation in 1000-tick test-scale worlds (research supplies starting point — see §Forced-Formation Config).
- Trigger-watcher window size W and radius R for signals 3 + 5.
- Per-component seed-derivation scheme (`SplittableRandom.split()` vs `masterSeed + componentTag.hashCode()` — research recommends the latter for explicit traceability).
- Oscillation-amplitude floor (0.15 suggested; refine if live sims show different natural variance).
- Heap-measurement mechanism (research recommends `Runtime.getRuntime()` snapshot).
- `EmergenceMetrics` bean package placement (`com.paralife.metrics` strongly preferred — `WebSocketMetrics` already there).
- Order of long-run-test assertions (fail-fast vs accumulate-all).
- Whether long-run test is `@Tag("slow")`.

### Deferred Ideas (OUT OF SCOPE)

- Subjective human-observer UAT / "pleasing to watch" → M5 visualiser.
- Live operator dashboard polling `/actuator/metrics/paralife.emergence.*` → M5.
- Per-session WS inspector / devtools → M5 global-observer endpoint.
- Bayesian / param-sweep tuning for emergent-behaviour config → post-MVP.
- Byte-stable fixture snapshots → explicitly rejected.
- Emergence counters as Prometheus-scrape targets → M5 wires `/actuator/prometheus`.
- Chart / plot rendering → M5 visualiser or dedicated writeup phase.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| R15 | Deterministic seed test for composite formation | §RNG Audit identifies the 6 hot-path call sites to refactor; §Forced-Formation Config gives tuning starting point; §Seed-Derivation Scheme gives the deterministic split mechanism |
| R16 | Population dynamics test with metabolism + environment | §Test Structure Patterns documents sampling via `Thread.sleep`-interval poll + `worldGrid.snapshot()`; §Counter Increment Sites enables extinction / floor / amplitude assertions |
| R17 | At least one emergent pattern documented | §Trigger-Watcher Mechanism gives signal 3 + 5 pattern; §Counter Increment Sites exposes signals 1 + 2; §Log Marker Format gives `EMERGENCE` line spec |
| R18 | Load test with composites — no regression from v1.0 baseline | §Tick-Work Timing Mechanism gives mean/p99 approach; §Heap Measurement gives leak-detection approach; §LoadTest Reference shows existing 100-bot full-stack harness |
| R19 | All v1.0 tests still pass | Covered by existing `./gradlew test` CI gate per D-03 — no new test artefact. Planner must ensure verifier agent runs full suite. |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Per-tick counter increment | Engine (SimulationEngine / EnvironmentEngine) | — | Counters live at the same call site that already detects the transition; double-bookkeeping would risk drift |
| Metrics exposure | `com.paralife.metrics` (new bean `EmergenceMetrics`) | Actuator endpoint | Mirrors Phase 15 `WebSocketMetrics` exactly; keeps Micrometer knowledge out of the engine package |
| Log-marker emission | Engine (at the same increment site) | — | The trigger detection already holds the tick number + relevant context; co-locating avoids a second lookup path |
| Trigger-watcher state | Test-only helper class inside `EmergenceStabilityLoadTest` | — | Test-specific; no production value; `worldGrid.snapshot()` already gives per-tick visibility the watcher needs |
| Fixture JSON dump | Test-only `@AfterEach` helper | — | No production value; paths read `MeterRegistry` counters + per-tick population snapshot |
| Tick-work timing | Engine (new bookend `@EventListener` pair or a `Timer` in `TickEngine`) | `com.paralife.metrics` (meter) | Must wrap the actual `publishEvent` path to measure the right window; alternatives in §Tick-Work Timing Mechanism |
| Seeded RNG injection | Engine components (one `Random` field per component) | Test `@Bean` override | Component-local state keeps each RNG's draw sequence isolated and reproducible; matches the existing `EnvironmentEngine` pattern |

## Standard Stack

### Core (all already in build.gradle.kts)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 5 | managed by spring-boot-starter-test | Test framework | All 166 existing tests use it [VERIFIED: build.gradle.kts:38] |
| AssertJ | via starter-test | Fluent assertions | Used in all existing integration tests [VERIFIED: grep of `org.assertj.core.api.Assertions.assertThat`] |
| Micrometer | via spring-boot-starter-actuator | Counter + Timer + DistributionSummary | Phase 15 already uses it for WebSocketMetrics [VERIFIED: src/main/java/com/paralife/metrics/WebSocketMetrics.java] |
| Jetty WebSocket client | 12.0.18 | Full-stack test transport | Already used by `BotClient` + `EnvironmentFullStackSmokeTest` [VERIFIED: build.gradle.kts:36] |
| Jackson (for fixture JSON) | transitive via Spring starter-web | Fixture JSON dump | Already on classpath; no new dep |
| SLF4J | transitive via Spring | `EMERGENCE` log markers | Standard throughout codebase |

### No new dependencies required

All the machinery Phase 16 needs — Micrometer meters, AssertJ assertions, Jackson serialisation for the fixture dump, Jetty WebSocket client for the full-stack test, slf4j for the log markers — is already on the classpath. Verified against `build.gradle.kts`.

**Installation:** none needed.

**Version verification:** skipped — only existing declared dependencies used; re-verifying Spring Boot 3.4.4's transitive managed versions adds nothing.

## Architecture Patterns

### System Data Flow

```
EmergenceStabilityLoadTest (@SpringBootTest RANDOM_PORT)
    │
    ├──> BotLauncher.launch(uri, 100)           [100 bots via virtual threads]
    │      │
    │      └──> BotClient × 100  ─── WS ──┐
    │                                      │
    │                                      ▼
    │                               Jetty :RANDOM_PORT
    │                                      │
    │                                      ▼
    │                            WorldWebSocketHandler
    │                                      │
    │                                      ▼
    │                              TickEngine (virtual thread)
    │                                      │ publishEvent(TickEvent)
    │                                      ▼
    │                       [single-threaded event pipeline]
    │                       SimulationEngine       @Order(10)   ◄── increments bonded/composite counters, emits EMERGENCE logs
    │                       EnvironmentEngine      @Order(14)   ◄── increments buff/infection counters, emits EMERGENCE logs
    │                       ActionResolver         @Order(20)
    │                       EnvPostActionReconciler @Order(25)
    │                       PerceptionBroadcaster  @Order(50)
    │                       TickBroadcaster        @Order(100)  ◄── records paralife.ws.tick.frame.bytes
    │                       [new] TickWorkTimer    @Order(101)  ◄── records tick-work-time delta
    │
    ├── per sampleInterval ticks:
    │      ├── read worldGrid.snapshot()               ──> population counts per type
    │      ├── read meterRegistry counter values       ──> emergence counters
    │      ├── update trigger-watchers (signals 3 + 5) ──> rolling-mean samples
    │      └── capture runtime.totalMemory()-freeMem() ──> heap samples
    │
    └── @AfterEach (or @AfterAll):
           ├── write fixtures/run-<ts>.json  ──> per-tick population + counters
           ├── prune old fixtures (keep N=5)
           └── assert stability + emergence + load predicates
```

### Recommended Test File Layout

```
src/test/java/com/paralife/engine/
├── CompositeFormationDeterminismTest.java     # R15 — engine-direct, @SpringBootTest (no web env)
└── EmergenceStabilityLoadTest.java            # R16 + R17 + R18 — @SpringBootTest(RANDOM_PORT)

src/test/java/com/paralife/engine/emergence/   # helpers (if needed; keep light)
├── TriggerWatcher.java                        # per-entity observation window
├── PopulationHistory.java                     # per-tick sample accumulator
└── RunFixtureWriter.java                      # JSON dump + rollover

src/main/java/com/paralife/metrics/
└── EmergenceMetrics.java                      # new bean — 4 Counters

.planning/phases/16-emergent-behavior-tests/
├── 16-CONTEXT.md                              # already committed
├── 16-DISCUSSION-LOG.md                       # already committed
├── 16-RESEARCH.md                             # this file
├── 16-EMERGENCE.md                            # written post-run in final wave
└── fixtures/                                  # gitignored
    └── run-*.json                             # keep latest 5
```

### Pattern 1: `@SpringBootTest` full-stack bot-launcher integration test

[VERIFIED: src/test/java/com/paralife/engine/LoadTest.java + MetabolismIntegrationTest.java + EnvironmentFullStackSmokeTest.java]

Canonical shape — mirror this for `EmergenceStabilityLoadTest`:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "paralife.tick.interval-ms=20",         // per D-02
    "paralife.tick.auto-start=true",
    "paralife.world.width=128",
    "paralife.world.height=128",
    "paralife.simulation.enabled=true",
    // ... forced-formation overrides (see §Forced-Formation Config) ...
    // ... per-component seed overrides (see §RNG Audit) ...
})
class EmergenceStabilityLoadTest {
    @LocalServerPort int port;
    @Autowired WorldGrid worldGrid;
    @Autowired BotRegistry botRegistry;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired BuffRegistry buffRegistry;
    @Autowired MeterRegistry meterRegistry;

    private final BotLauncher launcher = new BotLauncher();

    @AfterEach void tearDown() { launcher.shutdown(); }

    @Test void longRunStabilityAndEmergence() throws Exception {
        var bots = launcher.launch("ws://localhost:" + port + "/ws/world", 100);
        // sample loop: Thread.sleep(sampleInterval * intervalMs), snapshot, assert
        // ...
    }
}
```

### Pattern 2: Engine-direct `@SpringBootTest` without web env

Used by R15. Drives `TickEngine` / `SimulationEngine` in-thread via `publisher.publishEvent(new TickEvent(n))` — the same pattern `EnvironmentFullStackSmokeTest` uses to step ticks deterministically after disabling auto-start:

```java
@SpringBootTest
@TestPropertySource(properties = {
    "paralife.tick.auto-start=false",
    // seeds for every hot-path component
    "paralife.simulation.events.seed=42",
    "paralife.seed.master=42",          // new — see §Seed-Derivation
})
class CompositeFormationDeterminismTest {
    @Autowired ApplicationEventPublisher publisher;
    @Autowired WorldGrid worldGrid;
    @Autowired CompositeRegistry compositeRegistry;
    @Autowired MeterRegistry meterRegistry;

    @Test void sameSeedYieldsSameCompositeCount() {
        // seed-A run
        runWorld(seed=42, ticks=200);
        long seedACount = counterValue("paralife.emergence.composites.formed");
        resetWorld();
        // seed-A run again
        runWorld(seed=42, ticks=200);
        assertThat(counterValue("paralife.emergence.composites.formed")).isEqualTo(seedACount);
    }
}
```

### Pattern 3: Per-tick sampling

`PopulationDynamicsTest.java` + `MetabolismIntegrationTest.java` both use this shape [VERIFIED]:

```java
int totalTicks = 1000;
int sampleInterval = 10;   // D-07 oscillation-amplitude works on window of 200 samples
List<Map<String,Integer>> history = new ArrayList<>();
for (int s = 0; s < totalTicks / sampleInterval; s++) {
    Thread.sleep(sampleInterval * intervalMs);
    history.add(countPopulation(worldGrid.snapshot()));
    // update trigger-watchers here — they need to see every sample, not the final state
}
```

**Important detail**: the existing tests sleep on wall-clock time tied to `intervalMs`. This is appropriate for R16/R17/R18 because they run with `auto-start=true` and assert on real tick drift. For R15 (`auto-start=false`), use `publisher.publishEvent(new TickEvent(n))` in a tight loop — no sleep, full determinism.

### Pattern 4: Counter increment co-located with detection

[VERIFIED: src/main/java/com/paralife/metrics/WebSocketMetrics.java + src/main/java/com/paralife/websocket/TickBroadcaster.java:185]

`WebSocketMetrics` is injected into `TickBroadcaster` via ctor, and `TickBroadcaster` calls `metrics.recordFrameSize(encoded.getBytes(UTF_8).length)` at the exact post-send site. Mirror this:

```java
// EmergenceMetrics.java
@Component
public class EmergenceMetrics {
    public static final String M_BONDED_PAIRS_FORMED   = "paralife.emergence.bonded.pairs.formed";
    public static final String M_COMPOSITES_FORMED     = "paralife.emergence.composites.formed";
    public static final String M_BUFFS_GRANTED         = "paralife.emergence.buffs.granted";
    public static final String M_MUTAGEN_INFECTIONS    = "paralife.emergence.mutagen.infections";

    private final Counter bondedPairs, composites, buffs, infections;

    public EmergenceMetrics(MeterRegistry registry) {
        bondedPairs = Counter.builder(M_BONDED_PAIRS_FORMED).register(registry);
        composites  = Counter.builder(M_COMPOSITES_FORMED).register(registry);
        buffs       = Counter.builder(M_BUFFS_GRANTED).register(registry);
        infections  = Counter.builder(M_MUTAGEN_INFECTIONS).register(registry);
    }

    public void onBondedPairFormed()   { bondedPairs.increment(); }
    public void onCompositeFormed()    { composites.increment(); }
    public void onBuffGranted()        { buffs.increment(); }
    public void onMutagenInfection()   { infections.increment(); }
}
```

### Anti-Patterns to Avoid

- **Don't build a tick-work `Timer` inside `TickEngine.tickLoop`** by reading `startTime` and writing to a `Timer` — that measures the engine's sleep-adjusted loop body, not the listener pipeline. The load assertions need *listener-pipeline* work time (D-11 wording: "from TickEvent dispatch start to final `@Order(100)` listener completion").
- **Don't read counter values by string-matching `/actuator/metrics` HTTP surface** inside the test — use `meterRegistry.find(name).counter().count()` directly on the autowired bean. Faster, no HTTP parsing, no actuator-filter issues.
- **Don't byte-compare fixture JSON across runs.** D-09 explicitly rejects byte-stable fixtures. Fixture dump is for forensic inspection of *one* failing run, not diff-based assertion.
- **Don't re-implement population counting in the new test.** Copy the `countPopulation(WorldSnapshot)` helper from `MetabolismIntegrationTest` verbatim — it already handles Particle + BondedPair + CompositeMember, which is the subtle case the R16 dynamics assertions need.
- **Don't rely on `PopulationDynamicsTest`'s `Thread.sleep((long) sampleInterval * 50)` hard-coded 50ms** — read `paralife.tick.interval-ms` from `TickConfig` and compute the sleep. `EmergenceStabilityLoadTest` uses 20ms (per D-02), not 50ms.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Tick-work time percentile tracking | Custom ring buffer + manual p99 | `io.micrometer.core.instrument.Timer` with `.publishPercentiles(0.5, 0.99)` | Already what `WebSocketMetrics.tickFrameBytes` does — same API |
| Heap-leak detection | JFR integration, custom GC listener | `Runtime.getRuntime().totalMemory() - freeMemory()` snapshots at tick 200 and tick 1000, plus one `System.gc()` hint before each | Unit-test-scope; JFR and Micrometer's `jvm.memory.used` carry more operational burden than the 20 % delta check needs |
| WebSocket client | Raw socket + handshake | `org.eclipse.jetty.websocket.client.WebSocketClient` (already used by `BotClient` and `EnvironmentFullStackSmokeTest`) | Existing harness |
| JSON fixture writer | Manual StringBuilder | Jackson `ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(map)` | Jackson already on classpath via starter-web |
| Per-entity observation window | Custom `Map<String, List<Sample>>` with manual cleanup | A small `TriggerWatcher` helper class — still custom but confined to one file with clear lifecycle | Nothing off-the-shelf fits "start window on event, close after N ticks, compute mean" |
| Population snapshot over time | Custom double-buffered grid | `worldGrid.snapshot()` (already returns an immutable snapshot) | Verified in `PopulationDynamicsTest.java:157` and `MetabolismIntegrationTest.java:197` |
| Seed-derivation for N components | Custom hash function | `SplittableRandom.split()` OR the simpler `new Random(masterSeed ^ componentTagHash)` | Both in JDK; recommendation in §Seed-Derivation below |

## Server-Side RNG Audit (CRITICAL — D-09b)

Complete inventory of every `ThreadLocalRandom` / `new Random()` / `Math.random()` / `SecureRandom` call on the server side (`src/main/java/com/paralife/engine/**` and `src/main/java/com/paralife/websocket/**`). File:line citations verified by grep.

### (a) MUST-SEED for R15/R16 determinism

These sit in the tick-pipeline hot path and directly affect emergence signals (bond formation, composite formation, reproduction, combat tie-break, role selection). The planner MUST refactor these to accept an injected `Random`.

| File:Line | Call | Purpose | Tick Frequency | Refactor Target |
|-----------|------|---------|----------------|-----------------|
| `SimulationEngine.java:238` | `Collections.shuffle(particlePositions, ThreadLocalRandom.current())` | Randomise particle iteration order in combat scan — spatial fairness | Every tick | Inject `Random simRng` into `SimulationEngine` |
| `SimulationEngine.java:241` | `ThreadLocalRandom rng = ThreadLocalRandom.current()` | Bond probability rolls + bond-defense rolls (lines 262, 294, 323) — **directly decides bond formation** | Every tick, per particle per neighbour | Same `simRng` field (replace the local) |
| `SimulationEngine.java:362` | `Collections.shuffle(compositeMemberPositions, rng)` | Randomise composite-member iteration — reuses above `rng` | Every tick | Covered by `simRng` |
| `SimulationEngine.java:446` | `Collections.shuffle(bondedPairPositions, ThreadLocalRandom.current())` | Randomise bonded-pair iteration during composite-formation scan — **directly decides which pairs merge** | Every tick (only when composites enabled) | Use `simRng` |
| `SimulationEngine.java:942` | `ThreadLocalRandom.current().nextDouble() < compositeConfig.dissolutionChance()` | Composite dissolution probability | Every tick per composite | Use `simRng` |
| `SimulationEngine.java:1079` | `ThreadLocalRandom.current().nextDouble() < shatterProb` | Composite shatter-on-member-death probability | On-event (composite member death) | Use `simRng` |
| `SimulationEngine.java:1090` | `ThreadLocalRandom rng = ThreadLocalRandom.current()` | Composite member-promotion tie-break (line 1105) | On-event | Use `simRng` |
| `ActionResolver.java:520` | `ThreadLocalRandom.current().nextDouble() < profile.bonusOffspringChance()` | Bonus-offspring reproduction roll — **directly affects population dynamics** | Every tick per reproducing bot | Inject `Random actionRng` into `ActionResolver` |
| `CompositeEnergyDistributor.java:77` | `Collections.shuffle(memberIds, ThreadLocalRandom.current())` | Healing iteration order — affects starvation semantics within composite | Every tick per composite | Inject `Random cedRng` into `CompositeEnergyDistributor` |

**Count: 9 call sites, 3 engine components that need `Random` injection** (SimulationEngine, ActionResolver, CompositeEnergyDistributor). `EnvironmentEngine` is already seed-aware [VERIFIED: EnvironmentEngine.java:220 — `config.seed() == null ? new Random() : new Random(config.seed())`].

### (b) Already-Seeded (no refactor needed)

| File:Line | Already Handles Seeding | How |
|-----------|-------------------------|-----|
| `EnvironmentEngine.java:220` | ✓ | `new Random(config.seed())` when non-null — controlled via `paralife.simulation.events.seed` [VERIFIED: application.yml:107 commentary] |
| `EnvironmentEngine.java:1164` (resetForTest) | ✓ | Re-seeds from `config.seed()` or 0L |
| `ToxinPathGenerator.java:44` | ✓ | Package-private ctor `ToxinPathGenerator(Random rng)` already exists |
| `RockGenerator.java:119-122` | ✓ | `buildRandom()` reads `paralife.world.rock.seed` [VERIFIED: application.yml:25] |
| `BotClient.java:85-86` | ✓ | Third ctor takes `Random rng` (D-09a confirmed) |
| `HeuristicBrain.java:92` | ✓ | `decide(TickFrame, BotState, Random rng)` — pure function |

### (c) Out-of-Scope for Phase 16

| File:Line | Purpose | Reason Out-of-Scope |
|-----------|---------|---------------------|
| `WorldWebSocketHandler.java:191` | Random bot-spawn position placement on register | Startup / per-registration, not emergence-path; R15 tests drive worldGrid directly, not registration; R16/R18 accept "bots spawn random but are seeded by RNG once" — the emergence signals are robust to starting positions |
| `FertilityInitializer.java:46-55` | Random fertility-patch placement at world init | Runs once at startup; season-tracker makes subsequent fertility deterministic given that initial placement |
| `BotClient.java:294` | Respawn cooldown jitter (`ThreadLocalRandom`) | Wall-clock timing, not emergence logic; tests running with `auto-start=false` don't traverse respawn path deterministically anyway |
| `Entity.java:265, 286, 295` (`ThreadLocalRandom`) | Hybrid-vigor rate + bond-decay cost computed at BondedPair formation | Package-independent helper uses TLR; would need signature change. CONSIDER: either add an overload accepting `Random`, or accept that hybrid-vigor jitter introduces O(small) stochasticity even under a seeded master — statistical assertions in D-09 are robust to this. **Recommendation**: in-scope as a stretch — signature change is small (one new overload in `Entity.java`) and rewards R15 with full determinism of all bond formation outputs, not just which bonds form. |
| `SimulationEngine.java:556, 567, 568` | `UUID.randomUUID()` for composite + member IDs | IDs are opaque and counted, not compared. Determinism of *counts* is unaffected. R15 statistical assertions don't need ID stability. |

### Recommended RNG Injection Pattern

One `Random` field per component, injected via constructor. Tests override with a test-only `RandomSource` bean that derives per-component seeds from a master seed.

```java
// New minimal helper — lives in com.paralife.engine (or a new com.paralife.random package)
@Component
public class RandomSource {
    private final long masterSeed;

    public RandomSource(@Value("${paralife.seed.master:-1}") long masterSeed) {
        this.masterSeed = masterSeed;
    }

    /** Derive a deterministic sub-seed from the master + a stable component tag. */
    public Random forComponent(String tag) {
        if (masterSeed == -1L) return new Random();   // production: unseeded
        return new Random(masterSeed ^ ((long) tag.hashCode()));
    }
}
```

Components receive a seeded `Random` at construction:

```java
@Component
public class SimulationEngine {
    private final Random rng;
    public SimulationEngine(..., RandomSource source) {
        this.rng = source.forComponent("simulation");
    }
    // replace every ThreadLocalRandom.current() with this.rng
}
```

This mirrors `EnvironmentEngine`'s existing style exactly. No `@TestPropertySource` magic — the production default `masterSeed=-1` produces unseeded behaviour identical to today.

### Seed-Derivation Scheme (Claude's Discretion — recommended)

Use `new Random(masterSeed ^ componentTagHash)` over `SplittableRandom.split()`. Rationale:
- Explicit traceability — a failing test log line can print `simulation rng seed = 42 ^ hashOf('simulation') = ...` and the developer can reconstruct exactly.
- No `SplittableRandom` API learning curve.
- `Random` matches every existing `new Random(seed)` call in the codebase (EnvironmentEngine, RockGenerator).
- Tag-string collisions trivially avoided — there are ~5 tags total.

## Counter Increment Sites (D-14)

Exact source lines identified. All four counters sit at single co-located sites.

| Counter | Site File:Line | Current Code Context | Increment Injection |
|---------|---------------|---------------------|---------------------|
| `paralife.emergence.bonded.pairs.formed` | `SimulationEngine.java:542` | End of bond-application loop — `bondEvents++` is already there, emit from same point | `emergenceMetrics.onBondedPairFormed(); log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})", ...)` inside the `if (result instanceof BondFormation bond)` block |
| `paralife.emergence.composites.formed` | `SimulationEngine.java:602` | End of composite-application loop — `compositeEvents++` is already there | Same shape as above, inside `if (result instanceof CompositeFormation cf)` block, right before the `compositeEvents++` line |
| `paralife.emergence.buffs.granted` | `EnvironmentEngine.java:700-711` (`grantSurvivorBuffs`) | `buffRegistry.grant(entityId, ...)` call — emit once per `grantSurvivorBuffs` invocation | `emergenceMetrics.onBuffGranted(); log.info("EMERGENCE buff-granted tick={} entity={} buff={} survivor-of=mutagen", ...)` at method start |
| `paralife.emergence.mutagen.infections` | `EnvironmentEngine.java:573` | Line already exists: `mutagenInfectionEventCount++;` (internal long counter — Plan 14-06 Task 3b) — piggyback the new counter there | `emergenceMetrics.onMutagenInfection(); log.info("EMERGENCE infection-started tick={} entity={} strain={}", ...)` — **reuse** the existing `mutagenInfectionEventCount` field for cross-check assertions if needed |

**Bean wiring:**

- `SimulationEngine` ctor already has 10+ params; add `EmergenceMetrics emergenceMetrics` — tolerable but verify no test constructs this engine manually. Grep shows `CompositeFormationTest.java:45` does construct it directly — that test can pass a stub/noop `EmergenceMetrics` or the planner can make the param nullable. Recommendation: nullable param + null-check at each `.onXxx()` site mirrors `environmentEngine != null` pattern already in the file [VERIFIED: SimulationEngine.java:278].
- `EnvironmentEngine` ctor already takes 6+ params; same treatment.

**Preserve** the existing internal `mutagenInfectionEventCount` counter — Plan 14-06 tests read it directly. Don't migrate that call site to Micrometer-only.

## Tick-Work Timing Mechanism (D-11)

**Requirement restated:** Measure wall-clock time from `TickEvent` dispatch start to final `@Order(100)` listener completion. Compute mean + p99 over the run.

**Does Phase 15 already expose this?** No. `TickEngine.java:91` computes `elapsed = (nanoTime - startTime) / 1_000_000` but **only logs a warning when elapsed exceeds intervalMs** — no Micrometer meter is published. `WebSocketMetrics` exposes `paralife.ws.active.sessions` (Gauge) and `paralife.ws.tick.frame.bytes` (DistributionSummary); no tick-time meter [VERIFIED: WebSocketMetrics.java:32-33].

### Options

| Approach | Pros | Cons | Recommended? |
|----------|------|------|-------------|
| **Bookend listeners** — `@Order(0)` stores `System.nanoTime()` in `AtomicLong`, `@Order(101)` reads it and records `(now − start) / 1e6` into a `Timer` | No touching `TickEngine`; matches exact D-11 window; single-threaded pipeline means `AtomicLong` is sufficient (could even be plain long if guarded) | Two new beans or one helper bean with two `@EventListener` methods; listener ordering becomes part of the contract | ✓ **YES — primary recommendation** |
| Wrap `ApplicationEventMulticaster.multicastEvent` via `@Around` AOP | Captures the exact dispatch boundary; no bookend contract | Introduces Spring AOP machinery not currently used; heavier | No |
| Modify `TickEngine.tickLoop` to publish to a `Timer` | Smallest code change | Measures loop-body + sleep-offset, not listener-pipeline (misses `@Order(0)`/`@Order(100)` internal overhead) and misses any post-publish listener work outside the `nanoTime` window | No — doesn't match D-11 semantics |
| Spring `ApplicationListener.SmartApplicationListener` around wrapper | Niche; tricky ordering | Same semantics as bookend listeners but harder to understand | No |

### Recommended Implementation

```java
@Component
public class TickWorkTimer {
    public static final String M_TICK_WORK = "paralife.tick.work";

    private final Timer timer;
    private final AtomicLong tickStartNanos = new AtomicLong();

    public TickWorkTimer(MeterRegistry registry) {
        this.timer = Timer.builder(M_TICK_WORK)
                .description("Wall-clock time from TickEvent dispatch start to final listener completion")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @EventListener
    @Order(0)                               // BEFORE SimulationEngine @Order(10)
    void onTickStart(TickEvent e) {
        tickStartNanos.set(System.nanoTime());
    }

    @EventListener
    @Order(101)                             // AFTER TickBroadcaster @Order(100)
    void onTickEnd(TickEvent e) {
        long elapsed = System.nanoTime() - tickStartNanos.get();
        timer.record(elapsed, TimeUnit.NANOSECONDS);
    }
}
```

**Test reads:**
```java
Timer t = meterRegistry.find(TickWorkTimer.M_TICK_WORK).timer();
double meanMs = t.mean(TimeUnit.MILLISECONDS);
double p99Ms  = t.percentile(0.99, TimeUnit.MILLISECONDS);
```

**Note on single-threaded assumption:** CLAUDE.md states "Single-threaded simulation core (all world mutations in tick event handlers)". Spring's default `SimpleApplicationEventMulticaster` publishes events synchronously on the publisher thread (the tick-loop virtual thread). So the `AtomicLong` is strictly single-writer-single-reader per tick — a plain `long` would work, but `AtomicLong` costs nothing and defends against future async-multicaster config.

## Integration-Test Structure Patterns

Synthesis of what the three reference tests already do. Planner should adopt these — no invention needed.

### `@TestPropertySource` for config overrides

All three reference tests use the same pattern [VERIFIED: LoadTest.java:22, PopulationDynamicsTest.java:26, EnvironmentFullStackSmokeTest.java:76]:
- Flat dotted keys — e.g. `paralife.simulation.nutrient-spawn-probability=0.001`.
- Per-type metabolic profiles expanded explicitly (PopulationDynamicsTest overrides 24 keys for 3 types × 8 fields).
- `paralife.tick.auto-start=false` for engine-direct; `true` for full-stack with wall-clock assertions.
- `paralife.world.width=N / height=N` — note the actual prefix is `paralife.world` NOT `paralife.grid` [VERIFIED: EnvironmentFullStackSmokeTest.java:66 commentary].

### Bot launching

Full-stack tests use `BotLauncher.launch(uri, count)` [VERIFIED: LoadTest.java:54, PopulationDynamicsTest.java:99]. The launcher:
- Creates `BotClient` with the 3-arg ctor (brain default) — tests wanting seeded bots must use the 6-arg ctor directly in a custom launcher.
- Launches each bot on a virtual thread.
- Waits 30s max for registration.

**For R16/R17/R18 seeded full-stack:** the planner needs a `SeededBotLauncher` that accepts a master seed and derives `new Random(masterSeed ^ botIndex)` for each bot. Otherwise all 100 `BotClient` instances get `ThreadLocalRandom.current()` and the full-stack run is non-reproducible.

### Per-tick sampling

Pattern from `PopulationDynamicsTest.java:111-122`:
```java
for (int sample = 0; sample < samples; sample++) {
    Thread.sleep((long) sampleInterval * intervalMs);   // parameterise intervalMs!
    Map<String,Integer> counts = countPopulation();      // worldGrid.snapshot() under the hood
    history.add(counts);
    log.info("Tick ~{}: CATALYST={} MEMBRANE={} SPORE={} total={}", ...);
}
```

Sample interval = 10 ticks for R16's 1000-tick run gives 100 samples — enough for the 200-sample rolling window in D-07 oscillation-amplitude if the planner extends total ticks slightly or relaxes the window to 100 samples. Recommendation: **sample every tick** (sampleInterval = 1) for the behaviour-signal trigger-watcher — they need per-tick resolution for the rolling-mean window. Population history can sub-sample at interval 10 or aggregate per-tick. Cost is small: a `worldGrid.snapshot()` scan of a 128×128 world is microsecond-scale.

### Waiting for tick N

Two idioms in the codebase:
- **Thread.sleep polling** [VERIFIED: LoadTest.java:65] — cheap, wall-clock-tied. Appropriate for R16/R18 (they assert on wall-clock / tick-drift).
- **Manual `publisher.publishEvent(new TickEvent(n))` loop** [VERIFIED: EnvironmentFullStackSmokeTest.java:205] — zero-drift, deterministic. Appropriate for R15 (`auto-start=false`).

**Tick-event counter** — `TickEngine.getCurrentTick()` returns an `AtomicLong` the test can poll. Cleaner than guessing from wall-clock. Use `await().until(() -> tickEngine.getCurrentTick() >= N)` shape (Awaitility not on the classpath — use a simple loop).

## Forced-Formation Config (D-12)

Starting point extracted from existing `CompositeFormationTest.java` + `application.yml` + CONTEXT §Claude's Discretion. The planner should tune during execution.

### Bonding (forces bond formation rate ~10-100× production)

| Key | Production Default | Test Override | Rationale |
|-----|-------------------|---------------|-----------|
| `paralife.bonding.bond-energy-threshold` | 50 | **15** | Far more bots qualify with low starting energy |
| `paralife.bonding.bonding-probability` | 0.10 | **0.50** | 5× the probability per neighbour encounter |
| `paralife.bonding.bond-defense-chance` | 0.25 | 0.25 (unchanged) | Controls bp-defense, not formation rate |

### Composite formation (forces composite rate when bonds exist)

[VERIFIED against `CompositeFormationTest.java:114`'s `CompositeConfig.defaults()` values: `new CompositeConfig(0.03, 12, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true)`]

| Key | Production | Test Override | Rationale |
|-----|-----------|---------------|-----------|
| `paralife.composite.can-form-composites` | true | true | Must be true for composites to exist |
| `paralife.composite.dissolution-chance` | 0.03 | **0.005** | Keep formed composites alive long enough to be counted and to exert flee-pressure (signal #5) |
| `paralife.composite.critical-energy-percent` | 12 | 5 | Avoid energy-crisis dissolution during 1000-tick run |

### Energy surplus (ensures bonds survive to composite-formation)

| Key | Production | Test Override | Rationale |
|-----|-----------|---------------|-----------|
| `paralife.simulation.nutrient-spawn-probability` | 0.001 | **0.01** | 10× nutrients → entities hit bond-energy threshold faster |
| `paralife.simulation.energy-decay-per-tick` | 1 | 1 (unchanged) | Keep production semantics for realistic emergence |
| `paralife.simulation.types.*.decay-per-tick` | per-type | unchanged | Per-type profiles stay realistic |
| `paralife.simulation.types.*.starvation-threshold` | per-type | unchanged | Signal #3 (STARVING predator pressure) requires realistic starvation |

### Grid size / bot density (signals need observable event rates)

| Key | Override | Rationale |
|-----|----------|-----------|
| `paralife.world.width` | **64** (down from 256) | Densify bots → bonds form within 1000 ticks |
| `paralife.world.height` | **64** | Same |
| Bot count | **100** (per D-01) | High enough for statistical observability; matches v1 LoadTest envelope |
| `paralife.tick.interval-ms` | **20** (per D-02) | 1000 ticks × 20ms = 20s wall-clock — comfortable under the 90s target |

**Planner note:** These are starting values, not locked. During execution, if the composite counter stays at 0 after 500 ticks, further reduce `bond-energy-threshold`, raise `bonding-probability`, or increase `nutrient-spawn-probability`. If the tick pipeline is overloaded, back off bot count or grid density. The oscillation-amplitude threshold (D-07 default 0.15) may need tuning too — too high means frozen-equilibrium pass is misflagged as failure; too low means degenerate flat-lined runs pass falsely.

## Heap-Measurement Pick (D-11)

Three candidates weighed for "last 200 ticks vs first 200 ticks post-warmup, delta <20%":

| Approach | Verdict |
|----------|---------|
| `Runtime.getRuntime().totalMemory() - freeMemory()` snapshot at two checkpoints | ✓ **RECOMMENDED** — simplest, zero dependencies, deterministic call site. Hint `System.gc()` before each snapshot to reduce GC-phase noise |
| Micrometer `jvm.memory.used` gauge (Spring Boot actuator auto-registers this) | Valid, but the meter is live throughout the run — test would need to take two samples anyway, plus a lookup step. Same accuracy as Runtime API, more indirection |
| JFR periodic event recording | Overkill for a 20% delta check; adds recording configuration burden |

**Recommendation:**
```java
private long heapUsedBytes() {
    System.gc();  // hint, not a guarantee — accept we're post-GC-ish
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
}

// at tick 200:  long heap200  = heapUsedBytes();
// at tick 1000: long heap1000 = heapUsedBytes();
// assert: (heap1000 - heap200) / (double) heap200 < 0.20;
```

**Caveat to flag to the planner:** `System.gc()` is advisory. On a busy heap the delta may be noisy run-to-run. Two mitigations: (a) take 3 samples at each checkpoint and average; (b) relax the assertion to "heap after 1000 < 2× heap after 200" which tolerates GC-phase noise while still catching a real leak. Both are reasonable; start with (a).

## Trigger-Watcher Mechanism (D-05)

Shared pattern for signals #3 (predator pressure on STARVING prey) and #5 (flee from strong predator). Formalise as a single helper class:

```java
class TriggerWatcher {
    record Window(String entityId, Position startPos, long startTick, long endTick,
                  List<Integer> samples) {}

    private final int windowTicks;          // configurable (Claude's Discretion — suggest 30)
    private final int radius;               // configurable (suggest 5)
    private final List<Window> active = new ArrayList<>();
    private final List<Window> closed = new ArrayList<>();

    void observe(long tick, WorldSnapshot snap, /* signal-specific sampler */);
    void trigger(String entityId, Position pos, long tick);
    double meanSampleAcrossClosedWindows();
    int windowCount();
}
```

**Signal #3 ("predator pressure on STARVING prey"):**
- Trigger: on every tick, scan `snapshot.getCell(x,y).hasFlag(Cell.FLAG_STARVING) && occupant instanceof Particle p`.
- Per tick within window: count non-bonded `Particle` occupants of `p.type().predator()` within radius R of the prey's (tracked) position.
- Assertion at close: rolling-mean ≥ baseline-density (where baseline = total predators / grid-area).

**Signal #5 ("flee from strong predator"):**
- Trigger: on every tick, scan for `Particle` with buff OR `BondedPair` occupant [detect via `buffRegistry.getBuffs(id)` non-empty OR `occupant instanceof BondedPair`].
- Per tick within window: count `Particle` occupants of the weaker-type (i.e., the predator's *prey* type if they were to combat) within radius R.
- Assertion at close: weaker-type density at window close < density at window open (decline).

**Practical scaling note:** With 100 bots and 1000 ticks, naive implementation re-scans the whole grid every tick per open window → O(ticks × windows × gridSize). At 1000 × 10 × 4096 = 41M cell reads total, that's ~0.5s wall-clock — fine. If the planner finds windows piling up (say, a STARVING outbreak triggers 50 windows at once), cap active-window count at 20 and drop new triggers.

## Log-Marker Format (D-15)

Verbatim from CONTEXT D-15:

```
EMERGENCE bonded-pair-formed tick=234 types=CAT+MEM at=(45,78)
EMERGENCE composite-formed tick=512 size=4 role-mix=[L,F,S,A]
EMERGENCE buff-granted tick=401 entity=<id> buff=S+1 survivor-of=mutagen
EMERGENCE infection-started tick=355 entity=<id> strain=12
```

Implementation uses SLF4J at INFO level:
```java
log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})",
         tick, bp.primaryType().name().substring(0,3), bp.secondaryType().name().substring(0,3),
         pos.x(), pos.y());
```

**Grep-friendliness check:** the word `EMERGENCE` appears nowhere else in the existing codebase [VERIFIED: grep ran clean]. Safe to use as the single operator-cheat-sheet prefix.

## Fixture JSON + .gitignore (D-06b)

**Gitignore status:** ✗ `.planning/phases/16-emergent-behavior-tests/fixtures/*.json` is NOT currently ignored. The existing `.gitignore` covers Gradle, IDE, OS, Spring Boot logs, and the engine-managed `.gsd/STATE.md` — nothing under `.planning/`. Plan must add:

```gitignore
# Phase 16 emergence run fixtures (D-06b — keep locally, not committed)
.planning/phases/16-emergent-behavior-tests/fixtures/
```

**Fixture format (suggestion):**
```json
{
  "masterSeed": 42,
  "startedAt": "2026-04-21T10:15:00Z",
  "totalTicks": 1000,
  "config": { "width": 64, "height": 64, "botCount": 100, "intervalMs": 20 },
  "counters": {
    "bondedPairsFormed": 847,
    "compositesFormed": 42,
    "buffsGranted": 128,
    "mutagenInfections": 31
  },
  "ticks": [
    { "tick": 10, "catalyst": 33, "membrane": 34, "spore": 33 },
    ...
  ]
}
```

Rollover: at test start, list `fixtures/*.json`, sort by modified-time desc, delete entries beyond the 5th.

## Runtime State Inventory

N/A — Phase 16 is a testing phase with no production-side rename, migration, or refactor of stored data. The RNG refactor (§RNG Audit) is a code-only change; production behaviour is identical when `paralife.seed.master=-1` (unseeded default).

## Common Pitfalls

### Pitfall 1: `@SpringBootTest` context caching hides flakiness
**What goes wrong:** Spring reuses a cached ApplicationContext across tests with identical `@TestPropertySource`. If a prior test left `BotRegistry` / `CompositeRegistry` / `BuffRegistry` dirty, the next test's counter assertions start from non-zero.
**Warning sign:** Local run passes, CI fails — or vice versa.
**How to avoid:** `@BeforeEach` clears all registries + resets `EnvironmentEngine` (see `EnvironmentFullStackSmokeTest.java:114-121` for the exact pattern). Apply identical cleanup to Phase 16 tests. Also reset Micrometer counters via `meterRegistry.clear()` — or assert on *deltas* not absolute values.

### Pitfall 2: `MetabolismIntegrationTest` flakes under full-suite load
**What goes wrong:** Per STATE.md archived session note: "~50% flake under full-suite load (virtual-thread leakage across Spring contexts when paralife.tick.auto-start=true)". Deferred tech debt from Phase 15.11 specifically for Phase 16 to resolve or work around.
**Warning sign:** `EmergenceStabilityLoadTest` runs green in isolation but flakes when running `./gradlew test` full-suite after other `auto-start=true` tests.
**How to avoid:** Three options for the planner: (a) `@DirtiesContext(classMode = AFTER_CLASS)` on `EmergenceStabilityLoadTest` to force a fresh context — cheap, worst-case adds ~5s to the run; (b) pin the test to `auto-start=false` and drive ticks manually via `publisher.publishEvent` (breaks D-18 full-stack purity though); (c) investigate and fix the leak in Phase 16 — probably 1-2h of work but out of scope. **Recommend (a)** — minimal intervention, D-18 preserved.

### Pitfall 3: Virtual-thread async ordering violates listener `@Order`?
**What goes wrong:** `spring.threads.virtual.enabled: true` is on [VERIFIED: application.yml:4-6]. This applies to web-request handling and `@Async` tasks. Event listeners dispatched by `SimpleApplicationEventMulticaster` are still called synchronously on the publisher thread. Bookend-listener timing pattern (§Tick-Work Timing) is safe.
**Warning sign:** Timer records suspiciously low values (e.g., <10µs) — would indicate listeners ran async.
**How to avoid:** Verify by logging `Thread.currentThread().getName()` inside `@Order(0)` and `@Order(101)` once during initial test run — both should be `tick-engine` (virtual thread). If they aren't, Spring async config is different than expected and the bookend pattern needs a rethink.

### Pitfall 4: Micrometer `publishPercentiles` is client-side only
**What goes wrong:** `Timer.percentile(0.99, ...)` computes from a rolling histogram. The histogram is sampled, not exact — under thin sample counts (<100) percentiles can be wildly wrong.
**Warning sign:** p99 assertion fails on a short run with few ticks.
**How to avoid:** Long-run is 1000 ticks → 1000 samples → histogram is well-populated. If a sanity/smoke variant is added with fewer ticks, check `timer.count()` first and only assert percentiles when `count > 100`. Document this threshold in the test for future maintainers.

### Pitfall 5: Active-session gauge vs. bot count under respawn churn
**What goes wrong:** D-11 asserts `active-session gauge == configured bot count` throughout. But Phase 15.2 respawn flow keeps the WS session open after death — so the session gauge stays at N while the bot's entity lifecycles through death and re-registration. That's actually the correct behaviour.
**Warning sign:** Gauge drops to N-k during the run.
**How to avoid:** If the gauge drops, it's a *real* disconnect, not a respawn. The session-gauge assertion is correct as stated. Per §Specifics in CONTEXT: "Phase 15.2 respawn flow means sessions reconnect on death; load-stability test's 'active-session gauge' check must read the steady-state value not the startup transient" — interpret "steady-state" as "after tick 200 post-warmup". Wave this check through a 100-tick warmup before asserting.

### Pitfall 6: `grantSurvivorBuffs` is only one of many buff-grant paths
**What goes wrong:** The D-14 counter `paralife.emergence.buffs.granted` is labelled "Survivor buff granted" in CONTEXT (`survivor-of=mutagen` in the log format). But `BuffRegistry.grant()` is called from multiple places beyond mutagen survival.
**Verification:** grep of `buffRegistry.grant` shows 4 call sites — EnvironmentEngine lines 705, 706, 709 (all inside `grantSurvivorBuffs`) plus one in `BuffRegistry.transferBuffs` internal. So `grantSurvivorBuffs` is in fact the **only external grant site** [VERIFIED]. The D-14 semantic matches the implementation exactly — no ambiguity. Co-locate the counter increment in `grantSurvivorBuffs` method head, counting once per invocation (not once per type of buff granted within it).

### Pitfall 7: `environmentEngine.resetForTest()` pattern
**What goes wrong:** Tests that share an ApplicationContext and drive ticks manually need to reset environment state between tests, or accumulated toxin/mutagen state leaks. `EnvironmentEngine.resetForTest()` exists [VERIFIED: EnvironmentEngine.java:1129-1164] and re-seeds `rng` from config.
**Warning sign:** Second test in a suite sees composite events piling up from the prior test's residual state.
**How to avoid:** Call `environmentEngine.resetForTest()` in `@BeforeEach` as `EnvironmentFullStackSmokeTest` does [VERIFIED line 119]. For the engine-direct R15 test, this is the right pattern. For the full-stack long-run, it's less critical (fresh context anyway) but harmless.

## Code Examples

### R15 `CompositeFormationDeterminismTest` skeleton

```java
@SpringBootTest
@TestPropertySource(properties = {
    "paralife.tick.auto-start=false",
    "paralife.seed.master=42",
    "paralife.simulation.events.seed=42",
    "paralife.world.rock.seed=42",
    "paralife.world.width=32",
    "paralife.world.height=32",
    // forced-formation knobs (see §Forced-Formation Config)
    "paralife.bonding.bond-energy-threshold=15",
    "paralife.bonding.bonding-probability=0.5",
    "paralife.simulation.nutrient-spawn-probability=0.01",
})
class CompositeFormationDeterminismTest {
    @Autowired ApplicationEventPublisher publisher;
    @Autowired WorldGrid worldGrid;
    @Autowired MeterRegistry meterRegistry;
    @Autowired EnvironmentEngine environmentEngine;
    @Autowired CompositeRegistry compositeRegistry;

    @BeforeEach void reset() {
        worldGrid.clear();
        compositeRegistry.clear();
        environmentEngine.resetForTest();
        meterRegistry.clear();
    }

    @Test void sameSeedYieldsSameCompositeCount() {
        long firstRun = runAndCount(200);
        reset();
        long secondRun = runAndCount(200);
        assertThat(secondRun).isEqualTo(firstRun);
    }

    @Test void compositeFormsFromSeededBonding() {
        // Plant bot particles deterministically via worldGrid.setEntity
        // Drive 200 ticks
        // Assert counter > 0
        long count = runAndCount(200);
        assertThat(count).as("composite formation counter after 200 seeded ticks").isGreaterThan(0);
    }

    private long runAndCount(int ticks) {
        // plant particles (deterministic positions + types)
        for (long t = 1; t <= ticks; t++) publisher.publishEvent(new TickEvent(t));
        return (long) meterRegistry.find(EmergenceMetrics.M_COMPOSITES_FORMED).counter().count();
    }
}
```

### R16/R17/R18 `EmergenceStabilityLoadTest` skeleton

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)  // Pitfall 2
@TestPropertySource(properties = {
    "paralife.tick.interval-ms=20",
    "paralife.tick.auto-start=true",
    "paralife.world.width=64",
    "paralife.world.height=64",
    "paralife.seed.master=42",
    // forced-formation overrides ...
})
@Tag("slow")
class EmergenceStabilityLoadTest {
    @LocalServerPort int port;
    @Autowired WorldGrid worldGrid;
    @Autowired BuffRegistry buffRegistry;
    @Autowired MeterRegistry meterRegistry;
    @Autowired TickEngine tickEngine;

    private final BotLauncher launcher = new BotLauncher();
    @AfterEach void tearDown() { launcher.shutdown(); }

    @Test void thousandTickStabilityAndEmergence() throws Exception {
        // Launch 100 bots
        String uri = "ws://localhost:" + port + "/ws/world";
        var bots = launcher.launch(uri, 100);
        assertThat(bots).hasSize(100);

        // Wait for warmup + run
        waitForTick(200);   // warmup
        long heap200 = heapUsedBytes();

        // Main run with per-tick sampling
        List<PopulationSample> history = new ArrayList<>();
        TriggerWatcher predPressure = new TriggerWatcher(30, 5);  // signal #3
        TriggerWatcher fleeFrom     = new TriggerWatcher(30, 5);  // signal #5

        long lastTick = 200;
        while (tickEngine.getCurrentTick() < 1000) {
            Thread.sleep(50);
            long now = tickEngine.getCurrentTick();
            for (long t = lastTick + 1; t <= now; t++) {
                WorldSnapshot snap = worldGrid.snapshot();
                history.add(samplePopulation(snap));
                predPressure.observe(t, snap, buffRegistry);
                fleeFrom.observe(t, snap, buffRegistry);
            }
            lastTick = now;
        }
        long heap1000 = heapUsedBytes();

        // Read counters
        long bondedPairs = counter(EmergenceMetrics.M_BONDED_PAIRS_FORMED);
        long composites  = counter(EmergenceMetrics.M_COMPOSITES_FORMED);
        long buffs       = counter(EmergenceMetrics.M_BUFFS_GRANTED);
        long infections  = counter(EmergenceMetrics.M_MUTAGEN_INFECTIONS);
        Timer tickWork   = meterRegistry.find(TickWorkTimer.M_TICK_WORK).timer();

        // Write fixture
        new RunFixtureWriter().write(history, bondedPairs, composites, buffs, infections);

        // Assertions — D-07 stability
        assertNoExtinction(history);
        assertPerTypeFloorCoverage(history, 0.05, 0.80);
        assertOscillationAmplitude(history, 200, 0.15);

        // Assertions — D-04 emergence
        assertThat(bondedPairs).as("bonded pairs formed").isGreaterThan(0);
        // composites — soft-check per D-04 #2
        if (composites == 0) log.warn("no composites formed during run — consider tuning knobs");
        assertThat(predPressure.meanSample()).isGreaterThan(baselineDensity(history));
        // flee-from — declining density assertion

        // Assertions — D-11 load stability
        assertThat(tickWork.mean(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(10.0);  // 50% of 20ms
        assertThat(tickWork.percentile(0.99, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(18.0);  // 90% of 20ms
        assertThat((heap1000 - heap200) / (double) heap200).isLessThan(0.20);
        // tick drift: check TickEngine's recorded tick count vs wall-clock / intervalMs
        // active-session gauge: meterRegistry.find("paralife.ws.active.sessions").gauge().value() == 100
        // ERROR log count: log-appender probe — see §Validation
    }
}
```

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Tomcat WebSocket + Spring WebSocketClient | Jetty 12 WebSocketClient + codec (Phase 15) | Integration tests speak permessage-deflate and use `PerceptionCodec` — no Jackson paths |
| JSON `Messages.*` records on wire | `Frame.*` compact codec | Tests that read perceptions must decode `Frame.TickFrame`, not `Messages.Perception` |
| `ThreadLocalRandom.current()` unseeded | Seed-via-constructor `Random` (already done for EnvironmentEngine/RockGenerator/BotClient) | Phase 16 extends this to SimulationEngine + ActionResolver + CompositeEnergyDistributor |
| `Messages.Overcrowded` per-cell flag | Zero-trust vision-scoped bitmask recomputed per bot (Phase 15 D-40/D-41) | No impact on Phase 16 — tests read `worldGrid` directly, not the wire |

**Deprecated/outdated:** None that affect this phase.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `@EventListener` order in Spring (with `@Order(0)` / `@Order(101)`) is honoured synchronously on the publisher thread under virtual-thread config | §Tick-Work Timing | [ASSUMED] If Spring multicaster were changed to async, bookend pattern breaks. VERIFIED via CLAUDE.md statement "single-threaded simulation core" and inspection of `TickEngine.eventPublisher.publishEvent` with default multicaster — but NOT independently tested. Planner should spot-check thread names in first execution. |
| A2 | `Timer.percentile()` from Micrometer's client-side histogram is accurate at N=1000 samples | §Tick-Work Timing + §Pitfall 4 | [ASSUMED — Micrometer convention] Micrometer docs state `publishPercentiles` uses TDigest or fixed buckets; both have well-known properties. 1000 samples is well past any accuracy threshold. Low risk. |
| A3 | `System.gc()` hint before heap snapshot is sufficient noise reduction for a 20% delta check | §Heap Measurement | [ASSUMED] Mitigated by "take 3 samples and average" fallback. Low risk if planner accepts the fallback. |
| A4 | `new Random(masterSeed ^ tag.hashCode())` produces sufficiently decorrelated sub-streams | §Seed-Derivation | [ASSUMED — basic RNG theory] `Random`'s 48-bit LCG is not cryptographic, but for statistical-assertion purposes the xor-hash scheme is well-understood. Low risk; SplittableRandom.split() is the purer choice if reviewers object. |
| A5 | Starting config knob values (bond threshold 15, probability 0.5, nutrient 0.01) reliably produce bonds + composites in 1000 ticks on 64×64 with 100 bots | §Forced-Formation Config | [ASSUMED — extrapolated from `CompositeFormationTest` existing test values] Not executed. Planner/executor will tune empirically during first runs. Medium risk but easy to fix in-wave. |

## Open Questions

1. **Does Spring's default event multicaster actually dispatch synchronously on the publisher thread for TickEvent?**
   - What we know: `SimpleApplicationEventMulticaster` is the default and is synchronous.
   - What's unclear: whether Spring Boot 3.4.4 with `virtual.enabled: true` changes this.
   - Recommendation: first integration-test execution logs `Thread.currentThread().getName()` inside `@Order(0)` and `@Order(101)` — one-time sanity check.

2. **How should the test enforce "zero ERROR log entries during run"?**
   - What we know: Spring Boot tests can attach a Logback `Appender` programmatically.
   - What's unclear: whether the existing test infra has a helper, or whether the test must roll its own.
   - Recommendation: plan a small `TestLogCapture` helper (5-10 LOC) — add a `ListAppender<ILoggingEvent>` to the root logger in `@BeforeEach`, assert `events.stream().noneMatch(e -> e.getLevel() == Level.ERROR)` at end. Simple.

3. **Do we need to reset Micrometer `Counter` values between tests?**
   - What we know: `MeterRegistry.clear()` exists but removes the meter itself, not just its count.
   - What's unclear: whether after `clear()` the meter gets re-registered by the next `EmergenceMetrics` ctor call.
   - Recommendation: don't clear; read **delta** — snapshot counter value at `@BeforeEach`, subtract it from the final value. Robust to test ordering.

4. **Is `@DirtiesContext(classMode = AFTER_CLASS)` the right knob for Pitfall 2 mitigation?**
   - What we know: `AFTER_CLASS` forces context re-creation after the test class finishes. It does NOT re-create before each method within the class.
   - Recommendation: Acceptable for Phase 16 — the test class has one `@Test` method. If the planner adds a second, they can switch to `AFTER_EACH_TEST_METHOD` at a ~5s-per-test cost.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 | All tests | ✓ | managed by Gradle toolchain | — |
| Spring Boot 3.4.4 | `@SpringBootTest` | ✓ | Managed | — |
| JUnit 5 | Test framework | ✓ | via starter-test | — |
| Jetty 12.0.18 | WS client + server | ✓ | pinned in build.gradle.kts:36 | — |
| Micrometer | Meter API | ✓ | via starter-actuator | — |
| AssertJ | Assertions | ✓ | via starter-test | — |
| SLF4J / Logback | Log markers + test log capture | ✓ | via Spring | — |
| Jackson | Fixture JSON writer | ✓ | transitive via starter-web | — |

**Missing dependencies:** none.
**No external services required.**

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (managed by spring-boot-starter-test 3.4.4) |
| Config file | `build.gradle.kts` — `useJUnitPlatform()` |
| Quick run command | `./gradlew test --tests "*DeterminismTest"` or `./gradlew test --tests "EmergenceStabilityLoadTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| R15 | Composite formation is deterministic given a master seed | integration (engine-direct) | `./gradlew test --tests CompositeFormationDeterminismTest` | ❌ Wave 2 creates it |
| R16 | All three types coexist with oscillation under metabolism + env | integration (full-stack) | `./gradlew test --tests EmergenceStabilityLoadTest` | ❌ Wave 3 creates it |
| R17 | At least one emergent pattern is documented + asserted | integration + markdown | test above + `16-EMERGENCE.md` writeup | ❌ Wave 3 creates it |
| R18 | 100-bot load produces no tick-drift / heap-leak / error-log regressions | integration (full-stack) | `./gradlew test --tests EmergenceStabilityLoadTest` | ❌ Same test as R16/R17 (D-10) |
| R19 | All v1.0 tests pass | CI gate | `./gradlew test` (full suite) | ✓ Existing gate (D-03) |

### Phase-level Validation (validating the validators)

Because this *is* a testing phase, lightweight meta-validation:

| Phase claim | Meta-check | How |
|-------------|-----------|-----|
| R15 determinism works | Run `CompositeFormationDeterminismTest` twice locally with `--tests` flag | Assert both invocations report same counter values. This is enforced *inside* the test by `sameSeedYieldsSameCompositeCount` — no external meta-check needed. |
| Long-run test actually exercised composites | Assert composite counter > 0 post-run | Already in the test — D-04 #2 soft-check |
| Tick-work timer not cheating | Add a `@Tag("smoke")` variant that injects `Thread.sleep(30)` into one listener for one tick; assert p99 jumps | Optional but cheap — 1 new `@Test` method; planner decides |
| `EMERGENCE` log markers fire | Capture log output via `ListAppender`, assert ≥1 entry with "EMERGENCE " prefix | Include in long-run test's assertions — same `TestLogCapture` helper used for ERROR-count assertion |
| R19 no regression | Full suite green — handled by verifier agent pre-phase-complete | `./gradlew test` must return exit 0; tracked in STATE.md by existing workflow |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests <single-test-class>` — the specific test being worked on
- **Per wave merge:** `./gradlew test --tests "com.paralife.engine.*"` — engine package tests (covers all new + reference tests)
- **Phase gate:** `./gradlew test` full suite green (per D-03 / R19)

### Wave 0 Gaps

- [ ] `src/main/java/com/paralife/metrics/EmergenceMetrics.java` — covers D-14, used by all R15/R16/R17
- [ ] `src/main/java/com/paralife/engine/TickWorkTimer.java` — covers D-11 mean/p99 assertions
- [ ] `src/main/java/com/paralife/random/RandomSource.java` (or `com.paralife.engine`) — covers D-09b
- [ ] RNG refactor in `SimulationEngine.java` / `ActionResolver.java` / `CompositeEnergyDistributor.java` — covers D-09b (test-blocking for R15)
- [ ] `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` — R15
- [ ] `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` — R16/R17/R18
- [ ] `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` (or inline) — shared D-05 signal helper
- [ ] `.planning/phases/16-emergent-behavior-tests/fixtures/.gitkeep` (directory) + `.gitignore` entry
- [ ] `.planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md` — R17 evidence writeup (wave-final)
- [ ] Framework install: none

## Security Domain

Not applicable. Phase 16 adds no authentication, no user-facing input surface, no new network endpoints, no persistent storage, no new secrets. All changes are internal to the JVM (test code, test-only seed RNG, internal Micrometer meters reachable only via the existing authenticated-free actuator already exposed by Phase 15). The existing actuator exposure (`health,info,metrics` [VERIFIED: application.yml:14]) stands unchanged.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | N/A — no new auth paths |
| V3 Session Management | no | N/A |
| V4 Access Control | no | N/A — actuator exposure unchanged |
| V5 Input Validation | no | N/A — no new user-facing inputs. `@TestPropertySource` is developer-controlled. |
| V6 Cryptography | no | N/A — RNG refactor is for reproducibility, not security. Comments should state `Random`, not `SecureRandom`. |

### Known Threat Patterns

None newly introduced. Existing concerns (e.g., WebSocket DoS, bot impersonation) are Phase 15's scope and unchanged.

## Complexity Estimates (for Wave Sizing)

| Research Area / Task | Complexity | Notes |
|----------------------|-----------|-------|
| `EmergenceMetrics` bean | XS | Literally 30 lines, mirrors `WebSocketMetrics` exactly |
| `TickWorkTimer` bean | S | Bookend listener pattern, Timer wiring, one test for the meter itself |
| `RandomSource` bean | XS | 10 lines + `@Value` annotation |
| RNG refactor: `SimulationEngine` | M | 9 call sites, ctor signature change, `CompositeFormationTest` ctor fix-up, risk of subtle behaviour change if shuffle-iteration-order changes relative to current ThreadLocalRandom — mitigate with null-check pattern (fallback to unseeded when `masterSeed=-1`) |
| RNG refactor: `ActionResolver` + `CompositeEnergyDistributor` | S | 2-3 call sites combined; smaller blast radius than SimulationEngine |
| `CompositeFormationDeterminismTest` (R15) | S | ~100 LOC, leans entirely on the bean work above |
| `EmergenceStabilityLoadTest` (R16/R17/R18) | L | 300+ LOC: setup, sampling loop, TriggerWatcher, 3 stability assertions + 5 emergence assertions + 7 load assertions + fixture dump + log-capture |
| `TriggerWatcher` helper | S | ~80 LOC, 2 straightforward unit tests |
| Fixture JSON writer + rollover | XS | ~30 LOC, Jackson does the heavy lifting |
| `.gitignore` update | XS | 2 lines |
| `16-EMERGENCE.md` writeup | M | Requires running the test, extracting numbers, writing narrative — allocate dedicated time |
| **Total estimate** | **~6-8 plans** over 3-4 waves | Wave 0: infra (beans + RNG refactor + gitignore). Wave 1: R15 test. Wave 2: R16/R17/R18 test + helpers. Wave 3: writeup + verification. |

## Sources

### Primary (HIGH confidence)

- `src/main/java/com/paralife/engine/TickEngine.java` — tick loop structure, no metric published (lines 79-109)
- `src/main/java/com/paralife/engine/SimulationEngine.java` — bond formation site (line 542), composite formation site (line 602), 6 ThreadLocalRandom hot-path usages
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — buff grant site (lines 700-711), infection start site (line 573), already-seeded Random pattern (lines 220, 1164)
- `src/main/java/com/paralife/engine/ActionResolver.java` — bonus-offspring ThreadLocalRandom (line 520)
- `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` — shuffle ThreadLocalRandom (line 77)
- `src/main/java/com/paralife/metrics/WebSocketMetrics.java` — pattern to mirror for EmergenceMetrics
- `src/main/java/com/paralife/bot/BotClient.java` (lines 85-86) — 6-arg ctor accepts injectable `Random`
- `src/main/java/com/paralife/engine/BuffRegistry.java` — grant entry point, 4 buff types
- `src/test/java/com/paralife/engine/LoadTest.java` — 100-bot full-stack harness
- `src/test/java/com/paralife/engine/PopulationDynamicsTest.java` — per-tick sampling pattern
- `src/test/java/com/paralife/engine/MetabolismIntegrationTest.java` — BondedPair + CompositeMember population counting
- `src/test/java/com/paralife/engine/EnvironmentFullStackSmokeTest.java` — codec-native full-stack pattern, `publisher.publishEvent` stepping
- `src/test/java/com/paralife/engine/CompositeFormationTest.java` — CompositeConfig knob reference
- `src/main/resources/application.yml` — production defaults for all knobs
- `build.gradle.kts` — verified dependency set; no new deps needed
- `.planning/phases/16-emergent-behavior-tests/16-CONTEXT.md` — 18 locked decisions
- `.planning/REQUIREMENTS.md` — R15–R19 table
- `.gitignore` — confirmed no fixture-directory entry

### Secondary (MEDIUM confidence)

- CLAUDE.md — tick pipeline ordering, single-threaded core, virtual-thread usage
- STATE.md archived session notes — MetabolismIntegrationTest flakiness, Phase 15.10 WebSocketMetrics wiring details

### Tertiary (LOW confidence)

None. All claims backed by file-line citations in the current codebase.

## Metadata

**Confidence breakdown:**
- RNG audit: HIGH — every call site grepped and verified against the current tree
- Counter increment sites: HIGH — all four locations identified with file:line
- Tick-work timing approach: HIGH — bookend pattern is standard Spring; single-threaded assumption confirmed by CLAUDE.md and code inspection
- Forced-formation config starting values: MEDIUM — extrapolated from CompositeFormationTest; not empirically validated in a full-stack 1000-tick run yet
- Counter-reset / DirtiesContext recommendation: MEDIUM — reasonable but untested against full-suite interactions
- Heap measurement: HIGH — `Runtime` API is well-characterised
- Trigger-watcher pattern: HIGH — design is straightforward; signal noise thresholds are planner tuning territory

**Research date:** 2026-04-21
**Valid until:** 2026-05-21 (30 days — stable infrastructure, no external library churn expected)
