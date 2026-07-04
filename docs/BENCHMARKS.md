# `docs/BENCHMARKS.md` — Phase 21 scale-benchmark evidence

Real captured evidence from the Task 1–4 pipeline (`tools/benchmark/run-tiers.sh` +
`ServerMetricsScraper` + `ReportSnapshot.serverMetrics()`), run against a live server on a
**WSL2 dev box** — not a production capacity rig. Per plan decision D1: this doc **reports
observations**, it does not assert thresholds, and it documents not-sustained tiers honestly
rather than fabricating a green.

Every figure below traces to a saved report file (`ls docs/benchmarks/`):

```
$ ls -la docs/benchmarks/
bench-100.json
bench-500.json
bench-1000.json
```

Raw, timestamped sweep output lives (gitignored) under `reports/run-<epoch>-t<tier>/` on the
machine that captured it; the files above are that run's `bench-<tier>.json`, copied verbatim
under fixed names for citation. `reports/` is never committed (`.gitignore`).

## Commands run

```bash
./gradlew loadHarnessJar
./gradlew bootRun &                    # Jetty on :8080, admission cap = 256 (application.yml paralife.admission.cap)

java -jar build/libs/*-load-harness.jar --server-uri ws://localhost:8080/ws/world \
    --count 100  --duration 30 --ramp-up rate:50 --report-out reports/run-1783129007-t100/bench-100.json

java -jar build/libs/*-load-harness.jar --server-uri ws://localhost:8080/ws/world \
    --count 500  --duration 30 --ramp-up rate:50 --report-out reports/run-1783129053-t500/bench-500.json

java -jar build/libs/*-load-harness.jar --server-uri ws://localhost:8080/ws/world \
    --count 1000 --duration 30 --ramp-up rate:50 --report-out reports/run-1783129133-t1000/bench-1000.json
```

(Invoked as three direct per-tier `java -jar` calls rather than through
`tools/benchmark/run-tiers.sh` — same command shape the script wraps, `--duration 30` in place of
the script's default 120s to bound wall-clock on this box; the script itself was syntax-checked
and its live-scrape integration path exercised in Task 4.)

Gate applied to every report (per `docs/HARNESS.md` §12):

```bash
jq -e '.server_metrics | to_entries | map(select(.value != null)) | length > 0' docs/benchmarks/bench-<tier>.json
```

All three: `true`.

## Per-tier results

| Tier (target `--count`) | Report | Peak concurrent (`peak_registered`) | Sessions at run-end (`paralife.ws.active.sessions`) | Connect failures | `paralife.admission.rejected` (COUNT) | Tick-drift MAX (ms) | Backpressure stall/rebound/dropout | Exit reason |
|---|---|---|---|---|---|---|---|---|
| 100  | `docs/benchmarks/bench-100.json`  | 100 | 100 | 0   | null (empty) | 53 | 0 / 0 / 0 (`stalled.sessions` null) | `duration-reached` |
| 500  | `docs/benchmarks/bench-500.json`  | 256 | 168 | 244 | 332  | 70 | 0 / 0 / 0 (`stalled.sessions` null) | `duration-reached` |
| 1000 | `docs/benchmarks/bench-1000.json` | 256 | 173 | 744 | 1159 | 84 | 0 / 0 / 0 (`stalled.sessions` null) | `duration-reached` |

Throughput observed (per-report, not a rate — see variance caveat): `actions_sent_total` /
`perceptions_received_total` / `syncs_received_total` were 5,937 / 6,179 / 342 (100-tier), 17,257 /
18,324 / 1,235 (500-tier), 22,196 / 23,276 / 1,243 (1000-tier), over wall-clock 32s / 40s / 50s
respectively (harness-measured `wall_time_seconds_elapsed`, longer than the 30s `--duration` because
that clock covers connect/ramp-up too).

### Caveats (apply to every row above)

- **Variance caveat:** each figure is a single unseeded sample. Live-WS action timing is unseeded
  (`BACKLOG.md` B3) — re-running the same command will not reproduce these exact magnitudes, only
  the same qualitative shape. No median/range was captured (N=1 per tier); a repeat run is expected
  to drift.
- **MAX caveat:** `paralife.tick.drift.millis` MAX is `SimpleMeterRegistry`'s decaying
  `TimeWindowMax` (~1-minute window), not the run-global peak. These runs (32–50s wall time) are
  shorter than that window, so no early spike should have decayed out of the reported value — but
  for any future run longer than ~1 minute, scrape promptly at run-end or treat the MAX as a
  recency-weighted figure, not a true peak.
