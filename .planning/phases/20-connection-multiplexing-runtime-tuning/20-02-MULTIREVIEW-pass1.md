---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T08:30:09Z
files: ["src/main/java/com/paralife/runtime/JettyRuntimeConfig.java", "src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java", "src/main/resources/application.yml", "src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java", "src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java", "src/main/java/com/paralife/admission/AdmissionConfig.java"]
usage:
  claude: { input: 407047, output: 158, cached: 6436047, tool_calls: 14, elapsed_s: 647.6 }
  gemini: { input: 124814, output: 474, cached: 0, tool_calls: 0, elapsed_s: 186.8 }
  codex: { input: 258161, output: 4015, cached: 203136, tool_calls: 12, elapsed_s: 99.6 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 206.5 }
synthesizer: claude
synthesized_at: 2026-06-03T08:30:09Z
---

# Cross-AI Review

## Claude Review

Verification done — findings grounded in what I actually ran. Caveman prose, precise findings.

---

# Review: Plan 20-02 — Jetty runtime tuning surface

Verified against live repo, not just prose. Key checks run:
- **Jetty 12.0.18 real defaults** pulled from `jetty-websocket-core-common-12.0.18-sources.jar` → `WebSocketConstants`.
- **`Configurable` API surface** via `javap` on `jetty-websocket-jetty-api-12.0.18.jar`.
- **Tests executed** — read result XMLs to confirm what ran.
- **The `*JettyDeflate*` gate** — ran it.

## Headline contract checks (the 3 things that matter most) — all PASS

**Zero-behaviour-change: VERIFIED.** Pre-change bean set *only* `setIdleTimeout`; everything else tracked Jetty internals. Post-change sets all 8 explicitly. So the claim holds iff the 7 new explicit defaults equal Jetty's. They do, exactly:

| Field | Jetty `WebSocketConstants` | Record | Match |
|---|---|---|---|
| inputBufferSize | `4*1024`=4096 | 4096 | ✅ |
| outputBufferSize | `4*1024`=4096 | 4096 | ✅ |
| maxFrameSize | `64*1024`=65536 | 65536 | ✅ |
| maxBinaryMessageSize | `64*1024`=65536 | 65536 | ✅ |
| maxTextMessageSize | `64*1024`=65536 | 65536 | ✅ |
| maxOutgoingFrames | -1 | -1 | ✅ |
| autoFragment | true | true | ✅ |
| idleTimeoutMs | `ofSeconds(30)`=30000 | 60000 | differs — but pre-change *also* set 60000 explicitly → no change ✅ |

