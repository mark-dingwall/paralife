# Phase 19: High-Density Placement & Partition-Aware World Execution - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-30
**Phase:** 19-high-density-placement-partition-aware-world-execution
**Areas discussed:** Placement algorithm, Placement determinism, Partition strategy, Concurrency boundary

---

## Gray Area Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Placement algorithm | How to find empty cells under density. Today: 50 random retries. | ✓ |
| Placement determinism | How reproducible should placement be? | ✓ |
| Partition strategy | What 'partition-aware' means. | ✓ |
| Concurrency boundary | Whether to keep strict single-threaded mutation core or admit parallel substeps. | ✓ |

**User's choice:** All four areas selected.

---

## Placement algorithm

### Question 1: Initial scope of which placement paths to redesign

| Option | Description | Selected |
|--------|-------------|----------|
| Bot register/respawn only (Recommended) | Just the ingress path used by harness + BotRunner. | ✓ |
| + Initial rock generation | Also unify rock placement at startup. | |
| + Reproduce offspring placement | Currently local-adjacent only; could fall back to global. | |
| + Nutrient spawn | Per-cell probabilistic scan today. Probably out of scope. | |

**User's choice:** Bot register/respawn only.

**Notes:** First exchange: user asked whether the placement question covered new bots only or environmental entities too. Reply clarified the four candidate paths and pointed out that rocks were Phase 15-locked (`RockGenerator.java` D-34/D-35), nutrients use a different algorithm shape, and reproduce already places adjacent. User then locked scope to bot register/respawn.

### Question 2: Algorithm for register/respawn

| Option | Description | Selected |
|--------|-------------|----------|
| Free-cell index (Recommended) | Server tracks empty-cell set; sample uniformly in O(1). | ✓ |
| Adaptive retry | Keep random sampling, scale max-attempts by emptiness ratio. | |
| Banded sampling | Pick a band, sweep within. | |
| Index + harness hint | Free-cell index plus optional region hint via handshake header. | |

**User's choice:** Free-cell index, with three additional eligibility constraints supplied:
1. Exclude rocks (any occupant — already implicit)
2. Exclude already-overcrowded cells
3. Exclude cells where placement would cause an adjacent occupied cell to flip from non-overcrowded → overcrowded

User asked whether maintaining a constrained index per tick would be expensive. Reply: per-tick full rebuild is borderline at 5000 connections (~1–10ms); incremental dirty-bbox maintenance (5×5 around place/clear/death events) is sub-ms. Constraints 2 and 3 piggyback the per-tick `EnvironmentEngine.cellStatusCache` rebuild — single extra predicate.

### Question 3: Determinism level

| Option | Description | Selected |
|--------|-------------|----------|
| Bit-exact with seed (Recommended) | Same seed + same registration order → identical placements. Tested contract. | ✓ |
| Explainable, not bit-exact | Document algorithm + seed; not enforced. | |
| Status-quo behaviour | Seed exists today via SpawnConfig but nothing tests determinism. | |

**User's choice:** Bit-exact with seed.

### Question 4: Failure mode when constrained set is empty

| Option | Description | Selected |
|--------|-------------|----------|
| Reject with E\|503\|GRID_FULL (Recommended) | Status-quo wire shape, existing token. | ✓ |
| Relax constraints progressively | strictest → allow overcrowding-causing → allow already-overcrowded → 503 | |
| Queue + place next tick | Hold registration on a wait list. | |

**User's choice:** Reject with E|503|GRID_FULL.

### Question 5: More on placement, or move on?

| Option | Description | Selected |
|--------|-------------|----------|
| Move to Partition strategy | Decisions feel locked. | |
| More on placement | Still gaps. | |

**User's choice (free text):** Asked whether rock placement was the right scope for Phase 19 or already done. Reply: already locked Phase 15 D-34/D-35 (`RockGenerator.java`), Phase 19 inherits the rock map as constraint, no re-decision needed. Implicit choice: move to next area.

---

## Partition strategy

### Question 1: Initial framing

| Option | Description | Selected |
|--------|-------------|----------|
| Entity-list iteration (Recommended) | O(entities) not O(cells). Single-threaded core preserved. | |
| Spatial tiling with halo | Future-parallelizable; bigger refactor. | |
| Striped locks per region | Replace single RRWL with K stripes. | |
| Hybrid: entity-list + tile metadata | Both structures; each independently useful. | |

**User's choice (free text):** "Please remind me what this one is about? ELI5?"

Reply provided plain-language explanation of why the requirement exists (per-tick grid scan grows with grid size, not interesting state) and ELI5 of all four options.