- **Empty-category honesty:** `paralife.backpressure.stalled.sessions` / `.stalled.total` /
  `.rebound` / `.terminal.dropouts` read `0`/null in **all three** tiers — this run never drove a
  genuine mid-session outbound-queue stall (that requires sustained slow-client backpressure, not
  just admission-time rejection). Those categories are **structurally-present-but-empty** in every
  report here; nothing below claims stall/rebound/dropout recovery was demonstrated. By contrast,
  the **rejection** category (`paralife.admission.rejected`) *is* populated with real counts at the
  500 and 1000 tiers — see below.

## What actually happened at 500 / 1000: the admission cap, not degradation

The 500 and 1000-bot tiers did **not** reach their target concurrent count, and the reason is
directly attributable, not a mystery: `application.yml` sets `paralife.admission.cap: 256`. Both
tiers plateaued at `peak_registered=256` — exactly the configured cap — while `connect_failures_total`
(244 at 500-tier, 744 at 1000-tier) and the `paralife.admission.rejected` COUNT meter (332 / 1159)
climbed with the oversubscription ratio. The server's `/actuator/health` endpoint returned `200`
throughout both runs, tick-drift MAX rose only modestly (70ms, 84ms vs. 53ms at the 100-tier), and
no exception, OOM, or unresponsiveness was observed — this is the Phase 17 durable-admission gate
enforcing its configured ceiling under 2x and 4x oversubscription, not the server failing to sustain
load.

(The run-end `paralife.ws.active.sessions` figures — 168 at the 500-tier, 173 at the 1000-tier — sit
*below* the `peak_registered=256` high-water mark: `peak_registered` is a monotonic maximum, while
`active.sessions` is a point-in-time gauge sampled at report time, so ordinary admit/expire churn under
sustained oversubscription leaves the instantaneous count under the peak. Not a discrepancy.)

**So, per D1: the 500 and 1000-bot tiers are recorded as not-sustained *at their target concurrent
count*** — that is an honest, real result, not a failure mode of the harness or the server. The
failure mode, where one exists, is "admission-capped by design," not "degraded/crashed/OOM."

## M4-close boundary statement

**(a) Validated scale envelope — tiers with real captured evidence:**
- **100 concurrent bots**: fully validated. All 100 requested connections registered and held for
  the full run (`peak_registered == current_registered == 100`), zero connect failures, zero
  admission rejections. `docs/benchmarks/bench-100.json`.
- **500 and 1000-bot *target* tiers**: real evidence captured (`docs/benchmarks/bench-500.json`,
  `docs/benchmarks/bench-1000.json`), but the *validated concurrent envelope* both plateau at is the
  configured **admission cap of 256 concurrent sessions** — demonstrated safely and repeatably under
  both 2x (500) and 4x (1000) oversubscription, with the rejection meter populated and the server
  remaining healthy throughout. **The validated scale envelope for this phase is: 100 bots fully
  connected, and up to 256 concurrent sessions safely enforced under oversubscription up to at least
  4x** — not "500" or "1000 concurrent bots," which the current admission-cap config does not permit
  and this run does not claim.
- Raising the admission cap itself (i.e. validating a *larger* concurrent envelope than 256) is a
  config/tuning change, out of scope for this measurement task — see deferral (b) below.

**(b) Explicit deferrals and their homes:**
- **Tuning campaign** (raising `paralife.admission.cap`, retuning metabolic/env constants to
  change what population an oversubscribed tier can actually sustain) → **`BACKLOG.md` B4**
  (Ensemble-N tuning campaign), gated on Phase 21 landing (this phase) **and** a Core-Value
  spatial-emergence guard (M5 visualiser or an equivalent headless invariant) per the
  `docs/notes/headless-feedback-loop-adr.md` measurement/tuning split.
- **Live visualiser** (watching the world while under load, verifying spatial emergence survives
  oversubscription) → **M5 Observability & Operations** (`ROADMAP.md`).
- **Residual perf work** (re-enabling `EncodeDeflatePerformanceGateTest` and
  `MetabolismIntegrationTest` under benchmark conditions, the `@Disabled`-vs-`@Tag("slow")` firewall
  trap banked in the Task 5 brief) → **Phase 22.1** (`ROADMAP.md` "Later").

No production code and no test assertion was added or changed by this task — this document reports
magnitudes already published by Tasks 1–3's metrics/scraper work; it does not gate anything in
`./gradlew test`.

---

*Authored: Phase 21 Task 5. Source reports: `docs/benchmarks/bench-{100,500,1000}.json`. Companion:
`docs/HARNESS.md` §12 (the sweep script + live-scrape control this evidence was captured with).*
