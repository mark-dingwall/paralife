---
phase: 16-emergent-behavior-tests
plan: 01
subsystem: rng-determinism
tags: [rng, determinism, seeding, spring-configuration-properties, tdd-enablement]

requires:
  - phase: 14-environmental-rules
    provides: EnvironmentEngine seeded-Random ctor precedent (line 220) — the pattern this plan replicates across 6 more hot-path components
  - phase: 15-protocol-transport-overhaul
    provides: BotClient 6-arg ctor already accepting java.util.Random (D-09a) — Task 3 only needed to fix handleDeath:294 which bypassed the injected field
provides:
  - SimulationEngine.simRng + resetSeed() + 6 RNG sites rerouted (lines 238, 241, 446, 942, 1079, 1090)
  - ActionResolver.actionRng + resetSeed() + 3 RNG sites rerouted (lines 330, 361 shuffles + line 520 nextDouble)
  - CompositeEnergyDistributor.compositeRng via SplittableRandom.split() + resetSeed() (replaces XOR-magic derivation)
  - FertilityInitializer.fertilityRng + resetSeed() that re-runs seedPatches() so grid state is regenerated (REVIEWS HIGH #1)
  - Entity.BondedPair.formBond / hybridRate / bondDecayCost widened to RandomGenerator
  - WorldWebSocketHandler.spawnRng + SpawnConfig ctor-injection + resetSeed() (no @Value — matches project convention)
  - BotClient.handleDeath respawn jitter now draws from ctor-injected rng (D-09a finally honoured end-to-end)
  - SimulationConfig.seed + SimulationConfig.actionSeed (nullable Long fields at paralife.simulation.*)
  - FertilityConfig.seed (nullable Long at paralife.simulation.fertility.seed — CORRECT prefix per REVIEWS HIGH #5)
  - CompositeConfig.seed (nullable Long at paralife.composite.seed)
  - SpawnConfig @ConfigurationProperties record at paralife.simulation.spawn
affects: [16-02 EmergenceMetrics wiring, 16-05 CompositeFormationDeterminismTest (R15 — 3-run identity via resetSeed), 16-06 EmergenceStabilityLoadTest (master-seed propagation)]

tech-stack:
  added:
    - java.util.random.RandomGenerator (JDK 17+ super-type)
    - java.util.SplittableRandom (for uncorrelated derived sub-streams)
    - org.springframework.boot.context.properties.bind.ConstructorBinding
  patterns:
    - "Seeded-RNG pattern: `config.seed() == null ? new Random() : new Random(config.seed())` — mirrors EnvironmentEngine:220 precedent across 5 components"
    - "resetSeed() hook contract: every seeded component exposes a public method that re-initialises its Random from config — enables tests to reset RNG state between runs inside a single @Test method (where @DirtiesContext cannot fire)"
    - "@ConstructorBinding on canonical record ctor — disambiguates Spring's ctor selection when a record has both a canonical and convenience ctor"
    - "SplittableRandom.split() for derived streams — replaces XOR magic constants for deriving uncorrelated RNG streams from a master seed"
    - "RandomGenerator parameter type — JDK-standard widening that preserves existing ThreadLocalRandom callers (both implement RandomGenerator)"

key-files:
  created:
    - src/main/java/com/paralife/engine/SpawnConfig.java
  modified:
    - src/main/java/com/paralife/engine/SimulationEngine.java
    - src/main/java/com/paralife/engine/ActionResolver.java
    - src/main/java/com/paralife/engine/CompositeEnergyDistributor.java
    - src/main/java/com/paralife/engine/FertilityInitializer.java
    - src/main/java/com/paralife/engine/SimulationConfig.java
    - src/main/java/com/paralife/engine/FertilityConfig.java
    - src/main/java/com/paralife/engine/CompositeConfig.java
    - src/main/java/com/paralife/world/Entity.java
    - src/main/java/com/paralife/websocket/WorldWebSocketHandler.java
    - src/main/java/com/paralife/bot/BotClient.java
    - src/main/resources/application.yml

key-decisions:
  - "Use @ConstructorBinding on canonical record ctors to disambiguate Spring's ctor selection — convenience ctors are preserved for direct-instantiation tests but @ConstructorBinding routes Spring to the full canonical ctor for @ConfigurationProperties binding (this was the root cause of the initial 103-test failure cascade)"
  - "Preserve back-compat convenience ctors on SimulationConfig (7-arg), FertilityConfig (4-arg), CompositeConfig (14-arg), WorldWebSocketHandler (6-arg) — avoids churning every direct-instantiation test"
  - "BotClient default ctors now use `new Random()` instead of `ThreadLocalRandom.current()` — achieves acceptance-grep zero-TLR goal while keeping production unseeded semantics (new Random() uses System.nanoTime() internally, same-ish statistical behaviour as TLR for bots that don't care about seeding)"
  - "FertilityInitializer.resetSeed() MUST call seedPatches() — resetting the RNG field alone leaves the grid's prior-run nutrientLevel intact, which is the exact failure mode REVIEWS HIGH #1 identified"
  - "Use java.util.Random with `min + rng.nextInt(max-min+1)` instead of ThreadLocalRandom's 2-arg nextInt(origin, bound) — java.util.Random doesn't have the 2-arg overload in Java 21, so FertilityInitializer.seedPatches computes the bounded range explicitly"

patterns-established:
  - "Per-component seeded RNG + resetSeed() pair — the contract 16-05 (R15) and 16-06 (R16/17/18) rely on for deterministic multi-run tests"
  - "Config-record seed field uses nullable Long — null means unseeded (production), explicit value means seeded (tests); mirrors EnvironmentConfig.seed from Phase 14"

requirements-completed: [R15]

duration: 45min
completed: 2026-04-21
---

# Phase 16 Plan 01: RNG Injection & Seed Hooks Summary

**Ten hot-path server-side RNG draws refactored from ThreadLocalRandom / unseeded `new Random()` to constructor-injected seeded `Random` instances, with public `resetSeed()` hooks on every seeded component so 16-05's R15 test can prove determinism across three runs inside a single @Test method.**

## Performance

- **Duration:** 45 min
- **Started:** 2026-04-21T07:51:00Z
- **Completed:** 2026-04-21T08:18:59Z
- **Tasks:** 3 / 3 completed
- **Files modified:** 11 (1 created, 10 modified)
- **Tests:** 567 / 567 pass on full rerun (LoadTest historically flaky under full-suite CI load — see Deviations)

## Accomplishments
- Ten TLR / unseeded-new-Random() call sites across 6 production files converted to seeded injection — full RNG-audit coverage for the R15 tick-pipeline hot path per 16-RESEARCH.md §RNG Audit
- Four seeded components (SimulationEngine, ActionResolver, CompositeEnergyDistributor, FertilityInitializer, WorldWebSocketHandler) expose public `resetSeed()` — closes the REVIEWS HIGH #1 concern that `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` + `worldGrid.clear()` cannot reset bean-internal RNG state between the 3 runs in a single @Test method
- BondedPair.formBond / hybridRate / bondDecayCost signature widened to `java.util.random.RandomGenerator` — tests can pass a seeded Random without forcing every other caller to change (TLR still implements RandomGenerator, so no non-test call-sites break)
- CompositeEnergyDistributor uses `SplittableRandom.split()` to derive its sub-stream — replaces the XOR-magic `config.seed() ^ 0xC0FFEEL` pattern flagged in REVIEWS MEDIUM
- FertilityConfig seed at the CORRECT yaml prefix `paralife.simulation.fertility.seed` (not `paralife.world.fertility.seed` — that was the REVIEWS HIGH #5 bug)
- SpawnConfig introduced as `@ConfigurationProperties` record — replaces the `@Value` pattern floated in the prior planning round, matches project convention (CLAUDE.md §Spring patterns)
- BotClient.handleDeath:294 one-line bug fix: respawn jitter now draws from the ctor-injected `rng` field instead of `ThreadLocalRandom.current()` — D-09a is finally honoured end-to-end

## Task Commits

Each task was committed atomically:

1. **Task 1: Config record extensions — add seed fields, new SpawnConfig** — `c0943a4` (feat)
2. **Task 2: Inject seeded RNG + resetSeed() into engines + BondedPair.formBond widen** — `2ae221f` (feat)
3. **Task 3: WorldWebSocketHandler SpawnConfig + BotClient.handleDeath fix** — `a15c04e` (feat)

## Files Created/Modified

### Created
- `src/main/java/com/paralife/engine/SpawnConfig.java` — new `@ConfigurationProperties(prefix = "paralife.simulation.spawn")` record with a nullable `Long seed` field; auto-discovered by existing `@ConfigurationPropertiesScan` on ParalifeApplication.

### Modified
- `src/main/java/com/paralife/engine/SimulationConfig.java` — added `Long seed` + `Long actionSeed` fields, `@ConstructorBinding` on canonical ctor, back-compat 7-arg convenience ctor.
- `src/main/java/com/paralife/engine/FertilityConfig.java` — added `Long seed` field at the CORRECT `paralife.simulation.fertility.seed` prefix, `@ConstructorBinding`, back-compat 4-arg convenience ctor.
- `src/main/java/com/paralife/engine/CompositeConfig.java` — added `Long seed` field at `paralife.composite.seed`, `@ConstructorBinding`, back-compat 14-arg convenience ctor.
- `src/main/java/com/paralife/engine/SimulationEngine.java` — new `private Random simRng` field + `resetSeed()`; 6 TLR sites replaced (lines 238, 241, 446, 942, 1079, 1090); `simRng` threaded into the `BondedPair.formBond` call (the only caller).
- `src/main/java/com/paralife/engine/ActionResolver.java` — new `private Random actionRng` field + `resetSeed()`; `Collections.shuffle(resolvedList)` and `Collections.shuffle(resolvedCompositeList)` (previously no-arg, using `Collections.shuffle`'s internal JDK-shared Random) now take `actionRng`; `ThreadLocalRandom.current().nextDouble()` for bonus-offspring at line 520 → `actionRng.nextDouble()`.
- `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` — `private Random compositeRng` via `SplittableRandom.split()` + `resetSeed()`; line 77 shuffle now uses `compositeRng`. `SplittableRandom` + `java.util.Random` imports added; `ThreadLocalRandom` import removed.
- `src/main/java/com/paralife/engine/FertilityInitializer.java` — `private Random fertilityRng` + `resetSeed()` that calls the new package-private `seedPatches()` helper. The original `@PostConstruct`-annotated body was split into `initializeFertility()` (calls `seedPatches()`) and `resetSeed()` (resets RNG + re-runs `seedPatches()`). `ThreadLocalRandom`'s 2-arg `nextInt(origin, bound)` rewritten as `min + rng.nextInt(max-min+1)` for java.util.Random compatibility.
- `src/main/java/com/paralife/world/Entity.java` — `BondedPair.formBond`, `hybridRate`, `bondDecayCost` final param widened from `ThreadLocalRandom` to `java.util.random.RandomGenerator`. In-body `ThreadLocalRandom.current()` assignment in `formBond` removed (caller supplies).
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — ctor-injected `SpawnConfig spawnConfig` + `private Random spawnRng` + `resetSeed()`; line 191 TLR replaced with `spawnRng`. Back-compat 6-arg ctor preserved. `@Autowired` annotation on the new 7-arg ctor disambiguates Spring's ctor selection.
- `src/main/java/com/paralife/bot/BotClient.java` — `handleDeath()` at line 294 now uses `rng.nextLong(respawnJitterMs)` (the ctor-injected field, not `ThreadLocalRandom.current()`); 2-arg and 3-arg default ctors now supply `new Random()` (was TLR) so the acceptance grep for zero-TLR in BotClient is clean; `ThreadLocalRandom` import removed.
- `src/main/resources/application.yml` — nullable seed keys added under `paralife.simulation.{seed,action-seed}`, `paralife.simulation.fertility.seed`, `paralife.simulation.spawn.seed`, `paralife.composite.seed`. Production leaves them null = unseeded.

## Decisions Made

- **@ConstructorBinding on canonical record ctor.** When a record declares both a canonical ctor and a convenience ctor (for back-compat), Spring Boot's `@ConfigurationProperties` binder can't decide which to use and fails with `NoSuchMethodException: <init>()`. Annotating the canonical (compact) ctor with `@ConstructorBinding` disambiguates. This was the root cause of an initial 103-test failure cascade; the fix was a three-line diff across the three records.
- **BotClient defaults use `new Random()` not `ThreadLocalRandom.current()`.** The plan's acceptance grep demands zero TLR in BotClient.java, but the plan's prose says preserve TLR defaults for back-compat. Replacing `ThreadLocalRandom.current()` with `new Random()` at the two default ctor lines satisfies both: the grep is clean AND production bots still get unseeded randomness (just from `new Random()` which internally uses `System.nanoTime()` + counter). Statistical behaviour is indistinguishable for the load-test 100-bot use case.
- **FertilityInitializer: java.util.Random lacks 2-arg `nextInt(origin, bound)`.** ThreadLocalRandom has `nextInt(int, int)` but `java.util.Random` does not. Rewrote `rng.nextInt(min, max+1)` as `min + rng.nextInt(max-min+1)` which produces the same bounded range using the single-arg overload.
- **FertilityInitializer.resetSeed MUST re-run seedPatches.** The prior-round implementation reset the RNG field alone, leaving the grid's prior-run nutrientLevel intact. This is the exact failure mode REVIEWS HIGH #1 identified — `@PostConstruct` only fires once, so a bare `worldGrid.clear()` + RNG-reset does not regenerate patches. `resetSeed()` now explicitly calls `seedPatches()` so the grid starts from identical fertility state on every run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] Spring Boot @ConfigurationProperties ctor ambiguity after adding back-compat convenience ctors**
- **Found during:** Task 2 test run
- **Issue:** Adding a back-compat convenience ctor to each of SimulationConfig / FertilityConfig / CompositeConfig caused Spring Boot 3.4's `@ConfigurationProperties` binder to fail with `NoSuchMethodException: <init>()` during context startup — it couldn't pick between the canonical and convenience ctor. 103 integration tests failed (every `@SpringBootTest`).
- **Fix:** Added `@ConstructorBinding` to the canonical compact ctor of each record, plus the `import org.springframework.boot.context.properties.bind.ConstructorBinding` statement. Spring now unambiguously picks the full canonical ctor for config binding.
- **Files modified:** SimulationConfig.java, FertilityConfig.java, CompositeConfig.java
- **Verification:** All 567 tests green on rerun
- **Committed in:** `2ae221f` (folded into Task 2's commit)

**2. [Rule 1 — Bug] WorldWebSocketHandler ctor ambiguity after adding back-compat 6-arg ctor**
- **Found during:** Task 3 test run
- **Issue:** Adding the back-compat 6-arg ctor to WorldWebSocketHandler alongside the new 7-arg ctor triggered the same Spring selection failure. Every `@SpringBootTest` relying on a running WebSocket endpoint failed (103 again).
- **Fix:** Added `@org.springframework.beans.factory.annotation.Autowired` to the 7-arg ctor so Spring unambiguously picks it over the back-compat 6-arg ctor. Used the fully-qualified annotation name to avoid another import.
- **Files modified:** WorldWebSocketHandler.java
- **Verification:** Full suite green on rerun
- **Committed in:** `a15c04e` (folded into Task 3's commit)

**3. [Rule 1 — Bug] java.util.Random.nextInt(origin, bound) does not exist in Java 21**
- **Found during:** Task 2 compile of FertilityInitializer
- **Issue:** The plan's example code and the pre-existing FertilityInitializer body used `rng.nextInt(config.patchMinRadius(), config.patchMaxRadius() + 1)` — ThreadLocalRandom has this 2-arg overload but plain `java.util.Random` does not in Java 21. Direct replacement would not compile.
- **Fix:** Rewrote as `config.patchMinRadius() + rng.nextInt(config.patchMaxRadius() - config.patchMinRadius() + 1)` — produces the same bounded range using the single-arg `nextInt(bound)` available on both types. Guard for `patchMinRadius == patchMaxRadius` unchanged.
- **Files modified:** FertilityInitializer.java
- **Verification:** Compiles clean; FertilityInitializerTest (10 tests) all pass
- **Committed in:** `2ae221f`

### Known-flaky test (not a deviation)

- **LoadTest.hundredBotsNoCorruption** — historically flaky under full-suite CI load (documented in STATE.md and 16-PATTERNS.md line 310–321). Uses a loose 50%-bots-still-connected threshold that can fail when Jetty's virtual-thread scheduler is saturated by other integration tests running concurrently. Passes on `--rerun-tasks` consistently. Not introduced by this plan — pre-existing flake.

## Authentication Gates

None.

## Threat Flags

None — the plan's threat register (T-16-01, T-16-02) covers all risks this plan introduces. No new network surface, no new auth paths, no schema changes. Purely internal RNG plumbing plus nullable `@ConfigurationProperties` fields.

## Self-Check: PASSED

All commits verified:

- **c0943a4** (Task 1 config records): `git log --oneline | grep c0943a4` → found
- **2ae221f** (Task 2 engine RNG injection): `git log --oneline | grep 2ae221f` → found
- **a15c04e** (Task 3 WorldWebSocketHandler + BotClient): `git log --oneline | grep a15c04e` → found

All created/modified files verified present:

- `src/main/java/com/paralife/engine/SpawnConfig.java` — FOUND
- `src/main/java/com/paralife/engine/SimulationEngine.java` — FOUND (resetSeed count = 1, simRng count ≥ 1, zero TLR)
- `src/main/java/com/paralife/engine/ActionResolver.java` — FOUND (resetSeed count = 1, actionRng shuffles, zero TLR)
- `src/main/java/com/paralife/engine/CompositeEnergyDistributor.java` — FOUND (resetSeed count = 1, SplittableRandom count = 4, zero TLR, zero XOR magic)
- `src/main/java/com/paralife/engine/FertilityInitializer.java` — FOUND (resetSeed count = 1, seedPatches count = 4, zero TLR)
- `src/main/java/com/paralife/engine/SimulationConfig.java` — FOUND (Long seed, Long actionSeed)
- `src/main/java/com/paralife/engine/FertilityConfig.java` — FOUND (Long seed, correct prefix, zero wrong prefix)
- `src/main/java/com/paralife/engine/CompositeConfig.java` — FOUND (Long seed)
- `src/main/java/com/paralife/world/Entity.java` — FOUND (RandomGenerator param count = 3: formBond + hybridRate + bondDecayCost)
- `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java` — FOUND (resetSeed count = 1, SpawnConfig field present, zero @Value spawn-seed)
- `src/main/java/com/paralife/bot/BotClient.java` — FOUND (rng.nextLong(respawnJitterMs) present, zero TLR)
- `src/main/resources/application.yml` — FOUND (new seed keys present, zero wrong-prefix keys)

All plan acceptance greps pass.
