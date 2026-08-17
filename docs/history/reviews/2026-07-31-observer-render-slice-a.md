---
task: custom
mode: reference
reviewers_succeeded: ["claude", "codex", "opencode"]
reviewers_failed: ["agy", "pykrete"]
reviewed_at: 2026-07-31T18:55:33Z
prompt_file: /home/mark/kramtime/paralife/.multi-review/prompts/observer-render-slice-a.yaml
models:
usage:
  agy: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 9.8 }
  claude: { elapsed_s: 487.0 }
  codex: { input: 1436182, output: 18071, cached: 1287424, tool_calls: 45, elapsed_s: 450.2 }
  opencode: { input: 110813, output: 2317, cached: 432000, tool_calls: 22, elapsed_s: 160.6 }
  pykrete: { input: 0, output: 0, cached: 0, tool_calls: 0, elapsed_s: 0.0 }
---

# Cross-AI Review

## Agy Review (FAILED)

**Status:** failed — empty output (<50 bytes)

Elapsed: 9.8s

Stderr tail:
```
jetski: no output produced — a tool required the "read_file" permission that headless mode cannot prompt for, so it was auto-denied. Add an allow-rule under permissions.allow in settings.json (e.g. read_file(<target>)). Alternatively, re-run with --dangerously-skip-permissions to auto-approve all tools.
```

---

## Claude Review

## Summary

Slice A moves the observer renderer from a 2px inline-script canvas to a 6px-pitch pipeline split across two pure ES modules (`observer-markers.js` geometry, `observer-render.js` painting), fixes the rocks-over-environment layering bug, and adds a true-only optional `mutated` boolean sourced from a new `infectedIds` component on `EnvironmentSnapshot`. I found **no critical defects**: the wire change is genuinely additive and correctly serialized, the infection seam does not alias or race, the layer order and geometry match the spec including rectangular sizing, and the `jsTest` named-file preflight does close the zero-test glob hole with CI invoking `jsTest` explicitly. The weaknesses are all in the *gate*, not the code: cell placement coordinates are completely unpinned by the JS suite (a transposed or off-by-one `drawCellFill` stays green), one graceful-degradation test is vacuous, and the recorded concurrency rationale for the infection seam is factually wrong even though the code is safe. Safe to merge with the caveats below; W1 and W3 are worth closing before or immediately after merge.

## Critical Issues

None found.

Explicit verification of the four things the request asked to scrutinise hardest:

- **Wire compatibility — holds.** `@JsonInclude(NON_NULL)` is on the record class itself (`src/main/java/com/paralife/observer/ObserverFrame.java:29`), so it survives any global inclusion default, and `trueOrNull` (`:37-39`) means `false` can never reach the encoder. Production serializes with a bare `new ObjectMapper()` (`src/main/java/com/paralife/observer/ObserverBroadcaster.java:33`), which is exactly what `ObserverFrameBuilderTest:218` uses — the test's mapper is the production mapper's config, not a lookalike. The only consumer is `observer.html`, which never reads `mutated`; `ObserverEndpointIntegrationTest:125` asserts key presence, not absence, so nothing breaks. `schemaVersion` staying `1` is correct.
- **Infection seam — safe, wrong reason recorded.** `Set.copyOf` on a `ConcurrentHashMap` keySet view goes through `new HashSet<>(coll).toArray()`, so the snapshot does not alias the live map and cannot throw CME. See W3 for why the stated rationale is nonetheless wrong. All four record components are copied (`EnvironmentSnapshot.java:21-26`), and all six construction sites were updated (`rg 'new EnvironmentSnapshot\('` returns 1 production + 5 test sites, all 4-arg).
- **Layer order / geometry — correct.** `observer-render.js:79-98` paints background -> grid -> rocks -> toxin -> mutagen -> entities -> lightning, matching `docs/SCHEMA.md` §14. Width and height are independently derived (`:22-23`, `:76-82`). All marker literals match the durable contract exactly.
- **R11 — genuinely observe-only.** `performance.now()` bracketing at `observer.html:81-89`; the only test touching it is a static string/regex gate (`ObserverPageServesTest.java:49-53`). No threshold assertion exists anywhere in the default suite.

## Warnings

