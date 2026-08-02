# Observer Key and Layer Toggles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a merged Key / Layers panel to the observer visualiser page — a legend whose swatches are drawn by the real marker painter, plus six per-layer visibility checkboxes.

**Architecture:** Three client-side layers. `observer-markers.js` (unchanged) owns geometry. `observer-render.js` gains a shared `paintMarker` painter, a `LAYER_KEYS` constant, and layer gating inside `drawWorld`. A new pure `observer-legend.js` exports `LEGEND_ROWS` — row data only, DOM-free and canvas-free so `node --test` covers it. `observer.html` builds the panel from those two modules and repaints from a retained `lastFrame`. No server, wire-protocol, or simulation change.

**Tech Stack:** Vanilla ES modules served as Spring Boot static content (no build pipeline, no npm dependencies). Node 22 `node --test` via `./gradlew jsTest`. JUnit 5 + AssertJ + `TestRestTemplate` for the serve-checks. Gradle Kotlin DSL. Spotless (`ratchetFrom("origin/main")`) gates Java formatting only — the JS and HTML are not Spotless-managed.

**Task order is strictly sequential.** Task 2 imports what Task 1 exports; Task 3's tests import what Task 2 exports; Task 4 consumes all three. Do not dispatch tasks in parallel.

**Spec:** [`docs/superpowers/specs/2026-08-02-observer-key-and-layer-toggles-design.md`](../specs/2026-08-02-observer-key-and-layer-toggles-design.md). It carries the rationale this plan does not repeat.

## Global Constraints

Every task's requirements implicitly include this section. Values are verbatim from the spec.

- **Layer keys, exactly six, this spelling:** `["entities", "nutrients", "toxin", "mutagen", "lightning", "grid"]`. Note `nutrients` is plural, while the *entity kind* it gates is the singular `"nutrient"`.
- **The visibility map rides on the state object as `state.layers`.** `drawWorld`'s signature stays `(ctx, state)`; `layers` is a fourth key alongside `grid` / `rocks` / `entities` / `env`. Every producer and consumer uses that exact path.
- **Visibility predicate:** `layers?.[key] !== false`. Absent, `undefined`, and any non-`false` value (including `0`) are visible. Not `?? true`, not `!!`.
- **Layer order is unchanged:** background → grid → rocks → toxin → mutagen → entities → lightning. Hiding a layer skips it; it never reorders the rest. The background fill is never gated — hiding `grid` leaves `BACKGROUND_COLOR` gutters, not holes. Rocks are never gated.
- **Nutrients are not a flat layer.** They arrive inside `state.entities` with `kind === "nutrient"`, so the entity loop gates per item: nutrient items on `nutrients`, everything else (`particle`, `bondedPair`, `compositeMember`) on `entities`.
- **Species literals are the exact uppercase keys `markerOps` switches on:** `CATALYST`, `MEMBRANE`, `SPORE`. Any other string falls through to `UNKNOWN_SPECIES_COLOR`. `compositeId` must be a **string** — the cue-colour hash calls `.length` and `.charCodeAt` on it.
- **Row shape:** `{ label, entity?, swatch?, layer?, note? }`. Exactly one row — the collective control, whose `layer` is `"entities"` — carries `layer` with neither `entity` nor `swatch`. Every other row has `entity` or a non-empty-string `swatch`. `entity` and `layer` compose (the nutrient row carries both).
- **Row counts:** 15 rows total; 14 visual; 9 rows carry `entity`; 8 of those are *marker rows* (`"entity" in row && !("layer" in row)`); exactly 6 rows carry `layer`, with no duplicates, matching `LAYER_KEYS`.
- **Swatch surface:** one `<canvas width="6" height="6">` per **visual** row, drawn 1:1, CSS-upscaled to 24 px by a scoped `#knobs canvas { width: 24px; height: 24px; }`. Never `ctx.scale()`. Every swatch canvas fills `BACKGROUND_COLOR` before painting.
- **Dim state:** hidden rows get a `.off` class at `opacity: 0.4`.
- **No colour is retyped.** Swatches come from the existing exports/functions, never a hand-typed hex. `GRID_COLOR` is imported from `observer-markers.js` (`observer-render.js:17` re-exports only `ROCK_COLOR`).
- **JS tests use `node:test` + `node:assert/strict`**, importing production modules by relative path (`../../main/resources/static/…`). No npm dependencies. Java tests use AssertJ, matching the surrounding package.
- **No server contact.** `/ws/observer` stays send-only. No new WebSocket message, no fetch, no `localStorage`.
- **`build.gradle.kts:202`'s `requiredJsTests` must list every file in `src/test/js/`** — Node exits 0 on a zero-match glob, so an unregistered file lets the gate pass vacuously.
- **Commit after every task.** Run `./gradlew jsTest` for JS changes and `./gradlew test --tests 'com.paralife.observer.*'` for Java changes before committing.

---

### Task 1: Extract the shared marker painter

