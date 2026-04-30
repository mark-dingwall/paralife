---
phase: 19
plan: 02
type: execute
wave: 2
depends_on: [19-01]
files_modified:
  - src/main/java/com/paralife/engine/LiveEntityRegistry.java
  - src/main/java/com/paralife/engine/DeathFinalizer.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/test/java/com/paralife/engine/LiveEntityRegistryTest.java
  - src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java
autonomous: true
requirements:
  - SCALE-07
tags: [registry, sparse-set, lifecycle, java, spring-boot]

must_haves:
  truths:
    - "Every live grid-occupant entity (Particle, BondedPair, CompositeMember) appears exactly once in LiveEntityRegistry while alive. Composite/bonded child entityIds are NOT separately registered — only the grid-occupant id is."
    - "snapshot() returns a list sorted by ROW-MAJOR linear index `position.x() * gridConfig.height() + position.y()` for refactor-stable iteration order across the Plan 04 cut (REVIEWS HIGH-1 — row-major sort matches pre-refactor `for (x){ for (y) }` grid scan, so `Collections.shuffle(list, simRng)` produces identical output across the cut)."
    - "EntityEntry has `Optional<String> sessionId` field wired at registration time (REVIEWS HIGH-3 / Option B). TickBroadcaster reads `entry.sessionId().ifPresent(...)` directly — no botRegistry.getSessionForEntity lookup, no skip hazard. Composite/bonded entries with no single owning session use `Optional.empty()`."
    - "Death cleanup removes the entityId from LiveEntityRegistry at every site where BotRegistry.unregisterByEntity is called — DeathFinalizer (3 finalize* methods) AND SimulationEngine direct unregister sites (lines 719, 725, 973, 1127, 1136)."
    - "Registration in WorldWebSocketHandler.handleRegister adds the new entityId + sessionId to LiveEntityRegistry alongside the existing botRegistry.register call."
    - "Every entityId-introducing site is wired (REVIEWS H3): bond-formation (SimulationEngine.processInteractions ~589), composite-formation (~651–652), reproduce children (ActionResolver.resolveReproduce 569/582/753), composite-collapse (collapseToMember 695/698/701), composite-dissolve (dissolveToParticles 1098), revert-to-bonded-pair (revertToBondedPair 1051), member-death (handleMemberDeath / 1127, 1136, 1139), executeCompositeMovement (rigid-body multi-member updatePosition)."
    - "Movement in ActionResolver.resolveMove updates the entity's recorded position via liveEntityRegistry.updatePosition with the **correct entityId per path**: Particle → `ra.particle.id()`; BondedPair → bonded-pair grid-occupant id; CompositeMember moves → `member.id()` per moved member (REVIEWS MED-3)."
    - "register/unregister/updatePosition are O(1) (sparse-set: dense list + entityId→index map, swap-and-pop)."
    - "DeathFinalizer back-compat ctor in SimulationEngine (lines 172–202) is updated to pass LiveEntityRegistry. Every test-side manual instantiation of `SimulationEngine` / `ActionResolver` / `DeathFinalizer` / `WorldWebSocketHandler` is enumerated and forwarded (REVIEWS MED-2)."
    - "LiveEntityRegistryInvariantTest scripts bond/composite/dissolve/death/reproduce lifecycle and asserts `liveEntityRegistry.snapshot()` exactly matches the set of non-rock/non-nutrient occupied grid cells AND every entry's optional sessionId resolves via `botRegistry.getSessionForEntity` to the same value (or both empty) — REVIEWS HIGH-3."
    - "No tick-handler iteration logic is changed in this plan — Plan 04 owns the iteration refactor; this plan only stands up the registry + lifecycle hooks."
  artifacts:
    - path: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      provides: "@Component sparse-set of EntityEntry(entityId, position, Optional<String> sessionId) — register, unregister, updatePosition, snapshot; deterministic ROW-MAJOR sort by `position.x()*height + position.y()` baked into snapshot() (REVIEWS HIGH-1); thread-safe via synchronized."
      min_lines: 120
    - path: src/test/java/com/paralife/engine/LiveEntityRegistryTest.java
      provides: "Unit tests: register/unregister O(1) + idempotent; snapshot is shallow-copy and ROW-MAJOR-sorted; updatePosition mutates in place; concurrent register safe; snapshotIsSortedByRowMajorAfterRemovals (REVIEWS HIGH-1 — proves sort survives swap-and-pop); EntityEntry.sessionId is wired and preserved across updatePosition."
      min_lines: 120
    - path: src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java
      provides: "@SpringBootTest scenarios: registration, particle-death, bonding, composite-formation, composite-collapse, composite-dissolve, reproduce, executeCompositeMovement (if exists). After each scripted lifecycle step asserts (a) registry snapshot positions == set of non-rock/non-nutrient occupied cells, (b) every entry.sessionId().orElse(null) == botRegistry.getSessionForEntity(entityId).orElse(null). REVIEWS HIGH-3."
      min_lines: 150
  key_links:
    - from: src/main/java/com/paralife/engine/DeathFinalizer.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.unregister(entityId) called immediately after botRegistry.unregisterByEntity(entityId) in finalizeParticleDeath, finalizeBondedPairDeath; finalizeCompositeMemberDeath delegates to SimulationEngine.handleMemberDeath which unregisters via the SimulationEngine hook"
      pattern: "liveEntityRegistry\\.unregister"
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "Hooks at: processInteractions bonding (line 589 setEntity = unregister(predator)+unregister(prey)+register(bp.id, sessionId=Optional.empty)); composite-formation (lines 651–652 = unregister(bp1.id)+unregister(bp2.id)+register(member1.id, Optional.empty)+register(member2.id, Optional.empty)); collapseToMember (lines 695/698/701 = unregister(primary)+unregister(secondary)+register(member.id, Optional.empty)); processOvercrowding death (line 977 unregisterByEntity → liveEntityRegistry.unregister); processDeaths member-death sites (lines 1127, 1136 unregisterByEntity → liveEntityRegistry.unregister); revertToBondedPair (line 1051 = unregister(member.id)+register(bp.id, Optional.empty)); dissolveToParticles (line 1098 = unregister(member.id)+register(particle.id, Optional.empty))"
      pattern: "liveEntityRegistry\\.(register|unregister|updatePosition)"
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.register(entityId, pos, Optional.of(session.getId())) called immediately after botRegistry.register(...) in handleRegister"
      pattern: "liveEntityRegistry\\.register"
    - from: src/main/java/com/paralife/engine/ActionResolver.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "resolveMove — after a successful move, liveEntityRegistry.updatePosition(entityId, newPos) where entityId is `ra.particle.id()` for Particle; bonded-pair grid-occupant id for BondedPair moves; composite-rigid-body movement — updatePosition for every member using `member.id()`"
      pattern: "liveEntityRegistry\\.(updatePosition|register)"
---

