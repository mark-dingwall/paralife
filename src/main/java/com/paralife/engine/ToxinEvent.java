package com.paralife.engine;

import com.paralife.world.Position;

import java.util.List;

/**
 * Immutable event record for an active toxin spread (D-03 through D-06).
 *
 * <p>A toxin event carries a pre-sampled Catmull-Rom spline path produced by
 * {@link ToxinPathGenerator}. The {@code headIdx} advances by
 * {@code config.toxin().speed()} cells per tick; {@link #hasReachedEnd} and
 * {@link #isExpired} drive despawn logic in {@code EnvironmentEngine.advanceToxin}.
 *
 * <p>Max one active event per type (D-03). Per-tick mutation is by record
 * replacement via {@link #withHeadIdx(int)} — the record is immutable.
 *
 * @param spawnTick     tick at which the event started
 * @param lifetimeTicks hard upper bound on event duration ({@link #isExpired} check)
 * @param prePath       full arc-length-sampled path; {@code headIdx} indexes in
 * @param headIdx       advancing index along {@code prePath}
 * @param seed          seed used for path generation (reserved for future visualizer replay)
 */
public record ToxinEvent(
        long spawnTick,
        long lifetimeTicks,
        List<Position> prePath,
        int headIdx,
        long seed
) {

    /** Return a new ToxinEvent with {@code headIdx} replaced. */
    public ToxinEvent withHeadIdx(int newIdx) {
        return new ToxinEvent(spawnTick, lifetimeTicks, prePath, newIdx, seed);
    }

    /** True when the head has walked to (or past) the last path point. */
    public boolean hasReachedEnd() {
        return headIdx >= prePath.size() - 1;
    }

    /** True when the current tick is at or past {@code spawnTick + lifetimeTicks}. */
    public boolean isExpired(long currentTick) {
        return currentTick >= spawnTick + lifetimeTicks;
    }
}
