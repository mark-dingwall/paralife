---
phase: 20
slug: connection-multiplexing-runtime-tuning
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-09
---

# Phase 20 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `20-RESEARCH.md` §Validation Architecture (D-11 three-gate stack, D-12 disabled-test exclusions, TD-19.5-A flake caveat).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (`junit-jupiter`, bundled via `org.springframework.boot:spring-boot-starter-test` on Spring Boot 3.4.4) |
| **Config file** | `src/test/resources/junit-platform.properties` (5-min global timeout, SEPARATE_THREAD mode — P22) |
| **Quick run command** | `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` (in-suite three-gate stack — masks TD-19.5-A flake per D-11) |
| **Full suite command** | `./gradlew test` (default — `forkEvery=1` per `build.gradle.kts:75`; excludes 4 P22 `@Disabled` tests per D-12) |
| **Estimated runtime** | ~30–60 s (quick); ~5–15 min (full, due to `forkEvery=1`) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest`
- **After every plan wave:** Run `./gradlew test` (full suite)
- **Before `/gsd-verify-work`:** Full suite green + `20-RUNTIME.md` carries JFR-anchored before/after numbers (D-13 headline gauges: `paralife.tick.health.work-time-ms`, `paralife.outbound.detach.timeout`)
- **Max feedback latency:** 60 s (quick gate); 15 min (full suite)

> **TD-19.5-A caveat:** `GoldenTraceEquivalenceTest` is flaky (~40% emit ±1) in **isolated** runs only. The "quick" command above runs all three gates in one JVM, masking the flake — this is the trustworthy mode. Never gate CI on isolated single-test runs.

---

## Per-Task Verification Map

> Plans not yet written (Wave 0). Map will be filled during planner output. Stub rows below mirror the 6-plan slicing recommendation in `20-RESEARCH.md`.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 20-01-* | 01 (toolchain + baseline JFRs) | 1 | SCALE-09 | — | N/A (manual capture) | manual + regression | three-gate stack | ❌ W0 (artifacts created Plan 1) | ⬜ pending |
| 20-02-* | 02 (`paralife.runtime.jetty.*`) | 2 | SCALE-09 | T-20-V5 (codec validation bounds untouched) | Jetty defaults preserved unless explicitly overridden | unit + regression | `./gradlew test --tests JettyRuntimeConfigTest --tests JettyDeflateCustomizerTest` + three-gate stack | ❌ W0 | ⬜ pending |
| 20-03-* | 03 (`paralife.runtime.app.*`) | 2 | SCALE-09 | — | `outbound-queue-size` alongside, not moved (D-20) | unit + regression | `./gradlew test --tests AppRuntimeConfigTest --tests *Admission* --tests *Backpressure*` + three-gate stack | ❌ W0 | ⬜ pending |
| 20-04-* | 04 (JVM presets + per-tier recipes) | 3 | SCALE-09 | — | doc-only; no code change | manual | `test -f .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` (per-tier section populated) | ❌ W0 (Plan 6 finalises) | ⬜ pending |
| 20-05-* | 05 (codec hot-path opts, JFR-driven) | 3 | SCALE-08, SCALE-09 | T-20-V5 (`MAX_S_ENTRIES`, `MAX_V_ENTRIES`, varbase64 bounds preserved) | wire-equivalence preserved (D-11) | regression | three-gate stack + full suite | ❌ W0 | ⬜ pending |
| 20-06-* | 06 (20-RUNTIME.md + CLAUDE.md + README + inline) | 4 | SCALE-08, SCALE-09 | — | doc + comment locks D-01/D-02 rationale | manual | `grep -q "Runtime tuning" CLAUDE.md`; `grep -q "WS:entity 1:1" README.md`; `test -f 20-RUNTIME.md` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

No new test files. Existing infrastructure covers all phase requirements:
- D-11 three-gate stack (`GoldenTraceEquivalenceTest`, `GoldenTraceWithActionsTest`, `LiveEntityRegistryInvariantTest`) is the codec/encode/lifecycle wire-equivalence safety net.
- Existing `JettyDeflateCustomizerTest`, `*Admission*`, `*Backpressure*` tests catch wiring regressions for new `@ConfigurationProperties` records.
- D-12 explicitly forbids re-enabling the four P22 `@Disabled` tests (TD-22-A `MetabolismIntegrationTest`, TD-22-B `EncodeDeflatePerformanceGateTest`, TD-22-C `PopulationDynamicsTest`, TD-22-D `HundredBotIntegrationTest`) — that's P21 / P22.1 territory.

What plans MUST create (non-test artifacts):
- [ ] `tools/async-profiler-bootstrap.md` (or committed `tools/async-profiler/`) — Plan 1
- [ ] `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md` — Plan 1
- [ ] `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-{100,500,1000}bots-baseline-c22e487.jfr` — Plan 1 (D-19 SHA-anchored)
- [ ] `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` — Plan 2
- [ ] `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` — Plan 3
- [ ] `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` — Plan 6
- [ ] `CLAUDE.md` §Runtime tuning subsection — Plan 6
- [ ] `README.md` operator paragraph — Plan 6
- [ ] Inline rationale comments at `WorldWebSocketHandler` WS-upgrade and `OutboundSender.attachSession` — Plan 6

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Profile artifacts captured against c22e487 | SCALE-09 (D-19) | JFR/async-profiler runs invoke `loadHarnessJar` against a running server; not a Gradle test | `git checkout c22e487`; start server; `loadHarnessJar --bots 1000 --duration 60s`; `jcmd <pid> JFR.start name=p20 settings=profile filename=profiles/jfr-1000bots-baseline-c22e487.jfr duration=60s`; commit artifact |
| Tuned JFR captured against HEAD with deltas vs baseline | SCALE-09 (D-13) | same — measurement run | repeat above with `loadHarnessJar` against HEAD, filename `jfr-1000bots-tuned-<sha>.jfr`; compute deltas on `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout`; record in `20-RUNTIME.md` |
| Per-tier recipes copy-pasteable for Phase 21 benchmark consumption | SCALE-09 (D-14) | doc review | reviewer copies a 100/500/1000-bot recipe block out of `20-RUNTIME.md`, runs it as-is, confirms server boots and `loadHarnessJar` connects |
| WS:entity 1:1 deliberate-choice rationale codified in 3 places | SCALE-08 (D-02) | grep + visual review | `grep -q "WS:entity 1:1" README.md`; `grep -q "WS:entity 1:1" CLAUDE.md`; `grep -rE "WS:entity 1:1\|see 20-RUNTIME" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java src/main/java/com/paralife/admission/OutboundSender.java` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify (three-gate stack) OR documented manual capture (D-13/D-14)
- [ ] Sampling continuity: every plan wave runs full suite; no >2 consecutive tasks without quick-stack verify
- [ ] Wave 0 covers all MISSING references (none for tests; non-test artifacts listed above)
- [ ] No watch-mode flags (Gradle invocations are one-shot)
- [ ] Feedback latency < 60 s (quick) and < 15 min (full)
- [ ] `nyquist_compliant: true` set in frontmatter when planner sign-off lands
- [ ] D-12 enforced: 4 `@Disabled` tests untouched
- [ ] D-19 enforced: every committed baseline JFR cites `c22e487` in filename

**Approval:** pending
