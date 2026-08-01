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
} from "../../main/resources/static/observer-render.js";

import { SPECIES_COLOR, GRID_COLOR, BACKGROUND_COLOR } from "../../main/resources/static/observer-markers.js";

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
  toxin: { x: 2, y: 3 },
  mutagen: { x: 7, y: 4 },
  lightning: { x: 0, y: 2 },
};

function paintedWorld() {
  const ctx = recordingContext();
  drawWorld(ctx, {
    grid: { width: 8, height: 5 },
    rocks: [PLACED.rock],
    entities: [{ ...PLACED.entity, kind: "particle", species: "CATALYST", brained: true }],
    env: {
      toxin: [{ ...PLACED.toxin, intensity: 200 }],
      mutagen: [{ ...PLACED.mutagen, strain: 9 }],
      lightning: [PLACED.lightning],
    },
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

test("the lightning colour is the exact contract value", () => {
  assert.equal(LIGHTNING_COLOR, "#ffb");
  assert.equal(ROCK_COLOR, "#555");
});
