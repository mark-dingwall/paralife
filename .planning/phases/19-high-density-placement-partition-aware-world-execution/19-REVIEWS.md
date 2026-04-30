---
phase: 19
reviewers: [gemini, claude, codex, opencode]
rounds: [2, 3]
round_2_at: 2026-04-30T21:08:43Z
round_3_at: 2026-04-30T21:50:50Z
plans_reviewed:
  - 19-01-placement-index-PLAN.md
  - 19-02-live-entity-registry-PLAN.md
  - 19-03-golden-trace-equivalence-PLAN.md
  - 19-04-entity-list-iteration-PLAN.md
prior_review_round_commit: 1208060
models:
  gemini: gemini-3.1-pro-preview
  claude: opus (effort=xhigh)
  codex: gpt-5.5 (reasoning_effort=high)
  opencode: openrouter/moonshotai/kimi-k2.6
---

# Cross-AI Plan Review — Phase 19 (Rounds 2 & 3 merged)

Round 2 raised CONSENSUS-H1/H2/H3 plus several MED/LOW items. Plans were not re-revised between rounds. Round 3 confirmed all three blockers still present in plan body (only acknowledged in commentary), and additionally promoted three previously-singleton concerns to consensus blockers: H4 `cellStatusCache` thread-safety (3/4), H5 wrong package paths (2/4 + 1 MED), H6 `attemptPlacementForTest` visibility (2/4 + 1 MED). This document merges both rounds; each finding is tagged with the round(s) that raised it (`[R2]`, `[R3]`, `[R2+R3]`).

All four reviewers reached **HIGH overall risk** in both rounds.

---

## Gemini Review (R2 + R3 merged)

### Per-plan summary [R2 unique]
- **Plan 19-01:** Replaces inefficient 50-retry placement loop with sparse-set `EligibleCellIndex` providing O(1) sampling and enforcing occupancy + overcrowding via 5x5 bounding box. Comprehensively wires lifecycle hooks into mutation sites to prevent stale eligibility state.
- **Plan 19-02:** Introduces `LiveEntityRegistry` as O(N) iteration foundation to replace O(grid) scans. Implements critical row-major snapshot sort to match legacy grid traversal and correctly establishes `sessionId` field to track bot ownership.
- **Plan 19-03:** Constructs robust semantic equivalence gate (`GoldenTraceEquivalenceTest`) capturing outbound WebSocket frames post-send for deterministic byte-for-byte output across refactoring boundaries, with automated file-based baseline pinning.
- **Plan 19-04:** Executes core SCALE-07 by refactoring `SimulationEngine` and `EnvironmentEngine` to consume new `LiveEntityRegistry`, carefully preserving single-threaded invariants and excluding spatially-dependent diffusion passes.

### R3 framing
Round 3 plans maintain structural strengths of sparse-set + per-session digest architectures but **critically ignored the consensus blocking feedback from Round 2**. Plans continue broken `sessionId` model dropping perception frames for bonded/composite (H1), use a too-sparse golden trace scenario (H2), retain drain race (H3), and omit cross-run session cleanup. Most alarmingly Plans 19-01 and 19-02 suffer severe line-number hallucination, instructing the executor to place destructive lifecycle hooks inside the `applyDeltaToOccupant` combat damage method under the mistaken belief that it is a structural `collapseToMember` method.

### Strengths [R2+R3]
- **Row-major `snapshot()` ordering** (Plan 19-02) correctly mirrors legacy grid-scan traversal, neutralising `Collections.shuffle` output divergence.
- **Generate-if-missing baseline** (Plan 19-03) eliminates manual hex copy-paste into Java source.
- **Per-session digest map** (Plan 19-03) correct abstraction for cross-session VT scheduling jitter.
- **Lock-order Javadoc + RockGenerator sync verification** (Plan 19-01) excellent defensive engineering.
- **Scope discipline** (Plan 19-04) [R2 unique] correctly excludes `EnvironmentEngine`'s spatial diffusion loops and `SimulationEngine`'s nutrient spawning from entity-list refactor.

### Concerns

- **HIGH — CONSENSUS-H1 [R2+R3]: Erroneous `TickBroadcaster` refactor breaks composite/bonded perception (Plan 04).** Plan 04 instructs refactoring `TickBroadcaster.onTick` to iterate `LiveEntityRegistry.snapshot()` instead of `botRegistry.getAllBots()`. `LiveEntityRegistry` tracks grid occupants (e.g., 1 `BondedPair` with `Optional.empty()` session ID); instruction to "skip" empty session IDs means bot clients controlling bonded/composite entities will **never** receive tick frames. `TickBroadcaster` already does O(bots) iteration; it does not do a grid scan.
  - *Quote [R2]:* "TickBroadcaster session resolution ... If sidOpt.isEmpty() ... skip — the BondedPair/CompositeMember currently has no perception path of its own"
  - *R3 framing:* Plan 19-02 explicitly rejects previous review's fix, continuing `Optional.empty()` for BondedPair/CompositeMember; Plan 19-04 instructs `TickBroadcaster` to skip empty session IDs, leaving executor to "reproduce the fan-out" with code block that just executes `continue;`. Guarantees connected bots controlling bonded/composite entities silently stop receiving perception frames.

- **HIGH — CONSENSUS-H4 [R2+R3]: Concurrency violation on `cellStatusCache` read (Plan 01).** `EligibleCellIndex.notifyChanged` is called from WebSocket thread during registration, invoking `environmentEngine.cellStatusCacheView()`. `EnvironmentEngine` uses standard `HashMap` for `cellStatusCache`, cleared and mutated by tick thread. Concurrent access predictably throws `ConcurrentModificationException`.
  - *Quote:* "Map<Position, Byte> hoistedCache = environmentEngine.cellStatusCacheView();"

- **HIGH — CONSENSUS-H3 [R2+R3]: Race in `awaitAllSessionQueuesDrained` (Plan 03).** Test checks `outboundSender.queueDepth(sid) > 0`. Drain VT takes frame from queue *before* encoding/sending, so `queueDepth` hits 0 while final `listener.onEmit` still executing. Test thread reads `capture.emitCount()` prematurely → flaky digest mismatches.
  - *Quote:* "if (outboundSender.queueDepth(sid) > 0) { allDrained = false; break; }"

- **HIGH — CONSENSUS-H2 [R3 explicit; R2 implicit via Plan 04 risk]: Golden Trace Scenario too sparse.** Plan 19-03 still uses 10 bots × 50 ticks × 32×32 grid (~1% density). Far too sparse to reliably trigger bond/composite formation. Equivalence gate passes without exercising most volatile refactoring boundaries.

- **HIGH — Line-Number Hallucinations [R3].** Plan 19-01 and 19-02 instruct executor to hook `collapseToMember` at lines 695–701. In the codebase those lines are inside `applyDeltaToOccupant()` (combat/splash damage resolution). Hooking `liveEntityRegistry.unregister()` here means entities are unregistered from simulation the moment they take damage. Plan 19-02 also assumes `botRegistry.unregisterByEntity(...)` at lines 719/725; those are actually `emptyNeighbors` / `individualEnergy` calc inside composite formation.

- **MEDIUM — Redundant index recalculations on energy updates [R2+R3] (Plan 01).** Plan 01 hooks `notifyChanged` into energy decay / combat damage (e.g., `processEnergyDecay` 756, 783). Energy changes don't affect occupancy or overcrowding. 5x5 grid eval per entity per tick = pure waste.

- **MEDIUM — Missing Cross-Run Session Cleanup [R3] (Plan 03).** `resetAll()` doesn't detach outbound sessions or unregister from `sessionRegistry`. Two consecutive `driveScenario` calls reuse same session IDs (`trace-sess-0..9`) → duplicate sender VTs, digest corruption risk.

### Suggestions [merged, dedup]

1. **Fix `TickBroadcaster` Scope (Plan 04) [R2]:** Completely remove `TickBroadcaster` from entity-list refactor. It must continue iterating `botRegistry.getAllBots()` to ensure every connected client receives frames, preserving existing composite/bonded broadcasting semantics. Already O(bots), fulfils SCALE-07 natively.
2. **Fix H1 SessionId Wiring [R3 alternate]:** Don't assign `Optional.empty()` for bonded pairs. In Plan 19-02's `processInteractions` hook, look up predator's session via `botRegistry.getSessionForEntity(predator.id())` and pass to `liveEntityRegistry.register(bondedPair.id(), ..., Optional.of(predatorSessionId))`. Same for CompositeMembers via primary's session.
3. **Fix Cache Thread-Safety [R2+R3] (Plan 01/EnvironmentEngine):** Change `EnvironmentEngine.cellStatusCache` to `ConcurrentHashMap`, OR have `buildStatusCaches()` publish immutable `Map.copyOf(...)` at end for safe concurrent reads.
4. **Fix Drain Race [R2+R3] (Plan 03):** After `queueDepth == 0` loop, acquire monitor for each session to guarantee VT exited send block:
   ```java
   if (allDrained) {
       for (String sid : registeredSessionIds) {
           WebSocketSession s = sessionRegistry.getSession(sid);
           if (s != null) synchronized(s) {} // wait for in-flight sendMessage
       }
       return;
   }
   ```
