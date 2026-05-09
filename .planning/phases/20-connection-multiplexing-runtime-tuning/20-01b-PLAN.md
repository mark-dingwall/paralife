---
phase: 20-connection-multiplexing-runtime-tuning
plan: 01b
type: execute
wave: 2
depends_on: [20-01]
files_modified:
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.jfr
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.jfr
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.meta.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.meta.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json
  - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json
autonomous: false
requirements: [SCALE-09]

must_haves:
  truths:
    - "JFR baseline files for 100 / 500 / 1000-bot runs exist under profiles/, each ≤10 MB (D-06: profile run scenarios at minimum 100/500/1000-bot LoadHarness; D-04: JFR + async-profiler toolchain produces them)."
    - "Three baseline async-profiler flamegraph HTMLs exist (cpu/alloc/lock at 1000 bots)."
    - "Every committed baseline JFR filename contains the literal substring `c22e487` (D-19)."
    - "Each JFR has a sibling *.meta.json carrying captured_at_sha=c22e487, scenario, duration, harness_args, captured_utc, JVM flags, and A1/A2/A6/A7/A8 verification outcomes."
    - "Three-gate stack (D-11) runs green in-suite immediately after baseline capture (sanity of clean checkout)."
    - "Assumptions A1, A2, A6, A7, A8 from 20-RESEARCH.md §Assumptions Log are explicitly checked and recorded in meta.json companions."
    - "20-01b-SUMMARY.md is the canonical pointer downstream plans (20-04 §3, 20-05 Task 5.0, 20-06 §4.2/§4.3) consume — every later plan that needs baseline numbers must cite this SUMMARY's outputs."
    - "**Pass-2 Concern #10:** Per-tier `metrics-{N}bots-baseline-c22e487.json` actuator-metric sidecars exist alongside the JFRs. They snapshot `paralife.tick.health.work-time-ms` + `paralife.outbound.detach.timeout` from `/actuator/metrics/{name}` (exposed via `application.yml:15`) every 5s for 30s during the steady-state portion of the load window. Plan 6 §4.2 reads from these sidecars."
  artifacts:
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.jfr"
      provides: "JFR profile of server under 100-bot LoadHarness load against c22e487 codebase."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.jfr"
      provides: "JFR profile of server under 500-bot LoadHarness load against c22e487."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr"
      provides: "JFR profile of server under 1000-bot LoadHarness load against c22e487."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html"
      provides: "async-profiler CPU flamegraph at 1000 bots."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html"
      provides: "async-profiler allocation flamegraph at 1000 bots."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html"
      provides: "async-profiler lock-contention flamegraph at 1000 bots."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json"
      provides: "Sibling metadata: captured_at_sha=c22e487, scenario, duration_s, harness_args, captured_utc, A1/A2/A6/A7/A8 verification notes."
    - path: ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json"
      provides: "Pass-2 Concern #10: actuator-metric sidecar — per-tier headline-gauge JSON snapshots (`paralife.tick.health.work-time-ms` + `paralife.outbound.detach.timeout`) sampled from `/actuator/metrics/{name}` during steady-state. Plan 6 §4.2 baseline column source."
  key_links:
    - from: "profiles/jfr-1000bots-baseline-c22e487.jfr"
      to: "20-RUNTIME.md §6 Profile Index (created in Plan 6) + Plan 5 Task 5.0 triage input"
      via: "filename citation"
      pattern: "jfr-1000bots-baseline-c22e487\\.jfr"
    - from: "profiles/jfr-1000bots-baseline-c22e487.meta.json (A1 outcome)"
      to: "Plan 2 JettyRuntimeConfig field-set decisions"
      via: "if A1 reveals a missing Jetty 12.0.18 setter, Plan 2 drops that field"
      pattern: "A1"
    - from: "profiles/metrics-1000bots-baseline-c22e487.json"
      to: "Plan 6 §4.2 headline-numbers table (1000-bot baseline column)"
      via: "JSON parse — `measurements[].value` per `statistic: VALUE`"
      pattern: "metrics-1000bots-baseline-c22e487\\.json"
---

