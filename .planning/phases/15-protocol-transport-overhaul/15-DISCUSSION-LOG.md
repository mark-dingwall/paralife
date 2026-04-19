# Phase 15: Protocol & Transport Overhaul - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-19
**Phase:** 15-protocol-transport-overhaul
**Areas discussed:** Wire grammar + rock RLE; Entity vocab + zero-trust ID; Jetty + deflate + fan-out; Stateless bot + action format; Entity status bitmask + durations (user-added)

---

## Area selection

User selected all 4 initially-presented areas and added a 5th on `entityStatus` bitmask semantics — specifically raising that STARVING is redundant vs energy X/Y and asking whether durations should be on the wire for TOXIC / MUTATING / BUFFED.

---

## Wire grammar + rock RLE

### Coord encoding

| Option | Description | Selected |
|---|---|---|
| Sparse relative deltas, base36 | Base36 2-char deltas | |
| Dense row-major, omit empties | Placeholder chars, implicit coords | |
| Hybrid dense + RLE | Row-major + run collapsing | |

**User response:** Pivoted — asked whether base64 would be preferable. Subsequent clarification settled on 4-char `[+-]<1 base64><[+-]<1 base64>` per delta pair. Sparse + RLE also chosen ("`+04-02R3>` = 3 rocks east-west starting at (+4,-2)").

**Notes:** User pointed out that `{2}` magnitude was overkill given max vision radius of 3 today. Dropped to `{1}`. Covers ±63, ample.

### Rock generation

| Option | Description | Selected |
|---|---|---|
| Clustered Poisson-disk + accretion | Seeds + stochastic growth | |
| Perlin/simplex threshold | Noise-sampled | |
| Scattered uniform random | No clustering | |
| Designer-authored patterns | Hand-authored config | |

**User choice:** Pre-computed Perlin texture PNGs bundled as resources. Random file choice + rotate + flip. Poisson-disk deferred post-MVP.

### Perception framing

**User response:** Deferred — "assemble all the details that are going to go into a tick message, then it should be easier to decide."

### Rollout

| Option | Selected |
|---|---|
| Big-bang, JSON-out for P/CP/A | |
| Big-bang everything, JSON fully out | ✓ |
| Content negotiation, dual path | |

---

## Self fields (per-tick)

**User selected:** id+type+energy/maxEnergy, sensor radius, active-buff list with durations.

**User note:** "Tempted to have bots remember their own id and type — re-transmitting every tick is wasteful. Only send a type change on bonding/composite/dissolution." Also: "What benefit is there in sending absolute coordinates at this stage? Can't think of anything, useful post-MVP." Plus new ask: "Also transmit a list of events that affected the bot last tick — lightning radius, attacked by predator, etc."

**Resulting decisions:**
- id sent once on `R` (Registered) — client caches.
- type sent only on state-change — client caches.
- Absolute x,y dropped for this phase.
- Per-tick event list introduced as dedicated section.

---

## Entity status bitmask + durations

| Option | Description | Selected |
|---|---|---|
| Drop STARVING (redundant) | | |
| Keep STARVING, drop flags not needed | | |
| Replace bitmask with per-effect duration chars | | |
| Hybrid: bitmask for presence on others, durations for self | ✓ |

**User follow-up:** "Entity state bitmask: STARVING is redundant on self (energy X/Y already sent). For others, MUTATING durations would be strategic — high = seek cover, low = seek prey." Later clarified further: TOXIC on others is redundant because the cell itself has TOXIN_PRESENT; splash-damage rule is protocol-implicit.

**Resulting decisions:**
- Self: no bitmask; state inferred from energy + events + effects.
- Others: bitmask only. Final bits = STARVING, MUTATING, BUFFED (TOXIC dropped as redundant).

---

## Occupant IDs (zero-trust)

| Option | Selected |
|---|---|
| Drop IDs entirely | ✓ |
| Per-session pseudonyms | |
| Keep global IDs | |

---

## Action reply format

**User response:** "Defer until we've decided on format/schema for server tick messages; same format and conventions for both."

---

## State-change delivery

| Option | Selected |
|---|---|
| Optional `type=` section in Perception | |
| Dedicated state-change messages | |
| Send every tick | |

**User choice:** Roll into events section.

**User add-on:** "For status effects, transmit the World Tick ID they expire, not 'X ticks remaining' — network hiccup leaves no ambiguity about whether they're still buffed/debuffed."

Expiry tick ID width locked at 4 chars base64.

---

## Composite perception recipient

| Option | Selected |
|---|---|
| LOCOMOTORs + action-authority roles | |
| All members, same stitched view | |

**User choice:** Role class attribute `public static final boolean hasActionAuthority = false;` overridden true for LOCOMOTOR etc. Non-authority members receive minimal tick frame.

---

## Sensor section

| Option | Selected |
|---|---|
| `cs=<coord>:<r>;...` section | ✓ |
| Sensors as occupants in `c=` zone | |
| Server sends fog-of-war placeholders | |

**User add-on:** Send on-change only (sensor add/destroy/rotate). Client caches. Rotation question raised.

**Rotation clarified:** Deferred post-MVP. Current composites translate only, no orientation.

---

## Perception framing

| Option | Selected |
|---|---|
| Pipe-sectioned with letter tags | |
| Pipe-sectioned, fixed positional | |
| Newline-delimited sections | |

**User choice:** Hybrid — fixed order for always-sent, tagged for optional, pipe-delimited.

---

## Jetty container swap

| Option | Selected |
|---|---|
| Starter-tomcat exclusion + starter-jetty | ✓ (pending research) |
| Explicit JettyServletWebServerFactory bean | |

**User note:** "Research needed. Option 1 if it works, otherwise option 2."

---

## permessage-deflate negotiation

