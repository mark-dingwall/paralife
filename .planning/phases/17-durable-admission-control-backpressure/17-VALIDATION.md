---
phase: 17
slug: durable-admission-control-backpressure
status: draft
nyquist_compliant: true
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

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 17-01-01 | 17-01 | 1 | SCALE-01, SCALE-02 | T-17-misc | AdmissionConfig validates input ranges; RejectionToken constants present | unit | `./gradlew test --tests "com.paralife.admission.AdmissionConfigTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-01-02 | 17-01 | 1 | SCALE-01, SCALE-02 | — | Spec doc covers all 9 D-07 tokens, STALLED FSM, resume-token wire shape | doc | `test -f .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md && grep -q "RejectionToken" .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md && grep -q "STALLED" .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md && grep -q "resumeToken" .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md && grep -qE "ADMISSION\|BACKPRESSURE\|TICK-HEALTH" .planning/phases/17-durable-admission-control-backpressure/17-ADMISSION.md` | ✅ exists at task end | ⬜ pending |
| 17-02-01 | 17-02 | 1 | SCALE-01, SCALE-02 | T-17-01 | Codec round-trips r\|C\|<token> and S\|<id>\|<token>\|f<effects> backward-compatibly | unit | `./gradlew test --tests "com.paralife.codec.RegisterFrameResumeTokenTest" --tests "com.paralife.codec.SyncFrameResumeTokenTest" --tests "com.paralife.codec.PerceptionCodecTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-03-01 | 17-03 | 2 | SCALE-01, SCALE-02 | T-17-06, T-17-misc | AdmissionMetrics counter+gauges per D-17/D-18; AdmissionResult sealed type | unit | `./gradlew test --tests "com.paralife.admission.AdmissionMetricsTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-03-02 | 17-03 | 2 | SCALE-01, SCALE-02 | T-17-06 | AdmissionGate.evaluate denies on maintenance/world-full/respawn-cap/tick-overload, emits ADMISSION log marker | unit | `./gradlew test --tests "com.paralife.admission.AdmissionGateTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-04-01 | 17-04 | 2 | SCALE-01, SCALE-02 | T-17-07 | TickHealthMonitor hysteresis high/low watermark crossing; TICK-HEALTH log markers | unit | `./gradlew test --tests "com.paralife.admission.TickHealthMonitorTest" --tests "com.paralife.engine.TickEngineTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-05-01 | 17-05 | 2 | SCALE-01, SCALE-02 | T-17-01 | ResumeTokenRegistry mints opaque tokens, single-use tryRebind, tick-driven sweep, cleanup callback | unit | `./gradlew test --tests "com.paralife.admission.ResumeTokenRegistryTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-06-01 | 17-06 | 2 | SCALE-02 | T-17-03 | OutboundSender VT-per-session attach/detach, non-blocking offer, overflow callback | unit | `./gradlew test --tests "com.paralife.admission.OutboundSenderTest" -PincludeLong=false` | ⬜ W0 | ⬜ pending |
| 17-07-01 | 17-07 | 3 | SCALE-01, SCALE-02 | T-17-01, T-17-03, T-17-04, T-17-06, T-17-misc | WorldWebSocketHandler delegates to AdmissionGate; STALLED FSM via ATTR_STALL_TICK; all 8 free-text rejections retoken to D-07 | integration | `./gradlew compileJava compileTestJava -PincludeLong=false 2>&1 \| tee /tmp/p17-07-build.log; grep -q "BUILD SUCCESSFUL" /tmp/p17-07-build.log` | ⬜ W0 | ⬜ pending |
| 17-08-01 | 17-08 | 3 | SCALE-02 | T-17-03 | TickBroadcaster enqueues via outboundSender.offer (no synchronized); skips STALLED sessions | integration | `./gradlew compileJava compileTestJava 2>&1 \| tee /tmp/p17-08-build.log; grep -q "BUILD SUCCESSFUL" /tmp/p17-08-build.log` | ⬜ W0 | ⬜ pending |
| 17-09-01 | 17-09 | 3 | SCALE-02 | T-17-01 | BotClient stores resume token, reconnects with token on E\|408, falls through to fresh r\| if no token | integration | `./gradlew compileJava compileTestJava 2>&1 \| tee /tmp/p17-09-build.log; grep -q "BUILD SUCCESSFUL" /tmp/p17-09-build.log` | ⬜ W0 | ⬜ pending |
| 17-10-01 | 17-10 | 4 | SCALE-01, SCALE-02 | T-17-misc | application.yml migrated to paralife.admission.*; PopulationCapConfig deleted; LoadTest migrated | build gate | `./gradlew compileJava compileTestJava 2>&1 \| tee /tmp/p17-10a-build.log; grep -q "BUILD SUCCESSFUL" /tmp/p17-10a-build.log && ! test -f src/main/java/com/paralife/websocket/PopulationCapConfig.java && ! test -f src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java && grep -q "paralife.admission" src/main/resources/application.yml && ! grep -q "max-active-entities" src/main/resources/application.yml` | ⬜ W0 | ⬜ pending |
| 17-10-02 | 17-10 | 4 | SCALE-01, SCALE-02 | T-17-04 | ActionResolver wires D-09 incIngressOverwrite counter; CLAUDE.md gains Outbound concurrency section per D-10 | build gate | `./gradlew compileJava compileTestJava 2>&1 \| tee /tmp/p17-10b-build.log; grep -q "BUILD SUCCESSFUL" /tmp/p17-10b-build.log && grep -q "Outbound concurrency" CLAUDE.md && grep -q "incIngressOverwrite" src/main/java/com/paralife/engine/ActionResolver.java` | ⬜ W0 | ⬜ pending |
| 17-11-01 | 17-11 | 4 | SCALE-01, SCALE-02 | T-17-01, T-17-03 | End-to-end STALLED-pivot: queue overflow → markStalled → E\|408 → reconnect with token → entity rebind | integration | `./gradlew test --tests "com.paralife.websocket.StallRecoveryIntegrationTest" -PincludeLong=true` | ⬜ W0 | ⬜ pending |
| 17-11-02 | 17-11 | 4 | SCALE-01, SCALE-02 | T-17-07 | TickHealthGate hysteresis verified at integration level: AdmissionGate observes TickHealthMonitor across high/low/in-band transitions | integration | `./gradlew test --tests "com.paralife.admission.TickHealthGateIntegrationTest" -PincludeLong=true` | ⬜ W0 | ⬜ pending |
| 17-11-03 | 17-11 | 4 | SCALE-01, SCALE-02 | — | All D-19 log markers (ADMISSION/BACKPRESSURE/TICK-HEALTH) match documented format | integration | `./gradlew test --tests "com.paralife.admission.AdmissionLogMarkersIntegrationTest" -PincludeLong=true` | ⬜ W0 | ⬜ pending |
| 17-11-04 | 17-11 | 4 | SCALE-01, SCALE-02 | — | Full suite green incl. LoadTest; com.paralife.admission package coverage > 70% line | regression | `./gradlew test -PincludeLong=true 2>&1 \| tee /tmp/p17-11-full.log; grep -q "BUILD SUCCESSFUL" /tmp/p17-11-full.log` | ⬜ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/admission/AdmissionGateTest.java` — admission cap, maintenance, tick-overload, respawn-cap rejection tokens (renamed/rewritten from `WorldWebSocketHandlerPopulationCapTest`)
- [ ] `src/test/java/com/paralife/admission/TickHealthMonitorTest.java` — rolling-window mean, hysteresis high/low watermark crossing
- [ ] `src/test/java/com/paralife/admission/ResumeTokenRegistryTest.java` — token mint, lookup, expiry sweep, rebind
- [ ] `src/test/java/com/paralife/admission/OutboundSenderTest.java` — VT-per-session enqueue, drain, queue-overflow → STALLED transition
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
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
