---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-04T15:05:35Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md", ".planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md", "README.md"]
models:
  claude: "claude-opus-4-8"
usage:
  claude: { input: 482620, output: 69, cached: 5921603, tool_calls: 10, elapsed_s: 367.5 }
  gemini: { input: 378254, output: 787, cached: 183843, tool_calls: 2, elapsed_s: 169.2 }
  codex: { input: 932658, output: 8835, cached: 798080, tool_calls: 24, elapsed_s: 181.4 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 170.4 }
synthesizer: claude
synthesized_at: 2026-06-04T15:05:35Z
fallbacks:
  gemini:
    attempts: ["gemini-3.1-pro-preview", "gemini-3-flash-preview"]
    used: "gemini-3-flash-preview"
---

# Cross-AI Review

## Claude Review

Verification complete. Every JFR-backed claim checks out **except** the baseline GC-pause numbers — and those are wrong against the committed artifacts. Review follows.

---

# Review: Phase 20 Plan 20-06 execution (post-merge)

**Method:** verified every headline number, date, size, citation, and grep-gate against the live tree and the committed JFR artifacts using the `jfr` CLI. Not trusting the summary.

## What's solid (verified, not asserted)

| Claim | Verdict |
|---|---|
| D-02 four-file `WS:entity 1:1` grep = 4 | ✅ 4 |
| `AdmissionMetrics.java:70` / `:79` line refs in CLAUDE.md | ✅ `M_TICK_WORK_MS`=70, `M_DETACH_TIMEOUT`=79 |
| `application.yml:15` actuator exposure | ✅ `include: health,info,metrics` at L15 |
| 20-RUNTIME.md zero-pending audit | ✅ 0 hits; footer dated `2026-06-05` (no `XX`) |
| 62c1b44 baseline JFR citations ≥9 | ✅ 15, all three tiers present |
| §6 sizes (2.3/3.9/4.5 MB, flamegraphs 80/29/19 KB, tuned 3.5M/3.1K/1.6K) | ✅ match `ls -lh` exactly |
| §6 captured dates (baseline 2026-05-20, active 2026-05-25 "from sidecar", tuned 2026-06-04) | ✅ match meta `captured_utc` / sidecar `sample_utc` |
| c22e487 history group = 12 files retained | ✅ 12 on disk, all indexed |
| 20-VALIDATION.md: 22 rows, all 8 plans incl. 20-06-6.x + 20-01b superseded + 20-01c-A/B/C; flags flipped | ✅ |
| §4.2 stats internal consistency (9.0/30.2 means + σ recompute from listed samples) | ✅ exact |
| Null-result evidence: 103a615 ExecutionSample=4792, SocketRead=0; tuned ExecutionSample=909, SocketRead=0; **VirtualThreadPinned=0 at every JFR**; tuned GCPhasePause=4 @ 27.8/20.9/19.0/23.1ms | ✅ all match |

The D-02 codification, VALIDATION map, README/CLAUDE additions, c22e487 de-stale, and the null-result/pinning/SocketRead evidence are all accurate. No stale "Pending"/"c22e487-as-current" framing reintroduced.

---

## Findings

### BLOCKER — 1
**`20-RUNTIME.md` §3.1, §3.2, §3.3, §4.3.1, §4.3.2, §4.3.3 — "0 `jdk.GCPhasePause` events" at the churn baselines is contradicted by the committed JFRs.**

The doc states, citing each churn JFR *by filename*, that the baseline recorded **0** GC pauses:
- §3.1 (L234-237): "`jfr-100bots-baseline-62c1b44.jfr` … recorded 0 `jdk.GCPhasePause` events … unmeasurable at 100 bots"
- §3.2 (L279-284): "`jfr-500bots-baseline-62c1b44.jfr` … recorded 0 `jdk.GCPhasePause` events"
- §3.3 (L331-337): "The baseline `jfr-1000bots-baseline-62c1b44.jfr` … recorded 0 pauses"
- §4.3.1 (L397-398): "`jfr-100bots-baseline-62c1b44.jfr` captured 0 `jdk.GCPhasePause` events … across the 200 s window"
- §4.3.2 (L423-424): "`jfr-500bots-baseline-62c1b44.jfr` recorded 0 `jdk.GCPhasePause` events"
- §4.3.3 (L469-471): "The baseline `jfr-1000bots-baseline-62c1b44.jfr` … recorded 0 pauses"

