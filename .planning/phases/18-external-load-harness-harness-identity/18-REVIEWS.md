---
phase: 18
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-04-28T07:05:00Z
plans_reviewed:
  - 18-01-PLAN.md
  - 18-02-PLAN.md
  - 18-03-PLAN.md
  - 18-04-PLAN.md
  - 18-05-PLAN.md
  - 18-06-PLAN.md
models:
  gemini: gemini-2.5-pro (3.1-pro-preview/3-flash-preview both 429 MODEL_CAPACITY_EXHAUSTED)
  claude: opus --effort xhigh
  codex: gpt-5.5 reasoning_effort=high
  opencode: openrouter/moonshotai/kimi-k2.6
round: 2 (post-replan; supersedes earlier 18-REVIEWS.md from 2026-04-28T06:12:21Z)
---

# Cross-AI Plan Review — Phase 18 (Round 2)

## Gemini Review

# Plan Review: Phase 18 - External Load Harness & Harness Identity

## 1. Summary

The implementation plans for Phase 18 are exceptionally well-structured, detailed, and robust. They demonstrate a thorough understanding of the requirements (SCALE-03, SCALE-04, SCALE-05) and provide a clear, phased execution path. The incorporation of feedback from the initial cross-AI review has been exemplary; all identified high and medium-severity concerns have been addressed through thoughtful amendments, significantly de-risking the phase. The plans now present a low-risk, high-confidence path to delivering a scalable load harness with end-to-end identity attribution, while respecting existing project architecture and conventions.

## 2. Strengths

The plans exhibit numerous strengths, making them a model for phase execution:

- **Systematic Decomposition:** The breakdown into six plans across four waves is logical and enforces dependencies correctly. Foundational work on client-side identity (Plan 01) and server-side metrics APIs (Plan 03) is completed in Wave 1, enabling parallel work and clean integration in subsequent waves.
- **Robustness and Safety:** The design excels in defensive programming. The multi-layered approach to cardinality management (client-side validation in `BotIdentity`, server-side taxonomy filtering, and a `MeterFilter` cap in `AdmissionMetrics`) provides a costless and effective safety net against metric-explosion DoS (T-18-02).
- **Correctness by Construction:** The plans systematically address subtle but critical failure modes. The "STALLED-pivot" attribution loss (T-18-04) is mitigated by design in Plan 01 (`BotClient` re-sends headers) and locked by a dedicated integration test (`AttributionRebindTest`) in Plan 06. Likewise, amendments to Plan 03 to handle gauge lifecycle and idempotency prevent silent metric drift.
- **Excellent Test Coverage:** Every significant behavior is paired with a specific test, from unit-level validation of Picocli converters to complex integration tests for STALLED-rebind lifecycle. The addition of `ListAppender` tests to lock log-marker format contracts is a significant improvement over brittle `grep` checks.
- **Clear Documentation and Forward Compatibility:** The plans treat documentation (`18-HARNESS.md`, `CLAUDE.md`) as a key deliverable, ensuring Phase 21 can build upon this work. The `BotFactory` seam (Plan 04) is a textbook example of correctly-sized forward compatibility, enabling future work (backlog 999.2) without over-engineering.

## 3. Concerns

The amended plans have successfully resolved all original high-severity concerns. The remaining concerns are minor execution details rather than design flaws.

- **LOW - Manual Test Refactoring:** Plan 18-03 correctly identifies that the `AdmissionMetrics` constructor change is a breaking change for tests that instantiate it directly. The plan relies on the executor to "audit all test sites and update". While correct, this manual step carries a small risk of a missed update, which would only be caught during a full `./gradlew test` run.
- **LOW - Concurrency Complexity in `AttributionTagger`:** The implementation for `foldHarnessIfOverCap` in Plan 18-03 uses `putIfAbsent` and a potential `remove` to handle concurrent registration attempts at the cardinality cap boundary. While the logic appears sound, this is a complex concurrent pattern. For the current use case (internal load harness), the risk is negligible, but it's a piece of code that would warrant significant stress testing in a more hostile, multi-tenant environment.

## 4. Suggestions

The plans are already in excellent condition. The following are minor suggestions to further harden execution:

- **Explicitly Identify Test Refactoring Sites (Plan 18-03):** To mitigate the manual risk of the `AdmissionMetrics` constructor change, the plan could be enhanced with a `verify` step that runs `grep -l 'new AdmissionMetrics(' src/test/java/` to list the files that the executor must update. This turns an implicit audit task into an explicit checklist.
- **Document `BotLauncher` Deletion/Deprecation Decision:** Plan 18-04's action to either delete or deprecate `BotLauncher.java` is contingent on the output of a `grep` command. The plan should instruct the executor to document the outcome (e.g., "Deleted `BotLauncher.java` as no external dependencies were found") in the `18-04-SUMMARY.md` for future reference.

## 5. Risk Assessment

**LOW.**

The initial MEDIUM-HIGH risk assessment was driven by plan contradictions, ownership conflicts, and subtle logic bugs (e.g., metric drift, report header loss). The amended plans have systematically and effectively addressed every one of these points.

- **Plan Conflicts Resolved:** Ownership of `AdmissionGate.java` is now cleanly assigned to Plan 02, and the `TICK-HEALTH` scalar invariant is consistently enforced.
- **Logic Bugs Fixed:** The per-bucket gauge lifecycle, JSON report overwrite behavior, and `BotFleet` counter semantics have all been corrected and locked with specific tests and documentation notes.
- **Invariants are Test-Locked:** The most critical risk, silent attribution loss on rebind (T-18-04), is now directly covered by `AttributionRebindTest`.

The remaining risks are procedural and well-contained. The phase is grounded in solid research, leverages existing project patterns correctly, and has a comprehensive validation strategy. Execution can proceed with high confidence.

---

## Claude Review

# Phase 18 Plan Review — Round 2 (Post-Replan)

## 1. Summary

Plans absorbed Round-1 reviewer feedback well. AdmissionGate ownership consolidated to Plan 02, TICK-HEALTH scalar locked via `ListAppender` test, `--duration` int-seconds, ReportSnapshot.merge for overwrite-mode, BotFleet idempotent shutdown, BotIdentity invariant tightened. Bucket-keying race fixed by co-locating overflow folding inside `AttributionTagger`. Snapshot map (`bucketTagsByEntityId`) wires no-session decrement paths.

