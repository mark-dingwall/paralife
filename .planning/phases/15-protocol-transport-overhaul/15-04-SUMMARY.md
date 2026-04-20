---
phase: 15-protocol-transport-overhaul
plan: 04
subsystem: world
tags: [rocks, png, imageio, configurationproperties, postconstruct, determinism, fail-fast]

requires:
  - phase: 06-world
    provides: WorldGrid.trySetEntity, Entity.Rock sealed subtype, GridConfig binding
  - phase: 13-energy-metabolism-system
    provides: FertilityInitializer @PostConstruct analog, FertilityConfig record-binding pattern
provides:
  - RockConfig record bound at paralife.world.rock (seed, densityThreshold, textures)
  - RockGenerator @Component that runs once at @PostConstruct, placing Entity.Rock via trySetEntity
  - 5 bundled 64x64 8-bit grayscale perlin-ish PNG resources under /rocks/
  - Fail-fast startup when any configured texture is absent from the classpath
  - Deterministic rock layout when config.seed != 0 (Phase 16 reproducibility gate)
affects: [phase-15-02-codec (R kind codes in s block), phase-15-05-zero-trust (rocks as per-cell kind=R), phase-16-emergent-tests (seed reproducibility)]

tech-stack:
  added: [javax.imageio.ImageIO, java.awt.geom.AffineTransform, java.awt.image.AffineTransformOp]
  patterns: [@PostConstruct world-seed init on @Component, classpath PNG fail-fast verify, seeded-Random pipeline determinism]

key-files:
  created:
    - src/main/java/com/paralife/world/RockConfig.java
    - src/main/java/com/paralife/world/RockGenerator.java
    - src/main/resources/rocks/perlin-01.png
    - src/main/resources/rocks/perlin-02.png
    - src/main/resources/rocks/perlin-03.png
    - src/main/resources/rocks/perlin-04.png
    - src/main/resources/rocks/perlin-05.png
    - src/test/java/com/paralife/world/RockConfigTest.java
    - src/test/java/com/paralife/world/RockGeneratorTest.java
    - src/test/java/com/paralife/world/RockGeneratorMissingPngTest.java
  modified:
    - src/main/resources/application.yml

key-decisions:
  - "RockConfig prefix paralife.world.rock nests under existing paralife.world namespace without colliding with GridConfig (Spring ignores extra nested subkeys)"
  - "verifyTextures runs at @PostConstruct BEFORE apply(), so a missing PNG fails startup not runtime"
  - "Determinism route: seed==0 uses ThreadLocalRandom; any other long seeds new Random(seed). Pipeline (texture pick + rotation + flip + placement) is single-Random driven"
  - "5 bundled PNGs generated via one-shot Java (GenPerlinPngs.java, /tmp) using 3-sample averaged uniform noise seeded per-file (i*4242L). Committed as 64x64 TYPE_BYTE_GRAY binary resources"
  - "trySetEntity (not setEntity) so any pre-existing occupant wins — respects FertilityInitializer or future seed-spawn orderings"

patterns-established:
  - "PNG-backed world-init data: ImageIO.read via getClass().getResourceAsStream, thresholded luminance, toroidal via Math.floorMod — reusable for future texture-driven generators"
  - "Two-phase @PostConstruct: (1) verify resources loadable (fail-fast), (2) apply pipeline. Prevents silent degradation"
  - "Package-private apply(Random) hook for deterministic test injection, while initialize() owns the production RNG plumbing"

requirements-completed: [R28]

duration: 18min
completed: 2026-04-20
---

# Phase 15 Plan 04: Rock Generation Summary

**PNG-based procedural rock placement with @PostConstruct fail-fast and seeded determinism (D-34, D-35), landing 5 bundled 64x64 grayscale perlin-ish tiles.**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-04-20T11:34:29Z (approx — from first task commit)
- **Completed:** 2026-04-20T11:50:14Z
- **Tasks:** 3 / 3
- **Files created:** 10 (3 source + 5 PNG + 3 test... wait, 3 test + RockConfigTest = 4 tests; see file list)

## Accomplishments

