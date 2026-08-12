# Environment Defects E-1 / E-2 / E-5 / E-7 / E-8 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make toxin decay to zero, bound and age the mutagen bloom, stop the brain emitting a verb
the resolver discards, and make lightning visible on the observer at its true size.

**Architecture:** Five independent defects found by the observer visual passes and recorded in
`BACKLOG.md` §"Environment persistence defects". Four need production changes; E-5 needs only proof
(see Task 1). Each is a local change behind an EARS clause with a RED-first test. No new subsystems,
no new packages.

**Tech Stack:** Java 21 / Spring Boot 3.4.4 / JUnit 5 + AssertJ (`./gradlew test`); Node 22 built-in
test runner for the observer renderer (`./gradlew jsTest`, bound to `check`).

## Global Constraints

- **Mechanism, not emergence.** Every clause here is deterministic and pinnable. Do **not** add any
  default-suite assertion on a population statistic, coverage share, cell count, rate, or any
  predicate derived from one (`CLAUDE.md` §label vs count). E-3 (bloom raggedness) and E-9
  (extinction ordering) are explicitly **out of scope** and get no test.
- **Assert against independent constants.** Expected values are hand-computed literals whose inputs
  the test owns, or read back from a config accessor. Never recompute an expectation by calling the
  production function under test.
- **Every negative assertion needs a positive control.** A "does not happen" assertion is vacuous
  without a sibling proving the same harness produces the thing under the opposite input.
- **RED-first is not optional.** Each task records the ACTUAL observed failure message from step 2
  in its commit body. A gate never shown to fire is theatre.
- **Test placement mirrors source.** `src/test/java/com/paralife/<pkg>/` for Java,
  `src/test/js/` for renderer modules. Match the package-local assertion library: the
  `com.paralife.engine` and `com.paralife.observer` tests use AssertJ; `src/test/js` uses
  `node:assert/strict`.
- **Config keys are kebab-case in `application.yml`, camelCase on the record.** Every new record
  component gets a compact-constructor validation line in the style already present.
- **Spotless gate.** `./gradlew spotlessCheck` ratchets from `origin/main`; run `spotlessApply`
  before committing if it complains.

---

## Task 1: E-1 — toxin decays strictly to zero (and E-5 falls out of it)

**Why this also closes E-5.** E-5's only in-scope decider was "the `TOXIN_PRESENT` bit is never set
for the residual field". That residual field exists *because* of E-1. The projection contract itself
is already pinned by `ToxinTest.java:239-250` (intensity 50 sets the bit, intensity 5 does not), and
the chosen scope was "fix the bit only" — so **E-5 requires no production change**. What it requires
is proof that the sub-threshold band is transient rather than permanent, which is clause EARS-2
below. Record this in the scope-diff at PR time; it is deliberate under-delivery, not an omission.

**EARS clauses:**

- **EARS-1** — WHEN `diffuseStep` runs with `decayRate > 0` over a source grid whose maximum
  intensity is ≥ 1, THE SYSTEM SHALL produce a destination grid whose maximum intensity is strictly
  less than the source maximum.
- **EARS-2** — WHEN a toxin field receives no further deposits, THE SYSTEM SHALL reach all-zero in
  a bounded number of ticks, re-arming `advanceToxin`'s idle short-circuit
  (`nonZeroToxinCellCount == 0`).

**Why `Math.floor` is sufficient and no `self - 1` fallback is needed.** Let `M` be the source
maximum. For any cell, `mixed ≤ M`. So `after = floor(mixed × (1 − d)) ≤ floor(M − dM)`. For `d > 0`
and `M ≥ 1`, `M − dM < M`, and the floor of a value strictly below the integer `M` is at most
`M − 1`. The maximum therefore drops by ≥ 1 every tick and the field is empty within ≤ 255 ticks.
`Math.round` fails exactly here: `round(0.9 × v) == v` for every `v` in 1..5.

**Confirmed empirically before planning.** A faithful simulation of `diffuseStep` on a 64×64 torus
with the real production parameters (`diffusionRate 0.5`, `decayRate 0.1`, `threshold 1`,
`radius 3` — `EnvironmentEngine.java:467-468`), one 255 stamp, both rounding modes:

| mode | outcome |
|---|---|
| `round` | never clears — 49 cells stuck at intensity 1 at tick 400 |
| `floor` | field fully clear at **tick 7** |

That reproduces the BACKLOG's 49-cell stain exactly. Quote it in the Task 1 commit body.

Edge case, not a blocker: the proof needs `M × (1 − d) != M` in double arithmetic, i.e.
`decayRate ≳ 2⁻⁵³`. `Toxin`'s compact constructor admits any `decayRate` in `[0, 1]`, so a
pathological `1e-20` is bindable. Irrelevant at `decay-rate: 0.1`; do not add a guard for it.

**Files:**
- Modify: `src/main/java/com/paralife/engine/CellularAutomaton.java:61` (the decay expression) and
  its class javadoc formula at `:13-16`
- Test: `src/test/java/com/paralife/engine/CellularAutomatonTest.java` (EARS-1)
- Test: `src/test/java/com/paralife/engine/ToxinTest.java` (EARS-2)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no signature change. `diffuseStep`'s parameter list and return value are unchanged —
  only the rounding mode inside it moves. Later tasks do not depend on this task.

**Pre-verified: the existing suite survives this change.** Every current `CellularAutomatonTest`
expectation was checked by hand against `floor`. `diffuseStepAppliesDecayRate` (100 × 0.5 → 50) is
exact under both modes; every other test uses `decayRate = 0.0` and asserts `isGreaterThan` /
`isEqualTo(0)` bounds that `floor` still satisfies. If any of them goes red, stop and report — it
means the analysis above is wrong, not that the test needs updating.

- [ ] **Step 1: Write the failing EARS-1 test in `CellularAutomatonTest`**

Test-owned inputs so the literal stays valid under any production retuning:

```java
// Contract: strict descent of the grid maximum whenever decayRate > 0.
// Fixture: uniform 5x5 plateau at intensity 3 — the exact band Math.round pins
// (round(0.9*v) == v for v in 1..5). diffusionRate 0.5 keeps it locally uniform,
// so diffusion cannot mask the decay by importing higher neighbours.
// Hand-computed: uniform plateau on a torus => neighbourAvg == 3 => mixed == 3
//   => round(2.7) == 3 (RED) vs floor(2.7) == 2 (GREEN). Every input is
//   test-owned — grid, intensity, rates, threshold — so no production retuning
//   can invalidate the literal.
// Add the sharpest single-cell case too: v = 1, where floor(0.9) == 0 but
//   round(0.9) == 1. One line, and it states the defect exactly.
// Assert: max(dst) < max(src) after one step, and 0 after a bounded loop.
// Positive control: the SAME fixture with decayRate 0.0 must hold at 3 —
// otherwise a change that simply erodes every grid would pass vacuously.
```

- [ ] **Step 2: Run it and record the real failure**

Run: `./gradlew test --tests 'com.paralife.engine.CellularAutomatonTest' > /tmp/red1.log 2>&1; echo "EXIT=$?"`

Do **not** pipe through `tail` — `tail` launders the exit code and reports its own status.
Expected: FAIL. Paste the actual AssertJ message into the commit body.

- [ ] **Step 3: Change the rounding mode**

One expression at `CellularAutomaton.java:61`: `Math.round` → `Math.floor` (with the existing
`(int)` cast). Update the class javadoc formula block at `:13-16` to state the decay step floors,
and why: floor guarantees monotonic descent, round has integer fixed points at low intensities.

- [ ] **Step 4: Run the whole CA suite plus the toxin suite**

