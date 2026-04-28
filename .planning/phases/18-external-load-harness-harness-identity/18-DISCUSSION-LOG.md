# Phase 18: External Load Harness & Harness Identity - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in `18-CONTEXT.md` — this log preserves the alternatives considered.

**Date:** 2026-04-28
**Phase:** 18-external-load-harness-harness-identity
**Areas discussed:** Scale model, Identity carriage, Attribution surface, Repro & invocation, 999.2 forward-compat

---

## Gray Area Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Scale model | Single-JVM scale-up vs spawn-N-processes orchestrator vs both | ✓ |
| Identity carriage | New r| slot vs WS handshake header vs query param vs control frame | ✓ |
| Attribution surface | Where source tag shows up: rejected counter, all metrics, log markers, gauges | ✓ |
| Repro & invocation | Packaging, config surface, run report, documentation home | ✓ |
| 999.2 fwd-compat (user add) | Make BotLauncher refactor a non-rewrite when bot-driven offspring lands | ✓ |

**User's choice:** All four pre-suggested areas + a fifth user-added area: forward-compat for bot-driven offspring (backlog 999.2).
**Notes:** "Ensure the BotLauncher is built in such a way that when we hit post-MVP requirement to have spawned entities controlled by a bot, this will be a relatively straightforward enhancement, not a complete rewrite." — threaded through every subsequent area.

---

## Scale model

### Q1: Process model for the harness?

| Option | Description | Selected |
|--------|-------------|----------|
| Single-JVM VT | One harness JVM, one virtual thread per bot. Extends BotLauncher pattern to 1000+. (Recommended) | ✓ (after follow-up) |
| Multi-proc orchestrator | Parent process supervises N child JVMs/containers. Higher op cost; isolates failures. | |
| Both / hybrid | Single-JVM first; design API so a thin orchestrator wrapper can wrap N harnesses | (effectively converged with Yes) |

**User's choice (initial):** "With option #1, can we deploy it multiple times? E.g. 10 x 100 or 4 x 250, etc. Is that a good idea?"
**Follow-up resolution:** Yes — explained that single-JVM with multi-instance deployment is the cleanest scale-out model (each JVM gets a unique harness id; server attribution segments naturally; no orchestrator code needed). User confirmed via re-question with **Yes, single-JVM**.
**Notes:** Hybrid (option 3) effectively converges with single-JVM once each instance has a unique identity — capability is free without writing an orchestrator.

### Q2: Design headroom target for scale model?

| Option | Description | Selected |
|--------|-------------|----------|
| 1000+ exact | Build to comfortably hit Phase 21's 1000+ target. YAGNI past that. | |
| 5000 stretch | (Recommended) Design for ~5000 in one JVM as stretch ceiling so 1000 has clear headroom. | ✓ |
| Open-ended | Don't pin a target; Phase 21 finds where the architecture breaks. | |

**User's choice:** 5000 stretch
**Notes:** Confirmed again later when re-audited through the parallel-WS principle re-frame — 5000 already gives 5x headroom over Phase 21's target and "designed for 5000 concurrent WS connections" is itself a meaningful architectural property. Raising to 10000 was offered post-reframe and explicitly rejected.

### Q3: Ramp-up policy when launching N bots?

| Option | Description | Selected |
|--------|-------------|----------|
| Configurable | (Recommended) Single ramp-up flag with three modes: instant / rate-limited (default 50/sec) / batched waves | ✓ |
| Always rate-limited | Simpler — single mode, no instant option. Loses ability to stress-test admission gate. | |
| Always instant | Match BotLauncher's existing behavior. Hammers admission cap during ramp. | |

**User's choice:** Configurable
**Notes:** Default rate-limited keeps tick-overload gate calm during ramp; instant mode preserved specifically for stress-testing the gate itself.

### Q4: Reuse BotLauncher / BotClient directly, or fork?

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse | Thin layer over BotLauncher + BotClient. Drawback: 30s 'wait all done' assumption. | |
| Refactor BotLauncher | (Recommended) Reuse BotClient; split BotLauncher into a small fleet abstraction shared by both paths. | ✓ (after follow-up) |
| Fork | Build parallel ScaleHarness; reuses only BotClient. Code drift risk. | |

