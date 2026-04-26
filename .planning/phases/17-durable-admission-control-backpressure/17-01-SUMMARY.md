---
phase: 17
plan: "01"
subsystem: admission
tags: [admission, config, spec, rejection-tokens, tdd]
dependency_graph:
  requires: []
  provides:
    - AdmissionConfig @ConfigurationProperties record at paralife.admission.*
    - RejectionToken 9-constant vocabulary (D-07)
    - 17-ADMISSION.md spec doc (D-08)
  affects:
    - Wave 2+ plans (Plan 02 codec, Plan 03 AdmissionGate, Plan 07 retokening)
tech_stack:
  added: []
  patterns:
    - "@ConfigurationProperties record with nested sub-records (Spring Boot 3.4.4)"
    - "Compact constructor validation with IllegalArgumentException"
    - "TDD RED-GREEN cycle with @SpringBootTest + @EnableConfigurationProperties"
key_files:
  created:
    - src/main/java/com/paralife/admission/RejectionToken.java
    - src/main/java/com/paralife/admission/AdmissionConfig.java
    - src/test/java/com/paralife/admission/AdmissionConfigTest.java
    - .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md
  modified: []
decisions:
  - "AdmissionConfig uses single nested record decomposition (TickOverloadConfig + BackpressureConfig) under paralife.admission.* — chosen over split top-level records for namespace cohesion"
  - "RespawnConfig stays as sibling at paralife.websocket.max-respawns-per-session — folding would cause excessive test churn (RESEARCH OQ1)"
  - "Resume token format: 16-char hex from ThreadLocalRandom.nextLong() — 64-bit entropy, < 32 chars on wire"
  - "STALLED FSM state detected via ATTR_STALL_TICK session attribute (Long) — distinguishes STALLED from Unregistered without adding an explicit state enum"
metrics:
  duration: "~4 minutes"
  completed: "2026-04-27"
  tasks_completed: 2
  tasks_total: 2
  files_created: 4
  files_modified: 0
requirements_satisfied: [SCALE-01, SCALE-02]
---

# Phase 17 Plan 01: Admission Config & Spec Foundation Summary

**One-liner:** `AdmissionConfig` record at `paralife.admission.*` with nested `TickOverloadConfig` + `BackpressureConfig`, 9-constant `RejectionToken` vocabulary (D-07), and `17-ADMISSION.md` spec doc (D-08) — pure type declarations, no runtime behavior change.

## What Was Built

### Task 1: RejectionToken constants + AdmissionConfig record (TDD)

**RED:** `AdmissionConfigTest` committed first — failed to compile because `AdmissionConfig` did not exist.

**GREEN:** Two production files implemented:

- `RejectionToken.java` — utility final class with 9 `public static final String` constants matching the D-07 wire vocabulary exactly (lowercase-hyphenated):
  - `MALFORMED="malformed"`, `NO_ACTIVE_ENTITY="no-active-entity"`, `RECONNECT_REQUIRED="reconnect-required"`, `ALREADY_REGISTERED="already-registered"`, `WORLD_FULL="world-full"`, `RESPAWN_CAP="respawn-cap"`, `TICK_OVERLOAD="tick-overload"`, `MAINTENANCE="maintenance"`, `GRID_FULL="grid-full"`

- `AdmissionConfig.java` — `@ConfigurationProperties(prefix = "paralife.admission")` record following the established `PopulationCapConfig` / `RespawnConfig` pattern:
  - Top-level fields: `cap` (int, > 0), `maintenance` (boolean)
  - Nested `TickOverloadConfig`: `highWaterPct` (1–100), `lowWaterPct` (1–100), `windowTicks` (≥1); validates `high > low`
  - Nested `BackpressureConfig`: `outboundQueueSize` (≥1), `graceWindowTicks` (≥1)
  - Compact constructor validates all fields; `defaults()` factory returns cap=256, maintenance=false, high=80%, low=60%, window=10, queue=16, grace=10

**Test coverage:** 5 tests pass — Spring binding test (`@SpringBootTest + @EnableConfigurationProperties`), defaults factory, and 3 validation rejection tests.

### Task 2: 17-ADMISSION.md spec doc

Authored `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` covering:

- **§1 Token Taxonomy** — full D-07 table with HTTP code, token literal, Java constant, emitting site, cause
- **§2 Wire Shape Delta** — `r|<type>|<resumeToken>` (new stalled-recovery form), `S|<entityId>|<resumeToken>` (first sync), `E|<code>|<token>` (token now mandatory)
- **§3 FSM Including STALLED** — ASCII diagram; death-pivot vs stall-pivot orthogonality table
- **§4 Resume-Token Lifecycle** — issuance, storage, re-bind flow, expiry sweep, rotation, threats
- **§5 Tick-Health Gate** — rolling window hysteresis, defaults reasoning, gauge sampling lag caveat
- **§6 Backpressure** — VT-per-session outbound queue, inbound last-write-wins collapse, STALLED transition, grace window
- **§7 Operator Visibility** — counters, gauges, log markers with format examples and grep cheat sheet
- **§8 Migration Notes** — config key changes, deleted components, test migration plan
- **§9 Forward Notes** — D-03 origin-blind, D-02 reproduction exempt, M5 deferrals

`15-SCHEMA.md` not modified (Phase 15 lock respected).

## Deviations from Plan

None — plan executed exactly as written. All three files match the exact code given in `<action>` blocks. Spec doc sections match the required structure verbatim.

## TDD Gate Compliance

| Gate | Commit | Status |
|------|--------|--------|
| RED | `1448589 test(17-01): add failing AdmissionConfigTest` | PASS — compile failed as expected |
| GREEN | `5308be5 feat(17-01): implement RejectionToken constants + AdmissionConfig record` | PASS — 5 tests pass |
| REFACTOR | (none needed) | N/A — code was clean on first pass |

## Known Stubs

None — this plan is pure type declarations with no runtime behavior. No UI rendering, no data source wiring, no mock data.

## Threat Flags

No new network endpoints, auth paths, file access patterns, or schema changes at trust boundaries introduced by this plan. `AdmissionConfig` is a Spring config record — validated in compact constructor, not an execution surface.

## Self-Check: PASSED

Files created:
- FOUND: `src/main/java/com/paralife/admission/RejectionToken.java`
- FOUND: `src/main/java/com/paralife/admission/AdmissionConfig.java`
- FOUND: `src/test/java/com/paralife/admission/AdmissionConfigTest.java`
- FOUND: `.planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md`

Commits verified:
- FOUND: `1448589` — test(17-01): add failing AdmissionConfigTest (RED gate)
- FOUND: `5308be5` — feat(17-01): implement RejectionToken constants + AdmissionConfig record (GREEN)
- FOUND: `94ebdfe` — docs(17-01): author 17-ADMISSION.md admission spec
