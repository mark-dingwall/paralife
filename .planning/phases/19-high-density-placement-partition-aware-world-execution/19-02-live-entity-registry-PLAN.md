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
    - "Every live entity (Particle, BondedPair, CompositeMember offspring) appears exactly once in LiveEntityRegistry while alive."
    - "snapshot() returns a deterministic order (insertion order) and is stable for the duration of a tick handler — modifications during the tick do not mutate the snapshot."
    - "Death cleanup removes the entityId from LiveEntityRegistry at every site where BotRegistry.unregisterByEntity is called — primarily inside DeathFinalizer."
    - "Registration in WorldWebSocketHandler.handleRegister adds the new entityId to LiveEntityRegistry alongside the existing botRegistry.register call."
    - "Movement in ActionResolver.resolveMove updates the entity's recorded position via liveEntityRegistry.updatePosition — Plan 04 consumers can rely on entry.position() being current."
    - "register/unregister/updatePosition are O(1) (sparse-set: dense list + entityId→index map, swap-and-pop)."
    - "No tick-handler iteration logic is changed in this plan — Plan 04 owns the iteration refactor; this plan only stands up the registry + lifecycle hooks."
  artifacts:
    - path: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      provides: "@Component sparse-set of EntityEntry(entityId, position) — register, unregister, updatePosition, snapshot; deterministic insertion-order iteration; thread-safe via synchronized."
      min_lines: 90
    - path: src/test/java/com/paralife/engine/LiveEntityRegistryTest.java
      provides: "Unit tests: register/unregister O(1) + idempotent; snapshot is shallow-copy; insertion-order determinism; updatePosition mutates in place; concurrent register from multiple threads still yields a consistent snapshot."
      min_lines: 80
  key_links:
    - from: src/main/java/com/paralife/engine/DeathFinalizer.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.unregister(entityId) called immediately after botRegistry.unregisterByEntity(entityId) in finalizeParticleDeath, finalizeBondedPairDeath, finalizeCompositeMemberDeath"
      pattern: "liveEntityRegistry\\.unregister"
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.register(entityId, pos) called immediately after botRegistry.register(...) in handleRegister"
      pattern: "liveEntityRegistry\\.register"
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "collapseToMember (line ~725) — when a BondedPair collapses to a CompositeMember, unregister the pair and register the member"
      pattern: "liveEntityRegistry\\.(register|unregister|updatePosition)"
    - from: src/main/java/com/paralife/engine/ActionResolver.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "resolveMove — after a successful move (clearEntity(oldPos) + setEntity(newPos)), call liveEntityRegistry.updatePosition(entityId, newPos)"
      pattern: "liveEntityRegistry\\.updatePosition"
---

<objective>
Stand up `LiveEntityRegistry` — a sparse-set bean whose iteration order is deterministic and whose add/remove are O(1) — and wire its lifecycle hooks at every entity-creation, entity-death, and entity-move site. This plan is **infrastructure only**: it does NOT change tick-handler iteration. Plan 04 consumes the registry; the consumer wave depends on this one.

Per PATTERNS.md analog evidence (overriding the CONTEXT.md mention of `BotRegistry` as the death-hook site): the death-cleanup hook lands in `DeathFinalizer`, which was created cycle-4 specifically to centralise cross-bean death cleanup. `BotRegistry.unregisterByEntity` is **not** modified — the wiring is at the call sites.

**Wave assignment:** Plan 02 sits in Wave 2 and depends on Plan 01. Both plans modify `WorldWebSocketHandler.java`'s constructor (Plan 01 adds an `EligibleCellIndex` parameter; this plan adds a `LiveEntityRegistry` parameter). Sequencing avoids constructor-merge conflicts.

