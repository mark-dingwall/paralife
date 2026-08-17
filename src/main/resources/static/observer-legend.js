// Legend row data for the observer visualiser. DOM-free and canvas-free: every
// row is plain data, painted through the real markerOps()/paintMarker() (Task 4
// owns the panel that does that painting). Node's built-in test runner covers
// this module directly, with no browser.

import { GRID_COLOR } from "./observer-markers.js";
import { ROCK_COLOR, LIGHTNING_COLOR, toxinColor, mutagenColor } from "./observer-render.js";

/**
 * One row per visual state, in panel order. DOM-free and canvas-free.
 *   label   — display text (required, rendered by the page)
 *   entity  — synthetic DTO, painted through the real markerOps()/paintMarker()
 *   swatch  — a flat colour string, for layers markerOps does not draw
 *   layer   — the LAYER_KEYS entry this row's checkbox toggles
 *   note    — a qualifier rendered beside the label
 *   defaultOff — this layer starts hidden; only meaningful with `layer`
 * A row carries `entity` or `swatch`, except the one collective control
 * (layer "entities") that carries `layer` alone. `entity` and `layer`
 * compose — the nutrient row carries both.
 */
export const LEGEND_ROWS = [
  { label: "Catalyst", entity: { kind: "particle", species: "CATALYST", brained: true } },
  { label: "Membrane", entity: { kind: "particle", species: "MEMBRANE", brained: true } },
  { label: "Spore", entity: { kind: "particle", species: "SPORE", brained: true } },
  {
    label: "Unbrained",
    entity: { kind: "particle", species: "CATALYST", brained: false },
    note: "shell only — nothing running it",
  },
  {
    label: "Unknown species",
    entity: { kind: "particle", species: "FERAL", brained: true },
    note: "falls back to the unknown-species colour",
  },
  {
    label: "Bonded pair",
    entity: { kind: "bondedPair", primarySpecies: "CATALYST", secondarySpecies: "MEMBRANE" },
  },
  {
    label: "Composite member",
    entity: { kind: "compositeMember", species: "SPORE", compositeId: "legend-sample" },
    note: "the cue hue is per-composite, so this swatch is illustrative",
  },
  {
    label: "Mutated",
    entity: { kind: "particle", species: "CATALYST", brained: true, mutated: true },
  },
  { label: "Nutrient", entity: { kind: "nutrient" }, layer: "nutrients" },
  { label: "Rock", swatch: ROCK_COLOR },
  { label: "Toxin", swatch: toxinColor(160), layer: "toxin", note: "opacity tracks intensity" },
  { label: "Mutagen", swatch: mutagenColor(3), layer: "mutagen", note: "hue tracks strain" },
  { label: "Lightning", swatch: LIGHTNING_COLOR, layer: "lightning" },
  { label: "Entities", layer: "entities", note: "all non-nutrient markers" },
  { label: "Grid lines", swatch: GRID_COLOR, layer: "grid", defaultOff: true },
];