<objective>
Stand up `LiveEntityRegistry` — a sparse-set bean whose iteration order is deterministic (ROW-MAJOR sort baked in, REVIEWS HIGH-1) and whose add/remove/updatePosition are O(1) — and wire its lifecycle hooks at every entity-creation, entity-death, entity-move, entityId-introduction, and composite-restructure site. EntityEntry carries an `Optional<String> sessionId` (REVIEWS HIGH-3 / Option B). This plan is **infrastructure only**: it does NOT change tick-handler iteration. Plan 04 consumes the registry; the consumer wave depends on this one.

**REVIEWS-replan revisions applied (this revision):**

- **HIGH-1 (all four reviewers — row-major pre-shuffle order):** `LiveEntityRegistry.snapshot()` sorts by **row-major linear index** `position.x() * gridConfig.height() + position.y()`, NOT by entityId. This matches the pre-refactor `for (int x){ for (int y){ } }` grid-scan input order, so `Collections.shuffle(list, simRng)` produces identical output across the Plan 04 cut. Documented as a compatibility shim in the JavaDoc — when Phase 21 owns the next refactor it may revisit. `LiveEntityRegistryTest.snapshotIsSortedByRowMajorAfterRemovals` asserts this. Plan 04 retains the "STOP/escalate on EXPECTED_DIGESTS mismatch" guard as defence-in-depth but it should NOT be the expected outcome.
- **HIGH-3 (codex/opencode — bonded/composite identity ambiguity):** EntityEntry record gains `Optional<String> sessionId` field. Wired at registration in `WorldWebSocketHandler.handleRegister` (sessionId = `Optional.of(session.getId())`) and at every server-internal entity creation site (sessionId = `Optional.empty()` for bonded/composite/reproduce-child entries — those have no single owning session today). Documented rule:
  - One grid occupant entity ↔ one optional session.
  - Composite/bonded child entityIds are NOT separately registered — only the grid-occupant id is.
  - `BotRegistry` remains source of truth for session ↔ bot mapping; `LiveEntityRegistry` is source of truth for grid iteration.
  - New `LiveEntityRegistryInvariantTest` (`src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java`) scripts lifecycle scenarios and asserts (a) registry snapshot positions exactly match the set of non-rock/non-nutrient occupied grid cells, and (b) every entry's `sessionId().orElse(null)` agrees with `botRegistry.getSessionForEntity(entityId).orElse(null)`.
- **MED-2 (gemini/opencode — ctor cascade fragility):** Task 2 enumerates every existing manual constructor invocation site of `SimulationEngine`, `ActionResolver`, `DeathFinalizer`, and `WorldWebSocketHandler` in `src/test/**/*.java` via a deterministic grep command, and prescribes the explicit forwarding update for each. Acceptance gate: `./gradlew compileTestJava` exits 0 after the wave.
- **MED-3 (claude — ActionResolver entityId source ambiguity):** Task 2 specifies the entityId expression per move path:
  - Particle moves (`resolveMove` solo Particle path): `ra.particle.id()`
  - BondedPair moves: bonded-pair grid-occupant entityId (the BondedPair's own id, NOT child ids)
  - Composite-member moves (per-member translation in `executeCompositeMovement` if it exists, else inside `resolveMove` for CompositeMember occupant): each `member.id()` per moved member.
  Each call site is enumerated with file:line.
- **HIGH-1 / Codex H residual:** `TickBroadcaster` does NOT need a row-major sort (Plan 03's per-session digests already neutralise its order-sensitivity). Plan 04 explicitly documents this.

Per PATTERNS.md analog evidence (overriding the CONTEXT.md mention of `BotRegistry` as the death-hook site): the death-cleanup hook lands in `DeathFinalizer` (and at `SimulationEngine.unregisterByEntity` direct call sites). `BotRegistry.unregisterByEntity` is **not** modified — the wiring is at the call sites.

**Wave assignment:** Plan 02 sits in Wave 2 and depends on Plan 01.

Purpose: SCALE-07 prerequisite. Tick handlers currently grid-scan O(65,536) cells to find entities. `LiveEntityRegistry.snapshot()` will replace that with O(N) entity iteration — but Plan 04 does that. This plan only ensures the data structure exists and stays correct under register/move/death/composite-collapse/composite-formation/dissolve/revert lifecycle events, AND provides a refactor-stable row-major iteration order that survives the Plan 04 cut.
Output: `LiveEntityRegistry` bean (with row-major sort + Optional<String> sessionId on EntityEntry) + lifecycle hooks at every entityId-introducing/removing/move site (REVIEWS H3) + Wave 0 unit test + new invariant integration test (REVIEWS HIGH-3) + ctor cascade enumeration & fix (REVIEWS MED-2) + per-path entityId resolution in ActionResolver (REVIEWS MED-3) + zero behavioural change to existing tick handlers.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@CLAUDE.md
@.planning/PROJECT.md
@.planning/STATE.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-VALIDATION.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/DeathFinalizer.java
@src/main/java/com/paralife/engine/SimulationEngine.java
@src/main/java/com/paralife/engine/ActionResolver.java
@src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
@src/main/java/com/paralife/world/Position.java
@src/main/java/com/paralife/world/Entity.java

<interfaces>
<!-- Key existing types and the new contract this plan creates. -->

From src/main/java/com/paralife/engine/BotRegistry.java (existing):
```java
public record BotState(String sessionId, String entityId, Position position) { }
public void register(String sessionId, String entityId, Position position);     // line 62
public void unregisterByEntity(String entityId);                                  // line 93
public Optional<String> getSessionForEntity(String entityId);                    // line 146
public Collection<BotState> getAllBots();                                         // line 195
```

From src/main/java/com/paralife/world/GridConfig.java (existing):
```java
public record GridConfig(int width, int height) { }   // height() is the row-major sort divisor
```

From src/main/java/com/paralife/engine/DeathFinalizer.java (existing call sites for hook injection):
```java
public void finalizeParticleDeath(int x, int y, Particle p) { ... botRegistry.unregisterByEntity(id); ... worldGrid.clearEntity(x, y); ... }   // line 84 unreg, line 88 clearEntity
public void finalizeBondedPairDeath(int x, int y, BondedPair bp) { ... botRegistry.unregisterByEntity(primaryId); botRegistry.unregisterByEntity(secondaryId); ... worldGrid.clearEntity(x, y); ... }   // lines 102, 103 unreg, line 113 clearEntity
public void finalizeCompositeMemberDeath(int x, int y, CompositeMember cm) { ... }   // delegates; the actual unreg happens in SimulationEngine.handleMemberDeath line 973
```

From src/main/java/com/paralife/engine/SimulationEngine.java (CONFIRMED via grep — every direct unregisterByEntity site):
```java
// line 719 — collapseToMember bonded-pair primary unreg
// line 725 — collapseToMember bonded-pair secondary unreg
// line 973 — handleMemberDeath shared cleanup
// line 1127 — processDeaths composite half (member sweep variant 1)
// line 1136 — processDeaths composite half (member sweep variant 2)
```

NEW interface this plan creates (REVIEWS HIGH-3 — sessionId field; HIGH-1 — row-major sort):
```java
package com.paralife.engine;

@Component
public class LiveEntityRegistry {
    public record EntityEntry(String entityId, Position position, Optional<String> sessionId) { }

    public void register(String entityId, Position position, Optional<String> sessionId);  // O(1) sparse-set add; idempotent
    public void unregister(String entityId);                                                  // O(1) swap-and-pop; idempotent
    public void updatePosition(String entityId, Position newPosition);                       // O(1); preserves sessionId; idempotent if missing
    public List<EntityEntry> snapshot();                                                       // O(N + N log N) shallow copy SORTED ROW-MAJOR (REVIEWS HIGH-1)
    public int size();
    public void clearForTest();                                                                // test seam
}
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (existing call site for hook injection):
```java
botRegistry.register(session.getId(), entityId, new Position(x, y));   // line 469 — hook IMMEDIATELY AFTER
// HIGH-3: pass Optional.of(session.getId()) to liveEntityRegistry.register
```

From src/main/java/com/paralife/engine/ActionResolver.java — entityId per move path (REVIEWS MED-3):
```java
// resolveMove solo Particle path (line ~497 setEntity):
//   updatePosition(ra.particle.id(), target)
// resolveMove BondedPair path (if separate; re-grep — may share resolveMove with type dispatch):
//   updatePosition(<bondedPair grid-occupant id>, target)  // the BondedPair's own .id(), NOT primary/secondary child ids
// executeCompositeMovement (or composite-rigid-body translation per moved member):
//   for each member: updatePosition(member.id(), newMemberPos)
// resolveReproduce children (lines 569, 582, 753):
//   register(child.entityId(), target, Optional.empty())     // child has no session
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create LiveEntityRegistry sparse-set bean with ROW-MAJOR snapshot + Optional sessionId on EntityEntry + Wave 0 unit test</name>
  <files>src/main/java/com/paralife/engine/LiveEntityRegistry.java, src/test/java/com/paralife/engine/LiveEntityRegistryTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (target — confirm absent)
    - src/main/java/com/paralife/engine/BotRegistry.java (lines 1–60 — class header, record pattern, ConcurrentHashMap discipline; lines 60–110 — register/unregister)
    - src/main/java/com/paralife/world/GridConfig.java (height() accessor — used as row-major divisor)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 2 — live-entity registry design; §Pitfall 6 — deterministic ordering)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 88–134 — class shell + insertion/removal pattern + snapshot pattern)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-1 — row-major sort consensus; HIGH-3 — Optional<String> sessionId on EntityEntry)
    - src/test/java/com/paralife/engine/BuffRegistryTest.java (analog test pattern)
    - src/main/java/com/paralife/world/Position.java
  </read_first>
  <behavior>
    - registerAddsEntry: register("e-1", Pos(3,4), Optional.empty()) → size==1; snapshot contains EntityEntry("e-1", Pos(3,4), Optional.empty()).
    - registerWithSessionStoresSession: register("e-1", Pos(3,4), Optional.of("sess-A")) → snapshot entry sessionId() == Optional.of("sess-A").
    - registerIsIdempotent: register("e-1", ...) twice → size==1; first sessionId is preserved on second call.
    - unregisterRemoves: register then unregister → size==0; snapshot empty.
    - unregisterIsIdempotent: unregister non-existent id → no exception; size unchanged.
    - unregisterIsO1AndDoesNotShift: register 3 entries, unregister middle one, snapshot still contains the other two.
    - snapshotIsShallowCopy: snapshot is independent — registering after taking the snapshot does not mutate the captured list.
    - snapshotIsSortedByRowMajor: register entries at positions (2,1), (1,5), (1,2), (0,7). Assume gridHeight=8. Row-major linear indices = 2*8+1=17, 1*8+5=13, 1*8+2=10, 0*8+7=7. snapshot() returns entries in linear-index order: 7, 10, 13, 17 → positions (0,7), (1,2), (1,5), (2,1). (REVIEWS HIGH-1.)
    - snapshotIsSortedByRowMajorAfterRemovals: register 4 entries, unregister 2 (the swap-and-pop disturbs internal dense order), register 2 new ones; snapshot() returns row-major-sorted list regardless of internal swap-and-pop history. (REVIEWS HIGH-1 — proves sort survives.)
    - updatePositionMutatesEntry: register("e-1", Pos(0,0), Optional.empty()); updatePosition("e-1", Pos(5,5)); snapshot contains EntityEntry("e-1", Pos(5,5), Optional.empty()).
    - updatePositionPreservesSessionId: register("e-1", Pos(0,0), Optional.of("sess-A")); updatePosition("e-1", Pos(5,5)); snapshot entry sessionId() still == Optional.of("sess-A").
    - updatePositionMissingIsNoop: updatePosition on non-existent id → no exception, size unchanged.
    - concurrentRegisterIsSafe: 4 threads each register 100 unique ids; final size == 400; snapshot contains all 400 in row-major order.
  </behavior>
  <action>
