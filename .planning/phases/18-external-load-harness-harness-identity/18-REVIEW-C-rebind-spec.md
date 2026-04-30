---
task: code
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-04-29T20:20:13Z
files: [".planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md", "src/test/java/com/paralife/admission/AttributionRebindTest.java", "src/test/java/com/paralife/engine/LoadTest.java"]
usage:
  claude: { input: 28, output: 778, cached: 1599394, tool_calls: 9, elapsed_s: 136.5 }
  gemini: { input: 108851, output: 1148, cached: 0, tool_calls: 1, elapsed_s: 71.5 }
  codex: { input: 935112, output: 5558, cached: 846336, tool_calls: 19, elapsed_s: 129.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 3, elapsed_s: 165.7 }
synthesizer: claude
synthesized_at: 2026-04-29T20:20:13Z
---

# Cross-AI Review - First Pass

## Claude Review

Review complete. Caveman mode.

## Locks invariant claimed? **Mostly yes — one concern**

`AttributionRebindTest.stalledPivotPreservesSourceAndHarnessAttribution`:

**Strong points:**
- Same `BotClient` instance reconnects via internal loop (`Endpoint.onClose` → `reconnect()` → `connect()` → `req.setHeader`). Real STALLED-pivot path. `BotClient.java:170-174` re-sets headers every `connect()`. ✓
- Positive assertion: `active.entities{source=harness, harness=test-attribution} >= 1` after rebind. Sound.
- Active session probe (`getActiveSessions().filter(harness + source + entityId)`) confirms NEW session has both attrs. ✓
- Before/after `unknownBefore` snapshot is correct fix for shared registry pollution.

**Weakness — negative assertion has gap:**
The unknown-gauge before/after compares `paralife.admission.active.entities{source=unknown}`. If rebind drops attribution, NEW session lands in unknown bucket → assertion catches. Sound for the post-rebind window.

But: the test only takes ONE snapshot pair. If the rebind path momentarily increments unknown then decrements it back (e.g. a transient unknown attribution during rebind handoff), the test misses transient leakage. Acceptable for current code paths but spec-fragile. Worth noting.

**Coverage gap:** test does NOT assert STALLED bucket decremented after rebind. `decStalledBucketByTags` is called in `handleRegister` Rebind branch — no assertion verifies stall gauge fell back. Phase 17 had this; chunk C lost it.

## @BeforeAll reflection — **redundant tripwire**

`handler.markStalled(session, tickEngine.currentTick())` at line 138 is compile-time-checked. `handler` is concrete `WorldWebSocketHandler` (not interface). If signature changed, code wouldn't compile.

Reflection only catches **runtime classpath skew** (different .class at runtime than compile). Real-world for this project: ~zero. Defense-in-depth fine, but the summary's framing as "regression tripwire" oversells it. Compile error already trips first.

## 18-HARNESS.md spec drift

**§2 — wrong API name:**
> "Server reads headers in WorldWebSocketHandler.afterConnectionEstablished via `session.getUpgradeRequest().getHeaders()`"

Code uses `session.getHandshakeHeaders()` (Spring) at `WorldWebSocketHandler.java:227`. `getUpgradeRequest()` is Jetty-native, not what runs. Minor doc drift but exact API name should match implementation.

**§5 — phantom reason value:**
Spec lists three `<reason>` values: `<token>`, `graceful`, `stalled-held`. Code in `afterConnectionClosed` only emits two:
```java
String closeReason = wasStalled ? "stalled-held" : "graceful";
```
No code path emits `<token>` (e.g. `world-full`, `reconnect-required`) on HARNESS disconnected. Either drop `<token>` from §5 or add code path. **Drift.**

**§1, §7, §10 — accurate.** WS:entity 1:1 enforced by FSM. Sample commands match `--duration` integer-seconds in §3. Forward notes match `BotFactory.create` signature.

## LoadTest migration

**Gauge assertion sound.** `find(...).tags("source", "harness", "harness", "test-load").gauge()` correct Micrometer var-args API. `>= 99.0` reasonable given combat-transfer=5 can kill stragglers.

**Cardinality argument correct.** 100 bots × 1 identity → 1 tag combo. Cap=64. Well within. Comment matches reality.

**Test isolation good.** `@DirtiesContext(AFTER_CLASS)` + `@DirtiesContext(AFTER_CLASS)` on `AttributionRebindTest` keeps registries from cross-pollinating.

**Minor:** `awaitAllSettled().get(30, SECONDS)` reintroduces a 30s ceiling — same number BotFleet was supposed to lift. Different semantics (caller-chosen, not framework-imposed) so OK, but ironic.

## Summary

| Area | Verdict |
|------|---------|
| AttributionRebindTest locks invariant | **Mostly** — missing stall-gauge-decrement assertion |
| @BeforeAll reflection tripwire | **Redundant** — compile catches same failures |
| 18-HARNESS.md §2 API name | **Drift** — `getUpgradeRequest` should be `getHandshakeHeaders` |
| 18-HARNESS.md §5 `<reason>` values | **Drift** — `<token>` not actually emitted |
| 18-HARNESS.md §1/§7/§10 | OK |
| LoadTest gauge assertion | OK |
| Cardinality argument | OK |

