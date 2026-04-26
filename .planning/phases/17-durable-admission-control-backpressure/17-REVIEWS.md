---
phase: 17
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-04-27
plans_reviewed:
  - 17-01-PLAN.md
  - 17-02-PLAN.md
  - 17-03-PLAN.md
  - 17-04-PLAN.md
  - 17-05-PLAN.md
  - 17-06-PLAN.md
  - 17-07-PLAN.md
  - 17-08-PLAN.md
  - 17-09-PLAN.md
  - 17-10-PLAN.md
  - 17-11-PLAN.md
models:
  gemini: default
  claude: default (claude-opus-4-7 inferred)
  codex: gpt-5.5 (high reasoning)
  opencode: openrouter/moonshotai/kimi-k2.6 (--agent plan)
---

# Cross-AI Plan Review — Phase 17

## Gemini Review

# Phase 17 Review: Durable Admission Control & Backpressure

## 1. Summary
The plan for Phase 17 is exceptionally well-structured, providing a robust and idiomatic solution to admission control and backpressure in the Paralife simulation. By moving from a synchronous, global-lock-based send model to a decoupled, virtual-thread-per-session architecture, the system gains structural protection against slow clients dragging down tick health (SCALE-02). The introduction of a stable machine-readable token taxonomy (D-07) and a resume-token grace window (D-12) transforms the current stopgap into a production-grade protocol capable of handling the external load generation planned for Phase 18.

## 2. Strengths
*   **Structural Isolation (D-10):** Using a virtual thread and bounded queue per session is the correct "Paralife-idiomatic" way to leverage Java 21. It removes the `synchronized(session)` contention in the `TickBroadcaster`, ensuring one slow TCP socket cannot block the entire simulation tick.
*   **Deterministic Codec Disambiguation (Plan 02):** The decision to use a `r:` sentinel prefix for resume tokens is a clever, low-overhead way to resolve the potential ambiguity between tokens and effect blocks in the `S|` frame without adding complex lookahead logic to the `PerceptionCodec`.
*   **Comprehensive Observability (D-17..D-19):** The use of tagged counters, specific gauges, and grep-friendly log markers (`ADMISSION`, `BACKPRESSURE`, `TICK-HEALTH`) ensures operators have immediate visibility into why clients are being rejected or stalled.
*   **Migration Path (Plan 10):** The explicit deletion of `PopulationCapConfig` and the migration of `application.yml` and `LoadTest` ensures no stale configuration or "shadow code" remains from the temporary stopgap.
*   **Hysteresis Gate (D-14):** Implementing the tick-health gate with hysteresis prevents the admission system from "flapping" on single-tick spikes, providing a more stable environment for bot registration.

## 3. Concerns
*   **Delayed Closure of Stalled Sessions (Plan 07, Step 8):**
    *   *Severity: MEDIUM.*
    *   Currently, `markStalled` detaches the `OutboundSender` but waits for the *next inbound frame* in `handleTextMessage` to actually close the WebSocket session with a 408 error. If a bot is truly overloaded or silent, the session might remain "open" in the `SessionRegistry` until Jetty heartbeats fail or the server reaps it.
*   **`BotRegistry` Session/Entity Re-bind Atomicity (Plan 07, Step 5):**
    *   *Severity: LOW.*
    *   The `rebindSession` helper needs to be careful about the order of operations to avoid transient states where an entity is "lost" between the old and new session IDs, especially in a concurrent environment.
*   **`recordFrameSize` Metric Loss (Plan 08, Step 3):**
    *   *Severity: LOW.*
    *   The plan correctly identifies that frame size metrics are lost when moving the encode to the async sender. Dropping them is acceptable for this phase, but moving the metric to the `OutboundSender` (Option 1b) would be better for long-term telemetry.

## 4. Suggestions
*   **Proactive Closure:** In `WorldWebSocketHandler.markStalled` (Plan 07, Step 8), consider initiating `session.close()` immediately after enqueuing the 408 error frame. This prevents "zombie" stalled sessions from occupying slots in the `SessionRegistry` while waiting for the client to send a frame.
*   **Token Refresh Logic:** Ensure `ResumeTokenRegistry.tryRebind` (Plan 05) explicitly validates that the `newSessionId` is not already bound to another entity, although the `already-registered` check in the handler should cover this.
*   **LoadTest Calibration:** In Plan 11, expect to calibrate `LoadTest` tolerances. The move from synchronous to async broadcast *will* introduce jitter in frame arrival times at the bot client; assertions that check for frames in an "exact" tick might need a ±1 tick window.
*   **Ingress Metrics:** Tag the `paralife.admission.ingress.overwrites` counter with the `species` if possible, providing insight into which bot types are most frequently flooding the server.

## 5. Risk Assessment
**Overall Risk: LOW**

