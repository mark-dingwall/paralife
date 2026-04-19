# Phase 14: Environmental Rules — Pattern Map

**Mapped:** 2026-04-17
**Files analyzed:** 14 (9 new, 5 modified)
**Analogs found:** 14 / 14 (100% in-codebase coverage)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `src/main/java/com/paralife/engine/EnvironmentEngine.java` (NEW) | tick-pipeline component | event-driven / CA-compute | `engine/SimulationEngine.java` + `engine/CompositeEnergyDistributor.java` | exact (role + data flow + @Order slot) |
| `src/main/java/com/paralife/engine/EnvironmentConfig.java` (NEW) | root `@ConfigurationProperties` record | config-bind | `engine/MetabolicProfile.java` (nested records under single prefix) | exact |
| `src/main/java/com/paralife/engine/BuffRegistry.java` (NEW) | shadow registry (@Component) | cross-cutting state | `engine/CompositeRegistry.java` (+ `BotRegistry.java`) | exact |
| `src/main/java/com/paralife/engine/ActiveBuff.java` (NEW, may be nested in BuffRegistry) | immutable record + enum | data-model | `engine/BotRegistry.BotState` nested record | exact |
| `src/main/java/com/paralife/engine/ToxinEvent.java` (NEW) | immutable event-state record | data-model | `world/Entity.BondedPair` / `engine/CompositeRegistry.CompositeState` | role-match |
| `src/main/java/com/paralife/engine/MutagenEvent.java` (NEW) | immutable event-state record | data-model | Same as ToxinEvent | role-match |
| `src/main/java/com/paralife/engine/Infection.java` (NEW) | immutable per-entity record | data-model | `world/Entity.Particle` (immutable record with `withX` helpers) | role-match |
| `src/main/java/com/paralife/engine/ToxinPathGenerator.java` (NEW) | pure-math util | transform (waypoints → spline path) | `engine/FertilityInitializer.java` (math helpers + toroidal wrap + package-private method for test) | role-match (both generate coordinate sets on toroidal grid) |
| `src/main/java/com/paralife/engine/CellularAutomaton.java` (NEW, optional — may inline) | pure-math util | CA diffusion (double-buffer) | None in codebase (new pattern — `FertilityInitializer.generatePatch` is closest analog for "iterate grid with math") | no exact analog (see No Analog section) |
| `src/main/java/com/paralife/engine/SimulationEngine.java` (MODIFY) | existing tick component | CRUD + event-driven hook | Self — add compost hook inside `processDeaths` and `BuffRegistry.unregisterEntity` cleanup | N/A (self-modify) |
| `src/main/java/com/paralife/engine/ActionResolver.java` (MODIFY) | existing tick component | event-driven + buff-aware | Self — read `BuffRegistry` for move-range + attack-cure-reduction | N/A (self-modify) |
| `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` (MODIFY) | existing tick component | perception encoding | Self — consume status caches, encode into `CellView` | N/A (self-modify) |
| `src/main/java/com/paralife/websocket/Messages.java` (MODIFY) | sealed interface + records | JSON serialization | Self — extend `CellView` with 4-arg → 6-arg constructor chain | N/A (self-modify, back-compat pattern) |
| `src/main/resources/application.yml` (MODIFY) | YAML config | config | Self — add `paralife.simulation.events.*` section | N/A (self-modify) |

## Pattern Assignments

### `EnvironmentEngine.java` (tick-pipeline component, event-driven / CA-compute)

**Analog:** `src/main/java/com/paralife/engine/SimulationEngine.java` (structure) + `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` (compact `@Order(15)` example)

**Imports pattern** (SimulationEngine.java:1-26, CompositeEnergyDistributor.java:1-17):
```java
package com.paralife.engine;

import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
// Prefer Random (seedable) over ThreadLocalRandom in EnvironmentEngine
// so Poisson rolls + path generation are deterministic in tests (see Pitfall 6).
```

**@Component + @EventListener + @Order pattern** (CompositeEnergyDistributor.java:35-58 — cleanest mini-example):
```java
@Component
public class CompositeEnergyDistributor {

    private static final Logger log = LoggerFactory.getLogger(CompositeEnergyDistributor.class);

    private final WorldGrid worldGrid;
    private final CompositeRegistry compositeRegistry;
    private final CompositeConfig config;

    public CompositeEnergyDistributor(WorldGrid worldGrid,
                                       CompositeRegistry compositeRegistry,
                                       CompositeConfig config) {
        this.worldGrid = worldGrid;
        this.compositeRegistry = compositeRegistry;
        this.config = config;
    }

    @EventListener
    @Order(15) // After SimulationEngine(10), before ActionResolver(20)
    public void onTick(TickEvent event) {
        for (var composite : compositeRegistry.getAll()) {
            processCompositeEnergy(composite);
        }
    }
```

**NOTE on @Order(15) collision:** `CompositeEnergyDistributor` already occupies `@Order(15)`. Spring `@Order` ties are broken non-deterministically. Planner MUST choose either `@Order(14)` (before CompositeEnergyDistributor) or `@Order(16)` (after). Recommendation: `@Order(14)` — env damage/infection must resolve before CompositeEnergyDistributor drains energy, so composite members see post-env energy when their shared pool is updated. Alternatively, use a chained @Order string explicitly documented in the javadoc.

