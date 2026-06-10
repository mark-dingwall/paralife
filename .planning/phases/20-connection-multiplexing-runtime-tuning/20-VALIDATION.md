---
phase: 20
slug: connection-multiplexing-runtime-tuning
status: complete
nyquist_compliant: true
wave_0_complete: true
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
| **Full suite command** | `./gradlew test` (default — `forkEvery=1` per `build.gradle.kts:75`; live `@Disabled` count = 6 across 5 files per D-12 — see Wave 0 intro) |
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

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 20-01-1.0 | 01 (toolchain — async-profiler install) | 1 | SCALE-09 | — | N/A (human-action: network install) | manual | human-action checkpoint (async-profiler network install) | ✅ | ✅ green |
| 20-01-1.1 | 01 (tools/async-profiler-bootstrap.md + profiles/README.md) | 1 | SCALE-09 | — | Baseline capture docs present | regression | `test -f tools/async-profiler-bootstrap.md && test -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md && grep -c "c22e487" .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md \| awk '$1 >= 1 { exit 0 } { exit 1 }' && wc -l tools/async-profiler-bootstrap.md \| awk '$1 >= 40 { exit 0 } { exit 1 }' && wc -l .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md \| awk '$1 >= 50 { exit 0 } { exit 1 }'` | ✅ | ✅ green |
| 20-01b-1b.0 | 01b (baseline JFR capture at c22e487) | 1 | SCALE-09 | — | Artifacts present; size < 10 MB each | manual | `ls .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-{100,500,1000}bots-baseline-c22e487.jfr` (historical record — superseded series, not re-run; original verify incl. three-gate stack, see 20-01b-PLAN.md) | ✅ (superseded) | superseded by 20-01c |
| 20-01b-1b.1 | 01b (verify baseline + write summary) | 1 | SCALE-09 | — | SUMMARY.md exists | regression | `test -f .planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` (historical record — superseded series, not re-run; original verify was artifact existence + three-gate stack) | ✅ (superseded) | superseded by 20-01c |
| 20-01c-A | 01c §Phase A — source instrumentation (OutboundSender peakQueueDepth + encode.send Timer) | 2 | SCALE-09 | — | Additive metrics only; existing surface unchanged | regression | `./gradlew test` | ✅ | ✅ green (PR #1 `ddf0dfa`) |
| 20-01c-B | 01c §Phase B — re-baseline at HEAD (cap=1500), 3 tiers, JFRs + flamegraphs + metric sidecars at `62c1b44` + active-50xfood `103a615` | 2 | SCALE-09 | — | All 9 JFR artifacts + 9 meta.json + 3 flamegraph sets committed; cap=1500 in all meta.json | manual + regression | `ls profiles/jfr-{100,500,1000}bots-baseline-62c1b44.jfr profiles/jfr-{100,500,1000}bots-active-50xfood-103a615.jfr` + three-gate stack | ✅ | ✅ green (PR #1 `ddf0dfa`) |
| 20-01c-C | 01c §Phase C — docs (20-01c-SUMMARY.md, CONTEXT.md updates, 20-RUNTIME.md stub) | 2 | SCALE-09 | — | SUMMARY.md exists; provenance documented | manual | `test -f .planning/phases/20-connection-multiplexing-runtime-tuning/20-01c-SUMMARY.md` | ✅ | ✅ green (PR #1 `ddf0dfa`) |
| 20-02-2.1 | 02 — JettyRuntimeConfig record + JettyRuntimeConfigTest | 2 | SCALE-09 | T-20-V5 | Jetty defaults preserved; no codec change | unit | `./gradlew test --tests JettyRuntimeConfigTest` | ✅ | ✅ green |
| 20-02-2.2 | 02 — Wire JettyRuntimeConfig through JettyDeflateCustomizer | 2 | SCALE-09 | T-20-V5 | 8 knobs bound; idle-timeout-ms 60000; three-gate green | regression | `./gradlew test --tests JettyRuntimeConfigTest --tests "*JettyDeflate*" --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` | ✅ | ✅ green |
| 20-02-2.3 | 02 — Legacy idle-timeout fallback test | 2 | SCALE-09 | — | JettyIdleTimeoutFallbackTest passes | unit | `./gradlew test --tests JettyIdleTimeoutFallbackTest` | ✅ | ✅ green |
| 20-03-3.1 | 03 — AppRuntimeConfig record + AppRuntimeConfigTest | 3 | SCALE-09 | — | All 4 fields [reserved]; binding round-trip | unit | `./gradlew test --tests AppRuntimeConfigTest` | ✅ | ✅ green |
| 20-03-3.2 | 03 — paralife.runtime.app.* yaml block + regression | 3 | SCALE-09 | — | outbound-queue-size alongside, not moved (D-20) | regression | `grep -A30 "^  runtime:" src/main/resources/application.yml \| grep -q "    app:" && grep -A30 "^  runtime:" src/main/resources/application.yml \| grep -q "queue-watermark-pct" && grep -A60 "^paralife:" src/main/resources/application.yml \| grep -q "outbound-queue-size" && ./gradlew test --tests "*Admission*" --tests "*Backpressure*" --tests AppRuntimeConfigTest --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` | ✅ | ✅ green |
| 20-04-4.1 | 04 — 20-RUNTIME.md skeleton §1,§2,§5,§6 | 3 | SCALE-09 | — | doc-only; no code change | manual | `test -f .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "## §1 Architectural Principle: WS:entity 1:1" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "## §2 Tuning Surface" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "## §3 Per-Scale-Tier Recipes" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "## §6 Profile Index" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` | ✅ | ✅ green |
| 20-04-4.2 | 04 — §3 Per-Scale-Tier Recipes (human recipe runnability) | 3 | SCALE-09 | — | All 3 tier recipes boot server + harness; JFR produced | manual | Boot-verify: server ready at each tier; harness rc=0; JFR present + size check | ✅ | ✅ green (plan 4 ship-gate verify at HEAD `8f183cf`) |
| 20-05-5.0 | 05 — JFR triage (codec signals at active-50xfood 103a615) | 4 | SCALE-08, SCALE-09 | T-20-V5 | All signals below threshold; null-result documented | manual + regression | `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest && ./gradlew test` | ✅ | ✅ green (commit `bd59e60`) |
| 20-05-5.1 | 05 — Equivalence proof (three-gate ×2 + invariant checks) | 4 | SCALE-08, SCALE-09 | T-20-V5 | Three-gate GREEN ×2 consecutive; T-20-V5 bounds intact | regression | `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest && ./gradlew test` | ✅ | ✅ green (commit `becbb2e`) |
| 20-05-5.2 | 05 — Tuned-state JFR + metric sidecar capture at `424e06d` | 4 | SCALE-08, SCALE-09 | — | Artifacts present; §4.2/§4.4 populated; delta within noise floor | manual | `ls profiles/jfr-1000bots-active-50xfood-tuned-424e06d.jfr profiles/metrics-1000bots-active-50xfood-tuned-424e06d.json`; check §4.2 tuned column | ✅ | ✅ green (commit `328ff7a`) |
| 20-06-6.1 | 06 — Finalise 20-RUNTIME.md §4.3 + §4.2 100/500 + §6 index | 5 | SCALE-08, SCALE-09 | — | Zero pending markers; §4.3 all tiers present; §6 complete | manual + regression | `test -f .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "## §4.3 Per-tier narrative" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -qE "jfr-1000bots-active-50xfood-(103a615\|tuned-424e06d)" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "metrics-1000bots-active-50xfood-103a615" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md && grep -q "metrics-1000bots-active-50xfood-tuned-424e06d" .planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` | ✅ | ✅ green (commit `6be125e`) |
| 20-06-6.2 | 06 — CLAUDE.md §Runtime tuning subsection (D-15) | 5 | SCALE-08, SCALE-09 | — | Subsection after Connection model; WS:entity 1:1 cited | manual | `grep -q "### Runtime tuning (Phase 20)" CLAUDE.md && grep -q "WS:entity 1:1 model from §Connection model is non-negotiable" CLAUDE.md && grep -q "20-RUNTIME.md" CLAUDE.md` | ✅ | ✅ green (commit `684a5c4`) |
| 20-06-6.3 | 06 — README.md operator paragraph + scaffolding (D-16) | 5 | SCALE-08, SCALE-09 | T-20-V7 | WS:entity 1:1 + Runtime tuning section; ≥25 lines | manual | `grep -q "WS:entity 1:1" README.md && grep -q "## Runtime tuning" README.md && grep -q "20-RUNTIME.md" README.md && wc -l README.md \| awk '$1 >= 25 { exit 0 } { exit 1 }'` | ✅ | ✅ green (commit `eb5e164`) |
| 20-06-6.4 | 06 — D-02 inline comments at WS upgrade + OutboundSender.attachSession | 5 | SCALE-08, SCALE-09 | T-20-DOC-DRIFT | All 4 files contain WS:entity 1:1; three-gate + full suite green | regression | `grep -q "Phase 20 D-02" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java && grep -q "Phase 20 D-02" src/main/java/com/paralife/admission/OutboundSender.java && ./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest && ./gradlew test` | ✅ | ✅ green (commit `d37d281`) |
| 20-06-6.5 | 06 — Flip 20-VALIDATION.md frontmatter (I7 fix) | 5 | SCALE-08, SCALE-09 | — | nyquist_compliant: true; wave_0_complete: true; map populated | manual | `head -10 20-VALIDATION.md \| grep -q 'nyquist_compliant: true' && head -10 20-VALIDATION.md \| grep -q 'wave_0_complete: true'` | ✅ | ✅ green (this task) |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

No new test files. Existing infrastructure covers all phase requirements:
- D-11 three-gate stack (`GoldenTraceEquivalenceTest`, `GoldenTraceWithActionsTest`, `LiveEntityRegistryInvariantTest`) is the codec/encode/lifecycle wire-equivalence safety net.
- Existing `JettyDeflateCustomizerTest`, `*Admission*`, `*Backpressure*` tests catch wiring regressions for new `@ConfigurationProperties` records.
- D-12 explicitly forbids re-enabling remaining P22 `@Disabled` tests — live inventory at HEAD `d37d281`:
  - TD-22-A: `MetabolismIntegrationTest:82` @Disabled
  - TD-22-B: `EncodeDeflatePerformanceGateTest:112` @Disabled
  - TD-22-C: `PopulationDynamicsTest:96` @Disabled
  - `ToxinTest:348` @Disabled (Plan 06 TODO — full-stack smoke)
  - `ToxinTest:357` @Disabled (Plan 06 TODO — full-stack smoke)
  - `CellularAutomatonTest:151` @Disabled (perf-only — not CI)
  - TD-22-D `HundredBotIntegrationTest` was RE-ENABLED (not in above list — active)
  - Total: 6 annotations, 5 files (confirmed by `grep -rn "@Disabled(" src/test/java` at HEAD `d37d281`)

What plans MUST create (non-test artifacts):
- [x] `tools/async-profiler-bootstrap.md` — Plan 1
- [x] `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md` — Plan 1
- [x] `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-{100,500,1000}bots-baseline-c22e487.jfr` — Plan 1 (as originally captured; superseded by Plan 1c re-anchor → `62c1b44` series; `c22e487` files retained on disk for history)
- [x] `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` — Plan 2
- [x] `src/main/java/com/paralife/runtime/AppRuntimeConfig.java` — Plan 3
- [x] `.planning/phases/20-connection-multiplexing-runtime-tuning/20-05-TRIAGE.md` + `jfr-1000bots-active-50xfood-tuned-424e06d.{jfr,meta.json}` + `metrics-1000bots-active-50xfood-tuned-424e06d.json` — Plan 5 (Pass-2 Concern #10)
- [x] `.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` — Plan 6
- [x] `CLAUDE.md` §Runtime tuning subsection — Plan 6
- [x] `README.md` operator paragraph — Plan 6
- [x] Inline rationale comments at `WorldWebSocketHandler` WS-upgrade and `OutboundSender.attachSession` — Plan 6

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Profile artifacts captured against `62c1b44` (Plan 1c re-anchor) | SCALE-09 (D-19) | JFR/async-profiler runs invoke `loadHarnessJar` against a running server; not a Gradle test | `git checkout 62c1b44`; start server with `cap=1500`; run harness tiers 100/500/1000; commit JFRs + flamegraphs + meta.json sidecars — as done in Plan 1c (PR #1 `ddf0dfa`) |
| Tuned JFR captured against HEAD with deltas vs baseline | SCALE-09 (D-13) | same — measurement run | Shipped: `jfr-1000bots-active-50xfood-tuned-424e06d.jfr` at SHA `424e06d` (Plan 5 `328ff7a`); `grep -rE "WS:entity 1:1\|20-RUNTIME\.md" src/main/java/com/paralife/websocket/WorldWebSocketHandler.java src/main/java/com/paralife/admission/OutboundSender.java` |
| Per-tier recipes copy-pasteable for Phase 21 benchmark consumption | SCALE-09 (D-14) | doc review | reviewer copies a 100/500/1000-bot recipe block out of `20-RUNTIME.md`, runs it as-is, confirms server boots and `loadHarnessJar` connects (validated in Plan 4 ship-gate at HEAD `8f183cf`) |
| WS:entity 1:1 deliberate-choice rationale codified in 3 places | SCALE-08 (D-02) | grep + visual review | `grep -lE "WS:entity 1:1" README.md CLAUDE.md src/main/java/com/paralife/websocket/WorldWebSocketHandler.java src/main/java/com/paralife/admission/OutboundSender.java \| wc -l` = 4 |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify (three-gate stack) OR documented manual capture (D-13/D-14)
- [x] Sampling continuity: every plan wave runs full suite; no >2 consecutive tasks without quick-stack verify
- [x] Wave 0 covers all MISSING references (non-test artifacts all checked above)
- [x] No watch-mode flags (Gradle invocations are one-shot)
- [x] Feedback latency < 60 s (quick) and < 15 min (full)
- [x] `nyquist_compliant: true` set in frontmatter
- [x] D-12 enforced: remaining P22 `@Disabled` deferrals untouched — live inventory at HEAD `d37d281`: 6 annotations across 5 files (TD-22-A `MetabolismIntegrationTest`, TD-22-B `EncodeDeflatePerformanceGateTest`, TD-22-C `PopulationDynamicsTest`, `ToxinTest` ×2 Plan-06 TODOs, `CellularAutomatonTest` perf-only); TD-22-D `HundredBotIntegrationTest` was RE-ENABLED per plan
- [x] D-19 enforced (as re-anchored by Plan 1c F6): canonical churn baseline series cites `62c1b44`, active-scenario evidence set cites `103a615`, tuned capture cites `424e06d`; superseded `c22e487` series retained on disk for history (Task 6.1 step 7 reconciles the 20-CONTEXT.md D-19 wording)

**Approval:** planner-signed-off (d37d281 on 2026-06-05)

---

## Validation Audit 2026-06-11

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |

Re-audit at HEAD `d059521`. Spot-checks: all 10 artifact files present, D-02 inline comments intact, CLAUDE.md + README.md sections present, all 6 test files exist, @Disabled count still 6 (matches D-12 inventory), three-gate quick stack green (32 s). No status changes — all tasks remain ✅ green.