**Depends on:** nothing.

The op-to-canvas dispatch is currently inlined in `drawWorld`'s entity loop, and `paintOps` is module-private. The legend needs both. The extraction itself is behaviour-preserving, but it is **not** untested: no existing `drawWorld` test ever produces an `outline` or a `poly` op — the fixture's only entity is a brained particle, which is a single `fill` — so two of the three dispatch branches have no coverage at all, and a missing `export` would not fail any gate. This task adds the one test that closes both holes.

**Files:**
- Modify: `src/main/resources/static/observer-render.js:40-60` (export `paintOps`, update its docstring), `:88-96` (extract `paintMarker`, call it)
- Test: `src/test/js/observer-render.test.js` (one new test; the existing ten must stay green)

**Interfaces:**
- Consumes: `markerOps` from `observer-markers.js` (already imported)
- Produces:
  ```js
  /** The canvas 2D surface the renderer and the legend both paint through. */
  export function paintOps(ctx) // -> { fillRect(x,y,w,h,color), strokeRect(x,y,w,h,color), poly(points,color) }

  /** Paint one entity's markers at a cell's content origin. drawWorld and the legend share this. */
  export function paintMarker(ops, entity, ox, oy) // -> void
  ```
  `paintMarker` takes the **already-built ops surface**, not a raw context. Building it inside would move `paintOps(ctx)` from once-per-frame to once-per-entity in the hot path.

- [ ] **Step 1: Read the current dispatch and run the green baseline**

Read `src/main/resources/static/observer-render.js`. The three-branch loop at `:91-95` and the `const ops = paintOps(ctx)` hoist at `:72` are what this task moves.

Run: `./gradlew jsTest`
Expected: PASS. Record the test count — every one of them must still pass afterwards.

- [ ] **Step 2: Write the failing test**

Append to `src/test/js/observer-render.test.js`. Add `paintOps` and `paintMarker` to the **existing** render-module import at `:4-13` — do not add a second `import` statement from that path.

```js
// drawWorld's fixture contains only a brained particle, which markerOps resolves
// to a single `fill`. The strokeRect and poly branches of the dispatch therefore
// have no coverage anywhere. Driving them through the extracted seam pins both,
// and importing the two symbols by name makes a missing `export` a link-time
// failure here rather than a page that dies silently in the browser.
test("the shared painter dispatches all three op kinds at the origin it is given", () => {
  const ctx = recordingContext();
  const ops = paintOps(ctx);
  const [ox, oy] = [13, 7]; // hand-computed: cellOrigin(2), cellOrigin(1)

  paintMarker(ops, { kind: "particle", species: "SPORE", brained: false }, ox, oy);
  paintMarker(ops, { kind: "bondedPair", primarySpecies: "CATALYST", secondarySpecies: "MEMBRANE" }, ox, oy);

  // Outline: the half-pixel inset is the painter's contract, not the marker's.
  // Expected values are hand-computed from ox/oy and CONTENT_SIZE, never read
  // back from paintOps.
  const stroke = ctx.calls.find((c) => c.fn === "strokeRect");
  assert.ok(stroke, "no outline was dispatched — the strokeRect branch is unexercised");
  assert.deepEqual(
    { x: stroke.x, y: stroke.y, w: stroke.w, h: stroke.h, color: stroke.color },
    { x: 13.5, y: 7.5, w: 4, h: 4, color: SPECIES_COLOR.SPORE },
  );

  // Poly: every point carries the same origin offset. A dropped `.map` or a
  // transposed ox/oy shows up here and nowhere else.
  const polys = ctx.calls.filter((c) => c.fn === "fill");
  assert.equal(polys.length, 2, "a bonded pair is two triangles");
  assert.deepEqual(polys[0].points, [[13, 7], [18, 12], [13, 12]]);
  assert.equal(polys[0].color, SPECIES_COLOR.CATALYST);
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew jsTest`
Expected: FAIL at module link time — `does not provide an export named 'paintOps'`. That takes the whole file down, including the existing tests; that is the expected shape of this RED.

- [ ] **Step 4: Export `paintOps` and extract `paintMarker`**

Add `export` to `paintOps` and amend its docstring: it is now shared with the legend, not "the canvas 2D surface *this module* uses". Move the body of `drawWorld`'s inner `for (const op of markerOps(e))` loop verbatim into `paintMarker(ops, entity, ox, oy)`, keeping the branch order (`fill` → `outline` → `poly` fallthrough) and the `ox + op.x` / `oy + op.y` offsets exactly as they are.

`drawWorld`'s entity loop becomes:

```js
for (const e of state.entities ?? []) {
  paintMarker(ops, e, cellOrigin(e.x), cellOrigin(e.y));
}
```

