---
phase: 11
slug: bonding-rules-engine
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-13
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

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 11-01-01 | 01 | 1 | SC-1 (bonding config) | — | N/A | unit | `./gradlew test` | ❌ W0 | ⬜ pending |
| 11-01-02 | 01 | 1 | SC-2 (bonding conditions) | — | N/A | unit | `./gradlew test` | ❌ W0 | ⬜ pending |
| 11-01-03 | 01 | 1 | SC-3 (bonding events observable) | — | N/A | unit | `./gradlew test` | ❌ W0 | ⬜ pending |
| 11-01-04 | 01 | 1 | SC-4 (unit tests) | — | N/A | unit | `./gradlew test` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/engine/BondingRulesTest.java` — stubs for bonding eligibility and condition tests
- [ ] `src/test/java/com/paralife/world/BondedPairTest.java` — stubs for BondedPair entity tests

*Existing test infrastructure covers framework and fixtures.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Bonding events visible in tick output | SC-3 | Requires running server and observing WebSocket output | Start server, connect client, observe tick data includes bonding events |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
