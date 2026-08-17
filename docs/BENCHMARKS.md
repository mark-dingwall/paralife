# `docs/BENCHMARKS.md` — Phase 21 scale-benchmark evidence

Real captured evidence from the Task 1–4 pipeline (`tools/benchmark/run-tiers.sh` +
`ServerMetricsScraper` + `ReportSnapshot.serverMetrics()`), run against a live server on a
**WSL2 dev box** — not a production capacity rig. Per plan decision D1: this doc **reports
observations**, it does not assert thresholds, and it documents not-sustained tiers honestly
rather than fabricating a green.

These are **historical fixtures captured on 2026-07-05**, not proof of HEAD capacity. The reports
record JVM `21.0.6` but no source SHA, effective configuration, host capacity, or JVM flags; project
defaults have changed since capture. Use them as dated evidence only, not as a baseline comparison
against current code.

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

Each tier was captured against a **fresh server** — the HIGH-3 fix: a single long-lived `bootRun`
across tiers made `paralife.admission.rejected` accumulate across the sweep, overstating later
tiers. Protocol per tier: start, health-poll, run the harness, kill, repeat.

```bash
./gradlew loadHarnessJar

# per tier (100 / 500 / 1000):
java -jar build/libs/*-SNAPSHOT.jar &                # fresh server; admission cap = 256 (application.yml paralife.admission.cap)
until curl -sf http://localhost:8080/actuator/health >/dev/null; do sleep 1; done

java -jar build/libs/*-load-harness.jar --server-uri ws://localhost:8080/ws/world \
    --count <100|500|1000> --duration 30 --ramp-up rate:50 --report-out reports/run-<epoch>-t<tier>/bench-<tier>.json

kill %1                                              # fresh server for the next tier
```

(Invoked as three isolated per-tier restarts rather than through `tools/benchmark/run-tiers.sh`,
which currently assumes one server across the whole sweep — see the `run-tiers.sh` deferral in
`BACKLOG.md`. Because each tier starts from a clean server, `paralife.admission.rejected` below is
a **per-tier** count, not cumulative across tiers.)

Gate applied to every report (per `docs/HARNESS.md` §12):

```bash
jq -e '(.peak_registered // 0) > 0 and ((.server_metrics // {}) | to_entries | any(.value != null))' docs/benchmarks/bench-<tier>.json
```

All three: `true`.

## Per-tier results

| Tier (target `--count`) | Report | Peak concurrent (`peak_registered`) | Sessions at run-end (`paralife.ws.active.sessions`) | Connect failures | `paralife.admission.rejected` (COUNT, per-tier) | `paralife.tick.work.ms` MAX (ms) | Backpressure stall/rebound/dropout | Exit reason |
|---|---|---|---|---|---|---|---|---|
| 100  | `docs/benchmarks/bench-100.json`  | 100 | 100 | 0   | null (empty) | 54.90 (54.899518) | 0.0 / 0.0 / 0.0 (`stalled.sessions` null) | `duration-reached` |
| 500  | `docs/benchmarks/bench-500.json`  | 256 | 255 | 244 | 245  | 63.39 (63.385083) | 0.0 / 0.0 / 0.0 (`stalled.sessions` null) | `duration-reached` |
| 1000 | `docs/benchmarks/bench-1000.json` | 256 | 208 | 744 | 792 | 60.96 (60.961482) | 0.0 / 0.0 / 0.0 (`stalled.sessions` null) | `duration-reached` |

Throughput observed (per-report, not a rate — see variance caveat): `actions_sent_total` /
`perceptions_received_total` / `syncs_received_total` were 5,931 / 6,159 / 328 (100-tier), 18,312 /
19,059 / 1,002 (500-tier), 22,898 / 23,835 / 1,145 (1000-tier), over wall-clock 32s / 40s / 50s
respectively (harness-measured `wall_time_seconds_elapsed`, longer than the 30s `--duration` because
that clock covers connect/ramp-up too).

### Caveats (apply to every row above)

- **Variance caveat:** each figure is a single unseeded sample. Live-WS action timing is unseeded
  (`BACKLOG.md` B3) — re-running the same command will not reproduce these exact magnitudes, only
  the same qualitative shape. No median/range was captured (N=1 per tier); a repeat run is expected
  to drift.