The emitted op sequence must be byte-identical to before — that is what keeps the layer-order, placement, and sizing tests green.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew jsTest`
Expected: PASS — the new test plus every pre-existing one. A failure in `every layer paints, in exactly the contract order` or `every layer paints its cell at the pixel origin for its own coordinates` means the extraction changed op order or offsets.

- [ ] **Step 6: RED-test the new gate**

Apply, run `./gradlew jsTest`, record the failing assertion message, revert.

| # | Mutation | Must fail |
|---|---|---|
| 1 | Drop the `ox + op.x` offset in the `outline` branch | the `strokeRect` `deepEqual` |
| 2 | Drop the `.map` offset in the `poly` branch | the `polys[0].points` `deepEqual` |
| 3 | Remove `export` from `paintMarker` | module link error — `does not provide an export named 'paintMarker'` |

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/observer-render.js src/test/js/observer-render.test.js
git commit -m "refactor(observer): extract the shared marker painter"
```

Include the Step 6 RED evidence in the commit body — one line per mutation with the message it produced.

---

### Task 2: Gate drawWorld on state.layers

**Depends on:** Task 1 (`paintMarker`, `paintOps`).

**Files:**
- Modify: `src/main/resources/static/observer-render.js` (add `LAYER_KEYS` + `visible`, gate the six layers)
- Test: `src/test/js/observer-render.test.js` (extend the fixture; add three tests)

**Interfaces:**
- Consumes: `paintMarker`, `paintOps` from Task 1
- Produces:
  ```js
  export const LAYER_KEYS = ["entities", "nutrients", "toxin", "mutagen", "lightning", "grid"];
  ```
  `drawWorld(ctx, state)` additionally reads `state.layers` — an object of `layerKey -> boolean`, or absent. `visible` stays **module-private**; it is an implementation detail, and these tests pin its semantics through `drawWorld`'s observable output.

- [ ] **Step 1: Extend the shared fixture with a nutrient and a layers parameter**

In `src/test/js/observer-render.test.js`, add a nutrient to `PLACED` and thread a `layers` argument through `paintedWorld`. The coordinate must keep the file's stated invariant — `x !== y`, inside the 8×5 grid, not colliding with an existing placement:

```js
const PLACED = {
  rock:      { x: 1, y: 0 },
  entity:    { x: 6, y: 1 },
  nutrient:  { x: 4, y: 2 },   // new: x !== y, distinct from every other placement
  toxin:     { x: 2, y: 3 },
  mutagen:   { x: 7, y: 4 },
  lightning: { x: 0, y: 2 },
};

// `layers` is passed through verbatim, so paintedWorld() with no argument is the
// all-visible / back-compat case the existing tests already assert against.
function paintedWorld(layers) { /* ...existing body, plus the nutrient in `entities`, plus `layers` in the state object... */ }
```

The nutrient goes in the `entities` array as `{ ...PLACED.nutrient, kind: "nutrient" }` — that is where the wire puts it.

- [ ] **Step 2: Run the suite — the existing tests must still pass with the extended fixture**

Run: `./gradlew jsTest`
Expected: PASS. If `every layer paints, in exactly the contract order` fails, the nutrient collided with another layer's colour or coordinate. Fix the fixture, not the test.

- [ ] **Step 3: Write the three failing tests**

These are the contracts. Assertion shape matters more than helper mechanics.

**Imports:** add `LAYER_KEYS` to the **existing** render-module import at `:4-13`, and `NUTRIENT_COLOR` to the **existing** markers import at `:15`. Do not add new `import` statements — `drawWorld`, `SPECIES_COLOR`, `GRID_COLOR`, `BACKGROUND_COLOR`, `ROCK_COLOR` and `LIGHTNING_COLOR` are already bound, and re-declaring one is a `SyntaxError` that takes the whole file down.