Run: `./gradlew test --tests 'com.paralife.engine.CellularAutomatonTest' --tests 'com.paralife.engine.ToxinTest' > /tmp/green1.log 2>&1; echo "EXIT=$?"`
Expected: PASS, including all pre-existing tests.

- [ ] **Step 5: Write the failing EARS-2 test in `ToxinTest`**

```java
// Contract: an undisturbed toxin field returns to all-zero, re-arming the idle
// short-circuit. This is the clause that closes E-5 — the sub-threshold band
// (1 .. intensityThreshold-1) must be transient, not a permanent stain.
// Fixture: stampToxinIntensityForTest(pos, 255), then advanceToxinForTest in a
//   loop with NO active toxin event so nothing re-deposits.
// Assert: within a bounded loop cap, nonZeroToxinCellCountForTest() == 0.
//   The cap is a LOOP BOUND, not a tuned expectation — assert termination, never
//   "cleared in exactly N ticks" (that magnitude moves with decay-rate tuning).
//   500 is safe: from 255 at diffusionRate 0.5 the max collapses in well under 60.
// Positive control 1: assert nonZeroToxinCellCountForTest() > 0 BEFORE the loop,
//   so a stamp that silently failed cannot make this pass for the wrong reason.
// Positive control 2, and the one that matters: assert the field is STILL
//   non-empty after the FIRST tick. Without it, "zero the whole grid whenever
//   activeToxin == null" passes EARS-2 outright while being no fix at all.
//   EARS-1 also discriminates that, but it lives in another file — keep the
//   discrimination inside the clause that closes E-5. Safe by the empirical
//   table above: floor needs 7 ticks on 64x64, so tick 1 is comfortably non-empty.
```

Seams (all verified present): `stampToxinIntensityForTest(Position, int)`
`EnvironmentEngine.java:1604`, `advanceToxinForTest(long)` `:1695`, `nonZeroToxinCellCountForTest()`
— already asserted on at `ToxinTest.java:208, 218-232`. Note the accessor's `ForTest` suffix.

**Add one property to `ToxinTest`'s `@TestPropertySource` block:**
`paralife.simulation.events.toxin.decay-rate=0.1`. The block currently sets `diffusion-rate`,
`diffusion-radius` and `intensity-threshold` but **not** `decay-rate`, so it silently inherits the
production default — and EARS-2 is true for any `decayRate > 0` and false at `0`, making the
clause's own precondition untest-owned. Pinning it locally satisfies the "test owns its inputs"
rule. It changes no existing assertion in the file (they all stamp-and-resolve rather than diffuse);
confirm that by running the file before and after adding the key.

- [ ] **Step 6: Run it — it must pass now, and must have failed before Step 3**

Run the ToxinTest command from Step 4. To prove the gate is real rather than vacuous, temporarily
revert `floor` → `round`, watch EARS-2 go red, then restore. Record both outputs in the commit body.
This is the `CLAUDE.md` "gates are RED-first" rule applied to a test written after its fix.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/paralife/engine/CellularAutomaton.java \
        src/test/java/com/paralife/engine/CellularAutomatonTest.java \
        src/test/java/com/paralife/engine/ToxinTest.java
git commit   # body: EARS-1/EARS-2, both RED outputs, and the E-5 scope note
```

---

## Task 2: E-2a/b — bound the mutagen bloom and stop the cross-outbreak ratchet

**EARS clauses:**

- **EARS-3** — WHEN mutagen gossip runs, THE SYSTEM SHALL NOT colonize a cell whose toroidal
  Chebyshev distance from the active outbreak's `originCell` exceeds `max-radius`.
- **EARS-4** — WHEN a new outbreak begins while strain cells from an earlier outbreak remain, THE
  SYSTEM SHALL gossip only from cells colonized at or after the active outbreak's `spawnTick`.

Chebyshev, not Euclidean: gossip is an 8-neighbour Moore step, so Chebyshev is the metric the
propagation actually uses. Toroidal — wrap both axes with the `Math.floorMod` idiom already used
throughout `advanceMutagen`.

**Files:**
- Modify: `src/main/java/com/paralife/engine/EnvironmentConfig.java:200-214` (the `Mutagen` record,
  currently 13 components) and its compact constructor
- Modify: `src/main/resources/application.yml:196-209` (the `mutagen:` block)
- Modify: `src/main/java/com/paralife/engine/EnvironmentEngine.java:581-593` (the gossip source loop
  and the per-neighbour colonization guard)
- Create: `src/test/java/com/paralife/engine/MutagenRadiusTest.java` (see the class-split note below
  — these gates cannot live in `MutagenTest`)

**Interfaces:**
- Consumes: `MutagenEvent(long spawnTick, Position originCell, int lifetimeTicks)` — already exists
  at `MutagenEvent.java:20` and already carries both values these clauses need. No change to it.
- Produces: `EnvironmentConfig.Mutagen` gains one component, `int maxRadius`, appended **last** so
  existing positional constructions in tests keep their argument order. Task 3 consumes the same
  record but adds no component of its own.

```java
// EnvironmentConfig.Mutagen — new final component and its validation line.
// Existing 13 components unchanged and in order; maxRadius is appended.
int maxRadius
// compact constructor, matching the style of the surrounding checks:
//   maxRadius <= 0  -> IllegalArgumentException("mutagen.maxRadius must be > 0: " + maxRadius)
```

```yaml
# application.yml, inside the existing mutagen: block
# 20 => a 41x41 Chebyshev patch, ~2.5% of the default 256x256 world.
# This is the knob that stops "bloom covers every cell".
max-radius: 20
```

- [ ] **Step 1: Add the config component and its default, with no behaviour change yet**

Add `maxRadius` to the record, the validation line, and the yaml key. There is **exactly one**
positional `new Mutagen(...)` in the repo — `EnvironmentConfig.java:255`, inside `Mutagen.defaults()`
— and **zero** in test code (tests configure via `@TestPropertySource`, and
`EnvironmentSnapshotTest:42` / `ObserverFrameBudgetScaleTest:39` just pass `d.mutagen()` through).
Update that one site.

- [ ] **Step 2: Run the full test suite to confirm the config addition alone is inert**

Run: `./gradlew test > /tmp/cfg.log 2>&1; echo "EXIT=$?"`
Expected: PASS. Because there is only the one constructor site, a *compile* failure here is
unlikely. The failure this step really catches is a **missing yaml key**: Spring then binds
`maxRadius` to `0`, the new compact-constructor check throws, and every `@SpringBootTest` fails at
context load. That presents as mass `ApplicationContextException`, not an obvious config error —
recognise it and check `application.yml` first.

Package-private `EnvironmentEngine` seams this task needs — all verified present, use them rather
than building a harness: `forceSpawnMutagenForTest(tick, origin, strain, lifetime)` `:1651`,
`stampMutagenForTest(pos, strain)` `:1633`, `setMutagenLastReinforcedTickForTest(pos, tick)` `:1675`,
`mutagenStrainAtForTest(pos)` `:1665`, `advanceMutagenForTest(tick)` `:1701`, `activeMutagenEvent()`
`:1660`.

- [ ] **Step 3: Write the failing tests**

**Put these in a NEW class, `MutagenRadiusTest`, not in `MutagenTest`.** `@TestPropertySource` is
class-level with no per-test override seam, so the new mutagen gates need property sets that cannot
coexist (this task wants `max-radius=3`; Task 3 wants `gossip-probability=0.0` while
`MutagenTest.java:50` pins `1.0`). Copy `MutagenTest`'s block (`:37-55`) and set:

```
paralife.world.width=16
paralife.world.height=16
paralife.simulation.events.mutagen.max-radius=3
paralife.simulation.events.mutagen.gossip-probability=1.0
paralife.simulation.events.mutagen.strain-mutation-chance=0.0
paralife.simulation.events.mutagen.zone-decay-ticks=500   # see below
```

**`zone-decay-ticks=500` is deliberate.** Task 3 makes the age-out sweep run every tick. At
`MutagenTest`'s value of 5 the EARS-3 control cell — "the diagonal at Chebyshev 3 is colonized" —
would be swept away five ticks after it colonizes, making the gate a race. Parking the value far
above the test's tick budget decouples this task from Task 3 entirely. That is why a separate class
is worth the churn rather than adding one key to `MutagenTest`.

**Leave `MutagenTest` itself untouched.** Its world is 16×16, so the maximum toroidal Chebyshev
distance is 8 and the production default `max-radius: 20` can never bind there. No existing test
changes behaviour.

**You already own a boundary gate for free.** `MutagenTest.java:206-219`
(`strainGossipPropagatesToMooreNeighbors`) force-spawns at tick 0 — so the origin's timestamp equals
`spawnTick` — and asserts all 8 neighbours colonize at tick 1. Write the source filter as `>` rather
than `>=` and the origin excludes itself, gossip never starts, and that pre-existing test goes red.
Do not weaken it.

**Leave `MutagenTest` itself untouched.** Its world is 16×16, so the maximum toroidal Chebyshev
distance is 8 and the production default `max-radius: 20` can never bind there. No existing test
changes behaviour.

```java
// EARS-3 — radius cap. TWO fixtures, because one cannot discriminate the metric.
//
// 3a. Chebyshev, not Euclidean.
// forceSpawnMutagenForTest(10L, (8,8), strain, 300); max-radius 3; gossip 1.0.
// Advance enough ticks that an uncapped front would pass distance 3 (>= 5 on a
//   16x16 world, where it would otherwise reach 8).
// Assert: EVERY non-zero cell is within Chebyshev 3 of origin.
// Positive control MUST BE THE DIAGONAL CORNER (11,11): Chebyshev 3 but Euclidean
//   4.24. An on-axis control like (11,8) is Euclidean 3 as well, so it is
//   colonized under BOTH metrics — a Euclidean cap would ship with both halves
//   green. Euclidean-<=3 is a strict subset of Chebyshev-<=3, so the invariant
//   half cannot catch it either. The diagonal is the only discriminator.
//
// 3b. Toroidal, not absolute.
// With origin (8,8) on a 16x16 world and radius 3, wrap never engages, so
//   Math.abs(a-b) and min(|a-b|, dim-|a-b|) are indistinguishable. Re-run with
//   origin (0,0): assert (15,15) — toroidal Chebyshev 1 — IS colonized, and
//   (4,0) — Chebyshev 4 — is not. A non-toroidal cap leaves (15,15) clean.

