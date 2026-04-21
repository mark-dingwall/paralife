# Phase 16: Emergent Behavior Tests - Pattern Map

**Mapped:** 2026-04-21
**Files analyzed:** 8 new + 7 modified = 15 total
**Analogs found:** 15/15 (every target file has a concrete in-tree analog)

## Scope note

RESEARCH.md already enumerated the full new-or-modified list and nominated analog files (`EnvironmentDeterminismTest`, `EnvironmentFullStackSmokeTest`, `PopulationDynamicsTest`, `LoadTest`, `WebSocketMetrics`, `WebSocketMetricsWiringTest`, `BotLauncher`). This PATTERNS.md nails those analogs to **verbatim line ranges** and surfaces the subtle conventions (sealed interfaces, `@EventListener` ordering contract, `@Lazy` cycle breaks, `BondedPair.formBond` signature constraint, `Counter.count()` is `double`) that the planner must preserve when splitting across waves.

## File Classification

### New files

| File | Role | Data Flow | Closest Analog | Match Quality |
|------|------|-----------|----------------|---------------|
| `src/main/java/com/paralife/metrics/EmergenceMetrics.java` | bean (Micrometer counter container) | event-driven | `src/main/java/com/paralife/metrics/WebSocketMetrics.java` | exact (sibling in same package) |
| `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` | integration test (engine-direct) | request-response (in-thread `publishEvent`) | `src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java` | exact (same pattern: seeded, `@DirtiesContext`-free, reset-between-runs) |
| `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` | integration test (full-stack long-run) | pub-sub (WS frames) + periodic sample | `EnvironmentFullStackSmokeTest` + `PopulationDynamicsTest` + `LoadTest` (composite) | exact (borrow structure from all three) |
| `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` | test utility (per-entity window observer) | stream/batch | none in-tree — new pattern derived from D-05 spec | role-match only |
| `src/test/java/com/paralife/engine/emergence/PopulationHistory.java` | test utility (per-tick snapshot + stats) | batch | inline loop in `PopulationDynamicsTest:111-148` (pattern only, no class) | extract/refactor |
| `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` | test utility (JSON dump + rollover) | file-I/O | — (Jackson + `java.nio.file` idiom) | no in-tree analog |
| `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` | test utility (Logback ListAppender) | stream | — (Logback canonical pattern, no prior) | no in-tree analog |
| `src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java` | test utility (bulk bot launch with per-bot seed) | pub-sub bootstrap | `src/main/java/com/paralife/bot/BotLauncher.java` | exact (parallel path that uses 6-arg `BotClient` ctor) |
| `src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java` (inferred — RESEARCH §Meta-validation #4 calls this out) | wiring test | event-driven verification | `src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java` | exact (sibling; same MeterRegistry.find pattern) |
| `.planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md` | narrative documentation | — | `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` | role-match (narrative-with-numbers) |
| `.planning/phases/16-emergent-behavior-tests/fixtures/.gitkeep` (or rely on runtime create + `.gitignore`) | build artifact dir | file-I/O | — | — |

### Modified files

| File | Role | Data Flow | Change | Closest Analog |
|------|------|-----------|--------|----------------|
| `src/main/java/com/paralife/engine/SimulationEngine.java` | tick-pipeline bean | event-driven | Inject `Random simRng` (replaces 4× `ThreadLocalRandom` at lines 238, 241, 446, 942, 1079, 1090); inject `EmergenceMetrics` + log `EMERGENCE bonded-pair-formed` @ line 521-524, `EMERGENCE composite-formed` @ line 577-578 | `EnvironmentEngine` ctor-injected `seed` pattern (line 220) |
| `src/main/java/com/paralife/engine/ActionResolver.java` | tick-pipeline bean | event-driven | Inject `Random actionRng` (replaces `ThreadLocalRandom` at line 520 bonus-offspring roll) | Same `Random`-via-ctor pattern |
| `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` | tick-pipeline bean | event-driven | Inject `Random` (replaces line 77 shuffle RNG) — or reuse `simRng` via a shared `RandomSource` bean | Same |
| `src/main/java/com/paralife/engine/FertilityInitializer.java` | `@PostConstruct` bean | batch-init | Inject `Random fertilityRng` via ctor (replaces line 46 `ThreadLocalRandom`) | Same |
| `src/main/java/com/paralife/engine/BuffRegistry.java` | shadow registry | event-driven | Inject `EmergenceMetrics` + call `incBuffGranted()` inside the `list.add(new ActiveBuff(...))` branch at line 71 (NOT the dedup/refresh branch at line 66-68) + `EMERGENCE buff-granted …` log — thread `currentTick` through `grant(...)` signature OR log from caller | D-14 RESEARCH.md line 408 |
| `src/main/java/com/paralife/engine/EnvironmentEngine.java` | tick-pipeline bean | event-driven | Inject `EmergenceMetrics` + call `incInfection()` at line 573 (already increments `mutagenInfectionEventCount++`) + `EMERGENCE infection-started …` log at same site | D-14 RESEARCH.md line 409 |
| `src/main/java/com/paralife/engine/TickEngine.java` | tick-loop runner | event-driven | Inject `MeterRegistry`; register `DistributionSummary("paralife.tick.work.ms")` with p50/p95/p99; record `(System.nanoTime() - startTime)/1e6` inside the existing `tickLoop` at lines 85-97 (elapsed is already computed) | `WebSocketMetrics` ctor builder pattern (line 40-44) |
| `src/main/java/com/paralife/world/Entity.java` (`BondedPair.formBond`) | sealed-interface factory | pure fn | Relax `ThreadLocalRandom rng` param on `formBond`/`hybridRate`/`bondDecayCost` (lines 258-300) → `RandomGenerator` or `Random`; caller in `SimulationEngine:505` threads seeded `simRng`. | — (API migration; no analog, but non-breaking for existing `ThreadLocalRandom` callers since TLR implements RandomGenerator) |
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` | WS endpoint | request-response | Inject `Random spawnRng` via ctor (replaces line 191 `ThreadLocalRandom`) | Same as `SimulationEngine` RNG injection |
| `src/main/java/com/paralife/bot/BotClient.java` | client | pub-sub | **Single-line fix** at line 294: `ThreadLocalRandom.current().nextLong(respawnJitterMs)` → `rng.nextLong(respawnJitterMs)` — restores ctor's seeded-Random contract | — (bug fix) |
| `.gitignore` | config | — | Append `/.planning/phases/16-emergent-behavior-tests/fixtures/*.json` (D-06b) | existing `.gradle/`, `build/` entries |
| `src/main/resources/application.yml` (optional) | config | — | May add `paralife.simulation.seed`, `paralife.simulation.action-seed`, `paralife.world.fertility.seed`, `paralife.world.spawn-seed` as null defaults (each binds to a record via `@ConfigurationProperties` — use existing `EnvironmentConfig.seed()` pattern) | `EnvironmentConfig` record |

## Pattern Assignments

### `src/main/java/com/paralife/metrics/EmergenceMetrics.java` (bean, event-driven)

**Analog:** `src/main/java/com/paralife/metrics/WebSocketMetrics.java:29-60`

**Why this analog:** sibling bean in the same package, identical responsibility (hold Micrometer meters + expose increment methods). Phase 15 D-10 / Plan 15-10 explicitly set this as the template.

**Imports + class-level annotation pattern** (lines 1-30):

```java
package com.paralife.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebSocketMetrics {

    public static final String M_ACTIVE_SESSIONS   = "paralife.ws.active.sessions";
    public static final String M_TICK_FRAME_BYTES  = "paralife.ws.tick.frame.bytes";
```

**Ctor + builder pattern** (lines 39-49):

```java
public WebSocketMetrics(MeterRegistry registry) {
    this.tickFrameBytes = DistributionSummary.builder(M_TICK_FRAME_BYTES)
            .description("Per-tick outbound frame payload size (raw, pre-deflate)")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

    this.activeSessions = Gauge.builder(M_ACTIVE_SESSIONS, activeSessionCount, AtomicInteger::get)
            .description("Current active WebSocket sessions")
            .register(registry);
}
```

**Increment method pattern** (lines 51-53):

```java
/** Called by TickBroadcaster after every successful send. */
public void recordFrameSize(int rawBytes) {
    tickFrameBytes.record(rawBytes);
}
```

**Copy verbatim for EmergenceMetrics:** four `Counter.builder(...).register(registry)` entries (bondedPairs, composites, buffsGranted, infections); four `inc…()` mutators; four `…Count()` read accessors returning `double`. Shape verified in RESEARCH.md §Emergence-Counter Surface lines 411-443.

**Subtle convention:** Meter name is dot-separated lowercase (`paralife.emergence.bonded.pairs.formed`) — hyphens cause Prometheus backend name coercion (WebSocketMetrics javadoc line 12 pitfall). The four names are locked in CONTEXT D-14.

---

### `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` (integration test, engine-direct)

**Analog:** `src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java:37-208` (full file is the template)

**Why this analog:** exact role match — seeded engine-direct `@SpringBootTest` with no web env, `@TestPropertySource` driving deterministic config, `resetAll()` between runs, statistical-equality assertions across runs.

**Annotation block** (lines 37-46):

```java
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        "paralife.simulation.seasons.year-length-ticks=100",
        "paralife.simulation.events.lightning.peak-lambda=0.06",
        "paralife.tick.auto-start=false"
})
class EnvironmentDeterminismTest {
```

**For Phase 16:** extend `properties` with D-12 forced-composite knobs (`paralife.bonding.bonding-probability=0.5`, `paralife.bonding.bond-energy-threshold=20`, `paralife.composite.can-form-composites=true`) and the new seed properties the planner introduces (§RNG Audit sites 1-3, 5, 13, 17 minimum set).

**Reset-between-runs pattern** (lines 110-120):

```java
private void resetAll() {
    // cycle-4 action item #1 + cycle-6 MEDIUM: use worldGrid.clear() (full wipe)
    // NOT worldGrid.clearEntity(...) — the latter preserves nutrients + flags
    // which would leak compost + lightning fertility state across runs.
    worldGrid.clear();
    environmentEngine.resetForTest();
    buffRegistry.clear();
    compositeRegistry.clear();
    botRegistry.clear();
    deathFinalizer.resetCountForTest();
}
```

**Run-observable harness + cross-run assertion** (lines 91-108):

```java
@Test
void envOnlyRunsAreDeterministicAcrossTwoInvocations() {
    resetAll();
    RunObservables a = driveRun();

    resetAll();
    RunObservables b = driveRun();

    assertThat(b.toxinEvents).as("toxinEvents deterministic").isEqualTo(a.toxinEvents);
    …
    assertThat(b.totalNutrients)
            .as("cycle-4 action item #9 (Codex MEDIUM): totalNutrients MUST be equal across runs — "
                    + "guards compost + lightning fertility drift")
            .isEqualTo(a.totalNutrients);
}
```

**For Phase 16:** replace observable fingerprint with `compositeRegistry.size()` (RESEARCH §Pattern 1 lines 213-249) and collect counts across N≥3 runs, assert `new HashSet<>(counts).hasSize(1)`.

**Drive-loop pattern** (lines 139-144):

```java
for (long tick = 1; tick <= 300; tick++) {
    environmentEngine.onTickEnvOnlyForTest(tick);   // direct engine call — bypasses Spring events
    …
}
```

**For Phase 16 R15:** use `applicationEventPublisher.publishEvent(new TickEvent(t))` instead (CONTEXT expects the full tick pipeline — combat + bonding + composite formation — engine-direct only bypasses WebSocket). RESEARCH §Code Examples lines 796-806 lock this form.

**Seeding guard anti-pattern to preserve** (lines 126-131):

```java
assertThat(compositeRegistry.getAll())
        .as("cycle-6 HIGH #4: EnvironmentDeterminismTest harness is PARTICLE-ONLY. "
                + "Composites routed through SimulationEngine.handleMemberDeath which uses "
                + "ThreadLocalRandom — seeding composites breaks determinism. ...")
        .isEmpty();
```

This guard **no longer applies** to Phase 16 — the whole point is to seed `SimulationEngine.handleMemberDeath`'s RNG (§RNG Audit site #5). Do NOT copy this assertion; Phase 16's test IS the composite-path determinism proof the old guard deferred.

**Subtle conventions:**
- `@SpringBootTest` without `webEnvironment=…` defaults to `WebEnvironment.MOCK` — no port, no Jetty, no bots. Required for R15 (D-17).
- `paralife.tick.auto-start=false` is load-bearing — without it, the virtual-thread tick loop interleaves with the direct `publishEvent` calls and drops determinism.
- `worldGrid.clear()` vs `worldGrid.clearEntity(x,y)`: `clear()` wipes entities AND nutrient/flag state; `clearEntity` preserves cell-level state → leaks across runs. Use `clear()`.

---

### `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` (integration test, full-stack long-run)

**Primary analog:** `src/test/java/com/paralife/engine/EnvironmentFullStackSmokeTest.java:75-261` (Jetty WS client + `@LocalServerPort` + `@BeforeEach` reset pattern)
**Secondary analog:** `src/test/java/com/paralife/engine/PopulationDynamicsTest.java:77-167` (per-type snapshot + `BotLauncher` + tick-sampling loop)
**Tertiary analog:** `src/test/java/com/paralife/engine/LoadTest.java:21-98` (100-bot harness + connection-count assertion bar)

**Annotation block** (EnvironmentFullStackSmokeTest lines 75-94):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.world.width=12",
        "paralife.world.height=12",
        "paralife.simulation.events.enabled=true",
        "paralife.simulation.events.seed=42",
        …
        "paralife.tick.auto-start=false"
})
class EnvironmentFullStackSmokeTest {
```

**For Phase 16:** use `auto-start=true` (long-run test drives live tick loop — RESEARCH §Pattern 2 line 262), grid 64x64 (not 12x12 — this test isn't trying to force a toxin hit on one bot's vision), bump to D-12 composite-forcing config, set `paralife.tick.interval-ms=15`.

**Autowire + Jetty client setup pattern** (EnvironmentFullStackSmokeTest lines 99-111, 165-178):

```java
@LocalServerPort
private int port;

@Autowired private WorldGrid worldGrid;
@Autowired private BotRegistry botRegistry;
@Autowired private CompositeRegistry compositeRegistry;
@Autowired private BuffRegistry buffRegistry;
@Autowired private EnvironmentEngine environmentEngine;
@Autowired private DeathFinalizer deathFinalizer;
@Autowired private ApplicationEventPublisher publisher;

private WebSocketClient client;
private Session session;

…

client = new WebSocketClient();
client.start();
ClientUpgradeRequest req = new ClientUpgradeRequest();
req.addExtensions("permessage-deflate; server_no_context_takeover");
session = client.connect(capture,
        URI.create("ws://localhost:" + port + "/ws/world"), req)
        .get(5, TimeUnit.SECONDS);
```

**For Phase 16:** don't connect a single probe `MessageCapture` — use `SeededBotLauncher.launchSeeded(uri, 100, masterSeed)` (parallel to `BotLauncher.launch`). Autowire the SAME beans plus `SessionRegistry`, `MeterRegistry`, `EmergenceMetrics`.

**Per-tick population-count pattern** (PopulationDynamicsTest lines 155-167):

```java
private Map<String, Integer> countPopulation() {
    Map<String, Integer> counts = new HashMap<>();
    var snapshot = worldGrid.snapshot();
    for (int x = 0; x < snapshot.width(); x++) {
        for (int y = 0; y < snapshot.height(); y++) {
            Cell cell = snapshot.getCell(x, y);
            if (cell.occupant() instanceof Particle p) {
                counts.merge(p.type().name(), 1, Integer::sum);
            }
        }
    }
    return counts;
}
```

**For Phase 16:** extend to also count `BondedPair` (attribute both types, per RESEARCH §Population-Observable Surface note) and `CompositeMember` (by `.type()`). Move into `PopulationHistory` utility.

**Sampling loop pattern** (PopulationDynamicsTest lines 111-123):

```java
for (int sample = 0; sample < samples; sample++) {
    Thread.sleep((long) sampleInterval * 50); // 50ms per tick
    Map<String, Integer> counts = countPopulation();
    history.add(counts);
    log.info("Tick ~{}: CATALYST={} MEMBRANE={} SPORE={} total={}", …);
}
```

**Anti-pattern to avoid** (RESEARCH §Anti-Patterns line 330-333): don't use `Thread.sleep(tickCount * intervalMs)` — slippage accumulates. Sample + check `history.tickCount() >= targetTicks` OR read `tickEngine.getCurrentTick()`.

**Reset `@BeforeEach`** (EnvironmentFullStackSmokeTest lines 113-121):

```java
@BeforeEach
void setUp() {
    worldGrid.clear();
    botRegistry.clear();
    compositeRegistry.clear();
    buffRegistry.clear();
    environmentEngine.resetForTest();
    deathFinalizer.resetCountForTest();
}
```

**For Phase 16:** keep this verbatim — long-run test still leaks state between methods.

**Shutdown pattern** (PopulationDynamicsTest lines 87-92, LoadTest lines 44-47):

```java
private final BotLauncher launcher = new BotLauncher();

@AfterEach
void tearDown() {
    launcher.shutdown();
}
```

**For Phase 16:** add `logCapture.detach()` in `@AfterEach` (D-11 ERROR-log assertion requires clean attach/detach around each run) + `RunFixtureWriter.dumpAndRollover(...)` call to persist run.

**Load-style connection assertion bar — DO NOT COPY** (LoadTest lines 58-72):

```java
assertThat(registered)
        .as("At least 80%% of bots should register under load")
        .isGreaterThanOrEqualTo((long) (botCount * 0.8));
…
assertThat(connected)
        .as("At least 50%% of bots should still be connected after 100 ticks")
        .isGreaterThanOrEqualTo((long) (botCount * 0.5));
```

**For Phase 16 (D-10 reframe):** this loose bar is explicitly replaced. Use `sessionRegistry.getSessionCount() == botCount` at mid-run/end-of-run checkpoints (after 50-tick connect transient). Load-stability contract is stricter — D-11 table.

**Subtle conventions:**
- Full-stack tests MUST use Jetty native `WebSocketClient` + `ClientUpgradeRequest.addExtensions("permessage-deflate; server_no_context_takeover")` — `StandardWebSocketClient` won't negotiate the extension (§State of the Art line 894-895). `SeededBotLauncher` calls `BotClient.connect()` which already handles this correctly.
- `paralife.tick.interval-ms=15` is at the low end of D-02's 10-20ms range. 10ms risks tick-work budget overruns on CI; 20ms blows wall-clock past the 90s local target. 15ms is the centred choice.
- `paralife.world.rock.seed=<non-zero>` if the test wants deterministic terrain (§RNG Audit #14 — already YAML-controllable).
- `@DirtiesContext` not required — `@BeforeEach` reset is cheaper and matches EnvFullStackSmoke pattern.

---

### `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` (test utility)

**Analog:** no in-tree precedent. Specification-driven — CONTEXT D-05 + RESEARCH §Trigger-Watcher Pattern lines 448-493.

**Core pattern to implement** (RESEARCH lines 455-477):

```java
class TriggerWatcher {
    private final Predicate<EntitySnapshot> trigger;
    private final Predicate<EntitySnapshot> observer;
    private final int windowTicks;
    private final int radius;
    private final double thresholdBaseline;
    private final boolean directionUp;

    private final List<ActiveWindow> activeWindows = new ArrayList<>();

    void tickIfWindowActive(PopulationHistory history) {
        for (EntitySnapshot e : history.latestEntities()) {
            if (trigger.test(e) && !alreadyTracking(e.id())) {
                activeWindows.add(new ActiveWindow(e, history.currentTick(), windowTicks));
            }
        }
        activeWindows.removeIf(w -> w.sampleOrClose(history, observer, radius));
    }
}
```

**Trigger source notes (CLAUDE.md §Env state projection — load-bearing):**
- STARVING entities → `Cell.flags & Cell.FLAG_STARVING` from `worldGrid.snapshot()`. **NOT** from `entityStatus` wire bitmask (STARVING lives on `Cell.flags`, not on bitmask — D-38/D-39/D-41).
- Buffed entities → `buffRegistry.getBuffs(entityId)` non-empty (existing idiom at `EnvironmentEngine:855`).
- Bonded entities → `instanceof Entity.BondedPair`.

**Toroidal distance** (CLAUDE.md + `FertilityInitializer.generatePatch` line 80-81):

```java
int x = Math.floorMod(cx + dx, width);
int y = Math.floorMod(cy + dy, height);
```

**Subtle convention — DO NOT add as `@EventListener`:** RESEARCH §Anti-Patterns line 332 — "any production listener would ship in release builds for a test-only purpose." Drive from the `@Test` method body via `Thread.sleep(...)` + sample, or call `watcher.tickIfWindowActive(history)` inside the existing sampling loop.

---

### `src/test/java/com/paralife/engine/emergence/PopulationHistory.java` (test utility)

**Analog:** inline code in `PopulationDynamicsTest:103-148` — extract into a reusable class.

**Source state-tracking pattern** (PopulationDynamicsTest lines 103, 111-123):

```java
List<Map<String, Integer>> history = new ArrayList<>();
…
for (int sample = 0; sample < samples; sample++) {
    Thread.sleep((long) sampleInterval * 50);
    Map<String, Integer> counts = countPopulation();
    history.add(counts);
}
```

**Oscillation math — new, from RESEARCH §Code Examples lines 873-887:**

```java
double rollingAmplitude(List<Integer> typeSeries, int windowSize) {
    double maxAmp = 0;
    for (int i = windowSize; i <= typeSeries.size(); i++) {
        List<Integer> win = typeSeries.subList(i - windowSize, i);
        int max = Collections.max(win), min = Collections.min(win);
        double mean = win.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean > 0) maxAmp = Math.max(maxAmp, (max - min) / mean);
    }
    return maxAmp;
}
```

**Subtle convention:** BondedPair contributes +1 to BOTH primary and secondary type counts (RESEARCH §Population-Observable Surface line 664) — species identity is more fundamental than bond structure for stability framing. CompositeMember contributes +1 to its `.type()`.

---

### `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` (test utility, file-I/O)

**Analog:** no in-tree precedent (first JSON fixture dump in the project).

**Pattern from RESEARCH lines 622-646 (JDK + Jackson, no new deps):**

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
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> runs = files
                .filter(p -> p.getFileName().toString().matches("run-.*\\.json"))
                .sorted(/* by lastModifiedTime desc — handle IOException in wrapper */)
                .collect(toList());
            for (int i = KEEP; i < runs.size(); i++) {
                Files.deleteIfExists(runs.get(i));
            }
        }
    }
}
```

**Subtle convention:** Jackson is transitive via Spring Boot — no new Gradle dep. Files live at `.planning/phases/16-emergent-behavior-tests/fixtures/` — add to `.gitignore` per D-06b.

**JSON schema locked by CONTEXT D-06b + RESEARCH lines 591-618.**

---

### `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` (test utility, stream)

**Analog:** no in-tree precedent. Canonical Logback `ListAppender` pattern (RESEARCH §Code Examples lines 846-868).

**Pattern:**

```java
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