**Early-exit + log pattern** (SimulationEngine.java:79-117):
```java
@EventListener
@Order(10) // Before TickBroadcaster (default order = Integer.MAX_VALUE)
public void onTick(TickEvent event) {
    if (!config.enabled()) {
        return;
    }
    processTick(event.tickNumber());
}

/**
 * Process one simulation tick. Public for direct use in tests.
 */
public void processTick(long tickNumber) {
    int width = worldGrid.getWidth();
    int height = worldGrid.getHeight();

    // Phase 1: Interaction resolution (bonding, combat, composite formation)
    int[] interactionCounts = processInteractions(width, height);
    // ...
    if (log.isDebugEnabled()) {
        log.debug("Tick {} simulation: combat={}, bonds={}, ...", tickNumber, ...);
    }
}
```

**Grid write pattern (immutable Cell + withX)** (SimulationEngine.java:585-591):
```java
// Compost-style write (will be used in applyCompost helper)
worldGrid.setCell(x, y, cell.withNutrientLevel(
        Math.min(cell.nutrientLevel() + COMPOST_FULL, MAX_NUTRIENT)));
// Entity write (used for toxin/mutagen damage)
worldGrid.setEntity(x, y, p.withEnergy(p.energy() - damage));
```

**Key invariants:**
- All grid mutations via `worldGrid.setCell` / `setEntity` / `clearEntity` (never mutate `Cell` instances — records are immutable).
- Single-threaded execution on publisher thread (synchronous event propagation). Never add `@Async`.
- Wrap `onTick` body in try/catch to prevent pipeline stall (Pitfall 9 in RESEARCH.md).
- Shadow grids (`byte[][] toxinGrid`, `mutagenGrid`) allocated once in constructor, reused every tick.
- Use `& 0xFF` on every byte read (Pitfall 1).
- Per-tick status caches must be rebuilt-or-cleared even when config disabled (Pitfall 7).

---

### `EnvironmentConfig.java` (`@ConfigurationProperties` root record with nested records)

**Analog:** `src/main/java/com/paralife/engine/MetabolicProfile.java` (nested record pattern under single prefix)

**Prefix + nested records pattern** (MetabolicProfile.java:24-113):
```java
@ConfigurationProperties(prefix = "paralife.simulation.types")
public record MetabolicProfile(
        TypeProfile catalyst,
        TypeProfile membrane,
        TypeProfile spore
) {

    public record TypeProfile(
            int maxEnergy,
            int decayPerTick,
            int combatEnergyTransfer,
            // ... 11 total fields
            int starvationFloor
    ) {
        public TypeProfile {
            if (maxEnergy <= 0)
                throw new IllegalArgumentException("maxEnergy must be > 0: " + maxEnergy);
            // ... full validation cascade
        }
    }

    public MetabolicProfile {
        if (catalyst == null) throw new IllegalArgumentException("catalyst profile missing");
        // ...
    }

    public TypeProfile forType(Entity.ParticleType type) {
        return switch (type) {
            case CATALYST -> catalyst;
            case MEMBRANE -> membrane;
            case SPORE -> spore;
        };
    }

    public static MetabolicProfile defaults() { ... }
}
```

**Compact-constructor validation pattern** (SeasonsConfig.java:30-36, FertilityConfig.java:24-35):
```java
public SeasonsConfig {
    if (yearLengthTicks < MIN_YEAR_LENGTH_TICKS)
        throw new IllegalArgumentException(
                "yearLengthTicks must be >= " + MIN_YEAR_LENGTH_TICKS + ": " + yearLengthTicks);
    if (amplitude < 0.0 || amplitude > 1.0)
        throw new IllegalArgumentException("amplitude must be in [0, 1]: " + amplitude);
}

public static SeasonsConfig defaults() {
    return new SeasonsConfig(200, 0.5);
}
```

**Nested per-type lookup record** (mirror for `Toxin.Resistance`):
```java
// EnvironmentConfig.Toxin.Resistance — follows MetabolicProfile.forType pattern
public double resistanceFor(Entity.ParticleType type) {
    return switch (type) {
        case CATALYST -> catalyst;
        case MEMBRANE -> membrane;
        case SPORE -> spore;
    };
}
```

**Key invariants:**
- Single `@ConfigurationProperties(prefix = "paralife.simulation.events")` at root; nested records auto-bind via Spring.
- Rely on project-wide `@ConfigurationPropertiesScan` in `ParalifeApplication.java:8` — no explicit `@EnableConfigurationProperties` needed.
- Validation in compact constructors; throw `IllegalArgumentException` (matches SeasonsConfig/FertilityConfig/MetabolicProfile).
- Provide `public static EnvironmentConfig defaults()` for test construction (matches all sibling configs).
- Field validation order: null-checks first, then bounds, then cross-field invariants (e.g., `pathPointsMax >= pathPointsMin`).
- **Season enum binding:** Prefer `SeasonTracker.Season peakSeason` over `String peakSeason` — Spring binds enums from YAML string values automatically. Verify in tests (no circular package issues; both live in `com.paralife.engine`).

---

### `BuffRegistry.java` (shadow registry `@Component`)

