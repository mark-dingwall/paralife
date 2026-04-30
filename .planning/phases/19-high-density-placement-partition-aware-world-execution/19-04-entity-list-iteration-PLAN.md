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
    - "SimulationEngine, EnvironmentEngine per-entity segments, and TickBroadcaster.onTick consume LiveEntityRegistry.snapshot() instead of full grid scans for the per-entity logic paths."
    - "EnvironmentEngine diffusion loops (toxin/mutagen CA, lightning, fertility, serialization passes) STAY grid-walk — D-07 explicitly excludes them."
    - "Nutrient spawning (SimulationEngine.processNutrientSpawning, line ~1172) STAYS grid-walk — CONTEXT.md 'Not in scope: Nutrient spawn'."
    - "Tick pipeline @Order chain unchanged: 10 → 14 → 20 → 25 → 50 → 100. No handler is split; no @Order is renumbered."
    - "Single-threaded mutation invariant preserved (D-08, D-11) — no parallelStream introduced anywhere in this plan."
    - "GoldenTraceEquivalenceTest passes — outbound frame bytes byte-identical vs the pre-Plan-04 baseline. Specifically, the test's pinned `EXPECTED_DIGEST` constant (set in Plan 03 against the post-Plan-02 codebase) MUST equal the digest produced after this plan's refactor lands. That equality is the operational form of the D-10 promise."
    - "Existing 166+ tests remain green."
    - "RNG-call count for SimulationEngine's `Collections.shuffle(..., simRng)` calls is unchanged — refactor produces lists of identical size and identical entity content, in identical pre-shuffle order."
    - "Remaining double-nested grid loops (`for (int x = 0...; for (int y = 0...`) in SimulationEngine.java are bounded to ≤ 4 — only diffusion / nutrient-spawn / serialization-style passes that the refactor explicitly excluded."
  artifacts:
    - path: src/main/java/com/paralife/engine/SimulationEngine.java
      provides: "8 in-scope grid-scan sites refactored to LiveEntityRegistry.snapshot() iteration; nutrient-spawn pass (line 1172) preserved; processOvercrowding neighbour-count walk per entity preserved verbatim."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/engine/EnvironmentEngine.java
      provides: "Per-entity segments (lines 596–650; entity-status writeback portions of 894–900 and 924–936) iterate LiveEntityRegistry.snapshot(); diffusion / lightning / fertility passes unchanged."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/websocket/TickBroadcaster.java
      provides: "@Order(50) onTick loop iterates LiveEntityRegistry.snapshot() instead of botRegistry.getAllBots(); STALLED-skip and outboundSender.offer paths preserved verbatim."
      contains: "liveEntityRegistry.snapshot"
  key_links:
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected; snapshot() called once per tick at the start of each grid-scan-replacement block"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
    - from: src/main/java/com/paralife/engine/EnvironmentEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected; per-entity segments use snapshot(); diffusion stays grid-walk"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
    - from: src/main/java/com/paralife/websocket/TickBroadcaster.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected; onTick @Order(50) loop replaces botRegistry.getAllBots() with snapshot(); session resolution still via botRegistry.getSessionForEntity(entityId)"
      pattern: "liveEntityRegistry\\.snapshot\\(\\)"
---

<objective>
Refactor the per-tick **per-entity** iteration in `SimulationEngine`, `EnvironmentEngine`, and `TickBroadcaster.onTick` (the @Order(50) perception broadcast — there is no separate `PerceptionBroadcaster.java`) to consume `LiveEntityRegistry.snapshot()` instead of grid scans.

The `GoldenTraceEquivalenceTest` from Plan 03 is the merge gate: this plan's diff is correct iff that test stays green (including the pinned `EXPECTED_DIGEST` assertion) AND the full regression suite stays green.

**Decision (per PATTERNS.md finding 1):** keep `TickBroadcaster.onTick @Order(50)` in-place — no rename, no extraction of a `PerceptionBroadcaster` bean. Smaller blast radius; the in-tree handler is already named correctly for its role; renaming is decoupled work better suited to Phase 19.1 if it ever happens.

