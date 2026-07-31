# Observer Render Slice A Implementation Plan

> Implement task by task with the project’s normal spec-doc → TDD RED/GREEN → review loop.
> This document specifies contracts and verification evidence; it deliberately does not pre-write
> production code or tests.

**Goal:** Make every observer entity kind legible at the default 256×256 world by moving to a 6px
cell pitch, extracting testable renderer modules, drawing environment layers over rocks, and adding
an optional `mutated` flag to the observer wire.

**Readiness:** **GO-WITH-CAVEATS** — the default 256×256 world is the supported target for this
slice. Large-world rendering budgets and interactive zoom/pan remain follow-ups.

## Why

The current 2px cells collapse hollow and outlined markers into indistinguishable blocks, while
opaque rocks hide toxin and mutagen overlays. Infection state also exists in the engine but is not
projected onto the observer frame, so a human cannot see mutated entities. These are deterministic
rendering and projection defects, not emergence questions.

## What changes / impact

- The observer canvas uses a 6px pitch: 5px drawable content plus a 1px grid border.
- Marker geometry and world painting move from inline page script into pure ES modules covered by
  Node’s built-in test runner.
- Rocks render below toxin and mutagen; entities remain above both; lightning remains topmost.
- The server captures active infection IDs in `EnvironmentSnapshot` and emits `mutated: true` only
  for infected controllable entities.
- The repository CI gate provisions Node 22 and runs the JavaScript tests.
- `docs/SCHEMA.md` receives both the additive wire field and the durable observer render contract.

The observer endpoint remains read-only and disabled by default. The compact-text bot protocol is
unchanged.

## Assumptions / open questions

- Node 22 is available locally and will be provisioned explicitly in CI; no npm packages are needed.
- `EnvCleanupHooksBean.getInfections()` is keyed by the same entity IDs used by particles, bonded
  pairs, and composite members. `EnvironmentEngine.buildStatusCaches()` already relies on this
  identity contract.
- Browser-level JavaScript execution remains outside the default test stack per `BACKLOG.md`; this
  slice uses pure-module tests, static wiring checks, and a manual browser check.
- The full-resolution 6px renderer targets the default 256×256 grid. A performance envelope remains
  unresolved and is explicitly deferred — but note the exposure is **not** limited to larger worlds.
  A measured default-configuration world late in a run held 32,016 rocks, 45,559 mutagen cells,
  21,049 toxin cells and 25,311 nutrients, which is roughly 124,000 `fillRect` calls per frame at
  the default 500 ms tick. The default target can therefore saturate on its own, so the deferral is
  gated on a measurement rather than on grid size.

## Non-goals

- Observer control messages or environment trigger buttons. Those require an independently
  default-off controls flag and inbound-frame rejection tests.
- Zoom, pan, an offscreen viewport buffer, or gesture handling. These move together to the backlog;
  shipping an unused transform seam here would add a second full-world canvas and already produced
  an odd-dimension centering defect in review.
- Composite role glyphs at 5px content size.
- Balance tuning or default-suite assertions on population outcomes.
- A browser automation dependency. The existing headless browser smoke remains in `BACKLOG.md`.

## Mechanism requirements and gate map

Each clause maps to one deterministic RED/GREEN contract. Negative assertions use a positive
control in the same fixture or an immediately adjacent fixture.