The strategy is sound and follows established patterns in the codebase. The dependency chain across the 11 plans is logical, starting with the configuration/codec foundation and ending with full-stack integration tests. While the refactor of `TickBroadcaster` is a major change to the simulation's hot path, the "VT-per-session" pattern has already been successfully used in `TickEngine` and `BotLauncher`, providing high confidence in its performance characteristics. The comprehensive 17-VALIDATION.md plan mitigates the risk of regression in core simulation stability.

---

## Claude Review

# Phase 17 Plan Review

## Summary

The phase is well-researched and decisions are mostly locked, with strong reuse of established patterns (Micrometer, VT spawn, `@ConfigurationProperties`). However, several integration points carry subtle but high-impact bugs around codec disambiguation, the STALLED-pivot lifecycle, and concurrent writer races on `WebSocketSession.sendMessage`. These would surface under exactly the load conditions M4 is built to expose. The 4-wave dependency graph also under-specifies inter-bean compile-time dependencies in Wave 2. Net: solid intent, executable plans, but at least three HIGH-severity issues need correction before execution.

## Strengths

- **Clean wave decomposition.** Wave 1 (declarations) → Wave 2 (isolated beans + unit tests) → Wave 3 (integration in handlers) → Wave 4 (migration + integration tests) maps cleanly to the dependency graph.
- **Token taxonomy lock (Plan 01) before any consumer code.** Every Reject path keys off `RejectionToken.<CONST>`, eliminating string drift.
- **VT-per-session rationale (D-10) documented in two places.** Plan 06 Javadoc + Plan 10 CLAUDE.md prevents future drift to Jetty native async.
- **Hysteresis on tick-overload gate.** Plan 04's two-watermark design with explicit `noFlappingOnSamplesInBetweenWatermarks` test is the right shape for SCALE-02.
- **Resume-token single-use semantics.** Plan 05's `tryRebind` consume-and-mint-fresh in one atomic call is the correct API; Plan 03 and Plan 09 both align.
- **Migration plan is complete.** Plan 10 deletes `PopulationCapConfig`, migrates `LoadTest`, and updates `application.yml` in one wave — no half-state.

## Concerns

### HIGH

- **Plan 02 codec disambiguator is wrong.** Plan asserts "effects always begin with `f` followed by a digit per SCHEMA §8.3". This is the `f` BLOCK prefix in TickFrame. SyncFrame effects per `15-SCHEMA.md` §6.2 use **bare effect format with no `f` prefix** — see vector 10: `S|7A|S:1Fg8,I:1Ef0` (effects start with `S:` and `I:`). The proposed `looksLikeEffectBlock` checks for leading `f` and will misclassify legacy effect-only Sync frames as resume tokens. The `r:` sentinel approach is still salvageable but the disambiguator needs inverting: "second slot starts with `r:` → token; otherwise → effect list." Tests `parseSyncEntityAndEffects` and `disambiguateTokenVsEffects` will fail or pass for the wrong reasons against real codec behavior.

- **STALLED → afterConnectionClosed reaps the entity prematurely.** Plan 07 has `afterConnectionClosed` call `cleanupBot(sessionId)` unconditionally. When the server closes a STALLED session's WS, this fires and removes the entity from `BotRegistry` — but the resume-token grace window expects the entity to remain reachable for re-bind. By the time the client reconnects with a valid token, `BotRegistry` has lost the entry; only `ResumeTokenRegistry` knows the entityId, and the grid cell may already be cleared. The plan needs explicit "if `isStalled(session)` skip `cleanupBot`" in `afterConnectionClosed`, plus matching logic to keep the WorldGrid occupant alive for the grace window. ResumeTokenRegistry's sweep callback then becomes the sole reaper.

- **OutboundSender detach has no VT join — fallback path can race.** `OutboundSender.detachSession` calls `t.interrupt()` and returns immediately. Plan 07 step 9's `sendFrame` fallback then does `synchronized(session) sendMessage` if `offer` returns false (queue-not-attached). If detach was just called and the VT is mid-`sendMessage`, two threads write the session concurrently. Jetty 12 `sendMessage` is not thread-safe (RESEARCH A2). Need either `t.join(timeout)` in detach, or eliminate the synchronous fallback by routing all post-detach frames as drops with a warn log.

### MEDIUM

- **Wave 2 `depends_on` is under-declared.** Plan 03 (AdmissionGate) imports `TickHealthMonitor` and `ResumeTokenRegistry` as compile-time types but only declares `depends_on: [17-01]`. Executor running plans in parallel may compile Plan 03 before Plans 04/05 produce their classes. Add `depends_on: [17-01, 17-04, 17-05]` to Plan 03 (or accept that Wave 2 plans are jointly-compiled and document that explicitly).

- **Rolling window fires on first sample.** Plan 04 evaluates `mean = sum / filled` even when `filled == 1`. Plan 04's own test `singleSpikeDoesNotTrigger` admits a single 200ms spike triggers overload. That defeats the noise-resistance purpose. Add a `if (filled < window.length) return;` guard so the gate only evaluates once the window is fully populated.

