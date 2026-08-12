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
// Fixture: stamp one cell at 255 via the existing test seam, then advance with
// NO active toxin event so nothing re-deposits.
// Assert: within a bounded tick budget, nonZeroToxinCellCount() == 0.
//   Bound is a loop cap, not a tuned expectation — assert termination, never
//   "cleared in exactly N ticks" (that magnitude moves with decay-rate tuning).
// Positive control: assert the field is non-empty BEFORE the loop, so a stamp
// that silently failed cannot make this pass for the wrong reason.
```

Use the existing test seam for stamping and advancing — `ToxinTest` already constructs an
`EnvironmentEngine` with a test `EnvironmentConfig`; follow the harness in that file rather than
inventing one. Read `ToxinTest.java:230-270` first for the established pattern.

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
- Test: `src/test/java/com/paralife/engine/MutagenTest.java`

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

Add `maxRadius` to the record, the validation line, and the yaml key. Also update
`EnvironmentConfig.Mutagen.defaults()` if that factory exists in this file — grep for `defaults()`
in `EnvironmentConfig.java` and match whatever the sibling `Toxin`/`Lightning` records do.

- [ ] **Step 2: Run the full test suite to confirm the config addition alone is inert**

Run: `./gradlew test > /tmp/cfg.log 2>&1; echo "EXIT=$?"`
Expected: PASS. Any red here is a positional-constructor site you missed — fix it before continuing.

- [ ] **Step 3: Write the two failing tests**

```java
// EARS-3 — radius cap.
// Fixture: spawn an outbreak at a known origin via the existing test seam
// (EnvironmentEngine.java:1656 constructs a MutagenEvent directly — see how
// MutagenTest already drives it). Set gossip-probability to 1.0 and max-radius
// small (e.g. 3) so propagation is deterministic and the cap is reached fast.
// Assert: after enough ticks to cross the cap unbounded, EVERY non-zero cell is
//   within Chebyshev max-radius of origin.
// Positive control: a cell AT exactly max-radius is colonized — otherwise
//   "nothing outside the cap" would also pass if gossip were broken entirely.

// EARS-4 — no cross-outbreak ratchet.
// Fixture: stamp a legacy strain cell far from the new origin (beyond max-radius)
// with a colonization tick BEFORE the new outbreak's spawnTick, then start the
// new outbreak and advance one tick with gossip-probability 1.0.
// Assert: the legacy cell's 8 neighbours are all still clean.
// Positive control: in the same test, a cell colonized AT/AFTER spawnTick does
//   gossip to its neighbours — proving the source filter discriminates by tick
//   rather than suppressing gossip wholesale.
```

Both assertions are structural (a coordinate is or is not colonized), not statistical — no cell
counts, shares, or densities. Read `MutagenTest.java` in full first and reuse its fixture style.

- [ ] **Step 4: Run and record the real failures**

Run: `./gradlew test --tests 'com.paralife.engine.MutagenTest' > /tmp/red2.log 2>&1; echo "EXIT=$?"`
Expected: FAIL on both. Paste both actual messages into the commit body.

- [ ] **Step 5: Implement both guards**

Two additions inside `advanceMutagen`'s active branch:

- **Source filter (EARS-4)** — in the outer scan at `:581-583`, skip any non-zero cell whose
  `mutagenLastReinforcedTick[x][y]` is earlier than `activeMutagen.spawnTick()`. That timestamp is
  already maintained and is stamped once at colonization (`:592`'s `existingStrain != 0` guard
  short-circuits before the write at `:603`), so it is exactly "which outbreak colonized this cell".
- **Radius cap (EARS-3)** — in the per-neighbour loop at `:586-593`, before writing
  `mutagenGridNext[nx][ny]`, reject the neighbour when its toroidal Chebyshev distance from
  `activeMutagen.originCell()` exceeds `cfg.maxRadius()`. Toroidal distance per axis is
  `min(|a-b|, dim - |a-b|)`; the cell distance is the max of the two axes.

- [ ] **Step 6: Run the mutagen suite, then the full suite**

Run: `./gradlew test --tests 'com.paralife.engine.MutagenTest' > /tmp/green2.log 2>&1; echo "EXIT=$?"`
then `./gradlew test > /tmp/full2.log 2>&1; echo "EXIT=$?"`
Expected: PASS both.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/paralife/engine/EnvironmentConfig.java \
        src/main/java/com/paralife/engine/EnvironmentEngine.java \
        src/main/resources/application.yml \
        src/test/java/com/paralife/engine/MutagenTest.java
git commit   # body: EARS-3/EARS-4 and both RED outputs
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
- Test: `src/test/java/com/paralife/engine/MutagenTest.java`

**Interfaces:**
- Consumes: `EnvironmentConfig.Mutagen.maxRadius()` from Task 2 exists by now but is not used here.
  This task uses only the pre-existing `zoneDecayTicks()`.
- Produces: no signature change.

- [ ] **Step 1: Write the failing test**

```java
// EARS-5 — decay runs during an active outbreak.
// Fixture: outbreak whose lifetimeTicks far exceeds zoneDecayTicks (e.g. 300 vs 5),
// so the window under test sits strictly INSIDE the active period.
//
// ISOLATION IS LOAD-BEARING. A cleared cell has strain 0 next tick, so any live
// neighbour re-colonizes it with a FRESH timestamp and the assertion flakes. Place
// the target cell with no colonized cell in its Moore neighbourhood — put the
// outbreak origin far away and stamp the target directly, then set its age with
// setMutagenLastReinforcedTickForTest. Do NOT let gossip reach it.
//
// Assert: the target cell is clean, AND activeMutagenEvent() is still non-null at
//   the moment of assertion — without that second half the test passes for the
//   wrong reason if the outbreak quietly expired and the OLD idle-only path did
//   the clearing.
// Positive control: a second isolated cell whose timestamp is within the last
//   zoneDecayTicks is still set, proving the sweep discriminates by age rather
//   than clearing everything.
```

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
        src/test/java/com/paralife/engine/MutagenTest.java
git commit   # body: EARS-5, RED output, and the annulus behaviour note
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

`EnvironmentSnapshotTest:93`'s `isEmpty()` assertion needs no change and must stay — it is the
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
```

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