| ID | EARS requirement | RED/GREEN gate |
|---|---|---|
| R1 | WHEN an environment snapshot is constructed THE SYSTEM SHALL detach toxin, mutagen, lightning, and active infection IDs from every mutable input collection | `EnvironmentSnapshotTest`: construct the record from mutable inputs, mutate each input, and prove the record is unchanged |
| R2 | WHEN an active infection belongs to a particle, bonded pair, or composite member THE SYSTEM SHALL emit `mutated: true` for that entity and omit the field for a clean control | `ObserverFrameBuilderTest`: one mixed frame covers infected and clean examples for all three branches |
| R3 | WHEN `mutated` is absent THE SYSTEM SHALL omit the JSON key, and WHEN it is present THE SYSTEM SHALL serialize the literal boolean `true` | `ObserverFrameBuilderTest`: paired serialization assertions |
| R4 | WHEN marker geometry is requested at 5px content size THE SYSTEM SHALL return in-bounds operations that distinguish nutrient, brained particle, unbrained particle, bonded pair, composite member, and mutated overlay contracts | `observer-markers.test.js`: representative signatures plus geometry bounds. Two named properties are mandatory, because they are the exact defects this slice exists to remove and a pairwise inequality check passes without them: (a) an unbrained marker emits **no** operation covering the full content square, with a brained marker of the same species as its positive control; (b) a composite member still emits a full-content species-colour fill, and its composite cue covers strictly less than the content square. A `notDeepEqual`-style distinctness check alone does **not** satisfy R4 |
| R5 | WHEN a world frame is painted THE SYSTEM SHALL use background → grid → rocks → toxin → mutagen → entities → lightning order | `observer-render.test.js`: a recording context asserts the complete representative chain |
| R6 | WHEN toxin intensities differ THE SYSTEM SHALL vary toxin opacity, and WHEN mutagen strain IDs differ THE SYSTEM SHALL vary category hue without varying opacity | `observer-render.test.js`: magnitude and categorical controls |
| R7 | WHEN the default 256×256 world is sized THE SYSTEM SHALL produce a 1537×1537 backing canvas with 5px content and 1px borders | `observer-render.test.js`: pitch, origin, and rectangular width/height sizing contracts |
| R8 | WHEN the observer page is served THE SYSTEM SHALL load both renderer modules from a module script, call the renderer for world frames, and retain the observer WebSocket path | `ObserverPageServesTest`: static page-wiring assertions plus independent 200/content checks for both modules |
| R9 | WHEN repository CI runs THE SYSTEM SHALL require the named JavaScript test files and execute the Java and JavaScript default suites under Java 21 and Node 22 | Gradle preflight, CI workflow inspection, and exact-command RED proofs |
| R10 | WHEN the application uses committed defaults THE SYSTEM SHALL keep `paralife.observer.enabled` false | exact configuration gate, RED-proved by temporarily flipping only that value |
| R11 | WHEN a world frame is rendered THE SYSTEM SHALL measure that render's wall-clock duration and surface it alongside the tick indicator | `ObserverPageServesTest` page-wiring assertion that the world-frame path both times the render call and writes the result into the status text; the value itself is observed during manual verification, never asserted as a threshold |

## Durable rendering contract

- Pitch is 6px. Cell content occupies a 5×5 square inside 1px `#ddd` grid lines on `#000`.
- Species colours remain exact: Catalyst `#e34`, Membrane `#3d8`, Spore `#59f`.
- A brained particle is a species-colour fill; an unbrained particle is a hollow species-colour
  shell.
- A nutrient is a centred, sub-cell `#7a5` marker.
- A bonded pair shows both species with a stable ownership rule for the shared diagonal.
- A composite member retains its species fill and adds a smaller composite-ID-derived colour cue;
  the cue must not cover the species identity.
- Mutation adds one inset yellow hollow cue. It must coexist with the unbrained species shell.
- Every fill, outline, and polygon coordinate remains within the 5px content square with positive
  drawable extents.
- Layer order is exactly background, grid, rocks, toxin, mutagen, entities, lightning.
- Toxin is a magnitude heat ramp. Mutagen is categorical by strain and never an intensity ramp.

## File map