**User's choice (initial):** "Can you ELI5 this one?"
**Follow-up resolution:** Explained that today's `BotLauncher.launch` blocks on a `CountDownLatch.await(30, TimeUnit.SECONDS)` which is the only blocker for 1000+ bot launches; refactoring lifts that ceiling cleanly while keeping `BotClient` plumbing shared. User confirmed via re-question with **Refactor**.

---

## Identity carriage

### Q1: How does harness identity reach the server?

| Option | Description | Selected |
|--------|-------------|----------|
| WS handshake header | (Recommended) X-Paralife-Harness on ClientUpgradeRequest. Zero codec change; orthogonal to locked 15-SCHEMA. | ✓ |
| Query param on URL | ws://host/ws/world?harness=<id>. Slightly simpler server read; harness ids in proxy logs. | |
| New r| slot | r|<species>[|<resumeToken>][|<harnessId>]. Wire-level change; biggest blast radius. | |
| Control frame | First post-connect frame is H|<harnessId>. Codec change + extra round-trip. | |

**User's choice:** WS handshake header
**Notes:** Header path was specifically chosen to avoid editing the milestone-locked 15-SCHEMA.md.

### Q2: Identity granularity?

| Option | Description | Selected |
|--------|-------------|----------|
| Per-process only | (Recommended) One harness id per JVM; bots in that process share it. Bounded cardinality. | ✓ |
| Per-bot | Each bot has its own id. High-cardinality nightmare for Micrometer tags. | |
| Both | Process-level for metrics tag; per-bot as session attribute for log correlation only. | |

**User's choice:** Per-process only

### Q3: Default identity when no harness header is present?

| Option | Description | Selected |
|--------|-------------|----------|
| operator | (Recommended) source=operator default. BotRunner gets explicit header. | |
| unknown | More conservative — distinguish 'BotRunner with explicit identity' from 'random ad-hoc client'. | ✓ |
| Refuse | Reject sessions without identity header. Heavy-handed. | |

**User's choice:** unknown
**Notes:** User picked the more conservative option; followed up with a separate question about whether BotRunner should explicitly tag as `operator` so `unknown` becomes strictly reserved for unidentified ad-hoc sessions.

### Q4 (follow-up): Should BotRunner explicitly set X-Paralife-Source: operator?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — BotRunner tags 'operator' | (Recommended) BotRunner adds source header. unknown reserved for unidentified sessions. | ✓ |
| No — BotRunner stays header-free | Less work; loses the operator/unknown distinction. | |

**User's choice:** Yes — BotRunner tags 'operator'

### Q5 (originally Q4): Cardinality policy?

| Option | Description | Selected |
|--------|-------------|----------|
| Bounded + overflow | (Recommended) Cap distinct values at 64 per JVM lifetime; overflow folds to source=overflow with one warning log. | ✓ (after ELI5) |
| Unbounded | Trust operators not to mint thousands of harness ids. One bad shell script breaks ops. | |
| Whitelist | Operator-configured allowlist in application.yml. Tightest control; ongoing config friction. | |

**User's choice (initial):** "Can you ELI5 this one?"
**Follow-up resolution:** ELI5'd cardinality blow-up risk (every distinct tag value = a separate Prometheus time-series; misconfigured uuid-per-launch can break the metrics backend). User confirmed via re-question with **Bounded + overflow**.

---

## Attribution surface

### Q1: Tag scheme — one tag or two?

| Option | Description | Selected |
|--------|-------------|----------|
| Two tags | (Recommended) source ∈ {operator, harness, unknown, overflow} + harness=<id> only when source=harness. | ✓ |
| One tag | Single source tag carrying both type and instance values. Awkward grafana queries. | |

**User's choice:** Two tags

### Q2: Which metrics get the source/harness tag?

| Option | Description | Selected |
|--------|-------------|----------|
| All admission/backpressure | (Recommended) rejected, active.entities, stalled.sessions, ingress.overwrites all tagged. | ✓ |
| Rejected counter only | D-03 minimum. Loses 'how many active entities does harness-A own' visibility. | |
| All + tick-health | Everything plus tick-health gauges. Doesn't make sense — tick-health is server-global. | |

**User's choice:** All admission/backpressure

### Q3: Log marker prefixes — extend with harness field?

| Option | Description | Selected |
|--------|-------------|----------|
| Extend all | (Recommended) ADMISSION/BACKPRESSURE/TICK-HEALTH lines all gain source/harness fields. | ✓ |
| Session events only | Log harness id at connect/disconnect only; refer by sessionId thereafter. | |
| Don't touch markers | Markers stay as Phase 17 specced; only metrics carry harness identity. | |

