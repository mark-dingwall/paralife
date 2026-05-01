---
phase: 19
plan: 04
type: execute
wave: 4
depends_on: [19-02, 19-03]
files_modified:
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/EnvironmentEngine.java
autonomous: true
requirements:
  - SCALE-07
tags: [tick-pipeline, entity-list, refactor, java, spring-boot]

must_haves:
  truths:
    - "SimulationEngine in-scope grid scans and EnvironmentEngine per-entity segments consume `LiveEntityRegistry.snapshot()` (sorted by ROW-MAJOR linear index per Plan 02 / REVIEWS HIGH-1) instead of full grid scans for the per-entity logic paths."
    - "**TickBroadcaster.onTick is NOT migrated in Phase 19** — it continues to iterate `botRegistry.getAllBots()`. This is the LOCKED USER DECISION per REVIEWS CONSENSUS-H1 OPTION B. Migration is deferred to Phase 20.1+."
    - "EnvironmentEngine diffusion loops (toxin/mutagen CA, lightning, fertility, serialization passes) STAY grid-walk — D-07 explicitly excludes them."
    - "Nutrient spawning (SimulationEngine.processNutrientSpawning, line 1185) STAYS grid-walk — CONTEXT.md 'Not in scope: Nutrient spawn'."
    - "Tick pipeline @Order chain unchanged: 10 → 14 → 20 → 25 → 50 → 100. No handler is split; no @Order is renumbered."
    - "Single-threaded mutation invariant preserved (D-08, D-11) — no parallelStream introduced anywhere."
    - "GoldenTraceEquivalenceTest passes — outbound frame bytes byte-identical (per-session digest map) vs the pinned baseline at `src/test/resources/golden-trace-phase19.json` (REVIEWS MED-1). Expected outcome on first run is GREEN — Plan 02's row-major snapshot order matches the pre-refactor grid-scan order."
    - "RNG-call count for SimulationEngine's `Collections.shuffle(..., simRng)` calls is exactly 3 post-refactor (REVIEWS M4)."
    - "Remaining double-nested grid loops in SimulationEngine.java are bounded to ≤ 2 post-refactor (REVIEWS M5)."
    - "`src/test/resources/golden-trace-phase19.json` is NOT modified by this plan's diff — re-pinning requires operator-approved separate task."
    - "Existing 166+ tests remain green."
  artifacts:
    - path: src/main/java/com/paralife/engine/SimulationEngine.java
      provides: "7 in-scope grid-scan sites refactored to LiveEntityRegistry.snapshot() iteration; nutrient-spawn pass (line 1185) preserved; processOvercrowding neighbour-count walk per entity preserved verbatim. Exactly 3 Collections.shuffle(..., simRng) calls preserved (REVIEWS M4)."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/engine/EnvironmentEngine.java
      provides: "Per-entity segments iterate LiveEntityRegistry.snapshot(); diffusion / lightning / fertility passes unchanged; volatile cellStatusCache (Plan 01) untouched."
      contains: "liveEntityRegistry.snapshot"
  key_links:
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected (Plan 02); snapshot() called at the start of each grid-scan-replacement block; row-major sort intrinsic to snapshot per Plan 02 / REVIEWS HIGH-1"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
    - from: src/main/java/com/paralife/engine/EnvironmentEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected; per-entity segments use snapshot(); diffusion stays grid-walk"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
---

<objective>
Refactor the per-tick **per-entity** iteration in `SimulationEngine` and `EnvironmentEngine` (the per-entity segments only — diffusion stays grid-walk) to consume `LiveEntityRegistry.snapshot()` instead of grid scans.

**TickBroadcaster.onTick is EXPLICITLY OUT OF SCOPE for this plan.** Per REVIEWS CONSENSUS-H1 OPTION B (USER-LOCKED), TickBroadcaster continues to iterate `botRegistry.getAllBots()` in Phase 19. The migration is deferred to a later phase (20.1+) where the BondedPair/CompositeMember session model can be reconciled cleanly. This plan does not touch `TickBroadcaster.java`.

The `GoldenTraceEquivalenceTest` from Plan 03 is the merge gate: this plan's diff is correct iff that test stays green (incl. EXPECTED_DIGESTS loaded from `src/test/resources/golden-trace-phase19.json` per REVIEWS MED-1) AND the full regression suite stays green.

**REVIEWS Round 2 + Round 3 fixes encoded in plan body:**

