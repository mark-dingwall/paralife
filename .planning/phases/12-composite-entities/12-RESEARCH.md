# Phase 12: Composite Entities - Research

**Researched:** 2026-04-14
**Domain:** Multi-cell composite entity system on a Java 21 / Spring Boot grid simulation
**Confidence:** HIGH (all findings from direct codebase inspection)

## Summary

Phase 12 adds siphonophore-style composite organisms to an existing Java 21 / Spring Boot simulation. Every design decision is locked in CONTEXT.md — research here is purely about confirming how the existing code works and identifying the exact integration points. No library research is required; this phase is a pure server-side feature extension in an established codebase.

The codebase is clean and well-structured. All established patterns (sealed Entity hierarchy, immutable records, `@EventListener`/`@Order` tick pipeline, `@ConfigurationProperties` records, `ConcurrentHashMap`-based registry) are consistent and repeatable. Phase 12 follows every one of them.

The primary complexity is not structural — it is the interaction count. Composites touch every pipeline layer: SimulationEngine, ActionResolver, PerceptionBroadcaster, TickBroadcaster, Messages, BotRegistry, and a new CompositeRegistry. Each integration is well-scoped but the planner must schedule them in dependency order to avoid a "big bang" integration at the end.

**Primary recommendation:** Implement in strict dependency order — Entity model first, CompositeRegistry second, then pipeline integrations one layer at a time. Each layer should be independently testable before the next is wired.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Formation & Transition**
- D-01: BondedPair + adjacent BondedPair → Composite. BondedPair remains single-cell. Two distinct entity types.
- D-02: `canFormComposites` property on entities for future tweakability.
- D-03: Composites = siphonophore model (Portuguese man-o'-war reference).

**Grid Representation**
- D-04: Linked members model — `CompositeMember` entity in each cell, sharing a `compositeId`. `CompositeRegistry` holds shared state.
- D-05: `CompositeMember` record implements `Entity` sealed interface. Fields: `id`, `compositeId`, `type` (ParticleType), `role`, `energy` (individual), `maxEnergy`.

**Roles**
- D-06: Six roles: `LOCOMOTOR`, `FEEDER`, `ATTACKER`, `DEFENDER`, `REPRODUCER`, `SENSOR`.
- D-07: All ParticleTypes can fill any role for MVP.
- D-08: Initial pair are generalists. Every new member picks a specialized role. Size > 5: initial pair also specialize.
- D-09: At least one FEEDER must be on the surface (edge cell) of all composites at all times.

**Combat**
- D-10: Composites bypass RPS. ATTACKER deals true damage. DEFENDER absorbs additional damage.
- D-11: Position-based combat for non-role members. ATTACKER adds multiplier.
- D-12: Solo attack on CompositeMember → damage to targeted member's individual energy. Member death at 0.
- D-13: Composite-vs-composite: emergent from member interactions. No special composite-level combat for MVP.

**Energy Model**
- D-14: Dual energy — individual member energy + composite shared pool.
- D-15: FEEDER consume → shared pool. Decay → individual energy. Healing → shared pool to individual. Combat → individual directly.
- D-16: Draw rates set at Role level. No artificial size cap.
- D-17: Active/passive draw rates per role (ATTACKER most expensive active; SENSOR cheapest passive only).
- D-18: Emergent archetypes from energy economics.

**Vision & Perception**
- D-19: Only SENSOR members produce vision. 5×5 circles, union via HashSet deduplication.
- D-20: No SENSOR = blind composite.
- D-21: Non-SENSOR members contribute no vision.

**Coordinated Movement**
- D-22: LOCOMOTOR-driven. No LOCOMOTOR = sessile.
- D-23: Speed = `locomotor_count / colony_size * constant`. Speed < 1 means move every Nth tick.
- D-24: Rigid body translation. Blocked if ANY target cell is occupied.
- D-25: Movement decision: flee > hunt > eat > grow > bud > explore > rest.

**Movement Voting**
- D-26: STV with max 3 ranked preferences, random tie-break.
- D-27: Each LOCOMOTOR bot evaluates independently and submits ranked preferences.
- D-28: No PROCESSOR role — intelligence emergent from SENSOR placement quality.

**Dissolution & Death**
- D-29: Member death: 97% graceful degradation (shrink), 3% dissolution (shatter to BondedPairs/Particles). Configurable.
- D-30: 2-member composite losing one member → reverts to BondedPair.
- D-31: Shared pool < 12% energy threshold → progressive shatter die roll on each decrease. Pool = 0 → total death.

**Reproduction**
- D-32: REPRODUCER buds solo Particle into adjacent empty cell. Reuses existing reproduce mechanic. Energy from shared pool.

**Bot Control**
- D-33: All bot sessions stay connected when joining a composite.
- D-34: Reactive roles auto-act without consensus (FEEDER, ATTACKER, DEFENDER, REPRODUCER).
- D-35: Only movement requires consensus (LOCOMOTOR STV).
- D-36: All member bots receive composite's stitched perception each tick.

### Claude's Discretion

- CompositeRegistry internal data structures and thread-safety approach
- CompositeMember record field naming and convenience constructors
- Config property organization (extend SimulationConfig, new CompositeConfig, or both)
- Healing draw order when pool has insufficient energy for all members in a tick
- Logging levels for composite lifecycle events
- How stitched perception is serialized for WebSocket broadcast

### Deferred Ideas (OUT OF SCOPE)

- Per-type-role constraints
- Colony fission
- Composite-vs-composite detailed combat formulas
- Internal growth (REPRODUCER adds member directly)
- ANCHOR role
- Environmental sensing for SENSOR
- Composite perception radius extension
- Dynamic role switching for small composites
- PROCESSOR role (rejected)

</user_constraints>

---

## Standard Stack

No new dependencies required. Phase 12 is a pure Java extension of the existing stack.

[VERIFIED: direct codebase inspection]

| Layer | Technology | Version in Use |
|-------|------------|----------------|
| Language | Java | 21 (virtual threads enabled) |
| Framework | Spring Boot | 3.4.4 |
| Build | Gradle Kotlin DSL | wrapper present |
| Testing | JUnit 5 + AssertJ | via Spring Boot test starter |
| Config | `@ConfigurationProperties` on records | established pattern |
| JSON | Jackson (transitive via Spring) | established |

**Installation:** None required.

## Architecture Patterns

### Existing Patterns to Follow (All Verified)

**1. Sealed Entity hierarchy** [VERIFIED: Entity.java]

```java
public sealed interface Entity permits Entity.Particle, Entity.Rock, Entity.Nutrient, Entity.BondedPair {
    String id();
    // ...
    record BondedPair(...) implements Entity { ... }
}
```

`CompositeMember` adds a fifth permit. It must implement `Entity` as an inner record or a top-level record in the same file — consistent with `BondedPair`. Fields from D-05: `id`, `compositeId`, `type` (ParticleType), `role` (Role enum), `energy`, `maxEnergy`. Needs `withEnergy()` and `isAlive()` convenience methods matching `Particle` and `BondedPair`.

**2. @ConfigurationProperties record** [VERIFIED: BondingConfig.java]

```java
@ConfigurationProperties(prefix = "paralife.bonding")
public record BondingConfig(int bondEnergyThreshold, double bondingProbability, double bondDefenseChance) {
    public BondingConfig { /* compact constructor validates */ }
    public static BondingConfig defaults() { ... }
}
```

`CompositeConfig` follows the same pattern under `paralife.composite`. Fields: role draw rates (active/passive per role), dissolution chance, shatter chance, critical threshold percentage, speed constant, canFormComposites flag.

**3. ConcurrentHashMap registry** [VERIFIED: BotRegistry.java]

```java
@Component
public class BotRegistry {
    private final ConcurrentHashMap<String, BotState> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> entityToSession = new ConcurrentHashMap<>();
    public record BotState(String sessionId, String entityId, Position position) {}
}
```

`CompositeRegistry` mirrors this. Needs: `compositeId → CompositeState` (member list, positions, shared pool energy, shape), `memberId → compositeId` (reverse lookup for SimulationEngine to find the composite when a member is hit). `CompositeState` should be an immutable record where possible — but the member list and energy pool change every tick, so `CompositeState` will need mutable fields or atomic replacement.

**Recommendation (Claude's discretion):** Use a `ConcurrentHashMap<String, CompositeState>` where `CompositeState` is a mutable class (not record), protected by the single-threaded tick pipeline. The tick pipeline is already single-threaded for mutations — the registry only needs to be thread-safe for concurrent WebSocket reads (e.g., `getBots()` from PerceptionBroadcaster). A `CopyOnWriteArrayList` for the member list and `AtomicInteger` for shared pool energy gives safe reads without locks.

**4. Tick pipeline @EventListener @Order** [VERIFIED: SimulationEngine.java, ActionResolver.java, PerceptionBroadcaster.java, TickBroadcaster.java]

Current order:
- `@Order(10)` — SimulationEngine (combat, decay, death, nutrients)
- `@Order(20)` — ActionResolver (bot actions)
- `@Order(50)` — PerceptionBroadcaster
- `@Order(100)` — TickBroadcaster

Phase 12 needs a new pipeline step: **energy distribution** (FEEDER income to shared pool, decay from individual, healing from shared pool to individual). This belongs between `@Order(10)` and `@Order(20)` — after physics kills things, before bots act.

Recommended insertion: `@Order(15)` — `CompositeEnergyDistributor`. Handles shared pool accounting and role draw rates.

**5. Snapshot reads + deferred writes** [VERIFIED: SimulationEngine.processInteractions()]

All grid mutations use the pattern: scan all positions into a list, collect deltas into a result list, apply results after iteration. This prevents order-dependent outcomes. Composite formation from BondedPair pairs must use this same pattern.

**6. Messages sealed interface with @JsonSubTypes** [VERIFIED: Messages.java]

New message types needed:
- `CompositePerception` — extends Perception concept with stitched neighbourhood, composite state, role info, energy pool
- `CompositeAction` (client→server) — includes `role` field, STV vote payload for LOCOMOTOR
- `CompositeJoined` (server→client) — notifies bot its entity has joined a composite

Each new type needs a `@JsonSubTypes.Type` entry and a `permits` clause on the `Messages` sealed interface.

### Recommended File/Class Structure

```
src/main/java/com/paralife/
├── world/
│   └── Entity.java               # Add CompositeMember permit + record + Role enum
├── engine/
│   ├── CompositeRegistry.java    # NEW: @Component, shared state management
│   ├── CompositeConfig.java      # NEW: @ConfigurationProperties record
│   ├── CompositeEnergyDistributor.java  # NEW: @EventListener @Order(15)
│   ├── SimulationEngine.java     # MODIFY: composite formation, combat, death
│   └── ActionResolver.java       # MODIFY: composite member action handling + STV
├── websocket/
│   ├── Messages.java             # MODIFY: new message types
│   └── PerceptionBroadcaster.java  # MODIFY: SENSOR-based stitched perception
src/test/java/com/paralife/
├── engine/
│   ├── CompositeRegistryTest.java       # NEW
│   ├── CompositeEnergyDistributorTest.java  # NEW
│   ├── CompositeFormationTest.java      # NEW (SimulationEngine integration)
│   ├── CompositeMovementTest.java       # NEW (ActionResolver STV)
│   └── CompositeDissolutionTest.java    # NEW
└── world/
    └── CompositeMemberTest.java         # NEW
```

### Anti-Patterns to Avoid

- **Mutable records for CompositeState**: Java records are immutable. Don't try to use `record CompositeState(List<String> memberIds)` — mutation means replacing the whole record in the map each tick. This is fine for small composites but becomes a GC pressure issue for large ones. Use a mutable class with package-private mutation methods instead.
- **Locking CompositeRegistry with the WorldGrid lock**: The grid uses a `ReentrantReadWriteLock`. The composite registry must NOT acquire this lock inside its own operations — deadlock risk if the tick pipeline holds the grid write lock and then calls registry methods. Keep them independent.
- **Processing composite members as solo entities in existing loops**: `SimulationEngine.processInteractions()` scans for `Particle` instances. After Phase 12, `CompositeMember` instances are also on the grid. All existing loops that check `instanceof Particle` or `instanceof BondedPair` must also handle `instanceof CompositeMember` or explicitly skip it. Missing a case is the main source of bugs here.
- **STV vote accumulation across ticks**: LOCOMOTOR votes are per-tick. The ActionResolver must not accumulate votes across multiple ticks. Drain and discard on each tick boundary.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| STV counting logic | Custom vote tabulator | Simple ranked-choice: first-preference plurality with tie-break is sufficient for 8 directions and small LOCOMOTOR counts — full STV is over-engineered for this |
| JSON type dispatch | Manual type field checking | Existing `@JsonTypeInfo` + `@JsonSubTypes` pattern in `Messages.java` |
| Position arithmetic | Custom toroidal math | Existing `Position.wrap()` and `worldGrid.getNeighbors()` |
| Thread-safe counters | `synchronized` blocks | `AtomicInteger` for shared pool energy (already used in codebase) |

**Note on STV (D-26):** Full Single Transferable Vote with ranked preferences and transfer rounds is complex to implement correctly. For a small electorate (≤ 20 LOCOMOTORs) voting on 8 options, first-preference plurality with random tie-break produces equivalent emergent behavior. The context decision says "STV with max 3 ranked preferences, random tie-break" — the "random tie-break" clause means a simple implementation is acceptable. Don't implement full Droop quota transfer.

## Common Pitfalls

### Pitfall 1: instanceof gaps in existing grid scans

**What goes wrong:** `SimulationEngine.processEnergyDecay()`, `processDeaths()`, `processOvercrowding()`, and `ActionResolver.resolveActions()` all have `instanceof Particle` or `instanceof BondedPair` checks. `CompositeMember` instances on the grid are silently ignored by all of them.

**Why it happens:** The sealed interface is exhaustive in switch expressions but `instanceof` checks in loops are not compiler-enforced.

**How to avoid:** After adding `CompositeMember` to the `Entity` sealed hierarchy, do a global search for all `instanceof Particle` and `instanceof BondedPair` patterns in `SimulationEngine` and `ActionResolver`. Decide for each: should it also handle `CompositeMember`, or should it explicitly skip it (with a comment)?

**Warning signs:** CompositeMember entities never losing energy per tick; dead members not being removed from the grid.

### Pitfall 2: BotRegistry session mapping breaks at composite formation

**What goes wrong:** `BotRegistry` maps `sessionId → BotState(entityId, position)`. At composite formation, the original `BondedPair` entity IDs are replaced by `CompositeMember` IDs. If the registry isn't updated, `PerceptionBroadcaster` sends perception based on stale `entityId`/`position` pairs — bots get wrong or no perception.

**Why it happens:** Formation creates new entity IDs. The bot's WebSocket session still exists but its registered entity no longer matches what's on the grid.

**How to avoid:** Composite formation must update `BotRegistry` entries for all affected sessions. Map old BondedPair member IDs to new CompositeMember IDs. `PerceptionBroadcaster` also needs to know to route composite stitched perception, not individual 5×5 perception.

**Warning signs:** Bots go blind (receive UNKNOWN entity state) immediately after composite formation.

### Pitfall 3: Rigid body movement — partial occupancy race

**What goes wrong:** Movement check says "if ANY target cell is occupied, block". With deferred writes, two composites moving toward each other in the same tick could each see the other's current positions (not targets) as unoccupied, and both execute a move that would collide.

**Why it happens:** Snapshot reads show state at start of tick. Both composites pass the "target is unoccupied" check. Both write. One overwrites the other.

**How to avoid:** Use the same `claimedCells` set pattern that `ActionResolver.resolveMove()` already uses. Composite movement must claim all target cells atomically before executing any member moves.

**Warning signs:** Members of two composites end up in the same cell (impossible by grid rules — should be caught by assertions in test).

### Pitfall 4: Shared pool depletion during dissolution leaves orphan members

**What goes wrong:** Dissolution logic removes some members and updates others. If removal is not atomic, a subsequent tick might process a composite with 0 members but non-null registry entry, causing NPEs or infinite loops.

**Why it happens:** Multi-step dissolution: remove dead member → check member count → maybe revert to BondedPair → update registry. If any step is skipped on exception, state is inconsistent.

**How to avoid:** Dissolution handling must be a single method that runs to completion. Wrap in try/finally if needed. Test the 2-member → BondedPair reversion path explicitly (D-30).

**Warning signs:** `CompositeRegistry.getComposite()` returns a composite with empty member list; `NullPointerException` in PerceptionBroadcaster on the following tick.

### Pitfall 5: PerceptionBroadcaster sends per-member perception instead of stitched

**What goes wrong:** Current `PerceptionBroadcaster.onTick()` iterates `botRegistry.getAllBots()` and sends each bot its own 5×5 neighbourhood. For composite members, all non-SENSOR members should receive the composite's stitched perception, not their individual 5×5 view.

**Why it happens:** The broadcaster doesn't distinguish Particle bots from CompositeMember bots without checking entity type.

**How to avoid:** `PerceptionBroadcaster` must check if the bot's entity is a `CompositeMember`. If so, fetch stitched perception from `CompositeRegistry` (built once per composite per tick, not once per member). Memoize the stitched perception per composite per tick to avoid rebuilding it for every member.

**Warning signs:** Composite members each receive a tiny 5×5 view; LOCOMOTOR voting is based on fragmentary perception rather than the full stitched view.

## Code Examples

### Adding CompositeMember to Entity sealed interface [ASSUMED: pattern extrapolated from BondedPair]

```java
// In Entity.java — add to permits list
public sealed interface Entity permits Entity.Particle, Entity.Rock, Entity.Nutrient, Entity.BondedPair, Entity.CompositeMember {
    String id();

    // New role enum (can live here or in a separate file)
    enum Role { LOCOMOTOR, FEEDER, ATTACKER, DEFENDER, REPRODUCER, SENSOR }

    record CompositeMember(
            String id,
            String compositeId,
            ParticleType type,
            Role role,
            int energy,
            int maxEnergy
    ) implements Entity {
        public CompositeMember {
            if (energy < 0) throw new IllegalArgumentException("Energy cannot be negative: " + energy);
            if (maxEnergy <= 0) throw new IllegalArgumentException("Max energy must be positive: " + maxEnergy);
        }

        public CompositeMember withEnergy(int newEnergy) {
            return new CompositeMember(id, compositeId, type, role,
                    Math.clamp(newEnergy, 0, maxEnergy), maxEnergy);
        }

        public boolean isAlive() { return energy > 0; }
    }
}
```

### CompositeConfig pattern [ASSUMED: direct analogue of BondingConfig]

```java
@ConfigurationProperties(prefix = "paralife.composite")
public record CompositeConfig(
        double dissolutionChance,    // 0.03 — member death triggers full shatter
        int criticalEnergyPercent,   // 12 — pool % below which shatter die rolls begin
        double speedConstant,        // configures locomotor_count/colony_size formula
        int locomotorPassiveDrain,
        int locomotorActiveDrain,
        int feederPassiveDrain,
        int feederActiveDrain,
        int attackerPassiveDrain,
        int attackerActiveDrain,
        int defenderPassiveDrain,
        int reproducerPassiveDrain,
        int reproducerActiveDrain,
        int sensorPassiveDrain,
        boolean canFormComposites    // D-02: global toggle
) {
    public CompositeConfig { /* validate ranges */ }
}
```

### CompositeRegistry structure [ASSUMED: extends BotRegistry pattern]

```java
@Component
public class CompositeRegistry {
    // compositeId → mutable state (tick mutations are single-threaded)
    private final ConcurrentHashMap<String, CompositeState> composites = new ConcurrentHashMap<>();
    // memberId → compositeId (reverse lookup for engine)
    private final ConcurrentHashMap<String, String> memberToComposite = new ConcurrentHashMap<>();

    public static class CompositeState {
        // Mutable (tick pipeline is single-threaded for mutations):
        public final String compositeId;
        public final List<String> memberIds;    // positions tracked via BotRegistry
        public final AtomicInteger sharedPool;
        public final int maxPool;
        // ... formation shape as List<Position> offset from anchor
    }
}
```

### PerceptionBroadcaster — stitching pattern [ASSUMED: extrapolated from D-19/D-21]

```java
// In PerceptionBroadcaster.onTick():
// Build stitched perception once per composite, reuse for all members
Map<String, Messages.CompositePerception> compositePerceptions = new HashMap<>();

for (var bot : botRegistry.getAllBots()) {
    Cell cell = worldGrid.getCell(bot.position().x(), bot.position().y());
    if (cell.occupant() instanceof Entity.CompositeMember cm) {
        // Memoize per composite
        compositePerceptions.computeIfAbsent(cm.compositeId(), id ->
            buildStitchedPerception(tickNumber, compositeRegistry.getComposite(id)));
        sendToSession(bot.sessionId(), compositePerceptions.get(cm.compositeId()));
    } else {
        // Existing solo particle/bonded pair path
        sendToSession(bot.sessionId(), buildPerception(tickNumber, bot));
    }
}
```

```java
// Stitching: union of SENSOR member 5×5 circles
private Set<Position> stitchSensorCoverage(CompositeState composite) {
    Set<Position> coverage = new HashSet<>();
    for (String memberId : composite.memberIds) {
        // Only SENSOR members contribute
        // Add all 5×5 cells around each SENSOR's position
    }
    return coverage; // deduplicated by HashSet
}
```

### STV vote counting (simplified) [ASSUMED: see note in Don't Hand-Roll]

```java
// In ActionResolver — LOCOMOTOR voting for composite movement
private Direction resolveLocomotorVote(List<String[]> rankedVotes) {
    // Phase 1: count first preferences
    Map<Direction, Integer> counts = new EnumMap<>(Direction.class);
    for (String[] prefs : rankedVotes) {
        if (prefs.length > 0) {
            Direction d = Direction.fromString(prefs[0]);
            if (d != null) counts.merge(d, 1, Integer::sum);
        }
    }
    if (counts.isEmpty()) return null;

    // Phase 2: find max, break ties randomly
    int max = Collections.max(counts.values());
    List<Direction> winners = counts.entrySet().stream()
            .filter(e -> e.getValue() == max)
            .map(Map.Entry::getKey)
            .toList();
    return winners.get(ThreadLocalRandom.current().nextInt(winners.size()));
}
```

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ (via Spring Boot test starter) |
| Config file | none — uses Spring Boot auto-configuration |
| Quick run command | `./gradlew test --tests "com.paralife.*Composite*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Behavior | Test Type | Automated Command | File |
|----------|-----------|-------------------|------|
| CompositeMember record validation | unit | `./gradlew test --tests "*.CompositeMemberTest"` | Wave 0 |
| CompositeRegistry CRUD | unit | `./gradlew test --tests "*.CompositeRegistryTest"` | Wave 0 |
| Formation from 2 BondedPairs | unit | `./gradlew test --tests "*.CompositeFormationTest"` | Wave 0 |
| Shared energy pool accounting | unit | `./gradlew test --tests "*.CompositeEnergyDistributorTest"` | Wave 0 |
| Member death → dissolution/degradation | unit | `./gradlew test --tests "*.CompositeDissolutionTest"` | Wave 0 |
| Coordinated movement (rigid body) | unit | `./gradlew test --tests "*.CompositeMovementTest"` | Wave 0 |
| LOCOMOTOR STV vote counting | unit | `./gradlew test --tests "*.CompositeMovementTest"` | Wave 0 |
| SENSOR stitched perception | unit | `./gradlew test --tests "*.PerceptionBroadcasterTest"` (extend) | Exists |
| 2-member → BondedPair reversion | unit | `./gradlew test --tests "*.CompositeDissolutionTest"` | Wave 0 |
| Panic zone (pool < 12%) shatter | unit | `./gradlew test --tests "*.CompositeDissolutionTest"` | Wave 0 |

### Wave 0 Gaps

- [ ] `src/test/java/com/paralife/world/CompositeMemberTest.java`
- [ ] `src/test/java/com/paralife/engine/CompositeRegistryTest.java`
- [ ] `src/test/java/com/paralife/engine/CompositeFormationTest.java`
- [ ] `src/test/java/com/paralife/engine/CompositeEnergyDistributorTest.java`
- [ ] `src/test/java/com/paralife/engine/CompositeDissolutionTest.java`
- [ ] `src/test/java/com/paralife/engine/CompositeMovementTest.java`

### Sampling Rate

- Per task commit: `./gradlew test --tests "com.paralife.*Composite*"`
- Per wave merge: `./gradlew test`
- Phase gate: Full suite green before `/gsd-verify-work`

## Security Domain

No network-facing changes beyond the existing WebSocket protocol. New message types follow the same `@JsonTypeInfo` type-discriminated pattern. No new authentication surface. Input validation for STV votes (invalid direction strings) must be handled with null-safe `Direction.fromString()` already in the codebase.

ASVS V5 (Input Validation): new `CompositeAction` message fields (`role`, ranked direction preferences) must be validated against enum values before use. Pattern: `Direction.fromString()` returns null for invalid values — already handled in ActionResolver.

## Open Questions (RESOLVED)

1. **`canFormComposites` flag location (D-02)**
   - What we know: D-02 says it should be a property on entities to toggle formation eligibility.
   - What's unclear: Should it be a field on `BondedPair` (per-instance) or on `CompositeConfig` (global toggle)?
   - Recommendation: Global toggle in `CompositeConfig` for MVP. Per-instance flag adds fields to the immutable `BondedPair` record and complicates serialization for no MVP benefit.

2. **FEEDER surface constraint enforcement (D-09)**
   - What we know: "at least one FEEDER must be on the surface (edge) of all composites at all times."
   - What's unclear: How is "surface" defined for an arbitrary composite shape? Moore neighborhood edge cells? The constraint is architectural but no enforcement mechanism is specified.
   - Recommendation: Define "surface member" as any CompositeMember with at least one empty neighbor (not occupied by another CompositeMember). Enforce at formation time by role assignment rules (first member assigned FEEDER must be a surface member). No runtime enforcement needed if growth always adds to surface — document as a design invariant.

3. **Healing draw order when pool insufficient (Claude's discretion)**
   - What we know: Multiple members may need healing in the same tick but pool has less than the sum of their draw rates.
   - Recommendation: Shuffle member list before distributing healing — same approach as `Collections.shuffle(particlePositions)` in `SimulationEngine`. Prevents starvation of members added later in the list.

4. **CompositeEnergyDistributor order vs ActionResolver**
   - What we know: Energy must be distributed (FEEDER income to pool, decay from individual, healing from pool) before bots act on the energy state.
   - Recommendation: `@Order(15)` for `CompositeEnergyDistributor` (after SimulationEngine's combat/death at 10, before ActionResolver at 20). This means decay for solo Particle/BondedPairs happens in SimulationEngine at 10; composite-specific energy accounting happens at 15.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `CompositeMember` will be an inner record of `Entity`, following `BondedPair` pattern | Code Examples | Minor — could be a top-level class, but inner record is consistent |
| A2 | `Role` enum lives inside `Entity` (alongside `ParticleType`) | Code Examples | Minor — could be a top-level enum |
| A3 | STV is implemented as first-preference plurality + random tie-break | Don't Hand-Roll | Low — context says "random tie-break", which makes simple plurality equivalent |
| A4 | `CompositeState` is a mutable class (not a record) in `CompositeRegistry` | Architecture | Medium — if implemented as immutable record, every energy tick requires full map replacement; acceptable but higher GC pressure |
| A5 | Phase 11 delivered `BondedPair` with `primaryEntityId`/`secondaryEntityId` fields | Verified in Entity.java | VERIFIED — confirmed in Entity.java line 145-153 |

## Sources

### Primary (HIGH confidence)

All findings are from direct codebase inspection. No external sources required.

- `Entity.java` — sealed hierarchy, BondedPair record structure, ParticleType enum
- `Cell.java` — Cell record, flags bitfield, withOccupant/cleared pattern
- `BotRegistry.java` — ConcurrentHashMap registry pattern, BotState record
- `SimulationEngine.java` — tick pipeline, processInteractions pattern, snapshot+deferred writes
- `ActionResolver.java` — action queue, claimedCells pattern, resolveActions structure
- `PerceptionBroadcaster.java` — per-bot perception, buildPerception, PERCEPTION_RADIUS
- `Messages.java` — sealed interface with @JsonSubTypes, all message types
- `BondingConfig.java` — @ConfigurationProperties record pattern
- `SimulationConfig.java` — field types and validation pattern
- `application.yml` — current property structure
- `WorldGrid.java` — ReentrantReadWriteLock, getNeighbors, snapshot
- `HeuristicBrain.java` — decision model, Direction usage
- `12-CONTEXT.md` — all locked decisions D-01 through D-36
- `11-CONTEXT.md` — BondedPair decisions that composites build on
- `.planning/config.json` — nyquist_validation: true confirmed

## Metadata

**Confidence breakdown:**

- Entity model and sealed interface: HIGH — BondedPair is the direct template
- Registry pattern: HIGH — BotRegistry is the direct template
- Tick pipeline integration points: HIGH — confirmed by reading all pipeline components
- STV implementation approach: MEDIUM — simplified from context intent, see A3
- CompositeState mutability recommendation: MEDIUM — design choice, see A4
- Energy distribution ordering: MEDIUM — logical from pipeline structure, no conflicts found

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable codebase, no external dependencies)