/** Frames a strike stays visible after the tick it arrived on. */
export const LIGHTNING_TRAIL_TICKS = 6;

/** Base colour; the trail supplies the alpha. Matches the existing LIGHTNING_COLOR hue. */
export const LIGHTNING_RGB = [255, 255, 187];

/**
 * Age -> opacity. age 0 is the arrival frame (fully opaque), and opacity reaches
 * 0 at LIGHTNING_TRAIL_TICKS so an expiring strike never pops.
 * Contract: strictly decreasing over age, in (0, 1] for every drawn age.
 */
export function trailAlpha(age)

/**
 * Closure over the strikes still in their trail window.
 *   record(tick, strikes)  strikes are [{x, y, radius}] from env.lightning
 *   active(tick)           -> [{x, y, radius, alpha}], newest first, expired dropped
 * A strike recorded twice at the same tick is stored once (frames are latest-wins
 * and a slow observer may re-render the same tick).
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
| `:234` | layer-toggle matrix, same colour match |
| `:294-295` | pins `LIGHTNING_COLOR === "#ffb"` |

Emitting `rgba(255, 255, 187, α)` unconditionally turns three of those red. Honour the defaults and
they stay green untouched — which is the point: they are the regression net for the layer ordering
and the toggle gate, and rewriting them to match new output would forfeit exactly that.

- [ ] **Step 1: Write `observer-lightning.test.js` against the contracts above**

```js
// EARS-8 gates:
//  - trailAlpha is strictly decreasing across 0..LIGHTNING_TRAIL_TICKS-1, and
//    every value is in (0, 1].
//  - a strike recorded at tick T is in active(T) and in
//    active(T + LIGHTNING_TRAIL_TICKS - 1), and absent from
//    active(T + LIGHTNING_TRAIL_TICKS). The "present" halves are the positive
//    control for the "absent" half.
//  - recording the same tick twice yields one entry, not two.
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

- [ ] **Step 3: Implement `observer-lightning.js` to the contract**

- [ ] **Step 4: Run jsTest**

Run: `./gradlew jsTest > /tmp/green6.log 2>&1; echo "EXIT=$?"`
Expected: PASS.

- [ ] **Step 5: Wire it into `drawWorld` and the page, with a renderer test**

Add to `observer-render.test.js`: a strike with `radius: 1` and `alpha: 0.5` paints 5 cells (the
Euclidean disc of radius 1), and paints none when `layers.lightning === false` — the layer gate is
the positive/negative pair. Use the existing fake-context harness in that file; do not introduce a
canvas.

In `observer.html`, create one `createLightningTrail()` for the page lifetime, call `record(tick,
frame.env.lightning ?? [])` on each world frame, and put `active(tick)` on the state object passed
to `drawWorld`. The page must still type no layer key of its own — derive from the module exports,
per the Contract-1 rule the previous slice established.

- [ ] **Step 6: Extend `ObserverPageServesTest`**

Assert `observer-lightning.js` is served as static content and that the page imports it. Mirror the
three assertions already in `pageDelegatesRenderingToTheExtractedModules`.

- [ ] **Step 7: Run the full check**

Run: `./gradlew check > /tmp/full6.log 2>&1; echo "EXIT=$?"`
Expected: `EXIT=0`. Read the log's `BUILD` line directly — never trust a piped-through-`tail` status.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/ src/test/js/ \
        src/test/java/com/paralife/observer/ObserverPageServesTest.java
git commit   # body: EARS-8/EARS-9 and the RED output
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

- [ ] **Step 4: Verify the doc claims against the code**

For each numbered claim in Step 1, grep the shipped source for the value asserted. A doc gate that
was never shown to fire is theatre — if a grep finds nothing, the doc is wrong, not the grep.

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
contract block, its test gates, and the renderer wiring.

**Ordering.** Tasks 1–4 are mutually independent. Task 6 depends on Task 5's wire shape. Task 7
depends on 5 and 6. Tasks 2 and 3 touch the same method and should run in that order to avoid a
pointless conflict.
