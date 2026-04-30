# Phase 19: High-Density Placement & Partition-Aware World Execution - Context

**Gathered:** 2026-04-30
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the 50-retry random-scan placement of bot register/respawn with a constrained free-cell index that scales to dense grids, and refactor the per-tick world execution from O(grid-cells) scans to O(live-entities) iteration. Both changes preserve observed simulation semantics. Closes SCALE-06 + SCALE-07.

**In scope:**
- Free-cell index for **bot register/respawn placement only** (`r|` ingress path in `WorldWebSocketHandler`)
- Three placement constraints, in addition to the trivial "no occupant": exclude rocks (already implicit via occupant check), exclude already-overcrowded cells, exclude cells where placement would push an adjacent occupied cell from non-overcrowded into overcrowded
- Bit-exact deterministic placement keyed by `paralife.simulation.spawn.seed` — same seed + same registration order → identical placements (promoted to a tested contract)
- `E|503|GRID_FULL` on empty eligible set (status-quo wire shape)
- Entity-list iteration replacing O(grid-cells) scans inside tick handlers (`SimulationEngine`, `EnvironmentEngine`, `PerceptionBroadcaster`, `TickBroadcaster` candidates — exact set determined in planning)
- Strict single-threaded mutation core preserved (CLAUDE.md §Concurrency invariant intact)
- Equivalence verification: Phase 19 must not change observable simulation outputs vs the pre-refactor baseline at existing milestone workloads

**Not in scope (other phases / backlog):**
- Initial rock placement — locked Phase 15 D-34/D-35 (`RockGenerator.java`); Phase 19 inherits the rock map as a constant constraint and must not modify it
- Reproduce offspring placement — local-adjacent only today, stays as-is
- Nutrient spawn — different algorithm shape (per-empty-cell probability), out of scope
- Parallel perception broadcast + parallel tick-encode — **deferred to new Phase 19.1** to keep Phase 19's blast radius small
- Conflict-graph parallel action dispatch — **deferred to new backlog item** "Parallelism strategies for tick execution"
- Spatial tiling with halo, striped locks, full tile decomposition — same backlog
- Connection multiplexing / runtime tuning — Phase 20 (SCALE-08/09)
- Benchmark gate + scale reports — Phase 21 (SCALE-10)

</domain>

<decisions>
## Implementation Decisions

### Placement Algorithm (SCALE-06)

- **D-01:** **Free-cell index for bot register/respawn placement.** Server maintains a constrained eligible-cell set. Placement is O(1) sample from the set, no retry storms, fair (uniform) spatial distribution within the eligible set. Rejected: adaptive retry (cost spikes >70% density), banded sampling (non-uniform), index + harness region-hint (more surface than SCALE-06 needs).

- **D-02:** **Scope = `r|` ingress only.** Only the bot register and respawn paths in `WorldWebSocketHandler.handleRegister` use the new index. Rocks (Phase 15 lock), nutrients (different algorithm), and reproduce offspring (local-adjacent rule) are out of scope. Smallest blast radius that still satisfies SCALE-06.

