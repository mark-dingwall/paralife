---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: Combination & Emergence
current_phase: 15
current_phase_name: Protocol & Transport Overhaul
current_plan: 11
status: executing
stopped_at: Phase 15 wave 6 complete (10/11 plans) — plans 15-09 (Jetty-native BotClient + BotState record + pure-fn HeuristicBrain + respawn FSM) and 15-10 (WebSocketMetrics bean with 2 live Micrometer meters; paralife.ws.bytes.saved deferred per SCHEMA §13) merged from parallel worktrees. 507/10/3 tests — same 10 pre-existing websocket-upgrade failures; +5 new metrics tests (3 actuator reachability + 2 end-to-end wiring). Wave 7 plan 15-11 pending (test migration, Messages.java deletion).
last_updated: "2026-04-20T17:20:00.000Z"
last_activity: 2026-04-20
progress:
  total_phases: 6
  completed_phases: 4
  total_plans: 29
  completed_plans: 27
  percent: 93
---

# Project State

## Project Reference

See: .planning/PROJECT.md

**Core value:** Emergent spatial behaviour from simple local rules — a testbed for evolving entity intelligence.

**Current focus:** Phase 15 — Protocol & Transport Overhaul

**Status:** Executing
**Current Phase:** 15
**Current Phase Name:** Protocol & Transport Overhaul
**Total Phases:** 16
**Current Plan:** 10
**Total Plans in Phase:** 11
**Progress:** [█████████▍] 93%
**Last Activity:** 2026-04-20

## Current Position

Phase: 15 (Protocol & Transport Overhaul) — EXECUTING
Plan: 11 of 11 (plans 1–10 complete; wave 6 complete; wave 7 plan 11 pending)
Status: Executing
Last activity: 2026-04-20 -- Wave 6 complete: plan 15-09 (Jetty-native BotClient + BotState + pure-fn HeuristicBrain + respawn FSM) and plan 15-10 (WebSocketMetrics bean + 2 live meters) merged from parallel worktrees

## Accumulated Context

### Decisions

