---
phase: 19
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-04-30T20:26:43Z
plans_reviewed: [19-01-placement-index-PLAN.md, 19-02-live-entity-registry-PLAN.md, 19-03-golden-trace-equivalence-PLAN.md, 19-04-entity-list-iteration-PLAN.md]
models:
  gemini: gemini-3.1-pro-preview
  claude: opus
  codex: gpt-5.5 (reasoning_effort=high)
  opencode: openrouter/moonshotai/kimi-k2.6
---

# Cross-AI Plan Review — Phase 19

## Gemini Review

# Phase 19 Plan Review

## Summary
The four-plan sequence provides a highly disciplined, wave-based approach to solving SCALE-06 and SCALE-07. The separation of state structures (`EligibleCellIndex`, `LiveEntityRegistry`) from their eventual consumption is excellent, and inserting the `GoldenTraceEquivalenceTest` gate (Plan 03) before the risky refactor (Plan 04) is the correct architectural safety net. However, the sequence contains a fatal logic flaw regarding semantic equivalence in Plan 04, and a race condition in the test harness in Plan 03, which will guarantee failure during execution.

## Strengths
- **Excellent sequencing:** Pinning the `EXPECTED_DIGESTS` baseline in Plan 03 before mutating the tick pipeline in Plan 04 perfectly avoids the "refactor agrees with itself" trap.
- **Appropriate data structures:** `EligibleCellIndex` and `LiveEntityRegistry` correctly utilize O(1) sparse-set backing structures without introducing heavy dependencies.
- **Strict concurrency hygiene:** The explicit ban on `parallelStream` preserves the single-threaded mutation invariant, preventing subtle thread-safety regressions.
- **Comprehensive lifecycle hooks:** Plan 02 exhaustively identifies all entity ID mutation sites (bonding, composite formation/dissolve, rigid-body movement) to keep the registry accurate.

## Concerns

- **HIGH — Plan 04 conflates determinism with semantic equivalence.** The pre-refactor `SimulationEngine` processes entities and populates lists for `Collections.shuffle` using a row-major `(x, then y)` grid scan order. Plan 04 mandates using `LiveEntityRegistry.snapshot()` (which is ordered by `entityId`) and strictly forbids reordering. While `entityId` order is *deterministic*, it changes the pre-shuffle input order. Java's `Collections.shuffle` output depends on input order, meaning combat resolution and RNG consumption sequences (e.g., in `processDeaths` composite dissolution) will diverge from the baseline. This will definitively break the `GoldenTraceEquivalenceTest`.
- **HIGH — Plan 03 `awaitAllSessionQueuesDrained()` contains a vacuous truth race condition.** The helper iterates over `capture.sessionsSeen()`. If this check runs immediately after a tick—before the virtual threads have processed the queue and called `onEmit`—`sessionsSeen()` will be empty. The loop will skip, `allDrained` will remain true, and the test will fire the next tick immediately. This will cause runaway ticks, dropped frames, and flaky digests.
- **MEDIUM — Lock ordering risk in `EligibleCellIndex`.** `notifyChanged` holds `synchronized(this)` while calling `worldGrid.getCell()`, which acquires the grid's read lock. This establishes an `Index Monitor -> Grid Read Lock` order. While not deadlocking currently, it is a hidden hazard that future maintainers could trip over.
- **MEDIUM — Fragile fallback constructors.** Plans 01 and 02 both extend `WorldWebSocketHandler` and `SimulationEngine` constructors. Meticulously updating all legacy 6-arg/7-arg test constructors to forward `null`s for the new `EligibleCellIndex` and `LiveEntityRegistry` parameters is highly prone to compilation breaks during execution.

## Suggestions

1. **Plan 04 MUST Preserve Row-Major Order for Physics:** To pass the semantic equivalence gate, any list in `SimulationEngine` that replaces a grid scan and feeds into `Collections.shuffle` (or consumes `simRng` in loops like `processDeaths`) MUST be sorted by `x` ascending, `y` ascending before processing. You must explicitly authorize the executor to apply this row-major sort to perfectly emulate the legacy grid scan order. (Note: `TickBroadcaster` does *not* need this sort, as per-session hashing handles its non-determinism).
2. **Fix Plan 03 Drain Check:** Change `awaitAllSessionQueuesDrained()` to iterate over a known list of expected sessions (e.g., `sessionRegistry.getActiveSessions()`) rather than `capture.sessionsSeen()`.
3. **Locking Hygiene in Plan 01:** Document the `Index -> Grid` lock ordering in `EligibleCellIndex`, or evaluate the eligibility constraints using a grid read outside of the `synchronized` block.
4. **Explicit Constructor Updates:** Ensure Plan 02 explicitly lists all fallback constructors in `WorldWebSocketHandler` and `SimulationEngine` that need to be updated to accommodate the new `LiveEntityRegistry` parameter alongside the `EligibleCellIndex` from Plan 01.

## Risk Assessment
**HIGH**. While the architecture and wave strategy are exceptionally strong, Plan 04 is logically trapped. It requires passing a strict byte-for-byte semantic equivalence test against a legacy baseline, but actively forbids preserving the row-major ordering necessary to achieve that exact physics equivalence. The agent will fail the gate and be unable to proceed without operator intervention. Implementing the row-major sort suggestion for `SimulationEngine` and fixing the test harness race condition lowers this risk to LOW.

---

## Claude Review

# Phase 19 Plan Review (Caveman)

## Summary

Plans tight after replan. Reviews-driven fixes folded in well: H1 lifecycle hooks complete, H2 @DependsOn locked, H3 entity-id sites enumerated, H4 vacuous-baseline guards plus per-session digests, H5 explicit TickEvent driver, M3 sort-baked-in, M4/M5 grep tightened, M6 back-compat ctor patched. Main residual risk = Codex H "row-major vs sort-by-entityId pre-shuffle order changes simulation outcomes". Plan 04 acknowledges + STOP/escalate path documented but cross-cut digest divergence likely on first run. Otherwise plans executable.

## Strengths

