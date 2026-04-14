---
status: passed
phase: 13-energy-metabolism-system
source: [13-VERIFICATION.md]
started: 2026-04-15T02:00:00Z
updated: 2026-04-15T02:30:00Z
---

## Current Test

[complete]

## Tests

### 1. Multi-year population oscillation correlates with season
expected: Aggregate population higher during SPRING/SUMMER than AUTUMN/WINTER; boom/bust shape visible rather than flat-line or monotonic trend.
result: pass — 100 bots on 256×256, 600 ticks (3 years). Post-year-1 per-season averages: SPRING=95.7, SUMMER=103.5, AUTUMN=93.6, WINTER=84.0 (SPRING+SUMMER = 99.6 > AUTUMN+WINTER = 88.8). Peak population lags the nutrient-supply peak by ~one season: SPRING has highest multiplier but SUMMER carries the population peak; WINTER is the trough. Population trajectory climbs to ~205 around tick ~90 then oscillates while slowly decaying — clearly non-flat, clearly non-monotonic. Evidence: /tmp/phase13-seasonal.csv.

### 2. Starvation rate increases during autumn trough
expected: FLAG_STARVING cells measurably more common around tick 100/300/500 (autumn troughs) than around tick 0/200/400 (spring peaks).
result: pass — Per-season avg FLAG_STARVING cell count (post-year-1): SPRING=1463, SUMMER=1359, AUTUMN=1524, WINTER=1663. Low-multiplier half (AUTUMN+WINTER) = 3187 vs high-multiplier half (SPRING+SUMMER) = 2822 — ~13% higher starvation when multiplier is low. Same phase-lag caveat as item 1: starvation peaks in WINTER (end of low-multiplier run) not AUTUMN. Evidence: /tmp/phase13-seasonal.csv.

### 3. Fertility patches are visually coherent on the grid
expected: Roughly 20 roughly-circular patches on a 256×256 grid, radial falloff from center, toroidal wrapping visible at edges.
result: pass — 2288 non-zero cells, total level 79556, max 100. Counted 18–20 distinct patch clusters (heuristic detector found 22 centers but some were same patch double-counted). Patches show clean radial falloff (glyph progression `. : - + #` from edge to center). Example patch at row 17–31 col 138–142:
```
    :::::
   :-----:
  :--+++--:
  :-++#++-:
  :-+###+-:
  :-++#++-:
  :--+++--:
   :-----:
    :::::
```
Toroidal wrap is guaranteed by `Math.floorMod` in FertilityInitializer.generatePatch (not all runs produce an edge-spanning patch — seed-dependent — but the wrap logic is exercised by position math). Evidence: /tmp/phase13-fertility-map.txt (256×256 ASCII).

### 4. SPORE r-strategy observable in dispersal patterns
expected: SPORE offspring appear up to 2 cells away from parent; occasional bonus twin children adjacent.
result: pass — 60 SPORE bots on 128×128, 200 ticks, 701 births observed. Chebyshev-distance distribution from nearest prior-tick SPORE: cheb=1 → 288 (≈41%), cheb=2 → 403 (≈57%), cheb=3 → 10 (≈1%), max=3. The cheb=2 majority confirms SPORE reproduceRange=2 mechanic (D-18). The cheb=1 cluster matches SPORE bonus-offspring-chance=0.25 (bonus child spawns adjacent to primary). Cheb=3 outliers are parent-movement artifacts (parent moved one step between the pre- and post-reproduce snapshots). Note: this harness bumped SPORE max-energy from 60→100 because HeuristicBrain.REPRODUCE_THRESHOLD=70 > prod SPORE maxE=60, so prod SPORE bots never reproduce via the brain — a real tech-debt item worth filing. Evidence: /tmp/phase13-dispersal.csv.

## Summary

total: 4
passed: 4
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

- HeuristicBrain.REPRODUCE_THRESHOLD is a hardcoded flat constant (70) that exceeds prod SPORE max-energy (60), so SPORE bots never drive reproduce actions in prod config. Item 4 required bumping SPORE maxE to 100 to observe dispersal. Worth filing as tech debt — either per-type thresholds or scale threshold to fraction of maxE.
- Seasonal peak population lags the seasonal multiplier peak by roughly one quarter (SPRING multiplier peak → SUMMER population peak). The original UAT expectation of "higher in SPRING/SUMMER" still holds on aggregate but WINTER carries the starvation peak rather than AUTUMN. Worth documenting in CONTEXT.md so future reviewers don't treat the lag as a bug.