- **`cleanupCallback` signature mismatch.** Plan 05 callback is `Consumer<String> entityId`. Plan 07 wires `this::cleanupBot` — but existing `cleanupBot` takes `sessionId`. The two are not interchangeable; `BotRegistry.entityToSession` lookup is needed at the call site. Plan 07 hand-waves this. Specify either an `cleanupByEntityId(String)` helper on `WorldWebSocketHandler` or change the callback contract.

- **ATTR_RESPAWN_COUNT not migrated on Rebind.** Plan 07 step 5 sets `ATTR_ENTITY_ID` and `ATTR_ENTITY_TYPE` on the new session but not `ATTR_RESPAWN_COUNT`. The respawn cap then resets to 0 on every stall recovery — clients can stall-reconnect indefinitely to bypass the per-session respawn cap. Either (a) migrate the count via the `ResumeTokenRegistry` entry or (b) accept this as a documented quirk. Currently silent.

- **`ActionResolver` ctor change breaks existing tests.** Plan 10 adds `AdmissionMetrics` to the constructor. Any test that constructs `ActionResolver` manually (e.g. `PopulationDynamicsTest`, others in `engine/`) needs updating. Plan 10 doesn't enumerate.

- **BotClient reconnect uncertain.** Plan 09 explicitly hedges on whether reconnect logic exists. If it doesn't, Plan 09 adds one inline — but interaction with Phase 15.2 death-pivot's same-WS retry path is non-trivial. Verify existing `@OnWebSocketClose` behavior before authoring.

- **Plan 11 log-marker tests are brittle.** Exact-string regex assertions on log lines couple tests to D-19 phrasing forever. A typo fix in a log message becomes a test failure. Acceptable only if D-19 is genuinely the operator contract; otherwise weaken to "contains marker prefix + key fields" assertions.

### LOW

- **64-bit token entropy** is fine given the threat model (no privilege escalation per §Security Domain), but `nextLong()` from `ThreadLocalRandom` is not a CSPRNG. Drop-in upgrade: `SecureRandom`. Cost negligible at issuance rate.
- **Plan 06 `OutboundSenderTest`** uses `awaitility` conditionally; build dep check is good but the manual-poll fallback adds two code paths to maintain.
- **Plan 11 maintenance startup log** added inline in a test plan is scope creep into Plan 07. Move the production-code change to Plan 07 explicitly.
- **Outbound queue size 16** — at 500ms prod tick that's 8 seconds of buffering before STALLED fires. Verify in Plan 11 that grace-window-ticks default (10 ticks = 5s prod) doesn't expire while the queue is still draining a backlog.
- **No `@PreDestroy` on OutboundSender.** Application shutdown leaks VTs briefly. Cosmetic.

## Suggestions

1. **Fix Plan 02 disambiguator first.** Change rule to "second slot starts with `r:` ⇒ token; otherwise ⇒ effect list." Add a positive test where the second slot starts with `S:` (SCHEMA-vector-10-style) and round-trips as effects. Update Plan 01's `17-ADMISSION.md` §4 accordingly.

2. **Add an explicit STALLED-aware close path.** In Plan 07 step 4 (`afterConnectionClosed`):
   ```java
   if (isStalled(session.getAttributes())) {
       outboundSender.detachSession(session.getId());
       sessionRegistry.unregister(session.getId());
       // Do NOT cleanupBot — entity stays in grace until ResumeTokenRegistry sweep reaps it
       return;
   }
   ```
   Pair with a `cleanupByEntityId(String)` helper that ResumeTokenRegistry's callback invokes.

3. **Join sender VT in detach.** In Plan 06:
   ```java
   public void detachSession(String sessionId) {
       queues.remove(sessionId);
       Thread t = senderThreads.remove(sessionId);
       if (t != null) {
           t.interrupt();
           try { t.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
       }
   }
   ```
   Bounds the post-detach race to 100ms.

4. **Strengthen Wave 2 dependencies.** Update `depends_on` on Plan 03 to `[17-01, 17-04, 17-05]`. Document that Wave 2 plans must all complete before any compile gate.

5. **Window-full guard in `TickHealthMonitor`.** Defer gate evaluation until `filled == window.length`. Update Plan 04 tests to pre-fill before asserting transitions.

6. **Explicit RespawnCount migration.** Either copy `ATTR_RESPAWN_COUNT` from the old session via `ResumeTokenRegistry.ResumeEntry` (extend the record), or document the reset-on-rebind as a known limitation in `17-ADMISSION.md`.

7. **Enumerate ActionResolver-ctor breakage.** In Plan 10 add a step: grep all `new ActionResolver(` callsites and update each to inject the new metrics dependency (or pass `null` / `Mockito.mock(AdmissionMetrics.class)` in tests).

8. **Tighten log-marker assertions.** Replace exact regex with `containsAll(["ADMISSION rejected", "reason=world-full"])` style — preserves operator contract intent without brittleness.

## Risk Assessment

