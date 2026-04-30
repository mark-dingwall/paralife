# Phase 19: High-Density Placement & Partition-Aware World Execution — Pattern Map

**Mapped:** 2026-05-01
**Files analyzed:** 13 (4 NEW production, 1 NEW test class set, 8 MODIFIED)
**Analogs found:** 12 / 13

## File Classification

| File | Status | Role | Data Flow | Closest Analog | Match Quality |
|------|--------|------|-----------|----------------|---------------|
| `src/main/java/com/paralife/engine/EligibleCellIndex.java` | NEW | utility (sparse-set state holder) | event-driven (notify + sample) | `src/main/java/com/paralife/world/RockGenerator.java` (seeded init), `src/main/java/com/paralife/engine/BotRegistry.java` (concurrent map registry) | role+seed-pattern match |
| `src/main/java/com/paralife/engine/LiveEntityRegistry.java` | NEW | service (registry/projection) | request-response (snapshot per tick) | `src/main/java/com/paralife/engine/BotRegistry.java` | exact (same shape, sibling concern) |
| `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` | MODIFIED | controller (WS handler) | request-response | self (lines 450–467 placement loop replaced) | self-modify |
| `src/main/java/com/paralife/engine/SimulationEngine.java` | MODIFIED | service (tick handler) | event-driven | self — 9 grid scans (lines 295, 421, 505, 735, 870, 909, 924, 1172) | self-modify |
| `src/main/java/com/paralife/engine/EnvironmentEngine.java` | MODIFIED | service (tick handler) | event-driven | self — `buildStatusCaches()` lines 875–937, per-entity grid passes 596–650 | self-modify (per-entity only; diffusion stays grid) |
| `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` *(actual class lives in `TickBroadcaster.java` `@Order(50)` `onTick`)* | MODIFIED | service (tick handler) | event-driven | self — `TickBroadcaster.onTick` lines 178–223 already iterates `botRegistry.getAllBots()`; D-09 swap to `LiveEntityRegistry.snapshot()` | self-modify |
| `src/main/java/com/paralife/websocket/TickBroadcaster.java` | MODIFIED | service (tick handler) | event-driven | self (same `onTick` iteration; entity-list swap) | self-modify |
| `src/main/java/com/paralife/engine/BotRegistry.java` | MODIFIED | service (registry) | request-response | self — death hook integration via lines 93–105 (`unregisterByEntity`) | self-modify |
| `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` | NEW | test (unit) | n/a | `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java` (closest pure-unit), `src/test/java/com/paralife/engine/BuffRegistryTest.java` | role match (unit + ops + determinism) |
| `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` | NEW | test (integration; @SpringBootTest with seed) | n/a | `src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java` | exact (dual-run determinism harness) |
| `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` | NEW | test (integration; WS density) | n/a | `src/test/java/com/paralife/websocket/HundredBotIntegrationTest.java` | exact (high-density WS load harness) |
| `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` | NEW | test (integration; equivalence gate) | n/a | `src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java` (dual-run pattern); `RunObservables` digest accumulator | role+pattern match (no existing frame-byte digest test) |

---

## Pattern Assignments

### `src/main/java/com/paralife/engine/EligibleCellIndex.java` (utility, event-driven)

**Analogs:** `RockGenerator.java` (seeded init pattern), `BotRegistry.java` (Spring `@Component`).

**Imports / class shell — copy from `BotRegistry.java` lines 1–28:**

```java
package com.paralife.engine;

import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
// ...
@Component
public class EligibleCellIndex {
    private static final Logger log = LoggerFactory.getLogger(EligibleCellIndex.class);
    // ...
}
```

**Seeded init pattern — copy from `RockGenerator.java` lines 36–55, 119–123:**

```java
@PostConstruct
public void initialize() {
    // walk grid once, populate eligible-set (post-rock-init) — RockGenerator runs at @PostConstruct
    // and rock map is final by the time this bean's @PostConstruct fires.
}
```

`RockGenerator.buildRandom()` is the seed-or-unseeded idiom; `EligibleCellIndex` does NOT own its own RNG — sampling RNG is supplied by the caller (`WorldWebSocketHandler.spawnRng`, see below).