Purpose: SCALE-07 prerequisite. Tick handlers currently grid-scan O(65,536) cells to find entities. `LiveEntityRegistry.snapshot()` will replace that with O(N) entity iteration — but Plan 04 does that. This plan only ensures the data structure exists and stays correct under register/move/death/composite-collapse lifecycle events.
Output: `LiveEntityRegistry` bean + lifecycle hooks at four call sites (register, move, death, composite-collapse) + Wave 0 unit tests + zero behavioural change to existing tick handlers.
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
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/engine/DeathFinalizer.java
@src/main/java/com/paralife/engine/SimulationEngine.java
@src/main/java/com/paralife/engine/ActionResolver.java
@src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
@src/main/java/com/paralife/world/Position.java

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
public void finalizeParticleDeath(int x, int y, Particle p) { ... botRegistry.unregisterByEntity(id); ... }   // line 81–93, hook AFTER line 84
public void finalizeBondedPairDeath(int x, int y, BondedPair bp) { ... botRegistry.unregisterByEntity(primaryId); botRegistry.unregisterByEntity(secondaryId); ... }   // line 97–110, hook AFTER lines 102 and 103
public void finalizeCompositeMemberDeath(int x, int y, CompositeMember cm) { ... }   // line 123–138 (delegates to recursive variant; check whether it calls unregisterByEntity directly or via SimulationEngine.handleMemberDeath)
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
    public List<EntityEntry> snapshot();                                // O(N) shallow copy; deterministic insertion order
    public int size();
    public void clearForTest();                                          // test seam
}
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (existing call site for hook injection):
```java
botRegistry.register(session.getId(), entityId, new Position(x, y));   // line 469 — hook IMMEDIATELY AFTER
```

From src/main/java/com/paralife/engine/SimulationEngine.java (composite-collapse site):
```java
// line ~725 — collapseToMember(BondedPair, CompositeMember): pair becomes a single member.
// Hook: liveEntityRegistry.unregister(primaryId); liveEntityRegistry.unregister(secondaryId); liveEntityRegistry.register(memberId, pos);
// Re-read the source around line 725 to get exact variable names.
```

