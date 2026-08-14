import { test } from "node:test";
import assert from "node:assert/strict";

import {
  canvasWidth,
  canvasHeight,
  cellOrigin,
  toxinColor,
  mutagenColor,
  ROCK_COLOR,
  LIGHTNING_COLOR,
  drawWorld,
  paintOps,
  paintMarker,
  LAYER_KEYS,
} from "../../main/resources/static/observer-render.js";

import {
  SPECIES_COLOR,
  GRID_COLOR,
  BACKGROUND_COLOR,
  NUTRIENT_COLOR,
} from "../../main/resources/static/observer-markers.js";

import { LIGHTNING_RGB } from "../../main/resources/static/observer-lightning.js";

/**
 * Minimal recording stand-in for a canvas 2D context. It records every drawing
 * call in order together with the style in force at the time, which is what makes
 * layer order assertable without a browser. `paintOps` in the renderer is limited
 * to exactly the surface implemented here.
 */
function recordingContext() {
  return {
    calls: [],
    fillStyle: null,
    strokeStyle: null,
    lineWidth: 1,
    fillRect(x, y, w, h) {
      this.calls.push({ fn: "fillRect", x, y, w, h, color: this.fillStyle });
    },
    strokeRect(x, y, w, h) {
      this.calls.push({ fn: "strokeRect", x, y, w, h, color: this.strokeStyle });
    },
    beginPath() {
      this._pts = [];
    },
    moveTo(x, y) {
      this._pts = [[x, y]];
    },
    lineTo(x, y) {
      this._pts.push([x, y]);
    },
    closePath() {},
    fill() {
      this.calls.push({ fn: "fill", points: this._pts, color: this.fillStyle });
    },
  };
}

const alphaOf = (rgbaOrHsla) => Number(rgbaOrHsla.match(/([\d.]+)\s*\)$/)[1]);
const hueOf = (hsla) => Number(hsla.match(/hsla?\(\s*([\d.]+)/)[1]);

// ── R7: sizing ─────────────────────────────────────────────────────────────

test("the default 256x256 world backs a 1537x1537 canvas", () => {
  assert.equal(canvasWidth(256), 1537);
  assert.equal(canvasHeight(256), 1537);
});

// Rectangular on purpose: a renderer that reuses width for height passes a
// square fixture and fails here.
test("width and height are sized independently", () => {
  assert.equal(canvasWidth(10), 61);
  assert.equal(canvasHeight(4), 25);
});

test("cell content origins step by the pitch, offset past the leading border", () => {
  assert.equal(cellOrigin(0), 1);
  assert.equal(cellOrigin(1), 7);
  assert.equal(cellOrigin(255), 1531);
});

// ── R6: environment semantics ──────────────────────────────────────────────

test("toxin is a magnitude ramp: opacity rises with intensity at a fixed hue", () => {
  const weak = toxinColor(30);
  const strong = toxinColor(240);
  assert.ok(alphaOf(strong) > alphaOf(weak), "opacity is monotonic with intensity");
  assert.equal(
    weak.replace(/[\d.]+\s*\)$/, ""),
    strong.replace(/[\d.]+\s*\)$/, ""),
    "only the alpha channel varies — toxin is one colour at varying strength",
  );
});

test("mutagen is categorical: strain changes hue and never opacity", () => {
  const a = mutagenColor(3);
  const b = mutagenColor(40);
  assert.notEqual(hueOf(a), hueOf(b), "distinct strains are distinct categories");
  assert.equal(alphaOf(a), alphaOf(b), "strain is not a magnitude — alpha is constant");
  assert.equal(hueOf(mutagenColor(3)), hueOf(a), "the same strain is stably the same hue");
});

// ── R5: complete layer order ───────────────────────────────────────────────

// Every coordinate has x !== y, and the grid is rectangular. A fixture on the
// diagonal of a square grid is invariant under transposition, so it cannot tell
// drawCellFill(cellOrigin(x), cellOrigin(y)) from the argument-swapped version.
const PLACED = {
  rock: { x: 1, y: 0 },
  entity: { x: 6, y: 1 },
  nutrient: { x: 4, y: 2 },
  toxin: { x: 2, y: 3 },
  mutagen: { x: 7, y: 4 },
  lightning: { x: 0, y: 2 },
};

// `layers` is passed through verbatim, so paintedWorld() with no argument is the
// all-visible / back-compat case the existing tests already assert against.
function paintedWorld(layers) {
  const ctx = recordingContext();
  drawWorld(ctx, {
    grid: { width: 8, height: 5 },
    rocks: [PLACED.rock],
    entities: [
      { ...PLACED.entity, kind: "particle", species: "CATALYST", brained: true },
      { ...PLACED.nutrient, kind: "nutrient" },
    ],
    env: {
      toxin: [{ ...PLACED.toxin, intensity: 200 }],
      mutagen: [{ ...PLACED.mutagen, strain: 9 }],
      lightning: [PLACED.lightning],
    },
    layers,
  });
  return ctx.calls;
}

