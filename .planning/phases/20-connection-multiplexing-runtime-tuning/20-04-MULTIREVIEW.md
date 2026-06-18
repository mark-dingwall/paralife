---
task: generic
mode: inline
reviewers_succeeded: ["claude", "gemini", "codex", "opencode"]
reviewers_failed: []
reviewed_at: 2026-06-03T10:14:44Z
files: [".planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md"]
usage:
  claude: { input: 169744, output: 18, cached: 42002, tool_calls: 0, elapsed_s: 249.3 }
  gemini: { input: 150990, output: 591, cached: 0, tool_calls: 0, elapsed_s: 152.6 }
  codex: { input: 405964, output: 6620, cached: 317824, tool_calls: 13, elapsed_s: 135.8 }
  opencode: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 109.8 }
synthesizer: claude
synthesized_at: 2026-06-03T10:14:44Z
---

# Cross-AI Review

## Claude Review

Reviewed `20-04-PLAN.md` against CONTEXT, 20-01c/02/03 summaries, dispositions. One must-fix. Verdict: **not exec-ready** — BLOCKER on §3 cap override.

## Findings

**BLOCKER**
- **§3.2 + §3.3 omit `-Dparalife.admission.cap=1500`.** Production default cap=256 (`application.yml:65`). 500 and 1000 bots > 256 → `AdmissionGate` rejects ~244 / ~744 bots `world-full`. Recipe (a) can't reach target count and (b) can't reproduce the cited baseline JFR — that JFR was captured at cap=1500 (20-01c F1). Running cap=256 re-introduces the **exact F1 defect 20-01c fixed** (c22e487's silently-bound ~256 ceiling). 20-01c F1 explicitly directs Plan 20-04 to cite cap=1500 as a benchmark-time `-D` override. Fix: add `-Dparalife.admission.cap=1500` to §3.2 + §3.3 server launch (§3.1 fits under 256 but add for baseline-config parity). Task 4.2 human-gate ("connected=N reaching target") would catch this, but recipes ship to Phase 21 verbatim — author them right.

**MEDIUM**
- **§6 Profile Index omits the active set `*-active-50xfood-103a615.*` (18 committed files).** 20-01c is emphatic: churn baseline misdirects tuning to env-CA; the active profile is the correct transport hot-path evidence Plan 5/6 cite for §4.4. Stub active rows in §6 now so Plan 6 needn't add them.
- **§3.3 omits respawn-cap caveat.** 20-01c Caveat #2: at `maxRespawnsPerSession=5`, 1000-tier live pop tails to ~901 over 180s; 20-01c names *Plan 20-04* to decide. Add operator note (or raise for sustained runs).
- **§3.2 is prose-by-reference** ("same as §3.1 with…"), not a literal block. Contradicts the "copy-pasteable verbatim" mandate for the 500-bot tier — and silently inherits §3.1's missing cap override. (Per #24 disposition, but 500-bot still isn't paste-able.)
- **Recipe seed `20251205` unconfirmed.** 20-01c-SUMMARY never records the literal seed used for the 62c1b44 capture. Mismatch → recipe doesn't reproduce baseline (D-19). Executor confirm against `meta.json`.
- **§3 acceptance grep is vacuous.** `grep -A 5 "yaml override" | grep -c queue-watermark-pct == 0`: anchor `"yaml override"` never matches heading `**Yaml overrides (optional):**` (case + plural) → returns 0 regardless of content. Can't catch a #8 violation. Content is correct (no qwp yaml key), but the check is theatre. Anchor the fenced ```yaml block instead.

**LOW**
- §3.1 JFR `duration=180s` vs harness `--duration 60` (§3.2: 180 vs 90) — JFR records long idle tail at lower tiers; align JFR window to load window.
- §2.2 cites "real consumer at `WorldWebSocketHandler.java:320`" — line cite unverifiable + fragile; queue is `OutboundSender`-owned per CLAUDE.md. CONTEXT cites `AdmissionConfig.java:114`. Prefer symbolic ref.
- §3.3 parallelism=8 footnote claims lock flamegraph shows "no carrier-thread saturation" — lock mode evidences monitor contention, not carrier saturation/pinning. Loose wording; marked Pending so non-blocking.
- Case-E idle-timeout footgun (20-02: both keys set + new=60000 → legacy wins) not surfaced in §2.1/§3. No recipe sets idle-timeout, so not triggered — note for completeness only.

