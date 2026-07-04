#!/usr/bin/env bash
# tools/benchmark/run-tiers.sh — repeatable scale sweep. "Repeatable" = re-runnable command +
# saved report per tier; NOT bit-identical numbers (live-WS timing is unseeded — see spec Assumptions).
# NOTE: after creating this file, `chmod +x tools/benchmark/run-tiers.sh` (a patch leaves it 0644) OR
# always invoke it as `bash tools/benchmark/run-tiers.sh …`. `set -e` is deliberately OMITTED — the
# per-tier fail-soft path needs a non-fatal error branch; failures are accumulated and surfaced via the
# EXIT CODE instead (a masked non-zero would let a dead-server / all-zero sweep look successful).
# Duration note: keep DURATION under Micrometer's distribution-statistic-expiry window (~2 min) or the
# tick-drift MAX becomes recency-weighted, not a run peak (see docs/BENCHMARKS.md §Caveats). Requires jq.
set -uo pipefail
SERVER_URI="${1:?usage: run-tiers.sh <ws-uri> [duration-seconds]}"
DURATION="${2:-120}"
# Fresh per-sweep dir so the verify gate can't match a STALE report from a prior sweep (D1/evidence-bound).
RUN="reports/run-$(date +%s)"; mkdir -p "$RUN"
echo ">>> sweep dir: $RUN"

# Resolve the harness jar once, guarding the glob: a stray second *-load-harness.jar would otherwise
# silently become a `java -jar` program arg rather than an error.
shopt -s nullglob; JARS=(build/libs/*-load-harness.jar); shopt -u nullglob
if [ "${#JARS[@]}" -ne 1 ]; then
  echo ">>> ERROR: expected exactly 1 build/libs/*-load-harness.jar, found ${#JARS[@]} — run ./gradlew loadHarnessJar" >&2
  exit 2
fi
JAR="${JARS[0]}"

FAILURES=0
for TIER in 100 500 1000; do
  OUT_FILE="${RUN}/bench-${TIER}.json"
  echo ">>> tier=${TIER} report=${OUT_FILE}"
  if ! java -jar "$JAR" \
      --server-uri "$SERVER_URI" --count "$TIER" --duration "$DURATION" \
      --ramp-up rate:50 --report-out "$OUT_FILE"; then
    echo ">>> tier=${TIER} FAILED: harness exited non-zero" >&2
    FAILURES=$((FAILURES + 1)); continue
  fi
  # Per-report gate (the check docs/HARNESS.md §12 promises): a real run registered >0 bots AND scraped
  # >=1 non-null server metric. A dead/wrong server yields peak_registered=0 + all-null metrics — caught
  # here, not presented as a green sweep.
  if ! jq -e '(.peak_registered // 0) > 0
              and ((.server_metrics // {}) | to_entries | any(.value != null))' "$OUT_FILE" >/dev/null; then
    echo ">>> tier=${TIER} FAILED: degenerate report (peak_registered=0 or no server metrics) — server down?" >&2
    FAILURES=$((FAILURES + 1))
  fi
done

echo ">>> sweep complete: $RUN (failures=${FAILURES})"
[ "$FAILURES" -eq 0 ] || exit 1