**W1 (MEDIUM) — the JS gate does not pin *where* anything is drawn; a transposed or off-by-one renderer is green.**
`observer-render.js:62-64` and `:89-90` are the only places world coordinates become pixels:
```js
function drawCellFill(ops, x, y, color) {
  ops.fillRect(cellOrigin(x), cellOrigin(y), CONTENT_SIZE, CONTENT_SIZE, color);
}
```
No test in `src/test/js/observer-render.test.js` asserts the `x`/`y` of *any* painted cell. The layer-order tests (`:112-144`) match on colour only; the sizing test (`:148-162`) asserts background `w`/`h` and grid-line *counts* (`c.w === 1` / `c.h === 1`), never positions. Concretely: swap the arguments to `cellOrigin` in `drawCellFill`, or change `cellOrigin` to `index * CELL_PITCH` (dropping the `+1` so every cell lands on its border), or swap `ox`/`oy` at `:89-90`, and the entire suite still passes. The authors correctly identified this class of hazard for sizing ("a renderer that reuses width for height passes a square fixture", `:61-66`) but did not apply it to placement — and the layer-order fixture is a 4x4 square with `rocks:[{0,0}]`, `entities:[{1,1}]`, `toxin:[{2,2}]`, `mutagen:[{3,3}]` (`:97-110`), i.e. four diagonal points that are invariant under transposition. Fix: in `paintedWorld()` use asymmetric coordinates and assert one representative call per layer at `{x: cellOrigin(cx), y: cellOrigin(cy)}`.

**W2 (MEDIUM) — `absent environment and entity collections degrade gracefully` is partly vacuous.**
`observer-render.test.js:164-168` passes `rocks: []`, `entities: []`, `env: {}` — all *present*. The `state.rocks ?? []` (`observer-render.js:84`), `state.entities ?? []` (`:88`) and `state.env ?? {}` (`:74`) fallbacks are therefore never exercised; deleting all three leaves the test green. Only the `env.toxin/mutagen/lightning ?? []` fallbacks are actually reached. The assertion `assert.ok(ctx.calls.length > 0)` compounds it — the background fill alone satisfies it, so the test cannot distinguish "degraded gracefully" from "threw after the background". Fix: call `drawWorld(ctx, { grid: { width: 2, height: 2 } })` with nothing else, and assert a specific expected call count.

**W3 (MEDIUM) — the infection seam's recorded safety rationale is false; the real guarantee is undocumented and unpinned.**
The plan's convergence record states "capture, build, and serialization run synchronously on the tick thread" and the javadoc at `EnvironmentEngine.java:1060-1063` says "Call on the tick thread". That is true of the *caller*, but the *source* map is mutated off-tick: `WorldWebSocketHandler.cleanupBot()` calls `deathCleanupHooks.clearInfectionOnDeath(entityId)` at `src/main/java/com/paralife/websocket/WorldWebSocketHandler.java:996`, and `cleanupBot` is the `afterConnectionClosed` path — a WebSocket thread. The code is safe *only* because `EnvCleanupHooksBean.infections` is a `ConcurrentHashMap` (`src/main/java/com/paralife/engine/EnvCleanupHooksBean.java:101`) and `Set.copyOf` iterates it weakly-consistently. Nothing states or tests that dependency. If someone "simplifies" that field to a `HashMap` on the strength of the recorded (false) tick-confinement rationale, `Set.copyOf` at `EnvironmentEngine.java:1080` can throw `ConcurrentModificationException`, which `ObserverBroadcaster.java:86-93` catches and logs at WARN — the observer would silently stop updating with no test failure. Fix is a comment on the field naming the two off-tick writers, not new machinery.

**W4 (MEDIUM-LOW) — the R11 wiring gate does not prove the timer brackets the render.**
`ObserverPageServesTest.java:49-53` requires the substring `performance.now()` to appear somewhere and `statusEl.textContent[^;]*renderMs` to match. A page with a single `performance.now()` call and a `renderMs` computed from anything at all satisfies both. Since R11's whole point is a *duration around `drawWorld`*, a pattern requiring `performance.now()` on both sides of the `drawWorld(` call (e.g. `(?s)performance\.now\(\)[^;]*;\s*drawWorld\(.*performance\.now\(\)`) would actually pin the clause. Acknowledged in the test's own javadoc as a static gate, so this is a sharpening, not a defect.

