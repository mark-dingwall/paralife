---
phase: 18
slug: external-load-harness-harness-identity
status: planning-complete
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-28
last_updated: 2026-04-28
---

# Phase 18 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ + Spring Boot Test + Awaitility |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests 'com.paralife.admission.*' --tests 'com.paralife.harness.*' --tests 'com.paralife.bot.Bot*'` |
| **Full suite command** | `./gradlew test` |
| **Slow-tests command** | `./gradlew test -PincludeLong=true` |
| **Estimated runtime** | ~120 seconds default; ~6 minutes with -PincludeLong=true (LoadTest, EmergenceStabilityLoadTest, LoadHarnessIntegrationTest) |

---

## Sampling Rate

- **After every task commit:** Run quick command for the touched package(s)
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green; -PincludeLong=true sweep must pass
- **Max feedback latency:** 120 seconds for default suite

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 18-01-T1 | 01 | 1 | SCALE-04 | T-18-01 | BotIdentity bounded SOURCE_TAXONOMY + 32-char harness id truncation + CR/LF rejection | unit | `./gradlew test --tests "com.paralife.bot.BotIdentityTest"` | ❌ Wave 0 | ⬜ pending |
| 18-01-T2 | 01 | 1 | SCALE-04 | T-18-04 | BotClient.connect() emits X-Paralife-Source on every invocation; reconnect re-emits headers (Pitfall 1) | integration | `./gradlew test --tests "com.paralife.bot.BotClientHandshakeHeaderTest"` | ❌ Wave 0 | ⬜ pending |
| 18-02-T1 | 02 | 2 | SCALE-04 | T-18-01, T-18-04 | Server reads handshake headers, taxonomy-filters source, truncates harness; HARNESS connected/disconnected log markers | integration | `./gradlew test --tests "com.paralife.websocket.WorldWebSocketHandlerHandshakeHeaderTest" --tests "com.paralife.websocket.HarnessLogMarkerTest"` | ❌ Wave 0 | ⬜ pending |
| 18-02-T2 | 02 | 2 | SCALE-04 | (D-12 invariant) | ADMISSION rejected log marker carries source/harness; TICK-HEALTH stays scalar | unit | `./gradlew test --tests "com.paralife.admission.AdmissionLogMarkerTest"` | ❌ Wave 0 | ⬜ pending |
| 18-03-T1 | 03 | 1 | SCALE-04 | (D-11 / D-13 contract) | AttributionTagger helper + AdmissionConfig.AttributionConfig record | unit | `./gradlew test --tests "com.paralife.admission.AttributionTaggerTest"` | ❌ Wave 0 | ⬜ pending |
| 18-03-T2 | 03 | 1 | SCALE-04 | T-18-01, T-18-02 | Two-tag emission + MeterFilter cardinality cap + warn-once + scalar invariants | integration | `./gradlew test --tests "com.paralife.admission.AttributionTagTest" --tests "com.paralife.admission.CardinalityCapTest"` | ❌ Wave 0 | ⬜ pending |
| 18-04-T1 | 04 | 2 | SCALE-03 | T-18-04 | BotFactory seam (D-19 reserved params); BotFleet async per-bot tracking; identity propagation; no 30s ceiling | integration | `./gradlew test --tests "com.paralife.bot.BotFactoryTest" --tests "com.paralife.bot.BotFleetTest"` | ❌ Wave 0 | ⬜ pending |
| 18-04-T2 | 04 | 2 | SCALE-05 | (D-09 contract) | BotRunner uses BotFleet + BotIdentity.operator(); 100-cap + exit-code parity preserved | integration | `./gradlew test --tests "com.paralife.bot.BotRunnerOperatorTagTest" --tests "com.paralife.bot.BotRunnerRegressionTest"` | ❌ Wave 0 | ⬜ pending |
| 18-05-T1 | 05 | 3 | SCALE-03 | T-18-01 | Picocli CLI + JSON/JSONL atomic-rename ReportWriter + Gradle tasks | unit | `./gradlew test --tests "com.paralife.harness.LoadHarnessOptionsTest" --tests "com.paralife.harness.ReportWriterTest"` | ❌ Wave 0 | ⬜ pending |
| 18-05-T2 | 05 | 3 | SCALE-03 | T-18-03 | LoadHarness boots, runs fleet with BotIdentity.harness(...), writes report, exits cleanly | integration | `./gradlew test --tests "com.paralife.harness.LoadHarnessIntegrationTest"` | ❌ Wave 0 | ⬜ pending |
| 18-06-T1 | 06 | 4 | SCALE-04, SCALE-03 | T-18-04 | STALLED-pivot preserves attribution; LoadTest opts into harness-tagged path | integration | `./gradlew test --tests "com.paralife.admission.AttributionRebindTest"` && `./gradlew test --tests "com.paralife.engine.LoadTest" -PincludeLong=true` | ❌ Wave 0 | ⬜ pending |
| 18-06-T2 | 06 | 4 | SCALE-03, SCALE-04, SCALE-05 | T-18-01, T-18-04 | 18-HARNESS.md spec + CLAUDE.md Connection model documented (mitigations + Forward Notes for 999.2) | grep | `test -f .planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md && grep -q 'Connection model' CLAUDE.md` | ❌ Wave 0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

