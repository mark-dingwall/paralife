# Phase 21 — Scale Benchmark Gate & Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax
> for tracking. Design rationale + firewall triage: `docs/superpowers/specs/2026-07-04-phase-21-scale-benchmark-gate-design.md`.

**Goal:** Ship a repeatable command → saved-report benchmark pipeline for 100 / 500 / 1000+ bot runs
covering throughput, tick drift, session stability, rejection counts, and failure modes — closing M4 with
a validated scale envelope, without pinning any performance magnitude in the default test suite.

**Architecture:** Four mechanism slices + one evidence slice. (1) Publish a real `paralife.tick.drift.millis`
`DistributionSummary` from the existing per-tick work-time. (2) A read-only harness scraper folds server
`/actuator/metrics` values into the existing crash-safe JSON/JSONL report. (3) The report schema is extended
to carry every SC-required category. (4) A repeatable tier runner drives 100/500/1000 and names reports
deterministically. (5) Real evidence is captured and the M4↔M5/M6 boundary is written into the canonical doc.

**Tech Stack:** Java 21 (virtual threads), Spring Boot 3.4.4, Micrometer (`SimpleMeterRegistry` +
`/actuator/metrics`), picocli (`LoadHarness`), JUnit 5, Gradle Kotlin DSL. Jetty 12 WS client fleet.

## Global Constraints

- **Firewall (binding).** No default-suite `assertThat` on a benchmark **magnitude** (throughput, tick-drift,
  latency) or any **live-run per-population aggregate** (count / share / rate). Pin the *transformation
  contract* — metric-name constants, extraction over **test-owned canned input**, `count()==N` for N
  test-supplied samples — never a hardcoded magnitude. Any threshold that must gate → `@Tag("slow")`, excluded
  from `./gradlew test`. (Source: `CLAUDE.md` §constitution-clause + label-vs-count corollary.)
- **Metric name is a contract.** The drift summary MUST register under exactly `paralife.tick.drift.millis`
  (consumed verbatim by `EncodeDeflatePerformanceGateTest:163`). Expose it as a named constant; pin tests to
  the constant.
- **Percentiles must be client-side published.** `EncodeDeflatePerformanceGateTest` calls
  `summary.percentile(0.99)` — that returns NaN unless the summary is built with `.publishPercentiles(...)`.
- **EARS mapping.** Each mechanism clause maps to one RED/GREEN assertion pinned to a config accessor /
  name constant. Pair every negative assertion with a positive control. Real RED only (fails for the spec
  reason before GREEN).
- **No production tuning.** Measurement only; balance/perception constants are GUI/M5-gated and untouched.
- **Scope fences.** Do NOT touch `MetabolismIntegrationTest`, `HundredBotIntegrationTest`, or CI-re-enable
  `EncodeDeflatePerformanceGateTest` (all → Phase 22.1). Do NOT add a Prometheus registry. Do NOT automate JFR.
- **PR-per-slice.** One logical slice per PR. Each task ends with an independently testable deliverable and
  the close-out gates below.
- **Close-out gates (every slice/PR):** *Evidence-bound done* — every "passing/done" claim quotes a command's
  output. *Scope-diff line* — one line in the PR: delivered vs this plan's intent. *Merge-back* — fold the
  change into the canonical living doc at merge (targets flagged per task; provisional pending docs editorial —
  see Coupling note in the spec-doc).

---

## Task 1: Publish `paralife.tick.drift.millis` tick-drift distribution

**Why (EARS):** WHEN a tick's work-time is sampled THE SYSTEM SHALL record that sample (ms) into a
`DistributionSummary` registered under `paralife.tick.drift.millis` with client-side percentiles published,
so the in-JVM gate can read a p99, not only the rolling mean.

**Semantics note (labelling — not a firewall matter):** the value recorded is per-tick **work-time**
(`getLastTickWorkMs`), used as a *drift proxy*; it is NOT measured schedule overrun (observed-interval −
`TickConfig.intervalMs()`). The name is a fixed consumer contract (`EncodeDeflatePerformanceGateTest:163`),
so we keep it and make the proxy semantics loud in the merge-back and any evidence table. A true drift tap
is out of scope (deferred to 22.1). **p99 scope:** `publishPercentiles(0.5, 0.95, 0.99)` makes `summary.percentile(0.99)`
non-NaN *in-process* (the in-JVM EncodeDeflate gate). Micrometer does NOT surface client-side percentiles in
the base `/actuator/metrics/{name}` JSON (measurements are COUNT/TOTAL/MAX only) — so the out-of-process
harness report canNOT carry drift **p99**; it carries drift **MAX/COUNT/TOTAL** (see Task 3/5).

**Files:**
- Modify: `src/main/java/com/paralife/admission/AdmissionMetrics.java` — add the summary + record on the
  existing `setLastTickWorkMs(...)` call path (already invoked once per tick by `TickHealthMonitor`).
- Test: `src/test/java/com/paralife/admission/AdmissionMetricsTest.java` (add cases; create if absent).

