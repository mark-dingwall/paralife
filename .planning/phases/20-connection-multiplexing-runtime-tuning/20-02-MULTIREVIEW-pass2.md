---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "opencode"]
reviewers_failed: ["codex"]
reviewed_at: 2026-06-03T08:46:23Z
files: ["src/main/java/com/paralife/runtime/JettyRuntimeConfig.java", "src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java", "src/main/resources/application.yml", "src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java", "src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java", "src/main/java/com/paralife/admission/AdmissionConfig.java"]
usage:
  claude: { input: 508138, output: 70, cached: 2576258, tool_calls: 7, elapsed_s: 331.9 }
  gemini: { input: 128118, output: 560, cached: 0, tool_calls: 0, elapsed_s: 153.3 }
  codex: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 3.3 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 270.5 }
synthesizer: claude
synthesized_at: 2026-06-03T08:46:23Z
---

# Cross-AI Review

## Claude Review

Review complete. All ground-truth claims verified against source — including Jetty 12.0.18's actual default constants. Writing up.

---

# Review: Plan 20-02 — Jetty runtime tuning surface (pass 2)

Verified against source, not prose. No re-flags of disposed pass-1 items.

## Headline ask #1 — zero-behaviour-change: **VERIFIED bit-identical** (not just asserted)

Pulled Jetty 12.0.18's own `WebSocketConstants` from the sources jar in the gradle cache:

| Record field | Record default | Jetty `WebSocketConstants` | Match |
|---|---|---|---|
| inputBufferSize | 4096 | `DEFAULT_INPUT_BUFFER_SIZE = 4*1024` | ✓ |
| outputBufferSize | 4096 | `DEFAULT_OUTPUT_BUFFER_SIZE = 4*1024` | ✓ |
| maxFrameSize | 65536 | `DEFAULT_MAX_FRAME_SIZE = 64*1024` | ✓ |
| maxBinaryMessageSize | 65536 | `DEFAULT_MAX_BINARY_MESSAGE_SIZE = 64*1024` | ✓ |
| maxTextMessageSize | 65536 | `DEFAULT_MAX_TEXT_MESSAGE_SIZE = 64*1024` | ✓ |
| maxOutgoingFrames | -1 | `DEFAULT_MAX_OUTGOING_FRAMES = -1` | ✓ |
| autoFragment | true | `DEFAULT_AUTO_FRAGMENT = true` | ✓ |
| idleTimeoutMs | 60000 | (`DEFAULT_IDLE_TIMEOUT = 30s`) — intentional project override, matches old `@Value` default | ✓ |

All 7 newly-pinned literals exactly equal Jetty's runtime defaults. The 8th (idle=60000) preserves the pre-change explicit value. And the mechanism is a true no-op: Jetty's `Configuration.ConfigurationCustomizer.customize()` (lines 222-239) only forwards a setter when its field is non-null — so explicitly calling `setMaxFrameSize(65536)` produces the identical downstream session state as the old path that left it null and inherited 65536. The plan's "if a Jetty point release shifts a default" caveat is the only residual risk, and it's correctly documented. **Contract holds.**

## Headline asks #2/#3/#6 — confirmed