**MEDIUM-HIGH.** The plan structure is sound and the research is thorough, but three HIGH-severity issues (codec disambiguation, STALLED reap-on-close, detach race) would likely manifest at exactly the load levels M4 needs to validate. None of them are unfixable — all three are 1–10 line corrections — but they need to land before Wave 3 or Plan 11 will produce false greens. The MEDIUM concerns (respawn-count migration, Wave 2 dependency declarations) are also corrections-not-redesigns. After those fixes, risk drops to LOW–MEDIUM. The phase will achieve SCALE-01 cleanly; SCALE-02 depends on getting the three HIGH issues right.

---

## Codex Review

**Summary**
The phase design is directionally strong, but the current plan set has several correctness-breaking contradictions around STALLED lifecycle, resume-token expiry, and cleanup semantics. The admission taxonomy, config migration, metrics shape, and VT-per-session direction are solid. The risky parts are not small implementation details: as written, live entities may be reaped after normal registration, STALLED entities may be cleaned up immediately on WebSocket close, and the queue-overflow path may recurse into more overflow instead of delivering the explicit `E|408|reconnect-required` signal. Overall: good architecture, but the execution plans need tightening before implementation.

**Strengths**
- Clear separation of concerns: `AdmissionConfig`, `AdmissionGate`, `TickHealthMonitor`, `ResumeTokenRegistry`, and `OutboundSender` are the right conceptual components.
- D-07 token taxonomy is concrete, testable, and avoids brittle free-text branching.
- Keeping `RespawnConfig` as a sibling is pragmatic and lowers migration risk.
- VT-per-session sender is a good fit for Java 21 and the project's existing blocking-code style.
- Tick-health hysteresis is correctly identified as needing a dedicated rolling window, not Micrometer cumulative mean.
- Validation strategy covers the right broad behaviors: cap, maintenance, tick-overload, STALLED recovery, token expiry, log markers, and config migration.

**Concerns**
- **HIGH — Resume-token lifecycle is internally inconsistent.**
  Plans 05 and 07 call `ResumeTokenRegistry.issue(...)` on normal successful registration and store `expiresAtTick = currentTick + graceWindowTicks`. That means every normally alive bot's token expires after ~10 ticks, and `ResumeTokenRegistry.sweep()` may call `cleanupBot(entityId)` on a live entity. This violates D-12/D-13 and would cause silent entity loss.

- **HIGH — STALLED grace is defeated by `afterConnectionClosed`.**
  Plan 07 keeps `afterConnectionClosed` calling `cleanupBot(session.getId())`. But STALLED explicitly closes the WebSocket after `E|408|reconnect-required`. If close cleanup always reaps, the entity will not survive the grace window.

- **HIGH — `ResumeTokenRegistry` gauge semantics are wrong.**
  Plan 05 ends by making `paralife.backpressure.stalled.sessions` effectively `tokenMap.size()`, including fresh/armed tokens. D-18 requires sessions currently in STALLED grace. This will over-report and become operationally misleading.

- **HIGH — Rebind API and BotRegistry ownership are under-specified.**
  Plan 07 casually adds `BotRegistry.rebindSession(...)`, but this is central state mutation. It must preserve position, entity-to-session reverse mapping, death queues, remap behavior, and cleanup semantics. Treating this as a small helper is unsafe.

- **HIGH — `sendFrame` fallback can reintroduce tick/thread blocking.**
  Plan 07 routes error frames through `OutboundSender.offer`, but if the queue is full it falls back to synchronized direct send. For the exact slow-client case, this can block the inbound thread and partially violates the single-writer invariant. More importantly, the explicit STALLED error may never reach the client if the sender was detached.

- **HIGH — Overflow callback may be noisy/reentrant.**
  `OutboundSender.offer` invokes overflow callback every failed offer. Without an idempotent "already stalled" guard, repeated tick/death/error offers can repeatedly call `markStalled`, issue multiple tokens, and spam logs.

- **MEDIUM — `AdmissionGate` guard order should reject already-alive before resume-token.**
  Plan 03 says maintenance → tick-overload → resume-token → already-registered. A live session sending `r|C|some-token` should probably get `409 already-registered`, not try rebind. Resume-token rebind is for a new session after STALLED close.

- **MEDIUM — Codec token/effects disambiguation is overcomplicated and unstable.**
  Plan 02 starts with ambiguous `f` handling, then chooses `r:` sentinel, but Plan 01's spec says 16-char hex. This creates cross-plan drift. Token format should be decided once: either `r:<16hex>` everywhere, or make `S` grammar unambiguous another way.

- **MEDIUM — Token entropy is acceptable but collision handling is missing.**
  `ThreadLocalRandom.nextLong()` gives 64 bits, probably okay for short-lived tokens, but the registry should loop on collision. Current `put` can overwrite an existing entry on rare collision.

- **MEDIUM — TickHealthMonitor ordering/measurement is questionable.**
  `TickEngine.lastTickWorkMs` is assigned after `publishEvent` returns. A `TickHealthMonitor` listening to the same `TickEvent` will read the previous tick's value, not the current tick's, because the current elapsed time is not computed until after all listeners finish. That may be acceptable, but the plan claims it samples the current tick after all listeners. It does not.

