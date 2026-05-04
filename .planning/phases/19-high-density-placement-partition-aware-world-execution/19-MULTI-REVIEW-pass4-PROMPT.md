# Phase 19 + 19.5 + Pass-2 follow-ups + P22 A1 — pass-4 cross-AI code review

You are reviewing the **shipped Java/Spring code** for Phase 19 of Paralife (a JVM
distributed living-simulation server) plus three rounds of follow-up fixes layered
on top. Three prior multi-review passes have already been done; this pass is
specifically aimed at finding what those three passes missed before the next
phase (P20 — connection multiplexing & runtime tuning) starts work that will
compound any latent bugs in this surface.

## Project context (read this before judging anything)

**Stack:** Java 21 (virtual threads enabled) · Spring Boot 3.4.4 · Gradle Kotlin DSL · JUnit 5.

**Key invariants you must respect when judging code:**

1. **Single-threaded simulation core.** All world-state mutations happen inside
   `@EventListener(TickEvent)` handlers ordered by `@Order`:
   - `SimulationEngine` `@Order(10)` — combat / energy / death / nutrient spawn
   - `EnvironmentEngine` `@Order(14)` — toxin / mutagen / lightning / compost; rebuilds status caches
   - `ActionResolver` `@Order(20)` — drains pending bot actions, resolves moves/consume/reproduce/rest
   - `EnvPostActionReconciler` `@Order(25)` — applies post-action buffs, clears cure-immunity
   - `PerceptionBroadcaster` `@Order(50)` — sends 5×5 neighbourhood perception per bot
   - `TickBroadcaster` `@Order(100)` — broadcasts tick snapshot to all clients

   Virtual threads handle I/O (WebSocket inbound + per-session outbound sender),
   never simulation mutation. **Any race you spot in mutation paths is a real
   finding.** Any race in the I/O paths must respect the
   `synchronized(session)` monitor contract documented in
   `CLAUDE.md` §"Synchronized-session-monitor contract".

2. **WS:entity 1:1.** One WebSocket session per entity, always. Multi-entity
   per session is **out of scope** (D-05/D-21 in Phase 18). Many concurrent
   connections is a stated goal — design ceiling 5 000 connections per JVM.

3. **Env state projection — three layers** (see `CLAUDE.md` §Architecture):
   layer-1 shadow grids `byte[][]`; layer-2 status caches (immutable per-tick
   snapshots, currently published as a `volatile` reference for `cellStatusCache`
   and as a volatile-snapshot mirror for `entityStatusCache` per M1); layer-3
   wire bitmask emitted per-bot in `PerceptionBroadcaster.cellToView`.

4. **Determinism contract (D-06).** Under fixed `paralife.simulation.spawn.seed`
   and **single-threaded registration**, placements are byte-exact repeatable.
   Multi-threaded registration is explicitly **NOT** in scope (was tightened
   from "always" → "single-threaded only" in P19.5 L2).

## What's in this review's scope

This review covers the **complete shipped state** of three rounds of work:

### Round 1 — Phase 19 main (4 plans)

- **Plan 01 — Placement index** (`EligibleCellIndex`): O(1) sparse-set
  free-cell index with three eligibility constraints (no occupant, not
  OVERCROWDED, no adjacent flip-to-overcrowded). Replaces the 50-retry random
  scan in `WorldWebSocketHandler.handleRegister`. Emits `E|503|GRID_FULL` on
  empty eligible set. Bit-exact deterministic under spawn seed.

- **Plan 02 — Live entity registry** (`LiveEntityRegistry`): sparse-set + id→index
  map. `snapshot()` returns row-major-sorted `EntityEntry[]` for deterministic
  iteration. Lifecycle hooks at all 13 structural mutation sites
  (register, dies, composite formation, dissolution, etc.).

- **Plan 03 — Golden-trace equivalence**: `GoldenTraceEquivalenceTest` runs a
  fixed-seed 30-bot 16×16 200-tick scenario twice; per-session SHA-256 digests
  must match each other AND a pinned baseline (`golden-trace-phase19.json`).
  Hooked via `FrameEmitListener` seam on `OutboundSender`.

- **Plan 04 — Entity-list iteration**: replace O(grid-cells) scans in
  `SimulationEngine` (7 sites) and `EnvironmentEngine` (2 sites) with
  `LiveEntityRegistry.snapshot()` walks. `TickBroadcaster` migration was
  intentionally **deferred** (CONSENSUS-H1 OPTION B, user-locked).

### Round 2 — Phase 19.5 review remediation (10 fixes from prior multi-review)

H1 (EligibleCellIndex constraint #2 reads `Cell.flags` directly, not stale
`cellStatusCache`); H2 (bond-formation remaps `BotRegistry` + session
attribute, no spurious DeathNotice); H3 (register `LiveEntityRegistry` BEFORE
`WorldGrid` with rollback); M1 (`entityStatusCache` volatile-snapshot mirror,
ungating Phase 20 parallel reads); M2 (real entity id, no `_` sentinel); M3
(`SimulationEngine.clearStateForTest` + `childIdCounter` reset); M4
(synchronize `EligibleCellIndex.initialize` + reset dense state); M6 (DELETE
`EntityEntry.sessionId` field; D-06 contract scope tightened to
single-threaded reg only); L1 pinned as code comment; L2 folded into M6.

Plus follow-ons: H-A/H-B/H-C entity-id remap consistency post-H2 (resume
token re-binding, death-finalizer entity-id swap, listener notification
ordering) and M-A/M-B/M-C/M-D/M-F pass-2 polish.

### Round 3 — Pass-3 validated findings (already shipped before this review)

- **F1**: composite formation no longer queues spurious `DeathNotice` (uses
  remap path, not unregister+register).
