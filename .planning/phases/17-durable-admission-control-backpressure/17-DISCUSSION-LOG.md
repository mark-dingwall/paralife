# Phase 17: Durable Admission Control & Backpressure - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in `17-CONTEXT.md` — this log preserves the alternatives considered.

**Date:** 2026-04-27
**Phase:** 17-durable-admission-control-backpressure
**Areas discussed:** Admission policy shape, Rejection vocabulary, Backpressure + overload, Operator visibility

---

## Admission Policy Shape

### Cap dimension

| Option | Description | Selected |
|--------|-------------|----------|
| Single global cap | One number; max active externally-driven entities. Simple knob; Phase 18/21 friendly | ✓ |
| Per-type quotas (CAT/MEM/SPORE) | Three caps; protects RPS balance; heavier config + metric surface | |
| Hybrid global + per-type band | Global hard cap + per-type soft band; most complex | |

**User's choice:** Single global cap.

### In-sim reproduction & cap

| Option | Description | Selected |
|--------|-------------|----------|
| Stay exempt | Cap gates external load injection only; world rules govern in-sim spawn | ✓ |
| Count fully | Cap is a true world-density ceiling; changes sim semantics | |
| Soft throttle at high density | Probability-throttled repro between threshold and cap | |

**User's choice:** Stay exempt for now.
**Notes:** "Stay exempt for now, but note when we wire up spawned entities to bot clients, we *will* want these to count towards the cap." Captured in D-02 as a forward-note: bot-driven offspring (backlog 999.2) will arrive via `r|` and fall under admission naturally; code must stay neutral on origin.

### 999.1 PopulationCapConfig fate

| Option | Description | Selected |
|--------|-------------|----------|
| Replace + delete | New `AdmissionConfig` under `paralife.admission.*`; old class + key removed; tests rewritten; 999.1 closes as superseded | ✓ |
| Keep + alias | Old key still bound for back-compat, deprecated | |
| Keep config, rewrite semantics | Same key+name, rule + tokens change underneath | |

**User's choice:** Replace + delete.

### Source/origin field on `r|`

| Option | Description | Selected |
|--------|-------------|----------|
| Defer to Phase 18 | Origin-blind admission; SCALE-04 introduces harness identity later | ✓ |
| Reserve operator slots now | Walls off N slots for BotRunner; speculative since nothing identifies as operator yet | |
| Tag header, no quota | Pre-plumb wire shape; risk Phase 18 picks different model | |

**User's choice:** Defer to Phase 18.
**Notes:** User asked for ELI5 first; explanation provided (operator vs harness scarcity scenario, why each option costs/benefits). User then selected Defer.

---

## Rejection Vocabulary

### Wire form for rejection reasons

| Option | Description | Selected |
|--------|-------------|----------|
| Stable token | `E|<code>|<token>`; tests + clients branch on token | ✓ |
| Token + human suffix | `E|<code>|<token>|<human-text>`; codec parsing more involved | |
| Free text (status quo) | Keep current strings; fails SCALE-01 "testable reasons" | |

**User's choice:** Stable token.

### Code split

| Option | Description | Selected |
|--------|-------------|----------|
| 429 family + token discriminator | All admission rejections 429; token discriminates; 503 reserved for placement | ✓ |
| Distinct codes per cause | 429 / 503 / 507 / 503 mix per cause; bikeshed risk | |

**User's choice:** 429 family + token.

### Spec home for token taxonomy

| Option | Description | Selected |
|--------|-------------|----------|
| New 17-ADMISSION.md | Phase-scoped spec doc; 15-SCHEMA.md gets pointer; Phase 15 stays locked | ✓ |
| Extend 15-SCHEMA.md | One spec doc; muddies Phase 15 milestone boundary | |

**User's choice:** New 17-ADMISSION.md.

### GRID_FULL fate

| Option | Description | Selected |
|--------|-------------|----------|
| Keep separate, retoken | Stays 503; renamed token `grid-full`; preserves admissible-but-physically-packed semantic | ✓ |
| Fold into admission | `E|429|grid-full`; one bucket; loses semantic split | |

**User's choice:** Keep separate, retoken.

---

