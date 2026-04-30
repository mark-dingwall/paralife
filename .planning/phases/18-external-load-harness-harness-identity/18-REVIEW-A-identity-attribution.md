---
task: code
reviewers_succeeded: ["claude", "codex", "opencode"]
reviewers_failed: ["gemini"]
reviewed_at: 2026-04-28T23:07:40Z
files: ["src/main/java/com/paralife/bot/BotIdentity.java", "src/main/java/com/paralife/bot/BotClient.java", "src/main/java/com/paralife/bot/BotClientOptions.java", "src/main/java/com/paralife/admission/AdmissionGate.java", "src/main/java/com/paralife/admission/AdmissionConfig.java", "src/main/java/com/paralife/admission/AdmissionBeansConfig.java", "src/main/java/com/paralife/admission/AdmissionMetrics.java", "src/main/java/com/paralife/admission/AttributionSanitizer.java", "src/main/java/com/paralife/admission/AttributionTagger.java", "src/main/java/com/paralife/websocket/WorldWebSocketHandler.java", "src/main/java/com/paralife/websocket/SessionRegistry.java", "src/main/java/com/paralife/engine/ActionResolver.java", "src/main/resources/application.yml", "src/test/java/com/paralife/bot/BotIdentityTest.java", "src/test/java/com/paralife/bot/BotClientHandshakeHeaderTest.java", "src/test/java/com/paralife/admission/AdmissionGateTest.java", "src/test/java/com/paralife/admission/AdmissionMetricsTest.java", "src/test/java/com/paralife/admission/AdmissionMetricsLifecycleTest.java", "src/test/java/com/paralife/admission/AttributionSanitizerTest.java", "src/test/java/com/paralife/admission/AttributionTaggerTest.java", "src/test/java/com/paralife/admission/AttributionTagTest.java", "src/test/java/com/paralife/admission/CardinalityCapTest.java", "src/test/java/com/paralife/admission/AdmissionLogMarkerTest.java", "src/test/java/com/paralife/websocket/WorldWebSocketHandlerHandshakeHeaderTest.java", "src/test/java/com/paralife/websocket/HarnessLogMarkerTest.java", "src/test/java/com/paralife/engine/TickHealthMonitorScalarTest.java"]
usage:
  claude: { input: 5, output: 9821, cached: 20225, tool_calls: 0, elapsed_s: 163.3 }
  gemini: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 625.2 }
  codex: { input: 298167, output: 3757, cached: 202880, tool_calls: 8, elapsed_s: 80.3 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 1, elapsed_s: 625.1 }
synthesizer: claude
synthesized_at: 2026-04-28T23:07:40Z
---

# Cross-AI Review

## Claude Review

Cross-AI review of phase 18 chunk A. No skill match — this is review feedback, not implementation. Going direct.

## HIGH

### H1: Rebind across changed attribution leaks active gauge (`WorldWebSocketHandler.java:340-360`)

Rebind path:
```java
Tags oldTags = admissionMetrics.lookupBucketTags(rebind.entityId());
if (oldTags != null) admissionMetrics.decStalledBucketByTags(oldTags);
// ... no active bucket touched
```

Comment says "Active stays incremented from original Allow." Correct only if attribution unchanged. If client reconnects with **different harness id** (e.g. operator restarts harness JVM, gets new auto-generated `harness-<uuid>`, reuses resume token):
- OLD active bucket: +1 forever (never decremented — no entity now uses old tags)
- NEW session attrs have NEW tags
- On eventual close: `decActiveBucket(session)` decrements NEW bucket → 0 or -1 (NEW never incremented)
- OLD bucket leaks at +1 for JVM lifetime

Fix: rebind path must dec old active bucket via snapshot AND inc new active bucket via current session tags. Or document/enforce that attribution can't change across rebind (probably wrong — operator restart is legit).

Also: bucketTagsByEntityId snapshot must update to NEW tags after rebind, or grace-expiry reaper for that entity later decrements OLD tags (now stale).

### H2: `bucketTagsByEntityId` map never pruned (`AdmissionMetrics.java:96`)