5. **Fix Line Number Hallucinations [R3]:** Remove all instructions to hook lines 695–725 under guise of `collapseToMember`. Hook structural changes accurately in `revertToBondedPair` (~1051) and `dissolveToParticles` (~1098). Stop instructing modifications to `applyDeltaToOccupant`.
6. **Fix H2 Golden Trace Density [R3]:** Increase scenario in `driveScenario` to 30 bots on 16×16 grid for 200 ticks, or script deterministic placement forcing adjacent predator/prey to trigger bonding.
7. **Optimize Hooks [R2+R3] (Plan 01):** Remove `notifyChanged` from `processEnergyDecay` and `applyDeltaToOccupant`. Only hook structural grid mutations.
8. **Fix Cleanup [R3] (Plan 03):** Add `outboundSender.detachSession(sid)` + `sessionRegistry.unregister(sid)` per registered session to `resetAll()`.

### Risk Assessment

**HIGH [R2+R3].** Architectural shape sound. Wave sequencing correct. Sparse-set + determinism strategies exceptionally well-designed. But plans contain fatal flaws: `TickBroadcaster` refactor (Plan 04) breaks perception for complex entities; `ConcurrentModificationException` (Plan 01); test harness race (Plan 03). Round 3: plans actively ignored Round 2 consensus blockers; line-number hallucinations cause runtime bugs (entities unregistering on combat damage). Do not advance to execution until H1, H2, H3, H4, and hallucinated line numbers fully corrected. With suggestions applied risk drops to **MEDIUM** (then **LOW** if all M-tier fixes).

---

## Claude Review (R2 + R3 merged)

### Per-plan summary [R2 unique]

- **Plan 19-01 (Placement Index):** Tight. Sparse-set + dirty-bbox solid. Lifecycle-hook coverage broad. Lock-order Javadoc + RockGenerator sync verify good additions. Ctor cascade still risky but pre-flight grep mitigates. One minor: `attemptPlacementForTest` introduces refactor extraction without specifying shape.
- **Plan 19-02 (Live Entity Registry):** Row-major sort fix sound. **But sessionId model has two HIGH-severity bugs at bond-formation and composite-formation hooks** that REVIEWS HIGH-3 was supposed to close. Invariant test doesn't catch them because optional bond/composite scenarios are `@Disabled`-friendly.
- **Plan 19-03 (Golden Trace):** Per-session digest + resource-file pin good. `awaitAllSessionQueuesDrained` race fix correct. **But test scenario (10 bots × 32×32 × 50 ticks) likely too sparse to trigger bond formation — hides Plan 02 sessionId bugs from the gate.** Cross-run OutboundSender state cleanup not addressed.
- **Plan 19-04 (Entity-List Iteration):** Mechanical refactor + grep-counters tight. Row-major sort claim correct for SimulationEngine pre-shuffle equivalence. **But Plan 04 step 2's TickBroadcaster "skip empty sessionId" path makes incorrect claim about pre-refactor behavior — actual semantics differ when BondedPair exists.** Plan 04 says "expected GREEN on first run"; not guaranteed.

### R3 framing
Round 2 raised three CONSENSUS-HIGH blockers + several MEDIUMs. **Round 3 plans NOT actually fix CONSENSUS-H1, H2, H3.** Plan 02 still registers BondedPair/CompositeMember with `Optional.empty()`. Plan 03 still 10 bots × 32×32 × 50 ticks, still no `synchronized(session)` barrier post-drain. Plus several wrong package paths + visibility bugs that make plans not compile.

### Strengths [R2+R3 merged]
- Wave sequencing 01→02→03→04 correct. Plan 03 pin-before-Plan-04 closes self-agreement loophole.
- Sparse-set data structures right-sized for 256×256 + ≤256 entities.
- REVIEWS feedback substantively folded: row-major sort (HIGH-1), drain race fix via `registeredSessionIds` (HIGH-2), resource-file digests not Java source (MED-1), pre-flight signature check (MED-4), lock-order Javadoc (L1), RockGenerator sync verify (L2), lost-race metric (L3). All preserved through R3.
- Plan 01 ctor-cascade pre-flight grep + `compileTestJava` gate catches missed test instantiations cheaply.
- Plan 02 `LiveEntityRegistryInvariantTest` — registry vs grid agreement check is right shape.
- Plan 03 vacuous-baseline triple-guard (emitCount > 0, map non-empty, no per-session hex == EMPTY_SHA256_HEX) correct.
- Plan 04 grep counters tight: shuffle == 3 (M4), nested loops ≤ 2 (M5).
- D-08/D-11 single-threaded mutation invariant preserved via `parallelStream` ban grep.
- `parallelStream` ban + @Order preservation gate keep D-08/D-11 invariant honest [R3].

### Concerns

#### HIGH

**[H1] CONSENSUS-H1 [R2+R3] — Plan 02 step 3(b)/3(c) still `Optional.empty()`. NOT FIXED in R3.**

Plan 02 task 2 still says verbatim:
```
liveEntityRegistry.register(bondedPair.entityId(), bond.primaryPos, Optional.empty());
...
liveEntityRegistry.register(member1.entityId(), cf.pos1(), Optional.empty());
liveEntityRegistry.register(member2.entityId(), cf.pos2(), Optional.empty());
```
Plan 04 step 2 TickBroadcaster:
```java
if (sidOpt.isEmpty()) { continue; }
```
Pre-refactor evidence: `SimulationEngine.processInteractions` bond-formation block (lines 555–619) does NOT call `botRegistry.unregisterByEntity(predator)` at bond. Only `worldGrid.setEntity(primary, bondedPair)` + `worldGrid.clearEntity(secondary)`. So `botRegistry.bySession` keeps predator's BotState. Pre-refactor `TickBroadcaster.onTick` iterates `botRegistry.getAllBots()`, finds predator's BotState, reads `worldGrid.getCell(primary.pos).occupant()` = BondedPair, builds perception, sends to predator's session. Post-refactor: registry has BondedPair with `Optional.empty()` → Plan 04 skip → 0 perception frames for that session.

For composite: `updateBotRegistryForFormation` (SimulationEngine.java:716) re-binds primary's session to memberId in BotRegistry. Plan 02 wires member1 with `Optional.empty()`. Plan 02's own `LiveEntityRegistryInvariantTest.sessionIdAgreesWithBotRegistry` will fail post-formation (BotRegistry has primarySession; LiveEntityRegistry has empty).

R2 reviewers offered 2 fixes (A: lookup session before unregister; B: keep TickBroadcaster on BotRegistry). R3 picked neither — documented broken design AS the fix.

Plan 04 STOP/escalate guard catches this but operator burden — Plan 04 says "expected GREEN on first run" which is wrong. **Blocker.**

**Fix:** at bond formation, register BondedPair with `Optional.of(predator_session)` not `Optional.empty()`. Read predator's session from `botRegistry.getSessionForEntity(predator.entityId())` BEFORE the predator is unregistered. Or: don't unregister predator from LiveEntityRegistry; just don't register the BondedPair separately.

**[H2] CONSENSUS-H2 [R2+R3] — Plan 03 driveScenario still 10 bots × 32×32 × 50 ticks. NOT FIXED in R3.**

Density 1%. Bond formation requires (a) adjacent predator+prey of specific RPS pairs, (b) both energies ≥ `bondingConfig.bondEnergyThreshold`, (c) random roll under `bondingConfig.bondingProbability`. At 1% with random placement, adjacency is rare; bonding may not fire in 50 ticks. EXPECTED_DIGESTS pinned at this density may not exercise H1 paths → Plan 04 ships green but broken; bug surfaces at Phase 21. **Blocker for the gate's value as oracle.**

**Fix options:** Increase density (50 bots on 32×32 ≈5%, or 30 bots on 16×16 ≈12%). Run for 200+ ticks. Add explicit bonded-pair-creating sub-scenario in `driveScenario` (place adjacent predator+prey deterministically before main loop). Require `LiveEntityRegistryInvariantTest.registryMatchesGridOccupantsAfterBondFormation` enabled.

**[H3] CONSENSUS-H3 [R2+R3] — Plan 03 `awaitAllSessionQueuesDrained` still queueDepth-only.**
```java
for (String sid : registeredSessionIds) {
    if (outboundSender.queueDepth(sid) > 0) { allDrained = false; break; }
}
```
Drain VT takes frame from queue → `queueDepth==0` → test reads digest while in-flight `sendMessage`/`onEmit` still running. Flaky digests. R2 fix: post-drain `synchronized(session) {}` barrier OR `inFlight` counter. R3 unchanged.

