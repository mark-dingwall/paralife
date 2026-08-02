# Observer key and layer toggles — design

**Date:** 2026-08-02
**Status:** approved, ready for planning
**Predecessor:** [`2026-07-18-observer-visualiser-design.md`](2026-07-18-observer-visualiser-design.md) (M5-A Slice A)

## Why

The observer visualiser renders fourteen distinct visual states with no key, so reading the
world requires reading `observer-markers.js`. Separately, a saturated default world costs
93–268 ms per frame against a 500 ms tick, dominated by the environment and nutrient layers
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
| `src/main/resources/static/observer-render.js` | exports `LAYER_KEYS`, `paintOps`, `paintMarker`; `drawWorld` honours `state.layers` |
| `src/main/resources/static/observer.html` | builds the panel, owns `layers` state, repaints on toggle; `#knobs` CSS replaced; `#render-stats` element added |
| `src/test/js/observer-legend.test.js` | **new** — drift gate, dead-row gate, row-shape gate |
| `src/test/js/observer-render.test.js` | per-key layer gating, nutrient split, back-compat |
| `src/test/java/com/paralife/observer/ObserverPageServesTest.java` | serve-check + import-check for the new module; R11 pattern retargeted |
| `build.gradle.kts` | add `observer-legend.test.js` to `requiredJsTests` |

### Toggleable layers

`entities`, `nutrients`, `toxin`, `mutagen`, `lightning`, `grid`.

Rocks are **not** toggleable. They still get a swatch row (no checkbox) so the key is complete.
They are ~6% of a saturated frame since `density-threshold` moved to 185, so a rock toggle buys
neither legibility nor speed.

### Renderer contract

`drawWorld(ctx, state)` reads `state.layers`, an object of `layerKey -> boolean`.

```js
export const LAYER_KEYS = ["entities", "nutrients", "toxin", "mutagen", "lightning", "grid"];

const visible = (layers, key) => layers?.[key] !== false;
```

`visible` treats absent, `undefined`, and any non-`false` value as visible, so an omitted
`layers` renders exactly as today and every existing caller and test is unaffected.

`visible` is silently permissive on an unknown key — a typo'd string renders always-visible and
its checkbox is inert. Nothing in `LAYER_KEYS` forces `drawWorld` to consult it, so the key set
is bound to renderer behaviour by test 2 below, which drives every key in `LAYER_KEYS` through
`drawWorld` and requires each one to change the output. That test, not the constant, is the
binding.

Nutrients are the one case that is not a flat layer: they arrive inside the `entities` array as
`kind === "nutrient"`. The entity loop therefore gates per item:

```js
const shown = e.kind === "nutrient"
  ? visible(layers, "nutrients")
  : visible(layers, "entities");
```

`bondedPair` and `compositeMember` are gated by `entities` along with particles; neither is
separately keyed.

Layer order is unchanged — background, grid, rocks, toxin, mutagen, entities, lightning. Hiding
a layer skips it; it never reorders the rest. The background fill is never gated: hiding `grid`
leaves `BACKGROUND_COLOR` gutters, not holes.

### Painter seam

The op-to-canvas dispatch is currently inlined in `drawWorld`'s entity loop
(`observer-render.js:91-95`) and `paintOps` is module-private. A legend that painted markers
itself would be a second copy of that dispatch — the real drift risk, and one the colour-constant
discipline does not cover.

So extract it, and route both callers through it:

```js
/** Paint one entity's markers at a cell's content origin. drawWorld and the legend share this. */
export function paintMarker(ops, entity, ox, oy) {
  for (const op of markerOps(entity)) {
    if (op.op === "fill") ops.fillRect(ox + op.x, oy + op.y, op.w, op.h, op.color);
    else if (op.op === "outline") ops.strokeRect(ox + op.x, oy + op.y, op.w, op.h, op.color);
    else ops.poly(op.points.map(([px, py]) => [ox + px, oy + py]), op.color);
  }
}
```

`drawWorld`'s entity loop becomes `paintMarker(ops, e, cellOrigin(e.x), cellOrigin(e.y))`. A new
op kind then reaches the legend and the world through the same branch, or neither.

`paintMarker` takes the already-built ops surface, not the raw context, and `paintOps` is exported
alongside it. Building the surface inside `paintMarker` would move `paintOps(ctx)` from
once-per-frame (`observer-render.js:72`) to once-per-entity — tens of thousands of throwaway
closure sets per frame in the hot path this slice exists to measure. Probably elided by escape
analysis, but a design should not bet a stated render budget on that. The legend calls
`paintOps(ctx)` once per swatch canvas, where the cost is fifteen. `paintOps`' docstring
("the canvas 2D surface *this module* uses") is updated to reflect that it is now shared.

