# Observer key and layer toggles — design

**Date:** 2026-08-02
**Status:** approved, ready for planning
**Predecessor:** [`2026-07-18-observer-visualiser-design.md`](2026-07-18-observer-visualiser-design.md) (M5-A Slice A)

## Why

The observer visualiser renders 13 distinct visual states with no key, so reading the world
requires reading `observer-markers.js`. Separately, a saturated default world costs 93–268 ms
per frame against a 500 ms tick, dominated by the environment and nutrient layers
(`BACKLOG.md` §Observer bounded viewport). A merged key-and-layer panel answers both: it
documents the marker vocabulary and lets an operator switch off the layers that dominate cost
or obscure entities.

Client-side only. `/ws/observer` stays send-only; nothing here touches the simulation.

## What changes

A merged **Key / Layers** panel in the page's existing reserved `#knobs` slot. Every row is a
swatch plus a label; rows whose layer is toggleable also carry a checkbox. A hidden layer dims
its row, so the key always describes what is actually on screen.

### Files

| File | Change |
|---|---|
| `src/main/resources/static/observer-legend.js` | **new** — pure `LEGEND_ROWS` data |
| `src/main/resources/static/observer-render.js` | `drawWorld` honours `state.layers`; exports `LAYER_KEYS` |
| `src/main/resources/static/observer.html` | builds the panel, owns `layers` state, repaints on toggle |
| `src/test/js/observer-legend.test.js` | **new** — drift gate, no-dead-rows |
| `src/test/js/observer-render.test.js` | layer-gating and nutrient-split cases |
| `build.gradle.kts` | add `observer-legend.test.js` to `requiredJsTests` |

### Toggleable layers

`entities`, `nutrients`, `toxin`, `mutagen`, `lightning`, `grid`.

Rocks are **not** toggleable and have no key. They are ~6% of a saturated frame since
`density-threshold` moved to 185, so a rock toggle buys neither legibility nor speed.

### Renderer contract

`drawWorld(ctx, state)` reads `state.layers`, an object of `layerKey -> boolean`.

```js
export const LAYER_KEYS = ["entities", "nutrients", "toxin", "mutagen", "lightning", "grid"];

const visible = (layers, key) => layers?.[key] !== false;
```

`visible` treats absent and `undefined` as visible, so an omitted `layers` renders exactly as
today and every existing caller and test is unaffected.

Nutrients are the one case that is not a flat layer: they arrive inside the `entities` array as
`kind === "nutrient"`. The entity loop therefore gates per item:

```js
const shown = e.kind === "nutrient"
  ? visible(layers, "nutrients")
  : visible(layers, "entities");
```

Layer order is unchanged — background, grid, rocks, toxin, mutagen, entities, lightning. Hiding
a layer skips it; it never reorders the rest.

### Legend data shape

`observer-legend.js` is DOM-free and canvas-free, matching `observer-markers.js`, so
`node --test` covers it.

```js
LEGEND_ROWS = [
  { label: "Catalyst",        entity: { kind: "particle", species: "CATALYST", brained: true } },
  { label: "Toxin", swatch: toxinColor(160), note: "opaque = stronger", layer: "toxin" },
  { label: "Grid lines",      swatch: GRID_COLOR, layer: "grid" },
  ...
]
```

- `entity` rows carry a synthetic DTO drawn through the real `markerOps()`. The legend cannot
  disagree with the world because it uses the same geometry function.
- `swatch` rows carry a colour for the flat fills, taken from the existing exported constants
  (`ROCK_COLOR`, `GRID_COLOR`, `LIGHTNING_COLOR`) or the existing functions (`toxinColor`,
  `mutagenColor`). No colour is retyped.
- `layer` names the toggle key. Absent on rows that are not toggleable.
- `note` carries the qualifier for continuous layers.
- A row may carry `layer` with neither `entity` nor `swatch` — a label-only control. `entities`
  is the one such row: it gates all the marker rows collectively, so no single swatch
  represents it.

Rows to cover, in order: Catalyst / Membrane / Spore (brained), one unbrained example, unknown
species, bonded pair, composite member, mutated — then the toggleable block: nutrient, rock
(no checkbox), toxin, mutagen, lightning, entities (label-only), grid lines.

Exactly six rows carry a `layer`, matching `LAYER_KEYS`.

Toxin and mutagen are continuous — opacity tracks intensity, hue tracks strain — so a single
swatch misrepresents them. Both take a fixed mid-range swatch plus a `note`.

### Page behaviour

`drawWorld` currently runs only when a world frame arrives, so a checkbox would wait up to one
tick interval (500 ms by default) to take effect. The page retains `lastFrame`; both the
WebSocket handler and the toggle handler call a single `render()`.

Rock renders as a swatch row with no checkbox. A disabled checkbox reads as broken.

## Testing

Four assertions, each able to fail:

1. **Drift gate** — the set of `layer` values in `LEGEND_ROWS` equals `LAYER_KEYS` exactly.
   RED-test by adding a key to one side only. Without this, a layer the renderer honours but
   the key never lists is invisible to the operator.
2. **No dead rows** — every `entity` row yields `markerOps(row.entity).length > 0`. A typo'd
   `kind` falls through `markerOps`' `default:` and silently draws nothing, which presents as a
   blank swatch and reads like a CSS fault.
3. **Layer gating, each with its positive control** — `{toxin: false}` issues zero toxin fills
   *and* `{toxin: true}` issues them. Same for mutagen, lightning, grid. Per the project's
   negative-assertion rule.
4. **Nutrient split both ways** — `{entities: true, nutrients: false}` draws the particle and
   not the nutrient; the inverse draws the nutrient and not the particle.

`build.gradle.kts` line 202 lists the test files that must exist, because Node exits 0 on a
zero-match glob. The new file is added there or the gate passes vacuously when it is deleted.

No JUnit changes. Nothing here touches simulation behaviour, so the mechanism-vs-emergence
firewall is not engaged.

## Assumptions

- The operator wants defaults-all-visible on every page load.
- One swatch per row is enough; no size or animation cues are needed.
- The 13 states above are the complete marker vocabulary as of `observer-markers.js` at
  `fcdebe8`. Composite role glyphs remain deferred (`BACKLOG.md`).

## Non-goals

- **No `localStorage` persistence.** Reload resets to all-visible. Revisit if the reset
  actually annoys during use.
- **No per-species toggles.** Six layer checkboxes, not nine.
- **No rock toggle.**
- **No server contact.** `/ws/observer` stays send-only. Tick pause / step / speed and live
  parameter tuning are out of scope; both need an inbound WebSocket verb, which changes the
  endpoint's security class and must be paired with the *Observer exposure hardening* backlog
  item first.
- **No bounded viewport or zoom/pan.** Still backlogged.

## Readiness

**GO.** Pure client-side rendering and DOM work with no wire-protocol, concurrency, or
simulation surface. The one contract worth pinning — that the key and the renderer agree on the
layer set — is covered by the drift gate.

## Side benefit

With per-layer toggles, the page's existing `render Xms` readout becomes a per-layer cost probe.
That serves the bounded-viewport backlog item, which is currently blocked on knowing which layer
to shrink.