**Subtle convention:** attach in `@BeforeEach`, detach in `@AfterEach`. Otherwise the appender stays attached across tests → OOM over a suite run.

---

### `src/test/java/com/paralife/engine/emergence/SeededBotLauncher.java` (test utility)

**Analog:** `src/main/java/com/paralife/bot/BotLauncher.java:33-74` (full body).

**Imports + virtual-thread connect pattern** (lines 1-74):

```java
public List<BotClient> launch(String serverUri, int count) throws Exception {
    ParticleType[] types = ParticleType.values();
    List<BotClient> launched = new CopyOnWriteArrayList<>();
    CountDownLatch allDone = new CountDownLatch(count);
    AtomicInteger registered = new AtomicInteger(0);

    for (int i = 0; i < count; i++) {
        ParticleType type = types[i % types.length];
        char species = switch (type) {
            case CATALYST -> 'C';
            case MEMBRANE -> 'M';
            case SPORE -> 'S';
        };
        BotClient bot = new BotClient(serverUri, species,
                new HeuristicBrain(HeuristicBrain.REPRODUCE_THRESHOLD));     // ← 3-arg ctor: defaults to ThreadLocalRandom
        launched.add(bot);
        bots.add(bot);

        Thread.startVirtualThread(() -> {
            try {
                bot.connect();
                if (bot.waitForRegistered(10, TimeUnit.SECONDS)) {
                    registered.incrementAndGet();
                }
            } catch (Exception e) {
                log.warn("Bot failed to connect: {}", e.getMessage());
            } finally {
                allDone.countDown();
            }
        });
    }
    if (!allDone.await(30, TimeUnit.SECONDS)) { … }
    return new ArrayList<>(launched);
}
```