- **F2**: `PerceptionCodec.validateEventCode` accepts `'B'` (absorbed-frame for
  prey sessions) — encode/decode round-trips. New convention adopted: every
  new event code in `Event.java` requires a `PerceptionCodecTest` round-trip case.
- **F3**: `WorldWebSocketHandler.markDead` releases ACTIVE resume tokens to
  prevent unbounded `ResumeTokenRegistry` growth across server lifetime.
- **F4 (partial)**: mutagen survivor-buff RNG order — pass-2 rationale was
  incomplete; covered DoT but not `randomBuff()`. Open question for this pass.

### Round 4 — Phase 22 TD-19.5-A close

- **A1 (commit `42e9251`)**: close-then-interrupt `OutboundSender` detach.
  Previous close path could hang up to 5 minutes when a session dropped while
  the sender VT was mid-write. Now the close sequence drops the session
  reference first, then interrupts the VT — no `join()`, no shared-JVM hang.
  See `OutboundSender.java`, `WorldWebSocketHandler.java`, `OutboundSenderTest.java`.

## What prior reviews already caught and shipped

**Don't re-flag these.** They've all been addressed. Use this list to focus
your attention on what's *still* lurking.

- Cell.flags vs cellStatusCache divergence in EligibleCellIndex constraint #2 (H1)
- Bond-formation BotRegistry + session attribute remap (H2 + H-A/B/C follow-ons)
- LiveEntityRegistry registration ordering vs WorldGrid (H3)
- entityStatusCache mutability for Phase 20 parallel reads (M1)
- EntityEntry sentinel `_` literal (M2)
- Test-isolation childIdCounter / inter-test state pollution (M3)
- EligibleCellIndex.initialize race (M4)
- EntityEntry.sessionId field elimination (M6, L2)
- D-06 contract tightening to single-threaded registration scope (L2)
- `'B'` event code encode/decode round-trip (F2)
- ResumeTokenRegistry leak on death (F3)
- Composite-formation DeathNotice elimination (F1)
- OutboundSender close-then-interrupt detach (A1)

## What we want from THIS pass

Three prior passes hammered the obvious surface. We need fresh, harder questions:

1. **Cross-wave interactions.** Walk the tick pipeline (Order 10 → 14 → 20 →
   25 → 50 → 100) and identify any place where one wave's mutation can
   invalidate state another wave assumed. Special attention:
   - `LiveEntityRegistry.snapshot()` is captured at the start of each engine's
     handler — does any handler mutate the registry mid-iteration in a way
     that would diverge from the snapshot's view?
   - `EligibleCellIndex.notifyChanged` / dirty-bbox recomputation timing vs
     `Cell.flags` updates by `EnvironmentEngine.buildStatusCaches`.
   - `cellStatusCache` and `entityStatusCache` volatile-snapshot publication —
     is the publication ordering correct under JMM, or could a parallel
     reader (Phase 20) see torn state?

2. **Lifecycle hook coverage.** `LiveEntityRegistry` + `EligibleCellIndex` +
   `BotRegistry` + `ResumeTokenRegistry` must all stay consistent across
   13 structural mutation sites. Find a code path that mutates entity
   placement or identity but skips one of these registries. The risk is
   asymmetric: a missed unregister leaks a slot; a missed register makes
   an entity invisible to perception/broadcast.

3. **EntityId remap correctness.** Composite formation, bond dissolution,
   resume-token rebind, and death finalizer all transform an entityId.
   Trace each remap end-to-end and verify the new id surfaces consistently
   across every registry, every cached map keyed by id, and every wire
   frame the affected sessions might receive in the same tick.

4. **Determinism under stress.** D-06 says single-threaded reg + fixed seed
   = byte-exact placements. Find any code path that introduces non-determinism
   into the placement sequence even under that contract — RNG re-seeding,
   non-deterministic map iteration that feeds back into `spawnRng.nextInt`
   call ordering, etc. F4 pass-3 partial flagged a mutagen survivor-buff
   `randomBuff()` ordering concern that pass-2 didn't fully address — verify.

5. **Test coverage gaps.** The `GoldenTraceEquivalenceTest` was already
   flagged in pass-3 for not exercising `ActionResolver` movement / reproduction
   / composite-vote paths via deterministic action scripts. Look at all the
   tests in scope and identify the next-most-likely coverage gap that could
   let a real regression slip past CI.

6. **The OutboundSender close-then-interrupt fix (A1).** Verify the fix is
   complete:
   - Is there any path where the sender VT could still be holding the
     `synchronized(session)` monitor when the close happens?
   - Does the `FrameEmitListener` callback (used by `GoldenTraceCapture`)
     still fire for queued sends after the interrupt — and is that the
     right behaviour?
   - Are there any tests that would catch a regression of the original
     hang in CI without depending on a specific timeout value?

7. **Anything else you'd block at PR-review.** Treat this as adversarial
   review — the goal is to find what three prior reviewer passes missed.
   Nitpicks are not interesting. Real bugs, race conditions, invariant
   violations, leaks, latent failure modes under future load are.

## Output format

For each finding:

- **Severity** — HIGH / MEDIUM / LOW
- **Location** — `path:line` (one or more)
- **What's wrong** — one paragraph, technically precise
- **Why it matters** — observable consequence, ideally with the failure
  scenario named (e.g., "under concurrent reg from a LoadHarness JVM",
  "after a slow client triggers stall and rebinds")
- **Suggested fix** — concrete, minimal-blast-radius

Group findings by severity. If you find no real issues in a focus area
above, say so explicitly — silence is ambiguous.

If you encounter a finding that **was** already shipped per the "What prior
reviews already caught" list, do not include it; it wastes signal.