```java
private final ConcurrentHashMap<String, Tags> bucketTagsByEntityId = new ConcurrentHashMap<>();
```

Written in `incActiveBucket` / `incStalledBucket`. Never removed.

`WorldWebSocketHandler.cleanupBot` clears `respawnCountAtStall` but NOT `bucketTagsByEntityId`. Over JVM lifetime, every entity id ever admitted accumulates. At 5000 connections × respawn × restart churn → unbounded map growth.

Fix: `cleanupByEntityId` and `cleanupBot` must call `admissionMetrics.releaseBucketTags(entityId)` (new method) to remove entry.

### H3: Client can spoof reserved source values (`WorldWebSocketHandler.java:202-206`)

```java
if (!BotIdentity.SOURCE_TAXONOMY.contains(source)) source = "unknown";
```

Taxonomy = `{operator, harness, unknown, overflow, offspring}`. Client sending `X-Paralife-Source: overflow` or `X-Paralife-Source: offspring` passes the check. Now their traffic tags as overflow (impersonating server-side cardinality fold) or offspring (a reserved value, no producer this phase per D-20).

`overflow` should be **server-only**. `offspring` should be server-only until backlog 999.2 producer ships.

Fix: server-side allowlist = `{operator, harness, unknown}` only. Reserve `overflow`/`offspring` for server-side use.

## MEDIUM

### M1: Synchronized hotspot on every tag emission (`AttributionTagger.java:127`)

`foldHarnessIfOverCap` wraps full slot logic in `synchronized (slotLock)`. Called from `tagsFor()` on every counter increment / gauge read / log emission for every harness-tagged session.

Fast path (`observedHarnessIds.contains(harnessId)`) is inside the mutex → serializes all metric emissions globally. At 5000 connections × ~6 metrics/tick this is a real contention point.

Mitigation: lock-free fast path with `ConcurrentHashMap<String, Boolean>` for `containsKey`; sync only on slot-claim miss. Race-free via second `putIfAbsent` inside sync.

### M2: No harness-id eviction → restart churn exhausts cap

D-10 caps at 64 unique ids per JVM lifetime. No eviction. Over long-running server, every harness id ever connected stays in `observedHarnessIds` forever. Operators that don't pin `--harness-id` (auto-gen `harness-<uuid>`) burn one slot per harness restart. After 64 restarts → all subsequent harnesses fold to overflow even when only 1 is live.

D-10 does NOT address this — the spec implies cap is meaningful for live concurrency, but implementation gives lifetime cap. Worth ADR or eviction policy (LRU on stalled-cleared buckets).

### M3: `source=harness` with missing harness id silently allowed

Client sends `X-Paralife-Source: harness` with no `X-Paralife-Harness` (or blank). Server stashes `source=harness` but no `harness` attr. Result:
- Log marker: `HARNESS connected ... harness=- source=harness` (mixed signal)
- Metrics: `paralife.admission.active.entities{source=harness}` with no harness tag — breaks two-tag invariant

D-11: "`harness=<id>` emitted only when `source=harness`" reads as conditional, but practical invariant should be `source=harness ⇔ harness present`. Currently broken in one direction.

Fix: if source=harness and sanitized harness id absent, fold source to `unknown`.

### M4: `formatLogFields` skips overflow folding (`AttributionTagger.java:113`)

```java
public static String formatLogFields(WebSocketSession session) {
    String harness = harnessOf(session);  // raw attr value
    return harness == null ? ... : "source=harness harness=" + harness;
}
```

Log fields use raw harness id, not folded. Means log markers emit raw `harness=<id-65>` for sessions whose metrics tag as `harness=overflow`. Operator grepping for `harness=overflow` won't find log lines for affected sessions.

Defensible (logs preserve raw id; metrics fold for cardinality). But document the divergence in `18-HARNESS.md` §5 Operator Cheat Sheet — currently misleading.

## LOW

### L1: No length cap on source header (`WorldWebSocketHandler.java:200`)

`rawSource.trim()` then taxonomy check. 10MB source header gets trimmed and checked. Jetty's 8KB header field cap is the only practical limit. Cheap fix: bail if `rawSource.length() > 16`.