- Wave sequencing 01→02→03→04 correct. EXPECTED_DIGESTS pinned mid-flight (post-02/pre-04) closes self-agreement loophole.
- H1 closed thoroughly. Grep counters (≥12 in SimulationEngine, ≥8 ActionResolver, ≥2 DeathFinalizer) enforce coverage.
- H3 entity-id site audit explicit per-line: bond-formation, composite-formation/collapse/dissolve/revert, reproduce, executeCompositeMovement. Acceptance ≥14 grep gate.
- H4/L3 vacuous-baseline guards triple-belt: emitCount > 0, map non-empty, no per-session hex == EMPTY_SHA256_HEX.
- M3 sort-by-entityId baked into `snapshot()` not reactive in TickBroadcaster. Removes Plan 04 reactive-sort surface area.
- M4 shuffle count `== 3` (was `>= 3`). M5 remaining loops `<= 2` (was `<= 4`). RNG-regression silently-passing closed.
- Plan 04 W4 explicit "STOP and escalate" on EXPECTED_DIGESTS mismatch. No self-healing re-pin.
- @DependsOn("rockGenerator") on EligibleCellIndex closes H2 sibling-@PostConstruct race.
- Plan 03 listener fires inside `synchronized(session)` post-sendMessage. Captures actual wire bytes. try/catch wraps listener so test bug can't kill drain VT.
- DeathFinalizer back-compat ctor in SimulationEngine 9-arg/13-arg both addressed (M6).

## Concerns

### HIGH

**[H1] Plan 04 pre-shuffle-order divergence likely produces refactor-cut digest mismatch.**
Pre-Plan-04: row-major scan → list in row-major (x*H+y) order → shuffled. Post-Plan-04: snapshot in sort-by-entityId order → shuffled. `Collections.shuffle(list, seededRng)` with same seed but different input permutation produces DIFFERENT output permutation. Different shuffle output → different combat pairing order → different death events → different perception frames per session → per-session digest diverges.

Plan 04 acknowledges this and says "STOP and escalate" but treats it as edge case. Reality: it's the EXPECTED outcome unless registration happens in row-major order coincidentally. This means Plan 04's first run will almost certainly red on EXPECTED_DIGESTS, blocking the wave with no automated recovery path. Operator burden.

**Fix options (pick before executing):**
1. **Sort snapshot by `(x, y)` row-major instead of entityId.** snapshot() returns list sorted by `Comparator.comparingInt(EntityEntry::position().x()).thenComparingInt(...y())`. Matches pre-refactor row-major iteration → shuffle input identical → digest stable. This is what Codex review suggested. Drop M3 entityId-sort if you take this path.
2. **Re-pin EXPECTED_DIGESTS as part of Plan 04** with explicit operator-approval gate documented. Less ideal but pragmatic.
3. **Run Plan 04 first against pre-shuffle, capture the new digest, accept post-Plan-04 baseline as canonical.** Same as 2 with different framing.

Plan 04 currently chose option 2-via-escalation. Risk = Plan 04 stalls indefinitely. Recommend option 1.

**[H2] Plan 03 EXPECTED_DIGESTS pinning instability across CI machines/JVM versions.**
SHA-256 over outbound frame bytes assumes byte-perfect reproducibility. Two risks:
- `WebSocketSession.getId()` is Jetty-assigned, may differ across runs even at fixed seed → per-session map keys diverge across CI runs → mapA != EXPECTED_DIGESTS even with no refactor change.
- HashMap iteration in any Frame encoding (e.g. cell-entry construction) could leak instability.

Plan 03 driveScenario uses synthetic `trace-sess-N` but `sessionRegistry.register(sessionId, mockSession)` may be incompatible with the existing `SessionRegistry.register(WebSocketSession session)` signature (line 31 takes session, derives id internally via `session.getId()`). Mock returns "trace-sess-N" via `when(session.getId()).thenReturn(sessionId)` so should work IF registry honors `session.getId()` not its own id source. Verify.

Bigger risk: `OutboundSender.attachSession(sessionId, session)` — re-grep, signature uncertain. If method takes only `WebSocketSession` and derives id via `getId()`, mock plumbing OK. If signature differs, test won't compile.

**Fix:** Plan 03 Task 2 must verify these signatures BEFORE writing the test. If `OutboundSender.attachSession(WebSocketSession, int queueSize)` is the actual signature (confirmed at WorldWebSocketHandler line 223: `outboundSender.attachSession(session, admissionConfig.backpressure().outboundQueueSize())`), the test must use that.

**[H3] Plan 03 `awaitAllSessionQueuesDrained` race against TickBroadcaster.onTick.**
Driver loop:
```java
applicationEventPublisher.publishEvent(new TickEvent(t));
awaitAllSessionQueuesDrained();
```
publishEvent is synchronous — all @Order handlers fire on the calling thread before publishEvent returns. By time we reach awaitAllSessionQueuesDrained, all `outboundSender.offer(...)` calls already complete. Drain VTs run async. So far OK.

But `capture.sessionsSeen()` is populated AS frames drain. First tick: queues populate before `sessionsSeen` does. Loop `for (String sid : capture.sessionsSeen())` may be empty when first checked → loop returns "all drained" prematurely → next tick fires before previous tick's frames finish draining → frame ordering across ticks intermixes → digest non-deterministic.

**Fix:** track sessions registered in driveScenario explicitly, await drain for THOSE session ids, not for `capture.sessionsSeen()`. Or use `outboundSender` to enumerate attached sessions. Or block until `sessionsSeen.size() == 10` AND queue depths zero.

### MEDIUM

**[M1] Plan 02 SimulationEngine ≥14 grep counter undercount.**
Audit:
- bond-formation: 3 ops (unreg pred, unreg prey, reg bp) — 1 site
- composite-formation: 4 ops — 1 site
- collapseToMember: 3 ops × 2 unreg sites (lines 719/725) + register sites (multiple per pos 695/698/701) — could be 5+
- handleMemberDeath line 973: 1
- processDeaths 1127/1136: 2
- revertToBondedPair: 2
- dissolveToParticles: per-member loop — 2N ops, dissolveToParticles iterates all surviving members
- collapseToMember actually doesn't exist in the source — it's `revertToBondedPair` + `dissolveToParticles` per the read

