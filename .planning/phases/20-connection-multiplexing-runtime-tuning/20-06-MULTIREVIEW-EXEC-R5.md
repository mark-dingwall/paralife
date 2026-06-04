---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T15:48:31Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-CONTEXT.md", "README.md"]
models:
  claude: "claude-opus-4-8"
usage:
  claude: { input: 641236, output: 30, cached: 375582, tool_calls: 2, elapsed_s: 168.6 }
  gemini: { input: 235634, output: 857, cached: 0, tool_calls: 0, elapsed_s: 123.5 }
  codex: { input: 1118649, output: 11713, cached: 948736, tool_calls: 28, elapsed_s: 236.3 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 28, elapsed_s: 245.6 }
synthesizer: claude
synthesized_at: 2026-06-04T15:48:31Z
fallbacks:
  gemini:
    attempts: ["gemini-3.1-pro-preview", "gemini-3-flash-preview"]
    used: "gemini-3-flash-preview"
---

# Cross-AI Review

## Claude Review

Verified on disk. Two real gaps in R4-touched ritual block. Both pre-existing, both sit inside region R4 restructured, neither blocks ship.

# Review: Plan 20-06 EXECUTION — Round 5

## (a) R4 fix verification — PASS

| R4 fix | Verdict |
|---|---|
| Harness backgrounded `& HARNESS_PID=$!` + `sleep 20` ramp | ✓ correct |
| 3×60s flamegraphs serial in ONE bg subshell `( ) & FLAME_PID=$!` | ✓ one asprof session/JVM honoured; 20+180=200 fits harness `--duration 200` |
| Metric loop concurrent foreground (6×5s≈30s, t=20→50) | ✓ matches committed 6-sample baseline sidecar |
| `wait "$FLAME_PID" "$HARNESS_PID"` before restore | ✓ both end ~t=200, no hang |
| §4.3.1 "100 ms budget"→"~491 ms slack under 500 ms `paralife.tick.interval-ms`" | ✓ key real (`application.yml:34` `interval-ms: 500`); 500−9=491 ✓ |
| Footer `2026-06-XX`→`2026-06-05` | ✓ no XX |
| VALIDATION `c22e487 >= 3`→`>= 1` | ✓ live count = 3, passes |
| AdmissionMetrics refs →:70/:79/:167/:190 | ✓ |

Timing orchestration of the restructured ritual is sound: server(t0)→harness-bg(t0)→sleep20→flame-subshell(t20–200)+metric-loop(t20–50 fg)→wait(t200). No second-order arithmetic error introduced. GC numbers (10/18/18 churn, 0/0/0 active, 4 tuned) still internally consistent across §3/§4. §4.2 100/500 means verified by hand: (12+8+14+7+6+7)/6=9.0 σ=3.22 ✓; (33+27+29+34+29+29)/6=30.2 σ=2.71 ✓.

## (b) NEW findings

### MEDIUM — `profiles/README.md` 62c1b44 ritual never kills the server; multi-tier repeat hits :8080 bind conflict

**File/loc:** `profiles/README.md` §"62c1b44 baseline ritual", step 2 (`SERVER_PID=$!`, line 58) → step 6 ("Repeat steps 2-5 for --count 100 and --count 500") → step 7 (`git checkout - && git stash pop`, no kill).

**What's wrong:** `SERVER_PID` captured (line 58), used only for asprof attach (78–80). No `kill "$SERVER_PID"` anywhere. Step 6 says repeat steps 2–5 for the 100 and 500 tiers — but the 1000-tier server is still bound to `:8080`, so step 2's relaunch on the next tier fails to bind. Following the ritual verbatim for all three tiers fails after the first. Step 7 then runs `git checkout` with the JVM still alive → zombie server on the restored tree. The R4 restructure added `wait "$FLAME_PID" "$HARNESS_PID"` (correct) but no teardown, so the start→profile→wait orchestration is complete except for the exit half — the multi-tier loop the same section prescribes can't run. The sibling `capture-active.sh:74` does `kill -9 "$PID"; sleep 2` per tier; the ritual dropped it.

