---
phase: quick-19.5-review-remediation
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/main/java/com/paralife/engine/EligibleCellIndex.java
  - src/main/java/com/paralife/engine/EnvironmentEngine.java
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/ActionResolver.java
  - src/main/java/com/paralife/engine/LiveEntityRegistry.java
  - src/main/java/com/paralife/engine/BotRegistry.java
  - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
  - src/test/java/com/paralife/engine/EligibleCellIndexTest.java
  - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java
  - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md
autonomous: true
requirements:
  - P19-REVIEW-H1
  - P19-REVIEW-H2
  - P19-REVIEW-H3
  - P19-REVIEW-M1
  - P19-REVIEW-M2
  - P19-REVIEW-M3
  - P19-REVIEW-M4
  - P19-REVIEW-M6
  - P19-REVIEW-L1
  - P19-REVIEW-L2
---

<objective>
Implement the 10-step remediation roadmap from the multi-agent Phase 19 review (Claude / Gemini / Codex / OpenCode). All 3 HIGH bugs (H1, H2, H3) and all 6 in-scope MEDIUM latent hazards (M1, M2, M3, M4, M6) get fixed; two LOW items (L1 pin-comment, L2 doc-tightening) ride along as cheap polish. L3–L7 explicitly deferred.

Purpose: Close real bugs and Phase 20.1 footguns identified in review before Phase 20 (connection-multiplexing-runtime-tuning) starts work that would compound them.

Output:
- Surgical fixes across 7 production files + 2 test files + 1 doc.
- 3 new tests (H1 second test, H2 disconnect-before-death integration test, H3 atomicity test).
- GoldenTraceEquivalenceTest dual-run digest remains bit-stable across the entire fix set.

Decisions locked (do not revisit):
- D-LOCK-1: OPTION B `EntityEntry.sessionId` field → DELETE (not populate). Per Phase 18 D-05/D-21 WS:entity is strictly 1:1 — Gemini's `List<String> sessionIds` proposal is rejected as misreading the invariant. Phase 20.1 will design a session-mapping side channel from a clean slate.
- D-LOCK-2: Step ordering H1 → H2 → H3 → M1 → M2 → M3 → M4 → M6 → L2 → L1 is fixed. M6 (delete sessionId field) goes after H2 specifically so H2's bond-formation rework does not have to thread `Optional<String>` through call sites that M6 is about to delete.
- D-LOCK-3: L2 doc tightening folds into the M6 task (both clarify determinism scope) — no separate "doc fix" task.
- D-LOCK-4: L3, L4, L5, L6, L7 deferred to Phase 21 / backlog. Do not address.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md