7 decisions made during v1.0 (D001-D007). See PROJECT.md Key Decisions table.
Phase 14: 48 decisions captured in 14-CONTEXT.md.
Phase 15 plan 07: Test package alignment chosen over widening package-private seams — CompositePerceptionTest, VisionScopedOvercrowdingTest, TickBroadcasterProjectionTest moved to com.paralife.websocket to preserve encapsulation.
Phase 15 plan 08: FLEEING stored as sibling Map<String,Fleeing> on EnvironmentEngine (NOT via BuffType.FLEEING extension) — flat-record ActiveBuff can't carry 2 extra ints of strike ctx without null-guarding every callsite; sibling map keeps buff dedup/transfer semantics isolated. Lightning record dropped 7-arg back-compat ctor — two record ctors confused Spring @ConfigurationProperties binder. TickBroadcaster.buildTickFrame bumped to public for cross-package test reach (plan locks test to com.paralife.engine; Java has no cross-package package-private).
Phase 15 plan 09: Tasks 2+3 committed together — BotClient.onTick calls HeuristicBrain.decide(TickFrame, BotState, Random); splitting the commits would leave main uncompilable. BotState record replaces overloaded currentType char (Codex #a): species (invariant, C/M/S) × embodiment (SOLO/BONDED_PRIMARY/BONDED_SECONDARY/COMPOSITE_MEMBER) × compositeRole (0..5|null). Jetty @WebSocket annotation endpoint chosen over Session.Listener — simpler callback surface, matches Jetty 12 docs. Raw-socket stub server (computes Sec-WebSocket-Accept per RFC 6455 §4.2.1) used for D-33 gate test — ~40 lines, zero Jetty-server dependency surface. Authority-lite (FEEDER/ATTACKER/REPRODUCER) + passive (DEFENDER/SENSOR) client brains deferred post-MVP per SCHEMA §7 scope note; HeuristicBrain returns null, server auto-fallback covers. Added jetty-websocket-jetty-client:12.0.18 dep (not in Spring Boot managed set). HeuristicBrainTest + BotClientIntegrationTest excluded from gradle test source set — they type against the removed Messages.Perception record / old 2-arg BotClient ctor; plan 15-11 migrates.
Phase 15 plan 10: TWO live Micrometer meters only — paralife.ws.active.sessions (Gauge wraps AtomicInteger updated by SessionRegistry.register/unregister) + paralife.ws.tick.frame.bytes (DistributionSummary recorded by TickBroadcaster post-send). paralife.ws.bytes.saved DEFERRED per SCHEMA §13 — Jetty 12 has no stable hook for post-deflate byte length; all 3 cross-AI reviewers flagged the fabricated 0.6× estimate. Dot-separated lowercase names (Prometheus/Micrometer canonical, RESEARCH Pitfall 7) supersede hyphenated form from CONTEXT D-38. Preserved existing SessionRegistry.unregister(String) signature instead of plan's WebSocketSession example — avoided churn in WorldWebSocketHandler callers. WebSocketMetricsWiringTest addresses review consensus #6 — drives meters via real register/unregister + real TickBroadcaster.onTick (not bean-priming). BotRegistry.register(sessionId, entityId, Position) without a live Particle suffices — TickBroadcaster.buildTickFrame handles null occupants.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-04-20T17:20:00.000Z
Stopped at: Wave 6 complete. Both plans merged back to master from parallel worktrees.
Plan 15-10: WebSocketMetrics @Component with MeterRegistry-injected ctor; 
Gauge M_ACTIVE_SESSIONS wraps AtomicInteger (driven by SessionRegistry
register/unregister); DistributionSummary M_TICK_FRAME_BYTES with
publishPercentiles(0.5,0.95,0.99) recorded inside TickBroadcaster's
per-session send loop via encoded.getBytes(UTF_8).length. 
paralife.ws.bytes.saved literal grep-banned in WebSocketMetrics.java
(verify gate zero hits). MetricsEndpointIntegrationTest primes the
distribution summary in @BeforeEach (unsampled DS returns 404 on some
actuator configs); asserts 200 + "measurements" body for live meters +
404 for the deferred one. WebSocketMetricsWiringTest (real Spring
context, no @MockBean) — register Mockito-mock WebSocketSession →
gauge value +1 on meterRegistry.find(M_ACTIVE_SESSIONS).gauge(),
then unregister → gauge returns to before; broadcaster.onTick drives
ds.count() increment. @AfterEach botRegistry.clear() prevents
cross-test leakage. Commits: cef0ea6 (bean), bf85361 (wiring),
1bd2040 (actuator test), 2fa3371 (wiring test), 8aab866 (summary).
Merge commit: c06692f (Merge plan 15-10 from parallel worktree).
Post-merge test state: 507 tests, 10 failures (all pre-existing
websocket-upgrade integration failures), 3 skipped. +5 new metrics
tests over plan 15-09's 502/10/3 baseline.
Task 1: BotState record (species/embodiment/compositeRole) + BotStateTest (9 methods).
Species char invariant; withChangeCode maps SCHEMA §8.2 c-block codes
(C/M/S → BONDED_PRIMARY, D/N/T → BONDED_SECONDARY, 0-5 → COMPOSITE_MEMBER
with role, Z → SOLO). hasFullAuthority covers SOLO / BONDED_PRIMARY /
COMPOSITE_MEMBER role 0 (LOCOMOTOR).
Tasks 2+3 (coupled): BotClient rewrite to Jetty 12 native
WebSocketClient + ClientUpgradeRequest.addExtensions("permessage-deflate;
server_no_context_takeover"). Post-connect D-33 gate inspects
session.getUpgradeResponse().getHeader("Sec-WebSocket-Extensions");
close(1002) + IllegalStateException when absent (T-15-02). Jackson /
ObjectMapper / LinkedHashMap paths gone. PerceptionCodec.encode/decode
is sole wire I/O. onTick applies c-block change FIRST via
state.withChangeCode then dispatches to brain. handleDeath: v-block D
→ clear entityId, schedule r|<species> via
CompletableFuture.delayedExecutor(cooldown + rng.nextLong(jitter)).
E|429 → disconnect (T-15-04). Back-compat waitForRegistered(long,TimeUnit)
alias preserved for BotLauncher bootstrap. BotLauncher maps ParticleType
→ species char.
HeuristicBrain: decide(Frame.TickFrame, BotState, Random) pure function.
Dead-branch fix (Phase 09 #3): predatorType = myType.predator()
unconditionally. SOLO / BONDED_PRIMARY full cascade (flee effect → flee
predator → chase prey with STARVING+2/MUTATING-1/BUFFED-1 weighting →
consume → reproduce → walk; TOXIN avoidance when low-energy). LOCOMOTOR
(role 0) emits a|V|<3 numpad chars> partial Fisher-Yates ranked.
BONDED_SECONDARY + authority-lite/passive COMPOSITE_MEMBER roles return
null — server auto-fallback. Phase 09 tech debt #4 (JsonNode/LinkedHashMap)
gone too.
Task 4: BotClientClosesOnMissingServerDeflateTest (raw-socket stub
server with RFC 6455 §4.2.1 handshake minus Sec-WebSocket-Extensions) +
RespawnFlowIntegrationTest (@SpringBootTest RANDOM_PORT end-to-end).
Commits: d9d0c85 (BotState+test), c3d7907 (BotClient+HeuristicBrain+Brain
determinism test+BotLauncher+gradle deps/exclusions), 9a63474 (Task 4
tests). Test state: 502 tests, 10 failures, 3 skipped. Net: 503 → 502
after excluding HeuristicBrainTest + BotClientIntegrationTest (plan 15-11
migrates), +9 BotStateTest, +5 HeuristicBrainDeterminismTest, +2 Task 4,
−14 excluded-net. Same 10 pre-existing websocket-upgrade failures.
Added jetty-websocket-jetty-client:12.0.18 dep.
Bot side now fully codec-driven: Jackson fully eliminated from
com.paralife.bot; BotState split removes the type-overloading muddle.
Resume file: .planning/phases/15-protocol-transport-overhaul/15-11-PLAN.md
Next command: /gsd-execute-phase 15
