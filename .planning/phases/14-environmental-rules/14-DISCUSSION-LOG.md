# Phase 14 Discussion Log

**Discussed:** 2026-04-17
**Participants:** User (mark-dingwall), Claude (Opus 4.7)

## Session Arc

Initial scope from ROADMAP: "Richer environment — toxin spread, food regeneration, decay, and spatial effects." Discussion expanded dramatically beyond env effects alone, covering protocol redesign, transport changes, and stateless bot architecture. Mid-discussion decision to **split into two phases**: Phase 14 = env rules, Phase 15 = protocol overhaul.

## Gray Areas Explored (in order)

### 1. Effect selection and scope
- User brainstormed: toxin, corpse composting, plague, lightning
- "Plague" reframed as "mutagen" — damage now, mutation buff later
- Fire considered, then **dropped** (post-MVP)
- Locked: 4 effects — toxin, mutagen, lightning, compost

### 2. Toxin mechanics
- Spawn trigger: seasonal Poisson weather event (independent from combat/death)
- Path: Catmull-Rom spline adapted from user's microbe sketch (`microbes.js`)
- Damage: proportional to cell intensity
- Resistance: per-type coefficients
- Splash damage: attacker in toxic cell takes fractional damage back

### 3. Mutagen mechanics
- Spawn: seasonal Poisson (rare), Spring peak
- Propagation: **strain gossip** (stochastic cell infection with ±1 strain mutation)
- Survivor buff: random pick from +1 attack / +1 movement / +1 sensor / -1 upkeep
- Composite members: universal `-1 upkeep` + role-specific perk
- Buff duration = infection duration × 10
- **Attack-accelerates-cure** gamble mechanic locked

### 4. Lightning + compost
- Lightning: single-tick dual radius damage + fertility boost
- Compost: 100% cell, 50% on 8 neighbors

### 5. Storage architecture
- **Parallel shadow grids per effect** (not unified) — cache locality + additive evolution
- CA diffusion + decay chosen over reaction-diffusion (entity RPS already provides emergence)
- BuffRegistry shadow pattern (like BotRegistry, CompositeRegistry)

### 6. Messages & observability — DEEP DIVE
Initial observability question rejected by user ("what is the purpose of Messages.Tick?"). Re-grounded Tick as bot heartbeat + global context + test observables, NOT visualizer.

Expanded into full protocol redesign:
- User proposed **compact text schema** with relative coords: `A2N|C3S|E2N|F5B`
- Evolved through multiple rounds: base36 coords, case-sensitive ASCII, sparse encoding
- Debated absolute vs relative coords → locked absolute (translation cost negligible, relative's savings eaten by required position echo)
- User asked about encoding control (UTF-8 vs ISO 8859-1) — locked ASCII-safe TextMessage
- User proposed **server-precomputed event sequences** via virtual thread → multicast to all bots
- User questioned always-on deflate → explored `permessage-deflate` handshake negotiation
- User explored **precompress + fan-out** optimization → locked (memorable for portfolio showcase value)
- Explored `server_no_context_takeover` trade-offs → locked `=true` for shareable compressed frames
- User questioned double-compression → explained RSV1 bit + Jetty `WebSocketExtension` API
- User asked about sending DEFLATE dictionary to clients → protocol supports it (priming), but doesn't help

### 7. Architectural philosophy
- User's instinct: "do we need client state at all?" → locked **stateless bots**
- User's instinct: "do we need a broadcast channel?" → locked **single per-bot channel** (bots react to what they see, season derivable from tick number)
- User considered client-side rock stitching → locked **zero-trust perception** instead (following WoW radar-hack lesson)

### 8. Portfolio context saved to memory
User disclosed goal: portfolio project targeting Canva backend engineer role. Saved as project memory. Elevated precompress fan-out from "over-engineered" to "valuable showcase pattern." Never to be mentioned in code/commits/docs.

### 9. Phase split
Scope became too large. Split into Phase 14 (env effects) and Phase 15 (protocol/transport), with Phase 16 becoming the emergence tests. Phase 14 ships with existing JSON protocol.

### 10. Remaining Phase 14 details
- Seasonal Poisson rates per event type
- Sine-scaled λ formula verified correct
- Max 1 active event per type (debuff stacking post-MVP)
- Toxin path gen adapted from user's microbe sketch
- Mutagen timing (20-30 tick infection, buff = 10× duration)
- Compost formula (100% cell, 50% neighbors)
- Rock gen deferred to Phase 15

### 11. Status bitmasks
- Base64 alphabet `0-9A-Za-z_-` locked
- Two separate bitmasks: cell + entity (user caught conflation mid-discussion)
- Fixed-width (not omit-if-zero) — parse simplicity wins
- Cell: OVERCROWDED (vision-scoped) + TOXIN_PRESENT + MUTAGEN_ZONE
- Entity: STARVING + TOXIC + MUTATING + BUFFED
- BONDED/COMPOSITE_MEMBER are distinct entity types, not status flags
- Vision-scoped overcrowding: server computes per bot using only visible neighbors

### 12. Behavioral implications
- STARVING: easy prey (Phase 13 progressive scaling)
- TOXIC: attacker splash damage
- MUTATING: attacks accelerate cure (gamble)
- BUFFED: informational only

## Key User Pushbacks (Claude learned from)
- "Did we include fire in MVP? I thought we dropped it until post-MVP?" — Claude had scope-crept unprompted
- "What is the purpose of message tick?" — Claude made observability assumptions without grounding
- Typo correction: "send entire state on init" (not "in it")
- "I think we already mapped out starvation modifiers" — Claude re-proposed Phase 13 work

## Memory Written
- `project_portfolio_goal.md` — project targets Canva backend role, favor impressive Spring patterns, never mention in code/commits/docs

## Outcome
- `14-CONTEXT.md` — 48 locked decisions for Phase 14
- ROADMAP updated: Phase 15 = Protocol & Transport Overhaul, Phase 16 = Emergent Behavior Tests (renumbered)
- Ready for `/gsd-plan-phase 14`