**For Phase 16 — swap the 3-arg `BotClient` ctor for the 6-arg seeded form** (BotClient.java lines 85-97):

```java
public BotClient(String serverUri, char species, HeuristicBrain brain,
                 long respawnCooldownMs, long respawnJitterMs, Random rng) {
    …
    this.rng = rng;
}
```

**Seeded launch body (RESEARCH §Seeding Surface lines 557-580):**

```java
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
```

**Subtle conventions:**
- `Thread.startVirtualThread` — project runs on `spring.threads.virtual.enabled=true`; test must match.
- `CountDownLatch(count)` + `await(30s)` — standard barrier from `BotLauncher`.
- `CopyOnWriteArrayList` — concurrent add from virtual threads, safe to iterate later.
- `SplittableRandom.split()` produces uncorrelated streams per RESEARCH §Don't Hand-Roll line 345.
- **Depends on fix of `BotClient.handleDeath:294`** — without that, the seeded `rng` is still bypassed on respawn jitter and bots diverge post-first-death.

---

### `src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java` (wiring test — inferred from RESEARCH §Meta-validation #4)

**Analog:** `src/test/java/com/paralife/websocket/WebSocketMetricsWiringTest.java:36-107` (full file).

**Annotation + autowire pattern** (lines 36-48):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "paralife.tick.auto-start=false",
        "paralife.world.width=16",
        "paralife.world.height=16"
})
class WebSocketMetricsWiringTest {