- **CONSENSUS-H1 OPTION B (USER-LOCKED):** TickBroadcaster.onTick is NOT migrated. The previous Task 2 migrating TickBroadcaster has been REMOVED entirely from this plan. `files_modified` no longer includes `TickBroadcaster.java`. Acceptance grep counters reflect TickBroadcaster's absence: no `liveEntityRegistry.snapshot` count requirement against TickBroadcaster.java; the misleading "skip empty sessionId" code block from prior plan revisions is gone (TickBroadcaster.java is not modified at all).
- **HIGH-1 (consensus):** Plan 02's `LiveEntityRegistry.snapshot()` returns row-major-sorted order matching pre-Plan-04 `for(x){for(y)}` input order; `Collections.shuffle(list, simRng)` output is byte-identical across the cut.
- **MED-1 (claude/opencode):** EXPECTED_DIGESTS loaded from `src/test/resources/golden-trace-phase19.json` (Plan 03); this plan's acceptance verifies the file is present and unchanged.
- **M4 / M5:** Collections.shuffle count tightened to == 3; remaining double-nested loops bounded to ≤ 2.
- **STOP/escalate guard:** if GoldenTraceEquivalenceTest fails on first execution with intra-run determinism (`mapA == mapB`) but cross-cut divergence (`mapA != EXPECTED_DIGESTS`), STOP and escalate. Do NOT modify or delete `golden-trace-phase19.json`.

Purpose: SCALE-07 — replace O(grid-cells) inner loops with O(entities) iteration. Architectural payoff: Phase 19.1 read-only parallelism prerequisite for SimulationEngine + EnvironmentEngine.
Output: 2 files modified (SimulationEngine, EnvironmentEngine); tick @Order chain unchanged; D-08/D-11 invariants preserved; equivalence gate green; TickBroadcaster untouched.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@CLAUDE.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-CONTEXT.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-VALIDATION.md
@.planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md
@src/main/java/com/paralife/engine/SimulationEngine.java
@src/main/java/com/paralife/engine/EnvironmentEngine.java
@src/main/java/com/paralife/engine/LiveEntityRegistry.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/world/WorldGrid.java
@src/test/resources/golden-trace-phase19.json

<interfaces>
<!-- LiveEntityRegistry surface from Plan 02 -->

```java
public List<EntityEntry> snapshot();    // O(N + N log N) — SORTED ROW-MAJOR (REVIEWS HIGH-1)
public record EntityEntry(String entityId, Position position, Optional<String> sessionId) { }
public int size();
```

Pattern for replacing a grid scan that materialises positions of a particular Entity subtype:

```java
// BEFORE — typical pattern at SimulationEngine lines 295–302:
List<Position> particlePositions = new ArrayList<>();
for (int x = 0; x < width; x++) {
    for (int y = 0; y < height; y++) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof Entity.Particle) {
            particlePositions.add(new Position(x, y));
        }
    }
}

// AFTER — entity-list iteration with subtype filter via WorldGrid lookup:
List<Position> particlePositions = new ArrayList<>();
for (LiveEntityRegistry.EntityEntry entry : liveEntityRegistry.snapshot()) {
    Cell cell = worldGrid.getCell(entry.position().x(), entry.position().y());
    if (cell.occupant() instanceof Entity.Particle) {
        particlePositions.add(entry.position());
    }
}
// Critical: do NOT reorder this list before Collections.shuffle(..., simRng).
// snapshot() ORDER IS ROW-MAJOR (REVIEWS HIGH-1) — IDENTICAL to the pre-Plan-04
// for(x){for(y)} input order. Collections.shuffle is deterministic given
// (input order, seed) → shuffled output byte-identical across the cut.
```

**TickBroadcaster (OUT OF SCOPE this plan — REVIEWS CONSENSUS-H1 OPTION B):**
TickBroadcaster.onTick continues to iterate `botRegistry.getAllBots()`. Plan 04 does NOT modify `src/main/java/com/paralife/websocket/TickBroadcaster.java`. The acceptance grep gate explicitly verifies TickBroadcaster is untouched.

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Refactor SimulationEngine in-scope grid scans to LiveEntityRegistry.snapshot() iteration (7 sites; nutrient spawn line 1185 preserved; exactly 3 shuffles preserved)</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 290–310 processInteractions; 415–435 processInteractions composite path; 500–520 BondedPair path; 730–810 processEnergyDecay; 865–905 processOvercrowding; 905–935 processDeaths; 1165–1195 nutrient-spawn — DO NOT REFACTOR)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (Plan 02 — `snapshot()` returns ROW-MAJOR sort)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 192–230 — grid-scan table)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples lines 502–520; §Pitfall 4 — entity-list staleness within tick)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-1; M4; M5)
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (oracle from Plan 03)
    - src/test/resources/golden-trace-phase19.json (pinned baseline — must NOT be modified)
  </read_first>
  <action>