**Toroidal wrap idiom — `Math.floorMod`, used at `RockGenerator.java:108` and across codebase:**

```java
int cx = Math.floorMod(px + dx, width);
int cy = Math.floorMod(py + dy, height);
```

Use this in `notifyChanged(int px, int py)` for the 5×5 dirty bbox.

**Constraint-2/3 read source — `EnvironmentEngine.cellStatusCache` (line 177) + `Cell.FLAG_OVERCROWDED` (Cell.java:19) + `SimulationConfig.overcrowdingThreshold` (SimulationConfig.java:28). Reuse the existing neighbor walk shape from `SimulationEngine.processOvercrowding` lines 876–882:**

```java
int neighborCount = 0;
for (Position nPos : worldGrid.getNeighbors(x, y)) {
    Entity neighbor = worldGrid.getCell(nPos.x(), nPos.y()).occupant();
    if (neighbor instanceof Particle || neighbor instanceof Entity.BondedPair) {
        neighborCount++;
    }
}
```

For constraint-3, run this walk per Moore-neighbour of the candidate cell and reject if any occupied neighbour has `neighborCount == overcrowdingThreshold - 1`.

**Failure-mode hook — caller, not this file:** returns `null` from `sample(rng)` → `WorldWebSocketHandler` produces `E|503|GRID_FULL`.

---

### `src/main/java/com/paralife/engine/LiveEntityRegistry.java` (service, request-response)

**Analog:** `BotRegistry.java` — same package, same role shape, same `@Component` discipline.

**Class header — copy from `BotRegistry.java` lines 1–30:**

```java
package com.paralife.engine;

import com.paralife.world.Position;
// ...
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LiveEntityRegistry {
    private static final Logger log = LoggerFactory.getLogger(LiveEntityRegistry.class);
    // ...
}
```

**State-record pattern — copy `BotRegistry.BotState` lines 35–44:**

```java
public record EntityEntry(String entityId, Position position, /* kind tag */ ...) {}
```

**Insertion / removal — mirror `BotRegistry.register` / `unregisterByEntity` lines 62–105:**

```java
public void register(String entityId, Position pos) { /* dense-array add */ }
public void unregister(String entityId) { /* swap-and-pop O(1) */ }
```

**Snapshot-stable iteration — research §Pattern 2 + research code-example 296–301:**

```java
public List<EntityEntry> snapshot() {
    synchronized (this) {
        return new ArrayList<>(orderedEntities); // O(N) copy, N ≤ 256 today
    }
}
```

**Determinism — D-09 binding constraint:** insertion order must be deterministic given same seed + same WS-arrival order (already guaranteed by `WorldWebSocketHandler` single-threaded inbound). Mirror `BotRegistry`'s `ConcurrentHashMap` discipline; add a parallel ordered list for iteration.

**Death hook — must be notified at every site `BotRegistry.unregisterByEntity` is called.** Primary site: `DeathFinalizer.finalizeParticleDeath` line 84, `finalizeBondedPairDeath` lines 102–103, `cleanupCompositeMemberCellViaFinalizer` (called from `SimulationEngine.handleMemberDeath`). Secondary site: `SimulationEngine.collapseToMember` line 725. Wire `liveEntityRegistry.unregister(entityId)` immediately after each `botRegistry.unregisterByEntity(...)` call. (See Pitfall 4 in 19-RESEARCH.md.)

---

### `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (MODIFIED — placement path)

**Replace block — lines 450–467 (current 50-retry loop):**

```java
// CURRENT (to be replaced):
Random rng = spawnRng;
int x = -1, y = -1;
boolean placed = false;
for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
    x = rng.nextInt(worldGrid.getWidth());
    y = rng.nextInt(worldGrid.getHeight());
    if (worldGrid.trySetEntity(x, y, particle)) {
        placed = true;
        break;
    }
}

if (!placed) {
    if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
    if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
    sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
    return;
}
```

**Replace-with shape (research §Pattern 1, code example 487–491):**