**W5 (MEDIUM-LOW) — merge-back gap: Node is now a hard build prerequisite and the constitution doesn't say so.**
`build.gradle.kts:222` makes `check` (and therefore `build`) depend on `jsTest`, so `./gradlew build` now fails on any machine without `node` on PATH. `rg 'jsTest|Node 22|node --test' -g '*.md'` matches only `docs/SCHEMA.md` and the plan doc — `CLAUDE.md`'s **Technology Stack** and **Build commands** sections are unchanged. CLAUDE.md's own close-out gate requires folding the change into the canonical docs at merge; a one-line stack entry ("Node 22 — `jsTest`, Node's built-in runner, no npm deps") plus a `./gradlew jsTest` build command closes it.

**W6 (LOW-MEDIUM) — colour-contract drift between the tests and `docs/SCHEMA.md` §14.**
`observer-render.test.js:170-173` asserts `LIGHTNING_COLOR === "#ffb"` and `ROCK_COLOR === "#555"` under the name "the exact contract value", but neither literal appears in the §14 rendering contract — the doc lists species, nutrient, mutation, grid and background only. Conversely, §14 states "An unknown species falls back to `#888`", and no test pins that literal: `observer-markers.test.js:209-214` only asserts the fallback is *not one of the three known* colours, which would pass for `#000` or `#e35`. Either add the two colours to §14 and pin `#888`, or stop calling untracked constants "the contract".

**W7 (LOW-MEDIUM) — the bonded-pair ownership rule is pinned by a disjunctive negative.**
`observer-markers.test.js:135-138`:
```js
assert.ok(!has(secondary, 0, 0) || !has(secondary, CONTENT_SIZE, CONTENT_SIZE), ...)
```
This passes the moment the secondary lacks `(0,0)`, so the documented "primary … is one pixel larger" property is untested — a secondary of `[[1,0],[5,0],[5,5]]` (sharing the far diagonal endpoint) satisfies it. The preceding positive assertion (`:131-134`) does catch a full primary/secondary swap, which is the important case, so this is a weak clause rather than a hole. If the size relation matters, assert the shoelace area of primary > secondary.

**W8 (LOW) — §14 slightly overstates the equivalence between `mutated` and the bot-facing MUTATING bit.**
The doc says `mutated` comes from "the same `EnvCleanupHooksBean` infection map that drives the bot-facing `entityStatus` MUTATING bit". Same map, different sample point: `buildStatusCaches()` reads it at `@Order(14)` (`EnvironmentEngine.java:990`), while `snapshot()` reads it at `@Order(60)` (`ObserverBroadcaster.java:70`). An attack-cure resolved by `ActionResolver` at `@Order(20)` therefore shows MUTATING to bots and *omits* `mutated` to observers within the same tick. Harmless for a visualiser; worth one clause in §14 so nobody debugs it later.

## Suggestions

- **Assert `Content-Type` on the two module serve tests.** `ObserverPageServesTest.java:56-68` checks 200 + body substring. The one static-serving failure mode those miss is a wrong MIME type, which makes the browser reject *both* `import` statements and blanks the page with no server-side signal — precisely the silent break the test comment worries about. One extra line per test: `assertThat(resp.getHeaders().getContentType().toString()).contains("javascript")`.
- **`observer.html` guards `entities`/`env` but not `scoreboard`/`populations`.** `:85-86` use `|| []` / `|| {}`, while `:97-98` (`table(f.scoreboard)`) and `:106-110` (`pops[k]`) will throw `TypeError` on a frame missing those keys, aborting `onWorld` after the canvas has already been repainted. The builder always emits them, so this is consistency-with-stated-intent ("graceful defaults for missing frame collections") rather than a live bug.
- **The record invariant costs one redundant O(N) copy per tick.** `EnvironmentEngine.snapshot():1067-1081` builds fresh `ArrayList`s, then the compact constructor `List.copyOf`s them again. At the saturated default world the plan measured (45,559 mutagen + 21,049 toxin cells) that is ~67k extra reference copies plus two array allocations on the tick thread, every tick, whenever an observer is attached. This is the same shape as the Wave-1 hotfix documented at `EnvironmentEngine.java:1031-1040` where a per-tick `Map.copyOf` dominated tick cost. The invariant is worth keeping; just be aware the cost is real and measurable, and `BACKLOG.md:447-452` records that the client side of this budget has already fired.
- **Layer-order sentinels use first-index only.** `observer-render.test.js:114` uses `findIndex` per layer, so a renderer that painted rocks in two passes (one before, one after toxin) would still pass. The `lastGrid < firstRock` test (`:136-144`) does exactly the right thing for the grid layer; the same `lastIndexOf`/`findIndex` pairing on the rock->toxin and mutagen->entity boundaries would make R5 airtight.
- **Configuration-cache hazard in the preflight.** `build.gradle.kts:209` reads `layout.projectDirectory` inside `doFirst`, capturing the `Project` for execution-time use. Harmless today (the config cache is not enabled anywhere in this build or in CI), but it will fail the moment `--configuration-cache` is turned on. Capture the `File` at configuration time into a local `val` outside the task block.
- **Canvas ceiling is silent.** `canvasWidth(cells) = cells * 6 + 1` exceeds Chrome's 16,384px backing-store limit at ~2,730 cells, at which point the canvas silently becomes unusable rather than erroring. The `BACKLOG.md` viewport item covers "larger worlds"; adding the concrete number would make the trigger decidable rather than a judgement call.

