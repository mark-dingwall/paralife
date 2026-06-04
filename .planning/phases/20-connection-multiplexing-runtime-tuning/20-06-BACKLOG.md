# Plan 20-06 multi-review backlog / do-not-reflag

Loop target: no NEW HIGH+ post-triage. Subject: 20-06-PLAN.md (pre-execution, post-de-stale `3477d39`).

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

## Round fix indexes (do not re-raise)

- **R1** (this commit) → `20-06-MULTIREVIEW-R1.md`

## Reviewer health

R1: 4/4 succeeded — first codex success of the Phase 20 loops (gpt-5.5 pin per the gpt-5/ChatGPT-account 400 root-cause). Gemini produced full review but misread AdmissionMetrics line numbers (only reviewer to do so).
