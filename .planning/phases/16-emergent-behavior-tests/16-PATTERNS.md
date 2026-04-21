# Phase 16: Emergent Behavior Tests — Pattern Map

**Mapped:** 2026-04-21
**Files analyzed:** 14 new / 5 modified
**Analogs found:** 13 / 14 (one file — `RandomSource.java` — has a partial analog; one file — `EmergenceTestHelpers.java` — has no existing analog, use in-test helper patterns)

## File Classification

| File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `src/main/java/com/paralife/metrics/EmergenceMetrics.java` | metrics bean (`@Component`) | counter increment / pull-scrape | `src/main/java/com/paralife/metrics/WebSocketMetrics.java` | **exact** |
| `src/main/java/com/paralife/engine/TickWorkTimer.java` | engine bean / event-listener | event-driven (bookend `@EventListener`) | `src/main/java/com/paralife/metrics/WebSocketMetrics.java` (Timer setup) + `src/main/java/com/paralife/engine/*` `@EventListener @Order` pattern (e.g. `CompositeEnergyDistributor.onTick`) | role-match |
| `src/main/java/com/paralife/engine/RandomSource.java` (or `com.paralife.random`) | utility bean / factory | config-driven | **No direct analog** — closest pattern is `EnvironmentEngine` seeded-ctor (line 220, `new Random(config.seed())`) and `RockGenerator.buildRandom()` | partial |
| `src/main/java/com/paralife/engine/SimulationEngine.java` | existing engine — modify | tick pipeline | self (pattern already present at line 220 of `EnvironmentEngine` — inject `Random` via ctor) | exact |
| `src/main/java/com/paralife/engine/ActionResolver.java` | existing engine — modify | tick pipeline | `EnvironmentEngine` ctor pattern | exact |
| `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` | existing engine — modify | tick pipeline | `EnvironmentEngine` ctor pattern | exact |
| `src/main/java/com/paralife/engine/EnvironmentEngine.java` | existing engine — modify | tick pipeline | self (already has counter fields like `mutagenInfectionEventCount`) | exact |
| `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` | integration test (engine-direct) | request-response (tick publish / counter read) | `src/test/java/com/paralife/engine/EnvironmentFullStackSmokeTest.java` (for reset-per-test + `publisher.publishEvent` stepping); `src/test/java/com/paralife/engine/CompositeFormationTest.java` (for knobs + engine harness) | exact |
| `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` | integration test (full-stack) | WebSocket streaming + sampling | `src/test/java/com/paralife/engine/LoadTest.java` (bot-count harness) + `src/test/java/com/paralife/engine/PopulationDynamicsTest.java` (sampling loop) + `src/test/java/com/paralife/engine/MetabolismIntegrationTest.java` (`countPopulation` helper) | composite exact |
| `src/test/java/com/paralife/engine/EmergenceTestHelpers.java` (or `emergence/` subdir: `TriggerWatcher.java`, `PopulationHistory.java`, `RunFixtureWriter.java`) | test helper | in-memory state aggregation | **No existing test helper in `engine` package** — grep shows none. Closest pattern: private helpers inside `MetabolismIntegrationTest.countPopulation` + `PopulationDynamicsTest.countPopulation` | no analog — new helper |
| `.gitignore` addition | config | line-append | existing `.gitignore` at repo root | exact |
| `.planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md` | markdown narrative | doc | `.planning/phases/15-protocol-transport-overhaul/15-10-SUMMARY.md` (frontmatter), `15-UAT.md` (narrative test-run evidence format) | role-match |
| `.planning/phases/16-emergent-behavior-tests/fixtures/.gitkeep` | config | n/a | n/a | trivial |

## Pattern Assignments

### `src/main/java/com/paralife/metrics/EmergenceMetrics.java` (metrics bean)

**Analog:** `src/main/java/com/paralife/metrics/WebSocketMetrics.java` — exact match. Mirror the structure 1:1.

