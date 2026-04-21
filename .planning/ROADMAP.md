# Paralife Roadmap

- ✅ **v1.0 Foundation & Living Simulation** — Phases 01-10 (shipped 2026-04-12)
- 🚧 **v2.0 Combination & Emergence** — Phases 11-16 (in progress)

---

<details>
<summary>v1.0 — Foundation & Living Simulation (shipped 2026-04-12)</summary>

## Roadmap v1.0: Foundation & Living Simulation

### Phase 01: Spring Boot Project Scaffold
**Goal:** Gradle project with Spring Boot 3.4.4, Java 21 toolchain, virtual threads, health endpoint
- [x] Complete

### Phase 02: World Grid Model
**Goal:** WorldGrid (256x256 Cell[][]), Position with toroidal wrapping, GridConfig from application.yml
- [x] Complete

### Phase 03: Tick Engine
**Goal:** TickEngine on virtual thread, configurable interval, TickEvent via ApplicationEventPublisher
- [x] Complete

### Phase 04: WebSocket Connection Manager
**Goal:** WorldWebSocketHandler at /ws/world, SessionRegistry, TickBroadcaster, JSON message protocol
- [x] Complete

### Phase 05: Integration Test — 100 Bots
**Goal:** HundredBotIntegrationTest proving 100 concurrent WebSocket clients receive tick events
- [x] Complete

### Phase 06: Entity Model & Grid Refactor
**Goal:** Sealed Entity interface (Particle/Rock/Nutrient), Cell record with flags, WorldGrid refactored to Cell[][]
- [x] Complete

### Phase 07: Simulation Engine — Physics & Combat
**Goal:** 4-phase SimulationEngine (combat, energy decay, death, nutrients), overcrowding via Cell flags
- [x] Complete

### Phase 08: Perception & Action Protocol
**Goal:** BotRegistry, PerceptionBroadcaster (5x5), ActionResolver (move/consume/reproduce/rest)
- [x] Complete

### Phase 09: Bot Client — Heuristic AI
**Goal:** HeuristicBrain with 6-priority decision cascade, BotClient, BotLauncher for bulk launch
- [x] Complete

### Phase 10: Integration Test — Population Dynamics & Load
**Goal:** PopulationDynamicsTest (500 ticks, all 3 types survive), LoadTest (100 bots, no corruption)
- [x] Complete

</details>

---

## Roadmap v2.0: Combination & Emergence

Simple entities combine into complex organisms when conditions align.

### Phase 11: Bonding Rules Engine
**Goal:** Define which entity types can combine and under what conditions; implement bonding logic as a new simulation phase
**Depends on:** Phase 10 (stable entity system with RPS dynamics)
**Plans:** 2/2 plans complete
Plans:
- [x] 11-01-PLAN.md — BondedPair entity type, BondingConfig, processInteractions refactor
- [x] 11-02-PLAN.md — Downstream wiring (PerceptionBroadcaster, TickBroadcaster, Messages.Tick bondCount)
**Success Criteria:**
- Bonding rules configurable via application.yml or dedicated config
- At least two bonding conditions implemented (e.g., proximity + energy threshold)
- Bonding events recorded and observable in tick output
- Unit tests verify bonding triggers and rejects under correct conditions

### Phase 12: Composite Entities
**Goal:** Multi-cell organisms with shared state that move and act as a unit on the grid
**Depends on:** Phase 11 (bonding rules must exist before composites can form)
**Plans:** 6/6 plans complete
Plans:
- [x] 12-01-PLAN.md — Foundation types: CompositeMember entity, Role enum, CompositeConfig, CompositeRegistry, Message types
- [x] 12-02-PLAN.md — Composite formation from BondedPairs + CompositeEnergyDistributor
- [x] 12-03-PLAN.md — Reactive role actions (FEEDER/ATTACKER/REPRODUCER) + STV movement voting
- [x] 12-04-PLAN.md — Composite combat mechanics + dissolution/degradation/panic zone
- [x] 12-05-PLAN.md — SENSOR stitched perception + TickBroadcaster composite count + BotRegistry remap
- [x] 12-06-PLAN.md — Integration test: full composite lifecycle
**Success Criteria:**
- Composite entity representation on Cell[][] grid (each member occupies a cell)
- Shared energy pool across composite members
- Composites move as a unit (coordinated movement)
- Death of a member triggers composite dissolution or degradation
- Unit tests for composite formation, movement, and dissolution