| File | Change |
|---|---|
| `src/main/java/com/paralife/engine/EnvironmentSnapshot.java` | Add defensively copied `infectedIds` component and make collection immutability a record invariant |
| `src/main/java/com/paralife/engine/EnvironmentEngine.java` | Capture infection IDs in `snapshot()` on the tick thread |
| `src/main/java/com/paralife/observer/ObserverFrame.java` | Add nullable `mutated` to `EntityDto` factories |
| `src/main/java/com/paralife/observer/ObserverFrameBuilder.java` | Project mutation state for all three infectable entity kinds |
| `src/main/resources/static/observer-markers.js` | New pure marker geometry module |
| `src/main/resources/static/observer-render.js` | New painting, colour, sizing, and layer-order module |
| `src/main/resources/static/observer.html` | Module imports, 6px direct canvas rendering, existing panels and WebSocket wiring |
| `src/test/java/com/paralife/engine/EnvironmentSnapshotTest.java` | Production snapshot/copy contract |
| `src/test/java/com/paralife/observer/ObserverFrameBuilderTest.java` | Mutation projection and JSON omission contracts |
| `src/test/java/com/paralife/observer/ObserverPageServesTest.java` | Asset serving and page-wiring contracts |
| `src/test/js/observer-markers.test.js` | Marker distinction and bounds contracts |
| `src/test/js/observer-render.test.js` | Sizing, environment semantics, and complete layer-order contracts |
| `package.json` | Root ES-module declaration only; private and dependency-free |
| `build.gradle.kts` | `jsTest` verification task and `check` dependency |
| `.github/workflows/ci.yml` | Provision Node 22 and include `jsTest` in the merge gate |
| `docs/SCHEMA.md` | Merge back the optional field and durable rendering contract |
| `BACKLOG.md` | Preserve bounded viewport/zoom-pan and large-world render-budget follow-ups |

The existing `EnvironmentSnapshot` construction sites in
`ObserverBootstrapOrderingTest`, `ObserverWebSocketHandlerTest` (two sites),
`ObserverBroadcasterTest`, and `ObserverFrameBuilderTest` must receive an empty fourth component.
Re-run `rg -n 'new EnvironmentSnapshot\\(' src/main src/test` after the arity change rather than
trusting this inventory.

## Task 1: Project active mutation state onto the observer wire

**Files:** the four production Java files above; `EnvironmentSnapshotTest`;
`ObserverFrameBuilderTest`; affected snapshot-construction fixtures; the wire-field portion of
`docs/SCHEMA.md`.

### RED

1. Extend `EnvironmentSnapshotTest` with a record-direct fixture using mutable toxin, mutagen,
   lightning, and infection collections. Mutate every input after construction and prove all four
   record components remain unchanged. This directly pins the record invariant.
2. Add a separate engine-direct fixture that inserts an active infection through the canonical hooks
   object, captures a snapshot, and proves the production seam supplied that ID. Pair it with a clean
   control snapshot so an always-populated set cannot pass.
3. Extend `ObserverFrameBuilderTest` with one deterministic frame containing particle, bonded-pair,
   and composite-member infected/clean controls. Assert mutation projection through each independent
   switch branch.
4. Add paired JSON assertions proving true is emitted and false is represented by omission, not by a
   serialized false value.
5. Run `./gradlew test --tests 'com.paralife.engine.EnvironmentSnapshotTest' --tests 'com.paralife.observer.ObserverFrameBuilderTest'`.
   The initial compile failure from the missing record component, factory arguments, and accessor is
   the valid RED only if the newly added tests are among the reported compilation failures.

### GREEN

1. Add `infectedIds` to `EnvironmentSnapshot`. Use a compact constructor to defensively copy every
   collection component, so the record itself enforces its documented immutability rather than
   relying on one producer.
2. Supply the accumulated environment collections and infection key-set view to the record from
   `EnvironmentEngine.snapshot()`, leaving defensive copying at the record’s single authoritative
   boundary. Capture, frame build, and JSON serialization all occur synchronously on the single tick
   thread; immutable snapshots are still required because payload delivery continues off-thread.
3. Add nullable `mutated` to `EntityDto`. Factories for particles, bonded pairs, and composite
   members translate true to `Boolean.TRUE` and false to null; nutrients always use null.
4. In `ObserverFrameBuilder`, perform membership checks against the captured set using each
   occupant’s own ID.
5. Update every constructor call found by the repository-wide search.
6. Document the optional true-only field in `docs/SCHEMA.md` §14 and retain schema version 1 because
   the change is additive and existing consumers ignore unknown JSON properties.

### Verify and RED-proof

1. Re-run the two focused classes, then `./gradlew test --tests 'com.paralife.observer.*'`.
2. RED-proof R1 by disabling one compact-constructor copy at a time; the corresponding direct
   mutable-input assertion must fail. Restore each copy and confirm green. Separately suppress the
   infection argument at the engine seam; the engine-direct presence assertion must fail.
3. RED-proof R2 once per independent entity branch by temporarily suppressing its membership result;
   the corresponding mixed-frame assertion must fail. Restore after each probe.
