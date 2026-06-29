# `.planning/` — frozen GSD corpus (historical)

**Status: FROZEN as of 2026-06-29.** This directory is the archived planning
corpus from Paralife's GSD (Get Shit Done) era. It is kept committed and
readable as **historical reference** — phase plans, decision registers, research,
reviews, milestone audits — but is **no longer maintained**. New work does not
write here.

> **Agents: read for facts, never for patterns.** Mine these files for *what* was
> decided and *why* a value is what it is — never copy their structure, headings,
> naming, or the phase / PLAN / SUMMARY / VERIFICATION process onto new work. Those
> are retired GSD conventions and do not represent how this repo is built now. Live
> conventions live in `/CLAUDE.md` and `docs/`. If anything here disagrees with the
> code or `/CLAUDE.md`, the code and `/CLAUDE.md` win.

This is a deliberate *graduation* off GSD (the same path sibling project
Guestflow took): keep the habits, drop the machinery. The per-phase
PLAN/SUMMARY/VERIFICATION ceremony and the live STATE/ROADMAP ledgers stop here.
Nothing is deleted — git history and these docs remain the record of how v1.0–v3.0
were built.

## Read these for accurate current state instead

- **`/CLAUDE.md`** — authoritative project overview, architecture, conventions,
  testing philosophy. Kept current; the `.planning/codebase/*` analyses here are
  partly stale (cross-check against code).
- **Git history** — the real record of what shipped.

## Carried forward (NOT frozen)

The corpus contains one live thing the freeze must not bury: the **open
tech-debt / backlog register** in `STATE.md` (~22 `| open |` rows as of the
freeze — TD-19.5-A, TD-17-A/B, TD-20-01c-A..F, TD-22.1-A..E, TD-PR2-A..E,
BL-EMERGENCE-SURVIVAL, the Phase 18 UAT smoke, et al.). Those items are still
real. They carry forward and will relocate to the graduated workflow's backlog
once that workflow is defined. Until then, `STATE.md`'s open rows remain editable
for the sole purpose of **closing** items as they're resolved — not for resuming
GSD phase cadence.

## Extracted living contracts (moved OUT of this corpus)

Four capability contracts were authored inside phase folders but are **live** (referenced from
source code and kept current). On 2026-06-29 they were extracted to `docs/`; tombstones mark their
old paths. They are **not** frozen — maintain them at the new location:

| Was | Now |
|-----|-----|
| `phases/15-protocol-transport-overhaul/15-SCHEMA.md` | `docs/SCHEMA.md` (wire protocol) |
| `phases/17-durable-admission-control-backpressure/17-ADMISSION.md` | `docs/ADMISSION.md` (admission / backpressure / resume-token FSM) |
| `phases/18-external-load-harness-harness-identity/18-HARNESS.md` | `docs/HARNESS.md` (load harness + connection model) |
| `phases/20-connection-multiplexing-runtime-tuning/20-RUNTIME.md` | `docs/RUNTIME.md` (per-connection tuning) |

## Planning Artifacts Guide (the provenance archive)

This project carries artifacts from two project-management tools due to a mid-project migration.
Everything below is **frozen historical context**.

### GSD2 artifacts (`.gsd/`)

Used for M001 (Foundation) and initial M002 tracking. **Only a markdown subset is retained** — the
live GSD2 SQLite DB (`gsd.db`), `state-manifest.json`, `event-log.jsonl` are **not present**
(defunct/gitignored). What remains:
- **`DECISIONS.md`** — Decision register (D001–D005), markdown projection of the GSD2 `decisions` table.
- **`milestones/`** — Archived milestone hierarchy (`M00x-ROADMAP.md`, `M00x-SUMMARY.md`, slice/task plans).

### GSD1 artifacts (`.planning/`)

Adopted after the 2026-04-11 migration; the planning corpus from M002 onward (milestone v3.0).
- **`PROJECT.md`** — vision, core value, milestone. **`ROADMAP.md`** — phase checklist (v1.0–v3.0).
  **`STATE.md`** — session-continuity state + the open tech-debt/backlog register (see §Carried forward).
  **`REQUIREMENTS.md`** — v3.0 `SCALE-01..10`. **`MILESTONES.md`** — milestone archive index.
  **`config.json`** — workflow prefs.
- **`codebase/`** — `ARCHITECTURE.md` / `STRUCTURE.md` / `STACK.md` / `INTEGRATIONS.md` (⚠️ partly
  stale — e.g. `ARCHITECTURE.md` lists 3 of 5 `Entity` permits; cross-check against code).
- **`DOC-RECONCILIATION-FINDINGS.md`** — prior doc-vs-code audit (2026-04-20).
- **`phases/01–22` (+ `999.x`)** — per-phase directories. Phases 01–05 (GSD2-era) have only
  `CONTEXT.md`; later phases add `PLAN.md`, `SUMMARY.md`, research/review/task breakdowns.
- **`milestones/`** — per-milestone audits & roadmaps.

### Migration notes
- M001 phases (01–05) were managed by GSD2 — archived under `.gsd/milestones/`.
- M002 phases (06–10) have artifacts in both systems (GSD2 markdown + GSD1 plans/summaries under `phases/`).
- `.planning/REQUIREMENTS.md` is the v3.0 requirements doc; M001/M002 (GSD2) had none — GSD2 used
  `success_criteria` JSON in its milestones DB.

### Known tech debt (v1.0 milestone audit) — all resolved (re-confirmed against code 2026-06-23)

| Phase | Item | Status |
|-------|------|--------|
| 06 | `Cell.nutrientLevel` was inert | ✅ now written by `EnvironmentEngine`/`FertilityInitializer`, read as fertility multiplier in `SimulationEngine` |
| 09 | `HeuristicBrain.predatorType` dead-branch ternary | ✅ unconditional `myType.predator()` |
| 09 | `BotClient` used raw `JsonNode`/`LinkedHashMap` | ✅ now uses `com.paralife.codec` (`Frame` + `PerceptionCodec`) |
| 10 | `LoadTest` omitted `nutrient-consume-energy` | ✅ set explicitly in `LoadTest` |