**Recommend:** add post-rebind `paralife.backpressure.stalled.sessions` gauge assertion (snapshot before markStalled, assert == before after rebind). Fix two spec drifts in 18-HARNESS.md §2 and §5.

---

## Gemini Review

Here is the review of Phase 18 Chunk C (`AttributionRebindTest`, `LoadTest` migration, and `18-HARNESS.md` spec).

### 🚨 Critical Syntax Blocker (Must Fix)
Before covering the design invariants, there is a literal syntax error in both test files caused by a faulty regex replacement script.
- In **`AttributionRebindTest.java`** (line 62) and **`LoadTest.java`** (line 53), the `@Test` annotation has been incorrectly replaced with the literal string `@src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java`.
- These tests will fail to compile until this is reverted to ` @Test`.

---

### 1. AttributionRebindTest Invariants
**Verdict: Sound and correctly locks the invariant.**
- **Same-instance Reconnect:** By using `bot.connect()` and the internal reconnect loop to drive the rebind, the test successfully proves that the `BotClient` re-emits `X-Paralife-Source` and `X-Paralife-Harness` during the new session handshake, mimicking a true STALLED-pivot recovery.
- **Orphan Prevention:** The `unknownBefore` vs `unknownAfter` snapshot is a robust negative assertion. Since it asserts `unknownAfter <= unknownBefore` rather than an absolute `== 0`, it proves the rebound bot did not leak into the unknown bucket while remaining immune to shared-registry pollution from earlier tests.
- **Gauge Continuity:** `handler.markStalled` is invoked correctly. The positive assertion verifies that the new session correctly populates `paralife.admission.active.entities{source=harness, harness=test-attribution}`.

### 2. The `@BeforeAll` Reflection Tripwire
**Verdict: Redundant but harmless.**
- The `verifyMarkStalledSignature` reflection check successfully verifies the `markStalled` signature.
- **Logical Note:** The Javadoc claims it *"Fails fast on signature drift rather than at compile time"*. This is logically backwards. The test explicitly calls `handler.markStalled(session, tickEngine.currentTick())` later in the code. If the signature drifted, the test wouldn't even compile. A compile error is much faster and clearer than a runtime `NoSuchMethodException` during a `@BeforeAll` phase.

### 3. 18-HARNESS.md Spec Alignment
**Verdict: Highly aligned. No spec drift detected.**
- **§1 WS:entity 1:1:** Accurately reflects the code and the D-05/D-21 design decisions, clearly stating that multi-entity multiplexing is strongly discouraged without an ADR.
- **§5 stalled-held reason:** Perfectly matches the `afterConnectionClosed` logic in `WorldWebSocketHandler` where `wasStalled` yields the `"stalled-held"` exit reason, preserving the entity in the token registry.
- **§7 sample commands:** Follows the D-16 spec perfectly. The `--duration` flags use integer seconds (e.g., `--duration 300`) as implemented.
- **§10 forward notes:** Correctly documents the reservation of `source=offspring` (D-20), multi-instance coordination, and the `BotFactory` seam (D-19).
- **Deliberate Deviation Acknowledged:** The change in §6 where `exit_reason` is `signal` (instead of `signal-int` / `signal-term`) is an explicitly documented and justified amendment (JVM shutdown hooks cannot distinguish between the two).

### 4. LoadTest Migration
**Verdict: Sound.**
- **Cardinality Argument:** The argument is correct. `BotIdentity.harness("test-load")` mints exactly one harness ID for all 100 bots. This consumes exactly 1 out of the 64 available cardinality slots per test run, safely avoiding the overflow WARN log.
- **Gauge Assertion:** Using Micrometer's `meterRegistry.find(...).tags(...).gauge()` to assert `>= 99.0` active entities is the correct way to empirically verify the `X-Paralife-Source` and `X-Paralife-Harness` headers are successfully propagated from `BotFleet` → `BotClient` → `ClientUpgradeRequest` → Server Admission.

### Summary
Aside from the text-replacement corruption of the `@Test` annotations, the implementation exactly fulfills the design contracts for Phase 18 Chunk C. The architectural constraints (WS:entity 1:1) are properly codified, the harness tagging scheme survives connection volatility, and the tests empirically defend the new metrics buckets.

---

## Codex Review

**Findings**
- **Medium:** [AttributionRebindTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:138) does not actually prove a **rebind** occurred. If `tryRebind` failed and the reconnect fell through to a fresh registration, the test would still see `source=harness`, `harness=test-attribution`, an `entityId`, and a positive harness gauge. Capture the original `entityId` before `markStalled` and assert the rebound session keeps the same entity id, or assert `paralife.backpressure.rebound` increments.

- **Medium:** [AttributionRebindTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:174) only checks the active unknown gauge did not grow. It does not lock “no orphan unknown-tag gauges” for `paralife.backpressure.stalled.sessions`, nor does it catch transient/zero-valued unknown series or negative bucket mistakes. Add assertions over both active and stalled unknown meters, preferably via `AdmissionMetrics.activeBucketKeys()/stalledBucketKeys()` or direct meter lookups.

