---
phase: 18-external-load-harness-harness-identity
plan: "02"
subsystem: websocket-handshake-identity
tags: [websocket, handshake-headers, log-markers, server-side-sanitizer, admission-gate, tdd]
dependency_graph:
  requires: [18-01, 18-03]
  provides:
    - AttributionSanitizer shared harness-id sanitizer (server+client defense-in-depth)
    - WorldWebSocketHandler reads X-Paralife-Source/Harness at handshake; stashes ATTR_SOURCE/ATTR_HARNESS
    - HARNESS connected/disconnected log markers on every connection lifecycle
    - AdmissionGate session-bearing evaluate(req, session) overload
    - ADMISSION rejected log marker with source/harness attribution
    - BACKPRESSURE markers extended with AttributionTagger.formatLogFields
    - TickHealthMonitorScalarTest locks D-12 scalar invariant (non-vacuous)
  affects:
    - src/main/java/com/paralife/admission/AttributionSanitizer.java (created)
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java (modified)
    - src/main/java/com/paralife/admission/AdmissionGate.java (modified)
tech_stack:
  added: []
  patterns:
    - Shared sanitizer helper extracted per Round 2 Codex HIGH review feedback
    - Session-bearing overload with null-session back-compat shim (evaluate(req) → evaluate(req, null))
    - ReflectionTestUtils.setField to drive real TickHealthMonitor threshold breach without mocking
    - Non-vacuous ListAppender test: fails clearly if no TICK-HEALTH lines emitted
key_files:
  created:
    - src/main/java/com/paralife/admission/AttributionSanitizer.java
    - src/test/java/com/paralife/admission/AttributionSanitizerTest.java
    - src/test/java/com/paralife/websocket/WorldWebSocketHandlerHandshakeHeaderTest.java
    - src/test/java/com/paralife/websocket/HarnessLogMarkerTest.java
    - src/test/java/com/paralife/admission/AdmissionLogMarkerTest.java
    - src/test/java/com/paralife/engine/TickHealthMonitorScalarTest.java
  modified:
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/admission/AdmissionGate.java
decisions:
  - "TickHealthMonitor drive strategy: ReflectionTestUtils.setField on TickEngine.lastTickWorkMs + direct monitor.onTick() calls (no mocking); avoids mock complexity while producing a real threshold breach"
  - "BotIdentity (Plan 01) was NOT updated to delegate to AttributionSanitizer — the plan note says delegation is backward-compatible but not required now; Plan 01's tests still pass because their observable invariants (CR rejection, truncation) are unchanged"
  - "CR injection integration test replaced with blank-harness test: raw CR cannot be sent in HTTP headers (protocol strips them before server receipt); CR sanitization verified at unit level in AttributionSanitizerTest"
  - "AdmissionGate.reject refactored to session-bearing overload; old session-less reject removed; back-compat evaluate(req) delegates to evaluate(req, null)"
metrics:
  duration_minutes: 13
  completed_date: "2026-04-28"
  tasks_completed: 2
  tasks_total: 2
  files_created: 7
  files_modified: 2
---

# Phase 18 Plan 02: Harness Identity — Server Handshake Headers + Log Markers Summary

**One-liner:** Server reads X-Paralife-Source/Harness at WS upgrade via AttributionSanitizer shared helper; stashes ATTR_SOURCE/ATTR_HARNESS on session; emits HARNESS connected/disconnected markers; extends ADMISSION/BACKPRESSURE markers with source/harness attribution; D-12 scalar invariant locked by non-vacuous TickHealthMonitorScalarTest.

## Tasks Completed

| Task | Phase | Description | Commit | Type |
|------|-------|-------------|--------|------|
| 1 | RED | AttributionSanitizerTest + HandshakeHeader + HarnessLogMarker failing tests | 5b2587a | test |
| 1 | GREEN | AttributionSanitizer + WorldWebSocketHandler handshake header read + HARNESS markers | b47d1a6 | feat |
| 2 | RED | AdmissionLogMarkerTest + TickHealthMonitorScalarTest failing tests | baff8d0 | test |
| 2 | GREEN | AdmissionGate session-bearing evaluate/reject + WorldWebSocketHandler wiring | 0fc2678 | feat |

## What Was Built

### AttributionSanitizer (new)

`AttributionSanitizer.sanitizeHarnessId(String raw) → Optional<String>` — single source of truth for harness-id normalization (Round 2 Codex HIGH amendment):
- `null` / blank → `Optional.empty()`
- Any ASCII control char (0x00-0x1F, 0x7F) → `Optional.empty()` — header-injection guard
- Trim + truncate to `MAX_HARNESS_ID_LENGTH` (32 chars)
- Used by server-side `WorldWebSocketHandler.afterConnectionEstablished` for defense-in-depth

### WorldWebSocketHandler (modified)