```js
// Each key's ops are identified by the COLOUR they paint, never by call index.
// A count-only assertion ("fewer ops when hidden") passes on crossed wiring —
// toxin gated on the mutagen key and vice versa still changes the count for both.
const LAYER_COLOR_MATCH = {
  entities:  (c) => c.color === SPECIES_COLOR.CATALYST,
  nutrients: (c) => c.color === NUTRIENT_COLOR,
  toxin:     (c) => typeof c.color === "string" && c.color.startsWith("rgba("),
  mutagen:   (c) => typeof c.color === "string" && c.color.startsWith("hsla("),
  lightning: (c) => c.color === LIGHTNING_COLOR,
  grid:      (c) => c.color === GRID_COLOR,
};
// Never gated. Named here so the collateral clause below can assert they survive.
const UNGATED_MATCH = {
  background: (c) => c.color === BACKGROUND_COLOR,
  rock:       (c) => c.color === ROCK_COLOR,
};

test("every layer key gates exactly its own layer and nothing else", () => {
  for (const key of LAYER_KEYS) {
    // A key with no predicate would otherwise crash `filter(undefined)` with a
    // TypeError and read as a broken test rather than as drift.
    assert.ok(LAYER_COLOR_MATCH[key], `no colour predicate for ${key} — LAYER_KEYS drifted`);

    const shown  = paintedWorld({ [key]: true });
    const hidden = paintedWorld({ [key]: false });
    const countIn = (calls, pred) => calls.filter(pred).length;

    // Positive control: same fixture, one flag flipped, so a zero below cannot
    // come from a fixture that simply never populated this layer.
    assert.ok(countIn(shown, LAYER_COLOR_MATCH[key]) > 0, `${key} painted nothing when visible`);
    assert.equal(countIn(hidden, LAYER_COLOR_MATCH[key]), 0, `${key} still painted when hidden`);

    // Collateral: hiding one layer must not disturb any other, nor the two
    // ungated ones. This is what catches an over-broad gate (e.g. a `grid`
    // check whose scope swallows the rock loop) and what pins "hiding grid
    // leaves BACKGROUND_COLOR gutters, not holes".
    for (const [other, pred] of Object.entries({ ...LAYER_COLOR_MATCH, ...UNGATED_MATCH })) {
      if (other === key) continue;
      assert.equal(countIn(hidden, pred), countIn(shown, pred), `hiding ${key} disturbed ${other}`);
    }
  }
});

// Both keys act on the same array, so direction is not derivable from the test
// above: a nutrient branch that read the `entities` key would pass it.
test("the nutrient split gates per item, in both directions", () => {
  const noNutrients = paintedWorld({ entities: true, nutrients: false });
  assert.ok(noNutrients.some((c) => c.color === SPECIES_COLOR.CATALYST), "the particle stays");
  assert.ok(!noNutrients.some((c) => c.color === NUTRIENT_COLOR), "the nutrient goes");

  const noEntities = paintedWorld({ entities: false, nutrients: true });
  assert.ok(noEntities.some((c) => c.color === NUTRIENT_COLOR), "the nutrient stays");
  assert.ok(!noEntities.some((c) => c.color === SPECIES_COLOR.CATALYST), "the particle goes");
});

// `0` is the discriminator: (0 ?? true) === 0 and !!0 === false both HIDE it,
// while 0 !== false renders it. The undefined cases separately kill `!!` and
// `=== true`. Drop either half and one alternative goes unpinned.
test("only an explicit false hides a layer", () => {
  for (const layers of [undefined, {}, { toxin: undefined }, { toxin: 0 }]) {
    const calls = paintedWorld(layers);
    assert.ok(
      calls.some((c) => typeof c.color === "string" && c.color.startsWith("rgba(")),
      `toxin must render for layers=${JSON.stringify(layers)}`,
    );
  }
});
```

- [ ] **Step 4: Run to verify the link-stage failure**

Run: `./gradlew jsTest`
Expected: FAIL with `does not provide an export named 'LAYER_KEYS'`. The whole file fails to link, so nothing else runs. This proves the import, not the behaviour — the behavioural RED is Step 5.

- [ ] **Step 5: Export `LAYER_KEYS` only, and re-run for the real RED**

Add the exported constant to `observer-render.js` with the six values from Global Constraints. Add **no gating yet**.

Run: `./gradlew jsTest`
Expected: FAIL for the spec reason, with these messages:
- test 1: `entities still painted when hidden`
- test 2: `the nutrient goes`
- test 3: **passes.** All layers are visible before any gating exists, so the back-compat test is green from the start. It is a regression guard, and its RED demonstration is mutation 5 in Step 8 — not this step.

- [ ] **Step 6: Implement the gating**

Read `layers` once as `const layers = state.layers` alongside the existing `const env = state.env ?? {}`, add the module-private `const visible = (layers, key) => layers?.[key] !== false;`, and gate:

| Layer | Where | Gate |
|---|---|---|
| grid | both border loops | `visible(layers, "grid")` |
| toxin / mutagen / lightning | their `for` loops | `visible(layers, <key>)` |
| entities / nutrients | per item inside the entity loop | `e.kind === "nutrient" ? visible(layers, "nutrients") : visible(layers, "entities")` |

Do **not** gate the background fill or the rock loop.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew jsTest`
Expected: PASS, including every pre-existing test.

- [ ] **Step 8: RED-test the new gates**

Per CLAUDE.md's *Gates are RED-first*: a gate never shown to fire is theatre. For each mutation, apply it, run `./gradlew jsTest`, record the failing assertion message, then revert. The messages below are the ones these assertions actually emit — a mutation that throws early aborts its loop, so the *first* failure is what gets recorded.

| # | Mutation | Must fail with |
|---|---|---|
| 1 | Delete one `visible()` call in `drawWorld` | `<key> still painted when hidden` |
| 2 | Swap the toxin and mutagen gate keys | `toxin still painted when hidden` — the key's own zero-assertion throws before the collateral loop is reached |
| 3 | Widen the `grid` gate so the rock loop at `:84` falls inside it | `hiding grid disturbed rock` — this is the collateral clause's demonstration |
| 4 | Make the nutrient branch read the `entities` key | test 1: `hiding entities disturbed nutrients`; test 2: `the nutrient goes` |
| 5 | Change `!== false` to `?? true` | `toxin must render for layers={"toxin":0}` |
| 6 | Change `!== false` to `!!`, then to `=== true` | `toxin must render for layers=undefined` — the loop's first element. Both also cascade into test 1 (`entities painted nothing when visible`), which is expected, not a second defect |

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/observer-render.js src/test/js/observer-render.test.js
git commit -m "feat(observer): gate the renderer's layers on state.layers"
```

