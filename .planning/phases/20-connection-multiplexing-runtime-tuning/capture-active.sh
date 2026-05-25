#!/usr/bin/env bash
# P20 active-population profiling capture (50x food scenario).
# Produces scenario-tagged artifacts alongside the churn baseline.
# Usage: capture-active.sh COUNT SHA OUTDIR
set -uo pipefail

COUNT="$1"; SHA="$2"; OUTDIR="$3"
FOOD="0.05"; CAP="1500"; TAG="active-50xfood"
RAMP_S=20; PROFILE_S=90; HARNESS_DUR=130
JAR="build/libs/paralife-0.0.1-SNAPSHOT.jar"
HJAR="build/libs/paralife-0.0.1-SNAPSHOT-load-harness.jar"
ASPROF="$HOME/tools/async-profiler/bin/asprof"
JFRCONV="$HOME/tools/async-profiler/bin/jfrconv"
HID="${TAG}-${SHA}-t${COUNT}"

SLOG="/tmp/p20-cap-active/server-${COUNT}.log"
HLOG="/tmp/p20-cap-active/harness-${COUNT}.log"
JFR="${OUTDIR}/jfr-${COUNT}bots-${TAG}-${SHA}.jfr"
SIDE="${OUTDIR}/metrics-${COUNT}bots-${TAG}-${SHA}.json"
META="${OUTDIR}/jfr-${COUNT}bots-${TAG}-${SHA}.meta.json"

METRICS=(paralife.tick.work.ms paralife.tick.health.work-time-ms paralife.admission.active.entities \
  paralife.admission.rejected paralife.backpressure.stalled.sessions paralife.outbound.frame.size.bytes \
  paralife.outbound.queue.depth.max paralife.outbound.encode.send.ms paralife.outbound.detach.timeout)

echo "==> [${COUNT}] starting server (food=${FOOD} cap=${CAP})"
rm -f "$SLOG"
nohup java -Xms2g -Xmx2g -XX:+UseG1GC -Djdk.virtualThreadScheduler.parallelism=8 \
  -Dparalife.admission.cap=${CAP} -Dparalife.simulation.nutrient-spawn-probability=${FOOD} \
  -jar "$JAR" > "$SLOG" 2>&1 &
for i in $(seq 1 40); do grep -q 'Started ParalifeApplication' "$SLOG" 2>/dev/null && break; sleep 1; done
PID=$(pgrep -f "java .*-jar ${JAR}" | head -1)
echo "    server pid=$PID"

echo "==> [${COUNT}] starting harness (count=${COUNT} dur=${HARNESS_DUR})"
rm -f "$HLOG"
nohup java -jar "$HJAR" --server-uri=ws://localhost:8080/ws/world \
  --count=${COUNT} --duration=${HARNESS_DUR} --ramp-up=rate:50 \
  --harness-id=${HID} --report-out=/tmp/p20-cap-active/harness-${COUNT}-report.json \
  > "$HLOG" 2>&1 &
HPID=$!

echo "==> [${COUNT}] ramp ${RAMP_S}s then profile ${PROFILE_S}s"
sleep "$RAMP_S"
"$ASPROF" start -e cpu -i 10ms --alloc 512k --lock 10ms -o jfr -f "$JFR" "$PID" 2>&1 | sed 's/^/    asprof: /'

# sample metrics every 5s during the profile window
echo '{"captured_at_sha":"'"$SHA"'","scenario":"'"${COUNT}bots-${TAG}"'","cap_during_run":'"$CAP"',"food_spawn_prob":'"$FOOD"',"samples":[' > "$SIDE"
SAMPLES=$((PROFILE_S/5)); first=1
for s in $(seq 1 "$SAMPLES"); do
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

echo "==> [${COUNT}] stop asprof -> JFR"
"$ASPROF" stop "$PID" 2>&1 | sed 's/^/    asprof: /'

echo "==> [${COUNT}] flamegraphs"
"$JFRCONV" --cpu   "$JFR" "${OUTDIR}/cpu-${COUNT}bots-${TAG}-${SHA}.html"   2>&1 | sed 's/^/    cpu: /'
"$JFRCONV" --alloc "$JFR" "${OUTDIR}/alloc-${COUNT}bots-${TAG}-${SHA}.html" 2>&1 | sed 's/^/    alloc: /'
"$JFRCONV" --lock  "$JFR" "${OUTDIR}/lock-${COUNT}bots-${TAG}-${SHA}.html"  2>&1 | sed 's/^/    lock: /'

# meta
jfr_mb=$(du -m "$JFR" 2>/dev/null | cut -f1)
jq -n --arg sha "$SHA" --arg sc "${COUNT}bots-${TAG}" --arg hid "$HID" --arg food "$FOOD" \
  --arg mb "$jfr_mb" --arg cap "$CAP" '{captured_at_sha:$sha,scenario:$sc,profile_window_s:90,ramp_s:20,
   harness_args:("--count '"$COUNT"' --duration '"$HARNESS_DUR"' --ramp-up rate:50 --harness-id "+$hid),
   jvm_flags:"-Xms2g -Xmx2g -XX:+UseG1GC -Djdk.virtualThreadScheduler.parallelism=8 -Dparalife.admission.cap='"$CAP"' -Dparalife.simulation.nutrient-spawn-probability='"$FOOD"'",
   cap_during_run:($cap|tonumber),food_spawn_prob:($food|tonumber),
   asprof_cpu_interval_us:10000,asprof_alloc_interval_bytes:524288,jfr_size_mb:$mb,
   note:"Active-population scenario (50x food). Contrast with churn baseline *-62c1b44.* (production defaults)."}' > "$META"

echo "==> [${COUNT}] waiting harness exit"; wait "$HPID" 2>/dev/null
echo "==> [${COUNT}] stopping server"; kill -9 "$PID" 2>/dev/null; sleep 2
echo "==> [${COUNT}] DONE. artifacts:"; ls -la "${OUTDIR}"/*${COUNT}bots-${TAG}-${SHA}* 2>/dev/null