Actual count: depends on dissolveToParticles loop iteration unrolling. ≥14 reasonable LOWER bound but plan should re-audit count post-edit. Risk: passes ≥14 grep with one site missed.

**Fix:** acceptance should enumerate each site by line range, not just count.

**[M2] Plan 01 lost-race fallback emits GRID_FULL even when grid not full.**
Plan 01 step (d): if `trySetEntity` fails after `sample()` returned a position, treat as GRID_FULL. Acceptable per D-05 but creates a wire-shape where client sees GRID_FULL on a half-empty grid (race lost to one concurrent writer). At dense workloads this wire is technically correct (token = same), but operationally noisy.

**Fix accept:** Phase 21 benchmark observation; current plan flags as L1.

**[M3] Plan 02 ActionResolver.resolveMove `entityId` resolution.**
Plan instructs: `liveEntityRegistry.updatePosition(entityId, target)` after setEntity. But actual local in ActionResolver is `ra.bot.entityId()` or `placed.id()` (resolveMove may consume Particle which has its own id). Plan says "Match the actual local variable names" but no specific guidance. Risk: executor picks wrong id source → registry tracks wrong entity.

**Fix:** Plan 02 must specify: use `ra.particle.id()` (or whatever the placed Particle reference is). The Particle id IS the entityId stored in BotRegistry — they're the same string per `BotRegistry.register(sessionId, entityId, position)` where entityId comes from Particle.id().

**[M4] Plan 02 SimulationEngine.processInteractions energy decay sites (lines 756, 783) and overcrowding (886, 888) flagged "occupant identity unchanged → idempotent" for EligibleCellIndex.notifyChanged in Plan 01 — but Plan 02 does NOT hook LiveEntityRegistry.updatePosition there. Correct: occupant moves not happening, only energy changes. updatePosition unnecessary. Just confirming intentional.

**[M5] Plan 03 EXPECTED_DIGESTS uses `Map.of` capped at 10 entries.**
Java's `Map.of(k,v,...)` factory caps at 10 pairs. Test scenario uses 10 bots → 10 sessions → fits exactly. If executor chooses N=10 bots, plan compiles. If they bump to N=11+, must switch to `Map.ofEntries(Map.entry(...), ...)`. Plan mentions this caveat. OK.

**[M6] Plan 04 EnvironmentEngine refactor at lines 894–900 / 924–936 (entity-status writeback inside buildStatusCaches).**
buildStatusCaches runs at @Order(14). Refactoring its inner per-entity passes to iterate snapshot() while the outer pass walks the grid mixes two iteration shapes within the same handler method. Anti-pattern flagged in RESEARCH.md.

The plan calls this out and says "keep grid-walk for shadow-grid bit, entity-status portion uses entity-list". Concrete code surface = 2 different loops within buildStatusCaches. Fine but executor needs to be careful not to merge them or break cache-fill ordering (cell-status and entity-status caches must both be complete by the time @Order(20) ActionResolver reads them).

**Fix:** acceptance should verify buildStatusCaches still produces both caches fully populated post-refactor. The existing EnvironmentDeterminismTest.envOnlyObservablesFireDuringSingleRun catches major breakage. GoldenTraceEquivalenceTest catches subtle. Sufficient if both stay green.

### LOW

**[L1] Plan 01 Task 1 acceptance: `cellStatusCacheView called exactly 1 time per notifyChanged` via Mockito spy.** Mockito.spy on a Spring `@Component` requires either `@SpyBean` or manual instantiation. Plan uses pure-JUnit unit test, not @SpringBootTest, so manual `Mockito.spy(new EnvironmentEngine(...))` — but EnvironmentEngine has many collaborators. Stub instantiation in a unit test will be painful. Consider: skip the spy assertion, rely on code-review of the hoisted variable. Or move the test to integration (@SpringBootTest) where @SpyBean works.

**[L2] Plan 04 Task 2: EnvironmentEngine @Order(14) grep gate.**
`grep -c "@Order(14)" src/main/java/com/paralife/engine/EnvironmentEngine.java >= 1` — pre-refactor file may use `@Order(14)` literal or named constant. If named constant, grep fails. Sanity-check pre-refactor file before adding gate. CLAUDE.md states @Order(14) explicitly so likely literal. Confirm.

**[L3] Plan 03 listener inside synchronized(session) extends critical section.**
CLAUDE.md says monitor only protects sendMessage. Plan acknowledges as accepted. Listener is sub-µs digest update. OK.

**[L4] Plan 03 mock WebSocketSession: `when(session.isOpen()).thenReturn(true)` — does the session ever transition to closed during the 50-tick scenario?** If a bot dies, drainAndBroadcastDeaths fires terminal vD frame, then BotRegistry removes the bot. Session stays "open" because mock returns true forever. Test should not exercise the close path; if it does, mock needs more setup. Plan 03 scenario does NOT seed deaths intentionally, so OK.

**[L5] Plan 02 LiveEntityRegistry stores Position by value in EntityEntry.** Each updatePosition allocates a new EntityEntry record. At 256 entities × 50 ticks × N moves = ~minor GC pressure. Phase 21 benchmark may surface if unexpected. Trivial.

## Suggestions

1. **(HIGH urgency) Resolve pre-shuffle order before executing Plan 04.** Pick one:
   - **Option A (recommended):** change Plan 02 snapshot sort from entityId to row-major `(x, y)`. Matches pre-Plan-04 grid-scan order; shuffle input identical pre/post; EXPECTED_DIGESTS stable. Update REVIEWS M3 fix to "row-major sort", not entityId. Document why: shuffle determinism requires input-order stability across the cut.
   - **Option B:** explicitly accept that Plan 04 will need EXPECTED_DIGESTS re-pinned. Add a Plan 04.5 task: capture new digest post-refactor, operator reviews, re-pins. Honest but slow.

2. **Plan 03 Task 2: verify `OutboundSender.attachSession` and `SessionRegistry.register` signatures BEFORE writing driveScenario.** Add explicit pre-flight grep step. Currently plan has "re-grep for actual method names" comment but no acceptance check. Add: "first action of Task 2: read OutboundSender.java and SessionRegistry.java, document actual signatures, adjust driveScenario accordingly".