## Risk Assessment

**LOW.** The feature is default-off (`application.yml:238-239` — `enabled: false`, verified), read-only, operator-only, and touches the simulation core only by adding a copy-on-construct component to a snapshot record that the tick loop already built. The wire change is additive and provably true-only. The genuinely risky surfaces — the concurrent infection map and the new build gate — are both actually correct; W3 is a documentation defect that creates a *future* footgun, not a present one, and the `jsTest` preflight fails safe in every mode I could reason about statically (missing file -> `GradleException`; missing `node` -> `Exec` failure; non-expanding glob on an older Node -> non-zero exit; never a silent zero-test pass).

The one thing I could not verify statically is that Node 22 expands `src/test/js/*.test.js` itself when handed the literal via `ProcessBuilder` — but the failure mode if it does not is a hard non-zero exit, so the gate cannot pass vacuously either way. I also did not execute the suite (static review as instructed), so all test-quality findings are derived by reading fixtures against implementations rather than by mutation-testing them.

The residual risk is concentrated in W1: today's renderer is correct, but the gate protecting it has a blind spot precisely where a plausible future edit (a viewport transform, per the backlog item) would introduce a coordinate bug. Closing W1 costs about ten lines.

## Strengths

- The palette test (`observer-markers.test.js:40-53`) pins colour literals by hand *first*, then explicitly licenses the structural tests to use the imported symbols — that is the correct resolution of the "never import your expectation" rule, and the reasoning is written down rather than left implicit.
- `mutatedProjectedForEveryInfectableKindWithCleanControls` (`ObserverFrameBuilderTest.java:188-210`) puts an infected and a clean instance of all three infectable kinds plus a nutrient in one frame, so each branch is its own positive control. Suppressing any single `infectedIds.contains(...)` call fails exactly one assertion. Textbook.
- `recordDetachesEveryCollectionFromItsMutableConstructorInput` (`EnvironmentSnapshotTest.java:103-122`) mutates all four inputs post-construction and is record-direct, so the invariant is pinned at the constructor rather than at one cooperating producer — which is what makes the "immutability is a record invariant" claim actually true rather than aspirational.
- The `jsTest` preflight and its rationale comment (`build.gradle.kts:192-217`) name the exact Node behaviour being defended against. This is a gate that was clearly RED-tested, per the constitution's requirement.
- The CI comment at `.github/workflows/ci.yml:43-45` explains *why* `jsTest` is named explicitly despite the `check` binding — the failure mode (a `check`-only binding in a job that never calls `check`) is the sort of thing that silently rots, and it is documented at the point of use.
- `BACKLOG.md:432-460` records the deferral with a measured number (93–268 ms/frame against a 500 ms tick) and states plainly that trigger 2 has already fired. That is an honest deferral rather than a hopeful one.

---

## Codex Review

## Summary

The production implementation appears functionally correct, but I would hold the merge for the MEDIUM test and concurrency-contract issues below. The true-only wire field is correctly produced through `Boolean.TRUE`/`null` plus Jackson `NON_NULL`; all four snapshot collections are defensively copied; rendering matches the documented pitch, geometry, colours, rectangular sizing, and layer order. The repository’s only observer consumer ignores additive fields, but compatibility with out-of-repository consumers cannot be verified.

## Critical Issues

