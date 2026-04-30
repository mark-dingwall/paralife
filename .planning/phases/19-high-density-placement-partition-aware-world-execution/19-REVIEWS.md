---
phase: 19
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-04-30T18:44:54Z
plans_reviewed:
  - 19-01-placement-index-PLAN.md
  - 19-02-live-entity-registry-PLAN.md
  - 19-03-golden-trace-equivalence-PLAN.md
  - 19-04-entity-list-iteration-PLAN.md
models:
  gemini: gemini-3.1-pro-preview
  claude: opus (effort xhigh)
  codex: gpt-5.5 (reasoning_effort=high)
  opencode: openrouter/moonshotai/kimi-k2.6
---

# Cross-AI Plan Review — Phase 19

## Gemini Review

```markdown
# Phase 19 Implementation Plan Review

## Summary
The implementation plans for Phase 19 are exceptionally detailed, well-researched, and rigorously structured. The phased approach to decoupling the O(grid) scans into a `LiveEntityRegistry` (19-02) and an `EligibleCellIndex` (19-01), followed by the semantic-equivalence gate (19-03) before the main refactoring (19-04), ensures that behavioral correctness is proven and easily verifiable. However, there are significant gaps in lifecycle management for the `EligibleCellIndex` and a guaranteed hash-ordering divergence in the `GoldenTraceEquivalenceTest` that will cause the equivalence gate to fail.

## Strengths
- **Rigorous Semantic Equivalence Gate:** Plan 19-03’s `GoldenTraceEquivalenceTest` is a brilliant use of the Golden Master testing technique. Pinning the SHA-256 digest of outbound WebSocket frames before Plan 19-04 executes ensures the O(1) iteration refactoring won't silently break existing behavior.
- **Architectural Safety:** Strict adherence to the single-threaded mutation core invariant. Deferring the parallel read-only sub-steps to Phase 19.1 correctly minimizes the blast radius of the current milestone.
- **Data Structure Choices:** The O(1) dense-array + hash map (sparse-set) pattern chosen for both the `LiveEntityRegistry` and `EligibleCellIndex` is highly appropriate for ECS-like iteration and random placement sampling without adding heavy dependencies.

## Concerns
- **HIGH: `EligibleCellIndex` Staleness / Missing Lifecycle Hooks:** Plan 19-01 wires `notifyChanged(x, y)` exclusively into `WorldWebSocketHandler` after a successful placement. However, entities moving (via `ActionResolver`), dying (via `DeathFinalizer`), or collapsing into composites (`SimulationEngine`) will also alter the state of the grid. Because these sites are not wired to call `eligibleCellIndex.notifyChanged`, the 5x5 bounding box index will become permanently stale, leading to incorrect rejections (`GRID_FULL`) as cells are not freed upon death or movement.
- **HIGH: `GoldenTraceEquivalenceTest` Baseline Divergence (Plan 19-03 vs 19-04):** Plan 19-03 instructs the test driver to sort values to maintain determinism before the 19-04 refactor lands. However, `TickBroadcaster` internally iterates over `ConcurrentHashMap.values()` from `BotRegistry`, which the test driver cannot control. If `ConcurrentHashMap.values()` happens to yield a stable order in 19-03, it will almost certainly be a *different* order than the insertion-ordered `LiveEntityRegistry.snapshot()` introduced in 19-04. This will cause an unavoidable `EXPECTED_DIGEST` mismatch when Plan 19-04 lands, breaking the exact contract the test was meant to enforce.
- **MEDIUM: Duplicate Lifecycle Wiring:** Both `EligibleCellIndex` (once the bug above is fixed) and `LiveEntityRegistry` require hooks at the exact same lifecycle points (`DeathFinalizer`, `ActionResolver`, `WorldWebSocketHandler`). Relying on developers to manually maintain these parallel hooks across multiple services introduces a brittle coupling.

## Suggestions
1. **Expand `EligibleCellIndex` Hooks (Plan 19-01):** Update Plan 19-01 to wire `eligibleCellIndex.notifyChanged(x,y)` into the same lifecycle locations identified in Plan 19-02 for `LiveEntityRegistry`. This includes `ActionResolver.resolveMove` (triggering on both old and new positions), `DeathFinalizer.finalize*`, and `SimulationEngine.collapseToMember`.
2. **Fix `TickBroadcaster` Iteration Order in Plan 19-03:** To ensure the baseline digest captured in Plan 19-03 perfectly matches the output of Plan 19-04, update `TickBroadcaster` *in Plan 19-03* to explicitly sort `botRegistry.getAllBots()` by `entityId` before iteration. Then, update Plan 19-04 to apply the exact same sort on `liveEntityRegistry.snapshot()`.
3. **Consolidate WorldGrid Mutation Events (Future/Backlog):** Given that two independent indices now need to be notified on every grid change, consider introducing an internal event bus or callback interface directly on `WorldGrid` (e.g., `GridMutationListener.onCellChanged(x, y)`). This would remove the need to manually sprinkle `notifyChanged` and `updatePosition` hooks across `ActionResolver`, `DeathFinalizer`, etc.
4. **Clarify `GoldenTraceCapture` Test Setup:** In Plan 19-03, explicitly ensure that the `GoldenTraceCapture` helper strictly isolates the test context to the 10 seeded bots. Any stray keepalives, background reconnects, or metrics could inadvertently pollute the SHA-256 digest.

## Risk Assessment
**Overall Risk: MEDIUM**

*Justification:* The foundational ideas are extremely solid, and the testing strategy is excellent. The risk is elevated to MEDIUM solely due to the `EligibleCellIndex` staleness bug. If Plan 19-01 is executed as-written, the simulation will experience artificial capacity limits and density pathing bugs as dead or moved entities fail to free up eligible placement slots. If the missing lifecycle hooks and the hash iteration sorting mismatch are addressed, the risk profile drops to LOW.
```

---

## Claude Review

# Phase 19 Plan Review

## Summary

Plans solid in shape — sparse-set + entity-list patterns correct, EXPECTED_DIGEST pinning workflow clever. But three critical wiring gaps will break execution: EligibleCellIndex maintenance hooks incomplete (Plan 01), LiveEntityRegistry lifecycle hooks miss formation/reproduce (Plan 02), GoldenTraceEquivalenceTest scenario likely captures zero frames (Plan 03). Plan 04 strong but inherits gaps. Phase achievable; needs targeted fixes before execution.

