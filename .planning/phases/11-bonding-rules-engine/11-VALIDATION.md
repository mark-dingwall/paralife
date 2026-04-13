---
phase: 11
slug: bonding-rules-engine
status: complete
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-13
audited: 2026-04-13
---

# Phase 11 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 |
| **Config file** | `build.gradle.kts` (JaCoCo + JUnit config) |
| **Quick run command** | `./gradlew test` |
| **Full suite command** | `./gradlew test jacocoTestReport` |
| **Estimated runtime** | ~15 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test`
- **After every plan wave:** Run `./gradlew test jacocoTestReport`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|------|--------|
| 11-01-01 | 01 | 1 | R01 (BondingConfig) | — | N/A | unit | `./gradlew test` | `BondingConfigTest.java` (12 tests) | ✅ green |
| 11-01-02 | 01 | 1 | R01 (BondedPair entity) | — | N/A | unit | `./gradlew test` | `EntityTest.java` (+6 BondedPair tests) | ✅ green |
| 11-02-01 | 01 | 1 | R02 (bonding interaction) | — | N/A | unit | `./gradlew test` | `SimulationEngineTest.BondingTests` (10 tests) | ✅ green |
| 11-02-02 | 02 | 2 | R03 (BondedPair perception) | — | N/A | unit | `./gradlew test` | `PerceptionBroadcasterTest.java` (+2 tests) | ✅ green |
| 11-02-03 | 02 | 2 | R03 (bondCount in tick) | — | N/A | unit | `./gradlew test` | `TickBroadcasterTest.java` (6 tests) | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Wave 0 stubs were not created as standalone files — tests landed directly in existing suites:

- [x] `BondingConfigTest.java` — 12 tests covering config validation and edge values (**created new**)
- [x] `SimulationEngineTest.BondingTests` — 10 tests covering bonding eligibility and interaction logic (added to existing file)
- [x] `EntityTest.java` — 6 BondedPair tests covering construction, energy, and pattern matching (added to existing file)
- [x] `PerceptionBroadcasterTest.java` — 2 tests for `cellToView` BondedPair arm (added to existing file)
- [x] `TickBroadcasterTest.java` — 6 tests for bondCount field and broadcast behavior (**created new**)

*Note: `BondingRulesTest.java` and `BondedPairTest.java` stubs listed in original plan were not created. Equivalent coverage is in `SimulationEngineTest.BondingTests` and `EntityTest` respectively. Coverage is equivalent.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| ~~Bonding events visible in tick output~~ | ~~R03~~ | ~~Requires running server~~ | *Automated via `TickBroadcasterTest.tickMessageIncludesBondCount` and `tickMessageContainsExpectedFields`* |

*No remaining manual-only verifications — all behaviors have automated coverage.*

---

## Validation Sign-Off

- [x] All tasks have automated verify
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 coverage confirmed in existing test suites
- [x] No watch-mode flags
- [x] Feedback latency < 15s (full suite ~15s, confirmed UP-TO-DATE in 2s)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** Nyquist-compliant — all 36 tests (28 Plan 01 + 8 Plan 02) pass in 202-test suite.

---

## Validation Audit 2026-04-13

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |
| Pre-existing tests updated | 5 tasks → ✅ green |
| Plan 02 tasks added to map | 2 |
| Manual-only entries cleared | 1 (now automated) |