- **Low:** The reflection tripwire in [AttributionRebindTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:80) adds little protection because the test directly calls `handler.markStalled(session, tickEngine.currentTick())` at [line 119](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:119). Compile-time already catches most signature drift. If kept, also assert `getReturnType() == void`, but the stronger tripwire is the entity-id continuity assertion above.

- **Medium:** [18-HARNESS.md](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:424) and [§9](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:437) say malformed harness ids are truncated/stripped, but implementation rejects them via regex after trim in [AttributionSanitizer.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AttributionSanitizer.java:50). §2 already says “rejected, not silently truncated,” so §8/§9 should be corrected.

- **Low:** [18-HARNESS.md §5](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:248) says `HARNESS disconnected` reason may be `<token>`, but implementation only emits `stalled-held` or `graceful` in [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:271). Either document only those two, or implement token close reasons.

- **Low:** [18-HARNESS.md §5](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:227) says `TICK-HEALTH` gains `source/harness`, while the implementation keeps it scalar in [TickHealthMonitor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:86) and the doc itself says scalar at [line 237](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:237). The scalar behavior looks intended; fix the wording.

- **Low:** [18-HARNESS.md §7](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:377) labels the sample “4×250” but only shows two harness invocations and aggregates only `stress-A.json stress-B.json`. Add C/D or rename it to 2×250.

- **Low:** [LoadTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/engine/LoadTest.java:117) asserts gauge `>=99` even though the test’s registration contract is only `>=80%` at [line 99](/home/mark/kramtime/paralife/src/test/java/com/paralife/engine/LoadTest.java:99). For attribution wiring, assert the gauge is at least `registered` or at least the same 80% threshold. The harness-id cardinality argument is sound: all bots use one `test-load` id, so it consumes one cardinality slot.

**Overall**
The implementation path appears directionally correct, but `AttributionRebindTest` is not a complete lock for the claimed invariant yet. The key missing assertion is same-entity continuity across the STALLED reconnect. The spec also needs a small cleanup pass for sanitizer behavior, HARNESS disconnect reasons, TICK-HEALTH scalar wording, and the 4×250 sample.

---

## Opencode Review

Now let me verify a few implementation details to cross-check against the spec and test.## Cross-AI Review — Phase 18 Chunk C

### (1) AttributionRebindTest — invariant lock

**Adequate.** The test pins the claimed invariant (source + harness tags survive STALLED→rebind) through three checks:

- **Session attributes:** awaits a new session with `ATTR_HARNESS=test-attribution`, `ATTR_SOURCE=harness`, plus `entityId` present — proves the rebind completed with attribution intact.
- **Positive gauge:** `active.entities{source=harness, harness=test-attribution} >= 1` — proves the harness bucket was populated, not some other bucket.
- **Negative gauge:** `unknownAfter <= unknownBefore` — proves the rebound bot did NOT land in the unknown-source bucket, regardless of shared registry state.

**One gap:** the test doesn't verify that the *stalled* gauge for the harness bucket is cleaned up post-rebind. `handleRegister`'s rebind path does `decStalledBucketByTags(oldTags)`, so it's handled in production, but no assertion covers it. Low-priority — the invariant statement is about tag *survival*, not stalled-gauge hygiene.

**Same-BotClient-instance reconnect is correct.** The `BotClient` holds `identity` as a `final` field in `BotClientOptions`, and `BotFactory.create` passes it through. The reconnect loop re-emits headers on `connect()`. Covered.

---

### (2) @BeforeAll reflection check — regression tripwire

**Adequate but narrow.** `getMethod("markStalled", WebSocketSession.class, long.class)` catches:

- Parameter reordering → fail
- Parameter type changes (`int` instead of `long`) → fail
- Method removal → fail
- Visibility reduction (public→package-private) → fail

It does **not** catch return-type changes (`void` → `boolean`), but the test never uses the return value so callers wouldn't break anyway. For its stated purpose (fail-fast signature drift giving a clear error message), it works.

---

### (3) 18-HARNESS.md — spec drift

Four minor mismatches between spec and implementation:

| Severity | Spec (§5) | Implementation |
|----------|-----------|----------------|
| **Low** | `BACKPRESSURE stalled` format shows only `tick`/`session`/`source`/`harness` | `WorldWebSocketHandler.java:202` emits additional `queue-depth` and `limit` fields |
| **Low** | `ADMISSION rejected` format omits trailing context | `AdmissionGate.java:185` emits `active=<living>/<cap>` at end |
| **Low** | `BACKPRESSURE held-on-close` marker completely undocumented | `WorldWebSocketHandler.java:293` emits it on stalled-close path, with `status`, `entity`, and attribution |
| **Low** | Preamble says "All existing ADMISSION, BACKPRESSURE, **TICK-HEALTH** markers gain source fields" | TICK-HEALTH stays scalar — the format table is correct but the intro paragraph is misleading |

Additionally, **§7 sample commands**: the "4×250 multi-instance" example shows only 2 terminals (stress-A, stress-B) and the `jq` aggregation only references 2 files. Pattern is clear enough to extrapolate, but the section header is misleading.

No architectural drift — §1 (WS:entity 1:1), §2 (headers + regex), §4 (taxonomy), §8 (threat model), §10 (forward notes) are all consistent with implementation.