3. **Plan 03 awaitAllSessionQueuesDrained: track explicit session list.** Replace `for (String sid : capture.sessionsSeen())` with `for (String sid : registeredSessionIds)` where registeredSessionIds is the list built in Step 1 of driveScenario. Avoids race where sessionsSeen is empty on first tick.

4. **Plan 02 Task 2: enumerate ActionResolver entityId sources explicitly.** Add inline comment in plan: "use `ra.particle.id()` for Particle moves, `member.id()` for composite-member moves" so executor doesn't pick wrong field.

5. **Plan 04 Task 1 acceptance: replace `>= 7` grep with site-specific assertions.** Each refactor target line range should have its own `grep -nA5 "<method-name>" | grep -c "liveEntityRegistry.snapshot"` >= 1 check. Catches one-site-missed regression that aggregate count hides.

6. **Plan 03 EXPECTED_DIGESTS pinning workflow: add CI sanity step.** After pinning, run the test 3 times in a row in the same JVM — all 3 must pass identically. Catches latent JVM-internal non-determinism (HashMap iteration order, etc.) that single-run pinning misses.

7. **Plan 01 lost-race fallback: count it.** Add metric `paralife.placement.lost-race.total` increment in the fallback branch. Lets Phase 21 benchmark observe if races are common (signal for tightening). Cheap.

8. **Plan 02 LiveEntityRegistryTest.snapshotIsSortedByEntityIdAfterRemovals already in plan but consider adding `snapshotEqualsRebuiltSnapshot` test:** insert N entries, remove half, snapshot == manually-built sorted list. Belt-and-braces against swap-and-pop bug.

## Risk Assessment

**Overall: MEDIUM-HIGH.**

Architectural shape sound. H1/H2/H3/H4/H5/M3-M6 review fixes well-folded. Test infrastructure appropriately defensive (per-session digests, vacuous-baseline triple-guard, explicit TickEvent driver, STOP/escalate on cross-cut divergence).

Risk concentrated in Plan 04's Codex-H pre-shuffle-order issue. Plan acknowledges but treats as exceptional. Likely outcome: first execution of Plan 04 fails GoldenTraceEquivalenceTest at the EXPECTED_DIGESTS map assertion. Operator must intervene before wave can proceed. This is correct safety behavior but high friction.

If Suggestion 1 Option A taken (row-major sort in snapshot()), risk drops to LOW: the EXPECTED_DIGESTS contract becomes mechanically stable across the cut, Plan 04 lands cleanly, GoldenTraceEquivalenceTest stays green automatically.

If Option B taken (accept re-pinning), risk stays MEDIUM: extra round-trip but no architectural surprise.

Concurrency invariants preserved across all plans (D-08/D-11 single-threaded mutation, parallelStream banned via grep, VT-per-session outbound respected). Lock ordering documented for EligibleCellIndex (synchronized → grid read-lock; tick threads must not invert).

H1 lifecycle coverage now exhaustive — stale-eligibility / stale-registry traps closed by REVIEWS H1 + H3 + grep counters.

Recommend: pick suggestion 1 Option A before Plan 04 begins. Drops overall risk to LOW.

---

## Codex Review

Based on the supplied artifacts, Phase 19 is well-researched and has the right strategic shape, but the execution plans are still high-risk. The largest issue is semantic equivalence: several plans replace row-major grid iteration and per-session bot iteration with `LiveEntityRegistry.snapshot()` sorted by `entityId`, which is deterministic but not equivalent to the current observable order. The second major risk is lifecycle correctness around composite/bonded entities: the plans blur “grid occupant entity” vs “bot session entity,” which can cause missed removals, missing outbound frames, or stale registry positions.

## Plan 19-01 — Placement Index

**Summary:** Strong plan for replacing retry placement with an indexed eligible set, but lifecycle hook coverage and concurrency behavior are brittle.

**Strengths**
- Correct high-level data structure: dense array + sparse back-map gives O(1) add/remove/sample.
- Good recognition of dirty-bbox radius 2 for the “would cause overcrowding” rule.
- Preserves `E|503|GRID_FULL` wire shape.
- Adds deterministic placement coverage and removes retry storms.
- Explicitly calls out `@DependsOn("rockGenerator")`, stale cache behavior, and lock-order concerns.

**Concerns**
- **HIGH:** `sample()` and `trySetEntity()` are not atomic. A concurrent writer can make `trySetEntity` fail and the handler emits `GRID_FULL` even when many cells remain eligible.
- **HIGH:** Manual `notifyChanged` at every mutation site is very brittle. Future or missed `WorldGrid` mutations will silently stale the index.
- **MEDIUM:** Constraint 3 appears to count only `Particle` and `BondedPair`. If overcrowding applies to `CompositeMember` or other occupied entities, this violates the stated “adjacent occupied cell” rule.
- **MEDIUM:** Constraint 2 relies on potentially stale `cellStatusCache`; the plan documents this, but the acceptance criteria still imply the constraints are strictly enforced.
- **LOW:** The unit-test strategy around spying `EnvironmentEngine` may be cumbersome if that class has heavy constructor dependencies.

**Suggestions**
- Prefer a single placement method such as `eligibleCellIndex.tryPlace(Entity, rng)` that samples, tentatively removes, calls `WorldGrid.trySetEntity`, and updates/retries a small bounded number of times on stale candidates.
- Add a registry/index consistency assertion test that mutates through representative paths and verifies every indexed cell is truly eligible.
- Either centralize mutation notification inside `WorldGrid` via a lightweight listener, or create a single `GridMutationNotifier` wrapper used by all mutation paths.
- Clarify which entity types contribute to overcrowding and use that exact predicate in `EligibleCellIndex`.

**Risk Assessment:** **MEDIUM-HIGH**. The algorithm is right, but the split sample/place operation and hook sprawl are the main correctness risks.

## Plan 19-02 — Live Entity Registry

**Summary:** The registry is a useful SCALE-07 foundation, but the entity identity model needs tightening before consumers rely on it.