<key-invariants>
From CLAUDE.md (Phase 18 D-05/D-21):
- WS:entity is strictly 1:1 — every WebSocket session owns exactly one entity during Alive phase.
- BondedPair is ONE entity controlled by ONE session (the predator's surviving session). Prey's session is unregistered/cleaned at bond formation.
- Many concurrent connections is the architectural goal; multi-entity-per-session is strongly discouraged.

From CLAUDE.md (Phase 14 D-38..D-41):
- `cellStatusCache` bit 0 (OVERCROWDED) is REDACTED at the cache layer — bit 0 is recomputed per-bot in TickBroadcaster.cellToView. This is intentional, not a bug.
- `Cell.flags` (`FLAG_OVERCROWDED` / `FLAG_STARVING`) is the authoritative server-global per-cell state. Read directly when you need the truth.

SCALE-07 invariant (Phase 19): `LiveEntityRegistry` mirrors live grid occupants exactly. Stale entries violate this.
</key-invariants>

<critical-files>
- `src/main/java/com/paralife/engine/EligibleCellIndex.java` — H1, M4
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — M1
- `src/main/java/com/paralife/engine/SimulationEngine.java` — H2, M2, M3, M6, L1
- `src/main/java/com/paralife/engine/ActionResolver.java` — M3, M6
- `src/main/java/com/paralife/engine/LiveEntityRegistry.java` — M6
- `src/main/java/com/paralife/engine/BotRegistry.java` — H2 (`remapEntity` reuse / extension)
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — H2, H3, M5/M6
- `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` — H1
- `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` — M3
- `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md` — L2
</critical-files>

<commit-discipline>
Each task in this plan is **one atomic commit**. After each task: stage only the files that task touched, run `./gradlew test`, commit with the suggested message. Never bundle two task commits. Never push. Order matters — do not re-order.
</commit-discipline>
</context>

<tasks>

<task type="auto">
  <name>Task 1 (H1): Fix OVERCROWDED constraint in EligibleCellIndex — read Cell.flags directly, not the cache</name>
  <files>src/main/java/com/paralife/engine/EligibleCellIndex.java, src/test/java/com/paralife/engine/EligibleCellIndexTest.java</files>
  <action>
**Bug**: `EligibleCellIndex.java:223` reads `(status & 0x01) != 0` from `cellStatusCache.get(pos)`. Per CLAUDE.md D-40, bit 0 (OVERCROWDED) is **deliberately redacted** at the cache layer — `EnvironmentEngine.buildStatusCaches()` only ORs in TOXIN_PRESENT (0x02) at line 933 and MUTAGEN_ZONE (0x04) at line 954. Bit 0 is recomputed per-bot in `TickBroadcaster.cellToView`. Net effect today: D-03 placement constraint #2 silently passes for every empty cell whose neighbours are dense enough to be globally OVERCROWDED — that cell can be placed into and immediately gets the per-tick overcrowding penalty. Unfair on dense ramps. The unit test stubs bit 0 manually so it proves the predicate but not the cache pipeline → false confidence.

**Fix**:
1. In `EligibleCellIndex.evaluateEligibility` (around line 220-223), replace the `cellStatusCache.get(pos)` lookup for constraint #2 with a direct `worldGrid.getCell(x, y).hasFlag(Cell.FLAG_OVERCROWDED)`. This reads the authoritative server-global flag set by `SimulationEngine.processOvercrowding`.
2. Drop the `// PERF: REVIEWS MEDIUM-9` comment about the `new Position(x, y)` allocation if it's still there for constraint #2 — direct-cell read eliminates that allocation. (Leave the comment if it covers other allocations in the same method.)
3. Update `EligibleCellIndexTest.constraint2RejectsOvercrowded`: remove the cache-bit stubbing, set `Cell.FLAG_OVERCROWDED` via `worldGrid.getCell(x,y).withFlag(...)` (or whatever the existing API is — mirror what `SimulationEngine.processOvercrowding` does).
4. Add a SECOND test `constraint2RejectsOvercrowded_viaSimulationEnginePath`: register a bot, mark neighbour cells to push the empty target over the overcrowding threshold via the real `SimulationEngine.processOvercrowding`, then assert the index excludes that cell. This proves the end-to-end pipeline, not just the predicate.

**Per D-LOCK-2**: this is task 1 — must commit before any other task touches `EligibleCellIndex` or the cache layer.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.engine.EligibleCellIndexTest</automated>
  </verify>
  <done>
- `EligibleCellIndex.evaluateEligibility` reads `worldGrid.getCell(x,y).hasFlag(Cell.FLAG_OVERCROWDED)` directly for constraint #2 (no cache lookup for OVERCROWDED).
- `EligibleCellIndexTest.constraint2RejectsOvercrowded` no longer stubs the cache bit; uses real `Cell.FLAG_OVERCROWDED`.
- New test `constraint2RejectsOvercrowded_viaSimulationEnginePath` exists and passes — exercises the real `SimulationEngine.processOvercrowding` path.
- `./gradlew test` passes (all 166 + 1 new = 167 tests).
- Commit message: `fix(19.5): EligibleCellIndex constraint #2 reads Cell.flags directly (H1)`.
  </done>
</task>

<task type="auto">
  <name>Task 2 (H2): Bond-formation registry remap + integration test for disconnect-before-death</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/BotRegistry.java, src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java</files>
  <action>
**Bug**: `SimulationEngine.java:654-656` (bond formation): unregisters predator+prey from `liveEntityRegistry`, registers `bondedPair.id()`. **`BotRegistry` and `session.attributes[ATTR_ENTITY_ID]` are NOT updated** — they still hold the predator's particle id (set at `WorldWebSocketHandler.java:576`). When `WorldWebSocketHandler.cleanupBot:802` calls `liveEntityRegistry.unregister(predatorOldId)`, it's a no-op (registry holds `bondedPair.id()`). If the predator's session disconnects before the BondedPair dies, a stale `BondedPair` entry leaks in `LiveEntityRegistry` until BondedPair eventually dies. Violates SCALE-07 invariant. (`DeathFinalizer.finalizeBondedPairDeath:134-152` already handles the death-then-disconnect path correctly.)

**Architecture note (per D-LOCK-1)**: WS:entity is 1:1. The BondedPair is one entity controlled by one session — the predator's surviving session. Prey's session is unregistered/cleaned at bond formation. Use scalar predator-session, not a list.

**Fix**:
1. In `SimulationEngine.java` at the bond-formation site (~line 654-656), introduce a `bondingFormation` helper (or extend the existing call inline) that, in order:
   a. Resolves the predator's controlling sessionId via `botRegistry.getSessionForEntity(predator.id())` (add this lookup if it doesn't already exist — check `BotRegistry`).
   b. Unregisters predator + prey from `liveEntityRegistry` (existing behaviour).
   c. Registers `bondedPair.id()` in `liveEntityRegistry` (existing behaviour). NOTE: per D-LOCK-1, we are about to delete the `Optional<String>` sessionId param from `LiveEntityRegistry.register` in task 8 (M6). Pass `Optional.empty()` here for now — task 8 will drop it.
   d. Calls `botRegistry.remapEntity(predatorSessionId, bondedPair.id())` so `BotRegistry` reflects predator-session → bondedPair.id(). If `BotRegistry.remapEntity` does not exist, add it (mirror the existing composite-formation `updateBotRegistryForFormation` helper — search for that).
   e. Updates `session.attributes[ATTR_ENTITY_ID]` to `bondedPair.id()`. The clean way: have `SimulationEngine` call back into a small handler exposed by `WorldWebSocketHandler` (e.g. `WorldWebSocketHandler.onBondFormed(predatorSessionId, bondedPairId)`) that does the attribute update. Do NOT tightly couple SimulationEngine to WebSocketSession — use a registered listener / event.