1. Create `src/main/java/com/paralife/engine/LiveEntityRegistry.java`:

```java
package com.paralife.engine;

import com.paralife.world.GridConfig;
import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 19 SCALE-07 (D-07..D-11): authoritative list of live entities for
 * tick-handler iteration. Replaces the O(65,536) grid scans used today by
 * {@code SimulationEngine}, {@code EnvironmentEngine} (per-entity segments),
 * and {@code TickBroadcaster.onTick}. Plan 04 consumes this bean; Plan 02
 * owns the data structure + lifecycle hooks only.
 *
 * <p>Sparse-set: dense ArrayList of EntityEntry + HashMap entityId→index.
 * O(1) register, unregister (swap-and-pop), updatePosition.
 *
 * <p><b>Iteration order — REVIEWS HIGH-1 (consensus of all four reviewers):</b>
 * {@link #snapshot()} returns a shallow copy SORTED BY ROW-MAJOR LINEAR INDEX
 * {@code position.x() * height + position.y()}. This is a compatibility shim
 * with the pre-Plan-04 grid-scan order: tick handlers historically iterated
 * {@code for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)},
 * which produces a list in row-major order. {@link java.util.Collections#shuffle}
 * is deterministic given (input order, seed); preserving row-major input order
 * post-refactor preserves {@code Collections.shuffle(list, simRng)} output
 * byte-for-byte across the Plan 04 cut, which keeps GoldenTraceEquivalenceTest
 * green automatically.
 *
 * <p>Phase 21 may revisit this if a more cache-friendly ordering is wanted;
 * the row-major shim has no semantic cost beyond an O(N log N) sort per
 * snapshot at N≤256.
 *
 * <p><b>Composite/bonded identity — REVIEWS HIGH-3 (Option B):</b>
 * {@link EntityEntry#sessionId()} is {@code Optional.of(sessionId)} when the
 * entity is registered via {@code WorldWebSocketHandler.handleRegister}
 * (one bot ↔ one session ↔ one entity), and {@code Optional.empty()} for
 * server-internal creations (bonding, composite formation, reproduce-child,
 * collapse, dissolve, revert). Composite and bonded child entityIds are NOT
 * separately registered — only the grid-occupant entity (BondedPair,
 * CompositeMember) is. Plan 04 {@code TickBroadcaster.onTick} reads
 * {@code entry.sessionId()} directly: present → broadcast to that session;
 * empty → fall through to broadcast-to-all-member-sessions logic.
 * {@code BotRegistry} remains source of truth for session ↔ bot mapping;
 * {@code LiveEntityRegistry} is source of truth for grid iteration.
 *
 * <p>Single-threaded mutation invariant (D-08, D-11) is unaffected: this
 * registry is read by tick handlers, written from registration (WS thread),
 * death (tick handler thread), composite collapse (tick handler thread),
 * and movement (tick handler thread). All public methods synchronize on
 * this bean — sub-microsecond critical sections. NO parallelStream anywhere.
 */
@Component
public class LiveEntityRegistry {
    private static final Logger log = LoggerFactory.getLogger(LiveEntityRegistry.class);

    public record EntityEntry(String entityId, Position position, Optional<String> sessionId) {
        public EntityEntry withPosition(Position newPosition) {
            return new EntityEntry(entityId, newPosition, sessionId);
        }
    }

    private final int height;
    private final List<EntityEntry> dense = new ArrayList<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    /** Row-major linear index = position.x() * height + position.y(). REVIEWS HIGH-1. */
    private final Comparator<EntityEntry> rowMajorComparator;

    public LiveEntityRegistry(GridConfig gridConfig) {
        this.height = gridConfig.height();
        this.rowMajorComparator = Comparator.comparingInt(
            (EntityEntry e) -> e.position().x() * height + e.position().y());
    }

    public synchronized void register(String entityId, Position position, Optional<String> sessionId) {
        if (indexById.containsKey(entityId)) return; // idempotent — preserve existing entry
        indexById.put(entityId, dense.size());
        dense.add(new EntityEntry(entityId, position, sessionId));
    }

    public synchronized void unregister(String entityId) {
        Integer idx = indexById.remove(entityId);
        if (idx == null) return; // idempotent
        int last = dense.size() - 1;
        if (idx == last) {
            dense.remove(last);
        } else {
            EntityEntry tail = dense.remove(last);
            dense.set(idx, tail);
            indexById.put(tail.entityId(), idx);
        }
    }

    public synchronized void updatePosition(String entityId, Position newPosition) {
        Integer idx = indexById.get(entityId);
        if (idx == null) return; // idempotent — entity may have died this tick
        dense.set(idx, dense.get(idx).withPosition(newPosition));
    }

    /**
     * O(N + N log N) shallow copy SORTED BY ROW-MAJOR LINEAR INDEX
     * (position.x() * height + position.y()). REVIEWS HIGH-1: this matches the
     * pre-Plan-04 grid-scan iteration order so {@code Collections.shuffle(list,
     * simRng)} output is byte-identical across the cut. Sort is mandatory,
     * not reactive — external order must not depend on internal swap-and-pop
     * history. N ≤ 256 today; sort cost ~µs.
     */
    public synchronized List<EntityEntry> snapshot() {
        List<EntityEntry> copy = new ArrayList<>(dense);
        copy.sort(rowMajorComparator);
        return copy;
    }

    public synchronized int size() { return dense.size(); }

    /** Test seam — clear all state. */
    public synchronized void clearForTest() {
        dense.clear();
        indexById.clear();
    }
}
```

