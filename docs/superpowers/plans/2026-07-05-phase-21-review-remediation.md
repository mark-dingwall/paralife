# Phase 21 PR-Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the remaining accepted PR-review findings on `phase-21-scale-benchmark` (scraper robustness, harness fail-soft wiring, hygiene, a transposition guard, and the doc/evidence merge-back to the already-recaptured fixtures).

**Architecture:** All changes are on the existing Phase-21 measurement path (`com.paralife.harness.*` + `docs/`). The meter-swap (HIGH-2) and the live re-capture (HIGH-3) are **already done and committed to the working tree** — the three `docs/benchmarks/bench-{100,500,1000}.json` fixtures already hold the new numbers and the new `paralife.tick.work.ms` meter. This plan does **not** re-capture; it hardens the code that produces those fixtures and rewrites the prose that cites them.

**Tech Stack:** Java 21, JUnit 5 + AssertJ + Mockito, `java.net.http.HttpClient`, Micrometer, bash + jq, Gradle Kotlin DSL.

## Global Constraints

- **Firewall doctrine (CLAUDE.md).** No default-suite `assertThat` on any benchmark magnitude or live-run statistical aggregate. New default-suite tests here assert **mechanism** with **test-owned inputs** only (parser output, a timeout bound, field preservation). Any live-server assertion stays `@Tag("slow")`. If a test only goes green because a multi-tick run survived, it is emergence in disguise — decompose it.
- **Every negative assertion needs a positive control.** A "does not happen / omitted" assertion pairs with a control proving the same harness produces the thing under the opposite input.
- **Gates are RED-first.** A verification gate (grep/jq/a guard test) is not trusted until shown to fire on the exact loss it guards — delete/break the guarded thing, watch it go RED, restore, watch it go silent.
- **Evidence-bound done.** Every "done/passing" claim quotes a command output or a code line.
- **Merge-back.** A slice isn't done until the canonical doc matches shipped code.
- **Do not re-capture.** The fixtures under `docs/benchmarks/` are authoritative. Read numbers **from** them; never invent or recompute them.
- **Match existing style.** `docs/` edits follow `docs/STYLE.md` G1–G10 (cite `file:line`/`D-xx` on contract claims, stable section numbering, terse clause voice, no GSD review-round residue). Touch only what each task requires.
- **No portfolio/interview framing** anywhere in code, comments, commits, or docs.
- **Branch:** all work stays on `phase-21-scale-benchmark` (already checked out in this worktree). Do not open/merge a PR unless asked.
- **Spotless caveat:** `spotlessCheck` uses `ratchetFrom("origin/main")` and **cannot run inside a linked git worktree** (jgit can't resolve the `.git`-file). Do not treat a Spotless failure-to-run as a code defect; note it for a normal-checkout/CI run. `./gradlew test` and `compileJava` run fine in the worktree.

**Commit convention:** end every commit message body with
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

| File | Responsibility | Tasks |
|------|----------------|-------|
| `src/main/java/com/paralife/harness/ServerMetricsScraper.java` | Read-only actuator scraper; whole-scrape budget + URI derivation | 1 |
| `src/test/java/com/paralife/harness/ServerMetricsScraperTest.java` | Parser + URI-derivation + budget-bound unit tests | 1 |
| `src/main/java/com/paralife/harness/LoadHarness.java` | Harness lifecycle; fail-soft scraper build + null-guarded scrape call | 2 |
| `src/test/java/com/paralife/harness/ScrapeLiveIntegrationTest.java` | `@Tag("slow")` live scrape control; close its owned client | 3 |
| `.gitignore` | Anchor `reports/` ignore to repo root | 3 |
| `tools/benchmark/run-tiers.sh` | Repeatable sweep; stale mode-note fix + cumulativity comment; RED-test its jq gate | 3 |
| `src/test/java/com/paralife/harness/ReportSnapshotTest.java` | Field-preservation guard for the 16-arg positional `withServerMetrics` | 4 |
| `docs/BENCHMARKS.md` | Evidence doc — new numbers, isolated-restart methodology, dropped drift caveat | 5 |
| `docs/HARNESS.md` | §6/§11/§12 — meter name, budget wording, eager-vs-lazy correction | 5 |
| `BACKLOG.md` | Reframe the scrape-budget line to the shipped async approach | 5 |

**Deferred to BACKLOG (Non-Goals — do not implement here):**
- **`--actuator-uri` override** (MEDIUM-6): context-path / `management.port` deployments. Localhost-root is the only supported deployment in Phase 21; add a one-line BACKLOG entry in Task 5, no code.
- **`run-tiers.sh` owning the server lifecycle** (per-tier restart baked into the script): would couple the tool to localhost + the boot jar + health-polling. Documented protocol + cumulativity comment is the MVP fix (Task 3/5). Add a one-line BACKLOG entry in Task 5.

---

### Task 1: ServerMetricsScraper — whole-scrape budget via concurrent `sendAsync`, and case-insensitive `wss`

Fixes **HIGH-1** (the per-request `HttpRequest.timeout` bounds only header receipt, not a stalled response *body*, so the "~2s whole-scrape budget" was not actually enforceable) and **LOW-7** (`"wss".equals` is case-sensitive, so `WSS://` derives `http://`). Also removes the now-dead `min(remaining, REQ_TIMEOUT)` sequential-deadline logic (below-the-cut: `REQ_TIMEOUT == TOTAL_BUDGET` made it inert).

**Files:**
- Modify: `src/main/java/com/paralife/harness/ServerMetricsScraper.java`
- Test: `src/test/java/com/paralife/harness/ServerMetricsScraperTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `public ServerMetricsScraper(URI actuatorBase, HttpClient http)` — unchanged public signature (defaults the budget to `TOTAL_BUDGET`).
  - `ServerMetricsScraper(URI actuatorBase, HttpClient http, Duration totalBudget)` — **new package-private** test seam injecting the budget.
  - `public Map<String, Double> scrape(Map<String, String> meterToStatistic)` — unchanged signature; now fires all requests concurrently and harvests each within one shared deadline. Fail-soft omission preserved.
  - `static URI actuatorBaseFrom(String serverUri)` — unchanged signature; `wss` match now case-insensitive.

- [ ] **Step 1: Write the failing test — the budget bounds a stalled response body**

Add to `ServerMetricsScraperTest.java`. New imports at the top of the file (keep existing ones):

```java
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
```

New test method:

```java
    @Test
    @SuppressWarnings("unchecked")
    void scrapeOmitsStalledMeterAndStaysWithinBudget() {
        // Mechanism, test-owned: one meter's response completes instantly; the other NEVER completes
        // (simulates a stalled body — the exact case HttpRequest.timeout does NOT bound). scrape() must
        // harvest the fast meter and OMIT the stalled one, returning within the injected budget.
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"measurements\":[{\"statistic\":\"MAX\",\"value\":7.0}]}");

        CompletableFuture<HttpResponse<String>> fast = CompletableFuture.completedFuture(ok);
        CompletableFuture<HttpResponse<String>> stalled = new CompletableFuture<>(); // never completes
        when(http.sendAsync(any(HttpRequest.class), any())).thenAnswer(inv -> {
            HttpRequest req = inv.getArgument(0);
            return req.uri().getPath().endsWith("/fast") ? fast : stalled;
        });

        var scraper = new ServerMetricsScraper(
                URI.create("http://h/actuator/"), http, Duration.ofMillis(150));
        Map<String, String> meters = new LinkedHashMap<>();
        meters.put("fast", "MAX");
        meters.put("slow", "MAX");

        long t0 = System.nanoTime();
        Map<String, Double> out = scraper.scrape(meters);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(out).containsExactly(entry("fast", 7.0)); // fast harvested; stalled omitted (positive+negative pair)
        assertThat(elapsedMs).isLessThan(1000);              // positive control: bounded by the 150ms budget, not the 2s per-req timeout
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.paralife.harness.ServerMetricsScraperTest.scrapeOmitsStalledMeterAndStaysWithinBudget"`
Expected: **FAIL / ERROR** — the current `scrape()` calls `http.send()` (blocking), not `http.sendAsync()`, and takes a 3-arg constructor that doesn't exist yet (`Duration` budget). Compile error on the constructor, or (once the constructor exists but the body is unchanged) an NPE because the sync `send()` path is unstubbed. Either confirms the test drives the new concurrent+budgeted path.

- [ ] **Step 3: Rewrite `scrape()` concurrently, add the budget seam, and fix `wss`**

In `ServerMetricsScraper.java`, add imports:

```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

Replace the field/constructor block (currently the two `Duration` constants + `actuatorBase`/`http` fields + the single constructor) with:

```java
    private static final ObjectMapper M = new ObjectMapper();
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(2);   // per-request header/connect cap
    private static final Duration TOTAL_BUDGET = Duration.ofSeconds(2);  // whole-scrape harvest cap — bounds the report path

    private final URI actuatorBase;
    private final HttpClient http;
    private final Duration totalBudget;

    public ServerMetricsScraper(URI actuatorBase, HttpClient http) {
        this(actuatorBase, http, TOTAL_BUDGET);
    }

    /** Test seam: inject a short budget so the whole-scrape bound is unit-testable without a 2s wait. */
    ServerMetricsScraper(URI actuatorBase, HttpClient http, Duration totalBudget) {
        this.actuatorBase = actuatorBase;
        this.http = http;
        this.totalBudget = totalBudget;
    }
```

Fix `actuatorBaseFrom` (case-insensitive scheme — RFC 3986 schemes are case-insensitive and `java.net.URI` does not normalize them):

```java
        String scheme = "wss".equalsIgnoreCase(u.getScheme()) ? "https" : "http";
```

Replace the whole `scrape(...)` method body with the concurrent-harvest version:

```java
    /** name→value for each meter's requested statistic; absent/erroring/timed-out meters omitted. */
    public Map<String, Double> scrape(Map<String, String> meterToStatistic) {
        Map<String, Double> out = new LinkedHashMap<>();
        // Fire every request concurrently, then harvest each within ONE shared deadline. A future's
        // get(timeout) bounds the WHOLE exchange (connect + headers + body); HttpRequest.timeout bounds
        // only header receipt, so a stalled response body could otherwise block the report path past budget.
        Map<String, CompletableFuture<HttpResponse<String>>> inflight = new LinkedHashMap<>();
        for (var e : meterToStatistic.entrySet()) {
            HttpRequest req = HttpRequest.newBuilder(actuatorBase.resolve("metrics/" + e.getKey()))
                    .timeout(REQ_TIMEOUT).GET().build();
            inflight.put(e.getKey(), http.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
        }
        long deadlineNanos = System.nanoTime() + totalBudget.toNanos();
        for (var fe : inflight.entrySet()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            try {
                if (remainingNanos <= 0) {
                    throw new TimeoutException(); // budget spent — omit this and every remaining meter
                }
                HttpResponse<String> res = fe.getValue().get(remainingNanos, TimeUnit.NANOSECONDS);
                if (res.statusCode() == 200) {
                    Double v = parseMetricValue(res.body(), meterToStatistic.get(fe.getKey()));
                    if (v != null) out.put(fe.getKey(), v);
                }
            } catch (InterruptedException ie) {
                // Reporter/shutdown interrupt: restore the flag, cancel in-flight, stop promptly.
                Thread.currentThread().interrupt();
                inflight.values().forEach(f -> f.cancel(true));
                return out;
            } catch (TimeoutException | ExecutionException ex) {
                fe.getValue().cancel(true); // omit; a benchmark never dies (or hangs) on a missing/slow meter
            }
        }
        return out;
    }
```

Also update the class javadoc (currently says "a per-request timeout ... not merely per request"): change the whole-scrape-budget sentence to reflect the concurrent harvest — e.g. "…bounded by an overall ~2s whole-scrape budget: all meters are requested concurrently and harvested within one shared deadline, so a stalled response (even one whose headers arrived) can never stall the caller for `meters × timeout`."

- [ ] **Step 4: Run the new test + the existing scraper tests to verify they pass**

Run: `./gradlew test --tests "com.paralife.harness.ServerMetricsScraperTest"`
Expected: **PASS** (all methods, including the new budget test and the existing parser/URI tests).

- [ ] **Step 5: Add the `wss` case-insensitivity control and run it**

Add to `ServerMetricsScraperTest.derivesActuatorBaseFromWsServerUri` (or as a new method) a positive control for LOW-7:

```java
    @Test
    void derivesHttpsForWssRegardlessOfCase() {   // LOW-7: schemes are case-insensitive
        assertThat(ServerMetricsScraper.actuatorBaseFrom("wss://h/ws/world").getScheme()).isEqualTo("https");
        assertThat(ServerMetricsScraper.actuatorBaseFrom("WSS://h/ws/world").getScheme()).isEqualTo("https");
        assertThat(ServerMetricsScraper.actuatorBaseFrom("ws://h/ws/world").getScheme()).isEqualTo("http");
    }
```

Run: `./gradlew test --tests "com.paralife.harness.ServerMetricsScraperTest.derivesHttpsForWssRegardlessOfCase"`
Expected: **PASS** (the `equalsIgnoreCase` fix makes the uppercase `WSS` row green; without it that row is RED).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/paralife/harness/ServerMetricsScraper.java \
        src/test/java/com/paralife/harness/ServerMetricsScraperTest.java
git commit -m "fix(harness): enforce whole-scrape budget via concurrent sendAsync; case-insensitive wss

Per-request HttpRequest.timeout bounds only header receipt, not a stalled body,
so the ~2s whole-scrape budget was unenforceable. Fire all meter requests
concurrently and harvest each within one shared deadline. Drop the dead
min(remaining, REQ_TIMEOUT) sequential logic. Fix wss scheme match to be
case-insensitive (URI does not normalize schemes)."
```

---

### Task 2: LoadHarness — fail-soft scraper build + null-guarded scrape call

Fixes **MEDIUM-4**: `ServerMetricsScraper.actuatorBaseFrom(serverUri)` runs `URI.create(...)` eagerly at line ~240, **outside** the main `try/finally` (which starts at ~279). A malformed `--server-uri` throws there → propagates out of `runInternal` (wrong exit code, no final report) and leaks the already-built `metricsHttp`. The fix makes the build fail-soft and null-guards the one scrape call site, so a bad actuator URI degrades to "no server metrics" instead of crashing/leaking — matching the scraper's own never-crash-the-run contract.

**Files:**
- Modify: `src/main/java/com/paralife/harness/LoadHarness.java`

**Interfaces:**
- Consumes: `ServerMetricsScraper(URI, HttpClient)`, `ServerMetricsScraper.actuatorBaseFrom(String)`, `ReportSnapshot.withServerMetrics(ReportSnapshot, Map)`, `ReportSnapshot.BENCHMARK_METER_NAMES`.
- Produces: no signature changes. `metricsScraper` may now be `null` (fail-soft); the scrape call site treats null as "empty scrape".

- [ ] **Step 1: Make the scraper build fail-soft**

In `LoadHarness.java`, replace the build block (currently lines ~237–240):

```java
        // Build the scraper AFTER the initial write (which doesn't need it) so an initial-write
        // early-return can't bypass the finally that closes the owned client and leak it.
        metricsHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        metricsScraper = new ServerMetricsScraper(ServerMetricsScraper.actuatorBaseFrom(serverUri), metricsHttp);
```

with:

```java
        // Build the scraper AFTER the initial write (which doesn't need it) so an initial-write
        // early-return can't bypass the finally that closes the owned client and leak it. Fail-soft on a
        // malformed --server-uri: actuatorBaseFrom does URI.create (can throw), and this runs before the
        // main try/finally — an uncaught throw here would crash the run with the wrong exit code and leak
        // the client. Degrade to "no server metrics" instead, consistent with the scraper's own contract.
        metricsHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            metricsScraper = new ServerMetricsScraper(ServerMetricsScraper.actuatorBaseFrom(serverUri), metricsHttp);
        } catch (RuntimeException e) {
            log.warn("Server-metrics scraping disabled — could not derive actuator base from '{}': {}",
                    serverUri, e.getMessage());
            metricsScraper = null;
        }
```

- [ ] **Step 2: Null-guard the single scrape call site**

In `computeCountersSnapshot(...)`, replace the final `return` (currently lines ~458–459):

```java
        return ReportSnapshot.withServerMetrics(counters,
                metricsScraper.scrape(ReportSnapshot.BENCHMARK_METER_NAMES));
```

with:

```java
        Map<String, Double> scraped = (metricsScraper != null)
                ? metricsScraper.scrape(ReportSnapshot.BENCHMARK_METER_NAMES)
                : Map.of();   // fail-soft: no scraper -> withServerMetrics null-fills every category key
        return ReportSnapshot.withServerMetrics(counters, scraped);
```

Ensure `java.util.Map` is imported (it is used elsewhere in the file; add the import only if `compileJava` complains).

- [ ] **Step 3: Verify the downstream contract is already pinned, and compile**

The observable contract — "no scraper ⇒ report still carries the full `server_metrics` key set, values null" — is the `withServerMetrics(base, Map.of())` path, already pinned by `ReportSnapshotTest.absentMeterNormalizesToNullValuedCategoryKey`. This task routes the null-scraper case into that tested path; the lifecycle glue itself (build-order, finally-close) is verified by reading, consistent with how the existing shutdown-hook glue is covered (no unit seam without a heavy `@SpringBootTest`).

Run: `./gradlew compileJava test --tests "com.paralife.harness.ReportSnapshotTest"`
Expected: **PASS** (compile clean; `absentMeterNormalizesToNullValuedCategoryKey` green — the path the null-guard now reuses).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/paralife/harness/LoadHarness.java
git commit -m "fix(harness): fail-soft scraper build + null-guarded scrape call

A malformed --server-uri made actuatorBaseFrom throw before the main
try/finally, crashing the run with the wrong exit code and leaking the owned
HttpClient. Build the scraper fail-soft (bad URI -> no server metrics) and
null-guard the scrape call site, degrading to the already-tested null-fill
path instead of aborting."
```

---

### Task 3: Hygiene — test-client close, `.gitignore` anchor, `run-tiers.sh` note fixes + RED-tested gate

Fixes **MEDIUM-5** (unclosed `HttpClient` in a `forkEvery=0` leak-sensitive suite), **LOW-9** (unanchored `reports/` ignore), the stale "a patch leaves it 0644" comment in `run-tiers.sh`, and **RED-tests the jq gate** (below-the-cut: it shipped green-only). Adds a cumulativity comment (the HIGH-3 methodology note in code form).

**Files:**
- Modify: `src/test/java/com/paralife/harness/ScrapeLiveIntegrationTest.java`
- Modify: `.gitignore`
- Modify: `tools/benchmark/run-tiers.sh`

**Interfaces:** none (test + config + script only).

- [ ] **Step 1: Close the test's owned `HttpClient` (MEDIUM-5)**

In `ScrapeLiveIntegrationTest.java`, wrap the client in try-with-resources (`HttpClient` is `AutoCloseable` in Java 21). Replace the test body:

```java
    @Test
    void scrapePopulatesServerMetricsAgainstLiveServer() {
        try (HttpClient http = HttpClient.newHttpClient()) {
            var scraper = new ServerMetricsScraper(
                    ServerMetricsScraper.actuatorBaseFrom("ws://localhost:" + port + "/ws/world"),
                    http);

            Map<String, Double> result = scraper.scrape(Map.of("paralife.ws.active.sessions", "VALUE"));

            assertThat(result.get("paralife.ws.active.sessions")).isNotNull();
        }
    }
```

- [ ] **Step 2: Anchor the `reports/` ignore (LOW-9)**

In `.gitignore`, change the unanchored pattern (last line of the benchmark block):

```
reports/
```

to root-anchored:

```
/reports/
```

- [ ] **Step 3: Fix the stale mode-note and add the cumulativity comment in `run-tiers.sh`**

The file is committed `0755` (verify: `git ls-files -s tools/benchmark/run-tiers.sh` shows mode `100755`), so the "a patch leaves it 0644" note is stale. Replace the header `NOTE:` line:

```bash
# NOTE: after creating this file, `chmod +x tools/benchmark/run-tiers.sh` (a patch leaves it 0644) OR
# always invoke it as `bash tools/benchmark/run-tiers.sh …`. `set -e` is deliberately OMITTED — the
```

with:

```bash
# NOTE: `set -e` is deliberately OMITTED — the
```

Then add, immediately after the `for TIER in 100 500 1000; do` line, a cumulativity comment (documents the HIGH-3 methodology at the point it matters):

```bash
  # NB: paralife.admission.rejected is a SERVER-LIFETIME counter. This loop drives ONE persistent
  # server, so its rejected COUNT is CUMULATIVE across tiers (tier 1000 includes tier 500's rejections).
  # The committed docs/benchmarks fixtures were captured with a FRESH server per tier (see docs/BENCHMARKS.md
  # "Commands run") to get per-tier-isolated counts; restart the server between tiers to reproduce them.
```

- [ ] **Step 4: RED-test the jq gate (prove it fires), then confirm it passes a good report**

Run the gate against a **degenerate** report and a **healthy** fixture, capturing both exit codes:

```bash
# RED: peak_registered=0 must FAIL the gate (exit non-zero)
echo '{"peak_registered":0,"server_metrics":{"paralife.tick.work.ms":null}}' > /tmp/degen.json
jq -e '(.peak_registered // 0) > 0 and ((.server_metrics // {}) | to_entries | any(.value != null))' /tmp/degen.json; echo "degenerate exit=$?"

# GREEN: a real committed fixture must PASS (exit 0)
jq -e '(.peak_registered // 0) > 0 and ((.server_metrics // {}) | to_entries | any(.value != null))' docs/benchmarks/bench-500.json; echo "healthy exit=$?"
```

Expected: `degenerate exit=1` (gate fires on the loss it guards) and `healthy exit=0`. Record this two-line output in the commit body as the RED-first evidence. (No file change in this step — it proves the gate already in `run-tiers.sh` §Step 3 is not theatre.)

- [ ] **Step 5: Run the slow live test to confirm the close change didn't break the scrape path**

Run: `./gradlew test -PincludeLong=true --tests "com.paralife.harness.ScrapeLiveIntegrationTest"`
Expected: **PASS** (boots a random-port server, scrape returns non-null `paralife.ws.active.sessions`, client closed cleanly).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/paralife/harness/ScrapeLiveIntegrationTest.java .gitignore tools/benchmark/run-tiers.sh
git commit -m "chore(harness): close test HttpClient, anchor reports/ ignore, fix run-tiers notes

Close the ScrapeLiveIntegrationTest client (forkEvery=0 leak-sensitive suite).
Anchor .gitignore reports/ -> /reports/. Drop the stale 0644 mode note and
document admission.rejected cumulativity across tiers. Gate RED-tested:
degenerate report exit=1, healthy fixture exit=0."
```

---

### Task 4: ReportSnapshot — field-preservation guard for the 16-arg positional `withServerMetrics`

Addresses the below-the-cut transposition risk: `withServerMetrics` (and `merge`) hand-copy 16 positional components, several of them adjacent same-typed `Long`s — a transposition would compile and ship green (no test pins each base field flowing through with a distinct value). This adds a green **regression guard** with distinct sentinels, then proves the guard actually fires (RED-first doctrine) by a temporary transposition.

**Files:**
- Modify: `src/test/java/com/paralife/harness/ReportSnapshotTest.java`

**Interfaces:**
- Consumes: `ReportSnapshot` canonical constructor, `ReportSnapshot.withServerMetrics(ReportSnapshot, Map)`.
- Produces: no production change.

- [ ] **Step 1: Add the field-preservation guard test**

Add to `ReportSnapshotTest.java`:

```java
    @Test
    void withServerMetricsPreservesEveryBaseFieldUnchanged() {
        // Guard (not RED-first — no current defect): the 16-arg positional withServerMetrics/merge
        // constructors are transposition-prone. Distinct sentinel per field so any swap of two same-typed
        // components (esp. the adjacent Longs) fails here instead of silently shipping.
        ReportSnapshot base = new ReportSnapshot(
                "hID", "ws://u", 100, "startT", "21",
                50, 40, 2L, 1L, 3L, 1000L, 5000L, 200L, 30L, "duration-reached", Map.of());

        ReportSnapshot out =
                ReportSnapshot.withServerMetrics(base, Map.of("paralife.admission.rejected", 9.0));

        assertThat(out.harnessId()).isEqualTo("hID");
        assertThat(out.serverUri()).isEqualTo("ws://u");
        assertThat(out.targetCount()).isEqualTo(100);
        assertThat(out.startWallTime()).isEqualTo("startT");
        assertThat(out.jvmVersion()).isEqualTo("21");
        assertThat(out.peakRegistered()).isEqualTo(50);
        assertThat(out.currentRegistered()).isEqualTo(40);
        assertThat(out.connectFailuresTotal()).isEqualTo(2L);
        assertThat(out.e408ReconnectRequiredTotal()).isEqualTo(1L);
        assertThat(out.respawnsTotal()).isEqualTo(3L);
        assertThat(out.actionsSentTotal()).isEqualTo(1000L);
        assertThat(out.perceptionsReceivedTotal()).isEqualTo(5000L);
        assertThat(out.syncsReceivedTotal()).isEqualTo(200L);
        assertThat(out.wallTimeSecondsElapsed()).isEqualTo(30L);
        assertThat(out.exitReason()).isEqualTo("duration-reached");
        assertThat(out.serverMetrics()).containsEntry("paralife.admission.rejected", 9.0);
    }
```

- [ ] **Step 2: Run it to confirm it passes on current (correct) code**

Run: `./gradlew test --tests "com.paralife.harness.ReportSnapshotTest.withServerMetricsPreservesEveryBaseFieldUnchanged"`
Expected: **PASS** (no transposition exists today).

- [ ] **Step 3: RED-test the guard — prove it fires on a transposition**

Temporarily swap two adjacent `Long` args in `ReportSnapshot.withServerMetrics` (e.g. swap `base.respawnsTotal()` and `base.actionsSentTotal()` in the constructor call), then rerun the test:

Run: `./gradlew test --tests "com.paralife.harness.ReportSnapshotTest.withServerMetricsPreservesEveryBaseFieldUnchanged"`
Expected: **FAIL** — `respawnsTotal` expected `3` but was `1000` (guard fires on the exact loss it protects).

**Then revert the swap** (restore `withServerMetrics` to its original order) and rerun:
Expected: **PASS**. Confirm `git diff src/main/java/com/paralife/harness/ReportSnapshot.java` is empty before committing.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/paralife/harness/ReportSnapshotTest.java
git commit -m "test(harness): guard withServerMetrics against positional-arg transposition

16-arg positional copy with adjacent same-typed Longs is transposition-prone
and would ship green. Distinct-sentinel guard; RED-tested by a temporary swap
(respawns<->actions failed the guard as expected, then reverted)."
```

---

### Task 5: Doc + evidence merge-back to the recaptured fixtures

Merge-back gate: the fixtures already hold the new numbers and meter name, but the prose still cites the **old** ones. Rewrites the evidence/contract docs to match shipped code and the committed fixtures. Fixes **HIGH-3** in prose (per-tier isolation, no cumulative overstatement), **LOW-8** (eager-vs-lazy meter wording), the dropped drift-misnomer caveat, and the meter rename across docs. **All numbers must be read from the fixtures, not invented.**

**Reference — the committed fixture values** (verify each with `jq` before writing; do not transcribe blind):

| Field (JSON) | bench-100 | bench-500 | bench-1000 |
|---|---|---|---|
| `peak_registered` | 100 | 256 | 256 |
| `current_registered` | 100 | 255 | 208 |
| `connect_failures_total` | 0 | 244 | 744 |
| `respawns_total` | 228 | 746 | 889 |
| `actions_sent_total` | 5931 | 18312 | 22898 |
| `perceptions_received_total` | 6159 | 19059 | 23835 |
| `syncs_received_total` | 328 | 1002 | 1145 |
| `wall_time_seconds_elapsed` | 32 | 40 | 50 |
| `server_metrics."paralife.tick.work.ms"` | 54.899518 | 63.385083 | 60.961482 |
| `server_metrics."paralife.ws.active.sessions"` | 100.0 | 255.0 | 208.0 |
| `server_metrics."paralife.admission.rejected"` | null | 245.0 | 792.0 |
| `server_metrics.backpressure.{stalled.total,rebound,terminal.dropouts}` | 0.0 | 0.0 | 0.0 |
| `server_metrics."paralife.backpressure.stalled.sessions"` | null | null | null |

**Files:**
- Modify: `docs/BENCHMARKS.md`
- Modify: `docs/HARNESS.md`
- Modify: `BACKLOG.md`

**Interfaces:** none (docs).

- [ ] **Step 1: RED — confirm the stale meter name is present (the grep gate fires pre-edit)**

Run: `grep -rn "tick\.drift\.millis" docs/ BACKLOG.md`
Expected: **hits** in `docs/BENCHMARKS.md` and `docs/HARNESS.md` (§6 counter example L318 + meter table L336, §11 L458, §12 L512). This is the loss the Step-5 gate guards; it must be non-empty now.

- [ ] **Step 2: Rewrite `docs/HARNESS.md` (§6, §11, §12)**

- **§6** — in the counter-object JSON example, rename the key and the meter-table row `paralife.tick.drift.millis` → `paralife.tick.work.ms` (both L318 and L336). In the table row, change the "SC category" cell from "Tick drift" to "Tick work-time".
- **§11** — update the whole-scrape-budget sentence to the concurrent-harvest wording (matching Task 1's javadoc): meters are requested concurrently and harvested within one shared deadline, so a stalled response (even post-headers) can't stall the caller for `meters × timeout`. Rename the "tick-drift" mention to "tick work-time".
- **§12** — fix the **eager-vs-lazy** sentence (LOW-8). Replace the current L512–515 block:

```
`paralife.tick.drift.millis` (MAX) and `paralife.ws.active.sessions` (VALUE) populate in any run.
`paralife.admission.rejected` and the `paralife.backpressure.*` stall meters are lazily
registered and event-gated — they read absent/`0` until the server actually rejects or stalls,
so a benign run legitimately leaves those categories empty; that is not a scraper defect.
```

with (matches the fixtures: three backpressure counters read `0.0`, only `stalled.sessions` + `admission.rejected` read `null`):

```
`paralife.tick.work.ms` (MAX) and `paralife.ws.active.sessions` (VALUE) populate in any run.
`paralife.backpressure.stalled.total` / `.rebound` / `.terminal.dropouts` are eagerly-registered
counters — they read `0.0` (not null) from tick 0 until an event increments them. Only
`paralife.backpressure.stalled.sessions` and `paralife.admission.rejected` are lazily/tag-registered,
reading `null` until the first stall/rejection — so a benign run legitimately leaves those two null;
that is not a scraper defect.
```

- [ ] **Step 3: Rewrite `docs/BENCHMARKS.md` — numbers, methodology, caveats**

- **"Commands run"** — replace the single-`bootRun` narrative with the **isolated per-tier restart** protocol actually used (this is the HIGH-3 fix, documented). State: each tier was captured against a **fresh server** (`java -jar build/libs/*-SNAPSHOT.jar`, health-polled, killed between tiers), `--duration 30 --ramp-up rate:50`, so `paralife.admission.rejected` is a **per-tier** count, not cumulative across tiers.
- **Per-tier results table** — replace every cell from the fixtures (table above). Rename the "Tick-drift MAX (ms)" column to **"`paralife.tick.work.ms` MAX (ms)"** with values `54.90 / 63.39 / 60.96` (cite the fixture floats; a "(54.899518)" parenthetical is fine for byte-traceability). Update the rejection column to `null / 245 / 792` and `active.sessions` to `100 / 255 / 208`.
- **Throughput line** — update actions/perceptions/syncs to `5931/6159/328`, `18312/19059/1002`, `22898/23835/1145`; wall-clock `32 / 40 / 50`.
- **Drop the "Work-time-vs-drift caveat" entirely** — the meter is now honestly named `paralife.tick.work.ms`; there is no "drift proxy" misnomer to caveat. Replace it with one terse line: the meter is per-tick work-time; with a 500 ms tick period and MAX values ~55–63 ms, actual scheduling drift ≈ 0.
- **"What actually happened at 500 / 1000"** — rewrite the rejection sentence: counts are now **per-tier isolated** (245 at 2×, 792 at 4×), climbing with the oversubscription ratio, **not** cumulative. Fix the work-time claim: values are **bounded ~55–63 ms across all tiers, with no monotonic trend** (54.90 → 63.39 → **60.96**) — do NOT claim work-time "rose with load". Keep the "admission-capped, not degraded" conclusion and the out-of-band `/actuator/health` caveat.
- **MAX caveat / empty-category honesty** — keep, but rename the meter and align the empty-category wording to the eager-vs-lazy split from §12 Step 2.
- **M4-close boundary statement** — values are unchanged qualitatively (cap 256); just rename any `tick.drift` mention.

- [ ] **Step 4: Reframe the `BACKLOG.md` scrape-budget line + add the two deferrals**

- Change the scrape-budget follow-up from "Total-scrape time budget — ✅ RESOLVED" to reflect the shipped mechanism: "✅ RESOLVED — concurrent `sendAsync` + single shared harvest deadline bounds the whole scrape (connect+headers+body), not just header receipt (Phase-21 review remediation)."
- Add two one-line deferrals under the Phase-21 follow-ups: **`--actuator-uri` override** (context-path / `management.port` deployments; localhost-root only in P21) and **`run-tiers.sh` server-lifecycle ownership** (bake per-tier restart into the script for one-command isolated sweeps; currently the isolated protocol is manual per `docs/BENCHMARKS.md`).

- [ ] **Step 5: Verify — meter rename complete, every cited number traces to a fixture, style intact**

```bash
# GREEN gate: no stale meter name survives anywhere in docs/BACKLOG
grep -rn "tick\.drift\.millis" docs/ BACKLOG.md; echo "stale-name hits exit=$?"   # expect: no output, exit=1

# Cross-check the doc's headline numbers against the fixtures (spot-check a few)
for T in 100 500 1000; do
  jq -r '"bench-'"$T"': rejected=\(.server_metrics."paralife.admission.rejected") work=\(.server_metrics."paralife.tick.work.ms") active=\(.server_metrics."paralife.ws.active.sessions")"' docs/benchmarks/bench-$T.json
done

# STYLE (G6): no GSD review-round residue introduced
grep -rniE 'Round [0-9]+ .*amendment|Codex (HIGH|MED|MEDIUM|LOW)|Authored: Phase' docs/BENCHMARKS.md docs/HARNESS.md; echo "residue exit=$?"  # expect: no output, exit=1
```

Expected: `stale-name hits exit=1` (grep found nothing — the rename is complete), the three `jq` lines match the numbers written into `docs/BENCHMARKS.md`, and `residue exit=1`. The Step-1 RED (hits present) → Step-5 GREEN (no hits) pair proves the rename gate is not vacuous.

- [ ] **Step 6: Commit**

```bash
git add docs/BENCHMARKS.md docs/HARNESS.md BACKLOG.md
git commit -m "docs(bench): merge-back recaptured fixtures — per-tier isolation, tick.work.ms

Rewrite BENCHMARKS.md numbers/methodology to the recaptured fixtures: per-tier
isolated admission.rejected (245/792, not cumulative), honest non-monotonic
work-time (~55-63ms), dropped the drift-misnomer caveat. HARNESS §6/§11/§12:
tick.work.ms rename, concurrent-scrape budget wording, eager-vs-lazy meter fix.
BACKLOG: async budget resolved; actuator-uri + run-tiers lifecycle deferred."
```

- [ ] **Step 7: Update the PR body (out-of-repo — only if a PR is open)**

If PR #17 is open, update its description to replace `paralife.tick.drift.millis` with `paralife.tick.work.ms`, note the meter was reused (not newly published), and update the evidence bullets to the new per-tier-isolated numbers. Use `gh pr edit 17 --body-file <updated>`. If no PR is open, skip.

---

## Final Whole-Branch Review

After all tasks, dispatch the final whole-branch review on the most capable model against `git merge-base main HEAD .. HEAD`. Focus lenses: (1) the concurrent-scrape budget actually bounds a stalled body and doesn't leak async ops on the interrupt/timeout paths; (2) fail-soft `LoadHarness` wiring can't NPE on the shutdown-before-build race; (3) every number in `docs/BENCHMARKS.md` traces to a committed fixture; (4) firewall intact — no default-suite assertion on a benchmark magnitude or live aggregate. Then run the full suite + Spotless from a **normal checkout / CI** (not this worktree).

## Self-Review (author checklist — completed)

1. **Spec coverage:** HIGH-1 (T1), HIGH-2 (already shipped — meter swap done pre-plan), HIGH-3 (fixtures shipped; prose T5 + methodology T3/T5), MED-4 (T2), MED-5 (T3), MED-6 (deferred — Non-Goals + T5 backlog), LOW-7 (T1), LOW-8 (T5 §12), LOW-9 (T3), below-cut min()/dead-code (T1), transposition (T4), RED-first gate (T3 §4, T4 §3, T5 §1/§5), stale mode note (T3). Milestone SSOT edit (CLAUDE.md→ROADMAP) already shipped pre-plan. All covered.
2. **Placeholder scan:** every code/step carries real code or an exact command + expected output; doc numbers pinned to a fixture table with a jq cross-check. No TBD/TODO.
3. **Type consistency:** `ServerMetricsScraper(URI, HttpClient)` / `(URI, HttpClient, Duration)`, `scrape(Map<String,String>)→Map<String,Double>`, `withServerMetrics(ReportSnapshot, Map)`, `BENCHMARK_METER_NAMES` keyed on `paralife.tick.work.ms` — consistent across tasks.
