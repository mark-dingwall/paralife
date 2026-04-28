---
phase: 18
reviewers: [gemini, claude, codex, opencode]
reviewed_at: 2026-04-28T06:12:21Z
plans_reviewed: [18-01-PLAN.md, 18-02-PLAN.md, 18-03-PLAN.md, 18-04-PLAN.md, 18-05-PLAN.md, 18-06-PLAN.md]
models:
  gemini: gemini-3.1-pro-preview
  codex: gpt-5.5 (reasoning_effort=high)
  claude: opus (effort=xhigh)
  opencode: openrouter/moonshotai/kimi-k2.6
---

# Cross-AI Plan Review — Phase 18

External load harness + harness-identity attribution. Four independent reviewers, top-tier models with maximum capability/effort flags.

---

## Gemini Review (gemini-3.1-pro-preview)

# Cross-AI Plan Review

## 1. Summary
The implementation plans for Phase 18 provide a highly robust, well-sequenced integration of an external load harness and per-instance identity attribution. The architecture correctly isolates the standalone `LoadHarness` JVM from the server's Spring context, leans on standard ecosystem tooling (Picocli, Jackson, Micrometer `MeterFilter`), and introduces a clean `BotFleet` abstraction to bypass the legacy 30-second registration ceiling. The rigorous attention to metric safety—specifically the bounded-cardinality cap and the true high-water-mark tracking—is excellent.

## 2. Strengths
- **Zero-Spring Harness (Plan 05):** Keeping `LoadHarness` as a pure `public static void main` avoids unnecessary startup costs and cleanly enforces architectural separation between the client process and server-side dependencies.
- **Cardinality Blast-Radius Protection (Plan 03):** Using Micrometer's `MeterFilter.maximumAllowableTags` with an inline mapper to fold over-cap values to `overflow` (and warning exactly once) elegantly prevents metric-explosion DoS (T-18-02) without triggering the known `deny()` memory leak.
- **High-Water Mark Accuracy (Plan 04):** The paired-atomic approach (`liveCount` and `highWater`) in `BotFleet` perfectly captures the peak registration count, systematically avoiding the common cumulative-counter anti-pattern.
- **Dependency Sequencing:** The wave ordering is flawless. It sequences the foundational identity schema and metric helpers (Wave 1: Plans 01/03) before the server ingestion (Wave 2: Plan 02) and fleet refactor (Wave 2: Plan 04), culminating in the harness execution (Wave 3: Plan 05) and E2E documentation/verification (Wave 4: Plan 06).
- **STALLED-Pivot Rebind Preservation (Plan 06):** Explicitly validating that `BotClient` re-sends headers on reconnect and permanently locking this attribution persistence through `AttributionRebindTest` addresses the phase's most subtle failure mode (T-18-04).

## 3. Concerns
- **MEDIUM - Potential Metric Drift on Idempotent Cleanup (Plan 03):** `decActiveBucket(session)` is wired into `WorldWebSocketHandler.cleanupBot`. However, `cleanupBot` can be invoked multiple times for the same session (e.g., `handleTransportError` followed immediately by `afterConnectionClosed`). If `decActiveBucket` is called unconditionally on every `cleanupBot` invocation, the tag-keyed gauge bucket will double-decrement, causing the active entity metric to drift below the true grid count.
- **LOW - JSONL File Contention on Windows (Plan 05):** The report writer relies on `StandardOpenOption.APPEND + SYNC` for the JSONL counter lines. While this is crash-safe, concurrent reads (e.g., from an operator running `tail -f` or a dashboarding tool) on Windows might occasionally throw a `FileSystemException` due to mandatory locking during the write.
- **LOW - `BotClient.onClose` Hook Threading (Plan 04):** Plan 04 adds an `onClose(Runnable)` hook to `BotClient` to decrement `BotFleet`'s `liveCount`. Ensure this hook is invoked consistently regardless of whether the disconnect is initiated locally (via `disconnect()`) or remotely by the server.

## 4. Suggestions
- **Guard Metric Decrements (Plan 03):** Instruct the executor to wrap the `admissionMetrics.decActiveBucket(session)` call inside `WorldWebSocketHandler.cleanupBot` within the same `if (wasRegistered)` block that currently guards `admissionGate.releaseSlot()`. This guarantees the gauge bucket is decremented exactly once per session, preserving the single-source-of-truth invariant.
- **Add JSONL Write Retries (Plan 05):** In `ReportWriter.appendJsonlCounter`, consider wrapping the `Files.writeString` call in a brief retry loop (e.g., 3 attempts with a 50ms sleep) to gracefully handle transient file locks from external readers on Windows filesystems.
- **Ensure Strict `BotClient` Cleanup (Plan 04):** When adding the `onClose` hook to `BotClient`, ensure the callbacks are executed inside a `finally` block or guaranteed execution path within the Jetty `@OnWebSocketClose` handler and the manual `disconnect()` method.

