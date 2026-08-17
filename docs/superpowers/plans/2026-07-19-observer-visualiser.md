# Observer Visualiser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a live, operator-deployable god's-eye web visualiser of the full Paralife world (species, environment fields, emergent structures, population time-series) over a new read-only `/ws/observer` WebSocket, without touching the bot `/ws/world` path or the sealed `Entity` model.

**Architecture:** A tick-`@Order` listener (`ObserverBroadcaster`, after `TickBroadcaster @Order(50)`) does **bounded work only on the tick thread** — capture one immutable `WorldGrid.snapshot()` + one `EnvironmentEngine.snapshot()` + owned-entity set + spawn counts, serialize **once** to JSON, then non-blocking-`offer` the shared payload to each observer's latest-wins mailbox. Per-observer delivery runs off-thread in `ObserverOutboundSender` drain virtual-threads (mirrors the real `OutboundSender` VT idiom). A static `observer.html` + `<canvas>` + vanilla JS renders each frame. Two additive counters (`SpeciesSpawnCounter`, per-tick lightning coords) and one new read API (`EnvironmentEngine.snapshot()`) supply frame data.

**Tech Stack:** Java 21 (virtual threads), Spring Boot 3.4.4, Jetty 12, Jackson (transitive, actuator), JUnit 5 + AssertJ + Mockito, Gradle Kotlin DSL, vanilla HTML/canvas/JS.

## Global Constraints

- **Java 21**; virtual threads created via `Thread.ofVirtual().name(...).start(runnable)` — the only production VT idiom in the codebase (`OutboundSender.attachSession`), no executor.
- **Spotless formatting gate** (`./gradlew spotlessCheck`, `ratchetFrom("origin/main")`) — run before every commit.
- **Assertion library:** AssertJ (`assertThat`) for all new tests under `com.paralife.observer`, `com.paralife.engine`, `com.paralife.websocket` (house style there). Mockito for spies/mocks.
- **Config binding:** `@ConfigurationProperties` records are auto-discovered by `@ConfigurationPropertiesScan` on `ParalifeApplication` — no manual `@EnableConfigurationProperties`. Use `@DefaultValue` + `@ConstructorBinding`, kebab-case yaml keys.
- **Frame JSON style (locked in spec):** full-word camelCase keys; `x`/`y` coordinates; enum values spelled out (`CATALYST`, not `C`); sparse layers as arrays of full-key objects. `schemaVersion` = **1**.
- **Enablement default:** `paralife.observer.enabled=false`. This slice ships session-cap only; auth/origin/rate-limit is a **named later BACKLOG hardening slice** — do not build it here.
- **FIREWALL (mechanism vs emergence) — `CLAUDE.md:27,73,77`:** NO default-suite `assertThat` on any per-population statistical aggregate or predicate derived from one (shares, counts-from-a-run, rates, densities, magnitudes, survival, non-degeneracy). The observer **displays** those (permitted); tests may pin only the **frame contract** — structure, subtype fields, brained classification, a **spawn before/after delta of exactly 1**, lightning transient-clear, and a **seeded engine-direct census with zero ticks advanced** (permitted mechanism per `:73`/`:77`). Never advance N ticks then assert on populations/survival/composition.
- **Every negative assertion needs a positive control; every gate is RED-tested** (break the guarded line, watch it fail for the spec reason, restore).
- **Spec source of truth:** `docs/superpowers/specs/2026-07-18-observer-visualiser-design.md`. Merge shipped changes back into `docs/SCHEMA.md` / `docs/ARCHITECTURE.md` / `BACKLOG.md` at the end.

---

## File Structure

