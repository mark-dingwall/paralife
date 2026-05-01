---
phase: 19-high-density-placement-partition-aware-world-execution
verified: 2026-05-01T00:00:00Z
status: passed
score: 4/4
overrides_applied: 0
---

# Phase 19: High-Density Placement & Partition-Aware World Execution — Verification Report

**Phase Goal:** Introduce scale-path world execution and placement mechanics that keep dense runs fair, reproducible, and semantically equivalent to current behavior.
**Verified:** 2026-05-01
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | High-density runs avoid pathological spawn collision patterns against rocks and occupied cells | VERIFIED | `EligibleCellIndex` sparse-set enforces 3-constraint eligibility (no occupant, not OVERCROWDED, no adjacent overcrowding); `PlacementDensityIntegrationTest` fills 8×8 grid and asserts `E\|503\|GRID_FULL` with no retry storm |
| 2 | World execution gains a partition-aware path that can grow without changing observed simulation semantics | VERIFIED | `SimulationEngine` 7 sites + `EnvironmentEngine` 2 sites replaced with `LiveEntityRegistry.snapshot()` (O(N)); `TickBroadcaster` intentionally NOT migrated (CONSENSUS-H1 OPTION B, user-locked) |
| 3 | Current simulation semantics remain stable at existing milestone workloads | VERIFIED | `GoldenTraceEquivalenceTest` dual-run 30-bot 16×16 200-tick gate; pinned 26-session SHA-256 baseline in `golden-trace-phase19.json`; `shuffle` count = 3, nested-loop count = 2 (M4/M5 acceptance gates green) |
| 4 | Deterministic or explainable placement behavior exists for repeated large runs | VERIFIED | `PlacementDeterminismTest` asserts bit-exact same-seed same-grid-state → identical positions; row-major sort in `snapshot()` preserves deterministic entity ordering across Plan 04 cut |

**Score:** 4/4 truths verified

---

## Required Artifacts

| Artifact | Status | Evidence |
|----------|--------|----------|
| `src/main/java/com/paralife/engine/EligibleCellIndex.java` | VERIFIED | 256 lines; real sparse-set (`dense[]` + `posInDense[]`); O(1) add/remove/sample documented; `@PostConstruct @DependsOn("rockGenerator")` |
| `src/main/java/com/paralife/engine/LiveEntityRegistry.java` | VERIFIED | 169 lines; `ArrayList<EntityEntry>` + `HashMap<String,Integer>` id→index; row-major sort in `snapshot()`; `IllegalStateException` on conflict |
| `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` | VERIFIED | 383 lines; dual-run D-10 gate; asserts `mapA == mapB == EXPECTED_DIGESTS`; `emitCount > 0`; bond/composite formation > 0 |
| `src/test/resources/golden-trace-phase19.json` | VERIFIED | 26 pinned SHA-256 digests (trace-sess-0 through trace-sess-25) |
| `src/test/java/com/paralife/engine/GoldenTraceCapture.java` | VERIFIED | Per-session SHA-256 accumulator; thread-safe `onEmit` |
| `src/test/java/com/paralife/engine/EntityListIterationTest.java` | VERIFIED | 118 lines; Mockito spy verifies `registry.snapshot()` called at least once per tick per engine |
| `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` | VERIFIED | Exists; D-06 bit-exact seed contract |
| `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` | VERIFIED | Exists; GRID_FULL assertion |
| `src/test/java/com/paralife/engine/LiveEntityRegistryTest.java` | VERIFIED | Exists; 14 unit tests |
| `src/test/java/com/paralife/engine/LiveEntityRegistryInvariantTest.java` | VERIFIED | Exists; 4 lifecycle scenarios |

---

## Key Link Verification

| From | To | Via | Status | Evidence |
|------|----|-----|--------|---------|
| `WorldWebSocketHandler.handleRegister` | `EligibleCellIndex.sample()` | `eligibleCellIndex != null` guard + 3-retry fallback | WIRED | `WorldWebSocketHandler.java:253-256` |
| `WorldWebSocketHandler.cleanupByEntityId` / `cleanupBot` | `EligibleCellIndex.notifyChanged()` | called after `clearEntity` | WIRED | `WorldWebSocketHandler.java:755,799` |
| `SimulationEngine.entitySnapshot()` | `LiveEntityRegistry.snapshot()` | `liveEntityRegistry != null && liveEntityRegistry.size() > 0` guard | WIRED | `SimulationEngine.java:260-261`; 7 call sites |
| `EnvironmentEngine` | `LiveEntityRegistry.snapshot()` | `@Lazy` setter injection + same guard pattern | WIRED | `EnvironmentEngine.java:679,970` |
| `ActionResolver` / `DeathFinalizer` / `SimulationEngine` | `LiveEntityRegistry` lifecycle hooks | 13 structural mutation sites | WIRED | Confirmed across all 4 hook-bearing files |
| `OutboundSender.drainLoop` | `FrameEmitListener.onEmit()` | inside `synchronized(session)` after `sendMessage` | WIRED | `OutboundSender.java:218,223` |
| `TickEngine` | `ApplicationReadyEvent` | `@EventListener(ApplicationReadyEvent.class)` | WIRED | `TickEngine.java:60` — Wave-1 hotfix confirmed |
| `TickBroadcaster` | `LiveEntityRegistry` | intentionally NOT wired | PASSED (OPTION B) | CONSENSUS-H1 OPTION B user-locked; Javadoc at `LiveEntityRegistry.java:21` confirms |

---

## Data-Flow Trace (Level 4)

