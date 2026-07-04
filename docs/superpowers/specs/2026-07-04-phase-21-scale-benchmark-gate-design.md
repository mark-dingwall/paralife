# Phase 21 — Scale Benchmark Gate & Reports (spec-doc)

> Spec-doc per `CLAUDE.md` skeleton. Mechanism-vs-emergence triage is the crux of this phase; the
> Readiness line forces it up front. This doc is the **design**; the executable plan lives at
> `docs/superpowers/plans/2026-07-04-phase-21-scale-benchmark-gate.md`.

**Requirement:** SCALE-10 · **Milestone:** M4 close · **Plans before this:** 0.

---

## Why

M4 (Scale Engineering) built the scale path — admission/backpressure (P17), external load harness
(P18), high-density placement + golden-trace equivalence (P19), runtime tuning (P20) — but never
produced **repeatable, saved benchmark evidence** at 100 / 500 / 1000+ bots. The original baseline is
100 bots. We cannot claim a validated scale envelope, nor cleanly draw the M4↔M5/M6 boundary, without
a reproducible command → report pipeline that captures throughput, tick drift, session stability,
rejection counts, and failure modes. The one perf gate that would exercise this
(`EncodeDeflatePerformanceGateTest`) is `@Disabled` and its *preferred* assertion reads a tick-drift
metric that **does not exist yet** (`paralife.tick.drift.millis`) — so it silently falls back to a
survival proxy. This phase closes that gap.

## What changes / Impact

Behaviour delta (four mechanism slices + one evidence/doc slice):

1. **Publish the missing tick-drift distribution** — a `paralife.tick.drift.millis` `DistributionSummary`
   (with client-side percentiles) fed one sample per tick from the existing per-tick work-time. Unblocks
   the disabled gate's preferred path: `summary.percentile(0.99)` is non-NaN **in-process** (the in-JVM
   EncodeDeflate gate). **Two caveats made loud:** (a) the recorded value is per-tick *work-time* used as a
   *drift proxy*, not measured schedule overrun (the name is a fixed consumer contract — a true drift tap is
   22.1); (b) Micrometer does **not** surface client-side percentiles in the base `/actuator/metrics` JSON,
   so the out-of-process harness report carries drift **MAX/COUNT/TOTAL**, not p99 (p99 stays in-JVM).
2. **Server-metric scrape → report ingestion** — the load harness gains a read-only scraper that pulls a
   fixed set of server meters from `/actuator/metrics/{name}` (JSON) at report time and folds them into
   the report alongside the existing client-side counters. Today the report is client-counters-only.
3. **Report schema completeness** — extend `ReportSnapshot` with a `server_metrics` block carrying **one key
   per configured category** (null-valued when a meter isn't scraped, so completeness is a *checkable* schema
   property, not a claim an empty map can satisfy): throughput, tick drift (MAX), session stability, rejection
   counts (by reason where tag-scraped, else aggregate — see harness scraper note), and failure modes
   (exit-reason / error taxonomy).
4. **Repeatable tier runner** — a single documented, re-runnable command path (shell `tools/benchmark/run-tiers.sh`,
   D3) that exercises 100 / 500 / 1000 bots and writes deterministically-named reports.
5. **Benchmark evidence + M4-close doc** — capture real report artifacts per tier and write the
   scale-envelope evidence plus the M4↔M5/M6 boundary statement into the canonical doc.

**File list (touched):**
- `src/main/java/com/paralife/admission/AdmissionMetrics.java` — register + record the drift summary (slice 1).
- `src/main/java/com/paralife/harness/` — new `ServerMetricsScraper.java`; `ReportSnapshot.java`,
  `LoadHarness.java` wiring (slices 2–3).
- `tools/benchmark/run-tiers.sh` — tier runner (slice 4, D3); `.gitignore` gains `reports/` (D4).
- Canonical doc merge-back: `docs/HARNESS.md` (report schema + tier recipes) and the metric table in
  `docs/RUNTIME.md`/`docs/ADMISSION.md`; new `docs/BENCHMARKS.md` evidence doc + curated `docs/benchmarks/`
  fixtures (D4). **See Coupling note.**
- Tests: `AdmissionMetricsTest`, new `ServerMetricsScraperTest`, `ReportSnapshotTest`/`LoadHarnessIntegrationTest`.

## Assumptions / Resolved decisions

**Assumptions (stated, not silently chosen):**
- "Repeatable" means *re-runnable command producing a fresh saved report* — **not** bit-identical
  numbers. Live-WS action-delivery timing is an unseeded input (per `BACKLOG.md` B3); tier runs are
  **not** golden-trace-reproducible and must not be asserted as such. The report is the artifact.
- `/actuator/metrics` JSON (already exposed: `health,info,metrics`) is sufficient for the scraper; no
  Prometheus registry is added (YAGNI) unless a reviewer proves the JSON path can't carry a needed meter.
- The drift sample source is the existing per-tick work-time (`TickEngine.getLastTickWorkMs()`, already
  mirrored into `AdmissionMetrics.setLastTickWorkMs`) — no new tick-loop instrumentation.
- No balance/perception tuning happens here (GUI/M5-gated) — this phase *measures*, never *tunes*.

**Resolved decisions (user-confirmed 2026-07-04 after the review loop):**
- **D1 — 1000-bot:** *attempt* a real 1000-bot capture; if the box can't sustain it, record the failure mode
  (evidence-bound) and document 1000 as not-sustained — never fabricate a green. (T5 already carries this.)