1. **Constructor injection** — `LiveEntityRegistry liveEntityRegistry` field + ctor parameter were already added in Plan 02. Confirm and reuse.

2. **Refactor the 7 in-scope grid scans** (RESEARCH.md A2):

   | Line | Method | Filter |
   |------|--------|--------|
   | 295  | processInteractions | `instanceof Entity.Particle` |
   | 421  | processInteractions | `instanceof Entity.CompositeMember` |
   | 505  | processInteractions | `instanceof Entity.BondedPair` |
   | 735  | processEnergyDecay | mixed Particle / BondedPair branches |
   | 870  | processOvercrowding | `instanceof Entity.Particle \|\| Entity.BondedPair` |
   | 909  | processDeaths Phase 3a | particle/bonded; `if (!isAlive())` preserved |
   | 924  | processDeaths composite half | `instanceof Entity.CompositeMember` |

   **Out of scope:** line 1185 (nutrient spawn) — DO NOT refactor.

   For each in-scope site, replace the double-nested `for (x){ for (y){ } }` with the entity-list iteration pattern shown in `<interfaces>`. Do NOT reorder the resulting list before `Collections.shuffle(..., simRng)`.

3. **Critical invariants the executor MUST preserve verbatim:**

   (a) Three `Collections.shuffle(particlePositions, simRng)` calls (lines 305, 429, 513). **Exactly 3 such calls remain (REVIEWS M4).**

   (b) Row-major pre-shuffle order (REVIEWS HIGH-1) — preserved by Plan 02's snapshot() row-major sort. GoldenTraceEquivalenceTest is expected GREEN on first execution.

   **STOP/escalate (defence-in-depth):** if the test fails with `mapA == mapB` but `mapA != EXPECTED_DIGESTS`, do NOT modify `src/test/resources/golden-trace-phase19.json`. Escalate.

   (c) Death-removal accumulator pattern: do NOT modify `liveEntityRegistry` from inside the iteration loop. Plan 02's DeathFinalizer hook is the authorised mutation site. From SimulationEngine's perspective, the snapshot is read-only.

   (d) `processNutrientSpawning` at line 1185 — UNCHANGED.

   (e) @Order annotations — UNCHANGED.