2. Verify `WorldWebSocketHandler.cleanupBot:802` then naturally unregisters by the correct id (`bondedPair.id()`) on disconnect.
3. **New integration test** `BondDisconnectIntegrationTest`:
   - Place predator + prey adjacent on the grid.
   - Force a bond (drive the SimulationEngine tick that triggers bond-formation, or call the bond-formation path directly via a test seam).
   - Immediately disconnect the predator's session (close the WebSocket / call `cleanupBot`).
   - Assert `liveEntityRegistry.snapshot()` returns no entries pointing at the bond cell.
   - Assert `botRegistry` has no mapping for the predator's session.
   - Use `@SpringBootTest` (mirror existing integration test patterns under `src/test/java/com/paralife/websocket/`).

**Per D-LOCK-2**: depends on task 1 (independent file), but MUST land before task 8 (M6) because task 8 deletes the very `Optional<String>` plumbing this fix uses; the call to `LiveEntityRegistry.register(bondedPair.id(), pos, Optional.empty())` here will be simplified in task 8.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.websocket.BondDisconnectIntegrationTest --tests com.paralife.engine.SimulationEngineTest</automated>
  </verify>
  <done>
- `SimulationEngine` bond-formation now updates `BotRegistry` (predator session → `bondedPair.id()`) and triggers a session-attribute update via a callback/event into `WorldWebSocketHandler`.
- `BotRegistry.remapEntity` (or equivalent) handles the predator-only path.
- New `BondDisconnectIntegrationTest` exists and passes.
- After bond-then-disconnect-before-death, `liveEntityRegistry.snapshot()` is clean (no stale `bondedPair.id()` entry).
- `./gradlew test` passes.
- Commit message: `fix(19.5): bond-formation remaps BotRegistry and session attribute (H2)`.
  </done>
</task>

<task type="auto">
  <name>Task 3 (H3): Reorder register sequence — LiveEntityRegistry before WorldGrid.trySetEntity, with rollback</name>
  <files>src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/test/java/com/paralife/websocket/RegisterAtomicityTest.java</files>
  <action>