**Strengths**
- Clear sparse-set design with O(1) register/unregister/update.
- `snapshot()` returns a defensive copy.
- Good recognition that deterministic iteration order is required.
- Keeps `BotRegistry` decoupled, which is cleaner than embedding lifecycle concerns there.
- Explicitly includes move/update hooks, which Plan 04 depends on.

**Concerns**
- **HIGH:** `snapshot()` sorted by `entityId` is deterministic but not semantically equivalent to row-major grid scans. This will change pre-shuffle order in `SimulationEngine`, and therefore can change simulation outcomes.
- **HIGH:** Bonded/composite identity handling is ambiguous. If the registry stores one logical `BondedPair` entry, unregistering `primaryEntityId` and `secondaryEntityId` on bonded-pair death will not remove the bonded-pair registry entry.
- **HIGH:** Tick broadcasting may need per-session bot state, not one logical grid occupant. A `BondedPair` or composite may correspond to multiple sessions; replacing `botRegistry.getAllBots()` with one live-entity entry can drop frames.
- **MEDIUM:** No integration invariant test verifies `LiveEntityRegistry` matches the actual grid after bonding, reproduction, collapse, dissolve, movement, and death.
- **MEDIUM:** Registration can occur from WS threads while tick handlers are running; the plan does not define whether mid-tick registration should appear in later `@Order` handlers.

**Suggestions**
- Define two separate concepts explicitly:
  - `LiveEntityRegistry`: grid occupants for simulation iteration.
  - `BotSessionRegistry` or sorted `BotRegistry` snapshot: session-bound bots for outbound frames.
- Add `LiveEntityRegistryInvariantTest`: after scripted lifecycle operations, compare registry entries against occupied non-rock/non-nutrient grid cells.
- For semantic equivalence, expose `snapshotByGridOrder()` or sort by linearized position for refactored grid-scan replacements.
- Audit bonded-pair death hooks against actual registered IDs: unregister the grid occupant ID, not just session participant IDs.

**Risk Assessment:** **HIGH**. The registry is necessary, but identity semantics are currently under-specified and likely to break composites or broadcasting.

## Plan 19-03 — Golden Trace Equivalence

**Summary:** The pinned per-session digest gate is the right idea, but the proposed harness has race and coverage gaps.

**Strengths**
- Correctly avoids global digest flakiness by hashing per session.
- Pinned `EXPECTED_DIGESTS` prevents the “two wrong runs agree” failure mode.
- Good vacuous-baseline guards: `emitCount > 0`, non-empty maps, non-empty SHA rejection.
- Drives the Spring event pipeline through `ApplicationEventPublisher`, which is much better than a partial seam.
- Listener after successful send captures the actual outbound surface.

**Concerns**
- **HIGH:** `awaitAllSessionQueuesDrained()` uses `capture.sessionsSeen()`. Before first async emission, that list can be empty, causing the wait to return too early.
- **HIGH:** The test scenario appears to use simple placed particles only. It may not cover bonding, composites, death, collapse, reproduce, or movement, which are exactly where Plan 02/04 risks live.
- **MEDIUM:** `attemptPlacementForTest` plus manual `botRegistry.register` / `liveEntityRegistry.register` is not truly the full `handleRegister` path and risks divergence from production registration behavior.
- **MEDIUM:** Outbound direct sends outside `OutboundSender` are not captured. That is acceptable as a minimum gate, but it should not be described as “all observable output.”
- **LOW:** A production test seam on `OutboundSender` is acceptable, but it needs a small focused test that listener exceptions do not kill the drain loop.

**Suggestions**
- Track known session IDs in the test and wait for `queueDepth(sessionId) == 0` for those IDs, not `capture.sessionsSeen()`.
- Add an `OutboundSender.flushForTest(sessionIds, timeout)` helper if possible.
- Expand the golden scenario enough to include at least one move, one reproduce, one bonding/composite transition if deterministic setup allows it.
- Rename the claim from “all outbound WebSocket frames” to “all frames emitted via `OutboundSender` in this scenario.”
- Prefer driving registration through a real test register path that performs placement, bot registration, live registration, session registry, and outbound attachment together.

**Risk Assessment:** **MEDIUM-HIGH**. The gate design is sound, but the first implementation can pass while missing the riskiest semantic changes.

## Plan 19-04 — Entity-List Iteration

**Summary:** This plan targets the phase goal directly, but it is the riskiest plan because the proposed iteration order is not equivalent to the current grid-scan semantics.

**Strengths**
- Correctly leaves nutrient spawn, diffusion, lightning, fertility, and serialization grid-walks alone.
- Preserves the single-threaded mutation invariant and defers parallelism.
- Uses the golden trace as the merge gate.
- Explicitly preserves `@Order`, stalled-session handling, death broadcasts, and outbound queueing.
- Adds useful grep gates for shuffle count and remaining grid loops.

**Concerns**
- **HIGH:** Replacing row-major grid scans with `entityId` order changes processing order before `Collections.shuffle(..., simRng)`. Java shuffle output depends on input order, so semantic divergence is likely.
- **HIGH:** Replacing `botRegistry.getAllBots()` with `LiveEntityRegistry.snapshot()` may drop sessions for bonded/composite entities if there is not a one-to-one mapping from live grid occupant to WebSocket session.
- **HIGH:** The plan acknowledges golden-trace failure may happen and says to escalate. That means the plan is not yet executable autonomously as written.
- **MEDIUM:** `processEnergyDecay`, death processing, and overcrowding may have order-sensitive side effects even when no shuffle is involved.
- **LOW:** Grep-count acceptance for nested loops is fragile; it can miss or misclassify loops.

**Suggestions**
- For `SimulationEngine`, preserve row-major equivalence by sorting registry entries by `position.x * height + position.y` before building the lists. That keeps pre-shuffle order identical to the old grid scan.
- For `TickBroadcaster`, strongly consider using a deterministic `BotRegistry.snapshotSortedBySessionOrEntity()` rather than `LiveEntityRegistry`, unless the code proves every live entity maps to exactly one session.
- Add specific tests for bonded/composite broadcasting: both sessions still receive frames after bonding/composite transitions.
- Treat `GoldenTraceEquivalenceTest` failure as a plan-design failure, not just an implementation event. Fix ordering before execution.
- Replace grep loop-count checks with targeted tests or helper methods for the known refactor sites.

