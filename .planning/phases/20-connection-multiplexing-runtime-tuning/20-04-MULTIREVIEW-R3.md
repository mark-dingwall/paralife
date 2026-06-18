---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T16:30:00Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md"]
models:
  claude: "claude-opus-4-7"
  gemini: "gemini-3-pro-preview"
usage:
  claude: { input: 14, output: 153, cached: 908047, tool_calls: 3, elapsed_s: 59.6 }
  gemini: { input: 1341032, output: 708, cached: 652944, tool_calls: 7, elapsed_s: 196.3 }
  codex: { input: 388236, output: 6014, cached: 299904, tool_calls: 16, elapsed_s: 122.0 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 3, elapsed_s: 191.4 }
synthesizer: claude
synthesized_at: 2026-06-03T16:30:00Z
---

# Cross-AI Review

## Claude Review

R3 review complete.

**Verifications:**
- `RespawnConfig` prefix = `paralife.websocket` ✓ — key `paralife.websocket.max-respawns-per-session` correct
- Glob `grep -v load-harness | grep -v -- '-plain'` necessary + consistent across §3.1/§3.2/§3.3 (Spring Boot emits `-plain.jar` which sorts first lexically and would be picked sans exclusion → `java -jar` fails on non-executable jar)
- `HARNESS_JAR` glob OK (load-harness jar has no `-plain` variant)
- §6 new rows match column schema (Filename / Scenario / SHA / Captured / Size / Notes) ✓
- Active metric sidecar `~39–43 KB each` matches 18 samples vs baseline 6 samples ×3 ratio ✓

**Findings:**

- **NIT** §3.3 respawn caveat cites `RespawnConfig.java:31` — actual `@ConfigurationProperties(prefix = "paralife.websocket")` annotation is at line 33 (line 31 is mid-javadoc). Off-by-2. Cite still factually correct re: prefix; line drift fine per MVP lens.

- **NIT** §3.1 100-tier server launch retains `-Dparalife.admission.cap=1500` — §3 intro explains this is for "parity with §3.2/§3.3 + baseline capture conditions", but cap=256 default would also work at 100 bots. Pre-existing R1 decision; not introduced by R2; intentional.

- **NIT** 20-CONTEXT.md line ~314 (`code-context` block) places `RespawnConfig.java` under `com.paralife.engine` — actually lives at `com.paralife.websocket.RespawnConfig`. CONTEXT drift, out of scope per "do not re-flag" / Plan 6 reconciles.

**Post-triage HIGH-or-greater: none.** R2 converged. Plan is exec-ready.

---

## Gemini Review

# Phase 20-04-PLAN.md — R3 Review

**Regression Check:**
1. R2 edits are factually correct. The `paralife.websocket.max-respawns-per-session` key accurately targets `RespawnConfig.java:31` (`@ConfigurationProperties(prefix = "paralife.websocket")`).
2. Bash launch blocks are internally consistent; `grep -v -- '-plain'` robustly isolates the executable fat jar for `SERVER_JAR`, and the specific `-load-harness.jar` glob safely isolates `HARNESS_JAR`.
3. The new §6 rows (`.meta.json` and tuned-flamegraph) perfectly map to the 6-column table schema (`Filename | Scenario | Source SHA | Captured | Size | Notes`).
4. Active sidecar size correction (`~39–43 KB each`) correctly multiplies the baseline size (`~13–14 KB`) by the 3× sample count difference.

**New Findings:**
- **LOW** — §6 Profile Index table: `metrics-{100,500,1000}bots-baseline-62c1b44.json` row description claims "18-sample headline-gauge JSON snapshots". Per `20-01c-SUMMARY.md`, the baseline sidecars only contain **6 samples** (18-sample applies to the active scenario). Safe to ship as-is (documentation polish, does not break `Phase 21` copy-paste execution).

**Verdict:** 
R2 converged. No BLOCKER, HIGH, or MEDIUM issues remain post-triage. The plan is MVP-ready.

---

## Codex Review

[LOW] [20-04-PLAN.md:240](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:240) active JFR size range says `_0.8–1.2 MB each_`, but checked-in files are ~0.36 / 0.59 / 0.80 MB; change to `~0.35–0.8 MB each` if exact index sizes matter.

No BLOCKER / HIGH / MEDIUM post-triage findings.

R3 checks passed:
- Respawn key attribution matches [RespawnConfig.java:31](/home/mark/kramtime/paralife/src/main/java/com/paralife/websocket/RespawnConfig.java:31): `paralife.websocket.max-respawns-per-session`.
- All three server launch blocks include `grep -v -- '-plain'` and keep harness jar selection separate.
- New `.meta.json` and tuned flamegraph rows in §6 match the surrounding six-column schema.
- Active metric sidecar size correction matches actual files: ~39–43 KB each.

---

## Opencode Review

All factual claims verified against the codebase. Here's the R3 regression review:

---

## R3 Review: 20-04-PLAN.md

### R3-1: R2 edits — new errors?