## 5. Risk Assessment
**LOW.** 
The architecture relies on stable, well-understood patterns and standard libraries rather than hand-rolled abstractions. The threat model is explicitly defined and mitigated appropriately for an internal-network boundary. The E2E validation strategy (Plan 06) is comprehensive. The identified concern regarding metric drift is easily addressable during the execution phase by applying existing idempotency guards.

---

## Claude Review (opus, effort=xhigh)

# Phase 18 Plan Review

## 1. Summary

Phase 18 plans cover SCALE-03/04/05 cleanly with 6 plans across 4 waves. Decisions (D-01..D-21) traced to code. Research solid: Jetty/Micrometer/Spring Boot APIs verified. Big risks concentrate in Plan 03 (metric correctness) and a handful of file-list/dependency oversights that will bite during execution. Plans 01, 02, 04, 05, 06 mostly tight; Plan 03 has two real semantic gaps in per-bucket gauge lifecycle plus a MeterFilter/in-memory-map race. Overall well-structured; needs Plan 03 hardening before execute.

## 2. Strengths

- **Clear wave ordering and Pitfall 1 lock** — Plan 01 stores `BotIdentity` in BotClient final field; Plan 06 `AttributionRebindTest` end-to-end-locks T-18-04. Reconnect re-emits headers proven by integration test.
- **Bounded-cardinality defense-in-depth** — `BotIdentity.harness(...)` truncates 32 chars + rejects CR/LF (Plan 01); server taxonomy-folds unknown to "unknown" (Plan 02); MeterFilter caps registry (Plan 03). Three layers, costless.
- **Constructor-sprawl avoided** — `BotClientOptions` record (Plan 01 Task 2) sidesteps 7-arg ctor landmine cited in RESEARCH.md Pitfall 2.
- **Picocli + BootJar reuse** — no Shadow plugin, no Spring context in harness JVM (Plan 05). Pure `public static void main`. Matches RESEARCH.md A3.
- **D-12 scalar invariants enforced via grep checks** — `paralife.admission.maintenance` and `paralife.tick.health.work-time-ms` stay scalar; AttributionTagTest asserts `null` gauge with source tags. Locked at three layers (test, grep acceptance, source-comment).
- **18-HARNESS.md spec mirrors 17-ADMISSION.md style** — §1–§10 sections discoverable; sample 100/500/1000 commands copy-paste-ready for Phase 21.
- **Async per-bot tracking** — Plan 04 `BotFleet` lifts 30s `allDone.await` ceiling (Pitfall 3). True high-water-mark via paired `liveCount`/`highWater` atomics; broken `incrementAndGet` cumulative pattern explicitly called out.

## 3. Concerns

### HIGH

- **Plan 03: MeterFilter ↔ in-memory bucket-map race (gauge correctness bug).** `incActiveBucket(session)` keys `activeBuckets` by `Tags = AttributionTagger.tagsFor(session)` (carries actual harness id). `Gauge.builder(...).tags(t).register(registry)` triggers the filter, which folds `harness=<65th>` to `harness=overflow` in the registered Meter.Id. Result: 65th and beyond mint NEW AtomicIntegers in `activeBuckets` (keyed by original tags), but the registry only has ONE gauge for `harness=overflow` bound to the FIRST overflow bucket's AtomicInteger. Subsequent overflow buckets become orphaned — increments don't show up in metrics. Fix: key the bucket map by post-filter tags, or fold to "overflow" in `AttributionTagger` itself when over cap.

- **Plan 03: Stalled-bucket lifecycle gap — orphaned buckets after grace expiry.** Plan 03 says "decStalledBucket on graceful-disconnect of a stalled session" but `cleanupByEntityId` (called by `ResumeTokenRegistry` sweep) has NO `WebSocketSession` — the session is long-gone. To decrement the right bucket, plan must snapshot Tags at `markStalled` time alongside the existing `respawnCountAtStall` map (e.g. `bucketTagsByEntityId`). Without this, `paralife.backpressure.stalled.sessions{source=harness, harness=A}` gauge will drift upward over time as terminal-dropouts accumulate without dec. Same problem on `active.entities` reaped path. Reference: WorldWebSocketHandler.java:518 `cleanupByEntityId`.

- **Plan 03: `WorldWebSocketHandler.java` missing from `files_modified`.** Plan 03 task 2 explicitly wires `incActiveBucket` / `decActiveBucket` / `incStalledBucket` / `decStalledBucket` calls in `WorldWebSocketHandler` (handleRegister Allow, cleanupBot, cleanupByEntityId, markStalled, afterConnectionClosed wasStalled branch). But `files_modified` only lists `AdmissionGate.java`, `OutboundSender.java`, `ActionResolver.java`. Compounds with Plan 02 also editing `WorldWebSocketHandler.java` — both Wave 1 (Plan 03) and Wave 2 (Plan 02) hit the same file. Wave 1 must finish first for Plan 02 acceptance to compile.

### MEDIUM

- **Plan 04: hidden dependency on Plan 02.** `depends_on: ["18-01"]` only. But `BotRunnerOperatorTagTest` asserts `session.getAttributes().get(AttributionTagger.ATTR_SOURCE).equals("operator")` — that attribute is populated by Plan 02's `afterConnectionEstablished` edit. Plan 04 says "Wave ordering ensures Plan 02 lands before this test runs" — both are Wave 2. If wave runs in parallel, Plan 04 breaks. Fix: add `18-02` to `depends_on`, or move `BotRunnerOperatorTagTest` to Wave 3.