**[H4] CONSENSUS-H4 [R3 explicit; gemini-only R2] — `EnvironmentEngine.cellStatusCache` thread-safety race.**

`cellStatusCacheView()` returns `Collections.unmodifiableMap(cellStatusCache)`. Underlying HashMap. Tick thread mutates inside `buildStatusCaches`. WS thread calls `EligibleCellIndex.notifyChanged` → reads `cellStatusCache.get(new Position(...))` concurrent with tick-thread put. JDK 21 HashMap.get on concurrent put = race; not always CME but possible stale read, possible null return mid-resize. Plan 01 hoists view once but doesn't fix underlying race.

**Fix:** `ConcurrentHashMap` OR `Map.copyOf` swap to volatile field at end of `buildStatusCaches`.

**[H5] CONSENSUS-H5 [R3, codex flagged R2] — Wrong package paths; plans not compile.**

- `OutboundSender` actual = `com.paralife.admission.OutboundSender` (WorldWebSocketHandler.java:9). Plan 03 still references `src/main/java/com/paralife/websocket/OutboundSender.java` and imports `com.paralife.websocket.OutboundSender`.
- `TickEvent` actual = `com.paralife.engine.TickEvent` (TickBroadcaster.java:20). Plan 02 LiveEntityRegistryInvariantTest imports `com.paralife.websocket.TickEvent`. Plan 03 GoldenTraceEquivalenceTest imports `com.paralife.websocket.TickEvent`.

Pre-flight grep gates not catch — must be fixed in plan body.

**[H6] CONSENSUS-H6 [R3, codex flagged R2] — `attemptPlacementForTest` package-private from `com.paralife.websocket`, called from `com.paralife.engine` — not compile.**

Plan 01 task 2 step 1(f):
```java
Optional<Position> attemptPlacementForTest(String entityId, Entity.ParticleType type, int initialEnergy) {
```
No `public` modifier. PlacementDeterminismTest, LiveEntityRegistryInvariantTest, and GoldenTraceEquivalenceTest all in `com.paralife.engine`. Trivial fix (`public`), not made.

#### MEDIUM

**[M1] [R2+R3] Plan 02 `register()` is idempotent but silently drops new sessionId on re-register.**
```java
public synchronized void register(String entityId, Position position, Optional<String> sessionId) {
    if (indexById.containsKey(entityId)) return; // idempotent — preserve existing entry
    ...
}
```
If entityId already registered with `Optional.empty()` and re-registered with `Optional.of(X)`, X is dropped. Could mask H2-style hooks. Suggest: throw `IllegalStateException` on conflicting re-register, force callers to `unregister` first.

**[M2] [R2+R3] Plan 03 cross-run state cleanup missing.** Two `driveScenario` calls within one `@Test` reuse same sessionIds (`trace-sess-0..9`). Plan 03 `resetAll()`:
```java
worldGrid.clear(); botRegistry.clear(); liveEntityRegistry.clearForTest(); handler.resetSeed();
```
No `outboundSender.detachSession(...)` or `sessionRegistry.unregister(...)`. Re-attaching same sessionId in run 2 → unspecified OutboundSender behavior; possible duplicate drain VT, double frames, digest divergence.

**[M3] [R2 only] Plan 01 `attemptPlacementForTest` extraction not specified in detail.** Plan body doesn't show extracted method's interface. Risk: extraction reorders calls (sequence of `eligibleCellIndex.sample` + `worldGrid.trySetEntity` + `eligibleCellIndex.notifyChanged` + `botRegistry.register`). Production divergence from intent goes undetected. Plan should specify the extracted method's exact signature and body.

**[M4] [R2+R3] Plan 01 ActionResolver hook count threshold (`>= 8`) is loose.** Plan 01 Task 2 step 4 lists 13 specific lines for `notifyChanged`. Acceptance: `>= 8`. Five sites could be silently skipped. Same pattern in Plan 02 acceptance (`>= 14` SimulationEngine hooks). R3 acceptance grep counter `>= 12` actively encourages keeping the energy-only hooks. Tighten to exact line list.

**[M5] [R2+R3] Plan 04 step 2 TickBroadcaster comment block contains incorrect claim:**
> "the per-bot perception is keyed on the bot's session, which is the predator or prey id (those were unregistered from LiveEntityRegistry at bond-formation time per Plan 02)"

Pre-refactor key is `BotState.sessionId` from BotRegistry, NOT entityId. BotState lingers post-bond. Comment claims "minimum-viable refactor: skip" preserves equivalence — it doesn't. Will mislead executor into accepting H1 divergence.

**[M6] [R2+R3] Plan 02 `LiveEntityRegistryInvariantTest` declares bond/composite scenarios but allows them to be `@Disabled`:**
> "registryMatchesGridOccupantsAfterBondFormation": "... For full implementation, mirror EnvironmentDeterminismTest setup style."
> "Tests that are too elaborate to implement in this single task ... can be marked @Disabled with a comment ..."

Minimum-required tests (`AtRest`, `AfterDeath`, `sessionIdAgreesWithBotRegistry`) DO NOT exercise bond/composite identity model. So H1/H2 bugs slip past Plan 02's invariant gate.

**[M7] [R3, OpenCode L R2] Plan 01 `cellStatusCache.get(new Position(x, y))` allocates Position per cell × 25 cells per event.** Minor GC pressure at 1000+ events/tick. Could be alleviated by directly indexing if cache key changed to linear int.

#### LOW

**[L1] [R2+R3] Plan 03 OutboundSender listener exception swallow is `Throwable` catch:**
```java
try { listener.onEmit(sessionId, encodedBytes); }
catch (Throwable t) { log.warn("FrameEmitListener threw, ignoring: {}", t.toString()); }
```
Catching `Throwable` swallows `OutOfMemoryError`/`StackOverflowError`. Should be `Exception` (or `RuntimeException`). One-character fix.

**[L2] [R2+R3] Plan 02 Optional in record warnings.** `EntityEntry` is public record with `Optional<String> sessionId`. Style guides discourage `Optional` in records (serialization, equals semantics). Acceptable since record is server-internal but worth a Javadoc comment that this is intentional.

**[L3] [R2+R3] Plan 04 acceptance `git diff -- src/test/resources/golden-trace-phase19.json | wc -l == 0`** — works only if Plan 03 committed file before Plan 04 starts. Cross-plan timing dependency. Plan should specify "Plan 03 must commit the resource file before Plan 04 begins" explicitly. R3 also: grep-based acceptance gates still brittle smoke checks; counts like `>= 8` for ActionResolver hooks let 5 sites silently slip.

**[L4 — R2 unique] Plan 01 `EligibleCellIndex.notifyChanged` constraint-3 evaluation is O(8 × 8) = 64 reads per cell × 25 cells = 1600 reads per event.** At register storms (100+/sec), this becomes meaningful. Plan acknowledges as Phase 21 benchmark observation but no explicit metric.

**[L4 — R3, originally Codex MED R2] Plan 01 lost-race fallback creates false GRID_FULL under concurrent registrations.** Two sessions sample same cell → one gets GRID_FULL despite many empty cells. Metric helps observe but not prevent.

**[L5 — R2 unique] Plan 02 `LiveEntityRegistryInvariantTest` instantiates `new com.paralife.websocket.TickEvent(1)` with constructor signature unverified.** Plan 03 confirms `new TickEvent(t)` works — if `TickEvent` requires more fields (e.g. timestamp), test won't compile. (R3 H5 elevates the package-path part; R2 L5 was the constructor-shape part — both worth pre-flighting.)

### Suggestions [merged, dedup]

1. **(BLOCKING) Resolve CONSENSUS-H1 properly. Pick one:**
   - **Option A (registry-as-truth):** at bond-formation, lookup `botRegistry.getSessionForEntity(predator.entityId())` BEFORE unregister; pass `Optional.of(predatorSession)` to `liveEntityRegistry.register(bondedPair.id(), pos, ...)`. Same for composite via `updateBotRegistryForFormation` order: BotRegistry first, then read session, then `liveEntityRegistry.register`.
   - **Option B (carve-out):** drop TickBroadcaster from Plan 04 entirely. Keep `botRegistry.getAllBots()` iteration. Already O(bots). LiveEntityRegistry stays grid-occupant registry for SimulationEngine/EnvironmentEngine only.
   - Pick one, encode in Plan 02 + Plan 04, NOT just document.