Purpose: SCALE-07 — replace O(grid-cells) inner loops with O(entities) iteration. At current 256-entity cap and 256×256 grid, this is a ~50× iteration reduction per tick. The architectural payoff (Phase 19.1 read-only parallelism prerequisite, scaling beyond admission cap) is the strategic motivation — see RESEARCH.md §"Honest Assessment".
Output: 3 files modified; tick @Order chain unchanged; D-08/D-11 invariants preserved; equivalence gate (incl. EXPECTED_DIGEST pin) green.
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
@src/main/java/com/paralife/engine/SimulationEngine.java
@src/main/java/com/paralife/engine/EnvironmentEngine.java
@src/main/java/com/paralife/websocket/TickBroadcaster.java
@src/main/java/com/paralife/engine/LiveEntityRegistry.java
@src/main/java/com/paralife/engine/BotRegistry.java
@src/main/java/com/paralife/world/WorldGrid.java

<interfaces>
<!-- LiveEntityRegistry surface from Plan 02 -->

```java
public List<EntityEntry> snapshot();              // O(N) shallow copy; deterministic insertion order
public record EntityEntry(String entityId, Position position) { }
public int size();
```

Pattern for replacing a grid scan that materialises positions of a particular Entity subtype:

```java
// BEFORE — typical pattern at SimulationEngine lines 295–302:
List<Position> particlePositions = new ArrayList<>();
for (int x = 0; x < width; x++) {
    for (int y = 0; y < height; y++) {
        Cell cell = worldGrid.getCell(x, y);
        if (cell.occupant() instanceof Particle) {
            particlePositions.add(new Position(x, y));
        }
    }
}

// AFTER — entity-list iteration with subtype filter via WorldGrid lookup:
List<Position> particlePositions = new ArrayList<>();
for (LiveEntityRegistry.EntityEntry entry : liveEntityRegistry.snapshot()) {
    Cell cell = worldGrid.getCell(entry.position().x(), entry.position().y());
    if (cell.occupant() instanceof Particle) {
        particlePositions.add(entry.position());
    }
}
// Critical: do NOT reorder this list before Collections.shuffle(..., simRng).
// The simRng call must consume the same number of random bytes as the
// pre-refactor pass for the golden-trace digest to match.
```