- **Plan 04: `BotClient.java` missing from `files_modified`.** Plan 04 Step 2 ("BotClient `onClose` hook") explicitly says "add a minimal `public void onClose(Runnable r)` method backed by a `CopyOnWriteArrayList<Runnable>` invoked from `BotClient.disconnect()` and from the WebSocket `onClose` path." That's a source modification to `BotClient.java` — file not listed.

- **Plan 05: `--duration` UX deviates from CONTEXT.md.** CONTEXT.md says `--duration=<seconds>` (integer). Plan 05 declares `public Duration duration` with `defaultValue = "${PARALIFE_HARNESS_DURATION:-PT0S}"` — ISO-8601. Operators expect `--duration 600` not `--duration PT10M`. Fix: type as `int durationSeconds` and convert to `Duration` internally, or document ISO-8601 prominently in `18-HARNESS.md` §3.

- **Plan 06: `sessionRegistry.getSessions()` likely doesn't exist.** AttributionRebindTest iterates server-side sessions via `sessionRegistry.getSessions().stream()`. Plan 06 says "If not currently a public method, either add a package-private getSessions() helper". That's a `SessionRegistry.java` source modification not in `files_modified`. Verify before execute.

- **Plan 03: AdmissionMetrics constructor breaking change.** New ctor takes `(MeterRegistry, AdmissionConfig, TickEngine)`. Phase 17 ctor took only `MeterRegistry`. Spring DI handles production wiring, but any test that constructs `new AdmissionMetrics(registry)` directly breaks. Audit existing tests; either add a no-args fallback or update all sites.

- **Plan 03: ActionResolver SessionRegistry injection unspecified.** "look up via `SessionRegistry.getSession(sessionId)`" — requires injecting SessionRegistry into ActionResolver. Plan 03 lists ActionResolver in `files_modified` but doesn't show ctor change or @Autowired wiring detail. Risk of circular bean dependency (SessionRegistry is in `websocket` package, ActionResolver in `engine`).

### LOW

- **Plan 05: `paralife.build.sha` system property never set.** `ReportSnapshot.header` reads `System.getProperty("paralife.build.sha", "unknown")` — always "unknown" unless Gradle injects it via `bootJar` manifest. Either wire Gradle to set it, or delete the field for now.

- **Plan 05: double `fleet.shutdown()` on signal path.** Shutdown hook calls `fleet.shutdown()`, then main `run()` calls it again after exitLatch. `BotClient.disconnect()` should be idempotent (current Phase 17 code uses `shutdown.set(true)` guard) but worth a once-only guard on `BotFleet.shutdown()`.

- **Plan 05: `signal-int` vs `signal-term`.** Shutdown hook always sets `"signal-term"`. JVM's `Runtime.addShutdownHook` doesn't expose which signal triggered it. Either drop the distinction in `exitReason` taxonomy or document this gap.

- **Plan 06: AttributionRebindTest timing brittleness.** Forces STALLED via `handler.markStalled(...)`, polls Awaitility for `getE408ReconnectRequiredCount() >= 1`. Reconnect jitter is 100–300ms (BotClient.java:406). Test budget is `Duration.ofSeconds(3)` — tight on slow CI. Consider 5s.

- **Plan 02: case-sensitivity test relies on Spring's HttpHeaders behavior.** Mixed-case test (`X-paralife-source`) verifies Spring's `LinkedCaseInsensitiveMap`. RESEARCH.md A1 verified. If Spring future changes this, test catches it. Good defensive lock.

- **Plan 04: BotFleet.launch RampUpSpec.awaitNext blocks the launcher thread.** Per `RampUpSpec.Rate(50)`, launcher sleeps 20ms × 1000 = 20s for 1000 bots. Acceptable per design (D-03 default `rate:50`), but harness operators should know launcher thread is busy during ramp. Document in 18-HARNESS.md §3.