**Class skeleton (lines 29-49 of analog):**
```java
@Component
public class WebSocketMetrics {

    public static final String M_ACTIVE_SESSIONS   = "paralife.ws.active.sessions";
    public static final String M_TICK_FRAME_BYTES  = "paralife.ws.tick.frame.bytes";

    private final DistributionSummary tickFrameBytes;
    private final AtomicInteger activeSessionCount = new AtomicInteger();
    private final Gauge activeSessions;

    public WebSocketMetrics(MeterRegistry registry) {
        this.tickFrameBytes = DistributionSummary.builder(M_TICK_FRAME_BYTES)
                .description("Per-tick outbound frame payload size (raw, pre-deflate)")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        // ...
    }
}
```

**Adaptation for `EmergenceMetrics`:**
- Swap `DistributionSummary`/`Gauge` for four `Counter` fields.
- Constant names: `M_BONDED_PAIRS_FORMED="paralife.emergence.bonded.pairs.formed"`, `M_COMPOSITES_FORMED="paralife.emergence.composites.formed"`, `M_BUFFS_GRANTED="paralife.emergence.buffs.granted"`, `M_MUTAGEN_INFECTIONS="paralife.emergence.mutagen.infections"`.
- Four methods: `onBondedPairFormed()`, `onCompositeFormed()`, `onBuffGranted()`, `onMutagenInfection()` — each calls `counter.increment()`.
- Package: `com.paralife.metrics` (same as analog — confirmed by D-14 and RESEARCH §Architectural Responsibility Map).

---

### `src/main/java/com/paralife/engine/TickWorkTimer.java` (bookend listener bean)

**Analog:** Timer builder taken from `WebSocketMetrics.java` lines 40-44. `@EventListener @Order(n)` pattern taken from existing engine components — `CompositeEnergyDistributor.onTick` (lines 67-73).

**`@EventListener` shape (from `CompositeEnergyDistributor.java:67-73`):**
```java
@EventListener
@Order(15) // After SimulationEngine(10), before ActionResolver(20)
public void onTick(TickEvent event) {
    for (var composite : compositeRegistry.getAll()) { ... }
}
```

**Adaptation:**
- Two listener methods, `@Order(0)` (BEFORE `SimulationEngine` at 10) and `@Order(101)` (AFTER `TickBroadcaster` at 100) — matches D-11 definition ("TickEvent dispatch start to final `@Order(100)` listener completion").
- `AtomicLong tickStartNanos` holds the dispatch-start timestamp (per RESEARCH §Tick-Work Timing single-threaded-but-defensive rationale).
- `Timer` built with `publishPercentiles(0.5, 0.95, 0.99)` mirroring the `WebSocketMetrics.tickFrameBytes` DistributionSummary line.
- Constant: `M_TICK_WORK = "paralife.tick.work"`.
- Package placement: `com.paralife.engine` (lives with the pipeline it measures) — RESEARCH recommends this over `com.paralife.metrics` because the bookend listeners are pipeline concerns, not pure metric wiring.

---

### `src/main/java/com/paralife/engine/RandomSource.java` (new — no direct analog)

**Partial analog — seeded-Random ctor pattern:** `EnvironmentEngine.java:215-230` — production ctor reads `config.seed()` and chooses `new Random(seed)` vs `new Random()`.

**Excerpt (lines 215-221 of `EnvironmentEngine.java`):**
```java
// Production ctor
EnvironmentEngine(WorldGrid worldGrid, SeasonTracker seasonTracker,
                  EnvironmentConfig config, BuffRegistry buffRegistry,
                  FertilityConfig fertilityConfig, DeathFinalizer deathFinalizer,
                  EnvCleanupHooksBean envCleanupHooksBean) {
    this(worldGrid, seasonTracker, config, buffRegistry, fertilityConfig, deathFinalizer,
            envCleanupHooksBean,
            config.seed() == null ? new Random() : new Random(config.seed()));
}
```

**Adaptation — `RandomSource` skeleton (per RESEARCH §RNG Injection Pattern):**
```java
@Component
public class RandomSource {
    private final long masterSeed;

    public RandomSource(@Value("${paralife.seed.master:-1}") long masterSeed) {
        this.masterSeed = masterSeed;
    }

    public Random forComponent(String tag) {
        if (masterSeed == -1L) return new Random();           // production default: unseeded
        return new Random(masterSeed ^ (long) tag.hashCode());
    }
}
```