The scan order changes (insertion order vs grid row-major), but `Collections.shuffle` randomises the order regardless — the digest stays stable as long as the **list size** is the same. Provided LiveEntityRegistry contains every Particle currently on the grid (Plan 02's lifecycle hooks ensure this), the shuffled output is statistically equivalent and golden-trace-equivalent given the same seed and same input list size.

**Caveat — list size equality requires careful handling of off-grid entities:** A Particle is on the grid iff `WorldGrid.getCell(pos).occupant() == that-particle`. If LiveEntityRegistry is out of sync with the grid (e.g. a Particle was placed but liveEntityRegistry.register was missed), the refactored list size differs from the pre-refactor list size and the golden-trace test fails. This is the regression signal Plan 03 was built to catch.

**Position lookup via the registry:** Plan 02 wires `liveEntityRegistry.updatePosition` at the move site in `ActionResolver.resolveMove` as a **hard requirement**. Refactored handlers in this plan therefore read the current position directly from `entry.position()` — that value is current as of the most recent successful move. Do NOT introduce a fallback path that re-reads through `worldGrid.getCell(...)` keyed on entityId; the registry IS the source of truth. (If during execution the executor finds `liveEntityRegistry.updatePosition` is somehow not wired, that is a Plan 02 regression — fix Plan 02, do not paper over here.)

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Refactor SimulationEngine grid scans to entity-list iteration</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 220–235 — @Order annotations and onTick entry; lines 290–310 — first grid scan in processInteractions; lines 415–435 — composite-member grid scan; lines 500–520 — bonded-pair grid scan; lines 730–810 — processEnergyDecay; lines 865–905 — processOvercrowding; lines 905–935 — processDeaths Phase 3a; lines 1165–1195 — processNutrientSpawning DO NOT REFACTOR)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean from Plan 02)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 192–230 — grid-scan table; lines 207–225 — replace-pattern with shuffle invariant)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples lines 502–520; §Pitfall 4; §Anti-Patterns "Iterating bySession.values() inside a tick handler")
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (the oracle from Plan 03 — note the pinned `EXPECTED_DIGEST` constant; this plan's refactor must NOT change that digest)
  </read_first>
  <action>
1. **Constructor injection** — add `LiveEntityRegistry liveEntityRegistry` field + ctor parameter to `SimulationEngine`. Assign to a `private final` field. (If Plan 02 already added this for the composite-collapse hook, the field exists — confirm and reuse.)

2. **Refactor the in-scope grid scans.** RESEARCH.md A2 lists 8 known double-nested loop sites in SimulationEngine.java (lines 295, 421, 505, 735, 870, 909, 924, 1172). Site 1172 is `processNutrientSpawning` — out of scope per CONTEXT.md. Refactor the other 7 in-scope sites with the entity-list iteration pattern shown in `<interfaces>`. Preserve the entity-type filter (`instanceof Particle`, `instanceof BondedPair`, `instanceof CompositeMember`) — the filter on `worldGrid.getCell(pos).occupant()` is the canonical source of truth for what's currently on that cell.

   In-scope sites (line numbers from RESEARCH.md A2 — re-verify before patching; line numbers may shift after Plan 02 lands):

   | Line | Method | Filter |
   |------|--------|--------|
   | 295  | processInteractions | `instanceof Particle` |
   | 421  | processInteractions | `instanceof CompositeMember` |
   | 505  | processInteractions | `instanceof BondedPair` |
   | 735  | processEnergyDecay | mixed — Particle and BondedPair branches; preserve branch logic |
   | 870  | processOvercrowding | `instanceof Particle \|\| instanceof BondedPair` |
   | 909  | processDeaths Phase 3a | particle/bonded; `if (!isAlive())` predicate preserved |
   | 924  | processDeaths composite half | `instanceof CompositeMember` |

   The 8th in-scope site (1172) is the nutrient spawn — DO NOT refactor it.

   Re-grep `for (int x = 0` in `SimulationEngine.java` after the refactor lands. Acceptable surviving sites are the diffusion/nutrient/serialization style passes that the refactor explicitly excludes — the acceptance criterion below caps the count at ≤ 4 (a generous bound that accommodates the nutrient-spawn pass plus up to three additional spatial-only passes such as cell-flag reconciliation; if more remain, an in-scope site was missed).

3. **Critical invariants the executor MUST preserve verbatim:**

   (a) `Collections.shuffle(particlePositions, simRng)` calls (lines 305, 429, 513). Do NOT remove, do NOT reorder, do NOT change the RNG argument. The shuffled-list size must remain identical to pre-refactor for the golden-trace digest to match.

   (b) Death-removal accumulator pattern: `processDeaths` collects deaths into a queue, applies them after iteration. Do NOT modify `liveEntityRegistry` from inside the iteration loop — the death hook in `DeathFinalizer` (Plan 02) is the authorised mutation site. From SimulationEngine's perspective, the snapshot is read-only.

   (c) `processNutrientSpawning` at line 1172 — UNCHANGED. CONTEXT.md "Not in scope: Nutrient spawn".

   (d) @Order annotations on event listeners — UNCHANGED. The @Order(10) onTick handler stays @Order(10).

4. After all 7 in-scope sites are refactored, run the gate command. The full suite + GoldenTraceEquivalenceTest (incl. EXPECTED_DIGEST assertion) must be green.

   **If GoldenTraceEquivalenceTest fails:** the refactor changed observable output. Most likely causes:
   - List size differs (e.g., LiveEntityRegistry missed a registration site — fix Plan 02, not this plan).
   - Iteration order affects work that happens *outside* a `Collections.shuffle` call (e.g., a method that processes entities in grid order without shuffling). In that case, sort the snapshot deterministically before iteration, or wrap with a sort-by-entityId helper. PATTERNS.md (line 230) and RESEARCH.md §Pitfall 6 flag this.
   - A `Collections.shuffle` was accidentally dropped — re-grep after refactor.
   - `EXPECTED_DIGEST` mismatch but `hashA == hashB`: the refactor produced a self-consistent output that differs from the pre-Plan-04 baseline. That is exactly the regression D-10 forbids.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest" --tests "com.paralife.engine.SimulationEngine*"</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/SimulationEngine.java` >= 7 (one per refactored site; the nutrient-spawn site stays grid-walk)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -c "Collections.shuffle(.*simRng)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 3 (preserved at lines 305, 429, 513-equivalents — count must match pre-refactor count)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/SimulationEngine.java` == 0 (D-08/D-11 invariant)
    - `grep -c "@Order(10)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1 (onTick @Order unchanged)
    - `grep -nA2 "processNutrientSpawning" src/main/java/com/paralife/engine/SimulationEngine.java | head -20` shows nested grid scan still present (NOT refactored)
    - Bound on remaining double-nested grid loops: `bash -c 'count=$(grep -cE "for *\(int [a-z] = 0" src/main/java/com/paralife/engine/SimulationEngine.java); test "$count" -le 4'` exits 0. (Out of the original 8 in-scope sites + 1 nutrient-spawn site, only nutrient-spawn plus at most three additional spatial-only passes may remain.)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (D-10 gate, including EXPECTED_DIGEST equality)
    - `./gradlew test` exits 0 (full regression — 166+ tests + 4 new Wave 0 tests all green)
  </acceptance_criteria>
  <done>SimulationEngine's 7 in-scope grid scans now consume LiveEntityRegistry.snapshot(); nutrient-spawn pass preserved; @Order(10) unchanged; Collections.shuffle invariants preserved; remaining double-nested grid loops bounded ≤ 4; GoldenTraceEquivalenceTest stays green including EXPECTED_DIGEST equality.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor EnvironmentEngine per-entity segments + TickBroadcaster.onTick @Order(50)</name>
  <files>src/main/java/com/paralife/engine/EnvironmentEngine.java, src/main/java/com/paralife/websocket/TickBroadcaster.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (lines 150–185 — fields incl cellStatusCache; lines 320–360 — onTick entry; lines 590–660 — per-entity env effect application; lines 870–940 — buildStatusCaches with the entity-status writeback portion at 894–900, 906–915, 924–936; lines 423–680 — diffusion / path generators / lightning DO NOT REFACTOR; lines 1215, 1353 — fertility / serialization DO NOT REFACTOR)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 170–230 — onTick @Order(50) iteration; preserve STALLED-skip at line 195 and outboundSender.offer at line 206; preserve drainAndBroadcastDeaths at line 235)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java
    - src/main/java/com/paralife/engine/BotRegistry.java (the `getSessionForEntity(entityId)` method at line 146 — needed in TickBroadcaster to map entityId → sessionId)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 232–286 — EnvironmentEngine + TickBroadcaster refactor blocks; lines 280–286 — Phase 19.1 boundary: NO parallelStream)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4 — entity-list stale after death; this is what TickBroadcaster sees at @Order(50))
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (oracle from Plan 03 — EXPECTED_DIGEST pinned; this plan must keep that digest)
  </read_first>
  <action>