4. RED-proof R3 by temporarily representing clean as `Boolean.FALSE`; the JSON omission assertion
   must fail. Restore and confirm green.
5. Commit this logical unit with the wire documentation. Record scope-diff: delivered infection
   snapshot, three-kind projection, and true-only JSON field; no infection mechanics changed.

## Task 2: Extract pure marker geometry and establish the JavaScript gate

**Files:** `package.json`, `observer-markers.js`, `observer-markers.test.js`,
`ObserverPageServesTest`, `build.gradle.kts`, `.github/workflows/ci.yml`.

### RED

1. Add a private, dependency-free root package manifest whose only behavioral setting is ES-module
   mode.
2. Write marker contracts for every R4 marker class, the unbrained/mutated two-outline combination,
   stable composite hue for the same ID, and geometry bounds across all operation types.
3. Add the marker-module HTTP assertion to `ObserverPageServesTest` before creating the asset; it
   must fail with 404 while the existing observer-page positive control remains green.
4. Run `node --test 'src/test/js/*.test.js'`. The installed Node 22 runner accepts this glob;
   do not pass the directory itself, which Node treats as a module path and fails for the wrong
   reason.
5. Run the focused Spring serve test and record both RED reasons.

### GREEN

1. Create `observer-markers.js` as a DOM-free module that returns drawing data in cell-local
   coordinates. Keep painting APIs out of this module.
2. Preserve exact species, nutrient, mutation, content, and pitch constants from the durable
   contract. Use a fallback colour for unknown species without weakening known-species assertions.
3. Make composite cues smaller than the species fill and mutation cues inset from the outer shell.
4. Register a Gradle `jsTest` verification task using Node’s supported
   `src/test/js/*.test.js` glob, and make `check` depend on it. Before invoking Node, fail explicitly
   when no matching test file exists; Node 22 otherwise exits successfully with zero tests.
5. Update CI to provision Node 22 explicitly and run `./gradlew spotlessCheck test jsTest
   --no-daemon --console=plain`. Do not add JS tests to the manual Java stress workflow; it is not
   the merge gate and exists to reproduce timing/leak conditions.

### Verify and RED-proof

1. Run `./gradlew jsTest` and the focused serve test.
2. Temporarily move the marker test out of the discovery path and run `./gradlew jsTest`; the
   Gradle preflight must exit non-zero instead of accepting Node’s zero-test result. Restore it.
3. Temporarily alter the mutation colour and run `./gradlew jsTest`; the Gradle task must exit
   non-zero on the corresponding contract, then return green after restoration.
4. Temporarily repeat that loss under the exact CI Gradle command to prove the merge-gate command
   executes `jsTest`; restore and run it green.
5. Run `./gradlew spotlessCheck`; current Spotless targets only Java. If that source fact changes,
   fix the build target instead of manually formatting around it.
6. Commit marker geometry, its tests, the test harness, and CI wiring as one logical unit.

## Task 3: Extract world painting and pin complete layer order

**Files:** `observer-render.js`, `observer-render.test.js`, `ObserverPageServesTest`.

### RED

1. Write a recording-context contract containing at least one representative of every R5 layer,
   including lightning.
2. Assert the complete ordered chain, not merely one border before one rock. The gate must prove all
   grid operations precede the first rock and that rock, toxin, mutagen, entity, and lightning
   sentinels occur in exact order.
3. Add R6 toxin and mutagen contracts and R7 width/height, origin, and default-size contracts.
4. Include rectangular dimensions so width is never accidentally reused for height.
5. Add the render-module HTTP assertion before creating the asset; it must fail with 404.
6. Run `./gradlew jsTest` and the focused serve test. Marker tests remain green; render import and
   asset serving are the intended RED failures.

### GREEN

1. Create `observer-render.js` and import marker geometry through a relative browser-safe module
   path.
2. Keep `paintOps` limited to the small canvas 2D subset required by the recording context.
3. Size width and height independently as cells multiplied by pitch plus the trailing border.
4. Paint the exact R5 order. Environment and lightning collections tolerate absent arrays, matching
   the current page’s graceful-degradation behavior.
5. Keep toxin opacity monotonic with intensity. Derive mutagen hue from strain while keeping alpha
   constant.
