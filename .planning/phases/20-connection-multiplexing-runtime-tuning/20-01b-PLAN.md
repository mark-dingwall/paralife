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
autonomous: false
requirements: [SCALE-09]

must_haves:
  truths:
    - "JFR baseline files for 100 / 500 / 1000-bot runs exist under profiles/, each ≤10 MB."
    - "Three baseline async-profiler flamegraph HTMLs exist (cpu/alloc/lock at 1000 bots)."
    - "Every committed baseline JFR filename contains the literal substring `c22e487` (D-19)."
    - "Each JFR has a sibling *.meta.json carrying captured_at_sha=c22e487, scenario, duration, harness_args, captured_utc, JVM flags, and A1/A2/A6/A7/A8 verification outcomes."
    - "Three-gate stack (D-11) runs green in-suite immediately after baseline capture (sanity of clean checkout)."
    - "Assumptions A1, A2, A6, A7, A8 from 20-RESEARCH.md §Assumptions Log are explicitly checked and recorded in meta.json companions."
    - "20-01b-SUMMARY.md is the canonical pointer downstream plans (20-04 §3, 20-05 Task 5.0, 20-06 §4.2/§4.3) consume — every later plan that needs baseline numbers must cite this SUMMARY's outputs."
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
  key_links:
    - from: "profiles/jfr-1000bots-baseline-c22e487.jfr"
      to: "20-RUNTIME.md §6 Profile Index (created in Plan 6) + Plan 5 Task 5.0 triage input"
      via: "filename citation"
      pattern: "jfr-1000bots-baseline-c22e487\\.jfr"
    - from: "profiles/jfr-1000bots-baseline-c22e487.meta.json (A1 outcome)"
      to: "Plan 2 JettyRuntimeConfig field-set decisions"
      via: "if A1 reveals a missing Jetty 12.0.18 setter, Plan 2 drops that field"
      pattern: "A1"
---