1. **EnvironmentEngine** — Constructor injection: add `LiveEntityRegistry liveEntityRegistry` field + ctor parameter.

   Refactor sites (per PATTERNS.md lines 235–245 and re-verified line numbers):

   (a) **Per-entity env effect application (lines 596–650)** — replace the grid scan that walks for entities-with-effects with `for (var entry : liveEntityRegistry.snapshot()) { ... }` and read the live entity via `worldGrid.getCell(entry.position()).occupant()`. Preserve the per-entity effect logic verbatim (toxin damage, mutagen application, buff activation, etc.).

   (b) **Entity-status writeback portion of buildStatusCaches (lines 894–900 and 924–936)** — these segments write `entityStatusCache` per live entity. Refactor the per-entity loops to iterate `liveEntityRegistry.snapshot()`. Do NOT touch lines that compute the cell-status (FLAG_OVERCROWDED bit) for non-occupied cells; those belong to the grid-walk.

   (c) **STAYS GRID-WALK (PATTERNS.md lines 245–249, RESEARCH.md §Pattern 2 D-07 carve-out):**
       - Lines 423, 437–438 — toxin diffusion CA pass
       - Lines 532–540 — toxin path generator
       - Lines 563–575 — mutagen diffusion CA
       - Lines 651–680 — lightning per-cell scan
       - Lines 1215, 1353 — fertility / serialization
       - The cell-status (bit 0) computation in buildStatusCaches — bit 0 depends on per-cell neighbour count regardless of occupancy

   (d) Preserve `cellStatusCacheView()` at line 1399 — Plan 01's `EligibleCellIndex` reads it.