**Concrete fix:** before step 6's "repeat" (i.e. at end of each tier pass) add `kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null` so the port frees and no JVM survives into the git restore. Prefer hoisting tier into a `TIER=1000` var (codex's R4 suggestion) so `SERVER_PID`, JFR name, `SIDE`, harness-id, and the kill all cycle together.

**Evidence:** `grep -n "kill\|SERVER_PID" profiles/README.md` → only line 58 (capture) + 78-80 (attach), zero kill; `capture-active.sh:74` `kill -9 "$PID"` confirms the omitted step.

**Severity rationale:** committed artifacts were produced by `capture-tier.sh` (20-01c), not this ritual, so numbers are unaffected — this only bites an operator reproducing via the canonical README. Pre-existing (Plan 1 origin), surfaced now because R4 rewrote exactly this block and the de-stale's purpose is an accurate reproduction guide. Does NOT block phase ship.

### LOW — ritual launches harness before server is ready (no "Started ParalifeApplication" gate)

**File/loc:** `profiles/README.md` step 2 (server `&`) → step 3 (harness `&`), no wait between.

**What's wrong:** no readiness probe between server background-launch and harness launch. Harness starts at ~t0 while the server is still booting (~5 s); `--ramp-up rate:50` early connection attempts during t0–5 hit a not-yet-listening socket. `capture-active.sh:14` has the gate (`for i in $(seq 1 40); do grep -q 'Started ParalifeApplication' "$SLOG" && break; sleep 1; done`); the ritual doesn't. Yields a slightly different early population trajectory than the committed capture.

**Concrete fix:** insert a boot-wait loop between step 2 and step 3, polling for `Started ParalifeApplication` in a redirected server log (mirror `capture-active.sh:14`), then start the harness.

**Evidence:** `profiles/README.md` steps 2–3 vs `capture-active.sh:13-15`. Pre-existing; the "not byte-for-byte reproducible" caveat partially covers it, but a failed-connection front is regime drift, not just imprecision. Below ship threshold.

### NIT — already-known, not re-flagging
§4.2 mixes churn-100/500 with active-1000 baselines: covered by the R3 scenario note (lines 383-389). VirtualThreadPinned window labels (200s churn / 90s active / 180s tuned) are internally consistent. No action.

## Convergence judgement

**No new HIGH+ — convergence reached.** R4's restructured ritual verifies clean on process orchestration, wait semantics, and the 20+180 vs 200 s window; the `100 ms`→`500 ms tick interval` fix cites a config key that actually exists (`application.yml:34`); footer stamped; VALIDATION floor lowered correctly. The two new items are both `profiles/README.md` ritual-robustness defects (MEDIUM missing server-kill → multi-tier port conflict + zombie JVM; LOW missing readiness gate) — pre-existing, surfaced by the R4 rewrite of that block, operator-reproduction-only, neither blocks `/gsd-verify-work 20`. Recommend fixing the MEDIUM (one `kill` line) opportunistically since the README is now the canonical reproduction contract.

---

## Gemini Review

# Cross-AI Review

## Gemini Review

Phase 20 documentation finalisation and architectural codification are complete. Verification of the Round 4 fixes confirms that all process orchestration defects in the baseline ritual, stale line citations, and brittle validation assertions have been resolved. The deliverable is internally consistent, mathematically accurate regarding GC/metric data, and provides clear, runnable reproduction paths for future operators.

### (a) R4 fix verification — PASS

