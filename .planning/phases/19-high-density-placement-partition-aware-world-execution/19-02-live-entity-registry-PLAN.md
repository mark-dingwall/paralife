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
autonomous: true
requirements:
  - SCALE-07
tags: [registry, sparse-set, lifecycle, java, spring-boot]

must_haves:
  truths:
    - "Every live entity (Particle, BondedPair, CompositeMember, offspring child-* Particles) appears exactly once in LiveEntityRegistry while alive."
    - "snapshot() returns a list sorted by entityId for deterministic, refactor-stable iteration order across both pre- and post-Plan-04 codebases (REVIEWS M3 / Codex H — sort baked in, not reactive)."
    - "Death cleanup removes the entityId from LiveEntityRegistry at every site where BotRegistry.unregisterByEntity is called — DeathFinalizer (3 finalize* methods) AND SimulationEngine direct unregister sites (lines 719, 725, 973, 1127, 1136)."
    - "Registration in WorldWebSocketHandler.handleRegister adds the new entityId to LiveEntityRegistry alongside the existing botRegistry.register call."
    - "Every entityId-introducing site is wired (REVIEWS H3): bond-formation (SimulationEngine.processInteractions ~589), composite-formation (~651–652), reproduce children (ActionResolver.resolveReproduce 569/582/753), composite-collapse (collapseToMember 695/698/701), composite-dissolve (dissolveToParticles 1098), revert-to-bonded-pair (revertToBondedPair 1051), member-death (handleMemberDeath / 1127, 1136, 1139), executeCompositeMovement (rigid-body multi-member updatePosition)."
    - "Movement in ActionResolver.resolveMove updates the entity's recorded position via liveEntityRegistry.updatePosition — Plan 04 consumers can rely on entry.position() being current."
    - "register/unregister/updatePosition are O(1) (sparse-set: dense list + entityId→index map, swap-and-pop)."
    - "DeathFinalizer back-compat ctor in SimulationEngine (lines 172–202) is updated to pass LiveEntityRegistry — no compile-error regression (REVIEWS M6)."
    - "No tick-handler iteration logic is changed in this plan — Plan 04 owns the iteration refactor; this plan only stands up the registry + lifecycle hooks."
  artifacts:
    - path: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      provides: "@Component sparse-set of EntityEntry(entityId, position) — register, unregister, updatePosition, snapshot; deterministic sort-by-entityId iteration order baked into snapshot() (REVIEWS M3); thread-safe via synchronized."
      min_lines: 100
    - path: src/test/java/com/paralife/engine/LiveEntityRegistryTest.java
      provides: "Unit tests: register/unregister O(1) + idempotent; snapshot is shallow-copy and sort-by-entityId stable; updatePosition mutates in place; concurrent register from multiple threads still yields a consistent snapshot; snapshotIsSortedByEntityIdAfterRemovals (REVIEWS M3 / Codex H — proves sort survives swap-and-pop)."
      min_lines: 100
  key_links:
    - from: src/main/java/com/paralife/engine/DeathFinalizer.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.unregister(entityId) called immediately after botRegistry.unregisterByEntity(entityId) in finalizeParticleDeath, finalizeBondedPairDeath; finalizeCompositeMemberDeath delegates to SimulationEngine.handleMemberDeath which unregisters via the SimulationEngine hook"
      pattern: "liveEntityRegistry\\.unregister"
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "Hooks at: processInteractions bonding (line 589 setEntity = unregister(predator)+unregister(prey)+register(bp.id)); composite-formation (lines 651–652 = unregister(bp1.id)+unregister(bp2.id)+register(member1.id)+register(member2.id)); collapseToMember (lines 695/698/701 = unregister(primary)+unregister(secondary)+register(member.id)); processOvercrowding death (line 977 unregisterByEntity → liveEntityRegistry.unregister); processDeaths member-death sites (lines 1127, 1136 unregisterByEntity → liveEntityRegistry.unregister); revertToBondedPair (line 1051 = unregister(member.id)+register(bp.id)); dissolveToParticles (line 1098 = unregister(member.id)+register(particle.id))"
      pattern: "liveEntityRegistry\\.(register|unregister|updatePosition)"
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.register(entityId, pos) called immediately after botRegistry.register(...) in handleRegister"
      pattern: "liveEntityRegistry\\.register"
    - from: src/main/java/com/paralife/engine/ActionResolver.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "resolveMove — after a successful move, liveEntityRegistry.updatePosition(entityId, newPos); resolveReproduce — register(childId, target); composite-rigid-body movement — updatePosition for every member"
      pattern: "liveEntityRegistry\\.(updatePosition|register)"