<objective>
Capture the **c22e487 baseline JFRs + flamegraphs + actuator metric sidecars** that every later plan cites for before/after deltas. This plan is the gate: no other plan in waves 2/3/4 starts coding without these baseline artifacts in tree (with the explicit exception of Plan 2 + Plan 3, which only need Plan 20-01's toolchain to scaffold their `@ConfigurationProperties` records).

Purpose: D-04 / D-05 / D-06 / D-19 demand JFR + async-profiler artifacts committed under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`, baseline anchored to commit `c22e487`, with size discipline (≤10 MB per file, ≤50 MB total) so reviewers can git-checkout c22e487 and reproduce. **Pass-2 Concern #10:** Per-tier actuator-metric JSON sidecars are now part of the baseline capture so D-13/D-18 inheritance truths in Plan 6 are verifiable against actual measured baseline values (the previous plan grepped `/tmp/p20-tuned-server.log` for Micrometer meter values that aren't actually in the log — the actuator endpoint is the correct source).

Output: three baseline JFRs (100/500/1000 bots), three async-profiler flamegraphs at 1000 bots, sibling meta.json files, **three actuator-metric JSON sidecars (one per tier)**, plus explicit verification of assumptions A1, A2, A6, A7, A8 from RESEARCH.md.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/20-RESEARCH.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/20-01-SUMMARY.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md
@tools/async-profiler-bootstrap.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md
@CLAUDE.md
@build.gradle.kts
</context>

<tasks>

<task type="checkpoint:human-action" gate="blocking">
  <name>Task 1b.0: Capture baseline JFRs + flamegraphs + actuator metric sidecars at c22e487 (human-required: server runs locally, async-profiler attaches to PID, curl polls actuator endpoint)</name>
  <action>Capture three baseline JFRs (100/500/1000 bots), three flamegraph HTMLs (cpu/alloc/lock at 1000), and **three actuator-metric JSON sidecars (one per tier)** against the c22e487 server build; commit each artifact under .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ with the SHA-anchored filename convention from D-19; write sibling *.meta.json files capturing JVM flags + harness args + A1/A2/A6/A7/A8 verification outcomes.</action>
  <read_first>
    - tools/async-profiler-bootstrap.md (Plan 20-01 output)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md (Plan 20-01 output)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/20-RESEARCH.md (lines 361-428: capture commands; §Assumptions Log A1-A8)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/20-REVIEW-DISPOSITIONS.md (Pass-2 Concerns #10 + #15 — actuator metric capture + capture-script hazard fixes)
    - src/main/resources/application.yml (line 11-15 — actuator `metrics` endpoint exposure verified)
    - src/main/java/com/paralife/admission/AdmissionMetrics.java (lines 65, 74 — meter names `paralife.tick.health.work-time-ms` + `paralife.outbound.detach.timeout`)
  </read_first>
  <what-built>Plan 20-01 wrote the bootstrap docs. This task captures the baseline JFRs + flamegraphs + **actuator metric sidecars (Pass-2 Concern #10)** against the c22e487 codebase.</what-built>
  <how-to-verify>
**The capture is a multi-process ritual that needs a human:**

```bash
# 1. Pin to baseline SHA (D-19) — Pass-2 Concern #15: mkdir -p the profiles dir
#    because it doesn't exist on c22e487 (it was created by Plan 20-01 against HEAD).
git status   # MUST be clean — stash uncommitted work first
git checkout c22e487
mkdir -p .planning/phases/20-connection-multiplexing-runtime-tuning/profiles
./gradlew clean bootJar loadHarnessJar

# 2. Boot the server with continuous JFR + ZGC + tuned VT carrier count
#    Pass-2 Concern #15: pin SERVER_JAR to disambiguate from the loadHarness jar
SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | head -1)
HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1)
SERVER_LOG=/tmp/p20-baseline-server.log
JFR_OUT=.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr
java \
  -Xms2g -Xmx2g -XX:+UseG1GC \
  -XX:StartFlightRecording=duration=180s,filename="$JFR_OUT",settings=profile,name=p20-baseline-1000 \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -jar "$SERVER_JAR" \
  --paralife.simulation.spawn.seed=20251205 > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# Wait ~10s for boot; verify actuator endpoint reachable