Include the Step 8 RED evidence in the commit body — one line per mutation with the message it produced.

---

### Task 3: The legend data module and its gates

**Depends on:** Task 2 (`LAYER_KEYS`).

**Files:**
- Create: `src/main/resources/static/observer-legend.js`
- Create: `src/test/js/observer-legend.test.js`
- Modify: `build.gradle.kts:202` (register the new test file)

**Interfaces:**
- Consumes, **in the module**: `GRID_COLOR` from `observer-markers.js`; `ROCK_COLOR`, `LIGHTNING_COLOR`, `toxinColor`, `mutagenColor` from `observer-render.js`. That is the whole list.
  **`LAYER_KEYS` is imported by the test file only, never by the module.** Row `layer` values are written as literal strings; deriving them from `LAYER_KEYS` would make the drift gate tautological — it exists precisely to catch the two lists disagreeing.
  Species values are the uppercase literals from Global Constraints; the module does not need `SPECIES_COLOR` itself, since `markerOps` resolves the colour.
- Produces:
  ```js
  /**
   * One row per visual state, in panel order. DOM-free and canvas-free.
   *   label   — display text (required, rendered by the page)
   *   entity  — synthetic DTO, painted through the real markerOps()/paintMarker()
   *   swatch  — a flat colour string, for layers markerOps does not draw
   *   layer   — the LAYER_KEYS entry this row's checkbox toggles
   *   note    — a qualifier rendered beside the label
   * A row carries `entity` or `swatch`, except the one collective control
   * (layer "entities") that carries `layer` alone. `entity` and `layer`
   * compose — the nutrient row carries both.
   */
  export const LEGEND_ROWS = [ /* 15 rows */ ];
  ```

- [ ] **Step 1: Write the failing gates**

Create `src/test/js/observer-legend.test.js`. Five tests — they are the module's contract; the row data is written to satisfy them.

```js
import { test } from "node:test";
import assert from "node:assert/strict";

import { LEGEND_ROWS } from "../../main/resources/static/observer-legend.js";
import { LAYER_KEYS, LIGHTNING_COLOR, ROCK_COLOR } from "../../main/resources/static/observer-render.js";
import { markerOps, GRID_COLOR } from "../../main/resources/static/observer-markers.js";

// Row selection is by KEY PRESENCE throughout, never truthiness. A row whose
// `entity` was blanked to undefined must be caught, not silently skipped.
const entityRows = LEGEND_ROWS.filter((r) => "entity" in r);
const rowNamed = (label) => LEGEND_ROWS.find((r) => r.label === label);

// Array, not set: two rows bound to one key bind two checkboxes to one flag and
// desynchronise on click, which set-equality cannot see.
test("the key's layer rows are exactly LAYER_KEYS, without duplicates", () => {
  const declared = LEGEND_ROWS.filter((r) => "layer" in r).map((r) => r.layer);
  assert.deepEqual([...declared].sort(), [...LAYER_KEYS].sort());
});

// markerOps pushes the mutation outline OUTSIDE its switch, so a typo'd `kind`
// on a mutated row still returns one op. Stripping `mutated` removes that free
// pass and makes the gate fire on the row it most needs to cover.
test("every entity row draws its own species body, not just a cue", () => {
  for (const row of entityRows) {
    const ops = markerOps({ ...row.entity, mutated: false });
    assert.ok(ops.length > 0, `${row.label} draws nothing`);
  }
  // Positive control: proves the assertion above can fail at all.
  assert.equal(markerOps({ kind: "nope" }).length, 0);
});

// Presence-checked disjuncts. A truthiness check would let a blanked swatch pass
// on the strength of its `layer` key — four of the five swatch rows.
test("every row has a paintable identity, and the one label-only row is the entities control", () => {
  const labelOnly = LEGEND_ROWS.filter((r) => "layer" in r && !("entity" in r) && !("swatch" in r));
  assert.equal(labelOnly.length, 1, "the collective entities control is the only label-only row");
  // Without this, moving the label-only shape onto Lightning passes every other
  // gate while the page dims all eight marker rows off the wrong checkbox.
  assert.equal(labelOnly[0].layer, "entities", "the label-only row is the entities control");

  for (const row of LEGEND_ROWS) {
    if (labelOnly.includes(row)) continue;
    const hasSwatch = "swatch" in row && typeof row.swatch === "string" && row.swatch.length > 0;
    assert.ok("entity" in row || hasSwatch, `${row.label} has no paintable identity`);
  }
});

// Test 2 proves a row draws SOMETHING; this proves it draws ITS OWN thing. On
// RAW entities — reusing test 2's `mutated: false` override would make the
// Mutated row equal its base species row and fail spuriously.
test("no two entity rows render identically", () => {
  const drawn = entityRows.map((r) => JSON.stringify(markerOps(r.entity)));
  assert.equal(new Set(drawn).size, entityRows.length, "two rows draw the same swatch");
});

// The page's panel logic and this plan's Global Constraints both quote these
// counts, and neither is checked anywhere else. Dropping a row that feels
// redundant (Unbrained, Unknown species) otherwise passes every gate above.
// The colour identities pin "no colour is retyped" to the exports.
test("the row inventory matches the panel contract", () => {
  assert.equal(LEGEND_ROWS.length, 15);
  assert.equal(entityRows.length, 9);
  assert.equal(LEGEND_ROWS.filter((r) => "entity" in r && !("layer" in r)).length, 8, "marker rows");
  assert.equal(rowNamed("Lightning").swatch, LIGHTNING_COLOR);
  assert.equal(rowNamed("Rock").swatch, ROCK_COLOR);
  assert.equal(rowNamed("Grid lines").swatch, GRID_COLOR);
});
```