    @Autowired SessionRegistry sessionRegistry;
    @Autowired TickBroadcaster broadcaster;
    @Autowired BotRegistry botRegistry;
    @Autowired WebSocketMetrics metrics;
    @Autowired MeterRegistry meterRegistry;
```

**Meter-find + increment-through-real-call-path pattern** (lines 56-97):

```java
@Test
void sessionRegisterUnregisterDrivesActiveSessionsGauge() {
    Gauge g = meterRegistry.find(WebSocketMetrics.M_ACTIVE_SESSIONS).gauge();
    assertNotNull(g, "active.sessions gauge should be registered");
    double before = g.value();

    WebSocketSession mock = mockSession("wiring-s1");
    sessionRegistry.register(mock);

    assertEquals(before + 1.0, g.value(), 0.0001, …);
    …
}
```

**For Phase 16:** one `@Test` per counter (bondedPair, composite, buffGranted, infection). Each test constructs the minimal trigger state (place two `Particle`s adjacent with energy > threshold, call `simulationEngine.processTick(1)` directly), then asserts `meterRegistry.find(EmergenceMetrics.M_BONDED_PAIRS).counter().count() == 1.0`.

**Subtle convention — "drive through real call paths":** lines 29-34 javadoc — "no `metrics.recordFrameSize` or `metrics.setActiveSessions` is called directly in this test." Same rule applies here — trigger the counter through the production code path (`SimulationEngine.processTick`, `BuffRegistry.grant`, etc.), not via `emergenceMetrics.incBondedPair()` directly.

---

### `src/main/java/com/paralife/engine/SimulationEngine.java` (modify — inject seeded RNG + counters)

**Analog:** `EnvironmentEngine.java:220` (existing seeded RNG injection pattern):

```java
this.rng = config.seed() == null ? new Random() : new Random(config.seed());
```

**Ctor update pattern** (existing SimulationEngine ctor lines 85-107):

```java
@org.springframework.beans.factory.annotation.Autowired
public SimulationEngine(WorldGrid worldGrid, SimulationConfig config,
                        BotRegistry botRegistry, BondingConfig bondingConfig,
                        CompositeRegistry compositeRegistry, CompositeConfig compositeConfig,
                        MetabolicProfile metabolicProfile, StarvationConfig starvationConfig,
                        SeasonTracker seasonTracker, BuffRegistry buffRegistry,
                        DeathCleanupHooks hooks,
                        @org.springframework.context.annotation.Lazy DeathFinalizer deathFinalizer,
                        @org.springframework.context.annotation.Lazy EnvironmentEngine environmentEngine) {
```

**For Phase 16 — extend with `EmergenceMetrics` + `Random simRng`** (follow the same `@Lazy` guard for the 13-collaborator boundary; RESEARCH §Emergence-Counter Surface line 446 notes "adding a 12th is mechanically fine but test constructors will need updating").

**RNG replacement sites** (line-exact from RESEARCH §RNG Audit):

| Line | Current | Replacement |
|------|---------|-------------|
| 238 | `Collections.shuffle(particlePositions, ThreadLocalRandom.current())` | `Collections.shuffle(particlePositions, simRng)` |
| 241 | `ThreadLocalRandom rng = ThreadLocalRandom.current()` | `Random rng = simRng` (reuse field) |
| 446 | `Collections.shuffle(bondedPairPositions, ThreadLocalRandom.current())` | `Collections.shuffle(bondedPairPositions, simRng)` |
| 942 | `ThreadLocalRandom.current().nextDouble()` | `simRng.nextDouble()` |
| 1079 | `ThreadLocalRandom.current().nextDouble() < shatterProb` | `simRng.nextDouble() < shatterProb` |
| 1090 | `ThreadLocalRandom rng = ThreadLocalRandom.current()` | `Random rng = simRng` (shadow) |

**Counter + log insertion sites** (verified):

- **Bonded-pair** — after line 524 `claimedForBonding.add(bond.secondaryPos)` (RESEARCH line 406):

```java
worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair);
worldGrid.clearEntity(bond.secondaryPos.x(), bond.secondaryPos.y());
claimedForBonding.add(bond.primaryPos);
claimedForBonding.add(bond.secondaryPos);
emergenceMetrics.incBondedPair();                                                   // + new
log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})",             // + new
        tickNumber, bond.predator.type().name().charAt(0),
        bond.prey.type().name().charAt(0), bond.primaryPos.x(), bond.primaryPos.y());