**Interfaces:**
- Consumes: `MeterRegistry` (already injected into `AdmissionMetrics`); the existing per-tick call
  `AdmissionMetrics.setLastTickWorkMs(long ms)` (invoked by `TickHealthMonitor.onTick`).
- Produces: constant `AdmissionMetrics.METRIC_TICK_DRIFT = "paralife.tick.drift.millis"`; a
  `DistributionSummary` recorded once per `setLastTickWorkMs` call. No signature change to `setLastTickWorkMs`.

- [ ] **Step 1: Write the failing test**

```java
// AdmissionMetricsTest.java — new cases. Uses a test-owned SimpleMeterRegistry (test owns the inputs).
@Test
void recordsEachTickWorkSampleIntoNamedDriftSummary() {
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    AdmissionMetrics metrics = makeMetrics(reg);   // existing AdmissionMetricsTest helper (SimpleMeterRegistry)

    long[] samples = {3, 7, 4, 9, 5};              // test-owned magnitudes — NOT production defaults
    for (long s : samples) metrics.setLastTickWorkMs(s);

    DistributionSummary drift = reg.find(AdmissionMetrics.METRIC_TICK_DRIFT).summary();
    assertThat(drift).as("summary registered under the contract name").isNotNull();
    assertThat(drift.count()).isEqualTo(samples.length);   // transformation contract: one record per sample
    assertThat(drift.max()).isEqualTo(9.0);                // max of test-owned inputs
    assertThat(drift.totalAmount()).isEqualTo(28.0);       // sum of test-owned inputs
}

@Test
void driftSummaryPublishesP99Percentile() {
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    AdmissionMetrics metrics = makeMetrics(reg);
    for (int i = 1; i <= 100; i++) metrics.setLastTickWorkMs(i); // 1..100, test-owned
    DistributionSummary drift = reg.find(AdmissionMetrics.METRIC_TICK_DRIFT).summary();
    // Contract: percentiles are PUBLISHED (non-NaN), so EncodeDeflatePerformanceGateTest's
    // preferred path is live. We assert publication, NOT a magnitude bound.
    assertThat(drift.percentile(0.99)).as("p99 published (non-NaN)").isNotNaN();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.admission.AdmissionMetricsTest'`
Expected: FAIL — `find(METRIC_TICK_DRIFT).summary()` is null (constant/summary not defined yet), or compile
error on the missing constant. This is real RED (spec reason: metric absent).

- [ ] **Step 3: Write minimal implementation**

```java
// AdmissionMetrics.java
public static final String METRIC_TICK_DRIFT = "paralife.tick.drift.millis";

private final DistributionSummary tickDrift; // init in ctor after `registry` is available:
// this.tickDrift = DistributionSummary.builder(METRIC_TICK_DRIFT)
//         .baseUnit("milliseconds")
//         .description("Per-tick work-time distribution (scale-benchmark tick drift)")
//         .publishPercentiles(0.5, 0.95, 0.99)   // client-side percentiles → percentile() non-NaN
//         .register(registry);

// In the existing setLastTickWorkMs(long ms):
public void setLastTickWorkMs(long ms) {
    // ...existing gauge-backing assignment stays...
    tickDrift.record(ms);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.admission.AdmissionMetricsTest'`
