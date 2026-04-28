---
phase: 18-external-load-harness-harness-identity
plan: "01"
subsystem: bot-identity
tags: [websocket, handshake-headers, jetty12, identity, tdd]
dependency_graph:
  requires: []
  provides:
    - BotIdentity record with bounded SOURCE_TAXONOMY and compact-ctor invariants
    - BotClientOptions record (back-compat wrapper for BotClient constructors)
    - BotClient.connect() emitting X-Paralife-Source / X-Paralife-Harness headers
  affects:
    - src/main/java/com/paralife/bot/BotClient.java (header injection, new ctor)
    - src/main/java/com/paralife/bot/BotIdentity.java (new)
    - src/main/java/com/paralife/bot/BotClientOptions.java (new)
tech_stack:
  added: []
  patterns:
    - Java 21 record with compact constructor for invariant enforcement
    - Jetty 12 ClientUpgradeRequest.setHeader for custom WS upgrade headers
    - Options record pattern to avoid constructor sprawl (Pitfall 2)
key_files:
  created:
    - src/main/java/com/paralife/bot/BotIdentity.java
    - src/main/java/com/paralife/bot/BotClientOptions.java
    - src/test/java/com/paralife/bot/BotIdentityTest.java
    - src/test/java/com/paralife/bot/BotClientHandshakeHeaderTest.java
  modified:
    - src/main/java/com/paralife/bot/BotClient.java
decisions:
  - "BotClientOptions.defaults() provides the back-compat path; existing 3/5/6-arg constructors delegate to it (Pitfall 2 mitigation)"
  - "identity stored as final field in BotClient — re-emitted on every connect() including STALLED-pivot reconnects (Pitfall 1 / T-18-04 mitigation)"
  - "Reconnect test in BotClientHandshakeHeaderTest uses fresh BotClient instances; same-instance reconnect loop covered in Plan 06 AttributionRebindTest (per Round 2 LOW note)"
  - "Control-char rejection covers full ASCII control range 0x00-0x1F and 0x7F, not just CR/LF (Codex Round 1 HIGH absorbed)"
metrics:
  duration_minutes: 8
  completed_date: "2026-04-28"
  tasks_completed: 2
  tasks_total: 2
  files_created: 4
  files_modified: 1
---

# Phase 18 Plan 01: Harness Identity — BotIdentity + BotClient Handshake Headers Summary

**One-liner:** BotIdentity record with bounded SOURCE_TAXONOMY and compact-ctor invariants; BotClient injects X-Paralife-Source/Harness headers via Jetty 12 ClientUpgradeRequest.setHeader on every connect() call.

## Tasks Completed

| Task | Description | Commit | Type |
|------|-------------|--------|------|
| 1 (RED) | BotIdentityTest — failing tests for record, taxonomy, invariants | 959be8d | test |
| 1 (GREEN) | BotIdentity record implementation | 1c639af | feat |
| 2 (RED) | BotClientHandshakeHeaderTest — failing tests for header injection | f87560b | test |
| 2 (GREEN) | BotClientOptions + BotClient header injection | a68d909 | feat |

## What Was Built

### BotIdentity (new)

- Java record: `(String source, Optional<String> harnessId)`
- `SOURCE_TAXONOMY = Set.of("operator", "harness", "unknown", "overflow", "offspring")` — immutable, bounded (D-11 / D-20)
- `MAX_HARNESS_ID_LENGTH = 32`
- Factory methods: `operator()`, `harness(id)`, `unknown()`
- Compact constructor enforces:
  - `source` must be in `SOURCE_TAXONOMY`
  - `source=harness` IFF `harnessId.isPresent()` — symmetric invariant
  - Trim + truncate to 32 chars on every construction path
  - ASCII control char rejection (0x00–0x1F, 0x7F) — header-injection guard (T-18-01)
- Normalization applies via the compact ctor whether using factory or direct construction

### BotClientOptions (new)

- Java record: `(serverUri, species, brain, respawnCooldownMs, respawnJitterMs, rng, identity)`
- `defaults(uri, species, brain)` factory: defaults to `BotIdentity.unknown()`, 100ms cooldown, 50ms jitter, fresh RNG
- Eliminates the need for a 7th positional arg on BotClient constructors (Pitfall 2)

### BotClient (modified)

- New primary constructor `BotClient(BotClientOptions)` — takes identity at construction time
- Back-compat 3/5/6-arg constructors delegate to `BotClientOptions.defaults()` — no existing call sites broken
- `private final BotIdentity identity` field — bound at construction, re-used on every `connect()` call
- In `connect()`, after `req.addExtensions(...)`:
  ```java
  req.setHeader("X-Paralife-Source", identity.source());
  identity.harnessId().ifPresent(id -> req.setHeader("X-Paralife-Harness", id));
  ```
- `identity()` accessor added for Plan 06 AttributionRebindTest and future consumers

## Deviations from Plan

None — plan executed exactly as written. All Round 1 and Round 2 review feedback already incorporated into the plan before execution.

## Verification Results

- `./gradlew test --tests "com.paralife.bot.BotIdentityTest"` — PASSED (all 20 tests)
- `./gradlew test --tests "com.paralife.bot.BotClientHandshakeHeaderTest"` — PASSED (all 6 tests)
- `./gradlew test --tests "com.paralife.bot.*"` — PASSED (no regressions in existing bot tests)
- `./gradlew test` — PASSED (full suite, no regressions)
- `./gradlew compileJava` — PASSED

## Known Stubs

None. The plan's scope is fully implemented and all behaviors are exercised.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. `BotIdentity` and `BotClientOptions` are pure client-side data records. The `X-Paralife-Source` / `X-Paralife-Harness` headers cross the BotClient→server trust boundary as documented in the plan's threat model (T-18-01 accepted; T-18-04 mitigated by final-field pattern + test lock).

## Self-Check: PASSED

All 4 files found. All 4 commits found. Full test suite green.
