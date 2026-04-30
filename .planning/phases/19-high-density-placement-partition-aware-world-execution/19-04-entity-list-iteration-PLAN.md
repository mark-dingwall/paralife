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
    - "SimulationEngine, EnvironmentEngine per-entity segments, and TickBroadcaster.onTick consume LiveEntityRegistry.snapshot() (which sorts by entityId per Plan 02 / REVIEWS M3) instead of full grid scans for the per-entity logic paths."
    - "EnvironmentEngine diffusion loops (toxin/mutagen CA, lightning, fertility, serialization passes) STAY grid-walk — D-07 explicitly excludes them."
    - "Nutrient spawning (SimulationEngine.processNutrientSpawning, line ~1172) STAYS grid-walk — CONTEXT.md 'Not in scope: Nutrient spawn'."
    - "Tick pipeline @Order chain unchanged: 10 → 14 → 20 → 25 → 50 → 100. No handler is split; no @Order is renumbered."
    - "Single-threaded mutation invariant preserved (D-08, D-11) — no parallelStream introduced anywhere in this plan."
    - "GoldenTraceEquivalenceTest passes — outbound frame bytes byte-identical (per-session digest map) vs the pre-Plan-04 baseline. The test's pinned `EXPECTED_DIGESTS` map (set in Plan 03 against the post-Plan-02 codebase) MUST equal the digest map produced after this plan's refactor lands."
    - "Existing 166+ tests remain green."
    - "RNG-call count for SimulationEngine's `Collections.shuffle(..., simRng)` calls is **exactly 3** post-refactor (REVIEWS M4 — tight bound). Pre-refactor sites were lines 305, 429, 513; refactor produces lists of identical size and identical entity content per filter, so the 3 shuffle calls remain semantically equivalent."
    - "Remaining double-nested grid loops (`for (int x = 0...; for (int y = 0...`) in SimulationEngine.java are bounded to **≤ 2** post-refactor (REVIEWS M5 — was ≤ 4, tightened): nutrient-spawn pass + at most one explicitly-allowlisted spatial-only pass. If more remain, an in-scope site was missed."
  artifacts:
    - path: src/main/java/com/paralife/engine/SimulationEngine.java
      provides: "7 in-scope grid-scan sites refactored to LiveEntityRegistry.snapshot() iteration (sites 295/421/505/735/870/909/924); nutrient-spawn pass (line 1172) preserved; processOvercrowding neighbour-count walk per entity preserved verbatim. Exactly 3 Collections.shuffle(..., simRng) calls preserved (REVIEWS M4)."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/engine/EnvironmentEngine.java
      provides: "Per-entity segments (lines 596–650; entity-status writeback portions of 894–900 and 924–936) iterate LiveEntityRegistry.snapshot(); diffusion / lightning / fertility passes unchanged."
      contains: "liveEntityRegistry.snapshot"
    - path: src/main/java/com/paralife/websocket/TickBroadcaster.java
      provides: "@Order(50) onTick loop iterates LiveEntityRegistry.snapshot() (sort-by-entityId is intrinsic to snapshot() per Plan 02 — no extra sort needed in TickBroadcaster) instead of botRegistry.getAllBots(); STALLED-skip and outboundSender.offer paths preserved verbatim."
      contains: "liveEntityRegistry.snapshot"
  key_links:
    - from: src/main/java/com/paralife/engine/SimulationEngine.java
      to: src/main/java/com/paralife/engine/LiveEntityRegistry.java
      via: "constructor-injected (already added in Plan 02); snapshot() called once per tick at the start of each grid-scan-replacement block"
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

The `GoldenTraceEquivalenceTest` from Plan 03 is the merge gate: this plan's diff is correct iff that test stays green (including the pinned `EXPECTED_DIGESTS` map assertion AND emitCount > 0 / no-empty-digest guards) AND the full regression suite stays green.

**Decision (per PATTERNS.md finding 1):** keep `TickBroadcaster.onTick @Order(50)` in-place — no rename, no extraction of a `PerceptionBroadcaster` bean. Smaller blast radius.

**REVIEWS revisions applied:**