**Rationale for no analog:** No existing component centralises master-seed derivation. `EnvironmentEngine` reads its own seed property; `RockGenerator.buildRandom()` reads `paralife.world.rock.seed`. Centralising via `RandomSource.forComponent("simulation" | "action" | "ced")` is the cleanest Spring wiring — one `@Value`-bound property, five tagged sub-seeds (per RESEARCH §Seed-Derivation Scheme).

---

### RNG refactor — `SimulationEngine`, `ActionResolver`, `CompositeEnergyDistributor` (modified)

**Analog:** `EnvironmentEngine.java:215-220` ctor pattern (shown above).

**Call-site replacement excerpt — current code in `SimulationEngine.java:237-241`:**
```java
// Shuffle to prevent directional bias
Collections.shuffle(particlePositions, ThreadLocalRandom.current());

List<InteractionResult> results = new ArrayList<>();
ThreadLocalRandom rng = ThreadLocalRandom.current();
```

**Adaptation:**
1. Add `private final Random rng;` field.
2. Add `RandomSource source` as last ctor param; assign `this.rng = source.forComponent("simulation")` (tags: `"action"` for `ActionResolver`, `"ced"` for `CompositeEnergyDistributor`).
3. Replace every `ThreadLocalRandom.current()` with `this.rng` — 6 sites in `SimulationEngine` (lines 238, 241, 362, 446, 942, 1079, 1090), 1 site in `ActionResolver` (line 520), 1 site in `CompositeEnergyDistributor` (line 77).
4. **Test-callsite fix-up (critical):** `CompositeFormationTest.java:45` directly invokes `new SimulationEngine(...)` — add a `RandomSource` constructor argument there (either pass a stub `new RandomSource(-1L)` or make the ctor param nullable and null-check at each `rng.xxx()` site — RESEARCH recommends nullable + null-check mirroring the existing `environmentEngine != null` pattern at `SimulationEngine.java:278`).

---

### Counter increment sites — `SimulationEngine`, `EnvironmentEngine` (modified)

**Analog pattern:** `WebSocketMetrics` is injected into `TickBroadcaster` via ctor, then `metrics.recordFrameSize(...)` is called at the post-send site — `TickBroadcaster.java:187`:
```java
// Plan 15-10: record raw pre-deflate UTF-8 byte length on the DistributionSummary.
metrics.recordFrameSize(encoded.getBytes(StandardCharsets.UTF_8).length);
```

**Adaptation — exact injection sites (from RESEARCH §Counter Increment Sites, verified in code):**

| Counter | Site | Current code | New insertion |
|---|---|---|---|
| `bonded.pairs.formed` | `SimulationEngine.java:542` (end of `if (result instanceof BondFormation bond) {...}` block) | `bondEvents++;` | Insert before `bondEvents++`: `emergenceMetrics.onBondedPairFormed(); log.info("EMERGENCE bonded-pair-formed tick={} types={}+{} at=({},{})", tickNumber, bond.predator.type().name().substring(0,3), bond.prey.type().name().substring(0,3), pos.x(), pos.y());` |
| `composites.formed` | `SimulationEngine.java:602` (end of `if (result instanceof CompositeFormation cf) {...}` block) | `compositeEvents++;` | Insert before `compositeEvents++`: `emergenceMetrics.onCompositeFormed(); log.info("EMERGENCE composite-formed tick={} size=2 role-mix=[{},{}]", tickNumber, role1, role2);` |
| `buffs.granted` | `EnvironmentEngine.java:700` (top of `grantSurvivorBuffs` method) | n/a | At method entry: `emergenceMetrics.onBuffGranted(); log.info("EMERGENCE buff-granted tick={} entity={} survivor-of=mutagen", tickNumber, entityId);` |
| `mutagen.infections` | `EnvironmentEngine.java:573` (immediately after `mutagenInfectionEventCount++;`) | `mutagenInfectionEventCount++;` | Append: `emergenceMetrics.onMutagenInfection(); log.info("EMERGENCE infection-started tick={} entity={} strain={}", tickNumber, id, strain);` |

**Ctor wiring:**
- Add `EmergenceMetrics emergenceMetrics` param to `SimulationEngine` ctor (nullable, mirroring `environmentEngine` pattern at line 278).
- Same treatment for `EnvironmentEngine` — already large ctor; add one more.
- `CompositeFormationTest.java:45` passes `null` for the new param.