2. **(R2 unique) `checkPanicZone` fallback hooks at SimEngine 1127/1136.** Lines 1127 and 1136 inside `checkPanicZone` call `botRegistry.unregisterByEntity(memberId)` without corresponding `liveEntityRegistry.unregister`. Plan's grep count likely includes them but task instructions don't explicitly mention `checkPanicZone`. Add to avoid stale entries when composite pool hits zero.
3. **(BLOCKING) Tighten Plan 03 driveScenario.** 30 bots × 16×16 × 200 ticks + deterministic adjacent-RPS placement step. Append `ActionFrame` queue (e.g., all bots move N) so ActionResolver path covered. Optional second @Disabled scenario for explicit bond-formation pinning.
4. **(BLOCKING) Plan 03 `awaitAllSessionQueuesDrained` fix:**
   ```java
   if (allDrained) {
       for (String sid : registeredSessionIds) {
           WebSocketSession s = sessionRegistry.getSession(sid);
           if (s != null) synchronized (s) {} // wait for in-flight sendMessage
       }
       return;
   }
   ```
   Or expose `OutboundSender.inFlight(sid)` counter incremented before `sendMessage` and decremented after `onEmit`.
5. **(BLOCKING) Fix package paths globally [R3]:**
   - `com.paralife.admission.OutboundSender` (not `websocket.OutboundSender`) in Plan 03 files_modified, imports, code.
   - `com.paralife.engine.TickEvent` (not `websocket.TickEvent`) in Plan 02 + Plan 03 imports.
6. **(BLOCKING) Make `attemptPlacementForTest` `public`** OR move PlacementDeterminismTest / GoldenTraceEquivalenceTest / LiveEntityRegistryInvariantTest into `com.paralife.websocket` package.
7. **(HIGH) Make `cellStatusCache` thread-safe.** Easiest: at end of `buildStatusCaches`, publish `Map.copyOf(cellStatusCache)` to a `volatile` field; `cellStatusCacheView()` returns the volatile snapshot. WS thread reads immutable copy; tick thread mutates the staging map.
8. **(MED) Drop notifyChanged from energy-only sites.** Tighten Plan 01 ActionResolver/SimulationEngine hook list to structural mutations only (place/clear/move/bond/composite-form/dissolve/revert/death/spawn).
9. **(MED) Plan 03 resetAll cleanup:**
   ```java
   for (String sid : registeredSessionIds) {
       outboundSender.detachSession(sid);
       sessionRegistry.unregister(sid);
   }
   registeredSessionIds.clear();
   ```
   Verify signatures via the same MED-4 pre-flight grep.
10. **(MED) Plan 02 — make `registryMatchesGridOccupantsAfterBondFormation` + post-bond/post-composite `sessionIdAgreesWithBotRegistry` MANDATORY.** No @Disabled escape. These catch H1/H2 cheaply.
11. **(MED) Plan 01 + Plan 02 — re-derive line numbers from current SimulationEngine.java.** No `collapseToMember` exists. Replace cited 695/698/701 with `applyDeltaToOccupant` (if intended) or `revertToBondedPair` line 1051 + `dissolveToParticles` line 1098 (if intended). Be explicit which.
12. **(MED) Plan 04 task 2 — fix misleading TickBroadcaster comment.** State actual pre-refactor semantics: "Pre-refactor TickBroadcaster iterated `botRegistry.getAllBots()`. After bond formation, predator's BotState lingered in BotRegistry; the BondedPair grid occupant got a perception frame sent to predator's session. Post-refactor must preserve this — see H1 fix above."
13. **(MED) Plan 02 `register(...)` throw `IllegalStateException` on conflicting re-register.** Defense-in-depth catches H1/H2-style logic errors.
14. **(LOW) Plan 03 listener catch `Exception` not `Throwable`.**

### Risk Assessment

**Overall: HIGH [R2+R3].** Same blockers in both rounds. Architectural shape sound; risk concentrated in bond/composite sessionId model (H1+H2). H3 amplifies — narrow seed scenario hides the regression. R3 surfaced 3 fresh compile-blockers (H4 thread-safety, H5 package paths × 2, H6 visibility × 1) that mean Wave 1+ won't even build without fixes.

If suggestions 1–5 applied: drops to **MEDIUM** (cellStatusCache race + perf hooks + docs). If 1–9: drops to **LOW**.

**Recommend: do NOT execute Plans 02–04. Re-revise to actually encode round 2 consensus fixes in plan body, not just commentary. Plan 01 mostly executable after H4/H6 + line-number correction + drop energy-only hooks.**

---

## Codex Review (R2 + R3 merged)

### Per-plan summary [R2 unique]

- **Plan 19-01 — Placement Index:** Solid direction for SCALE-06: sparse-set sampling, explicit eligibility rules, deterministic seed test, GRID_FULL preservation are right primitives. Main risk is lifecycle completeness. Plan says every grid mutation must notify the index, but enumerated hook list misses real production cleanup/mutation paths, so index can become stale under disconnect, stalled cleanup, environment damage, composite energy updates.
- **Plan 19-02 — Live Entity Registry:** Registry abstraction useful, row-major snapshot ordering good revision for preserving pre-refactor shuffle inputs. However, revised `Optional<String> sessionId` model is currently inconsistent with existing bonded/composite session semantics. Plan registers only grid occupants, often with `Optional.empty()`, while current broadcasting is per bot session. High risk that bonded/composite-controlled bots stop receiving tick frames after Plan 04.
- **Plan 19-03 — Golden Trace Equivalence:** Resource-file digest pinning, per-session hashing, non-empty guards, explicit registered-session drain list all meaningful improvements. But test is likely too weak as oracle: drives random placements and ticks, doesn't guarantee action resolution, bonding, composites, death, or movement paths exercised. May pass while highest-risk semantic paths broken.
- **Plan 19-04 — Entity List Iteration:** Staged dependency on Plans 02 and 03 conceptually correct. Preserving grid-walks for nutrient spawning/diffusion appropriate. Highest risk: `TickBroadcaster` refactored from `BotRegistry.getAllBots()` to `LiveEntityRegistry.snapshot()` before registry can faithfully represent per-session perception targets. As written, this can change who receives frames, not just how entities are found.

### R3 framing
Phase 19 architecture still broadly right but plans remain **high risk**. Sparse-set placement and row-major registry ideas sound, yet several blockers still present: `TickBroadcaster` moved from per-session registry to grid-occupant registry, golden-trace coverage too weak and still race-prone, placement-index maintenance has unresolved concurrency and lifecycle gaps.

### Strengths [R2+R3 merged]
- Phase split good: placement, lifecycle registry, equivalence gate, then iteration refactor.
- Sparse-set `EligibleCellIndex` correct replacement for retry placement.
- Row-major `LiveEntityRegistry.snapshot()` good compatibility shim for pre-refactor grid scan order.
- Per-session digest map (Plan 19-03) correctly avoids cross-session VT scheduling noise.
- Resource-file baseline pinning better than hardcoded Java `Map.of(...)` digests.
- Plans repeatedly preserve single-threaded mutation invariant; defer `parallelStream()` work to 19.1.
- `GRID_FULL` wire-shape preservation and `RejectionToken.GRID_FULL` reuse aligned with milestone constraints.
- Lost-race metric useful operationally for Phase 21 load evidence.
- Wave ordering correct: placement, registry, oracle, then iteration refactor.
- Scope discipline mostly good: nutrient spawn and diffusion remain grid-walks.

### Concerns

#### HIGH

- **CONSENSUS-H1 [R2+R3] — Plan 19-04 still breaks session-based broadcasting.** `TickBroadcaster` currently iterates `botRegistry.getAllBots()` at [TickBroadcaster.java:185](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/TickBroadcaster.java:185), already O(bots), not O(grid). Refactoring to `LiveEntityRegistry.snapshot()` changes iteration domain from sessions to grid occupants. Empty `sessionId` entries for bonded/composite occupants drop client frames.

- **[R2 unique — wrong-id unregister bondedPair] Plan 19-02 unregisters wrong IDs for bonded-pair death.** Plan says `DeathFinalizer.finalizeBondedPairDeath` calls `liveEntityRegistry.unregister(primaryId)` and `unregister(secondaryId)`. But Plan 19-02 also says child IDs are not separately registered and only grid-occupant ID is registered. Registry entry should be `bp.id()`, not `primaryEntityId` / `secondaryEntityId`. Current code unregisters primary/secondary from `BotRegistry` at [DeathFinalizer.java:102](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/DeathFinalizer.java:102), but grid occupant is the `BondedPair`.

- **CONSENSUS-H4 [R3 explicit; R2 silent] — Plan 19-01 cache read not thread-safe.** `EligibleCellIndex.notifyChanged` reads `EnvironmentEngine.cellStatusCacheView()`, but `cellStatusCache` is mutable `HashMap` cleared/mutated during ticks ([EnvironmentEngine.java:177](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:177), [EnvironmentEngine.java:324](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/EnvironmentEngine.java:324)). WS registration races with tick mutation.