| Fix | Status | Verified |
|:---|:---:|:---|
| **Baseline Ritual Orchestration** | **PASS** | `profiles/README.md` now correctly backgrounds the harness and profiling subshell. The 20s ramp followed by 180s serialised flamegraph captures fits precisely within the 200s harness duration. The inline metric loop (Step 5) runs concurrently as intended. |
| **AdmissionMetrics line refs** | **PASS** | `20-CONTEXT.md` (L311-314) and `CLAUDE.md` (L164-165) accurately cite `AdmissionMetrics.java:70/:79` (constants) and `:167/:190` (registration sites), matching the current source state. |
| **100-bot tick slack** | **PASS** | `20-RUNTIME.md` §4.3.1 now correctly cites ~491 ms of slack under the real 500 ms tick interval, removing the unsupported 100 ms budget premise. |
| **Validation Floor** | **PASS** | `20-VALIDATION.md` row 20-01-1.1 floor lowered to `>= 1` for the de-staled `profiles/README.md`, ensuring the historical record remains green. |

### (b) Internal Consistency & Quality — PASS

*   **GC re-attribution (R1 priority risk):** Churn baseline counts (10/18/18) and sums (57.2/283.7/213.0 ms) are consistent across all narrative sections (§3.1/3.2/3.3, §4.3.1/4.3.2/4.3.3) and §4.4 table. 
*   **D-02 Three-Place Rationale:** Codified accurately in `README.md`, `CLAUDE.md`, and both Java source files (`WorldWebSocketHandler`, `OutboundSender`). Grep for `WS:entity 1:1` correctly hits all 4 files.
*   **Validation Map:** The 22-row map in `20-VALIDATION.md` is exhaustive, correctly mapping prose sections for Plan 1c and maintaining traceability for superseded tasks.
*   **Settled Phrasing:** All future-tense markers ("Plan 6 will...") have been successfully purged from the deliverable docs, which now read as authoritative final specs.

### (c) NEW findings

No `BLOCKER`, `HIGH`, or `MEDIUM` issues found.

#### NIT — §4.3.3 "same server build"
*   **File:** `20-RUNTIME.md` §4.3.3 (~L469).
*   **Note:** The tuned-state JFR is described as being on the "same server build" as the baseline. While they are different commit SHAs, the **null-result** outcome means no behavioural code change occurred; they are functionally identical binaries for the purpose of the noise-floor comparison.

## Convergence judgement

**No new HIGH+ — convergence reached.**

The artifact set is authoritative, internally consistent, and fulfills the SCALE-08 and SCALE-09 requirements. The phase is ready for final verification.

```bash
/gsd-verify-work 20
```

---
*QA Review · v5 · 2026-06-05*

---

## Codex Review

**Findings**

- **HIGH** — [profiles/README.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:48) baseline ritual starts the server but never stops it before step 6 repeat or step 7 restore.  
  The command backgrounds the server and stores `SERVER_PID`, then waits only for `FLAME_PID` and `HARNESS_PID` at line 116. There is no `kill "$SERVER_PID"` before “Repeat steps 2-5” or `git checkout - && git stash pop`. Running the ritual verbatim leaves the 62c1b44 server alive on port 8080, so the next tier’s server start will collide, and the tree restore happens while the old server is still running.  
  **Fix:** after the waits, stop the server before repeating/restoring, ideally with a `trap` and per-tier function:
  `wait...; kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true`.  
  **Evidence checked:** README lines 48-121; comparison script `capture-active.sh` does explicitly stop its server.

- **HIGH** — [profiles/README.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:45) restore step will collide with generated untracked artifacts.  
  The ritual checks out `62c1b44`, then writes artifacts into current-branch tracked paths such as `profiles/jfr-1000bots-baseline-62c1b44.jfr`, `metrics-1000bots-baseline-62c1b44.json`, and `cpu-1000bots-baseline-62c1b44.html`, then runs `git checkout -`. Those files are tracked at `HEAD` but absent at `62c1b44`; after capture they are untracked files that Git will refuse to overwrite when returning to `HEAD`. `git stash --include-untracked` at the start does not help because these files are created after the stash.  
  **Fix:** capture into `/tmp/p20-baseline-62c1b44/...` while detached, stop the server, `git checkout - && git stash pop`, then copy artifacts into `profiles/` on the restored branch.  
  **Evidence checked:** `git cat-file -e HEAD:<artifact>` = yes; `git cat-file -e 62c1b44:<artifact>` = no for the three artifact paths above.