6. Tighten the `jsTest` preflight from “at least one discovered test” to require both named observer
   test files now that both exist.

### Verify and RED-proof

1. Run `./gradlew jsTest` and the focused serve test.
2. RED-proof each ordering boundary by moving one representative layer across its neighbor, one at a
   time. Each mutation must fail the complete-order assertion for the intended reason.
3. RED-proof rectangular sizing by substituting width for height; the rectangular fixture must fail.
4. Temporarily move only the render test out of place; `jsTest` must fail its named-file preflight
   even though the marker suite still exists. Restore it.
5. Restore after every probe and finish green.
6. Commit painting, tests, and render-module serving as one logical unit.

## Task 4: Rewire the page for direct 6px rendering

**Files:** `observer.html`, `ObserverPageServesTest`, the render-contract portion of
`docs/SCHEMA.md`, `BACKLOG.md`.

### RED

1. Extend the existing page test to require a module script, exact imports of both renderer assets,
   use of the extracted world renderer in the world-frame path, the R11 render timing around that
   call with its result reaching the status text, and the existing `/ws/observer`, bootstrap, and
   world positive controls.
2. Run `./gradlew test --tests 'com.paralife.observer.ObserverPageServesTest'`. The current inline
   classic script must fail the new module-wiring assertions.

This is a static wiring gate, not a claim that JavaScript executed in a browser. A browser smoke
remains deferred by explicit project decision.

### GREEN

1. Convert the page script to an ES module and import shared species colours plus the world renderer
   and sizing helpers.
2. On bootstrap, size the visible grid canvas directly from both received dimensions, set static
   rocks, and retain the current derived `ws:`/`wss:` connection behavior.
3. On every world frame, call `drawWorld` directly on the visible canvas. Do not add an offscreen
   full-world buffer or dormant transform state in this slice.
4. Preserve scoreboard, populations, time-series history, connection status, and graceful defaults
   for missing frame collections.
4a. Time the world render with a monotonic clock around the `drawWorld` call and append the rounded
   millisecond cost to the status text next to the tick number (R11). This is the instrument that
   makes the deferred render budget measurable rather than a matter of opinion: a saturated default
   world issues on the order of 124,000 fill operations per frame, and "12 ms against a 500 ms tick"
   is a decidable fact where "does it look smooth" is not. Do not assert a threshold anywhere in the
   default suite — the number moves with hardware and with tuning, so it is observe-only.
5. Use block layout for canvases and avoid CSS scaling that would blur the 1px backing-store lines.
6. Add the durable pitch, marker, colour, layer-order, and environment-semantics contract to
   `docs/SCHEMA.md` §14.
7. Add a concrete `BACKLOG.md` item for a bounded/tiled viewport with zoom-pan and an explicit
   render budget. Record two independent triggers, not one: any work beginning interactive
   navigation or claiming observer support beyond the default 256×256 target, **or** an observed
   render duration that consumes a material fraction of the configured tick interval at default
   grid size. The second trigger exists because the default world can saturate on its own — a
   grid-size-only trigger would never fire on the case actually measured during this slice. Note in
   the item that reintroducing an offscreen buffer is the first move once panning is in scope, since
   panning under direct rendering repaints the whole world every pan frame.

### Automated verification

1. Run the page serve test.
2. Run `./gradlew spotlessCheck test jsTest`.
3. Run `rg -n -U 'observer:\\n\\s+enabled:\\s+false' src/main/resources/application.yml` and require
   a match.
4. RED-proof the default-off gate by temporarily changing only that observer value to true; the
   command must return non-zero. Restore and confirm green.

### Manual verification

1. Start the server with command-line overrides for observer enablement and a slower tick interval;
   do not edit committed YAML.
2. Start a 60-bot runner in a second terminal.
3. Open `/observer.html` and confirm at the default 256×256 grid:
   - the 1537×1537 backing canvas has crisp `#ddd` borders on black;
   - the three species and the particle/structure marker classes are legible;
   - status ticks, panels, and time series update;
   - the browser console has no module, syntax, or runtime errors;
   - toxin strength varies, mutagen zones remain categorical, and toxin or mutagen on a rock remains
     visible over grey.