**Analog:** `src/main/java/com/paralife/engine/CompositeRegistry.java` (rich mutable state variant) + `src/main/java/com/paralife/engine/BotRegistry.java` (simpler Map-of-records variant)

**@Component + ConcurrentHashMap skeleton** (BotRegistry.java:23-45):
```java
@Component
public class BotRegistry {

    private static final Logger log = LoggerFactory.getLogger(BotRegistry.class);

    /** Immutable state for a registered bot. */
    public record BotState(String sessionId, String entityId, Position position) {}

    private final ConcurrentHashMap<String, BotState> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> entityToSession = new ConcurrentHashMap<>();

    public void register(String sessionId, String entityId, Position position) {
        var state = new BotState(sessionId, entityId, position);
        bySession.put(sessionId, state);
        entityToSession.put(entityId, sessionId);
        log.debug("Bot registered: session={} entity={} pos={}", sessionId, entityId, position);
    }
```

**Death cleanup hook (called from SimulationEngine.processDeaths)** (BotRegistry.java:70-76):
```java
/**
 * Unregister a bot by entity ID (e.g. on entity death).
 */
public void unregisterByEntity(String entityId) {
    var sessionId = entityToSession.remove(entityId);
    if (sessionId != null) {
        bySession.remove(sessionId);
        log.debug("Bot unregistered (entity death): entity={} session={}", entityId, sessionId);
    }
}
```

**CopyOnWriteArrayList for mutable-list-of-records pattern** (CompositeRegistry.CompositeState, lines 42-57):
```java
public static class CompositeState {
    private final String compositeId;
    private final CopyOnWriteArrayList<String> memberIds;
    private final ConcurrentHashMap<String, Position> memberPositions;
    // ...
```

**`clear()` for test support** (BotRegistry.java:122-127, CompositeRegistry.java:227-231):
```java
/**
 * Clear all registrations (for testing).
 */
public void clear() {
    bySession.clear();
    entityToSession.clear();
}
```

**Key invariants:**
- `ConcurrentHashMap<String, List<ActiveBuff>>` keyed by entity ID (D-19, D-47).
- `List<ActiveBuff>` stored as `CopyOnWriteArrayList` so perception-thread reads are safe while tick thread writes.
- Every `unregisterByEntity` call in `SimulationEngine.processDeaths` must be mirrored to `BuffRegistry.unregisterEntity` (Pitfall 5).
- `clear()` method for tests (all existing registries have this).
- `ActiveBuff` is a nested `public record` inside `BuffRegistry` (mirror of `BotRegistry.BotState`).
- `BuffType` is a nested `public enum` inside `BuffRegistry` (single source of truth).
- Expiry sweep: `entrySet().removeIf` after `replaceAll` because `ConcurrentHashMap.replaceAll` does not remove on null return (see RESEARCH.md Pattern 4 note).

---

### `ActiveBuff.java` / `BuffType` (can live nested inside BuffRegistry)

**Analog:** `BotRegistry.BotState` (BotRegistry.java:31):
```java
public record BotState(String sessionId, String entityId, Position position) {}
```

**Recommended shape** (derived from RESEARCH.md Pattern 4):
```java
public record ActiveBuff(BuffType type, long expiryTick) {}

public enum BuffType {
    ATTACK_PLUS_1,      // +1 attack power
    MOVEMENT_PLUS_1,    // hop-to-range-2 (reuses SPORE reproduce-range=2 code)
    SENSOR_PLUS_1,      // 5×5 → 7×7 vision
    UPKEEP_MINUS_1      // -1 decay per tick (or modulus-skip if decay already 1)
}
```

**Key invariants:**
- Records are immutable; granting "another" of same type = new record added to list.
- Nested `public record` inside `BuffRegistry` is idiomatic (matches `BotRegistry.BotState`, `CompositeRegistry.CompositeState`).
- No circular dependency with `Entity` — buffs are pure type+expiry data.

---

### `ToxinEvent.java` / `MutagenEvent.java` / `Infection.java` (immutable event-state records)

**Analog:** `world/Entity.java` (sealed interface with record implementations) + `engine/TickEvent.java` (simple event record)

**Simple event record pattern** (TickEvent.java:9-14):
```java
public record TickEvent(long tickNumber, Instant timestamp) {
    public TickEvent(long tickNumber) {
        this(tickNumber, Instant.now());
    }
}
```

**Record with `withX` mutation helpers** (Entity.Particle.java:99-101, Cell.java:45-67):
```java
/** Return a copy with adjusted energy, clamped to [0, maxEnergy]. */
public Particle withEnergy(int newEnergy) {
    return new Particle(id, type, Math.clamp(newEnergy, 0, maxEnergy), maxEnergy);
}

public Cell withNutrientLevel(int level) {
    return new Cell(occupant, flags, level);
}
```

**Recommended shape for `Infection`:**
```java
public record Infection(long ticksLeft, byte strain, int damagePerTick) {
    public Infection decrement() {
        return new Infection(ticksLeft - 1, strain, damagePerTick);
    }
    public boolean isExpired() {
        return ticksLeft <= 0;
    }
}
```