Remaining issues concentrate in Plan 03 (markStalled ordering creates a snapshot-loss bug; rebind-path inc/dec prose ambiguous) and a couple of file-list omissions in Plan 05. None are architectural — all mechanical fixes during execute. Phase risk drops to **MEDIUM** from the Round-1 MEDIUM-HIGH.

## 2. Strengths

- **Plan 03 bucket-keying race fix** — folding moved into `AttributionTagger.tagsFor` so map and registry share Tags. Includes the 100-id sum-equality lock test. Properly locked.
- **Plan 03 every-release-path test** — `AdmissionMetricsLifecycleTest` covers all six paths (graceful, stalled-hold, stalled-expiry, rebind, post-placement-reject, duplicate-close). Strong regression net.
- **Plan 02 TICK-HEALTH scalar lock via ListAppender** — Codex+Claude amendment good. Brittle grep replaced with assert-no-`source=`-on-TICK-HEALTH-line. Survives format drift.
- **Plan 04 paired-atomic peak** — `liveCount`+`highWater` shape, with `currentRegistered()` Javadoc explicitly flagging STALLED-pivot drift and pointing at server-side gauge as authoritative. Honest engineering doc.
- **Plan 01 BotIdentity invariant** — symmetric source⇔harnessId enforced in compact ctor; normalization on every construction path; ASCII control-char rejection broader than just CR/LF. Codex amendment fully absorbed.
- **Plan 06 Awaitility 5s budget + verified `getActiveSessions()`/`markStalled` public** — flake risk managed; no test-only accessor needed.
- **Plan 05 ReportSnapshot.merge** — header retention bug (OpenCode catch) fixed cleanly with explicit factory.
- **Wave ordering serializes WorldWebSocketHandler edits** — Plan 03 (Wave 1) wires inc/dec, Plan 02 (Wave 2) reads post-state. Safe under sequential wave execution.

## 3. Concerns

### HIGH

- **Plan 03 markStalled ordering bug — snapshot lost.** `incStalledBucket(session)` snapshots tags via `session.getAttributes().get("entityId")`. But Phase 17 `markStalled` does `Object entityIdObj = attrs.remove(ATTR_ENTITY_ID);` near the top (line 481). If `incStalledBucket` runs AFTER that remove, `eid == null` → `bucketTagsByEntityId.put` skipped → grace-expiry reaper has no Tags → silent stalled-bucket drift (the exact bug Plan 03 was supposed to fix). Plan must mandate: call `incStalledBucket(session)` BEFORE `attrs.remove(ATTR_ENTITY_ID)`, OR pass `entityId` explicitly: `incStalledBucket(session, entityId)`.

### MEDIUM

- **Plan 03 rebind-path prose misleading.** Plan says "the existing `incActiveBucket(session)` above wires the NEW session's active bucket." But rebind branch returns early before reaching the Allow path's inc — and active bucket was never decremented at STALLED time, so no inc needed. Test `stalledRebindDecrementsStalledIncrementsActive` expects active==1 which already holds without inc. Implementer may add a redundant inc and double-count. Fix prose: "rebind path decrements stalled bucket only; active bucket stays incremented from the original Allow."

- **Plan 05 BotFleet.java edit not in files_modified.** Step 2 edits `BotFleet.shutdown()` for idempotency (`shutdownDone.compareAndSet`) but `BotFleet.java` isn't listed. Either add it or move idempotency to Plan 04 (cleaner — property of BotFleet, not LoadHarness). Recommend Plan 04.

- **Plan 03 OutboundSender edit underspecified.** OutboundSender listed in files_modified but no line numbers / call-site spec for what to delete or replace. The legacy `setStalledSessions` caller path needs explicit guidance — likely just deleting the call, since per-bucket inc/dec lives in `WorldWebSocketHandler.markStalled` now.

- **Plan 05 shutdown hook leak in tests.** `Runtime.getRuntime().addShutdownHook(...)` is called in `runInternal()`. Tests calling `runInternal()` accumulate hooks across test runs. Need `Runtime.getRuntime().removeShutdownHook(hook)` in a finally or after exitLatch resolves to keep test JVM clean.

- **Plan 03 AttributionTagger overflow-folding race window.** Concurrent first-time observers of harness ids 64 and 65: both pass `containsKey` check (false), both `putIfAbsent` succeed, both `incrementAndGet` race. One returns harness id, one rolls back to overflow. Mostly fine but the rollback's `remove(harnessId, TRUE)` and `decrementAndGet` aren't atomic together — a third concurrent observer could see `observedCount > maxCardinality` briefly. Use a single mutex around the slot-claim, or accept the tiny race (same id always returns same answer; only the boundary id may flip between "kept" and "overflow" across observers in a narrow window). Document or fix.

### LOW

- **Plan 05 LoadHarness `validateAndDefault` mutates Picocli-injected fields.** Works but unusual. Picocli's `defaultValue` could be set on the `@Option` to do the auto-generation declaratively (`defaultValue = "${PARALIFE_HARNESS_HARNESS_ID}"` then null-check in run is fine, but generation belongs after parse — current shape is OK, just non-idiomatic).

- **Plan 02 `closeReason="stalled-held"` introduces new token outside D-14 taxonomy.** D-14 specifies `reason=<token|graceful>`. Plan 02 emits `stalled-held` for the `wasStalled` close branch. Either update 18-HARNESS.md §5 to document `stalled-held` as a valid token, or normalize to `graceful` (loses signal). Recommend documenting.

- **Plan 04 SpeciesMix.pickFor weighted-mode boundary test missing.** Test covers parsing but not the position-based partitioning correctness. With `0.4:0.3:0.3` and 10 bots, expected 4 C / 3 M / 3 S — add an assertion.

- **Plan 06 LoadTest migration doesn't gate on `paralife.admission.attribution.max-harness-cardinality`.** LoadTest uses 1 harness id; well within cap. No issue today but if Phase 21 reuses LoadTest infrastructure with multiple ids, no protection. Comment-document that LoadTest is single-harness.