```

- **Composite** — after line 578 where both `CompositeMember`s are placed (RESEARCH line 407):

```java
worldGrid.setEntity(cf.pos1().x(), cf.pos1().y(), member1);
worldGrid.setEntity(cf.pos2().x(), cf.pos2().y(), member2);
emergenceMetrics.incComposite();                                                    // + new
log.info("EMERGENCE composite-formed tick={} size=2 compositeId={} role-mix=[{},{}]", // + new
        tickNumber, compositeId, role1, role2);
```

**Subtle conventions (CLAUDE.md load-bearing):**
- **`@Lazy` cycle break** (existing, don't touch) — `DeathFinalizer` + `EnvironmentEngine` are `@Lazy` on SimulationEngine. Adding `EmergenceMetrics` as a non-lazy dep is fine — `metrics` package has no cycle with `engine`.
- **`@Order(10)` contract** — `SimulationEngine.onTick` is `@Order(10)`, first in pipeline. Counter increments happen inside this window — they run on the tick thread, single-threaded, no volatility concerns (CLAUDE.md §Conventions).
- **Back-compat ctor at line 108+** — a 9-arg ctor exists for pre-Phase-14 unit tests. The new `EmergenceMetrics` + `Random` dep must either extend that ctor (preserves back-compat) or those unit tests must migrate. Planner's call — RESEARCH notes "test constructors will need updating" (line 446).

---

### `src/main/java/com/paralife/engine/TickEngine.java` (modify — DistributionSummary)

**Analog:** `WebSocketMetrics.java:40-44` (DistributionSummary builder pattern).

**Existing tick-loop elapsed computation** (lines 85-97):

```java
long tickNumber = tickCounter.incrementAndGet();
long startTime = System.nanoTime();

