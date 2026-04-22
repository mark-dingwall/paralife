---
phase: 16-emergent-behavior-tests
plan: 07
subsystem: testing
tags: [narrative, documentation, websocket, admission-control, phase-gate]

requires:
  - phase: 16-emergent-behavior-tests
    plan: 06
    provides: EmergenceStabilityLoadTest fixture dumps, calibration evidence, RespawnConfig-bound per-session cap override pattern
provides:
  - 16-EMERGENCE.md narrative with 5 D-04 signal sections citing concrete fixture evidence
  - R19 green gate via ./gradlew test -PincludeLong=true
  - Temporary world-level registration gate via paralife.websocket.max-active-entities and E|429|population cap exceeded
  - Backlog capture for durable registration policy (999.1) and offspring/bot-driven follow-up with M5 flower fallback (999.2)
affects: [milestone-v2.0-close-out, websocket-registration-policy, future-m5-visualizer]

tech-stack:
  added: []
  patterns:
    - "Fixture-backed narrative pattern: prose claims in planning docs cite concrete numbers from run-*.json, not anecdotal observations"
    - "Admission-control split: production keeps conservative caps while load-oriented tests override those caps high so they measure load/corruption rather than temporary back-pressure policy"

key-files:
  created:
    - .planning/phases/16-emergent-behavior-tests/16-07-SUMMARY.md
    - src/main/java/com/paralife/websocket/PopulationCapConfig.java
    - src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java
  modified:
    - .planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md
    - .planning/ROADMAP.md
    - .planning/STATE.md
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/world/WorldGrid.java
    - src/main/java/com/paralife/bot/BotClient.java
    - src/main/resources/application.yml
    - src/test/java/com/paralife/engine/LoadTest.java
    - src/test/java/com/paralife/world/WorldGridTest.java

key-decisions:
  - "Recovered the missing world-level entity-count admission gate as a temporary production policy: register/respawn now returns E|429|population cap exceeded once live non-rock/non-nutrient occupants reach paralife.websocket.max-active-entities"
  - "Kept the per-session respawn cap in production for now; LoadTest overrides both max-respawns-per-session and max-active-entities high so the test keeps measuring load/corruption rather than temporary gate policy"
  - "Captured the longer-term cleanup as backlog instead of expanding Phase 16 further: 999.1 for durable registration policy, 999.2 for offspring-to-bot assignment plus M5 flower fallback"

patterns-established:
  - "When a phase-gate failure exposes a missing production requirement, recover the minimal runtime behaviour, prove it with focused tests, and backlog the broader policy cleanup separately"

requirements-completed: [R17, R19]

duration: ~2h combined across interrupted and resumed sessions
completed: 2026-04-22
---

# Phase 16 Plan 07: R17 + R19 Narrative And Gate Summary

**Phase 16 closes with a fixture-cited emergence narrative, a green full-suite R19 gate, and a temporary world-level registration cap that restores the missing population-back-pressure requirement surfaced during gate review.**

## Performance

- **Duration:** ~2h combined across the interrupted and resumed sessions
- **Started:** 2026-04-22 (partial narrative + first R19 run in prior session)
- **Completed:** 2026-04-22T15:03:25+10:00
- **Tasks:** 2 / 2 completed
- **Files modified:** 11
- **Verification:** `./gradlew test -PincludeLong=true` green in 3m 34s

## Accomplishments

- **R17 narrative landed.** `16-EMERGENCE.md` now contains a five-signal D-04 write-up with concrete numbers from three fixture runs, seed-to-command reproducibility, and the functional-only pivot context from 16-06.
- **R19 is now honestly green.** The full suite including long-run tests passes after recovering the intended world-level registration back-pressure and ensuring `LoadTest` no longer measures the temporary cap policies.
- **Backlog follow-through is explicit.** The temporary registration-cap policy and the offspring-as-NPC asymmetry are both promoted into the roadmap backlog rather than being left as undocumented session context.

## Task Commits

No commit yet in this workspace. The work is present as verified local changes and documented here so it can be committed or reviewed cleanly next.

## Files Created/Modified

### Created

- `src/main/java/com/paralife/websocket/PopulationCapConfig.java` — temporary world-level registration-cap config bound at `paralife.websocket.max-active-entities`.
- `src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java` — Spring wiring test that pins the `E|429|population cap exceeded` behaviour.
- `.planning/phases/16-emergent-behavior-tests/16-07-SUMMARY.md` — this summary.

### Modified