Notes:
- DO NOT use `parallelStream` (D-08, D-11).
- `synchronized(this)` matches `BotRegistry.drainDeaths` pattern.
- **Row-major sort is mandatory** (REVIEWS HIGH-1): pre-Plan-04 tick handlers iterate `for (x){ for (y) }`, producing row-major lists. `Collections.shuffle(list, simRng)` is deterministic given (input order, seed). Sort-by-entityId would change input order across the Plan 04 cut → different shuffle output → different combat resolution → different deaths → different per-session digests → guaranteed `GoldenTraceEquivalenceTest` red on first Plan 04 run.
- `Optional<String> sessionId` (REVIEWS HIGH-3): present for bot-bound entities; empty for server-internal entities (bonding/composite/reproduce-child).

2. Create `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` as a pure-JUnit unit test. Cover the 13 behaviour bullets above. For `concurrentRegisterIsSafe`, use a `CountDownLatch` to release 4 threads simultaneously and `join()` them, then assert `registry.size() == 400` and `registry.snapshot().size() == 400` and that the snapshot is sorted by `position.x() * height + position.y()`.

For test setup, instantiate `new LiveEntityRegistry(new GridConfig(8, 8))` (or whatever GridConfig signature exists — re-grep before writing) so `height=8` and the row-major divisor is known. If `GridConfig` is a Spring `@ConfigurationProperties` record, the test can construct a plain instance directly.