var event = new TickEvent(tickNumber);
eventPublisher.publishEvent(event);

long elapsed = (System.nanoTime() - startTime) / 1_000_000;
long sleepTime = Math.max(0, config.intervalMs() - elapsed);
```

**For Phase 16 — inject `MeterRegistry` + record (RESEARCH §Code Examples lines 810-826):**

```java
// ctor
public TickEngine(TickConfig config, ApplicationEventPublisher eventPublisher,
                  MeterRegistry meterRegistry) {
    …
    this.tickWork = DistributionSummary.builder("paralife.tick.work.ms")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
}

// inside tickLoop, after publishEvent:
long elapsedNs = System.nanoTime() - startTime;
tickWork.record(elapsedNs / 1_000_000.0);                 // record as double ms
long elapsed = elapsedNs / 1_000_000;                     // existing var — preserve
```

**Subtle conventions:**
- **Pre-existing `elapsed` in ms** is load-bearing for the warn-on-overrun log (lines 94-97). Compute nanos once, then derive both `double ms` (for meter) and `long ms` (for comparison). Don't double-sample `System.nanoTime()`.
- **Virtual-thread scheduling** — `tickLoop` runs on a single virtual thread. `DistributionSummary` is thread-safe. Single-writer means no contention.
- **`publishPercentiles(0.5, 0.95, 0.99)`** mirrors `WebSocketMetrics` — not mean-only. Read p99 via `.summary().takeSnapshot().percentileValues()` in the test.
- **Pitfall 7 (RESEARCH line 785):** DistributionSummary meters don't appear on `/actuator/metrics/` until one sample is recorded. OK for long-run test (1000 ticks = 1000 samples). Not OK for Phase-16 wiring test unless a sample primes the meter first.

---

### `src/main/java/com/paralife/world/Entity.java` (modify — `BondedPair.formBond` RNG signature)

**Analog:** none (API migration). Existing signature lines 258-282:

```java
public static BondedPair formBond(
        String id,
        Particle primary, Particle secondary,
        int primaryDecay, int primaryCombatTransfer, int primaryAttackPower, int primaryMaxEnergy,
        int secondaryDecay, int secondaryCombatTransfer, int secondaryAttackPower, int secondaryMaxEnergy,
        double bondRateBonusMin, double bondRateBonusMax,
        double bondDecayCostMin, double bondDecayCostMax) {
    ThreadLocalRandom rng = ThreadLocalRandom.current();     // ← replace
    …
}
```

**For Phase 16 — accept a `RandomGenerator` param** (RESEARCH §RNG Audit #13):

```java
public static BondedPair formBond(
        …,
        double bondRateBonusMin, double bondRateBonusMax,
        double bondDecayCostMin, double bondDecayCostMax,
        RandomGenerator rng                                   // ← new last param
) {
    // no longer `ThreadLocalRandom.current()` — use caller-supplied rng
    …
}