### Phase 13: Energy & Metabolism System
**Goal:** Richer energy model — entities need food, starve, and reproduce based on metabolic state
**Depends on:** Phase 12 (composites need metabolism to be interesting)
**Plans:** 4/4 plans complete
Plans:
- [x] 13-01-PLAN.md — Per-type metabolic profiles (MetabolicProfile, StarvationConfig), per-type decay/combat in SimulationEngine, surplus-gated reproduction with cooldown and SPORE bonuses in ActionResolver
- [x] 13-02-PLAN.md — BondedPair hybrid vigor metabolism (BondingConfig extension, bond decay cost) + progressive starvation mechanic (FLAG_STARVING, combat modifiers, nutrient boost)
- [x] 13-03-PLAN.md — Soil fertility (FertilityInitializer, FertilityConfig) + seasonal cycles (SeasonTracker, SeasonsConfig) + fertility/season-modulated nutrient spawning + Messages.Tick season data
- [x] 13-04-PLAN.md — Integration test: 300-tick population dynamics with full metabolism system
**Success Criteria:**
- Metabolism rates differ by entity type and composite size
- Starvation mechanic with configurable thresholds
- Reproduction gated by energy surplus (existing mechanic extended)
- Nutrient consumption activates Cell.nutrientLevel (currently inert — tech debt item from phase 06)
- Integration test showing population dynamics with metabolism enabled

### Phase 14: Environmental Rules
**Goal:** Four spatially-propagating environmental effects (toxin, mutagen, lightning, compost) stressing the Phase 13 metabolism system, with seasonal Poisson triggering, CA shadow grids, mutagen survivor buffs, and perception-visible status bytes
**Depends on:** Phase 13 (metabolism must work before environment can stress it)
**Plans:** 6/6 plans complete
Plans:
- [x] 14-01-PLAN.md — Foundation: EnvironmentConfig + BuffRegistry + EnvironmentEngine skeleton + Messages.CellView extension + compost death hook + Wave-0 test scaffolding
- [x] 14-02-PLAN.md — Toxin spread: Catmull-Rom spline path + CA diffusion + per-type resistance damage + splash damage
- [x] 14-03-PLAN.md — Mutagen outbreak: strain gossip + infection DoT + survivor buff grant (Particle + composite role-specific) + attack-accelerates-cure
- [x] 14-04-PLAN.md — Lightning strike: single-tick dual-radius damage + fertility boost
- [x] 14-05-PLAN.md — Perception integration + buff effect application + HeuristicBrain status-aware decisions
- [x] 14-06-PLAN.md — Integration test: 300-tick seeded full-stack validation of all four effects + buff grants + population stability
**Success Criteria:**
- At least two new environmental effects beyond overcrowding (e.g., toxin spread, food regeneration)
- Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern)
- Spatial propagation of effects across ticks (not just local)
- Configurable parameters in application.yml
- Unit tests for each environmental effect