test("every layer paints, in exactly the contract order", () => {
  const calls = paintedWorld();
  const firstOf = (pred) => calls.findIndex(pred);

  const background = firstOf((c) => c.color === BACKGROUND_COLOR);
  const grid = firstOf((c) => c.color === GRID_COLOR);
  const rock = firstOf((c) => c.color === ROCK_COLOR);
  const toxin = firstOf((c) => typeof c.color === "string" && c.color.startsWith("rgba("));
  const mutagen = firstOf((c) => typeof c.color === "string" && c.color.startsWith("hsla("));
  const entity = firstOf((c) => c.color === SPECIES_COLOR.CATALYST);
  const lightning = firstOf((c) => c.color === LIGHTNING_COLOR);

  for (const [name, i] of Object.entries({ background, grid, rock, toxin, mutagen, entity, lightning })) {
    assert.ok(i >= 0, `${name} layer painted nothing — the order assertion would be vacuous`);
  }

  assert.ok(background < grid, "background is beneath the grid");
  assert.ok(grid < rock, "grid is beneath the rocks");
  assert.ok(rock < toxin, "rocks are beneath toxin — this is the bug where rocks hid the env field");
  assert.ok(toxin < mutagen, "toxin is beneath mutagen");
  assert.ok(mutagen < entity, "the environment is beneath the entities");
  assert.ok(entity < lightning, "lightning is topmost");
});

test("the whole grid is drawn before the first rock, not merely one border line", () => {
  const calls = paintedWorld();
  const lastGrid = calls.map((c) => c.color).lastIndexOf(GRID_COLOR);
  const firstRock = calls.findIndex((c) => c.color === ROCK_COLOR);
  assert.ok(
    lastGrid < firstRock,
    "a rock painted mid-grid would be sliced by later grid lines",
  );
});

// Layer ORDER says nothing about layer PLACEMENT. Without this, swapping the two
// arguments to cellOrigin in drawCellFill, or swapping ox/oy in the entity loop,
// leaves every other test in this file green. Origins are hand-computed from the
// 6px pitch and 1px leading border (x * 6 + 1), never read back from cellOrigin().
test("every layer paints its cell at the pixel origin for its own coordinates", () => {
  const calls = paintedWorld();
  const at = (pred) => calls.find(pred);

  const expected = {
    rock: { color: ROCK_COLOR, x: 7, y: 1 },
    toxin: { color: toxinColor(200), x: 13, y: 19 },
    mutagen: { color: mutagenColor(9), x: 43, y: 25 },
    entity: { color: SPECIES_COLOR.CATALYST, x: 37, y: 7 },
    lightning: { color: LIGHTNING_COLOR, x: 1, y: 13 },
  };

  for (const [layer, want] of Object.entries(expected)) {
    const call = at((c) => c.color === want.color && c.fn === "fillRect");
    assert.ok(call, `${layer} painted no fill — the placement assertion would be vacuous`);
    assert.deepEqual(
      { x: call.x, y: call.y, w: call.w, h: call.h },
      { x: want.x, y: want.y, w: 5, h: 5 },
      `${layer} at cell (${PLACED[layer].x},${PLACED[layer].y}) must fill 5x5 at (${want.x},${want.y})`,
    );
  }
});

// The sizing functions being rectangular is not enough: drawWorld has to USE both.
// A width-for-height swap inside the painter is invisible to the sizing contracts.
test("the painter sizes its own background and grid from both dimensions", () => {
  const ctx = recordingContext();
  drawWorld(ctx, { grid: { width: 10, height: 4 }, rocks: [], entities: [], env: {} });

  // Hand-computed from the pitch (n * 6 + 1), NOT read back from canvasWidth/
  // canvasHeight — deriving the expectation from the code under test lets a bug
  // in the sizing helpers move the expectation along with it.
  const background = ctx.calls[0];
  assert.equal(background.color, BACKGROUND_COLOR, "background paints first");
  assert.equal(background.w, 61);
  assert.equal(background.h, 25);

  const gridCalls = ctx.calls.filter((c) => c.color === GRID_COLOR);
  const verticals = gridCalls.filter((c) => c.w === 1);
  const horizontals = gridCalls.filter((c) => c.h === 1);
  assert.equal(verticals.length, 11, "one border per column plus the trailing one");
  assert.equal(horizontals.length, 5, "one border per row plus the trailing one");
});

test("absent environment and entity collections degrade gracefully", () => {
  const ctx = recordingContext();
  drawWorld(ctx, { grid: { width: 2, height: 2 }, rocks: [], entities: [], env: {} });
  assert.ok(ctx.calls.length > 0, "background and grid still paint");
});

