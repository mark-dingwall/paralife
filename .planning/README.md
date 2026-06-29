# `.planning/` — frozen GSD corpus (historical)

**Status: FROZEN as of 2026-06-29.** This directory is the archived planning
corpus from Paralife's GSD (Get Shit Done) era. It is kept committed and
readable as **historical reference** — phase plans, decision registers, research,
reviews, milestone audits — but is **no longer maintained**. New work does not
write here.

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

## What's where (orientation)

See `/CLAUDE.md` §"Planning Artifacts Guide" for the full map. In brief:

- `PROJECT.md` / `ROADMAP.md` / `REQUIREMENTS.md` / `STATE.md` — GSD1 top-level ledgers (frozen)
- `MILESTONES.md` + `milestones/` — per-milestone audits & archives
- `phases/01–22` (+ `999.x`) — per-phase directories
- `codebase/` — `ARCHITECTURE.md` / `STRUCTURE.md` / `STACK.md` / `INTEGRATIONS.md` (⚠️ partly stale)
- `.gsd/` (sibling dir) — older GSD2 artefacts (M001/M002)