3a. Record the R11 render-duration readout at two points: shortly after connect, and again once the
   environment field has spread and nutrients have accumulated, which is when the frame is heaviest.
   Report both numbers against the configured tick interval. If the later figure consumes a material
   fraction of a tick, the `BACKLOG.md` render-budget item activates by its own second trigger.
   Chrome DevTools CPU throttling (Performance panel, or a device-mode preset) is an optional
   amplifier here, with a caveat worth stating: it slows JavaScript execution, so it exaggerates the
   loop issuing the fill operations while under-modelling compositor rasterization. Treat it as a
   qualitative stress signal — the timed readout is the measurement of record.
4. Do not claim observation of bonded pairs, composites, or infections unless they actually occur.
   Their deterministic geometry contracts are the coverage of record.
5. Stop each foreground process with Ctrl-C so normal shutdown hooks run. If terminals must run in
   the background, capture their exact PIDs and signal only those PIDs gracefully; never use a broad
   process-name match or unconditional SIGKILL.

### Close-out

1. Commit the page and canonical render documentation as one logical unit.
2. Record scope-diff: direct 6px default-world rendering, extracted modules, complete layer order,
   mutation cue, observe-only render-duration readout, CI gate, and canonical docs delivered;
   viewport gestures/buffer, controls, render budget, browser automation, role glyphs, and balance
   tuning deferred.
3. Re-run `./gradlew spotlessCheck test jsTest` immediately before any done/passing claim.
4. Review only the final plan-to-implementation diff for deviations before opening a PR.

## Review convergence record

Round 1 used a holistic reviewer, an adversarial reviewer, a JavaScript/render/build specialist, and
a server wire/snapshot specialist. Findings were checked against the current sources before
acceptance.

Accepted and addressed:

- Node 22 does not discover tests from `node --test src/test/js/`; use its supported test-file glob.
- `jsTest` wired only to `check` was absent from the actual CI command.
- Infection capture/copy and the bonded-pair/composite branches lacked production-seam coverage.
- The record’s immutability claim needed constructor-level defensive copies.
- Layer tests omitted lightning and did not pin the complete order.
- The page asset test did not prove page wiring.
- The canonical render contract was promised but not scheduled.
- Marker geometry bounds were specified but untested.
- The manual shutdown command could kill unrelated processes and bypass cleanup.
- The document lacked the constitution’s Why, impact, EARS, non-goals, and readiness structure.
- The original plan prescribed implementation and test bodies; this revision retains contracts and
  evidence requirements without pre-writing either.
- Round 2 found that Node 22 treats an unmatched test glob as a successful zero-test run; Gradle now
  owns named-test preflight checks and their RED proofs.
- Round 2 found that viewport and large-world deferrals were absent from the durable backlog; Task 4
  now writes the follow-up and trigger to `BACKLOG.md`.

Refined or declined with source-based rationale:

- A new browser automation gate was not added: `BACKLOG.md` explicitly defers it and the repository
  has no browser-test harness. Static wiring, pure-module tests, and manual execution cover this
  slice without a new runtime dependency.
- Large-world tiling or a bounded viewport was not pulled into Slice A: the stated target is the
  default 256×256 world. The caveat is now explicit and the performance envelope remains a named
  follow-up.
- The offscreen viewport seam was not retained: zoom/pan is deferred and the reviewed odd-size
  transform clipped one border, so the slice carried dormant machinery that had already produced a
  defect. Its cost was not the reason — a second 1537×1537 canvas is ~9.4 MB and one extra blit per
  tick, which is a rounding error on this hardware. The buffer's real value is decoupling render
  rate from world rate, which only pays once interactive panning exists: panning over a buffer is a
  blit, whereas panning under direct rendering re-runs the whole world paint every pan frame. That
  trade belongs with the zoom/pan backlog item, not here. The transferable seam that this slice does
  keep is `drawWorld` accepting a context argument — reintroducing a buffer later means creating one
  and passing its context, and touches no marker or layer-order code.
- `schemaVersion` remains 1: `mutated` is additive, nullable, and ignored by existing consumers.
- No snapshot data race was accepted: capture, build, and serialization run synchronously on the
  tick thread. Defensive copying is still required to make ownership explicit and durable.