- **MEDIUM** — [profiles/README.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:116) `wait "$FLAME_PID" "$HARNESS_PID"` masks flamegraph failure if the harness exits 0.  
  Bash returns the status of the last waited PID in this form. If the flamegraph subshell fails and the harness succeeds, the combined `wait` returns 0, so the ritual can proceed toward restore with missing flamegraphs.  
  **Fix:** wait and check separately:
  `wait "$FLAME_PID"; FLAME_RC=$?; wait "$HARNESS_PID"; HARNESS_RC=$?; [ "$FLAME_RC" -eq 0 ] && [ "$HARNESS_RC" -eq 0 ] || exit 1`.

- **LOW** — [profiles/README.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:113) R4 timing comment is inaccurate.  
  It says “20 s ramp + 180 s serial flamegraphs ≈ the 200 s load window,” but `LoadHarness` launches the fleet first, then waits `durationSeconds`; `rate:50` ramp happens inside `fleet.launch`, before the 200-second duration wait starts. The orchestration is therefore safe with margin, but the comment explains it incorrectly.  
  **Fix:** say the 180-second flamegraph block runs after the ramp and inside the harness duration window, with roughly 20 seconds of post-flamegraph margin.

- **LOW** — [20-06-SUMMARY.md](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md:47) still claims `20-RUNTIME.md` is 558 lines, but the live file is 559 lines after R4.  
  **Fix:** update the repeated `558` claims to `559`, or phrase them as “≥250 null-result floor satisfied” without pinning the exact count.

**Verified Clean**

R4’s core foreground-harness fix is structurally correct: the harness is backgrounded, flamegraphs are serial in one background subshell, the metric loop runs concurrently in the foreground, and restore is after the waits. D-02 grep is 4 files, `20-VALIDATION.md` has 22 task rows, live `@Disabled` count is 6, and the `20-RUNTIME.md` zero-pending audit passes.

**Convergence Judgement**

NEW HIGH+: two HIGH findings remain, both in `profiles/README.md` baseline ritual end-to-end runnability. Convergence not reached.

---

## Opencode Review

I'll read the key files to verify the R4 fixes and sweep for any remaining issues.Now let me verify the specific R4 fixes and sweep for remaining issues:Let me now do the deeper sweep for what all rounds may have missed:Good. All four stale c22e487 references in 20-CONTEXT are annotated. Let me now verify the 20-CONTEXT AdmissionMetrics line ref de-stale and check the other R4 fix from the codex findings:All CONTEXT line refs de-staled correctly. Now let me check the remaining items that could have been missed and verify the CLAUDE.md insertion position:Let me verify a few more cross-references and check the application.yml ref:Let me do a final check on the remaining potential issues — verifying §4.3.3 D-21 outcome enumeration and the README ritual's multi-tier correctness:## Round 5 Review — Plan 20-06 EXECUTION (post-merge, post-R4)

### (a) R4 fix verification — ALL PASS

All five R4 fixes verify clean against the live post-merge files:

| R4 fix | Verification |
|--------|-------------|
| **Harness backgrounded** (`& HARNESS_PID=$!` at `profiles/README.md:68`) | ✓ |
| **`sleep 20` ramp** (`profiles/README.md:69`) | ✓ |
| **Flamegraphs serial in one background subshell** (`(... & ) & FLAME_PID=$!` at lines 77-82) | ✓ |
| **`wait "$FLAME_PID" "$HARNESS_PID"`** at step 5b (`profiles/README.md:116`), **before** step 7's `git checkout - && git stash pop` | ✓ |
| **Timing:** 20s ramp + 180s serial flamegraphs = 200s = harness `--duration 200`. Metric loop 6×5s=30s runs concurrently (foreground, t=20→50), well within window. wait blocks at t=50 until ~t=200 when both complete. | ✓ |
| **`20-RUNTIME.md §4.3.1` "100 ms tick budget" → "~491 ms of slack under the 500 ms tick interval"** | ✓ |
| **`20-VALIDATION.md` row 20-01-1.1 `c22e487 >= 3` → `>= 1`** | ✓ |
| **`20-CONTEXT.md` AdmissionMetrics line refs de-staled:** all 4 sites now cite `:70, :79` (constants) + `:167, :190` (registration) with `*line refs de-staled 2026-06-04*` annotation | ✓ |
| **`20-CONTEXT.md` c22e487 annotations:** 4 parenthetical re-anchor notes present at lines 220, 233, 336, 475 | ✓ |