Expected: PASS (both cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paralife/admission/AdmissionMetrics.java \
        src/test/java/com/paralife/admission/AdmissionMetricsTest.java
git commit -m "feat(metrics): publish paralife.tick.drift.millis tick-drift distribution"
```

**Merge-back (at PR):** add `paralife.tick.drift.millis` to the metric table in the runtime/admission
contract doc (`docs/RUNTIME.md` or `docs/ADMISSION.md` — provisional, docs editorial may relocate).
**Scope-diff line + evidence-bound done** in the PR (quote the passing `./gradlew test` line).

---

## Task 2: Read-only server-metrics scraper

**Why (EARS):** WHEN the harness assembles a report snapshot THE SYSTEM SHALL, for each configured server
meter name, GET `/actuator/metrics/{name}` and parse the returned JSON into a typed value, so server-side
tick-drift/rejection/latency data can be folded into the report. WHEN a meter is absent or the endpoint
errors THE SYSTEM SHALL omit that field (record it null/absent) and continue — a benchmark run never fails
on a missing meter.

**Files:**
- Create: `src/main/java/com/paralife/harness/ServerMetricsScraper.java` (incl. the pure `actuatorBaseFrom` URI helper)
- Create: `src/test/java/com/paralife/harness/ServerMetricsScraperTest.java`

**Interfaces:**
- Consumes: a base server URI (derived from `--server-uri`, swapping `ws→http` + `/ws/world`→`/actuator`);
  Java 21 `HttpClient`; Jackson (already transitive) for the actuator JSON.
- Produces:
  - `ServerMetricsScraper(URI actuatorBase, HttpClient http)`.
  - **The meter set is heterogeneous** (Counter→`COUNT`, Gauge→`VALUE`, DistributionSummary→`MAX`), so the
    statistic is **per meter**, not one for the whole list. Method
    `Map<String,Double> scrape(Map<String,String> meterToStatistic)` returning name→value.
  - Package-visible static `parseMetricValue(String json, String statistic)` for unit-testing the parse over
    canned input.
  - Package-visible static **`URI actuatorBaseFrom(String serverUri)`** — the pure `ws://h:8080/ws/world`
    → `http://h:8080/actuator/` derivation (its own unit test over test-owned input; see Step 1).
  - Rejection sourcing (**aggregate only in P21** — by-reason explicitly deferred, not silently cut):
    `paralife.admission.rejected` is a **two-tag** counter (`reason`+`source`); the base
    `/actuator/metrics/paralife.admission.rejected` returns the **aggregate sum** over all tags — that is what
    P21 carries. The **by-reason** breakdown (read `availableTags` → GET `?tag=reason:<v>` per value) is
    **deferred to `BACKLOG.md` §Phase-21 follow-ups** — it is extra HTTP round-trips for an observe-only
    emergence *count*, not needed to close M4. The `scrape()` below does flat per-meter GETs only. Likewise
    `paralife.ws.active.sessions` / `paralife.backpressure.stalled.sessions` are multi-bucket gauges tagged by
    attribution `source`+`harness`; the base endpoint returns the **`VALUE` summed across all buckets**, not a
    single scalar — fine for the report (a whole-server figure), but note the aggregation. Later tasks (3)
    consume `scrape(...)`.

**Note on actuator JSON shape** (test fixtures use exactly this):
```json
{ "name": "paralife.tick.drift.millis",
  "measurements": [ {"statistic":"COUNT","value":50.0}, {"statistic":"TOTAL","value":210.0},
                    {"statistic":"MAX","value":11.0} ],
  "availableTags": [] }
```
Client-side percentiles (`publishPercentiles(0.99)`) are **not** exposed in this base-meter JSON — only
COUNT/TOTAL/MAX. Drift **p99 is in-JVM-gate-only**; the harness report carries drift **MAX** (see Task 1
semantics note). Per-tag values require `/actuator/metrics/{name}?tag=k:v`.

- [ ] **Step 1: Write the failing test** (parse over **test-owned canned JSON** — firewall-clean)

```java
@Test
void parsesRequestedStatisticFromCannedActuatorJson() {
    String json = """
        {"name":"paralife.tick.drift.millis",
         "measurements":[{"statistic":"COUNT","value":50.0},
                         {"statistic":"MAX","value":11.0}]}""";
    // test OWNS this input → asserting extracted values is parser-mechanism, not a live-aggregate assert
    assertThat(ServerMetricsScraper.parseMetricValue(json, "MAX")).isEqualTo(11.0);
    assertThat(ServerMetricsScraper.parseMetricValue(json, "COUNT")).isEqualTo(50.0);
}

@Test
void returnsNullForAbsentStatisticOrMalformedJson() {   // positive/negative pair
    String json = """
        {"name":"x","measurements":[{"statistic":"COUNT","value":1.0}]}""";
    assertThat(ServerMetricsScraper.parseMetricValue(json, "MAX")).isNull();      // absent stat
    assertThat(ServerMetricsScraper.parseMetricValue("not json", "COUNT")).isNull(); // malformed
    // present statistic but missing/non-numeric value → null, never a false 0.0
    assertThat(ServerMetricsScraper.parseMetricValue(
        "{\"measurements\":[{\"statistic\":\"MAX\"}]}", "MAX")).isNull();          // no value node
    assertThat(ServerMetricsScraper.parseMetricValue(
        "{\"measurements\":[{\"statistic\":\"MAX\",\"value\":\"NaN\"}]}", "MAX")).isNull(); // non-numeric
}

@Test
void derivesActuatorBaseFromWsServerUri() {   // pure URI mechanism — test owns the input
    // ws→http, /ws/world→/actuator/. This is the ONE positive control that the retrieval
    // path targets the right endpoint; the fail-soft scrape() otherwise hides a wrong base.
    assertThat(ServerMetricsScraper.actuatorBaseFrom("ws://h:8080/ws/world"))
            .isEqualTo(URI.create("http://h:8080/actuator/"));
    assertThat(ServerMetricsScraper.actuatorBaseFrom("wss://h:8443/ws/world"))
            .isEqualTo(URI.create("https://h:8443/actuator/"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.harness.ServerMetricsScraperTest'`
Expected: FAIL — `ServerMetricsScraper` / `parseMetricValue` undefined. Real RED.

- [ ] **Step 3: Write minimal implementation**

```java
// ServerMetricsScraper.java — read-only; no state mutation; HttpClient injected for testability.
public final class ServerMetricsScraper {
    private static final ObjectMapper M = new ObjectMapper();
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(2); // bounded — never hang the report path
    private final URI actuatorBase; private final HttpClient http;
    public ServerMetricsScraper(URI actuatorBase, HttpClient http) { this.actuatorBase = actuatorBase; this.http = http; }

    /** ws://h/ws/world → http://h/actuator/ (wss→https). Pure; unit-tested over test-owned input. */
    static URI actuatorBaseFrom(String serverUri) {
        URI u = URI.create(serverUri);
        String scheme = "wss".equals(u.getScheme()) ? "https" : "http";
        int port = u.getPort();
        return URI.create(scheme + "://" + u.getHost() + (port < 0 ? "" : ":" + port) + "/actuator/");
    }

    /** name→value for each meter's requested statistic; absent/erroring/timed-out meters omitted. */
    public Map<String,Double> scrape(Map<String,String> meterToStatistic) {
        Map<String,Double> out = new LinkedHashMap<>();
        for (var e : meterToStatistic.entrySet()) {
            try {
                HttpRequest req = HttpRequest.newBuilder(actuatorBase.resolve("metrics/" + e.getKey()))
                        .timeout(REQ_TIMEOUT).GET().build();  // bounded: an overloaded server omits, never stalls
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    Double v = parseMetricValue(res.body(), e.getValue());
                    if (v != null) out.put(e.getKey(), v);
                }
            } catch (Exception ignored) { /* omit; a benchmark never dies (or hangs) on a missing meter */ }
        }
        return out;
    }

    static Double parseMetricValue(String json, String statistic) {
        try {
            JsonNode ms = M.readTree(json).path("measurements");
            for (JsonNode m : ms)
                if (statistic.equals(m.path("statistic").asText())) {
                    JsonNode v = m.path("value");
                    return v.isNumber() ? v.asDouble() : null;  // missing/non-numeric → null, NOT a false 0.0
                }
            return null;
        } catch (Exception e) { return null; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.harness.ServerMetricsScraperTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paralife/harness/ServerMetricsScraper.java \
        src/test/java/com/paralife/harness/ServerMetricsScraperTest.java
git commit -m "feat(harness): read-only /actuator/metrics scraper for benchmark reports"
```

**Merge-back:** document the scraped meter set + endpoint dependency in the harness contract doc
(`docs/HARNESS.md`). Scope-diff + evidence-bound done in PR.

---

## Task 3: Fold server metrics into the report schema (completeness)

**Why (EARS):** WHEN the harness writes a benchmark report THE SYSTEM SHALL include a `server_metrics`
object carrying **one key per `BENCHMARK_METER_NAMES` entry** — the SC-required categories: tick drift
(`paralife.tick.drift.millis` MAX), session stability (`paralife.ws.active.sessions` + the enumerated
`paralife.backpressure.stalled.sessions`/`.stalled.total`/`.rebound`/`.terminal.dropouts` meters — **exact
names, no wildcard**), rejection counts (`paralife.admission.rejected` **aggregate COUNT**; by-reason deferred
to BACKLOG per Task 2) — alongside the existing client-counter throughput and `exit_reason` failure-mode fields. WHEN the scraper returns no value
for a configured meter THE SYSTEM SHALL still emit that meter's key with a **JSON null** value (schema stable
— the category is always visible, absent only in value). This makes "carries every category" a checkable
property, not a claim satisfied by an empty map.