**Preserve:** the existing internal long counter `mutagenInfectionEventCount` — Plan 14-06 tests read it directly.

---

### `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` (R15 — engine-direct)

**Primary analog:** `EnvironmentFullStackSmokeTest.java` — same `@SpringBootTest` + `@TestPropertySource` + `@BeforeEach` reset + `publisher.publishEvent(new TickEvent(n))` stepping pattern. (Note: EnvironmentFullStackSmokeTest is `RANDOM_PORT` full-stack, but its reset + event-publish pattern transfers unchanged; R15 just drops the `webEnvironment=RANDOM_PORT` arg.)

**Reset pattern — `EnvironmentFullStackSmokeTest.java:113-121`:**
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

**Manual tick stepping — `EnvironmentFullStackSmokeTest.java:205`:**
```java
for (long tick = 1; tick <= maxTicks && !nonZeroStatusSeen; tick++) {
    publisher.publishEvent(new TickEvent(tick));
    // ...
}
```

**Secondary analog — config knobs:** `CompositeFormationTest.java:114`'s `CompositeConfig(0.03, 12, 1.0, 1, 3, 1, 2, 1, 4, 1, 1, 2, 1, true)` — use as starting-point values for forced-formation `@TestPropertySource` keys.

**Adaptation:**
- `@SpringBootTest` (NO `webEnvironment` — engine-direct per D-17).
- `@TestPropertySource` with: `paralife.tick.auto-start=false`, `paralife.seed.master=42`, `paralife.simulation.events.seed=42`, `paralife.world.rock.seed=42`, forced-formation knobs from RESEARCH §Forced-Formation Config.
- Two `@Test` methods: `sameSeedYieldsSameCompositeCount()` (two runs, same seed, counter equality); `compositeFormsFromSeededBonding()` (counter > 0 after 200 ticks).
- Read counter value via `meterRegistry.find(EmergenceMetrics.M_COMPOSITES_FORMED).counter().count()` — per RESEARCH Pitfall: don't go through `/actuator/metrics` HTTP.

---

### `src/test/java/com/paralife/engine/EmergenceStabilityLoadTest.java` (R16/R17/R18 — full-stack)

**Three composite analogs** (no single file covers everything):

**1. `LoadTest.java` — 100-bot harness (lines 21-55):**
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { ... })
class LoadTest {
    @LocalServerPort private int port;
    private final BotLauncher launcher = new BotLauncher();
    @AfterEach void tearDown() { launcher.shutdown(); }