---

<objective>
Stand up `LiveEntityRegistry` — a sparse-set bean whose iteration order is deterministic (sort-by-entityId baked in) and whose add/remove/updatePosition are O(1) — and wire its lifecycle hooks at every entity-creation, entity-death, entity-move, entityId-introduction, and composite-restructure site. This plan is **infrastructure only**: it does NOT change tick-handler iteration. Plan 04 consumes the registry; the consumer wave depends on this one.

**REVIEWS revisions applied:**

- **H3 (gemini/claude/codex):** Lifecycle hooks expanded to cover every entityId-introducing or entityId-removing site:
  bond-formation, composite-formation, reproduce children (incl. bonus children), composite-collapse, composite-dissolve, revert-to-bonded-pair, member-death, executeCompositeMovement (rigid-body multi-member move). Without these, Plan 04 would iterate an incomplete registry → Collections.shuffle list-size differs → RNG-consumption differs → GoldenTraceEquivalenceTest fails late in Wave 4.
- **M3 / Codex H (sort-by-entityId):** `LiveEntityRegistry.snapshot()` sorts by entityId before returning the shallow copy. This is mandatory, not reactive — it makes the registry's iteration order independent of insertion-order vs swap-and-pop ordering, so the post-Plan-04 cut produces an order that's reproducible against the pre-Plan-04 baseline (which Plan 03 will pin into EXPECTED_DIGEST after Plan 02 lands; pre-Plan-02 the digest was non-deterministic across `ConcurrentHashMap.values()` ordering — Plan 03's harness change addresses that on the broadcast side).
- **M6 (claude):** Back-compat 9-arg / 13-arg `SimulationEngine` ctor (lines 172–202) is updated to pass `LiveEntityRegistry` to the internal `new DeathFinalizer(...)` call.

Per PATTERNS.md analog evidence (overriding the CONTEXT.md mention of `BotRegistry` as the death-hook site): the death-cleanup hook lands in `DeathFinalizer` (and at `SimulationEngine.unregisterByEntity` direct call sites). `BotRegistry.unregisterByEntity` is **not** modified — the wiring is at the call sites.

**Wave assignment:** Plan 02 sits in Wave 2 and depends on Plan 01. Both plans modify `WorldWebSocketHandler.java`'s constructor (Plan 01 adds an `EligibleCellIndex` parameter; this plan adds a `LiveEntityRegistry` parameter). Sequencing avoids constructor-merge conflicts. Plan 01 also constructor-injects `EligibleCellIndex` into `DeathFinalizer`, `ActionResolver`, and `SimulationEngine` for its lifecycle hooks; this plan extends those constructors with `LiveEntityRegistry` parameters in the same wave-2 commit window — keep both fields side by side in each modified class.

Purpose: SCALE-07 prerequisite. Tick handlers currently grid-scan O(65,536) cells to find entities. `LiveEntityRegistry.snapshot()` will replace that with O(N) entity iteration — but Plan 04 does that. This plan only ensures the data structure exists and stays correct under register/move/death/composite-collapse/composite-formation/dissolve/revert lifecycle events.
Output: `LiveEntityRegistry` bean (with sort-by-entityId snapshot per REVIEWS M3) + lifecycle hooks at every entityId-introducing/removing/move site (REVIEWS H3) + Wave 0 unit tests + DeathFinalizer back-compat ctor fix (REVIEWS M6) + zero behavioural change to existing tick handlers.
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

From src/main/java/com/paralife/engine/DeathFinalizer.java (existing call sites for hook injection):
```java
public void finalizeParticleDeath(int x, int y, Particle p) { ... botRegistry.unregisterByEntity(id); ... worldGrid.clearEntity(x, y); ... }   // line 84 unreg, line 88 clearEntity
public void finalizeBondedPairDeath(int x, int y, BondedPair bp) { ... botRegistry.unregisterByEntity(primaryId); botRegistry.unregisterByEntity(secondaryId); ... worldGrid.clearEntity(x, y); ... }   // lines 102, 103 unreg, line 113 clearEntity
public void finalizeCompositeMemberDeath(int x, int y, CompositeMember cm) { ... }   // delegates to recursive variant; CONFIRMED (grep): does NOT call botRegistry.unregisterByEntity directly. The actual unreg happens in SimulationEngine.handleMemberDeath line 973 (botRegistry.unregisterByEntity(id)) — hook there, not in DeathFinalizer for member deaths.
```

From src/main/java/com/paralife/engine/SimulationEngine.java (CONFIRMED via grep — every direct unregisterByEntity site):
```java
// line 719 — collapseToMember bonded-pair primary unreg before collapse
botRegistry.unregisterByEntity(bp.primaryEntityId());
// line 725 — collapseToMember bonded-pair secondary unreg before collapse
botRegistry.unregisterByEntity(bp.secondaryEntityId());
// line 973 — handleMemberDeath shared cleanup (called from finalizeCompositeMemberDeath chain)
botRegistry.unregisterByEntity(id);
// line 1127 — processDeaths composite half (member sweep variant 1)
botRegistry.unregisterByEntity(memberId);
// line 1136 — processDeaths composite half (member sweep variant 2)
botRegistry.unregisterByEntity(memberId);
```

NEW interface this plan creates:
```java
package com.paralife.engine;

@Component
public class LiveEntityRegistry {
    public record EntityEntry(String entityId, Position position) { }

    public void register(String entityId, Position position);         // O(1) sparse-set add; idempotent
    public void unregister(String entityId);                           // O(1) swap-and-pop; idempotent
    public void updatePosition(String entityId, Position newPosition); // O(1); idempotent if missing
    public List<EntityEntry> snapshot();                                // O(N + N log N) shallow copy SORTED BY entityId (REVIEWS M3)
    public int size();
    public void clearForTest();                                          // test seam
}
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (existing call site for hook injection):
```java
botRegistry.register(session.getId(), entityId, new Position(x, y));   // line 469 — hook IMMEDIATELY AFTER
```

From src/main/java/com/paralife/engine/SimulationEngine.java (entityId-introducing sites — REVIEWS H3 audit):
```java
// line 589 — setEntity(primaryPos, bondedPair):
//   bond-formation: predator (Particle) + prey (Particle) → BondedPair with id "predator+prey".
//   Hooks: liveEntityRegistry.unregister(predator.id); unregister(prey.id); register(bp.id, primaryPos);
// line 590 — clearEntity(secondaryPos): the absorbed Particle's cell.
//   No additional registry mutation here — the unregister of `prey.id` already covered above.

// line 651 — setEntity(pos1, member1); line 652 — setEntity(pos2, member2):
//   composite-formation: two BondedPairs (bp1, bp2) → two CompositeMembers (member1, member2) with FRESH UUIDs.
//   Hooks (right before lines 651–652, after the new ids are generated):
//     liveEntityRegistry.unregister(bp1.id);
//     liveEntityRegistry.unregister(bp2.id);
//     liveEntityRegistry.register(member1.id, pos1);
//     liveEntityRegistry.register(member2.id, pos2);

// line 695, 698, 701 — setEntity in collapseToMember (BondedPair → CompositeMember).
//   Already has line 719/725 unregister for the bonded-pair members.
//   Add: liveEntityRegistry.register(memberId, pos) at the placement site.

// line 1051 — setEntity(pos, bondedPair):
//   revertToBondedPair: CompositeMember → BondedPair with new id.
//   Hooks: liveEntityRegistry.unregister(member.id); register(bondedPair.id, pos);

// line 1098 — setEntity(pos, particle):
//   dissolveToParticles: CompositeMember → Particle with new id (e.g. cm.id+"-p").
//   Hooks: liveEntityRegistry.unregister(member.id); register(particle.id, pos);

// line 1139 — clearEntity(pos):
//   member-death cleanup. The unregister already happens at lines 1127/1136/973
//   (whichever is the live path). Confirm via grep — do NOT double-unregister.
```

From src/main/java/com/paralife/engine/ActionResolver.java (movement + reproduce sites):
```java
// resolveMove successful move — line 483 clearEntity, line 497 setEntity:
//   Hook: liveEntityRegistry.updatePosition(entityId, newPos);

// resolveReproduce — child-* Particle placement at line 569 (primary child),
// line 582 (bonus child for composite), line 753 (variant):
//   Hook: liveEntityRegistry.register(childId, target);

// composite rigid-body movement — if executeCompositeMovement (or equivalent) moves N members,
// every member's position changes. Re-grep ActionResolver for "executeCompositeMovement"
// or for the multi-setEntity loop that handles composite-rigid-body translation.
//   Hook (per moved member): liveEntityRegistry.updatePosition(member.id, newPos);
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create LiveEntityRegistry sparse-set bean with sort-by-entityId snapshot + Wave 0 unit test</name>
  <files>src/main/java/com/paralife/engine/LiveEntityRegistry.java, src/test/java/com/paralife/engine/LiveEntityRegistryTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (target — confirm absent)
    - src/main/java/com/paralife/engine/BotRegistry.java (lines 1–60 — class header, record pattern, ConcurrentHashMap discipline; lines 60–110 — register/unregister)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 2 — live-entity registry design; §Pitfall 6 — deterministic ordering required for golden-trace test)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 88–134 — class shell + insertion/removal pattern + snapshot pattern)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (M3 + Codex HIGH on iteration order — sort-by-entityId baked in)
    - src/test/java/com/paralife/engine/BotRegistryTest.java (analog test pattern if it exists; otherwise BuffRegistryTest as fallback analog)
    - src/main/java/com/paralife/world/Position.java
  </read_first>
  <behavior>
    - registerAddsEntry: register("e-1", Pos(3,4)) → size==1; snapshot contains EntityEntry("e-1", Pos(3,4)).
    - registerIsIdempotent: register("e-1", ...) twice → size==1.
    - unregisterRemoves: register then unregister → size==0; snapshot empty.
    - unregisterIsIdempotent: unregister non-existent id → no exception; size unchanged.
    - unregisterIsO1AndDoesNotShift: register 3 entries, unregister middle one, snapshot still contains the other two. (Internal swap-and-pop reorders the dense array, but snapshot() sorts before return → external order is stable.)
    - snapshotIsShallowCopy: snapshot is independent — registering after taking the snapshot does not mutate the captured list.
    - snapshotIsSortedByEntityId: register("c"), register("a"), register("b") → snapshot() returns [a, b, c] sorted lexicographically by entityId. (REVIEWS M3.)
    - snapshotIsSortedByEntityIdAfterRemovals: register("a"), register("b"), register("c"); unregister("a"); register("d"); snapshot() returns [b, c, d] in lex order regardless of internal swap-and-pop dense ordering. (REVIEWS M3 + Codex H — proves sort survives.)
    - updatePositionMutatesEntry: register("e-1", Pos(0,0)); updatePosition("e-1", Pos(5,5)); snapshot contains EntityEntry("e-1", Pos(5,5)).
    - updatePositionMissingIsNoop: updatePosition on non-existent id → no exception, size unchanged.
    - concurrentRegisterIsSafe: 4 threads each register 100 unique ids; final size == 400; snapshot contains all 400 in sorted order.
  </behavior>
  <action>