- **MAX caveat:** `paralife.tick.work.ms` MAX is `SimpleMeterRegistry`'s decaying
  `TimeWindowMax` (Micrometer's default `distributionStatisticExpiry` is ~2 minutes), not the
  run-global peak. These runs (32–50s wall time) are shorter than that window, so no early spike
  should have decayed out of the reported value — but for any future run longer than that window,
  scrape promptly at run-end or treat the MAX as a recency-weighted figure, not a true peak.
- **Work-time, not drift:** `paralife.tick.work.ms` is per-tick listener-dispatch work-time. Against
  a 500ms tick period (`application.yml`), MAX values of ~55–63ms show roughly 437–445ms of nominal
  work-time headroom; they do **not** measure wake-up or scheduling drift.
- **Empty-category honesty:** `paralife.backpressure.stalled.total` / `.rebound` /
  `.terminal.dropouts` are eagerly-registered counters — they read `0.0` in **all three** tiers,
  not null, because this run never drove a genuine mid-session outbound-queue stall (that requires
  sustained slow-client backpressure, not just admission-time rejection). `stalled.sessions` is
  lazily/tag-registered and reads `null` for the same reason. Nothing here claims stall/rebound/
  dropout recovery was demonstrated. By contrast, `paralife.admission.rejected` (also
  lazily/tag-registered) *is* populated with real per-tier counts at the 500 and 1000 tiers — see
  below.

## What actually happened at 500 / 1000: the admission cap, not degradation

The 500 and 1000-bot tiers did **not** reach their target concurrent count, and the reason is
directly attributable, not a mystery: `application.yml` sets `paralife.admission.cap: 256`. Both
tiers plateaued at `peak_registered=256` — exactly the configured cap — while `connect_failures_total`
(244 at 500-tier, 744 at 1000-tier) and the per-tier-isolated `paralife.admission.rejected` COUNT
meter (245 at 500-tier, 792 at 1000-tier) climbed with the oversubscription ratio. `paralife.tick.work.ms`
MAX stayed bounded across all three tiers — 54.90 / 63.39 / 60.96ms, no monotonic trend with load —
and the rejection counter was populated rather than the run erroring out — this is the Phase 17
durable-admission gate enforcing its configured ceiling under 2x and 4x oversubscription, not the
server failing to sustain load. (A manual `/actuator/health` check returned `200` with no
exception/OOM seen during the runs, but that was an **out-of-band** observation: only the
`/actuator/metrics` figures cited here are captured in the committed report artifacts, so the
"admission-capped, not degraded" conclusion rests on those bounded metrics — not on the health probe.)

(The run-end `paralife.ws.active.sessions` figures — 255 at the 500-tier, 208 at the 1000-tier — sit
*at-or-below* the `peak_registered=256` high-water mark: `peak_registered` is a monotonic maximum, while
`active.sessions` is a point-in-time gauge sampled at report time, so ordinary admit/expire churn under
sustained oversubscription leaves the instantaneous count under the peak. Not a discrepancy.)

**So, per D1: the 500 and 1000-bot tiers are recorded as not-sustained *at their target concurrent
count*** — that is an honest, real result, not a failure mode of the harness or the server. The
failure mode, where one exists, is "admission-capped by design," not "degraded/crashed/OOM."

## M4-close boundary statement

**(a) Validated scale envelope — tiers with real captured evidence:**
- **100-bot tier**: the final snapshot recorded `peak_registered == current_registered == 100`,
  zero initial connect failures, and no admission-rejection meter. The overwrite report has no
  availability time series, so it does not prove uninterrupted occupancy for the full run.
  `docs/benchmarks/bench-100.json`.
- **500 and 1000-bot *target* tiers**: real evidence captured (`docs/benchmarks/bench-500.json`,
  `docs/benchmarks/bench-1000.json`), but the *validated concurrent envelope* both plateau at is the
  configured **admission cap of 256 concurrent sessions** — demonstrated safely and consistently under
  both 2x (500) and 4x (1000) oversubscription, with the rejection meter populated and
  `paralife.tick.work.ms` staying bounded throughout. **The validated scale envelope for this phase is: 100 bots fully
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

*Source reports: `docs/benchmarks/bench-{100,500,1000}.json`. Companion: `docs/HARNESS.md` §12 (the
sweep script + live-scrape control this evidence was captured with).*
