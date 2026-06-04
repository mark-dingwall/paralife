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
# 1. Capture the pristine baseline at the canonical Plan 1c re-anchor SHA.
#    Artifacts are staged under /tmp while the tree is detached at 62c1b44 —
#    their target paths under profiles/ are tracked at HEAD but absent at
#    62c1b44, so writing them in-place would make `git checkout -` refuse to
#    restore ("untracked working tree files would be overwritten"). Copy into
#    profiles/ AFTER the restore (step 8).
git stash --include-untracked  # protect any uncommitted work
git checkout 62c1b44
./gradlew clean loadHarnessJar bootJar
STAGE=/tmp/p20-baseline-62c1b44; mkdir -p "$STAGE"

# 2. Start the server with JFR continuous-recording from boot, then gate on
#    readiness — the harness must not fire connection attempts at a socket
#    that is not listening yet (mirrors capture-active.sh's boot-wait).
JFR_OUT="$STAGE/jfr-1000bots-baseline-62c1b44.jfr"
java \
  -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=duration=200s,filename="$JFR_OUT",settings=profile,name=p20-baseline-1000 \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -Dparalife.admission.cap=1500 \
  -jar build/libs/paralife-0.0.1-SNAPSHOT.jar \
  --paralife.simulation.spawn.seed=20251205 > "$STAGE/server-1000.log" 2>&1 &
SERVER_PID=$!
for i in $(seq 1 40); do
  grep -q "Started ParalifeApplication" "$STAGE/server-1000.log" && break; sleep 1
done

# 3. Drive load via the harness jar built in step 1 — BACKGROUNDED so steps
#    4-5 capture while load is actually running (a foreground harness would
#    block the shell for the full run and steps 4-5 would profile an idle,
#    drained server — wrong regime, not the committed steady-state evidence)
java -jar build/libs/paralife-0.0.1-SNAPSHOT-load-harness.jar \
  --server-uri ws://localhost:8080/ws/world \
  --count 1000 --duration 200 --ramp-up rate:50 \
  --harness-id baseline-62c1b44-t1000 &
HARNESS_PID=$!
sleep 20   # let ramp-up (rate:50 -> 1000 bots) complete before profiling

# 4. While load runs: capture flamegraphs (serial inside one background
#    subshell — async-profiler allows only ONE active session per JVM, so the
#    three 60 s captures must not overlap each other). Timing: the harness
#    ramps the fleet up FIRST and only then starts its 200 s duration wait,
#    so the load window is ~220 s from harness launch — the 20 s sleep +
#    180 s of serial flamegraphs finish with ~20 s of load to spare.
#    Step 5's curl-only metric loop runs concurrently.
ASYNC_PROFILER=~/tools/async-profiler/bin/asprof
OUT_DIR="$STAGE"
(
  $ASYNC_PROFILER -d 60 -e cpu   -f "$OUT_DIR/cpu-1000bots-baseline-62c1b44.html"   $SERVER_PID
  $ASYNC_PROFILER -d 60 -e alloc -f "$OUT_DIR/alloc-1000bots-baseline-62c1b44.html" $SERVER_PID
  $ASYNC_PROFILER -d 60 -e lock  -f "$OUT_DIR/lock-1000bots-baseline-62c1b44.html"  $SERVER_PID
) &
FLAME_PID=$!

# 5. Capture the actuator metric sidecar (Pass-2 Concern #10) with a
#    baseline-safe sampling loop. Do NOT use ../capture-active.sh here — that
#    script is the ACTIVE-50xfood variant (hard-codes TAG=active-50xfood,
#    FOOD=0.05, 90 s window, and boots its own server with the food flag);
#    running it for the churn baseline would mint wrong-scenario sidecars.
#    This loop mirrors its METRICS array (incl. BOTH headline gauges) and the
#    committed schema {captured_at_sha, scenario, cap_during_run, samples[]}:
METRICS=(paralife.tick.work.ms paralife.tick.health.work-time-ms paralife.admission.active.entities \
  paralife.admission.rejected paralife.backpressure.stalled.sessions paralife.outbound.frame.size.bytes \
  paralife.outbound.queue.depth.max paralife.outbound.encode.send.ms paralife.outbound.detach.timeout)
SIDE="$OUT_DIR/metrics-1000bots-baseline-62c1b44.json"
echo '{"captured_at_sha":"62c1b44","scenario":"1000bots","cap_during_run":1500,"samples":[' > "$SIDE"
first=1
for s in $(seq 1 6); do      # 6 samples x 5 s, matching the committed sidecars
  obj=$(jq -n --arg t "$(date -u +%FT%T+00:00)" '{sample_utc:$t}')
  for m in "${METRICS[@]}"; do
    key=$(echo "$m" | sed 's/\./_/g')
    body=$(curl -s -m3 "localhost:8080/actuator/metrics/${m}" 2>/dev/null)
    [ -z "$body" ] && body=null
    obj=$(echo "$obj" | jq --arg k "$key" --argjson v "$body" '. + {($k):$v}')
  done
  [ $first -eq 1 ] && first=0 || echo "," >> "$SIDE"
  echo "$obj" | jq -c . >> "$SIDE"
  sleep 5
done
echo ']}' >> "$SIDE"
#    A one-shot curl of a single gauge does NOT reproduce the committed
#    metrics-*.json schema and misses the detach.timeout headline gauge.

# 5b. Let the flamegraph captures and the harness run to completion, then
#     STOP THE SERVER — :8080 must be free before the next tier's step 2, and
#     no JVM may survive into the git restore. Waits are separate so a
#     flamegraph failure isn't masked by the harness's exit code (a combined
#     `wait A B` returns only the LAST pid's status); eyeball the three
#     cpu/alloc/lock-*.html files in $STAGE before moving on.
wait "$FLAME_PID"
wait "$HARNESS_PID"
kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null || true

# 6. Repeat steps 2-5b for --count 100 and --count 500 tiers

# 7. Restore the working tree
git checkout - && git stash pop

# 8. Move the staged artifacts into profiles/ on the restored branch
cp "$STAGE"/jfr-*.jfr "$STAGE"/metrics-*.json "$STAGE"/{cpu,alloc,lock}-*.html \
  .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/
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
# (as a Spring app-arg AFTER -jar; a -D form placed AFTER -jar is a program
#  argument Spring silently ignores -- gemini R4 finding. -D BEFORE -jar is a
#  JVM system property and does work, but the app-arg form is the convention.)
```

Tuned captures land alongside the baseline. The before/after deltas live in `20-RUNTIME.md` §4.2 and §4.4.

## Size discipline

Phase 20 D-05 bound: **≤10 MB per file, ≤50 MB phase-total**. JFR `settings=profile` at 180–200s/1000 bots can balloon to 50-200 MB raw. After every capture:

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