## Strengths

- D-10 EXPECTED_DIGEST pin design — closes self-consistency loophole. Strong.
- PATTERNS.md analog override (DeathFinalizer not BotRegistry) — correct architectural call.
- Plan 02 ActionResolver.resolveMove hook flagged HARD REQUIREMENT — prevents Plan 04 stale-position trap.
- Wave structure (01→02→03→04) sequences cleanly. EXPECTED_DIGEST captured pre-Plan-04 = clean baseline.
- Sparse-set sketches verbatim from competitive-programming canon. No hand-rolled novelty.
- D-08/D-11 single-threaded invariant enforced via grep gates (`parallelStream == 0`). Good.
- Constraint-3 cascade radius math (5×5 from 1-step overcrowding coupling) sound.
- Diffusion / nutrient-spawn carve-outs explicit. No accidental refactor of inherently-spatial loops.

## Concerns

### HIGH

**[H1] Plan 01 — EligibleCellIndex.notifyChanged not wired at death + movement sites.**
CONTEXT.md D-04 mandates "every entity place / clear / death event dirties a 5×5 bbox". Plan 01 task 2(d) wires only the WorldWebSocketHandler.handleRegister success path. Death (DeathFinalizer.finalize*Death → worldGrid.clearEntity), composite collapse (SimulationEngine.collapseToMember), movement (ActionResolver.resolveMove → clearEntity + setEntity), reproduce (resolveReproduce → setEntity), bonding (processInteractions → setEntity + clearEntity), composite formation, dissolve all mutate grid without notifying index. Result: cells that became eligible after a death stay excluded from index until coincidental neighbour-event triggers a bbox refresh. PlacementDensityIntegrationTest will pass at saturation (no event-rate dependency) but production runs with churn will produce false GRID_FULL. Fix: add notifyChanged hooks at all `worldGrid.{clearEntity, setEntity}` callers (or hook inside WorldGrid itself + accept Spring-awareness there).

**[H2] Plan 01 — EligibleCellIndex @PostConstruct ordering vs RockGenerator undefined.**
EligibleCellIndex.initialize() walks grid and adds eligible cells. Comment claims "Spring guarantees component init order via dependency graph — WorldGrid is already populated". WorldGrid is populated (constructor), but rocks aren't placed until RockGenerator.@PostConstruct runs. EligibleCellIndex doesn't depend on RockGenerator; @PostConstruct ordering between sibling beans is undefined. If EligibleCellIndex.initialize runs first, rock cells get added permanently (no future event removes them). Fix: `@DependsOn("rockGenerator")` on EligibleCellIndex, or constructor-inject RockGenerator to force ordering, or initialize lazily on first sample.

**[H3] Plan 02 — LiveEntityRegistry missing formation / reproduce / dissolve / revert hooks.**
Plan 02 covers register (WS), unregister (DeathFinalizer 3 sites), updatePosition (ActionResolver.resolveMove), and `collapseToMember`. Misses:
- BondFormation (SimulationEngine.processInteractions ~line 555–617): two Particles → one BondedPair with new id `predator+prey`. Need unregister(predator.id) + unregister(prey.id) + register(bp.id, primaryPos).
- CompositeFormation (~line 622–681): two BondedPairs → two CompositeMembers with fresh UUIDs. Need unregister(bp1.id) + unregister(bp2.id) + register(member1.id, pos1) + register(member2.id, pos2).
- ReproducerBud + resolveReproduce: spawn `child-N` Particle. Need register(childId, target).
- dissolveToParticles: members → new Particle ids `cm.id+"-p"`. Need unregister(cm.id) + register(particle.id, pos) per member.
- revertToBondedPair: CompositeMember → BondedPair with new id. Need unregister(cm.id) + register(bp.id, pos).
- executeCompositeMovement: rigid-body move of N members; updatePosition needed per member, not just resolveMove site.

Plan 04 then iterates an incomplete registry — shuffle list size differs from grid-scan size → RNG consumption differs → GoldenTraceEquivalenceTest fails. The failure points to "fix Plan 02" but executor mid-Plan-04 doesn't own that. Backflow during Wave 4 = expensive. Fix: Plan 02 must enumerate every `worldGrid.setEntity / clearEntity / trySetEntity` site that introduces or remaps an entityId, and add the matching register/unregister/updatePosition.

**[H4] Plan 03 — GoldenTraceEquivalenceTest driveScenario likely captures zero frames.**
Test wires `botRegistry.register(sessionId, entityId, pos)` with synthetic `trace-sess-N` ids but doesn't register sessions in SessionRegistry, doesn't call `OutboundSender.attachSession`, doesn't open real WebSocket sessions. TickBroadcaster.onTick at line 192–195: `WebSocketSession session = sessionRegistry.getSession(bot.sessionId()); if (session == null || !session.isOpen()) continue;` — every iteration skipped. OutboundSender.offer never invoked. drainLoop never sends. FrameEmitListener never fires. Digest = SHA-256("") = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

Acceptance regex `EXPECTED_DIGEST = "[0-9a-f]{64}"` matches the empty-digest. Test will pass with vacuous baseline. Plan 04 refactor would also produce empty-digest. False green throughout. Fix: either (a) wire real WS clients à la HundredBotIntegrationTest, or (b) inject mock WebSocketSession + attached OutboundSender into SessionRegistry. Add acceptance gate `assertThat(capture.emitCount()).isGreaterThan(0)`.

**[H5] Plan 03 — TickEngine driving mechanism unspecified for full @Order chain.**
EnvironmentDeterminismTest uses `environmentEngine.onTickEnvOnlyForTest(tick)` — env-only, not the full pipeline. GoldenTrace needs SimulationEngine + EnvironmentEngine + ActionResolver + EnvPostActionReconciler + TickBroadcaster — i.e. publish TickEvent via ApplicationEventPublisher (so all @EventListener-annotated handlers fire in @Order chain). Plan says "tickEngine.runOneTick() is the test seam" but doesn't confirm it exists or that it triggers the full event chain. If executor falls back to env-only, test exercises the wrong code path and EXPECTED_DIGEST is captured against a partial pipeline. Fix: explicit driver via `applicationEventPublisher.publishEvent(new TickEvent(tickNumber))` — confirms full chain fires; document in plan.