2. **TickBroadcaster** — Constructor injection: add `LiveEntityRegistry liveEntityRegistry` field + ctor parameter.

   Refactor `onTick @Order(50)` at lines 176–223:

   ```java
   // BEFORE (line 185):
   var bots = botRegistry.getAllBots();   // ConcurrentHashMap.values() — RESEARCH §Pitfall 6
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

   // AFTER (D-09 deterministic snapshot):
   List<LiveEntityRegistry.EntityEntry> entries = liveEntityRegistry.snapshot();
   if (entries.isEmpty()) return;
   for (LiveEntityRegistry.EntityEntry entry : entries) {
       String entityId = entry.entityId();
       String sessionId = botRegistry.getSessionForEntity(entityId).orElse(null);
       if (sessionId == null) continue;   // entity not bot-bound (e.g. NPC offspring)
       WebSocketSession session = sessionRegistry.getSession(sessionId);
       if (session == null || !session.isOpen()) continue;
       if (worldWebSocketHandler != null && worldWebSocketHandler.isStalled(session)) {
           skipped++;
           continue;
       }
       // ... build perception for this entity, offer to outboundSender ...
   }
   ```

   **Preserve verbatim:**
   - `drainAndBroadcastDeaths(event.tickNumber())` at the top of onTick (line ~187) — Phase 15.2 death-frame pipeline; orthogonal to this refactor.
   - STALLED-skip at line ~195 (`worldWebSocketHandler.isStalled(session)`).
   - `outboundSender.offer(sessionId, frame)` at line ~206 — VT-per-session enqueue.
   - The session-monitor synchronisation contract (CLAUDE.md §"Outbound concurrency").
   - All metric increments in the loop body.
   - The `@Order(50)` annotation.

   **Phase 19.1 boundary (D-12):** DO NOT introduce `parallelStream()` here. Read-only parallel perception is a Phase 19.1 deliverable.

3. **Optional but recommended:** if there is a `@Order(100)` broadcast pass (the tick-snapshot broadcast) elsewhere in `TickBroadcaster.java` that ALSO walks `botRegistry.getAllBots()`, refactor it the same way. Re-grep `botRegistry.getAllBots\|bySession.values` in TickBroadcaster.java and assess. If the @Order(100) handler doesn't iterate per-bot (it broadcasts a single snapshot to all sessions), no refactor is needed there.

