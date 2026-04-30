---
task: code
compiled_at: 2026-04-29
compiler: claude (host)
source_files:
  - 18-REVIEW-B-fleet-harness-CLAUDE-INLINE.md
  - 18-REVIEW-B-fleet-harness-CLAUDE-REFERENCE.md
  - 18-REVIEW-B-fleet-harness-INLINE.md
  - 18-REVIEW-B-fleet-harness-REFERENCE.md
covered_files:
  - src/main/java/com/paralife/bot/BotFleet.java
  - src/main/java/com/paralife/bot/BotClient.java
  - src/main/java/com/paralife/bot/BotLauncher.java
  - src/main/java/com/paralife/bot/BotRunner.java
  - src/main/java/com/paralife/bot/SpeciesMix.java
  - src/main/java/com/paralife/bot/RampUpSpec.java
  - src/main/java/com/paralife/harness/LoadHarness.java
  - src/main/java/com/paralife/harness/SpeciesMixConverter.java
  - src/main/java/com/paralife/harness/RampUpConverter.java
  - src/main/java/com/paralife/harness/ReportWriter.java
  - src/test/java/com/paralife/bot/BotRunnerOperatorTagTest.java
---

# Phase 18 Chunk B — Cross-AI Review (Compiled)

Single deduplicated finding list across the 4 source review files. Severity is the
host-Claude consensus call after re-reading current source line-by-line. Reviewer
attribution preserved per finding so the originals can be deleted.

---

## 1. Source files & reviewer matrix

| Reviewer | Mode      | Source file                                       |
|----------|-----------|---------------------------------------------------|
| claude   | inline    | `18-REVIEW-B-fleet-harness-CLAUDE-INLINE.md`      |
| claude   | reference | `18-REVIEW-B-fleet-harness-CLAUDE-REFERENCE.md`   |
| gemini   | inline    | `18-REVIEW-B-fleet-harness-INLINE.md`             |
| codex    | inline    | `18-REVIEW-B-fleet-harness-INLINE.md`             |
| opencode | inline    | `18-REVIEW-B-fleet-harness-INLINE.md`             |
| codex    | reference | `18-REVIEW-B-fleet-harness-REFERENCE.md`          |
| opencode | reference | `18-REVIEW-B-fleet-harness-REFERENCE.md`          |
| gemini   | reference | FAILED (premature stream close)                   |

Coverage: 7 successful reviewer×mode passes plus 1 host pass. Every HIGH below has
≥3 independent reviewers flagging it.

---

## 2. Consolidated findings

### HIGH

#### H-01-livecount — `BotFleet.liveCount` underflow on partial-failure shutdown

- **Raised by:** claude-inline H1, claude-ref H3, gemini-inline §1, codex-inline §1, codex-ref §2, opencode-inline (Critical§2 / Medium), opencode-ref (Bug)
- **Severity (consensus):** HIGH
- **Claim:** `BotFleet.launch` registers `bot.onClose(() -> liveCount.decrementAndGet())` unconditionally before `connect()`, but `liveCount.incrementAndGet()` only fires inside the `if (ok)` branch after `awaitRegistered` succeeds. Failed bots cause a decrement without a prior increment, so `currentRegistered()` underflows below zero.
- **Verification:** `BotFleet.java:94` registers the unconditional decrement callback. `BotFleet.java:102-122` runs the launch VT — increment is at line 107 inside `if (ok)`; failure paths at lines 110-112 and 115-121 increment `connectFailuresTotal` only. `BotClient.disconnect` at `BotClient.java:222` calls `fireCloseCallbacks` after closing the session; `fireCloseCallbacks` at `BotClient.java:256-267` is CAS-guarded but fires regardless of whether the prior register-success branch ran. `BotFleet.shutdown` at line 193 invokes `disconnect()` on every bot; result: every never-registered bot decrements `liveCount` exactly once. With K failures and zero successes, post-shutdown `liveCount = -K`.
- **My verdict:** AGREE. Reproducible just by pointing the harness at an unreachable URI; `current_registered` then ships negative in the JSON report. The Javadoc at `BotFleet.java:144-160` even calls the field "best-effort", but a negative steady-state value is wrong, not imprecise.
- **Recommended fix:** Bind the decrement to a successful prior increment via a per-bot flag captured in the launch VT closure. The decrement callback should only act when that flag is set, so callbacks fired after a failed launch (whether from `disconnect()` during shutdown or from `@OnWebSocketClose` after a server-initiated close) become no-ops. Add a regression test that points a small fleet at an unreachable URI, drives `shutdown`, and asserts `currentRegistered() >= 0` and equal to the count of successful bots.