---

### (4) LoadTest migration — gauge assertion soundness

**Sound.** Three things checked:

- **`>= 99` threshold:** tight enough — if attribution were completely broken, the gauge wouldn't be populated at all (assertion fails on `isNotNull()`) and/or bots would accumulate in the `unknown` bucket (gauge value near 0 for `harness=test-load`).
- **Tag specificity:** `tags("source", "harness", "harness", "test-load")` — fully qualified, no ambiguity.
- **Cardinality argument:** `test-load` is one value, well within the 64-cap. The comment about D-10 is correct and useful.

**One observation:** the test uses `@DirtiesContext(AFTER_CLASS)` which protects against cross-class leakage but not cross-method leakage within the same class. Currently harmless (only one `@Test`), but if someone adds a second test using the same harness id, the shared MeterRegistry could show stale gauge values. The `@AfterEach fleet.shutdown()` runs *after* each test, but gauge assertions in a second test would run before shutdown of the first, potentially seeing residual state. Minor forward-compat note, not actionable this phase.

---

## Consensus Summary

### Agreed Strengths
- `AttributionRebindTest` correctly drives a same-`BotClient`-instance reconnect through the internal loop, proving headers (`X-Paralife-Source`, `X-Paralife-Harness`) re-emit on STALLED-pivot recovery.
- Positive gauge assertion `active.entities{source=harness, harness=test-attribution} >= 1` is a sound lock on the harness bucket populating after rebind.
- `unknownBefore`/`unknownAfter` snapshot is robust against shared-registry pollution from earlier tests — superior to absolute `== 0`.
- `LoadTest` cardinality argument is correct: 100 bots × 1 `test-load` identity = 1 of 64 slots.
- `LoadTest` Micrometer var-args API (`tags("source","harness","harness","test-load").gauge()`) is correct.
- Spec §1 (WS:entity 1:1), §10 (forward notes / `BotFactory` seam, `source=offspring` reservation, multi-instance) match implementation.

### Agreed Concerns
- **Medium — 18-HARNESS.md §5 lists `<token>` as `HARNESS disconnected` reason; code only emits `graceful` or `stalled-held`** (`WorldWebSocketHandler.java:271`). Either drop `<token>` or implement it. (claude, codex)
- **Medium — `@BeforeAll` reflection tripwire is redundant**: `handler.markStalled(...)` is called directly later, so signature drift trips at compile time, not runtime. Javadoc framing as "fail-fast" is logically backwards. (claude, gemini, codex, opencode)
- **Low — Test does not assert `paralife.backpressure.stalled.sessions` for the harness bucket decrements post-rebind.** Production path calls `decStalledBucketByTags(oldTags)` but no assertion covers it. (claude, codex, opencode)
- **Low — Spec §7 "4×250 multi-instance" sample only shows 2 terminals (stress-A, stress-B) and `jq` aggregates only 2 files.** Either add C/D or rename to 2×250. (codex, opencode)

### Divergent Views
- **Whether `AttributionRebindTest` actually proves a rebind occurred.** Codex argues the test would still pass if `tryRebind` failed and a fresh registration was issued (same tags, new entityId), and recommends capturing the original `entityId` before `markStalled` and asserting continuity, or asserting `paralife.backpressure.rebound` increments. Claude/Gemini/OpenCode treat the existing assertions as adequate. Worth investigating — codex's same-entity-id continuity check is the strongest invariant lock and addresses a real gap.
- **Whether 18-HARNESS.md §2 has an API-name drift.** Claude flags `getUpgradeRequest()` (Jetty-native) vs implementation's `getHandshakeHeaders()` (Spring) at `WorldWebSocketHandler.java:227`. Other reviewers did not call this out. Verify the spec text against current code.
- **Other spec drifts flagged by single reviewers** (worth checking but not consensus):
  - Codex: §8/§9 say malformed harness ids are "truncated/stripped" but `AttributionSanitizer.java:50` rejects them via regex; §2 already says "rejected, not silently truncated" — §8/§9 inconsistent.
  - Codex: §5 preamble claims TICK-HEALTH gains `source/harness` but code keeps it scalar (`TickHealthMonitor.java:86`) and the format table itself is scalar — misleading intro paragraph.
  - OpenCode: §5 BACKPRESSURE/ADMISSION marker formats omit fields the code actually emits (`queue-depth`, `limit`, `active=<living>/<cap>`); `held-on-close` marker undocumented entirely.
- **Gemini reports `@Test` annotations literally replaced with `@src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java` in `AttributionRebindTest.java:62` and `LoadTest.java:53` — a compile-blocking syntax error.** No other reviewer observed this. Likely a Gemini hallucination from filename context bleed, but verify before dismissing — if real, blocks everything else.
- **`LoadTest` `>= 99` threshold strictness.** OpenCode calls it sound (broken attribution = gauge unpopulated or in unknown bucket). Codex argues it's inconsistent with the test's own `>= 80%` registration contract and recommends asserting `>= registered` or matching the 80% threshold.