None found. The named JavaScript-test preflight closes the zero-match glob hole, and CI explicitly provisions Node 22 and invokes `jsTest` (`build.gradle.kts:202-222`, `.github/workflows/ci.yml:34-46`).

## Warnings

### MEDIUM — Infection capture is not strictly tick-confined

`EnvironmentEngine.snapshot()` copies the live infection key view (`EnvironmentEngine.java:1078-1080`). The backing map is a `ConcurrentHashMap`, so `Set.copyOf` is safe from aliasing and concurrent-modification exceptions (`EnvCleanupHooksBean.java:101`, `EnvCleanupHooksBean.java:181-184`). However, WebSocket cleanup removes infections outside the tick pipeline (`WorldWebSocketHandler.java:850`, `WorldWebSocketHandler.java:996`).

Consequently, the plan’s strict single-thread-confinement rationale is false, and a frame can transiently combine a pre-cleanup grid snapshot with post-cleanup infection state. Either document this weakly consistent behavior as acceptable or funnel cleanup through the tick thread.

### MEDIUM — R8/R11 page wiring assertions are string-presence checks

`ObserverPageServesTest.java:44-52` independently searches for `drawWorld(`, `performance.now()`, and a status assignment mentioning `renderMs`. It would remain green if `drawWorld` moved outside `onWorld`, if timing stopped bracketing the render, or if these strings survived in dead code.

A static assertion can still pin this without browser automation by extracting `onWorld` and matching the ordered sequence: start time → `drawWorld` → elapsed calculation → status update.

### MEDIUM — Exact marker geometry is under-pinned

Several tests assert only broad inequalities instead of the documented operations:

- Nutrient need not remain centered 3×3 (`observer-markers.test.js:55-63`).
- The unbrained shell can shrink below 5×5 (`observer-markers.test.js:75-85`).
- The composite cue can change from 2×2 to any smaller size (`observer-markers.test.js:89-108`).
- Bonded polygons are not checked for exact points or positive area (`observer-markers.test.js:115-139`, `observer-markers.test.js:191-195`).
- The mutation cue can change from inset 3×3 to another inset shape (`observer-markers.test.js:144-159`).
- Unknown-species fallback can change from `#888` to any non-species colour (`observer-markers.test.js:209-214`).

The production geometry currently matches the contract, but these named tests would stay green through several contract-breaking regressions. Assert complete operation objects against test-owned literals and calculate nonzero polygon area.

### MEDIUM — Renderer expectations are partially self-referential and incomplete

The rectangular painter test calculates expected dimensions by calling the production helpers being exercised (`observer-render.test.js:154-155`) rather than using independent `61` and `25` literals. Separately, the “exact contract order” test compares only each layer’s first occurrence (`observer-render.test.js:112-133`), so an additional rock or toxin operation painted after lightning would pass.

Use independent dimensions and assert that every operation in each layer precedes every operation in the next layer.

### MEDIUM — The nutrient negative control never exercises the forbidden input

The test calls the nutrient “never infectable,” but `"n"` is absent from the infection set (`ObserverFrameBuilderTest.java:198-209`). An implementation that began projecting infection membership onto nutrients would therefore remain green. Include `"n"` in `envInfecting(...)`; the infected particle/structure cases already provide the positive control.

## Suggestions

### LOW — Make the default-off requirement durable

The committed default is correctly false (`src/main/resources/application.yml:238-239`), but CI only runs Spotless, Java tests, and `jsTest` (`.github/workflows/ci.yml:41-46`). Consider adding a configuration test or Gradle verification task so R10 remains protected after this one-time review.

## Risk Assessment

**MEDIUM.** No current HIGH correctness, wire-compatibility, aliasing, or CI-gate defect was found, and R11 remains observe-only with no performance threshold assertion. Risk comes from binding transformation contracts that are currently guarded by vacuous, self-referential, or incomplete tests, plus an overstated thread-confinement assumption. Per instruction, this was a static review; no tests, server, browser, or endpoints were run.

Available next steps: discuss a finding, save this review, re-review a narrower scope, or—if explicitly authorized—fix the issues.

---

## Opencode Review

Now let me verify a few final details about edge cases and potential issues.## Summary

