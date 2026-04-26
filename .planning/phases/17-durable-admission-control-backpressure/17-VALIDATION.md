---
phase: 17
slug: durable-admission-control-backpressure
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-27
---

# Phase 17 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Spring Boot Test) |
| **Config file** | `build.gradle.kts` (existing) |
| **Quick run command** | `./gradlew test --tests '*Admission*' --tests '*Backpressure*' --tests '*TickHealth*' --tests '*ResumeToken*'` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | quick ~30s, full ~3 min |

---

## Sampling Rate

- **After every task commit:** Run quick command (scoped to admission/backpressure/tick-health/resume-token tests)
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds for quick runs

---

## Per-Task Verification Map

> Filled in by gsd-planner per task. Initial scaffold below — planner will replace with concrete task IDs.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | SCALE-01 / SCALE-02 | TBD | TBD | unit / integration | TBD | ⬜ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/admission/AdmissionGateTest.java` — admission cap, maintenance, tick-overload, respawn-cap rejection tokens (renamed/rewritten from `WorldWebSocketHandlerPopulationCapTest`)
- [ ] `src/test/java/com/paralife/admission/TickHealthMonitorTest.java` — rolling-window mean, hysteresis high/low watermark crossing
- [ ] `src/test/java/com/paralife/admission/ResumeTokenRegistryTest.java` — token mint, lookup, expiry sweep, rebind
- [ ] `src/test/java/com/paralife/websocket/OutboundSenderTest.java` — VT-per-session enqueue, drain, queue-overflow → STALLED transition
- [ ] `src/test/java/com/paralife/websocket/StallRecoveryIntegrationTest.java` — full STALLED-pivot flow: queue overflow → close → reconnect with token → entity rebind within grace window
- [ ] `src/test/java/com/paralife/codec/RegisterFrameResumeTokenTest.java` — codec parse/encode `r|<type>|<resumeToken>` and `S|<entityId>|<resumeToken>`
- [ ] Existing `LoadTest` migrated from `paralife.websocket.max-active-entities=1000000` to `paralife.admission.cap=1000000`

*JUnit 5 + Spring Boot Test infra already present — no framework install required.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Operator-visible log markers (`ADMISSION rejected …`, `BACKPRESSURE stalled …`, `TICK-HEALTH degraded …`) | SCALE-01 / SCALE-02 | Log inspection is a UX/operability check, not a correctness check | After running an integration scenario, `grep -E 'ADMISSION\|BACKPRESSURE\|TICK-HEALTH' build/reports/tests/test/.../output.txt` and confirm format matches D-19 examples |
| Maintenance-mode flag denies all `r|` until restart | SCALE-01 (D-16) | Restart-required toggle is config-only this phase | `bootRun` with `paralife.admission.maintenance=true`, `wscat` register attempt, observe `E\|429\|maintenance` |
| `paralife.tick.health.work-time-ms` gauge tracks last-tick wall-clock | SCALE-02 (D-14, D-18) | `/actuator/metrics` scrape | `bootRun`, `curl /actuator/metrics/paralife.tick.health.work-time-ms` shows value |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
