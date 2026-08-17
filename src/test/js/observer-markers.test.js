import { test } from "node:test";
import assert from "node:assert/strict";

import {
  CONTENT_SIZE,
  CELL_PITCH,
  SPECIES_COLOR,
  NUTRIENT_COLOR,
  MUTATION_COLOR,
  BUFF_COLOR,
  GRID_COLOR,
  BACKGROUND_COLOR,
  markerOps,
  compositeCueColor,
} from "../../main/resources/static/observer-markers.js";

// ── helpers ────────────────────────────────────────────────────────────────
// "Covers the full content square" means a FILL whose box spans the whole 5x5
// content area. An outline is a 1px stroke around the box and deliberately does
// NOT count: leaving the interior unpainted is exactly what makes an unbrained
// shell read as "shell is there but nothing running it".
const coversContent = (op) =>
  op.op === "fill" &&
  op.x <= 0 &&
  op.y <= 0 &&
  op.x + op.w >= CONTENT_SIZE &&
  op.y + op.h >= CONTENT_SIZE;

const particle = (species, extra = {}) => ({
  kind: "particle",
  species,
  brained: true,
  ...extra,
});

test("pitch is 5px of content inside a 1px border", () => {
  assert.equal(CONTENT_SIZE, 5);
  assert.equal(CELL_PITCH, 6);
});

// Hand-written literals, never the imported symbol: an expectation read back from
// the module under test moves with the bug and stays green. The structural tests
// below may then use the symbols freely, because the values are pinned here.
test("the palette is the exact contract values", () => {
  assert.deepEqual(SPECIES_COLOR, {
    CATALYST: "#e34",
    MEMBRANE: "#3d8",
    SPORE: "#59f",
  });
  assert.equal(NUTRIENT_COLOR, "#7a5");
  assert.equal(MUTATION_COLOR, "#ff0");
  assert.equal(BUFF_COLOR, "#0FF");
  assert.equal(GRID_COLOR, "#333");
  assert.equal(BACKGROUND_COLOR, "#000");
});

test("nutrient is a centred sub-cell marker in the nutrient colour", () => {
  const ops = markerOps({ kind: "nutrient" });
  assert.equal(ops.length, 1);
  const [m] = ops;
  assert.equal(m.op, "fill");
  assert.equal(m.color, NUTRIENT_COLOR);
  assert.ok(m.w < CONTENT_SIZE && m.h < CONTENT_SIZE, "sub-cell, not a full block");
  assert.ok(m.x > 0 && m.y > 0, "inset on both axes so it reads as centred");
});

test("a brained particle is a full species-colour fill", () => {
  const ops = markerOps(particle("CATALYST"));
  const filled = ops.filter(coversContent);
  assert.equal(filled.length, 1);
  assert.equal(filled[0].color, SPECIES_COLOR.CATALYST);
});

// R4(a). The defect this slice exists to remove: at the old size an unbrained
// particle rendered as a solid block. The positive control is the SAME species
// brained — so this cannot pass by the species lookup silently failing.
test("an unbrained particle emits no full-content fill, unlike its brained control", () => {
  const unbrained = markerOps(particle("MEMBRANE", { brained: false }));
  const brainedControl = markerOps(particle("MEMBRANE", { brained: true }));

  assert.equal(unbrained.filter(coversContent).length, 0, "hollow shell, not a block");
  assert.equal(brainedControl.filter(coversContent).length, 1, "control still fills");

  const shell = unbrained.find((o) => o.op === "outline");
  assert.ok(shell, "an unbrained particle still draws its species shell");
  assert.equal(shell.color, SPECIES_COLOR.MEMBRANE);
});

// R4(b). A composite member must keep its species identity legible; the
// composite cue is an addition, never a replacement.
test("a composite member keeps a full species fill and adds a strictly smaller cue", () => {
  const ops = markerOps({
    kind: "compositeMember",
    species: "SPORE",
    compositeId: "c-7",
    brained: true,
  });

  const filled = ops.filter(coversContent);
  assert.equal(filled.length, 1, "species identity is still a full fill");
  assert.equal(filled[0].color, SPECIES_COLOR.SPORE);

  const cue = ops.find((o) => o !== filled[0] && o.op === "fill");
  assert.ok(cue, "a composite cue is drawn");
  assert.ok(
    cue.w < CONTENT_SIZE && cue.h < CONTENT_SIZE,
    "the cue covers strictly less than the content square",
  );
  assert.notEqual(cue.color, SPECIES_COLOR.SPORE, "the cue is distinguishable from the species");
});

test("a composite cue colour is stable per composite id and varies across ids", () => {
  assert.equal(compositeCueColor("c-7"), compositeCueColor("c-7"));
  assert.notEqual(compositeCueColor("c-7"), compositeCueColor("c-8"));
});

test("a bonded pair shows both species, and the primary owns the shared diagonal", () => {
  const ops = markerOps({
    kind: "bondedPair",
    primarySpecies: "CATALYST",
    secondarySpecies: "SPORE",
    brained: true,
  });

  const polys = ops.filter((o) => o.op === "poly");
  assert.equal(polys.length, 2, "one triangle per species");

  const primary = polys.find((p) => p.color === SPECIES_COLOR.CATALYST);
  const secondary = polys.find((p) => p.color === SPECIES_COLOR.SPORE);
  assert.ok(primary && secondary, "both species colours are present");

  const has = (poly, x, y) => poly.points.some(([px, py]) => px === x && py === y);
  assert.ok(
    has(primary, 0, 0) && has(primary, CONTENT_SIZE, CONTENT_SIZE),
    "the primary triangle touches both ends of the shared diagonal",
  );
  assert.ok(
    !has(secondary, 0, 0) || !has(secondary, CONTENT_SIZE, CONTENT_SIZE),
    "the secondary is inset off the diagonal, making the primary the larger triangle",
  );

  const twiceArea = (points) =>
    Math.abs(
      points.reduce((sum, [x, y], index) => {
        const [nextX, nextY] = points[(index + 1) % points.length];
        return sum + x * nextY - y * nextX;
      }, 0),
    );
  assert.ok(
    twiceArea(primary.points) > twiceArea(secondary.points),
    "the primary must occupy strictly more area than the secondary",
  );
});

