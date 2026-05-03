---
task: generic
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-03T03:20:03Z
files: ["src/main/java/com/paralife/engine/EligibleCellIndex.java", "src/main/java/com/paralife/engine/LiveEntityRegistry.java", "src/main/java/com/paralife/engine/SimulationEngine.java", "src/main/java/com/paralife/engine/EnvironmentEngine.java", "src/main/java/com/paralife/engine/ActionResolver.java", "src/main/java/com/paralife/engine/BotRegistry.java", "src/main/java/com/paralife/engine/BondLifecycleListener.java", "src/main/java/com/paralife/engine/DeathFinalizer.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/test/java/com/paralife/engine/EligibleCellIndexTest.java", "src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java", "src/test/java/com/paralife/engine/PlacementDeterminismTest.java", "src/test/java/com/paralife/engine/EntityListIterationTest.java", "src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java", "src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java", "src/test/java/com/paralife/engine/GoldenTraceCapture.java", "src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java", "src/test/java/com/paralife/websocket/RegisterAtomicityTest.java", "src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java"]
usage:
  claude: { input: 64, output: 1745, cached: 6950126, tool_calls: 25, elapsed_s: 491.7 }
  gemini: { input: 3006990, output: 2587, cached: 2515313, tool_calls: 40, elapsed_s: 325.9 }
  codex: { input: 2271866, output: 13895, cached: 2065792, tool_calls: 64, elapsed_s: 296.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 56, elapsed_s: 697.8 }
synthesizer: claude
synthesized_at: 2026-05-03T03:20:03Z
---

# Cross-AI Review (First Pass)

## Claude Review

Done reading. Real findings emerged. Writing review.

# Phase 19 + 19.5 Cross-AI Review

## Bugs

### HIGH — `DeathFinalizer.finalizeBondedPairDeath` leaks predator session post-H2 (regression introduced by H2)
**File:** `src/main/java/com/paralife/engine/DeathFinalizer.java:129-155` (combined with `BotRegistry` post-bond state)
**Issue:** Pre-H2, predator's session was bound in `BotRegistry` to `predator.id()`; on BondedPair death, `unregisterByEntity(primaryId)` cleared `entityToSession[predator.id()]` AND queued a `DeathNotice` for the predator's session. Post-H2, `SimulationEngine.processInteractions` (`SimulationEngine.java:716`) calls `botRegistry.remapEntity(predatorSessionId, bondedPair.id())` — `entityToSession[predator.id()]` is REMOVED, replaced by `entityToSession[bondedPair.id()]`. On BondedPair death, `finalizeBondedPairDeath` still calls `unregisterByEntity(primaryId)` / `unregisterByEntity(secondaryId)` — both no-ops now (those keys gone). It NEVER calls `unregisterByEntity(bp.id())`. Result: `BotRegistry.bySession[predSid]` retains stale `BotState(bp.id(), pos)` after BondedPair gone; `entityToSession[bp.id()]` remains set; predator session never receives a `vD` frame; client never enters respawn FSM.
**Why it matters:** Two failure modes:
1. `TickBroadcaster.onTick` iterates `botRegistry.getAllBots()` and tries to send tick frames to predSid for a dead `bp.id()` (cell now empty/clobbered). Resource waste at minimum; on reconnect via stalled-grace token issued for `bp.id()`, `botRegistry.rebindSession(newSid, bp.id())` succeeds and binds the new session to a phantom entity (`ActionResolver.java:431` then silently drops every action frame because no live grid occupant matches).
2. The predator client never observes its bond's death and never respawns — hangs forever.
**Fix:** In `DeathFinalizer.finalizeBondedPairDeath` (around line 152, where `liveEntityRegistry.unregister(bp.id())` is already called), add `botRegistry.unregisterByEntity(bp.id())` BEFORE the existing `liveEntityRegistry.unregister(bp.id())`. This clears the BotRegistry maps AND queues the `DeathNotice` for the predator session so `TickBroadcaster.drainAndBroadcastDeaths` emits the terminal `vD`. Mirrors `finalizeParticleDeath` (line 112).