4. Run the gate command. The full suite + GoldenTraceEquivalenceTest (incl. EXPECTED_DIGEST) must be green.

   **If GoldenTraceEquivalenceTest fails:** the most common cause is iteration order divergence — `botRegistry.getAllBots()` iterated in ConcurrentHashMap order; LiveEntityRegistry iterates in insertion order. The frames sent are byte-identical per-session, but cross-session emit order may differ. Plan 03's GoldenTraceCapture digests in emit order; if that order changes, hashes diverge.
   - **Fix:** sort the snapshot by entityId before iteration in TickBroadcaster:
     ```java
     List<LiveEntityRegistry.EntityEntry> entries = liveEntityRegistry.snapshot();
     entries.sort(Comparator.comparing(LiveEntityRegistry.EntityEntry::entityId));
     ```
     This adds O(N log N) per tick at N≤256 (~µs) — negligible. Determinism over performance.
   - Verify by grep that the sort is in place if the fix is needed.
  </action>
  <verify>
    <automated>./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 2 (per-entity effect site + entity-status writeback site)
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 1
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 1
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/EnvironmentEngine.java` == 0
    - `grep -c "parallelStream" src/main/java/com/paralife/websocket/TickBroadcaster.java` == 0
    - `grep -c "@Order(50)" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1 (perception @Order unchanged)
    - `grep -c "@Order(14)" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (env-engine @Order unchanged)
    - `grep -c "drainAndBroadcastDeaths" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1 (Phase 15.2 death-frame path preserved)
    - `grep -c "outboundSender.offer" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "isStalled(session)" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1 (Phase 17 STALLED-skip preserved)
    - `grep -c "cellStatusCacheView" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (Plan 01 dependency preserved)
    - `grep -nA3 "toxin diffusion\|mutagenGrid\|lightning" src/main/java/com/paralife/engine/EnvironmentEngine.java | grep -c "for (int" >= 1 (diffusion grid-walk preserved)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (D-10 gate; EXPECTED_DIGEST equality preserved)
    - `./gradlew test` exits 0 (full regression — all 166+ tests + Wave 0 tests green)
  </acceptance_criteria>
  <done>EnvironmentEngine per-entity segments and TickBroadcaster.onTick @Order(50) consume LiveEntityRegistry.snapshot(); diffusion / lightning / fertility passes preserved as grid-walks; @Order chain unchanged; STALLED-skip and outboundSender.offer paths verbatim; D-08/D-11 invariants preserved; GoldenTraceEquivalenceTest green incl. EXPECTED_DIGEST; full regression green.</done>
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
| T-19-10 | Tampering | Refactored handler emits non-equivalent output | mitigate | GoldenTraceEquivalenceTest from Plan 03 — including the pinned EXPECTED_DIGEST — is the regression gate; failure blocks merge. |
| T-19-11 | DoS | Sort-by-entityId in TickBroadcaster (if needed for determinism) adds per-tick cost | accept | O(N log N) at N≤256 is ~µs; tick budget is 500ms. |
| T-19-12 | Information disclosure | snapshot exposes entityIds to read paths | accept | All readers are server-internal tick handlers. |
| T-19-13 | Repudiation | Phase 21 benchmark cannot attribute entity-list win separately | mitigate | Phase 19/19.1 split exists for exactly this attribution — entity-list win measurable separately from parallelism win (CONTEXT.md D-12). |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — D-10 gate green (incl. EXPECTED_DIGEST equality).
- `./gradlew test` — full regression green (all 166+ existing tests + 5 new Wave 0 tests).
- @Order chain unchanged: `grep -h "@Order(\\d" src/main/java/com/paralife/engine/SimulationEngine.java src/main/java/com/paralife/engine/EnvironmentEngine.java src/main/java/com/paralife/websocket/TickBroadcaster.java | sort -u` matches the pre-refactor set.
- `grep -rn "parallelStream" src/main/java/com/paralife/{engine,websocket}/` returns no results in any modified file (D-08, D-11 enforced).
- Diffusion / lightning / nutrient-spawn passes still grid-walk — verified by inspection.
- Remaining double-nested grid loops in SimulationEngine.java are bounded to ≤ 4.
</verification>

<success_criteria>
- World execution is partition-aware via entity-list iteration: O(N) replaces O(grid-cells) at all in-scope sites.
- Current simulation semantics stable at existing milestone workloads — proven byte-for-byte by GoldenTraceEquivalenceTest (the EXPECTED_DIGEST pin enforces equality against the pre-Plan-04 baseline, not just internal self-consistency).
- Tick @Order chain, STALLED-skip, outboundSender.offer, drainAndBroadcastDeaths, RNG-call counts, and shuffle calls all preserved.
- D-08/D-11 single-threaded mutation invariant preserved — no parallelStream introduced.
- Phase 19.1 prerequisite met: tick handlers now consume an entity-list, ready for read-only parallel sub-steps in 19.1.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-04-SUMMARY.md`.
</output>