### L2: `ATTR_HARNESS` / `ATTR_SOURCE` not cleared on cleanup

`cleanupBot` removes entity/stall/resume attrs but not attribution. Session unregistered anyway so harmless, but inconsistent with other attr cleanup.

### L3: `overflowWarned` AtomicBoolean redundant inside synchronized

`AttributionTagger.java:166` — already inside `synchronized (slotLock)`, so plain boolean works. Trivial.

## TEST GAPS

1. **Rebind across different harness id** — no test for H1. Add: stall session A with `harness=h1`, rebind via token from session B with `harness=h2`, assert h1 active gauge → 0 (or document that rebind preserves original attribution).
2. **`bucketTagsByEntityId` growth** — no test for H2. Add: 100 register→close cycles, assert map size = 0 after.
3. **Reserved source spoofing** — no test for H3. Add: client sends `source=overflow` and `source=offspring`, assert server folds to `unknown`.
4. **Source=harness with no harness id** — no test for M3. Add: client sends `source=harness` only, assert source folds to unknown.
5. **CardinalityCapTest with restarts** — no test for M2 (eviction-on-bucket-empty).
6. **High-concurrency tagsFor under contention** — `synchronizedSlotClaimEliminatesCapBoundaryRace` covers correctness, not throughput. JMH or load-style test would reveal M1.

## OK / Verified

