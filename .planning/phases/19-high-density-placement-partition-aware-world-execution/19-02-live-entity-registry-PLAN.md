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
    - "snapshot() returns a list sorted by ROW-MAJOR linear index `position.x() * gridConfig.height() + position.y()` — matches the pre-Plan-04 `for(x){for(y)}` grid-scan order so `Collections.shuffle(list, simRng)` output is byte-identical across the cut (REVIEWS HIGH-1)."
    - "EntityEntry has `Optional<String> sessionId` field. Per CONSENSUS-H1 OPTION B (USER-LOCKED): TickBroadcaster is NOT migrated in Phase 19. The sessionId field is **vestigial for this phase** — populated as `Optional.of(sessionId)` at WS handshake registration where it is trivially available, and `Optional.empty()` for all server-internal creations (bonding, composite formation, reproduce-children, collapse, dissolve, revert). Documented in Javadoc: 'TickBroadcaster does not consume sessionId in Phase 19; field reserved for Phase 20.1+ broadcaster migration.'"
    - "`LiveEntityRegistry.register()` throws `IllegalStateException` on conflicting re-register (different sessionId for already-registered entityId); silent drop is forbidden (REVIEWS MEDIUM-3)."
    - "Death cleanup: every `botRegistry.unregisterByEntity(...)` call site in DeathFinalizer (lines 84, 102, 103) and SimulationEngine direct unregister sites (lines 719, 725, 973, 1127, 1136) is paired with `liveEntityRegistry.unregister(...)` IMMEDIATELY after."
    - "Registration in `WorldWebSocketHandler.handleRegister` adds the new entityId + sessionId to LiveEntityRegistry alongside `botRegistry.register`; `cleanupByEntityId` and `cleanupBot` paths also unregister from LiveEntityRegistry (REVIEWS MEDIUM-6 / Codex)."
    - "Every entityId-introducing site is wired (REVIEWS H3) — verified against current source (lines re-derived; see exact list in <interfaces>)."
    - "ActionResolver.resolveMove updates the registry via `liveEntityRegistry.updatePosition` with the correct entityId per dispatch path (Particle → `ra.particle.id()`; BondedPair → bonded-pair occupant id; CompositeMember → `member.id()` via `executeCompositeMovement`'s per-member loop). Energy-only writes do NOT call updatePosition (REVIEWS MED-3)."
    - "register/unregister/updatePosition are O(1) (sparse-set: dense ArrayList + entityId→index map, swap-and-pop)."
    - "DeathFinalizer back-compat ctor in SimulationEngine (line 173) is updated to forward LiveEntityRegistry. Every test-side manual instantiation is enumerated and forwarded (REVIEWS MED-2)."
    - "LiveEntityRegistryInvariantTest scripts AT-REST + post-death + post-bond-formation + post-composite-formation lifecycle and asserts `liveEntityRegistry.snapshot()` exactly matches the set of non-rock/non-nutrient occupied grid cells. **Post-bond-formation and post-composite-formation scenarios are MANDATORY — no @Disabled escape (REVIEWS MEDIUM-4).**"
    - "No tick-handler iteration logic is changed in this plan — Plan 04 owns the iteration refactor (and excludes TickBroadcaster per OPTION B)."
  artifacts:
    - path: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      provides: "@Component sparse-set of EntityEntry(entityId, position, Optional<String> sessionId); register/unregister/updatePosition; snapshot() ROW-MAJOR-sorted (REVIEWS HIGH-1); register() throws IllegalStateException on conflicting re-register (REVIEWS MEDIUM-3). Javadoc: row-major sort is a Phase 19 compatibility shim (REVIEWS R2-14)."
      min_lines: 130
    - path: src/test/java/com/paralife/engine/LiveEntityRegistryTest.java
      provides: "Unit tests: register/unregister O(1) + idempotent + IllegalStateException on conflicting sessionId; snapshot is shallow-copy and ROW-MAJOR-sorted before AND after removals; updatePosition preserves sessionId; concurrent-register safety."
      min_lines: 130
    - path: src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java
      provides: "@SpringBootTest scenarios — registration AT-REST, post-death, post-bond-formation, post-composite-formation. After each step asserts (1) registry positions == set of non-rock/non-nutrient occupied grid cells. **Bond + composite scenarios are MANDATORY (REVIEWS MEDIUM-4).** sessionIdAgreesWithBotRegistry assertion is DROPPED (TickBroadcaster not migrated in Phase 19 per OPTION B)."
      min_lines: 180
  key_links:
    - from: src/main/java/com/paralife/engine/DeathFinalizer.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "liveEntityRegistry.unregister(entityId) called immediately after botRegistry.unregisterByEntity in finalizeParticleDeath (line 84) and finalizeBondedPairDeath (lines 102, 103)"
      pattern: "liveEntityRegistry\\.unregister"
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "Hooks at: bond-formation (lines 589/590 — unregister predator+prey, register bondedPair); composite-formation (lines 651/652 — unregister bp1+bp2, register member1+member2); updateBotRegistryForFormation unregister sites (lines 719, 725 — inside composite-formation method called at lines 666/667 from attemptCompositeFormation); handleMemberDeath shared cleanup (line 977 unregisterByEntity); processDeaths member sweeps (lines 1127, 1136); revertToBondedPair (line 1051 — unregister member, register bondedPair); dissolveToParticles (line 1098 — unregister member, register particle); checkPanicZone clearEntity site (line 1139)"
      pattern: "liveEntityRegistry\\.(register|unregister|updatePosition)"
    - from: src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "handleRegister: liveEntityRegistry.register(entityId, pos, Optional.of(session.getId())) after botRegistry.register; cleanupByEntityId / cleanupBot: liveEntityRegistry.unregister(entityId) (REVIEWS MEDIUM-6)"
      pattern: "liveEntityRegistry\\.(register|unregister)"
    - from: src/main/java/com/paralife/engine/ActionResolver.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "resolveMove: updatePosition with per-path entityId; resolveReproduce children at lines 569/582/753: register with Optional.empty(); executeCompositeMovement (line 968 setEntity per member): updatePosition(member.id(), target) per moved member"
      pattern: "liveEntityRegistry\\.(updatePosition|register)"
---

<objective>
Stand up `LiveEntityRegistry` — a sparse-set bean whose iteration order is deterministic (ROW-MAJOR sort baked in, REVIEWS HIGH-1) and whose add/remove/updatePosition are O(1) — and wire its lifecycle hooks at every entity-creation, entity-death, entity-move, and composite-restructure site. **EntityEntry carries `Optional<String> sessionId` as a vestigial field for this phase** (REVIEWS CONSENSUS-H1 OPTION B, USER-LOCKED — TickBroadcaster migration is deferred). This plan is **infrastructure only**: it does NOT change tick-handler iteration. Plan 04 consumes the registry for SimulationEngine + EnvironmentEngine only; TickBroadcaster keeps `botRegistry.getAllBots()`.

**REVIEWS Round 2 + Round 3 consensus fixes encoded in plan body:**

- **CONSENSUS-H1 — OPTION B LOCKED BY USER:** TickBroadcaster is NOT migrated in Phase 19. `EntityEntry.sessionId` becomes vestigial: populated only at WS-handshake registration (`Optional.of(session.getId())`); all server-internal creations use `Optional.empty()`. NO `botRegistry.getSessionForEntity(...)` lookup at bond-formation. Javadoc explicitly notes "TickBroadcaster does not consume sessionId in Phase 19 — reserved for Phase 20.1+".
- **CONSENSUS-H5 — Package paths verified:** TickEvent is at `com.paralife.engine.TickEvent` (not websocket). LiveEntityRegistryInvariantTest imports `com.paralife.engine.TickEvent`. Test imports re-grepped before authoring.
- **MEDIUM-2 — Line numbers re-derived from current source via grep.** Real structural sites (verified by `grep -n "worldGrid\\.(set|clear)Entity\\|botRegistry\\.unregisterByEntity"`):
  - Line 589 setEntity bond-formation (predator+prey → BondedPair)
  - Line 590 clearEntity (secondary cell)
  - Lines 651, 652 setEntity composite-formation
  - Lines 719, 725 — `botRegistry.unregisterByEntity` inside `updateBotRegistryForFormation` (composite-formation primary/secondary unreg)
  - Line 973 — `botRegistry.unregisterByEntity` in `handleMemberDeath` shared cleanup
  - Line 977 — `worldGrid.clearEntity` (handleMemberDeath)
  - Line 1051 setEntity in `revertToBondedPair`
  - Line 1098 setEntity in `dissolveToParticles`
  - Lines 1127, 1136 — `botRegistry.unregisterByEntity` in `checkPanicZone`
  - Line 1139 clearEntity in `checkPanicZone`
  - **Lines 695, 698, 701 are inside `applyDeltaToOccupant` (energy-only `withEnergy`) — NOT structural; NO LiveEntityRegistry hook.**
