---
phase: 18
slug: external-load-harness-harness-identity
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-28
---

# Phase 18 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ + Spring Boot Test |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests 'com.paralife.admission.*' --tests 'com.paralife.harness.*'` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick command for the touched package(s)
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

*Populated during planning — every task in Phase 18 PLAN.md must map to a row here.*

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| {tbd}   | {tbd}| {tbd}| SCALE-03/04/05 | {tbd}    | {tbd}           | {tbd}     | {tbd}             | {tbd}       | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/admission/AttributionTagTest.java` — stub for SCALE-04 (two-tag scheme, bounded cardinality)
- [ ] `src/test/java/com/paralife/admission/AttributionRebindTest.java` — stub for SCALE-04 (STALLED-pivot preserves source/harness)
- [ ] `src/test/java/com/paralife/harness/LoadHarnessTest.java` — stub for SCALE-03 (harness boots, ramp modes, JSON report shape)
- [ ] `src/test/java/com/paralife/bot/BotFleetTest.java` — stub for SCALE-03 / SCALE-05 (refactor regression — BotRunner ≤100 path unchanged)
- [ ] `src/test/java/com/paralife/bot/BotClientHandshakeHeaderTest.java` — stub for SCALE-04 (X-Paralife-Harness / X-Paralife-Source emitted on upgrade)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `java -jar build/libs/load-harness.jar --server-uri ws://localhost:8080/ws/world --count 1000 --harness-id manual-1k` sustains 1000 concurrent connections, server logs `HARNESS connected … harness=manual-1k` for each | SCALE-03 | Load level beyond CI runner budget; needs manual verify against running server | (1) `./gradlew bootRun` in one terminal; (2) `./gradlew loadHarnessJar`; (3) run command above; (4) `grep 'HARNESS connected' server.log \| wc -l` ≥ 1000; (5) `grep 'harness=manual-1k' server.log \| head` |
| Multi-instance attribution: two harnesses (`harness-A` n=500, `harness-B` n=500) appear distinctly in metrics with no overflow | SCALE-04 | Multi-process — automation impractical inside JUnit | Launch two harness JVMs side-by-side; verify `paralife.admission.active.entities{harness="harness-A"}` and `{harness="harness-B"}` both present in `/actuator/metrics`; verify `harness=overflow` count = 0 |
| Cardinality cap overflow: 65 distinct harness ids fold into `harness=overflow` with one warning log line | SCALE-04 | Repeated rapid restart timing; easier to drive by hand | Bash loop `for i in $(seq 1 65); do harness JVM with --harness-id=hn-$i & sleep 0.5; done`; assert exactly one `HARNESS overflow first-seen` log line; assert `harness=overflow` counter increments from 65th onward |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