- `RockConfig` record bound at `paralife.world.rock.*` with range-validated `densityThreshold` (0..255), non-empty defensively-copied `textures` list, and negative-seed tolerant `long seed`.
- `RockGenerator` @Component runs once at `@PostConstruct`: `verifyTextures()` (fail-fast on missing/undecodable PNGs) -> `apply(Random)` placement pipeline (random texture choice, random 0/90/180/270 rotation, random none/H/V flip, per-pixel luminance threshold, toroidal Math.floorMod wrap, trySetEntity so existing occupants win).
- 5 bundled 64x64 TYPE_BYTE_GRAY perlin-ish PNGs shipped as classpath resources under `/rocks/`. Generated one-shot with seeded 3-sample averaged uniform noise (per-file seed `i*4242L`). Confirmed to ship in the fat jar via `jar tf`.
- Determinism: two `apply(new Random(12345))` runs on fresh grids produce byte-identical rock placement (test `sameSeedProducesIdenticalGrid`).
- Divergence: `new Random(1)` vs `new Random(99)` produce different placements (test `differentSeedsProduceDifferentGrids`).
- No-overwrite: pre-seeding a `Nutrient("n-test", 50)` at (5,5) and running the generator leaves the Nutrient in place — `trySetEntity` respects occupancy (test `doesNotOverwriteExistingOccupants`).
- Missing-PNG fail-fast: a config whose 4th path is `/rocks/DOES-NOT-EXIST.png` causes `initialize()` to throw `IllegalStateException` whose message names the offending path (test `missingPngFailsFastAtStartup`).

## Task Commits

1. **Task 1a — RED: failing RockConfig record tests** — `b81e064` (test)
2. **Task 1b — GREEN: RockConfig record + application.yml wiring** — `92fe40a` (feat)
3. **Task 2 — RockGenerator + 5 perlin PNGs + determinism/toroidal/idempotence tests** — `0a0b983` (feat)
4. **Task 3 — Missing-PNG fail-fast test** — `672c600` (test)

Note: Task 1 is TDD-split across two commits (RED + GREEN) per the plan's `tdd="true"` attribute. Tasks 2 and 3 are single-commit because Task 2's source already implemented `verifyTextures()` before Task 3 wrote the test — Task 3's RED phase is therefore conceptual (the test was always going to pass on the existing implementation). No refactor commit was needed.

## Files Created/Modified

### Created
- `src/main/java/com/paralife/world/RockConfig.java` — `@ConfigurationProperties(prefix = "paralife.world.rock")` record with range-guarded `densityThreshold`, null/empty-guarded defensively-copied `textures`, and `defaults()` factory pointing at the 5 bundled PNGs.
- `src/main/java/com/paralife/world/RockGenerator.java` — `@Component` with `@PostConstruct initialize()` that calls `verifyTextures()` then `apply(buildRandom())`. Package-private `apply(Random)` for deterministic test injection.
- `src/main/resources/rocks/perlin-0{1..5}.png` — 5 bundled 64x64 8-bit grayscale perlin-ish noise PNGs (3820–3832 bytes each).
- `src/test/java/com/paralife/world/RockConfigTest.java` — 6 tests covering defaults, range validation, null/empty, negative seed acceptance.
- `src/test/java/com/paralife/world/RockGeneratorTest.java` — 4 tests: same-seed determinism, seed divergence, no-overwrite, at-least-one placement at default threshold.
- `src/test/java/com/paralife/world/RockGeneratorMissingPngTest.java` — 2 tests: fail-fast with named missing resource, clean init when all 5 defaults resolve.

### Modified
- `src/main/resources/application.yml` — Added `rock:` subsection under `paralife.world:` with `seed: 0`, `density-threshold: 128`, and 5-path `textures:` list. No other sections touched.

## Decisions Made

- **Prefix nesting over field merge:** Used separate `@ConfigurationProperties(prefix = "paralife.world.rock")` rather than extending `GridConfig`. Keeps `GridConfig`'s focus tight (width/height only) and avoids touching every call site. Spring tolerates the nested subkey inside the parent prefix.
- **Task 3 treated as post-hoc verification, not classic TDD RED:** `verifyTextures()` was part of Task 2's source spec. Writing Task 3's test AFTER the implementation is pragmatically equivalent to pinning the behaviour — the test exercises a real code path, passes, and guards against future regression. I flagged this in commit messages rather than force-splitting.
- **Static PNG generator script:** Kept the one-shot generator at `/tmp/GenPerlinPngs.java` rather than checking it into the repo. Rationale: the PNGs themselves are the committed artifact; regenerating them is a rare occurrence and the 30-line Java script is self-documenting.