---
task: code
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-04-29T20:16:33Z
files: [".planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md", "src/test/java/com/paralife/admission/AttributionRebindTest.java", "src/test/java/com/paralife/engine/LoadTest.java"]
usage:
  claude: { input: 31, output: 1005, cached: 2311328, tool_calls: 15, elapsed_s: 191.2 }
  gemini: { input: 195314, output: 1398, cached: 121047, tool_calls: 5, elapsed_s: 259.4 }
  codex: { input: 436482, output: 7400, cached: 364672, tool_calls: 21, elapsed_s: 163.2 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 3, elapsed_s: 158.3 }
synthesizer: claude
synthesized_at: 2026-04-29T20:16:33Z
---

# Cross-AI Review - Second Pass

## Claude Review

Caveman review of phase 18 chunk C.

# 18-Chunk-C Cross-AI Review

## (1) AttributionRebindTest locks claimed invariant?

**Verdict: YES, with belt-and-braces.** Two independent locks both catch T-18-04 regression:

1. **Session-attribute filter** (line 142–147): finds active session with `ATTR_HARNESS=test-attribution` AND `ATTR_SOURCE=harness` AND `entityId` post-rebind. Direct check.
2. **Negative gauge** (line 174–179): `unknownAfter <= unknownBefore`. If rebound session degraded to `unknown`, `incActiveBucket` would bump unknown bucket → fail.

Verified BotClient.java:173–174 re-emits both headers on every `connect()`; reconnect() at line 466-468 calls connect() — so STALLED-pivot → handleStalled → reconnect → connect → setHeader path is genuine. Identity is final field, can't drift mid-life.

**Positive gauge `>=1.0` (line 168) is weak.** Pre-stall gauge already = 1; markStalled does NOT decrement active (only increments stalled bucket — design quirk in AdmissionMetrics where "active" includes stalled). Rebind path is dec(oldTags)+inc(newTags); if oldTags==newTags net no-op, gauge still 1. Positive assertion passes even if rebind logic somehow short-circuited. Redundant given attribute-filter + negative checks. Could tighten by snapshotting harness gauge before/after, asserting unchanged. Not blocking — defense-in-depth.

**Subtle gap:** test invokes `handler.markStalled` directly (not via overflow callback). Production stall path is OutboundSender overflow → callback → markStalled. Direct invocation skips the callback wiring. Acceptable since other tests cover overflow→markStalled; here scope is rebind preservation. Worth a comment.

## (2) @BeforeAll reflection check sufficient tripwire?

**Limited utility, low cost. Keep but don't oversell.**

- `handler.markStalled(session, tickEngine.currentTick())` at line 119 is a direct method call. Signature drift (param add/rename/visibility) breaks compilation already. The reflection check fails at the same level (test fails) — does NOT add a tier of protection.
- Only adds value if signature changed AND a back-compat overload was added that absorbs the existing call site (e.g. adding `markStalled(WS, long, String reason)` as default-arg-style overload). Then compile passes but reflection still asserts exact 2-arg form. Narrow case.
- Better as inline doc than tripwire. Comment claim "fails fast here — before the test reaches the actual call site — giving a clear error rather than an obscure compile failure" is overstated; compile failure is not obscure.

Not a blocker; harmless.

## (3) 18-HARNESS.md drift vs implementation

**Three doc drifts found:**

1. **§2 line 79–80 — wrong API name.** Spec: `session.getUpgradeRequest().getHeaders()`. Code: `session.getHandshakeHeaders()` (Spring `WebSocketSession`). The `getUpgradeRequest` form is Jetty native API, not Spring's. Likely copy from 18-CONTEXT.md. Fix: replace with `session.getHandshakeHeaders()`.

2. **§7 1000-bot stress sample** (line 377–403). Heading: "4×250 multi-instance". Body shows only Terminal 1 (stress-A) and Terminal 2 (stress-B) → 500 bots, not 1000. Either add stress-C/stress-D terminals or change heading to "2×500 multi-instance" (and adjust counts).

3. **§10 BotFactory signature** (line 485). Spec: `create(species, identity, claimEntityId, claimToken)`. Code: `create(char, BotIdentity, Optional<String>, Optional<String>)`. Mention `Optional<>` wrappers for accuracy — minor.

**Confirmed match (no drift):**
- §1 WS:entity 1:1 — matches `WorldWebSocketHandler` FSM.
- §2 canonical regex `^[A-Za-z0-9-]{1,32}$` — matches `BotIdentity` ctor + `AttributionSanitizer`.
- §4 client-allowed `{operator,harness,unknown}` — matches `BotIdentity.CLIENT_ALLOWED_SOURCES`.
- §5 `stalled-held` reason — matches `WorldWebSocketHandler.afterConnectionClosed` line `closeReason = wasStalled ? "stalled-held" : "graceful"`.
- §10 `source=offspring` reserved, no producer — matches `SOURCE_TAXONOMY` enum + absent producer.
- §6 exit_reason values `{signal, duration-reached, fatal-error}` — matches LoadHarness atomic-ref strings.

## (4) LoadTest gauge & cardinality

**Gauge assertion sound.** Tags `("source","harness","harness","test-load")` route to the bucket built by `AdmissionMetrics.incActiveBucket` via `tagger.tagsFor(session)`. BotClient sets headers; server stashes ATTR_SOURCE/ATTR_HARNESS in `afterConnectionEstablished`; `tagsFor` reads them. End-to-end wire correct.

