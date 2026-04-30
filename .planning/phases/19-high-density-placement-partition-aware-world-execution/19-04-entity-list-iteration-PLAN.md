---
phase: 19
plan: 04
type: execute
wave: 4
depends_on: [19-02, 19-03]
files_modified:
  - src/main/java/com/paralife/engine/SimulationEngine.java
  - src/main/java/com/paralife/engine/EnvironmentEngine.java
  - src/main/java/com/paralife/websocket/TickBroadcaster.java
autonomous: true
requirements:
  - SCALE-07
tags: [tick-pipeline, entity-list, refactor, java, spring-boot]

must_haves:
  truths:
    - "SimulationEngine, EnvironmentEngine per-entity segments, and TickBroadcaster.onTick consume LiveEntityRegistry.snapshot() (which sorts by ROW-MAJOR linear index per Plan 02 / REVIEWS HIGH-1) instead of full grid scans for the per-entity logic paths."
    - "EnvironmentEngine diffusion loops (toxin/mutagen CA, lightning, fertility, serialization passes) STAY grid-walk — D-07 explicitly excludes them."
    - "Nutrient spawning (SimulationEngine.processNutrientSpawning, line ~1172) STAYS grid-walk — CONTEXT.md 'Not in scope: Nutrient spawn'."
    - "Tick pipeline @Order chain unchanged: 10 → 14 → 20 → 25 → 50 → 100. No handler is split; no @Order is renumbered."
    - "Single-threaded mutation invariant preserved (D-08, D-11) — no parallelStream introduced anywhere."
    - "GoldenTraceEquivalenceTest passes — outbound frame bytes byte-identical (per-session digest map) vs the pinned baseline at `src/test/resources/golden-trace-phase19.json` (REVIEWS MED-1). The expected outcome on first run is GREEN — Plan 02's row-major snapshot order matches the pre-refactor grid-scan order so `Collections.shuffle(list, simRng)` output is identical across the cut. The STOP/escalate guard remains as defence-in-depth, not the expected outcome."
    - "TickBroadcaster does NOT need a row-major (or any other) re-sort — Plan 03's per-session digest map already neutralises its order-sensitivity. Plan 02's snapshot row-major sort exists for SimulationEngine's pre-shuffle equivalence; TickBroadcaster simply iterates the snapshot in whatever order it arrives (REVIEWS HIGH-1 — TickBroadcaster carve-out)."
    - "TickBroadcaster reads `entry.sessionId().ifPresent(...)` directly per REVIEWS HIGH-3 / Option B (no `botRegistry.getSessionForEntity` lookup; composite/bonded entries with empty sessionId fall through to broadcast-to-all-member-sessions logic)."
    - "Existing 166+ tests remain green."
    - "RNG-call count for SimulationEngine's `Collections.shuffle(..., simRng)` calls is **exactly 3** post-refactor (REVIEWS M4)."
    - "Remaining double-nested grid loops in SimulationEngine.java are bounded to **≤ 2** post-refactor (REVIEWS M5)."
  artifacts:
    - path: src/main/java/com/paralife/engine/SimulationEngine.java
      provides: "7 in-scope grid-scan sites refactored to LiveEntityRegistry.snapshot() iteration; nutrient-spawn pass (line 1172) preserved; processOvercrowding neighbour-count walk per entity preserved verbatim. Exactly 3 Collections.shuffle(..., simRng) calls preserved (REVIEWS M4)."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/engine/EnvironmentEngine.java
      provides: "Per-entity segments iterate LiveEntityRegistry.snapshot(); diffusion / lightning / fertility passes unchanged."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/websocket/TickBroadcaster.java
      provides: "@Order(50) onTick loop iterates LiveEntityRegistry.snapshot() and reads entry.sessionId() directly (REVIEWS HIGH-3); no extra sort (REVIEWS HIGH-1 — TickBroadcaster carve-out); STALLED-skip and outboundSender.offer paths preserved verbatim."
      contains: "liveEntityRegistry.snapshot"
  key_links:
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected (already added in Plan 02); snapshot() called at the start of each grid-scan-replacement block; row-major sort intrinsic to snapshot per Plan 02 / REVIEWS HIGH-1"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
    - from: src/main/java/com/paralife/engine/EnvironmentEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected; per-entity segments use snapshot(); diffusion stays grid-walk"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
    - from: src/main/java/com/paralife/websocket/TickBroadcaster.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected; onTick @Order(50) iterates snapshot() and reads entry.sessionId() directly (Optional<String> from Plan 02 REVIEWS HIGH-3)"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