- **MEDIUM — Plan 08 may drop frame-size metrics without replacing them.**
  Removing `metrics.recordFrameSize` is operational regression. If frame size matters for scale work, the correct place is `OutboundSender` after encoding.

- **MEDIUM — Integration tests are too aspirational.**
  Plan 11 describes pausing `BotClient` receive handling and capturing raw frames, but existing `BotClient` may not expose hooks. This is likely to balloon into test-only API changes or flaky WebSocket timing tests.

- **LOW — `AdmissionConfigTest` nested `@SpringBootTest` classes may not run as intended.**
  The proposed nested test class pattern is unusual. It may be fine, but a simpler `ApplicationContextRunner` or top-level test configuration would reduce test fragility.

- **LOW — Planning scope is large for Phase 17.**
  This phase now includes codec changes, handler refactor, BotClient reconnect, async send, metrics, docs, migration, log-marker testing, and long-run integration. It is coherent but high blast-radius.

**Suggestions**
- Split resume tokens into explicit states:
  - `ACTIVE`: token belongs to a live connected entity, no expiry cleanup.
  - `STALLED`: token is grace-held with `expiresAtTick`; expiry reaps entity.
  - On registration: mint `ACTIVE`.
  - On stall: convert current token/entity to `STALLED` with expiry.
  - On rebind: consume `STALLED`, mint new `ACTIVE`.
- Change `afterConnectionClosed` cleanup logic:
  - If session is STALLED or has a grace entry, detach/unregister the WebSocket session but do not call `cleanupBot`.
  - Only reap through `ResumeTokenRegistry.sweep()` or normal non-stalled close.
- Make `markStalled` idempotent:
  - If `ATTR_STALL_TICK` already exists, return.
  - Issue/activate exactly one grace token.
  - Log `BACKPRESSURE stalled` once per session.
- Do not enqueue the `E|408|reconnect-required` error behind a saturated queue. For stalled sessions, send the 408 synchronously after detaching the sender, or close with a close reason if reliable delivery is not guaranteed. The current "queue is full, then enqueue error to same queue" path is suspect.
- Fix tick-health sampling:
  - Either accept that `TickHealthMonitor` samples the previous tick and document it, or move rolling-window update into `TickEngine` after elapsed time is computed.
- Decide token wire format in Plan 01 and keep it consistent. If using `r:<16hex>`, update every doc and test up front.
- Add collision-safe token generation:
  - Generate until `putIfAbsent(token, entry) == null`.
- Move encoded frame-size metrics into `OutboundSender.drainLoop` instead of dropping them.
- Replace Plan 11's WebSocket-heavy tests with more deterministic integration layers where possible:
  - Unit-test `OutboundSender` overflow.
  - Integration-test `WorldWebSocketHandler.markStalled` + reconnect using mock sessions.
  - Keep one real WebSocket happy-path test only.

**Risk Assessment**
Overall risk: **HIGH** as currently written. The high-level architecture can meet SCALE-01 and SCALE-02, but the execution plans contain lifecycle contradictions that would break the main STALLED guarantee and may reap live entities. Fixing resume-token state, close cleanup, idempotent stall transition, and tick-health sampling would bring the risk down to MEDIUM.

---

## OpenCode Review

# Cross-AI Review: Phase 17 — Durable Admission Control & Backpressure

## Summary

Phase 17 is a thorough, well-researched plan that directly attacks SCALE-01 and SCALE-02. The four-wave decomposition (config/spec → isolated beans → handler integration → migration/tests) is logical, and the research artifacts correctly identify the critical codec blocker, the `synchronized(session)` removal target, and the existing codebase patterns to clone. The VT-per-session design is idiomatic for the project's Java 21 stack, and the backward-compatibility story for old bot clients is explicit. Overall, the phase will achieve its stated goal, but several execution hazards — compilation ordering within Wave 2, a misleading stalled-session gauge, and finicky integration test mechanics — will likely require iteration during implementation.

## Strengths

- **Clean separation of concerns.** Admission decision (`AdmissionGate`), metrics (`AdmissionMetrics`), outbound backpressure (`OutboundSender`), resume-token lifecycle (`ResumeTokenRegistry`), and tick-health hysteresis (`TickHealthMonitor`) are each isolated beans with narrow interfaces.
- **Excellent research foundation.** The research document identifies the exact codec lines that must change (`parseRegister` strict `atEnd()` enforcement), the precise `synchronized(session)` blocks to remove, and eight specific free-text rejection sites to retoken.
- **Backward compatibility is first-class.** The `RegisterFrame` and `SyncFrame` convenience constructors preserve old call-site arities; missing resume tokens fall through to fresh registration; `r|C` (no token) still parses.
- **VT-per-session rationale is documented in two places** (source Javadoc + `CLAUDE.md` per D-10), preventing future drift toward Jetty native async.
- **Explicit pitfall inventory.** The research section calls out six specific pitfalls (tick-after-STALLED race, poison-pill vs interrupt, entity-remap stale token, `@ConfigurationProperties` prefix collision, `DistributionSummary` cumulative mean trap, codec backward compat) with mitigations.
- **Token taxonomy is locked and testable.** D-07's nine tokens are constants in `RejectionToken.java`, referenced by name in all downstream plans, with grep-friendly log markers.