- **CONSENSUS-H5 [R2+R3] — Plan 19-03 still uses wrong package paths.** `OutboundSender` is `com.paralife.admission.OutboundSender`, not `com.paralife.websocket.OutboundSender` ([OutboundSender.java:55](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/OutboundSender.java:55)). `TickEvent` is `com.paralife.engine.TickEvent` ([TickEvent.java:9](/home/mark/kramtime/paralife/src/main/java/com/paralife/engine/TickEvent.java:9)). Plan's sample imports and `files_modified` will mislead execution.

- **CONSENSUS-H6 [R2+R3] — `attemptPlacementForTest` access will not compile.** Plan 19-01 says to add package-private `WorldWebSocketHandler.attemptPlacementForTest(...)`, but `PlacementDeterminismTest` is in `com.paralife.engine`. Plan 19-03 also calls this seam from `com.paralife.engine`. Package-private methods in `com.paralife.websocket` not accessible from `com.paralife.engine`.

- **CONSENSUS-H1-extension [R2+R3] — Plan 19-01 hook audit misses real grid mutation sites.** Plan's "every grid mutation site" list omits `WorldWebSocketHandler.cleanupByEntityId` and `cleanupBot`, which clear grid cells on resume-token expiry/disconnect at [WorldWebSocketHandler.java:655](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:655) and [WorldWebSocketHandler.java:695](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:695). Also omits `EnvironmentEngine` and `CompositeEnergyDistributor` `setEntity` calls. If intentionally excluded as energy-only updates, plan should say so.

- **CONSENSUS-H2 [R2+R3] — Plan 19-03 golden trace may be weak semantic oracle.** Scenario places 10 bots randomly in 32×32 for 50 ticks. Doesn't guarantee adjacency, actions, combat, bonding, composite formation, movement, death, or reproduction. May not exercise the exact SimulationEngine loops Plan 19-04 refactors.

#### MEDIUM

- **[R2 unique — cleanupByEntityId/cleanupBot lifecycle] Plan 19-01 misses cleanup lifecycle paths.** `cleanupByEntityId` and `cleanupBot` clear grid entities on stalled-token expiry/disconnect ([WorldWebSocketHandler.java:655](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:655), [WorldWebSocketHandler.java:695](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:695)). These need placement-index AND live-registry hooks. *(Codex carrying this as MED in both rounds; surfaces alongside the H1-extension framing in R3.)*

- **[R2+R3] Over-hooking energy-only writes.** `applyDeltaToOccupant`, decay, toxin/mutagen damage, combat damage, starvation flag updates don't change occupancy or neighbor counts. Dirtying 5x5 bboxes there adds needless load.

- **[R2+R3] Drain race in `awaitAllSessionQueuesDrained`** *(R2 framed as MED, R3 elevated to HIGH)*. `queueDepth(sid) == 0` becomes true after sender VT takes frame and before `sendMessage`/listener callback finish. Fix: in-flight counter or synchronized-session barrier after queues hit zero.

- **[R2+R3] Golden trace test leaks attached sender VTs/sessions unless cleaned up.** `tearDown()` clears listener but doesn't detach outbound sessions or unregister sessions. Because `OutboundSender.attachSession` starts sender VT, test should explicitly detach/unregister all `registeredSessionIds`.

- **[R2+R3] Plan 19-01 no-retry lost-race fallback creates false `GRID_FULL` under concurrent registrations.** Two sessions sample same eligible cell, one succeeds, other emits `GRID_FULL` even when many cells remain. Metric helps observe but doesn't prevent avoidable admission failure.

- **[R2 unique — back-compat ctors null] Back-compat constructors forwarding `null` for new required collaborators are risky.** Plan 19-01 says alternate `WorldWebSocketHandler` constructors should forward `null` for `EligibleCellIndex`. If any legacy direct-instantiation test exercises registration, becomes NPE after retry loop is deleted.

- **[R2+R3] LiveEntityRegistry invariant coverage allows skip of riskiest cases.** Plan 19-02 Task 3 permits bond/composite scenarios `@Disabled`, exactly the lifecycle paths most likely to break.

#### LOW

- **[R2 unique — row-major name imprecision] "Row-major" name imprecise but formula correct for current loops.** Current scan is `for x` outer, `for y` inner; plan's `x * height + y` matches that order. More "x-major" than conventional row-major, but compatibility behavior is right.
- **[R2+R3] Grep-based acceptance gates brittle.** Counts like "remaining double-nested grid loops ≤ 2" or `grep -c "liveEntityRegistry.snapshot" >= 7` can pass or fail for non-semantic reasons. Useful smoke checks, not sufficient validation.

### Suggestions [merged]

- Fix package/path references before execution: `com.paralife.admission.OutboundSender`, `com.paralife.engine.TickEvent`, actual `RejectionToken` path.
- Make `attemptPlacementForTest` `public`, move tests into `com.paralife.websocket`, or preferably drive real `handleRegister` with mock sessions so the same registration side effects covered.
- Split registry responsibilities: keep `LiveEntityRegistry` as grid-occupant registry for SimulationEngine/EnvironmentEngine, but do not move `TickBroadcaster` off `BotRegistry.getAllBots()` unless you introduce a separate per-session projection that faithfully mirrors `BotRegistry`. **Lowest-risk option: leave TickBroadcaster on `BotRegistry.getAllBots()`.** [R3 emphasis]
- If `TickBroadcaster` must consume `LiveEntityRegistry`, change `EntityEntry.sessionId` from `Optional<String>` to explicit session target model: `List<String> sessionIds` or separate `ControlledEntityView`. Bonded pairs and composites need concrete fan-out/remap semantics.
- Correct lifecycle hook IDs: unregister `BondedPair.id()` when bonded grid occupant dies/transformed; unregister `CompositeMember.id()` when member leaves grid; only unregister primary/secondary IDs from `BotRegistry`. Propagate session IDs after BotRegistry remaps for composite/revert/dissolve paths.
- Add hooks for `WorldWebSocketHandler.cleanupBot` and `cleanupByEntityId` in both `EligibleCellIndex` and `LiveEntityRegistry`, or explicitly prove paths out of scope.
- Strengthen `GoldenTraceEquivalenceTest` with scripted scenario: force adjacent RPS particles, queue move/consume/reproduce actions, trigger bond formation, trigger composite formation/collapse/dissolve, include at least one death. Random sparse placement not enough.
- For golden trace drain, wait for observable quiescence beyond queue depth. Options: add `inFlight` counter to `OutboundSender`, expose test-only "idle" await, or use listener latch with expected frame count per tick.
- In test teardown, detach all outbound sender sessions and unregister sessions to avoid sender VT leakage across full suite.
- Make bond/composite invariant tests mandatory, not optional or disabled.
- Consider bounded lost-race retry: on failed `trySetEntity`, call `notifyChanged(pos)` and retry small fixed number of times. Keep `GRID_FULL` as terminal behavior; retain lost-race metric.
- Publish immutable cell-status snapshot from `EnvironmentEngine`, or avoid reading mutable cache from WS threads.
- Dirty placement index only for structural occupancy/position changes.

### Risk Assessment

**Overall: HIGH [R2+R3].**

SCALE-06 close but placement index needs complete cleanup/mutation hook audit + compile-safe test seam + thread-safe cache reads + better lost-race handling. SCALE-07 has more serious design risk: revised `LiveEntityRegistry` mixes grid-occupant iteration with per-session broadcast identity. Can change externally visible tick delivery even if row-major ordering and golden trace digests otherwise stable. Plan 03's golden trace good framework but current scenario too narrow to catch most likely semantic regressions. Fix session identity model and strengthen trace scenario before allowing Plan 19-04 to land.

---

## OpenCode Review (R2 + R3 merged)

### Per-plan summary [R2 unique]

- **Plan 19-01 (Placement Index):** Well-scoped replacement of 50-retry random scan with dense-array sparse-set (`EligibleCellIndex`). Three eligibility constraints correctly derived from existing per-tick state. Incremental dirty-bbox maintenance (5×5) is right O(1) amortized approach. However plan over-counts grid-mutation sites in `SimulationEngine` (some line numbers point to `applyDeltaToOccupant`, not structural changes) and unnecessarily hooks energy-decay `setEntity` calls that cannot affect placement eligibility. Lock-order Javadoc and lost-race metric good defensive additions.
- **Plan 19-02 (Live Entity Registry):** `LiveEntityRegistry` sparse-set design with row-major `snapshot()` architecturally sound; addresses O(grid) → O(entities) refactor. However plan contains **critical internal contradiction**: mandates `Optional<String> sessionId` on `EntityEntry`, wires composite/bonded entries with `Optional.empty()` in `SimulationEngine`, but simultaneously demands `LiveEntityRegistryInvariantTest` asserting `entry.sessionId()` agrees with `BotRegistry.getSessionForEntity()`. For `CompositeMember`, BotRegistry *does* have a session today (via `updateBotRegistryForFormation` at `SimulationEngine.java:716`), so invariant test would fail under proposed wiring. For `BondedPair`, BotRegistry does *not* have a session today, but existing `TickBroadcaster` still sends perception frames to predator session via stale BotRegistry entry — behavior plan would inadvertently drop.
- **Plan 19-03 (Golden Trace Equivalence):** Per-session SHA-256 digest map correct abstraction to neutralise cross-session VT scheduling jitter. Generate-if-missing JSON resource workflow eliminates manual hex-copying pain. Explicit `registeredSessionIds` for drain-await fixes vacuous-truth race from prior review. Main weakness: 10-bot, 50-tick scenario doesn't drive actions (no movement, combat, bonding, composite dynamics), so gate verifies equivalence for mostly static decay-and-death workload only.
- **Plan 19-04 (Entity List Iteration):** Refactor list for `SimulationEngine` (7 in-scope grid scans) and `TickBroadcaster` accurate; decision to preserve nutrient spawn as grid-walk correct. Row-major sort shim in `snapshot()` pragmatic compatibility measure. Plan's expectation that `GoldenTraceEquivalenceTest` will pass on first execution is **optimistic** given unresolved BondedPair perception gap from Plan 19-02; if seeded scenario triggers bonding, per-session digest will diverge.