- [ ] **Step 2: Register the test file, then run to verify it fails**

Add `"observer-legend.test.js"` to `requiredJsTests` at `build.gradle.kts:202`.

Run: `./gradlew jsTest`
Expected: FAIL — `Cannot find module` / `observer-legend.js` does not exist.

- [ ] **Step 3: Write the row data**

Create `observer-legend.js` with 15 rows in this order. Labels are the exact strings the inventory gate looks up.

| # | `label` | Shape | Notes |
|---|---|---|---|
| 1 | `Catalyst` | `entity: { kind: "particle", species: "CATALYST", brained: true }` | |
| 2 | `Membrane` | `entity: { kind: "particle", species: "MEMBRANE", brained: true }` | |
| 3 | `Spore` | `entity: { kind: "particle", species: "SPORE", brained: true }` | |
| 4 | `Unbrained` | `entity: { kind: "particle", species: <one of the three>, brained: false }` | shell only — nothing running it |
| 5 | `Unknown species` | `entity: { kind: "particle", species: <any string that is NOT one of the three>, brained: true }` | falls back to `UNKNOWN_SPECIES_COLOR` |
| 6 | `Bonded pair` | `entity: { kind: "bondedPair", primarySpecies: <one>, secondarySpecies: <a different one> }` | two *distinct* species, or the diagonal split is invisible |
| 7 | `Composite member` | `entity: { kind: "compositeMember", species: <one>, compositeId: <a string> }` | `note`: the cue hue is per-composite, so this swatch is illustrative |
| 8 | `Mutated` | `entity: { kind: "particle", species: <one>, brained: true, mutated: true }` | must differ from rows 1–3 under raw `markerOps` — pick a species/brained combination no other row uses, or vary `brained` |
| 9 | `Nutrient` | `entity: { kind: "nutrient" }`, `layer: "nutrients"` | the one row carrying both |
| 10 | `Rock` | `swatch: ROCK_COLOR` | no `layer` — not toggleable |
| 11 | `Toxin` | `swatch: toxinColor(160)`, `layer: "toxin"` | `note`: opacity tracks intensity |
| 12 | `Mutagen` | `swatch: mutagenColor(<any strain>)`, `layer: "mutagen"` | `note`: hue tracks strain |
| 13 | `Lightning` | `swatch: LIGHTNING_COLOR`, `layer: "lightning"` | |
| 14 | `Entities` | `layer: "entities"` only | `note`: all non-nutrient markers |
| 15 | `Grid lines` | `swatch: GRID_COLOR`, `layer: "grid"` | |

Rows 1–8 are the *marker rows* (`entity`, no `layer`) — the eight the page dims collectively. Every colour is imported; none is retyped as a literal.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew jsTest`
Expected: PASS.

- [ ] **Step 5: RED-test each gate**

Apply, run `./gradlew jsTest`, record the failure, revert.

| # | Mutation | Must fail with |
|---|---|---|
| 1 | Add a seventh entry to `LAYER_KEYS` only | the drift gate's `deepEqual`. It also reds `observer-render.test.js` test 1 with `no colour predicate for <key> — LAYER_KEYS drifted`; that is the guard working, not a second defect |
| 2 | Give a second row `layer: "toxin"` | the drift gate's `deepEqual` (duplicate) |
| 3 | Typo the Mutated row's `kind` | `Mutated draws nothing` — the one that would have passed a raw length check |
| 4 | Set the Toxin row's `swatch` to `""` | `Toxin has no paintable identity` |
| 5 | **Delete** the Grid lines row's `swatch` **key** | `the collective entities control is the only label-only row` — deleting the key changes the row's *shape*, where blanking it (mutation 4) does not |
| 6 | Move the label-only shape onto Lightning and give Entities a swatch | `the label-only row is the entities control` |
| 7 | Copy the Catalyst row's `entity` onto Membrane | `two rows draw the same swatch` |
| 8 | Delete the Unknown species row | the inventory gate's `LEGEND_ROWS.length` |
| 9 | Replace the Grid lines row's `swatch` with the literal `"#ddd"` | *nothing* — the inventory gate compares against `GRID_COLOR`, whose value is `#ddd`. Note this: the "no colour retyped" constraint is pinned only against a *wrong* literal, not an equal one. Code review covers the rest |
| 10 | Delete `src/test/js/observer-legend.test.js` | Gradle: `Missing required JS test file(s)` |