// EARS-4 — no cross-outbreak ratchet.
// THE FIXTURE MUST DEFEAT ITS OWN SIBLING GUARD. Both guards land in Step 5, so a
// legacy cell placed OUTSIDE max-radius has every neighbour rejected by the radius
// cap and the assertion passes green with the source filter deleted — vacuous.
// Place the legacy cell INSIDE the cap instead:
//   SPAWN AT A NON-ZERO TICK: forceSpawnMutagenForTest(10L, (8,8), strain, 300).
//   stampMutagenForTest(legacy=(11,8), strain)          // Chebyshev 3 — inside cap
//   setMutagenLastReinforcedTickForTest(legacy, 9L)     // AFTER the stamp
//   advanceMutagenForTest(11L)                          // EXACTLY ONE tick
// The origin's front reaches only Chebyshev 1 in one tick, so any colonization at
//   Chebyshev 2-3 can ONLY have come from the legacy cell.
// Assert: (10,7) (10,8) (10,9) (11,7) (11,9) — legacy's neighbours, all inside
//   the cap — are strain 0. A broken source filter colonizes them => RED.
//   ((12,*) are Chebyshev 4, rejected by the cap — do not assert on them, they
//   prove nothing about the source filter.)
// Positive control: the origin's own 8 neighbours ARE colonized. This also pins
//   the filter as >= spawnTick and not > : the origin's own timestamp EQUALS
//   spawnTick, so a strict > filter silently kills the entire bloom.
```

**Two fixture hazards that will cost an hour each if you hit them blind.**

1. **`stampMutagenForTest` RESETS the reinforcement tick to `0L`** (`EnvironmentEngine.java:1637`).
   Always call `setMutagenLastReinforcedTickForTest` *after* the stamp, never before — the stamp
   would silently undo it.
2. **Never spawn the outbreak at tick 0 in this test.** Combined with hazard 1, a legacy cell would
   carry timestamp `0` equal to `spawnTick` `0`, pass the `>= spawnTick` filter, and the gate would
   fail for a reason that looks nothing like its cause. Spawn at tick 10.

Both assertions are structural (a named coordinate is or is not colonized), not statistical — no
cell counts, shares, or densities.

**RED-test each guard independently.** After Step 5, delete the radius cap alone and confirm EARS-3
goes red while EARS-4 stays green; restore; delete the source filter alone and confirm the reverse.
A guard never shown to fire on its own is theatre.

- [ ] **Step 4: Run and record the real failures**

Run: `./gradlew test --tests 'com.paralife.engine.MutagenTest' > /tmp/red2.log 2>&1; echo "EXIT=$?"`
Expected: FAIL on both. Paste both actual messages into the commit body.

- [ ] **Step 5: Implement both guards**

Two additions inside `advanceMutagen`'s active branch:

- **Source filter (EARS-4)** — in the outer scan at `:581-583`, skip any non-zero cell whose
  `mutagenLastReinforcedTick[x][y]` is earlier than `activeMutagen.spawnTick()`. That timestamp is
  already maintained and is stamped once at colonization (`:592`'s `existingStrain != 0` guard
  short-circuits before the write at `:603`), so it is exactly "which outbreak colonized this cell".
- **Radius cap (EARS-3)** — in the per-neighbour loop at `:586-593`, reject the neighbour when its
  toroidal Chebyshev distance from `activeMutagen.originCell()` exceeds `cfg.maxRadius()`. Toroidal
  distance per axis is `min(|a-b|, dim - |a-b|)`; the cell distance is the max of the two axes.

  **Placement is RNG-stream-critical, not a style choice.** The check must go **after** both random
  draws — the gossip-probability roll (`:591`) and the strain-mutation roll (`:595`) — immediately
  before the write to `mutagenGridNext[nx][ny]`. Hoisting it above the probability roll is the
  obvious optimisation (why draw for a neighbour you will reject?) and it is wrong here: it removes
  draws from the shared `EnvironmentEngine` `rng`, shifting every subsequent draw and moving the
  spawn ticks of toxin, mutagen **and** lightning. Leave the wasted draw in place and say why in a
  comment, or the next reader will "fix" it.

- [ ] **Step 6: Run the mutagen suite, then the full suite**

Run: `./gradlew test --tests 'com.paralife.engine.MutagenRadiusTest' > /tmp/green2.log 2>&1; echo "EXIT=$?"`
then `./gradlew test > /tmp/full2.log 2>&1; echo "EXIT=$?"`

**Two integration tests are expected to be the ones that move, and the response is decided in
advance — do not improvise it at this step.** Neither sets a world size, and there is no
`application-test.yml` under `src/test/resources`, so both run on `application.yml`'s **256×256**,
where `max-radius: 20` confines a bloom to a 41×41 patch — about 2.6% of the grid:

| test | assertion at risk |
|---|---|
| `EnvironmentPhaseGateIntegrationTest:127-129` | `getMutagenInfectionEventCount() > 0` over 300 ticks |
| `EnvironmentPhaseGateIntegrationTest:138-141` | `maxBuffsObserved > 0` — the mutagen survivor path, downstream of the same event |

A bloom on 2.6% of the world is much less likely to reach a seeded particle. The EARS-4 source
filter independently shifts the RNG stream (skipping a source cell removes its 8 draws), so these
counters will move once regardless and need a deliberate re-baseline.

**If they go red: raise `mutagen.peak-lambda` in that class's `@TestPropertySource`.** That class
already documents its lambdas as "NOT production values — forces event firing within 300-tick
window" (`:68-75`), so raising it further is exactly in keeping with its design. Do **not** raise
`max-radius` for the test — that disables the behaviour this task exists to add. If lambda alone
will not do it, move those two assertions to `@Tag("slow")` and say so in the commit body; they are
threshold assertions over emergent quantities and are firewall-questionable as default gates
anyway (`CLAUDE.md` §label vs count). Never weaken the cap to make a test pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/paralife/engine/EnvironmentConfig.java \
        src/main/java/com/paralife/engine/EnvironmentEngine.java \
        src/main/resources/application.yml \
        src/test/java/com/paralife/engine/MutagenRadiusTest.java
git commit   # body: EARS-3/EARS-4, both RED outputs, and the two
             #       independent guard-deletion RED-tests
```