- **All 8 setters wired** (`JettyDeflateCustomizer:105-112`), each to the matching field, none dropped/duped.
- **Legacy fallback resolves** — and `WebSocketKeepaliveIntegrationTest:41` sets *only* the legacy key (`=2000`), so the case-C fallback is exercised end-to-end by an existing integration test in the green suite. (Weakens TD-20-02-A's "no end-to-end fallback assertion" premise — there de-facto is one.)
- **Bean materialises** — `@ConfigurationPropertiesScan` on `ParalifeApplication` (`com.paralife`) covers `com.paralife.runtime`; the three-gate boots the real context green.
- **Validation fails fast** — every bounded field has a compact-ctor check via `@ConstructorBinding`; malformed yaml throws at bind time, not 4 layers deep at handshake. Answer to ask #4: yes, it fails fast.
- **No yaml shadowing** — single `application.yml`, exactly one `runtime:` key, no test-profile override of `runtime.jetty`.
- **`BindingRoundTripTest` actually runs** — identical static-nested `@SpringBootTest(classes=TestApp.class)` shape as the green `AdmissionConfigTest.BindsAllKeys`; not dead.

---

## LOW

**[LOW] `JettyRuntimeConfigTest.java:96-99` (BindingRoundTripTest) — the two override fields are the only two whose Spring-bound `@DefaultValue` goes unverified.** The test overrides `input-buffer-size` and `idle-timeout-ms`, then asserts the other six at their bound defaults. So the production path (Spring binds via `@DefaultValue`, not via `defaults()`) is never asserted to yield `idleTimeoutMs=60000` — the literal zero-behaviour-change linchpin. It's pinned only by `defaultsMatchProjectCurrentDefaults` (the `defaults()` factory, which `JettyDeflateCustomizer` does **not** call) and by `CaseA` (also via `defaults()`). A future edit to `@DefaultValue("60000")` alone would pass every test. Cheap fix: override two *other* fields, or add one no-override binding assertion that pins `idleTimeoutMs()==60000` at the binding layer.

**[LOW] `JettyRuntimeConfigTest.java` — `maxOutgoingFrames` carve-out only tests `0` as the reject case.** The branch `!= -1 && < 1` correctly rejects all negatives except `-1` (e.g. `-2`, `-5`), but only `0` is asserted as rejected. Logic is right; the test is just thin on the negative-non-sentinel edge. One extra `-2`-rejected assertion closes it.

## NIT

**[NIT] `JettyDeflateCustomizer.java:67,120-121` — `LEGACY_IDLE_TIMEOUT_DEFAULT_MS` name undersells its dual role.** The helper uses it for both the legacy-`@Value` default *and* `runtimeConfig.idleTimeoutMs() == LEGACY_IDLE_TIMEOUT_DEFAULT_MS` (the **new** record's default). The javadoc documents this, but the name reads LEGACY-only. The constant now silently couples three 60000s (the `@Value(:60000)` literal at line 99, the record `@DefaultValue("60000")`, and itself); if the record default ever moves, the case-E proxy misfires with no test catching it (see LOW above). Consider `IDLE_TIMEOUT_DEFAULT_MS`.

**[NIT] `application.yml:64` — comment "also bound via legacy paralife.websocket.idle-timeout-ms" reads as if the two keys are aliased.** They're independent keys joined by a one-way fallback. Minor wording that could mislead an operator into thinking setting one sets the other.

---

No regressions introduced by the pass-1 fixes: the `LEGACY_IDLE_TIMEOUT_DEFAULT_MS` extraction is used consistently in the helper and all five case A–E assertions still pin correctly; `BindingRoundTripTest` asserting all 8 fields is intact.

**VERDICT: SHIP** (the two LOWs and two NITs are polish, not gates; zero-behaviour-change is source-verified).

---

## Gemini Review

No new findings at or above BLOCKER, HIGH, MEDIUM, LOW, or NIT severity were surfaced.

The codebase strictly adheres to the provided Phase 20 Plan 02 constraints. All specific items requested for review and explicit fixes from pass 1 have been successfully implemented and verified:

1. **Zero-behaviour-change claim:** Verified. Defaults explicitly match project-current values, keeping `idleTimeoutMs` pinned to 60000 to inherit legacy project-current default and preserving standard Jetty buffer properties at 4096, 65536, etc.
2. **All 8 Configurable setters wired:** Verified. All eight setters (`setIdleTimeout`, `setInputBufferSize`, `setOutputBufferSize`, `setMaxFrameSize`, `setMaxBinaryMessageSize`, `setMaxTextMessageSize`, `setAutoFragment`, `setMaxOutgoingFrames`) are cleanly wired through the `addWebSocketConfigurer` lambda chain in `JettyDeflateCustomizer`.
3. **Legacy `paralife.websocket.idle-timeout-ms` fallback:** Verified. Back-compat handled correctly with precedence proxy logic employing the `LEGACY_IDLE_TIMEOUT_DEFAULT_MS` constant. The explicit 60000 footgun is thoroughly pinned and documented via `JettyIdleTimeoutFallbackTest.CaseE_bothSetNewAtDefault`.
4. **Record validation bounds:** Verified. Lower bounds checking in the `JettyRuntimeConfig` compact constructor properly covers all fields. The carve-out allowing `-1` and caps `>= 1` for `maxOutgoingFrames` accurately blocks invalid `0` caps.
5. **Test quality:** Verified. `BindingRoundTripTest` actively asserts the resolved property state using a true `@SpringBootTest` Spring binding phase without mocking out the results, while `JettyIdleTimeoutFallbackTest` avoids loading a heavy Spring context just to test the fallback proxy static method logic.
6. **Spring wiring pitfalls:** Verified. The context loading test gracefully avoids the "Java record is not a `@Configuration`" startup failure by encapsulating it within a static `TestApp` `@Configuration` wrapper, matching the `AdmissionConfigTest` precedent.
7. **yaml block semantics:** Verified. The `jetty:` block correctly resides under the single existing `runtime:` key as a sibling to `app:` to prevent duplicate key boot failures. Every attribute incorporates the `[launch-only]` comment tag matching the record field docs.
8. **Mirror of AdmissionConfig:** Verified. Adheres to established code shapes via `@ConstructorBinding`, compact constructor layout, field-by-field `@DefaultValue` usage, and the `defaults()` static factory utility.

VERDICT: SHIP

---

## Codex Review (FAILED)

**Status:** failed — exit 1

Elapsed: 3.3s

---

## Opencode Review

I've reviewed all 5 source files against the review criteria. Here are the pass-2 findings:

---

## BLOCKER

None found.

## HIGH

None found.

## MEDIUM

**1.** `JettyRuntimeConfig.java:62` — `idleTimeoutMs` javadoc says `see application.yml#paralife.runtime.jetty.idle-timeout-ms comment` for the keepalive-safe minimum, but the yaml comment at that key only says "project-current default; also bound via legacy". The keepalive-safe `> 2 × (keepaliveTicks × tick.interval-ms)` formula lives at `paralife.websocket.idle-timeout-ms` (application.yml line ~42), not at the referenced key. A reader following the cross-reference finds nothing about keepalive timing. Either the yaml comment should carry the formula, or the javadoc should point to the legacy key or inline the information.

**2.** `JettyRuntimeConfigTest.java` — No boundary-acceptance tests exist for the validation floors. All rejection tests use values substantially below the floor (128, 512L, 500L, 0) and the `defaults()` test uses values well above floors. If the compact constructor had an off-by-one (`<=` where it should be `<`), boundary-accepting values like `inputBufferSize=256`, `maxOutgoingFrames=1`, or `maxFrameSize=1024` would be silently rejected — and no test would catch it. The constructor logic is simple enough that this is unlikely to be wrong, but the test gap is real. One test per validated field exercising the boundary value would close it.

## LOW

**3.** `JettyRuntimeConfigTest.java:108` — `acceptsMaxOutgoingFramesUnlimitedAndPositive` tests `maxOutgoingFrames=8` (comfortably above the floor) but not the boundary positive `1`. Given the validation carve-out (`!= -1 && < 1`) is non-trivial, the boundary case deserves explicit coverage.

**4.** `JettyDeflateCustomizer.java:110` — The `@Value` default `:60000` is a hardcoded literal, but `resolveEffectiveIdleMs` uses the `LEGACY_IDLE_TIMEOUT_DEFAULT_MS` constant. Changing the constant without updating the `@Value` default would break resolution logic. The `JettyIdleTimeoutFallbackTest` would catch this (it constructs records directly, not via `@Value`), but the coupling wart exists and there's no compiler-level enforcement. A comment on the constant noting "must match `@Value` default in `jettyRequestUpgradeStrategy`" would be a cheap defense for future contributors who don't know Phase 999.x is the cleanup.

## NIT

**5.** `JettyRuntimeConfigTest.java:117` — `BindingRoundTripTest` added `webEnvironment = NONE` which is strictly better than the plan template (no embedded server for a config-binding test) but this deviation wasn't mentioned in the summary's deviations section.

---

### Cross-check results against the "Specific things I want eyes on":

| # | Check | Verdict |
|---|-------|---------|
| 1 | Zero-behaviour-change defaults | Pass — all 8 defaults in record + yaml match claimed values. Cannot independently verify Jetty 12.0.18 source defaults from here, but code is internally consistent. |
| 2 | All 8 Configurable setters wired | Pass — every setter invoked in the `addWebSocketConfigurer` lambda, matching the record fields one-to-one. |
| 3 | Legacy fallback + precedence | Pass — `resolveEffectiveIdleMs` logic matches the 5-case truth table; `@Value` slot intact; new-key-wins-except-case-E documented and pinned by test. |
| 4 | Record validation bounds | Pass (with gap) — All floors enforced; malformed yaml fails at binding time with property-key-tagged messages. Boundary-acceptance untested (see MEDIUM #2). |
| 5 | Test quality | Pass — Tests assert observable record state (defaults, Spring binding, helper resolution). Not theatre. Missing boundary acceptance (MEDIUM #2). |
| 6 | Spring wiring / auto-discovery | Pass — `@ConfigurationPropertiesScan` on `ParalifeApplication` (confirmed from plan context); `@EnableConfigurationProperties` on `TestApp` for slice tests. Constructor injection into `JettyDeflateCustomizer` will materialize. |
| 7 | yaml block `[launch-only]` accuracy | Pass — All 8 fields tagged `[launch-only]`; field names match record component names via Spring's relaxed binding; one `runtime:` key, no duplicate. Keepalive cross-ref gap (MEDIUM #1). |
| 8 | Mirrors AdmissionConfig pattern | Pass — `@ConfigurationProperties`, `@ConstructorBinding`, per-field `@DefaultValue`, compact-ctor validation with key-tagged messages, `defaults()` factory. |

---

**VERDICT: SHIP WITH FIXES** — no blockers, two MEDIUM items (broken doc cross-reference, missing boundary-acceptance tests) that are safe to fix post-hoc or backlog.

---

## Consensus Summary

### Agreed Strengths
- **Zero-behaviour-change holds.** All 3 reviewers confirm the 8 pinned defaults match project-current values (idleTimeoutMs=60000, buffers 4096, frame/message 65536, maxOutgoingFrames=-1, autoFragment=true). Claude independently verified 7 literals bit-identical to Jetty 12.0.18 `WebSocketConstants` from the sources jar; opencode confirmed internal consistency but could not reach Jetty source.
- **All 8 Configurable setters wired one-to-one** through the `addWebSocketConfigurer` lambda in `JettyDeflateCustomizer` — none dropped or duped. (all 3)
- **Legacy `paralife.websocket.idle-timeout-ms` fallback + precedence correct**, matching the 5-case truth table, new-key-wins-except-case-E, pinned by `JettyIdleTimeoutFallbackTest.CaseE`. (all 3)
- **Validation fails fast at bind time** — compact-ctor floors on every bounded field, key-tagged messages, `maxOutgoingFrames` carve-out (`!= -1 && < 1`) correctly rejects 0 while allowing -1. (all 3)
- **Spring wiring avoids the record-not-@Configuration boot trap** via static `TestApp` wrapper, mirroring `AdmissionConfigTest`. Single `runtime:` yaml key, no duplicate/shadow, `[launch-only]` tags present. (all 3)
- **Tests assert observable state, not theatre** — `BindingRoundTripTest` uses real Spring binding; fallback test exercises helper resolution directly. (all 3)

### Agreed Concerns
- **[LOW–MEDIUM] Boundary-acceptance / boundary-positive test gap.** Rejection tests use values well below floors; no test pins boundary-accepting values (e.g. `maxOutgoingFrames=1`, `inputBufferSize=256`). An off-by-one (`<=` vs `<`) would silently reject valid config uncaught. maxOutgoingFrames only tests `0` as reject, not `-2`. (claude LOW; opencode MEDIUM #2 + LOW #3) — *cheap: one boundary assertion per validated field.*
- **[LOW–MEDIUM] yaml idle-timeout comment misleads.** Comment reads as if the two keys are aliased / does not carry the keepalive-safe `> 2×(keepaliveTicks×interval)` formula that actually lives at `paralife.websocket.idle-timeout-ms`. A reader following the cross-ref finds nothing. (claude NIT; opencode MEDIUM #1) — *fix: inline formula or repoint javadoc to legacy key.*
- **[LOW/NIT] `LEGACY_IDLE_TIMEOUT_DEFAULT_MS` coupling.** Constant silently couples three 60000s (the `@Value(:60000)` literal, record `@DefaultValue`, itself) with no compiler enforcement; name undersells its dual legacy+new-default role. (claude NIT; opencode LOW #4) — *fix: rename `IDLE_TIMEOUT_DEFAULT_MS` and/or add "must match @Value default" comment.*

### Divergent Views
- **Severity of the test-coverage gap:** opencode rates boundary-acceptance MEDIUM; claude rates the equivalent thin-coverage LOW. Worth a call on whether it gates.
- **Binding-layer linchpin gap (claude only):** `BindingRoundTripTest` overrides the two fields (input-buffer-size, idle-timeout-ms) whose production Spring `@DefaultValue` path is the zero-behaviour-change linchpin — so `idleTimeoutMs()==60000` is never asserted at the binding layer (only via `defaults()`, which `JettyDeflateCustomizer` does not call). A lone edit to `@DefaultValue("60000")` would pass every test. Not surfaced by gemini/opencode — worth investigating; cheap to close by overriding two *other* fields.
- **Thoroughness spread:** gemini surfaced zero findings (clean SHIP, no NITs); claude and opencode each found polish items. gemini's all-pass may mean it did not probe the doc cross-ref or boundary edges the others did.
- **`webEnvironment=NONE` deviation (opencode NIT #5 only):** strictly better than the plan template but undocumented in the summary's deviations section.

**Consensus verdict: SHIP** — no blockers or highs across any reviewer; all items are LOW/NIT polish safe to fix post-hoc or backlog.
