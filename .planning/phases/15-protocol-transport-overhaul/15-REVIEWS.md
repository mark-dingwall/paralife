---
phase: 15
reviewers: [gemini, claude, codex]
reviewed_at: 2026-04-20T00:05:18Z
plans_reviewed:
  - 15-01-PLAN.md
  - 15-02-PLAN.md
  - 15-03-PLAN.md
  - 15-04-PLAN.md
  - 15-05-PLAN.md
  - 15-06-PLAN.md
  - 15-07-PLAN.md
  - 15-08-PLAN.md
  - 15-09-PLAN.md
  - 15-10-PLAN.md
  - 15-11-PLAN.md
---

# Cross-AI Plan Review — Phase 15: Protocol & Transport Overhaul

Three independent reviewers invoked via `/gsd-review --phase 15 --all` plus explicit `claude -p`. Full raw outputs below, followed by a consensus synthesis for the planner.

| Reviewer | Overall Risk | Top 3 Concerns |
|---|---|---|
| Gemini  | **LOW**    | Jetty/Spring adapter fragility · extended RED window · Vector 9 extended coords |
| Claude  | **MEDIUM** | Vector 9 unresolved · Messages.java strip sequencing · Jetty bridge speculative API |
| Codex   | **HIGH**   | Messages.java removed too early · Jetty path too complex · Vector 9 still ambiguous |

---

## Gemini Review

# Phase 15 Plan Review: Protocol & Transport Overhaul

## 1. Summary
The Phase 15 implementation plans provide a robust and highly detailed roadmap for transitioning Paralife from a JSON-based development protocol to a production-grade, compact text protocol with high-performance transport. The strategy is characterized by a "big-bang" replacement that rigorously enforces technical standards (D-33 fail-fast compression) and security mandates (zero-trust vision filtering). The decision to use hand-rolled index-based parsing (`ParseCursor`) over `String.split` or regex demonstrates a sophisticated understanding of Project Loom's performance characteristics in high-concurrency environments. The inclusion of procedural rock generation and rich Micrometer metrics elevates the phase from a simple refactor to a significant upgrade in simulation quality and observability.

## 2. Strengths
- **Performance Engineering:** The adoption of a single-pass LL(1) parser using a mutable index cursor (`ParseCursor`) and pre-sized `StringBuilder` encoders minimizes allocations, which is critical for maintaining the 100-bot tick cadence without GC pressure.
- **Strict Zero-Trust Model:** The protocol design goes beyond the initial requirements by hiding bonded-secondary types (`D/N/T` codes) and fully dropping entity IDs, ensuring that bot clients are truly reactive only to perceived local state.
- **Deflate Negotiation Rigor:** Programmatic enforcement of `permessage-deflate` with `server_no_context_takeover` on both server (via `WebServerFactoryCustomizer`) and client (via Jetty-native `WebSocketClient`) ensures optimal compression ratios without stateful memory leaks.
- **Resilient Respawn Flow:** The integration of a server-side respawn cap (E|429) with client-side jittered cooldowns provides a solid DoS defense while keeping bot populations stable.
- **TDD Integration:** Establishing the 13 SCHEMA §10 vectors as a RED/GREEN oracle in the very first wave (15-02) ensures the codec implementation remains strictly conformant to the specification.

## 3. Concerns
- **Jetty/Spring Session Bridging (MEDIUM):** In `15-03`, Task 2 introduces `JettyWorldWebSocketAdapter`. Manually instantiating `JettyWebSocketSession` can be fragile, as Spring's internal implementations of these adapters often change between minor versions. If the constructor is not easily accessible, the "bridge" logic might require more boilerplate than anticipated.
- **Extended RED Window (MEDIUM):** Because `15-06` strips/deletes `Messages.java` but the full test migration doesn't land until `15-11`, the build will remain in a "partially compilable" state for several waves. While expected in a "big-bang" rollout, this increases the difficulty of debugging regression bugs in the simulation core during the transition.
- **Vector 9 Extended Coordinates (LOW):** The 6-char relative coordinate in Vector 9 (`+0F-03`) deviates from the 4-char standard. While plan `15-05` includes a checkpoint for this, a hard requirement to support variable-length coordinates in specific event types (`L/N`) adds branching logic to the "simple" LL(1) parser.
- **Counter Accuracy (LOW):** As noted in `15-10`, the `paralife.ws.bytes.saved` metric is a 0.6x estimate. This is acceptable for a prototype, but may mislead users if actual compression ratios differ significantly due to specific RLE or bitmask patterns.