### MEDIUM

**[M1] Plan 01 — wrong import paths in plan source samples.**
Plan code imports `com.paralife.world.Particle` and `com.paralife.world.ParticleType`. Actual: `com.paralife.world.Entity.Particle` and `com.paralife.world.Entity.ParticleType` (sealed inner types). Will not compile as-written. Cosmetic but multiple sites — executor needs to translate. Fix: correct samples to `Entity.Particle` qualifier or import via `com.paralife.world.Entity.Particle`.

**[M2] Plan 01 — PlacementDeterminismTest bypasses handleRegister.**
Test calls `eligibleCellIndex.sample(handler.spawnRngForTest())` directly instead of driving WS register frames. Tests the index, not the integration with handleRegister. D-06 contract is "registration arrival order → identical placements" — the integration is what matters. If a future refactor splits `spawnRng` from sample-call site, this test stays green while D-06 silently breaks. Fix: drive via real WS r| frames or via a package-private `handler.handleRegisterForTest(sessionId, registerFrame)` seam.

**[M3] Plan 04 — TickBroadcaster sort-by-entityId determinism treated as optional.**
Plan 04 task 2 step 4: "If GoldenTraceEquivalenceTest fails ... sort the snapshot by entityId before iteration". This is not a flake fix — pre-Plan-04 used `botRegistry.getAllBots()` (ConcurrentHashMap.values()) iteration order; post-Plan-04 uses LiveEntityRegistry insertion order. These almost certainly differ → cross-session emit order differs → digest differs. Sort isn't optional, it's mandatory for digest stability across the refactor boundary. (Same applies to any other handler iterating bots.) Fix: bake `entries.sort(Comparator.comparing(EntityEntry::entityId))` into the canonical replace-pattern; add grep gate for sort presence.

**[M4] Plan 04 — `Collections.shuffle(.*simRng) >= 3` shuffle count gate too loose.**
Pre-refactor SimulationEngine has shuffle calls at lines 305, 429, 513 (= 3). Post-refactor count must be exactly 3, not "≥ 3". A `>= 3` bound passes if executor accidentally adds a fourth shuffle. RNG consumption changes → digest fails. Fix: `== 3`.

**[M5] Plan 04 — remaining-grid-loop bound `≤ 4` too generous.**
8 in-scope sites + 1 nutrient-spawn (kept) = 9 doubles in source. Refactor 8 → 1 remaining (nutrient-spawn). Bound `≤ 4` lets executor leave 3 in-scope sites unrefactored and still pass. Fix: `≤ 1` if executor cleanly removes the 8 in-scope sites; or `≤ 2` to permit one unforeseen pass.

**[M6] Plan 02 — DeathFinalizer constructor signature change risk.**
Adding `LiveEntityRegistry` to DeathFinalizer constructor breaks the back-compat 9-arg SimulationEngine ctor (lines 172–202) which constructs a fresh DeathFinalizer with the old signature: `new DeathFinalizer(worldGrid, botRegistry, this.buffRegistry, compositeRegistry, this.hooks, this)`. Plan 02 doesn't mention this. Fix: update the back-compat ctor to pass a fresh LiveEntityRegistry instance, or mark that ctor as deprecated and remove its DeathFinalizer instantiation.

### LOW

**[L1] Plan 01 — lost-race fallback emits GRID_FULL on transient race.**
D-05 acceptable per CONTEXT.md, but spurious GRID_FULL on a non-full grid is a slightly noisy wire shape under high concurrent registration. Could mitigate with one re-sample (still O(1)). Accept per existing decision; flag for Phase 21 benchmark observation.

**[L2] Plan 03 — FrameEmitListener inside synchronized(session) extends critical section.**
CLAUDE.md says monitor only protects sendMessage. Adding listener inside extends slightly. Listener is fast (digest update); sub-µs. Could move outside (after monitor exit) — bytes are immutable post-send. Trivial.

**[L3] Plan 03 — empty-digest false-pass not gated.**
Acceptance regex matches SHA-256 of empty input. Add `emitCount > 0` assertion + grep that EXPECTED_DIGEST is not the empty-digest constant.

**[L4] Plan 04 — `@Order(14)` grep on EnvironmentEngine assumes existing annotation.**
Confirm pre-refactor file has `@Order(14)`. CLAUDE.md tick pipeline cites @Order(14) so likely present. Sanity check.

**[L5] Plan 03 — capture digest reset semantics ambiguous.**
`MessageDigest.digest()` resets the digest. `digestAsHex()` calls `digest.digest()` once, then `currentDigest()` would re-compute over zero input. If test calls both, second call returns SHA-256 of empty. Plan only calls `digestAsHex()` per run — OK. Document.

## Suggestions

1. **Add Plan 01 task 3:** wire EligibleCellIndex.notifyChanged at all worldGrid mutation sites — DeathFinalizer.finalize*Death (post-clearEntity), ActionResolver.resolveMove (post-setEntity new pos + post-clearEntity old pos), SimulationEngine.processInteractions (bonding setEntity/clearEntity, composite formation), reproduce children, dissolve/revert. Otherwise placement index drifts.

2. **Add @DependsOn("rockGenerator") to EligibleCellIndex** or constructor-inject RockGenerator to lock @PostConstruct ordering.

3. **Expand Plan 02 task 2 to enumerate every entity-id-introducing site:** BondFormation, CompositeFormation, ReproducerBud, resolveReproduce solo, dissolveToParticles, revertToBondedPair, executeCompositeMovement. Provide explicit hook locations per site.

4. **Plan 03: pre-flight test infrastructure** — confirm SessionRegistry + OutboundSender wiring needed for real frame emission. Either model on HundredBotIntegrationTest (real Jetty WS clients) or inject mock-but-registered sessions. Add `assertThat(capture.emitCount()).isGreaterThan(0)` before digest comparison. Document `applicationEventPublisher.publishEvent(new TickEvent(t))` as the explicit tick driver.

