---
phase: 18-external-load-harness-harness-identity
plan: "06"
subsystem: integration-test-attribution-docs
tags: [docs, integration-test, attribution, rebind, harness-spec]
dependency_graph:
  requires: [18-01, 18-02, 18-03, 18-04, 18-05]
  provides:
    - AttributionRebindTest: STALLED-pivot attribution lock (T-18-04 mitigated)
    - 18-HARNESS.md: authoritative Phase 18 harness spec
    - CLAUDE.md Connection model subsection codifying WS:entity 1:1
    - LoadTest migrated to harness-tagged path (BotFleet + BotIdentity.harness)
  affects:
    - src/test/java/com/paralife/admission/AttributionRebindTest.java (new)
    - src/test/java/com/paralife/engine/LoadTest.java (migrated)
    - .planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md (new)
    - CLAUDE.md (Connection model subsection added)
tech_stack:
  added: []
  patterns:
    - Awaitility before/after gauge comparison for shared-registry-safe negative assertions
    - BeforeAll reflection-based signature pre-flight for markStalled
    - BotFleet + BotIdentity.harness migration of LoadTest to harness-tagged path
    - 10-section spec doc mirroring 17-ADMISSION.md style
key_files:
  created:
    - src/test/java/com/paralife/admission/AttributionRebindTest.java
    - .planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md
  modified:
    - src/test/java/com/paralife/engine/LoadTest.java
    - CLAUDE.md
decisions:
  - "AttributionRebindTest uses same-BotClient-instance reconnect loop (not fresh constructor) to exercise the actual STALLED-pivot path end-to-end (T-18-04)"
  - "Negative assertion compares unknownGauge.value() before/after rebind rather than asserting absolute < 1.0 — robust against shared registry state from prior tests (Round 2 Codex MEDIUM)"
  - "@BeforeAll uses reflection getMethod() to pre-flight markStalled signature before any test logic runs (Round 2 OpenCode MEDIUM)"
  - "LoadTest migrated to BotFleet.launch + BotIdentity.harness(test-load); DirtiesContext(AFTER_CLASS) for admission gate isolation"
  - "18-HARNESS.md §2 pins canonical harness-id regex as single source of truth for BotIdentity + AttributionSanitizer (Round 2 LOW doc-alignment)"
  - "18-HARNESS.md §5 explicitly documents stalled-held as a valid reason= value alongside <token> and graceful (Round 2 Claude+Codex LOW)"
metrics:
  duration_minutes: 51
  completed_date: "2026-04-28"
  tasks_completed: 2
  tasks_total: 2
  files_created: 2
  files_modified: 2
---

# Phase 18 Plan 06: Integration Tests, 18-HARNESS.md, CLAUDE.md Connection Model Summary

**One-liner:** STALLED-pivot attribution lock integration test (before/after gauge comparison + reflection pre-flight); LoadTest migrated to BotFleet + harness identity; 18-HARNESS.md 10-section spec with stalled-held reason and canonical harness-id regex anchored.

## Tasks Completed

| Task | Description | Commit | Type |
|------|-------------|--------|------|
| 1 | AttributionRebindTest + LoadTest harness migration | 3b73b73 | test |
| 2 | 18-HARNESS.md + CLAUDE.md Connection model + dry-run smoke | 953d82f | docs |

## What Was Built

### AttributionRebindTest (new)

- `@SpringBootTest(RANDOM_PORT)` integration test exercising the full STALLED → E|408 → reconnect → rebind cycle.
- `@BeforeAll` `verifyMarkStalledSignature()`: reflection `getMethod("markStalled", WebSocketSession.class, long.class)` — fails fast if signature drifted (Round 2 OpenCode MEDIUM).
- `stalledPivotPreservesSourceAndHarnessAttribution()`:
  - Boots embedded server, launches `BotClient` with `BotIdentity.harness("test-attribution")`.
  - Snapshots `unknownGauge` BEFORE calling `handler.markStalled()` (Round 2 Codex MEDIUM before/after pattern).
  - Awaits E|408 counter increment (5s budget).
  - Awaits new session with `ATTR_HARNESS=test-attribution`, `ATTR_SOURCE=harness`, and `entityId` present (rebind confirmed, 5s budget).
  - Positive assertion: `paralife.admission.active.entities{source=harness, harness=test-attribution}` gauge >= 1 (5s budget).
  - Negative assertion: `unknownAfter <= unknownBefore` — rebound bot did NOT land in unknown-source bucket.