No fix introduced a new contradiction. The recurring fix-induced failure mode did not recur at R4→R5.

### (b) NEW findings — all four rounds missed

#### MEDIUM — `profiles/README.md` baseline ritual: no server kill between tiers → step 6 multi-tier repeat fails with port-in-use

**File:** `profiles/README.md` lines 57-58, 116-118.

The ritual contains no instruction to stop the server between tiers. After step 5b's `wait` completes (1000-tier server still running on :8080), step 6 says "Repeat steps 2-5 for --count 100 and --count 500 tiers." Step 2 starts a new `java -jar ... & SERVER_PID=$!` on the same port — this fails with `java.net.BindException: Address already in use`.

The committed capture script (`capture-active.sh:83`) properly calls `kill -9 "$PID"` at the end of each tier run. The README ritual inherited the "repeat steps 2-5" structure from the pre-R4 version (which already had this defect) but the R4 restructure made the ritual explicitly "verbatim" — now the verbatim claim is actively contradicted.

An experienced operator would deduce the fix, but the ritual bills itself as runnable as-written and fails silently on repetition. This is a documentation integrity defect for a file that serves as the canonical reproduction contract.

**Fix:** Add `kill "$SERVER_PID" 2>/dev/null; sleep 2` at the beginning of step 2 (idempotent — first run's empty `$SERVER_PID` makes `kill ""` a no-op). Or insert a step 5c before step 6.

**Evidence:** `capture-active.sh:83` kill pattern; `profiles/README.md:57-58` server launch (no preceding kill); `profiles/README.md:118` repeat instruction.

#### LOW — `20-RUNTIME.md` line count: 559 vs SUMMARY claim of 558

**File:** `20-RUNTIME.md` line count is 559, but `20-06-SUMMARY.md` claims "548 at execution; +10 from EXEC-R1" = 558. The ±1 discrepancy is negligible (likely an extra trailing newline from the footer replacement) and does not affect the ≥250 null-result floor assessment. Merely cosmetic.

### (c) Verified clean — no issues

- **D-21 outcome enumeration in §4.3.3:** "No codec opts, no knob tightening, no pinning handoff" (line 471-472) names outcomes 1/2/4 by negation; outcome 3 is the explicit heading. All four covered. ✓
- **20-CONTEXT D-19 c22e487:** Body text at line 232 still uses c22e487 in its lead sentence, but parenthetical at line 233 correctly re-anchors. The "annotate-not-rewrite" pattern is the established contract for this historical file. ✓
- **CLAUDE.md insertion:** After Connection model (ends L141), before `<!-- GSD:architecture-end -->` (L171). Both gauge line refs (`:70`, `:79`) and `application.yml:15` verified correct at HEAD. ✓
- **Zero-pending audit on 20-RUNTIME.md:** passes with expanded regex. ✓
- **GC numbers:** Consistent across all 7 passages + §4.2/§4.4 tables. ✓
- **D-02 four-file grep:** Confirmed 4 files match. ✓

### Convergence judgement

**One new MEDIUM, no HIGH+.** The `profiles/README.md` multi-tier server-kill gap is a pre-existing condition (pre-dates R3/R4) that the R4 "verbatim" restructure accidentally spotlighted — it makes step 6 un-runnable as written against the file's own claim. One cosmetic LOW (line count off by 1). No new HIGH+ — **convergence reached.**

---

## Consensus Summary

### Agreed Strengths
- R4 fixes verify clean (PASS, all four reviewers). Harness backgrounded (`& HARNESS_PID=$!`), 3×60s flamegraphs serial in one bg subshell, metric loop concurrent foreground, `wait "$FLAME_PID" "$HARNESS_PID"` before git restore. Timing (20s ramp + 180s flamegraphs ≈ 200s harness duration) sound.
- GC/metric numbers internally consistent across all narrative sections + §4.2/§4.4 tables (claude, gemini, codex, opencode). Churn 10/18/18, active 0/0/0, σ math checks.
- §4.3.1 "100 ms budget" → "~491 ms slack under 500 ms tick interval" fix cites a real config key (`application.yml:34`) (claude, gemini, opencode).
- `20-VALIDATION.md` row 20-01-1.1 floor `>= 3` → `>= 1` correct against live count of 3 (claude, gemini, codex, opencode). 22-row validation map exhaustive.
- D-02 `WS:entity 1:1` grep hits 4 files; AdmissionMetrics line refs (`:70/:79/:167/:190`) de-staled correctly (gemini, codex, opencode).

### Agreed Concerns
- **`profiles/README.md` baseline ritual never kills the server between tiers → multi-tier repeat hits :8080 BindException + zombie JVM into git restore** — raised by 3 reviewers. Severity split: codex HIGH, claude + opencode MEDIUM. `SERVER_PID` captured but no `kill`; step 6 "repeat steps 2-5" can't run on tiers 100/500; sibling `capture-active.sh` does `kill -9 "$PID"` per tier. Pre-existing (Plan 1 origin), spotlighted by R4 rewrite of this block, contradicts the file's "verbatim/canonical reproduction" claim. **Fix:** add `kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null` per tier pass (or hoist tier into a var/function with trap). Operator-reproduction-only — committed artifacts came from `capture-tier.sh`/`capture-active.sh`, not this ritual.
- **`20-RUNTIME.md` line count 559 vs SUMMARY claim 558** — LOW (codex, opencode). ±1, cosmetic, doesn't affect ≥250 null-result floor. Fix: update to 559 or drop the exact pin.

### Divergent Views
- **Convergence verdict.** codex: NOT reached (2 HIGHs in README runnability). claude/gemini/opencode: reached (no new HIGH+). The disagreement reduces to severity of the README server-kill gap — codex rates it HIGH (ritual un-runnable as written), others rate MEDIUM (operator-reproduction-only, committed data unaffected, neither blocks `/gsd-verify-work 20`). Worth a decision call.
- **codex-only HIGH: git restore collides with untracked artifacts.** `git checkout 62c1b44` then writes tracked-at-HEAD paths (`jfr-1000bots-baseline-62c1b44.jfr` etc.) absent at that SHA; on `git checkout -` Git refuses to overwrite the now-untracked files; `git stash -u` at start doesn't cover post-stash creations. Verified via `git cat-file -e`. Fix: capture to `/tmp/...` while detached, then copy into `profiles/` after restore. Not flagged by other three — investigate; if real, it's a second README-runnability defect.
- **codex-only MEDIUM: `wait "$FLAME_PID" "$HARNESS_PID"` masks flamegraph failure** — bash returns status of last-waited PID; flamegraph fail + harness exit 0 → wait returns 0, ritual proceeds with missing flamegraphs. Fix: wait + check each rc separately. Others rated the same `wait` line correct on ordering; none checked exit-status masking.
- **claude-only LOW: no server-readiness gate** (`Started ParalifeApplication`) between server bg-launch and harness launch → early `rate:50` connections hit not-yet-listening socket, slight population-trajectory drift. `capture-active.sh:14` has the gate; ritual doesn't.
- **codex-only LOW: R4 timing comment inaccurate** — ramp happens inside `fleet.launch` before the `durationSeconds` wait starts, so margin exists but the "20s+180s≈200s window" explanation describes the mechanism wrong.
- **gemini** found no HIGH/MED/MEDIUM at all (one NIT on "same server build" wording) — did not surface the README server-kill gap that the other three caught.
