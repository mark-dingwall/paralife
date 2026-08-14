// Lightning trail geometry for the observer visualiser. Pure and frame-agnostic:
// the page owns the closure returned by createLightningTrail() across frames,
// so drawWorld (observer-render.js) stays a function of its arguments.

/** Total frames a strike stays visible, COUNTING its arrival frame. */
export const LIGHTNING_TRAIL_TICKS = 6;

/** Base colour; the trail supplies the alpha. Matches the existing LIGHTNING_COLOR hue. */
export const LIGHTNING_RGB = [255, 255, 187];

/**
 * Age -> opacity. trailAlpha(0) === 1 EXACTLY (load-bearing: it routes the arrival
 * frame down the opaque `#ffb` path and keeps the four existing render gates green).
 * Opacity reaches 0 at LIGHTNING_TRAIL_TICKS so an expiring strike never pops.
 * Contract: strictly decreasing over age, in (0, 1] for every drawn age.
 */
export function trailAlpha(age) {
  return 1 - age / LIGHTNING_TRAIL_TICKS;
}

/**
 * Closure over the strikes still in their trail window.
 *   record(tick, strikes)  strikes are [{x, y, radius}] from env.lightning
 *   active(tick)           -> [{x, y, radius, alpha}], newest first, expired dropped
 *
 * Dedupe key is (tick, x, y) — NOT tick alone. Frames are latest-wins and a slow
 * observer may re-render the same tick, so re-recording must be idempotent; but
 * the engine appends MULTIPLE strike centres in one tick, and keying on tick
 * alone would silently collapse two simultaneous strikes into one.
 */
export function createLightningTrail() {
  const strikes = new Map();

  return {
    record(tick, entries) {
      for (const s of entries) {
        strikes.set(`${tick}:${s.x}:${s.y}`, { x: s.x, y: s.y, radius: s.radius, tick });
      }
    },
    active(tick) {
      const result = [];
      for (const s of strikes.values()) {
        const age = tick - s.tick;
        if (age >= 0 && age < LIGHTNING_TRAIL_TICKS) {
          result.push({ x: s.x, y: s.y, radius: s.radius, alpha: trailAlpha(age) });
        }
      }
      result.sort((a, b) => b.alpha - a.alpha);
      return result;
    },
  };
}

/**
 * Cell offsets of a Euclidean disc of the given radius, centred on (0, 0).
 * Contract: an offset is included iff sqrt(dx^2 + dy^2) <= radius — the exact
 * test applyLightningAtInternal uses. Includes (0, 0). Pure; no wrap applied
 * (the caller wraps against grid dims).
 */
export function discOffsets(radius) {
  const offsets = [];
  for (let dy = -radius; dy <= radius; dy++) {
    for (let dx = -radius; dx <= radius; dx++) {
      // `+ 0` normalises -0 (from -radius when radius is 0) to 0 for deepEqual.
      if (dx * dx + dy * dy <= radius * radius) offsets.push([dx + 0, dy + 0]);
    }
  }
  return offsets;
}