static int hybridRate(int rateA, int rateB, double bonusMin, double bonusMax, RandomGenerator rng) { … }
static int bondDecayCost(int decayA, int decayB, double costMin, double costMax, RandomGenerator rng) { … }
```

**Why `RandomGenerator` not `Random`:** JDK 17+ super-type that both `ThreadLocalRandom` and `Random` implement. Existing non-test callers passing TLR still compile without change; seeded tests pass a seeded `Random`. RESEARCH line 383 recommends this exact migration.

**Caller update in `SimulationEngine:505`** — thread `simRng` into the final param of the existing `Entity.BondedPair.formBond(...)` call.

**Subtle conventions (CLAUDE.md §Data modeling):**
- `Entity` is a **sealed interface** (`Particle`, `BondedPair`, `CompositeMember`, `Nutrient` are permitted). No new permits — `formBond` stays a static factory on the existing `BondedPair` record. Don't add a new permitted subtype.
- `BondedPair` is a **record** — its canonical ctor stays intact. `formBond` is the builder-equivalent; only its signature changes.
- **Package-independent primitive args** (javadoc line 255) — do not import `MetabolicProfile` or `BondingConfig` into `com.paralife.world`. The existing primitive-parameter convention is load-bearing.

---

### `src/main/java/com/paralife/engine/BuffRegistry.java` (modify — counter + log at grant)

**Analog:** RESEARCH §Emergence-Counter Surface line 408.

**Existing grant body** (lines 59-75):

```java
public void grant(String entityId, BuffType type, long expiryTick) {
    byEntity.compute(entityId, (key, existing) -> {
        CopyOnWriteArrayList<ActiveBuff> list =
                existing != null ? existing : new CopyOnWriteArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ActiveBuff b = list.get(i);
            if (b.type() == type) {
                long newExpiry = Math.max(b.expiryTick(), expiryTick);
                list.set(i, new ActiveBuff(type, newExpiry));
                return list;                                        // ← refresh branch (DO NOT count)
            }
        }
        list.add(new ActiveBuff(type, expiryTick));                 // ← new-buff branch (COUNT HERE)
        return list;
    });
    log.debug("Buff granted: entity={} type={} expiryTick={}", entityId, type, expiryTick);
}
```

**For Phase 16:** increment + EMERGENCE log only on the `list.add(...)` path (line 71) — dedup/refresh at line 67 is not a new-buff event (RESEARCH line 408 "Counting only the new-buff branch avoids double-counting expiry refreshes").

**Challenge — `currentTick` not in `grant(...)` signature.** Two options (RESEARCH line 408):
1. Add `long currentTick` to `grant(...)` signature — all callers must pass it.
2. Log from callers (`EnvironmentEngine.grantBuff`, `EnvPostActionReconciler`) where `tickNumber` is already in scope; leave `BuffRegistry.grant` logic-only.

**Recommend option 2** — smaller blast radius, matches the "trigger site owns the log" convention used at bond/composite sites in `SimulationEngine`.

**Subtle convention:** `ConcurrentHashMap.compute` callback runs under the map's per-key lock. The counter increment should happen OUTSIDE the lambda (returning a flag, then inc-and-log on the outside) to avoid doing work inside the lock. Or, since counter increment is AtomicDouble internally, it's safe inside — but be aware.

---

### `src/main/java/com/paralife/engine/EnvironmentEngine.java` (modify — counter + log at infection)

**Analog:** RESEARCH §Emergence-Counter Surface line 409.

**Existing infection-start site** (lines 565-573):

```java
int minDur = cfg.infectionDurationMin();
int maxDur = cfg.infectionDurationMax();
int dur = maxDur > minDur
        ? minDur + rng.nextInt(maxDur - minDur + 1)
        : minDur;
Infection infection = new Infection(dur, (byte) strain,
        cfg.damagePerTick(), dur, new Position(x, y));
infections.put(id, infection);
mutagenInfectionEventCount++; // Plan 14-06 Task 3b counter
```

**For Phase 16 — add after line 573:**

```java
infections.put(id, infection);
mutagenInfectionEventCount++;
emergenceMetrics.incInfection();                                        // + new
log.info("EMERGENCE infection-started tick={} entity={} strain={}",     // + new
        tickNumber, id, strain);
```

**Subtle conventions:**
- `tickNumber` and `strain` (local int line 552) and `id` (line 557) are all already in scope — no plumbing.
- `EnvironmentEngine` already has 11 collaborators (RESEARCH line 446). Adding `EmergenceMetrics` crosses into test-ctor-updates territory — expect `EnvironmentEngine` direct-ctor unit tests to require the new dep or a test-helper null-pass.
- The existing `mutagenInfectionEventCount` counter is a `long` field; the Micrometer counter mirrors it as `double`. Both remain — the long field is already consumed by `EnvironmentFullStackSmokeTest:233` and `EnvironmentPhaseGateIntegrationTest`.

---

### `src/main/java/com/paralife/bot/BotClient.java:294` (modify — single-line bug fix)

**Analog:** — (bug fix, no analog).

**Existing bug** (line 290-303):

```java
private void handleDeath() {
    alive.set(false);
    entityId = null;
    long jitter = respawnJitterMs > 0
            ? ThreadLocalRandom.current().nextLong(respawnJitterMs)           // ← bug
            : 0L;
    …
}
```

**Fix:**

```java
    long jitter = respawnJitterMs > 0
            ? rng.nextLong(respawnJitterMs)                                   // ← use ctor-injected rng
            : 0L;