From src/main/java/com/paralife/engine/ActionResolver.java (movement site — HARD REQUIREMENT):
```java
// resolveMove(...) — after the move succeeds (typically a worldGrid.clearEntity(oldPos) followed
// by worldGrid.setEntity(newPos, entity)), call:
//     liveEntityRegistry.updatePosition(entityId, newPos);
// This is mandatory in Plan 02 — Plan 04 relies on EntityEntry.position() being current.
// Re-read ActionResolver.resolveMove to get exact variable names and the post-move hook site.
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create LiveEntityRegistry sparse-set bean + Wave 0 unit test</name>
  <files>src/main/java/com/paralife/engine/LiveEntityRegistry.java, src/test/java/com/paralife/engine/LiveEntityRegistryTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (target — confirm absent)
    - src/main/java/com/paralife/engine/BotRegistry.java (lines 1–60 — class header, record pattern, ConcurrentHashMap discipline; lines 60–110 — register/unregister)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 2 — live-entity registry design; §Pitfall 6 — deterministic ordering required for golden-trace test)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 88–134 — class shell + insertion/removal pattern + snapshot pattern)
    - src/test/java/com/paralife/engine/BotRegistryTest.java (analog test pattern if it exists; otherwise BuffRegistryTest as fallback analog)
    - src/main/java/com/paralife/world/Position.java
  </read_first>
  <behavior>
    - registerAddsEntry: register("e-1", Pos(3,4)) → size==1; snapshot contains EntityEntry("e-1", Pos(3,4)).
    - registerIsIdempotent: register("e-1", ...) twice → size==1.
    - unregisterRemoves: register then unregister → size==0; snapshot empty.
    - unregisterIsIdempotent: unregister non-existent id → no exception; size unchanged.
    - unregisterIsO1AndDoesNotShift: register 3 entries, unregister middle one, snapshot still contains the other two (in original insertion order minus the removed entry; swap-and-pop produces an order where the last entry takes the removed slot, but `snapshot()` returns the dense list in current dense order — see "snapshot order" below).
    - snapshotIsShallowCopy: snapshot is independent — registering after taking the snapshot does not mutate the captured list.
    - insertionOrderDeterminism: register(a), register(b), register(c) — snapshot order matches insertion order until a removal happens. After unregister(b), snapshot is [a, c] (b's slot took the last entry, which was c, then size shrank: dense=[a,c]).
    - updatePositionMutatesEntry: register("e-1", Pos(0,0)); updatePosition("e-1", Pos(5,5)); snapshot contains EntityEntry("e-1", Pos(5,5)).
    - updatePositionMissingIsNoop: updatePosition on non-existent id → no exception, size unchanged.
    - concurrentRegisterIsSafe: 4 threads each register 100 unique ids; final size == 400; snapshot contains all 400.
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
 * O(1) register, unregister (swap-and-pop), updatePosition, size. Iteration
 * via {@link #snapshot()} returns a shallow copy — stable for the duration
 * of a tick handler invocation regardless of concurrent register/death.
 *
 * <p>Determinism (D-09 / RESEARCH §Pitfall 6): insertion order is preserved
 * as long as no removal happens between registrations. After a removal, the
 * removed slot takes the last-inserted entry (swap-and-pop). Two runs with
 * the same seed + same WS-arrival order yield byte-identical snapshot order
 * because WS inbound is single-threaded per session and registration arrival
 * order is itself deterministic.
 *
 * <p>Single-threaded mutation invariant (D-08, D-11) is unaffected: this
 * registry is read by tick handlers, written from registration (WS thread),
 * death (tick handler thread), and composite collapse (tick handler thread).
 * All public methods synchronize on this bean — sub-microsecond critical
 * sections. NO parallelStream anywhere.
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

    /** O(N) shallow copy. N ≤ 256 today; cost ~2µs. Stable for tick-handler iteration. */
    public synchronized List<EntityEntry> snapshot() {
        return new ArrayList<>(dense);
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

2. Create `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` as a pure-JUnit unit test. Cover the 10 behaviour bullets above. For `concurrentRegisterIsSafe`, use a `CountDownLatch` to release 4 threads simultaneously and `join()` them, then assert `registry.size() == 400` and `registry.snapshot().size() == 400` and that the snapshot contains all 400 unique ids.

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
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 0
    - File `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` exists and contains tests `registerAddsEntry`, `unregisterIsO1AndDoesNotShift`, `snapshotIsShallowCopy`, `insertionOrderDeterminism`, `concurrentRegisterIsSafe` (verifiable via `grep`).
    - `./gradlew compileJava compileTestJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` exits 0
  </acceptance_criteria>
  <done>LiveEntityRegistry bean exists with O(1) register/unregister/updatePosition/snapshot; insertion-order determinism documented and tested; concurrent-safety test passes; no parallelism in this class.</done>
</task>

<task type="auto">
  <name>Task 2: Wire lifecycle hooks at registration, move, death, and composite-collapse call sites</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/ActionResolver.java</files>
  <read_first>
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (line 469 — `botRegistry.register(...)` call; hook AFTER)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (entire file — confirm exact line numbers for all `botRegistry.unregisterByEntity` calls; PATTERNS.md cites lines 84, 102, 103 — re-verify before patching)
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 700–740 — `collapseToMember`; PATTERNS.md cites line 725 as the BondedPair → CompositeMember transition site; re-verify)
    - src/main/java/com/paralife/engine/ActionResolver.java (entire `resolveMove` method — find the exact post-success site where `worldGrid.setEntity(newPos, entity)` returns true; the `updatePosition` hook lands immediately after)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean from Task 1 — confirm method signatures)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 130–134 — death-hook discipline; lines 289–299 — `BotRegistry` modification override)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4 — entity-list stale after death within same tick; this is the pitfall this task closes)
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
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the constructor (around line 64). Assign in body.
   (c) In `finalizeParticleDeath` (line 81): immediately AFTER the `botRegistry.unregisterByEntity(id);` call (line 84), insert:
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
   (e) In `finalizeCompositeMemberDeath` (lines 123 and 138): re-read those methods. If either calls `botRegistry.unregisterByEntity` directly, hook `liveEntityRegistry.unregister(...)` alongside. If the actual unregister happens in `SimulationEngine.handleMemberDeath`, hook there instead — the executor must follow the existing recipe and not introduce a duplicate hook. Confirm by `grep -n "unregisterByEntity" src/main/java/com/paralife/`.

3. **Modify** `src/main/java/com/paralife/engine/SimulationEngine.java`:

   (a) If the SimulationEngine constructor does not already inject `LiveEntityRegistry`, add it (final field + ctor parameter).
   (b) At `collapseToMember` (PATTERNS.md cites line ~725): after the BondedPair is removed from the world and the CompositeMember is placed, add hooks **using the exact variable names from the existing source** (re-read before patching):
   ```java
   liveEntityRegistry.unregister(/* primaryId — match local variable */);
   liveEntityRegistry.unregister(/* secondaryId — match local variable */);
   liveEntityRegistry.register(/* memberId */, /* memberPos */);
   ```
   If `collapseToMember` only handles the unregister-pair half and the register-member half lives elsewhere, re-search for `setEntity` / `trySetEntity` calls that place a `CompositeMember` and hook `liveEntityRegistry.register(memberId, pos)` at the placement site.

4. **Modify** `src/main/java/com/paralife/engine/ActionResolver.java` — **HARD REQUIREMENT** (not deferrable):

   (a) Add field: `private final LiveEntityRegistry liveEntityRegistry;`
   (b) Add `LiveEntityRegistry liveEntityRegistry` parameter to the @Autowired constructor. Assign in body.
   (c) Locate `resolveMove` (or the equivalent move-resolution method in ActionResolver). After the successful move — i.e. after the `worldGrid.clearEntity(oldPos)` + `worldGrid.setEntity(newPos, entity)` pair (or whatever the existing post-move write pattern is) — insert:
   ```java
   liveEntityRegistry.updatePosition(entityId, newPos);
   ```
   The hook must fire on every successful move, regardless of which entity subtype moved (Particle, BondedPair, CompositeMember). Re-read `resolveMove` to confirm the exact post-write site and variable names.

   **Do NOT defer this hook to Plan 03 or Plan 04.** Plan 04 reads `EntityEntry.position()` directly during tick-handler iteration; if `updatePosition` is missing, the recorded position goes stale on every move and Plan 04's refactor breaks (entities appear to occupy their pre-move cell from the registry's perspective).

   **Note for the executor:** Re-read `SimulationEngine.collapseToMember` and `ActionResolver.resolveMove` before patching. Variable names and method names in PATTERNS.md/RESEARCH.md may not be exact — the canonical source is the `.java` file.

5. Run `./gradlew test` — full regression suite. No tick-handler iteration logic changes in this plan; existing tests should remain green. Any failure indicates a hook landed in the wrong site or an idempotency assumption was violated.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.register" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -c "liveEntityRegistry.unregister" src/main/java/com/paralife/engine/DeathFinalizer.java` >= 3 (one per finalize* method's unregisterByEntity call)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1 (constructor injection at minimum; one or more lifecycle calls)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -c "liveEntityRegistry.updatePosition" src/main/java/com/paralife/engine/ActionResolver.java` >= 1 (HARD REQUIREMENT — Plan 04 depends on this)
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/BotRegistry.java` == 0 (BotRegistry NOT coupled to LiveEntityRegistry; hook lives in DeathFinalizer per PATTERNS.md)
    - `./gradlew test` exits 0 (full regression — 166+ existing tests remain green; no observable behaviour change in this task)
  </acceptance_criteria>
  <done>LiveEntityRegistry receives register/unregister/updatePosition events at every entity lifecycle site (registration, particle death, bonded-pair death, composite-collapse, AND move via ActionResolver). DeathFinalizer is the death-cleanup choke point — BotRegistry is not coupled to LiveEntityRegistry. ActionResolver.resolveMove updates the recorded position on every successful move (hard requirement for Plan 04). Full regression suite passes; Plan 04 can now consume `liveEntityRegistry.snapshot()` and trust `entry.position()` is current.</done>
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
| T-19-05 | Tampering | LiveEntityRegistry stale after death within tick (Pitfall 4) | mitigate | DeathFinalizer hook fires synchronously inside the @Order(10) death pass — by @Order(50) snapshot is post-death. Verified by Plan 04 golden-trace. |
| T-19-06 | Information disclosure | EntityEntry contains entityId + position | accept | Server-internal data; never echoed to clients. |
| T-19-07 | DoS | Concurrent register storm during burst connect | accept | Per-call critical section is sub-µs; Phase 17 admission gate caps the burst rate. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` — Wave 0 unit tests green.
- `./gradlew test` — full regression remains green (no behaviour change in this plan).
- `grep -rn "liveEntityRegistry" src/main/java/com/paralife/` shows hooks at: WorldWebSocketHandler.handleRegister, DeathFinalizer.finalize*, SimulationEngine.collapseToMember, ActionResolver.resolveMove.
- BotRegistry.java has no reference to LiveEntityRegistry (per PATTERNS.md analog evidence).
</verification>

<success_criteria>
- LiveEntityRegistry is registered as a Spring bean and ready for Plan 04 to consume.
- All register/unregister/updatePosition hooks are wired at the correct DeathFinalizer + WS + composite-collapse + ActionResolver sites — no double-hooks, no missed sites, no deferred sites.
- Existing 166+ tests remain green (this plan introduces NO observable behaviour change).
- Single-threaded mutation invariant preserved (D-08, D-11): all writers run on either the single tick thread or the WS inbound thread, both protected by `synchronized(this)`.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-02-SUMMARY.md`.
</output>
</content>
</invoke>