## 4. Suggestions
- **Refined Adapter Wiring:** In `15-03`, instead of manual `new JettyWebSocketSession(...)`, consider if `JettyRequestUpgradeStrategy.upgrade(...)` can be invoked within the `WebSocketCreator`. This would allow Spring to handle the session wrapping using its internal, version-safe mechanisms.
- **Vector 9 Schema Note:** Formally update `15-SCHEMA.md §2` to explicitly define "Extended Relative" coordinates (6-char) for events that exceed standard vision ranges, rather than relying on the checkpoint to "discover" the need.
- **Incremental Test Compilation:** In Wave 3/4, consider moving the test migration for `ActionResolverTest` earlier. Since `ActionResolver` is the heart of the physics loop, keeping its tests green throughout the protocol swap would provide a "safety island" during the long migration.
- **Metrics Documentation:** Explicitly tag the `paralife.ws.bytes.saved` metric with a `type="estimate"` tag in Micrometer so that future Prometheus dashboards can distinguish it from exact transport-layer byte counts.

## 5. Risk Assessment: LOW
The overall risk is low. The plans are exceptionally thorough, citing specific file lines and pre-existing patterns. The dependency chain (`15-01` through `15-11`) is logical, and the use of wave-based development with early "Wave 0" test oracles mitigates the risk of protocol drift. The most significant technical risk—the container swap—is addressed early in `15-03` with dedicated handshake integration tests, ensuring that the most complex infrastructure change is validated before the application logic is migrated. Accomplishment of all R20-R29 requirements is highly probable.

---

## Claude Review

# Cross-AI Plan Review — Phase 15

## Summary

The Phase 15 plan set is thorough, schema-grounded, and TDD-seeded — the 13-vector round-trip oracle is the right anchor. Wave dependencies are mostly sound, and zero-trust / D-33 contracts have automated tests. Three real concerns stand out: (1) Vector 9's coord ambiguity is deferred rather than resolved, (2) the `Messages.java` strip is sequenced inconsistently between 15-06 and 15-08, and (3) the Jetty12↔Spring bridge (15-03 Task 2, 15-09 Task 1) is partially speculative on concrete API surfaces. Risk is **MEDIUM** — the plans are executable but three or four implementation checkpoints are likely.

## Strengths

- **Schema-first**: 15-02 seeds the 13-vector RED test before any codec code; 15-05 turns it GREEN. Acceptance oracle is unambiguous.
- **Wave ordering** cleanly parallelises: codec scaffold (15-02) + container swap (15-03) + rock gen (15-04) are independent in Wave 1; projection (15-08) correctly waits on renamed broadcaster (15-07) + codec impl (15-05).
- **Zero-trust enforcement is both type-level and test-level**: CellEntry has no id field (compile-time), ZeroTrustFilteringTest greps encoded wire (runtime).
- **D-33 has both sides tested**: server-refusal test (15-03 Task 3) + client-close test (15-09 Task 3).
- **Tech debt consolidation**: Phase 09 items #3 (dead branch) and #4 (JsonNode/LinkedHashMap) are scheduled with specific tasks, not hand-waved.
- **Dot-separated metric names**: 15-10 overrides CONTEXT D-38's hyphenated names with the Prometheus-canonical form (RESEARCH Pitfall 7).
- **Respawn cap surfaced as first-class concern** with `MAX_RESPAWNS_PER_SESSION` + `E|429` + integration test.
- **RLE + env-supplement semantics** (Vector 13) explicitly tested; a subtle edge that encoders commonly get wrong.

## Concerns

