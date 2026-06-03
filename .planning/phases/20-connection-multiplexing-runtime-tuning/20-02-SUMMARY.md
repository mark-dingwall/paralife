---
phase: 20-connection-multiplexing-runtime-tuning
plan: 02
subsystem: runtime
tags: [config, jetty, websocket, tuning, d-07, d-09]
requires:
  - 20-01b (A1 verification: all 8 Configurable setters present on Jetty 12.0.18)
provides:
  - JettyRuntimeConfig record (paralife.runtime.jetty.*)
  - All 8 Configurable setters wired into JettyRequestUpgradeStrategy
  - Legacy paralife.websocket.idle-timeout-ms fallback (one-phase back-compat)
affects:
  - JettyDeflateCustomizer (bean signature + javadoc + resolveEffectiveIdleMs helper)
  - application.yml (paralife.runtime.jetty: sub-block added under existing runtime: key)
tech-stack:
  added: []
  patterns: [@ConfigurationProperties-record (D-09), @ConstructorBinding, @DefaultValue, compact-ctor validation, package-private helper extraction for unit-testability]
key-files:
  created:
    - src/main/java/com/paralife/runtime/JettyRuntimeConfig.java
    - src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java
    - src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java
  modified:
    - src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java
    - src/main/resources/application.yml
decisions:
  - D-07 (layer 2 — Jetty tuning surface)
  - D-09 (record shape mirrors AdmissionConfig)
  - Pass-2 Concern #16 (idleTimeoutMs=60000 inherits project-current default, not Jetty's 30000)
  - Cross-AI review #4 (legacy fallback resolution + case E footgun pinned)
  - Cross-AI review #9 (Task 2.3 is pure unit test of static helper, no Spring context)
metrics:
  duration: ~25 min
  completed: 2026-06-03
  tasks: 3
  test-count: 9 (JettyRuntimeConfigTest) + 1 (BindingRoundTripTest) + 5 (JettyIdleTimeoutFallbackTest) = 15 new tests
---

# Phase 20 Plan 02: Jetty Runtime Tuning Surface — Summary

**One-liner:** `paralife.runtime.jetty.*` `@ConfigurationProperties` record wires all 8 Jetty 12 Configurable setters (idleTimeout, input/output buffers, max-frame/binary/text, autoFragment, maxOutgoingFrames) into `JettyRequestUpgradeStrategy` via Spring's `@ConfigurationPropertiesScan`, with one-phase legacy `paralife.websocket.idle-timeout-ms` fallback and zero behavioural change at boot with no overrides.

## What Was Built

### Task 2.1 — `JettyRuntimeConfig` record + test

Created `src/main/java/com/paralife/runtime/JettyRuntimeConfig.java` (105 LOC) and `src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java` (114 LOC).

**All 8 fields retained per 20-01b-SUMMARY §A1 (javap evidence confirmed all 8 setters present on Jetty 12.0.18):**

