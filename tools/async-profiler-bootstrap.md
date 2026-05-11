# async-profiler bootstrap (Phase 20 D-04 / D-05)

This doc is the install + capture ritual for [async-profiler](https://github.com/async-profiler/async-profiler) 4.x, the native-attribution profiler used by Phase 20 alongside JFR. See [`.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md`](../.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md) for filename convention, the c22e487 baseline ritual, and size discipline.

## Why

JFR (Java Flight Recorder, JDK-bundled) gives a strong allocation / pinning / lock / GC view of the JVM, but it cannot resolve symbols below the JVM/native boundary (kernel syscalls, JIT helper stubs, libc, native libraries). async-profiler attaches to a running `ParalifeApplication` PID and captures simultaneous CPU / alloc / lock / wall-clock flamegraphs that DO see through that boundary — needed for Phase 20 D-04 / D-05 to ground the per-tier JVM-flag presets in evidence rather than folklore.

Per Phase 20 D-19 every captured artifact embeds the source commit SHA in its filename (`c22e487` for baseline, `git rev-parse --short HEAD` for tuned). Per D-05 captures are size-disciplined (≤10 MB per file, ≤50 MB phase-total) so the `.planning/.../profiles/` directory stays mergeable.

## Install

Three install paths are supported. RESEARCH §Open Question 4 (RESOLVED) recommends the external path — keeps the repo small and async-profiler ships frequently. The Paralife project's chosen path is **external** (this doc was bootstrapped against an external install at `~/tools/async-profiler/bin/asprof`); the other two are documented so contributors on different setups can replicate.

### External (~/tools/async-profiler/) — recommended

```bash
mkdir -p ~/tools && cd ~/tools
ARCH=linux-x64  # or macos for macOS hosts
LATEST_TAG=$(curl -sIL https://github.com/async-profiler/async-profiler/releases/latest \
  | awk -F/ 'tolower($1) ~ /^location:/ { gsub(/\r/, "", $NF); print $NF }')
curl -sL -o async-profiler.tar.gz \
  "https://github.com/async-profiler/async-profiler/releases/download/${LATEST_TAG}/async-profiler-${LATEST_TAG#v}-${ARCH}.tar.gz"
tar -xzf async-profiler.tar.gz
mv "async-profiler-${LATEST_TAG#v}-${ARCH}" async-profiler
rm async-profiler.tar.gz
~/tools/async-profiler/bin/asprof --version    # expect "Async-profiler 4.x built on ..."
```

Set `ASYNC_PROFILER=~/tools/async-profiler/bin/asprof` in your shell so the capture blocks below resolve.

### In-tree (tools/async-profiler/)

Same `curl` + `tar` recipe, target `tools/async-profiler/` in the repo root. If this is the chosen path, also add `tools/async-profiler/` to `.gitignore` — the binary is ~10 MB and platform-specific; the docs in this file are reproducible, the binary is not. `asprof --version` MUST still return a 4.x version.

### ap-loader (alternative — hermetic JAR)

```bash
curl -sL -o tools/ap-loader.jar \
  https://github.com/jvm-profiling-tools/ap-loader/releases/latest/download/ap-loader-all.jar
java -jar tools/ap-loader.jar version    # expect 4.x
```

Use `java -jar tools/ap-loader.jar <asprof-args>` wherever the recipes below say `$ASYNC_PROFILER`. ap-loader unpacks the right native lib for the platform on first run, so a single committed JAR works across linux/macOS/x64/arm64. Trade-off: an extra JVM-launch wrapping around every profile run.

## Capture (CPU / alloc / lock at 1000 bots)

Run against a server already started at the SHA being profiled (see `profiles/README.md` for the baseline-vs-tuned ritual that pins HEAD to `c22e487` for baseline captures). All three captures can run concurrently — they do not contend on event channels at this scale [github.com/async-profiler/async-profiler/issues/436].

```bash
ASYNC_PROFILER=~/tools/async-profiler/bin/asprof
SERVER_PID=$(jps -l | grep ParalifeApplication | awk '{print $1}')
HEAD_SHA=$(git rev-parse --short HEAD)
OUT_DIR=".planning/phases/20-connection-multiplexing-runtime-tuning/profiles"

# CPU flamegraph (60s window — Phase 20 D-05)
$ASYNC_PROFILER -d 60 -e cpu   -f "$OUT_DIR/cpu-1000bots-tuned-${HEAD_SHA}.html"   "$SERVER_PID"

# Allocation flamegraph
$ASYNC_PROFILER -d 60 -e alloc -f "$OUT_DIR/alloc-1000bots-tuned-${HEAD_SHA}.html" "$SERVER_PID"

# Lock contention flamegraph
$ASYNC_PROFILER -d 60 -e lock  -f "$OUT_DIR/lock-1000bots-tuned-${HEAD_SHA}.html"  "$SERVER_PID"
```

For the baseline capture, swap `${HEAD_SHA}` for the literal `c22e487` per D-19 — and ensure the server JAR being profiled was built from that SHA (`./gradlew clean loadHarnessJar bootJar` after `git checkout c22e487`).

`jdk.VirtualThreadPinned` is captured by JFR, not by asprof — see `profiles/README.md` for the JFR command that runs alongside these flamegraphs.

## File-size discipline

Phase 20 D-05 bound: **≤10 MB per file, ≤50 MB phase-total**. JFR `settings=profile` at 180s/1000 bots produces 50-200 MB raw — too large to commit. Two options:

```bash
jfr summary "$OUT_DIR/jfr-1000bots-tuned-${HEAD_SHA}.jfr" | head
# If file is over 10 MB, filter to events of interest:
jfr filter \
  --include-events 'jdk.VirtualThreadPinned,jdk.GCPhasePause,jdk.ObjectAllocationSample,jdk.JavaMonitorEnter' \
  "$OUT_DIR/jfr-1000bots-tuned-${HEAD_SHA}.jfr" \
  "$OUT_DIR/jfr-1000bots-tuned-${HEAD_SHA}.filtered.jfr"
```

async-profiler flamegraph HTML is well under 10 MB at 60s capture in practice.

## meta.json sidecar

Every JFR file gets a sibling `<jfr-name>.meta.json` written by the operator script, so the capture context is reproducible without re-running the harness:

```json
{
  "captured_at_sha": "c22e487",
  "scenario": "1000bots",
  "duration_s": 180,
  "harness_args": "--count 1000 --duration 180 --ramp-up rate:50 --harness-id baseline-c22e487",
  "captured_utc": "2026-05-11T12:00:00Z",
  "assumptions_verified": {
    "A1": "tick rate 5 Hz held throughout",
    "A2": "100/500/1000 bot tiers each captured separately",
    "A6": "G1 default (baseline), no -XX:+UseZGC override",
    "A7": "JFR ran concurrently with asprof, no event-channel collision",
    "A8": "GoldenTraceEquivalenceTest green in-suite at capture SHA"
  }
}
```

## Where artifacts land

Per D-19, every committed artifact uses the convention `<event>-<scenario>-<state>-<sha>.<ext>` under `.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/`. See `profiles/README.md` for the table of patterns and the c22e487 baseline ritual.