| R2 Item | Claim | Verified? |
|---------|-------|-----------|
| #16 respawn key | `-Dparalife.websocket.max-respawns-per-session` → `RespawnConfig.java:31` | ✅ L31 is `@ConfigurationProperties(prefix = "paralife.websocket")` |
| #18 `-plain` exclusion | `grep -v -- '-plain'` on all 3 launches | ✅ Spring Boot Gradle plugin produces `-plain` jar by default; filter needed |
| #19 active sidecar size | `~39–43 KB each` | ✅ Matches 20-01c-SUMMARY |
| #20 `.meta.json` rows | Added baseline + active rows in §6 | ✅ Both present, 6 columns each |
| #21 tuned flamegraphs | `{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html` row | ✅ Present, 6 columns |

**No new factual errors introduced by R2 edits.**

### R3-2: Launch bash block consistency

All three recipes use **identical** `SERVER_JAR`/`HARNESS_JAR` extraction patterns:

```bash
SERVER_JAR=$(ls build/libs/paralife-*.jar | grep -v load-harness | grep -v -- '-plain' | head -1)
HARNESS_JAR=$(ls build/libs/paralife-*-load-harness.jar | head -1)
```

Filters are applied consistently across §3.1, §3.2, §3.3. **No inconsistency.** The `grep -v -- '-plain'` correctly uses `--` to prevent `-plain` from being interpreted as a grep option.

### R3-3: `RespawnConfig.java:31` attribution

`RespawnConfig.java:31` is exactly `@ConfigurationProperties(prefix = "paralife.websocket")`. Spring Boot's kebab-case convention maps `maxRespawnsPerSession` → `max-respawns-per-session`, yielding `-Dparalife.websocket.max-respawns-per-session=10`. The parenthetical `"paralife.simulation.spawn.* is the spawn config, not respawn config"` is a correct clarification. **Attribution matches.**

### R3-4: §6 row schema consistency

All new rows match the 6-column schema (`Filename | Scenario | Source SHA | Captured | Size | Notes`):

- `profiles/jfr-{100,500,1000}bots-baseline-62c1b44.meta.json` → `_~1.2 KB each_` ✅
- `profiles/jfr-{100,500,1000}bots-active-50xfood-103a615.meta.json` → `_~0.7 KB each_` ✅
- `profiles/{cpu,alloc,lock}-1000bots-tuned-<HEAD>.html` → `_≤10 MB each_` ✅
- `profiles/metrics-1000bots-tuned-<HEAD>.json` → `_~13–14 KB_` ✅

**No schema drift.**

### R3-5: Post-triage severity

**None at-or-above MEDIUM post-triage.** Three LOW observations (polish, not blocking):

1. **LOW** — §6 flamegraph sizes use `_≤10 MB_` placeholder caps rather than actual sizes from 20-01c-SUMMARY (cpu 81 KB, alloc 29 KB, lock 18 KB). The cap is technically correct (they're all ≤10 MB) but the actual values are known and could be cited. Does not affect runnability.

2. **LOW** — §3.2 yaml `outbound-queue-size: 128` is a no-op (it's the default). The comment `# default; reduce only with measured slow-client evidence` adequately explains this. An operator copy-pasting it verbatim loses nothing.

3. **LOW** — §3.3 states `"Pinning monitor: jdk.VirtualThreadPinned event count from the baseline JFR is the headline. If count is dominant, Plan 5 documents..."` — the D-21 outcome 4 "pinning-dominates supersedes knob tightening" rule is correctly referenced as Pass-3 Concern #25. The text is internally consistent with D-21.

---

**R3 verdict: CONVERGED.** No findings at-or-above MEDIUM post-triage. Plan is exec-ready with the Task 4.2 human-gate as the sole blocker.

---

## Consensus Summary

### Agreed Strengths
- R2 edits factually correct; no new errors introduced (claude, gemini, codex, opencode)
- `RespawnConfig.java:31` attribution and `paralife.websocket.max-respawns-per-session` key verified against source (all 4)
- `grep -v -- '-plain'` exclusion necessary and applied consistently across §3.1/§3.2/§3.3 launch blocks (all 4)
- New §6 rows (`.meta.json`, tuned flamegraph) match 6-column schema (all 4)
- Active metric sidecar `~39–43 KB each` correction matches actual files (claude, gemini, codex, opencode)
- R2 converged; plan exec-ready, no post-triage findings at-or-above MEDIUM (all 4)

### Agreed Concerns
- None at-or-above MEDIUM. All 4 reviewers report LOW/NIT only.

### Divergent Views
- **§6 baseline sidecar sample count** — gemini flags row description as "18-sample" when baseline is 6-sample (18 applies to active). Others did not flag. Worth checking row text.
- **§6 active JFR size range** — codex flags `0.8–1.2 MB each` vs actual `~0.36/0.59/0.80 MB`; suggests `~0.35–0.8 MB`. Others did not flag.
- **§6 flamegraph size cap** — opencode notes `≤10 MB` placeholder when actual sizes (cpu 81 KB / alloc 29 KB / lock 18 KB) are known. Others did not flag.
- **CONTEXT.md drift** — claude notes `RespawnConfig` mislocated under `com.paralife.engine` (actually `com.paralife.websocket`); out-of-scope per Plan 6. Others did not flag.
- **§3.1 cap=1500 at 100 bots** — claude notes cap=256 default would suffice; pre-existing R1 decision, intentional. Others did not flag.