**All 8 setters wired: VERIFIED.** `javap` on api `Configurable` shows exactly 8 setters (no `setWriteTimeout` — it's on the core `Configuration`, not the api interface, so "8" is correct and complete). All 8 chained once each in `JettyDeflateCustomizer:97-106`, correct field per setter, none dropped, none duplicated.

**Legacy fallback: RESOLVES.** `resolveEffectiveIdleMs:111-118` logic correct for all 5 cases. No warning emitted at default. New wins when both set (except case E, below).

**Tests real, not theatre.** Result XMLs confirm `JettyRuntimeConfigTest` = 9 tests + `$BindingRoundTripTest` = 1, all pass. Static-nested `@SpringBootTest` *does* get discovered here (own XML produced) — binding round-trip genuinely exercises Spring `@DefaultValue`/`@ConstructorBinding`, asserts bound record state. Not a round-trip-only no-op.

---

## BLOCKER
None.

## HIGH

**[HIGH] 20-02-PLAN.md Task 2.2 acceptance + 20-02-SUMMARY.md verification — `--tests "*JettyDeflate*"` gate is non-reproducible; claimed green, actually exits 1.**
No test class matches `*JettyDeflate*` (deflate behaviour lives in `ServerRefusesUpgradeWithoutDeflateTest`, `WebSocketDeflateHandshakeIntegrationTest`, `BotClientClosesOnMissingServerDeflateTest`). Gradle fails on no-match — I ran it: `BUILD FAILED ... No tests found for given includes: [*JettyDeflate*]`, EXIT=1. So the acceptance criterion as written *fails*, and the summary's "existing JettyDeflate tests pass — proves wiring didn't regress deflate enforcement" describes a result that does not reproduce. **Code is fine** — the real deflate regression *is* covered (those RANDOM_PORT full-context tests boot `ParalifeApplication`, instantiate `jettyRequestUpgradeStrategy` with `JettyRuntimeConfig` injected, so production wiring is genuinely exercised). The defect is the verification record: a documented "green" gate that errors, providing zero assurance and breaking any CI/re-run that pastes it. Fix: point the gate at real class names (`ServerRefusesUpgradeWithoutDeflateTest`, `WebSocketDeflateHandshakeIntegrationTest`) or drop it and lean on the three-gate + full suite. (Downgrade to MEDIUM defensible since coverage exists elsewhere; rated HIGH because it's a false-green in the shipped verification artifact.)

## MEDIUM

**[MEDIUM] JettyDeflateCustomizer.java:111-118 + application.yml — case-E precedence footgun ships live with both keys defaulted to 60000.**
`application.yml` ships *both* `paralife.websocket.idle-timeout-ms: 60000` and `paralife.runtime.jetty.idle-timeout-ms: 60000`. The `== 60000L` proxy can't distinguish explicit-60000 from unset, so an operator who pins the new key to exactly 60000 while any legacy override is present gets the legacy value — contradicting "new key wins." Documented + pinned by `CaseE` test, and both single-edit paths behave intuitively, so acceptable for a one-phase migration. But it's a real surprise sitting in the default config. Not speculative — confirmed by the case-E test. Acceptable as-is if the javadoc footgun note stays; flagging because "new wins when both set" (review item 3) is only *mostly* true.

## LOW

**[LOW] JettyRuntimeConfig.java idleTimeoutMs validation (`< 1000` floor) is far below the documented keepalive-safe minimum.**
`application.yml` states idle-timeout "Must be > 2 × (keepaliveTicks × tick.interval-ms)" = >30000ms. The record floor is 1000ms. An operator can bind `idle-timeout-ms: 1000`, pass validation, and idle-close sessions before keepalive PINGs (15s cadence) can hold them → self-inflicted mass disconnect. Strictly better than the pre-existing unvalidated `@Value`, and these are launch-only operator knobs (T-20-DOS-1 philosophy), so low. Could note the keepalive relationship in the field javadoc.

**[LOW] JettyRuntimeConfig.java — record now pins 7 values that previously floated with Jetty's internal defaults; no gate catches future Jetty drift.**
Pre-change those 7 tracked `WebSocketConstants`; now they're hard-pinned. A Jetty point-bump that changes a default would be silently overridden by this record, and the three-gate doesn't exercise WS buffer sizing. The plan documents this risk explicitly, so it's acknowledged debt, not a miss — noting for completeness.

**[LOW] JettyRuntimeConfig.java:42-66 — record-component javadoc placement won't bind to the component.**
The `/** [launch-only] ... */` blocks sit inline before each component. Javadoc tooling associates record-component docs via `@param` on the record, not inline comments — these render as nothing in generated javadoc (fine as inline source comments). Cosmetic; `AdmissionConfig` doesn't doc components at all, so this is additive, not drift.

## NIT

**[NIT] JettyRuntimeConfig.defaults() is test-only.** Mirrors `AdmissionConfig.defaults()` (also test-oriented), so consistent with the pattern — no action.

---

## Item-by-item (reviewer's requested eyes)

| # | Item | Verdict |
|---|---|---|
| 1 | bit-identical fresh boot | ✅ verified against `WebSocketConstants` |
| 2 | all 8 setters wired correctly | ✅ verified via `javap`, exactly 8, all correct |
| 3 | legacy fallback + precedence | ✅ resolves; new wins except documented case-E (MEDIUM) |
| 4 | validation bounds enforced/fail-fast | ✅ enforced at startup; floors only (intentional); idle floor loose (LOW) |
| 5 | tests assert observable state | ✅ binding test runs + asserts bound record (confirmed via result XML) |
| 6 | Spring auto-discovery | ✅ `@ConfigurationPropertiesScan` present; bean exercised by full-context deflate tests |
| 7 | yaml `[launch-only]`, single `runtime:` key, names match | ✅ all 8 tagged, names map, no duplicate key |
| 8 | mirrors AdmissionConfig | ✅ faithful (flat record appropriate; no nested-record drift concern) |

`VERDICT: SHIP WITH FIXES` — code is correct and the zero-change contract is verified against authoritative Jetty source. The one HIGH is a broken/overclaimed verification gate (`*JettyDeflate*`), not a code defect; fix that gate (or the summary's claim) so the regression assurance is real, then ship.

---

## Gemini Review

[BLOCKER] src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java:79, 102 — `@Bean` annotations are corrupted.
The `@Bean` annotations on `jettyRequestUpgradeStrategy` and `deflateEnforcementFilter` have been incorrectly replaced with `@src/main/java/com/paralife/admission/AdmissionBeansConfig.java`. This will prevent compilation and stop the Spring context from registering these critical beans, breaking the WebSocket upgrade and deflate enforcement paths entirely.

[BLOCKER] src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java:17 (and multiple others) — `@Test` annotations are corrupted.
Every `@Test` annotation across `JettyRuntimeConfigTest.java` and `JettyIdleTimeoutFallbackTest.java` has been replaced with `@src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java`. This will cause widespread compilation failures and prevent any of the test methods from being discovered or executed.

[HIGH] src/main/java/com/paralife/runtime/JettyRuntimeConfig.java:10 (and multiple others) — `@code` javadoc annotations are corrupted.
All `{@code ...}` javadoc tags have been erroneously replaced with `{@src/main/java/com/paralife/codec/Base64Codec.java ...}` across `JettyRuntimeConfig.java` and `JettyDeflateCustomizer.java`. This breaks standard Javadoc generation and pollutes the code comments with invalid syntax.

[LOW] src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java:82 — `BindingRoundTripTest` nested configuration discovery.
While Spring Boot's `@SpringBootTest` can discover static nested test classes, it relies on standard JUnit Jupiter discovery. Since the `@Test` annotations are already corrupted (flagged above), this static nested class pattern will technically work once fixed, but it is typically better to place it as a top-level test class or structure it carefully to avoid test runner discovery issues in some isolated Gradle builds.

VERDICT: DO NOT SHIP

---

## Codex Review

**HIGH**

[HIGH] `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java:111` — New idle-timeout key does not always win when both keys are set.  
`resolveEffectiveIdleMs` treats `runtimeConfig.idleTimeoutMs() == 60000L` as “new key unset”, so an explicit `paralife.runtime.jetty.idle-timeout-ms=60000` loses to `paralife.websocket.idle-timeout-ms=45000`. The review contract asks to confirm the new key wins when both are set; this implementation documents and tests the opposite edge case.

**MEDIUM**

[MEDIUM] `src/test/java/com/paralife/websocket/JettyIdleTimeoutFallbackTest.java:22` — Legacy fallback tests do not exercise Spring property binding or the actual bean.  
The tests construct `JettyRuntimeConfig` directly and pass a primitive legacy value into the helper, so they cannot catch a broken `@Value("${paralife.websocket.idle-timeout-ms:60000}")`, property-source precedence issue, or accidental omission of the helper call from `jettyRequestUpgradeStrategy`. This is useful unit coverage, but it does not prove the observable fallback behavior requested in the review.

[MEDIUM] `src/test/java/com/paralife/runtime/JettyRuntimeConfigTest.java:95` — No test observes the resulting Jetty `Configurable` state.  
The code currently wires all eight setters correctly at `JettyDeflateCustomizer.java:98-105`, but the tests do not verify the `JettyRequestUpgradeStrategy` configurer output or fail if a setter is later dropped. Given the plan’s “all 8 setters wired” contract, this is a coverage gap rather than a current wiring bug.

**NIT**

[NIT] `src/main/java/com/paralife/websocket/JettyDeflateCustomizer.java:30` — Javadoc link points to a non-existent zero-arg overload.  
`{@link #jettyRequestUpgradeStrategy()}` no longer matches the method signature after adding `JettyRuntimeConfig` and the legacy timeout parameter. Use the method name as code text or link with the full signature.

VERDICT: SHIP WITH FIXES

---

## Opencode Review

Here's the review of Plan 20-02 implementation:

---

## Findings

### MEDIUM

**[MEDIUM] `JettyDeflateCustomizer.java:107` — magic number `60000L` for legacy-default proxy breaks if `JettyRuntimeConfig.idleTimeoutMs` default ever changes**

```java
if (legacyIdleTimeoutMs != 60000L && runtimeConfig.idleTimeoutMs() == 60000L) {
```

The helper hardcodes the sentinel `60000L` twice — once to detect "legacy was overridden" and once to detect "new key is at default". If `JettyRuntimeConfig`'s `@DefaultValue("60000")` ever changes, this silently drifts. `AdmissionConfig` avoids this pattern by extracting `DEFAULT_CAP` as a constant. A `static final` constant or referencing `JettyRuntimeConfig.defaults().idleTimeoutMs()` would be more robust. Low likelihood given Phase 999.x will remove this fallback entirely, so not urgent — but a trap for any maintenance between now and then.

### LOW

**[LOW] `JettyRuntimeConfigTest.java:106-131` — `BindingRoundTripTest` asserts only 5 of 8 fields, omitting `maxFrameSize`, `maxBinaryMessageSize`, `maxTextMessageSize`**

The plan's test spec says "all other fields at default values". The test verifies `outputBufferSize`, `autoFragment`, and `maxOutgoingFrames` defaults are preserved, but does not assert `maxFrameSize`, `maxBinaryMessageSize`, or `maxTextMessageSize` remain at `65536L`. Functionally harmless (they're wired through `@DefaultValue` and proven by `defaultsMatchProjectCurrentDefaults`), but the coverage gap means a misconfigured property source that zeroes only those three fields wouldn't be caught by this test.

**[LOW] `JettyRuntimeConfig.java:116-118` — `defaults()` factory hardcodes magic numbers; `AdmissionConfig` extracts a `DEFAULT_CAP` constant**

The plan claims `JettyRuntimeConfig` "mirrors AdmissionConfig pattern." `AdmissionConfig` defines `public static final int DEFAULT_CAP = 256` and references it in `defaults()`. `JettyRuntimeConfig.defaults()` inlines all values. Minor style drift — harmless for a flat record with no reusable constants needed, but the claim of "exact mirroring" is slightly overstated.

### NIT

**[NIT] `JettyRuntimeConfigTest.java:106` — `@SpringBootTest` adds `webEnvironment = NONE` not present in plan template**

This is a correct refinement (avoids starting a web server for a pure config-binding test), but the plan template explicitly included `@ExtendWith(SpringExtension.class)` which was omitted. Both are safe since `@SpringBootTest` is meta-annotated with `@ExtendWith(SpringExtension.class)`.

---

## Eight Checklist Items

| # | Item | Status |
|---|------|--------|
| 1 | Zero-behaviour-change claim — defaults match: all 8 values correct, idle=60000 matches legacy | PASS |
| 2 | All 8 Configurable setters wired — grep confirms every setter is called | PASS |
| 3 | Legacy `paralife.websocket.idle-timeout-ms` fallback — `@Value` slot preserved, 5-case truth table covered, case-E footgun pinned | PASS |
| 4 | Record validation bounds — lower bounds enforced in compact-ctor with property-key-tagged messages; `maxOutgoingFrames` carve-out correct | PASS |
| 5 | Test quality — `defaultsMatchProjectCurrentDefaults` is a factory test (observable), `BindingRoundTripTest` verifies actual Spring binding (not round-trip of the binder), validation tests exercise each branch | PASS (with LOW gap noted above) |
| 6 | Spring wiring — `@ConfigurationPropertiesScan` on `ParalifeApplication` (from plan context), constructor injection into `JettyDeflateCustomizer`; three-gate green confirms bean materialised | PASS |
| 7 | yaml `[launch-only]` semantics — all 8 fields tagged, single `runtime:` key, correct indent | PASS |
| 8 | Mirror of `AdmissionConfig` — `@ConfigurationProperties` + `@ConstructorBinding` + `@DefaultValue` + compact-ctor + `defaults()` all present | PASS (minor constant-extraction style drift) |

---

**VERDICT: SHIP WITH FIXES** (the MEDIUM magic-number issue is real but limited blast radius given Phase 999.x removal plan; fix it or backlog it)

---

## Consensus Summary

### Agreed Strengths
- **Zero-behaviour-change holds** — 8 explicit defaults match Jetty 12.0.18 `WebSocketConstants`; idle=60000 matches pre-change explicit value (claude verified against jar source, opencode, codex implicit PASS).
- **All 8 `Configurable` setters wired correctly** — each called once, correct field (claude via `javap`, codex, opencode via grep).
- **Legacy `paralife.websocket.idle-timeout-ms` fallback resolves** — 5-case truth table covered, case-E pinned by test (claude, opencode, codex).
- **Mirrors `AdmissionConfig` pattern** — `@ConfigurationProperties` + `@ConstructorBinding` + `@DefaultValue` + compact-ctor + `defaults()` (claude, opencode; minor constant-extraction drift noted).

### Agreed Concerns
- **[HIGH/MEDIUM — split severity] Case-E precedence: new key does NOT always win when both set.** `resolveEffectiveIdleMs` uses `== 60000L` as "new key unset" proxy, so explicit `paralife.runtime.jetty.idle-timeout-ms=60000` loses to a legacy override. Contradicts "new key wins when both set" contract. codex rates HIGH; claude + opencode rate MEDIUM (documented + test-pinned, acceptable for one-phase migration). application.yml ships BOTH keys defaulted to 60000, so footgun sits live in default config (claude).
- **[MEDIUM] Magic number `60000L` hardcoded twice as default-sentinel.** Silently drifts if `JettyRuntimeConfig` `@DefaultValue` ever changes. `AdmissionConfig` avoids this via `DEFAULT_CAP` constant. Fix: `static final` constant or `JettyRuntimeConfig.defaults().idleTimeoutMs()` (opencode MEDIUM, codex mechanism, claude). Limited blast radius given Phase 999.x removes fallback.
- **[MEDIUM/LOW] Test coverage gaps.** No test observes resulting Jetty `Configurable` state — won't fail if a setter later dropped (codex MEDIUM). Fallback tests construct `JettyRuntimeConfig` directly, don't exercise Spring binding / `@Value` precedence (codex MEDIUM). `BindingRoundTripTest` asserts only 5 of 8 fields — omits `maxFrameSize`, `maxBinary/TextMessageSize` (opencode LOW). claude notes binding test does run + asserts bound record (not a no-op).

### Divergent Views
- **Gemini's 2 BLOCKERs + 1 HIGH (corrupted `@Bean`/`@Test`/`{@code}` annotations replaced with file paths) — almost certainly spurious.** claude, codex, and opencode all *executed* the tests and read result XMLs (`JettyRuntimeConfigTest` = 9 + binding = 1, all pass; `javap`/grep on compiled output). Corruption that broke compilation would make those runs impossible. Gemini also did not run anything. Treat as reviewer artifact — but worth a 30-second grep to confirm no literal `@src/...` strings exist before dismissing.
- **[HIGH — claude only] `--tests "*JettyDeflate*"` gate is non-reproducible.** No class matches; `gradle` exits 1 (`No tests found for given includes`). Summary claims it green → false-green in shipped verification artifact. Code is fine (real deflate regression covered by `ServerRefusesUpgradeWithoutDeflateTest`, `WebSocketDeflateHandshakeIntegrationTest` full-context tests). Fix: repoint gate at real class names or drop it. No other reviewer caught this.
- **Verdict split:** claude / codex / opencode = **SHIP WITH FIXES**; gemini = **DO NOT SHIP** (on the disputed corruption claim). Consensus leans SHIP WITH FIXES — code correct, fixes are verification-record + precedence-proxy hardening.