- **D-03:** **Eligibility constraints (in priority order):**
  1. Cell has no occupant (excludes rocks, particles, nutrients, composite members — already today's rule)
  2. Cell is not flagged OVERCROWDED (`Cell.flags & FLAG_OVERCROWDED == 0`, equivalently bit 0 of `cellStatusCache`)
  3. **Placement here would not cause any adjacent occupied cell to flip from non-overcrowded → overcrowded** (no occupied Moore neighbour with `neighborCount == OVERCROWDED_THRESHOLD - 1`)

  Constraint 3 is new. Constraints 1 and 2 derive cleanly from existing per-tick state (`Cell.hasOccupant()` and `EnvironmentEngine.cellStatusCache`).

- **D-04:** **Index maintenance: incremental, dirty-bbox.** Per-tick full rebuild is borderline at 5000 connections (~1–10ms). Incremental approach: every entity place / clear / death event dirties a 5×5 bbox around the position; the eligible-set updater recomputes only those cells. O(25) per event, sub-ms. Hooks live wherever `WorldGrid.{trySetEntity,setEntity,clearEntity}` are called. Constraint-3 evaluation reuses the same per-tick neighbour-count walk that `EnvironmentEngine.buildStatusCaches()` already performs (CLAUDE.md §"Env state projection — three layers" layer 2). Marginal cost: one extra predicate per cell in that pass.

- **D-05:** **Failure mode: `E|503|GRID_FULL`.** Status-quo wire shape preserved. When the eligible set is empty, registration is rejected with `E|503` and existing `RejectionToken.GRID_FULL` (Phase 17 D-07 token taxonomy). Bots may retry on subsequent ticks once death frees slots. Rejected: progressive constraint relaxation (introduces three placement modes to test), queue-and-place-next-tick (blocks WS handshake, conflicts with admission gates).

### Determinism (SCALE-06)

- **D-06:** **Bit-exact placement contract under `paralife.simulation.spawn.seed`.** Same seed + same registration arrival order → identical (x,y) for every bot. Promoted to a tested contract (regression test asserts byte-equality of placements for a known seed across two runs). Registration arrival order is already serialised through `WorldWebSocketHandler` on a single inbound thread per session — no further ordering work needed. Useful for Phase 21 benchmark replay and for narrowing regression bisects. Production default `seed=null` keeps unseeded behaviour. Mirrors the Phase 15 D-35 contract for `RockGenerator`.

### World Execution (SCALE-07)

- **D-07:** **Entity-list iteration replaces O(grid-cells) scans.** Tick handlers iterate the live-entity registry (size = N entities) instead of walking 65,536 cells. At 1000 entities + ~5% rocks, this is a ~50× reduction in inner-loop iterations per tick. Exact list of handlers to refactor (candidate set: `SimulationEngine`, `EnvironmentEngine`, `PerceptionBroadcaster`, `TickBroadcaster`) is determined in planning. Some scans must remain (e.g., `EnvironmentEngine` toxin/mutagen diffusion is inherently spatial — those stay grid-walk).

- **D-08:** **Single-threaded mutation core preserved.** CLAUDE.md §Concurrency invariant ("single-threaded simulation core, all world mutations in tick event handlers") stays intact through Phase 19. Entity-list iteration changes *what* the single-threaded handler walks, not *that* it's single-threaded. Outbound WebSocket sender VTs (Phase 17 D-10) are unaffected.

- **D-09:** **Live-entity registry is the source of iteration.** Concrete data structure (existing `BotRegistry` extension vs new `LiveEntitySet` vs reuse of an environment registry) is Claude's Discretion / planning research. Constraints on the choice: (a) deterministic iteration order (sorted by entityId or insertion order), (b) O(1) add/remove on entity place/death, (c) supports composite entities (BondedPair / CompositeMember presence is one logical entry), (d) snapshot-stable iteration during a single tick handler invocation.

- **D-10:** **Semantic equivalence is a verification gate.** Phase 19 must produce byte-identical observable output (tick frames, perception frames, action results, metric counters) compared with the pre-refactor baseline at existing milestone workloads. Mechanism: golden-trace test that records a fixed-seed run's output frames and asserts byte-equality after the refactor. Existing test suite must remain green.

### Concurrency Boundary

- **D-11:** **Phase 19 ships entity-list refactor only — strictly single-threaded inside tick handlers.** No new parallelism in tick mutation, perception, or broadcast paths in this phase.

- **D-12:** **Phase 19.1 (new follow-up phase) ships parallel read-only sub-steps:**
  - Parallel `PerceptionBroadcaster @Order(50)` — per-bot independent reads against the snapshot, parallel-stream over the entity list
  - Parallel `TickBroadcaster @Order(100)` compact-protocol encoding — per-session encode in parallel, sequential send (or queue per Phase 17 D-10 VT-per-session)
  Both preserve the single-threaded mutation invariant (only read-only sub-steps parallelise). Phase 19.1 is split out specifically to keep blast radius small per phase and to make Phase 21 benchmark attribution clean (entity-list win measurable separately from parallelism win).

### Claude's Discretion

- Concrete name and package of the free-cell index component (e.g., `EligibleCellIndex` in `com.paralife.world` or `com.paralife.engine`)
- Exact data structure for the eligible set (bitset + sample-from-set, indexed list, segment tree over cell ranks, etc.)
- Concrete name and shape of the live-entity registry abstraction (extend `BotRegistry`, new `LiveEntitySet`, reuse existing iteration in `EnvironmentEngine`)
- Tuning of dirty-bbox radius if 5×5 turns out wrong (e.g., overcrowding threshold relevant radius differs)
- Golden-trace test format (raw frame log, hashed digest, both)
- Final list of tick handlers refactored to entity-list iteration
- Overcrowding constraint threshold value — read from existing config (`Cell.flags FLAG_OVERCROWDED` source); not redefined here

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level

- `CLAUDE.md` §Conventions → Concurrency — single-threaded mutation invariant (D-08, D-11)
- `CLAUDE.md` §Architecture → Tick pipeline — `@Order` chain that entity-list iteration must preserve (D-07)
- `CLAUDE.md` §Architecture → "Env state projection — three layers" — `cellStatusCache` reuse for constraint-2/3 evaluation (D-04)
- `.planning/REQUIREMENTS.md` — SCALE-06 / SCALE-07 acceptance text
- `.planning/PROJECT.md` — overall vision; M4 scale milestone scope
- `.planning/STATE.md` — current milestone position

### Prior phases referenced as locked contracts

- `.planning/phases/15-protocol-transport-overhaul/15-SCHEMA.md` — milestone-locked wire grammar (`r|`, `E|<code>[|<token>]`); placement decisions must not mutate it
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` D-07 — `RejectionToken.GRID_FULL` taxonomy (D-05)
- `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` — admission gate / backpressure surface that placement plugs into
- `.planning/phases/17-durable-admission-control-backpressure/17-CONTEXT.md` D-10 — VT-per-session outbound concurrency (Phase 19.1 parallelism must respect this)
- `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` §1 (D-05/D-21) — WS:entity 1:1; placement is per-WS-session
- Phase 15 D-34 / D-35 — `RockGenerator` deterministic rock placement contract; Phase 19 inherits rock map and must not touch `RockGenerator.java`

### In-tree code referenced as ground truth

- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` lines 87, 116, 144, 450–467 — current 50-retry placement (`MAX_PLACEMENT_ATTEMPTS`, `spawnRng`, `trySetEntity` loop, `503|GRID_FULL` rejection)
- `src/main/java/com/paralife/engine/SpawnConfig.java` — `paralife.simulation.spawn.seed` binding; D-06 promotes its use to a tested contract
- `src/main/java/com/paralife/world/RockGenerator.java` — Phase 15-locked rock placement; do not touch
- `src/main/java/com/paralife/world/WorldGrid.java` — `ReentrantReadWriteLock`; `trySetEntity`/`clearEntity`/`setEntity` are the placement event hooks for D-04
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — `buildStatusCaches()` is where constraint-2/3 cache piggyback happens (D-04)
- `src/main/java/com/paralife/engine/SimulationEngine.java` — primary candidate for entity-list iteration (D-07)
- `src/main/java/com/paralife/engine/PerceptionBroadcaster.java` — entity-list iteration target; parallelisation deferred to 19.1
- `src/main/java/com/paralife/websocket/TickBroadcaster.java` — entity-list / encode candidate; parallelisation deferred to 19.1
- `src/main/java/com/paralife/engine/BotRegistry.java` — candidate base for the live-entity registry (D-09)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **`SpawnConfig` record** (`com.paralife.engine.SpawnConfig`): `paralife.simulation.spawn.seed` binding already exists. D-06 promotes the existing seedability into a tested contract; no new config surface needed.
- **`EnvironmentEngine.cellStatusCache`** (CLAUDE.md §"Env state projection" layer 2): per-tick read-only bitmask projection of cell status. OVERCROWDED bit 0 is already computed every tick; constraint 2 is a free read against this cache. Constraint-3 piggybacks on the same neighbour-count walk that builds OVERCROWDED — single extra predicate.
- **`BotRegistry`**: already maintains session ↔ entity ↔ position. Plausible base for the live-entity registry abstraction (D-09).
- **`RejectionToken.GRID_FULL`** (Phase 17 D-07): existing token in the locked taxonomy; D-05 reuses unchanged.
- **`AdmissionGate.releaseSlot()`**: already invoked in the current `503|GRID_FULL` path for fresh registrations; behaviour preserved unchanged after the placement-algorithm swap.

### Established Patterns

- **`@ConfigurationProperties` records for config** (CLAUDE.md §Spring patterns) — any new tuning surface (e.g., dirty-bbox radius if it ever becomes configurable) follows this pattern.
- **`@Order`-chained tick pipeline** — mutation handlers run in fixed order on a single thread. Entity-list refactor cannot change handler order or the @Order numbering. Per-tick state visible to a handler must be the post-state of all lower-@Order handlers.
- **Deterministic seed contracts** — `RockGenerator` (Phase 15 D-35) is the precedent for a tested bit-exact-placement-from-seed contract. D-06 mirrors that pattern for bot placement.
- **Status-cache projection** (D-41 from CLAUDE.md) — per-tick read-only cache derived from authoritative shadow state. Free-cell index follows the same shape: derived from `WorldGrid` + `cellStatusCache`, rebuilt incrementally.
- **VT-per-session outbound** (Phase 17 D-10) — Phase 19.1 parallel tick-encode must enqueue into the existing per-session bounded queue, not bypass it.

### Integration Points

- **Placement event hooks** for the free-cell index: every site that calls `WorldGrid.trySetEntity / setEntity / clearEntity / setCell`. The lock surface inside `WorldGrid` is the natural place to dirty the index, but the index itself probably lives outside `WorldGrid` to keep that class focused.
- **Tick-handler refactor sites**: `SimulationEngine`, `EnvironmentEngine`, `PerceptionBroadcaster`, `TickBroadcaster`. Spatial diffusion code in `EnvironmentEngine` keeps its grid-walk shape; per-entity logic moves to entity-list iteration.
- **Verification site**: a new golden-trace test that runs a fixed-seed scenario both before and after the refactor and asserts byte-equality of all observable output.
- **Phase 19.1 boundary**: PerceptionBroadcaster + TickBroadcaster encode are the only places parallel sub-steps land. Mutation pipeline stays sequential.

</code_context>

<specifics>
## Specific Ideas

- The "would-cause-overcrowding" constraint (D-03 #3) was an explicit user-supplied refinement — a placement rule that protects existing entities from a sudden density-induced overcrowding penalty caused by the new arrival. This is stronger than just "avoid currently-overcrowded cells": it preserves the locality of overcrowding to areas where it already exists.
- The conflict-graph parallel dispatch model the user proposed (HoMM3-style: parallel actions until conflict detected, then serial within conflict groups) is captured intact in the deferred backlog item — not abandoned, just descoped from Phase 19.
- Phase 19 / Phase 19.1 split is deliberate so Phase 21 benchmark numbers can attribute the entity-list win separately from the read-only-parallelism win.

</specifics>

<deferred>
## Deferred Ideas

### To new follow-up phase 19.1 — Parallel Read-Only Tick Sub-Steps

- Parallel `PerceptionBroadcaster` over entity list (per-bot independent reads on snapshot)
- Parallel `TickBroadcaster` compact-protocol encoding (per-session encode parallel, send via Phase 17 D-10 VT-per-session queues)
- Determinism constraints: parallelism must produce identical observable output as sequential equivalent (golden-trace check); ordering of parallel work must not affect any sent-frame bytes

**Action:** run `/gsd-add-phase 19.1` after Phase 19 lands — depends on Phase 19; addresses M4 SCALE residual hot paths.

### To backlog — Parallelism strategies for tick execution

The user's conflict-graph proposal + broader survey:

- **Conflict-graph parallel dispatch** — 2-pass: pass 1 reads intended actions and partitions into conflict groups (transitive moves, combat-adjacent, composite-spanning); pass 2 dispatches groups to threads such that order-mattering work shares a thread. HoMM3-style fail-soft analogy.
- **Spatial tile decomposition with halo zones** — full tile-with-halo parallelism for grid mutation; toroidal wraparound preserved.
- **Striped lock granularity** — replace single `WorldGrid` RRWL with K stripes; works for non-spanning entities, complicates BondedPair/CompositeMember.
- **Work-stealing per @Order phase** — break each tick handler into independent work units, fan out across VT pool, barrier between handlers.
- **GPU offload for environmental diffusion** — toxin/mutagen shadow-grid CA diffusion is embarrassingly parallel; could move to compute kernel.
- **Action-locality scheduling** — group entities by spatial bucket, process buckets in parallel where they can't interact.

**Trigger condition:** Phase 21 benchmark reports identify a tick-handler hotspot that entity-list + Phase 19.1 parallelism do not eliminate. Until then, this stays speculative.

**Action:** run `/gsd-add-backlog` to capture as 999.x backlog item ("Parallelism strategies for tick execution") with the strategies above as a non-exhaustive starting list.

### Reviewed Todos (not folded)

None — no pending todos surfaced for Phase 19.

</deferred>

---

*Phase: 19-high-density-placement-partition-aware-world-execution*
*Context gathered: 2026-04-30*