### Phase 15: Protocol & Transport Overhaul
**Goal:** Replace JSON per-tick messaging with compact text perception protocol; switch container Tomcat→Jetty; enable permessage-deflate with server_no_context_takeover on both sides; redesign bots as stateless reactive agents; zero-trust vision filtering
**Depends on:** Phase 14 (env effects provide diverse message types to exercise codec)
**Requirements:** R20, R21, R22, R23, R24, R25, R26, R27, R28, R29
**Plans:** 11/11 plans complete
Plans:
- [x] 15-01-PLAN.md — Housekeeping: ROADMAP fan-out-criterion removal + REQUIREMENTS R20-R29 add (R15-R19 remap to Phase 16)
- [x] 15-02-PLAN.md — Codec package scaffolding (Frame/Coord/KindData sealed hierarchies, Base64Codec, ParseCursor, CodecException, stub PerceptionCodec, RED 13-vector test)
- [x] 15-03-PLAN.md — Container swap + Jetty deflate customizer + handshake + refusal integration tests
- [x] 15-04-PLAN.md — PNG-based rock generation (RockConfig + RockGenerator + 5 perlin PNGs + determinism test)
- [x] 15-05-PLAN.md — PerceptionCodec encode/decode implementation (13 vectors GREEN) + error-path + DoS-sentinel test (CHECKPOINT for Vector 9 coord ambiguity)
- [x] 15-06-PLAN.md — WorldWebSocketHandler rewrite (codec I/O + respawn FSM + E|429 cap) + ActionResolver verb dispatch + IRV vote replacement + Messages.java partial strip + AlarmQueue bean
- [x] 15-07-PLAN.md — Delete old heartbeat TickBroadcaster + git mv engine/PerceptionBroadcaster → websocket/TickBroadcaster (preserve vision-scoped OVERCROWDED mask-and-OR)
- [x] 15-08-PLAN.md — TickBroadcaster projection rewrite to codec Frames + authority tiers (full/authority-lite/passive) + FLEEING effect + alarm routing + zero-trust test
- [x] 15-09-PLAN.md — BotClient Jetty-native transport + D-33 client-side enforcement + codec replaces Jackson + HeuristicBrain pure function + Phase 09 tech debt #3 fix + respawn flow
- [x] 15-10-PLAN.md — Micrometer metrics (WebSocketMetrics bean: paralife.ws.active.sessions / tick.frame.bytes) + actuator reachability test — bytes.saved deferred per SCHEMA §13
- [x] 15-11-PLAN.md — Test migration: all Messages.* references ported to codec Frame; Messages.java deleted; 100-bot performance gate; ./gradlew test green in isolation
**Success Criteria:**
- Compact text Perception protocol with sparse relative coords, base64 encoding, fixed-width status bitmasks
- Stateless bot redesign — server sends complete authoritative state per tick; no client-side caching
- Zero-trust perception filtering — server sends only data derivable from entity's vision range
- Tomcat → Jetty container swap, permessage-deflate negotiated both sides with `server_no_context_takeover=true`
- PerceptionCodec with shared encode/decode used by both server and bot client
- Bot→Server compact action format
- Actuator custom metrics (bytes saved, active sessions, tick frame bytes distribution)
- Rock generation algorithm defined (enables run-length encoding decision)
- All existing tests pass under new protocol

### Phase 15.1: Bot Operator CLI
**Goal:** Ship the permanent `BotRunner` operator CLI + `runBot` Gradle task so Phase 15 UAT Tests 3/5/6/7 can be retried with real evidence against a live `bootRun` server. Single-JVM, single-process, 100-bot hard cap enforced at the CLI boundary; multi-process / harness-ID / 1000+ scale deferred to M4.
**Depends on:** Phase 15 (codec transport + keepalive + respawn FSM must be in place)
**Success Criteria:**
- `com.paralife.bot.BotRunner` main class invoking `BotLauncher` with clean SIGTERM/SIGINT shutdown
- Gradle `runBot` JavaExec task discoverable via `./gradlew tasks --group=application`
- Hard cap rejects `count > 100` with a clear error citing the validated envelope
- Optional `--duration` arg for scripted UAT; default runs until interrupted
- `CLAUDE.md` architecture table corrects `com.paralife.bot` (no longer "test-only, not deployed")
- Phase 15 UAT Tests 3/5/6/7 retried against `runBot`, evidence captured from server logs and curl probes
- Phase 15 UAT: Tests 3/5/6 recovered with real evidence; Test 7 found a latent server-side gap (own-death `v|D` never reaches wire) — handed off to Phase 15.2
- M4 seed planted: "External load harness as deployment primitive"

