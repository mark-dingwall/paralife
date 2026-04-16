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
**Goal:** Richer environment — toxin spread, food regeneration, decay, and spatial effects
**Depends on:** Phase 13 (metabolism must work before environment can stress it)
**Success Criteria:**
- At least two new environmental effects beyond overcrowding (e.g., toxin spread, food regeneration)
- Environmental effects use Cell flags system (extending FLAG_OVERCROWDED pattern)
- Spatial propagation of effects across ticks (not just local)
- Configurable parameters in application.yml
- Unit tests for each environmental effect

### Phase 15: Protocol & Transport Overhaul
**Goal:** Replace JSON per-tick messaging with compact text perception protocol; switch container to Jetty; enable permessage-deflate with precompress fan-out infrastructure; redesign bots as stateless reactive agents
**Depends on:** Phase 14 (env effects provide diverse message types to exercise codec)
**Success Criteria:**
- Compact text Perception protocol with sparse relative coords, base36 encoding, fixed-width status bitmasks
- Stateless bot redesign — server sends complete authoritative state per tick; no client-side caching
- Zero-trust perception filtering — server sends only data derivable from entity's vision range
- Tomcat → Jetty container swap, permessage-deflate negotiated both sides with `server_no_context_takeover=true`
- Precompress fan-out infrastructure (`BroadcastChannel` + `CompressedFrame`) ready for future visualizer broadcast channel
- PerceptionCodec with shared encode/decode used by both server and bot client
- Bot→Server compact action format
- Actuator custom metrics (bytes saved, compress ops saved, active sessions)
- Rock generation algorithm defined (enables run-length encoding decision)
- All existing tests pass under new protocol

### Phase 16: Emergent Behavior Tests
**Goal:** Validate that complex behaviors emerge from the combination of bonding, composites, metabolism, environment, and protocol
**Depends on:** Phase 15 (all systems including new protocol must be active)
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
| 15 | Protocol & Transport Overhaul | Not started |
| 16 | Emergent Behavior Tests | Not started |