`afterConnectionEstablished` additions:
- Reads `X-Paralife-Source` and `X-Paralife-Harness` from `session.getHandshakeHeaders()` (Spring `HttpHeaders` is case-insensitive)
- Source: bounded-taxonomy filter using `BotIdentity.SOURCE_TAXONOMY`; values outside taxonomy fold to `"unknown"`
- Harness: server-side sanitization via `AttributionSanitizer.sanitizeHarnessId`; only stashed if `source=harness` AND sanitizer accepts the value
- Stashes `AttributionTagger.ATTR_SOURCE` and `AttributionTagger.ATTR_HARNESS` on session attributes

`afterConnectionClosed` additions:
- Captures attribution fields before any attribute removal
- Emits `HARNESS disconnected tick=… session=… harness=… source=… reason=(graceful|stalled-held)` on every exit path
- `stalled-held` close-reason for STALLED sessions (Round 2 Claude LOW)
- BACKPRESSURE held-on-close marker extended with `AttributionTagger.formatLogFields(session)`

`wireCrossBeanCallbacks` and `handleRegister` additions:
- BACKPRESSURE stalled marker extended with `AttributionTagger.formatLogFields(session)`
- BACKPRESSURE resumed marker extended with `AttributionTagger.formatLogFields(session)`

### AdmissionGate (modified)

- `evaluate(AdmissionRequest req, WebSocketSession session)`: session-bearing overload with all 6 guards; passes `session` to new `reject` helper
- `evaluate(AdmissionRequest req)`: back-compat shim → `evaluate(req, null)`
- `reject(req, session, code, token)`: calls `metrics.incRejected(token, session)` and formats ADMISSION log marker with `AttributionTagger.formatLogFields(session)`
- `WorldWebSocketHandler.handleRegister` now calls `admissionGate.evaluate(req, session)`

### TickHealthMonitorScalarTest (new, non-vacuous)

Drive strategy chosen: **real threshold breach via `ReflectionTestUtils.setField`**:
- Sets `TickEngine.lastTickWorkMs = 500L` (above 80% × 100ms = 80ms high-water mark)
- Calls `monitor.onTick(new TickEvent(i))` directly for 10 samples (window=5; 10 ensures full fill + mean computation)
- Gate: `if (tickHealthLines == 0) { fail("TickHealthMonitor did not emit…") }` before scalar-shape assertion
- Locks D-12 invariant: all TICK-HEALTH lines must not contain `source=`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] CR injection cannot be tested at HTTP integration level**
- **Found during:** Task 1 GREEN — `harnessIdWithCrInjection_notStashed` failed: Jetty's HTTP parser converts `\r` in header values to a space (0x20) before the server receives it; space passes sanitizer (not a control char)
- **Root cause:** HTTP protocol constraint — raw CR characters cannot be injected via HTTP upgrade headers from a Jetty client; they are normalized away by the HTTP/1.1 parser
- **Fix:** Replaced the CR injection integration test with `blankHarnessId_notStashed` — uses whitespace-only header value which the sanitizer rejects as blank; documents HTTP transport constraint in test comment. CR rejection remains verified at unit level in `AttributionSanitizerTest.carriageReturn_returnsEmpty()`
- **Files modified:** `WorldWebSocketHandlerHandshakeHeaderTest.java`
- **Impact:** None on production code; sanitizer correctly rejects CR in unit tests; the code path is connected to the sanitizer (verified by truncation integration test)

## TDD Gate Compliance

| Gate | Commit | Status |
|------|--------|--------|
| Task 1 RED | 5b2587a | PASS — AttributionSanitizerTest compile-fails (class not found); HandshakeHeaderTest/HarnessLogMarkerTest would fail at runtime |
| Task 1 GREEN | b47d1a6 | PASS — all Task 1 tests pass; BotIdentityTest still passes |
| Task 2 RED | baff8d0 | PASS — AdmissionLogMarkerTest compile-fails (evaluate(req, session) not found) |
| Task 2 GREEN | 0fc2678 | PASS — all Task 2 tests pass; full suite BUILD SUCCESSFUL |

## Known Stubs

None. All behaviors are implemented and wired end-to-end.

## Threat Surface Scan

No new network endpoints, auth paths, or schema changes introduced. `AttributionSanitizer` and the `AdmissionGate` session-bearing overload are purely internal plumbing. The handshake header read path is the server side of the existing `/ws/world` WebSocket endpoint — no new trust boundary is opened. T-18-01 (header spoofing) is mitigated: source folded to "unknown" if outside taxonomy; harness id sanitized via shared helper with trim/truncate/control-char rejection.

## Self-Check: PASSED

All 7 files found (6 created + 1 SUMMARY). All 4 commits found (5b2587a, b47d1a6, baff8d0, 0fc2678). Full test suite BUILD SUCCESSFUL.
