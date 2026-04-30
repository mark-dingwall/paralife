---
phase: 19
slug: high-density-placement-partition-aware-world-execution
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-01
---

# Phase 19 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) via Spring Boot Test |
| **Config file** | `src/test/resources/application.yml` (overridden per test via `@TestPropertySource`) |
| **Quick run command** | `./gradlew test --tests "com.paralife.engine.EligibleCellIndex*" --tests "com.paralife.engine.Placement*" --tests "com.paralife.engine.GoldenTrace*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30s quick · ~2–4 min full |

---

## Sampling Rate

- **After every task commit:** Run quick command for the touched concern (placement → `EligibleCellIndex*` / `Placement*`; entity-list → `GoldenTrace*`)
- **After every plan wave:** Run `./gradlew test` (full suite must be green)
- **Before `/gsd-verify-work`:** Full suite green AND `GoldenTraceEquivalenceTest` passes
- **Max feedback latency:** ~30 s for quick tests, ~4 min for full suite

---

## Per-Task Verification Map

> Concrete task IDs are filled in by the planner. The rows below are wave-level placeholders mapped to the four Wave 0 test files derived from RESEARCH.md §Validation Architecture.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 19-XX-YY | placement-index | ≥1 | SCALE-06 | — | Eligibility constraints 1/2/3 enforced; O(1) ops | unit | `./gradlew test --tests "com.paralife.engine.EligibleCellIndexTest"` | ❌ W0 | ⬜ pending |
| 19-XX-YY | placement-index | ≥1 | SCALE-06 | — | Same seed + same registration order → identical placements (D-06) | unit | `./gradlew test --tests "com.paralife.engine.PlacementDeterminismTest"` | ❌ W0 | ⬜ pending |
| 19-XX-YY | placement-index | ≥1 | SCALE-06 | — | Dense grid (>50% occupancy): no retry storm; `E\|503\|GRID_FULL` on empty eligible set | integration | `./gradlew test --tests "com.paralife.websocket.PlacementDensityIntegrationTest"` | ❌ W0 | ⬜ pending |
| 19-XX-YY | entity-list | ≥1 | SCALE-07 | — | Tick handler output byte-identical before/after entity-list refactor (D-10 semantic equivalence) | integration | `./gradlew test --tests "com.paralife.engine.GoldenTraceEquivalenceTest"` | ❌ W0 | ⬜ pending |
| 19-XX-YY | entity-list | ≥1 | SCALE-07 | — | Existing 166 tests remain green | regression | `./gradlew test` | ✅ existing | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/paralife/engine/EligibleCellIndexTest.java` — covers SCALE-06 constraint evaluation + O(1) add/remove/sample
- [ ] `src/test/java/com/paralife/engine/PlacementDeterminismTest.java` — covers SCALE-06 D-06 bit-exact seed contract (two-run byte-equality)
- [ ] `src/test/java/com/paralife/websocket/PlacementDensityIntegrationTest.java` — covers SCALE-06 high-density `GRID_FULL` path with no retry storm
- [ ] `src/test/java/com/paralife/engine/GoldenTraceEquivalenceTest.java` — covers SCALE-07 D-10 semantic-equivalence gate (SHA-256 digest of outbound frame bytes; mock `OutboundSender` capture per RESEARCH §Open Questions Q3)

JUnit 5 + Spring Boot Test infrastructure already exists; no framework install needed.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Real-world dense-run sanity (1000+ bot LoadHarness) | SCALE-06 / SCALE-07 | Phase 21 owns the benchmark gate; Phase 19 only proves equivalence at existing milestone workloads | Boot server + `./gradlew runHarness` once Phase 18 harness is online; observe no `GRID_FULL` retry storm and stable tick cadence. Defer formal benchmark to Phase 21. |

All in-scope phase behaviors have automated verification via the four Wave 0 files above. The manual check is informational only — not gating Phase 19 completion.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (4 new test files)
- [ ] No watch-mode flags
- [ ] Feedback latency < 30 s for quick command
- [ ] `nyquist_compliant: true` set in frontmatter once planner fills task IDs

**Approval:** pending