### R3 framing
Four plans contain solid data-structure choices and well-designed test infrastructure. However **three Round 2 blockers remain unaddressed**: BondedPair/CompositeMember session identity wired brokenly end-to-end, golden-trace oracle too narrow, drain-race unfixed. Additionally, **new HIGH `cellStatusCache` thread-safety issue** has not been addressed. Until resolved, Plans 02–04 are not safe to execute.

### Strengths [R2+R3 merged]
- **Dense-array sparse-set pattern** (`EligibleCellIndex`, `LiveEntityRegistry`) right structure for O(1) add/remove/sample on 256×256 + ≤256 entities. No unnecessary segment trees or quad trees.
- **Per-session digest map** (Plan 19-03) correctly sidesteps non-deterministic cross-session emit order.
- **Lock-order documentation** (Plan 19-01, REVIEWS L1) explicitly states `index-monitor → grid-read-lock`, preventing deadlock regressions.
- **Generate-if-missing baseline** (Plan 19-03, REVIEWS MED-1) superior workflow to embedding hex literals.
- **Ctor cascade enumeration** (REVIEWS MED-2) deterministic grep + `compileTestJava` gate, only reliable way to catch missed manual instantiations.
- **`@DependsOn("rockGenerator")`** on `EligibleCellIndex` (REVIEWS H2) correctly sequences post-rock initialisation without polluting `WorldGrid`.
- **Out-of-scope discipline** well maintained: nutrient spawn, spatial diffusion CA, parallel perception broadcast all correctly deferred.
- **Wave sequencing 01→02→03→04** sound; pinning EXPECTED_DIGESTS before Plan 04 closes self-agreement loophole.

### Concerns

#### HIGH

**[H1] CONSENSUS-H1 [R2+R3] — `EntityEntry.sessionId` wiring contradicts existing BotRegistry behavior and breaks BondedPair perception.**

- **CompositeMembers** already have a session in `BotRegistry` today (`SimulationEngine.java:716-720`, `updateBotRegistryForFormation`). Plan wires them with `Optional.empty()` (`Plan 19-02, Task 2, step 3(c)`), causing `LiveEntityRegistryInvariantTest` assertion (b) to fail immediately.
- **BondedPairs** are *not* registered in `BotRegistry` today (`processInteractions` at `SimulationEngine.java:589-590` does `setEntity`/`clearEntity` but never updates `BotRegistry`). However existing `TickBroadcaster` iterates `BotRegistry.getAllBots()`, so predator's stale `BotState` still drives a `buildTickFrame` call for the BondedPair occupant. Plan's `TickBroadcaster` refactor (`Plan 19-04, Task 2`) skips `sessionId.isEmpty()` entries entirely, meaning predator session would stop receiving frames for the BondedPair — direct observable behavioural change `GoldenTraceEquivalenceTest` would detect if bonding occurs.
- **R2-detail on member1 vs member2:** Plan 19-02 wires `Optional.empty()` for both member1 and member2 even though only member1 is bound to primary's session (member2 is not registered in BotRegistry today). The fix should preserve that asymmetry.

**Suggested fix:** Wire `sessionId` from `botRegistry.getSessionForEntity(entityId)` at every `register` call, or at minimum preserve predator session for BondedPairs by looking it up during bond formation. Don't blanket-assign `Optional.empty()` to server-internal creations with existing session bindings.

**[H2] CONSENSUS-H2 [R2+R3] — Golden-trace scenario too narrow to catch semantic regressions.**

Plan 19-03 drives **10 bots on 32×32 (~1% density) for 50 ticks with no queued actions**. Bond formation requires adjacency + energy threshold + probability roll; statistically unlikely at ~1% density within 50 ticks. ActionResolver (movement, combat, consume, reproduce) completely unexercised. Composite/collapse/dissolve paths not driven. EXPECTED_DIGESTS pinned at this workload provides false confidence; highest-risk refactors verified only against static decay-and-death workload.

**[H3-line] Plan 19-01: Line number references for `SimulationEngine` mutation sites are inaccurate (R2+R3).**
Plan cites `collapseToMember` at "lines 695/698/701" (`Plan 19-01, Task 2, step 5`). In current codebase those lines are inside `applyDeltaToOccupant` (`SimulationEngine.java:695-701`), generic energy-delta helper, not structural collapse. Actual structural methods are `revertToBondedPair` (line ~1051) and `dissolveToParticles` (line ~1098). Plan's line-number table conflates `applyDeltaToOccupant` with structural entity transformations. Executor blindly following table might hook `notifyChanged` inside `applyDeltaToOccupant` (harmless but unnecessary) while missing actual structural sites.

**[H3-drain] CONSENSUS-H3 [R3] — Drain race in `awaitAllSessionQueuesDrained` not fixed.**
Plan 19-03 Task 2 still polls `outboundSender.queueDepth(sid) == 0` only. Sender VT dequeues frame *before* entering `synchronized(session)` and calling `sendMessage`. `queueDepth` hits 0 while `listener.onEmit` still in-flight. Test thread captures digest prematurely, producing flaky cross-run mismatches. R2 fix (post-loop `synchronized(session)` barrier) absent from plan text.

**[H4] CONSENSUS-H4 [R3 explicit; R2 silent] — `cellStatusCache` thread-safety: ConcurrentModificationException risk.**
Plan 19-01 `EligibleCellIndex.notifyChanged` (called from WS inbound thread during registration) reads `environmentEngine.cellStatusCacheView()`. `buildStatusCaches()` runs on tick thread and mutates underlying `HashMap` (clear + put). Concurrent read from WS thread violates `HashMap`'s concurrency contract → `ConcurrentModificationException`. Only Gemini flagged in R2; not resolved.

**[Plan 19-04: Expected green outcome for `GoldenTraceEquivalenceTest` is at risk due to Plan 19-02 sessionId gap (R2+R3).]**
Plan states: "The expected outcome on first execution is GREEN." Assumes row-major sort is only cross-cut variable. If 10-bot scenario triggers bonding, `TickBroadcaster` will skip BondedPair entry, predator session's digest will change, test will fail. STOP/escalate guard good defense-in-depth, but plan shouldn't treat as unlikely.

#### MEDIUM

**[M1] [R2+R3] Unnecessary `notifyChanged` hooks on pure energy updates.** Plan requires hooks at `SimulationEngine.java:756`/`:783` (`processEnergyDecay` `setEntity` calls). Energy decay doesn't change occupancy, position, or neighbour counts. 5×5 bbox re-eval (25 cells × 8 neighbour checks = 200 grid reads) for zero placement-eligibility effect. At 1000 entities, ~200k redundant grid reads/tick.

**[M2] [R2+R3] `attemptPlacementForTest` package-private access won't compile from `com.paralife.engine`.** Plan declares seam package-private in `WorldWebSocketHandler` (`com.paralife.websocket`). `PlacementDeterminismTest` and `GoldenTraceEquivalenceTest` live in `com.paralife.engine`. Compile error.

**[M3] [R2+R3] OutboundSender / TickEvent package paths may be wrong.** Plan 19-03 references `com.paralife.websocket.OutboundSender` and `com.paralife.websocket.TickEvent`. Codex flagged. Actual classes: `com.paralife.admission.OutboundSender`, `com.paralife.engine.TickEvent`. Pre-flight signature check (REVIEWS MED-4) prescribed for `attachSession`/`register` but not for class packages themselves.

**[M4] [R2+R3] LiveEntityRegistryInvariantTest allows bond/composite scenarios `@Disabled`.** Plan 19-02 Task 3: "Tests that are too elaborate ... can be marked `@Disabled`." Exactly the scenarios that catch H1. Mandatory tests (`AtRest`, `AfterDeath`, `sessionIdAgreesWithBotRegistry`) don't exercise bond/composite state transitions.

