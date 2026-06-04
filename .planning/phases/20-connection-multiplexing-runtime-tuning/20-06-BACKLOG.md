# Plan 20-06 multi-review backlog / do-not-reflag

Loop target: no NEW HIGH+ post-triage. Subject: 20-06-PLAN.md (pre-execution, post-de-stale `3477d39`).

## Convergence reached at R4

Round-by-round post-triage HIGH trajectory: R1=3, R2=1, R3=2, **R4=0**. Convergence target met. R4's only HIGH claim (codex `captured_at` field) recategorised MEDIUM (one-word field-name error, executor-recoverable by reading the meta) and fixed alongside the round's other accepted items. **Plan 20-06 is executable as-written.**

## R1 (`20-06-MULTIREVIEW-R1.md`) — post-triage: 3 HIGH, 6 MEDIUM accepted; all fixed this commit

### Fixed (this commit)

| # | Source | Sev (post-triage) | Finding | Fix |
|---|--------|------|---------|-----|
| H1 | claude+opencode (consensus, both ran the grep) | HIGH | Task 6.1 gauge acceptance grep `^\| (paralife...` can't match — §4.2 rows wrap metric names in backticks; 0 hits even on populated table | pattern → `^\| .?paralife\.(tick\.health\.work-time-ms\|outbound\.detach\.timeout)`; dry-run = 2 rows |
| H2 | codex | HIGH | step 5 claimed §6 index "covers all three generations incl. superseded c22e487" — FALSE; §6 has zero c22e487 rows, 12 committed files unlisted, must-have says "every committed artifact" | step 5 premise corrected + add history-only c22e487 row group + acceptance grep `jfr-1000bots-baseline-c22e487.jfr` |
| H3 | codex | HIGH | D-19 reconcile (step 7) too narrow — 20-CONTEXT L225/327/463 still cite c22e487 as live; profiles/README says "baseline (always c22e487)" — operator-facing, would direct capture against wrong SHA | step 7 widened: annotate 3 more CONTEXT sites; de-stale profiles/README (added to files_modified) |
| M1 | claude+opencode+gemini | MEDIUM | §3 stale forward-refs (L100/119/143/155/314 incl. dangling `jfr-1000bots-tuned-<HEAD>.jfr` placeholder) uncovered by step 1; step-6 audit grep matched L100 → step fails own audit | step 1 widened to all 5 prose sites; audit regex widened to `pending\|plan [0-9/]+ (populates\|produces\|finalises\|wires\|will)`, expect 0; all 14 current hits verified mapped to steps 1/2/3 |
| M2 | gemini | MEDIUM | step 3 only covered work-time-ms row's Pending cells; detach.timeout + VirtualThreadPinned 100/500-baseline `_Pending_` cells had no source instruction (pinning needs `jfr print`, not sidecar) | step 3: all three rows in scope; sidecar JSON keys named; `jfr print --events jdk.VirtualThreadPinned` for pinning |
| M3 | gemini | MEDIUM | Task 6.5 step 3 says tick Plan 5 Wave-0 box — checklist has NO Plan 5 line | step 3: ADD the Plan 5 line before ticking |
| M4 | claude(LOW)+opencode(MED) | MEDIUM | 20-VALIDATION L67 checkbox + L81-84 manual rows cite c22e487 while step 4 flips sign-off to 62c1b44 → file self-contradicts post-execution | step 3: annotate as superseded/history-only |
| M5 | codex | MEDIUM | VALIDATION verify/acceptance had no 20-06 row grep; floor 16 satisfiable by pre-20-06 rows alone; read_first omitted 20-06-PLAN | added `^\| 20-06-6\.` greps; floor 16→20; read_first += this plan |
| M6 | claude | MEDIUM | Task 6.4 gates comment-only change on `./gradlew test` with no TD-22-E flake path (Plan 5 SUMMARY records BUILD FAILED on XML-write contention) | single-retry-on-infra-flake clause added (mirrors Plan 5 Concern #14) |
| L1 | claude+codex | LOW | line-count case patterns `*"null-result:"*` carry colon; live signal `Plan 5 outcome: null-result` has none → silent fall-through (latent 350→250 underclaim) | colons dropped; dry-run matches |
| L2 | gemini+codex | LOW | 62c1b44 citation grep ≥6 already satisfied pre-execution; no per-tier proof | per-tier loop added + floor raised to 9 (6 index + 3 narrative headings = exactly 9) |
| L3 | gemini (HIGH, recategorised) | LOW | AdmissionMetrics acceptance pinned `:70`/`:79` while plan says "they drift" — internally contradictory contract | acceptance → `AdmissionMetrics\.java:[0-9]+` count ≥2; executor re-verifies actual numbers |
| L4 | opencode | LOW | `<interfaces>` CLAUDE.md insertion note garbled ("BEFORE ... is already above") | reworded: between §Connection model end and ## Project Skills |
| N1 | claude | NIT | example task-id `20-02-2.0` doesn't exist (20-02 ships 2.1/2.2/2.3) | → `20-02-2.1` |

### Dropped with reason (do not re-flag)

- **[R1 gemini HIGH] AdmissionMetrics at lines 52/59, `:70`/`:79` grep unsatisfiable** — DROPPED as stated. `grep -n` at HEAD: `M_TICK_WORK_MS`=L70, `M_DETACH_TIMEOUT`=L79. Gemini misread (claude independently confirmed 70/79). The *robustness* kernel of the finding was kept as L3.
- **[R1 codex MEDIUM] 20-06-SUMMARY.md + ROADMAP completion update untracked (no files_modified entry / task / acceptance grep)** — DROPPED. GSD execute-plan workflow (`execution_context` @execute-plan.md) itself mandates SUMMARY creation + ROADMAP/STATE tracking updates on every plan; no Phase 20 plan lists them in `files_modified` (convention: task-touched repo files only). Output block documents the required SUMMARY content — that is the established contract.
- **[R1 opencode MEDIUM #3] Task 6.1 step 4 TRIAGE consistency** — reviewer self-retracted in the same review ("No change needed — verified correct").
- **[R1 opencode LOW] OutboundSender insertion line ~132-135 off by ~1-2 lines** — DROPPED per reviewer's own note: Edit tool matches the quoted code block, not line numbers.
- **[R1 opencode NIT] 20-VALIDATION.md absent from `<context>` block** — DROPPED. It's in Task 6.5 `read_first` and `files_modified`; top-level context block lists framing docs, not every touched file.

## R2 (`20-06-MULTIREVIEW-R2.md`) — post-triage: 1 HIGH accepted; all fixed this commit

### Fixed (this commit)

| # | Source | Sev (post-triage) | Finding | Fix |
|---|--------|------|---------|-----|
| H1 | opencode HIGH + codex MED | HIGH | "4 P22 `@Disabled` tests" stale at HEAD — live inventory 6 annotations / 5 files; TD-22-D `HundredBotIntegrationTest` RE-ENABLED; ticking D-12 sign-off as-written = false validation record (opencode's "7" included a javadoc mention, corrected to 6) | plan L407/456/499/510 reworded to live-inventory protocol + VALIDATION ~L24 rewrite folded into Task 6.5 |
| M1 | codex HIGH (recat MED, claude concurs MED) | MEDIUM | R1-H2 fix under-enumerated: c22e487 row group listed 9/12 files — 3 `metrics-*-baseline-c22e487.json` sidecars missing; acceptance grep couldn't see the gap | enumeration += sidecars; acceptance += `metrics-1000bots-baseline-c22e487.json` grep |
| M2 | gemini HIGH (recat MED — quoted text is unique, executor finds it) | MEDIUM | step 7 placed the ~L370 "Plan 6 reconciles" parenthetical in §6; it lives in §5 Forward Notes | step reworded: §5 (~L370) + §6 (~L377), locate by quoted text |
| M3 | gemini MED + claude NIT | MEDIUM | "two c22e487 Manual-Only rows (~L81-84)" — only L81 carries the SHA; L83 row carries stale `jfr-1000bots-tuned-<sha>.jfr` pattern instead | wording fixed: single c22e487 row + annotate L83 with shipped tuned filename |
| M4 | opencode | MEDIUM | `20-05-TRIAGE.md` referenced by step 4 but absent from read_first | added |
| M5 | opencode | MEDIUM | gauge acceptance grep coupled to backtick table format | step 3 format-preservation note |
| L1 | claude | LOW | §6 ~L388 Notes "Plan 5/6 tune against this set" — stale forward-ref outside audit verb list; L118 "Plan 5 tunes against" likewise | step 5 reword bullet + audit regex += `tunes?` + step 1 quote widened to full L118-119 sentence (dry-run: 16 hits, all step-mapped; suggested rewording verified regex-clean) |
| L2 | claude | LOW | CLAUDE.md template cited "Plan 1b + Plan 5 capture from" — 1b superseded | → Plan 1c + Plan 5 |
| L3 | codex HIGH (recat LOW — verify blocks carry full repo-root paths; claude concurs LOW) | LOW | bare-path acceptance greps fail from repo root | working-directory note added to 6.1 + 6.5 acceptance blocks |
| L4 | opencode | LOW | interfaces CLAUDE.md ~L141/144 off; insertion must stay INSIDE `GSD:architecture-end` marker (caught while fixing — subsection is architecture content) | note + Task 6.2 action updated |
| L5 | opencode | LOW | OutboundSender interfaces range 124-135 vs actual ~128-138 | annotated, match-on-code-block note |
| L6 | opencode | LOW | context block lists 20-01-SUMMARY but task needs 20-01c-SUMMARY | @20-01c-SUMMARY.md added to context block |
| N1 | claude | NIT | ≥9 citation gate zero-margin | step 2 note: headings carry tier baseline JFR filename verbatim |
| N2 | opencode | NIT | `application.yml:15` template ref had no re-verify instruction | folded into 6.2 re-verify clause |

### Dropped with reason (do not re-flag)

- **[R2 codex MEDIUM] D-19 bullet annotation leaves stale "git checkout c22e487" lead text in 20-CONTEXT.md** — DROPPED. Annotate-not-rewrite is the deliberate pattern for 20-CONTEXT (decision-history record; step 7 says "do NOT rewrite the surrounding decision history"). Operator-facing capture guidance lives in `profiles/README.md`, which step 7 now de-stales to `62c1b44`. A reader of the D-19 bullet hits the re-anchor parenthetical in the same sentence.

## R3 (`20-06-MULTIREVIEW-R3.md`) — post-triage: 2 HIGH accepted; all fixed this commit

### Fixed (this commit)

| # | Source | Sev (post-triage) | Finding | Fix |
|---|--------|------|---------|-----|
| H1 | claude+gemini (consensus; claude proved on live 62c1b44 brace row) | HIGH | R2's c22e487 acceptance greps literal-only while step 5 prescribes compressed-brace form — faithful execution rejected (R1-H1 defect class, introduced by R2 fix) | greps → brace-tolerant ERE `(\{[0-9,]+\}\|1000)bots`; dry-run vs prescribed strings = match |
| H2 | codex (solo, verified real) | HIGH | step 1 told executor to write heap presets as "measured-justified / defaults stand" — but ALL captures (churn/active/tuned) ran `-Xms2g -Xmx2g` per meta.json `jvm_flags`; 100-tier `1g/1g` + 500-tier `1g/2g` presets never exercised by any JFR → false claim in canonical doc | step 1 split: GC + parallelism measured-justified; heap = honest "no heap retune; smoke presets, NOT JFR-validated; reproduction uses 2g/2g capture shape"; recipe values unchanged |
| M1 | codex | MEDIUM | acceptance could pass with `jdk.VirtualThreadPinned` 100/500 cells still `_Pending_` (gauge grep covers only the 2 paralife rows; audit was action-only) | zero-pending audit added as acceptance gate (`! grep -iqE ...`) |
| M2 | codex | MEDIUM | profiles/README tuned filename examples (~L17, ~L75-99) still non-scenario `jfr-{N}bots-tuned-{HEAD_SHA}.jfr` — operator would mint wrongly-named tuned captures | README bullet extended: scenario-aware pattern + concrete `424e06d` example |
| M3 | claude | MEDIUM | §4.2 baseline row silently mixes churn-62c1b44 (100/500) with active-103a615 (1000) — headline table apples-to-oranges without label | step 3: one-line scenario note added to §4.2 footnote (kept churn sources — re-sourcing 100/500 from active sidecars would change tier-anchor meaning; 01c directive applied active treatment to 1000 only) |
| M4 | codex | MEDIUM | `jfr print profiles/...` path fails from repo root | full repo-root path inlined |
| L1 | claude | LOW | pinning-dominates grouped at ≥250 contradicts canonical Concern #13 disposition (≥350 for codec/knob/pinning; 250 = null-result only); inert this run | case branch + must_haves min_lines + prose regrouped; disposition-aligned |
| L2 | opencode | LOW | step 5 "capture dates via ls -lh" — mtimes not authoritative; risks losing `_Plan 1c_` provenance labels | sizes from ls -lh, dates from meta.json `captured_at`, labels retained (`Plan 1c — 2026-05-27` form) |
| L3 | opencode | LOW | VALIDATION ~L62 Wave-0 intro still says "four P22" + lists re-enabled TD-22-D — outside the R2 rewrite scope (L24/L96 only) | L62 added to Task 6.5 step 4 rewrite scope |
| L4 | claude | LOW | tuned-JFR manual row is L82 not "~L83" | corrected |
| N1 | claude | NIT | must_haves OutboundSender artifact ref "~132-135" vs actual L136-138 | corrected + match-on-code-block note |

### Dropped with reason (do not re-flag)

- **[R3 gemini MEDIUM] AdmissionMetrics at L63/L72, template stale** — DROPPED. `grep -n` at HEAD (`59e8cfd`-era file, last touched `0824f1a`): `M_TICK_WORK_MS`=**L70**, `M_DETACH_TIMEOUT`=**L79**. Gemini's THIRD distinct wrong pair (R1: 52/59; R3: 63/72) for the same two constants — likely reading a stale/partial view. Claude independently verified 70/79 in R1 and R3. Plan's line-agnostic grep + re-verify clause makes the dispute moot at execution time anyway.
- **[R3 opencode NIT] no automated grep for `application.yml:15` ref** — DROPPED. Re-verify instruction (R2-N2) suffices; a yml-line-ref grep adds contract surface for a cosmetic citation. Line verified correct at HEAD by two reviewers.

## R4 (`20-06-MULTIREVIEW-R4.md`) — post-triage: 0 HIGH (CONVERGED); accepted MED/LOW fixed this commit

### Fixed (this commit)

| # | Source | Sev (post-triage) | Finding | Fix |
|---|--------|------|---------|-----|
| M1 | codex HIGH (recat MED — executor reads the meta and recovers) | MEDIUM | R3's date instruction named nonexistent field `captured_at`; live field is `captured_utc` (62c1b44 + tuned metas); `103a615` active metas have NO timestamp field at all | step 5: `captured_utc` when present; 103a615 → first `sample_utc` from matching metrics sidecar, marked `(from metric sidecar)`; "do not invent dates" |
| M2 | claude | MEDIUM | Task 6.4 `&& ./gradlew test` hard gate vs documented pre-existing HundredBot WSL2 timeout (20-01c-SUMMARY Caveat #1, verified pre-Phase-20 at `14e96ea`); TD-22-E clause didn't cover it — executor would stop-and-report on a non-Plan-6 condition | flake protocol widened: two enumerated pre-existing conditions (TD-22-E XML, HundredBot WSL2 timeout); retry-once → record + three-gate load-bearing; any OTHER failure still stop-and-report |
| M3 | codex | MEDIUM | §4.3 100-tier template hint "default G1 + 1g heap sustains" would reintroduce the exact heap overclaim R3-H2 fixed | template hint reworded: headroom shown in 2g/2g capture; 1g = smoke preset, not JFR-validated |
| M4 | opencode | MEDIUM | VALIDATION L84 D-02 grep has dead `see 20-RUNTIME` branch (case mismatch vs planned "See 20-RUNTIME.md" comment; OutboundSender comment has no "see" at all); `WS:entity 1:1` alternative carries | Task 6.5 step 3 scope += L84: drop dead branch or `20-RUNTIME\.md` |
| L1 | claude | LOW | RUNTIME footer `· v1 · 2026-06-XX` placeholder evades zero-pending audit; no step stamps it | step 5: stamp completion date |
| L2 | codex | LOW | objective prose (L64) + verification summary (L509) still said "≥250 for outcomes 3/4" — residue of R3's pinning regroup | both aligned to 1/2/4→350, 3→250 |
| L3 | opencode | LOW | step 5 "add a row for profiles/README if absent" incongruous with §6 table schema (Filename/Scenario/SHA/Captured/Size); README already cited in §6 preamble ~L332 | inverted: do NOT add a row, cite preamble |

### Dropped with reason (do not re-flag)

- **[R4 claude NIT] README template nests ```bash fence inside ```markdown fence** — DROPPED. Executor Writes the literal body; final file well-formed; restructuring the fence is churn.
- **[R4 opencode LOW] audit regex could false-positive on past-tense "Plan 4 produced"** — DROPPED per reviewer's own analysis (harmless; executor verifies and moves on; regex is documented as forward-ref-only).
- **[R4 opencode LOW] OutboundSender ~L136-138** — reviewer self-verified correct; no action.

## Round fix indexes (do not re-raise)

- **R1** `cf2e4e3` → `20-06-MULTIREVIEW-R1.md`
- **R2** `59e8cfd` → `20-06-MULTIREVIEW-R2.md`
- **R3** `4887739` → `20-06-MULTIREVIEW-R3.md`
- **R4** (this commit) → `20-06-MULTIREVIEW-R4.md`

## Reviewer health

R1: 4/4 succeeded — first codex success of the Phase 20 loops (gpt-5.5 pin per the gpt-5/ChatGPT-account 400 root-cause). Gemini produced full review but misread AdmissionMetrics line numbers (only reviewer to do so).
R2: 4/4 succeeded. Severity calibration divergent (codex ran hot: 2 of its HIGHs recategorised; gemini's HIGH recategorised MED). opencode's HIGH was the round's one accepted HIGH (with count correction 7→6).
R3: 4/4 succeeded. Codex's solo heap HIGH verified REAL (meta.json evidence) — its uncorroborated findings deserve direct verification, not dismissal. Gemini's AdmissionMetrics misread recurred (3rd wrong pair) — treat gemini line-number claims on this file as suspect. opencode found nothing above LOW (called execution-ready).
R4: 4/4 succeeded (gemini fell back to gemini-3-flash-preview on capacity, returned 0 findings + correct verification log incl. AdmissionMetrics 70/79 — its earlier misreads were the pro model). Each remaining finding single-reviewer; all 5 verified real on direct check. CONVERGED.

## Convergence judgement

R4 surfaces zero post-triage HIGH. The four R4 MEDIUMs are precision residue of R3's own fixes (date field name, heap-template hint) plus two pre-existing-environment/file items (HundredBot WSL2 gate, VALIDATION L84 dead branch) — all fixed in the R4 commit. Four-round trajectory 3→1→2→0 with each round's new findings increasingly self-inflicted-by-prior-fix rather than original-plan defects. **Plan 20-06 ready for `/gsd-execute-phase 20`.**