## Concerns

- **HIGH — Wave 2 hidden compilation dependency.** Plan 03 (`AdmissionGate`) is marked `depends_on: [17-01]` and `autonomous: true`, but its constructor requires `TickHealthMonitor` (Plan 04) and `ResumeTokenRegistry` (Plan 05). If an executor verifies Plan 03 before Plans 04/05 produce their classes, compilation fails. The plan text hand-waves this ("let `./gradlew compileJava` resolve at integration") but the metadata does not reflect the true dependency graph. *Relevant: Plan 03 metadata, 17-03-PLAN.md lines 3224-3226.*
- **MEDIUM — `paralife.backpressure.stalled.sessions` gauge counts armed tokens, not stalled sessions.** Plan 05's `tryRebind` consumes the old token and immediately calls `issue()` to mint a fresh one for the *next* stall. `issue()` increments `tokenMap.size()`, which drives the gauge. After rebind, the session is Alive, not Stalled, yet the gauge still counts its armed token. A normal disconnect also leaves an armed token until sweep expiry, further inflating the gauge. Operators will see non-zero stalled sessions when none are actually stalled. *Relevant: Plan 05 `tryRebind` implementation, 17-05-PLAN.md lines 4639-4651.*
- **MEDIUM — `BotRegistry.rebindSession` is assumed but not validated.** Plan 07 assumes `BotRegistry` either has or needs a `rebindSession(newSessionId, entityId)` helper. If `BotRegistry`'s internal double-map (`bySession` / `entityToSession`) doesn't support swapping sessionIds while preserving the entityId cleanly, the rebind path could corrupt mappings. The plan says "add it if needed" but doesn't provide the implementation or a contract test. *Relevant: Plan 07 Step 5, 17-07-PLAN.md lines 5718-5728.*
- **MEDIUM — Integration test mechanics are uncertain.** `StallRecoveryIntegrationTest` needs to pause a real WebSocket client's `onMessage` handler to trigger queue overflow. The plan waffles between using `BotClient` ("if it cannot be paused without invasive changes, substitute a raw Jetty client"). This ambiguity means the test may not be implementable as written without first discovering the client's threading model. *Relevant: Plan 11 Task 1, 17-11-PLAN.md lines 6924-6946.*
- **MEDIUM — `sendFrame` fallback path races with detach.** Plan 07's `sendFrame` falls back to `synchronized(session) { sendMessage(...) }` when `outboundSender.offer` returns false. If `markStalled` has just detached the sender (removing the queue) but the inbound thread is simultaneously emitting an error frame, the fallback fires on a session that is about to be closed. The `try/catch` handles exceptions, but benign `IOException` warnings may pollute logs during normal STALLED transitions. *Relevant: Plan 07 Step 9, 17-07-PLAN.md lines 5768-5784.*
- **LOW — `recordFrameSize` metric is dropped without replacement.** Plan 08 removes `metrics.recordFrameSize(...)` calls from `TickBroadcaster` (Option 1: drop). If this metric is consumed by existing dashboards or the M5 observer, it goes dark. A note in `17-08-SUMMARY.md` documents the choice, but no recovery plan is given. *Relevant: Plan 08 interfaces, 17-08-PLAN.md lines 6005-6009.*
- **LOW — Missing edge-case test coverage.** No test covers: double-stall of the same session, concurrent rebind attempts with one token, `offer()` after `detachSession()`, or `ResumeTokenRegistry.sweep()` when the cleanup callback throws. *Relevant: 17-VALIDATION.md test map.*

## Suggestions

1. **Fix Wave 2 dependency metadata.** Add `depends_on: [17-04, 17-05]` to Plan 03, or add a wave-level instruction that Plans 03–06 must be compiled as a batch and verified only after all four are authored. The current `autonomous: true` tags are misleading for an executor that compiles after each plan.
2. **Decouple armed-token count from stalled-session gauge.** Either add a boolean `armed` field to `ResumeEntry` (false on rebind, true on stall) and filter the gauge, or maintain two maps: `stalledTokens` vs `armedTokens`. The simplest fix: in `tryRebind`, do not call `issue()`; instead, return `RebindOutcome` with the entityId only, and let Plan 07 call `issue()` only if the session later enters STALLED again.
3. **Pre-validate `BotRegistry.rebindSession` in Plan 05 or 07.** Before editing `WorldWebSocketHandler`, read `BotRegistry.java` and either confirm `remapEntity` already supports the session-swap case, or author the `rebindSession` implementation as a dedicated sub-task with its own unit test.
4. **Default to a raw Jetty client helper for `StallRecoveryIntegrationTest`.** Don't predicate the test on `BotClient` instrumentation. Author a `BlockingWebSocketClient` test utility (with a latch-holdable `onMessage`) as part of Plan 11's test scaffolding.
5. **Add overflow-fire-once guard to `OutboundSender`.** Track a per-session `AtomicBoolean overflowFired` so that if `offer()` is called multiple times while the queue remains full, the `markStalled` callback only fires once. Prevents duplicate log markers and duplicate STALLED transitions.
6. **Recover or explicitly deprecate `recordFrameSize`.** If the metric is load-bearing, add a `recordFrameSize(int)` call inside `OutboundSender.drainLoop` after encoding. If not, add a `// METRIC: frame-size tracking removed in Phase 17; restore in M5 if needed` comment in `TickBroadcaster`.
7. **Add a `rebindSession` contract test.** Verify that after `BotRegistry.rebindSession(oldSession, newSession, entityId)`, `getBySession(newSession)` returns the entity and `getBySession(oldSession)` returns empty.