---

## Task 3: E-2c — the bloom ages out on a rolling schedule instead of vanishing in one tick

**The defect.** Zone decay only runs in the `activeMutagen == null` branch
(`EnvironmentEngine.java:612-626`). Colonization timestamps are frozen at first touch, so by the
time a 300-tick outbreak ends every cell is far older than `zone-decay-ticks: 50` — and the entire
field clears on the first idle tick. That is the single-frame disappearance the operator saw.

**EARS clause:**

- **EARS-5** — WHEN an active mutagen outbreak is in progress, THE SYSTEM SHALL clear any strain
  cell whose colonization tick is older than `zone-decay-ticks`, on the same schedule as when no
  outbreak is active.

**Behaviour change to expect.** Not a clean annulus. A cell cleared by the sweep has
`existingStrain == 0` on the next tick, so any live neighbour re-colonizes it at `gossip-probability`
per neighbour and stamps a *fresh* timestamp (`:602-603`). The outward ring is always younger than
the ring it just cleared, so it continuously reseeds inward. Expect a **bounded, mottled, churning
disc** — not a hollow ring and not a centre burnout. `zone-decay-ticks` therefore is not cleanly a
"ring-thickness" knob; treat it as a churn-rate knob and judge it by eye on the visualiser.

**The E-2c fix still lands regardless of that shape.** What changes is the *distribution* of
colonization timestamps: today they are uniformly ancient by the time a 300-tick outbreak ends, so
every cell clears on the same idle tick. With rolling decay they are staggered, so the field
recedes progressively instead of vanishing in one frame. That is the whole point of the task.

Arithmetic for scale, not for a test: a frontier cell typically has ~3 colonized neighbours, so
per-tick colonization is `1 − 0.7³ ≈ 0.66`, and radius 20 is reached in roughly 30 ticks.

**Perf note for the commit body:** the O(w·h) sweep now runs every tick rather than only on idle
ticks — ~65k extra reads/tick at the default 256×256, on top of the existing gossip scan.
Negligible, but say it rather than let a reviewer discover it.

**Files:**
- Modify: `src/main/java/com/paralife/engine/EnvironmentEngine.java:565-627` (`advanceMutagen` —
  lift the decay sweep out of the `else`)
- Create: `src/test/java/com/paralife/engine/MutagenZoneDecayTest.java` (see the class-split note
  below — this gate cannot live in `MutagenTest`)

**Interfaces:**
- Consumes: nothing from Task 2. `maxRadius()` exists by now but is deliberately unused here — the
  gate sets `gossip-probability=0.0`, which removes the need for any spatial isolation and keeps
  this task genuinely independent of the radius cap.
- Produces: no signature change.

**Run this task after Task 2.** Not a code dependency — both edit `advanceMutagen`, so sequencing
avoids a pointless conflict.

- [ ] **Step 1: Write the failing test**

**Put this in a NEW class, `MutagenZoneDecayTest`.** Copy `MutagenTest`'s whole class header
(`MutagenTest.java:37-55`) — annotations included — then change only
`paralife.simulation.events.mutagen.gossip-probability` to `0.0`, keeping `zone-decay-ticks=5`.
Copying rather than hand-writing is the instruction: the block carries
`paralife.tick.auto-start=false`, and omitting that leaves the tick loop running live against the
world under test, which presents as an intermittent failure that looks like a logic bug.

**Why `gossip-probability=0.0`.** This is not a
style preference — under `MutagenTest`'s class-level `gossip-probability=1.0` the test *cannot* go
green after the fix. A cell the sweep clears has `existingStrain == 0` on the next tick, so any live
neighbour re-colonizes it with a fresh timestamp; on the 16×16 test world at p=1.0 the front covers
everything within ~8 ticks. Setting gossip to 0.0 makes the decay sweep the only mechanism that can
clear a cell, which is exactly the gate under test.

```java
// EARS-5 — decay runs during an active outbreak.
// Fixture: gossip-probability = 0.0 (class-level), zone-decay-ticks = 5.
// forceSpawnMutagenForTest(0L, origin, strain, 300) — lifetime is a PARAMETER, so
//   it is test-owned; it must far exceed zoneDecayTicks so the whole window under
//   test sits strictly INSIDE the active period.
// stampMutagenForTest(target, strain) THEN setMutagenLastReinforcedTickForTest(target, 0L)
//   — that order matters: the stamp resets the reinforcement tick to 0L
//   (EnvironmentEngine.java:1637), so setting it first would be silently undone.
// Advance past zoneDecayTicks via advanceMutagenForTest.
//
// Assert: target is strain 0, AND activeMutagenEvent() != null at the moment of
//   assertion — without that second half the test passes for the wrong reason if
//   the outbreak quietly expired and the OLD idle-only path did the clearing.
// Positive control: a second cell whose timestamp is within the last zoneDecayTicks
//   is still set, proving the sweep discriminates by age rather than clearing all.
```

With gossip at 0.0 the isolation problem disappears entirely, so no Moore-neighbourhood placement
constraint is needed and the gate cannot flake.

Package-private test seams that already exist on `EnvironmentEngine` — use these rather than
inventing a harness: `forceSpawnMutagenForTest(tick, origin, strain, lifetime)` `:1651`,
`stampMutagenForTest(pos, strain)` `:1633`, `mutagenStrainAtForTest(pos)` `:1665`,
`mutagenLastReinforcedTickForTest(pos)` `:1670`, `setMutagenLastReinforcedTickForTest(pos, tick)`
`:1675`, `advanceMutagenForTest(tick)` `:1700`, `activeMutagenEvent()` `:1660`.

- [ ] **Step 2: Run and record the real failure**

Run: `./gradlew test --tests 'com.paralife.engine.MutagenTest' > /tmp/red3.log 2>&1; echo "EXIT=$?"`
Expected: FAIL — the cell is still colonized. Paste the actual message.

- [ ] **Step 3: Run the decay sweep unconditionally**

Restructure `advanceMutagen` so the age-out sweep executes on every tick rather than only in the
`activeMutagen == null` branch.

**Placement is load-bearing, and not for the obvious reason.** A cell colonized this tick has
`tickNumber − lastReinforced == 0`, so it could never be aged out in the same tick either way. The
real constraint is the double buffer: the sweep reads and writes `mutagenGrid`, but the active
branch's gossip writes land in `mutagenGridNext` and only reach `mutagenGrid` at the
`System.arraycopy` swap (`:609-611`). **The sweep must therefore run after that swap, outside the
`if/else`** — placing it before would sweep a stale grid. Say this in the comment.

- [ ] **Step 4: Run the mutagen suite, then the full suite**

