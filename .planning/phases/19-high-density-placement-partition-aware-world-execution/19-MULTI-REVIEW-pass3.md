---
task: generic
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-05-03T07:45:48Z
files: ["src/main/java/com/paralife/engine/EligibleCellIndex.java", "src/main/java/com/paralife/engine/LiveEntityRegistry.java", "src/main/java/com/paralife/engine/SimulationEngine.java", "src/main/java/com/paralife/engine/EnvironmentEngine.java", "src/main/java/com/paralife/engine/ActionResolver.java", "src/main/java/com/paralife/engine/BotRegistry.java", "src/main/java/com/paralife/engine/DeathFinalizer.java", "src/main/java/com/paralife/engine/EntityLifecycleListener.java", "src/main/java/com/paralife/engine/TickEngine.java", "src/main/java/com/paralife/admission/OutboundSender.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/ResumeTokenRegistry.java", "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/websocket/TickBroadcaster.java", "src/main/java/com/paralife/codec/Event.java", "src/main/java/com/paralife/bot/BotClient.java", "src/test/java/com/paralife/engine/EligibleCellIndexTest.java", "src/test/java/com/paralife/engine/EligibleCellIndexRectangularTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryTest.java", "src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java", "src/test/java/com/paralife/engine/PlacementDeterminismTest.java", "src/test/java/com/paralife/engine/EntityListIterationTest.java", "src/test/java/com/paralife/engine/CompositeFormationDeterminismTest.java", "src/test/java/com/paralife/engine/CompositeFormationTest.java", "src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java", "src/test/java/com/paralife/engine/GoldenTraceCapture.java", "src/test/java/com/paralife/websocket/BondDisconnectIntegrationTest.java", "src/test/java/com/paralife/websocket/RegisterAtomicityTest.java", "src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java"]
usage:
  claude: { input: 78, output: 2358, cached: 7984289, tool_calls: 32, elapsed_s: 539.1 }
  gemini: { input: 386672, output: 1627, cached: 236644, tool_calls: 14, elapsed_s: 198.1 }
  codex: { input: 1525842, output: 8836, cached: 1316352, tool_calls: 44, elapsed_s: 187.6 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 23, elapsed_s: 505.9 }
synthesizer: claude
synthesized_at: 2026-05-03T07:45:48Z
---

# Cross-AI Review

## Claude Review

Have enough. Two HIGH findings + verification of pass-2 work. Drafting verdict.

# Phase 19 + 19.5 Pass-3 Cross-AI Review

## NEEDS-REWORK

Two HIGH bugs survived passes 1–2. Both ship today; both have concrete production failure modes; both are caught by a single-line fix.

---

## HIGH-1 — Composite formation queues spurious `v|D` to the predator session, orphans the composite member

**Where:** `src/main/java/com/paralife/engine/SimulationEngine.java:881-894` (`updateBotRegistryForFormation`)

**What:** The H-A pass-2 fix correctly switched the lookup key from `bp.primaryEntityId()` to `bp.id()`, but it kept `unregisterByEntity` + `register` as the action pair:

```java
botRegistry.getSessionForEntity(bp.id()).ifPresent(sessionId -> {
    botRegistry.unregisterByEntity(bp.id());      // <-- queues DeathNotice
    botRegistry.register(sessionId, newMemberId, pos);
    if (entityLifecycleListener != null) {
        entityLifecycleListener.onEntityRemapped(sessionId, bp.id(), newMemberId);
    }
});
```

`BotRegistry.unregisterByEntity` (`src/main/java/com/paralife/engine/BotRegistry.java:105-117`) **always** appends a `DeathNotice(sessionId, bp.id(), pos)` to `deathsThisTick`. The bond-formation path correctly avoids this by calling `remapEntity` (2-arg) and `absorbBySession`; composite formation does not.

**Failure trace (same tick — composite formation tick):**

1. `updateBotRegistryForFormation` runs → DeathNotice for `bp.id()` queued, BotRegistry now keyed by `cm.id()`.
2. `entityLifecycleListener.onEntityRemapped` updates session `ATTR_ENTITY_ID = cm.id()`.
3. `TickBroadcaster.onTick @Order(100)` fires `drainAndBroadcastDeaths` BEFORE the live-bot iteration:
   - sends `v|D` frame to the predator session
   - calls `markDead(session)` → clears `ATTR_ENTITY_ID` (just set to `cm.id()` two steps ago)