1. Create `src/main/java/com/paralife/engine/LiveEntityRegistry.java`:

```java
package com.paralife.engine;

import com.paralife.world.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 19 SCALE-07 (D-07..D-11): authoritative list of live entities for
 * tick-handler iteration. Replaces the O(65,536) grid scans used today by
 * {@code SimulationEngine}, {@code EnvironmentEngine} (per-entity segments),
 * and {@code TickBroadcaster.onTick}. Plan 04 consumes this bean; Plan 02
 * owns the data structure + lifecycle hooks only.
 *
 * <p>Sparse-set: dense ArrayList of EntityEntry + HashMap entityId→index.
 * O(1) register, unregister (swap-and-pop), updatePosition. Iteration via
 * {@link #snapshot()} returns a shallow copy SORTED BY entityId (REVIEWS M3
 * + Codex HIGH), so external iteration order is independent of internal
 * insertion vs swap-and-pop ordering — a necessary condition for the
 * GoldenTraceEquivalenceTest digest to be stable across the Plan 04 cut.
 *
 * <p>Determinism (D-09 / RESEARCH §Pitfall 6): the sort-by-entityId is the
 * canonical order. Same scenario + same set of live entityIds → same
 * snapshot order, regardless of registration history.
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

    public record EntityEntry(String entityId, Position position) {
        public EntityEntry withPosition(Position newPosition) {
            return new EntityEntry(entityId, newPosition);
        }
    }

    private final List<EntityEntry> dense = new ArrayList<>();
    private final Map<String, Integer> indexById = new HashMap<>();

    public synchronized void register(String entityId, Position position) {
        if (indexById.containsKey(entityId)) return; // idempotent
        indexById.put(entityId, dense.size());
        dense.add(new EntityEntry(entityId, position));
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
     * O(N + N log N) shallow copy SORTED BY entityId. REVIEWS M3 + Codex HIGH:
     * sort is mandatory, not reactive. External iteration order is stable
     * regardless of internal insertion / swap-and-pop sequence — necessary
     * for the GoldenTraceEquivalenceTest to be reproducible across the
     * Plan 04 cut. N ≤ 256 today; sort cost ~µs.
     */
    public synchronized List<EntityEntry> snapshot() {
        List<EntityEntry> copy = new ArrayList<>(dense);
        copy.sort(Comparator.comparing(EntityEntry::entityId));
        return copy;
    }

    public synchronized int size() { return dense.size(); }

    /** Test seam — clear all state. Used by integration tests with @SpringBootTest. */
    public synchronized void clearForTest() {
        dense.clear();
        indexById.clear();
    }
}
```