### HIGH — H2 follow-on `unregisterBySession(preySessionId)` strands the prey bot (no respawn signal)
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:707-714`
**Issue:** The H2-followon comment correctly notes that `unregisterByEntity` queues a `DeathNotice` → spurious `vD` for prey. The fix replaces it with `unregisterBySession(preySessionId)` — which does NOT queue a death notice. Prey's WebSocket session keeps `ATTR_ENTITY_ID = prey.id()` in attrs but `BotRegistry` no longer has any binding. Subsequent prey action frames hit `ActionResolver.java:421` (`botOpt.isEmpty()` → silently dropped). The prey client has no signal that its entity was absorbed — never enters respawn FSM, hangs indefinitely. The GoldenTrace digest gate is satisfied (no extra emit) but the prey bot is dead in the water for production.
**Why it matters:** Production bot pool degrades silently. Every bond-formed prey is a permanently stuck bot. At scale (Phase 21 1000+ bots), a steady state of 5-15% bonds means similar fraction of bots get stranded over time. WS:entity 1:1 invariant violated semantically — prey session has `ATTR_ENTITY_ID` set but no entity exists.
**Fix:** Three options, ordered by preference:
1. Send a domain-specific `vA` (absorbed) frame to prey before unregister — needs schema addition (`Frame.TickFrame` event-type byte). Strict but new wire shape.
2. Keep `unregisterByEntity` AND add a `DeathNotice` filter: `BotRegistry.unregisterByEntity(id, boolean queueDeath)` — overload that skips the death-notice queue. Prey gets cleared in BotRegistry without `vD`. Then explicitly `markDead(preySession)` followed by `worldWebSocketHandler.sendOutOfBand` of a `vD` (or new `vA` event).
3. Cheapest: queue the death notice as today (revert H2 follow-on) and update `golden-trace-phase19.json` to encode the `vD`-on-bond-prey behavior. Pretend bonding-as-death is a wire-level approximation. Deceptive.

### HIGH — H3 race window produces duplicate `LiveEntityRegistry` entries at the same Position
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:540-561`
**Issue:** The H3 sequence is per-WS-thread serial within `handleRegister`, but cross-thread two concurrent registrations can both pass `liveEntityRegistry.register` for the same sampled `pos_X` before either reaches `worldGrid.trySetEntity`:
1. Thread A: `sample()` → `pos_X`; `register(eid_A, pos_X)` → registry has `{(eid_A, pos_X)}`; `trySetEntity(pos_X)` → succeeds.
2. Thread B: `sample()` → `pos_X` (sampled BEFORE A's `notifyChanged` fired at line 585); `register(eid_B, pos_X)` succeeds (different eid); registry has `{(eid_A, pos_X), (eid_B, pos_X)}`; `trySetEntity(pos_X)` → fails; `unregister(eid_B)`.
During the B-register-to-B-unregister window, `liveEntityRegistry.snapshot()` returns BOTH entries. `SimulationEngine.processInteractions` (`SimulationEngine.java:401-410`) builds `particlePositions` and adds `pos_X` TWICE (one entry per duplicate). `Collections.shuffle` keeps both. Combat at `pos_X` then resolves twice — duplicate `CombatDelta` entries → double damage to neighbours, double bonding rolls, simRng consumption pattern diverges from baseline.
**Why it matters:** Determinism gate breaks under any concurrent registration. GoldenTrace test pre-registers all bots before tick 1 so the race never fires there — no test coverage. Production multi-bot ramps (and Phase 21 LoadHarness 1000+ bots) hit this regularly because `eligibleCellIndex.notifyChanged` is only called AFTER the successful path. `LiveEntityRegistry.register` accepts `(eid_B, pos_X)` because the conflict-detection (line 96-107 of `LiveEntityRegistry.java`) keys on `entityId`, not `position`.
**Fix:** Two options:
1. Move `liveEntityRegistry.register` to AFTER `trySetEntity` succeeds; add a synchronized block around the whole sample-register-grid-set sequence (re-introduces the pre-H3 race in the other direction — the original H3 problem). Not better.
2. Best: serialise the entire sample+register+trySetEntity sequence via the `eligibleCellIndex` monitor. Add a `EligibleCellIndex.sampleAndReserve(rng, entityId, Function<Position,Boolean> tryClaim)` method that holds the synchronized index monitor across `sample()` and `tryClaim.apply(pos)` — only releasing after either claim succeeds or the index updates. Caller passes a closure that does `liveEntityRegistry.register(entityId, pos)` then `worldGrid.trySetEntity`. The monitor prevents two threads from sampling overlapping positions in the same window. Requires careful lock-order design (index monitor → grid write lock — the current order per `EligibleCellIndex.java:35`).
3. Cheaper interim: in `LiveEntityRegistry.register`, also key-check by Position — throw or refuse if any existing entry matches the same Position. Surfaces the bug loudly; doesn't fix it.

### MEDIUM — `entityStatusCache` swap pattern asymmetric with `cellStatusCache` (O(N) handoff)
**File:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:1011-1019`
**Issue:** `cellStatusCache` uses `Collections.unmodifiableMap(cellStatusStaging) + this.cellStatusStaging = new HashMap<>()` — O(1) handoff (the documented hot-path fix per Wave 1 hotfix at line 1006-1010). M1's `entityStatusCache` uses `Map.copyOf(entityStatusStaging) + entityStatusStaging.clear()` — O(N) per tick. Comment at line 1014 acknowledges the cost, justified by "N is small". At Phase 21 scale (5000 entities, all infected during a mutagen event = 5000 entries × 2 maps including cells), `Map.copyOf` becomes the same hot-path issue Wave 1 already fixed for `cellStatusCache`.
**Why it matters:** Defeats the Wave 1 hotfix rationale. M1's stated goal is "mirror the cellStatusCache pattern" — the publish mechanic doesn't mirror.
**Fix:** Apply the same swap pattern: `this.entityStatusCache = Collections.unmodifiableMap(entityStatusStaging); this.entityStatusStaging = new HashMap<>();`. Identical wave-1 hotfix shape.

### MEDIUM — `compositeBallots` HashMap iteration order non-deterministic
**File:** `src/main/java/com/paralife/engine/ActionResolver.java:862, 882`
**Issue:** `compositeBallots = new HashMap<>()` then iterated via `for (var entry : compositeBallots.entrySet())` to dispatch composite movement. `executeCompositeMovement` mutates the shared `claimedCells` set and reads `worldGrid.getCell(target).hasOccupant()` — order-dependent when two composites contest overlapping target cells. Composite ids derive from `UUID.randomUUID()` (`SimulationEngine.java:766`), so iteration order varies across runs. `simRng` consumption is unaffected (no rng in this loop), so currently the only observable effect is "which composite moves when targets collide." Inert in 200-tick GoldenTrace (no composite-vs-composite collisions exercised), real at scale.
**Why it matters:** Phase 21 benchmark replay (D-06 spirit) won't be reproducible the moment composites get dense. Same class of bug as L1 but in a more common code path.
**Fix:** Use `LinkedHashMap` (preserves insertion order — composites added in shuffle order from `resolvedCompositeList`, which is itself seeded). Or sort `entrySet()` by `compositeId` before iteration for stronger guarantee independent of insertion timing.

### MEDIUM — `processOvercrowding` does not call `eligibleCellIndex.notifyChanged` when `FLAG_OVERCROWDED` mutates
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:1043-1049`
**Issue:** H1 made constraint #2 read `Cell.hasFlag(FLAG_OVERCROWDED)` directly (correct per CLAUDE.md D-40). But `processOvercrowding` adds/removes the flag without ever calling `eligibleCellIndex.notifyChanged(x, y)`. Constraint #2 is only re-evaluated at notifyChanged hooks (entity place/clear). A cell that gains FLAG_OVERCROWDED mid-tick stays "eligible" in the index until the next structural event in its 5×5 bbox; a cell that loses FLAG_OVERCROWDED stays "ineligible". `EligibleCellIndex.evaluateEligibility` reads the stale state via the cell read path correctly — but only AT notifyChanged time.
**Why it matters:** At sustained density, the eligible-set drifts from the truth. Placement may pick a "should-be-rejected" cell (causing extra `incLostRace` retries) or skip a "should-be-eligible" cell (under-utilising free space). Correctness drift, not crash.
**Fix:** In `processOvercrowding`, after the `setCell(...withAddedFlag/withRemovedFlag(FLAG_OVERCROWDED))` calls (lines 1044, 1048), invoke `if (eligibleCellIndex != null) eligibleCellIndex.notifyChanged(x, y)`. Same hook discipline as the move/place/clear sites.

### MEDIUM — `BondLifecycleListener.onBondFormed` writes `ATTR_ENTITY_ID` to a STALLED session without coordination
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:813-823` + `src/main/java/com/paralife/engine/SimulationEngine.java:715-720`
**Issue:** Bond formation runs at `@Order(10)`. `markStalled` runs at `@Order(100)` (called from `TickBroadcaster.onTick` overflow callback) — same tick. If predator's session got stalled in tick T-1 but `afterConnectionClosed` hasn't fired yet (stalled-held path, `WorldWebSocketHandler.java:363-373`), the session is still in `sessionRegistry` in tick T. `onBondFormed` at tick T writes `attrs[ATTR_ENTITY_ID] = bp.id()` even though `markStalled` has already removed it. The resume token in registry is mapped to whatever entityId was alive at stall time (the original predator id). Now `BotRegistry` says predSid → bp.id(); resume token says predator.id(). Reconnect:
- `ResumeTokenRegistry.tryRebind(token)` returns predator.id().
- `botRegistry.rebindSession(newSid, predator.id())` — `entityToSession[predator.id()]` is GONE (H2 remap removed it). Rebind silently returns false.
- Handler still puts `ATTR_ENTITY_ID = predator.id()` in attrs (line 474) and sends Sync(predator.id(), ...). The session is now bound to a non-existent entity.
**Why it matters:** Phantom-entity bug after stalled-then-bond-then-reconnect. Action frames silently dropped. WS:entity 1:1 violated.
**Fix:** In `onBondFormed`, check `isStalled(session.getAttributes())` first; if stalled, do NOT write ATTR_ENTITY_ID — the resume-token registry is the authoritative source for stalled sessions and it already holds the original entityId. Additionally, `BotRegistry.remapEntity` should refuse the remap if the target session is in a STALLED state — but `BotRegistry` doesn't know about session state. Cleaner: have `SimulationEngine` skip the H2 remap entirely if the session is STALLED (consult `WorldWebSocketHandler.isStalled(session)` before calling `botRegistry.remapEntity`). Trades complexity for the "predator stalls during bond" being rare; can land as Phase 20 follow-up.

## Determinism risks

### MEDIUM — Phase 3c `compositeRegistry.getAll()` iteration may consume `simRng` non-deterministically (L1 acknowledged but currently mitigated only by scenario)
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:1099-1108, 1347`
**Issue:** Pin comment correctly notes the issue and that 200-tick GoldenTrace never reaches panic-zone. But the L1 mitigation is "the test scenario doesn't trigger it" — fragile. Any future tuning of `criticalEnergyPercent` or any test that drains a composite below 12% breaks bit-stability silently.
**Fix:** Wrap `compositeRegistry.getAll()` with `.stream().sorted(Comparator.comparing(CompositeState::getCompositeId))` at the iteration site. ~µs cost at N≤10 composites; eliminates the latent risk entirely. Mirrors L1's documented mitigation but proactive.

## Scale concerns

### MEDIUM — `LiveEntityRegistry.snapshot()` allocation pressure unchanged at 4-5 calls/tick
**File:** `src/main/java/com/paralife/engine/LiveEntityRegistry.java:149-153` + `SimulationEngine.java` 7 call sites
**Issue:** Phase 19 REVIEW IN-03 already flagged this. Phase 19.5 did not address it. At Phase 21 N=1000 + 4-5 snapshot calls per tick = 4000-5000 allocations × O(N log N) sort + monitor acquisition. Multiplied across `SimulationEngine` AND `EnvironmentEngine.tickBuffsAndInfections` AND `EnvironmentEngine.buildStatusCaches` = 6-8 calls/tick. At 10 ticks/sec ≈ 60-80 sorts/sec on the tick thread.
**Why it matters:** Scales poorly into Phase 21 1000+ entity target.
**Fix:** `SimulationEngine.processTick` captures `entitySnapshot()` once at entry and threads it as a parameter to `processInteractions`, `processEnergyDecay`, `processOvercrowding`, `processDeaths`. `EnvironmentEngine.onTick` does the same for its consumers. Lock acquired once per tick; one allocation. Phase 21 hot path candidate.

### MEDIUM — Concrete fix proposal for TD-19.5-A (`OutboundSender.awaitAllSessionQueuesDrained` race)
**File:** `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java:344-370` + `src/main/java/com/paralife/admission/OutboundSender.java:204-237`
**Issue:** Reviewer asked for concrete fix. The race: `queue.take()` returns BEFORE `synchronized(session) { sendMessage; onEmit }` — window between dequeue and monitor acquire. `queueDepth() == 0` can be true while an in-flight frame is mid-emit. Acquiring the session monitor as a barrier works ONLY if the awaiter's monitor acquire serialises against the in-flight call — which it does — BUT only after the drain VT enters the monitor. If awaiter races the drain VT to the monitor between `take()` and `synchronized(session)`, the awaiter wins (gets monitor first → empty), returns, then drain VT enters and emits → emit count diverges by ±1.
**Fix:** Add a per-session `AtomicInteger inFlightEmits` to `OutboundSender`. Increment at the top of the drain loop AFTER `take()` returns, decrement INSIDE the `synchronized(session)` block AFTER `onEmit`. `awaitAllSessionQueuesDrained` checks `queueDepth(sid) == 0 && outboundSender.inFlight(sid) == 0` for ALL sessions BEFORE the monitor barrier. Cheap, race-free, doesn't require monitor changes.

```java
// OutboundSender.drainLoop (line 204):
while (!Thread.currentThread().isInterrupted()) {
    Frame frame = queue.take();
    inFlightCounters.get(sessionId).incrementAndGet(); // NEW
    if (!session.isOpen()) {
        inFlightCounters.get(sessionId).decrementAndGet(); // NEW
        continue;
    }
    try {
        // ... existing encode + synchronized + sendMessage + onEmit ...
    } finally {
        inFlightCounters.get(sessionId).decrementAndGet(); // NEW
    }
}
```

## Test gaps

### LOW — `attemptPlacementForTest` test seam diverges from production `handleRegister` path
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:248-264` vs `handleRegister` at 540-561
**Issue:** `attemptPlacementForTest` does sample → trySetEntity → notifyChanged. Production `handleRegister` (post-H3) does sample → `liveEntityRegistry.register` → trySetEntity → (rollback or notifyChanged). `PlacementDeterminismTest` and `GoldenTraceEquivalenceTest` exercise `attemptPlacementForTest` — H3's register-first ordering is NEVER tested through the actual production code path. Same H1-pattern bug ("test only covers the predicate, not the pipeline").
**Fix:** Make `attemptPlacementForTest` mirror `handleRegister` exactly — call `liveEntityRegistry.register(entityId, pos)` before `trySetEntity`, with rollback on failure. Or replace test seam usage in `GoldenTraceEquivalenceTest.driveScenario` (line 289) with direct `handleRegister` calls via mock RegisterFrame; loses some test ergonomics but exercises the real path.

### LOW — `LiveEntityRegistryInvariantTest.assertRegistryMatchesGrid` uses Sets, hides duplicate-position entries
**File:** `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java:299-325`
**Issue:** Builds `Set<Position> registryPositions` from snapshot. If H3 race produces two entries `(eid_A, pos_X)` and `(eid_B, pos_X)`, the Set dedupes silently — invariant passes. The HIGH duplicate-position bug above would never be caught.
**Fix:** Replace with `List<Position>` and assert `.size() == gridOccupants.size()` AND list equals. Or assert `liveEntityRegistry.snapshot().stream().map(EntityEntry::position).distinct().count() == liveEntityRegistry.size()` as a separate "no duplicate positions" invariant.

### LOW — `BondDisconnectIntegrationTest` doesn't cover STALLED-during-bond
**File:** `src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java`
**Issue:** Single happy-path test. Doesn't cover: predator session STALLED at the moment of bond formation (the MEDIUM `onBondFormed` scenario above). Doesn't cover BondedPair death path (the HIGH `finalizeBondedPairDeath` regression above).
**Fix:** Add two tests: (1) predator STALLED then bond forms then reconnect → entity must rebind to a live entity, not phantom. (2) bond forms then BondedPair dies before disconnect → predator session receives `vD` frame; `BotRegistry.bySession[predSid]` is empty.

## H1/H2/H3 sanity check

**H1:** Confirmed correct. `EligibleCellIndex.evaluateEligibility` line 238 reads `cell.hasFlag(Cell.FLAG_OVERCROWDED)` directly; new test `constraint2RejectsOvercrowded_viaSimulationEnginePath` exercises the residue scenario. No regression. (Caveat: see MEDIUM about `processOvercrowding` not firing notifyChanged — the H1 fix is correct but the supporting hook discipline is incomplete.)

**H2:** Closes the documented bug (predator-disconnect leaves stale `BondedPair` entry) but introduces TWO new HIGH bugs above (BondedPair death no longer cleans BotRegistry → phantom; prey unregister has no death signal). The `BondLifecycleListener` callback is structurally sound (decoupled engine from WS layer correctly) but the cleanup math at the OTHER end of the BondedPair lifecycle wasn't updated. The STALLED-during-bond path was not considered. **Net: ships a bug while closing one.**

**H3:** Closes the originally-documented "grid has it, registry doesn't" race but introduces the cross-thread "registry has it twice" race documented in the HIGH above. The `RegisterAtomicityTest` only validates the orphan-skip path, not the duplicate-position path. **Net: trades one race for a less detectable one.**

## Verdict

**NEEDS-REWORK** — three HIGH findings (H2-introduced BondedPair-death cleanup leak; H2-followon prey-strand; H3 duplicate-position race) all stem from the 19.5 fixes themselves and have concrete failure modes at production scale. Each blocks Phase 20 work that depends on `BotRegistry`/`LiveEntityRegistry` invariants holding. Recommended sequence: fix HIGH-1 (one-line addition in `DeathFinalizer.finalizeBondedPairDeath`), HIGH-2 (decide on prey-cleanup signaling — requires user input), HIGH-3 (sample-and-reserve under index monitor — requires design decision); then re-run GoldenTrace and reseed baseline if HIGH-2 changes wire shape.

---

## Gemini Review

### HIGH — Prey's WebSocket session hangs open indefinitely after bond formation
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:708-713`
**Issue:** The H2 follow-on deviation changed `botRegistry.unregisterByEntity(prey.id())` to `botRegistry.unregisterBySession(preySessionId)` to intentionally avoid queuing a `DeathNotice`. However, `unregisterBySession` neither sends a terminal frame nor closes the WebSocket connection.
**Why it matters:** Without the `vD` frame, the prey client's respawn FSM never triggers. The prey client becomes permanently bricked with an open WebSocket connection that receives no further frames. This breaks the 100-bot continuous simulation if bots get stuck after being absorbed.
**Fix:** Revert the H2 follow-on deviation: use `botRegistry.unregisterByEntity(prey.id())` so the prey receives a `vD` frame and respawns. From the prey client's perspective, its entity is gone and it must respawn; the `vD` frame is the correct mechanism for this in the current schema.

## Scale concerns

### MEDIUM — Multiple O(N log N) sorts per tick via `LiveEntityRegistry.snapshot()`
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:259` and `src/main/java/com/paralife/engine/EnvironmentEngine.java:690`
**Issue:** `liveEntityRegistry.snapshot()` is called 4–5 times in `SimulationEngine` and twice in `EnvironmentEngine` per tick. Each call allocates a new `ArrayList` and sorts it using `copy.sort(rowMajorComparator)`.
**Why it matters:** At the target scale of 1000+ entities (Phase 21), this results in ~7 array allocations and O(N log N) sorts per tick, redundantly executed on the single-threaded mutation core.
**Fix:** Pre-compute the snapshot once at the top of the `@EventListener` method (e.g., `SimulationEngine.onTick`) and pass the `List<LiveEntityRegistry.EntityEntry>` down to the handler sub-steps (`processInteractions`, `processEnergyDecay`, etc.).

## Test gaps

### HIGH — Flaky `awaitAllSessionQueuesDrained` VT race undermines semantic equivalence gate
**File:** `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java:264-282`
**Issue:** The test observes `queueDepth == 0` and grabs `synchronized(session)` as a barrier. However, the `OutboundSender` VT might have just dequeued the last frame but not yet entered the `synchronized(session)` block to emit it. The test thread acquires the lock first, proceeds, and reads the digest before the final frame is emitted.
**Why it matters:** This causes the ~40% flakiness in isolated runs mentioned in the contextual caveats. It compromises the reliability of the Phase 19/19.1 determinism contracts.
**Fix:** Add `AtomicLong offered` and `AtomicLong emitted` per session in `OutboundSender` (increment `offered` in `offer()`, increment `emitted` in `drainLoop` after `sendMessage()`). Expose `getOfferedCount` and `getEmittedCount`. In the test, replace the monitor barrier with `while (offered.get() != emitted.get()) Thread.sleep(1);`.

## H1/H2/H3 sanity check

- **H1 (EligibleCellIndex constraint)**: **Passed.** `EligibleCellIndex.evaluateEligibility` correctly reads `Cell.FLAG_OVERCROWDED` directly, bypassing the redacted cache. The added test proves the end-to-end pipeline via `SimulationEngine.processOvercrowding`.
- **H2 (Bond formation registry remap)**: **Passed (Core Fix).** `SimulationEngine` correctly remaps the `BotRegistry` for the predator and triggers `BondLifecycleListener` to update the WebSocket session's `ATTR_ENTITY_ID`. The test proves no stale registry entries leak. *(Note: The follow-on deviation regarding prey cleanup introduced a bug flagged above, but the core H2 predator fix is sound).*
- **H3 (Register sequence reorder)**: **Passed.** `WorldWebSocketHandler` registers in `LiveEntityRegistry` before mutating the grid, with proper rollback on `trySetEntity` failure. This successfully closes the missing-entity sub-tick transient.

## Verdict

`NEEDS-REWORK` — The HIGH severity lifecycle bug introduced by the H2 follow-on deviation must be reverted to prevent prey clients from being permanently bricked upon bonding.

---

## Codex Review

## Bugs

### HIGH — BondedPair death leaves the controlling bot registered
**File:** `src/main/java/com/paralife/engine/DeathFinalizer.java:134-152`  
**Issue:** After H2, `BotRegistry` maps the predator session to `bondedPair.id()`, but `finalizeBondedPairDeath` still unregisters only `primaryEntityId()` and `secondaryEntityId()`. The controlling session never gets `unregisterByEntity(bp.id())`.  
**Why it matters:** The predator client does not receive a terminal `vD` frame and remains mapped to a dead `BondedPair` after the grid/live registry are cleared. This breaks WS:entity 1:1 and death semantics.  
**Fix:** Call `botRegistry.unregisterByEntity(bp.id())` as the bot-facing death path, then keep constituent cleanup for buffs/infections. Add a bond-then-kill test asserting `BotRegistry` empties and a death notice is queued for the bonded pair id.

### HIGH — Bond remap does not remap resume-token identity
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:705-718`, `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:814-822`, `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:741-779`  
**Issue:** H2 updates `BotRegistry` and `ATTR_ENTITY_ID`, but the active/stalled resume token remains keyed to the old predator id. If the session is already STALLED, or stalls after bonding, resume/expiry still operates on the old id.  
**Why it matters:** `cleanupByEntityId(oldPredatorId)` finds no `BotRegistry` mapping after the remap and returns without clearing the `BondedPair`; reconnect can also rebind to the obsolete id while `botRegistry.rebindSession(...)` failure is ignored.  
**Fix:** Add an identity-remap API for resume-token entries and admission metric snapshots, and invoke it during bond formation. `WorldWebSocketHandler` should make the remap atomic for alive and stalled sessions and fail/rollback visibly if `BotRegistry.rebindSession` cannot bind the returned id.

### HIGH — Registration can commit an entity after the session has closed
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:545-609`, `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:825-848`  
**Issue:** `handleRegister` mutates `LiveEntityRegistry`/`WorldGrid` before it sets `ATTR_ENTITY_ID`; concurrent `afterConnectionClosed → cleanupBot` can run in that window and no-op because the attr and bot mapping are not present yet. The register path then completes for an unregistered/closed session.  
**Why it matters:** A disconnected session can leave an occupant on the grid, an entry in `LiveEntityRegistry`, a `BotRegistry` mapping, and an admission slot with no live WebSocket owner.  
**Fix:** Serialize register/cleanup on the session monitor, re-check `session.isOpen()` before and after placement, and rollback `LiveEntityRegistry`, grid, `BotRegistry`, resume token, and admission slot if the session is closed or sync send fails.

## Determinism Risks

### MEDIUM — Action resolution shuffles an unordered ConcurrentHashMap snapshot
**File:** `src/main/java/com/paralife/engine/ActionResolver.java:389-440`  
**Issue:** Actions are drained from a `ConcurrentHashMap` and appended in `entrySet()` order before `Collections.shuffle(..., actionRng)`. The seeded shuffle is deterministic only if its input order is deterministic.  
**Why it matters:** Under concurrent inbound actions, equal seeds and equal logical action sets can resolve in different orders, changing move conflicts, combat, reproduction, and outbound frames.  
**Fix:** Copy actions into a list sorted by stable key, e.g. session id or current row-major bot position, before shuffling.

## Scale Concerns

### MEDIUM — Repeated live-entity snapshots re-sort the same tick state
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:291-293`, `src/main/java/com/paralife/engine/LiveEntityRegistry.java:149-152`  
**Issue:** `SimulationEngine` calls `entitySnapshot()` repeatedly in one tick, and each call copies and sorts O(N log N). Several calls occur before any structural mutation.  
**Why it matters:** At 1000+ entities this adds avoidable allocation, locking, and sort cost on the tick thread.  
**Fix:** Capture one snapshot per stable subphase and pass it down; refresh only after code that can structurally mutate live entities.

### LOW — Eligible-cell dirty updates are constant-time but lock-heavy
**File:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:211-249`  
**Issue:** Each `notifyChanged` re-evaluates 25 cells, and each candidate can walk neighbours and count neighbouring occupants with many `WorldGrid.getCell` read-lock acquisitions.  
**Why it matters:** High-density registration/reproduction/death bursts will pay hundreds to thousands of read-lock operations per structural mutation.  
**Fix:** Consider a packed local neighbour-count helper over a single grid snapshot or a small locked read section for the 5x5/7x7 area.

## Test Gaps

### MEDIUM — `LiveEntityRegistryInvariantTest` ignores entity ids
**File:** `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java:299-324`  
**Issue:** The invariant helper compares only occupied positions. A registry entry with the wrong `entityId` at the correct position passes.  
**Why it matters:** The H2/H3 class of bugs is often “right cell, wrong identity”; this test would miss a stale old id mapped to a new occupant.  
**Fix:** Compare `Position -> EntityIds.entityIdOf(occupant)` against `Position -> entry.entityId()`.

### LOW — `EligibleCellIndexTest` does not exercise the real overcrowding engine path
**File:** `src/test/java/com/paralife/engine/EligibleCellIndexTest.java:94-123`  
**Issue:** The “viaSimulationEnginePath” test manually sets `Cell.FLAG_OVERCROWDED`; it does not call `SimulationEngine.processTick` to set the flag and then refresh the index.  
**Why it matters:** It proves the predicate, not the pipeline that previously failed.  
**Fix:** Use a Spring or direct-engine fixture with neighbours over threshold, run a tick, notify/rebuild the index, then assert exclusion.

### LOW — `GoldenTraceEquivalenceTest` does not force session-backed bonds
**File:** `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java:261-330`  
**Issue:** The guaranteed adjacent bonding pairs are registered only in `LiveEntityRegistry`, not `BotRegistry`/`SessionRegistry`.  
**Why it matters:** The H2 listener path is not guaranteed to run for the forced bonds.  
**Fix:** Attach mocked sessions for the seeded pairs or assert at least one bonded session remap occurred.

### LOW — `BondDisconnectIntegrationTest` covers only post-bond normal cleanup
**File:** `src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java:119-147`  
**Issue:** It calls `cleanupBot` after bond formation completes; it does not cover concurrent disconnect, STALLED sessions, resume-token expiry, or BondedPair death.  
**Why it matters:** Those are the remaining H2 failure modes.  
**Fix:** Add separate tests for stalled-before-bond, stall-after-bond, and bond-then-death.

### LOW — `RegisterAtomicityTest` does not drive `handleRegister`
**File:** `src/test/java/com/paralife/websocket/RegisterAtomicityTest.java:80-119`  
**Issue:** The test manually inserts an orphan registry entry and manually rolls it back; it does not latch inside the actual register path.  
**Why it matters:** It misses the real WS close/register interleaving and `trySetEntity` lost-race behaviour.  
**Fix:** Add a test seam/latch around register-first and `trySetEntity`, then close the session and assert rollback.

### LOW — `PlacementDeterminismTest` bypasses production registration
**File:** `src/test/java/com/paralife/engine/PlacementDeterminismTest.java:97-108`  
**Issue:** `attemptPlacementForTest` bypasses admission, session attrs, `BotRegistry`, and `LiveEntityRegistry`.  
**Why it matters:** It proves serial sparse-set sampling, not production `r|` lifecycle determinism or rollback under contention.  
**Fix:** Keep this unit contract, but add a production-path deterministic registration test with real `handleRegister` frames.

## H1/H2/H3 Sanity Check

H1 code is correct: `EligibleCellIndex` now reads `Cell.FLAG_OVERCROWDED` directly at `EligibleCellIndex.java:233-238`. The class Javadoc is stale at `EligibleCellIndex.java:24-26` and still says cache bit 0 is the source.

H2 is not fully closed. Normal post-bond disconnect is covered, but death, stalled/reconnect, and concurrent cleanup paths still break identity cleanup.

H3 closes the specific “grid has it, registry does not” tick window, but it does not make registration atomic against WebSocket close.

## Verdict

NEEDS-REWORK

---

## Opencode Review

I'll start by reading the core production files that are central to the review.Now let me read the test files:Now let me check the remaining test files and search for any remaining patterns that might be problematic:Now I have enough context. Let me also verify one quick check on the `ActionResolver.clearStateForTest` is correct and review some more edge cases:Now I have comprehensive knowledge of the code. Let me compile the final review.

---

# Cross-AI Review: Phase 19 + Phase 19.5 (Paralife)

## Bugs

### MEDIUM — STALLED session during bond formation: ResumeTokenRegistry entityId not remapped
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:814-823` + `src/main/java/com/paralife/admission/ResumeTokenRegistry.java:127-148`
**Issue:** `onBondFormed` updates BotRegistry (L716) and session attributes (L821), but does NOT update the `ResumeTokenRegistry` STALLED token. When a session is STALLED between ticks and a subsequent tick forms a bond, `onBondFormed` finds `sessionRegistry.getSession(predatorSessionId) == null` (unregistered by `afterConnectionClosed:357`) and skips the attribute update. The STALLED token's `entityId` still holds the obsolete `predParticleId`. Two failure paths: (a) grace expiry sweep calls `cleanupByEntityId(predParticleId)` → `botRegistry.getSessionByEntity(predParticleId)` returns `Optional.empty()` (remapEntity cleared it at L705-716) → the no-bound-session branch at `cleanupByEntityId:746-753` only decrements gauges → BondedPair remains on grid and in LiveEntityRegistry until natural death. (b) Client reconnect via resume token → `tryRebind` returns `predParticleId` → `handleRegister` calls `botRegistry.rebindSession(newSessionId, predParticleId)` at L492 → `entityToSession.get(predParticleId)` returns null → returns `false` **silently** (no caller checks the boolean) → bot thinks it owns a non-existent entity.
**Why it matters:** Resource leak (BondedPair lives on grid without controlling session through entire grace duration) plus **silent rebind failure** — the bot gets a valid sync frame but no actual entity binding. Self-corrects only when the BondedPair dies from energy decay.
**Fix:** `onBondFormed` should iterate `ResumeTokenRegistry` STALLED entries for `predatorParticleId` and remap to `bondedPairId`, or add `ResumeTokenRegistry.updateStalledEntityId(oldEntityId, newEntityId)`. Alternatively, in `markStalled`, capture the entityId snapshot into the token only at sweep time by re-deriving from BotRegistry rather than storing it at stall time.

### LOW — Silent return-value discard on `botRegistry.rebindSession` failure
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:492`
**Issue:** `botRegistry.rebindSession(session.getId(), rebind.entityId())` returns `boolean` (false when entityId unknown) but the result is discarded. Combined with the STALLED+bond scenario above (or any future `entityId` mismatch between STALLED token and BotRegistry), the handler continues to send a valid sync frame (`S|entityId|token`) with a stale entityId while no binding actually occurred. Pre-existing, but exposed by H2's BotRegistry remap.
**Why it matters:** Client enters a split-brain state: believes it controls entityId=X but no BotRegistry mapping exists. Subsequent action frames are silently dropped (session not in BotRegistry). `isAlive` returns true but `PerceptionBroadcaster` can't find the entity.
**Fix:** Check the return value and handle failure — send an error frame or fall through to fresh-registration.

### LOW — `evaluateEligibility` constraint-3 reads `cellStatusCache` parameter but it is `@SuppressWarnings("unused")`
**File:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:227`
**Issue:** The `cellStatusCache` parameter on `evaluateEligibility` is annotated `@SuppressWarnings("unused")` (L227). H1 correctly removed the `cellStatusCache.get()` read for OVERCROWDED, but the parameter is now entirely unused in the method body — constraint-2 reads `cell.hasFlag()` from the grid directly, and constraint-3 walks neighbors via `worldGrid.getNeighbors()`. No remaining uses of this parameter.
**Why it matters:** Footgun for future env-effect constraints (e.g., "don't place in TOXIN zones"). A contributor reaching for this parameter could write `(cellStatusCache.get(pos) & 0x02) != 0`, which would work today at the cache layer but misattribute the purpose of the `@SuppressWarnings` annotation.
**Fix:** Remove the parameter entirely from `evaluateEligibility`, or actually use it for future env filters (and drop the `@SuppressWarnings`). The hoisted read at `notifyChanged:212` would then serve its documented purpose for those future constraints. Shortest fix: keep the annotation comment updated to document *why* the parameter is reserved.

## Determinism risks

No new determinism risks found. L1 (Phase 3c `ConcurrentHashMap` iteration) is correctly deferred and pinned. `LiveEntityRegistry.snapshot()` sort by row-major index is deterministic. `evaluateEligibility` reads `worldGrid.getNeighbors()` which returns an `ArrayList` — order is deterministic (created by `getNeighbors` in WorldGrid). All other determinism hazards are in the known caveats.

## Scale concerns

### LOW — `entitySnapshot()` called 5+ times per tick, each allocating + sorting
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:291-294`, `LiveEntityRegistry.java:149-153`
**Issue:** Already documented as IN-03 in Phase 19 review and Phase 21 revisit planned. Flagging because the HotSpot JIT may not be able to eliminate the duplicate sorts when `snapshot()` is called within a single `processTick` invocation, since calls go through different private methods (`processInteractions`, `processEnergyDecay`, etc.). Each call allocates `new ArrayList<>(dense)` + `copy.sort(rowMajorComparator)` — O(N log N) × 5 calls.
**Why it matters:** At N=5000 this becomes material. The five `entitySnapshot()` calls inside a single `processTick()` should be a single snapshot captured at the top and passed as a parameter.
**Fix:** Capture once in `processTick` and pass `List<EntityEntry>` to each sub-method. Already noted for Phase 21.

## Test gaps

### MEDIUM — `BondDisconnectIntegrationTest` doesn't cover STALLED + bond + expiring grace window
**File:** `src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java:82-156`
**Issue:** The test exercises the happy path (predator alive, forms bond, then disconnects). It does NOT test: (a) STALLED session → bond formation → grace expiry → sweep calls `cleanupByEntityId` with stale entityId; (b) STALLED session → bond formation → client reconnects with resume token within grace window → rebind fails silently. The `RegisterAtomicityTest` tests the register-first transient but not the STALLED interaction with bond formation.
**Why it matters:** The ResumeTokenRegistry entityId staleness bug (flagged above) has zero test coverage. The bond-disconnect test passes because `onBondFormed` successfully updates the live session attribute — but the STALLED path where the session is absent from `sessionRegistry` is untested.
**Fix:** Add a test that marks the predator session as STALLED (call `markStalled`), then fires a tick that forms a bond, then expires the grace window and asserts the sweep correctly reaps the BondedPair via `cleanupByEntityId`. Verify BotRegistry is remapped and the sweep finds the right entity.

### LOW — `RegisterAtomicityTest` only tests the benign transient direction
**File:** `src/test/java/com/paralife/websocket/RegisterAtomicityTest.java:81-99`
**Issue:** Tests "registry has entry, grid cell is empty" → tick skips without error. Does NOT test "grid has entity, registry is empty" which is the pre-H3 bug direction that entity-list iteration would skip. The test validates the H3 fix is non-harmful (benign transient doesn't crash) but doesn't validate the H3 fix prevents the harmful transient from occurring.
**Why it matters:** If a future refactor accidentally reverts the register-ordering, this test won't catch it. The test proves current code tolerates the transient, not that the bug is prevented.
**Fix:** Either add a test that asserts the harmful transient is unreachable (check that the `@Order(10)` tick handler and `handleRegister` can never interleave to observe "grid has it, registry doesn't"), or accept that the existing `LiveEntityRegistryInvariantTest` scenarios cover the post-tick invariant well enough.

## H1/H2/H3 sanity check

### H1 — VERIFIED FIXED
`EligibleCellIndex.evaluateEligibility:238` now reads `cell.hasFlag(Cell.FLAG_OVERCROWDED)` directly from `Cell.flags` — the authoritative server-global state set by `SimulationEngine.processOvercrowding`. The `cellStatusCache` bit-0 redaction (CLAUDE.md D-40) no longer silently breaks placement constraints. The test `constraint2RejectsOvercrowded_viaSimulationEnginePath` (L94-124) exercises the end-to-end `setCell(...withAddedFlag(FLAG_OVERCROWDED))` path. **No regression** — the `@SuppressWarnings("unused")` on the cellStatusCache parameter at L227 is a code-smell remnant (see Bugs above).

### H2 — VERIFIED FIXED WITH RESIDUAL GAP
`SimulationEngine.java:692-720` correctly:
1. Unregisters predator+prey from LiveEntityRegistry, registers BondedPair
2. Calls `botRegistry.unregisterBySession(preySessionId)` (NOT unregisterByEntity — avoids spurious vD frame)
3. Calls `botRegistry.remapEntity(predatorSessionId, bondedPair.id())`
4. Fires `bondLifecycleListener.onBondFormed(predatorSessionId, bondedPairId)`
5. `WorldWebSocketHandler.onBondFormed` updates session attributes

The `BondDisconnectIntegrationTest` validates: BotRegistry remapped, predator session controls BondedPair, prey session empty, `cleanupBot` reaches correct entityId, LiveEntityRegistry clean after disconnect.

**Residual gap**: the `ResumeTokenRegistry` STALLED token entityId is NOT remapped when the session is STALLED (see Bugs above). The `onBondFormed` null-guard at L815-819 handles the "session already disconnected" case but doesn't address the STALLED token. This is a follow-on gap, not a regression of the fix — the `BotRegistry` and session-attribute components are correctly fixed.

### H3 — VERIFIED FIXED
`WorldWebSocketHandler.handleRegister:544-561` now calls `liveEntityRegistry.register(entityId, pos)` BEFORE `worldGrid.trySetEntity()`. On failure, rolls back via `liveEntityRegistry.unregister(entityId)`. The `RegisterAtomicityTest` validates the benign transient direction. The harmful transient (grid has entity, registry doesn't) is prevented by the register-first ordering — no tick read at `@Order(10)` can fire between `trySetEntity` and `register` because `register` happens first.

## Verdict

**READY-WITH-FOLLOWUPS**

The three HIGH bugs (H1, H2, H3) are correctly fixed. The six MEDIUM hazards (M1–M6) are closed. L1 is properly pinned. No CRITICAL or HIGH findings remain.

The MEDIUM STALLED+bond ResumeTokenRegistry gap is a real bug but self-corrects (BondedPair dies naturally), requires a specific timing sequence, and has a clear containment fix path (`ResumeTokenRegistry.updateStalledEntityId` or sweep-time BotRegistry re-derivation). The rebind-silent-failure on `rebindSession` return-value is pre-existing. Both are follow-on candidates for Phase 20 or a 19.5.1 quick-task, not blockers.

---

## Consensus Summary

### Agreed Strengths
- H1 (EligibleCellIndex) verified fixed: `evaluateEligibility` reads `Cell.FLAG_OVERCROWDED` directly; new test exercises `SimulationEngine.processOvercrowding` pipeline (claude, gemini, codex, opencode).
- H2 core predator remap verified: `BotRegistry.remapEntity` + `BondLifecycleListener.onBondFormed` correctly update session attribute; `BondDisconnectIntegrationTest` covers happy-path post-bond cleanup (gemini, codex, opencode).
- H3 closes the originally-documented "grid has entity, registry empty" sub-tick window via register-first + rollback ordering (claude, gemini, codex, opencode).
- L1 (Phase 3c `compositeRegistry` iteration) correctly pinned with documented scenario-based mitigation (claude, opencode).

### Agreed Concerns
- **HIGH — H2 follow-on `unregisterBySession(preySessionId)` strands prey bot.** Prey session retains `ATTR_ENTITY_ID` but no `BotRegistry` binding; no `vD`/respawn signal sent; client hangs indefinitely. WS:entity 1:1 violated semantically. Breaks 100-bot continuous sim; degrades silently at scale (claude, gemini). [`SimulationEngine.java:707-713`]
- **HIGH — `DeathFinalizer.finalizeBondedPairDeath` leaks predator session post-H2.** Only unregisters `primaryEntityId`/`secondaryEntityId` (both no-ops post-remap); never calls `unregisterByEntity(bp.id())`. Predator session retains stale BotRegistry entry → no terminal `vD` → reconnect can phantom-bind (claude, codex). [`DeathFinalizer.java:129-155`]
- **MEDIUM — STALLED-session-during-bond resume-token identity not remapped.** `onBondFormed` updates BotRegistry + session attrs but `ResumeTokenRegistry` still keyed to old predator id; reconnect via token returns stale id, `botRegistry.rebindSession` silently fails (return value discarded at `WorldWebSocketHandler.java:492`); BondedPair leaks until natural death (claude, codex, opencode).
- **MEDIUM — `LiveEntityRegistry.snapshot()` allocation/sort pressure unaddressed.** 4–7 calls/tick × O(N log N); should be captured once at top of `processTick`/`onTick` and threaded down. Phase 21 1000+ entity hot-path (claude, gemini, codex).
- **MEDIUM — `awaitAllSessionQueuesDrained` VT race causes ~40% test flakiness.** Test thread can win `synchronized(session)` between `take()` and emit; counter-pair fix (`offered`/`emitted` or `inFlight` AtomicInteger incremented after `take()`, decremented inside monitor after `onEmit`) is concrete and cheap (claude, gemini).
- **MEDIUM/LOW — `LiveEntityRegistryInvariantTest` lacks identity/duplicate-position rigor.** Compares `Set<Position>` only; misses both "right cell, wrong entityId" and "two registry entries at same Position" failure modes (claude, codex).
- **LOW — `BondDisconnectIntegrationTest` happy-path-only.** No coverage for STALLED-during-bond, stall-after-bond, or BondedPair death; the most likely H2 follow-on failure modes are untested (claude, codex, opencode).
- **LOW — `RegisterAtomicityTest` / `PlacementDeterminismTest` bypass production `handleRegister`.** Tests use `attemptPlacementForTest` seam or manual orphan injection; the actual register-first ordering under WS-close interleaving and `trySetEntity` lost-race is not exercised (claude, codex, opencode).

### Divergent Views
- **H3 race assessment.** Claude flags a NEW HIGH cross-thread duplicate-position race (two threads sample same `pos_X` before either calls `notifyChanged` → both register → second rolls back, but snapshot in the window double-counts in combat). Codex flags a related register-vs-`afterConnectionClosed` ordering gap. Gemini and opencode mark H3 fully closed. Worth investigating: whether `LiveEntityRegistry.register` accepts the same Position twice, and whether `SimulationEngine.processInteractions` could observe both during the rollback window.
- **Verdict split.** Claude/gemini/codex → **NEEDS-REWORK** (HIGH lifecycle bugs introduced or unaddressed by H2/H3). Opencode → **READY-WITH-FOLLOWUPS** (treats prey-strand and BondedPair-death-leak as containable follow-ons rather than regressions). The disagreement turns on whether the H2 follow-on prey-cleanup change is a regression or an acceptable trade-off for the GoldenTrace digest gate.
- **Determinism — `compositeBallots` HashMap iteration (claude only).** Claude flags `ActionResolver.java:862,882` HashMap iteration as MEDIUM determinism risk at scale (composite-vs-composite collisions). Other reviewers did not surface this; worth a one-line `LinkedHashMap` swap regardless.
- **Determinism — `ActionResolver` ConcurrentHashMap drain order (codex only).** Codex flags actions drained from `ConcurrentHashMap` in `entrySet()` order before seeded shuffle as a MEDIUM determinism risk. Other reviewers did not flag; verify whether the drain site already sorts by stable key before shuffle.
- **`processOvercrowding` notifyChanged hook (claude only).** Claude flags missing `eligibleCellIndex.notifyChanged` after `FLAG_OVERCROWDED` add/remove in `SimulationEngine.java:1043-1049`, causing eligible-set drift. Other reviewers did not surface; worth checking whether the H1 fix relies on a separate refresh path.



---
---
---



---
task: generic
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-03T03:33:05Z
files: ["src/main/java/com/paralife/engine/EligibleCellIndex.java", "src/main/java/com/paralife/engine/LiveEntityRegistry.java", "src/main/java/com/paralife/engine/SimulationEngine.java", "src/main/java/com/paralife/engine/EnvironmentEngine.java", "src/main/java/com/paralife/engine/ActionResolver.java", "src/main/java/com/paralife/engine/BotRegistry.java", "src/main/java/com/paralife/engine/BondLifecycleListener.java", "src/main/java/com/paralife/engine/DeathFinalizer.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/test/java/com/paralife/engine/EligibleCellIndexTest.java", "src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java", "src/test/java/com/paralife/engine/PlacementDeterminismTest.java", "src/test/java/com/paralife/engine/EntityListIterationTest.java", "src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java", "src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java", "src/test/java/com/paralife/engine/GoldenTraceCapture.java", "src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java", "src/test/java/com/paralife/websocket/RegisterAtomicityTest.java", "src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java"]
usage:
  claude: { input: 10, output: 16, cached: 40450, tool_calls: 0, elapsed_s: 390.4 }
  gemini: { input: 230154, output: 1764, cached: 0, tool_calls: 0, elapsed_s: 216.1 }
  codex: { input: 1512639, output: 10491, cached: 1335680, tool_calls: 17, elapsed_s: 239.5 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 655.1 }
synthesizer: claude
synthesized_at: 2026-05-03T03:33:05Z
---

# Cross-AI Review (Second Pass)

## Claude Review

## Bugs

### HIGH — `updateBotRegistryForFormation` lookup key broken after H2 remap
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:851-861` (`updateBotRegistryForFormation`)
**Issue:** Method calls `botRegistry.getSessionForEntity(bp.primaryEntityId())` to remap the session at composite-formation. Pre-H2 this worked because BotRegistry held `session → primaryEntityId` (the original Particle id). H2 changed bond-formation (`SimulationEngine.java:670-672` `botRegistry.remapEntity(predatorSessionId, bondedPair.id())`) so BotRegistry now holds `session → bondedPair.id()` (e.g. `"p1+p2"`). At composite-formation `bp.primaryEntityId()` is still the legacy `"p1"`, which is no longer a key in `entityToSession`. The `.ifPresent` block never fires. The session keeps its stale mapping to the now-deleted `bp.id()` and the new CompositeMember has no session.
**Why it matters:** Breaks the WS:entity 1:1 invariant exactly the way H2 was supposed to fix, but on the next tier up. Knock-on failures: `revertToBondedPair` (`SimulationEngine.java:1186-1187`) and `dissolveToParticles` (`SimulationEngine.java:1228-1229`) also look up by `cm.id()` — which was never written by the broken remap — so neither path remaps the session back when the composite degrades. TickBroadcaster will route perception frames to the wrong entity (or to nothing). Existing tests miss this: `LiveEntityRegistryInvariantTest.registryMatchesGridOccupantsAfterCompositeFormation` seeds entities directly on the grid without going through `botRegistry.register`, and `GoldenTraceEquivalenceTest` seeds two adjacent bonding pairs but they are not adjacent BondedPairs so composite-formation never fires in the trace.
**Fix:** Look up by `bp.id()`, not `bp.primaryEntityId()`. Also drop the now-dead secondary branch (prey's session was already unregistered at bond formation).
```java
private void updateBotRegistryForFormation(Entity.BondedPair bp, String newMemberId, Position pos) {
    botRegistry.getSessionForEntity(bp.id()).ifPresent(sessionId -> {
        botRegistry.unregisterByEntity(bp.id());
        botRegistry.register(sessionId, newMemberId, pos);
    });
    // bp.secondaryEntityId() branch removed — H2 unregistered prey at bond formation.
}
```
Mirror the same key fix in `revertToBondedPair` and `dissolveToParticles`: look up the session keyed on the entity-id BotRegistry actually holds (the CompositeMember's `cm.id()` after the fixed remap above).

### MEDIUM — STALLED predator + bond formation invalidates the resume token
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:660-674` + `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:497-540` (`markStalled`)
**Issue:** `markStalled` removes `ATTR_ENTITY_ID` and detaches the sender VT but does NOT call `botRegistry.unregisterBySession`. So a STALLED predator still has `session → predator.id()` in BotRegistry. If the predator's prey is adjacent and bonding fires while the predator is still in grace, H2's remap runs and BotRegistry becomes `session → bondedPair.id()`. The resume token issued at registration is keyed to `predator.id()`, which is now gone. The client's reconnect (`ResumeTokenRegistry.tryRebind` → `botRegistry.rebindSession(newSession, predator.id())`) returns `false` (entity not found). The reconnect silently degrades.
**Why it matters:** Phase 17 backpressure was specifically designed to give a STALLED client a grace window to reconnect. Bond-formation during the grace window quietly burns that contract — the operator-visible counter `paralife.backpressure.terminal.dropouts` ticks up as if the client failed to reconnect, masking the real cause.
**Fix:** Either (a) suppress bond formation when the predator is in STALLED state (cheapest — check `botRegistry → session.attributes[ATTR_STALL_TICK]` before forming the bond), or (b) inside `BondLifecycleListener.onBondFormed`, also call `resumeTokenRegistry.remapEntity(predator.id(), bondedPair.id())` so the reconnect path follows the new id. (a) is the smaller blast radius; (b) preserves the bond.

### MEDIUM — `notifyChanged` reads `cellStatusCacheView` for nothing after H1
**File:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:172-185`
**Issue:** H1 removed the only consumer of the cache snapshot inside `evaluateEligibility` (constraint #2 now reads `cell.hasFlag(Cell.FLAG_OVERCROWDED)`; constraint #3 walks `worldGrid.getNeighbors`). The `@SuppressWarnings("unused")` on the `Map<Position, Byte> cellStatusCache` parameter confirms the parameter is dead. But `notifyChanged` still calls `environmentEngine.cellStatusCacheView()` once per call and threads the unused map through 25 inner-loop iterations.
**Why it matters:** Wasted volatile read on the WS thread per registration / per cleanup. Cheap individually but multiplies at the 5000-connection ceiling — and the snapshot is allocated by EnvironmentEngine at end-of-tick, so this also keeps a needless reference live across the tick.
**Fix:** Drop the parameter and the `cellStatusCacheView()` call. Remove `@SuppressWarnings`. Single mechanical change; no behavior delta.

### MEDIUM — `entityStatusCache` swap allocates O(N) per tick instead of O(1)
**File:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:1457-1462`
**Issue:** `cellStatusCache` uses the swap-and-allocate pattern: `unmodifiableMap(staging); staging = new HashMap<>()` — O(1) hand-off, no per-tick copy. M1's `entityStatusCache` uses `Map.copyOf(staging); staging.clear()` — that's an O(N) copy of every entity-status entry every tick, because `entityStatusStaging` was declared `final` (line 187 area) and can't be reassigned.
**Why it matters:** At Phase 21 scale during a mutagen outbreak, N grows. The plan explicitly justifies the cost as "small" but the asymmetry is the surprise — operators reading the patch will assume both caches share the same allocation profile.
**Fix:** Make `entityStatusStaging` non-final and use the same swap pattern:
```java
this.entityStatusCache = Collections.unmodifiableMap(entityStatusStaging);
this.entityStatusStaging = new HashMap<>();
```
Mirrors `cellStatusCache` exactly.

### LOW — Dead secondary branch in `updateBotRegistryForFormation`
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:856-860`
**Issue:** Even after the HIGH fix above, the second `getSessionForEntity(bp.secondaryEntityId())` branch is unreachable: H2 made `botRegistry.unregisterBySession(preySessionId)` at bond-formation, so by the time composite-formation runs the secondary entity has no session.
**Why it matters:** Future readers will assume it's load-bearing. Removing it makes the WS:entity 1:1 invariant visible at this site.
**Fix:** Delete the branch. (Folds into the HIGH fix.)

### LOW — `EnvironmentEngine` setter for `LiveEntityRegistry` not annotated `@Lazy` consistently with read sites
**File:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:294-298`
**Issue:** Setter takes `@Lazy LiveEntityRegistry`. Two read sites in `tickBuffsAndInfections` (line ~715) and `buildStatusCaches` (line ~1432) call `liveEntityRegistry.snapshot()` directly. With `@Lazy`-injected proxy the first call materialises the bean; if any other Spring init order lands first, it works. Pattern is the same as `SimulationEngine` so behaviour is consistent — but the field has no `volatile`. Single-threaded mutation makes this safe today; flag it because Phase 19.1 parallel perception is the activation path.
**Fix:** Mark `liveEntityRegistry` `volatile` in both `EnvironmentEngine` and `SimulationEngine` before Phase 19.1 starts. Cheap insurance.

## Determinism risks

### MEDIUM — `compositeRegistry.getAll()` iteration is non-deterministic; L1 pin understates the blast radius
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:1090-1110` (`processDeaths` Phase 3c) and `src/main/java/com/paralife/engine/EnvironmentEngine.java:`(`processEnvDeaths`)
**Issue:** `compositeRegistry.getAll()` returns `ConcurrentHashMap.values()` iteration order. The L1 pin comment says it's "inert today" because the GoldenTrace 200-tick scenario never reaches panic-zone shatter. Fine for the digest gate. But: even WITHOUT shatter, the iteration order affects when composite-member energy distribution and panic-zone snapshots are taken — `previousPoolEnergy.put(composite.getCompositeId(), composite.getSharedPoolEnergy())` at the bottom of `processDeaths` writes in the same non-deterministic order. If two composites' pool-energy snapshots interleave with mutations from other handlers, observable behaviour can diverge across runs even before shatter rolls.
**Why it matters:** Phase 21 benchmark replay assumes deterministic sims under fixed seed. As soon as the workload includes >1 composite at a time (which is plausible at 1000 bots with composites enabled), determinism quietly weakens. The L1 comment frames this as "switch to LinkedHashMap before exercising shatter" — actually the dependency is "before exercising any composite at panic-zone-eligible energy levels."
**Fix:** Replace `CompositeRegistry`'s internal `ConcurrentHashMap<String, CompositeState>` with `LinkedHashMap` (and external `synchronized` for the few writers), or wrap iteration in `getAll()` with a `Comparator.comparing(CompositeState::getCompositeId)` sort. Same cost as the LiveEntityRegistry row-major sort; cheap at any plausible composite count.

### LOW — `ConcurrentHashMap`-backed `Infection`/`cureImmuneUntil` iteration in env paths
**File:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:`(`tickBuffsAndInfections`)
**Issue:** Same shape as the composite-iteration risk: `infections.entrySet()` and `pendingGrants` snapshot are iterated, then a `toRemove` list is built and applied. Iteration order over the underlying `ConcurrentHashMap` is non-deterministic. Today the per-entity DoT damage is commutative (each infection ticks independently) so the digest happens to be stable. Once attack-cure-reduction interactions become more involved (multiple infections per tick reducing infections shared across entities), this will start to drift.
**Fix:** Sort the entrySet snapshot by `entityId` before iterating in `tickBuffsAndInfections`. One-line change; preserves the perf characteristic (one extra sort over a typically-small map).

## Scale concerns

### MEDIUM — `LiveEntityRegistry.snapshot()` called ~9× per tick, each O(N log N) under monitor
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:` multiple call sites in `processInteractions`, `processEnergyDecay`, `processOvercrowding`, `processDeaths` + `EnvironmentEngine.java` 2 sites
**Issue:** REVIEWS IN-03 noted this and the plan deferred to Phase 21. At N=1000 each snapshot is ~10µs sort + ArrayList allocation, all under `synchronized(this)` on the registry. WS thread `register`/`unregister` calls compete for that monitor on every placement. At Phase 21's 1000+ connection target this is a real bottleneck — not because the sort is expensive but because the lock is uncontested today and benchmarks won't catch the contention until live ramp.
**Fix:** Pre-compute one snapshot at the top of `SimulationEngine.processTick` and pass `List<EntityEntry>` down to each phase. Same change in `EnvironmentEngine.onTick`. Eliminates 7 of the 9 snapshot calls; the remaining two are `buildStatusCaches` (separate handler @Order(14)) and `tickBuffsAndInfections` (also @Order(14)) — those can share their own pre-computed snapshot. Also lets the `dirty-bbox-since-last-tick` optimisation land later without rewriting call sites. Recommend doing this BEFORE Phase 20.1 parallel perception to avoid lock contention compounding parallelism gains.

### LOW — `EligibleCellIndex.notifyChanged` 5×5 walk × `worldGrid.getCell` × `getNeighbors` × `countOccupiedMooreNeighbours`
**File:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:172-235`
**Issue:** Per-event cost is 25 cells × (1 `getCell` + up to 8 `getNeighbors` + up to 8 `getCell` for constraint #3) = up to ~225 `getCell` calls per `notifyChanged`. Each `getCell` acquires `WorldGrid`'s RRWL read lock. In the placement hot-path with backpressure churn (entity placed, displaced, replaced) this is a lot of lock acquisition.
**Why it matters:** Below Phase 21 scale this is invisible. At 5000 connections with frequent registers + cleanups, the WS thread becomes a noisy reader of `WorldGrid` and the tick thread's write lock contention rises.
**Fix:** Pre-snapshot the bbox via `worldGrid.snapshot()` once per `notifyChanged` (single read lock, copy 25 cells), then evaluate eligibility against the snapshot. Already a pattern used by `EnvironmentEngine.totalNutrients`.

## Test gaps

### HIGH — No test exercises bond → composite formation through real registration flow
**File:** `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java:160-225` and `src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java`
**Issue:** `LiveEntityRegistryInvariantTest.registryMatchesGridOccupantsAfterCompositeFormation` constructs `BondedPair` instances directly via `new BondedPair(...)` and seeds them on the grid, then calls `liveEntityRegistry.register("bp1", pos1)` manually. It never goes through `WorldWebSocketHandler.handleRegister` → bond-formation tick → composite-formation. So the BotRegistry remap chain (predator session → bp.id() at bond, then bp.id() → memberId at composite) is never exercised end-to-end. Same gap in `BondDisconnectIntegrationTest` (only tests bond + disconnect, no composite).
**Why it matters:** This is the H1 lesson recurring — the existing test asserts the predicate (registry positions match grid occupants) but not the end-to-end pipeline (BotRegistry session mapping survives both transitions). The HIGH bug above would not have shipped if a real-flow test existed.
**Fix:** Add an integration test: register two bots through `handleRegister`, place adjacent particles via test seam, fire ticks until bond forms, register two more adjacent bots, fire ticks until both BondedPairs become adjacent, fire one more tick to form composite, then assert (a) `botRegistry.getBySession(session1).get().entityId()` equals one of the CompositeMember ids, (b) the CompositeMember exists on the grid, (c) `botRegistry.getSessionForEntity(memberId).isPresent()`.

### MEDIUM — No test for STALLED predator participating in bond formation
**File:** N/A — needed
**Issue:** The MEDIUM "STALLED predator + bond formation" finding above has no test. Markstalled, fire bond-formation tick, attempt rebind — assert success or graceful fallback.
**Fix:** Add `BondFormationDuringStallTest` mirroring `BondDisconnectIntegrationTest` pattern but with `markStalled` between seed and tick.

### MEDIUM — `RegisterAtomicityTest` only asserts no-throw, not consumer-side correctness
**File:** `src/test/java/com/paralife/websocket/RegisterAtomicityTest.java:65-85`
**Issue:** The test inserts an orphan `liveEntityRegistry.register("orphan-entity", pos)` at an empty cell, fires one tick, asserts no exception. Doesn't verify the entity is eventually consistent — a tick handler that silently swallowed every entry would also pass this test.
**Fix:** After firing the tick, place a real Particle at the orphan position, fire a second tick, assert the entity is processed (e.g. its energy decayed by the per-tick decay rate). Proves the handlers actually picked it up after the transient resolved.

### LOW — `EligibleCellIndexTest.constraint2RejectsOvercrowded_viaSimulationEnginePath` doesn't actually run SimulationEngine
**File:** `src/test/java/com/paralife/engine/EligibleCellIndexTest.java:78-105`
**Issue:** Test name promises "via SimulationEngine path" but the body just calls `withAddedFlag(Cell.FLAG_OVERCROWDED)` directly — it never invokes `SimulationEngine.processOvercrowding`. So if `processOvercrowding`'s `withAddedFlag` semantics ever change (e.g. moves to `Cell.cellStatus`), this test won't catch it.
**Fix:** Wire a real `SimulationEngine` (or the test integration class), seed neighbor-dense cells, fire one tick, then build the index. Closer to the H1 end-to-end gap the second test was created to close.

## H1/H2/H3 sanity check

**H1 — confirmed closed.** `EligibleCellIndex.evaluateEligibility:213-216` reads `cell.hasFlag(Cell.FLAG_OVERCROWDED)` directly. The flag is the same one `SimulationEngine.processOvercrowding` writes. Cell is an immutable record, mutated through `WorldGrid.setCell` under the RRWL — visibility correct. No regression introduced beyond the dead `cellStatusCache` parameter (LOW above).

**H2 — closes the disconnect bug, opens the composite-formation regression.** The bond-formation site (`SimulationEngine.java:660-674`) correctly:
- looks up `predatorSessionId` and `preySessionId` BEFORE the LiveEntityRegistry mutations (when BotRegistry still holds the original Particle ids);
- unregisters prey by session (not by entity — avoids the spurious `vD` death frame the H2 follow-on caught);
- calls `botRegistry.remapEntity(predatorSessionId, bondedPair.id())`;
- fires `bondLifecycleListener.onBondFormed` synchronously inside the tick thread, so the predator session's `ATTR_ENTITY_ID` update is atomic with the registry remap from the consumer's POV.

For STALLED state during bond formation: `WorldWebSocketHandler.onBondFormed:`(implementation) correctly null-guards on missing session — no NPE. But the silent resume-token invalidation that follows is the MEDIUM I flagged. The HIGH composite-formation regression is the bigger problem — H2 changed the BotRegistry key without auditing every other site that looks the entity up.

**H3 — confirmed closed.** Register-first ordering at `WorldWebSocketHandler.java:551-585` is correct:
- LiveEntityRegistry.register before WorldGrid.trySetEntity;
- on trySetEntity failure, immediate unregister rollback;
- consumers re-derive the entity from `worldGrid.getCell(entry.position()).occupant()` and use `instanceof` guards on Particle / BondedPair / CompositeMember (verified across `SimulationEngine.processInteractions`, `processDeaths`, `EnvironmentEngine.tickBuffsAndInfections`).

The "registry has it, grid doesn't" transient resolves to a benign skip on the consumer side. Lost-race retry (3 attempts) properly cleans up between attempts: register → trySetEntity fails → unregister → notifyChanged → next sample → register again. No leak.

One subtle observation: the comment at H3's call site says consumers handle the transient, but `RegisterAtomicityTest` only asserts no-throw — see test gap above.

## Concrete fix for the known TD-19.5-A flake (since the prompt invited proposals)

`OutboundSender.awaitAllSessionQueuesDrained` race: queue depth reads zero while the drain VT is between `queue.take()` and `synchronized(session) { sendMessage; listener.onEmit }`. The test's `synchronized(s)` barrier in `GoldenTraceEquivalenceTest.awaitAllSessionQueuesDrained` covers in-flight callbacks for sessions that are STILL ATTACHED, but the issue is that a frame can be dequeued before the barrier acquires the monitor on a different session.

Concrete fix:
```java
// in OutboundSender — add a per-session "drain-quiescent" flag the VT toggles
// inside the monitor, and a global counter incremented on every emit.
private final ConcurrentHashMap<String, AtomicBoolean> drainBusy = new ConcurrentHashMap<>();

// in drainLoop, before queue.take():
drainBusy.get(sessionId).set(false);
Frame frame = queue.take();
drainBusy.get(sessionId).set(true);
synchronized (session) { ... }
drainBusy.get(sessionId).set(false);

// in GoldenTraceEquivalenceTest.awaitAllSessionQueuesDrained — require N consecutive
// observations of (queueDepth==0 AND drainBusy==false) for every session, then
// acquire each session monitor as the final barrier.
```
N=3 consecutive zero-quiescent polls plus the existing `synchronized(s)` barrier closes the race deterministically. Cost: one extra `AtomicBoolean` per session, no impact on the production drain hot path beyond a single get/set per frame.

## Verdict

`NEEDS-REWORK`

The HIGH composite-formation regression introduced by H2 is the blocker — every code path that touches a CompositeMember session mapping (formation, revert, dissolve) is downstream of the broken `updateBotRegistryForFormation` lookup. WS:entity 1:1 is broken at composite scale. Existing tests miss it because the integration test seeds the grid directly without going through real registration. Fix is mechanical (one-line key change × 3 sites + test that exercises the chain). The MEDIUM findings can ride into Phase 20 backlog without blocking.

---

## Gemini Review

## Bugs

### CRITICAL — ActionResolver queueAction TOCTOU lost actions
**File:** `src/main/java/com/paralife/engine/ActionResolver.java:181-182, 203`
**Issue:** `queueAction` performs `pendingActions.get().put(sessionId, action)`. `onTick` swaps the map using `pendingActions.getAndSet(new ConcurrentHashMap<>())`. If a VT thread executes the `put()` on the map reference obtained just before `getAndSet` swaps it, the action is written to the old, discarded map. The same applies to `pendingVoteBallots`.
**Why it matters:** High-concurrency environments will experience dropped frames (silent loss of bot actions), violating the WebSocket inbound pipeline reliability.
**Fix:** Stop swapping maps. Iterate over `pendingActions.entrySet()` and use atomic `pendingActions.remove(entry.getKey(), entry.getValue())` to drain actions safely.

### HIGH — SimulationEngine and ActionResolver leak remaps via missing lifecycle listeners
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:731, 755`, `src/main/java/com/paralife/engine/ActionResolver.java:501`
**Issue:** While H2 added `bondLifecycleListener.onBondFormed` for `BondedPair` creation, `revertToBondedPair` and `dissolveToParticles` in `SimulationEngine`, as well as `updateBotRegistryForFormation` in `ActionResolver`, call `BotRegistry.remapEntity` but do NOT invoke any listener to update the WebSocket session's `ATTR_ENTITY_ID`.
**Why it matters:** On disconnect, `cleanupBot` uses the stale `ATTR_ENTITY_ID` to unregister from `LiveEntityRegistry` (a no-op), permanently leaking the new `CompositeMember` or `BondedPair` entries on the grid and registry.
**Fix:** Expand `BondLifecycleListener` into a generic `EntityLifecycleListener.onEntityRemapped` and invoke it at all structural identity transition sites.

### HIGH — H2 fix ignores STALLED sessions and breaks reconnect
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:623-625`
**Issue:** `onBondFormed` early-returns if `sessionRegistry.getSession(predatorSessionId)` is null. STALLED sessions are removed from the registry, so the callback is skipped. The `ResumeTokenRegistry` is never informed that the entity ID changed from the particle ID to the `BondedPair` ID.
**Why it matters:** Reconnecting bots will present a resume token mapped to the old, deleted particle ID. `BotRegistry.rebindSession` will fail, and the entity is permanently leaked on the grid and registry when grace expires.
**Fix:** Update `ResumeTokenRegistry` during entity remap to associate the active token with the new entity ID.

## Determinism risks

### HIGH — Action resolution shuffling order is non-deterministic
**File:** `src/main/java/com/paralife/engine/ActionResolver.java:231, 260, 276`
**Issue:** `resolvedList` and `resolvedCompositeList` are built by iterating `actions.entrySet()` (a `ConcurrentHashMap`), then shuffled via `Collections.shuffle(..., actionRng)`. `compositeBallots` uses a `HashMap`. Shuffling a list with a non-deterministic initial order produces a non-deterministic final order.
**Why it matters:** Action resolution order will vary non-deterministically across runs, breaking replay equivalence and the GoldenTrace gate.
**Fix:** Sort `resolvedList` and `resolvedCompositeList` by `sessionId` before shuffling. Use `TreeMap` for `compositeBallots`.

### HIGH — ConcurrentHashMap iteration desyncs RNG during buff grants
**File:** `src/main/java/com/paralife/engine/EnvironmentEngine.java:464`
**Issue:** `tickBuffsAndInfections` iterates over `infections.entrySet()` (a `ConcurrentHashMap`). When multiple infections expire on the same tick, they enter `pendingBuffGrants` in non-deterministic order. `processPendingGrants` then consumes `rng.nextInt()` per entity to pick a random buff.
**Why it matters:** Divergent RNG consumption order breaks bit-exact replication.
**Fix:** Sort expired infections by `entityId` before enqueuing to `pendingBuffGrants`.

### HIGH — Non-deterministic UUID generation for composite and member IDs
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:482, 492-493`
**Issue:** `compositeId` and `memberId` are generated using `UUID.randomUUID()`.
**Why it matters:** Explicitly flagged as a determinism bug. While latent today, the moment IDs are relied upon for deterministic iteration or GoldenTrace tests cross panic-zone boundaries, runs will diverge.
**Fix:** Use `AtomicLong compositeIdCounter` and `memberIdCounter` alongside the existing `childIdCounter`.

## Scale concerns

### MEDIUM — O(N log N) snapshot() called in hot loop 4-5 times per tick
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:259-282`
**Issue:** `SimulationEngine` invokes `entitySnapshot()` (which calls `liveEntityRegistry.snapshot()`, allocating and sorting a new ArrayList) up to 5 times per tick.
**Why it matters:** At Phase 21's target density (1000+ entities), this becomes 5 allocations and sorts per tick, increasing CPU and GC pressure unnecessarily.
**Fix:** Pre-compute the snapshot once at the beginning of `processTick` and pass it down to the phase handlers.

### MEDIUM — O(N) garbage collection sweeps every tick
**File:** `src/main/java/com/paralife/engine/ActionResolver.java:319-328`
**Issue:** `resolveCompositeMovements` performs full-registry iterations (`compositeRegistry.getAll()` and `botRegistry.getAllBots()`) to build HashSets, then calls `retainAll` to prune `compositeTicksSinceMove` and `lastReproducedTick`.
**Why it matters:** O(N) operations on every tick compound heavily at 1000-5000 scale.
**Fix:** Clear these tracking maps surgically during entity death via a hook in `DeathFinalizer`, avoiding global sweeps.

## Test gaps

### MEDIUM — BondDisconnectIntegrationTest misses STALLED connections
**File:** `src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java`
**Issue:** The test simulates a clean disconnect by calling `handler.cleanupBot(predSession)`. It does NOT test the scenario where a session stalls (queue overflow causing `markStalled`) before or during bond formation, followed by grace expiry.
**Why it matters:** This gap allowed the STALLED H2 regression (finding #3) to slip through.
**Fix:** Add a test case that forces a stall on the predator session, triggers bond formation, and validates that grace expiry correctly reaps the BondedPair.

## H1/H2/H3 sanity check

- **H1:** Clean. The fix correctly reads `Cell.flags` directly, circumventing the redacted cache.
- **H2:** Failed. The fix introduced a regression for STALLED sessions (the callback no-ops because the session is unregistered) and completely ignored identical identity remap lifecycle bugs in `revertToBondedPair`, `dissolveToParticles`, and composite formation.
- **H3:** Clean. Registering before mutating the grid, with rollback on failure, securely closes the "grid has it, registry doesn't" window without introducing unsafe lock scopes. The inverse state is properly skipped by consumers.

## Verdict
NEEDS-REWORK

---

## Codex Review

## Bugs

### HIGH — BondedPair death leaves the controlling session registered
**File:** `src/main/java/com/paralife/engine/DeathFinalizer.java:129`
**Issue:** After H2, the predator session is remapped to `bondedPair.id()`, but `finalizeBondedPairDeath` only calls `botRegistry.unregisterByEntity(primaryId)` and `secondaryId` at lines 134 and 137. It unregisters `bp.id()` from `LiveEntityRegistry` at line 152, but never from `BotRegistry`.
**Why it matters:** When a BondedPair dies, the predator session remains mapped to a dead `bp.id()`, no `vD` is queued for that session, and `TickBroadcaster` can keep treating the session as live with an empty grid cell.
**Fix:** In `finalizeBondedPairDeath`, call `botRegistry.unregisterByEntity(bp.id())` for the controlling session, then keep primary/secondary cleanup idempotent for old state. Add a bond-then-death regression test asserting BotRegistry is empty and a death notice is emitted.

### HIGH — Composite formation remaps using obsolete BondedPair child IDs
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:813`
**Issue:** Composite formation calls `updateBotRegistryForFormation(cf.bp1(), ...)`, but that helper looks up `bp.primaryEntityId()` and `bp.secondaryEntityId()` at lines 866 and 872. H2 moved the surviving session mapping to `bp.id()`, so these lookups miss.
**Why it matters:** A BondedPair that becomes a CompositeMember leaves its session mapped to the dead BondedPair id while `LiveEntityRegistry` holds the new `cm-*` id. A later disconnect unregisters the wrong id and leaks the CompositeMember registry entry.
**Fix:** Make composite formation remap from `bp.id()` first, falling back to `primaryEntityId()` only for legacy/idempotent cleanup. Update the WebSocket session attribute through the same lifecycle-listener pattern used for bond formation.

### HIGH — Prey session remains logically Alive after bond absorption
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:707`
**Issue:** Bond formation removes the prey from `BotRegistry` via `unregisterBySession` at line 713, but nothing clears the prey session’s `ATTR_ENTITY_ID` or releases admission/metric state. `handleAction` only checks the session attribute at `WorldWebSocketHandler.java:619`, and `handleRegister` computes `alreadyAlive` from the same stale attribute at line 439.
**Why it matters:** The prey WebSocket outlives its entity. Actions are accepted then dropped because BotRegistry has no entry, `r|` can be rejected as already registered, and the active admission slot/gauge is never released through `cleanupBot` lines 850–868.
**Fix:** Extend `BondLifecycleListener` to receive `preySessionId` too. In the WS layer, transition the prey session out of Alive atomically: clear `ATTR_ENTITY_ID`, decide whether it should be respawn-pending or closed, and decrement active admission accounting without emitting `vD`.

### HIGH — Bond remap is not atomic with disconnect or STALLED state
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:692`
**Issue:** Bond formation updates `LiveEntityRegistry`, then `BotRegistry`, then the session attribute, then the grid across lines 693–721 with no common lock. `cleanupBot` independently removes attrs and unregisters at `WorldWebSocketHandler.java:825`, and `onBondFormed` blindly writes `ATTR_ENTITY_ID` at line 821 even if the session is STALLED.
**Why it matters:** A disconnect between those steps can clear the grid and unregister the old id while the tick later installs a BondedPair with no valid session cleanup path. For STALLED sessions, the resume token and admission bucket remain keyed to the old id, so grace expiry can call `cleanupByEntityId(oldId)` and miss the remapped BondedPair.
**Fix:** Move bond session transition into one WS-owned atomic method, synchronized on the session or a lifecycle lock also used by `cleanupBot`. If the session is STALLED, rekey resume-token/admission snapshots to the BondedPair id or skip attr resurrection and make grace cleanup target the new id.

## Determinism Risks

### MEDIUM — Composite lifecycle still has nondeterministic and untested session paths
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:777`
**Issue:** Composite ids/member ids still use `UUID.randomUUID()` at lines 777–778, while H2 changed the session mapping feeding later composite transitions. The GoldenTrace scenario only asserts aggregate bond/composite count, not stable composite session routing.
**Why it matters:** Once composite paths are exercised with real bot sessions, routing bugs can change per-session frame streams without necessarily changing global formation counts.
**Fix:** Add a deterministic composite-with-sessions trace or pin a separate integration test that drives BondedPair → CompositeMember → disconnect/death with real BotRegistry/session attributes.

## Scale Concerns

### MEDIUM — LiveEntityRegistry snapshot sorting is repeated per tick
**File:** `src/main/java/com/paralife/engine/LiveEntityRegistry.java:149`
**Issue:** `snapshot()` allocates and sorts on every call. `SimulationEngine` calls `entitySnapshot()` at lines 402, 527, 610, 885, 1022, 1062, and 1078.
**Why it matters:** At 1000+ entities this becomes repeated `O(N log N)` sorts and lock acquisitions inside one tick, exactly on the Phase 21 hot path.
**Fix:** Capture one row-major snapshot at the start of `processTick` and pass it through phase helpers. If phases need post-mutation visibility, capture once per mutation boundary, not once per loop.

## Test Gaps

### MEDIUM — EligibleCellIndex H1 “simulation path” test does not run SimulationEngine
**File:** `src/test/java/com/paralife/engine/EligibleCellIndexTest.java:95`
**Issue:** The test manually sets `Cell.FLAG_OVERCROWDED` at line 108 instead of driving `SimulationEngine.processOvercrowding`.
**Why it matters:** It verifies the predicate, but not the real tick path that sets and clears overcrowding flags plus dirty-index notifications.
**Fix:** Add a Spring or package-level test that creates the neighbor density, publishes a tick, then asserts the index excludes the flagged empty cell after the real overcrowding phase.

### MEDIUM — LiveEntityRegistryInvariantTest checks positions, not identities
**File:** `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java:299`
**Issue:** The helper only compares position sets at lines 301–324. It does not assert `EntityEntry.entityId()` matches `EntityIds.entityIdOf(gridOccupant)`.
**Why it matters:** The current H2/H2-follow-on bugs can leave BotRegistry/session ids stale while the position invariant still passes.
**Fix:** Compare a `Map<Position, entityId>` from grid vs registry, and add BotRegistry/session-attribute assertions for bond, BondedPair death, and composite formation.

### MEDIUM — GoldenTrace forced bond pairs are not real sessions
**File:** `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java:315`
**Issue:** `seedAdjacentBondingPair` registers only grid and `LiveEntityRegistry` at lines 323–330. The guaranteed bond pairs do not have BotRegistry mappings or WebSocket sessions.
**Why it matters:** The scenario can prove formation occurred while not exercising the H2 session remap that caused the production bug.
**Fix:** Attach mock sessions and BotRegistry entries for the forced pairs, or add a separate golden trace variant where the forced predator/prey are real registered sessions.

### MEDIUM — BondDisconnectIntegrationTest misses prey and death paths
**File:** `src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java:136`
**Issue:** The test asserts prey BotRegistry removal, but does not assert prey session attrs are cleared, admission state is released, or BondedPair death cleans the predator session.
**Why it matters:** Those are currently failing lifecycle surfaces after H2.
**Fix:** Extend the test with assertions for prey `entityId` removal, predator bond death, and no stale BotRegistry/LiveEntityRegistry entries afterward.

### LOW — RegisterAtomicityTest does not exercise handleRegister
**File:** `src/test/java/com/paralife/websocket/RegisterAtomicityTest.java:80`
**Issue:** The test manually inserts an orphan registry entry and manually rolls back registration at lines 84 and 113. It never drives `WorldWebSocketHandler.handleRegister`.
**Why it matters:** It proves consumers tolerate the transient, but not that the production handler orders register/trySet/rollback correctly under a real registration frame.
**Fix:** Add a test seam/latch around the handler’s register-first point and drive a real `r|` frame.

### LOW — PlacementDeterminismTest intentionally omits live WS and concurrent registration
**File:** `src/test/java/com/paralife/engine/PlacementDeterminismTest.java:65`
**Issue:** The test uses `attemptPlacementForTest`, not `handleRegister`, and only covers serial placement.
**Why it matters:** This is acceptable for the tightened D-06 scope, but it does not protect BotRegistry/session ordering, admission rollback, or concurrent arrival behavior.
**Fix:** Keep this as the unit determinism contract, and add a separate live-register deterministic smoke for serial WebSocket registrations.

## H1/H2/H3 Sanity Check

### LOW — H1 production fix is correct, but comments are stale
**File:** `src/main/java/com/paralife/engine/EligibleCellIndex.java:24`
**Issue:** The implementation correctly reads `Cell.FLAG_OVERCROWDED` at lines 230–238. The class Javadoc still says constraint 2 uses `cellStatusCache` bit 0 at lines 24–25.
**Why it matters:** The stale comment points future work back toward the exact redacted-cache bug H1 fixed.
**Fix:** Update the Javadoc to say constraint 2 reads `Cell.flags`; keep the cache note only for future env-effect constraints.

### HIGH — H2 is not fully closed
**File:** `src/main/java/com/paralife/engine/SimulationEngine.java:705`
**Issue:** The predator remap exists, but downstream BondedPair death, composite formation, prey-session cleanup, STALLED rebind, and cleanup races are not handled consistently.
**Why it matters:** H2’s original stale-registry problem is fixed for the one tested happy path, but equivalent stale BotRegistry/LiveEntityRegistry leaks remain in reachable lifecycle paths.
**Fix:** Treat entity identity transition as a shared lifecycle operation, not a local BotRegistry remap. Cover bond → disconnect, bond → death, bond → composite, and bond while STALLED.

### LOW — H3 production ordering closes the original grid-before-registry window
**File:** `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:541`
**Issue:** The handler now registers with `LiveEntityRegistry` before `trySetEntity` and rolls back on failure at lines 545–555.
**Why it matters:** This closes the original one-tick skip where the grid had an entity absent from the registry.
**Fix:** Keep the ordering, but replace the current simulation-only test with a handler-level latch test so future edits cannot move BotRegistry/session updates around accidentally.

## Verdict

NEEDS-REWORK

---

## Opencode Review

## Bugs

### MEDIUM — EligibleCellIndex.evaluateEligibility carries dead `cellStatusCache` parameter
**File:** `EligibleCellIndex.java:223`, parameter `Map<Position, Byte> cellStatusCache`
**Issue:** After the H1 fix removed the `cellStatusCache.get(pos)` read for constraint #2, the parameter is unconditionally unused. The `@SuppressWarnings("unused")` band-aid at line 221 masks this. `notifyChanged:203` hoists the cache, `initialize:136` passes it — both callers allocate/load a value the method never reads. The Javadoc on line 59 says "Cell-status read … reads cellStatusCacheView()" which is now stale.
**Why it matters:** Dead parameter with stale docs misleads future authors adding env-effect eligibility constraints (TOXIN_PRESENT, MUTAGEN_ZONE). They'll reach for the parameter, not realizing it was gutted by H1.
**Fix:** Remove the parameter, update the Javadoc, remove the `@SuppressWarnings`. `notifyChanged` still hoists the cache because constraint #3 (via `evaluateEligibility`) doesn't use it, but `notifyChanged` callback docs say it's future-reserved — keep the hoist in `notifyChanged` if truly intended, but drop it from the `evaluateEligibility` signature.

### MEDIUM — EntitySnapshot fallback path creates entries with entityId `"_"` for all occupants except live-types
**File:** `SimulationEngine.java:287-288`
**Issue:** The M2 fallback (`EntityIds.entityIdOf(occ)`) resolved the `"_"` sentinel for Particle/BondedPair/CompositeMember. But any future entity kind (e.g., a hypothetical TerrainFeature or Phase-XX construct) that passes the `instanceof` gate at line 284 would get `id = null` → `"_"`. The null-guard at line 288 is correct for today's type set only.
**Why it matters:** Non-extensible sentinel. Adding a new entity kind that counts as a "live occupant" requires touching this fallback in two places (the instanceof filter AND the id-fallback). Violates open-closed principle for entity types.
**Fix:** Extract `EntityIds.entityIdOfOrDefault(occ, "_")` or similar single-source helper.

## Determinism risks

### MEDIUM — processInteractions calls entitySnapshot() 3 times with interleaved grid mutations
**File:** `SimulationEngine.java:361, 419, 475`
**Issue:** The method snapshots particles at L361, applies combat+bonds, snaps composite members at L419, processes their attacks, then snaps BondedPairs at L475. Each `entitySnapshot()` reflects a different grid+registry state. This mirrors the pre-Phase-19 grid-walk behavior (which also observed the latest state), but the staged-progression is invisible at the call sites. Any future refactoring that caches `entitySnapshot()` once and reuses it would break combat/bond/composite determinism silently.
**Why it matters:** The dual-run digest gate relies on this staged-progression being preserved. GoldenTrace can't catch it post-hoc because the refactoring itself would produce deterministic (but wrong) output.
**Fix:** Add a pinned comment at each `entitySnapshot()` call in `processInteractions` noting "snapshot reflects post-bonds state" / "snapshot reflects post-composite-member state." Alternatively, rename to `postBondSnapshot()` / `postCompositeMemberSnapshot()` to make the dependency chain structural.

## Scale concerns

### MEDIUM — EnvironmentEngine.processEnvDeaths() uses O(width×height) grid scan
**File:** `EnvironmentEngine.java:667-683`
**Issue:** The env-death sweep iterates every cell, and unlike `SimulationEngine.processDeaths` (migrated to `entitySnapshot()`), this method was scoped out of Phase 19 migration. At 256×256 = 65 536 cells, this dominates tick cost whenever environment damage is active. The `envDamageAppliedThisTick` short-circuit only helps when no damage was applied at all — any toxin/mutagen/lightning tick triggers the full scan.
**Why it matters:** Phase 21's 1000+ connection target runs with environment events enabled; the O(grid) scan becomes the bottleneck for env-active ticks even though N entities ≪ grid cells.
**Fix:** Scan only `liveEntityRegistry.snapshot()` positions — entities that could receive env damage are exclusively live occupants (the same set `SimulationEngine.processDeaths` already iterates). Identical fix shape, same guarded-null pattern.

### LOW — LiveEntityRegistry.snapshot() called ~8 times per processTick
**File:** `SimulationEngine.java:361, 419, 475, 596, 634, 706, 722, 788`
**Issue:** Each call acquires the registry monitor, allocates an `ArrayList`, and sorts O(N log N). At N=1000 this is 8 × ~10 µs ~= 80 µs — negligible per tick but multiplied by 8 allocates a combined ~80 KB + 8 lock acquisitions per tick. Phase 21's benchmark environment may show this as GC pressure.
**Why it matters:** Low now, but Phase 19.1 parallel `PerceptionBroadcaster` would CONTEND on this monitor — the single synchronized snapshot becomes a serialization point.
**Fix:** Pre-compute `entitySnapshot()` once at `processTick` top and pass the list down through all phase methods. Schedule for Phase 21 (already noted in IN-03).

## Test gaps

### MEDIUM — BondDisconnectIntegrationTest omits STALLED-predator-during-bond scenario
**File:** `BondDisconnectIntegrationTest.java:109-125`
**Issue:** The test drives bond formation with both predator and prey sessions in ALIVE state, then disconnects the predator. It does NOT test the case where the predator's session is STALLED (outbound queue overflow → `markStalled` → session removed from SessionRegistry) when bond formation fires. In that scenario, the H2 `onBondFormed` callback no-ops silently (session not in registry), and the BotRegistry remap is the sole corrective action.
**Why it matters:** If the BotRegistry remap (`remapEntity(predatorSessionId, bondedPair.id())`) fails or is skipped (e.g., null-guard regression), a STALLED predator's rebind would resurrect the stale particle id, violating the SCALE-07 invariant on reconnect.
**Fix:** Add a test variant that marks the predator session as STALLED (sets `ATTR_STALL_TICK`, removes `ATTR_ENTITY_ID`, unregisters from `SessionRegistry`), fires bond formation, then simulates reconnect with resume token — asserts BotRegistry maps to `bondedPair.id()` and rebind works.

### MEDIUM — RegisterAtomicityTest uses manual orphan insertion instead of latch-based mid-register tick fire
**File:** `RegisterAtomicityTest.java:68-88`
**Issue:** The plan (`260502-sds-PLAN.md` Task 3) specified: "insert a test-only barrier between `liveEntityRegistry.register` and `trySetEntity`, fire `tickEvent`, then release the barrier." The shipped test instead manually calls `liveEntityRegistry.register("orphan-entity", orphanPos)` and fires a tick. This tests the CONSUMER side (tick handlers tolerate orphan entries) but NOT the PRODUCER side (two concurrent WS registrations contending for the same sampled cell with a tick interleaving at the precise race window).
**Why it matters:** The H3 fix claims to close the race window between register and trySetEntity. The test verifies "after the fix, consumers tolerate the opposite transient" — but the opposite transient ("registry has it, grid doesn't") is explicitly benign per the fix design. The test does NOT verify that the original transient ("grid has it, registry doesn't") can no longer occur.
**Fix:** Add a second test using a `CountDownLatch` barrier placed inside `handleRegister` (via a test-only seam, e.g., a package-private `AtomicBoolean` guard after `liveEntityRegistry.register` that the test thread can block on). Fire a tick between register and trySetEntity, then assert the entity id appears in `entitySnapshot()` OR the grid cell — not only one. Without a seam, this test can't cover the real race window.

## H1/H2/H3 sanity check

### H1 — VERIFIED CORRECT
- `EligibleCellIndex:237` reads `cell.hasFlag(Cell.FLAG_OVERCROWDED)` directly, not `cellStatusCache` bit 0.
- `EligibleCellIndexTest:101-109` (`constraint2RejectsOvercrowded`) stubs `FLAG_OVERCROWDED` on an empty cell — predicate test.
- `EligibleCellIndexTest:115-135` (`constraint2RejectsOvercrowded_viaSimulationEnginePath`) sets `FLAG_OVERCROWDED` then clears occupant, confirming the flag persists on empty cells and the index excludes them — end-to-end pipeline test.
- **No regression risk.** The cache was never populated with bit 0 (per D-40); reading it was always a no-op. Reading `Cell.flags` is strictly more correct.

### H2 — VERIFIED CORRECT (with noted gap)
- `SimulationEngine.java:654-669`: bond formation now calls `botRegistry.remapEntity(predatorSessionId, bondedPair.id())` and `bondLifecycleListener.onBondFormed(...)`.
- `WorldWebSocketHandler.java:502-513`: `onBondFormed` updates `ATTR_ENTITY_ID`. Null-guards on absent session.
- `BondDisconnectIntegrationTest` verifies BotRegistry remap + session attribute update + cleanupBot correctness.
- `BotRegistry.java:remapEntity(String sessionId, String newEntityId)` (two-arg, predator-only) preserves WS:entity 1:1.
- Prey cleanup uses `unregisterBySession` (not `unregisterByEntity`) — verified in the H2 follow-on commit (`3960fcc`), avoiding spurious `vD` frames.
- **Regression risk:** If predator's session is STALLED at bond time, `onBondFormed` no-ops (session not in SessionRegistry). This is correct — BotRegistry remap suffices for rebind. But this scenario is untested (see test gap above).
- **Atomicity:** `onBondFormed` runs synchronously in the tick thread immediately after `remapEntity`. No other handler can interleave. The session attribute update and BotRegistry remap are atomically observed by any downstream `@Order` listener.

### H3 — VERIFIED CORRECT (with noted gap)
- `WorldWebSocketHandler.java:368-376`: register in LiveEntityRegistry BEFORE `trySetEntity`; rollback on failure.
- `RegisterAtomicityTest` verifies consumers tolerate the orphan transient (registry entry with empty grid cell).
- **Regression risk:** The `register` call uses `IllegalStateException`-on-conflict semantics. If a `trySetEntity` failure leaves a stale `entityId` mapping and the retry sample picks the same position, `register(entityId, samePos)` is idempotent (no throw). If it picks a different position, the prior `unregister` cleared the mapping — no conflict. Correct.
- **Race window closure:** The fix eliminates "grid-has-it, registry-doesn't" by registering first. The opposite transient is benign (consumer re-derives from grid). The plan-requested latch test is missing (see test gap above) but the shipped test is sufficient for the invariant's consumer-safety guarantee.

## Verdict

**READY-WITH-FOLLOWUPS** — no CRITICAL or HIGH findings. The 3 HIGH bugs (H1/H2/H3) are correctly closed. The 4 MEDIUM findings (dead parameter, sentinel extensibility, staged-snapshot determinism comment, env-death grid-scan) and 3 test gaps (STALLED+bond scenario, latch-based H3 test, multi-snapshot scale pre-compute) should be backlog items for Phase 20/21. No component is broken at current workloads.

---

## Consensus Summary

### Agreed Strengths
- H1 fix verified correct: `EligibleCellIndex` reads `Cell.FLAG_OVERCROWDED` directly, sidestepping redacted cache (claude, codex, opencode).
- H3 register-before-trySetEntity ordering with rollback closes original "grid-has-it, registry-doesn't" window; consumers safely tolerate inverse transient (claude, codex, opencode).
- H2 predator session remap to `bondedPair.id()` + `onBondFormed` callback closes the disconnect-mid-bond happy path (claude, codex, opencode).

### Agreed Concerns
- **HIGH — Composite formation lookup uses obsolete `bp.primaryEntityId()` after H2 remap.** `updateBotRegistryForFormation` (`SimulationEngine.java:851-861` / call at `:813`) keys on the legacy primary id, but H2 already remapped session→`bp.id()`. CompositeMember session never updates; revert/dissolve paths inherit same bug. Blocker per claude/codex (3 reviewers cite this; gemini cites equivalent missing listener at composite/revert/dissolve sites).
- **HIGH — STALLED predator + bond formation breaks resume token.** `onBondFormed` no-ops on STALLED sessions (removed from registry); `ResumeTokenRegistry` keeps stale particle-id mapping → rebind fails silently, terminal-dropouts metric increments misleadingly (claude, gemini, codex).
- **HIGH/MEDIUM — Lifecycle listener missing at non-bond identity transitions.** `revertToBondedPair`, `dissolveToParticles`, and composite-formation call `botRegistry.remapEntity` without firing a listener; `cleanupBot` then unregisters the wrong id, leaking entries (gemini, codex; claude flags as the same lookup-key bug).
- **MEDIUM — `LiveEntityRegistry.snapshot()` invoked ~5–9× per tick.** O(N log N) sort + ArrayList alloc + monitor acquisition each call. Pre-compute once at top of `processTick` and pass down. Phase 21 / 19.1 parallel-perception bottleneck (claude, gemini, codex, opencode).
- **MEDIUM — Composite ids use `UUID.randomUUID()`.** Latent determinism hazard once composite paths exercised at scale; replace with `AtomicLong` counters paralleling `childIdCounter` (gemini, codex).
- **MEDIUM — `ConcurrentHashMap` iteration order in env/composite paths.** `infections.entrySet()` (and `compositeRegistry.getAll()`) feed RNG-consuming logic non-deterministically; sort by entityId before iterating (claude, gemini).
- **MEDIUM — Dead `cellStatusCache` parameter in `EligibleCellIndex.evaluateEligibility`.** `@SuppressWarnings("unused")` masks H1 leftover; stale Javadoc misleads future env-effect authors. Remove parameter + update docs (claude, opencode).
- **MEDIUM — `BondDisconnectIntegrationTest` doesn't cover STALLED-predator bond scenario.** Test gap that allowed the H2 STALLED regression to slip; add variant marking session STALLED before bond-formation tick (gemini, codex, opencode).
- **MEDIUM — `LiveEntityRegistryInvariantTest` / `RegisterAtomicityTest` weak end-to-end coverage.** Position-only invariants miss entityId mismatches; orphan-insertion path doesn't drive `handleRegister` real flow. Add latch-based real-handler tests + entityId equivalence assertions (claude, codex, opencode).
- **LOW — `EligibleCellIndexTest.constraint2RejectsOvercrowded_viaSimulationEnginePath` doesn't actually drive `SimulationEngine`.** Sets `FLAG_OVERCROWDED` directly; future change to overcrowding path won't be caught (claude, codex).

### Divergent Views
- **Verdict.** Three reviewers (claude, gemini, codex) say `NEEDS-REWORK` citing the composite-formation HIGH chain; opencode says `READY-WITH-FOLLOWUPS`, claiming the 3 HIGHs (H1/H2/H3) are closed and missing the composite-formation regression entirely. Weight of evidence + concrete file:line traces favor NEEDS-REWORK.
- **BondedPair death cleanup.** Codex flags `DeathFinalizer.finalizeBondedPairDeath` (`:129`) never unregisters `bp.id()` from BotRegistry → predator session orphaned; not raised by other reviewers. Worth verifying — concrete line citation.
- **`ActionResolver.queueAction` TOCTOU.** Gemini calls CRITICAL (lost actions on map swap); not raised by claude/codex/opencode. Likely real (`getAndSet(new ConcurrentHashMap<>())` race with concurrent `put` on stale reference) — verify before dismissing.
- **Action-resolution shuffle determinism.** Gemini flags HIGH (CHM iteration → shuffle gives non-deterministic order); other reviewers don't mention. Plausible but check whether GoldenTrace already exercises >1 simultaneous action.
- **Prey-session admission/attr cleanup.** Codex says prey session retains `ATTR_ENTITY_ID` and admission slot after bond absorption (HIGH); other reviewers cleared this via the H2 follow-on `3960fcc`. Re-verify against current `unregisterBySession` path.
- **`entityStatusCache` swap-vs-copy asymmetry.** Claude flags O(N) `Map.copyOf` per tick (vs `cellStatusCache` swap-and-allocate). Not raised by others; isolated micro-optimization.
- **`EligibleCellIndex.notifyChanged` lock churn.** Claude proposes `WorldGrid.snapshot()` bbox; opencode says hoisted cache should stay for future env constraints. Mutually compatible — drop unused param, keep snapshot pattern.
- **`EnvironmentEngine.processEnvDeaths` grid scan.** Opencode flags O(width×height) sweep as Phase 21 bottleneck; others don't cite. Concrete fix path (iterate `liveEntityRegistry.snapshot()` instead) is straightforward.