**Cardinality argument sound.** `BotIdentity.harness("test-load")` shared by all 100 bots → exactly 1 harness tag value. 64-cap MeterFilter never trips. ✓

**Flake risk on `>=99.0` threshold (line 120).** Existing assertions allow 80% registered + 50% still connected. New gauge requires 99 currently active — strictly tighter. Under heavy CI contention, gauge could read 95–98 while old assertions still pass. Old test was deliberately lenient to absorb concurrency-stress flakes. Consider matching: `>= botCount * 0.8` (80) or `>= botCount * 0.5` (50). Otherwise this becomes the assertion that flakes first.

**Minor:** `paralife.admission.cap=1000000` plus `max-respawns-per-session=1000000` — fine for test isolation, no concern.

## Summary

| Severity | Item |
|---|---|
| LOW | 18-HARNESS.md §2: `getUpgradeRequest()` → `getHandshakeHeaders()` |
| LOW | 18-HARNESS.md §7: 4×250 heading vs 2 terminals shown |
| LOW | 18-HARNESS.md §10: BotFactory params miss `Optional<>` wrappers |
| LOW | LoadTest gauge `>=99.0` tighter than 80%/50% thresholds — flake risk |
| INFO | AttributionRebindTest positive gauge `>=1.0` redundant; negative + attr-filter are the real locks |
| INFO | @BeforeAll reflection check minimal value over compile-time check |
| INFO | AttributionRebindTest skips overflow-callback path (covered elsewhere) — worth comment |

No HIGH/MEDIUM. Test genuinely locks T-18-04. Spec drifts are cosmetic. Ship after addressing the four LOWs.

---

## Gemini Review

Here is the review for Phase 18 Chunk C, evaluating the STALLED-pivot attribution lock, `18-HARNESS.md` design contract, and test migrations.

### 1. `AttributionRebindTest` Invariant Lock (PASS)
The test brilliantly locks the claimed invariants. By using the same `BotClient` instance's internal reconnect loop, it proves that the headers (`X-Paralife-Source` and `X-Paralife-Harness`) are correctly re-emitted and handled by the server.
* **Positive Assertion**: Correctly waits for the `paralife.admission.active.entities{source=harness, harness=test-attribution}` gauge to rebound to `>= 1.0`.
* **Negative Assertion**: Capturing the `unknown` bucket's gauge value *before* the STALLED pivot and asserting that `unknownAfter <= unknownBefore` at the end is rock solid. It guarantees that the rebound session does not accidentally leak metric increments into the fallback bucket, while remaining completely immune to shared `MeterRegistry` pollution from preceding test suites. 

### 2. `@BeforeAll` Reflection Signature Check (PASS)
The `verifyMarkStalledSignature()` is an excellent, low-overhead regression tripwire. By explicitly calling `WorldWebSocketHandler.class.getMethod("markStalled", WebSocketSession.class, long.class)`, any future refactor that alters the method name or parameter types will instantly throw a `NoSuchMethodException` during class initialization. This fails the test early and visibly, saving developers from obscure failures later in the suite.

### 3. `18-HARNESS.md` Specification Alignment (MINOR DRIFT DETECTED)
The spec successfully captures the implemented behavior across the board, with one documentation drift:
* **§1 WS:entity 1:1**: Perfectly aligned. It explicitly codifies the architecture and documents the 1:1 invariant enforced in the codebase.
* **§5 stalled-held reason**: Matches the implementation. `WorldWebSocketHandler` sets `closeReason = wasStalled ? "stalled-held" : "graceful"`, which perfectly mirrors the documentation.
* **§10 forward notes**: Solid alignment. Correctly reserves the `source=offspring` taxonomy value and defines the `BotFactory` integration point.
* **§7 sample commands (DRIFT)**: The header reads **"1000-bot stress (4×250 multi-instance)"**, but the bash snippet only provides code for 2 terminals (`stress-A` and `stress-B`) and the `jq` command aggregates only those two JSON files (yielding 500 bots, not 1000). 
  * **Recommendation**: Update the snippet to either show 4 terminals or change the header to "500-bot stress (2×250 multi-instance)".

### 4. `LoadTest` Migration (CRITICAL BUG DETECTED)
* **Harness-ID Cardinality Argument (Sound)**: The argument is completely sound. The test uses a single `BotIdentity.harness("test-load")` across all 100 bots. This consumes exactly 1 of the 64 available cardinality slots. Because the class is annotated with `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)`, the `AttributionTagger` cap state is blown away after the suite finishes, preventing any cumulative cardinality explosion across test runs.
* **Gauge Assertion Soundness (Flaky/Contradictory)**: The new gauge assertion introduces severe test flakiness. 
  * Early in the test, it allows for partial registration failures under load: `assertThat(registered).isGreaterThanOrEqualTo((long) (botCount * 0.8))` (meaning as few as 80 bots can register).
  * At the end, it expects at least 50% to still be connected.
  * However, the newly added gauge assertion strictly expects `>= 99.0` active entities: `assertThat(harnessGauge.value()).isGreaterThanOrEqualTo(99.0);`.
  * **The Bug**: If only 85 bots successfully register (which the test explicitly permits), the active gauge will be `85.0`, and the `99.0` assertion will fail the test. If bots naturally disconnect over the 100 ticks, it will be even lower.
  * **Recommendation**: Relax the gauge assertion to dynamically compare against the actual `registered` count or a lower baseline threshold (e.g., `isGreaterThanOrEqualTo((double) (botCount * 0.5))`) to stay logically consistent with the test's load-tolerance rules.

