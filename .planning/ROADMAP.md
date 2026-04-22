# Paralife Roadmap

## Milestones

- ✅ **v1.0 Foundation & Living Simulation** — Phases 01-10 (shipped 2026-04-12)
- ✅ **v2.0 Combination & Emergence** — Phases 11-16 plus 15.1/15.2 follow-ups (shipped 2026-04-22)

## Archived Milestones

<details>
<summary>v1.0 — Foundation & Living Simulation</summary>

- Archive: `.planning/milestones/v1.0-ROADMAP.md`
- Audit: `.planning/milestones/v1.0-MILESTONE-AUDIT.md`

</details>

<details>
<summary>v2.0 — Combination & Emergence</summary>

- Archive: `.planning/milestones/v2.0-ROADMAP.md`
- Requirements: `.planning/milestones/v2.0-REQUIREMENTS.md`
- Audit: `.planning/milestones/v2.0-MILESTONE-AUDIT.md`

</details>

## Next Step

Start the next milestone with `$gsd-new-milestone`.

## Backlog

### Phase 999.1: Replace temporary WebSocket caps with durable registration policy (BACKLOG)
**Goal:** Replace the temporary `max-active-entities` gate and test-time cap overrides with a durable admission-control policy. Re-evaluate whether the per-session respawn cap should remain once world-level population back-pressure and future external-load handling are in place.
**Requirements:** TBD
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `$gsd-review-backlog` when ready)

### Phase 999.2: Offspring entities become bot-driven; M5 flower rendering fallback (BACKLOG)
**Goal:** Eliminate the current NPC offspring asymmetry by assigning spawned `child-*` entities to bots or bot-summoning infrastructure. Until that lands, treat unassigned offspring as flowers in the future M5 visualizer so their stationary / edible / ephemeral behavior reads intentionally.
**Requirements:** TBD
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `$gsd-review-backlog` when ready)