### Swatch surface

Each swatch is its own `<canvas width="6" height="6">` — one whole cell pitch, drawn 1:1 — CSS-
upscaled to 24 px. `canvas { image-rendering: pixelated }` (`observer.html:12`) already makes that
crisp. Backing-store geometry is therefore *identical* to the world's, so the shared
`strokeRect` half-pixel convention and the 1 px outline width survive unchanged; a `ctx.scale()`
route would thicken every outline by the scale factor and collapse the brained/unbrained
distinction. The legend adds a scoped `#knobs canvas { width: 24px; height: 24px; }`, which beats
the global rule without conflicting — that rule sets no dimensions. The "block + no CSS sizing"
comment at `observer.html:10-11` gains a clause: the 1:1 rule governs the world canvas; legend
swatches are integer-upscaled by CSS, which `image-rendering: pixelated` keeps exact.

Every swatch canvas fills `BACKGROUND_COLOR` first, then paints. `toxinColor` and `mutagenColor`
return alpha values that only read correctly over black, so this is what makes the swatch match
what the operator sees in-world.

- **`entity` rows** call `paintMarker(paintOps(ctx), row.entity, cellOrigin(0), cellOrigin(0))`.
- **`swatch` rows** fill `CONTENT_SIZE` at `cellOrigin(0)` with `row.swatch`.

### Legend data shape

`observer-legend.js` is DOM-free and canvas-free, matching `observer-markers.js`, so
`node --test` covers it. It imports `GRID_COLOR` from `observer-markers.js` (which does not
re-export it via the render module) and `LIGHTNING_COLOR` / `toxinColor` / `mutagenColor` /
`ROCK_COLOR` from `observer-render.js`.

```js
LEGEND_ROWS = [
  { label: "Catalyst", entity: { kind: "particle", species: "CATALYST", brained: true } },
  { label: "Nutrient", entity: { kind: "nutrient" }, layer: "nutrients" },
  { label: "Toxin", swatch: toxinColor(160), note: "opaque = stronger", layer: "toxin" },
  { label: "Entities", note: "all non-nutrient markers", layer: "entities" },
  { label: "Grid lines", swatch: GRID_COLOR, layer: "grid" },
  ...
]
```

- `entity` rows carry a synthetic DTO drawn through the real `markerOps()` and the real
  `paintMarker()`. The legend cannot disagree with the world because it shares both the geometry
  and the painter.
- `swatch` rows carry a colour for the flat fills, taken from the existing exported constants or
  functions. No colour is retyped.
- `layer` names the toggle key. Absent on rows that are not toggleable. `entity` and `layer`
  compose — the nutrient row carries both.
- `note` carries a qualifier: the continuous-layer caveat, the composite hue caveat, or the
  `entities` row's scope.
- A row may carry `layer` with neither `entity` nor `swatch` — a label-only control. `entities`
  is the one such row: it gates eight marker rows collectively, so no single swatch represents
  it.

Rows to cover, in order: Catalyst / Membrane / Spore (brained), one unbrained example, unknown
species, bonded pair, composite member, mutated — then the toggleable block: nutrient, rock (no
checkbox), toxin, mutagen, lightning, entities (label-only), grid lines. Fifteen rows, fourteen
of them visual.

Exactly six rows carry a `layer`, matching `LAYER_KEYS`, with no duplicates.

Toxin and mutagen are continuous — opacity tracks intensity, hue tracks strain — so a single
swatch misrepresents them. Both take a fixed mid-range swatch plus a `note`. The composite cue
hue is derived from a hash of `compositeId` (`observer-markers.js:29-35`), so the legend's
synthetic id shows an illustrative hue, not one a real composite is guaranteed to use; its
`note` says so.

### Page behaviour

`drawWorld` currently runs only when a world frame arrives, so a checkbox would wait up to one
tick interval (500 ms by default) to take effect. The page retains `lastFrame`; both the
WebSocket handler and the toggle handler call a single `render()`.

`render()` is **exactly** `drawWorld` plus the render-stats text. `updatePanels`, `pushHistory`,
and `drawSeries` stay in `onWorld` as frame-arrival side effects — folding `pushHistory` into
`render()` would append a duplicate population sample on every checkbox click and silently
compress the 512-point series' time axis.