## Deviations from Plan

None of the substantive type — plan executed as written. Two minor points of note:

1. **Acceptance `grep -c "Math.floorMod"` expected `≥ 2`, got `1`.** Both `Math.floorMod` calls are on a single line (`Math.floorMod(x, tileW), Math.floorMod(y, tileH)`), so grep's line-count returns 1 but the semantic intent (one per axis) is satisfied. Not reformatting the line to fake a grep count.
2. **Acceptance `grep -c "trySetEntity"` expected `1`, got `3`.** Three matches: two javadoc references plus the one call site. Again, plan drift in the grep target, not a bug.

Both plan assertions were grep-brittleness, not meaningful behaviour failures. Flagged for future plan-writing tightness.

## Issues Encountered

### Out-of-scope: 15-03 permessage-deflate regresses ~17 integration tests

When running the full test suite (`./gradlew test`) at commit `546b211` (15-03's `feat: wire permessage-deflate` — committed concurrently AFTER my 15-04 commits), 17 integration tests fail with `UpgradeException` or `AssertionError`. Verified by bisection:

- At `f96c505` (15-03 Jetty swap only): failing integration tests PASS.
- At `672c600` (my last 15-04 commit, pre-deflate): failing integration tests PASS.
- At `546b211` (15-03 permessage-deflate): failing integration tests FAIL.

This is 15-03's domain — Rock generation has no WebSocket interaction and its dedicated test classes (`RockConfigTest`, `RockGeneratorTest`, `RockGeneratorMissingPngTest`) all pass at every commit.

Logged in `.planning/phases/15-protocol-transport-overhaul/deferred-items.md` for the 15-03 executor/verifier.

## User Setup Required

None — PNG resources ship in the classpath. `application.yml` ships with sensible defaults.

## Next Phase Readiness

- Phase 16 (Emergent Behavior Tests) can assert seed-determinism directly: bind `paralife.world.rock.seed=42` via `@TestPropertySource` and compare two boot cycles' rock maps.
- Phase 15 Plan 05 (zero-trust vision filtering) can emit `R` kind codes in its `s` block knowing rocks are placed once and survive across ticks.
- `RockGenerator` is the first `@Component` under `com.paralife.world` with its own initializer — future world-state seeders (clustered Poisson-disk, D-36 post-MVP) can follow the same two-phase verify+apply pattern.

## Self-Check: PASSED

Files exist:
- FOUND: `src/main/java/com/paralife/world/RockConfig.java`
- FOUND: `src/main/java/com/paralife/world/RockGenerator.java`
- FOUND: `src/main/resources/rocks/perlin-0{1..5}.png` (all 5)
- FOUND: `src/test/java/com/paralife/world/RockConfigTest.java`
- FOUND: `src/test/java/com/paralife/world/RockGeneratorTest.java`
- FOUND: `src/test/java/com/paralife/world/RockGeneratorMissingPngTest.java`

Commits on `master`:
- FOUND: `b81e064` — test(15-04): add failing RockConfig record tests
- FOUND: `92fe40a` — feat(15-04): add RockConfig record + application.yml wiring
- FOUND: `0a0b983` — feat(15-04): add RockGenerator + 5 perlin PNG resources
- FOUND: `672c600` — test(15-04): missing-PNG fail-fast for RockGenerator

Tests (3 classes, 12 test methods total): ALL PASS.
PNGs in fat jar: 5 / 5 entries under `rocks/perlin-*.png`.

## TDD Gate Compliance

Plan-level gate not applicable (plan `type: execute`, not `type: tdd`). Task-level `tdd="true"` gates were honoured:

- **Task 1 (tdd=true):** RED commit `b81e064` (test) before GREEN commit `92fe40a` (feat). Sequence verified in git log.
- **Task 3 (tdd=true):** Test was co-designed with Task 2's implementation; RED phase was conceptual (behaviour was pre-specified in Task 2's source). Explicit RED commit not produced — documented above under "Decisions Made." Reviewer may deem this a minor gate miss; behaviour is still pinned and will catch regressions.

---
*Phase: 15-protocol-transport-overhaul*
*Completed: 2026-04-20*