4. Same tick, `TickBroadcaster.onTick`'s bot iteration also queues a normal tick frame for the composite member (`bySession[predSessionId]` still has `cm.id()`).
5. `BotClient.onTick` (`src/main/java/com/paralife/bot/BotClient.java:393-396`) sees `event.code() == 'D'`, dispatches to `handleEntityTerminated("died")`, schedules respawn `r|<species>`.
6. Respawn admit allocates a fresh particle on a different cell. `botRegistry.register(sessionId, freshId, freshPos)` overwrites `bySession[sessionId]`. `entityToSession[cm.id()]` is **still pointing at this session** (only the new key gets added; the old key is not removed). The composite member is now orphaned on the grid — no client controls it; it dies eventually via decay.

**Why prior passes missed it:** `CompositeFormationTest.formationUpdatesBotRegistry` (line 206-231) only asserts `getSessionForEntity("bp1")` is empty and `getSessionForEntity(cm1.id())` is present — both hold under the buggy implementation. It does NOT assert `botRegistry.drainDeaths().isEmpty()` after composite formation. `CompositeFormationDeterminismTest` doesn't register sessions at all. `GoldenTraceEquivalenceTest`'s forced bonds use `seed-pred-N` entityIds that are never registered with `BotRegistry`, so neither bond nor composite formation fires the session path.

**Smallest fix:**
```java
botRegistry.getSessionForEntity(bp.id()).ifPresent(sessionId -> {
    botRegistry.remapEntity(sessionId, newMemberId, pos);   // 3-arg, no DeathNotice
    if (entityLifecycleListener != null) {
        entityLifecycleListener.onEntityRemapped(sessionId, bp.id(), newMemberId);
    }
});
```

This matches the pattern `revertToBondedPair` (`SimulationEngine.java:1259`) and `dissolveToParticles` (`SimulationEngine.java:1319`) already use.

**Add a regression test** in `CompositeFormationTest`: after `engine().processTick(1)`, assert `botRegistry.drainDeaths()` contains no entry for `bp1`/`bp2` and `botRegistry.drainAbsorptions()` is also empty.

---

## HIGH-2 — `PerceptionCodec` cannot decode `v|B` (absorBed); bot clients silently drop the entire E1-E5 wire path

**Where:** `src/main/java/com/paralife/codec/PerceptionCodec.java:683-697`

```java
private static void validateEventCode(char code, int pos) {
    switch (code) {
        case 'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D' -> { /* ok */ }
        default -> throw new CodecException("Unknown event code '" + code + "' at " + pos);
    }
}

private static boolean eventHasMagnitude(char code) {
    return switch (code) {
        case 'E', 'A', 'H', 'T', 'M', 'R', 'L' -> true;
        case 'N', 'S', 'D' -> false;
        default -> false;
    };
}
```

`Event.java:21` accepts `'B'` (`code != 'B'` excluded from the rejection clause). `TickBroadcaster.buildAbsorbedFrame` emits `new Event('B', ...)`. `encodeVBlock` (`PerceptionCodec.java:231-244`) appends `ev.code()` blindly — encode side is fine, the literal `B` byte goes on the wire.