Notes:
- DO NOT use `parallelStream` (D-08, D-11).
- Use `synchronized(this)` not a `ReentrantLock` — matches `BotRegistry.drainDeaths` pattern.
- The `EntityEntry` record carries only `entityId` + `position` — Plan 04 consumers do `worldGrid.getCell(entry.position()).occupant()` to get the live `Entity` object. Storing the `Entity` here would require updates on every `Cell.withOccupant()` call (energy decay, etc.) and increase coupling — keep the registry minimal.
- **Sort-by-entityId** is mandatory (REVIEWS M3 / Codex H): without it, `snapshot()` order depends on the swap-and-pop history, which differs from `ConcurrentHashMap.values()` order, which means the post-Plan-04 digest cannot match the pre-Plan-04 baseline. Plan 03's harness change addresses pre-Plan-04 determinism on the broadcast side; this sort addresses post-Plan-04 determinism on the registry side.

2. Create `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` as a pure-JUnit unit test. Cover the 11 behaviour bullets above (including the new `snapshotIsSortedByEntityId` and `snapshotIsSortedByEntityIdAfterRemovals` from REVIEWS M3). For `concurrentRegisterIsSafe`, use a `CountDownLatch` to release 4 threads simultaneously and `join()` them, then assert `registry.size() == 400` and `registry.snapshot().size() == 400` and that the snapshot is sorted lexicographically.