#### H-02-counters-cleared — Signal-path final report zeroes per-bot counters

- **Raised by:** claude-ref H1, opencode-inline (Critical)
- **Severity (consensus):** HIGH
- **Claim:** The shutdown hook calls `fleet.shutdown()` before signalling `exitLatch`. `BotFleet.shutdown` clears the bot list; the main thread then computes its final-report counters by iterating `fleet.getBots()`, which is now empty. All per-bot sums in the final JSON (actions, perceptions, syncs, respawns, e408) read as zero on signal exit.
- **Verification:** `LoadHarness.java:226-230` — hook body sets `exitReason`, calls `fleet.shutdown()`, then `exitLatch.countDown()`. `BotFleet.java:188-201` — `shutdown` runs `bots.clear()` at line 197 after disconnecting. `LoadHarness.java:259-267` — main thread wakes from `exitLatch.await`, then calls `writeFinalReport`. `LoadHarness.java:314-323` — `computeCountersSnapshot` iterates `fleet.getBots()` (returns empty `List.copyOf` of the cleared list). Only the fleet-level atomics survive: `peakRegistered` (`highWater` not reset, `BotFleet.java:200`), `connectFailuresTotal` (never reset), and `currentRegistered` (drained to 0, or negative per H-01).
- **My verdict:** AGREE. Distinct from H-03 — even if the JVM doesn't kill the writer mid-rename, the *content* is already wrong because the counters are summed from an empty list. Defeats D-17 contract on the most common signal-path operator workflow (Ctrl-C during benchmarking).
- **Recommended fix:** Either remove `fleet.shutdown()` from the shutdown hook entirely (let the main thread handle it after writing the final report — `BotFleet.shutdown` is already CAS-idempotent so a second call from the hook is a no-op), or compute and capture the counter snapshot inside the hook before clearing bots. The first option is simpler: hook does only `exitReason` + `exitLatch.countDown()`; main thread writes report (bot list still populated), then calls `fleet.shutdown()`.

#### H-03-signal-write-race — Final report write may not finish before JVM exit

- **Raised by:** claude-inline H2, claude-ref H2, gemini-inline §2, codex-inline §2, codex-ref §1, opencode-inline (Critical), opencode-ref (Race Found)
- **Severity (consensus):** HIGH
- **Claim:** On SIGINT/SIGTERM the shutdown hook signals `exitLatch` but does not perform the final write. The main thread (not a shutdown hook) performs `writeFinalReport` after `exitLatch.await` returns. JVM signal-shutdown halts after all hooks return without joining non-hook non-daemon threads, so the main thread can be killed mid-write.
- **Verification:** `LoadHarness.java:226-230` — hook does NOT call `writeFinalReport`. `LoadHarness.java:266-269` — main thread does `writeFinalReport` then `fleet.shutdown` after waking from `exitLatch.await`. JVM specification: after all shutdown hooks complete, the VM halts unless other shutdown hooks are still running; non-hook threads receive no join.
- **My verdict:** AGREE that the race exists; PARTIAL on severity framing. Atomic-rename in `ReportWriter.writeOverwrite` (`ReportWriter.java:71-81`) means readers will see either the prior valid file or the new valid file, never a torn one. So the failure mode is "final report is the previous interval's snapshot with no `exit_reason` field", not "corrupted JSON". Combined with H-02 the wider impact is: on signal exit the operator is *guaranteed* to lose the post-signal counter delta, and the file may further lack `exit_reason`. The contract violation is real.
- **Recommended fix:** Move the final-report write into the shutdown hook itself, before any `fleet.shutdown` (so H-02 is also addressed by the same change). Keep the `duration-reached` write on the main thread — there is no signal race on that path. Set a `finalReportWritten` AtomicBoolean and let both paths CAS-guard the write so it runs exactly once whichever wins.

### MEDIUM

#### M-01-tmp-write-race — `reporterVT` and final write race on shared `.tmp` file