Safe to merge. Wire contract additive and backward-compatible (`mutated` true-only, `schemaVersion` stays 1, Jackson NON_NULL omits clean). Infection seam safe — CHM keySet iterated on tick thread; record defensive copy makes ownership durable. JS renderer implements exact spec: 6px pitch, full layer order chain (background→grid→rocks→toxin→mutagen→entities→lightning), independent rectangular sizing, pinned colour literals. Build gate correctly guards against Node's zero-test-glob footgun; CI explicitly names `jsTest`. Tests are properly pinned with positive controls.

## Critical Issues

None.

## Warnings

1. **`EnvCleanupHooksBean.getInfections()` returns raw mutable CHM reference** — `EnvCleanupHooksBean.java:182`. Every caller today runs on the tick thread (safe), but the public `Map` return type invites off-thread concurrent access that would alias mutations. Worth returning an unmodifiable view or documenting the thread contract explicitly on the accessor.

2. **`ObserverFrameBuilderTest.mutatedProjectedForEveryInfectableKindWithCleanControls()` reuses same IDs across entity types** — `ObserverFrameBuilderTest.java:200`. `envInfecting("sickP", "sickBp", "sickCm")` uses distinct ID strings, but the test fixture relies on the builder's `infectedIds.contains(p.id())` matching by entity ID, not coordinate. If `Entity.id()` or `EntityIds.entityIdOf()` ever deduplicates across entity kinds without a namespace, this test silently vacates. Should add a negative control that asserts a clean entity of each kind is NOT in the set (already partially done with the `well*` entities, but the infection set contains only strings, so a regression in ID generation could make `wellBp` also match `sickBp`).

## Suggestions

1. **Fallback colour not pinned in test** — `observer-markers.test.js:209-213`. The "unknown species" test asserts the fallback colour is not a known species colour but never asserts it equals `#888`. The spec says `UNKNOWN_SPECIES_COLOR = "#888"`. Adding `assert.equal(filled[0].color, "#888")` would catch a mismatch between the declared constant and the actual value (turns out they're both `#888`, but the test doesn't know that).

2. **`GRID_COLOR` and `BACKGROUND_COLOR` exported from `observer-markers.js` but also used in `observer-render.js`** — `observer-render.js:11`. `observer-render.js` imports both from markers, keeping one source of truth. Fine, but the `ROCK_COLOR` re-export pattern (`export { ROCK_COLOR }` at line 17) is inconsistent — `GRID_COLOR` and `BACKGROUND_COLOR` are used but not re-exported, while `ROCK_COLOR` is re-exported only for the test. Minor maintainability asymmetry.

3. **`jsTest` `doFirst` preflight requires EXACT two files** — `build.gradle.kts:202`. Adding a third JS test file (e.g., `observer-ui.test.js`) doesn't break the gate, but deleting one of the two named files does. If the test suite grows to N files, the preflight becomes a maintenance burden — consider switching to a count check (≥N test files found by glob) rather than named-file existence, or just document that `requiredJsTests` must be updated when files are added/removed.

## Risk Assessment

**LOW.** All three claimed defects are demonstrably fixed in the source. Wire projection is additive and backward-compatible. Snapshot defensive copying is correct and enforced by record invariant. Layer ordering, geometry, and colour contracts are pinned by independent test assertions. The build gate closes the zero-test-glob hole with a reasonably aggressive preflight. No concurrency hazards, no data leaks, no vacuous tests that would stay green while the behaviour breaks.

---

## Pykrete Review (FAILED)

**Status:** failed — pykrete requires $PYKRETE_CONFIG to point at a pykrete.toml (NanoGPT config). See README 'Pykrete setup'.

Elapsed: 0.0s

---

## Consensus Summary

### Headline

All three reviewers found **no critical defects** and agree the production code is correct: the `mutated` wire addition is genuinely additive and true-only, snapshot collections are defensively copied, and the JS renderer matches the documented pitch, geometry, colours and layer order. The disagreement is over merge readiness — the shared theme is that the *gate* is weaker than the code, with under-pinned geometry/placement assertions and an overstated thread-confinement rationale on the infection seam.

### Agreed Strengths