**Recommended shape for `ToxinEvent`** (stores full spline pre-sampled):
```java
public record ToxinEvent(
        long spawnTick,
        long lifetimeTicks,
        List<Position> prePath,   // arc-length-sampled waypoints (D-06 "cells per tick")
        int headIdx,              // advances each tick at configured speed
        long seed                 // for future visualizer replay
) {
    public ToxinEvent withHeadIdx(int newIdx) {
        return new ToxinEvent(spawnTick, lifetimeTicks, prePath, newIdx, seed);
    }
}
```

**Key invariants:**
- Records stored in `List<ToxinEvent>` / `List<MutagenEvent>` owned by `EnvironmentEngine`.
- Max 1 active per type (D-03) — enforce via null-check before new spawn, not via list length.
- Immutable — replace entire record when head advances.
- `Infection` map keyed by entity ID lives in `EnvironmentEngine` (not BuffRegistry — infection is pre-cure transient state, buff is post-cure).

---

### `ToxinPathGenerator.java` (pure-math util, transform)

**Analog:** `src/main/java/com/paralife/engine/FertilityInitializer.java` (structurally closest: iterates toroidal coords with math helper + package-private method for testing)

**Package-private helper for testable math** (FertilityInitializer.java:65-87):
```java
/**
 * Generate one fertility patch centered at {@code (cx, cy)} with radial
 * linear falloff out to {@code radius}. Uses max-merge so overlapping
 * patches keep the higher level per cell. Toroidal via {@link Math#floorMod}.
 *
 * <p>Package-private for direct testing.
 */
void generatePatch(int cx, int cy, int radius, int width, int height) {
    for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > radius) continue;
            // ...
            int x = Math.floorMod(cx + dx, width);
            int y = Math.floorMod(cy + dy, height);
```

**Seedable Random + package-private method pattern** (adapt):
```java
// ToxinPathGenerator — mirror FertilityInitializer's package-private method for direct testing
// Inject Random (not ThreadLocalRandom) so path generation is reproducible in tests.

@Component
public class ToxinPathGenerator {

    private final Random rng;  // Supplied by EnvironmentEngine (optionally seeded)

    public ToxinPathGenerator(Random rng) { this.rng = rng; }

    /** Package-private for testing — generate N waypoints from edge A to edge B. */
    List<Position> generateWaypoints(int width, int height,
                                      int pathPointsMin, int pathPointsMax,
                                      int offsetMin, int offsetMax) {
        // Adapt microbes.js:207-223 — generate in UN-WRAPPED coordinates (Pitfall 8).
        // Waypoints on edges; intermediate points evenly spaced + perpendicular offset.
        // Wrap only at final Position.wrap step when caller materializes grid cells.
    }

    /** Package-private for testing — evaluate Catmull-Rom spline (see RESEARCH.md Pattern 2). */
    static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (
            (2.0 * p1)
            + (-p0 + p2) * t
            + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
            + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        );
    }
}
```

**Microbes.js reference (lines 207-223, 242-254)** — already cited in CONTEXT.md D-05 and RESEARCH.md Pattern 2.

**Key invariants:**
- Waypoints generated in un-wrapped "flat" coordinates; only wrap at final grid materialization (Pitfall 8).
- Package-private methods enable direct unit tests without Spring context (mirrors `FertilityInitializer.generatePatch`).
- Use `Math.floorMod` (not `%`) for toroidal wrap (project-wide convention, FertilityInitializer.java:80-81).
- Pre-sample spline at event spawn into a `List<Position>` array so head advance is O(1) per tick (RESEARCH.md line 405).

---

### `CellularAutomaton.java` (NEW math util — optional; may inline into EnvironmentEngine)

**Analog:** None in Paralife codebase (novel pattern). Closest structural analog is `FertilityInitializer.generatePatch` for "iterate grid with math," but CA double-buffering is new territory.

**Recommended shape** (from RESEARCH.md Pattern 3):
```java
// No in-codebase analog — follow RESEARCH.md Pattern 3 exactly.
void diffuseStep(byte[][] src, byte[][] dst, int w, int h,
                  double diffusionRate, double decayRate) {
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            int self = src[x][y] & 0xFF;   // unsigned read
            int neighborSum = 0;
            int neighborCount = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = Math.floorMod(x + dx, w);
                    int ny = Math.floorMod(y + dy, h);
                    neighborSum += src[nx][ny] & 0xFF;
                    neighborCount++;
                }
            }
            double neighborAvg = neighborSum / (double) neighborCount;
            double mixed = (1.0 - diffusionRate) * self + diffusionRate * neighborAvg;
            int after = (int) Math.round(mixed * (1.0 - decayRate));
            dst[x][y] = (byte) Math.clamp(after, 0, 255);
        }
    }
}
```

**Key invariants:**
- Double-buffered: caller provides `src` (read) and `dst` (write) arrays; swap or copy outside this method (Pitfall 2).
- Moore neighborhood (8 cells) to match codebase convention — `WorldGrid.getNeighbors` is already 8-neighbor (WorldGrid.java:156-159).
- **Cannot** reuse `WorldGrid.getNeighbors` here — that returns `Position` objects and operates on `Cell[]`; CA needs raw `byte[][]` for performance. Rationale documented in RESEARCH.md "Don't Hand-Roll" table.
- All byte reads masked with `& 0xFF` (Pitfall 1).
- Apply threshold clear after diffuse+decay: `if (dst[x][y] < threshold) dst[x][y] = 0;` to prevent long-tail spread.