- **Raised by:** claude-ref M1, gemini-inline §2, opencode-ref (Severity MEDIUM)
- **Severity (consensus):** MEDIUM
- **Claim:** Periodic `reporterVT` and the main-thread final write both call `writer.writeOverwrite(reportOut, ...)`, which uses the same `<target>.tmp` path. Concurrent invocations can cause one stream to truncate-and-overwrite the other's bytes before the rename, then both attempt `Files.move` on the same `.tmp`.
- **Verification:** `LoadHarness.java:206-221` — `reporterVT` loops on `Thread.sleep(reportIntervalSeconds * 1000L)` then calls `writeCounters`, which calls `writer.writeOverwrite`. `LoadHarness.java:213-214` — VT checks `exitReason.get() != null` before each write but the check happens *before* the write call, not held for its duration. `ReportWriter.java:71-81` — `writeOverwrite` opens `tmp` with `CREATE | TRUNCATE_EXISTING`; two concurrent calls each truncate the same path. `ReportWriter.java:74` — `resolvedSibling(target, ".tmp")` is deterministic per target, so two writers compete on one filename.
- **My verdict:** AGREE. The window is narrow but real because `reporterVT.interrupt()` in `LoadHarness.java:280` runs in the `finally` block AFTER `writeFinalReport`, not before. There is no synchronization on the writer or the path. The corruption mode requires byte-level interleave so it is rare but not impossible; the JSON-parse-fail mode will look like a flake.
- **Recommended fix:** Interrupt the reporter VT and join it (with a short bounded timeout) before calling `writeFinalReport`. Alternatively make `ReportWriter.writeOverwrite` and `appendJsonlCounter` `synchronized` on the writer instance — cheap, both writers go through the same lock, no API change. The interrupt-and-join approach is preferable because it also preserves the invariant that the final write is the last write.

#### M-02-count-no-cap — `LoadHarness --count` has no upper bound

- **Raised by:** claude-inline M1, claude-ref M2, opencode-inline (Low/Info §5), opencode-ref (note)
- **Severity (consensus):** MEDIUM
- **Claim:** `BotRunner` enforces a hard 100-bot cap with a helpful redirect message; `LoadHarness.validateAndDefault` only checks `count >= 1`. D-02 documents a 5000-VT design ceiling per JVM but nothing in the harness enforces or warns. `--count 1000000` silently spawns a million VTs.
- **Verification:** `LoadHarness.java:128-150` — `validateAndDefault` validates `count >= 1` (line 129-131), `report-interval` range (132-135), `report-mode` enum (136-139), `duration >= 0` (140-143). No upper bound on `count`. Compare `BotRunner.java:104-110` which rejects `count > MAX_BOTS` (100) with a redirect to the harness.
- **My verdict:** AGREE. Operator finger-slip is the realistic threat (`--count 10000` instead of `1000`). At minimum a WARN log when crossing 5000 mirrors the documented design ceiling.
- **Recommended fix:** Soft cap — emit a WARN log when `count > 5000` referencing D-02 and recommending the operator split across multiple JVMs. Preferred over a hard cap because the ceiling is described in D-02 as a guideline and operators with bigger boxes should not be blocked.

#### M-03-env-var-misnamed — `--harness-id` env override is broken

- **Raised by:** codex-inline §3, codex-ref §3
- **Severity (consensus):** MEDIUM
- **Claim:** Spec line 158 of `18-HARNESS.md` documents the env override as `PARALIFE_HARNESS_ID`. The `@Option` defaultValue at `LoadHarness.java:67` reads `${env:PARALIFE_HARNESS_HARNESS_ID}` (note the doubled `HARNESS`), so headless runs that follow the spec keep auto-generating fresh ids and break attribution correlation across log → report.
- **Verification:** `18-HARNESS.md:158` — `| `--harness-id` | `PARALIFE_HARNESS_ID` | auto-generated | Process-level identity tag |`. `LoadHarness.java:66-69` — `defaultValue = "${env:PARALIFE_HARNESS_HARNESS_ID}"`. All other env vars at lines 56-101 are correctly named (`PARALIFE_HARNESS_SERVER_URI`, `PARALIFE_HARNESS_COUNT`, etc.) — only this one has the doubled prefix.
- **My verdict:** AGREE. Pure typo. Operator following the docs cannot pin a stable harness id from the environment; will surface as "harness ids change every CI run" with no obvious cause.
- **Recommended fix:** Rename the env-var reference in the `@Option` annotation to match the spec (`PARALIFE_HARNESS_ID`). Add an integration test that exports the env var and asserts the harness picks it up — `LoadHarnessOptionsTest` is the natural home. No spec change needed; the spec is the source of truth.