- **Wire compatibility holds.** `mutated` is true-or-null via `Boolean.TRUE`/`null` plus `@JsonInclude(NON_NULL)` on the record itself; `schemaVersion` correctly stays `1`; the only in-repo consumer (`observer.html`) ignores the field — claude, codex, opencode. Claude additionally verified the test mapper is the production mapper's config, not a lookalike.
- **Snapshot immutability is real, not aspirational.** All four `EnvironmentSnapshot` components are copied at the record constructor, and all six construction sites were updated — claude, codex, opencode. Claude singles out `recordDetachesEveryCollectionFromItsMutableConstructorInput` as pinning the invariant record-direct rather than via one cooperating producer.
- **Renderer matches the spec.** 6px pitch, full layer chain (background -> grid -> rocks -> toxin -> mutagen -> entities -> lightning), independently derived width/height for rectangular worlds — claude, codex, opencode. This also fixes the rocks-over-environment layering bug (claude).
- **The `jsTest` preflight closes the zero-test glob hole**, and CI names `jsTest` explicitly rather than relying on the `check` binding — claude, codex, opencode. Claude notes the gate fails safe in every statically reasonable mode and reads as genuinely RED-tested; the CI comment documents the rot-prone failure mode at the point of use.
- **R11 is genuinely observe-only** — timing is bracketed in the page and the only test touching it is a static string gate; no threshold assertion exists in the default suite (claude, codex).
- **`mutatedProjectedForEveryInfectableKindWithCleanControls` uses per-kind positive controls** so suppressing any single `infectedIds.contains(...)` fails exactly one assertion (claude, opencode — though see divergence below).
- **Honest deferral.** `BACKLOG.md` records the render-budget deferral with a measured number and states plainly that a trigger has already fired (claude).

### Agreed Concerns