**Decision point for planner:** Inline into `EnvironmentEngine` if total CA logic is <50 lines, or extract to `CellularAutomaton.java` if shared between toxin + mutagen paths. RESEARCH.md Project Structure lists it as "(NEW optional)" for this reason.

---

### `SimulationEngine.java` (MODIFY — compost hook + BuffRegistry cleanup)

**Self-analog**, existing `processDeaths` structure (SimulationEngine.java:603-659):
```java
private int processDeaths(int width, int height) {
    int deaths = 0;

    // Phase 3a: Particle and BondedPair death
    for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
            Cell cell = worldGrid.getCell(x, y);
            if (cell.occupant() instanceof Particle p && !p.isAlive()) {
                botRegistry.unregisterByEntity(p.id());    // <-- ADD: buffRegistry.unregisterEntity(p.id())
                                                           // <-- ADD: environmentEngine.applyCompost(new Position(x, y))
                worldGrid.clearEntity(x, y);
                deaths++;
            } else if (cell.occupant() instanceof Entity.BondedPair bp && !bp.isAlive()) {
                botRegistry.unregisterByEntity(bp.primaryEntityId());    // <-- ADD both buff unregs
                botRegistry.unregisterByEntity(bp.secondaryEntityId());
                worldGrid.clearEntity(x, y);                             // <-- ADD applyCompost
                deaths++;
            }
        }
    }

    // Phase 3b: CompositeMember death
    // ... (handleMemberDeath, revertToBondedPair, dissolveToParticles also call clearEntity)
    // Phase 3c: Panic zone — pool=0 total death calls clearEntity in a loop (line 773-776)
```

**Required modifications:**
1. Inject `BuffRegistry buffRegistry` in constructor (add to existing 9-param constructor at line 59-73).
2. Inject `EnvironmentEngine environmentEngine` in constructor (creates circular dependency — resolve via `@Lazy` or apply compost via `ApplicationEventPublisher` event — planner decides).
3. **Create a helper** `clearEntityAndCompost(x, y, entityId)` that centralizes: `buffRegistry.unregisterEntity(entityId)` + `environmentEngine.applyCompost(pos)` + `worldGrid.clearEntity(x, y)`. Pitfall 4 requires this — SimulationEngine has 3 distinct death branches (3a Particle/BondedPair, 3b CompositeMember, 3c panic-zone pool=0) and every `clearEntity` site must apply compost.
4. `dissolveToParticles` (line 740-757) intentionally does NOT compost — dissolution is not death; particles continue living.
5. `revertToBondedPair` (line 708-735) does NOT compost — similarly not death.

**Key invariants:**
- Don't double-compost (bondedpair dies → 1 compost application, not 2 for its constituent types).
- Preserve existing `FN-8` invariant: `clearOccupants` preserves environment (WorldGrid.java:140-151).
- Preserve existing `FN-3` combat counting invariant (SimulationEngine.java:160-164 — composite-member combats counted).

---

### `ActionResolver.java` (MODIFY — buff-aware movement + attack-cure-reduction)

**Self-analog**, existing SPORE reproduce-range=2 code (ActionResolver.java:429-444):
```java
// D-18: walk `reproduceRange` steps in the given direction (SPORE=2, others=1).
// FN-9: for range > 1 fall back one step closer if the far cell is blocked
int range = profile.reproduceRange();
int minCandidate = range > 1 ? range - 1 : 1;
Position target = null;
for (int candidate = range; candidate >= minCandidate; candidate--) {
    Position t = ra.bot.position();
    for (int step = 0; step < candidate; step++) {
        t = dir.apply(t, worldGrid.getWidth(), worldGrid.getHeight());
    }
    if (claimedCells.contains(t)) continue;
    if (worldGrid.getCell(t.x(), t.y()).hasOccupant()) continue;
    target = t;
    break;
}
```

**Required modifications:**
1. **D-15 `+1 movement` buff:** Extract the range-walking loop above into a package-private helper `findTargetAtRange(from, dir, range, claimedCells, w, h)` (verified pattern in RESEARCH.md Example A). Call from both `resolveReproduce` AND `resolveMove`. For `resolveMove`, default `range=1`; if `buffRegistry.hasBuff(entityId, MOVEMENT_PLUS_1)`, bump to `range=2` (8+16=24 candidate cells, D-15).
2. **D-20 attack-cure-reduction:** Inside combat-resolution branch (currently in `SimulationEngine.processInteractions`, not ActionResolver — verify placement with planner), when a successful attack lands on a MUTATING defender, call `environmentEngine.reduceInfection(defenderId, attackCureReduction)`. This is the only cross-component mutation; consider exposing via a method on EnvironmentEngine or a dedicated interface.
3. **D-15 `+1 attack` buff:** Apply at damage computation — if attacker has `ATTACK_PLUS_1`, `attackPower += 1` before any starvation-intensity multiplication. Placement: `applyAttackBoost` at SimulationEngine.java:520-526 (not ActionResolver).

**Key invariants:**
- Preserve FN-9 fallback (SPORE fallback from range-2 to range-1 when blocked).
- Buff reads are O(1) via `BuffRegistry.hasBuff(entityId, BuffType)` — no iteration in hot paths.
- All attack modifiers compose: base → attack+1 buff → starvation-intensity multiplier (order matters for integer rounding; planner decides).

