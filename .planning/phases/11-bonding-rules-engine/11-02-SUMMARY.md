---
phase: 11-bonding-rules-engine
plan: 02
title: "Bonding Rules Engine — Downstream Consumers"
one_liner: "BondedPair wired into PerceptionBroadcaster, Messages.Tick bondCount field, TickBroadcaster reads SimulationEngine bond count"
subsystem: websocket, engine
tags: [bonding, perception, tick-broadcast, observability]
dependency_graph:
  requires:
    - "Entity.BondedPair sealed permit (Plan 01)"
    - "SimulationEngine.getLastTickBondCount() (Plan 01)"
  provides:
    - "PerceptionBroadcaster BondedPair arm with BONDED_PRIMARY_SECONDARY type string"
    - "Messages.Tick bondCount field"
    - "TickBroadcaster SimulationEngine injection for bond count"
  affects:
    - "WebSocket tick message JSON schema (new bondCount field)"
    - "Perception cell views for bonded entities"
tech_stack:
  added: []
  patterns:
    - "Constructor injection of SimulationEngine into TickBroadcaster (websocket -> engine dependency)"
key_files:
  created:
    - src/test/java/com/paralife/websocket/TickBroadcasterTest.java
  modified:
    - src/main/java/com/paralife/engine/PerceptionBroadcaster.java
    - src/main/java/com/paralife/websocket/Messages.java
    - src/main/java/com/paralife/websocket/TickBroadcaster.java
    - src/test/java/com/paralife/engine/PerceptionBroadcasterTest.java
decisions:
  - "BondedPair perception type string uses BONDED_PRIMARY_SECONDARY format (e.g. BONDED_CATALYST_SPORE) -- distinct from base types, parseable by clients"
  - "bondCount added as 4th field to Messages.Tick record -- backward-compatible JSON addition"
metrics:
  duration_minutes: 5
  completed: "2026-04-13T08:53:00Z"
  tasks_completed: 2
  tasks_total: 2
  tests_added: 8
  files_created: 1
  files_modified: 4
---

# Phase 11 Plan 02: Bonding Rules Engine -- Downstream Consumers

BondedPair wired into PerceptionBroadcaster, Messages.Tick bondCount field, TickBroadcaster reads SimulationEngine bond count. 8 new tests (2 perception, 6 tick broadcaster). Full suite 202 tests green.

## What Was Built

### PerceptionBroadcaster BondedPair arm
- Updated `cellToView()` switch from placeholder `"BONDED"` to descriptive `"BONDED_" + bp.primaryType() + "_" + bp.secondaryType()`
- Produces strings like `BONDED_CATALYST_SPORE`, `BONDED_MEMBRANE_CATALYST` -- distinct from base types, parseable by clients

### Messages.Tick bondCount field
- Added `int bondCount` as 4th field to `Tick` record: `Tick(long tickNumber, long timestamp, int entityCount, int bondCount)`
- Backward-compatible JSON addition -- existing clients that don't read bondCount will ignore it

### TickBroadcaster SimulationEngine injection
- Added `SimulationEngine` as constructor dependency (websocket -> engine, same direction as existing PerceptionBroadcaster dependency)
- Tick message now reads `simulationEngine.getLastTickBondCount()` for bondCount field
- No circular dependency -- SimulationEngine does not depend on TickBroadcaster

## Deviations from Plan

None -- plan executed exactly as written. The PerceptionBroadcaster BondedPair arm existed from Plan 01 auto-fix with placeholder "BONDED" type; this plan updated it to the descriptive format as intended.

## Test Summary

| Suite | Tests Added | Status |
|-------|-------------|--------|
| PerceptionBroadcasterTest | 2 (cellToViewBondedPair, cellToViewBondedPairReflectsTypes) | PASS |
| TickBroadcasterTest | 6 (new file: broadcast, skip empty, skip closed, fields, bondCount, bondCountZero) | PASS |
| Full test suite | 202 total | PASS (zero regressions) |

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 916d3a2 | feat | Wire BondedPair into downstream consumers (PerceptionBroadcaster, Messages.Tick, TickBroadcaster) |
| 728d4cc | test | Add tests for BondedPair perception and bondCount tick messages |

## Known Stubs

None. All data paths are wired end-to-end: SimulationEngine bondCount -> TickBroadcaster -> Messages.Tick -> client JSON. BondedPair -> PerceptionBroadcaster -> CellView -> client perception.

## Self-Check: PASSED

All 6 files verified present. Both commits (916d3a2, 728d4cc) verified in history. Full test suite (202 tests) passes.