Actual event counts in those committed files (`jfr print --events jdk.GCPhasePause`):

| JFR | Doc claims | Actual events | Max pause |
|---|---|---|---|
| `jfr-100bots-baseline-62c1b44.jfr` | 0 | **10** | 11.8 ms |
| `jfr-500bots-baseline-62c1b44.jfr` | 0 | **18** | 41.9 ms |
| `jfr-1000bots-baseline-62c1b44.jfr` | 0 | **18** | 34.5 ms |

**Root cause:** the executor conflated the two baselines. The **active** `*-50xfood-103a615.jfr` captures genuinely have **0** GCPhasePause (verified: 0/0/0) — that is where the "0 pauses" finding actually came from (the plan's seed text "baseline 90 s window captured 0 pauses" correctly meant the 103a615 active baseline). But the doc attributed it to the **churn 62c1b44** files, which have 10/18/18 real pauses (durations up to 41.9 ms). VirtualThreadPinned=0 is fine everywhere — only the GC claim is false.

**Why BLOCKER:** this is the rubric's defining case — a factual claim in an *Authoritative / FINAL* spec doc, contradicted by the very artifact it names. The project's stated stance is "evidence over assertion (D-05)"; a reviewer opening the JFR to challenge the recommendation (the explicit promise of §4.1/D-05) finds the opposite of what's written.

**Mitigating (so the fixer knows the scope):** the *design conclusion survives*. Worst case is 500-bot churn ≈ 284 ms total over 200 s = 0.14% wall-clock — still far below the >2% ZGC trigger, so "G1 stays" is correct. This is an attribution/wording fix, not a re-decision.

**Concrete fix:** pivot the GC rationale to the active 103a615 captures (which are 0-pause *and* are the doc's own "authoritative transport-load evidence set" per §4.3.3), e.g. *"the active-50xfood baseline `jfr-{N}bots-active-50xfood-103a615.jfr` recorded 0 `jdk.GCPhasePause` events"* — OR state the churn counts honestly in the tuned-capture's own style: *"N small G1 minor pauses totalling X ms ≈ Y% wall-clock, below the 2% trigger."* Do not leave any `*-baseline-62c1b44.jfr` filename next to a "0 GCPhasePause" assertion.

### MEDIUM — 2
**`20-RUNTIME.md` §3.3 (L335) — churn 1000-baseline window mislabelled "90 s effective window"; it is 200 s.**

`jfr-1000bots-baseline-62c1b44.meta.json` carries `duration_s: 200` (and §3 intro + §4.2 + §4.3.1/§4.3.2 all correctly state churn = 200 s). The "90 s" is the active 103a615 window (`profile_window_s: 90`). Same churn/active conflation as the BLOCKER. Fix: say "200 s" (or drop the window qualifier) for the 62c1b44 churn 1000 baseline. (§4.3.3 L470 hedges with "shorter effective window" — also imprecise, but the explicit "90 s" in §3.3 is the wrong number a cross-referencing reader catches.)

### LOW — 3
**`20-RUNTIME.md` §4.4 codec table (L492) — "baseline 90 s window captured 0 pauses" is correct only if "baseline" = active 103a615; the cell doesn't say which.** Given §4.3.3 attributes "0 pauses" to the *churn* 62c1b44 baseline (false), the §4.4 phrasing inherits the ambiguity. Once finding 1 is fixed by re-attributing to 103a615, make §4.4 explicit: "active-103a615 baseline (90 s) captured 0 pauses." Technically defensible as-is (103a615 = 0 @ 90 s), hence LOW.

### NIT — 4
**`20-06-SUMMARY.md` reports baseline JFR sizes inconsistent with the shipped doc.** SUMMARY (via 20-01c lineage) implies 2.4/4.0/4.7 MB; §6 of 20-RUNTIME.md says 2.3/3.9/4.5 MB. The **shipped artifact (§6) is correct** — matches `ls -lh` exactly. No deliverable defect; only the summary prose is loose. Not worth a fix.

---

## Convergence judgement

**New HIGH+ findings: 1 BLOCKER** — the "0 `jdk.GCPhasePause`" baseline claims (§3.1/§3.2/§3.3/§4.3.1/§4.3.2/§4.3.3) are contradicted by the committed churn JFRs (actual 10/18/18 pauses); the 0-pause evidence belongs to the active 103a615 captures. Plus 1 MEDIUM (§3.3 200s-vs-90s window mislabel), 1 LOW, 1 NIT.

**Convergence NOT reached.** The BLOCKER must be fixed before ship — it's a false measurement in the phase's canonical "evidence over assertion" deliverable. The fix is bounded (re-attribute to the active JFRs or state real churn counts; design conclusion unchanged), so a single targeted edit pass should clear it. Everything else in the plan executed faithfully and verifies clean.

---

## Gemini Review

This pre-publication QA review of Phase 20 Plan 20-06 (documentation finalisation) confirms that the executed deliverables are internally consistent, architecturally accurate, and compliant with the plan contract (`20-06-PLAN.md`).

### Review Summary

1.  **Internal Consistency**: `20-RUNTIME.md` §4.2 headline numbers (e.g., 1000-bot tuned mean of 45.0 ms, σ=8.77) are correctly cited in the §4.3.3 narrative and derived from the Plan 5 metric sidecars indexed in §6. The -4.5 ms delta is correctly classified as a null-result within the D-21 noise floor (±15.74 ms).
2.  **D-02 Three-place Rationale**: The mandatory "WS:entity 1:1" design lock appears in all three locations (four files): `README.md` §Runtime tuning, `CLAUDE.md` §Runtime tuning, and inline comments at the WebSocket upgrade (`WorldWebSocketHandler`) and sender-VT spawn (`OutboundSender`) sites. All citations correctly reference `20-RUNTIME.md §1`.
3.  **20-VALIDATION.md Fidelity**: The Per-Task Verification Map is fully populated with 22 rows covering all 8 plans (including superseded 20-01b and prose-section 20-01c). The frontmatter is correctly flipped to `nyquist_compliant: true`.
4.  **De-staling of Historical Claims**: `20-CONTEXT.md` D-19 and related bullets are correctly annotated with the `62c1b44` re-anchor information. `profiles/README.md` has been de-staled to reflect the canonical baseline ritual.
5.  **Document Quality**: `20-RUNTIME.md` passes the zero-pending audit; all placeholder markers and future-tense forward references have been replaced with settled past-tense narrative (e.g., §3 intro, §6 index notes).

### Findings

| Severity | File + Section | Finding | Concrete Fix | Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **NONE** | — | — | — | — |

### Convergence Judgement

**No new HIGH+ findings — convergence reached.**

The deliverables effectively lock the Phase 20 architectural decisions (D-01 to D-21) and provide the canonical evidence base (1000-bot active-scenario null-result) required for Phase 21 benchmark gating.

**Verdict: APPROVED (EXECUTION COMPLETE)**

---

## Codex Review

**Findings**

1. `MEDIUM` — [20-RUNTIME.md §6](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:511)  
The §6 preamble says “only the `62c1b44` series is cited by §3 recipes, §4 numbers, and D-19,” but §4 intentionally cites `103a615` and `424e06d` for the 1000-tier active baseline/tuned numbers. Evidence: §4.2 says 1000 baseline/tuned source from active-50xfood `103a615`/`424e06d` at [20-RUNTIME.md:376](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:376), and §4.3.3 cites those files at [20-RUNTIME.md:445](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:445).  
Concrete fix: reword to say `c22e487` is history-only; `62c1b44` anchors churn recipes / 100-500 baseline cells, while §4’s 1000 active evidence uses `103a615` + `424e06d`.

2. `MEDIUM` — [20-VALIDATION.md Per-Task Verification Map](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:46)  
Several “Automated Command” cells still contain placeholders or non-runnable shorthand (`ls ...`, `same artifact existence`, `&& ... --tests`, `.../20-RUNTIME.md`). Task 6.5’s contract required exact verify commands or concrete manual procedures. Evidence at [20-VALIDATION.md:46](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:46), [20-VALIDATION.md:55](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:55), [20-VALIDATION.md:56](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:56), and [20-VALIDATION.md:61](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-VALIDATION.md:61).  
Concrete fix: replace shorthand with the full commands from the corresponding PLAN `<verify>` blocks, or mark manual rows with explicit step-by-step procedure text.

3. `MEDIUM` — [profiles/README.md baseline ritual](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/profiles/README.md:73)  
The operator ritual says to scrape actuator metrics into the sidecar but only curls `paralife.tick.health.work-time-ms`. Phase 20’s headline sidecars require both `paralife.tick.health.work-time-ms` and `paralife.outbound.detach.timeout`; the committed sidecars include both. Evidence: `20-RUNTIME.md` states both headline gauges are sourced from JSON sidecars at [20-RUNTIME.md:365](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:365), and §4.2 depends on `detach.timeout` at [20-RUNTIME.md:387](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md:387).  
Concrete fix: update step 5 to use the actual sidecar capture loop/schema, or at minimum collect both required actuator endpoints into the JSON sidecar.

4. `LOW` — [20-06-SUMMARY.md Performance](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md:63)  
The file-count parenthetical is internally inconsistent: “8” files modified, but “5 .planning docs + CLAUDE.md + README.md + 2 source files” sums to 9. The key-files list shows 8 modified files plus the created summary.  
Concrete fix: change to “8 modified files” with “4 .planning docs,” or “9 touched files including the created summary.”

5. `LOW` — [20-06-SUMMARY.md README before/after](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-06-SUMMARY.md:168)  
The summary says the README was previously 2 lines, but the supplied diff shows it was a 1-line `# paralife` stub.  
Concrete fix: change “Before: 2 lines” to “Before: 1 line.”

**Checked Clean**

D-02 four-file codification is present and mutually consistent. The metric values in §4.2 match the JSON sidecars I sampled. The live `@Disabled` inventory matches the validation doc’s 6 annotations across 5 files.

**Convergence Judgement**

No new HIGH+ — convergence reached.

---

## Opencode Review

I've reviewed all the provided artifacts. Here are my findings.

## Finding

**MEDIUM** — `profiles/README.md` ~L95: factual error in `-D` guidance.

The "Re-running (tuned-state captures)" section says:
```
# (as a Spring app-arg AFTER -jar, NOT as -D before -jar -- gemini R4 finding)
```

This is wrong. `-D` properties set **before** `-jar` are JVM system properties and Spring Boot's `@ConfigurationProperties` reads them fine. The correct rule (stated correctly in `20-RUNTIME.md` §3 intro) is that `-D` **after** `-jar` is silently ignored as a system property. Saying "NOT as -D before -jar" could mislead an operator into thinking `-Dparalife.simulation.nutrient-spawn-probability=0.05` placed before `-jar` won't work, when it does.

Evidence: `20-RUNTIME.md` §3 intro correctly states "*passing the same value as a JVM `-D` property **after `-jar`** is a silently-ignored application argument*" (emphasis mine). The `profiles/README.md` version inverts the direction.

**Fix:** Change `before -jar` to `after -jar` to match the correct statement in `20-RUNTIME.md`.

## Convergence judgement

No new HIGH+ — **convergence reached**. One MEDIUM factual error in operator-facing documentation (`profiles/README.md` re-running section), all other artifacts are internally consistent with the plan contract. D-02 three-place codification is complete and grep-verifiable. 20-VALIDATION.md map has 22 rows covering all 8 plans. No stale claims reintroduced.

---

## Consensus Summary

### Agreed Strengths
- **D-02 "WS:entity 1:1" three-place codification complete and grep-verifiable** — all four reviewers confirm present and mutually consistent across README.md, CLAUDE.md, and inline comments at `WorldWebSocketHandler` upgrade + `OutboundSender` sender-VT sites (grep = 4).
- **20-VALIDATION.md map fidelity** — 22 rows covering all 8 plans (incl. superseded 20-01b, 20-01c sub-rows), `nyquist_compliant: true` flipped (claude, gemini, codex, opencode).
- **§4.2 metric values match committed JSON sidecars** — verified against sampled sidecars (claude, gemini, codex); -4.5 ms 1000-bot delta correctly classed null-result within D-21 noise floor.
- **No stale "Pending" / c22e487-as-current framing reintroduced; zero-pending audit passes** (claude, gemini, opencode).

### Agreed Concerns
- **MEDIUM — churn (`62c1b44`) vs active (`103a615`) baseline conflation in 20-RUNTIME.md** (claude, codex). claude: §6 preamble claims "only `62c1b44` cited" but §4 intentionally cites `103a615`/`424e06d` for 1000-tier active numbers. codex same. Fix: reword §6 — `c22e487` history-only, `62c1b44` anchors churn recipes + 100/500 cells, §4 1000-active evidence uses `103a615`+`424e06d`.
- **MEDIUM — `profiles/README.md` operator ritual has factual errors** (codex, opencode — different errors, same file/section): codex — sidecar capture loop only curls `work-time-ms`, missing required `outbound.detach.timeout` gauge; opencode — `-D` direction inverted ("NOT as -D before -jar" should be *after* -jar, per 20-RUNTIME.md §3 intro). Both operator-facing, both should be fixed.

### Divergent Views
- **GC-pause BLOCKER (claude only) — convergence verdict split.** claude opened the committed JFRs with the `jfr` CLI and found 20-RUNTIME.md §3.1/§3.2/§3.3/§4.3.1/§4.3.2/§4.3.3 assert "0 `jdk.GCPhasePause` events" against named `*-baseline-62c1b44.jfr` files that actually hold **10/18/18** pauses (max 41.9 ms); the genuine 0-pause evidence belongs to the active `103a615` captures. gemini/codex/opencode reviewed doc-internal consistency and declared convergence reached; none verified against artifact bytes. **This is the highest-value item to investigate** — it's a falsifiable measurement claim in the phase's "evidence over assertion (D-05)" canonical doc. Design conclusion survives (worst case ≈0.14% wall-clock, below 2% ZGC trigger) — bounded attribution/wording fix, not a re-decision. Related: claude MEDIUM §3.3 "90 s window" mislabel (churn = 200 s per meta.json).
- **20-VALIDATION.md verify-command quality** — codex (MEDIUM): several "Automated Command" cells still hold placeholders / non-runnable shorthand (`ls ...`, `same artifact existence`, `&& ... --tests`), violating Task 6.5 contract. claude explicitly checked the map clean. Worth a direct look at the rows codex cites (L46/55/56/61).
- **20-06-SUMMARY.md minor inconsistencies** (codex LOW, claude NIT): file-count parenthetical sums to 9 not 8; "README before: 2 lines" vs actual 1-line stub; baseline JFR sizes loose (2.4/4.0/4.7 vs shipped-correct 2.3/3.9/4.5 MB). Summary prose only — no deliverable defect.