```java
Position pos = eligibleCellIndex.sample(spawnRng);
if (pos == null) {
    if (!isRespawn && admissionGate != null) admissionGate.releaseSlot();
    if (admissionMetrics != null) admissionMetrics.incRejected(RejectionToken.GRID_FULL, session);
    sendFrame(session, new Frame.ErrorFrame(503, Optional.of(RejectionToken.GRID_FULL)));
    return;
}
int x = pos.x(), y = pos.y();
boolean placed = worldGrid.trySetEntity(x, y, particle);
if (!placed) { /* race lost — retry once or treat as GRID_FULL; planner decides */ }
// notify index of successful placement (5x5 bbox dirty)
eligibleCellIndex.notifyChanged(x, y);
```

**Constructor wiring — line 118 `@Autowired` ctor:** add `EligibleCellIndex eligibleCellIndex` parameter and field, mirroring existing `BotRegistry botRegistry` field at line 101.

**Field alongside line 116 (`Random spawnRng`):** add `private final EligibleCellIndex eligibleCellIndex;` (or non-final if a `resetSeed()`-style test reset is needed — research §Pitfall 3 says rng call count drops from 2 → 1 per placement; a single `nextInt(size)` sample preserves seed-contract semantics).

**Constant `MAX_PLACEMENT_ATTEMPTS` (line 88):** delete after refactor — no longer needed.

**Other registration sites that need the same pattern:** lines 87, 116, 144 are the wiring/seed sites for the placement RNG; they stay (only the 450–467 loop body changes).

---

### `src/main/java/com/paralife/engine/SimulationEngine.java` (MODIFIED — entity-list iteration)

**9 grid scans confirmed (matches research A2):**

| Line | Method | Purpose | Refactor target |
|------|--------|---------|-----------------|
| 295–302 | `processInteractions` | build particle position list | `liveEntityRegistry.snapshot().filter(Particle).map(pos)` |
| 421–428 | `processInteractions` | composite-member positions | same shape, filter `CompositeMember` |
| 505–512 | `processInteractions` | bonded-pair positions for composite formation | same shape, filter `BondedPair` |
| 735–803 | `processEnergyDecay` | per-cell decay walk | iterate live entities only (Particle + BondedPair branches preserved) |
| 870–899 | `processOvercrowding` | per-cell density check | iterate live entities; neighbour-count walk per entity unchanged |
| 909–919 | `processDeaths` (Phase 3a) | particle/bonded death sweep | iterate live entities; `if (!isAlive())` predicate unchanged |
| 924+ | `processDeaths` (composite half) | composite-member death sweep | same |
| 1172–1191 | `processNutrientSpawning` | per-empty-cell probability | **STAYS GRID-WALK** — out of scope (CONTEXT.md "Not in scope: Nutrient spawn") |

**Replace pattern — research code example 502–519:**

```java
// BEFORE (lines 294–302):
List<Position> particlePositions = new ArrayList<>();
for (int x = 0; x < width; x++) {
    for (int y = 0; y < height; y++) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof Particle) {
            particlePositions.add(new Position(x, y));
        }
    }
}

// AFTER:
List<Position> particlePositions = liveEntityRegistry.snapshot().stream()
    .filter(e -> worldGrid.getCell(e.position().x(), e.position().y()).occupant() instanceof Particle)
    .map(LiveEntityRegistry.EntityEntry::position)
    .collect(Collectors.toCollection(ArrayList::new));
Collections.shuffle(particlePositions, simRng);  // RNG call-count must match — see Pitfall 3
```

**Critical invariant — preserve `Collections.shuffle(..., simRng)` exactly as-is** (line 305, 429, 513). Changing the size or order of the shuffled list changes RNG-consumption count → golden-trace divergence.

**Single-threaded mutation invariant (D-08):** all mutations stay inside the `@Order(10) onTick` handler. Entity-list iteration changes the *source* of the position list, not *who* writes.

---

### `src/main/java/com/paralife/engine/EnvironmentEngine.java` (MODIFIED — per-entity segments only)

**Per-entity grid passes to refactor:**