**NIT**
- Footer `2026-05-XX` placeholder.

## Verification (the 8 asks)

| # | Check | Result |
|---|-------|--------|
| 1 | §2.1 = 8 JettyRuntimeConfig fields | ✅ MATCH (input/output buf, max frame/binary/text, idle-timeout, auto-fragment, max-outgoing-frames; defaults all correct) |
| 2 | §2.2 = 4 AppRuntimeConfig fields | ✅ MATCH, all 4 `[reserved]` |
| 3 | §3 JFR filenames @62c1b44 on disk | ✅ exist in 20-01c — **but** recipe cap≠baseline cap (BLOCKER) breaks reproduction |
| 4 | #7 + #8 | ✅ frame-size-budget-bytes `[reserved]` in §2.2; ✅ qwp absent from §3.2/§3.3 yaml keys (grep check itself vacuous — MEDIUM) |
| 5 | §5 999.6 cites D-21 outcome 4 | ✅ "dominant pinning with backlog-handoff" — correct |
| 6 | §4.1 noise-floor vs CONTEXT:230 | ✅ verbatim: "±5% of baseline mean OR ±1σ, whichever is larger" |
| 7 | Task 4.1 greps hit skeleton | ✅ WS:entity 1:1 / 62c1b44 / D-20 / D-08 / 999.4 / 999.6 / `[reserved`≥4 all present |
| 8 | parallelism=8 footnote well-formed | ✅ cites real `lock-1000bots-baseline-62c1b44.html`, matches `_Pending — JFR-driven_` pattern (evidentiary wording loose — LOW) |

Scope discipline good — no creep (recipes stay doc-only, GC `Pending`, no wrapper scripts, no parallel-encode numbers). 999.x numbering consistent (4=namespace, 5=re-baseline, 6=pinning). Fix the cap override and the recipes are sound.

---

## Gemini Review

Here is the review of **20-04-PLAN.md** based on your instructions and project context:

- **HIGH** — `depends_on` array in the frontmatter still cites the superseded `20-01b` instead of the canonical `20-01c`, which risks execution sequencing errors.
- **MEDIUM** — The `<context>` block omits `20-01c-SUMMARY.md` (it only retains `20-01-SUMMARY.md`). While correctly placed in `<read_first>`, it should be added to `<context>` to guarantee automatic orchestrator injection.
- **LOW** — §6 Profile Index placeholder table lists the actuator metric sidecar sizes as `_<1 KB_`, but `20-01c-SUMMARY.md` explicitly measured them at 13–14 KB.

**Verification of specific instructions:**
1. **§2.1 table:** Correct. It explicitly features all 8 fields from `JettyRuntimeConfig` including the newly added `max-outgoing-frames` (`-1`).
2. **§2.2 table:** Correct. It accurately lists the 4 properties configured on `AppRuntimeConfig`.
3. **§3 baseline filenames:** Correct. §3.1, §3.2, and §3.3 recipes all successfully cite `62c1b44`.
4. **Pass-2 Concerns #7/#8:** Correct. `frame-size-budget-bytes` is properly tagged `[reserved]` in §2.2, and `queue-watermark-pct` has been successfully stripped from all §3 override yaml blocks.
5. **D-21 outcome 4:** Correct. §5 Forward Notes properly contextualizes Phase 999.6 around the dominant pinning with backlog-handoff outcome.
6. **Noise-floor convention:** Correct. The §4.1 narrative perfectly mirrors CONTEXT line 230 ("±5% of baseline mean OR ±1σ, whichever is larger").
7. **Acceptance criteria greps:** Correct. All requested grep strings (`WS:entity 1:1`, `62c1b44`, `D-20`, `D-08`, `Phase 999.4`, `Phase 999.6`, `\[reserved`) exist and match the authored markdown exactly.
8. **§3.3 footnote:** Correct. The `parallelism=8` placeholder properly adheres to the `Pending — JFR-driven` format and accurately cites `profiles/lock-1000bots-baseline-62c1b44.html`. 

No scope creep detected. The plan successfully maintains Phase 20's boundaries.

---

## Codex Review