---

<objective>
Refactor the per-tick **per-entity** iteration in `SimulationEngine`, `EnvironmentEngine`, and `TickBroadcaster.onTick` (the @Order(50) perception broadcast — there is no separate `PerceptionBroadcaster.java`) to consume `LiveEntityRegistry.snapshot()` instead of grid scans.

The `GoldenTraceEquivalenceTest` from Plan 03 is the merge gate: this plan's diff is correct iff that test stays green (incl. EXPECTED_DIGESTS loaded from `src/test/resources/golden-trace-phase19.json` per REVIEWS MED-1) AND the full regression suite stays green.

**Decision (per PATTERNS.md finding 1):** keep `TickBroadcaster.onTick @Order(50)` in-place — no rename, no extraction of a `PerceptionBroadcaster` bean.

**REVIEWS revisions applied (this revision):**

- **HIGH-1 (consensus of all four reviewers — pre-shuffle order):** Plan 02 makes `LiveEntityRegistry.snapshot()` sort by **row-major linear index** `position.x() * height + position.y()`. This matches the pre-refactor `for (int x){ for (int y){ } }` grid-scan input order. `Collections.shuffle(list, simRng)` is deterministic given (input order, seed); preserving row-major input order across the cut preserves shuffle output byte-for-byte → same combat resolution → same deaths → same per-session digests. **The expected outcome on first execution is GREEN** — the STOP/escalate guard below is defence-in-depth only.
- **HIGH-1 TickBroadcaster carve-out:** TickBroadcaster does NOT need any re-sort. Plan 03's per-session digest map already neutralises cross-session emit-order non-determinism, and per-session frame content is byte-identical regardless of which session's frames are computed first. The row-major sort is purely a SimulationEngine pre-shuffle compatibility shim.
- **HIGH-3 (codex/opencode — bonded/composite identity):** Plan 02's `EntityEntry.sessionId()` is `Optional<String>`, populated at registration and propagated. `TickBroadcaster.onTick` reads it directly: `entry.sessionId().ifPresent(...)` for bot-bound entries; empty for composite/bonded grid-occupants (which fan out to broadcast-to-all-member-sessions logic preserving existing semantics). No `botRegistry.getSessionForEntity` lookup needed. No skip hazard from registry/BotRegistry drift.
- **MED-1 (claude/opencode — EXPECTED_DIGESTS):** Plan 03 loads digests from `src/test/resources/golden-trace-phase19.json`; this plan's acceptance verifies the file is present and has not been modified by this plan's diff.
- **M4 (claude):** `Collections.shuffle(.*simRng)` count tightened to `== 3`.
- **M5 (claude):** Remaining double-nested grid loops bounded to `≤ 2`.

**STOP/escalate guard (defence-in-depth):** if `GoldenTraceEquivalenceTest` fails on first execution of this plan with `mapA == mapB` (intra-run determinism holds) but `mapA != EXPECTED_DIGESTS` (cross-plan-cut divergence), STOP and escalate to the operator. Do NOT modify `EXPECTED_DIGESTS` (do NOT delete `golden-trace-phase19.json`) without operator review. Re-pinning requires a separate operator-approved task. Self-healing re-pin paths are explicitly forbidden.

Purpose: SCALE-07 — replace O(grid-cells) inner loops with O(entities) iteration. Architectural payoff: Phase 19.1 read-only parallelism prerequisite.
Output: 3 files modified; tick @Order chain unchanged; D-08/D-11 invariants preserved; equivalence gate green.
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
@src/main/java/com/paralife/websocket/TickBroadcaster.java
@src/main/java/com/paralife/engine/LiveEntityRegistry.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/world/WorldGrid.java
@src/test/resources/golden-trace-phase19.json

<interfaces>
<!-- LiveEntityRegistry surface from Plan 02 (REVIEWS HIGH-1 + HIGH-3) -->