sleep 10
jcmd $SERVER_PID VM.uptime  # confirm alive
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/metrics/paralife.tick.health.work-time-ms
# Expect: 200 — meter exists. If 404, AdmissionMetrics may not have registered yet OR the meter name changed.

# 3. Drive 1000-bot load (200s — Pass-2 Concern #15: extended from 180s to give
#    flamegraph captures a 20s margin within the active load window)
java -jar "$HARNESS_JAR" \
  --server-uri ws://localhost:8080/ws/world \
  --count 1000 --duration 200 --ramp-up rate:50 \
  --harness-id baseline-c22e487 &
HARNESS_PID=$!

# 4. While load is driving (after first 30s — steady-state), in another terminal capture flamegraphs:
#    Pass-2 Concern #15 alternative — use `&` + `wait` to run the three captures concurrently and
#    fit inside the 200s load window with margin. async-profiler supports concurrent attach.
ASYNC_PROFILER=tools/async-profiler/bin/asprof  # or external path
sleep 30   # let load reach steady state
$ASYNC_PROFILER -d 60 -e cpu   -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html   $SERVER_PID &
$ASYNC_PROFILER -d 60 -e alloc -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html $SERVER_PID &
$ASYNC_PROFILER -d 60 -e lock  -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html  $SERVER_PID &
wait   # all three concurrent; total ~60s wall, total elapsed ≤ 90s into the 200s window