- **Plan 03 active bucket count doesn't include placement-failure releases via `admissionGate.releaseSlot()`.** When GRID_FULL occurs, AdmissionGate's reservedSlots decrements but no per-bucket gauge change is needed (entity never placed → never inc'd). Verify the order is: reserve → place → incBucket → ... so a failed-placement path naturally avoids inc. Plan 03 says "after `worldGrid.placeEntity` succeeds" — correct order.

## 4. Suggestions

- **Plan 03: switch bucket-map key strategy to defeat the MeterFilter race.**
  Either:
  ```java
  // Option A: pre-compute Tags through filter (cleanest)
  Tags effective = applyOverflowIfOverCap(tags);
  activeBuckets.computeIfAbsent(effective, t -> register(t));
  ```
  OR move overflow folding into `AttributionTagger.tagsFor(session, registry)` so the tags stored in attrs match what the registry sees. Add a test: register 100 unique harness ids; assert `Σ(activeBuckets values) == sum of all gauge.value()` across registered gauges including the overflow gauge.

- **Plan 03: add `bucketTagsByEntityId` snapshot.**
  Mirror the `respawnCountAtStall` pattern. Snapshot Tags at `markStalled` and at `incActiveBucket`, keyed by entityId. `cleanupByEntityId` and grace-expiry reaper consult this map to call `decStalledBucket` / `decActiveBucket` with the original tags.

- **Plan 03: add `WorldWebSocketHandler.java` to `files_modified`.** Update Wave 1/Wave 2 contention plan: Plan 03 first writes the inc/dec-call sites and the legacy setter deletion; Plan 02 then reads/edits same file for handshake header logic. Either serialize the file edit (Wave 1 fully completes Plan 03 → Wave 2 starts Plan 02), or merge the WorldWebSocketHandler.java surface into one plan.

- **Plan 04: add `18-02` to `depends_on`.** Or split BotRunnerOperatorTagTest into Wave 3.

- **Plan 04: add `src/main/java/com/paralife/bot/BotClient.java` to `files_modified`.** onClose hook is a real source change.

- **Plan 05: `--duration` switch to `int` seconds.** Aligns with CONTEXT.md D-16 ("`--duration=<seconds>`"). Operators don't expect ISO-8601.

- **Plan 06: add `src/main/java/com/paralife/websocket/SessionRegistry.java` to `files_modified`** if `getSessions()` doesn't exist. Verify with `grep -n 'public.*getSessions\|public.*streamSessions' src/main/java/com/paralife/websocket/SessionRegistry.java` before execute.

- **Plan 02: factor TICK-HEALTH grep into a unit test, not a build-time check.** Acceptance criterion `grep -E 'TICK-HEALTH.*source=' ... returns nothing` is fragile (formats change, regex drifts). A test that registers a workload, captures TickHealthMonitor logs via ListAppender, and asserts `noneMatch(line -> line.contains("source="))` is more durable.

- **Plan 05: pin Picocli version compatibility note.** RESEARCH.md verified 4.7.7; pin in version catalog or `dependency-management` block, not just `implementation` declaration. Phase 21 benchmarks should not float.

- **Plan 06: add 5s budget to AttributionRebindTest awaitility.** Reduce flake risk on slow runners.

- **Cross-plan: add a "dry-run" smoke task.** `./gradlew loadHarnessJar && java -jar build/libs/*-load-harness.jar --help` should be a Wave 4 acceptance check, not just Plan 05 manual. Lock the Pitfall 5 invariant (no Spring banner in `--help`, < 1s startup).

## 5. Risk Assessment

**Overall: MEDIUM-HIGH**

Justification:
- HIGH risks all in Plan 03: cardinality/bucket race + stalled-bucket lifecycle + missing files. These cause silent gauge corruption — exactly the kind of bug Phase 21 benchmarks would surface late after operators trust the dashboards. Fix before execute.
- MEDIUM risks (Plan 04/05/06 dependency + UX issues) are mechanical fixes during plan amendment; low chance of surfacing as runtime bugs but high chance of breaking the plan's own acceptance criteria during execute.
- Per-plan goal achievement: Plans 01, 02, 04, 05, 06 achieve their phase-goal slice cleanly given the noted fixes. Plan 03 currently does NOT achieve "metrics correctly tagged + bounded" without the bucket-keying fix.
- Phase goal (SCALE-03/04/05): once Plan 03 hardened, plans collectively close the requirements with end-to-end attribution lock via Plan 06 AttributionRebindTest.

Recommend: amend Plan 03 (bucket-keying + stalled lifecycle + files_modified), amend Plan 04/05/06 file lists and `depends_on`, then execute.

---

## Codex Review (gpt-5.5, reasoning=high)

## Summary

The phase is well-scoped around the right dependency chain: client identity headers, server attribution, metrics, fleet refactor, standalone harness, then end-to-end docs/tests. The plans are unusually thorough, with strong traceability to SCALE-03/04/05 and good attention to cardinality, STALLED rebinds, and BotRunner compatibility. Main risks are over-specification in implementation details, a few contradictions between plans, and some likely brittle test designs. I would approve the overall direction, but tighten the contracts before execution so implementers do not build conflicting behavior.

## Strengths

- **Clear phase decomposition:** `18-01` and `18-03` can land independently, `18-02` consumes both, `18-04` prepares the fleet, `18-05` builds the harness, and `18-06` closes the loop.
- **Good cardinality discipline:** `18-03-PLAN.md / Task 2` correctly treats harness IDs as an operational risk and requires bounded tags plus overflow behavior.
- **Good forward-compat seam:** `18-04-PLAN.md / Task 1` keeps 999.2 support to a `BotFactory` seam instead of redesigning bot/entity ownership now.
- **BotRunner preservation is explicit:** `18-04-PLAN.md / Task 2` keeps the 100-bot operator path in scope, satisfying SCALE-05 rather than letting the harness replace it implicitly.
- **STALLED rebind risk is recognized:** `18-06-PLAN.md / Task 1` targets the subtle failure mode where attribution could silently degrade to `unknown`.
- **Docs are treated as deliverables:** `18-06-PLAN.md / Task 2` gives Phase 21 enough operational surface to run repeatable harness sweeps.

## Concerns

- **HIGH — Contradictory TICK-HEALTH requirement in `18-02-PLAN.md`:** The plan’s `must_haves` says TICK-HEALTH log markers gain `source=server`, while Task 2 says TICK-HEALTH stays scalar and no `source=` should appear. This must be resolved before execution.

- **HIGH — `BotIdentity` invariants are incomplete in `18-01-PLAN.md / Task 1`:** The proposed public record allows invalid combinations such as `new BotIdentity("operator", Optional.of("h1"))`, which would make `BotClient` send a harness header for an operator source. The compact constructor also validates but does not normalize/truncate direct-constructor harness IDs.

- **HIGH — `18-03-PLAN.md / Task 2` may be too invasive for Wave 1:** Replacing scalar active/stalled gauges with inc/dec bucket mutation touches admission, cleanup, stalled, rebind, and action paths. That is correctness-sensitive and depends on exact lifecycle accounting. A missed decrement will make gauges drift.

- **HIGH — AdmissionGate ownership conflict between `18-02` and `18-03`:** `18-02-PLAN.md / Task 2` says Plan 03 owns `AdmissionMetrics`, but also changes `AdmissionGate.evaluate` and rejection flow. `18-03-PLAN.md / Task 2` also edits `AdmissionGate` call sites. This is likely to cause merge and sequencing confusion.

- **MEDIUM — MeterFilter overflow design may not actually “fold 65th+” as written:** `MeterFilter.maximumAllowableTags` behavior needs careful verification. The plan assumes `onMaxReached.map` can rewrite over-cap IDs into `harness=overflow`, but Micrometer filter ordering and already-registered meters can be subtle. `CardinalityCapTest` is essential, but the plan should not over-prescribe an implementation until proven.

- **MEDIUM — Append JSONL behavior conflicts with D-17 wording:** `18-05-PLAN.md / Task 1` uses atomic rename only for the header and append+SYNC for counters, while the context says reports are “always” written by temp+rename. This is reasonable engineering, but it should be documented as an intentional interpretation, not hidden in implementation.

- **MEDIUM — Picocli parsing in `18-05-PLAN.md` is likely wrong:** `CommandLine.execute(args)` only works on a `@Command` with `Runnable`/`Callable` or subcommands. `LoadHarnessOptions` is only an option holder, so tests using `execute` may not behave as intended. Use `parseArgs`/`populateCommand`, or make `LoadHarness` the `@Command`.

- **MEDIUM — Duration format inconsistency:** Context says `--duration=<seconds>`, but `18-05-PLAN.md` uses ISO-8601 `Duration` values like `PT5S`. Pick one. Operators will expect `--duration=300` from the spec examples.

- **MEDIUM — Tests may be brittle or slow:** Several plans use real WebSocket servers, logback appenders, direct lifecycle forcing, and slow load runs. That is appropriate for a few integration tests, but too many such tests will make the phase hard to execute quickly.

- **LOW — Harness ID validation differs by layer:** Plans mention alphanumeric+dash for generated IDs, but only CR/LF rejection is enforced. Decide whether arbitrary printable IDs are allowed or whether `[A-Za-z0-9-]` is the actual contract.

- **LOW — Docs target may be excessive:** `18-06-PLAN.md / Task 2` asks for 350-500 lines. The spec should be complete, but line-count targets encourage filler and drift.

## Suggestions

- Fix the TICK-HEALTH decision globally: either no `source=` anywhere for TICK-HEALTH, or a constant `source=server`; the current plans say both. Given D-12, I recommend keeping it scalar and untouched.

- Strengthen `BotIdentity`:
  - Enforce `source=harness` iff `harnessId` is present.
  - Normalize/truncate in the compact constructor, not just `harness()`.
  - Reject CR/LF and other control characters.
  - Consider a regex for harness IDs if docs promise alphanumeric plus dash.

- Move all admission metric API edits into `18-03`, and let `18-02` only consume `AttributionTagger.formatLogFields`. Avoid having both plans reshape `AdmissionGate`.

- Reconsider per-bucket active/stalled gauge implementation. If possible, derive buckets from session registry/world state snapshots instead of manual inc/dec lifecycle accounting. If inc/dec remains, add tests for every release path: graceful close, stalled hold, stalled expiry, rebind success, reject after placement failure, and duplicate close.

- In `18-05`, make CLI syntax match docs:
  - Prefer `--duration=300` seconds if that is the operator contract.
  - Accept ISO-8601 as an optional convenience only if low-cost.
  - Ensure env-var defaults are tested with Picocli’s actual behavior.

- Keep the harness integration test modest. A 10- or 20-bot embedded test is enough for CI; leave 200/1000-bot runs as manual or slow-gated Phase 21 checks.

- Clarify JSONL durability in `18-HARNESS.md`: overwrite is atomic temp+rename; JSONL header is atomic; counter lines are append+SYNC and readers must tolerate a truncated final line.

## Risk Assessment

**Overall risk: MEDIUM-HIGH.**

The architecture is sound and aligned with the milestone goals, but implementation risk is elevated because the plans touch session lifecycle, admission accounting, WebSocket reconnect behavior, metrics cardinality, Gradle packaging, and a new CLI tool in one phase. The highest-risk area is `18-03` active/stalled per-source gauge accounting, followed by the plan contradictions around TICK-HEALTH and AdmissionGate ownership. Resolve those before execution and the phase drops closer to **MEDIUM** risk.

---

## OpenCode Review (kimi-k2.6 via OpenRouter)

## Summary

The six plans for Phase 18 form a thorough, well-structured implementation of the external load harness and server-side attribution infrastructure. The design correctly elevates `BotClient` handshake identity, introduces a `BotFleet` async abstraction to break the 100-bot ceiling, and builds a bounded-cardinality Micrometer safety net. Documentation and forward-compatibility (the `BotFactory` seam, `source=offspring` reservation) are treated as first-class deliverables. However, there is a dependency-ordering ambiguity between **Plan 02** and **Plan 03** around `AdmissionGate.java`, and **Plan 05** contains a functional bug in its JSON overwrite report implementation where static header fields are dropped after the first interval.

## Strengths

* **Clear architectural layering.** Plans are split cleanly: client identity (01), server ingress (02), metrics plumbing (03), fleet refactor (04), harness main/CLI (05), and integration lock/docs (06). This makes parallel execution mostly safe.
* **Constructor-sprawl mitigation.** **Plan 01** introduces `BotClientOptions` and `BotIdentity` so the new identity field can be injected without breaking every existing test call site.
* **Defensive header handling.** Source values are taxonomy-filtered, harness IDs are truncated to 32 chars, and CR/LF are rejected before the values touch the Micrometer registry (**Plan 02** + **Plan 01**).
* **Cardinality safety net.** **Plan 03** uses `MeterFilter.maximumAllowableTags` with `replaceTagValues` (not `deny()`, avoiding Micrometer issue #4971) and a warn-once log. This directly mitigates T-18-02.
* **End-to-end rebind verification.** **Plan 06**'s `AttributionRebindTest` targets the phase's highest-risk invariant: STALLED-pivot rebind silently losing attribution. The negative assertion (`source=unknown` must not absorb the rebound bot) is exactly the right shape.
* **Forward-compat seam.** **Plan 04**'s `BotFactory.create(..., claimEntityId, claimToken)` is minimal but correctly positioned for backlog 999.2 without over-engineering Brain×Entity decoupling.

## Concerns

* **Plan 03 and Plan 02 overlapping on `AdmissionGate.java` (HIGH).**  
  **Plan 03** (wave 1) lists `AdmissionGate.java` in `files_modified` and instructs updating `AdmissionGate.reject(...)` to call `metrics.incRejected(token, session)`. **Plan 02** (wave 2, which *depends on* 03) also edits `AdmissionGate.java` to add `evaluate(req, session)` and change the `reject` helper signature. Because Plan 03 runs first, it cannot assume the session-bearing `reject` helper exists yet. This creates either a compilation break or duplicated/conflicting changes.  
  *Reference: 18-03-PLAN.md Task 2 action step "Update incRejected call sites" and 18-02-PLAN.md Task 2 Step 1.*

* **Plan 05 overwrite report loses header fields after first interval (MEDIUM).**  
  In **Plan 05**, `computeSnapshot` calls `ReportSnapshot.counters(...)` which sets all header fields (`harnessId`, `serverUri`, `targetCount`, etc.) to `null`. In overwrite mode, the first periodic reporter VT write replaces the initial header object with a counters-only object. After the first 30-second tick, the report permanently loses its static config.  
  *Reference: 18-05-PLAN.md Task 2 Step 2 `computeSnapshot` method.*

* **Plan 04 `BotFleet.currentRegistered()` drifts on STALLED-pivot reconnect (MEDIUM).**  
  `liveCount` is incremented only inside the launch VT when `awaitRegistered` succeeds. It is decremented via `bot.onClose(...)`. When a BotClient reconnects after a STALLED-pivot (Phase 17), it does so inside its own internal loop—not through `BotFleet`—so `liveCount` is never re-incremented. `currentRegistered()` will undercount for long-running harnesses. The JSON report mitigates this by also polling `b.isRegistered()`, but the Plan should document that `BotFleet`'s counters are best-effort for the ramp window only.  
  *Reference: 18-04-PLAN.md Task 1 BotFleet implementation.*

* **Plan 03 per-bucket gauge map never evicts entries (LOW).**  
  `activeBuckets` and `stalledBuckets` in `AdmissionMetrics` grow unbounded for every unique `(source, harness)` tag combo observed. While the cardinality cap limits this to ~256 entries, stalled-session buckets for transient harness IDs could accumulate briefly. The map is short-lived (JVM lifetime) and small; acceptable, but worth noting.

* **Plan 06 `AttributionRebindTest` assumes direct handler access (LOW).**  
  The test invokes `handler.markStalled(session, tick)` directly. If `markStalled` is private, the test (in `com.paralife.admission`) cannot access it without reflection or a package move. The plan should verify visibility or add a test-only accessor.  
  *Reference: 18-06-PLAN.md Task 1.*

* **Plan 05 Picocli `Duration` conversion unverified (LOW).**  
  `LoadHarnessOptions` declares `public Duration duration;` with a default of `PT0S`. Picocli 4.7 does not ship a built-in `Duration` converter by default (unlike `File`, `Path`, etc.). If absent, this will throw at runtime. A custom `ITypeConverter<Duration>` should be provided or verified.  
  *Reference: 18-05-PLAN.md Task 1 Step 2.*

## Suggestions

* **Disentangle `AdmissionGate.java` ownership.** Let **Plan 02** own *all* edits to `AdmissionGate.java` (adding the session-bearing `evaluate`/`reject` signatures). **Plan 03** should own `AdmissionMetrics.java` and call-site updates in `WorldWebSocketHandler`, `ActionResolver`, and `OutboundSender` *only*. The `AdmissionGate` call site update should move to **Plan 02** (where it compiles naturally against the new metric API).
* **Fix overwrite-mode header retention in `ReportWriter`.** Store the initial header snapshot in `LoadHarness` and merge it with live counters in `computeSnapshot` for overwrite mode, so every snapshot includes `harnessId`, `serverUri`, etc.
* **Document `BotFleet` counter semantics.** Add a Javadoc/note in `BotFleet.java` stating that `currentRegistered()` tracks the launch VT's admitted count and may undercount post-reconnect; the server-side gauge and per-bot `isRegistered()` polling are the authoritative sources for long-running reports.
* **Add `Duration` converter or switch to `long` seconds.** Either add a `picocli.ITypeConverter<Duration>` or change `--duration` to a `long` in seconds to avoid Picocli runtime surprises.
* **Verify `loadHarnessJar` startup time.** The plan uses Spring Boot's `BootJar` (which uses `JarLauncher`). Per **Pitfall 5**, check that `java -jar build/libs/*-load-harness.jar --help` starts in < 1s. If not, switch to a plain `Jar` task with an explicit `Main-Class` manifest.

## Risk Assessment

**Overall Risk: MEDIUM**

Justification: The plans are deeply researched and hit the phase requirements (SCALE-03/04/05) comprehensively. Risk is elevated from LOW by two execution hazards: (1) the **Plan 02/03 AdmissionGate overlap** could cause a compilation break or require a mid-wave course correction, and (2) the **Plan 05 overwrite report bug** would produce silently incorrect benchmark artifacts. Both are straightforward to fix with the suggestions above. The extensive test scaffolding—especially `AttributionRebindTest`, `CardinalityCapTest`, and the `BotRunner` regression tests—provides strong mitigation against regressions in the Phase 17 admission contract.

---

## Consensus Summary

Overall risk verdicts split: **Gemini = LOW**, **OpenCode = MEDIUM**, **Codex = MEDIUM-HIGH**, **Claude = MEDIUM-HIGH**. The split is itself a signal — Gemini reads the plans as well-mitigated by their own architectural choices; the other three flag plan-internal contradictions and ownership boundaries that will bite at execute time. Treat the median (MEDIUM) as the working assumption and resolve the cross-plan contradictions below before Wave 1.

### Agreed Strengths

- **Cardinality safety net** — `MeterFilter.maximumAllowableTags` + `replaceTagValues` avoiding Micrometer issue #4971's `deny()` memory leak (Gemini, OpenCode).
- **AttributionRebindTest catches the STALLED-pivot attribution loss** — phase's most subtle failure mode locked by Plan 06 (Gemini, Claude, OpenCode).
- **Wave decomposition / dependency sequencing is clean** — independent landings for 18-01 and 18-03, then 18-02 consumes both (Gemini, Codex, OpenCode).
- **BotRunner preservation is explicit** — 100-bot operator path stays in scope alongside the new harness, satisfying SCALE-05 (Codex, Claude).
- **`BotFactory` forward-compat seam for 999.2** is minimal and correctly placed (Codex, OpenCode).

### Agreed Concerns (highest priority — fix before execute)

1. **HIGH — Plan 02 / Plan 03 cross-edits to `AdmissionGate.java` collide** (Codex, OpenCode, Claude — three of four).
   - OpenCode pinpoints the compilation break: Plan 03 Wave 1 calls `metrics.incRejected(token, session)` from `AdmissionGate.reject(...)`, but the session-bearing `reject` signature only arrives in Plan 02 Wave 2.
   - Codex names it an "ownership conflict": both plans reshape `AdmissionGate`.
   - Claude additionally flags missing `WorldWebSocketHandler.java` in Plan 03's `files_modified`, which Plan 02 also edits.
   - **Recommended fix (Codex + OpenCode agree):** consolidate all `AdmissionGate.java` edits into Plan 02; Plan 03 owns `AdmissionMetrics.java` plus the call-site updates in `WorldWebSocketHandler` / `ActionResolver` / `OutboundSender` only.

2. **HIGH — Plan 02 internally contradicts itself on TICK-HEALTH `source=` field** (Codex HIGH; Claude flags the brittle grep acceptance criterion).
   - `must_haves` says TICK-HEALTH log markers gain `source=server`; Task 2 says no `source=` should appear and TICK-HEALTH stays scalar.
   - Codex recommends keeping it scalar (consistent with D-12); Claude recommends replacing the build-time grep with a `ListAppender`-based unit test.

3. **HIGH/MEDIUM — Per-bucket active/stalled gauge accounting is fragile** (Codex HIGH, Gemini MEDIUM, OpenCode LOW + adjacent).
   - Gemini: idempotent-cleanup double-decrement via `handleTransportError` → `afterConnectionClosed`. Fix: guard `decActiveBucket(session)` inside the existing `if (wasRegistered)` block.
   - Codex: too many lifecycle paths (admission, cleanup, stalled, rebind, action) — missed decrement = silent drift. Suggests deriving buckets from snapshots instead of inc/dec.
   - OpenCode: `activeBuckets` / `stalledBuckets` maps never evict (small risk, but worth a note).

4. **MEDIUM — Plan 05 `--duration` format inconsistency** (Codex, Claude, OpenCode — three of four).
   - Context (D-16) says `--duration=<seconds>`; plan code uses ISO-8601 `Duration` like `PT5S`.
   - Three independent reviewers want `int seconds`. OpenCode adds: Picocli 4.7 has no built-in `Duration` converter, so the `PT0S` default may fail at runtime without a custom `ITypeConverter<Duration>`.
   - Codex separately flags `CommandLine.execute(args)` won't work on an option-holder class — needs `parseArgs` / `populateCommand`, or make `LoadHarness` itself the `@Command`.

5. **MEDIUM — Plan 05 overwrite-mode report drops static header fields** (OpenCode — sole catch, but mechanically clear bug).
   - `computeSnapshot` calls `ReportSnapshot.counters(...)` which nulls `harnessId`, `serverUri`, `targetCount`. After the first 30s reporter VT tick, the overwrite report permanently loses static config.
   - Fix: store header snapshot in `LoadHarness`; merge with live counters in every overwrite write.

6. **MEDIUM — `BotFleet` counter semantics under STALLED-pivot rebind** (OpenCode + adjacent Gemini/Claude concerns about `onClose` hook).
   - OpenCode: BotClient reconnects through its own internal loop (Phase 17), not through `BotFleet`, so `liveCount` is decremented but never re-incremented on rebind. `currentRegistered()` undercounts for long-running harnesses.
   - Mitigation: document `BotFleet` counters as best-effort for ramp window; rely on server-side gauge + per-bot `isRegistered()` polling for steady-state.

### Divergent Views

- **`BotIdentity` invariants** — Codex (HIGH) flags that `new BotIdentity("operator", Optional.of("h1"))` is legal; recommends enforcing `source=harness` iff `harnessId.isPresent()` plus normalization in the compact constructor. The other three reviewers don't surface this.
- **JSONL durability** — Gemini flags Windows file-locking races on `tail -f` readers (suggests retry loop); Codex wants the temp+rename-vs-append-SYNC mismatch documented as intentional in `18-HARNESS.md`. Claude/OpenCode silent.
- **AttributionRebindTest mechanics** — OpenCode flags package-visibility risk for `handler.markStalled(session, tick)` direct call; others assume it works.
- **Test scope** — Codex argues 200/1000-bot harness integration tests should be slow-gated for Phase 21; Gemini and OpenCode read the existing test scaffolding as appropriately sized.
- **Risk weighting** — Gemini reads the plans as low-risk because the architecture leans on stable libraries with explicit threat-model entries. The other three weight plan-internal contradictions higher than architectural soundness; this is the gap to close before execute.

### Recommended Plan Amendments (consensus-driven, before `gsd-execute-phase 18`)

1. Move all `AdmissionGate.java` edits into Plan 02; trim Plan 03's `files_modified` to `AdmissionMetrics.java` plus call sites in `WorldWebSocketHandler` / `ActionResolver` / `OutboundSender`.
2. Resolve TICK-HEALTH contradiction in Plan 02 — recommend keeping scalar (Codex). Replace the grep acceptance criterion with a `ListAppender` assertion (Claude).
3. Plan 05: change `--duration` to `int seconds` (D-16); replace `CommandLine.execute` with `parseArgs`/`populateCommand` or annotate `LoadHarness` as `@Command`.
4. Plan 05: fix `computeSnapshot` overwrite-mode header retention.
5. Plan 03: guard `decActiveBucket` inside `if (wasRegistered)` in `cleanupBot`; add tests for every release path (graceful close, stalled hold, stalled expiry, rebind success, reject after placement failure, duplicate close).
6. Plan 04: document `BotFleet.currentRegistered()` as best-effort ramp-window metric; cite server-side gauge as authoritative for steady state.
7. Plan 01: tighten `BotIdentity` invariants — `source=harness` ⇔ `harnessId.isPresent()`, normalize/truncate in compact constructor, reject control chars.

To incorporate: `/gsd-plan-phase 18 --reviews`