3. Run the gate command in `<verify>`. Test must pass.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/main/java/com/paralife/engine/LiveEntityRegistry.java` exists.
    - `grep -c "@Component" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -c "public record EntityEntry" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 1
    - `grep -c "public synchronized.*register(String entityId" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -c "public synchronized.*unregister(String entityId" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -c "public synchronized.*snapshot()" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 1
    - `grep -cE "Comparator\\.comparing\\(.*entityId\\)" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS M3 — sort baked in)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 0
    - File `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` exists and contains tests `registerAddsEntry`, `unregisterIsO1AndDoesNotShift`, `snapshotIsShallowCopy`, `snapshotIsSortedByEntityId`, `snapshotIsSortedByEntityIdAfterRemovals`, `concurrentRegisterIsSafe` (verifiable via `grep`).
    - `./gradlew compileJava compileTestJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` exits 0
  </acceptance_criteria>
  <done>LiveEntityRegistry bean exists with O(1) register/unregister/updatePosition and snapshot() that returns a sort-by-entityId shallow copy (REVIEWS M3 / Codex H closed); concurrent-safety test passes; no parallelism in this class.</done>
</task>

<task type="auto">
  <name>Task 2: Wire lifecycle hooks at every entityId-introducing/removing/move site (REVIEWS H3); update DeathFinalizer back-compat ctor (REVIEWS M6)</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/ActionResolver.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (line 469 — `botRegistry.register(...)` call; hook AFTER)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (entire file — confirmed sites: line 84 unreg in finalizeParticleDeath, lines 102/103 unreg in finalizeBondedPairDeath; finalizeCompositeMemberDeath delegates and does NOT call unregisterByEntity directly per grep)
    - src/main/java/com/paralife/engine/SimulationEngine.java (CONFIRMED via grep: line 589 setEntity bonding, line 590 clearEntity, lines 651–652 composite-formation setEntity, lines 695/698/701 collapseToMember setEntity, lines 719/725 collapse-pair unregisterByEntity, line 973 handleMemberDeath unregisterByEntity, line 1051 revertToBondedPair setEntity, line 1098 dissolveToParticles setEntity, lines 1127/1136 processDeaths member-sweep unregisterByEntity, line 1139 member-death clearEntity)
    - src/main/java/com/paralife/engine/ActionResolver.java (CONFIRMED via grep: line 483 clearEntity move-from, line 497 setEntity move-to, lines 569/582/753 reproduce-child setEntity; re-grep for executeCompositeMovement / multi-member move loop)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean from Task 1 — confirm method signatures)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 130–134 — death-hook discipline; lines 289–299 — `BotRegistry` modification override)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4 — entity-list stale after death within same tick)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (H3 — full entityId-introducing-site audit; M6 — back-compat ctor)
  </read_first>
  <action>