### Question 2: User's own counter-proposal

User proposed a **2-pass conflict-graph parallel dispatch** scheme: pass 1 reads intended actions and classifies "order matters" vs "order doesn't matter" based on conflict detection (e.g., two entities targeting the same nutrient = order matters; two entities moving into uncontested cells = order doesn't matter). Pass 2 dispatches actions to threads such that conflicting actions always share a thread. Cited HoMM3 simultaneous-turns-with-fail-soft as analogy.

Reply (substantive critique):
- **Works in concept** — real pattern (deterministic parallel / conflict-graph coloring).
- **Hidden costs**: transitive moves form chains; combat (RPS) at density forms one giant connected component; composite entities (BondedPair / CompositeMember) span cells; environmental diffusion is inherently shared state; cross-thread visibility ordering needs explicit barriers; implementation is ~3–4× the size of plain entity-list iteration.
- **Likely small win at 1000 bots** — entity-list iteration already gives ~50× reduction; conflict-graph parallel adds 2–4× *if* combat doesn't serialize.
- **Recommendation: layer it.** Phase 19 ships entity-list (foundation), conflict-graph parallel becomes its own phase post Phase 21 benchmarks identify the residual hotspot.

### Question 3: Scope partition work for Phase 19

| Option | Description | Selected |
|--------|-------------|----------|
| Entity-list now, dispatch later (Recommended) | Layer it; benchmark first. | ✓ |
| Entity-list + tile metadata | Slightly larger now, smaller later. | |
| Full conflict-graph parallel | Bigger scope, more risk. | |
| Hold for benchmark data | Defer SCALE-07 to Phase 20 / new phase. | |

**User's choice:** Entity-list now, conflict-graph + other parallelism approaches added to backlog. User explicitly noted "additional other ways of parallelising should be explored too" — backlog item is broader than just the conflict-graph proposal.

---

## Concurrency boundary

### Question 1: Read-only parallel sub-step in Phase 19?

| Option | Description | Selected |
|--------|-------------|----------|
| Strict single-threaded (Recommended) | Entity-list iteration alone. | |
| Parallel perception broadcast | PerceptionBroadcaster @Order(50) is per-bot independent. | |
| Parallel TickBroadcaster encoding | Per-session encode then sequential send. | |

**User's choice (free text):** Asked for reminder of "the CLAUDE.md-stated invariant".

Reply quoted the exact CLAUDE.md §Conventions → Concurrency line ("Single-threaded simulation core, all world mutations in tick event handlers; virtual threads for I/O; ReentrantReadWriteLock on WorldGrid"). Then re-asked the question with grounded options.

### Question 2: Re-asked with grounded context

| Option | Description | Selected |
|--------|-------------|----------|
| Strict single-threaded (Recommended) | Entity-list iteration alone. | |
| Parallel perception broadcast | Read-only PerceptionBroadcaster. | |
| Both perception + tick encode | Both PerceptionBroadcaster and TickBroadcaster. | ✓ |

**User's choice:** Both — but **make it Phase 19.1** rather than Phase 19. "Trying to do everything at once is asking for trouble."

This split keeps Phase 19 strictly entity-list iteration (single-threaded), with Phase 19.1 as a follow-up that adds parallel perception + parallel tick-encode. Benefit beyond blast-radius: Phase 21 benchmark numbers can attribute entity-list wins separately from parallelism wins.

---

## Wrap-up

### Question: Ready for context?

| Option | Description | Selected |
|--------|-------------|----------|
| Write context (Recommended) | Lock decisions and write CONTEXT.md. | ✓ |
| Explore more gray areas | Still gaps. | |

**User's choice:** Write context.

---

## Claude's Discretion

- Free-cell index data structure (bitset+sample, indexed list, segment tree, etc.)
- Component / package naming for the index and the live-entity registry
- Dirty-bbox radius if 5×5 turns out wrong
- Golden-trace test format
- Final list of tick handlers refactored to entity-list iteration
- Overcrowding threshold value (read from existing config)

## Deferred Ideas (recap; full content in CONTEXT.md)

- **Phase 19.1 (new)** — parallel `PerceptionBroadcaster` + parallel `TickBroadcaster` encode. Action: `/gsd-add-phase 19.1` after Phase 19 lands.
- **Backlog 999.x (new)** — "Parallelism strategies for tick execution". Includes the user's conflict-graph dispatch proposal plus spatial tiling, striped locks, work-stealing per @Order phase, GPU offload for environmental diffusion, action-locality scheduling. Trigger: Phase 21 benchmark identifies residual hotspot. Action: `/gsd-add-backlog`.
