---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: Combination & Emergence
current_phase: 15
current_phase_name: Protocol & Transport Overhaul
current_plan: 9
status: executing
stopped_at: Phase 15 wave 5 plan 08 complete (8/11 plans) — codec-driven TickBroadcaster projection with authority tiers (FULL/AUTHORITY_LITE/PASSIVE), FLEEING applier (sibling Map<String,Fleeing> in EnvironmentEngine, populated by lightning outer ring), AlarmQueue drain wired in LOCOMOTOR v-block, zero-trust test green (ZeroTrustFilteringTest, 3/3). D-40 mask-and-OR preserved VERBATIM. 503/13/3 tests (13 pre-existing failures, 3 new ZeroTrust passes; TickBroadcasterProjectionTest + CompositePerceptionTest added to exclusion — 15-11 migrates)
last_updated: "2026-04-20T15:15:00.000Z"
last_activity: 2026-04-20
progress:
  total_phases: 6
  completed_phases: 4
  total_plans: 29
  completed_plans: 25
  percent: 86
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
**Current Plan:** 9
**Total Plans in Phase:** 11
**Progress:** [████████▌░] 86%
**Last Activity:** 2026-04-20

## Current Position

Phase: 15 (Protocol & Transport Overhaul) — EXECUTING
Plan: 9 of 11 (plans 1–8 complete; wave 5 complete; wave 6 up next)
Status: Executing
Last activity: 2026-04-20 -- Plan 15-08 complete (codec-driven TickBroadcaster projection with authority tiers + FLEEING applier + AlarmQueue drain + zero-trust test)

## Accumulated Context

### Decisions

7 decisions made during v1.0 (D001-D007). See PROJECT.md Key Decisions table.
Phase 14: 48 decisions captured in 14-CONTEXT.md.
Phase 15 plan 07: Test package alignment chosen over widening package-private seams — CompositePerceptionTest, VisionScopedOvercrowdingTest, TickBroadcasterProjectionTest moved to com.paralife.websocket to preserve encapsulation.
Phase 15 plan 08: FLEEING stored as sibling Map<String,Fleeing> on EnvironmentEngine (NOT via BuffType.FLEEING extension) — flat-record ActiveBuff can't carry 2 extra ints of strike ctx without null-guarding every callsite; sibling map keeps buff dedup/transfer semantics isolated. Lightning record dropped 7-arg back-compat ctor — two record ctors confused Spring @ConfigurationProperties binder. TickBroadcaster.buildTickFrame bumped to public for cross-package test reach (plan locks test to com.paralife.engine; Java has no cross-package package-private).

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-04-20T15:15:00.000Z
Stopped at: Plan 15-08 complete. Wave 5 complete (solo plan).
Task 1: full TickBroadcaster rewrite — PerceptionCodec.encode(Frame.TickFrame)
replaces Jackson; ObjectMapper + Messages.* imports gone. Authority tiers
(FULL/AUTHORITY_LITE/PASSIVE) in dedicated enum + tierOf() + sensorRadiusFor()
helpers. Kind-code switch over Entity sealed subtypes: C/M/S solo, D/N/T
bonded primary (secondary hidden per D-28), 0-5 composite roles, R rock,
F nutrient. Rock RLE with 63-cell cap along 8 numpad directions; env
differences split runs. AlarmQueue drain in LOCOMOTOR v-block, MAX_V_ENTRIES
cap with truncation warn. Send-on-change g block via per-session
rosterHash map. D-40 vision-scoped OVERCROWDED mask-and-OR preserved
VERBATIM per T-15-03 (grep-anchored in plan verify gate).
Task 2: FLEEING applier in EnvironmentEngine — sibling Map<String,Fleeing>
(expiryTick, strikeX, strikeY) populated by applyLightningAtInternal's
outer-ring scan (alive occupants flee), per-tick expireFleeing sweep;
Lightning config gains fleeingTicks (default 8) via new yaml key.
Task 3: ZeroTrustFilteringTest (3 methods, all green) with regex-anchored
CELL_ENTRY_KIND pattern — no coarse wire.contains("D") false-positives.
Commits: 7b0c47d (Task 2 FLEEING), 1c07a7a (Task 1 rewrite + Lightning ctor
fix + gradle exclusions), 74a8051 (Task 3 test + empty-cell filter fix).
Test state: 503 tests, 13 failures, 3 skipped. Net: 532 → 500 after
excluding TickBroadcasterProjectionTest + CompositePerceptionTest (32
Jackson-era tests; plan 15-11 migrates), +3 new ZeroTrust = 503. 13
pre-existing websocket-upgrade integration failures unchanged.
Wire path now fully coherent: handler on codec, TickBroadcaster on codec.
Resume file: .planning/phases/15-protocol-transport-overhaul/15-09-PLAN.md
Next command: /gsd-execute-phase 15