4. After all 7 in-scope sites are refactored, run the gate command. Full suite + GoldenTraceEquivalenceTest must be green.

   **Failure modes:**
   - `mapA != mapB` (intra-run divergence): list size differs, registry hook missing → fix Plan 02, not this plan.
   - `mapA == mapB` but `mapA != EXPECTED_DIGESTS`: refactor self-consistent but diverged from baseline. Surface to operator. **DO NOT delete or modify `golden-trace-phase19.json`.**
   - Wrong shuffle count → REVIEWS M4 grep gate catches.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest" --tests "com.paralife.engine.SimulationEngine*"</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/SimulationEngine.java` >= 7 (one per refactored site)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -cE "Collections\\.shuffle\\(.*simRng" src/main/java/com/paralife/engine/SimulationEngine.java` == 3 (REVIEWS M4 — EXACT)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/SimulationEngine.java` == 0
    - `grep -c "@Order(10)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1
    - `grep -nA2 "processNutrientSpawning" src/main/java/com/paralife/engine/SimulationEngine.java | head -20` shows nested grid scan still present (NOT refactored)
    - **REVIEWS M5 — bound on remaining double-nested grid loops:**
      `bash -c 'count=$(grep -cE "for *\\(int [a-z] = 0" src/main/java/com/paralife/engine/SimulationEngine.java); test "$count" -le 2'` exits 0
    - **REVIEWS MED-1 — pinned baseline preserved:** `test -f src/test/resources/golden-trace-phase19.json` AND `git diff --name-only -- src/test/resources/golden-trace-phase19.json | wc -l` reports 0 modified within this plan's commits
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (D-10 gate green)
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>SimulationEngine's 7 in-scope grid scans now consume LiveEntityRegistry.snapshot() (row-major sort intrinsic per Plan 02); nutrient-spawn pass preserved; @Order(10) unchanged; exactly 3 Collections.shuffle(..., simRng) calls (REVIEWS M4); remaining double-nested loops ≤ 2 (REVIEWS M5); GoldenTraceEquivalenceTest green; pinned baseline file not modified.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor EnvironmentEngine per-entity segments to LiveEntityRegistry.snapshot() (diffusion / lightning / fertility STAY grid-walk; volatile cellStatusCache from Plan 01 untouched). TickBroadcaster is OUT OF SCOPE per CONSENSUS-H1 OPTION B.</name>
  <files>src/main/java/com/paralife/engine/EnvironmentEngine.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (lines 150–185 fields incl. volatile cellStatusCache from Plan 01; 320–360; 590–660 per-entity env effect; 870–940 buildStatusCaches with entity-status writeback at 894–900, 906–915, 924–936; lines 423–680 diffusion DO NOT REFACTOR; 1215, 1353 fertility / serialization DO NOT REFACTOR)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (snapshot() returns row-major sort)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 232–286)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-1; CONSENSUS-H1 OPTION B — TickBroadcaster excluded)
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (oracle)
    - src/test/resources/golden-trace-phase19.json (pinned baseline — must not be modified)
  </read_first>
  <action>

**This task is the FINAL Plan 04 task. TickBroadcaster.java is NOT touched (REVIEWS CONSENSUS-H1 OPTION B — USER-LOCKED).**

1. **EnvironmentEngine** — Constructor injection: `LiveEntityRegistry liveEntityRegistry` field + ctor parameter. (Plan 02 already added this.)

   Refactor sites:

   (a) **Per-entity env effect application (lines 596–650)** — replace the grid scan that walks for entities-with-effects with `for (var entry : liveEntityRegistry.snapshot()) { ... }`. Read the live entity via `worldGrid.getCell(entry.position().x(), entry.position().y()).occupant()`. Preserve per-entity effect logic verbatim.

   (b) **Entity-status writeback portion of buildStatusCaches (lines 894–900 and 924–936)** — these segments write `entityStatusCache` per live entity. Refactor the per-entity loops to iterate `liveEntityRegistry.snapshot()`. Do NOT touch lines that compute the cell-status (FLAG_OVERCROWDED bit) for non-occupied cells — that's the cell-status path, which writes to `cellStatusStaging` per Plan 01's volatile-snapshot refactor (REVIEWS CONSENSUS-H4) and stays grid-walk.

   (c) **STAYS GRID-WALK:**
       - Lines 423, 437–438 — toxin diffusion CA
       - Lines 532–540 — toxin path generator
       - Lines 563–575 — mutagen diffusion CA
       - Lines 651–680 — lightning per-cell scan
       - Lines 1215, 1353 — fertility / serialization
       - Cell-status (bit 0) computation in buildStatusCaches → still uses `cellStatusStaging.put(...)` per Plan 01

   (d) Preserve `cellStatusCacheView()` at line 1399 (now returns the volatile immutable snapshot from Plan 01) — Plan 01's `EligibleCellIndex` reads it.

2. Run the gate command. Full suite + GoldenTraceEquivalenceTest must be green.

   **STOP/escalate guard:** if `mapA == mapB` but `mapA != EXPECTED_DIGESTS`, do NOT delete or modify `src/test/resources/golden-trace-phase19.json`. Escalate.

3. **TickBroadcaster sanity check (REVIEWS CONSENSUS-H1 OPTION B):**
   ```bash
   git diff --name-only -- src/main/java/com/paralife/websocket/TickBroadcaster.java | wc -l
   ```
   Output MUST be 0 — TickBroadcaster is untouched in Phase 19. If output is >0, REVERT TickBroadcaster.java to the post-Plan-03 state. The migration is deferred per USER-LOCKED OPTION B.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 2 (per-entity effect + entity-status writeback)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 1
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 0
    - `grep -c "@Order(14)" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (REVIEWS L4 — confirm @Order(14) literal; if a named constant is used, adapt grep)
    - `grep -c "cellStatusCacheView" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (Plan 01 volatile snapshot accessor preserved)
    - `grep -c "private volatile Map<Position, Byte> cellStatusCache" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 1 (REVIEWS CONSENSUS-H4 — Plan 01's volatile field still in place)
    - **CONSENSUS-H1 OPTION B — TickBroadcaster UNTOUCHED:**
      `bash -c 'git diff --name-only -- src/main/java/com/paralife/websocket/TickBroadcaster.java | wc -l'` reports 0 within this plan's commits
      `grep -c "liveEntityRegistry" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 0 (TickBroadcaster does not consume LiveEntityRegistry in Phase 19)
      `grep -c "private final LiveEntityRegistry" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 0
      `grep -c "botRegistry.getAllBots" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1 (still uses pre-Phase-19 iteration)
    - `grep -nA3 "toxin diffusion\\|mutagenGrid\\|lightning" src/main/java/com/paralife/engine/EnvironmentEngine.java | grep -c "for (int" >= 1 (diffusion grid-walk preserved)
    - **REVIEWS MED-1 — pinned baseline preserved:** `test -f src/test/resources/golden-trace-phase19.json` AND `git diff -- src/test/resources/golden-trace-phase19.json | wc -l` == 0 within this plan's commits
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>EnvironmentEngine per-entity segments consume LiveEntityRegistry.snapshot(); diffusion / lightning / fertility passes preserved as grid-walks; volatile cellStatusCache from Plan 01 untouched; @Order chain unchanged; D-08/D-11 invariants preserved; **TickBroadcaster.java NOT modified (REVIEWS CONSENSUS-H1 OPTION B — USER-LOCKED)**; GoldenTraceEquivalenceTest green incl. resource-file EXPECTED_DIGESTS (REVIEWS MED-1); full regression green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Tick handlers (read-only on registry) → DeathFinalizer (writes registry) | Same single-threaded execution; no race. |
| TickBroadcaster (read-only on BotRegistry — not migrated in Phase 19) | OPTION B — broadcaster keeps pre-Phase-19 iteration. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation |
|-----------|----------|-----------|-------------|------------|
| T-19-10 | Tampering | Refactored handler emits non-equivalent output | mitigate | GoldenTraceEquivalenceTest from Plan 03 — pinned EXPECTED_DIGESTS resource (REVIEWS MED-1) + emitCount > 0 / no-empty-digest guards. |
| T-19-10a | Tampering | Pre-shuffle order divergence | mitigate | Plan 02 snapshot is ROW-MAJOR sort (REVIEWS HIGH-1). Expected outcome GREEN on first run. |
| T-19-10b | Tampering | TickBroadcaster session lookup drift | accept | OPTION B — TickBroadcaster not migrated in Phase 19; existing behaviour preserved. |
| T-19-11 | DoS | Sort-by-row-major in registry adds per-tick cost | accept | O(N log N) at N≤256 is ~µs. |
| T-19-12 | Information disclosure | snapshot exposes entityIds | accept | Server-internal. |
| T-19-13 | Repudiation | Phase 21 benchmark cannot attribute entity-list win separately | mitigate | Phase 19 / 19.1 split (CONTEXT.md D-12). |
| T-19-14 | Tampering | TickBroadcaster accidentally migrated | mitigate | Acceptance gate `git diff -- TickBroadcaster.java` MUST be empty. |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — D-10 gate green (incl. EXPECTED_DIGESTS, emitCount > 0, no-empty-digest, bond/composite formation count > 0).
- `./gradlew test` — full regression green.
- @Order chain unchanged.
- `grep -rn "parallelStream" src/main/java/com/paralife/engine/` returns no results in any modified file.
- Diffusion / lightning / nutrient-spawn passes still grid-walk.
- Remaining double-nested grid loops in SimulationEngine.java bounded to ≤ 2 (REVIEWS M5).
- Exactly 3 Collections.shuffle(..., simRng) calls in SimulationEngine.java (REVIEWS M4).
- `src/test/resources/golden-trace-phase19.json` not modified by this plan.
- **TickBroadcaster.java is NOT modified** (REVIEWS CONSENSUS-H1 OPTION B — USER-LOCKED).
</verification>

<success_criteria>
- World execution is partition-aware via entity-list iteration in SimulationEngine + EnvironmentEngine: O(N) replaces O(grid-cells) at all in-scope sites.
- Current simulation semantics stable at existing milestone workloads — proven by GoldenTraceEquivalenceTest's pinned EXPECTED_DIGESTS resource file (per-session) plus emitCount > 0 / no-empty-digest / bond-formation-count guards. Row-major sort in Plan 02's snapshot (REVIEWS HIGH-1) makes this the EXPECTED outcome on first execution.
- Tick @Order chain, RNG-call counts, and shuffle calls (exactly 3) all preserved.
- D-08/D-11 single-threaded mutation invariant preserved — no parallelStream introduced.
- TickBroadcaster.onTick remains on `botRegistry.getAllBots()` — broadcaster migration deferred per REVIEWS CONSENSUS-H1 OPTION B (USER-LOCKED).
- Phase 19.1 prerequisite met: SimulationEngine + EnvironmentEngine tick handlers now consume an entity-list, ready for read-only parallel sub-steps in 19.1. TickBroadcaster migration is the first task of Phase 20.1+.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-04-SUMMARY.md`.
</output>