| Option | Selected |
|---|---|
| Programmatic ExtensionConfig + handshake test | ✓ |
| Jetty default + server.compression flag | |
| Graceful fallback / lenient | |

**Post-selection hardening:** Fail-fast on missing extension both sides (server refuses upgrade; client closes session). Prevents silent uncompressed fallback.

---

## Fan-out infrastructure

| Option | Selected |
|---|---|
| Build minimal BroadcastChannel + CompressedFrame | |
| Keep as stub for narrative | |
| Rock map only | |
| Defer entirely to M005 visualizer phase | ✓ |

**User reasoning:** Per-bot perception is unique per session (different coord center), so precompress fan-out doesn't apply. Tick heartbeat is tiny and stateless under no-context-takeover. Real use case is observer / visualizer world-state stream — belongs with UI and transport evaluation (SSE vs WebSocket vs HTTP/2 push). Pushed to M005.

**Note for M005:** evaluate BroadcastChannel approach against alternatives before committing.

---

## Metrics

| Metric | Selected |
|---|---|
| Bytes saved (deflate vs raw) | ✓ |
| Compress ops saved (fan-out dedup) | ✓ (later dropped with fan-out) |
| Active sessions | ✓ |
| Perception payload size histogram | ✓ |

Final shipping metrics: bytes saved, active sessions, payload histogram.

---

## Merge tick + perception

| Option | Selected |
|---|---|
| Merge: one tick message per bot | ✓ |
| Keep Tick heartbeat for bots | |

**User clarification:** Rename merged component `TickBroadcaster` (not `PerceptionBroadcaster`) — semantically the tick carries everything needed for action decision.

---

## Effects / events / vision split + lightning

| Option | Selected |
|---|---|
| Confirmed split + Option B (lightning in vision) | ✓ |
| Same split, lightning stays in events | |
| Merge effects + events | |

**User add-on:** Lightning-flee formula `flee_strength = CONSTANT - magnitudeOfDistanceVectorToLightningStrike`. New effect `FLEEING (FleeFromX, FleeFromY, FleeTicks)` proposed to carry post-strike behaviour.

**Scope flag (raised by user):** Recognised as borderline gameplay-addition. Moved to deferred ideas pending formal schema review decision (D-50).

---

## Cooldown surfacing

| Option | Selected |
|---|---|
| Per-action-type effect codes (`CR`, `CA`, …) | ✓ |
| Generic `COOLDOWN:<action>` | |

---

## Out-of-vision event markers

| Option | Selected |
|---|---|
| `<coord><markerChar>` single token | ✓ |
| Separate tagged `far=` section | |

**Namespace collision flagged:** `L` = lightning marker OR LOCOMOTOR role char. Disambiguation rule pending formal schema review (D-50).

---

## Status block sentinel

**User response:** Requested full option breakdown — laid out Options A (positional single sentinel), B (dual separator), C (fixed 2-char when present). Then asked to pause before deciding.

**Resolution:** Deferred to formal schema review (D-50). Full option breakdown preserved here for the review.

### Option A — positional, single sentinel `:`
```
+4-23              rock, no statuses
+4-23:1            cellStatus=1 only (entityStatus zero implicit)
+4-23:1A           both
+4-23:0A           entityStatus only (explicit zero for cellStatus — 1 wasted char)
```

### Option B — dual separator `:` and `;`
```
+4-23              no statuses
+4-23:1            cellStatus only
+4-23;A            entityStatus only
+4-23:1;A          both
```

### Option C — fixed 2-char block when `:` present
```
+4-23              no statuses
+4-23:10           cellStatus=1, entityStatus=0
+4-23:1A           both
+4-23:0A           entityStatus only (explicit zero)
```

### Byte cost comparison per cell

| Scenario | Option A | Option B | Option C |
|---|---|---|---|
| No status | 0 | 0 | 0 |
| cellStatus only | 2 | 2 | 3 |
| entityStatus only | 3 | 2 | 3 |
| Both | 3 | 4 | 3 |

Parse complexity: C < A < B.

---

## FLEEING effect shape

**Proposed shape:** `F:<expiryTick>,<fromX>,<fromY>` in `b=` list.

**Status:** Deferred to formal schema review with the decision on whether to pull lightning-flee into Phase 15 or leave as Phase 14 follow-up.

---

## Scope check-in (user-initiated)

**User asked:** "Are we getting out of scope for this phase? Our context window is quite full."

**Assessment:** Borderline drift confirmed — FLEEING effect + lightning flee formula + far-perception consumers are new gameplay mechanics that entered via the wire-format conversation. Moved to deferred pending formal schema review.

**Closure decision:** Stop discuss-phase here. Flag "Formal schema review + grammar lock" (D-50) as mandatory pre-planning gate. Six small open items become Claude's Discretion or schema-review topics for the planner. CONTEXT.md committed with full schema preview as review artefact.

---

## Claude's Discretion

- Exact direction char encoding (alphabetic N/E/S/W/U/V/X/Y vs numeric 0–7) — D-48.
- Season multiplier encoding in `season=` section — D-49.

## Deferred Ideas

- FLEEING effect + lightning flee formula + far-perception concrete consumers — D-50 pending.
- BroadcastChannel + CompressedFrame + observer / visualizer stream → M005.
- Composite rotation → post-MVP.
- Multi-tick gestation reproduction → post-MVP.
- Persistent POISONED debuff → post-MVP.
- Bot memory / fog of war → post-MVP (Priority #1 from Phase 14).
- Clustered Poisson-disk rock generator → post-MVP.

## Housekeeping Flagged

- `.planning/REQUIREMENTS.md` R15–R19 renumbering to Phase 16 — D-47.
- `.planning/ROADMAP.md` line 134 edit — fan-out success criterion moves to M005.