| Line | Pattern | Refactor |
|------|---------|----------|
| 596–650 | per-entity env effect application | iterate `liveEntityRegistry.snapshot()` |
| 906–915 | per-cell mutagen entity-status writeback in `buildStatusCaches` | keep grid-walk for the **shadow-grid bit** (mutagenGrid is spatial), but the entity-status portion at lines 894–900 + 924–936 should iterate live entities |
| 924–936 | BUFFED bit grid scan (BuffRegistry.getBuffs O(1) per id) | iterate live entities only |

**STAYS GRID-WALK (diffusion / shadow grids — D-07 explicitly excludes these):**
- Lines 423, 437–438 — toxin diffusion CA pass
- Lines 532–540 — toxin path generator
- Lines 563–575 — mutagen diffusion CA
- Lines 651–680 — lightning per-cell scan
- Lines 1215, 1353 — fertility / serialization passes (per-cell intrinsic state)

**Reusable cache surface for `EligibleCellIndex` constraint-2 — line 1399:**

```java
Map<Position, Byte> cellStatusCacheView() {
    return Collections.unmodifiableMap(cellStatusCache);
}
```

`EligibleCellIndex.notifyChanged` reads via this view. Bit 0 (`Cell.FLAG_OVERCROWDED` ↔ `BIT_OVERCROWDED 0x01` at TickBroadcaster.java:96) is constraint-2.

---

### `src/main/java/com/paralife/websocket/TickBroadcaster.java` + the in-tree "PerceptionBroadcaster" (MODIFIED)

**Note:** there is no separate `PerceptionBroadcaster.java`. The `@Order(50)` perception-broadcast handler **is** `TickBroadcaster.onTick` at lines 176–223. Phase 19 either (a) leaves the file name as-is and refactors the iteration, or (b) extracts a `PerceptionBroadcaster` bean if planner wants the rename — that is Claude's Discretion. The PATTERNS.md treats them as one refactor target.

**Replace block — lines 185–222:**

```java
// CURRENT:
var bots = botRegistry.getAllBots();   // ConcurrentHashMap.values() — research Pitfall 6
if (bots.isEmpty()) return;
// ... iterate bots ...

// AFTER (D-09 deterministic snapshot):
List<LiveEntityRegistry.EntityEntry> entries = liveEntityRegistry.snapshot();
if (entries.isEmpty()) return;
// ... iterate entries; resolve session via botRegistry.getSessionForEntity(entityId) ...
```

**Critical invariants:**
- STALLED-skip at line 195 (`worldWebSocketHandler.isStalled(session)`) — preserve verbatim.
- `outboundSender.offer(sessionId, frame)` at line 206 — preserve.
- `drainAndBroadcastDeaths` at line 235 — independent of entity-list refactor; preserve verbatim (drains `botRegistry.drainDeaths()` for sessions whose entities just died this tick).

**Phase 19.1 boundary (D-12):** parallelisation of this loop is deferred. Do NOT introduce `parallelStream()` here.

---

### `src/main/java/com/paralife/engine/BotRegistry.java` (MODIFIED — death hook integration)

**Insertion point — line 94 (`unregisterByEntity`):**

The cleanest hook is at the call sites of `unregisterByEntity` (in `DeathFinalizer.finalizeParticleDeath:84`, `finalizeBondedPairDeath:102–103`, `SimulationEngine.collapseToMember:725`) — the planner can inject `LiveEntityRegistry` into `DeathFinalizer` and call `liveEntityRegistry.unregister(entityId)` alongside `botRegistry.unregisterByEntity(entityId)`.

Alternative: notify from inside `BotRegistry.unregisterByEntity` itself (lines 93–105). That couples `BotRegistry` to `LiveEntityRegistry`; the analog pattern (DeathFinalizer was created cycle-4 specifically to centralise cross-bean death cleanup, see DeathFinalizer.java lines 16–40) argues for the **DeathFinalizer hook**, not internal coupling inside BotRegistry.

**Recommendation:** modify `DeathFinalizer.java`, not `BotRegistry.java`, unless the planner finds an additional non-DeathFinalizer death path. (CONTEXT.md only mentions `BotRegistry` modification; planner should re-evaluate against this analog evidence.)