    @Test void hundredBotsNoCorruption() throws Exception {
        String uri = "ws://localhost:" + port + "/ws/world";
        List<BotClient> bots = launcher.launch(uri, 100);
        // ...
    }
}
```

**2. `PopulationDynamicsTest.java` — sampling loop (lines 111-123):**
```java
int totalTicks = 500;
int sampleInterval = 50;
int samples = totalTicks / sampleInterval;
for (int sample = 0; sample < samples; sample++) {
    Thread.sleep((long) sampleInterval * 50);   // NOTE: 50ms hard-coded in analog — parameterise to intervalMs
    Map<String, Integer> counts = countPopulation();
    history.add(counts);
    log.info("Tick ~{}: CATALYST={} MEMBRANE={} SPORE={} total={}", ...);
}
```

**3. `MetabolismIntegrationTest.java` — population counting (lines 194-212 — COPY VERBATIM per RESEARCH Anti-Pattern 4):**
```java
private Map<String, Integer> countPopulation() {
    Map<String, Integer> counts = new HashMap<>();
    var snapshot = worldGrid.snapshot();
    for (int x = 0; x < snapshot.width(); x++) {
        for (int y = 0; y < snapshot.height(); y++) {
            Cell cell = snapshot.getCell(x, y);
            Entity occ = cell.occupant();
            if (occ instanceof Particle p)           counts.merge(p.type().name(), 1, Integer::sum);
            else if (occ instanceof BondedPair bp) { counts.merge(bp.primaryType().name(), 1, Integer::sum);
                                                     counts.merge(bp.secondaryType().name(), 1, Integer::sum); }
            else if (occ instanceof CompositeMember cm) counts.merge(cm.type().name(), 1, Integer::sum);
        }
    }
    return counts;
}
```

**Adaptations:**
- `@DirtiesContext(classMode = AFTER_CLASS)` on the class (Pitfall 2 mitigation for `MetabolismIntegrationTest` flakiness).
- `@Tag("slow")` optional per D-08 + Claude's Discretion.
- Sample every tick (`sampleInterval = 1`) for trigger-watcher fidelity — per RESEARCH §Per-tick sampling. Population sub-sample at 10 is fine.
- Replace `Thread.sleep((long) sampleInterval * 50)` with `Thread.sleep((long) sampleInterval * tickConfig.intervalMs())` — parameterise per Anti-Pattern 4.
- Poll `tickEngine.getCurrentTick()` instead of wall-clock — cleaner per RESEARCH §Waiting for tick N.
- **Seeded bots**: `BotLauncher.launch()` uses the 3-arg `BotClient` ctor (no seed). Per D-09a, full-stack seeded variant needs a `SeededBotLauncher` or inline launch loop that uses the 6-arg `BotClient(uri, species, brain, pollMs, respawnJitterMs, random)` ctor — RESEARCH §Bot launching flags this explicitly.

---

### `src/test/java/com/paralife/engine/EmergenceTestHelpers.java` / `emergence/` subdir (no existing analog)

**Survey of `src/test/java/com/paralife/engine/` for reusable helpers:** grep returned two hits for `countPopulation` — both are **private static methods inlined into the test class**, not shared helpers. There is no existing `src/test/java/com/paralife/engine/` helper class shared across tests.

**Recommendation:** New small classes in `src/test/java/com/paralife/engine/emergence/`:
- `TriggerWatcher.java` — per RESEARCH §Trigger-Watcher Mechanism skeleton. ~80 LOC. No code analog — design is custom.
- `PopulationHistory.java` — thin wrapper over `List<Map<String,Integer>>` with the `countPopulation` helper lifted from `MetabolismIntegrationTest.java:194-212` verbatim.
- `RunFixtureWriter.java` — Jackson `ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(...)` per RESEARCH §Don't Hand-Roll. Rollover: list `fixtures/*.json`, sort by `Files.getLastModifiedTime` desc, delete beyond index 4.
- `TestLogCapture.java` — Logback `ListAppender<ILoggingEvent>` attached to root logger in `@BeforeEach`, per RESEARCH Open Question #2. Used for both ERROR-count assertion and `EMERGENCE` log-marker verification.

**None of these exist in the codebase today** — this is genuinely new helper surface. Planner should not invent an analog; flag as "new, see RESEARCH sections for skeletons."

---

### `.gitignore` addition

**Analog:** existing `.gitignore` (shown above, 20 lines).

**Adaptation:** append the block from RESEARCH §Fixture JSON + .gitignore:
```
# Phase 16 emergence run fixtures (D-06b — keep locally, not committed)
.planning/phases/16-emergent-behavior-tests/fixtures/
```

---

### `16-EMERGENCE.md` narrative writeup

**Analog:** `.planning/phases/15-protocol-transport-overhaul/15-10-SUMMARY.md` (frontmatter style) for the YAML header. The narrative sections have no tight analog; R17 evidence format (observed values per signal + seed log + interpretation paragraph) is first-of-its-kind in this repo. `15-UAT.md` is the nearest prose-evidence analog but it documents black-box UAT, not emergent-signal narrative.

**Frontmatter excerpt (lines 1-17 of `15-10-SUMMARY.md`):**
```yaml
---
phase: 15-protocol-transport-overhaul
plan: 10
subsystem: observability
tags: [metrics, micrometer, actuator, observability, bytes-saved-deferred]
dependency_graph:
  requires: [15-08]
  provides: [ ... ]
  affects: [ ... ]
---
```

**Adaptation:** mirror the YAML block; body sections one-per-signal-from-D-04 (bonded-pair formation, composite formation, predator pressure, RPS boom-bust, flee-from-strong-predator); each section lists master seed, observed values, and a prose interpretation paragraph per R17 requirement.

---

## Shared Patterns

### Pattern A — `@Component` + ctor-injected `MeterRegistry`

**Source:** `WebSocketMetrics.java:39-49`
**Apply to:** `EmergenceMetrics`, `TickWorkTimer`
```java
@Component
public class XxxMetrics {
    public XxxMetrics(MeterRegistry registry) {
        this.foo = Counter.builder(M_FOO).description("...").register(registry);
    }
}
```

### Pattern B — `@EventListener @Order(n)` tick pipeline entry

**Source:** `CompositeEnergyDistributor.java:67-73`
**Apply to:** `TickWorkTimer` (orders 0 and 101)
**Contract:** default `SimpleApplicationEventMulticaster` dispatches synchronously on the publisher thread (tick-engine virtual thread) — bookend ordering is reliable (see RESEARCH Assumption A1 + one-time thread-name sanity log recommended on first run).

### Pattern C — Seeded `Random` via ctor injection

**Source:** `EnvironmentEngine.java:215-220`
**Apply to:** `SimulationEngine`, `ActionResolver`, `CompositeEnergyDistributor`
**Contract:** field is final; tests can route a fixed seed via `RandomSource.forComponent(tag)`; production default (`masterSeed=-1`) falls through to `new Random()`, preserving current non-reproducible behaviour.

### Pattern D — Nullable ctor param + call-site null-check

**Source:** `SimulationEngine.java:278` — `if (environmentEngine != null) { ... }`
**Apply to:** `EmergenceMetrics` injection into `SimulationEngine` + `EnvironmentEngine`
**Rationale:** avoids forcing `CompositeFormationTest.java:45` (which directly constructs `SimulationEngine`) to build a Micrometer registry. Null-check at each `.onXxx()` call site.

### Pattern E — `@BeforeEach` registry reset

**Source:** `EnvironmentFullStackSmokeTest.java:113-121`
**Apply to:** `CompositeFormationDeterminismTest`, `EmergenceStabilityLoadTest`
**Rationale:** Spring context caching (Pitfall 1) — stale `BotRegistry` / `CompositeRegistry` / `BuffRegistry` / `EnvironmentEngine` state leaks across tests without this block.

### Pattern F — Counter read via autowired `MeterRegistry`

**Source:** RESEARCH §Code Examples (anti-pattern note)
**Apply to:** both new tests
```java
@Autowired MeterRegistry meterRegistry;
long count = (long) meterRegistry.find(EmergenceMetrics.M_COMPOSITES_FORMED).counter().count();
```
**Do not:** go through `/actuator/metrics` HTTP (RESEARCH Anti-Pattern 2).

### Pattern G — Snapshot-based population counting

**Source:** `MetabolismIntegrationTest.java:194-212` (copy verbatim)
**Apply to:** `EmergenceStabilityLoadTest`, `PopulationHistory` helper
**Contract:** must count `Particle` + `BondedPair` (both types) + `CompositeMember` — the subtle case D-07 stability assertions depend on.

---

## No Analog Found

| File | Role | Reason |
|---|---|---|
| `src/test/java/com/paralife/engine/emergence/TriggerWatcher.java` | test helper | No "observation window with rolling-mean close" pattern exists in codebase. Custom. |
| `src/test/java/com/paralife/engine/emergence/RunFixtureWriter.java` | test helper | No Jackson fixture-dump helper exists. Trivial once skeleton in place. |
| `src/test/java/com/paralife/engine/emergence/TestLogCapture.java` | test helper | No Logback `ListAppender` helper in existing tests. RESEARCH Open Question #2 flagged. |
| `src/main/java/com/paralife/engine/RandomSource.java` | utility bean | No master-seed-to-component-sub-seed factory exists. Partial pattern from `EnvironmentEngine` seeded ctor. |

Planner should use RESEARCH §RNG Injection Pattern and §Trigger-Watcher Mechanism skeletons directly for these four files — not invent analogs.

---

## Metadata

**Analog search scope:** `src/main/java/com/paralife/{metrics,engine,websocket,bot}`, `src/test/java/com/paralife/engine`, `.planning/phases/15-*/*-SUMMARY.md`, `.gitignore`
**Files scanned:** ~40
**Pattern extraction date:** 2026-04-21