// Each key's ops are identified by the COLOUR they paint, never by call index.
// A count-only assertion ("fewer ops when hidden") passes on crossed wiring —
// toxin gated on the mutagen key and vice versa still changes the count for both.
const LAYER_COLOR_MATCH = {
  entities: (c) => c.color === SPECIES_COLOR.CATALYST,
  nutrients: (c) => c.color === NUTRIENT_COLOR,
  toxin: (c) => typeof c.color === "string" && c.color.startsWith("rgba("),
  mutagen: (c) => typeof c.color === "string" && c.color.startsWith("hsla("),
  lightning: (c) => c.color === LIGHTNING_COLOR,
  grid: (c) => c.color === GRID_COLOR,
};
// Never gated. Named here so the collateral clause below can assert they survive.
const UNGATED_MATCH = {
  background: (c) => c.color === BACKGROUND_COLOR,
  rock: (c) => c.color === ROCK_COLOR,
};

test("every layer key gates exactly its own layer and nothing else", () => {
  for (const key of LAYER_KEYS) {
    // A key with no predicate would otherwise crash `filter(undefined)` with a
    // TypeError and read as a broken test rather than as drift.
    assert.ok(LAYER_COLOR_MATCH[key], `no colour predicate for ${key} — LAYER_KEYS drifted`);

    const shown = paintedWorld({ [key]: true });
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

test("the lightning colour is the exact contract value", () => {
  assert.equal(LIGHTNING_COLOR, "#ffb");
  assert.equal(ROCK_COLOR, "#555");
});

// drawWorld's fixture contains only a brained particle, which markerOps resolves
// to a single `fill`. The strokeRect and poly branches of the dispatch therefore
// have no coverage anywhere. Driving them through the extracted seam pins both,
// and importing the two symbols by name makes a missing `export` a link-time
// failure here rather than a page that dies silently in the browser.
test("the shared painter dispatches outline and poly ops at the origin it is given", () => {
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

// ── E-8b: lightning trail rendering ──────────────────────────────────────
//
// Own bare fixtures, not paintedWorld(): paintedWorld() has no toxin field and
// an alpha-bearing lightning entry emits rgba(...), which would collide with
// the toxin predicate `startsWith("rgba(")` used elsewhere in this file. The
// prefix is derived from LIGHTNING_RGB so the two cannot drift.
const rgbaPrefix = `rgba(${LIGHTNING_RGB[0]}, ${LIGHTNING_RGB[1]}, ${LIGHTNING_RGB[2]},`;

test("a strike disc paints exactly its Euclidean cell count", () => {
  const ctx = recordingContext();
  drawWorld(ctx, {
    grid: { width: 8, height: 8 },
    rocks: [],
    entities: [],
    env: {},
    lightningTrail: [{ x: 4, y: 4, radius: 1, alpha: 0.5 }],
  });
  const lightningFills = ctx.calls.filter(
    (c) => c.fn === "fillRect" && typeof c.color === "string" && c.color.startsWith(rgbaPrefix),
  );
  assert.equal(lightningFills.length, 5, "radius-1 Euclidean disc is 5 cells");
});

test("hiding the lightning layer suppresses the strike disc", () => {
  const ctx = recordingContext();
  drawWorld(ctx, {
    grid: { width: 8, height: 8 },
    rocks: [],
    entities: [],
    env: {},
    lightningTrail: [{ x: 4, y: 4, radius: 1, alpha: 0.5 }],
    layers: { lightning: false },
  });
  assert.ok(
    !ctx.calls.some((c) => typeof c.color === "string" && c.color.startsWith(rgbaPrefix)),
    "lightning still painted when hidden",
  );
});

// EARS-9's toroidal wrap, which nothing in observer-lightning.test.js can catch:
// discOffsets is deliberately wrap-free and the caller wraps. alpha:0.5 is
// deliberate — without it the entry routes to the opaque literal and this gate
// would pass on an empty rgba( set, proving nothing.
test("a strike disc wraps toroidally across grid edges", () => {
  const ctx = recordingContext();
  drawWorld(ctx, {
    grid: { width: 8, height: 5 },
    rocks: [],
    entities: [],
    env: {},
    lightningTrail: [{ x: 0, y: 0, radius: 1, alpha: 0.5 }],
  });
  const lightningFills = ctx.calls.filter(
    (c) => c.fn === "fillRect" && typeof c.color === "string" && c.color.startsWith(rgbaPrefix),
  );
  const at = (x, y) => lightningFills.find((c) => c.x === x && c.y === y);

  assert.ok(at(43, 1), "west wrap: cell (7,0) must fill at its own pixel origin");
  assert.ok(at(1, 25), "north wrap: cell (0,4) must fill at its own pixel origin");
});

// The alpha === 1 => opaque half of the back-compat contract. Existing fixtures
// only exercise absent alpha, and the gates above use 0.5 — an implementation
// reading "absent ⇒ opaque, else rgba" would pass every other gate while
// violating this one.
test("alpha === 1 paints the opaque lightning colour, not rgba", () => {
  const ctx = recordingContext();
  drawWorld(ctx, {
    grid: { width: 4, height: 4 },
    rocks: [],
    entities: [],
    env: {},
    lightningTrail: [{ x: 1, y: 1, radius: 0, alpha: 1 }],
  });
  assert.ok(
    ctx.calls.some((c) => c.color === LIGHTNING_COLOR),
    "alpha:1 must still paint the opaque literal",
  );
});