- **Plan 05 dry-run smoke check timing not asserted.** `--help` < 1s startup is documented as a Pitfall 5 invariant but no assertion. Spring banner check is also manual. Could add: `time java -jar ... --help 2>&1 | grep -v "Spring Boot" | wc -l` style guard. Acceptable as manual.

## 4. Suggestions

- **Plan 03 markStalled fix.** Specify call ordering explicitly:
  ```java
  // BEFORE attrs.remove(ATTR_ENTITY_ID):
  if (admissionMetrics != null) admissionMetrics.incStalledBucket(session);
  attrs.put(ATTR_STALL_TICK, stallTick);
  Object entityIdObj = attrs.remove(ATTR_ENTITY_ID);
  // ...
  ```
  OR change signature to `incStalledBucket(WebSocketSession session, String entityId)` and pass entityId explicitly.

- **Plan 03 rebind prose.** Rewrite as: "Rebind decrements OLD stalled bucket via `decStalledBucketByTags(lookupBucketTags(rebind.entityId()))`. Active bucket NOT modified — it stayed incremented during STALLED hold."

- **Move BotFleet idempotency to Plan 04.** Cleaner — property of fleet abstraction. Plan 04 already owns BotFleet.java.

- **Plan 03 OutboundSender spec.** Add concrete delete-this-line guidance after grep'ing for `setStalledSessions` call sites.

- **Plan 05 hook cleanup.** Capture hook reference; remove on cleanup. Test fixture in `@AfterEach`.

- **Plan 02 doc `stalled-held` token in 18-HARNESS.md §5.** Trivial doc change.

- **Plan 03 AttributionTagger thread-safety.** Add a comment acknowledging boundary race or wrap slot-claim in `synchronized` block. Cap=64 makes contention negligible.

## 5. Risk Assessment

**Overall: MEDIUM**

Justification:
- HIGH risk (Plan 03 markStalled ordering) is one-line fix specifying call ordering. Locked by `AdmissionMetricsLifecycleTest::stalledExpiryDecrementsBothBuckets` which would fail if snapshot is lost.
- All MEDIUM risks are mechanical: file list additions, prose clarifications, hook cleanup. No architectural rework.
- Plan 02/03/05 reviewer-driven amendments comprehensively absorbed; AdmissionGate ownership clear; --duration int-seconds; bucket race fixed; header retention fixed.
- Plan 06 STALLED-pivot attribution lock + LoadTest harness-tagged migration provides end-to-end coverage of T-18-04.
- Phase goal achievement: SCALE-03/04/05 closed cleanly with the markStalled ordering fix in place.

Recommend: amend Plan 03 (markStalled call ordering, rebind prose, OutboundSender spec, AttributionTagger race comment); move BotFleet idempotency from Plan 05 to Plan 04; doc `stalled-held` in 18-HARNESS.md; then execute.

---

## Codex Review

## Overall

### Summary
The phase plan is unusually thorough and mostly aligned with SCALE-03/04/05: it builds a standalone harness, preserves BotRunner, and adds per-harness attribution. The strongest parts are the explicit identity model, bounded-cardinality thinking, rebind-attribution test, and documentation closure.

The biggest risks are not conceptual; they are execution risks from overly prescriptive implementation details that contain several lifecycle, metric, CLI, and ordering bugs.

### Strengths
- Clear requirement mapping to SCALE-03, SCALE-04, SCALE-05.
- Good separation between client identity carriage, server attribution, fleet refactor, harness CLI, and docs.
- Strong attention to hidden failure modes: cardinality explosion, STALLED rebind attribution loss, BotRunner regression, header spoofing.
- Good decision to keep wire grammar unchanged and use handshake headers.
- Good validation intent, especially the final rebind test.

### Cross-Plan Concerns

- **HIGH: Wave ordering is inconsistent.** Plan 04 is wave 2 but depends on Plan 02, which is also wave 2. If waves are parallel execution units, that is invalid. Plan 04 should move to wave 3, Plan 05 to wave 4, Plan 06 to wave 5, or wave 2 must explicitly support serial sub-ordering.

- **HIGH: Plans prescribe buggy implementation details.** The plans include code-level skeletons that are sometimes wrong: `LoadHarness.run()` calling `System.exit`, signal handling that cannot distinguish SIGINT/SIGTERM, `ReportSnapshot` camelCase fields despite required snake_case, `BotClient.onClose` likely double-decrementing, and STALLED rebind active-bucket double-counting.

- **HIGH: Metric bucket lifecycle is the hardest part and still under-specified.** Plan 03 recognizes most lifecycle paths, but the proposed `bucketTagsByEntityId` map and rebind flow can still drift or double-count. This needs a simpler state model before implementation.

- **MEDIUM: Optional `harness` tag may be incompatible with future Prometheus export.** Micrometer/Prometheus commonly expects consistent tag keys for a metric name. The D-11 optional-tag shape may be acceptable in the current registry, but it should be explicitly tested or reconsidered with `harness="none"`/`"-"`.

- **MEDIUM: Scope is large for one phase.** The test matrix is valuable but heavy. Many tests are full Spring integration tests, several are slow, and some overlap. Execution may spend more time fighting brittle test harnesses than delivering the harness.

### Suggestions
- Fix wave sequencing before execution.
- Convert prescriptive code blocks into behavioral contracts where implementation is uncertain.
- Add a pre-implementation "metric lifecycle design checkpoint" for Plan 03.
- Decide whether Micrometer tag keys must be consistent across all meters now, before M5 Prometheus work.
- Keep harness signal handling simple unless true SIGINT/SIGTERM distinction is implemented deliberately.
- Make report JSON field naming explicit with Jackson `SNAKE_CASE` or `@JsonProperty`.

### Risk Assessment: **HIGH**
The architecture is sound, but the current plan text contains enough implementation-level defects that a literal executor could produce broken lifecycle accounting, brittle tests, or a harness that exits the JVM during tests. Risk falls to MEDIUM after fixing ordering, metric lifecycle, CLI/report details, and shutdown semantics.

---

## Plan 18-01

### Summary
Adds `BotIdentity`, `BotClientOptions`, and client-side handshake header injection.