**Reconciliation (was a contradiction):** the scraper's `scrape(...)` returns a *partial* map (omits
unscraped meters — Task 2); `withServerMetrics` **normalizes** that partial map to the full
`BENCHMARK_METER_NAMES` key set, inserting `null` for any missing meter. So the omit-in-scraper and
null-in-report behaviours are consistent, and the completeness test below can assert the full category
key set is present.

**Files:**
- Modify: `src/main/java/com/paralife/harness/ReportSnapshot.java` — add a `server_metrics` map field to the
  snake_case record; extend `merge()`/factories to carry it.
- Modify: `src/main/java/com/paralife/harness/LoadHarness.java` — construct the scraper from `--server-uri`,
  call `scrape(...)` inside the periodic + final report assembly, pass into the snapshot.
- Test: `src/test/java/com/paralife/harness/ReportSnapshotTest.java` (create if absent) — schema shape.
  **`ReportSnapshotTest` (pure, test-owned inputs) is the SOLE default-suite home for the schema check.**
  P21 adds **no** assertion to `LoadHarnessIntegrationTest`: that class is `@SpringBootTest RANDOM_PORT`,
  **untagged**, and **already** carries pre-existing live-integration asserts (`peak_registered>=1` at
  `LoadHarnessIntegrationTest:91`, sync-count`>=1` at `:219`, `connectFailures==3` at `:240`) that are
  **out of P21 scope and untouched** — their firewall status is a legacy matter, not this phase's. Keeping the
  new schema assertion in the pure unit test sidesteps the live-aggregate question entirely; do NOT extend the
  integration class here. Any assertion that a *real* scrape produced data belongs in `@Tag("slow")`, never
  `./gradlew test` (see Task 4 for the live wiring check).