Not applicable — this phase produces engine infrastructure (sparse-sets, registries, test seams), not components that render dynamic data to users.

---

## Behavioral Spot-Checks

| Behavior | Check | Status |
|----------|-------|--------|
| `EligibleCellIndex` is a real sparse-set | `grep "dense\[\|posInDense"` — both arrays present | PASS |
| `LiveEntityRegistry.snapshot()` sorts row-major | `grep "rowMajorComparator\|x\*height"` — sort present at `snapshot()` | PASS |
| `TickBroadcaster` not consuming `LiveEntityRegistry` | `grep "LiveEntityRegistry" TickBroadcaster.java` — only Javadoc comment | PASS |
| `TickEngine` uses `ApplicationReadyEvent` not `@PostConstruct` | `grep @EventListener TickEngine.java` — confirmed | PASS |
| Golden trace baseline has 26 sessions | `golden-trace-phase19.json` — 26 entries | PASS |
| SimulationEngine shuffle count = 3 | `grep -c Collections.shuffle SimulationEngine.java` = 3 | PASS |
| SimulationEngine nested loops <= 2 | `grep -cE "for \(int [a-z] = 0"` = 2 | PASS |
| All phase 19 commits present | 18 commits verified via `git log` | PASS |

---

## Requirements Coverage

| Requirement | Description | Status | Evidence |
|-------------|-------------|--------|---------|
| SCALE-06 | O(1) sparse-set placement, bit-exact seed contract | SATISFIED | `EligibleCellIndex` + `PlacementDeterminismTest` + `PlacementDensityIntegrationTest`; REQUIREMENTS.md `[x]` |
| SCALE-07 | LiveEntityRegistry sparse-set + lifecycle hooks, O(N) entity iteration | SATISFIED | `LiveEntityRegistry` + 13 lifecycle hook sites + `EntityListIterationTest` + `GoldenTraceEquivalenceTest`; REQUIREMENTS.md `[x]` |

---

## Anti-Patterns Found

| File | Issue | Severity | Impact |
|------|-------|----------|--------|
| `EnvironmentEngine.java:187` | `entityStatusCache` is a plain `HashMap` (WR-01) — `cellStatusCache` received the volatile+swap fix (CONSENSUS-H4) but `entityStatusCache` did not. `entityStatusCacheView()` returns a live `unmodifiableMap` wrapper. Current tick-thread-only access path is safe today; latent hazard if any future caller reads on a different thread (e.g., Phase 20.1 parallel perception). | Warning | No current data race; fails only if architecture changes |
| `EligibleCellIndex.java:122` | `initialize()` is not `synchronized` (WR-02) — `rebuildForTest()` is synchronized and calls `initialize()`, providing lock coverage from that path. But `@PostConstruct` calls `initialize()` directly without the lock. Spring does not call `@PostConstruct` concurrently on a single bean, so no actual race. Risk is package-private visibility allowing a test to call `initialize()` directly without holding the lock. | Warning | No current correctness impact |
| `SimulationEngine.java:277` | `entitySnapshot()` fallback creates `EntityEntry("_", ...)` sentinel (WR-03) — `processDeaths` uses only `entry.position()` then reads the actual entity from the grid cell, so entity lifecycle hooks receive real entity IDs from the grid, not `"_"`. No ghost entries produced in current code. | Warning | No current impact; logic should be clarified |
| `ActionResolver.java:205` / `TickBroadcaster.java:184` | `clearStateForTest()` methods are public with no production guard (IN-02) — callers could wipe reproduction cooldowns or roster-suppression caches. Risk is low (no production caller). | Info | No production caller exists |
| `CLAUDE.md` Architecture section | Documents `TickEngine.@PostConstruct` but Wave-1 hotfix changed it to `@EventListener(ApplicationReadyEvent.class)` (IN-01). | Info | Doc-only staleness; no code impact |

---

## Human Verification Required

None. All phase 19 success criteria are verifiable programmatically. The manual dense-run sanity check (1000+ bot LoadHarness) is explicitly deferred to Phase 21 in VALIDATION.md.

---

## Deferred Items

| Item | Addressed In | Evidence |
|------|-------------|---------|
| Real-world dense-run benchmark (1000+ bots, tick-health validation) | Phase 21 | Phase 21 SC: "Benchmark runs exist for 100, 500, and 1000+ bots with repeatable commands and saved reports" |
| `TickBroadcaster` O(N) entity-list migration | Phase 20.1+ | CONSENSUS-H1 OPTION B user-locked; ROADMAP Phase 19 Plan 04 note: "TickBroadcaster migration deferred to Phase 20.1+ per H1 Option B" |
| IN-03: Pre-compute single `entitySnapshot()` call per tick instead of 4-5 calls | Phase 21 | Plan 04 SUMMARY notes "Phase 21 pre-optimisation candidate" |

---

## Summary

All 4 phase success criteria are achieved. The two key infrastructure components (`EligibleCellIndex`, `LiveEntityRegistry`) are substantive implementations, fully wired at all structural mutation sites. The D-10 golden-trace equivalence gate is green with a pinned 26-session SHA-256 baseline proving byte-identical outbound encoding before and after the entity-list refactor. SCALE-06 and SCALE-07 are satisfied and marked `[x]` in REQUIREMENTS.md.

Three code-review warnings (WR-01, WR-02, WR-03) from the post-execution review are latent hazards rather than active bugs — none breaks the simulation semantics, the equivalence gate, or the placement contracts at current workloads. They are surfaced here for tracking but do not block phase closure.

---

_Verified: 2026-05-01_
_Verifier: Claude (gsd-verifier)_