**Bug**: `WorldWebSocketHandler.handleRegister` sequence (lines 533→568): `trySetEntity` → `notifyChanged` → `botRegistry.register` → `liveEntityRegistry.register`. No lock spans the gap. Tick read at @Order(10) (`SimulationEngine.entitySnapshot` via `liveEntityRegistry.snapshot()`) can fire mid-sequence: entity is on the grid but absent from registry → entity-list iteration skips it for one tick. Pre-Phase-19 grid-walk would have caught it. Today benign because Golden gate batch-registers all bots before tick 1, but production multi-bot ramps will hit it.

**Fix**:
1. In `WorldWebSocketHandler.handleRegister` (~line 533), swap the order:
   a. Call `liveEntityRegistry.register(entityId, pos, Optional.of(sessionId))` FIRST.
      - NOTE: per D-LOCK-1 / task 8, the `Optional<String>` param will be deleted by task 8. For now, pass `Optional.of(sessionId)` to keep the existing signature; task 8 drops the third arg.
   b. Then call `worldGrid.trySetEntity(...)`.
   c. **On `trySetEntity` failure**: immediately call `liveEntityRegistry.unregister(entityId)` to roll back, then continue the existing retry loop.
2. Rationale (per source plan): consumers re-derive the entity from `worldGrid.getCell(entry.position()).occupant()`, so a "registry has it, grid doesn't" transient resolves to a benign skip on the consumer side. Cheap.
3. Keep the relative order of `notifyChanged` and `botRegistry.register` as-is unless the rollback path changes them.
4. **New test** `RegisterAtomicityTest`:
   - Schedule a tick fire mid-register using a latch on the WS thread (insert a test-only barrier between `liveEntityRegistry.register` and `trySetEntity`, fire `tickEvent`, then release the barrier).
   - Assert the new entity appears in either both `liveEntityRegistry.snapshot()` AND `worldGrid` OR neither (atomic from consumer perspective, modulo grid re-derivation).
   - Specifically: if the tick observes the entity in `liveEntityRegistry` but `worldGrid.getCell(pos).occupant()` is absent, the tick must skip it without crashing — assert no exceptions thrown.

**Per D-LOCK-2**: depends on tasks 1, 2 logically (sequential commits) but does not collide on files. Files are `WorldWebSocketHandler.java` (also touched by task 2) — apply task 2's edits first, then this task's reorder on top.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.websocket.RegisterAtomicityTest --tests com.paralife.websocket.WorldWebSocketHandlerTest</automated>
  </verify>
  <done>
- `WorldWebSocketHandler.handleRegister` calls `liveEntityRegistry.register` BEFORE `worldGrid.trySetEntity`.
- On `trySetEntity` failure, `liveEntityRegistry.unregister(entityId)` rolls back before retry.
- New `RegisterAtomicityTest` passes — tick fired mid-register sees consistent state from consumer perspective (no exceptions, no missing entity for >0 ticks).
- `./gradlew test` passes.
- Commit message: `fix(19.5): register LiveEntityRegistry before WorldGrid with rollback (H3)`.
  </done>
</task>

<task type="auto">
  <name>Task 4 (M1): Volatile-snapshot entityStatusCache mirroring cellStatusCache pattern</name>
  <files>src/main/java/com/paralife/engine/EnvironmentEngine.java</files>
  <action>
**Hazard (latent)**: `entityStatusCache` is currently a plain `HashMap`. Today the only reader is `TickBroadcaster.getEntityStatus` at @Order(50), same tick thread as the @Order(14) writer — safe. Phase 19.1 / 20.1 parallel `PerceptionBroadcaster` (CONTEXT D-12) is the activation path. `cellStatusCache` already uses volatile + stage + swap (post-CONSENSUS-H4); `entityStatusCache` should mirror it for symmetry — one cheap `Map.copyOf` per tick, removes a Phase 20.1 footgun.

**Fix** (in `EnvironmentEngine.java`, near line 187 where `entityStatusCache` is declared):
1. Replace the field declaration:
   ```java
   private volatile Map<String, Byte> entityStatusCache = Map.of();
   private final HashMap<String, Byte> entityStatusStaging = new HashMap<>();
   ```
2. Inside `buildStatusCaches()`, write all per-entity status bits into `entityStatusStaging` instead of the live cache.
3. At the end of `buildStatusCaches()`, mirror the existing `cellStatusCache` swap pattern:
   ```java
   this.entityStatusCache = Map.copyOf(entityStatusStaging);
   entityStatusStaging.clear();
   ```