### Strengths
- Good first step and clean dependency base.
- Preserves legacy constructors through an options record.
- Correctly identifies reconnect header re-emission as critical for STALLED attribution.
- Strong direct-constructor tests for identity invariants.

### Concerns
- **MEDIUM: Server-side sanitization is not guaranteed by client-side `BotIdentity`.** Malicious or ad-hoc clients can bypass `BotIdentity`; Plan 02 must reuse equivalent validation.
- **MEDIUM: Harness ID character policy is inconsistent.** Context says alphanumeric + dash, but `BotIdentity` only rejects ASCII control chars. Spaces, quotes, unicode, and punctuation can enter logs/tags.
- **MEDIUM: Reconnect test as written may not prove the real reconnect path.** "Fresh BotClient instance" does not prove the same client re-emits headers through its internal STALLED reconnect loop.
- **LOW: `source=harness iff harnessId.present` may constrain future `offspring` behavior if offspring later wants a harness-like id.** Not blocking now, but document the intended boundary.

### Suggestions
- Add a reusable harness-id sanitizer/normalizer used by both BotIdentity and server header parsing.
- Test same-instance reconnect or the actual `BotClient` reconnect path, not only a new instance.
- Enforce a grep-friendly harness id pattern if the docs promise alphanumeric + dash.

### Risk Assessment: **MEDIUM**
Good plan, but security/normalization must not stop at the client boundary.

---

## Plan 18-02

### Summary
Reads handshake headers server-side, stashes session attributes, emits HARNESS logs, and routes session context through `AdmissionGate`.

### Strengths
- Correctly places identity parsing in `afterConnectionEstablished`.
- Preserves `unknown` default for headerless clients.
- Good decision to keep TICK-HEALTH scalar.
- Moving all `AdmissionGate` edits into one plan reduces conflicts.

### Concerns
- **HIGH: Server accepts untrusted harness header values without full validation.** It trims/truncates but does not reject ASCII control chars or enforce the documented token shape. This can pollute logs and metric tags.
- **MEDIUM: `TickHealthMonitorScalarTest` is under-specified.** The plan says if no TICK-HEALTH line exists, the test may become a TODO/vacuous. That weakens the invariant.
- **MEDIUM: Admission log marker tests may be brittle if driven through full WebSocket setup.** A unit-level `AdmissionGate` test with mocked session attrs may be more reliable.
- **LOW: Disconnect reason taxonomy has drift.** Context says `token|graceful`; plan adds `stalled-held`. That may be fine, but docs and tests must match.

### Suggestions
- Use a shared `AttributionSanitizer` or `BotIdentity` helper on the server path.
- Make TICK-HEALTH scalar validation concrete: either trigger an actual log path or assert source code/log event shape where the marker exists.
- Prefer unit tests for `AdmissionGate` formatting, integration tests for header ingestion.

### Risk Assessment: **MEDIUM**
The structure is right, but untrusted-header sanitation and some test design need tightening.

---

## Plan 18-03

### Summary
Adds attribution tags, cardinality capping, active/stalled bucket gauges, ingress overwrite tagging, and lifecycle wiring.

### Strengths
- Correctly identifies cardinality as an operational risk.
- Good recognition that map keys and MeterFilter output must agree.
- Strong intent to test every lifecycle path.
- Keeps maintenance and tick-work metrics scalar.

### Concerns
- **HIGH: Overflow warning may not log the real 65th harness id.** Since `AttributionTagger` folds to `harness=overflow` before meter registration, the MeterFilter may only see `overflow`, not the raw over-cap id. The warn-once log belongs in the folding code, not only the MeterFilter.
- **HIGH: STALLED rebind active-bucket flow can double-count.** Marking STALLED leaves active incremented, then rebind success appears to call `incActiveBucket(newSession)` again. The test expects active remains 1, but the described implementation can produce 2.
- **HIGH: `bucketTagsByEntityId` needs clearer lifecycle ownership.** One shared map for active and stalled tags is fragile. Removal timing can break grace expiry, rebind, or cleanup-by-id paths.
- **HIGH: ActionResolver session lookup by streaming active sessions is a scale regression.** Doing O(active sessions) work on an action hot path is risky in a scale milestone.
- **MEDIUM: Optional tag keys may cause registry/export issues.** See overall concern.
- **MEDIUM: `Counter.builder(...).register(...)` per event may be allocation-heavy.** It may be acceptable but should be measured or cached if hot.
- **MEDIUM: Proposed concurrent cardinality registry has race-prone count/map bookkeeping.** A lock or bounded cache with atomic registration semantics would be safer.

### Suggestions
- Model active/stalled attribution as explicit per-entity state: `entityId -> AttributionBucket`, with clear transitions: registered, stalled, rebound, expired, disconnected.
- Put overflow folding and warn-once logging in `AttributionTagger`.
- Add `SessionRegistry.getById(sessionId)` or pass session/tags into `ActionResolver` instead of scanning sessions.
- Decide consistent tag-key shape before implementation.
- Make the rebind path transfer attribution rather than incrementing active a second time.

### Risk Assessment: **HIGH**
This is the riskiest plan. It touches shared simulation/session lifecycle and can silently corrupt metrics if the transition model is wrong.

---

## Plan 18-04

### Summary
Introduces `BotFactory`, `BotFleet`, ramp-up/species mix, close hooks, and migrates BotRunner to the fleet.

### Strengths
- Correctly removes the 30s launcher ceiling from the large-N path.
- Good use of `CompletableFuture<RegistrationResult>`.
- BotFactory seam is appropriately small for 999.2.
- Preserves BotRunner as the small-N operator path.

### Concerns
- **HIGH: `BotClient.onClose` can double-decrement.** If `disconnect()` fires callbacks and Jetty `onClose` also fires, `liveCount` can go negative.
- **HIGH: `BotRunnerOperatorTagTest` does not actually test BotRunner.** It launches `BotFleet` directly, so it does not prove BotRunner passes `BotIdentity.operator()`.
- **MEDIUM: Same-wave dependency issue.** Plan 04 depends on Plan 02 but is also wave 2.
- **MEDIUM: `currentRegistered()` is explicitly best-effort, yet the harness later uses it for reports.** That contradiction should be resolved in Plan 05.
- **MEDIUM: Ramp rate implementation with `1000 / perSecond` loses precision and becomes zero above 1000/s.**
- **LOW: Randomness/seed behavior is not addressed.** Repeatable benchmark runs may want deterministic species ordering and bot RNG seed control.