- **HIGH — Vector 9 ambiguity not resolved, only deferred.** `v+0F-03L5` in SCHEMA §10 uses a coord form (`+0F-03` = 6 chars, 2-char-per-axis signed base64) that doesn't appear in §2. Plan 15-05 Task 1 prescribes "interpretation (A) — extended 6-char relative coords for L/N events" and says "document in … SCHEMA §2 via a separate follow-up task in this plan (see Task 2)" — but plan 15-05 Task 2 is the error test, not a schema edit. The schema stays inconsistent with the implementation. Either the schema needs a §2 note for "extended relative coords in v-block events with out-of-vision codes" OR Vector 9 itself needs a patch. The CHECKPOINT escape hatch is good but likely to fire, and the resolution path is unclear.

- **HIGH — `Messages.java` strip sequenced inconsistently between 15-06 and 15-08.** Plan 15-06 Task 2 Part C says "delete every wire-bound record" and its verify requires `! grep -rE "import com.paralife.websocket.Messages" src/main/java/` to pass. But the same task's migration table explicitly says `PerceptionBroadcaster.java` + `HeuristicBrain.java` + `BotClient.java` imports are "Migrated by plan 15-08 Task 1 / 15-09 Task 1/2". These are in Wave 5–6, after Wave 3. The verify command will fail against PerceptionBroadcaster — its `Messages.CellView` et al. still live at that point. Either Messages must be only partially stripped in 15-06 (keep CellView/Perception/EntityState/CompositePerception until 15-08 migrates consumers) with a corrected verify, or the strip moves to end of Wave 5.

- **HIGH — `JettyWorldWebSocketAdapter` (15-03 Task 2) uses speculative API.** The sketch has `new JettyWebSocketSession(Map.of())` with a wiring comment "exact JettyWebSocketSession wiring is Jetty-version-specific". This is the bridge between Jetty's native creator path and Spring's `WorldWebSocketHandler`. If the BOM-pinned constructor surface doesn't match, the whole plan-3-Task-2 chain must be re-drafted, and plans 15-06/15-08 depend on it running. No fallback spec — the "use JettyRequestUpgradeStrategy instead" alternative is noted in RESEARCH but not kept as a committed fallback in the plan.

- **MEDIUM — Spring's `registerWebSocketHandlers` + Jetty's native `addMapping` both target `/ws/world`.** `WebSocketConfig.java` retains Spring's handler registration; `JettyDeflateCustomizer` adds a native creator. In Jetty 12, double-binding the same path surfaces as either "last writer wins" (whichever hooks later overrides) or a boot-time conflict. The plan does not verify which path actually serves traffic — the handshake test (15-03 Task 3) would pass if EITHER path advertises deflate, but only one of the two registrations handles frame decode downstream. Risk of an orphaned path or a silent no-op on the Spring side.