| Field | Type | Default | Validation lower bound | Tag |
|-------|------|---------|------------------------|-----|
| `inputBufferSize` | int | 4096 | ≥256 | [launch-only] |
| `outputBufferSize` | int | 4096 | ≥256 | [launch-only] |
| `maxFrameSize` | long | 65536 | ≥1024 | [launch-only] |
| `maxBinaryMessageSize` | long | 65536 | ≥1024 | [launch-only] |
| `maxTextMessageSize` | long | 65536 | ≥1024 | [launch-only] |
| `idleTimeoutMs` | long | **60000** (project-current, not Jetty's 30000) | ≥1000 | [launch-only] |
| `autoFragment` | boolean | true | n/a | [launch-only] |
| `maxOutgoingFrames` | int | -1 (unlimited) | `-1` OR `≥1` (carve-out) | [launch-only] |

Mirrors `AdmissionConfig` shape exactly: `@ConfigurationProperties(prefix = "paralife.runtime.jetty")` + `@ConstructorBinding` + per-field `@DefaultValue` + compact-ctor validation with property-key-tagged messages + `defaults()` factory.

**Tests (10 total):** 1 default-shape + 6 single-field rejection + 1 carve-out (`maxOutgoingFrames` 0 rejected; -1 and positive accepted) + 1 Spring binding round-trip via `TestApp` `@Configuration` wrapper (Pass-3 Concern #22 — record itself is not a `@Configuration`).

### Task 2.2 — Wire through `JettyDeflateCustomizer` + yaml block

Mutated `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java`:

- **Signature:** `jettyRequestUpgradeStrategy(JettyRuntimeConfig runtimeConfig, @Value("${paralife.websocket.idle-timeout-ms:60000}") long legacyIdleTimeoutMs)` — `@Value` slot kept alive for one-phase back-compat.
- **Lambda body:** all 8 `Configurable` setters chained on `addWebSocketConfigurer`, using `runtimeConfig.*()` accessors.
- **Helper extracted (concern #4):** package-private `static long resolveEffectiveIdleMs(JettyRuntimeConfig, long)` — unit-testable by Task 2.3 without Spring context.
- **Javadoc rewritten:** drops the orphan "raised from Jetty's 30s default to 60s" wording and documents the precedence rules, the case-E footgun, the project-current-default disposition (Pass-2 Concern #16), and the launch-only contract.
- **deflateEnforcementFilter (lines 84-180)** untouched.

Added `paralife.runtime.jetty:` block to `application.yml` as a **sibling of the existing `app:`** under the **single existing `paralife.runtime:` key**. No duplicate top-level `runtime:` key — SnakeYAML 2.x duplicate-mapping guard satisfied (`grep -cE '^  runtime:' = 1`). Per-field `[launch-only]` tag on all 8 jetty fields; existing `app:` block retains its `[reserved — no effect]` tags untouched.

### Task 2.3 — Helper unit test for legacy fallback resolution

Created `src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java` (77 LOC). Pure unit test of the static helper, no Spring context (cross-AI review #9 — property-binding round-trip is already proven by Task 2.1 Test 9). Five `@Nested` classes one per yaml combination:

| Case | NEW key | LEGACY key | Expected effectiveIdleMs | Rationale |
|------|---------|------------|--------------------------|-----------|
| A — neither set | unset (60000) | unset (60000) | 60000 | both at default → new-key default |
| B — new-only set | 30000 | unset (60000) | 30000 | new key wins when explicitly set |
| C — legacy-only set | unset (60000) | 45000 | 45000 | legacy fallback honoured |
| D — both set (new ≠ 60000) | 30000 | 45000 | 30000 | new key wins when set to a non-default value |
| **E — both set, new = 60000** | 60000 (explicit) | 45000 | **45000 — LEGACY wins** | Documented footgun: primitive field cannot distinguish explicit-60000 from unset (concern #4). Pinned so it cannot silently regress. Phase 999.x removes legacy key + carve-out. |

## Wiring Diff in JettyDeflateCustomizer

```
- import-block: + com.paralife.runtime.JettyRuntimeConfig
- @Bean signature: + JettyRuntimeConfig runtimeConfig, @Value(legacy) long legacyIdleTimeoutMs
- @Bean body: setIdleTimeout via helper; +setInputBufferSize +setOutputBufferSize
              +setMaxFrameSize +setMaxBinaryMessageSize +setMaxTextMessageSize
              +setAutoFragment +setMaxOutgoingFrames
- +helper:    static long resolveEffectiveIdleMs(JettyRuntimeConfig, long)
- javadoc:    rewritten — drops "raised from Jetty's 30s default to 60s defensive belt"
              wording; adds D-07 layer 2 reference, precedence rules, case-E footgun,
              project-current-default disposition (Pass-2 #16), launch-only contract
- deflateEnforcementFilter() body: untouched (lines 84-180)
```

LOC: +47 / -10 in `JettyDeflateCustomizer.java`; +18 / -3 in `application.yml`.

## Three-Gate Result

```
./gradlew test --tests GoldenTraceEquivalenceTest \
                --tests GoldenTraceWithActionsTest \
                --tests LiveEntityRegistryInvariantTest
BUILD SUCCESSFUL — 4m 41s — 2026-06-03T07:57Z
```

Three-gate stack (D-11) green in-suite (TD-19.5-A in-suite ordering caveat applies as documented).

## Pass-2 Concern #16 — Confirmation

| Surface | "project-current default" wording present? |
|---------|---------------------------------------------|
| `JettyRuntimeConfig.java` class javadoc | ✓ |
| `JettyRuntimeConfig.java` `defaults()` factory javadoc | ✓ |
| `JettyDeflateCustomizer.java` @Bean javadoc | ✓ |
| `application.yml` yaml comment on `runtime:` block | ✓ |
| Test name `defaultsMatchProjectCurrentDefaults` | ✓ |
| Test method body comment explaining 60000 vs 30000 | ✓ |

## What Plan 4 (Per-Tier Recipes) Needs to Know

1. **Effective override semantics with legacy key still alive (Phase 20 only):**
   - Operator setting **only** `paralife.runtime.jetty.idle-timeout-ms` → that value wins.
   - Operator setting **only** `paralife.websocket.idle-timeout-ms` → that value wins (back-compat).
   - Operator setting **both** with new ≠ 60000 → new wins.
   - Operator setting **both** with new = 60000 (explicit) → **legacy wins** (case-E footgun). Recipes that want to pin 60000 should clear the legacy key from yaml/test fixtures.
   - All other 7 fields (buffers, frames, autoFragment, maxOutgoingFrames) have no legacy counterpart — new key always wins.
2. **`maxOutgoingFrames = -1` is the project-default and the Phase 17 D-10 contract.** Per-tier recipes raising a positive cap (e.g. `maxOutgoingFrames: 64`) shift the primary backpressure signal from the D-10 `OutboundSender` queue to Jetty's `WritePendingException`. Do not set this without coordinated review of `WorldWebSocketHandler.markStalled` and `OutboundSender.detachSession`.
3. **All 8 fields are launch-only.** Jetty applies them at WS upgrade per session; no live mutation. `@RefreshScope` does not apply here. Per-tier recipes are `@TestPropertySource` / -D / env-var only.
4. **Validation lower bounds** (compact-ctor) are floors, not ceilings — recipes can raise any field without an upper bound. Maximum sanity is the operator's responsibility (T-20-DOS-1 disposition is the preserved 65536 default cap, not the validation).

## TDD Gate Compliance

- **Task 2.1 (tdd="true"):** RED (`c107a4e` — failing test) → GREEN (`419465d` — implementation). Compliant.
- **Task 2.2 (`type=auto`, no tdd):** wiring + yaml only; no behaviour-adding test required at this gate.
- **Task 2.3 (tdd="true"):** test was the entire deliverable; the helper it tests was extracted in Task 2.2 per the plan's explicit "land both together" instruction (`<action>` block end: *"the executor lands both Task 2.2 and Task 2.3 together so the helper is in place when Task 2.3's test runs"*). Functionally compliant — the test would not compile without the Task 2.2 helper extraction, and `git show 917c36c` shows the helper as the new code Task 2.3's test exercises. Pinned 5 cases (A-E) green on first run.

## Self-Check

```bash
# Files exist
test -f src/main/java/com/paralife/runtime/JettyRuntimeConfig.java       → FOUND
test -f src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java   → FOUND
test -f src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java → FOUND

# Commits
git log --oneline -4
  88e3b62 test(20-02): cover legacy idle-timeout fallback resolution across 5 yaml combos
  917c36c feat(20-02): wire JettyRuntimeConfig through JettyDeflateCustomizer + yaml block
  419465d feat(20-02): implement JettyRuntimeConfig record with all 8 Configurable setters
  c107a4e test(20-02): add failing test for JettyRuntimeConfig record
```

## Self-Check: PASSED

## Deviations from Plan

None — plan executed exactly as written. All acceptance criteria green (verified by inline grep checks); no Rule 1/2/3 auto-fixes triggered; no Rule 4 architectural pauses.