**Risk Assessment:** **HIGH**. The plan achieves the intended shape, but likely violates semantic equivalence unless iteration order and session mapping are corrected first.

## Overall Risk Assessment

**Overall risk: HIGH.**

The phase has the right architecture: indexed placement plus live-entity iteration plus a pinned golden trace is a credible path to SCALE-06 and SCALE-07. The main unresolved risks are semantic equivalence and identity mapping. Before execution, I would tighten two contracts:

1. Simulation refactors that replace grid scans must preserve row-major order unless a golden-trace-approved behavior change is explicitly accepted.
2. Live grid occupants and bot/session recipients must not be conflated; broadcasting should remain session-driven unless composites are proven one-session-per-live-entry.

With those corrections and broader lifecycle invariant tests, the phase becomes much more likely to deliver capacity headroom without changing emergent behavior.

---

## OpenCode Review

# Phase 19 Plan Review

## Summary

The four-plan sequence (Placement Index → Live Entity Registry → Golden Trace Gate → Entity-List Iteration) is meticulously researched and correctly scoped for SCALE-06/SCALE-07. The sparse-set data structures (`EligibleCellIndex`, `LiveEntityRegistry`) are the right choice for the grid size and entity cap. However, the phase carries **medium-high execution risk** due to a fundamental determinism tension in Plan 04 (shuffle-order divergence), a non-automatable baseline-pin workflow in Plan 03, and heavy code-surgery coupling across six production files and seven test files. The plans will likely achieve the phase goals, but only if the shuffle determinism issue is resolved before Plan 04 execution and the EXPECTED_DIGESTS capture is automated.

---

## Strengths

*   **Appropriate data structures:** Dense-array + back-map sparse sets for both `EligibleCellIndex` and `LiveEntityRegistry` give O(1) add/remove/sample with deterministic iteration — exactly right for a 256×256 grid with ≤256 entities.
*   **Strong test strategy:** Four Wave-0 test files cover constraints, determinism, density saturation, and semantic equivalence. The GoldenTraceEquivalenceTest with per-session SHA-256 digests correctly sidesteps cross-session virtual-thread scheduling jitter.
*   **Lifecycle hook audit (REVIEWS H1/H3):** The exhaustive enumeration of every `setEntity`/`clearEntity`/`unregisterByEntity` call site for `notifyChanged` and registry hooks is thorough and necessary.
*   **Explicit stale-data acknowledgment:** Plan 01 documents the 1-tick `cellStatusCache` staleness during placement and correctly designates `trySetEntity` as the atomic gate.
*   **Atomic backpressure preservation:** `GRID_FULL` wire shape and `RejectionToken` taxonomy are reused unchanged (Phase 17 contracts respected).

---

## Concerns

### Plan 01 — Placement Index

*   **[MEDIUM] `notifyChanged` cost under high churn:** Each grid mutation triggers a 5×5 bbox re-evaluation. That's ~225 `worldGrid.getCell` / `getNeighbors` reads per event (25 cells × ~9 reads each). At 100+ moves/deaths/births per tick, the tick handler thread could spend >10k grid reads just maintaining the index, dwarfing the O(1) sampling win. The dirty-bbox approach is correct; the multiplier under churn is just higher than acknowledged.
*   **[MEDIUM] `@DependsOn("rockGenerator")` fragility:** Spring `@DependsOn` ensures `@PostConstruct` order, but it does not guarantee the Rocks are physically written to `WorldGrid` before `initialize()` runs if `RockGenerator` uses deferred/async initialization. The plan should verify `RockGenerator.initialize()` is fully synchronous.
*   **[LOW] `evaluateEligibility` double-neighbor-walk:** For constraint-3, the code walks 8 neighbours of the candidate cell, and for each occupied neighbour walks *its* 8 neighbours to count. That's up to 64 reads per cell in the bbox. This is O(25×64) = 1,600 reads worst-case per event. Still acceptable at current scale, but wasteful. A pre-computed neighbour count cache (like `cellStatusCache` but storing the raw count byte) would eliminate the inner walk.

### Plan 02 — Live Entity Registry

*   **[MEDIUM] `executeCompositeMovement` hook uncertainty:** The plan marks this as a "HARD REQUIREMENT" but admits the method may not exist in `ActionResolver`. If the rigid-body translation is embedded inside `resolveMove` or spread across helper methods, the executor could miss it, leaving stale positions in the registry. Stale positions → Plan 04 iterates wrong coordinates → golden trace failure.
*   **[MEDIUM] Constructor/back-compat ctor cascade:** Adding two new constructor parameters (`EligibleCellIndex`, `LiveEntityRegistry`) to `SimulationEngine`, `ActionResolver`, `DeathFinalizer`, and `WorldWebSocketHandler` — plus propagating them through internal `new DeathFinalizer(...)` calls and test alt-ctors — is high-touch. One missed alt-ctor path (e.g. a `@SpringBootTest` that instantiates `SimulationEngine` manually) causes a compile error that blocks the wave.
*   **[LOW] Sort cost in `snapshot()`:** `O(N log N)` per snapshot, called by every tick handler (maybe 4–5× per tick). At N=256 this is noise, but it's a fixed tax on every tick even when the grid scan being replaced was cheap.

### Plan 03 — Golden Trace Equivalence

*   **[HIGH] Manual EXPECTED_DIGESTS pin workflow:** The two-pass "run once with placeholder, print to stdout, manually copy into source, recompile, run again" workflow is not CI-friendly and is prone to copy-paste errors. If the executor is an automated agent it can self-edit, but the plan frames this as requiring manual intervention, which breaks autonomous execution flow.
*   **[MEDIUM] Mock session fidelity:** The test uses `mock(WebSocketSession.class)`. If `OutboundSender` or `TickBroadcaster` calls any other method on the session (e.g. `getAttributes()`, `getUri()`, `isOpen()` checks inside the drain loop), the mock defaults may return null/false and silently skip emit paths. This could produce a vacuous pass even with the emitCount guard, if all sessions are skipped for the same mock-default reason across both runs.
*   **[MEDIUM] `resetAll()` completeness:** The plan mentions resetting `worldGrid`, `botRegistry`, `liveEntityRegistry`, and `handler`, but may miss `SessionRegistry` internal state and `OutboundSender` per-session queues. Leftover queue state or closed mock sessions from Run A could pollute Run B.