```

**Subtle convention:** the ctor signature at line 85 `BotClient(String, char, HeuristicBrain, long, long, Random rng)` already accepts the injected `Random`. This single-line change is all that's needed to restore the seeded-contract across respawn jitter (RESEARCH §Pitfall 2 line 756).

## Shared Patterns

### Tick pipeline `@EventListener` contract

**Source:** CLAUDE.md §Architecture + `SimulationEngine:11-13` (imports) + `CompositeEnergyDistributor:67-72` (exemplar).

**Occupied slots (do not collide):**

| @Order | Listener | Owner |
|--------|----------|-------|
| 10 | `SimulationEngine` | combat, bonds, composite formation |
| 14 | `EnvironmentEngine` | env effects |
| 15 | `CompositeEnergyDistributor` | energy pool distribution |
| 20 | `ActionResolver` | bot actions |
| 25 | `EnvPostActionReconciler` | post-action env reconciliation |
| 50 | `PerceptionBroadcaster`, `TickBroadcaster` | WS out |
| 200 | `WebSocketKeepaliveService` | keepalive |

**Apply to:** Phase 16 adds NO new production `@EventListener`. The `TickWorkTimer` proposal is replaced by inline instrumentation inside `TickEngine.tickLoop` (RESEARCH §Load-Stability Instrumentation decision "Option 2 is smaller. Recommend option 2 for minimum diff" — line 519-520). Test-side sampling polls from the `@Test` method body, not an @EventListener.

**Exemplar signature** (`CompositeEnergyDistributor.java:67-69`):

```java
@EventListener
@Order(15) // After SimulationEngine(10), before ActionResolver(20)
public void onTick(TickEvent event) { … }
```

### WorldGrid read-safe snapshot pattern

**Source:** `PopulationDynamicsTest:157` + CLAUDE.md §Conventions.

**Apply to:** `PopulationHistory`, `TriggerWatcher`, any test-side sampler reading grid state during live tick loop.

```java
var snapshot = worldGrid.snapshot();   // takes read lock internally
for (int x = 0; x < snapshot.width(); x++) {
    for (int y = 0; y < snapshot.height(); y++) {
        Cell cell = snapshot.getCell(x, y);
        …
    }
}
```

**Never:** call `worldGrid.getCell(x, y)` directly inside a sampling loop — it takes and releases the read-lock per cell, thrashing the live tick loop's write-lock attempts. `snapshot()` amortises to one lock acquisition.

### Three-layer env state projection (read-only contract for tests)

**Source:** CLAUDE.md §Env state projection.

**Apply to:** every Phase 16 test that needs cell/entity state.

| Need | Correct source | Wrong source |
|------|---------------|--------------|
| STARVING entities | `cell.flags() & Cell.FLAG_STARVING` | wire `entityStatus` bitmask (redacted) |
| OVERCROWDED globally | `cell.flags() & Cell.FLAG_OVERCROWDED` | wire `cellStatus` (per-bot redacted per D-40) |
| Toxin intensity | `environmentEngine.toxinGridView()` / `stampToxinIntensityForTest` | wire `cellStatus` bit 1 (binary — loses intensity) |
| Mutagen strain | `EnvironmentEngine.mutagenGrid` via package-private view | wire `cellStatus` bit 2 (binary) |
| Infection state | `envCleanupHooksBean.getInfections()` | — |
| Active buffs | `buffRegistry.getBuffs(entityId)` | — |

**Why:** bot perception filters + redacts per the zero-trust design. Asserting on `Frame.TickFrame.cells()` from a bot session will under-report globally. Full-stack tests MUST autowire the authoritative server-side beans for assertions — use the WS path only to prove the wire plumbing, not as a data source for emergence math.

### Micrometer counter read pattern

**Source:** `WebSocketMetricsWiringTest:57, 74`.

**Apply to:** all emergence-counter assertions.

```java
Counter c = meterRegistry.find(EmergenceMetrics.M_BONDED_PAIRS).counter();
assertNotNull(c);
assertThat(c.count()).isGreaterThan(0.0);       // double, not long — RESEARCH §Pitfall 6
```

**Never:** `assertThat((long) c.count()).isGreaterThan(0L)` — silently downcasts and hides fractional counts if any are ever introduced. Stay in double-land.

### Full-stack test bean autowire list

**Source:** `EnvironmentFullStackSmokeTest:102-108` (canonical list).

**Apply to:** `EmergenceStabilityLoadTest`. Autowire at minimum:

```java
@LocalServerPort private int port;
@Autowired WorldGrid worldGrid;
@Autowired BotRegistry botRegistry;
@Autowired CompositeRegistry compositeRegistry;
@Autowired BuffRegistry buffRegistry;
@Autowired EnvironmentEngine environmentEngine;
@Autowired DeathFinalizer deathFinalizer;
@Autowired SessionRegistry sessionRegistry;
@Autowired MeterRegistry meterRegistry;
@Autowired EmergenceMetrics emergenceMetrics;
@Autowired EnvCleanupHooksBean envCleanupHooksBean;  // infection map — canonical read per EnvironmentDeterminismTest:190-192
```

### `@TestPropertySource` knob convention

**Source:** `PopulationDynamicsTest:26-75` (exhaustive); `EnvironmentFullStackSmokeTest:76-94` (concise).

**Apply to:** `EmergenceStabilityLoadTest`, `CompositeFormationDeterminismTest`.

**Key convention (EnvironmentFullStackSmokeTest:65-68 comment):** config prefix is `paralife.world` (from `GridConfig`), NOT `paralife.grid`. Every new seed property goes on a record via `@ConfigurationProperties(prefix = "paralife.…")` — follow the `EnvironmentConfig.seed()` precedent (RESEARCH line 545).

### Pipeline ctor `@Lazy` cycle break (for new Spring deps)

**Source:** `SimulationEngine:85-107` (exemplar).

**Apply to:** ctor updates across `SimulationEngine`, `EnvironmentEngine`, `BuffRegistry` when adding `EmergenceMetrics`.

**Rule:** new dep is `@Autowired` regular if no cycle; `@Lazy` if a cycle with an already-`@Lazy` dep exists. `EmergenceMetrics` is a leaf bean (depends only on `MeterRegistry`) → no cycle → regular injection.

### Sealed-interface + record immutability

**Source:** CLAUDE.md §Conventions + `Entity.java` header.

**Apply to:** the `BondedPair.formBond` migration — DO NOT widen the sealed hierarchy, DO NOT add mutable fields. Any new RNG param stays as a method parameter, not a field.

## No Analog Found (explicitly — these are net-new patterns)

| File | Role | Reason | Fallback |
|------|------|--------|----------|
| `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` | test obs | First per-entity sliding-window observer in the project | CONTEXT D-05 + RESEARCH §Trigger-Watcher Pattern lines 448-493 (spec-only) |
| `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` | file-I/O | First JSON fixture dump | JDK `java.nio.file` + `ObjectMapper` from Jackson (transitive via Spring) |
| `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` | log capture | First Logback ListAppender usage | Canonical Logback pattern (RESEARCH §Code Examples lines 846-868) |
| `.planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md` | narrative | First emergence writeup | CONTEXT D-06 format; author after long-run test results materialise |

## Metadata

**Analog search scope:** `src/main/java/com/paralife/{engine,metrics,bot,websocket,world}`, `src/test/java/com/paralife/engine`, `src/test/java/com/paralife/websocket`, `src/test/java/com/paralife/metrics`
**Files scanned:** 14 analogs read; RESEARCH.md §RNG Audit (17 sites) line-verified across `SimulationEngine`, `ActionResolver`, `CompositeEnergyDistributor`, `FertilityInitializer`, `Entity.BondedPair.formBond`, `BotClient`, `WorldWebSocketHandler`, `EnvironmentEngine`
**Pattern extraction date:** 2026-04-21

---

*Phase: 16-emergent-behavior-tests*
*Pattern map complete. Planner can now reference analog excerpts directly in per-plan action sections.*