---

### `PerceptionBroadcaster.java` (MODIFY — status caches + vision-scoped overcrowding + sensor-buff-aware radius)

**Self-analog**, existing `cellToView` (PerceptionBroadcaster.java:269-288):
```java
static CellView cellToView(Cell cell) {
    int flags = cell.flags();
    if (cell.isEmpty()) {
        return new CellView(null, null, cell.nutrientLevel(), flags);
    }
    Entity occupant = cell.occupant();
    return switch (occupant) {
        case Particle p -> new CellView(p.type().name(), p.id(), cell.nutrientLevel(), flags);
        case Entity.Rock r -> new CellView("ROCK", r.id(), cell.nutrientLevel(), flags);
        case Entity.Nutrient n -> new CellView("NUTRIENT", n.id(), cell.nutrientLevel(), flags);
        case Entity.BondedPair bp -> new CellView(
                "BONDED_" + bp.primaryType() + "_" + bp.secondaryType(),
                bp.id(), cell.nutrientLevel(), flags);
        case Entity.CompositeMember cm -> new CellView(
                "COMPOSITE_" + cm.type() + "_" + cm.role(),
                cm.id(), cell.nutrientLevel(), flags);
    };
}
```

**Existing neighborhood build loop** (PerceptionBroadcaster.java:149-163):
```java
int diameter = PERCEPTION_RADIUS * 2 + 1;
List<List<CellView>> neighbourhood = new ArrayList<>(diameter);

for (int dy = -PERCEPTION_RADIUS; dy <= PERCEPTION_RADIUS; dy++) {
    List<CellView> row = new ArrayList<>(diameter);
    for (int dx = -PERCEPTION_RADIUS; dx <= PERCEPTION_RADIUS; dx++) {
        Cell cell = worldGrid.getCell(pos.x() + dx, pos.y() + dy);
        row.add(cellToView(cell));
    }
    neighbourhood.add(row);
}
```

**Required modifications:**
1. **Dynamic radius (D-15 `+1 sensor` buff):** Replace constant `PERCEPTION_RADIUS` with per-bot `int radius = buffRegistry.hasBuff(entityId, SENSOR_PLUS_1) ? 3 : 2;` — 5×5 → 7×7.
2. **Status encoding:** Modify `cellToView` to accept `byte cellStatus` and `byte entityStatus` parameters. Compute via the status caches passed from EnvironmentEngine. New signature:
   ```java
   static CellView cellToView(Cell cell, byte cellStatus, byte entityStatus) { ... }
   ```
3. **Vision-scoped overcrowding (D-40):** Compute per-bot by counting occupied neighbors *within the bot's visible neighborhood*. Since vision is 5×5 or 7×7 and center-of-cell overcrowding counts 8 Moore neighbors, cells at `radius` edge won't have full neighbor visibility — this is the locked incomplete-information property. Server-authoritative `Cell.FLAG_OVERCROWDED` remains as-is (SimulationEngine.processOvercrowding still runs globally). New status bit *overrides* the perception-encoded visibility of OVERCROWDED.
4. **Cache access:** Inject `EnvironmentEngine` (or a dedicated `StatusCacheProvider` interface to avoid tight coupling). Read `cellStatusCache.getOrDefault(pos, (byte)0)` and `entityStatusCache.getOrDefault(entityId, (byte)0)`.

**Key invariants:**
- Existing `flags` field on CellView is preserved for back-compat (Phase 13 tests consume it).
- New `cellStatus` and `entityStatus` fields default to 0 when no env effect present.
- `Cell.FLAG_OVERCROWDED` continues to populate `flags` (server-authoritative); `cellStatus` bit 0 is the *perception-visible* vision-scoped version (D-40 distinction).
- Composite stitched perception (buildStitchedPerception, line 169-196) must also encode status fields — same `cellToView` call site.

---

### `Messages.java` — `CellView` extension (MODIFY)

**Self-analog**, existing back-compat constructor chain (Messages.java:193-203):
```java
record CellView(
        String occupantType,
        String occupantId,
        int nutrientLevel,
        int flags
) {
    /** Back-compat 3-arg constructor — defaults {@code flags} to 0. */
    public CellView(String occupantType, String occupantId, int nutrientLevel) {
        this(occupantType, occupantId, nutrientLevel, 0);
    }
}
```

**Required shape** (RESEARCH.md Pattern 6):
```java
record CellView(
        String occupantType,
        String occupantId,
        int nutrientLevel,
        int flags,
        byte cellStatus,     // NEW — 6-bit bitfield: OVERCROWDED|TOXIN_PRESENT|MUTAGEN_ZONE
        byte entityStatus    // NEW — 6-bit bitfield: STARVING|TOXIC|MUTATING|BUFFED
) {
    /** Back-compat 3-arg — flags=0, statuses=0. Pre-Phase-13 tests. */
    public CellView(String occupantType, String occupantId, int nutrientLevel) {
        this(occupantType, occupantId, nutrientLevel, 0, (byte) 0, (byte) 0);
    }
    /** Back-compat 4-arg — statuses=0. Phase 13 tests. */
    public CellView(String occupantType, String occupantId, int nutrientLevel, int flags) {
        this(occupantType, occupantId, nutrientLevel, flags, (byte) 0, (byte) 0);
    }
}
```