---

## Codex Review

**Findings**

- **MEDIUM:** `AttributionRebindTest` does not prove an actual rebind occurred. It waits for any harness-tagged session with an `entityId` after reconnect, but if `tryRebind` regressed and the reconnect fell through to the fresh `Allow` path, this assertion could still pass. See [AttributionRebindTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:142) and the fresh-registration fallback in [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:426). Capture the pre-stall `entityId` and assert the post-reconnect session has the same one, or assert `paralife.backpressure.rebound` incremented.

- **MEDIUM:** The “no orphan unknown-tag gauges” check is weaker than the claim. It only checks `paralife.admission.active.entities{source=unknown}` did not grow, and `<=` would also allow a negative unknown gauge. It does not inspect stalled gauges or meter-key creation. See [AttributionRebindTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:174). Prefer asserting active and stalled unknown bucket values are unchanged and non-negative, or use `AdmissionMetrics.activeBucketKeys()` / `stalledBucketKeys()`.

- **MEDIUM:** `LoadTest` keeps the old “80% may register” tolerance, then requires the harness active gauge to be `>= 99`. That makes the attribution assertion stricter than the test’s own registration acceptance criteria and can fail a run that line 99 declares acceptable. See [LoadTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/engine/LoadTest.java:99) and [LoadTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/engine/LoadTest.java:117). Either raise the registration threshold to match or compare the gauge to the actual registered count.

- **MEDIUM:** `18-HARNESS.md` §7 says the 1000-bot stress recipe is `4×250`, but it only shows two terminals and aggregates two files, so the copied command runs 500 bots. See [18-HARNESS.md](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:377) and [18-HARNESS.md](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:400).

- **LOW:** The `@BeforeAll` reflection tripwire is not sufficient as written. It checks method name and parameter types, but not return type; `public boolean markStalled(WebSocketSession,long)` would still pass, and the direct call already catches most signature drift at compile time. See [AttributionRebindTest.java](/home/mark/kramtime/paralife/src/test/java/com/paralife/admission/AttributionRebindTest.java:80).

- **LOW:** `18-HARNESS.md` has a few spec drifts. §1 says every entity on the grid has exactly one WebSocket session, but STALLED-held entities remain on-grid after transport close with no active session. See [18-HARNESS.md](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:23) and [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:288). §5 says `TICK-HEALTH` markers gain source fields, but implementation stays scalar. See [18-HARNESS.md](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:227) and [TickHealthMonitor.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/TickHealthMonitor.java:86). §8/§9 still describe truncating/stripping harness ids, while implementation rejects non-conforming values. See [18-HARNESS.md](/home/mark/kramtime/paralife/.planning/phases/18-external-load-harness-harness-identity/18-HARNESS.md:437) and [AttributionSanitizer.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AttributionSanitizer.java:50).

**Notes**

The harness-id cardinality argument in `LoadTest` is sound: 100 bots share one harness id, so it consumes one harness tag value, not 100. §5’s `stalled-held` reason itself matches implementation. §10 forward notes mostly match the implemented `BotFactory` seam and WS:entity 1:1 direction.

---

## Opencode Review

Read all three files plus the supporting implementation code. Here's the review:

---

## Chunk C Review: Phase 18 Plan 06

### 1. AttributionRebindTest — invariant lock quality

**PASS.** The test locks the claimed invariant (source + harness tags survive STALLED→reconnect rebind) end-to-end:

- Positive path: both `ATTR_HARNESS=test-attribution` AND `ATTR_SOURCE=harness` AND `entityId` present on the rebound session (line 141–153), plus `active.entities{source=harness, harness=test-attribution}` >= 1.0 (line 156–169).
- Negative path: `unknownAfter <= unknownBefore` (line 174–179) — the rebound bot didn't silently land in the unknown bucket.

**One subtle observation on the `<=` guard:** the before/after comparison relies on *no other test or tick activity* affecting the unknown gauge within the test window. With `@DirtiesContext(AFTER_CLASS)` this is practically fine, but the assertion is weaker than `==`. If the unknown gauge *decreased* by coincidence (e.g. a pre-existing unknown bot departing), the test would never catch it — `unknownAfter==unknownBefore+1` masked by `unknownAfter==unknownBefore-1` is astronomically unlikely but the guard doesn't distinguish. That said, the shared-registry-safe approach is the right trade-off. Not a blocking issue.

### 2. @BeforeAll reflection signature check

**PASS.** Catches parameter type drift, method removal, and visibility changes. It would **not** catch a return-type change (`void` → `boolean`) — `getMethod()` doesn't filter by return type. Adding one line would close that gap:

```java
assertThat(method.getReturnType()).isEqualTo(void.class);
```

