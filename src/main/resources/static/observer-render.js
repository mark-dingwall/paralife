// World painting for the observer visualiser. Owns every canvas call; all marker
// geometry comes from observer-markers.js as plain data.
//
// drawWorld takes its context as an argument rather than reaching for a canvas.
// That is the transferable seam: reintroducing an offscreen buffer for zoom/pan
// later means creating one and passing its context, touching nothing here.

import {
  CONTENT_SIZE,
  CELL_PITCH,
  GRID_COLOR,
  BACKGROUND_COLOR,
  ROCK_COLOR,
  markerOps,
} from "./observer-markers.js";

export { ROCK_COLOR };

export const LIGHTNING_COLOR = "#ffb";

export const LAYER_KEYS = ["entities", "nutrients", "toxin", "mutagen", "lightning", "grid"];

/** Backing-store size: one border line per cell, plus the trailing one. */
export const canvasWidth = (cellsAcross) => cellsAcross * CELL_PITCH + 1;
export const canvasHeight = (cellsDown) => cellsDown * CELL_PITCH + 1;

/** Top-left pixel of a cell's drawable content, past its leading border. */
export const cellOrigin = (index) => index * CELL_PITCH + 1;

/** Toxin is a magnitude: one hue, opacity rising with intensity (1–255). */
export function toxinColor(intensity) {
  const alpha = 0.15 + 0.6 * (intensity / 255);
  return `rgba(200, 60, 60, ${alpha.toFixed(3)})`;
}

/** Mutagen is categorical: strain picks a hue; opacity is fixed so it reads as a class, not a level. */
export function mutagenColor(strain) {
  return `hsla(${(strain * 47) % 360}, 70%, 45%, 0.400)`;
}

/** The canvas 2D surface the renderer and the legend both paint through. */
export function paintOps(ctx) {
  return {
    fillRect(x, y, w, h, color) {
      ctx.fillStyle = color;
      ctx.fillRect(x, y, w, h);
    },
    strokeRect(x, y, w, h, color) {
      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      // Half-pixel offset so a 1px stroke lands on the pixel rather than straddling two.
      ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
    },
    poly(points, color) {
      ctx.fillStyle = color;
      ctx.beginPath();
      points.forEach(([px, py], i) => (i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py)));
      ctx.closePath();
      ctx.fill();
    },
  };
}

const visible = (layers, key) => layers?.[key] !== false;

function drawCellFill(ops, x, y, color) {
  ops.fillRect(cellOrigin(x), cellOrigin(y), CONTENT_SIZE, CONTENT_SIZE, color);
}

/** Paint one entity's markers at a cell's content origin. drawWorld and the legend share this. */
export function paintMarker(ops, entity, ox, oy) {
  for (const op of markerOps(entity)) {
    if (op.op === "fill") ops.fillRect(ox + op.x, oy + op.y, op.w, op.h, op.color);
    else if (op.op === "outline") ops.strokeRect(ox + op.x, oy + op.y, op.w, op.h, op.color);
    else ops.poly(op.points.map(([px, py]) => [ox + px, oy + py]), op.color);
  }
}

/**
 * Paint one world frame. Layer order is background, grid, rocks, toxin, mutagen,
 * entities, lightning — rocks sit BELOW the environment field so a toxic or
 * mutagenic rock cell stays visible.
 */
export function drawWorld(ctx, state) {
  const ops = paintOps(ctx);
  const { width, height } = state.grid;
  const env = state.env ?? {};
  const layers = state.layers;

  const w = canvasWidth(width);
  const h = canvasHeight(height);

  ops.fillRect(0, 0, w, h, BACKGROUND_COLOR);

  if (visible(layers, "grid")) {
    for (let x = 0; x <= width; x++) ops.fillRect(x * CELL_PITCH, 0, 1, h, GRID_COLOR);
    for (let y = 0; y <= height; y++) ops.fillRect(0, y * CELL_PITCH, w, 1, GRID_COLOR);
  }

  for (const r of state.rocks ?? []) drawCellFill(ops, r.x, r.y, ROCK_COLOR);
  if (visible(layers, "toxin")) {
    for (const t of env.toxin ?? []) drawCellFill(ops, t.x, t.y, toxinColor(t.intensity));
  }
  if (visible(layers, "mutagen")) {
    for (const m of env.mutagen ?? []) drawCellFill(ops, m.x, m.y, mutagenColor(m.strain));
  }

  for (const e of state.entities ?? []) {
    const key = e.kind === "nutrient" ? "nutrients" : "entities";
    if (visible(layers, key)) paintMarker(ops, e, cellOrigin(e.x), cellOrigin(e.y));
  }

  if (visible(layers, "lightning")) {
    for (const s of env.lightning ?? []) drawCellFill(ops, s.x, s.y, LIGHTNING_COLOR);
  }
}