#### M-04-nan-species — `SpeciesMixConverter` accepts `NaN` fractions

- **Raised by:** codex-inline §4, codex-ref §4
- **Severity (consensus):** MEDIUM
- **Claim:** `Double.parseDouble("NaN")` succeeds; `SpeciesMix`'s record-compact constructor checks `Math.abs(sum - 1.0) > 0.001`, but with any NaN component the sum is NaN, `Math.abs(NaN)` is NaN, and `NaN > 0.001` is false. So `--species-mix NaN:0.5:0.5` constructs a valid `SpeciesMix`. Then `pickFor` does `frac < cFrac` against NaN (always false), routing every bot to the fallback species silently.
- **Verification:** `SpeciesMixConverter.java:27-31` — calls `Double.parseDouble` then `new SpeciesMix(c, m, s)`. `SpeciesMix.java:30-39` — constructor checks `Math.abs(sum - 1.0) > 0.001` (false for NaN) and `cFrac < 0 || mFrac < 0 || sFrac < 0` (false for NaN since NaN comparisons are always false). `SpeciesMix.java:68-71` — `pickFor` does `frac < cFrac` and `frac < cFrac + mFrac`; against any NaN bound both comparisons return false → all bots route to SPORE.
- **My verdict:** AGREE. Quiet failure — the harness exits 0 with a homogeneous fleet that the operator did not ask for.
- **Recommended fix:** Reject non-finite fractions either in the converter or in the record constructor. Constructor is the right home because it is the single point of construction and protects callers other than the CLI converter as well. Use `Double.isFinite` on each fraction; reject with the same `IllegalArgumentException` style.

#### M-05-operator-tag-test-noop — `BotRunnerOperatorTagTest` does not assert what its name claims

- **Raised by:** claude-inline M3, codex-inline notes, codex-ref coverage notes, opencode-inline (notes)
- **Severity (consensus):** MEDIUM
- **Claim:** The test computes `ops` (the count of sessions tagged `source=operator` with no harness header) but never asserts on it. The Awaitility block re-asserts `rc == 0` only. The author comment acknowledges bots may have disconnected before the assertion runs.
- **Verification:** `BotRunnerOperatorTagTest.java:48-59` — Awaitility body computes `ops` via the stream filter at lines 49-52 and binds it to a local variable, then the only assertion is `assertThat(rc).isEqualTo(0)` at line 58. `ops` is never read after assignment. Test passes if `BotRunner.run` returns 0, regardless of session attribution.
- **My verdict:** AGREE. The Round 2 fix that motivated this test (proving `BotRunner` itself, not just `BotFleet`, passes `BotIdentity.operator()`) is not actually validated. The test compiles and passes for the wrong reason.
- **Recommended fix:** Inspect session attributes during the bot run, not after. Two viable approaches: (a) reduce `duration-seconds` to 0 and capture session attrs inside an `Awaitility.untilAsserted` poll *while* bots are connected — needs the test to await registered before disconnect; or (b) inject a recording `BotFleet` via the `fleetFactory` seam already exposed at `BotRunner.run(args, fleetFactory, botFactoryFactory)` and assert on the captured `BotIdentity` argument. Option (b) is cleaner and avoids the wall-clock race.

### LOW

#### L-01-no-fsync-overwrite — `writeOverwrite` does not fsync before rename

- **Raised by:** claude-inline L1, claude-ref M3
- **Severity (consensus):** LOW
- **Claim:** `ReportWriter.writeOverwrite` opens the tmp stream with `CREATE | TRUNCATE_EXISTING | WRITE` — no `SYNC` and no explicit `force(true)`. After the stream closes and atomic-rename succeeds, dirty pages may still sit in the page cache. Power-loss between rename and writeback can leave the target file present but short. `appendJsonlCounter` correctly uses `O_SYNC`.
- **Verification:** `ReportWriter.java:75-79` — `Files.newOutputStream(tmp, CREATE, TRUNCATE_EXISTING, WRITE)` then `mapper.writeValue(out, snapshot)` inside try-with-resources. No fsync. Compare `ReportWriter.java:115-117` — `appendJsonlCounter` uses `StandardOpenOption.SYNC`.
- **My verdict:** AGREE on the gap; LOW because the harness is not a database and the spec does not promise crash-safety on power loss. Worth fixing for symmetry with append mode.
- **Recommended fix:** Add `StandardOpenOption.SYNC` to the tmp open in `writeOverwrite` to match the append-mode contract. One-token change with negligible cost on a per-interval cadence.