**`drainDeaths` at line 112 stays unchanged** — Phase 15.2 death-frame pipeline is orthogonal.

---

### Test files

#### `EligibleCellIndexTest.java` (NEW, unit)

**Analog:** `BuffRegistryTest.java` / `CompositeFormationDeterminismTest.java` — pure-JUnit, no Spring context.

**Pattern:**

```java
class EligibleCellIndexTest {
    @Test void addThenSampleReturnsAddedCell() { /* ... */ }
    @Test void removeIsO1AndDoesNotShift() { /* ... */ }
    @Test void sampleEmptyReturnsNull() { /* ... */ }
    @Test void seededSampleIsBitExact() {
        EligibleCellIndex idx = build(8, 8); // small grid for unit
        // populate deterministically
        Random r = new Random(42L);
        Position a = idx.sample(r);
        // re-build, same seed, same ops:
        Random r2 = new Random(42L);
        assertThat(idx2.sample(r2)).isEqualTo(a);
    }
    @Test void constraint3RejectsCellsThatWouldOvercrowdNeighbour() { /* ... */ }
}
```

#### `PlacementDeterminismTest.java` (NEW, integration)

**Analog:** `EnvironmentDeterminismTest.java` (lines 1–207, full file is the template).

**Copy these elements verbatim (style):**

```java
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false"
})
class PlacementDeterminismTest {
    @Autowired WorldGrid worldGrid;
    @Autowired EligibleCellIndex eligibleCellIndex;
    @Autowired WorldWebSocketHandler handler;
    @Autowired BotRegistry botRegistry;
    // ...

    @Test void placementsAreBitExactAcrossTwoRuns() {
        resetAll();
        List<Position> a = driveRegistrations(50);
        resetAll();
        List<Position> b = driveRegistrations(50);
        assertThat(b).as("D-06 bit-exact contract").isEqualTo(a);
    }

    private void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        handler.resetSeed();   // existing test helper at WorldWebSocketHandler:187
        // re-init eligible index after grid reset
    }
}
```

`resetAll` mirrors `EnvironmentDeterminismTest.resetAll` (lines 110–120). `handler.resetSeed()` already exists at `WorldWebSocketHandler.java:187` — reuse it.

#### `PlacementDensityIntegrationTest.java` (NEW, integration)

**Analog:** `HundredBotIntegrationTest.java` — `@SpringBootTest(webEnvironment = RANDOM_PORT)` with multiple WS clients connecting via `BlockingWebSocketClient`.

**Pattern: spin up N clients, register all, assert no retry storm metric, assert N+1th gets `E|503|GRID_FULL`.**

#### `GoldenTraceEquivalenceTest.java` (NEW, integration / equivalence gate)

**Analog:** `EnvironmentDeterminismTest.envOnlyRunsAreDeterministicAcrossTwoInvocations` (lines 90–108) — the dual-run RunObservables comparison pattern.

**Recommended digest format (research §Common Pitfalls / Open Question 3):** SHA-256 over outbound frame bytes captured via mock `OutboundSender`. Captures perception frames at minimum (D-10 minimum-viable contract). Mirror `RunObservables` accumulator at lines 57–66 with a `MessageDigest digest` field instead of int counters.

```java
@Test void byteIdenticalOutputAcrossRefactorBaseline() {
    resetAll();
    byte[] hashA = driveRunCapturingFrameDigest(seedScenario);
    resetAll();
    byte[] hashB = driveRunCapturingFrameDigest(seedScenario);
    assertArrayEquals(hashA, hashB);  // D-10 gate
}
```

---

## Shared Patterns

### Spring `@Component` + `@PostConstruct` init

**Source:** `RockGenerator.java` lines 36–55, `BotRegistry.java` line 27, every `engine/` bean.
**Apply to:** `EligibleCellIndex` (`@PostConstruct` walks grid post-rock-init), `LiveEntityRegistry` (no init needed; populated as bots register).