**New package `com.paralife.observer`** (flat, one responsibility per file — matches the project's single-level-per-layer convention; keeps the bot path untouched):

| File | Responsibility |
|------|----------------|
| `ObserverConfig.java` | `@ConfigurationProperties(prefix="paralife.observer")` record — `enabled`, `maxSessions`. |
| `ObserverFrame.java` | Jackson DTOs: `BootstrapFrame`, `WorldFrame`, `EntityDto`, `EnvDto`, `RockDto`, `GridDims`, coordinate/cell records. Pure data. |
| `ObserverFrameBuilder.java` | `@Component`, stateless. Builds `BootstrapFrame`/`WorldFrame` from snapshots. The pure, unit-tested core (census, subtype fields, brained). |
| `ObserverOutboundSender.java` | `@Component`. Per-observer drain VT + capacity-1 latest-wins mailbox + close/interrupt. |
| `ObserverBroadcaster.java` | `@Component`, tick `@Order(60)`. Bounded on-thread capture → serialize once → offer. Owns the observer registry. |
| `ObserverSessionGate.java` | `@Component implements HandshakeInterceptor`. Enablement + `Semaphore` cap + release-once lease. |
| `ObserverWebSocketHandler.java` | `@Component extends AbstractWebSocketHandler`. `/ws/observer` lifecycle; bootstrap-barrier on open; idempotent close. Read-only. |

**New in `com.paralife.engine`:**

| File | Responsibility |
|------|----------------|
| `EnvironmentSnapshot.java` | Immutable record: sparse non-zero toxin intensity + mutagen strain + this-tick lightning coords. |
| `SpeciesSpawnCounter.java` | `@Component`. Atomic per-species committed-spawn counter. |

**Modified:**

| File | Change |
|------|--------|
| `engine/EnvironmentEngine.java` | Add `snapshot()`; add `lightningStrikesThisTick` capture (append at apply site, clear at `onTick` start). |
| `engine/ActionResolver.java` | Setter-inject `SpeciesSpawnCounter`; increment at 3 offspring sites. |
| `engine/BotRegistry.java` | Add `ownedEntityIds()` keyset copy accessor. |
| `websocket/WorldWebSocketHandler.java` | Setter-inject `SpeciesSpawnCounter`; increment after committed fresh/respawn placement. |
| `websocket/WebSocketConfig.java` | Register `/ws/observer` with `ObserverSessionGate` interceptor. |
| `websocket/JettyDeflateCustomizer.java` | Exempt `/ws/observer` from the deflate-enforcement filter (C1). |
| `src/main/resources/application.yml` | Add `paralife.observer` block. |
| `src/main/resources/static/observer.html` | New static render page (UI slice). |
| `docs/SCHEMA.md`, `docs/ARCHITECTURE.md`, `BACKLOG.md` | Merge-back. |

**Note (verified):** `WebSocketRouteAssertion` guards only `PATH="/ws/world"` (`urlMap.get(PATH)`) — a second `/ws/observer` handler does **not** trip its single-route invariant. No change needed there.

**Slices:** Tasks 1–11 = **Server slice** (independently shippable, headless-testable; Task 11 is the opt-in scale-gate measurement). Tasks 12–13 = **UI slice** (static page + merge-back docs). The server slice is complete and useful without the UI (any WS client can read frames).

---

# SLICE 1 — SERVER

## Task 1: `ObserverConfig` + yaml

**Files:**
- Create: `src/main/java/com/paralife/observer/ObserverConfig.java`
- Create: `src/test/java/com/paralife/observer/ObserverConfigTest.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `ObserverConfig` record with `boolean enabled()`, `int maxSessions()`, static `ObserverConfig defaults()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/paralife/observer/ObserverConfigTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ObserverConfigTest {

    @Test
    void defaultsAreDisabledWithPositiveCap() {
        ObserverConfig c = ObserverConfig.defaults();
        assertThat(c.enabled()).as("observer ships disabled by default").isFalse();
        assertThat(c.maxSessions()).as("default cap is positive").isPositive();
    }

    @Test
    void rejectsNonPositiveMaxSessions() {
        assertThatThrownBy(() -> new ObserverConfig(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-sessions");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverConfigTest'`
Expected: FAIL — `ObserverConfig` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/paralife/observer/ObserverConfig.java`:
```java
package com.paralife.observer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the read-only observer visualiser endpoint ({@code /ws/observer}).
 *
 * <p>Ships {@code enabled=false} — an operator opts in. {@code maxSessions} caps
 * concurrent observers (enforced atomically in {@link ObserverSessionGate}). Real
 * auth / origin policy / rate-limiting is a named later hardening slice (BACKLOG).
 */
@ConfigurationProperties(prefix = "paralife.observer")
public record ObserverConfig(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("4") int maxSessions) {

    @ConstructorBinding
    public ObserverConfig {
        if (maxSessions <= 0) {
            throw new IllegalArgumentException(
                    "paralife.observer.max-sessions must be > 0 (got " + maxSessions + ")");
        }
    }

    /** Convenience for unit tests that instantiate components without Spring. */
    public static ObserverConfig defaults() {
        return new ObserverConfig(false, 4);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverConfigTest'`
Expected: PASS.

- [ ] **Step 5: Add the yaml block**

In `src/main/resources/application.yml`, under the top-level `paralife:` mapping (sibling of `admission:` / `websocket:`), add:
```yaml
  observer:
    enabled: false
    max-sessions: 4
```

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/observer/ObserverConfig.java \
        src/test/java/com/paralife/observer/ObserverConfigTest.java \
        src/main/resources/application.yml
git commit -m "feat(observer): ObserverConfig (enabled/max-sessions), default disabled"
```

---

## Task 2: `SpeciesSpawnCounter`

**Files:**
- Create: `src/main/java/com/paralife/engine/SpeciesSpawnCounter.java`
- Create: `src/test/java/com/paralife/engine/SpeciesSpawnCounterTest.java`

**Interfaces:**
- Produces: `SpeciesSpawnCounter` with `void increment(ParticleType)`, `long get(ParticleType)`, `long[] snapshot()` (indexed by `ParticleType.ordinal()`).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/paralife/engine/SpeciesSpawnCounterTest.java`:
```java
package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.world.Entity.ParticleType;
import org.junit.jupiter.api.Test;

class SpeciesSpawnCounterTest {

    @Test
    void incrementRaisesOnlyTheTargetSpeciesByExactlyOne() {
        SpeciesSpawnCounter counter = new SpeciesSpawnCounter();
        long before = counter.get(ParticleType.CATALYST);

        counter.increment(ParticleType.CATALYST);

        assertThat(counter.get(ParticleType.CATALYST) - before)
                .as("committed spawn is an exact +1 delta on its species").isEqualTo(1L);
        assertThat(counter.get(ParticleType.MEMBRANE))
                .as("control: other species untouched").isZero();
        assertThat(counter.get(ParticleType.SPORE))
                .as("control: other species untouched").isZero();
    }

    @Test
    void snapshotDeltaIsPlusOneOnlyForTheIncrementedOrdinal() {
        // Firewall (O4): assert a before/after DELTA around one increment — never an
        // accumulated total. This still pins the ordinal MAPPING (which slot each species
        // lands in) without asserting any cumulative magnitude.
        SpeciesSpawnCounter counter = new SpeciesSpawnCounter();
        long[] before = counter.snapshot();

        counter.increment(ParticleType.SPORE);
        long[] after = counter.snapshot();

        assertThat(after[ParticleType.SPORE.ordinal()] - before[ParticleType.SPORE.ordinal()])
                .as("increment lands on the SPORE ordinal, delta exactly +1").isEqualTo(1L);
        assertThat(after[ParticleType.CATALYST.ordinal()] - before[ParticleType.CATALYST.ordinal()])
                .as("control: other ordinals unchanged").isZero();
        assertThat(after[ParticleType.MEMBRANE.ordinal()] - before[ParticleType.MEMBRANE.ordinal()])
                .as("control: other ordinals unchanged").isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.engine.SpeciesSpawnCounterTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/paralife/engine/SpeciesSpawnCounter.java`:
```java
package com.paralife.engine;

import com.paralife.world.Entity.ParticleType;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/**
 * Cumulative per-species committed-spawn counter (process lifetime).
 *
 * <p>A "spawn" is a committed biological birth/admission — incremented only after
 * successful placement/registration. Admission runs on WebSocket threads and
 * reproduction on the tick thread, so counts are atomic per species (a plain
 * {@code long} map would lose increments). Indexed by {@link ParticleType#ordinal()}.
 *
 * <p>Firewall note: consumers assert only the +1 state-transition delta of a single
 * committed creation — never an accumulated total, share, or {@code > 0} predicate.
 */
@Component
public class SpeciesSpawnCounter {

    private final AtomicLongArray counts = new AtomicLongArray(ParticleType.values().length);

    public void increment(ParticleType type) {
        counts.incrementAndGet(type.ordinal());
    }

    public long get(ParticleType type) {
        return counts.get(type.ordinal());
    }

    /** Immutable point-in-time copy for the observer frame (indexed by ordinal). */
    public long[] snapshot() {
        long[] out = new long[counts.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = counts.get(i);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.engine.SpeciesSpawnCounterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/engine/SpeciesSpawnCounter.java \
        src/test/java/com/paralife/engine/SpeciesSpawnCounterTest.java
git commit -m "feat(observer): SpeciesSpawnCounter (atomic per-species committed births)"
```

---

## Task 3: Wire spawn increments into `ActionResolver` + `WorldWebSocketHandler`

This pins **O4** at the production creation paths. `SpeciesSpawnCounter` is **setter-injected** (`@Autowired(required=false)`) mirroring `TickBroadcaster.setOutboundSender` — so existing `ActionResolver`/`WorldWebSocketHandler` unit tests that construct these by hand are not broken, and increments are null-guarded.

**Files:**
- Modify: `src/main/java/com/paralife/engine/ActionResolver.java` (setter + sites ~674, ~692, ~936)
- Modify: `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` (setter + committed-placement site ~670)
- Create: `src/test/java/com/paralife/engine/ActionResolverSpawnCounterTest.java`
- Modify: `src/test/java/com/paralife/engine/ReproducerAutoPlaceTest.java` (composite-bud site coverage — Step 5b)

**Interfaces:**
- Consumes: `SpeciesSpawnCounter` (Task 2).
- Produces: `ActionResolver.setSpawnCounter(SpeciesSpawnCounter)`; `WorldWebSocketHandler.setSpawnCounter(SpeciesSpawnCounter)`.

- [ ] **Step 1: Write the failing test** (ActionResolver-direct, engine-only — mirrors `ActionResolverReproduceTest` harness)

`src/test/java/com/paralife/engine/ActionResolverSpawnCounterTest.java`:
```java
package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.codec.Frame;
import com.paralife.metrics.WebSocketMetrics;
import com.paralife.websocket.SessionRegistry;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O4 at the reproduce paths: a committed offspring increments spawns[parentSpecies]
 * by exactly 1; a rejected reproduce commits nothing. Engine-direct, zero ticks
 * advanced beyond the single resolveActions call. Fixture-owned profile
 * (bonusOffspringChance = 0.0) so exactly one child is placed.
 */
class ActionResolverSpawnCounterTest {

    private static final int DIM = 16;
    private WorldGrid worldGrid;
    private BotRegistry botRegistry;
    private SessionRegistry sessionRegistry;
    private CompositeRegistry compositeRegistry;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(DIM, DIM));
        botRegistry = new BotRegistry();
        sessionRegistry = new SessionRegistry(new WebSocketMetrics(new SimpleMeterRegistry()));
        compositeRegistry = new CompositeRegistry();
    }

    private ActionResolver resolverWith(MetabolicProfile profile, SpeciesSpawnCounter counter) {
        ActionResolver r = new ActionResolver(worldGrid, botRegistry, sessionRegistry,
                SimulationConfig.defaults(), compositeRegistry, CompositeConfig.defaults(), profile);
        r.setSpawnCounter(counter);
        return r;
    }

    /** Fixture-owned profile: cost 30, no cooldown, no starvation floor, bonusChance 0.0. */
    private static MetabolicProfile profile() {
        var p = new MetabolicProfile.TypeProfile(100, 1, 10, 10, 5, 30, 0, 0.0, 1, 0, 0);
        return new MetabolicProfile(p, p, p);
    }

    /** Same, but bonusOffspringChance = 1.0 → the bonus-offspring site always fires. */
    private static MetabolicProfile profileWithBonus() {
        var p = new MetabolicProfile.TypeProfile(100, 1, 10, 10, 5, 30, 0, 1.0, 1, 0, 0);
        return new MetabolicProfile(p, p, p);
    }

    private static Frame.ActionFrame reproduce(char numpad) {
        return new Frame.ActionFrame('R', Optional.of(String.valueOf(numpad)));
    }

    private void placeBot(String sessionId, String entityId, Position pos, int energy) {
        worldGrid.setEntity(pos.x(), pos.y(), new Particle(entityId, ParticleType.CATALYST, energy, 100));
        botRegistry.register(sessionId, entityId, pos);
    }

    @Test
    void committedReproduceIncrementsParentSpeciesByExactlyOne() {
        var counter = new SpeciesSpawnCounter();
        var resolver = resolverWith(profile(), counter);
        placeBot("s", "e", new Position(5, 5), 70); // CATALYST, above cost

        long before = counter.get(ParticleType.CATALYST);
        resolver.resolveActions(1, Map.of("s", reproduce('6'))); // East → (6,5) empty

        assertThat(counter.get(ParticleType.CATALYST) - before)
                .as("one committed child → spawns[CATALYST] += 1").isEqualTo(1L);
        assertThat(counter.get(ParticleType.MEMBRANE))
                .as("control: unrelated species unchanged").isZero();
    }

    @Test
    void bonusOffspringSiteAlsoIncrements_deltaTwoForParentSpecies() {
        // bonusOffspringChance=1.0 → the SEPARATE bonus-offspring creation site (ActionResolver
        // ~:692) fires alongside the primary (~:674): two committed children, both parent-species.
        var counter = new SpeciesSpawnCounter();
        var resolver = resolverWith(profileWithBonus(), counter);
        placeBot("s", "e", new Position(5, 5), 90); // ample energy; open neighbourhood for the bonus

        long before = counter.get(ParticleType.CATALYST);
        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        assertThat(counter.get(ParticleType.CATALYST) - before)
                .as("primary + forced bonus offspring → exactly +2").isEqualTo(2L);
    }

    @Test
    void rejectedReproduceIncrementsNothing() {
        var counter = new SpeciesSpawnCounter();
        var resolver = resolverWith(profile(), counter);
        placeBot("s", "e", new Position(5, 5), 29); // below cost 30 → no child

        resolver.resolveActions(1, Map.of("s", reproduce('6')));

        assertThat(counter.get(ParticleType.CATALYST))
                .as("rejected reproduce commits no birth (failed-path control)").isZero();
    }
}
```

> **O4 coverage map (verified):** primary + forced-bonus + below-cost-control are pinned above; the **composite-reproducer bud** site (`ActionResolver` ~:936) is pinned in Step 5b (a real edit to `ReproducerAutoPlaceTest`, listed in Files + commit). The **admission** path is pinned by Task 9's co-located `/ws/world` control (`spawns[CATALYST] += 1`). **Respawn needs no separate test:** verified against source, `handleRegister` (`WorldWebSocketHandler.java:508`) routes fresh **and** respawn through the *same* placement block — both converge at `:610` and hit the *single* increment line after `botRegistry.register` (`~:670`); there is no respawn-specific counter code, so the fresh-admission control exercises the exact line respawn also executes. Every production creation site is thus pinned as a `+1`/`+2` delta, plus a failed-path control — never an accumulated total.

- [ ] **Step 5b: Pin the composite-reproducer bud site** (`ActionResolver` ~:936)

In `src/test/java/com/paralife/engine/ReproducerAutoPlaceTest.java`, inject a counter into the fixture and assert the bud delta. In `setUp()`, after the `resolver = new ActionResolver(...)` line, add:
```java
budSpawnCounter = new SpeciesSpawnCounter();
resolver.setSpawnCounter(budSpawnCounter);
```
(add a field `private SpeciesSpawnCounter budSpawnCounter;`). Then add two assertions using that counter:
- In the existing **successful auto-place** test (the one that asserts a bud particle was placed at the nearest free cell), capture `long before = budSpawnCounter.get(<the composite member's ParticleType>);` before the resolve and assert `budSpawnCounter.get(<type>) - before == 1L` after — the committed bud increments its species by exactly 1.
- In the existing **bounded-skip** test (neighbourhood full → no spawn, pool energy unchanged), assert `budSpawnCounter.get(<type>) == 0` — a skipped bud commits no birth (failed-path control).

The `<type>` is the REPRODUCER member's `type()` used by that fixture (read it from the fixture's composite-member setup). Run:
```
./gradlew test --tests 'com.paralife.engine.ReproducerAutoPlaceTest'
```
Expected: PASS (the bud site now increments; the skip site does not). RED-test by commenting the `~:936` increment → the successful-bud delta drops to 0.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.engine.ActionResolverSpawnCounterTest'`
Expected: FAIL — `setSpawnCounter` does not exist (compile error).

- [ ] **Step 3: Add the setter + field to `ActionResolver`**

In `ActionResolver.java`, add the import and a field near the other fields, and a setter (place beside the class's existing methods):
```java
// imports (top of file, with the other org.springframework imports):
import org.springframework.beans.factory.annotation.Autowired;

// field (with the other private final fields — note: NOT final, setter-injected):
private SpeciesSpawnCounter spawnCounter;

// setter (mirrors TickBroadcaster.setOutboundSender — optional dependency):
@Autowired(required = false)
public void setSpawnCounter(SpeciesSpawnCounter spawnCounter) {
    this.spawnCounter = spawnCounter;
}
```
(`Autowired` may already be imported — the file imports `org.springframework.beans.factory.annotation.Autowired` per its header; if present, skip the duplicate import.)

- [ ] **Step 4: Add guarded increments at the 3 offspring sites**

**Primary offspring** — after `liveEntityRegistry.register(child.id(), target);` (~line 680):
```java
if (spawnCounter != null) spawnCounter.increment(ra.particle.type());
```
**Bonus offspring** — inside the `if (bonusTarget != null) { ... }` block, after `claimedCells.add(bonusTarget);` (~line 699):
```java
if (spawnCounter != null) spawnCounter.increment(ra.particle.type());
```
**Composite reproducer bud** — after `liveEntityRegistry.register(child.id(), target);` (~line 942):
```java
if (spawnCounter != null) spawnCounter.increment(rca.member.type());
```

- [ ] **Step 5: Run the ActionResolver test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.engine.ActionResolverSpawnCounterTest'`
Expected: PASS.

- [ ] **Step 6: Add the setter + committed-placement increment to `WorldWebSocketHandler`**

In `WorldWebSocketHandler.java`, add the field + setter (mirror the ActionResolver pattern):
```java
private SpeciesSpawnCounter spawnCounter;

@Autowired(required = false)
public void setSpawnCounter(SpeciesSpawnCounter spawnCounter) {
    this.spawnCounter = spawnCounter;
}
```
(Import `com.paralife.engine.SpeciesSpawnCounter`; `Autowired` is already imported in this handler.)

Then, in the fresh-registration path, immediately **after** `botRegistry.register(session.getId(), entityId, pos);` (~line 670 — this is past the only early-return `if (!placed) { ... return; }`, so it runs exactly on committed placements including respawn):
```java
if (spawnCounter != null) spawnCounter.increment(particleType);
```
(`particleType` is the `ParticleType` already in scope at this point, computed earlier in the method.)

- [ ] **Step 7: Run the full engine + websocket suites (no regressions from the wiring)**

Run: `./gradlew test --tests 'com.paralife.engine.*' --tests 'com.paralife.websocket.*'`
Expected: PASS (setter injection means existing hand-constructed instances still compile and run with `spawnCounter == null`, increments no-op).

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/engine/ActionResolver.java \
        src/main/java/com/paralife/websocket/WorldWebSocketHandler.java \
        src/test/java/com/paralife/engine/ActionResolverSpawnCounterTest.java \
        src/test/java/com/paralife/engine/ReproducerAutoPlaceTest.java
git commit -m "feat(observer): wire SpeciesSpawnCounter into reproduce + admission commit sites (O4)"
```

---

## Task 4: `EnvironmentSnapshot` + `EnvironmentEngine.snapshot()` + lightning capture (O5)

**Files:**
- Create: `src/main/java/com/paralife/engine/EnvironmentSnapshot.java`
- Modify: `src/main/java/com/paralife/engine/EnvironmentEngine.java` (add field, clear-on-onTick, append-at-apply, `snapshot()`)
- Create: `src/test/java/com/paralife/engine/EnvironmentSnapshotTest.java`

**Interfaces:**
- Produces: `EnvironmentSnapshot(List<EnvCell> toxin, List<EnvCell> mutagen, List<Position> lightning)` with nested `EnvCell(int x, int y, int value)`; `EnvironmentEngine.snapshot()` returning it.

- [ ] **Step 1: Write the failing test** (engine-direct; uses existing test helpers `stampMutagenForTest`, `applyLightningAtForTest`, `toxinIntensityAt` + a toxin stamp)

`src/test/java/com/paralife/engine/EnvironmentSnapshotTest.java`:
```java
package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Env snapshot contract: sparse non-zero toxin intensity + mutagen strain (seeded via the
 * engine's own package-private test-stamp helpers), and this-tick lightning coordinates that
 * clear on the next onTick. Engine-direct and DETERMINISTIC: a test-owned config with
 * enabled=false + a seeded RNG means onTick injects NO random toxin/mutagen/lightning, so the
 * "cleared → empty" assertion cannot be masked by a freshly-spawned strike. (The
 * lightningStrikesThisTick.clear() runs at the TOP of onTick, before the enabled gate, so the
 * clear still fires with events disabled.) Helpers verified package-private:
 * stampToxinIntensityForTest / stampMutagenForTest / applyLightningAtForTest (EnvironmentEngine
 * :1569 / :1598 / :1550) — test stays in package com.paralife.engine.
 */
class EnvironmentSnapshotTest {

    /** Real EnvironmentEngine with a deterministic, event-disabled config (mirrors LightningTest wiring). */
    private static EnvironmentEngine newEngine(int dim) {
        WorldGrid grid = new WorldGrid(new GridConfig(dim, dim));
        EnvironmentConfig d = EnvironmentConfig.defaults();
        // enabled=false so onTick spawns nothing; reuse the default sub-configs via accessors.
        EnvironmentConfig cfg = new EnvironmentConfig(
                false, 42L, d.lightning(), d.toxin(), d.mutagen(), d.compost());
        BuffRegistry buffs = new BuffRegistry();
        EnvCleanupHooksBean hooks = new EnvCleanupHooksBean();
        DeathFinalizer finalizer = new DeathFinalizer(
                grid, new BotRegistry(), buffs, mock(CompositeRegistry.class), hooks,
                mock(SimulationEngine.class));
        EnvironmentEngine env = new EnvironmentEngine(grid,
                new SeasonTracker(new SeasonsConfig(200, 0.5)),
                cfg, buffs, FertilityConfig.defaults(), finalizer, hooks,
                (ToxinPathGenerator) null, new Random(42L));
        hooks.registerCompostSink(env::applyCompost);
        return env;
    }

    @Test
    void snapshotListsOnlyNonZeroCellsWithCorrectValues() {
        EnvironmentEngine env = newEngine(16);
        env.stampToxinIntensityForTest(new Position(1, 2), 180); // intensity magnitude
        env.stampMutagenForTest(new Position(3, 4), 42);         // strain id (NOT a magnitude)

        EnvironmentSnapshot snap = env.snapshot();

        assertThat(snap.toxin())
                .as("only the seeded non-zero toxin cell, carrying its intensity")
                .containsExactly(new EnvironmentSnapshot.EnvCell(1, 2, 180));
        assertThat(snap.mutagen())
                .as("only the seeded non-zero mutagen cell, carrying its strain id")
                .containsExactly(new EnvironmentSnapshot.EnvCell(3, 4, 42));
    }

    @Test
    void snapshotExcludesZeroCells() {
        // control: nothing seeded → both layers empty. Arms "only non-zero" — an impl that
        // listed every cell (or a default value on clean cells) would fail here.
        EnvironmentSnapshot snap = newEngine(16).snapshot();
        assertThat(snap.toxin()).isEmpty();
        assertThat(snap.mutagen()).isEmpty();
    }

    @Test
    void appliedLightningPresentThisTick_multipleCoords_noDuplicates_clearsNext() {
        EnvironmentEngine env = newEngine(16);
        env.applyLightningAtForTest(7, 8);
        env.applyLightningAtForTest(9, 10);

        assertThat(env.snapshot().lightning())
                .as("each applied strike CENTER appears exactly once (append-once, not per-affected-cell)")
                .containsExactly(new Position(7, 8), new Position(9, 10));

        env.onTick(new TickEvent(1)); // clears the per-tick list at onTick start (before the enabled gate)

        assertThat(env.snapshot().lightning())
                .as("lightning list is transient — cleared by the next onTick").isEmpty();
    }
}
```

> **Determinism note:** `EnvironmentConfig.defaults()` ships `enabled=true` with a probabilistic lightning generator (`EnvironmentConfig:37-44,92`), so an `onTick` on the default config could randomly spawn a strike and defeat the "cleared → empty" assertion. The `enabled=false` copy above removes that entire class of flakiness while still exercising the clear (which runs before the enabled gate). The constructor arg list is copied verbatim from `LightningTest.java:63-81` — do not invent collaborators.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.engine.EnvironmentSnapshotTest'`
Expected: FAIL — `EnvironmentSnapshot` and `snapshot()` do not exist.

- [ ] **Step 3: Create `EnvironmentSnapshot`**

`src/main/java/com/paralife/engine/EnvironmentSnapshot.java`:
```java
package com.paralife.engine;

import com.paralife.world.Position;
import java.util.List;

/**
 * Immutable, tick-owned projection of environment field state for the observer
 * visualiser. Values are copied by value (never a reference into the mutable
 * shadow arrays). Toxin carries an intensity magnitude (1–255); mutagen carries a
 * strain identifier (1–255, 0=clean) — NOT a magnitude, so the renderer draws it
 * categorically. Lightning lists coordinates of strikes applied on this tick only.
 */
public record EnvironmentSnapshot(List<EnvCell> toxin, List<EnvCell> mutagen, List<Position> lightning) {

    /** A single non-zero env cell: {@code value} is toxin intensity or mutagen strain id. */
    public record EnvCell(int x, int y, int value) {}
}
```

- [ ] **Step 4: Add lightning capture + `snapshot()` to `EnvironmentEngine`**

Add the import + field (near the other private fields):
```java
import com.paralife.engine.EnvironmentSnapshot.EnvCell;
// ...
/** Coordinates of lightning strikes applied on the current tick (transient; O5). */
private final java.util.List<Position> lightningStrikesThisTick = new java.util.ArrayList<>();
```

In `onTick(TickEvent event)`, at the top alongside the existing `cellStatusStaging.clear(); entityStatusStaging.clear();` (~line 361), add:
```java
lightningStrikesThisTick.clear();
```

At the lightning **apply** site — record the strike **center exactly once per applied strike**. `applyLightningAtInternal(int cx, int cy, ...)` (starts ~line 1149) is a nested `(dx,dy)` loop over every affected cell, so the append MUST go **before that loop** (or after it), NOT inside it — appending inside would record one entry per affected cell (a duplicate storm). Add as the first statement of the method body:
```java
lightningStrikesThisTick.add(new Position(cx, cy)); // one entry per applied strike center
```

Add the public `snapshot()` method (place near the existing `toxinIntensityAt` accessor):
```java
/**
 * Immutable, tick-owned snapshot of env field state for the observer visualiser.
 * Sparse: only non-zero cells are listed. Values copied by value. Call on the tick
 * thread (after this engine's {@code @Order(14)} stage, before frame serialization).
 */
public EnvironmentSnapshot snapshot() {
    int w = toxinGrid.length;
    int h = toxinGrid[0].length;
    java.util.List<EnvCell> toxin = new java.util.ArrayList<>();
    java.util.List<EnvCell> mutagen = new java.util.ArrayList<>();
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            int ti = toxinGrid[x][y] & 0xFF;
            if (ti > 0) toxin.add(new EnvCell(x, y, ti));
            int ms = mutagenGrid[x][y] & 0xFF;
            if (ms > 0) mutagen.add(new EnvCell(x, y, ms));
        }
    }
    return new EnvironmentSnapshot(
            java.util.List.copyOf(toxin),
            java.util.List.copyOf(mutagen),
            java.util.List.copyOf(lightningStrikesThisTick));
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.engine.EnvironmentSnapshotTest'`
Expected: PASS. (If the mutagen literal 42 does not survive `stampMutagenForTest`, read that helper — it stamps the raw strain byte; 42 is in-range.)

- [ ] **Step 6: Run the full env suite (no regression from the new field/clear)**

Run: `./gradlew test --tests 'com.paralife.engine.Environment*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/engine/EnvironmentSnapshot.java \
        src/main/java/com/paralife/engine/EnvironmentEngine.java \
        src/test/java/com/paralife/engine/EnvironmentSnapshotTest.java
git commit -m "feat(observer): EnvironmentEngine.snapshot() + transient lightning coords (O5)"
```

---

## Task 5: Frame DTOs + `ObserverFrameBuilder` + `BotRegistry.ownedEntityIds()` (O3, O3b, O7, O7b)

This is the pure, engine-direct core: the frame contract. No server, no threads.

**Files:**
- Create: `src/main/java/com/paralife/observer/ObserverFrame.java`
- Create: `src/main/java/com/paralife/observer/ObserverFrameBuilder.java`
- Modify: `src/main/java/com/paralife/engine/BotRegistry.java` (add `ownedEntityIds()`)
- Create: `src/test/java/com/paralife/observer/ObserverFrameBuilderTest.java`

**Interfaces:**
- Consumes: `WorldGrid.GridSnapshot` (has `int width()`, `int height()`, `Cell[][] cells()`); `Cell.occupant()` → `Entity | null`; `EnvironmentSnapshot` (Task 4); `SpeciesSpawnCounter.snapshot()` → `long[]` (Task 2); `BotRegistry.ownedEntityIds()` → `Set<String>`.
- Entity accessors (verified): `Particle.type()`/`energy()`/`id()`; `BondedPair.primaryType()`/`secondaryType()`/`energy()`/`id()`; `CompositeMember.type()`/`compositeId()`/`role()`/`energy()`/`id()`; `Nutrient.level()`/`id()`; `Rock.id()`. `Role` enum: LOCOMOTOR/FEEDER/ATTACKER/DEFENDER/REPRODUCER/SENSOR. `ParticleType`: CATALYST/MEMBRANE/SPORE. **There is no `species()` accessor — species = `type().name()`.**
- Produces: `ObserverFrameBuilder.buildWorld(long tick, GridSnapshot, EnvironmentSnapshot, Set<String> ownedIds, long[] spawnsByOrdinal)` → `ObserverFrame.WorldFrame`; `buildBootstrap(GridSnapshot)` → `ObserverFrame.BootstrapFrame`; `ObserverFrameBuilder.SCHEMA_VERSION`.

- [ ] **Step 1: Add `ownedEntityIds()` to `BotRegistry`**

In `BotRegistry.java` (the `entityToSession` map is `ConcurrentHashMap<String,String>` at line 67), add:
```java
/**
 * Immutable copy of every currently-owned entity id. Called on the tick thread by
 * the observer broadcaster to classify {@code brained} without a live per-entity
 * query during serialization. {@code ConcurrentHashMap.keySet()} is weakly
 * consistent; {@code Set.copyOf} takes a stable snapshot.
 */
public java.util.Set<String> ownedEntityIds() {
    return java.util.Set.copyOf(entityToSession.keySet());
}
```

- [ ] **Step 2: Write the failing test** (engine-direct, seeded fixture, zero ticks advanced)

`src/test/java/com/paralife/observer/ObserverFrameBuilderTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;

import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.world.Entity;
import com.paralife.world.Entity.BondedPair;
import com.paralife.world.Entity.CompositeMember;
import com.paralife.world.Entity.Nutrient;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.Entity.Role;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O3/O3b/O7/O7b — the frame CONTRACT. Seeded engine-direct fixture, ZERO ticks
 * advanced; expected values enumerated solely from the test-owned fixture (never
 * from a production census function). Permitted mechanism per CLAUDE.md:73/:77.
 */
class ObserverFrameBuilderTest {

    private final ObserverFrameBuilder builder = new ObserverFrameBuilder();

    private static WorldGrid grid16() {
        return new WorldGrid(new GridConfig(16, 16));
    }

    private static EnvironmentSnapshot emptyEnv() {
        return new EnvironmentSnapshot(List.of(), List.of(), List.of());
    }

    private static long[] noSpawns() {
        return new long[] {0, 0, 0};
    }

    @Test
    void worldFrameCarriesEveryOccupantWithKindAndCoordinates() {
        WorldGrid grid = grid16();
        grid.setEntity(1, 1, new Particle("p1", ParticleType.CATALYST, 45, 100));
        grid.setEntity(2, 2, new Nutrient("n1", 20));
        grid.setEntity(3, 3, new Entity.Rock("r1")); // rock excluded from world frame

        ObserverFrame.WorldFrame f = builder.buildWorld(
                12L, grid.snapshot(), emptyEnv(), Set.of(), noSpawns());

        assertThat(f.type()).isEqualTo("world");
        assertThat(f.schemaVersion()).isEqualTo(ObserverFrameBuilder.SCHEMA_VERSION);
        assertThat(f.tick()).isEqualTo(12L);
        assertThat(f.entities())
                .as("particle + nutrient present at their coords; rock excluded")
                .anySatisfy(e -> {
                    assertThat(e.kind()).isEqualTo("particle");
                    assertThat(e.x()).isEqualTo(1);
                    assertThat(e.y()).isEqualTo(1);
                    assertThat(e.species()).isEqualTo("CATALYST");
                    assertThat(e.energy()).isEqualTo(45);
                })
                .anySatisfy(e -> assertThat(e.kind()).isEqualTo("nutrient"))
                .noneSatisfy(e -> assertThat(e.kind()).isEqualTo("rock"));
    }

    /** Select the DTO at exact grid coordinates (entities carry no id on the wire). */
    private static ObserverFrame.EntityDto dtoAt(ObserverFrame.WorldFrame f, int x, int y) {
        return f.entities().stream().filter(e -> e.x() == x && e.y() == y)
                .findFirst().orElseThrow(() -> new AssertionError("no entity at " + x + "," + y));
    }

    @Test
    void brainedTrueForOwnedFalseForWild_particlesAndStructures() {
        WorldGrid grid = grid16();
        grid.setEntity(1, 1, new Particle("ownedP", ParticleType.CATALYST, 50, 100));
        grid.setEntity(2, 2, new Particle("wildP", ParticleType.SPORE, 50, 100));
        grid.setEntity(6, 6, new BondedPair("ownedBp", ParticleType.CATALYST, ParticleType.SPORE,
                60, 200, "pe", "se", 1, 1, 1));
        grid.setEntity(7, 7, new BondedPair("wildBp", ParticleType.MEMBRANE, ParticleType.SPORE,
                60, 200, "pe2", "se2", 1, 1, 1));

        // ownership set contains the particle AND the structure ids (remapEntity keeps the
        // controlling session across bond formation → structures are frequently brained).
        ObserverFrame.WorldFrame f = builder.buildWorld(
                1L, grid.snapshot(), emptyEnv(), Set.of("ownedP", "ownedBp"), noSpawns());

        assertThat(dtoAt(f, 1, 1).brained()).as("owned particle → true").isTrue();
        assertThat(dtoAt(f, 2, 2).brained()).as("wild particle → false (control)").isFalse();
        assertThat(dtoAt(f, 6, 6).brained()).as("owned bondedPair → true (O3 structures)").isTrue();
        assertThat(dtoAt(f, 7, 7).brained()).as("wild bondedPair → false (control)").isFalse();
    }

    @Test
    void envLayersProjectIntensityStrainAndLightningCoords() {
        WorldGrid grid = grid16();
        EnvironmentSnapshot env = new EnvironmentSnapshot(
                List.of(new EnvironmentSnapshot.EnvCell(1, 2, 180)), // toxin intensity magnitude
                List.of(new EnvironmentSnapshot.EnvCell(3, 4, 42)),  // mutagen strain id
                List.of(new Position(5, 6), new Position(7, 8)));    // this-tick lightning

        ObserverFrame.WorldFrame f = builder.buildWorld(9L, grid.snapshot(), env, Set.of(), noSpawns());

        assertThat(f.env().toxin())
                .containsExactly(new ObserverFrame.ToxinCell(1, 2, 180));
        assertThat(f.env().mutagen())
                .as("mutagen DTO carries strain id, not intensity")
                .containsExactly(new ObserverFrame.MutagenCell(3, 4, 42));
        assertThat(f.env().lightning())
                .containsExactly(new ObserverFrame.Coord(5, 6), new ObserverFrame.Coord(7, 8));
    }

    @Test
    void subtypeFieldsEmittedForBondedPairAndCompositeMember() {
        WorldGrid grid = grid16();
        grid.setEntity(4, 4, new BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE,
                60, 200, "pe", "se", 1, 1, 1));
        grid.setEntity(5, 5, new CompositeMember("cm", "c-7", ParticleType.MEMBRANE, Role.FEEDER, 33, 100));

        ObserverFrame.WorldFrame f = builder.buildWorld(
                1L, grid.snapshot(), emptyEnv(), Set.of(), noSpawns());

        assertThat(f.entities()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo("bondedPair");
            assertThat(e.primarySpecies()).isEqualTo("CATALYST");
            assertThat(e.secondarySpecies()).isEqualTo("SPORE");
        });
        assertThat(f.entities()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo("compositeMember");
            assertThat(e.species()).isEqualTo("MEMBRANE");
            assertThat(e.compositeId()).isEqualTo("c-7");
            assertThat(e.role()).isEqualTo("FEEDER");
        });
    }

    @Test
    void populationsCensusCountsBothSpeciesOfAPairAndIncludesZeroEnergyMember() {
        WorldGrid grid = grid16();
        grid.setEntity(1, 1, new Particle("p", ParticleType.CATALYST, 50, 100));
        grid.setEntity(2, 2, new BondedPair("bp", ParticleType.CATALYST, ParticleType.SPORE,
                60, 200, "pe", "se", 1, 1, 1));
        grid.setEntity(3, 3, new CompositeMember("cm", "c-1", ParticleType.MEMBRANE, Role.FEEDER, 0, 100)); // zero energy
        grid.setEntity(4, 4, new Nutrient("n", 10)); // excluded

        ObserverFrame.WorldFrame f = builder.buildWorld(
                1L, grid.snapshot(), emptyEnv(), Set.of(), noSpawns());

        // O7: the zero-energy member is PRESENT in entities (occupancy, not just counted)
        assertThat(dtoAt(f, 3, 3).kind()).isEqualTo("compositeMember");
        assertThat(dtoAt(f, 3, 3).energy()).as("zero-energy member still emitted, energy 0").isEqualTo(0);

        // particle CATALYST(+1); pair CATALYST(+1) & SPORE(+1); member MEMBRANE(+1); nutrient excluded
        assertThat(f.populations().get("CATALYST")).as("particle + pair-primary").isEqualTo(2);
        assertThat(f.populations().get("SPORE")).as("pair-secondary").isEqualTo(1);
        assertThat(f.populations().get("MEMBRANE"))
                .as("zero-energy composite member still counts — occupancy census, no liveness filter")
                .isEqualTo(1);
    }

    @Test
    void bootstrapCarriesRocksAndGridDimsOnly() {
        WorldGrid grid = grid16();
        grid.setEntity(3, 3, new Entity.Rock("r1"));
        grid.setEntity(1, 1, new Particle("p", ParticleType.CATALYST, 50, 100)); // NOT in bootstrap

        ObserverFrame.BootstrapFrame b = builder.buildBootstrap(grid.snapshot());

        assertThat(b.type()).isEqualTo("bootstrap");
        assertThat(b.schemaVersion()).isEqualTo(ObserverFrameBuilder.SCHEMA_VERSION);
        assertThat(b.grid().width()).isEqualTo(16);
        assertThat(b.grid().height()).isEqualTo(16);
        assertThat(b.rocks()).containsExactly(new ObserverFrame.RockDto(3, 3));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverFrameBuilderTest'`
Expected: FAIL — `ObserverFrame` / `ObserverFrameBuilder` do not exist.

- [ ] **Step 4: Create the frame DTOs**

`src/main/java/com/paralife/observer/ObserverFrame.java`:
```java
package com.paralife.observer;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Observer wire DTOs (JSON via Jackson, full-word camelCase keys, schemaVersion 1).
 * Two frame types: a once-per-connection {@link BootstrapFrame} (static terrain) and
 * a per-tick {@link WorldFrame} (everything dynamic).
 */
public final class ObserverFrame {

    private ObserverFrame() {}

    public record GridDims(int width, int height) {}

    public record RockDto(int x, int y) {}

    /** Static terrain, sent once on connect (never retransmitted). */
    public record BootstrapFrame(String type, int schemaVersion, GridDims grid, List<RockDto> rocks) {}

    /**
     * One dynamic occupant. Nullable fields are omitted from JSON (NON_NULL): a
     * nutrient has only kind/energy; a particle adds species/brained; a bondedPair
     * uses primarySpecies/secondarySpecies; a compositeMember adds compositeId/role.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityDto(
            int x, int y, String kind,
            String species, Integer energy, Boolean brained,
            String primarySpecies, String secondarySpecies,
            String compositeId, String role) {

        public static EntityDto particle(int x, int y, String species, int energy, boolean brained) {
            return new EntityDto(x, y, "particle", species, energy, brained, null, null, null, null);
        }

        public static EntityDto nutrient(int x, int y, int level) {
            return new EntityDto(x, y, "nutrient", null, level, null, null, null, null, null);
        }

        public static EntityDto bondedPair(int x, int y, String primary, String secondary,
                                           int energy, boolean brained) {
            return new EntityDto(x, y, "bondedPair", null, energy, brained, primary, secondary, null, null);
        }

        public static EntityDto compositeMember(int x, int y, String species, String compositeId,
                                                String role, int energy, boolean brained) {
            return new EntityDto(x, y, "compositeMember", species, energy, brained, null, null, compositeId, role);
        }
    }

    public record ToxinCell(int x, int y, int intensity) {}

    public record MutagenCell(int x, int y, int strain) {}

    public record Coord(int x, int y) {}

    public record EnvDto(List<ToxinCell> toxin, List<MutagenCell> mutagen, List<Coord> lightning) {}

    /** Per-tick dynamic frame. */
    public record WorldFrame(
            String type, int schemaVersion, long tick,
            List<EntityDto> entities, EnvDto env,
            Map<String, Long> scoreboard, Map<String, Integer> populations) {}
}
```

- [ ] **Step 5: Create the builder**

`src/main/java/com/paralife/observer/ObserverFrameBuilder.java`:
```java
package com.paralife.observer;

import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.WorldGrid.GridSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure, stateless builder for observer frames. All inputs are immutable snapshots
 * captured on the tick thread; this does no I/O and holds no state, so it is fully
 * unit-testable and safe to call from the broadcaster's {@code @Order} listener.
 *
 * <p>Census rule (H3/H5, matches PopulationHistory): particle → +1 species;
 * bondedPair → +1 primary AND +1 secondary; compositeMember → +1 species (no
 * liveness filter — a zero-energy member awaiting next-tick cleanup still counts);
 * rock/nutrient excluded.
 */
@Component
public class ObserverFrameBuilder {

    public static final int SCHEMA_VERSION = 1;

    public ObserverFrame.BootstrapFrame buildBootstrap(GridSnapshot grid) {
        List<ObserverFrame.RockDto> rocks = new ArrayList<>();
        Cell[][] cells = grid.cells();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                if (cells[x][y].occupant() instanceof Entity.Rock) {
                    rocks.add(new ObserverFrame.RockDto(x, y));
                }
            }
        }
        return new ObserverFrame.BootstrapFrame(
                "bootstrap", SCHEMA_VERSION,
                new ObserverFrame.GridDims(grid.width(), grid.height()),
                List.copyOf(rocks));
    }

    public ObserverFrame.WorldFrame buildWorld(long tick, GridSnapshot grid,
                                               EnvironmentSnapshot env, Set<String> ownedIds,
                                               long[] spawnsByOrdinal) {
        List<ObserverFrame.EntityDto> entities = new ArrayList<>();
        Map<String, Integer> populations = new LinkedHashMap<>();
        for (ParticleType t : ParticleType.values()) {
            populations.put(t.name(), 0);
        }
        Cell[][] cells = grid.cells();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                Entity e = cells[x][y].occupant();
                if (e == null) continue;
                switch (e) {
                    case Entity.Particle p -> {
                        entities.add(ObserverFrame.EntityDto.particle(
                                x, y, p.type().name(), p.energy(), ownedIds.contains(p.id())));
                        populations.merge(p.type().name(), 1, Integer::sum);
                    }
                    case Entity.Nutrient n -> entities.add(ObserverFrame.EntityDto.nutrient(x, y, n.level()));
                    case Entity.BondedPair bp -> {
                        entities.add(ObserverFrame.EntityDto.bondedPair(
                                x, y, bp.primaryType().name(), bp.secondaryType().name(),
                                bp.energy(), ownedIds.contains(bp.id())));
                        populations.merge(bp.primaryType().name(), 1, Integer::sum);
                        populations.merge(bp.secondaryType().name(), 1, Integer::sum);
                    }
                    case Entity.CompositeMember cm -> {
                        entities.add(ObserverFrame.EntityDto.compositeMember(
                                x, y, cm.type().name(), cm.compositeId(), cm.role().name(),
                                cm.energy(), ownedIds.contains(cm.id())));
                        populations.merge(cm.type().name(), 1, Integer::sum);
                    }
                    case Entity.Rock ignored -> {
                        // static terrain — excluded from the world frame (bootstrap only)
                    }
                }
            }
        }

        ObserverFrame.EnvDto envDto = new ObserverFrame.EnvDto(
                env.toxin().stream()
                        .map(c -> new ObserverFrame.ToxinCell(c.x(), c.y(), c.value())).toList(),
                env.mutagen().stream()
                        .map(c -> new ObserverFrame.MutagenCell(c.x(), c.y(), c.value())).toList(),
                env.lightning().stream()
                        .map(p -> new ObserverFrame.Coord(p.x(), p.y())).toList());

        Map<String, Long> scoreboard = new LinkedHashMap<>();
        for (ParticleType t : ParticleType.values()) {
            long v = t.ordinal() < spawnsByOrdinal.length ? spawnsByOrdinal[t.ordinal()] : 0L;
            scoreboard.put(t.name(), v);
        }

        return new ObserverFrame.WorldFrame(
                "world", SCHEMA_VERSION, tick, entities, envDto, scoreboard, populations);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverFrameBuilderTest'`
Expected: PASS. (The sealed `Entity` switch is exhaustive — compiler-verified over all five permitted types.)

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/observer/ObserverFrame.java \
        src/main/java/com/paralife/observer/ObserverFrameBuilder.java \
        src/main/java/com/paralife/engine/BotRegistry.java \
        src/test/java/com/paralife/observer/ObserverFrameBuilderTest.java
git commit -m "feat(observer): frame DTOs + builder (census/subtype/brained) + BotRegistry.ownedEntityIds (O3/O3b/O7/O7b)"
```

---

## Task 6: `ObserverOutboundSender` — latest-wins off-thread delivery (O1b, O1c)

**Files:**
- Create: `src/main/java/com/paralife/observer/ObserverOutboundSender.java`
- Create: `src/test/java/com/paralife/observer/ObserverOutboundSenderTest.java`

**Interfaces:**
- Produces: `void attach(WebSocketSession)`, `void offer(String sessionId, String payload)`, `void detach(String sessionId)`, `void detach(WebSocketSession)`, `int attachedCount()`, and test accessor `boolean isDraining(String sessionId)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/paralife/observer/ObserverOutboundSenderTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ObserverOutboundSenderTest {

    private static WebSocketSession openSession(String id) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void latestWinsWhileDrainStalled_offerNeverBlocks() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        CopyOnWriteArrayList<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstSendEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        WebSocketSession s = openSession("obs1");
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            firstSendEntered.countDown();
            release.await(2, TimeUnit.SECONDS); // stall the drain VT inside the first send
            return null;
        }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());

        sender.attach(s);
        sender.offer("obs1", "frame-1"); // taken by drain, stalls in send
        assertThat(firstSendEntered.await(2, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        sender.offer("obs1", "frame-2"); // slot
        sender.offer("obs1", "frame-3"); // overwrites frame-2 (latest-wins)
        long offerNanos = System.nanoTime() - t0;
        assertThat(offerNanos).as("offer is non-blocking even while drain is stalled")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(200));

        release.countDown(); // drain resumes → takes frame-3 (frame-2 was dropped)
        Thread.sleep(200);
        assertThat(sent).as("stale frame-2 coalesced away; newest wins")
                .containsExactly("frame-1", "frame-3");
        sender.detach("obs1");
    }

    @Test
    void detachInterruptsDrainAndRemovesSession() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs2");
        sender.attach(s);
        assertThat(sender.attachedCount()).isEqualTo(1);
        assertThat(sender.isDraining("obs2")).isTrue();

        sender.detach("obs2");

        assertThat(sender.attachedCount()).as("session removed on detach").isEqualTo(0);
        // control: a still-attached, non-detached session stays draining
        WebSocketSession s3 = openSession("obs3");
        sender.attach(s3);
        assertThat(sender.isDraining("obs3")).as("un-detached observer remains").isTrue();
        sender.detach("obs3");
    }

    @Test
    void detachClosesTransportSoADrainStalledInSendActuallyTerminates() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        CountDownLatch inSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WebSocketSession s = openSession("obs4");
        // Drain stalls inside sendMessage; session.close() (invoked by detach) unblocks it —
        // this simulates the real Jetty behaviour the OutboundSender docs describe.
        doAnswer(inv -> {
            inSend.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        doAnswer(inv -> { release.countDown(); when(s.isOpen()).thenReturn(false); return null; })
                .when(s).close(org.mockito.ArgumentMatchers.any());

        sender.attach(s);
        sender.offer("obs4", "f1"); // taken → drain stalls in send
        assertThat(inSend.await(2, TimeUnit.SECONDS)).isTrue();

        Thread drain = sender.threadForTest("obs4"); // capture BEFORE detach removes it
        sender.detach(s);                            // close-first → unblocks send, then interrupt
        drain.join(2000);

        assertThat(drain.isAlive())
                .as("a drain stalled in a Jetty write terminates once the transport is closed")
                .isFalse();
    }

    @Test
    void sendIOExceptionClosesTransportAndTerminatesDrain() throws Exception {
        ObserverOutboundSender sender = new ObserverOutboundSender();
        WebSocketSession s = openSession("obs5");
        doThrow(new IOException("broken")).when(s).sendMessage(org.mockito.ArgumentMatchers.any());

        sender.attach(s);
        Thread drain = sender.threadForTest("obs5");
        sender.offer("obs5", "f1"); // drain takes it → send throws IOException → close + exit

        drain.join(2000);
        assertThat(drain.isAlive())
                .as("drain exits on an unrecoverable send failure — no dead-socket spin").isFalse();
        verify(s).close(org.mockito.ArgumentMatchers.any()); // close → Jetty callback → handler cleanup
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverOutboundSenderTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/paralife/observer/ObserverOutboundSender.java`:
```java
package com.paralife.observer;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Off-thread delivery for observers (C2 analog of OutboundSender, NOT routed through
 * the bot STALLED/resume FSM). One drain virtual-thread per observer + a capacity-1
 * latest-wins mailbox. {@link #offer} from the tick thread is non-blocking and
 * overwrites any unsent frame (a slow tab shows the newest world, never a backlog).
 * The drain VT does the {@code synchronized(session)} send. Closed only on
 * detach (transport error / handler close / shutdown) — never on lag.
 */
@Component
public class ObserverOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(ObserverOutboundSender.class);

    private final Map<String, LinkedBlockingQueue<String>> slots = new ConcurrentHashMap<>();
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void attach(WebSocketSession session) {
        String id = session.getId();
        detach(id); // idempotent re-attach
        LinkedBlockingQueue<String> slot = new LinkedBlockingQueue<>(1);
        slots.put(id, slot);
        sessions.put(id, session); // retained so @PreDestroy can close-first (interrupt alone
                                   // cannot unblock a drain stalled inside a Jetty write)
        Thread t = Thread.ofVirtual().name("ws-observer-" + id).start(() -> drain(session, slot));
        threads.put(id, t);
    }

    /** Non-blocking, latest-wins. Single-producer (tick thread) per observer. */
    public void offer(String sessionId, String payload) {
        LinkedBlockingQueue<String> slot = slots.get(sessionId);
        if (slot == null) return;
        slot.poll();        // drop any stale unsent frame
        slot.offer(payload); // install newest
    }

    private void drain(WebSocketSession session, LinkedBlockingQueue<String> slot) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String payload = slot.take();
                if (!session.isOpen()) continue;
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(payload));
                    }
                } catch (IOException e) {
                    // Transport is broken. Close so Jetty fires afterConnectionClosed →
                    // ObserverWebSocketHandler.cleanup (broadcaster.unregister + detach + release-once),
                    // then EXIT this drain — do not spin re-sending into a dead socket. (This is
                    // stricter than the bot OutboundSender's log-and-continue, justified because an
                    // observer has no admission FSM to reap it; the send failure IS its liveness signal.)
                    log.warn("Observer send failed for session={}, closing: {}", session.getId(), e.getMessage());
                    closeQuietly(session);
                    return;
                } catch (RuntimeException e) {
                    log.warn("Observer send error for session={}: {}", session.getId(), e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // best-effort; the handler's cleanup + detach still finish teardown
        }
    }

    public void detach(String sessionId) {
        slots.remove(sessionId);
        sessions.remove(sessionId);
        Thread t = threads.remove(sessionId);
        if (t != null) t.interrupt();
    }

    /** Test/diagnostic: the drain Thread handle (captured before detach removes it). */
    Thread threadForTest(String sessionId) {
        return threads.get(sessionId);
    }

    /** Close-first-then-interrupt to unblock any in-flight Jetty write. */
    public void detach(WebSocketSession session) {
        if (session == null) return;
        if (session.isOpen()) {
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (Exception ignored) {
                // close-on-close races are benign; interrupt below drives termination
            }
        }
        detach(session.getId());
    }

    public int attachedCount() {
        return threads.size();
    }

    /** Test/diagnostic: is a drain VT registered and alive for this session? */
    public boolean isDraining(String sessionId) {
        Thread t = threads.get(sessionId);
        return t != null && t.isAlive();
    }

    @PreDestroy
    public void shutdown() {
        // Close-first for every retained session (mirrors WorldWebSocketHandler.shutdownDetachAll):
        // a drain VT blocked inside a Jetty sendMessage will NOT exit on interrupt alone — closing
        // the transport unblocks the write. detach(WebSocketSession) closes then interrupts.
        for (WebSocketSession s : new ArrayList<>(sessions.values())) {
            detach(s);
        }
        // any residual thread whose session already went away → interrupt-only
        for (String id : new ArrayList<>(threads.keySet())) {
            detach(id);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverOutboundSenderTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/observer/ObserverOutboundSender.java \
        src/test/java/com/paralife/observer/ObserverOutboundSenderTest.java
git commit -m "feat(observer): ObserverOutboundSender — latest-wins drain VT (O1b/O1c)"
```

---

## Task 7: `ObserverBroadcaster` — tick `@Order` capture + serialize-once + offer (O1, O2a, O2b)

**Files:**
- Create: `src/main/java/com/paralife/observer/ObserverBroadcaster.java`
- Create: `src/test/java/com/paralife/observer/ObserverBroadcasterTest.java`

**Interfaces:**
- Consumes: `ObserverFrameBuilder`, `WorldGrid`, `EnvironmentEngine`, `BotRegistry`, `SpeciesSpawnCounter`, `ObserverOutboundSender`.
- Produces: `void register(WebSocketSession)`, `void unregister(WebSocketSession)`, `int observerCount()`, `@EventListener @Order(60) void onTick(TickEvent)`. Package-private seam `String serializeFrame(ObserverFrame.WorldFrame)` for the O2a spy.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/paralife/observer/ObserverBroadcasterTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.engine.TickEvent;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

class ObserverBroadcasterTest {

    private WorldGrid worldGrid;
    private EnvironmentEngine env;
    private BotRegistry botRegistry;
    private SpeciesSpawnCounter spawnCounter;
    private ObserverOutboundSender sender;
    private ObserverBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        worldGrid = new WorldGrid(new GridConfig(16, 16));
        env = mock(EnvironmentEngine.class);
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of()));
        botRegistry = new BotRegistry();
        spawnCounter = new SpeciesSpawnCounter();
        sender = mock(ObserverOutboundSender.class);
        broadcaster = new ObserverBroadcaster(new ObserverFrameBuilder(), worldGrid, env,
                botRegistry, spawnCounter, sender);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void zeroObserversProducesNoOfferAndNoError() {
        broadcaster.onTick(new TickEvent(1)); // must not throw
        verify(sender, times(0)).offer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void offersExactlyOneFrameToEachOpenObserver() {
        broadcaster.register(session("a"));
        broadcaster.register(session("b"));

        broadcaster.onTick(new TickEvent(7));

        verify(sender, times(1)).offer(org.mockito.ArgumentMatchers.eq("a"),
                org.mockito.ArgumentMatchers.anyString());
        verify(sender, times(1)).offer(org.mockito.ArgumentMatchers.eq("b"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void serializesExactlyOnceRegardlessOfObserverCount() {
        ObserverBroadcaster spy = spy(broadcaster);
        spy.register(session("a"));
        spy.register(session("b"));
        spy.register(session("c"));

        spy.onTick(new TickEvent(1));

        verify(spy, times(1)).serializeFrame(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allObserversReceiveTheSameNonEmptyPayload() {
        broadcaster.register(session("a"));
        broadcaster.register(session("b"));

        broadcaster.onTick(new TickEvent(1));

        ArgumentCaptor<String> payloadA = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadB = ArgumentCaptor.forClass(String.class);
        verify(sender).offer(org.mockito.ArgumentMatchers.eq("a"), payloadA.capture());
        verify(sender).offer(org.mockito.ArgumentMatchers.eq("b"), payloadB.capture());
        assertThat(payloadA.getValue()).as("precondition: non-empty payload").isNotEmpty();
        assertThat(payloadA.getValue()).isEqualTo(payloadB.getValue());
    }

    @Test
    void aThrowingCaptureIsContainedAndOnTickNeverEscapes() {
        // positive control: normal onTick with an observer does not throw (asserted above).
        // failure path: a throwing collaborator must be contained so the synchronous tick
        // publish is not aborted (later @Order listeners must still run).
        when(env.snapshot()).thenThrow(new RuntimeException("boom"));
        broadcaster.register(session("a"));

        assertThatCode(() -> broadcaster.onTick(new TickEvent(1)))
                .as("observer capture failure must not escape the tick listener")
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverBroadcasterTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/paralife/observer/ObserverBroadcaster.java`:
```java
package com.paralife.observer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.engine.TickEvent;
import com.paralife.world.WorldGrid;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tick-{@code @Order(60)} listener (after TickBroadcaster @Order(50)). On the tick
 * thread it does BOUNDED work only: capture one immutable grid + env snapshot + owned
 * set + spawn counts, serialize ONCE to JSON, then non-blocking {@code offer} the
 * shared payload to each observer's latest-wins mailbox. It NEVER calls
 * {@code session.sendMessage} here — a blocked socket must not add latency to tick work.
 */
@Component
public class ObserverBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ObserverBroadcaster.class);

    private final Set<WebSocketSession> observers = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObserverFrameBuilder builder;
    private final WorldGrid worldGrid;
    private final EnvironmentEngine environmentEngine;
    private final BotRegistry botRegistry;
    private final SpeciesSpawnCounter spawnCounter;
    private final ObserverOutboundSender sender;

    public ObserverBroadcaster(ObserverFrameBuilder builder, WorldGrid worldGrid,
                               EnvironmentEngine environmentEngine, BotRegistry botRegistry,
                               SpeciesSpawnCounter spawnCounter, ObserverOutboundSender sender) {
        this.builder = builder;
        this.worldGrid = worldGrid;
        this.environmentEngine = environmentEngine;
        this.botRegistry = botRegistry;
        this.spawnCounter = spawnCounter;
        this.sender = sender;
    }

    public void register(WebSocketSession session) {
        observers.add(session);
    }

    public void unregister(WebSocketSession session) {
        observers.remove(session);
    }

    public int observerCount() {
        return observers.size();
    }

    @EventListener
    @Order(60) // after TickBroadcaster(50); bounded on-thread work only
    public void onTick(TickEvent event) {
        if (observers.isEmpty()) return; // cheap early-out
        try {
            WorldGrid.GridSnapshot grid = worldGrid.snapshot();
            EnvironmentSnapshot env = environmentEngine.snapshot();
            Set<String> owned = botRegistry.ownedEntityIds();
            long[] spawns = spawnCounter.snapshot();

            ObserverFrame.WorldFrame frame = builder.buildWorld(event.tickNumber(), grid, env, owned, spawns);
            String payload = serializeFrame(frame);
            if (payload == null) return;

            for (WebSocketSession s : observers) {
                try {
                    sender.offer(s.getId(), payload);
                } catch (RuntimeException e) {
                    // one bad session must not abort the fan-out
                    log.warn("Observer offer failed for session={}: {}", s.getId(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // TickEvent is published SYNCHRONOUSLY on the tick thread (TickEngine.java:114). An
            // exception escaping here would abort publishEvent and skip every later listener for
            // this tick — WebSocketKeepaliveService @Order(200), TickHealthMonitor @Order(MAX).
            // The observer is best-effort: contain any capture/build/serialize failure here.
            log.warn("Observer broadcast failed at tick {} (contained): {}",
                    event.tickNumber(), e.getMessage());
        }
    }

    /** Single serialization seam (O2a): called exactly once per tick regardless of observer count. */
    String serializeFrame(ObserverFrame.WorldFrame frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn("Observer frame serialization failed at tick {}: {}", frame.tick(), e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverBroadcasterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/observer/ObserverBroadcaster.java \
        src/test/java/com/paralife/observer/ObserverBroadcasterTest.java
git commit -m "feat(observer): ObserverBroadcaster — bounded on-thread capture, serialize-once fan-out (O1/O2a/O2b)"
```

---

## Task 8: `ObserverSessionGate` — enablement + cap + release-once lease (O9)

**Files:**
- Create: `src/main/java/com/paralife/observer/ObserverSessionGate.java`
- Create: `src/test/java/com/paralife/observer/ObserverSessionGateTest.java`

**Interfaces:**
- Consumes: `ObserverConfig`.
- Produces: `implements HandshakeInterceptor`; `void releaseIfHeld(WebSocketSession)`; `int availablePermits()`. Constant `ATTR_PERMIT = "observerPermit"`.

- [ ] **Step 1: Write the failing test** (unit — drive the interceptor + release directly, no live server)

`src/test/java/com/paralife/observer/ObserverSessionGateTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class ObserverSessionGateTest {

    private static ObserverSessionGate gate(boolean enabled, int max) {
        return new ObserverSessionGate(new ObserverConfig(enabled, max));
    }

    private static boolean before(ObserverSessionGate g, Map<String, Object> attrs) throws Exception {
        return g.beforeHandshake(mock(ServerHttpRequest.class), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attrs);
    }

    private static WebSocketSession sessionWith(Map<String, Object> handshakeAttrs) {
        WebSocketSession s = mock(WebSocketSession.class);
        // handshake attrs become session attributes
        when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>(handshakeAttrs));
        return s;
    }

    @Test
    void disabledRefusesEveryHandshake() throws Exception {
        ObserverSessionGate g = gate(false, 4);
        assertThat(before(g, new HashMap<>())).as("disabled → refuse").isFalse();
        assertThat(g.availablePermits()).as("no permit consumed when disabled").isEqualTo(4);
    }

    @Test
    void capEnforcedSequentially() {
        ObserverSessionGate g = gate(true, 2);
        assertThat(before(g, new HashMap<>())).isTrue();
        assertThat(before(g, new HashMap<>())).isTrue();
        assertThat(before(g, new HashMap<>())).as("third refused at cap 2").isFalse();
        assertThat(g.availablePermits()).isZero();
    }

    @Test
    void capNeverExceededUnderConcurrentHandshakeStampede() throws Exception {
        // The design's whole reason for a Semaphore is that `size() < max; add()` is a
        // check-then-act race. Fire far more callers than the cap simultaneously behind a
        // barrier and assert EXACTLY maxSessions win. (RED-test by swapping the Semaphore for a
        // plain `if (count < max) count++` — this stampede then admits > maxSessions.)
        int max = 4, callers = 16;
        ObserverSessionGate g = gate(true, max);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        AtomicInteger successes = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(callers);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(); // release all callers at once
                    if (before(g, new HashMap<>())) successes.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(successes.get()).as("exactly maxSessions win the stampede").isEqualTo(max);
        assertThat(g.availablePermits()).as("permits exhausted, never negative").isZero();
    }

    @Test
    void releaseIsExactlyOnceAcrossErrorThenClose() throws Exception {
        ObserverSessionGate g = gate(true, 2);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(before(g, attrs)).isTrue();
        assertThat(g.availablePermits()).isEqualTo(1);

        WebSocketSession s = sessionWith(attrs); // carries ATTR_PERMIT marker
        g.releaseIfHeld(s); // handleTransportError path
        g.releaseIfHeld(s); // afterConnectionClosed path (duplicate)

        assertThat(g.availablePermits())
                .as("both close/error paths fire but the permit releases exactly once")
                .isEqualTo(2);
        assertThat(g.availablePermits())
                .as("cap never inflated above maxSessions").isLessThanOrEqualTo(2);
    }

    @Test
    void normalSingleCloseReleasesExactlyOne() throws Exception {
        ObserverSessionGate g = gate(true, 1);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(before(g, attrs)).isTrue();
        WebSocketSession s = sessionWith(attrs);

        g.releaseIfHeld(s);

        assertThat(g.availablePermits()).as("positive control: one close → one release").isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverSessionGateTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/paralife/observer/ObserverSessionGate.java`:
```java
package com.paralife.observer;

import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Pre-upgrade enablement + concurrency-cap gate for {@code /ws/observer}. Runs as a
 * {@link HandshakeInterceptor} so it can refuse the HTTP handshake (afterConnectionEstablished
 * is post-upgrade and cannot). A {@link Semaphore} makes the cap race-free under
 * concurrent handshakes (a plain size()&lt;max check is check-then-act).
 *
 * <p>Release-once lease (O9): {@code handleTransportError} AND {@code afterConnectionClosed}
 * both fire for one failed connection, so release is guarded by a remove-once marker
 * (the {@code observerPermit} session attribute) — mirroring the bot cleanup's
 * {@code attrs.remove(ATTR_ENTITY_TYPE) != null} gate. This prevents double-release
 * inflating the semaphore above maxSessions.
 */
@Component
public class ObserverSessionGate implements HandshakeInterceptor {

    static final String ATTR_PERMIT = "observerPermit";

    private final ObserverConfig config;
    private final Semaphore permits;

    public ObserverSessionGate(ObserverConfig config) {
        this.config = config;
        this.permits = new Semaphore(config.maxSessions());
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!config.enabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        if (!permits.tryAcquire()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        attributes.put(ATTR_PERMIT, Boolean.TRUE); // becomes a session attribute (remove-once marker)
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // If the upgrade failed after we acquired, no session is established (so no
        // close callback will fire) — release the permit here, exactly once.
        if (exception != null) {
            permits.release();
        }
    }

    /** Release the session's permit exactly once (idempotent across error+close). */
    public void releaseIfHeld(WebSocketSession session) {
        if (session.getAttributes().remove(ATTR_PERMIT) != null) {
            permits.release();
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverSessionGateTest'`
Expected: PASS.

- [ ] **Step 5: RED-test the release-once gate** (prove it fires)

Temporarily change `releaseIfHeld` to release unconditionally:
```java
public void releaseIfHeld(WebSocketSession session) {
    session.getAttributes().remove(ATTR_PERMIT);
    permits.release(); // BROKEN: double-releases
}
```
Run: `./gradlew test --tests 'com.paralife.observer.ObserverSessionGateTest'`
Expected: `releaseIsExactlyOnceAcrossErrorThenClose` FAILS (availablePermits == 3 > cap 2). Restore the guarded version; re-run → PASS. This proves the O9 guard is not vacuous.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/observer/ObserverSessionGate.java \
        src/test/java/com/paralife/observer/ObserverSessionGateTest.java
git commit -m "feat(observer): ObserverSessionGate — enablement + atomic cap + release-once lease (O9)"
```

---

## Task 9: `ObserverWebSocketHandler` + registration + deflate exemption (O6a/b/c, C1)

**Files:**
- Create: `src/main/java/com/paralife/observer/ObserverWebSocketHandler.java`
- Modify: `src/main/java/com/paralife/websocket/WebSocketConfig.java`
- Modify: `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java` (exempt `/ws/observer`)
- Create: `src/test/java/com/paralife/observer/ObserverEndpointIntegrationTest.java`

**Interfaces:**
- Consumes: `ObserverBroadcaster`, `ObserverOutboundSender`, `ObserverSessionGate`, `ObserverFrameBuilder`, `WorldGrid`.
- Produces: `/ws/observer` route; a browser-equivalent handshake succeeds; no entity/slot/registry mutation on observer connect.

- [ ] **Step 1: Write the failing integration test** (real server, browser-equivalent offer — guards C1)

`src/test/java/com/paralife/observer/ObserverEndpointIntegrationTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.admission.AdmissionGate;
import com.paralife.codec.Frame;
import com.paralife.codec.PerceptionCodec;
import com.paralife.engine.BotRegistry;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.WorldGrid;
import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * O6a/O6b/O6c + C1, with the /ws/world positive control CO-LOCATED (same Spring context,
 * same autowired counters). C1: the observer upgrade uses a BROWSER-EQUIVALENT offer (plain
 * permessage-deflate, WITHOUT server_no_context_takeover — a browser cannot send that param),
 * proving /ws/observer is exempt from the deflate-enforcement filter. Frames are parsed with
 * Jackson (not substring-matched), asserting the real wire contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "paralife.tick.interval-ms=100",
        "paralife.tick.auto-start=true",   // ticks fire → observer still gets world frames
        // Freeze the world so entityCount/slot/registry deltas are deterministic: sim OFF stops
        // nutrient spawning + decay/death (SimulationEngine early-returns on !enabled), events OFF
        // stops env. Registration/placement is in the handler, independent of these — the /ws/world
        // control still places its bot. Without this, a probabilistic nutrient spawn between the
        // before/after capture would fail O6a (entityCount counts nutrients).
        "paralife.simulation.enabled=false",
        "paralife.simulation.events.enabled=false",
        "paralife.world.width=16",
        "paralife.world.height=16",
        "paralife.observer.enabled=true",
        "paralife.observer.max-sessions=4"
})
class ObserverEndpointIntegrationTest {

    @LocalServerPort int port;
    @Autowired AdmissionGate admissionGate;
    @Autowired BotRegistry botRegistry;
    @Autowired WorldGrid worldGrid;
    @Autowired SpeciesSpawnCounter spawnCounter;

    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocketClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.stop();
    }

    @WebSocket
    public static class Capture {
        final CopyOnWriteArrayList<String> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;
        Capture(CountDownLatch latch) { this.latch = latch; }
        @OnWebSocketMessage
        public void onMessage(String msg) { frames.add(msg); latch.countDown(); }
    }

    /** Bot-side capture: decodes wire frames so we can await the SyncFrame. */
    @WebSocket
    public static class BotCapture {
        final CopyOnWriteArrayList<Frame> frames = new CopyOnWriteArrayList<>();
        @OnWebSocketMessage
        public void onMessage(String msg) {
            try { frames.add(PerceptionCodec.decode(msg)); } catch (Exception ignored) { }
        }
    }

    private Session connect(Object endpoint, String path, String extensions) throws Exception {
        client = new WebSocketClient();
        client.start();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        req.addExtensions(extensions);
        return client.connect(endpoint, URI.create("ws://localhost:" + port + path), req)
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void observerBrowserOffer_getsBootstrapThenWorld_mutatesNoGridSlotOrRegistry() throws Exception {
        int slotsBefore = admissionGate.reservedSlots();
        int botsBefore = botRegistry.size();
        int occupantsBefore = worldGrid.snapshot().entityCount();

        CountDownLatch got2 = new CountDownLatch(2); // bootstrap + ≥1 world frame
        Capture cap = new Capture(got2);
        // browser-equivalent offer: NO server_no_context_takeover (guards C1)
        Session session = connect(cap, "/ws/observer", "permessage-deflate");
        assertThat(session.isOpen()).as("browser-equivalent handshake succeeded (C1 exemption)").isTrue();
        assertThat(got2.await(5, TimeUnit.SECONDS)).as("bootstrap + a world frame arrived").isTrue();

        // Jackson-parse the real contract, not substrings
        JsonNode bootstrap = mapper.readTree(cap.frames.get(0));
        assertThat(bootstrap.get("type").asText()).as("bootstrap first").isEqualTo("bootstrap");
        assertThat(bootstrap.get("grid").get("width").asInt()).isEqualTo(16);
        JsonNode world = null;
        for (String f : cap.frames) {
            JsonNode n = mapper.readTree(f);
            if ("world".equals(n.get("type").asText())) { world = n; break; }
        }
        assertThat(world).as("a world frame followed").isNotNull();
        assertThat(world.has("entities") && world.has("populations")).isTrue();

        // O6a/O6b/O6c: observer created no grid occupant, consumed no slot, added no registry entry
        assertThat(worldGrid.snapshot().entityCount())
                .as("O6a: observer placed no entity on the grid").isEqualTo(occupantsBefore);
        assertThat(admissionGate.reservedSlots())
                .as("O6b: observer consumed no admission slot").isEqualTo(slotsBefore);
        assertThat(botRegistry.size())
                .as("O6c: observer added no BotRegistry entry").isEqualTo(botsBefore);

        session.close(1000, "done", Callback.NOOP);
    }

    @Test
    void worldRegistrationControl_movesSlotRegistryGridAndSpawnCounter() throws Exception {
        // POSITIVE CONTROL proving the O6 gates are live, not inert: a real /ws/world admission
        // moves every counter the observer test asserts unchanged. Also covers O4's admission
        // creation path (spawns[CATALYST] += 1). Bot offer includes server_no_context_takeover.
        int slotsBefore = admissionGate.reservedSlots();
        int botsBefore = botRegistry.size();
        int occupantsBefore = worldGrid.snapshot().entityCount();
        long catBefore = spawnCounter.get(ParticleType.CATALYST);

        BotCapture cap = new BotCapture();
        Session bot = connect(cap, "/ws/world", "permessage-deflate; server_no_context_takeover");
        bot.sendText(PerceptionCodec.encode(new Frame.RegisterFrame('C')), Callback.NOOP);

        // admission resolves synchronously on the register frame → await the SyncFrame
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cap.frames.stream().noneMatch(f -> f instanceof Frame.SyncFrame)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(cap.frames).anyMatch(f -> f instanceof Frame.SyncFrame);

        assertThat(admissionGate.reservedSlots() - slotsBefore).as("+1 admission slot").isEqualTo(1);
        assertThat(botRegistry.size() - botsBefore).as("+1 BotRegistry entry").isEqualTo(1);
        assertThat(worldGrid.snapshot().entityCount() - occupantsBefore).as("+1 grid occupant").isEqualTo(1);
        assertThat(spawnCounter.get(ParticleType.CATALYST) - catBefore)
                .as("O4 admission path: +1 committed CATALYST spawn").isEqualTo(1L);

        bot.close(1000, "done", Callback.NOOP);
    }
}
```

> **RED-test each conjunct (Step 6a):** independently break each guarded line and confirm the matching assertion fires — e.g. remove the deflate-filter exemption → the observer handshake 400s (test A fails at connect); make the observer handler place an entity → O6a fails; comment out the admission-site spawn increment (Task 3) → the control's `spawns[CATALYST]` delta drops to 0. Restore each; re-run green. The two methods are mutually the positive/negative controls: A proves the observer moves nothing, B proves the same counters DO move on a real bot.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverEndpointIntegrationTest'`
Expected: FAIL — `/ws/observer` returns HTTP 400 (deflate filter) or 404 (route absent); handshake `.get()` throws.

- [ ] **Step 3: Exempt `/ws/observer` from the deflate filter (C1)**

In `JettyDeflateCustomizer.java`, inside `DeflateEnforcementFilter.doFilter`, add an exempt-path check immediately after the `isWebSocketUpgrade` short-circuit (before the extensions check):
```java
if (!isWebSocketUpgrade(httpReq)) {
    chain.doFilter(request, response);
    return;
}
// C1: observers are browser-facing; browsers cannot advertise server_no_context_takeover.
String uri = httpReq.getRequestURI();
if (uri != null && uri.startsWith("/ws/observer")) {
    chain.doFilter(request, response);
    return;
}
```

- [ ] **Step 4: Write the handler**

`src/main/java/com/paralife/observer/ObserverWebSocketHandler.java`:
```java
package com.paralife.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.world.WorldGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Read-only {@code /ws/observer} handler. No admission FSM, no vision-scoping, no
 * resume/stall. On open it follows the bootstrap-barrier: attach the outbound sender
 * → send the bootstrap frame under {@code synchronized(session)} → only THEN register
 * with the broadcaster (so no world frame can precede or overwrite the bootstrap).
 * Inbound frames are ignored (AbstractWebSocketHandler defaults are no-ops).
 */
@Component
public class ObserverWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ObserverWebSocketHandler.class);

    private final ObserverBroadcaster broadcaster;
    private final ObserverOutboundSender sender;
    private final ObserverSessionGate gate;
    private final ObserverFrameBuilder builder;
    private final WorldGrid worldGrid;
    private final ObjectMapper mapper = new ObjectMapper();

    public ObserverWebSocketHandler(ObserverBroadcaster broadcaster, ObserverOutboundSender sender,
                                    ObserverSessionGate gate, ObserverFrameBuilder builder,
                                    WorldGrid worldGrid) {
        this.broadcaster = broadcaster;
        this.sender = sender;
        this.gate = gate;
        this.builder = builder;
        this.worldGrid = worldGrid;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sender.attach(session);
        // Bootstrap-barrier: send static terrain BEFORE the broadcaster can offer a world frame.
        ObserverFrame.BootstrapFrame boot = builder.buildBootstrap(worldGrid.snapshot());
        String payload = mapper.writeValueAsString(boot);
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
        broadcaster.register(session); // now eligible for world frames
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Observer transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanup(session);
    }

    private void cleanup(WebSocketSession session) {
        broadcaster.unregister(session);
        sender.detach(session);
        gate.releaseIfHeld(session); // release-once (O9)
    }
}
```

- [ ] **Step 5: Register `/ws/observer` in `WebSocketConfig`**

Rewrite `WebSocketConfig.java` to inject and register the observer handler + interceptor:
```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorldWebSocketHandler worldWebSocketHandler;
    private final ObserverWebSocketHandler observerWebSocketHandler;
    private final ObserverSessionGate observerSessionGate;
    private final JettyRequestUpgradeStrategy upgradeStrategy;

    public WebSocketConfig(WorldWebSocketHandler worldWebSocketHandler,
                           ObserverWebSocketHandler observerWebSocketHandler,
                           ObserverSessionGate observerSessionGate,
                           JettyRequestUpgradeStrategy upgradeStrategy) {
        this.worldWebSocketHandler = worldWebSocketHandler;
        this.observerWebSocketHandler = observerWebSocketHandler;
        this.observerSessionGate = observerSessionGate;
        this.upgradeStrategy = upgradeStrategy;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(worldWebSocketHandler, "/ws/world")
                .setHandshakeHandler(new DefaultHandshakeHandler(upgradeStrategy))
                .setAllowedOrigins("*"); // bots
        registry.addHandler(observerWebSocketHandler, "/ws/observer")
                .setHandshakeHandler(new DefaultHandshakeHandler(upgradeStrategy))
                .addInterceptors(observerSessionGate)
                .setAllowedOrigins("*"); // browser observers (read-only; enablement/cap gate applies)
    }
}
```
(Add imports: `com.paralife.observer.ObserverWebSocketHandler`, `com.paralife.observer.ObserverSessionGate`.)

- [ ] **Step 6: Run the integration test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverEndpointIntegrationTest'`
Expected: PASS — handshake succeeds with the browser-equivalent offer; bootstrap + world frames arrive; slots/registry unchanged.

- [ ] **Step 7: Run the full websocket suite (no route/filter regressions)**

Run: `./gradlew test --tests 'com.paralife.websocket.*'`
Expected: PASS — `WebSocketRouteAssertion` still confirms a single `/ws/world` handler; existing deflate-enforcement tests still reject non-deflate `/ws/world` upgrades.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessCheck
git add src/main/java/com/paralife/observer/ObserverWebSocketHandler.java \
        src/main/java/com/paralife/websocket/WebSocketConfig.java \
        src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java \
        src/test/java/com/paralife/observer/ObserverEndpointIntegrationTest.java
git commit -m "feat(observer): /ws/observer handler + registration + deflate exemption (O6/C1)"
```

---

## Task 10: Bootstrap-barrier ordering test (O8)

Task 9 already implements the attach→bootstrap→register order. This task pins the **happens-before invariant** that makes the barrier real: the bootstrap is **sent** before the observer is **registered** with the broadcaster (registration is what makes it eligible for world frames). We record every session send *and* the `register()` call into one ordered list and assert `send:bootstrap` precedes `register` — so no world frame can even be offered, let alone delivered, before bootstrap. This is deterministic (no flaky real-thread race) and — crucially — **fails under the reorder mutation**, which the earlier "call the method then fire a tick" version did not (the tick fired after the method already completed, so a reorder inside it couldn't be observed).

**Files:**
- Create: `src/test/java/com/paralife/observer/ObserverBootstrapOrderingTest.java`

- [ ] **Step 1: Write the failing test** (deterministic ordering pin: bootstrap-send happens-before register)

`src/test/java/com/paralife/observer/ObserverBootstrapOrderingTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.paralife.engine.BotRegistry;
import com.paralife.engine.EnvironmentEngine;
import com.paralife.engine.EnvironmentSnapshot;
import com.paralife.engine.SpeciesSpawnCounter;
import com.paralife.engine.TickEvent;
import com.paralife.world.GridConfig;
import com.paralife.world.WorldGrid;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * O8 bootstrap-barrier. The invariant is a happens-before: the bootstrap is SENT before the
 * observer is REGISTERED with the broadcaster (registration is what makes it eligible for world
 * frames). We record every session send and the register() call into one ordered list and assert
 * bootstrap-send precedes register — so no world frame can be offered, let alone delivered, before
 * bootstrap. Bootstrap is sent directly under synchronized(session), never via the latest-wins
 * slot, so a concurrent tick cannot overwrite it. Deterministic — no flaky real-thread race, and
 * (unlike a "call the method then fire a tick" test) it actually fails when register is reordered
 * before the bootstrap send.
 */
class ObserverBootstrapOrderingTest {

    private static String sendType(WebSocketMessage<?> m) {
        String p = ((TextMessage) m).getPayload();
        return p.contains("\"type\":\"bootstrap\"") ? "send:bootstrap"
             : p.contains("\"type\":\"world\"") ? "send:world" : "send:other";
    }

    @Test
    void bootstrapIsSentBeforeTheObserverIsRegistered_andWorldNeverPrecedesIt() throws Exception {
        WorldGrid grid = new WorldGrid(new GridConfig(16, 16));
        EnvironmentEngine env = mock(EnvironmentEngine.class);
        when(env.snapshot()).thenReturn(new EnvironmentSnapshot(List.of(), List.of(), List.of()));
        ObserverFrameBuilder builder = new ObserverFrameBuilder();
        ObserverOutboundSender sender = new ObserverOutboundSender();
        ObserverBroadcaster broadcaster = spy(new ObserverBroadcaster(builder, grid, env,
                new BotRegistry(), new SpeciesSpawnCounter(), sender));
        ObserverWebSocketHandler handler = new ObserverWebSocketHandler(
                broadcaster, sender, new ObserverSessionGate(new ObserverConfig(true, 4)),
                builder, grid);

        List<String> events = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("obs");
        when(session.isOpen()).thenReturn(true);
        doAnswer(inv -> { events.add(sendType(inv.getArgument(0))); return null; })
                .when(session).sendMessage(org.mockito.ArgumentMatchers.any());
        // record the register() call in the SAME ordered list, then run the real registration
        doAnswer(inv -> { events.add("register"); return inv.callRealMethod(); })
                .when(broadcaster).register(org.mockito.ArgumentMatchers.any());

        handler.afterConnectionEstablished(session);

        assertThat(events)
                .as("barrier order: bootstrap is SENT, THEN the observer is registered")
                .containsExactly("send:bootstrap", "register");

        // end-to-end: only after registration does a tick's world frame reach the wire (second)
        broadcaster.onTick(new TickEvent(1));
        Thread.sleep(200); // let the drain VT flush the world frame
        assertThat(events)
                .as("world frame follows — never precedes — bootstrap")
                .containsExactly("send:bootstrap", "register", "send:world");

        sender.detach("obs");
    }
}
```

- [ ] **Step 2: Run test to verify it passes** (Task 9 implements attach→bootstrap→register)

Run: `./gradlew test --tests 'com.paralife.observer.ObserverBootstrapOrderingTest'`
Expected: PASS.

- [ ] **Step 3: RED-test the barrier** (prove the test is not vacuous — this is the fix for the old vacuous version)

In `ObserverWebSocketHandler.afterConnectionEstablished`, temporarily move `broadcaster.register(session)` **before** the bootstrap send block. Re-run: the first assertion must now FAIL with `events == ["register", "send:bootstrap"]` — the reorder is deterministically caught. Restore the correct order; re-run → PASS. (The prior design fired the tick *after* the method returned, so this reorder went undetected — that is precisely the defect this redesign fixes.)

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessCheck
git add src/test/java/com/paralife/observer/ObserverBootstrapOrderingTest.java
git commit -m "test(observer): pin bootstrap-barrier happens-before (O8), deterministic RED"
```

- [ ] **Step 5: Full-suite gate** (shared-JVM leak check — mandatory before slice close)

Run: `./gradlew test`
Expected: PASS, full green. This is the `forkEvery=0` shared-JVM run — the integration test opens a real observer session; confirm teardown (`session.close` + `@AfterEach client.stop()`) leaves no leaked VT/session. If any unrelated integration test times out, the observer session/VT is leaking — verify `handler.cleanup` runs on close and `ObserverOutboundSender.detach` interrupts the drain VT.

---

## Task 11: Worst-case frame-budget scale gate (GO-on-scale, Assumption 1)

The tick-thread work is bounded, but capture + single Jackson encode still run **on the tick thread**. The spec (Assumption 1) mandates *measuring* worst-case capture+encode against the **400 ms** tick-overload watermark before claiming scale-readiness. This is a real, executable measurement — not a note. It is `@Tag("slow")` (machine-sensitive timing must not gate CI; run via `-PincludeLong=true`). The number it **logs** is the artifact; the assertion is a soft ceiling that forces the decision.

**Files:**
- Create: `src/test/java/com/paralife/engine/ObserverFrameBudgetScaleTest.java` (in `com.paralife.engine` to reach the package-private env stamp helpers)

- [ ] **Step 1: Write the measurement**

`src/test/java/com/paralife/engine/ObserverFrameBudgetScaleTest.java`:
```java
package com.paralife.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paralife.observer.ObserverFrameBuilder;
import com.paralife.world.Entity.Particle;
import com.paralife.world.Entity.ParticleType;
import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GO-on-scale gate (spec Assumption 1). Measures the FULL worst-case ON-THREAD path a real tick
 * runs — WorldGrid.snapshot() + EnvironmentEngine.snapshot() (both-layers-saturated full-grid scan
 * + ~131 072 EnvCell allocations) + buildWorld + Jackson serialize — on a 256x256 grid densely
 * occupied and both env layers saturated. Lives in com.paralife.engine to reach the package-private
 * stamp helpers; calling the REAL env.snapshot() inside the timed loop is deliberate — an
 * EnvironmentSnapshot pre-built outside the loop would exclude the production scan+alloc the spec
 * counts (Assumption 1). NOT default-suite (@Tag("slow"), run via -PincludeLong=true) —
 * machine-sensitive. The logged best-of-5 is the artifact; the assertion is a soft ceiling forcing
 * the decision: under 400 ms → on-thread encode ships; over → implement the capacity-1 encoder-VT
 * fallback (Assumption 1) before scale-readiness.
 */
@Tag("slow")
class ObserverFrameBudgetScaleTest {

    private static final int DIM = 256;
    private static final long WATERMARK_MS = 400;

    /** Real EnvironmentEngine with BOTH shadow grids saturated (mirrors the Task 4 wiring). */
    private static EnvironmentEngine saturatedEngine(WorldGrid grid) {
        EnvironmentConfig d = EnvironmentConfig.defaults();
        EnvironmentConfig cfg = new EnvironmentConfig(false, 42L, d.lightning(), d.toxin(), d.mutagen(), d.compost());
        BuffRegistry buffs = new BuffRegistry();
        EnvCleanupHooksBean hooks = new EnvCleanupHooksBean();
        DeathFinalizer finalizer = new DeathFinalizer(
                grid, new BotRegistry(), buffs, mock(CompositeRegistry.class), hooks, mock(SimulationEngine.class));
        EnvironmentEngine env = new EnvironmentEngine(grid,
                new SeasonTracker(new SeasonsConfig(200, 0.5)),
                cfg, buffs, FertilityConfig.defaults(), finalizer, hooks,
                (ToxinPathGenerator) null, new Random(42L));
        hooks.registerCompostSink(env::applyCompost);
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                env.stampToxinIntensityForTest(new Position(x, y), 200); // saturate both layers
                env.stampMutagenForTest(new Position(x, y), 7);
            }
        }
        return env;
    }

    @Test
    void worstCaseCaptureAndEncodeUnderTickWatermark() throws Exception {
        WorldGrid grid = new WorldGrid(new GridConfig(DIM, DIM));
        int i = 0;
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                grid.setEntity(x, y,
                        new Particle("p" + (i++), ParticleType.values()[(x + y) % 3], 50, 100));
            }
        }
        EnvironmentEngine env = saturatedEngine(grid);
        ObserverFrameBuilder builder = new ObserverFrameBuilder();
        ObjectMapper mapper = new ObjectMapper();

        for (int w = 0; w < 3; w++) encodeOnce(builder, mapper, grid, env); // JIT warmup
        long bestNs = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) bestNs = Math.min(bestNs, encodeOnce(builder, mapper, grid, env));
        long ms = bestNs / 1_000_000;
        System.out.println("[scale-gate] worst-case snapshot+capture+encode best-of-5 = " + ms
                + " ms (watermark " + WATERMARK_MS + " ms)");

        assertThat(ms)
                .as("worst-case capture+encode must stay under the 400ms tick watermark, else "
                        + "implement the capacity-1 encoder-VT fallback (spec Assumption 1)")
                .isLessThan(WATERMARK_MS);
    }

    /** Times the ENTIRE on-thread path: BOTH snapshots (incl. the real env scan) + build + serialize. */
    private static long encodeOnce(ObserverFrameBuilder builder, ObjectMapper mapper,
                                   WorldGrid grid, EnvironmentEngine env) throws Exception {
        long t0 = System.nanoTime();
        var frame = builder.buildWorld(1L, grid.snapshot(), env.snapshot(), Set.of(), new long[] {0, 0, 0});
        mapper.writeValueAsString(frame);
        return System.nanoTime() - t0;
    }
}
```

- [ ] **Step 2: Run the gate and record the number**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverFrameBudgetScaleTest' -PincludeLong=true`
Record the logged `[scale-gate] … = N ms` in the PR. **Decision:** `N < 400` → the MVP on-thread encode is GO-on-scale; `N ≥ 400` → do **not** claim scale-readiness — implement the capacity-1 encoder-VT fallback (snapshot handed off unencoded, encoded once off-thread) per spec Assumption 1, then re-measure.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessCheck
git add src/test/java/com/paralife/engine/ObserverFrameBudgetScaleTest.java
git commit -m "test(observer): worst-case frame-budget scale gate (Assumption 1, @slow)"
```

---

# SLICE 2 — UI + DOCS

## Task 12: `observer.html` — canvas render + WS client

The render is judged **by eye** (spec) — no pixel test. The task deliverable is a working static page that connects, parses both frame types, and repaints. The **automated** frame-contract coverage lives in `ObserverEndpointIntegrationTest` (Task 9), which completes the real handshake and **Jackson-parses** the bootstrap + world frames. The page test below is a **serves + scaffolding-presence** check only (explicitly *not* a JS-execution smoke — see the backlog note); a headless-browser JS smoke is deferred to BACKLOG (Task 13).

**Files:**
- Create: `src/main/resources/static/observer.html`
- Create: `src/test/java/com/paralife/observer/ObserverPageServesTest.java`

**Interfaces:**
- Consumes: the wire contract from Task 5 (bootstrap `{type,schemaVersion,grid,rocks}`; world `{type,tick,entities[],env{toxin,mutagen,lightning},scoreboard,populations}`).

- [ ] **Step 1: Write the failing test** (page is served as static content)

`src/test/java/com/paralife/observer/ObserverPageServesTest.java`:
```java
package com.paralife.observer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * The observer page ships as static content in the app (no build pipeline). This is
 * a serves-check only; render fidelity is judged by eye per the spec. NOTE a serves
 * check alone would pass with broken JS — the end-to-end handshake + parse is covered
 * by ObserverEndpointIntegrationTest (real frames) — so this asserts the canvas +
 * WS-client scaffolding is present, not just HTTP 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObserverPageServesTest {

    @Autowired TestRestTemplate rest;

    @Test
    void observerHtmlIsServedWithCanvasAndWsClient() {
        ResponseEntity<String> resp = rest.getForEntity("/observer.html", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).as("has a grid canvas").contains("<canvas");
        assertThat(body).as("connects to the observer endpoint").contains("/ws/observer");
        assertThat(body).as("handles both frame types").contains("bootstrap").contains("world");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverPageServesTest'`
Expected: FAIL — 404, page absent.

- [ ] **Step 3: Write the page**

`src/main/resources/static/observer.html`:
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Paralife — Observer</title>
  <style>
    body { margin: 0; background: #0b0b0f; color: #ddd; font: 13px monospace; }
    #wrap { display: flex; gap: 12px; padding: 12px; }
    #left { display: flex; flex-direction: column; gap: 8px; }
    canvas { background: #000; image-rendering: pixelated; }
    #side { display: flex; flex-direction: column; gap: 12px; min-width: 220px; }
    .panel { border: 1px solid #333; padding: 8px; }
    .panel h3 { margin: 0 0 6px; font-size: 12px; color: #9ad; }
    #knobs { flex: 1; color: #555; } /* reserved for future tuning dials */
    #status { color: #7c7; }
    table { border-collapse: collapse; } td { padding: 1px 6px; }
  </style>
</head>
<body>
  <div id="wrap">
    <div id="left">
      <div id="status">connecting…</div>
      <canvas id="grid" width="512" height="512"></canvas>
      <canvas id="series" width="512" height="120"></canvas>
    </div>
    <div id="side">
      <div class="panel"><h3>Scoreboard (cumulative spawns)</h3><div id="scoreboard"></div></div>
      <div class="panel"><h3>Populations</h3><div id="populations"></div></div>
      <div class="panel" id="knobs"><h3>Controls</h3><div>(reserved)</div></div>
    </div>
  </div>
  <script>
    // ── config ──────────────────────────────────────────────────────────
    const SPECIES_COLOR = { CATALYST: "#e34", MEMBRANE: "#3d8", SPORE: "#59f" };
    const SERIES_COLOR  = SPECIES_COLOR;
    const grid = document.getElementById("grid");
    const gx = grid.getContext("2d");
    const series = document.getElementById("series");
    const sx = series.getContext("2d");
    const statusEl = document.getElementById("status");

    let dims = { width: 256, height: 256 };
    let rocks = [];
    let cell = 2;                 // px per cell, recomputed on bootstrap
    const history = { CATALYST: [], MEMBRANE: [], SPORE: [] };
    const HISTORY_MAX = 512;

    // ── connection ──────────────────────────────────────────────────────
    // Derive scheme from the page origin: wss:// under HTTPS (a hardcoded ws://
    // is blocked as mixed content on an HTTPS-served page).
    const wsScheme = location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(`${wsScheme}//${location.host}/ws/observer`);
    ws.onopen  = () => { statusEl.textContent = "connected"; };
    ws.onclose = () => { statusEl.textContent = "disconnected"; };
    ws.onerror = () => { statusEl.textContent = "error"; };
    ws.onmessage = (ev) => {
      const frame = JSON.parse(ev.data);
      if (frame.type === "bootstrap") onBootstrap(frame);
      else if (frame.type === "world") onWorld(frame);
    };

    function onBootstrap(f) {
      dims = f.grid;
      rocks = f.rocks || [];
      cell = Math.max(1, Math.floor(grid.width / dims.width));
      grid.width = dims.width * cell;
      grid.height = dims.height * cell;
    }

    // ── render ──────────────────────────────────────────────────────────
    function px(v) { return v * cell; }

    function onWorld(f) {
      statusEl.textContent = "tick " + f.tick;
      gx.fillStyle = "#000";
      gx.fillRect(0, 0, grid.width, grid.height);

      // env layers first (under entities)
      drawToxin(f.env.toxin || []);
      drawMutagen(f.env.mutagen || []);

      // rocks (static, from bootstrap)
      gx.fillStyle = "#555";
      for (const r of rocks) gx.fillRect(px(r.x), px(r.y), cell, cell);

      // entities
      for (const e of f.entities) drawEntity(e);

      // lightning flashes (this tick only)
      gx.fillStyle = "rgba(255,255,180,0.9)";
      for (const s of (f.env.lightning || [])) gx.fillRect(px(s.x), px(s.y), cell, cell);

      updatePanels(f);
      pushHistory(f.populations);
      drawSeries();
    }

    function drawToxin(cells) {
      for (const c of cells) {
        const a = Math.min(1, c.intensity / 255); // heat gradient — intensity IS a magnitude
        gx.fillStyle = `rgba(200,60,60,${0.15 + 0.6 * a})`;
        gx.fillRect(px(c.x), px(c.y), cell, cell);
      }
    }
    function drawMutagen(cells) {
      // categorical zones keyed by strain id — NEVER a heat ramp (strain is an id)
      for (const c of cells) {
        const hue = (c.strain * 47) % 360;
        gx.fillStyle = `hsla(${hue},70%,45%,0.4)`;
        gx.fillRect(px(c.x), px(c.y), cell, cell);
      }
    }

    function drawEntity(e) {
      if (e.kind === "nutrient") { gx.fillStyle = "#7a5"; dot(e); return; }
      if (e.kind === "bondedPair") {
        gx.fillStyle = SPECIES_COLOR[e.primarySpecies] || "#aaa"; block(e);
        gx.strokeStyle = SPECIES_COLOR[e.secondarySpecies] || "#aaa";
        gx.strokeRect(px(e.x) + 0.5, px(e.y) + 0.5, cell - 1, cell - 1);
        return;
      }
      if (e.kind === "compositeMember") {
        // Distinct from a solid particle: species-colour fill + a compositeId-keyed
        // outline (stable hue per composite so members of one organism read as a group)
        // + a small role initial. Never a plain block (that would alias a brained particle).
        gx.fillStyle = SPECIES_COLOR[e.species] || "#aaa"; block(e);
        gx.strokeStyle = `hsl(${compositeHue(e.compositeId)},90%,70%)`;
        gx.lineWidth = 1;
        gx.strokeRect(px(e.x) + 0.5, px(e.y) + 0.5, cell - 1, cell - 1);
        if (cell >= 8 && e.role) {
          gx.fillStyle = "#000";
          gx.fillText(e.role[0], px(e.x) + 1, px(e.y) + cell - 1);
        }
        return;
      }
      // particle: brained = solid block, flower (unbrained) = hollow marker
      gx.fillStyle = SPECIES_COLOR[e.species] || "#aaa";
      if (e.brained) block(e);
      else { gx.strokeStyle = gx.fillStyle; gx.strokeRect(px(e.x) + 0.5, px(e.y) + 0.5, cell - 1, cell - 1); }
    }
    function block(e) { gx.fillRect(px(e.x), px(e.y), cell, cell); }
    function dot(e) { gx.fillRect(px(e.x) + cell / 4, px(e.y) + cell / 4, Math.max(1, cell / 2), Math.max(1, cell / 2)); }
    // Stable hue per compositeId so all members of one organism share an outline colour.
    function compositeHue(id) {
      let h = 0; for (let i = 0; i < (id || "").length; i++) h = (h * 31 + id.charCodeAt(i)) % 360;
      return h;
    }

    function updatePanels(f) {
      document.getElementById("scoreboard").innerHTML = table(f.scoreboard);
      document.getElementById("populations").innerHTML = table(f.populations);
    }
    function table(obj) {
      return "<table>" + Object.keys(obj).map(k =>
        `<tr><td style="color:${SPECIES_COLOR[k] || "#ddd"}">${k}</td><td>${obj[k]}</td></tr>`
      ).join("") + "</table>";
    }

    function pushHistory(pops) {
      for (const k of Object.keys(history)) {
        history[k].push(pops[k] || 0);
        if (history[k].length > HISTORY_MAX) history[k].shift();
      }
    }
    function drawSeries() {
      sx.fillStyle = "#000"; sx.fillRect(0, 0, series.width, series.height);
      let max = 1;
      for (const k of Object.keys(history)) for (const v of history[k]) max = Math.max(max, v);
      for (const k of Object.keys(history)) {
        const data = history[k];
        sx.strokeStyle = SERIES_COLOR[k]; sx.beginPath();
        for (let i = 0; i < data.length; i++) {
          const xx = (i / HISTORY_MAX) * series.width;
          const yy = series.height - (data[i] / max) * (series.height - 4);
          if (i === 0) sx.moveTo(xx, yy); else sx.lineTo(xx, yy);
        }
        sx.stroke();
      }
    }
  </script>
</body>
</html>
```

- [ ] **Step 4: Run the serves test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverPageServesTest'`
Expected: PASS.

- [ ] **Step 5: Manual eye check** (render fidelity is judged by eye per the spec)

```bash
./gradlew bootRun
```
With `paralife.observer.enabled=true` (set it temporarily in `application.yml` or pass `--paralife.observer.enabled=true`), open `http://localhost:8080/observer.html`. Confirm: species cells appear in three colours; **bonded pairs, composite members, and a brained particle of the same species each read distinctly** (composite members carry a compositeId-keyed outline + role initial, so a composite member and a plain brained particle of the same species are NOT confusable — the specific aliasing the review flagged); toxin renders as a heat gradient while mutagen renders as flat categorical zones; the time-series accumulates; and the scoreboard/populations panels update. Revert `enabled` to `false` before committing.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessCheck
git add src/main/resources/static/observer.html \
        src/test/java/com/paralife/observer/ObserverPageServesTest.java
git commit -m "feat(observer): static observer.html — canvas render + WS client + time-series"
```

---

## Task 13: Merge-back docs (close-out gate)

A slice isn't done until the living docs match shipped code.

**Files:**
- Modify: `docs/SCHEMA.md` (add an Observer frame section — bootstrap + world frame JSON, both frame types, census rule, subtype fields, env intensity-vs-strain)
- Modify: `docs/ARCHITECTURE.md` (tick pipeline: add `ObserverBroadcaster @Order(60)` row; note the new `com.paralife.observer` package + off-thread delivery)
- Modify: `BACKLOG.md` (add the named "Observer exposure hardening" slice: auth/authz, non-wildcard origin, handshake rate-limiting — prerequisite for public deployment)
- Modify: `CLAUDE.md` (Architecture §packages: add `observer` to the package list; add the `ObserverBroadcaster @Order(60)` step to the tick pipeline)

- [ ] **Step 1: Update `docs/SCHEMA.md`** — add an "Observer frame (`/ws/observer`)" section documenting: the two frame types (`bootstrap`, `world`), the exact JSON from the spec (Task 5 DTOs), the census rule (particle +1; bondedPair +1 both species; compositeMember +1; rock/nutrient excluded; no liveness filter), the subtype fields, and that `env.toxin.intensity` is a magnitude while `env.mutagen.strain` is a categorical id. State `schemaVersion=1`.

- [ ] **Step 2: Update `docs/ARCHITECTURE.md`** — in the tick-pipeline list, add: `ObserverBroadcaster @Order(60) — bounded snapshot + serialize-once + non-blocking offer to observer mailboxes (off-thread delivery via ObserverOutboundSender drain VTs)`. Add a short subsection describing the `com.paralife.observer` package and why delivery is off-thread (C2).

- [ ] **Step 3: Update `BACKLOG.md`** — add:
```markdown
### Observer exposure hardening (prerequisite for public deployment)

The M5-A observer visualiser (`/ws/observer`) ships `enabled=false` + a session cap
only. Before ANY authenticated/public exposure: real auth/authz, non-wildcard origin
policy, and handshake rate-limiting. Until then the endpoint exposes full-world state
(which the bot path deliberately vision-scopes) and must stay operator-only.

### Observer UI headless-browser JS smoke

`observer.html` render fidelity is judged by eye (the stack has no browser-test harness).
The frame contract is covered automatically by `ObserverEndpointIntegrationTest` (real
handshake + Jackson parse), but the page's own JS (`JSON.parse` → canvas render → `#status`
tick signal) is not executed by any test. When a headless-browser harness is justified
(htmlunit for JVM-only, or Playwright for real canvas), add a smoke that loads the page,
completes the observer handshake, and asserts `#status` shows a tick — RED-tested with a
deliberate JS error. Deferred per the M5-A review (2026-07-19); not blocking MVP.
```

- [ ] **Step 4: Update `CLAUDE.md`** — add `observer` to the package-structure list (`com.paralife.{...,observer}` — "read-only visualiser endpoint, broadcaster, off-thread sender, frame DTOs; OFF by default via `paralife.observer.enabled`"), and add the `ObserverBroadcaster @Order(60)` step after `TickBroadcaster @Order(50)` in the tick pipeline.

- [ ] **Step 5: Scope-diff line + commit**

Write the PR scope-diff (delivered vs spec intent) in the commit body. Then:
```bash
./gradlew spotlessCheck test   # full green before docs commit
git add docs/SCHEMA.md docs/ARCHITECTURE.md BACKLOG.md CLAUDE.md
git commit -m "docs(observer): merge-back — SCHEMA frame contract, ARCHITECTURE pipeline row, BACKLOG hardening slice"
```

---

## Self-Review (author checklist — run after writing, before execution)

**Spec coverage** (spec §EARS clauses → task) — task numbers reflect the scale gate inserted as Task 11 (UI → 12, docs → 13):
- O1 (offer per open observer) → Task 7 · O1b (latest-wins/non-block) → Task 6 · O1c (close-first on error/shutdown, drain terminates) → Task 6 (retained-sessions `@PreDestroy` + stalled-drain-terminates test)
- O2a (serialize once) → Task 7 · O2b (same payload) → Task 7
- O3 (brained, particles **and** structures, both controls) → Task 5 · O3b (subtype fields) → Task 5
- O4 (spawn +1 delta) → Task 2 (counter delta) + Task 3 (primary, forced-bonus, below-cost control) + Task 9 (admission path) + composite-bud via `ReproducerAutoPlaceTest` (Task 3 note)
- O5 (lightning transient-clear, multi-coord, no duplicates) → Task 4
- O6a (no grid entity) / O6b (no slot) / O6c (no registry), each with a **co-located `/ws/world` +1 control** → Task 9 · O7/O7b (seeded census, member present in `entities`) → Task 5
- O8 (bootstrap-barrier happens-before, deterministic RED) → Task 10 · O9 (release-once, RED-tested) → Task 8
- C1 (deflate exemption / browser-equivalent handshake) → Task 9 · C2 (off-thread delivery + on-thread failure boundary) → Tasks 6+7
- Env DTO projection (toxin intensity / mutagen strain / lightning) → Task 5 · `EnvironmentSnapshot` M1 → Task 4 · spawn counter M2 → Task 2/3 · concurrent registry M4 → Task 7 (`newKeySet`) · enablement gate H4 → Task 8
- Worst-case frame-budget measurement (Assumption 1) → **Task 11** (coded `@Tag("slow")` gate: 256×256 dense + both env layers saturated, best-of-5 vs 400 ms, explicit decision + encoder-VT fallback trigger)
- Browser JS-execution smoke (spec §M6 render/status signal) → **deferred to BACKLOG** (Task 13) per the 2026-07-19 review: the stack has no browser-test harness; the automated contract coverage is `ObserverEndpointIntegrationTest`'s real handshake + Jackson parse; render stays eye-judged.

**Placeholder scan:** none — every code step carries complete code. The former `EnvironmentTestSupport` placeholder is replaced with the inlined real seeded constructor (verified against `LightningTest:63-81`); the Task 9 O6 control is fully scripted (co-located `/ws/world` registration), not delegated.

**Type consistency:** `type()`/`primaryType()`/`secondaryType()` (not `species()`) used throughout the builder; `EntityDto` factories match the `WorldFrame` field names; `SpeciesSpawnCounter` indexed by ordinal in both counter and builder; `ObserverConfig(enabled, maxSessions)` consistent across gate + yaml; `ownedEntityIds()` consistent between `BotRegistry` and `ObserverBroadcaster`; `ObserverOutboundSender` retains a `sessions` map used by `@PreDestroy` + `threadForTest`.

**Firewall check:** every default-suite assertion pins a contract — O4 is a before/after +1 (or +2 for forced-bonus) delta (never an accumulated total/share — the Task 2 snapshot test was converted from totals to deltas), O7/O7b are seeded engine-direct with zero ticks advanced, no default-suite test advances N ticks then asserts on populations/survival/composition. The scale gate is `@Tag("slow")`, out of the default suite. Clean.

**Review-fix trace (2026-07-19, 4 codex reviewers):** vacuous bootstrap RED → deterministic happens-before pin (Task 10); O6 controls made real + co-located (Task 9); O1c close-first shutdown + stalled-drain test (Task 6); `brained:false` reachable + structure controls (Task 5); env DTO projection armed + toxin seeded (Tasks 4/5); frame-capture failure boundary (Task 7); spawn snapshot totals → delta (Task 2); lightning append-once (Task 4); concurrent cap stampede (Task 8); scale gate coded (Task 11); UI `wss://` scheme + composite glyph (Task 12); JS smoke → backlog (Task 13).