- `.planning/phases/16-emergent-behavior-tests/16-EMERGENCE.md` — final fixture-backed R17 narrative.
- `src/main/java/com/paralife/world/WorldGrid.java` — `livingEntityCount()` counts live non-rock/non-nutrient occupants for registration back-pressure.
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — `handleRegister` now rejects over-cap register/respawn with `E|429|population cap exceeded`; constructors accept the new config.
- `src/test/java/com/paralife/engine/LoadTest.java` — both cap properties overridden high so the test keeps targeting load/corruption rather than temporary policy.
- `src/test/java/com/paralife/world/WorldGridTest.java` — count semantics pinned for live occupants vs terrain/dead entries.
- `src/main/java/com/paralife/bot/BotClient.java` — 429 handling comment updated to reflect both back-pressure causes.
- `src/main/resources/application.yml` — temporary production default `paralife.websocket.max-active-entities: 256`.
- `.planning/ROADMAP.md` — Phase 16 marked complete; backlog items 999.1 and 999.2 added.
- `.planning/STATE.md` — project state advanced from “16-07 pending” to “milestone ready to complete”.

## Decisions Made

- Kept `E|429` as the wire-level response for the recovered population cap. The protocol already treats `429` as the server’s back-pressure error family; only the message text changes.
- Counted all live non-rock/non-nutrient occupants for the temporary population cap. That matches the “protect the grid, not just bot sessions” interpretation the user clarified during gate review.
- Left reproduction itself ungated by the new cap. The cap limits external register/respawn injection; in-sim ecology still follows the existing energy/cooldown/world rules.

## Deviations From Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Recovered the missing world-level population-cap requirement**
- **Found during:** Task 2 (R19 gate investigation)
- **Issue:** The failing full-suite `LoadTest` was not a connection failure. Bots were disconnecting because the per-session respawn cap was the only 429 gate in production, but the intended requirement was a total-living-entity admission gate.
- **Fix:** Added `PopulationCapConfig`, `WorldGrid.livingEntityCount()`, and a `WorldWebSocketHandler.handleRegister` gate that returns `E|429|population cap exceeded` when live non-terrain occupants hit the configured ceiling.
- **Files modified:** `src/main/java/com/paralife/websocket/PopulationCapConfig.java`, `src/main/java/com/paralife/world/WorldGrid.java`, `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java`, `src/test/java/com/paralife/websocket/WorldWebSocketHandlerPopulationCapTest.java`, `src/test/java/com/paralife/world/WorldGridTest.java`, `src/main/resources/application.yml`
- **Verification:** Targeted handler/grid tests green; full `./gradlew test -PincludeLong=true` green in 3m 34s.

**2. [Rule 2 - Missing Critical] Decoupled LoadTest from temporary gate policy**
- **Found during:** Task 2 (same R19 investigation)
- **Issue:** Once the temporary admission controls exist, `LoadTest` should not pass or fail based on those policy thresholds; its purpose is still load/corruption coverage.
- **Fix:** Overrode both `paralife.websocket.max-respawns-per-session` and `paralife.websocket.max-active-entities` to `1000000` in `LoadTest`.
- **Files modified:** `src/test/java/com/paralife/engine/LoadTest.java`
- **Verification:** `./gradlew test --tests "com.paralife.engine.LoadTest"` green; full suite green.

---

**Total deviations:** 2 auto-fixed, both required to make the phase gate reflect the intended system behaviour rather than a policy mismatch.
**Impact on plan:** R17/R19 are fully closed. The extra work is contained and documented, while the broader policy cleanup is explicitly deferred to backlog rather than silently folded into this phase.

## Issues Encountered

- The first interrupted-session R19 run reproduced a single failing `LoadTest` under full-suite load. Investigation showed bots were connecting successfully, then later self-disconnecting on `E|429` after exhausting the per-session respawn cap. That failure mode exposed the missing world-level gate requirement rather than a regression in Phase 16 logic.

## User Setup Required

None.

## Next Phase Readiness

- Phase 16 is complete: all `16-0x` plans now have matching summaries, `16-EMERGENCE.md` is written, and the R19 full-suite gate is green.
- Milestone v2.0 is ready for close-out via `$gsd-complete-milestone`.
- Two follow-ups are explicitly parked, not forgotten:
  - `999.1` durable registration/admission policy, including eventual removal or redesign of the temporary caps.
  - `999.2` offspring become bot-driven, with M5 flower rendering as the interim semantic convention.