All test files in this list will be created by their respective plans during execution; the executor must scaffold them BEFORE production code (TDD-tagged tasks):

- [ ] `src/test/java/com/paralife/bot/BotIdentityTest.java` (Plan 01)
- [ ] `src/test/java/com/paralife/bot/BotClientHandshakeHeaderTest.java` (Plan 01)
- [ ] `src/test/java/com/paralife/websocket/WorldWebSocketHandlerHandshakeHeaderTest.java` (Plan 02)
- [ ] `src/test/java/com/paralife/websocket/HarnessLogMarkerTest.java` (Plan 02)
- [ ] `src/test/java/com/paralife/admission/AdmissionLogMarkerTest.java` (Plan 02)
- [ ] `src/test/java/com/paralife/admission/AttributionTaggerTest.java` (Plan 03)
- [ ] `src/test/java/com/paralife/admission/AttributionTagTest.java` (Plan 03)
- [ ] `src/test/java/com/paralife/admission/CardinalityCapTest.java` (Plan 03)
- [ ] `src/test/java/com/paralife/bot/BotFactoryTest.java` (Plan 04)
- [ ] `src/test/java/com/paralife/bot/BotFleetTest.java` (Plan 04)
- [ ] `src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java` (Plan 04)
- [ ] `src/test/java/com/paralife/bot/BotRunnerRegressionTest.java` (Plan 04)
- [ ] `src/test/java/com/paralife/harness/LoadHarnessOptionsTest.java` (Plan 05)
- [ ] `src/test/java/com/paralife/harness/ReportWriterTest.java` (Plan 05)
- [ ] `src/test/java/com/paralife/harness/LoadHarnessIntegrationTest.java` (Plan 05)
- [ ] `src/test/java/com/paralife/admission/AttributionRebindTest.java` (Plan 06)
- [ ] Picocli dependency added to `build.gradle.kts` (Plan 05)
- [ ] Awaitility transitive (already on classpath via spring-boot-starter-test — verify)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `java -jar build/libs/paralife-*-load-harness.jar --server-uri ws://localhost:8080/ws/world --count 1000 --harness-id manual-1k` sustains 1000 concurrent connections; server logs `HARNESS connected … harness=manual-1k` for each | SCALE-03 | Load level beyond CI runner budget; needs manual verify against running server | (1) `./gradlew bootRun` in one terminal; (2) `./gradlew loadHarnessJar`; (3) run command above; (4) `grep 'HARNESS connected' server.log \| wc -l` ≥ 1000; (5) `grep 'harness=manual-1k' server.log \| head` |
| Multi-instance attribution: two harnesses (`harness-A` n=500, `harness-B` n=500) appear distinctly in metrics with no overflow | SCALE-04 | Multi-process — automation impractical inside JUnit | Launch two harness JVMs side-by-side; verify `paralife.admission.active.entities{harness="harness-A"}` and `{harness="harness-B"}` both present; verify `harness=overflow` count = 0 |
| Cardinality cap overflow: 65 distinct harness ids fold into `harness=overflow` with one warning log line | SCALE-04 | Repeated rapid restart timing | Bash loop `for i in $(seq 1 65); do harness JVM with --harness-id=hn-$i & sleep 0.5; done`; assert exactly one `HARNESS overflow first-seen` log line; assert `harness=overflow` counter increments from 65th onward |
| `java -jar paralife-*-load-harness.jar --help` prints all flag documentation in < 1s startup | SCALE-03 | Pitfall 5 verification — confirm BootJar startup cost is acceptable | Manual; alternative is to swap to plain Jar with explicit Main-Class |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 120s for default suite
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved (planning-complete; ready for execution)