## Backpressure + Overload

### Outbound slow-consumer policy (round 1)

| Option | Description | Selected |
|--------|-------------|----------|
| Per-session lag budget | Track ticks-behind; close on budget exceed; WS 1008 | (clarification asked) |
| Drop frame silently | Lossy snapshot; session sees gaps | |
| Synchronous send with timeout | Block tick broadcaster up to T ms; one stuck socket holds thread | |

**User's response:** Asked clarifying question — "if client can't decide in time, fine to take no action that tick? Becomes easy prey, eventually starves, no zombies?"

**Claude's clarification:** Distinguished inbound (already harmless via `pendingActions` collapse) from outbound (server-side broadcaster blocking on slow socket = tick-thread stall). Outbound is the actual problem.

### Outbound async mechanism (round 2 — refined options after user's protocol proposal)

User proposed: "async sending; once a client begins to drop ticks we should stop sending it more ticks; if/when it reconnects with action, reply error 408/409 reconnect-required; either send reconnect welcome along with that frame, or wait for next request."

Refined design captured:
- Async outbound (off tick thread)
- Queue overflow → STALLED state, server stops broadcasting to it
- Inbound from STALLED → `E|408|reconnect-required`, WS closed
- Entity grace-held; resume-token wire path

### Stall code

| Option | Description | Selected |
|--------|-------------|----------|
| E|408|reconnect-required | 408 Request Timeout matches "took too long"; token tells client what to do | ✓ |
| E|409|stalled | 409 already used for "already-registered"; risks confusion | |
| E|429|slow-consumer | Stays in admission/backpressure family; loses "reconnect required" affordance | |

**User's choice:** E|408|reconnect-required.

### Entity fate on stall

| Option | Description | Selected |
|--------|-------------|----------|
| Reap immediately | Closed WS + entity removed; reconnect = fresh r|; matches current cleanupBot path | |
| Grace window (~10 ticks) | Entity persists briefly; client reconnects with resume token to re-bind same entityId | ✓ |
| Keep entity alive, no resume | Persists as NPC; complicates Phase 16 emergence assumptions | |

**User's choice:** Grace window.

### Async outbound mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Jetty native async write + bounded queue | `sendStringByFuture` / write callbacks; smallest delta from today; slow-client detection implicit | |
| Virtual-thread-per-session | Per-session VT drains per-session bounded queue; idiomatic Java 21; queue.size() = explicit gauge | ✓ |
| Single sender VT + multiplex | One VT serialises all outbound; one slow socket can still slow others | |

**User's response (round 1):** Asked for pros/cons of #1 and #2.
**Claude's response:** Detailed pro/con on both. #1 = framework does the work, fewer lines, depends on Jetty primitives. #2 = build it in-app, idiomatic for Paralife, more code, full control. Recommended #2 because it matches the codebase's blocking-with-VTs philosophy.
**User's choice (round 2):** Virtual-thread-per-session.
**Notes:** "Ok makes sense, let's go with Virtual-thread-per-session and document (comments, CLAUDE.md) why." Documentation-of-rationale is captured as a hard requirement in D-10.

### Ingress flood

| Option | Description | Selected |
|--------|-------------|----------|
| Add explicit rate counter | Per-session overwrites/tick counter; sustained → kill | (clarification asked) |
| Status quo (silent collapse) | Last-write-wins is enough | |
| Hard rate limit per session | Drop above N/sec | |

**User's clarifying question:** "Can you explain more about what we're guarding against here? P queue?"

**Claude's clarification:** Sim correctness already protected by `ConcurrentHashMap` collapse. The guard would protect CPU/IO + give operator visibility. P queue doesn't help — collapse already keeps the latest action which is the highest-priority signal by definition.

### Ingress flood (refined)

| Option | Description | Selected |
|--------|-------------|----------|
| Add counter only, no kill | Counter for visibility; no auto-disconnect | ✓ |
| Defer entirely | Sim safe; CPU bounded by Jetty; add nothing | |
| Add counter + threshold kill | Sustained overwrites → close with `E|429|ingress-flood` | |

**User's choice:** Add counter only, no kill.

### Resume token wire shape