// The mutation cue must be readable ON TOP of the hollow shell, which is the
// hardest combination: two outlines in one cell that must not sit on top of
// each other.
test("a mutated unbrained particle draws its shell and an inset mutation cue", () => {
  const ops = markerOps(particle("SPORE", { brained: false, mutated: true }));
  const outlines = ops.filter((o) => o.op === "outline");
  assert.equal(outlines.length, 2);

  const shell = outlines.find((o) => o.color === SPECIES_COLOR.SPORE);
  const cue = outlines.find((o) => o.color === MUTATION_COLOR);
  assert.ok(shell && cue, "species shell and mutation cue are both present");
  assert.ok(
    cue.x > shell.x &&
      cue.y > shell.y &&
      cue.x + cue.w < shell.x + shell.w &&
      cue.y + cue.h < shell.y + shell.h,
    "the mutation cue is inset strictly inside the shell",
  );
});

test("a mutated brained particle keeps its fill and gains the mutation cue", () => {
  const ops = markerOps(particle("CATALYST", { mutated: true }));
  assert.equal(ops.filter(coversContent).length, 1);
  assert.ok(ops.some((o) => o.op === "outline" && o.color === MUTATION_COLOR));
});

test("a buffed brained particle keeps its fill and gains a full-cell cyan shell", () => {
  const ops = markerOps(particle("CATALYST", { buffed: true }));
  assert.equal(ops.filter(coversContent).length, 1, "the species fill is untouched");
  const cue = ops.find((o) => o.op === "outline" && o.color === BUFF_COLOR);
  assert.ok(cue, "the buff cue is present");
  assert.deepEqual(
    [cue.x, cue.y, cue.w, cue.h],
    [0, 0, CONTENT_SIZE, CONTENT_SIZE],
    "the buff shell spans the whole content square",
  );
});

// Control: no buff → no cyan outline, so the cue can't be an always-on artefact.
test("an un-buffed particle draws no buff cue", () => {
  const ops = markerOps(particle("CATALYST"));
  assert.ok(!ops.some((o) => o.color === BUFF_COLOR));
});

// Hardest combination: buffed AND mutated must show BOTH rings without overlap —
// the buff shell is the outer ring, the mutation cue the inset ring.
test("a buffed mutated particle shows the outer buff ring and the inset mutation cue", () => {
  const ops = markerOps(particle("SPORE", { buffed: true, mutated: true }));
  const buff = ops.find((o) => o.op === "outline" && o.color === BUFF_COLOR);
  const cue = ops.find((o) => o.op === "outline" && o.color === MUTATION_COLOR);
  assert.ok(buff && cue, "both rings are present");
  assert.ok(
    cue.x > buff.x &&
      cue.y > buff.y &&
      cue.x + cue.w < buff.x + buff.w &&
      cue.y + cue.h < buff.y + buff.h,
    "the mutation cue is inset strictly inside the buff ring",
  );
});

// Geometry bounds across every operation type and every marker class. A marker
// that leaks past the content square would bleed over the grid border.
test("every operation of every marker class stays inside the content square", () => {
  const cases = [
    { kind: "nutrient" },
    particle("CATALYST"),
    particle("MEMBRANE", { brained: false }),
    particle("SPORE", { brained: false, mutated: true }),
    { kind: "compositeMember", species: "SPORE", compositeId: "c-1", brained: true },
    {
      kind: "bondedPair",
      primarySpecies: "CATALYST",
      secondarySpecies: "MEMBRANE",
      brained: true,
      mutated: true,
    },
  ];

  const inBounds = (v) => v >= 0 && v <= CONTENT_SIZE;

  for (const entity of cases) {
    const ops = markerOps(entity);
    assert.ok(ops.length > 0, `${entity.kind} draws something`);
    for (const op of ops) {
      if (op.op === "poly") {
        assert.ok(op.points.length >= 3, "a polygon has at least three points");
        for (const [x, y] of op.points) {
          assert.ok(inBounds(x) && inBounds(y), `poly point ${x},${y} out of bounds`);
        }
      } else {
        assert.ok(op.w > 0 && op.h > 0, "positive drawable extents");
        assert.ok(inBounds(op.x) && inBounds(op.y), `origin ${op.x},${op.y} out of bounds`);
        assert.ok(
          inBounds(op.x + op.w) && inBounds(op.y + op.h),
          `extent ${op.x + op.w},${op.y + op.h} out of bounds`,
        );
      }
      assert.ok(typeof op.color === "string" && op.color.length > 0, "every op carries a colour");
    }
  }
});

test("an unknown species falls back to a placeholder colour without weakening known lookups", () => {
  const ops = markerOps(particle("WEIRD"));
  const filled = ops.filter(coversContent);
  assert.equal(filled.length, 1);
  assert.equal(filled[0].color, "#888");
  assert.ok(!Object.values(SPECIES_COLOR).includes(filled[0].color));
});