**Findings**
- **BLOCKER** — [20-04-PLAN.md:332](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:332): §3.2 is not copy-pasteable; it says “same as §3.1” instead of full server/harness commands, violating the Phase 21 verbatim-consumption requirement.
- **HIGH** — [20-04-PLAN.md:6](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:6), [20-04-PLAN.md:55](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:55): stale Plan 1 references remain (`depends_on: 20-01b`, context `20-01-SUMMARY.md`). Should be `20-01c` / `20-01c-SUMMARY.md` to avoid reintroducing c22e487 context.
- **HIGH** — [20-04-PLAN.md:338](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:338), [20-04-PLAN.md:378](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:378), [20-04-PLAN.md:419](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:419): the YAML blocks contain `queue-watermark-pct` in comments, so the specified grep returns nonzero. Move the note outside fenced YAML or change the verification to ignore comments.
- **MEDIUM** — [20-04-PLAN.md:407](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:407): human verification says to look for `connected=N` in stdout, but `LoadHarness` does not emit that literal. Use the harness report JSON `peak_registered/current_registered` or server `HARNESS connected` logs.
- **MEDIUM** — [20-04-PLAN.md:85](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:85): `62c1b44` is described as “Phase 19.1 close”; actual Phase 19.1-close baseline was superseded `c22e487`. `62c1b44` is the Plan 1c re-anchor after F1/F2/F6 remediation.
- **LOW** — [20-04-PLAN.md:216](/home/mark/kramtime/paralife/.planning/phases/20-connection-multiplexing-runtime-tuning/20-04-PLAN.md:216): §5 says both runtime records reserve `[live-tunable]` tags, but Jetty fields are launch-only and all four app fields are `[reserved — no effect in Phase 20]`.

**Verified**
§2.1 matches all 8 `JettyRuntimeConfig` fields. §2.2 matches all 4 `AppRuntimeConfig` fields. The three `jfr-{100,500,1000}bots-baseline-62c1b44.jfr` files and cited lock flamegraph exist. D-21 outcome 4 and the noise-floor convention are cited correctly.

---

## Opencode Review

## Review: 20-04-PLAN.md

### BLOCKER

**B1 — Task 4.1 `<read_first>` references stale `20-01-SUMMARY.md` instead of `20-01c-SUMMARY.md`.** The plan's refinement list item 4 claims this update was applied, but Task 4.1's `<read_first>` still lists `20-01-SUMMARY.md`. Task 4.2's `<read_first>` correctly uses `20-01c-SUMMARY.md`. The executor reading Task 4.1 would open a superseded (or nonexistent) file for a plan that was supposed to be updated.

### MEDIUM

**M1 — §6 Profile Index and §3 recipes omit active-population scenario artifacts.** 20-01c-SUMMARY.md explicitly directs Plans 20-04/05 to "tune against the active profile" and lists 18 `*-active-50xfood-103a615.*` files. §6 indexes only churn-baseline artifacts (`62c1b44`). §3 recipes don't mention the `-Dparalife.simulation.nutrient-spawn-probability=0.05` override nor cite active-scenario JFRs. Plan 5/6 executors need these filenames to know which profiles to tune against.

### LOW

**L1 — `depends_on` lists `20-01b` but baseline is now `20-01c`.** 20-01c-SUMMARY explicitly says it supersedes 20-01b. The dependency still points to the superseded plan. Not functionally broken (01b exists), but messy.

### Verified: OK

| Check | Result |
|-------|--------|
| §2.1 — 8 rows match all 8 JettyRuntimeConfig fields | ✓ |
| §2.2 — 4 rows match all 4 AppRuntimeConfig fields | ✓ |
| §3 — JFR filenames cite `62c1b44` per 20-01c-SUMMARY | ✓ |
| Pass-2 #7 — `frame-size-budget-bytes` tagged `[reserved]` in §2.2 | ✓ |
| Pass-2 #8 — §3.2/§3.3 yaml override blocks omit `queue-watermark-pct` | ✓ |
| §5 — Phase 999.6 correctly cites D-21 outcome 4 | ✓ |
| §4.1 — noise-floor convention matches D-21 | ✓ |
| Acceptance-criteria greps — all match skeleton content | ✓ |
| §3.3 `parallelism=8` footnote — cites real flamegraph, marked Pending, well-formed | ✓ |

### Summary

One blocker (stale `<read_first>` reference in Task 4.1), one medium (active-profile artifacts missing from §6 index), one low (depends_on references superseded 01b instead of 01c). All 8 verification items from the review instructions pass. No scope creep in the plan.

---

## Consensus Summary