**Interfaces:**
- Consumes: `ServerMetricsScraper.scrape(Map<String,String>)` (Task 2 — one statistic per meter); existing
  `ReportSnapshot.header()` / `counters()` / `merge()` factories.
- Produces: `ReportSnapshot` with a `Map<String,Double> serverMetrics()` component (Java field `serverMetrics`
  → `server_metrics` via the existing mapper-level `SNAKE_CASE` strategy — **no `@JsonProperty`**, per the
  record's documented convention); a constant **`ReportSnapshot.BENCHMARK_METER_NAMES`** (hosted on the data
  record, **not** on `LoadHarness` — keeps the arrow data→data, avoiding a record→CLI reference; `LoadHarness`
  references it) — a **`Map<String,String>` meter→statistic** enumerating **exact** meter names (there is no
  wildcard `/actuator/metrics` endpoint, so `paralife.backpressure.*` must be spelled out):
  `paralife.tick.drift.millis`→`MAX`, `paralife.ws.active.sessions`→`VALUE`,
  `paralife.backpressure.stalled.sessions`→`VALUE`, `paralife.backpressure.stalled.total`→`COUNT`,
  `paralife.backpressure.rebound`→`COUNT`, `paralife.backpressure.terminal.dropouts`→`COUNT`,
  `paralife.admission.rejected`→`COUNT` (aggregate; by-reason deferred to BACKLOG per Task 2). `withServerMetrics(base,
  scraped)` normalizes `scraped` to this full key set (null-fill missing).

- [ ] **Step 1: Write the failing test**

```java
// ReportSnapshotTest.java — assert schema shape over test-owned values (NOT a live run).
// ReportSnapshot has NO toJson(): serialize the way ReportWriter does (SNAKE_CASE mapper), or assert on
// the record accessor directly. No @JsonProperty (the record forbids per-field annotations by convention).
private static final ObjectMapper MAPPER =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

// baseSnap() = new local test helper (ReportSnapshotTest is create-if-absent): a valid header+counters
// merge to attach server_metrics onto, e.g.
//   private static ReportSnapshot baseSnap() {
//       return ReportSnapshot.merge(ReportSnapshot.header(...), ReportSnapshot.counters(...));
//   }
// Fill header(...)/counters(...) with the exact arg lists from ReportSnapshot's factory signatures
// (read the record); values are test-owned and arbitrary — baseSnap only needs to be a valid merged snapshot.

@Test
void snapshotCarriesServerMetricsBlockSerializedSnakeCase() throws Exception {
    Map<String,Double> server = new LinkedHashMap<>();
    server.put("paralife.tick.drift.millis", 11.0);      // test-owned
    server.put("paralife.admission.rejected", 3.0);      // test-owned
    ReportSnapshot snap = ReportSnapshot.withServerMetrics(/* existing header+counters */ baseSnap(), server);

    String json = MAPPER.writeValueAsString(snap);        // mirrors ReportWriter's mapper
    assertThat(json).contains("\"server_metrics\"");
    assertThat(snap.serverMetrics()).containsEntry("paralife.tick.drift.millis", 11.0);
}

@Test
void absentMeterNormalizesToNullValuedCategoryKey() throws Exception {
    // scraper omitted every meter → withServerMetrics normalizes to the full BENCHMARK_METER_NAMES key set,
    // value null. Completeness is thus enforceable: every category key is present even with zero live data.
    ReportSnapshot snap = ReportSnapshot.withServerMetrics(baseSnap(), Map.of());
    assertThat(snap.serverMetrics().keySet())
            .containsExactlyInAnyOrderElementsOf(ReportSnapshot.BENCHMARK_METER_NAMES.keySet());
    assertThat(snap.serverMetrics().get("paralife.tick.drift.millis")).isNull(); // absent → null, not missing
    // JSON-level: the null-valued key is VISIBLE (not stripped) — pins Jackson content-inclusion behaviour
    assertThat(MAPPER.writeValueAsString(snap)).contains("\"paralife.tick.drift.millis\":null");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.paralife.harness.ReportSnapshotTest'`
Expected: FAIL — `withServerMetrics` / `serverMetrics()` / the `server_metrics` key don't exist. Real RED.

- [ ] **Step 3: Write minimal implementation**