## Risk Assessment

**MEDIUM**

The architectural direction is correct and the research depth is high. The main risks are execution-level:

1. **Wave 2 compilation ordering** could stall an autonomous executor if it tries to compile Plan 03 before Plans 04/05 exist.
2. **The stalled-session gauge inaccuracy** will confuse operators and undermine the observability goal of SCALE-02.
3. **Integration test complexity** (blocking a real WS client to trigger backpressure) may cause Plan 11 to miss its verification deadline or require significant test-utility scaffolding not currently in the codebase.

None of these threaten simulation correctness — the core mechanics (cap, hysteresis, token expiry, VT isolation) are sound. But they create friction and require mid-wave fixes. The phase should be scheduled with buffer time for the integration tests and a final gauge-cleanup pass.

---

## Consensus Summary

Four reviewers (gemini, claude, codex, opencode/kimi-k2.6) agree that the phase architecture is sound — VT-per-session, token taxonomy, hysteresis, and migration plan are all the right shape — but three reviewers (claude, codex, opencode) flag the same execution-level lifecycle bugs. Risk verdicts: gemini LOW, opencode MEDIUM, claude MEDIUM-HIGH, codex HIGH. The dispersion comes from how seriously each reviewer treats the STALLED close-path / resume-token-lifecycle contradiction; gemini reads it as a fixable detail, codex reads it as correctness-breaking. Three independent reviewers landing on the same set of HIGH issues suggests they are real and worth a Plan 03/05/07 corrections pass before Wave 3 executes.

### Agreed Strengths

- **Clean wave decomposition + bean separation** (gemini, claude, codex, opencode) — `AdmissionConfig` / `AdmissionGate` / `OutboundSender` / `ResumeTokenRegistry` / `TickHealthMonitor` are the right components with narrow interfaces.
- **VT-per-session is idiomatic and correct for Java 21 + blocking-style code** (gemini, claude, codex, opencode); D-10 rationale documented in two places (Javadoc + CLAUDE.md) is praised.
- **D-07 token taxonomy locked early in Plan 01** removes string drift across consumers (claude, codex, opencode); Plan 10 migration cleanly deletes `PopulationCapConfig` (gemini, claude).
- **Hysteresis on tick-overload** is correctly identified as needing a dedicated rolling window, not Micrometer cumulative mean (gemini, claude, codex, opencode).
- **Backward compatibility for old bot clients** (missing token → fresh registration) is first-class (gemini, opencode).

### Agreed Concerns (priority order — multiple reviewers)

1. **HIGH — STALLED grace defeated by `afterConnectionClosed` reaping the entity.** (claude HIGH, codex HIGH, gemini MEDIUM). `cleanupBot(sessionId)` fires on every close, including the close that follows `E|408|reconnect-required`; the entity disappears before the resume-token grace window can recover it. Plan 07 step 4 needs an `isStalled` guard; reaping must move to `ResumeTokenRegistry.sweep()`.

2. **HIGH — `OutboundSender` overflow → `markStalled` fallback is racy AND non-idempotent.** (claude HIGH detach race, codex HIGH overflow callback reentrancy + sendFrame fallback, opencode MEDIUM detach race, opencode SUGGESTION fire-once guard). Three distinct bugs in the same code path: (a) `detachSession` doesn't `t.join()` so `synchronized(session)` fallback can race the dying VT — Jetty 12 `sendMessage` is not thread-safe; (b) repeated `offer()` failures fire `markStalled` repeatedly, issuing multiple tokens and spamming logs; (c) the explicit STALLED error frame may be enqueued behind a saturated queue and never delivered. Needs `AtomicBoolean overflowFired`, `t.join(100)` in detach, and synchronous-out-of-band delivery (or `session.close(reason)`) for the 408.

3. **HIGH — `BotRegistry.rebindSession` is hand-waved central state mutation.** (codex HIGH, claude MEDIUM, opencode MEDIUM, gemini LOW). Three reviewers want the rebind helper specified before Plan 07 lands — `bySession` / `entityToSession` swap must preserve position, death queues, and remap semantics, with a contract test (`getBySession(new)` returns entity, `getBySession(old)` returns empty).