# 4b. Pass-2 Concern #10: snapshot actuator metrics during the steady-state portion
#     of the load window. Sample every 5s for 30s, write the resulting JSON array
#     to the per-tier sidecar.
METRICS_OUT=.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json
echo '{"captured_at_sha":"c22e487","scenario":"1000bots","samples":[' > "$METRICS_OUT"
for i in 1 2 3 4 5 6; do
  TS=$(date -u -Iseconds)
  WORK_TIME_MS=$(curl -s http://localhost:8080/actuator/metrics/paralife.tick.health.work-time-ms)
  DETACH_TO=$(curl -s http://localhost:8080/actuator/metrics/paralife.outbound.detach.timeout)
  if [ $i -gt 1 ]; then echo "," >> "$METRICS_OUT"; fi
  echo "{\"sample_utc\":\"$TS\",\"work_time_ms\":$WORK_TIME_MS,\"detach_timeout\":$DETACH_TO}" >> "$METRICS_OUT"
  sleep 5
done
echo ']}' >> "$METRICS_OUT"
# Validate the JSON parsed cleanly (each curl returns a JSON object with `measurements` array)
jq . "$METRICS_OUT" > /dev/null && echo "OK: $METRICS_OUT parses as JSON"

# 5. Wait for harness completion + JFR auto-flush. Confirm:
wait $HARNESS_PID
ls -lh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr
jfr summary .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr | head -20

# 6. Repeat steps 2-5 with --count 100 (`--duration 80` so the 30s steady-state metric
#    sampling fits) and --count 500 (`--duration 100`). Adjust JFR `duration=` and
#    metric sample count proportionally. The 100-tier and 500-tier METRICS_OUT
#    paths are metrics-100bots-baseline-c22e487.json and metrics-500bots-baseline-c22e487.json.
#    For the 100/500 tiers flamegraph captures are NOT required — only the 1000 tier needs them
#    (per existing must_haves; flamegraph capture is at 1000-bot scale only).
# 7. Stop server, return to HEAD:
kill $SERVER_PID
git checkout -

# 8. Write sibling meta.json files:
cat > .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json <<EOF
{
  "captured_at_sha": "c22e487",
  "scenario": "1000bots",
  "duration_s": 200,
  "harness_args": "--count 1000 --duration 200 --ramp-up rate:50 --harness-id baseline-c22e487",
  "captured_utc": "$(date -u -Iseconds)",
  "jvm_flags": "-Xms2g -Xmx2g -XX:+UseG1GC -Djdk.virtualThreadScheduler.parallelism=8",
  "metric_sidecar": "metrics-1000bots-baseline-c22e487.json",
  "assumptions_verified": {
    "A1": "<verbatim setter availability check on Jetty 12.0.18 — see RESEARCH §Assumptions A1>",
    "A2": "async-profiler 4.x version: <output of asprof --version>",
    "A6": "LoadHarness sustained 1000 conns from single JVM: yes/no + final connect-rate",
    "A7": "JFR file size at 60s × 1000 bots: <bytes> (≤10 MB target)",
    "A8": "Generational ZGC default-on in Temurin 21.0.6: <output of java -XX:+PrintFlagsFinal -version | grep ZGenerational>"
  }
}
EOF

# Repeat for 100/500 meta.json (assumptions repeat verbatim; metric_sidecar field
# updated to the per-tier path).
```

**Verification before reply:**
- `ls -lh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` shows 3 baseline JFRs + 3 baseline flamegraph HTMLs + 3 meta.json + **3 metric sidecars (Pass-2 Concern #10)**
- Every JFR file size ≤10 MB; total ≤50 MB (metric sidecars are KB-class, well under cap)
- Each filename contains the substring `c22e487`
- Each metric sidecar parses as JSON (`jq .` succeeds)
- Three-gate stack runs green in-suite: `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` exits 0
  </how-to-verify>
  <resume-signal>Reply `baseline-captured` with file sizes confirmed under 10 MB each AND the three metric sidecars confirmed parseable by `jq .` AND `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` green. If A1 (Jetty 12.0.18 setter availability) reveals a missing setter, note it explicitly so Plan 2 drops that field. If the actuator endpoint returns 404 for either meter (work-time-ms / detach.timeout), name the failing meter so the orchestrator can decide whether to expand the actuator exposure list or accept a degraded sidecar.</resume-signal>
</task>

<task type="auto">
  <name>Task 1b.1: Verify baseline artifacts + write completion summary</name>
  <read_first>
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ (list)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json (assumptions log)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json (Pass-2 Concern #10 sidecar — sample-array shape)
  </read_first>
  <files>.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md</files>
  <action>
Programmatically verify Task 1b.0's outputs and write the plan summary:

1. List artifacts: `ls -lh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`
2. Confirm 3 JFRs (100/500/1000 baselines), 3 flamegraph HTMLs (cpu/alloc/lock at 1000), 3 meta.json, **3 metric sidecars (Pass-2 Concern #10)**. Each filename contains `c22e487`.
3. Verify total size ≤50 MB: `du -sh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`
4. Verify per-file size ≤10 MB: `find .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ -type f -size +10M` MUST be empty.
5. **Pass-2 Concern #10:** Verify each metric sidecar is valid JSON: `for f in .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-*-baseline-c22e487.json; do jq -e '.samples | length >= 3' "$f"; done` — each sidecar carries ≥3 samples (steady-state coverage).
6. Run sanity gate: `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest`
7. Write `20-01b-SUMMARY.md` capturing: artifacts list with sizes, A1-A8 verification outcomes (from meta.json), three-gate result, **per-tier baseline metric values extracted from sidecars (mean `paralife.tick.health.work-time-ms` + total `paralife.outbound.detach.timeout` count over the sample window)**, any deviations from RESEARCH assumptions (especially A1 — if a Jetty setter is unavailable on 12.0.18, name it explicitly so Plan 2 drops it from the JettyRuntimeConfig record).
  </action>
  <verify>
    <automated>ls .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.jfr .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.jfr .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-100bots-baseline-c22e487.json .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-500bots-baseline-c22e487.json .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-1000bots-baseline-c22e487.json && [ -z "$(find .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ -type f -size +10M)" ] && [ "$(du -sb .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ | cut -f1)" -le 52428800 ] && for f in .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/metrics-*-baseline-c22e487.json; do jq -e '.samples | length >= 3' "$f" > /dev/null || exit 1; done && ./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest</automated>
  </verify>
  <acceptance_criteria>
    - All 6 baseline files (3 JFRs + 3 flamegraph HTMLs) exist with `c22e487` in filename
    - **Pass-2 Concern #10:** All 3 metric sidecars (`metrics-{100,500,1000}bots-baseline-c22e487.json`) exist with `c22e487` in filename
    - Each metric sidecar parses as JSON with `samples` array length ≥ 3
    - `find ... -type f -size +10M` empty (D-05 ≤10 MB per file)
    - Total profiles/ size ≤50 MB (D-05 budget — metric sidecars are KB-class; well under)
    - Three-gate stack exits 0 in-suite
    - `20-01b-SUMMARY.md` exists, lists artifacts with sizes, records A1-A8 outcomes, **records per-tier baseline metric values extracted from sidecars (Pass-2 Concern #10)**
    - `grep -q "c22e487" .planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` (SHA cited)
    - `grep -qE "A1:|A2:|A6:|A7:|A8:" .planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` (assumptions documented)
    - `grep -qE "metrics-(100|500|1000)bots-baseline" .planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` (sidecar references in summary)
  </acceptance_criteria>
  <done>Baseline JFR + flamegraph + actuator-metric-sidecar artifacts in tree, all under size budget, sanity gate green, summary written, A1-A8 outcomes recorded, per-tier baseline metric values extracted so downstream plans (20-04, 20-05, 20-06) know which RESEARCH assumptions held AND have a JSON-parseable source for D-13/D-18 headline numbers.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| operator → JFR/flamegraph artifacts | committed binary files; no untrusted input |
| JFR/meta.json sidecar → reviewers | non-secret architectural evidence; no PII captured |
| operator → actuator metric sidecar | committed JSON files; gauge values + sample timestamps; no secrets (Pass-2 Concern #10) |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-20-V7 | I (Information disclosure) | meta.json + metric sidecars | mitigate | meta.json carries no secrets, only SHA + scenario + JVM flags + assumption notes; metric sidecars carry only Micrometer gauge values + UTC timestamps; reviewed before commit |
| T-20-DOS-1 | D (DoS) | profiles/ directory growth | mitigate | D-05 ≤10 MB/file ≤50 MB total enforced by Task 1b.1 acceptance criteria; metric sidecars are KB-class so do not threaten the budget |
</threat_model>

<verification>
- All baseline artifact paths committed and within size budget
- A1 (Jetty 12.0.18 setter availability) documented — Plan 2 reads from this summary to know which fields to drop if any setter is missing on the pinned Jetty version
- Three-gate stack green at c22e487 (sanity)
- Plan 4/5/6 are unblocked: every later plan can cite a real JFR/flamegraph + per-tier baseline metric sidecar for before/after deltas (D-13). Plans 2 + 3 only depend on 20-01 (toolchain) so they may have already started in parallel.
- Pass-2 Concern #10: actuator metric sidecars provide a JSON-parseable source for the Plan 6 §4.2 baseline column; replaces the previous broken plan to grep `/tmp/p20-tuned-server.log` for Micrometer meter values that aren't actually in the log
</verification>

<success_criteria>
- 3 JFRs + 3 flamegraph HTMLs + meta.json sidecars + **3 actuator-metric JSON sidecars (Pass-2 Concern #10)** under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`
- D-19 SHA-anchoring enforced (filenames contain `c22e487`)
- D-05 size discipline enforced (≤10 MB/file, ≤50 MB total)
- Three-gate stack green in-suite
- Assumptions A1-A8 from RESEARCH explicitly verified in meta.json + summary
- Pass-2 Concern #15 capture-script hardening present: `mkdir -p`, `SERVER_JAR`/`HARNESS_JAR` glob disambiguation, 200s harness `--duration` (or concurrent flamegraph capture via `&` + `wait`)
</success_criteria>

<output>
After completion, create `.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` with:
- Artifact inventory (filename, size, SHA segment)
- Three-gate result (commit SHA + timestamp)
- A1-A8 verification outcomes
- **Per-tier baseline metric snapshot summary (Pass-2 Concern #10)** — mean `paralife.tick.health.work-time-ms` + total `paralife.outbound.detach.timeout` count from each `metrics-{N}bots-baseline-c22e487.json` sidecar; Plan 6 §4.2 reads these values into the headline-numbers table baseline columns
- Any RESEARCH assumption that didn't hold (explicit list — Plan 2 reads this to adapt JettyRuntimeConfig fields; Plan 5 Task 5.0 reads this to triage codec hot paths)
- Pass-2 Concerns #10 + #15 confirmation: actuator sidecars exist + capture-script hazards mitigated (mkdir, jar globs, flamegraph timing margin)
</output>