### Suggestions
- Make close callbacks exactly-once per connection or tie decrement to a registered-state compare-and-set.
- Test BotRunner through an extracted `run(...)` method that receives a fleet/factory test double.
- Move Plan 04 after Plan 02, or split BotRunner operator-tag test into Plan 06.
- Document/report `currentRegistered()` as ramp-only and use `BotClient.isRegistered()` polling for harness snapshots.

### Risk Assessment: **MEDIUM-HIGH**
The fleet abstraction is sound, but close lifecycle and BotRunner testing need correction.

---

## Plan 18-05

### Summary
Builds the standalone Picocli load harness, JSON/JSONL report writer, Gradle tasks, and integration tests.

### Strengths
- Correctly keeps harness as a pure client process with no Spring context.
- Good CLI surface and report schema coverage.
- Good overwrite-mode header-retention fix.
- Good dry-run jar/help verification.

### Concerns
- **HIGH: Picocli env-var default syntax is likely wrong or at least unverified.** `defaultValue = "${PARALIFE_HARNESS_COUNT}"` may not read environment variables as intended. This needs a verified Picocli syntax or manual env fallback.
- **HIGH: `LoadHarness.run()` should not call `System.exit`.** It will break tests and makes composition hard. Use `Callable<Integer>` and let `main` call `CommandLine.execute`.
- **HIGH: SIGINT/SIGTERM handling is not correct.** A standard shutdown hook cannot reliably distinguish SIGINT from SIGTERM, and final report writing from the main thread may not happen after JVM shutdown starts.
- **HIGH: JSON field names are camelCase, not required snake_case.** D-17 names are `peak_registered`, `current_registered`, etc. Jackson needs `@JsonProperty` or snake-case naming.
- **HIGH: Adding Picocli may fail in a restricted/offline environment if the dependency is not cached.** The plan should verify cache availability or define a fallback.
- **MEDIUM: `connect_failures_total` is not a total.** The proposed computation counts currently disconnected/unregistered bots, not cumulative connection failures.
- **MEDIUM: `syncs_received_total` computation is probably wrong.** It should use an actual bot counter if one exists, not `1 + respawnCount`.
- **MEDIUM: Shutdown hook registration in tests can leak global hooks.** Tests need a hook abstraction or direct lifecycle method.
- **MEDIUM: Append mode overwrites the file at header write.** If "append" means append across process restarts, this violates intent. Clarify semantics.

### Suggestions
- Implement `LoadHarness implements Callable<Integer>`.
- Create a `HarnessEnvironment` resolver for CLI/env precedence.
- Use `@JsonProperty("peak_registered")` or `ObjectMapper.setPropertyNamingStrategy(SNAKE_CASE)`.
- Track report counters from monotonic counters in `BotFleet`/`BotClient`, not current state inference.
- Either use `sun.misc.Signal` deliberately for signal-specific reasons or collapse shutdown reason to a portable value.
- Check Picocli dependency availability before committing to it.

### Risk Assessment: **HIGH**
This plan has several implementation defects that can prevent the harness from being testable or from producing the required report shape.

---

## Plan 18-06

### Summary
Adds final rebind attribution test, migrates LoadTest to harness identity, writes `18-HARNESS.md`, updates `CLAUDE.md`, and runs jar/help smoke.

### Strengths
- Excellent focus on the subtle STALLED rebind attribution failure.
- Good documentation closure with concrete commands.
- Good decision to make LoadTest exercise the harness-tagged path.
- Dry-run smoke catches accidental Spring startup.

### Concerns
- **MEDIUM: `AttributionRebindTest` may be brittle against shared registry state.** Negative assertion on `source=unknown` can fail due unrelated sessions or prior tests unless registry/context isolation is guaranteed.
- **MEDIUM: Direct `handler.markStalled(...)` may not perfectly simulate queue-overflow STALLED behavior.** It is fine as a focused test, but one real overflow-path test should exist elsewhere.
- **MEDIUM: Docs contain a likely tag typo.** The cheat sheet mentions `source=overflow`, but overflow is planned as a `harness=overflow` fold, not a source value in normal harness attribution.
- **LOW: Jar glob in `java -jar build/libs/paralife-*-load-harness.jar --help` can be fragile if multiple matching jars exist.**
- **LOW: Docs promise alphanumeric+dash harness ids, but earlier plans allow broader values.**

### Suggestions
- Compare unknown gauge before/after instead of requiring absolute `< 1.0`.
- Add one integration test that triggers STALLED through actual outbound queue pressure, even if marked slow.
- Correct docs to use `harness=overflow`.
- Use a deterministic jar path in smoke checks.
- Align docs and implementation on harness-id character policy.

### Risk Assessment: **MEDIUM**
Good closure plan, but its tests and docs depend on earlier lifecycle/report fixes.

---

## Final Recommendation

Proceed only after revising these blockers:

1. Fix wave ordering: Plan 04 cannot be same-wave with Plan 02 if it depends on it.
2. Redesign Plan 03's active/stalled bucket lifecycle, especially STALLED rebind and `bucketTagsByEntityId`.
3. Move overflow warn-once logging into the same code that folds raw harness ids.
4. Fix Plan 05 CLI/report/shutdown shape: `Callable<Integer>`, verified env handling, snake_case JSON, no `System.exit` in `run`.
5. Make `BotClient.onClose` exactly-once or state-guarded.
6. Reconcile optional metric tag keys with future Prometheus expectations.

After those changes, the phase plan should be strong enough to execute with MEDIUM risk.

---

## OpenCode Review

## Phase 18 Plan Review

### Summary

Phase 18 delivers an external load harness (`LoadHarness`) scaling past 100 bots, per-harness identity attribution via WebSocket handshake headers, and a two-tag Micrometer schema with bounded cardinality. Six plans execute in four waves:

| Plan | Wave | Focus |
|------|------|-------|
| 18-01 | 1 | `BotIdentity` record + `BotClient` handshake-header injection |
| 18-03 | 1 | `AttributionTagger`, `AdmissionMetrics` two-tag extension + MeterFilter cap |
| 18-02 | 2 | Server-side header read, session ATTR stashing, `AdmissionGate` session-bearing evaluate |
| 18-04 | 2 | `BotFleet`/`BotFactory` refactor, `BotRunner` migration |
| 18-05 | 3 | `LoadHarness` Picocli CLI + JSON/JSONL report writer |
| 18-06 | 4 | Integration tests (`AttributionRebindTest`), `18-HARNESS.md`, `CLAUDE.md` updates |

The plans correctly close SCALE-03, SCALE-04, and SCALE-05. Research is thorough (Jetty 12 header API, Micrometer `MeterFilter`, Spring `HttpHeaders` case-insensitivity all verified). Cross-reviewer amendments are well-documented (overflow folding co-located in `AttributionTagger`, `BotFleet` peak high-water mark semantics, `--duration` as int seconds).

---

### Strengths

- **Clear responsibility separation.** Plan 02 owns *all* `AdmissionGate.java` edits; Plan 03 owns *all* `AdmissionMetrics.java` edits. This avoids the merge hell that usually sinks multi-plan phases.
- **Defense-in-depth on cardinality.** The 64-cap is enforced twice: `AttributionTagger.foldHarnessIfOverCap` (bucket-map keys) and `MeterFilter.maximumAllowableTags` (registry). The `replaceTagValues` pattern avoids Micrometer issue #4971 memory leaks.
- **Back-compat preservation.** `BotClient`'s existing 3/5/6-arg constructors remain compilable via `BotClientOptions.defaults(...)`. `BotRunner` exit codes and stdout strings are explicitly preserved.
- **Rigorous test coverage.** Every release path (graceful close, stalled hold, stalled expiry, stalled rebind, rejected placement, duplicate close) has a dedicated lifecycle test.
- **Pitfall documentation.** Research calls out six specific pitfalls (rebind attribution loss, constructor sprawl, 30s false-positive ceiling, header spoofing, Spring startup cost, Windows atomic move) and each plan addresses its relevant ones.

---

### Concerns

#### HIGH

- **Plan 03 `AdmissionMetrics` constructor breaking change.** Refactoring from `AdmissionMetrics(MeterRegistry)` to `AdmissionMetrics(MeterRegistry, AdmissionConfig, TickEngine, AttributionTagger)` will break every existing test that instantiates `AdmissionMetrics` directly. The plan says "audit all test sites and update" but provides no exhaustive list of call sites. Missed sites = compilation failure wave 1.
- **`WorldWebSocketHandler.java` sequential-edit fragility.** Plan 03 (wave 1) deletes `setActiveEntities`/`setStalledSessions` call sites and adds `incActiveBucket`/`decActiveBucket` calls. Plan 02 (wave 2) then reads *the same file* to add handshake-header logic. While wave ordering enforces sequence, any missed Plan 03 call site (e.g., in a `handleTransportError` branch not explicitly listed) will leave a compilation error that Plan 02's executor must debug without context. A single shared structural diagram of `WorldWebSocketHandler` would mitigate this.
- **Plan 05 assumes unverified `BotClient` observability methods.** The `computeCountersSnapshot` method calls `getActionCount()`, `getPerceptionCount()`, `getRespawnCount()`, `isRegistered()`, `isConnected()`, and `getE408ReconnectRequiredCount()`. Only `getE408...` is explicitly added in Plan 05. If the others don't exist today, Plan 05 will not compile. The interfaces block claims they are "current counters" but this is an unverified assumption (RESEARCH.md did not include a `BotClient.java` read of these specific methods).

#### MEDIUM

- **`bucketTagsByEntityId` rebind lifecycle ambiguity.** On STALLED-pivot rebind, the *old* session is closed and a *new* session is created with the *same* `entityId`. `incActiveBucket` is called for the new session (same key, possibly same Tags), but the old session's `decActiveBucket` may or may not fire depending on whether `cleanupBot` runs for the old session. If `decActiveBucket` fires *after* the rebind increment, we risk transient undercount. The lifecycle test in Plan 06 (`stalledRebindDecrementsStalledIncrementsActive`) locks the steady-state, but a transient dip could trigger false alerts on a dashboard. Add an explicit assertion that `activeBuckets` never goes negative.
- **`TickHealthMonitorScalarTest` may be vacuous.** The test drives `metrics.setLastTickWorkMs(200)` but `TickHealthMonitor` might not emit a `TICK-HEALTH` log line in response to raw metric updates—it may only evaluate on tick events. If zero lines are emitted, the test passes trivially (`tickHealthLines >= 1` fails, but if the implementation is forgiving it could silently succeed). Lock the test to *force* emission or assert the test is non-vacuous by verifying `tickHealthLines > 0`.
- **Plan 04 `SpeciesMix.pickFor` is sensitive to `ParticleType.values()` ordering.** The `balanced` path uses `types[i % types.length]`. If the enum declaration order ever changes (e.g., `SPORE` moves before `MEMBRANE`), the species distribution shifts silently. Since ` balanced()` is the default, this is a regression risk. Hardcode the order: `new ParticleType[]{CATALYST, MEMBRANE, SPORE}`.
- **Plan 05 `--report-interval` VT may lose final write on fast shutdown.** The reporter VT sleeps `reportIntervalSeconds * 1000L`, then checks `exitReason`. If `exitReason` is set *during* the sleep, the VT returns without writing. The shutdown hook then calls `writeFinalAndReturn`, which is fine. But if `durationSeconds` expires and `exitReason` is set between the sleep wake and the write-check, the periodic write is skipped. Acceptable, but means the report might have a gap of up to `reportIntervalSeconds` before the final write. Not a bug, but document it.
- **Plan 06 `AttributionRebindTest` directly calls `handler.markStalled(session, tick)`.** This assumes `markStalled` is public and takes `(WebSocketSession, long)`. If the signature differs (e.g., needs an `OutboundSender` or a `Consumer<Session>`), the test won't compile. Verify the exact signature in `WorldWebSocketHandler.java` before Plan 06 execution.

#### LOW