### Phase 15.2: Own-Death Event Wiring
**Goal:** Close the latent Phase 15 gap surfaced by 15.1 UAT Test 7 retry — emit own-death `v|D` events onto the wire so `BotClient.handleDeath()` can fire the respawn FSM end-to-end. Scope is TickBroadcaster `buildEventsForBot` (server-side event-source wiring the in-file comment at `src/main/java/com/paralife/websocket/TickBroadcaster.java:636` already flagged as 'plans 15-09+ wire the remaining event sources').
**Depends on:** Phase 15.1 (BotRunner is the only way to exercise the fix under real combat — Test 7 becomes the acceptance gate)
**Success Criteria:**
- TickBroadcaster emits `Event('D', ...)` to a bot on the tick its entity is finalized by DeathFinalizer (and no later, so BotClient sees one D event per death)
- No wire-protocol changes — reuses the existing v-block slot
- Phase 15 UAT Test 7 passes with real respawn-loop evidence: `Entity registered: ...-r1` on same session ID, respawnCount increments, E|429 fires only at the configured cap
- Phase 15 UAT returns to `status: complete`, `passed: 7`

### Phase 16: Emergent Behavior Tests
**Goal:** Validate that complex behaviors emerge from the combination of bonding, composites, metabolism, environment, and protocol
**Depends on:** Phase 15 (all systems including new protocol must be active)
**Requirements:** R15, R16, R17, R18, R19
**Plans:** 7 plans
Plans:
- [x] 16-01-PLAN.md — RNG injection refactor across 10 hot-path sites + BotClient:294 fix + application.yml seed properties (R15)
- [x] 16-02-PLAN.md — EmergenceMetrics bean + 4 counter increment sites + EMERGENCE log markers + wiring test (R17)
- [x] 16-03-PLAN.md — TickEngine paralife.tick.work.ms DistributionSummary + .gitignore fixtures entry (R18)
- [x] 16-04-PLAN.md — 5 test helpers (PopulationHistory, RunFixtureWriter, TestLogCapture, TriggerWatcher, SeededBotLauncher) (R15/R16/R17/R18)
- [x] 16-05-PLAN.md — CompositeFormationDeterminismTest engine-direct 3-run identity assertion (R15)
- [x] 16-06-PLAN.md — EmergenceStabilityLoadTest full-stack 100-bot 1000-tick long-run with D-04/D-07/D-11 assertions + fixture dump (R16/R17/R18)
- [ ] 16-07-PLAN.md — 16-EMERGENCE.md narrative + R19 full-suite gate checkpoint (R17/R19)
**Success Criteria:**
- Deterministic seed test demonstrating composite formation from simple rules
- Population dynamics test with metabolism + environment showing stable ecosystems
- At least one emergent pattern documented (e.g., composite clustering, niche formation)
- Load test with composites verifying no performance regression from v1.0 baseline
- All v1.0 tests still pass (no regressions)

---

## Progress

| Phase | Name | Status |
|-------|------|--------|
| 01 | Spring Boot Project Scaffold | ✅ Complete |
| 02 | World Grid Model | ✅ Complete |
| 03 | Tick Engine | ✅ Complete |
| 04 | WebSocket Connection Manager | ✅ Complete |
| 05 | Integration Test — 100 Bots | ✅ Complete |
| 06 | Entity Model & Grid Refactor | ✅ Complete |
| 07 | Simulation Engine — Physics & Combat | ✅ Complete |
| 08 | Perception & Action Protocol | ✅ Complete |
| 09 | Bot Client — Heuristic AI | ✅ Complete |
| 10 | Integration Test — Population Dynamics & Load | ✅ Complete |
| 11 | Bonding Rules Engine | ✅ Complete |
| 12 | Composite Entities | ✅ Complete |
| 13 | Energy & Metabolism System | Planning complete |
| 14 | Environmental Rules | Context complete |
| 15 | Protocol & Transport Overhaul | ✅ Complete (UAT 7/7 pass after 15.2) |
| 15.1 | Bot Operator CLI | ✅ Complete |
| 15.2 | Own-Death Event Wiring | ✅ Complete |
| 16 | Emergent Behavior Tests | In progress (5/7 plans complete) |