### Agreed Strengths
- **All 8 structured verification asks pass.** §2.1 matches the 8 `JettyRuntimeConfig` fields (incl. `max-outgoing-frames=-1`) and §2.2 matches the 4 `AppRuntimeConfig` fields, confirmed independently by all 4 reviewers.
- **Baseline JFR filenames cite `62c1b44` and exist on disk** (`jfr-{100,500,1000}bots-baseline-62c1b44.*`) — all 4 reviewers.
- **`frame-size-budget-bytes` correctly tagged `[reserved]`** in §2.2 (Pass-2 #7) — all 4 reviewers.
- **§5 Phase 999.6 correctly cites D-21 outcome 4** (dominant pinning with backlog-handoff) — all 4 reviewers.
- **§4.1 noise-floor convention matches CONTEXT:230 verbatim** ("±5% of baseline mean OR ±1σ, whichever is larger") — all 4 reviewers.
- **§3.3 `parallelism=8` footnote is well-formed** — `Pending — JFR-driven`, cites the real `lock-1000bots-baseline-62c1b44.html` flamegraph — all 4 reviewers.
- **No scope creep.** Plan stays within Phase 20 boundaries; recipes remain doc-only, GC marked Pending — all 4 reviewers.

### Agreed Concerns
- **[HIGH — severity spread LOW→BLOCKER] Stale references to superseded Plan 1.** Two stale pointers remain:
  - `depends_on: 20-01b` should be `20-01c` — gemini (HIGH), codex (HIGH), opencode (LOW).
  - `20-01-SUMMARY.md` should be `20-01c-SUMMARY.md` in the context/`<read_first>` blocks — codex (HIGH, context block), gemini (MEDIUM, missing from `<context>`), opencode (BLOCKER, Task 4.1 `<read_first>` still stale while Task 4.2 is correct). Risks re-injecting the superseded c22e487 context the executor must not read.
- **[MEDIUM→BLOCKER] §3.2 is not copy-pasteable.** It says "same as §3.1 with…" instead of a literal block, violating the Phase 21 verbatim-consumption mandate for the 500-bot tier — codex (BLOCKER), claude (MEDIUM). claude additionally notes it silently inherits §3.1's gaps.
- **[MEDIUM→HIGH] The §3 `queue-watermark-pct` acceptance grep is flawed** — claude (MEDIUM), codex (HIGH). Both conclude the check cannot do its job (diagnoses differ — see Divergent Views).
- **[MEDIUM] Active-population profile artifacts omitted.** §6 Profile Index (and §3 recipes) index only the `62c1b44` churn baseline and skip the 18 committed `*-active-50xfood-103a615.*` files that 20-01c directs Plans 20-04/05/06 to tune against — claude (MEDIUM), opencode (MEDIUM). Plan 5/6 executors need these filenames to know which profiles are the transport hot-path evidence.

### Divergent Views
- **Cap override `-Dparalife.admission.cap=1500` missing from §3.2/§3.3** — flagged only by claude, as a **BLOCKER**: prod default cap=256 rejects ~244/~744 bots `world-full`, can't reach target counts, and re-introduces the exact F1 defect 20-01c fixed (the baseline JFR was captured at cap=1500). No other reviewer raised it. Strong reasoning from one source — worth verifying independently.
- **Does Pass-2 #8 pass?** Direct disagreement. claude/gemini/opencode marked `queue-watermark-pct` as successfully stripped (PASS). codex says it **FAILS** — the key still appears inside YAML comments, so the grep returns nonzero. Resolve by checking whether qwp survives anywhere in the §3 fenced blocks.
- **Why the grep is broken** (mechanism conflicts even where both agree it's broken): claude says the anchor `"yaml override"` never matches the heading `**Yaml overrides (optional):**`, so it returns 0 regardless of content (false-negative theatre); codex says comments contain `queue-watermark-pct` so it returns nonzero (false-positive). Mutually exclusive diagnoses — inspect the actual lines.
- **Singletons worth a look:** §6 actuator sidecar sizes listed `<1 KB` but 20-01c measured 13–14 KB (gemini, LOW); `62c1b44` mischaracterized as "Phase 19.1 close" when it's the Plan 1c re-anchor (codex, MEDIUM); human-gate looks for `connected=N` which `LoadHarness` doesn't emit (codex, MEDIUM); §5 claims `[live-tunable]` reservation but all four app fields are `[reserved]` and Jetty fields are launch-only (codex, LOW); respawn-cap tail-off caveat for sustained 1000-tier runs (claude, LOW).