**Key invariants:**
- Jackson serializes records via canonical accessors — no `@JsonCreator` needed for writes.
- `@JsonInclude(NON_NULL)` at Messages interface level (Messages.java:31) does NOT omit zero-value primitives — `cellStatus == 0` will be serialized (fine per D-37 "fixed-width, not omit-if-zero").
- All existing callers of `cellToView` continue to compile against the 4-arg constructor until the PerceptionBroadcaster change lands.
- **Do NOT add new `Cell.flags` constants** for TOXIC / MUTAGEN_ZONE (RESEARCH.md Anti-Pattern). Shadow-grid origin; CellView status bit destination.

---

### `application.yml` (MODIFY — add `paralife.simulation.events.*`)

**Self-analog**, existing structure (application.yml:27-87):
```yaml
paralife:
  simulation:
    # flat fields bound to SimulationConfig (prefix paralife.simulation)
    energy-decay-per-tick: 1
    # ...
    # nested sub-sections bound to own @ConfigurationProperties records
    types:           # paralife.simulation.types → MetabolicProfile
      catalyst: { ... }
    starvation:      # paralife.simulation.starvation → StarvationConfig
      max-attack-boost: 0.5
    fertility:       # paralife.simulation.fertility → FertilityConfig
      patch-count: 20
    seasons:         # paralife.simulation.seasons → SeasonsConfig
      year-length-ticks: 200
```

**Required addition** (matching CONTEXT.md D-48 scaffold):
```yaml
paralife:
  simulation:
    events:          # paralife.simulation.events → EnvironmentConfig
      lightning:
        peak-season: SUMMER
        peak-lambda: 0.04
        off-season-lambda: 0.005
        inner-radius: 2
        outer-radius: 4
        damage: 40
        fertility-boost: 25
      toxin:
        peak-season: AUTUMN
        # ... full scaffold per CONTEXT.md D-48
      mutagen: { ... }
      compost:
        full-strength: 30
        half-strength: 15
```

**Key invariants:**
- Kebab-case YAML keys bind to camelCase record fields via Spring relaxed binding.
- `@ConfigurationPropertiesScan` in `ParalifeApplication.java` auto-registers new `EnvironmentConfig` — no changes there.
- Validate all defaults match the numeric values promised by CONTEXT.md D-48 (planner should copy verbatim).

---

## Shared Patterns

### Pattern S1: Spring `@Component` + Constructor Injection

**Source:** Every engine component (SimulationEngine, ActionResolver, PerceptionBroadcaster, CompositeEnergyDistributor, BotRegistry, CompositeRegistry, FertilityInitializer, SeasonTracker)

**Apply to:** All new `@Component` beans (`EnvironmentEngine`, `BuffRegistry`, optionally `ToxinPathGenerator`)

```java
@Component
public class EnvironmentEngine {
    private final WorldGrid worldGrid;
    private final SeasonTracker seasonTracker;
    private final EnvironmentConfig config;
    private final BuffRegistry buffRegistry;
    private final MetabolicProfile metabolicProfile;
    private final Random rng;  // seedable for tests

    public EnvironmentEngine(WorldGrid worldGrid, SeasonTracker seasonTracker,
                             EnvironmentConfig config, BuffRegistry buffRegistry,
                             MetabolicProfile metabolicProfile) {
        this.worldGrid = worldGrid;
        this.seasonTracker = seasonTracker;
        this.config = config;
        this.buffRegistry = buffRegistry;
        this.metabolicProfile = metabolicProfile;
        this.rng = new Random();  // test constructor overload can accept seed
    }

    // Package-private test-only constructor for seeding
    EnvironmentEngine(..., long seed) { ...; this.rng = new Random(seed); }
}
```

### Pattern S2: Immutable Records + `withX` Helpers

**Source:** `world/Entity.java` (Particle.withEnergy, BondedPair.withEnergy, CompositeMember.withEnergy), `world/Cell.java` (withOccupant, withNutrientLevel, withAddedFlag, withRemovedFlag)

**Apply to:** `ToxinEvent.withHeadIdx`, `Infection.decrement` — new records use same naming convention

### Pattern S3: `@ConfigurationProperties` Validation in Compact Constructor

**Source:** `MetabolicProfile.TypeProfile` (metabolic.java:60-87), `SeasonsConfig` (lines 30-36), `FertilityConfig` (lines 24-35), `StarvationConfig` (lines 22-30), `CompositeConfig` (lines 44-71)

**Apply to:** All new config records in `EnvironmentConfig`. Throw `IllegalArgumentException` with descriptive message including the bad value. Validate numeric bounds, cross-field invariants (min ≤ max), and ranges.

### Pattern S4: Grid Mutation Idioms

**Source:** WorldGrid.java methods + call sites in SimulationEngine