4. **HIGH — Resume-token gauge counts armed tokens, not stalled sessions.** (codex HIGH, opencode MEDIUM). `paralife.backpressure.stalled.sessions = tokenMap.size()` over-reports because every alive bot has an armed token. Two-state model (ACTIVE / STALLED) or filtered gauge required; this directly undermines SCALE-02's operator-visibility goal. Codex extends this to a HIGH "live entities reaped after grace expiry" risk if registration mints expiring tokens.

5. **MEDIUM-HIGH — Codec disambiguator (Plan 02) is wrong / cross-plan drift.** (claude HIGH, codex MEDIUM). Claude: the `looksLikeEffectBlock` check uses the `f` BLOCK prefix from `TickFrame`, but `SyncFrame` effects are bare (`S:`, `I:`) per `15-SCHEMA.md` §6.2 vector 10 — disambiguator must be inverted to "second slot starts with `r:` ⇒ token, else effects." Codex: token format drift between Plan 01 (16-char hex) and Plan 02 (`r:` sentinel). Decide once in `17-ADMISSION.md` and propagate.

6. **MEDIUM — Wave 2 hidden compile dependency.** (opencode HIGH, claude MEDIUM). Plan 03 declares `depends_on: [17-01]` but compiles against `TickHealthMonitor` (17-04) and `ResumeTokenRegistry` (17-05). Either tighten metadata to `[17-01, 17-04, 17-05]` or document Wave 2 as a joint compile gate.

7. **MEDIUM — `TickHealthMonitor` samples the previous tick, not the current tick.** (codex MEDIUM, claude MEDIUM window-fill guard). Two related issues: (a) `TickEngine.lastTickWorkMs` is written after `publishEvent` returns, so a same-event listener reads the prior tick; (b) the rolling window evaluates `mean = sum / filled` even when `filled == 1`, so a single 200ms spike trips overload before the window is full.

8. **MEDIUM — `recordFrameSize` metric dropped without replacement.** (codex MEDIUM, gemini LOW, opencode LOW). Plan 08's "Option 1: drop" creates an operational regression. Move the metric into `OutboundSender.drainLoop` after encoding.

9. **MEDIUM — Plan 11 integration tests rely on undiscovered `BotClient` hooks.** (codex MEDIUM, opencode MEDIUM). Pausing `BotClient.onMessage` to drive queue overflow is aspirational; ship a `BlockingWebSocketClient` test utility instead, or shift to mock-session integration tests with one real-WS happy-path.

10. **MEDIUM — `ATTR_RESPAWN_COUNT` is not migrated on stall-rebind.** (claude MEDIUM, no other reviewers). Clients can stall-reconnect indefinitely to bypass the per-session respawn cap. Either copy via `ResumeEntry` or document the reset-on-rebind quirk.

11. **MEDIUM — `cleanupCallback` signature mismatch.** (claude MEDIUM). Plan 05 callback expects `Consumer<String> entityId`; Plan 07 wires `this::cleanupBot` which takes `sessionId`. Add a `cleanupByEntityId(String)` helper or change the callback contract.

12. **MEDIUM — `AdmissionGate` guard ordering.** (codex MEDIUM). Live session sending `r|C|<token>` should hit `409 already-registered` before resume-token rebind is considered.

### Divergent Views

- **Overall risk verdict:** gemini LOW vs codex HIGH. Worth investigating: is the STALLED grace contradiction one fixable detail (gemini) or a load-bearing correctness break (codex)? The fact that three reviewers independently flagged it suggests codex is closer to the truth — but a quick correction pass (~10–20 lines across Plan 05/07) would close the gap and bring everyone to LOW–MEDIUM.
- **Codec disambiguator approach:** gemini approves the `r:` sentinel as "clever, low-overhead"; claude says the implementing rule in Plan 02 is inverted; codex says the format drifts across plans. Settle in `17-ADMISSION.md` by locking `r:<16hex>` as the only token wire form and inverting the disambiguator to "second slot starts with `r:` ⇒ token."
- **`recordFrameSize` severity:** codex MEDIUM (operational regression), gemini/opencode LOW. Decide based on whether the metric is consumed today; if yes, restore in `OutboundSender`.
- **Synchronous fallback in `sendFrame`:** claude proposes `t.join(timeout)` to bound the race; codex argues the synchronous fallback should be eliminated entirely because it reintroduces tick-thread blocking under exactly the slow-client case the design is meant to prevent. The latter is stronger if the 408 must reach the client (close-with-reason is the alternative).

---

*Review completed: 2026-04-27*
*Models: gemini (default), claude (default), codex/gpt-5.5 (high reasoning), opencode/openrouter/moonshotai/kimi-k2.6 (--agent plan)*
*Feed back into planning via:* `/gsd-plan-phase 17 --reviews`