`render()` is safe before the first world frame: `dims` and `rocks` are set at bootstrap, so it
passes `entities: lastFrame?.entities ?? []` and `env: lastFrame?.env ?? {}` and paints
background + grid + rocks. No guard-return; `drawWorld` already tolerates that shape. The readout
reads `tick ${lastFrame?.tick ?? "—"}`, so a pre-frame render does not print `tick undefined`.

The tick / render-ms readout moves out of `#status` into its own `#render-stats` element.
`#status` stays socket-owned (`connected` / `disconnected` / `error`); otherwise a toggle click
after a disconnect would overwrite the operator's only liveness signal with a stale tick line.

The panel is built by iterating `LEGEND_ROWS` and reading `row.layer`; `layers` is initialised as
`Object.fromEntries(LAYER_KEYS.map(k => [k, true]))`. No layer-key string is typed in the page,
so the panel cannot drift from the tested constant. Inline page script is outside `jsTest`'s
reach, which is why the keys must be derived rather than written.

The two row classifications the page needs are structural, not string matches:

- **marker row** — `"entity" in row && !("layer" in row)`. Eight rows. The nutrient row carries
  both and is therefore excluded, keeping its own dim state.
- **the collective entities control** — the row with `layer` and neither `entity` nor `swatch`.
  Test 5 pins that exactly one row has that shape, so the predicate is unambiguous.

Unchecking that control dims all eight marker rows plus its own.

Rock renders as a swatch row with no checkbox. A disabled checkbox reads as broken.

**Styling.** `#knobs { flex: 1; color: #555; }` (`observer.html:16`) is a placeholder dim — the
same value as `ROCK_COLOR`, and it leaves no headroom below itself for a "hidden" cue. Replace it
with `#knobs { flex: 1; min-height: 0; overflow-y: auto; }`, inheriting the body's `#ddd`. Hidden
rows get a `.off` class at `opacity: 0.4`.

## Testing

Seven assertions, each with the mutation that RED-tests it:

1. **Drift gate** — `LEGEND_ROWS.filter(r => r.layer).map(r => r.layer)`, sorted, deep-equals
   `LAYER_KEYS` sorted. Array, not set: two rows bound to one key would bind two checkboxes to
   one flag and desynchronise on click.
   *RED:* add a key to one side only; separately, duplicate a `layer` value.

2. **Per-key layer gating, by colour identity** — `observer-render.test.js:110-119` already builds
   a fixture populating every layer and already discriminates layers by recorded `color`
   (`:127-133`). Extend that fixture with a nutrient, then for **every** `k` in `LAYER_KEYS`
   assert three things: with `{[k]: false}` the ops matching `k`'s colour predicate are **zero**;
   with `{[k]: true}` they are non-zero; and the op counts for the other five predicates — plus
   `BACKGROUND_COLOR` and `ROCK_COLOR` — are **unchanged** between the two runs.

   Colour predicates: `entities` → `SPECIES_COLOR.CATALYST`, `nutrients` → `NUTRIENT_COLOR`,
   `toxin` → `startsWith("rgba(")`, `mutagen` → `startsWith("hsla(")`, `lightning` →
   `LIGHTNING_COLOR`, `grid` → `GRID_COLOR`.

   Identity, not "fewer ops". A count-only assertion passes on crossed wiring (toxin gated on
   `visible(layers,"mutagen")` and vice versa — both keys still change the count, and the "Toxin"
   checkbox hides mutagen) and on over-gating (a `grid` check placed above the rock loop). The
   zero/non-zero pair is the positive control the project's negative-assertion rule requires, on
   one fixture with one flag flipped, so a zero cannot come from an empty fixture. The
   others-unchanged clause is also what pins the stated contract that hiding `grid` leaves
   `BACKGROUND_COLOR` gutters rather than holes.
   *RED:* delete one `visible()` call in `drawWorld`; separately, swap two keys' gates.

3. **Nutrient split both ways** — `{entities: true, nutrients: false}` draws the particle and not
   the nutrient; the inverse draws the nutrient and not the particle. Test 2 pins each key to its
   own colour but both keys act on the same loop, so the per-item branch needs its own case.
   *RED:* make the nutrient branch read the `entities` key.

4. **No dead rows** — every `entity` row (selected by `"entity" in row`, matching test 5's
   presence rule, so a blanked `entity` cannot be silently skipped) yields
   `markerOps({...row.entity, mutated: false}).length > 0`.
   The `mutated: false` override is load-bearing: the mutation outline is pushed *outside* the
   switch (`observer-markers.js:101-104`), so a raw length check passes on a typo'd `kind` when
   `mutated` is true, leaving a yellow ring with no species body. Positive control:
   `markerOps({kind: "nope"}).length === 0`.
   *RED:* typo one row's `kind`.

