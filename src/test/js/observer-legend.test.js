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