5. **Plan 04: make sort mandatory** — bake `entries.sort(Comparator.comparing(EntityEntry::entityId))` into the canonical TickBroadcaster + any per-bot iteration replace-pattern. Reduces "harness flake or real divergence" diagnostic ambiguity. Add grep gate.

6. **Tighten Plan 04 grep counters** — `Collections.shuffle(.*simRng)` exactly == 3; remaining `for (int .. = 0` ≤ 1 (or ≤ 2 with explicit allowlist for fertility/serialization passes).

7. **Plan 02 — also update SimulationEngine 13-arg back-compat ctor** to construct DeathFinalizer with LiveEntityRegistry parameter, OR add a no-op stub registry, OR remove the back-compat ctor's internal DeathFinalizer construction.

8. **Consider Plan 03 per-session digest map** instead of single global digest. Stronger contract; cross-session ordering becomes irrelevant. O(N) digests, each independently comparable. Simpler debugging when divergence happens.

9. **Add explicit "EXPECTED_DIGEST is non-empty" check** to Plan 03 acceptance: `EXPECTED_DIGEST != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"` (SHA-256 of empty).

## Risk Assessment

**Overall: MEDIUM-HIGH.**

Plan shape correct; execution gaps will surface as test failures or false-greens. Specifically:
- H1 + H3: Plan 01 + Plan 02 wiring incompleteness propagates to Plan 04 as digest divergence. Caught by GoldenTrace, but expensive backflow if discovered mid-Wave-4.
- H4 + H5: Plan 03's harness gaps risk a vacuous baseline that masks Plan 04 regressions. Worst case: Plan 04 lands with broken semantics, EXPECTED_DIGEST agrees because both runs produce empty-digest, real wire output silently drifts. Concerns 4+5 are the most damaging because they undermine the entire equivalence-gate strategy.
- H2: smaller blast-radius (rocks-as-eligible bug) but causes false GRID_FULL early in production runs.

Concurrency invariants (single-threaded core, RRWL, VT-per-session) preserved across plans — design discipline is sound. D-08/D-11 enforced via grep gates. No parallelStream sneaking in.

Fixes for H1–H5 are localised (each ≤ 1 hour of plan revision), not architectural. Once addressed, risk drops to LOW. Recommend revising Plans 01–03 before Wave 1 begins; Plan 04 holds as-written subject to M3 + M4 + M5 tightening.

Want me to draft concrete patch text for the H1–H5 fixes?

---

## Codex Review

# Phase 19 Plan Review

## Overall Assessment

The four-plan sequence is directionally strong: it separates placement, lifecycle registry, equivalence gating, and entity-list consumption in a sensible dependency chain. The strongest part is the explicit semantic-equivalence gate before Plan 04. The main risks are lifecycle correctness and over-specified implementation details. In particular, the placement index must be updated for *all* occupancy-changing events, not only register/death; otherwise it will drift from `WorldGrid`. There are also concurrency risks around reading `EnvironmentEngine.cellStatusCacheView()` from WebSocket threads while the tick thread rebuilds it.

Overall risk: **MEDIUM-HIGH** until the index-maintenance and golden-trace design are tightened.

---

# Plan 19-01 — Placement Index

## Summary

This plan targets the right bottleneck and uses an appropriate sparse-set data structure. The eligibility rules and `GRID_FULL` behavior match the phase decisions. However, the maintenance strategy is incomplete: updating the index only from `WorldWebSocketHandler` after registration is not enough to keep it correct as entities move, die, collapse, reproduce, or are cleared. The plan also introduces a cross-thread read of `EnvironmentEngine.cellStatusCacheView()` that may not be safe unless the cache is immutable or guarded.

## Strengths

- Correctly removes the retry-storm failure mode.
- Sparse-set `dense + posInDense` structure is appropriate for O(1) sample/add/remove.
- Keeps existing `E|503|GRID_FULL` wire shape.
- Tests cover core constraints, determinism, empty-set behavior, and dense saturation.
- Explicitly avoids new dependencies and new protocol surface.

## Concerns

- **HIGH:** Index maintenance misses many occupancy changes. Movement, deaths, composite collapse, reproduction, and any `WorldGrid.clearEntity/setEntity` call can change eligibility. Hooking only register means stale occupied cells may remain eligible and freed cells may never become eligible.
- **HIGH:** `EnvironmentEngine.cellStatusCacheView()` may be unsafe to read from WS threads while the tick thread rebuilds the backing map.
- **HIGH:** `@PostConstruct` ordering against `RockGenerator` is not guaranteed unless there is an explicit dependency. The initial eligible set may be built before rocks exist.
- **MEDIUM:** The proposed `EnvironmentEngine` dependency from `EligibleCellIndex` increases coupling and may create bean lifecycle or circular dependency issues.
- **MEDIUM:** Lost-race fallback returns `GRID_FULL` even if only one sampled cell was stale. That preserves no-retry, but the reason may be false.
- **MEDIUM:** The determinism test samples directly from `EligibleCellIndex` instead of driving the real register path, so it may not prove the actual `handleRegister` contract.
- **LOW:** Acceptance checks based on line counts and grep counts are brittle.

## Suggestions

- Move dirty notifications to a central occupancy mutation layer, ideally `WorldGrid` events or explicit hooks at every known mutation site.
- At minimum, add hooks in:
  - `ActionResolver.resolveMove`
  - `DeathFinalizer`
  - composite collapse paths
  - reproduction/offspring placement if they occupy cells
  - any direct `setEntity`, `clearEntity`, or `trySetEntity` call site
- Replace `cellStatusCacheView()` map reads with a thread-safe snapshot/array, or compute constraint 2/3 directly from `WorldGrid` under a read lock.
- Add explicit startup ordering, for example `@DependsOn("rockGenerator")`, or rebuild the index after rock generation deterministically.
- Make the integration determinism test drive the actual registration handler or WS path.
- Add an invariant test: after a scripted sequence of move/death/register, index eligibility matches a full recomputation.

## Risk Assessment

**HIGH.** The sparse-set idea is solid, but stale-index risk is central to correctness. This plan should not proceed until the maintenance contract covers all occupancy mutations.