Run: `./gradlew test --tests 'com.paralife.engine.MutagenTest' > /tmp/green3.log 2>&1; echo "EXIT=$?"`
then `./gradlew test > /tmp/full3.log 2>&1; echo "EXIT=$?"`
Expected: PASS. If a pre-existing mutagen test breaks, read it before touching it — it may be
pinning the idle-only behaviour deliberately, in which case update it and say so in the commit body.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paralife/engine/EnvironmentEngine.java \
        src/test/java/com/paralife/engine/MutagenZoneDecayTest.java
git commit   # body: EARS-5, RED output, the churning-disc behaviour note,
             #       and the every-tick sweep perf note
```

---

## Task 4: E-7 — the brain stops emitting a verb the resolver discards

**The defect.** `ActionResolver.java:509-512` handles `case 'A'` by incrementing `restCount` and
nothing else — combat is a passive adjacency scan (`SimulationEngine.java:464-500`), so a solo
attack verb has nothing to resolve. `HeuristicBrain.java:194-201` emits `A` whenever prey sits at
distance 1. Every such tick the bot rests instead of acting.

**Chosen scope (user decision):** the brain stops emitting `A`. Making `A` a real bonus attack was
considered and rejected — it would layer a balance change on top of the untested E-9 hypothesis.
Leave `ActionResolver`'s `case 'A'` exactly as it is; it remains correct for the composite path.

**EARS clause:**

- **EARS-6** — WHEN `HeuristicBrain` selects a chase target that is adjacent, THE SYSTEM SHALL emit
  the move verb `M` toward it, never the solo attack verb `A`.

**Files:**
- Modify: `src/main/java/com/paralife/bot/HeuristicBrain.java:194-201` (the chase branch)
- Test: `src/test/java/com/paralife/bot/HeuristicBrainDeterminismTest.java` (or the sibling
  `HeuristicBrain*Test` that already owns chase behaviour — grep for `'A'` in
  `src/test/java/com/paralife/bot/` first and put the new test where chase is already covered)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no signature change. `Frame.ActionFrame(char verb, Optional<String> arg)` is unchanged;
  only which verb the chase branch selects moves.

- [ ] **Step 1: Find every existing test that asserts the brain emits `A`**

Run: `grep -rn "'A'" src/test/java/com/paralife/bot/`
Any test pinning "adjacent prey → `A`" is pinning the defect and must flip. List them in the commit
body — do not silently delete one.

- [ ] **Step 2: Write the failing test**

```java
// EARS-6 — adjacent prey draws a move, not the discarded attack verb.
// Fixture: a frame with exactly one prey-species entity at distance 1 in a known
// direction, energy high enough that no starvation branch pre-empts the chase.
// Assert: verb == 'M' and the direction argument is the numpad code toward that
//   prey — asserting the direction too, so "never emits A" cannot be satisfied by
//   a brain that stopped chasing altogether.
// Positive control: prey at distance 2 still yields 'M' toward it, proving the
//   chase branch is reached in both cases and only the verb changed.
// Run the adjacent case for TWO different directions (e.g. N and SE). A brain
//   that fell out of the chase branch into the fallback random walk still returns
//   'M', and could match one numpad code by luck — it cannot track the prey
//   across two.
```

- [ ] **Step 3: Run and record the real failure**

Run: `./gradlew test --tests 'com.paralife.bot.*' > /tmp/red4.log 2>&1; echo "EXIT=$?"`
Expected: FAIL with the verb assertion showing `A`. Paste the actual message.

- [ ] **Step 4: Remove the branch**

In the chase branch (`HeuristicBrain.java:196-201` — `:194` is target selection and stays), drop the
`adj` computation and always emit `M`. Replace the two-line comment about `A` with one line stating
why: solo `A` resolves to rest because combat is a passive adjacency scan, so emitting it costs the
bot its action. Delete the now-orphaned `adj` local — the build does **not** set `-Werror` and javac
will not fail on an unused local, so this is tidiness required by `CLAUDE.md` §Editing existing code,
not something a gate will catch for you.

- [ ] **Step 5: Run the bot suite, then the full suite**

Run: `./gradlew test --tests 'com.paralife.bot.*' > /tmp/green4.log 2>&1; echo "EXIT=$?"`
then `./gradlew test > /tmp/full4.log 2>&1; echo "EXIT=$?"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/paralife/bot/HeuristicBrain.java src/test/java/com/paralife/bot/
git commit   # body: EARS-6, RED output, and every flipped pre-existing test
```

---

## Task 5: E-8a — the observer wire carries each strike's radius

**The defect.** A strike damages a Euclidean disc of `outer-radius: 4` — exactly 49 cells
(hand-counted over `dx² + dy² ≤ 16`) — but the wire carries only its centre, so the renderer draws
**1/49** of the affected area.

**EARS clause:**

- **EARS-7** — WHEN a lightning strike is applied on a tick, THE SYSTEM SHALL carry that strike's
  outer radius alongside its centre coordinates in the observer world frame for that tick.

**Shape decision.** Per-strike `{x, y, radius}` rather than a frame-level `lightningRadius`. The
radius is config-global today, but a frame-level field is meaningless when the strike list is empty,
and per-strike keeps the value attached to the thing it describes. `schemaVersion` stays `1`:
existing consumers ignore an added key, exactly as the `mutated` field precedent at
`docs/SCHEMA.md` records.

**Files:**
- Modify: `src/main/java/com/paralife/engine/EnvironmentSnapshot.java` (the `lightning` component
  type and its javadoc)
- Modify: `src/main/java/com/paralife/engine/EnvironmentEngine.java:1083-1085` (the `snapshot()`
  construction — map centre + config radius)
- Modify: `src/main/java/com/paralife/observer/ObserverFrame.java:69,71` (`Coord` → `Strike`)
- Modify: `src/main/java/com/paralife/observer/ObserverFrameBuilder.java:95-96` (the mapping)
- Test: `src/test/java/com/paralife/engine/EnvironmentSnapshotTest.java:87-89, 108, 115, 120`
- Test: `src/test/java/com/paralife/observer/ObserverFrameBuilderTest.java:102-106, 115-116`

**Every site that fails to compile once `lightning` is `List<Strike>` — all four verified present:**

| site | current code | note |
|---|---|---|
| `EnvironmentSnapshotTest:89` | `.containsExactly(new Position(7, 8), new Position(9, 10))` | comparison |
| `EnvironmentSnapshotTest:108` | `List<Position> lightning = new ArrayList<>(List.of(new Position(3, 3)))` | construction |
| `EnvironmentSnapshotTest:115` | `lightning.add(new Position(9, 9))` | construction |
| `EnvironmentSnapshotTest:120` | `.containsExactly(new Position(3, 3))` | comparison |
| `ObserverFrameBuilderTest:105` | `List.of(new Position(5, 6), new Position(7, 8))` | construction |
| `ObserverFrameBuilderTest:116` | `.containsExactly(new ObserverFrame.Coord(5, 6), ...)` | comparison |

`EnvironmentSnapshotTest:94`'s `isEmpty()` assertion needs no change and must stay — it is the
positive control proving the list is not merely always populated.

**Orphaned import.** `EnvironmentSnapshot.java:3` imports `com.paralife.world.Position` solely for
the `lightning` component; remove it once the type changes. Do **not** remove it from
`EnvironmentSnapshotTest` — that file still uses `Position` at `:59`, `:60`, and `:136`.

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the JSON key names Task 6's renderer reads. Both sides must agree exactly:

```java
// com.paralife.engine.EnvironmentSnapshot — component type changes; the
// defensive List.copyOf in the compact constructor stays as-is.
public record EnvironmentSnapshot(List<EnvCell> toxin, List<EnvCell> mutagen,
                                  List<Strike> lightning, Set<String> infectedIds) {
    /** A strike centre and the outer radius of the disc it affected. */
    public record Strike(int x, int y, int radius) {}
}

