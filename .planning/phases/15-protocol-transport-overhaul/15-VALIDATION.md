---
phase: 15
slug: protocol-transport-overhaul
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-20
---

# Phase 15 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (unit + integration) |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests 'com.paralife.codec.*' --tests 'com.paralife.world.RockGenerator*'` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90 seconds full suite |

---

## Sampling Rate

- **After every task commit:** Run the quick command scoped to the package touched by the task
- **After every plan wave:** Run full suite
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds

---

## Per-Task Verification Map

Populated by planner. Must cover these phase verification dimensions (see §Validation Architecture in 15-RESEARCH.md):

1. **Codec round-trip** — 13 locked vectors from 15-SCHEMA.md §10 (`PerceptionCodecRoundTripTest`, `@ParameterizedTest`).
2. **Handshake extension negotiation** (D-32) — raw HTTP upgrade asserts `Sec-WebSocket-Extensions` contains `permessage-deflate; server_no_context_takeover`.
3. **Fail-fast enforcement** (D-33) — server refuses upgrade when client omits extension; client closes session when server doesn't echo it.
4. **Existing test migration** (ROADMAP line 139) — all 166 existing tests still pass under new wire protocol.
5. **Stateless bot reachability** — fixed-seed replay produces identical action decisions from identical decoded frame sequence (pure-function `HeuristicBrain`).
6. **Rock generation determinism** (D-35) — same `paralife.world.rock-seed` → byte-identical rock grid.
7. **Actuator metrics** (D-38) — `paralife.ws.active-sessions` gauge, `paralife.ws.tick-frame-bytes` distribution summary, `paralife.ws.bytes-saved` counter reachable and monotonically valid after N ticks.
8. **Zero-trust projection** — server only emits data derivable from bot's sensor radius; assertion test covers mixed-radius scenarios.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| *(populated during planning)* | | | | | | | | | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/codec/PerceptionCodecRoundTripTest.java` — 13-vector round-trip from 15-SCHEMA.md §10 (seeded from the locked table before codec impl lands, test red initially then green once codec complete)
- [ ] `src/test/java/com/paralife/websocket/PermessageDeflateHandshakeIntegrationTest.java` — asserts `Sec-WebSocket-Extensions` response header contents
- [ ] Existing JUnit + Spring Boot Test + Jetty 12 dependency — no new framework install required

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| *(none anticipated; all wire behaviour is deterministic and testable)* | | | |

*If none: "All phase behaviors have automated verification."*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