4. `getEntityStatus(id)` reads from the volatile field. `entityStatusCacheView()` returns it directly (already an immutable Map after copyOf).
5. **No call-site changes** — public API unchanged.

Per CLAUDE.md "Env state projection — three layers": this is layer-2 (status caches) cleanup. Layer 1 (shadow grids) and layer 3 (wire bitmask) untouched.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.engine.EnvironmentEngineTest --tests com.paralife.websocket.TickBroadcasterTest</automated>
  </verify>
  <done>
- `EnvironmentEngine.entityStatusCache` is `volatile Map<String,Byte>` initialized to `Map.of()`.
- `entityStatusStaging` (private final HashMap) used for in-tick writes.
- `buildStatusCaches()` swaps via `Map.copyOf` at end, mirroring `cellStatusCache`.
- `getEntityStatus` and `entityStatusCacheView` read the volatile field.
- No public-API changes; no call-site changes elsewhere.
- `./gradlew test` passes.
- Commit message: `refactor(19.5): entityStatusCache volatile-snapshot mirror of cellStatusCache (M1)`.
  </done>
</task>

<task type="auto">
  <name>Task 5 (M2): Replace EntityEntry "_" sentinel with real entityId in SimulationEngine.entitySnapshot</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java</files>
  <action>
**Hazard (latent)**: `SimulationEngine.entitySnapshot` (~line 277) constructs `new EntityEntry("_", pos, Optional.empty())` as a fallback when the snapshot path needs an `EntityEntry` but the occupant id isn't directly available. All 7 current callers use only `entry.position()` — no live bug. But the `"_"` sentinel is an active footgun: any future caller that reads `entry.entityId()` silently gets garbage.

**Fix**: In `SimulationEngine.entitySnapshot` around line 277, replace:
```java
entries.add(new EntityEntry("_", new Position(col, row), Optional.empty()));
```
with:
```java
String id = occ != null ? EntityIds.entityIdOf(occ) : "_";
entries.add(new EntityEntry(id, new Position(col, row), Optional.empty()));
```
(Use the existing `EntityIds.entityIdOf` helper. Keep `"_"` only as a defensive null-occupant fallback — should never trigger in practice but preserves total ordering.)

**Note**: per D-LOCK-1 / task 8, the third arg `Optional.empty()` will be removed by task 8 (M6 deletes the `Optional<String>` field from `EntityEntry`). For now, keep the three-arg constructor; task 8 will sweep all 11 sites including this one.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.engine.SimulationEngineTest</automated>
  </verify>
  <done>
- `SimulationEngine.entitySnapshot` fallback line uses `EntityIds.entityIdOf(occ)` (or `"_"` only when `occ == null`).
- No remaining hard-coded `"_"` literal as the entity id when an occupant is available.
- `./gradlew test` passes.
- Commit message: `fix(19.5): replace EntityEntry "_" sentinel with real entity id (M2)`.
  </done>
</task>

<task type="auto">
  <name>Task 6 (M3): SimulationEngine.clearStateForTest() + ActionResolver.childIdCounter reset + GoldenTrace wiring</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/ActionResolver.java, src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java</files>
  <action>
**Hazard (latent)**: state survives across `GoldenTraceEquivalenceTest.resetAll()`:
- `SimulationEngine.previousPoolEnergy` — HIGH impact if compositeIds become deterministic (panic-zone roll baseline).
- `SimulationEngine.nutrientIdCounter` — MEDIUM (embeds in entity ids).
- `SimulationEngine.lastTickBondCount` — LOW (only test-asserted after run).
- `ActionResolver.childIdCounter` — `ActionResolver.clearStateForTest` doesn't reset it.
Latent today only because composite-ids use `UUID.randomUUID()`. The instant ids become deterministic, dual-run digest divergence appears.

**Fix**:
1. Add to `SimulationEngine`:
   ```java
   public void clearStateForTest() {
       previousPoolEnergy.clear();
       nutrientIdCounter.set(0);
       lastTickBondCount.set(0);
   }
   ```
   (Match the existing `clearStateForTest` style — it likely exists already; if not, add as new public method with `@VisibleForTesting`-style Javadoc.)