| Option | Description | Selected |
|--------|-------------|----------|
| Issue in S|, present in r| | First r| → `S|<entityId>|<resumeToken>`; reconnect r|<type>|<resumeToken>; missing = fresh | ✓ |
| Separate handshake frame | New frame `H|`; cleaner separation but bigger schema mutation | |
| Reuse entityId as token | Client remembers entityId; impersonation risk | |

**User's choice:** Issue in S|, present in r|.

### Tick gate watermark expression

| Option | Description | Selected |
|--------|-------------|----------|
| Config knobs, sensible defaults | `paralife.admission.tick-overload.high-water-pct=80`, low=60, window=10; @TestPropertySource overrides | ✓ |
| Hardcoded with comments | Constants in code; inflexible for benchmarks | |
| Operator-overridable via actuator | Live mutation; M5 territory | |

**User's choice:** Config knobs.

### Maintenance-mode toggle

| Option | Description | Selected |
|--------|-------------|----------|
| Config property, restart required | `paralife.admission.maintenance: true|false` | ✓ |
| Actuator endpoint, live | Runtime toggle; M5 vocab risk | |
| Both | Maximum flexibility; double the spec surface | |

**User's choice:** Config property.

### Tick-health gate (yes/no)

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, threshold + hysteresis | Above hi-water for K ticks → `E|429|tick-overload`; clears below lo-water | ✓ |
| No, trust cap alone | Doesn't defend against pathological spikes | |

**User's choice:** Yes.

### Maintenance mode (yes/no, top-level)

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, simple flag | Phase 17 ships flag; M5 may add actuator endpoint | ✓ |
| Defer to M5 | M5 owns operator UX | |

**User's choice:** Yes, simple flag.

---

## Operator Visibility

### Counter granularity

| Option | Description | Selected |
|--------|-------------|----------|
| Tagged counter | `paralife.admission.rejected{reason=<token>}`; standard Prometheus idiom | ✓ |
| Counter-per-reason | One Micrometer counter per token; bean churn | |
| Both | Duplicate signal | |

**User's choice:** Tagged counter.

### Gauges to expose (multi-select)

| Gauge | Selected |
|-------|----------|
| `admission.active.entities` | ✓ |
| `admission.maintenance` | ✓ |
| `tick.health.work-time-ms` | ✓ |
| `backpressure.stalled.sessions` | ✓ |

**User's choice:** All four.

### Log marker prefix

| Option | Description | Selected |
|--------|-------------|----------|
| Split prefixes | `ADMISSION` / `BACKPRESSURE` / `TICK-HEALTH`; mirrors Phase 16 `EMERGENCE` style | ✓ |
| Single ADMISSION prefix | One grep target; less semantic | |
| Structured JSON only | No prefix; better for shippers, worse for grep | |

**User's choice:** Split prefixes.

### Prometheus scrape

| Option | Description | Selected |
|--------|-------------|----------|
| Defer to M5 | Counters via `/actuator/metrics/<name>`; M5 owns Prometheus surface | ✓ |
| Wire it now | Useful for Phase 21 benchmark scraping; bigger M5 spillover | |

**User's choice:** Defer to M5.

---

## Claude's Discretion

Captured in `17-CONTEXT.md` D-section under "Claude's Discretion":
- Default cap value, outbound queue size, grace-window duration, tick-overload watermark defaults, `AdmissionConfig` decomposition, resume-token format, ingress-overwrite counter granularity, VT lifecycle hookpoints, resume-token registry placement, `AdmissionGate` package placement, `RespawnConfig` fold/sibling decision.

## Deferred Ideas

- Backlog `999.2` (bot-driven offspring) — D-02 forward note ensures admission code stays origin-neutral.
- Backlog `999.1` (temporary cap) — closes as superseded by Phase 17.
- Source/origin tag on `r|` — Phase 18 (SCALE-04).
- Reserved operator slots — Phase 18.
- `/actuator/prometheus` — M5.
- Live maintenance-mode actuator endpoint — M5.
- Per-type quotas — single-counter design admits trivial extension; not built this phase.
- Subjective spawn-scoring leaderboard via SSE/observer — M5 (logged from user notes during gray-area selection).