// com.paralife.observer.ObserverFrame — Coord is used ONLY for lightning
// (verified: ObserverFrameBuilder.java:96 is its sole construction site),
// so it is replaced rather than added alongside.
public record Strike(int x, int y, int radius) {}
public record EnvDto(List<ToxinCell> toxin, List<MutagenCell> mutagen, List<Strike> lightning) {}
```

Resulting wire fragment — Task 6 reads exactly these key names:

```json
"env": { "toxin": [], "mutagen": [], "lightning": [ { "x": 12, "y": 40, "radius": 4 } ] }
```

`lightningStrikesThisTick` stays `List<Position>`; the radius is attached at `snapshot()` time from
`config.lightning().outerRadius()`. Do not thread a radius through `applyLightningAtInternal`.

- [ ] **Step 1: Write the failing tests on both sides of the seam**

```java
// EARS-7a, EnvironmentSnapshotTest — apply a strike at a known centre, then
// assert snapshot().lightning() contains exactly one Strike whose radius equals
// cfg.lightning().outerRadius(). Read the expectation back from the config
// accessor, never a hardcoded 4 — the magnitude is a tunable default.
// Positive control: the existing "no strike this tick -> empty list" assertion
// in this file must stay green, proving the list is not merely always populated.

// EARS-7b, ObserverFrameBuilderTest — assert the built frame's env.lightning()
// carries the same x, y AND radius through the DTO mapping, so a builder that
// drops the field cannot pass on the engine-side test alone.
// USE A RADIUS THAT IS NOT THE CONFIG DEFAULT — e.g. construct the snapshot's
// Strike with radius 7 and assert 7 comes back. Feeding it the default 4 lets a
// mapper that ignores the carried value and substitutes config.lightning()
// .outerRadius() pass both halves; a non-default value kills that.
```

Note the two sides pin different things on purpose: the engine-side test proves the radius is
*sourced* from config, the observer-side test proves it is *carried* rather than re-derived.

- [ ] **Step 2: Run and record the real failures**

Run: `./gradlew test --tests 'com.paralife.engine.EnvironmentSnapshotTest' --tests 'com.paralife.observer.ObserverFrameBuilderTest' > /tmp/red5.log 2>&1; echo "EXIT=$?"`
Expected: compile failure first (`Strike` does not exist), then assertion failures once it does.
Record whichever the run actually produced — a compile error is a legitimate RED here, but say so.

- [ ] **Step 3: Make the change across the four production files**

Follow the interface block above exactly. Update the javadoc on `EnvironmentSnapshot` — its current
text says "Lightning lists coordinates of strikes applied on this tick only" and must now say the
radius travels with each centre.

- [ ] **Step 4: Run both suites, then the full suite**

Run the Step 2 command, then `./gradlew test > /tmp/full5.log 2>&1; echo "EXIT=$?"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/paralife/engine/EnvironmentSnapshot.java \
        src/main/java/com/paralife/engine/EnvironmentEngine.java \
        src/main/java/com/paralife/observer/ObserverFrame.java \
        src/main/java/com/paralife/observer/ObserverFrameBuilder.java \
        src/test/java/com/paralife/engine/EnvironmentSnapshotTest.java \
        src/test/java/com/paralife/observer/ObserverFrameBuilderTest.java
git commit   # body: EARS-7 and the RED output
```

---

## Task 6: E-8b — the renderer holds each strike and paints its true disc

**The defect.** `EnvironmentEngine.java:370` clears `lightningStrikesThisTick` every tick, so a
strike appears in exactly one 500ms frame as a single 6px square on a 1537px canvas.

**EARS clauses:**

- **EARS-8** — WHEN a strike appears in a world frame at tick `T`, THE SYSTEM SHALL draw it on every
  frame from `T` through `T + LIGHTNING_TRAIL_TICKS − 1` inclusive, at monotonically decreasing
  opacity, and SHALL NOT draw it from `T + LIGHTNING_TRAIL_TICKS` onward.
- **EARS-9** — WHEN a strike is drawn, THE SYSTEM SHALL paint every cell whose Euclidean distance
  from the centre is ≤ its `radius`, with toroidal wrap.

Euclidean, not Chebyshev: `applyLightningAtInternal` skips a cell when
`Math.sqrt(dx*dx + dy*dy) > outer`, so the damaged region is a disc. Drawing a square would
misreport the mechanic.

**Files:**
- Create: `src/main/resources/static/observer-lightning.js`
- Create: `src/test/js/observer-lightning.test.js`
- Modify: `build.gradle.kts:202` — **`requiredJsTests` must gain `"observer-lightning.test.js"`.**
  Node exits 0 on a zero-match glob, so this list is the only thing stopping the new gate from
  passing vacuously if the file is renamed or lost. Omitting it is exactly the failure the list was
  written to prevent. It is easy to miss because it is in neither an obvious file nor an obvious
  step — add it in Step 3, alongside creating the module.
- Modify: `src/main/resources/static/observer-render.js:113-115` (the lightning draw block)
- Modify: `src/test/js/observer-render.test.js`
- Modify: `src/main/resources/static/observer.html` (own the trail instance across frames)
- Modify: `src/test/java/com/paralife/observer/ObserverPageServesTest.java` (the new module is
  served, and the page imports it — this file already pins the other three modules the same way)

**Interfaces:**
- Consumes: the wire shape from Task 5 — `env.lightning` is `[{x, y, radius}]`.
- Produces: a pure, frame-agnostic trail module. State lives in a closure the page owns, so
  `drawWorld` stays a function of its arguments and remains testable without a canvas.

```js
// observer-lightning.js — the whole exported surface.

/** Total frames a strike stays visible, COUNTING its arrival frame. */
export const LIGHTNING_TRAIL_TICKS = 6;

/** Base colour; the trail supplies the alpha. Matches the existing LIGHTNING_COLOR hue. */
export const LIGHTNING_RGB = [255, 255, 187];

/**
 * Age -> opacity. trailAlpha(0) === 1 EXACTLY (load-bearing: it routes the arrival
 * frame down the opaque `#ffb` path and keeps the four existing render gates green).
 * Opacity reaches 0 at LIGHTNING_TRAIL_TICKS so an expiring strike never pops.
 * Contract: strictly decreasing over age, in (0, 1] for every drawn age.
 */
export function trailAlpha(age)

/**
 * Closure over the strikes still in their trail window.
 *   record(tick, strikes)  strikes are [{x, y, radius}] from env.lightning
 *   active(tick)           -> [{x, y, radius, alpha}], newest first, expired dropped
 *
 * Dedupe key is (tick, x, y) — NOT tick alone. Frames are latest-wins and a slow
 * observer may re-render the same tick, so re-recording must be idempotent; but
 * the engine appends MULTIPLE strike centres in one tick, and keying on tick
 * alone would silently collapse two simultaneous strikes into one.
 */
export function createLightningTrail()

/**
 * Cell offsets of a Euclidean disc of the given radius, centred on (0, 0).
 * Contract: an offset is included iff sqrt(dx^2 + dy^2) <= radius — the exact
 * test applyLightningAtInternal uses. Includes (0, 0). Pure; no wrap applied
 * (the caller wraps against grid dims).
 */