2. Add to `ActionResolver.clearStateForTest()`:
   ```java
   childIdCounter.set(0);
   ```
3. Wire into `GoldenTraceEquivalenceTest.resetAll()`: add `simulationEngine.clearStateForTest();` after the existing `simulationEngine.resetSeed()` call (or wherever `actionResolver.clearStateForTest` is currently invoked — keep them adjacent for clarity).
4. Run dual-run digest twice locally to confirm bit-stability survives.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.engine.GoldenTraceEquivalenceTest</automated>
  </verify>
  <done>
- `SimulationEngine.clearStateForTest()` exists and clears `previousPoolEnergy`, `nutrientIdCounter`, `lastTickBondCount`.
- `ActionResolver.clearStateForTest()` includes `childIdCounter.set(0)`.
- `GoldenTraceEquivalenceTest.resetAll()` invokes `simulationEngine.clearStateForTest()`.
- `GoldenTraceEquivalenceTest` dual-run digest is bit-stable (passes).
- `./gradlew test` passes.
- Commit message: `test(19.5): SimulationEngine.clearStateForTest + childIdCounter reset (M3)`.
  </done>
</task>

<task type="auto">
  <name>Task 7 (M4): Synchronize EligibleCellIndex.initialize and clear dense/posInDense at start</name>
  <files>src/main/java/com/paralife/engine/EligibleCellIndex.java</files>
  <action>
**Hazard (latent)**: `EligibleCellIndex.initialize()` (line 122) is not synchronized. `rebuildForTest` already calls it under monitor (re-entrant — free). Test-misuse footgun: any future test or call path that invokes `initialize` from outside the monitor races with reads.