---

# Plan 19-02 — Live Entity Registry

## Summary

The registry is a useful prerequisite for entity-list iteration, and separating lifecycle wiring from consumer refactors is good sequencing. The plan correctly identifies moves as a hard requirement. The main weakness is the O(1) swap-and-pop structure conflicts with the stated deterministic insertion-order requirement after removals, which may matter for semantic equivalence. It also risks missing lifecycle paths unless every entity creation/removal site is audited mechanically.

## Strengths

- Good separation: registry first, consumers later.
- Includes registration, death, movement, and composite-collapse lifecycle hooks.
- Explicitly avoids coupling `BotRegistry` to `LiveEntityRegistry`.
- `updatePosition` in `ActionResolver` is correctly marked non-deferrable.
- Snapshot-copy iteration is the right shape for tick-handler stability.

## Concerns

- **HIGH:** Swap-and-pop does not preserve insertion order after removal. The plan documents this, but still claims deterministic insertion-order iteration.
- **HIGH:** Composite/member lifecycle is complex. The plan relies on executor source inspection, which is fine, but acceptance criteria only grep for references and may miss a real lifecycle gap.
- **MEDIUM:** Registry stores only `entityId` and `position`; if entity IDs change/remap during bonding/collapse, hooks must be exact or Plan 04 will silently skip/duplicate entities.
- **MEDIUM:** Concurrency model is mixed: WS registration can mutate while tick handlers snapshot. `synchronized` protects registry state, but does not coordinate with `WorldGrid` mutation order.
- **LOW:** `concurrentRegisterIsSafe` may imply broader thread-safety than the simulation actually needs.

## Suggestions

- Choose one deterministic order explicitly:
  - maintain insertion order with remove-by-index shifting, accepting O(N) removal, or
  - keep swap-and-pop but sort snapshots by `entityId` for all semantic-sensitive consumers.
- Add a full-grid consistency assertion test:
  - scan `WorldGrid`
  - compare live non-rock/non-nutrient entities against `LiveEntityRegistry.snapshot()`
  - run after register, move, death, bonding/collapse.
- Add a single lifecycle audit task using `rg "trySetEntity|setEntity|clearEntity|unregisterByEntity|register\\("`.
- Clarify whether offspring/composite members without sessions belong in the registry. The plan says yes, but registration hooks may only cover bot-bound entities.

## Risk Assessment

**MEDIUM.** The registry shape is useful, but deterministic ordering and lifecycle completeness need a tighter contract before Plan 04 depends on it.

---

# Plan 19-03 — Golden Trace Equivalence

## Summary

This is the best architectural safeguard in the phase. Pinning a digest before Plan 04 is exactly the right idea. However, the proposed capture point may be asynchronous and cross-session ordering may be nondeterministic, making the test flaky or misleading. The test scenario also appears under-specified: direct `BotRegistry.register` may not produce actual outbound frames unless real sessions and sender queues are wired.

## Strengths

- Correctly avoids the weak “run twice after refactor” trap by pinning a pre-refactor digest.
- Captures wire bytes instead of internal objects.
- Places the equivalence gate before Plan 04, which is the right dependency.
- Leaves metric equivalence to Phase 21, which is a reasonable scope choice.
- Listener defaults to null, limiting production impact.

## Concerns

- **HIGH:** Cross-session send order can vary because `OutboundSender` uses per-session virtual-thread queues. A global ordered digest may be flaky.
- **HIGH:** Directly registering fake sessions may not exercise real outbound sending if `TickBroadcaster` requires open `WebSocketSession` objects.
- **MEDIUM:** Adding a production listener seam to `OutboundSender` for tests is acceptable, but it must not run under session locks if listener work can block.
- **MEDIUM:** “Listener fires inside synchronized(session)” conflicts with “do not hold lock during listener” guidance. Capturing inside the lock improves ordering but increases risk.
- **MEDIUM:** Digest pinning can become a maintenance burden if benign frame ordering changes occur.
- **LOW:** Printing baseline digest in a permanent test is noisy.

## Suggestions

- Prefer per-session digests sorted by session ID over one global emit-order digest.
- If global ordering is required, capture logical enqueue order at `offer`, not asynchronous send completion order.
- Use real test WebSocket sessions if the goal is actual wire output; otherwise document that this is a tick-frame enqueue equivalence test.
- Make the listener very lightweight and never call user code while holding a session lock. A safe pattern is to copy `(sessionId, bytes)` into a thread-safe queue after send, then digest deterministically in the test.
- Add `emitCount > 0` and expected session count assertions.
- Fail fast if `EXPECTED_DIGEST` is still placeholder.

## Risk Assessment

**MEDIUM.** The concept is strong, but the async ordering and test-session realism need refinement to avoid a flaky or toothless gate.

---

# Plan 19-04 — Entity-List Iteration

## Summary

The scope is appropriate: consume the registry in per-entity paths while leaving spatial diffusion and nutrient spawning alone. The main risk is semantic drift. Replacing row-major grid scans with registry order can change RNG consumption effects, interaction ordering, death ordering, and broadcast ordering. The golden trace should catch this, but only if Plan 03 is robust. The plan also underestimates the importance of order before `Collections.shuffle`: Java shuffle is deterministic for a given input order and RNG, so changing pre-shuffle order can change the shuffled order even with the same size.

## Strengths

- Correctly preserves spatial grid-walks for diffusion and nutrient spawning.
- Keeps `@Order` chain unchanged.
- Explicitly blocks parallelism in Phase 19.
- Uses Plan 03 as a semantic gate.
- Correctly preserves `STALLED` skip, death broadcast, and outbound queue paths.

## Concerns

- **HIGH:** The statement “shuffle randomizes regardless, digest stays stable if list size is same” is false. Same RNG + same size but different initial list order can produce different final order.
- **HIGH:** Registry order differs from row-major grid order. Any unshuffled logic will change behavior unless snapshots are sorted to match previous scan order.
- **HIGH:** If Plan 02 registry position updates are incomplete, this plan will process stale positions.
- **MEDIUM:** The remaining-loop grep cap is brittle. It may either miss an in-scope loop or reject legitimate spatial loops.
- **MEDIUM:** EnvironmentEngine cache writeback refactors are risky because mixed cell-status/entity-status caches often have subtle ordering assumptions.
- **LOW:** Sorting by entity ID may produce deterministic output but not row-major-equivalent output.