Mutation 10 is the `requiredJsTests` preflight itself — the gate that exists because Node exits 0 on a zero-match glob.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/observer-legend.js src/test/js/observer-legend.test.js build.gradle.kts
git commit -m "feat(observer): add the legend row data and its drift gates"
```

Include the Step 5 RED evidence in the commit body.

---

### Task 4: Build the panel and wire the page

**Depends on:** Tasks 1–3.

**Files:**
- Modify: `src/main/resources/static/observer.html` (CSS at `:10-19`, markup at `:24-33`, module script at `:34-127`)
- Modify: `src/test/java/com/paralife/observer/ObserverPageServesTest.java:40-53` and `:63-68`

**Interfaces:**
- Consumes from `./observer-render.js`: `LAYER_KEYS`, `paintOps`, `paintMarker`, `drawWorld`, `cellOrigin`, `canvasWidth`, `canvasHeight`
- Consumes from `./observer-legend.js`: `LEGEND_ROWS`
- Consumes from `./observer-markers.js`: `BACKGROUND_COLOR`, `CONTENT_SIZE`, `CELL_PITCH`, and the already-imported `SPECIES_COLOR`
- `drawWorld(ctx, state)` — signature unchanged; the visibility map rides as `state.layers`
- Produces: no exports — this is the page.

**Page contracts** (the invariants a reviewer checks; mechanics are the implementer's):

1. **No layer-key string is typed in the page.** `layers` initialises as `Object.fromEntries(LAYER_KEYS.map((k) => [k, true]))`; checkboxes come from iterating `LEGEND_ROWS` and reading `row.layer`. Inline page script is outside `jsTest`'s reach, so deriving the keys from tested constants is what stands in for the missing coverage.
2. **The two row classifications are structural, not string matches:**
   - marker row — `"entity" in row && !("layer" in row)` (the eight rows 1–8)
   - the collective control — the row with `layer` and neither `entity` nor `swatch` (Task 3's gates pin that exactly one exists and that its `layer` is `"entities"`)
3. **Row rendering.** Each row renders its swatch, `row.label`, and `row.note` when present — the notes are the only place the panel says "this hue is per-composite" or "opacity tracks intensity". Rows carrying `layer` also render a checkbox.
4. **Swatch painting.** Each of the **14 visual** rows gets a `<canvas width="6" height="6">`, sized to 24 px by CSS only. The collective control (row 14) has no swatch: render its label and checkbox with a 24 px spacer so the column stays aligned. Fill `BACKGROUND_COLOR` over the whole 6×6 first — `toxinColor`/`mutagenColor` return alpha values that only read correctly over black. Then:
   - `entity` rows: `paintMarker(paintOps(ctx), row.entity, cellOrigin(0), cellOrigin(0))`
   - `swatch` rows: fill `CONTENT_SIZE`×`CONTENT_SIZE` at `cellOrigin(0)` with `row.swatch`
5. **`render()` is exactly `drawWorld` plus the render-stats text.** `updatePanels`, `pushHistory`, and `drawSeries` stay in `onWorld`. Folding `pushHistory` in would append a duplicate population sample on every checkbox click and silently compress the 512-point series' time axis.
6. **`render()` passes `layers` into `drawWorld`.** The state object is `{ grid: dims, rocks, entities, env, layers }` — `layers` is the fourth key, and without it every checkbox is a silent no-op that no automated gate can see.
7. **`render()` is safe before the first frame.** `dims` and `rocks` are set at bootstrap, so it passes `entities: lastFrame?.entities ?? []`, `env: lastFrame?.env ?? {}`, and reads `tick ${lastFrame?.tick ?? "—"}`. No guard-return — background, grid, and rocks are honest output.
8. **`#status` stays socket-owned.** The tick / render-ms readout moves to a new `#render-stats` element, written through a handle named `renderStatsEl` (the JUnit pattern in Step 1 greps for that name). `render()` must not write to `statusEl` at all — otherwise a toggle click after a disconnect overwrites the operator's only liveness signal with a stale tick line.
9. **Dimming.** A hidden layer's row gets `.off`. Unchecking the collective control dims all eight marker rows plus its own; the nutrient row keeps its own state.
10. **Markup and CSS.** The `#knobs` heading becomes `Key / Layers` (currently `Controls`, `observer.html:31`) and `<div>(reserved)</div>` is replaced by the generated panel. Replace `#knobs { flex: 1; color: #555; }` (`:16`) with `#knobs { flex: 1; min-height: 0; overflow-y: auto; }` — `#555` is a placeholder dim, equal to `ROCK_COLOR`, with no headroom below it for the hidden cue. Add `#knobs canvas { width: 24px; height: 24px; }` and `.off { opacity: 0.4; }`. Amend the "no CSS sizing" comment at `:10-11`: the 1:1 rule governs the world canvas; legend swatches are integer-upscaled by CSS, which `image-rendering: pixelated` keeps exact.