- Uses same `BotClient` instance's internal reconnect loop (not a fresh constructor) — covers end-to-end rebind path including header re-emission (addresses Codex Plan 01 MEDIUM Round 2 carry-over).
- `@DirtiesContext(AFTER_CLASS)` for admission gate isolation.

### LoadTest (migrated)

- Replaced `BotLauncher` with `BotFleet.launch(uri, count, identity, rampUpSpec, speciesMix, factory)`.
- `BotIdentity.harness("test-load")` passed to all 100 bots.
- `fleet.awaitAllSettled().get(30, SECONDS)` replaces the old 30s fixed-ceiling `BotLauncher.launch`.
- Positive gauge assertion: `paralife.admission.active.entities{source=harness, harness=test-load}` >= 99.
- Comment: `// Single harness id; well within the 64-cap MeterFilter threshold (D-10)`.
- Existing assertions (80% registration, 50% connected, total perceptions/actions) all preserved.

### 18-HARNESS.md (new)

Ten-section spec mirroring `17-ADMISSION.md` style:

| Section | Content |
|---------|---------|
| §1 Architectural Principles | WS:entity 1:1 (D-05/D-21), scale model (D-01/D-02), connection model table |
| §2 Identity Wire Shape | Headers table, **canonical harness-id regex `^[A-Za-z0-9-]{1,32}$`** (Round 2 LOW), cardinality policy (D-10) |
| §3 CLI Surface | Packaging, all flags with env overrides, ramp-up modes (D-16) |
| §4 Attribution Tagging Schema | Source taxonomy, tag schema, metrics gaining tags vs staying scalar (D-12) |
| §5 Log Marker Catalog | Extended ADMISSION/BACKPRESSURE channels; **stalled-held documented** (Round 2 LOW); grep cheat sheet |
| §6 JSON Report Schema | Header/counter wire format (snake_case), write modes, exit_reason values (D-17) |
| §7 Sample Benchmark Commands | Three `java -jar` invocations for **100/500/1000 bots**, all using `--duration <integer-seconds>` |
| §8 Threat Model | T-18-01 through T-18-04 with dispositions |
| §9 Security Domain | `AttributionSanitizer` enforcement point, canonical regex as code-review anchor |
| §10 Forward Notes | `source=offspring` reserved (D-20); multi-entity exception policy (D-21); multi-instance coordination; BotFactory seam (D-19) |

### CLAUDE.md (modified)

Added `### Connection model (Phase 18, D-05 / D-21)` subsection after the existing `### Outbound concurrency` subsection, codifying WS:entity 1:1 with a cross-reference to `18-HARNESS.md §1`.

## Deviations from Plan

None — plan executed exactly as written. All Round 1 and Round 2 review feedback already incorporated into the plan before execution.

## Verification Results

- `./gradlew test --tests "com.paralife.admission.AttributionRebindTest"` — PASSED (1 test, 1.575s)
- `./gradlew test --tests "com.paralife.engine.LoadTest" -PincludeLong=true` — PASSED (1 test, 11.833s)
- `./gradlew cleanTest test -PincludeLong=true` — PASSED (BUILD SUCCESSFUL, all 124 tests)
- `./gradlew loadHarnessJar` — PASSED; shaded JAR built
- `java -jar build/libs/paralife-*-load-harness.jar --help` — exit 0; all option flags listed
- `grep -c '## §' 18-HARNESS.md` — 10 (>= 9 required)
- `grep -c 'stalled-held' 18-HARNESS.md` — 5 (>= 2 required)
- `grep -c 'Connection model' CLAUDE.md` — 1 (>= 1 required)

## Known Stubs

None. All behaviors are implemented and wired end-to-end:
- `AttributionRebindTest` exercises the live STALLED-pivot path against an embedded server
- `LoadTest` gauge assertion verifies real server-side metric wiring
- `18-HARNESS.md` is a complete spec (no placeholder sections)

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced:
- `AttributionRebindTest` is test-only; exercises existing production paths
- `LoadTest` migration uses the same existing admission/attribution infrastructure
- `18-HARNESS.md` and `CLAUDE.md` are documentation only

## Self-Check: PASSED

Files created:
- `src/test/java/com/paralife/admission/AttributionRebindTest.java` — FOUND
- `.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md` — FOUND

Files modified:
- `src/test/java/com/paralife/engine/LoadTest.java` — FOUND
- `CLAUDE.md` — FOUND

Commits:
- `3b73b73` — FOUND (test(18-06): AttributionRebindTest STALLED-pivot attribution lock + LoadTest harness migration)
- `953d82f` — FOUND (docs(18-06): 18-HARNESS.md spec + CLAUDE.md Connection model + dry-run smoke)

All tests green. All acceptance criteria verified.