## Suggestions

- For semantic equivalence, sort snapshots by previous grid scan order before replacing grid scans:
  ```java
  entries.sort(Comparator
      .comparingInt((EntityEntry e) -> e.position().x())
      .thenComparingInt(e -> e.position().y()));
  ```
  Use entity ID only as a tie-breaker if needed.
- For methods that previously built lists by grid order and then shuffled, preserve row-major pre-shuffle order.
- Add helper methods like `livePositionsMatching(Predicate<Entity>)` to avoid repeated subtle implementations.
- Before Plan 04, add a registry-vs-grid invariant test and run it inside the golden scenario.
- Treat any `EXPECTED_DIGEST` mismatch as a design issue, not something to re-pin.

## Risk Assessment

**MEDIUM-HIGH.** The plan is necessary and well scoped, but ordering equivalence is fragile. Preserving row-major ordering from registry snapshots is likely required.

---

# Cross-Plan Dependency Review

## Strengths

- Plan order is broadly correct:
  - 19-01 placement
  - 19-02 registry lifecycle
  - 19-03 equivalence gate
  - 19-04 registry consumers
- Plan 03 correctly blocks Plan 04.
- Plan 02 correctly blocks Plan 04.
- The Phase 19 / 19.1 split is prudent.

## Main Cross-Plan Gaps

- **HIGH:** `EligibleCellIndex` maintenance should be coordinated with Plan 02 lifecycle hooks. Right now Plan 02 wires `LiveEntityRegistry`, but not necessarily `EligibleCellIndex`, at death/move/collapse sites.
- **HIGH:** Ordering equivalence is not consistently defined. Registry insertion order, swap-and-pop order, row-major scan order, entity-ID sort order, and async send order are all different.
- **MEDIUM:** Plan 01 and Plan 02 both modify `WorldWebSocketHandler` constructors. Sequencing avoids merge conflict, but test constructors/null forwarding may create null-path bugs.
- **MEDIUM:** The plans rely heavily on grep acceptance criteria. Add behavioral invariant tests where possible.

## Top Recommended Changes Before Execution

1. Define one canonical iteration order for equivalence. For replacing grid scans, use row-major position order unless there is a strong reason not to.
2. Add a central “index dirty notification” audit for every occupancy mutation.
3. Make `EligibleCellIndex` cache reads thread-safe or self-contained.
4. Change golden trace digesting to per-session deterministic digests, or capture enqueue order synchronously.
5. Add registry/index consistency tests against a full `WorldGrid` recomputation.

## Final Risk Assessment

**Overall: MEDIUM-HIGH.**

The plans can achieve the Phase 19 goal, but only if lifecycle synchronization and ordering semantics are tightened. The biggest correctness risks are stale placement eligibility and behavior changes caused by replacing row-major grid scans with registry iteration. The biggest test risk is a flaky or non-representative golden trace due to async send ordering.

---

## OpenCode Review

## Phase 19 Plan Review

### 1. Summary

The four-plan structure (Placement Index → Live Registry → Golden Gate → Entity-List Refactor) is a disciplined, layered attack on SCALE-06 and SCALE-07. The wave dependencies (01 → 02 → 03 → 04) correctly sequence constructor-conflicting changes and establish a determinism oracle before the risky tick-handler refactor lands. The scope is appropriately tight—placement is limited to `r|` ingress, entity-list iteration defers parallelism to Phase 19.1, and spatial diffusion stays grid-bound. The research artifacts are thorough and the honesty assessment about modest performance win at 256 bots (architectural, not purely performance-driven) lends credibility.

### 2. Strengths

- **Excellent sequencing.** Plan 03 (Golden Trace) pins a SHA-256 baseline *before* Plan 04 mutates the tick pipeline. This prevents the "refactor agrees with itself" trap.
- **Honest performance framing.** Research explicitly states the 256-bot grid-scan overhead is only ~1.2% of the 500 ms tick budget; the real payoff is enabling Phase 19.1 parallelism and scaling beyond the admission cap.
- **Strong concurrency hygiene.** D-08/D-11 single-threaded mutation invariant is repeated as an acceptance gate in every plan; `parallelStream()` is explicitly banned in acceptance criteria.
- **Defensive design for placement.** Constraint-3 (would-cause-overcrowding) is a thoughtful refinement that prevents cascade penalties; dirty-bbox maintenance avoids the O(65k) full-rebuild cost.
- **Clear backlog separation.** Phase 19.1 for read-only parallelism and 999.x for conflict-graph dispatch prevent scope creep while preserving the user's proposals.

### 3. Concerns