- [ ] **Step 1: Update the JUnit gates to the new page contract, and run them RED**

Four changes in `ObserverPageServesTest.java`:

```java
// In pageDelegatesRenderingToTheExtractedModules — a third page-imported module:
assertThat(body).as("imports the legend module").contains("./observer-legend.js");

// Same test, :51-52 — the R11 intent (the measured cost reaches the page) is
// unchanged; only the element is. Retarget the pattern at the new handle.
assertThat(body).as("R11: the measured cost reaches the render-stats text")
        .containsPattern("(?s)renderStatsEl\\.textContent[^;]*renderMs");

// Same test — page contract 8. Without this, render() could write the tick line
// to BOTH elements and every other gate would stay green.
assertThat(body).as("the connection status is not overwritten by a repaint")
        .doesNotContainPattern("(?s)statusEl\\.textContent[^;]*renderMs");

// New case, mirroring markerModuleIsServedAsStaticContent / renderModuleIsServedAsStaticContent:
@Test
void legendModuleIsServedAsStaticContent() {
    ResponseEntity<String> resp = rest.getForEntity("/observer-legend.js", String.class);
    assertThat(resp.getStatusCode().is2xxSuccessful())
            .as("the page imports this module by URL — a 404 breaks the page silently").isTrue();
    assertThat(resp.getBody()).as("exports the legend rows").contains("LEGEND_ROWS");
}
```

Run: `./gradlew test --tests 'com.paralife.observer.ObserverPageServesTest'`
Expected: FAIL on `pageDelegatesRenderingToTheExtractedModules` — the page has no `./observer-legend.js` import and no `renderStatsEl`. The `doesNotContainPattern` assertion passes for now (`observer.html:89` still writes `renderMs` to `statusEl`, so it will go red the moment the page is *half* migrated — that is its job).

`legendModuleIsServedAsStaticContent` **passes immediately**: Task 3 already shipped the module, and Spring serves `src/main/resources/static/**` unconditionally. It is a 404-regression guard, not a RED step — so RED-test it explicitly: `git mv src/main/resources/static/observer-legend.js /tmp/`, re-run, quote the failure, move it back.

- [ ] **Step 2: Implement the page**

Work through contracts 1–10 above.

- [ ] **Step 3: Run the JUnit gates to verify they pass**

Run: `./gradlew test --tests 'com.paralife.observer.ObserverPageServesTest'`
Expected: PASS, all five tests.

- [ ] **Step 4: Verify the whole gate**

Run: `./gradlew check`
Expected: PASS. This runs `jsTest` (`build.gradle.kts:222`), the full JUnit suite, and `spotlessCheck`.

- [ ] **Step 5: Look at it**

Run: `./gradlew bootRun --args='--paralife.observer.enabled=true'`, open `http://localhost:8080/observer.html`, and check by eye — render fidelity is judged visually by explicit project decision, since there is no headless-browser coverage.

Confirm: all 15 rows present, labelled, and legible; the toxin, mutagen and composite rows show their notes; each swatch matches its in-world appearance; every checkbox visibly changes the canvas *immediately*, not after a tick; unchecking Entities dims all eight marker rows but not Nutrient; unchecking Grid lines leaves black gutters, not holes; the tick readout and the connection status are separate, and the connection status survives a toggle after the server is stopped.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/observer.html src/test/java/com/paralife/observer/ObserverPageServesTest.java
git commit -m "feat(observer): build the merged key and layer-toggle panel"
```

---

## Close-out

Per CLAUDE.md's close-out gates, before opening the PR:

- [ ] **Evidence-bound done** — the RED evidence from Task 1 Step 6, Task 2 Step 8 and Task 3 Step 5 is in the commit bodies, each quoting the assertion message the mutation produced.
- [ ] **Scope-diff** — one line in the PR: delivered vs the spec's intent, naming anything added or dropped.
- [ ] **Merge-back** — the spec has no canonical living-doc counterpart for client-side modules (`docs/ARCHITECTURE.md` §Observer covers server packages only), so there is no doc to fold into. State that explicitly in the PR rather than leaving the gate silently unaddressed.