### Plan 04 — Entity-List Iteration

*   **[HIGH] `Collections.shuffle` determinism divergence:** Pre-Plan-04, the shuffled lists are built from row-major grid scans. Post-Plan-04, they come from `snapshot()` which is sorted by `entityId` (lexicographic string order). `Collections.shuffle` with a fixed seed produces a deterministic permutation **of its input order**. Because the input order differs, the post-shuffle sequence will differ. This changes interaction resolution order, which changes deaths, energy levels, and ultimately the observable simulation output. The plan acknowledges this and says "STOP and escalate," but this is a **predictable Plan 04 failure mode** that should be mitigated proactively, not treated as an unknown.
*   **[MEDIUM] `TickBroadcaster` session lookup per entity:** Switching from `botRegistry.getAllBots()` (which yields `sessionId` directly) to `liveEntityRegistry.snapshot()` + `botRegistry.getSessionForEntity(entityId)` adds a HashMap lookup per entity per tick. More importantly, if the two registries ever drift (e.g. an entity is in `LiveEntityRegistry` but missing from `BotRegistry` due to a missed hook), the session is silently skipped. The existing code would have iterated it via `getAllBots()`. The per-session digest might still match if the skip is consistent, but it's a coverage gap.
*   **[MEDIUM] `processOvercrowding` refactored semantics:** The original grid scan visits every cell and may set overcrowding flags on cells that become empty mid-tick (e.g. due to death). Refactoring to iterate only live entities means empty cells that were overcrowded last tick might not get their flag refreshed/cleared if the logic depends on visiting them. The plan claims empty cells can't be overcrowded, but if the flag is used as a spatial cache for environmental effects or nutrient spawning, stale flags on empty cells could diverge.

---

## Suggestions

1.  **[Plan 04 — CRITICAL] Pre-shuffle sort to preserve row-major equivalence:** For the three `Collections.shuffle(..., simRng)` sites in `SimulationEngine`, sort the filtered entity-list by `position.x() * height + position.y()` (row-major linear index) **before** calling `shuffle`. This makes the pre-shuffle input order identical to the pre-refactor grid-scan order, guaranteeing deterministic shuffle output and eliminating the HIGH-risk divergence. Add a comment explaining this is a compatibility shim.
2.  **[Plan 03] Auto-pin EXPECTED_DIGESTS via resource file:** Instead of hardcoding digests in Java source, write them to `src/test/resources/golden-trace-phase19.json` on first run (generate-if-missing). The test reads the file at runtime. This removes manual editing and recompilation from the workflow.
3.  **[Plan 02/04] Registry consistency assertion:** Add a `@Profile("test")` bean or a test assertion that verifies `liveEntityRegistry.size() == botRegistry.getAllBots().size()` at the end of every tick. Catches missed register/unregister hooks immediately rather than via a flaky golden trace.
4.  **[Plan 01] Hoist `worldGrid.getCell` reads in `evaluateEligibility`:** For the dirty-bbox walk, instead of re-calling `worldGrid.getCell(cx, cy)` and then `worldGrid.getCell(nPos.x(), nPos.y())` for each neighbour, consider whether `Cell` itself can report its occupied-neighbour count if that data is already computed during `buildStatusCaches()`. This would collapse constraint-3 evaluation to a single cache read instead of a nested walk.
5.  **[Plan 04] Add `sessionId` to `EntityEntry`:** If `LiveEntityRegistry.EntityEntry` also stored `sessionId` (or an `Optional<String>`), `TickBroadcaster` could avoid the `botRegistry.getSessionForEntity` lookup entirely, removing a coupling point and a potential skip hazard. This requires wiring `sessionId` at registration time.

---

## Risk Assessment

**Overall Risk Level: MEDIUM-HIGH**

**Justification:**

The phase goals are clearly defined and the chosen algorithms are sound, but execution hinges on four sequential waves of high-touch code surgery across the simulation core. The highest risk is **Plan 04's deterministic equivalence guarantee**, which is architecturally compromised by the `Collections.shuffle` order divergence unless a compatibility sort is added. Without it, the GoldenTraceEquivalenceTest is expected to fail predictably, forcing either an operator escalation (delaying the phase) or an undocumented digest re-pin (voiding the D-10 contract). Secondary risks are the manual baseline-pin workflow in Plan 03 and the cascading constructor changes in Plans 01–02, which are prone to compile-time regression in tests with manual bean instantiation. If the suggested pre-shuffle sort is adopted, the risk drops to **MEDIUM**.

---

## Consensus Summary

All four reviewers agree the phase architecture is sound (sparse-set data structures right-sized, wave sequencing correct, EXPECTED_DIGESTS pin-before-refactor closes the self-agreement loophole). One issue dominates the risk profile and is flagged HIGH by **all four** independently.

### Agreed Strengths

- **Wave sequencing 01 → 02 → 03 → 04 with EXPECTED_DIGESTS pinned post-02/pre-04** — closes the "two wrong runs agree" trap. *(gemini, claude, codex, opencode)*
- **Sparse-set data structures** (`EligibleCellIndex`, `LiveEntityRegistry`) — O(1) add/remove/sample, deterministic iteration, right scale for 256×256 grid + ≤256 entities. *(gemini, claude, codex, opencode)*
- **Per-session SHA-256 digests** sidestep cross-session VT scheduling jitter; correct call. *(gemini, codex, opencode)*
- **Lifecycle hook audit (REVIEWS H1/H3)** — exhaustive enumeration of `setEntity`/`clearEntity`/`unregisterByEntity` sites. *(claude, codex, opencode)*
- **Concurrency hygiene preserved** — single-threaded mutation invariant kept, `parallelStream` banned via grep gate. *(gemini, claude)*
- **Phase 17 contracts respected** — `E\|503\|GRID_FULL` wire shape and `RejectionToken` taxonomy unchanged. *(codex, opencode)*