**Decode side rejects `'B'` outright.** `BotClient.handlePayload` calls `PerceptionCodec.decode(payload)`, which throws `CodecException("Unknown event code 'B' at ...")`. `BotClient.handlePayload:347-351` catches it, logs `WARN "Failed to decode frame"`, returns. The bot client never sees the `v|B` event, never enters `handleEntityTerminated("absorbed")`, never respawns. The prey session is stuck with a dead binding (E1's whole point was to fix this).

The pass-2 review claim that "`Event` codec encodes/decodes `vA` consistently" was checked only on the `Event` record's compact-ctor validator — not on the `PerceptionCodec` parser, which is the actual wire boundary.

**Why prior passes missed it:** `BondDisconnectIntegrationTest.bondFormation_emitsAbsorbedNoticeForPreySession` asserts `preySession.getAttributes().get("entityId")` is null after the tick, which proves `markDead` ran inside `drainAndBroadcastAbsorptions` — but it never round-trips the encoded frame through `PerceptionCodec.decode`. There is no codec test covering the `'B'` event code.

**Smallest fix:**
```java
case 'E', 'A', 'H', 'T', 'M', 'R', 'L', 'N', 'S', 'D', 'B' -> { /* ok */ }
```
plus add `'B'` to `eventHasMagnitude`'s `case 'N', 'S', 'D' -> false;` line for clarity (functionally already false via the default branch, but explicit pinning prevents the next reviewer reproducing the same gap).

**Add a regression test:** round-trip a `Frame.TickFrame` whose events list contains `new Event('B', Optional.empty(), OptionalInt.empty())` through `PerceptionCodec.encode` → `PerceptionCodec.decode` and assert the events list is preserved byte-equal.

---

## Pass-2 work that I verified holds

- **H-B** (`DeathFinalizer.finalizeBondedPairDeath` calling `botRegistry.unregisterByEntity(bp.id())` post-H2 BotRegistry rekeying) — correct. `BondDisconnectIntegrationTest.bondedPairDeath_clearsBotRegistryAndQueuesDeathNotice` exercises the production path end-to-end.
- **H-C** (`ResumeTokenRegistry.remapEntity` rewrites STALLED entries on bond formation) — correct. The `tokenMap.replace(key, old, updated)` is atomic against concurrent `tryRebind`. `BondDisconnectIntegrationTest.stalledPredator_thenBond_thenReconnect_rebindsToBondedPair` covers it.
- **M-A** (`ActionResolver.pendingActions` migration to stable `ConcurrentHashMap` + iterator-drain) — closes the prior `AtomicReference`-swap TOCTOU.
- **M-B** (`TreeMap` for `compositeBallots`, sorted-by-sessionId for actions) — correctly stabilises HashMap iteration into the tick-thread shuffle.
- **M-D** (`processOvercrowding` calls `eligibleCellIndex.notifyChanged` on FLAG_OVERCROWDED add/clear) — correct; the radius-2 dirty bbox is sufficient since constraint #2 is purely a self-cell read.
- **M-F** (rebindSession returning false → `stale-resume-token` 400 close) — closes the late-grace-expiry race.
- **E1-E5 server-side state machine** (BotRegistry.AbsorbedNotice, drainAbsorptions, buildAbsorbedFrame, markDead, attribute clearance) — server-side path is internally consistent and tested. Wire encode is fine. **Decode is broken — see HIGH-2.**

## Concurrency reasoning on H3 register-first

I read the WS handler threading model. Spring Web dispatches inbound `handleTextMessage` per session on a Tomcat/Jetty worker; new connections (`afterConnectionEstablished`) also run on a fresh worker per session. So two concurrent `r|` from different sessions can race. `EligibleCellIndex.sample` is `synchronized`, but the index monitor is released before `worldGrid.trySetEntity` and `LiveEntityRegistry.register` run, so two concurrent placements **can** sample the same cell and only one wins `trySetEntity`. The H3 rollback path handles this. The `LER.register → LER.snapshot → LER.unregister` interleaving briefly observable by a tick handler is the M-G concern explicitly deferred to Phase 20 — in-scope per the declared deferral.

## Test seams

- `attemptPlacementForTest` skips `liveEntityRegistry.register` (the GoldenTrace caller back-fills it manually). Diverges from production `handleRegister` ordering but is fine for the determinism + digest gates it serves.
- GoldenTrace's `seed-pred-N` entities are never wired through `BotRegistry`, so the H-A composite-formation session-bookkeeping path is not exercised by the digest gate. This is the chief reason HIGH-1 survived two passes.

---

## Verdict

**NEEDS-REWORK**

Two HIGH bugs, both single-line fixes, both warrant a regression test that exercises the wire/session round-trip the existing tests bypass. Once fixed, every other pass-1/pass-2 remediation I rechecked holds.

---

## Gemini Review

1. **HIGH: H-A entity-id remap fix triggers fatal `v|D` (death) broadcast on Composite Formation.**
   In `SimulationEngine`, the H-A fix successfully retrieves the `sessionId` for the `BondedPair`. However, it uses `botRegistry.unregisterByEntity(bp.id())` followed by a `register` instead of `botRegistry.remapEntity(...)`. `unregisterByEntity` internally queues a `DeathNotice` for the session. As a result, `TickBroadcaster` erroneously sends a `v|D` terminal frame to the client and clears its `ATTR_ENTITY_ID`. The client incorrectly triggers its respawn FSM, breaking composite formation entirely.
   - **File:** `SimulationEngine.java:398`
   - **Fix:** Replace `botRegistry.unregisterByEntity(bp.id()); botRegistry.register(sessionId, newMemberId, pos);` with `botRegistry.remapEntity(sessionId, newMemberId, pos);`.

2. **HIGH: Memory leak of ACTIVE resume tokens on every death/absorption.**
   `WorldWebSocketHandler.markDead` removes `ATTR_ENTITY_ID` from the session to signal a pending respawn. However, it fails to call `resumeTokenRegistry.clearActive(...)`. Because `ATTR_ENTITY_ID` is removed, the subsequent cleanup via `cleanupBot(session)` when the client eventually disconnects also skips clearing the token. This leaks an `ACTIVE` `ResumeEntry` indefinitely for every death and bond-absorption over the lifetime of the server.
   - **File:** `WorldWebSocketHandler.java:491`
   - **Fix:** In `markDead()`, add `if (eid instanceof String entityId && resumeTokenRegistry != null) resumeTokenRegistry.clearActive(entityId);` when removing the attribute.

3. **HIGH: Determinism leak in mutagen survivor buff grants breaks byte-exact replay.**
   In `EnvironmentEngine.tickBuffsAndInfections`, the `PendingGrant` queue is populated by iterating `infections.entrySet()` (a `ConcurrentHashMap` with non-deterministic iteration order). `processPendingGrants` then iterates these grants and calls `grantSurvivorBuffs` -> `randomBuff()`, which consumes the shared `this.rng`. This means the sequence of RNG consumption across entities depends on CHM iteration order, breaking the bit-exact reproducibility contract.
   - **File:** `EnvironmentEngine.java:551`
   - **Fix:** Sort the `snapshot` of pending grants by `entityId` (`snapshot.sort(Comparator.comparing(PendingGrant::entityId));`) before the `for (var pg : snapshot)` loop in `processPendingGrants`.

4. **HIGH: `GoldenTraceEquivalenceTest` test seam bypasses `v|B` (absorbed) wire path and H-A/B/C lifecycle plumbing.**
   `GoldenTraceEquivalenceTest.seedAdjacentBondingPair` places entities directly on the grid and `LiveEntityRegistry` WITHOUT attaching them to `BotRegistry` or creating mock WebSocket sessions. When these pre-positioned entities bond, `predatorSessionId` and `preySessionId` evaluate to `null`. This bypasses the new `botRegistry.absorbBySession` call (skipping the `v|B` frame) and `EntityLifecycleListener.onEntityRemapped`. The Golden Trace fails to actually exercise the new Phase 19.5 wire shapes it claims to baseline.
   - **File:** `GoldenTraceEquivalenceTest.java:232`
   - **Fix:** Update `seedAdjacentBondingPair` to attach mock `WebSocketSession`s and register them in `botRegistry` (mirroring the `attemptPlacementForTest` mock setup in the same test) so the bond formation executes the full production lifecycle and encodes the `v|B` frames in the digest.

**NEEDS-REWORK**

---

## Codex Review

**Findings**

HIGH: `PerceptionCodec` still rejects the new absorbed event.  
`TickBroadcaster.java:330-331` emits `Event('B', ...)`, and `Event.java:19-21` accepts `B`, but `PerceptionCodec.java:683-686` does not include `B` in `validateEventCode`. Real `BotClient` sessions decode inbound frames through `PerceptionCodec.decode` before checking `D`/`B` in `BotClient.java:344-395`, so a prey client receiving `vB` logs a decode failure and never enters the respawn FSM.  
Smallest fix: add `B` to `validateEventCode`, keep it magnitude-free in `eventHasMagnitude`, and add an encode/decode round-trip test for a minimal `T|...|vB` frame.

MEDIUM: Death/absorption does not clear ACTIVE resume tokens.  
`TickBroadcaster.java:263-267` and `TickBroadcaster.java:311-315` call `markDead`, but `WorldWebSocketHandler.markDead` only removes `ATTR_ENTITY_ID` (`WorldWebSocketHandler.java:940-943`). The ACTIVE token issued for that entity remains in `ResumeTokenRegistry`; `clearActive` is only reached from normal cleanup (`WorldWebSocketHandler.java:851-854`). After H-C remaps a predator token to `bp.id`, a BondedPair death leaves that ACTIVE token stored forever. This is not immediately a bad rebind because ACTIVE tokens are rejected, but it is unbounded churn-state growth and violates the requested lock-step cleanup.  
Smallest fix: have `markDead` capture the entity id before removal, call `resumeTokenRegistry.clearActive(entityId)`, and remove `ATTR_RESUME_TOKEN`.

MEDIUM: the golden trace still does not prove the bond lifecycle/wire path it claims to cover.  
The forced bond pairs are seeded with `worldGrid.trySetEntity` plus `liveEntityRegistry.register` only (`GoldenTraceEquivalenceTest.java:315-332`); they do not create sessions, `BotRegistry` mappings, resume tokens, or outbound queues. The “formation happened” assertion at `GoldenTraceEquivalenceTest.java:142-145` can pass entirely through this bypassed path, so it does not validate `BotRegistry.absorbBySession`, `WorldWebSocketHandler.onEntityRemapped`, or the encoded `vB` frame. This gap is why the codec bug above survives.  
Smallest fix: register/attach the forced pair through the same session bookkeeping used by production, then assert at least one absorbed terminal frame is emitted and decodes.

**Verdict: NEEDS-REWORK**

---

## Opencode Review

Now let me check one final critical piece — the `ResumeTokenRegistry.remapEntity` thread safety and whether the `AbsorbedNotice` is properly drained before the session is unregistered:I've completed my thorough review of all 27 files. Here is my verdict:

---

**READY-TO-SHIP**

No HIGH findings. Three MEDIUM findings:

**M1 — `GoldenTraceEquivalenceTest` does not exercise BotRegistry bond-remap lifecycle or vB absorbed-frame path** (`GoldenTraceEquivalenceTest.java:261-264`). The seed bonding pairs (`seed-pred-1/2`, `seed-prey-1/2`) are placed directly on the grid and registered in `LiveEntityRegistry` only — they have no `BotRegistry`, `SessionRegistry`, or WS session bindings. When bonds form between them, `botRegistry.getSessionForEntity(predator.id())` returns empty at `SimulationEngine.java:718`, so `absorbBySession`, `remapEntity`, and `EntityLifecycleListener.onEntityRemapped` never fire. The 26 trace bots registered at line 300-301 have BotRegistry bindings but are placed randomly and may not form bonds with the seed pairs. Result: the D-10 equivalence gate (primary regression guard) doesn't cover the H-A/H-B/H-C or E1-E5 lifecycle paths. `BondDisconnectIntegrationTest` covers them individually. To close: add 1-2 BotRegistry-bound predator+prey pairs in `driveScenario()` alongside the existing seed pairs (using `botRegistry.register` + `sessionRegistry.register` + mock sessions with entityId attributes) so `processInteractions` exercises the full remap pipeline end-to-end.

**M2 — `processEnvDeaths` uses O(width*height) grid scan, not entity-list iteration** (`EnvironmentEngine.java:1258-1278`). Phase 19 D-07/D-09 refactored `SimulationEngine` death-sweeps but left the env-death sweep as a full grid walk. Not a bug (safe because `envDamageAppliedThisTick` gate short-circuits ~100% of idle ticks), but architecturally inconsistent with the Phase 19 design. Phase 21 benchmark profiling may flag this if env-damage tick rates are high. Recommend refactoring to iterate `liveEntityRegistry.snapshot()` — the `DeathFinalizer.finalize*` entry points are already entity-list-aware via their `unregister` hooks.

**M3 — `simRng`/`actionRng`/`spawnRng` fields are non-final and non-volatile, repeatedly passed into synchronized methods that consume RNG state inside the lock** (`SimulationEngine.java:119`, `ActionResolver.java:142`, `WorldWebSocketHandler.java:126`). The D-06 multi-threaded determinism is explicitly out of scope, and all known call sites go through `synchronized` blocks on a single monitor (e.g., `eligibleCellIndex.sample(spawnRng)`), so there is no concurrent RNG-state corruption. However, the `resetSeed()` test seam reassigns these fields without any volatile/final visibility guarantee. If a test thread calls `resetSeed()` while a production tick thread reads the field (even inside the synchronized block), the tick thread could see a stale reference. In practice this is harmless because `resetSeed()` is only called between runs (not concurrently with ticks), but the non-final non-volatile pattern is fragile. Low risk; formalize by documenting the concurrency contract or by adding a volatile.

---

## Consensus Summary

### Agreed Strengths
- H-B (`DeathFinalizer` post-H2 unregister), H-C (`ResumeTokenRegistry.remapEntity`), and pass-2 medium fixes (M-A/M-B/M-D/M-F) verified correct by reviewers who spot-checked them.
- Server-side E1–E5 absorbed-frame state machine (`AbsorbedNotice`, `drainAbsorptions`, `markDead`, attribute clearance) internally consistent and tested by `BondDisconnectIntegrationTest`.
- Encode side of `v|B` frames (`TickBroadcaster.buildAbsorbedFrame`, `PerceptionCodec.encodeVBlock`) wires correctly; `Event` record accepts `'B'`.

### Agreed Concerns
- **HIGH — H-A composite-formation path queues spurious `v|D` to predator session** (`SimulationEngine.updateBotRegistryForFormation` ~line 881–894 / 398). Pass-2 fix swapped the lookup key but kept `unregisterByEntity` + `register`, which queues a `DeathNotice`. Predator client triggers respawn FSM, composite member orphaned. Fix: use `botRegistry.remapEntity(sessionId, newMemberId, pos)` (matches `revertToBondedPair`/`dissolveToParticles`). Add regression asserting `drainDeaths`/`drainAbsorptions` empty after formation. *(claude HIGH-1, gemini #1)*
- **HIGH — `PerceptionCodec.validateEventCode` rejects `'B'`, breaking E1–E5 wire path** (`PerceptionCodec.java:683-697`). `BotClient.handlePayload` decodes via `PerceptionCodec.decode`, gets `CodecException`, logs WARN, never enters absorbed-respawn FSM. Prey session left with dead binding — defeating the whole point of E1. Fix: add `'B'` to `validateEventCode` and `eventHasMagnitude` (false). Add encode→decode round-trip test for `v|B`. *(claude HIGH-2, codex HIGH)*
- **HIGH/MEDIUM — `WorldWebSocketHandler.markDead` leaks ACTIVE resume tokens** (`WorldWebSocketHandler.java:491` / 940-943). Removes `ATTR_ENTITY_ID` without calling `resumeTokenRegistry.clearActive(...)`; subsequent `cleanupBot` cleanup also skips it. Unbounded `ResumeEntry` growth across every death/absorption. Fix: capture entityId before removal, call `clearActive`, also remove `ATTR_RESUME_TOKEN`. *(gemini HIGH #2, codex MEDIUM)*
- **MEDIUM — `GoldenTraceEquivalenceTest` does not exercise BotRegistry/session bond-remap or `v|B` wire path** (`GoldenTraceEquivalenceTest.java:232/261-264/315-332`). Forced bond pairs seeded directly via `worldGrid.trySetEntity` + `LiveEntityRegistry.register` only, no `BotRegistry`/`SessionRegistry`/mock-session bindings. `getSessionForEntity` returns empty, so `absorbBySession`/`remapEntity`/`onEntityRemapped` never fire in the digest gate. This is the chief reason the codec and composite-formation HIGHs survived two passes. Fix: attach mock sessions and register through full production path; assert at least one `v|B` frame is emitted and decodes. *(claude "test seams", gemini #4, codex MEDIUM, opencode M1)*

### Divergent Views
- **opencode verdict READY-TO-SHIP vs. claude/gemini/codex NEEDS-REWORK.** Opencode flagged only MEDIUMs and missed both the composite-formation `unregisterByEntity` regression and the codec `'B'` rejection. The other three reviewers independently identified those as HIGH with concrete failure traces — strong signal that NEEDS-REWORK is the correct verdict.
- **Mutagen-survivor RNG determinism leak** (gemini HIGH #3, `EnvironmentEngine.tickBuffsAndInfections` ~line 551): only gemini raised it. Claim is that `infections.entrySet()` (CHM) iteration order feeds `PendingGrant` ordering, which then drives `randomBuff()` consumption from shared `rng`, breaking byte-exact replay. Worth verifying directly — if true, single-line sort by `entityId` before `processPendingGrants` closes it; if `infections` is actually a sorted/insertion-ordered map at that callsite, the finding dissolves.
- **`processEnvDeaths` full grid scan** (opencode M2): only opencode raised it. Architectural-consistency nit, not a bug; defer to Phase 21 perf profiling unless env-damage hot path materialises.
- **Non-final/non-volatile RNG fields + `resetSeed()` test seam** (opencode M3): only opencode raised it. Multi-threaded determinism explicitly out of scope per D-06; harmless in current call patterns. Document the contract or mark `volatile` if cheap; not blocking.
