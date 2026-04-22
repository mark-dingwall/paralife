# Seed: Offspring entities are grid-only NPCs

**Status:** Backlog. Captured 2026-04-22 during Phase 16 R19 gate review.
**Owner:** (unassigned)
**Depends on:** nothing for the long-term work; M5 visualizer phase for the interim rendering convention.

## Context

`ActionResolver.resolveReproduce` (`src/main/java/com/paralife/engine/ActionResolver.java:530`) produces child particles with ids `child-<N>` and places them directly on the grid via `worldGrid.setEntity(...)`. Children are never registered against `BotRegistry`, have no WebSocket session, and are not driven by any bot brain. They are inert world-grid occupants — they occupy a cell, decay per tick, can be consumed by predators or die from combat, but they never take actions.

This asymmetry was not a design goal. It is an implementation artifact from Phase 09 (Heuristic Bot Brain) where the bot-driven model and the in-world reproduction model were built as separate mechanisms and never unified.

Surfaced during Phase 16 R19 review when evaluating the total-entity DoS cap (see the sibling seed file / 16.1 phase for that work): counting "active entities" was ambiguous because bot sessions and grid-resident children are both "alive" but only one of them has an AI driving it. The cap choice lands on counting all living entities, which is correct for grid-density protection but highlights that NPC offspring exist at all — a distinct concern worth tracking.

## Long-term: offspring should be bot-driven

Two candidate designs when this is picked up:

- **(A) Match to idle bot.** The bot server tracks a pool of bots with no active entity (either freshly connected-but-not-registered, or recently died and respawn-cooldown-expired). On reproduction, bind the new child particle to one of those sessions. Requires a matchmaking step keyed on species.
- **(B) Spin up a new bot client.** The bot handler opens a fresh virtual-thread bot internally (or externally via harness hook), wires it to the new child's entity id. Simpler end-to-end — no matchmaking, no bot-pool state — but requires the bot client to be summonable from server-side, which crosses the client/server boundary that is currently clean.

**Preferred:** (B). Parallels how `BotLauncher` already spawns bots on demand and matches the "externalise scale" shape of the M4 external load harness. The client/server boundary concern is real but negotiable — an internal `BotSummoner` component on the server can own the bot-client lifecycle without exposing it over the wire.

## Interim: render offspring as flowers (M5 visualizer)

Rather than fixing the asymmetry now, accept it and render the gap as narrative: an unassigned child particle is "a flower" — a stationary, edible, ephemeral organism that behaves like a nutrient source. The fiction matches the mechanic (no AI, consumable, decays). This is purely a rendering convention for the M5 visualizer.

Specifically, the M5 visualizer should distinguish:

| Entity on grid | Source | Suggested icon |
|----------------|--------|----------------|
| Bot-driven `Particle` | registered via WebSocket, has `BotRegistry` entry | species primary (C/M/S or equivalent) |
| Offspring `Particle` (id `child-*`) | produced by `ActionResolver.resolveReproduce` | flower (stationary/edible/ephemeral) |
| `BondedPair`, `CompositeMember` | via `CompositeRegistry` | composite primary |
| `Nutrient` | `SimulationEngine` spawn | food icon |
| `Rock` | `RockGenerator` | obstacle |

Representation style (ASCII / emoji / Unicode / tileset) is M5's call — the seed is only pinning the semantic assignment.

The `child-*` id prefix already makes the categorisation trivial at render time (no server-side change needed for the viewer to implement this convention).

## What this seed is NOT

- Not a bug report — the current behaviour is consistent with Phase 09 + reproduction phase contracts.
- Not a blocker for any v1.0 / v2.0 milestone deliverable — reproduction produces valid grid occupants that are correctly consumed and correctly die.
- Not a Phase 16 scope item — surfaced here only because the total-entity cap made the NPC category visible.

## Handoff notes

When this is picked up:
1. Read `ActionResolver.resolveReproduce` (lines 506-552) and the surrounding `childIdCounter`.
2. `BotRegistry` (`src/main/java/com/paralife/engine/BotRegistry.java`) is the session ↔ entity-id map that would need to gain offspring entries.
3. `WorldWebSocketHandler.handleRegister` is the existing bot-registration chokepoint — summoned bots would enter through an in-process variant of this path.
4. Confirm: does the 16.1 (or whatever phase name lands) total-entity cap need to distinguish bot-driven entities from NPCs? If (B) removes the NPC category entirely, the cap simplifies.