```java
public List<EntityEntry> snapshot();    // O(N + N log N) — SORTED BY ROW-MAJOR (REVIEWS HIGH-1)
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

**Position lookup via the registry:** Plan 02 wires `liveEntityRegistry.updatePosition` at the move site (REVIEWS MED-3 — per-path entityId). Refactored handlers in this plan therefore read the current position directly from `entry.position()`. Do NOT introduce a fallback path that re-reads through `worldGrid.getCell(...)` keyed on entityId; the registry IS the source of truth.

**TickBroadcaster session resolution (REVIEWS HIGH-3):** read `entry.sessionId()` directly:
```java
for (LiveEntityRegistry.EntityEntry entry : liveEntityRegistry.snapshot()) {
    Optional<String> sidOpt = entry.sessionId();
    if (sidOpt.isEmpty()) {
        // composite/bonded grid-occupant — no single owning session.
        // Fall through to broadcast-to-all-member-sessions logic if existing
        // code does so; otherwise skip (server-internal entity, no perception target).
        continue;  // adapt per existing semantics
    }
    String sessionId = sidOpt.get();
    // ... resolve session, build perception, offer to outboundSender
}
```

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Refactor SimulationEngine grid scans to entity-list iteration (7 in-scope sites; 3 shuffles preserved exactly; row-major snapshot order matches pre-refactor grid-scan order)</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 220–235; 290–310; 415–435; 500–520; 730–810; 865–905; 905–935; 1165–1195 nutrient-spawn DO NOT REFACTOR)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean from Plan 02 — `snapshot()` returns ROW-MAJOR sort per REVIEWS HIGH-1)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 192–230 — grid-scan table)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples lines 502–520; §Pitfall 4)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-1 — row-major sort; M4 / M5)
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (oracle from Plan 03 — EXPECTED_DIGESTS in `src/test/resources/golden-trace-phase19.json` per REVIEWS MED-1)
    - src/test/resources/golden-trace-phase19.json (the pinned baseline — must NOT be modified by this plan's diff)
  </read_first>
  <action>
1. **Constructor injection** — `LiveEntityRegistry liveEntityRegistry` field + ctor parameter were already added in Plan 02. Confirm and reuse.

2. **Refactor the in-scope grid scans.** RESEARCH.md A2 lists 8 known double-nested loop sites (295, 421, 505, 735, 870, 909, 924, 1172). Site 1172 is `processNutrientSpawning` — out of scope. Refactor the other **7 in-scope sites** with the entity-list iteration pattern shown in `<interfaces>`.

   In-scope sites:

   | Line | Method | Filter |
   |------|--------|--------|
   | 295  | processInteractions | `instanceof Entity.Particle` |
   | 421  | processInteractions | `instanceof Entity.CompositeMember` |
   | 505  | processInteractions | `instanceof Entity.BondedPair` |
   | 735  | processEnergyDecay | mixed — Particle and BondedPair branches |
   | 870  | processOvercrowding | `instanceof Entity.Particle \|\| instanceof Entity.BondedPair` |
   | 909  | processDeaths Phase 3a | particle/bonded; `if (!isAlive())` preserved |
   | 924  | processDeaths composite half | `instanceof Entity.CompositeMember` |

   The 8th site (1172) is the nutrient spawn — DO NOT refactor.

3. **Critical invariants the executor MUST preserve verbatim:**

   (a) `Collections.shuffle(particlePositions, simRng)` calls (lines 305, 429, 513). **Exactly 3 such calls must remain — REVIEWS M4.**

   (b) **Row-major pre-shuffle order (REVIEWS HIGH-1 — consensus of all four reviewers):** Java `Collections.shuffle` is deterministic given (input order, seed). Plan 02's `LiveEntityRegistry.snapshot()` returns row-major-sorted order — IDENTICAL to the pre-Plan-04 `for (int x){ for (int y){ } }` input order. The shuffled output is therefore deterministic AND identical across the Plan 04 cut. **GoldenTraceEquivalenceTest is expected to pass on first execution.**

   **STOP/escalate (defence-in-depth):** if the test fails with `mapA == mapB` but `mapA != EXPECTED_DIGESTS`, do NOT modify `src/test/resources/golden-trace-phase19.json`. Escalate to the operator.

   (c) Death-removal accumulator pattern: do NOT modify `liveEntityRegistry` from inside the iteration loop. Plan 02's DeathFinalizer hook is the authorised mutation site. From SimulationEngine's perspective, the snapshot is read-only.

   (d) `processNutrientSpawning` at line 1172 — UNCHANGED.

   (e) @Order annotations — UNCHANGED.

4. After all 7 in-scope sites are refactored, run the gate command. The full suite + GoldenTraceEquivalenceTest must be green.

   **Failure modes:**
   - `mapA != mapB` (intra-run divergence): list size differs, or registry is missing a hook → fix Plan 02 (REVIEWS H3 / HIGH-3 audit gap), not this plan.
   - `mapA == mapB` but `mapA != EXPECTED_DIGESTS` (refactor-cut divergence): the refactor produced self-consistent output that differs from the pinned baseline. Surface to operator. **DO NOT delete `src/test/resources/golden-trace-phase19.json`. DO NOT modify EXPECTED_DIGESTS.** This is operator-review territory.
   - Missing `Collections.shuffle` — REVIEWS M4 grep gate catches.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest" --tests "com.paralife.engine.SimulationEngine*"</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/SimulationEngine.java` >= 7 (one per refactored site)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -cE "Collections\\.shuffle\\(.*simRng" src/main/java/com/paralife/engine/SimulationEngine.java` == 3 (REVIEWS M4)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/SimulationEngine.java` == 0
    - `grep -c "@Order(10)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1
    - `grep -nA2 "processNutrientSpawning" src/main/java/com/paralife/engine/SimulationEngine.java | head -20` shows nested grid scan still present (NOT refactored)
    - REVIEWS M5 — bound on remaining double-nested grid loops: `bash -c 'count=$(grep -cE "for *\\(int [a-z] = 0" src/main/java/com/paralife/engine/SimulationEngine.java); test "$count" -le 2'` exits 0
    - **REVIEWS MED-1 — pinned baseline preserved:** `test -f src/test/resources/golden-trace-phase19.json` AND `git diff --name-only -- src/test/resources/golden-trace-phase19.json | wc -l` == 0 within this plan's commits (this plan must NOT modify the pinned digests; operator-review territory)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (D-10 gate green; expected outcome on first execution per REVIEWS HIGH-1 row-major sort)
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>SimulationEngine's 7 in-scope grid scans now consume LiveEntityRegistry.snapshot() (row-major sort intrinsic per Plan 02); nutrient-spawn pass preserved; @Order(10) unchanged; exactly 3 Collections.shuffle(..., simRng) calls preserved (REVIEWS M4); remaining double-nested grid loops bounded ≤ 2 (REVIEWS M5); GoldenTraceEquivalenceTest green incl. EXPECTED_DIGESTS resource-file equality (REVIEWS MED-1 closed); pinned baseline file not modified by this plan's diff.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor EnvironmentEngine per-entity segments + TickBroadcaster.onTick @Order(50) (TickBroadcaster reads entry.sessionId() directly per REVIEWS HIGH-3; no extra sort per REVIEWS HIGH-1 carve-out)</name>
  <files>src/main/java/com/paralife/engine/EnvironmentEngine.java, src/main/java/com/paralife/websocket/TickBroadcaster.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (lines 150–185; 320–360; 590–660 per-entity env effect; 870–940 buildStatusCaches with entity-status writeback at 894–900, 906–915, 924–936; lines 423–680 diffusion DO NOT REFACTOR; 1215, 1353 fertility / serialization DO NOT REFACTOR)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 170–230 — onTick @Order(50); preserve STALLED-skip line 195, outboundSender.offer line 206, drainAndBroadcastDeaths line 235)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (snapshot() returns row-major sort; EntityEntry has Optional<String> sessionId per REVIEWS HIGH-3)
    - src/main/java/com/paralife/engine/BotRegistry.java (`getSessionForEntity(entityId)` line 146 — fallback only if sessionId is empty AND existing code requires it for fan-out)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 232–286)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (HIGH-1 carve-out — TickBroadcaster needs no re-sort; HIGH-3 — sessionId direct read)
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (oracle)
    - src/test/resources/golden-trace-phase19.json (pinned baseline — must not be modified)
  </read_first>
  <action>
1. **EnvironmentEngine** — Constructor injection: `LiveEntityRegistry liveEntityRegistry` field + ctor parameter. (Plan 02 may have already added this.)

   Refactor sites:

   (a) **Per-entity env effect application (lines 596–650)** — replace the grid scan that walks for entities-with-effects with `for (var entry : liveEntityRegistry.snapshot()) { ... }`. Read the live entity via `worldGrid.getCell(entry.position()).occupant()`. Preserve per-entity effect logic verbatim.

   (b) **Entity-status writeback portion of buildStatusCaches (lines 894–900 and 924–936)** — these segments write `entityStatusCache` per live entity. Refactor the per-entity loops to iterate `liveEntityRegistry.snapshot()`. Do NOT touch lines that compute the cell-status (FLAG_OVERCROWDED bit) for non-occupied cells.

   (c) **STAYS GRID-WALK:**
       - Lines 423, 437–438 — toxin diffusion CA
       - Lines 532–540 — toxin path generator
       - Lines 563–575 — mutagen diffusion CA
       - Lines 651–680 — lightning per-cell scan
       - Lines 1215, 1353 — fertility / serialization
       - Cell-status (bit 0) computation in buildStatusCaches

   (d) Preserve `cellStatusCacheView()` at line 1399 — Plan 01's `EligibleCellIndex` reads it.

2. **TickBroadcaster** — Constructor injection: `LiveEntityRegistry liveEntityRegistry` field + ctor parameter.

   Refactor `onTick @Order(50)` at lines 176–223:

   ```java
   // BEFORE (line 185):
   var bots = botRegistry.getAllBots();
   if (bots.isEmpty()) return;
   for (BotRegistry.BotState bot : bots) {
       WebSocketSession session = sessionRegistry.getSession(bot.sessionId());
       if (session == null || !session.isOpen()) continue;
       if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) {
           skipped++;
           continue;
       }
       // ... build perception, offer to outboundSender ...
   }

   // AFTER (REVIEWS HIGH-3 — sessionId direct read; REVIEWS HIGH-1 — no extra sort needed):
   List<LiveEntityRegistry.EntityEntry> entries = liveEntityRegistry.snapshot();
   if (entries.isEmpty()) return;
   for (LiveEntityRegistry.EntityEntry entry : entries) {
       Optional<String> sidOpt = entry.sessionId();
       if (sidOpt.isEmpty()) {
           // Composite/bonded grid-occupant — no single owning session.
           // Existing semantics: fan-out via member-sessions if applicable.
           // For minimum-viable refactor: skip — the BondedPair/CompositeMember
           // currently has no perception path of its own; the per-bot
           // perception is keyed on the bot's session, which is the predator
           // or prey id (those were unregistered from LiveEntityRegistry at
           // bond-formation time per Plan 02). Confirm against existing
           // pre-refactor TickBroadcaster behaviour: did getAllBots() include
           // the BondedPair or only the bot-bound entries? If only bot-bound,
           // skipping empty-sessionId is correct. If both, you must reproduce
           // the fan-out — re-read pre-refactor TickBroadcaster.
           continue;
       }
       String sessionId = sidOpt.get();
       WebSocketSession session = sessionRegistry.getSession(sessionId);
       if (session == null || !session.isOpen()) continue;
       if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) {
           skipped++;
           continue;
       }
       // ... build perception for this entity, offer to outboundSender ...
   }
   ```

   **TickBroadcaster carve-out (REVIEWS HIGH-1):** No reactive `entries.sort(...)` is applied. The row-major sort exists in Plan 02's snapshot() to keep SimulationEngine's pre-shuffle equivalence. TickBroadcaster's per-session frame content is byte-identical regardless of which session is iterated first; the per-session digest from Plan 03's GoldenTraceCapture is insensitive to cross-session order.

   **Preserve verbatim:**
   - `drainAndBroadcastDeaths(event.tickNumber())` at the top of onTick (line ~187)
   - STALLED-skip at line ~195
   - `outboundSender.offer(sessionId, frame)` at line ~206
   - The session-monitor synchronisation contract
   - All metric increments
   - The `@Order(50)` annotation

   **Phase 19.1 boundary (D-12):** DO NOT introduce `parallelStream()`.

3. **Optional but recommended:** if there is a `@Order(100)` broadcast pass that ALSO walks `botRegistry.getAllBots()`, refactor it the same way. Re-grep `botRegistry.getAllBots\\|bySession.values` in TickBroadcaster.java.

4. Run the gate command. Full suite + GoldenTraceEquivalenceTest must be green.

   **STOP/escalate guard:** if `mapA == mapB` but `mapA != EXPECTED_DIGESTS`, do NOT delete or modify `src/test/resources/golden-trace-phase19.json`. Escalate.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 2
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 1
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 1
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 0
    - `grep -c "parallelStream" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 0
    - `grep -c "@Order(50)" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "@Order(14)" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (REVIEWS L4 — confirm @Order(14) literal is present; if a named constant is used, adapt grep)
    - `grep -c "drainAndBroadcastDeaths" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "outboundSender.offer" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "isStalled(session)" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "cellStatusCacheView" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1
    - **REVIEWS HIGH-3 — TickBroadcaster reads sessionId directly, not via BotRegistry lookup:** `grep -cE "entry\\.sessionId\\(\\)" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - **REVIEWS HIGH-1 carve-out — TickBroadcaster does NOT apply an extra sort:** `grep -cE "entries\\.sort|Collections\\.sort\\(entries" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 0
    - `grep -nA3 "toxin diffusion\\|mutagenGrid\\|lightning" src/main/java/com/paralife/engine/EnvironmentEngine.java | grep -c "for (int" >= 1 (diffusion grid-walk preserved)
    - **REVIEWS MED-1 — pinned baseline preserved:** `test -f src/test/resources/golden-trace-phase19.json` AND this plan's diff does NOT modify the file (`git diff -- src/test/resources/golden-trace-phase19.json | wc -l` == 0 within this plan's commits)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0
    - `./gradlew test` exits 0
  </acceptance_criteria>
  <done>EnvironmentEngine per-entity segments and TickBroadcaster.onTick @Order(50) consume LiveEntityRegistry.snapshot() (row-major sort intrinsic per Plan 02 — REVIEWS HIGH-1; no extra TickBroadcaster sort per HIGH-1 carve-out; sessionId read directly from EntityEntry per HIGH-3); diffusion / lightning / fertility passes preserved as grid-walks; @Order chain unchanged; STALLED-skip and outboundSender.offer paths verbatim; D-08/D-11 invariants preserved; GoldenTraceEquivalenceTest green incl. resource-file EXPECTED_DIGESTS (REVIEWS MED-1); full regression green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Tick handlers (read-only on registry) → DeathFinalizer (writes registry) | Same single-threaded execution; no race. |
| TickBroadcaster (read-only on registry) → WS inbound (writes registry on register) | `synchronized(this)` on registry; sub-µs critical section. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-19-10 | Tampering | Refactored handler emits non-equivalent output | mitigate | GoldenTraceEquivalenceTest from Plan 03 — pinned EXPECTED_DIGESTS resource (REVIEWS MED-1) + emitCount > 0 / no-empty-digest guards. |
| T-19-10a | Tampering | Pre-shuffle order divergence | mitigate | Plan 02 snapshot is ROW-MAJOR sort (REVIEWS HIGH-1) — pre-shuffle order identical to pre-Plan-04 grid-scan order; Collections.shuffle deterministic given (input, seed). Expected outcome: GREEN on first run. |
| T-19-10b | Tampering | TickBroadcaster session lookup drift | mitigate | EntityEntry.sessionId() Optional<String> read directly (REVIEWS HIGH-3 / Option B) — no BotRegistry lookup; no skip hazard. |
| T-19-11 | DoS | sort-by-row-major in registry adds per-tick cost | accept | O(N log N) at N≤256 is ~µs. |
| T-19-12 | Information disclosure | snapshot exposes entityIds | accept | Server-internal. |
| T-19-13 | Repudiation | Phase 21 benchmark cannot attribute entity-list win separately | mitigate | Phase 19/19.1 split (CONTEXT.md D-12). |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — D-10 gate green (incl. EXPECTED_DIGESTS resource-file equality per REVIEWS MED-1, emitCount > 0, no-empty-digest guards).
- `./gradlew test` — full regression green.
- @Order chain unchanged.
- `grep -rn "parallelStream" src/main/java/com/paralife/{engine,websocket}/` returns no results in any modified file.
- Diffusion / lightning / nutrient-spawn passes still grid-walk.
- Remaining double-nested grid loops in SimulationEngine.java bounded to ≤ 2 (REVIEWS M5).
- Exactly 3 Collections.shuffle(..., simRng) calls in SimulationEngine.java (REVIEWS M4).
- `src/test/resources/golden-trace-phase19.json` not modified by this plan.
- TickBroadcaster reads `entry.sessionId()` directly (REVIEWS HIGH-3); no extra sort applied (REVIEWS HIGH-1 carve-out).
</verification>

<success_criteria>
- World execution is partition-aware via entity-list iteration: O(N) replaces O(grid-cells) at all in-scope sites.
- Current simulation semantics stable at existing milestone workloads — proven by GoldenTraceEquivalenceTest's pinned EXPECTED_DIGESTS resource file (per-session) plus emitCount > 0 / no-empty-digest guards. Row-major sort in Plan 02's snapshot (REVIEWS HIGH-1) makes this the EXPECTED outcome on first execution.
- Tick @Order chain, STALLED-skip, outboundSender.offer, drainAndBroadcastDeaths, RNG-call counts, and shuffle calls (exactly 3) all preserved.
- D-08/D-11 single-threaded mutation invariant preserved — no parallelStream introduced.
- Phase 19.1 prerequisite met: tick handlers now consume an entity-list, ready for read-only parallel sub-steps in 19.1.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-04-SUMMARY.md`.
</output>