<objective>
Capture the **c22e487 baseline JFRs + flamegraphs** that every later plan cites for before/after deltas. This plan is the gate: no other plan in waves 2/3/4 starts coding without these baseline artifacts in tree (with the explicit exception of Plan 2 + Plan 3, which only need Plan 20-01's toolchain to scaffold their `@ConfigurationProperties` records).

Purpose: D-04 / D-05 / D-06 / D-19 demand JFR + async-profiler artifacts committed under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`, baseline anchored to commit `c22e487`, with size discipline (≤10 MB per file, ≤50 MB total) so reviewers can git-checkout c22e487 and reproduce.

Output: three baseline JFRs (100/500/1000 bots), three async-profiler flamegraphs at 1000 bots, sibling meta.json files, plus explicit verification of assumptions A1, A2, A6, A7, A8 from RESEARCH.md.
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
@tools/async-profiler-bootstrap.md
@.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md
@CLAUDE.md
@build.gradle.kts
</context>

<tasks>

<task type="checkpoint:human-action" gate="blocking">
  <name>Task 1b.0: Capture baseline JFRs + flamegraphs at c22e487 (human-required: server runs locally, async-profiler attaches to PID)</name>
  <action>Capture three baseline JFRs (100/500/1000 bots) and three flamegraph HTMLs (cpu/alloc/lock at 1000) against the c22e487 server build; commit each artifact under .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ with the SHA-anchored filename convention from D-19; write sibling *.meta.json files capturing JVM flags + harness args + A1/A2/A6/A7/A8 verification outcomes.</action>
  <read_first>
    - tools/async-profiler-bootstrap.md (Plan 20-01 output)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md (Plan 20-01 output)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/20-RESEARCH.md (lines 361-428: capture commands; §Assumptions Log A1-A8)
  </read_first>
  <what-built>Plan 20-01 wrote the bootstrap docs. This task captures the baseline JFRs + flamegraphs against the c22e487 codebase.</what-built>
  <how-to-verify>
**The capture is a multi-process ritual that needs a human:**

```bash
# 1. Pin to baseline SHA (D-19)
git status   # MUST be clean — stash uncommitted work first
git checkout c22e487
./gradlew clean bootJar loadHarnessJar

# 2. Boot the server with continuous JFR + ZGC + tuned VT carrier count
SERVER_LOG=/tmp/p20-baseline-server.log
JFR_OUT=.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr
java \
  -Xms2g -Xmx2g -XX:+UseG1GC \
  -XX:StartFlightRecording=duration=180s,filename="$JFR_OUT",settings=profile,name=p20-baseline-1000 \
  -Djdk.virtualThreadScheduler.parallelism=8 \
  -jar build/libs/paralife-*.jar \
  --paralife.simulation.spawn.seed=20251205 > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# Wait ~10s for boot
sleep 10
jcmd $SERVER_PID VM.uptime  # confirm alive

# 3. Drive 1000-bot load (180s)
java -jar build/libs/paralife-*-load-harness.jar \
  --server-uri ws://localhost:8080/ws/world \
  --count 1000 --duration 180 --ramp-up rate:50 \
  --harness-id baseline-c22e487

# 4. While load is driving (after first 30s), in another terminal capture flamegraphs:
ASYNC_PROFILER=tools/async-profiler/bin/asprof  # or external path
$ASYNC_PROFILER -d 60 -e cpu   -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html   $SERVER_PID
$ASYNC_PROFILER -d 60 -e alloc -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html $SERVER_PID
$ASYNC_PROFILER -d 60 -e lock  -f .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html  $SERVER_PID

# 5. Wait for harness completion + JFR auto-flush. Confirm:
ls -lh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr
jfr summary .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr | head -20

# 6. Repeat steps 2-5 with --count 100 and --count 500 (shorter --duration 60 acceptable for 100/500).
# 7. Stop server, return to HEAD:
kill $SERVER_PID
git checkout -

# 8. Write sibling meta.json files:
cat > .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json <<EOF
{
  "captured_at_sha": "c22e487",
  "scenario": "1000bots",
  "duration_s": 180,
  "harness_args": "--count 1000 --duration 180 --ramp-up rate:50 --harness-id baseline-c22e487",
  "captured_utc": "$(date -u -Iseconds)",
  "jvm_flags": "-Xms2g -Xmx2g -XX:+UseG1GC -Djdk.virtualThreadScheduler.parallelism=8",
  "assumptions_verified": {
    "A1": "<verbatim setter availability check on Jetty 12.0.18 — see RESEARCH §Assumptions A1>",
    "A2": "async-profiler 4.x version: <output of asprof --version>",
    "A6": "LoadHarness sustained 1000 conns from single JVM: yes/no + final connect-rate",
    "A7": "JFR file size at 60s × 1000 bots: <bytes> (≤10 MB target)",
    "A8": "Generational ZGC default-on in Temurin 21.0.6: <output of java -XX:+PrintFlagsFinal -version | grep ZGenerational>"
  }
}
EOF

# Repeat for 100/500 meta.json (assumptions repeat verbatim).
```

**Verification before reply:**
- `ls -lh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/` shows 3 baseline JFRs + 3 baseline flamegraph HTMLs + 3 meta.json
- Every JFR file size ≤10 MB; total ≤50 MB
- Each filename contains the substring `c22e487`
- Three-gate stack runs green in-suite: `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` exits 0
  </how-to-verify>
  <resume-signal>Reply `baseline-captured` with file sizes confirmed under 10 MB each AND `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest` green. If A1 (Jetty 12.0.18 setter availability) reveals a missing setter, note it explicitly so Plan 2 drops that field.</resume-signal>
</task>

<task type="auto">
  <name>Task 1b.1: Verify baseline artifacts + write completion summary</name>
  <read_first>
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ (list)
    - .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.meta.json (assumptions log)
  </read_first>
  <files>.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md</files>
  <action>
Programmatically verify Task 1b.0's outputs and write the plan summary:

1. List artifacts: `ls -lh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`
2. Confirm 3 JFRs (100/500/1000 baselines), 3 flamegraph HTMLs (cpu/alloc/lock at 1000), 3 meta.json. Each filename contains `c22e487`.
3. Verify total size ≤50 MB: `du -sh .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`
4. Verify per-file size ≤10 MB: `find .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ -type f -size +10M` MUST be empty.
5. Run sanity gate: `./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest`
6. Write `20-01b-SUMMARY.md` capturing: artifacts list with sizes, A1-A8 verification outcomes (from meta.json), three-gate result, any deviations from RESEARCH assumptions (especially A1 — if a Jetty setter is unavailable on 12.0.18, name it explicitly so Plan 2 drops it from the JettyRuntimeConfig record).
  </action>
  <verify>
    <automated>ls .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-100bots-baseline-c22e487.jfr .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-500bots-baseline-c22e487.jfr .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/jfr-1000bots-baseline-c22e487.jfr .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/cpu-1000bots-baseline-c22e487.html .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/alloc-1000bots-baseline-c22e487.html .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/lock-1000bots-baseline-c22e487.html && [ -z "$(find .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ -type f -size +10M)" ] && [ "$(du -sb .planning/phases/20-connection-multiplexing-runtime-tuning/profiles/ | cut -f1)" -le 52428800 ] && ./gradlew test --tests GoldenTraceEquivalenceTest --tests GoldenTraceWithActionsTest --tests LiveEntityRegistryInvariantTest</automated>
  </verify>
  <acceptance_criteria>
    - All 6 baseline files (3 JFRs + 3 flamegraph HTMLs) exist with `c22e487` in filename
    - `find ... -type f -size +10M` empty (D-05 ≤10 MB per file)
    - Total profiles/ size ≤50 MB (D-05 budget)
    - Three-gate stack exits 0 in-suite
    - `20-01b-SUMMARY.md` exists, lists artifacts with sizes, records A1-A8 outcomes
    - `grep -q "c22e487" .planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` (SHA cited)
    - `grep -qE "A1:|A2:|A6:|A7:|A8:" .planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` (assumptions documented)
  </acceptance_criteria>
  <done>Baseline JFR + flamegraph artifacts in tree, all under size budget, sanity gate green, summary written, A1-A8 outcomes recorded so downstream plans (20-04, 20-05, 20-06) know which RESEARCH assumptions held.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| operator → JFR/flamegraph artifacts | committed binary files; no untrusted input |
| JFR/meta.json sidecar → reviewers | non-secret architectural evidence; no PII captured |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-20-V7 | I (Information disclosure) | meta.json sidecar | mitigate | meta.json carries no secrets, only SHA + scenario + JVM flags + assumption notes; reviewed before commit |
| T-20-DOS-1 | D (DoS) | profiles/ directory growth | mitigate | D-05 ≤10 MB/file ≤50 MB total enforced by Task 1b.1 acceptance criteria |
</threat_model>

<verification>
- All baseline artifact paths committed and within size budget
- A1 (Jetty 12.0.18 setter availability) documented — Plan 2 reads from this summary to know which fields to drop if any setter is missing on the pinned Jetty version
- Three-gate stack green at c22e487 (sanity)
- Plan 4/5/6 are unblocked: every later plan can cite a real JFR/flamegraph for before/after deltas (D-13). Plans 2 + 3 only depend on 20-01 (toolchain) so they may have already started in parallel.
</verification>

<success_criteria>
- 3 JFRs + 3 flamegraph HTMLs + meta.json sidecars under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`
- D-19 SHA-anchoring enforced (filenames contain `c22e487`)
- D-05 size discipline enforced (≤10 MB/file, ≤50 MB total)
- Three-gate stack green in-suite
- Assumptions A1-A8 from RESEARCH explicitly verified in meta.json + summary
</success_criteria>

<output>
After completion, create `.planning/phases/20-connection-multiplexing-runtime-tuning/20-01b-SUMMARY.md` with:
- Artifact inventory (filename, size, SHA segment)
- Three-gate result (commit SHA + timestamp)
- A1-A8 verification outcomes
- Any RESEARCH assumption that didn't hold (explicit list — Plan 2 reads this to adapt JettyRuntimeConfig fields; Plan 5 Task 5.0 reads this to triage codec hot paths)
</output>