- **No JSON Schema for the report.** Phase 21 benchmark aggregation could benefit from a lightweight schema. Deferrable.
- **No test for the 5000-connection design ceiling.** "Designed for 5000" is stated but unverified. Acceptable since the admission gate caps at 256 by default.
- **`loadHarnessJar` uses Spring Boot `BootJar`**, pulling ~30MB of unused Spring deps into a pure client process. Wasteful but operationally harmless. Consider a future optimization to plain `Jar` with `Manifest` `Class-Path`.
- **Plan 05 `generateHarnessId()` could collide.** `ThreadLocalRandom.nextLong()` has 2^64 space; collision probability is negligible for 64 concurrent harnesses, but not zero. Acceptable.

---

### Suggestions

1. **Add a `BotClient.java` observability audit to Plan 05 wave 0.** Before writing `LoadHarness.computeCountersSnapshot`, grep `BotClient.java` for `getActionCount|getPerceptionCount|getRespawnCount|isRegistered|isConnected`. If any are missing, add them in a Plan 05 prerequisite task or default to zero in `computeCountersSnapshot`.
2. **Replace `SpeciesMix.pickFor` enum iteration with an explicit ordered array.** Prevents silent behavioral regression if `ParticleType` declaration order changes.
3. **Harden `TickHealthMonitorScalarTest` against vacuous pass.** Assert that the test actually triggers at least one `TICK-HEALTH` log line by driving the real tick loop or mocking `TickHealthMonitor` to emit on threshold breach.
4. **Add a transient-negative guard to `AdmissionMetricsLifecycleTest`.** Assert `activeBuckets` values never go below zero during the `stalledRebind` test.
5. **Document the report-gap invariant in `18-HARNESS.md`.** State that the on-disk file reflects the most recent periodic snapshot or the final snapshot, whichever is later; gaps of up to `--report-interval` are expected.
6. **Consider a `BotClient` builder for future phases.** `BotClientOptions` is a good step, but with 7+ fields a fluent builder (`BotClient.builder().serverUri(u).species('C').identity(id).build()`) would improve readability for the 999.2 producer.

---

### Risk Assessment: **MEDIUM**

**Justification:**

- **Dependency ordering is mostly sound** (waves 1→2→3→4), but `WorldWebSocketHandler.java` being edited in both Plan 03 and Plan 02 creates a single point of fragility. A missed call site or subtle merge drift in that file could propagate into metric corruption or compilation failure.
- **Metrics machinery is sophisticated.** The `AttributionTagger` ↔ `MeterFilter` ↔ `bucketTagsByEntityId` interaction is correct on paper but has edge cases around rebind and concurrent decrement. The lifecycle tests cover this, but they themselves depend on exact `markStalled` and `SessionRegistry` signatures.
- **Plan 05's unverified `BotClient` observability assumptions** are the most likely source of a compilation surprise. If those methods don't exist, the Plan 05 integration test cannot compile.

Mitigation: Run a **wave 0 spike** that audits `BotClient` for counter methods and verifies `markStalled` signature before any code is written. If both checks pass, risk drops to **LOW**.

---

## Consensus Summary

### Risk Verdict Spread

| Reviewer | Risk | Recommendation |
|----------|------|----------------|
| Gemini | LOW | Execute as-is; only LOW concerns |
| Claude | MEDIUM | Amend Plan 03 markStalled ordering then execute |
| Codex | HIGH→MEDIUM after fixes | Block on 6 listed amendments |
| OpenCode | MEDIUM→LOW with wave-0 spike | Audit BotClient counters + markStalled signature first |

Three of four reviewers agree the architecture is sound and remaining issues are mechanical/executional, not design-level. Codex is the outlier on overall severity but its specific findings overlap heavily with the others.

### Agreed Strengths (2+ reviewers)

- **Cardinality defense-in-depth** — `AttributionTagger.foldHarnessIfOverCap` + `MeterFilter` cap, with `replaceTagValues` avoiding Micrometer leak (Gemini, OpenCode).
- **Plan 03 every-release-path lifecycle test** — covers all six release paths; strong regression net (Claude, OpenCode).
- **AdmissionGate ownership consolidation in Plan 02** — eliminates merge conflicts (Claude, OpenCode).
- **Plan 06 `AttributionRebindTest` directly locks T-18-04** — STALLED-pivot attribution loss; agreed as the right invariant lock (Gemini, Claude, Codex, OpenCode).
- **BotIdentity invariant tightening + reusable normalization** — Round-1 amendments absorbed (Claude, Codex, Gemini).
- **Doc closure / forward-compat seams** — `18-HARNESS.md`, `BotFactory` for backlog 999.2 (Gemini, OpenCode).
- **Wave 1 → Wave 2 sequencing of `WorldWebSocketHandler` edits prevents collision** (Claude positive, OpenCode flags fragility but agrees ordering is correct).

### Agreed Concerns (2+ reviewers — highest priority)

#### HIGH — Block before execute

1. **Plan 03 STALLED-pivot bucket accounting is the highest-risk single area.**
   - Claude HIGH: `markStalled` ordering bug — `incStalledBucket(session)` runs after `attrs.remove(ATTR_ENTITY_ID)`, snapshot lost, grace-expiry has no Tags.
   - Codex HIGH: `bucketTagsByEntityId` lifecycle ownership fragile; rebind active-bucket double-count possible.
   - OpenCode MEDIUM: rebind transient-negative window between old-session `decActiveBucket` and new-session `incActiveBucket`.
   - **Action:** specify `markStalled` call ordering explicitly OR change signature to `incStalledBucket(WebSocketSession, String entityId)`; clarify rebind prose ("active stays incremented from original Allow"); add `activeBuckets ≥ 0` invariant assertion to lifecycle test.