#### L-02-balanced-detection-tolerance — `SpeciesMix` balanced branch swallows near-balanced weighted mixes

- **Raised by:** claude-inline L2, claude-ref L2, opencode-ref (Severity LOW)
- **Severity (consensus):** LOW
- **Claim:** `pickFor` triggers the balanced round-robin path when `|cFrac - 1/3| < 0.001 && |mFrac - 1/3| < 0.001`. A manually constructed `SpeciesMix(0.334, 0.333, 0.333)` matches and falls into round-robin instead of position-based partitioning, producing symmetric output despite asymmetric input.
- **Verification:** `SpeciesMix.java:58` — checks only `cFrac` and `mFrac`, not `sFrac`. The 0.001 tolerance is wider than the float-equality margin needed to recognise `SpeciesMix.balanced()` itself. `SpeciesMixConverter` would never produce values this close to 1/3 from CLI input, so the gap is reachable only via direct record construction.
- **My verdict:** PARTIAL — agree the branch logic is asymmetric (skips `sFrac` check) but pushback on severity. The CLI converter cannot produce these values; only programmatic callers can. Tighten regardless because the asymmetry is a code smell.
- **Recommended fix:** Use a sentinel rather than tolerance-based detection — for example, an internal `balanced` boolean flag set only by the static `balanced()` factory. That removes the float-tolerance question entirely. Or include `sFrac` in the check and tighten the tolerance to 1e-6.

#### L-03-converter-case-whitespace — Picocli converters are case- and whitespace-sensitive

- **Raised by:** claude-ref L3
- **Severity (consensus):** LOW
- **Claim:** `RampUpConverter` requires exact `instant`, `rate:N`, or `wave:C:S`. Inputs like `"INSTANT"`, `" rate:50"`, `"Rate:50"` all fail the format checks. Operator typos in long shell commands cause non-zero exits.
- **Verification:** `RampUpConverter.java:20-47` — uses literal `equals`/`startsWith` against lowercase strings, no `trim` or `toLowerCase`. `SpeciesMixConverter.java:19-23` — same pattern.
- **My verdict:** AGREE. Robustness nit; CLI ergonomics improvement.
- **Recommended fix:** Apply `value.trim().toLowerCase(Locale.ROOT)` at the entry of each converter before the format checks. Update the existing converter tests to cover the whitespace and case variants.

#### L-04-bot-launcher-removal-deadline — `BotLauncher` `forRemoval=true` lacks a target phase

- **Raised by:** claude-inline L3
- **Severity (consensus):** LOW
- **Claim:** `BotLauncher` is annotated `@Deprecated(since = "0.18", forRemoval = true)` with a Javadoc note "removed in a future phase". No phase number is named, no follow-up ticket exists, and three test files (`LoadTest`, `PopulationDynamicsTest`, `MetabolismIntegrationTest`) still depend on the facade. Without a deadline the deprecation will rot.
- **Verification:** `BotLauncher.java:23-26` — Javadoc claims "removed in a future phase" without specifying. No backlog entry exists in `.planning/ROADMAP.md` for the removal.
- **My verdict:** AGREE. Filing the migration tickets while context is fresh is the cheapest path; once the original authors rotate off the project the migration path becomes archaeology.
- **Recommended fix:** Add a backlog item via `/gsd-add-backlog` capturing the three call-site migrations and the facade deletion as a single phase. Tighten the Javadoc to reference the backlog id.

#### L-05-rampup-wave-interrupt — `RampUpSpec.Wave` re-flags interrupt without exit

- **Raised by:** claude-inline L4
- **Severity (consensus):** LOW (PUSHBACK on actionability)
- **Claim:** `Wave.awaitNext` catches `InterruptedException`, re-flags via `Thread.currentThread().interrupt()`, but does not return — the next iteration continues the launch loop.
- **Verification:** `RampUpSpec.java:59-68` — catches and re-flags but the method just returns normally. Caller (`BotFleet.launch` at `BotFleet.java:86-87`) then proceeds to launch the next bot.
- **My verdict:** PUSHBACK. The next time `Thread.sleep` is called on the same launch thread (next wave boundary), the JDK throws `InterruptedException` immediately because the flag is set, so the loop *will* exit by the next boundary. For instant/rate ramps the flag is observable by callers via `Thread.interrupted()`. Inside `BotFleet.launch` itself there is no other interrupt check, so the loop runs to completion *without* a wave boundary, but `BotFleet.shutdown` will disconnect any bots launched during that brief interval anyway. Behaviour is acceptable as documented.
- **Recommended fix:** None required. If pedantic correctness matters, add `if (Thread.interrupted()) return;` at the top of `BotFleet.launch`'s loop body.

