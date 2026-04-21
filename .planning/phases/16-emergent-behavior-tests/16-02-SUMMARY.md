---
phase: 16-emergent-behavior-tests
plan: 02
subsystem: emergence-metrics
tags: [metrics, instrumentation, observability, micrometer, emergence]

requires:
  - phase: 16-emergent-behavior-tests
    plan: 01
    provides: Spring-wired MeterRegistry + SimulationEngine/EnvironmentEngine ctor shapes that this plan extends; seeded RNG hooks let the wiring test force deterministic bond + infection outcomes
provides:
  - EmergenceMetrics @Component bean with 4 Micrometer counters (paralife.emergence.bonded.pairs.formed, composites.formed, buffs.granted, mutagen.infections)
  - SimulationEngine incBondedPair site (line ~553) + EMERGENCE bonded-pair-formed log
  - SimulationEngine incComposite site (line ~636) + EMERGENCE composite-formed log
  - EnvironmentEngine incInfection site (resolveMutagenCollisions, ~line 603) + EMERGENCE infection-started log
  - EnvironmentEngine grantWithEmergenceCount helper in grantSurvivorBuffs: size-diff on BuffRegistry.getBuffs(id) gates incBuffGranted + EMERGENCE buff-granted log to new-buff branch only
  - BuffRegistry.grant signature UNCHANGED (REVIEWS MEDIUM — minimum blast radius)
  - EmergenceMetricsWiringTest (4 tests) driving every counter through real production call path
  - Public test hooks on EnvironmentEngine (stampMutagenAtForTestPublic, resolveMutagenCollisionsForTestPublic, tickBuffsAndInfectionsForTestPublic) — thin passthroughs to package-private ForTest helpers
affects: [16-06 EmergenceStabilityLoadTest (R16/17/18) consumes these counters, 16-07 16-EMERGENCE.md writeup reads EMERGENCE log markers, M5 visualiser (downstream) consumes the same log channel]