Add the `Map<String,Double> serverMetrics` component to the `ReportSnapshot` record — **no `@JsonProperty`**
(the record's convention is a single mapper-level `SNAKE_CASE` strategy; `serverMetrics` auto-maps to
`server_metrics`). The component is a new field, so **`header()`, `counters()`, AND `merge()` each gain it**
(they enumerate every component by hand — `merge()`'s `new ReportSnapshot(…)` spans `ReportSnapshot.java:76-89`)
defaulting to an **empty map, never `null`** (with `@JsonInclude(NON_NULL)` an empty map still serializes as
`"server_metrics":{}`; a `null` would be omitted and break the schema-key test). Add `withServerMetrics(base,
scraped)` which returns a copy of `base` whose `serverMetrics` is `scraped` **normalized to the full
`BENCHMARK_METER_NAMES` key set** (null-fill any meter the scraper omitted). **Adding a record component breaks
every positional caller — update them in the same commit** (the compiler will list them; known sites:
`ReportWriterTest.java` `counters(...)` calls at `:34,71,75,94,158`, and `LoadHarness.java` `header(...)` `:213`
+ `counters(...)` `:428`). **Null-map-value note (settle the Jackson question):** class-level `@JsonInclude(NON_NULL)`
suppresses a null *property*, not null *map values* (content inclusion defaults to `USE_DEFAULTS` → `ALWAYS`),
so a null-filled key serializes as `"meter":null`. Pin it: the Step-1 test asserts the serialized JSON contains
an explicit `"paralife.tick.drift.millis":null` for the all-absent case, not just the record accessor's keySet —
so a Jackson upgrade that changed this would go RED.

In `LoadHarness`: build `new ServerMetricsScraper(ServerMetricsScraper.actuatorBaseFrom(serverUri),
HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build())` once (bounded connect so an
overloaded server can't stall the scrape), and in the report-assembly path call
`scrape(ReportSnapshot.BENCHMARK_METER_NAMES)` (per-meter statistics live in the map), threading the result through
`withServerMetrics`. **Shutdown-hook safety:** the final report is written from LoadHarness's shutdown hook
(crash-safe, must complete before JVM halt); the bounded request+connect timeouts (Task 2) ensure a scrape
under 1000-bot overload omits-and-continues rather than hanging that critical write.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.paralife.harness.ReportSnapshotTest' --tests 'com.paralife.harness.LoadHarnessIntegrationTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paralife/harness/ReportSnapshot.java \
        src/main/java/com/paralife/harness/LoadHarness.java \
        src/test/java/com/paralife/harness/ReportSnapshotTest.java
git commit -m "feat(harness): fold server /actuator metrics into benchmark report schema"
```

**Merge-back:** update the report-schema section of `docs/HARNESS.md` with the `server_metrics` block +
the meter→SC-category mapping. Scope-diff + evidence-bound done in PR.

---

## Task 4: Repeatable tier runner (100 / 500 / 1000)

**Why:** SC requires "repeatable commands and saved reports" across the three tiers. This is operational
glue, not simulation logic — keep it minimal and free of new tested Java surface. **Form (D3, resolved):** a
shell runner `tools/benchmark/run-tiers.sh` — no Gradle task, no new tested surface.

**Files:**
- Create: `tools/benchmark/run-tiers.sh` — loop over `100 500 1000`, invoke the built `load-harness` jar (or
  `./gradlew runHarness`) per tier with pinned `--duration`/`--ramp-up`/`--report-interval`, writing
  `reports/run-<sweep-ts>/bench-<tier>.json` (fresh per-sweep dir — no stale-glob collisions). Fail-soft per
  tier (one tier's failure doesn't abort the sweep; it's recorded). `chmod +x` it (or invoke via `bash`).
- Doc: recipe in `docs/HARNESS.md`.

**Interfaces:**
- Consumes: `loadHarnessJar` output / `runHarness` task; `--report-out` naming.
- Produces: three deterministically-named report files under `reports/run-<sweep-ts>/` per sweep run.

- [ ] **Step 1: Write the runner** (no unit test — it's a thin wrapper; verified by execution)

```bash
#!/usr/bin/env bash
# tools/benchmark/run-tiers.sh — repeatable scale sweep. "Repeatable" = re-runnable command +
# saved report per tier; NOT bit-identical numbers (live-WS timing is unseeded — see spec Assumptions).
# NOTE: after creating this file, `chmod +x tools/benchmark/run-tiers.sh` (a patch leaves it 0644) OR
# always invoke it as `bash tools/benchmark/run-tiers.sh …`. `set -e` is deliberately OMITTED — the
# per-tier `|| echo FAILED` fail-soft recovery needs a non-fatal error path.
set -uo pipefail
SERVER_URI="${1:?usage: run-tiers.sh <ws-uri> [duration-seconds]}"
DURATION="${2:-120}"
# Fresh per-sweep dir so the verify gate can't match a STALE report from a prior sweep (D1/evidence-bound).
RUN="reports/run-$(date +%s)"; mkdir -p "$RUN"
echo ">>> sweep dir: $RUN"
for TIER in 100 500 1000; do
  OUT_FILE="${RUN}/bench-${TIER}.json"
  echo ">>> tier=${TIER} report=${OUT_FILE}"
  java -jar build/libs/*-load-harness.jar \
      --server-uri "$SERVER_URI" --count "$TIER" --duration "$DURATION" \
      --ramp-up rate:50 --report-out "$OUT_FILE" \
    || echo ">>> tier=${TIER} FAILED (recorded, continuing)"
done
echo ">>> sweep complete: $RUN"
```

- [ ] **Step 2: Verify (execution, not assertion)**

Run against a locally started server: `./gradlew bootRun &` then
`./gradlew loadHarnessJar && bash tools/benchmark/run-tiers.sh ws://localhost:8080/ws/world 30`
Expected: three report files appear in **this sweep's** `reports/run-<ts>/` dir; each is valid JSON whose
`server_metrics` block carries real scraped values. **Checkable pass gate — gate EACH report produced THIS run**
(glob the fresh sweep dir, never a stale `reports/bench-*` from a prior sweep):
```bash
RUN=$(ls -td reports/run-*/ | head -1)          # the sweep just produced
for f in "$RUN"bench-*.json; do
  echo "checking $f"; jq -e '.server_metrics | to_entries | map(select(.value != null)) | length > 0' "$f"
done
```
**Which categories actually populate (Micrometer reality — do NOT read the gate as proving all five SC
categories):** `paralife.tick.drift.millis` (MAX) and `paralife.ws.active.sessions` (VALUE) populate in any
run. But `paralife.admission.rejected` and the `paralife.backpressure.*` stall meters are **lazily registered
and event-gated** — they read `0`/absent until the server actually rejects or stalls, so in a benign 100/500
run the *session-stability* and *rejection* categories are legitimately empty (present-as-null, per Task 3).
The `jq length>0` gate above therefore passes on drift+active-sessions **alone** — it does **not** prove stability/
rejection populated. To exercise those categories, a tier must genuinely drive backpressure (e.g. the 1000 tier
under an admission cap); otherwise Task 5 must record them as *structurally-present-but-empty in this run*, not
claim them demonstrated. (D1: if the 1000 tier can't be sustained, record its failure mode rather than fabricating
a green.)

This live end-to-end retrieval path (URI derivation + HTTP scrape actually returning data) is also the
place for a **`@Tag("slow")` integration check** — a new
`src/test/java/com/paralife/harness/ScrapeLiveIntegrationTest.java`, method
`scrapePopulatesServerMetricsAgainstLiveServer()`, `@Tag("slow")` — asserting `report.serverMetrics()` contains
a non-null value for a known-live meter (e.g. `paralife.ws.active.sessions`) against a `@SpringBootTest`/`bootRun`
server. Key/real-value presence is wiring mechanism, firewall-legal, and gives the scraper spine a falsifiable
positive control **outside** the default suite (excluded from `./gradlew test`; runs under `-PincludeLong=true`).
**Shape (so it's not hand-waved):** `@SpringBootTest(webEnvironment = RANDOM_PORT) @Tag("slow")`; inject the
random port; build the scraper via `ServerMetricsScraper.actuatorBaseFrom("ws://localhost:"+port+"/ws/world")`;
`scrape(Map.of("paralife.ws.active.sessions","VALUE"))` (that gauge is registered eagerly, so it returns even
with **zero** connected bots — no bot fleet needed); assert the returned map has a **non-null** entry for that
key. Mirrors the existing integration classes' `@SpringBootTest RANDOM_PORT` setup. Keep it this minimal — a
one-meter liveness probe, not a full-fleet scenario.
(P21 adds nothing to `LoadHarnessIntegrationTest`; the pure `ReportSnapshotTest` is the only default-suite schema check — Task 3.)

- [ ] **Step 3: Commit**

```bash
git add tools/benchmark/run-tiers.sh
git commit -m "chore(bench): repeatable 100/500/1000 tier sweep runner"
```

**Merge-back:** tier-sweep recipe into `docs/HARNESS.md`. Scope-diff in PR.

---

## Task 5: Capture evidence + M4-close boundary doc

**Why:** SC requires saved reports covering the five categories and a clear M4↔M5/M6 boundary statement.
This task captures **real** artifacts and writes the evidence doc. No production code.

**Files (D4, resolved):**
- Create: **`docs/BENCHMARKS.md`** — the benchmark-evidence doc. (Coordinate with the docs editorial pass per
  the Coupling note; if `docs/` is mid-restructure, the doc's final home may shift — keep the ROADMAP pointer
  indirect.)
- Add: a **curated** evidence subset (one report per tier) under `docs/benchmarks/` with fixed names the doc
  cites — **not** the whole runtime `reports/` dir.
- Modify: `.gitignore` — add `reports/` (raw, timestamped, non-reproducible per B3 — never `git add reports/`).

**Interfaces:** consumes the Task 4 runner output; produces a doc citing each tier's report file.

- [ ] **Step 1: Capture** — run the tier sweep, save the reports.
- [ ] **Step 2: Write the evidence doc** — per tier: command, report path, and the observed
  throughput / tick-drift **MAX** (the harness report carries MAX/COUNT/TOTAL, not p99 — see Task 1
  semantics note; p99 is the in-JVM gate's, out of the report's reach) / session-stability /
  rejection (**aggregate COUNT**; by-reason deferred to BACKLOG) / failure-mode figures **as reported
  observations** (a table of measured values — explicitly *reports, not asserted thresholds*). **Empty-category
  honesty (per Task 4):** session-stability + rejection meters read `0`/null unless a tier actually drove
  backpressure — where a run didn't induce stalls/rejections, record those categories as
  *structurally-present-but-empty in this run*, never as demonstrated. **Variance
  caveat (one line per tier):** each figure is a *single unseeded sample* — live-WS action timing is
  unseeded (`BACKLOG.md` B3), so run-to-run spread is expected; these are not reproducible magnitudes.
  (Optionally capture N≥3 and report a median/range.) **MAX caveat (one line):** the reported tick-drift/frame
  `MAX` is `SimpleMeterRegistry`'s decaying `TimeWindowMax` (≈1-min step), not the run-global peak — for a run
  longer than the step it can drop early spikes; scrape promptly at run-end or note the window. Then the M4
  close statement (below).
- [ ] **Step 3: Write the M4-close boundary statement (concrete acceptance shape — not free prose).** It MUST
  name: (a) **which tiers have real captured evidence** = the validated scale envelope (e.g. "100 & 500
  validated; 1000 per Q1"); (b) the explicit deferrals with their homes — **tuning campaign → BACKLOG B4**
  (gated on this phase + a Core-Value guard), **live visualiser → M5**, and any residual perf work → 22.1.
  Verify: the doc cites real files that exist (`ls`), every figure traces to a saved report (evidence-bound
  done — no hand-typed numbers without a backing report), and the boundary statement contains both (a) and (b).
- [ ] **Step 4: Commit**

```bash
# Commit the doc + CURATED fixtures + the reports/ ignore rule (D4). Runtime reports/ stays untracked.
git add docs/BENCHMARKS.md docs/benchmarks/ .gitignore   # curated per-tier fixtures, not raw reports/
git commit -m "docs(bench): M4 scale-envelope evidence + M4/M5/M6 boundary"
```

**Merge-back:** this task *is* the merge-back for the evidence; ensure `ROADMAP.md` Phase 21 entry is
updated to done with a pointer. Scope-diff in PR.

---

## Global self-review (run after drafting, per writing-plans)

- **Spec coverage:** SC1 (repeatable 100/500/1000 + saved reports) → T4+T5; SC2 (drift/stability/throughput/
  rejection/failure-mode coverage) → T1+T3+T5; SC3 (validated envelope beyond 100) → T5; SC4 (M4↔M5/M6
  boundary) → T5; SC5 (`EncodeDeflatePerformanceGateTest` **and** `MetabolismIntegrationTest` re-enable) →
  **EncodeDeflate unblocked by T1; both re-enables deferred to 22.1 per D2** (flagged, not silently dropped).
- **22.1 handoff trap (bank for the downstream phase):** `EncodeDeflatePerformanceGateTest` stays out of
  `./gradlew test` today via `@Disabled`, **not** its `@Tag("performance")` — `build.gradle.kts` excludes only
  `"slow"`, not `"performance"`. When 22.1 removes `@Disabled` it MUST simultaneously retag the test `@Tag("slow")`
  (or add a `performance` exclude to the build), or its `p99 < budget` magnitude assertion re-enters the default
  gate — a firewall violation. P21 does not touch the test; it only publishes the metric.
- **Firewall re-scan:** grep the plan for any default-suite `assertThat` on a magnitude or live aggregate →
  none (all asserted values are test-owned inputs or `count()==N` / non-NaN publication contracts). **Multi-review
  hardening (2026-07-04):** P21 adds its schema check to the **pure `ReportSnapshotTest` only**, NOT to the
  untagged `LoadHarnessIntegrationTest` — that class already carries pre-existing live-integration asserts
  (`peak_registered>=1` `:91`, sync-count`>=1` `:219`, `connectFailures==3` `:240`), a legacy matter left
  untouched by this phase (Task 3). So P21 introduces zero new default-suite live-aggregate assertions.
- **Type consistency:** `METRIC_TICK_DRIFT`, `parseMetricValue`, `actuatorBaseFrom`,
  `scrape(Map<String,String>)`, `withServerMetrics`, `serverMetrics()`, and `ReportSnapshot.BENCHMARK_METER_NAMES`
  (a `Map<String,String>` meter→statistic, hosted on the data record) used consistently across tasks — one
  statistic per meter, since the meter set spans Counter/Gauge/DistributionSummary; exact meter names only
  (no `/actuator/metrics` wildcard exists).

## Resolved decisions (user-confirmed 2026-07-04 — see spec-doc §Resolved decisions)

- **D1** 1000-bot: *attempt* real capture; on failure, record the mode + document not-sustained (never fake green).
- **D2** re-enable boundary: P21 ships the metric only, edits neither test; **both** EncodeDeflate + Metabolism
  re-enables → **22.1** (+ the `@Disabled`-not-tag firewall trap banked for 22.1).
- **D3** tier-runner: shell `tools/benchmark/run-tiers.sh` (no Gradle task).
- **D4** evidence: `docs/BENCHMARKS.md` + curated `docs/benchmarks/` fixtures; `reports/` gitignored.

No open questions remain — the plan is executable end-to-end via `/executing-plans` or `/subagent-driven-development`.