2. **Plan 05 has multiple implementation-shape defects.**
   - Codex HIGH: `LoadHarness.run()` calling `System.exit` (use `Callable<Integer>`); JSON field naming camelCase vs required snake_case; SIGINT/SIGTERM distinction not portable; Picocli env-var `${VAR}` syntax unverified.
   - OpenCode HIGH: `LoadHarness.computeCountersSnapshot` calls `BotClient` getters not verified to exist.
   - Claude MEDIUM: `BotFleet` shutdown idempotency edit missing from `files_modified`; shutdown-hook leak in tests.
   - **Action:** make `LoadHarness` `Callable<Integer>`; force snake_case via Jackson `PropertyNamingStrategies.SNAKE_CASE` or `@JsonProperty`; spike-audit BotClient observability methods before Plan 05 execute (OpenCode's wave-0 audit); register/remove shutdown hook with reference; verify Picocli env-var syntax; collapse signal-distinction unless deliberately implemented.

3. **Wave-ordering / file-edit fragility on `WorldWebSocketHandler.java`.**
   - Codex HIGH: Plan 04 same-wave as Plan 02 despite dependency.
   - OpenCode HIGH: sequential edits to `WorldWebSocketHandler` across Plan 03 (W1) → Plan 02 (W2); a missed call site leaves silent compilation failure for the next executor.
   - **Action:** either move Plan 04 to Wave 3 OR document explicit serial sub-ordering inside Wave 2; produce a `WorldWebSocketHandler` structural snapshot listing every call site before Plan 03 edits.

4. **`BotClient.onClose` double-decrement risk** (Codex HIGH, Plan 04).
   - Disconnect callbacks + Jetty `onClose` can both fire → `liveCount` negative.
   - **Action:** state-guard the decrement with a CAS on a registered/closed flag.

#### MEDIUM — Fix during execute or document

- **`AdmissionMetrics` constructor breaking change** — exhaustive grep for call sites mandated, not optional (OpenCode HIGH, Gemini LOW with concrete `grep -l 'new AdmissionMetrics('` suggestion).
- **Server-side harness-id sanitation must not depend on client `BotIdentity`** — reject ASCII control chars + enforce alphanumeric+dash on the server path (Codex HIGH on Plan 02, Claude MEDIUM on header trust).
- **`TickHealthMonitorScalarTest` vacuous-pass risk** — if the test never triggers a `TICK-HEALTH` line it silently passes (Codex MEDIUM, OpenCode MEDIUM). Force emission or assert `tickHealthLines > 0`.
- **`SpeciesMix.pickFor` balanced-mode depends on `ParticleType.values()` order** (OpenCode MEDIUM, Codex MEDIUM-LOW). Hardcode `{CATALYST, MEMBRANE, SPORE}`.
- **`closeReason="stalled-held"` outside D-14 taxonomy** (Claude LOW, Codex LOW). Document in `18-HARNESS.md` §5.
- **Overflow warn-once log location** — must live in `AttributionTagger` folding code, not only the `MeterFilter`, or 65th id never logged (Codex HIGH).
- **`AttributionTagger` cap-boundary race** — `putIfAbsent` + `incrementAndGet` rollback isn't atomic; either mutex slot-claim or document acceptable narrow window (Claude MEDIUM, Codex MEDIUM, Gemini LOW).

#### LOW — Polish

- Picocli `defaultValue` patterns; jar-glob path determinism; LoadTest single-harness comment; harness-id char policy doc alignment; report-gap invariant doc.

### Divergent Views (worth investigating)

- **Overall risk level.** Gemini (LOW) vs Codex (HIGH-pre-fix). Gemini reads the Round-1 amendment block as resolving the architectural risks; Codex reads the prescriptive code skeletons as still containing executable defects. The truth is that the architecture is sound (per Gemini) but the plan text contains executable defects (per Codex/OpenCode/Claude HIGH on Plan 03 markStalled ordering). Net: **MEDIUM** is the correct reading.
- **`bucketTagsByEntityId` design.** Codex wants a redesign to explicit `entityId → AttributionBucket` state machine; Claude treats it as a one-line ordering fix. Lower-cost path (Claude's) likely sufficient if call ordering is mandated and `AdmissionMetricsLifecycleTest::stalledExpiryDecrementsBothBuckets` would fail on snapshot loss.
- **Optional `harness` tag for future Prometheus export.** Codex flags MEDIUM (consistent tag keys expected); OpenCode and Claude don't raise. Worth a one-line spike: confirm that `MeterFilter`'s `replaceTagValues` produces consistent key sets, OR adopt `harness="none"` sentinel now to forestall M5 Prometheus rework.
- **Reconnect-test fidelity** (Plan 01). Codex flags MEDIUM (fresh `BotClient` instance ≠ same-instance reconnect path); other reviewers don't flag. Worth amending the test to drive the actual reconnect loop, not just a fresh constructor call.
- **ActionResolver session lookup as scale regression** (Codex HIGH, Plan 03). Codex calls O(active sessions) work on the action hot path a regression for a scale milestone; other reviewers don't flag. Worth measuring or adding `SessionRegistry.getById(sessionId)` before Plan 03 execute.

### Recommendation

Adopt the union of HIGH-severity findings above before execute. Concretely:

1. Plan 03: specify `markStalled` call ordering OR pass entityId explicitly to `incStalledBucket`; rewrite rebind prose; move overflow warn-once log into `AttributionTagger`; add `OutboundSender` delete-call-site spec; document or fix cap-boundary race; add `SessionRegistry.getById(sessionId)` (or pass session/tags) for ActionResolver hot path.
2. Plan 04: state-guard `BotClient.onClose` decrement; move BotFleet shutdown idempotency here from Plan 05; either move plan to Wave 3 or document Wave-2 serial sub-ordering; fix ramp-rate precision (`1000 / perSecond` precision loss).
3. Plan 05: `Callable<Integer>` shape; snake_case via Jackson naming strategy; verify Picocli env-var syntax; capture shutdown-hook reference for test cleanup; spike-audit `BotClient` observability methods before write.
4. Plan 02: server-side harness-id sanitizer reused from `BotIdentity`; document `stalled-held` close token in `18-HARNESS.md` §5; harden `TickHealthMonitorScalarTest` against vacuous pass.
5. Wave plan: serialize Plan 02 → Plan 04 inside Wave 2 (or move Plan 04 to Wave 3) to avoid same-wave dependency.
6. Cross-cutting: produce a `WorldWebSocketHandler` call-site map before Plan 03 edits; produce a `new AdmissionMetrics(` grep before Plan 03 executes.

After amendments, executor risk: MEDIUM. Phase goal achievement (SCALE-03/04/05) remains intact.