```java
@Component
public class X {
    private final WorldGrid worldGrid;
    public X(WorldGrid worldGrid) { this.worldGrid = worldGrid; }
    @PostConstruct void initialize() { /* ... */ }
}
```

### Toroidal coordinate wrap

**Source:** `RockGenerator.java:108`, `Position.wrap`, used everywhere.
**Apply to:** `EligibleCellIndex.notifyChanged` 5×5 bbox walk.

```java
int cx = Math.floorMod(px + dx, width);
int cy = Math.floorMod(py + dy, height);
```

### Seeded RNG contract (`@ConfigurationProperties` Long seed → `new Random(seed)` else `new Random()`)

**Source:** `WorldWebSocketHandler.buildRng()` lines 180–182; `RockGenerator.buildRandom()` lines 119–123 (uses `0L` sentinel; `WorldWebSocketHandler` uses `null` sentinel — keep `null` idiom, it matches `SpawnConfig`).
**Apply to:** No new seeded RNG owners. `EligibleCellIndex.sample(rng)` accepts an externally-supplied seeded `Random` (the existing `WorldWebSocketHandler.spawnRng`).

### Snapshot-stable iteration via shallow copy

**Source:** research §Pattern 2; existing pattern in `BotRegistry.drainDeaths` lines 112–119 (drain-under-lock then return copy).
**Apply to:** `LiveEntityRegistry.snapshot()` — every tick handler that iterates entities.

```java
public List<EntityEntry> snapshot() {
    synchronized (this) {
        return new ArrayList<>(orderedEntities);
    }
}
```

### Death-cleanup centralisation via `DeathFinalizer`

**Source:** `DeathFinalizer.java` lines 16–40 (Javadoc enumerates the recipe), 81–116 (call sites).
**Apply to:** `LiveEntityRegistry.unregister` hook — add inside each `finalize*Death` method **alongside** the existing `botRegistry.unregisterByEntity(...)` call.

### Tick-pipeline `@EventListener @Order(...)` invariant

**Source:** `CLAUDE.md §Architecture`, `TickBroadcaster.java:176–177`, `SimulationEngine.java:222–223`.
**Apply to:** All MODIFIED tick handlers — Phase 19 must NOT change `@Order` numbering or split a handler across orders. Entity-list iteration is intra-handler only.

### Dual-run determinism harness

**Source:** `EnvironmentDeterminismTest.java` lines 90–120 (`resetAll` + `driveRun` × 2 + observable equality).
**Apply to:** `PlacementDeterminismTest`, `GoldenTraceEquivalenceTest`.

---

## No Analog Found

| File | Role | Reason |
|------|------|--------|
| `GoldenTraceEquivalenceTest.java` (frame-byte digest specifically) | equivalence test with SHA-256 over WS outbound frames | No existing test captures outbound WS frame bytes for equivalence. `EnvironmentDeterminismTest` is the closest pattern (dual-run + observable equality) but uses int counters, not frame digests. Planner should design the digest-capture surface; recommendation in research §Open Question 3 is mock `OutboundSender` capturing `frameBytes` per tick. |

---

## Metadata

**Analog search scope:** `src/main/java/com/paralife/{engine,world,websocket}/`, `src/test/java/com/paralife/{engine,websocket}/`.
**Files scanned (Read or Grep):** `WorldWebSocketHandler.java`, `RockGenerator.java`, `BotRegistry.java`, `SimulationEngine.java` (3 regions), `EnvironmentEngine.java` (1 region + grep), `TickBroadcaster.java` (2 regions), `WorldGrid.java`, `SpawnConfig.java`, `DeathFinalizer.java`, `EnvironmentDeterminismTest.java`, `Cell.java` (grep), `SimulationConfig.java` (grep). Listed test directory; verified no separate `PerceptionBroadcaster.java` exists.
**Key code-text load-bearing:** lines 450–467 in WorldWebSocketHandler (placement loop to replace), 9 grid-scan sites in SimulationEngine, `BotRegistry.unregisterByEntity` recipe, `EnvironmentEngine.cellStatusCacheView()` accessor for constraint-2/3 reuse.
**Pattern extraction date:** 2026-05-01.