### Agreed Concerns

**[CONSENSUS HIGH — flagged by all 4 reviewers] Plan 04 pre-shuffle order divergence.**
Pre-Plan-04: `SimulationEngine` builds shuffle inputs via row-major grid scan (`x*H+y` order). Post-Plan-04: `LiveEntityRegistry.snapshot()` returns entries sorted by `entityId` (lexicographic). `Collections.shuffle(list, seededRng)` is deterministic *for a given input permutation* — different input order → different output permutation → different combat pairing → different deaths → different per-session digests. Plan 04 acknowledges this as a STOP/escalate edge case; reviewers treat it as the **expected outcome** of first execution unless mitigated.

**Recommended fix (3-of-4 reviewers — gemini, claude, codex, opencode):** sort the snapshot (or sort the filtered list before each `Collections.shuffle` call site in `SimulationEngine`) by row-major linear index `position.x() * height + position.y()`, *not* by `entityId`. This preserves byte-for-byte shuffle equivalence across the cut. Document as a compatibility shim. `TickBroadcaster` does not need this sort — per-session hashing already neutralises its order-sensitivity (gemini explicitly).

**[CONSENSUS HIGH — flagged by gemini, codex, claude] Plan 03 `awaitAllSessionQueuesDrained` race.**
The helper iterates `capture.sessionsSeen()` — populated *as frames drain*. On the first tick check, the set may be empty before the drain VT has emitted, the loop returns "all drained" prematurely, the next tick fires before the previous tick's frames finish, and digests become non-deterministic.

**Recommended fix:** track the explicit list of registered session IDs in the test driver; await `queueDepth(sid) == 0` for *those* IDs (not for `sessionsSeen()`).

**[CONSENSUS HIGH — flagged by codex, opencode strongly] Bonded/composite identity ambiguity in `LiveEntityRegistry`.**
Plans blur "grid occupant entity" vs "bot session entity". A `BondedPair` may correspond to two sessions; replacing `botRegistry.getAllBots()` with one live-entity entry can drop frames. Death-of-bonded-pair hooks may unregister `primaryEntityId`/`secondaryEntityId` while the registry stores the bonded-pair's grid-occupant ID, leaving a stale entry.

**Recommended fix:** either keep `TickBroadcaster` driven by `BotRegistry.snapshotSorted...` (codex) or add `sessionId` to `EntityEntry` so the lookup is direct (opencode); add a `LiveEntityRegistryInvariantTest` that compares registry contents against actual non-rock/non-nutrient occupied cells after scripted bond/composite/dissolve/death scenarios.

**[CONSENSUS MEDIUM — flagged by gemini, opencode] Constructor cascade fragility.**
Adding `EligibleCellIndex` + `LiveEntityRegistry` ctor params to `SimulationEngine`/`ActionResolver`/`DeathFinalizer`/`WorldWebSocketHandler` plus all alt/back-compat ctors in tests is high-touch. One missed `@SpringBootTest` manual instantiation = compile break that blocks the wave.

**[CONSENSUS MEDIUM — flagged by claude, opencode] EXPECTED_DIGESTS pinning is not CI-friendly.**
Two-pass "run with placeholder, copy hex into Java source, recompile, re-run" workflow is manual and error-prone. **Recommended fix (opencode):** pin to a generate-if-missing resource file `src/test/resources/golden-trace-phase19.json` so the test is self-pinning on first run and read-only afterward; eliminates copy-paste step.

### Divergent Views

- **Severity of Plan 04 risk if shuffle-order issue is mitigated:** gemini drops to LOW, codex stays HIGH (cites identity-mapping for broadcasting as separate HIGH), claude drops to LOW under "Suggestion 1 Option A", opencode drops to MEDIUM. → identity-mapping fix should be made independently of shuffle-order fix.
- **`evaluateEligibility` cost model:** opencode flags MEDIUM cost of repeated `worldGrid.getCell` reads inside the dirty bbox + suggests a pre-computed neighbour-count cache. Other reviewers treat current design as acceptable. Worth noting for Phase 21 benchmark observation but not blocking.
- **`@DependsOn("rockGenerator")` sufficiency:** opencode flags it MEDIUM (only enforces `@PostConstruct` order, not synchronous Rock writes). Others treat as closed by H2. Verify `RockGenerator.initialize()` is fully synchronous before relying on `@DependsOn`.
- **Lock ordering documentation:** gemini flags MEDIUM (`Index Monitor → Grid Read Lock`). Claude treats as closed/documented. Light-touch: add a one-line comment in `EligibleCellIndex` capturing the invariant.

### Recommended pre-execution actions (in order)

1. **Adopt row-major sort in `LiveEntityRegistry.snapshot()`** (or per-call-site sort in `SimulationEngine` before each `Collections.shuffle`) — closes the unanimous HIGH. *(claude Suggestion 1A; codex Suggestion 1; gemini Suggestion 1; opencode Suggestion 1.)*
2. **Fix `awaitAllSessionQueuesDrained`** — track registered session IDs explicitly, drop `sessionsSeen()`. *(gemini, codex, claude.)*
3. **Decide bonded/composite identity model** — either (a) keep `TickBroadcaster` session-driven via `BotRegistry`, or (b) add `sessionId` to `EntityEntry`. Add `LiveEntityRegistryInvariantTest`. *(codex, opencode.)*
4. **Move EXPECTED_DIGESTS to a generated resource file** — eliminates manual edit step, makes the gate autonomously executable. *(opencode.)*
5. **Pre-flight signature check in Plan 03 Task 2** — verify `OutboundSender.attachSession` and `SessionRegistry.register` actual signatures before writing `driveScenario`. *(claude.)*
6. **Enumerate `ActionResolver` entityId sources explicitly** in Plan 02 ("`ra.particle.id()` for Particle moves; `member.id()` for composite-member moves") so executor doesn't pick wrong field. *(claude.)*

With items 1–3 applied, all reviewers project the overall risk drops from HIGH/MEDIUM-HIGH to LOW/MEDIUM and the wave becomes autonomously executable.