- **HIGH — GoldenTraceEquivalenceTest cross-session emit determinism.** The test assumes fixed seed + single-threaded driver yields deterministic interleaving of per-session virtual-thread drain loops. Virtual threads are scheduled on platform thread pools; cross-session emit order is **not** guaranteed to be stable across two JVM runs even with identical simulation seeds. The plan acknowledges this as a "fallback" to per-session digests, but relying on JVM scheduler stability as the primary gate is brittle. If the test flakes in CI, the Plan 04 merge gate loses trust.
- **MEDIUM — `LiveEntityRegistry.snapshot()` order stability after removals.** The registry uses swap-and-pop `unregister`, which mutates iteration order after the first death. The plan claims insertion-order determinism holds because WS arrival is serialized, but death order is simulation-dependent. If two runs have particles die in a different order (e.g., due to `Collections.shuffle` altering combat pairings), the dense array order diverges. Plan 04's TickBroadcaster task notes a *reactive* `sort(Comparator.comparing(...))` fix, but this should be the default behavior in Plan 02 to avoid digest surprises.
- **MEDIUM — `EligibleCellIndex.evaluateEligibility` allocates 25 `unmodifiableMap` wrappers per event.** Calling `environmentEngine.cellStatusCacheView()` inside the 5×5 `notifyChanged` loop creates transient `Collections.unmodifiableMap` objects for every dirtied cell. At high event rates this is minor GC pressure that is trivially avoidable by hoisting the view reference above the loop.
- **MEDIUM — Plan 19-03 `driveScenario()` placeholder is undercooked.** The task sketches a test driver that places bots at hardcoded `(i*3, i*3)` and "seeds" them manually, bypassing the actual `handleRegister` and `EligibleCellIndex` integration. If the baseline digest is captured against a toy scenario that doesn't exercise the full tick pipeline (e.g., no real WS sessions, no `OutboundSender` queues), it may miss regressions in the actual broadcast path. The test needs real sessions or at a minimum must drive registration through `WorldWebSocketHandler.handleRegister` so the post-Plan-04 refactor exercises the same code surface.
- **MEDIUM — `EligibleCellIndex` holds its monitor while calling `WorldGrid` and `EnvironmentEngine`.** A WS inbound thread acquires `EligibleCellIndex`'s monitor inside `notifyChanged`, then calls `worldGrid.getCell()` (which acquires the grid read lock). If a tick thread ever needs the reverse order (grid write lock → EligibleCellIndex monitor), this is safe *today* because tick threads don't touch the index. However, this ordering is an implicit contract that future maintainers could violate. A brief comment in the source would mitigate this.
- **LOW — Plan 19-04 SimulationEngine acceptance criteria miscounts grid loops.** Research (A2) claims 9 grid scans; PATTERNS.md lists 8 line numbers; Plan 04 says "7 in-scope sites." The discrepancy is minor and acceptance criteria use `grep` bounds (`≤ 4` remaining loops), but the planner should reconcile the count to ensure nothing is missed.

### 4. Suggestions

1. **Guarantee deterministic snapshot order in Plan 02.** Change `LiveEntityRegistry.snapshot()` to sort by `entityId` before returning the shallow copy, or maintain a `LinkedHashMap`/`TreeMap` for stable ordering. This removes the reactive sort hack from Plan 04 and makes the golden trace inherently more stable.
2. **Use per-session digests as the primary gate in Plan 03.** Capture a `Map<String, byte[]>` of per-session SHA-256 digests and assert map equality across runs. This eliminates cross-session emit-order flakiness entirely. A secondary global digest can be kept for convenience.
3. **Hoist `cellStatusCacheView()` in `EligibleCellIndex.notifyChanged`.** Cache the map reference before the 5×5 loop to avoid 25 temporary wrapper allocations per event.
4. **Drive Plan 03's scenario through real WS test clients.** Model `driveScenario()` after `HundredBotIntegrationTest`'s `BlockingWebSocketClient` loop rather than direct `worldGrid.trySetEntity` calls. The baseline must include the full `OutboundSender` queue/drain path to be a valid oracle for Plan 04.
5. **Add an `EligibleCellIndex` gauge to `AdmissionMetrics` (or a new `ScaleMetrics` bean).** Emitting `eligibleCount` as a Micrometer gauge would give immediate observability during Phase 21 dense-run benchmarks without requiring new work later.
6. **Document the lock-ordering invariant.** Add a code comment in `EligibleCellIndex` noting that callers typically hold the `WorldGrid` write lock before entering synchronized methods, and that tick threads must not call into the index while holding the grid write lock.

### 5. Risk Assessment

**Overall: MEDIUM**

The plans are architecturally sound and well-scoped, but the **MEDIUM** rating stems from the fragility of the primary verification gate. If the `GoldenTraceEquivalenceTest` flakes due to virtual-thread scheduling jitter or non-deterministic `ConcurrentHashMap` iteration order in unrefactored code, Plan 04 loses its safety net. Additionally, the `LiveEntityRegistry` swap-and-pop ordering introduces a subtle source of cross-run divergence that the test is specifically designed to catch—meaning the gate could fail for infrastructure reasons rather than real regressions. The actual refactor logic ( Plans 01, 02, 04 ) is low-risk; the risk is concentrated in the test harness that guards it. Fixing the gate design *before* pinning the digest will lower the overall risk to **LOW**.

---

## Consensus Summary

All four reviewers rate the architectural shape **directionally sound** but converge on the same execution-time blast points: incomplete index/registry maintenance hooks, semantic-equivalence harness fragility, and iteration-order drift across the Plan 04 cut.

### Agreed Strengths

- **Wave sequencing 19-01 → 19-02 → 19-03 → 19-04 is correct.** Capturing the EXPECTED_DIGEST before the tick-handler refactor (Plan 03 before Plan 04) avoids the "refactor agrees with itself" trap. (gemini, claude, codex, opencode)
- **Sparse-set / dense-array choice for `EligibleCellIndex` and `LiveEntityRegistry`** is the appropriate ECS-style data structure for O(1) sample/add/remove. (gemini, claude, codex, opencode)
- **D-08/D-11 single-threaded mutation invariant preserved**, with `parallelStream` explicitly banned via grep gates; deferral of read-only parallelism to Phase 19.1 keeps blast radius small. (claude, codex, opencode)
- **`ActionResolver.resolveMove` flagged as a hard requirement in Plan 02** prevents the stale-position trap that would otherwise leak into Plan 04. (claude, opencode)
- **Plans avoid new dependencies and new wire-protocol surface.** (gemini, codex)

### Agreed Concerns (priority order — all four reviewers flagged unless noted)

1. **HIGH — `EligibleCellIndex.notifyChanged` hooks are incomplete (Plan 19-01).** Plan wires only `WorldWebSocketHandler.handleRegister` success path. Movement (`ActionResolver.resolveMove`), death (`DeathFinalizer.finalize*`), composite collapse/formation/dissolve, reproduce, and bonding all mutate occupancy without notifying the index. Result: stale eligibility set drifts from `WorldGrid`, producing false `GRID_FULL` under churn. *(All four reviewers — gemini H, claude H1, codex HIGH, opencode noted via lifecycle audit.)*

2. **HIGH — `GoldenTraceEquivalenceTest` driveScenario likely produces a vacuous baseline (Plan 19-03).** Synthetic `BotRegistry.register` without real `SessionRegistry` / `OutboundSender` wiring means `TickBroadcaster.onTick` skips every iteration (open-session check). Digest = SHA-256(""). Acceptance regex `[0-9a-f]{64}` matches the empty-digest, so EXPECTED_DIGEST passes by silently encoding "no frames sent." Plan 04 then refactors against a toothless gate. *(claude H4, codex HIGH, opencode MEDIUM, gemini implicit via "GoldenTraceCapture isolation".)*