**[M5] [R2+R3] Cross-run cleanup missing in Plan 19-03 `resetAll()`.** `tearDown()` clears `frameEmitListener`, but `resetAll()` doesn't detach outbound sender sessions or unregister from `SessionRegistry`. Reusing same sessionIds in Run 2 of dual-run test may leave stale sender VTs, queues, or registry entries from Run 1.

**[M6] [R2+R3] `LiveEntityRegistry.register()` silently drops conflicting sessionId.** Method returns early on duplicate entityId, preserving first `sessionId`. If entity ever re-registered with different session (e.g., composite member remap), new session silently ignored. Should be hard failure (`IllegalStateException`).

**[M7 — R2 unique] `snapshot()` sorts O(N log N) every tick without performance evidence.** Row-major sort is compatibility shim. At N=256, cost negligible. At N=1000 (Phase 21 target), ~10 µs. Plan doesn't quantify or provide fallback. Not a blocker but Javadoc should acknowledge cost ceiling and note Phase 21 may revisit.

**[R2 — constraint-3 read perf] Plan 19-01 Constraint-3 evaluation reads `worldGrid.getNeighbors` under index monitor.** `evaluateEligibility` acquires `synchronized(this)` then calls `worldGrid.getNeighbors` → `worldGrid.getCell` → `ReentrantReadWriteLock.readLock()`. Documented lock order respected. However `evaluateEligibility` does 8+8=16 grid reads per candidate cell inside monitor, serialising concurrent `sample()` calls. At high registration churn, could briefly stall WS thread. Unlikely to matter at 256 entities but worth noting.

#### LOW

**[L1 — R2 unique] Plan 19-01 `cellStatusCacheView()` hoisted once per `notifyChanged`, but `evaluateEligibility` creates new `Position` per cache lookup.** `Byte status = cellStatusCache.get(new Position(x, y))` allocates Position per cell in 5×5 bbox = 25 allocations per event. Minor GC pressure at 1000+ events/tick.

**[L2 — R2 unique] Plan 19-02 `concurrentRegisterIsSafe` unit test uses `CountDownLatch` but production `register` is `synchronized`.** Test valid but tests JVM monitor contention, not lock-free concurrency. Fine as regression guard but doesn't prove scalability.

**[L3 — R2 unique] Plan 19-03 `awaitAllSessionQueuesDrained` spins with `Thread.sleep(1)` and 2-second timeout.** In heavily loaded CI runner, 2s might be tight if VT scheduler starved. Consider configurable timeout via test property.

**[L4] [R3] Plan 19-04 overstates first-run guarantee.** "Expected outcome on first execution is GREEN" — given H1 unfixed, not safe. STOP/escalate guard is defence-in-depth, but plan language treats as unlikely.

**[L5] [R2+R3] OutboundSender listener catch uses `Throwable`.** Should be `Exception`.

### Suggestions [merged]

1. **Fix BondedPair/CompositeMember sessionId wiring before Plan 19-04 lands.**
   - In `SimulationEngine.processInteractions` (bond formation), look up predator's session from `botRegistry` and pass to `liveEntityRegistry.register(bondedPair.id(), ..., Optional.of(predatorSessionId))`.
   - In `SimulationEngine.updateBotRegistryForFormation`, pass same session to `liveEntityRegistry.register(newMemberId, ..., Optional.of(sessionId))` for member1 (and optionally member2 if you decide member2 should also have a session, though today's code doesn't register member2 in BotRegistry).
   - Update `TickBroadcaster` to use `entry.sessionId()` directly, but ensure `isEmpty()` truly means "no session" (reproduce children, bonus offspring), not "we forgot to wire it".
   - **Alternatively, exclude TickBroadcaster from Plan 04 entirely** (keep it on `botRegistry.getAllBots()`). Zero-risk and still achieves SCALE-07 for simulation core. Defer TickBroadcaster migration until session model fully reconciled.
2. **[R2 unique — panic-zone hooks] Add `LiveEntityRegistry` hooks to `checkPanicZone` fallback paths explicitly.** `SimulationEngine.java:1127` and `:1136` (inside `checkPanicZone`) call `botRegistry.unregisterByEntity(memberId)` without corresponding `liveEntityRegistry.unregister`. Plan's grep count likely includes them but task instructions don't explicitly mention `checkPanicZone`. Add to avoid stale entries when composite pool hits zero.
3. **Remove `notifyChanged` from `applyDeltaToOccupant` and energy-decay paths.** Only structural placement/clear events should dirty the index.
4. **Expand golden-trace scenario.** After placing bots, queue deterministic `ActionFrame` (e.g., all bots move fixed direction) and assert digest includes the move. Catches `ActionResolver` and `LiveEntityRegistry.updatePosition` regressions. Increase density to ≥30 bots on 16×16 (~12%) or 50 bots on 32×32 (~5%); run 200+ ticks. Place predator+prey adjacent before tick loop to force at least one bond-formation.
5. **[R2 unique — row-major Javadoc TODO] Document row-major sort as "Plan 19 compatibility shim" in `LiveEntityRegistry` Javadoc.** Add TODO or `@deprecated` note indicating Phase 21 benchmarks may justify removing sort in favour of insertion-order or spatial-hashed iteration.
6. **[R2 unique — rectangular grid test] Verify `EligibleCellIndex` initialisation on non-square grids.** Linearisation `x * height + y` correct for current `GridConfig`, but if grid ever becomes rectangular with `width != height`, ensure `toIndex` and `fromIndex` use same formula. Add unit test for 8×16 grid to lock this.
7. **Fix H3 (drain race).** After `queueDepth == 0` loop, second loop with `synchronized(s) {}` per registered session.
8. **Fix H4 (cache thread-safety).** Change `EnvironmentEngine.cellStatusCache` to `ConcurrentHashMap` (safe for concurrent read + single-writer) or publish immutable `Map.copyOf(cache)` at end of `buildStatusCaches()` and return that from `cellStatusCacheView()`.
9. **Make bond/composite invariant tests mandatory** in Plan 19-02. Remove `@Disabled` escape for `registryMatchesGridOccupantsAfterBondFormation` and `sessionIdAgreesWithBotRegistry` post-bond/post-composite variants.
10. **Fix M2 (package access).** Make `attemptPlacementForTest` `public`, or move tests into `com.paralife.websocket`.
11. **Add class-path pre-flight** for Plan 19-03: verify actual FQN of `OutboundSender` and `TickEvent` with one-line `grep` before writing imports.
12. **Add session cleanup** to Plan 19-03 `resetAll()` (detachSession + unregister per session id).
13. **Harden `register()`** in `LiveEntityRegistry` to throw `IllegalStateException` on re-register with different `sessionId`.

### Risk Assessment

**Overall: MEDIUM-HIGH [R2] / HIGH [R3].** Core data structures sound, test infrastructure well-designed. But Plan 19-02 has critical blind spot around `sessionId` wiring directly threatening D-10 semantic-equivalence guarantee. If executed as written: `TickBroadcaster` skips BondedPair perception frames; `LiveEntityRegistryInvariantTest` fails for CompositeMembers; Plan 19-04 first-run-green expectation not achievable without resolving sessionId model first. Plan 19-01 line-number references inaccurate.

**Mitigation path:**
1. Hold Plan 19-04 until Plan 19-02 amended to correctly wire `sessionId` for all grid-occupants with BotRegistry bindings.
2. Re-pin golden-trace baseline only after amended Plan 19-02 passes invariant test and bonding-enabled scenario added.
3. With those fixes, residual risk drops to **MEDIUM** (minor perf overhead + narrow trace).

**Recommendation [R3]:** Do **not** execute Plans 02–04 until H1–H3 resolved in plan text. Fastest mitigator: keep TickBroadcaster on BotRegistry for Plan 04 — drops largest risk immediately and still satisfies SCALE-07 for simulation core. If taken, risk drops to **MEDIUM** pending H2/H3 fixes.

---

## Unified Consensus (Rounds 2 & 3)

### Trajectory
- R2 raised CONSENSUS-H1/H2/H3 + several MED/LOW. Plans not re-revised between rounds.
- R3 confirmed all three blockers still present in plan body (only acknowledged in commentary). R3 also surfaced H4/H5/H6 as new compile/runtime blockers — H4 (cellStatusCache) was a Gemini-only R2 finding promoted to 3/4 consensus; H5 (package paths) and H6 (visibility) were Codex-only R2 HIGH findings now reaching 2/4 explicit consensus + a third reviewer's MEDIUM.
- Architectural shape (sparse-set, row-major sort, per-session digests, generate-if-missing baseline, lock-order Javadoc) sound across both rounds.

### Blockers (4/4 R2 + R3 unless noted)