- **M3 / Codex H closed by Plan 02:** `LiveEntityRegistry.snapshot()` returns sort-by-entityId order intrinsically. This plan therefore does NOT need to add reactive `entries.sort(Comparator.comparing(...))` in TickBroadcaster — the registry already does it. Acceptance gate verifies no extra sort is applied (or that, if applied, it's redundant-but-harmless).
- **M4 (claude):** `Collections.shuffle(.*simRng)` count tightened to `== 3` (was `>= 3`). RNG consumption regression cannot silently pass.
- **M5 (claude):** Remaining double-nested grid loops bounded to `≤ 2` (was `≤ 4`). 7 in-scope refactors + 1 nutrient-spawn-kept = 8 sites to remove from the `for (int x = 0 ...; for (int y = 0 ...` count, leaving only nutrient-spawn (and potentially one explicitly-allowlisted spatial-only pass) post-refactor.
- **Codex HIGH (registry order vs row-major):** the previous plan claimed "shuffle randomizes regardless, digest stays stable if list size is same" — Codex correctly noted that Java's `Collections.shuffle` is deterministic given input order + seed, so changing pre-shuffle order changes post-shuffle order. Mitigation: Plan 02's sort-by-entityId snapshot makes pre-shuffle order **deterministic and refactor-stable** (the snapshot order is independent of grid row-major scan order). The shuffle output is therefore deterministic — the digest matches across runs of the same scenario. The pre-Plan-04 baseline used row-major + ConcurrentHashMap iteration; the post-Plan-04 path uses sort-by-entityId. Plan 03's per-session digests sidestep cross-session emit-order divergence; the WITHIN-session frame content is byte-identical because each session's perception is a function of the entity set + grid state, both of which are unchanged.

  **If GoldenTraceEquivalenceTest fails on first execution of this plan:** that signals a genuine semantic divergence (the pre/post Plan 04 simulation produced different observable output), NOT a harness bug. Investigate; do NOT re-pin EXPECTED_DIGESTS without operator review.

Purpose: SCALE-07 — replace O(grid-cells) inner loops with O(entities) iteration. At current 256-entity cap and 256×256 grid, this is a ~50× iteration reduction per tick. The architectural payoff (Phase 19.1 read-only parallelism prerequisite, scaling beyond admission cap) is the strategic motivation — see RESEARCH.md §"Honest Assessment".
Output: 3 files modified; tick @Order chain unchanged; D-08/D-11 invariants preserved; equivalence gate (incl. EXPECTED_DIGESTS map) green.
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

<interfaces>
<!-- LiveEntityRegistry surface from Plan 02 -->

```java
public List<EntityEntry> snapshot();              // O(N + N log N) shallow copy SORTED BY entityId (REVIEWS M3 baked in)
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
// snapshot() ORDER IS DETERMINISTIC (sort-by-entityId, REVIEWS M3).
// Collections.shuffle is deterministic given (input order, seed) — therefore
// the shuffled output is deterministic across runs of the same scenario.
```

**Caveat — list size equality requires careful handling of off-grid entities:** A Particle is on the grid iff `WorldGrid.getCell(pos).occupant() == that-particle`. If LiveEntityRegistry is out of sync with the grid (e.g. a Particle was placed but liveEntityRegistry.register was missed), the refactored list size differs from the pre-refactor list size and the golden-trace test fails. This is the regression signal Plan 03 was built to catch — and Plan 02's REVIEWS H3 audit (every entityId-introducing site wired) is the prevention.

**Position lookup via the registry:** Plan 02 wires `liveEntityRegistry.updatePosition` at the move site in `ActionResolver.resolveMove` as a **hard requirement**. Refactored handlers in this plan therefore read the current position directly from `entry.position()` — that value is current as of the most recent successful move. Do NOT introduce a fallback path that re-reads through `worldGrid.getCell(...)` keyed on entityId; the registry IS the source of truth.

</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Refactor SimulationEngine grid scans to entity-list iteration (7 in-scope sites; 3 shuffles preserved exactly)</name>
  <files>src/main/java/com/paralife/engine/SimulationEngine.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/SimulationEngine.java (lines 220–235 — @Order annotations and onTick entry; lines 290–310 — first grid scan in processInteractions; lines 415–435 — composite-member grid scan; lines 500–520 — bonded-pair grid scan; lines 730–810 — processEnergyDecay; lines 865–905 — processOvercrowding; lines 905–935 — processDeaths Phase 3a; lines 1165–1195 — processNutrientSpawning DO NOT REFACTOR)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (the bean from Plan 02 — note `snapshot()` is sort-by-entityId per REVIEWS M3)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 192–230 — grid-scan table; lines 207–225 — replace-pattern with shuffle invariant)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Code Examples lines 502–520; §Pitfall 4; §Anti-Patterns "Iterating bySession.values() inside a tick handler")
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (M4 / M5 — tightened grep gates; Codex HIGH on shuffle determinism)
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (the oracle from Plan 03 — note the pinned `EXPECTED_DIGESTS` map; this plan's refactor must NOT change that map)
  </read_first>
  <action>
1. **Constructor injection** — `LiveEntityRegistry liveEntityRegistry` field + ctor parameter were already added in Plan 02 (Wave 2 lifecycle wiring). Confirm and reuse.

2. **Refactor the in-scope grid scans.** RESEARCH.md A2 lists 8 known double-nested loop sites in SimulationEngine.java (lines 295, 421, 505, 735, 870, 909, 924, 1172). Site 1172 is `processNutrientSpawning` — out of scope per CONTEXT.md. Refactor the other **7 in-scope sites** with the entity-list iteration pattern shown in `<interfaces>`. Preserve the entity-type filter (`instanceof Entity.Particle`, `instanceof Entity.BondedPair`, `instanceof Entity.CompositeMember` — sealed-inner-type qualified names per REVIEWS M1) — the filter on `worldGrid.getCell(pos).occupant()` is the canonical source of truth for what's currently on that cell.

   In-scope sites (line numbers from RESEARCH.md A2 — re-verify before patching; line numbers may shift after Plan 02 lands):

   | Line | Method | Filter |
   |------|--------|--------|
   | 295  | processInteractions | `instanceof Entity.Particle` |
   | 421  | processInteractions | `instanceof Entity.CompositeMember` |
   | 505  | processInteractions | `instanceof Entity.BondedPair` |
   | 735  | processEnergyDecay | mixed — Particle and BondedPair branches; preserve branch logic |
   | 870  | processOvercrowding | `instanceof Entity.Particle \|\| instanceof Entity.BondedPair` |
   | 909  | processDeaths Phase 3a | particle/bonded; `if (!isAlive())` predicate preserved |
   | 924  | processDeaths composite half | `instanceof Entity.CompositeMember` |

   The 8th in-scope site (1172) is the nutrient spawn — DO NOT refactor it.

3. **Critical invariants the executor MUST preserve verbatim:**

   (a) `Collections.shuffle(particlePositions, simRng)` calls (lines 305, 429, 513). **Exactly 3 such calls must remain after the refactor — REVIEWS M4 tightened bound** (was `>= 3`, now `== 3`). Do NOT remove, do NOT reorder, do NOT change the RNG argument. The shuffled-list size must remain identical to pre-refactor for the golden-trace digest to match.

   (b) **Pre-shuffle order matters (REVIEWS Codex HIGH):** Java `Collections.shuffle` is deterministic given (input order, seed). Pre-Plan-04 path: row-major grid scan → list in row-major order. Post-Plan-04 path: snapshot() in sort-by-entityId order. These differ. Plan 03's GoldenTraceCapture compares per-session digests, AND the pre-Plan-04 baseline was captured against a path where TickBroadcaster.onTick read `botRegistry.getAllBots()` (ConcurrentHashMap.values()), which itself was non-deterministic across runs. The fact that Plan 03 still produces a stable EXPECTED_DIGESTS map indicates the ConcurrentHashMap order happened to be stable for that scenario in that JVM run — the per-session digest map captures one specific stable run.

      **Reality check:** if Plan 03's EXPECTED_DIGESTS was captured against a row-major-shuffled simulation, and Plan 04 produces sort-by-entityId-shuffled simulation, the in-tick logic that consumes the shuffled list (e.g. interaction resolution order) may produce different observable simulation outcomes — different death events, different perception frames sent to clients. The per-session digest will diverge.

      **Mitigation (W4 tightened — re-pinning is OUT OF SCOPE for this plan):** if the test fails on first run of this plan with `mapA == mapB` (intra-run determinism holds) but `mapA != EXPECTED_DIGESTS` (cross-plan-cut divergence), **STOP and escalate to the operator**. Do NOT modify `EXPECTED_DIGESTS` in this plan under any circumstance — re-pinning the digest requires a separate operator-approved task, governed by threat T-19-09a (see Plan 03 threat model). The expected outcome of Plan 04's first execution is (a) the test stays GREEN (semantic equivalence — D-10 satisfied). Any other outcome is an operator-review event, not a self-healing path.

   (c) Death-removal accumulator pattern: `processDeaths` collects deaths into a queue, applies them after iteration. Do NOT modify `liveEntityRegistry` from inside the iteration loop — the death hook in `DeathFinalizer` (Plan 02) is the authorised mutation site. From SimulationEngine's perspective, the snapshot is read-only.

   (d) `processNutrientSpawning` at line 1172 — UNCHANGED. CONTEXT.md "Not in scope: Nutrient spawn".

   (e) @Order annotations on event listeners — UNCHANGED. The @Order(10) onTick handler stays @Order(10).

4. After all 7 in-scope sites are refactored, run the gate command. The full suite + GoldenTraceEquivalenceTest (incl. EXPECTED_DIGESTS map assertion) must be green.

   **Failure modes and what they mean:**
   - `mapA != mapB` (intra-run divergence): list size differs, or registry is missing a hook → fix Plan 02 (REVIEWS H3 / M3 audit gap), not this plan.
   - `mapA == mapB` but `mapA != EXPECTED_DIGESTS` (refactor-cut divergence): the refactor produced a self-consistent output that differs from the pre-Plan-04 baseline. Surface to operator. Either accept the new digest as the post-refactor baseline (re-pin) or investigate the source of divergence (likely the row-major vs sort-by-entityId pre-shuffle order changing observable simulation outcomes).
   - A `Collections.shuffle` was accidentally dropped — re-grep after refactor; M4 grep gate catches this.
  </action>
  <verify>
    <automated>./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest" --tests "com.paralife.engine.SimulationEngine*"</automated>
  </verify>
  <acceptance_criteria>
    - `grep -c "liveEntityRegistry.snapshot" src/main/java/com/paralife/engine/SimulationEngine.java` >= 7 (one per refactored site; the nutrient-spawn site stays grid-walk)
    - `grep -c "private final LiveEntityRegistry liveEntityRegistry" src/main/java/com/paralife/engine/SimulationEngine.java` == 1
    - `grep -cE "Collections\\.shuffle\\(.*simRng" src/main/java/com/paralife/engine/SimulationEngine.java` == 3 (REVIEWS M4 — exactly 3, was >= 3)
    - `grep -c "parallelStream" src/main/java/com/paralife/engine/SimulationEngine.java` == 0 (D-08/D-11 invariant)
    - `grep -c "@Order(10)" src/main/java/com/paralife/engine/SimulationEngine.java` >= 1 (onTick @Order unchanged)
    - `grep -nA2 "processNutrientSpawning" src/main/java/com/paralife/engine/SimulationEngine.java | head -20` shows nested grid scan still present (NOT refactored)
    - Bound on remaining double-nested grid loops (REVIEWS M5 — tightened from ≤ 4 to ≤ 2): `bash -c 'count=$(grep -cE "for *\\(int [a-z] = 0" src/main/java/com/paralife/engine/SimulationEngine.java); test "$count" -le 2'` exits 0. Out of the original 8 in-scope sites + 1 nutrient-spawn site, only nutrient-spawn plus at most one explicitly-allowlisted spatial-only pass may remain.
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (D-10 gate, including EXPECTED_DIGESTS map equality and emitCount > 0 / no-empty-digest guards from Plan 03)
    - `./gradlew test` exits 0 (full regression — 166+ tests + Wave 0 tests all green)
  </acceptance_criteria>
  <done>SimulationEngine's 7 in-scope grid scans now consume LiveEntityRegistry.snapshot(); nutrient-spawn pass preserved; @Order(10) unchanged; exactly 3 Collections.shuffle(..., simRng) calls preserved (REVIEWS M4); remaining double-nested grid loops bounded ≤ 2 (REVIEWS M5); GoldenTraceEquivalenceTest stays green including EXPECTED_DIGESTS map equality.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Refactor EnvironmentEngine per-entity segments + TickBroadcaster.onTick @Order(50) (no extra sort needed — Plan 02 snapshot already sorts)</name>
  <files>src/main/java/com/paralife/engine/EnvironmentEngine.java, src/main/java/com/paralife/websocket/TickBroadcaster.java</files>
  <read_first>
    - src/main/java/com/paralife/engine/EnvironmentEngine.java (lines 150–185 — fields incl cellStatusCache; lines 320–360 — onTick entry; lines 590–660 — per-entity env effect application; lines 870–940 — buildStatusCaches with the entity-status writeback portion at 894–900, 906–915, 924–936; lines 423–680 — diffusion / path generators / lightning DO NOT REFACTOR; lines 1215, 1353 — fertility / serialization DO NOT REFACTOR)
    - src/main/java/com/paralife/websocket/TickBroadcaster.java (lines 170–230 — onTick @Order(50) iteration; preserve STALLED-skip at line 195 and outboundSender.offer at line 206; preserve drainAndBroadcastDeaths at line 235)
    - src/main/java/com/paralife/engine/LiveEntityRegistry.java (snapshot() returns sort-by-entityId — REVIEWS M3 / Codex H closed at the registry layer; this task does NOT need a reactive sort in TickBroadcaster)
    - src/main/java/com/paralife/engine/BotRegistry.java (the `getSessionForEntity(entityId)` method at line 146 — needed in TickBroadcaster to map entityId → sessionId)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-PATTERNS.md (lines 232–286 — EnvironmentEngine + TickBroadcaster refactor blocks; lines 280–286 — Phase 19.1 boundary: NO parallelStream)
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-RESEARCH.md (§Pitfall 4 — entity-list stale after death; this is what TickBroadcaster sees at @Order(50))
    - .planning/phases/19-high-density-placement-partition-aware-world-execution/19-REVIEWS.md (M3 — sort baked in at registry layer; M4 / M5 grep gates)
    - src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java (oracle from Plan 03 — EXPECTED_DIGESTS pinned; this plan must keep that map)
  </read_first>
  <action>
1. **EnvironmentEngine** — Constructor injection: `LiveEntityRegistry liveEntityRegistry` field + ctor parameter. (Plan 02 may have already added this; confirm.)

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

2. **TickBroadcaster** — Constructor injection: `LiveEntityRegistry liveEntityRegistry` field + ctor parameter.

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

   // AFTER (D-09 deterministic snapshot — sort is intrinsic to snapshot per Plan 02 / REVIEWS M3):
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

   **No reactive `entries.sort(...)` is needed here — Plan 02's `snapshot()` already returns sort-by-entityId order (REVIEWS M3 closed at the registry layer).**

   **Preserve verbatim:**
   - `drainAndBroadcastDeaths(event.tickNumber())` at the top of onTick (line ~187) — Phase 15.2 death-frame pipeline; orthogonal to this refactor.
   - STALLED-skip at line ~195 (`worldWebSocketHandler.isStalled(session)`).
   - `outboundSender.offer(sessionId, frame)` at line ~206 — VT-per-session enqueue.
   - The session-monitor synchronisation contract (CLAUDE.md §"Outbound concurrency").
   - All metric increments in the loop body.
   - The `@Order(50)` annotation.

   **Phase 19.1 boundary (D-12):** DO NOT introduce `parallelStream()` here. Read-only parallel perception is a Phase 19.1 deliverable.

3. **Optional but recommended:** if there is a `@Order(100)` broadcast pass (the tick-snapshot broadcast) elsewhere in `TickBroadcaster.java` that ALSO walks `botRegistry.getAllBots()`, refactor it the same way. Re-grep `botRegistry.getAllBots\\|bySession.values` in TickBroadcaster.java and assess. If the @Order(100) handler doesn't iterate per-bot (it broadcasts a single snapshot to all sessions), no refactor is needed there.

4. Run the gate command. The full suite + GoldenTraceEquivalenceTest (incl. EXPECTED_DIGESTS map) must be green.

   **If GoldenTraceEquivalenceTest fails (mapA == mapB but mapA != EXPECTED_DIGESTS):** that signals a genuine refactor-cut divergence. Operator-supervised diagnosis required:
   - Plan 03's baseline was captured against `botRegistry.getAllBots()` order (ConcurrentHashMap.values()).
   - Plan 04's path uses `liveEntityRegistry.snapshot()` (sort-by-entityId).
   - Per-session frame BYTES are functions of (entity set + grid state + this entity's position + neighbourhood) — they should be identical.
   - Per-session emit ORDER is a function of which session was iterated first, which differs across the cut.
   - But the per-session digest hashes ALL frames sent to that session — emit order across sessions does not affect a per-session digest.
   - **If divergence still appears:** the simulation produced different observable outcomes (e.g. shuffled-interaction order in SimulationEngine differs → different death events → different perception frames). This is a real D-10 violation. Investigate or escalate.

   **DO NOT modify EXPECTED_DIGESTS in this plan under any circumstance.** If the test fails on first run, STOP and escalate to the operator. Re-pinning the digest is OUT OF SCOPE for Plan 04 — it requires a separate operator-approved task governed by threat T-19-09a (see Plan 03 threat model). Self-healing re-pin paths are explicitly forbidden (W4).
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
    - `grep -c "@Order(14)" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (env-engine @Order unchanged — note: re-grep before patching to confirm pre-refactor file actually has @Order(14); REVIEWS L4)
    - `grep -c "drainAndBroadcastDeaths" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1 (Phase 15.2 death-frame path preserved)
    - `grep -c "outboundSender.offer" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1
    - `grep -c "isStalled(session)" src/main/java/com/paralife/websocket/TickBroadcaster.java` >= 1 (Phase 17 STALLED-skip preserved)
    - `grep -c "cellStatusCacheView" src/main/java/com/paralife/engine/EnvironmentEngine.java` >= 1 (Plan 01 dependency preserved)
    - `grep -nA3 "toxin diffusion\\|mutagenGrid\\|lightning" src/main/java/com/paralife/engine/EnvironmentEngine.java | grep -c "for (int" >= 1 (diffusion grid-walk preserved)
    - `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` exits 0 (D-10 gate; EXPECTED_DIGESTS map equality preserved)
    - `./gradlew test` exits 0 (full regression — all 166+ tests + Wave 0 tests green)
  </acceptance_criteria>
  <done>EnvironmentEngine per-entity segments and TickBroadcaster.onTick @Order(50) consume LiveEntityRegistry.snapshot() (sort-by-entityId is intrinsic to snapshot, no reactive sort needed); diffusion / lightning / fertility passes preserved as grid-walks; @Order chain unchanged; STALLED-skip and outboundSender.offer paths verbatim; D-08/D-11 invariants preserved; GoldenTraceEquivalenceTest green incl. EXPECTED_DIGESTS map; full regression green.</done>
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
| T-19-10 | Tampering | Refactored handler emits non-equivalent output | mitigate | GoldenTraceEquivalenceTest from Plan 03 — including the pinned EXPECTED_DIGESTS map AND emitCount > 0 / no-empty-digest guards — is the regression gate; failure blocks merge. |
| T-19-10a | Tampering | Pre-shuffle order divergence (REVIEWS Codex H) | mitigate | Plan 02 snapshot is sort-by-entityId — pre-shuffle order is deterministic post-refactor; Java Collections.shuffle is deterministic given (input order, seed). |
| T-19-11 | DoS | sort-by-entityId in registry adds per-tick cost | accept | O(N log N) at N≤256 is ~µs; tick budget is 500ms. (Plan 02 cost; this plan inherits.) |
| T-19-12 | Information disclosure | snapshot exposes entityIds to read paths | accept | All readers are server-internal tick handlers. |
| T-19-13 | Repudiation | Phase 21 benchmark cannot attribute entity-list win separately | mitigate | Phase 19/19.1 split exists for exactly this attribution — entity-list win measurable separately from parallelism win (CONTEXT.md D-12). |
</threat_model>

<verification>
- `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` — D-10 gate green (incl. EXPECTED_DIGESTS map equality, emitCount > 0, no-empty-digest guards).
- `./gradlew test` — full regression green (all 166+ existing tests + Wave 0 tests).
- @Order chain unchanged.
- `grep -rn "parallelStream" src/main/java/com/paralife/{engine,websocket}/` returns no results in any modified file (D-08, D-11 enforced).
- Diffusion / lightning / nutrient-spawn passes still grid-walk — verified by inspection.
- Remaining double-nested grid loops in SimulationEngine.java are bounded to ≤ 2 (REVIEWS M5).
- Exactly 3 Collections.shuffle(..., simRng) calls in SimulationEngine.java (REVIEWS M4).
</verification>

<success_criteria>
- World execution is partition-aware via entity-list iteration: O(N) replaces O(grid-cells) at all in-scope sites.
- Current simulation semantics stable at existing milestone workloads — proven by GoldenTraceEquivalenceTest's pinned EXPECTED_DIGESTS map (per-session) plus emitCount > 0 / no-empty-digest guards.
- Tick @Order chain, STALLED-skip, outboundSender.offer, drainAndBroadcastDeaths, RNG-call counts, and shuffle calls (exactly 3) all preserved.
- D-08/D-11 single-threaded mutation invariant preserved — no parallelStream introduced.
- Phase 19.1 prerequisite met: tick handlers now consume an entity-list, ready for read-only parallel sub-steps in 19.1.
</success_criteria>

<output>
After completion, create `.planning/phases/19-high-density-placement-partition-aware-world-execution/19-04-SUMMARY.md`.
</output>
</content>