3. **HIGH — `LiveEntityRegistry` iteration-order determinism is unstable (Plans 19-02 / 19-04).** Swap-and-pop unregister mutates dense-array order after the first death; `TickBroadcaster` previously iterated `ConcurrentHashMap.values()` whose order also differs from insertion order. The two orders almost certainly diverge across the Plan 04 cut → digest mismatch. Plan 04 currently treats sort-by-entityId as a *reactive* fix; reviewers want it baked-in as the canonical order. *(All four — gemini H, claude M3, codex HIGH, opencode MEDIUM.)*

4. **HIGH — Cross-session emit-order non-determinism in golden trace (Plan 19-03).** Per-session virtual-thread drain loops mean global SHA-256 over emit-order is sensitive to JVM scheduling. Reviewers recommend either per-session digests sorted by sessionId, OR capture at `offer()` (synchronous enqueue order) instead of post-send. *(claude H4 paths, codex MEDIUM, opencode HIGH.)*

5. **HIGH — `EligibleCellIndex` `@PostConstruct` ordering vs `RockGenerator` is undefined.** Sibling `@PostConstruct` order is unspecified by Spring; if `EligibleCellIndex.initialize` fires before `RockGenerator`, rock cells get added to the eligible set permanently. Fix: `@DependsOn("rockGenerator")` or constructor-inject. *(gemini implicit, claude H2, codex HIGH.)*

6. **MEDIUM — Cross-thread read of `EnvironmentEngine.cellStatusCacheView()` from WS threads.** WS handler thread reads the cache that the tick thread rebuilds every tick; the per-event `Collections.unmodifiableMap` allocation also adds GC churn inside a 5×5 loop. *(codex HIGH, opencode MEDIUM.)*

7. **MEDIUM — Acceptance grep gates are loose.** `Collections.shuffle(.*simRng) >= 3` should be `== 3` (RNG consumption regression silently passes if a fourth shuffle is added); remaining-grid-loop bound `≤ 4` permits up to three unrefactored sites. *(claude M4 / M5, codex implicit "behavioral invariant tests where possible".)*

8. **MEDIUM — Plan 19-02 misses entity-id-introducing sites.** BondFormation, CompositeFormation, ReproducerBud, dissolveToParticles, revertToBondedPair, executeCompositeMovement all create or remap entityIds; without matching `register/unregister/updatePosition` the registry and grid diverge. *(claude H3, codex HIGH.)*

9. **MEDIUM — `DeathFinalizer` constructor-signature change collides with the back-compat 9-arg `SimulationEngine` ctor.** Plan 02 doesn't address it; will fail to compile or instantiate a registry-less DeathFinalizer in tests. *(claude M6.)*

10. **MEDIUM — Plan 19-01 `PlacementDeterminismTest` bypasses `handleRegister`,** testing the index but not the D-06 contract ("registration arrival order → identical placements"). *(claude M2, codex MEDIUM.)*

### Divergent Views

- **Overall risk:** gemini=MEDIUM, opencode=MEDIUM, codex=MEDIUM-HIGH, claude=MEDIUM-HIGH. The split is mostly about how much weight to give the harness-fragility risk (claude/codex more pessimistic on H4) versus the architectural soundness (gemini/opencode more optimistic). All four agree the fixes are **localised, not architectural** — no plan needs to be redesigned.

- **Where to centralise occupancy notifications:** opencode/codex suggest a `WorldGrid`-internal `GridMutationListener` callback (one place, automatic propagation); claude proposes enumerating every site in Plan 02; gemini proposes consolidating Plans 01 and 02 hooks since they fire at the same sites. The `GridMutationListener` route is the lowest-coupling option for future plans but adds Spring-awareness to `WorldGrid`.

- **Listener placement inside vs outside `synchronized(session)` (Plan 03):** opencode flags as MEDIUM lock-extension risk; claude rates LOW (sub-µs digest update); codex says "must not run under session locks" and proposes capturing `(sessionId, bytes)` into a thread-safe queue post-send for offline digesting. The codex pattern is the safest of the three.

### Recommended Pre-Execution Fix List (all reviewers agree)

1. Plan 01: wire `notifyChanged` at every `WorldGrid.{setEntity, clearEntity, trySetEntity}` site (or hook inside `WorldGrid` itself).
2. Plan 01: add `@DependsOn("rockGenerator")` (or constructor-inject) to lock `@PostConstruct` order.
3. Plan 02: enumerate and wire every entityId-introducing site (bonding, composite formation/dissolve/revert, reproduce, executeCompositeMovement).
4. Plan 02: choose canonical iteration order — either insertion-stable removal OR `sort(Comparator.comparing(EntityEntry::entityId))` baked into snapshot.
5. Plan 02: update the back-compat 9-arg `SimulationEngine` constructor for the new `DeathFinalizer` signature.
6. Plan 03: drive the scenario through real WS clients (à la `HundredBotIntegrationTest`) OR inject mock-but-registered sessions; add `assertThat(capture.emitCount()).isGreaterThan(0)` and an "EXPECTED_DIGEST is not the empty-digest" gate.
7. Plan 03: prefer per-session digests OR capture at `offer()` enqueue rather than post-send to remove virtual-thread scheduling jitter.
8. Plan 03: confirm `tickEngine.runOneTick()` (or explicit `applicationEventPublisher.publishEvent(new TickEvent(t))`) drives the full `@Order` chain.
9. Plan 04: bake `entries.sort(Comparator.comparing(...))` into the canonical replace-pattern; tighten grep gates to `Collections.shuffle(.*simRng) == 3` and remaining `for (int .. = 0` ≤ 1 (or ≤ 2 with explicit allowlist).
10. Plan 01: replace `cellStatusCacheView()` map reads with a thread-safe snapshot or compute eligibility under `WorldGrid` read lock; hoist the view reference outside the 5×5 loop.

### Next Step

Feed back into planning:

```
/gsd-plan-phase 19 --reviews
```