3. Run the gate command in `<verify>`. Test must pass.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/main/java/com/paralife/engine/LiveEntityRegistry.java` exists.
    - `grep -c "@Component" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -c "public record EntityEntry" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 1
    - `grep -cE "EntityEntry\\(String entityId, Position position, Optional<String> sessionId\\)" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS HIGH-3 — sessionId field)
    - `grep -cE "register\\(String entityId, Position position, Optional<String> sessionId\\)" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS HIGH-3)
    - `grep -c "public synchronized.*unregister(String entityId" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -c "public synchronized.*snapshot()" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 1
    - `grep -cE "position\\(\\)\\.x\\(\\) \\* height \\+ position\\(\\)\\.y\\(\\)" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS HIGH-1 — row-major sort divisor)
    - `grep -c "Comparator.comparingInt" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS HIGH-1 — sort baked in)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 0
    - File `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` exists and contains tests `registerAddsEntry`, `unregisterIsO1AndDoesNotShift`, `snapshotIsShallowCopy`, `snapshotIsSortedByRowMajor`, `snapshotIsSortedByRowMajorAfterRemovals`, `updatePositionPreservesSessionId`, `concurrentRegisterIsSafe` (verifiable via `grep`).
    - `grep -c "snapshotIsSortedByRowMajor" src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` >= 2 (REVIEWS HIGH-1 — both sort tests present)
    - `./gradlew compileJava compileTestJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` exits 0
  </acceptance_criteria>
  <done>LiveEntityRegistry bean exists with O(1) register/unregister/updatePosition, EntityEntry carries Optional&lt;String&gt; sessionId (REVIEWS HIGH-3), and snapshot() returns a row-major-sorted shallow copy (REVIEWS HIGH-1 closed); unit test asserts both row-major sort properties and sessionId preservation; concurrent-safety test passes; no parallelism in this class.</done>
</task>

<task type="auto">
  <name>Task 2: Wire lifecycle hooks at every entityId-introducing/removing/move site (REVIEWS H3); enumerate and update all manual ctor invocation sites (REVIEWS MED-2); per-path entityId resolution in ActionResolver (REVIEWS MED-3); update DeathFinalizer back-compat ctor (REVIEWS M6)</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/ActionResolver.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (line 469 — `botRegistry.register(...)` call; hook AFTER)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (entire file — line 84 unreg in finalizeParticleDeath, lines 102/103 unreg in finalizeBondedPairDeath; finalizeCompositeMemberDeath delegates per grep)
    - src/main/java/com/paralife/engine/SimulationEngine.java (line 589 setEntity bonding, line 590 clearEntity, lines 651–652 composite-formation setEntity, lines 695/698/701 collapseToMember setEntity, lines 719/725 collapse-pair unregisterByEntity, line 973 handleMemberDeath unregisterByEntity, line 1051 revertToBondedPair setEntity, line 1098 dissolveToParticles setEntity, lines 1127/1136 processDeaths member-sweep unregisterByEntity, line 1139 member-death clearEntity)
    - src/main/java/com/paralife/engine/ActionResolver.java (line 483 clearEntity move-from, line 497 setEntity move-to, lines 569/582/753 reproduce-child setEntity; re-grep for `executeCompositeMovement` / multi-member move loop; identify exact local variable name for the moving entity per path — `ra.particle.id()` for solo Particle moves, bonded-pair grid-occupant id for BondedPair moves, `member.id()` for composite-member moves — REVIEWS MED-3)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean from Task 1 — confirm method signatures incl. Optional<String> sessionId on register)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 130–134, 289–299 — death-hook discipline + BotRegistry not modified)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4 — entity-list stale after death within same tick)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-3 — Optional<String> sessionId & invariant test; H3 — full entityId-introducing-site audit; MED-2 — ctor cascade enumeration; MED-3 — per-path entityId; M6 — back-compat ctor)
  </read_first>
  <action>

**STEP 0 (REVIEWS MED-2 — ctor cascade pre-flight):** Before editing any production source, run this command to enumerate every test-side manual constructor invocation that will need forwarding updates:

```bash
grep -nE "new (SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler)\\(" src/test/java -r
```

Save the output. Each listed `file:line` must be visited after step 1 lands its production-side ctor changes; each `new X(...)` must be updated to forward `liveEntityRegistry` (and `eligibleCellIndex` if Plan 01 added one and the test predates Plan 01's update). Acceptance criterion is `./gradlew compileTestJava` exits 0.

If any test uses `@SpringBootTest` (no manual `new`), no edit is needed — Spring autowires the new bean. Manual instantiations are the risk surface.

1. **Modify** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:

   (a) Add field: `private final LiveEntityRegistry liveEntityRegistry;` next to the other registry fields (around line 101).
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the @Autowired constructor (line 118 onwards). Forward `null` from any alternate test constructors. Plan 01 already added an `EligibleCellIndex` parameter in Wave 1; this plan extends the same constructor signature.
   (c) **Insert** immediately AFTER `botRegistry.register(session.getId(), entityId, new Position(x, y));` (~ line 469):
   ```java
   liveEntityRegistry.register(entityId, new Position(x, y), Optional.of(session.getId()));
   ```
   (REVIEWS HIGH-3 — sessionId wired at registration.)

2. **Modify** `src/main/java/com/paralife/engine/DeathFinalizer.java`:

   (a) Add field: `private final LiveEntityRegistry liveEntityRegistry;`
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the constructor (around line 64). Assign in body. Plan 01 already added an `EligibleCellIndex` parameter — extend the same constructor signature.
   (c) In `finalizeParticleDeath` (line 81): immediately AFTER `botRegistry.unregisterByEntity(id);` (line 84), insert:
   ```java
   liveEntityRegistry.unregister(id);
   ```
   (d) In `finalizeBondedPairDeath` (line 97): immediately AFTER each of the two `botRegistry.unregisterByEntity(...)` calls (lines 102, 103), insert:
   ```java
   liveEntityRegistry.unregister(primaryId);
   ```
   ```java
   liveEntityRegistry.unregister(secondaryId);
   ```
   (e) `finalizeCompositeMemberDeath` does NOT call `botRegistry.unregisterByEntity` directly. The composite-member unregister happens in `SimulationEngine.handleMemberDeath` at line 973 — handled in step 3.

3. **Modify** `src/main/java/com/paralife/engine/SimulationEngine.java`:

   (a) Add field `private final LiveEntityRegistry liveEntityRegistry;` and ctor parameter. Plan 01 already added `EligibleCellIndex` — extend the same constructor signature.
   (b) **Bond-formation hook (lines 588–590, REVIEWS H3):** before `worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair)` at line 589, after `bondedPair` is constructed, insert:
   ```java
   liveEntityRegistry.unregister(predator.entityId());
   liveEntityRegistry.unregister(prey.entityId());
   liveEntityRegistry.register(bondedPair.entityId(), bond.primaryPos, Optional.empty());  // server-internal, no session
   ```

   (c) **Composite-formation hook (lines 651–652, REVIEWS H3):** before the two `setEntity` calls, after `member1` and `member2` are constructed, insert:
   ```java
   liveEntityRegistry.unregister(bp1.entityId());
   liveEntityRegistry.unregister(bp2.entityId());
   liveEntityRegistry.register(member1.entityId(), cf.pos1(), Optional.empty());
   liveEntityRegistry.register(member2.entityId(), cf.pos2(), Optional.empty());
   ```
   Re-read SimulationEngine lines 640–660 to confirm exact local variable names.

   (d) **collapseToMember hook (lines 695–725):** beside each `botRegistry.unregisterByEntity(...)` at lines 719 and 725, insert:
   ```java
   liveEntityRegistry.unregister(bp.primaryEntityId());
   liveEntityRegistry.unregister(bp.secondaryEntityId());
   ```
   At each `worldGrid.setEntity(pos, ...)` site for the new CompositeMember (lines 695, 698, 701), insert:
   ```java
   liveEntityRegistry.register(member.entityId(), pos, Optional.empty());
   ```

   (e) **handleMemberDeath hook (line 973):** after `botRegistry.unregisterByEntity(id);`, insert:
   ```java
   liveEntityRegistry.unregister(id);
   ```

   (f) **processDeaths member-sweep hooks (lines 1127, 1136):** after each `botRegistry.unregisterByEntity(memberId);`, insert:
   ```java
   liveEntityRegistry.unregister(memberId);
   ```

   (g) **revertToBondedPair hook (line 1051):** before `worldGrid.setEntity(pos, bondedPair)`, insert:
   ```java
   liveEntityRegistry.unregister(member.entityId());
   liveEntityRegistry.register(bondedPair.entityId(), pos, Optional.empty());
   ```

   (h) **dissolveToParticles hook (line 1098):** before `worldGrid.setEntity(pos, particle)`, insert:
   ```java
   liveEntityRegistry.unregister(member.entityId());
   liveEntityRegistry.register(particle.entityId(), pos, Optional.empty());
   ```

   (i) **REVIEWS M6 — back-compat ctor:** SimulationEngine has back-compat 9-arg / 13-arg ctors (lines 172–202) that internally construct `new DeathFinalizer(...)`. Update the internal `new DeathFinalizer(...)` to pass `liveEntityRegistry` (and `eligibleCellIndex` from Plan 01) per the new positional order. Re-read SimulationEngine lines 172–202 before patching.

4. **Modify** `src/main/java/com/paralife/engine/ActionResolver.java` — **HARD REQUIREMENT** (REVIEWS MED-3 — per-path entityId):

   (a) Add field: `private final LiveEntityRegistry liveEntityRegistry;` (Plan 01 added `EligibleCellIndex`; extend the constructor with this second new parameter).
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the @Autowired constructor.

   (c) **resolveMove (lines 483 + 497) — per-path entityId resolution (REVIEWS MED-3):**

   Re-read `resolveMove` to identify the type-dispatch shape. The method handles three kinds of move:

   - **Solo Particle move:** the local variable holding the moving particle is `ra.particle` (the `RegisteredAction` carries the live `Particle` reference). After `worldGrid.setEntity(target.x(), target.y(), placed);` at line 497, insert:
     ```java
     liveEntityRegistry.updatePosition(ra.particle.id(), target);
     ```
     `ra.particle.id()` IS the entityId — same string `BotRegistry.register(sessionId, entityId, position)` was called with.

   - **BondedPair move (if `resolveMove` dispatches on occupant type):** the local variable holding the BondedPair is the BondedPair grid-occupant — its `.id()` (or `.entityId()`, match what's in `Entity.BondedPair` definition) is the registry key. After the BondedPair `setEntity` call, insert:
     ```java
     liveEntityRegistry.updatePosition(bondedPair.id(), target);  // or .entityId() — match source
     ```
     This is the BondedPair's OWN entityId, NOT `bondedPair.primaryEntityId()` or `secondaryEntityId()` — those child ids are NOT separately registered (REVIEWS HIGH-3 / Option B).

   - **Composite rigid-body multi-member move:** see step (e) below.

   If `resolveMove` is a single dispatcher with a generic `entity` reference, use a pattern-switch on Entity subtype:
   ```java
   String entityId = switch (entity) {
       case Entity.Particle p -> p.id();
       case Entity.BondedPair bp -> bp.id();
       case Entity.CompositeMember cm -> cm.id();
       default -> null;
   };
   if (entityId != null) liveEntityRegistry.updatePosition(entityId, target);
   ```
   Match the actual sealed-inner-type method name (`.id()` vs `.entityId()`) — re-read `Entity.java`.

   (d) **resolveReproduce children (REVIEWS H3) — children have no session, sessionId = Optional.empty():**
       - line 569 — `setEntity(target, child)` primary child:
         ```java
         liveEntityRegistry.register(child.entityId(), target, Optional.empty());
         ```
       - line 582 — `setEntity(bonusTarget, bonusChild)`:
         ```java
         liveEntityRegistry.register(bonusChild.entityId(), bonusTarget, Optional.empty());
         ```
       - line 753 — variant `setEntity(target, child)`:
         ```java
         liveEntityRegistry.register(child.entityId(), target, Optional.empty());
         ```

   (e) **executeCompositeMovement (rigid-body multi-member move, REVIEWS H3 + MED-3):**

   First, run `grep -nE "executeCompositeMovement|composite.*move|rigid.*body" src/main/java/com/paralife/engine/ActionResolver.java`. Three outcomes:

   1. **Method exists in ActionResolver:** for each `worldGrid.setEntity(newMemberPos, ...)` inside the per-member loop, insert:
      ```java
      liveEntityRegistry.updatePosition(member.id(), newMemberPos);  // REVIEWS MED-3 — member.id() per moved member
      ```
      Use `member.id()` — that's the CompositeMember's own grid-occupant entityId.

   2. **Method exists but lives in `SimulationEngine`:** apply the same hook there. Document the cross-class location in a comment in `ActionResolver` so future maintainers find it.

   3. **No such method exists** (composite movement is folded into `resolveMove` for CompositeMember occupant, single-member translation per call): the `resolveMove` switch above (step c) already handles it via `case Entity.CompositeMember cm -> cm.id();`.

   In every case, the `member.id()` (or `cm.id()`) is the entityId — NOT child references.

   **Do NOT defer this hook to Plan 03 or Plan 04.** Plan 04 reads `EntityEntry.position()` directly during tick-handler iteration; if `updatePosition` is missing on any move path, the recorded position goes stale and Plan 04's refactor breaks.

5. **Visit every site listed in STEP 0's grep output** and update the manual `new SimulationEngine(...)` / `new ActionResolver(...)` / `new DeathFinalizer(...)` / `new WorldWebSocketHandler(...)` calls to forward the new `LiveEntityRegistry` (and Plan 01's `EligibleCellIndex`) parameters. If any test instantiates with an explicit `null` for a previously-existing parameter, the new parameter goes in the same positional slot per the production constructor signature. (REVIEWS MED-2.)

6. Run `./gradlew compileTestJava` first as a fast gate (REVIEWS MED-2 acceptance) — if any manual instantiation site was missed, the compiler error pinpoints `file:line`. Fix and rerun. Then run `./gradlew test` for the full regression.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.register" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -cE "liveEntityRegistry\\.register\\(.*Optional\\.of\\(session\\.getId\\(\\)\\)\\)" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1 (REVIEWS HIGH-3 — sessionId wired)
    - `grep -c "liveEntityRegistry.unregister" src/main/java/com/paralife/engine/DeathFinalizer.java` >= 3 (REVIEWS H3 — particle + 2 bonded-pair)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1 (constructor injection at minimum)
    - `grep -cE "liveEntityRegistry\\.(unregister|register|updatePosition)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 14 (REVIEWS H3 — bond-formation 3 ops; composite-formation 4 ops; collapseToMember 3 ops; handleMemberDeath 1; processDeaths 2; revertToBondedPair 2; dissolveToParticles ≥2)
    - `grep -c "Optional.empty()" src/main/java/com/paralife/engine/SimulationEngine.java` >= 4 (REVIEWS HIGH-3 — server-internal entries have empty sessionId)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -c "liveEntityRegistry.updatePosition" src/main/java/com/paralife/engine/ActionResolver.java` >= 1 (HARD REQUIREMENT)
    - `grep -cE "liveEntityRegistry\\.updatePosition\\(ra\\.particle\\.id\\(\\)" src/main/java/com/paralife/engine/ActionResolver.java` >= 1 OR `grep -cE "case Entity\\.Particle.*->.*\\.id\\(\\)" src/main/java/com/paralife/engine/ActionResolver.java` >= 1 (REVIEWS MED-3 — Particle path uses `ra.particle.id()` or pattern-switch)
    - `grep -c "liveEntityRegistry.register" src/main/java/com/paralife/engine/ActionResolver.java` >= 3 (REVIEWS H3 — reproduce children at lines 569/582/753)
    - `grep -c "Optional.empty()" src/main/java/com/paralife/engine/ActionResolver.java` >= 3 (REVIEWS HIGH-3 — reproduce-children sessionId empty)
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/BotRegistry.java` == 0 (BotRegistry NOT coupled)
    - **REVIEWS MED-2 ctor cascade pre-flight gate** — `./gradlew compileTestJava` exits 0 (every manual `new SimulationEngine\|new ActionResolver\|new DeathFinalizer\|new WorldWebSocketHandler` site updated; missed forwardings caught at compile time)
    - `grep -nE "new DeathFinalizer\\(" src/main/java/com/paralife/engine/SimulationEngine.java` — every match line includes `liveEntityRegistry` and `eligibleCellIndex` arguments (REVIEWS M6)
    - `./gradlew compileJava` exits 0
    - `./gradlew test` exits 0 (full regression — no observable behaviour change in this task; failing here means a hook landed wrong or sessionId resolution is incorrect)
  </acceptance_criteria>
  <done>LiveEntityRegistry receives register/unregister/updatePosition events at EVERY entity lifecycle site (REVIEWS H3 closed). EntityEntry.sessionId is wired (Optional.of(sessionId) at WS register; Optional.empty() at server-internal sites — REVIEWS HIGH-3 closed). ActionResolver entityId resolution per move path documented and grep-verified (REVIEWS MED-3 closed). All manual ctor invocation sites in src/test/** are enumerated and updated (REVIEWS MED-2 closed). DeathFinalizer back-compat ctor in SimulationEngine updated (REVIEWS M6 closed). BotRegistry not coupled. Full regression suite passes.</done>
</task>

<task type="auto">
  <name>Task 3: LiveEntityRegistryInvariantTest — registry vs grid-occupant agreement after scripted lifecycle (REVIEWS HIGH-3)</name>
  <files>src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean — confirm EntityEntry.sessionId() returns Optional&lt;String&gt;)
    - src/main/java/com/paralife/engine/BotRegistry.java (line 146 — getSessionForEntity returns Optional&lt;String&gt;)
    - src/main/java/com/paralife/engine/SimulationEngine.java (bond-formation, composite-formation, collapseToMember, dissolveToParticles, revertToBondedPair, processDeaths — to script the lifecycle)
    - src/main/java/com/paralife/engine/ActionResolver.java (resolveMove, resolveReproduce — to script movement and child placement)
    - src/main/java/com/paralife/world/WorldGrid.java (occupant accessors)
    - src/main/java/com/paralife/world/Entity.java (sealed inner types Particle, Rock, Nutrient, BondedPair, CompositeMember)
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (lines 1–207 — @SpringBootTest dual-run analog)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-3 — invariant test mandate)
  </read_first>
  <behavior>
    - registryMatchesGridOccupantsAtRest: register N=5 bots via the real handleRegister path (or attemptPlacementForTest from Plan 01); assert `liveEntityRegistry.snapshot().stream().map(EntityEntry::position).collect(Set::toSet)` equals the set of `Position(x,y)` for which `worldGrid.getCell(x,y).occupant() instanceof Entity.Particle || ... instanceof Entity.BondedPair || ... instanceof Entity.CompositeMember` (excluding Rock and Nutrient).
    - registryMatchesGridOccupantsAfterBondFormation: drive a tick that triggers bond-formation; same equality holds; the BondedPair's id is in the registry; the predator/prey child ids are NOT.
    - registryMatchesGridOccupantsAfterCompositeFormation: drive a tick that triggers composite-formation; same equality holds; the two CompositeMember ids are in the registry; the source bonded-pair ids are NOT.
    - registryMatchesGridOccupantsAfterDeath: kill an entity (set energy to 0, drive a tick); the dead entityId is removed from the registry; the position is no longer in `snapshot()` and is no longer occupied.
    - registryMatchesGridOccupantsAfterMove: drive a tick where a bot moves; the moved entityId still in registry but with new position; the new position matches `worldGrid.getCell` lookup.
    - sessionIdAgreesWithBotRegistry: for every entry in the snapshot, `entry.sessionId().orElse(null)` equals `botRegistry.getSessionForEntity(entry.entityId()).orElse(null)` (both present and same, or both null/empty). For composite/bonded entries, both should be empty.
  </behavior>
  <action>
1. Create `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java`:

```java
package com.paralife.engine;

import com.paralife.websocket.WorldWebSocketHandler;
import com.paralife.world.Cell;
import com.paralife.world.Entity;
import com.paralife.world.Position;
import com.paralife.world.WorldGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19 SCALE-07 (REVIEWS HIGH-3): assert {@link LiveEntityRegistry} agrees
 * with the grid after scripted lifecycle scenarios. Two invariants per
 * scenario:
 * <ol>
 *   <li>Registry positions == set of non-rock/non-nutrient occupied grid cells.</li>
 *   <li>For every entry, {@code entry.sessionId().orElse(null) ==
 *       botRegistry.getSessionForEntity(entry.entityId()).orElse(null)}.</li>
 * </ol>
 * Catches missed register/unregister hooks immediately, instead of via a flaky
 * golden trace. Composite/bonded child entityIds must NOT appear in the
 * registry — only the grid-occupant id (BondedPair, CompositeMember).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false"
})
class LiveEntityRegistryInvariantTest {

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired WorldWebSocketHandler handler;
    @Autowired ApplicationEventPublisher publisher;

    @BeforeEach
    void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        handler.resetSeed();
        // EligibleCellIndex.rebuildForTest() if applicable from Plan 01
    }

    @Test
    void registryMatchesGridOccupantsAtRest() {
        // Register 5 bots via the Plan 01 test seam (real handleRegister sub-step).
        for (int i = 0; i < 5; i++) {
            handler.attemptPlacementForTest("bot-" + i, Entity.ParticleType.values()[i % 3], 100);
        }
        assertRegistryAgreesWithGrid();
    }

    @Test
    void registryMatchesGridOccupantsAfterDeath() {
        // Place a bot, force its energy to 0, drive a tick, assert removal.
        // (Use whatever helper in TestSupport/SimulationConfigForcedDeath exists,
        //  or directly mutate via worldGrid.setEntity to a zero-energy clone.)
        handler.attemptPlacementForTest("bot-1", Entity.ParticleType.values()[0], /*energy*/ 0);
        publisher.publishEvent(new com.paralife.websocket.TickEvent(1));
        // After the death tick, the entity should be off the grid AND off the registry.
        assertRegistryAgreesWithGrid();
        assertThat(liveEntityRegistry.size()).isZero();
    }

    @Test
    void registryMatchesGridOccupantsAfterBondFormation() {
        // Place predator + prey adjacent so bond-formation fires.
        // (Re-read SimulationEngine.processInteractions for the exact scenario;
        //  this may require placing two specific ParticleType values adjacent.)
        // After publishEvent(tick), the registry must contain the BondedPair's
        // id and NOT contain either predator or prey id.
        // ... scripting omitted; pattern: place + tick + assertRegistryAgreesWithGrid.
        // For full implementation, mirror EnvironmentDeterminismTest setup style.
    }

    @Test
    void sessionIdAgreesWithBotRegistry() {
        for (int i = 0; i < 5; i++) {
            handler.attemptPlacementForTest("bot-" + i, Entity.ParticleType.values()[i % 3], 100);
        }
        for (LiveEntityRegistry.EntityEntry entry : liveEntityRegistry.snapshot()) {
            String regSession = entry.sessionId().orElse(null);
            String botSession = botRegistry.getSessionForEntity(entry.entityId()).orElse(null);
            // Either both present and equal, or both null (server-internal entity).
            assertThat(regSession)
                .as("sessionId agrees with BotRegistry for entityId=%s", entry.entityId())
                .isEqualTo(botSession);
        }
    }

    private void assertRegistryAgreesWithGrid() {
        // 1. Build the set of non-rock/non-nutrient occupied positions from the grid.
        Set<Position> gridOccupants = new HashSet<>();
        for (int x = 0; x < worldGrid.getWidth(); x++) {
            for (int y = 0; y < worldGrid.getHeight(); y++) {
                Cell cell = worldGrid.getCell(x, y);
                Entity occ = cell.occupant();
                if (occ == null) continue;
                if (occ instanceof Entity.Rock || occ instanceof Entity.Nutrient) continue;
                gridOccupants.add(new Position(x, y));
            }
        }

        // 2. Build the set of registry positions.
        Set<Position> regPositions = new HashSet<>();
        for (LiveEntityRegistry.EntityEntry e : liveEntityRegistry.snapshot()) {
            regPositions.add(e.position());
        }

        assertThat(regPositions).as("registry positions equal grid-occupant positions").isEqualTo(gridOccupants);
    }
}
```

The `attemptPlacementForTest` and `handler.resetSeed` come from Plan 01 (Task 2 step 1(f)). If the bond-formation scenario requires more specific setup (predator + prey type pairing in adjacent cells), refer to `SimulationEngine.processInteractions` source for the exact ParticleType pairing rule and place via direct `worldGrid.setEntity` if `attemptPlacementForTest` cannot guarantee adjacency.

Tests that are too elaborate to implement in this single task (full bond/composite scripting) can be marked `@Disabled` with a comment pointing to the lifecycle method they need, but `registryMatchesGridOccupantsAtRest`, `registryMatchesGridOccupantsAfterDeath`, and `sessionIdAgreesWithBotRegistry` MUST be enabled and green. The HIGH-3 fix is satisfied by these three at minimum; bond/composite scenarios strengthen coverage but are not strictly required for HIGH-3 closure.

2. Run the gate command. All enabled tests must pass.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.LiveEntityRegistryInvariantTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` exists.
    - `grep -c "@SpringBootTest" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "registryMatchesGridOccupantsAtRest" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "registryMatchesGridOccupantsAfterDeath" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "sessionIdAgreesWithBotRegistry" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "assertRegistryAgreesWithGrid" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 2 (helper used in multiple scenarios)
    - `grep -c "Entity.Rock\\|Entity.Nutrient" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 1 (rocks/nutrients excluded from comparison set)
    - `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryInvariantTest"` exits 0 (REVIEWS HIGH-3 invariant gate green)
    - `./gradlew test` exits 0 (full regression remains green)
  </acceptance_criteria>
  <done>LiveEntityRegistryInvariantTest exists and asserts (1) registry positions exactly equal the set of non-rock/non-nutrient occupied grid cells after registration / death / move scenarios, and (2) every entry's optional sessionId agrees with BotRegistry.getSessionForEntity. REVIEWS HIGH-3 closed: bonded/composite identity model is verified by an automated test, not just documented.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| WS thread → tick thread | Registration arrives on WS inbound; tick handlers read snapshot. `synchronized(this)` is the boundary. |