- **MEDIUM — `paralife.ws.bytes.saved` Counter records a fabricated 0.6 estimate.** Plan 15-10 Task 2 ships `long estSaved = Math.round(rawBytes * 0.6)` into a Counter named `bytes.saved`. The name implies measurement; the metric is a constant-ratio fabrication. Downstream consumers (dashboards, alerts) will treat this as ground truth. Either rename to `paralife.ws.bytes.saved.estimated` OR ship only `tick.frame.bytes` this phase and defer bytes-saved until Jetty exposes per-frame post-deflate length (RESEARCH calls this out explicitly — plan 15-10 doesn't take the "defer" path it enumerates).

- **MEDIUM — DoS sentinel asserts elapsed time, not bounded output.** `largeInputRejectedQuickly` (15-05 Task 2) checks that a 100KB `"x".repeat(100_000)` parse completes in < 500ms. `x` is valid base64 (value 33), so the parser will march through until structural failure. A well-formed but pathologically deep frame (e.g. a `s` block with 100K cell entries) would pass the 500ms budget but produce a huge in-memory `List<CellEntry>` that could OOM. Missing: max-entries cap per block + a test that a structurally-valid oversized frame is rejected.

- **MEDIUM — IRV test in 15-06 Task 3 assumes `ActionResolver.resolveLocomotorVote(List<String>)` is static/callable from test code.** Current method is package-private. Plan 15-06 Task 2 changes the signature but doesn't explicitly say it becomes `static` or stays instance. Test calls `ActionResolver.resolveLocomotorVote(ballots)` — works only if static or if the test constructs a resolver. Easy fix; flagged so executor doesn't stall.

- **MEDIUM — `Frame.TickFrame.sensorRadius = 0` overloaded to mean "minimal form".** SCHEMA §6.3 defines sensorRadius as 1 base64 char with values `1`/`2`/`3`. Using value 0 as an in-memory sentinel for "minimal form" conflates wire values with record semantics. Codec must special-case both on encode (omit slot) and decode (detect minimal-form frame inventory by peeking). Plan 15-05 does address this but the convention is fragile and undocumented in the Frame record's javadoc.

- **MEDIUM — Alarm queue placement unspecified between 15-06 and 15-08.** Plan 15-06 Task 2 says verb `L` dispatches to a composite lookup and "broadcaster handles" the `vN<coord>` emission. Plan 15-08 Task 2 adds the queue to `TickBroadcaster`. In between (Wave 4), the verb `L` dispatch exists but has nowhere to enqueue. If any test runs during the inter-wave window, `L` is a silent no-op.

- **LOW — Respawn cap hardcoded.** `MAX_RESPAWNS_PER_SESSION = 5` is a magic number in 15-06. CONTEXT flags "per-session respawn cap counter compared against a config" — the plan ships a hardcoded constant and defers configurability. Acceptable for MVP, but makes load tests harder to tune.

- **LOW — `ZeroTrustFilteringTest` grep assertions are coarse.** `wire.contains("SPORE")` asserts the full word, not a spurious `S` char. `wire.contains("D")` asserts presence of the kind code — but `D` appears in tick IDs, expiry ticks, etc. The assertions happen to pass under the current construction, but a later frame that encodes a timestamp containing `D` could false-positive. Tighten to regex-anchored cell-entry parsing.

- **LOW — Rock generator id uniqueness.** `"rock-" + x + "-" + y` is unique across cells within one run. But if `@PostConstruct` fires twice under Spring reloads or tests that reset the context, id collisions are possible. Probably fine under production but worth a `static final` guard or UUID suffix.

- **LOW — `JettyWebSocketSession` wrapper in `JettyWorldWebSocketAdapter` stores a `Map<Session, WebSocketSession>` but never clears on error.** Plan handles close but not transport error → slow leak across bot respawns. Minor.

## Suggestions

1. **Resolve Vector 9 NOW, before implementation starts.** Either add a SCHEMA §2 note for extended 6-char relative coords used in out-of-vision event contexts (codes `L`/`N`), or patch vector 9 to `T|001|0A1B|15/80|2|fF:2E:0F03|v+0F-0L5` (4-char relative). Commit the decision to `15-SCHEMA.md` and update round-trip vectors before plan 15-05 runs.
2. **Reframe the Messages.java strip.** Plan 15-06 Task 2 Part C should strip only the records whose consumers are already migrated (Welcome, Registered, Heartbeat, ActionResult, Action, Register, Tick heartbeat, CompositeAction/Joined). Retain CellView, Perception, EntityState, CompositePerception until plans 15-08/15-09 migrate their consumers. Final deletion moves to a new sub-task in 15-09 or a 15-12 cleanup task. Verify command in 15-06 narrows to the stripped subset.
3. **Commit to ONE Jetty wiring path.** Either "Jetty native creator + custom adapter" (current path) OR "Spring JettyRequestUpgradeStrategy + extension customizer". Prototype both in a spike before 15-03 Task 2 and bake the choice into the plan. Keep the alternative as a documented fallback with bounded scope for when the BOM pin doesn't match the sketch.
4. **Validate the double-registration at `/ws/world`.** Add a startup-time assertion: at bean-ready time, log which path (Spring handler vs Jetty native creator) serves `/ws/world`. Add a quick integration check that a frame sent over the upgraded connection actually reaches `WorldWebSocketHandler.handleTextMessage`.
5. **Rename or defer `bytes.saved`.** Either `paralife.ws.bytes.saved.estimate` with a clear javadoc + description field OR drop the Counter this phase and ship only `tick.frame.bytes` + `active.sessions`. Fabricated Counter values contaminate downstream observability.
6. **Add a bounded-entries DoS test.** Construct a structurally-valid `s` block with 10_000 cell entries, confirm decoder rejects (max-entries cap) OR that the resulting `List<CellEntry>` is bounded by a documented limit. The current elapsed-time test doesn't catch exhaustion attacks.
7. **Declare IRV test helper shape in 15-06 Task 2.** Explicitly make `resolveLocomotorVote` either `static package-private` or expose via a small test harness class. The test-as-written assumes static.
8. **Document the sensorRadius=0 sentinel** in `Frame.TickFrame` javadoc and in codec encode/decode comments. Add an invariant check in the canonical ctor: "sensorRadius in {0,1,2,3}; 0 denotes minimal form per §6.3.2."
9. **Sequence alarm wiring atomically in 15-06 or 15-08, not split.** Either add the alarm queue to `TickBroadcaster` in 15-06 (even as a no-op sink) so the verb dispatch has a target, or defer the verb `L` dispatch to 15-08 where the queue lands.
10. **Add per-block max-entries constants to `PerceptionCodec`** (e.g. `MAX_S_ENTRIES = 256`, `MAX_V_ENTRIES = 32`). Enforce in decode; document in Frame javadoc. This is the V5 backstop.

## Risk Assessment

**Overall: MEDIUM.**

Justification: The plan set is well-structured and the core machinery (codec, handler, broadcaster, bot) is clearly specified with executable verify commands. However, three items are likely to cause iteration loops — Vector 9 will hit the CHECKPOINT (high confidence), the Messages.java strip will fail its verify command in 15-06 (high confidence), and the Jetty bridge adapter will need API-level adjustment when the BOM-pinned classes don't match the sketch (medium confidence). None of these are showstoppers, but they represent three predictable stalls that a tighter pre-execution pass could eliminate. Security posture (zero-trust, D-33, DoS) is well-covered in principle with minor tightening needed. Protocol correctness is high modulo Vector 9. Performance budget for per-tick codec encoding is adequate (StringBuilder + single-pass LL(1), no split/regex). The metric name fabrication and the coarse zero-trust assertions are quality issues, not correctness risks.

Collectively, the plans achieve R20–R29 if the Vector 9 and Messages.java sequencing are resolved pre-flight. As-written, executor throughput will be reduced by ~15–25% due to predictable checkpoint loops — recoverable, not fatal.

---

## Codex Review

## Summary

The plan set is unusually strong on schema discipline, traceability, and test intent: `15-SCHEMA.md`, the 13-vector round-trip oracle, and per-plan verification commands give Phase 15 a solid acceptance framework. The main weaknesses are foundational rather than local: the `Messages.java` removal sequence is out of order, the Jetty integration strategy is too complex and likely conflicting, and vector 9 is still ambiguous despite the schema being declared locked. If those three issues are corrected, the rest of the plan stack is credible and broadly covers R20-R29; without them, execution risk is high.

## Strengths

- Clear requirements fan-out: R20-R29 are concrete and map cleanly to plans 15-01 through 15-11.
- Strong protocol-first discipline: `15-SCHEMA.md` plus the 13 byte-exact round-trip vectors is the right foundation for a custom wire format.
- Good separation of concerns: codec, transport swap, broadcaster rewrite, bot rewrite, metrics, and test migration are decomposed sensibly.
- Security thinking is present throughout: zero-trust filtering, explicit deflate negotiation, fail-fast downgrade rejection, and dedicated zero-trust tests.
- The pure-Java `com.paralife.codec` package is well scoped and keeps Spring/Jackson out of the hot path.
- Preservation of the vision-scoped OVERCROWDED mask-and-OR logic is correctly treated as load-bearing.
- Validation quality is better than average: most plans include concrete automated checks rather than vague "run tests".
- The phase keeps observer/fan-out work out of scope; that avoids a major source of milestone drift.

## Concerns

- [HIGH] `Messages.java` is removed too early. Plan 15-06 strips/deletes it while main-source classes still depending on it are explicitly deferred to 15-08 and 15-09. That makes the claimed `./gradlew compileJava` success in 15-06 internally inconsistent.
- [HIGH] The Jetty path in 15-03 is over-complex and under-validated. Keeping Spring's `WebSocketHandlerRegistry` mapping on `/ws/world` while also adding a native Jetty `addMapping("/ws/world", ...)` plus a custom Spring-session adapter is a likely runtime conflict and a major implementation risk.
- [HIGH] Vector 9 is still unresolved even though the schema is marked "LOCKED". Deferring `v+0F-03L5` to implementation-time interpretation undermines the round-trip oracle and the LL(1) parser claim.
- [HIGH] Bot type/state semantics are muddled after transitions. `HeuristicBrain` wants species-level information, but the plans reuse state-change codes like `D/N/T` and `0-5` as `currentType`, which conflates species, bonded state, and composite role.
- [MEDIUM] Authority-lite behavior is inconsistent across artifacts. The schema allows FEEDER/ATTACKER/REPRODUCER actions, 15-06 wires server verbs for them, but research text says client-side submission is deferred and 15-09 focuses mostly on solo logic.
- [MEDIUM] DoS protection is only partially specified. There are malformed-input and "bomb" tests, but no explicit codec/handler caps on frame length, block entry counts, duplicate tagged blocks, or repeated large segments.
- [MEDIUM] There is no real performance gate for the new encode+deflate path. With `server_no_context_takeover`, every tick frame is compressed cold, and every frame is per-session unique; the plans rely on correctness tests more than CPU/allocation/tick-drift validation.
- [MEDIUM] The bytes-saved metric is weakly defined. A hardcoded 60% estimate will make the metric reachable, but not trustworthy, and it risks becoming dashboard noise.
- [MEDIUM] The metrics integration test proves endpoint exposure more than actual wiring; priming the bean directly does not verify that `SessionRegistry` and `TickBroadcaster` truly drive the meters end-to-end.
- [LOW] Some plan metadata drifts from task bodies (`JettyWorldWebSocketAdapter`, `BotRegistry` edits, renamed tests), which increases execution friction.
- [LOW] Rock generation is fine for MVP, but rotation/flip correctness, startup failure behavior for missing PNGs, and actual "Perlin-like" asset quality are not strongly validated.

## Suggestions

- Resolve vector 9 before implementation. Update `15-SCHEMA.md` and the test vectors first; do not leave a "locked but maybe reinterpret" hole in the core grammar.
- Reorder the migration so `Messages.java` is deleted last, or keep a temporary compatibility shim until broadcaster, bot, brain, and tests are fully moved.
- Simplify 15-03 to one server integration strategy. Prefer either Spring's Jetty upgrade path or native Jetty mapping, not both on the same endpoint.
- Split bot state into distinct concepts: species, embodiment/state, and composite role. Do not overload `currentType` with transition codes.
- Decide explicitly whether authority-lite members submit actions in Phase 15. If yes, add end-to-end client behavior and tests; if no, remove/mark the server verb support as deferred.
- Add explicit parser limits: max frame bytes, max `s`/`v`/`g` entries, duplicate-tag rejection, and reserved-bit validation.
- Add at least one performance gate after 15-08/15-09: a load/integration test that asserts tick drift and/or CPU budget under 100 bots with deflate enabled.
- Make the metric either exact or obviously approximate. If exact post-deflate bytes are unavailable, document it in the meter name/description and avoid implying precision.
- Strengthen 15-10's test to drive one real session register/unregister and one real tick send so meter updates are behaviorally verified, not only exposed.
- Add codec edge-case tests for minimal `T` frames with no trailing `v`, out-of-order/duplicate optional blocks, illegal reserved presence bits, and large but syntactically valid lists.

## Risk Assessment

**HIGH**

The phase is well designed conceptually, but there are three foundational execution risks: broken sequencing around `Messages.java`, an overly risky Jetty/Spring integration plan, and a supposedly locked protocol that still has an unresolved coordinate ambiguity. Those issues sit on the critical path for nearly every later plan, so they can stall or destabilize the whole phase even though the remainder of the design is strong.

---

## Consensus Summary

Three reviewers agree on the plan's design intent but disagree on severity. Gemini rates risk LOW, Claude MEDIUM, Codex HIGH — because Gemini treats each foundational issue as a known checkpoint with mitigations, while Codex treats the same issues as critical-path blockers. Claude sits between: predictable stalls, not showstoppers. Planner should weigh the pattern of agreement over the summary verdict.

### Agreed Strengths (2+ reviewers)

- **Schema-first TDD via 13-vector round-trip oracle** (all 3) — correct anchor, correct sequence (RED in 15-02 → GREEN in 15-05).
- **Zero-trust enforcement** both compile-time (CellEntry has no id) and runtime (ZeroTrustFilteringTest) (Gemini, Claude, Codex).
- **D-33 fail-fast deflate negotiation** tested on both server and client (Gemini, Claude).
- **Wave decomposition** is sensible — codec/container/rock-gen independent; projection correctly gated (Gemini, Claude, Codex).
- **Pure-Java codec package** keeps Spring/Jackson off the hot path; LL(1) + StringBuilder performance-conscious (Gemini, Codex).
- **Verification quality** — concrete `grep`/gradle commands, not vague "run tests" (Codex, Claude).
- **Respawn cap + E|429 DoS defense surfaced as first-class** (Gemini, Claude).

### Agreed Concerns (2+ reviewers — highest priority)

1. **[HIGH — unanimous] Vector 9 `v+0F-03L5` coordinate form is unresolved.** Schema §10 uses 6-char extended relative coords not defined in §2. Plan 15-05 defers resolution to implementation-time via a CHECKPOINT. All three reviewers want this locked pre-execution: either patch SCHEMA §2 to define extended coords for `L`/`N` out-of-vision events, or patch vector 9 to use the standard 4-char form. Leaving this ambiguous contradicts the "schema LOCKED" claim and will trigger the checkpoint with high probability.

2. **[HIGH — unanimous] `Messages.java` strip sequencing in Plan 15-06 is inconsistent with Waves 5–6.** Plan 15-06 Task 2 Part C's verify command (`! grep -rE "import com.paralife.websocket.Messages" src/main/java/`) cannot succeed while `PerceptionBroadcaster`, `HeuristicBrain`, and `BotClient` still import from `Messages` — their migration is explicitly deferred to 15-08/15-09. Either:
   - partial strip in 15-06 (keep CellView / Perception / EntityState / CompositePerception until consumers migrate) with a narrowed verify, or
   - move final deletion to end of Wave 5 / a new 15-09 cleanup task or 15-12.

3. **[HIGH/MEDIUM — unanimous] Jetty/Spring dual-registration on `/ws/world` is fragile.** Plan 15-03 Task 2 sketches a `JettyWorldWebSocketAdapter` wrapping `JettyWebSocketSession(Map.of())` with a disclaimer that wiring is "Jetty-version-specific". Meanwhile Spring's `WebSocketConfig` keeps its own `/ws/world` registration. Reviewers split on whether this causes a runtime conflict (Codex: HIGH conflict) or just API fragility (Gemini/Claude: MEDIUM). All three recommend picking ONE wiring path (Spring `JettyRequestUpgradeStrategy` OR native Jetty creator), prototyping it in a spike, and committing before executing 15-03 Task 2. Add a startup-time assertion that confirms which path actually serves traffic.

4. **[MEDIUM — 3 reviewers] `paralife.ws.bytes.saved` Counter fabricates a 0.6× estimate.** All three flag this: a Counter named "bytes.saved" that stores `rawBytes * 0.6` will be read by downstream dashboards as ground truth. Options: rename to `…bytes.saved.estimate` + a description tag, OR defer the metric this phase and ship only `tick.frame.bytes` + `active.sessions` (RESEARCH already enumerates the defer option).

5. **[MEDIUM — 2 reviewers] DoS protection lacks bounded-output caps.** Claude + Codex: the `largeInputRejectedQuickly` test asserts elapsed time (<500ms) against a 100KB input of valid base64 chars, but a structurally-valid frame with 10K `s`-block entries could pass the time budget while producing a List large enough to OOM. Missing: explicit `MAX_S_ENTRIES` / `MAX_V_ENTRIES` caps in `PerceptionCodec` plus a bounded-entries test.

6. **[MEDIUM — 2 reviewers] Metrics integration test proves exposure, not wiring.** Claude + Codex: priming the bean directly in `WebSocketMetricsTest` does not verify that `SessionRegistry` / `TickBroadcaster` actually drive the meters end-to-end. Strengthen with one real session register/unregister + one real tick send.

7. **[MEDIUM — Codex unique] No performance gate for encode + deflate path under `server_no_context_takeover`.** Every frame is compressed cold; no load/tick-drift test gates CPU or allocation budget under 100 bots. Codex recommends adding a performance gate after 15-08/15-09 before closing the phase.

### Divergent Views

| Topic | Gemini | Claude | Codex | Planner's call |
|---|---|---|---|---|
| **Overall risk** | LOW — the plans are "exceptionally thorough" and checkpoints are sufficient | MEDIUM — predictable ~3 iteration loops, ~15–25% executor throughput loss | HIGH — three foundational issues on the critical path | Lean toward MEDIUM: Gemini underweights the HIGH-severity sequencing issues Claude/Codex identify concretely |
| **Vector 9 extended coords** | LOW — "adds branching logic to the LL(1) parser" but checkpoint will catch | HIGH — schema inconsistency undermines the round-trip oracle | HIGH — contradicts "schema LOCKED" claim | HIGH — lock pre-execution |
| **Messages.java strip** | MEDIUM — "extended RED window" but expected in big-bang rollout | HIGH — verify command will demonstrably fail in 15-06 | HIGH — claimed `compileJava` success is internally inconsistent | HIGH — fix the verify command scope before 15-06 runs |
| **Bot type/state muddle** | not raised | not raised | HIGH — `currentType` conflates species, bonded state, composite role | Worth investigating — split species from embodiment/role before 15-09 implementation begins |
| **Authority-lite client submission** | not raised | not raised | MEDIUM — schema allows it, 15-06 wires server verbs, 15-09 focuses on solo | Clarify scope: does Phase 15 deliver authority-lite action flow, or defer? Align 15-06, 15-08, 15-09 |
| **sensorRadius=0 sentinel** | not raised | MEDIUM — fragile in-memory vs wire-value overload | not raised | Document in Frame javadoc; add canonical-ctor invariant |
| **Alarm queue placement (verb L)** | not raised | MEDIUM — silent no-op between Wave 4 and Wave 5 | not raised | Move queue creation earlier OR gate verb L dispatch |
| **IRV test helper shape** | not raised | MEDIUM — method visibility ambiguity | not raised | Mark `resolveLocomotorVote` as static package-private explicitly |
| **Rock generator MVP quality** | not raised | LOW — id collision under Spring reload | LOW — rotation/flip correctness, missing PNG behavior unvalidated | Add startup failure test for missing PNGs; id-collision mitigation optional |

### Recommended Pre-Execution Fixes

Before running `/gsd-execute-phase 15`, address at minimum the HIGH-severity consensus items (1), (2), (3) above. These three sit on the critical path for plans 15-05, 15-06, 15-08, 15-09 — fixing them pre-flight eliminates the predictable checkpoint loops all three reviewers anticipate. The MEDIUM items (4)–(7) can be addressed as plan edits during execution or as a 15-12 follow-up task.

To incorporate this feedback into the plans:
```
/gsd-plan-phase 15 --reviews
```
