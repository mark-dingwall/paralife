// Pure marker geometry for the observer visualiser. DOM-free and canvas-free: every
// function returns plain drawing data in cell-local coordinates (0..CONTENT_SIZE), so
// the contracts below are unit-testable under `node --test` with no browser.
// The painter (observer-render.js) owns all canvas calls.

export const CONTENT_SIZE = 5; // drawable square inside one cell
export const CELL_PITCH = 6; // CONTENT_SIZE + 1px grid border

export const SPECIES_COLOR = {
  CATALYST: "#e34",
  MEMBRANE: "#3d8",
  SPORE: "#59f",
};

export const UNKNOWN_SPECIES_COLOR = "#888";
export const NUTRIENT_COLOR = "#7a5";
export const MUTATION_COLOR = "#ff0";
export const BUFF_COLOR = "#0FF";
export const GRID_COLOR = "#333";
export const BACKGROUND_COLOR = "#000";
export const ROCK_COLOR = "#555";

const speciesColor = (species) => SPECIES_COLOR[species] ?? UNKNOWN_SPECIES_COLOR;

const fill = (x, y, w, h, color) => ({ op: "fill", x, y, w, h, color });
const outline = (x, y, w, h, color) => ({ op: "outline", x, y, w, h, color });
const poly = (points, color) => ({ op: "poly", points, color });

/** Stable, composite-id-derived hue. Same id → same colour, across frames and reloads. */
export function compositeCueColor(compositeId) {
  let hash = 0;
  for (let i = 0; i < compositeId.length; i++) {
    hash = (hash * 31 + compositeId.charCodeAt(i)) | 0;
  }
  return `hsl(${Math.abs(hash) % 360}, 85%, 70%)`;
}

/**
 * Drawing operations for one occupant, in paint order (identity first, cues on top).
 * `entity` is a world-frame entity DTO; `mutated` and `buffed` are true-only on the wire.
 */
export function markerOps(entity) {
  const ops = [];

  switch (entity.kind) {
    case "nutrient":
      // Centred sub-cell marker: never a full block, so it cannot be confused
      // with a brained particle.
      ops.push(fill(1, 1, 3, 3, NUTRIENT_COLOR));
      break;

    case "particle": {
      const color = speciesColor(entity.species);
      // Brained: solid, something is running it. Unbrained: shell only.
      ops.push(
        entity.brained
          ? fill(0, 0, CONTENT_SIZE, CONTENT_SIZE, color)
          : outline(0, 0, CONTENT_SIZE, CONTENT_SIZE, color),
      );
      break;
    }

    case "bondedPair": {
      // Split on the main diagonal. The primary owns the diagonal itself and is
      // therefore the larger triangle — a stable ownership rule, so the same pair
      // never flips orientation between frames.
      const c = CONTENT_SIZE;
      ops.push(
        poly(
          [
            [0, 0],
            [c, c],
            [0, c],
          ],
          speciesColor(entity.primarySpecies),
        ),
      );
      ops.push(
        poly(
          [
            [1, 0],
            [c, 0],
            [c, c - 1],
          ],
          speciesColor(entity.secondarySpecies),
        ),
      );
      break;
    }

    case "compositeMember":
      // Species identity stays a full fill; the membership cue is strictly smaller
      // so it can never hide which species this is.
      ops.push(fill(0, 0, CONTENT_SIZE, CONTENT_SIZE, speciesColor(entity.species)));
      ops.push(fill(1, 1, 2, 2, compositeCueColor(entity.compositeId)));
      break;

    default:
      break;
  }

  if (entity.mutated) {
    // Inset one pixel so it coexists with — rather than overwrites — the hollow shell.
    ops.push(outline(1, 1, CONTENT_SIZE - 2, CONTENT_SIZE - 2, MUTATION_COLOR));
  }

  if (entity.buffed) {
    // Survivor-buff cue: a full-cell cyan shell — the outer ring, distinct from the
    // inset mutation ring, so a buffed+mutated entity shows both.
    ops.push(outline(0, 0, CONTENT_SIZE, CONTENT_SIZE, BUFF_COLOR));
  }

  return ops;
}