**Apply to:** All EnvironmentEngine grid writes:
- `worldGrid.setEntity(x, y, entity)` — replace occupant, preserve cell environment
- `worldGrid.setCell(x, y, cell)` — full cell replace (e.g. `cell.withNutrientLevel(...)` for compost / lightning outer ring)
- `worldGrid.clearEntity(x, y)` — remove occupant, preserve environment
- `worldGrid.getNeighbors(x, y)` — 8 toroidal Moore neighbors, returns `List<Position>`
- **Never** mutate returned `Cell` record directly — always `setCell` a new one via `withX`

### Pattern S5: Death Cleanup Symmetry

**Source:** `SimulationEngine.processDeaths` (lines 603-659) — every `clearEntity` pairs with `botRegistry.unregisterByEntity`

**Apply to:** Every death site must also call `buffRegistry.unregisterEntity(entityId)` AND `environmentEngine.applyCompost(pos)`. Centralize via a `clearEntityAndCompost` helper in SimulationEngine (Pitfall 4 + Pitfall 5).

### Pattern S6: Seedable Random for Test Determinism

**Source:** `ThreadLocalRandom.current()` is used throughout production code (SimulationEngine, ActionResolver, FertilityInitializer, CompositeEnergyDistributor) but is not seedable.

**Apply to:** `EnvironmentEngine` — introduce a `Random rng` field initialized to `new Random()` in production constructor, with a package-private test constructor that accepts `long seed`. Pitfall 6 + CONTEXT.md Claude's Discretion item.

### Pattern S7: Test Structure

**Source:** `FertilityInitializerTest` (clean unit test), `SeasonTrackerTest`, `SimulationEngineTest`, `CompositeIntegrationTest` (`@SpringBootTest`)

**Apply to:** 6 new test files per RESEARCH.md Project Structure:
- `EnvironmentEngineTest` — unit tests, per-effect sections
- `ToxinPathGeneratorTest` — deterministic with seeded Random
- `CellularAutomatonTest` — double-buffer correctness, mass conservation
- `BuffRegistryTest` — register/grant/expire/unregister, same shape as `BotRegistryTest`
- `PoissonTriggerTest` — sine-scaled lambda edge cases (boundaries, winter=0, peak at mid-season)
- `EnvironmentIntegrationTest` — `@SpringBootTest` full pipeline

Package-private methods for direct math testing (mirror `FertilityInitializer.generatePatch`).

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `CellularAutomaton.java` (diffusion helper) | math util | CA (double-buffered byte grid) | No existing code does CA — FertilityInitializer iterates grid once at init; SimulationEngine's overcrowding pass reads adjacent `Cell` records, not a parallel shadow grid. Follow RESEARCH.md Pattern 3 verbatim. Use `byte[][]` primitives (not `Cell[]`) for performance — mask every read with `& 0xFF`. |

**Closest-but-partial analogs** (included above for completeness, flagged for planner awareness):
- `ToxinEvent` / `MutagenEvent` — no domain analog for "time-evolving event record"; closest is `Entity.BondedPair` (stateful record with immutable mutation). Plan's main risk: deciding between record-with-withX vs. mutable state-holder (CompositeRegistry.CompositeState shows the mutable variant; record-with-withX is cleaner for a single value like `headIdx`).

---

## Planner Reminders

1. **@Order slot (14 vs 16):** RESEARCH.md Architecture Diagram says `@Order(15)` but `CompositeEnergyDistributor` already occupies that slot. Planner decides `@Order(14)` (recommended — damage resolves before composite pool drains) or `@Order(16)`.

2. **Circular dependency** (SimulationEngine needs EnvironmentEngine.applyCompost; EnvironmentEngine reads BuffRegistry; planner should consider using `ApplicationEventPublisher` to decouple — publish a `DeathEvent` in SimulationEngine, subscribe in EnvironmentEngine). If using direct injection, annotate one side `@Lazy`.

3. **Season enum binding** (EnvironmentConfig): prefer `SeasonTracker.Season peakSeason` over `String peakSeason` — verify Spring binds enum from YAML string in integration test.

4. **Status cache lifecycle** (Pitfall 7): initialize empty maps in EnvironmentEngine constructor; rebuild at top of `onTick`; always leave valid (empty == "no env effects") for PerceptionBroadcaster.

5. **Back-compat for CellView**: adding 2 bytes to the record requires 2 back-compat constructors (3-arg + 4-arg). Both already exist and both must continue working. Run the full test suite after the Messages.java change before changing anything else.

6. **Buff application distribution** (RESEARCH.md Architecture Map): buffs affect behavior in 3 different components — document this explicitly in the plan so reviewers find all call sites:
   - `ATTACK_PLUS_1` → SimulationEngine.applyAttackBoost
   - `MOVEMENT_PLUS_1` → ActionResolver.resolveMove (shared helper with resolveReproduce)
   - `SENSOR_PLUS_1` → PerceptionBroadcaster.onTick radius calc
   - `UPKEEP_MINUS_1` → SimulationEngine.processEnergyDecay

## Metadata

**Analog search scope:**
- `src/main/java/com/paralife/engine/**` (all 19 files)
- `src/main/java/com/paralife/world/**` (all 5 files)
- `src/main/java/com/paralife/websocket/**` (all 5 files)
- `src/main/resources/application.yml`
- `src/test/java/com/paralife/engine/**` (26 files — structural reference only)
- `src/main/java/com/paralife/ParalifeApplication.java`

**Files read:** 18
**Pattern extraction date:** 2026-04-17