export function discOffsets(radius)
```

`drawWorld`'s signature does not change. It reads the already-aged list off `state`:

```js
// observer-render.js — the lightning block becomes:
//   state.lightningTrail  ->  [{x, y, radius, alpha}], supplied by the page.
// Still gated by visible(layers, "lightning"), still painted last in the layer
// order. Cells wrap toroidally against state.grid before painting.
//
// BACK-COMPAT CONTRACT — both defaults are load-bearing, see below:
//   entry with no `alpha`   -> paint the literal LIGHTNING_COLOR ("#ffb")
//   entry with no `radius`  -> paint a single cell (radius 0)
// Falls back to state.env.lightning when state.lightningTrail is absent, so the
// module stays renderable from a bare frame.
```

**Why those two defaults are mandatory, not stylistic.** Four existing gates in
`observer-render.test.js` depend on them, and the fixture at `:114`/`:131` supplies
`lightning: [{x: 0, y: 2}]` — no `radius`, no `alpha`:

| line | gate |
|---|---|
| `:148-159` | layer-order test, finds the lightning op by `c.color === LIGHTNING_COLOR` |
| `:185` | per-layer coordinate check, same colour match |
| `:234` | layer-toggle matrix (`LAYER_COLOR_MATCH.lightning`), same colour match |
| `:294-295` | pins `LIGHTNING_COLOR === "#ffb"` |

**And there is a second, worse collision.** The toxin predicate in the same file is
`typeof c.color === "string" && c.color.startsWith("rgba(")` — at `:145` (layer-order), `:232` (`LAYER_COLOR_MATCH.toxin`) and `:288`
(`LAYER_COLOR_MATCH.toxin`). Toxin is the *only* layer currently emitting `rgba(`. If lightning
starts emitting `rgba(255, 255, 187, α)`, it also satisfies the toxin predicate, so the layer-order
gate mis-resolves `toxin` to a lightning call and the toggle matrix's collateral checks fire
spuriously. The danger is that an implementer "fixes" the red by loosening those predicates —
destroying the discrimination the gates exist for.

**Required resolution, so no existing test is touched:** emit the opaque literal `LIGHTNING_COLOR`
whenever `alpha` is absent **or equals 1**, and `rgba(...)` only for genuinely aged frames. Define
`trailAlpha(0) === 1` so the arrival frame takes the opaque path too.

**Be precise about which half does which job** — the two are easy to conflate, and conflating them
invites relaxing the wrong one later:

- **`alpha` absent ⇒ opaque** is what keeps the four existing gates green. Those fixtures build
  `state` literally (`:114`, `:131`) and never call `createLightningTrail`, so they travel the
  `state.env.lightning` fallback with no `alpha` at all.
- **`trailAlpha(0) === 1`** buys something different: visual consistency, so a strike looks
  identical whether or not the page supplies a trail. It does *not* protect the existing gates.

Both need their own gate, because each can be violated while the other holds — see Step 1.

If you find yourself editing `observer-render.test.js`'s toxin or lightning predicates, stop — the
contract above is wrong or unimplemented, and loosening the predicate hides the regression.

- [ ] **Step 1: Write `observer-lightning.test.js` against the contracts above**

```js
// EARS-8 gates:
//  - trailAlpha is strictly decreasing across 0..LIGHTNING_TRAIL_TICKS-1, and
//    every value is in (0, 1].
//  - assert.equal(trailAlpha(0), 1) EXACTLY. The range gate above is satisfied by
//    a trailAlpha returning 0.99 at age 0, which silently pushes every arrival
//    frame onto the rgba( path. One line, and it makes the contract real.
//  - a strike recorded at tick T is in active(T) and in
//    active(T + LIGHTNING_TRAIL_TICKS - 1), and absent from
//    active(T + LIGHTNING_TRAIL_TICKS). The "present" halves are the positive
//    control for the "absent" half.
//  - recording the SAME strike at the same tick twice yields one entry.
//  - TWIN GATE, and the one that matters: two DISTINCT strikes recorded at the
//    same tick yield TWO entries in active(T). Without it, an impl keyed on tick
//    alone passes the idempotence gate and silently drops simultaneous strikes —
//    which the engine really does produce (EnvironmentSnapshotTest:82-89 applies
//    two centres on one tick).
// EARS-9 gates:
//  - discOffsets(0) is exactly [[0, 0]].
//  - discOffsets(4) includes [4, 0] and [2, 2] (sqrt(8) <= 4) and excludes
//    [3, 3] (sqrt(18) > 4). Hand-computed literals — the geometry is fixed by
//    the engine's own distance test, not by a tunable default.
//  - every returned offset satisfies dx^2 + dy^2 <= radius^2.
```

- [ ] **Step 2: Run and record the real failure**

Run: `./gradlew jsTest > /tmp/red6.log 2>&1; echo "EXIT=$?"`
Expected: FAIL — module not found. Paste the actual message.

- [ ] **Step 3: Implement `observer-lightning.js` to the contract, and register it in `build.gradle.kts`**

Add `"observer-lightning.test.js"` to `requiredJsTests` (`build.gradle.kts:202`) in this same step.
Prove the preflight works before moving on: temporarily rename the test file, run `./gradlew jsTest`,
confirm it fails with "Missing required JS test file(s)", then restore. A preflight never shown to
fire is theatre.

- [ ] **Step 4: Run jsTest**

Run: `./gradlew jsTest > /tmp/green6.log 2>&1; echo "EXIT=$?"`
Expected: PASS.

- [ ] **Step 5: Wire it into `drawWorld` and the page, with a renderer test**

Add three gates to `observer-render.test.js`, using the existing fake-context harness (no canvas):

1. A strike with `radius: 1, alpha: 0.5` paints exactly 5 cells — the Euclidean disc of radius 1.
   **Give this its own fixture helper; do not reuse `paintedWorld()`.** That helper paints
   background, grid, rock, toxin, mutagen and entity fills into one `ctx.calls` array, and an
   alpha-bearing lightning entry emits `rgba(255, 255, 187, 0.5)` — which satisfies the existing
   *toxin* predicate `startsWith("rgba(")`. The back-compat contract protects the *old* fixtures
   (they carry no alpha); a new alpha-bearing fixture routed through `paintedWorld()` reintroduces
   the collision. Use a bare state with no toxin, and match on the full prefix
   `rgba(255, 255, 187,`.
2. The same strike paints nothing when `layers.lightning === false`. That is the positive/negative
   pair for the layer gate.
3. **EARS-9's toroidal wrap, which otherwise ships untested** — `discOffsets` is deliberately
   wrap-free and the caller wraps, so nothing in the module test can catch an unwrapped caller.
   Strike at `(0, 0)` with `radius: 1` **and `alpha: 0.5`** on a `grid {width: 8, height: 5}`:
   assert fills land at cells `(7, 0)` and `(0, 4)`, at their hand-computed pixel origins
   (`index * 6 + 1` from `cellOrigin`, so x=43 and y=25). An unwrapped implementation paints at a
   negative origin and fails this.

   **Give it `alpha: 0.5` deliberately** so it shares gate 1's `rgba(` predicate. Omit `alpha` and
   the back-compat contract routes it to the opaque literal instead, the `rgba(` predicate matches
   nothing, and the gate passes on an empty set — green while proving nothing.

4. **The `alpha === 1 ⇒ opaque` half of the contract**, which nothing else covers: existing fixtures
   exercise *absent* alpha and gates 1–3 use `0.5`. An implementation reading "absent ⇒ opaque, else
   rgba" passes every other gate while violating the stated contract. Assert an entry with
   `alpha: 1` paints `LIGHTNING_COLOR`.

**Predicate spelling is load-bearing.** `rgba(255, 255, 187,` must match the emitted string
byte-for-byte, spaces included. If the module builds its colour as `rgba(${r},${g},${b},${a})` with
no spaces, the predicate matches nothing and the disc-count gate reads 0 instead of 5 — a false red
that looks exactly like a geometry bug. Derive the prefix from `LIGHTNING_RGB` in the test rather
than hand-typing it, so the two cannot drift.

In `observer.html`, create one `createLightningTrail()` for the page lifetime. **Placement is
ambiguous unless stated, and the two readings differ:** `drawWorld` is called from `render()`
(`:105-111`), which runs on *both* a new world frame (`onWorld`, `:118`) *and* every layer-toggle
click — and `render()` has no frame in scope, only `lastFrame`, which is `undefined` before the
first frame. So:

- `record(f.tick, f.env?.lightning ?? [])` goes in **`onWorld(f)`**, never in `render()` — recording
  from `render()` would re-record on every toggle click.
- `render()` reads `active(lastFrame?.tick ?? 0)`. The `?? 0` matters: `active(undefined)` yields
  `NaN` ages on the pre-first-frame paint, and every comparison against `NaN` is false, so strikes
  would neither draw nor expire.

The page must still type no layer key of its own — derive from the module exports, per the
Contract-1 rule the previous slice established.

- [ ] **Step 6: Extend `ObserverPageServesTest` in BOTH places**

The file splits these two concerns across different methods — doing only one leaves half the pin:

1. Add an import assertion to `pageDelegatesRenderingToTheExtractedModules`, alongside the three at
   `:46-48` (`./observer-render.js`, `./observer-markers.js`, `./observer-legend.js`).
2. Add a new sibling method `lightningModuleIsServedAsStaticContent`, mirroring
   `legendModuleIsServedAsStaticContent` at `:74-78` — the served-as-static-content assertions live
   in their own per-module methods (`:59`, `:67`, `:74`), not in the delegation test.

- [ ] **Step 7: Run the full check**

Run: `./gradlew check > /tmp/full6.log 2>&1; echo "EXIT=$?"`
Expected: `EXIT=0`. Read the log's `BUILD` line directly — never trust a piped-through-`tail` status.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/ src/test/js/ build.gradle.kts \
        src/test/java/com/paralife/observer/ObserverPageServesTest.java
git commit   # body: EARS-8/EARS-9, the RED output, and the
             #       requiredJsTests preflight RED-test
```

---

## Task 7: Docs merge-back

A slice is not done until the canonical doc matches shipped code (`CLAUDE.md` §close-out gates).

**Files:**
- Modify: `docs/SCHEMA.md` — the observer section around `:613`, `:659`, `:678-685`
- Modify: `BACKLOG.md` — the E-1/E-2/E-5/E-7/E-8 entries
- Modify: `CLAUDE.md` — the tick-pipeline table row for `EnvPostActionReconciler`

**Interfaces:** consumes the final wire shape from Task 5 and the render contract from Task 6.

- [ ] **Step 1: Update `docs/SCHEMA.md`**

1. `:613` — the `world` frame example's `env` object gains a populated `lightning` entry showing
   `{x, y, radius}`.
2. `:659` — the `lightning` bullet documents `radius` and states it is the **Euclidean** outer
   radius of the damaged disc.
3. `:667` — the pitch paragraph says grid lines are `#ddd`. They are `#333` and the layer starts
   hidden (shipped in `8a207b3`). Fix both; this is merge-back debt from the previous slice.
4. `:685` — the layer-order line stays correct, but add the trail contract: a strike is drawn for
   `LIGHTNING_TRAIL_TICKS` frames at decreasing opacity, as a Euclidean disc of its `radius`.
5. Note the key/layer-toggle panel from the previous slice if it is still undocumented — check
   before writing, and skip the point if it is already there.

- [ ] **Step 2: Mark the BACKLOG entries resolved**

For E-1, E-2, E-5, E-7, E-8: keep the diagnosis text (it is the record of why), and add a
one-line resolution naming the commit and the EARS clause. E-5's line must say explicitly that it
needed no production change and why. Leave E-3, E-4, E-6, E-9 untouched — they are out of scope.

- [ ] **Step 3: Fix the `CLAUDE.md` tick-pipeline row**

The `EnvPostActionReconciler` row claims it clears cure-immunity. `EnvPostActionReconciler.java:37-44`
calls `processEnvDeaths()` and `drainPostActionGrants()` only. Correct the row to match.

- [ ] **Step 4: Verify the doc claims against the code, and RED-test the verification itself**

For each numbered claim in Step 1, grep the shipped source for the value asserted.

Then actually apply the rule rather than just citing it. Pick one claim — the `#333` grid colour is
the easiest — and **grep for a deliberately wrong string first** (`#ddd` in
`observer-markers.js`), confirm the grep returns empty, and only then run the real grep and confirm
it hits. Without that step you have a gate that has never been shown to fire, which is precisely
what `CLAUDE.md` §close-out gates forbids. Record both commands and both outputs.

- [ ] **Step 5: Commit**

```bash
git add docs/SCHEMA.md BACKLOG.md CLAUDE.md
git commit   # body: the scope-diff line, delivered vs plan intent
```

---

## Self-review

**Spec coverage.** E-1 → Task 1. E-2 → Tasks 2 and 3 (radius cap, ratchet, single-tick clear —
all three sub-defects from the BACKLOG entry). E-5 → Task 1, no production change, stated up front.
E-7 → Task 4. E-8 → Tasks 5 and 6. Docs → Task 7. No BACKLOG sub-point is unassigned.

**Deliberately out of scope.** E-3 (bloom raggedness) and E-9 (extinction ordering) are emergence
and get no test. E-6 (bonding thresholds) is balance tuning and waits for E-9's confirmation. The
two E-5 deciders the user chose not to change — the 30%-energy avoidance gate and
exclusion-not-repulsion — stay exactly as they are.

**Type consistency.** `Strike(int x, int y, int radius)` is the name on both sides of the Task 5
seam (`EnvironmentSnapshot.Strike`, `ObserverFrame.Strike`) and its three JSON keys are what Task 6
reads. `maxRadius` (record) / `max-radius` (yaml) is the single new config name. `LIGHTNING_TRAIL_TICKS`,
`trailAlpha`, `createLightningTrail`, `discOffsets` are used with those exact spellings in Task 6's
contract block, its test gates, and the renderer wiring. New test classes are `MutagenRadiusTest`
(Task 2) and `MutagenZoneDecayTest` (Task 3); `MutagenTest` itself is not modified by any task.

**Ordering.** Tasks 1–4 are independent in **production** code. Task 6 depends on Task 5's wire
shape. Task 7 depends on 5 and 6. Tasks 2 and 3 both edit `advanceMutagen`, so run 2 before 3 to
avoid a pointless conflict — that is a merge concern, not a behavioural dependency: Task 3's gate
sets `gossip-probability=0.0` precisely so it does not lean on Task 2's radius cap for isolation.

**One caveat the class split creates.** `MutagenRadiusTest` is a new persistent test class, and
Task 3 then changes the engine behaviour it runs under — the age-out sweep becomes unconditional
and fires inside that class too. It is decoupled deliberately (`zone-decay-ticks=500` there, far
above its tick budget), so nothing should move; that is exactly why Task 3's Step 4 runs the **full**
suite and not just its own class.

**Two new Spring contexts.** Each new test class carries a distinct `@TestPropertySource`, so
neither shares a cached `ApplicationContext` with `MutagenTest` — two additional contexts in a
`forkEvery=0` shared JVM that `CLAUDE.md` calls leak-sensitive by design. The split is still the
right call, but note it in the commit body rather than leaving it to be discovered.

**Two gates that could have shipped vacuous, and how they were fixed.** Both were caught in plan
review, before any code existed, and both are worth re-checking at implementation time:
- EARS-4's original fixture placed the legacy cell *outside* `max-radius`, so the radius cap alone
  satisfied it and the source filter could be deleted with the test still green.
- EARS-5's original fixture could never go green under `MutagenTest`'s class-level
  `gossip-probability=1.0`, because cleared cells are re-colonized the next tick.