- Cardinality cap correctness: `MeterFilter` cap+1 reasoning correct, sync slot-claim race-free (Round 2 fix).
- markStalled ordering: read entityId BEFORE attrs.remove → snapshot captured correctly.
- `SessionRegistry.getById` O(1) for action hot-path (Round 2 Codex HIGH).
- `BotClient.identity` final field → re-emitted on every `connect()` including reconnect.
- `AttributionSanitizer` defense-in-depth (server doesn't trust client BotIdentity).
- Fire-once close callback CAS gate.
- TickHealthMonitorScalarTest non-vacuous (explicit fail if no TICK-HEALTH lines).
- Guard order in AdmissionGate: already-registered before resume-token (T-17-confused mitigation).

Top fix priority: H1 (gauge leak across rebind) and H2 (map memory leak). Both are silent — no test catches them, both compound over time. Recommend blocking merge until addressed.

---

## Gemini Review (FAILED)

**Status:** failed — unhandled ValueError: Separator is not found, and chunk exceed the limit

Elapsed: 625.2s

---

## Codex Review

**Findings**

1. **High: normal disconnect leaks admission slots and active gauges.**  
   [WorldWebSocketHandler.java](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:273) unregisters the session before calling `cleanupBot`. Then `cleanupBot` looks up the session from `SessionRegistry` at [line 620](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:620), gets `null`, leaves `wasRegistered=false`, and skips both `admissionGate.releaseSlot()` and `admissionMetrics.decActiveBucket(...)` at [lines 642-649](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:642). A clean close after successful registration can permanently consume cap and leave `active.entities` high.

2. **High: active gauge cleanup is not rebind-safe if attribution changes or is missing on reconnect.**  
   Rebind decrements stalled by the original entity snapshot, but later cleanup decrements active using the new session’s current tags at [AdmissionMetrics.decActiveBucket(s)](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:648). If a reconnect omits headers or changes `X-Paralife-Harness`, the active bucket originally incremented for `harness=A` will not be decremented. Cleanup should use `lookupBucketTags(entityId)` for active decrement, then remove that snapshot.

3. **Medium: `bucketTagsByEntityId` is unbounded because snapshots are never removed.**  
   The map is populated at [AdmissionMetrics.java:235](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:235) and [line 282](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AdmissionMetrics.java:282), but there is no remove path. Over long runs with respawns/reconnects, this leaks one entry per entity id even though gauges are cardinality-capped.

4. **Medium: malformed harness ids are accepted despite the documented regex policy.**  
   `AttributionSanitizer` only trims, rejects ASCII controls, and truncates at [lines 42-56](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AttributionSanitizer.java:42). `BotIdentity` has the same gap at [lines 57-61](/home/mark/kramtime/paralife/src/main/java/com/paralife/bot/BotIdentity.java:57). Values like `bad id`, `foo=bar`, `/path`, or non-ASCII pass into logs/metrics, conflicting with `^[A-Za-z0-9-]{1,32}$` in `18-HARNESS.md`.

5. **Medium: `AttributionTagger` emits `harness` for any session carrying `ATTR_HARNESS`, even when `source != harness`.**  
   [AttributionTagger.java:91-97](/home/mark/kramtime/paralife/src/main/java/com/paralife/admission/AttributionTagger.java:91) only checks whether a harness attr exists. The D-11 shape says `harness` is emitted only for `source=harness`. This is mostly protected by the handshake path, but the tagger is described as the single source of truth and should enforce the invariant directly.

**Coverage Gaps**

Rejection paths have decent counter/log coverage for admission rejects, malformed frames, and default unknown. The missing tests are lifecycle integration tests: registered connection close should decrement active and release cap; rebind with missing/changed headers should not leak the original bucket; and cleanup should remove `bucketTagsByEntityId` snapshots.

I did not run the test suite; this was a static review of the provided chunk plus line checks in the workspace.

---

## Opencode Review

## Phase 18 Chunk A — Code Review

### CRITICAL (2)

**C1 — Slot + gauge leak on non-stalled disconnect (`WorldWebSocketHandler.java:273→296`)**
`afterConnectionClosed` calls `sessionRegistry.unregister(sessionId)` **before** `cleanupBot(sessionId)`. By the time `cleanupBot` runs, `sessionRegistry.getSession(sessionId)` returns `null`, so `wasRegistered` stays `false` — both `releaseSlot()` and `decActiveBucket()` are skipped. **Every** non-stalled disconnect permanently leaks 1 reserved slot + 1 active gauge count. After 1000 bot cycles, `reservedSlots` diverges from reality.

Fix: swap the order — call `cleanupBot` before `unregister`. `handleTransportError` at line 307 already does this correctly.

**C2 — `bucketTagsByEntityId` unbounded growth (`AdmissionMetrics.java:98` → `put` at lines 235, 282)**
The map is written on `incActiveBucket` and `incStalledBucket` but **never removed**. Over a long-running sim with 10⁶ entity lifecycles, this leaks ~100 MB. Zero `remove()` calls exist in the codebase.

Fix: `remove(entityId)` in `cleanupBot`, `cleanupByEntityId` (all exit paths), and after the rebind path's `lookupBucketTags` call.

---

### HIGH (3)

**H1 — Duplicated sanitization logic (`BotIdentity.java:33-62` vs `AttributionSanitizer.java:42-57`)**  
The `BotIdentity` compact constructor and `AttributionSanitizer` independently implement null/blank check, control-char rejection, and 32-char truncation. The BotIdentity Javadoc itself says delegation is pending. Divergence risk: change one, miss the other.

**H2 — Stale `bucketTagsByEntityId` after rebind with changed headers (`WorldWebSocketHandler.java:370-398`)**  
After rebind, the map still holds the old stall-snapshot Tags. If the reconnecting client sends different headers, `cleanupBot` decrements the new bucket (using session Tags) while the old bucket stays +1 permanently. Same-harness rebind is fine; misconfigured harness breaks.

Fix: `bucketTagsByEntityId.put(entityId, tagger.tagsFor(session))` in the rebind path after `decStalledBucketByTags`.

**H3 — `SessionRegistry.getSession` and `getById` are identical duplicates (`SessionRegistry.java:51, 66`)**  
Both do `sessions.get(sessionId)` — already O(1). `getById` was added in Round 2 claiming optimization need, but the original `getSession` was never O(N). Pick one canonical name, deprecate the other.

---

### MEDIUM (3)

- **M1**: `AdmissionGateTest` only tests the no-session `evaluate(req)` shim — no tests for `evaluate(req, session)` within this test class.
- **M2**: `handleTransportError` calls `cleanupBot` → `unregister`; `afterConnectionClosed` calls `unregister` → `cleanupBot`. Works by accident (C1 makes cleanupBot a no-op the second time), but fragile.
- **M3**: Rebind path never updates `bucketTagsByEntityId` to new session Tags — stale entry survives until next stall overwrites it.

---

### LOW (3)

- **L1**: Four test classes define redundant `FakeSession` stubs — extract a shared fixture.
- **L2**: `WorldWebSocketHandler.java:232-234` — oversized source header value not length-limited before `SOURCE_TAXONOMY.contains()` check (cheap defense against 1MB header).
- **L3**: `markStalled` pass-through when no resume token exists — entity becomes unrecoverable orphan; comment should note gauge implications.

---

### Key things done right

- `foldHarnessIfOverCap` synchronized block is genuinely race-free — `contains`+`size`+`add` all under one lock.
- MeterFilter `cap+1` correctly accommodates the overflow bucket alongside 64 legitimate harness buckets.
- `AttributionSanitizer` covers every edge case: null, blank, NUL, DEL, max-length, over-length, CR, LF, tab.
- All admission and non-admission rejection paths pass session context to `incRejected(token, session)`. No gaps.
- `AdmissionBeansConfig` correctly produces the `AttributionTagger` bean.
- Round 2 amendments (explicit `entityId` param on `incStalledBucket`, warn-once at fold site, synchronized slot-claim) are all implemented and verified.

**C1 is the showstopper** — it silently corrupts admission state under normal operation. C2 is a slow leak that only matters for long-running sims but is equally easy to fix.

---

## Consensus Summary

### Agreed Strengths
- Cardinality cap synchronized slot-claim is race-free; MeterFilter `cap+1` correctly accommodates overflow bucket alongside 64 harness buckets (claude OK, opencode "done right").
- `AttributionSanitizer` covers edge cases (null/blank/control chars/length) as defense-in-depth (claude OK, opencode "done right").
- Round 2 fixes (explicit `entityId` snapshot on stall, warn-once fold, synchronized slot-claim) verified implemented (claude OK, opencode "done right").

### Agreed Concerns
- **CRITICAL/HIGH — Active gauge + slot leak across disconnect/rebind when attribution changes or session-lookup ordering wrong.** Codex H1 + opencode C1: `afterConnectionClosed` unregisters session before `cleanupBot`, so `wasRegistered=false` → `releaseSlot()` and `decActiveBucket()` skipped on every clean disconnect (silent slot/gauge leak). Claude H1 + codex H2 + opencode H2: rebind path decrements stalled by old snapshot but later `decActiveBucket(session)` uses NEW session tags — if harness id changes (operator restart with new auto-uuid) old bucket leaks +1 forever, new bucket goes negative on close. Fix: cleanup must use `lookupBucketTags(entityId)` snapshot for active dec; rebind must update snapshot to new tags.
- **HIGH — `bucketTagsByEntityId` unbounded map growth.** Unanimous (claude H2, codex M3, opencode C2): map written on inc, never removed. Leaks one entry per entity id over JVM lifetime. Fix: `releaseBucketTags(entityId)` called from `cleanupBot` and `cleanupByEntityId`.
- **MEDIUM — `source=harness` invariant not enforced symmetrically.** Claude M3 + codex M5: `AttributionTagger` emits `harness=` tag whenever `ATTR_HARNESS` exists regardless of source; conversely `source=harness` with missing harness id silently passes. D-11 invariant should be `source=harness ⇔ harness present`. Fix: enforce both directions in tagger.
- **MEDIUM — Sanitization gap vs documented `^[A-Za-z0-9-]{1,32}$` policy.** Codex M4 + opencode H1: `AttributionSanitizer` and `BotIdentity` only reject control chars/truncate, not non-allowlist chars; values like `bad id`, `foo=bar`, `/path` pass through. Also duplicated logic between the two. Fix: single sanitizer with regex-allowlist.
- **LOW — No length cap on source header before taxonomy check** (claude L1, opencode L2).

### Divergent Views
- **Reserved source spoofing (`overflow`/`offspring`).** Claude H3 flags this as HIGH (client can impersonate server-side cardinality fold or reserved offspring tag). Codex/opencode did not raise it. Worth investigating — taxonomy check currently allows these values from clients, which contradicts their documented server-only role.
- **`SessionRegistry.getById` vs `getSession`.** Opencode H3 calls them identical duplicates and questions Round 2 rationale. Claude OK'd `getById` as O(1) hot-path verified. Worth a quick check whether the duplication is intentional or an artifact.
- **Harness-id eviction policy.** Claude M2 raises lifetime-cap-vs-live-concurrency mismatch (64 restarts → all subsequent fold to overflow). Others did not flag. Possibly out-of-scope for chunk A but should be tracked.
- **`AttributionTagger` synchronized hotspot under load.** Claude M1 (real contention at 5k connections × metrics/tick). Others did not flag. Performance concern, not correctness — defer unless load harness validates.
- **Severity of disconnect-ordering bug.** Opencode rates C1 CRITICAL and showstopper; codex rates equivalent finding HIGH. Both agree on fix; disagreement only on label.

---

## Codex Round 2

**Findings**

1. **High: STALLED terminal expiry also leaks active accounting after the old socket is unregistered.**  
   The first-round disconnect finding covers normal close, but the same shape exists on grace expiry. `afterConnectionClosed` unregisters STALLED sessions and returns while leaving the entity bound for `ResumeTokenRegistry` cleanup. Later `cleanupByEntityId` resolves the old session id and calls `cleanupBot(sessionId)`, but `cleanupBot` cannot recover the old `WebSocketSession`, so `wasRegistered=false` and active bucket decrement plus admission slot release are skipped. The stalled bucket is decremented by snapshot, but active/reserved accounting remains leaked.

2. **High: "preserve attribution across rebind" is not guaranteed for missing or changed reconnect headers.**  
   `AttributionRebindTest` covers the happy same-identity reconnect using the same `BotClient`, but the server does not preserve original source/harness onto the rebound session. If the resume-token reconnect omits headers, changes harness id, or sends malformed harness attribution, later cleanup uses the new session's tags while the original active bucket remains incremented. The phase spec says rebind should preserve attribution; the implementation currently relies on clients re-sending identical headers.

3. **Medium: `source=harness` without a valid harness id creates a half-attributed series.**  
   `WorldWebSocketHandler.afterConnectionEstablished` stores `source=harness` even when `X-Paralife-Harness` is missing, blank, or rejected by `AttributionSanitizer`. `AttributionTagger.tagsFor` then emits `source=harness` with no `harness` tag, which undercuts the two-tag scheme and makes harness traffic un-attributable.

4. **Medium: malformed harness ids still drift from `18-HARNESS.md`'s canonical regex.**  
   Round 1 already called out the sanitizer gap; Round 2 confirms both `BotIdentity` and `AttributionSanitizer` still accept spaces, punctuation such as `=`, `/`, and non-ASCII characters as long as they are not ASCII controls. This conflicts with `^[A-Za-z0-9-]{1,32}$`.

5. **Low: `AttributionTagger` should enforce `harness` only for `source=harness`.**  
   Even if the handshake path is tightened, the tagger is the "single source of truth" and should not mint `source=operator,harness=...` if a test or future call site sets inconsistent attributes.

**Coverage Gaps**

- Add a STALLED terminal-expiry integration test that asserts active gauge, stalled gauge, and `reservedSlots()` all return to zero after the old socket has closed.
- Add a rebind test where the resumed socket has missing or different attribution headers; expected behavior should be either explicit original-attribution preservation or an active-bucket move without leakage.
- Add handshake tests for `source=harness` with missing/blank/rejected harness id.
- Add sanitizer tests for spaces, slash, equals, underscore if intentionally disallowed, and non-ASCII.

**OK / Verified**

- The 64-tag cap and `overflow` boundary remain race-free in the current implementation.
- `MeterFilter` cap+1 is still the right shape for allowing `overflow` beside 64 real harness ids.
- Rejection-path coverage is reasonable for admission guard tokens and malformed frames; the remaining gaps are lifecycle and malformed-handshake attribution paths.

**Test Run**

Not run. Round 2 was a static follow-up over the same requested chunk and phase notes.