- **D2 — re-enable boundary:** P21 ships the drift metric **only** and touches **neither** test file;
  **both** `EncodeDeflatePerformanceGateTest` and `MetabolismIntegrationTest` re-enable + revalidation go to
  **Phase 22.1** (ROADMAP/BACKLOG authority). P21 will not meet that frozen-SC bullet by design — the handoff
  covers both. **Tag trap banked for 22.1:** EncodeDeflate is kept out of `./gradlew test` by `@Disabled`, **not**
  its `@Tag("performance")` (the build excludes only `"slow"`); when 22.1 removes `@Disabled` it must retag
  `@Tag("slow")` or add a `performance` exclude, else its `p99 < budget` magnitude assert re-enters the default
  gate — a firewall breach.
- **D3 — tier-runner form:** shell script `tools/benchmark/run-tiers.sh` (minimal glue, no new tested Java surface).
- **D4 — evidence doc + artifacts:** new **`docs/BENCHMARKS.md`**; commit a **curated** per-tier fixture subset
  under `docs/benchmarks/`; **gitignore** the runtime `reports/` dir (raw, timestamped, non-reproducible per B3).

## Non-Goals (explicit backlog-defer homes)

- **Balance / metabolic / env tuning** → GUI/M5-gated (`ROADMAP.md` §Gated; `MEMORY.md` balance-tuning-deferred).
  This phase produces the *measurement substrate the tuning campaign (BACKLOG B4) is gated on*, nothing more.
- **`MetabolismIntegrationTest` re-enable** and **`HundredBotIntegrationTest` connect-latch race** → **Phase 22.1**
  (`ROADMAP.md` line 49–51). Phase 21 does not touch them.
- **`EncodeDeflatePerformanceGateTest` CI re-enable + revalidation** → **Phase 22.1** (per D2). P21 only
  removes its *blocker* (the absent metric).
- **JFR capture automation / P20 baseline re-run** → BACKLOG 999.5 (docs-only today).
- **Prometheus registry / `/actuator/prometheus`** → not added (YAGNI); revisit only if a meter can't ride
  the JSON endpoint.
- **Pass/fail *magnitude* gates in the default `./gradlew test` suite** → **forbidden by firewall** (below).
  Any threshold that genuinely must gate lives in `@Tag("slow")`/`@Tag("performance")`, opt-in only.
- **`paralife.runtime.app.*` namespace consolidation** → BACKLOG 999.4.

## Firewall (the crux — mechanism vs emergence)

Per `CLAUDE.md` §constitution-clause + label-vs-count corollary:

| Aspect | Class | Treatment |
|---|---|---|
| Benchmark **harness/report runs, collects, and reports** (fields present, wired to sources) | **mechanism** | spec + EARS + RED/GREEN, default suite |
| Scraper **parses actuator JSON → snapshot fields** (over *test-owned* canned input) | **mechanism** | pin extraction over a fixed fixture — never over a live run |
| Rejection-**under-overload is deterministic** (a reject emits its token/counter) | **mechanism** | pin the label/emission, not the live count |
| Throughput @1000, tick-drift p99, latency **magnitudes** | **environment-sensitive emergence** | **reports only** — never a default-suite `assertThat` |
| Any per-population **count / share / rate** from a live run | **emergence** | class-banned in default suite; report or `@Tag("slow")` ordinal only |

**Bright lines the plan MUST honour (and reviewers MUST hunt):**
- **No default-suite `assertThat` on a benchmark magnitude or any live-run aggregate.** A test asserting a
  throughput/drift/latency number, or a live rejection *count*, is a firewall violation — flag it.
- **Test-owned-input carve-out:** asserting the scraper extracts `rejected_total=7` from *a canned JSON the
  test supplies* is a parser-mechanism test (the test owns the input), **not** a banned live-aggregate
  assertion. The distinction is the reviewers' sharpest probe.
- EARS clauses pin to the **transformation contract** (a config accessor / metric-name constant), never a
  hardcoded magnitude — e.g. summary registered under the `METRIC_TICK_DRIFT` name constant and
  `count()==N` for N test-supplied samples, not `p99 < 5.0`.
- Any scale threshold that must gate → `@Tag("slow")`, excluded from `./gradlew test`.

## Readiness

**GO-WITH-CAVEATS.**
- **GO:** the mechanism spine (drift metric, scraper, report schema) is deterministic, EARS-phrasable, and
  TDD-able; the missing metric is a real, scoped gap with an existing consumer.
- **CAVEATS:** (1) all magnitudes stay in reports / `@Tag("slow")` — zero magnitude assertions enter the
  default suite; (2) the 22.1 boundary is settled (D2) — P21 claims **no** test re-enable; the `@Disabled`-not-tag
  firewall trap is banked for 22.1; (3) the docs editorial pass may relocate the merge-back targets — keep doc
  references indirect and flag the target (Coupling note); (4) 1000-bot feasibility (D1) may cap the *delivered*
  envelope at 500 with 1000 documented-not-sustained — the plan handles this without a fabricated green.

## Coupling note (execution-order dependency)

A parallel session is running a **docs editorial pass** that restructures/renames
`SCHEMA`/`ARCHITECTURE`/`RUNTIME`/`HARNESS` — the same docs this phase reads and merges back into.
**Assume those four may move/rename.** Keep in-plan doc references indirect (name the doc's *role* — "the
harness contract doc" — over a pinned path where practical), and flag the merge-back target explicitly so
execution can be sequenced: **editorial ideally lands first.** If it hasn't, slice 5's merge-back target is
provisional.