1. **Modify** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:

   (a) Add field: `private final LiveEntityRegistry liveEntityRegistry;` next to the other registry fields (around line 101).
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the @Autowired constructor (line 118 onwards). Forward `null` from any alternate test constructors. Note: Plan 01 already added an `EligibleCellIndex` parameter in Wave 1; this plan extends the same constructor signature.
   (c) **Insert** immediately AFTER `botRegistry.register(session.getId(), entityId, new Position(x, y));` (~ line 469):
   ```java
   liveEntityRegistry.register(entityId, new Position(x, y));
   ```

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
   (e) `finalizeCompositeMemberDeath` does NOT call `botRegistry.unregisterByEntity` directly (confirmed via grep — only `worldGrid.clearEntity` at line 113 of the bonded-pair variant). The composite-member unregister happens in `SimulationEngine.handleMemberDeath` at line 973 — handle that in step 3.

3. **Modify** `src/main/java/com/paralife/engine/SimulationEngine.java`:

   (a) Add field `private final LiveEntityRegistry liveEntityRegistry;` and ctor parameter. Plan 01 already added `EligibleCellIndex` — extend the same constructor signature.
   (b) **Bond-formation hook (lines 588–590, REVIEWS H3):** before `worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair)` at line 589 — re-read source to confirm the predator/prey local variable names (likely `predator.entityId()` and `prey.entityId()`). After the new bondedPair id is constructed, insert:
   ```java
   liveEntityRegistry.unregister(predator.entityId());
   liveEntityRegistry.unregister(prey.entityId());
   liveEntityRegistry.register(bondedPair.entityId(), bond.primaryPos);
   ```

   (c) **Composite-formation hook (lines 651–652, REVIEWS H3):** before the two `setEntity` calls, after `member1` and `member2` are constructed (and `bp1` / `bp2` are the source bonded pairs in scope), insert:
   ```java
   liveEntityRegistry.unregister(bp1.entityId());
   liveEntityRegistry.unregister(bp2.entityId());
   liveEntityRegistry.register(member1.entityId(), cf.pos1());
   liveEntityRegistry.register(member2.entityId(), cf.pos2());
   ```
   Re-read SimulationEngine lines 640–660 to confirm exact local variable names (`bp1`, `bp2`, `cf` — composite-formation event — match what's in the source).

   (d) **collapseToMember hook (lines 695–725):** lines 719/725 already call `botRegistry.unregisterByEntity` for the bonded-pair primary/secondary. Beside each `botRegistry.unregisterByEntity(...)` at lines 719 and 725, insert:
   ```java
   liveEntityRegistry.unregister(bp.primaryEntityId());
   liveEntityRegistry.unregister(bp.secondaryEntityId());
   ```
   At each `worldGrid.setEntity(pos, ...)` site for the new CompositeMember (lines 695, 698, 701 — re-read to confirm which one(s) actually place the member; one is likely the primary placement, the others variants), insert:
   ```java
   liveEntityRegistry.register(member.entityId(), pos);
   ```
   (Match local variable names — `member`, `pos` may differ in source.)

   (e) **handleMemberDeath hook (line 973):** after `botRegistry.unregisterByEntity(id);` at line 973, insert:
   ```java
   liveEntityRegistry.unregister(id);
   ```

   (f) **processDeaths member-sweep hooks (lines 1127, 1136):** after each `botRegistry.unregisterByEntity(memberId);` at lines 1127 and 1136, insert:
   ```java
   liveEntityRegistry.unregister(memberId);
   ```

   (g) **revertToBondedPair hook (line 1051):** before `worldGrid.setEntity(pos, bondedPair)` at line 1051, with `member` (the dying CompositeMember) and `bondedPair` (the new BondedPair) in scope, insert:
   ```java
   liveEntityRegistry.unregister(member.entityId());
   liveEntityRegistry.register(bondedPair.entityId(), pos);
   ```

   (h) **dissolveToParticles hook (line 1098):** before `worldGrid.setEntity(pos, particle)` at line 1098, with `member` and `particle` in scope, insert:
   ```java
   liveEntityRegistry.unregister(member.entityId());
   liveEntityRegistry.register(particle.entityId(), pos);
   ```
   This loop runs per member of a dissolving composite — re-read to confirm whether dissolveToParticles iterates 1 or 2 members and whether the unregister of each member needs to happen before each particle.register.

   (i) **REVIEWS M6 — back-compat ctor:** SimulationEngine has a back-compat 9-arg / 13-arg ctor (around lines 172–202) that internally constructs `new DeathFinalizer(...)`. After step 2(b) added `LiveEntityRegistry` to DeathFinalizer's signature (and Plan 01 added `EligibleCellIndex`), update that internal `new DeathFinalizer(...)` call to pass both: e.g., `new DeathFinalizer(worldGrid, botRegistry, this.buffRegistry, compositeRegistry, this.hooks, this, eligibleCellIndex, liveEntityRegistry)` — match the new positional order of the constructor. If the back-compat ctor doesn't already receive `LiveEntityRegistry` itself, add it as a parameter or construct a fresh `LiveEntityRegistry` instance. Re-read SimulationEngine lines 172–202 before patching.

4. **Modify** `src/main/java/com/paralife/engine/ActionResolver.java` — **HARD REQUIREMENT** (not deferrable):

   (a) Add field: `private final LiveEntityRegistry liveEntityRegistry;` (Plan 01 added `EligibleCellIndex` — same plan-02 commit extends the constructor with this second new parameter).
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the @Autowired constructor.
   (c) **resolveMove (lines 483 + 497):** after `worldGrid.setEntity(target.x(), target.y(), placed);` at line 497, insert:
   ```java
   liveEntityRegistry.updatePosition(entityId, target);
   ```
   Match the actual local variable names (`entityId` may be `ra.bot.entityId()` or `placed.entityId()` — re-read to confirm).
   (d) **resolveReproduce children:**
       - line 569 — `setEntity(target, child)` primary child; insert after:
         ```java
         liveEntityRegistry.register(child.entityId(), target);
         ```
       - line 582 — `setEntity(bonusTarget, bonusChild)` bonus child; insert after:
         ```java
         liveEntityRegistry.register(bonusChild.entityId(), bonusTarget);
         ```
       - line 753 — `setEntity(target, child)` (variant — composite or alt); insert the same:
         ```java
         liveEntityRegistry.register(child.entityId(), target);
         ```
   (e) **executeCompositeMovement (rigid-body multi-member move, REVIEWS H3 — claude H3 specifically calls this out):**
       Re-grep `executeCompositeMovement\\|composite.*move\\|rigid.*body` in ActionResolver. If a method exists that translates an N-member composite by a single delta and calls `setEntity` per member, after each `worldGrid.setEntity(newMemberPos, ...)` call, insert:
       ```java
       liveEntityRegistry.updatePosition(member.entityId(), newMemberPos);
       ```
       If no such method exists in ActionResolver (it may live in `SimulationEngine` or as a sub-step of resolveMove), add an explicit comment in the source where the rigid-body translation happens and the registry hook lands there.

   **Do NOT defer this hook to Plan 03 or Plan 04.** Plan 04 reads `EntityEntry.position()` directly during tick-handler iteration; if `updatePosition` is missing on any move path (single-entity OR composite-rigid-body), the recorded position goes stale and Plan 04's refactor breaks (entities appear to occupy their pre-move cell from the registry's perspective).

   **Note for the executor:** Re-read `SimulationEngine.collapseToMember`, `SimulationEngine.processInteractions` bonding/composite-formation regions, and `ActionResolver.resolveMove`/`resolveReproduce`/composite-move methods before patching. Variable names and method names in PATTERNS.md/RESEARCH.md may not be exact — the canonical source is the `.java` file.