**User's choice:** Extend all

### Q4: New per-harness summary log line at session lifecycle?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, lifecycle markers | (Recommended) HARNESS connected/disconnected log markers — clean per-harness session timeline grep. | ✓ |
| No, marker fields suffice | If existing markers carry harness, lifecycle is implicit. Less log churn. | |

**User's choice:** Yes, lifecycle markers

---

## Repro & invocation

### Q1: How do operators launch the harness?

| Option | Description | Selected |
|--------|-------------|----------|
| Shaded jar + gradle task | (Recommended) Runnable fat jar plus gradle task wrapping same main class. | ✓ |
| Gradle only | No standalone artifact. Awkward in containers; Gradle daemon startup cost. | |
| Shaded jar + Docker | Adds container infra to a phase that defers Deployment to M6. | |
| All three | Most surface to maintain. Only worth it if Phase 21 mandates containers. | |

**User's choice:** Shaded jar + gradle task

### Q2: Config surface for the harness?

| Option | Description | Selected |
|--------|-------------|----------|
| CLI + env override | (Recommended) CLI flags for everything; PARALIFE_HARNESS_* env vars override. No YAML. | ✓ |
| YAML config file | harness.yml with all settings; CLI takes --config=path. Adds Spring config infra. | |
| CLI + YAML | Both. CLI for ad-hoc, YAML for pinned profiles. More to keep coherent. | |

**User's choice:** CLI + env override

### Q3: Run report — does the harness emit its own summary?

