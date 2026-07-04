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