**Fix**:
1. Add `synchronized` to the method signature at line 122 (`public synchronized void initialize() { ... }`). Spring honours `@PostConstruct` on synchronized methods; `rebuildForTest` is re-entrant safe.
2. Optional polish (codex's idea — apply it): clear `dense` and `posInDense` at the start of `initialize()` so re-init works correctly:
   ```java
   dense.clear();
   posInDense.clear();
   ```
   Place at the very top of the method body.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.engine.EligibleCellIndexTest</automated>
  </verify>
  <done>
- `EligibleCellIndex.initialize` signature includes `synchronized`.
- `dense.clear()` and `posInDense.clear()` at the top of `initialize()`.
- `./gradlew test` passes.
- Commit message: `fix(19.5): synchronize EligibleCellIndex.initialize and reset dense state (M4)`.
  </done>
</task>

<task type="auto">
  <name>Task 8 (M6 + L2): DELETE EntityEntry.sessionId field across all 11 sites + tighten D-06 doc wording</name>
  <files>src/main/java/com/paralife/engine/LiveEntityRegistry.java, src/main/java/com/paralife/engine/SimulationEngine.java, src/main/java/com/paralife/engine/ActionResolver.java, src/main/java/com/paralife/websocket/WorldWebSocketHandler.java, src/test/java/com/paralife/engine/PlacementDeterminismTest.java, .planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md</files>
  <action>
**Per D-LOCK-1**: OPTION B `EntityEntry.sessionId` field → DELETE. Rationale: today the field is populated only at `WorldWebSocketHandler.java:568` and is `Optional.empty()` at all 9+ server-internal sites. Rebind never refreshes it. It's a footgun that adds zero value today and would silently lie to any contributor who reaches for it. Phase 20.1 will design a session-mapping side channel from a clean slate — likely as the FIRST task of Phase 20.1, before the broadcaster migration itself.

**Pushback rejected (D-LOCK-1)**: Gemini's `List<String> sessionIds` proposal is wrong — misreads CLAUDE.md Phase 18 D-05/D-21 (WS:entity is strictly 1:1; BondedPair is one entity controlled by one session).

**Fix steps**:
1. **`LiveEntityRegistry.java`** (~line 78): Remove `Optional<String> sessionId` from the `EntityEntry` record. New shape: `record EntityEntry(String entityId, Position position) { }`.
2. **`LiveEntityRegistry.register(...)`** signature: drop the `Optional<String>` param. New shape: `void register(String entityId, Position position)`.
3. Update the class Javadoc (~line 21-52). Replace the OPTION B narrative with:
   > Phase 20.1 will introduce a session-mapping side channel for the broadcaster migration — design TBD. This registry intentionally holds no session attribution; `BotRegistry` remains authoritative for session→entity routing.
4. **Sweep all 11 call sites** — drop the third arg `Optional.empty()` / `Optional.of(sessionId)` everywhere:
   - `WorldWebSocketHandler.java:568` (the one populated site — drops `Optional.of(sessionId)`)
   - `SimulationEngine.java`: lines 632, 650, 656, 728, 729, 829, 1151, 1205 (all currently pass `Optional.empty()`)
   - `ActionResolver.java`: lines 632, 650, 829 (all currently pass `Optional.empty()`)
   - Note: line numbers may have shifted after tasks 2, 3, 5 — search by call shape `liveEntityRegistry.register(.*Optional` to find every site.
5. Update any test that constructed `EntityEntry` with a third arg — search `new EntityEntry(.*Optional` across `src/test/`. (At least `PlacementDeterminismTest` is suspected; grep to confirm.)
6. **This auto-fixes M5** (rebind staleness has no field to stale).
7. **L2 doc tightening (folded in per D-LOCK-3)**:
   - Edit `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md` D-06: change wording from "same seed + same arrival order → identical placements" (or similar) to: "single-threaded registration determinism: under serial registration with a fixed seed, placements are byte-exact repeatable. Multi-threaded registration is not in scope of this contract."
   - Add the same clarification as a class-level Javadoc on `PlacementDeterminismTest`.
8. Verify with grep: `grep -rn "EntityEntry(" src/ | grep -v "EntityEntry(\".*\", " | head` — every remaining construction should be exactly two args.

**Per D-LOCK-2**: this task lands AFTER tasks 2, 3, 5 (which all touch sites that this task will simplify). Doing M6 last among the production fixes minimizes thrash.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <done>
- `EntityEntry` record is two-arg: `(String entityId, Position position)` — no `Optional<String> sessionId`.
- `LiveEntityRegistry.register` is two-arg.
- Zero remaining call sites pass three args. Verified via grep.
- `LiveEntityRegistry` class Javadoc updated to point at Phase 20.1 design-TBD.
- `19-CONTEXT.md` D-06 wording tightened to single-threaded scope.
- `PlacementDeterminismTest` Javadoc carries the same scope note.
- All existing tests pass (no construction-site regressions).
- `./gradlew test` passes.
- Commit message: `refactor(19.5): delete EntityEntry.sessionId field; tighten D-06 determinism scope (M6, L2)`.
  </done>
</task>

<task type="auto">
  <name>Task 9 (L1): Pin known-limitation comment at SimulationEngine.processDeaths Phase 3c</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java</files>
  <action>
**Status**: REAL but inert in current test scenario. `CompositeRegistry` uses `ConcurrentHashMap` and composite-ids use `UUID.randomUUID()`. The 200-tick GoldenTrace scenario doesn't reach panic-zone shatter (energy levels never drop below 12% critical threshold, no `simRng.nextDouble()` consumed for shatter). Deferred to Phase 21 — but log as a known bound on the current digest gate.

**Fix**: Add a pinned comment at `SimulationEngine.processDeaths` Phase 3c (search for "Phase 3c" or the composite-iteration block):

```java
// L1 (Phase 19 review, deferred to Phase 21):
// compositeRegistry.getAll() iteration order is non-deterministic (ConcurrentHashMap).
// Inert today: GoldenTrace 200-tick scenario never reaches panic-zone shatter
// (energy never < 12% critical threshold; simRng.nextDouble() not consumed).
// If panic-zone shatter is ever exercised in the digest gate, switch to
// LinkedHashMap or sort by insertion-tick before iterating.
```

No code changes — comment only. Single-line / multi-line acceptable.
  </action>
  <verify>
    <automated>./gradlew test --tests com.paralife.engine.GoldenTraceEquivalenceTest</automated>
  </verify>
  <done>
- Pinned comment exists at `SimulationEngine.processDeaths` Phase 3c block describing the L1 deferred limitation.
- No behaviour change; `./gradlew test` passes (unchanged result).
- Commit message: `docs(19.5): pin L1 limitation comment at processDeaths Phase 3c`.
  </done>
</task>

<task type="auto">
  <name>Task 10: Final dual-run digest verification — full suite green and bit-stable</name>
  <files></files>
  <action>
**Verification gate** for the entire 19.5 fix set.

1. Run the full test suite: `./gradlew test`. All 166 existing + 3 new tests (H1 second test, H2 BondDisconnectIntegrationTest, H3 RegisterAtomicityTest) must pass.
2. Run the GoldenTrace dual-run digest specifically: `./gradlew test --tests com.paralife.engine.GoldenTraceEquivalenceTest`. Must remain green and **bit-stable across the full set of fixes** — this is the contract: no production-fidelity regression from any of H1/H2/H3/M1/M2/M3/M4/M6.
3. Optional manual smoke (per source plan): start `./gradlew bootRun`, ramp 50 bots via `BotRunner` (`./gradlew runBot`), check logs for any `stuck entity` / `stale registry` warnings. If any appear, file a follow-up — do not block this plan on it (the digest gate is the binding contract).
4. Confirm grep cleanliness:
   - `grep -rn "EntityEntry(" src/ | grep "Optional" | wc -l` → 0
   - `grep -n "cellStatusCache.get" src/main/java/com/paralife/engine/EligibleCellIndex.java | grep -i "0x01\|FLAG_OVERCROWDED"` → 0 (constraint #2 no longer reads bit 0 from cache)
   - `grep -n "synchronized" src/main/java/com/paralife/engine/EligibleCellIndex.java | grep "initialize"` → 1 line

If any of these fail, return to the failing task and fix before completing.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <done>
- `./gradlew test` passes (all 166 + 3 new = 169 tests, modulo any test count adjustments from M6's record refactor).
- `./gradlew test --tests com.paralife.engine.GoldenTraceEquivalenceTest` passes — dual-run digest bit-stable.
- All three grep checks above return the expected counts.
- No new commit (this is the verification gate; if anything was missed, the failing task gets a follow-up commit).
  </done>
</task>

</tasks>

<verification>
**Full-suite gate** (task 10):
- `./gradlew test` — all existing tests + 3 new tests green.
- `./gradlew test --tests com.paralife.engine.GoldenTraceEquivalenceTest` — dual-run digest bit-stable.
- Grep cleanliness checks (see task 10) return expected counts.

**Per-task gate** (tasks 1-9): each task's `<verify>` runs and passes before commit. Commit only the files that task touched. Never bundle task commits.

**Order invariant**: H1 → H2 → H3 → M1 → M2 → M3 → M4 → M6 → L1 → final-verify. M6 (delete `EntityEntry.sessionId`) goes after H2/H3/M2 because those tasks touch the very call sites M6 sweeps; doing M6 last avoids thrashing the same lines twice.
</verification>

<success_criteria>
- All 3 HIGH bugs fixed (H1, H2, H3) with regression tests covering the real failure mode (not just the predicate).
- All 6 in-scope MEDIUM hazards closed (M1, M2, M3, M4, M6 — M5 auto-resolves with M6).
- L1 pinned as a comment, L2 doc tightening folded into M6.
- L3, L4, L5, L6, L7 untouched (deferred per D-LOCK-4).
- `EntityEntry` is two-arg; `LiveEntityRegistry` Javadoc points at Phase 20.1 design-TBD.
- `EligibleCellIndex` constraint #2 reads `Cell.flags` directly; `EligibleCellIndex.initialize` is synchronized.
- `entityStatusCache` mirrors `cellStatusCache` (volatile + stage + swap).
- `SimulationEngine.clearStateForTest` + `ActionResolver.childIdCounter.set(0)` wired into `GoldenTraceEquivalenceTest.resetAll`.
- WS:entity 1:1 invariant preserved (no `List<String> sessionIds` introduced).
- `./gradlew test` passes; GoldenTrace dual-run digest bit-stable.
- 9 atomic commits on the working branch (one per task; task 10 produces no commit unless it surfaces a regression).
</success_criteria>

<output>
After completion, the executor should write a brief summary to:
`.planning/quick/260502-sds-implement-planned-p19-fixes-from-multi-r/260502-sds-SUMMARY.md`

Summary should list each task with its commit SHA, the files actually touched (compared to planned), any deviations from the plan with rationale, and final test count.
</output>