---

## 3. Reviewer-only / divergent items (not surviving verification)

### Gemini §3 — `BotFleet.futures` key collision on repeated `launch()`

- **Raised by:** gemini-inline §3
- **Claim:** `BotFleet.launch` keys futures via `"fleet-" + harnessTag + "-" + i`, where `i` restarts at 0 on every call. Repeated `launch()` calls overwrite earlier futures.
- **Verification:** Confirmed in source at `BotFleet.java:98`. However, every caller (`BotRunner.java:129`, `LoadHarness.java:181`, `BotLauncher.java:30`) instantiates a fresh `BotFleet` per run. No test or production path calls `launch` twice on the same `BotFleet` instance.
- **My verdict:** PUSHBACK. Hypothetical defect — no live caller hits it. Worth filing as a defensive cleanup but not blocking.
- **Action:** Defensive change only. Replace the loop index `i` with an `AtomicInteger` field if desired. Below the LOW bar for merge.

### Codex/OpenCode harness count cap commentary

Both reviewers note that `LoadHarness` does not inherit the `BotRunner` 100-bot cap. That is the intended separation and is captured under M-02. Not a divergence — flagged here only because two reviewers framed it as commentary rather than a bug.

### Opencode L/Info 5 — `BotFleetTest.onCloseHookFiresExactlyOnce` coverage gap

- **Claim:** Existing test exercises two concurrent `disconnect()` calls but not the real `disconnect()` vs Jetty `@OnWebSocketClose` dual path.
- **Verification:** Acknowledged by reviewer themselves: the CAS gate at `BotClient.java:256-267` makes the assertion correct under any caller mix.
- **My verdict:** PUSHBACK. Coverage nit only. The CAS is unconditional; an additional test would not increase confidence.

### Claude-inline L4 (counted under L-05, PUSHBACK retained for clarity)

See L-05 — the interrupt is naturally consumed by the next `Thread.sleep` call.

### Claude-ref M3 — `writeOverwrite` lacks SYNC

Counted under L-01. Severity demoted from MEDIUM to LOW because rename+atomic-rename gives readers a valid file in either pre- or post-write state; the loss is bounded to the *content* of the in-flight write, only on power loss. Append-mode SYNC is correct; symmetry argument supports the LOW fix.

---

## 4. Suggested action plan

**Block merge (HIGH):**
- H-01-livecount — fix the `liveCount` decrement gating; add a regression test covering connect-failure → shutdown.
- H-02-counters-cleared — remove `fleet.shutdown()` from the signal hook; main thread (or hook itself) writes the report before clearing bots.
- H-03-signal-write-race — co-fixed with H-02 by moving the final-report write into the hook OR ensuring the hook joins the main writer before returning.

**Fix before Phase 21 (MEDIUM):**
- M-01-tmp-write-race — interrupt+join `reporterVT` before final write, or `synchronize` writer methods.
- M-02-count-no-cap — soft WARN at `count > 5000`.
- M-03-env-var-misnamed — rename `${env:PARALIFE_HARNESS_HARNESS_ID}` to `${env:PARALIFE_HARNESS_ID}` + integration test.
- M-04-nan-species — reject non-finite fractions in `SpeciesMix` constructor.
- M-05-operator-tag-test-noop — tighten the test via the existing `fleetFactory` seam.

**Backlog / cleanup (LOW):**
- L-01-no-fsync-overwrite — add `SYNC` to `writeOverwrite`'s tmp open.
- L-02-balanced-detection-tolerance — sentinel-based balanced detection.
- L-03-converter-case-whitespace — `trim().toLowerCase()` at converter entry.
- L-04-bot-launcher-removal-deadline — backlog entry for facade removal + 3 call-site migrations.
- L-05 — no action.
- Defensive: monotonic `AtomicInteger` for `BotFleet` future keys (gemini §3) — file as a tiny chore.

**No action:**
- Gemini-inline §3 (futures key collision — hypothetical)
- Opencode L/Info 5 (CAS-test coverage nit)
- Claude-inline L4 (interrupt swallowed — naturally consumed)