- **MEDIUM-3 — `register()` throws on conflict.** If an entityId is already registered with a different `sessionId`, throw `IllegalStateException`. Idempotent on identical re-register.
- **MEDIUM-4 — Bond + composite invariant scenarios MANDATORY.** No @Disabled escape.
- **MEDIUM-6 (Codex) — `cleanupByEntityId` / `cleanupBot` lifecycle hooks.** Both paths in `WorldWebSocketHandler` clear grid cells AND must call `liveEntityRegistry.unregister(entityId)`.
- **R2-14 — Row-major sort Javadoc shim note.** Add explicit "Phase 19 compatibility shim; cost ~10µs at N=1000; Phase 21 may revisit".
- **R2-15 — TickEvent ctor pre-flight.** `TickEvent` is a record `(long tickNumber, Instant timestamp)` with convenience ctor `(long tickNumber)`; `new TickEvent(1L)` is valid.
- **HIGH-3 invariant test scope adjustment:** with OPTION B, `sessionIdAgreesWithBotRegistry` no longer applies (TickBroadcaster doesn't read sessionId). The mandatory tests are `registryMatchesGridOccupantsAtRest`, `registryMatchesGridOccupantsAfterDeath`, `registryMatchesGridOccupantsAfterBondFormation`, `registryMatchesGridOccupantsAfterCompositeFormation`. The first two catch most lifecycle bugs; the last two catch the structural composite/bond hooks specifically.

**Wave assignment:** Plan 02 sits in Wave 2 and depends on Plan 01.
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
@src/main/java/com/paralife/engine/TickEvent.java
@src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
@src/main/java/com/paralife/world/Position.java
@src/main/java/com/paralife/world/Entity.java
@src/main/java/com/paralife/world/GridConfig.java

<interfaces>
<!-- VERIFIED via grep -n against current source. -->

From src/main/java/com/paralife/engine/BotRegistry.java:
```java
public record BotState(String sessionId, String entityId, Position position) { }
public void register(String sessionId, String entityId, Position position);
public void unregisterByEntity(String entityId);
public Optional<String> getSessionForEntity(String entityId);
public Collection<BotState> getAllBots();
```

From src/main/java/com/paralife/world/GridConfig.java:
```java
@ConfigurationProperties(prefix = "paralife.world")
public record GridConfig(int width, int height) { ... }   // line 10
```

From src/main/java/com/paralife/engine/TickEvent.java (REVIEWS CONSENSUS-H5 + R2-15 verified):
```java
package com.paralife.engine;   // NOT websocket
public record TickEvent(long tickNumber, Instant timestamp) {
    public TickEvent(long tickNumber) { this(tickNumber, Instant.now()); }   // convenience ctor — `new TickEvent(1L)` works
}
```

From src/main/java/com/paralife/engine/DeathFinalizer.java (RE-VERIFIED line numbers):
```java
public DeathFinalizer(WorldGrid worldGrid, ...) { ... }   // line 63 — ctor extension point
public void finalizeParticleDeath(int x, int y, Particle p) {
    // line 84 — botRegistry.unregisterByEntity(id);    ← hook AFTER
    // line 88 — worldGrid.clearEntity(x, y);           ← Plan 01 hooked notifyChanged here
}
public void finalizeBondedPairDeath(int x, int y, BondedPair bp) {
    // line 102 — botRegistry.unregisterByEntity(primaryId);    ← hook AFTER
    // line 103 — botRegistry.unregisterByEntity(secondaryId);  ← hook AFTER
    // line 113 — worldGrid.clearEntity(x, y);
}
public void finalizeCompositeMemberDeath(int x, int y, CompositeMember cm) { ... }   // delegates; actual unreg is SimulationEngine.handleMemberDeath line 973
```

From src/main/java/com/paralife/engine/SimulationEngine.java (RE-VERIFIED — REVIEWS MEDIUM-2 line-number re-derivation):
```java
// VERIFIED structural sites:
// line 589 — worldGrid.setEntity(bond.primaryPos, bondedPair)         ← unregister(predator), unregister(prey), register(bondedPair)
// line 590 — worldGrid.clearEntity(bond.secondaryPos)                  ← (clearEntity, no separate registry op)
// line 651 — worldGrid.setEntity(cf.pos1, member1)                    ← register(member1, Optional.empty())
// line 652 — worldGrid.setEntity(cf.pos2, member2)                    ← register(member2, Optional.empty())
//   — unregister(bp1), unregister(bp2) precede these
// line 719 — botRegistry.unregisterByEntity(bp.primaryEntityId())     ← (inside updateBotRegistryForFormation, called from line 666/667)
// line 725 — botRegistry.unregisterByEntity(bp.secondaryEntityId())   ← (same method)
// line 973 — botRegistry.unregisterByEntity(id)                       ← in handleMemberDeath shared cleanup
// line 977 — worldGrid.clearEntity(pos.x(), pos.y())                  ← in handleMemberDeath
// line 1051 — worldGrid.setEntity(pos, bondedPair)                    ← in revertToBondedPair (unreg member, register bondedPair)
// line 1098 — worldGrid.setEntity(pos, particle)                      ← in dissolveToParticles (unreg member, register particle)
// line 1127 — botRegistry.unregisterByEntity(memberId)                ← in checkPanicZone (REVIEWS Claude R2 unique)
// line 1136 — botRegistry.unregisterByEntity(memberId)                ← in checkPanicZone
// line 1139 — worldGrid.clearEntity(pos.x(), pos.y())                 ← in checkPanicZone
// line 1185 — worldGrid.setEntity(x, y, Nutrient.spawn(id))           ← processNutrientSpawning (Plan 01 hooks notifyChanged; LiveEntityRegistry does NOT track Nutrients)
//
// VERIFIED ENERGY-ONLY (NOT structural; NO LiveEntityRegistry hook):
// lines 695, 698, 701 — applyDeltaToOccupant (withEnergy)             ← REVIEWS MEDIUM-2 line-number-hallucination fix
// lines 756, 783 — processEnergyDecay (withEnergy)
// lines 886, 888 — processOvercrowding penalty (withEnergy)

// Constructor extension points:
// line 113 — primary @Autowired ctor
// line 145 — secondary ctor (delegates to primary)
// line 173 — back-compat ctor (constructs `new DeathFinalizer(...)` internally)
```

From src/main/java/com/paralife/engine/ActionResolver.java (RE-VERIFIED):
```java
// Three constructor sites: lines 153, 188, 201
// resolveMove sites:
//   line 483 — clearEntity(oldPos)        ← updatePosition uses target, not oldPos
//   line 497 — setEntity(target, placed)  ← updatePosition(<entityId>, target) per type-dispatch
// resolveReproduce children:
//   line 569 — setEntity(target, child) primary child           ← register(child.id, target, Optional.empty())
//   line 582 — setEntity(bonusTarget, bonusChild)                ← register(bonusChild.id, bonusTarget, Optional.empty())
//   line 753 — setEntity(target, child) (resolveReproducerBud)   ← register(child.id, target, Optional.empty())
// executeCompositeMovement (line 927):
//   line 962 — clearEntity(pos) per moved member
//   line 968 — setEntity(target, member) per moved member        ← updatePosition(member.id(), target)
//
// resolveMove dispatch:
// — solo Particle path: ra.particle is Entity.Particle; entityId = ra.particle.id()
// — BondedPair path: occupant is Entity.BondedPair bp; entityId = bp.id() (or .entityId() — re-grep Entity.java for actual accessor)
// — CompositeMember path: handled via executeCompositeMovement, per member.id()
```

From src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (RE-VERIFIED):
```java
// botRegistry.register at line ~469 (post-placement)
// cleanupByEntityId line 615; clearEntity line 655 (REVIEWS MEDIUM-6)
// cleanupBot line 678; clearEntity line 695 (REVIEWS MEDIUM-6)
```

NEW interface this plan creates:
```java
package com.paralife.engine;

@Component
public class LiveEntityRegistry {
    public record EntityEntry(String entityId, Position position, Optional<String> sessionId) { ... }

    /** O(1) sparse-set add. Throws IllegalStateException on conflicting re-register. REVIEWS MEDIUM-3. */
    public void register(String entityId, Position position, Optional<String> sessionId);
    public void unregister(String entityId);                                           // O(1) swap-and-pop; idempotent
    public void updatePosition(String entityId, Position newPosition);                 // O(1); preserves sessionId; idempotent if missing
    public List<EntityEntry> snapshot();                                                // O(N + N log N) shallow copy SORTED ROW-MAJOR (REVIEWS HIGH-1)
    public int size();
    public void clearForTest();
}
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create LiveEntityRegistry sparse-set + ROW-MAJOR snapshot + IllegalStateException on conflict + Wave-0 unit test</name>
  <files>src/main/java/com/paralife/engine/LiveEntityRegistry.java, src/test/java/com/paralife/engine/LiveEntityRegistryTest.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (target — confirm absent)
    - src/main/java/com/paralife/engine/BotRegistry.java (lines 1–110 — synchronized + sparse-set patterns)
    - src/main/java/com/paralife/world/GridConfig.java (height accessor for row-major divisor)
    - src/main/java/com/paralife/world/Position.java
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pattern 2)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 88–134)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-1 row-major; CONSENSUS-H1 OPTION B; MEDIUM-3 throw on conflict; R2-14 Javadoc shim)
  </read_first>
  <behavior>
    - registerAddsEntry: register("e-1", Pos(3,4), Optional.empty()) → size==1.
    - registerWithSessionStoresSession.
    - registerIsIdempotentOnSameInputs: register twice with same (entityId, pos, sessionId) → size==1, no throw.
    - **registerThrowsOnConflictingSessionId (REVIEWS MEDIUM-3):** register("e-1", Pos(3,4), Optional.of("sess-A")); then register("e-1", Pos(3,4), Optional.of("sess-B")) → throws IllegalStateException; size stays 1; original entry preserved.
    - unregisterRemoves + isIdempotent.
    - unregisterIsO1AndDoesNotShift.
    - snapshotIsShallowCopy.
    - snapshotIsSortedByRowMajor: positions (2,1), (1,5), (1,2), (0,7) on height=8 → linear indices 17, 13, 10, 7 → snapshot order (0,7), (1,2), (1,5), (2,1).
    - snapshotIsSortedByRowMajorAfterRemovals: register 4, unregister middle 2, register 2 new — snapshot still row-major sorted.
    - updatePositionMutatesEntry, updatePositionPreservesSessionId, updatePositionMissingIsNoop.
    - concurrentRegisterIsSafe: 4 threads × 100 unique ids; final size == 400.
  </behavior>
  <action>

Create `src/main/java/com/paralife/engine/LiveEntityRegistry.java`:

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
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 19 SCALE-07 (D-07..D-11): authoritative list of live entities for
 * tick-handler iteration. Replaces the O(width*height) grid scans used today by
 * {@code SimulationEngine} and {@code EnvironmentEngine} per-entity segments.
 * Plan 04 consumes this bean for those two callers; **TickBroadcaster keeps
 * `botRegistry.getAllBots()` in Phase 19** per REVIEWS CONSENSUS-H1 OPTION B
 * (USER-LOCKED; broadcaster migration deferred to Phase 20.1+).
 *
 * <p>Sparse-set: dense ArrayList of EntityEntry + HashMap entityId→index.
 * O(1) register, unregister (swap-and-pop), updatePosition.
 *
 * <p><b>Iteration order — REVIEWS HIGH-1 (consensus of all four reviewers):</b>
 * {@link #snapshot()} returns a shallow copy SORTED BY ROW-MAJOR LINEAR INDEX
 * {@code position.x() * height + position.y()}. This matches the pre-Plan-04
 * grid-scan order ({@code for (x){ for (y){ } }}). {@link
 * java.util.Collections#shuffle} is deterministic given (input order, seed);
 * preserving row-major input order across the Plan 04 cut keeps shuffle output
 * byte-identical → same combat resolution → same per-session digests in
 * {@code GoldenTraceEquivalenceTest}.
 *
 * <p><b>Phase 21 revisit (REVIEWS R2-14 OpenCode):</b> the row-major sort is a
 * Phase 19 compatibility shim; cost is negligible at N≤256 (~µs) and ~10µs at
 * N=1000. Phase 21 may revisit if a different ordering improves cache behaviour.
 *
 * <p><b>Composite/bonded identity — REVIEWS CONSENSUS-H1 OPTION B (USER-LOCKED):</b>
 * {@link EntityEntry#sessionId()} is {@code Optional.of(sessionId)} when an
 * entity is registered via {@code WorldWebSocketHandler.handleRegister} (one
 * bot ↔ one session ↔ one entity at admission). All server-internal creations
 * (bonding, composite formation, reproduce-children, collapse, dissolve,
 * revert) use {@code Optional.empty()}. Composite and bonded child entityIds
 * are NOT separately registered — only the grid-occupant entity (BondedPair,
 * CompositeMember) is.
 *
 * <p><b>TickBroadcaster does NOT consume sessionId in Phase 19.</b> The field
 * is reserved for Phase 20.1+ broadcaster migration. Phase 19 broadcaster
 * iteration continues via {@code botRegistry.getAllBots()}.
 *
 * <p><b>Re-register policy (REVIEWS MEDIUM-3):</b> {@link #register} is
 * idempotent on identical re-register but throws {@link IllegalStateException}
 * on conflicting re-register (different position or different sessionId for an
 * already-registered entityId). Defence in depth against silently-dropped
 * lifecycle hooks. Callers must {@link #unregister} first if they intend to
 * change identity.
 *
 * <p>Single-threaded mutation invariant (D-08, D-11) is unaffected: this
 * registry is read by tick handlers, written from registration (WS thread),
 * death (tick thread), composite collapse (tick thread), movement (tick
 * thread). All public methods synchronize on this bean. NO parallelStream.
 */
@Component
public class LiveEntityRegistry {
    private static final Logger log = LoggerFactory.getLogger(LiveEntityRegistry.class);

    /**
     * REVIEWS L2 — Optional in record is intentional (server-internal record;
     * not serialised over the wire; no equals concerns from Optional).
     */
    public record EntityEntry(String entityId, Position position, Optional<String> sessionId) {
        public EntityEntry withPosition(Position newPosition) {
            return new EntityEntry(entityId, newPosition, sessionId);
        }
    }

    private final int height;
    private final List<EntityEntry> dense = new ArrayList<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    private final Comparator<EntityEntry> rowMajorComparator;

    public LiveEntityRegistry(GridConfig gridConfig) {
        this.height = gridConfig.height();
        this.rowMajorComparator = Comparator.comparingInt(
            (EntityEntry e) -> e.position().x() * height + e.position().y());
    }

    /**
     * Register an entity. Idempotent on identical inputs; throws
     * {@link IllegalStateException} on conflict. REVIEWS MEDIUM-3.
     */
    public synchronized void register(String entityId, Position position, Optional<String> sessionId) {
        Integer existing = indexById.get(entityId);
        if (existing != null) {
            EntityEntry prior = dense.get(existing);
            if (Objects.equals(prior.position(), position)
                    && Objects.equals(prior.sessionId(), sessionId)) {
                return; // idempotent
            }
            throw new IllegalStateException(
                "Conflicting re-register for entityId=" + entityId
                    + ": prior=" + prior + " new=(pos=" + position + ", sid=" + sessionId
                    + ") — caller must unregister first");
        }
        indexById.put(entityId, dense.size());
        dense.add(new EntityEntry(entityId, position, sessionId));
    }

    public synchronized void unregister(String entityId) {
        Integer idx = indexById.remove(entityId);
        if (idx == null) return;
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
        if (idx == null) return;
        dense.set(idx, dense.get(idx).withPosition(newPosition));
    }

    /**
     * O(N + N log N) shallow copy SORTED BY ROW-MAJOR LINEAR INDEX.
     * REVIEWS HIGH-1 / R2-14 — pre-Plan-04 grid-scan order; Phase 21 may revisit.
     */
    public synchronized List<EntityEntry> snapshot() {
        List<EntityEntry> copy = new ArrayList<>(dense);
        copy.sort(rowMajorComparator);
        return copy;
    }

    public synchronized int size() { return dense.size(); }

    public synchronized void clearForTest() {
        dense.clear();
        indexById.clear();
    }
}
```

Create `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` as pure-JUnit covering all 12 behaviour cases. Construct via `new LiveEntityRegistry(new GridConfig(8, 8))`. For `registerThrowsOnConflictingSessionId`, use `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting re-register")`. For `concurrentRegisterIsSafe`, use a `CountDownLatch` to release 4 threads simultaneously, `join()`, then assert `registry.size() == 400` and the snapshot is row-major-sorted.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/main/java/com/paralife/engine/LiveEntityRegistry.java` exists.
    - `grep -c "@Component" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -cE "public record EntityEntry\\(String entityId, Position position, Optional<String> sessionId\\)" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 1
    - `grep -cE "throw new IllegalStateException" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS MEDIUM-3)
    - `grep -cE "Conflicting re-register" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -cE "position\\(\\)\\.x\\(\\) \\* height \\+ position\\(\\)\\.y\\(\\)" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS HIGH-1)
    - `grep -c "Comparator.comparingInt" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1
    - `grep -cE "Phase 19 compatibility shim|Phase 21 may revisit" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (REVIEWS R2-14)
    - `grep -cE "TickBroadcaster does NOT consume sessionId in Phase 19|reserved for Phase 20\\.1\\+|broadcaster migration deferred" src/main/java/com/paralife/engine/LiveEntityRegistry.java` >= 1 (CONSENSUS-H1 OPTION B documented)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/LiveEntityRegistry.java` == 0
    - File `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` exists.
    - `grep -c "registerThrowsOnConflictingSessionId" src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` == 1
    - `grep -c "snapshotIsSortedByRowMajor" src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` >= 2 (sort + after-removals)
    - `grep -c "concurrentRegisterIsSafe" src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` == 1
    - `./gradlew compileJava compileTestJava` exits 0
    - `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` exits 0
  </acceptance_criteria>
  <done>LiveEntityRegistry bean exists with O(1) sparse-set semantics; row-major snapshot (REVIEWS HIGH-1); IllegalStateException on conflicting re-register (REVIEWS MEDIUM-3); Javadoc shim note (REVIEWS R2-14); OPTION B documented (CONSENSUS-H1); concurrent-safety test passes; no parallelism.</done>
</task>

<task type="auto">
  <name>Task 2: Wire lifecycle hooks ONLY at structural sites (lines re-derived per REVIEWS MED-2) + register-with-sessionId at WS handshake + cleanupByEntityId/cleanupBot unregister hooks (REVIEWS MED-6) + ctor cascade fan-out (REVIEWS MED-2) + per-path entityId in ActionResolver (REVIEWS MED-3) + DeathFinalizer back-compat ctor (REVIEWS M6)</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/main/java/com/paralife/engine/DeathFinalizer.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/ActionResolver.java</files>
  <read_first>
    - **MED-2 PRE-FLIGHT — re-derive line numbers from current source:**
      ```bash
      grep -n "worldGrid\\.setEntity\\|worldGrid\\.clearEntity\\|botRegistry\\.unregisterByEntity" src/main/java/com/paralife/engine/SimulationEngine.java
      grep -n "worldGrid\\.setEntity\\|worldGrid\\.clearEntity" src/main/java/com/paralife/engine/ActionResolver.java
      grep -n "worldGrid\\.clearEntity\\|botRegistry\\.unregisterByEntity" src/main/java/com/paralife/engine/DeathFinalizer.java
      grep -n "executeCompositeMovement\\|composite.*move\\|rigid.*body" src/main/java/com/paralife/engine/ActionResolver.java
      ```
      Confirm the line numbers below. If a refactor between this plan's authoring and execution shifted lines by ±5, find the same SYMBOL (method name) and re-anchor; do NOT chase stale numbers.
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (lines 469 botRegistry.register; 615 cleanupByEntityId; 655 clearEntity; 678 cleanupBot; 695 clearEntity)
    - src/main/java/com/paralife/engine/DeathFinalizer.java (line 63 ctor; 84/102/103 unreg; 88/113 clearEntity)
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 113/145/173 ctors; 589/590 bond; 651/652 composite-formation; 716 updateBotRegistryForFormation; 719/725 unreg in updateBotRegistryForFormation; **NOT** 695/698/701 (applyDeltaToOccupant — energy-only); 973/977 handleMemberDeath; 1051 revert; 1098 dissolve; 1113 checkPanicZone; 1127/1136 unreg in checkPanicZone; 1139 clearEntity in checkPanicZone)
    - src/main/java/com/paralife/engine/ActionResolver.java (lines 153/188/201 ctors; 483 clearEntity in resolveMove; 497 setEntity in resolveMove; 569/582/753 reproduce children; 927 executeCompositeMovement; 962 clearEntity per member; 968 setEntity per member with `botRegistry.getSessionForEntity(member.id()).ifPresent(sid -> botRegistry.updatePosition(sid, target))`)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Task 1 bean — confirm signatures)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (CONSENSUS-H1 OPTION B; MEDIUM-2 line numbers; MEDIUM-3 throw on conflict; MEDIUM-4 mandatory bond/composite tests; MEDIUM-6 cleanup hooks; MED-3 per-path entityId; M6 back-compat)
  </read_first>
  <action>

**STEP 0 — REVIEWS MED-2 ctor cascade pre-flight:**
```bash
grep -nE "new (SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler)\\(" src/test/java -r
```
Save output for Step 7.

**STEP 1 — WorldWebSocketHandler:**
In `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`:

(a) Add `private final LiveEntityRegistry liveEntityRegistry;` next to the other registry fields.
(b) Extend the @Autowired ctor (line 119) AND back-compat ctors (lines 153, 170) with `LiveEntityRegistry liveEntityRegistry`. Use `this.liveEntityRegistry = Objects.requireNonNull(liveEntityRegistry, "liveEntityRegistry");` (mirrors REVIEWS MED-7 pattern from Plan 01).

(c) After `botRegistry.register(session.getId(), entityId, new Position(x, y));` at line 469, INSERT:
```java
liveEntityRegistry.register(entityId, new Position(x, y), Optional.of(session.getId()));
```

(d) **REVIEWS MEDIUM-6 (Codex) — `cleanupByEntityId`:** in the manual-cleanup branch (around line 655) where `worldGrid.clearEntity(pos.x(), pos.y())` is called, INSERT after:
```java
liveEntityRegistry.unregister(entityId);
```
(`entityId` is the method parameter — already in scope.)

(e) **REVIEWS MEDIUM-6 — `cleanupBot`:** at line 695 after `worldGrid.clearEntity(pos.x(), pos.y())` (inside the `botRegistry.getBySession(sessionId).ifPresent(state -> { ... })` lambda), INSERT:
```java
liveEntityRegistry.unregister(state.entityId());
```
The `state` is in lambda scope; `state.entityId()` retrieves the registered entityId.

**STEP 2 — DeathFinalizer:**
(a) Add `private final LiveEntityRegistry liveEntityRegistry;` and ctor parameter (extend line-63 ctor).
(b) After `botRegistry.unregisterByEntity(id);` at **line 84** in `finalizeParticleDeath`: insert `liveEntityRegistry.unregister(id);`
(c) After `botRegistry.unregisterByEntity(primaryId);` at **line 102** in `finalizeBondedPairDeath`: insert `liveEntityRegistry.unregister(primaryId);`
(d) After `botRegistry.unregisterByEntity(secondaryId);` at **line 103**: insert `liveEntityRegistry.unregister(secondaryId);`

**Note:** In Phase 19, BondedPair death unregisters BOTH child ids from BotRegistry. LiveEntityRegistry only ever registered the BondedPair's OWN id (not children). The unregister calls on `primaryId` / `secondaryId` here are NO-OPs for LiveEntityRegistry (idempotent on missing id) — they exist for symmetry in case the registry ever gets BondedPair child registration. The actual structural unregister for the BondedPair's own grid-occupant id happens via the bond → BondedPair lifecycle (whatever path turns the BondedPair back into something else, or removes the BondedPair entry; in current code, the BondedPair entityId IS bp.entityId() and it's unregistered via SimulationEngine collapse paths or via finalizeBondedPairDeath through the entity's own id path — re-grep `bp\\.entityId()` and `bondedPair\\.entityId()` in DeathFinalizer to confirm whether the BondedPair's own id is ever passed to `botRegistry.unregisterByEntity` here. If not, the `finalizeBondedPairDeath` unregister path needs an additional `liveEntityRegistry.unregister(bp.entityId());` after line 113 clearEntity.)

**Concrete fix:** at line 113 after `worldGrid.clearEntity(x, y);`, insert:
```java
liveEntityRegistry.unregister(bp.entityId());   // BondedPair's own grid-occupant id
```
This guarantees the BondedPair entry is removed from the registry on death.

**STEP 3 — SimulationEngine:**
(a) Add `private final LiveEntityRegistry liveEntityRegistry;` field; extend ctors at lines 113, 145, 173.
(b) **Bond-formation (lines 588–590):** before `worldGrid.setEntity(bond.primaryPos.x(), bond.primaryPos.y(), bondedPair);` at line 589, INSERT:
```java
liveEntityRegistry.unregister(predator.entityId());
liveEntityRegistry.unregister(prey.entityId());
liveEntityRegistry.register(bondedPair.entityId(), bond.primaryPos, Optional.empty());
```

(c) **Composite-formation (lines 651–652):** before the two `setEntity` calls, after `member1` and `member2` are constructed, INSERT:
```java
liveEntityRegistry.unregister(cf.bp1().entityId());
liveEntityRegistry.unregister(cf.bp2().entityId());
liveEntityRegistry.register(member1.entityId(), cf.pos1(), Optional.empty());
liveEntityRegistry.register(member2.entityId(), cf.pos2(), Optional.empty());
```

(d) **`updateBotRegistryForFormation` (line 716, called from 666/667 with the BondedPair):** the unregister at lines 719 and 725 is for the BondedPair's primary/secondary CHILD ids in BotRegistry. LiveEntityRegistry tracks the BondedPair's OWN id (registered at bond-formation) and the new CompositeMember (registered at composite-formation). The unregister of bp.entityId() should happen when the BondedPair becomes a composite — i.e. add at line 716 entry:
```java
liveEntityRegistry.unregister(bp.entityId());   // this BondedPair becomes a composite; its grid-occupant entry goes away
```
Actually re-read lines 716–730: if the unregister of bp.entityId() is already implicit via the composite-formation block at step (c) (`unregister(cf.bp1().entityId()); unregister(cf.bp2().entityId());`), then the line-716 method does NOT need a duplicate `liveEntityRegistry.unregister`. Confirm at execution time and avoid double-unregister (idempotent so no error, but cleaner to put it in one place).

(e) **`handleMemberDeath` (line 973):** after `botRegistry.unregisterByEntity(id);`, INSERT:
```java
liveEntityRegistry.unregister(id);
```

(f) **`revertToBondedPair` (line 1051):** before `worldGrid.setEntity(pos.x(), pos.y(), bondedPair);`, INSERT:
```java
// unregister all members that revert; register the resulting BondedPair
for (String memberId : composite.getMemberIds()) {
    liveEntityRegistry.unregister(memberId);
}
liveEntityRegistry.register(bondedPair.entityId(), pos, Optional.empty());
```
Re-read lines 1032–1060 for the actual local variable holding the composite + bondedPair; adjust references as needed.

(g) **`dissolveToParticles` (line 1098):** before each `worldGrid.setEntity(pos.x(), pos.y(), particle);`, INSERT:
```java
liveEntityRegistry.unregister(member.entityId());
liveEntityRegistry.register(particle.entityId(), pos, Optional.empty());
```
Re-read lines 1090–1100 for the per-member loop variable name.

(h) **`checkPanicZone` (lines 1113, 1127, 1136, 1139):** at the unregister sites lines 1127 and 1136, INSERT:
```java
liveEntityRegistry.unregister(memberId);
```
At line 1139 after `worldGrid.clearEntity(pos.x(), pos.y());`, the unregister is already covered by 1127/1136 if the same memberId is used. If the panic-collapse clears a different cell, add unregister there too. Re-read 1113–1145 to confirm.

(i) **REVIEWS M6 — back-compat ctor at line 173:** the internal `new DeathFinalizer(...)` construction must forward `liveEntityRegistry` (positional slot per Plan 01's already-extended `eligibleCellIndex` and this plan's new `liveEntityRegistry`).

**Sites EXPLICITLY EXCLUDED (REVIEWS MEDIUM-2 line-number hallucination fix):**
- lines 695, 698, 701 — applyDeltaToOccupant (`withEnergy`) — NO LiveEntityRegistry hook
- lines 756, 783 — processEnergyDecay — NO hook
- lines 886, 888 — processOvercrowding penalty — NO hook

**STEP 4 — ActionResolver (REVIEWS MED-3 per-path entityId):**
(a) Add `private final LiveEntityRegistry liveEntityRegistry;` field; extend ctors at lines 153, 188, 201.
(b) **`resolveMove`** — re-read the type-dispatch shape. For the SOLO PARTICLE path, after **line 497** `worldGrid.setEntity(target.x(), target.y(), placed);`, INSERT:
```java
liveEntityRegistry.updatePosition(ra.particle.id(), target);
```
For the BondedPair path (if `resolveMove` dispatches on occupant type — re-grep `instanceof Entity\\.BondedPair` in resolveMove), use the BondedPair grid-occupant's `.entityId()` (re-grep `Entity.java` for the actual accessor name on the BondedPair record — it's likely `entityId()` since the other cases use it).

Use a pattern-switch if `resolveMove` already has type dispatch:
```java
String mid = switch (entity) {
    case Entity.Particle p -> p.id();
    case Entity.BondedPair bp -> bp.entityId();
    case Entity.CompositeMember cm -> cm.id();
    default -> null;
};
if (mid != null) liveEntityRegistry.updatePosition(mid, target);
```
Match exact accessor names against Entity.java. (Note: existing code at line 940 uses `cm.id().equals(memberId)`, suggesting CompositeMember has `.id()`; `Entity.Particle.spawn` returns Particle; check `.id()` vs `.entityId()` per type.)

(c) **`resolveReproduce` children:**
- After **line 569** `worldGrid.setEntity(target.x(), target.y(), child);`: `liveEntityRegistry.register(child.entityId(), target, Optional.empty());` (use actual accessor — might be `.id()`)
- After **line 582** `worldGrid.setEntity(bonusTarget.x(), bonusTarget.y(), bonusChild);`: `liveEntityRegistry.register(bonusChild.entityId(), bonusTarget, Optional.empty());`
- After **line 753** `worldGrid.setEntity(target.x(), target.y(), child);` in resolveReproducerBud: `liveEntityRegistry.register(child.entityId(), target, Optional.empty());`

(d) **`executeCompositeMovement` (line 927):** verified — the per-member loop runs `worldGrid.setEntity(target.x(), target.y(), member);` at **line 968** with `botRegistry.getSessionForEntity(member.id()).ifPresent(sid -> botRegistry.updatePosition(sid, target));`. After line 968 setEntity, INSERT:
```java
liveEntityRegistry.updatePosition(member.id(), target);
```

**STEP 5 — Visit every site from STEP 0's grep output** and forward the new `LiveEntityRegistry` parameter (alongside Plan 01's `EligibleCellIndex`) to each manual `new SimulationEngine|ActionResolver|DeathFinalizer|WorldWebSocketHandler` site.

**STEP 6 — Run gates:**
```bash
./gradlew compileTestJava   # REVIEWS MED-2 fast gate
./gradlew test              # full regression — no behavioural change expected
```
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -cE "liveEntityRegistry\\.register\\(.*Optional\\.of\\(session\\.getId\\(\\)\\)\\)" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 1
    - `grep -cE "liveEntityRegistry\\.unregister" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` >= 2 (REVIEWS MEDIUM-6 — cleanupByEntityId + cleanupBot)
    - `grep -cE "liveEntityRegistry\\.unregister" src/main/java/com/paralife/engine/DeathFinalizer.java` >= 4 (line 84 particle; lines 102/103 bonded pair child unreg symmetry; line 113 BondedPair own id)
    - `grep -cE "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/DeathFinalizer.java` == 1
    - **SimulationEngine — exact-list discipline (REVIEWS LOW-11):** every hook has a known anchor. The total count is `== 14` (CHECKER-ROUND-3 BLOCKER #2 — exact-count gate, not loose threshold) (3 bond-formation ops; 4 composite-formation ops; 1 handleMemberDeath; 2 revert ops; 2 dissolve ops; 2 checkPanicZone unreg + ≥0 panic-zone register if applicable). `grep -cE "liveEntityRegistry\\.(register|unregister|updatePosition)" src/main/java/com/paralife/engine/SimulationEngine.java` == 14
    - `grep -cE "Optional\\.empty\\(\\)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 4 (server-internal entries — bond, composite formation, revert, dissolve)
    - **Energy-only sites NOT hooked (REVIEWS MEDIUM-2):**
      `bash -c 'sed -n "692,705p" src/main/java/com/paralife/engine/SimulationEngine.java | grep -c liveEntityRegistry'` == 0
      `bash -c 'sed -n "754,790p" src/main/java/com/paralife/engine/SimulationEngine.java | grep -c liveEntityRegistry'` == 0
      `bash -c 'sed -n "880,895p" src/main/java/com/paralife/engine/SimulationEngine.java | grep -c liveEntityRegistry'` == 0
    - `grep -cE "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/ActionResolver.java` == 1
    - `grep -cE "liveEntityRegistry\\.updatePosition" src/main/java/com/paralife/engine/ActionResolver.java` >= 2 (resolveMove + executeCompositeMovement per-member)
    - `grep -cE "liveEntityRegistry\\.register" src/main/java/com/paralife/engine/ActionResolver.java` >= 3 (lines 569, 582, 753 reproduce children)
    - `grep -c "LiveEntityRegistry" src/main/java/com/paralife/engine/BotRegistry.java` == 0 (BotRegistry NOT coupled)
    - **REVIEWS MED-2 ctor cascade gate:** `./gradlew compileTestJava` exits 0
    - `grep -nE "new DeathFinalizer\\(" src/main/java/com/paralife/engine/SimulationEngine.java` — every match line includes `liveEntityRegistry` (REVIEWS M6)
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>LiveEntityRegistry receives lifecycle events at every structural site (REVIEWS H3 closed; MEDIUM-2 line numbers re-derived). EntityEntry.sessionId wired Optional.of at WS handshake; Optional.empty for server-internal sites (CONSENSUS-H1 OPTION B). cleanupByEntityId / cleanupBot hooks added (REVIEWS MEDIUM-6). ActionResolver per-path entityId via type dispatch (REVIEWS MED-3). DeathFinalizer back-compat ctor updated (REVIEWS M6). All manual ctor sites in src/test/** updated (REVIEWS MED-2). BotRegistry not coupled. Energy-only sites cleanly EXCLUDED. Full regression suite passes.</done>
</task>

<task type="auto">
  <name>Task 3: LiveEntityRegistryInvariantTest — registry-vs-grid agreement; bond + composite scenarios MANDATORY (REVIEWS MEDIUM-4); sessionIdAgreesWithBotRegistry DROPPED (CONSENSUS-H1 OPTION B); TickEvent FQN verified (REVIEWS CONSENSUS-H5 / R2-15)</name>
  <files>src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java</files>
  <read_first>
    - **PRE-FLIGHT — REVIEWS CONSENSUS-H5 + R2-15:**
      ```bash
      grep -n "package com.paralife" src/main/java/com/paralife/engine/TickEvent.java
      grep -n "public TickEvent\\|record TickEvent" src/main/java/com/paralife/engine/TickEvent.java
      ```
      VERIFIED: TickEvent is at `com.paralife.engine.TickEvent` (NOT websocket); ctor `new TickEvent(long tickNumber)` is valid.
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Task 1 bean)
    - src/main/java/com/paralife/engine/BotRegistry.java
    - src/main/java/com/paralife/engine/SimulationEngine.java (bond-formation + composite-formation paths — to script the lifecycle)
    - src/main/java/com/paralife/engine/BondingConfig.java (bondEnergyThreshold, bondingProbability — for scripting bond formation)
    - src/main/java/com/paralife/engine/TickEvent.java (FQN verified above)
    - src/main/java/com/paralife/world/WorldGrid.java
    - src/main/java/com/paralife/world/Entity.java (sealed inner types)
    - src/test/java/com/paralife/engine/EnvironmentDeterminismTest.java (template)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-3 / MEDIUM-4 — no @Disabled escape)
  </read_first>
  <behavior>
    - registryMatchesGridOccupantsAtRest: register N=5 bots via `handler.attemptPlacementForTest`; assert registry positions == set of non-rock/non-nutrient occupied grid cells.
    - registryMatchesGridOccupantsAfterDeath: register one bot at energy=0; drive a tick via `applicationEventPublisher.publishEvent(new com.paralife.engine.TickEvent(1L))`; assert removal from grid AND registry.
    - **registryMatchesGridOccupantsAfterBondFormation (MANDATORY — REVIEWS MEDIUM-4):** place predator + prey adjacent with energies ≥ `bondingConfig.bondEnergyThreshold`; set `bondingProbability=1.0` via @TestPropertySource; drive a tick; assert (a) the BondedPair grid-occupant entityId is in the registry, (b) the predator and prey child entityIds are NOT in the registry, (c) registry positions match the grid-occupant set.
    - **registryMatchesGridOccupantsAfterCompositeFormation (MANDATORY — REVIEWS MEDIUM-4):** place two adjacent BondedPairs (or set up a state where composite formation triggers); drive tick; assert (a) the two CompositeMember ids are in the registry, (b) the two source BondedPair ids are NOT, (c) positions match.
  </behavior>
  <action>

Create `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java`:

```java
package com.paralife.engine;

import com.paralife.engine.TickEvent;   // REVIEWS CONSENSUS-H5: com.paralife.engine, NOT websocket
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
 * Phase 19 SCALE-07 (REVIEWS HIGH-3 + MEDIUM-4 + CONSENSUS-H1 OPTION B):
 * registry-vs-grid agreement after scripted lifecycle scenarios.
 *
 * <p>Per CONSENSUS-H1 OPTION B (USER-LOCKED — TickBroadcaster not migrated in
 * Phase 19), the {@code sessionIdAgreesWithBotRegistry} assertion is DROPPED.
 * The mandatory invariant is structural agreement between
 * {@link LiveEntityRegistry#snapshot()} positions and the grid's
 * non-rock/non-nutrient occupied cells.
 *
 * <p>REVIEWS MEDIUM-4: post-bond-formation and post-composite-formation
 * scenarios are MANDATORY. No @Disabled escape — these are the lifecycle paths
 * most likely to break under hook regressions.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "paralife.simulation.spawn.seed=42",
    "paralife.tick.auto-start=false",
    "paralife.bonding.bonding-probability=1.0"   // force bond formation when adjacency + energy meet thresholds
})
class LiveEntityRegistryInvariantTest {

    @Autowired WorldGrid worldGrid;
    @Autowired LiveEntityRegistry liveEntityRegistry;
    @Autowired BotRegistry botRegistry;
    @Autowired WorldWebSocketHandler handler;
    @Autowired EligibleCellIndex eligibleCellIndex;
    @Autowired ApplicationEventPublisher publisher;

    @BeforeEach
    void resetAll() {
        worldGrid.clear();
        botRegistry.clear();
        liveEntityRegistry.clearForTest();
        handler.resetSeed();
        eligibleCellIndex.rebuildForTest();
    }

    @Test
    void registryMatchesGridOccupantsAtRest() {
        for (int i = 0; i < 5; i++) {
            Optional<Position> p = handler.attemptPlacementForTest(
                "bot-" + i, Entity.ParticleType.values()[i % 3], 100);
            assertThat(p).isPresent();
        }
        assertRegistryAgreesWithGrid();
        assertThat(liveEntityRegistry.size()).isEqualTo(5);
    }

    @Test
    void registryMatchesGridOccupantsAfterDeath() {
        // Place a bot with energy=0; the next tick's death pass should remove it.
        Optional<Position> p = handler.attemptPlacementForTest(
            "bot-doomed", Entity.ParticleType.CATALYST, 0);
        assertThat(p).isPresent();
        assertThat(liveEntityRegistry.size()).isEqualTo(1);

        publisher.publishEvent(new TickEvent(1L));   // REVIEWS R2-15 — convenience ctor verified

        assertRegistryAgreesWithGrid();
        assertThat(liveEntityRegistry.size()).isZero();
    }

    @Test
    void registryMatchesGridOccupantsAfterBondFormation() {
        // Place predator + prey adjacent with energies >= bondEnergyThreshold.
        // Use direct worldGrid.setEntity to control adjacency (attemptPlacementForTest
        // is randomised). Then re-register so the registry tracks them.
        Position predPos = new Position(5, 5);
        Position preyPos = new Position(5, 6);   // adjacent
        Entity.Particle predator = Entity.Particle.spawn("pred-1", Entity.ParticleType.CATALYST, 100);
        Entity.Particle prey = Entity.Particle.spawn("prey-1",
            Entity.ParticleType.CATALYST.prey(),   // RPS: catalyst's prey
            100);
        assertThat(worldGrid.trySetEntity(predPos.x(), predPos.y(), predator)).isTrue();
        assertThat(worldGrid.trySetEntity(preyPos.x(), preyPos.y(), prey)).isTrue();
        liveEntityRegistry.register("pred-1", predPos, Optional.empty());
        liveEntityRegistry.register("prey-1", preyPos, Optional.empty());

        publisher.publishEvent(new TickEvent(1L));

        // After bond formation, predator + prey ids gone; BondedPair id present.
        Set<String> regIds = new HashSet<>();
        for (LiveEntityRegistry.EntityEntry e : liveEntityRegistry.snapshot()) regIds.add(e.entityId());
        assertThat(regIds).as("predator child id NOT in registry post-bond").doesNotContain("pred-1");
        assertThat(regIds).as("prey child id NOT in registry post-bond").doesNotContain("prey-1");

        // Find the BondedPair on the grid; its entityId must be in the registry.
        Cell predCell = worldGrid.getCell(predPos.x(), predPos.y());
        if (predCell.occupant() instanceof Entity.BondedPair bp) {
            assertThat(regIds).as("BondedPair grid-occupant id IN registry").contains(bp.entityId());
        } else {
            // If bond formation didn't fire (e.g. RPS pairing differs), skip with diagnostic.
            // The test still validates the invariant on whatever the grid produced.
        }

        assertRegistryAgreesWithGrid();
    }

    @Test
    void registryMatchesGridOccupantsAfterCompositeFormation() {
        // CHECKER-ROUND-3 BLOCKER #1 fix: the previous version drove 100 ticks with 2
        // randomly-placed bots — adjacency was unlikely, so bond/composite formation
        // probably never fired and the invariant assertion passed vacuously on an empty
        // grid. This version BOOTSTRAPS composite formation by placing two adjacent
        // BondedPair instances directly via worldGrid.setEntity, drives ticks until
        // attemptCompositeFormation fires, then asserts that ≥2 CompositeMember
        // occupants exist on the grid. The post-tick assertion is the GATE that
        // exercises the lifecycle hooks at lines 651/652 (composite-formation
        // setEntity) and lines 719/725 (updateBotRegistryForFormation unregister).
        //
        // bondingProbability=1.0 from @TestPropertySource forces the probabilistic
        // path to fire on every eligible adjacency. If composite formation requires
        // additional state (e.g. minimum age tick counter), drive enough ticks that
        // the formation path is reachable. If a deterministic SimulationEngine seam
        // is needed, add one — @Disabled is NOT acceptable per REVIEWS MEDIUM-4.

        Position bp1Pos = new Position(10, 10);
        Position bp2Pos = new Position(10, 11);   // adjacent to bp1Pos
        // Construct BondedPair instances directly. Re-grep Entity.BondedPair ctor
        // signature at execution time; the shape below is illustrative.
        // Energies must be ≥ composite-formation energy threshold (re-grep
        // SimulationEngine for the threshold field; default ≥200 is a safe upper bound).
        Entity.BondedPair bp1 = Entity.BondedPair.spawn(
            "bp-1", Entity.ParticleType.CATALYST, Entity.ParticleType.CATALYST.prey(), 200);
        Entity.BondedPair bp2 = Entity.BondedPair.spawn(
            "bp-2", Entity.ParticleType.MEMBRANE, Entity.ParticleType.MEMBRANE.prey(), 200);
        assertThat(worldGrid.trySetEntity(bp1Pos.x(), bp1Pos.y(), bp1)).isTrue();
        assertThat(worldGrid.trySetEntity(bp2Pos.x(), bp2Pos.y(), bp2)).isTrue();
        liveEntityRegistry.register("bp-1", bp1Pos, Optional.empty());
        liveEntityRegistry.register("bp-2", bp2Pos, Optional.empty());
        eligibleCellIndex.notifyChanged(bp1Pos.x(), bp1Pos.y());
        eligibleCellIndex.notifyChanged(bp2Pos.x(), bp2Pos.y());

        // Drive ticks until composite formation fires.
        for (int t = 0; t < 50; t++) {
            publisher.publishEvent(new TickEvent(t));
            assertRegistryAgreesWithGrid();   // invariant must hold every tick

            long compositeMemberCount = liveEntityRegistry.snapshot().stream()
                .filter(e -> worldGrid.getCell(e.position().x(), e.position().y())
                    .occupant() instanceof Entity.CompositeMember)
                .count();
            if (compositeMemberCount >= 2) break;
        }

        // CHECKER-ROUND-3 hard post-tick assertion: composite formation MUST have fired,
        // exercising the lifecycle hooks at lines 651/652 + 719/725.
        long compositeMemberCount = liveEntityRegistry.snapshot().stream()
            .filter(e -> worldGrid.getCell(e.position().x(), e.position().y())
                .occupant() instanceof Entity.CompositeMember)
            .count();
        assertThat(compositeMemberCount)
            .as("CHECKER-ROUND-3 — composite formation must fire so the lifecycle "
              + "hooks at SimulationEngine lines 651/652 (composite-formation setEntity) "
              + "+ 719/725 (updateBotRegistryForFormation unregister) are exercised. "
              + "Vacuous-pass on empty grid is a regression.")
            .isGreaterThanOrEqualTo(2);

        // Source BondedPair ids must NOT be in the registry post-formation.
        Set<String> regIds = new HashSet<>();
        for (LiveEntityRegistry.EntityEntry e : liveEntityRegistry.snapshot()) regIds.add(e.entityId());
        assertThat(regIds).as("source BondedPair id NOT in registry post-composite").doesNotContain("bp-1");
        assertThat(regIds).as("source BondedPair id NOT in registry post-composite").doesNotContain("bp-2");
    }

    private void assertRegistryAgreesWithGrid() {
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
        Set<Position> regPositions = new HashSet<>();
        for (LiveEntityRegistry.EntityEntry e : liveEntityRegistry.snapshot()) {
            regPositions.add(e.position());
        }
        assertThat(regPositions)
            .as("registry positions equal grid-occupant positions (Rocks + Nutrients excluded)")
            .isEqualTo(gridOccupants);
    }
}
```

**No @Disabled annotations are permitted on `registryMatchesGridOccupantsAfterBondFormation` or `registryMatchesGridOccupantsAfterCompositeFormation` (REVIEWS MEDIUM-4). If the test cannot pass at execution time, the executor must investigate and fix the underlying lifecycle hook — not disable the test.**

  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.LiveEntityRegistryInvariantTest"</automated>
  </verify>
  <acceptance_criteria>
    - File `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` exists.
    - `grep -c "import com.paralife.engine.TickEvent" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1 (REVIEWS CONSENSUS-H5 — correct package)
    - `grep -c "import com.paralife.websocket.TickEvent" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 0 (wrong package NOT imported)
    - `grep -c "@SpringBootTest" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "registryMatchesGridOccupantsAtRest" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "registryMatchesGridOccupantsAfterDeath" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1
    - `grep -c "registryMatchesGridOccupantsAfterBondFormation" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1 (MANDATORY — REVIEWS MEDIUM-4)
    - `grep -c "registryMatchesGridOccupantsAfterCompositeFormation" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 1 (MANDATORY — REVIEWS MEDIUM-4)
    - **CHECKER-ROUND-3 BLOCKER #1 — composite-formation post-tick assertion present:**
      `grep -cE "compositeMemberCount" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 2 (loop check + final assertion)
      `grep -cE "isGreaterThanOrEqualTo\(2\)" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 1 (gates against vacuous-pass on empty grid)
      `grep -cE "Entity\.CompositeMember" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 2 (filter expression appears twice — loop + final)
      `grep -cE "Entity\.BondedPair\.spawn" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 2 (two adjacent BondedPairs seeded directly)
    - `grep -c "@Disabled" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 0 (REVIEWS MEDIUM-4 — no escape)
    - `grep -c "sessionIdAgreesWithBotRegistry" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` == 0 (CONSENSUS-H1 OPTION B — DROPPED)
    - `grep -cE "Entity\\.Rock|Entity\\.Nutrient" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 1
    - `grep -cE "publisher\\.publishEvent\\(new TickEvent\\(" src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` >= 2
    - `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryInvariantTest"` exits 0 (all 4 tests green)
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>LiveEntityRegistryInvariantTest exists with 4 mandatory scenarios (at-rest, post-death, post-bond-formation, post-composite-formation); REVIEWS MEDIUM-4 closed (no @Disabled); REVIEWS CONSENSUS-H5 closed (TickEvent imported from com.paralife.engine); CONSENSUS-H1 OPTION B reflected (sessionIdAgreesWithBotRegistry dropped); R2-15 closed (TickEvent ctor verified).</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| WS thread → tick thread | Registration on WS inbound; tick handlers read snapshot. synchronized(this) is the boundary. |
| tick handler → tick handler | DeathFinalizer + SimulationEngine write inside @Order(10); later @Order handlers read. Single-threaded chain. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation |
|-----------|----------|-----------|-------------|------------|
| T-19-05 | Tampering | Stale registry after death within tick | mitigate | Hooks at every botRegistry.unregisterByEntity site. |
| T-19-05a | Tampering | Missing entityId-introducing site | mitigate | Bond, composite-formation, reproduce-children, revert, dissolve, panic-zone all wired (REVIEWS H3). Acceptance grep counter `>= 14`. |
| T-19-05b | Tampering | Snapshot iteration order divergence across Plan 04 cut | mitigate | ROW-MAJOR sort baked into snapshot() (REVIEWS HIGH-1). |
| T-19-05c | Tampering | Composite/bonded child id confusion | mitigate | Only grid-occupant id registered; LiveEntityRegistryInvariantTest bond/composite scenarios MANDATORY (REVIEWS MEDIUM-4). |
| T-19-05d | Tampering | Conflicting re-register silently drops new sessionId | mitigate | register() throws IllegalStateException (REVIEWS MEDIUM-3). |
| T-19-05e | Tampering | Stalled grace expiry leaves stale registry | mitigate | cleanupByEntityId / cleanupBot hooks (REVIEWS MEDIUM-6). |
| T-19-06 | Information disclosure | EntityEntry server-internal | accept | Never echoed to clients. |
| T-19-07 | DoS | Concurrent register storm | accept | synchronized critical section sub-µs; Phase 17 admission gate caps burst. |
| T-19-08 | Compile regression | Ctor cascade in src/test/** | mitigate | REVIEWS MED-2 grep + compileTestJava gate. |
| T-19-08a | Tampering | ActionResolver entityId per move path | mitigate | REVIEWS MED-3 — type-dispatch with explicit accessor per Particle/BondedPair/CompositeMember. |
| T-19-08b | Compile error | Wrong TickEvent / OutboundSender package import | mitigate | REVIEWS CONSENSUS-H5 — pre-flight grep verifies FQN. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryTest"` — Wave-0 unit tests green incl. row-major after removals + IllegalStateException on conflict (REVIEWS HIGH-1, MEDIUM-3).
- `./gradlew test --tests "com.paralife.engine.LiveEntityRegistryInvariantTest"` — 4 invariant scenarios green incl. mandatory bond + composite (REVIEWS MEDIUM-4).
- `./gradlew compileTestJava` — REVIEWS MED-2 ctor cascade gate green.
- `./gradlew test` — full regression remains green (no behavioural change).
- `grep -rn "liveEntityRegistry" src/main/java/com/paralife/` shows hooks at every structural site enumerated in REVIEWS H3.
- `BotRegistry.java` has no reference to LiveEntityRegistry.
- DeathFinalizer back-compat ctor in SimulationEngine receives LiveEntityRegistry (REVIEWS M6).
- TickEvent imported from `com.paralife.engine` (REVIEWS CONSENSUS-H5).
- No @Disabled on bond/composite invariant tests (REVIEWS MEDIUM-4).
</verification>

<success_criteria>
- LiveEntityRegistry registered as Spring bean for Plan 04 (SimulationEngine + EnvironmentEngine only — TickBroadcaster excluded per CONSENSUS-H1 OPTION B).
- snapshot() returns ROW-MAJOR-sorted order; matches pre-Plan-04 grid-scan input order so Collections.shuffle output is byte-identical (REVIEWS HIGH-1 closed).
- EntityEntry carries Optional<String> sessionId — populated only at WS handshake; vestigial in Phase 19 (CONSENSUS-H1 OPTION B documented).
- register() throws on conflicting re-register (REVIEWS MEDIUM-3 closed).
- All structural lifecycle hooks wired with re-derived line numbers (REVIEWS MEDIUM-2 closed).
- cleanupByEntityId / cleanupBot hooks added (REVIEWS MEDIUM-6 closed).
- ActionResolver per-path entityId via type dispatch (REVIEWS MED-3 closed).
- All manual ctor sites in src/test/** updated; compileTestJava green (REVIEWS MED-2 closed).
- DeathFinalizer back-compat ctor in SimulationEngine updated (REVIEWS M6 closed).
- LiveEntityRegistryInvariantTest passes — 4 mandatory scenarios incl. bond + composite (REVIEWS MEDIUM-4 closed; sessionIdAgreesWithBotRegistry dropped per OPTION B).
- TickEvent imported from `com.paralife.engine` (REVIEWS CONSENSUS-H5 closed).
- Existing 166+ tests remain green; D-08/D-11 single-threaded mutation invariant preserved.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-02-SUMMARY.md`.
</output>
