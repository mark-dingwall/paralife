import { test } from "node:test";
import assert from "node:assert/strict";

import {
  LIGHTNING_TRAIL_TICKS,
  LIGHTNING_RGB,
  trailAlpha,
  createLightningTrail,
  discOffsets,
} from "../../main/resources/static/observer-lightning.js";

// ── EARS-8: trail lifetime + opacity ─────────────────────────────────────

test("trailAlpha is strictly decreasing across the trail window, always in (0, 1]", () => {
  const ages = Array.from({ length: LIGHTNING_TRAIL_TICKS }, (_, i) => i);
  const alphas = ages.map(trailAlpha);
  for (const a of alphas) {
    assert.ok(a > 0 && a <= 1, `trailAlpha out of (0,1]: ${a}`);
  }
  for (let i = 1; i < alphas.length; i++) {
    assert.ok(alphas[i] < alphas[i - 1], `trailAlpha not strictly decreasing at age ${i}`);
  }
});

// A range check alone is satisfied by trailAlpha returning 0.99 at age 0, which
// would silently push every arrival frame onto the rgba( path. This is the one
// line that makes "arrival frame is opaque" a real contract.
test("trailAlpha(0) is exactly 1", () => {
  assert.equal(trailAlpha(0), 1);
});

test("a strike is visible for its whole trail window and gone the tick after", () => {
  const trail = createLightningTrail();
  trail.record(10, [{ x: 3, y: 4, radius: 2 }]);

  // Positive control: present at arrival and at the last visible tick.
  assert.ok(
    trail.active(10).some((s) => s.x === 3 && s.y === 4),
    "strike absent on its arrival tick",
  );
  assert.ok(
    trail.active(10 + LIGHTNING_TRAIL_TICKS - 1).some((s) => s.x === 3 && s.y === 4),
    "strike absent on its last visible tick",
  );

  // Negative half, backed by the positive controls above.
  assert.ok(
    !trail.active(10 + LIGHTNING_TRAIL_TICKS).some((s) => s.x === 3 && s.y === 4),
    "strike still visible one tick past its trail window",
  );
});

test("re-recording the same strike at the same tick is idempotent", () => {
  const trail = createLightningTrail();
  trail.record(5, [{ x: 1, y: 1, radius: 1 }]);
  trail.record(5, [{ x: 1, y: 1, radius: 1 }]);
  assert.equal(trail.active(5).length, 1, "the same (tick, x, y) recorded twice yields one entry");
});

// TWIN GATE: keying on tick alone would pass the idempotence test above while
// silently dropping simultaneous strikes, which the engine really produces
// (EnvironmentSnapshotTest applies two centres on one tick).
test("two distinct strikes recorded at the same tick both survive", () => {
  const trail = createLightningTrail();
  trail.record(7, [
    { x: 1, y: 1, radius: 1 },
    { x: 9, y: 9, radius: 1 },
  ]);
  assert.equal(trail.active(7).length, 2, "distinct (tick, x, y) strikes must not collapse");
});

// Expired strikes are EVICTED from storage, not merely filtered out of active()'s
// result — otherwise the Map grows without bound on a long-running observer page
// and active() does O(all-strikes-ever) work every frame. Observable: a still-stored
// strike resurfaces when active() is queried within its original window; an evicted
// one cannot. record() fires every tick (even with no strikes), so it can prune.
test("expired strikes are evicted, so the trail does not accumulate forever", () => {
  const trail = createLightningTrail();
  trail.record(0, [{ x: 2, y: 2, radius: 1 }]);
  for (let t = 1; t <= LIGHTNING_TRAIL_TICKS + 3; t++) trail.record(t, []);
  assert.equal(
    trail.active(3).length,
    0,
    "an expired strike is still stored — active() only filters, storage leaks",
  );
});

// ── EARS-9: Euclidean disc geometry ──────────────────────────────────────

test("discOffsets(0) is exactly the origin", () => {
  assert.deepEqual(discOffsets(0), [[0, 0]]);
});

test("discOffsets(4) matches the engine's own <=radius distance test", () => {
  const offsets = discOffsets(4);
  const has = ([dx, dy]) => offsets.some(([x, y]) => x === dx && y === dy);

  assert.ok(has([4, 0]), "sqrt(16) <= 4 must be included");
  assert.ok(has([2, 2]), "sqrt(8) <= 4 must be included");
  assert.ok(!has([3, 3]), "sqrt(18) > 4 must be excluded");
});

test("every returned offset satisfies dx^2 + dy^2 <= radius^2", () => {
  const radius = 4;
  for (const [dx, dy] of discOffsets(radius)) {
    assert.ok(dx * dx + dy * dy <= radius * radius, `offset (${dx},${dy}) outside the disc`);
  }
});