- **MEDIUM (all three) — the infection seam's recorded safety rationale is wrong.** The plan/javadoc claim strict tick-confinement, but `WorldWebSocketHandler.cleanupBot()` -> `clearInfectionOnDeath()` mutates the map from a WebSocket thread (`WorldWebSocketHandler.java:850,996`). The code is safe *only* because `EnvCleanupHooksBean.infections` is a `ConcurrentHashMap`, and nothing states or tests that dependency. Claude spells out the footgun: a "simplification" to `HashMap` on the strength of the false rationale lets `Set.copyOf` throw CME, which `ObserverBroadcaster` catches and logs at WARN — the observer silently stops updating with no test failure. Codex adds that a frame can transiently combine a pre-cleanup grid with post-cleanup infection state. Opencode frames the same seam from the accessor side: `getInfections()` returns the raw mutable CHM, inviting off-thread aliasing. Agreed fix shape is documentation, not machinery — name the off-tick writers on the field / accessor, or funnel cleanup through the tick thread.
- **MEDIUM (claude, codex) — geometry and placement are under-pinned by the JS suite.** Claude: *no* test asserts the x/y of any painted cell, and the layer-order fixture is a 4x4 square with four diagonal points, invariant under transposition — swapping the `cellOrigin` arguments, dropping the `+1`, or swapping `ox`/`oy` leaves the whole suite green. Codex enumerates the marker-side equivalent: nutrient need not stay centred 3x3, the unbrained shell can shrink below 5x5, the composite cue can shrink from 2x2, bonded polygons are checked for neither exact points nor positive area, the mutation cue can change from inset 3x3 to any inset shape. Both prescribe asserting complete operation objects against test-owned literals, plus asymmetric fixture coordinates.
- **MEDIUM (claude, codex) — layer-order tests match first occurrence only.** `findIndex` per layer means a renderer painting rocks in two passes (one after toxin/lightning) still passes. Fix: assert every op in each layer precedes every op in the next, or pair `lastIndexOf`/`findIndex` on each boundary as the grid->rock test already does.
- **MEDIUM-LOW (claude, codex) — the R8/R11 page-wiring gate is a bare string-presence check.** A single stray `performance.now()` and any `renderMs` assignment satisfies it; `drawWorld` could move out of `onWorld` or the timer could stop bracketing the render and the test stays green. Both propose an ordered-sequence regex or extracting `onWorld` and matching start -> `drawWorld` -> elapsed -> status. Claude classes this as a sharpening (the test's own javadoc admits it is static), codex as a MEDIUM.
- **LOW-MEDIUM (all three) — the unknown-species fallback is pinned only negatively.** `observer-markers.test.js:209-214` asserts the fallback is not one of the three species colours, which `#000` or `#e35` would satisfy; `#888` itself is never asserted. Claude additionally flags the mirror-image drift: `LIGHTNING_COLOR`/`ROCK_COLOR` are asserted as "the exact contract value" but neither literal appears in `docs/SCHEMA.md` §14.

### Divergent Views

- **Merge readiness.** Claude and opencode say **safe to merge** (LOW risk) with the caveats tracked; codex says **hold the merge** (MEDIUM risk) until the test and concurrency-contract items are addressed. The substantive gap is interpretive, not factual: all three found the same issues and none found a live correctness defect. Codex weights "binding transformation contracts guarded by vacuous or self-referential tests" as blocking; claude weights it as a ten-line follow-up whose residual risk lands only where a *future* viewport edit would bite.
- **The nutrient negative control.** Codex calls it MEDIUM-vacuous — `"n"` is absent from the infection set, so an implementation that started projecting infection onto nutrients stays green; fix is to include `"n"` in `envInfecting(...)`. Claude cites the *same* test as a strength ("textbook") on the strength of its per-kind positive controls. Opencode raises a third, different concern about the same test: the fixture depends on ID-namespace separation across entity kinds, so an `EntityIds` regression that collides IDs across kinds would silently vacate it. These are compatible observations about different clauses of one test — worth reading together rather than picking a side.
- **Whether the infection seam is a real hazard.** Opencode assesses the accessor as safe today ("every caller runs on the tick thread") and suggests hardening the return type; claude and codex both identify a concrete off-tick writer that contradicts that premise. Claude's and codex's file-and-line evidence (`WorldWebSocketHandler.java:996`) is the more specific claim here.
- **The `jsTest` preflight design.** Claude and codex treat the named-file preflight as a strength with no reservation; opencode flags it as a future maintenance burden (a third test file is silently unguarded; deleting a named file breaks the build) and suggests a count-based check or an explicit note that `requiredJsTests` must be kept in sync.
- **Self-referential expectations in the render test.** Codex flags that the rectangular painter test derives expected dimensions by calling the production helpers under test rather than using independent `61`/`25` literals — a direct CLAUDE.md testing-philosophy violation. Claude reviewed the same test and raised the adjacent gap (positions unasserted) without flagging the self-reference; opencode did not raise it. Codex's point stands on its own reading and is cheap to close.

### Unique Findings Worth Triaging

- **Merge-back gap (claude, MEDIUM-LOW):** `build.gradle.kts:222` makes `check`/`build` depend on `jsTest`, so `./gradlew build` now fails without `node` on PATH — but CLAUDE.md's Technology Stack and Build commands sections are unchanged. This is a live close-out-gate miss under the project's own merge-back rule.
- **`ObserverPageServesTest` doesn't assert `Content-Type` (claude):** a wrong MIME type makes the browser reject both `import` statements and blanks the page with no server-side signal — the exact silent break the test comment worries about.
- **Per-tick redundant copy (claude):** `snapshot()` builds fresh `ArrayList`s that the compact constructor then `List.copyOf`s again — ~67k extra reference copies per tick at the saturated default world, same shape as the Wave-1 `Map.copyOf` hotfix. Keep the invariant; know the cost.
- **Configuration-cache hazard (claude):** `build.gradle.kts:209` reads `layout.projectDirectory` inside `doFirst`, capturing `Project` at execution time — harmless today, fails under `--configuration-cache`.
- **Canvas ceiling (claude):** `cells * 6 + 1` exceeds Chrome's 16,384px backing-store limit at ~2,730 cells, silently. Adding the number makes the backlog viewport trigger decidable.
- **`observer.html` guards `entities`/`env` but not `scoreboard`/`populations` (claude):** `TypeError` on a frame missing those keys, aborting `onWorld` after the canvas repaint. Not a live bug (the builder always emits them), but inconsistent with the stated "graceful defaults" intent — and adjacent to the vacuous graceful-degradation test claude flags at W2, where all three collections are passed *present-but-empty* so none of the `??` fallbacks are actually exercised.
- **Default-off durability (codex, LOW):** the committed default is correctly `false`, but nothing in CI protects R10 after this one-time review — a config test or Gradle verification task would make it durable.

### Suggested Triage Order

Close before or immediately after merge: the infection-seam comment (W3 — cheapest fix, highest future cost if lost), cell-placement assertions (W1), and the CLAUDE.md Node/`jsTest` merge-back. The rest — marker-geometry literals, all-ops layer ordering, `#888`, the nutrient `"n"` control, the self-referential dimension literals, `Content-Type` — are a coherent single follow-up slice on test sharpening.