| Option | Description | Selected |
|--------|-------------|----------|
| JSON summary at exit | (Recommended) Final-state JSON; Phase 21 concatenates per-harness JSONs. | (Superseded by user's evolved answer) |
| Server-side only | No structured client-side output. Loses client-side ground truth. | |
| JSON + per-tick CSV | Granular timeline; balloons at 1000 bots × N harnesses × 30+ min runs. | |

**User's choice:** Free-text — "JSON summary at regular intervals, crashing doesn't lose report. CLI flags dictate whether summary is overwritten (default) or appended (relevant fields only) on each write interval. Suggest anywhere between 10s to 1min for write interval — what's your take?"
**Notes:** User evolved the option significantly — periodic crash-safe JSON write with overwrite/append modes. Resolved follow-up by recommending 30s default (60 snapshots in a 30-min run; aligns with typical Prometheus scrape cadence; balanced for ramp-up granularity). Crash-safety bolted on: atomic temp-rename, JSONL in append mode with one-shot header line, allowed range 10..300s. User confirmed **Yes, 30s default**.

### Q4: Where does harness documentation live?

| Option | Description | Selected |
|--------|-------------|----------|
| 18-HARNESS.md spec | (Recommended) Mirror of 17-ADMISSION.md style; CLAUDE.md gets brief subsection; README one-line link. | ✓ |
| README only | Just a README section. Loses spec-doc discoverability that 17-ADMISSION.md established. | |
| Doc + README + CLAUDE.md | All three. Most discoverable; slightly more sync overhead. | |

**User's choice:** 18-HARNESS.md spec

---

## 999.2 forward-compat

### Q1: Brain abstraction shape during the BotLauncher refactor?

| Option | Description | Selected |
|--------|-------------|----------|
| Brain factory + Brain×Entity | (Initially recommended) Decouple Brain from connection; track Brain×Entity pairs. Required substrate for server-driven assignment. | (Reframed and rejected) |
| Brain-per-connection | Keep BotClient owning one Brain. 999.2 has to either rework BotClient or fork. | |
| Brain factory only, no decoupling | Half-measure. | |
| BotFactory seam (post-reframe) | Smaller, sharper refactor: extract bot-creation. Optional claim params reserved for 999.2. WS:entity 1:1 preserved. | ✓ (after reframe) |

**User's choice (initial):** "Can you explain the difference between Brain factory + Brain×Entity vs the server simply keeping a register of which harnesses have how many bots, and sending a message to the harness with the least: 'here is a new bot, you control it now'?"
**Reframe trigger:** User raised the parallel-WS architectural principle as a project goal — many concurrent connections is the showpiece, multiplexing actively undermines it.
**Resolution:** Explained the two are at different layers (Brain×Entity is internal harness refactoring; server-driven assignment is a wire protocol). Explained that the server-push idea is great and the per-harness `active.entities{harness=...}` gauge being added in Attribution is exactly the data source it would query. Re-recommended the simpler **BotFactory seam** which preserves WS:entity 1:1 and is the right-sized substrate for the future server-push model. User confirmed **BotFactory seam**.
**Notes:** Brain×Entity decoupling rejected as over-abstraction given the now-explicit WS:entity 1:1 commitment.

### Q2: Reserve 'offspring' in the source taxonomy now?

| Option | Description | Selected |
|--------|-------------|----------|
| Reserve now | (Recommended) Document in 18-HARNESS.md; no producer this phase. Dashboards don't churn at 999.2 boundary. | ✓ |
| Don't reserve | Add when 999.2 actually lands. Risks dashboard/query churn later. | |

**User's choice:** Reserve now

### Q3: Multi-entity-per-session connection model?

| Option | Description | Selected |
|--------|-------------|----------|
| Defer with forward-note | (Initially recommended) Today's WS:entity is 1:1. 18-HARNESS.md notes the open question for 999.2. | (Evolved) |
| Build multi-entity now | Out of scope — breaks 15-SCHEMA, redesigns FSM, redesigns admission. | |
| Strongly discourage with rationale; case-by-case exception | User-evolved final form. | ✓ |

**User's choice (initial):** "Can you ELI5?"
**Follow-up resolution:** Explained today's 1:1 connection-to-entity model vs hypothetical multiplexed model where one WS carries multiple entities. Highlighted this would break 15-SCHEMA, FSM, admission shape — two phases of work plus likely an M5 protocol revision.
**Final user position:** Strongly discourage with reason "One of paralife's core goals is a massively parallel system", but note that it may be useful in certain circumstances; inspect case-by-case.
**Notes:** Captured as D-21 — default WS:entity 1:1, exceptions require explicit ADR/spec justification. Not a blanket ban.

---

## Re-frame Audit (post-parallel-WS principle)

User asked whether the parallel-WS principle re-frame changed prior decisions. Audited each:

| Area | Decision | Re-frame impact |
|---|---|---|
| Scale model | Single-JVM VT-per-bot | ✓ Reinforced — VT-per-WS-connection is exactly the showpiece |
| Scale model | Multi-instance deployment (10x100) | ✓ Reinforced |
| Scale model | 5000 stretch design | ◯ Confirmed — 5000 is sufficient stretch; raise to 10K offered and rejected |
| Scale model | Refactor BotLauncher | ✓ Confirmed Bot-per-connection (1:1) framing |
| Identity | WS handshake header | ✓ Reinforced |
| Identity | Per-process granularity | ✓ Reinforced |
| Identity | Default unknown / BotRunner operator / bounded+overflow | ✓ All stay |
| Attribution | Two-tag, all metrics tagged, all log markers extended, HARNESS lifecycle markers | ✓ All stay (some reinforced) |
| Repro | Shaded jar + gradle, CLI + env, 30s JSON, 18-HARNESS.md | ✓ All stay |

**Net:** No prior decisions revised; principle codified explicitly in 18-HARNESS.md and CLAUDE.md.

---

## Claude's Discretion

Captured in `18-CONTEXT.md` Claude's Discretion section:
- Auto-generated harness-id format (UUID short / hostname-pid-suffix / random hex; <32 chars, alnum+dash)
- BotFactory final API name and module location
- Fleet abstraction final name (BotFleet / BotPool / BotSwarm)
- LoadHarness package location (com.paralife.harness vs com.paralife.bot.harness)
- Sub-namespace for max-harness-cardinality config (paralife.admission.* vs paralife.harness.*)
- JSON report field name casing convention (peak_registered vs registered.peak)
- Sample benchmark commands as shell scripts vs documented invocations
- Whether ResumeTokenRegistry rebind path needs ATTR preservation work (strongly suggested yes)
- Whether LoadTest opts into the harness-tagged path (recommended yes)

## Deferred Ideas

Captured in `18-CONTEXT.md` Deferred section:
- Bot-driven offspring producer wire shape (999.2)
- Multi-entity-per-session protocol (D-21 strongly discouraged)
- Cross-harness ramp coordination / unified run report
- Live-tunable harness whitelist
- YAML config file for the harness
- Per-tick CSV report
- Docker packaging (M6)
- /actuator/prometheus (M5; Phase 17 D-20 stays)
- Operator dashboard / live maintenance-mode actuator endpoint (M5)