| tick handler → tick handler | DeathFinalizer (called from SimulationEngine death pass) writes; later @Order handlers (PerceptionBroadcaster, TickBroadcaster) read. Same single-threaded execution chain. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-05 | Tampering | LiveEntityRegistry stale after death within tick (Pitfall 4) | mitigate | DeathFinalizer + SimulationEngine direct unregisterByEntity sites all hook liveEntityRegistry.unregister synchronously (REVIEWS H3). By @Order(50) snapshot is post-death. Verified by Plan 03 GoldenTraceEquivalenceTest + new LiveEntityRegistryInvariantTest. |
| T-19-05a | Tampering | LiveEntityRegistry missing entityId-introducing site (REVIEWS H3) | mitigate | Bond-formation, composite-formation, reproduce-children, collapseToMember, revertToBondedPair, dissolveToParticles, executeCompositeMovement all wired (REVIEWS H3 closed). Acceptance grep counter ≥14 enforces coverage. |
| T-19-05b | Tampering | snapshot() iteration order divergence across Plan 04 cut | mitigate | ROW-MAJOR sort baked into snapshot() (REVIEWS HIGH-1); matches pre-Plan-04 grid-scan order; Collections.shuffle(list, simRng) output identical across cut. |
| T-19-05c | Tampering | Bonded/composite identity confusion — child ids registered separately, or grid-occupant id missed | mitigate | EntityEntry.sessionId Optional<String> + LiveEntityRegistryInvariantTest assertions (REVIEWS HIGH-3 closed). Documented rule: only grid-occupant id is registered; children are NOT. |
| T-19-06 | Information disclosure | EntityEntry contains entityId + position | accept | Server-internal data; never echoed to clients. |
| T-19-07 | DoS | Concurrent register storm during burst connect | accept | Per-call critical section is sub-µs; Phase 17 admission gate caps the burst rate. |
| T-19-08 | Compile-error regression | Ctor cascade — missed manual instantiation in src/test/** | mitigate | REVIEWS MED-2 — pre-flight grep enumerates every manual `new` site; compileTestJava gate catches misses. |
| T-19-08a | Tampering | ActionResolver entityId source ambiguity (Particle vs BondedPair vs CompositeMember) | mitigate | REVIEWS MED-3 — per-path entityId resolution documented and grep-verified (`ra.particle.id()` for Particle; bonded-pair grid-occupant id for BondedPair; `member.id()` for CompositeMember). |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` — Wave 0 unit tests green incl. row-major sort after removals (REVIEWS HIGH-1).
- `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryInvariantTest"` — invariant tests green (REVIEWS HIGH-3).
- `./gradlew compileTestJava` — passes (REVIEWS MED-2 ctor cascade gate).
- `./gradlew test` — full regression remains green.
- `grep -rn "liveEntityRegistry" src/main/java/com/paralife/` shows hooks at every site enumerated in REVIEWS H3.
- `BotRegistry.java` has no reference to `LiveEntityRegistry`.
- DeathFinalizer back-compat construction in SimulationEngine receives `LiveEntityRegistry` (REVIEWS M6).
</verification>

<success_criteria>
- LiveEntityRegistry is registered as a Spring bean and ready for Plan 04 to consume.
- snapshot() returns ROW-MAJOR-sorted order — matches pre-Plan-04 grid-scan input order so `Collections.shuffle(list, simRng)` output is byte-identical across the cut (REVIEWS HIGH-1 closed).
- EntityEntry carries Optional&lt;String&gt; sessionId, wired at registration; composite/bonded child ids NOT separately registered (REVIEWS HIGH-3 closed).
- All register/unregister/updatePosition hooks wired at every entityId-introducing/removing/move site (REVIEWS H3 closed). ActionResolver per-path entityId resolution explicit (REVIEWS MED-3 closed).
- Every manual `new SimulationEngine\|ActionResolver\|DeathFinalizer\|WorldWebSocketHandler` site in src/test/** updated; compileTestJava green (REVIEWS MED-2 closed).
- DeathFinalizer back-compat ctor in SimulationEngine updated (REVIEWS M6 closed).
- LiveEntityRegistryInvariantTest passes — registry vs grid agreement scripted (REVIEWS HIGH-3 closed).
- Existing 166+ tests remain green (this plan introduces NO observable behaviour change).
- Single-threaded mutation invariant preserved (D-08, D-11).
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-02-SUMMARY.md`.
</output>