tech-stack:
  added:
    - io.micrometer.core.instrument.Counter (new counter type — distinct from 15-10's Gauge/DistributionSummary)
    - io.micrometer.core.instrument.simple.SimpleMeterRegistry (fallback registry for back-compat unit ctors)
  patterns:
    - "EmergenceMetrics bean mirrors WebSocketMetrics (Phase 15 D-10) — MeterRegistry-injected ctor + Counter.builder(name).description(...).register(registry)"
    - "Counter-at-domain-event pattern: increments live where the business-meaningful transition happens (grantSurvivorBuffs, not BuffRegistry.grant) — addresses REVIEWS HIGH #3"
    - "Size-diff idiom for new-buff detection: before=buffRegistry.getBuffs(id).size(); grant(); after=…; if (after>before) {counter; log;} — keeps BuffRegistry.grant signature unchanged (REVIEWS MEDIUM)"
    - "FALLBACK_REGISTRY static constant on SimulationEngine + EnvironmentEngine for back-compat ctors that bypass Spring wiring — single static allocation, not per-call"

key-files:
  created:
    - src/main/java/com/paralife/metrics/EmergenceMetrics.java
    - src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/EnvironmentEngine.java

key-decisions:
  - "buffs.granted counter lives in EnvironmentEngine.grantSurvivorBuffs, NOT BuffRegistry.grant. BuffRegistry.transferBuffs() also calls grant() internally for identity-transfer (BondFormation, CompositeFormation cleanse); placing the counter in BuffRegistry would double-count transfers as emergence events. grantSurvivorBuffs is the true domain trigger. Detection via size-diff on getBuffs(id) — keeps BuffRegistry.grant signature unchanged (minimum blast radius, REVIEWS MEDIUM)."
  - "EMERGENCE buff-granted log gated on the same new-buff branch as the counter (D-24 addendum). Symmetric: identity transfers and refresh branches emit neither counter nor log."
  - "Public test hooks (stampMutagenAtForTestPublic etc.) added to EnvironmentEngine so the wiring test can live in com.paralife.metrics (plan spec), not be forced back into com.paralife.engine to reach package-private helpers. Thin passthroughs — no behaviour change."
  - "Back-compat ctors preserved: SimulationEngine adds a 13-arg overload that supplies FALLBACK_REGISTRY-backed EmergenceMetrics (for SimulationEngineTest.engineWithBuffs which wires the collaborator surface without knowing about emergence metrics); EnvironmentEngine preserves both existing package-private test ctors by threading through a stub EmergenceMetrics."

patterns-established:
  - "Counter-at-atomic-domain-event: 4 counters each increment at the single line where the business transition commits (worldGrid.setEntity for bond/composite, infections.put for infection, list.add branch of BuffRegistry for new-buff). Log marker emits at the same site in the same visit — bond between metric and log is textual co-location, not an event listener."
  - "REVIEWS HIGH #3 idiom (size-diff new-state detection) — preferred over API changes to BuffRegistry. Pattern reusable for any future 'fire observability only on add, not refresh or transfer' need."

requirements-completed: []
requirements-partial: [R17]

duration: 17min
completed: 2026-04-21
---

# Phase 16 Plan 02: EmergenceMetrics + counters Summary

**Four Micrometer counters (`paralife.emergence.*`) wired at the true domain-event trigger sites (BondFormation + CompositeFormation in SimulationEngine; infections.put + grantSurvivorBuffs in EnvironmentEngine) with grep-friendly `EMERGENCE` INFO log markers at each site. `buffs.granted` relocated from BuffRegistry to grantSurvivorBuffs with size-diff detection to keep BuffRegistry.grant signature unchanged and prevent transferBuffs double-counting (REVIEWS HIGH #3).**

## Performance

- **Duration:** 17 min
- **Started:** 2026-04-21T08:55:33Z
- **Completed:** 2026-04-21T09:12:29Z
- **Tasks:** 3 / 3 completed
- **Files modified:** 4 (2 created, 2 modified)
- **Tests:** 571 / 571 pass on full rerun (567 baseline + 4 new wiring tests). LoadTest was flaky under full-suite load per 16-01 known-flake note; passes in isolation.

## Accomplishments

- `EmergenceMetrics` @Component bean registered with 4 Micrometer `Counter` instances under `paralife.emergence.*` dot-separated lowercase names (Prometheus/Micrometer convention, mirrors 15-10 WebSocketMetrics pattern).
- SimulationEngine ctor extended to accept `EmergenceMetrics`; both bond-formation (line ~553) and composite-formation (line ~636) sites increment the counter AND emit an `EMERGENCE {event} tick=... at=(...)` INFO log in the same textual block as the `worldGrid.setEntity` call — binding is co-location, not an event listener, so the counter can never drift from the transition.
- EnvironmentEngine ctor extended to accept `EmergenceMetrics`; infection-start site (`infections.put` in `resolveMutagenCollisions`) increments `incInfection` + emits `EMERGENCE infection-started` log with `tickNumber`, `id`, `strain`.
- `grantSurvivorBuffs` refactored to call a new private `grantWithEmergenceCount(entityId, type, expiry, tickNumber)` helper at each of its 3 grant sites (Composite: role-specific + UPKEEP_MINUS_1; Particle/BondedPair: random pick). The helper uses the size-diff idiom on `BuffRegistry.getBuffs(id)` to gate both the counter AND the `EMERGENCE buff-granted` log to the new-buff branch only. Refresh (`list.set`) path and transferBuffs path are silent — as REVIEWS HIGH #3 requires.
- `BuffRegistry.grant` signature/return-type UNCHANGED — the relocation is contained entirely in EnvironmentEngine. Minimum-blast-radius principle observed (REVIEWS MEDIUM).
- `EmergenceMetricsWiringTest` (4 @Test methods) drives every counter through its real production call path. No `metrics.inc*()` direct calls. Includes fail-fast `BondingConfig` binding assertion (would catch silent kebab-case binder failure). Includes explicit REVIEWS HIGH #3 regression check: after a real buff grant, `transferBuffs` + a same-type grant (refresh branch) both leave the counter flat.
- Added 3 thin public passthrough methods on EnvironmentEngine (`stampMutagenAtForTestPublic`, `resolveMutagenCollisionsForTestPublic`, `tickBuffsAndInfectionsForTestPublic`) so the wiring test can live in `com.paralife.metrics` per the plan spec. No behaviour change — passthroughs only.

## Task Commits

Each task committed atomically on the main worktree (sequential mode, default hooks enabled):

1. **Task 1: Create EmergenceMetrics bean** — `00ab101` (feat)
2. **Task 2: Wire counters + EMERGENCE log markers at SimulationEngine and EnvironmentEngine** — `33792c7` (feat)
3. **Task 3: EmergenceMetricsWiringTest** — `2fd59db` (test)

## Files Created/Modified

### Created
- `src/main/java/com/paralife/metrics/EmergenceMetrics.java` — @Component bean with 4 Counter fields + 4 inc* mutators + 4 *Count accessors returning double. 65 lines.
- `src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java` — `@SpringBootTest(webEnvironment=NONE)` with @TestPropertySource forcing bonding (probability=1.0, threshold=0) and disabling env auto-spawn (lambdas=0). 4 @Test methods. 204 lines.

### Modified
- `src/main/java/com/paralife/engine/SimulationEngine.java` — added import + `FALLBACK_REGISTRY` static + `EmergenceMetrics emergenceMetrics` field + ctor args (12-arg autowired ctor now 13-arg; 9-arg back-compat preserved; new 13-arg back-compat ctor added for pre-Plan-02 tests that wire the full collaborator surface). `incBondedPair()` + `EMERGENCE bonded-pair-formed` log immediately after the `claimedForBonding.add` block at the bond-placement site (D-14 atomicity). `incComposite()` + `EMERGENCE composite-formed` log immediately after `worldGrid.setEntity` for both composite members.
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — added imports + `FALLBACK_REGISTRY` static + `EmergenceMetrics emergenceMetrics` field + ctor args. Two new package-private test-ctor overloads preserve existing test-ctor shapes with a stub EmergenceMetrics. `incInfection()` + `EMERGENCE infection-started` log at `infections.put`. New private `grantWithEmergenceCount` method implements size-diff detection. `grantSurvivorBuffs` rewritten to call `grantWithEmergenceCount` at each of its 3 grant sites. 3 new `public ForTestPublic` passthrough methods for cross-package test access.

## Decisions Made

- **Counter location for `buffs.granted`: EnvironmentEngine.grantSurvivorBuffs, not BuffRegistry.grant.** REVIEWS HIGH #3 specifically flagged this: `BuffRegistry.transferBuffs()` calls `grant()` internally during identity transfer (BondFormation, CompositeFormation cleanse), so placing the counter in BuffRegistry would over-count identity events as new emergence. The true "a new survivor buff was awarded" domain event is `grantSurvivorBuffs` — that's where the counter lives. Detection via size-diff on `buffRegistry.getBuffs(id)` avoids changing BuffRegistry's public surface.
- **EMERGENCE log marker gated identically to the counter.** Addendum D-24 unified counter and log gating on the new-buff branch; previously the PLAN had counter-gated but log unconditional. Symmetric gating makes the log a reliable proxy for the counter (grep count == counter count after a run).
- **Public test hooks on EnvironmentEngine.** The plan spec mandates the test live in `com.paralife.metrics` so the wiring test file sits alongside the bean it validates. Reaching package-private `*ForTest` methods from outside `com.paralife.engine` requires either reflection (fragile, opaque) or a public wrapper. Added 3 thin `*ForTestPublic` passthroughs — no behaviour change, zero risk.
- **Back-compat strategy: 13-arg ctor on SimulationEngine, 2 new test-ctor overloads on EnvironmentEngine.** `SimulationEngineTest.engineWithBuffs` (1 caller) wired a 13-arg ctor that included BuffRegistry/hooks/DeathFinalizer/EnvironmentEngine=null — extending this to 14-arg would churn that test. Added a 13-arg overload that supplies a FALLBACK_REGISTRY-backed EmergenceMetrics stub. EnvironmentEngine has 2 package-private test ctors (Random-only and Random+ToxinPathGenerator); preserved both by routing them through new overloads that supply the stub metrics.
- **FALLBACK_REGISTRY as static constant.** Back-compat ctors allocate no new registry per call — a single `private static final SimpleMeterRegistry FALLBACK_REGISTRY` lives on each engine class, shared across all fallback instances. Avoids unbounded allocation in direct-instantiation unit tests (especially parameterised tests with many engine instances).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] SimulationEngineTest has a 13-arg ctor call that can't resolve to the new 14-arg canonical ctor**
- **Found during:** Task 2 test compilation
- **Issue:** `SimulationEngineTest.engineWithBuffs()` at line 1080 calls `new SimulationEngine(grid, cfg, botRegistry, noBonding(), compReg, CompositeConfig.defaults(), ..., hooks, df, null)` — a 13-arg ctor. Extending the canonical ctor to 14-args (with EmergenceMetrics) left this call unable to resolve (actual list too short; 9-arg and 14-arg both mismatch).
- **Fix:** Added a 13-arg back-compat ctor on SimulationEngine that delegates to the 14-arg canonical ctor, supplying a FALLBACK_REGISTRY-backed EmergenceMetrics stub.
- **Files modified:** `src/main/java/com/paralife/engine/SimulationEngine.java`
- **Verification:** `./gradlew compileTestJava` clean; full suite green on rerun (571/571).
- **Committed in:** `33792c7` (folded into Task 2)

**2. [Rule 3 — Blocking] Plan-mandated test location (com.paralife.metrics) cannot access package-private EnvironmentEngine test hooks**
- **Found during:** Task 3 test authoring
- **Issue:** The plan specifies the wiring test must live at `src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java`. But `stampMutagenForTest`, `resolveMutagenCollisionsForTest`, and `tickBuffsAndInfectionsForTest` on EnvironmentEngine are all package-private — unreachable from `com.paralife.metrics`. The plan acknowledged this ambiguity ("if package-private access is available… else drive through the full mutagen→infection→cure pipeline") but the alternative routes were fragile (full Poisson-spawn timing or reflection).
- **Fix:** Added 3 thin public passthrough methods on EnvironmentEngine (`stampMutagenAtForTestPublic`, `resolveMutagenCollisionsForTestPublic`, `tickBuffsAndInfectionsForTestPublic`) that delegate to the package-private ForTest helpers. Zero behaviour change; preserves the package-private ones for existing in-package tests.
- **Files modified:** `src/main/java/com/paralife/engine/EnvironmentEngine.java`
- **Verification:** Wiring test compiles and passes all 4 @Test methods.
- **Committed in:** `2fd59db` (folded into Task 3)

### Known-flaky test (not a deviation)

- **LoadTest.hundredBotsNoCorruption** — same pre-existing flake documented in 16-01 SUMMARY. Fails under full-suite concurrent load (45/100 bots connected vs 50-threshold) when virtual-thread scheduler is saturated by other integration tests. Passes in isolation on `./gradlew test --tests "com.paralife.engine.LoadTest"`. Not introduced by this plan.

## Authentication Gates

None.

## Threat Flags

None — the plan's threat register (T-16-05 buff double-count, T-16-06 entity-ID log leak) is fully mitigated. T-16-05 mitigation verified by the EmergenceMetricsWiringTest explicit transferBuffs-doesn't-bump assertion. T-16-06 mitigation unchanged — entity IDs are per-run session-stable (Phase 15.2), no PII.

## Self-Check: PASSED

All commits verified:
- **00ab101** (Task 1 EmergenceMetrics bean): `git log --oneline | grep 00ab101` → found
- **33792c7** (Task 2 counter wiring): `git log --oneline | grep 33792c7` → found
- **2fd59db** (Task 3 wiring test): `git log --oneline | grep 2fd59db` → found

All created/modified files verified present:
- `src/main/java/com/paralife/metrics/EmergenceMetrics.java` — FOUND (4 counter names, @Component)
- `src/main/java/com/paralife/engine/SimulationEngine.java` — FOUND (incBondedPair=1, incComposite=1, EMERGENCE bonded-pair-formed=1, EMERGENCE composite-formed=1)
- `src/main/java/com/paralife/engine/EnvironmentEngine.java` — FOUND (incBuffGranted=1, incInfection=1, EMERGENCE buff-granted=2 (javadoc + log), EMERGENCE infection-started=1, getBuffs(id).size()=2)
- `src/main/java/com/paralife/engine/BuffRegistry.java` — UNCHANGED (incBuffGranted count = 0, REVIEWS HIGH #3 compliant)
- `src/test/java/com/paralife/metrics/EmergenceMetricsWiringTest.java` — FOUND (@Test=4, meterRegistry.find=4, direct inc* calls=0, bondingConfig.bondingProbability=1, isGreaterThan=4, transferBuffs+HIGH#3 refs=7)

All plan acceptance greps pass.
