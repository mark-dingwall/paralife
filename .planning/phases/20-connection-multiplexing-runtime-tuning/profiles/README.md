# Phase 20 profile artifacts

Every captured JFR + flamegraph + actuator-metric sidecar Phase 20 commits to
the repo lives in this directory. Read [`tools/async-profiler-bootstrap.md`](../../../../tools/async-profiler-bootstrap.md)
for the `asprof` install + capture commands. This README is the **filename convention,
the baseline ritual, and the size-discipline contract** every artifact must satisfy.

## Filename convention

Per Phase 20 D-19 every artifact embeds its source commit SHA so the file
self-documents what code it profiled. State is one of:
- `baseline` — churn scenario; canonical series is `62c1b44` (Plan 1c re-anchor;
  original `c22e487` series superseded/history-only — see §6 of `20-RUNTIME.md`)
- `active-50xfood` — active-population scenario (50× food); canonical series is `103a615`
- `tuned` — HEAD short-SHA at capture time; scenario-aware per Plan 5 convention,
  e.g. `jfr-1000bots-active-50xfood-tuned-424e06d.jfr`

| Pattern | Example |
|---|---|
| `jfr-{N}bots-baseline-{SHA}.jfr` | `jfr-1000bots-baseline-62c1b44.jfr` |
| `jfr-{N}bots-{scenario}-tuned-{HEAD_SHA}.jfr` | `jfr-1000bots-active-50xfood-tuned-424e06d.jfr` |
| `cpu-{N}bots-{state}-{sha}.html` | `cpu-1000bots-baseline-62c1b44.html` |
| `alloc-{N}bots-{state}-{sha}.html` | `alloc-1000bots-baseline-62c1b44.html` |
| `lock-{N}bots-{state}-{sha}.html` | `lock-1000bots-baseline-62c1b44.html` |
| sibling meta | `<jfr-name>.meta.json` (capture context) |
| sibling actuator-metric sidecar | `metrics-{N}bots-{state}-{sha}.json` (Pass-2 Concern #10) |

`{N}` is one of `100`, `500`, `1000` (the three scale tiers Phase 20 D-08 cares
about). The SHA segment must match `git rev-parse --short HEAD` at the time the
JAR being profiled was built.

## 62c1b44 baseline ritual

The baseline capture is the foundation every later before/after diff stands on.
The canonical churn-baseline series is `62c1b44` (Plan 1c F6 re-anchor, post-Phase
19.1 close). The original `c22e487` series is retained on disk for history only —
it surfaced F1/F2/F6 defects that shifted the post-fix baseline. Do NOT use
`c22e487` for new reproductions.

Run the baseline ONCE per phase iteration cycle, verbatim:

```bash
# 1. Capture the pristine baseline at the canonical Plan 1c re-anchor SHA
git stash --include-untracked  # protect any uncommitted work
git checkout 62c1b44
./gradlew clean loadHarnessJar bootJar

# 2. Start the server with JFR continuous-recording from boot
JFR_OUT=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-62c1b44.jfr"
java \
  -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=duration=180s,filename="$JFR_OUT",settings=profile,name=p20-baseline-1000 \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -Dparalife.admission.cap=1500 \
  -jar build/libs/paralife-0.0.1-SNAPSHOT.jar \
  --paralife.simulation.spawn.seed=20251205 &
SERVER_PID=$!

# 3. Drive load via the harness jar built in step 1
java -jar build/libs/paralife-0.0.1-SNAPSHOT-load-harness.jar \
  --server-uri ws://localhost:8080/ws/world \
  --count 1000 --duration 180 --ramp-up rate:50 \
  --harness-id baseline-62c1b44

# 4. While load runs: capture flamegraphs concurrently (see tools/async-profiler-bootstrap.md)
ASYNC_PROFILER=~/tools/async-profiler/bin/asprof
OUT_DIR=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles"
$ASYNC_PROFILER -d 60 -e cpu   -f "$OUT_DIR/cpu-1000bots-baseline-62c1b44.html"   $SERVER_PID
$ASYNC_PROFILER -d 60 -e alloc -f "$OUT_DIR/alloc-1000bots-baseline-62c1b44.html" $SERVER_PID
$ASYNC_PROFILER -d 60 -e lock  -f "$OUT_DIR/lock-1000bots-baseline-62c1b44.html"  $SERVER_PID

# 5. Scrape actuator metrics into the sidecar (Pass-2 Concern #10)
curl -s http://localhost:8080/actuator/metrics/paralife.tick.health.work-time-ms > \
  "$OUT_DIR/metrics-1000bots-baseline-62c1b44.json"

# 6. Repeat steps 2-5 for --count 100 and --count 500 tiers

# 7. Restore the working tree
git checkout - && git stash pop
```

The same ritual runs against tier 100 and tier 500 — substitute `--count` and
the `{N}bots` segment of the filename.

## Re-running (tuned-state captures)

Tuned captures use whatever SHA the server is being built from now. Use the
scenario-aware filename pattern (Plan 5 convention):

```bash
HEAD_SHA=$(git rev-parse --short HEAD)
SCENARIO="active-50xfood"          # or "baseline" for churn-baseline variant
./gradlew clean loadHarnessJar bootJar    # build at HEAD
# ... repeat steps 2-5 above, swapping every "baseline-62c1b44" segment for
# "${SCENARIO}-tuned-${HEAD_SHA}", e.g. "active-50xfood-tuned-${HEAD_SHA}"
# For the active-50xfood scenario, add to the server launch:
#   --paralife.simulation.nutrient-spawn-probability=0.05
# (as a Spring app-arg AFTER -jar, NOT as -D before -jar -- gemini R4 finding)
```

Tuned captures land alongside the baseline. The before/after deltas live in `20-RUNTIME.md` §4.2 and §4.4.

## Size discipline

Phase 20 D-05 bound: **≤10 MB per file, ≤50 MB phase-total**. JFR `settings=profile` at 180s/1000 bots can balloon to 50-200 MB raw. After every capture:

```bash
jfr summary "$OUT_DIR/jfr-1000bots-active-50xfood-tuned-${HEAD_SHA}.jfr" | head
# If > 10 MB:
jfr filter \
  --include-events 'jdk.VirtualThreadPinned,jdk.GCPhasePause,jdk.ObjectAllocationSample,jdk.JavaMonitorEnter' \
  "$OUT_DIR/jfr-1000bots-active-50xfood-tuned-${HEAD_SHA}.jfr" \
  "$OUT_DIR/jfr-1000bots-active-50xfood-tuned-${HEAD_SHA}.filtered.jfr"
mv "$OUT_DIR/jfr-1000bots-active-50xfood-tuned-${HEAD_SHA}.filtered.jfr" \
   "$OUT_DIR/jfr-1000bots-active-50xfood-tuned-${HEAD_SHA}.jfr"
```

`du -sh $OUT_DIR` must stay under 50 MB before commit. Anything older than the
current phase iteration goes — D-19 reproducibility is preserved by the
filename SHA, not by keeping every prior capture.

## Reproducibility (D-19)

The SHA in the filename is the single source of truth for what code was profiled. JFR recordings carry process metadata (JVM args, host, capture timestamps) but NOT the git SHA — so the filename + sibling `*.meta.json` is what closes the loop:

```json
{ "captured_at_sha": "62c1b44", "scenario": "1000bots", "duration_s": 200,
  "harness_args": "--count 1000 --duration 200 --ramp-up rate:50 --harness-id baseline-62c1b44-t1000",
  "captured_utc": "2026-05-20T22:11:46Z" }
```

Every committed JFR MUST have a sibling `*.meta.json`. Reviewers check both.

## Three-gate stack sanity check

Before AND after every fresh capture, run the in-suite three-gate stack to confirm the codebase being profiled is itself green:

```bash
./gradlew test \
  --tests GoldenTraceEquivalenceTest \
  --tests GoldenTraceWithActionsTest \
  --tests LiveEntityRegistryInvariantTest
```

This is **in-suite only** — never run these tests in isolation. TD-19.5-A (`OutboundSender.awaitAllSessionQueuesDrained` VT race) makes `GoldenTraceEquivalenceTest` ~40% flaky when run alone, so isolation hides drift. Two consecutive in-suite greens before shipping a codec opt (Pitfall 4); one in-suite green is sufficient as a pre-capture sanity gate.

The harness jar itself is built via the existing `loadHarnessJar` Gradle task (`build.gradle.kts:95`); the bootstrap doc and this README both assume `./gradlew loadHarnessJar bootJar` has been run at the target SHA.