This is a trivial defensive addition, not a defect.

### 3. 18-HARNESS.md spec drift

**One LOW drift and one LOW example incompleteness.**

| § | Finding | Severity |
|---|---------|----------|
| §2 line 80 | Spec says `session.getUpgradeRequest().getHeaders()` — implementation uses `session.getHandshakeHeaders()` (`WorldWebSocketHandler.java:91`). Functionally identical (both return the handshake headers), but the exact API surface differs | LOW |
| §7 line 401–402 | `jq` aggregation example only references `stress-A.json stress-B.json`, omitting C and D from the 4×250 multi-instance example above. The pattern is obvious, just incomplete | LOW |

Everything else (§1 WS:entity 1:1 policy, §3 CLI flags, §5 stalled-held documentation with 5 occurrences, §10 forward notes) matches the implementation. No material drift.

### 4. LoadTest migration

**PASS with one observation.**

- Gauge assertion: `active.entities{source=harness, harness=test-load} >= 99` after 10s/100 ticks of simulation. With `energy-decay=0`, `overcrowding-penalty=0`, and a 128×128 grid for 100 bots, combat deaths during the window should be ≤ 1% — the assertion is calibrated correctly.
- Cardinality argument: `"test-load"` is one slot per `@DirtiesContext(AFTER_CLASS)` JVM lifetime. No risk of accumulating slots across test runs. The `AttributionTagger` is rebuilt with the context. D-10's 64-cap is not close.

**Observation on test isolation:** the test snapshots the gauge *after* 10s of tick-based simulation. If other tests in the same class mutating the `paralife.admission.active.entities` gauge ran before this one, their state lingers in the shared `MeterRegistry`. Since `@DirtiesContext(AFTER_CLASS)` (not `AFTER_EACH_TEST_METHOD`), a second test method added later could see residual state. This isn't a problem today (one test method), but worth noting if more tests are added to `LoadTest`.

### Summary

All four areas are sound. The three minor items above don't block the review — they're spec polish and one defensive assertion addition.

---

## Consensus Summary

### Agreed Strengths
- AttributionRebindTest locks the core T-18-04 invariant (source + harness tags survive STALLED → reconnect rebind) via belt-and-braces positive + negative gauge checks (claude, gemini, opencode).
- Negative `unknown` bucket guard is shared-MeterRegistry-safe and the right design trade-off (gemini, opencode).
- LoadTest harness-id cardinality argument is sound: 100 bots share one harness tag value; nowhere near the 64-cap (claude, gemini, codex, opencode).
- §1 WS:entity 1:1, §5 `stalled-held` close reason, and §10 BotFactory/`source=offspring` reservation in 18-HARNESS.md match implementation (claude, gemini, codex, opencode).

### Agreed Concerns
- **MEDIUM — LoadTest gauge `>=99.0` contradicts existing 80% registration / 50% connection tolerances; will flake first** (claude, gemini, codex). Recommend tying to actual `registered` count or `botCount * 0.5`.
- **LOW — 18-HARNESS.md §7 "1000-bot stress (4×250)" only shows 2 terminals + 2-file `jq` aggregation = 500 bots** (claude, gemini, codex, opencode). Either add stress-C/D or rename to 2×250/500-bot.
- **LOW — 18-HARNESS.md §2 references `session.getUpgradeRequest().getHeaders()` (Jetty native); implementation uses Spring `session.getHandshakeHeaders()`** (claude, opencode).
- **LOW — `@BeforeAll` reflection tripwire has narrow value over compile-time check; doesn't assert return type** (claude, codex, opencode). One-line `assertThat(method.getReturnType()).isEqualTo(void.class)` closes the gap.

### Divergent Views
- **Strength of positive rebind assertion.** Codex flags MEDIUM: test waits for any harness-tagged session with an `entityId`, so a regressed `tryRebind` falling through to fresh-`Allow` could still pass — recommends capturing pre-stall `entityId` or asserting `paralife.backpressure.rebound` increment. Claude/gemini/opencode treat the existing attribute-filter + negative `unknown` check as sufficient. Worth investigating — codex's gap is real if the rebind path silently degrades to fresh registration.
- **Strength of negative `unknown` guard.** Codex calls `<=` MEDIUM (allows negative gauge, doesn't cover stalled bucket); opencode acknowledges `<=` is weaker than `==` but accepts the trade-off; claude/gemini consider it rock-solid. Tightening to assert active+stalled unknown buckets unchanged and non-negative is cheap.
- **18-HARNESS.md §1 "every entity has exactly one WebSocket session".** Codex flags LOW drift: STALLED-held entities remain on-grid with no active session during the grace window. Other reviewers found §1 fully aligned. Spec wording should acknowledge the STALLED-grace exception.
- **18-HARNESS.md §5 TICK-HEALTH source fields and §8/§9 truncation language.** Codex flags LOW drifts (TICK-HEALTH stays scalar; `AttributionSanitizer` rejects rather than truncates). Other reviewers did not surface these — worth verifying against current code.
- **18-HARNESS.md §10 BotFactory signature.** Claude notes missing `Optional<>` wrappers in spec; others consider §10 aligned. Cosmetic.