5. Run `./gradlew test` — full regression suite. No tick-handler iteration logic changes in this plan; existing tests should remain green. Any failure indicates a hook landed in the wrong site or an idempotency assumption was violated.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.register" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "liveEntityRegistry.unregister" src/main/java/com/paralife/engine/DeathFinalizer.java` >= 3 (REVIEWS H3 — particle + 2 bonded-pair)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1 (constructor injection at minimum)
    - `grep -c "liveEntityRegistry.unregister\\|liveEntityRegistry.register\\|liveEntityRegistry.updatePosition" src/main/java/com/paralife/engine/SimulationEngine.java` >= 14 (REVIEWS H3 — bond-formation 3 ops; composite-formation 4 ops; collapseToMember 3 ops; handleMemberDeath 1; processDeaths 2; revertToBondedPair 2; dissolveToParticles ≥2; total ≥14)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -c "liveEntityRegistry.updatePosition" src/main/java/com/paralife/engine/ActionResolver.java` >= 1 (HARD REQUIREMENT — Plan 04 depends on this)
    - `grep -c "liveEntityRegistry.register" src/main/java/com/paralife/engine/ActionResolver.java` >= 3 (REVIEWS H3 — reproduce children at lines 569/582/753)
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/BotRegistry.java` == 0 (BotRegistry NOT coupled to LiveEntityRegistry; hook lives in DeathFinalizer + SimulationEngine per PATTERNS.md)
    - `grep -nE "new DeathFinalizer\\(" src/main/java/com/paralife/engine/SimulationEngine.java | wc -l` matches the number of internal back-compat instantiations; each must include the new `LiveEntityRegistry` argument (REVIEWS M6) — verify by reading the lines and confirming no compile error.
    - `./gradlew compileJava compileTestJava` exits 0 (back-compat ctor wired correctly — REVIEWS M6 closed)
    - `./gradlew test` exits 0 (full regression — 166+ existing tests remain green; no observable behaviour change in this task)
  </acceptance_criteria>
  <done>LiveEntityRegistry receives register/unregister/updatePosition events at EVERY entity lifecycle site (registration, particle-death, bonded-pair-death, member-death, bond-formation, composite-formation, collapseToMember, revertToBondedPair, dissolveToParticles, processDeaths member-sweep, AND move via ActionResolver including reproduce-children and composite rigid-body movement) — REVIEWS H3 closed. DeathFinalizer back-compat ctor in SimulationEngine updated — REVIEWS M6 closed. BotRegistry is not coupled to LiveEntityRegistry. Full regression suite passes; Plan 04 can now consume `liveEntityRegistry.snapshot()` and trust both `entry.position()` and the entity-set membership are current.</done>
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
| T-19-05 | Tampering | LiveEntityRegistry stale after death within tick (Pitfall 4) | mitigate | DeathFinalizer + SimulationEngine direct unregisterByEntity sites all hook liveEntityRegistry.unregister synchronously (REVIEWS H3). By @Order(50) snapshot is post-death. Verified by Plan 03 GoldenTraceEquivalenceTest. |
| T-19-05a | Tampering | LiveEntityRegistry missing entityId-introducing site (REVIEWS H3) | mitigate | Bond-formation, composite-formation, reproduce-children, collapseToMember, revertToBondedPair, dissolveToParticles, executeCompositeMovement all wired (REVIEWS H3 closed). Acceptance grep counter ≥14 enforces coverage. |
| T-19-05b | Tampering | snapshot() iteration order divergence across Plan 04 cut | mitigate | sort-by-entityId baked into snapshot() (REVIEWS M3); external order is independent of internal swap-and-pop history. |
| T-19-06 | Information disclosure | EntityEntry contains entityId + position | accept | Server-internal data; never echoed to clients. |
| T-19-07 | DoS | Concurrent register storm during burst connect | accept | Per-call critical section is sub-µs; Phase 17 admission gate caps the burst rate. |
| T-19-08 | Compile-error regression | DeathFinalizer ctor change breaks SimulationEngine back-compat ctor | mitigate | REVIEWS M6 closed — back-compat `new DeathFinalizer(...)` updated; gradle compile gate enforces. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` — Wave 0 unit tests green incl. sort-by-entityId after removals (REVIEWS M3).
- `./gradlew test` — full regression remains green (no behaviour change in this plan).
- `grep -rn "liveEntityRegistry" src/main/java/com/paralife/` shows hooks at every site enumerated in REVIEWS H3.
- `BotRegistry.java` has no reference to `LiveEntityRegistry` (per PATTERNS.md analog evidence).
- DeathFinalizer back-compat construction in SimulationEngine receives `LiveEntityRegistry` (REVIEWS M6).
</verification>

<success_criteria>
- LiveEntityRegistry is registered as a Spring bean and ready for Plan 04 to consume.
- snapshot() returns sort-by-entityId order — stable across Plan 04 cut (REVIEWS M3 closed).
- All register/unregister/updatePosition hooks are wired at every entityId-introducing/removing/move site (REVIEWS H3 closed) — DeathFinalizer + SimulationEngine + WorldWebSocketHandler + ActionResolver. No double-hooks, no missed sites, no deferred sites.
- DeathFinalizer back-compat ctor in SimulationEngine updated for new ctor signature (REVIEWS M6 closed).
- Existing 166+ tests remain green (this plan introduces NO observable behaviour change).
- Single-threaded mutation invariant preserved (D-08, D-11): all writers run on either the single tick thread or the WS inbound thread, both protected by `synchronized(this)`.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-02-SUMMARY.md`.
</output>
</content>