- **CONSENSUS-H1 [4/4 R2+R3]** — BondedPair/CompositeMember sessionId still `Optional.empty()`. Two viable fixes:
  - **Option A (registry-as-truth):** look up `botRegistry.getSessionForEntity(predator.entityId())` BEFORE unregister; pass `Optional.of(predatorSession)` to `liveEntityRegistry.register(bondedPair.id(), ...)`. Symmetric for composite via `updateBotRegistryForFormation` order.
  - **Option B (defer TickBroadcaster):** drop TickBroadcaster from Plan 04 entirely. Keep `botRegistry.getAllBots()` iteration (already O(bots)). LiveEntityRegistry remains internal grid-occupant registry for SimulationEngine/EnvironmentEngine only.

- **CONSENSUS-H2 [4/4 R2 implicit/explicit + R3]** — Golden-trace scenario too sparse (10 bots × 32×32 × 50 ticks ≈ 1%). Fix: 30 bots × 16×16 × 200 ticks + scripted adjacent-RPS placement and queued action frames.

- **CONSENSUS-H3 [R2 gemini+codex; R3 4/4]** — `awaitAllSessionQueuesDrained` queueDepth-only race. Fix: post-loop `synchronized(session) {}` barrier per session OR `OutboundSender.inFlight(sid)` counter.

- **CONSENSUS-H4 [R3 3/4 (claude, codex, opencode); R2 gemini-only]** — `cellStatusCache` HashMap concurrent read/mutate race. Fix: `ConcurrentHashMap` OR publish `Map.copyOf` to `volatile` at end of `buildStatusCaches`.

- **CONSENSUS-H5 [R2+R3, codex full both rounds; R3 claude HIGH; R3 opencode MED M3]** — Wrong package paths. `com.paralife.admission.OutboundSender` not `websocket`; `com.paralife.engine.TickEvent` not `websocket`.

- **CONSENSUS-H6 [R2+R3, codex full both rounds; R3 claude HIGH; R3 opencode MED M2]** — `attemptPlacementForTest` package-private from `com.paralife.websocket`, called from `com.paralife.engine` tests. Fix: `public` modifier OR move tests.

### MEDIUM consensus

- **Energy-only `notifyChanged` over-hooking (4/4 R2+R3).** Plan 01 hooks `notifyChanged` at energy-decay / `applyDeltaToOccupant` / damage sites. Energy updates don't change occupancy or neighbor count → 5×5 bbox re-eval is pure waste (~100–200k redundant grid reads/tick at scale). Acceptance grep `>= 12` actively encourages keeping these.
- **Line-number hallucinations (3/4 R3 + R2 opencode).** `collapseToMember` cited at lines 695/698/701 but those are inside `applyDeltaToOccupant` (energy-delta helper); no `collapseToMember` exists in current SimulationEngine. Real structural sites: `revertToBondedPair` (~1051), `dissolveToParticles` (~1098). Plan 02 also assumes `botRegistry.unregisterByEntity(...)` at lines 719/725 (actually `emptyNeighbors` / `individualEnergy` calc inside `attemptCompositeFormation`).
- **`LiveEntityRegistry.register()` silently drops conflicting sessionId (3/4 R2+R3).** Make it throw `IllegalStateException`.
- **`LiveEntityRegistryInvariantTest` allows bond/composite scenarios `@Disabled` (3/4 R2+R3).** Make mandatory — exactly the scenarios that catch H1/H2 cheaply.
- **Plan 03 `resetAll()` missing `outboundSender.detachSession` + `sessionRegistry.unregister` (3/4 R2+R3).** Reused sessionIds across two `driveScenario` runs risk duplicate sender VTs / digest corruption.
- **Codex MED [R2 unique] — `cleanupByEntityId` / `cleanupBot` lifecycle hooks.** Resume-token expiry / disconnect paths clear grid cells but plan doesn't add placement-index + live-registry hooks. Carried in R3 within H1-extension framing.
- **Codex MED [R2 unique] — back-compat ctors forwarding `null` for `EligibleCellIndex` after retry-loop deletion → NPE risk** if any legacy direct-instantiation test exercises registration.
- **Plan 04 task 2 misleading TickBroadcaster comment (claude R2+R3).** Claims pre-refactor key is entityId; actually `BotState.sessionId` from BotRegistry (BotState lingers post-bond). "Minimum-viable refactor: skip" is behavior change, not equivalence. Misleads executor into accepting H1 divergence.
- **Position record allocation per cache lookup (R3, originally OpenCode L R2).** ~25 allocations/event minor GC pressure.

### LOW consensus

- **Catch `Throwable` → `Exception` in OutboundSender listener (R2+R3).** Swallows OOM/StackOverflow.
- **Grep-based acceptance gates brittle (R2+R3).** Counts like `>= 8` for ActionResolver hooks let 5 sites silently slip; tighten to exact line list.
- **Lost-race false GRID_FULL under concurrent registrations.** R2 codex MED, R3 claude L4. Two sessions sample same cell → one gets GRID_FULL despite many empty cells. Metric helps observe but not prevent. Defensive retry-on-loss before declaring `GRID_FULL`.

### Divergent / R2-unique items worth preserving

- **TickBroadcaster scope decision:**
  - Gemini (R2) favours pure removal (Option B): keep `botRegistry.getAllBots()` because already O(bots), no SCALE-07 win to be had.
  - Claude / OpenCode favour sessionId fix (Option A): TickBroadcaster CAN consume `LiveEntityRegistry` if sessionId wiring is fixed correctly, preserving registry-as-single-iteration-source-of-truth.
  - Codex (R2) hybrid: split responsibilities (LiveEntityRegistry for engines; BotRegistry for broadcaster) OR `List<String> sessionIds` / separate `ControlledEntityView` with explicit fan-out semantics.
  - Both close H1; Gemini's pure-removal lowest-risk; Claude/OpenCode sessionId-fix cleaner long-term shape.
- **OpenCode [R2 unique] — rectangular grid (`width != height`) unit test for `EligibleCellIndex` linearisation.** Lock formula now while it's simple.
- **OpenCode [R2 unique] — row-major sort Javadoc as "Plan 19 compatibility shim" with Phase 21 revisit TODO.**
- **Claude [R2 unique] suggestion 2 — `checkPanicZone` fallback `liveEntityRegistry.unregister` at SimEngine 1127/1136.** Avoid stale entries when composite pool hits zero.
- **Claude [R2 unique] L5 — `TickEvent` ctor signature pre-flight verify.** If TickEvent requires more fields than `(int tick)`, test won't compile.
- **Codex [R2 unique] L — row-major name imprecision.** Current scan is `for x` outer, `for y` inner; `x * height + y` matches that order. More "x-major" than conventional row-major; compatibility behavior right.

### Recommendation (unanimous across rounds)

**Do NOT execute Plans 02–04.** Re-revise to actually encode the round 2 + round 3 consensus fixes in plan body (not just commentary). Two acceptable resolution paths for H1:

- **Option A — registry-as-truth.** At bond formation, look up `botRegistry.getSessionForEntity(predator.entityId())` BEFORE unregister; pass `Optional.of(predatorSession)` to `liveEntityRegistry.register(bondedPair.id(), ...)`. Symmetric for composite.
- **Option B — defer TickBroadcaster.** Drop TickBroadcaster from Plan 04 entirely. Keep `botRegistry.getAllBots()` iteration. LiveEntityRegistry remains internal grid-occupant registry. Migrate TickBroadcaster in later phase.

Plan 01 mostly executable after H4 (thread-safety) + H6 (visibility) + line-number correction + drop-energy-hooks. If H1+H2+H3 fixed, residual risk **MEDIUM**; with all M-tier fixes, **LOW**.

#### Minimum fix set
1. **CONSENSUS-H1** — pick Option A or B; encode in Plan 19-02 + 19-04.
2. **CONSENSUS-H2** — tighten golden-trace scenario in Plan 19-03 (density + ticks + deterministic actions).
3. **CONSENSUS-H3** — post-drain `synchronized(session)` barrier in `awaitAllSessionQueuesDrained` (or `inFlight` counter).
4. **CONSENSUS-H4** — `ConcurrentHashMap` or `Map.copyOf` swap for `cellStatusCache`.
5. **CONSENSUS-H5** — fix `OutboundSender` and `TickEvent` package paths in Plan 02 + Plan 03.
6. **CONSENSUS-H6** — `public` modifier on `attemptPlacementForTest` (or move tests to `com.paralife.websocket`).
7. **MEDIUM** — scope-narrow `notifyChanged` to structural changes only; correct line numbers; add `cleanupBot` / `cleanupByEntityId` / `checkPanicZone` hooks; drop `@Disabled` escape on bond/composite invariant scenarios; add `outboundSender.detachSession` + `sessionRegistry.unregister` in `resetAll()`; throw on conflicting `register()`; rectangular-grid test; row-major Javadoc shim note.

If items 1–6 resolved, Plans 02–04 safe to execute. Items 7 are quality-of-execution improvements that prevent late-surface bugs.