5. **Row shape** — checked by key *presence*, not truthiness: exactly one row (the collective
   `entities` control) may carry `layer` with neither `"entity" in row` nor `"swatch" in row`;
   every other row must have `"entity" in row` or a `swatch` that is a non-empty string.
   A truthiness-based disjunct would let a blanked toxin/mutagen/lightning/grid swatch pass on the
   strength of its `layer` key — four of the five swatch rows — which is the failure this gate
   exists for. It also pins the structural predicate the page uses to find the collective control.
   *RED:* blank one row's `swatch`; separately, give a second row the label-only shape.

6. **Distinct rows** — the `entity` rows (same `"entity" in row` selection) have pairwise
   deep-unequal `markerOps` outputs, computed on the **raw** `row.entity` — not test 4's
   `mutated: false` override, which would make the Mutated row equal its base species row and
   fail spuriously. Test 4
   proves a row draws *something*; this proves it draws *its own thing*. A Membrane row left with
   `species: "CATALYST"`, or a "Mutated" row that lost `mutated: true`, otherwise ships a
   duplicate swatch and a key that lies about the vocabulary.
   *RED:* copy one row's `entity` onto its neighbour.

7. **Back-compat** — an omitted `layers`, `layers: {}`, `layers: {toxin: undefined}`, and
   `layers: {toxin: 0}` all render toxin. The two halves do different work: the omitted / `{}` /
   `undefined` cases kill `!!` and `=== true`, both of which hide an absent key. `{toxin: 0}` is
   what kills `?? true` — `(0 ?? true) === 0`, falsy, hidden — while `0 !== false` renders it.
   Drop either half and one of the three alternatives goes unpinned.
   *RED:* change `!== false` to `=== true`, to `?? true`, and to `!!`.

`build.gradle.kts` line 202 lists the test files that must exist, because Node exits 0 on a
zero-match glob. The new file is added there or the gate passes vacuously when it is deleted.

**RED-first evidence** (close-out gate). Each gate ships with its demonstration: the mutations
named above, each shown failing and then reverted, plus the `requiredJsTests` preflight RED-tested
by deleting `src/test/js/observer-legend.test.js`, running `./gradlew jsTest`, and quoting the
`Missing required JS test file(s)` failure before restoring it.

**JUnit — three changes, not zero.**

- `pageDelegatesRenderingToTheExtractedModules` (`ObserverPageServesTest.java:40`) asserts one
  `contains` per page-imported module; `observer-legend.js` is a third and gets its line.
- The same test's R11 assertion is `containsPattern("(?s)statusEl\\.textContent[^;]*renderMs")`
  (`:51-52`). Moving the readout to `#render-stats` breaks it, so the pattern retargets to the
  new element handle and the `as(...)` message drops "status". The R11 intent — the measured cost
  reaches the page — is unchanged; only the element is.
- A `legendModuleIsServedAsStaticContent` case mirroring `:56-68`: 2xx plus
  `.contains("LEGEND_ROWS")`. A 404 on a URL-imported module breaks the page silently.

Nothing here touches simulation behaviour, so the mechanism-vs-emergence firewall is not engaged.

## Assumptions

- The operator wants defaults-all-visible on every page load.
- One 24 px swatch per row is enough; no animation cues are needed.
- The fourteen visual states above are the complete vocabulary as of `observer-markers.js` at
  `fcdebe8`, plus rock and grid (colours in `observer-markers.js:18,20`) and the three flat env
  fills, all painted by `observer-render.js`. Composite role glyphs remain deferred (`BACKLOG.md`).

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
- **No headless-browser coverage of the inline page script.** Still backlogged; mitigated here by
  deriving the panel from tested constants rather than typed strings.

## Readiness

**GO.** Pure client-side rendering and DOM work with no wire-protocol, concurrency, or
simulation surface. The two contracts worth pinning — that the key and the renderer agree on the
layer set, and that legend and world share one painter — are covered by tests 1 and 2 together
(1 binds the key to `LAYER_KEYS`, 2 binds `LAYER_KEYS` to `drawWorld`) and by the `paintMarker`
extraction respectively.

## Side benefit

With per-layer toggles, the page's `render Xms` readout becomes a per-layer cost probe. That
serves the bounded-viewport backlog item, which is currently blocked on knowing which layer to
shrink.
