---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: Combination & Emergence
current_phase: 15
current_phase_name: Protocol & Transport Overhaul
current_plan: 10
status: executing
stopped_at: Phase 15 wave 6 plan 09 complete (9/11 plans) — Jetty-native BotClient with permessage-deflate negotiation + D-33 client-side gate (close 1002 on missing Sec-WebSocket-Extensions, T-15-02 mitigation), Jackson/LinkedHashMap paths fully eliminated from com.paralife.bot, codec-only wire I/O, BotState record splits species/embodiment/compositeRole (Codex #a review), HeuristicBrain refactored to pure function decide(TickFrame, BotState, Random) with Phase 09 tech debt #3 dead-branch fix, LOCOMOTOR vote path (a|V|<3 numpad>), authority-lite deferred post-MVP (null return + test contract), client-side respawn FSM keeps session open across deaths. 502/10/3 tests (same 10 pre-existing failures; HeuristicBrainTest + BotClientIntegrationTest added to exclusion — 15-11 migrates)
last_updated: "2026-04-20T17:10:00.000Z"
last_activity: 2026-04-20
progress:
  total_phases: 6
  completed_phases: 4
  total_plans: 29
  completed_plans: 26
  percent: 90
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
**Progress:** [█████████░] 90%
**Last Activity:** 2026-04-20

## Current Position

Phase: 15 (Protocol & Transport Overhaul) — EXECUTING
Plan: 10 of 11 (plans 1–9 complete; wave 6 plan 09 complete; wave 6 plan 10 in parallel worktree; wave 7 plan 11 pending)
Status: Executing
Last activity: 2026-04-20 -- Plan 15-09 complete (Jetty-native BotClient + D-33 client-side gate + codec-only wire I/O + BotState record + pure-fn HeuristicBrain with dead-branch fix + respawn FSM)

## Accumulated Context

### Decisions

7 decisions made during v1.0 (D001-D007). See PROJECT.md Key Decisions table.
Phase 14: 48 decisions captured in 14-CONTEXT.md.
Phase 15 plan 07: Test package alignment chosen over widening package-private seams — CompositePerceptionTest, VisionScopedOvercrowdingTest, TickBroadcasterProjectionTest moved to com.paralife.websocket to preserve encapsulation.
Phase 15 plan 08: FLEEING stored as sibling Map<String,Fleeing> on EnvironmentEngine (NOT via BuffType.FLEEING extension) — flat-record ActiveBuff can't carry 2 extra ints of strike ctx without null-guarding every callsite; sibling map keeps buff dedup/transfer semantics isolated. Lightning record dropped 7-arg back-compat ctor — two record ctors confused Spring @ConfigurationProperties binder. TickBroadcaster.buildTickFrame bumped to public for cross-package test reach (plan locks test to com.paralife.engine; Java has no cross-package package-private).
Phase 15 plan 09: Tasks 2+3 committed together — BotClient.onTick calls HeuristicBrain.decide(TickFrame, BotState, Random); splitting the commits would leave main uncompilable. BotState record replaces overloaded currentType char (Codex #a): species (invariant, C/M/S) × embodiment (SOLO/BONDED_PRIMARY/BONDED_SECONDARY/COMPOSITE_MEMBER) × compositeRole (0..5|null). Jetty @WebSocket annotation endpoint chosen over Session.Listener — simpler callback surface, matches Jetty 12 docs. Raw-socket stub server (computes Sec-WebSocket-Accept per RFC 6455 §4.2.1) used for D-33 gate test — ~40 lines, zero Jetty-server dependency surface. Authority-lite (FEEDER/ATTACKER/REPRODUCER) + passive (DEFENDER/SENSOR) client brains deferred post-MVP per SCHEMA §7 scope note; HeuristicBrain returns null, server auto-fallback covers. Added jetty-websocket-jetty-client:12.0.18 dep (not in Spring Boot managed set). HeuristicBrainTest + BotClientIntegrationTest excluded from gradle test source set — they type against the removed Messages.Perception record / old 2-arg BotClient ctor; plan 15-11 migrates.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-04-20T17:10:00.000Z
Stopped at: Plan 15-09 complete (wave 6, parallel with 15-10 in separate worktree).
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
Resume file: .planning/phases/15-protocol-transport-overhaul/15-10-PLAN.md
(executing in parallel worktree).
Next command: /gsd-execute-phase 15
